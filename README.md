# StrikeBench

**Learn options by doing, with the assumptions and risks visible.**

StrikeBench is a local-first options workbench for education, idea discovery, exact-package
analysis, paper trading, historical replay, and tracking positions held elsewhere. Optional live
brokerage exists, but it is disabled by default and uses the same pricing and safety checks as
Practice.

> **Educational tool only — not financial advice.** Options involve substantial risk of loss.
> StrikeBench can compare modeled outcomes and enforce mechanical checks; it cannot promise profit
> or replace personal financial, legal, or tax advice.

## Run locally

### Prerequisites

- JDK 25
- Maven 3.9 or newer
- Docker with Docker Compose

From a source checkout:

```bash
docker compose up -d db
mvn -q package
java -jar target/strikebench.jar
```

Open [http://localhost:7070](http://localhost:7070). The first screen is **Your book**. The default
Practice account starts with $100,000; PostgreSQL data persists in the Docker volume between app
restarts.

For an isolated review that makes no external market-data requests:

```bash
FIXTURES_ONLY=true java -jar target/strikebench.jar
```

## The first useful journey

1. Start on **Your book** to see cash, positions, working ideas, market context, and Scout.
2. Select **New idea**, or choose a Scout result.
3. Choose the underlying or market scope, goal, directional view, horizon, risk posture,
   assignment preference, and earnings preference. StrikeBench does not silently invent missing
   choices.
4. Compare the ranked exact packages. A candidate can remain visible as a teaching comparison even
   when it is not endorsed.
5. Select a package to inspect its exact legs, executable book, expiration payoff, scenario
   reactions, shared simulated paths, historical replay, supporting data, and effect on the selected
   Book.
6. If the package is endorsed and executable, review the exact limit, fees, buying-power effect,
   warnings, and required acknowledgments before placing a Practice order. Decision quality and
   execution readiness are separate statuses.
7. Return to **Your book** and open the position to review its durable entry payoff, current mark,
   saved possible futures, supporting market data, and available management actions.

## Current product surfaces

| Surface | Purpose |
|---|---|
| **Your book** | Home and orientation: account truth, market context, permanent Scout controls, positions, working ideas, research, and book-level risk. |
| **New Idea / Decide** | Deep analysis of one saved idea: choices, ranked packages, exact legs, payoff, scenarios, paths, replay, data quality, Book fit, and commitment. |
| **Position** | Focused review of a held Practice or tracked package, including current market data and close/roll/adjust workflows when the required marks exist. |
| **Import or record trade** | Preview and confirm supported broker-statement text or manually record activity in a tracked account without touching Practice cash. |
| **Learn** | Search the shared strategy and terminology registry used by the rest of the product. |

The top bar also owns:

- **Observed / Simulated** market selection;
- the global symbol, sector, and Learn search;
- **New idea**; and
- a persistent light/dark theme toggle.

Book, Position, and New Idea are focus states in one mounted browser application. Scout results open
the full New Idea analysis rather than a smaller, parallel analysis screen.

## What StrikeBench evaluates

- The server-owned strategy catalog builds stock, put, call, vertical, calendar, butterfly,
  condor, covered, acquisition, income, directional, hedge, and exit structures when their required
  holdings and market data exist.
- Recommendations keep structural eligibility, after-cost economics, data quality,
  compensation, and destination-Book fit as separately visible judgments.
- One saved set of underlying paths is shared across package comparisons. Each package is
  valued on those same paths; selecting another candidate does not secretly generate a friendlier
  market.
- Historical replay is a separate observed-past question with no-look-ahead rules. It is never
  averaged into modeled forward futures.
- Practice trading uses an append-only cash/reserve ledger. Tracked accounts use independent lots,
  basis, transactions, valuations, and structures; they never mutate Practice balances.
- Position management re-evaluates the remaining position from today while preserving its entry
  facts and campaign history.

## Financial and data-quality boundaries

- **Java services own financial facts.** Browser JavaScript formats server values and draws supplied
  points; it does not price options, calculate POP/EV/Greeks, generate paths, or reconstruct payoff
  facts.
- **Executable prices use the book.** Buys use the ask and sells use the bid. Crossed, one-sided,
  stale, or internally inconsistent books do not become imaginary fills.
- **Fees and cash flow remain visible.** Package premium, stock cash flow, fees, collateral, maximum
  loss, and buying-power effect are not collapsed into one ambiguous number.
- **Different measures stay named.** Market-implied probability, modeled gain-path frequency,
  realized-volatility EV, market-price benchmark, and assignment probability are not interchangeable.
- **Missing data remains missing.** An unavailable quote, option mark, event date, probability,
  or Book comparison is not rendered as zero and does not erase durable facts that are still valid.
- **A comparison is not an order.** An unendorsed package requires an explicit decision to continue;
  optional live brokerage additionally requires an executable signed limit and an explicit review.
- **Stored results explain their origin.** Important outputs carry their market mode, source,
  observation time, completeness, model version, or input hash as appropriate.

## Market data and modes

StrikeBench keeps market identity explicit:

| Mode | Meaning |
|---|---|
| `OBSERVED` | Stored or provider-supplied market observations with source and freshness. Missing observed data stays unavailable. |
| `DEMO` | Built-in fixture data, enabled deliberately for deterministic offline work. It never fills an Observed gap. |
| `SIMULATED` | A generated market with its own clock, account, seed, and event log. |
| `SCENARIO` | A rehearsal conditioned on a selected stored path or authored scenario. |

Quotes, option chains, daily history, events, news, and rates are independent data domains. Having
one does not imply that the others are available.

Supported acquisition paths include:

| Source | Use |
|---|---|
| Cboe | Keyless delayed US option quotes/chains when the source responds. |
| Yahoo | User-authorized, serialized, durably budgeted daily history; disable with `YAHOO_ENABLED=false`. |
| Polygon | Keyed daily history and plan-dependent historical options. |
| Alpha Vantage | Keyed adjusted daily history subject to plan limits. |
| SEC EDGAR / RSS | Filings, headlines, and event data; EDGAR requires a contact-bearing `EDGAR_USER_AGENT`. |
| U.S. Treasury / FRED | Risk-free-rate data; FRED requires a key. |
| User CSV and broker statements | Validated local history or tracked-account activity from data the user is entitled to use. |

Provider calls are bounded by concurrency, spacing, durable daily budgets, and cooldowns. Stored
coherent ranges are reused rather than downloaded repeatedly. Source permissions and subscription
terms remain the operator's responsibility.

## Storage, accounts, and security

- PostgreSQL 16 is the durable system of record. Flyway validates migrations at startup. Never edit
  an applied migration; add a new forward migration.
- Practice and tracked accounts are deliberately separate. A tracked taxable, IRA, or retirement
  record cannot change Practice cash, and modeled/Demo values cannot become tracked-account P/L.
- Authentication is off by default for a local single-user installation. Google OIDC and user
  scoping can be enabled for a hosted instance.
- Live brokerage is off by default (`BROKER_LIVE_ENABLED=false`) and its routes require an administrator when
  enabled.
- OAuth token values in the current `secrets` table are server-only but not application-level
  encrypted. Protect PostgreSQL, the runtime properties file, and database backups accordingly.
- Do not expose port 7070 publicly. Put a trusted TLS reverse proxy in front of a hosted instance.

## Development and verification

```bash
docker compose up -d db
mvn -q test                       # focused financial and data rules
mvn -q -DskipTests package        # build the shaded jar without rerunning tests
```

For an already-running Observed instance, the read-only provider probe records typed quote, chain,
research, history, expiration, and news responses without mutating trades:

```bash
scripts/live-market-probe.sh
```

The repository intentionally retains a small set of focused rule tests rather than the retired broad
test suites. A green Maven run therefore covers only those rules—not every browser journey or
responsive composition. Changes to Home, New Idea, Position, displayed financial
strings, or responsive layout require a deliberate private-instance browser review at the affected
desktop and mobile sizes.

## Production

The reference deployment is a single Linux host:

```text
Browser → nginx/TLS → StrikeBench systemd service on 127.0.0.1:7070 → local PostgreSQL 16
```

The maintained scripts provide the operational path:

```bash
DB_PASSWORD='strong password' scripts/provision-postgres.sh
BRANCH=main RUN_USER="$(id -un)" scripts/deploy.sh --install
scripts/backup-postgres.sh --setup-timer
```

Configure `/opt/strikebench/strikebench.properties`, keep it mode `600`, terminate TLS at nginx,
then verify `/api/health` and walk a real browser journey. Later deployments use
`BRANCH=main scripts/deploy.sh`; the script fast-forwards, clean-builds, atomically swaps the jar,
restarts the service, and confirms success through the health endpoint. Every host-specific value is overridable—see the
script header and the deployment section in the developer guide.

## Documentation

- [architecture.md](architecture.md) — current components, responsibilities, schema, APIs, data
  flows, providers, jobs, security, and infrastructure.
- [DEVELOPER.md](DEVELOPER.md#build--run) — detailed build, configuration, tax-rule review, and
  deployment reference. Its architecture overview is historical; use `architecture.md` for current
  system responsibilities.
- [`scripts/deploy.sh`](scripts/deploy.sh) — supported deployment flags and service template.
- [`scripts/provision-postgres.sh`](scripts/provision-postgres.sh) — PostgreSQL 16 provisioning.
- [`scripts/backup-postgres.sh`](scripts/backup-postgres.sh) — local and optional S3 backups.
