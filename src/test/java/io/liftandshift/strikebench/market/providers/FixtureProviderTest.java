package io.liftandshift.strikebench.market.providers;

import io.liftandshift.strikebench.model.OptionChain;
import io.liftandshift.strikebench.model.OptionQuote;
import io.liftandshift.strikebench.model.OptionType;
import io.liftandshift.strikebench.model.Quote;
import io.liftandshift.strikebench.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureProviderTest {

    // A fixed Wednesday so 0DTE behavior is deterministic
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);

    private final FixtureProvider provider = new FixtureProvider(CLOCK);

    @Test
    void quotesForKnownSymbols() {
        Quote aapl = provider.quote("aapl").orElseThrow();
        assertThat(aapl.last()).isEqualByComparingTo(new BigDecimal("255.30"));
        assertThat(aapl.optionable()).isTrue();
        assertThat(aapl.symbol()).isEqualTo("AAPL");

        Quote vtsax = provider.quote("VTSAX").orElseThrow();
        assertThat(vtsax.optionable()).isFalse();

        assertThat(provider.quote("NOPE")).isEmpty();
    }

    @Test
    void vtsaxHasNoOptions() {
        assertThat(provider.expirations("VTSAX")).isEmpty();
        assertThat(provider.chain("VTSAX", TODAY.plusDays(10))).isEmpty();
    }

    @Test
    void spyGetsZeroDteButAaplDoesNot() {
        List<LocalDate> spy = provider.expirations("SPY");
        assertThat(spy).hasSize(8).first().isEqualTo(TODAY); // Wednesday 0DTE
        List<LocalDate> aapl = provider.expirations("AAPL");
        assertThat(aapl).hasSize(8);
        assertThat(aapl.getFirst()).isAfter(TODAY);
        assertThat(aapl).allSatisfy(d -> assertThat(d.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.FRIDAY));
        assertThat(spy).isSorted();
    }

    @Test
    void chainIsDeterministicAcrossInstances() {
        LocalDate exp = provider.expirations("AAPL").getFirst();
        OptionChain a = provider.chain("AAPL", exp).orElseThrow();
        OptionChain b = new FixtureProvider(CLOCK).chain("AAPL", exp).orElseThrow();
        assertThat(a.calls()).hasSameSizeAs(b.calls());
        for (int i = 0; i < a.calls().size(); i++) {
            OptionQuote qa = a.calls().get(i), qb = b.calls().get(i);
            assertThat(qa.bid()).isEqualByComparingTo(qb.bid());
            assertThat(qa.ask()).isEqualByComparingTo(qb.ask());
            assertThat(qa.volume()).isEqualTo(qb.volume());
            assertThat(qa.iv()).isEqualTo(qb.iv());
        }
    }

    @Test
    void chainHasSaneQuotesAndSmile() {
        LocalDate exp = provider.expirations("AAPL").getFirst();
        OptionChain chain = provider.chain("AAPL", exp).orElseThrow();
        assertThat(chain.calls()).hasSize(21);
        assertThat(chain.puts()).hasSize(21);

        double spot = chain.underlyingPrice().doubleValue();
        OptionQuote atmPut = chain.find(OptionType.PUT, nearestStrike(chain, spot)).orElseThrow();
        OptionQuote otmPut = chain.puts().getFirst(); // deepest downside strike
        assertThat(otmPut.strike().doubleValue()).isLessThan(atmPut.strike().doubleValue());
        assertThat(otmPut.iv()).as("put skew: downside IV richer").isGreaterThan(atmPut.iv());

        for (OptionQuote q : chain.calls()) {
            assertThat(q.bid()).isLessThanOrEqualTo(q.ask());
            assertThat(q.bid().signum()).isGreaterThanOrEqualTo(0);
            assertThat(q.openInterest()).isPositive();
            assertThat(q.occSymbol()).hasSize(21); // 6 sym + 6 date + 1 type + 8 strike
        }

        // Deep ITM call worth at least intrinsic
        OptionQuote deepItm = chain.calls().getFirst();
        double intrinsic = spot - deepItm.strike().doubleValue();
        assertThat(deepItm.mid().doubleValue()).isGreaterThan(intrinsic * 0.98);
    }

    @Test
    void candlesEndAtSpotAndAreDeterministic() {
        List<Candle> candles = provider.candles("AAPL", TODAY.minusYears(1), TODAY);
        assertThat(candles).isNotEmpty();
        assertThat(candles.getLast().date()).isEqualTo(TODAY); // Wednesday, a trading day
        assertThat(candles.getLast().close()).isEqualByComparingTo(new BigDecimal("255.30"));
        assertThat(candles).allSatisfy(c -> {
            assertThat(c.high()).isGreaterThanOrEqualTo(c.low());
            assertThat(c.close().signum()).isPositive();
        });
        List<Candle> again = new FixtureProvider(CLOCK).candles("AAPL", TODAY.minusYears(1), TODAY);
        assertThat(again).isEqualTo(candles);
    }

    @Test
    void historicalChainUsesHistoricalSpot() {
        LocalDate asOf = TODAY.minusDays(90);
        List<LocalDate> exps = provider.historicalExpirations("AAPL", asOf);
        assertThat(exps).isNotEmpty();
        OptionChain chain = provider.historicalChain("AAPL", asOf, exps.getFirst()).orElseThrow();
        assertThat(chain.freshness()).isEqualTo(io.liftandshift.strikebench.model.Freshness.MODELED);
        // Historical spot comes from the candle walk, not today's price
        List<Candle> candles = provider.candles("AAPL", asOf.minusDays(5), asOf);
        assertThat(chain.underlyingPrice()).isEqualByComparingTo(candles.getLast().close());
    }

    @Test
    void chainOnlyExistsForListedExpirations() {
        assertThat(provider.chain("AAPL", TODAY.plusDays(1))).isEmpty();  // tomorrow: unlisted
        assertThat(provider.chain("AAPL", TODAY.minusDays(30))).isEmpty(); // past
        assertThat(provider.chain("AAPL", provider.expirations("AAPL").getFirst())).isPresent();
    }

    @Test
    void lookupMatchesSymbolAndName() {
        assertThat(provider.lookup("AA")).extracting(m -> m.symbol()).contains("AAPL");
        assertThat(provider.lookup("vanguard")).extracting(m -> m.symbol()).contains("VTSAX");
        assertThat(provider.lookup("zzz")).isEmpty();
    }

    private static BigDecimal nearestStrike(OptionChain chain, double spot) {
        return chain.strikes().stream()
                .min(java.util.Comparator.comparingDouble(k -> Math.abs(k.doubleValue() - spot)))
                .orElseThrow();
    }
}
