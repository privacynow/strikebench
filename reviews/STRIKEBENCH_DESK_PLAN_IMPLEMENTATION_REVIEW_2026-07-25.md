# StrikeBench Desk implementation review against M0–M10

Reviewed commit: `445d4f1cb6a53ecfd9f99839161ea1178be9ec79`  
Branch: `feature/journey_refactor`  
Governing plan: `reviews/STRIKEBENCH_DESK_PRODUCT_UI_UX_AUDIT_2026-07-25.md`  
Review date: 2026-07-25  
Scope: current served Desk, backend/API ownership, browser state, CSS, CI, tests, and visible behavior.  
Explicitly excluded: `workspace.html`.

## Executive verdict

The implementation has moved meaningfully toward the plan. It is not a failed rewrite, and the newest work should not be discarded. The strongest advances are:

- one physical `app.css` instead of inline stylesheet blocks;
- backend-owned Greeks, quote, held-payoff, and exact spot-P/L receipts;
- a persisted server-side workspace-context model;
- genuinely progressive Scout transport;
- Scout result identity and adoption into canonical New Idea;
- corrected candidate collection semantics and risk-map domains;
- synthetic stress fixtures and a real viewport test matrix;
- focused regressions for the original `+$0`, chart-abort, and raw-epoch defects.

But the plan is not complete. No milestone currently satisfies all of its acceptance criteria. M5 (Scout) is closest. M6 (New Idea), M7 (Position), and M10 (full interaction/visual proof) are the furthest from completion.

The central remaining problem is still the one the plan identified:

> New canonical owners now exist, but old consumers, duplicate state, browser calculations, fixed geometry, and exception-filled tests still bypass or conceal them.

The current green test result therefore means:

> The Java suite, mocked browser contracts, auth-only packaged journey, and geometry matrix with named exceptions passed.

It does **not** mean:

> The M0–M10 product program is complete, all displayed numbers have one authority, or Home/New Idea/Position satisfy the required viewport and interaction contracts.

## How this review was performed

This was not a code-only review.

1. I froze the exact committed source with `git archive HEAD`, excluding the developer’s untracked work.
2. I built and tested that frozen source.
3. I copied `strikebench_dev` into a private database and launched a private server on port 7198.
4. The private server ran with external providers and maintenance jobs disabled:
   - `FIXTURES_ONLY=true`
   - `YAHOO_ENABLED=false`
   - `SNAPSHOT_ENABLED=false`
   - `PORTFOLIO_NAV_ENABLED=false`
   - `ARTIFACT_RETENTION_ENABLED=false`
   - `AUTH_ENABLED=false`
5. I exercised Home, New Idea, Position evidence already captured from the same source, world switching, composer changes, candidate changes, playback, and order review.
6. I inspected visible geometry at 2560×1440, 2000×963, 1000×800, and 390×844, and cross-checked the broader committed viewport artifacts.
7. I audited backend/API ownership, frontend state and math, CSS ownership, CI scripts, browser lanes, fixtures, and release evidence independently.

The user’s server on port 7070 and its database were not modified.

## Milestone scorecard

| Milestone | Verdict | What is genuinely complete | What prevents acceptance |
|---|---|---|---|
| M0 — CI and golden receipts | **Partial, substantial progress** | Lanes exist; exact regressions exist; stress fixtures exist; clean Java and browser contract/visual runs pass | Release totals are wrong for multi-shard TAP; zero-test visual can pass; artifact aggregation is suspect; packaged product journeys do not exist |
| M1 — Stop every live wrong number | **Partial** | Original net-premium `+$0`, chart abort, epoch display, Greeks, quote, held payoff, and spot P/L paths improved | General null→`$0` remains; browser still originates displayed scenario facts; duplicate book rank/share and price consumers remain |
| M2 — One workspace context | **Partial** | Versioned server context, validation, revision control, and reconciliation exist | Clear semantics disagree across browser/server; world transition is not atomic with context; route restoration and market focus remain split |
| M3 — Shared primitives and one CSS owner | **Partial** | One external `app.css`; some leg/scenario/chain markup is shared | `app.css` is an override forest; component ownership is not consolidated; control sizes and surface grammars remain duplicated |
| M4 — Home composition | **Partial, visibly failing at intermediate width** | Market-first Home, permanent workbench, sector scope, watch/news, empty-book improvements | Fixed panel appetites, large voids, separate oversized activity panels, and a known 1000×800 overprint remain |
| M5 — Scout | **Substantially complete** | Progressive NDJSON, stable row patching, four counts, exact evaluation identity, actionable New Idea adoption | Row hierarchy still compresses four judgments; phase total can be unattainable; persistence failure can produce an unadoptable row |
| M6 — New Idea | **Not complete** | Strong canonical analysis, improved candidate semantics, inline package editing, corrected map domain | Required desktop geometry is broken; mobile has nested scroll and tiny controls; legs, scenario prose, evidence layout, news, and review state remain unresolved |
| M7 — Position | **Not complete** | Fresh-eyes doctrine and backend lifecycle receipts exist; some shared components are reused | Position→Idea is not exact, Forward Test is not a test workflow, duplicated fans/maps persist, responsive composition remains weak |
| M8 — Market/chain/research | **Partial** | Honest daily-history labels and independent receipt states improved | Chain rows are not actionable; market focus is not durable; news overflow is inaccessible; watch policy is hardcoded |
| M9 — Visual/copy consolidation | **Not complete** | Some common tokens and SVG controls exist | Implementation copy, redundant prose, hardcoded black controls, Unicode controls, and inconsistent responsive overrides remain |
| M10 — Full verification and deletion | **Not complete** | Useful visual matrix and artifacts now exist | Known failures are filtered into green; New Idea is not in the full visual lane; packaged journeys and every-click coverage are absent |

## P0 findings

### 1. Release evidence is numerically false for a multi-shard browser lane

The contracts lane ran 130 tests: a 100-test shard and a 30-test shard, with two skips in the first shard. The release aggregator reports only the last shard because [`tapMetric()`](/Users/tinker/output/optin/scripts/release-matrix.mjs:34) selects `matches.at(-1)`.

Consequences:

- 100 passing tests disappear from the release matrix.
- both skips disappear;
- a failing early shard followed by a passing last shard can be reported as green.

In addition:

- [`lane.js`](/Users/tinker/output/optin/dom-tests/lane.js:208) returns success for a required visual lane containing zero tests;
- the release artifacts are uploaded with paths such as `target/dom-visual.tap` and downloaded into `target`, which needs an end-to-end CI check because it can produce a nested `target/target/...` layout;
- TAP files contain no source SHA, so stale local reports can be stamped with the current HEAD.

M0 cannot be closed until one aggregate record covers every shard, every required lane fails on zero tests, and the report is bound to the exact source SHA.

### 2. Workspace values cannot reliably be cleared through the real API

The backend deliberately distinguishes:

- omitted field: retain the old value;
- field named in `clear`: remove the old value.

That contract is implemented in [`WorkspaceContext.mergedWith()`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/db/WorkspaceContext.java:194).

The browser, however, sends `null` fields and never constructs a `clear` set in [`workspaceSave()` / `workspaceFlush()`](/Users/tinker/output/optin/src/main/resources/public/index.html:1456). Real flows set `focusedEvaluationId` and `focusedPositionId` to null before saving.

The mock browser backend treats null as replacement, so its tests certify behavior the production service does not implement.

Visible risk:

- an old evaluation survives a symbol change;
- a position focus that appeared cleared returns after reload;
- route and focused subject diverge after navigation.

The fix is not another state store. The browser adapter must translate explicit null declarations into the server’s `clear` set, and the mock must implement exactly the same merge contract.

### 3. World and workspace transitions are not one atomic transaction

[`WorldTransitionService`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/api/WorldTransitionService.java:133) persists the world/dataset selection. [`WorkspaceService`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/db/WorkspaceService.java:72) reconciles WorkspaceContext later on another read.

That means the header can commit one market while the body still owns a focus and declarations from another. The private visual run reproduced the user-facing symptom: switching worlds and opening New Idea produced an undeclared, mostly empty composer rather than preserving the prior declared posture.

![World switch followed by undeclared New Idea](/Users/tinker/.codex/visualizations/2026/07/21/019f86e2-6cbb-7f93-9e2d-e4685b6c9602/strikebench-plan-review-445d4f1/newidea-simulated-2000x963.png)

One service transaction must commit:

- world/dataset;
- reconciled WorkspaceContext;
- cleared market-owned identities;
- generation/revision;
- the event delivered to the browser.

### 4. Missing financial facts can still display as zero

The original candidate net-premium defect was fixed, but the root policy was not.

[`money()`](/Users/tinker/output/optin/src/main/resources/public/index.html:410) still turns `null` into `$0`. Candidate capital can legitimately be null in [`desk-backend.js`](/Users/tinker/output/optin/src/main/resources/public/js/desk-backend.js:1429), but it is rendered unguarded in the candidate rail and explicitly changed to `$0` in the risk-map card.

Other live examples include:

- held-position hover P/L through `money(p.pnl)`;
- a primitive `long` underlying price in [`TradePreview`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/paper/TradePreview.java:7), where unavailable therefore becomes zero.

The correction must be structural:

- nullable financial facts stay nullable on the wire;
- semantic renderers accept null and emit `—` or a named unavailable state;
- generic money formatting must refuse null rather than coercing it.

### 5. The browser still originates displayed financial facts

The large offline Black–Scholes/Monte Carlo engine was substantially removed, which is good. The display-only boundary is still violated.

[`authoritativeFrame()`](/Users/tinker/output/optin/src/main/resources/public/index.html:1213) interpolates:

- underlying price;
- IV;
- P/L;
- position value;
- Delta, Gamma, Theta, and Vega;
- elapsed session.

[`renderAtPrice()`](/Users/tinker/output/optin/src/main/resources/public/index.html:1239) then prints those interpolated values as financial readouts. That is more than turning supplied points into pixels.

Likewise, the residual non-authoritative payoff path still computes and prints payoff/breakeven facts in the browser.

The backend should supply exact keyed animation frames or a backend valuation endpoint. The browser may interpolate only visual coordinates between two supplied facts; it must not print the interpolation as a new financial fact.

### 6. Book risk share/rank has two backend authorities

[`BookRiskService.bookShareRoster()`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/paper/BookRiskService.java:947) is the stronger canonical receipt:

- named denominator;
- unavailable reasons;
- shared ranks for equal risk (`1, 2, 2, 4`);
- typed roster.

[`TradeService.portfolioHeat()`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/paper/TradeService.java:1693) independently recomputes share and rank, assigns `index + 1`, and is still the endpoint consumed through [`PortfolioController`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/api/PortfolioController.java:63).

Equal-risk positions can therefore have different ranks depending on which endpoint is used. Delete the second derivation and make the UI consume the typed Book receipt.

### 7. “One package-price receipt” is published but not yet consumed as one authority

[`TradePreview`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/paper/TradePreview.java:7) carries both:

- canonical `PackagePriceReceipt`;
- legacy `entryNetPremiumCents` and `feesOpenCents`.

Legacy fields are still consumed in:

- [`HeldPositionEconomicsService`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/position/HeldPositionEconomicsService.java:373);
- [`PlanDecisionService`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/plan/PlanDecisionService.java:230);
- [`PositionTransformationController`](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/api/PositionTransformationController.java:186).

The accurate status is:

> One receipt published, not yet one authority consumed.

M1 requires migrating every consumer, then removing the parallel wire fields.

## Product and visual findings

### Home: improved information architecture, unfinished allocation

Home now has the correct ingredients:

- market-first orientation;
- a permanent discovery workbench;
- explicit sector/market scope;
- positions, working ideas, watch, and news;
- useful empty-book behavior.

The remaining problem is fixed rectangular appetite.

At 2560×1440, the Market and Scout panels expand into large voids while Working Ideas reserves a large empty rectangle. Positions and ideas remain separate fixed regions instead of one adaptive activity rail.

![Home at 2560×1440](/Users/tinker/.codex/visualizations/2026/07/21/019f86e2-6cbb-7f93-9e2d-e4685b6c9602/strikebench-plan-review-445d4f1/home-2560x1440.png)

At 2000×963, the same hierarchy is denser but still spends height according to grid tracks rather than content value.

![Home at 2000×963](/Users/tinker/.codex/visualizations/2026/07/21/019f86e2-6cbb-7f93-9e2d-e4685b6c9602/strikebench-plan-review-445d4f1/home-2000x963.png)

At 1000×800, Home is not merely dense: panels occupy the same pixels. The Scout/workbench panel paints over market, activity, and market-lens content.

![Home overprint at 1000×800](/Users/tinker/.codex/visualizations/2026/07/21/019f86e2-6cbb-7f93-9e2d-e4685b6c9602/strikebench-plan-review-445d4f1/home-1000x800.png)

The visual test knows about this exact failure and filters it out in [`desk.visual.test.js`](/Users/tinker/output/optin/dom-tests/desk.visual.test.js:427). A known overlap cannot remain inside a test named “no panel prints over another.”

Required Home completion:

1. Combine positions and working ideas into one adaptive activity rail with two stacked sections.
2. Let sparse sections relinquish space.
3. Keep one permanent Scout/results owner; never replace market/sector navigation with results.
4. Give the option chain enough width to be useful.
5. Let watch and news claim residual height.
6. At 1000–1440, intentionally recompose rather than shrinking the wide grid.
7. On phones, use the page as the one scroller; do not put the entire board in its own scroll container.

### Scout: transport is now real; result presentation needs one final pass

Scout’s NDJSON path genuinely flushes progressive frames. Stable result identities are patched into one card, and completed rows can open canonical New Idea with the exact evaluation.

Remaining issues:

- the visible result row compresses economics, evidence, compensation, and Book effect into one dot-separated sentence;
- a provisional result key is symbol + intent until the exact evaluation arrives;
- `AutoRecommender` can publish an unattainable phase total because it computes `maxPicks × intents` before skips;
- `DiscoveryController` swallows evaluation-persistence failure and can leave a row visible but impossible to adopt.

Each final result still needs four named lanes and an explicit unavailable adoption state if persistence fails. Do not build a second analysis surface on Home.

### New Idea: still the strongest concept, currently broken at required geometries

At 2000×963, the scenario panel and Market panel overlap by roughly 242px. The center column clips the nested lower content, and news becomes a scroller inside a clipped panel.

![New Idea at 2000×963](/Users/tinker/.codex/visualizations/2026/07/21/019f86e2-6cbb-7f93-9e2d-e4685b6c9602/strikebench-plan-review-445d4f1/newidea-transition-stalled-2000x963.png)

At 2560×1440, more content is visible, but the wide screen does not resolve the component-level defects:

- generic scenario descriptions remain;
- scenario rows are needlessly tall;
- Evidence & Paths header text collides;
- Market execution/news content is cramped and partially overlapping;
- news owns another nested scroller;
- implementation text remains visible.

![New Idea at 2560×1440](/Users/tinker/.codex/visualizations/2026/07/21/019f86e2-6cbb-7f93-9e2d-e4685b6c9602/strikebench-plan-review-445d4f1/newidea-2560x1440.png)

Opening Review is an important additional failure. It changes the vertical allocation without recomposing the center: How It Reacts, market history, execution, and research print into one another.

![New Idea after Review click at 2000×963](/Users/tinker/.codex/visualizations/2026/07/21/019f86e2-6cbb-7f93-9e2d-e4685b6c9602/strikebench-plan-review-445d4f1/review-order-2000x963.png)

This matters because the plan requires every click state, not just the resting screenshot.

#### Candidate rail

Improved:

- “Collect” is no longer the only semantic label;
- low-POP points remain in the risk-map domain;
- rows are actionable.

Unresolved:

- internal catalog copy still consumes the top of the rail;
- long strategy names truncate;
- dense columns compete with verdict meaning;
- missing capital can still render as `$0`.

#### Leg workbench

The hierarchy remains backwards:

- repeated expiry per leg;
- repeated source/freshness per leg;
- prose such as `fill: sell at bid`;
- side/type labels and tiny steppers receive space before full executable bid/ask;
- Position’s shared-looking leg row omits the complete book.

The target remains:

`SELL CALL   290   ×5     bid 11.89 | ask 12.10`

with executable side highlighted, package-level expiry/source once, leg expiry only when different, and a complete two-line mobile grammar.

#### How It Reacts and playback

The eight generic descriptions remain in the source and are displayed on wide screens. They restate the headings and force taller tiles.

The playback ribbon still combines:

- a prose sentence;
- Unicode play/pause;
- undersized speed and step controls;
- repeated scenario facts.

Use one shared compact `ScenarioSpectrum` and one SVG `IconButton`/`Stepper` family. Resting scenario tiles need only name, move, trajectory glyph, match/probability, and P/L.

#### Evidence, market, and news

Evidence & Paths is visible at 2560 in the current build, but it is not robust:

- its heading text collides;
- the fan’s appetite crowds the lower market owner;
- the current visual test excludes `.scenpanel` and `.marketlens`;
- New Idea shows three sliced headlines and does not expose the remainder.

Quote, history, chain, package execution, and news must remain independent receipts. Each can fail or overflow without hiding the others.

#### Mobile

At 390×844:

- the whole New Idea surface is an internal scroller;
- news introduces a nested scroller;
- candidate buttons are about 30px tall;
- side/type controls are about 18px tall;
- steppers are about 22×26px;
- market, execution, and research headings collide;
- the fan consumes nearly a viewport;
- the order dock truncates.

![New Idea mobile, top](/Users/tinker/.codex/visualizations/2026/07/21/019f86e2-6cbb-7f93-9e2d-e4685b6c9602/strikebench-plan-review-445d4f1/newidea-390x844-top.png)

![New Idea mobile, lower panels](/Users/tinker/.codex/visualizations/2026/07/21/019f86e2-6cbb-7f93-9e2d-e4685b6c9602/strikebench-plan-review-445d4f1/newidea-390x844-bottom.png)

The current mobile test accepts 24px touch targets. The product contract is 40–44px.

### Position: good doctrine, incomplete convergence

Position’s fresh-eyes question and lifecycle evidence are valuable. The current surface still has four structural problems:

1. Position→New Idea carries cloned legs but not durable source-position identity or explicit management intent.
2. On arrival it snaps strikes to the nearest listed contract and clears entry prices in [`index.html`](/Users/tinker/output/optin/src/main/resources/public/index.html:3321). That is a new package, not an exact held-package fork.
3. “Forward test” currently resets and plays the stored fan. It has no job state, progress, persisted result, or failure receipt.
4. Position, Book, and New Idea still own separate fan/risk-map renderers.

At approximately 2000×963, lower scenario/path content begins below the visible fold. At 2560×1440 it fits but retains oversized canvases and fixed appetites. At 1000 and mobile, internal/nested scrolling returns.

![Position at 2000×963 — lower primary panels begin below the visible composition](/Users/tinker/output/optin/dom-tests/shots/adv-m9/pos-2000x963.png)

![Position at 2560×1440 — complete, but still dominated by fixed canvas appetites](/Users/tinker/output/optin/dom-tests/shots/adv-m9/pos-2560x1440.png)

The migration must preserve exact held identity, reuse shared rendering components, and make Forward Test a real backend operation with an explicit approximation/evidence tier.

### Market, chain, watch, and news

Genuine improvements:

- 1D and 1W now honestly mean one and five stored daily sessions;
- quote, history, chain, and news have more independent terminal states;
- history provenance is explicit.
- the old always-similar parabolic expected-move cone has been removed from the current chart path; the browser now fetches the backend p16/p50/p84 receipt and renders quiet horizontal expiry levels.

Remaining:

- option-chain rows are hoverable `div`s, not keyboard/touch actions;
- market focus is a browser-only `marketSymbol` separate from persisted `focusedSymbol`;
- Home watch policy remains a hardcoded SPY/QQQ/IWM/DIA/TLT/GLD selection and fixed slices;
- New Idea and Position hide headline overflow rather than offering an actionable `+N more`;
- Expected Move remains calculated in both `SimulationEngine.MarketImpliedRange` and `TradeService`. The current formulas are intentionally aligned, but that is still two owners of one fact; the trade preview should consume the canonical receipt rather than restate the lognormal calculation.

## CSS and component consolidation

The repository now has one physical stylesheet, which is progress. It does not yet have one coherent CSS architecture.

Current `app.css`:

- 3,873 lines;
- 87 media queries;
- many surface-specific `overflow:auto|scroll` rules;
- multiple Stepper/play-button sizes;
- overlapping desktop-height and width rules;
- hardcoded near-black component backgrounds outside a small token grammar;
- a width-unbounded 851–999px height query at [`app.css`](/Users/tinker/output/optin/src/main/resources/public/app.css:3762).

The same control is repeatedly resized by selectors such as:

- `.smstps .mstp button`;
- `.declegs .mstp button`;
- `.scenpanel .srow-ctl .mstp button`;
- `.focus .card ...`;
- mobile overrides.

This is why fixes at one resolution regress another.

The required consolidation is not “move more rules into app.css.” It is:

1. Tokens and reset/base.
2. Shared component blocks:
   - `IconButton`
   - `Stepper`
   - `LegRow`
   - `ScenarioSpectrum`
   - `EvidenceReceipt`
   - `OverflowList`
   - `PathFan`
   - `RiskMap`
   - `MarketContext`
3. Surface composition blocks for Home, New Idea, and Position.
4. Container-query density owned by each component.
5. A small, non-overlapping responsive policy.
6. Delete the superseded selectors after each component migration.

One file is a necessary physical constraint, not proof of consolidation.

## CI and browser-test assessment

Keep the browser suite. It is catching the exact class of failure the backend suite cannot see. Fix its truthfulness.

### Current coverage

- Java: clean test pass.
- Browser contracts: 130 actual tests, two skipped, all passing.
- Visual: 28 passing.
- Packaged journey: authentication only.
- `desk-backend.test.js`: 9,755 lines in one process.

### Acceptance holes

1. The visual suite filters the known 1000×800 Home overlap.
2. New Idea geometry lives in the contract monolith, not the visual lane.
3. New Idea omits 2048, 1280, 1000, 375, and 320.
4. `.marketlens` and `.scenpanel` are explicitly excluded from the New Idea clipping check.
5. Vertical page/panel scroll is not prohibited on wide desktop.
6. chart-owning viewports are excluded from clipping checks.
7. phone controls need only be 24px.
8. “Every offered action” measures Home controls but does not click them.
9. Review/order-open, inspect tabs, disclosures, news overflow, loading recovery, and Position→Idea are not crossed with every viewport.
10. Screenshots are diagnostic artifacts, not a baseline or explicit reviewer gate.
11. The untracked `m7adv.test.js` is not release evidence.

The next test increment should first make the current known failures fail honestly, then add packaged product journeys and the full state/viewport/action matrix.

## Corrected completion sequence

This is the dependency order for the remaining work. It preserves all prior scope.

### 1. Make CI evidence truthful

- aggregate every TAP shard;
- bind reports to the exact SHA;
- fail required lanes on zero tests;
- fix/verify artifact download paths;
- remove known-success filters;
- report retry/flaky counts separately.

### 2. Close live correctness leaks

- make semantic money renderers null-safe;
- make financial wire facts nullable where unavailable;
- stop browser-originated displayed scenario values;
- route Book rank/share to the one typed receipt;
- migrate every package-price consumer;
- delete the duplicate expected-move calculation.

### 3. Finish one WorkspaceContext

- implement the server `clear` contract in the browser adapter and mock;
- commit world + reconciled context atomically;
- persist package/evaluation/position/return identity;
- restore Position/New Idea on cold boot;
- fold market focus into the persisted context;
- make Back use durable return focus.

### 4. Consolidate shared UI ownership

- build and migrate shared IconButton, Stepper, LegRow, ScenarioSpectrum, EvidenceReceipt, PathFan, RiskMap, OverflowList, and MarketContext;
- delete parallel markup/renderers and their CSS after migration;
- keep `app.css` as the single file, organized by tokens/components/surfaces/responsive rules.

### 5. Finish Home

- adaptive activity rail;
- stable Scout result owner;
- proportional market/chart/chain allocation;
- useful sparse and empty compositions;
- deliberate intermediate layout;
- single mobile page scroller.

### 6. Finish Scout presentation and edge cases

- four named receipt lanes per result;
- attainable progress totals;
- explicit adoption-unavailable state;
- exact canonical New Idea handoff.

### 7. Finish New Idea

- correct leg hierarchy;
- compact shared scenarios;
- graphical playback;
- resolve center/right geometry at all required viewports;
- independent evidence/market/news receipts;
- actionable news overflow;
- stable Review/order-open composition;
- 40–44px mobile targets and one page scroller.

### 8. Finish Position

- exact source-position fork;
- named management intent;
- real Forward Test job/progress/result;
- shared fan/map/scenario/legs/receipts;
- correct responsive priority order.

### 9. Finish market, chain, research, and copy

- actionable chain rows;
- persisted market focus and watch policy;
- actionable headline overflow;
- remove implementation prose and repeated instructions;
- replace Unicode controls with shared SVGs.

### 10. Prove and delete

- packaged Home→Scout→New Idea→order/Book journey;
- packaged Position→exact New Idea journey;
- packaged world transition without stale artifacts;
- every action/disclosure at 2560, 2048, 1920, 1440, 1280, 1000, 390, 375, and 320;
- exact rendered financial strings;
- no default wide-desktop page/panel scroll;
- one mobile page scroller;
- screenshots/baselines or explicit reviewer evidence;
- then delete superseded state, renderers, helpers, endpoints, and CSS.

## Definition of done

Do not mark this program complete until all of the following are true:

- every displayed financial fact traces to one backend receipt;
- null/unavailable never becomes zero;
- world and workspace context change atomically;
- Home, Scout, New Idea, and Position preserve exact context through every transition and reload;
- Home has no overlap at any required viewport and uses space according to content value;
- New Idea and Position show all primary content at both full desktop targets with only one intentional overflow owner where content genuinely exceeds capacity;
- mobile has one page scroller, no nested vertical scrollers, no horizontal clipping, and 40–44px actions;
- Scout rows stream, settle, explain their four lanes, and always open the exact package;
- Position→New Idea preserves exact held identity;
- every offered action works and produces a visible state/result;
- required browser lanes cannot report green with zero tests, filtered failures, stale TAP, or missing packaged journeys;
- all duplicate state stores, financial consumers, renderers, components, APIs, and CSS rules superseded by the program are deleted.

## Final assessment

The current trajectory is directionally correct. The implementation should continue from the new canonical owners rather than revert or invent replacement engines.

The next developer should not spend another cycle tuning isolated rectangles. The work must proceed through the corrected sequence above: truthful evidence, financial authority, context authority, shared components, then composition and interaction completion. That is the shortest path to both correctness and the coherent “breathing workspace” product the plan describes.
