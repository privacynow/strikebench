# Duplication Ledger — StrikeBench

Date: 2026-07-23 · Branch: `feature/journey_refactor` · Method: 5 parallel read-only code audits
(orchestration · intent/verdict/EV · market-data · API-layer · frontend), each verified by reading
the code and citing `file:line`.

This is the map that drives the consolidation. Ordering follows the owner's directive — **merge the
orchestrators first, then land correctness fixes** — but the **HIGH-severity DIVERGENT** items are
flagged separately because they are *active correctness divergences* (two places computing the same
thing and disagreeing), not cosmetic copies.

Severity legend: **DIVERGENT** = same job, different rules → can already produce inconsistent results
(worst). **IDENTICAL** = verbatim copy → drift risk, no live bug yet. **OVERLAP** = two owners of one
responsibility.

### Progress log
- **2026-07-23 · commit 60ab33f (P1a):** O1-core, O3, O4 done — `EvaluationService.evaluateBestPerFamily`
  (one per-symbol ranking primitive, also fixed the Portfolio scan's non-best-per-family divergence),
  `RedeploymentFrontier.composeBookLayer` (one Book-layer epilogue), public `StrategyEvaluator.RANKING`
  (one comparator). Scout output verified behavior-identical on sim.
- **2026-07-23 · commit fcbcf0b (P1b):** O5, O6, A3 done — `Request`/`AutoRequest` withers +
  `DiscoveryController.withAccountHoldings`/`withRiskCap`; three cap-and-rebuild copies and two
  holdings-injection copies collapsed. ApiIntegration 52/52 + PlanApiIntegration 36/36 green.
- REMAINING orchestrator: O2/X4 (economic-readiness, 3 dialects), O7 (recommend/ladder preflight).

---

## 0. The dangerous few (DIVERGENT — these can bite today)

| Tag | Duplication | Locations | Why it's dangerous |
|---|---|---|---|
| **X1** | Round-trip fee formula + `EV − fees` | `EconomicAssessment.roundTripFees:223` (canonical) vs `ScoreComposer:63-68` vs `OutcomeController:721-728` vs `TradeController:773` | The **verdict** and the **ranking score** net *different* fee numbers off the same EV; in the exact-ticket path the score even uses a re-estimated fee while the verdict uses the preview's real fee. |
| **X2** | Income carry/coherence ("gets paid to wait?") | `RecommendationEngine.intentIncoherence:1228` (entry-premium sign) vs `StrategyEvaluator.objectiveCoherence:140` (theta-carry sign) | Two proxies for positive carry that can disagree: a structure can pass the offer-time gate (collects a credit) yet be flagged incoherent at eval (negative theta), or vice-versa. |
| **X3** | Provider politeness governor | `ProviderPoliteness` (shared) vs `CboeProvider` inline gate `:88-108,289-295` | Cboe's copy **regresses** a live breaker via a shorter persisted cooldown and has **no consecutive-failure trip** — latent rate-limit bugs, the exact class that caused the original Cboe incident. |
| **X4** | Economic-readiness classification | `DiscoveryController.decisionRanked:160-210` ≈ `PlanStrategyService.attachEconomicReadiness:235-268` (same dialect) vs `PlanStrategyController.flattenPlanScout:299-348` (**different vocabulary**) | The Scout tab and the Decision tab can report *different readiness* for the *same* underlying verdicts. |

These four are the literal instance of "fix the same correctness/intent problem in multiple places."

---

## 1. Recommendation / orchestration layer

| Tag | Duplication | Locations | Kind | Canonical home |
|---|---|---|---|---|
| O1 | Candidate selection/ranking orchestration | `AutoRecommender`, `OpportunityScanner`, `DiscoveryController.decisionRanked` | DIVERGENT (3 selection rules, one backbone) | one `scanSymbol(...)` |
| O2 | Economic-readiness classifier (= X4) | `DiscoveryController:160-210`, `PlanStrategyService:235-268`, `PlanStrategyController:299-348` | DIVERGENT (2 dialects) | `EconomicReadiness.summarize(field)` |
| O3 | Compensation+frontier epilogue | `AutoRecommender:360-367` ≡ `OpportunityScanner:131-136` | near-IDENTICAL | `ScanEpilogue.compose(...)` |
| O4 | `decisionScore` comparator re-spelled inline | `OpportunityScanner:128`, `AutoRecommender:424-426,554` | IDENTICAL copy of `StrategyEvaluator.RANKING` | reuse `RANKING` |
| O5 | Holdings-injection for hold-based intents | `DiscoveryController:327-337` ≡ `:397-407` | near-IDENTICAL | one `withInjectedHoldings(...)` |
| O6 | Risk-cap "apply + rebuild request" (= A3) | `DiscoveryController:340-346,409-415,483-490` | DIVERGENT-by-record-type | request `withMaxLossCents(...)` withers |
| O7 | `recommend()` vs `ladder()` preflight/budget/notes | `RecommendationEngine:178-218` vs `:459-506` | DIVERGENT wording | shared preflight + note helpers |

Not duplicated (verified): candidate construction (one home: `RecommendationEngine.toCandidate` → `StrategyBuilder`); per-candidate scoring (one home: `EvaluationService.evaluate` → `StrategyEvaluator`); `bestPackagePerFamily` (one def, 3 reuse sites); `CompensationView`/`RedeploymentFrontier` composers (single-home; only the glue is copied — O3).

---

## 2. Intent / thesis / stance / verdict / EV

| Tag | Duplication | Locations | Kind | Canonical home |
|---|---|---|---|---|
| E1 | Round-trip fees + `EV − fees` (= X1) | `EconomicAssessment:223-232,80-83` vs `ScoreComposer:63-68` vs `OutcomeController:721-728` vs `TradeController:773-774` | DIVERGENT | `EconomicAssessment.roundTripFees` / a `Fees` util |
| E2 | Income carry/coherence (= X2) | `RecommendationEngine.intentIncoherence:1228-1240` vs `StrategyEvaluator.objectiveCoherence:140-148` | DIVERGENT proxies | one carry primitive (`StanceProfiler` carry) |
| E3 | Directional fit, 2 fidelities | `StrategyFamily.fits:110` (catalog) vs `StanceProfiler.implied:137-139` (delta) | MODERATE two-tier | keep two-tier, but change both together |

Not duplicated (verified — the reassuring part): market-implied EV point (one owner: `RiskNeutralAnalyzer`/`PayoffCurve`); realized-vol EV point (one site: `RiskProfiler:90`); materiality + payoff scale (`EconomicAssessment`; `ScoreComposer` **reuses** `realisticPayoffScaleCents`); verdict tiers FAVORABLE/MIXED/… (only `EconomicAssessment`); **structure-direction derivation is single-owner** (`StanceProfiler.implied`; `objectiveCoherence` consumes it, never re-derives). → This is why adding a `family.fits()` term to `ScoreComposer` would have been a *new* 3rd fidelity; the correct fix is to consume `ImpliedStance`.

---

## 3. Market data / provider / cache / streaming

| Tag | Duplication | Locations | Kind | Canonical home |
|---|---|---|---|---|
| D1 | Politeness governor (= X3) | `ProviderPoliteness` vs `CboeProvider:88-108,289-295` | DIVERGENT (+latent bugs) | `ProviderPoliteness` |
| D2 | Serve last-known quote (warm/fallback) | `MarketDataEngine:65,118-129,419-421` vs `MarketDataService:64-66,443-445` (both over `MarketSnapshotStore`) | OVERLAP (dual owners) | `MarketDataEngine` |
| D3 | Write observed `underlying_bar` | `ObservedCandleWriter:62` vs `SnapshotService:117-124` (+ `HistoricalOptionsIngest:100`, `UnderlyingCsvIngest:145`) | DIVERGENT (bypasses "one validated path") | `ObservedCandleWriter` |

Not duplicated (verified): option_bar→`OptionChain` assembly (one owner: `StoredHistoricalOptionsProvider`; the new `StoredOptionChainStore` **delegates**, zero SQL copied); candle read (orchestrator vs store, layered); SSE broadcaster vs scout NDJSON (different interaction shapes); payload-cache vs derived-caches (different granularity, intentional).

---

## 4. API / controller layer

| Tag | Duplication | Locations | Kind | Canonical home |
|---|---|---|---|---|
| A1 | `worldParam` sentinel normalization | 8 identical private copies (`DiscoveryController:118`, `CoreController:272`, `ApiServer:720`, `PlanController:165`, `OutcomeController:67`, `ResearchController:413`, `SparklineController:178`, `TradeController:887`) + ~32 inline `"observed".equals(world)` | IDENTICAL | `MarketLane` |
| A2 | `DollarDelta → PortfolioExposureContext` mapping | 7 sites / 4 files (`DiscoveryController:452,652,671`, `PlanStrategyController:225`, `TradeController:365`, `TrackedPackageAnalysisService:64,115`) | DIVERGENT superset | `PortfolioExposureContext.fromDelta(...)` |
| A3 | Risk-cap apply+rebuild (= O6) | `DiscoveryController:340-346,409-415,483-490` | DIVERGENT-by-record | request withers |
| A4 | World/active universe symbol resolution | `DiscoveryController.universeScope:600` (private) + partial variants in `CoreController:165`, `SparklineController:80`, `MarketStreamController:64,95,104`, `DataController:215,242`, `PlanStrategyController:260` | DIVERGENT partials | promote `universeScope` to `UniverseService` |
| A5 | Replay-lane predicate `!observed && !demo` | `ApiServer:713`, `ResearchController:353`, `DataController:349`, `WorldController:374`, `WorldTransitionService:99,178` | DIVERGENT wording | `MarketLane.isReplayable()` |
| A6 | Evaluation-receipt JSON attach | `DiscoveryController.attachEvaluationReceipt:221` vs inline `PlanStrategyController:215,317` | IDENTICAL | reuse the helper / `ApiResponses` |

Not duplicated (verified): `decisionRanked` is shared, not copied (Plan controllers call it); owner/world/account resolution centralized in `ApiServer`; request-body parsing centralized (`ApiRequest`, `DecisionDeclarationPolicy`); routes cleanly split.

---

## 5. Desk frontend (`index.html` + `js/desk-backend.js` + `js/api.js` + `app.css`)

| Tag | Duplication | Locations | Kind | Canonical home |
|---|---|---|---|---|
| U1 | Money format `money()`/`signed()` | canonical `index.html:3705-3710` vs ~8 inline (`:7185,4384-4387,4378,4271,8017-8021`, governor row) | DIVERGENT fallbacks | `money`/`signed`/`authMoney` |
| U2 | Max-profit / max-loss display | `authPayKeyHTML:3885` vs `maxLossDisp/maxProfitDisp:7185-7186` | DIVERGENT vocab | one max-P/L formatter |
| U3 | Greeks row (Δ/Θ/Vega/Γ) | `:3883`, `:3898`, `:5703`, `:8235` | DIVERGENT (4 ways, inconsistent units) | one greeks-row builder |
| U4 | Quote/mark normalization ("which price is authoritative") | `desk-backend.js:149,2663` vs `index.html.authHomeQuote:4907` | DIVERGENT (second price authority) | `researchMark`/`quoteFromResearch` in desk-backend |
| U5 | Parse-to-finite-or-null | `authNumber:3707` vs `desk-backend.js.number:143` | DIVERGENT on `''` | one parser |
| U6 | Sessions-between-dates | `desk-backend.js.sessionDistance:209` (weekday count) vs `index.html.histSessionsUntil:4961` (×252/365 approx) | DIVERGENT algorithms | one session-count |
| U7 | CSS type-scale tokens | `app.css:12-15` vs `index.html:24-31` (inline override) | DIVERGENT (shadowed) | one token block |
| U8 | Percent-change `(x/prev-1)*100` | `index.html:3845,4907,4910,5108,7415,7433,8391` | IDENTICAL ×7 | one `pctChange` helper |
| U9 | 2-decimal price format | `authHomePrice:4906` vs inline `$'+x.toFixed(2)` `:3883,3897,3901,4184,4225,7914,8375` | IDENTICAL bypass | `authHomePrice` |
| U10 | Hardcoded CSS token values | `#0e141d`×6, `#39C7D4`, `#41C892`×2, `#F35D64` in inline `<style>` | IDENTICAL bypass | `var(--…)` from `app.css` |
| U11 | Two `app.css` files (naming hazard) | `public/app.css` (cyan Desk) vs `public/css/app.css` (blue Workspace) | structural | rename / namespace |
| U12 | `prototypes/desk.html` stale fork | byte-identical `money`,`fmtK`,`drawPayoff`,… vs served `index.html` | IDENTICAL fork | delete or clearly quarantine |

Not duplicated (verified — the good baselines): `deskEsc` single-source (277 call sites); `desk-backend.js` builds **zero** HTML (pure data layer); `authPositionChainHTML` correctly *delegates* to `authHomeChainHTML`.

---

## 6. Consolidation plan (ordered per owner directive)

**Phase 1 — Orchestrator merge (chosen first).** Collapse O1 into one `scanSymbol(symbol, intent, thesis,
horizon, riskMode, holdings, world) → List<StrategyEvaluation>`; the 3 orchestrators become thin callers.
Absorb O2/X4 (→ `EconomicReadiness.summarize`), O3 (`ScanEpilogue`), O4 (reuse `RANKING`), O5, O6/A3,
O7 in the same pass so selection + readiness + epilogue live once.

**Phase 2 — The dangerous eval divergences.** X1/E1 (one fee/EV-net path) and X2/E2 (one carry-coherence
primitive, offer-gate consumes it). Keep E3 two-tier but change-together.

**Phase 3 — Data pillar.** X3/D1 (fold Cboe onto `ProviderPoliteness` — also fixes the breaker bugs),
D2 (single last-known-quote owner), D3 (route all observed writes through `ObservedCandleWriter`).

**Phase 4 — API layer.** A1 (`MarketLane`), A2 (`fromDelta` factory), A4 (`universeScope`→service), A5, A6.

**Phase 5 — Frontend.** U1–U6 first (divergent, user-visible money/greeks/quote/session logic → one
`fmt`/greeks/quote module), then U7–U10 (bypasses of existing helpers), then U11/U12 (structural).

**Reassurance for the record:** the *deep* correctness owners are already single — candidate construction,
per-candidate scoring, EV math, payoff scale, verdict tiers, structure-direction derivation, chain
assembly, `deskEsc`. The drift is concentrated in **fees, carry-coherence, politeness, readiness
classification, and frontend formatting** — a finite list, now tracked here.
