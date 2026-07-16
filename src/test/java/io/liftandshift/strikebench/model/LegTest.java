package io.liftandshift.strikebench.model;

import io.liftandshift.strikebench.paper.TradeRecord;
import io.liftandshift.strikebench.recommend.LegView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegTest {

    @Test void currentLegJsonRequiresAnExplicitMultiplier() {
        assertThatThrownBy(() -> TradeRecord.legsFromJson("[{\"action\":\"BUY\",\"type\":\"CALL\","
                + "\"strike\":100,\"expiration\":\"2026-08-21\",\"ratio\":1,\"entryPrice\":2.5}]"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("multiplier");
    }

    @Test void currentApiMultiplierMustBePositive() {
        assertThatThrownBy(() -> new LegView("BUY", "CALL", "100", "2026-08-21", 1, "2.5", 0, "OPEN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier must be 1..10,000");
    }

    @Test void anExplicitDomainMultiplierOfZeroIsNotRewrittenAsAStandardContract() {
        assertThatThrownBy(() -> new Leg(LegAction.BUY, OptionType.CALL, BigDecimal.valueOf(100),
                LocalDate.of(2026, 8, 21), 1, BigDecimal.valueOf(2.5), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier must be 1..10,000");
    }

    @Test void stockWireLegsPreserveExplicitLotAndExactShareDeliverables() {
        Leg standardLot = new LegView("BUY", "STOCK", null, null, 1, "100", 100, "OPEN").toLeg();
        Leg exactShares = new LegView("BUY", "STOCK", null, null, 37, "100", 1, "OPEN").toLeg();

        assertThat(standardLot.multiplier()).isEqualTo(100);
        assertThat(standardLot.ratio()).isEqualTo(1);
        assertThat(exactShares.multiplier()).isEqualTo(1);
        assertThat(exactShares.ratio()).isEqualTo(37);
    }

    @Test void internalCallersCannotBypassTheMultiplierCeiling() {
        assertThatThrownBy(() -> Leg.option(LegAction.BUY, OptionType.CALL,
                BigDecimal.valueOf(100), LocalDate.of(2026, 8, 21), 1,
                BigDecimal.valueOf(2.5), 10_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier must be 1..10,000");
    }
}
