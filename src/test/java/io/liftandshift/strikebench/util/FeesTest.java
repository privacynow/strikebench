package io.liftandshift.strikebench.util;

import io.liftandshift.strikebench.model.Leg;
import io.liftandshift.strikebench.model.LegAction;
import io.liftandshift.strikebench.model.OptionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeesTest {

    @Test
    void roundTripDoublesContractAndOrderFeesForOpenAndClose() {
        // 2 contracts × 65c × 2 (open+close) + 0 order fee × 2 = 260c
        assertThat(Fees.roundTripCents(2, 65, 0)).isEqualTo(260);
        // add a 100c per-order fee, charged twice
        assertThat(Fees.roundTripCents(2, 65, 100)).isEqualTo(260 + 200);
    }

    @Test
    void zeroOptionContractsCarryNoOptionCommission() {
        assertThat(Fees.roundTripCents(0, 65, 100)).isZero();
        assertThat(Fees.roundTripCents(-3, 65, 100)).isZero();
    }

    @Test
    void negativeConfiguredFeesAreClampedToZero() {
        assertThat(Fees.roundTripCents(4, -65, -100)).isZero();
    }

    @Test
    void openingChargesContractsPlusOneOrderFee() {
        // one-way = contracts × per-contract + per-order (NOT doubled)
        assertThat(Fees.openingCents(2, 65, 100)).isEqualTo(2 * 65 + 100);
        assertThat(Fees.roundTripCents(2, 65, 100)).isEqualTo(2 * Fees.openingCents(2, 65, 100));
    }

    @Test
    void stockOnlyOrderPaysNoFlatOrderFee() {
        // The unified policy: a package with no option contracts carries no commission at all —
        // TradeService/Backtester previously leaked the flat per-order fee onto stock-only orders.
        assertThat(Fees.openingCents(0, 65, 100)).isZero();
    }

    @Test
    void optionContractsCountsRatioTimesQtyOverOptionLegsOnly() {
        LocalDate exp = LocalDate.of(2026, 1, 16);
        List<Leg> pkg = List.of(
                Leg.stock(LegAction.BUY, 1, BigDecimal.valueOf(100)),               // stock excluded
                Leg.option(LegAction.SELL, OptionType.CALL, BigDecimal.valueOf(105), exp, 1, BigDecimal.ZERO),
                Leg.option(LegAction.BUY, OptionType.CALL, BigDecimal.valueOf(110), exp, 2, BigDecimal.ZERO));
        // (1 + 2) option ratios × qty 3 = 9 contracts; the 1 stock lot contributes nothing.
        assertThat(Fees.optionContracts(pkg, 3)).isEqualTo(9);
    }
}
