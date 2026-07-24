package io.liftandshift.strikebench.strategy;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Constructs concrete legs for a strategy family from an option chain using
 * delta-targeted strike selection. Shared by the recommendation engine and the backtester.
 * Entry prices are chain mids; callers adjust for slippage as needed.
 */
public final class StrategyBuilder {

    private StrategyBuilder() {}

    /** Bounded per-expiration search: enough width/strike diversity for DecisionPolicy without
     * turning one interactive request into an exhaustive chain optimizer. */
    public static final int MAX_SEARCH_ALTERNATIVES = 4;
    static final int MAX_VERTICAL_WING_PROBES_PER_SHORT = 18;
    static final int MAX_CONDOR_SIDES_PER_TYPE = 16;
    private static final int GLOBAL_RESERVOIR_SIZE = 12;
    private static final int BAND_RESERVOIR_SIZE = 8;
    private static final int COMBINED_BAND_RESERVOIR_SIZE = 12;
    static final int MAX_SEARCH_RETAINED_CANDIDATES = 2 * MAX_CONDOR_SIDES_PER_TYPE
            + GLOBAL_RESERVOIR_SIZE + 2 * BAND_RESERVOIR_SIZE
            + COMBINED_BAND_RESERVOIR_SIZE;

    public record Built(List<Leg> legs, List<OptionQuote> quotes, String label) {}

    /** Package-private receipt used by the large-chain regression to pin search complexity. */
    record AlternativeSearchResult(List<Built> alternatives, long quotePairEvaluations,
                                   int peakRetainedCandidates) {}

    private static final class SearchStats {
        private long quotePairEvaluations;
        private int peakRetainedCandidates;

        void evaluatedPair() { quotePairEvaluations++; }
        void retained(int count) {
            peakRetainedCandidates = Math.max(peakRetainedCandidates, count);
        }
    }

    /**
     * Intent-flow construction hints. targetPrice steers the short strike of covered calls /
     * cash-secured puts, the collar's call, and an acquisition put-calendar to the user's desired
     * sell/buy level instead of the default delta target; sharesHeld omits the stock BUY leg from
     * stock-hedged families because the account already owns the shares (the trade layer locks them
     * as coverage).
     */
    public record BuildHints(BigDecimal targetPrice, boolean sharesHeld) {
        public static final BuildHints NONE = new BuildHints(null, false);
    }

    /** Returns null when the family cannot be built from this chain. */
    public static Built build(StrategyFamily family, OptionChain chain, OptionChain farChain, BigDecimal spot) {
        return build(family, chain, farChain, spot, BuildHints.NONE);
    }

    /** Returns null when the family cannot be built from this chain. */
    public static Built build(StrategyFamily family, OptionChain chain, OptionChain farChain, BigDecimal spot,
                              BuildHints hints) {
        try {
            return switch (family) {
                case LONG_CALL -> single(chain, OptionType.CALL, LegAction.BUY, 0.50);
                case LONG_PUT -> single(chain, OptionType.PUT, LegAction.BUY, 0.50);
                case LONG_STRADDLE -> straddle(chain, spot);
                case LONG_STRANGLE -> strangle(chain);
                case NAKED_CALL -> single(chain, OptionType.CALL, LegAction.SELL, 0.30);
                case NAKED_PUT -> single(chain, OptionType.PUT, LegAction.SELL, 0.30);
                case DEBIT_CALL_SPREAD -> vertical(chain, OptionType.CALL, LegAction.BUY, 0.50, +2);
                case DEBIT_PUT_SPREAD -> vertical(chain, OptionType.PUT, LegAction.BUY, 0.50, -2);
                case CREDIT_CALL_SPREAD -> creditVertical(chain, OptionType.CALL, spot);
                case CREDIT_PUT_SPREAD -> creditVertical(
                        chain, OptionType.PUT, spot, hints.targetPrice());
                case IRON_CONDOR -> ironCondor(chain, spot);
                case IRON_BUTTERFLY -> ironButterfly(chain, spot);
                case LONG_CALL_BUTTERFLY -> butterfly(chain, OptionType.CALL, spot);
                case LONG_PUT_BUTTERFLY -> butterfly(chain, OptionType.PUT, spot);
                case CALENDAR_CALL -> calendar(chain, farChain, OptionType.CALL, spot, null);
                case CALENDAR_PUT -> calendar(chain, farChain, OptionType.PUT, spot, hints.targetPrice());
                case DIAGONAL_CALL -> diagonal(chain, farChain, OptionType.CALL, spot);
                case DIAGONAL_PUT -> diagonal(chain, farChain, OptionType.PUT, spot);
                case COVERED_CALL -> coveredCall(chain, spot, hints);
                case COVERED_STRANGLE -> coveredStrangle(chain, spot, hints);
                case COVERED_CALL_PUT_SPREAD -> coveredCallPutSpread(chain, spot, hints);
                case COVERED_CALL_CALL_OVERLAY -> coveredCallCallOverlay(chain, spot, hints);
                case CASH_SECURED_PUT -> cashSecuredPut(chain, spot, hints);
                case PROTECTIVE_COLLAR -> collar(chain, spot, hints);
                case PROTECTIVE_PUT -> protectivePut(chain, spot, hints);
                case SHORT_STRADDLE, SHORT_STRANGLE -> null; // never built, never recommended
            };
        } catch (RuntimeException e) {
            return null; // unbuildable from this chain
        }
    }

    /**
     * Additive search surface for the recommendation engine. Construction only enumerates a
     * restrained set of executable packages; it never chooses the recommendation. Every returned
     * package still passes through Guardrails, exact payoff construction, evidence assembly and the
     * shared DecisionPolicy. Families without a meaningful strike/width search retain their one
     * canonical package.
     */
    public static List<Built> buildAlternatives(StrategyFamily family, OptionChain chain,
                                                 OptionChain farChain, BigDecimal spot,
                                                 BuildHints hints) {
        return buildAlternativesWithStats(family, chain, farChain, spot, hints).alternatives();
    }

    static AlternativeSearchResult buildAlternativesWithStats(StrategyFamily family, OptionChain chain,
                                                                OptionChain farChain, BigDecimal spot,
                                                                BuildHints hints) {
        SearchStats stats = new SearchStats();
        try {
            List<Built> alternatives = switch (family) {
                case CREDIT_CALL_SPREAD -> creditVerticalAlternatives(
                        chain, OptionType.CALL, spot, false, MAX_SEARCH_ALTERNATIVES, stats);
                case CREDIT_PUT_SPREAD -> creditVerticalAlternatives(
                        chain, OptionType.PUT, spot, false, MAX_SEARCH_ALTERNATIVES, stats).stream()
                        .filter(built -> shortStrikeAtOrBelow(built, hints.targetPrice()))
                        .toList();
                case IRON_CONDOR -> ironCondorAlternatives(
                        chain, spot, MAX_SEARCH_ALTERNATIVES, stats);
                default -> {
                    Built one = build(family, chain, farChain, spot, hints);
                    yield one == null ? List.of() : List.of(one);
                }
            };
            return new AlternativeSearchResult(List.copyOf(alternatives),
                    stats.quotePairEvaluations, stats.peakRetainedCandidates);
        } catch (RuntimeException unavailable) {
            return new AlternativeSearchResult(List.of(), stats.quotePairEvaluations,
                    stats.peakRetainedCandidates);
        }
    }

    private static Built single(OptionChain chain, OptionType type, LegAction action, double targetDelta) {
        OptionQuote q = byDelta(chain, type, targetDelta);
        if (q == null) return null;
        return new Built(List.of(leg(action, q)), List.of(q),
                action + " " + strikeLabel(q) + " " + chain.expiration());
    }

    /**
     * EXECUTABLE-AWARE credit vertical (engine remediation). The old delta-target + one-strike-step
     * selection degenerated at high IV: it sold a far-OTM (~0.27-delta) strike whose tiny theoretical
     * credit was destroyed by the bid/ask, so the "credit spread" debited at executable prices (the
     * AMD 547.5/550 case). Instead, SEARCH the book: among short strikes on the OTM side and long
     * wings a sensible %-of-spot away, pick the pair with the best EXECUTABLE return-on-risk
     * (sell the short at its BID, buy the wing at its ASK), requiring the credit to be a meaningful
     * fraction of the width. This naturally lands near the money where the premium is real and the
     * book is tight — and returns null (honest) only when nothing earns a genuine credit.
     */
    private static Built creditVertical(OptionChain chain, OptionType type, BigDecimal spot) {
        return creditVertical(chain, type, spot, false);
    }

    private static Built creditVertical(OptionChain chain, OptionType type, BigDecimal spot,
                                        BigDecimal maxShortStrike) {
        SearchStats stats = new SearchStats();
        return creditVerticalAlternatives(chain, type, spot, false, MAX_SEARCH_ALTERNATIVES, stats).stream()
                .filter(built -> shortStrikeAtOrBelow(built, maxShortStrike))
                .findFirst().orElse(null);
    }

    private static boolean shortStrikeAtOrBelow(Built built, BigDecimal ceiling) {
        if (ceiling == null) return true;
        return built.legs().stream().anyMatch(leg -> !leg.isStock()
                && leg.action() == LegAction.SELL
                && leg.type() == OptionType.PUT
                && leg.strike().compareTo(ceiling) <= 0);
    }

    private static Built creditVertical(OptionChain chain, OptionType type, BigDecimal spot,
                                        boolean strictlyOutsideSpot) {
        SearchStats stats = new SearchStats();
        List<Built> alternatives = creditVerticalAlternatives(
                chain, type, spot, strictlyOutsideSpot, 1, stats);
        return alternatives.isEmpty() ? null : alternatives.getFirst();
    }

    private record VerticalPackage(Built built, double returnOnRisk,
                                   double shortDistanceFromSpot, double width,
                                   String shortBoundaryBand, String wingWidthBand) {}

    private static final Comparator<VerticalPackage> VERTICAL_RANKING = Comparator
            .comparingDouble(VerticalPackage::returnOnRisk).reversed()
            .thenComparingDouble(VerticalPackage::shortDistanceFromSpot)
            .thenComparingDouble(VerticalPackage::width)
            .thenComparing(candidate -> candidate.built().label());

    private static List<Built> creditVerticalAlternatives(OptionChain chain, OptionType type,
                                                           BigDecimal spot, boolean strictlyOutsideSpot,
                                                           int maxCount, SearchStats stats) {
        if (spot == null || spot.signum() <= 0) return List.of();
        double s = spot.doubleValue();
        List<OptionQuote> side = (type == OptionType.CALL ? chain.calls() : chain.puts()).stream()
                .filter(q -> q.strike() != null && q.bid() != null && q.ask() != null
                        && q.bid().signum() > 0 && q.ask().signum() > 0 && q.ask().compareTo(q.bid()) >= 0)
                .sorted(Comparator.comparing(OptionQuote::strike))
                .toList();
        if (side.size() < 2) return List.of();
        double minWidth = s * 0.02, maxWidth = s * 0.12;   // spread width band: 2%–12% of spot
        StratifiedReservoir<VerticalPackage> packages = new StratifiedReservoir<>(
                VERTICAL_RANKING, VerticalPackage::shortBoundaryBand,
                VerticalPackage::wingWidthBand, stats);
        for (int shortIndex = 0; shortIndex < side.size(); shortIndex++) {
            OptionQuote shortLeg = side.get(shortIndex);
            double ks = shortLeg.strike().doubleValue();
            // A standalone credit vertical may deliberately sit slightly in the money. An iron
            // condor cannot: independently maximizing the two verticals can otherwise cross the
            // short strikes and silently turn the advertised range-income shape into a long-vol
            // inverted package. Condor construction therefore keeps each short on its own side of
            // spot and validates the complete four-strike ordering below.
            boolean shortOtm = strictlyOutsideSpot
                    ? (type == OptionType.CALL ? ks > s : ks < s)
                    : (type == OptionType.CALL ? ks >= s * 0.98 : ks <= s * 1.02);
            if (!shortOtm) continue;
            for (int wingIndex : verticalWingProbeIndexes(side, shortIndex, type, minWidth, maxWidth)) {
                stats.evaluatedPair();
                OptionQuote longLeg = side.get(wingIndex);
                double kl = longLeg.strike().doubleValue();
                boolean farther = type == OptionType.CALL ? kl > ks : kl < ks;
                if (!farther) continue;
                double width = Math.abs(kl - ks);
                if (width < minWidth || width > maxWidth) continue;
                // Executable credit: sell the short at its BID, buy the wing at its ASK.
                double credit = shortLeg.bid().doubleValue() - longLeg.ask().doubleValue();
                double maxLoss = width - credit;
                if (credit <= 0 || maxLoss <= 0) continue;
                // A meaningful credit vs the width is what rejects far-OTM crumbs that slippage eats.
                if (credit < 0.20 * width) continue;
                double ror = credit / maxLoss; // executable return on risk
                Built built = new Built(List.of(leg(LegAction.SELL, shortLeg), leg(LegAction.BUY, longLeg)),
                        List.of(shortLeg, longLeg),
                        "SELL " + strikeLabel(shortLeg) + " / BUY " + strikeLabel(longLeg)
                                + " " + chain.expiration());
                packages.offer(new VerticalPackage(built, ror, Math.abs(ks - s), width,
                        shortBoundaryBand(shortLeg, s), wingWidthBand(width, s)));
            }
        }
        List<VerticalPackage> ranked = packages.ranked();
        return stratified(ranked, maxCount,
                VerticalPackage::shortBoundaryBand, VerticalPackage::wingWidthBand).stream()
                .map(VerticalPackage::built).distinct().toList();
    }

    /**
     * Probe only the nearest listed wings around economically distinct width targets. Exhaustively
     * pairing every short with every listed wing made a dense 2,000-strike chain quadratic before
     * the evaluation layer saw its four-package budget. The target grid covers the full 2%-12%
     * construction band and adjacent listed strikes absorb irregular chain spacing.
     */
    private static List<Integer> verticalWingProbeIndexes(List<OptionQuote> side, int shortIndex,
                                                           OptionType type, double minWidth,
                                                           double maxWidth) {
        double shortStrike = side.get(shortIndex).strike().doubleValue();
        double[] targetFractions = {0.0, 0.005, 0.03, 0.055, 0.08, 0.10};
        LinkedHashSet<Integer> probes = new LinkedHashSet<>();
        for (double offset : targetFractions) {
            double width = minWidth + (maxWidth - minWidth) * (offset / 0.10);
            double target = type == OptionType.CALL ? shortStrike + width : shortStrike - width;
            int insertion = lowerBoundStrike(side, target);
            for (int adjacent = -1; adjacent <= 1; adjacent++) {
                int index = insertion + adjacent;
                if (index >= 0 && index < side.size() && index != shortIndex) probes.add(index);
            }
        }
        return List.copyOf(probes);
    }

    private static int lowerBoundStrike(List<OptionQuote> side, double target) {
        int low = 0;
        int high = side.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (side.get(mid).strike().doubleValue() < target) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private static final double CONDOR_SHORT_DELTA = 0.20;

    /** Compatibility name for the shared structural quality policy. */
    public static final double MIN_IRON_CONDOR_CREDIT_TO_WIDTH =
            IronCondorQuality.MIN_CREDIT_TO_WIDEST_WING;

    private record CondorSide(
            Built built,
            OptionQuote shortLeg,
            OptionQuote longLeg,
            int shortPreference,
            int wingPreference,
            String shortBoundaryBand,
            String wingWidthBand
    ) {}

    private static final Comparator<CondorSide> CONDOR_SIDE_RANKING = Comparator
            .comparingInt(CondorSide::shortPreference)
            .thenComparingInt(CondorSide::wingPreference)
            .thenComparing(candidate -> candidate.built().label());

    private record CondorPackage(
            Built built,
            int maximumShortPreference,
            int totalShortPreference,
            int maximumWingPreference,
            int totalWingPreference,
            double creditToWidth,
            double wingBalance,
            boolean bounded,
            String shortBoundaryBand,
            String wingWidthBand
    ) {}

    private static final Comparator<CondorPackage> CONDOR_RANKING = Comparator
            .comparingInt(CondorPackage::maximumShortPreference)
            .thenComparingInt(CondorPackage::totalShortPreference)
            .thenComparingInt(CondorPackage::maximumWingPreference)
            .thenComparingInt(CondorPackage::totalWingPreference)
            .thenComparing(candidate -> candidate.built().label());

    /**
     * Builds the canonical range-credit shape as one package. A condor is not merely the two
     * highest-return credit verticals: optimizing each side independently pulls both short strikes
     * toward spot and can leave an implausibly narrow profit interval. Choose each short near the
     * conventional 20-delta probability boundary, then buy a nearby executable wing. The complete
     * package still goes through the authoritative payoff, EV, liquidity and decision assessment;
     * this construction rule only gives that assessment a genuine range-income shape to judge.
     *
     * <p>The four strikes must remain strictly ordered:
     * long put &lt; short put &lt; short call &lt; long call.</p>
     */
    private static Built ironCondor(OptionChain chain, BigDecimal spot) {
        List<Built> alternatives = ironCondorAlternatives(
                chain, spot, 1, new SearchStats());
        return alternatives.isEmpty() ? null : alternatives.getFirst();
    }

    private static List<Built> ironCondorAlternatives(OptionChain chain, BigDecimal spot,
                                                       int maxCount, SearchStats stats) {
        List<CondorSide> puts = condorSides(chain, OptionType.PUT, spot, stats);
        List<CondorSide> calls = condorSides(chain, OptionType.CALL, spot, stats);
        if (puts.isEmpty() || calls.isEmpty()) return List.of();
        stats.retained(puts.size() + calls.size());

        StratifiedReservoir<CondorPackage> viable = new StratifiedReservoir<>(
                CONDOR_RANKING, CondorPackage::shortBoundaryBand,
                CondorPackage::wingWidthBand, stats, puts.size() + calls.size());
        CondorPackage firstBounded = null;
        CondorPackage firstAny = null;
        for (CondorSide put : puts) {
            for (CondorSide call : calls) {
                stats.evaluatedPair();
                BigDecimal longPut = put.longLeg().strike();
                BigDecimal shortPut = put.shortLeg().strike();
                BigDecimal shortCall = call.shortLeg().strike();
                BigDecimal longCall = call.longLeg().strike();
                if (longPut.compareTo(shortPut) >= 0
                        || shortPut.compareTo(shortCall) >= 0
                        || shortCall.compareTo(longCall) >= 0) {
                    continue;
                }

                BigDecimal putWidth = shortPut.subtract(longPut);
                BigDecimal callWidth = longCall.subtract(shortCall);
                BigDecimal widestWing = putWidth.max(callWidth);
                BigDecimal executableCredit = put.shortLeg().bid().subtract(put.longLeg().ask())
                        .add(call.shortLeg().bid().subtract(call.longLeg().ask()));
                if (widestWing.signum() <= 0 || executableCredit.signum() <= 0) continue;

                double creditToWidth = executableCredit.doubleValue() / widestWing.doubleValue();
                IronCondorQuality.Assessment quality = IronCondorQuality.assess(
                        putWidth, callWidth, executableCredit);
                CondorPackage candidate = new CondorPackage(
                        combine(put.built(), call.built()),
                        Math.max(put.shortPreference(), call.shortPreference()),
                        put.shortPreference() + call.shortPreference(),
                        Math.max(put.wingPreference(), call.wingPreference()),
                        put.wingPreference() + call.wingPreference(),
                        creditToWidth,
                        quality.narrowToWideWing(),
                        executableCredit.compareTo(widestWing) < 0,
                        shortBoundaryBand(put.shortLeg(), spot.doubleValue()) + '|'
                                + shortBoundaryBand(call.shortLeg(), spot.doubleValue()),
                        wingWidthBand(widestWing.doubleValue(), spot.doubleValue()));
                if (firstAny == null || CONDOR_RANKING.compare(candidate, firstAny) < 0) {
                    firstAny = candidate;
                }
                if (candidate.bounded()
                        && (firstBounded == null || CONDOR_RANKING.compare(candidate, firstBounded) < 0)) {
                    firstBounded = candidate;
                }
                if (candidate.bounded() && quality.viable()) {
                    viable.offer(candidate);
                }
            }
        }
        List<CondorPackage> viablePackages = viable.ranked();
        if (!viablePackages.isEmpty()) {
            return stratified(viablePackages, maxCount,
                    CondorPackage::shortBoundaryBand, CondorPackage::wingWidthBand).stream()
                    .map(CondorPackage::built).distinct().toList();
        }

        // Preserve a concrete comparison/rejection when the chain has a bounded condor but no
        // economically meaningful one. Truly impossible quote packages remain visible to the
        // existing payoff-integrity guard only when no bounded package exists at all.
        if (firstBounded != null) return List.of(firstBounded.built());
        return firstAny == null ? List.of() : List.of(firstAny.built());
    }

    /**
     * Fixed-memory streaming selector. It keeps a small global leaderboard plus the best package
     * from a bounded number of boundary, width, and combined strata. A dense chain therefore does
     * not allocate or sort every viable pair, while a slightly lower-ranked probability boundary
     * or protective width still reaches the authoritative evaluator.
     */
    private static final class StratifiedReservoir<T> {
        private final Comparator<T> ranking;
        private final Function<T, String> boundaryBand;
        private final Function<T, String> widthBand;
        private final SearchStats stats;
        private final int retainedBase;
        private final List<T> global = new ArrayList<>(GLOBAL_RESERVOIR_SIZE);
        private final List<T> boundaries = new ArrayList<>(BAND_RESERVOIR_SIZE);
        private final List<T> widths = new ArrayList<>(BAND_RESERVOIR_SIZE);
        private final List<T> combined = new ArrayList<>(COMBINED_BAND_RESERVOIR_SIZE);

        StratifiedReservoir(Comparator<T> ranking, Function<T, String> boundaryBand,
                            Function<T, String> widthBand, SearchStats stats) {
            this(ranking, boundaryBand, widthBand, stats, 0);
        }

        StratifiedReservoir(Comparator<T> ranking, Function<T, String> boundaryBand,
                            Function<T, String> widthBand, SearchStats stats, int retainedBase) {
            this.ranking = ranking;
            this.boundaryBand = boundaryBand;
            this.widthBand = widthBand;
            this.stats = stats;
            this.retainedBase = Math.max(0, retainedBase);
        }

        void offer(T candidate) {
            offerTop(global, candidate, GLOBAL_RESERVOIR_SIZE);
            offerBestBand(boundaries, candidate, boundaryBand, BAND_RESERVOIR_SIZE);
            offerBestBand(widths, candidate, widthBand, BAND_RESERVOIR_SIZE);
            offerBestBand(combined, candidate,
                    value -> boundaryBand.apply(value) + '\u0000' + widthBand.apply(value),
                    COMBINED_BAND_RESERVOIR_SIZE);
            stats.retained(retainedBase + global.size() + boundaries.size()
                    + widths.size() + combined.size());
        }

        List<T> ranked() {
            LinkedHashSet<T> unique = new LinkedHashSet<>();
            unique.addAll(global);
            unique.addAll(boundaries);
            unique.addAll(widths);
            unique.addAll(combined);
            List<T> ranked = new ArrayList<>(unique);
            ranked.sort(ranking);
            return List.copyOf(ranked);
        }

        private void offerTop(List<T> retained, T candidate, int limit) {
            if (retained.contains(candidate)) return;
            retained.add(candidate);
            retained.sort(ranking);
            if (retained.size() > limit) retained.removeLast();
        }

        private void offerBestBand(List<T> retained, T candidate, Function<T, String> key,
                                   int limit) {
            String candidateKey = key.apply(candidate);
            for (int i = 0; i < retained.size(); i++) {
                if (Objects.equals(candidateKey, key.apply(retained.get(i)))) {
                    if (ranking.compare(candidate, retained.get(i)) < 0) retained.set(i, candidate);
                    return;
                }
            }
            if (retained.size() < limit) {
                retained.add(candidate);
                return;
            }
            int worst = 0;
            for (int i = 1; i < retained.size(); i++) {
                if (ranking.compare(retained.get(i), retained.get(worst)) > 0) worst = i;
            }
            if (ranking.compare(candidate, retained.get(worst)) < 0) retained.set(worst, candidate);
        }
    }

    /**
     * Preserve a small but economically distinct construction field before the evaluation layer
     * sees it. Pure executable return-on-risk ranking can fill every slot with several widths off
     * one near-money short strike. That makes a materially better probability boundary impossible
     * for DecisionPolicy to discover, even though the engine already knows how to evaluate it.
     *
     * <p>The first package remains the construction rank leader. The remaining bounded slots first
     * preserve another short-delta/moneyness band and another wing-width band, then fill by ranked
     * novelty. This is package enumeration only: Guardrails, economics, evidence and DecisionPolicy
     * still decide whether any package is favorable.</p>
     */
    private static <T> List<T> stratified(List<T> ranked, int rawLimit,
                                           Function<T, String> boundaryBand,
                                           Function<T, String> widthBand) {
        int limit = Math.max(1, rawLimit);
        if (ranked.isEmpty()) return List.of();
        if (limit == 1) return List.of(ranked.getFirst());

        List<T> selected = new ArrayList<>(Math.min(limit, ranked.size()));
        Set<String> boundaries = new HashSet<>();
        Set<String> widths = new HashSet<>();
        addStratum(selected, boundaries, widths, ranked.getFirst(), boundaryBand, widthBand);

        boolean addedBoundary = addFirst(ranked, selected,
                candidate -> !boundaries.contains(boundaryBand.apply(candidate))
                        && !widths.contains(widthBand.apply(candidate)),
                boundaries, widths, boundaryBand, widthBand, limit);
        if (!addedBoundary) {
            addFirst(ranked, selected,
                    candidate -> !boundaries.contains(boundaryBand.apply(candidate)),
                    boundaries, widths, boundaryBand, widthBand, limit);
        }
        addFirst(ranked, selected,
                candidate -> !widths.contains(widthBand.apply(candidate)),
                boundaries, widths, boundaryBand, widthBand, limit);
        addFirst(ranked, selected,
                candidate -> !boundaries.contains(boundaryBand.apply(candidate)),
                boundaries, widths, boundaryBand, widthBand, limit);

        while (selected.size() < limit && selected.size() < ranked.size()) {
            T best = null;
            int bestNovelty = -1;
            for (T candidate : ranked) {
                if (selected.contains(candidate)) continue;
                int novelty = (boundaries.contains(boundaryBand.apply(candidate)) ? 0 : 2)
                        + (widths.contains(widthBand.apply(candidate)) ? 0 : 1);
                if (novelty > bestNovelty) {
                    best = candidate;
                    bestNovelty = novelty;
                }
            }
            if (best == null) break;
            addStratum(selected, boundaries, widths, best, boundaryBand, widthBand);
        }
        return List.copyOf(selected);
    }

    private static <T> boolean addFirst(List<T> ranked, List<T> selected, Predicate<T> predicate,
                                         Set<String> boundaries, Set<String> widths,
                                         Function<T, String> boundaryBand,
                                         Function<T, String> widthBand, int limit) {
        if (selected.size() >= limit) return false;
        for (T candidate : ranked) {
            if (!selected.contains(candidate) && predicate.test(candidate)) {
                addStratum(selected, boundaries, widths, candidate, boundaryBand, widthBand);
                return true;
            }
        }
        return false;
    }

    private static <T> void addStratum(List<T> selected, Set<String> boundaries, Set<String> widths,
                                        T candidate, Function<T, String> boundaryBand,
                                        Function<T, String> widthBand) {
        selected.add(candidate);
        boundaries.add(boundaryBand.apply(candidate));
        widths.add(widthBand.apply(candidate));
    }

    /** Ten-delta bands when Greeks exist; 2.5%-of-spot moneyness bands otherwise. */
    private static String shortBoundaryBand(OptionQuote shortLeg, double spot) {
        Double delta = shortLeg.delta();
        if (delta != null && Double.isFinite(delta)) {
            int band = Math.max(0, Math.min(9, (int) Math.round(Math.abs(delta) * 10.0)));
            return "delta-" + band;
        }
        double distance = Math.abs(shortLeg.strike().doubleValue() - spot) / spot;
        return "moneyness-" + Math.max(0, (int) Math.floor(distance / 0.025 + 1e-9));
    }

    /** 2.5%-of-spot width bands retain narrow, medium and wider protection choices. */
    private static String wingWidthBand(double width, double spot) {
        return "width-" + Math.max(0, (int) Math.floor((width / spot) / 0.025 + 1e-9));
    }

    /**
     * One executable side of a probability-aware condor. Prefer two listed strikes between the
     * short and protective wing (a restrained, readable width), then fall back to one/three/four
     * when the chain is sparse. A side that cannot collect a credit at bid/ask is not a credit side.
     */
    private record IndexedQuote(int index, OptionQuote quote) {}

    private static List<CondorSide> condorSides(OptionChain chain, OptionType type, BigDecimal spot,
                                                SearchStats stats) {
        if (spot == null || spot.signum() <= 0) return List.of();
        double s = spot.doubleValue();
        List<OptionQuote> side = (type == OptionType.CALL ? chain.calls() : chain.puts()).stream()
                .filter(StrategyBuilder::hasExecutableBook)
                .sorted(Comparator.comparing(OptionQuote::strike))
                .toList();
        if (side.size() < 2) return List.of();

        List<IndexedQuote> shorts = java.util.stream.IntStream.range(0, side.size())
                .filter(index -> type == OptionType.CALL
                        ? side.get(index).strike().doubleValue() > s
                        : side.get(index).strike().doubleValue() < s)
                .mapToObj(index -> new IndexedQuote(index, side.get(index)))
                .sorted(Comparator
                        .comparingDouble((IndexedQuote q) -> condorDeltaDistance(q.quote(), type, s))
                        .thenComparingDouble(q -> Math.abs(q.quote().strike().doubleValue() - s))
                        .thenComparing(q -> q.quote().strike()))
                .toList();
        int direction = type == OptionType.CALL ? 1 : -1;
        int[] preferredWingSteps = {2, 1, 3, 4};
        StratifiedReservoir<CondorSide> candidates = new StratifiedReservoir<>(
                CONDOR_SIDE_RANKING, CondorSide::shortBoundaryBand,
                CondorSide::wingWidthBand, stats);
        for (int shortPreference = 0; shortPreference < shorts.size(); shortPreference++) {
            IndexedQuote indexedShort = shorts.get(shortPreference);
            OptionQuote shortLeg = indexedShort.quote();
            int shortIndex = indexedShort.index();
            for (int wingPreference = 0; wingPreference < preferredWingSteps.length; wingPreference++) {
                int steps = preferredWingSteps[wingPreference];
                int wingIndex = shortIndex + direction * steps;
                if (wingIndex < 0 || wingIndex >= side.size()) continue;
                stats.evaluatedPair();
                OptionQuote longLeg = side.get(wingIndex);
                BigDecimal credit = shortLeg.bid().subtract(longLeg.ask());
                if (credit.signum() <= 0) continue;
                Built built = new Built(
                        List.of(leg(LegAction.SELL, shortLeg), leg(LegAction.BUY, longLeg)),
                        List.of(shortLeg, longLeg),
                        "SELL " + strikeLabel(shortLeg) + " / BUY " + strikeLabel(longLeg)
                                + " " + chain.expiration());
                double width = Math.abs(shortLeg.strike().doubleValue()
                        - longLeg.strike().doubleValue());
                candidates.offer(new CondorSide(
                        built, shortLeg, longLeg, shortPreference, wingPreference,
                        shortBoundaryBand(shortLeg, s), wingWidthBand(width, s)));
            }
        }
        return stratified(candidates.ranked(), MAX_CONDOR_SIDES_PER_TYPE,
                CondorSide::shortBoundaryBand, CondorSide::wingWidthBand);
    }

    private static boolean hasExecutableBook(OptionQuote quote) {
        return quote != null && quote.strike() != null && quote.bid() != null && quote.ask() != null
                && quote.bid().signum() > 0 && quote.ask().signum() > 0
                && quote.ask().compareTo(quote.bid()) >= 0;
    }

    /** Delta is the probability-aware selector when supplied. A disclosed moneyness proxy keeps
     * fixture/imported chains without Greeks deterministic instead of silently dropping the family. */
    private static double condorDeltaDistance(OptionQuote quote, OptionType type, double spot) {
        if (quote.delta() != null && Double.isFinite(quote.delta())) {
            return Math.abs(Math.abs(quote.delta()) - CONDOR_SHORT_DELTA);
        }
        double otmFraction = type == OptionType.CALL
                ? (quote.strike().doubleValue() - spot) / spot
                : (spot - quote.strike().doubleValue()) / spot;
        return 1.0 + Math.abs(otmFraction - 0.05);
    }

    private static Built vertical(OptionChain chain, OptionType type, LegAction anchorAction, double targetDelta, int hedgeSteps) {
        OptionQuote anchor = byDelta(chain, type, targetDelta);
        OptionQuote hedge = anchor == null ? null : stepAway(chain, type, anchor.strike(), hedgeSteps);
        if (anchor == null || hedge == null) return null;
        LegAction hedgeAction = anchorAction.opposite();
        return new Built(List.of(leg(anchorAction, anchor), leg(hedgeAction, hedge)), List.of(anchor, hedge),
                anchorAction + " " + strikeLabel(anchor) + " / " + hedgeAction + " " + strikeLabel(hedge) + " " + chain.expiration());
    }

    private static Built combine(Built a, Built b) {
        if (a == null || b == null) return null;
        List<Leg> legs = new ArrayList<>(a.legs()); legs.addAll(b.legs());
        List<OptionQuote> quotes = new ArrayList<>(a.quotes()); quotes.addAll(b.quotes());
        return new Built(legs, quotes, a.label() + " + " + b.label());
    }

    /** Buy the ATM call AND put: a defined-risk bet on a big move in EITHER direction. */
    private static Built straddle(OptionChain chain, BigDecimal spot) {
        BigDecimal atm = nearestStrike(chain, spot);
        OptionQuote call = at(chain, OptionType.CALL, atm);
        OptionQuote put = at(chain, OptionType.PUT, atm);
        if (call == null || put == null) return null;
        return new Built(
                List.of(leg(LegAction.BUY, call), leg(LegAction.BUY, put)),
                List.of(call, put),
                "BUY " + atm.stripTrailingZeros().toPlainString() + " straddle " + chain.expiration());
    }

    /** Buy an OTM call and an OTM put (~30 delta): cheaper than a straddle, needs a bigger move. */
    private static Built strangle(OptionChain chain) {
        OptionQuote call = byDelta(chain, OptionType.CALL, 0.30);
        OptionQuote put = byDelta(chain, OptionType.PUT, 0.30);
        if (call == null || put == null
                || call.strike().compareTo(put.strike()) <= 0) return null; // degenerate chain
        return new Built(
                List.of(leg(LegAction.BUY, call), leg(LegAction.BUY, put)),
                List.of(call, put),
                "BUY " + put.strike().stripTrailingZeros().toPlainString() + "/"
                        + call.strike().stripTrailingZeros().toPlainString() + " strangle " + chain.expiration());
    }

    private static Built ironButterfly(OptionChain chain, BigDecimal spot) {
        BigDecimal atm = nearestStrike(chain, spot);
        OptionQuote shortCall = at(chain, OptionType.CALL, atm);
        OptionQuote shortPut = at(chain, OptionType.PUT, atm);
        OptionQuote wingCall = stepAway(chain, OptionType.CALL, atm, +2);
        OptionQuote wingPut = stepAway(chain, OptionType.PUT, atm, -2);
        if (shortCall == null || shortPut == null || wingCall == null || wingPut == null) return null;
        return new Built(
                List.of(leg(LegAction.SELL, shortCall), leg(LegAction.SELL, shortPut),
                        leg(LegAction.BUY, wingCall), leg(LegAction.BUY, wingPut)),
                List.of(shortCall, shortPut, wingCall, wingPut),
                "SELL " + atm.stripTrailingZeros().toPlainString() + " straddle, BUY wings " + chain.expiration());
    }

    private static Built butterfly(OptionChain chain, OptionType type, BigDecimal spot) {
        BigDecimal atm = nearestStrike(chain, spot);
        OptionQuote lower = stepAway(chain, type, atm, -2);
        OptionQuote middle = at(chain, type, atm);
        OptionQuote upper = stepAway(chain, type, atm, +2);
        if (lower == null || middle == null || upper == null) return null;
        Leg mid = new Leg(LegAction.SELL, type, middle.strike(), chain.expiration(), 2, mid(middle),
                Leg.SHARES_PER_CONTRACT);
        return new Built(List.of(leg(LegAction.BUY, lower), mid, leg(LegAction.BUY, upper)),
                List.of(lower, middle, upper),
                "BUY " + strikeLabel(lower) + " / SELL 2x " + strikeLabel(middle) + " / BUY " + strikeLabel(upper) + " " + chain.expiration());
    }

    private static Built calendar(OptionChain near, OptionChain far, OptionType type, BigDecimal spot,
                                  BigDecimal targetPrice) {
        if (far == null) return null;
        BigDecimal anchor = targetPrice == null ? nearestStrike(near, spot)
                : commonStrikeAtOrBelow(near, far, type, targetPrice);
        if (anchor == null) return null;
        OptionQuote nearQ = at(near, type, anchor);
        OptionQuote farQ = at(far, type, anchor);
        if (nearQ == null || farQ == null) return null;
        return new Built(List.of(leg(LegAction.SELL, nearQ), leg(LegAction.BUY, farQ)), List.of(nearQ, farQ),
                "SELL " + strikeLabel(nearQ) + " " + near.expiration() + " / BUY " + strikeLabel(farQ) + " " + far.expiration());
    }

    private static BigDecimal commonStrikeAtOrBelow(OptionChain near, OptionChain far, OptionType type,
                                                     BigDecimal target) {
        return near.strikes().stream()
                .filter(strike -> strike.compareTo(target) <= 0)
                .filter(strike -> at(near, type, strike) != null && at(far, type, strike) != null)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private static Built diagonal(OptionChain near, OptionChain far, OptionType type, BigDecimal spot) {
        if (far == null) return null;
        OptionQuote longQ = byDelta(far, type, 0.60);
        OptionQuote shortQ = shortStrike(near, type, spot, 0.30); // cap the short near-leg (far-OTM at high IV)
        if (longQ == null || shortQ == null || longQ.strike().compareTo(shortQ.strike()) == 0) return null;
        return new Built(List.of(leg(LegAction.BUY, longQ), leg(LegAction.SELL, shortQ)), List.of(longQ, shortQ),
                "BUY " + strikeLabel(longQ) + " " + far.expiration() + " / SELL " + strikeLabel(shortQ) + " " + near.expiration());
    }

    private static Built coveredCall(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote call = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : shortStrike(chain, OptionType.CALL, spot, 0.30);
        if (call == null) call = shortStrike(chain, OptionType.CALL, spot, 0.30);
        if (call == null) return null;
        if (hints.sharesHeld()) {
            return new Built(List.of(leg(LegAction.SELL, call)), listOf(call),
                    "SELL " + strikeLabel(call) + " " + chain.expiration() + " against held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.SELL, call));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null); // stock leg has no option quote
        quotes.add(call);
        return new Built(legs, quotes, "BUY 100 shares / SELL " + strikeLabel(call) + " " + chain.expiration());
    }

    /** Covered call plus a cash-secured put: double premium and a standing repurchase bid below. */
    private static Built coveredStrangle(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote call = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : shortStrike(chain, OptionType.CALL, spot, 0.30);
        if (call == null) call = shortStrike(chain, OptionType.CALL, spot, 0.30);
        OptionQuote put = shortStrike(chain, OptionType.PUT, spot, 0.25);
        if (call == null || put == null) return null;
        if (put.strike().compareTo(call.strike()) >= 0) return null; // degenerate — the strikes must bracket the price
        String label = "SELL " + strikeLabel(call) + " / SELL " + strikeLabel(put) + " " + chain.expiration();
        if (hints.sharesHeld()) {
            List<OptionQuote> quotes = new ArrayList<>();
            quotes.add(call);
            quotes.add(put);
            return new Built(List.of(leg(LegAction.SELL, call), leg(LegAction.SELL, put)), quotes,
                    label + " around held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.SELL, call), leg(LegAction.SELL, put));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null); // stock leg has no option quote
        quotes.add(call);
        quotes.add(put);
        return new Built(legs, quotes, "BUY 100 shares / " + label);
    }

    /** Covered call whose premium funds a debit put spread: a protected shelf below the shares. */
    private static Built coveredCallPutSpread(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote call = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : shortStrike(chain, OptionType.CALL, spot, 0.30);
        if (call == null) call = shortStrike(chain, OptionType.CALL, spot, 0.30);
        OptionQuote floorPut = byDelta(chain, OptionType.PUT, 0.30);
        OptionQuote fundingPut = floorPut == null ? null : stepAway(chain, OptionType.PUT, floorPut.strike(), -2);
        if (call == null || floorPut == null || fundingPut == null) return null;
        if (fundingPut.strike().compareTo(floorPut.strike()) >= 0
                || floorPut.strike().compareTo(call.strike()) >= 0) return null;
        String label = "SELL " + strikeLabel(call) + " / BUY " + strikeLabel(floorPut)
                + " / SELL " + strikeLabel(fundingPut) + " " + chain.expiration();
        if (hints.sharesHeld()) {
            List<OptionQuote> quotes = new ArrayList<>();
            quotes.add(call);
            quotes.add(floorPut);
            quotes.add(fundingPut);
            return new Built(List.of(leg(LegAction.SELL, call), leg(LegAction.BUY, floorPut),
                    leg(LegAction.SELL, fundingPut)), quotes, label + " around held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.SELL, call),
                leg(LegAction.BUY, floorPut), leg(LegAction.SELL, fundingPut));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null);
        quotes.add(call);
        quotes.add(floorPut);
        quotes.add(fundingPut);
        return new Built(legs, quotes, "BUY 100 shares / " + label);
    }

    /** Covered call plus a farther long call: upside participation resumes above the overlay strike. */
    private static Built coveredCallCallOverlay(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote shortCall = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : shortStrike(chain, OptionType.CALL, spot, 0.35);
        if (shortCall == null) shortCall = shortStrike(chain, OptionType.CALL, spot, 0.35);
        OptionQuote overlay = shortCall == null ? null : stepAway(chain, OptionType.CALL, shortCall.strike(), +2);
        if (shortCall == null || overlay == null) return null;
        if (overlay.strike().compareTo(shortCall.strike()) <= 0) return null;
        String label = "SELL " + strikeLabel(shortCall) + " / BUY " + strikeLabel(overlay) + " " + chain.expiration();
        if (hints.sharesHeld()) {
            List<OptionQuote> quotes = new ArrayList<>();
            quotes.add(shortCall);
            quotes.add(overlay);
            return new Built(List.of(leg(LegAction.SELL, shortCall), leg(LegAction.BUY, overlay)), quotes,
                    label + " over held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.SELL, shortCall),
                leg(LegAction.BUY, overlay));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null);
        quotes.add(shortCall);
        quotes.add(overlay);
        return new Built(legs, quotes, "BUY 100 shares / " + label);
    }

    private static Built cashSecuredPut(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote put = hints.targetPrice() != null
                ? strikeAtOrBelow(chain, OptionType.PUT, hints.targetPrice())
                : shortStrike(chain, OptionType.PUT, spot, 0.30);
        if (put == null) put = shortStrike(chain, OptionType.PUT, spot, 0.30);
        if (put == null) return null;
        return new Built(List.of(leg(LegAction.SELL, put)), listOf(put),
                "SELL " + strikeLabel(put) + " " + chain.expiration());
    }

    private static Built protectivePut(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote put = hints.targetPrice() != null
                ? strikeAtOrBelow(chain, OptionType.PUT, hints.targetPrice())
                : byDelta(chain, OptionType.PUT, 0.30);
        if (put == null) put = byDelta(chain, OptionType.PUT, 0.30);
        if (put == null) return null;
        if (hints.sharesHeld()) {
            return new Built(List.of(leg(LegAction.BUY, put)), listOf(put),
                    "BUY " + strikeLabel(put) + " " + chain.expiration() + " protecting held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.BUY, put));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null);
        quotes.add(put);
        return new Built(legs, quotes, "BUY 100 shares / BUY " + strikeLabel(put) + " " + chain.expiration());
    }

    private static Built collar(OptionChain chain, BigDecimal spot, BuildHints hints) {
        OptionQuote put = byDelta(chain, OptionType.PUT, 0.25); // bought protection — its distance sets the floor
        OptionQuote call = hints.targetPrice() != null
                ? strikeAtOrAbove(chain, OptionType.CALL, hints.targetPrice())
                : shortStrike(chain, OptionType.CALL, spot, 0.25);
        if (call == null) call = shortStrike(chain, OptionType.CALL, spot, 0.25);
        if (put == null || call == null) return null;
        if (put.strike().compareTo(call.strike()) >= 0) return null; // degenerate collar
        if (hints.sharesHeld()) {
            List<Leg> legs = List.of(leg(LegAction.BUY, put), leg(LegAction.SELL, call));
            List<OptionQuote> quotes = new ArrayList<>();
            quotes.add(put);
            quotes.add(call);
            return new Built(legs, quotes,
                    "BUY " + strikeLabel(put) + " / SELL " + strikeLabel(call) + " " + chain.expiration()
                            + " around held shares");
        }
        List<Leg> legs = List.of(Leg.stock(LegAction.BUY, 1, spot), leg(LegAction.BUY, put), leg(LegAction.SELL, call));
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(null);
        quotes.add(put);
        quotes.add(call);
        return new Built(legs, quotes,
                "BUY 100 shares / BUY " + strikeLabel(put) + " / SELL " + strikeLabel(call) + " " + chain.expiration());
    }

    /** Lowest marked strike at or above the target price; null when the chain tops out below it. */
    public static OptionQuote strikeAtOrAbove(OptionChain chain, OptionType type, BigDecimal target) {
        return chain.strikes().stream()
                .filter(k -> k.compareTo(target) >= 0)
                .sorted(Comparator.naturalOrder())
                .map(k -> at(chain, type, k))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** Highest marked strike at or below the target price; null when the chain bottoms out above it. */
    public static OptionQuote strikeAtOrBelow(OptionChain chain, OptionType type, BigDecimal target) {
        return chain.strikes().stream()
                .filter(k -> k.compareTo(target) <= 0)
                .sorted(Comparator.reverseOrder())
                .map(k -> at(chain, type, k))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static List<OptionQuote> listOf(OptionQuote q) {
        List<OptionQuote> quotes = new ArrayList<>();
        quotes.add(q);
        return quotes;
    }

    private static Leg leg(LegAction action, OptionQuote q) {
        return Leg.option(action, q.type(), q.strike(), q.expiration(), 1, mid(q));
    }

    private static BigDecimal mid(OptionQuote q) {
        return Objects.requireNonNull(q.mid(), "no mark");
    }

    public static OptionQuote byDelta(OptionChain chain, OptionType type, double targetAbsDelta) {
        List<OptionQuote> side = type == OptionType.CALL ? chain.calls() : chain.puts();
        return side.stream()
                .filter(q -> q.hasMark() && q.delta() != null)
                .min(Comparator.comparingDouble(q -> Math.abs(Math.abs(q.delta()) - targetAbsDelta)))
                .orElse(null);
    }

    /** Default moneyness cap for a short premium leg: a fixed delta target degenerates far OTM at high IV. */
    private static final double MAX_SHORT_OTM_FRACTION = 0.12;

    /**
     * EXECUTABLE-AWARE short strike (engine remediation). A fixed delta target degenerates at high IV:
     * a 0.30-delta short call/put can land 15–20% OTM (the AMD 96%-IV covered-strangle case), where the
     * premium collected is a thin crumb. Start from the delta target, but cap how far OTM the short leg
     * may sit; when the delta strike is beyond the cap (or missing), snap to the EXECUTABLE strike (real
     * bid) nearest the moneyness cap, where the premium is meaningful. At normal IV the delta strike is
     * already inside the cap, so this is a no-op — it only reins in the high-IV far-OTM degeneration.
     */
    private static OptionQuote shortStrike(OptionChain chain, OptionType type, BigDecimal spot,
                                           double targetDelta, double maxOtmFraction) {
        OptionQuote byD = byDelta(chain, type, targetDelta);
        if (spot == null || spot.signum() <= 0) return byD;
        double s = spot.doubleValue();
        if (byD != null && byD.strike() != null) {
            double k = byD.strike().doubleValue();
            double otm = type == OptionType.CALL ? (k - s) / s : (s - k) / s;
            if (otm <= maxOtmFraction) return byD; // within the cap — honor the delta target
        }
        double cap = type == OptionType.CALL ? s * (1 + maxOtmFraction) : s * (1 - maxOtmFraction);
        OptionQuote capped = (type == OptionType.CALL ? chain.calls() : chain.puts()).stream()
                .filter(q -> q.hasMark() && q.strike() != null && q.bid() != null && q.bid().signum() > 0)
                .filter(q -> type == OptionType.CALL // keep the short leg OTM
                        ? q.strike().doubleValue() >= s : q.strike().doubleValue() <= s)
                .min(Comparator.comparingDouble(q -> Math.abs(q.strike().doubleValue() - cap)))
                .orElse(null);
        return capped != null ? capped : byD;
    }

    private static OptionQuote shortStrike(OptionChain chain, OptionType type, BigDecimal spot, double targetDelta) {
        return shortStrike(chain, type, spot, targetDelta, MAX_SHORT_OTM_FRACTION);
    }

    public static OptionQuote at(OptionChain chain, OptionType type, BigDecimal strike) {
        return chain.find(type, strike).filter(OptionQuote::hasMark).orElse(null);
    }

    public static OptionQuote stepAway(OptionChain chain, OptionType type, BigDecimal fromStrike, int steps) {
        List<BigDecimal> strikes = chain.strikes();
        int idx = -1;
        for (int i = 0; i < strikes.size(); i++) if (strikes.get(i).compareTo(fromStrike) == 0) { idx = i; break; }
        if (idx < 0 || idx + steps < 0 || idx + steps >= strikes.size()) return null;
        return at(chain, type, strikes.get(idx + steps));
    }

    public static BigDecimal nearestStrike(OptionChain chain, BigDecimal spot) {
        return chain.strikes().stream()
                .min(Comparator.comparingDouble(k -> Math.abs(k.doubleValue() - spot.doubleValue())))
                .orElseThrow();
    }

    private static String strikeLabel(OptionQuote q) {
        return q.strike().stripTrailingZeros().toPlainString() + (q.type() == OptionType.CALL ? "C" : "P");
    }
}
