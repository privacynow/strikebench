package io.liftandshift.strikebench.position;
import static io.liftandshift.strikebench.util.Numbers.round4;

import io.liftandshift.strikebench.eval.EconomicAssessment;
import io.liftandshift.strikebench.eval.StrategyEvaluation;
import io.liftandshift.strikebench.market.ExecutablePrice;
import io.liftandshift.strikebench.market.EventService;
import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Symbol;
import io.liftandshift.strikebench.paper.PackagePrice;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fact-only adapter from the existing exact-package preview/evaluation into one held-position
 * lifecycle result. Pricing, EV, campaign math, events, and Book risk remain with their existing
 * owners. This service changes only the immediate cash leg needed to answer hold-versus-close.
 */
public final class HeldPositionEconomicsService {
    public static final String FRESH_EYES_QUESTION =
            "Would you open the exact position you still own today, ignoring sunk campaign cash?";
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final EventService events;

    public HeldPositionEconomicsService(Clock clock) {
        this(clock, null);
    }

    public HeldPositionEconomicsService(Clock clock, EventService events) {
        this.events = events;
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
                                 AuthorityFacts.MoneyFact taxLotBasisPerShare,
                                 AuthorityFacts.MoneyFact campaignBasisPerShare,
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

    public PositionLifecycleAnalysis compose(TradeService.OpenRequest request, TradePreview preview,
                                            StrategyEvaluation evaluation) {
        Object supplied = preview == null || preview.analytics() == null
                ? null : preview.analytics().get("time");
        OptionTime.Measure time = supplied instanceof OptionTime.Measure measured ? measured : null;
        if (time == null && preview != null && preview.analytics() != null
                && preview.analytics().get("evaluatedAtEpochMs") instanceof Number stamp) {
            time = OptionTime.nearest(request == null ? null : request.legs(),
                    java.time.Instant.ofEpochMilli(stamp.longValue()));
        }
        if (time == null) {
            throw new IllegalArgumentException(
                    "held-position analysis requires the preview's mode-aware option-time result");
        }
        return compose(request, preview, evaluation, time);
    }

    /**
     * Composes against the exact market-mode clock that priced the preview. No wall-clock read is
     * permitted here: a Practice simulation may be months away from today, and using the host clock
     * would corrupt annualization, event crossings, calendar days, and management sessions together.
     */
    public PositionLifecycleAnalysis compose(TradeService.OpenRequest request, TradePreview preview,
                                            StrategyEvaluation evaluation,
                                            OptionTime.Measure time) {
        return compose(request, preview, evaluation, time, null);
    }

    /**
     * Practice positions may supply the exact current-market result already produced by
     * {@link TradeService}. The lifecycle mode consumes that result instead of repricing the same
     * quote maps. Tracked packages, which have no Practice MarkView, continue through the preview
     * adapter below.
     */
    public PositionLifecycleAnalysis compose(TradeService.OpenRequest request, TradePreview preview,
                                            StrategyEvaluation evaluation,
                                            OptionTime.Measure time,
                                            TradeService.MarkView currentMarket) {
        if (request == null || preview == null) {
            throw new IllegalArgumentException("request and exact preview are required");
        }
        if (request.qty() < 1 || request.legs() == null || request.legs().isEmpty()) {
            throw new IllegalArgumentException("a held position requires positive exact package geometry");
        }
        if (time == null || time.asOf() == null) {
            throw new IllegalArgumentException("held-position analysis requires a mode timestamp");
        }

        PositionLifecycleAnalysis.CloseQuote close = currentMarket == null
                ? closeQuote(request, preview)
                : closeQuote(request, currentMarket);
        EconomicAssessment freshEyes = evaluation == null || evaluation.assessment() == null
                ? null : evaluation.assessment().economics();
        PositionLifecycleAnalysis.ForwardEconomics hold = holdVsClose(preview, close, freshEyes);
        List<String> currentLimitations = new ArrayList<>();
        Long expectedShortfall = expectedShortfall(preview);
        if (expectedShortfall == null) {
            currentLimitations.add("The current probability analysis has no CVaR95 value.");
        }
        if (!close.executable()) currentLimitations.add(close.unavailableReason());

        Long calendarDays = time.calendarDays() < 0 ? null : time.calendarDays();
        Integer sessions = time.hasManagementClock() ? time.sessions() : null;
        long grossRemaining = close.executable()
                ? Math.max(0, Math.negateExact(close.price().optionNetPremiumCents())) : 0;
        Long modeledCollateral = preview.reserveCents();
        Double grossAnnualized = modeledCollateral != null && grossRemaining > 0
                ? time.annualizedSimplePercent(grossRemaining, modeledCollateral) : null;
        if (grossAnnualized != null) grossAnnualized = round4(grossAnnualized);
        List<String> carryLimitations = new ArrayList<>();
        if (singleExpiration(request.legs()) == null) {
            carryLimitations.add("A mixed-expiration package has no single honest annualized remaining-premium clock.");
        }
        if (modeledCollateral == null) {
            carryLimitations.add("This exact package has no finite reserve result, so collateral, "
                    + "encumbrance, and released capital remain unavailable.");
        } else if (modeledCollateral == 0) {
            carryLimitations.add("This exact package has no model-derived cash reserve denominator.");
        }
        carryLimitations.add("Tracked-account encumbrance is model-derived until a broker-reported reserve is linked.");
        carryLimitations.add("Settlement-fund income is separate and unavailable until an account-level rate result is linked.");

        long sharesReleased = request.heldShares()
                ? Math.multiplyExact(CoverageCheck.shareContextUnitsNeeded(request.legs()), request.qty()) : 0;
        var collateral = modeledCollateral == null
                ? AuthorityFacts.MoneyFact.unavailable(
                        "The exact package has no finite reserve result.")
                : new AuthorityFacts.MoneyFact(modeledCollateral,
                        PositionDomain.FactAuthority.MODEL_DERIVED,
                        "The exact package reserve calculation; not a broker buying-power claim.");
        var encumbrance = modeledCollateral == null
                ? AuthorityFacts.MoneyFact.unavailable(
                        "The exact package has no finite reserve result.")
                : new AuthorityFacts.MoneyFact(modeledCollateral,
                        PositionDomain.FactAuthority.MODEL_DERIVED,
                        "Theoretical cash encumbrance from the exact package geometry.");
        var release = modeledCollateral == null
                ? AuthorityFacts.MoneyFact.unavailable(
                        "Capital release cannot be stated without a finite reserve result.")
                : new AuthorityFacts.MoneyFact(modeledCollateral,
                        PositionDomain.FactAuthority.MODEL_DERIVED,
                        "Theoretical encumbrance removed by a full close; this is not a broker buying-power claim.");

        List<PositionLifecycleAnalysis.AssignmentLeg> assignmentLegs = assignmentLegs(request, preview);
        EventFacts eventFacts = eventFacts(request, time);
        List<String> assignmentLimitations = new ArrayList<>();
        assignmentLimitations.add("Tax-lot and campaign-adjusted bases require a linked tracked structure or campaign.");
        assignmentLimitations.add("Book impacts remain unavailable until the read-only Book action projection is composed.");
        assignmentLimitations.addAll(eventFacts.limitations());

        String positionFingerprint = positionFingerprint(request);
        String marketFingerprint = snapshotFingerprint(preview);
        String modelFingerprint = modelFingerprint(evaluation);
        String freshEyesRef = evaluation == null
                ? "evaluation:UNAVAILABLE"
                : PositionLifecycleAnalysis.FRESH_EYES_ECONOMICS_REF;
        String stanceRef = evaluation == null
                ? "evaluation:UNAVAILABLE"
                : PositionLifecycleAnalysis.STANCE_REF;
        OffsetDateTime now = OffsetDateTime.ofInstant(time.asOf(), ZoneOffset.UTC);
        return new PositionLifecycleAnalysis(PositionLifecycleAnalysis.SCHEMA_VERSION,
                Symbol.normalize(request.symbol()), positionFingerprint,
                PositionLifecycleAnalysis.History.unavailable(
                        "No linked opening/campaign result was supplied to this exact-package analysis.",
                        "Opening history is never inferred from today's executable marks."),
                new PositionLifecycleAnalysis.CurrentChoice(close, FRESH_EYES_QUESTION,
                        freshEyesRef, hold, expectedShortfall,
                        expectedShortfall == null ? null
                                : "Absolute loss of the risk-neutral CVaR95 P/L result.",
                        stanceRef,
                        evaluation == null
                                ? "Fresh-eyes economics and stance are unavailable; close facts remain separate and no hold claim is inferred."
                                : "Fresh-eyes keeps the existing evaluation; hold-vs-close replaces only today's immediate cash leg.",
                        currentLimitations),
                new PositionLifecycleAnalysis.CarryCollateral(
                        close.executable() ? grossRemaining : null,
                        grossAnnualized,
                        calendarDays == null ? null : Math.toIntExact(calendarDays),
                        sessions, collateral,
                        AuthorityFacts.RateFact.unavailable(
                                "No broker-reported settlement-fund rate/income result is linked to this analysis."),
                        encumbrance, release, sharesReleased,
                        "Gross remaining premium is the executable close debit avoided if the net-short package expires worthless; "
                                + "it is not an expected return and it never replaces EV.", carryLimitations),
                new PositionLifecycleAnalysis.AssignmentExit(assignmentLegs,
                        AuthorityFacts.MoneyFact.unavailable("No linked tracked tax-lot basis result."),
                        AuthorityFacts.MoneyFact.unavailable("No linked campaign-adjusted basis result."),
                        eventFacts.crossings(), eventFacts.status(), "UNAVAILABLE",
                        "Short-option strikes and quantities supply exact shares and strike dollars; current event data "
                                + "supplies event evidence; intent and probability remain separate.",
                        assignmentLimitations),
                new PositionLifecycleAnalysis.Evidence(now, "PARTIAL", marketFingerprint,
                        modelFingerprint, "FACTS_ONLY",
                        eventFacts.sourceRefs().isEmpty()
                                ? List.of("preview",
                                    evaluation == null ? "evaluation:UNAVAILABLE" : "evaluation",
                                    freshEyesRef, stanceRef).stream().distinct().toList()
                                : java.util.stream.Stream.concat(
                                        List.of("preview",
                                                evaluation == null ? "evaluation:UNAVAILABLE" : "evaluation",
                                                freshEyesRef, stanceRef).stream(),
                                        eventFacts.sourceRefs().stream()).distinct().toList(),
                        evaluation == null
                                ? List.of("The fresh-eyes evaluation is unavailable; no score, "
                                        + "stance, or forward economics was substituted.",
                                        "History, broker reserve, settlement income, and Book projections are not linked yet.")
                                : List.of("History, broker reserve, settlement income, and Book projections are not linked yet.")));
    }

    private record EventFacts(List<PositionLifecycleAnalysis.EventCrossing> crossings,
                              String status, List<String> limitations, List<String> sourceRefs) {}

    private EventFacts eventFacts(TradeService.OpenRequest request, OptionTime.Measure time) {
        if (events == null) {
            return new EventFacts(List.of(), "UNAVAILABLE",
                    List.of("Event evidence was not supplied for this position."), List.of());
        }
        EventService.EventEvidence event;
        try {
            event = events.earnings(request.symbol());
        } catch (RuntimeException unavailable) {
            return new EventFacts(List.of(), "UNAVAILABLE",
                    List.of("Event evidence could not be read; this is not a no-event claim."), List.of());
        }
        List<String> refs = List.of("event:" + event.payloadFingerprint());
        if (!event.available()) {
            return new EventFacts(List.of(), event.status().name(), List.of(event.note()), refs);
        }
        LocalDate today = LocalDate.ofInstant(time.asOf(), MARKET_ZONE);
        LocalDate lastExpiration = request.legs().stream().filter(leg -> !leg.isStock())
                .map(Leg::expiration).max(LocalDate::compareTo).orElse(null);
        boolean crosses = lastExpiration != null
                && !event.confidenceStart().isAfter(lastExpiration)
                && !event.confidenceEnd().isBefore(today);
        if (!crosses) return new EventFacts(List.of(), event.status().name(), List.of(), refs);
        return new EventFacts(List.of(new PositionLifecycleAnalysis.EventCrossing(
                event.eventType().name(), event.date(), event.session().name(), event.status().name(),
                event.source(), event.sourceUrl(), event.observedAt(), event.payloadFingerprint())),
                event.status().name(), List.of(), refs);
    }

    /**
     * Adds the frozen adoption/campaign/accounting facts to the same result. This never reprices
     * the package and never copies campaign arithmetic into tax basis (or vice versa).
     */
    public PositionLifecycleAnalysis withHistory(PositionLifecycleAnalysis base, HistoryContext context) {
        if (base == null || context == null) {
            throw new IllegalArgumentException("base lifecycle result and history context are required");
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
            captured = Math.addExact(grossCredit, close.price().optionNetPremiumCents());
            capturedPct = round4(100.0 * captured / grossCredit);
        }
        if (available && context.openingFeesCents() != null && close.executable()) {
            netPnl = Math.subtractExact(Math.addExact(
                    Math.subtractExact(signedOptionOpening, context.openingFeesCents()),
                    close.price().optionNetPremiumCents()), close.price().openingFeesCents());
        }
        List<String> refs = new ArrayList<>(context.sourceRefs());
        for (OpeningLeg leg : context.openingLegs()) {
            if (leg.sourceRef() != null && !leg.sourceRef().isBlank()) refs.add(leg.sourceRef());
        }
        refs = refs.stream().distinct().toList();
        PositionLifecycleAnalysis.History history = available
                ? new PositionLifecycleAnalysis.History(true, signedOpening, signedOptionOpening,
                        context.openingFeesCents(), grossCredit, netCredit, captured, capturedPct,
                        netPnl, context.campaignRealizedCents(), context.campaignUnrealizedCents(),
                        context.basis(), null, refs)
                : PositionLifecycleAnalysis.History.unavailable(
                        "The linked adoption result has no frozen opening-leg fills.", context.basis());

        var assignment = base.assignmentExit();
        var enrichedAssignment = new PositionLifecycleAnalysis.AssignmentExit(
                assignment.legs(), context.taxLotBasisPerShare(), context.campaignBasisPerShare(),
                assignment.eventCrossings(), assignment.eventEvidenceStatus(), assignment.bookImpactRef(),
                assignment.basis(), assignment.limitations());
        var evidence = base.evidence();
        List<String> evidenceRefs = new ArrayList<>(evidence.sourceRefs());
        evidenceRefs.addAll(refs);
        return new PositionLifecycleAnalysis(base.schemaVersion(), base.symbol(), base.positionFingerprint(),
                history, base.currentChoice(), base.carryCollateral(), enrichedAssignment,
                new PositionLifecycleAnalysis.Evidence(evidence.observedAt(),
                        available ? "PARTIAL" : evidence.reconciliationStatus(),
                        evidence.marketSnapshotFingerprint(), evidence.modelFingerprint(),
                        evidence.policyFingerprint(), evidenceRefs.stream().distinct().toList(),
                        evidence.limitations()));
    }

    private PositionLifecycleAnalysis.CloseQuote closeQuote(TradeService.OpenRequest request,
                                                            TradePreview preview) {
        if (preview.legs() == null || preview.legs().size() != request.legs().size()) {
            return unavailableClose(request.qty(),
                    "The exact preview does not contain one current quote result per leg.");
        }
        long executableCash = 0;
        long optionExecutableCash = 0;
        // Measured in the SAME pass, from the same executable per-leg prices, so the result's
        // additive identity compares three separately accumulated facts instead of restating one.
        long stockExecutableCash = 0;
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
                return unavailableClose(request.qty(), "No executable "
                        + (leg.action() == LegAction.BUY ? "bid" : "ask")
                        + " exists for current leg " + (i + 1) + "; hold-vs-close economics stay unavailable.");
            }
            long units = Math.multiplyExact(Math.multiplyExact((long) leg.multiplier(), leg.ratio()), request.qty());
            int sign = leg.action() == LegAction.BUY ? 1 : -1;
            executableCash = Math.addExact(executableCash,
                    Math.multiplyExact(sign, Money.centsFromPrice(executable, units)));
            if (leg.isStock()) {
                stockExecutableCash = Math.addExact(stockExecutableCash,
                        Math.multiplyExact(sign, Money.centsFromPrice(executable, units)));
            } else {
                optionExecutableCash = Math.addExact(optionExecutableCash,
                        Math.multiplyExact(sign, Money.centsFromPrice(executable, units)));
            }
            if (mid != null && midComplete) {
                midCash = Math.addExact(midCash, Math.multiplyExact(sign, Money.centsFromPrice(mid, units)));
            } else {
                midComplete = false;
            }
        }
        // The symmetric fee schedule charges the same commission either way, but this is the CLOSING
        // side and the result now says so rather than borrowing a field named for the opening.
        // No local clamp: the result owns the "fees are never negative" rule and now enforces it
        // instead of two producers quietly rewriting the commission they were handed.
        //
        // The commission is read from the exact preview's own §7.2 result, not from a parallel
        // `feesOpenCents` primitive that was 0 on every package the preview refused to price. A
        // closing quote whose commission is unknown is not a quote (§3.2) — it would understate the
        // cost of getting out by exactly the commission — so it states the absence instead.
        Long fees = preview.price() == null ? null : preview.price().openingFeesCents();
        if (fees == null) {
            return unavailableClose(request.qty(), "The exact package price states no commission, so"
                    + " the cost of closing this position cannot be quoted.");
        }
        var price = PackagePrice.of(request.qty(), executableCash, optionExecutableCash,
                stockExecutableCash, fees, null,
                PackagePrice.FeeSide.CLOSING, executableCash, null,
                io.liftandshift.strikebench.paper.OrderInstruction.Executability.IMMEDIATE,
                PackagePrice.ValuationBasis.EXECUTABLE_BOOK,
                preview.evidence() == null ? null : preview.evidence().source(), preview.freshness(),
                snapshotObservedAt(preview),
                PackagePrice.fingerprintOf(request.legs(), request.qty(), executableCash,
                        PackagePrice.ValuationBasis.EXECUTABLE_BOOK, snapshotObservedAt(preview)));
        return new PositionLifecycleAnalysis.CloseQuote(true, price, midComplete ? midCash : null, authority,
                "Every long leg closes at bid and every short leg closes at ask; crossed/one-sided books are unavailable. "
                        + "Closing fees use the exact preview's configured symmetric fee schedule.", null);
    }

    /**
     * Adapt the ONE current closing-price result into lifecycle vocabulary. A stale two-sided book
     * may retain an indicative gross valuation, but it can never become executable closing cash or
     * unlock hold-vs-close advice.
     */
    private PositionLifecycleAnalysis.CloseQuote closeQuote(
            TradeService.OpenRequest request,
            TradeService.MarkView currentMarket) {
        PackagePrice price = currentMarket.currentClosePrice();
        String reason = currentMarket.availability() == null
                ? "The current market result does not state whether this package can be closed."
                : currentMarket.availability().closeUnavailableReason();
        if (price == null || !price.priced()) {
            return unavailableClose(request.qty(),
                    reason == null || reason.isBlank()
                            ? price == null ? "No current closing-price result is available."
                                : price.unavailableReason()
                            : reason);
        }
        if (price.executableNetCents() == null
                || currentMarket.availability() == null
                || !currentMarket.availability().closeAvailable()) {
            String named = reason == null || reason.isBlank()
                    ? "The current closing-price result is indicative only and cannot support "
                        + "an executable close or lifecycle advice."
                    : reason;
            return new PositionLifecycleAnalysis.CloseQuote(false, price, null,
                    authority(price),
                    "The current-market result retains labeled indicative valuation "
                            + "separately from executable closing cash.",
                    named);
        }
        if (!java.util.Objects.equals(
                price.executableNetCents(),
                currentMarket.currentClosePrice().executableNetCents())) {
            throw new IllegalStateException(
                    "Current-market close cash disagrees with its package-price result.");
        }
        return new PositionLifecycleAnalysis.CloseQuote(true, price, null, authority(price),
                "The current-market result prices every long close at bid and every "
                        + "short close at ask, with mode executability and closing fees attached.",
                null);
    }

    /** The stalest leg stamp on the preview — the package is no fresher than its oldest quote. */
    private static Long snapshotObservedAt(TradePreview preview) {
        if (preview.legs() == null) return null;
        return PackagePrice.observedAtOf(preview.legs().stream()
                .map(leg -> leg.get("asOfEpochMs") instanceof Number stamp ? stamp.longValue() : null)
                .toList());
    }

    /**
     * §3.2/§3.5: the unpriced close still states the SIZE it failed to price. The quantity was
     * hardcoded to 1 with {@code request.qty()} in scope at both call sites, so a 5-lot position
     * whose book went one-sided published a result for a single contract — a silent product
     * default sitting inside the very object that exists to stop invented amounts.
     */
    private static PositionLifecycleAnalysis.CloseQuote unavailableClose(int quantity, String reason) {
        return PositionLifecycleAnalysis.CloseQuote.unavailable(quantity,
                "Executable close quotes require every opposite-side book.", reason);
    }

    private static PositionLifecycleAnalysis.ForwardEconomics holdVsClose(
            TradePreview preview, PositionLifecycleAnalysis.CloseQuote close, EconomicAssessment freshEyes) {
        if (!close.executable()) {
            return PositionLifecycleAnalysis.ForwardEconomics.unavailable(close.unavailableReason(),
                    "Hold-vs-close cannot be derived without an executable close.");
        }
        if (freshEyes == null) {
            return PositionLifecycleAnalysis.ForwardEconomics.unavailable(
                    "The evaluation has no economic assessment.",
                    "Hold-vs-close adjusts the fresh-eyes economics for today's closing cash flow.");
        }
        // The fresh executable opening cash LESS the opening fee is exactly the §7.2 result's own
        // afterFeeNetCents, which the result already enforces as gross − commission. Restating it
        // here as `entryNetPremiumCents − feesOpenCents` made this the second author of that
        // subtraction, and on a refused package it silently evaluated to 0 − 0 = 0, turning a
        // missing opening price into a hold-vs-close EV shift of zero (§3.1/§3.2).
        Long immediateOpenNet = preview.price() == null ? null : preview.price().afterFeeNetCents();
        if (immediateOpenNet == null) {
            return PositionLifecycleAnalysis.ForwardEconomics.unavailable(
                    "The exact package price states no after-fee opening net.",
                    "Hold-vs-close substitutes the fresh opening cash for the closing cash; without a"
                            + " priced opening leg there is nothing to substitute.");
        }
        long substitution = Math.subtractExact(Math.negateExact(immediateOpenNet),
                close.price().afterFeeNetCents());
        Long market = shifted(freshEyes.marketEvAfterCostsCents(), substitution);
        Long realized = shifted(freshEyes.realizedVolEvAfterCostsCents(), substitution);
        Long low = shifted(freshEyes.realisticEvLowAfterCostsCents(), substitution);
        Long high = shifted(freshEyes.realisticEvHighAfterCostsCents(), substitution);
        boolean available = market != null || realized != null || low != null || high != null;
        if (!available) {
            return PositionLifecycleAnalysis.ForwardEconomics.unavailable(
                    "The fresh-eyes assessment has no forward EV model.",
                    "Hold-vs-close adjusts the fresh-eyes economics for today's closing cash flow.");
        }
        return new PositionLifecycleAnalysis.ForwardEconomics(true, market, realized, low, high,
                freshEyes.realisticEvMaterialityCents(), freshEyes.observedEvidence(),
                "For every existing economic mode: hold-vs-close EV = fresh-eyes EV − "
                        + "(fresh executable opening cash less opening fee) − "
                        + "(executable closing cash less closing fee). The future payoff/model is unchanged; "
                        + "bid/ask and fees are therefore represented exactly once.", null);
    }

    private static Long shifted(Long value, long delta) {
        return value == null ? null : Math.addExact(value, delta);
    }

    private static Long expectedShortfall(TradePreview preview) {
        var risk = preview.marketImpliedRisk();
        var probability = risk == null ? null : risk.probabilityMap();
        if (probability == null) {
            return null;
        }
        long pnl = probability.cvar95Cents();
        return pnl < 0 ? Math.negateExact(pnl) : 0L;
    }

    private static List<PositionLifecycleAnalysis.AssignmentLeg> assignmentLegs(
            TradeService.OpenRequest request, TradePreview preview) {
        List<PositionLifecycleAnalysis.AssignmentLeg> out = new ArrayList<>();
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
            out.add(new PositionLifecycleAnalysis.AssignmentLeg(leg.type(), leg.expiration(), strike,
                    shares, dollars, effective,
                    leg.type() == OptionType.PUT ? "BUY_SHARES" : "SELL_OR_CASH_SETTLE_SHARES",
                    "Exact short-leg geometry. The effective price uses only that leg's current fresh-eyes "
                            + "executable premium; tracked tax and campaign bases remain separate."));
        }
        return List.copyOf(out);
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

    /** Authority follows the actual current package-price result, never a separately repriced preview. */
    private static PositionDomain.PriceAuthority authority(PackagePrice price) {
        if (price == null || !price.priced()
                || price.valuationBasis() == PackagePrice.ValuationBasis.MODELED
                || price.valuationBasis() == PackagePrice.ValuationBasis.MID_MARKET) {
            return PositionDomain.PriceAuthority.MODELED;
        }
        String source = price.source() == null ? "" : price.source().toLowerCase(java.util.Locale.ROOT);
        return source.contains("broker") || source.contains("etrade")
                ? PositionDomain.PriceAuthority.BROKER_REPORTED
                : PositionDomain.PriceAuthority.OBSERVED;
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
                PositionDomain.PackageSource.TRACKED_STRUCTURE, PositionDomain.BookType.TRACKED,
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
        if (evaluation == null) {
            model.put("evaluation", "UNAVAILABLE");
            return PositionPackageFingerprint.entrySnapshotFingerprint(Json.write(model));
        }
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

}
