# Program ONE — Session Handoff Document

Written 2026-07-16 ~23:00 PT by the executing agent. Purpose: complete state transfer so any
session or model can pick up mid-program without loss. Read this top to bottom before touching
the tree. Companion state lives in the persistent memory directory
(`~/.claude/projects/-Users-tinker-output-optin/memory/`, index `MEMORY.md`, chiefly
`project-program-one.md`) — but this document is self-sufficient.

---

## 1. The mandate

The owner handed over full execution of **Program ONE** (`STRIKEBENCH_ONE_SPEC.md`, repo root of
`/Users/tinker/output/optin`, branch `feature/journey_refactor`):

> "Take the mantle. Execute plan, fully. Don't stop until you are happy with the outcome, don't
> add duplicate paths in UI, UX or backend, reuse where you can, extend where you have to and
> build new when you must but be thoughtful... Make me proud."

Program ONE runs R0→R6. R4 ("folded depth") and R5 ("folded breadth") fold in Phases 9 and 10
of `TRADER_OWN_SPEC.md` (also at repo root — read §3.x contracts, Phase 9/10 blocks around
lines 241–260, and the journey narratives around line 276).

### Standing owner rules (each was earned through a correction; violating them re-litigates a lost argument)

- **Never stop at milestones.** While ANY task is pending, keep executing in-turn. "What else is
  broken?" means exhaustive sweep AND fix, counted. The owner asked "why did you stop?" four times
  before this stuck.
- **Product-first, always.** Every audit needs a walk-the-journey-as-a-novice product verdict, not
  craft-grading. The owner exploded at "excellent" compliance grading while the UX failed.
- **Elegant SPA / attention model.** The workspace is ONE flow document of 6 bands (view /
  evidence / strategy / outcomes / commit / live). One open band; ready-unfocused bands are
  one-line invitations; locked bands visible with reasons; no yanking scroll position; navigation
  applies in place (the "flow seam" — `App._renderOnce` intercepts same-plan stage navigation, no
  teardown/skeleton).
- **Desktop-first at 5K** (the owner literally runs 5K), graceful degradation down, mobile a
  full-capability secondary plane. BUT width ≠ kitchen sink: "use the real estate judiciously and
  in a manner that makes the product and journeys better."
- **Units on every number.** ("look back window is what unit? 20 days? Weeks?")
- **No bare `<select>` for decision choices** — `UI.segmented` / `UI.chipSet` with consequence
  lines. Disclosure toggles use `aria-pressed`.
- **Two levels only** — Beginner/Expert, a LENS never a fork: same capabilities, different wording
  and density. Every label must survive "what does that mean?" (INFO registry / learn.js
  VOCABULARY; a DOM label audit enforces the primary routes).
- **Never fabricate data.** Heuristics say they are heuristics; missing data says "unavailable";
  ex-dividend dates require a sourced calendar or the surface says so (§3.15). Estimates carry
  `confirmed=false` labels.
- **No back-compat pre-release.** Clean breaks are correct; ONE fingerprint-guarded
  `src/main/resources/db/schema.sql` (no migrations); schema changes are sanctioned and dev/test
  DBs recreate.
- **Commits:** thematic, at gates, message explains the product change. **NEVER add
  Co-Authored-By** (owner said so twice). Targeted tests while iterating; full matrices only at
  final gates.
- **Never remove features; retain-and-improve.** Never reintroduce AI-assist UI (built and then
  removed at owner demand, 2026-07-07).
- **Track running agents**: end every response with a status line while background agents run.

---

## 2. System primer (what you're working on)

StrikeBench: options-education/practice platform. Vanilla-JS SPA (no framework) + Javalin
(Java 21) + Postgres. One jar (`target/strikebench.jar`) serves API + static client from
`src/main/resources/public/`. Key server packages: `eval/` (the ONE DecisionPolicy:
ScoreComposer + profilers + FourOutputAssessment), `recommend/` (RecommendationEngine,
AutoRecommender=Scout, OpportunityScanner), `strategy/` (StrategyFamily/Catalog/Builder,
Guardrails, CoverageCheck), `plan/` (PlanService + the plan workflow services),
`paper/` (accounts, tracked portfolio accounting, campaigns, objectives, book risk),
`sim/` (ScenarioSpec/PathGenerator/ensembles), `api/` (controllers/routes; record-Handlers
pattern), `market/` (providers, universe, hours).

Client: `public/js/` — `views.js` (Desk/home), `views-research.js`, `views-plan.js` (the
workspace flow document), `views-portfolio.js` (Book), `scenario.js` (fan chart + canvas
studio), `builder.js`, `ui.js` (control primitives), `learn.js` (levels + VOCABULARY/INFO
registry), `contracts.js` (routes/horizons).

Nav: Desk (#/home) · Workspace (#/research, #/plan/{id}/{stage}) · Book
(#/portfolio/...) · Data. The plan bar/chips era is DEAD (R3) — plan library lives in a Desk
drawer.

**Test infrastructure:**
- JUnit: `mvn -f /Users/tinker/output/optin/pom.xml test` (~856 tests, needs docker
  `strikebench-db` container up; tests create per-suite DBs via `support/TestDb`).
- DOM: `cd /Users/tinker/output/optin/dom-tests && node --test dom.test.js` (95 tests,
  Playwright; boots the jar on port 7072 with fresh per-suite DBs via `pgtest.js` against the
  `strikebench-db` docker container; **staleness guard** compares `src/main` mtimes vs jar — always
  `mvn -f ... package -DskipTests -q` first). Subsets: `--test-name-pattern "..."`.
- Label audit (in dom.test.js): every visible label on #/home, #/research,
  #/portfolio/positions, #/data/overview must be registry-covered or in
  `dom-tests/label-allowlist.json`.
- Probe browser: boot the jar on port 7073 against a scratch DB for product walks:
  `docker exec strikebench-db psql -U strikebench -d strikebench_dev -c "CREATE DATABASE probe_r4"`
  then `PORT=7073 DB_URL=jdbc:postgresql://localhost:5432/probe_r4 DB_USER=strikebench
  DB_PASSWORD=strikebench FIXTURES_ONLY=true java -jar target/strikebench.jar`.
  The schema fingerprint guard refuses stale DBs — drop/recreate the probe DB after schema changes.

**Hard-won traps (each cost real time this session):**
1. **CWD drift.** The shell resets to a git worktree
   (`.claude/worktrees/trader-own-spec-review-0b4737`). One relative-path edit corrupted the
   worktree's copy instead of the repo. ALWAYS `mvn -f /Users/tinker/output/optin/pom.xml` and
   absolute file paths in scripts.
2. **CSS cascade order.** Appended overrides must come AFTER the rule they beat (equal
   specificity = later wins). A media block placed above a 4-column rule silently lost.
3. **Stale probe servers.** A zombie server from a previous session answered port 7073 with an
   old jar and made new features look broken. `lsof -nP -iTCP:7073 -sTCP:LISTEN -t | xargs kill`
   before booting; check `/api/health` `startedAt`/`jarChangedSinceBoot`.
4. **Never rebuild the jar while a DOM suite runs** (yours or an agent's). The app's
   `jarChangedSinceBoot` guard makes the running server refuse requests → the in-flight suite
   fails. `mvn test`/`compile` are safe (they don't rewrite the jar); `mvn package` is not.
5. **Plan-creation dedupe in tests.** Plans dedupe on (symbol/intent/thesis/horizon/...) —
   two DOM tests sharing default context share ONE plan and leak state. Convention: unique
   `horizonDays` per test (e.g. `openPlan('AAPL','evidence','DIRECTIONAL','bullish','38d')`).
6. **Client catalog parity guards.** `builder.js` and `scenario.js` `applyCatalog` hard-fail if a
   server family/template lacks client mechanics. Any new StrategyFamily needs entries in BOTH
   `TEMPLATE_BUILDERS` (builder.js) and `SCENARIO_LEGS` (scenario.js).
7. **Positional records everywhere.** Growing a record (CreateRequest, EvalContext, Candle...)
   breaks every construction site including tests. Pattern used: add the field at the END and keep
   compatibility constructors delegating with nulls.

---

## 3. Program state: R0–R3 (committed before/early in this stretch)

- **R0 Contracts as code** — state rails, control primitives, label audit, flow scaffolding,
  simulation-spine lineage (one ensemble per plan-context, fingerprint-pinned; lineage chips;
  `previewFromStored` byte-compatible rebuilds).
- **R1 Workspace bands 1–2** — view declaration band, evidence band (optional:true in the
  attention model), fan chart at hypothesis, nav collapse.
- **R2 Workspace bands 3–6** — strategy hero + ranked field, same-fan outcomes, commitment card,
  live strip.
- **R3 Desk + Book re-home** — Desk owns attention/resume/ideas + plan library drawer
  (`#home-plan-drawer`), profit-first Book (h1 "Portfolio"→"Book"), plan chip bar retired with its
  value re-homed, `#/plans` route removed, adoption flow (PlanAdoptionService: ADOPTION receipts
  over existing lots → mid-journey plan), broker promotion (PlanPromotionService, one-tx freeze +
  four-artifact set). Commit `b2187d6`.

Alongside (same tree, earlier actors): TRADER/OWN Phases 1–8 foundations — one Real ledger,
three-axis position model, receipt family (DECISION/ADOPTION/TRANSFORMATION/RESOLUTION),
structure projection tables, pending imports schema, four-output assessment, stance vector,
CampaignMath (§4 formula appendix).

---

## 4. R4 "folded depth" — COMMITTED as `de32fdb` (this session's core work)

One increment: coherence, composites, lenses, campaigns, canvas. Gates: **JUnit 856/856, DOM
95/95**, novice + expert product walks on the probe. Every piece below is in that commit.

### 4.1 Coherence — the headline diagnostic (spec §3.7–§3.9, Phase 9)

**What:** every ranked or exact-assessed structure is judged against what the user DECLARED.

- `eval/DeclaredObjective.java` — the declared side: `{objective, thesis, horizonTradingDays,
  assignmentPreference, source}`. `horizonCalendarDays()` converts sessions→calendar (5≈7)
  because the stance vector's duration is calendar-denominated.
- `eval/StrategyEvaluator.objectiveCoherence(declared, impliedStance, stanceVector)` replaced the
  hardwired UNDECLARED stub. **Direction axis**: declared thesis vs `implied.direction()`
  (match=COHERENT, opposite=INCOHERENT, neutral-mismatch=MIXED); VOLATILE thesis reads
  `implied.volatility()` shape; INCOME reads the carry axis; HEDGE reads `primaryTail`.
  **Duration axis**: structure life vs declared horizon — life < 0.8× horizon → INCOHERENT ("ends
  before the thesis can play out"); > 3× → MIXED; INCOME short cycles = coherent. Verdict = worst
  of the two axes. Reasons quote BOTH sides in plain language and always end with "Economics are
  unchanged by this diagnostic" (four-outputs discipline: coherence never overwrites the other
  three).
- **Threading:** `EvalContext` gained `declared` (+ compat constructor). `EvaluationService.rank()`
  builds `DeclaredObjective(intent, thesis, horizonSessions(horizon), assignmentPreference,
  "this Plan's declared view")`. `assessExact(...)` gained a declared-aware overload used by (a)
  the plan exact-ticket path (`PlanStrategyController`, from plan context) and (b) the
  tracked-account analyze path (`PortfolioController.analyzePackage`, from the account objective —
  source string "this account's declared objective (revision N)"). NON_DIRECTIONAL account
  direction maps to null thesis (the coherence direction axis doesn't know that token).
- **Client:** `views-plan.js` — `coherenceBadge(c)` scan pill, only for MIXED/INCOHERENT
  ("PARTLY OFF-VIEW" badge-caution / "AGAINST YOUR VIEW" badge-danger; COHERENT stays quiet by
  design — the expected state must not add noise), and `coherenceBlock(c)` ("Does this match your
  view?" beginner / "View coherence" expert, with both axes as bullets). Wired into BOTH renderers:
  the beginner/expert `candidateCard`s AND the `ranked-idea` hero/rows (they are separate render
  paths; the hero is what the strategy band actually shows — this was discovered by probe walk,
  not code reading). CSS `.coherence-note` mirrors `.economic-assessment` (left-edge color by
  verdict).
- **Tests:** `ObjectiveCoherenceTest` (12) — every axis, UNDECLARED/UNAVAILABLE honesty,
  reasons quote provenance.

**Why these choices:** the implied side reuses the engine's own stance classification (never
re-derives thresholds, so coherence cannot drift from participation/book math). The pill only
speaks when something is wrong; the hero block sits between economics and "Why this ranks first"
because that's the order a decider reads.

### 4.2 Account objectives (R4.0) + Book card

- `paper/AccountObjectiveService.java` — immutable revision series over `account_objective_revision`
  (schema was ready): `declare()` writes revision N+1 (PROSPECTIVE — history never rewritten),
  `latest()`, `history()`. Objectives INCOME/ACCUMULATE/HEDGE/DIRECTIONAL/CAPITAL_PRESERVATION;
  optional direction; assignment preference AVOID/ACCEPT/PREFER_BELOW_BASIS/SEEK; optional target
  exposure. Validation refusals leave no revision behind.
- API: `GET/POST /api/portfolio/accounts/{id}/objective` (`{latest, history[]}`; invalid values
  → 400 "must be one of").
- **Book card** (built by a subagent, verified in the walk): "What is this account for? ⓘ" right
  under the account headline stats. Undeclared = one-line invitation + Declare button; declared =
  plain-language headline ("Income — collect premium; assignment below basis welcome"), revision
  provenance, Change button, History expandable, green declaration receipt ("revision 1 ...
  earlier revisions stay on record"), and the coupling line ("Every analysis on this account is
  now judged against this objective."). Segmented controls with per-option consequence lines at
  both levels. learn.js vocabulary added.
- Test: `ApiIntegrationTest#accountObjectiveIsAnImmutableRevisionSeriesAppliedProspectively`.

### 4.3 Composite catalog (R4.3)

Three new named, evaluated, defined-risk share-backed structures, end to end:

| Family | Legs | Group |
|---|---|---|
| `COVERED_STRANGLE` | shares + short call + short (cash-secured) put | covered_composite |
| `COVERED_CALL_PUT_SPREAD` | shares + short call + long put + lower short put ("put-spread floor") | covered_composite |
| `COVERED_CALL_CALL_OVERLAY` | shares + short call + higher long call ("upside re-entry") | covered_composite |

Touched: `StrategyFamily` (3 enums + exhaustive `structureGroup()` cases), `StrategyBuilder`
(3 construction cases honoring `BuildHints.sharesHeld` — held-shares variants omit the stock leg,
label "... around/over held shares"), `StrategyCatalog` (family metadata with piecewise payoff
glyphs, `identify()` branches — NOTE the overlay is a 2-option shape and the put-spread floor a
3-option shape; the covered strangle requires put strike < call strike), template copies,
`RecommendationEngine.beginnerText` copy, client `builder.js` TEMPLATE_BUILDERS +
`scenario.js` SCENARIO_LEGS (parity guards demand both).

**THE key engine fix:** `CoverageCheck.callCoverSharesNeeded` used to binary-search on ALL
uncovered shorts — a cash-secured put can never be share-covered, so it returned -1 and
Guardrails saw no cover → covered strangle rejected as "undefined risk". Now cover math is
**call-side-only** (typed scope, `uncoveredCallShorts`, stock-leg validity still included), and
Guardrails' single-expiration branch accepts share-cover when **the combined stock+legs payoff
curve is bounded** (`coverageClean || !combined.maxLossUnbounded()`) — the curve is the arbiter,
not string matching. Cash-secured discipline is still enforced honestly through max-loss ≤ buying
power.

Verified live: under INCOME with held shares all three compete BESIDE the covered call
(`usesHeldShares=true`), and the over-budget cash-secured put stays visible in "Useful structures
above this Plan's budget" with its real one-lot risk. Tests: `CompositeCatalogTest` (3 — build →
identify round-trip, held-shares variants, bounded piecewise payoffs),
`RecommendationEngineTest#heldSharesSurfaceTheCompositeIncomeStructures`,
`StrategyCatalogTest` counts updated (33 templates, 21 scenario-enabled).

### 4.4 Objective lenses (R4.4)

- **Assignment preference as a typed context attribute.** `plan_context_revision` gained
  `assignment_preference` (CHECK constraint); threaded through `Plan.java` records
  (ContextRevision/CreateRequest/ContextUpdateRequest — positional, so ~20 test construction
  sites were mechanically fixed), `PlanService` (create/updateContext/claimIntent, contextHash,
  activeIdentityHash, activeEquivalentOn — the hash/equivalence changes are pre-release-sanctioned),
  API pass-through, and the workspace declare band: an "IF ASSIGNMENT COMES UP" chip row shown
  only for assignment-bearing intents (ACQUIRE/EXIT/INCOME), with per-choice consequence lines.
  Cleared like any assumption (`clear:["assignmentPreference"]` → new revision).
- **"Assignment fit" score component** (`ScoreComposer`): only when a preference is declared AND
  the candidate has assignment exposure. AVOID → value 1−p; SEEK → p; PREFER_BELOW_BASIS → p on
  acquiring structures (short puts), 1−p on share-losing ones; ACCEPT/undeclared → no component.
  Notes quote the declaration ("you declared: avoid assignment — 40% chance...").
- **Lens registry** (`eval/ObjectiveLenses.java`): a lens is DATA — an applicability predicate
  over DeclaredObjective + a contribution function returning named components + plain cautions.
  The next composite objective is a registry entry, not new code paths. First entry
  **income-while-accumulating** (applies when objective ∈ {INCOME, ACCUMULATE} AND preference ∈
  {PREFER_BELOW_BASIS, SEEK}): "Accumulation entry discount" component (deeper below spot = better
  paid bid, full credit at ~15%), income honesty caution ("X% annualized on the strike collateral
  IF this cycle is repeatable — one cycle proves nothing. Redeployed instead of taken, this
  cycle's premium buys ~N more shares at the strike" — this sentence IS the redeploy toggle
  server-side; a UI toggle can later switch which frame leads), NAV-erosion caution (short call
  over shares being accumulated SELLS the position being built), concentration caution
  (post-assignment share of gross book exposure > 25%, using PortfolioExposureContext with its
  basis string). Components flow through ScoreComposer; cautions through Explainer failureModes.
- Tests: `AssignmentPreferenceLensTest` (6), `IncomeWhileAccumulatingLensTest` (6),
  `PlanAssignmentPreferenceApiTest` (declare-with-view → prospective revision → clear → junk 400).

### 4.5 Campaigns (R4.2 — subagent, landed green)

`paper/CampaignService` + `api/CampaignRoutes/Controller` + Book overview card.
Typed member tables only; auto-proposals are **returned, never persisted** (only user confirmation
writes membership) across four evidence tiers (lot/roll-chain fixpoint; plan lineage; structure
revisions; window heuristics whose reasons demand confirmation). §4 assembly: campaign-adjusted
economic basis through scale-ins/exits, realized-vs-headline yield on ONE shared
peak-committed-capital denominator ("annualized only if repeatable"), live-cash + buy-and-hold
counterfactuals (honestly "undefined" when ineligible), dividends by explicit attachment,
interest strictly explicit-tagged (untagged attach → 400 "never auto-assigned"), churn round-trip
pairs, largest-remainder for exact-100% shares. Six learn.js vocabulary entries. Buy-and-hold
dividends omitted WITH a stated treatment note (no sourced dividend calendar — §3.15).

### 4.6 Scenario canvas (R4.5 — two subagents, landed green)

Foundation: `ScenarioSpec.Waypoint(dayIndex, priceRatio, tolerance)` (+14th component, 13-arg
compat), multi-pin conditional fill in `PathGenerator` — **EXACT_CONDITIONAL** for Gaussian
models (textbook conditional sampling: linear mean-shift per pinned segment, free-tail beyond,
bridge-law variance verified in tests), **GUIDED_INTERPOLATION** honesty label for
STUDENT_T/JUMP_DIFFUSION/HESTON/BLOCK_BOOTSTRAP (smooth multiplicative correction — labeled,
never passed off as exact). `plan/AuthoredScenarioService` + `authored_scenario(+_waypoint)`
tables; `plan_ensemble_waypoint` so a reloaded pinned fan can never masquerade as plain Monte
Carlo. Calendar methods (`sessionDates`, `calendarHorizonDays`, `calendarDt`) added WITHOUT
touching legacy `dt()` (stored-fan fingerprint compatibility).

Studio wiring: waypoints flow through the outcomes API (`planScenarioSpec` now honors them;
every ensemble/outcome response carries `waypointFill`); `POST/GET /api/plans/{id}/scenarios`
(save/list/load with fingerprint lineage + stale-context explanations and re-anchoring);
fan-click canvas UI in the outcomes band (click to pin "$X · session N" with real NYSE session
dates, drag, double-click remove, tolerance whiskers for experts, save/load cards with fill
badge + waypoint count). A record-constructor exception mapping surfaces the units-bearing
validation message ("waypoint day 40 lies beyond the scenario horizon of 30 trading days").

### 4.7 Product fixes found by the novice walk (not by tests)

- Ranked hero grid collided at tablet widths → stacks below 980px (`.ranked-idea-grid`), row
  facts 2-col at mid-width — placed AFTER the 4-col rule (cascade).
- Income picture said "Shares available: none" while candidates used the plan's declared 100
  shares → the chip now names its source ("100 AAPL · declared on this Plan") + an explanatory
  line. One screen must not answer one question two ways.
- The horizon segmented control DISPLAYED "1 month" as selected while the draft held null, so
  "Declare the view" scolded users for a visible choice → draft initializes to the displayed
  default.
- DOM suite-order leak: the canvas spec and the older rehearsal spec shared a deduped plan (the
  rehearsal test inherited a pinned ensemble) → rehearsal spec got its own plan (`'38d'`), with a
  comment. (First attempt — giving the CANVAS spec `'43d'` — broke that spec because its explicit
  30-day ensemble and candidate selection are horizon-coupled; reverted, fixed the simpler side.)

---

## 5. R5 "folded breadth" — IN FLIGHT (uncommitted work in the tree right now)

### 5.1 My track: regime + history evidence (spec 10.3) — DONE, 49/49 targeted tests

- **`eval/RegimeSnapshot`** — trend UP/DOWN/SIDEWAYS ±8% over the trailing window (~63 sessions of
  the lane's own candles), drawdown from lookback high, VRP + IV-rank from the shared volatility
  profile, `basis` provenance. **< 30 sessions = trend null ("unknown")** — never guessed.
  `headline()` composes one plain sentence with units ("up 18% over the last 63 sessions ...
  options are pricing more movement than the stock has delivered (rich premium)").
- **`eval/RegimeProfiler`** — the blunt classifier (deliberately NOT a signal; it's framing).
- `EvalContext` gained `regime` and `trailingCloses` (17 components now; compat constructors:
  14-arg undeclared / 15-arg declared / full). `buildContext` computes both from the SAME lane
  (`worldId`-aware candles; "this simulated world's sessions" vs "observed sessions" basis).
- **Explainer regime cautions** (warnings ONLY — `regimeNeverChangesTheScore` test proves rank
  and decision score identical with/without regime): down-trend + short puts → "the discount is
  widening, but assignment odds rise while it falls... judge whether it is enough (Trend
  heuristic: ...)"; up-trend >10% + capped structure with terminal upside capture < 50%
  (participation profile, now computed BEFORE the explainer and passed in) → CSP-regret
  pre-emption ("capped income lags simply holding shares — deliberate income is fine; accidental
  capping is regret").
- **Research surface:** `/api/research/{symbol}` response gained `regime`
  (`ApiResponses.Regime.of(snapshot)` — headline pre-composed server-side), rendered as a muted
  line in the research hero (`views-research.js`), title=basis.
- **`eval/HistoryFit`** — historical structure-fit sentences in Explainer assumptions:
  defined-range structures (≥2 breakevens) get terminal containment over overlapping DTE-length
  windows of `trailingCloses` ("stayed inside this range ($L to $U, −x% to +y%) N% of the time.
  History is not a forecast — it is the same market that set these prices") + expected-move
  coverage ("half-width is a%, b× the options-implied expected move"); one-sided structures get
  the breakeven as a percentile of delivered |moves|. **Thin history (< 60+DTE closes) yields
  NOTHING** rather than a guess. Note: containment is RELATIVE terminal moves per window — a
  ranging path scores ~65-70%, not 95%; the discriminating property (tested) is ranging ≫
  trending for the same range.
- **`recommend/CompensationView`** (shared) — "premium per unit of realized risk", ranked BESIDE
  the decision order, never replacing it (the basis string says exactly that). Premium
  collectors only. Named components: Annualized premium yield 0.40 (collateral yield when
  share/strike-backed, else `capital().annualizedRocPct()` "on the risk capital" — fallback added
  because credit spreads carry no collateral yield), VRP 0.20, Gap risk 0.15 (new
  `EvaluationService.gapFrequency(symbol, worldId)` — fraction of sessions opening >2% from prior
  close over ~90 sessions, null when thin → neutral 0.5 with an honest note), Liquidity 0.15,
  Capital efficiency 0.10. Earnings proximity deliberately NOT a component (candidates carry
  their own earnings warnings; the basis string discloses this).
  Wired into BOTH `OpportunityScanner.ScanResult` (+`/api/opportunities` envelope) and
  `AutoRecommender.AutoResult` (compat constructors) — the Scout the user actually touches is
  AutoRecommender via `/api/research/scout`, which serializes AutoResult directly.
  UI: expandable "Compensation view — premium per unit of realized risk" table on the research
  Scout results (rank/symbol/structure/score/why, component notes in the Why column and row
  title).
- Tests: `RegimeLensTest` (5), `HistoryFitTest` (4), `AutoRecommenderTest` (+1 compensation,
  15 total), all neighborhood suites green (49 across RegimeLens/HistoryFit/AutoRecommender/
  RecommendationEngine).

**Spec fragments of 10.3 I consider covered by the above:** regime lens conditioning, CSP-regret
flag, compensation ranking beside, containment scoring, percentile strikes (the breakeven
percentile IS the strike-percentile in payoff terms), expected-move coverage, history≠forecast
badges (sentence labels), ex-div "unavailable" (already honest on the research surface).

### 5.2 Agent track A: Alert Center (spec 10.1) — RUNNING at handoff time

Mandate (full text was given to the agent; reproduce if relaunching): compute attention items
from real state per user across lanes — protocol breaches (reuse the Manage band's trigger
evaluation, ONE evaluator; extract if inline), expiries today/this week with notional, earnings
proximity estimate-labeled, pin risk (spot within ~1% of a short strike ≤3 sessions to expiry,
labeled heuristic), assignment events (short ITM, extrinsic < fees — the generalized early
assignment warning), unresolved imports. Each alert: kind, severity (info/attention/urgent),
plain-language headline, entity, deep-link route. `GET /api/alerts`; EventBus event on material
change (NO new SSE channel — reuse `/api/events`); nav badge on Desk; **the Desk attention rail
ORDERS BY IT** (that's the spec's point); expiry/earnings rows open family-appropriate decision
helpers (deep-link existing Manage flows; build only the assignment-acceptance teaching preview
modal: campaign-adjusted basis + concentration + next step, records nothing). Ex-div rows say
"unavailable" (no sourced calendar). JUnit per alert kind + one DOM spec (ordering, badge, deep
link). Told to avoid `views-portfolio.js` (agent B owns it) and eval/recommend (mine).

### 5.3 Agent track B: Book-risk lane (spec 10.2) — RUNNING at handoff time

Mandate: computed FROM LOTS DIRECTLY (never structures), per-account first then cross-account,
side-by-side lanes. Net dollar delta/vega/gamma per account; **beta-weighted dollar delta**
(betas from `underlying_bar` returns vs SPY with coverage disclosure; raw share delta retired
from cross-symbol aggregates); stressed assignment capital ("sector −10% → short puts obligate
$N vs $M cash"); expiry-concentration calendar ("$X notional expires 8/07"); sector/theme
concentration labeled as CLASSIFICATION (13-sector map is a theme map); intra-theme
contradiction vs the account's declared objective direction (reads
`AccountObjectiveService.latest`); churn/whipsaw per §4 (reuse CampaignMath pairing). Greeks
disclosure when marks are missing ("aggregated over 14 of 16 option lots"). `paper/BookRiskService`
+ `GET /api/portfolio/book-risk` + a Book tab/lane (spec: "elevated to a destination").
JUnit seeded like battery scenario 6 (the Vanguard concentrated book) + one DOM spec.
**Its PortfolioController/Routes wiring already landed in the tree** (constructor takes
BookRiskService; `bookRisk` handler registered) — the service/UI/tests were still in flight at
handoff.

### 5.4 Queued: broker imports + pending resolution (spec 10.4 + journey V)

After track A lands (shares alert surfaces): statement paste/parse → package nets land as
**pending imports** (fingerprint key; `portfolio_import_pending` table + reconciliation trigger
already exist in schema) → batch-adopt (ADOPTION receipts) → resolve each way
(BROKER_REPORTED vs USER_ALLOCATED) with the tax-withholding difference VISIBLE (pending
permits package-net economics; withholds per-leg tax basis until resolution — campaigns already
implement the withholding side). Unresolved imports feed the alert center and the campaign
pending tier.

### 5.5 R5 gate (when A + B land)

1. Integrate; read both agents' reports; walk their surfaces in the probe as a novice
   (both levels, desktop + 390px).
2. Full JUnit + full DOM (rebuild jar first; expect >856 / >95).
3. Label audit will sweep #/home and #/portfolio/positions — the agents were told to add
   vocabulary; verify.
4. Commit R5 as one increment (same style as `de32fdb`; NO Co-Authored-By).
5. Launch R5.4 imports; then R6.

---

## 6. R6 (not started) — close-out

- **Sentiment versioning** (spec 10.5): per-headline sentiment chips ("keyword-derived", honest),
  aggregate trend, event-risk feed; the scorer gains a VERSION STAMP (deterministic but unversioned
  today — persisted sentiment-derived conclusions must record scorer version).
- **Learn surface** aggregated from the INFO registry + strategy guides with "use this in a Plan"
  deep links; the explanation audit should FAIL THE BUILD on any visible label lacking both-level
  coverage, including every program-introduced concept (the spec block lists them: participation,
  implied stance, stance vector, realized-vs-headline yield, campaign-adjusted economic basis,
  tracked tax basis, campaign, coverage receipt, fresh-eyes, adoption, thesis, objective,
  coherence, book-risk, churn, waypoint, bridge fill, guided interpolation, pending import,
  resolution, alert center, provenance words).
- **Scenario battery 1–22** (spec ~line 260+; includes the journey narratives B, C, D, E, V at
  line ~276). Several are already satisfied by landed work; each needs an explicit
  verification pass with evidence. Battery 6 (Vanguard book) is the book-risk agent's seeded
  test; journey V is R5.4.
- **Final full matrix + fresh screenshots + final novice walk** at both levels. The DOM suites
  regenerate `dom-tests/shots/*` — those ARE the screenshot evidence.

---

## 7. Open items / known loose ends (owner-visible honesty)

1. **Two agents in flight** (alert center, book-risk). If this session dies, their processes die
   with it; their PARTIAL work may be on disk uncommitted. `git status` + `git diff` to see what
   landed; their mandates are §5.2/§5.3 above — relaunch with the same text if incomplete.
   Book-risk had already landed its controller/route wiring; if `BookRiskService` is missing or
   half-built, either finish it to the mandate or revert the PortfolioController/Routes hunks.
2. **My uncommitted R5.3 work** is in the tree (files: `eval/RegimeSnapshot|RegimeProfiler|
   HistoryFit`, `recommend/CompensationView`, edits to `EvalContext|EvaluationService|Explainer|
   StrategyEvaluator|OpportunityScanner|AutoRecommender|ApiResponses|DiscoveryController|
   ResearchController`, `views-research.js`, tests `RegimeLensTest|HistoryFitTest|
   AutoRecommenderTest`). All targeted-green; NOT yet through a full matrix (that's the R5 gate).
3. **Redeploy toggle UI**: server-side redeploy framing shipped (lens caution sentence). If the
   owner wants a literal UI toggle switching the income presentation, it's a small Book/analyze
   display change.
4. **Regime `eventSoon`** is false at the eval layer (events live at the API layer; candidates
   carry their own earnings warnings; the research surface computes it for the regime wire
   object). If regime-level event framing is wanted deeper, wire EventService into
   EvaluationService.
5. **Compensation view on the plan-scout** (`planScoutRun` per-plan alternatives): deliberately
   NOT added — the compensation view targets the universe Scout (that was the spec's surface);
   the plan scout ranks alternatives for an existing plan. Add later if the owner asks.
6. **Exact-ticket coherence via TradeController's ExactAssessment override interface**: the
   PLAN exact path and ACCOUNT analyze path are threaded; the raw practice-ticket path
   (TradeController) still assesses undeclared. Low value (no declaration exists there) but
   noted.
7. **Deferred fix list from the TRADER/OWN audit** (pre-Program-ONE): lives in memory
   `project-trader-own.md` — owner ordered it deferred until program completion; it becomes
   relevant again at R6 close-out. Also `project-trader-own-remediation.md` — a 60+ item ledger
   to hand to the "junior" AFTER completion.
8. **Prod deployment** (strikebench.com) untouched all program — everything is on
   `feature/journey_refactor`, NOT deployed. Deployment notes in memory
   `reference-strikebench-deployment.md`.

---

## 8. How to resume (exact sequence for a fresh session)

1. `cd /Users/tinker/output/optin && git log --oneline -3 && git status --short` — orient.
   Expect `de32fdb` at or near HEAD of this stretch; uncommitted R5 work per §5/§7.
2. Read memory `project-program-one.md` + this file. The task board (harness tasks #1–#7) says
   R5 in_progress, R6 pending.
3. If the R5 agents' work is complete on disk (BookRiskService + AlertCenterService + UIs +
   tests exist and compile): run their targeted tests, walk their surfaces in the probe, then the
   R5 gate (§5.5). If half-landed: finish per mandates or cleanly revert their hunks and
   relaunch.
4. `docker ps` — needs `strikebench-db` up. Probe DB may need recreation after schema changes
   (fingerprint guard tells you).
5. Full gates before any commit: `mvn -f /Users/tinker/output/optin/pom.xml test` then
   `mvn ... package -DskipTests -q && cd dom-tests && node --test dom.test.js`.
6. Never stop at a milestone; end responses with a status line while agents run; walk the product
   as a novice before calling anything done.

— End of handoff.
