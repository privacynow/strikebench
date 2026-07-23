# OSFF New York 2026 — submission package

Event: Open Source in Finance Forum, New York, November 4–5, 2026.
CFP closes July 26, 2026, 11:59 PM EDT · notifications August 17 · schedule August 19.
Submission page: https://sessionize.com/open-source-in-finance-forum-new-york-2026

Frame: a practitioner case study of USING open source (Apache Flink, Apache Kafka, classical ML)
to solve a real problem. StrikeBench is the case study, not the product being launched. No
license announcement, no pitch. Finance terms are explained on first use — the audience is bank
technologists and open-source program office people, not options traders.

Tech posture (owner-settled): title and story are tech-agnostic; the stack is named once as the
implementation, with one line making the method portable ("the guarantee comes from one shared
code path, not any framework"). Never fully agnostic — the open source stack is the admission
ticket at this conference. No dollar figures anywhere in the materials.

---

## Title (final — owner's choice)

The backtest was green. The account wasn't. Building a backtest that can't lie.

Note: Apache Flink is no longer in the title — it stays prominent in the elevator pitch's third
sentence, the abstract, and the tags, which the committee also scans for technology fit.

Retired alternates (for reference):
- Building a backtest that can't lie: one Apache Flink job for both history and live
- When your model says 70%, does it ever mean 70%?
- I built an engine that says my trades are bad. It's right. — *still the talk's opening line.*

## Elevator pitch (short field, ≤ ~300 chars)

I built an options platform to learn the domain without risking much money. Its spine is
backtesting — which worked fine, until I asked what "70% chance of success" had actually meant.
So I rebuilt it: history and live share one code path (Apache Flink), and every claim became
checkable.

## Abstract / description

I built an options trading platform for one reason: to learn a complex domain without paying
real-money tuition. The spine of that platform is backtesting — replaying history to check
whether an idea would have worked. It served me fine, until I started asking harder questions.
When the platform says a trade has a 70% chance of making money, what does that number actually
mean? How much trust has it earned?

Chasing that question exposed a defect most trading systems share: the code that replays history
is not the code that runs live. Built separately, the two drift apart, and once they do, test
results quietly stop meaning much. The fix I landed on is one job that runs two ways: batch mode
replays ten years and re-makes every recommendation the platform would have made along the way;
streaming mode runs the identical code on live data. We use Apache Flink for this — though the
pattern travels, because the guarantee comes from sharing one code path, not from any one
framework.

On top sit small, explainable models — no LLMs — that score the replayed recommendations: did
the "70%" trades actually work out 70% of the time? None of this is a panacea; a backtest can
still be wrong about the future. What it gives you is a repeatable way to verify claims — and it
has already contradicted me once, grading my own real-money positions and turning out right.
I'll cover the pipeline, the late-arriving-data handling that keeps profit-and-loss numbers from
going quietly wrong, and what an honest scorekeeper taught me about my own trading.

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
