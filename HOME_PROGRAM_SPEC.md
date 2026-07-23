# Home Program Spec — three pillars, five problems

Date: 2026-07-23 · Branch: `feature/journey_refactor` · Status: **DRAFT for owner review, no code yet.**

Companion to `POSITION_LIFECYCLE_SPEC.md` and `CALIBRATION_ENGINE_SPEC.md`. This spec sequences the
program the owner set: consolidate the product into **three areas, one canonical implementation
each**, and fix the five concrete failures riding on today's sprawl. Every claim below is anchored to
`file:line` from a read of the current tree so the plan is executable, not aspirational.

---

## 0. The architecture: three pillars, strict seams

Today the same capability is implemented several times over on all three sides — that duplication is
the root of the fragile scans, the "useless" recommendations, and the "funeral home" Home. The fix is
one canonical implementation per area, each **internally structured into clear components** (not a
single god-class — that is just sprawl of another kind), with strict seams:

```
┌────────────────────┐     reads warm data      ┌────────────────────┐   renders/interacts   ┌──────────┐
│ Calculation Engine │ ───────────────────────▶ │  Data & Feeds svc  │ ◀──────────────────── │  UI / UX │
│  (all math)        │ ◀─────────────────────── │  (all I/O + state) │   via the bridge      │ (client) │
└────────────────────┘   returns computed math  └────────────────────┘                       └──────────┘
```

- **Data & Feeds (one service)** — fetch, streaming, job semantics, resume, in-memory cache, DB cache,
  provider politeness, the providers. Holds/serves/streams data; **computes no recommendations.**
- **Calculation Engine (one engine)** — candidate generation, evaluation, ranking, verdicts, Monte
  Carlo. Computes; **touches no provider or cache directly — it asks Data.**
- **UI / UX (one client + one design system)** — the desk (`index.html`) + the bridge
  (`desk-backend.js`/`api.js`). Renders and interacts; **fetches and computes nothing itself.**

**Seam rule:** Engine reads Data; UI reads Engine + Data through the bridge; no layer reaches around
another. This makes each pillar independently fixable and testable.

> Note on prior guidance: earlier in the program the standing rule was "no backend refactor now."
> The owner has now explicitly asked for this consolidation, which supersedes that stance for the
> Data and Engine pillars. The `no-sprawl / one canonical definition` rule still governs: we
> **consolidate the implementation and keep every capability** — nothing is removed, only de-duplicated.

The five problems map onto the three pillars:

| # | Problem (owner's words) | Pillar |
|---|---|---|
| 1 | Scan hammers the provider, restarts on retry, lost on navigate, results not saved | **Data & Feeds** |
| 2 | "Useless recommendations with no income or bad fit"; "why fourteen engines?" | **Calculation Engine** |
| 3 | "So much space wasted"; whole Home layout | **UI / UX** |
| 4 | "Funeral home" vs "terminal"; buttons/sector-selectors/CTA colors/labels differ from New Idea | **UI / UX** |
| 5 | New Idea button placement + the disruptive bounce-to-Home from Decide/Position | **UI / UX** |

---

## 1. Pillar 1 — Data & Feeds service

### 1.1 What it owns (collapse map)

One service, internally split into **Providers · Cache · Feed · Jobs · Stream**, absorbing today's
scattered pieces (no new service — these fold together):

| Capability | Today (to fold in) | Evidence |
|---|---|---|
| Live read-through | `MarketDataService` | `chain()`/`quote()`/`loadCandles()` read-through |
| Warm in-memory loop | `MarketDataEngine` | 20s/300s refresh, warm store, singleflight |
| DB persistence | `SnapshotService` → `option_bar`/`underlying_bar`; `MarketSnapshotStore` → `market_snapshot`; candle/quote stores | `SnapshotService.java:70-104`, `V1__baseline.sql` tables |
| Scheduled resumable sync | `DataSyncScheduler` (`sync_underlying`, coverageHash resume) | `DataSyncScheduler.java:56,95-126` |
| **Job semantics** | `DataJobService` — cancellable, resumable, idempotent, per-item progress, `reconcileOnBoot()`, `retry()`=resume, EventBus progress | `DataJobService.java:28-29,110,118,219,227` |
| Politeness | `ProviderPoliteness` **+** `CboeProvider`'s separate inline gate → **one governor** | `ProviderPoliteness.java:12-13`, `CboeProvider.java:82-84` |
| Providers | Cboe, Yahoo behind one provider interface | — |
| Streaming | `MarketFrameBroadcaster`/SSE **+** the ad-hoc scout NDJSON stream → **one streaming facet** | `MarketFrameBroadcaster.java`, `DiscoveryController` scout stream |
| Caches | in-memory (`chainCache`, `payloadCache`, engine warm store) + DB (`market_snapshot`, `underlying_bar`, `option_bar`) | `MarketDataService.java:457-463,66-67`, `CboeProvider.java:58-60` |

### 1.2 The scan as a job inside this service (Problem 1)

The scout scan (`/api/research/scout` → `AutoRecommender`, run **inline in the HTTP thread**) becomes
a **job kind within the one Data service** — reusing the existing job engine, not a new one:

- **Tracked / resumable / restart-safe / retry=resume** come free from the existing job engine
  (`start`/`run`/`cancel`/`retry`/`reconcileOnBoot`/`publishJob`).
- **Per-symbol checkpoint + partial results** persist to `data_job_item` + `strategy_evaluation`
  (call the existing `EvaluationService.persist()` the scout path skips today). A retry skips
  already-scored symbols via a per-symbol cursor (copy `data_sync_cursor`/`DataSyncState`).
- **Seamless availability:** the finished ranked result persists as an owner-scoped, TTL'd claim
  (model on `research_ensemble_receipt`); the client re-reads it after navigating away.
- `/api/research/scout` becomes a thin **start-or-attach** adapter returning a job id; the client
  subscribes over the existing SSE and re-attaches on return. The NDJSON stream stays only as a
  convenience "watch live" view — the persisted job is the source of truth.

### 1.3 The warm chain store — the real fix for the 429/exhaustion

Root cause confirmed: the scan fans out virtual threads calling `MarketDataService.chain()` **live per
symbol**; every Cboe call returns a full-chain payload; the inline gate (2 concurrent, 1.2s spacing,
15-min cooldown) *paces* but doesn't *reduce* ~105 heavy downloads, so Cloudflare's 1015 trips ~symbol
90, and nothing persists so the cooldown retry re-fetches from zero. (`RecommendationEngine.java:1191`,
`CboeProvider.java:289-294`.)

The scan must read a **warm persisted chain store** instead of hitting the provider live:

- Add a **store-first branch** to `chain()`/`expirations()` backed by an adapted
  `StoredHistoricalOptionsProvider` (it already reads `option_bar` into an `OptionChain`), mirroring the
  read-through `loadCandles()` already uses (`MarketDataService.java:496-544`).
- **Feed:** schedule the existing `SnapshotService` (the `startSnapshotScheduler` harness exists, just
  off) to **trickle the whole universe slowly**, budget-governed by the unified politeness governor —
  so provider I/O happens once, continuously, politely, and a full scan is warm. (Owner's pick.)
- **Freshness:** `option_bar` is date-granular today; add an intraday freshness/`captured_at` column so
  a chain can be marked minutes-fresh. **On-demand refresh** (owner's pick): a scan targeting a symbol
  whose chain is older than N minutes triggers a single governed refresh of that symbol before scoring —
  fresh where it matters, no burst.

### 1.4 Gaps to build

Warm live-chain store + freshness column; scheduled chain ingestion (feed); per-symbol scan cursor;
the scan job kind; scan-result persistence + a re-readable "finished scan" endpoint; unify Cboe's
inline politeness into the shared governor.

---

## 2. Pillar 2 — Calculation Engine

### 2.1 The census (16 services, 3 real duplicates)

The pipeline is ~16 services in three layers. Only the **three cross-symbol orchestrators are true
duplicates**; the profilers are genuinely distinct single-responsibility units.

- **Generation:** `RecommendationEngine` (single-symbol candidate builder) is wrapped by **three**
  overlapping orchestrators running the *same* `recommend()`→`EvaluationService.evaluate()` backbone
  with **three different candidate-selection rules**:
  - `AutoRecommender` — Home "Find your next trade" / Scout (`/api/research/scout`)
  - `OpportunityScanner` — Portfolio (`/api/optimize`)
  - the inline Decide path (`decisionCompetition`/`decisionRanked`) — "New Idea"
- **Evaluation (keep distinct):** `EvaluationService` orchestrating ~11 pure profilers (Risk / Capital /
  Volatility / Regime / Stance / Evidence + `ScoreComposer` + `EconomicAssessment` + `ManagementPlanner`
  + `Explainer`).
- **Decision (keep distinct):** `StrategyEvaluation.decisionScore` + `EconomicAssessment.Verdict`,
  plus `RedeploymentFrontier`, `CompensationView`, `RiskBudgetPolicy`, `DecisionDeclarationPolicy`,
  `SignalEngine`, `PortfolioOptimizer`, `ResearchQuestionEngine`.

**Consolidation:** extract one canonical primitive
`scanSymbol(symbol, intent, thesis, horizon, riskMode, holdings, world) → List<StrategyEvaluation>`
that does `recommend()`→`evaluate()`→best-package-per-family **once, with one selection rule**. The
three orchestrators become thin callers (Scout keeps its distinctive front-end: SignalEngine ranking,
multi-intent/horizon fan-out, progress streaming, holdings resolution; Portfolio feeds `PortfolioOptimizer`;
Decide calls it with a one-symbol list). This kills the divergent selection rules so **the same symbol
yields the same best idea on every surface.** Quick win first: lift the identical
`CompensationView`+`RedeploymentFrontier` tail (`AutoRecommender.java:355-367` ≡
`OpportunityScanner.java:130-135`) into one `composeBookLayer(...)` helper.

### 2.2 Why recommendations come back "no income / bad fit"

The old "market-implied EV always negative forces MIXED" bug is **fixed** (`EconomicAssessment.java:154-177`
bars market EV from vetoing) — but it was replaced by a **single fragile gate**: the realized-volatility
EV lane. FAVORABLE now requires `realizedNet > materiality` AND end-to-end observed evidence, and
`realizedVol` is **null** whenever daily history is thin (<20 observed candles) OR the structure is
multi-expiration (calendars). When that lane is null, the largest score weight (EV, 0.35) goes neutral
(`ScoreComposer.java:73-76`), every candidate collapses to MIXED/UNAVAILABLE, nothing is endorsed, and
ranking degrades to POP/liquidity — exactly "useless recommendations with no income."

**Fixes, highest-lever first:**

1. **Two-axis verdict (biggest lever).** Split the single verdict into an **economic tier**
   (favorable / mixed / unfavorable) **× evidence badge** (observed / modeled / simulated). A genuine
   positive-EV income idea on modeled evidence today collapses to MIXED "promising model, incomplete
   evidence" and hides (`EconomicAssessment.java:183-190`); instead Scout surfaces "favorable · modeled"
   income **ranked above** mixed ones. This alone is the single biggest fix for "no income shows up."
2. **Fallback realistic-measure lane.** When observed daily history <20 candles, fall back to a longer
   window or an IV-implied realized proxy with an honest evidence downgrade; when a symbol truly has no
   history, allow a bounded "watchlist candidate" instead of a dead UNAVAILABLE.
   (`EvaluationService.realizedVol30:350`, `EconomicAssessment.assess:105-177`.)
3. **Calendar/multi-expiry EV lane.** Calendars serve INCOME but `RiskProfiler.java:84` hard-nulls their
   realized-vol EV, so they can never be endorsed — wire the two-expiry path valuation
   (`PathEnsembleService`, referenced at `RiskProfiler.java:95`), or exclude calendars from the INCOME
   menu until the lane exists so the menu stops implying an unreachable endorsement.
4. **Ranking degrades gracefully.** When the realized lane is null, rank income candidates by a
   transparent premium-richness proxy (IV-vs-HV gap × credit-to-width, already in `CompensationView`),
   labeled as such — not a flat neutral 0.5.
5. **Materiality re-scope.** `EconomicAssessment.realisticMaterialityCents:239-249` scales the hurdle by
   full collateral, so a normal cash-secured put must clear ~$85-90 of edge. For collateral-based INCOME,
   base the hurdle on premium-at-risk / annualized-yield. Also align the "assumed 45-day" comment with the
   actual 35-day MONTH default (`RecommendationEngine.java:1154`).
6. **Surface *why* nothing was endorsed.** `AutoRecommender` already computes `noFavorableNote:457-474`
   ("mechanically ineligible" vs "needs daily history" vs "no favorable after costs") — surface it on the
   Home workbench so the owner sees "no observed daily history for X," an actionable data-coverage prompt,
   instead of a silent empty result.
7. **Per-family reachability fixture matrix.** For each INCOME-serving family, assert the realized-vol
   lane is reachable and a favorable verdict is *possible* given rich enough premium — a test that would
   have caught "income scans return no income" before the owner did.

Note the tie-in: Fix 2/6 above are exactly why the live 7070 scan returned all "Economics Unavailable"
— thin history + provider-exhausted chains. The Data warm-store (Pillar 1) removes the provider half;
these fixes remove the endorsement-math half.

---

## 3. Pillar 3 — UI / UX

**One design system, applied identically across Home / New Idea / Position; delete the divergent
per-screen styling.** The probe confirmed both surfaces already draw from the *same* token palette
(`app.css:4-10`) and even share the `.objchip` control — the divergence is *how each spends the tokens*.
Decide spends the cyan accent (`--accent #39C7D4`) as saturated **ink** (glowing gradient CTA,
accent-washed cards, bright mono values, crisp `--line` frames, accent selection) — the "terminal"
feel. Home spends the same accent only as 10%-opacity **wash** behind grey text, frames everything in
the near-invisible `--line-soft`, types tickers in sans, and lets its two most action-bearing controls
recede. That is the "funeral home."

### 3.1 Track C — visual/terminal parity (targeted, not a rebuild)

| Element | Decide (terminal) | Home (today) | Fix |
|---|---|---|---|
| Primary CTA | glowing accent gradient `.btn.pri` (`app.css:47`) | Analyze collapses to grey `.btn.ghost` when empty (`index.html:5230,1126`) | Always `btn pri`; use `disabled` + `.btn.pri:disabled{opacity:.5;saturate(.55)}` — never a dead grey ghost |
| Sector selector | bright framed rows, never ghost pills | pills with `--line-soft` border on `--panel-2` fill = invisible (`index.html:3030`) | border `--line-soft`→`--line`, fill `--panel-2`→`#131a24`, text `--f-2xs`→`--f-xs`, radius `999px`→`6px` — read as segmented buttons |
| Scan states | — | loading/error drop `pri`→flat grey (`index.html:5261,5264`) | keep `btn pri`; loading uses the disabled-dim rule → workbench never goes grey mid-scan |
| Secondary buttons | accent-outline `.microcta`/`.addleg` (`index.html:2183,2067`) | grey `.btn.ghost`/`.btn.xs` | accent-outline treatment in the workbench |
| Ticker input | bright mono | sans (`index.html:3013`) | `font:...var(--mono)`, one size up; keep the accent focus ring |
| Field label | accent/paired-with-value | lone `--faint` grey "Ticker or sector" (`index.html:3012`) = reads disabled | promote `--faint`→`--dim` or accent eyebrow; show the chosen symbol in bright mono |
| Panel frames | crisp `--line` + accent selection bar (`index.html:1841`) | uniform `--line-soft` (`index.html:2999,3011,1049`) | `--line-soft`→`--line`; raised cards get `--raise` — **highest-leverage change against the flat read** |
| Result rows | live `.fanr.sel`/`.pick` accent selection (`index.html:1841-1844`) | passive table | give `.opportunityrow` `--sel-bar`/`--sel-wash` hover/active — a live terminal list, not a passive table |

The Goal/View/Horizon/Risk controls already reuse Decide's `.objchip` (`index.html:5212`), proving the
fix is a targeted parity pass on the 8 rows above — not a rebuild.

### 3.2 Track B — Home layout & the wasted space

Measured at 1920×1080 (bottom row 553px tall): Working ideas fills **56%** of its 307×553 column, the
roster holds **1 card** in another 307×553 column, the single-position futures fan takes a big
**466×553**, while the market band + option chain (owner wants it **bigger**) is squeezed into the 368px
top row. The idle compose panel stretches ~250px of content across 525px — my earlier "gap fill"
(growing the goal box, pinning the CTA) spread the emptiness instead of removing it.

Direction (grounded in the owner's stated budget: chain BIGGER; positions + working ideas = small rails;
pulse + book-futures smaller):
- **Compose is compact, not stretched.** Size the idle workbench to its content; do not reserve
  results-height when there are no results. Reclaimed vertical space goes to the market band / option
  chain.
- **Rebalance the rows** so the chain/market gets more height than the sparse work rails.
- **Adaptive rails:** at low counts, Working ideas + roster collapse/merge rather than each holding a
  half-empty 307×553 column.
- **Shrink the single-position futures fan;** it does not need 466×553 for one position.
- One dense panel grammar (Track C frames) so full panels read as terminal, not flat.

### 3.3 Track D — the New Idea button & the Position flow

Confirmed handler (`index.html:9557`): from **any** view the New Idea button does
`if(decide)exitDecide(); go('book'); focus(workbench input)` — i.e. it always bounces to Home and
focuses a field lower on the screen. Two facts make a better design available: the top bar already has a
persistent command input (`cmdEl`/`cmdSym`, `index.html:9553`), and Decide already has an **in-place**
underlying picker (`decide.pickOpen`/`univq`) rather than a bounce.

Redesign by context (one intent per destination):
- **From Home:** focus the workbench, but make the focus-shift deliberate (the compose surface lifts /
  the input highlights) — not a silent focus ring on a distant field. Better: the top-bar New Idea button
  focuses/opens the **command bar** that sits right next to it (target adjacent to button → the
  disconnect disappears); typing a symbol there opens the composer.
- **From Decide:** New Idea **resets the composer in place** via the existing `univq` picker — it does
  **not** exit to Home.
- **From a Position:** do **not** bounce to Home. Open a contextual **"what can I do with {SYM}?"**
  surface (the owner's option b, enriched): preload the idea surface for that underlying with a scouted
  set spanning **both** managing the held position (roll / trim / defend per its lifecycle verdict) **and**
  fresh viable ideas across income / hedge / acquire / directional. Reuse what exists: `authForkPositionLegs`
  (fork-on-touch, already built), `enterDecide` seeding, `symbolContext`. (The owner's option a — inline
  input in the position — is the lighter fallback if the full surface is too big for phase one.)

---

## 4. Sequencing

Ordered by dependency and impact; each phase independently shippable and gated. UI is decoupled from the
backend pillars and can run in parallel.

| Phase | Track | Deliverable | Gate |
|---|---|---|---|
| **P0** | Engine | Endorsement-math fixes #1 (two-axis verdict) + #2 (fallback lane) + #6 (surface the reason) | A per-family fixture matrix (#7) shows INCOME reaching FAVORABLE on rich premium; Home shows favorable-modeled income instead of empty |
| **P1** | UI/UX | Track C visual parity + Track B compact-compose/reclaim (no backend dep) | Home reads "terminal"; no wasted-space voids; DOM suite green; both scales verified |
| **P2** | Data | Warm chain store + store-first read + trickle feed + freshness/on-demand refresh | A full-universe scan completes with **zero 429s** reading warm data; analyze never starved |
| **P3** | Data | Scout becomes a resumable job (persist partials + cursor + re-readable result + SSE re-attach) | Navigate away mid-scan and return → progress + partials restored; retry resumes; survives restart |
| **P4** | Engine | Merge the 3 orchestrators into `scanSymbol`; fixes #3/#4/#5; `composeBookLayer` quick win | Same symbol → same best idea on Home/Decide/Portfolio; equivalence suite green |
| **P5** | UI/UX | Track D — New Idea button per-context + Position "what can I do with SYM" surface | New Idea from Decide/Position no longer bounces to Home; Position→idea is in-context |

Rationale for P0 first: bad recommendations make everything else moot — the owner should see *good
income ideas* before we polish how they're delivered. P1 (pure UI) can land alongside P0. P2 before P3
(a job over a still-live-fetching scan is only half the win). P4/P5 build on the earlier pillars.

---

## 5. Open decisions for the owner

1. **New Idea button destination (Track D).** Preference between: (a) top-bar button focuses the
   adjacent **command bar** (kills the disconnect, unifies compose), vs (b) keep it opening the Home
   workbench but make the focus-shift deliberate. My lean: (a).
2. **Position→idea surface depth (Track D/P5).** Full "what can I do with SYM" surface (manage + fresh
   ideas across intents) now, or the lighter inline-input-in-position first and the rich surface later?
3. **Calendars in the INCOME menu (Engine #3).** Build the multi-expiry EV lane so calendars can be
   endorsed, or exclude calendars from INCOME until that lane exists? (Exclude is faster and stops the
   menu implying an unreachable endorsement.)
4. **Cboe politeness unification (Data).** Fold Cboe's inline gate into the shared `ProviderPoliteness`
   now (cleaner, one governor) or leave it until after the warm-store lands (lower risk)?
5. **Sequencing check.** Is P0 (recommendation quality) first the right call, or do you want the visual
   parity (P1) landed first since it's the most visible?
