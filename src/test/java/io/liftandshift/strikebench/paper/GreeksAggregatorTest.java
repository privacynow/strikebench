package io.liftandshift.strikebench.paper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GreeksAggregatorTest {

    @Test
    void currentPackageGreeksUseExactSignsDeliverablesRatiosAndQuantity() {
        var longCall = new GreeksAggregator.LegExposure(false, 1, 100, 2, 3,
                0.50, 0.02, -0.04, 0.10);
        var shortPut = new GreeksAggregator.LegExposure(false, -1, 10, 1, 3,
                -0.30, 0.01, -0.02, 0.08);

        var result = GreeksAggregator.aggregate(List.of(longCall, shortPut), 17);

        assertThat(result.deltaShares()).isEqualTo(326.0);
        assertThat(result.gammaSharesPerDollar()).isEqualTo(11.7);
        assertThat(result.thetaCentsPerDay()).isEqualTo(-2340.0);
        assertThat(result.vegaCentsPerPoint()).isEqualTo(5760.0);
    }

    @Test
    void anyMissingOptionGreekMakesTheWholeReceiptUnavailable() {
        var missingGamma = new GreeksAggregator.LegExposure(false, 1, 100, 1, 1,
                0.50, null, -0.04, 0.10);

        assertThat(GreeksAggregator.aggregate(List.of(missingGamma), 0)).isNull();
    }

    @Test
    void stockHasKnownDeltaAndNoInventedOptionExposure() {
        var stock = new GreeksAggregator.LegExposure(true, 1, 1, 25, 1,
                0.12, 9.0, 8.0, 7.0);

        var result = GreeksAggregator.aggregate(List.of(stock), 0);

        assertThat(result.deltaShares()).isEqualTo(25.0);
        assertThat(result.gammaSharesPerDollar()).isZero();
        assertThat(result.thetaCentsPerDay()).isZero();
        assertThat(result.vegaCentsPerPoint()).isZero();
    }

    @Test
    void malformedDeliverableGeometryIsRejectedInsteadOfDefaulted() {
        assertThatThrownBy(() -> new GreeksAggregator.LegExposure(
                false, 1, 0, 1, 1, 0.5, 0.1, -0.2, 0.3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GreeksAggregator.LegExposure(
                false, 1, 100, 0, 1, 0.5, 0.1, -0.2, 0.3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedSnapshotGeometryCannotProducePackageGreeks() {
        Map<String, Object> valid = optionSnapshot();
        Map<String, Object> missingMultiplier = new LinkedHashMap<>(valid);
        missingMultiplier.remove("multiplier");
        Map<String, Object> fractionalRatio = new LinkedHashMap<>(valid);
        fractionalRatio.put("ratio", 1.5);
        Map<String, Object> missingAction = new LinkedHashMap<>(valid);
        missingAction.remove("action");

        assertThat(TradeService.packageGreeks(List.of(missingMultiplier), 1, 0)).isNull();
        assertThat(TradeService.packageGreeks(List.of(fractionalRatio), 1, 0)).isNull();
        assertThat(TradeService.packageGreeks(List.of(missingAction), 1, 0)).isNull();
        assertThat(TradeService.packageGreeks(List.of(valid), 0, 0)).isNull();
    }

    @Test
    void explicitStockLegAndHeldShareContextAreEachCountedExactlyOnce() {
        var explicitStock = new GreeksAggregator.LegExposure(true, 1, 1, 100, 1,
                null, null, null, null);
        var optionOnly = new GreeksAggregator.LegExposure(false, -1, 100, 1, 1,
                0.25, 0.01, -0.02, 0.03);

        var buyWrite = GreeksAggregator.aggregate(List.of(explicitStock, optionOnly), 0);
        var coveredByHeldShares = GreeksAggregator.aggregate(List.of(optionOnly), 100);

        assertThat(buyWrite.deltaShares()).isEqualTo(75.0);
        assertThat(coveredByHeldShares.deltaShares()).isEqualTo(75.0);
    }

    private static Map<String, Object> optionSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("action", "BUY");
        snapshot.put("type", "CALL");
        snapshot.put("multiplier", 100);
        snapshot.put("ratio", 1);
        snapshot.put("delta", 0.5);
        snapshot.put("gamma", 0.02);
        snapshot.put("theta", -0.04);
        snapshot.put("vega", 0.10);
        return snapshot;
    }
}
