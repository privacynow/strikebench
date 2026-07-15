# StrikeBench — developer guide

User-facing overview lives in [README.md](README.md). This file is everything else: build,
architecture, tests, configuration, and deployment.

## Current product shape (2026-07-14)

The Plan-centered journey is complete on `feature/journey_refactor` and is not deployed.

- The visible workflow is **Understand -> Evidence -> Strategy -> Outcomes -> Decide ->
  Manage & Review** at `#/plan/{id}/{stage}`. Research starts ticker- and universe-led work,
  Home is the bounded multi-Plan desk, Portfolio owns positions and construction, and Data owns
  sources, datasets, and simulated markets.
- `StrategyCatalog` is the only strategy-family/template registry. The frontend downloads its
  metadata from `GET /api/strategies`; Plan Strategy composes ranked proposals, goal-native
  ladders, the visual catalog, exact-contract Builder, option book, and Scout without route hops.
- `OutcomeContract` and `POST /api/evaluate` are the only forward-evaluation contract.
  Basis is explicit (`DECISION_POLICY`, `PARAMETRIC`, `HISTORICAL_ANALOGS`,
  `CONDITIONAL_BOOTSTRAP`, or `RISK_NEUTRAL`) so shared machinery never blends interpretations.
- `PathEnsembleService` is the only path source. `ScenarioSimulator` prices supplied ensembles;
  `HistoricalReplayKernel` owns historical entry/mark/exit pricing for both backtest modes.
- `PATHS` evaluation is decision-grade rather than a decorative fan: it returns named terminal
  quantiles, mean/dispersion/standard error, direct end/touch probabilities, median first-touch time,
  Wilson intervals, a sampling-margin disclosure, and an immutable ensemble fingerprint. `POSITION`
  can price the exact working package on that same fingerprint, so Research and Trade cannot silently
  answer with different futures.
- A live simulated market remains a separate, single-realization practice lane. Its control room shows
  every session symbol at once, one clearly named focus chart, the moving account/book, and explicit
  management controls. A Plan hands one stored path to the simulator only as a fingerprinted rehearsal;
  it never treats one realized path as validation of the distribution.
- Economic teaching-market labels are reserved for `DEMO_FIXTURE` and `SIMULATED` pricing evidence.
  `MODELED` is an incomplete input inside another lane, not a generated market; otherwise-promising
  modeled fallbacks remain `MIXED` until end-to-end evidence supports a stronger verdict.
- The old Lab, standalone Decision, ETF-replicator, old Trade-stage, and
  `/api/sim/{scenario,strategy,compare}` surfaces are gone. Do not restore internal aliases or DTO
  overloads for hypothetical API consumers. Database and model-version migrations remain because
  they protect actual persisted user data and deterministic model identity.
- Release evidence is generated from Surefire XML and per-suite browser TAP reports by
  `scripts/release-matrix.mjs` on every CI run. The workflow summary is authoritative for that
  exact commit; test counts are never transcribed here. Representative screenshots are under
  `dom-tests/shots/final-*.png`.

## Build & run

Requires **JDK 25**, Maven, and the bundled local data service.

```bash
docker compose up -d db
mvn test package                 # unit/integration suite + fat jar
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
- **Levels change presentation, never capability**: Beginner/Expert differ in language, density,
  and progressive disclosure, never catalog, controls, requests, math, or risk gates.
- **Accounting facts outlive tax rules**: lots, basis, realized matches, flows, and performance stay
  available for every year. Automated tax characterization and user-rate scenarios are source-cited,
  ruleset-versioned, and enabled only for a reviewed year; provisional/unsupported years expose facts
  and reconciliation guidance without manufacturing an amount owed. Do not expand this into return
  preparation or encode jurisdiction-specific rates.

### Annual tax-rules review

`paper/TaxRules` is the only switch that enables automated federal common-case characterization. Advancing
`REVIEWED_TAX_YEAR` is a release task, not a calendar rollover:

1. Keep the new year `PROVISIONAL` while reviewing that year's IRS Publication 550, Form 8949 instructions,
   Form 6781, and Form 1099-B instructions. Update every title and URL in `TaxRules.SOURCES` to the material
   actually reviewed.
2. Re-check each encoded assumption against those sources: the calendar holding-period boundary, the bounded
   same-account/exact-instrument wash-sale model and its exclusions, the broad-based-index taxonomy, Section
   1256 60/40 character and year-end mark, and retirement-wrapper suppression. Do not infer rules that the
   cited material does not support.
3. Update `RULESET_ID`, `REVIEWED_TAX_YEAR`, `REVIEWED_THROUGH`, and `SCOPE` together. Never advance only the
   year constant. A changed rule that affects stored basis or realized matches requires an explicit migration
   or reconciliation path; do not silently rewrite historical accounting facts.
4. Extend the tax service, export, and browser tests for the reviewed year and its next provisional year.
   The gate must cover loss rows and wash disclosures, Section 1256 classification and year-end marks,
   user-rate scenario availability, retirement-wrapper suppression, primary-source links, and the visible
   `Not tax advice` boundary.
5. Run the generated full release matrix and record the source-review evidence in the release notes. Only
   after that evidence passes may `REVIEWED_TAX_YEAR` move forward. Years not reviewed remain fact-only and
   must say on each affected row that the legal rule was not applied.

## Architecture

```
io.liftandshift.strikebench
├── config      AppConfig (env > sysprops > strikebench.properties > defaults)
├── auth        AuthService, GoogleOidcProvider, verified identity/session policy
├── db          Db (HikariCP-pooled Postgres), Migrations (Flyway; classpath:db/migrations)
├── model       Quote, Candle, OptionQuote, OptionChain, Leg, DataEvidence, provenance/age, ...
├── pricing     BlackScholes, ImpliedVol, PayoffCurve, VolSurface, HistoricalVol
├── market      MarketDataService (provider chain + caches + status), MarketDataEngine (in-memory
│               feed: warm-on-boot + singleflight + stale-while-refresh + SSE), Universes,
│               explicit market lanes, providers/*, sim/*
├── strategy    StrategyCatalog, StrategyFamily, StrategyBuilder, Guardrails, StrategyIntent
├── recommend   RecommendationEngine, AutoRecommender (scout), SignalEngine
├── eval        StrategyEvaluator, EconomicAssessment, risk/vol/evidence profiles, scoring
├── outcomes    OutcomeContract (the versioned cross-engine request/response contract)
├── sim         PathEnsembleService, ScenarioSimulator, SimulationEngine, deterministic path models
├── research    PortfolioOptimizer, ResearchQuestionEngine, BootstrapSampler, NotebookService
├── plan        Plan lifecycle, evidence, strategy, outcomes, decisions, rehearsals, retention
├── paper       Practice trading plus tracked-account accounting, performance, tax facts, import/export
├── backtest    Backtester + HistoricalReplayKernel (single-position and portfolio modes)
├── broker      OAuth1, ETradeProvider, BrokerService (live-order gates)
└── api         Small ApiServer composition root; per-domain *Routes/*Controller classes,
                typed ApiResponses, shared stream broadcaster, WorldTransitionService
```

**Decision flow.** Research establishes a lane-owned symbol and can open or resume a durable Plan.
Understand records the question; Evidence keeps historical observations and modeled futures distinct;
Strategy selects a server-catalog family, ranked proposal, intent ladder, or custom package; Outcomes
evaluates those exact contracts under explicitly named bases; Decide assembles economics, probability
map, execution quality, account sizing, guardrails, and management guidance; Manage & Review preserves
the frozen comparison, paper action, and rehearsal record. Beginner and Expert traverse this same state
machine with different composition and explanation, never different requests or math.

**Canonical evaluation API.** `POST /api/evaluate` accepts a versioned `OutcomeContract.Request`
containing operation, basis, market context, exact position(s), and optional scenario/study inputs.
Pure forward-outcome work must enter here. Historical replay is Plan-owned at
`POST /api/plans/{id}/outcomes/backtest` and delegates pricing and evidence accounting to
`HistoricalReplayKernel`; the retired standalone backtest endpoints are not compatibility aliases.
Live simulated markets remain a market lane under `/api/sim/market/*`, not an alternate evaluation
engine.

For `operation=PATHS`, the response owns both the path receipt and the facts derived from it. The UI
may visualize sample lines, but decisions use the quantiles/probabilities in the response. A price fan
without a position must answer a concrete level or strike question; with a working position, the same
ensemble is repriced immediately. Scenario-model ranges and market-implied ATM-IV ranges are displayed
side by side and never blended into one probability.

**Market data flow.** `MarketDataEngine` sits above the provider chain as the single owned "current
market state": it warms the active universe on boot, refreshes tracked symbols in the background
(RTH-aware cadence), serves the last snapshot instantly (stale-while-refresh), and collapses
concurrent same-symbol fetches onto one provider call. `/api/quotes` + the SSE `/api/market/stream`
serve from it; full chains stay on-demand. The **Data Center** (`/api/data/*` + the Data screen) is
the operational hub: engine status, per-symbol coverage matrix, source setup cards (with each
connector's license/use mode), cancellable/idempotent background jobs (warm / snapshot / backfill /
CSV import), and a tiered, confirmation-gated reset. The Observed evidence ladder is:
**owned CSV > attributable forward snapshots > eligible licensed/personal sources > unavailable**.
Modeled inputs are separately labeled assumptions; Demo, Simulated, and Scenario are explicit lanes,
never fallbacks inside Observed.

Provider priority is domain-specific: executable broker data when connected, keyless Cboe delayed
quotes/chains, eligible keyed or explicitly authorized daily-history sources, then unavailable.
Per-domain health is exposed at `GET /api/status` without laundering a failed domain through another
one. `FixtureProvider` is mounted only behind the explicit Demo lane. Frontend:
`src/main/resources/public/js` — shared `{api,contracts,learn,workspace,ui,plans,outcomes,scenario,builder}`
modules, screen files `views-{research,plan,portfolio,data}.js`, a small shared `views.js`, and `app.js`.

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
- *Simulated market* (`market/sim/`): `SimulatedWorld` is a deterministic MODELED harness — a
  generated market, honestly labeled, never a claim of exchange realism. The path lives on fixed
  30-sim-second QUANTA (playback speed = quanta per tick; the path at a given sim time is
  identical at 1× and 600× — pinned), randomness is counter-based per (seed, stream, symbol,
  quantum) so the symbol set can never shift another symbol's draws, and beta is a real factor
  loading (β·σ_market + idiosyncratic). The virtual exchange clock skips closed hours via
  `MarketHours` and measures option time-to-expiry in open-market seconds to the bell, so theta
  converges continuously to intrinsic on expiration day. The generated option book is European
  BSM + smile + an intrinsic (early-exercise) floor with moneyness/DTE spreads and synthetic OI —
  no dividends, no real order book; surface no-arbitrage invariants are pinned (parity band,
  convexity, calendar, verticals). Strike grids anchor at inception so a crash never delists a
  held strike. `SimulationSessions` (V11/V12) checkpoint state + an immutable event log; restore
  REPLAYS config+events to the exact world (a restart resumes it, RUNNING sessions resume
  ticking); per-owner run caps, owner-checked lookups (including memory-resident worlds), and
  owner-scoped `world.tick` events. The active world is a per-user runtime switch (`GET/PUT
  /api/world`); every read/gate/analytic in a world runs on the WORLD's clock and data — shares,
  option marks, settle, decision scores, scans — and calibration ('Your record') never records
  sim outcomes. Lifecycle: `/api/sim/market` CRUD + `start/pause/step/speed/event` + `/report`
  (model version + event log disclosed).
- *Prefetch*: client-hinted, server-governed. `API.prefetch(path)` marks speculative GETs with
  `X-Priority: prefetch`; the server answers 204 when heavy providers lack budget
  (`CboeProvider.prefetchBudget()`: never while cooling down or fully contended; fixture mode
  always allows). The app warms the likely next step (expirations/history for the working
  symbol) during idle time after each render; denials are silent and cost the user nothing.
- *Status UX*: provider rate-limit cooldowns show as a calm amber header chip (self-expiring),
  a synthetic active dataset shows the loud SCENARIO MODE banner, and Home offers
  "Pick up where you left off" chips into the working context.

## Tests

```bash
mvn test                                             # JUnit (unit + API integration)
cd dom-tests
JAVA_BIN=$JAVA_HOME/bin/java PORT=7101 node --test dom.test.js        # fixture UI suite
JAVA_BIN=$JAVA_HOME/bin/java PORT=7102 node --test dom-audit.test.js  # 8-width responsive/geometry sweep
JAVA_BIN=$JAVA_HOME/bin/java PORT=7103 node --test dom-seeded.test.js # grown-DB walk (both levels)
JAVA_BIN=$JAVA_HOME/bin/java PORT=7104 node --test dom-auth.test.js   # auth-on signed-out + signed-in OIDC journey
JAVA_BIN=$JAVA_HOME/bin/java PORT=7105 node --test dom-live.test.js   # REAL Cboe/EDGAR, every screen
```

DOM suites use Playwright (`npm i` inside `dom-tests/` once) against the real jar on a fresh
temp database; each suite needs its own port. Page JS errors and 5xx responses are hard
failures. Run all suites — fresh-DB suites miss grown-state bugs, fixture suites miss live
ones. The responsive audit checks 2048, 1920, 1440, 1280, 1000, 390, 375, and 320 pixels and
fails on horizontal overflow, clipped controls, or inaccessible geometry. CI retains one TAP report
per browser suite and runs `node scripts/release-matrix.mjs`; that command sums the actual Surefire
and TAP reports, fails on reported failures, writes `target/release-matrix.md`, and publishes it to
the workflow summary. `.github/workflows/ci.yml` runs the backend and non-network browser matrix on every push/PR;
`live-providers.yml` runs the observed-lane suite on a weekday schedule and by manual dispatch.

Browser tabs share one origin-wide market/event stream pair through a short leader lease and
`BroadcastChannel`; followers consume relayed frames and retain an ordinary polling fallback until
their first frame. Lease failover heals either transport independently, hidden tabs release streams,
and default observed frames resolve the current universe on every computation rather than capturing
the sector present when the connection opened. The five-tab fixture regression must remain green:
long-lived streams may never exhaust the browser connection pool and starve ordinary API reads.

Released Flyway migrations are byte-immutable. `MigrationImmutabilityTest` pins every V1-V56
SHA-256 digest and requires each new migration to be added to the manifest. Never edit an applied
migration, including comments or whitespace, and never use `repair` to legitimize a source edit;
schema changes always receive a new versioned migration.

## Configuration

Environment variables, or the same keys lowercase-dotted in `./strikebench.properties`
(precedence: env > `-D` sysprops > file > defaults):

| Key | Default | Purpose |
|---|---|---|
| `PORT` | `7070` | HTTP port |
| `BRAND_NAME` / `BRAND_TAGLINE` | `StrikeBench` / built-in | Header, title, hero |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` / `DB_PATH` | compose db / legacy SQLite path | Postgres connection; `DB_PATH` is one-time legacy import only |
| `FIXTURES_ONLY` | `false` | Demo data only, zero network |
| `TRUSTED_PROXY` | `false` | Trust forwarded client IPs only when every request comes through the owned proxy |
| `HTTP_TIMEOUT_MS` | `10000` | Provider timeout |
| `FEE_PER_CONTRACT_CENTS` / `FEE_PER_ORDER_CENTS` | `65` / `0` | Paper/execution commission assumptions |
| `DEFAULT_STARTING_CASH_CENTS` | `10000000` | New paper account ($100k) |
| `SNAPSHOT_ENABLED` / `SNAPSHOT_INTERVAL_HOURS` / `SNAPSHOT_INITIAL_DELAY_SECONDS` | live default on / `24` / `600` | Forward option-chain snapshot schedule |
| `PORTFOLIO_NAV_ENABLED` / `PORTFOLIO_NAV_INITIAL_DELAY_SECONDS` / `PORTFOLIO_NAV_INTERVAL_MINUTES` | live default on / `30` / `15` | Calculated tracked-book valuation schedule |
| `ARTIFACT_RETENTION_ENABLED` | `true` | Run computed-artifact cleanup |
| `STALE_PLAN_ARTIFACT_RETENTION_DAYS` / `ORPHAN_ENSEMBLE_RETENTION_DAYS` | `30` / `90` | Retention windows for replaceable Plan artifacts and unreferenced ensembles |
| `ARTIFACT_RETENTION_INITIAL_DELAY_SECONDS` / `ARTIFACT_RETENTION_INTERVAL_HOURS` | `300` / `24` | Cleanup startup delay and cadence |
| `ENGINE_ENABLED` | `true` | In-memory market engine: warm the active universe on boot + refresh in the background |
| `ENGINE_QUOTE_REFRESH_SECONDS` / `ENGINE_QUOTE_REFRESH_CLOSED_SECONDS` | `20` / `300` | Engine refresh cadence (RTH / market-closed) |
| `ENGINE_MAX_TRACKED` / `ENGINE_WARM_FULL_UNIVERSE` / `ENGINE_STREAM_INTERVAL_SECONDS` | `220` / `false` / `3` | Warm-set cap, explicit heavy-provider full warm, and stream interval |
| `CBOE_BASE_URL` | Cboe CDN | Cboe endpoint override |
| `CBOE_COOLDOWN_MINUTES` / `CBOE_MAX_CONCURRENCY` / `CBOE_MIN_SPACING_MS` | `15` / `2` / `1200` | Shared Cboe politeness and rate-limit controls |
| `YAHOO_ENABLED` + `YAHOO_AUTOMATION_PERMISSION_CONFIRMED` | `false` / `false` | **PERSONAL / local-clone only** — both are required; automated Yahoo access remains permission-gated and is never a hosted default |
| `YAHOO_DAILY_REQUEST_LIMIT` / `YAHOO_BASE_URL` | `100` / Yahoo chart endpoint | Durable local safety cap and testable endpoint; neither grants source rights |
| `STOOQ_ENABLED` / `STOOQ_BASE_URL` | `false` / Stooq | Opt-in source and endpoint override; automated clients commonly receive an anti-bot response |
| `NEWS_RSS_BASE_URL` | Google News RSS | Keyless per-symbol headline source; blank disables it |
| `AUTH_ENABLED` | `false` | Require an authenticated, owner-scoped session |
| `OIDC_ISSUER` / `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` / `OIDC_CALLBACK_URL` | Google / blank / blank / local callback | OIDC discovery, client credentials, and exact registered redirect |
| `AUTH_POST_LOGIN_URL` / `AUTH_COOKIE_SECURE` / `AUTH_SESSION_IDLE_SECONDS` | `/` / `false` / `1800` | Post-login destination, HTTPS-only cookie setting, and server-side idle expiry |
| `AUTH_ALLOWED_EMAILS` / `AUTH_ADMIN_EMAILS` | blank | Sign-in allowlist (blank permits verified identities) and explicit admin allowlist (blank permits no admins) |
| `ADMIN_TOKEN` | blank | Auth-off destructive-operation token matched via `X-Admin-Token`; blank remains local-only behind the TLS proxy |
| `AUTO_UNIVERSE` | — | Fallback scout universe (the in-app sector picker overrides) |
| `POLYGON_API_KEY` / `POLYGON_BASE_URL` / `POLYGON_DAILY_REQUEST_LIMIT` | blank / Polygon / `0` | Plan-governed history/options source; zero means no invented StrikeBench cap |
| `ALPHAVANTAGE_API_KEY` / `ALPHAVANTAGE_BASE_URL` | blank / Alpha Vantage | Official keyed daily-candle source and endpoint override |
| `ALPHAVANTAGE_FULL_HISTORY_ENABLED` / `ALPHAVANTAGE_DAILY_REQUEST_LIMIT` | `false` / `25` | Entitlement-controlled full history and durable request cap |
| `FRED_API_KEY` / `FRED_BASE_URL` | blank / FRED | Keyed rates source (else Treasury) and endpoint override |
| `TREASURY_BASE_URL` | Treasury | Keyless Treasury rate endpoint override |
| `EDGAR_BASE_URL` / `EDGAR_DATA_BASE_URL` | SEC endpoints | SEC ticker and submissions endpoint overrides |
| `EDGAR_USER_AGENT` | blank | Required SEC contact identity, for example `MyStrikeBench/1.0 (me@example.com)`; blank disables EDGAR |
| `ETRADE_CONSUMER_KEY` / `ETRADE_CONSUMER_SECRET` / `ETRADE_SANDBOX` / `ETRADE_BASE_URL` | blank / blank / `true` / derived | E*TRADE credentials, environment, and test endpoint override |

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
