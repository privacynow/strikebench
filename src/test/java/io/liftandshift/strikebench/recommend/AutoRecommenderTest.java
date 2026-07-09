package io.liftandshift.strikebench.recommend;

import io.liftandshift.strikebench.config.AppConfig;
import io.liftandshift.strikebench.market.MarketDataService;
import io.liftandshift.strikebench.market.providers.FixtureProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AutoRecommenderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T15:30:00Z"), ZoneId.of("America/New_York"));
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);
    private static final long BP = 10_000_000L;

    private AutoRecommender auto;

    @BeforeEach
    void setUp() {
        FixtureProvider fixture = new FixtureProvider(CLOCK);
        MarketDataService market = new MarketDataService(List.of(fixture), List.of(fixture), List.of(fixture));
        AppConfig cfg = new AppConfig(Map.of("FIXTURES_ONLY", "true"));
        auto = new AutoRecommender(new SignalEngine(market, CLOCK), new RecommendationEngine(market, CLOCK), cfg, CLOCK);
    }

    private static AutoRecommender.AutoRequest req(List<String> horizons, Long targetProfit, Boolean allow0dte) {
        return new AutoRecommender.AutoRequest(null, horizons, 3, targetProfit, null, null, null, "balanced", allow0dte);
    }

    @Test
    void scansUniversePicksOptionableSymbolsWithEvidence() {
        AutoRecommender.AutoResult result = auto.run(req(null, null, false), BP);

        assertThat(result.picks()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        for (AutoRecommender.Pick pick : result.picks()) {
            assertThat(pick.signals().optionable()).isTrue();
            assertThat(pick.signals().thesis()).isIn("BULLISH", "BEARISH", "NEUTRAL", "VOLATILE");
            assertThat(pick.signals().rationale()).isNotEmpty();
            assertThat(pick.opportunityScore()).isBetween(0.0, 1.0);
            // default horizons without 0DTE opt-in
            assertThat(pick.horizons()).extracting(AutoRecommender.HorizonIdeas::horizon)
                    .containsExactly("week", "month");
            for (AutoRecommender.HorizonIdeas h : pick.horizons()) {
                assertThat(h.candidates()).hasSizeLessThanOrEqualTo(2);
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(sc.candidate().maxLossCents()).isPositive();
                    io.liftandshift.strikebench.strategy.StrategyFamily family =
                            io.liftandshift.strikebench.strategy.StrategyFamily.valueOf(sc.candidate().strategy());
                    assertThat(family.blockedByDefault()).isFalse();
                }
            }
        }
        assertThat(result.disclaimer()).containsIgnoringCase("not predictions");
        // VTSAX is in the fixture universe and must be skipped with a reason
        assertThat(result.skipped()).isEmpty(); // fixture universe has only optionable symbols
    }

    @Test
    void explicitUniverseSkipsNonOptionableWithReason() {
        AutoRecommender.AutoRequest request = new AutoRecommender.AutoRequest(
                List.of("AAPL", "VTSAX", "ZZZZ"), null, 3, null, null, null, null, "balanced", false);
        AutoRecommender.AutoResult result = auto.run(request, BP);
        assertThat(result.picks()).extracting(AutoRecommender.Pick::symbol).containsExactly("AAPL");
        assertThat(result.skipped()).anySatisfy(s -> assertThat(s).contains("VTSAX").contains("no listed options"));
        assertThat(result.skipped()).anySatisfy(s -> assertThat(s).contains("ZZZZ").contains("no market data"));
    }

    @Test
    void zeroDteOnlyWhenAllowedAndOnlySameDayExpiries() {
        AutoRecommender.AutoResult without = auto.run(req(List.of("0DTE", "week"), null, false), BP);
        for (AutoRecommender.Pick p : without.picks()) {
            assertThat(p.horizons()).extracting(AutoRecommender.HorizonIdeas::horizon).doesNotContain("0DTE");
        }
        assertThat(without.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("allow0dte"));

        AutoRecommender.AutoRequest spyOnly = new AutoRecommender.AutoRequest(
                List.of("SPY"), List.of("0DTE"), 1, null, null, null, null, "aggressive", true);
        AutoRecommender.AutoResult with = auto.run(spyOnly, BP);
        assertThat(with.picks()).hasSize(1);
        AutoRecommender.HorizonIdeas zeroDte = with.picks().getFirst().horizons().getFirst();
        assertThat(zeroDte.horizon()).isEqualTo("0DTE");
        assertThat(zeroDte.candidates()).isNotEmpty();
        for (AutoRecommender.ScoredCandidate sc : zeroDte.candidates()) {
            for (LegView leg : sc.candidate().legs()) {
                if (leg.expiration() != null) assertThat(LocalDate.parse(leg.expiration())).isEqualTo(TODAY);
            }
        }
    }

    @Test
    void profitTargetAnnotatesEveryCandidate() {
        AutoRecommender.AutoResult result = auto.run(req(List.of("month"), 25_000L, false), BP);
        assertThat(result.picks()).isNotEmpty();
        boolean sawAnnotation = false;
        for (AutoRecommender.Pick p : result.picks()) {
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(sc.targetFit()).isNotBlank();
                    assertThat(sc.targetFit()).containsAnyOf("covers", "cannot reach", "Uncapped");
                    sawAnnotation = true;
                }
            }
        }
        assertThat(sawAnnotation).isTrue();
        assertThat(result.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("aspirations"));
    }

    @Test
    void volFitPrefersCreditWhenIvRich() {
        // Fixture IV (~base) vs fixture HV differ per symbol; verify the rescoring direction:
        // for a RICH pick, the top candidate should not be ranked below an equal-score debit.
        AutoRecommender.AutoResult result = auto.run(req(List.of("month"), null, false), BP);
        for (AutoRecommender.Pick p : result.picks()) {
            if (!"RICH".equals(p.signals().volSignal())) continue;
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    if (sc.candidate().entryNetPremiumCents() > 0) {
                        assertThat(sc.autoScore()).isGreaterThan(sc.candidate().score());
                    }
                }
            }
        }
    }

    @Test
    void respectsMaxLossBudget() {
        AutoRecommender.AutoRequest request = new AutoRecommender.AutoRequest(
                null, List.of("month"), 3, null, 50_000L, null, null, "balanced", false);
        AutoRecommender.AutoResult result = auto.run(request, BP);
        for (AutoRecommender.Pick p : result.picks()) {
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(sc.candidate().maxLossCents()).isLessThanOrEqualTo(50_000L);
                }
            }
        }
    }
    @Test
    void exitIntentScansHoldingsNotTheUniverse() {
        var holdings = java.util.List.of(new AutoRecommender.HoldingInfo("AAPL", 200, 20_000L));
        AutoRecommender.AutoRequest r = new AutoRecommender.AutoRequest(java.util.List.of("SPY", "QQQ"),
                java.util.List.of("month"), 3, null, null, null, null, "balanced", false,
                java.util.List.of("exit"), null);
        AutoRecommender.AutoResult res = auto.run(r, BP, holdings);
        assertThat(res.picks()).isNotEmpty();
        for (AutoRecommender.Pick p : res.picks()) {
            assertThat(p.intent()).isEqualTo("EXIT");
            assertThat(p.symbol()).isEqualTo("AAPL"); // what you HOLD, not the scanned universe
        }
        Candidate cc = res.picks().getFirst().horizons().getFirst().candidates().stream()
                .map(AutoRecommender.ScoredCandidate::candidate)
                .filter(c -> c.strategy().equals("COVERED_CALL")).findFirst().orElseThrow();
        assertThat(cc.usesHeldShares()).isTrue();
        assertThat(cc.qty()).isEqualTo(2); // both free lots covered
    }

    @Test
    void exitIntentWithoutHoldingsExplainsInsteadOfInventing() {
        AutoRecommender.AutoRequest r = new AutoRecommender.AutoRequest(null, java.util.List.of("month"), 3,
                null, null, null, null, "balanced", false, java.util.List.of("exit", "hedge"), null);
        AutoRecommender.AutoResult res = auto.run(r, BP);
        assertThat(res.picks()).isEmpty();
        assertThat(String.join(" ", res.notes())).contains("buy shares first");
    }

    @Test
    void incomeIntentScanReturnsIncomeCandidatesAcrossTheUniverse() {
        AutoRecommender.AutoRequest r = new AutoRecommender.AutoRequest(null, java.util.List.of("month"), 3,
                null, null, null, null, "balanced", false, java.util.List.of("income"), null);
        AutoRecommender.AutoResult res = auto.run(r, BP);
        assertThat(res.picks()).isNotEmpty();
        for (AutoRecommender.Pick p : res.picks()) {
            assertThat(p.intent()).isEqualTo("INCOME");
            for (AutoRecommender.HorizonIdeas h : p.horizons()) {
                for (AutoRecommender.ScoredCandidate sc : h.candidates()) {
                    assertThat(io.liftandshift.strikebench.strategy.StrategyFamily.valueOf(sc.candidate().strategy())
                            .servesIntent(io.liftandshift.strikebench.strategy.StrategyIntent.INCOME)).isTrue();
                }
            }
        }
    }

}
