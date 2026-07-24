package io.liftandshift.strikebench.backtest;

import io.liftandshift.strikebench.db.AnalysisContext;
import io.liftandshift.strikebench.market.Domain;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.ports.MarketDataProvider;
import io.liftandshift.strikebench.model.Candle;
import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.SymbolMatch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalReplayKernelTest {

    @Test void oneTimelineSortsProviderBarsAndNeverExposesFutureEvidence() {
        LocalDate warmup = LocalDate.parse("2026-01-02");
        LocalDate first = LocalDate.parse("2026-01-05");
        LocalDate second = LocalDate.parse("2026-01-06");
        MarketDataProvider provider = new MarketDataProvider() {
            @Override public String name() { return "unsorted-test"; }
            @Override public Set<Domain> domains() { return Set.of(Domain.CANDLES); }
            @Override public List<SymbolMatch> lookup(String query) { return List.of(); }
            @Override public Optional<Quote> quote(String symbol) { return Optional.empty(); }
            @Override public List<LocalDate> expirations(String symbol) { return List.of(); }
            @Override public Optional<OptionChain> chain(String symbol, LocalDate expiration) {
                return Optional.empty();
            }
            @Override public List<Candle> candles(String symbol, LocalDate from, LocalDate to) {
                return List.of(candle(second, "102"), candle(warmup, "99"), candle(first, "101"));
            }
        };
        HistoricalReplayKernel kernel = new HistoricalReplayKernel(
                new MarketDataService(List.of(provider), List.of(), List.of()), null);

        HistoricalReplayKernel.Window window = kernel.window(
                "TEST", first, second, 10, AnalysisContext.OBSERVED);
        List<HistoricalReplayKernel.ReplayDay> days = new ArrayList<>();
        kernel.forEachDay(window, days, (out, day) -> out.add(day));

        assertThat(window.all()).extracting(Candle::date)
                .containsExactly(warmup, first, second);
        assertThat(days).extracting(HistoricalReplayKernel.ReplayDay::index)
                .containsExactly(0, 1);
        assertThat(days.get(0).known()).extracting(Candle::date)
                .containsExactly(warmup, first);
        assertThat(days.get(1).known()).extracting(Candle::date)
                .containsExactly(warmup, first, second);
        assertThat(days).allSatisfy(day -> assertThat(day.known())
                .allMatch(candle -> !candle.date().isAfter(day.candle().date())));
    }

    private static Candle candle(LocalDate date, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Candle(date, price, price, price, price, 1_000, false);
    }
}
