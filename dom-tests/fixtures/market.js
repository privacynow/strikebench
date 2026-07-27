'use strict';

/**
 * The four market lanes — quote, history, chain and news — each in the four states §16.3 requires
 * them to be exercisable in INDEPENDENTLY: ready, stale, missing and error.
 *
 * "Independently" is the point. The Desk's failure mode is not that everything breaks at once; it
 * is a fresh chart beside a stale price, or a working chain beside a news lane that 500s, and a
 * surface that answers with a verdict anyway. Every builder here therefore takes its own state,
 * and `marketDocuments()` composes four states rather than one.
 *
 * Every builder returns `{ status, body }`. A missing FACT and a failed REQUEST are different
 * states with different shapes — a missing quote is a 200 carrying `quoteUnavailableReason`, a
 * missing chain is the server's own 404 `ErrorBody` — and collapsing them would hide exactly the
 * distinction §3.2 turns on.
 */

const wire = require('./wire');
const golden = require('./golden');

const LANE_STATES = ['ready', 'stale', 'missing', 'error'];

/** One session behind the frozen instant, for stale lanes. */
const STALE_OBSERVED_AT_MS = wire.OBSERVED_AT_MS - 26 * 60 * 60 * 1000;
const STALE_OBSERVED_AT_ISO = new Date(STALE_OBSERVED_AT_MS).toISOString();

function evidence(provenance, age, source) {
  return { provenance: provenance, age: age, source: source };
}

/** `ApiResponses.ErrorBody`, the shape every failing route answers with. */
function errorBody(error, detail) {
  return { error: error, detail: detail };
}

// ---------------------------------------------------------------------------------------------
// Quote / research detail
// ---------------------------------------------------------------------------------------------

/** `model.Quote`. Prices are BigDecimal on the server and reach the browser as JSON numbers. */
function quote(overrides) {
  const o = Object.assign({
    symbol: golden.SYMBOL,
    description: null,
    last: 250,
    bid: 249.98,
    ask: 250.02,
    prevClose: 247.5,
    dayHigh: 250.8,
    dayLow: 246.9,
    volume: 4820000,
    optionable: true,
    asOfEpochMs: wire.OBSERVED_AT_MS,
    source: 'FIXTURE_EXECUTABLE_BOOK',
    freshness: 'REALTIME'
  }, overrides || {});
  return wire.nonNull(o);
}

/**
 * `ApiResponses.ResearchDetail`.
 *
 * `quote.displayPrice` and `quote.displayChangePct` are the BACKEND's arithmetic —
 * `Quote.mark()` and `Quote.markChangePct()` — carried on the one QuoteView precisely so no surface
 * can choose between a typed receipt and loose ResearchDetail aliases, or recompute
 * `(price/prevClose − 1) × 100` for itself (§3.1). The fixture states them, and states them null
 * together with a reason when the quote is absent.
 */
function researchDetail(state, overrides) {
  wire.oneOf('quote state', state, LANE_STATES);
  const settings = Object.assign({ symbol: golden.SYMBOL }, overrides || {});
  if (state === 'error') {
    return { status: 503, body: errorBody('quote_unavailable',
      `The market provider did not answer for ${settings.symbol}.`) };
  }
  const stale = state === 'stale';
  const missing = state === 'missing';
  const quoteEvidence = missing
    ? evidence('MISSING', 'MISSING', 'quote')
    : evidence('OBSERVED', stale ? 'STALE' : 'REALTIME',
      stale ? 'FIXTURE_LAST_SESSION_CLOSE' : 'FIXTURE_EXECUTABLE_BOOK');
  const current = wire.nonNull({
    symbol: settings.symbol,
    description: null,
    displayPrice: missing ? null : golden.FACTS.anchorSpotCents / 100,
    displayChangePct: missing ? null : golden.FACTS.displayChangePct,
    markBasis: missing ? 'UNAVAILABLE' : 'LAST',
    priceIsPreviousClose: false,
    priced: !missing,
    quoteUnavailableReason: missing
      ? 'No quote was available for this symbol in the active market.' : null,
    last: missing ? null : 250,
    bid: missing ? null : 249.98,
    ask: missing ? null : 250.02,
    prevClose: missing ? null : 247.5,
    optionable: !missing,
    freshness: missing ? 'UNAVAILABLE' : stale ? 'STALE' : 'REALTIME',
    source: missing ? null : stale
      ? 'FIXTURE_LAST_SESSION_CLOSE' : 'FIXTURE_EXECUTABLE_BOOK',
    evidence: quoteEvidence,
    asOf: missing ? null : stale ? STALE_OBSERVED_AT_MS : wire.OBSERVED_AT_MS,
    refreshing: false
  });
  return {
    status: 200,
    body: wire.nonNull({
      symbol: settings.symbol,
      quote: current,
      marketLane: 'OBSERVED',
      optionable: !missing,
      ivAtm: missing ? null : 0.2814,
      ivRankAvailable: !missing,
      ivRankPct: missing ? null : 55,
      ivPercentilePct: missing ? null : 61,
      ivHistoryDays: missing ? 0 : 252,
      ivRankRequiredDays: 120,
      ivRankNote: missing ? 'No implied-volatility history for this symbol.' : 'FIXTURE_IV_HISTORY',
      earningsEstimate: eventEvidence(settings.symbol, 'EARNINGS', missing),
      exDividend: eventEvidence(settings.symbol, 'EX_DIVIDEND', true),
      hv30: missing ? null : 0.2465,
      hvHistoryDays: missing ? 0 : 120,
      hvRequiredDays: 20,
      historyDemo: false,
      historyBarBasis: 'DAILY_SESSION',
      historyPriceBasis: 'SPLIT_ADJUSTED_CLOSE',
      evidence: {
        summary: quoteEvidence,
        inputs: {
          quote: quoteEvidence,
          history: evidence('OBSERVED', 'EOD', 'FIXTURE_DAILY_HISTORY'),
          options: missing
            ? evidence('MISSING', 'MISSING', 'option chain')
            : evidence('OBSERVED', 'REALTIME', 'FIXTURE_CHAIN')
        }
      },
      expirations: missing ? [] : [wire.NEAR_EXPIRATION, wire.FAR_EXPIRATION],
      planEligible: !missing,
      planEligibility: missing
        ? `${settings.symbol} has no usable quote in the active market, so an options Plan cannot `
          + 'be built until the quote returns.'
        : 'Quote, chain and expirations are all present.',
      benchmarks: missing ? [] : [{
        symbol: 'ZBM', last: 512.4, freshness: 'REALTIME',
        evidence: evidence('OBSERVED', 'REALTIME', 'FIXTURE_EXECUTABLE_BOOK')
      }],
      asOfDate: wire.TODAY,
      regime: missing ? null : {
        trend: 'SIDEWAYS',
        trendReturnPct: 0.4,
        trendSessions: 20,
        drawdownPct: -3.1,
        varianceRiskPremium: 0.035,
        ivRankPct: 55,
        eventSoon: false,
        eventBasis: 'No confirmed issuer event before the near expiration.',
        headline: 'Range-bound with a modest volatility premium.',
        basis: 'observed sessions'
      }
    })
  };
}

/** `EventService.EventEvidence`; `unavailable: true` is the honest no-keyless-source state. */
function eventEvidence(symbol, eventType, unavailable) {
  if (unavailable) {
    return wire.nonNull({
      symbol: symbol,
      eventType: eventType,
      status: 'UNAVAILABLE',
      date: null,
      session: null,
      confidenceStart: null,
      confidenceEnd: null,
      sourceKind: null,
      source: null,
      sourceUrl: null,
      observedAt: null,
      payloadFingerprint: null,
      basis: 'No keyless source states this event.',
      note: 'Connect a licensed calendar for confirmed dates.'
    });
  }
  return wire.nonNull({
    symbol: symbol,
    eventType: eventType,
    status: 'ESTIMATED',
    date: '2026-08-06',
    session: 'AFTER_MARKET',
    confidenceStart: '2026-08-04',
    confidenceEnd: '2026-08-11',
    sourceKind: 'DERIVED',
    source: 'FIXTURE_EVENT_CADENCE',
    sourceUrl: null,
    observedAt: wire.OBSERVED_AT_ISO,
    payloadFingerprint: 'e'.repeat(64),
    basis: 'Estimated from the prior four reporting cadences.',
    note: 'Estimated, not confirmed.'
  });
}

// ---------------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------------

/** `model.Candle`. */
function candle(date, close, index) {
  const open = Math.round((close - 1.2) * 100) / 100;
  return {
    date: date,
    open: open,
    high: Math.round((close + 0.9) * 100) / 100,
    low: Math.round((open - 1.1) * 100) / 100,
    close: close,
    volume: 4000000 + index * 12500,
    adjusted: true
  };
}

/** Deterministic session dates counting back from the frozen instant, weekends skipped. */
function sessionDates(count) {
  const dates = [];
  const cursor = new Date(`${wire.TODAY}T00:00:00Z`);
  while (dates.length < count) {
    const day = cursor.getUTCDay();
    if (day !== 0 && day !== 6) dates.push(cursor.toISOString().slice(0, 10));
    cursor.setUTCDate(cursor.getUTCDate() - 1);
  }
  return dates.reverse();
}

/**
 * `ApiResponses.History`, with the server-owned overlays.
 *
 * `HistoryOverlays` values are null until enough trailing history exists — 20 bars for `sma20` and
 * `rv20`, 50 for `sma50` — and `bandUp`/`bandDn` are null wherever either input is. Filling those
 * leading nulls with a plausible number is how a client-side estimator sneaks back in, so the
 * builder emits the nulls.
 */
function history(state, overrides) {
  wire.oneOf('history state', state, LANE_STATES);
  const settings = Object.assign({ symbol: golden.SYMBOL, range: '6M', sessions: 60 },
    overrides || {});
  if (state === 'error') {
    return { status: 502, body: errorBody('history_unavailable',
      `Daily history could not be read for ${settings.symbol}.`) };
  }
  const missing = state === 'missing';
  const stale = state === 'stale';
  const dates = missing ? [] : sessionDates(settings.sessions);
  // A gentle uptrend plus a deterministic oscillation. A perfectly smooth ramp would produce an
  // rv20 of ~0, and a realized-volatility overlay pinned at zero is not a state that exercises
  // anything — the ±1σ band would collapse onto the moving average.
  const candles = dates.map((date, index) => candle(date,
    Math.round((240 + index * 0.18 + 3.2 * Math.sin(index * 1.1)) * 100) / 100, index));
  const closes = candles.map(row => row.close);
  const mean = (values) => values.reduce((total, value) => total + value, 0) / values.length;
  const overlay = (window, project) => closes.map((value, index) =>
    index + 1 < window ? null : project(closes.slice(index + 1 - window, index + 1)));
  const sma20 = overlay(20, mean);
  const sma50 = overlay(50, mean);
  const rv20 = overlay(20, values => {
    const returns = values.slice(1).map((value, index) => Math.log(value / values[index]));
    const average = mean(returns);
    const variance = returns.reduce((total, value) =>
      total + (value - average) * (value - average), 0) / (returns.length - 1);
    return Math.sqrt(variance * 252);
  });
  const band = sign => sma20.map((value, index) => value == null || rv20[index] == null
    ? null : value * Math.exp(sign * rv20[index] * Math.sqrt(21 / 252)));
  return {
    status: 200,
    body: wire.nonNull({
      symbol: settings.symbol,
      range: settings.range,
      candles: candles,
      source: missing ? 'none' : stale ? 'FIXTURE_LAST_SESSION_CLOSE' : 'FIXTURE_DAILY_HISTORY',
      freshness: missing ? 'MISSING' : stale ? 'STALE' : 'EOD',
      barBasis: missing ? null : 'DAILY_SESSION',
      priceBasis: missing ? null : 'SPLIT_ADJUSTED_CLOSE',
      evidence: missing
        ? evidence('MISSING', 'MISSING', 'daily history')
        : evidence('OBSERVED', stale ? 'STALE' : 'EOD',
          stale ? 'FIXTURE_LAST_SESSION_CLOSE' : 'FIXTURE_DAILY_HISTORY'),
      coverage: missing ? null : {
        requestedFrom: dates[0], requestedTo: dates[dates.length - 1],
        firstAvailable: dates[0], lastAvailable: dates[dates.length - 1],
        sessions: dates.length, missingSessions: 0
      },
      overlays: missing ? null : {
        rv20: rv20, sma20: sma20, sma50: sma50, bandUp: band(1), bandDn: band(-1)
      }
    })
  };
}

/**
 * `ResearchController.ExpectedMove`.
 *
 * This is an expiry-level backend receipt, not a client-generated path. Its three prices are
 * intentionally asymmetric around spot so a visual test cannot pass by drawing the old,
 * identical-looking square-root-time cone. Stale or missing anchors remain explicit and must not
 * be drawn as current market evidence.
 */
function expectedMove(state, overrides) {
  wire.oneOf('expected move state', state, LANE_STATES);
  const settings = Object.assign({
    symbol: golden.SYMBOL,
    expiration: wire.NEAR_EXPIRATION,
    anchorSpot: golden.FACTS.anchorSpotCents / 100,
    p16: 229.75,
    p50: 251.35,
    p84: 274.6
  }, overrides || {});
  if (state === 'error') {
    return { status: 502, body: errorBody('expected_move_unavailable',
      `The expected-move receipt could not be read for ${settings.symbol}.`) };
  }
  if (state === 'missing') {
    return {
      status: 200,
      body: {
        symbol: settings.symbol,
        available: false,
        reason: 'no ATM implied volatility for the selected expiration'
      }
    };
  }
  const stale = state === 'stale';
  return {
    status: 200,
    body: {
      symbol: settings.symbol,
      available: true,
      reason: null,
      atmIv: 0.2814,
      expiration: settings.expiration,
      horizonSessions: 20,
      expirationCalendarDays: 28,
      p16: settings.p16,
      p50: settings.p50,
      p84: settings.p84,
      p16MovePct: -8.1,
      p84MovePct: 9.8,
      basis: 'RISK_NEUTRAL_LOGNORMAL_16_50_84',
      anchorSpot: settings.anchorSpot,
      anchorSource: stale ? 'FIXTURE_LAST_SESSION_CLOSE' : 'FIXTURE_EXECUTABLE_BOOK',
      anchorFreshness: stale ? 'STALE' : 'REALTIME',
      asOf: wire.TODAY
    }
  };
}

/** `ApiResponses.Expirations` — every row states its own distance in BOTH units. */
function expirations(state, overrides) {
  wire.oneOf('expirations state', state, LANE_STATES);
  const settings = Object.assign({ symbol: golden.SYMBOL }, overrides || {});
  if (state === 'error') {
    return { status: 502, body: errorBody('expirations_unavailable',
      `Listed expirations could not be read for ${settings.symbol}.`) };
  }
  const missing = state === 'missing';
  return {
    status: 200,
    body: {
      symbol: settings.symbol,
      asOfDate: wire.TODAY,
      expirations: missing ? [] : [
        { date: wire.NEAR_EXPIRATION, tradingSessions: 20, calendarDays: 28 },
        { date: wire.FAR_EXPIRATION, tradingSessions: 40, calendarDays: 56 }
      ]
    }
  };
}

// ---------------------------------------------------------------------------------------------
// Chain
// ---------------------------------------------------------------------------------------------

/** `model.OptionQuote`. */
function optionQuote(overrides) {
  const o = Object.assign({
    underlying: golden.SYMBOL,
    occSymbol: null,
    type: 'CALL',
    strike: 250,
    expiration: wire.NEAR_EXPIRATION,
    bid: 4.2,
    ask: 4.4,
    last: 4.3,
    volume: 1240,
    openInterest: 8600,
    iv: 0.2814,
    delta: 0.51,
    gamma: 0.021,
    theta: -0.086,
    vega: 0.184,
    asOfEpochMs: wire.OBSERVED_AT_MS,
    source: 'FIXTURE_CHAIN',
    freshness: 'REALTIME'
  }, overrides || {});
  if (!o.occSymbol) {
    const yy = o.expiration.slice(2, 4), mm = o.expiration.slice(5, 7), dd = o.expiration.slice(8, 10);
    const strike = String(Math.round(o.strike * 1000)).padStart(8, '0');
    o.occSymbol = `${o.underlying}${yy}${mm}${dd}${o.type === 'CALL' ? 'C' : 'P'}${strike}`;
  }
  return wire.nonNull(o);
}

/**
 * `model.OptionChain`.
 *
 * A `missing` chain is the server's own 404 with an `ErrorBody` — `ResearchController.chain`
 * answers exactly that when `market.chain(...)` is empty — not a 200 carrying empty strike lists.
 * The distinction matters: one is "this expiration has no book", the other is "this expiration
 * does not exist", and the Desk has to tell the reader which.
 */
function chain(state, overrides) {
  wire.oneOf('chain state', state, LANE_STATES);
  const settings = Object.assign({
    symbol: golden.SYMBOL, expiration: wire.NEAR_EXPIRATION, strikes: [240, 245, 250, 255, 260]
  }, overrides || {});
  if (state === 'error') {
    return { status: 502, body: errorBody('chain_unavailable',
      `The option chain could not be read for ${settings.symbol}.`) };
  }
  if (state === 'missing') {
    return { status: 404, body: errorBody('no_chain',
      `No option chain for ${settings.symbol} ${settings.expiration}`) };
  }
  const stale = state === 'stale';
  const spot = golden.FACTS.anchorSpotCents / 100;
  const side = type => settings.strikes.map(strike => {
    const moneyness = type === 'CALL' ? spot - strike : strike - spot;
    const mid = Math.max(0.05, Math.round((Math.max(0, moneyness) + 3.4) * 100) / 100);
    return optionQuote({
      underlying: settings.symbol,
      type: type,
      strike: strike,
      expiration: settings.expiration,
      bid: Math.round((mid - 0.05) * 100) / 100,
      ask: Math.round((mid + 0.05) * 100) / 100,
      last: mid,
      delta: type === 'CALL' ? 0.5 + (spot - strike) / 100 : -0.5 + (spot - strike) / 100,
      asOfEpochMs: stale ? STALE_OBSERVED_AT_MS : wire.OBSERVED_AT_MS,
      source: stale ? 'FIXTURE_LAST_SESSION_CHAIN' : 'FIXTURE_CHAIN',
      freshness: stale ? 'STALE' : 'REALTIME'
    });
  });
  return {
    status: 200,
    body: {
      underlying: settings.symbol,
      expiration: settings.expiration,
      underlyingPrice: spot,
      calls: side('CALL'),
      puts: side('PUT'),
      asOfEpochMs: stale ? STALE_OBSERVED_AT_MS : wire.OBSERVED_AT_MS,
      source: stale ? 'FIXTURE_LAST_SESSION_CHAIN' : 'FIXTURE_CHAIN',
      freshness: stale ? 'STALE' : 'REALTIME'
    }
  };
}

// ---------------------------------------------------------------------------------------------
// News
// ---------------------------------------------------------------------------------------------

const HEADLINES = [
  ['Quarterly results beat the prior guidance range', 'POSITIVE', 0.62, ['beat'], [], true, ['EARNINGS', 'RESULTS']],
  ['Supply agreement extended through the next fiscal year', 'NEUTRAL', 0.0, [], [], false, []],
  ['Regulator opens an investigation into pricing practices', 'NEGATIVE', -0.55, [], ['investigation'], false, []],
  ['Analyst upgrade cites accelerating unit growth', 'POSITIVE', 0.48, ['upgrade', 'growth'], [], false, []],
  ['Guidance narrowed as input costs ease', 'MIXED', 0.08, ['eases'], [], true, ['GUIDANCE']]
];

/** One `NewsSentimentScorer.HeadlineSentiment`. */
function headline(index, symbol) {
  const row = HEADLINES[index % HEADLINES.length];
  return {
    symbol: symbol,
    headline: `${row[0]} (${index + 1})`,
    source: 'Fixture Newswire',
    url: `https://example.invalid/fixture/${index + 1}`,
    publishedEpochMs: wire.OBSERVED_AT_MS - (index + 1) * 3600000,
    classification: row[1],
    score: row[2],
    basis: 'KEYWORD_DERIVED',
    positiveKeywords: row[3],
    negativeKeywords: row[4],
    eventRisk: row[5],
    eventRiskFlags: row[6],
    scorerVersion: 'sentiment-keyword-v1'
  };
}

/**
 * `ApiResponses.ResearchNews` at the §16.3 counts (0, 5, 20).
 *
 * An EMPTY news lane is `available: false` on the aggregate with a stated basis — `score(...)`
 * treats an empty input as unavailable, never as silently neutral, and a "neutral" reading of no
 * headlines at all is a fabricated conclusion (§3.2).
 */
function news(state, overrides) {
  wire.oneOf('news state', state, LANE_STATES);
  const settings = Object.assign({ symbol: golden.SYMBOL, count: 5 }, overrides || {});
  wire.oneOf('news(count)', settings.count, [0, 5, 20]);
  if (state === 'error') {
    return { status: 502, body: errorBody('news_unavailable',
      `Headlines could not be read for ${settings.symbol}.`) };
  }
  const missing = state === 'missing';
  const stale = state === 'stale';
  const count = missing ? 0 : settings.count;
  const items = [];
  for (let index = 0; index < count; index += 1) items.push(headline(index, settings.symbol));
  if (stale) {
    items.forEach(item => { item.publishedEpochMs -= 5 * 24 * 3600000; });
  }
  const countBy = classification =>
    items.filter(item => item.classification === classification).length;
  const eventRiskItems = items.filter(item => item.eventRisk);
  const available = items.length > 0;
  const scored = items.filter(item => item.score != null);
  return {
    status: 200,
    body: wire.nonNull({
      symbol: settings.symbol,
      scorerVersion: 'sentiment-keyword-v1',
      items: items,
      aggregate: {
        available: available,
        trend: available ? 'MIXED' : 'UNAVAILABLE',
        score: available
          ? Math.round(scored.reduce((total, item) => total + item.score, 0) / scored.length * 1e4) / 1e4
          : null,
        totalHeadlines: items.length,
        scoredHeadlines: scored.length,
        positiveHeadlines: countBy('POSITIVE'),
        negativeHeadlines: countBy('NEGATIVE'),
        mixedHeadlines: countBy('MIXED'),
        neutralHeadlines: countBy('NEUTRAL'),
        coverageRatio: available ? scored.length / items.length : 0,
        eventRisk: eventRiskItems.length > 0,
        eventRiskHeadlines: eventRiskItems.length,
        eventRiskFlags: Array.from(new Set(eventRiskItems
          .reduce((flags, item) => flags.concat(item.eventRiskFlags), []))),
        basis: available ? 'KEYWORD_DERIVED' : 'UNAVAILABLE',
        scorerVersion: 'sentiment-keyword-v1',
        note: available
          ? (stale ? 'Latest headline is more than five sessions old.' : null)
          : 'No headlines were available for this symbol, so no sentiment was scored.'
      },
      eventRisk: eventRiskItems,
      evidence: available ? 'OBSERVED' : 'UNAVAILABLE',
      note: available ? null
        : 'No headlines were available for this symbol, so no sentiment was scored.'
    })
  };
}

/**
 * The four lanes composed, each carrying its own state. The default is the all-ready world; every
 * degraded combination the matrix needs is one argument away.
 */
function marketDocuments(options) {
  const settings = Object.assign({
    symbol: golden.SYMBOL,
    quote: 'ready',
    history: 'ready',
    chain: 'ready',
    news: 'ready',
    newsCount: 5,
    historySessions: 60,
    expectedMove: null,
    // Expirations and the chain both come off the option surface, so an unstated expirations
    // state follows the chain's rather than staying implausibly healthy beside a dead book. Pass
    // it explicitly to break them apart — listed expirations with no chain behind them is a real
    // state, and it is the one that strands the strike picker.
    expirations: null
  }, options || {});
  return {
    symbol: settings.symbol,
    research: researchDetail(settings.quote, { symbol: settings.symbol }),
    history: history(settings.history, { symbol: settings.symbol,
      sessions: settings.historySessions }),
    expectedMove: expectedMove(settings.expectedMove || settings.chain, {
      symbol: settings.symbol
    }),
    expirations: expirations(settings.expirations || settings.chain, { symbol: settings.symbol }),
    chain: chain(settings.chain, { symbol: settings.symbol }),
    news: news(settings.news, { symbol: settings.symbol, count: settings.newsCount })
  };
}

module.exports = {
  LANE_STATES, STALE_OBSERVED_AT_MS, STALE_OBSERVED_AT_ISO,
  quote, researchDetail, eventEvidence, candle, sessionDates, history, expectedMove, expirations,
  optionQuote, chain, headline, news, marketDocuments, errorBody, evidence
};
