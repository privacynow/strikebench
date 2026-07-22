package io.liftandshift.strikebench.recommend;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.FourOutputAssessment;
import io.liftandshift.strikebench.eval.PortfolioExposureContext;
import io.liftandshift.strikebench.eval.PortfolioImpactComposer;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.market.Universes;
import io.liftandshift.strikebench.paper.AccountObjectiveService;
import io.liftandshift.strikebench.paper.BookActionProjectionService;
import io.liftandshift.strikebench.paper.BookRiskService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure composition over the existing Scout evaluations, compensation view, Book-risk receipts,
 * and account declarations. It creates neither candidates nor another score. Decision economics
 * and compensation remain separate rankings; Book fit can block or qualify an action without
 * changing any candidate's EV.
 */
public final class RedeploymentFrontier {
    public static final String SCHEMA_VERSION = "redeployment-frontier-v1";

    private RedeploymentFrontier() {}

    public record UniverseScope(String source, String label, List<String> symbols) {
        public UniverseScope {
            source = text(source, "universe source");
            label = text(label, "universe label");
            symbols = symbols == null ? List.of() : symbols.stream()
                    .filter(java.util.Objects::nonNull).map(String::trim)
                    .filter(value -> !value.isBlank()).map(value -> value.toUpperCase(Locale.ROOT))
                    .distinct().toList();
        }
    }

    /** One lane supplied by the existing exposure, Book-risk, liquidity, and objective owners. */
    public record BookLane(
            String lane,
            String accountId,
            String label,
            Map<String, PortfolioExposureContext> exposuresBySymbol,
            BookRiskService.AccountRisk risk,
            AccountObjectiveService.AccountCapacityPolicy capacityPolicy,
            Long encumbranceCents,
            String encumbranceAuthority
    ) {
        public BookLane {
            lane = text(lane, "Book lane").toUpperCase(Locale.ROOT);
            accountId = text(accountId, "Book account id");
            label = text(label, "Book account label");
            exposuresBySymbol = exposuresBySymbol == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(exposuresBySymbol));
            if (encumbranceCents != null && encumbranceCents < 0) {
                throw new IllegalArgumentException("Book encumbrance cannot be negative");
            }
            encumbranceAuthority = text(encumbranceAuthority, "encumbrance authority");
        }

        public BookLane(String lane, String accountId, String label,
                        PortfolioExposureContext exposure,
                        BookRiskService.AccountRisk risk,
                        AccountObjectiveService.AccountCapacityPolicy capacityPolicy,
                        Long encumbranceCents, String encumbranceAuthority) {
            this(lane, accountId, label, exposure == null ? Map.of()
                            : Map.of("*", exposure), risk, capacityPolicy,
                    encumbranceCents, encumbranceAuthority);
        }
    }

    /** Frozen close-side facts resolved from a surfaced lifecycle receipt, never client arithmetic. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RedeploymentSource(
            String receiptId,
            String accountId,
            String symbol,
            String action,
            int quantity,
            Long executableCloseCostCents,
            Long capitalReleasedCents,
            Long closingPnlCents,
            BookActionProjectionService.BookSnapshot postCloseBook,
            BookActionProjectionService.BasisEffect basisEffect,
            String authority,
            String basis
    ) {
        public RedeploymentSource {
            receiptId = text(receiptId, "lifecycle receipt id");
            accountId = text(accountId, "source account id");
            symbol = text(symbol, "source symbol").toUpperCase(Locale.ROOT);
            action = text(action, "source action").toUpperCase(Locale.ROOT);
            if (quantity <= 0) throw new IllegalArgumentException("source quantity must be positive");
            if (executableCloseCostCents != null && executableCloseCostCents < 0) {
                throw new IllegalArgumentException("close cost cannot be negative");
            }
            if (capitalReleasedCents != null && capitalReleasedCents < 0) {
                throw new IllegalArgumentException("released capital cannot be negative");
            }
            authority = text(authority, "source authority");
            basis = text(basis, "source basis");
        }
    }

    public record Context(UniverseScope universe, String destinationAccountId,
                          List<BookLane> lanes, RedeploymentSource source) {
        public Context {
            if (universe == null) throw new IllegalArgumentException("frontier universe is required");
            destinationAccountId = text(destinationAccountId, "destination account id");
            lanes = lanes == null ? List.of() : List.copyOf(lanes);
            boolean destinationPresent = false;
            for (BookLane lane : lanes) {
                if (lane.accountId().equals(destinationAccountId)) destinationPresent = true;
            }
            if (!destinationPresent) {
                throw new IllegalArgumentException("destination account is not present in the Book context");
            }
            if (source != null && !source.accountId().equals(destinationAccountId)) {
                throw new IllegalArgumentException("redeployment source and destination account must match");
            }
        }
    }

    public record DataCompleteness(String status, int observedInputs, int totalInputs,
                                   List<String> missingInputs, List<String> nonObservedInputs,
                                   List<String> limitations, String basis) {
        public DataCompleteness {
            missingInputs = copy(missingInputs);
            nonObservedInputs = copy(nonObservedInputs);
            limitations = copy(limitations);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LaneImpact(
            String lane,
            String accountId,
            String label,
            String status,
            FourOutputAssessment.PortfolioImpact dollarDelta,
            String candidateTheme,
            Double leadingThemeShareBeforePct,
            Double leadingThemeShareAfterPct,
            boolean offsetsNetDelta,
            boolean diversifiesLeadingTheme,
            boolean joinsFlaggedExpiry,
            List<AccountObjectiveService.CapacityCheck> capacityChecks,
            boolean hardBlocked,
            List<String> reasons,
            String basis
    ) {
        public LaneImpact {
            capacityChecks = capacityChecks == null ? List.of() : List.copyOf(capacityChecks);
            reasons = copy(reasons);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplacementComparison(
            String status,
            Long executableCloseCostCents,
            Long estimatedOpeningFeesCents,
            Long replacementCapitalCents,
            Long capitalReleasedCents,
            Long additionalCapitalRequiredCents,
            String evidenceStatus,
            String churnTaxStatus,
            List<String> reasons,
            String basis
    ) {
        public ReplacementComparison { reasons = copy(reasons); }
    }

    public record Entry(
            String evaluationId,
            String symbol,
            String strategy,
            double decisionScore,
            String economicVerdict,
            String qualification,
            DataCompleteness dataCompleteness,
            List<LaneImpact> bookImpacts,
            ReplacementComparison replacement,
            List<String> reasons
    ) {
        public Entry {
            bookImpacts = bookImpacts == null ? List.of() : List.copyOf(bookImpacts);
            reasons = copy(reasons);
        }
    }

    public record Result(
            String schemaVersion,
            UniverseScope universe,
            String destinationAccountId,
            List<Entry> decisionRanking,
            List<CompensationView.CompensationEntry> compensationRanking,
            String compensationBasis,
            RedeploymentSource source,
            List<String> notes,
            String basis
    ) {
        public Result {
            decisionRanking = List.copyOf(decisionRanking);
            compensationRanking = List.copyOf(compensationRanking);
            notes = copy(notes);
        }
    }

    public static Result compose(List<StrategyEvaluation> evaluations,
                                 List<CompensationView.CompensationEntry> compensation,
                                 Context context) {
        if (context == null) throw new IllegalArgumentException("frontier context is required");
        List<Entry> entries = new ArrayList<>();
        for (StrategyEvaluation evaluation : evaluations == null
                ? List.<StrategyEvaluation>of() : evaluations) {
            if (evaluation == null || evaluation.candidate() == null) continue;
            DataCompleteness completeness = completeness(evaluation);
            List<LaneImpact> impacts = context.lanes().stream()
                    .map(lane -> impact(evaluation, lane, context.source())).toList();
            LaneImpact destination = impacts.stream()
                    .filter(impact -> impact.accountId().equals(context.destinationAccountId()))
                    .findFirst().orElseThrow();
            EconomicAssessment economics = economics(evaluation);
            String qualification = qualification(evaluation, economics, completeness,
                    destination.hardBlocked());
            ReplacementComparison replacement = context.source() == null ? null
                    : replacement(evaluation, completeness, destination, context.source());
            List<String> reasons = new ArrayList<>();
            reasons.add(economics == null ? "After-cost economics are unavailable."
                    : economics.summary());
            if (destination.hardBlocked()) {
                reasons.add("The destination account's named hard capacity policy blocks this package; EV is unchanged.");
            }
            if (replacement != null && !"QUALIFIES".equals(replacement.status())) {
                reasons.add("This package does not qualify as a close-to-reopen replacement under the full frontier receipt.");
            }
            entries.add(new Entry(evaluation.id(), evaluation.symbol(), evaluation.family(),
                    evaluation.decisionScore(), economics == null ? "UNAVAILABLE" : economics.verdict().name(),
                    qualification, completeness, impacts, replacement, reasons));
        }
        entries.sort(Comparator.comparingInt(RedeploymentFrontier::decisionTier).reversed()
                .thenComparing(Comparator.comparingDouble(Entry::decisionScore).reversed())
                .thenComparing(Entry::symbol).thenComparing(Entry::strategy));
        List<String> notes = new ArrayList<>();
        if (context.source() != null && entries.stream().noneMatch(entry ->
                entry.replacement() != null && "QUALIFIES".equals(entry.replacement().status()))) {
            notes.add("No replacement qualifies after close cost, opening friction, resulting Book, evidence, and churn/tax disclosures. Capital optionality is restored; no replacement yield is invented.");
        }
        notes.add("Decision economics and compensation are independent rankings. Book fit may block an account action but never changes EV; compensation never promotes an adverse decision verdict.");
        return new Result(SCHEMA_VERSION, context.universe(), context.destinationAccountId(),
                entries, compensation == null ? List.of() : compensation,
                CompensationView.BASIS, context.source(), notes,
                "Composed from canonical StrategyEvaluation, CompensationView, BookRiskService, "
                        + "PortfolioImpactComposer, account-capacity declarations, and an optional frozen lifecycle action. "
                        + "Practice and tracked lanes remain separate and are never netted.");
    }

    private static LaneImpact impact(StrategyEvaluation evaluation, BookLane lane,
                                     RedeploymentSource source) {
        BookRiskService.AccountRisk baseRisk = lane.risk();
        Long baseEncumbrance = lane.encumbranceCents();
        if (source != null && source.accountId().equals(lane.accountId())
                && source.postCloseBook() != null) {
            baseRisk = source.postCloseBook().risk();
            baseEncumbrance = source.postCloseBook().encumbrance() == null
                    ? null : source.postCloseBook().encumbrance().cents();
        }
        CandidateUsage added = candidateUsage(evaluation);
        AccountObjectiveService.CapacityUsage before = usage(baseRisk, baseEncumbrance,
                "Current canonical Book receipt before this proposed package.");
        AccountObjectiveService.CapacityUsage after = add(before, added,
                evaluation.capitalIncrementalCents(), lane.encumbranceAuthority());
        List<AccountObjectiveService.CapacityCheck> checks = lane.capacityPolicy() == null
                ? List.of() : AccountObjectiveService.assessCapacity(lane.capacityPolicy(), after);
        boolean hardBlocked = checks.stream().anyMatch(check -> check.available() && check.breached()
                && check.enforcement() == AccountObjectiveService.Enforcement.HARD);
        boolean advisory = checks.stream().anyMatch(check -> check.available() && check.breached()
                && check.enforcement() == AccountObjectiveService.Enforcement.ADVISORY);

        PortfolioExposureContext exposure = lane.exposuresBySymbol().get(evaluation.symbol());
        if (exposure == null) exposure = lane.exposuresBySymbol().get("*");
        FourOutputAssessment.PortfolioImpacts delta = PortfolioImpactComposer.compose(
                exposure, evaluation.stance());
        FourOutputAssessment.PortfolioImpact laneDelta = "PRACTICE".equals(lane.lane())
                ? delta.practice() : delta.real();
        boolean offsets = laneDelta != null
                && magnitude(laneDelta.netExposureAfterCents()) < magnitude(laneDelta.netExposureBeforeCents());
        ThemeShares shares = themeShares(before.themeCents(), after.themeCents(), added.theme());
        Set<String> flagged = baseRisk == null ? Set.of() : baseRisk.expiries().rows().stream()
                .filter(BookRiskService.ExpiryRow::flagged).map(BookRiskService.ExpiryRow::date)
                .collect(java.util.stream.Collectors.toSet());
        boolean joinsFlagged = added.expiryCents().keySet().stream().anyMatch(flagged::contains);
        boolean diversifies = shares.leadingBefore() != null
                && !shares.leadingBefore().equalsIgnoreCase(added.theme())
                && shares.afterPct() != null && shares.beforePct() != null
                && shares.afterPct() < shares.beforePct();
        boolean worsens = joinsFlagged || (shares.leadingBefore() != null
                && shares.leadingBefore().equalsIgnoreCase(added.theme()));
        String status = hardBlocked ? "BLOCKED" : advisory || worsens ? "WORSENS"
                : offsets || diversifies ? "IMPROVES" : "NEUTRAL";
        List<String> reasons = new ArrayList<>();
        if (offsets) reasons.add("Modeled package delta reduces this lane's absolute net dollar delta.");
        if (diversifies) reasons.add("The package is outside the leading theme and reduces its notional share.");
        if (joinsFlagged) reasons.add("The package adds notional to an already flagged expiration wall.");
        if (shares.leadingBefore() != null && shares.leadingBefore().equalsIgnoreCase(added.theme())) {
            reasons.add("The package adds to the Book's leading classified theme.");
        }
        if (hardBlocked) reasons.add("At least one declared HARD capacity ceiling is breached after the hypothetical open.");
        else if (advisory) reasons.add("At least one declared ADVISORY capacity ceiling is breached after the hypothetical open.");
        if (baseRisk == null) reasons.add("Theme and expiry effects are unavailable for this lane; only its canonical dollar-delta receipt is shown.");
        return new LaneImpact(lane.lane(), lane.accountId(), lane.label(), status, laneDelta,
                added.theme(), shares.beforePct(), shares.afterPct(), offsets, diversifies,
                joinsFlagged, checks, hardBlocked, reasons,
                "Existing exposure comes from the lane's canonical receipt; candidate notional is exact strike × ratio × quantity × multiplier. "
                        + "Theme labels are classifications, not correlation claims.");
    }

    private static ReplacementComparison replacement(StrategyEvaluation evaluation,
                                                     DataCompleteness completeness,
                                                     LaneImpact destination,
                                                     RedeploymentSource source) {
        EconomicAssessment economics = economics(evaluation);
        Long openingFees = economics == null ? null
                : divideHalf(economics.estimatedRoundTripFeesCents());
        Long capital = evaluation.capitalIncrementalCents();
        Long additional = source.capitalReleasedCents() == null || capital == null ? null
                : Math.max(0, Math.subtractExact(capital, source.capitalReleasedCents()));
        boolean observed = economics != null && economics.observedEvidence()
                && "OBSERVED_COMPLETE".equals(completeness.status());
        boolean favorable = economics != null
                && economics.verdict() == EconomicAssessment.Verdict.FAVORABLE;
        boolean bookOk = !destination.hardBlocked();
        boolean capitalKnown = source.capitalReleasedCents() != null && capital != null;
        boolean fitsReleased = capitalKnown && capital <= source.capitalReleasedCents();
        String churn = evaluation.symbol().equalsIgnoreCase(source.symbol())
                ? "REVIEW_REQUIRED" : "NO_SAME_SYMBOL_REENTRY";
        boolean qualifies = favorable && observed && bookOk && capitalKnown && fitsReleased
                && !"REVIEW_REQUIRED".equals(churn);
        List<String> reasons = new ArrayList<>();
        reasons.add("Executable close cost: " + value(source.executableCloseCostCents())
                + "; replacement capital: " + value(capital)
                + "; estimated opening fees: " + value(openingFees) + ".");
        if (!capitalKnown) reasons.add("Released capital or replacement capital is unavailable; no redeployment return is claimed.");
        else if (!fitsReleased) reasons.add("The replacement needs additional capital beyond the modeled release.");
        if (!favorable) reasons.add("After-cost decision economics are not favorable.");
        if (!observed) reasons.add("End-to-end observed evidence is incomplete.");
        if (!bookOk) reasons.add("The resulting Book breaches a named hard ceiling.");
        if ("REVIEW_REQUIRED".equals(churn)) {
            reasons.add("Same-symbol re-entry requires tax-lot/wash-sale and churn review; this composer does not invent a tax result.");
        }
        if (source.basisEffect() != null) reasons.add(source.basisEffect().basis());
        return new ReplacementComparison(qualifies ? "QUALIFIES" : "DOES_NOT_QUALIFY",
                source.executableCloseCostCents(), openingFees, capital, source.capitalReleasedCents(),
                additional, observed ? "OBSERVED_COMPLETE" : completeness.status(), churn, reasons,
                "A replacement qualifies only after favorable economics, observed evidence, known capital, "
                        + "the resulting account Book, and churn/tax review. No carry/yield comparison substitutes for this receipt.");
    }

    private static String qualification(StrategyEvaluation evaluation, EconomicAssessment economics,
                                        DataCompleteness completeness, boolean hardBlocked) {
        if (hardBlocked) return "ACCOUNT_BLOCKED";
        if (!evaluation.viable()) return "MECHANICALLY_BLOCKED";
        if (economics == null || economics.verdict() == EconomicAssessment.Verdict.UNAVAILABLE) {
            return "ECONOMICS_UNAVAILABLE";
        }
        if (economics.verdict() == EconomicAssessment.Verdict.UNFAVORABLE) return "UNFAVORABLE";
        if (economics.verdict() == EconomicAssessment.Verdict.FAVORABLE
                && economics.observedEvidence()
                && "OBSERVED_COMPLETE".equals(completeness.status())) return "QUALIFIED";
        return "COMPARE_CAREFULLY";
    }

    private static int decisionTier(Entry entry) {
        return switch (entry.qualification()) {
            case "QUALIFIED" -> 6;
            case "COMPARE_CAREFULLY" -> 5;
            case "ECONOMICS_UNAVAILABLE" -> 4;
            case "UNFAVORABLE" -> 3;
            case "ACCOUNT_BLOCKED" -> 2;
            case "MECHANICALLY_BLOCKED" -> 1;
            default -> 0;
        };
    }

    private static DataCompleteness completeness(StrategyEvaluation evaluation) {
        Set<String> missing = new LinkedHashSet<>();
        Set<String> nonObserved = new LinkedHashSet<>();
        int observed = 0;
        int total = 0;
        if (evaluation.coverage() != null) {
            total = evaluation.coverage().inputs().size();
            for (var entry : evaluation.coverage().inputs().entrySet()) {
                if (entry.getValue().level().isObserved()) observed++;
                else if (entry.getValue().level() == io.liftandshift.strikebench.eval.EvidenceLevel.UNKNOWN) {
                    missing.add(entry.getKey());
                } else nonObserved.add(entry.getKey());
            }
        }
        if (evaluation.evidence() != null) {
            var endorsement = evaluation.evidence().claims().get("endorsement");
            if (endorsement != null) {
                missing.addAll(endorsement.missingDimensions());
                nonObserved.addAll(endorsement.nonObservedDimensions());
            }
        }
        EconomicAssessment economics = economics(evaluation);
        String status = economics != null && economics.observedEvidence() && missing.isEmpty()
                && nonObserved.isEmpty() ? "OBSERVED_COMPLETE"
                : !missing.isEmpty() ? "MISSING_INPUTS"
                : !nonObserved.isEmpty() ? "NON_OBSERVED_INPUTS" : "INCOMPLETE";
        return new DataCompleteness(status, observed, total, List.copyOf(missing),
                List.copyOf(nonObserved), evaluation.coverage() == null
                        ? List.of("No data-coverage receipt is attached.")
                        : evaluation.coverage().limitations(),
                "Coverage is projected from the evaluation's existing input and endorsement receipts; missing evidence is never neutralized into a favorable claim.");
    }

    private static CandidateUsage candidateUsage(StrategyEvaluation evaluation) {
        Map<String, Long> expiries = new LinkedHashMap<>();
        long notional = 0;
        boolean optionLeg = false;
        Candidate candidate = evaluation.candidate();
        for (LegView leg : candidate.legs() == null ? List.<LegView>of() : candidate.legs()) {
            if ("STOCK".equalsIgnoreCase(leg.type()) || leg.strike() == null) continue;
            optionLeg = true;
            long strikeCents = new BigDecimal(leg.strike()).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            long units = Math.multiplyExact(Math.multiplyExact((long) leg.ratio(), candidate.qty()),
                    (long) leg.multiplier());
            long legNotional = Math.multiplyExact(strikeCents, units);
            notional = Math.addExact(notional, legNotional);
            if (leg.expiration() != null) expiries.merge(leg.expiration().toUpperCase(Locale.ROOT),
                    legNotional, Math::addExact);
        }
        if (!optionLeg && evaluation.capitalEconomicCents() != null) {
            notional = evaluation.capitalEconomicCents();
        }
        String symbol = evaluation.symbol().toUpperCase(Locale.ROOT);
        String theme = Universes.allocationSectorLabel(symbol).toUpperCase(Locale.ROOT);
        return new CandidateUsage(symbol, theme, notional, Map.copyOf(expiries));
    }

    private static AccountObjectiveService.CapacityUsage usage(BookRiskService.AccountRisk risk,
                                                               Long encumbrance,
                                                               String basis) {
        Map<String, Long> symbols = new LinkedHashMap<>();
        Map<String, Long> themes = new LinkedHashMap<>();
        Map<String, Long> expiries = new LinkedHashMap<>();
        if (risk != null) {
            risk.themes().symbolNotionals().forEach(row -> symbols.merge(
                    row.symbol().toUpperCase(Locale.ROOT), row.notionalCents(), Math::addExact));
            risk.themes().rows().forEach(row -> themes.merge(
                    row.label().toUpperCase(Locale.ROOT), row.notionalCents(), Math::addExact));
            risk.expiries().rows().forEach(row -> expiries.merge(
                    row.date().toUpperCase(Locale.ROOT), row.notionalCents(), Math::addExact));
        }
        return new AccountObjectiveService.CapacityUsage(symbols, themes, expiries, encumbrance, basis);
    }

    private static AccountObjectiveService.CapacityUsage add(
            AccountObjectiveService.CapacityUsage before, CandidateUsage added,
            Long incrementalCapital, String encumbranceAuthority) {
        Map<String, Long> symbols = new LinkedHashMap<>(before.symbolCents());
        Map<String, Long> themes = new LinkedHashMap<>(before.themeCents());
        Map<String, Long> expiries = new LinkedHashMap<>(before.expiryCents());
        symbols.merge(added.symbol(), added.notionalCents(), Math::addExact);
        themes.merge(added.theme(), added.notionalCents(), Math::addExact);
        added.expiryCents().forEach((key, value) -> expiries.merge(key, value, Math::addExact));
        Long encumbrance = before.encumbranceCents() == null || incrementalCapital == null
                ? null : Math.addExact(before.encumbranceCents(), Math.max(0, incrementalCapital));
        return new AccountObjectiveService.CapacityUsage(symbols, themes, expiries, encumbrance,
                before.basis() + " Proposed usage adds canonical evaluation capital; encumbrance authority: "
                        + encumbranceAuthority + ".");
    }

    private static ThemeShares themeShares(Map<String, Long> before, Map<String, Long> after,
                                           String candidateTheme) {
        String leading = before.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        if (leading == null) leading = candidateTheme;
        long beforeTotal = before.values().stream().mapToLong(Long::longValue).sum();
        long afterTotal = after.values().stream().mapToLong(Long::longValue).sum();
        Double beforePct = beforeTotal == 0 ? null
                : roundPct(before.getOrDefault(leading, 0L), beforeTotal);
        Double afterPct = afterTotal == 0 ? null
                : roundPct(after.getOrDefault(leading, 0L), afterTotal);
        return new ThemeShares(leading, beforePct, afterPct);
    }

    private static Double roundPct(long part, long total) {
        return total <= 0 ? null : Math.round(part * 10_000.0 / total) / 100.0;
    }

    private static EconomicAssessment economics(StrategyEvaluation evaluation) {
        return evaluation == null || evaluation.assessment() == null
                ? null : evaluation.assessment().economics();
    }

    private static long magnitude(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static long divideHalf(long value) {
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(2), 0,
                RoundingMode.HALF_UP).longValueExact();
    }

    private static String value(Long cents) { return cents == null ? "unavailable" : cents + " cents"; }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record CandidateUsage(String symbol, String theme, long notionalCents,
                                  Map<String, Long> expiryCents) {}
    private record ThemeShares(String leadingBefore, Double beforePct, Double afterPct) {}
}
