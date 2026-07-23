# OSFF New York 2026 submission package

Event: Open Source in Finance Forum, New York, November 4–5, 2026.
CFP closes July 26, 2026, 11:59 PM EDT · notifications August 17 · schedule August 19.
Submission page: https://sessionize.com/open-source-in-finance-forum-new-york-2026

Frame: a practitioner case study of USING open source (Apache Flink, Apache Kafka, classical ML)
to solve a real problem. StrikeBench is the case study, not the product being launched. No
license announcement, no pitch. Finance terms are explained on first use; the audience is bank
technologists and open-source program office people, not options traders.

Tech posture (owner-settled): title and story are tech-agnostic; the stack is named once as the
implementation, with one line making the method portable ("the guarantee comes from one shared
code path, not any framework"). Never fully agnostic, because the open source stack is the
admission ticket at this conference. No dollar figures anywhere in the materials. No em dashes
or LLM-flavored phrasing in any submission field.

---

## Title (final, owner's choice)

The backtest was green. The account wasn't. Building a backtest that can't lie.

Note: Apache Flink is no longer in the title. It stays prominent in the elevator pitch's third
sentence, the abstract, and the tags, which the committee also scans for technology fit.

Retired alternates (for reference):
- Building a backtest that can't lie: one Apache Flink job for both history and live
- When your model says 70%, does it ever mean 70%?
- I built an engine that says my trades are bad. It's right. (still the talk's opening line)

## Elevator pitch (short field, ≤ ~300 chars)

I built an options platform to learn the domain without risking much money. Its spine is
backtesting, and that worked fine until I asked what a "70% chance of success" had actually
meant. So I rebuilt it. History and live now share one code path (Apache Flink), and every
claim is testable.

## Abstract / description

I built an options trading platform to learn a complex domain without risking a lot of money
first. The spine of the platform is backtesting: replay history and check whether an idea would
have worked. That served me fine until I started asking harder questions. When the platform says
a trade has a 70% chance of making money, what does that number actually mean? How much trust
has it earned?

Chasing that question exposed a defect most trading systems share: the code that replays
history is not the code that runs live. Built separately, the two drift apart, and after that
the test results stop meaning much. My fix is one job that runs two ways. Batch mode replays ten
years and re-makes every recommendation the platform would have made along the way. Streaming
mode runs the identical code on live data. I use Apache Flink for this, but the pattern is not
tied to it. The guarantee comes from sharing one code path, not from any framework.

On top of the pipeline sit small, explainable models. No LLMs. They score the replayed
recommendations and answer the blunt version of the question: did the "70%" trades work out 70%
of the time? None of this is a panacea. A backtest can still be wrong about the future. What you
get is a repeatable way to test claims, and it has already contradicted me once: it graded my
own real-money positions, disagreed with me, and was right. I'll cover the pipeline, the
handling of late-arriving market data that would otherwise corrupt profit-and-loss numbers, and
what the whole exercise taught me about my own trading.

## Audience takeaways

1. A working pattern for running one Flink codebase in both batch (historical replay) and
   streaming (live) modes, and why trading systems need that guarantee more than most software.
2. How event-time processing keeps late or out-of-order market data from corrupting
   profit-and-loss numbers without anyone noticing.
3. A practical way to test whether a prediction system's confidence numbers deserve trust, and
   why you should show users that track record instead of asking for faith.

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
launched; this is not a vendor talk. The session is the engineering story of using Apache
Flink's batch/stream unification to make that platform's backtesting trustworthy, including the
uncomfortable finding that its own confidence numbers needed calibration. All claims are backed
by reproducible replays; a preview recording of the demo is available on request.

## Speaker bio (fill the bracketed part)

Faraz Babar is [role / affiliation, one line]. He trades options for his own account and built
StrikeBench, a personal platform that recommends options trades and shows the evidence behind
every number it puts on screen. His current work uses Apache Flink and small, explainable
models to make the platform's backtests trustworthy and to check its confidence numbers against
what actually happened.

## Tags

Apache Flink, Apache Kafka, stream processing, backtesting, market data, model calibration,
machine learning, event-time processing

---

## Submission checklist

- [ ] Bio bracket filled (role/affiliation)
- [ ] Sessionize account: title, elevator pitch, abstract, format, track, tags pasted
- [ ] Notes-to-organizers field pasted
- [ ] Submitted before July 26, 11:59 PM EDT
