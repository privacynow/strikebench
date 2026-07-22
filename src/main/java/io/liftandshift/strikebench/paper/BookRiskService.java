package io.liftandshift.strikebench.paper;

import io.liftandshift.strikebench.db.Db;
import io.liftandshift.strikebench.market.MarketLane;
import io.liftandshift.strikebench.market.Universes;
import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.position.CampaignMath;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The book-risk lane (§10.2): aggregate risk of the tracked book, computed FROM LOTS DIRECTLY
 * (portfolio_lot) — never from structure groupings, so wrong or missing grouping can neither
 * hide exposure nor double-count it. Per-account first, then cross-account subtotals; the
 * Practice lane is shown side-by-side and never numerically netted (§3.13).
 *
 * Every number carries a basis string and a coverage disclosure. Nothing is fabricated:
 * betas require observed underlying_bar closes, greeks require observed executable marks,
 * and every omission is counted out loud.
 */
public final class BookRiskService {

    /** Uniform downside shock for the stressed-assignment block, in percent. */
    public static final int SHOCK_PCT = 10;
    /** Minimum matched observed sessions before a beta is trusted. */
    public static final int MIN_BETA_SESSIONS = 30;
    /** Maximum matched return observations used per beta. */
    public static final int BETA_WINDOW = 252;
    /** Re-entry window for churn pairing: the wash-sale window, reused as a labeled heuristic. */
    public static final int CHURN_WINDOW_DAYS = 30;
    /** Expiry-cluster flags: one session holding >= 40% of option notional, or >= 3 option lots. */
    public static final double CLUSTER_NOTIONAL_SHARE = 0.40;
    public static final int CLUSTER_LOT_COUNT = 3;
    /** Covered-call funds for the strategy-collision heuristic (a static classification list). */
    public static final Set<String> COVERED_CALL_FUNDS =
            Set.of("JEPI", "JEPQ", "QYLD", "XYLD", "RYLD", "DIVO", "DIVA");

    private final Db db;
    private final Clock clock;
    private final MarksSource marks;
    private final PortfolioAccountingService books;
    private final AccountObjectiveService objectives;
    private final TradeService trades;

    public BookRiskService(Db db, Clock clock, MarksSource marks, PortfolioAccountingService books,
                           AccountObjectiveService objectives, TradeService trades) {
        this.db = db;
        this.clock = clock;
        this.marks = marks;
        this.books = books;
        this.objectives = objectives;
        this.trades = trades;
    }

    // ---- Views (every block names its basis; every aggregate discloses its coverage) ----

    public record BetaRow(String symbol, Double beta, int sessions, boolean weighted) {}

    public record GreekBlock(Long betaWeightedDollarDeltaCents, Long netDollarDeltaCents,
                             Long vegaPerPointCents, Long gammaPer1PctCents,
                             int optionLots, int markedOptionLots, int unmarkedOptionLots,
                             String greekCoverage, String betaCoverage, List<BetaRow> betas,
                             boolean complete, String basis) {}

    public record StressBlock(int shockPct, long obligationCents, long contracts,
                              long unmarkedObligationCents, int unmarkedLots,
                              long cashCents, String sentence, String basis) {}

    public record ExpiryRow(String date, long notionalCents, int lots,
                            long shortPutObligationCents, boolean flagged) {}

    public record ExpiryCalendar(List<ExpiryRow> rows, String clusterNote, String basis) {}

    public record ThemeRow(String label, long notionalCents, Double share, int positions,
                           List<String> symbols, Long netDollarDeltaCents,
                           boolean bothSides, int basisValuedPositions) {}

    public record SymbolNotional(String symbol, String theme, long notionalCents) {}

    public record ThemeBlock(List<ThemeRow> rows, String concentrationCallout,
                             String classificationLabel, List<SymbolNotional> symbolNotionals) {}

    public record Contradiction(String theme, List<String> longVia, List<String> shortVia,
                                Long netThemeDollarDeltaCents, String objectiveNote,
                                String message) {}

    public record ChurnPairView(String symbol, String exitAt, String reentryAt, long shares,
                                long exitPerShareCents, long reentryPerShareCents,
                                long costCents, String message) {}

    public record ChurnBlock(List<ChurnPairView> pairs, long totalCostCents, String basis) {}

    public record ObjectiveRef(String objective, String direction, int revisionNo) {}

    public record AccountRisk(String accountId, String name, String accountType, long cashCents,
                              ObjectiveRef objective, GreekBlock greeks, StressBlock stress,
                              ExpiryCalendar expiries, ThemeBlock themes,
                              List<Contradiction> contradictions, List<String> collisions,
                              ChurnBlock churn) {}

    public record CrossAccount(int accounts, GreekBlock greeks, long stressedObligationCents,
                               long unmarkedObligationCents, String stressNote,
                               ExpiryCalendar expiries, ThemeBlock themes, String basis) {}

    public record PracticeLane(Double deltaShares, Long dollarDeltaNetCents, Long dollarDeltaGrossCents,
                               Double gammaShares, Double thetaPerDay, Double vegaPerPoint,
                               boolean complete, String basis) {}

    public record Lane(List<AccountRisk> accounts, CrossAccount crossAccount,
                       PracticeLane practice, String basis) {}

    // ---- Entry point ----

    public Lane lane(String ownerId, String practiceAccountId) {
        List<PortfolioAccountingService.AccountProfile> profiles = books.accounts(ownerId);
        List<AccountRisk> accounts = new ArrayList<>();
        for (var profile : profiles) {
            if ("ARCHIVED".equals(profile.status())) continue;
            accounts.add(accountRisk(ownerId, profile));
        }
        CrossAccount cross = accounts.size() > 1 ? crossAccount(accounts) : null;
        PracticeLane practice = practiceAccountId == null ? null : practiceLane(practiceAccountId);
        return new Lane(List.copyOf(accounts), cross, practice,
                "Book risk is computed from open tracked lots directly (portfolio_lot), never from "
                        + "structure groupings, so grouping mistakes can neither hide exposure nor "
                        + "double-count it. Accounts are shown one by one before any subtotal; the "
                        + "Practice lane is side-by-side and never numerically netted with tracked "
                        + "accounts. Missing marks and missing history are disclosed, never fabricated.");
    }

    // ---- Per-account lane ----

    private AccountRisk accountRisk(String ownerId, PortfolioAccountingService.AccountProfile profile) {
        List<PortfolioAccountingService.LotView> lots = books.lots(ownerId, profile.id(), false);
        long cash = bookCash(profile.id());
        return accountRisk(ownerId, profile, lots, cash);
    }

    /**
     * Runs the one canonical Book-risk engine over a hypothetical lot/cash snapshot. Callers own
     * only the transformation of copied lots; this method performs no write and retains every
     * observed-evidence and missing-data rule of the live account lane.
     */
    public AccountRisk projectAccount(String ownerId, String accountId,
                                      List<PortfolioAccountingService.LotView> hypotheticalLots,
                                      long hypotheticalCashCents) {
        var profile = books.account(ownerId, accountId);
        return accountRisk(ownerId, profile,
                hypotheticalLots == null ? List.of() : List.copyOf(hypotheticalLots),
                hypotheticalCashCents);
    }

    private AccountRisk accountRisk(String ownerId, PortfolioAccountingService.AccountProfile profile,
                                    List<PortfolioAccountingService.LotView> lots, long cash) {
        Map<String, Long> spots = spotCents(lots);
        Map<String, MarksSource.LegMark> optionMarks = optionMarks(lots);
        BetaSet betas = betas(optionUnderlyings(lots));
        AccountObjectiveService.Revision revision = objectives.latest(ownerId, profile.id());
        ObjectiveRef objective = revision == null ? null
                : new ObjectiveRef(revision.objective(), revision.direction(), revision.revisionNo());

        GreekBlock greeks = greekBlock(lots, spots, optionMarks, betas);
        StressBlock stress = stressBlock(lots, spots, cash);
        ExpiryCalendar expiries = expiryCalendar(lots);
        ThemeBlock themes = themeBlock(lots, spots, optionMarks);
        List<Contradiction> contradictions = contradictions(lots, spots, optionMarks, revision);
        List<String> collisions = collisions(lots);
        ChurnBlock churn = churn(profile.id());
        return new AccountRisk(profile.id(), profile.name(), profile.accountType(), cash, objective,
                greeks, stress, expiries, themes, contradictions, collisions, churn);
    }

    private long bookCash(String accountId) {
        return db.query("SELECT COALESCE(SUM(cash_effect_cents),0) n FROM portfolio_transaction "
                + "WHERE portfolio_account_id=?", r -> r.lng("n"), accountId).getFirst();
    }

    // ---- Marks (observed executable evidence only — the tracked book's standing rule) ----

    private Optional<MarksSource.LegMark> eligibleMark(String symbol, Leg leg) {
        if (marks == null) return Optional.empty();
        return marks.legMark(symbol, leg).filter(mark -> {
            DataEvidence evidence = mark.evidence() == null
                    ? DataEvidence.of(null, mark.freshness()) : mark.evidence();
            return evidence.executableIn(MarketLane.OBSERVED);
        });
    }

    /** Observed spot per symbol (cents), via the same stock-mark eligibility as valuations. */
    private Map<String, Long> spotCents(List<PortfolioAccountingService.LotView> lots) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (var lot : lots) {
            if (out.containsKey(lot.symbol())) continue;
            eligibleMark(lot.symbol(), Leg.stock(LegAction.BUY, 1, BigDecimal.ZERO))
                    .map(MarksSource.LegMark::mid)
                    .filter(mid -> mid != null && mid.signum() > 0)
                    .ifPresent(mid -> out.put(lot.symbol(), Money.toCents(mid)));
        }
        return out;
    }

    private Map<String, MarksSource.LegMark> optionMarks(List<PortfolioAccountingService.LotView> lots) {
        Map<String, MarksSource.LegMark> out = new LinkedHashMap<>();
        for (var lot : lots) {
            if (!"OPTION".equals(lot.instrumentType())) continue;
            String key = optionKey(lot);
            if (out.containsKey(key)) continue;
            eligibleMark(lot.symbol(), optionLeg(lot)).ifPresent(mark -> out.put(key, mark));
        }
        return out;
    }

    private static Leg optionLeg(PortfolioAccountingService.LotView lot) {
        return Leg.option(LegAction.BUY, OptionType.valueOf(lot.optionType()), lot.strike(),
                lot.expiration(), 1, BigDecimal.ZERO, lot.multiplier());
    }

    private static String optionKey(PortfolioAccountingService.LotView lot) {
        return String.join("|", lot.symbol(), lot.optionType(),
                String.valueOf(lot.strike()), String.valueOf(lot.expiration()),
                String.valueOf(lot.multiplier()));
    }

    private static double signedUnits(PortfolioAccountingService.LotView lot) {
        double units = (double) lot.remainingQuantity() * lot.multiplier();
        return "SHORT".equals(lot.side()) ? -units : units;
    }

    // ---- Net dollar greeks, beta-weighted (options book) ----

    private GreekBlock greekBlock(List<PortfolioAccountingService.LotView> lots,
                                  Map<String, Long> spots,
                                  Map<String, MarksSource.LegMark> optionMarks,
                                  BetaSet betas) {
        int optionLots = 0, marked = 0;
        double dollarDelta = 0, betaDollarDelta = 0, vegaPerPoint = 0, gammaPer1Pct = 0;
        Set<String> weightedSymbols = new LinkedHashSet<>();
        Set<String> unweightedSymbols = new LinkedHashSet<>();
        for (var lot : lots) {
            if (!"OPTION".equals(lot.instrumentType())) continue;
            optionLots++;
            MarksSource.LegMark mark = optionMarks.get(optionKey(lot));
            Long spot = spots.get(lot.symbol());
            if (mark == null || mark.delta() == null || spot == null) continue;
            marked++;
            double units = signedUnits(lot);
            double spotDollars = spot / 100.0;
            double lotDollarDelta = mark.delta() * units * spot; // cents
            Double beta = betas.beta(lot.symbol());
            if (beta != null) weightedSymbols.add(lot.symbol());
            else unweightedSymbols.add(lot.symbol());
            dollarDelta += lotDollarDelta;
            betaDollarDelta += lotDollarDelta * (beta == null ? 1.0 : beta);
            vegaPerPoint += (mark.vega() == null ? 0 : mark.vega()) * units * 100.0; // $/pt -> cents/pt
            gammaPer1Pct += (mark.gamma() == null ? 0 : mark.gamma()) * units * 0.01 * spotDollars * spot;
        }
        int unmarked = optionLots - marked;
        boolean complete = optionLots > 0 && unmarked == 0;
        String greekCoverage = optionLots == 0
                ? "No open option lots in this account."
                : "Delta aggregated over " + marked + " of " + optionLots + " option lots; "
                        + lackMarks(unmarked);
        int betaSessions = betas.minSessions(weightedSymbols);
        String betaCoverage = marked == 0
                ? "Beta weighting not applied — no marked option lots."
                : weightedSymbols.isEmpty()
                        ? "Betas unavailable — no observed return history vs SPY; all "
                                + unweightedSymbols.size() + " symbols are shown unweighted."
                        : "Betas from " + betaSessions + " sessions of observed closes; "
                                + lackHistory(unweightedSymbols.size());
        List<BetaRow> betaRows = new ArrayList<>();
        for (String symbol : weightedSymbols) {
            betaRows.add(new BetaRow(symbol, betas.beta(symbol), betas.sessions(symbol), true));
        }
        for (String symbol : unweightedSymbols) {
            betaRows.add(new BetaRow(symbol, null, betas.sessions(symbol), false));
        }
        return new GreekBlock(
                marked == 0 ? null : roundCents(betaDollarDelta),
                marked == 0 ? null : roundCents(dollarDelta),
                marked == 0 ? null : roundCents(vegaPerPoint),
                marked == 0 ? null : roundCents(gammaPer1Pct),
                optionLots, marked, unmarked, greekCoverage, betaCoverage,
                List.copyOf(betaRows), complete,
                "Options book only, from open lots. Dollar delta = option delta × signed units × "
                        + "observed spot; beta weighting multiplies each name's dollar delta by its "
                        + "observed beta vs SPY (unweighted names enter at their raw dollar delta, "
                        + "disclosed). Raw share delta is not additive across names and is not "
                        + "aggregated here. Vega is $ per volatility point; gamma is the $ delta "
                        + "change for a 1% underlying move. Model statistics at current observed "
                        + "marks — descriptions, not forecasts.");
    }

    private static Long roundCents(double cents) {
        if (!Double.isFinite(cents) || cents > Long.MAX_VALUE || cents < Long.MIN_VALUE) return null;
        return Math.round(cents);
    }

    private static String lackMarks(int unmarked) {
        return unmarked == 1 ? "1 lacks observed marks." : unmarked + " lack observed marks.";
    }

    private static String lackHistory(int count) {
        return count == 1 ? "1 symbol lacks history and is shown unweighted."
                : count + " symbols lack history and are shown unweighted.";
    }

    // ---- Stressed assignment capital ----

    private StressBlock stressBlock(List<PortfolioAccountingService.LotView> lots,
                                    Map<String, Long> spots, long cash) {
        long obligation = 0, contracts = 0, unmarkedObligation = 0;
        int unmarkedLots = 0;
        for (var lot : lots) {
            if (!"OPTION".equals(lot.instrumentType()) || !"PUT".equals(lot.optionType())
                    || !"SHORT".equals(lot.side())) continue;
            long strikeCash = Money.centsFromPrice(lot.strike(),
                    Math.multiplyExact(lot.remainingQuantity(), (long) lot.multiplier()));
            Long spot = spots.get(lot.symbol());
            if (spot == null) {
                unmarkedObligation = Math.addExact(unmarkedObligation, strikeCash);
                unmarkedLots++;
                continue;
            }
            double stressedSpot = spot * (1.0 - SHOCK_PCT / 100.0);
            if (Money.centsFromPrice(lot.strike(), 1L) > stressedSpot) {
                obligation = Math.addExact(obligation, strikeCash);
                contracts = Math.addExact(contracts, lot.remainingQuantity());
            }
        }
        StringBuilder sentence = new StringBuilder("If these names fall " + SHOCK_PCT
                + "%, your short puts obligate " + Money.fmt(obligation) + " vs your "
                + Money.fmt(cash) + " recorded cash in this account.");
        if (unmarkedLots > 0) {
            sentence.append(" Another ").append(Money.fmt(unmarkedObligation)).append(" of strike")
                    .append(" obligation across ").append(unmarkedLots).append(" short-put lot")
                    .append(unmarkedLots == 1 ? "" : "s")
                    .append(" cannot be stressed — no observed underlying mark — and is shown separately.");
        }
        return new StressBlock(SHOCK_PCT, obligation, contracts, unmarkedObligation, unmarkedLots,
                cash, sentence.toString(),
                "Uniform -" + SHOCK_PCT + "% shock to every underlying — a classification-level "
                        + "stress, a labeled heuristic, not a correlation model. A short put "
                        + "obligates strike × contracts × multiplier when the shocked price is "
                        + "below the strike. Cash is this account's recorded book cash; broker "
                        + "margin is not modeled.");
    }

    // ---- Expiry-concentration calendar ----

    private ExpiryCalendar expiryCalendar(List<PortfolioAccountingService.LotView> lots) {
        record Bucket(long[] notional, int[] lotCount, long[] shortPut) {}
        Map<LocalDate, Bucket> buckets = new TreeMap<>();
        long total = 0;
        for (var lot : lots) {
            if (!"OPTION".equals(lot.instrumentType()) || lot.expiration() == null) continue;
            long notional = Money.centsFromPrice(lot.strike(),
                    Math.multiplyExact(lot.remainingQuantity(), (long) lot.multiplier()));
            Bucket bucket = buckets.computeIfAbsent(lot.expiration(),
                    ignored -> new Bucket(new long[]{0}, new int[]{0}, new long[]{0}));
            bucket.notional()[0] = Math.addExact(bucket.notional()[0], notional);
            bucket.lotCount()[0]++;
            if ("PUT".equals(lot.optionType()) && "SHORT".equals(lot.side())) {
                bucket.shortPut()[0] = Math.addExact(bucket.shortPut()[0], notional);
            }
            total = Math.addExact(total, notional);
        }
        List<ExpiryRow> rows = new ArrayList<>();
        ExpiryRow biggestFlagged = null;
        for (var entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            double share = total == 0 ? 0 : bucket.notional()[0] / (double) total;
            boolean flagged = buckets.size() > 1
                    ? (share >= CLUSTER_NOTIONAL_SHARE || bucket.lotCount()[0] >= CLUSTER_LOT_COUNT)
                    : bucket.lotCount()[0] >= CLUSTER_LOT_COUNT;
            ExpiryRow row = new ExpiryRow(entry.getKey().toString(), bucket.notional()[0],
                    bucket.lotCount()[0], bucket.shortPut()[0], flagged);
            rows.add(row);
            if (flagged && (biggestFlagged == null || row.notionalCents() > biggestFlagged.notionalCents())) {
                biggestFlagged = row;
            }
        }
        String clusterNote = biggestFlagged == null ? null
                : Money.fmt(biggestFlagged.notionalCents()) + " notional expires "
                        + biggestFlagged.date() + " — "
                        + Math.round(total == 0 ? 0 : biggestFlagged.notionalCents() * 100.0 / total)
                        + "% of this book's option notional on one session.";
        return new ExpiryCalendar(List.copyOf(rows), clusterNote,
                "Strike-value notional (strike × contracts × multiplier) per expiration date. "
                        + "Clusters are flagged when one session holds ≥"
                        + Math.round(CLUSTER_NOTIONAL_SHARE * 100) + "% of expiring notional or ≥"
                        + CLUSTER_LOT_COUNT + " option lots — a labeled heuristic threshold, "
                        + "not a risk model.");
    }

    // ---- Theme / sector concentration (a CLASSIFICATION, never a correlation claim) ----

    private static final String CLASSIFICATION_LABEL =
            "Theme labels come from StrikeBench's curated 13-sector map — a classification, not "
                    + "measured correlation. A true correlation claim would require observed return "
                    + "series and would have to state its coverage; none is asserted here.";

    private ThemeBlock themeBlock(List<PortfolioAccountingService.LotView> lots,
                                  Map<String, Long> spots,
                                  Map<String, MarksSource.LegMark> optionMarks) {
        record Theme(long[] notional, int[] positions, Set<String> symbols, double[] delta,
                     boolean[] deltaComplete, boolean[] bullish, boolean[] bearish, int[] basisValued) {}
        Map<String, Theme> themes = new LinkedHashMap<>();
        Map<String, long[]> symbols = new LinkedHashMap<>();
        long total = 0;
        for (var lot : lots) {
            String label = Universes.allocationSectorLabel(lot.symbol());
            Theme theme = themes.computeIfAbsent(label, ignored -> new Theme(new long[]{0},
                    new int[]{0}, new LinkedHashSet<>(), new double[]{0}, new boolean[]{true},
                    new boolean[]{false}, new boolean[]{false}, new int[]{0}));
            long notional;
            if ("STOCK".equals(lot.instrumentType())) {
                Long spot = spots.get(lot.symbol());
                if (spot != null) {
                    notional = Math.multiplyExact(
                            Math.multiplyExact(lot.remainingQuantity(), (long) lot.multiplier()), spot);
                    theme.delta()[0] += signedUnits(lot) * spot; // signed shares × spot
                } else {
                    notional = lot.economicRemainingOpenAmountCents();
                    theme.basisValued()[0]++;
                    theme.deltaComplete()[0] = false;
                }
                boolean longStock = "LONG".equals(lot.side());
                theme.bullish()[0] |= longStock;
                theme.bearish()[0] |= !longStock;
            } else {
                notional = Money.centsFromPrice(lot.strike(),
                        Math.multiplyExact(lot.remainingQuantity(), (long) lot.multiplier()));
                MarksSource.LegMark mark = optionMarks.get(optionKey(lot));
                Long spot = spots.get(lot.symbol());
                if (mark == null || mark.delta() == null || spot == null) {
                    theme.deltaComplete()[0] = false;
                } else {
                    theme.delta()[0] += mark.delta() * signedUnits(lot) * spot;
                }
                boolean call = "CALL".equals(lot.optionType());
                boolean shortSide = "SHORT".equals(lot.side());
                boolean bullish = call != shortSide; // long call or short put
                theme.bullish()[0] |= bullish;
                theme.bearish()[0] |= !bullish;
            }
            theme.notional()[0] = Math.addExact(theme.notional()[0], notional);
            long[] symbolNotional = symbols.computeIfAbsent(lot.symbol(), ignored -> new long[]{0});
            symbolNotional[0] = Math.addExact(symbolNotional[0], notional);
            theme.positions()[0]++;
            theme.symbols().add(lot.symbol());
            total = Math.addExact(total, notional);
        }
        List<ThemeRow> rows = new ArrayList<>();
        for (var entry : themes.entrySet()) {
            Theme theme = entry.getValue();
            rows.add(new ThemeRow(entry.getKey(), theme.notional()[0],
                    total == 0 ? null : theme.notional()[0] / (double) total,
                    theme.positions()[0], List.copyOf(theme.symbols()),
                    theme.deltaComplete()[0] ? roundCents(theme.delta()[0]) : null,
                    theme.bullish()[0] && theme.bearish()[0], theme.basisValued()[0]));
        }
        rows.sort(Comparator.comparingLong(ThemeRow::notionalCents).reversed());
        String callout = null;
        if (!rows.isEmpty() && total > 0) {
            ThemeRow top = rows.getFirst();
            if (top.share() != null && top.share() >= 0.5 && top.positions() >= 2) {
                callout = "Effectively one " + top.label().toLowerCase(Locale.ROOT) + " bet: "
                        + Math.round(top.share() * 100) + "% of this account's recorded exposure ("
                        + Money.fmt(top.notionalCents()) + ") shares one theme classification across "
                        + top.symbols().size() + " symbol" + (top.symbols().size() == 1 ? "" : "s")
                        + " (" + String.join(", ", top.symbols()) + ").";
            }
        }
        List<SymbolNotional> symbolRows = symbols.entrySet().stream()
                .map(entry -> new SymbolNotional(entry.getKey(),
                        Universes.allocationSectorLabel(entry.getKey()), entry.getValue()[0]))
                .sorted(Comparator.comparingLong(SymbolNotional::notionalCents).reversed()
                        .thenComparing(SymbolNotional::symbol)).toList();
        return new ThemeBlock(List.copyOf(rows), callout, CLASSIFICATION_LABEL, symbolRows);
    }

    // ---- Intra-theme contradiction + strategy-collision callouts ----

    private List<Contradiction> contradictions(List<PortfolioAccountingService.LotView> lots,
                                               Map<String, Long> spots,
                                               Map<String, MarksSource.LegMark> optionMarks,
                                               AccountObjectiveService.Revision revision) {
        record Sides(Map<String, Long> shortPuts, Map<String, Long> shortCalls,
                     Map<String, Long> longShares, double[] delta, double[] grossDelta,
                     boolean[] deltaComplete) {}
        Map<String, Sides> byTheme = new LinkedHashMap<>();
        for (var lot : lots) {
            String label = Universes.allocationSectorLabel(lot.symbol());
            Sides sides = byTheme.computeIfAbsent(label, ignored -> new Sides(new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(), new double[]{0}, new double[]{0},
                    new boolean[]{true}));
            Long spot = spots.get(lot.symbol());
            if ("STOCK".equals(lot.instrumentType())) {
                if ("LONG".equals(lot.side())) {
                    sides.longShares().merge(lot.symbol(), lot.remainingQuantity(), Math::addExact);
                }
                if (spot == null) sides.deltaComplete()[0] = false;
                else {
                    double d = signedUnits(lot) * spot; // signed shares × spot, in cents
                    sides.delta()[0] += d;
                    sides.grossDelta()[0] += Math.abs(d);
                }
                continue;
            }
            if ("SHORT".equals(lot.side()) && "PUT".equals(lot.optionType())) {
                sides.shortPuts().merge(lot.symbol(), lot.remainingQuantity(), Math::addExact);
            }
            if ("SHORT".equals(lot.side()) && "CALL".equals(lot.optionType())) {
                sides.shortCalls().merge(lot.symbol(), lot.remainingQuantity(), Math::addExact);
            }
            MarksSource.LegMark mark = optionMarks.get(optionKey(lot));
            if (mark == null || mark.delta() == null || spot == null) {
                sides.deltaComplete()[0] = false;
            } else {
                double d = mark.delta() * signedUnits(lot) * spot;
                sides.delta()[0] += d;
                sides.grossDelta()[0] += Math.abs(d);
            }
        }
        List<Contradiction> out = new ArrayList<>();
        for (var entry : byTheme.entrySet()) {
            Sides sides = entry.getValue();
            if (sides.shortPuts().isEmpty() || sides.shortCalls().isEmpty()) continue;
            List<String> longVia = sides.shortPuts().entrySet().stream()
                    .map(e -> "short puts: " + e.getKey() + " ×" + e.getValue()).toList();
            boolean covered = sides.shortCalls().keySet().stream()
                    .allMatch(symbol -> sides.longShares().getOrDefault(symbol, 0L) > 0);
            String callWord = covered ? "covered calls" : "short calls";
            List<String> shortVia = sides.shortCalls().entrySet().stream()
                    .map(e -> callWord + ": " + e.getKey() + " ×" + e.getValue()).toList();
            Long netDelta = sides.deltaComplete()[0] ? roundCents(sides.delta()[0]) : null;
            String deltaPhrase;
            if (netDelta == null) {
                deltaPhrase = "net thematic dollar delta unavailable (missing marks)";
            } else if (sides.grossDelta()[0] > 0
                    && Math.abs(sides.delta()[0]) <= 0.2 * sides.grossDelta()[0]) {
                deltaPhrase = "net thematic dollar delta ≈ neutral (" + Money.fmt(netDelta) + ")";
            } else {
                deltaPhrase = "net thematic dollar delta " + Money.fmt(netDelta);
            }
            String objectiveNote;
            if (revision != null && revision.direction() != null
                    && !"NON_DIRECTIONAL".equals(revision.direction())) {
                objectiveNote = "despite a " + revision.direction().toLowerCase(Locale.ROOT)
                        + " stated goal (this account's declared objective, revision "
                        + revision.revisionNo() + ")";
            } else {
                objectiveNote = "this account declares no objective direction to compare against";
            }
            String symbols = String.join("/", union(sides.shortPuts().keySet(), sides.shortCalls().keySet()));
            String message = "You are both long (short puts) and short (" + callWord + ") "
                    + symbols + " — " + entry.getKey() + ": " + deltaPhrase + " — " + objectiveNote + ".";
            out.add(new Contradiction(entry.getKey(), longVia, shortVia, netDelta, objectiveNote, message));
        }
        return List.copyOf(out);
    }

    private static List<String> union(Set<String> a, Set<String> b) {
        LinkedHashSet<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    private List<String> collisions(List<PortfolioAccountingService.LotView> lots) {
        boolean writesCalls = lots.stream().anyMatch(lot -> "OPTION".equals(lot.instrumentType())
                && "CALL".equals(lot.optionType()) && "SHORT".equals(lot.side()));
        if (!writesCalls) return List.of();
        List<String> out = new ArrayList<>();
        for (var lot : lots) {
            if (!"STOCK".equals(lot.instrumentType()) || !"LONG".equals(lot.side())
                    || !COVERED_CALL_FUNDS.contains(lot.symbol())) continue;
            out.add(lot.symbol() + " is a covered-call fund — you pay its manager to write calls "
                    + "while also writing calls yourself in this account. That is the same strategy "
                    + "twice, labeled from a static fund list (a heuristic, not fund research).");
        }
        return List.copyOf(out);
    }

    // ---- Churn / whipsaw accounting (§4) ----

    private ChurnBlock churn(String accountId) {
        record StockLeg(OffsetDateTime at, String symbol, String action, String effect,
                        long quantity, BigDecimal price, long feeCents) {}
        List<StockLeg> legs = db.query(
                "SELECT t.occurred_at, l.symbol, l.action, l.position_effect, l.quantity, l.price, "
                        + "l.allocated_fee_cents FROM portfolio_transaction_leg l "
                        + "JOIN portfolio_transaction t ON t.id=l.transaction_id "
                        + "WHERE t.portfolio_account_id=? AND l.instrument_type='STOCK' "
                        + "ORDER BY t.occurred_at, t.record_seq, l.leg_no",
                r -> new StockLeg(r.odt("occurred_at"), r.str("symbol"), r.str("action"),
                        r.str("position_effect"), r.lng("quantity"), r.bd("price"),
                        r.lng("allocated_fee_cents")),
                accountId);
        // FIFO pairing of same-symbol exits with re-entries inside the window (CampaignService's
        // pairing discipline, scoped account-wide per symbol; the cost math stays in CampaignMath).
        final class Exit {
            final StockLeg leg;
            long used = 0;
            Exit(StockLeg leg) { this.leg = leg; }
        }
        Map<String, List<Exit>> exits = new LinkedHashMap<>();
        List<ChurnPairView> pairs = new ArrayList<>();
        long total = 0;
        for (StockLeg leg : legs) {
            if ("SELL".equals(leg.action()) && "CLOSE".equals(leg.effect())) {
                exits.computeIfAbsent(leg.symbol(), ignored -> new ArrayList<>()).add(new Exit(leg));
                continue;
            }
            if (!"BUY".equals(leg.action()) || !"OPEN".equals(leg.effect())) continue;
            long need = leg.quantity();
            for (Exit exit : exits.getOrDefault(leg.symbol(), List.of())) {
                if (need <= 0) break;
                long available = exit.leg.quantity() - exit.used;
                if (available <= 0) continue;
                if (leg.at().isAfter(exit.leg.at().plusDays(CHURN_WINDOW_DAYS))) continue;
                long take = Math.min(need, available);
                exit.used += take;
                need -= take;
                long exitFee = proportional(exit.leg.feeCents(), take, exit.leg.quantity());
                long reFee = proportional(leg.feeCents(), take, leg.quantity());
                long exitCents = Money.toCents(exit.leg.price());
                long reCents = Money.toCents(leg.price());
                long cost = CampaignMath.churnRoundTripCostCents(exitCents, reCents, take,
                        Math.addExact(exitFee, reFee));
                total = Math.addExact(total, cost);
                pairs.add(new ChurnPairView(leg.symbol(), iso(exit.leg.at()), iso(leg.at()), take,
                        exitCents, reCents, cost,
                        "You sold " + leg.symbol() + " at " + Money.fmt(exitCents)
                                + " and rebought at " + Money.fmt(reCents)
                                + " — the round trip cost " + Money.fmt(cost)
                                + " on " + take + " share" + (take == 1 ? "" : "s") + "."));
            }
        }
        return new ChurnBlock(List.copyOf(pairs), total,
                "Same-symbol stock exits paired chronologically with re-entries within "
                        + CHURN_WINDOW_DAYS + " days (the wash-sale window, reused as a labeled "
                        + "pairing heuristic). Cost = (re-entry − exit) × paired shares + both legs' "
                        + "allocated fees, per the §4 round-trip formula. The wash-sale tax "
                        + "consequence lives on the tracked tax basis, reported separately.");
    }

    private static long proportional(long totalCents, long part, long whole) {
        if (whole == 0) return 0;
        return Math.round(totalCents * (double) part / whole);
    }

    private static String iso(OffsetDateTime at) {
        return at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().toString();
    }

    // ---- Cross-account subtotals (exposure only; cash is never fungible) ----

    private CrossAccount crossAccount(List<AccountRisk> accounts) {
        double betaDelta = 0, delta = 0, vega = 0, gamma = 0;
        boolean anyMarked = false;
        int optionLots = 0, marked = 0;
        long obligation = 0, unmarkedObligation = 0;
        Map<String, long[]> expiryNotional = new TreeMap<>();
        Map<String, int[]> expiryLots = new TreeMap<>();
        Map<String, long[]> expiryShortPut = new TreeMap<>();
        Map<String, long[]> themeNotional = new LinkedHashMap<>();
        Map<String, int[]> themePositions = new LinkedHashMap<>();
        Map<String, Set<String>> themeSymbols = new LinkedHashMap<>();
        Map<String, long[]> symbolNotional = new LinkedHashMap<>();
        int minSessions = Integer.MAX_VALUE;
        Set<String> unweighted = new LinkedHashSet<>();
        for (AccountRisk account : accounts) {
            GreekBlock greeks = account.greeks();
            optionLots += greeks.optionLots();
            marked += greeks.markedOptionLots();
            if (greeks.betaWeightedDollarDeltaCents() != null) {
                anyMarked = true;
                betaDelta += greeks.betaWeightedDollarDeltaCents();
                delta += greeks.netDollarDeltaCents();
                vega += greeks.vegaPerPointCents();
                gamma += greeks.gammaPer1PctCents();
            }
            for (BetaRow row : greeks.betas()) {
                if (row.weighted()) minSessions = Math.min(minSessions, row.sessions());
                else unweighted.add(row.symbol());
            }
            obligation = Math.addExact(obligation, account.stress().obligationCents());
            unmarkedObligation = Math.addExact(unmarkedObligation, account.stress().unmarkedObligationCents());
            for (ExpiryRow row : account.expiries().rows()) {
                expiryNotional.computeIfAbsent(row.date(), ignored -> new long[]{0})[0] += row.notionalCents();
                expiryLots.computeIfAbsent(row.date(), ignored -> new int[]{0})[0] += row.lots();
                expiryShortPut.computeIfAbsent(row.date(), ignored -> new long[]{0})[0] += row.shortPutObligationCents();
            }
            for (ThemeRow row : account.themes().rows()) {
                themeNotional.computeIfAbsent(row.label(), ignored -> new long[]{0})[0] += row.notionalCents();
                themePositions.computeIfAbsent(row.label(), ignored -> new int[]{0})[0] += row.positions();
                themeSymbols.computeIfAbsent(row.label(), ignored -> new LinkedHashSet<>()).addAll(row.symbols());
            }
            for (SymbolNotional row : account.themes().symbolNotionals()) {
                long[] total = symbolNotional.computeIfAbsent(row.symbol(), ignored -> new long[]{0});
                total[0] = Math.addExact(total[0], row.notionalCents());
            }
        }
        int unmarked = optionLots - marked;
        String greekCoverage = optionLots == 0 ? "No open option lots across tracked accounts."
                : "Delta aggregated over " + marked + " of " + optionLots + " option lots; "
                        + lackMarks(unmarked);
        String betaCoverage = !anyMarked ? "Beta weighting not applied — no marked option lots."
                : minSessions == Integer.MAX_VALUE
                        ? "Betas unavailable — no observed return history vs SPY; all symbols are shown unweighted."
                        : "Betas from " + minSessions + " sessions of observed closes; "
                                + lackHistory(unweighted.size());
        GreekBlock greeks = new GreekBlock(anyMarked ? roundCents(betaDelta) : null,
                anyMarked ? roundCents(delta) : null, anyMarked ? roundCents(vega) : null,
                anyMarked ? roundCents(gamma) : null, optionLots, marked, unmarked,
                greekCoverage, betaCoverage, List.of(), optionLots > 0 && unmarked == 0,
                "Sum of the per-account dollar aggregates above. Beta-weighted dollar delta is "
                        + "additive across accounts; per-account coverage disclosures still apply.");
        long totalNotional = expiryNotional.values().stream().mapToLong(v -> v[0]).sum();
        List<ExpiryRow> expiryRows = new ArrayList<>();
        ExpiryRow biggestFlagged = null;
        for (var entry : expiryNotional.entrySet()) {
            double share = totalNotional == 0 ? 0 : entry.getValue()[0] / (double) totalNotional;
            int lotCount = expiryLots.get(entry.getKey())[0];
            boolean flagged = expiryNotional.size() > 1
                    ? (share >= CLUSTER_NOTIONAL_SHARE || lotCount >= CLUSTER_LOT_COUNT)
                    : lotCount >= CLUSTER_LOT_COUNT;
            ExpiryRow row = new ExpiryRow(entry.getKey(), entry.getValue()[0], lotCount,
                    expiryShortPut.get(entry.getKey())[0], flagged);
            expiryRows.add(row);
            if (flagged && (biggestFlagged == null || row.notionalCents() > biggestFlagged.notionalCents())) {
                biggestFlagged = row;
            }
        }
        String clusterNote = biggestFlagged == null ? null
                : Money.fmt(biggestFlagged.notionalCents()) + " notional expires "
                        + biggestFlagged.date() + " across your tracked accounts — "
                        + Math.round(totalNotional == 0 ? 0 : biggestFlagged.notionalCents() * 100.0 / totalNotional)
                        + "% of all option notional on one session.";
        ExpiryCalendar expiries = new ExpiryCalendar(List.copyOf(expiryRows), clusterNote,
                "Per-account calendars summed by date; same strike-value notional basis and "
                        + "cluster heuristic as each account block.");
        List<ThemeRow> themeRows = new ArrayList<>();
        long themeTotal = themeNotional.values().stream().mapToLong(v -> v[0]).sum();
        for (var entry : themeNotional.entrySet()) {
            themeRows.add(new ThemeRow(entry.getKey(), entry.getValue()[0],
                    themeTotal == 0 ? null : entry.getValue()[0] / (double) themeTotal,
                    themePositions.get(entry.getKey())[0],
                    List.copyOf(themeSymbols.get(entry.getKey())), null, false, 0));
        }
        themeRows.sort(Comparator.comparingLong(ThemeRow::notionalCents).reversed());
        String callout = null;
        if (!themeRows.isEmpty() && themeTotal > 0) {
            ThemeRow top = themeRows.getFirst();
            if (top.share() != null && top.share() >= 0.5 && top.positions() >= 2) {
                callout = "Across every tracked account this is effectively one "
                        + top.label().toLowerCase(Locale.ROOT) + " bet: "
                        + Math.round(top.share() * 100) + "% of recorded exposure ("
                        + Money.fmt(top.notionalCents()) + ") shares one theme classification.";
            }
        }
        List<SymbolNotional> symbolRows = symbolNotional.entrySet().stream()
                .map(entry -> new SymbolNotional(entry.getKey(),
                        Universes.allocationSectorLabel(entry.getKey()), entry.getValue()[0]))
                .sorted(Comparator.comparingLong(SymbolNotional::notionalCents).reversed()
                        .thenComparing(SymbolNotional::symbol)).toList();
        ThemeBlock themes = new ThemeBlock(List.copyOf(themeRows), callout, CLASSIFICATION_LABEL,
                symbolRows);
        return new CrossAccount(accounts.size(), greeks, obligation, unmarkedObligation,
                "Combined stressed short-put obligation: " + Money.fmt(obligation)
                        + (unmarkedObligation > 0
                                ? " (plus " + Money.fmt(unmarkedObligation) + " unstressable — no observed marks)"
                                : "")
                        + ". The per-account obligations above are the actionable numbers — cash is "
                        + "account-local and never fungible across accounts, so no combined cash "
                        + "comparison is shown.",
                expiries, themes,
                "Cross-account subtotals aggregate exposure only, after each account is shown on "
                        + "its own (§3.13). Practice never enters these numbers.");
    }

    // ---- Practice lane (side-by-side, never netted) ----

    private PracticeLane practiceLane(String practiceAccountId) {
        Map<String, Object> greeks = trades.portfolioGreeks(practiceAccountId);
        var dollarDelta = trades.portfolioDollarDelta(practiceAccountId, null);
        return new PracticeLane((Double) greeks.get("deltaShares"),
                dollarDelta.netCents(), dollarDelta.grossCents(),
                (Double) greeks.get("gammaShares"), (Double) greeks.get("thetaPerDay"),
                (Double) greeks.get("vegaPerPoint"),
                Boolean.TRUE.equals(greeks.get("complete")) && dollarDelta.complete(),
                "Practice account, in its own market lane — shown side-by-side and never "
                        + "numerically netted with tracked accounts (§3.13). Share-equivalent "
                        + "delta/gamma, $/day theta, and $/vol-point vega from current Practice "
                        + "marks; dollar delta uses the disclosed option model.");
    }

    // ---- Betas from observed underlying_bar return series vs SPY ----

    private List<String> optionUnderlyings(List<PortfolioAccountingService.LotView> lots) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (var lot : lots) {
            if ("OPTION".equals(lot.instrumentType())) out.add(lot.symbol());
        }
        return List.copyOf(out);
    }

    private record BetaSet(Map<String, Double> betas, Map<String, Integer> sessions) {
        Double beta(String symbol) { return betas.get(symbol); }
        int sessions(String symbol) { return sessions.getOrDefault(symbol, 0); }
        int minSessions(Set<String> symbols) {
            return symbols.stream().mapToInt(this::sessions).min().orElse(0);
        }
    }

    private BetaSet betas(List<String> symbols) {
        Map<String, Double> betas = new LinkedHashMap<>();
        Map<String, Integer> sessions = new LinkedHashMap<>();
        if (symbols.isEmpty()) return new BetaSet(betas, sessions);
        LocalDate since = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(400);
        TreeMap<LocalDate, Double> spy = db.with(c -> observedCloses(c, "SPY", since));
        if (spy.size() < 2) return new BetaSet(betas, sessions);
        for (String symbol : symbols) {
            TreeMap<LocalDate, Double> closes = "SPY".equals(symbol) ? spy
                    : db.with(c -> observedCloses(c, symbol, since));
            if (closes.size() < 2) continue;
            List<double[]> returns = matchedReturns(closes, spy);
            if (returns.size() > BETA_WINDOW) {
                returns = returns.subList(returns.size() - BETA_WINDOW, returns.size());
            }
            if (returns.size() < MIN_BETA_SESSIONS) {
                sessions.put(symbol, returns.size());
                continue;
            }
            double meanI = returns.stream().mapToDouble(r -> r[0]).average().orElse(0);
            double meanM = returns.stream().mapToDouble(r -> r[1]).average().orElse(0);
            double cov = 0, var = 0;
            for (double[] r : returns) {
                cov += (r[0] - meanI) * (r[1] - meanM);
                var += (r[1] - meanM) * (r[1] - meanM);
            }
            sessions.put(symbol, returns.size());
            if (var == 0) continue;
            betas.put(symbol, cov / var);
        }
        return new BetaSet(betas, sessions);
    }

    /** One coherent observed close series per symbol (same eligibility as the SPY benchmark). */
    private static TreeMap<LocalDate, Double> observedCloses(Connection c, String symbol,
                                                             LocalDate since) throws SQLException {
        record Bar(LocalDate d, double close, String source, boolean adjusted, int quality) {}
        List<Bar> bars = Db.queryOn(c, "SELECT d,close,source,adjusted,quality_rank FROM underlying_bar "
                        + "WHERE symbol=? AND dataset_id='observed' AND observed=1 AND d>=? "
                        + "AND LOWER(source) NOT IN ('fixture','synthetic','simulation','scenario','model') "
                        + "ORDER BY source,adjusted,d",
                r -> new Bar(r.date("d"), r.bd("close").doubleValue(), r.str("source"),
                        r.bool("adjusted"), r.intv("quality_rank")), symbol, since);
        Map<String, List<Bar>> series = new LinkedHashMap<>();
        for (Bar bar : bars) {
            series.computeIfAbsent(bar.source() + "|" + bar.adjusted(), ignored -> new ArrayList<>()).add(bar);
        }
        List<Bar> chosen = series.values().stream().max(Comparator
                        .comparingInt((List<Bar> s) -> s.size())
                        .thenComparingInt(s -> s.stream().mapToInt(Bar::quality).max().orElse(0))
                        .thenComparing(s -> s.getFirst().adjusted()))
                .orElse(List.of());
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        for (Bar bar : chosen) if (bar.close() > 0) out.put(bar.d(), bar.close());
        return out;
    }

    /** Simple returns over consecutive SHARED dates, so both series cover identical intervals. */
    private static List<double[]> matchedReturns(TreeMap<LocalDate, Double> symbol,
                                                 TreeMap<LocalDate, Double> market) {
        List<LocalDate> shared = new ArrayList<>();
        for (LocalDate d : market.keySet()) if (symbol.containsKey(d)) shared.add(d);
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < shared.size(); i++) {
            double s0 = symbol.get(shared.get(i - 1)), s1 = symbol.get(shared.get(i));
            double m0 = market.get(shared.get(i - 1)), m1 = market.get(shared.get(i));
            if (s0 <= 0 || m0 <= 0) continue;
            out.add(new double[]{s1 / s0 - 1.0, m1 / m0 - 1.0});
        }
        return out;
    }
}
