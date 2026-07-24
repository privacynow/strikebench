# StrikeBench Home / Scout / Desk Recovery Handoff

Date: 2026-07-23  
Branch: `feature/journey_refactor`  
Current committed tip: `2cbb94f — work in progress`  
Deployment: none; production was not touched  
Owner server: port 7070 was initially PID 94300; it stopped listening concurrently while this
note was being written. I did not stop it. Do not start, stop, restart, or rebuild under 7070
without first coordinating with the owner.

## Accountability

The prior Codex execution failed the owner.

I consumed far too much time and token budget, repeatedly made local changes without converging on a coherent Home composition, and let a supposedly focused UI repair expand into a 24-file work-in-progress commit. The owner repeatedly and specifically asked for a rich, useful, consistent Home, no clipping, no dead controls, a permanent Scout/New-Idea interface, streaming Scout results, and canonical New-Idea handoff. I did not deliver a finished, fully verified result.

The execution was stupidly wasteful in concrete ways:

- I kept treating visible symptoms one at a time instead of freezing a single composition and validating it end to end.
- I added explanatory copy and implementation receipts where the owner wanted clear actions.
- I repeatedly filled scarce panels with internal vocabulary such as “canonical catalog,” “strategy coverage,” “governed source,” and “all markets,” then had to revisit it.
- I allowed a Home-only presentation to drift away from New Idea’s established visual grammar.
- I left controls and summary text that were technically present but not meaningfully actionable.
- I claimed progress after narrow checks while the actual 1920×1080 composition still wrapped, clipped, wasted space, or exposed dead promises such as “+9 more headlines.”
- I began a final news/compact-content patch and was interrupted before completing its JavaScript or validating it. The tree is therefore not in a handoff-ready product state even though parts of the backend work are promising.

This is my responsibility, not the owner’s. The owner supplied unusually specific feedback, screenshots, target resolutions, architecture constraints, and exact product intent. The failure was execution and convergence.

## Do not violate these boundaries

1. Do not touch the owner’s server on port 7070.
2. Do not spend Yahoo allowance while investigating. Use `YAHOO_ENABLED=false` for private verification. The owner’s recorded daily allowance was exhausted during the original review period.
3. Use a private port and isolated database for browser work, then remove both.
4. Preserve the owner-created untracked files:
   - `DESK_REVIEW_AND_DIRECTION_HANDOFF.md`
   - `prototypes/`
5. Do not discard or overwrite `2cbb94f` indiscriminately. It is a mixed work-in-progress commit
   described below, and it was pushed to `origin/feature/journey_refactor`.
6. Do not add a second recommendation engine, risk engine, strategy catalog, lifecycle engine, market-data owner, or duplicate API. Extend the existing canonical owners.
7. Do not remove capabilities. The full recommendation, research, simulation, paper-trading, tracked-account, position-management, and strategy functionality must remain.
8. Do not reintroduce top-level tabs, subnavigation, or nested desktop scrolling as a shortcut.
9. No silent provider spend. Scout remains explicitly user-triggered.
10. Validate real viewport compositions, not only DOM existence.

The private Codex test server on port 7097 was stopped and its isolated database
`sb_codex_visual_20260722` was dropped. I did not touch port 7070. A final listener check found
no process on 7070 after the previously observed owner process disappeared concurrently.

## Source-of-truth documents to read first

Read these completely before changing code:

1. `DESK_REVIEW_AND_DIRECTION_HANDOFF.md` — owner-created, untracked, preserved.
2. `DESK_MARKET_CONVERGENCE_HANDOFF.md`
3. `DESK_UX_UNIFICATION_HANDOFF.md`
4. `POSITION_LIFECYCLE_SPEC.md`
5. `PROGRAM_ONE_HANDOFF.md`
6. `STRIKEBENCH_ONE_SPEC.md`
7. `AGENTS.md`

The owner’s prior analysis and the repository audit established an important correction:
the repo already has `OpportunityScanner`, `AutoRecommender`, `CompensationView`,
`PlanAdoptionReviewService`, `ManagementPlanner`, `StrategyCatalog`,
`RecommendationEngine`, `PathEnsembleService`, and Book-risk owners. The correct work is
composition, lifecycle receipt reuse, policy extension, and transport/UI delivery—not another engine.

## Owner’s non-negotiable product direction

### One workspace, three focus states

The settled direction is one persistent workspace with:

- **Book state** — nothing focused.
- **Position state** — a held package focused.
- **Idea state** — a proposed package focused.

Shared panels rebind to the current subject. A single context slot changes between roster,
ranked candidates, and management. This must feel like breathing in and out of context,
not navigating among separate products.

The owner is open to reusing the excellent New Idea surface as the detailed result view,
especially for Scout results. The owner is not asking to cram all Book, Position, and Idea
panels onto one screen simultaneously.

Do not reintroduce tabs or subnavigation. At 1920×1080 and 2560×1440, the default
composition should have no scrolling. Genuine overflow may scroll elegantly in the one
list that owns it: whole-row scroll snap, fade masks, a visible “+N more” cue and position
hairline, hidden scrollbar chrome until intent, and never nested scrollers. Mobile/tablet
may use normal page scrolling with priority stacking and no horizontal overflow.

### What Home must be

Home must be a rich, useful Book-level command surface, not a collection of empty cards.
It must remain useful with:

- zero positions,
- one position,
- several positions,
- zero working ideas,
- many working ideas.

The owner repeatedly rejected the “hungry orphan child” empty Home. Empty states must
lead with useful market context and opportunity discovery, not apologetic voids.

Home needs:

- a concise cash-truth/account strip;
- a compact position rail ordered by today’s lifecycle decision, not raw P/L;
- compact Book/possible-futures context;
- a permanent, rationalized Scout + exact-New-Idea control surface;
- a broad, persistent market/sector lens at the top;
- actionable cross-sector market rows;
- a useful market-history + option-chain instrument;
- accessible research/news;
- compact working ideas;
- every visible row and primary control doing something clear.

Home should use New Idea’s established visual language: bordered controls, restrained
accent use, consistent panel surfaces, typography, spacing, and action grammar. The owner
specifically rejected black, borderless label-like boxes and Home-only styling.

### Permanent Scout + exact-idea workbench

The owner wants one permanent panel, not a bloom, modal, popover, or “genie from a lamp.”

It must provide input/control parity with New Idea:

- underlying or sector/market field;
- goal: Income, Directional, Acquire, Hedge, Exit;
- view: Bearish, Neutral, Bullish;
- horizon;
- risk posture;
- Acquire price and quantity when Acquire is selected;
- explicit Scan for a field;
- explicit Analyze/Shape for one underlying.

Typing or selecting a symbol must stage it only. It must not immediately navigate before
the user can choose the remaining declarations.

Sector selection belongs in the persistent Home market lens at the top. It must stay open
and be usable. Scout may consume that selected lens but must not hide or replace the market
panel.

Avoid vague/internal copy:

- “All markets” is misleading; the actual scope is a curated set of optionable US stocks,
  ETFs, indexes, and represented sectors/groups.
- “Canonical catalog,” “strategy coverage,” “governed source,” “four named lanes,” and
  similar implementation language do not belong in the primary UI.
- Strategy names shown as inert text are not useful. If strategy choice is exposed, it must
  be an actual control or a concise statement that all compatible strategies compete.

### Scout

Scout is a real cross-symbol, cross-sector opportunity scan. It must not be semis-only or
megacap-only. It should surface premium/income, acquisition, directional, hedge, and exit
opportunities according to the chosen goal while keeping economics authoritative.

Requirements:

- explicit user-triggered scan;
- progressive, streaming results as symbols finish;
- immediately visible progress;
- each provisional/final row actionable;
- clicking a row opens the canonical New Idea surface with the same symbol, goal, view,
  horizon, risk, and any required target/quantity declarations;
- New Idea then owns all exact candidates, payoff, scenario, market, evidence, legs,
  economics, and execution;
- Scout never replaces or hides Market/Sectors;
- Home may deduplicate to one best row per symbol, but New Idea must show every viable
  strategy for that symbol.

The owner later clarified sequencing: finish Home first; make Scout streaming and actionable;
only then generalize streaming architecture to other slow APIs/screens. Do not over-engineer a
global streaming framework before Home and Scout are correct.

### Strategies and risk-managed acquisition

Do not invent a new risk engine. Improve existing `StrategyCatalog`,
`StrategyIntent`, `StrategyBuilder`, `RecommendationEngine`, `AutoRecommender`,
`CompensationView`, Book risk, and existing APIs.

The owner expects viable strategies, when supported by chain/evidence/economics, including:

- cash-secured put;
- covered call;
- bull put credit spread;
- bear call credit spread;
- debit call/put verticals where goal-compatible;
- iron condor;
- iron butterfly;
- call and put calendars;
- covered strangle;
- existing covered-call spread variants;
- defined-risk and time-spread alternatives for acquisition/income;
- diversity/concentration and expiry/event risk management.

Acquire means acquiring shares at a declared price and quantity. The UI must ask for both.
The engine must distinguish direct-delivery structures from alternatives that cap or reshape
risk but cannot deliver shares. Do not claim that every vertical or calendar delivers stock.

Carry/compensation belongs beside EV and may never overrule unfavorable after-cost economics.
Book concentration, event windows, expiry clustering, quantity-level assignment willingness,
and destination-Book fit can demote an otherwise rich carry opportunity.

### Position lifecycle

The existing lifecycle plan calls for:

- HARVEST;
- KEEP;
- REDUCE;
- DEFEND;
- ACCEPT_ASSIGNMENT when assignment is truly the active decision.

“DEFEND” is not a typo for “defined,” but it was unexplained and alarming on Home. Compact
Home labels should use plain action language such as “Action required,” with the precise
mechanical trigger visible and the full policy receipt available in Position state.

Unrealized P/L is history, never a hold signal. The fresh-eyes question remains:
“Would you open the exact position you still own today?”

### New Idea

The New Idea result screen is the strongest existing surface and should remain the canonical
detailed analysis.

Known defects/requirements:

- `+ New idea` from inside New Idea must not produce a crushed 88px symbol-picker sliver.
- Starting another idea must not leave the old idea vivid and confusing behind a replacement
  composer.
- The transition should preserve declarations deliberately but create a fresh Plan when
  appropriate.
- Home and New Idea must have control parity.
- Acquire must request price and shares before evaluation.
- Few-result states must not look bare or cramped.
- Candidate rows should be compact at rest; detail belongs on focus/expand.
- Candidate pagination/overflow must measure the candidate list’s real content box, not a
  viewport breakpoint.
- The Leg Workbench should move up into available space; the Risk/Reward map should grow.
- Legs should carry richer information when space permits.
- Inline leg editing should be used where feasible; do not force a separate editor for every
  adjustment.
- The zero-EV/reference line must not strike through the “ideal” label.
- Columns two and three must not be dimmed unless a temporary, explicitly staged transition
  truly requires it.

### Market / sectors / chart / chain / news

The owner rejected a Market Watch hardcoded to four tech names. It must span broad-market
ETFs, rates, commodities, sectors, and relevant Book/idea symbols.

Current observed defects:

- Market Pulse defaults to/sticks on NVDA because Home’s context symbol list is Book/Plan-first
  and the first symbol is hydrated as the detailed market subject.
- “All markets” is vague and inaccurate.
- Clicking sector controls previously failed to stay open.
- Scanned ideas previously overwrote the markets/sectors panel.
- Some market rows wrap or truncate without enough useful action.
- The option chain remains too narrow relative to the chart.
- 1D and 1W use daily bars, so 1D has one candle and 1W has five. The code now suppresses the
  forward cone for these windows and spreads the wicks across the plot, but the candle bodies
  remain capped at 9px. They still look visually empty. Do not fabricate intraday data.
  Either give these short windows a deliberate latest-session/five-session detail grammar,
  with wider candles and OHLC/range/change facts, or clearly label the daily-session limitation.
- The market pulse must default to a broad-market benchmark (normally SPY when available), or
  a sector benchmark for a chosen sector, unless the user deliberately focuses a symbol.
- A deliberate symbol focus must remain stable and race-safe.

Research & News currently renders only 3 headlines in a populated Home and appends dead text:
“+9 more headlines · open Market in New idea.” There is no way to reach those headlines.
Every returned headline must be reachable. The intended Home behavior is one elegant
headline-list scroller on desktop, using the existing `bindElegantList` fade/hairline/count
grammar, and normal page flow on mobile. Do not add another modal or navigation layer merely
to expose the list.

### Book possible futures and sparse-position panels

The owner’s screenshots show that “inside the boxes” is also broken:

- long Book-fan header hints wrap;
- Book and AMD legend rows wrap inside a very narrow rail;
- “gain odds” repeats excessively;
- the one-position four-fact receipt is cramped;
- the chart readout is too long for the panel;
- lifecycle summary/facts wrap into dense fragments;
- a single position still causes several wide panels to reserve space inefficiently;
- Position Bloom leaves an empty column with one position and applies an inconsistent blue
  border.

Fix the content grammar, not only outer overflow. Home needs short labels and compact facts;
Position/New Idea can retain full receipts.

## Current branch and worktree

While this note was being written, the tracked batch was concurrently committed and pushed by
`tinker <tinker@home>` as:

```text
2cbb94fef54a02932be30ce8bfc5739607669739 — work in progress
```

That commit contains 24 files, approximately 1,605 insertions and 395 deletions. The prior tip was
`61ddcb7`.

`git status --short` after that concurrent commit:

```text
?? CODEX_HOME_REFACTOR_FAILURE_HANDOFF.md
?? DESK_REVIEW_AND_DIRECTION_HANDOFF.md
?? prototypes/
```

The handoff note itself is deliberately untracked. The other two untracked entries are
owner-owned and must remain untouched.

Treat `2cbb94f` as several separable increments, not as a release-quality convergence commit.

## What commit `2cbb94f` attempts

### Yahoo 400 isolation

Intended behavior:

- HTTP 400 is request-local, not provider throttling.
- Do not activate a provider-wide 30-minute cooldown for one malformed symbol/range.
- Normalize Yahoo class symbols such as `BRK.B` to `BRK-B`.
- Reject invalid date windows before provider spend.
- Quarantine a deterministically rejected symbol for the process.
- Log the first rejected symbol/range and Yahoo diagnostic; skip repeats quietly.

Files:

- `YahooFinanceProvider.java`
- `MarketDataService.java`
- `ProviderPoliteness.java`
- their tests

No Yahoo calls were intentionally made during this batch.

### Scout streaming

Intended behavior:

- the existing `POST /api/research/scout` negotiates NDJSON;
- progress frames arrive while bounded concurrent symbol work runs;
- provisional signal rows may arrive before final priced ideas;
- final frame remains the canonical complete Scout receipt;
- JSON fallback remains valid;
- Safari gets a response preamble to encourage streaming flush;
- no extra symbol evaluation/provider spend is added merely for progress.

Files:

- `DiscoveryController.java`
- `AutoRecommender.java`
- `desk-backend.js`
- API/AutoRecommender tests

The last targeted backend run after this work was:

```text
mvn -q -Dtest=AutoRecommenderTest,ApiIntegrationTest test
```

It passed. This is not a substitute for the full suite or browser verification.

### Strategy/Acquire extension

Intended behavior:

- extend existing strategy owners, not create another engine;
- make ACQUIRE consider CSP, credit put spread, debit call spread, and put calendar where
  mechanically/economically appropriate;
- steer short put/calendar strikes using target price;
- size defined-risk alternatives through the existing risk budget;
- label direct delivery versus protected alternatives honestly.

Files:

- `StrategyIntent.java`
- `StrategyFamily.java`
- `StrategyBuilder.java`
- `RecommendationEngine.java`
- their tests

This work needs a careful domain review. In particular, verify that goal mappings do not
misrepresent assignment/delivery and that no family is silently hidden from other goals.

### Home / New Idea UI

The committed `index.html` and `desk-backend.js` batch attempts many things at once:

- remove AMD as an implicit New Idea default;
- stage symbol choice instead of navigating immediately;
- require Acquire target price and quantity;
- create a permanent Home workbench;
- put a sector selector in the top summary;
- keep Scout results separate from Market;
- make market rows actionable;
- make Scout result rows open canonical New Idea;
- show nine nearest chain strikes;
- expose Home strategy-catalog data;
- add inline leg editing;
- resize candidates/legs/map;
- adjust reference-line/label collision;
- change one-position Home composition;
- alter `+ New idea` behavior;
- change multiple responsive breakpoints.

This is exactly where convergence failed. Do not assume the batch’s current composition is
good simply because individual controls exist.

## Critically incomplete last patch

The final edit before the owner ended the work changed CSS only:

- removed `.authhomenews` from a Home overflow-hiding rule;
- added desktop news scroll-snap/elegant-scroll CSS;
- added mobile news overflow CSS;
- added compact Home lifecycle CSS;
- hid structure text in compact Book-fan legend rows;
- added compact Book-fan header truncation.

The corresponding JavaScript was **not completed**:

- `authRenderMarketPanels()` still slices populated Home news to 3 rows.
- It still appends the dead `+N more headlines` text.
- It does not add `data-scroll-row` to every headline.
- It does not render the news overflow meta/hairline.
- It does not call `bindElegantList()` for news.
- Book-fan header/readout/odds copy is still long.
- Compact lifecycle copy is still the full backend summary.
- No test was run after the final CSS edit.
- The private browser server was built before that last CSS edit, so the edit has never been
  rendered or visually verified.

Do not mistake the CSS selectors for a finished fix.

## Known code hotspots

Line numbers will drift; search by function/class.

### `src/main/resources/public/index.html`

- `HOME_AUTH_SYMBOL`, `HOME_OPPORTUNITY`, `HOME_SCOUT`, `HOME_IDEA`
- `authHomeSectorSelectHTML`
- `authLifecycleReceiptHTML`
- `authRenderMarketPanels`
- `authPatchHomeContext`
- `authBookFanLegendHTML`
- `bookFanPrompt`
- `bookFanHeaderHint`
- `bookFanReadoutText`
- `authBookFuturesPanelHTML`
- `authBookReady`
- `focusSector`
- `focusSymbol`
- `histPresetSpan`
- `histWindow`
- `drawHistChart`
- `histReceiptText`
- `updateElegantList`
- `bindElegantList`
- `authHomeOpportunityHTML`
- `authPartialOpportunityRowsHTML`
- `authOpportunityHTML`
- `authAnalyzeHomeSymbol`
- `enterDecide`
- `ideaComposer`
- `underlyingChip`
- `threadNewIdea` handler

Important CSS:

- `.homeworkbenchpanel`
- `.scoutactivegrid`, `.scoutidlefield`, `.scoutresultcard`
- `.homecataloglist`
- `.scoutfieldreceipt`
- `.authmarketpulse`, `.pulsecols`
- `.authchainslice`
- `.authmarketwatch`, `.authmarketrow`
- `.authhomenews`
- `.bookfuturespanel`, `.compactbookfutures`
- `.bookfanwrap`, `.bookfanlegend`, `.bookfanrow`, `.bookonefacts`
- `.homeonesummary`, `.authlifecycle`, `.authlifefacts`
- the `@media(min-width:1500px)` Home grid
- 1200–1499, 901–1199, and mobile Home grids

### `src/main/resources/public/js/desk-backend.js`

- `homeBookSymbols` currently places Book/Plan symbols before broad-market benchmarks.
- `hydrateBookContext` hydrates `symbols[0]` as the detailed market subject.
- `focusBookSymbol`
- `focusBookSector`
- `scoutOpportunities`

To fix NVDA default cleanly, preserve the diverse watch-row order but add a separate default
detail subject:

- prefer SPY (then another broad benchmark) for the broad Home lens;
- prefer an available sector ETF/benchmark for a sector lens;
- use the exact symbol only after deliberate user focus;
- retain the existing request-generation/race protection.

Do not simply reorder every market row if that harms Book-first visibility.

## CI/test work in commit `2cbb94f`

The owner supplied two classes of failures.

### Java/EventBus CI failure

Both branch/release runs failed:

```text
WorkspaceServiceTest.eventBusReplaysSinceAndSurvivesBadSubscribers
Expecting value to be true but was false
```

The WIP commit changes `EventBus` and its test to remove timing dependence using a stable
virtual-thread factory/latch-based assertions. Verify the intent and run the failing test
repeatedly before trusting it.

### Browser suite architecture

The owner explicitly wants the DOM suite retained because it catches rendered dishonesty.
The suite currently mixes genuine product regressions and structural flakes:

- deterministic per-Plan scenario state restore failure;
- deterministic local-date rendering failure;
- slow shared-runner timeout flakes;
- state leakage across a 104-test monolith;
- a diagnostic handler typo (`activeId`) that masked timeout evidence.

The WIP commit includes:

- `dom-tests/run-journey-shards.mjs`
- CI/package-script changes for sharded journey lanes;
- a fix for the diagnostic typo;
- many new/changed browser assertions.

Required direction:

- keep fast deterministic contract tests hard-blocking;
- shard journeys with fresh server/browser/database state;
- use event-based waits;
- allow retry-once only for the journey lane;
- preserve evidence artifacts;
- do not hide deterministic failures with retries.

The sharder and full release-matrix integration have not received a complete final verification
at this branch tip.

## Last known verification state

Confirmed during the interrupted batch:

- targeted `AutoRecommenderTest,ApiIntegrationTest` passed after Scout streaming changes;
- earlier targeted recommendation/strategy/Yahoo tests had passed during development;
- an earlier private browser build showed a Scout result row opening canonical New Idea with
  symbol/goal/view/horizon/risk preserved;
- the New Idea result exposed vertical credit spreads and calendars in the tested fixture;
- earlier syntax and `git diff --check` checks passed before the final CSS-only edit.

Not confirmed for the present tree:

- full `mvn -q test`;
- full packaged browser suites;
- sharded journey runner end to end;
- release matrix;
- 1920×1080 Home after the last patch;
- 2560×1440 Home after the last patch;
- 1440, 1280, 1000, 390, 375, and 320 layouts;
- Safari rendering;
- all click targets;
- news reachability;
- NVDA market default;
- short-range chart grammar;
- final no-overflow/no-clipping contract.

Do not treat `2cbb94f` as release-ready, and do not deploy it until those gaps are resolved.

## Recovery sequence for the next agent

### 1. Freeze and inspect

- Read all handoffs/specs.
- Inspect every file in `2cbb94f`.
- Split the batch mentally into Yahoo, Scout transport, strategy extension, CI infrastructure,
  and Home/New-Idea UI.
- Preserve user files and port 7070.
- Run `git diff --check` and syntax checks.

### 2. Stabilize the backend increments first

- Run focused Yahoo/politeness tests.
- Run focused recommendation/strategy tests.
- Run focused Scout/API streaming tests.
- Run focused EventBus/Workspace tests repeatedly.
- Correct any domain or transport issue before touching visual composition.

### 3. Finish Home as one deliberate composition

At 1920×1080 first:

- replace vague/internal workbench copy;
- remove the black implementation-receipt box;
- make the permanent exact-idea and Scout actions obvious;
- keep the top market lens persistent;
- default Market Pulse to SPY/broad context, not NVDA;
- allocate more width to the option chain and less unearned height to the chart;
- compact Book fan and lifecycle content;
- make all headlines reachable;
- make every visible row/action work;
- eliminate clipping, uncontrolled wrapping, and dead voids.

Do not proceed based only on a screenshot. Audit geometry and click behavior.

### 4. Make Scout streaming and canonical handoff undeniable

- start scan;
- see progress immediately;
- see first usable provisional row before completion;
- click it;
- land in canonical New Idea;
- verify all declarations and exact candidates;
- return Home without lost market/sector context;
- verify final rows replace/settle provisional rows without duplicates.

### 5. Verify responsive composition

Required widths:

- 2560×1440
- 1920×1080
- 1440
- 1280
- 1000
- 390
- 375
- 320

At desktop, default composition has no page or panel scrolling. Genuine list overflow alone may
scroll elegantly. At mobile/tablet, use normal page flow, no nested scrollers, no horizontal
overflow, and preserve every capability.

### 6. Audit all screens and clicks

Only after Home/Scout are correct:

- New Idea, including New Idea → New Idea;
- each goal and view;
- candidate rows;
- inline legs;
- risk/reward map;
- scenario paths;
- chain rows;
- Position focus, sparse and multiple positions;
- management actions;
- Book roster/fan;
- market/sector controls;
- news links;
- back/escape/home transitions.

### 7. Generalize streaming

After Scout is proven, identify other slow endpoints/screens and reuse the same general transport
principles. Do not invent a premature platform that delays the visible Home repair.

### 8. Full verification and clean commits

- full Maven suite;
- contract browser tests;
- isolated/sharded journeys;
- packaged jar;
- release matrix;
- no private server/database left running;
- port 7070 untouched;
- split commits by coherent increment;
- no deployment unless explicitly requested.

## Acceptance checklist

The work is not complete unless all are true:

- Home is visually consistent with New Idea.
- Home is useful with zero, one, and many positions.
- No “all markets” ambiguity.
- No implementation-language leakage in primary UI.
- No black borderless pseudo-label theme.
- No dead “+N more” text.
- All news is reachable.
- No NVDA implicit market default when broad market is intended.
- 1D/1W are honest and visually deliberate without fabricated intraday data.
- The option chain receives useful space.
- Book-fan/lifecycle content does not wrap into fragments.
- Sector selection persists and stays usable.
- Scout never hides Market.
- Scout streams before completion.
- Scout rows are actionable.
- Scout rows open canonical New Idea with declaration parity.
- Acquire requires price and quantity.
- All compatible canonical strategy families remain available.
- Defined-risk acquisition/income alternatives are labeled honestly.
- No duplicate engine/API/component is added.
- No desktop clipping or overflow at 1920×1080 and 2560×1440.
- Mobile/tablet are priority-stacked, complete, and horizontally contained.
- CI distinguishes deterministic failures from journey flakes.
- Full tests and browser journeys pass.
- Port 7070 and user data remain untouched.

## Final note to the next agent

Do not repeat my mistake of responding to each screenshot with another local patch and another
paragraph of explanation. The owner has already explained the product. Establish one composition,
make every element earn its space, prove every click, and keep iterating privately until the whole
Home/Scout journey is coherent at the required viewports. Report evidence only after convergence.
