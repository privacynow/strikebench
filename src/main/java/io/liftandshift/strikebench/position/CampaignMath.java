package io.liftandshift.strikebench.position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Exact-cent formulas shared by campaigns, receipts, and their exports. */
public final class CampaignMath {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private CampaignMath() {}

    public static long campaignNetCredit(List<Long> signedOptionCashCents) {
        long total = 0;
        if (signedOptionCashCents != null) for (Long cents : signedOptionCashCents) {
            if (cents != null) total = Math.addExact(total, cents);
        }
        return total;
    }

    public static long campaignAdjustedEconomicBasisPerShareCents(long shareCashPaidCents,
                                                                   long netOptionCashCents,
                                                                   long dividendsReceivedCents,
                                                                   long feesCents,
                                                                   long sharesCurrentlyHeld) {
        if (sharesCurrentlyHeld <= 0) throw new IllegalArgumentException("shares currently held must be positive");
        long numerator = Math.addExact(Math.subtractExact(
                Math.subtractExact(shareCashPaidCents, netOptionCashCents), dividendsReceivedCents), feesCents);
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(sharesCurrentlyHeld), 0,
                RoundingMode.HALF_UP).longValueExact();
    }

    public static YieldComparison realizedVsHeadlineYield(long periodPremiumCents,
                                                           long periodCommittedCapitalCents,
                                                           long campaignNetPnlCents,
                                                           long peakCommittedCapitalCents,
                                                           int periodDays, int campaignDays) {
        return new YieldComparison(ratePct(periodPremiumCents, periodCommittedCapitalCents),
                annualizedPct(periodPremiumCents, periodCommittedCapitalCents, periodDays),
                ratePct(campaignNetPnlCents, peakCommittedCapitalCents),
                annualizedPct(campaignNetPnlCents, peakCommittedCapitalCents, campaignDays));
    }

    public static long cashBenchmark(List<DatedCashFlow> externalFlows, LocalDate endDate,
                                     int annualRateBps) {
        if (endDate == null) throw new IllegalArgumentException("benchmark end date is required");
        BigDecimal daily = BigDecimal.ONE.add(BigDecimal.valueOf(annualRateBps)
                .divide(BigDecimal.valueOf(10_000), 16, RoundingMode.HALF_EVEN)
                .divide(DAYS_PER_YEAR, 16, RoundingMode.HALF_EVEN));
        BigDecimal value = BigDecimal.ZERO;
        if (externalFlows != null) for (DatedCashFlow flow : externalFlows) {
            if (flow == null || flow.date() == null || flow.date().isAfter(endDate)) continue;
            long days = ChronoUnit.DAYS.between(flow.date(), endDate);
            value = value.add(BigDecimal.valueOf(flow.signedCents()).multiply(daily.pow(Math.toIntExact(days))));
        }
        return value.setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /**
     * Matches each external flow into fractional benchmark shares at that date's close. Dividends
     * are either retained as cash or reinvested at the ex-date close, as named by the caller.
     */
    public static long buyAndHoldBenchmark(List<DatedCashFlow> externalFlows, List<DatedPrice> prices,
                                           List<BenchmarkDividend> dividends, LocalDate endDate,
                                           DividendTreatment dividendTreatment) {
        if (prices == null || prices.isEmpty() || endDate == null || dividendTreatment == null) {
            throw new IllegalArgumentException("benchmark prices, end date, and dividend treatment are required");
        }
        List<LocalDate> dates = new ArrayList<>();
        if (externalFlows != null) externalFlows.stream().filter(f -> f != null && !f.date().isAfter(endDate))
                .map(DatedCashFlow::date).forEach(dates::add);
        if (dividends != null) dividends.stream().filter(d -> d != null && !d.exDate().isAfter(endDate))
                .map(BenchmarkDividend::exDate).forEach(dates::add);
        dates.add(endDate);
        dates = new ArrayList<>(new LinkedHashSet<>(dates.stream().sorted().toList()));

        BigDecimal shares = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        for (LocalDate date : dates) {
            long price = priceOnOrBefore(prices, date);
            if (externalFlows != null) for (DatedCashFlow flow : externalFlows) {
                if (flow != null && date.equals(flow.date())) {
                    shares = shares.add(BigDecimal.valueOf(flow.signedCents())
                            .divide(BigDecimal.valueOf(price), 18, RoundingMode.HALF_EVEN));
                    if (shares.signum() < 0) throw new IllegalArgumentException("benchmark withdrawals exceed accumulated shares");
                }
            }
            if (dividends != null) for (BenchmarkDividend dividend : dividends) {
                if (dividend != null && date.equals(dividend.exDate())) {
                    BigDecimal dividendCash = shares.multiply(BigDecimal.valueOf(dividend.perShareCents()));
                    if (dividendTreatment == DividendTreatment.REINVEST_AT_EX_DATE_CLOSE) {
                        shares = shares.add(dividendCash.divide(BigDecimal.valueOf(price), 18, RoundingMode.HALF_EVEN));
                    } else {
                        cash = cash.add(dividendCash);
                    }
                }
            }
        }
        BigDecimal terminal = shares.multiply(BigDecimal.valueOf(priceOnOrBefore(prices, endDate))).add(cash);
        return terminal.setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    public static long attributedDividends(List<HoldingWindow> holdings, List<Dividend> dividends) {
        long total = 0;
        if (holdings == null || dividends == null) return 0;
        for (Dividend dividend : dividends) for (HoldingWindow holding : holdings) {
            if (dividend != null && holding != null && holding.symbol().equalsIgnoreCase(dividend.symbol())
                    && !dividend.exDate().isBefore(holding.fromInclusive())
                    && (holding.toExclusive() == null || dividend.exDate().isBefore(holding.toExclusive()))) {
                total = Math.addExact(total, Math.multiplyExact(holding.quantity(), dividend.perShareCents()));
            }
        }
        return total;
    }

    public static long explicitlyTaggedInterest(String campaignId, List<TaggedInterest> entries) {
        long total = 0;
        if (campaignId == null || entries == null) return 0;
        for (TaggedInterest entry : entries) if (entry != null && campaignId.equals(entry.campaignId())) {
            total = Math.addExact(total, entry.signedCents());
        }
        return total;
    }

    public static long scenarioOutcomeCents(long positionValueCents, long cumulativeCampaignCashCents) {
        return Math.addExact(positionValueCents, cumulativeCampaignCashCents);
    }

    public static long churnRoundTripCostCents(long exitPricePerShareCents, long reentryPricePerShareCents,
                                               long shares, long feesCents) {
        if (shares < 0 || feesCents < 0) throw new IllegalArgumentException("shares and fees cannot be negative");
        return Math.addExact(Math.multiplyExact(Math.subtractExact(reentryPricePerShareCents,
                exitPricePerShareCents), shares), feesCents);
    }

    /** Rounds user-entered exact leg amounts while preserving the exact package total. */
    public static List<Long> largestRemainderCents(List<BigDecimal> exactLegCents, long packageTotalCents) {
        if (exactLegCents == null || exactLegCents.isEmpty()) {
            if (packageTotalCents == 0) return List.of();
            throw new IllegalArgumentException("leg amounts are required");
        }
        List<Long> rounded = new ArrayList<>(exactLegCents.size());
        List<Remainder> remainders = new ArrayList<>(exactLegCents.size());
        long floorTotal = 0;
        for (int i = 0; i < exactLegCents.size(); i++) {
            BigDecimal exact = exactLegCents.get(i);
            if (exact == null) throw new IllegalArgumentException("leg amount " + (i + 1) + " is missing");
            long floor = exact.setScale(0, RoundingMode.FLOOR).longValueExact();
            floorTotal = Math.addExact(floorTotal, floor);
            rounded.add(floor);
            remainders.add(new Remainder(i, exact.subtract(BigDecimal.valueOf(floor))));
        }
        long units = Math.subtractExact(packageTotalCents, floorTotal);
        if (units < 0 || units > rounded.size()) {
            throw new IllegalArgumentException("entered legs do not reconcile to the package total within cent rounding");
        }
        remainders.sort(Comparator.comparing(Remainder::fraction).reversed().thenComparingInt(Remainder::index));
        for (int i = 0; i < units; i++) {
            int index = remainders.get(i).index();
            rounded.set(index, Math.addExact(rounded.get(index), 1));
        }
        if (!reconciles(rounded, packageTotalCents)) throw new IllegalStateException("package reconciliation failed");
        return List.copyOf(rounded);
    }

    public static boolean reconciles(List<Long> legCents, long packageTotalCents) {
        long total = 0;
        if (legCents != null) for (Long cents : legCents) total = Math.addExact(total, cents == null ? 0 : cents);
        return total == packageTotalCents;
    }

    private static BigDecimal ratePct(long numerator, long denominator) {
        if (denominator <= 0) throw new IllegalArgumentException("committed capital must be positive");
        return BigDecimal.valueOf(numerator).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal annualizedPct(long numerator, long denominator, int days) {
        if (days <= 0) throw new IllegalArgumentException("period days must be positive");
        return ratePct(numerator, denominator).multiply(DAYS_PER_YEAR)
                .divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_EVEN);
    }

    public record YieldComparison(BigDecimal headlinePeriodPct, BigDecimal headlineAnnualizedPct,
                                  BigDecimal realizedPeriodPct, BigDecimal realizedAnnualizedPct) {}
    public record DatedCashFlow(LocalDate date, long signedCents) {}
    public record DatedPrice(LocalDate date, long closePerShareCents) {
        public DatedPrice {
            if (date == null || closePerShareCents <= 0) throw new IllegalArgumentException("benchmark price must be positive");
        }
    }
    public record BenchmarkDividend(LocalDate exDate, long perShareCents) {
        public BenchmarkDividend {
            if (exDate == null || perShareCents < 0) throw new IllegalArgumentException("benchmark dividend cannot be negative");
        }
    }
    public enum DividendTreatment { CASH, REINVEST_AT_EX_DATE_CLOSE }
    public record HoldingWindow(String symbol, LocalDate fromInclusive, LocalDate toExclusive, long quantity) {}
    public record Dividend(String symbol, LocalDate exDate, long perShareCents) {}
    public record TaggedInterest(String campaignId, long signedCents) {}
    private record Remainder(int index, BigDecimal fraction) {}

    private static long priceOnOrBefore(List<DatedPrice> prices, LocalDate date) {
        return prices.stream().filter(p -> p != null && !p.date().isAfter(date))
                .max(Comparator.comparing(DatedPrice::date))
                .orElseThrow(() -> new IllegalArgumentException("no benchmark price is available on or before " + date))
                .closePerShareCents();
    }
}
