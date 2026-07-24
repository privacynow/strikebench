package io.liftandshift.strikebench.pricing;

import io.liftandshift.strikebench.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalVolTest {

    @Test
    void thirtySessionVolRequiresTheSharedTwentyObservationFloor() {
        List<Candle> bars = bars(20);

        assertThat(HistoricalVol.annualized(bars.subList(0, 19), 30)).isNaN();
        assertThat(HistoricalVol.annualized(bars, 30)).isFinite().isPositive();
    }

    private static List<Candle> bars(int count) {
        List<Candle> out = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-06-01");
        for (int i = 0; i < count; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i + (i % 3) * 0.2);
            out.add(new Candle(start.plusDays(i), close, close, close, close, 1_000, true));
        }
        return out;
    }
}
