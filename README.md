# StrikeBench

A standalone, **local-first** options strategy **education, paper-trading, and backtesting** app, with
optional live trading through E*TRADE behind heavy safety gates.

> **Educational tool only — not financial advice.** All suggestions are risk-screened, data-backed
> teaching examples with explicit assumptions and caveats. Options involve substantial risk of loss.
> Nothing here promises any profit. Paper trading uses practice money by default.

## Quick start

```bash
mvn test package
java -jar target/strikebench.jar
# open http://localhost:7070
```

Requires JDK 25 and Maven. No API keys needed —
without keys the app runs on keyless free sources (Cboe delayed chains, Stooq EOD candles,
SEC EDGAR filings, US Treasury yields) and falls back to built-in deterministic demo data
(`FIXTURES_ONLY=true` forces demo data everywhere, no network at all).

## What it does

- **An opening page** — fresh accounts land on a welcome experience: what the product is, three one-click paths in (Teach me / I know the basics / Give me the terminal) that set the experience level, a capability tour, and a numbered how-it-works. Always reachable at `#/welcome`.
- **Sector discovery, everywhere** — a sector switcher lives inside the ticker tape (visible on every tab), the Research index is a full **sector explorer** (13 curated sectors → live quote tiles → Research/Ideas one-tap, "make this my universe", "scout this sector"), Home shows a **sector pulse** (per-sector ETF day change), the header has a global symbol search (press `/`), and clicking the brand opens the product welcome page with a live engine-generated example idea.
- **A live universe, everywhere** — pick a sector universe (Tech, Semiconductors, Healthcare, Defense, Staples, Energy, Financials, Industrials, Communications, Utilities, Discretionary, index ETFs, or a custom list) once and it feeds the scrolling **ticker tape** under the header, the scout's default scan list, the dashboard tiles, and symbol suggestions in every input (`GET/PUT /api/universe`, persisted server-side; `GET /api/quotes` batches light quotes).
- **Finance-grade charts** — price history with range pills (1M / 3M / 6M / YTD / 1Y / 2Y / 5Y / MAX), a slide-anywhere crosshair reading the exact date, close, and window change under your cursor (mouse or touch), window high/low and change chips, and red/green coloring by window performance. The payoff chart reads "at $X → P/L $Y" at any price you point at.
- **Research** — quotes, option chains with greeks/IV, price history, IV vs HV, news & filings, freshness labels everywhere (`REALTIME`/`DELAYED`/`EOD`/`MODELED`/`DEMO DATA`/`STALE`).
- **Auto-scout** — scans a universe of optionable tickers (configurable via `AUTO_UNIVERSE`), derives a thesis per symbol from price momentum, keyword-scored news sentiment, and IV-vs-realized volatility, then proposes fitting structures per horizon (0DTE / weekly / monthly): credit structures when options are rich, debit when cheap. Accepts a profit target, max loss, and risk budget, and annotates every candidate against them. Every pick carries its evidence (headlines, momentum numbers, IV/HV) — signals are auditable heuristics, never a black box.
- **Goal-native experiences** — each goal is its own interaction, shaped per experience level: *Buy at a discount* is a **discount ladder** ("Buy at $250 (−2.1%) — you're paid $624 to wait; effectively $243.76. Chance: 43%"), *Sell at a target* is an **exit-rung table** against your actual cost basis, *Protect my shares* is **insurance quotes per floor**, and *Earn income* opens with a board of **your** capital (free cash, shares to rent, what the best idea pays). Learning reads sentences with one recommended rung; Pro gets the dense sortable rung tables. Every rung is a real, fully-screened candidate — one tap into the ticket. Research symbol pages show "What you can do with SYM": live per-goal one-liners computed from real rungs.
- **Goal-first ideas** — every idea flow starts with WHY you're trading: *Trade a view* (directional), *Earn income* (premium against cash or shares), *Buy at a discount* (cash-secured puts at your price), *Sell at a target* (covered calls on shares you hold — "AAPL ran 20%, pay me to be patient about selling at $260"), or *Protect my shares* (protective puts and collars). Hold-based goals read your real position (free shares, average basis) and pick strikes from your target price. Candidates carry goal-specific metrics: modeled **assignment probability** (framed as the *chance of success* when assignment IS the goal), **annualized premium yield** on the capital at risk (quoted only for share-backed structures — never a condor's max-return dressed up as income), and the **effective buy/sell price** (strike ± premium).
- **Filters on what matters** — minimum probability of profit, maximum assignment chance, minimum annualized yield, entry-cost cap, and max loss are hard screens on Ideas and the Scout; anything that fails lands under "Looked at and refused" with the exact reason.
- **Real share holdings** — buy and sell shares in the paper account (fills at the executable ask/bid, weighted-average cost basis, $0 equity commissions). Shares you hold cover short calls: the trade locks them (no new buying power consumed — risk figures then include the shares at today's price), a covered call that finishes in the money **physically delivers** the shares at the strike, and an assigned cash-secured put puts the shares TO you at strike basis. Locked shares cannot be sold until the covering trade closes.
- **Educational ideas** — a recommendation engine that screens 18 defined-risk strategy families by your goal, thesis, horizon, and risk mode. Ranks by a composite of freshness, liquidity, risk:reward, probability of profit, capital efficiency, and event risk — never max profit alone. Every candidate explains *why considered, best upside, biggest risk, and what would invalidate it*; blocked strategies (naked calls & friends) are shown with the reasons they were refused.
- **Guided paper trading** — a step-by-step ticket (thesis → horizon → risk → strategy → strikes → review → confirm) backed by a $100,000 practice account with an exact, append-only cents ledger. Guardrails block undefined risk, stale data, and over-budget trades; warnings flag 0DTE gamma, wide spreads, thin liquidity, earnings, and assignment risk.
- **An experience ladder, not a beginner switch** — a Learning / Confident / Pro control reshapes every
  screen: Learning gets plain-language cards ("Most you can lose"), a tap-to-define glossary, per-strategy
  "How this trade works — and what can go wrong" expandables, a review-step safety checklist, and no 0DTE;
  Pro gets dense data-first screens with EV/liquidity chips, a quick-start ticket, a sortable
  strategy-comparison table on Ideas, position greeks (share-equivalent Net Δ/Γ, $/day theta, $/vol-pt
  vega per trade and summed across the book via `GET /api/portfolio/greeks`), and a custom multi-leg
  builder — which feeds the same preview and guardrail pipeline as guided strategies, so more control
  never means fewer guardrails. Mobile gets a bottom tab bar and single-column reflow.
- **Execution realism** — paper fills happen at the executable side of the book (buys pay the ask, sells receive the bid; one-sided or empty books don't fill), same-day expirations die at 4:00pm ET, options marked below intrinsic value are rejected as stale/dead quotes, and any structure whose computed max loss is $0 is refused as a quote-integrity failure — real markets have no risk-free trades. Trading outside regular hours is allowed but loudly labeled.
- **Portfolio** — live marks, payoff charts, updated probability of profit, unwind/settle at intrinsic/void flows with explicit cash effects, full audit trail.
- **Backtesting** — daily loop with strict no-look-ahead. Pricing tiers: `HISTORICAL_CHAIN` (Polygon) → `MODELED_FROM_UNDERLYING` (Black-Scholes over an HV-derived smile) → `PAYOFF_ONLY`. Stock-hedged families (covered calls, cash-secured puts, protective puts/collars) are supported — stock legs are valued from real candles, and the report counts how many expirations finished with the short strike in the money (assignment). Reports state their mode, confidence, coverage, skipped entries, and assumptions — modeled results are never labeled as real option performance.
- **Live trading (optional, E*TRADE)** — OAuth 1.0a, server-side only. A live order requires: connection + successful preview + the exact typed confirmation *"I understand max loss and this is real money"* + an idempotent client order id. The recommendation engine can never place live orders.

## Configuration

Environment variables (or the same keys lowercase-dotted in `./strikebench.properties`):

| Key | Default | Purpose |
|---|---|---|
| `PORT` | `7070` | HTTP port |
| `BRAND_NAME` | `StrikeBench` | Product name shown in the header and page title |
| `BRAND_TAGLINE` | *(built-in)* | One-line subtitle available to the UI |
| `DB_PATH` | `data/strikebench.db` | SQLite file (a legacy `data/options-lab.db` is auto-migrated once at boot) |
| `MODELS_DIR` | `data/models` | On-device AI assets. The app ships NO model bytes: the user clicks "Enable on-device AI" and the server downloads checksum-pinned Apache-2.0/MIT assets (~121MB) here. Empty = an enable card; absent server support = features hidden. |
| `FIXTURES_ONLY` | `false` | Demo data only, zero network |
| `FEE_PER_CONTRACT_CENTS` | `65` | Commission per contract per leg |
| `FEE_PER_ORDER_CENTS` | `0` | Flat fee per order |
| `DEFAULT_STARTING_CASH_CENTS` | `10000000` | New paper account ($100k) |
| `HTTP_TIMEOUT_MS` | `10000` | Provider timeout |
| `ETRADE_CONSUMER_KEY` / `ETRADE_CONSUMER_SECRET` | — | Enables the E*TRADE adapter |
| `ETRADE_SANDBOX` | `true` | Sandbox vs live E*TRADE |
| `POLYGON_API_KEY` | — | Historical option chains for backtests + candles |
| `ALPHAVANTAGE_API_KEY` | — | Daily adjusted candles |
| `FRED_API_KEY` | — | Risk-free rates (else keyless Treasury XML) |
| `CBOE_BASE_URL`, `STOOQ_BASE_URL`, `EDGAR_BASE_URL`, `EDGAR_DATA_BASE_URL`, `TREASURY_BASE_URL`, `FRED_BASE_URL`, `POLYGON_BASE_URL`, `ALPHAVANTAGE_BASE_URL`, `ETRADE_BASE_URL` | real endpoints | Overridable for tests |

> Operational note: if you rebuild while the app is running, restart it afterwards — a JVM cannot
> reliably serve static files from a jar that was rewritten underneath it. The app detects this
> itself: `GET /api/health` reports `jarChangedSinceBoot`, and the UI shows a red restart banner
> instead of mystery "failed to load" screens. The header also has a theme toggle (Auto / Light /
> Dark, persisted per browser).

Provider priority per data domain: **E*TRADE → Cboe → AlphaVantage/Polygon → Stooq → fixtures**, with
per-domain health on the Data screen (`GET /api/status` never 500s, even with zero providers).

## Architecture

Java 25 + Javalin 7 + SQLite (WAL) + Caffeine; plain HTML/CSS/JS frontend served from the jar — no build step.
Money is **exact**: `BigDecimal` for per-share prices, `long` integer cents for the ledger and balances.
Doubles appear only in the Black-Scholes kernel and model statistics (IV, greeks, probabilities), converted
at the boundary. The ledger is append-only; every row snapshots cash and reserve after application, fees are
separate rows, and the reserve holds each trade's remaining worst case so buying power always drops by exactly
max loss + fees at entry.

```
io.liftandshift
├── config      AppConfig (env > sysprops > properties file > defaults)
├── db          Db (per-op connections, WAL), Migrations
├── model       Quote, Candle, OptionQuote, OptionChain, Leg, Freshness, ...
├── pricing     BlackScholes, ImpliedVol, PayoffCurve, VolSurface, HistoricalVol
├── market      MarketDataService (provider chain + caches + status), providers/*
├── strategy    StrategyFamily, StrategyBuilder, Guardrails
├── recommend   RecommendationEngine (+ Candidate/Rejection/LegView DTOs)
├── paper       AccountService, TradeService, AuditLog (append-only ledger)
├── backtest    Backtester (no-look-ahead daily loop, tiered pricing)
├── broker      OAuth1, ETradeProvider, BrokerService (live-order gates)
└── api         ApiServer (Javalin routes), Main
```

## API

All JSON under `/api`: `status`, `config`, `account`, `account/reset`, `research/{symbol}`
(`/expirations`, `/chain`, `/history`, `/news`), `lookup`, `recommend`, `recommend/auto`, `trades` (preview/create/list/detail/
refresh/unwind/settle/delete), `backtest`, `backtests`, `broker/*`, `audit`. Errors are
`{error, detail}` — validation 400, conflicts 409, unsafe trades 422 with block reasons.

## Testing

```bash
mvn test                 # 163 unit + integration tests (fixture mode, random port, MockWebServer)
cd dom-tests
npm install && npx playwright install chromium
node --test dom.test.js       # 13 browser tests, fixture mode (deterministic)
node --test dom-live.test.js  # 8 browser tests, LIVE keyless mode (needs network)
```

Covered: Black-Scholes vs known values and put-call parity, IV round-trips, payoff/breakevens per strategy
family, ledger invariants (replayed row by row), guardrail blocks (naked call → 422, zero mutation),
risk-mode gating, backtest no-look-ahead (extending the window never changes earlier trades), OAuth 1.0a
against the canonical RFC 5849 signature vector, and full click-through DOM tests of every screen.
