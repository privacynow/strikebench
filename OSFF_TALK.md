# OSFF New York 2026 — submission package

Event: Open Source in Finance Forum, New York, November 4–5, 2026.
CFP closes July 26, 2026, 11:59 PM EDT · notifications August 17 · schedule August 19.
Submission page: https://sessionize.com/open-source-in-finance-forum-new-york-2026

Frame: a practitioner case study of USING open source (Apache Flink, Apache Kafka, classical ML)
to solve a real problem. StrikeBench is the case study, not the product being launched. No
license announcement, no pitch. Finance terms are explained on first use — the audience is bank
technologists and open-source program office people, not options traders.

---

## Title

Your backtest is lying to you: one Apache Flink job for both history and live

Alternates (swap at paste time if preferred):
- The backtest was green. The account wasn't. — *closing the backtest/live gap with Apache Flink*
- When your model says 70%, does it ever mean 70%? — *calibrating a trading engine with Apache Flink and small, explainable models*
- I built an engine that says my trades are bad. It's right. — *Apache Flink, honest backtests, and a decade of replayed decisions*
(The last one doubles as the talk's opening line even if not used as the title.)

## Elevator pitch (short field, ≤ ~300 chars)

I built a platform that recommends options trades. Like every trading system, its backtest code
drifted from its live code until the test results meant little. The fix: one Apache Flink job that
replays ten years in batch mode and runs live in streaming mode — same code, so it can't drift.

## Abstract / description

I trade options for my own account, and I built a platform that recommends trades and scores how
good its own recommendations are. Testing it honestly ran into a problem every trading system
has: the code that replays history to evaluate a strategy is never quite the same as the code
that runs live. The two drift apart, and once they do, the test results quietly stop meaning
anything.

This talk is about the fix I landed on: one Apache Flink job, run two ways. In batch mode it
replays ten years of market data, re-makes every recommendation the platform would have made
along the way, and scores them against what actually happened. In streaming mode the identical
code runs on live data. Because both modes share one codebase, the test cannot drift from
production.

On top of that pipeline sit small, explainable machine-learning models — no LLMs. The most
useful one answers a blunt question: when the platform said a trade had a 70% chance of making
money, did those trades actually work out 70% of the time? At first, they didn't. I'll cover the
pipeline, the handling of late-arriving market data that otherwise produces quietly wrong
profit-and-loss numbers, and what the whole exercise taught me about my own real-money trading.

## Audience takeaways

1. A working pattern for running one Flink codebase in both batch (historical replay) and
   streaming (live) modes, and why trading systems need that guarantee more than most software.
2. How Flink's event-time processing keeps late or out-of-order market data from silently
   corrupting profit-and-loss numbers.
3. A practical way to test whether any prediction system's confidence numbers deserve trust —
   and why showing users that track record beats asking them for faith.

## Session format

25–30 minute talk with a live demo. The demo runs against a deterministic, seeded market
recording, so it cannot fail on stage due to market hours or connectivity; a live-data version
runs when conditions allow.

## Suggested track

Primary: Evolution at Scale. Alternates: High Performance Computing; Agentic AI, GenAI and AI
Governance (the talk is a deliberate counter-programming fit there: measurable, explainable ML
with published error rates, no LLMs).

## Notes to organizers (private field)

The speaker is an individual options trader who built StrikeBench (strikebench.com), a personal
platform that recommends and analyzes options trades. Nothing is sold and nothing is being
launched — this is not a vendor talk. The session is the engineering story of using Apache
Flink's batch/stream unification to make that platform's backtesting trustworthy, including the
uncomfortable finding that its own confidence numbers needed calibration. All claims are backed
by reproducible replays; a preview recording of the demo is available on request.

## Speaker bio (fill the bracketed part)

Faraz Babar is [role / affiliation — one line]. He trades options for his own account and built
StrikeBench, a personal platform that recommends options trades and shows the evidence behind
every number it puts on screen. His current work uses Apache Flink and small, explainable
models to make the platform's backtests trustworthy and its confidence numbers measurable
against reality.

## Tags

Apache Flink, Apache Kafka, stream processing, backtesting, market data, model calibration,
machine learning, event-time processing

---

## Submission checklist

- [ ] Bio bracket filled (role/affiliation)
- [ ] Sessionize account: title, elevator pitch, abstract, format, track, tags pasted
- [ ] Notes-to-organizers field pasted
- [ ] Submitted before July 26, 11:59 PM EDT
