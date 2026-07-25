'use strict';

/**
 * Scout — the five states §16.3 requires: idle, partial, complete, empty and error.
 *
 * `/api/research/scout` streams NDJSON when the caller accepts it, and each line is one frame:
 * `{type:'progress', progress}`, `{type:'complete', result}` or `{type:'error', error}`. A plain
 * JSON body carrying the result is ALSO valid — `desk-backend.js` accepts one explicitly, for
 * intermediaries and harnesses that cannot preserve the negotiated content type. Both forms are
 * produced here, because a suite that only ever exercises the JSON form has never tested the
 * streaming path the product actually uses.
 *
 * IDLE is the state with no payload at all, and it is the most important one to get right: the
 * scan is withheld until the user declares a goal, a horizon and a risk posture. §3.5 forbids
 * substituting Income / 45 days / Balanced and then presenting the result as the user's own idea,
 * so `undeclaredScoutRequest()` is the input that must leave Scout idle and name what is missing —
 * not a request that quietly succeeds.
 */

const wire = require('./wire');
const golden = require('./golden');

const SCOUT_STATES = ['idle', 'partial', 'complete', 'empty', 'error'];
const DEFAULT_UNIVERSE = wire.ROSTER_SYMBOLS.slice(0, 6);

/** `SignalEngine.VolatilityEvidence`. */
function volatilityEvidence(available) {
  return wire.nonNull({
    impliedAvailable: available,
    impliedSource: available ? 'FIXTURE_CHAIN' : null,
    impliedFreshness: available ? 'REALTIME' : null,
    impliedAsOfEpochMs: available ? wire.OBSERVED_AT_MS : null,
    realizedAvailable: available,
    realizedSource: available ? 'FIXTURE_DAILY_HISTORY' : null,
    realizedFreshness: available ? 'EOD' : null,
    realizedObservations: available ? 120 : 0,
    realizedFrom: available ? '2026-02-02' : null,
    realizedTo: available ? wire.TODAY : null,
    unavailableReasons: available ? [] : ['No option chain and no eligible daily history.']
  });
}

/** `SignalEngine.EventEvidence`. */
function eventEvidence(available) {
  return wire.nonNull({
    available: available,
    eventRisk: available,
    flags: available ? ['EARNINGS'] : [],
    headlineCount: available ? 5 : 0,
    sources: available ? ['Fixture Newswire'] : [],
    latestPublishedEpochMs: available ? wire.OBSERVED_AT_MS - 3600000 : null,
    basis: available ? 'KEYWORD_DERIVED' : 'UNAVAILABLE',
    scorerVersion: 'sentiment-keyword-v1',
    note: available ? null : 'No eligible headlines for this symbol.'
  });
}

/** `SignalEngine.Signals`. */
function signals(symbol, overrides) {
  return wire.nonNull(Object.assign({
    symbol: symbol,
    optionable: true,
    ret5d: 0.014,
    ret20d: 0.036,
    ivAtm: 0.2814,
    hv30: 0.2465,
    ivHvRatio: 1.1416,
    volSignal: 'RICH',
    sentimentScore: 0.126,
    positiveHeadlines: ['Quarterly results beat the prior guidance range'],
    negativeHeadlines: [],
    eventRisk: true,
    liquidityScore: 0.86,
    thesis: 'NEUTRAL',
    confidence: 0.74,
    rationale: ['Implied volatility sits above realized.', 'Two-sided books on every strike.'],
    sentimentScorerVersion: 'sentiment-keyword-v1',
    sentimentAggregate: null,
    headlineSentiment: [],
    volatilityEvidence: volatilityEvidence(true),
    eventEvidence: eventEvidence(true)
  }, overrides || {}));
}

/** `AutoRecommender.OpportunityContext` — the cross-symbol RANKING score, never the economics. */
function opportunityContext(score, overrides) {
  return wire.nonNull(Object.assign({
    goal: 'INCOME',
    score: score,
    signalConfidence: 0.74,
    volatilityFit: 0.81,
    liquidity: 0.86,
    eventAdjustment: -0.05,
    summary: 'Rich implied volatility against a liquid two-sided book.',
    volatilityEvidence: volatilityEvidence(true),
    eventEvidence: eventEvidence(true)
  }, overrides || {}));
}

/**
 * `AutoRecommender.BestIdea` — the compact projection Home's opportunity rows read.
 *
 * `available: false` is the state where evidence ranked a symbol but no package could be priced;
 * every economic field is then null and the row must say "evidence ready", not print a number.
 */
function bestIdea(overrides) {
  const options = Object.assign({ available: true }, overrides || {});
  const { available, summary, ...fields } = options;
  const settings = { available: available, summary: summary };
  if (!settings.available) {
    return wire.nonNull({
      available: false,
      horizon: null,
      family: null,
      displayName: null,
      economicVerdict: 'UNAVAILABLE',
      placement: null,
      chanceOfProfit: null,
      maxLossCents: null,
      marketImpliedEvAfterCostsCents: null,
      realizedVolEvAfterCostsCents: null,
      realizedVsMarketEvDifferenceCents: null,
      realisticEvLowAfterCostsCents: null,
      realisticEvHighAfterCostsCents: null,
      realisticEvBasis: null,
      observedEvidence: false,
      summary: settings.summary || 'Ranked on evidence; no package could be priced.'
    });
  }
  return wire.nonNull(Object.assign({
    available: true,
    horizon: 'month',
    family: 'PUT_CREDIT_SPREAD',
    displayName: 'Put credit spread',
    economicVerdict: 'FAVORABLE',
    placement: 'BELOW_SPOT',
    chanceOfProfit: golden.FACTS.pop,
    maxLossCents: golden.FACTS.maxLossCents,
    marketImpliedEvAfterCostsCents: 6200,
    realizedVolEvAfterCostsCents: 7400,
    realizedVsMarketEvDifferenceCents: 1200,
    realisticEvLowAfterCostsCents: 3100,
    realisticEvHighAfterCostsCents: 11600,
    realisticEvBasis: 'REALIZED_VOL_AFTER_COSTS',
    observedEvidence: true,
    summary: settings.summary || 'Positive after costs on both EV lanes.'
  }, fields));
}

/**
 * `AutoRecommender.Pick`. The first pick is the golden package, so a Scout result and the New Idea
 * rail it hands off to can be asserted against the same numbers.
 */
function pick(index, overrides) {
  const settings = Object.assign({ available: true }, overrides || {});
  const symbol = index === 0 ? golden.SYMBOL
    : wire.ROSTER_SYMBOLS[index % wire.ROSTER_SYMBOLS.length];
  const score = Math.round((92 - index * 7.5) * 10) / 10;
  return wire.nonNull({
    symbol: symbol,
    signals: signals(symbol),
    opportunityScore: score,
    horizons: settings.available ? [{
      horizon: 'month',
      candidates: [{
        targetFit: 'INCOME',
        // `ScoredCandidate.evaluation` is a StrategyEvaluation, which carries its exact candidate.
        // Only the members the Desk reads are populated; the rest are legitimately null on this
        // record and the mapper's NON_NULL inclusion drops them.
        evaluation: {
          id: `evaluation_${symbol.toLowerCase()}_month_1`,
          candidate: golden.goldenCandidate({ id: `candidate_scout_${symbol.toLowerCase()}` }),
          risk: golden.goldenRiskProfile(),
          assessment: golden.goldenEvaluation().assessment
        }
      }],
      notes: []
    }] : [],
    intent: 'INCOME',
    opportunity: opportunityContext(score),
    bestIdea: bestIdea({ available: settings.available })
  });
}

/** `AutoRecommender.Progress` — one streamed frame's payload. */
function progress(phase, completed, total, overrides) {
  return wire.nonNull(Object.assign({
    phase: phase,
    completed: completed,
    total: total,
    symbol: DEFAULT_UNIVERSE[Math.min(completed, DEFAULT_UNIVERSE.length - 1)],
    pick: null,
    message: null
  }, overrides || {}));
}

/**
 * `AutoRecommender.AutoResult`. `picks: []` with a stated `skipped` list and notes is the EMPTY
 * state — the scan ran and found nothing, which is a different fact from the scan not having run,
 * and a different fact again from the scan failing.
 */
function autoResult(overrides) {
  const settings = Object.assign({ pickCount: 3, available: true }, overrides || {});
  const picks = [];
  for (let index = 0; index < settings.pickCount; index += 1) {
    picks.push(pick(index, { available: settings.available }));
  }
  return wire.nonNull({
    picks: picks,
    skipped: settings.skipped || (picks.length ? [] : DEFAULT_UNIVERSE.map(symbol =>
      `${symbol}: no candidate cleared the declared risk budget`)),
    notes: settings.notes || (picks.length
      ? [`Scanned ${DEFAULT_UNIVERSE.length} symbols in the active universe.`]
      : [`Scanned ${DEFAULT_UNIVERSE.length} symbols; none produced a priced package.`]),
    riskBudgetCents: 500000,
    disclaimer: 'Ranked candidates are analysis, not advice, and no order was placed.',
    compensation: [],
    compensationBasis: null,
    frontier: null
  });
}

/**
 * The scan request the Desk WILL send once the user has declared everything it requires.
 * `maxPicks` is bounded by the universe size in `scoutOpportunities`, so the fixture bounds it the
 * same way rather than stating a number the client would never produce.
 */
function scoutRequest(overrides) {
  const settings = Object.assign({
    universe: DEFAULT_UNIVERSE.slice(),
    horizons: ['month'],
    intents: ['INCOME'],
    riskMode: 'balanced',
    maxPicks: 5
  }, overrides || {});
  return wire.nonNull({
    horizons: settings.horizons,
    maxPicks: Math.max(1, Math.min(settings.universe.length || 12, settings.maxPicks)),
    riskMode: settings.riskMode,
    allow0dte: false,
    intents: settings.intents,
    universe: settings.universe.length ? settings.universe : undefined,
    maxLossCents: settings.maxLossCents,
    thesisOverride: settings.thesisOverride
  });
}

/**
 * The request state that must leave Scout IDLE. Each missing declaration is named, because §3.5's
 * requirement is not merely "do not default" — it is "name what remains to be chosen".
 */
function undeclaredScoutRequest(missing) {
  const absent = missing || ['goal', 'horizon', 'risk posture'];
  return {
    options: {
      universe: DEFAULT_UNIVERSE.slice(),
      horizons: absent.includes('horizon') ? [] : ['month'],
      intents: absent.includes('goal') ? [] : ['INCOME'],
      riskMode: absent.includes('risk posture') ? '' : 'balanced'
    },
    missingDeclarations: absent,
    expectedErrorCode: 'DESK_DECLARATION_REQUIRED',
    expectedMessage: 'The opportunity scan requires an explicit ' + absent.join(', ')
      + '; no decision default was substituted.'
  };
}

/**
 * One Scout state as everything a mock needs to serve it.
 *
 *   `frames`   — the NDJSON lines, in order (empty for idle)
 *   `ndjson`   — those frames as the exact newline-delimited body the stream sends
 *   `body`     — the plain-JSON equivalent, for the non-streaming path
 *   `status`   — the HTTP status the route answers with
 *   `requested`— whether the endpoint is called at all in this state
 */
function scoutState(state, options) {
  wire.oneOf('scout state', state, SCOUT_STATES);
  const settings = Object.assign({ pickCount: 3, total: DEFAULT_UNIVERSE.length }, options || {});

  if (state === 'idle') {
    return {
      requested: false,
      status: null,
      frames: [],
      ndjson: '',
      body: null,
      declaration: undeclaredScoutRequest(settings.missingDeclarations)
    };
  }

  if (state === 'error') {
    // Both failure shapes, because the client handles them on different paths: a non-OK response
    // never reaches the frame reader, and an in-stream error frame arrives after a 200.
    const frames = [
      { type: 'progress', progress: progress('SCANNING', 2, settings.total) },
      { type: 'error', error: 'The opportunity scan could not finish: the provider rate-limited the universe.' }
    ];
    return {
      requested: true,
      status: 200,
      frames: frames,
      ndjson: frames.map(frame => JSON.stringify(frame)).join('\n') + '\n',
      body: null,
      httpFailure: {
        status: 503,
        body: { error: 'scout_unavailable',
          detail: 'The opportunity scan could not finish: the provider rate-limited the universe.' }
      }
    };
  }

  const complete = state === 'complete' || state === 'empty';
  const result = autoResult({ pickCount: state === 'empty' ? 0 : settings.pickCount });
  const frames = [];
  frames.push({ type: 'progress', progress: progress('STARTED', 0, settings.total) });
  frames.push({ type: 'progress', progress: progress('SCANNING', 1, settings.total) });
  if (state === 'partial') {
    // A partial scan has already ranked something; the pick rides the progress frame so Home can
    // show real rows while the rest of the universe is still being read.
    frames.push({ type: 'progress',
      progress: progress('RANKED', 2, settings.total, { pick: pick(0) }) });
    frames.push({ type: 'progress',
      progress: progress('SCANNING', 3, settings.total) });
  }
  if (complete) {
    frames.push({ type: 'progress',
      progress: progress('SCANNING', settings.total, settings.total) });
    frames.push({ type: 'complete', result: result });
  }
  return {
    requested: true,
    status: 200,
    frames: frames,
    ndjson: frames.map(frame => JSON.stringify(frame)).join('\n') + (complete ? '\n' : ''),
    body: complete ? result : null,
    partialPicks: state === 'partial' ? [pick(0)] : []
  };
}

module.exports = {
  SCOUT_STATES, DEFAULT_UNIVERSE,
  signals, volatilityEvidence, eventEvidence, opportunityContext, bestIdea, pick, progress,
  autoResult, scoutRequest, undeclaredScoutRequest, scoutState
};
