# OSFF New York 2026 submission, field by field

Form: https://sessionize.com/open-source-in-finance-forum-new-york-2026 (autosaves; max 3
submissions per speaker; we submit ONE). Closes Sunday July 26, 11:59 PM EDT (8:59 PM Phoenix).
Notifications August 17. Slides due November 3. Event November 4 to 5.

Rules for all fields: plain first-person voice, no em dashes, no dollar figures, finance terms
explained on first use, every field must stand alone for a reader with zero context.

## Session Title

The backtest was green. The account wasn't. Building a backtest that can't lie.

## Description (limit 1200 characters; this text is 1195)

I built an options trading platform to learn a hard domain without risking much money first. The spine of the platform is backtesting: replay history and check whether an idea would have worked. That worked until I asked harder questions. When the platform says a trade has a 70% chance of making money, what does that number actually mean? How much trust has it earned?

That question exposed a defect most trading systems share: the code that replays history is not the code that runs live. Built separately, they drift apart until the results stop meaning much. My fix is one job that runs two ways. Batch mode replays ten years and remakes every recommendation the platform would have made. Streaming mode runs the identical code on live data. I use Apache Flink, but the pattern is not tied to it; the guarantee comes from sharing one code path.

On top sit small, explainable models. No LLMs. They score the replayed recommendations: did the 70% trades work out 70% of the time? None of this is a panacea, but claims become testable. It has already contradicted me once, and it was right. I will cover the pipeline, late-arriving data, and what the exercise taught me about my own trading.

## Topic (dropdown)

HPC - High Performance Computing. Their suggested topics include "Scaling Financial Workloads in
HPC Environments" and "Deployment of distributed workloads," which is exactly this talk.
Alternate if preferred: Agentic AI, GenAI and AI Governance, under their suggested topic "Case
studies on successful implementation of AI-driven financial models" (this talk is the no-LLM
counterpoint that track will otherwise lack). Do not pick Fluxnova (that is Day 1's dedicated
conference).

## Session format

Session Presentation (typically 30 minutes).

## Audience Level

Any. The talk defines every finance term it uses.

## Benefits to the Ecosystem (901 characters)

This solves two problems. First, backtest drift: backtests drive real capital decisions, but they usually run on a second implementation of the trading logic, and the two codebases drift apart until the test results describe nothing that actually runs. The pattern shown here, demonstrated with Apache Flink but portable to any engine, removes that by construction: one code path serves both historical replay and live processing. Second, unaudited confidence: models emit probabilities that are rarely checked against what later happened. The talk shows a repeatable way to score every past prediction and publish the reliability record. Both patterns apply directly to trading, risk, and reporting pipelines in regulated environments, where every institution rebuilds this non-differentiating plumbing privately. Nothing is sold here; the platform is a personal project used as an honest case study.

## Session keywords (they ask for at least 5)

Apache Flink, stream processing, backtesting, batch and stream unification, model calibration,
market data, event-time processing

## Presented this talk before?

No.

## Code of Conduct / Inclusive Speaker Orientation

Two required checkboxes; review and check both.

## Are you both the submitter and speaker?

Yes.

## Speaker Name

Faraz Babar

## Speaker Tagline

Software engineer who trades options with tools he built himself.

## Email

Use the address you actually read; the organizer contacts you there about this submission.

## Speaker Biography (limit 500 characters; text below is 351, leaving 148 for the first sentence)

[One sentence: role / affiliation.] He trades options for his own account and built StrikeBench, a personal platform that recommends options trades and shows the evidence behind every number it puts on screen. His current work uses Apache Flink and small, explainable models to make the platform's backtests trustworthy and to check its confidence numbers against what actually happened.

## Speaker Photo

Yours; it will be cropped square.

## LinkedIn / Company Website / Fediverse / Company / Speaker Title / Country

Yours. Company Website can be https://strikebench.com. Country: United States.

## Diversity questions

Optional and confidential; your call.

## Co-speakers

None.

---

# Supporting material (not form fields)

Opening line of the talk itself: "I built an engine that says my trades are bad. It's right."

Retired titles, for reference: "Building a backtest that can't lie: one Apache Flink job for
both history and live" / "When your model says 70%, does it ever mean 70%?"

Tech posture (owner-settled): title and story are tech-agnostic; the stack is named once as the
implementation, with one line making the method portable. Never fully agnostic, because the open
source stack is the admission ticket at this conference.

Demo plan for the talk: live demo against a deterministic, seeded market recording so it cannot
fail on stage; a live-data version runs when conditions allow. Slides due November 3.
