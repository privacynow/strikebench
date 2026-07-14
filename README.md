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

First launch opens the welcome page. Pick your path:

- **Teach me** — plain language everywhere, a tap-to-define glossary, question-driven flows,
  and a safety checklist before anything is placed.
- **Give me the terminal** — dense, data-first screens: greeks, sortable comparison tables,
  per-leg analytics, inline filters.

You can switch levels any time with the Beginner / Expert control in the header, and the
brand mark in the top-left always brings your working desk back. The tour remains linked from Home.

## What you can do

**Home** — your account at a glance, live market tiles for your chosen universe, sector pulse,
and one-tap paths into everything else.

**Research** — explore 13 sector universes as live quote tiles; tap any stock for an
interactive candle chart (1M to MAX, crosshair readout), what's coming up (option expirations,
earnings mentions, SEC filings), headlines, the full option chain with greeks, and
"what you can do with this stock" — live per-goal suggestions computed from the active market lane.

**Plans** — one continuous journey from a market question to review: Understand → Evidence →
Strategy → Outcomes → Decide → Manage & Review. Start with one stock or let the universe Scout
surface alternatives. The same Plan carries its market lane, goal, horizon, thesis, evidence,
exact contracts, scenario receipt, and decision forward, so moving between Research, strategy
selection, historical replay, futures, and a paper order does not ask for the same context again.
Beginners and experts use the same catalog, controls, and math; the presentation changes, not
the capability. Unfavorable and over-budget structures remain visible as teaching cases with
the reason stated rather than disappearing.

**Portfolio → Practice desk** — review the isolated paper account: total value, buying power,
share holdings, open and closed option positions, executable marks, greeks, management actions,
and the append-only practice ledger.

**Portfolio → Tracked accounts** — maintain a separate owner-scoped record of external taxable,
IRA, or 401(k) activity without placing orders or changing practice cash. Record normalized
stock and multi-leg option fills, short positions, fees, interest, dividends, deposits,
withdrawals, assignment, exercise, and expiration; import a chronological CSV atomically;
reconcile exact FIFO/LIFO/HIFO lots and realized basis; inspect executable-side P/L, known cash
obligations, allocation, and Modified Dietz performance; estimate taxes from explicit worksheet
rates; and export exact CSV or a six-sheet Excel workbook. Generated Demo/simulated/model prices
never mark an external account, and unavailable observed marks stay unavailable rather than zero.

**Data → Simulated market** — a practice market that *moves*, any day, at any speed. Pick a
story (steady climb, sell-off then rebound, volatility event...), name your symbols, and
generated prices and option chains stream through every screen — on a virtual exchange clock
that honors sessions, so time decay and expirations behave correctly, and playback speed never
changes what happens, only how fast you watch it. It is a *model*, loudly labeled SIMULATED
everywhere (no dividends, no real order book), a dedicated simulation account keeps your real
practice account untouched, and the same seed plus the session's event log replays the
identical market — reviews are reproducible. One click returns you to the real market
instantly.

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

Works with **zero keys** for delayed option chains and quotes from Cboe, SEC EDGAR filings,
and US Treasury yields when those sources answer. Missing observed data stays unavailable.
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
