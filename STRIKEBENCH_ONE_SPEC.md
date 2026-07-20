# Program ONE — unified product specification

*Owner-approved 2026-07-16. Supersedes: the UI/UX portions of `TRADER_OWN_SPEC.md` (its §3 domain contracts,
§4 formulas, and delivered phases 1–5 stand untouched), all of `TRADER_OWN_COURSE_CORRECTION.md`
except the invariant shortlist junior is finishing now, and every prior IA. One spec to run
everything remaining: the UX re-architecture AND TRADER/OWN phases 6–10, folded. Grounded in
the 2026-07-16 surveys: full control inventory, level-flip census, stage-content truth table.*

---

## 1. Why this program

The product failed its own mission test: **it became more complex than the options it exists to
simplify.** The mechanism is named and evidenced: the UI is the org chart. Each program built an
engine and every engine got a *place* — five top tabs plus a plan chip bar (three lenses on one
PlanStore), six stage-rail items, five Strategy tool tabs with tabs inside them, and default
listboxes for every parameter. Retain-and-improve was misread as retain-every-*surface*; the
level ladder forked controls instead of deepening one set; and simulation — the product's crown
jewel — became two apparent *destinations* instead of the fabric.

The truth table proves the fix is cheap on the engine side: the ten destinations perform **four
real jobs** (inspect a market · test a view and a package · commit · review), the worst
"duplications" are literally one function rendering five places, and Evidence/Outcomes already
share one fingerprint-pinned path ensemble byte-for-byte. The complexity is presentational. This
program re-homes surfaces; it deletes no engine and no capability.

**Standing rules carried forward unchanged:** everything in TRADER_OWN_SPEC §1 (deterministic
only, exact cents, typed columns, honesty-first, thin-data never weakens engines, one Real
ledger, no manufactured tax basis, retain-and-improve *of capability*), the §3 domain contracts,
the §4 formula appendix, profit-first tracked book, schema-reset policy. New standing rule:
**every phase ships with a product verdict — walk the primary journey as a novice human and
grade simplicity — separate from and equal to contract compliance.**

## 2. The architecture

### 2.1 Three destinations (plus back-of-house)

- **Desk** — a true home: current Book condition and what needs attention, the market/research
  context required to interpret it, what to resume, and today's argued ideas. It composes canonical
  owners read-only; actions enter those owners rather than becoming Desk-owned journeys. (Plan chip
  bar retired; Desk resume cards do that job. Plans-library becomes Desk's archive drawer.)
- **Workspace** — THE journey (§3). Public Research dissolves into its open beginning: exploring
  a symbol IS the journey before commitment (the truth table shows Understand already *is* the
  Research renderer — the merge is chrome removal, not surgery).
- **Book** — the portfolio: practice + tracked, profit-first, campaigns' home.
- *Data* stays back-of-house (admin).

### 2.2 The Workspace flow — a document, not tabs

One progressively revealing flow. Sections activate as their inputs exist; earlier sections
stay visible (collapsed to their *conclusions*, not to chrome). No stage rail, no tool tabs,
no tabs-inside-tabs. The six stages and five tools map to five bands:

1. **Your view** (was: scattered across intent chooser / hidden context editor / nothing).
   The first move: symbol, direction chips (up / down / sideways / income — the objective and
   direction vocabulary from TRADER_OWN §3.7), horizon control, conviction, optional target.
   **No silent defaults, ever** — kill the `thesis:'neutral'` fallback (`views.js:127-128`) and
   the Home-card global-context write (`views-plan.js:430-431`). Engine-carried context (scout
   thesis) arrives *labeled as the scout's view, asking for adoption*. Objective revisions
   version per TRADER_OWN §3.7; the coherence engine (folded Phase 9) lives here for the plan's
   whole life: "your positions no longer express this view."
2. **The evidence band** (was: "Evidence" — which the survey measured as ~45% genuine event-study
   evidence and ~45% simulation, mislabeled as one thing). Two honest sub-bands, both framed as
   *interrogating the declared view*:
   - **"Does history agree with you?"** — the event-study/analog machinery with the §4 control
     language (below). Regime lens (folded Phase 10.3) conditions it visibly.
   - **"What could happen from here?"** — the simulation fan, born HERE, at the hypothesis
     (owner directive). This is the ONE ensemble (§2.3). Beginner keeps the story cards +
     horizon chips + Calm/Typical/Wild wildness with the live ±N% preview (the survey found
     this design already good); Expert gets the SAME cards with the parameters revealed and
     editable beneath (level-as-lens, §5) — not a separate 16-field parallel form.
3. **Strategy** (was: five tabs). The **argued-idea hero**: rank-1 rendered fully (payoff at
   size, participation, stance, both EV lanes, POP, capital words, "why this ranked first" from
   the score breakdown, what-could-go-wrong, protocol preview), two runner-up cards, near-tie
   honesty line, and the full ranked field one tap away (Expert: comparison table inline below).
   "Your trade" (the unified editor) and Builder become the hero band's *alternate composers* —
   one shared entry core (course-correction P1.3 direction A lands here), surfaced as "compose
   your own" beside the proposals, feeding the same ranked comparison ("yours vs ours",
   scenario 14). Chain/scout are pickers inside composition, not sibling tabs. Selection is the
   hero's primary action and **advances the flow** — no wholesale panel wipe (`views-plan.js:1028-1083`
   converts to a targeted update).
4. **Outcomes on your structure** (was: Outcomes stage). **The same fan, re-priced** — the UI
   finally says what the server already does ("repriced on the same stored model ensemble shown
   in Evidence", `PlanOutcomeController.java:139`): one visible thread — "your view's futures
   from band 2, now carrying your structure." Market-implied odds, analogs, and rule replay
   render as *lenses on the same artifact*, disabled-with-reason when inputs are missing (never
   hidden). The scenario canvas (folded Phase 8) is this band's deep-dive: "author the path" opens
   the SAME fan in the studio — waypoints, IV paths, symbol-scope, comparison — per TRADER_OWN
   Phase 8's full content, including its model-honesty contracts.
5. **The commitment card** (was: the Decide tab). The survey verdict: Decide's display is ~80%
   re-render; its substance is a *transaction* — fresh executable reprice, qty/limit/fee, guardrail
   blocks, acknowledgments, freeze. So it renders as an inline card at the flow's end with
   exactly that transaction and nothing else. Three outcomes: practice, cash, **"I placed this
   with my broker"** (folded Phase 6 promotion: destination account, atomic write, DECISION
   receipt, plan↔book link). Acknowledgment state persists across level flips and re-renders
   (today it's wiped — flip-census loss point).
6. **The live strip** (was: Manage & Review, the perpetually disabled rail item — which even a
   rehearsal fails to unlock despite its tooltip claiming otherwise; that bug dies with the rail).
   Appears only when something is alive: open position, protocol chips, expiry/assignment
   helpers, campaign accounting line, review-on-close. Contextual, never disabled navigation.

### 2.3 The simulation spine

One ensemble per plan-context, fingerprint-pinned (already true in the engine): born at the view
declaration, quoted by every band, re-priced for structures, authored in the studio, replayed in
review (authored-vs-realized). UI contract: the fan has ONE visual identity threading the flow —
same chart language, a visible "this is the same simulation" lineage chip, and per-claim "how do
you know?" disclosure (novice: a sentence; expert: the distribution). Every number on any band
that derives from the spine can trace to it in one click. Rehearsals and backtests are spine
artifacts, not separate worlds.

### 2.4 The Book

Profit-first per the standing directive: P/L answers first, campaigns (folded Phase 7 — full
TRADER_OWN content: auto-linking, campaign-adjusted economic basis, realized-vs-headline with
identical denominators, counterfactuals live, accumulation ledger) as the Book's center; tax as
quiet derived background (course-correction P1.2 items 1–7 carry forward). Book-risk (folded
Phase 10.2 in full: dollar/beta-weighted aggregation from lots, stressed assignment, expiry
calendar, theme concentration, churn) is the Book's risk lane. Imports + pending-import
resolution (folded Phases 6/10.4) are Book flows. Adoption (Phase 6) enters here or from the
Workspace: any as-is position spawns a flow entering mid-journey with an ADOPTION receipt.

### 2.5 The Desk

Attention (alert center — folded Phase 10.1: protocol breaches, expiries, earnings proximity,
pin risk, unresolved imports — drives ordering), resume (the flows with their next step named),
and today's argued ideas (the teaser density of the one argued-idea component; guardrails:
read-only, one CTA, one destination, one data source). Beginner-tuned first-steps strip; the
tour becomes a guided first run OF the flow (its two load-bearing jobs — level selection and
the rename glossary — move into it).

Appendix B.8 defines the signed-off wide-screen composition; it changes density and co-visibility,
not capability ownership or journey count.

### 2.6 The SPA and desktop workspace contract

StrikeBench is one stateful application, not a set of screens that happen to share a header.
There is one long-lived app shell and one nested Workspace document. Client-side navigation
changes the active destination or band without rebuilding an unchanged destination, losing its
controls, or flashing a page skeleton. Back/forward, deep links, level changes, market changes,
and in-band actions all reconcile through the same route and state owners. A route may mount or
unmount a genuinely different destination; it may not use a whole-root repaint as the ordinary
way to update a choice, result, badge, or flow position. Focus, scroll, pending work, expanded
detail, and live subscriptions have explicit lifecycle ownership.

Desktop is a working surface, not a stretched mobile column and not an article floating in a
wide viewport. At every supported desktop width, the layout spends available space on useful
co-visibility: question beside consequence, controls beside their result, hero beside genuine
alternatives, and summary beside detail. Merely widening controls does not count. Controls stay
top-aligned with the decision they affect; no action cluster is vertically centered in an empty
card, no prose line grows to scanning-hostile width, and no layout leaves a large blank track
when a related result or explanation can occupy it. Each wide-layout rule must prove what is
being compared across columns and must collapse deliberately, not accidentally, below its
content threshold.

The responsive product gate covers **2560** (the owner's fully expanded 5K display), 2048,
1920, 1440, 1280, 1000, 390, 375, and 320 CSS pixels. A 2560 composition may gain a useful
column; 1440 may reduce co-visibility; mobile may sequence the same objects vertically. None may
change the capability, state owner, calculation, or journey, and no breakpoint may be justified
by simply stretching controls or hiding required content.

The journey's questions follow time and causality. While a view is being formed in Research,
first: **what just happened?** (pullback, sharp down day, new high, strong momentum, up streak),
then: **what do you believe happens from here, and over what horizon?** The evidence answers
**what happened after comparable past setups?** If a durable Plan already owns the forward view,
the band quotes it instead of asking again, then asks only which setup should test it.
“Choppy/quiet” describes the backdrop that filters both trigger and comparison days; it is not a
duplicate setup or a competing forward view. Similar-sounding choices carry an immediate
plain-language distinction and the same registry-backed Beginner/Expert explanation control.

Capability ownership is singular. One journey owns each decision, one component owns each
interaction pattern, and one API/domain service owns each calculation or mutation. Existing
capabilities are composed into the Workspace and Book; they are not copied into parallel cards,
alternate entry routes, client-side formulas, or second backend endpoints. Extend the canonical
owner when its contract needs more depth; create a new owner only for a genuinely new job.

## 3. Control language contract

Every parameter: its **natural control**, a **plain label that survives "what does that even
mean?"**, and a **live consequence preview**. The survey's conversions (complete inventory in
the survey artifact; these are normative):

- "How selective?" (a client-side macro over per-question thresholds — the engine never sees it)
  → a 3-anchor segmented control ("More examples / Balanced / Only the strongest") that VISIBLY
  moves the threshold numbers beneath it, with a live "would find ~N past examples" count.
- "Market conditions" (real regime conditioning, both levels) → chips, with a live "today:
  ABOVE its 200-day average" indicator and a one-tap "only conditions like today."
- "History to check" → segmented 1y / 3y / All, date range appearing on Custom.
- "Signals" → the per-question trigger inputs, renamed to what they are ("What counts as a
  pullback: __% below the __-day high") — the word "signal" never labels a control.
- Possible-futures: Beginner's story cards / horizon chips / wildness chips stay (they're
  right); Expert = same cards with parameters revealed beneath (model family, drift, vol,
  jumps, paths — the 12 silent beginner defaults become *visible carried values*).
- Product-wide: a listbox is never the default control; every label ships with both-level ⓘ
  coverage; **the explanation audit inventories rendered visible labels against the registry**
  (not just wired terms — closing the `__usedInfoTerms` blindness), and fails the build on
  uncovered labels. "Model-dependent" ceiling and its six render sites get the E.3 treatment.

## 4. Level-as-lens contract

Level is a **pure view function of one state owner per surface**. Mandates from the flip census:
(1) one state owner per surface — no closure-local results (the 11 loss points route onto the
four existing rails: keyed expandables, planUi, server re-hydration, workspace persistence);
(2) level-invariant persistence keys (never summary text, never occurrence index);
(3) every silent beginner default becomes a visible, carried value (12 today: 9 scenario
parameters, 3 tax inputs — beginner sees them as facts with ⓘ, expert edits them);
(4) flipping levels NEVER changes what a button computes, what a fill records, or what state
exists — pinned by test: run each band, flip twice, assert results/selections/acks/drafts
identical. Expert = deeper lens on the same objects: revealed parameters, distributions,
tables below heroes — never different controls, never different outcomes.

## 5. Interaction grammar (carried forward, now product-wide)

Course-correction P0.2 rules 1–6 are permanent law: targeted updates (no wholesale repaints —
including the five residual toast+render sites and the selection handler), consequence at the
point of action, persistent previews with stale-dimming, one-viewport-first by layout (Appendix
B's keep-list discipline; the roll surface's density regression gets fixed in its re-homed
form), no internals in chrome, expandables only for secondary detail.

## 6. What is explicitly retained (the anti-loss inventory)

Every engine and capability, re-homed: event-study/analogs + containment (evidence band), full
Monte Carlo ensemble + Scenario Studio + rehearsals + authored canvas (spine + its studio),
proposals + DecisionPolicy ranking + ranked field + compensation ranking (hero band + Scout
folded into Desk ideas and composition pickers), Builder pedagogy (composer skin), unified
editor two-command core (composer + Book recording), backtests/rule replay (outcomes lens),
calibration "Your record" (Book + review band; BROKER-provenance re-pointing per Phase 6),
tracked book in full (Book), sim worlds/lanes (Data + lane chips), INFO/vocabulary registry
(everywhere), all TRADER_OWN §3 receipts/structures/pending-import machinery (unchanged
underneath). The old six-stage deep-link routes die (pre-release, clean break); the Workspace
flow gets stable section anchors.

## 7. Sequence

- **R0 — Contracts as code.** State-owner/level-lens rails (§4), control-language primitives
  (segmented/chips/slider + consequence-preview components), the label audit rework (§3), flow
  scaffolding (section model, activation, conclusions-collapse), spine lineage chip. Pinned
  tests: flip-without-loss, no-silent-defaults, label coverage.
- **R1 — The Workspace flow, bands 1+2.** View declaration (kills the neutral default), evidence
  band with converted controls, the fan born at the hypothesis. Research index/symbol pages
  become the Workspace's open mode (one renderer already — chrome unification); nav collapses
  to Desk/Workspace/Book(+Data).
- **R2 — Bands 3–6.** Strategy hero + composer consolidation (one entry core), outcomes-as-same-fan
  with lenses + studio deep-dive, commitment card with the third outcome (Phase 6 promotion +
  destination accounts + adoption), live strip replacing M&R.
- **R3 — Desk + Book.** Desk (attention/resume/ideas + tour-as-first-run), Book profit-first
  re-home, plan bar/library retirement into Desk.
- **R4 — Folded depth.** Campaigns (Phase 7) + canvas full content (Phase 8) + coherence/objective
  lenses + composite catalog (Phase 9) — all born into their bands.
- **R5 — Folded breadth.** Alert center driving Desk (10.1), book-risk lane (10.2), regime
  conditioning everywhere (10.3), broker imports + batch adoption + pending-import UI (10.4).
- **R6 — Learn + close-out.** Learn from the registry, sentiment chips (10.5), full battery.

Every phase ends: full matrix + fresh screenshots + **the novice product-verdict walk** + the
Definition of Done (evidence-cited closure per item; no self-certification — course-correction
E.5 is permanent).

## 8. Scenario battery

TRADER_OWN scenarios 1–16 carry forward unchanged (they test engines and honesty, which
survive). New UX scenarios, from the owner's own walk:

17. **Flip-without-loss** — run every band, flip Beginner↔Expert↔Beginner: zero lost results,
    selections, acknowledgments, drafts; identical computations.
18. **The label test** — a novice reads every visible label on the primary journey and can
    answer "what does that mean?" from the screen or one ⓘ; the audit proves coverage.
19. **Trade a view, two interactions, view declared** — search symbol → declare view → hero,
    with the declared view visibly driving the ranking; no imposed defaults anywhere.
20. **One-fan continuity** — the fan seen at the hypothesis is visibly the same object at
    outcomes and in review; the lineage chip traces it; no surface presents a second
    unexplained simulation.
21. **The novice walk** — cold start to committed practice trade in one flow with no dead ends,
    no tab-inside-tab, nothing that requires documentation; graded by a human, not a test.
22. **The expert walk** — same flow, deeper everywhere: parameters revealed, field table, studio
    authoring — and not one control that differs in kind from the novice's.

---

**Through-line, restated for this program:** the engines were always the product; the UI's only
job is to let a person declare what they believe, see it tested honestly, choose a structure
with full attention, commit deliberately, and watch reality answer — in one place, in one flow,
in language they already speak. Simplicity is not a feature of this program; it is the program.

## Appendix A — completion and executable evidence (2026-07-17)

Program ONE R0–R6 is complete in the integrated source tree on `feature/journey_refactor`. The
production host has not received this branch. Exact test totals are generated from Surefire and
per-suite TAP reports for the exact branch tip by `scripts/release-matrix.mjs`; this specification
does not freeze a count that can become stale.

### A.1 Release closure

| Release | Closure evidence |
|---|---|
| R0 | `dom.test.js` contracts-as-code and rendered-label tests; `no-silent-defaults.test.js` |
| R1 | public Evidence handoff/fan persistence browser tests; `PlanDeclarationLifecycleTest`; `PlanStrategyDeclarationTest` |
| R2 | `PlanApiIntegrationTest` strategy competition, one-ensemble Outcomes, decision freeze, rehearsal/review cases |
| R3 | `spa-identity.test.js`; canonical-route/same-hash Workspace browser cases; atomic broker promotion integration |
| R4 | `ObjectiveCoherenceTest`; `CampaignServiceTest`; `ScenarioCanvasTest`; composite and objective-lens suites |
| R5 | `AlertCenterServiceTest`; `BookRiskServiceTest`; `BrokerStatementParserTest`; `BrokerImportServiceTest`; `PlanAdoptionBatchTest`; `dom-bookrisk.test.js` |
| R6 | `NewsSentimentScorerTest`; `dom-learn-coverage.test.js`; `dom-learn.test.js`; `adoption-review.test.js`; `dom-audit.test.js`; `scenario-form.test.js` |

### A.2 Battery traceability

The exact scenario descriptions remain normative in `TRADER_OWN_SPEC.md` scenarios 1–16 and this
specification scenarios 17–22. The strongest focused traces are:

- **1–3, participation and objective framing:**
  `StrategyEvaluatorTest#shortPremiumParticipationAndRegimePointsDoNotMasqueradeAsUpsideOwnership`,
  `#theSameShortPremiumBookFlipsOnlyCoherenceWhenItsObjectiveChanges`, and
  `CampaignServiceTest#crossCampaignPatternsNeedTwoClosedObservedExamplesPerLaneAndNeverPoolRealWithPractice`.
- **4, 7, 8, position transformations:** `PositionTransformationTest` leg-out, roll/fresh-eyes,
  residual-hedge cases and the signed lifecycle cases in `PositionTransformationApiTest`.
- **5 and 10–12, event/history/model honesty:** evaluator buying-power, IV-context, and thin-history
  cases; `ScenarioCanvasTest` event template, IV path, and observed-to-modeled replay boundary;
  `JourneySurfaceTest#everyHistoricalReplayUsesOneNoLookAheadTimeline`.
- **6, the Vanguard aggregate:** the seeded `BookRiskServiceTest` battery plus
  `dom-bookrisk.test.js` covers beta/Greek coverage, stress, the 8/07 cluster, theme
  classification, contradiction, JEPQ collision, INTC churn, and cross-account subtotals.
- **9, adopted loser:** `PlanAdoptionReviewServiceTest` and `adoption-review.test.js` keep the
  ADOPTION baseline, current-view analysis, and campaign truth separate.
- **13, multi-account reality:** atomic account promotion in `PlanApiIntegrationTest`, campaign
  and Book Risk account subtotals, and retirement-wrapper tax suppression suites.
- **14, yours vs ours:**
  `PlanApiIntegrationTest#exactBuilderSelectionCompetesBesideServerProposalsOnOneStoredFan`.
- **15–16, coherence over time:** direction/duration cases in `ObjectiveCoherenceTest` and
  `PositionArtifactStoreTest#decisionAndAdoptionReceiptsFreezeTheObjectiveRevisionInForceProspectively`.
- **17, flip-without-loss:** the R0 rail browser case, `scenario-form.test.js`, and the SPA
  identity suite preserve drafts, selections, acknowledgments, inputs, lineage, and results.
- **18, label test:** the rendered-label browser gate exercises real Strategy, Outcomes Canvas,
  Book Import, and Book Risk surfaces at both levels; `dom-learn-coverage.test.js` checks registry
  depth.
- **19, two-interaction declaration:** `no-silent-defaults.test.js`,
  `PlanStrategyDeclarationTest`, `DecisionDeclarationPolicyTest`, and the public Evidence handoff
  browser case.
- **20, one-fan continuity:** Plan integration tests for ensemble reuse and byte-compatible restore,
  plus the browser test that keeps the same fan node mounted across attention moves.
- **21–22, novice and expert walks:** the fixture journey, responsive audit, Scenario level-lens
  suite, and fresh artifacts under `dom-tests/shots/` cover the same end-to-end flow at both
  presentation depths and at desktop/mobile widths.

### A.3 Journey traceability

- **B:** atomic tracked-account promotion (`PlanApiIntegrationTest`), signed assignment/close
  transformations, and closed Campaign overlay/counterfactual/calibration review.
- **C:** deterministic ToS parse and pending queue (`BrokerStatementParserTest`,
  `BrokerImportServiceTest`), atomic batch adoption (`PlanAdoptionBatchTest`), two-lens adoption
  review, surviving-15 partial close, loss-visible roll, alerts, and Campaign lesson.
- **D:** exact Builder/custom and server proposals compare with cash on one stored authored
  earnings fan (`PlanApiIntegrationTest` and `ScenarioCanvasTest`).
- **E:** closed MU Campaign review keeps scenario lineage, realized-versus-headline yield,
  protocol override cost, pattern evidence, and the owner-scoped lesson (`CampaignServiceTest`).
- **V:** the sanitized Vanguard fixture preserves packages/accounts/8/07 facts
  (`BrokerStatementParserTest#sanitizedJourneyVPreservesPackagesAccountsAndTheAugustClusterWithoutInventingFacts`),
  then import authority, adoption, Book Risk, income-versus-accumulate coherence, and provisional
  versus attested tax-bearing history are covered by their owning service and browser suites.

The full command sequence and the detailed scenario-by-scenario evidence map are maintained in
`PROGRAM_ONE_HANDOFF.md`. Deployment is the remaining operational action and does not change the
Program ONE product contract.

## Appendix B — Desk prototype convergence contract (2026-07-19)

### B.1 Integration decision

`desk.html` is the signed-off interaction and visual-behavior reference, not a second application.
Its cockpit, position bloom, Decide workbench, animation vocabulary, and summoned lenses move into
the existing long-lived SPA shell. The existing router, market store, workspace state, API client,
SSE/tab reconciliation, and world-transition transaction remain mounted. A route change or bloom
changes SPA state; it does not navigate to another HTML document, mount an iframe, or start a
parallel client runtime.

The prototype's local option pricing, IV construction, candidate generation/ranking, POP, Greeks,
capital/risk, payoff, scenario valuation, Monte Carlo, synthetic market, and mutation routines are
design scaffolding. They do not become production calculation owners. Production renderers consume
the canonical server contracts below, and every mutation continues through its present service and
receipt boundary.

### B.2 Surface-to-owner map

| Prototype surface | Canonical read owner | Canonical action owner |
|---|---|---|
| Desk P/L, positions, capital, Greeks | `/api/portfolio/summary`, `/api/portfolio/greeks`, `/api/plans/portfolio` | existing Plan/Book routes only |
| Attention strip | `/api/alerts` plus the referenced Plan/package | the referenced Plan manage, import, or review command |
| Risk map and Book Governor | `/api/portfolio/heat`, `/api/portfolio/book-risk` (`BookRiskService`) | position transformation or Plan creation; no risk-card mutation API |
| Observed chain, candles, universe, research/news | `/api/research/{symbol}`, `/history`, `/expirations`, `/chain`, `/news`; Research scout where requested | Plan declaration/creation and existing research-note commands |
| Position bloom | canonical Plan manage view, trade detail/refresh, portfolio Greeks, Campaign view | `/api/plans/{id}/manage/*`, `/api/position-transformations/{preview,apply}`, and canonical trade close/refresh routes |
| Payoff and exact economics | `POST /api/evaluate` and `OutcomeContract`; selected package/fill receipt | canonical trade or Plan decision preview/commit |
| Strategy fan, recommendation, custom build | `/api/plans/{id}/strategy/*`, `StrategyCatalog`, Scout, and the shared builder exposure contract | Plan selection/custom-fit commands; no client-side ranker |
| Scenario spectrum and Monte Carlo | `/api/plans/{id}/outcomes/ensemble`, latest ensemble, outcome run/compare, `PathEnsembleService`, `ScenarioSimulator` | saved Scenario Canvas, rehearsal, backtest, and review commands |
| Authored paths and animated position trace | saved Plan scenario plus `ScenarioCanvasValuator` timeline | existing scenario/rehearsal commands |
| Import lens | `/api/portfolio/broker-imports*` and account/package reads | broker-import preview/confirm/command and adoption routes |
| Observed/simulated controls | `/api/world`, `/api/sim/market*`, current universe bootstrap | the existing atomic `App.switchWorld`/`WorldTransitionService` path |
| Learn | the existing registry-backed Learn data and explanation controls | presentation state only |

The map is additive: Desk composes existing owners into one cockpit. It does not copy their logic,
invent alternate endpoints for the same job, or create another entry journey. For example, the
cluster signal may focus Book Governor, but Book Governor remains the single hedge-entry owner;
the resulting hedge is still a Plan/Strategy/Decision flow.

New Idea composes existing owners in sequence: `/api/plans/{id}/strategy/*` supplies ranked,
selected, and custom packages; `POST /api/evaluate` and `OutcomeContract` supply exact payoff and
economics. `POST /api/plans/{id}/outcomes/ensemble` and
`GET .../outcomes/ensemble/latest` supply the immutable Plan-owned path artifact;
`POST .../outcomes/run` values the selected package and `POST .../outcomes/compare` values the field
against a supplied or stored `ensembleId`; the existing decision preview/commit path alone owns
executable order state and mutation. Where animation data is missing, extend these responses through
`ScenarioSimulator` or `ScenarioCanvasValuator`; do not add a Desk-specific payoff, Monte Carlo,
scenario, or order endpoint.

### B.3 Client state contract

The integrated Desk keeps four deliberately separate layers:

1. **Server facts** — world/dataset identity, account/Plan/package versions, marks and provenance,
   strategies, ensemble references/fingerprints, valuations, receipts, alerts, and unavailable
   reasons. These are immutable render inputs until a canonical refresh replaces them.
2. **Declarations and drafts** — Plan view/objective, scenario assumptions, exact legs, order draft,
   acknowledgments, and pending import/transform commands. Their existing durable owners survive
   every bloom, level flip, route reconciliation, and background refresh.
3. **Presentation state** — focused symbol/position, selected risk facet, hover, pinned scenario,
   animation time, expanded teaching, and panel geometry. This layer may be local because it changes
   presentation, never financial truth.
4. **Operation state** — request generation, pending/error/stale status, abort handle, optimistic
   visual affordance, and returned version. A superseded read is discarded; a mutation is never
   canceled merely because the user changed focus.

Every artifact cache key includes the identities that can change its meaning: user/account, world
and dataset, Plan and context revision, package/selection version, ensemble fingerprint, and model
version. A cache hit with a mismatched identity is a miss, not a best-effort display.

Simulation exposes two non-overloaded identities. `ensembleFingerprint` names the stored Plan-context
artifact—world/dataset, context revision, anchor and provenance, basis/spec/horizon/seed, stored
IV/canvas/rate environment, and model versions—and excludes candidate, package, order, and display-
label identity. `valuationFingerprint` names one child valuation and covers `ensembleFingerprint`
plus exact package/selection version, legs/ratios/multipliers, quantity, captured entry or fill,
fees/cash baseline, and valuation-kernel version. Changing a candidate, leg, quantity, entry, or fee
retains the ensemble and replaces only its valuation; changing a path/context declaration replaces
the ensemble and invalidates every child valuation. Combined responses and cache entries carry both
identities explicitly.

### B.4 Backend-derived animation contract

“Every frame benefits from the engines” does **not** mean one server request per animation frame.
It means every financial path, value, Greek, risk band, and event the animation can reveal comes
from one versioned server artifact, while the browser performs only time interpolation and drawing.

- The backend computes authoritative knots: ensemble paths/distribution bands, session dates,
  position P/L and Greeks, event/transformation markers, payoff points, and exact scenario results.
  `PathEnsembleService` supplies the one fingerprinted path artifact; `ScenarioCanvasValuator`
  supplies coherent daily position traces over it.
- The existing `SimulationEngine.Preview` and `ScenarioCanvasValuator.Report` are the normative wire
  sources. The ensemble view model carries the actual path count and horizon, receipt, ordered session
  dates, p10/p50/p90 underlying bands, ATM-IV knots, and a bounded deterministic set of sample paths.
  A child valuation carries ordered value/P&L p10/p50/p90 knots, Greeks, per-leg value/Greeks/state,
  discrete transformations, terminal comparison, notes, and unavailable reasons. Both use the same
  session index/date domain. The full path matrix remains server-side unless a canonical studio
  operation requires it; the browser never hard-codes “1,500 paths” or “21 sessions.”
- The client keeps the last complete artifact visible while a changed input requests a new one.
  It marks the view updating/stale, aborts superseded reads, and swaps artifacts only after identity
  and version validation. It never mixes knots from two fingerprints.
- `requestAnimationFrame` advances a presentation clock and interpolates between adjacent server
  knots. It patches SVG transforms, paths, markers, and numeric text in place. It does not rebuild
  the whole payoff/scenario DOM, rerun Black–Scholes, resample paths, recompute Greeks, or call an
  API on every frame.
- Discrete server events—expiry, assignment, a roll, a scenario waypoint, a market-session boundary—
  are exact frame boundaries. Interpolation may make movement fluid between them; it may not invent
  an intermediate transaction, probability result, or receipt.
- Pointer motion uses a cached projection of the current server artifact. A strike/waypoint drag
  updates its visual ghost at frame rate, then debounces one canonical preview request. The returned
  artifact settles the chart; the ghost is never presented as an executable result.
- Reduced-motion changes temporal presentation only. It jumps between the same backend knots and
  preserves the same values, warnings, lineage, and controls.

This contract retains the prototype's fluid shared-element blooms and scenario playback while
removing its expensive full-render-per-frame behavior. The first performance target is a stable
60 Hz presentation on the 2560 cockpit with no long task over 50 ms during playback; correctness
tests assert exact equality at every server knot, not merely visual similarity.

Contract tests pin candidate A → candidate B → candidate A: the ensemble fingerprint and displayed
path coordinates remain byte-identical, the valuation fingerprint and exact P/L/Greeks change with
the package, and returning to A reproduces its prior child artifact. Changing a path-defining
declaration changes the ensemble. Browser tests assert server equality at every knot, no API or
financial calculation per animation frame, no long task over 50 ms during playback, and no geometry
or scroll-owner change between simulation states.

### B.5 Economic and market invariants

- `OBSERVED`, `DEMO`, `SIMULATED`, and `SCENARIO` never mix silently. The active world and source
  provenance remain visible; unavailable observed inputs remain unavailable.
- A Plan remains bound to its owning world/dataset. World changes use the existing atomic transition,
  preserving route/focus only after server, app state, market store, account, and universe agree.
- Practice and tracked books never net together. Tracked P/L uses observed or broker-executable
  marks only; modeled/demo values cannot become tracked economic fact.
- Captured entry/fill economics, quantities, fees, and cash baseline remain frozen. Live marks may
  refresh a view; they may not rewrite the decision receipt.
- POP, EV, payoff, Greeks, capital, scenario outcomes, ranking, and hedge effects come from their
  canonical server owners. Missing is rendered as missing with its named reason, never zero.
- Proposal/custom/cash comparison and every animated repricing cite one `ensembleFingerprint`; each
  exact package cites its own `valuationFingerprint`. Package or economic changes mint only a child
  valuation. A Plan-context or path-assumption change mints or selects a new ensemble and invalidates
  its children explicitly.
- All writes carry the existing expected version/idempotency/preview contracts. Smooth UI feedback
  never weakens a guardrail, archive boundary, broker gate, or receipt.

### B.6 Performance and API shape

The current `API` client remains the transport owner: navigation-scoped cancellation, bounded TTL/LRU
read caching, targeted invalidation, speculative prefetch, stale-runtime blocking, and mutation-safe
semantics are reused. The SPA's mounted refresh and SSE/tab reconciliation update affected regions;
they do not wholesale-repaint the cockpit.

New backend work is permitted only where profiling proves that composition—not domain capability—is
missing. The acceptable shapes are a read-only Desk snapshot that atomically composes already-owned
summaries, a server-owned risk-map projection of `BookRiskService`, an orchestration response that
creates/focuses a canonical hedge Plan, or an aggregated universe/sector snapshot. Such a response
must quote its component versions/provenance and must call the existing services. It may not become
a second chain, candle, news, strategy, payoff, Monte Carlo, scenario, trade, import, or world API.

### B.7 Additive convergence sequence

1. **Contract and parity harness.** Freeze the prototype's signed-off geometry/interaction states;
   inventory every visible mock value/action against the owner map; add tests that reject a local
   financial calculator or unowned mutation in the production Desk modules.
2. **Real read-only Desk.** Mount the cockpit inside the SPA and feed its Home bands from real
   portfolio, Plan, alert, Book Risk, research, and world stores. Preserve current destinations while
   each prototype band becomes a view of the same state, not a competing route.
3. **Real position bloom.** Replace mock positions, payoff, Greeks, research, scenarios, Campaign
   context, and management status with the joined Plan/Book artifacts. Keep preview/apply/close on
   their canonical commands.
4. **Real Strategy and Outcomes.** Drive the fan, custom build, payoff hero, scenario spectrum,
   Monte Carlo, and authored playback from one selected Plan and ensemble fingerprint. Remove the
   prototype pricing/simulation routines as each server-backed vertical slice lands.
5. **Mutation convergence.** Wire decision preview/confirm, practice/cash/broker outcomes, import,
   transformations, rehearsal, and review with existing version/receipt boundaries and one visible
   journey per job.
6. **World and capability reconciliation.** Exercise Observed, Demo, Simulated, and authored replay;
   reconcile Research, Backtest, Learn, Campaign, accounting/tax, Data controls, and every Beginner/
   Expert capability against the anti-loss inventory.
7. **Cutover gate.** Make the new Desk the sole presentation only after parity, economic invariants,
   browser performance, responsive geometry, focus/back-forward restoration, and the full release
   matrix pass together. Remove the replaced renderer entry points, not their capabilities.

The integration is complete only when the prototype contains no authoritative mock economics in the
shipping path, every visible result can name its backend owner and provenance, and the new experience
is still as fluid at real data volume as the signed-off design.

### B.8 Signed-off hierarchy and state geometry (2026-07-20)

These names describe compositions inside the existing SPA, not new destinations, routes, or
calculation owners.

- **Home.** One shallow orientation band combines Book P/L with capital at work, shared-gap loss,
  compact attention, posture, and import. On the wide cockpit, a compact risk-sorted position roster
  sits at left, Book risk in the center, and the active-world chain at right; Book Governor,
  market/universe, sector/research, and news remain co-visible below. Position rows own position-
  specific attention. There is no second narrative triage strip and no duplicate hedge entry.
- **Position.** A position blooms from its Home row through the shared-element motion. Its default
  hierarchy is decision band; payoff hero; three primary facts—collect/cost, max loss, chance—with a
  quiet secondary line; the shared scenario spectrum; exact-leg summary; then one bounded inspector
  showing Research, Mechanics, or Book context one at a time. Editing and confirmation controls
  appear only after their owning action starts.
- **New Idea.** A compact editable intent capsule anchors the composition. The left rail owns the
  ranked field and shows deeper rationale only for the selected candidate; risk/reward map and
  screens/caps are summoned into that same rail one at a time. The center owns exact payoff, the same
  fact hierarchy and scenario spectrum, and exact legs/composer. The right owns one decision brief
  and one inspector—Paths, Fit, Greeks, or Book—one pane at a time. A stable bottom transaction dock
  owns order editing, review, and confirmation. These inspector choices are disclosure facets, not
  navigation or parallel journeys.
- **Geometry.** In the signed-off 2560 fixture states, neither the document nor the center/right
  primary columns scroll, and idle, pending, result, stale, and error states retain the same bounds.
  If real collection cardinality exceeds available space, the collection itself may own bounded
  overflow; page-plus-column nested scrolling is not permitted. Narrower layouts reflow the same
  content and owners rather than hiding them.
