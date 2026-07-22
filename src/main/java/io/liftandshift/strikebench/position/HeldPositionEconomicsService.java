package io.liftandshift.strikebench.position;

import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.market.ExecutablePrice;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.paper.TradePreview;
import io.liftandshift.strikebench.paper.TradeService;
import io.liftandshift.strikebench.strategy.CoverageCheck;
import io.liftandshift.strikebench.util.Json;
import io.liftandshift.strikebench.util.Money;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fact-only adapter from the existing exact-package preview/evaluation into one held-position
 * lifecycle receipt. Pricing, EV, campaign math, events, and Book risk remain with their existing
 * owners. This service changes only the immediate cash leg needed to answer hold-versus-close.
 */
public final class HeldPositionEconomicsService {
    public static final String FRESH_EYES_QUESTION =
            "Would you open the exact position you still own today, ignoring sunk campaign cash?";
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final Clock clock;

    public HeldPositionEconomicsService(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Adapter facts supplied by the adoption/campaign composer; no accounting value is inferred here. */
    public record OpeningLeg(LegAction action, String instrumentType, long quantity,
                             int multiplier, BigDecimal openingFill, String sourceRef) {
        public OpeningLeg {
            if (action == null || instrumentType == null || instrumentType.isBlank()
                    || quantity <= 0 || multiplier <= 0 || openingFill == null
                    || openingFill.signum() < 0) {
                throw new IllegalArgumentException("opening leg needs action, type, quantity, multiplier, and fill");
            }
        }
    }

    public record HistoryContext(List<OpeningLeg> openingLegs, Long openingFeesCents,
                                 Long campaignRealizedCents, Long campaignUnrealizedCents,
                                 PositionLifecycleReceipt.MoneyFact taxLotBasisPerShare,
                                 PositionLifecycleReceipt.MoneyFact campaignBasisPerShare,
                                 String basis, List<String> sourceRefs) {
        public HistoryContext {
            openingLegs = openingLegs == null ? List.of() : List.copyOf(openingLegs);
            if (openingFeesCents != null && openingFeesCents < 0) {
                throw new IllegalArgumentException("opening fees cannot be negative");
            }
            if (taxLotBasisPerShare == null || campaignBasisPerShare == null
                    || basis == null || basis.isBlank()) {
                throw new IllegalArgumentException("history context needs both basis authorities and a basis note");
            }
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }

    public PositionLifecycleReceipt compose(TradeService.OpenRequest request, TradePreview preview,
                                            StrategyEvaluation evaluation) {
        if (request == null || preview == null || evaluation == null) {
            throw new IllegalArgumentException("request, exact preview, and canonical evaluation are required");
        }
        if (request.qty() < 1 || request.legs() == null || request.legs().isEmpty()) {
            throw new IllegalArgumentException("a held position requires positive exact package geometry");
        }

        PositionLifecycleReceipt.CloseQuote close = closeQuote(request, preview);
        EconomicAssessment freshEyes = evaluation.assessment() == null
                ? null : evaluation.assessment().economics();
        PositionLifecycleReceipt.ForwardEconomics hold = holdVsClose(preview, close, freshEyes);
        List<String> currentLimitations = new ArrayList<>();
        Long expectedShortfall = expectedShortfall(preview);
        if (expectedShortfall == null) {
            currentLimitations.add("The canonical probability-map receipt has no CVaR95 value.");
        }
        if (!close.executable()) currentLimitations.add(close.unavailableReason());

        int days = singleExpirationDays(request.legs());
        long grossRemaining = close.executable()
                ? Math.max(0, Math.negateExact(close.signedOptionExecutableCloseCashCents())) : 0;
        long modeledCollateral = Math.max(0, preview.reserveCents());
        Double grossAnnualized = days > 0 && modeledCollateral > 0 && grossRemaining > 0
                ? round4(100.0 * grossRemaining / modeledCollateral * 365.0 / days) : null;
        List<String> carryLimitations = new ArrayList<>();
        if (singleExpiration(request.legs()) == null) {
            carryLimitations.add("A mixed-expiration package has no single honest annualized remaining-premium clock.");
        }
        if (modeledCollateral == 0) {
            carryLimitations.add("This exact package has no model-derived cash reserve denominator.");
        }
        carryLimitations.add("Tracked-account encumbrance is model-derived until a broker-reported reserve is linked.");
        carryLimitations.add("Settlement-fund income is separate and unavailable until an account-level rate receipt is linked.");

        long sharesReleased = request.heldShares()
                ? Math.multiplyExact(CoverageCheck.shareContextUnitsNeeded(request.legs()), request.qty()) : 0;
        var collateral = new PositionLifecycleReceipt.MoneyFact(modeledCollateral,
                PositionDomain.FactAuthority.MODEL_DERIVED,
                "The exact package's canonical reserve model; not a broker buying-power claim.");
        var encumbrance = new PositionLifecycleReceipt.MoneyFact(modeledCollateral,
                PositionDomain.FactAuthority.MODEL_DERIVED,
                "Theoretical cash encumbrance from the exact package geometry.");
        var release = new PositionLifecycleReceipt.MoneyFact(modeledCollateral,
                PositionDomain.FactAuthority.MODEL_DERIVED,
                "Theoretical encumbrance removed by a full close; this is not a broker buying-power claim.");

        List<PositionLifecycleReceipt.AssignmentLeg> assignmentLegs = assignmentLegs(request, preview);
        List<String> assignmentLimitations = new ArrayList<>();
        assignmentLimitations.add("Tax-lot and campaign-adjusted bases require a linked tracked structure or campaign.");
        assignmentLimitations.add("Book impacts remain unavailable until the read-only Book action projection is composed.");
        assignmentLimitations.add("Event crossings remain unavailable until the canonical EventService receipt is composed.");

        String positionFingerprint = positionFingerprint(request);
        String marketFingerprint = snapshotFingerprint(preview);
        String modelFingerprint = modelFingerprint(evaluation);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return new PositionLifecycleReceipt(PositionLifecycleReceipt.SCHEMA_VERSION,
                request.symbol().trim().toUpperCase(Locale.ROOT), positionFingerprint,
                PositionLifecycleReceipt.History.unavailable(
                        "No linked opening/campaign receipt was supplied to this exact-package analysis.",
                        "Opening history is never inferred from today's executable marks."),
                new PositionLifecycleReceipt.CurrentChoice(close, FRESH_EYES_QUESTION,
                        PositionLifecycleReceipt.FRESH_EYES_ECONOMICS_REF, hold, expectedShortfall,
                        expectedShortfall == null ? null
                                : "Absolute loss of the canonical risk-neutral CVaR95 P/L receipt.",
                        PositionLifecycleReceipt.STANCE_REF,
                        "Fresh-eyes keeps the existing evaluation; hold-vs-close replaces only today's immediate cash leg.",
                        currentLimitations),
                new PositionLifecycleReceipt.CarryCollateral(
                        close.executable() ? grossRemaining : null,
                        grossAnnualized, days < 0 ? null : days, collateral,
                        PositionLifecycleReceipt.RateFact.unavailable(
                                "No broker-reported settlement-fund rate/income receipt is linked to this analysis."),
                        encumbrance, release, sharesReleased,
                        "Gross remaining premium is the executable close debit avoided if the net-short package expires worthless; "
                                + "it is not an expected return and it never replaces EV.", carryLimitations),
                new PositionLifecycleReceipt.AssignmentExit(assignmentLegs,
                        PositionLifecycleReceipt.MoneyFact.unavailable("No linked tracked tax-lot basis receipt."),
                        PositionLifecycleReceipt.MoneyFact.unavailable("No linked campaign-adjusted basis receipt."),
                        List.of(), "UNAVAILABLE", "UNAVAILABLE",
                        "Short-contract geometry supplies exact shares and strike dollars; intent and probability remain separate.",
                        assignmentLimitations),
                new PositionLifecycleReceipt.Evidence(now, "PARTIAL", marketFingerprint,
                        modelFingerprint, "FACTS_ONLY",
                        List.of("preview", "evaluation", PositionLifecycleReceipt.FRESH_EYES_ECONOMICS_REF,
                                PositionLifecycleReceipt.STANCE_REF),
                        List.of("History, events, broker reserve, settlement income, and Book projections are not linked yet.")));
    }

    /**
     * Adds the frozen adoption/campaign/accounting facts to the same receipt. This never reprices
     * the package and never copies campaign arithmetic into tax basis (or vice versa).
     */
    public PositionLifecycleReceipt withHistory(PositionLifecycleReceipt base, HistoryContext context) {
        if (base == null || context == null) {
            throw new IllegalArgumentException("base lifecycle receipt and history context are required");
        }
        long signedOpening = 0;
        long signedOptionOpening = 0;
        for (OpeningLeg leg : context.openingLegs()) {
            long amount = Money.centsFromPrice(leg.openingFill(),
                    Math.multiplyExact(leg.quantity(), (long) leg.multiplier()));
            long signed = leg.action() == LegAction.SELL ? amount : Math.negateExact(amount);
            signedOpening = Math.addExact(signedOpening, signed);
            if ("OPTION".equalsIgnoreCase(leg.instrumentType())) {
                signedOptionOpening = Math.addExact(signedOptionOpening, signed);
            }
        }
        boolean available = !context.openingLegs().isEmpty();
        Long grossCredit = available ? Math.max(0, signedOptionOpening) : null;
        Long netCredit = available && context.openingFeesCents() != null
                ? Math.max(0, Math.subtractExact(signedOptionOpening, context.openingFeesCents())) : null;
        Long captured = null;
        Double capturedPct = null;
        Long netPnl = null;
        var close = base.currentChoice().close();
        if (available && grossCredit != null && grossCredit > 0 && close.executable()) {
            captured = Math.addExact(grossCredit, close.signedOptionExecutableCloseCashCents());
            capturedPct = round4(100.0 * captured / grossCredit);
        }
        if (available && context.openingFeesCents() != null && close.executable()) {
            netPnl = Math.subtractExact(Math.addExact(
                    Math.subtractExact(signedOptionOpening, context.openingFeesCents()),
                    close.signedOptionExecutableCloseCashCents()), close.closingFeesCents());
        }
        List<String> refs = new ArrayList<>(context.sourceRefs());
        for (OpeningLeg leg : context.openingLegs()) {
            if (leg.sourceRef() != null && !leg.sourceRef().isBlank()) refs.add(leg.sourceRef());
        }
        refs = refs.stream().distinct().toList();
        PositionLifecycleReceipt.History history = available
                ? new PositionLifecycleReceipt.History(true, signedOpening, signedOptionOpening,
                        context.openingFeesCents(), grossCredit, netCredit, captured, capturedPct,
                        netPnl, context.campaignRealizedCents(), context.campaignUnrealizedCents(),
                        context.basis(), null, refs)
                : PositionLifecycleReceipt.History.unavailable(
                        "The linked adoption receipt has no frozen opening-leg fills.", context.basis());

        var assignment = base.assignmentExit();
        var enrichedAssignment = new PositionLifecycleReceipt.AssignmentExit(
                assignment.legs(), context.taxLotBasisPerShare(), context.campaignBasisPerShare(),
                assignment.eventCrossings(), assignment.eventEvidenceStatus(), assignment.bookImpactRef(),
                assignment.basis(), assignment.limitations());
        var evidence = base.evidence();
        List<String> evidenceRefs = new ArrayList<>(evidence.sourceRefs());
        evidenceRefs.addAll(refs);
        return new PositionLifecycleReceipt(base.schemaVersion(), base.symbol(), base.positionFingerprint(),
                history, base.currentChoice(), base.carryCollateral(), enrichedAssignment,
                new PositionLifecycleReceipt.Evidence(evidence.observedAt(),
                        available ? "PARTIAL" : evidence.reconciliationStatus(),
                        evidence.marketSnapshotFingerprint(), evidence.modelFingerprint(),
                        evidence.policyFingerprint(), evidenceRefs.stream().distinct().toList(),
                        evidence.limitations()));
    }

    private PositionLifecycleReceipt.CloseQuote closeQuote(TradeService.OpenRequest request,
                                                            TradePreview preview) {
        if (preview.legs() == null || preview.legs().size() != request.legs().size()) {
            return unavailableClose("The exact preview does not contain one current quote receipt per leg.");
        }
        long executableCash = 0;
        long optionExecutableCash = 0;
        long midCash = 0;
        boolean midComplete = true;
        PositionDomain.PriceAuthority authority = authority(preview);
        for (int i = 0; i < request.legs().size(); i++) {
            Leg leg = request.legs().get(i);
            Map<String, Object> quote = preview.legs().get(i);
            BigDecimal bid = decimal(quote.get("bid"));
            BigDecimal ask = decimal(quote.get("ask"));
            BigDecimal mid = decimal(quote.get("mid"));
            BigDecimal executable = ExecutablePrice.forAction(bid, ask, leg.action().opposite());
            if (executable == null) {
                return unavailableClose("No executable "
                        + (leg.action() == LegAction.BUY ? "bid" : "ask")
                        + " exists for current leg " + (i + 1) + "; hold-vs-close economics stay unavailable.");
            }
            long units = Math.multiplyExact(Math.multiplyExact((long) leg.multiplier(), leg.ratio()), request.qty());
            int sign = leg.action() == LegAction.BUY ? 1 : -1;
            executableCash = Math.addExact(executableCash,
                    Math.multiplyExact(sign, Money.centsFromPrice(executable, units)));
            if (!leg.isStock()) {
                optionExecutableCash = Math.addExact(optionExecutableCash,
                        Math.multiplyExact(sign, Money.centsFromPrice(executable, units)));
            }
            if (mid != null && midComplete) {
                midCash = Math.addExact(midCash, Math.multiplyExact(sign, Money.centsFromPrice(mid, units)));
            } else {
                midComplete = false;
            }
        }
        long fees = Math.max(0, preview.feesOpenCents());
        long net = Math.subtractExact(executableCash, fees);
        return new PositionLifecycleReceipt.CloseQuote(true,
                midComplete ? midCash : null, executableCash, optionExecutableCash, fees, net, authority,
                "Every long leg closes at bid and every short leg closes at ask; crossed/one-sided books are unavailable. "
                        + "Closing fees use the exact preview's configured symmetric fee schedule.", null);
    }

    private static PositionLifecycleReceipt.CloseQuote unavailableClose(String reason) {
        return new PositionLifecycleReceipt.CloseQuote(false, null, null, null, null, null, null,
                "Executable close quotes require every opposite-side book.", reason);
    }

    private static PositionLifecycleReceipt.ForwardEconomics holdVsClose(
            TradePreview preview, PositionLifecycleReceipt.CloseQuote close, EconomicAssessment freshEyes) {
        if (!close.executable()) {
            return PositionLifecycleReceipt.ForwardEconomics.unavailable(close.unavailableReason(),
                    "Hold-vs-close cannot be derived without an executable close.");
        }
        if (freshEyes == null) {
            return PositionLifecycleReceipt.ForwardEconomics.unavailable(
                    "The canonical evaluation has no EconomicAssessment.",
                    "Hold-vs-close is a cash-leg transform of the canonical fresh-eyes economics.");
        }
        long immediateOpenNet = Math.subtractExact(preview.entryNetPremiumCents(), preview.feesOpenCents());
        long substitution = Math.subtractExact(Math.negateExact(immediateOpenNet),
                close.signedNetCloseCashCents());
        Long market = shifted(freshEyes.marketEvAfterCostsCents(), substitution);
        Long realized = shifted(freshEyes.realizedVolEvAfterCostsCents(), substitution);
        Long low = shifted(freshEyes.realisticEvLowAfterCostsCents(), substitution);
        Long high = shifted(freshEyes.realisticEvHighAfterCostsCents(), substitution);
        boolean available = market != null || realized != null || low != null || high != null;
        if (!available) {
            return PositionLifecycleReceipt.ForwardEconomics.unavailable(
                    "The canonical fresh-eyes assessment has no forward EV lane.",
                    "Hold-vs-close is a cash-leg transform of the canonical fresh-eyes economics.");
        }
        return new PositionLifecycleReceipt.ForwardEconomics(true, market, realized, low, high,
                freshEyes.realisticEvMaterialityCents(), freshEyes.observedEvidence(),
                "For every existing economic lane: hold-vs-close EV = fresh-eyes EV − "
                        + "(fresh executable opening cash less opening fee) − "
                        + "(executable closing cash less closing fee). The future payoff/model is unchanged; "
                        + "bid/ask and fees are therefore represented exactly once.", null);
    }

    private static Long shifted(Long value, long delta) {
        return value == null ? null : Math.addExact(value, delta);
    }

    private static Long expectedShortfall(TradePreview preview) {
        Object probability = preview.analytics() == null ? null : preview.analytics().get("probabilityMap");
        if (!(probability instanceof Map<?, ?> map) || !(map.get("cvar95Cents") instanceof Number cvar)) {
            return null;
        }
        long pnl = cvar.longValue();
        return pnl < 0 ? Math.negateExact(pnl) : 0L;
    }

    private static List<PositionLifecycleReceipt.AssignmentLeg> assignmentLegs(
            TradeService.OpenRequest request, TradePreview preview) {
        List<PositionLifecycleReceipt.AssignmentLeg> out = new ArrayList<>();
        for (int i = 0; i < request.legs().size(); i++) {
            Leg leg = request.legs().get(i);
            if (leg.isStock() || leg.action() != LegAction.SELL) continue;
            long shares = Math.multiplyExact(Math.multiplyExact((long) leg.multiplier(), leg.ratio()), request.qty());
            long strike = Money.toCents(leg.strike());
            long dollars = Math.multiplyExact(strike, shares);
            BigDecimal currentPremium = preview.legs() != null && i < preview.legs().size()
                    ? decimal(preview.legs().get(i).get("fill")) : null;
            Long effective = currentPremium == null ? null
                    : leg.type() == OptionType.PUT
                        ? Math.subtractExact(strike, Money.toCents(currentPremium))
                        : Math.addExact(strike, Money.toCents(currentPremium));
            out.add(new PositionLifecycleReceipt.AssignmentLeg(leg.type(), leg.expiration(), strike,
                    shares, dollars, effective,
                    leg.type() == OptionType.PUT ? "BUY_SHARES" : "SELL_OR_CASH_SETTLE_SHARES",
                    "Exact short-leg geometry. The effective price uses only that leg's current fresh-eyes "
                            + "executable premium; tracked tax and campaign bases remain separate."));
        }
        return List.copyOf(out);
    }

    private int singleExpirationDays(List<Leg> legs) {
        LocalDate expiration = singleExpiration(legs);
        if (expiration == null) return -1;
        LocalDate today = LocalDate.ofInstant(clock.instant(), MARKET_ZONE);
        return Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(today, expiration)));
    }

    private static LocalDate singleExpiration(List<Leg> legs) {
        List<LocalDate> expirations = legs.stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).distinct().toList();
        return expirations.size() == 1 ? expirations.getFirst() : null;
    }

    private static PositionDomain.PriceAuthority authority(TradePreview preview) {
        if (preview.evidence() == null || preview.evidence().provenance() == null) {
            return PositionDomain.PriceAuthority.MODELED;
        }
        return switch (preview.evidence().provenance()) {
            case OBSERVED -> PositionDomain.PriceAuthority.OBSERVED;
            case BROKER -> PositionDomain.PriceAuthority.BROKER_REPORTED;
            default -> PositionDomain.PriceAuthority.MODELED;
        };
    }

    private static String positionFingerprint(TradeService.OpenRequest request) {
        List<PositionPackage.Leg> legs = new ArrayList<>();
        for (int i = 0; i < request.legs().size(); i++) {
            Leg leg = request.legs().get(i);
            legs.add(new PositionPackage.Leg(i, leg.action().name(), leg.isStock() ? "STOCK" : "OPTION",
                    request.symbol(), leg.isStock() ? null : leg.type().name(), leg.strike(), leg.expiration(),
                    Math.multiplyExact(request.qty(), (long) leg.ratio()), leg.multiplier(), null,
                    PositionDomain.PriceAuthority.MODELED));
        }
        var position = new PositionPackage("lifecycle-identity",
                PositionDomain.PackageSource.TRACKED_STRUCTURE, PositionDomain.ExecutionLane.REAL,
                request.symbol(), request.qty(), null,
                OffsetDateTime.parse("1970-01-01T00:00:00Z"), legs);
        var provenance = new PositionPackageFingerprint.EntryProvenance(
                "1970-01-01T00:00:00Z", "PACKAGE_GEOMETRY", "NOT_APPLICABLE",
                "exact request", null);
        return PositionPackageFingerprint.fingerprint(
                PositionPackageFingerprint.focusedIdentity(position, 0, provenance));
    }

    private static String snapshotFingerprint(TradePreview preview) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("underlyingCents", preview.underlyingCents());
        facts.put("freshness", preview.freshness());
        facts.put("evidence", preview.evidence());
        facts.put("legs", preview.legs());
        if (preview.analytics() != null) {
            facts.put("sourceAsOfEpochMs", preview.analytics().get("sourceAsOfEpochMs"));
            facts.put("evaluatedAtEpochMs", preview.analytics().get("evaluatedAtEpochMs"));
        }
        return PositionPackageFingerprint.entrySnapshotFingerprint(Json.write(facts));
    }

    private static String modelFingerprint(StrategyEvaluation evaluation) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("pricingModel", evaluation.coverage() == null ? null : evaluation.coverage().pricingModel());
        model.put("evBasis", evaluation.risk() == null ? null : evaluation.risk().evBasisNote());
        model.put("terminalPayoffModel", evaluation.risk() == null || evaluation.risk().terminalPayoff() == null
                ? null : evaluation.risk().terminalPayoff().modelVersion());
        EconomicAssessment economics = evaluation.assessment() == null ? null : evaluation.assessment().economics();
        model.put("realisticEvBasis", economics == null ? null : economics.realisticEvBasis());
        return PositionPackageFingerprint.entrySnapshotFingerprint(Json.write(model));
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
