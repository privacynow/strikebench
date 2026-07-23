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

Same code for the backtest and the live system: an Apache Flink case study from an options platform

## Elevator pitch (short field, ≤ ~300 chars)

Backtest code always drifts from production code, and then your test results quietly stop meaning
anything. One Flink job, run in batch mode to replay ten years and in streaming mode live, closes
that gap. Plus: checking whether an engine's "70% chance" calls actually come true 70% of the time.

## Abstract / description

I trade options, and I built a platform that recommends trades and tells me honestly when my own
ideas are bad. It has a problem every trading system has: the code that tests a strategy against
history is never quite the same as the code that runs live. The two drift apart, and then your
test results quietly stop meaning anything.

I solved this with Apache Flink, using one job in two modes. In batch mode it replays ten years
of market data and scores every recommendation the engine would have made along the way. In
streaming mode the same operators run against live data. Same code, so the test can't drift from
production.

On top of that pipeline we train small, explainable models — no LLMs. The most useful one is
simple: when the engine says a trade has a 70% chance of making money, did its past "70%" calls
actually come true 70% of the time? Mine didn't, at first. This talk covers the pipeline, the
late-data handling that keeps profit numbers from being silently wrong, and what the whole thing
taught me about my own real-money trading.

## Audience takeaways

1. A working pattern for running one Flink codebase in both batch (backtest) and streaming (live)
   mode, and why finance needs that guarantee more than any other industry.
2. How event-time processing stops late or out-of-order market data from producing wrong profit
   numbers that nobody notices.
3. How to measure whether a prediction engine's confidence numbers can be trusted, and how to
   show that record to users instead of asking them to take it on faith.

## Session format

25–30 minute talk with a live demo. The demo runs against a deterministic, seeded market
recording, so it cannot fail on stage due to market hours or connectivity; a live-data version
runs when conditions allow.

## Suggested track

Primary: Evolution at Scale. Alternates: High Performance Computing; Agentic AI, GenAI and AI
Governance (the talk is a deliberate counter-programming fit there: measurable, explainable ML
with published error rates, no LLMs).

## Notes to organizers (private field)

This is not a vendor talk. StrikeBench is a personal options practice platform (running at
strikebench.com); nothing is sold and nothing is being launched. The talk is the engineering
story of applying Flink's batch/stream unification to the backtest-drift problem, with the
honest twist that the calibration work exposed the author's own trading assumptions. All claims
in the talk are backed by reproducible replays; happy to share a preview recording of the demo
before the event.

## Speaker bio (fill the bracketed part)

Faraz Babar is [role / affiliation — one line]. He is an active options trader who built
StrikeBench, a practice and analytics platform whose recommendation engine is required to show
its evidence — including the uncomfortable evidence about its author's own trades. His current
work applies Apache Flink and small, explainable models to make backtests trustworthy and
prediction confidence measurable.

## Tags

Apache Flink, Apache Kafka, stream processing, backtesting, market data, model calibration,
machine learning, event-time processing

---

## Submission checklist

- [ ] Bio bracket filled (role/affiliation)
- [ ] Sessionize account: title, elevator pitch, abstract, format, track, tags pasted
- [ ] Notes-to-organizers field pasted
- [ ] Submitted before July 26, 11:59 PM EDT
