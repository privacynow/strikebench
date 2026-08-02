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
        EventService.EarningsProximity earningsProximity, // null only during pre-regime assembly
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

    /** Listed-option IV and annualization use calendar time. */
    public int calendarDaysToExpiry() {
        return Math.toIntExact(timeToExpiry.calendarDays());
    }

    /** Management urgency uses exchange trading sessions. */
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

}
