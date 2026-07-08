# StrikeBench — developer guide

User-facing overview lives in [README.md](README.md). This file is everything else: build,
architecture, tests, configuration, and deployment.

## Build & run

Requires **JDK 25** and Maven.

```bash
mvn test package                 # 224 JUnit tests + fat jar
java -jar target/strikebench.jar # port 7070
```

`FIXTURES_ONLY=true` runs on built-in deterministic demo data with zero network — useful for
development and what most test suites use.

## Stack & principles

Java 25 + Javalin 7 (Jetty 12) + SQLite (WAL, per-op connections) + Caffeine caches; plain
HTML/CSS/JS frontend served from the jar — **no frontend build step**. D3 v7 is vendored for
candlesticks; everything else is hand-rolled SVG.

- **Money is exact**: `BigDecimal` for per-share prices, `long` integer cents for the ledger,
  balances, and contract totals. Doubles exist only inside the Black-Scholes kernel and model
  statistics (IV, greeks, probabilities), converted at the boundary (`util/Money`).
- **The ledger is append-only**: every row snapshots cash and reserve after application; fees
  are separate rows; the reserve holds each trade's remaining worst case, so buying power
  drops by exactly max loss + fees at entry.
- **Fills are executable**: buys price at the ask, sells at the bid; one-sided or crossed
  books refuse to fill. Marks value closes at the executable side too.
- **Levels change content, never geometry**: Beginner/Expert differ in explainers, columns,
  and features — never in padding or font size (a DOM test enforces this).

## Architecture

```
io.liftandshift
├── config      AppConfig (env > sysprops > strikebench.properties > defaults)
├── db          Db (per-op connections, WAL), Migrations (V1..V3)
├── model       Quote, Candle, OptionQuote, OptionChain, Leg, Freshness, ...
├── pricing     BlackScholes, ImpliedVol, PayoffCurve, VolSurface, HistoricalVol
├── market      MarketDataService (provider chain + caches + status), Universes, providers/*
├── strategy    StrategyFamily, StrategyBuilder, Guardrails, StrategyIntent, CoverageCheck
├── recommend   RecommendationEngine, AutoRecommender (scout), SignalEngine
├── paper       AccountService, TradeService, PositionsService, AuditLog
├── backtest    Backtester (no-look-ahead daily loop, tiered pricing)
├── broker      OAuth1, ETradeProvider, BrokerService (live-order gates)
└── api         ApiServer (Javalin routes), AssistInstaller-era code removed, Main
```

Provider priority per data domain: **E*TRADE → Cboe → AlphaVantage/Polygon → Stooq →
fixtures**, per-domain health at `GET /api/status` (never 500s). Frontend:
`src/main/resources/public` — `js/{learn,api,ui,builder,views,app}.js`, hash router, screens
adapt structurally to the Beginner/Expert level.

The engineering log — every decision, incident, and gotcha — is `CLAUDE.md` at the repo root.

## Tests

```bash
mvn test                                             # JUnit (unit + API integration)
cd dom-tests
JAVA_BIN=$JAVA_HOME/bin/java PORT=7101 node --test dom.test.js        # fixture UI suite
JAVA_BIN=$JAVA_HOME/bin/java PORT=7102 node --test dom-audit.test.js  # overflow/emoji/control-height sweep
JAVA_BIN=$JAVA_HOME/bin/java PORT=7103 node --test dom-seeded.test.js # grown-DB walk (both levels)
JAVA_BIN=$JAVA_HOME/bin/java PORT=7104 node --test dom-live.test.js   # REAL Cboe/EDGAR, every screen
```

DOM suites use Playwright (`npm i` inside `dom-tests/` once) against the real jar on a fresh
temp database; each suite needs its own port. Page JS errors and 5xx responses are hard
failures. Run all suites — fresh-DB suites miss grown-state bugs, fixture suites miss live
ones.

## Configuration

Environment variables, or the same keys lowercase-dotted in `./strikebench.properties`
(precedence: env > `-D` sysprops > file > defaults):

| Key | Default | Purpose |
|---|---|---|
| `PORT` | `7070` | HTTP port |
| `BRAND_NAME` / `BRAND_TAGLINE` | `StrikeBench` / built-in | Header, title, hero |
| `DB_PATH` | `data/strikebench.db` | SQLite file (legacy `options-lab.db` auto-migrates once) |
| `FIXTURES_ONLY` | `false` | Demo data only, zero network |
| `FEE_PER_CONTRACT_CENTS` | `65` | Commission per contract per leg |
| `FEE_PER_ORDER_CENTS` | `0` | Flat fee per order |
| `DEFAULT_STARTING_CASH_CENTS` | `10000000` | New paper account ($100k) |
| `HTTP_TIMEOUT_MS` | `10000` | Provider timeout |
| `AUTO_UNIVERSE` | — | Fallback scout universe (the in-app sector picker overrides) |
| `POLYGON_API_KEY` | — | Historical candles + historical option chains (backtest tier) |
| `ALPHAVANTAGE_API_KEY` | — | Daily adjusted candles |
| `FRED_API_KEY` | — | Risk-free rates (else keyless Treasury XML) |
| `ETRADE_CONSUMER_KEY` / `ETRADE_CONSUMER_SECRET` | — | Enables the E*TRADE adapter |
| `ETRADE_SANDBOX` | `true` | Sandbox vs live E*TRADE |
| `*_BASE_URL` (Cboe/Stooq/EDGAR/Treasury/FRED/Polygon/AlphaVantage/ETrade) | real endpoints | Overridable for tests |

Live trading requires, beyond keys: connection + successful preview + the exact typed
confirmation *"I understand max loss and this is real money"* + an idempotent client order
id. The recommendation engine can never place live orders.

> Operational note: rebuilding the jar under a running JVM breaks lazy classloading.
> `GET /api/health` reports `jarChangedSinceBoot` and the UI shows a red restart banner.

## Deployment

`scripts/deploy.sh` is machine-generic — every host-specific value is an env override
(`REPO`, `APP_DIR`, `SERVICE`, `PORT`, `BRANCH`, `RUN_USER`, `JAVA_BIN`, `JAVA_HEAP`):

```bash
scripts/deploy.sh --install       # first run: app dir + properties + systemd unit, then deploy
scripts/deploy.sh                 # update: pull -> build -> atomic jar swap -> restart -> health
scripts/deploy.sh --setup-timer   # optional: poll git every 5 min, auto-deploy new commits
```

The app runs from `APP_DIR` (default `/opt/strikebench`), never from the source tree.
Reverse proxy + TLS stay outside the script; a minimal nginx block plus
`sudo certbot --nginx -d your.domain --redirect` is all it takes (example in the script
header). Production reference deployment: https://strikebench.com
