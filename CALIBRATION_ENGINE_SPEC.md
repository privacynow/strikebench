# Calibration & Parity Engine — Spec

Date: 2026-07-22 · Branch: `feature/journey_refactor` · Status: agreed direction, build authorized.
Companion to `POSITION_LIFECYCLE_SPEC.md` (this engine is the empirical layer its M6/M7 need) and
`OSFF_TALK.md` (the talk demos this system; talk dates: CFP Jul 26 · notify Aug 17 · stage Nov 4).

## 0. Purpose and honest rationale

The recommendation engine has no track record: nothing scores what it would have recommended
historically, its probability-of-profit numbers are uncalibrated, and its tail model runs on
hand-set priors. This engine fixes that, and it is wanted as product regardless of the talk —
it is the empirical answer to "the recommendations still aren't profitable."

Why Flink, stated honestly: at today's single-user scale a partitioned single-node job could run
the replay. Flink is chosen for (a) the parity guarantee — one operator graph runs bounded
(backtest) and unbounded (live), so backtest/live drift is eliminated structurally, not by
discipline; (b) mature event-time processing (watermarks, late-data policy) — late or reordered
market data producing silently wrong P/L is a correctness bug this project refuses to have;
(c) the live event-reaction lane and community-scale deployment this platform is heading toward.
If (a)–(c) ever stop being true, Flink stops being justified.

## 1. Composition contract (no second engine)

The Flink job embeds the EXISTING owners as operators — it never reimplements them:
`RecommendationEngine` + `EvaluationService` (candidate generation and scoring),
`EconomicAssessment` (verdicts), `PathEnsembleService` (Monte Carlo), `EventService` (event
evidence, per lifecycle-spec M2), `ProviderPoliteness` (all acquisition). New code is pipeline,
state, scoring, and model-fitting — not a parallel evaluator. Any divergence between an operator
result and the same call made through the API on identical inputs is a build-failing defect.

## 2. Architecture

**Event log (Kafka):** topics for daily bars, option-chain snapshots, quotes, market events
(earnings etc.), and resolved outcomes. Every record carries event time, source, provenance, and
a payload fingerprint. The log is the single replayable truth; the existing Postgres store feeds
it via a backfill producer and stays authoritative for the product.

**One job, two tempos:**
- *Bounded (replay):* sources read the log from the beginning with event-time semantics; for each
  session and symbol the embedded engine generates its recommendations exactly as it would have
  that day (no lookahead — inputs are watermark-gated to the session); results flow to the
  scoring stage.
- *Unbounded (live):* identical graph on the delayed live lane; CEP patterns (e.g. implied-vol
  spike plus price gap inside one theme) emit reaction events that re-fire lifecycle verdicts.

**Determinism harness:** same input log ⇒ byte-identical scored output, proven by fingerprint
comparison in CI. Savepoints give reproducible "as-of" analysis. This harness is also the stage
demo receipt.

**Late-data policy:** explicit per-topic watermark strategy; late records route to a disclosed
side-output and mark affected windows re-stated, never silently merged.

## 3. Tracks (parallel where independent)

**T1 — Data foundation.** Kafka + schemas + backfill producer from the existing store; corpus =
our accumulating daily Cboe chain snapshots, extended by a historical options dataset
(Databento / ORATS / Cboe DataShop — owner's budget call, deferred until after talk acceptance)
and optionally Deribit's free tick history for a tick-scale lane; confirmed-earnings backfill
through the lifecycle-spec M2 event owner. Gate: replay of one symbol-year reconciles
bar-for-bar with the Postgres store.

**T2 — Parity dataflow.** Embedded-engine operators; bounded walk-forward generate+score across
the universe; unbounded lane + CEP reactions; determinism harness green in CI. Gate: one
operator-vs-API equivalence suite; one full-universe decade replay completes with fingerprint
receipt.

**T3 — Scoring and models.** Outcome resolution against subsequent history (positions and
virtual recommendations); calibration store keyed by structure family × sector × regime; models:
HAR-RV realized-volatility forecast (feeds the after-cost expected-profit check), per-sector
jump-mixture parameters fitted by EM/MLE (replace hand priors, with confidence intervals),
isotonic probability-of-profit calibration, implied-vs-subsequent-realized volatility premium,
earnings-move vs implied-move distributions. Versioned model registry: every model carries its
training-window fingerprint and reliability curve. All models interpretable; no LLMs anywhere.
Gate: calibration curves computed from replay outputs only (no in-sample leakage; time-split
validation).

**T4 — Product integration.** Reliability curve beside every probability the product shows
("when this engine said 70%, it historically meant X"); fitted tail priors with provenance and
fallback; the opportunity scan ranked by estimated volatility premium; CEP reactions wired into
lifecycle verdicts (this is the redeployment-hurdle producer the lifecycle spec names); a public
engine report card page. Gate: the desk shows calibrated numbers with their receipts; the report
card regenerates from a replay artifact, never hand-edited.

**T5 — Stage assets.** Full-stack compose (app + postgres + kafka + flink) for the demo; seeded
deterministic demo world so the talk cannot be market-shy; benchmark receipts (evaluations
replayed, wall time, determinism fingerprints). StrikeBench remains private; these are stage
assets, not a release.

## 4. Correctness gates (the spine, per the talk)

1. Replay determinism: fingerprint-identical outputs for identical logs, enforced in CI.
2. No lookahead: every replay input is event-time-gated; a single future-leak test failure blocks.
3. Executable prices for scoring: fills modeled at bid/ask with fees, never mid — consistent with
   the lifecycle spec's hold-vs-close rule.
4. Calibration honesty: the report card publishes misses with the same prominence as hits;
   restatements (late data) are visible, dated, and explained.
5. Model receipts: no number surfaces in the product without its model version, training window,
   and reliability curve one click away.
