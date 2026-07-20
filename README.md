# StrikeBench

**Learn options by doing — with honest numbers.**

StrikeBench is a free, local-first app for learning, paper-trading, and backtesting stock
options. It runs on infrastructure you control, gives you a **$100,000 practice account**,
keyless delayed quotes and option chains where available, and an engine that tells you the worst case
*before* you commit — and explains every idea it refuses.

> **Educational tool only — not financial advice.** Every suggestion is a risk-screened
> teaching example with its assumptions stated. Options involve substantial risk of loss;
> nothing here promises any profit. You trade practice money unless you deliberately wire up
> a real brokerage and pass every safety gate.

## Get started in two minutes

You need Java 25 (free: [Adoptium](https://adoptium.net) or Amazon Corretto). From a source checkout:

```bash
docker compose up -d db              # start the bundled local data service
mvn -q -DskipTests package
java -jar target/strikebench.jar
# open http://localhost:7070
```

First launch opens the welcome page. Pick your presentation level:

- **Teach me** — plain language everywhere, a tap-to-define glossary, question-driven flows,
  and a safety checklist before anything is placed.
- **Give me the terminal** — dense, data-first screens: greeks, sortable comparison tables,
  per-leg analytics, inline filters.

You can switch levels any time with the Beginner / Expert control in the header, and the
brand mark in the top-left always brings your Desk back. Beginner and Expert use the same
controls, decisions, math, and saved state; Expert reveals denser parameters and provenance.

## What you can do

**Desk** — see what needs attention, resume the one active Plan, inspect today's argued ideas,
and open the bounded Plan-library drawer. Alerts for protocol breaches, expiries, event proximity,
assignment/pin heuristics, and unresolved imports drive the order; the Desk does not duplicate the
journeys that own those actions.

**Workspace** — explore 13 sector universes, search a stock, inspect its chart, events, news,
filings, option chain, and regime context, then carry that question into one durable Plan. The Plan
is a progressively revealed document: **Your view → Evidence → Strategy → Outcomes → Commitment →
Live**. It requires an explicit direction, horizon, and risk posture before ranking; absent choices
stay visibly absent instead of becoming a neutral view or a hidden one-month assumption. The
argued-idea hero, runner-ups, exact Builder/custom package, Scout, and cash baseline all use the
same server-owned catalog and decision policy. Unfavorable and over-budget ideas remain visible as
named teaching cases.

Evidence creates one fingerprinted fan for the declared hypothesis. Outcomes reprice the selected
package on that same fan beside market-implied odds, historical analogs, and no-look-ahead replay.
**Scenario Canvas** is the deep editor for that same artifact: author price waypoints and IV paths,
apply an event template, choose symbol or position scope, compare structures, and save a provenance
receipt. Exact conditional fills and guided non-Gaussian interpolation are labeled distinctly.

**Book** — use the isolated Practice book or maintain owner-scoped tracked taxable, IRA, and 401(k)
records without changing practice cash. Construct multi-symbol allocations; record normalized
stock and option transactions; manage exact FIFO/LIFO/HIFO lots; inspect P/L, performance, cash
obligations, allocation, and reviewed-year tax facts; and export exact CSV or a six-sheet workbook.
Generated Demo, simulated, or modeled prices never mark an external account, and an unavailable
observed mark remains unavailable rather than zero.

The Book also owns the full outside-position journey. Paste a supported broker statement or export,
review parser inferences and a current observed re-mark, map source accounts, then confirm exact
transactions or quarantine package-net facts in the pending queue. Resolve a package as provisional
`USER_ALLOCATED` or attested `BROKER_REPORTED`, and batch-adopt confirmed lots into Plans with frozen
ADOPTION receipts. Adopted positions receive separate fresh-eyes and campaign-to-date reviews.
Campaigns preserve economic basis, realized-versus-headline yield, counterfactuals, churn,
authored-versus-realized overlays, protocol adherence, and lessons. Book Risk aggregates lots into
per-account and cross-account dollar Greeks, stressed assignment, expiry clusters, theme
classification, contradictions, and coverage disclosures.

**Data** — inspect source coverage, datasets, jobs, and administration, or run a simulated market
that *moves*, any day, at any speed. Pick a story (steady climb, sell-off then rebound,
volatility event...), name your symbols, and
generated prices and option chains stream through every screen — on a virtual exchange clock
that honors sessions, so time decay and expirations behave correctly, and playback speed never
changes what happens, only how fast you watch it. It is a *model*, loudly labeled SIMULATED
everywhere (no dividends, no real order book), a dedicated simulation account keeps your real
practice account untouched, and the same seed plus the session's event log replays the
identical market — reviews are reproducible. One click returns you to the prior base market
(Observed or Demo) instantly.

**Learn** — search the same vocabulary, explanations, and strategy guides used by the inline
information controls. It links each concept back to the canonical Workspace or Book action.

## One mounted app, from 2560 to mobile

StrikeBench is a real SPA: the shell stays mounted, stage links move attention inside one live
Workspace document, and ordinary updates preserve drafts, focus, scroll, expanded explanations,
pending commands, and subscriptions. The layout is tested at 2560 CSS pixels on a fully expanded
5K display, plus 2048, 1920, 1440, 1280, 1000, 390, 375, and 320. Wide screens use additional space
for useful comparisons and related results; mobile sequences the same capabilities without hiding
them or changing their calculations.

## Honest numbers, by design

- Fills happen at the **executable** side of the book — you buy the ask and sell the bid,
  never a fantasy mid-price. Fees are counted, separately and visibly.
- Undefined-risk positions (naked calls and friends) are **blocked by default** — and shown
  anyway, labeled, so you learn why.
- Model outputs (chance of profit, expected value, breakevens) are labeled as model outputs.
  Demo data is loudly badged `DEMO DATA`. Stale or missing quotes block trades instead of
  silently pricing them.
- The ledger is append-only: corrections and practice-only voids add audit events rather than
  rewriting prior cash movements; every row snapshots cash and reserves.

## Market data

Works with **zero API keys** for delayed option chains and quotes from Cboe and US Treasury
yields when those sources answer. SEC EDGAR additionally requires `EDGAR_USER_AGENT` with
this installation's app name and contact email; StrikeBench has no compiled-in owner identity.
Missing observed data stays unavailable.
Built-in fabricated data exists only after you explicitly enter the isolated **Demo** market;
it never fills a gap in Observed.

Daily price history has several lawful, additive paths. StrikeBench never assumes that a key
grants storage or redistribution rights; the source's plan and terms remain authoritative.

| Path | What it unlocks |
|---|---|
| Your own CSV export | Broker/vendor/Yahoo-exported daily OHLCV or closes, validated and stored locally with its source and raw/adjusted basis |
| `POLYGON_API_KEY` | Official keyed daily history and plan-dependent historical option data; your subscribed terms apply |
| `ALPHAVANTAGE_API_KEY` | Official keyed adjusted daily history; compact access is request-limited and full history requires an entitled plan |
| Authorized Yahoo automation | Disabled unless both local opt-in and permission confirmation are set; never a hosted default |

Data → Sources & jobs previews missing sessions and request cost before downloading, resumes
per symbol after interruption, quarantines invalid rows, and can run once after each completed
market session. Daily bars are intentionally not re-downloaded every hour.

## Good to know

- **Charts empty for some stocks?** Quotes, option chains, and daily history are separate domains.
  Import a file you may use or connect an eligible daily-history source; StrikeBench leaves the
  chart blank rather than substituting Demo data.
- **"Market is closed" warnings** are real: after 4pm ET, quotes are the last session's and
  paper fills are simulated. The app refuses to trade contracts that are already dead.
- **Updated the app while it was running?** Restart it — the UI shows a red banner when it
  detects this.
- Your data lives in the configured local data service. Use Data → Administration for the
  guarded reset levels; resetting a practice account keeps its audit history unless you explicitly
  choose a broader data reset. Deployment backup details live in `DEVELOPER.md`.

## For developers

Build instructions, architecture, test suites, configuration reference, and deployment live
in [DEVELOPER.md](DEVELOPER.md).

The current local verification entry points are:

```bash
docker compose up -d db
mvn -q clean package
cd dom-tests
npm ci
npx playwright install chromium
npm run test:ci       # declarations, SPA, fixture, grown-state, responsive, auth, Book, Learn
npm run test:live     # observed-provider journey; requires network/source availability

# Read-only capture from an already-running local server (defaults to NVDA).
scripts/live-market-probe.sh
```

CI records one TAP report per browser suite and runs `node scripts/release-matrix.mjs`; the
generated report is the source of exact test totals for that branch tip.
