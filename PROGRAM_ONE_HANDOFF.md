# StrikeBench current program and consolidated issue ledger

**Current source baseline:** `02660406` — `Make income ideas complete and economically honest`
**Consolidated:** 2026-07-29
**Purpose:** this is the single current status/checklist for the implementation, correctness, UI/UX,
and consolidation work that was previously spread across issue audits, recovery notes, worklists,
and historical handoffs.

This document replaces the superseded issue-history files listed in §9. Stable product and domain
specifications remain separate because they define lasting requirements rather than issue diaries.

## Status convention

- `[x]` means the current source contains the named implementation/behavior and the claim was checked against
  code at the baseline above.
- `[ ]` means work or current-tip proof remains.
- **Partial** means a useful implementation exists, but the product requirement is not yet completely
  demonstrated.
- A historical checkbox is not treated as evidence. Only the current source and current verification
  count.

## 1. Product outcome

StrikeBench should help a user discover, understand, compare, open, and manage risk-aware option
positions without inventing certainty or hiding adverse economics.

The intended workspace has three focus states:

1. **Home / Book** — orient to the market and the account, scan a market or sector, see the Book,
   and choose what deserves attention.
2. **New Idea** — analyze one exact package deeply, including its book, costs, payoff, scenarios,
   evidence, paths, Book fit, and execution constraints.
3. **Position** — reconsider one held package from today, see what evidence is available, and compare
   explicit keep/reduce/harvest/defend/assignment choices.

Home must open the full New Idea analysis; it must not implement a second analysis engine.
Position should reuse the same financial services and visual components where the subject is the same. Missing
evidence must remain missing and must never become a zero, a simulated substitute, or advice.

## 2. Achieved: correctness and backend consolidation

### 2.1 One authority for financial facts

- [x] **One package-price model.** `paper/PackagePrice.java` contains option net, stock cash
  flow, gross package net, fees, after-fee net, quantity, valuation basis, source, observation time,
  executability, and fingerprint. Candidate, preview, outcome, decision, and held-position paths
  consume it.
- [x] **One Greeks API model.** `model/GreeksView.java` names units and completeness instead of
  publishing incompatible cents/dollars shapes to each surface.
- [x] **One fee authority.** `util/Fees.java` owns fee arithmetic; option and stock-only paths no
  longer maintain independent round-trip formulas.
- [x] **One quantile/CDF utility family.** `util/Quantiles.java` and the shared pricing utility
  replaced the competing statistical primitives recorded in the old audits.
- [x] **One option-bar writer.** `db/OptionBarWriter.java` replaced duplicated option-history SQL.
- [ ] **Partial — typed symbol and horizon identities.** `model/Symbol.java` and
  `model/Horizon.java` exist and are used by current hot paths, but local normalization wrappers
  still survive in providers, ingest, plans, campaigns, and accounting. Keep only wrappers that
  translate an external alias; route normalized identity through the value types.
- [x] **Backend-owned session boundaries.** Scenario responses publish session dates and terminal
  package boundaries rather than asking the browser to approximate trading time.
- [x] **Backend-computed lifecycle economics.** Held-position economics, events, current close data,
  carry, policy, tail, Book, and assignment/exit facts are composed on the server.

### 2.2 Browser is display-only for financial facts

- [x] The former browser Black–Scholes, Monte Carlo, tail, POP, and offline fixture engine is absent
  from the served frontend.
- [x] `js/api.js` and `js/desk-backend.js` are transport/adaptation layers; they do not own a second
  option-pricing or recommendation engine.
- [x] The live held-position POP overwrite and the independent browser session-to-expiry algorithms
  recorded in the old audits are gone.
- [x] The frontend may map supplied values to pixels and interpolate supplied chart points for
  drawing, but current price, P/L, POP, EV, Greeks, fees, and order values come from backend results.

### 2.3 Missing evidence and execution safety

- [x] Missing quote, option book, event, or current-close evidence is represented by an unavailable
  value and a reason; it is not silently converted to zero.
- [x] A missing current close mark can block a management verdict without deleting the durable entry
  payoff, captured legs, or saved possible-futures artifact.
- [x] Multi-leg orders default to a signed package **LIMIT** derived from the captured option book.
  A MARKET order requires an explicit user choice.
- [x] An unendorsed comparison is not silently promoted into an order; the UI requires an explicit
  proceed-without-endorsement choice.
- [x] Exact-expiry chain mismatch has a user action to load the package expiration.
- [x] Event evidence, settlement convention, study horizon, and package terminal boundary are
  published to New Idea.
- [x] Equity-option expiry scenarios are labeled as cash-equivalent valuation and expose conditional
  deliverables rather than pretending physical assignment does not exist.
- [x] Observed, Demo, Simulated, and Scenario modes remain distinct. Generated data cannot satisfy an
  Observed execution requirement.

## 3. Achieved: engines, data, and product capabilities

### 3.1 Recommendation and income capability

- [x] `strategy/StrategyCatalog.java` is the central family/template registry.
- [x] Income discovery includes cash-secured puts, covered calls, put and call credit spreads,
  calendars, condors, butterflies, covered combinations, diagonals/overlays, and acquisition-oriented
  structures when their requirements are met.
- [x] Strategy results keep separate results for after-cost economics, compensation/carry,
  evidence/events, and destination-Book fit. Rich premium cannot overrule adverse after-cost EV.
- [x] Recommendation disposition is explicit: desk pick, comparison, unfavorable, mechanically
  blocked, or unavailable. A comparison remains educational instead of masquerading as advice.
- [x] Acquire inputs include a target level and quantity rather than equating “acquire” with any
  arbitrary short put.
- [x] Candidate generation, exact preview, outcomes, and order review share package identity and
  captured price evidence.
- [x] Current strategy education distinguishes a true covered put (short stock plus short put) from a
  cash-secured put and from a poor-man’s covered put.
- [x] A true covered put is intentionally **education-only** until a real short-stock, borrow,
  margin, and assignment authority exists. The product offers actionable bearish-income alternatives
  instead of fabricating short-stock execution.

### 3.2 Scout and progressive discovery

- [x] `recommend/OpportunityScanKernel.java` owns bounded cross-symbol scanning.
- [x] Scout can use the broad optionable universe, active names, or a selected market/sector scope
  rather than being tied to semiconductors and megacaps.
- [x] Scan progress/results can be delivered progressively; early retained rows do not wait for the
  final symbol.
- [x] Result identity includes symbol plus exact strategy/package identity, preventing a row from
  becoming a vague ticker-only suggestion.
- [x] Scout rows are actionable and open the full New Idea view with the exact declarations/package
  needed for analysis.
- [x] The current UI distinguishes universe considered, sufficient inputs, packages evaluated, and
  retained results.

### 3.3 Prices reconcile across surfaces

- [x] Candidate rail, exact preview, order dock, and outcome evaluation publish/consume the same
  `PackagePrice` for an unchanged captured book.
- [x] Option premium, stock cash flow, gross package net, fees, and after-fee net remain separate.
  Stock-inclusive structures can no longer show an option credit as if it were the cost of the whole
  package.
- [x] Unavailable package pricing remains unavailable; it does not render as `+$0` or create a
  committable zero-based limit.
- [x] Current execution and destination checks are performed after an exact package and destination
  are selected.

### 3.4 Position and Book

- [x] Position fresh-eyes analysis asks whether the exact remaining position would be opened today,
  ignoring sunk campaign cash.
- [x] Lifecycle dimensions keep economics, carry, events, mechanical rules, tail risk, account
  limits, Book fit, and assignment intent separate.
- [x] Missing evidence cannot produce `KEEP`, `DEFEND`, or another verdict.
- [x] Position management supports explicit quantity-aware alternatives and recomputes their account
  consequences.
- [x] Position-to-New-Idea creates a durable fork before navigation rather than showing an empty or
  transient analysis.
- [x] The Book fan uses synchronized package P/L paths and identifies individual position
  contributions; it does not sum unrelated independent price axes.
- [x] Cash truth distinguishes settlement, encumbered, pending, and genuinely free amounts.

### 3.5 Market/data integrity

- [x] Yahoo has a durable, explicit daily allowance and cooldown policy.
- [x] Local allowance exhaustion is distinguished from an HTTP/provider failure and does not pretend
  a request was sent.
- [x] A valid Yahoo “no data for this historical range” response records a symbol-plus-range
  pre-history boundary; it does not quarantine all future dates for that symbol.
- [x] Provider failures include symbol/range diagnostics, while bad-symbol/range work is isolated
  from healthy requests.
- [x] Quote, daily history, option chain, and news are independent API results and can succeed or fail
  independently.
- [x] Daily history labels short windows honestly as sessions rather than manufacturing intraday
  bars.
- [x] News and chain overflow affordances are actions, not decorative `+N more` text.

## 4. Achieved: frontend structural recovery

- [x] The served application loads one external `app.css`; there is no inline `<style>` block in
  `index.html`.
- [x] A server-owned `WorkspaceContext` carries world, account, market scope, symbol, goal, direction,
  horizon, risk, and focus identity. Browser workspace events hydrate that context.
- [x] Home has a permanent market/idea workbench and Scout region rather than a modal bloom that
  replaces the whole page.
- [x] Home combines positions and working ideas into a bounded activity region and provides explicit
  watch/news overflow controls.
- [x] New Idea retains one-click candidate selection, exact legs, payoff, scenarios, market evidence,
  paths, Book fit, and order review.
- [x] Shared path geometry is centralized in `renderPathFan`; Home, New Idea, and Position keep small
  subject-specific adapters rather than separate financial path engines.
- [x] Risk-map geometry has one renderer; financial coordinates come from backend values.
- [x] Scenario story terminal boundaries and the chart’s price/P&L axes were corrected in the recent
  scenario commits.
- [ ] **Partial — mobile composition.** The page owns the primary scroll and dense New Idea content
  has folds/disclosures, but current-tip geometry still needs the explicit 390/375/320 proof in §5.

## 5. Remaining: major product and trust work

The backend is not the reason to redesign the product again. The remaining work is to prove the
current frontend, simplify its composition ownership, and close visible UX debt without adding
parallel renderers or calculators.

### P0 — current-tip product proof

- [ ] **Run one current-tip journey audit, once, against a private database.** Exercise Home → Scout
  partial result → New Idea → candidate → paths/scenario → exact package/destination → review; Back
  to the same Home context; Position → management choice → New Idea fork → Back. Inspect the result,
  not merely test exit codes.
- [ ] **Verify exact rendered financial strings against backend values.** Cover unavailable vs zero,
  credit/debit signs, max loss, market POP vs package-gain frequency, Greeks units, current-close
  cash flow, event availability, and package quantity.
- [ ] **Verify no stale-world artifact survives Observed/Simulated transitions.** Header, chart,
  chain, paths, account, and decisions must change atomically or state why they are unavailable.
- [ ] **Verify stored position artifacts remain independent.** A missing current close mark may
  disable management but must not blank entry payoff or saved paths.
- [ ] **Verify every visible primary action resolves its stated blocker.** Retry mark, load exact
  expiry, choose destination, proceed without endorsement, save, Back, news disclosure, Scout row,
  and position fork must all do what their labels promise.

These are acceptance checks, not a request for another large test framework. Add a focused regression
only when the audit finds a real bug that existing deterministic coverage cannot protect.

### P1 — responsive composition and information priority

- [ ] **Home must adapt to 0, 1, 4, and 12 positions and 0, 5, and 20 ideas.** Empty or sparse
  positions/ideas must release space to Scout, market, chain, Book risk, and research instead of
  leaving fixed voids.
- [ ] **Prove bounded desktop composition at 1920×1080 and 2560×1440.** The default state should have
  no page/panel scroll except one intentional scroller for genuine list overflow. Do not achieve
  “no scroll” by crushing charts into sparklines or clipping exact values.
- [ ] **Give 1280/1440 desktop a deliberate composition.** It must not collapse immediately into the
  same multi-thousand-pixel column as a phone.
- [ ] **Keep mobile complete but prioritized.** One page scroller, no horizontal overflow, no nested
  vertical scrollers, 40–44px touch targets, complete two-line leg facts, and useful disclosures
  instead of every desktop panel expanded at once.
- [ ] **New Idea rail allocation needs current visual proof.** Candidate rows, exact legs, the
  risk/reward map, payoff, scenarios, market, and Evidence/Paths must all remain visible and useful at
  the required desktop sizes. The fan/map may scroll only for genuine list overflow, not because a
  sibling claimed a fixed appetite.
- [ ] **Position needs the same proof.** Entry payoff, current facts, held legs, saved paths,
  management actions, chain/history, and news need one clear set of responsive rules and no contradictory
  overflow rules.
- [ ] **Correct the remaining sign-dependent Book label.** The single-position Book summary still
  labels `p.net` as “Entry credit” even when the package is a debit. Route it through the same
  semantic credit/debit renderer used by package values.

### P1 — CSS and component ownership

- [x] CSS is physically in one `app.css`.
- [ ] **CSS still has competing rules.** The file contains overlapping base, width,
  height, focus-state, and late corrective rules that resize the same components. Consolidate by
  component and viewport rules; do not add another late override.
- [ ] Consolidate the remaining subject adapters around shared `LegRow`, `ScenarioSpectrum`,
  `IconButton`, `Stepper`, `EvidenceSummary`, `OverflowList`, `PathFan`, and `RiskMap` primitives.
  Surface code should place components, not restyle their internals.
- [ ] Remove CSS selectors that hide semantic rows with `nth-child` unless the same component provides an
  accurate, working disclosure action.
- [ ] Replace remaining Unicode/text control glyphs with shared SVG icons where optical alignment
  and touch geometry matter.

### P1 — communication and accessibility

- [ ] Remove remaining internal engine labels from customer-facing
  messages.
- [ ] Replace repeated scenario/tutorial prose with trajectory, probability, P/L, legend, and state
  graphics. Keep exact financial facts, sources, missing-input reasons, and blocking reasons as text.
- [ ] Keep source/freshness once at the package or panel level unless one leg differs. Do not repeat
  expiry and provider text on every same-expiry leg.
- [ ] Ensure strike and executable bid/ask remain visually primary in every leg row. Enrich IV,
  delta, and liquidity only when space remains.
- [ ] Make every interactive row a real button/link or give it complete keyboard semantics and a
  visible focus state.
- [ ] Give Save and Back visible outcome/context confirmation.

### P2 — progress and market explanation

- [ ] **General progress architecture is partial.** Scout streams and workspace events exist. Other
  genuinely slow operations should adopt the same job/progress mechanism when they currently leave a
  static loader, but this must extend the existing event/job services rather than introduce a second
  streaming framework.
- [ ] The forward expected-move cone is a model envelope, not a forecast. Keep its provenance and
  statistical lens explicit; if it remains visually generic across symbols, show the changing inputs
  and values or reduce its visual dominance.
- [ ] Continue expanding observed event/calendar coverage through polite, source-backed acquisition.
  “Unavailable” is preferable to inferred earnings dates.
- [ ] True covered-put execution remains unavailable until short-stock borrowing, margin, hard-to-
  borrow fees, dividends, buy-in risk, and physical assignment are modeled by one broker/account
  authority. Do not add a UI-only approximation.

## 6. Verification

Use the smallest meaningful check during implementation, then one release pass:

1. Backend changes: extend `CoreRulesTest` only when a small invariant protects a current
   implementation; do not reconstruct the retired broad suite.
2. Frontend result/render changes: perform an exact rendered-value check in the private instance.
3. Interaction changes: run the affected packaged-browser journey on a private database and port.
4. Geometry changes: inspect current screenshots at 2560, 1920, 1440/1280, and 390/320.
5. Release: run `mvn -q test` and build the jar once.

There is no current full-suite count. The former 1,428-test and browser-matrix claims were historical
and those suites were intentionally removed. Generated screenshots and exploratory scripts are not
automated release evidence.

## 7. Definition of done

- [ ] Every displayed financial fact has one backend value and one semantic renderer.
- [ ] No missing fact renders as zero or becomes advice.
- [ ] Home, New Idea, and Position preserve one workspace context and transition in one click.
- [ ] Every supported income/acquisition/hedge/directional family is visible or names the exact
  evidence/holding/account capability that prevents it.
- [ ] Every order is an exact, destination-aware package with a safe default and explicit blockers.
- [ ] Default desktop composition is bounded at both required full resolutions.
- [ ] Intermediate and mobile layouts preserve all capabilities with deliberate priority.
- [ ] Every visible action works; every `+N more` control reveals the promised content.
- [ ] No duplicate calculator, pricing authority, state store, API, renderer, or CSS geometry rule
  survives merely for compatibility.
- [ ] One adversarial current-tip review finds no stale values, contradictory lenses, unreachable
  facts, clipping, accidental scrolling, or dead actions.

## 8. Stable documents retained

These documents have a distinct continuing purpose and are not issue-history duplicates:

- `README.md` — installation and product overview.
- `DEVELOPER.md` — build, configuration, architecture, and operations.
- `REBUILD_PROMPT.md` — durable engineering invariants and incident lessons.
- `CALIBRATION_ENGINE_SPEC.md` — calibration/parity feature specification.
- `STRIKEBENCH_ONE_SPEC.md` — approved Program ONE product/IA specification.
- `TRADER_OWN_SPEC.md` — authoritative domain/formula requirements in its retained sections.
- `POSITION_LIFECYCLE_SPEC.md` — lifecycle/carry specification.
- `OSFF_TALK.md` — unrelated conference artifact.
- `AGENTS.md` — local project memory/instructions; not part of the tracked issue ledger.

## 9. Consolidated source lineage

The following superseded issue/history files were read and folded into this ledger:

- `BACKEND_WIRING_FINDINGS.md`
- `CODEX_HOME_REFACTOR_FAILURE_HANDOFF.md`
- `CONSOLIDATION_WORKLIST.md`
- `DESK_MARKET_CONVERGENCE_HANDOFF.md`
- `DESK_REVIEW_AND_DIRECTION_HANDOFF.md`
- `DESK_UX_UNIFICATION_HANDOFF.md`
- `DUPLICATION_LEDGER.md`
- `HOME_PROGRAM_SPEC.md`
- `PROGRAM_AUDIT.md`
- the former completed-state version of `PROGRAM_ONE_HANDOFF.md`
- `TRADER_OWN_COURSE_CORRECTION.md`
- `program.md`
- `reviews/STRIKEBENCH_DESK_PLAN_IMPLEMENTATION_REVIEW_2026-07-25.md`
- `reviews/STRIKEBENCH_DESK_PRODUCT_UI_UX_AUDIT_2026-07-25.md`

The historical browser-suite artifacts are no longer present. Their still-relevant geometry concerns
are represented in §5; current verification must inspect a freshly built private instance.
