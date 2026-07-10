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

Java 25 + Javalin 7 (Jetty 12) + PostgreSQL (HikariCP pool, Flyway migrations) + Caffeine
caches; plain HTML/CSS/JS frontend served from the jar — **no frontend build step**. D3 v7 is
vendored for candlesticks; everything else is hand-rolled SVG. Local dev DB: `docker compose up -d db`.

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
io.liftandshift.strikebench
├── config      AppConfig (env > sysprops > strikebench.properties > defaults)
├── db          Db (HikariCP-pooled Postgres), Migrations (Flyway; classpath:db/migrations)
├── model       Quote, Candle, OptionQuote, OptionChain, Leg, Freshness, ...
├── pricing     BlackScholes, ImpliedVol, PayoffCurve, VolSurface, HistoricalVol
├── market      MarketDataService (provider chain + caches + status), MarketDataEngine (in-memory
│               feed: warm-on-boot + singleflight + stale-while-refresh + SSE), Universes, providers/*
├── strategy    StrategyFamily, StrategyBuilder, Guardrails, StrategyIntent, CoverageCheck
├── recommend   RecommendationEngine, AutoRecommender (scout), SignalEngine
├── research    PortfolioOptimizer, ResearchQuestionEngine (workbench), ETFReplicator, NotebookService
├── paper       AccountService, TradeService, PositionsService, AuditLog
├── backtest    Backtester, PortfolioBacktester (no-look-ahead daily loops, tiered pricing)
├── db          + DataJobService (Data Center jobs), DataCoverage, DataResetService, UnderlyingBackfill
├── broker      OAuth1, ETradeProvider, BrokerService (live-order gates)
└── api         ApiServer (Javalin routes incl. /api/market/{engine,stream} + /api/data/*), Main
```

**Market data flow.** `MarketDataEngine` sits above the provider chain as the single owned "current
market state": it warms the active universe on boot, refreshes tracked symbols in the background
(RTH-aware cadence), serves the last snapshot instantly (stale-while-refresh), and collapses
concurrent same-symbol fetches onto one provider call. `/api/quotes` + the SSE `/api/market/stream`
serve from it; full chains stay on-demand. The **Data Center** (`/api/data/*` + the Data screen) is
the operational hub: engine status, per-symbol coverage matrix, source setup cards (with each
connector's license/use mode), cancellable/idempotent background jobs (warm / snapshot / backfill /
CSV import), and a tiered, confirmation-gated reset. Best-source ladder for evidence:
**owned CSV > licensed API > forward snapshots > personal underlying (Yahoo) + modeled > fixtures.**

Provider priority per data domain: **E*TRADE → Cboe → AlphaVantage/Polygon → Stooq →
fixtures**, per-domain health at `GET /api/status` (never 500s). Frontend:
`src/main/resources/public` — `js/{learn,api,workspace,ui,scenario,builder,views,app}.js`,
hash router, screens adapt structurally to the Beginner/Expert level.

**Workspace continuity + events.** The app behaves like a trader's desk: it remembers what you
were doing and quietly prepares the next step.
- *State*: `js/workspace.js` persists a client-owned blob (draft forms, working idea, working
  symbol, route — never result payloads) to localStorage instantly and to `PUT /api/workspace`
  debounced (`workspace` table, rev per write, 128KB cap, per-user; `'local'` when auth is off).
  Boot hydrates the newest copy; a bare open restores the exact route, an explicit hash wins.
- *Events*: `GET /api/events` (SSE) streams small typed hints from the in-process
  `util.EventBus` — `job.progress`/`job.complete`, `dataset.selected`, `provider.cooldown`,
  `workspace.updated` — with a Last-Event-ID replay ring. Events carry ids, never payloads; the
  client refetches what it cares about (GETs stay the source of truth). The quote tape keeps its
  dedicated `/api/market/stream`.
- *Simulated market* (`market/sim/`): `SimulatedWorld` is a deterministic per-seed world — a
  virtual exchange clock (30 sim-sec × speed per 1s tick, skips closed hours via `MarketHours`,
  rolls daily bars), factor-correlated GBM with Itô drift and scenario shapes, 250 seeded
  history bars, and a full BSM option exchange (smile, intrinsic floors, moneyness/DTE spreads,
  synthetic OI). `SimulationSessions` persists configs (`sim_session`, V11 — same seed restores
  the same world), runs all active sessions on one shared loop (max 3), and publishes throttled
  `world.tick` hints. The active world is a per-user runtime switch (`GET/PUT /api/world`,
  settings `active_world:<owner>`); `MarketDataService` routes world-aware reads through a
  resolver while the observed engine keeps running, so switching back is instant. Inside a
  world, `currentAccount` resolves to a dedicated simulation account (`accounts.world_id`) —
  the real practice account and the sim lane can never pollute each other. Everything is
  labeled `Freshness.SIMULATED`. Lifecycle: `/api/sim/market` CRUD + `start/pause/step/speed/
  event` + `/report`.
- *Prefetch*: client-hinted, server-governed. `API.prefetch(path)` marks speculative GETs with
  `X-Priority: prefetch`; the server answers 204 when heavy providers lack budget
  (`CboeProvider.prefetchBudget()`: never while cooling down or fully contended; fixture mode
  always allows). The app warms the likely next step (expirations/history for the working
  symbol) during idle time after each render; denials are silent and cost the user nothing.
- *Status UX*: provider rate-limit cooldowns show as a calm amber header chip (self-expiring),
  a synthetic active dataset shows the loud SCENARIO MODE banner, and Home offers
  "Pick up where you left off" chips into the working context.

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
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | compose db | Postgres connection (`DB_PATH` is legacy, ETL-only) |
| `FIXTURES_ONLY` | `false` | Demo data only, zero network |
| `SNAPSHOT_ENABLED` | `false` | Record daily forward chain snapshots into `option_bar` |
| `ENGINE_ENABLED` | `true` | In-memory market engine: warm the active universe on boot + refresh in the background |
| `ENGINE_QUOTE_REFRESH_SECONDS` / `ENGINE_QUOTE_REFRESH_CLOSED_SECONDS` | `20` / `300` | Engine refresh cadence (RTH / market-closed) |
| `ENGINE_MAX_TRACKED` / `ENGINE_STREAM_INTERVAL_SECONDS` | `160` / `3` | Warm-set cap (LRU) / SSE push interval on `/api/market/stream` |
| `YAHOO_ENABLED` | `false` | **PERSONAL / local-clone only** — keyless Yahoo daily equity candles for underlying backfill (NOT options; you own Yahoo's terms). Never a hosted default |
| `AUTH_ENABLED` + `OIDC_*` + `AUTH_ALLOWED_EMAILS` + `AUTH_COOKIE_SECURE` | off | Google sign-in + per-user scoping |
| `AUTH_ADMIN_EMAILS` / `ADMIN_TOKEN` | — | Who can run destructive ops (data reset, CSV import). With auth on: admin-email allowlist (else the entry allowlist). With auth off: `ADMIN_TOKEN` matched via `X-Admin-Token`, else LOCAL-only (blocked behind the TLS proxy) |
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

## Postgres cutover runbook (SQLite → Postgres, done once at merge time)

The app runs on PostgreSQL. Local dev uses the docker-compose `db` (`docker compose up -d db`);
production runs a bare Postgres on the app box. To cut a running SQLite deployment over:

1. **Provision Postgres** (once, on the box):
   `DB_PASSWORD='…' scripts/provision-postgres.sh` — installs Postgres 16 (localhost-only,
   scram-sha-256) and creates the `strikebench` role + database.
2. **Set the password** in `/opt/strikebench/strikebench.properties`: `db.password=…` (chmod 600).
3. **Deploy the Postgres-aware unit**: `scripts/deploy.sh --install` writes a systemd unit that
   passes `DB_URL`/`DB_USER` (password stays in the properties file). Don't start it yet.
4. **Migrate the data** into the *empty* Postgres before the first app boot:
   `cd /opt/strikebench && DB_URL=jdbc:postgresql://localhost:5432/strikebench DB_USER=strikebench \
     DB_PASSWORD='…' java -jar strikebench.jar etl /path/to/options-lab.db`
   — introspective column-intersection copy in one transaction; verifies row counts + the ledger
   invariant and exits non-zero on any problem. Rehearse it against a copy of the prod SQLite first.
5. **Start**: `sudo systemctl restart strikebench` (Flyway migrates the schema on boot).
6. **Backups**: `scripts/backup-postgres.sh --setup-timer` installs a nightly `pg_dump → gzip → S3`
   timer (`BACKUP_BUCKET=s3://…` in `/opt/strikebench/backup.env`; keeps the last 14 locally).

### Optional: require Google sign-in
Set in `strikebench.properties`: `auth.enabled=true`, `oidc.client.id`, `oidc.client.secret`,
`auth.allowed.emails=you@example.com`, `auth.cookie.secure=true`. Register the redirect URI
`https://your.domain/auth/callback` in the Google console, and make nginx forward the scheme:
`proxy_set_header X-Forwarded-Proto $scheme;` (so the Secure session cookie is emitted). Off by
default — every visitor otherwise shares the single local paper account.

### Forward chain snapshots (the historical-evidence moat)
Set `snapshot.enabled=true` to record a daily EOD snapshot of the active universe's option chains
into `option_bar`/`underlying_bar` (evidence-tagged). Off by default so the demo never hammers
providers; `POST /api/admin/snapshot` triggers one on demand.
