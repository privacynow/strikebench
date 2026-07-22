# Desk UX unification & correctness handoff

Date: 2026-07-22 · Branch: `feature/journey_refactor` · Last commit this effort: `ace2641`
Prior context: `DESK_MARKET_CONVERGENCE_HANDOFF.md` (data/budget/snapshot ops) and `DESK_REVIEW_AND_DIRECTION_HANDOFF.md` (product direction). This note is the source of truth for the UI/UX state, the canonical components, the correctness contracts, and the verification protocol. It is written so a new implementer can continue without this conversation.

## 0. The one rule that governs everything

**One component per concept, and New Idea (Decide) is the reference design language.** Every regression in this effort traced back to a parallel implementation: a second scenario UI, a second leg list, a second candle renderer, a second interaction rect, a second label style. Before building ANY surface element, find the canonical owner below and extend it. If you catch yourself writing a new chart/list/tile for something that exists, stop.

## 1. Canonical components (client, all in `src/main/resources/public/desk.html`)

| Concept | Owner | Used by |
| --- | --- | --- |
| Scenario stories ("How it reacts") | `decScenSpectrum` + `scenExpand` + `pinScen`/`scenPlay` | New Idea center, fixture bloom, authoritative Position stage (as of `6047f49`). `scenData(p)` prices held packages via terminal `payFor` per story. |
| Path fans (paths + quantile bands + hover + click-to-pin) | `drawMCFan` (idea), `drawAuthoritativePositionPaths` (position), `drawAuthoritativeBookFan` (book) — one interaction grammar: chart-wide `.mcinteraction` surface, nearest-path hover (`nearestMCPath` / `nearestPositionPath` / `nearestBookFanSeries`), accent `.hot` highlight, `.mcpathread` narration, click pins the exact path | Full renderer merge is the remaining consolidation (see §5) |
| Exact-path pinning | `buildExactPathPins` + `FAN_STORY` (=-9) sentinel + `_customStoryPins` | `playMCPath` (idea), `pinPositionFanPath` (position) |
| Package life boundary | `authLifeIndex` / `authLifeSessions` / `authFanForLife`; book fan applies the same rule inline in `authLoadBookFan` | EVERY fan, playback, scrub, risk journey, story-day clamps |
| Market history chart | `histWidgetHTML`/`drawHistChart` + `HIST_STORE` (one `range=max` fetch per symbol via `DeskBackend.symbolHistory`) | Home pulse, Decide market band, bloom Price history |
| Candle drawing inside that widget | part of `drawHistChart` (do NOT resurrect `drawCandleSeries`) | — |
| Option chain slice | `authHomeChainHTML` | Home pulse, Decide market band, bloom (`authPositionChainHTML` wraps it) |
| Symbol market context (research/news/history/expirations/chain, cached) | `DeskBackend.symbolContext` → `loadBookSymbolContext` | Decide band, bloom chain, Home rows |
| Leg rows | `.declegs`/`.legr` chip grammar (see `decLegWorkbench`, `authPositionLegsHTML`) | New Idea workbench, bloom side rail |
| KPI cells | `kcell` + `.kgrid.boxed.numrow` | heroes, Book Risk row |
| Summary line blocks | `.bookline .sblock` | Home account summary (`authBookSummary`) |
| Position identity colors | `positionColor(id)` / `POSITION_COLORS` | roster dot, book-fan lines, legend — always matched |
| Interaction surface | `.mcinteraction` — **global transparent rule; never re-scope** (an unscoped SVG rect fills BLACK) | all charts |

## 2. Correctness contracts (violations here are prime-directive failures)

1. **The package's timeline ends at its expiry, not the ensemble horizon.** Plans hold 45-session ensembles; seeded packages expire earlier (e.g. 08-21 ≈ session 21–23). Post-expiry P/L is settled — drawing/animating it produces flat lines and frozen readouts against a moving price. `authFanForLife` (and the inline book-fan truncation) own this. Axis labels say `exp 08-21 · 23 sessions`.
2. **Playback mappings must be day-linear and mutually consistent**: `authoritativeFrame(p,t)` maps t over the life-bounded row index; chart cursors map t over the (life-truncated) path points; reveal clips are pixel-linear. Grids are uniform (⅓-session steps) so these agree — verify with the t=0.25/0.5/0.75 frame-day vs pixel-day check whenever touching any of them.
3. **Clicking a path pins THAT path**: pins sampled from the clicked polyline (`buildExactPathPins`), backend `NEAREST_AUTHORED_WAYPOINTS` returns the same source row as focus (assert `receipt.focusSourcePathIndex === _customStoryPins.sourcePathIndex`). Domain-carry (`decide._fanCarry`/`p._fanCarry`) keeps the chosen path visually in place; cleared on tile-pin/unpin/candidate switch.
4. **Flat P/L tails inside a package's life are honest saturation** (bounded payoff); `fanSaturationNote` captions them. Never "fix" them by resampling.
5. **No fabricated data, ever**: honest-null pills (1D until an intraday collector exists), true weekly OHLC aggregation only (labeled), no smoothing/skipping, stored ranges are zero-upstream-call reads under the politeness engine (`range=max` fetched once per symbol; AMD/NVDA hold ~5k bars back to 2006).
6. **Frozen plans**: a plan that owns a placed trade is not a mutable working idea — the backend refuses resume. "Analyze in New idea" opens a fresh idea on the symbol unless `assumptionsEditable !== false && status ∈ {DRAFT, ACTIVE} && open !== false` (the `authWorkingPlanRows` predicate).

## 3. Layout decisions that took real debugging (do not re-learn these)

- **Home top row cells are ~303px tall at 2000×963** (board rows `minmax(350px,.72fr)` under the ≤1199px-height media). The Market pulse panel therefore: one hero line with inline facts, chart|chain as two grid columns (`.pulsecols`, row `minmax(0,1fr)`), chain rows pinned to 21px with a single-line header (a wrapping `lenshd` hint eats a chain row). Below 1700px width the panel grows (`height:auto;min-height:100%`) and the board scrolls.
- The account summary is ONE `.bookline.authsummary` line. Do not reintroduce the `perfband` card band.
- The bloom research band is `296px` tall at ≥1101px width, five panels: research | option chain | price history (chart flexes ≥120px) | news (fills, no inner scroll) | management.
- Compact widget hosts (bloom, Decide band) keep every control but drop the context strip; Home keeps it.
- Book fan labels: one right-aligned column with dotted leaders when `W≥430`, sym-only at line ends otherwise.
- `.dcleft .fan` caps at `min(56vh,660px)` only on ≥1900×≥1200 canvases; below 1080px height the workbench and map share one row so the ranked list keeps ≥5 whole rows.
- Candidate pager: page size measured from the list's real share of the column (sibling geometry), never the viewport.

## 4. Verification protocol (mandatory — this effort shipped a black chart by skipping it)

For any visual/interactive change: (1) full screenshots at 2000×963 AND ~1260 wide — look at every changed panel; a wrong-looking region is a bug until a measurement explains it; (2) sample reality: `elementFromPoint` for occlusion, parse `d` attributes for colinearity, dump raw series; (3) cross-check animation vs chart numerically at several t; (4) geometry via raw pixels vs the clipping ancestor (clipped children still report full rects); (5) `node` syntax-check the inline script, rebuild (`mvn -q -DskipTests package`), restart on 7070 with the documented env (see `DESK_MARKET_CONVERGENCE_HANDOFF.md` §Packaged development server), **cache-bust** (`?v=N` — the pane caches desk.html), and nudge-resize if the compositor shows a stale frame; (6) console must be error-free; (7) commit only after all of it.

## 4b. Round `ace2641` — what changed and the new traps

**Ranked ideas are a TABLE** (`fanList` + `.fanhead`): one 30px row per idea, column labels once
in the header, verdicts as glyphs via `fanVerdictIcon` (✓ endorsable · ✕ blocked · ▾ unfavorable —
the red EV beside it is the why · dashed amber ring = evidence incomplete, tooltip carries the CTA
"select the row, comparison completes it" · ⊘ screened). Stress/capital columns hide ≤1700px;
numbers ≤500px. Grid template lives ONCE on `.fan .fanr,.fan .fanhead`.

**Honest default fan in the bloom**: no auto-pin. `authEnsurePositionFutures(p)` loads the
UNCONDITIONED ensemble slice (`DeskBackend.positionFutures`, same envelope as the scenario
animation) so "Possible futures" shows real terminal variance; pinning a tile is what conditions
(bunched terminals under a story are BY DESIGN and labeled). Unpinning re-requests the futures.
The what-if chart defaults to PRICE space — both `authPositionScenarioStageHTML` AND
`authPositionScenarioFan` check `_positionPathView==='pnl'` (they must stay in agreement).

**Flex-basis traps that hid whole panels** (all fixed, do not reintroduce):
- `flex:1` means basis 0. The ranked list (`.dcleft .fan`) and the center stack (`.dccenter
  .dcctop` in the ≥1900×≥1000 media) need `flex:1 1 auto` (content basis) or a sibling with a
  real basis starves them — this is what made the payoff hero vanish at 2560×1440.
- `syncCandidatePageSize` reads the leftsupport sibling's `flex-basis` (not its live height),
  because the support is elastic; keep a px-resolvable clamp on `.dcleft .leftsupport`.

**Cross-browser floors** (Safari's wider font metrics wrap control rows and then crush any
`min-height:0` flex/grid child — Chromium hides the same bug until a few px shorter):
- Home pulse: two-part layout (chart | chain) applies from **1001px** width, with
  `min-height` floors on `.authhomehistory` (168) and `.authchainslice` (150).
- `.authhomehistory .histchart` floor 112px — never `min-height:0`.
- Home charts redraw on resize (`redraw()` book branch calls `drawAuthoritativeHomeHistory`).
- Right Decide column: `.understand{overflow:hidden}` (bleed-proof), market band bounded
  (`max-height:clamp(210px,36vh,340px)` at 851–1150 heights) with news scrolling inside.

**Audit tooling baked into the page**: `?goto=idea:SYM` / `?goto=pos:SYM` deep-links (wait for
hydration, reuse the real openers) and `?probe=1` (fixed overlay reporting viewport/grid/track
geometry so a screenshot of ANY browser yields numbers). The dev Mac's display is
**1920×1080** — real-Safari verification maxes out there; 2560×1440 is verified in the pane
numerically (the pane compositor can't screenshot 2560 — it renders a miniature).

**Chain rows are interactive**: `data-chain-k` + `data-chain-sym` → document-level mouseover
draws a dashed strike rail into the SAME symbol's history chart cross layer (`histYv` is the one
y-mapper). Next step if asked: click-a-strike seeds a leg in the workbench.

**Bloom**: legs panel header has `✎ Edit legs` (`data-auth-manage="resume"` — mutable plan opens
its working idea, else a fresh seeded idea). Price history column is the widest
(`1.55fr`); band height `clamp(230px,25vh,380px)` at ≥1101px width.

## 5. Remaining work, in order of value

1. **Fan renderer merge**: `drawMCFan`, `drawAuthoritativePositionPaths`, `drawAuthoritativeBookFan` share grammar but not code — extract one `drawPathFan(svg, model)` core. Mechanical, medium risk; do it with the battery from §4.
2. **Position-lens merge into the one Workspace** (owner-decided architecture: Book/Position/Idea states, one grid, dock swaps execute↔manage). The bloom now has component parity; what's left is layout unification, then folding Home in (mostly deletion).
3. **Home second row**: Book Risk cell still has slack at tall viewports; Working ideas could compress further. Candidates for the Workspace merge rather than more Home surgery.
4. **Intraday collector** (backend, politeness-governed): unlocks the 1D pill and min-max line decimation.
5. **Phase-2 joint correlated multi-symbol ensemble** in `PathEnsembleService`: the true book-level fan/POP (current book fan is honestly labeled independent-never-summed).
6. Yahoo gap-fill after the UTC reset + Cboe snapshot + bundle recapture — scripted verbatim in the implementation handoff.
7. Responsive acceptance sweep (1920/1440/1366/1280/tablet/mobile) with the §4 protocol; MARKET/LIMIT review exercise; cold/warm New-Idea timing with named progress.

## 6. Verified state at handoff (all live on 7070, console clean)

Home: one-line summary; compact colored roster (colors match fan/legend); book fan lives end at expiry (21/21/21/21/1); pulse = hero line + legible candles/cone + full 5-row chain + regime foot; Book Risk k-cell row; headline-list news. New Idea: 5 whole rows + honest pager; single-sentence comparison notice; exact-path pin verified (source row === focus row, in-place via domain carry); life-bounded playback (23/23 at t=1); market band standing. Bloom: shared spectrum (8 tiles, pin→play verified), shared leg chips, taller price chart (~183px), Option chain panel, entry story on the history chart, "Analyze in New idea" opens a working Decide, Forward test plays.
