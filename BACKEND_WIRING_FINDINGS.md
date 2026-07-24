# Backend wiring findings

Updated 2026-07-20 from the Program ONE/TRADER-OWN specifications, the integrated source tree,
the current prototype review, and an observed-market probe. This is an execution ledger, not a
replacement architecture. New work extends the canonical owners named in `PROGRAM_ONE_HANDOFF.md`.

## Immediate market-hours evidence

The read-only probe against the already-running non-fixture server succeeded at 11:33 America/Phoenix:

- health, config, provider status, world, market engine, quote, expirations, and chain all returned HTTP 200;
- NVDA quote came from Cboe as `OBSERVED` / `DELAYED`, with bid 204.83 and ask 204.84;
- Cboe returned 24 expirations and the selected 2026-07-20 chain contained 61 calls and 61 puts;
- raw receipts are in `/tmp/strikebench-live-probe-20260720-113323`.

The non-trading end-to-end probe then exercised the canonical Plan path before the close:

- 30- and 5-session NVDA declarations stopped honestly at Strategy because no liquid two-sided
  chain matched those horizons; the application did not substitute fixtures or invent candidates;
- a one-session declaration with the existing 0DTE gate explicitly enabled returned 11 backend
  candidates, selected an eligible exact package, stored a 500-path ensemble, evaluated that exact
  package through ensemble fingerprint
  `df0c2240c15743a633f0c2d2690c59ee358b4da5f842d438f6c283e496593ee0`, and returned an executable
  `OBSERVED` / `DELAYED` decision preview;
- no trade or broker order was placed. The successful receipts are in
  `/private/var/folders/fj/6zb38k1n2l73djm42fkdm03c0000gn/T/strikebench-live-desk-flow-20260720-185204`.

The packaged convergence build was probed again at 12:11 America/Phoenix. NVDA returned 11 ranked
candidates, a new 500-path stored ensemble with fingerprint
`5898875f3b13f65004debb007ffc9bc6dfe7cfab6828ec533841721bc54ec006`, and an executable observed /
delayed preview. A transient -4% one-session hypothesis selected nine original source paths from
that exact ensemble, focused source row 128, and returned the proposal plus stock-baseline valuation
checkpoints under child valuation fingerprint
`ab5b125b6c5fe972192acd8ac4eb289b12ca51dc70b6a0703a83880e65f782c4`.
MARKET and marketable LIMIT previews were immediate; a more favorable signed debit limit was
`RESTING`, non-executable, and left the ensemble fingerprint unchanged. No trade was submitted.
Receipts are in
`/private/var/folders/fj/6zb38k1n2l73djm42fkdm03c0000gn/T/strikebench-live-desk-flow-20260720-191154`.

A final bounded receipt at 19:47Z captured AMD and NVDA quotes, same-day near-the-money chain
slices, available expirations, and sourced headlines while `/api/config` still reported the
observed market open. Quotes and chains were `OBSERVED` / Cboe / `DELAYED`; requested AMD history
remained honestly unavailable. The engineering-only note is
`.tmp/market-session-2026-07-20-1947z.md`; it is deliberately untracked and is not a runtime cache.
StrikeBench already persists eligible observed quote snapshots and daily bars through its canonical
stores. News remains provider-refetched with a short in-memory cache, so this receipt does not
introduce a second storage owner.

After the close, the fresh packaged build was restarted in non-fixture `OBSERVED` mode. It reported
`marketOpen:false`, restored its canonical quote snapshots, and returned NVDA 203.31 / 203.33 from
Cboe as `DELAYED`, 23 currently available expirations, and 63 calls plus 63 puts for the first
available 2026-07-22 expiration. The expired 2026-07-20 chain remained unavailable instead of being
fabricated. This closed-session check verifies honest continuity, not a live executable market.

The same fresh package then completed a non-trading two-session NVDA flow: 10 backend candidates,
one exact selection, a 500-path ensemble
`5ee5e8e4a058ac0aada2fb1f77a6fe34a8dafffab6d034e18b7ac4b4ee57a371`, an outcome evaluation, and
an eligible `OBSERVED` / `DELAYED` Practice preview. A -3% conditioned display selected nine original
ensemble rows. Each returned 25 stochastic price points plus 25 synchronized underlying, package,
and per-leg valuation checkpoints; the focus path had non-linear interior movement and all eight
context paths retained distinct terminals. No trade was submitted. Receipts are in
`/private/var/folders/fj/6zb38k1n2l73djm42fkdm03c0000gn/T/strikebench-live-desk-flow-20260720-201910`.

The packaged Desk was then verified end to end against the closed-session observed AMD context.
The canonical Plan resumed with its persisted `conservative` risk posture; the Desk accepted that
mutable context instead of treating a header default as Plan identity. Quote and evidence now come
from the direct Research owner used by Strategy and Outcomes, avoiding a mixed ambient-engine
snapshot. The screen rendered three backend candidates, a selected 77% model POP, an exact
server-owned payoff curve, the stored 500-path parametric ensemble, a 96% profitable-path outcome,
and the backend's signed +$670 Practice order valuation. A selected -20% scenario displayed several
conditioned original paths with noisy interior checkpoints and one emphasized focus path. No trade
was submitted.

After the final convergence package and restart, the same observed AMD Plan rendered four backend
candidates and a selected 86% model POP. The stored 500-path result and server payoff remained
visible, while the now-`STALE` observed option receipt correctly changed execution to
`UNAVAILABLE`: the dock retained the analyzed +$670 candidate credit, showed no executable package,
disabled Review, and did not promote the backend's legacy zero sentinel into a `+$0` order. The
-20% scenario again displayed a bounded set of original noisy paths with one focus trajectory and
server-valued progression. No trade was submitted.

A later fresh-tab check exposed an analysis/execution boundary bug rather than a missing option
surface. AMD still had a large two-sided Cboe chain, but its enclosing observed receipt had aged to
`STALE`; Strategy required execution freshness before it would even form an analytical competition.
That returned a valid empty competition, which the Desk then incorrectly promoted to a fatal screen
while discarding the backend notes. The corrected boundary now permits same-lane `STALE` observations
for explicitly labeled prior-close analysis and retains the ordinary placement gate for preview and
commitment. The rebuilt closed-market AMD flow returned nine ranked packages, selected an exact
four-leg condor, loaded its 500-path ensemble and same-ensemble outcome, and rendered noisy stored
paths. The Practice preview remained `UNAVAILABLE`, retained the candidate's +$2,560 credit as
analysis rather than an executable quote, and kept Review disabled. No trade was submitted.

The same Desk then exercised the canonical closed-market teaching flow instead of a browser fixture.
It created `simw_0a4pg75hnb2q8p6m` through `/api/sim/market` using AMD's server-owned stale observed
anchor, waited for preparation, started it, selected it through `/api/world`, and verified both the
world and config receipts before reloading the declaration. StrikeBench created the separate
SIMULATED Plan `plan_qvqbga4ffes4j6rw`; the screen returned eight ranked packages, a new authoritative
fan/outcome, simulated provenance, and an executable Practice preview. Returning through the same
world API resumed observed Plan `plan_ef6bks1dmngsq7ah`; neither Plan was copied across worlds or
mutated into the other. No order was submitted.

This proves the quote/expiration/chain seam while the market is open. It does not prove order
placement, fill state, historical coverage, or that every contract is executable. Those retain
their existing gates.

## Executable wiring order

1. **Observed market frame.** Reuse `MarketDataEngine`, `MarketDataService`, and the existing
   freshness/evidence records. Wire the new surfaces to quote, expiration, and chain APIs without
   synthesizing missing values. Preserve SSE/world-transition ownership instead of adding polling
   state inside individual panels.
2. **Scenario motion.** Reuse the stored `PathEnsembleService` artifact and
   `ScenarioCanvasValuator` / `PathValuationKernel`. A display subset of source paths is necessary
   but not sufficient: the production animation response must also bind its focus path to the exact
   position-value, P/L, IV, Greeks, leg-state, and same-symbol position checkpoints from the same
   valuation. The typed, non-mutating animation request accepts either a stored scenario or transient
   waypoints/IV-canvas assumptions against an already stored fan. It returns full ensemble/model/
   market provenance, a versioned selection receipt, and a deterministic child valuation receipt.
   A selected scenario shows several nearest original paths, never one misleading forecast or a
   visual-only simulator; the focus line drives the journey and surrounding paths retain dispersion.
3. **Model depth.** The model selector continues to use the existing GBM, Student-t, jump, Heston,
   historical-analog, and conditional-bootstrap capabilities. Beginner copy explains the behavior;
   Expert exposes canonical parameters and receipts. Animation timing never changes the simulation
   seed, path, valuation, or probability result.
4. **Order semantics.** Signed package net remains canonical (`+` credit, `-` debit). The existing
   trade and Plan-decision owner now accepts one normalized nested `MARKET` / `LIMIT` instruction,
   with signed limit,
   time-in-force, natural executable net, midpoint, marketability, present eligibility, and actual
   evaluated fill. A marketable limit fills at the natural/better executable package—not blindly at
   its worse bound—and a favorable resting limit must never create a position. The existing broker
   preview identity, TTL, confirmation, and idempotency receipts remain the live adapter boundary.
   Debit/credit is derived economics, never a third order type. Order changes re-preview execution
   and package consequences while preserving the exact ensemble id and fingerprint.
5. **Trade and Book continuity.** Placement, working, partial, filled, cancelled-requested, and
   rejected states flow into the existing Plan decision, position, ledger, campaign, alert, and Book
   owners. Do not create a prototype-only order store.

## UX findings translated to backend contracts

| Surface feedback | UX decision | Backend consequence |
|---|---|---|
| Scenario animation should use the real statistical work | Animate the selected stored fan with one focal path plus contextual neighbors; synchronize price, IV, position P&L, and dated events | Return immutable source-indexed display paths and lineage; value through `ScenarioSimulator` / `PathValuationKernel` |
| Limit, market, and net-credit currently look equivalent | Change price control, fill likelihood/state, natural price, debit/credit, buying-power consequence, warnings, and review language | Introduce a normalized order preview DTO/service over broker and paper execution owners; never infer behavior only in JS |
| New-Idea risk map crowds the first column | Stack Ideas then Legs; give the leg editor full width and a bounded panel scroll when it grows | No new calculation owner; proposal selection and exact leg edits continue through `StrategyCatalog`, Builder, and evaluation APIs |
| Four-plus legs become clipped | Prefer a readable single-row leg at wide widths, horizontal detail overflow only inside the leg panel, and stacked fields at narrow widths | Leg DTO must retain full action, type, ratio, strike, expiration, quote/execution, and provenance; the UI may not truncate the data model |
| Position Bloom risk-map labels differ | Use one risk-map component and labeling policy; suppress underlying text before suppressing structure identity when space is tight | Reuse the same risk/exposure payload for Idea and Position contexts, with presentation-only density changes |
| Every screen should move smoothly | Motion reflects state transitions, data refresh, selection, scenario time, or hierarchy; it does not continuously distract or mutate state | SSE/event owners provide deltas; animation frames remain client presentation over frozen server facts |

## Layout decision for New Idea

Keep the current visual language. Move the exact leg editor adjacent to the Ideas selection in the
reading order and stack Ideas, exact Legs, and the supporting risk map as full-width panels. This
remains legible for condors and larger packages and collapses naturally on mobile. Panel-local
horizontal scrolling is acceptable for a grown desktop leg set; whole-page horizontal scrolling is
not. The map retains useful height instead of becoming a tiny sidecar on wide screens.

## Implemented convergence increment

- `POST /api/plans/{id}/outcomes/ensemble/paths` is the strict animation contract. It accepts a
  stored scenario or transient waypoints and IV/canvas assumptions, selects a bounded subset of
  original source paths, identifies the focus source index, and returns dated underlying,
  position-value/P&L, Greek, leg-state, and transformation checkpoints from that same source row.
  The response binds Plan context, world, dataset, model, assumptions, ensemble fingerprint,
  selected candidate, path-selection rule, and child valuation fingerprint.
- The same request now accepts an optional `focusPositionKey` for an existing-position journey.
  The key must resolve through the canonical same-symbol `ScenarioPositionScopeService`/Scenario
  Canvas scope (or an existing stock/proposal canvas key); no selected proposal is required. The
  response reuses the unchanged stored ensemble and original display paths, returns checkpoints
  only for the focused package, and echoes the key in both the animation and model receipts. Its
  child valuation fingerprint includes the normalized key and exact position input: identical
  requests are stable, while a position change updates that child fingerprint without changing the
  ensemble fingerprint. Blank or unknown keys return 400; an in-scope package that cannot be
  repriced returns 409. The original no-focus proposal path and fingerprint construction remain
  unchanged. The focused integration test, full `PlanApiIntegrationTest`, and full 974-test Maven
  suite pass with no failures or errors.
- Conditioning is nearest-path selection over the immutable stored fan. It does not claim to
  sample a new conditional posterior. Full-fan probability bands remain context; the focus trace
  and neighboring paths show the selected hypothesis's journey.
- Outcome reads have two existing wire shapes: `POST /outcomes/run` returns the saved result under
  `outcome.result`, while `GET /outcomes/latest` exposes those stored result fields directly on each
  outcome row. The Desk adapter now normalizes both at the presentation boundary, and the browser
  regression pins fresh-run and restored-result statistics so valid probability/tail values cannot
  silently degrade to placeholders.
- Default Plan ensembles now retain a generator-owned intraday grid: 12 steps per session for one-
  and two-session decisions, six through one week, three through 45 sessions, and one thereafter.
  A one-session default therefore stores 13 stochastic checkpoints rather than drawing straight
  open-to-close rays. Default parametric fans calibrate volatility from the active option-market IV
  receipt before the scenario fallback can replace the calibration sentinel. Explicit user/model
  volatility and resolution remain authored.
- `ScenarioCanvasValuator` keeps its daily probability bands and now additively returns focus-path
  underlying, package P/L/value/Greeks, and per-leg value/Greeks/state for every stored simulation
  step. Every checkpoint is produced through `PathValuationKernel`; the Desk follows those step
  values while the highlighted stochastic path advances, so a noisy one-session trajectory cannot
  drive a straight-line payoff or risk-map animation.
- Plan decision preview accepts
  `orderInstruction: {type: MARKET|LIMIT, limitNetCents?, timeInForce: DAY}`. Preview responses name
  `IMMEDIATE`, `RESTING`, or `UNAVAILABLE`, retain the natural executable signed net, derive
  CREDIT/DEBIT economics, and prevent a resting limit from creating a position. A marketable limit
  receives the better natural executable fill.
- Candidate evaluation now carries a versioned `risk.terminalPayoff` receipt produced by the
  existing `PayoffCurve` kernel. It retains model/schema version, anchor spot, expiration, captured
  package-net basis, fee treatment, and a bounded exact polyline, and survives the existing
  evaluation JSON snapshot without another schema or pricing owner. Mixed-expiration structures
  explicitly withhold a false single-terminal curve and use supplied-path valuation checkpoints.
- Order summaries add nullable `valuedNetCents` plus `EXECUTABLE_BOOK`, `RESTING_LIMIT`, or
  `UNAVAILABLE` valuation basis while retaining the legacy proposal field during convergence. The
  Desk keeps selected-candidate economics visible when the execution book is unavailable, but never
  promotes the trade preview's primitive zero sentinel into a `+$0` debit/credit claim.
- The Desk's HTTP runtime sequences quote, expiration, chain, Plan, recommendation, selection,
  ensemble, outcome, conditioned animation, and decision-preview owners. Its `file://` dataset is
  retained only as a visual regression fixture. Production animation interpolates returned
  checkpoints; it does not run a browser pricing or random-path engine.
- Candidate/leg/risk composition now reads Ideas → exact Legs → supporting map. Four-plus-leg
  packages use a panel-local rail on desktop and full-width stacked legs on narrow layouts; position
  risk marks expose identity through focus/hover instead of printing ticker text over every dot.
- At 1280–1700 desktop widths the stacked candidate/leg/map rail now gives exact contracts slightly
  more width and the risk map more useful height; intermediate and mobile widths retain their
  structural breakpoints. Scenario receipt text updates in place when Outcomes arrive, preserving
  the same scenario DOM node without leaving a stale “loading” label.
- Same-lane stale observations are now an analysis input, not an execution claim. Recommendation
  and signal evaluation use the existing evidence receipt's `usableIn` contract, while normal
  `Guardrails.check`, decision preview, and trade placement still require executable observations.
  The analysis-only guardrail path adds an explicit prior-close warning instead of silently
  weakening the placement gate.
- A fingerprinted zero-candidate competition is a stable Desk result. The declaration, quote/chain
  provenance, backend notes, and canonical family `reasons[]` remain visible with explicit horizon,
  intent, and simulated-market actions; selection, ensemble, outcome, and preview are not called,
  and no prototype package is substituted. The same empty receipt may be reused for its identical
  declaration rather than rerunning on every retry.
- Screens & Caps no longer submit undeclared defaults. A one-session horizon does not imply 0DTE,
  and fixed prototype POP, assignment, or numeric loss thresholds are omitted until the user moves
  the corresponding control. Explicit edits retain the existing unit conversions and rerun the
  canonical competition. The qualitative Plan posture remains the backend-owned default budget.
- The Observed/Simulated control now uses the existing durable simulation and world-transition
  owners. It creates or resumes a symbol-bearing non-rehearsal world, waits for server-side anchor
  resolution, refuses an excluded underlying, starts the session, PUTs and verifies the selected
  world/config, clears every old financial receipt, and reloads the same declaration into a
  world-owned Plan. Returning to Observed performs the inverse transition and resumes the observed
  Plan rather than rewriting the simulated one.
- Backend rank remains visible and unmodified. Desk Pick is a separate candidate identity and is
  shown only when the backend assessment is mechanically eligible, `COHERENT`, and economically
  `FAVORABLE`; an adverse `LEARN_FROM` comparison can never become the endorsement merely because
  it ranked first. If no package clears all three gates, the Desk says `no endorsable pick` and
  keeps the ranked packages available as comparisons. Screened alternatives keep readable names
  and verdicts while secondary metrics remain visually quiet. Authoritative candidates are no
  longer re-screened by browser-only caps, eliminating the contradictory “Desk Pick / screened”
  state and the mass dimming seen in the interim review.
- Iron-condor construction is now package-aware. The existing vertical builder is reused, but each
  condor short must remain strictly on its own side of spot and the final strikes must satisfy
  `long put < short put < short call < long call`. Crossed four-leg packages stay analyzable as
  `CUSTOM`; they cannot inherit iron-condor range-income names or explanations. The Plan strategy
  engine version changed so persisted competitions created by the old geometry are not silently
  restored into the corrected Desk.
- Ranked and restored candidates now carry `StrategyCatalog.PositionIdentity`, including the
  canonical defined-risk classification. Nullable or model-dependent maximum-profit fields no
  longer force the browser to guess a structure's risk class. If catalog classification is
  unavailable, the Desk says `unknown` rather than coloring the package as defined risk.
- Strategy competition runs now expose a server-owned canonical `inputHash`. The Desk pairs the
  exact `runId` and `inputHash` with its declaration, control, quote, chain, world, and dataset
  fingerprint before reusing a competition. A required refresh reads `/strategy/latest` after the
  write, so an independently selected exact CUSTOM/Scout package remains selected while only the
  ranked competition is replaced; clearing tab-local receipts forces a safe refresh rather than a
  stale reuse.
- Stored fans are repainted only when their immutable receipt still matches the active symbol,
  world, dataset, quote anchor/source/freshness/time, and the current backend-resolved ATM IV.
  A quote or volatility-surface change creates a new canonical ensemble and rebinds the selected
  outcome; the browser never rescales an old fan to make it appear current. Fresh generation also
  re-reads the exact loaded chain receipt and canonical quote/IV surface identity before accepting
  the fan, so even a same-clock simulated volatility shift during the multi-request flow is refused
  instead of mixing snapshots.
- Selection and outcome responses must echo the requested candidate, ensemble id, ensemble
  fingerprint, and valuation identity before replacing visible state. The strategy-select endpoint's
  intentionally thin `{candidateId, planVersion}` mutation receipt is validated while the full
  server-loaded candidate remains the rendering/evaluation object. Pending candidate changes and
  decision commits serialize competing Plan mutations. Decision review and confirmation honor
  `preview.ok`, backend guardrails, account fit, required acknowledgments, and present executability.
  MARKET/LIMIT preview facts stay inside the execution surface and no longer overwrite the
  candidate/outcome payoff.
- The view now waits for the accepted selection receipt before changing the visible package, and
  rapid candidate clicks cannot split UI selection from outcome or preview identity. Screens &
  Caps changes queue behind an in-flight Plan mutation and then rerun the canonical competition;
  an explicit cap remains a recommendation filter within the persisted qualitative risk posture.
  A missing signed package value cannot become a zero-cent limit. When an authoritative load does
  fail, the Desk renders one contained retry state at desktop and mobile widths instead of leaving
  three indefinite loading skeletons on screen.
- Initial idea hydration is one serialized unit through selection, ensemble, selected-package
  outcome, stored decision, and execution preview. Candidate changes cannot enter while that unit
  is incomplete. Backing out invalidates the canceled read chain and an immediate New Idea re-entry
  queues behind the releasing mutation, so neither overlapping Plan writes nor a stranded loading
  surface can result. A financially completed trade remains committed after navigation, but its
  detached receipt cannot close the replacement idea; a canceled exact-package application stops
  before publishing selection, outcome, or preview state into that replacement journey.
- Exact-leg editing is a pure preview until the edited package is explicitly applied. Pending,
  blocked, and valid-but-unapplied drafts keep the selected package's payoff, conditioned paths,
  scenario checkpoints, probabilities, and animation together as one authoritative artifact while
  the leg workbench independently shows the draft and its backend preview status. Canceling the
  draft explicitly restores the selected order preview; no zero-valued draft shell or generic-path /
  conditioned-payoff mixture can appear.
- HTTP Book and Position are now read-only projections of the existing portfolio, Plan, exact-trade,
  payoff, marks, Greeks, Book Risk, research, observed-history, and source-backed-news routes. The
  bridge pages the complete active roster, rejects partial identity, reconciles cards by exact trade
  package revision, and invalidates a focused position whenever that package changes. An empty
  Practice account stays honestly empty instead of restoring staged holdings. The compact position
  context strip remains visible through Mechanics/Book panes; mobile stacks its detail rail above a
  full-width research and market pane.
- Position scenario animation now conditions the Plan's stored `PathEnsembleService` artifact on the
  exact active trade (or owned share lot) through `/outcomes/ensemble/paths`. The response retains
  ensemble id/fingerprint, source-path indices, selection rule, anchor provenance, authored
  waypoints, and exact-package and valuation fingerprints. A Practice trade focus must be the Plan's
  linked active trade in the same account and symbol. Its basis includes opening fees and frozen
  held-share context, so covered packages retain stock P/L and Delta; its receipt also binds immutable
  entry time, source, freshness, snapshot fingerprint, leg authorities, and package economics.
  Multiple neighboring stored trajectories and one emphasized path are animated against server-valued
  payoff, P/L, and Greek checkpoints; browser code only interpolates those checkpoints. Requests and
  events are owned by exact trade id and package revision, so an in-flight response cannot attach to a
  replacement or mutated position.

## Non-negotiable verification

- Run focused JUnit tests after each backend owner changes, then `mvn -q test` for the integrated module.
- Exercise observed quote/expiration/chain reads before the close when available; preserve the raw probe.
- Add browser assertions that order-type changes produce materially different ticket consequences.
- Add responsive checks at 2560, 2048, 1920, 1440, 1280, 1000, 390, 375, and 320 CSS pixels,
  including a four-leg and a larger custom package.
- Assert the animation endpoint reuses the exact ensemble and authored-scenario fingerprints and
  returns only original source paths.
