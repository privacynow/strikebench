# StrikeBench (formerly Options Lab) — project memory & handoff

Standalone local-first options strategy **education, paper-trading, backtesting, and optional live-trading** app.
Owner: Ahmedfaraz (babarahmedfaraz@gmail.com). This file is the single source of truth for decisions already made.
**Work style: build incrementally and run `mvn -q test` after every module. Never write large amounts of unverified code.**

## Standing Product & Execution Rules
- Never frame meaningful product scope as "for later", "deferred", "out of scope", or similar exclusionary language
  during brainstorming, planning, or execution. Sequence work by impact and dependency order, then keep moving through the
  sequence until the requested scope is genuinely handled. Do not avoid hard work because it is hard.
- Do not remove product capabilities. Existing features, including StrikeBench's trade recommendation and idea-generation
  workflows, must be retained and improved. The research, backtesting, education, and simulation agenda should make
  recommendations more accurate, explainable, and confidence-building, not replace them.

## Status (2026-07-06, second session — BUILD COMPLETE)
- **All 12 build-order milestones done on the user's Mac** (not the sandbox; network was open).
  Full backend + frontend + tests: **146 JUnit tests green** (`mvn -q test`), **12 Playwright DOM tests green**
  (`cd dom-tests && node --test dom.test.js`), fat jar boots and serves SPA + API.
- Toolchain on this Mac: Homebrew OpenJDK 25 (`openjdk@25`), Maven 3.9.11. Shell sets
  `JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` in `~/.zprofile`.
- Everything implemented per the sections below: pricing (BSM/IV/PayoffCurve/VolSurface/HistoricalVol),
  FixtureProvider + MarketDataService (+status), paper core (append-only ledger w/ replayed invariant tests),
  Guardrails + StrategyBuilder + RecommendationEngine, ApiServer (Javalin 7; all routes incl. broker + backtest),
  free providers (Cboe/Stooq/EDGAR/Treasury/FRED/Polygon/AlphaVantage, MockWebServer-tested),
  E*TRADE (OAuth 1.0a verified vs RFC 5849 vector; live gates + idempotent clientOrderId),
  Backtester (no-look-ahead pinned by test), SPA frontend (9 screens, beginner mode, dark mode, SVG charts),
  README.md.
- Key decisions made this session (consistent with the rules below):
  - Reserve semantics: reserve = max(0, maxLoss + entryNet) — debit trades reserve 0 extra (premium already left
    cash), credit trades reserve gross width; net BP impact at entry is exactly maxLoss + fees either way.
  - Multi-expiration (calendar/diagonal): debit → maxLoss = debit, maxProfit/POP/breakevens = null/empty
    (model-dependent); credit multi-expiration BLOCKED. Backtester rejects multi-exp + stock-hedged families.
  - Migrations runner strips `--` comments line-wise before splitting on ';' (V1 had a comment containing ';'
    that broke the naive splitter and silently skipped the accounts table).
  - `AppConfig(Map<String,String>)` test constructor: overrides > env > sysprops > file > defaults.
  - Backtest tier detection: provider chain source "polygon" → HISTORICAL_CHAIN, else MODELED; internal BSM
    chain fallback (HV30 + VolSurface.smile); fixture historical data used only when FIXTURES_ONLY.
- Live-provider smoke (2026-07-06, real endpoints): **Cboe OK** (real delayed AAPL chain, 25 expirations,
  spot ~$312.69 — fixture's $255.30 is fake), **EDGAR OK** (10 real filings w/ User-Agent). **Stooq blocked**:
  serves a JavaScript anti-bot interstitial to non-browser clients → provider correctly reports EMPTY and falls
  through to fixture candles; real candles require a Polygon or AlphaVantage key. Treasury/FRED wired but only
  exercised via riskFreeRate callers.
- Adversarial review (multi-agent, 28 agents, all 12 findings confirmed by 2 refuters each) → ALL FIXED
  with regression tests: (1) net-short multi-expiration "debits" booked with ~zero max loss → new
  strategy/CoverageCheck blocks structurally uncovered shorts in Guardrails + TradeService; (2) settle()
  now values at the expiration-day close (MarksSource.closeOn) not the click-time spot; (3) settle API
  no longer fabricates confirm=true on empty body; (4) post-commit audit writes are best-effort
  (auditSafe) so a committed trade can't report failure and be double-submitted; (5) no fake POP for
  calendars in marks; (6/7) mixed-exp Guardrails branch also checks coverage + buying power; (8) freshness
  aggregation is worst-of (Freshness.worse); (9) backtest reports the WORST pricing tier used, not best;
  (10) DateTimeParseException → 400; (11) holiday-tolerant coverage note. Final: 151 JUnit + 12 DOM green.
- Frontend was overhauled after user feedback ("basic and barely usable"): real design system in app.css,
  Home dashboard (default route), hero quotes w/ deltas, ATM-focused chain w/ ITM tinting + show-all
  toggle, score bars + chip metrics on candidates, numbered wizard steps w/ descriptive choice cards,
  gridlined SVG charts w/ breakeven markers, empty states, dark mode — verified by screenshots
  (desktop/mobile/dark) and the 12 Playwright DOM tests.
- Auto-scout added (user request): recommend/SignalEngine (20d momentum + keyword news sentiment w/
  evidence + IV-vs-HV vol signal + event risk; deterministic and explainable) derives thesis+confidence;
  recommend/AutoRecommender scans cfg.autoUniverse() (AUTO_UNIVERSE env; fixture list in demo mode),
  ranks by opportunity (confidence/vol-edge/liquidity), calls the engine per horizon (0DTE/week/month;
  0DTE candidates must actually expire same-day), rescores for vol fit (rich IV → credit +, cheap → debit +),
  annotates each candidate vs targetProfitCents/maxLossCents/risk%. POST /api/recommend/auto. Ideas page
  now has tabs: "Scout for me" (default) / "I have a view". Fixture news gained per-symbol sentiment
  variety for offline demos. 163 JUnit + 13 DOM tests green.
- INCIDENT (user-reported "Ideas/ticket fail to load / fails at risk"): root causes were NOT missing API
  keys (keyless design holds). (1) Static assets served with max-age=0 let a browser pair a cached OLD
  ui.js with a NEW views.js across a rebuild → "chip is not a function"-class crashes exactly at the
  ticket Risk step and on both Ideas tabs. FIX: static files now serve Cache-Control: no-store (tiny
  assets, local app). (2) Live-mode latency: every quote/expirations/chain call re-downloaded Cboe's
  full multi-MB per-symbol payload; the scout took ~23s cold. FIX: CboeProvider caches the parsed
  payload per symbol for 60s (test pins 1 HTTP download for quote+expirations+all chains) and the
  scout scans the universe on virtual threads. Measured after: scout 4s cold / 0.6s warm, manual ideas
  instant. LESSON: test recommend/ticket flows in LIVE keyless mode, not only fixtures. 164 JUnit +
  13 DOM green.
- Candle provenance (fix for "fixture data masquerading as real" in live mode): MarketDataService.candleSeries
  returns CandleSeries(candles, source, freshness); /history exposes source/freshness/demo flag (UI labels the
  chart + HV chip), SignalEngine marks momentum "DEMO price history" and dampens data quality, Backtester
  sets confidence "none (demo data)" + a prominent note, and settle's closeOn REFUSES fixture closes in live
  mode (falls back to the real current quote). BacktesterTest constructs AppConfig with FIXTURES_ONLY=true.
- Live-mode regression net: dom-tests/dom-live.test.js — 8 browser tests against the jar with NO
  FIXTURES_ONLY (real Cboe/EDGAR): every screen, full ticket lifecycle at real marks, scout <20s budget,
  page JS errors and 5xx are hard failures. Run alongside the 13 fixture DOM tests.
- SCENARIO SWEEP (6 curl-driven agents vs live+fixture servers, 20 findings) → ALL confirmed items fixed:
  - ROOT CAUSE OF USER'S FAILURES FOUND: rebuilding target/options-lab.jar UNDER a running JVM breaks lazy
    classloading; failures inside Javalin's exception pipeline make handleTask re-queue handleHttpException
    forever → threads spin at 100% CPU, pool wedges, requests hang (reproduced A/B with jstack; +1 spinner
    per request). FIX: Main.preloadAllClasses() loads all ~6.5k jar classes at boot (439ms, no static init)
    — after a jar swap, APIs keep working from resident classes, static returns clean 500s, ZERO spinners;
    plus resident cfg.router.javaLangErrorHandler, boot warmup of the 404/error pipeline, and a
    "jar changed on disk — RESTART" hint appended to 500s. (Stale-JS no-store fix was the other half.)
  - Malformed/empty/wrong-typed JSON bodies → 400 via JacksonException mapper (was 500 w/ Jackson internals).
  - Unknown trade/backtest ids → 404 via NoSuchElementException mapper (was 400).
  - Past-dated/expired contract legs BLOCKED at preview+create+guardrails (was accepted; settle could mint
    riskless profit). FixtureProvider.chain no longer fabricates chains for unlisted expirations.
  - guardrailCheck now prices request legs from fetched mids before Guardrails (omitted entryPrice defaulted
    to ZERO → debit diagonals misread as credit and blocked at create while preview approved).
  - error(404) no longer clobbers handler-written bodies (ctx.attribute("apiErrorWritten")).
  - /history echoes the normalized range; backtest rejects startingCashCents <= 0; scout notes when 0DTE was
    requested but allow0dte is off; Cboe 403 AccessDenied = unknown symbol (not provider ERROR).
  - Not fixed by design: arbitrary strategy labels allowed (risk math is label-independent); SPX index roots
    unmapped (Cboe uses _SPX) — follow-up.
  - Final: 171 JUnit + 13 fixture-DOM + 8 live-DOM green; jar-swap A/B verified no-wedge.
- FINANCIAL-REALISM HARDENING (after user placed a "riskless" TSLA collar at 9:24pm ET against a dead
  same-day-expiry book: put marked $0.005 vs $0.53 intrinsic → maxLoss $0, POP 1.0; ledger itself was exact):
  - market/MarketHours: RTH 9:30–16:00 ET Mon–Fri (holidays NOT modeled — documented); contracts DIE at
    16:00 ET on expiration day — TradeService blocks dead-contract entries (calendar-date check was not enough).
  - MarksSource.LegMark now carries bid/ask/mid; fills use executable(action) — BUY@ask, SELL@bid; one-sided/
    empty book = block. Unwind closes at opposite executable sides. Marks/unrealized stay at mid (display).
  - Quote-integrity blocks in TradeService + Guardrails: option mid < intrinsic − max($0.05, 2%) vs same-feed
    underlying = stale/dead → block; computed maxLoss <= 0 = impossible risk-free → block (engine surfaces it
    as a rejected candidate; backtester skips the entry).
  - Engine sizing: debit candidates capped by buying-power CASH, not just risk budget (was suggesting 5x
    collars ≈ $208K on a $100K account when maxLoss computed ~0).
  - Closed-market preview/create carry an explicit warning. Entry snapshots now record fill/bid/ask/mid.
  - Regression: PaperCoreTest replays the exact TSLA incident (dead-contract block + intrinsic-floor block +
    risk-free block + executable-fill arithmetic + one-sided book + weekend warning); MarketHoursTest pins the
    4pm-ET boundary. LIVE validation after hours: incident replay → 422; sane spread previews with honest
    warnings. 179 JUnit + 13 + 8 DOM green.
  - NOTE for user's existing DB: the bogus TSLA collar (tr_a6cjzj5e36f4zzfz) predates the fix — advise VOID.
- MONEY-REALISM HUNT (6 lenses; verifier fleet hit session limits so all 24 findings were self-verified
  against code before fixing) → ALL FIXED with pinned tests:
  - Crossed books (bid>ask) are not executable (LegMark.executable) — buying the ask/selling the higher bid
    minted money.
  - settle(): per-leg valuation at each leg's OWN expiration-day close (calendars no longer erase the
    inter-expiry move); gate is MarketHours.contractDead not a date compare (no intraday expiry cash-outs);
    closeOn is strict (candle date == expiry, no yesterday's-close stand-ins); missing close ⇒ reject until
    the next day, then a LOUDLY-LABELED fallback to the current quote (memo + WARN audit; audit written
    post-commit — in-tx audit caused SQLITE_BUSY).
  - Marks/unrealized (computeMark) valued at executable closing sides, matching what unwind actually pays.
  - Backtester: entries at executable bid/ask (slippage now an extra haircut), expiry settles at the close
    dated <= expiration, cash/reserve gate (skips "insufficient buying power" entries), WINDOW_END exits pay
    close fees and are EXCLUDED from winRate/avgRoR/sampleSize.
  - Broker gates: previewId must match a recorded preview (same account + byte-identical canonical payload,
    Json.canonical) within a 120s TTL; same clientOrderId with a different order = hard 409, not silent
    replay; cancels record CANCEL_REQUESTED (async, can lose to a fill), never terminal CANCELLED; required()
    runs before any other gate.
  - Sizing: server-side 100-qty cap; manual-ticket trades exceeding the header risk-mode budget get a loud
    WARN (deliberate freedom, labeled); engine candidates priced at executable sides (not mids).
  - Model honesty: IV-placeholder (30%) now labeled in preview + candidates and cuts confidence; DISCLAIMER
    states POP/EV/breakevens are pre-commission, zero-drift model outputs; earnings warnings wired from news
    keywords (ex-div stays false — no keyless source, documented).
  - Void/delete stays (user-decided design) but the modal now labels it a practice-only affordance.
  - Known accepted gaps: OptionQuote.mid()'s last-trade fallback can be stale (affects display marks only,
    fills/marks now use executable sides); market holidays unmodeled; ex-div calendar absent.
- EXPERIENCE LADDER UX (user-driven): Learning/Confident/Pro segmented control replaces the beginner
  checkbox (persisted, body classes lvl-*, screens RE-RENDER on switch). learn.js = GLOSSARY (~20 terms,
  tap-to-define popovers via UI.term) + STRATEGY_GUIDE (story/how/win/lose/watch per family).
  UI.expandable = lazy progressive-disclosure primitive. Learning: plain-language candidate cards
  (story + 3 big facts + expandable mechanics + exact-contracts expandable), 3-column chain + how-to-read,
  0DTE and aggressive mode hidden. Confident: one-line-clamped explainers (tap to expand), guide
  expandables. Pro: density body class, EV/liquidity/score chips, ticket steps 1-3 collapse into a
  quick-start row. Review step gained a Safety check checklist (pass/warn/fail rows restating guardrails
  in human terms) at all levels. Mobile: bottom tab bar <640px. DOM suites updated (ladder test replaces
  beginner-toggle test; learning wording "Most you can lose"). 186 JUnit + 13 fixture + 8 live green.
- PRO DEPTH (2026-07-07, user: "do that deeper rung as well; don't defer sensible work"):
  - Position greeks: `LegMark` carries Δ/Γ/Θ/vega (5-arg ctor kept; stock legs 1/0/0/0);
    `TradeService.PositionGreeks(deltaShares, gammaShares, thetaPerDay, vegaPerPoint, complete)` aggregated
    in computeMark with sign=closeSign(leg), mult=100·ratio·qty; MarkView gained `greeks` + `legGreeks`;
    `portfolioGreeks(accountId)` sums ACTIVE trades → `GET /api/portfolio/greeks`.
  - UI (Pro level only): Ideas comparison table when >1 candidate (`#compare-table`: sortable columns
    cost/maxLoss/maxProfit/R:R/POP/EV/breakevens/liq/score, row click expands full candidate card, Use
    button); trade-detail "Position greeks" card (`#greeks-card`: Net Δ sh / Γ sh/$ / Θ $/day / Vega $/pt
    chips + per-leg bid/ask/greeks/IV table — pnlSpan takes CENTS so dollar greeks are passed ×100);
    portfolio "Book greeks" strip (`#portfolio-greeks`, active tab); custom multi-leg builder off the
    quick-start (`#custom-builder-btn` → t.custom → step-5 add/remove leg rows with chain-populated
    selects → preview with `strategy:'CUSTOM'` through the same guardrail pipeline — undefined risk etc.
    still block). Chain strike option values are plain JSON numbers ("255", not "255.0") — select by index
    in tests.
  - Tests: `positionGreeksAggregateAcrossLegs`, `portfolioGreeksAggregateActivePositions`, DOM
    "pro depth" test (sort/expand table, build+place a custom 2-leg spread, greeks card + book strip).
    **188 JUnit + 14 fixture DOM + 8 live DOM green.** Ladder DOM test now sets its own level (no order
    dependence). Screenshots in dom-tests/shots/pro-*.png.
- INTENT TAXONOMY + HOLDINGS + FILTERS (2026-07-07, user: "income, hedge, and buying or selling
  [shares at desired price points] should be there... everything, everywhere should take this into
  account; allow filtering by all critical values"). SHIPPED — 215 JUnit + 15 fixture DOM + 8 live DOM:
  - `strategy.StrategyIntent` {DIRECTIONAL, INCOME, HEDGE, ACQUIRE, EXIT} + per-family intents mapping
    (CC→INCOME+EXIT, CSP→INCOME+ACQUIRE, collar→HEDGE+EXIT, credit/condor/calendar→INCOME, debit/long/
    fly→DIRECTIONAL); NEW family PROTECTIVE_PUT (riskRank 1); StrategyBuilder.BuildHints{targetPrice,
    sharesHeld} steers short strikes to the user's sell/buy price and omits stock legs for held shares.
  - Engine: Request gains intent/Holdings{sharesOwned(free; "shares I WANT" under ACQUIRE),
    costBasisCents, targetPriceCents}/Filters{minPop, maxAssignmentProb, minAnnualizedYieldPct,
    maxCostCents} (compat ctors keep old call sites). Candidate gains intent(s), assignmentProb
    (N(d2)/N(−d2) per DISTINCT short strike, summed, capped 1 — ATM iron fly honestly ~100%),
    annualizedYieldPct (ONLY share-backed: CC on share value, CSP on strike cash, collar — annualizing
    a condor's max RoR printed 4-digit "yields", review-confirmed and pinned), effectivePrice
    (strike ± OPTION premium/share — buy-write stock cost must not leak in, pinned), intentNote
    (assignment framed as CHANCE OF SUCCESS for EXIT/ACQUIRE), usesHeldShares/sharesNeeded/
    combinedMaxLossCents. Filters reject into rejected[] with exact numbers. ACQUIRE: budget=BP
    (CSP reserves the whole strike by design, note added), sized to holdings.sharesOwned as
    "shares I want" (default 1 lot) — API/scout NEVER inject the existing position for ACQUIRE.
  - V2 migration: positions table, trades.intent+shares_locked, ledger CHECK recreated w/
    STOCK_BUY/STOCK_SELL (append-only copy, ids preserved). PositionsService: buy@ask/sell@bid,
    weighted-avg basis from EXACT ledger cost, realized-vs-basis on sells, locks = Σ shares_locked of
    ACTIVE trades, reset clears positions. TradeService: useHeldShares plans (risk display = COMBINED
    curve incl. held lot — also for protective puts so preview matches the candidate; ledger
    maxLoss = incremental cash risk; reserve 0), in-tx free-share checks (max(lock, 100·qty)),
    physical assignment at settle: short calls deliver UP TO shares_locked at the strike (remainder
    cash-settles — a 2x-short/1x-long-covered custom must never strip another trade's collateral,
    pinned), CSP physical only when STRUCTURAL (single short put) AND FUNDED (reserve ≥ strike×100 —
    a relabeled put spread drove cash negative in review, pinned).
  - Routes: /api/positions (+/buy,/sell), trades?symbol=&intent= (DIRECTIONAL matches NULL legacy
    rows), recommend auto-fills holdings from the real position (except ACQUIRE), payoff chart
    includes the locked lot, guardrailCheck reports share shortfalls honestly ("needs N free shares",
    not "add a protective wing"), bodyOrNull treats the literal JSON document "null" as absent
    (JavalinJackson NPEs → were 500s). Scout: intents[] + filters; EXIT/HEDGE scan HOLDINGS not the
    universe ("buy shares first" when none); Backtester: stock-hedged families supported (stock legs
    from candles; CC reserve 0 / CSP strike cash pinned), assignments counted per report, unusable
    historical chains (Polygon has no bid/ask/greeks) FALL BACK to the modeled tier (pinned), zero-entry
    runs report confidence "none", assumptions text corrected to executable-sides fills.
  - UI: Ideas "My own idea" tab is intent-first ("What are you trying to do?" 5-way chooser; thesis only
    for DIRECTIONAL; target-price for EXIT/ACQUIRE/HEDGE — only sent when visible, stale-hidden-target
    bug pinned in review; shares-wanted for ACQUIRE; live holdings hint; shared Filters expandable, ids
    rec-f-*/auto-f-*); candidate cards carry intent badge/intent-note strip/USES HELD SHARES badge/
    assignment/yield/effective-price/worst-case-w-shares chips; Learning third fact becomes "Chance you
    sell/buy" for EXIT/ACQUIRE; comparison table adds Assign%/Yield columns w/ covered-call R:R computed
    on combinedMaxLoss ($0* footnote); Portfolio holdings card (LOCKED badges, buy/sell modals,
    "up X% — Sell at a target…" nudge prefilling EXIT ideas) + symbol/intent trade filters; trade detail
    shows intent badge + "Covered by N held sh (locked)" chip + "New cash at risk $0 (covered)";
    review checklist passes covered trades with honest wording; backtest form groups families by intent
    incl. the stock-hedged four + "Assigned at expiry" stat; App.render serialized (filter-change +
    nav race duplicated the page); closed tab merges EXPIRED/DELETED on page 0 only; manual-ideas form
    state persists in App.state.ideasForm across level-switch re-renders. learn.js: INTENTS meta,
    glossary (+cost basis, annualized yield, effective price, covered), PROTECTIVE_PUT guide.
  - Adversarial review (6 lenses → 24 findings → 3 refuters each): 22 confirmed, ALL fixed (list above
    marks the pinned ones); 2 refuted (held-CC yield labeling judged consistent; learning-card covered
    framing already includes context). Screenshots: dom-tests/shots/intent-*.png.
  - LADDER TUNING (user: "all of this needs to be tuned to the type of user"): the intent feature now
    climbs the ladder structurally like everything else — Learning: intent story cards; filters =
    two plain limits ("most I'm willing to lose" + "min chance of any profit", glossary-termed) behind
    "Only show ideas that fit my limits"; holdings columns in plain words; backtest menu = beginner
    families only (+unlock note). Confident: five-filter expandable w/ glossary labels, one-line-clamped
    intent blurbs. Pro: compact segmented intent row (blurbs as tooltips), all five filters INLINE
    (.compact-filters, no expandable), holdings + Unrealized $ column, full backtest menu. filterPanel()/
    renderManual()/holdings card/backtest select all branch on Learn.currentLevel(); ladder DOM test
    pins per-level filter fields, compact chooser, and backtest menu gating; holdings DOM test asserts
    the lock badge (the learning explainer legitimately contains the word LOCKED). ideasForm persistence
    means DOM tests must reset intent to DIRECTIONAL when they assume it. Screenshots:
    dom-tests/shots/ladder-ideas-{learning,pro}.png. 215 JUnit + 15 fixture DOM + 8 live DOM green.
- BULLETPROOF-UI PASS (2026-07-07, user: brand click + Trade/Portfolio/Account/Data "failing to load";
  root cause = STALE SERVER PROCESS: jar rebuilt 6x under the running JVM — current build renders every
  route clean against a copy of the user's real DB, verified via playwright; user must restart; their DB
  still holds the pre-fix TSLA collar tr_a6cjzj5e36f4zzfz — advise Settle/Void):
  - GET /api/health {ok, startedAt, jarChangedSinceBoot} (never 500s; reuses jarChangedHint mtime logic);
    UI checks health at boot + every 5 min + on any route error and shows a red #stale-banner
    ("rebuilt after this server started — restart") instead of mystery failures. Route error boundary
    #route-error names the screen, shows the reason, offers Retry + "Check data status".
  - Brand is config: AppConfig.brandName()/brandTagline() (BRAND_NAME/BRAND_TAGLINE), exposed via
    /api/config.brand, applied at boot to document.title + header .brand. README env table updated.
  - Theme toggle: html[data-theme] set PRE-PAINT by an inline head script (localStorage
    'optionslab.theme': system|light|dark); header #theme-toggle cycles Auto/Light/Dark; dark palette
    moved from the prefers-color-scheme media query to :root[data-theme="dark"] (+color-scheme); JS
    listens to media change in system mode. Charts/components read vars so everything follows.
  - NEW REGRESSION NET dom-tests/dom-seeded.test.js (5 tests): grows a DB through the API (shares,
    share-covered call, spread, closed trade, backtest) + injects a LEGACY dead collar row byte-identical
    to the user's real pre-fix trade (stock:true legs_json, expired legs, maxLoss=0/POP=1, NULL intent),
    then walks EVERY route x 3 levels + every trade detail; pageerror/5xx/#route-error = fail. LESSON:
    fresh-DB suites miss grown-state bugs — run all three DOM suites (fixture, seeded, live).
  - dom.test.js gained 'theme toggle, brand, health banner, route error boundary' (16 tests; intercepts
    /api/health to assert the banner, aborts /api/status to assert the boundary + Retry).
- UI-CONSISTENCY REVIEW FLEET (same session; 4 lenses x 2 refuters -> 26 confirmed, 1 refuted) — ALL FIXED:
  silent async onclick failures (refresh-marks, backtest previous-runs, place-trade duplicate errors —
  pattern: disable button, catch, render alert with stable id, remove stale one); optional cards must
  FAIL VISIBLY (holdings card keeps rendering w/ Retry; home Markets empty-state; research history
  unavailable note; ticket step-5 chain warning + candidate strike kept in the select); chain-load
  SEQUENCE TOKEN (slow response for a prior expiration must never overwrite the current one); ticket
  respects engine-sized qty (t.qty = t.qty || candidate.qty || 1 — was force-reset to 1, silently
  shrinking every sized candidate); ladder guards on PERSISTED ticket state (leaving Pro mid-custom or
  Learning w/ 0DTE horizon falls back to guided steps); scout form + last scan persist in
  App.state.scoutForm/scoutResults; scout had TWO max-loss inputs -> filter panel is the single control;
  scout surfaces up to 3 "Refused: family — reason" notes (AutoRecommender) so the filter-panel promise
  holds on both tabs; dark-mode danger contrast via --risk-danger-solid (pastel --risk-danger is
  fg-oriented; .btn-danger/#stale-banner use the solid + white); stale banner no longer fights the
  sticky topbar (body.has-stale-banner offsets .topbar by --stale-banner-h); portfolio filter change
  resets pagination; copy fixes (reset removes ALL share holdings; collars lock shares too; Data-screen
  copy uses brandName()). Suites after: 215 JUnit + 16 fixture + 5 seeded + 8 live DOM green.
- NAMING (researched 2026-07-07): "Options Lab" is unownable — 8+ active products incl. optionslab.app
  (iOS education), optionslab.io (calculators), Geojit OptionsLab (backtesting), optionslabpro.com;
  optionslab.com is squatted. Top candidates: ThetaBench (zero collisions; thetabench.com dormant),
  StrikeBench (strikebench.com UNREGISTERED), PaperStrike (most literal; only a party-game collision).
  BRAND_NAME/BRAND_TAGLINE config makes the rename a one-line change; needs a real trademark search.
- STRIKEBENCH REBRAND (2026-07-08, user bought strikebench.com; "internally consistent everywhere it
  matters"): BRAND default "StrikeBench"; properties file RENAMED optionslab.properties ->
  strikebench.properties (AppConfig; also fixed Properties.load Latin-1 garbling by reading UTF-8);
  pom artifact/finalName -> strikebench (jar: target/strikebench.jar; JAR_PATH self-locates so no
  hardcoding); default DB data/strikebench.db with ONE-TIME boot move of legacy data/options-lab.db
  (+ -wal/-shm) in Main.migrateLegacyDefaultDb — verified against a copy of the user's real DB, data
  intact; explicit DB_PATH never migrated (MainMigrationTest pins all of it); localStorage keys ->
  strikebench.{level,riskMode,theme} with one-time migration in learn.js (+ legacy fallback in the
  index.html pre-paint theme script) — verified prefs survive; EDGAR User-Agent -> StrikeBench/1.0;
  static index.html title/brand/noscript, JS/CSS headers, log lines, dom-tests jar paths + brand/theme
  assertions all renamed. KEPT DELIBERATELY BRAND-NEUTRAL (reviewed): io.liftandshift groupId
  (owner namespace; the Java PACKAGE later moved to io.liftandshift.strikebench 2026-07-08 — see
  Non-negotiable #1), env var names (PORT/DB_PATH/...), DB table names, id prefixes (tr_/acct_/...).
  219 JUnit + 16 fixture + 5 seeded + 8 live DOM green on strikebench.jar.
- PRODUCT UX PASS (2026-07-08, user: match/exceed optionslab.io — opening page, chart range pills w/
  mouse-slide readouts, universe/ticker everywhere): V3 settings table; market/Universes (13 curated
  sector lists incl. DEMO) + UniverseService (settings-persisted sector/custom > AUTO_UNIVERSE env >
  DEMO/CORE default) + GET/PUT /api/universe + GET /api/quotes (light batch, defaults to active
  universe, caps 40) + scout default universe = active universe (ApiServer injects); /history gains
  ytd/5y/max (fixtures carry ~3y). UI: ticker TAPE under the header (marquee, hover-pauses, click->
  research, 45s refresh, reduced-motion -> static scroll row; Playwright must hover #tape before
  clicking items — marquee defeats the stability check); UI.rangeChart (pills + summary chips +
  supersede guard) + interactiveChart crosshair layer (pointermove, viewBox->px mapping, edge-flip
  tooltip) wired into lineChart (date/value-2dp/%-in-window; chart-down red coloring via opts.baseline)
  and payoffChart (price->P/L interpolation readout); research uses rangeChart (#history-card);
  universe sector select on scout (#universe-sector, PUTs globally), datalist#universe-symbols feeds
  rec/ticket/backtest symbol inputs; WELCOME page (views.welcome + #/welcome route): hero w/ brand/
  tagline, 3 level-path cards (set ladder + navigate + strikebench.welcomed), capability grid,
  01-04 steps, skip->dashboard; home() shows it for fresh accounts (hasTraded=false && !welcomed);
  dashboard Markets uses active universe (first 8) + Welcome-tour link. CSS: tape marquee, range
  pills, chart tooltip/xhair, welcome hero/grid/steps, hover-lift/focus-visible/active-scale micro-
  polish, all guarded by prefers-reduced-motion. Tests: ApiIntegrationTest#universeSelectionQuotesBatch
  AndHistoryRanges (25 total); dom.test.js boot test = welcome->skip->dashboard + new 'interactive
  charts, range pills, universe picker, tape' test (scrollIntoView before chart hover — below-fold
  hover silently no-ops); dom-live boot skips welcome if present. 220 JUnit + 17 fixture + 5 seeded +
  8 live DOM green.
- HOLISTIC PASS 2 (2026-07-08, user: "you only focused on the two areas I pointed out… improve the
  product holistically" — feedback saved to memory as a standing rule): TAPE SEAM FIXED (root cause:
  fixed 40s duration + -50% on a strip whose halves were narrower than the viewport; now one sequence
  is measured, cloned until each half >= viewport, duration = halfWidth/55px/s; DOM test pins even
  sequence count + halves >= 2x viewport; VISUALLY verified mid-loop screenshot); tape gained the
  SECTOR SWITCHER (#tape-sector, PUTs the global universe — sectors reachable from every tab);
  BRAND CLICK -> #/welcome (the product page); welcome enriched: live engine-generated candidate
  ("See it think", POST /api/recommend at render, DOM-pinned) + sector-explorer entry; SECTOR
  EXPLORER (views.sectorExplorer, #/research index): 13 sector chips -> live quote tiles (batch
  /api/quotes) w/ Research/Ideas actions, missing-data honesty line, "Make this my universe" +
  "Scout this sector" (prefills scoutForm.universe); HOME SECTOR PULSE (#sector-pulse: per-sector
  ETF-proxy day change chips -> explorer; async, replaces an anchor div); HEADER GLOBAL SEARCH
  (#global-search, universe datalist, Enter -> research, "/" focuses, hidden <900px). Test-order
  gotchas fixed: boot test must end at #/home; explorer set-universe assert must use a sector that
  is NOT active; text= selectors flaky for badges — use .quote-hero .badge:has-text(). 220 JUnit +
  17 fixture + 5 seeded + 8 live DOM green. Screenshots: shots/ux2-*.png.
- INTENT-NATIVE UX (2026-07-08, user: intents "just change the labels… not thought through from a
  user's lens, and certainly not novice/confident/pro"): each hold-based goal now has its OWN
  interaction, not a generic candidate list:
  - Engine.ladder(Request, bp) -> LadderResult{rungs: List<Candidate>}: 4-6 strikes of ONE family
    (ACQUIRE->CSP stepping DOWN from spot, EXIT->CC climbing UP, HEDGE->PP floors DOWN), built via
    BuildHints(targetPrice=strike) so each rung snaps exactly; every rung is a full Candidate
    (executable pricing, assignmentProb, yield, effectivePrice, held-shares sizing) and goes
    straight to the ticket. POST /api/recommend/ladder (holdings auto-injected except ACQUIRE).
    Pinned: strikes monotonic per intent, credits positive, effective<strike (CSP), EXIT rungs
    usesHeldShares w/ maxLoss=0, DIRECTIONAL->400.
  - Ideas result views by intent: ladderView() — ACQUIRE "Name your price" / EXIT "Pick your exit" /
    HEDGE "Pick your floor"; LADDER-SHAPED PER LEVEL: Learning = sentence rows + one highlighted
    "A SANE MIDDLE" (or closest-to-target) rung + Practice buttons; Confident = rung table w/
    tap-to-expand cards; Pro = dense table (+Cash-set-aside / Yield/yr / POP-w-shares columns).
    EXIT table shows "vs your basis" when holdings known. Engine cards demoted below the ladder as
    "Other structures for this goal". INCOME renders incomeBoard(): YOUR capital (free cash, shares
    to rent out, best idea pays $X at Y%/yr) above the candidates.
  - Forms: EXIT/HEDGE show YOUR HOLDINGS as one-tap chips (#holdings-chips w/ gain badges); target
    gets live %-preset buttons (#target-presets: EXIT +5/+10/+20%, ACQUIRE/HEDGE -5/-10/-15% off the
    live quote).
  - Research symbol page: "What you can do with SYM" (#symbol-actions) — per-goal action cards with
    LIVE one-liners computed from real ladder rungs ("Get paid $839 now to buy at $255…"), holdings-
    aware (exit/hedge cards only when held; acquire card when not), one-tap prefilled handoff;
    Learning caps at 3 cards. Scout groups picks under intent headers when scanning multiple goals.
  - 226 JUnit + 18 fixture + 5 seeded + 8 live DOM green. Screenshots shots/intent2-*.png. DOM test
    covers acquire-learning sentences, exit-pro table, preset buttons, holdings chips, income board,
    symbol actions, rung->ticket.
- VISUAL DESIGN PASS (2026-07-08, user: hero "looks very much like a dim AI generated it… text
  littered… follow real UX patterns"; sector selector colliding with the tape): welcome REDESIGNED —
  hero = eyebrow (PAPER TRADING · LOCAL-FIRST · FREE) + display H1 w/ gradient span ("Learn options
  by *doing*, with honest numbers." — the H1 sells the VALUE; the brand stays in the header, boot
  test updated accordingly) + short sub + two CTAs (Find my first idea / See how it works → smooth-
  scrolls to #how-it-works) + QUIET trust row (dot-separated small text, not emoji chips); sections
  get eyebrow labels; EMOJI REPLACED with a hand-drawn stroke-SVG icon system (views.js ICON_PATHS +
  icon(name) — 12 icons, currentColor, .icon-tile accent squares); capability copy tightened; steps
  are a real horizontal stepper (ghost 01-04 numerals + connectors); live example framed in a
  .showcase-frame w/ gradient panel + caption; single .cta-banner (sector explorer) + ghost skip.
  CSS: hero radial gradient, type scale via clamp, btn-lg/btn-ghost, eyebrow, icon-tile, stepper,
  showcase, cta-banner. TAPE FIX: sector select restyled as a self-contained pill (flex sibling, not
  a hard-bordered column) + .tape-scroll gets mask-image edge fades (56px in / 40px out) so items
  dissolve instead of clipping into the control; item borders softened. Verified via screenshots
  (design-hero{,-dark}.png, design-lower.png, design-tape2.png) light+dark. 226 JUnit + 18 fixture +
  5 seeded + 8 live DOM green. (COUNT CORRECTION 2026-07-07: the "226 JUnit" here and in the
  intent-native entry was a bookkeeping slip — the surefire total at that snapshot was 220; nothing
  was ever lost. Verified via git snapshot d290c43 + per-class surefire counts.)
- DESIGN-LANGUAGE UNIFICATION (2026-07-07, user: select heights inconsistent; sector clicks dead-end;
  scout overflows + confusing multi-intent checkboxes; emojis remain; backtest an afterthought;
  charts missing there; "one sane design language everywhere"):
  - UNIFIED CONTROL SCALE (app.css tail section): --ctl-h 38px for all content inputs/selects/date
    pickers and .btn (inline-flex centered); --ctl-h-sm 30px for .btn-sm/.sym-chip/.sector-chip;
    header controls pinned to one 30px scale (#global-search/.risk-select select/.theme-toggle);
    .btn-lg 46px. Overflow hygiene: .form-grid .field/.grid>*/.compact-filters .field min-width:0,
    #app overflow-x: clip.
  - MOBILE OVERFLOW ROOT CAUSE was NOT the tape: .topbar-controls was an unwrappable flex row
    (~458px at 375px viewport) pushing document.scrollWidth wide on EVERY route. Fix: flex-wrap on
    .topbar-controls + compact level-switch under 640px. (The audit's naive "widest element" blamed
    tape-strip because getBoundingClientRect ignores ancestor clipping — check clipping before
    blaming.)
  - EMOJI FULLY RETIRED → shared SVG icon set moved views.js→ui.js as UI.icon(name,size)+ICON_PATHS
    (added sun/moon/halftone/warn/pen/grid/magnifier): bottom-nav (inline SVGs in index.html),
    Learn.INTENTS.icon now holds icon NAMES (compass/coins/tag/flag/shield), ideas intent chooser
    (.choice-head), research actionCards (.icon-tile-sm), theme toggle (SVG+text), stale banner,
    safety-check warn mark. learn.js stores \uXXXX escapes literally — Edit tool can't match them;
    use perl/python for such swaps.
  - SCOUT RETHOUGHT AS A QUESTION: five intent checkboxes → single-select goal chips (#scout-goal,
    radio semantics, .goal-chip w/ icon) + "Everything" (grid icon) that scans all five grouped;
    live blurb (#scout-goal-blurb) restates the selected goal in a sentence. scoutForm.goal persists
    (back-compat maps old intents[]). Layout order: goal → budget/universe grid → expirations →
    filters → explain → scan.
  - EXPLORER NEVER DEAD-ENDS: sector grid renders EVERY symbol as an actionable tile; quote-less
    tiles show "NO LIVE DATA" badge + em-dash price but keep Research/Ideas buttons (.tile-nodata).
    This was the "clicking sectors shows nothing clickable" complaint — demo mode showed an empty
    state for 12 of 13 sectors.
  - BACKTEST AS A PRODUCT: window pills (3M/6M/1Y/2Y reusing .range-pills/.pill; hand-editing dates
    clears the active pill), DTE preset chips (Weekly/Monthly/Quarterly), learning-level intro copy,
    equity curve now leads with the standard .chart-summary chip row (window, change $/%, high/low,
    max drawdown) above the interactive crosshair chart; candidate cards (confident/pro) gained
    "Backtest this" → App.state.backtestPrefill {symbol,strategy} prefills the form (prefill extends
    the level-gated menu rather than silently swapping strategy). Candidate cards carry
    data-strategy for deterministic tests. Trade detail marks history now draws UI.lineChart of
    unrealized P/L when ≥3 marks (marks come newest-first; reverse before charting).
  - PERMANENT REGRESSION NET: dom-tests/dom-audit.test.js walks 11 routes × {1280,1000,375}px
    asserting (1) zero horizontal page overflow, (2) zero emoji text nodes (range regex incl.
    U+2600-27BF), (3) every control height ∈ {38,30,46}±1.5px. Run it with the other suites.
  - Suites: 221 JUnit + 19 fixture DOM + 5 seeded + 8 live + 3 audit — ALL GREEN. Screenshots
    dom-tests/shots/d2-*.png (scout learning/pro/dark/mobile, ideas chooser, explorer TECH,
    backtest form/report/equity, mobile home).
- SMOOTH PIPELINE + MULTI-LEG BUILDER (2026-07-07, user: abrupt view/level/ticker transitions;
  home blank 1-2s; "middle ground" caching not a memory hog; learning = scattered sentences; and a
  broker-grade multi-leg builder for ALL levels w/ live risk/probabilities and leg hover/click insight):
  - GET CACHE (api.js): 20s-TTL, 40-entry-LRU read-through cache on API.get; ANY successful
    mutation (POST/PUT/DELETE) flushes it EXCEPT pure-compute POSTs (/api/recommend*, /api/trades/
    preview — the builder previews constantly); /api/health + /api/status never cached; failures
    never cached; API.getFresh + API.flushCache exposed. GOTCHA: DOM tests seeding state via raw
    page fetch() bypass the flush — use API.post/put inside page.evaluate instead (3 sites fixed).
  - RENDER PIPELINE (app.js _renderOnce): data-ready drops SYNCHRONOUSLY at render start (the
    crossfade is async — tests catching the OLD screen's data-ready=true was the entire first wave
    of failures); swap (clear + UI.skeleton paint) runs inside document.startViewTransition when
    available + motion allowed (#app has view-transition-name: app-main; root old/new animation
    DISABLED so the ticker never freezes inside a snapshot); skeleton removed by MutationObserver
    on the view's first real append (and in finally). CSS: .skel shimmer, card-in fade-rise on
    #app .card/.candidate/.tile, all reduced-motion guarded.
  - TAPE: refreshTape with an UNCHANGED symbol set updates prices/deltas IN PLACE (data-symbols
    attr on the strip, per-item data-sym) — the marquee no longer restarts at 0% every 45s tick;
    rebuilds only on genuine universe change. App.refreshTape exported for tests.
  - HOME: h1 + stats appended before ANY await; account fetch decides welcome redirect + fills
    stats; Markets uses ONE /api/quotes batch (was 8 sequential full-research calls — /api/quotes
    rows gained "description"); trades card fills async; every section fails visibly.
  - LEARNING LANGUAGE: explain() gained a fixed info icon (absolutely positioned so the confident
    line-clamp override still works); mid-form explainers moved to ONE slot (top of card, under
    header): scout, manual ideas, backtest, chain card, stock modal. Card ORDER is identical
    across levels — level switches morph instead of reshuffling.
  - PREVIEW ENRICHMENT (server): TradePreview gained underlyingCents, assignmentProb (engine's
    N(d2) math via new public RecommendationEngine.assignmentProbabilityFromIvs — preview and
    Ideas can never disagree), legs[] (action/type/strike/exp/ratio/fill/bid/ask/mid/iv/Δ/Γ/Θ/vega/
    freshness — snapshotLegs enriched, so persisted snapshots improve too), payoff[] (PayoffCurve.
    chartPoints(spot): 60-step grid + knots + breakevens, shared with ApiServer.payoffPoints).
    Blocked undefined-risk plans still carry legs+payoff (the builder charts the cliff as the
    lesson). Calendars: payoff honestly empty. NON_NULL mapper: assignmentProb absent when no
    shorts (tests assert .has()==false). Pinned in ApiIntegrationTest order(23).
  - STRATEGY BUILDER (js/builder.js, window.Builder; third Ideas tab "Build a strategy" =
    #/recommend/builder; ticket()'s pro-only custom guard REMOVED — custom tickets legit at every
    level): 26-template catalog grouped by job (bullish/bearish/neutral-income/volatility/pinpoint/
    shares) incl. buy-write, risk reversal, straddles/strangles (short versions labeled "USUALLY
    BLOCKED" — selecting one previews honestly, then blocks with the cliff chart: deliberate
    education), backspreads, calendars/diagonals, collar, blank custom. Templates build from LIVE
    strikes (ctx.pick(exp, ATM-offset)); catalog COLLAPSES to a one-line summary + "Change
    structure" after selection so legs+panel are in view. Leg rows: buy/sell, call/put/100-shares,
    exp+strike selects (chain-fed), ratio 1-10, Details button, remove. HOVER = .leg-pop floating
    market tip (bid/ask/IV/Δ/Θ/OI/vol/DTE/moneyness, zero layout shift); DETAILS = inline card w/
    per-leg cash effect (fill×100×100×ratio×qty CENTS — the ×100-twice is dollars-per-contract
    then cents, pinned by a fixed bug) + greeks + lazy "without this leg" impact (one extra
    preview; maxLoss/maxProfit/POP before→after). Live panel (sticky ≥1000px): verdict banner
    (refused/cautions/passes), You collect|pay, max loss/profit, POP, assignment odds, fees,
    breakevens, BP after, reserve, payoff chart from p.payoff, "Review & place (paper)" →
    App.state.ticket={custom, step:6} into the standard ticket review (disabled while blocked).
    Per level: learning gets stories (STRATEGY_GUIDE expandable, open) + full blurbs; confident
    clamps blurbs; pro compact grid w/ title tooltips. builderForm persists in App.state.
  - Playwright gotchas added this pass: waiting for '#app[data-ready="true"]' after clicking a
    NAV affordance must scope to the target route ([data-route="ticket"]) — data-ready may still
    be true for the outgoing screen; tape-item clicks should target nth>=4 (left-edge items slide
    under the sector select); re-navigating to the SAME hash needs App.navigate (no hashchange).
  - Suites: 222 JUnit + 21 fixture DOM + 5 seeded + 8 live + 3 audit (builder route added to the
    audit walk) — ALL GREEN. Screenshots shots/sm-builder-{learning,confident,pro,dark,mobile}.png,
    sm-home.png, sm-scout-hints.png, bld-*.png.
- TWO-LEVEL LADDER + BUILDER V2 + DESKTOP HOME (2026-07-07, user: confident tier killed
  ("neither here nor there"), rename learning->Beginner / pro->Expert; desktop home must not
  scroll; builder was "too busy for a learner" and expert was "beginner minus text"; full broker
  strategy menu w/ education; SVG graphics; investigate "market data not loading"):
  - LEVELS: exactly TWO — beginner/expert (learn.js LEVELS/META; localStorage one-time map
    learning->beginner, confident->beginner, pro->expert; body classes lvl-beginner/lvl-expert;
    .beginner-only/.expert-only/.not-expert CSS helpers; 2-button header switch; welcome = 2 door
    cards). ALL confident branches deleted: explain() clamp CSS + boot click handler, filterPanel
    (beginner: 2 plain limits in expandable / expert: 5 inline), ladderView, intent chooser,
    holdings columns, BT_LEVEL_ALLOWED {beginner: 7 families} else everything. STANDING RULE in
    memory: never reintroduce a middle tier; expert = structurally deeper tool, not fewer words.
  - BUILDER V2 (js/builder.js rewritten; moved to the TRADE screen: #/ticket pills "Guided
    ticket | Strategy builder", #/ticket/builder; Ideas back to 2 tabs; ticket() step-5 custom
    flow + renderCustomBuilder DELETED — quick-start's #custom-builder-btn now navigates to the
    builder; builder hands off to ticket review step 6 as before):
    * Beginner = question wizard: Goal (intent cards) -> Shape (one question per goal, choice
      cards, e.g. DIRECTIONAL: rise-a-lot/rise-somewhat/fall/big-move/calm) -> Build-it: legs
      arrive ONE AT A TIME, each with a plain-language story w/ live fill dollars ("Sell the
      $250 put (you collect about $362) — you promise to buy 100 shares at $250 if asked"),
      impact chips showing before->after (worst case, best case, POP), the payoff chart morphing,
      and the undefined-risk teaching moment ("alone this leg has unlimited risk — the next leg
      caps it") -> Where-you-stand: recap + full panel + review handoff. "Browse all structures"
      from steps 1/2 opens the FULL catalog.
    * Expert = terminal: symbol/qty/structure-select bar, leg grid w/ INLINE market data per row
      (bid/ask Δ Θ, .leg-mkt), per-leg include CHECKBOXES (untick = instant position-without-leg
      analysis, "N LEG OFF" badge), hover pop w/ full greeks/OI, net greeks chips (Δsh/Γ/Θ$day/
      Vega$pt from preview p.legs), dense panel, payoff chart.
    * Catalog: 26 structures incl. verticals (named), married put, risk reversal, backspreads,
      calendars/diagonals, iron condor/fly, straddles/strangles, AND blocked-by-design naked
      call/put + short straddle/strangle in an "Undefined risk (blocked)" group — selecting one
      previews honestly then blocks with the cliff. EVERY card carries a payoff-SHAPE sketch
      (tpl-shape: inline SVG polyline vs a dashed zero line — educational shape, not live data).
  - DESKTOP HOME FITS ONE SCREEN: .home-cols 2fr/1fr >=1100px (left: Markets + Open trades;
    right: Sector pulse + 4 quick actions incl. Build a strategy); home-desktop compaction CSS
    (@media >=1100px: stat explainers/h1/t-nm/qa-hints hidden — hints become title tooltips,
    tighter paddings); Markets capped at 4 tiles (explorer has the rest). Measured: content
    bottom 835/900 @1440, 744/800 @1280 (only the boilerplate disclaimer sits below the fold).
  - "MARKET DATA NOT LOADING" INVESTIGATED, NOT REPRODUCIBLE ON CURRENT BUILD: dom-live.test.js
    ran 8/8 GREEN against real Cboe/EDGAR mid-investigation (every screen incl. trade + scout at
    real marks). No data source lost; graceful fallbacks unchanged (provider chain w/ labeled
    freshness, honest empty states + Retry, tape hides on error). Diagnosis: almost certainly the
    documented stale-server failure (jar rebuilt under a running JVM — the red RESTART banner +
    /api/health exist for exactly this). User action: stop the process, java -jar target/
    strikebench.jar, reload; then check the Data screen if anything still looks empty.
  - CHARTING LIBRARY RESEARCH (user asked; integration is the NEXT pass): recommendation D3 v7,
    vendored into /public/vendor (no build step, no CDN): SVG-native (matches the existing
    hand-rolled SVG chart system + the user's explicit "svg based" ask), MIT, API frozen since
    2021 w/ maintenance releases — the stability profile requested. Chart.js = easier but canvas
    + general-purpose; Highcharts = commercial license (ruled out); lightweight-charts = canvas,
    finance-native (fallback candidate if candlesticks become the priority).
  - Tests swept to 2 levels (dom.test data-level selectors, ladder test asserts exactly 2 buttons,
    seeded walks beginner+expert => 4 tests, audit walks #/ticket/builder). Builder test rewritten:
    wizard leg-walk w/ impact chips + browse-all catalog (>=24 tpl, shape SVGs, BLOCKED badges) +
    expert terminal (inline mkt data, LEG OFF toggle, net greeks, hover no-layout-shift). Pro-depth
    test's custom-flow segment now drives the expert terminal end-to-end to a placed paper trade.
  - Suites: 222 JUnit + 21 fixture + 4 seeded + 8 live + 3 audit — ALL GREEN. Screenshots
    shots/v3-{home-desktop,wizard-goal,wizard-shape,wizard-leg1,catalog,terminal}.png.
- D3 CANDLESTICKS SHIPPED (same session, "finish the work"): d3.v7.9.0.min.js VENDORED at
  public/vendor/d3.min.js (ISC, no CDN — jar-served like everything else; script tag before
  learn.js). UI.candleChart(candles, opts): real OHLC bars (d3.scaleBand/scaleLinear/.nice
  ticks; green up / red down + wicks; >160 bars aggregate to WEEKLY candles), standard
  crosshair tooltip (date, O/H/L/C, ±% in window, volume), GRACEFUL FALLBACK to the close
  line when window.d3 is absent or <2 candles — never blank. UI.rangeChart gained a candles
  mode (fetch returns {candles} instead of {series}; summary chips computed from closes);
  research /history now feeds raw candles. CSS .candles .candle-up/.candle-down/.candle-wick.
  Test pins: >=20 rect.candle + .candle-up present + OHLC tooltip text (dom.test 'interactive
  charts'). ALL SUITES GREEN after: 222 JUnit + 21 fixture + 4 seeded + 8 live + 3 audit.
  Screenshot shots/v3-candles.png. Payoff/equity charts stay hand-rolled SVG (already
  interactive); D3 is available app-wide for future visuals.
- OPENING-SCREEN FOLD + FULL STRATEGY OFFERING + PIPELINE STREAMLINE (2026-07-07, user
  clarified "home" meant the HERO/OPENING screen; also: offer all strategy types product-wide,
  streamline discovery->construction):
  - WELCOME FITS ONE DESKTOP SCREEN (measured content-bottom 835/900 @1440, 799/800 @1280;
    mobile keeps the stacked scroll): .welcome-top grid = pitch LEFT (eyebrow/H1/sub/CTAs/trust)
    | LIVE engine proof RIGHT (showcase clamped ~250px w/ bottom fade mask — proof, not reading
    material); .welcome-bottom grid = the two altitude door cards (horizontal: icon|title+
    one-line blurb|button) LEFT | toolkit (2x2 icon+title chips, blurbs dropped at desktop) +
    4-step stepper (2x2, verb-only) RIGHT; slim footer row = sector cta-banner + Skip. A
    1100-1365px laptop band tightens hero/showcase further (btn-lg 38px there — the audit's
    sanctioned heights are [38,30,46]). All ids/copy the boot test pins survive (#welcome-live,
    #welcome-levels, #welcome-skip, "How do you want to start?", #how-it-works anchor).
  - LONG_STRADDLE + LONG_STRANGLE ARE FIRST-CLASS FAMILIES (StrategyFamily riskRank 2, VOLATILE
    thesis, DIRECTIONAL; StrategyBuilder.straddle(ATM call+put)/strangle(~30d both sides,
    degenerate-chain guard)); the engine iterates values() so a volatile view now OFFERS them
    automatically (pinned: volatileThesisOffersStraddlesAndStrangles — 2 legs, debit, maxLoss=
    debit, uncapped, 2 breakevens); backtest menu carries them under Trade a view (expert;
    beginner list unchanged); builder templates switched from CUSTOM to the real families so
    STRATEGY_GUIDE attaches; learn.js gained LONG_STRADDLE/LONG_STRANGLE guides.
  - PIPELINE STREAMLINE: every candidate card now has "Open in builder" (maps candidate legs
    incl. stock:true -> builderForm, step 4 = straight to Where-you-stand) — discovery feeds
    construction/analysis; Ideas tab row gained "All strategies →" (#all-strategies-link, sets
    builderForm goal:'BROWSE' step 2 -> the shape-card catalog). Flow is now: Ideas (discover)
    -> Builder (construct/analyze per-leg) -> Ticket review (execute) -> Backtest (verify).
  - Home dashboard re-verified with a GROWN account (200 shares + covered call + 2 spreads):
    content fits 1440x900 AND 1280x800 with real trade rows (shots/v3-home-grown.png).
  - Pinned after the fact (interrupted mid-pass, then finished): dom.test 'pipeline streamline'
    — expert candidate "Open in builder" loads its legs into the terminal and prices them;
    beginner "All strategies" link from Ideas lands on the shape-card catalog (>=24 cards).
    Dead-code sweep of all 5 JS files: zero unused functions/constants.
  - Suites: 223 JUnit + 22 fixture + 4 seeded + 8 live + 3 audit — ALL GREEN. Screenshots
    shots/v3-welcome-fold.png, v3-welcome-1280.png, v3-home-grown.png.
- BUILDER LIMITS + WIZARD BREADTH + EXPERT EDUCATION + ELEGANCE REPAIR (2026-07-07, user:
  expert needs expandable strategy/leg education; "why only four?" wizard options; compacted
  home/hero "look like crap" — usability/elegance/responsiveness top of the NFR pyramid,
  saved to memory: fit via OMISSION and HIERARCHY, never shrinking; builder lacked max-loss/
  profit/POP/assignment targeting — "did we miss streamlining this?"):
  - BUILDER "YOUR LIMITS" (both levels; builderForm.limits persists): beginner = 2 plain fields
    on Where-you-stand (max loss $, min POP %); expert = 4 inline in the bar (max loss, profit
    target, min POP, max assign). The live panel judges the priced position against each set
    limit (#builder-limit-chips, .limit-ok/.limit-fail — an impossible $150 cap on a $2.50-wide
    fixture spread fails HONESTLY). "Fit to my limits" (#builder-fit) calls POST /api/recommend
    {allowedStrategies:[family], maxLossCents, filters:{minPop,maxAssignmentProb}} and swaps in
    the engine-sized candidate (verified live: 250/245 credit put spread -> 242.5/240 under a
    $300 cap, 0 fail chips); CUSTOM/blocked structures get an honest refusal. This closes the
    streamline gap: the same screens as Ideas filters, wired into construction.
  - EXPERT EDUCATION: #builder-edu in the Legs card — "About this structure" (STRATEGY_GUIDE
    story/how/win/lose/watch) + "What each leg is for" (legStory per leg), UI.expandable,
    COLLAPSED by default (naked until asked). Refreshed on every legs render.
  - WIZARD BREADTH: WIZARD options may carry next:{prompt,options} — two-tier Q&A (st.wizNode,
    transient). DIRECTIONAL: Rise/Fall/Big-move/Calm/Land-on-a-price, each with 2-5 refinements;
    INCOME adds calendar + diagonal sub-questions (call/put twins); HEDGE adds the put-spread
    partial hedge. ALL 26 catalog structures now reachable via Q&A incl. naked put/call and
    short straddle/strangle as labeled BLOCKED lessons (badge on the choice card).
  - ELEGANCE REPAIR (measured AND eyeballed): welcome = hero|live top fold (sub copy SHORTENED
    to fit 2 lines rather than clip), two horizontal door cards, single-line step strip w/ ghost
    numerals, slim sector banner; Skip moved to a quiet top-right .btn-link in the live column
    (#welcome-skip id preserved); TOOLKIT SECTION DELETED (its copy duplicated hero+doors —
    omission, not compression). Home: normal paddings restored, company names back on tiles,
    quick actions iconified (.qa-head icon-tile + title + hint), self-evident card explainers
    hidden at desktop only. Fits: welcome 899/900 @1440 + 800/800 @1280; home 835 + 779.
    GOTCHA: fresh-DB "home" measurements actually measure WELCOME (hasTraded redirect) — click
    #welcome-skip first.
  - Tests updated/added (dom.test 22): two-tier wizard path (data-next choices, BLOCKED badge on
    short straddle), limits fail-chip at beginner + limits row/education expandables at expert.
  - Suites: 223 JUnit + 22 fixture + 4 seeded + 8 live + 3 audit — ALL GREEN. Screenshots
    shots/v4-{welcome,home,builder-limits}.png.
- ON-DEVICE AI ASSIST LAYER SHIPPED (2026-07-07, user: "all in on the web based llm approach…
  correctness remains paramount"; browser-first for deployment simplicity + WebGPU):
  - ARCHITECTURE: transformers.js 3.7.6 vendored (876KB min.js + 21.6MB ort jsep wasm) + two
    quantized ONNX models — Xenova/finbert (q8, 110MB, financial sentiment) and
    Xenova/nli-deberta-v3-xsmall (q8, 87MB, zero-shot) — ALL ON DISK at data/models (242MB
    total, NEVER in the jar). New AppConfig.modelsDir() (MODELS_DIR, default data/models);
    ApiServer mounts it EXTERNAL at /models only when the dir exists, with
    Cache-Control: public,max-age=604800,immutable (app assets stay no-store). js/assist.js
    (in jar, ~230 lines): detect via /models/manifest.json (absent -> features simply don't
    render), lazy dynamic-import of the runtime, env.allowRemoteModels=false (NOTHING ever
    fetched from a CDN), WebGPU when navigator.gpu offers an adapter, WASM otherwise
    (headless/test path = wasm = deterministic).
  - CORRECTNESS CONTRACT (enforced in code + tests): models NEVER produce a number the engine
    uses. Intake numbers come only from the user's own words via regex ($400, horizons,
    tickers vs the universe datalist + stopword filter); the model contributes CLASSIFICATION
    only (goal via 5 intent labels, thesis via 4 view labels, hypothesis-templated); an
    EXPLAINABLE tie-break (goalScore<0.35 + strong non-neutral thesis>=0.5 -> DIRECTIONAL,
    chip says "leaned directional — check me") fixes composite sentences like "TSLA drops
    hard, max $400" that zero-shot reads as hedging (29%). Apply FILLS the visible form
    (intent click, symbol, thesis, horizon, max-loss filter — opening the beginner expandable
    if needed) and focuses Find ideas — NOTHING auto-submits, pinned by test. Server
    SignalEngine/scout/backtester untouched: sentiment badges are display-only decoration.
  - FEATURES: (1) Ideas "Say it in your own words" card (#assist-intake, ON-DEVICE AI badge,
    dashed accent) -> Understand it -> parsed chips w/ confidences -> Fill the form. (2)
    Research news card: FinBERT badges per headline (label + confidence + title tooltip
    "display only — the engine's signals are unchanged"). Real-model behavior note: fixture
    "Apple beats expectations…" -> POSITIVE 82%; the regulatory-probe headline -> NEUTRAL 72%
    (defensible FinBERT verdict — do NOT pin model opinions in tests, pin the robust case +
    the format contract).
  - NEW SUITE dom-assist.test.js (3 tests, SKIPS wholesale when data/models absent): /models
    served w/ immutable caching; intake end-to-end (TSLA/bearish/month/$400 parsed + applied,
    zero candidates rendered = no auto-submit); news badges = valid label+confidence format +
    beats-headline positive + display-only note. First-run inference budget 240s (models load
    from localhost + wasm compile); subsequent runs ~4-8s/test.
  - ALL OTHER SUITES UNAFFECTED (models absent from their temp cwds -> /models 404 -> assist
    hidden): 223 JUnit + 22 fixture + 4 seeded + 8 live + 3 audit + 3 assist — ALL GREEN.
    Screenshots shots/ai-{intake,news}.png. Licenses: transformers.js Apache-2.0; deberta
    conversion cross-encoder/MIT-ish; ProsusAI FinBERT — verify license before any public
    distribution (fine for personal local use). Next AI steps live in memory
    project-browser-llm-direction (candidate re-ranking, filing reader, WebLLM tier).
- AI LICENSING FIX: SHIP NO MODEL BYTES + FINBERT REMOVED (2026-07-07, user approved recs 1+2;
  product is FOR DISTRIBUTION): FinBERT DELETED from data/models (HF weights repo has NO license
  tag; fine-tuned on Financial PhraseBank CC BY-NC-SA 3.0 — never redistribute). Sentiment now
  runs on the SAME Apache-2.0 zero-shot model (labels good/bad/neutral news for the stock price;
  the beats-headline robust pin still passes). ONE model total.
  - api/AssistInstaller.java: user-initiated server-side download of 11 pinned assets
    (jsdelivr transformers.js 3.7.6 + HF Xenova/nli-deberta-v3-xsmall, ~121MB) with per-file
    SHA-256 verification (upstream drift fails LOUDLY, .part temp + atomic move, manifest.json
    written LAST as the installed marker); virtual-thread run, AtomicReference progress;
    GET /api/assist/status + POST /api/assist/install (202). The release artifact contains
    ZERO model bytes — pinned by ApiIntegrationTest order(24) (fresh MODELS_DIR: installed=false,
    filesTotal=11, honest bytesTotal, /models/manifest.json 404).
  - /models is now an EXPLICIT ROUTE (serveModelAsset), NOT staticFiles: Jetty caches not-found
    lookups, so files installed after boot 404'd until restart. GOTCHA #2: do NOT set manual
    Content-Length — Javalin gzips text responses → ERR_CONTENT_LENGTH_MISMATCH killed the
    dynamic import of transformers.min.js. Path-traversal guarded (normalize + startsWith),
    immutable cache headers, correct MIME for .mjs/.wasm.
  - assist.js: detect() via /api/assist/status → 'available' | 'installable' | 'absent';
    'installable' renders the ENABLE card (#assist-enable: size + licenses disclosed, install
    button, live progress polling, App.render() on completion — no restart needed, verified by
    smoke: empty dir -> UI install -> intake parse in the same session).
  - dom.test 23rd test pins the no-bytes contract (enable card w/ ~1xx MB + Apache-2.0 text +
    no #assist-text until installed); dom-assist re-pinned to zeroshot manifest.
  - Suites: 224 JUnit + 23 fixture + 4 seeded + 8 live + 3 audit + 3 assist — ALL GREEN.
    data/models on THIS machine now holds only runtime + the Apache-2.0 model (finbert gone).
- IA RESTRUCTURE (2026-07-07, user answered the four questions: Single workbench / Backtest folds
  under Trade / Ticker contextual only / Adaptive Home + Portfolio absorbs Account). Both stages
  shipped and green: 224 JUnit + 23 fixture + 3 audit + 4 seeded + 3 assist + 8 live.
  - STAGE 1 — merges + chrome: top nav is FIVE items (Home/Research/Trade/Portfolio/Data);
    welcome route KILLED — home(root,params) renders the tour for fresh accounts (hasTraded=false
    && !welcomed) or params[0]==='tour' (brand + Markets link → #/home/tour; welcome content
    wrapped in .welcome-page, CSS scoped via `#app:has(.welcome-page)` since data-route="welcome"
    no longer exists). Portfolio gained section pills #pf-sec-{positions,activity,account}:
    Activity = ledger table, Account = reset card (old #/account aliased). Tape is CONTEXTUAL
    (home+research only): app.js toggles .tape-offroute; NEVER rebuild the marquee while hidden —
    display:none measures zero widths → data-stale attr + rebuild on next visibility. Sector
    selector left the tape entirely.
  - STAGE 2 — single Trade workbench, four numbered stages `Discover | Shape | Verify | Place`
    (WB_STAGES pills + ideaBar under one h1 'Trade'): Discover hosts scout/manual tabs +
    "All strategies" link (→ Shape w/ goal BROWSE); Shape = Builder.render; Verify = backtest
    (h1 removed, idea bar keeps context); Place = ticket. `trade(root,params)` dispatches
    `/^tr_/` → tradeDetail, else workbench. Route ALIASES in app.js keep every old hash working:
    recommend→trade/discover, backtest→trade/verify, ticket→trade/place, ticket/builder→
    trade/shape, account→portfolio/account, welcome→home/tour.
  - IDEA BAR (#idea-bar): reads App.state.ticket — chip = symbol + structure + qty, 'Place →'
    on non-Place stages, #idea-clear; honest muted line when empty. Candidate buttons set
    {candidate, symbol, step:5} and navigate #/trade/place.
  - TICKET DEDUP (the point of the merge): steps 1–4 (thesis/horizon/risk/strategy) DELETED from
    ticket() — Discover IS that flow. Place = 3 steps Strikes/Review/Confirm (internal t.step
    stays 5..7 so review/confirm code is untouched; displayed dot = i+1). No working idea →
    emptyState 'Nothing to place yet' + Discover CTA + builder button; custom legs skip the
    strike picker (t.step=6). nav(step<5) routes back to #/trade/discover/manual. Expert
    quick-start block + choices() helper deleted with it.
  - TEST REWRITES: dom.test 'discover-to-place' pins empty-state + the one-flow contract
    (discover manual → candidate button → Strikes/Review/Confirm → detail); dom-live ticket flow
    same shape. Candidate button LABEL DIFFERS BY LEVEL — select
    'button:has-text("Practice this trade"), button:has-text("Use in trade ticket")'. Playwright
    must wait on LANDMARKS not data-route (routes collapsed under 'trade'): detail=#refresh-btn,
    place=#to-confirm, shape=#builder-legs .leg-row. Screenshot scripts must waitForTimeout ~900ms
    after waitForSelector or View Transitions produce mid-crossfade frames; playwright module only
    resolves from dom-tests/ (run scripts from there).
  - Copy sweep: killed lingering three-tier language — backtest strategy-menu note now says
    Beginner/Expert ('unlock at Expert'), comments updated. Two levels ONLY, everywhere.
    Screenshots: dom-tests/shots/wb-{discover-beginner,discover-results,place-strikes,place-empty,
    verify,shape-expert}.png.
- UX-REGRESSION REPAIR (2026-07-07, user was FURIOUS about the IA restructure — six confirmed
  regressions, all fixed; suites after: 224 JUnit + 23 fixture + 3 audit + 4 seeded + 4 assist +
  8 live, ALL GREEN; screenshots shots/fix-*.png; standing lesson saved to memory
  feedback-streamline-not-remove):
  - NAV DOUBLE-HIGHLIGHT: app.js literally marked Portfolio active whenever route==='trade'
    (leftover clause). Removed — exactly one nav item highlights.
  - HERO WAS DELETED, NOT STREAMLINED ("hero page was a beautiful, non intimidating opening…
    I said streamline it with home"): the dashboard now opens with a .home-hero band carrying
    the welcome DNA — gradient, eyebrow, "Learn options by *doing*." title, the four account
    stats inside the band, and CTAs "Find an idea" + "Take the tour" (#home-tour-link →
    #/home/tour, the full welcome page, still the boot experience for fresh accounts). Desktop
    ≥1100px collapses the band to one row (sub OMITTED, stats nowrap-compact) — home content
    bottom measured 790px at BOTH 1440x900 and 1280x800 (no scroll). Markets-card "Welcome
    tour" link removed (the band CTA is the single affordance).
  - DOUBLE SUB-NAV KILLED: the Scout/My-own-idea TAB ROW under the stage pills is GONE.
    discoverStage is now ONE unified form (renderScout + renderManual deleted): goal chips
    (#intent-choices, 5 intents + "Everything"/ALL) + ONE symbol box (#rec-symbol, "blank =
    scan for me") + goal-aware fields. Blank symbol OR goal ALL → scout scan (universe/
    expirations/target-profit fields appear, POST /api/recommend/auto, grouped results);
    named symbol → single-symbol ideas (thesis/target/shares/horizon fields, POST
    /api/recommend + ladder extras). ALL+symbol scans universe=[symbol]. Holdings chips still
    one-tap for EXIT/HEDGE. State = App.state.discoverForm (one-time migration from
    ideasForm/scoutForm; explorer "Scout this sector" writes it). Route aliases preserved:
    /recommend/manual seeds a symbol, /recommend/scout clears it. REMOVED ids: #tab-scout,
    #tab-manual, #auto-go, #auto-results, #scout-goal, #scout-goal-blurb, #auto-maxrisk
    (#auto-target/#auto-universe/#auto-h-*/#universe-sector live on in scan mode; button is
    always #rec-go, label swaps Find ideas / Scan for opportunities).
  - "BUY AT A DISCOUNT DOESN'T LET ME CHOOSE A TICKER": direct consequence of the unified
    form — every goal now has the symbol box + target-price presets; pinned in dom.test
    (ACQUIRE+QQQ shows the buy-price field) and shots/fix-discover-acquire.png.
  - DEAD-END NAV GATED: the Place stage pill is disabled (with an honest tooltip) until a
    working idea exists; direct #/trade/place still renders the empty state.
  - "THE AI IS DUMB" ("I think qqq will go down further, I want to buy some more" → "Earn
    income · 21%", Apply did nothing): (1) assist.js GOAL_RULES — deterministic, explainable
    keyword rules run BEFORE the model (buy/add…more/cheaper→ACQUIRE, sell/trim…shares +
    take profits→EXIT, protect/hedge/insure→HEDGE, income/premium/get paid→INCOME); the chip
    quotes the matched words ("your words: “buy some more”"), no fake confidence. (2) Lowercase
    tickers resolve against the universe datalist ("qqq"→QQQ); unknown symbols still require
    CAPS. (3) No keyword + goalScore<0.45 → goalUncertain: the chip says "not sure — pick the
    goal yourself" and Apply does NOT click a goal. (4) Apply now targets the unified form (one
    form = nothing to miss), calls sync(), and scrolls/focuses #rec-go. dom-assist gained the
    exact user sentence as a regression test (4 assist tests now). Research "Get ideas" sets
    ideasPrefill so its symbol always lands in the form.
  - Boot test now pins the hero band + #home-tour-link; scan test pins blank-symbol mode +
    button relabel; goal-radio test pins ALL-replaces + ACQUIRE-with-ticker showing the
    buy-price field.
- UX-REGRESSION REPAIR ROUND 2 (2026-07-07, user: unified Discover lost the scout's feel; "what
  the heck is Shape… how has backtesting died and gotten replaced with verify?"; ticker amnesia
  across stages (qqq→AAPL); "the AI… is bad… get rid of that"; "where has news gone?" + demand:
  news/corporate actions/events integrated in research AND trade at both levels. ALL SHIPPED;
  suites: 223 JUnit + 22 fixture + 3 audit + 4 seeded + 8 live — ALL GREEN; shots/r2-*.png):
  - STAGE NAMES ARE PLAIN WORDS: labels now `Ideas | Builder | Backtest | Place` (keys/URLs
    stay discover/shape/verify/place for stability), wizard numbers dropped, every "Discover/
    Shape/Verify" string swept from copy (builder beginner wizard step 2 renamed 'Structure').
    RULE: section names must be words a first-time user already knows.
  - SCOUT FEEL RESTORED: the blank-symbol-means-scan idiom was too clever. Discover asks two
    explicit questions: goal chips, then "Where should ideas come from?" — `#idea-source`
    choice chips `Scan the market for me` / `I have a stock in mind` (the two old tabs as
    in-card form controls, both levels; beginner cards w/ blurbs, expert compact). Source
    drives mode; symbol box only in one-stock mode (always seeded, never blank); holdings
    chips switch to one-stock mode when tapped; goal "Everything" still scans (one stock →
    universe=[symbol]). discoverForm gains `source`; /recommend/manual → single,
    /recommend/scout → scan.
  - TICKER FOLLOWS YOU: typing in the Ideas symbol box updates App.state.lastRecommendSymbol
    immediately (not only on submit); Backtest's #bt-symbol and the Builder seed from it.
    Pinned in dom.test (type QQQ in Ideas → #/trade/verify shows QQQ).
  - STALE-ASYNC RACE FIXED (found via an order-dependent test failure): renderTargetPresets +
    refreshHoldingsHint now carry sequence tokens — a slow quote response for the PREVIOUS
    symbol could overwrite the new symbol's %-preset prices (same class as the chain-load
    token). Pattern: EVERY async fill of a shared container needs a supersede guard.
  - AI ASSIST FULLY REMOVED (user decision, recorded in memory): assist.js, intake/enable
    cards, news badges, /api/assist/* + /models routes, AssistInstaller.java,
    AppConfig.modelsDir(), MODELS_DIR docs, dom-assist suite, the fixture AI test, and
    data/models on disk (~109MB freed) — all deleted. JUnit drops to 223 (assist no-bytes
    test gone with the feature). Do NOT reintroduce AI UI without an explicit ask.
  - NEWS NEVER VANISHES: research #news-card ALWAYS renders — items w/ dates or an honest
    empty state + "Check data status" (it was silently skipped when the fetch failed/empty —
    that's what read as "where has news gone"). Filings (source SEC EDGAR / headline "…
    filing") group under a 'Corporate filings (SEC)' subheader w/ beginner explainer
    (10-K/10-Q/8-K in plain words).
  - EVENTS INTEGRATED: shared `comingUp(symbol, asStrip)` — expiration chips w/ DTE, an
    "Earnings in the news" chip (EARNINGS_RE on headlines), latest-SEC-filing chip (all
    link out). Research gets the card (#events-card, beginner explainer, honest note when
    nothing dated); single-symbol trade ideas get the slim strip (#events-strip) above
    results (vanishes quietly if empty — decoration there). Pinned in the research test.
  - Ex-div/true earnings CALENDAR still has no keyless source (documented gap; headlines
    are the honest proxy).
- SECTOR LANGUAGE + SYMBOL CONTEXT + FILTER VOCABULARY (2026-07-07, user: sector pills
  "littered" on home+research, trade's sector <select> "meh"; explorer tiles should expand to
  charts+news; universe stocks must stay visible with a symbol in the box; filters "make no
  sense… wtf is min pop… why annual yield… max risk and max loss overlap"; wants an expandable
  underlying chart/news card in Trade. Suites: 223 JUnit + 22 fixture + 3 audit + 4 seeded +
  8 live ALL GREEN; shots/r3-*.png):
  - `sectorRail(opts)` is THE sector affordance everywhere: one horizontally-scrolling rail
    (mask-fade edges like the tape) of .sector-chip pills w/ ETF-proxy day moves (.sr-delta
    fills async via SECTOR_ETF_MAP; :empty hides). Used by Home pulse (renders instantly now,
    not after quotes), Research explorer (#sector-chips), and Trade's scan scope
    (#universe-sector — REPLACED the <select>; onPick still PUTs /api/universe globally).
    Explorer's chip wall and home's ad-hoc chips are gone.
  - `symbolPanel(symbol, opts)` = quote row + THE SAME UI.rangeChart as Research (1M…MAX
    pills, candles, DEMO badge) + comingUp strip + top-3 headlines (+ Full research / Trade
    ideas actions unless noActions). Used twice: sector-explorer tiles now EXPAND IN PLACE
    (accordion, .tile-expand grid-column 1/-1, tile.open border; tile buttons still win via
    closest('button,a') guard) and Trade one-stock mode gets a collapsed expandable
    "#symbol-context — What's happening with SYM" (rebuilt when the symbol changes). NO
    intraday ranges (1d/5d) — keyless daily candles only; ranges match Research exactly.
  - One-stock mode: #universe-sym-chips under the symbol box keeps the active universe's
    stocks one tap away EVEN when the box is filled (the datalist only helps mid-typing —
    that was the "had to delete AAPL to see the list" complaint). Seeded symbol behavior kept.
  - FILTERS SPEAK TRADER, NOT SCHEMA: expert row is now `Chance of profit ≥ %` /
    `Assignment risk ≤ %` / `Income rate ≥ %/yr` / `Cash outlay ≤ $` / `Worst case ≤ $`,
    each with a hover tooltip that explains honestly (POP is a model output pre-commission;
    assignment is the GOAL for exit/acquire; yield is share-backed income only and
    annualized so different-length trades compare — 0.5%/2wk ≈ 13%/yr; outlay=debit vs
    worst-case=risk budget) + a "Blank = no limit" footer. Builder limit labels aligned.
    Pinned: old jargon (Min POP/Max assign %/% of account) asserted ABSENT.
  - "MAX RISK, % OF ACCOUNT" REMOVED from both modes — it overlapped `Worst case ≤ $`
    (the user's "why percent? why not a fixed amount?"). maxRiskPctOfAccount no longer sent;
    the header risk mode still sizes by default; the $ cap is the per-idea budget. Engine
    API unchanged (field simply omitted).
  - Tests: rail click replaces selectOption in the charts test; new pins for tile
    expand/collapse, trade context expandable (same range pills), sym-chips beside a filled
    box, filter vocabulary. GOTCHA reaffirmed: any async fill of a shared container needs a
    supersede token (presets/holdings got theirs last pass; sectorRail deltas mutate only
    their own spans so they're safe).
- "PG/BA PRICE HISTORY EMPTY" (2026-07-07, user report): NOT a data bug — keyless live mode has
  NO candle source for non-demo symbols (Cboe = quotes/chains only; Stooq STILL serves its
  JS anti-bot wall, re-verified; no Polygon/AV key in the user's strikebench.properties —
  it holds only brand.*). The BUG was the message: rangeChart said "Not enough data for this
  window" (blames the window) for a no-source symbol. FIX: shared `historyFetch(symbol)`
  (research #history-card + every symbolPanel now use it) returns `emptyText` explaining
  exactly what's live, what's missing, and which key to set; rangeChart renders
  data.emptyText over the generic line. Pinned in dom-live (research/PG must NOT say "Not
  enough data", must say Polygon/Alpha Vantage — or show real/demo data when a key exists).
  USER ACTION for real PG/BA candles: add `polygon.api.key=...` (or alphavantage.api.key)
  to strikebench.properties, or export POLYGON_API_KEY, and restart. Suites: 22 fixture +
  8 live green after.
- RAIL OVERFLOW + BUILDER RECOVERY + SECTOR PATH (2026-07-07, user: rail "vanishes into the
  horizontal end everywhere"; beginner builder "failed to load aapl and keeps trying" after
  choosing another stock; no way to switch sector from a research symbol page). Suites:
  23 fixture + 3 audit + 4 seeded + 8 live green; shots/r4-*.png:
  - sectorRail now returns a `.sector-rail-wrap` (the id moves to the WRAPPER — descendant
    test selectors unchanged) with 30px circular scroll arrows that appear ONLY when content
    is off-screen in that direction (.can-left/.can-right via scroll listener +
    ResizeObserver; deltas re-measure after filling); the edge fade also applies only on
    overflowing sides. A fade with no affordance read as "the list just ends".
  - Builder symbol-load failure is a RECOVERABLE state (#builder-load-error: honest alert +
    symbol input + Retry + one-tap universe chips), never a dead-end alert. And st.symbol
    precedence fixed: an EMPTY builder follows App.state.lastRecommendSymbol (the working
    symbol from Ideas/Research); only an in-progress build (legs.length) keeps its own —
    the "keeps trying to load AAPL" trap was saved builderForm.symbol always winning.
    render() now calls remember() after a successful load so the followed symbol persists.
  - Research symbol pages get `#back-to-sectors` ("← All sectors") beside Look up.
  - TEST GOTCHA (hit again): go(sameHash) renders NOTHING (no hashchange) — leave the route
    first or drive App.render() and wait on STATE, not on selectors that already exist.
- RECENTS + EXPLORER FOCUS + PORTFOLIO CONSISTENCY (2026-07-07, user: lookup chips "never
  change, why are they special?"; mid-grid tile expansion "strange and random"; portfolio
  "left behind… weird spacing… not consistent". Suites: 23 fixture + 3 audit + 4 seeded +
  8 live green; shots/pf-after-*.png, explorer-focus.png):
  - Research lookup chips are now RECENTLY RESEARCHED symbols (#recent-symbols, labeled
    "Recent:", localStorage strikebench.recent, max 6, current highlighted) — recorded after
    a symbol loads and re-rendered immediately (renderRecents(); first visit shows itself).
    No recents = no chips. The frozen universe slice is gone.
  - Explorer tiles now open the symbol panel in ONE FIXED slot (#explorer-focus, between the
    sector head and the grid) with a 30px ✕ close (.focus-wrap/.focus-close, xp-in animation,
    reduced-motion guarded); source tile gets .open highlight; sector switch clears the
    focus. Mid-grid .tile-expand accordion (shoved tiles around) is gone.
  - PORTFOLIO PASS: Book greeks = slim one-line strip (.card-slim, chips + PARTIAL inline);
    trades are ONE #trades-card — cardHeader('Practice trades', .seg Active|Closed segmented
    control [ids #tab-active/#tab-closed kept]) + explain at top + #pf-filters INSIDE the
    card + table + btn-sm pagination + empty state, all in the card. The second .tabs row,
    the floating filter strip, and the orphaned pager are gone. Empty-state CTA now goes to
    #/trade/discover (Place is gated when idea-less). .seg CSS = 30px pill toggle (audit-safe).
  - GOTCHA (cost one lost edit batch): views.js mixes LITERAL unicode (…, ←, →, Δ in most
    strings) with \\uXXXX ESCAPES (greek chips) — grep the exact bytes before writing python
    old-strings, and put each independent replace in its own assert so one mismatch can't
    discard sibling edits.
- LEVEL-GEOMETRY SYMMETRY + TAPE RESIZE (2026-07-07, user: home spacing changed between
  Beginner/Expert — "the sort of bad UI and UX we want to avoid, probably lurking everywhere";
  ticker "ends with a gap and then the wrap around comes back". Suites: 25 fixture + 3 audit +
  4 seeded + 8 live green):
  - STANDING LAW (also in memory): levels differ by CONTENT (explainers, columns, features),
    NEVER by padding/font-size alone. The global `body.lvl-expert` density block (.card/.stat/
    .tbl/.chip/.candidate/h1 padding+font overrides) was pure reflow with identical data —
    DELETED; both levels share one geometry everywhere. PERMANENT PIN: dom.test 'levels share
    ONE geometry' compares computed padding/margins/font-size on home across the toggle.
  - TAPE WRAP GAP root cause: the marquee halves are sized ONCE at build; growing the window
    past the built half-length leaves a blank region each cycle before the wrap re-enters
    (the seam itself was verified exact — six equal sequences, strip = 2× half to the pixel).
    FIX: strip stores data-halfw; a debounced ResizeObserver on .tape-scroll forces a full
    rebuild (clears data-symbols so the in-place path can't skip re-measuring) whenever the
    container outgrows the halves. PIN: build at 900px, grow to 1600px → data-halfw grows and
    covers the viewport. Note: tiny universes (4 symbols) necessarily repeat symbols to span
    wide screens — duplication is the minimum needed to cover the viewport.
- DEPLOYED TO strikebench.com (2026-07-08): EC2 (Amazon Linux 2023, x86_64, Corretto 25 +
  Maven preinstalled), repo clone at /home/ec2-user/output/strikebench
  (github.com/privacynow/strikebench — byte-identical to the local tree at deploy time).
  App runs from a DEDICATED dir /opt/strikebench (jar + strikebench.properties [brand only;
  polygon/AV keys go here] + data/strikebench.db), systemd unit `strikebench.service`
  (User=ec2-user, PORT=7070 localhost-only, DB_PATH under /opt, -Xmx768m, Restart=on-failure).
  nginx server block /etc/nginx/conf.d/strikebench.conf proxies :80/:443 → 127.0.0.1:7070
  (other domains live inline in nginx.conf — untouched); Let's Encrypt cert via
  `certbot --nginx -d strikebench.com --redirect` (auto-renew timer active; expires Oct 6).
  WWW NOT covered: Let's Encrypt got DNS SERVFAIL for www.strikebench.com (apex validated
  fine) — fix the www record/DNSSEC at the registrar, then
  `sudo certbot --nginx -d strikebench.com -d www.strikebench.com --redirect`.
  REDEPLOY = run scripts/deploy.sh ON the server (git pull → mvn package → atomic jar swap
  → systemctl restart → health check). SSH: `ssh -i /Users/tinker/output/output/flinkey.pem
  ec2-user@strikebench.com`. Verified live: HTTPS health OK, http→https 301, REAL delayed
  Cboe quotes through the proxy (AAPL $311.39 DELAYED). CAVEAT (flagged to user): the app
  has NO authentication — every visitor shares the single paper account; nginx basic auth
  is the quick lock if wanted.
- PORTFOLIO HEADLINE + GENERIC DEPLOY + UPDATE LOOP (2026-07-08; suites 224 JUnit + 26
  fixture + 3 audit + 4 seeded + 8 live green; deployed live, commit d54b37c):
  - `GET /api/portfolio/summary` — the honest liquidation view: cash (+reserve is a lien
    INSIDE cash, never added twice) + Σ positions.marketValueCents + Σ closeCostCents of all
    ACTIVE trades (TradeService.openPositionsValue: computeMark per trade, executable sides,
    PRE-close-fee; partial marks → complete:false + worst-of freshness). totalPnlCents =
    totalValue − startingCash. Identity pinned in ApiIntegrationTest Order(24) and in the
    dom.test portfolio-headline test (asserts the sum via API.getFresh from the page).
  - Portfolio header now leads with `Portfolio value` (PARTIAL badge when marks incomplete)
    + `P/L since start` ($ and %, gain/loss colored) + Cash (reserve named in the explainer)
    + Buying power; falls back to plain account stats when the summary call fails.
  - scripts/deploy.sh is now MACHINE-GENERIC: env overrides REPO/APP_DIR/SERVICE/PORT/
    BRANCH/RUN_USER/JAVA_BIN/JAVA_HEAP (+SKIP_PULL=1 for tree-as-is), `--install` bootstraps
    APP_DIR/properties/systemd unit, `--setup-timer` installs an OPTIONAL systemd timer that
    polls origin/BRANCH every 5 min and deploys only on new commits (`--if-changed`),
    nginx/certbot stay outside (documented in the header comment).
  - UPDATE LOOP DECISION: manual `scripts/deploy.sh` on the server after a push (deliberate,
    simple); the poll timer exists but is NOT enabled — enabling it means unreviewed pushes
    go straight to prod.
- LEARN-BY-TOUCHING BUILDER (2026-07-08, user: beginner autopilot "chooses everything…
  how else do we learn?"; wants the CHART as the tweaking tool + a numeric escape hatch +
  actionable breadcrumb steps. Suites 224 JUnit + 26 fixture + 3 audit + 4 seeded + 8 live;
  deployed live; shots/bld-chart-handles.png, bld-stand-tune.png):
  - UI.payoffChart gains opts.handles [{id,strike,label,strikes[],onChange}]: draggable
    grips (SVG circles + dashed strike lines) that snap to the leg's REAL chain strikes
    while dragging and fire onChange on release → the owner re-prices; worst case/best
    case/odds move live. Each handle gets its OWN row (hi % 4 stagger — shared rows mash
    condor labels), labels ride BESIDE the grip (anchor flips near the right edge), the
    'now' spot label moved to the plot BOTTOM (the top strip belongs to handles). Grip
    pointer events stopPropagation so the crosshair stays quiet; .xhair pointer-events:none.
  - builder.js strikeHandles(indices, onMoved) builds them from st.legs + ensureChain;
    wired into ALL THREE charts: beginner step-3 walkthrough (tweak WHILE learning the leg),
    beginner Where-you-stand (onMoved=repaint), expert panel (onMoved=onLegsReplaced so the
    leg grid re-renders too; excluded legs get no handle). renderVerdictAndStats gained a
    5th `handles` param.
  - Beginner escape hatch: 'Fine-tune each leg — exact strikes and dates' expandable
    (#bw-tune) on Where-you-stand: per-leg strike + expiration selects (chain-fed; expiry
    change snaps to the nearest strike on the new chain), stock legs honestly labeled.
  - Wizard steps are BUTTONS now (.step-link when earned, disabled+title when not):
    Goal always, Structure needs a goal, Build-it/Where-you-stand need legs. CSS keeps the
    old look (bg none) — audit only measures input/select/.btn/.goal-chip so step buttons
    are exempt.
  - TEST GOTCHAS reconfirmed: <option> is never "visible" to waitForSelector (wait on
    querySelectorAll().length); mouse events on below-fold elements silently no-op
    (scrollIntoViewIfNeeded BEFORE boundingBox); tune-row index must match the LEG index
    asserted.
- BRAND MARK + RECOVERABLE WELCOME + DOCS SPLIT + AUTO-DEPLOY (2026-07-08; suites 224 JUnit
  + 26 fixture + 3 audit + 4 seeded + 8 live; deployed; shots/welcome-v2.png):
  - NEW MARK: gradient tile, a flat "bench" line kinking upward at a strike dot over a dashed
    baseline (the name, drawn). Inline SVG in the header brand anchor, URL-encoded SVG
    favicon in index.html, `UI.brandMark(size)` (unique gradient id per call) for the hero.
    GOTCHA FIXED: applyBrand did `el.textContent = name` which WIPED the inline SVG — it now
    replaces only text nodes.
  - Brand anchor href → #/home/tour: the mark ALWAYS reopens the welcome page ("skip" is not
    goodbye). Boot test pins brand→#welcome-hero + the mark's presence; #home-tour-link on
    the dashboard hero band remains the second path.
  - Welcome polish: .hero-brandline (mark beside the eyebrow — inline, adds no height),
    .hero-deco quiet condor-tent SVG motif behind the pitch (aria-hidden, opacity .10),
    accent-topped door cards (blue/violet). Fold verified: 885/900 at 1440.
  - DOCS: README.md rewritten for END USERS (two-minute start, what you can do, honest
    numbers, optional keys table, good-to-know); everything developer-facing moved to NEW
    DEVELOPER.md (build, principles, architecture tree, all four DOM suites w/ commands,
    full config table, deploy.sh usage). The old README described dead UI (3-tier ladder,
    tape sector switcher, 7-step ticket).
  - AUTO-DEPLOY ENABLED on strikebench.com (user approved; main will be branch-protected/PR
    soon): `deploy.sh --setup-timer` → strikebench-autodeploy.timer polls origin/main every
    5 min and deploys only when it moves. A push is now live within ~5 minutes. Disable:
    `sudo systemctl disable --now strikebench-autodeploy.timer`.
- WELCOME SYMMETRY (2026-07-08, user: "the welcome/hero page lacks symmetry still"; commit
  57c5290, 26 fixture + 3 audit green, auto-deployed): the top fold was 1.05fr/0.95fr with
  only the hero framed (the live column floated, offset by a stray 12px section margin), the
  hero mixed a left brandline with centered title/sub/CTAs, and the steps were a wrapping
  flex rag. NOW: `.welcome-top > *` share ONE frame and stretch to one grid row (measured
  504/504 wide, 354/354 tall, tops aligned, fold 889/900 at 1440), columns exactly 1fr 1fr,
  hero left-aligned throughout, eyebrow rows of both panels on the same line, steps a strict
  repeat(4,1fr) grid of mirrored cells. LESSON: prove symmetry with getBoundingClientRect
  measurements (widths/heights/tops), not by eyeballing a screenshot — the 12px offender was
  `#app:has(.welcome-page) .welcome-section { margin: 12px 0 0 }` leaking into the grid
  child, killed via `.welcome-top > .welcome-section { margin: 0 }`.
- AUTO-DEPLOY INCIDENT + FIX (2026-07-08, caught while confirming a push): the timer's FIRST
  tick pulled the repo then died with `mvn: command not found` (systemd's minimal PATH lacks
  /opt/maven/bin) — and because the pull had already moved HEAD, every later tick compared
  HEAD==origin and exited 0 "nothing new". A FAILED DEPLOY POISONED ALL FUTURE CHECKS while
  the timer looked healthy. FIXES in deploy.sh: (1) --setup-timer bakes the resolving shell's
  mvn/git dirs into the unit's PATH; (2) freshness is now a SUCCESS MARKER — deploy() writes
  $APP_DIR/.deployed-rev only after the health check passes, and --if-changed compares
  `git ls-remote origin refs/heads/BRANCH` (no local side effects) against that marker, so a
  half-failed deploy retries next tick instead of going quiet. LESSONS: never gate retries on
  state mutated by the failed attempt itself; verify a new automation's FIRST real run in the
  journal, not just that its timer ticks; systemd units need explicit PATHs for login-shell
  toolchains.
- RESEARCH-PLATFORM DIRECTION + PHASE 1 STORAGE MIGRATION (2026-07-08, feature branch
  `feature/research-platform` off `feature/volatility_lab`; NOT deployed — strikebench.com stays
  on main until merge). Product reframed (both reviewers + user aligned): StrikeBench becomes an
  evidence-first, portfolio-aware, capital-efficient **trade-discovery & recommendation** engine —
  recommendations stay THE product; the scanner/backtester/vol-analytics/optimizer/notebook are
  the machinery that makes each recommendation better. Organizing idea: **recommendations-as-a-
  competition** (rank viable alternatives, show why one wins + what could fail + the management
  plan). Standing rules reinforced (in memory): never defer/exclude — sequence by IMPACT not ease,
  finish everything; never remove features. Honesty non-negotiable: per-dimension evidence badges
  (Observed/Modeled/Paper/Live, worst-of rollup — generalizes the existing Freshness pattern);
  annualized-ROC is a labeled component, never the primary rank; single score never stands alone.
  5-phase plan (all being built, impact-ordered): P1 data foundation (Postgres + reworked model +
  Flyway + faithful migration + forward snapshots + auth) · P2 StrategyEvaluation backbone
  (StrategySpec + producer modules: Capital[incr+economic]/Volatility[IV rank/percentile/VRP/
  expected move]/Risk[+tail+scenario]/Evidence/ManagementPlan/Score[gate→normalize→risk-adjusted]/
  Explanation) · P3 recommendations-as-competition (OpportunityScanner + comparison UI + ordered
  decision page + scrubbable management plan) · P4 real evidence (owned CSV-bulk historical options
  + backtester rewrite [portfolio/rolls/exits/delta-selection] + calibration loop) · P5 research lab
  (portfolio optimizer + hypothesis tester + notebook + ETF replication).
  DATA DECISIONS: local dev = Docker Postgres via compose (bare Postgres on the box for prod;
  off-box backups); own the past via one-time CSV-bulk historical options (commercial/internal-use
  license, NOT redistribution — derived output only) + own the future via forward snapshots;
  subscriptions rent (lose the data on cancel) so they're optional add-ons; Flyway for migrations;
  one user now via Google OIDC (pac4j) on a multi-user schema; user count doesn't affect data cost.
  PHASE 1 STORAGE MIGRATION DONE + VERIFIED (commits a7ff865, 3db7b74, 49b80e4):
  - SQLite -> **PostgreSQL 16** everywhere. `Db` is now a HikariCP-pooled Postgres helper keeping
    the exact with/tx/exec/query/Row API (all ~25 call sites untouched), AutoCloseable, exposes
    dataSource() for Flyway; ApiServer owns+closes the pool on stop(). Booleans stay integer 0/1
    (zero churn); paper-domain timestamps stay ISO-TEXT; new tables use timestamptz/date/numeric/jsonb.
  - `Migrations` -> **Flyway** (classpath:db/migrations). Flyway accepts Java 25 cleanly.
  - Schema V1 (Postgres) collapses old SQLite V1+V2+V3 final state + adds the product's new core
    entities: `users`(+user_id FKs), `option_bar` & `underlying_bar` (ONE table each holds our
    forward snapshots AND vendor history, per-dimension evidence cols, date-indexed),
    `strategy_evaluation` (typed rank columns score/ev/roc/pop/capital/tail + jsonb sub-profiles),
    `recommendation` (+outcome fields for calibration). BIGINT cents, NUMERIC(19,4) prices, DOUBLE
    only for ratios. App SQL was already Postgres-ready (ON CONFLICT/excluded upserts; no OR REPLACE).
  - LOCAL DEV: `docker-compose.yml` (pinned Postgres 16, localhost only, dev+test dbs via init
    script; optional `app` profile + Dockerfile for full-stack smoke). App runs NATIVELY for the
    inner loop; AppConfig gains DB_URL/DB_USER/DB_PASSWORD (default = compose db).
  - TESTS ON POSTGRES: `src/test/.../support/TestDb.java` creates a fresh isolated Flyway-migrated
    database per JUnit test in the running dev Postgres (Testcontainers proved UNRELIABLE on
    Java 25 + Docker 29 socket detection — even pointed at the socket it "Could not find a valid
    Docker environment"; the compose Postgres is simpler and robust; TEST_DB_* overrides for CI).
    `dom-tests/pgtest.js` does the same per spawned jar (jar migrates via Flyway on boot); the
    seeded suite's legacy-collar injection moved sqlite3-on-file -> psql-on-Postgres. PREREQ:
    `docker compose up -d db` before tests. FULL MATRIX GREEN ON POSTGRES: 224 JUnit + 26 fixture
    + 3 audit + 4 seeded + 8 live DOM.
  - PHASE 1 COMPLETE (all shipped + verified on this branch; NOT deployed — main stays SQLite until merge):
    * Package moved io.liftandshift -> **io.liftandshift.strikebench** (Java package only; Maven groupId
      stays io.liftandshift). Shade mainClass io.liftandshift.strikebench.Main. 99 files git-mv'd.
    * Forward chain-snapshot recorder (the moat): market/SnapshotService writes a daily EOD snapshot of
      the active universe's chains + underlying into option_bar/underlying_bar (source='snapshot', honest
      per-dimension evidence: observed/bid_ask_observed/iv_source/greeks_source — fixtures flagged
      not-observed so they never masquerade as real). Off by default (SNAPSHOT_ENABLED); daily scheduler
      + POST /api/admin/snapshot. The two new evidence cols are INTEGER 0/1 (Db-helper boolean convention).
    * SQLite->Postgres ETL: db/SqliteToPostgresEtl introspects both schemas, copies the column INTERSECTION
      in one tx (PG-only cols like user_id keep defaults), coerces to exact PG types, preserves ids +
      resets IDENTITY sequences, verifies row counts + the ledger invariant. `java -jar strikebench.jar
      etl <sqlite>` (Main dispatch). Supersedes the dormant Main.migrateLegacyDefaultDb.
    * Google OIDC auth (Nimbus oauth2-oidc-sdk, NOT pac4j — no Javalin-7 adapter exists; Nimbus is pac4j's
      own OIDC engine so crypto still isn't hand-rolled) + per-user scoping. OFF by default (AUTH_ENABLED)
      so local/keyless + the whole suite are byte-identical. auth/{AuthService,GoogleOidcProvider,
      IdentityProvider,VerifiedIdentity,UnauthorizedException}; session gate on /api/* (health/config/
      status/auth open); users provisioning; AccountService.getOrCreateDefaultForUser (owner claims the
      legacy account, others get fresh); SPA sign-in screen + header sign-out. Jetty ee10 SessionHandler
      wired only when enabled.
    * ADVERSARIAL SECURITY REVIEW (5 lenses x verifiers, 6 confirmed findings, ALL FIXED): (1) IDOR on
      trade-by-id routes -> ensureOwnedTrade ownership check (404); (2) session cookie HttpOnly; (3)
      /api/account/reset scoped to caller (AccountService.resetAccount by id, was global-oldest); (4)
      cookie Secure behind the TLS proxy via ForwardedRequestCustomizer honoring X-Forwarded-Proto
      (AUTH_COOKIE_SECURE; nginx must send proxy_set_header X-Forwarded-Proto $scheme); (5) /api/audit
      scoped to caller's account when auth on (AuditLog.pageForAccount); (6) orphan-claim TOCTOU ->
      guarded UPDATE ... WHERE user_id IS NULL + rowcount. Plus session-fixation rotation, constant-time
      state, nonce, verified-email + allowlist, fail-closed on missing OIDC creds.
    * Prod Postgres: scripts/provision-postgres.sh (PG16 localhost/scram, role+db), scripts/
      backup-postgres.sh (pg_dump->gzip->S3 + nightly timer), deploy.sh systemd unit passes DB_URL/DB_USER
      (password in strikebench.properties chmod 600), DEVELOPER.md cutover runbook. Scripts bash -n only
      (real runs are cutover-day ops on the box).
    * FULL MATRIX GREEN throughout: 239 JUnit (+15 for snapshot/etl/auth) + 26 fixture + 3 audit + 4
      seeded + 8 live DOM. New config keys: SNAPSHOT_*, AUTH_ENABLED, OIDC_*, AUTH_ALLOWED_EMAILS,
      AUTH_POST_LOGIN_URL, AUTH_COOKIE_SECURE.
- PHASE 2 COMPLETE + PHASE 3 BACKEND (branch feature/research-platform; NOT deployed). The
  StrategyEvaluation backbone that powers recommendations-as-a-competition:
  - `eval` package. Immutable contracts: EvidenceLevel (worst-of rollup, generalizes Freshness) +
    EvidenceProfile, CapitalProfile (incremental vs economic capital + LABELED annualized ROC),
    VolatilityProfile (IV rank/percentile from observed history/null-when-thin, VRP, expected move),
    RiskProfile (real payoff grid via PayoffCurve + stressed tail loss; nested Scenario),
    ManagementPlan (+Rule), ScoreBreakdown (gate->normalize->risk-adjust; +Component), Explanation,
    StrategySpec, StrategyEvaluation (aggregate w/ typed rank accessors). Candidate stays the concrete
    candidate (no duplication).
  - Seven producer modules (Capital/Volatility/Risk/Evidence/Management/Score/Explainer) + a pure,
    deterministic StrategyEvaluator (evaluate + evaluateAndRank: viable-first then risk-adjusted desc).
    RiskProfiler folds share-backed coverage in as a synthetic stock leg so scenarios reflect the
    COMBINED position. ScoreComposer: hard gate (finite risk / evidence!=UNKNOWN / buying power),
    weighted 6-component normalize, risk-adjust haircut by evidence uncertainty + tail. Demo/fixture
    data is haircut AND labeled DEMO_FIXTURE — never masquerades as observed.
  - EvaluationStore persists to strategy_evaluation (typed rank cols + JSONB sub-profiles; ?::jsonb
    binds; recent() casts ::text for the nullable-user filter). EvaluationService assembles the live
    EvalContext (underlying, DTE from front expiration, ATM IV from the chain, realized vol via
    HistoricalVol, and OBSERVED IV history from option_bar snapshots [iv_source='vendor' only, so a
    fresh/demo DB honestly yields null IV rank]) and ranks/persists.
  - APIs: POST /api/evaluate (one symbol -> ranked competition of full evaluations + rejected),
    GET /api/evaluations (recent per user), POST /api/opportunities (P3: universe-scale scan -> each
    symbol's best viable idea ranked cross-symbol; defaults to the active universe). recommend
    refactored to a shared resolveAndRecommend so evaluate reuses the holdings-injection path.
  - Verified: 248 JUnit (StrategyEvaluatorTest, EvaluationStoreTest, EvaluateIntegrationTest incl.
    opportunities) + 26 fixture + 3 audit + 4 seeded + 8 live DOM. New config keys: none.
- PHASE 3 COMPLETE — recommendations-as-a-competition, backend + UI (branch; NOT deployed):
  - OpportunityScanner: EvaluationService.scan evaluates a universe, keeps each symbol's best VIABLE
    idea, ranks cross-symbol; POST /api/opportunities (defaults to the active universe).
  - Decision/comparison UI: new #/decision route + Views.decision renders /api/evaluate as a ranked
    competition. THE PICK card = why-it-wins + honest capital pair (buying power used vs full economic
    exposure) + real payoff scenario strip (from RiskProfile.scenarios) + POP/assignment + co-equal
    management plan + (Expert) evidence-by-dimension + score breakdown + what-could-go-wrong. Ladder-
    tuned: Beginner = plain intro, plan OPEN, simple alt-rows; Expert = full dimensions + comparison
    table of the whole field. Demo data labeled 'Demo data' + honesty-haircut score. 'Compare side by
    side' entry from Discover; winner hands off to Place. CSS .decision-pick/.scenario-strip/etc.
  - Verified: 248 JUnit + 27 fixture (new decision DOM test) + 3 audit + 4 seeded + 8 live DOM; both
    levels screenshotted (shots/p3-decision-{expert,beginner}.png) — coherent, honest, no overflow.
- PHASE 4 IN PROGRESS — real evidence (2 of 3 shipped; branch, NOT deployed):
  - (a) DONE — Historical-options CSV bulk ingest: db/HistoricalOptionsIngest, header-mapped +
    vendor-agnostic (ORATS/Cboe/Databento aliases), loads a LICENSED CSV into option_bar tagged
    source=<vendor> + OBSERVED (bid_ask_observed=1, iv_source/greeks_source='vendor') + per-(symbol,
    date) underlying_bar; idempotent upserts, skips/reports bad rows. CLI `ingest-options <csv>
    [source]`. Once loaded, EvaluationService's observed-IV history query finds it -> IV rank real.
    Only derived rows stored (license: internal-use, no redistribution). +3 JUnit.
  - (c) DONE — Calibration loop: eval/CalibrationService records surfaced evals as recommendations
    (recommendation table), resolves outcomes, reports RELIABILITY (resolved recs bucketed by
    predicted POP vs realized win rate; well-calibrated == predicted~realized) + overall win rate +
    total P/L. /api/evaluate records the pick; GET /api/calibration; POST /api/calibration/resolve.
    outcome_asof bound as OffsetDateTime (TIMESTAMPTZ). +3 JUnit. (Auto-resolution from paper-trade
    closes needs a trade<->recommendation link — the manual/API resolve is the seam; follow-up.)
  - (b) DONE — PortfolioBacktester (additive; the single-position Backtester stays): CONCURRENT
    positions (capacity + capital gated), DELTA-based strike selection (short put ~0.30d / long call
    ~0.50d), mechanical management mirroring the ManagementPlan (profit-target / stop as a fraction of
    max loss / time-roll), intrinsic settle at expiry, no look-ahead (each day uses only candles <=
    that day). Portfolio equity = starting + realized + open unrealized; report has concurrentPeak,
    per-trade exit reasons, win rate, avg RoR, max drawdown, coverage, modeled confidence.
    CREDIT_PUT_SPREAD + DEBIT_CALL_SPREAD. POST /api/backtest/portfolio. +4 JUnit. Observed
    option_bar pricing per-day is the documented next step (needs a loaded dataset).
  - PHASE 4 COMPLETE. Suite: 258 JUnit + 27 fixture + 3 audit + 4 seeded + 8 live DOM.
- PHASE 5 COMPLETE — research lab (branch; NOT deployed). New `research` package, all API-exposed:
  - PortfolioOptimizer: greedy capital allocation across the competition's winners by objective
    DENSITY (score or EV per unit capital) under total budget + per-position cap + per-symbol
    concentration cap + max positions; only viable evals, every skip explained. POST /api/optimize
    (scans a universe, then allocates). +2 JUnit.
  - HypothesisTester: "after N-day momentum >= threshold, is the next M-day return positive more
    often than chance?" over candles with a two-sided z-test; honest verdict (too-few / not-
    significant / supported / rejected). POST /api/lab/hypothesis. +2 JUnit.
  - ETFReplicator: delta-1 synthetic (long call + short put, or reverse) sized to a target dollar
    exposure; reports contracts / delta exposure / share cost / est. margin + honest caveats.
    POST /api/lab/replicate. +2 JUnit.
  - NotebookService + Flyway **V2** (research_note): per-user saved analyses, full CRUD, per-user
    isolation (ownership-checked; ?::text null-safe filter). /api/lab/notes[/{id}]. +2 JUnit.
    (V2 is APPENDED — V1 unchanged, so fresh + existing DBs migrate cleanly, no checksum churn.)
  - ownerId(ctx) helper added for per-user persistence scoping.
  - A dedicated research-lab UI page is the optional follow-up; the four tools are complete +
    tested + exposed. Suite: 266 JUnit + 27 fixture + 3 audit + 4 seeded + 8 live DOM.
  ===============================================================================================
  ALL FIVE PHASES OF THE RESEARCH-PLATFORM PROGRAM ARE IMPLEMENTED + VERIFIED ON feature/research-
  platform (NOT deployed; main/strikebench.com unchanged until a deliberate merge). P1 data
  foundation · P2 StrategyEvaluation backbone · P3 recommendations-as-a-competition (+decision UI)
  · P4 real evidence (ingest + calibration + portfolio backtester) · P5 research lab.
  ===============================================================================================
- FINISH-THE-BUILD PASS (2026-07-08, user "finish it all" + standard UI drill; the 3 buildable follow-ups):
  - RESEARCH-LAB UI (#/lab, Views.lab; new Lab nav item desktop + mobile bottom-nav w/ stroke-SVG flask
    icon; #/lab added to the dom-audit walk). Four tools, ladder-tuned + responsive: Optimizer (budget/
    goal + expert knobs -> summary chips, SVG composition bar+legend, allocations table), Hypothesis
    tester (momentum form -> verdict banner + SVG win-rate gauge vs the 50% baseline + z/edge chips),
    ETF replicator (target-exposure -> synthetic structure + delta/cost/margin chips + efficiency gauge),
    Notebook (inline CRUD). Desktop uses the real estate (optimizer+notebook full-width, hypothesis|
    replicate 2-up); mobile stacks; hand-rolled theme-aware SVG (compositionChart/gaugeChart via el
    html:); no emoji (UI.icon); Beginner=plain+fewer knobs / Expert=dense+z-scores. Shots p5-lab-*.png.
  - AUTO-RESOLVE (calibration loop closes end-to-end): V3 migration recommendation.trade_id;
    /api/evaluate returns the pick's recommendationId; decision -> ticket -> trade-create body threads
    it; tradeCreate linkTrade; unwind/settle resolveByTrade (idempotent, WHERE outcome_status IS NULL).
  - OBSERVED BACKTEST PRICING: PortfolioBacktester(market,cfg,clock,db) marks each leg from option_bar
    (mark/mid) when a dataset is loaded, else BSM; report tier OBSERVED_FROM_HISTORY vs
    MODELED_FROM_UNDERLYING; /api/backtest/portfolio passes the pool. observedMarkDollars pkg-visible+tested.
  - Suite: 268 JUnit + 28 fixture + 3 audit + 4 seeded + 8 live DOM, all green. New memory
    [[feedback-standard-ui-bar]] + [[feedback-no-false-scarcity]]. ONLY remaining now: the merge-day
    prod cutover (needs the real prod DB + the box) and turning auth on (needs a Google OIDC client secret).
  ===============================================================================================
- THESIS-WORKFLOW PROGRAM COMPLETE (2026-07-09, commits c391389→6d0a90e→533d1dc→069e4e9; branch, NOT
  deployed). The app is reorganized around Thesis→Dataset→Scenario→Strategy→Simulation:
  - SIM ENGINE (sim/): Rng (seeded, deterministic), ScenarioSpec (+Beginner presets), PathGenerator
    (GBM/bridge/block-bootstrap/Student-t/Merton-jump/Heston full-truncation Euler; OHLC intraday
    bridge), IvSpec (drift/mean-revert/event-crush IV paths), ScenarioSimulator (BSM legs along every
    path → P&L percentiles/winRate/fan/histogram), SimulationEngine (preview fan + runAndPersist →
    auto-saved synthetic dataset). V6 dataset registry + dataset_id on both bar tables; DatasetService
    (active switch, observed untouchable, prune 25); StoredCandleStore serves the ACTIVE dataset
    (synthetic → MODELED, never masquerades); StoredHistoricalOptionsProvider feeds the single
    Backtester from owned option history. Routes: /api/datasets*, /api/sim/{scenario,strategy,dataset}.
  - SCENARIO STUDIO UX (js/scenario.js): Beginner = story cards w/ SVG sketches + plain-dollar
    verdicts; Expert = full model menu + IV knobs + seed/paths + "The math" expandable (real SDEs).
    Research gains "What could happen next?" (fan preview + Re-roll + handoff); Trade→Verify gains
    "Replay the past | Imagine a future" pills (working idea or quick picks through the Monte Carlo);
    Data gains "Datasets & scenarios" (generate/activate/delete) + a loud app-wide SCENARIO MODE
    banner whenever a synthetic dataset is active.
  - WORKSPACE CONTINUITY: V8 workspace table + WorkspaceService + GET/PUT /api/workspace (client-
    authoritative blob: draft forms/working idea/symbol/route; rev per write; 128KB cap; 'local' when
    auth off); js/workspace.js persists localStorage-instantly + backend-debounced (+pagehide
    keepalive); a bare open restores the exact route (explicit hash wins); hidden tabs adopt newer
    revs and re-render on return. RESTORED TICKETS RE-ENTER AT THE STRIKES STEP w/ preview dropped
    (never a reload-armed Confirm at stale prices). Home gains "Pick up where you left off" chips.
  - EVENTS (SSE, not WebSockets — all realtime is server→browser; commands stay REST): util/EventBus
    (async single pump thread — a stalled client never back-pressures a publisher; Last-Event-ID
    replay ring; fresh clients start at NOW) + GET /api/events streaming job.progress/complete,
    dataset.selected, provider.cooldown, workspace.updated as small HINTS (client refetches; GETs
    stay truth). PER-USER SCOPED when auth on (job/workspace events carry their owner; the stream
    filters — was an identity-enumeration leak). Jobs card updates live (poll drops to 10s fallback).
  - GOVERNED PREFETCH: API.prefetch marks speculative GETs X-Priority: prefetch; server 204s them
    when heavy providers lack budget (CboeProvider.prefetchBudget: never while cooling/contended;
    fixtures always allowed); app warms likely-next-step (expirations/history for the working symbol)
    on idle. GENERAL GOVERNOR: market/ProviderPoliteness (concurrency+spacing+429/999 breaker+
    prefetch budget+cooldown events) — wired into Yahoo; Cboe keeps its inline test-pinned copy.
    Cooldowns render as a CALM amber header chip (self-expiring), never a scary banner.
  - THESIS SPINE: the working view (symbol · goal · thesis · horizon) VISIBLY follows — idea bar
    shows it when no working idea exists ('QQQ · Trade a view: bearish · ~1 month' + Find ideas);
    a fresh Scenario Studio opens on the thesis-matched story shape.
  - ADVERSARIAL REVIEW (workflow fleet: 4 lenses × 2 refuters, 32 agents): 14 findings, 11 confirmed
    + 1 contested — ALL FIXED (list above reflects the fixes); 2 refuted. Pinned gotchas: Db.prep's
    RETURN_GENERATED_KEYS breaks INSERT..RETURNING via executeQuery (upsert+select in one tx);
    concurrent refreshScenarioBanner (click + SSE) duplicated banners (supersede token + remove-all);
    workspace PUT must NOT flush the GET cache (STATE_WRITER regex); `docker compose up -d db` down
    ⇒ every DOM suite fails at 0ms in before() — check the container FIRST.
  - FINAL MATRIX: 332 JUnit + 43 fixture + 3 audit + 4 seeded + 8 live DOM — ALL GREEN.
  ===============================================================================================
- REVIEW-RESPONSE PROGRAM (2026-07-10, commits 03e5972→44fcacf→b59b85c→7d2a9d9; branch, NOT deployed).
  External review (12 findings P0/P1) + user UX critique of "Imagine a future" — ALL addressed:
  - SIM CORRECTNESS (P0s): expired legs settle ONCE at their expiration-step price (were re-valuing
    against post-expiry stock forever); total work capped as a PRODUCT (ScenarioSpec.MAX_TOTAL_POINTS
    3M + SimBudget 2 concurrent runs); runAndPersist generates exactly ONE path; missing quote = loud
    404 (never a silent $100 anchor — three sites).
  - SIM MATH: GBM is Gaussian (was literally t(6)); Itô −σ²/2 drift correction in GBM/jump/Heston (+
    Merton compensator) — E[S_T] sits ON the stated drift (pinned); bootstrap rescales to the vol knob
    + step size; GAP/EVENT shapes carry a deterministic timed shock that REPLACES that step's noise
    (overnight events, not diffusion) so EVERY path contains the promised move; 6 statistical
    contract tests. Persisted scenarios write the RECENT PAST ending today (not future dates) so an
    ACTIVE dataset genuinely drives Research/HV/backtests (test window updated to match).
  - OWNERSHIP: datasets per-user (list own+observed; foreign activate/delete = 404; per-owner prune
    spares the active run); active-dataset + universe switches admin-gated under auth (app-wide read
    path); workspace localStorage namespaced per signed-in subject.
  - EVIDENCE: StoredCandleStore answers only when it COVERS the range (head/tail ±7d + ≥60% trading
    days; synthetic datasets exempt — scenario mode IS the world); weakest-link freshness everywhere
    (any demo bar ⇒ FIXTURE; all-observed ⇒ EOD); UnderlyingBackfill uses candleSeriesFromProviders
    (stored-first read let a partial store backfill only itself); Backtester tier decided by chain
    EVIDENCE not provider name (owned CSV counts as HISTORICAL_CHAIN); PortfolioBacktester marks from
    dataset_id='observed' AND bid_ask_observed=1 only, OBSERVED label needs ≥90% observed marks.
  - FEED: live-mode active-sector warm trickles at 1.5s spacing (was ~18s interactive queueing);
    refresh/warm jobs use engine.refreshBlocking (no success-before-refresh); Cboe payload cache
    weight-bounded ~64MB; breaker cooldown PERSISTS across restarts (settings via event bus);
    fixture snapshots reload as FIXTURE never STALE; EventBus per-subscriber bounded queues (256,
    drop-oldest) on virtual threads — one stalled client can't block publishers or peers.
  - DATA OPS: reset tiers cover market_snapshot/dataset(≠observed)/workspace + invalidate the
    in-memory active-dataset cache; scenario banner shows the dataset NAME (config.activeDatasetName).
  - "IMAGINE A FUTURE" REBUILT (user critique): visible symbol input + universe chips (was silently
    following the working symbol = looked hardcoded to AAPL); FULL 18-structure catalog w/ payoff-
    shape sketches (story cards sketch the PRICE PATH; position cards the PROFIT shape — labeled);
    entries priced from LIVE quotes at executable sides w/ provenance note (model-vs-itself was a
    coin flip by construction — verdicts are now decisive, e.g. straddle under news-shock 84%/+$837);
    beginner IV defaults to the chain's real ATM IV (server); 4 steps/day + full-resolution samples
    (real squiggle, not connect-the-dots); Heston κ/ξ/ρ knobs; model-irrelevant fields disable w/
    tooltip; σ/μ glyphs escape the uppercase transform; fan hover readout; histogram $ axis +
    break-even marker. Research: "What COULD happen" + "What HAS happened" framed as a pair.
  - DELIBERATE DEPARTURES from the review: recent-past framing instead of a virtual analysis clock
    (simpler, honest, makes every existing screen work unchanged); active-dataset/universe stay
    app-level switches gated to admin under auth (per-user read-path threading is the recorded
    follow-up); Cboe keeps its inline pinned governor (ProviderPoliteness generalizes for others).
  - Matrix: 342 JUnit + 43 fixture + 3 audit + 4 seeded + 8 live DOM — ALL GREEN.
  ===============================================================================================
- UXR RECONCILIATION PROGRAM (2026-07-10, commit 85d28d4): the 67-finding UX-audit P1/P2s + my
  strategic list + junior's one-workflow critique, executed. P1s: portfolio-backtest drawdown was
  displayed 100x (fraction now); dual Score systems NAMED ('Screen score' vs 'Decision score' w/
  which-wins tooltips). Reconciliation theme: unwind-vs-header explained (executable vs midpoint),
  builder totals framing + set-aside vs worst-case in place, lots x ratio notation, per-flow
  risk-budget chips, young-trade spread-cost explainer, LOCAL-timezone dates (UI.fmtDate). Jargon/
  honesty: beginner blurbs plain-worded, POP spelled out, Unwind/Settle/Void/Roll subtitles,
  pricing enums prettified, covered-call guided path ('buy practice shares ->'), tape DEMO chip,
  demo/level-branched empty states, scenario card gated on quote, \$247.1600->\$247.16, -0.0% fix,
  welcome clips, scan wall (jump nav + top-3/goal + Show-N-more), stale results cleared, #/data
  alias. ONE WORKFLOW: 'Compare all strategies under this scenario' — full catalog on IDENTICAL
  seeded paths ranked by E[P&L] (verified: 71% CSP..35% long put under one story — decisive, not
  50% mush); buy-and-hold baseline on the same paths; HV-calibrated beginner wildness ('Typical'
  = typical FOR THIS STOCK); volAnnual<=0 = server 'use market vol' sentinel; GET /api/strategies
  + DOM anti-drift check pins frontend catalogs to StrategyFamily; named templates keep family
  through handoff. EXPERT DEPTH: chain OI/Vol columns + per-strike Build buttons; Active table
  live 'Now' P/L (enriched rows); IV-rank narrative chip; snapshots DEFAULT ON in live mode
  (600s initial delay). LOOP CLOSED: Portfolio 'Your record' (calibration: predicted-POP buckets
  vs realized); Roll on trade detail (close + rebuild ~1mo out, honestly two orders); calendars
  get a MODELED sim P&L range in the builder; Home first-week journey checklist (beginner, fresh
  accounts, CSS-glyph checkmarks — text ✓ trips the emoji audit). GOTCHA: a views.js helper used
  in ticket() must not assume a 'beginner' var exists in that scope (broke Place; cascaded 10
  tests). KNOWN-OPEN (P3): payoff-chart x-domain still spot±30% (verticals render thin) — needs a
  careful knot-based trim around the drag-handle math; per-path click-inspection on the fan.
  Matrix: 342 JUnit + 43 fixture + 3 audit + 4 seeded + 8 live DOM — ALL GREEN.
  ===============================================================================================
- UXR2 (2026-07-10, commit b1050e1): junior's 8 release blockers + the user's three UX problems.
  BLOCKERS: (1) magVolFor crash — LESSON: a multi-replace python patch that asserts AFTER some
  replacements but BEFORE write() loses EVERYTHING silently when one assert fails; the calibration
  patch died this way TWICE. Assert-all-first or write incrementally. (2) True per-stock wildness:
  HV multiplier client-side + volAnnual<=0 sentinel resolving to chain ATM IV in ALL sim endpoints.
  (3) Contract identity: sim runs the EXACT listed contracts the entry priced (resolvedLegs +
  named snaps). (4) Freshness-honest entry notes (DEMO/delayed/real-time; never 'is real').
  (5) /api/sim/compare: ONE path set, one budget permit, refusals BY NAME, RoR-per-downside
  ranking (fair across position sizes), beginner top-3 progressive disclosure, undefined-risk
  exclusion disclosed. (6) legs/qty/ratio/structure caps. (7) PortfolioBacktester snaps to LISTED
  expirations from owned history. (8) observed-only coverage; banner states single-symbol scope.
  UX: MarketContextSelector on Imagine-a-future (sector rail + search + full-universe non-wrapping
  peer rail — replaced 8 scattered wrapping pills); 'Historical evidence — did this happen before?'
  MOUNTED on the symbol page between chart and futures (the promised pairing was a false claim;
  also fixed its labForm crash when mounted outside the study view); stable field geometry
  (.form-grid label min-height contract + shortened offender labels) with a DOM alignment test.
  New pinned coverage: every story card × every model end-to-end; batched-comparison call count;
  evidence-card ordering; per-row control alignment. Matrix: 342 JUnit + 47 fixture + 3 audit +
  4 seeded + 8 live — ALL GREEN.
  ===============================================================================================
- FIN PROGRAM (2026-07-10, commit 2a9bda6): junior's completion sequence + the two open P3s —
  everything closed except the two user-dependency items (auth-on needs the Google OIDC client
  secret; prod cutover needs the EC2 box + real DB).
  - FIN-1 PER-USER DATASET CONTEXT (no global synthetic switch): db/DatasetContext = request-scoped
    ThreadLocal set by a before("/api/*") from datasets.activeId(ownerId(ctx)), cleared in after;
    StoredCandleStore reads it; anything OUTSIDE a request (engine warm, snapshots, scans) sees no
    context → observed. DatasetService.activeId(userId): per-owner key 'active_dataset:<owner>' w/
    legacy-global fallback + dangling-id→observed guard; setActive per-user (activation admin gate
    REMOVED — ownership is the rule) + owner-tagged dataset.selected; DataResetService predicate →
    k LIKE 'active_dataset%'. Pinned: user A's switch never flips user B; unit tests must set
    DatasetContext explicitly (no HTTP request thread).
  - FIN-2 FEED DISCIPLINE: MarketDataEngine pendingInteractive counter — tick + boot-warm
    backgroundRefresh() YIELDS while any user-blocking fetchBlocking is in flight (next tick
    retries). CboeProvider CachedPayload{data, fetchedAtMs} — quote/chain asOf = FETCH time (a
    cache read can never restamp stale as fresh). TradeService markMemo (Caffeine 10s): ONE mark
    snapshot shared by portfolio summary/greeks/rows; refresh() recomputes+replaces; unwind/settle/
    delete invalidate. Live-mode per-IP token bucket (300 burst, 50/s; fixture mode + SSE/health/
    metrics exempt) + GET /api/metrics {requests, errors, throttled, engine}.
  - FIN-3 ONE RANKING: /api/recommend candidates now ORDERED by the DECISION score (full
    StrategyEvaluation composite, same as Decision page + scans) via ApiServer.decisionRanked —
    each candidate carries decisionScore/decisionViable (Jackson tree rebuild; Candidate record
    untouched), 'ranking':'decision' disclosed, evaluation failure falls back to screen order.
    UI: Decision-score chip leads the expert chip row, comparison table gains the column, tooltips
    updated. GOTCHA: DOM suites that pin RESERVE_RELEASE ledger rows must pick a CREDIT candidate
    card (decision ranking put a debit spread first) — beginner cards now carry data-strategy too.
  - FIN-3 STATISTICAL RIGOR (ResearchQuestionEngine): NON-OVERLAPPING signal events (firings
    inside a taken hold window merge into that episode; noted w/ merged count; pinned by example-
    date spacing ≥ hold); z-test = independent events vs deflated overlapping complement; Cohen's-d
    effectSize vs baseline spread; split-half walk-forward holdout ('held'/'faded' in verdict +
    both-level note + expert chip); multiple-testing note when significant. QuestionResult gained
    effectSize/holdout (appended fields, wire-compat).
  - FIN-3 CHART P3s CLOSED: payoffChart knot-based x-domain trim (knots from slope changes +
    breakevens + spot + current handle strikes, padded max(35% span, 3% spot); edge interpolation
    keeps the curve exact; handle snap bounded to the visible domain, seeded from current; pure-
    linear payoffs keep the full span) — verified: 245/250 bull put fills the chart. Fan per-path
    click inspection: wide invisible hit stroke per sample + accent highlight + 'ends at X (+p%),
    travelled lo–hi' readout — verified live (playwright: click ON the stroke via getPointAtLength
    midpoint mapped through getScreenCTM; a bbox-center force-click misses squiggles).
  - Matrix: 344 JUnit + 47 fixture + 3 audit + 4 seeded + 8 live DOM — ALL GREEN.
  ===============================================================================================
- CP PROGRAM COMPLETE (2026-07-10, commits dcd4c7b..CP-12; branch, NOT deployed): the full
  completion plan (MU-trade lessons + junior's plan + my additions, reconciled over 3 iterations)
  EXECUTED END TO END. One invariant everywhere: no new surfaces, no duplicate builders, no
  free-form text — ONE evaluation pipeline upgraded for every strategy, both levels.
  - CP-1 CONTRACTS+ISOLATION: db/AnalysisContext (explicit, immutable; ThreadLocal DELETED —
    vthread fan-outs capture the param; settle/marks/backfill/recommendations ALWAYS observed —
    fixed a real leak where a synthetic-active user's settle read synthetic closes); legacy
    dataset fallback local-only; OpenRequest IS the OrderPackage (+proposedNetCents/
    feesOverrideCents/source, compat ctors); PMCC template; 5-surface anti-drift; keyword
    warning relabeled event-like-news. GOTCHA: Jackson + record + multiple ctors can bind a
    SHORTER ctor silently dropping new fields — @JsonCreator the canonical AND check wire DTO
    adapters (TradeOpenRequest→toOpenRequest dropped the fields; curl-verified).
  - CP-2 ENGINE CORRECTNESS: sim work = paths×(steps+1)×LEGS (40M cap, compare sums legs);
    uniform sim-leg validation; dup calibrateVol removed; MarketHours NYSE calendar 2020-35
    (computus + observed shifts; isTradingDay/tradingDaysBetween — Fri→Mon = 1 session, pinned);
    PortfolioBacktester strikes snap to LISTED contracts w/ modeled-substitution counting.
  - CP-3 EVALUATION MATH (the core): pricing/ProbabilityMap — numeric lognormal integration vs
    the exact PayoffCurve: P(any/max profit), P(max loss), P(partial), CVaR95, ±20%/2σ stress
    (the honest number when max loss is undefined), touch odds; basis ALWAYS disclosed
    ('the options market's own odds, not a forecast'); trading-day T ≤14d (sessions/252);
    near-expiry regime warnings (≤5 sessions + weekend gap + pin-inside-1-session-EM);
    proposedNet reprices the WHOLE package (max loss/breakevens/POP/EV follow YOUR price,
    optimistic-vs-mid warned); execution quality aggregated (mid/executable/proposed, package
    spread, exit estimate, concession $ + % of mid/maxP/maxR); DTE-aware ManagementPlanner
    (near-expiry: close-never-roll/touch-triggers/final-hour; ≤15 sessions; standard 21-DTE);
    server-assembled verdict favorable/mixed/unfavorable + biggest-risk sentence. All via
    TradeService.computePlan → TradePreview.analytics (one map field; blocked cliffs included).
    MU map pinned: ~34%/~14%/~53% reproduce the hand-verified incident numbers.
  - CP-4 DECISIONPOLICY: ScoreComposer = THE policy; +EV component (0.20, heaviest — POP & RR
    separately let the MU shape double-dip), executable-credit gate (negative-credit 'credit'
    structures gate out: 'book too wide to earn a credit'), tail haircut 35%, gamma/DTE mult
    (×0.8 ≤3d, ×0.9 ≤7d); weights .15/.15/.20/.10/.10/.15/.15; negativeEv flag on decision-
    ranked candidates; /api/evaluate returns CASH + BUY_AND_HOLD baselines.
  - CP-5 FORCEFUL REVIEW: views.verdictPanel — conclusion-first banner + probability map stats
    (beginner sentences / expert dense) + execution ladder + DTE plan + quote-age line;
    ACKNOWLEDGMENT GATE (negative EV / >10% concession / near-expiry) before Continue; expert
    'Evaluate at your price' (net+fees re-preview); EM band on payoff charts (domain-aware);
    builder panel verdict line + prob chips. DOM suites check ack boxes like users.
  - CP-6 ACCOUNT TRUTH: paper/AccountRiskContext (NLV/cashBP/marginBP/maintenance/riskCapital,
    self-declared, per-user settings JSON; GET/PUT /api/account/risk-context; Portfolio Account
    card both levels); preview.accountFit % of each denominator + over-risk-capital warning
    (the 6.5%-of-real-cash MU lesson); /api/portfolio/heat (total worst case, short-vol count,
    concentration, assignment cash, post-assignment BP) + Book-heat strip; ATOMIC account mark
    snapshot (one pass, 10s memo) shared by summary+greeks; closes invalidate.
  - CP-7 EVENT MODEL: market/EventService — next-earnings ESTIMATED from EDGAR 10-Q/10-K
    cadence (median gap 60-120d, forward-dated, confirmed=false, honest empty when <2 reports);
    guardrail earnings warning is CALENDAR-based w/ date+basis; keyword hits a separate labeled
    advisory; research payload earningsEstimate + exDividend{available:false}; comingUp chip
    'Earnings ~date ±7d'; Cboe payloads parse the feed's OWN timestamp (asOf = source stamp).
  - CP-8 RIGOR: RiskProfile +evPhysicalCents/evBasisNote (same integral at REALIZED vol — the
    VRP made visible, two lanes never blended); compare returns CASH baseline + fairness
    contract; split-half renamed 'consistency check … not out-of-sample' everywhere.
  - CP-9 LEARNING LOOP: V9 trades.origin; createExternal (real fill via proposedNetCents,
    validated against live listed contracts, ZERO ledger/cash/reserve — pinned); external
    unwind/settle write outcome to the trade row only; EXCLUDED from openPositionsValue
    (money identity) but marked/judged identically; POST /api/trades/external; Portfolio
    'Record a real trade' structured form + EXTERNAL badges. E*TRADE populates the same
    OrderPackage when keys arrive.
  - CP-10 PERFORMANCE: MarketDataEngine refresh pool = PriorityBlockingQueue executor
    (interactive 0 > screen 1 > job 2 > warm 3, FIFO within class; singleflight kept; advisory
    yield kept as queue brake); throttle honors XFF only from loopback/TRUSTED_PROXY=true;
    Trade sector rail shows the persisted selection on cold render (fetches /api/universe).
  - CP-11 SWEEP: keyboard strike handles (role=slider, arrows step listed strikes, Enter
    commits); backtest prefill = family+DTE from the working idea; Cboe _SPX/_NDX/_VIX/… index
    roots; mid()-is-last-trade → display mark labeled STALE (fills unaffected); /history
    labeling verified already-done.
  - CP-12 GATE: golden regression portfolio in PaperCoreTest — MU-geometry condor (judged at
    the user's fill: map+regime+no-invented-earnings+exec+verdict), long straddle (uncapped map
    coherence), per-family analytics contract (spread/long-call/CSP), external lifecycle,
    repricing, near-expiry regime + all prior pins.
  - FINAL MATRIX: 359 JUnit + 48 fixture + 3 audit + 4 seeded + 8 live DOM — ALL GREEN.
  - USER-DEPENDENCY LANE (blocked on the user, NOT deferred): auth-on (Google OIDC client
    secret) · prod cutover (EC2 session: merge→ETL→migration rehearsal→backup-RESTORE drill→
    deploy; strikebench.com still runs pre-research-platform main) · Polygon/AV key (observed
    candles) · licensed options CSV (observed option evidence) · E*TRADE keys (sandbox e2e +
    automated fill sync).
  ===============================================================================================
- SIMULATED-MARKET + EXPLANATION PROGRAM (2026-07-10, branch; NOT deployed). Three blocks after the MU
  iron-condor analysis: R (13 junior corrections), E (explanations), S (simulated market for weekend
  reviewer sessions). Commits 6be985c..: R-MATH / R-HONESTY / R-CONTRACTS / R-GOVERNANCE / Block E /
  Block S.
  - BLOCK R (all 13): R1 ONE time convention — model T = calendar/365 everywhere (chain IV is
    calendar-annualized; MU straddle reproduces only at calendar T); trading SESSIONS drive the
    near-expiry regime (<=5 sessions gamma warnings, DTE-aware plans). R2 server-enforced
    acknowledgments: preview returns requiredAcks + HMAC ackToken (15-min TTL, per-boot secret);
    create RECOMPUTES and 422s on missing acks — UI checkboxes are no longer the enforcement.
    R3 three clocks disclosed (sourceAsOf / evaluatedAt / now). R4 AccountRiskContext governs:
    riskCapital caps engine budget + oversize needs an ack; accountFit % chips. R5 proposed price
    is FIRST-CLASS: PayoffCurve.entryAdjustCents package-level shift (adjustNetTo leg-fabrication
    DELETED). R6 external-trade lane: trades.origin EXTERNAL (V9/V10: proposed_net_cents,
    executed_at, broker, order_ref), planFromUserFills for dead-contract historical recording,
    zero paper-cash mutation. R7 earnings are ESTIMATES from EDGAR 10-Q/K cadence (never keyword
    hits; chip says 'Earnings (est.)'). R8 evPhysicalCents -> evHistVolCents rename (it is a
    historical-vol scenario EV, not physical measure). R9 EV net of round-trip fees
    (EvalContext.feePerContractCents; DecisionPolicy gates + 35% tail haircut + gamma/DTE mult).
    R10 evaluated baselines: CASH zeros + BUY_AND_HOLD via ProbabilityMap CVaR on the same grid.
    R11 backtest TTE to the SNAPPED expiration + executable sides. R12 priority-executor
    escalation (a queued low-priority task joins at the caller's priority via requeue). R13
    dependence-corrected stats wording. pricing/ProbabilityMap: P(any/max profit), P(max loss),
    CVaR95, +/-20%/2sigma stress, touch odds — risk-neutral basis ALWAYS disclosed; TradePreview
    .analytics consumed by ticket review, builder, compare — ONE evaluation pipeline.
  - BLOCK E EXPLANATIONS: learn.js INFO registry ({short, beginner, expert} per term, ~20 terms:
    pop/ev/evhistvol/cvar/pmaxloss/touch/concession/decisionscore/screenscore/evidence/ivrank/vrp/
    nlv/riskcapital/expectedmove/sessions/seed/world/assignment...); UI.info(termKey) = VISIBLE
    quiet (i) trigger (user: never hide the affordance), bubble on 550ms hover or click-focus,
    [+] expands beginner/expert body by level, Escape/outside closes, aria-expanded; title= dups
    removed where info() replaced them. AUDIT: window.__usedInfoTerms accumulates every rendered
    term; dom.test 'explanation system' walks screens at both levels and fails on any term missing
    from the registry (anti-drift, same pattern as the strategy-catalog pin).
  - BLOCK S SIMULATED MARKET (weekend reviewer sessions; traders/statisticians): market/sim/
    SimulatedWorld — deterministic per-seed world: virtual exchange clock (30 sim-sec x speed per
    tick, skips closed hours/weekends via MarketHours, rolls daily bars at close), market-factor
    model (z = rho*sign(beta)*zM + sqrt(1-rho^2)*zi, rho=|beta|/1.5, Ito drift, scenario shapes
    CHOP/TREND_UP/TREND_DOWN/SELLOFF_REBOUND/RALLY_FADE/VOL_EVENT), 250-bar seeded history so
    HV/charts work from day one, full option exchange (next-6-Fridays + 2 monthlies, BSM + smile
    + intrinsic floors + moneyness/DTE spreads + synthetic OI), injectMove/injectVolShift for
    live-demo events. Freshness.SIMULATED (TRADABLE rank, after FIXTURE; badge-sim style).
    SimulationSessions: V11 sim_session persistence (config jsonb — same seed restores the SAME
    world), ONE shared 1s tick loop, MAX_ACTIVE=3, world.tick SSE hints throttled 4s, per-user
    ownership. RUNTIME PER-USER SWITCH (user: never a boot flag): settings 'active_world:<owner>',
    GET/PUT /api/world; MarketDataService.setWorldResolver + world-aware overloads — observed
    engine NEVER stops, switching back is instant; anything outside a request sees observed
    (fail-safe). currentAccount() is world-aware: inside a world the SIMULATION account (V11
    accounts.world_id, $100k, getOrCreateForWorld) IS the account — real practice account and sim
    lane can never pollute each other. TradeService.worldOf(accountId) threads every marks/closeOn
    call; RecommendationEngine.recommend(req,bp,worldId) uses a CALL-scoped ThreadLocal (set/
    finally-cleared same thread — NOT request-ambient); research/history/expirations/chain/
    recommend/guardrails all world-routed. Routes: /api/sim/market CRUD + start/pause/step/speed/
    event + report (honest note: outcomes measure DECISIONS, not the market). FRONTEND stays
    agnostic: ONE loud #world-band (SIMULATED MARKET · scenario · sim clock · speed · seed +
    Play/Pause/Step + Return to real market) mirroring the scenario-banner supersede pattern;
    body.in-sim-world hides the tape (it quotes the observed market); world.tick SSE filtered by
    App.state.world + worldGen token discards stale events from a just-left world; App.switchWorld
    = PUT + flushCache + render (route/level/symbol preserved). Data Center 'Simulated market'
    workbench card: create (name, symbol:beta pairs, scenario, vol, seed, speed) / enter / pause /
    inject event / finish. SimulatedMarketTest (5): seed determinism, coherent books (no crossed,
    intrinsic floors, monotonic strikes), clock-in-session + rolled bars, event injection, beta
    co-movement; dom.test 'simulated market' pins create -> band -> world-routed research
    (SIMULATED badge, sim account active) -> instant return (band gone, real account back).
    COHERENCE SWEEP (screenshot-driven): scout world-routed end-to-end (SignalEngine.analyze +
    AutoRecommender.run + horizonIdeas gained worldId; in a world the default scan list is the
    WORLD's symbols); engine.ladder(req,bp,worldId) twin (research symbol-action one-liners price
    in-world); research benchmarks come from the SAME world (observed SPY/QQQ never leak into a
    sim session — filter on SIMULATED freshness since world reads fall back observed). GOTCHAS:
    ApiServer field-initializer built SimulationSessions before create() assigned the db pool ->
    attachDb late wiring (same pattern as setEvents); V1's accounts_type_check needed the
    SIMULATION lane (V11 drops+recreates it); dom-seeded's raw POST /api/trades seeding now goes
    preview->ack->create (R2 gate applies to test seeding too); /api/account returns {account:{}}
    not the account. Suites after: 365 JUnit + 50 fixture + 3 audit + 4 seeded + 8 live DOM — ALL
    GREEN on the final jar. Screenshots shots/sim-{workbench,band-data,research-acme,trade}.png.
- W REMEDIATION PROGRAM (2026-07-10, commits b896113..; branch, NOT deployed): junior's 10 review
  findings + 10 math findings + the (one-time, oversized) adversarial fleet's confirmed set —
  ALL EXECUTED. The review-discipline lesson is memory [[feedback-review-discipline]]: no more
  mega-fleets; domain invariants (one clock per lane / lane purity / identity / price coherence)
  are structural release gates, budgeted into every program as a stage.
  - ONE CLOCK PER LANE: MarksSource.simNow → TradeService.nowFor threads the WORLD's clock through
    entry gates, settle (settles at the SIM bell at world closes — accelerated expiry unwedged),
    closed-market warnings (a running world is never 'closed' — the Saturday session works),
    analytics T/DTE/regime/acks, engine+ladder expiration pick, signals, positions, research/
    history windows (sim rolled bars render + feed HV), guardrail today, comingUp (payload asOfDate).
  - ISOLATION: owner-checked memory-resident lookups; server-only world ids; owner-tagged
    world.tick; per-owner MAX_ACTIVE at create AND start (+persisted RUNNING rows); LRU-bounded
    resident worlds; FINISHED terminal; V12 FK accounts.world_id; ack HMAC += accountId+feesOverride.
  - LANE ROUTING: shares world-priced (PositionsService.worldOf); /api/quotes + /api/universe world-
    aware; EvalContext from the candidates' OWN market (decision/evaluate/ladder/opportunities/
    optimize + R4 risk-capital caps on all of them); calibration NEVER records sim outcomes;
    sim-studio anchors + calibrateVol world-routed; backtests refuse in-world honestly;
    earnings/news suppressed in worlds.
  - MATH: EV includes entryAdjustCents (pinned to the cent); ProbabilityMap r−σ²/2 drift disclosed
    (r=0 labeled 'zero-drift lognormal scenario'), EXACT knot-region probabilities (straddle max
    loss honestly P=0), touch labeled reflection estimate; SimulatedWorld v2 (MODEL_VERSION sim-2):
    fixed 30-sim-sec QUANTA (speed-invariant paths, pinned), counter-based per-(seed,stream,symbol,
    quantum) RNG, real beta factor model (σ_i=√(β²σ_m²+σ_idio²), pinned), continuous open-market-
    seconds TTE (bell convergence pinned), inception-anchored strike grids (crash can't delist,
    pinned), sim-time scenario phases, separate IV level process, holiday-Friday rollback + 0DTE
    till the bell, vol-scaled history, validation, America/New_York stamps. Parity pinned as the
    AMERICAN band |C−P−(S−Ke^{−rT})| ≤ K(1−e^{−rT})+floors (the intrinsic floor IS early-exercise
    value); convexity/calendar/verticals/uncrossed pinned.
  - REPLAYABLE: V12 state/events/model_version; injections+speed logged at quanta; restore =
    config+replay (restart resumes the exact world, RUNNING resumes ticking — pinned); report
    shows model version + event log ('seed alone' overclaim scoped).
  - UX: product workbench (scenario story cards both levels, beginner plain symbols + speed words +
    auto seed + real-price anchors disclosed, expert per-symbol beta/price rows — no micro-syntax),
    app modals for inject (symbol picker) + finish (REPORT SHOWN FIRST; finished sessions stay
    listed w/ reports), band v2 (human scenario names, live speed control, Report, in-place tick
    updates preserving focus, sticky below topbar, info(world)/info(seed)), Home quick action
    'Practice in a simulated market', world.tick → cache flush + in-place research price updates
    (the market VISIBLY moves), world.selected SSE + visibilitychange = multi-tab adoption,
    cross-lane working ideas dropped on switch (ticket.world tag), in-world hides tape+sector rails.
  - EXPLANATIONS: UI.term merged into INFO (registry-backed terms open the SAME level-aware bubble
    at BOTH levels), scenario/beta/speed INFO entries + seed/world/pop/ev/assignment/ivrank/nlv/
    riskcapital wired on their surfaces, aria-expanded/describedby + role=dialog, DOM-adjacent
    insertion (Tab reaches [+]), Escape returns focus, scroll RE-ANCHORS (close only when trigger
    leaves view), 28px hit area, bubble z-above sticky chrome + max-height, audit test walks
    builder/backtest/data/portfolio FIRST + pins sim terms + keyboard path.
  - GATES: SimWorldGateTest(9) + SimLaneGateTest(6) + EV/map pins. FINAL MATRIX: 381 JUnit +
    50 fixture + 3 audit + 4 seeded + 8 live DOM — ALL GREEN. Screenshots shots/w-{workbench-
    beginner,workbench-expert,band,inject-modal}.png. GOTCHAS: the info bubble must be BODY-
    appended with absolute page coords (positioned ancestors corrupt them) + explicit Tab focus
    management (the dialog pattern) — DOM-adjacent insertion made it a moving target under
    Playwright's auto-scroll; Escape's return-focus needs a reopen-suppression window or the
    focus handler reopens the bubble; page.evaluate(Learn.setLevel) does NOT re-render — call
    App.render() after it.
- EVIDENCE-CONSOLIDATION PROGRAM (2026-07-10; the "Research straggler" fix): the event study is
  no longer a relocated Lab card — it is the PAST-EVIDENCE stage of the thesis workflow, and its
  analog windows are a first-class path source for the strategy simulator.
  - PRODUCT: research landing = market-entry only (sectors/symbols/recents/notes; workbench gone);
    symbol pages get ONE composed 'Test your view' section — a shared MarketThesis row
    (direction+horizon) drives two connected stages, 'Past evidence — what followed similar
    conditions?' (thesis picks the question; the horizon IS the study forward window; NO nested
    ticker input — the page symbol is inherited) and 'Possible futures' (whatIfCard mounted as the
    second stage). Conclusions are decision-useful: evidence strength (weak/moderate/strong from
    sample+significance+effect+holdout), N independent events, 'use this to raise or lower your
    confidence — never a prediction', and the DOWNSTREAM HANDOFF: 'Test strategies on these N real
    past occurrences →' → Trade Verify in EVIDENCE MODE (banner + clear button; the horizon locks
    to the study's window). The #/lab alias keeps a pointer card, not a duplicate tool.
  - STATE: App.state.researchStudy + App.state.marketThesis[symbol] replace labForm.hyp; results
    are KEYED by symbol|question|params|range (server studyKey authoritative) and NEVER restored
    across a mismatch — an AAPL study cannot flash on a QQQ page (pinned in dom.test).
  - BACKEND: QuestionResult += analogPaths (per-event forward windows as relative prices, 1.0 =
    event close) + eventDates + studyKey (appended fields, wire-compat); /api/research/questions +
    /api/research/event-studies are the PRIMARY routes (registered ABOVE /api/research/{symbol} —
    Javalin matches the param route first otherwise, pinned by the alias-parity test); /api/lab/*
    stays as byte-identical compatibility aliases. ScenarioSimulator.runOnPaths(paths,...) consumes
    ANY ensemble; /api/sim/strategy accepts pathSource HISTORICAL_ANALOGS (re-derives the SAME
    analogs deterministically — event detection has no RNG — so Research and Trade price ONE
    conditional sample, pinned) or CONDITIONAL_BOOTSTRAP (whole-path resamples via the shared
    sampler), horizon = the study's forward window (1 step/day), result carries pathSource/
    studyKey/analogEvents/sourceNote ('conditional history, not a model's odds, not a forecast').
  - SHARED FOUNDATION: sim/RandomStreams (counter-based mix64+gaussian+uniformInt — SimulatedWorld
    now delegates to it, identical outputs) + research/BootstrapSampler (the event study's CI moved
    verbatim — validated outputs unchanged — plus deterministic whole-path resampling). RECORDED
    SEAM (deliberately not silently done): migrating PathGenerator's stateful RNG onto RandomStreams
    CHANGES every generated path — do it only with a model-version bump and re-pinned goldens.
  - INTERPRETATION STAYS DISTINCT: historical frequency (conditional on selected past events),
    bootstrap uncertainty, parametric Monte Carlo, and risk-neutral pricing share machinery and
    paths but are always separately labeled; evidence mode in Trade banners its basis.
  - GATES: analog ensemble = the study's exact sample and deterministic (paths==conditioned.sample,
    start 1.0, run-twice identical, stock-over-analogs EV == analogs' own mean, pinned);
    conditional bootstrap deterministic + whole-path only; API alias parity + pathSource contract;
    DOM: composed section, no nested symbol input, keyed no-cross-symbol restore, landing clean,
    futures as second stage, studio tests open the futures stage via openFutures().
- Remaining/optional follow-ups: E*TRADE sandbox end-to-end with real keys, richer calendar modeling,
  candles-source labeling in /api/research/{symbol}/history (currently unlabeled when fixture serves in
  live mode), Backtest-stage prefill from the working idea (symbol lands in the form; family/window/DTE
  defaults could too), a real earnings/ex-div calendar if a keyless source appears.

## Non-negotiable decisions (from user)
1. Java package root: **`io.liftandshift.strikebench`** (moved 2026-07-08 from `io.liftandshift` on the
   research-platform branch, per user). Maven **groupId stays `io.liftandshift`** (owner namespace /
   coordinate — NOT the Java package; deliberately brand-neutral). Artifact `strikebench` (renamed from
   `options-lab` 2026-07-08 with the StrikeBench rebrand). The shade `mainClass` is
   `io.liftandshift.strikebench.Main`.
2. **Money is ONLY `BigDecimal` (per-share prices, scale ≤ 4) or `long` cents (ledger, balances, contract totals). Never float/double.**
   Doubles allowed only for non-money ratios (IV, greeks, probabilities) and inside the Black-Scholes numeric kernel; convert at boundary via `util/Money`.
3. Backend: Java 25, Maven, fat jar: `mvn test package` → `java -jar target/strikebench.jar`. (pom has `-Djava.release=NN` override property.)
4. Frontend: plain HTML/CSS/JS SPA, **no build step**, served from the jar (`/public` on classpath). No secrets in frontend.
5. Stack: Javalin 7.2.2 (Jetty 12), Jackson 2.19.0, sqlite-jdbc 3.49.1.0, Caffeine 3.2.0, SLF4J 2.0.17/Logback 1.5.18, JUnit 5.11.4, AssertJ 3.27.3, MockWebServer 4.12.0 (chosen over WireMock to avoid Jetty classpath clashes), Shade 3.6.0. Simple migration runner (not Flyway).
6. Scope: FULL build in one pass. User HAS keys for **E*TRADE, Polygon/Alpha Vantage, FRED** (will provide via env/properties; app must degrade keyless to fixtures + free sources).
7. Educational framing everywhere: recommendations are "risk-screened, data-backed educational candidates" with confidence/assumptions/caveats. **Never promise profits.** Not financial advice disclaimer.
8. Default paper account **$100,000** (editable before first trade; reset requires confirmation).
9. Port 7070. DB `data/strikebench.db` (legacy `data/options-lab.db` auto-moved once at boot by
   Main.migrateLegacyDefaultDb; explicit DB_PATH never touched). Fees default: $0.65/contract/leg
   (`FEE_PER_CONTRACT_CENTS=65`) + $0/order.

## Toolchain bootstrap (sandbox)
```bash
# 1. Check network (must succeed before anything else):
python3 -c "import urllib.request;print(urllib.request.urlopen('https://repo.maven.apache.org/maven2/',timeout=8).status)"
# If blocked: STOP and tell the user to enable network egress (Settings→Capabilities→Code Execution) and restart session.

# 2. JDK 25 (pick arch: uname -m → aarch64|x64):
mkdir -p ~/tools && cd ~/tools
python3 - <<'EOF'
import urllib.request,platform
arch='aarch64' if platform.machine() in ('aarch64','arm64') else 'x64'
url=f"https://api.adoptium.net/v3/binary/latest/25/ga/linux/{arch}/jdk/hotspot/normal/eclipse"
urllib.request.urlretrieve(url,'jdk25.tgz'); print("ok")
EOF
tar xzf jdk25.tgz && mv jdk-25* jdk25
# 3. Maven:
python3 -c "import urllib.request;urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz','mvn.tgz')"
tar xzf mvn.tgz && mv apache-maven-3.9.9 maven
# 4. Every mvn invocation (bash calls don't share env):
export JAVA_HOME=$HOME/tools/jdk25 PATH=$HOME/tools/jdk25/bin:$HOME/tools/maven/bin:$PATH
cd <project> && mvn -q test
```
Fallbacks if adoptium blocked: `https://download.oracle.com/java/25/latest/jdk-25_linux-aarch64_bin.tar.gz` (or `_linux-x64_`).
Project path in sandbox: `/sessions/<name>/mnt/optin/`.

## Javalin 7 API gotchas (verified from official docs — do NOT use Javalin 5/6 style)
- ALL routes/handlers go inside create: `Javalin.create(cfg -> { cfg.routes.get("/x", ctx -> ...); cfg.routes.exception(E.class,(e,ctx)->...); cfg.routes.error(404, ctx->...); }).start();`
- `cfg.jetty.port = 7070;` `cfg.staticFiles.add("/public", Location.CLASSPATH);` `cfg.router.ignoreTrailingSlashes = true;`
- `Javalin.start(cfg -> ...)` shortcut exists. `ctx.json/bodyAsClass/queryParam/pathParam` unchanged. Validators need `.required()` before `.get()`.
- Keep API DTOs to plain types + BigDecimal (Jackson auto-detected). Avoid java.time types in DTOs — use ISO strings.

## Architecture (packages under io.liftandshift.strikebench)
- `config.AppConfig` — env > sysprops > ./optionslab.properties > defaults. Provider base URLs overridable for tests (mock server).
- `db.Db` (JDBC helper, WAL, per-op connections, tx), `db.Migrations` (ordered classpath list).
- `util.{Json,Ids,Money}`.
- `model.*` — records; prices BigDecimal; `Freshness{REALTIME,DELAYED,EOD,MODELED,FIXTURE,STALE,MISSING}`.
- `pricing.*` — BlackScholes (double kernel: price/delta/gamma/theta/vega, normCdf), ImpliedVol (bisection), PayoffCurve (piecewise-linear from legs; knots; analytic breakevens; maxProfit/maxLoss incl. unbounded detection; probProfit under lognormal; EV via numeric integration in log-space).
- `market.ports`: `MarketDataProvider` (lookup, quote, expirations, chain, candles, news, status), `HistoricalOptionsProvider`, `NewsFilingsProvider`, `RatesProvider`, `BrokerageProvider` (connect/OAuth, accounts, balances, positions, previewOrder, placeOrder, cancel, orderStatus). `FixtureProvider` implements market+historical+news deterministically.
- `market.MarketDataService` — provider chain w/ priority (ETrade→Cboe→AlphaVantage/Polygon→Stooq→Fixture), Caffeine caches (quote 15s, chain 60s, candles 1h, news 5m), freshness gates, per-domain `ProviderStatusInfo` for /api/status (NEVER 500).
- `market.providers`: `FixtureProvider` (AAPL $255ish, SPY, QQQ, TSLA + `VTSAX` non-optionable; seeded RNG; realistic IV smile; generated chains for next ~8 expirations incl. 0DTE on SPY/QQQ), `CboeProvider` (keyless delayed chains: `GET {cboe}/api/global/delayed_quotes/options/{SYM}.json` — has bid/ask/iv/greeks/OI; label DELAYED), `StooqProvider` (keyless EOD candles CSV `https://stooq.com/q/d/l/?s=aapl.us&i=d`), `EdgarProvider` (keyless; MUST send `User-Agent: OptionsLab/1.0 (email)`; submissions JSON `https://data.sec.gov/submissions/CIK##########.json`; ticker→CIK via `https://www.sec.gov/files/company_tickers.json`), `TreasuryRatesProvider` (keyless daily yield XML) + `FredProvider` (key; series DGS1MO/DGS3MO/DGS1), `PolygonProvider` (key; historical option aggregates + expired contract reference for backtests), `AlphaVantageProvider` (key; daily adjusted candles), `ETradeProvider` (below).
- `strategy.*` — `StrategyFamily` enum: LONG_CALL, LONG_PUT, DEBIT_CALL_SPREAD, DEBIT_PUT_SPREAD, CREDIT_CALL_SPREAD, CREDIT_PUT_SPREAD, IRON_CONDOR, IRON_BUTTERFLY, LONG_CALL_BUTTERFLY, LONG_PUT_BUTTERFLY, CALENDAR_CALL, CALENDAR_PUT, DIAGONAL_CALL, DIAGONAL_PUT, COVERED_CALL, CASH_SECURED_PUT, PROTECTIVE_COLLAR + blocked-by-default NAKED_CALL, NAKED_PUT, SHORT_STRADDLE, SHORT_STRANGLE. `Guardrails` validator returns ALLOW/WARN/BLOCK + reasons: block undefined risk, stale/missing marks, insufficient buying power, chainless symbols; warn 0DTE/gamma, earnings, dividend, assignment (short ITM near ex-div), wide spreads, low OI/volume, DELAYED/EOD data.
- `recommend.RecommendationEngine` — inputs: symbol, thesis(bullish/bearish/neutral/volatile), horizon(0DTE/week/month/quarter), riskMode(learning/conservative/balanced/aggressive), maxLossCents, maxRiskPctOfAccount, minConfidence, allowedStrategies, avoidEarnings, allow0dte. Defaults conservative: defined-risk only, no 0DTE, ≤1-2% account risk. Rank by composite (finite-risk validity, freshness, liquidity/spread quality, risk:reward, POP confidence, capital efficiency, event risk, thesis fit) — never max-profit alone. Each candidate: legs, entry debit/credit, maxP/L, breakevens, POP, EV, liquidity score, freshness, warnings, `whyConsidered/bestUpside/biggestRisk/wouldInvalidate/confidence` + beginner explanation. Also return `rejected[]` examples (e.g. naked call) with block reasons.
- `paper.*` — accounts/ledger/trades per DB schema in V1. **Append-only ledger**; entry types DEPOSIT|RESET|PREMIUM_OPEN|PREMIUM_CLOSE|SETTLEMENT|FEE|RESERVE_HOLD|RESERVE_RELEASE|ADJUSTMENT; every row stores cash_after+reserved_after; fees are separate rows (never double-count); reserve = max loss held at entry, released on close; create revalidates against live data + buying power (cash − reserved); rejection → audit row only, zero mutation; refresh-marks writes trade_marks only (never cash); unwind at current mark, settle at cash-equivalent intrinsic; delete only voids ACTIVE trade after confirm (status DELETED + reserve release + reverse entry cash via ADJUSTMENT rows, audit-logged) — no hidden mutations.
- `backtest.Backtester` — daily loop, no look-ahead (entry uses data ≤ entry date); pricing tiers: HISTORICAL_CHAIN (Polygon) > MODELED_FROM_UNDERLYING (BSM w/ HV-derived IV proxy + skew bump) > PAYOFF_ONLY; report must state mode + confidence + coverage(days requested/covered), skipped trades w/ reasons, assumptions (slippage %, fees), equity curve, max drawdown, win rate, avg return-on-risk, worst trade, sample size. Never label modeled results as real option performance.
- `api.ApiServer` — routes below; `broker.*` for E*TRADE flows.

## E*TRADE adapter
- OAuth 1.0a HMAC-SHA1, server-side only. Request token → user authorize URL (`https://us.etrade.com/e/t/etws/authorize?key={consumerKey}&token={requestToken}`) → user pastes verifier code → access token (store in `secrets` table; expires midnight ET).
- Base URLs: sandbox `https://apisb.etrade.com`, live `https://api.etrade.com` (override `ETRADE_BASE_URL` for tests).
- Endpoints: `/v1/market/quote/{symbols}.json`, `/v1/market/lookup/{search}.json`, `/v1/market/optionchains.json?symbol=&expiryYear=&expiryMonth=&expiryDay=&includeWeekly=true`, `/v1/market/optionexpiredate.json?symbol=`, `/v1/accounts/list.json`, `/v1/accounts/{accountIdKey}/balance.json?instType=BROKERAGE&realTimeNAV=true`, `/v1/accounts/{accountIdKey}/portfolio.json`, orders: preview `POST /v1/accounts/{k}/orders/preview.json`, place `POST /v1/accounts/{k}/orders/place.json`, cancel `PUT /v1/accounts/{k}/orders/cancel.json`, list `GET /v1/accounts/{k}/orders.json`.
- Delayed vs realtime flagged per agreements — label in UI. Live trading gates: connected + options level + preview success + explicit typed confirmation "I understand max loss and this is real money" + idempotent clientOrderId. **Recommendation engine NEVER auto-places live orders.**

## HTTP API surface (all JSON under /api)
- `GET /api/status` (per-domain provider health; 200 even with zero providers) · `GET /api/config` (safe subset: port, fixture mode, fees, disclaimers)
- `GET /api/account` · `POST /api/account/reset {startingCash}` (confirm flag; blocked after first trade unless `force`)
- `GET /api/research/{symbol}` (quote+profile+IV/HV+context incl. sector benchmark quotes SPY/QQQ) · `/expirations` · `/chain?expiration=` · `/history?range=1y` · `/news`
- `POST /api/recommend` · `POST /api/trades/preview` (validate, before/after cash+reserve+buying power, warnings, no mutation) · `POST /api/trades` (create) · `GET /api/trades?status=&page=&size=` (paginated) · `GET /api/trades/{id}` (detail: entry vs current snapshot, payoff table/chart data, updated POP, marks history, audit) · `POST /api/trades/{id}/refresh` · `POST /api/trades/{id}/unwind` (body {confirm:true}; returns realized P/L) · `POST /api/trades/{id}/settle` · `DELETE /api/trades/{id}` (confirm)
- `POST /api/backtest` (sync; returns full report; persists to backtests table) · `GET /api/backtests`
- Broker: `GET /api/broker/status`, `POST /api/broker/connect/start` → {authorizeUrl}, `POST /api/broker/connect/verify {code}`, `GET /api/broker/accounts`, `GET /api/broker/accounts/{k}/balance`, `POST /api/broker/orders/preview`, `POST /api/broker/orders/place`, `PUT /api/broker/orders/{id}/cancel`
- `GET /api/audit?page=` · Errors: JSON `{error, detail}`; validation 400/422; unsafe trade → 422 with block reasons; **/api/status route wrapped so it can never 500 for missing providers.**

## Frontend (src/main/resources/public — index.html + css/app.css + js/{api,ui,views,app}.js)
Hash-router SPA screens: 1 Account setup · 2 Symbol research · 3 Recommendations · 4 Guided ticket (stage-by-stage wizard: thesis→horizon→risk→strategy→strikes→review→confirm) · 5 Portfolio (active/closed tabs, pagination, refresh, realized/unrealized P/L) · 6 Trade detail (payoff chart via inline SVG/canvas — no chart lib, entry/current compare, updated POP, actions) · 7 Unwind/settle/delete confirm flows w/ explicit cash effects · 8 Backtesting form+report · 9 Data status. 
Design: CSS variables; risk palette red/orange/yellow/green (`--risk-danger #d64545`-ish, orange warn, yellow caution, green ok) with accessible fg/bg pairs in light+dark (prefers-color-scheme); responsive (mobile-first, cards→tables at ≥768px); beginner mode toggle (persistent, default ON: plain-language explainer beside every number/decision); persistent risk-mode selector in header; freshness/DELAYED badges everywhere; educational disclaimer footer.

## Testing requirements (acceptance)
- Unit: pricing (BSM vs known values, put-call parity, IV round-trip), payoff/breakevens per family, guardrails (naked call BLOCKED), ledger invariants (sum of ledger == cash; fees separate; reserve release exact), recommendation gating per risk mode, backtest no-look-ahead + coverage reporting.
- Integration (JUnit, random port, fixture mode): /api/status 200 with no keys; ticker change flows; create paper trade → active + buying power reduced; unsafe naked short call → 422, account unchanged; unwind → realized P/L + reserve released; backtest returns coverage/assumptions/skips/confidence. E*TRADE adapter tests against MockWebServer (canned XML/JSON fixtures, OAuth signature asserted).
- Browser/DOM tests asserting visible DOM after each button (not just HTTP 200): if network allows `npx playwright` use it; else Node-based jsdom harness against running server; keep SPA JS jsdom-compatible (no ESM imports required at runtime, attach app to window).

## Env/config keys
`PORT, DB_URL, DB_USER, DB_PASSWORD, DB_PATH (legacy/ETL only), FIXTURES_ONLY, HTTP_TIMEOUT_MS, FEE_PER_CONTRACT_CENTS, FEE_PER_ORDER_CENTS, DEFAULT_STARTING_CASH_CENTS, SNAPSHOT_ENABLED, SNAPSHOT_INTERVAL_HOURS, SNAPSHOT_INITIAL_DELAY_SECONDS, AUTH_ENABLED, OIDC_ISSUER, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET, OIDC_CALLBACK_URL, AUTH_ALLOWED_EMAILS, AUTH_POST_LOGIN_URL, ETRADE_CONSUMER_KEY, ETRADE_CONSUMER_SECRET, ETRADE_SANDBOX, ETRADE_BASE_URL, POLYGON_API_KEY, ALPHAVANTAGE_API_KEY, FRED_API_KEY, CBOE_BASE_URL, STOOQ_BASE_URL, EDGAR_BASE_URL, TREASURY_BASE_URL, FRED_BASE_URL, POLYGON_BASE_URL, ALPHAVANTAGE_BASE_URL` (+ same lowercase.dotted in strikebench.properties; BRAND_NAME/BRAND_TAGLINE too).

## Build order (test-as-you-go)
1. Toolchain → `mvn -q test package` on scaffold (fix anything) 2. pricing engine + tests 3. fixture provider + MarketDataService + status + tests 4. paper core + tests 5. strategy/guardrails + recommend + tests 6. API + integration tests 7. free providers (Cboe/Stooq/EDGAR/Treasury/FRED/Polygon/AV) + tests 8. E*TRADE + tests 9. backtester + tests 10. frontend 11. DOM tests 12. README + final `mvn test package` + run jar smoke test.
