# StrikeBench unified product and implementation program

This is the handoff-ready plan a new developer should be able to execute without reconstructing the conversation.

It is grounded in the current repository at commit `67e88b2` on `feature/journey_refactor`. No source changes were made during this review. `workspace.html` does not participate in the served Desk and is deliberately ignored.

The latest commit records a green backend and Desk lane, but that is only the starting baseline. The current screenshots still demonstrate product failures—especially the crushed Evidence & Paths fan, internal scrolling at full desktop size, clipping, duplicated facts, and poor allocation of space—that the existing tests do not catch.

---

## 1. What StrikeBench is supposed to do

StrikeBench is a decision workspace for options research, recommendation, practice trading, and position management. It must answer four questions:

1. **What deserves attention today?**
2. **What exact package should I consider opening, and at what executable prices?**
3. **What should I do with positions I already hold?**
4. **What does that decision do to the whole Book?**

The product has three focus states, not three unrelated applications:

- **Book state:** nothing specific is focused. Show the account, market field, opportunities, existing positions, and Book-level risk.
- **Idea state:** a proposed package is focused. Show ranked alternatives, exact contracts, economics, evidence, scenarios, and execution.
- **Position state:** a held package is focused. Show current evidence, lifecycle verdict, management alternatives, scenarios, and Book impact.

Changing focus should feel like breathing in and out of the same workspace. It must not introduce global tabs, subnavigation, modal blooms, or a second miniature implementation of New Idea inside Home.

The flow is:

```text
Book / Home
    ├── Scout a market or sector ──> choose result ──> New Idea
    ├── Shape an exact symbol ──────────────────────> New Idea
    └── Choose a held position ─────────────────────> Position
                                                        └── Edit/roll/replace ──> New Idea
```

Home orients and routes. New Idea performs canonical proposal analysis. Position performs canonical held-package analysis.

---

## 2. Vocabulary the implementation must use consistently

| Term | Meaning |
|---|---|
| **Plan** | Durable backend analysis record. The UI normally calls this an Idea. |
| **Candidate** | One exact proposed package generated and priced by the recommendation engine. |
| **Position** | A held package in the Practice or Tracked Book. |
| **Book** | The collection of holdings, cash, obligations, active ideas, and aggregate risk. |
| **Receipt** | Backend-supplied fact with units, basis, timestamp, freshness, source, and availability. |
| **World / lane** | Observed, Simulated, Demo, or Scenario market context. |
| **Path ensemble** | One fingerprinted set of possible underlying paths used to compare packages consistently. |
| **Lifecycle verdict** | KEEP, HARVEST, REDUCE, DEFEND, ACCEPT ASSIGNMENT, or NEEDS EVIDENCE. |
| **Scout** | Cross-symbol discovery using the existing recommendation and evaluation engines. |
| **Workspace context** | The shared market, scope, symbol, goal, view, horizon, risk posture, focus, and route state. |

Do not expose internal phrases such as “canonical engine,” “strategy coverage,” “same owners as Home,” or “one market fan across ideas” as primary product copy. Those are implementation explanations, not user decisions.

---

## 3. Non-negotiable product laws

### 3.1 One authority for every financial fact

Java services calculate financial facts. The browser formats and draws them.

The frontend may:

- format cents as money;
- render percentages and units;
- map supplied points into SVG coordinates;
- interpolate supplied points strictly to draw a line.

The frontend may not originate displayed:

- prices or day changes;
- premiums;
- P/L;
- POP;
- EV;
- Greeks;
- breakevens;
- max profit or max loss;
- scenario outcomes;
- stress losses;
- sessions to expiration;
- expected moves;
- Book totals.

If a required value is absent, the UI shows it as unavailable with a reason. It does not reconstruct it.

### 3.2 Missing evidence never becomes advice

A missing option mark, chain, history range, event record, or Book fact must produce:

- `No verdict · evidence needed`;
- the missing input;
- the last available source and time;
- the conclusions that are unavailable.

It must never fall through to `DEFEND`, `Action required`, or a fabricated value.

### 3.3 Prices must reconcile

Every package needs one explicit price receipt that distinguishes:

- option-only net premium;
- any stock cash flow;
- executable package value;
- opening fees;
- after-fee net;
- market order versus resting limit;
- quantity;
- source, freshness, and timestamp.

A candidate row, payoff header, leg workbench, order dock, and review screen cannot display different unexplained amounts.

For example, if one screen currently shows `+$3,290`, `+$3,281 max`, and `+$3,190 natural`, the UI must explain exactly whether those differences are quantity, fees, executable side, or order type. Otherwise the screen is financially untrustworthy.

### 3.4 Economics, compensation, evidence, and Book fit stay separate

Every candidate and held position is judged through four lanes:

1. **After-cost economics:** endorsement authority.
2. **Evidence and events:** whether the economics are adequately supported.
3. **Premium compensation:** what is being collected for carrying the risk.
4. **Destination Book:** concentration, capacity, assignment, expiry, and correlated tail impact.

Rich premium cannot erase negative after-cost EV. Good standalone economics can still be demoted by Book concentration. Both facts remain visible.

### 3.5 No silent product defaults

The system must not silently choose AMD, NVDA, Income, Neutral, 45 days, or Balanced and then behave as if the user declared them.

It may restore the user’s last explicit context. On a true first run:

- broad market context may be shown as orientation;
- a benchmark may be labeled explicitly;
- Idea creation waits for a symbol and required declarations;
- missing declarations name what remains to be chosen.

### 3.6 All current strategy capabilities remain available

The existing catalog already supports:

- long calls and puts;
- debit and credit verticals;
- cash-secured puts;
- covered calls;
- protective puts and collars;
- calendars and diagonals;
- iron condors and butterflies;
- long straddles and strangles;
- covered composite structures;
- custom packages;
- blocked-by-default undefined-risk structures.

The authoritative catalog is [StrategyFamily.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/strategy/StrategyFamily.java) and [StrategyCatalog.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/strategy/StrategyCatalog.java).

The UI must not imply that the engine only supports a cash-secured put merely because one candidate survived the current screen. It must distinguish:

- supported families;
- families attempted;
- packages successfully priced;
- packages rejected;
- packages retained for comparison;
- packages endorsed.

### 3.7 Analysis never mutates an account

Clicks, scans, simulations, previews, and lifecycle analysis are read-only. Account mutation still requires the existing explicit review and confirmation flow.

### 3.8 One component per concept

There is one implementation each for:

- price receipt;
- leg row;
- scenario spectrum;
- path fan;
- risk map;
- market context;
- lifecycle receipt;
- icon button;
- stepper;
- status badge;
- overflow list;
- empty/loading/error state.

Book, Idea, and Position configure those components. They do not maintain parallel renderers.

---

## 4. Current code: what already exists and must be extended

A developer must not build replacement engines for capabilities that already exist.

| Capability | Existing owner |
|---|---|
| Strategy families and package identity | [StrategyCatalog.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/strategy/StrategyCatalog.java) |
| Package construction | `strategy/StrategyBuilder` |
| Recommendation and sizing | `recommend/RecommendationEngine` |
| Cross-symbol opportunities | [OpportunityScanner.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/recommend/OpportunityScanner.java) |
| Automatic signal scouting | [AutoRecommender.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/recommend/AutoRecommender.java) |
| Shared fan-out mechanism | `util/BoundedFanout` |
| Compensation alongside decision score | `recommend/CompensationView` |
| Economic assessment | `eval/EconomicAssessment` |
| Payoff contract | [OutcomeContract.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/outcomes/OutcomeContract.java) |
| Shared simulated paths | [PathEnsembleService.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/sim/PathEnsembleService.java) |
| Scenario valuation | [ScenarioCanvasValuator.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/sim/ScenarioCanvasValuator.java) |
| Held-position economics | [HeldPositionEconomicsService.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/position/HeldPositionEconomicsService.java) |
| Lifecycle decision policy | [PositionLifecycleDecisionService.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/position/PositionLifecycleDecisionService.java) |
| Lifecycle receipt | [PositionLifecycleReceipt.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/position/PositionLifecycleReceipt.java) |
| Book-action before/after projections | [BookActionProjectionService.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/paper/BookActionProjectionService.java) |
| Book risk | [BookRiskService.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/paper/BookRiskService.java) |
| Mechanical protocol evaluation | [ProtocolEvaluator.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/paper/ProtocolEvaluator.java) |
| Market acquisition and freshness | [MarketDataService.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/market/MarketDataService.java) |
| Market engine orchestration | [MarketDataEngine.java](/Users/tinker/output/optin/src/main/java/io/liftandshift/strikebench/market/MarketDataEngine.java) |
| Frontend HTTP sequencing | [desk-backend.js](/Users/tinker/output/optin/src/main/resources/public/js/desk-backend.js) |
| Current served Desk | [index.html](/Users/tinker/output/optin/src/main/resources/public/index.html) |

Several important consolidations have already landed and should not be reopened:

- one backend normal CDF;
- shared lognormal terminal math;
- canonical quantiles;
- canonical fee calculation;
- one option-bar writer;
- shared scan fan-out;
- market-data authority seams;
- persistence seams;
- backend expected-move receipt;
- `NEEDS_EVIDENCE`;
- initial HEDGE/EXIT holdings protection;
- typed Yahoo budget exhaustion;
- range-aware historical absence;
- initial removal of the client Black–Scholes/Monte Carlo cluster.

The remaining program finishes the convergence and fixes its product surfaces.

---

## 5. Current structural problems

### 5.1 The served frontend is still a promoted prototype

Current [index.html](/Users/tinker/output/optin/src/main/resources/public/index.html) is 8,386 lines:

- inline CSS begins at line 9 and runs to approximately line 3,201;
- inline JavaScript begins around line 3,259 and runs almost to EOF;
- [app.css](/Users/tinker/output/optin/src/main/resources/public/app.css) is only 366 lines and has not been the real owner of the Desk styling.

This happened because the prototype Desk was promoted directly into `index.html`. Its temporary inline styling kept accumulating viewport- and surface-specific overrides.

That produces:

- different colors and borders on Home, New Idea, and Position;
- black label-like controls that bypass the shared theme;
- the Position-only blue outline;
- repeated stepper and play-button sizes;
- media queries that overlap and fight;
- clipping fixes that break another viewport;
- components whose geometry changes depending on which surface contains them.

The target is exactly one served `app.css`, but CSS extraction is an enabling step—not permission to postpone product work behind a styling rewrite.

### 5.2 Multiple frontend state owners still compete

Current state is split among:

- `DeskBackend.state`;
- the global `state` for Book/Position focus;
- `HOME_SCOUT`;
- `homeIdea`;
- `MKT_CHART`;
- Decide-local state;
- `BOOK_AUTH`, `POS`, and `byId` mirrors.

This explains:

- a sector lens and Scout using different scopes;
- Home market focus remaining on NVDA after another context is selected;
- declarations being lost or silently reset;
- stale artifacts surviving world transitions;
- Back returning somewhere other than the previous focus;
- Home and New Idea disagreeing about the same request.

### 5.3 Frontend financial derivation remains

Although most of the old JavaScript engine was removed, live derivations remain:

- `scenData()` constructs held-position scenario P/L from `payFor()`;
- `payFor()` interpolation is used to print “If price holds” and stress values, not merely draw lines;
- `histSessionsUntil()` approximates trading sessions using `days × 252 / 365`;
- `authHomeQuote()` derives day change;
- the Book risk map derives fixed −11% stress P/L in the browser;
- payoff sampling and breakeven scans remain entangled with fixture fallbacks;
- dead import/builder paths still call removed `finishCand()` logic.

The served application also retains comments and branches describing `file://` fixtures even though offline operation is not a product requirement.

### 5.4 Parallel visual renderers remain

Current separate functions include:

- `drawAuthoritativePositionPaths`;
- `drawAuthoritativeBookFan`;
- `drawMCFan`;
- `drawAuthoritativeBookRisk`;
- `drawDecMap`;
- `drawExposureMap`.

They share concepts but not one component contract. That is why fixes to labels, axes, hover, path selection, or geometry do not reach every surface.

---

# 6. Target product experience

## 6.1 Home / Book state

Home is not a dashboard of six equal rectangles. It is the workspace’s orientation state.

It must show:

1. account and cash truth;
2. permanent discovery controls;
3. market and option-chain context;
4. streamed opportunity results;
5. Book possible futures and risk;
6. positions and resumable ideas;
7. market lenses and research.

### Home header

The top strip should contain:

- Practice or Tracked account identity;
- equity;
- settlement balance;
- assignment/obligation encumbrance;
- pending cash;
- genuinely free buying power;
- package count;
- reconciliation status;
- current market lens.

It must use the same panel, badge, border, and typography grammar as New Idea.

“Cash truth” needs separate authority:

- `BROKER_REPORTED`;
- `MODEL_DERIVED`;
- `UNAVAILABLE`.

For cash-secured puts, do not describe pledged settlement cash as losing its money-market return. Keep these distinct:

- settlement-fund income;
- remaining option premium;
- buying-power encumbrance;
- redeployment opportunity.

### Permanent discovery workbench

The workbench remains visible; it does not bloom or replace the entire page.

It contains one shared declaration set:

- scope: broad market, active universe, sector, or exact symbol;
- goal: Income, Directional, Acquire, Hedge, Exit;
- view: Bearish, Neutral, Bullish, Volatile when relevant;
- horizon;
- risk budget;
- target price and share quantity when required.

Two actions use those declarations:

- **Scout this field:** cross-symbol discovery over the selected market or sector.
- **Analyze this symbol:** open canonical New Idea for the staged underlying.

Typing or choosing a symbol stages it. It does not immediately navigate away before the user can set goal, horizon, target, quantity, or risk.

For Acquire, the interface asks:

- target acquisition price;
- shares wanted;
- acceptable assignment posture.

For Hedge or Exit, it binds to actual eligible held shares. It never silently purchases 100 shares to manufacture a collar or covered call.

### Persistent market scope

Market or sector scope belongs in the Home-level context, not hidden inside a Scout result panel.

The selector must:

- remain open until a selection is made or dismissed;
- show all represented sectors;
- preserve the selection across Home composition;
- drive market watch, Scout, research, and the ambient chart consistently.

Replace vague `All markets` with something explicit, such as:

> Broad market · 105 optionable symbols · 14 groups

or:

> Healthcare · 10 optionable symbols

### Market context

The chart and option chain should share the same focused symbol.

If nothing is focused:

- show an explicitly labeled benchmark or selected sector proxy;
- do not silently use NVDA or AMD.

The chain must receive enough width to show:

- expiration;
- strike;
- call bid/ask;
- put bid/ask;
- source;
- freshness;
- loading/unavailable state.

A tiny five-row chain squeezed beside an oversized chart is not acceptable.

### Activity rail

Positions and Working Ideas become one adaptive rail with two stacked sections:

- **Needs attention / Positions**
- **Resume / Working ideas**

This is not a tab. Each section receives only the height its useful rows require. Genuine overflow belongs to one intentional list scroller.

A single position must not reserve an empty full-height column. Four working ideas must not receive the same space as the Book fan or discovery workbench.

### Book possible futures

With no selected position:

- show the measured synchronized Book total only when the joint Book ensemble supports it;
- show colored per-position projections as independent projections;
- never sum independent single-symbol simulations and call that Book risk;
- medians may be shown at rest;
- individual bands are earned by hover/focus;
- each path ends at its actual expiry;
- hover links to the corresponding position row;
- click opens Position.

With one selected position, the same component rebinds to the exact position.

The Book chart must not consume half the screen merely because it can. It should earn height from the number of positions, labels, and actual analytic content.

### Empty Book

An empty Book should become more useful, not barren.

Use the available space for:

- broad-market opportunity discovery;
- market regimes;
- sector comparisons;
- evidence availability;
- the most promising redeployment frontier;
- educational context for the chosen goal.

Do not display large empty panels apologizing that there are no positions.

---

## 6.2 Scout

Scout uses the existing `OpportunityScanner`, recommendation engine, strategy catalog, economic assessment, and Book gates. Do not create a second risk or recommendation engine.

### Progressive behavior

Current transport can receive progressive frames, but the screen must render rows as each result is retained.

Stable states:

- idle;
- starting;
- partial results;
- complete;
- cancelled;
- failed.

The panel’s geometry stays stable across those states.

Progress reports four distinct quantities:

1. universe considered;
2. symbols with sufficient inputs;
3. packages evaluated;
4. rows retained.

A display such as `5 scanned` followed by `105 scanned` without explaining what each count means is not acceptable.

### Result identity

Deduplicate by result identity, not symbol alone:

```text
symbol + strategy family + exact package + expiration + declarations
```

IWM bull put spread and IWM covered call are different results. A provisional row must be replaced only by its corresponding final row.

### Result row

Each result shows:

- symbol;
- human strategy name;
- goal served;
- exact expiration;
- net credit or debit;
- capital required;
- max loss;
- after-cost EV;
- evidence state;
- compensation;
- Book impact;
- explicit `Analyze →`.

Example:

```text
IWM · Bull put credit spread · 18 Sep
Net credit $315 · max loss $785 · after-cost EV +$134
Observed chain · no earnings crossing · Book concentration improves
Analyze →
```

A Scout row is not a miniature New Idea. Clicking it opens New Idea with:

- exact symbol;
- selected strategy/package identity;
- goal;
- view;
- horizon;
- risk posture;
- target and quantity;
- market/world;
- source scope;
- Scout receipt/fingerprint.

New Idea must not rerun a generic symbol request and lose the result that was clicked.

### Strategy visibility

The screen should say something user-facing such as:

> 11 income structures were eligible for this request; 7 exact packages were priced; 3 survived evidence and Book checks.

Do not present “Strategy coverage · canonical catalog” as a headline. The catalog is implementation machinery.

---

## 6.3 New Idea

New Idea remains the canonical deep proposal surface. Home and Scout route into it; they do not recreate it.

### Candidate rail

The list must show all retained comparisons and make the selected item’s role unambiguous:

- `Recommended`;
- `Favorable economics, mixed fit`;
- `Comparison only`;
- `Evidence incomplete`;
- `Rejected` in the full field.

If one favorable candidate exists, the screen must not silently select an unfavorable 8%-POP comparison and make it look like the recommendation. Preserve the exact Scout selection when arriving from Scout; otherwise select the engine’s endorsed candidate. If no package is endorsed, say so before choosing a teaching comparison.

Column labels are semantic:

- Net credit;
- Net debit;
- Capital required;
- Max loss;
- Chance of profit;
- After-cost EV.

Do not call a negative stock-inclusive amount `Collect`.

### Candidate-list geometry

The existing breakpoint-based or fixed appetite logic is insufficient because banners and support panels change the available height.

The list must measure its own content box after layout and on resize. Candidate rows use whole-row scrolling with:

- hidden scrollbar until intent;
- fade or position hairline;
- honest `+N more`;
- no paging dots;
- no rows underneath the leg editor.

### Leg workbench

For same-expiry packages, one desktop row should approximate:

```text
SELL CALL   540   ×2      bid 62.20 | ask 63.45
```

Required hierarchy:

- strike is primary;
- quantity is compact;
- executable side is highlighted;
- full bid and ask are visible;
- side/type is compact;
- expiry appears once in the panel header;
- a leg-specific expiry appears only for calendars/diagonals;
- source and freshness appear once per package unless a leg differs;
- IV and delta appear as secondary information when width permits;
- no `fill: sell at bid` sentence.

The workbench is inline-editable:

- side;
- type;
- strike;
- quantity;
- expiration;
- add/remove leg.

Every edit requests a canonical backend preview. The screen retains the last valid analysis while the new preview is pending or blocked.

On mobile:

- line 1: side/type, strike, quantity, edit/remove;
- line 2: full bid/ask, executable side, expiry if distinct;
- optional mechanics disclose below.

The current right-side “Selected package book” duplicates the same legs. Remove that duplication. Use the space for aggregate execution quality, selected-leg chain context, or the active Evidence & Paths lens.

### Risk/reward map

The map’s axes are:

- chance of profit;
- realistic after-cost EV.

Its domain derives from the actual candidates plus padding. A legitimate 15% POP candidate cannot be plotted outside a hardcoded 40–90% domain.

Required behavior:

- all points visible;
- size legend explains capital;
- color/outline legend explains defined risk/evidence state;
- zero-EV reference line is quiet;
- reference line is clipped around label boxes;
- ideal-region label is collision placed;
- selecting a point selects the corresponding candidate;
- unused space is reduced through an appropriate domain, not filled with decoration.

### Payoff

The payoff hero shows one reconciled package receipt:

- expiration;
- exact quantity;
- current spot;
- break-even(s);
- max profit;
- max loss;
- entry/executable package value;
- fee relationship.

Every value must match the candidate, legs, and execution dock.

### How It Reacts

Resting scenario tiles contain:

- story name;
- price move;
- small path glyph;
- match/probability;
- P/L bar;
- exact P/L.

Remove repeated generic prose such as:

- “stays inside the range as time decays”;
- “steady upside drift with softer volatility”;
- “whipsaws near spot while time passes.”

Those are static narratives, not package-specific evidence.

Selected-story explanation may appear once after interaction.

The scenario component should be content-sized, approximately 75–90px per tile at desktop—not stretched to 143px by empty space.

### Animation controls

Use one shared control ribbon:

- SVG play/pause;
- scrub timeline;
- 1×/2×/4×;
- graphical move;
- volatility;
- time;
- compact match badge.

Remove the narrative sentence before the controls. It repeats information already visible elsewhere.

Unicode `▶`, `❚❚`, `+`, and `−` are replaced with shared SVG icons. Desktop visuals can remain compact, while the touch target is at least 40–44px on mobile.

### Evidence & Paths

This is a P0 product defect in the current screenshot.

The current right column allows:

- the decision brief;
- inspect tabs;
- active Evidence pane;
- standing Market & Research band;

to compete for the same height. The active fan is consequently reduced to a nearly invisible strip and placed inside a scrolling `.inspectpane`.

Target composition:

- decision brief is compact and bounded;
- Paths/Fit/Greeks/Book rail remains visible;
- the active lens owns all remaining right-column height;
- the fan itself never scrolls;
- its plot receives a useful minimum height—roughly 260–320px at standard desktop sizes;
- stats and readout remain visible;
- only a genuine row list inside a lens may scroll.

Remove the duplicate standing `decMarketBand` as a second owner of market data:

- chart and quote live in the center Market panel;
- exact bid/ask lives with the leg workbench;
- active Evidence/Fit/Greeks/Book lives in the right column;
- research headlines use the shared research owner.

This preserves market visibility without crushing the decision lens.

A test that merely clicks the fan and passes is insufficient. Geometry tests must assert:

- plot height;
- plot visibility;
- no fan ancestor scrolling;
- stats visible;
- hover and click targets inside bounds.

### Evidence lanes

When realized-volatility EV and market-implied EV disagree, show both explicitly:

| Lens | Result | Meaning |
|---|---:|---|
| Market-implied | −$502 | Cost benchmark under market-implied pricing |
| Realized-volatility | −$527 | Outcome under observed realized-volatility assumptions |

Then show one clear endorsement result. Avoid a red verdict in one panel and a contradictory checkbox elsewhere.

---

## 6.4 Position state

Position uses the same payoff, legs, scenarios, fan, market, and receipt components as New Idea.

### Lifecycle decision

Every held package receives the four-lane lifecycle receipt and one policy result:

- KEEP;
- HARVEST;
- REDUCE;
- DEFEND;
- ACCEPT ASSIGNMENT;
- NEEDS EVIDENCE.

`DEFEND` means a named trigger actually fired. It must say which:

- stop-loss rule;
- expiry/time rule;
- capacity hard limit;
- event crossing;
- challenged covered call;
- tail-risk limit;
- assignment decision.

If current marks are missing, use `NEEDS EVIDENCE`, not `DEFEND`.

The primary question remains:

> Would you open the exact position you still own today, ignoring sunk campaign cash?

### Management choices

Position should support:

- keep;
- close;
- partial close;
- roll;
- adjust;
- accept assignment;
- restore upside on a challenged call;
- release collateral;
- compare a replacement opportunity.

Each choice uses current executable close-side evidence. Opening evaluation cannot be reused because open and close bid/ask asymmetry differs.

Partial-close proposals show:

- contracts closed;
- close cost;
- premium relinquished;
- collateral released;
- concentration before/after;
- assignment shares/dollars before/after;
- scenario loss before/after.

### Basis and wheel awareness

Covered calls compare strike against:

- tax-lot basis;
- campaign effective basis after collected premium.

Warnings identify which basis is being used. A call below basis is a warning, not an automatic verdict.

Assignment willingness is quantity-based:

> Would you intentionally own 900 shares for $410,000?

A ticker-level boolean is insufficient.

### Position layout

Desktop priority:

1. lifecycle verdict and evidence;
2. current economics;
3. management choices and legs;
4. market and chain;
5. scenarios and paths;
6. research.

Mobile uses one page scroller in that order. It does not place content inside several tall nested scrollers.

`Forward Test` must show:

- start;
- progress;
- result;
- data/model tier;
- cancellation or failure.

A button that appears to do nothing is unacceptable.

---

## 6.5 Book-level management and recommendation gates

Book risk is not decoration after ranking. It participates in both open and close analysis.

Required Book facts:

- cash reconciliation;
- assignment commitment in shares and dollars;
- free buying power;
- single-name and sector concentration;
- ETF look-through;
- expiry clustering;
- earnings/event crossings;
- factor concentration;
- correlated shock loss;
- partial-close improvements.

A high-carry AMD package can remain economically attractive while being demoted because it worsens semiconductor concentration. The screen shows both facts.

### Event calendar

Events are first-class evidence:

- earnings;
- ex-dividend when sourced;
- macro events where applicable;
- option expiry clusters.

Event acquisition must not consume the Yahoo history allowance. It uses its own polite, cached data domain with explicit provenance.

### Joint Book ensemble

Independent position projections may be overlaid but never summed into:

- Book P/L;
- Book POP;
- Book quantile bands.

A true Book total requires a synchronized, correlated multi-symbol ensemble. The existing `PathEnsembleService` is extended; a second simulation engine is not created.

The Book result states:

- correlation source;
- data window;
- symbols included;
- missing symbols;
- fingerprint;
- horizon;
- position expiry handling.

---

## 6.6 Market chart, chain, and research

### Short history ranges

The stored history is daily.

Until an observed intraday source exists, label short ranges honestly:

- `1 session`;
- `5 sessions`;
- `1 month`;
- etc.

Do not show `1D` as though it contains intraday candles, and do not render one daily candle as a broken chart. One-session view can show the daily OHLC receipt as a compact range/volume presentation.

### The “parabolic” forward cone

The current band is a backend expected-move receipt, but displaying a similar symmetric cone on every chart makes it look like a forecast and provides little decision value.

Target behavior:

- no ambient cone when no exact package/expiry is focused;
- anchor it to the latest observed price;
- terminate it at the selected expiry;
- label P16, P50, and P84 endpoints;
- show volatility, horizon, source, and freshness;
- use the selected package’s shared ensemble quantiles when available;
- hide it when evidence is stale or missing;
- never call it a prediction;
- never calculate it in JavaScript.

The visual becomes useful because it answers:

> What distribution is being assumed for this exact decision and expiry?

not:

> What generic parabola can be drawn after every chart?

### Independent receipts

Quote, history, chain, and news load independently. Missing quote must not hide stored history. Missing chain must not hide news.

Each slot settles into:

- ready;
- stale stored data;
- unavailable with reason;
- retryable provider failure;
- explicit allowance exhausted.

### News

`+9 more headlines` is an action:

- expand the one news list;
- open a focused news view;
- or scroll the one owning list.

It is never decorative text.

### Market focus

Market Pulse follows the shared workspace focus. It may be pinned explicitly; otherwise it cannot remain stuck on NVDA while the user selects AMD, Healthcare, or a different idea.

---

# 7. Backend and data work still required

## 7.1 Canonical Greeks wire contract

Current backend shapes still differ:

- `TradeService.PositionGreeks`;
- `ScenarioCanvasValuator.Greeks`;
- `BookRiskService.GreekBlock`;
- `BookRiskService.PracticeLane`.

Create one `GreeksView` with explicit units, for example:

- `deltaShares`;
- `gammaSharesPerDollar`;
- `thetaCentsPerDay`;
- `vegaCentsPerVolPoint`;
- `dollarDeltaCents`;
- `gammaDollarDeltaCentsPerOnePct`;
- `complete`;
- unavailable dimensions.

All APIs project this contract. Frontend normalizers are then deleted rather than expanded.

## 7.2 Canonical package-price receipt

Introduce one shared response object used by candidates, previews, orders, reviews, and held-position closes:

```text
quantity
optionNetPremiumCents
stockCashFlowCents
grossPackageNetCents
openingFeesCents
afterFeeNetCents
executableNetCents
restingLimitNetCents
valuationBasis
executability
source
freshness
observedAt
fingerprint
```

The frontend never chooses among competing fields on its own.

## 7.3 Canonical time receipt

Expose:

- valuation date;
- expiry date;
- calendar days;
- trading sessions;
- ensemble horizon;
- path progress;
- expiry cut index.

Delete `histSessionsUntil()` and browser weekday approximations.

## 7.4 Position scenario and stress receipts

Backend supplies:

- each named scenario checkpoint;
- underlying move;
- volatility shift;
- elapsed sessions;
- position P/L;
- probability/match if available;
- fixed Book stress facts.

Delete browser `payFor()` uses that print “If price holds,” scenario values, or Book stress.

## 7.5 Mechanical management policy

`ManagementPlanner`, `ProtocolEvaluator`, and `TradeService.dtePlan()` currently express overlapping 50%, 2×, and 21-DTE rules.

`ProtocolEvaluator` becomes the policy owner. Other surfaces render its rules or consume typed triggers. Policy selection is configurable and named; it is not presented as universal financial truth.

## 7.6 Symbol and horizon identity

Create canonical value types for:

- normalized/validated symbol;
- declared horizon.

Use them in cache keys, routes, providers, Plans, Scout, and frontend receipts. Remove repeated string normalization and competing horizon grammar.

## 7.7 Holdings gate before provider work

The ladder HEDGE/EXIT holdings check currently occurs after `preflightSymbol()`. Move the semantic rejection before quote/chain/history acquisition.

Acceptance: an impossible no-shares HEDGE/EXIT request performs zero provider calls.

## 7.8 Yahoo historical absence

The current provider-scope fix has two defects:

- the cache key contains a literal NUL character;
- automatic acquisition can record the provider as `auto` because the reporting provider is lost on an empty result.

Replace the string key with a typed key:

```java
record HistoricalAbsenceKey(String provider, Symbol symbol) {}
```

The acquisition result must carry the provider that produced the empty-range receipt even when no candles were returned.

Distinguish:

- exact first available observation;
- inferred lower bound;
- unavailable requested range;
- budget exhaustion;
- provider error.

SNDK’s pre-listing absence must not poison valid recent SNDK history. Budget exhaustion must not make an HTTP request or trip the upstream circuit breaker.

## 7.9 Lifecycle completion

Extend the existing lifecycle services rather than replacing them:

- opening/campaign history;
- current executable close;
- settlement-fund income authority;
- option carry;
- encumbrance;
- tax-lot basis;
- campaign basis;
- event crossings;
- Book-action projections;
- redeployment frontier.

The redeployment frontier may honestly return no qualifying alternative. That means optionality was restored, not that an invented yield exists.

---

# 8. Frontend architecture

## 8.1 One `WorkspaceContext`

Create a single context object owned by the backend bridge/store:

```text
world
marketLane
accountId
scopeType
scopeId
focusedSymbol
focusedIdeaId
focusedPositionId
goal
view
horizon
riskPosture
targetPrice
shareQuantity
assignmentPreference
routeState
returnFocus
```

Home, Scout, New Idea, Position, market chart, and Back use it.

Transition rules:

- world change clears all market-owned receipts before the new world publishes;
- symbol change clears only symbol-owned receipts;
- scope change clears Scout results but preserves compatible declarations;
- Scout result preserves exact package identity;
- Back restores `returnFocus`;
- reload restores only explicitly saved declarations;
- stale async responses cannot publish into a newer context generation.

## 8.2 Frontend becomes display-only

Remove:

- remaining fixture/offline branches;
- `finishCand()` callers and dead import/build paths;
- displayed scenario interpolation;
- client sessions-to-expiry;
- client day-change calculation;
- client stress P/L;
- price-authority fallbacks;
- old Home/Book mirrors once views consume the store directly.

Add exact-string cross-surface tests before deleting each fallback.

## 8.3 Shared visual components

Implement using the existing vanilla JavaScript stack; do not introduce a framework rewrite.

Shared component contracts:

- `PriceReceipt`
- `LegRow`
- `ScenarioSpectrum`
- `PathFan`
- `RiskMap`
- `LifecycleReceipt`
- `MarketContext`
- `OverflowList`
- `IconButton`
- `Stepper`
- `StatusBadge`
- `EvidenceState`

A component owns its internal geometry. A surface owns only placement.

## 8.4 One `app.css`

First make a behavior-neutral extraction:

1. move the current inline `<style>` contents into [app.css](/Users/tinker/output/optin/src/main/resources/public/app.css);
2. verify no visual change;
3. remove the `<style>` block;
4. then consolidate rules as components migrate.

The final file is ordered:

```css
/* 1. tokens */
/* 2. reset and document */
/* 3. primitives */
/* 4. shared components */
/* 5. Home composition */
/* 6. Idea composition */
/* 7. Position composition */
/* 8. responsive/container rules */
/* 9. accessibility and motion */
```

Rules:

- no hardcoded black control surfaces;
- no component implemented separately under `.lv-book`, `.decwrap`, and `.focus`;
- no overlapping media-query grammars;
- container queries control component density;
- JavaScript may set documented data-driven CSS variables or SVG coordinates, but not visual theme declarations;
- no static inline `style=` attributes for app components;
- dynamic position color uses a CSS custom property.

## 8.5 Semantic money formatting

Use integer cents and distinct renderers:

- signed P/L;
- signed cash flow;
- loss magnitude;
- option price;
- fees;
- unavailable.

One generic `money()` is not enough because `+$0`, `$0`, `−$0`, credit, debit, and loss magnitude have different meanings.

Exact output strings are tested.

---

# 9. Implementation sequence

Each milestone lands as a small verified increment. Run `mvn -q test` after every backend module and the relevant browser lane after every frontend increment.

## M0 — Golden fixtures and executable product laws

Create synthetic, non-personal fixtures covering:

- 0, 1, 4, and 12 positions;
- 0, 5, and 20 working ideas;
- 1, 2, 4, and 6-leg packages;
- same- and multi-expiration packages;
- complete, stale, and missing quotes;
- history without quote;
- chain loading, ready, stale, unavailable, and budget-exhausted;
- 0, 5, and 20 news items;
- Scout idle, partial, complete, cancelled, and error;
- Observed↔Simulated transitions;
- concentrated and diversified Books;
- expiry wall and event crossings;
- below-basis covered call;
- stacked overwrite;
- partial-close alternatives;
- Book cash reconciliation to the cent.

Record exact backend facts expected on Home, New Idea, and Position.

Acceptance:

- no personal brokerage data in the repository;
- fixture values reconcile across every API;
- each later milestone adds regression assertions against these fixtures.

## M1 — Close current correctness defects

Implement:

- holdings gate before provider acquisition;
- typed provider/symbol historical-absence key;
- preserve actual provider on empty history result;
- world-transition cache invalidation;
- missing-evidence lifecycle semantics on every surface;
- stable plan-id 409 recovery;
- package-price reconciliation receipt;
- dynamic risk-map domain;
- explicit dual-EV receipt.

Acceptance:

- HEDGE/EXIT with no shares performs zero provider calls;
- SNDK pre-history request does not quarantine valid current data;
- allowance exhaustion logs `160/160; no request sent`;
- no circuit breaker trips on local budget exhaustion;
- no stale Simulated artifact remains after switching to Observed;
- every package price shown on screen reconciles.

## M2 — Complete canonical backend facts

Implement:

- `GreeksView`;
- canonical time receipt;
- backend scenario checkpoints;
- backend payoff-at-price facts;
- backend fixed stress facts;
- `ProtocolEvaluator` as management-policy owner;
- canonical Symbol and Horizon identities;
- lifecycle evidence integration;
- event calendar;
- cash/collateral decomposition.

Acceptance:

- every displayed financial fact has one typed backend field;
- no API exposes ambiguous Greek units;
- no duplicate management thresholds remain;
- event acquisition is independent of Yahoo history budget.

## M3 — Finish display-only frontend conversion

Before deleting fallbacks, add cross-surface exact-value tests for:

- P/L;
- POP;
- max loss;
- max profit;
- Greeks;
- package net;
- fees;
- price/change;
- scenario P/L;
- sessions to expiry.

Then:

- remove fixture engine remnants;
- remove `finishCand()` paths;
- remove printed `payFor()` derivations;
- remove client session calculations;
- remove client quote-change and stress calculations;
- delete dead fixture code and associated CSS.

Acceptance:

- searching the served frontend finds no financial calculator;
- Home, New Idea, and Position display the exact same receipt consistently;
- missing fields remain unavailable.

## M4 — Workspace context and CSS foundation

Implement:

- one `WorkspaceContext`;
- generation-safe async publication;
- exact transition/Back semantics;
- mechanical CSS extraction into the single `app.css`;
- shared token and primitive layer;
- shared SVG icon system.

This milestone does not redesign every panel. It removes the structural conditions that keep causing inconsistent fixes.

Acceptance:

- no `<style>` block in served `index.html`;
- Home/New Idea/Position use one surface grammar;
- no hardcoded AMD/NVDA product default;
- context survives transitions and reload as specified;
- existing visual baseline is preserved before composition changes.

## M5 — Home and Scout refactor

Implement:

- permanent discovery workbench;
- persistent market/sector scope;
- exact-symbol staging without immediate navigation;
- full goal/control parity with New Idea;
- progressive Scout rows;
- four progress counts;
- actionable result handoff;
- adaptive activity rail;
- rebalanced chart/chain;
- responsive Book futures;
- actionable market rows and news overflow;
- productive empty Book.

Acceptance at 1920×1080 and 2560×1440:

- default Home has no page or panel scroll;
- Scout results own one intentional list scroller only when needed;
- chain values are readable;
- no empty Texas-sized panel;
- sector choice remains visible and drives every Home market surface;
- results render before scan completion;
- every result opens exact New Idea;
- 0/1/4/12 positions produce balanced compositions.

## M6 — New Idea convergence

Implement:

- candidate role semantics;
- measured candidate-list space;
- redesigned `LegRow`;
- one price receipt;
- removal of duplicate package-book legs;
- compact `ScenarioSpectrum`;
- SVG animation controls;
- dynamic risk map;
- active Evidence & Paths geometry;
- removal of duplicate standing market band;
- clearer EV lanes;
- copy-debt removal.

Acceptance:

- full strike and bid/ask visible at 1920 and 2560;
- calendars show per-leg expiration correctly;
- Evidence fan is fully visible without scrolling;
- active plot is at least the agreed useful height;
- scenario tiles do not change height dramatically when controls appear;
- all controls meet touch targets on mobile;
- every candidate, map point, leg control, fan path, inspect lens, and review action works.

## M7 — Position and Book convergence

Implement:

- lifecycle receipt with named triggers;
- management choices and quantity-level projections;
- partial-close support;
- basis/campaign warnings;
- shared legs/scenarios/fan/market components;
- proper Forward Test feedback;
- synchronized correlated Book ensemble;
- ETF look-through and event/expiry concentration gates;
- compact Position and activity geometry.

Acceptance:

- missing evidence produces NEEDS EVIDENCE;
- DEFEND always names a trigger;
- partial actions show before/after Book facts;
- one position does not leave an empty column;
- independent projections are never summed;
- true Book total carries a joint-ensemble receipt;
- mobile uses one page scroller.

## M8 — Market, research, and general progress architecture

Scout establishes the streaming pattern first. Then extract a small reusable progress envelope:

```text
started
progress
item
complete
cancelled
error
```

Apply it only to operations that materially take time:

- Book projections;
- outcome/path generation;
- backtests;
- large imports;
- history enrichment;
- long portfolio reports.

Streaming wraps existing services. It does not create streaming variants of engines or APIs with different calculations.

Also implement:

- independent quote/history/chain/news slots;
- honest session-range labels;
- package-expiry-specific expected-move band;
- actionable news overflow;
- clear retry/cancellation;
- visible provider/budget states.

Acceptance:

- no operation longer than the UX threshold appears frozen;
- partial updates never replace complete receipts with stale ones;
- cancellation is real;
- retries are idempotent;
- external allowance is not spent silently.

## M9 — Copy, accessibility, and responsive completion

Perform a complete communication audit.

Keep explicit text for:

- exact values and units;
- provenance;
- missing evidence;
- named triggers;
- blocking reasons;
- legal and educational disclosures.

Replace repeated prose with graphics for:

- scenario movement;
- Book/position line identity;
- risk-map size;
- animation state;
- timeline;
- market trend;
- move/volatility/time controls.

Delete:

- implementation-owner commentary;
- repeated expiry/source receipts;
- generic scenario sentences;
- duplicated instructions;
- sentences that merely restate headings.

Responsive contract:

- 2560, 2048, 1920: no default scroll; useful co-visibility;
- 1440, 1280, 1000: deliberate recomposition;
- 390, 375, 320: one page scroller, no horizontal overflow, no nested vertical scrollers, 40–44px targets.

## M10 — Full interaction, geometry, and release verification

Run every meaningful click in every state:

- world switch;
- market scope;
- sector selection;
- chart ranges and overlays;
- chain expiration and rows;
- Scout start/cancel/retry/results;
- exact-symbol staging;
- candidate selection;
- inline leg changes;
- add/remove leg;
- risk-map point;
- scenario select/play/pause/scrub/speed;
- fan hover/click/path playback;
- inspect lenses;
- news overflow;
- working-idea resume;
- Position focus and management choices;
- Forward Test;
- Back and reload;
- review/confirmation boundaries.

Geometry assertions include:

- all actionable children within their panel bounds;
- all charts above useful minimum dimensions;
- only designated lists may overflow;
- no clipping;
- no invisible `+N more`;
- no overlapping labels;
- no chart or fan inside an accidental scroller;
- no horizontal body overflow.

Then delete superseded:

- renderers;
- state mirrors;
- formatting helpers;
- CSS selectors;
- compatibility branches;
- dead fixture code.

---

# 10. Test architecture

The current test estate catches real browser bugs, but its largest files remain difficult to trust and maintain:

- `desk-backend.test.js` is roughly 7,963 lines;
- `dom.test.js` is roughly 9,171 lines.

Keep the browser protection but split it by ownership:

### Deterministic contract lane

Fast, hard-blocking tests:

- exact displayed strings;
- missing-evidence behavior;
- state identity;
- API-to-view mapping;
- money signs and units;
- Plan idempotency;
- world transition clearing;
- no silent defaults.

### Journey lane

Fresh database and browser per shard:

- Home;
- Scout;
- New Idea;
- Position;
- market/data;
- responsive/mobile;
- account/book.

Use event/state-based waits. Do not use fixed sleeps as readiness signals. Journey retries may run once, while preserving the original failure evidence.

### Geometry lane

Run at:

- 2560×1440;
- 1920×1080;
- approximately 2000×963 effective viewport;
- 1440×900;
- 1280×800;
- 1000×800;
- 390×844;
- 375×812;
- 320×700.

A clickability test is not a visual-usefulness test. Evidence & Paths needs explicit height, visibility, scroll-owner, and bounding-box assertions.

### Private visual environment

Use:

- a private port, never the owner’s 7070;
- a private copied or synthetic database;
- Simulated market;
- Yahoo and Cboe disabled;
- no provider allowance consumption;
- cleanup after each run.

---

# 11. Definition of done

This program is complete only when all of the following are true:

- Home is useful with zero, one, or many positions.
- Scout renders progressively and every result opens exact New Idea.
- Sector, market, symbol, and declarations share one context.
- New Idea shows all viable supported strategies and explains screening.
- Legs show complete strikes and executable bid/ask.
- Package values reconcile across every surface.
- Evidence & Paths is fully visible on desktop.
- No chart, fan, or core analysis panel accidentally scrolls.
- Position verdicts are evidence-aware and trigger-specific.
- Book gates affect open and close analysis.
- Independent simulations are never summed.
- The true Book ensemble is correlated and fingerprinted.
- The browser originates no financial fact.
- One CSS file owns the served application.
- One component owns each repeated visual concept.
- No hardcoded AMD/NVDA product default remains.
- Quote, history, chain, and news fail independently and honestly.
- Expected-move visualization is decision-specific and labeled.
- All `+N more` elements work.
- Desktop layouts are balanced; mobile retains capability with one page scroller.
- Every click has been exercised at every required viewport.
- Backend, contract, journey, geometry, and exact-string lanes are green.
- Superseded code, CSS, state, and compatibility paths have been deleted.

That is the complete product program: it preserves the engines and capabilities that are already valuable, finishes correctness consolidation, makes Home genuinely useful, routes Scout into the canonical New Idea experience, converges Position and Book on the same components, and eliminates the duplicated frontend architecture that has repeatedly caused the clipping and inconsistency.
