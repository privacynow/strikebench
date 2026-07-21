# Desk, market-data, and simulation convergence handoff

Date: 2026-07-21

Branch: `feature/journey_refactor`
Starting checkpoint for this increment: `7c97774` — `Converge desk data and scenario workflows`

## Outcome of this increment

This increment keeps the approved Desk design while replacing the empty or prototype-local parts of
the served experience with canonical StrikeBench owners. The working development database now has a
broad observed-history corpus, a bounded observed option/quote snapshot, and five API-created Practice
positions. Home, Position Bloom, recommendation economics, scenario paths, and the data maintenance
loop use the existing backend contracts. The existing Universe Scout is also now goal-aware, so the
new memory/storage opportunity work sits above the same recommendation/evaluation engine instead of
creating a second recommender.

The core product rules remain unchanged:

- observed, delayed, stored, simulated, and modeled inputs remain distinct in the data contract;
- product copy states the surface-wide market mode once and flags exceptions, rather than repeating
  internal provenance vocabulary throughout every panel;
- no browser code generates authoritative prices, paths, payoff values, Greeks, or verdicts;
- `PathEnsembleService` remains the only Monte Carlo/path owner;
- Strategy evaluation and `DecisionPolicy` remain the only recommendation/endorsement owners;
- the transient snapshot is a development recovery artifact, never an application fallback;
- Yahoo bars retain Yahoo provenance and are never described as exchange data.

## Observed development snapshot

Bundle: `.tmp/dev-market-snapshot`

Manifest: `.tmp/dev-market-snapshot/manifest.txt`

Format: `3`
Captured: `2026-07-21T21:07:14.852Z`

Verified contents at capture time:

| Domain | Rows | Symbols | Coverage |
| --- | ---: | ---: | --- |
| Observed underlying bars | 51,072 | 102 | 2024-07-22 through 2026-07-21 |
| Yahoo-attributed daily bars | 51,027 | 102 | at least 500 stored sessions per canonical symbol through 2026-07-20 |
| Observed option snapshots | 242,365 | 28 | 2026-07-17 through 2026-07-21 |
| Cboe quote snapshots | 23 | 23 | attributable observed/delayed receipts |

The captured 102-symbol history set covers the prior complete canonical curated universe: major
semiconductors, technology leaders, broad and sector ETFs, financials, healthcare, consumer,
industrial, defense, energy, utilities, communications, and liquid cross-asset ETFs. The source
contract has since been expanded additively to **105 symbols** with `STX`, `WDC`, and `SNDK`; the
current bundle does not yet contain those three. This is a scheduled data gap, not permission to
weaken the capture contract or invent history. `SNDK` has an honest coverage floor of 2025-02-24,
its first regular-way Nasdaq session after the Western Digital separation.

The option snapshot intentionally concentrates on the working semiconductor/technology set and
important broad/sector ETFs; its exact 28 symbols are in the manifest. The polite 2026-07-21 Yahoo
allowance reached 160/160 after 27 symbols received the newly completed session. The other 75 retain
complete two-year coverage through 2026-07-20, and the three new storage names have not yet been
requested. Do not bypass the durable budget to make those dates cosmetically uniform. The manifest
makes the captured asymmetry explicit:
`yahoo_complete_through=2026-07-20`,
`yahoo_latest_session_distribution=2026-07-20:75,2026-07-21:27`, and
`yahoo_row_count_distribution=500:75,501:27`; verification recomputes all three from the exported
per-symbol coverage file.

Capture jobs used during the open session:

- CORE: `job_ek62kfzaa4ycpd09`
- SEMICONDUCTORS: `job_3edfv829td562v1m`
- TECH: `job_5rd6a1fc9hc747r8`
- ETFS: `job_zrz4p24n89958b3z`
- Yahoo 102-symbol history synchronization: `job_xyfg2wj9z2ehvh8y`

The original Yahoo job completed 102/102 symbols without failure. Subsequent missing-session
enrichment consumed the remainder of the configured allowance; the durable budget captured in the
bundle is now 160/160 for 2026-07-21. Do not run another request in that budget period. The product
schedule and canonical universe now include the storage trio, but their first fill must wait for the
next UTC budget period.

The active product universe was restored to CORE after capture.

### Snapshot operations

The helper is deliberately restricted to `strikebench_dev`. Format 3 validates the exact selected
table/type/nullability contracts independently from unrelated global schema evolution, hashes every
export, and derives data plus manifest facts from one read-only repeatable-read database snapshot.
It also captures `provider_request_budget` and every persisted `*_cooldown_until` setting so a
destructive database recreate cannot reset upstream etiquette.

```bash
scripts/dev-market-snapshot.sh verify
scripts/dev-market-snapshot.sh hydrate
scripts/dev-market-snapshot.sh capture
```

Use `hydrate` only after the application has initialized the current empty development schema. An
ordinary Postgres/container restart retains its volume and requires no hydration. `capture` replaces
the bundle only after the new export is complete and verified. It first refuses loss of any member
of the canonical 105-symbol Yahoo universe, established per-symbol date/row coverage, important
option and quote sentinels, prior option history, request usage, or persisted cooldown state. The
previous verified bundle is retained under `.tmp/dev-market-snapshot.versions/`; the current
pre-format-3 capture is preserved at
`.tmp/dev-market-snapshot.versions/20260721T201116319Z-811d0d2669db`, the first format-3 capture is
preserved at `.tmp/dev-market-snapshot.versions/20260721T204410042Z-847f14c5b7b5`, and the bundle
immediately preceding the current capture is
`.tmp/dev-market-snapshot.versions/20260721T204811156Z-a7af627baaf5`.

Format-3 bundles captured before the storage-theme expansion remain verifiable and hydratable against
the explicit legacy 102-symbol contract. Every **new** capture writes `canonical_yahoo_symbols` and
must contain all 105 symbols, so the next capture will correctly refuse to install until the storage
trio has real Yahoo-attributed coverage.

`SNAPSHOT_ALLOW_REGRESSION=true` is the explicit emergency override and emits a warning. Hydration is
an additive merge: underlying rows update only for higher quality or newer equal-quality receipts,
option rows update only for stronger provenance or newer equal-quality receipts, quotes never move
backward in `as_of`, and provider request counts/cooldowns only increase. A merge rehearsal against
the populated database completed successfully. Request budgets retain their original
`source_key + period_key`; hydration never rebases an exhausted historical allowance onto a later
day.

## Yahoo as a persistent product source

The product owner's authorization is now represented by the Observed-lane defaults. This is a
standing product authorization, not a one-off development-lane exception: Yahoo daily history stays
enabled outside Fixtures-only until the owner explicitly revokes it. Either of these settings stops new
Yahoo requests without deleting already attributed stored bars:

```bash
YAHOO_ENABLED=false
YAHOO_AUTOMATION_PERMISSION_CONFIRMED=false
```

The default operating envelope is intentionally conservative:

- maximum concurrency: 1;
- minimum request-start spacing: 1,500 ms;
- durable daily request budget: 160;
- provider-wide cooldown: 30 minutes after 403, 429, legacy 999, or three consecutive ordinary
  failures;
- complete stored ranges: zero upstream calls;
- scheduled range: two years across the canonical curated universe, with a durable 200-symbol
  schedule ceiling (the current contract is 105);
- cadence: once after each completed NYSE session, with durable coverage/config identity and bounded
  recovery of missing symbols rather than repeated whole-universe sweeps.

Relevant controls are `YAHOO_MAX_CONCURRENCY`, `YAHOO_MIN_SPACING_MS`,
`YAHOO_DAILY_REQUEST_LIMIT`, `YAHOO_COOLDOWN_MINUTES`, `YAHOO_HISTORY_SYNC_ENABLED`, and
`YAHOO_HISTORY_SYNC_YEARS`. Data → Sources & jobs exposes the system schedule and job receipts.

## Standing Practice book and Position Bloom

`scripts/seed-practice-book.sh` creates positions through the real Plan → recommendation → ensemble →
outcome → decision preview → trade APIs. It performs no direct database insert and creates no browser
fixture. It skips an already active symbol, reuses only mutable ACTIVE seed Plans after interruption,
and creates a numbered seed cycle after a position is closed or its Plan is frozen.

Current active Practice positions:

| Symbol | Structure | Trade | Plan | Stored ensemble |
| --- | --- | --- | --- | --- |
| AMD | Iron condor, quantity 2 | `tr_h1cgyftjhktaejj8` | existing owning Plan | existing owning ensemble |
| NVDA | Bull put credit spread | `tr_arhzgw2be1eze1kx` | `plan_125vnaw1pyhfj753` | `pen_8a75rq00ktbwd0vt` |
| MU | Iron butterfly | `tr_042jqvweck6cjtqw` | `plan_8bygjdpzwjdxw7r2` | `pen_bwkkgxt68x78mr5z` |
| AAPL | Iron condor | `tr_yyet629rr92jkvtv` | `plan_5f0v2qfaq9n2ha3v` | `pen_6zkhyxwr5st12h1m` |
| SPY | Bear call credit spread | `tr_k0fqxm0bxwf7n52s` | `plan_hnyzat1fb0arjrjm` | `pen_56bfqkf0a1bxkvz5` |

Successful seed receipts are in:

- `.tmp/practice-book-seed-20260721-194518`
- `.tmp/practice-book-seed-20260721-194533`

The earlier `.tmp/practice-book-seed-20260721-194420` receipt records the rejected pre-fix MARKET
request that incorrectly supplied `proposedNetCents`; it is useful negative evidence. The corrected
script omits that field for MARKET instructions.

Useful controls:

```bash
PRACTICE_SEED_SYMBOLS=AMD,NVDA,MU,AAPL,SPY scripts/seed-practice-book.sh
PRACTICE_SEED_CYCLE_ID=market-open-2026-07-22 scripts/seed-practice-book.sh
```

The served Position Bloom composes independently available results instead of letting missing
research decoration blank the position. It renders an actionable contained error if a required read
fails. The final packaged walkthrough included terminal payoff, current executable mark, P/L, POP,
Greeks, all four iron-condor legs, scenario tiles and paths, history, research, news, and the owning
working idea. The four legs fit as a readable 2x2 group and the six current-position metrics share a
single compact rail; geometry checks confirmed that no leg escapes or clips the side panel.

Position opens on the ensemble-derived central market story, paused at `t=0`, with the complete path
already visible rather than an empty instruction panel. P/L is the useful default and Price remains
available as a lens over the identical source trajectories. The path uses the backend-returned full
checkpoint grid. News/research/history/management have dedicated usable areas, payoff values live in
a rail instead of overwriting the hero curve, and internal contract language/debug receipts have
been removed from the default product copy. Detailed provenance remains available in the data
contract; only exceptions to the surface-wide lane are called out in the product.

## Home

Home is again a product surface rather than a collection of empty-state prose. It now renders:

- the Practice account and five-position roster;
- Book Risk and a restrained POP/max-loss map;
- a focused candlestick panel from attributed history;
- a bounded near-market option-chain slice with bid/ask values;
- an active-universe quote watch;
- working ideas;
- research coverage and news.

Only the focused symbol loads full history, expirations, chain, and research. The watch uses bounded
quote reads, so Home does not turn into a broad Cboe warm-up. Empty books collapse empty risk geometry
while retaining ambient market/research context.

The Home risk/reward map is interactive and links marks to the position roster. Product-facing copy
uses **Idea** and **Working idea** while `Plan` remains the backend aggregate. Ingestion counters,
trend thresholds, raw source enums, and repeated lane labels no longer occupy the normal product
surface. A bounded Memory & storage scan uses the existing goal-aware Scout and currently reports
honestly when none of MU, SMH, STX, WDC, or SNDK clears a favorable after-cost edge rather than
manufacturing an opportunity. Future market-pulse and broader sector lenses should continue to reuse
the same history, quote, universe, Book Risk, and research owners; they must not revive prototype-local
data or duplicate the evaluation engine.

## Monte Carlo and scenario coherence

The backend response remains one immutable 500-path `PathEnsembleService` artifact per working idea.
That underlying-price ensemble is intentionally candidate-invariant: every package is judged against
the same possible market, so switching candidates must retain the ensemble fingerprint and the same
source-row sample. The UI must say this plainly once (for example, “AMD · 500 price paths · 45
sessions — one market, every idea judged against it”) rather than disguising sameness by redrawing a
different random subset.

Backend additions in the current diff make the candidate-specific transform possible without a
browser pricing engine:

- `SimulationEngine.Preview` returns up to 48 deterministic, source-indexed price trajectories,
  `sampleSourcePathIndices`, a focus index, and P10/P25/P50/P75/P90 `stepBands` on the bounded
  full-resolution display grid;
- `PlanOutcomeController` carries those same display source indices and selection rule into the
  canvas valuation and valuation fingerprint, and accepts `pathWaypoints` as the progress-based
  alternative to legacy day-index waypoints (never both in one request);
- `ScenarioCanvasValuator` returns `PositionStepBand` quantiles and `DisplayPositionPath` P/L
  trajectories keyed by `sourcePathIndex`, valued through `PathValuationKernel` for the exact package;
- a selected scenario still returns neighboring paths from that same ensemble and preserves the
  ensemble identity, model, assumptions, valuation fingerprint, and path-selection rule;
- one-session hypotheses retain fractional-session pins so crash, gap, chop, and drift do not
  collapse to an identical closing waypoint.

This establishes two honest views of one artifact:

1. **Price** — the underlying's common future-price fan.
2. **P/L** — those identical source rows repriced through the selected package, which changes shape
   when the candidate changes.

The packaged Desk now contains the P/L/Price toggle, stable source-row mapping, terminal hover
readout, and click-to-nearest-story behavior. A chart-wide hit layer chooses the closest displayed
source path rather than relying on overlapping invisible path strokes. A physical pointer click in
Playwright focused the Flat/range story and started the existing whole-desk playback, so payoff,
risk, values, and dock remain on one scenario workflow. The browser interpolates returned checkpoints
for motion; it does not add random noise, resample paths, or recalculate prices, probabilities, P/L,
or Greeks per frame.

New Idea now measures a conservative whole-row page size. At 1920x1080 it renders five complete
candidate rows plus the contained pager (`1-5 of 10 whole rows` in the final walkthrough), with no
partial sixth row. Bloom's four-leg package uses a readable contained 2x2 arrangement. Extend this
same whole-item/pager grammar when a roster or unusually large exact-leg package exceeds its measured
container; do not introduce unrelated free-scrolling card interiors.

## Recommendation economics and search

The risk-neutral market-implied EV lane is now what it mathematically is: a price/cost benchmark. It
remains visible, including spread and fees, but it cannot veto realistic-measure EV as a second edge
vote. The recommendation verdict uses the realized-volatility point estimate after costs and a
structure-scaled materiality threshold. Its one-standard-error volatility range and full tail remain
visible as model-risk disclosure; a range crossing zero narrows confidence but no longer makes
`FAVORABLE` structurally impossible.

`FAVORABLE` still requires the evidence consumed by the claim, mechanical eligibility, and coherent
fit. Incomplete/model-only evidence remains `MIXED`; a materially adverse realistic estimate whose
sensitivity stays adverse remains `UNFAVORABLE`. The UI uses realistic EV/range as its edge axis and
shows market EV separately as the cost benchmark.

Income starts at 45 sessions in the Desk. Exact persisted horizons remain exact, including 30d and
45d. Internal callers with an absent Income horizon use an explicit 30-session compatibility cycle;
public Plan decision routes still require the declaration.

Credit-vertical and iron-condor construction now preserve bounded diversity across short
delta/moneyness boundaries and wing-width bands before evaluation. Existing Guardrails, exact payoff,
evidence assembly, and DecisionPolicy still evaluate and rank every retained package. Raw
return-on-risk clustering can no longer consume every search slot around one strike neighborhood.
Poor asymmetric iron condors are rejected by a dedicated quality screen rather than being promoted
merely because they collect premium. The strategy engine version is now `plan-strategy-6`; older
cached competitions are invalidated rather than being silently interpreted under the new economics.

The latest AMD 45-session live walkthrough produced two packages with favorable after-cost realistic
economics. The selected cash-secured put showed approximately:

- realistic-measure EV after costs: `+$334`;
- sensitivity: `-$233` through `+$889`;
- market-implied cost benchmark after costs: `-$32`;
- chance of profit: about `70%`.

The covered strangle showed about `+$441` of after-cost realistic EV. Both packages were mechanically
valid but directionally mixed with the declared Neutral objective, so the Desk correctly displayed
“favorable economics · mixed fit” and did not manufacture an exact-fit Desk Pick. This distinction is
important: favorable economics may be earned without claiming the package is coherent with every
declared goal/view.

### Goal-aware cross-symbol opportunity scan

Do not rewrite the per-symbol recommendation engine. `POST /api/research/scout` now supplies the
missing layer above it: it ranks symbols for a declared goal, then sends every surfaced package
through the same `RecommendationEngine`, `EvaluationService`, evidence, economics, and DecisionPolicy
owners.

Minimal focused request:

```json
{
  "universe": ["MU", "SMH", "STX", "WDC", "SNDK"],
  "horizons": ["month"],
  "maxPicks": 5,
  "riskMode": "balanced",
  "intents": ["INCOME"]
}
```

`universe` is optional and defaults to the active universe; `horizons`, `riskMode`, and `intents` are
explicit decision declarations. Optional fields remain `targetProfitCents`, `maxLossCents`,
`maxRiskPctOfAccount`, `minConfidence`, `allow0dte`, `filters`, and `thesisOverride`.

Each `pick` retains `symbol`, `signals`, `intent`, the full per-horizon evaluated candidates, and:

- `opportunity`: `goal`, composite `score`, signal confidence, goal-directional `volatilityFit`,
  liquidity, event adjustment, plain-language summary, and volatility/event evidence;
- `bestIdea`: family/display name, economic verdict and placement, chance of profit, max loss,
  market-implied cost benchmark, realistic after-cost EV and range, the difference between the two,
  evidence state, and a summary.

For an Income scan, rich IV relative to realized movement contributes positively, source-backed event
risk subtracts from the opportunity score, and missing IV/HV receives no volatility credit rather than
a fabricated neutral score. For Hedge, comparatively cheap options receive the positive fit. In the
memory/storage example, `MU` and `SMH` remain liquid tradable proxies while `STX`, `WDC`, and `SNDK`
enter the same scan after their real daily histories are loaded; SK Hynix is not presented as a normal
US-options underlying. The cross-symbol score orders where to look and never replaces candidate-level
economics or tail analysis.

## Desk client recovery and freshness

Two client failures that previously left the Desk in a permanent empty/error surface are fixed in
the current diff:

- Plan creation uses a session-scoped idempotency key, re-lists Plans when a create response no
  longer matches the current declaration, and rotates that key exactly once before retrying. A stale
  idempotent response can no longer trap New Idea in “returned Plan does not match” forever.
- `loadMarket` uses fresh reads for canonical research, expirations, and chain inputs. A changed quote
  or calibration now invalidates the strategy/ensemble fingerprint correctly instead of reusing a
  market response that belonged to the prior assumptions.

The Home focus request is also sequence-guarded so a slower earlier symbol cannot overwrite the
latest selected symbol. Preserve these guards when refining the composer or adding prefetch; do not
restore whole-view replacement that loses focus, animation, scroll, or control state.

## Packaged development server

Build before starting, and place configuration before the Java command:

```bash
mvn -q -DskipTests package
env \
  PORT=7070 \
  FIXTURES_ONLY=false \
  ENGINE_WARM_FULL_UNIVERSE=false \
  SNAPSHOT_ENABLED=true \
  PORTFOLIO_NAV_ENABLED=true \
  YAHOO_ENABLED=true \
  YAHOO_AUTOMATION_PERMISSION_CONFIRMED=true \
  YAHOO_HISTORY_SYNC_ENABLED=true \
  java -jar target/strikebench.jar
```

The final verified boot restored 23 cached quotes, warmed only the ten-symbol active sector, mounted
the durable Yahoo schedule, and served the Observed lane on port 7070 from the latest packaged source.
At this handoff a `java -jar target/strikebench.jar` process still owns port 7070 and the development
database still contains the five active Practice positions listed above. Resolve the current PID
with `lsof` rather than relying on a recorded process number. The market closed at 13:00 MST (16:00
ET) during this increment; existing observed inputs retain their source/freshness in the contract,
and closed-market development can use those honestly stale reads or an explicitly simulated world
without relabeling one as the other.

## Acceptance evidence

Focused checks completed while building this increment:

- recommendation/economics/horizon tests: passed;
- `StrategyBuilderTest,RecommendationEngineTest`: 39 tests passed;
- path display/simulation tests: passed;
- focused served Desk cases for Home, New Idea, Position Bloom, candidate paging, and source-aligned
  scenario paths: passed on the final package;
- snapshot/Yahoo focused tests: passed;
- `scripts/dev-market-snapshot-contract-test.sh`: passed coverage, sentinel, budget, cooldown, and
  schema-surface regression contracts;
- `scripts/dev-market-snapshot.sh verify`: passed for the captured format-3 bundle;
- `scripts/dev-market-snapshot.sh hydrate`: passed as an idempotent, non-downgrading merge against
  the populated development database;
- `bash -n scripts/seed-practice-book.sh`: passed;
- `git diff --check`: passed after the final integrated Desk/scout edits;
- `node --check src/main/resources/public/js/desk-backend.js`: passed after final integration.

The final packaged browser walkthrough verified:

- Home at 1920x1080 with five positions, candles and chain, watch, Working ideas, research/news,
  interactive risk map, Book Risk, and no page overflow;
- New Idea at the 45-session Income default with ten comparisons, four favorable-economics results,
  five whole visible rows, a contained pager, the P/L fan, stable source rows, and a physical path
  click that drove the selected story across the Desk;
- AAPL Position Bloom with a non-empty default P/L story, all four iron-condor legs contained,
  separated metrics and research areas, and no page overflow;
- no visible matches in the audited surfaces for the internal/default-copy vocabulary
  `authoritative`, `backend-owned`, `source-owned`, `stored coverage`, `mark coverage`, `conditioned`,
  `receipt`, raw `KEYWORD_DERIVED`, or raw `MANAGE_REVIEW`.

A broad `mvn -q test` completed with exit 0 before the final frontend-only geometry/copy refinement.
The final targeted Java set passed for scenario/path display, Plan APIs, recommendation/economics,
Yahoo/scheduler/politeness, configuration, and strategy competition. The four focused packaged
browser cases covering source-aligned path interaction, whole-row paging, Position Bloom loading,
and Bloom leg containment passed after the final client edits. Do not spend normal iteration loops
repeating the entire broad suite; rerun the affected module first and reserve the broad suite for the
next materially integrated Java increment.

## Exact next Yahoo action after the UTC budget reset

Do not change the limit, clear `provider_request_budget`, alter its period key, or issue ad-hoc Yahoo
requests before reset. When the durable Yahoo budget moves off `2026-07-21`:

1. Start one normal `sync_underlying` job through `POST /api/data/jobs` (or the identical Data UI)
   for the full canonical 105-symbol set, source `yahoo`, from `2024-07-22` through the latest
   completed NYSE session. The missing-range planner must make this a gap-only pass: 75 existing
   symbols need the 2026-07-21 session and `STX`/`WDC`/`SNDK` need their initial histories. Accept
   `SNDK` beginning on 2025-02-24; never fabricate its pre-listing period.
2. Wait for the durable job receipt. Confirm all three storage symbols in `/api/data/coverage`, no
   provider cooldown, and a request count within 160. If Yahoo applies a cooldown, stop and let it
   expire; do not parallelize or retry around it.
3. While attributable Cboe data is available, run the bounded semiconductor/storage quote and option
   snapshot job through the existing scheduler. Optionless/unavailable names must remain named gaps.
4. Run `scripts/dev-market-snapshot.sh capture` and then `verify`. The new manifest must contain
   `canonical_yahoo_symbols` with all 105 names, preserve or improve every prior row/date sentinel,
   and archive the current 21:07:14.852Z bundle automatically.

## Ordered continuation

1. Start from this branch and verify the snapshot before any destructive development-database reset.
2. Keep the packaged server on port 7070 and use the existing five-position book for every Home →
   Position → scenario acceptance pass.
3. Perform the exact gap-only 105-symbol Yahoo action above after the UTC allowance resets, then
   refresh the bounded Cboe snapshot during the next observed session and recapture the bundle.
4. Keep the source-indexed Price/P&L fan, default Position story, stable path interaction, whole-row
   paging, structural New Idea composer, and synchronized whole-desk playback as regression contracts.
5. Extend the established focus-lens grammar to the remaining Book/Home objects: hover previews,
   click focuses the Desk in place, and Escape/back restores the exact prior context.
6. Continue the responsive acceptance set at 2560, 1920, 1440, 1366, 1280, tablet, and mobile. The
   final packaged review covered the focused 1920x1080 desktop and targeted geometry contracts; keep
   structural adaptation additive and do not shrink information into illegibility.
7. Measure New Idea cold/warm phases separately. Keep quote/chain prefetch bounded and show named
   progress for a strategy/outcome computation that exceeds two seconds.
8. Exercise Review with MARKET and LIMIT instructions. Confirm that order instruction changes only
   execution price/eligibility/commitment state, never the stored market distribution.
9. Continue enriching the Home market pulse and sector lenses from the same canonical stored-history
   and Scout owners; do not add a parallel browser market-data or recommendation implementation.

Do not erase the standing Practice book or broad observed snapshot merely to obtain a clean-looking
database. They are the daily acceptance corpus that makes Home, Bloom, Book Risk, recommendation
quality, latency, and scenario coherence visible.
