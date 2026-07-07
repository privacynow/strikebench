# StrikeBench (formerly Options Lab) — project memory & handoff

Standalone local-first options strategy **education, paper-trading, backtesting, and optional live-trading** app.
Owner: Ahmedfaraz (babarahmedfaraz@gmail.com). This file is the single source of truth for decisions already made.
**Work style: build incrementally and run `mvn -q test` after every module. Never write large amounts of unverified code.**

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
  assertions all renamed. KEPT DELIBERATELY BRAND-NEUTRAL (reviewed): io.liftandshift package/groupId
  (owner namespace), env var names (PORT/DB_PATH/...), DB table names, id prefixes (tr_/acct_/...).
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
- Remaining/optional follow-ups: E*TRADE sandbox end-to-end with real keys, richer calendar modeling,
  candles-source labeling in /api/research/{symbol}/history (currently unlabeled when fixture serves in
  live mode).

## Non-negotiable decisions (from user)
1. Java package root: **`io.liftandshift`** (groupId `io.liftandshift`, artifact `strikebench` — renamed
   from `options-lab` 2026-07-08 with the StrikeBench rebrand).
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

## Architecture (packages under io.liftandshift)
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
`PORT, DB_PATH, FIXTURES_ONLY, HTTP_TIMEOUT_MS, FEE_PER_CONTRACT_CENTS, FEE_PER_ORDER_CENTS, DEFAULT_STARTING_CASH_CENTS, ETRADE_CONSUMER_KEY, ETRADE_CONSUMER_SECRET, ETRADE_SANDBOX, ETRADE_BASE_URL, POLYGON_API_KEY, ALPHAVANTAGE_API_KEY, FRED_API_KEY, CBOE_BASE_URL, STOOQ_BASE_URL, EDGAR_BASE_URL, TREASURY_BASE_URL, FRED_BASE_URL, POLYGON_BASE_URL, ALPHAVANTAGE_BASE_URL` (+ same lowercase.dotted in strikebench.properties; BRAND_NAME/BRAND_TAGLINE too).

## Build order (test-as-you-go)
1. Toolchain → `mvn -q test package` on scaffold (fix anything) 2. pricing engine + tests 3. fixture provider + MarketDataService + status + tests 4. paper core + tests 5. strategy/guardrails + recommend + tests 6. API + integration tests 7. free providers (Cboe/Stooq/EDGAR/Treasury/FRED/Polygon/AV) + tests 8. E*TRADE + tests 9. backtester + tests 10. frontend 11. DOM tests 12. README + final `mvn test package` + run jar smoke test.
