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

**Trade → Ideas** — start from what you're *trying to do*: trade a view, earn income, buy a
stock at a discount, sell your shares at a target, or protect them. Name a ticker or let the
scout scan the market for you — every pick shows its evidence (momentum, news sentiment,
volatility) and every refused idea says exactly why it was refused. Screen by what matters:
chance of profit, assignment risk, income rate, cash outlay, worst case.

**Trade → Builder** — every structure a broker menu carries (verticals, condors, butterflies,
calendars, collars, straddles…), built leg by leg. Beginners get a story per leg and its live
impact; experts get a terminal with per-leg market data and toggles. On the payoff chart you
can **drag any strike** — it snaps to real chain strikes and re-prices the position live, so
you can *feel* what moving a strike does to your worst case and odds. A fine-tune panel gives
you the exact numbers when you want them.

**Trade → Backtest** — before risking even paper money on a rule like "sell a monthly covered
call," see how it would actually have gone, trade by trade, with honest labels on what is real
data and what is modeled.

**Trade → Place** — review with explicit money effects (what you pay or collect, worst case,
buying power after, fees) and a plain-language safety check, then place it in the paper
account. Track it in **Portfolio**: total value, P/L since start, your shares, every trade's
live marks, and an append-only ledger of every cent.

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

Two optional free keys unlock more:

| Configure | What it unlocks |
|---|---|
| `POLYGON_API_KEY` | Eligible daily price history and historical option chains that can upgrade backtests from modeled to observed pricing |
| `ALPHAVANTAGE_API_KEY` | Eligible daily price history (alternative to Polygon) |

The **Data** screen shows the live health of every source, per domain.

## Good to know

- **Charts empty for some stocks?** Price *history* needs one of the free keys above; quotes
  and chains work keyless.
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
