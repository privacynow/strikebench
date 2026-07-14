package io.liftandshift.strikebench.market;

import io.liftandshift.strikebench.model.LegAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutablePriceTest {
    private static BigDecimal px(String value) { return new BigDecimal(value); }

    @Test void buysPayAskAndSellsReceiveBid() {
        assertThat(ExecutablePrice.forAction(px("1.20"), px("1.30"), LegAction.BUY))
                .isEqualByComparingTo("1.30");
        assertThat(ExecutablePrice.forAction(px("1.20"), px("1.30"), LegAction.SELL))
                .isEqualByComparingTo("1.20");
        assertThat(ExecutablePrice.midpoint(px("1.20"), px("1.30")))
                .isEqualByComparingTo("1.25");
    }

    @Test void crossedBooksAreNeverExecutableOrMarkable() {
        assertThat(ExecutablePrice.forAction(px("1.40"), px("1.30"), LegAction.BUY)).isNull();
        assertThat(ExecutablePrice.forAction(px("1.40"), px("1.30"), LegAction.SELL)).isNull();
        assertThat(ExecutablePrice.midpoint(px("1.40"), px("1.30"))).isNull();
    }

    @Test void onlyTheAvailablePositiveSideCanExecute() {
        assertThat(ExecutablePrice.forAction(null, px("1.30"), LegAction.BUY))
                .isEqualByComparingTo("1.30");
        assertThat(ExecutablePrice.forAction(px("1.20"), null, LegAction.SELL))
                .isEqualByComparingTo("1.20");
        assertThat(ExecutablePrice.forAction(null, px("1.30"), LegAction.SELL)).isNull();
        assertThat(ExecutablePrice.forAction(px("1.20"), null, LegAction.BUY)).isNull();
        assertThat(ExecutablePrice.forAction(BigDecimal.ZERO, px("1.30"), LegAction.SELL)).isNull();
    }
}
