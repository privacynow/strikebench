# Position Lifecycle, Carry-Honest Engine & One-Workspace Home — Spec v2

Date: 2026-07-22 · Branch: `feature/journey_refactor` · Status: agreed direction; build starts with
Milestone 0 when the owner authorizes implementation.
Origin: three-way review of a real $1.57M wheel account (owner + two independent analyses),
junior's repository audit and dependency-ordered plan, and the owner's Home/workspace decision.
This v2 supersedes v1 and the individual reviews where they disagree. All repository claims below
were verified against the code on 2026-07-22.

## 0. Principles (settled)

1. **Compose, don't rebuild.** Most owners already exist (§1). The missing piece is a lifecycle
   receipt composer and a policy layer — NOT a second recommendation/management engine.
2. **The engine only answers "what to open." The daily question is "what to close, reduce, or
   defend."** Position lifecycle management is the missing half of the product.
3. **Unrealized P/L is history, never a hold signal.** "Red therefore keep" must be impossible to
   read out of any surface.
4. **Fresh-eyes and hold-vs-close are different economics.** "Would I open this today?" prices a
   NEW short at the executable bid; "hold vs close" compares buying back at the executable ask
   (plus fees) against retaining the liability. Both are shown; bid/ask asymmetry is material.
5. **Carry is a decomposition beside EV — never a competing truth.** Gross carry explains the
   premium-seller's experience; after-cost forward EV remains the sole endorsement authority.
   (`CompensationView` already implements this contract for scouting; extend the same discipline
   to held positions and the New Idea candidate table.)
6. **Cash truth, authority-aware.** Pledged CSP collateral (Vanguard-style) keeps earning the
   settlement-fund yield. Three quantities never conflate: collateral income · incremental option
   carry · buying-power encumbrance. Closing a put = risk removal + restored optionality; it does
   not "create" cash yield. Tracked accounts must label liquidity claims `BROKER_REPORTED`,
   `MODEL_DERIVED`, or `UNAVAILABLE`; never present theoretical strike obligation as broker
   buying power. Practice accounts have exact canonical reserves.
7. **Carry rates are labeled honestly**: `remaining mark ÷ collateral × 365 ÷ DTE` =
   *gross annualized remaining premium if the option expires worthless* — never "yield," never an
   expected return.
8. **Redeployment is a frontier, not "find higher carry."** A close-to-reopen comparison exists
   only after evaluating close cost + open cost + resulting book + evidence quality + churn/tax.
   No qualifying replacement ⇒ "capital optionality restored," never an invented yield.
9. **Personal decisions are receipts, not engine answers.** The engine may recommend differently
   under a named risk policy; the owner's recorded decision stands beside it. Intent and declared
   capacity change fit and coherence — they never alter EV, tail math, or evidence.
10. **Book risk can override single-position economics — visibly.** "KEEP by residual carry ·
    CAUTION by event risk · REDUCE under concentration policy" is a valid, honest output; the
    dimensions are never collapsed into one misleading adjective.
11. **Receipts over verdicts.** Every verdict ships with the inputs that produced it, timestamped
    and fingerprinted (model, market snapshot, policy) so later outcomes can calibrate.

## 1. What already exists (verified 2026-07-22 — extend these, never duplicate)

| Owner | Location | Role |
| --- | --- | --- |
| Fresh-eyes exact-package analysis | `api/TrackedPackageAnalysisService.java` | one read-only analyzer; Book editor and adopted Plans share it |
| Zero-based + campaign questions side-by-side | `api/PlanAdoptionReviewService.java` | literally encodes "Would you open the exact position you still own today?" |
| Campaign economics | `paper/CampaignService.java` + `position/CampaignMath.java` | net credit, effective basis, committed capital, annualized formulas (exact cents) |
| Carry-beside-EV | `recommend/CompensationView.java` | premium-per-unit-realized-risk with named components, beside the Decision score |
| Cross-symbol scan | `recommend/OpportunityScanner.java` | bounded-concurrency universe scan → best viable idea per symbol → decision-ranked + compensation view |
| Auto-scout | `recommend/AutoRecommender.java` | thesis per symbol from price action + news sentiment + IV-vs-HV |
| Scan endpoint | `api/DiscoveryController.java` (~330) | already defaults to `universe.active().symbols()`; runs `PortfolioOptimizer` |
| Book risk | `paper/BookRiskService.java` | expiry clusters, theme concentration, stressed assignment, strategy collisions, Greeks |
| Transformations | `api/PositionTransformationController.java` | close / partial close / roll / adjustment previews |
| Event model | `market/EventService.java` | SEC filing-cadence ESTIMATES, honestly labeled `confirmed=false`; born from the MU incident |
| Entry-time management heuristics | `eval/ManagementPlanner.java` | static 50% take-profit / 2× stop / 21-DTE roll rules attached at recommendation time — NOT a held-position engine |
| Assignment intent | Plan `assignmentPreference` | boolean-ish; to be extended with quantity/dollar capacity, not replaced |

The Home "Scan" button (`index.html` ~4755) hands the universe-capable endpoint ONE hardcoded
sector question ("memory & storage"). Generalize the surface; the machinery stands.

## 2. The lifecycle receipt (`PositionLifecycleReceipt`, immutable)

Four lanes, composed from the §1 owners:

- **History**: gross opening credit; net credit after opening costs; captured $ and %; campaign
  realized + unrealized.
- **Current choice**: mark; executable buy-to-close ask + fees; hold-vs-close forward EV;
  fresh-eyes sell-to-open EV (separate, per §0.4); expected shortfall; Greeks.
- **Carry & collateral**: gross remaining premium if expires worthless; annualized gross carry
  (honest label); concurrent settlement income + its authority; encumbrance; capital released by
  closing (puts) / share encumbrance released (calls).
- **Assignment/exit**: exact shares AND dollars; effective acquisition price (puts) / sale
  proceeds (calls); tax-lot basis AND campaign-adjusted basis, kept separate; event crossings;
  book impact under hold / close / partial close / assignment.

Plus: evidence timestamp, reconciliation status, model/policy fingerprints.

## 3. Verdicts

`KEEP · HARVEST · REDUCE · DEFEND · ACCEPT_ASSIGNMENT`

- `ACCEPT_ASSIGNMENT` appears only when assignment is genuinely the active decision (near expiry /
  deep ITM); otherwise willingness stays a declared-capacity field. (Settles v1's naming and
  junior's PREPARE/ACCEPT discrepancy.)
- `REDUCE` is first-class: partial-close proposals compute the minimum k of n restoring a named
  policy ceiling, with the whole book recomputed at each k.
- Rolling is an ACTION PROPOSAL, never a verdict — its close and its replacement stay two visible
  economic decisions.
- Precedence: (1) executability/mechanical truth → (2) declared intent & capacity →
  (3) hard account limits → (4) after-cost forward economics → (5) tail & event risk →
  (6) carry compensation → (7) historical P/L as context only.
- Profit-% targets (50–75%) are configurable policy inputs, never doctrine; post-event
  re-evaluation precedes any rewrite suggestion.

## 4. Dependency-ordered milestones

**M0 — Contracts + fixture.** Privacy-safe SYNTHETIC account preserving the reviewed book's
structure (38 short puts; 9 AMD / 2 AVGO / 21 INTC / 2 MU / 1 NVDA / 3 QQQ; one expiry wall;
covered calls incl. below-basis and ATM-on-overwritten-fund cases; $1.02M-style pledged
collateral reconciling to the cent; four confirmed event crossings). Never commit the owner's
real account record. Also: worst-case static layout fixtures for Book/Position/Idea states.

**M1 — Held-position fact owner.** `HeldPositionEconomicsService` + `PositionLifecycleReceipt`
composing `TrackedPackageAnalysisService`, `PlanAdoptionReviewService`, `CampaignService`,
`EconomicAssessment`, existing mark/fee owners. Facts only — no verdicts until these are
independently tested. Read-only API composition into the existing tracked-package/adoption
response. This produces the correct NVDA statement: *"≈$47 remains; closing releases $18,000 and
removes assignment risk; collateral income likely continues either way"* — not "dominated by cash."

**M2 — Confirmed event evidence.** Evolve `EventService` (do NOT fork it): persist
`CONFIRMED / ESTIMATED / UNAVAILABLE`, date + session (before/after close), source + URL,
observed-at, confidence window, payload fingerprint. Provider order: confirmed issuer evidence →
reviewed import → SEC-cadence estimate → honest unavailable. Flyway `V2__…`. All acquisition
through `ProviderPoliteness`; MUST NOT spend the Yahoo budget; tests run Yahoo-disabled. Feeds
every existing consumer (evaluations, alerts, scenarios, Research, Scout, lifecycle receipts).

**M3 — Account liquidity receipt.** Authority-bearing contract: settlement balance; pending;
recorded/reported reserve; theoretical short-put obligation; genuinely-free buying power when
knowable; collateral-income basis; reconciliation difference + reason. Vanguard-shaped fixture
must reproduce `settlement − 1,016,500 − pending = free` exactly; a margin account without broker
reserve evidence must REFUSE to claim free cash.

**M4 — Read-only book action projections.** `BookActionProjectionService` over hypothetical lot
transformations (hold / close 1 / close k of n / close all / assignment / call-away / roll as two
steps), each recomputing cash+encumbrance, obligation, shares, Greeks, symbol/theme exposure,
expiry concentration, stressed loss, basis effects, executable costs. Reuses `BookRiskService` +
existing transformation math; zero mutation.

**M5 — Quantity-denominated intent & capacity.** Keep `assignmentPreference`; add versioned
declarations — package level (accepted shares, dollars, effective acquisition price, call-away
quantity/proceeds) and account policy (symbol/theme/expiry/encumbrance ceilings, hard vs
advisory). Intent changes fit; never EV.

**M6 — Lifecycle decision policy.** Compose M1–M5 into decision receipts with the §3 precedence
and verdicts. Persist every surfaced receipt + subsequent user decision with fingerprints for
later calibration. Two-policy acceptance (see §6).

**M7 — Scout → redeployment frontier.** Generalize the existing scanners (`OpportunityScanner`,
`AutoRecommender`, `CompensationView`) to: full configured universe, watchlists/themes, tracked +
Practice book context, account capacity, data completeness, book-improving alternatives. Two
rankings stay visibly separate (decision economics vs compensation). Redeployment comparisons
follow §0.8. The Home surface drops its hardcoded sector question.

**M8 — Measured book engine.** ETF holdings look-through with provenance/freshness; aligned
observed-return correlation (missing-data disclosed); joint multi-symbol ensemble in
`PathEnsembleService`; package P/L transforms; correlated assignment/drawdown scenarios.
Independent single-symbol fans remain visible but are never summed. Long pole; supplies the
honest no-focus Book fan.

**M9 — One workspace + the useful Home.** See §5.

**First executable increment (when authorized):** M0 fixture + `PositionLifecycleReceipt` +
held-position economics service + read-only API composition + focused JUnit. No Home redesign or
verdict policy before the receipt proves the economics.

## 5. The workspace & Home (owner-settled architecture)

**Three focus states, one skeleton, no tabs, no subnavigation.** Book (nothing focused) ·
Position (held package focused) · Idea (proposed package focused). Clicking a position or
candidate rebinds the shared panels (payoff hero, scenario spectrum, market chart, legs,
Understand rail — all already single components); one context slot swaps roster ↔ ranked
candidates ↔ management. Clearing focus returns to Book. Strategy: **unify the seams first**
(bring Book state to Decide quality), then — only after measured parity — remove the redundant
Decide overlay and Position-Bloom rendering paths.

**Book state layout**: top = liquidity receipt (settlement · encumbered · pending · genuinely
free); left = positions ordered by TODAY'S DECISION (lifecycle verdict urgency), not raw P/L;
center = book paths + expiry/event wall + action projections; right = selected lifecycle receipt
or book-risk explanation; primary action = Scout (generalized opportunity rows). Empty book leads
with Scout + market context — never two empty panels.

**Scroll contract (owner-revised 2026-07-22 — supersedes v1's hard no-scroll and junior's
whole-row pagination):**
- At 1920×1080 and 2560×1440 (real packaged-app viewports, Mac chrome subtracted), the DEFAULT
  composition shows no page scroll and no panel scroll.
- When content genuinely exceeds the default (many positions, many candidates), the ONE
  overflowing list scrolls — elegantly: whole-row scroll-snap; top/bottom fade masks; an honest
  count affordance ("+12 more") that doubles as the scroll cue; scrollbar chrome hidden until
  intent (hover/wheel); a thin position hairline instead of paging dots; natural wheel/keyboard.
  Never nested scrollers-in-scrollers; never the fitpager (retire `candidatePageSize`/pager
  machinery).
- Secondary lists (news, glossaries) stay bounded summaries; expanding one takes focus rather
  than nesting a scroller.
- Tablet/intermediate: same priority stack, no horizontal overflow. Mobile: normal document
  scrolling, same facts and actions — "perfectly tuned" means priority and legibility, not
  zero scroll.

## 6. Acceptance gates (against the M0 synthetic book)

- 38 contracts; collateral and cash reconcile to the cent; settlement income separate from
  incremental carry; captured premium uses executable close cost, not mid.
- **Two named policies, both proven**: (a) full assignment capacity declared, no ceilings →
  `KEEP` with explicit concentration/event cautions + REDUCE scenarios attached; (b) a named
  symbol/theme/expiry ceiling breached → `REDUCE` with the minimum k restoring compliance.
  (An analyst's risk appetite must never be hard-coded as financial truth.)
- NVDA-P180-shaped position → optional cheap-risk-removal `HARVEST` under a named policy while
  the owner's recorded `KEEP` stands beside it.
- Rich AMD-shaped carry never silently hides event/concentration risk; red P/L never contributes
  to KEEP; INTC-shaped call shows both basis systems + appreciation headroom; JEPQ-shaped stacked
  overwrite disclosed; roll = two decisions; GOOGL/INTC/MSFT/AMD-shaped event crossings use
  CONFIRMED evidence; scanner output cannot bypass EV/evidence/book gates; missing broker
  reserve / event / ETF composition / correlation evidence stays `UNAVAILABLE`; no analysis
  endpoint mutates an account.
- `mvn -q test` after each backend increment; `mvn clean test` for schema/renamed-test changes;
  packaged-browser regressions + the full responsive matrix (2560×1440, 1920×1080, laptop,
  tablet, mobile) before any production decision.
