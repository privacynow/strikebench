# StrikeBench — Genesis Prompt (v2, distilled from a full build + hardening cycle)

You are building **StrikeBench** (née Options Lab — that name is used by 8+ existing options
products and was retired) from scratch: a standalone, local-first options strategy
**education, paper-trading, backtesting, and auto-recommendation** app, with optional live trading
through E*TRADE behind heavy safety gates. Owner: Ahmedfaraz (babarahmedfaraz@gmail.com).

This prompt is the distillation of a complete previous build: the product spec, every architectural
decision, every data-source quirk discovered empirically, and — most importantly — **every bug class
that was found the hard way, rewritten as a requirement so it is designed-in rather than patched-in.**
Where a rule below seems oddly specific, it is a scar. Treat every MUST as non-negotiable.

---

## 1. Mission and framing

- Educational tool first. Every suggestion is a "risk-screened, data-backed educational candidate"
  with confidence, assumptions, and caveats. **Never promise profits.** "Not financial advice"
  disclaimer visible on every screen and in every recommendation payload.
- An **experience ladder** (Learning / Confident / Pro — default Learning, persistent) reshapes the
  information architecture per level; see §11. Plain-language explainers accompany every number and
  decision at Learning, collapse to one line at Confident, disappear at Pro.
- A **strategy-intent taxonomy** (`StrategyIntent`: DIRECTIONAL / INCOME / HEDGE / ACQUIRE / EXIT)
  is first-class, not a garnish: options are also tools for income, protection, and trading the
  SHARES (sell a covered call to exit a winner at a target price; sell a cash-secured put to buy at
  a discount). Every family maps to the intents it serves; every idea flow starts with "what are you
  trying to do?"; candidates, the scout, the portfolio, and the backtester all carry the intent. This
  requires real **equity holdings** in the paper account (see §4.5) — build both together.
- Data freshness labels everywhere: `REALTIME / DELAYED / EOD / MODELED / FIXTURE("DEMO DATA") / STALE / MISSING`.
- Paper account: $100,000 default (env-configurable), editable before first trade; reset requires
  confirmation and `force` once trades exist.
- Port 7070. DB `data/strikebench.db`. Fees: $0.65/contract/leg (`FEE_PER_CONTRACT_CENTS=65`) + $0/order.
- Branding is CONFIG (`BRAND_NAME`/`BRAND_TAGLINE`, default StrikeBench) flowing through /api/config
  to title/header/copy; internal identifiers (properties file `strikebench.properties`, jar name,
  localStorage `strikebench.*` keys, EDGAR User-Agent) stay consistent with the product name, while
  genuinely brand-neutral internals (env var names, table names, id prefixes, the `io.liftandshift`
  Maven groupId owner namespace) stay neutral. The Java package root is `io.liftandshift.strikebench`.
- The app must be FULLY functional with **zero API keys and zero network** (deterministic fixtures),
  degrade gracefully through free keyless sources, and get better with keys. Live trading is the ONLY
  feature that requires credentials.

## 2. Non-negotiable engineering decisions

1. Java 25 (pom property `java.release`, overridable `-Djava.release=21`), Maven, single fat jar:
   `mvn test package` → `java -jar target/strikebench.jar`. Java package root
   `io.liftandshift.strikebench` (Maven groupId stays `io.liftandshift`), artifact `strikebench`.
2. **Money discipline**: money is ONLY `BigDecimal` (per-share prices, scale ≤ 4) or `long` integer
   cents (ledger, balances, contract totals). Never float/double for money. Doubles allowed only for
   non-money ratios (IV, greeks, probabilities) and inside the Black-Scholes kernel; convert at the
   boundary via a `Money` util (`toCents(BigDecimal)`, `centsFromPrice(perShare, shares)` with
   HALF_UP, `PRICE_SCALE=4`).
3. Stack (pin these versions unless newer are verified): Javalin 7.2.2 (Jetty 12), Jackson 2.19.0,
   sqlite-jdbc 3.49.1.0, Caffeine 3.2.0, SLF4J 2.0.17 / Logback 1.5.18, JUnit 5.11.4, AssertJ 3.27.3,
   MockWebServer 4.12.0 (NOT WireMock — Jetty classpath clash), Shade 3.6.0. Hand-rolled migration
   runner, not Flyway.
4. Frontend: plain HTML/CSS/JS SPA, **no build step**, served from the jar (`/public` on classpath).
   No secrets in the frontend. All JS attached to `window` (no runtime ESM) so a jsdom harness can
   drive it.
5. Jackson mapper: `FAIL_ON_UNKNOWN_PROPERTIES=false`, **`USE_BIG_DECIMAL_FOR_FLOATS=true`**,
   ISO dates (JavaTimeModule, no timestamps), NON_NULL inclusion. Register it as Javalin's mapper via
   `cfg.jsonMapper(new JavalinJackson(mapper, true))`. Remember: NON_NULL means "null field" tests
   must check `path(...).isMissingNode()`, not `get(...).isNull()`.
6. API DTOs: plain types + BigDecimal only. No `java.time` types in wire DTOs — ISO strings
   (a `LegView(action,type,strike,expiration,ratio,entryPrice)` string record is the wire form of a leg).
7. All timestamps/clocks are injected (`java.time.Clock`) — every service takes a Clock; tests use
   `Clock.fixed`. This is what makes the entire suite deterministic.

## 3. Architecture (packages under io.liftandshift.strikebench)

```
config    AppConfig — env > sysprops > ./strikebench.properties (UTF-8 reader — Properties.load(InputStream)
          is Latin-1 and garbles em-dashes) > defaults; ALSO a test constructor
          AppConfig(Map<String,String> overrides) where overrides beat everything.
db        Db (per-op connections, WAL, foreign_keys ON, busy_timeout 5000, tx helper),
          Migrations (ordered classpath list).
util      Json (shared mapper + canonical(o) with ORDER_MAP_ENTRIES_BY_KEYS for payload identity),
          Ids (prefixed random ids), Money.
model     Records: Quote, Candle, OptionQuote, OptionChain, Leg, NewsItem, SymbolMatch, Freshness
          (with static worse(a,b) ranking), OptionType, LegAction.
pricing   BlackScholes (double kernel: price/delta/gamma/theta/vega, normCdf via Abramowitz-Stegun),
          ImpliedVol (bisection), PayoffCurve (piecewise-linear, exact BigDecimal; knots, analytic
          breakevens, unbounded detection, lognormal probProfit, Simpson EV in log-space),
          VolSurface (parametric smile: skew -0.35·m, wings 1.4·m², term damping),
          HistoricalVol (close-to-close, annualized ×√252).
market    MarketHours (RTH 9:30–16:00 ET; contractDead(exp, now) = past 16:00 ET on expiry day),
          Domain enum, ProviderStatusInfo, CandleSeries (candles + source + freshness),
          MarketDataService (provider chain, caches, freshness gates, per-domain status),
          MarketDataMarks (bridges market data to the paper core's MarksSource),
          providers/* (one class per source + Http helper).
strategy  StrategyFamily (22 families with thesis-fit/definedRisk/blockedByDefault/needsStock/
          multiExpiration/riskRank + primaryIntent/intents — incl. PROTECTIVE_PUT),
          StrategyIntent (DIRECTIONAL/INCOME/HEDGE/ACQUIRE/EXIT with display+blurb),
          StrategyBuilder (delta-targeted leg construction + BuildHints{targetPrice, sharesHeld},
          shared by engine and backtester), Guardrails (ALLOW/WARN/BLOCK; Proposal carries
          lockedShareLots), CoverageCheck (structural short-coverage matching + callCoverLotsNeeded),
          Verdict.
recommend RecommendationEngine (+ Candidate/Rejection/LegView), SignalEngine (momentum + keyword
          sentiment + IV-vs-HV), AutoRecommender (universe scout with targets).
paper     Account, LedgerEntry, TradeRecord (intent + sharesLocked), TradePreview, MarksSource (port),
          AccountService, TradeService, PositionsService (equity holdings, locks, assignment hooks),
          AuditLog, TradeRejectedException.
backtest  Backtester (daily loop, tiered pricing, honest reporting).
broker    OAuth1 (injectable nonce/timestamp), SecretsStore (secrets table), ETradeProvider
          (BrokerageProvider + MarketDataProvider), BrokerService (live-order gates).
api       ApiServer (all routes inside Javalin.create), TradeView, Main (with class preloading).
```

DB schema (`V1__init.sql` + `V2__positions_intent.sql`): `accounts, trades, ledger, trade_marks,
audit, secrets, live_orders, backtests, positions` — all money INTEGER cents; trades carry
`legs_json`, `breakevens_json`, `entry_snapshot_json`, `max_loss_cents` (finite, >0 for accepted
trades except share-covered ones where it is the incremental cash risk), `max_profit_cents`
(NULL = uncapped/model-dependent), `intent`, `shares_locked`. SQLite cannot ALTER a CHECK — adding
ledger types means recreating the table and copying rows (ids preserved) in the migration.

## 4. The money engine — invariants that MUST hold (each was violated once; never again)

### 4.1 Ledger
- Append-only. Types: `DEPOSIT|RESET|PREMIUM_OPEN|PREMIUM_CLOSE|SETTLEMENT|FEE|RESERVE_HOLD|RESERVE_RELEASE|ADJUSTMENT|STOCK_BUY|STOCK_SELL`.
- Every row snapshots `cash_after_cents` and `reserved_after_cents`. A test MUST replay the whole
  ledger row-by-row and assert both running balances match every row AND the account.
- Fees are separate FEE rows, never folded into premiums.
- **Reserve = max(0, maxLoss + entryNet)**: debit trades reserve 0 extra (the premium already left
  cash and caps the loss); credit trades reserve the gross width. Consequence (assert it!):
  buying power drops by exactly `maxLoss + fees` at entry, both cases. Release is exact, computed
  from the trade's own RESERVE_* ledger rows.
- A rejected trade writes an audit row and NOTHING else. Assert account bytes-identical after any 4xx.
- Reset keeps the ledger append-only: void open trades (release reserves), then a single RESET row
  absorbs the cash delta.
- delete/void reverses each cash row with mirror ADJUSTMENT rows. It is a user-decided practice-only
  affordance — label it loudly in the UI ("real brokers have no undo; prefer Unwind").
- NEVER write through a second DB connection inside an open transaction (SQLite WAL →
  SQLITE_BUSY_SNAPSHOT). Post-commit audit writes are best-effort (catch + warn): a committed trade
  must never report failure because an audit insert failed (retry would double-open).

### 4.2 Execution realism (the "riskless TSLA collar" incident, condensed)
The single worst bug shipped: at 9:24pm ET a user bought a collar whose legs expired *that same day*
(dead since 4pm), with an ITM put quoted $0.005 against $0.53 intrinsic (zombie after-hours book),
filled at mid → computed max loss $0.00, POP 100%, and candidates sized 5× into "$208K on a $100K
account". The ledger was exact; the inputs were corpses. Therefore, from day one:

- **Fills at the executable side only**: buys pay the ask, sells receive the bid. Marks carry
  bid/ask/mid (`LegMark.executable(action)`); the mid is display-only. One-sided, empty, or
  **crossed (bid > ask)** books do not fill — crossed books otherwise mint money (buy ask, sell
  higher bid).
- **Contracts die at 16:00 ET on expiration day** — a calendar-date check is NOT enough
  (`MarketHours.contractDead`). Dead contracts cannot be opened.
- **Quote integrity**: an option marked below `intrinsic − max($0.05, 2%·intrinsic)` against the
  same feed's underlying is an impossible price → block. A structure whose computed max loss ≤ $0
  is a data error, never an opportunity → block (engine surfaces it as a rejected candidate with the
  real reason; backtester skips the entry).
- Outside RTH, trading is allowed (educational) but previews/creates carry an explicit
  "market is closed — quotes are leftovers" warning. Holidays are NOT modeled — document that.
- Unwind closes at the opposite executable sides (long→bid, short→ask). Unrealized/marks
  (`trade_marks`) are ALSO valued at executable closing sides so displayed P/L matches what an
  unwind actually pays (UI labels "before close fees").
- Entry snapshots record fill/bid/ask/mid/iv/freshness per leg — they are the forensic record.
- Server-side qty cap: 100 contracts. Manual trades exceeding the header risk-mode budget
  (1%/1%/2%/5% of BP for learning/conservative/balanced/aggressive) get a loud WARN, not a block
  (deliberate freedom, labeled).

### 4.3 Structural risk
- Undefined-risk families (naked call/put, short straddle/strangle) are blocked by default
  EVERYWHERE and shown only as educational rejected[] examples with reasons.
- The payoff curve detects unbounded loss for same-expiration structures. For
  **multi-expiration** positions the expiry curve is meaningless: credit multi-exp is blocked;
  debit multi-exp requires a **structural CoverageCheck** — every short option unit must be matched
  (exhaustive matching, ratios expanded to units) by a long of the same type with
  expiration ≥ short's AND strike ≤ short's (calls) / ≥ (puts), or long stock for calls. This is
  what catches reverse calendars, inverted diagonals, and net-short ratio "debits" that a premium
  sign test misreads (a net-debit position CAN be net-short a leg). Covered debit multi-exp:
  maxLoss = net debit, maxProfit/POP/breakevens = null/empty ("model-dependent"), reserve 0.
- Guardrails (advisory, also enforced at create): block stale/missing marks, chainless symbols,
  buying-power breaches, expired contracts, below-intrinsic quotes, zero-max-loss; warn on 0DTE
  gamma, wide spreads (>10% of mid), low OI (<100)/volume (<10), DELAYED/EOD data, earnings
  proximity (derive from news keywords — there is no keyless earnings calendar; ex-div has no
  keyless source, keep the parameter but document it as false).
- When the API layer runs Guardrails on raw request legs, it MUST price them from fetched quotes
  first — request legs may omit entryPrice, and zeros make debit diagonals look like credits.

### 4.4 Settlement (four bugs lived here)
- Settle only when every leg is DEAD per MarketHours (16:00 ET gate) — never intraday on expiry day
  (else: cash out at intrinsic vs yesterday's close with hours of gamma left).
- **Each leg settles at the underlying close ON ITS OWN expiration date** — valuing a near leg at
  the far leg's expiry erases the entire inter-expiry move (a $1,200 assignment loss silently
  vanished this way).
- `closeOn(symbol, date)` must return the close OF that exact date (candle.date == date), never
  "most recent before" (that substitutes yesterday's close and fabricates the overnight gap).
- If the expiry close is unavailable: reject until the calendar day after expiry, then allow a
  **loudly labeled** fallback to the current quote (memo on close_reason + WARN audit row, written
  post-commit). This keeps keyless-live users unstuck while making the imprecision explicit.
- Cash settlement charges no close fees (documented assumption).

### 4.5 Equity holdings, share-covered calls, physical assignment
- `positions` table: (account, symbol, shares, avg_cost_cents) with weighted-average basis on buys
  (basis unchanged on sells; realized P/L vs basis reported per sell). Buys fill at the executable
  ASK, sells at the BID, through STOCK_BUY/STOCK_SELL ledger rows; $0 equity commissions. Account
  reset deletes positions (the RESET row absorbs the cash).
- **Held shares cover short calls**: a trade opened with `useHeldShares` carries option legs only and
  locks `shares_locked = uncoveredCallLots × 100 × qty` on the trade row (free = held − Σ locks of
  ACTIVE trades; verify INSIDE the create transaction or two trades will claim one lot). Locked
  shares cannot be sold; the lock dies with ACTIVE status. CoverageCheck gets an overload taking
  extra held lots, and `callCoverLotsNeeded(legs)` returns the minimal lots that fix the CALL side
  (-1 when shares can't fix it — puts and short stock are never share-coverable).
- **Two risk numbers, both honest**: the ledger's `maxLoss`/reserve track only NEW cash the trade can
  lose (covered call → 0, covered collar → its debit; reserve = 0 for share-covered trades), while
  POP/breakevens/maxProfit/payoff come from the COMBINED curve (option legs + a synthetic stock leg
  at today's price) and a `combinedMaxLossCents` disclosure. Never let maxLoss=0 read as "risk-free":
  label it "no NEW cash at risk — your shares keep their downside".
- **Physical assignment at settle** where it is the point of the strategy: a locked-shares covered
  call finishing ITM sells the locked shares at the STRIKE (STOCK_SELL row with stock-P/L-vs-basis
  memo, position decremented); an ITM CASH_SECURED_PUT buys shares at the strike (basis = strike —
  the premium already sits in option income; note this differs from the tax convention of
  strike−premium). Everything else (spreads, longs, trades whose stock leg was bought inside the
  trade) cash-settles at intrinsic. Total equity is identical either way; only the cash/shares split
  differs — and the CSP's strike-cash reserve guarantees assignment can never overdraw cash.

## 5. Data sources — endpoints, shapes, and field-tested quirks

Provider chain priority per domain: **E*TRADE → Cboe → AlphaVantage/Polygon → Stooq → Fixture**
(fixture is ALWAYS last resort and always labeled). Per-domain health in `ProviderStatusInfo`;
`/api/status` never 500s even with zero providers. Providers return data, empty (definitively
nothing), or throw (the service records ERROR and falls through). Caffeine caches in the service:
quote 15s, chain 60s, expirations 60s, candles 1h (as `CandleSeries` WITH source+freshness), news
5m, rates 1h. Freshness gates: REALTIME/DELAYED older than 10min (quotes)/30min (chains) → STALE.

### Cboe (keyless, delayed chains — the workhorse)
- `GET https://cdn.cboe.com/api/global/delayed_quotes/options/{SYM}.json`
- Payload: `data.{current_price, close, bid, ask, prev_day_close, volume, options[]}`; each option:
  `option` (OCC-ish id), `bid/ask/iv/open_interest/volume/delta/gamma/theta/vega/last_trade_price`.
- OCC parsing: root length varies (SPXW vs SPX) — strip the known symbol when it prefix-matches,
  else anchor on the fixed-width 15-char tail (yyMMdd + C|P + 8 digits of strike×1000). Skip
  malformed ids, never hard-fail.
- **The payload is one multi-MB file per symbol carrying ALL expirations.** Cache the parsed
  payload per symbol (~60s) inside the provider — without this, quote+expirations+chain(near)+
  chain(far) re-download it 4× per screen and a 10-symbol scout takes 23s (measured; 4s after).
- **404 AND "403 AccessDenied" (S3-style XML) both mean "unknown symbol"** — treat as empty, not
  provider failure (else every typo marks Cboe unhealthy).
- After hours the feed serves zombie books for contracts that expired that afternoon (bid 0 /
  ask 0.01 on ITM options) and `current_price` is a delayed after-hours mark — this is exactly what
  the execution-realism rules exist for.
- Index roots (SPX → `_SPX`) are not mapped — documented limitation, or map underscore roots.

### Stooq (keyless EOD candles)
- `GET https://stooq.com/q/d/l/?s={sym-lower}.us&i=d` → CSV `Date,Open,High,Low,Close,Volume`.
- **QUIRK: serves a JavaScript anti-bot interstitial to non-browser HTTP clients** — treat any
  non-CSV body ("No data", HTML) as empty and fall through. In practice keyless live mode gets NO
  real candles (→ CandleSeries provenance below matters).

### SEC EDGAR (keyless filings-as-news)
- TWO hosts, both config-overridable: ticker map `https://www.sec.gov/files/company_tickers.json`
  (`{"0":{"cik_str":320193,"ticker":"AAPL","title":...},...}` — cache it), submissions
  `https://data.sec.gov/submissions/CIK{10-digit-zero-padded}.json` (`filings.recent` parallel arrays).
- **MUST send `User-Agent: OptionsLab/1.0 (email)`** on every request or get blocked.
- ETFs (SPY) often absent from the ticker map → empty, fall through.

### US Treasury / FRED (risk-free rates)
- Treasury (keyless): daily yield-curve XML at
  `.../interest-rates/pages/xml?data=daily_treasury_yield_curve&field_tdr_date_value={year}` —
  parse LAST entry's `d:BC_1MONTH/3MONTH/6MONTH/1YEAR` (percent) with regex; nearest tenor by days.
- FRED (key): `/fred/series/observations?series_id={DGS1MO|DGS3MO|DGS1}&api_key=...&file_type=json&sort_order=desc`
  — skip `"."` values. Blank key → no HTTP call at all.

### Polygon (key; historical chains for backtests + candles)
- Contracts: `/v3/reference/options/contracts?underlying_ticker=X&as_of=DATE[&expiration_date=]&limit=...&apiKey=`
- Per-contract day bar: `/v2/aggs/ticker/O:XXXX/range/1/day/{d}/{d}?apiKey=` (cap contracts ~60/chain).
- Underlying candles: `/v2/aggs/ticker/X/range/1/day/{from}/{to}?adjusted=true&sort=asc`; `t` is
  epoch millis UTC.

### Alpha Vantage (key; adjusted candles)
- `/query?function=TIME_SERIES_DAILY_ADJUSTED&symbol=X&outputsize=full&apikey=`
- **All numbers arrive as JSON strings** — parse `new BigDecimal(node.asText())`, NOT
  `decimalValue()` (returns 0 on text nodes). Use "5. adjusted close". A body with
  `Note`/`Information`/`Error Message` and no series = rate limit → THROW (fall through), don't
  return empty.

### E*TRADE (keys; market data + live orders)
- OAuth 1.0a HMAC-SHA1, server-side only, injectable nonce/timestamp for tests; verify the signer
  against the canonical OAuth vector (photos.example.net → `tR3+Ty81lMeYAr/Fid0kMTYa/WM=`).
- Flow: `GET /oauth/request_token` (oauth_callback=oob) → authorize URL
  `https://us.etrade.com/e/t/etws/authorize?key={ck}&token={rt}` → user pastes verifier →
  `GET /oauth/access_token`. **Tokens die at midnight US/Eastern** — store token+secret+ET-date in
  the local `secrets` table; connected() compares dates.
- Bases: sandbox `https://apisb.etrade.com`, live `https://api.etrade.com`, override for tests.
- Endpoints + response roots we mapped: `/v1/market/quote/{sym}.json` (`QuoteResponse.QuoteData[].All`,
  `quoteStatus` REALTIME|DELAYED), `/v1/market/lookup/{q}.json`, `/v1/market/optionexpiredate.json?symbol=`
  (`ExpirationDate[]{year,month,day}`), `/v1/market/optionchains.json?symbol=&expiryYear=&expiryMonth=&expiryDay=&includeWeekly=true`
  (`OptionPair[]{Call,Put}` with `strikePrice/bid/ask/lastPrice/volume/openInterest/OptionGreeks{iv,delta,...}`, `osiKey`, `nearPrice`),
  `/v1/accounts/list.json`, `/v1/accounts/{k}/balance.json?instType=BROKERAGE&realTimeNAV=true`
  (`BalanceResponse.Computed{cashBalance,cashBuyingPower,RealTimeValues.totalAccountValue}`),
  `/v1/accounts/{k}/portfolio.json`, preview/place/cancel/list orders
  (`PreviewOrderResponse.PreviewIds[].previewId`, `PlaceOrderResponse.OrderIds[].orderId`,
  wrap payloads as `{"PreviewOrderRequest": {...}}` / `{"PlaceOrderRequest": {...+PreviewIds+clientOrderId}}`).

### Fixture provider (deterministic demo)
- Symbols: AAPL 255.30 (iv .28, step 2.5), SPY 562.10 (.15, 5, has 0DTE), QQQ 484.75 (.20, 5, 0DTE),
  TSLA 321.50 (.55, 5), VTSAX non-optionable. Clock-injected; everything a pure function of
  (symbol, date, strike) + seeded RNG for volume noise only.
- Chains: ATM ±10 strikes, BSM mids over the VolSurface smile, bid/ask = mid ∓ max(0.02, 3%·mid)/2,
  greeks, OCC symbols. **Chains exist ONLY for listed expirations** (next 8 Fridays; +today for
  SPY/QQQ weekdays) — fabricating a chain for an arbitrary date lets people trade unlisted/expired
  contracts.
- Candles: ~3y seeded random walk pinned to end exactly at the fixture price (deterministic across
  instances). News: 3 generic + per-symbol sentiment-varied headlines (AAPL beats+probe, SPY rally,
  QQQ surge, TSLA recall+record) so the scout demos offline. Also implements
  HistoricalOptionsProvider (MODELED chains from the candle walk) and RatesProvider (4%).

### Candle provenance (a whole bug class)
`MarketDataService.candleSeries()` returns candles + source + freshness. In LIVE mode, fixture
candles standing in for real history MUST be labeled everywhere they flow: `/history` response
(`demo` flag → UI banner), HV chip "(demo)", SignalEngine rationale suffix + data-quality cut,
Backtester note + confidence "none (demo data)", and settlement's closeOn REFUSES fixture closes in
live mode (falls back to the labeled current-quote path).

## 6. HTTP API (all JSON under /api)

- `GET /api/status` — `{ok, asOf, fixturesOnly, domains:{DOMAIN:[ProviderStatusInfo]}}`.
  **Wrapped so it can never 500.**
- `GET /api/config` — port, fixturesOnly, fees, defaultStartingCash, disclaimer.
- `GET /api/account` (+ recent ledger), `POST /api/account/reset {startingCashCents, confirm, force}`.
- `GET /api/research/{symbol}` (quote, ivAtm, hv30 + historyDemo flag, expirations, SPY/QQQ
  benchmarks), `/expirations`, `/chain?expiration=` (404 for unknown/unlisted — never fabricate),
  `/history?range=1m|3m|6m|1y|2y` (normalize invalid ranges, echo the NORMALIZED value; include
  source/freshness/demo), `/news`; `GET /api/lookup?q=`.
- `POST /api/recommend` (manual thesis), `POST /api/recommend/auto` (scout; empty body = defaults).
- `POST /api/trades/preview` (never mutates; exact before/after cash/reserve/BP + guardrail verdict),
  `POST /api/trades` (201; guardrails enforced), `GET /api/trades?status=&page=&size=`,
  `GET /api/trades/{id}` (detail: current mark computed WITHOUT persisting, marks history, audit,
  payoff grid for charting — empty for multi-exp), `POST .../refresh` (writes ONE trade_marks row,
  never cash), `POST .../unwind {confirm:true}`, `POST .../settle {confirm:true}` (explicit confirm
  REQUIRED — `!Boolean.FALSE.equals(null)` fabricating consent was a real bug),
  `DELETE .../{id}?confirm=true`.
- `POST /api/backtest` (sync, persists), `GET /api/backtests`, `GET /api/backtests/{id}`.
- Broker: `GET /api/broker/status`, `POST /api/broker/connect/start|verify`, accounts/balance/
  positions/orders, `POST /api/broker/orders/preview|place`, `PUT /api/broker/orders/{id}/cancel`.
- `GET /api/audit?page=`.
- **Error contract** (a fuzz pass found every gap here): JSON `{error, detail}` always.
  400 = IllegalArgumentException + DateTimeParseException + **JacksonException** (malformed/empty/
  wrong-typed bodies must NEVER 500 or leak parser internals); 404 = NoSuchElementException
  (missing trade/backtest ids) + unmatched /api paths (but the 404 mapper must NOT clobber
  handler-written bodies — use a context attribute); 409 = IllegalStateException; 422 =
  TradeRejectedException with `reasons[]`; 500 = everything else, with a "jar changed on disk —
  RESTART" hint when applicable (see §10).

## 7. Recommendation engine, signals, auto-scout

- **StrategyFamily riskRank** gates by mode: learning(1) → long calls/puts, debit spreads, covered
  call; conservative(2) → + credit spreads, CSP, collar, iron condor; balanced(3) → + butterflies,
  calendars; aggressive(4) → + diagonals. Blocked-by-default families are rank 99 and appear only
  in rejected[].
- Builders (shared StrategyBuilder): delta-targeted — long ~0.50Δ, credit shorts ~0.27Δ, condor
  ~0.25Δ wings ±1 step, flies ATM ±2 steps, covered call 0.30Δ, collar 0.25Δ; verticals 1–2 steps.
- Horizon → expiration: 0DTE/week/month/quarter → target 0/7/35/90 days, nearest listed; 0DTE only
  when explicitly allowed; scout additionally DROPS "0DTE" candidates whose legs don't actually
  expire today, with a note.
- **Candidates are priced at executable sides, not mids** (learners must see fill-realistic numbers);
  structures with one-sided/crossed legs are unbuildable.
- Sizing: budget = min(riskPct·BP, maxLossCents param); qty = clamp(budget/unitMaxLoss, 1, 5) AND
  clamp by CASH for debits (unit debit ≤ BP) — without the cash cap the engine suggested 5× collars
  ≈ $208K on a $100K account.
- Composite score (never max-profit alone): 0.15 freshness + 0.20 liquidity(worst spread/15%) +
  0.15 risk:reward(cap 3×) + 0.25 POP + 0.10 capital efficiency + 0.15 event safety. Confidence =
  0.40 fresh + 0.35 liquidity + 0.25 model (0.4 multi-exp / 0.5 IV-placeholder / 0.9 modeled).
- Every candidate: `whyConsidered / bestUpside / biggestRisk / wouldInvalidate / beginnerExplanation`
  + warnings; results include rejected[] with reasons and a disclaimer stating POP/EV/breakevens are
  **pre-commission, zero-drift model outputs**; a 30%-IV placeholder is disclosed when no IV exists.
- **Intent flows** (`Request.intent` + `Holdings{sharesOwned(free), costBasisCents, targetPriceCents}`
  + `Filters{minPop, maxAssignmentProb, minAnnualizedYieldPct, maxCostCents}`): family selection by
  `family.servesIntent(intent)` instead of thesis-fit (explicit thesis narrows further); targetPrice
  steers the short strike (covered call ≥ target sell price, CSP ≤ target buy price); holdings with
  ≥100 free shares build stock-hedged families WITHOUT the stock leg (`BuildHints.sharesHeld`);
  ACQUIRE caps by BUYING POWER not the risk-% (a CSP reserves the full strike by design — say so in a
  note) and sizes to the shares the user asked for, default one lot. Filters are hard screens whose
  failures land in rejected[] with the exact numbers.
- **Intent metrics on every candidate**: `assignmentProb` = risk-neutral N(d2)/N(−d2) per DISTINCT
  short strike summed and capped at 1 (an ATM iron butterfly is honestly ~100%); `annualizedYieldPct`
  = credit/collateral × 365/DTE quoted ONLY for share-backed premium (CC on share value, CSP on
  strike cash, collar) — annualizing a narrow condor's max return-on-risk produces four-digit
  "yields" that are really low-POP best cases (bug found in review; that is R:R's job);
  `effectivePrice` = strike ± premium/share; `intentNote` frames the numbers against the user's
  goal, and for ACQUIRE/EXIT presents assignment as the CHANCE OF SUCCESS, not a risk.
- **SignalEngine** (deterministic, auditable — never a black box): momentum = clamp(ret20d/5%, ±1);
  keyword sentiment over headlines (pos/neg stem lists, per-headline majority, score =
  (pos−neg)/scored, keep the matched headlines as evidence); IV/HV ratio → RICH >1.25 / CHEAP <0.85;
  event risk from earnings/guidance/results/fda/merger keywords; direction = 0.6·momentum +
  0.4·sentiment; |direction| ≥ 0.25 → BULLISH/BEARISH, else CHEAP+event → VOLATILE else NEUTRAL;
  confidence = 0.65·strength + 0.35·dataQuality (demo history cuts quality). Rationale strings
  carry the actual numbers.
- **AutoRecommender**: universe from `AUTO_UNIVERSE` (fixture list in demo mode; ~10 liquid names
  live), scanned CONCURRENTLY (virtual threads — serial live scans took 23s); rank by
  0.5·confidence + 0.3·|log(ivHv)|/log2 + 0.2·liquidity; top N picks × requested horizons through
  the engine; re-score ±8/−4 for vol fit (RICH→credit, CHEAP→debit); annotate every candidate
  against `targetProfitCents` ("covers / cannot reach / uncapped-possible"); skipped[] explains
  every excluded symbol; profit targets note: "aspirations, not predictions".
- Scout `intents[]`: DIRECTIONAL rides the derived thesis; INCOME/ACQUIRE scan the ranked universe
  under that intent (no thesis passed); **EXIT/HEDGE scan the user's HOLDINGS, not the universe** —
  the question is "which holding should I harvest or protect". The API layer injects
  `HoldingInfo(symbol, freeShares, avgCostCents)` from real positions; with no 100-share lots the
  scout says "buy shares first" instead of inventing.

## 8. Backtester (honesty is the feature)

- Daily loop, STRICT no-look-ahead: day-D decisions use only data ≤ D. PIN IT with the test
  "extending the window never changes earlier trades' entries/exits".
- Pricing tiers: HISTORICAL_CHAIN (Polygon) > MODELED_FROM_UNDERLYING (BSM over HV30 + VolSurface
  smile, internal synthetic chain when no provider) > PAYOFF_ONLY (flat 30% IV entry, intrinsic
  only). Report the WORST tier used across the run (reporting the best mislabeled 95%-modeled runs
  as HISTORICAL). Confidence: high/medium/low — and **"none (demo data)"** with a prominent note
  when the underlying candles are fixtures in live mode.
- Entries at executable bid/ask + slippage haircut (default 0.5%/leg) + per-contract fees; a
  cash/reserve gate mirrors the paper engine (skip "insufficient buying power" into skipped[]).
- Expiry settles at the close dated ≤ expiration (never a later bar); WINDOW_END forced exits pay
  close fees and are EXCLUDED from winRate/avgRoR/sampleSize (they're report-boundary artifacts).
- Report: mode, confidence, coverage (weekday count minus ~holiday tolerance: only note a shortfall
  below 90%), sampleSize, winRate, avgReturnOnRisk, maxDrawdown, worstTrade, full trades + skipped
  lists, assumptions map (slippage, fees, IV model, rate, settlement, fills), equity curve
  (ending == starting + Σpnl — assert it), disclaimer ("modeled ≠ real option performance").
- Reject upfront: blocked-by-default families, multi-exp + stock-hedged families (until modeled
  properly), from ≥ to, nonpositive starting cash.

## 9. Live trading gates (real-money adjacent — maximum paranoia)

- The recommendation engine has NO code path to order placement.
- Placement requires ALL of: configured + connected; a `previewId` that matches a locally recorded
  preview **for the same account with byte-identical canonical payload** (`Json.canonical`,
  map-key-ordered) **within a 120s TTL**; the EXACT typed confirmation
  `"I understand max loss and this is real money"`; an idempotent `clientOrderId` — same id + same
  payload replays the recorded result WITHOUT re-sending; same id + different payload is a hard
  409-class error (silent no-op = user thinks the edited order went out).
- Cancels are asynchronous REQUESTS that can lose the race to a fill: record `CANCEL_REQUESTED`,
  never terminal `CANCELLED`; tell the user to confirm via the orders list.
- configured() gate runs before any other validation. Persist every order state transition in
  `live_orders` with the canonical payload.

## 10. Infrastructure lessons (each cost real debugging time)

1. **Never serve a JVM whose jar was rewritten underneath it.** Symptoms: lazy classloads fail;
   failures inside Javalin's exception pipeline make its task loop re-queue error handling forever —
   threads spin at 100% CPU until the pool wedges (this specific cascade wedged servers and looked
   like "the app randomly dies"). Defenses to build in: (a) `Main.preloadAllClasses()` — iterate own
   jar entries, `Class.forName(name, false, loader)` everything (~6.5k classes, ~450ms, no static
   init) — after a swap, APIs keep running from resident classes and static files 500 cleanly;
   (b) resident `cfg.router.javaLangErrorHandler` that only sets 500 + logs; (c) boot-time warmup
   request through the 404/exception pipeline; (d) record jar mtime at boot and append
   "jar changed on disk — RESTART the server" to 500 details; (e) README: restart after rebuild.
2. **Static assets: `Cache-Control: no-store`.** With multiple JS files and max-age=0, a browser can
   pair a cached old `ui.js` with a new `views.js` → "X is not a function" crashes on exactly the
   screens using new helpers. Tiny local assets; no-store ends the entire class.
3. Javalin 7 API facts (verified): everything inside `Javalin.create(cfg -> ...)`:
   `cfg.routes.get/post/put/delete/exception/error`, `cfg.jetty.port`, `cfg.router.ignoreTrailingSlashes`,
   `cfg.staticFiles.add(sf -> {hostedPath/directory/location/headers})`,
   `cfg.startup.showJavalinBanner`, `cfg.jsonMapper(new JavalinJackson(mapper, true))`;
   `app.port()` after `.start()`; path params `{id}`; `bodyAsClass` surfaces raw Jackson exceptions
   (map them); the `error(404)` handler runs after handler-set 404s and will overwrite bodies.
4. Migration runner: strip `--` comments PER LINE before splitting on `;` — a comment containing a
   semicolon split the DDL mid-statement, and a leading file comment swallowed the first CREATE
   (the accounts table silently never existed).
5. SQLite: per-operation connections, WAL, busy_timeout 5000, foreign_keys ON. In-memory DBs don't
   survive per-op connections — tests use @TempDir files.
6. Toolchain: JDK 25 Temurin via Adoptium API
   (`https://api.adoptium.net/v3/binary/latest/25/ga/{os}/{arch}/jdk/hotspot/normal/eclipse`;
   macOS layout `Contents/Home`), export JAVA_HOME per invocation (shells don't persist). In the
   Cowork sandbox, Maven Central is BLOCKED but github.com release assets, pypi, npm are reachable —
   check `curl -s -o /dev/null -w "%{http_code}" https://repo.maven.apache.org/maven2/` FIRST and
   stop if blocked (network settings apply only to sessions created after changing them).
7. Long-running local servers for tests: kill by port (`lsof -ti tcp:PORT | xargs kill -9`);
   background wrapper shells dying does not kill the JVM (orphans keep serving).

## 11. Frontend (quality is a completion criterion, not a garnish)

Hash-router SPA, 5 files (`js/learn.js`, `js/api.js`, `js/ui.js`, `js/views.js`, `js/app.js`) +
`css/app.css`. Views set `#app[data-ready="true"]` after render (browser tests key on it). Design
system: CSS variables, light+dark (prefers-color-scheme), accessible risk palette, cards/chips/
stat blocks/badges, tabular numerals for money.

### The experience ladder (the single most important UX decision)
A boolean "beginner mode" is NOT enough — the information architecture itself must adapt. Ship a
3-level segmented control in the header, persisted, applied as a body class, and let screens adapt
STRUCTURALLY (re-render on change), not just cosmetically:
- **Learning** (default): plain-language-first. Candidate cards lead with a one-sentence analogy
  ("story") and three big facts — "Most you can lose / Most you can make / Chance of any profit" —
  then the cost sentence in words; mechanics live in an expandable "How this trade works — and what
  can go wrong" (story → numbered steps → you-win-when / you-lose-when → "easy to miss"); exact
  contracts in a second expandable. Chain shows 3 columns (call price / strike / put price) plus a
  "how to read this" expandable. 0DTE and the aggressive risk mode are hidden entirely. Every term
  of art (premium, strike, POP, credit, breakeven, ~20 more) is a dotted-underline **glossary
  button** opening a tap-to-define popover.
- **Confident**: balanced density (metric chips, greeks in the chain, all structures); explainer
  blocks clamp to one line and expand on tap; the strategy-guide expandable stays available.
- **Pro**: dense, data-first — a body-class density layer tightens paddings/fonts; candidate cards
  add Model EV / liquidity / score chips and drop explainers; the ticket collapses steps 1–3 into a
  one-row quick-start (symbol + view + horizon → screen strategies). Pro is a genuinely deeper tier,
  not just denser styling — build these WITH the ladder, not as a "future rung":
  - **Comparison table** on Ideas when >1 candidate: one row per strategy (cost/credit, max loss,
    max profit, R:R, POP, EV, breakevens, liquidity, score), click-to-sort headers, click-a-row
    expands the full candidate card inline, "Use" button per row.
  - **Position greeks**: marks carry per-leg Δ/Γ/Θ/vega from the chain; the trade service aggregates
    with sign = closing side and multiplier = 100·ratio·qty into share-equivalent Net Δ/Γ, $/day Θ,
    $/vol-pt vega (a `complete` flag guards partially-quoted books). Surface as a chips-plus-per-leg
    table card on trade detail and a portfolio-level "Book greeks" strip (`GET /api/portfolio/greeks`
    sums ACTIVE trades).
  - **Custom multi-leg builder**: a quick-start escape hatch ("Build custom" button) into an
    add/remove-leg editor — action/type/ratio/expiration/strike selects populated from the real
    chain per expiration — that feeds the SAME preview/guardrail pipeline as guided strategies
    (`strategy: "CUSTOM"`). Undefined risk, uncovered shorts, and dead quotes still block at
    preview; Pro means more control, never fewer guardrails.
Content lives in `learn.js`: a GLOSSARY map and a STRATEGY_GUIDE with story/how/win/lose/watch for
every recommendable family — write this copy with care; it IS the product for new traders.

### Reusable primitives
`UI.expandable(summary, lazyDetail, {open})` (chevron header, lazy-built body, aria-expanded) and
`UI.term(word, display)` (glossary popover; renders as plain text at Pro). Use progressive
disclosure everywhere a screen wants to explain itself.

### Screens
**Home dashboard** (default: account strip, market tiles with colored deltas, open positions,
quick actions) · Research (hero quote + delta, IV/HV chips, gridlined year chart with DEMO banner,
chain FOCUSED on ATM ±6 with ITM tinting + "show all" toggle, level-adaptive columns, news) ·
Ideas with TWO tabs — "Scout for me" (default; targets form, intent checkboxes, pick cards with
intent + thesis badges, signal chips, rationale, expandable headline evidence, per-horizon candidates
with target-fit banners) and "My own idea" which is **intent-first**: a "What are you trying to do?"
choice grid (the five intents with plain-language blurbs) reshapes the form — thesis only for
DIRECTIONAL, target-price input for EXIT/ACQUIRE/HEDGE, desired-share count for ACQUIRE, a live
holdings hint (you hold N sh, free M, basis $B, since-purchase %) for hold-based intents — plus a
Filters panel shared with the scout. **Both the chooser and the filters climb the ladder
STRUCTURALLY** (the intent feature is not exempt from this section's rule): Learning gets story cards
and a two-limit expandable ("The most I am willing to lose ($)" + "Minimum chance of any profit (%)",
glossary-termed, with a note that refused ideas show their reason); Confident gets the full
five-filter expandable with clamped blurbs; Pro gets a compact segmented intent row (labels only,
blurbs as tooltips) and all five filters INLINE with terse labels — no clicks. The holdings card
uses plain column names at Learning ("What you paid /sh", "Since you bought") and adds an
Unrealized $ column at Pro; the backtest strategy menu mirrors the ladder (Learning: long options,
debit spreads, covered call, CSP, protective put; Confident adds credit spreads/condor/collar;
Pro everything backtestable, with a note telling Learning users where the rest unlocks). Candidate cards show the intent badge, an intent-note strip framing the numbers
against the goal, USES-HELD-SHARES badges, assignment/yield/effective-price chips, and at Learning
the third big fact becomes "Chance you sell/buy" for EXIT/ACQUIRE (assignment = success there) ·
Portfolio gains a **Holdings card** (shares, avg basis, last, value, since-purchase %, LOCKED badges,
buy/sell modals at executable sides, and an "up X% — Sell at a target…" nudge that prefills the EXIT
ideas flow) and symbol/intent filters on the trade list · Guided ticket (numbered steps; the review
step renders a **Safety check
checklist** — ✓/⚠/✕ rows restating the guardrail verdict in human terms: "Worst case is known and
capped at $X (+fees)", "Fits inside your risk-mode budget", data freshness, 0DTE/closed-market
flags — before the money grid; Pro gets the quick-start) · Portfolio · Trade detail (P/L hero,
payoff SVG with breakeven dots + "now" line, strategy-guide expandable at non-Pro levels, actions
incl. the practice-only-labeled Void) · Backtest · Account · Data status.

### Mobile
Below 640px the top nav hides and a fixed **bottom tab bar** (Home/Research/Ideas/Trade/Portfolio,
safe-area padded, active-state tinted) takes over; cards reflow single-column; fact grids stack.
Freshness badges everywhere (FIXTURE renders as "DEMO DATA").

## 12. Testing doctrine (what "done" means)

- Fixed `Clock` everywhere; fixture determinism asserted (two instances → identical chains).
- Unit: BSM vs textbook values + put-call parity grid + IV round-trips; payoff/breakevens per
  family incl. unbounded detection and qty scaling; **ledger replay invariant test**; guardrails
  (naked call blocked, coverage check, expired, crossed, below-intrinsic, zero-max-loss); engine
  gating per risk mode; MarketHours boundaries (16:00:00 exactly is dead); OAuth1 RFC vector;
  canonical-payload gates; signal determinism.
- Integration: real Javalin on port 0, fixture mode, temp DB, `AppConfig(Map)` overrides; the
  whole lifecycle via HTTP incl. BP arithmetic assertions; 400/404/409/422 contract for malformed
  everything (fuzz mindset: empty body, `{`, wrong types, negative qty, past dates, unknown ids).
- Provider tests: MockWebServer per provider — happy-path exact values (BigDecimal
  `isEqualByComparingTo`), request path/header assertions (EDGAR User-Agent!), error semantics
  (404/403-AccessDenied→empty, 500→throw, rate-limit note→throw), Cboe payload-cache ("many calls,
  one download").
- Browser: Playwright suite in FIXTURE mode (deterministic, every screen + full ticket lifecycle +
  modals + ladder reshaping + Pro depth: comparison table sort/expand, custom builder end-to-end to
  a placed trade, greeks card and book strip) AND a second suite in **LIVE keyless mode** (structural assertions,
  page JS errors and any 5xx are hard failures) — the original build tested only fixtures and every
  live-mode failure shipped. Spawn the actual fat jar in both.
- Incident regressions as named tests (e.g., replay the riskless-collar with its exact quotes).
- Holdings + assignment battery: weighted-basis buys and realized-P/L sells replayed against the
  ledger; covered call on held shares (reserve 0, lock, double-pledge and sell-locked blocked, unwind
  releases); ITM covered-call settle delivers at the STRIKE with a STOCK_SELL row; OTM keeps shares
  and releases the lock; CSP assignment books shares at strike basis and releases the full reserve;
  the same single short call WITHOUT held shares stays blocked as naked; account reset clears
  positions. Backtest pins: buy-write reserve semantics keep entries affordable, assignments counted.
- A jar-swap A/B check: start server, overwrite jar, verify APIs still respond and no thread spins.

## 13. Process lessons (how to run the build)

1. Build incrementally, `mvn -q test` after every module — but **"done" includes a genuinely usable,
   polished UI**; don't spend effort on deep review passes before the product meets that bar
   (the owner explicitly corrected this once).
2. Exercise every flow in LIVE keyless mode as soon as it exists, not only fixtures — the fixture
   suite was green while live mode was slow, null-ridden, and (once) trivially exploitable.
3. After completion, run adversarial review — it earned its cost every time (found: a net-short
   "debit calendar" hole, settlement fabrication, a consent-fabricating settle endpoint, best-tier
   mislabeling, crossed-book minting, idempotency-swallowing-edited-orders, and more). Verify each
   finding against the code before fixing; write a pinned regression test per confirmed finding.
4. When a user reports "it's broken" and you can't reproduce: suspect YOUR OWN dev loop first
   (stale browser JS, jar rebuilt under a running server) before suspecting the app.
5. Financial-realism rules are not hardening to defer — a paper engine that trusts quotes blindly
   is *wrong*, not merely unpolished. Design fills, session-awareness, and quote integrity into v1.
6. Never label modeled data as real. Provenance flows with the data (CandleSeries pattern).
7. Adapt the UI to the user, not the user to the UI: expert tools are dense because experts want
   density — beginners need a different STRUCTURE (fewer numbers, more words, disclosure on tap),
   not the same screen with captions. Build the ladder early; retrofitting it touches every view.
8. Every user-facing action needs failure feedback: an async onclick with no catch is a silent
   no-op when the server hiccups — the single most reported "bad UX" class. House pattern: disable
   the button, try/catch, render an alert with a stable element id (replace the previous one).
   Optional cards fail VISIBLY with a Retry, never vanish. Async loaders that can be superseded
   (option chains per expiration) carry a sequence token so stale responses can't overwrite the
   current view. Ship an /api/health staleness beacon (jar mtime vs boot) + UI banner — a rebuilt
   jar under a running JVM is a certainty in a local-first dev loop, and "restart the server" must
   come from the APP, not from support. And test against a GROWN database (seed via API + inject
   legacy-shaped rows), at every ladder level — fresh-DB suites systematically miss what real users hit.
9. Don't defer work you can see is missing: if a wrap-up names concrete, in-scope follow-ups
   (comparison tables, greeks, custom builder were once listed as a "deeper rung"), build them in
   the same pass — the owner reads a sensible-but-deferred item as an incomplete deliverable.
   Reserve "future work" for items needing his decision or external resources.

## 14. Suggested build order

1. Toolchain + scaffold compiles + `Money`/`Json`/`Db`/migrations (with the comment-safe splitter) + models.
2. Pricing kernel + PayoffCurve + tests.
3. **MarketHours + execution-realism primitives** (LegMark with bid/ask/executable, quote-integrity
   rules) — BEFORE the paper engine, so the engine is born honest.
4. FixtureProvider + MarketDataService (+ CandleSeries provenance, status) + tests.
5. Paper core (ledger invariants, executable fills, session gates, per-leg settlement) + tests.
6. Strategy/Guardrails/CoverageCheck + RecommendationEngine + tests.
7. ApiServer (full error contract, preload/no-store/warmup from day one) + integration tests.
8. Free providers (each with MockWebServer tests + the documented quirks) + live smoke of each.
9. E*TRADE + BrokerService gates + tests.
10. Backtester + tests (no-look-ahead pin).
11. SignalEngine + AutoRecommender + tests.
12. Frontend (design system + all screens) + fixture AND live browser suites + screenshot review.
13. README + full verification battery + adversarial review pass + fix + re-verify.

Config keys (env, or lowercase.dotted in `./strikebench.properties`):
`PORT, DB_PATH, FIXTURES_ONLY, HTTP_TIMEOUT_MS, FEE_PER_CONTRACT_CENTS, FEE_PER_ORDER_CENTS,
DEFAULT_STARTING_CASH_CENTS, AUTO_UNIVERSE, ETRADE_CONSUMER_KEY, ETRADE_CONSUMER_SECRET,
ETRADE_SANDBOX, ETRADE_BASE_URL, POLYGON_API_KEY, ALPHAVANTAGE_API_KEY, FRED_API_KEY,
CBOE_BASE_URL, STOOQ_BASE_URL, EDGAR_BASE_URL, EDGAR_DATA_BASE_URL, EDGAR_USER_AGENT,
TREASURY_BASE_URL, FRED_BASE_URL, POLYGON_BASE_URL, ALPHAVANTAGE_BASE_URL`.

Final bar: ~185+ unit/integration tests, ~13 fixture-browser + ~8 live-browser tests, all green;
the fat jar boots keyless, serves a polished SPA, refuses impossible trades with teachable reasons,
and every simplification it makes is written on the screen where it applies.
