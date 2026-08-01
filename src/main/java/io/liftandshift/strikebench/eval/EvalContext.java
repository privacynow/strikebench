package io.liftandshift.strikebench.eval;

import io.liftandshift.strikebench.model.DataEvidence;
import io.liftandshift.strikebench.market.OptionTime;
import io.liftandshift.strikebench.market.EventService;

import java.time.LocalDate;
import java.util.List;

/**
 * The live market context an evaluation is computed against, assembled once by
 * {@code StrategyEvaluator} and shared by every producer so they agree on the same snapshot.
 */
public record EvalContext(
        String symbol,
        long underlyingCents,     // current underlying price, cents
        LocalDate asOfDate,       // mode clock date; simulated worlds never borrow wall-clock DTE
        OptionTime.Measure timeToExpiry,
        Double atmIv,             // at-the-money implied vol from the chain, null if none
        Double realizedVol30,     // 30-day realized (annualized), null if no candles
        List<Double> ivHistory,   // trailing ATM-IV observations for rank/percentile (may be empty)
        long buyingPowerCents,    // for the capital gate
        boolean marketOpen,
        double riskFreeRate,      // annualized r used by the shared risk-neutral approximation
        DataEvidence rateEvidence,
        PortfolioExposureContext portfolioExposure,
        DeclaredObjective declared,   // what the user SAID this is for; null = undeclared
        RegimeSnapshot regime,        // the mode's trailing regime; null = not computed
        List<Double> trailingCloses,  // chronological mode closes for history-fit; empty = none
        DataEvidence historyEvidence, // exact provenance of the CandleSeries behind realized vol/history
        EventService.EarningsProximity earningsProximity, // null only for compatibility/pure fixtures
        Long lossAppetiteCents
) {
    public EvalContext {
        if (asOfDate == null) throw new IllegalArgumentException("evaluation date is required");
        if (underlyingCents <= 0) throw new IllegalArgumentException("evaluation underlying price is required");
        if (timeToExpiry == null) throw new IllegalArgumentException("evaluation option time is required");
        if (timeToExpiry.asOf() != null
                && !LocalDate.ofInstant(timeToExpiry.asOf(),
                        io.liftandshift.strikebench.market.MarketHours.EASTERN).equals(asOfDate)) {
            throw new IllegalArgumentException("evaluation date must match the option-time mode instant");
        }
        ivHistory = ivHistory == null ? List.of() : List.copyOf(ivHistory);
        rateEvidence = rateEvidence == null ? DataEvidence.missing("rate input") : rateEvidence;
        trailingCloses = trailingCloses == null ? List.of() : List.copyOf(trailingCloses);
        historyEvidence = historyEvidence == null
                ? DataEvidence.missing("daily history provenance") : historyEvidence;
    }

    /** Compatibility shape for callers that supplied event evidence before account fit existed. */
    public EvalContext(String symbol, long underlyingCents, LocalDate asOfDate,
                       OptionTime.Measure timeToExpiry, Double atmIv, Double realizedVol30,
                       List<Double> ivHistory, long buyingPowerCents, boolean marketOpen,
                       double riskFreeRate, DataEvidence rateEvidence,
                       PortfolioExposureContext portfolioExposure, DeclaredObjective declared,
                       RegimeSnapshot regime, List<Double> trailingCloses,
                       DataEvidence historyEvidence,
                       EventService.EarningsProximity earningsProximity) {
        this(symbol, underlyingCents, asOfDate, timeToExpiry, atmIv, realizedVol30,
                ivHistory, buyingPowerCents, marketOpen, riskFreeRate, rateEvidence,
                portfolioExposure, declared, regime, trailingCloses, historyEvidence,
                earningsProximity, null);
    }

    /** The PRE-REGIME bootstrap: the volatility profiler needs a context before the regime and
     *  event evidence that depend on it exist. Live by design, not a back-compat shim. */
    public EvalContext(String symbol, long underlyingCents, LocalDate asOfDate,
                       OptionTime.Measure timeToExpiry, Double atmIv, Double realizedVol30,
                       List<Double> ivHistory, long buyingPowerCents, boolean marketOpen,
                       double riskFreeRate, DataEvidence rateEvidence,
                       PortfolioExposureContext portfolioExposure, DeclaredObjective declared,
                       RegimeSnapshot regime, List<Double> trailingCloses,
                       DataEvidence historyEvidence) {
        this(symbol, underlyingCents, asOfDate, timeToExpiry, atmIv, realizedVol30, ivHistory,
                buyingPowerCents, marketOpen, riskFreeRate, rateEvidence, portfolioExposure,
                declared, regime, trailingCloses, historyEvidence, null, null);
    }

    /** Listed-option IV and annualization use calendar time. */
    public int calendarDaysToExpiry() {
        return Math.toIntExact(timeToExpiry.calendarDays());
    }

    /** Management urgency uses exchange trading sessions; -1 means a legacy caller did not supply it. */
    public int tradingSessionsToExpiry() {
        return timeToExpiry.sessions();
    }

    /** Exact chain-IV year fraction published by the normalized option-time result. */
    public Double yearsToExpiry() {
        return timeToExpiry.years();
    }

    /** True only when the typed result supplies a positive model fraction. */
    public boolean hasModelTime() {
        return timeToExpiry.hasModelTime();
    }

    /**
     * Compatibility accessor for older pure-evaluator tests and persisted shapes. New calculations
     * must choose calendar days, trading sessions, or years explicitly.
     */
    public int daysToExpiry() {
        return calendarDaysToExpiry();
    }

    /**
     * Compatibility shape for callers that predate history provenance. A realized-volatility
     * number alone cannot prove where its bars came from, so the evidence stays explicitly
     * missing rather than being inferred from option pricing or the presence of a value.
     */
    public EvalContext(String symbol, long underlyingCents, LocalDate asOfDate, int daysToExpiry,
                       Double atmIv, Double realizedVol30, List<Double> ivHistory,
                       long buyingPowerCents, boolean marketOpen, double riskFreeRate,
                       DataEvidence rateEvidence,
                       PortfolioExposureContext portfolioExposure, DeclaredObjective declared,
                       RegimeSnapshot regime, List<Double> trailingCloses) {
        this(symbol, underlyingCents, asOfDate, OptionTime.ofCalendarDays(daysToExpiry),
                atmIv, realizedVol30, ivHistory,
                buyingPowerCents, marketOpen, riskFreeRate,
                rateEvidence, portfolioExposure, declared, regime, trailingCloses,
                DataEvidence.missing("daily history provenance not supplied"), null, null);
    }

    /**
     * Compatibility shape for pure evaluator fixtures that record calendar days and explicit
     * history evidence. Trading sessions remain unavailable rather than being inferred.
     */
    public EvalContext(String symbol, long underlyingCents, LocalDate asOfDate, int daysToExpiry,
                       Double atmIv, Double realizedVol30, List<Double> ivHistory,
                       long buyingPowerCents, boolean marketOpen, double riskFreeRate,
                       DataEvidence rateEvidence,
                       PortfolioExposureContext portfolioExposure, DeclaredObjective declared,
                       RegimeSnapshot regime, List<Double> trailingCloses,
                       DataEvidence historyEvidence) {
        this(symbol, underlyingCents, asOfDate, OptionTime.ofCalendarDays(daysToExpiry),
                atmIv, realizedVol30, ivHistory, buyingPowerCents, marketOpen, riskFreeRate,
                rateEvidence, portfolioExposure, declared, regime, trailingCloses, historyEvidence,
                null, null);
    }

    /** Undeclared-context constructor: existing callers keep their shape. */
    public EvalContext(String symbol, long underlyingCents, LocalDate asOfDate, int daysToExpiry,
                       Double atmIv, Double realizedVol30, List<Double> ivHistory,
                       long buyingPowerCents, boolean marketOpen, double riskFreeRate,
                       DataEvidence rateEvidence,
                       PortfolioExposureContext portfolioExposure) {
        this(symbol, underlyingCents, asOfDate, OptionTime.ofCalendarDays(daysToExpiry),
                atmIv, realizedVol30, ivHistory,
                buyingPowerCents, marketOpen, riskFreeRate,
                rateEvidence, portfolioExposure, null, null, List.of(),
                DataEvidence.missing("daily history provenance not supplied"), null, null);
    }

    /** Declared-but-regimeless constructor: pre-regime callers keep their shape. */
    public EvalContext(String symbol, long underlyingCents, LocalDate asOfDate, int daysToExpiry,
                       Double atmIv, Double realizedVol30, List<Double> ivHistory,
                       long buyingPowerCents, boolean marketOpen, double riskFreeRate,
                       DataEvidence rateEvidence,
                       PortfolioExposureContext portfolioExposure, DeclaredObjective declared) {
        this(symbol, underlyingCents, asOfDate, OptionTime.ofCalendarDays(daysToExpiry),
                atmIv, realizedVol30, ivHistory,
                buyingPowerCents, marketOpen, riskFreeRate,
                rateEvidence, portfolioExposure, declared, null, List.of(),
                DataEvidence.missing("daily history provenance not supplied"), null, null);
    }
}
