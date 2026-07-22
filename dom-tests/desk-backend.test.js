'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const PUBLIC = path.resolve(__dirname, '../src/main/resources/public');
const CANDIDATE_ID = 'candidate_backend_debit';
const CUSTOM_CANDIDATE_ID = 'candidate_backend_exact_package';
const ENSEMBLE_ID = 'ensemble_desk_1';
const ENSEMBLE_FINGERPRINT = 'ensemble-fingerprint-desk-1';
const PLAN_ID = 'plan_desk_test';
const SIM_PLAN_ID = 'plan_desk_simulated_test';
const SIM_WORLD_ID = 'sim_desk_amd';
const SIM_DATASET_ID = 'dataset-desk-simulated-test';
const ACCOUNT_ID = 'account_desk_test';
const DATASET_ID = 'dataset-desk-test';
const WORLD_REVISION = 41;
const WORLD_EPOCH = 'observed-epoch-desk-41';
const BOOK_TRADE_ID = 'trade_backend_book_receipt';
const BOOK_PLAN_ID = 'plan_backend_book_receipt';
const BOOK_ENSEMBLE_ID = 'ensemble_backend_book_receipt';
const BOOK_ENSEMBLE_FINGERPRINT = 'ensemble-fingerprint-backend-book-receipt';
const SECOND_BOOK_TRADE_ID = 'trade_backend_book_second';
const SECOND_BOOK_PLAN_ID = 'plan_backend_book_second';
const SECOND_BOOK_ENSEMBLE_ID = 'ensemble_backend_book_second';
const SECOND_BOOK_ENSEMBLE_FINGERPRINT = 'ensemble-fingerprint-backend-book-second';

let browser;
let server;
let deskUrl;

function contentType(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.css')) return 'text/css; charset=utf-8';
  if (file.endsWith('.svg')) return 'image/svg+xml';
  return 'application/octet-stream';
}

function servePublic(req, res) {
  const requestUrl = new URL(req.url, 'http://127.0.0.1');
  const pathname = requestUrl.pathname === '/' ? '/index.html' : decodeURIComponent(requestUrl.pathname);
  const file = path.resolve(PUBLIC, `.${pathname}`);
  if (file !== PUBLIC && !file.startsWith(`${PUBLIC}${path.sep}`)) {
    res.writeHead(403).end('forbidden');
    return;
  }
  fs.readFile(file, (error, body) => {
    if (error) {
      res.writeHead(error.code === 'ENOENT' ? 404 : 500).end(error.message);
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' });
    res.end(body);
  });
}

before(async () => {
  browser = await chromium.launch({ headless: true });
  server = http.createServer(servePublic);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  deskUrl = `http://127.0.0.1:${server.address().port}/index.html`;
});

after(async () => {
  if (browser) await browser.close();
  if (server) await new Promise(resolve => server.close(resolve));
});

function plan(version, overrides = {}) {
  const has = key => Object.prototype.hasOwnProperty.call(overrides, key);
  return {
    id: has('id') ? overrides.id : PLAN_ID,
    version,
    open: has('open') ? overrides.open : true,
    status: has('status') ? overrides.status : 'ACTIVE',
    assumptionsEditable: has('assumptionsEditable') ? overrides.assumptionsEditable : true,
    symbol: has('symbol') ? overrides.symbol : 'AMD',
    intent: has('intent') ? overrides.intent : 'INCOME',
    marketKind: has('marketKind') ? overrides.marketKind : 'OBSERVED',
    worldId: has('worldId') ? overrides.worldId : null,
    accountId: has('accountId') ? overrides.accountId : ACCOUNT_ID,
    context: {
      thesis: has('thesis') ? overrides.thesis : 'neutral',
      horizonDays: has('horizonDays') ? overrides.horizonDays : 45,
      riskMode: has('riskMode') ? overrides.riskMode : 'balanced',
      rev: has('contextRev') ? overrides.contextRev : 7
    }
  };
}

function emptyBookDocuments() {
  return {
    activeTrades: [],
    sharePositions: [],
    summary: {
      cashCents: 10000000,
      reservedCents: 0,
      buyingPowerCents: 10000000,
      startingCashCents: 10000000,
      sharesValueCents: 0,
      sharesPositions: 0,
      openTradesCount: 0,
      openTradesValueCents: 0,
      openTradesUnrealizedCents: 0,
      totalValueCents: 10000000,
      totalPnlCents: 0,
      complete: true,
      freshness: 'FRESH',
      note: 'Authoritative empty Practice account receipt.'
    },
    heat: {
      activeTrades: 0,
      totalMaxLossCents: 0,
      shortVolTrades: 0,
      concentrationPct: 0,
      earlyAssignmentLiquidityCents: 0,
      physicalAssignmentCashCents: 0,
      postPhysicalAssignmentBuyingPowerCents: 10000000
    },
    greeks: {
      positions: [],
      deltaShares: 0,
      gammaShares: 0,
      thetaPerDay: 0,
      vegaPerPoint: 0,
      complete: true,
      note: 'No active Practice positions.'
    },
    bookRisk: {
      accounts: [],
      crossAccount: null,
      practice: {
        deltaShares: 0,
        dollarDeltaNetCents: 0,
        dollarDeltaGrossCents: 0,
        gammaShares: 0,
        thetaPerDay: 0,
        vegaPerPoint: 0,
        complete: true,
        basis: 'PRACTICE_EXECUTABLE_MARKS'
      },
      basis: 'PRACTICE_EXECUTABLE_MARKS'
    },
    planPortfolio: []
  };
}

function positionStoredEnsemble() {
  return {
    plan: plan(31, { id: BOOK_PLAN_ID, symbol: 'AAPL' }),
    ensemble: {
      id: BOOK_ENSEMBLE_ID,
      fingerprint: BOOK_ENSEMBLE_FINGERPRINT,
      basis: 'PARAMETRIC'
    },
    preview: {
      paths: 500,
      horizonDays: 21,
      pathModelVersion: 'position-path-model-test-v1',
      samples: [
        [222.22, 221.5, 224.1],
        [222.22, 223.4, 226.2],
        [222.22, 219.8, 220.7]
      ],
      receipt: {
        symbol: 'AAPL',
        worldId: 'observed',
        datasetId: DATASET_ID,
        contextRev: 7,
        modelVersion: 'position-path-model-test-v1',
        asOf: '2026-07-20T16:00:00Z',
        anchorSpot: 222.22,
        anchorSource: 'BOOK_TEST_EXECUTABLE_RECEIPT',
        anchorFreshness: 'FRESH'
      }
    }
  };
}

function measuredJointBookReceipt() {
  const stepBands = [0, 15, 30, 45].map((sessionProgress, index) => ({
    step: sessionProgress,
    sessionProgress,
    pnlP5Cents: [-0, -42000, -88000, -135000][index],
    pnlP10Cents: [0, -30000, -65000, -94000][index],
    pnlP25Cents: [0, -10000, -24000, -36000][index],
    pnlP50Cents: [0, 12000, 28000, 46000][index],
    pnlP75Cents: [0, 33000, 67000, 104000][index],
    pnlP90Cents: [0, 52000, 98000, 151000][index],
    pnlP95Cents: [0, 68000, 124000, 188000][index]
  }));
  const displayPaths = [
    [0, -18000, 12000, 46000],
    [0, 22000, 54000, 104000],
    [0, -42000, -88000, -135000]
  ].map((values, sourcePathIndex) => ({
    sourcePathIndex,
    role: sourcePathIndex === 0 ? 'FOCUS' : 'CONTEXT',
    steps: values.map((pnlCents, index) => ({
      step: stepBands[index].step,
      sessionProgress: stepBands[index].sessionProgress,
      pnlCents
    }))
  }));
  return {
    available: true,
    unavailableReason: null,
    anchorDate: '2026-07-20',
    correlation: {
      available: true,
      alignedSessions: 252,
      pairs: [],
      basis: 'Exact shared dated sessions; no fill-forward.'
    },
    scenario: {
      jointFingerprint: 'joint-book-browser-receipt',
      modelVersion: 'scenario-canvas-1+joint-book-1',
      pathCount: 600,
      positionCount: 1,
      horizonSessions: 45,
      stepBands,
      displayPaths,
      positions: [],
      terminalP5Cents: -135000,
      terminalP50Cents: 46000,
      terminalP95Cents: 188000,
      expectedTerminalPnlCents: 51000,
      chanceOfGainPct: 61.5,
      p10MaxDrawdownCents: -98000,
      assignments: { chanceAnyAssignmentPct: 0 },
      tailScenarios: [],
      notes: ['P/L aggregates only synchronized source path indexes.']
    },
    annualRate: 0.04,
    rateEvidence: { provenance: 'MODELED', source: 'modeled-default' },
    basis: 'Measured joint Book browser sentinel; no provider history was acquired automatically.'
  };
}

function populatedBookDocuments() {
  const trade = {
    id: BOOK_TRADE_ID,
    symbol: 'AAPL',
    strategy: 'CALL_DEBIT_SPREAD',
    status: 'ACTIVE',
    qty: 2,
    legs: [
      {
        type: 'CALL', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
        multiplier: 100, strike: 215, expiration: '2026-08-21', entryPrice: 5.25
      },
      {
        type: 'CALL', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
        multiplier: 100, strike: 225, expiration: '2026-08-21', entryPrice: 3.09
      }
    ],
    thesis: 'Backend-owned AAPL position sentinel.',
    horizon: 'month',
    riskMode: 'balanced',
    entryUnderlyingCents: 21111,
    entryNetPremiumCents: -43210,
    maxLossCents: 43210,
    maxProfitCents: 156790,
    breakevens: [217.16],
    popEntry: 0.57,
    feesOpenCents: 260,
    feesCloseCents: 260,
    realizedPnlCents: 0,
    decisionPnlCents: 0,
    entrySnapshot: { source: 'BOOK_TEST_EXECUTABLE_RECEIPT', freshness: 'FRESH' },
    isLive: false,
    createdAt: '2026-07-20T15:00:00Z',
    updatedAt: '2026-07-20T16:00:00Z',
    intent: 'DIRECTIONAL',
    sharesLocked: 0,
    proposedNetCents: -43210,
    dataProvenance: 'OBSERVED',
    dataAge: 'CURRENT',
    dataSource: 'BOOK_TEST_EXECUTABLE_RECEIPT',
    unrealizedPnlCents: 24680,
    decisionUnrealizedPnlCents: 24680
  };
  const current = {
    tradeId: BOOK_TRADE_ID,
    ts: '2026-07-20T16:00:00Z',
    underlyingCents: 22222,
    closeCostCents: 18530,
    unrealizedCents: 24680,
    decisionUnrealizedCents: 24680,
    popNow: 0.64,
    freshness: 'FRESH',
    greeks: {
      deltaShares: 37.25,
      gammaShares: 1.75,
      thetaPerDay: -12.34,
      vegaPerPoint: 18.5,
      complete: true
    },
    legGreeks: []
  };
  return {
    activeTrades: [trade],
    sharePositions: [],
    tradeDetail: {
      trade,
      current,
      marksHistory: [
        Object.assign({}, current, { ts: '2026-07-19T16:00:00Z', unrealizedCents: 11220 }),
        current
      ],
      audit: [],
      payoff: [
        { price: 200, profitCents: -43210 },
        { price: 222.22, profitCents: 24680 },
        { price: 230, profitCents: 156790 }
      ]
    },
    summary: {
      cashCents: 9752000,
      reservedCents: 43210,
      buyingPowerCents: 9708790,
      startingCashCents: 10000000,
      sharesValueCents: 0,
      sharesPositions: 0,
      openTradesCount: 1,
      openTradesValueCents: 124543,
      openTradesUnrealizedCents: 24680,
      totalValueCents: 9876543,
      totalPnlCents: -123457,
      complete: true,
      freshness: 'FRESH',
      note: 'Backend summary sentinel; reserve remains inside cash; BEFORE close fees.'
    },
    heat: {
      activeTrades: 1,
      totalMaxLossCents: 43210,
      shortVolTrades: 0,
      concentrationPct: 37,
      earlyAssignmentLiquidityCents: 0,
      physicalAssignmentCashCents: 0,
      postPhysicalAssignmentBuyingPowerCents: 9708790
    },
    greeks: Object.assign({ positions: [{ id: BOOK_TRADE_ID }] }, current.greeks, {
      note: 'Backend aggregate Greeks sentinel.'
    }),
    bookRisk: {
      accounts: [],
      crossAccount: null,
      practice: {
        deltaShares: 37.25,
        dollarDeltaNetCents: 827695,
        dollarDeltaGrossCents: 827695,
        gammaShares: 1.75,
        thetaPerDay: -12.34,
        vegaPerPoint: 18.5,
        complete: true,
        basis: 'PRACTICE_EXECUTABLE_MARKS'
      },
      basis: 'PRACTICE_EXECUTABLE_MARKS'
    },
    planPortfolio: [{
      plan: plan(31, { id: BOOK_PLAN_ID, symbol: 'AAPL' }),
      decision: { action: 'TRADE', tradeId: BOOK_TRADE_ID },
      tradeId: BOOK_TRADE_ID,
      mark: current
    }],
    research: {
      symbol: 'AAPL',
      quote: {
        symbol: 'AAPL', bid: 222.20, ask: 222.24, last: 222.22,
        source: 'BOOK_TEST_RESEARCH_RECEIPT', freshness: 'FRESH', asOfEpochMs: 1784563200000
      },
      displayPrice: 222.22,
      marketLane: 'OBSERVED',
      freshness: 'FRESH',
      evidence: { summary: { source: 'BOOK_TEST_RESEARCH_RECEIPT', provenance: 'OBSERVED' } },
      expirations: ['2026-08-21'],
      planEligible: true
    },
    history: {
      symbol: 'AAPL',
      source: 'BOOK_TEST_HISTORY_RECEIPT',
      freshness: 'EOD',
      candles: [
        { date: '2026-07-17', open: 218, high: 221, low: 217, close: 220 },
        { date: '2026-07-20', open: 220, high: 223, low: 219, close: 222.22 }
      ]
    },
    expirations: {
      symbol: 'AAPL', source: 'BOOK_TEST_CHAIN_RECEIPT', freshness: 'FRESH',
      expirations: ['2026-08-21', '2026-09-18']
    },
    chain: {
      underlying: 'AAPL', expiration: '2026-08-21', underlyingPrice: 222.22,
      source: 'BOOK_TEST_CHAIN_RECEIPT', freshness: 'FRESH',
      calls: [
        { strike: 215, bid: 10.1, ask: 10.4 },
        { strike: 220, bid: 6.8, ask: 7.1 },
        { strike: 225, bid: 3.9, ask: 4.2 }
      ],
      puts: [
        { strike: 215, bid: 2.6, ask: 2.9 },
        { strike: 220, bid: 4.6, ask: 4.9 },
        { strike: 225, bid: 7.8, ask: 8.2 }
      ]
    },
    news: {
      symbol: 'AAPL',
      evidence: 'BOOK_TEST_NEWS_RECEIPT',
      items: [{
        headline: 'Backend research sentinel headline',
        summary: 'This copy exists only in the authoritative research response.',
        source: 'Backend Newswire',
        classification: 'MARKET_NEWS',
        basis: 'OBSERVED',
        url: 'https://example.test/backend-research-sentinel',
        publishedAt: '2026-07-20T15:30:00Z'
      }]
    },
    management: {
      plan: plan(31, { id: BOOK_PLAN_ID, symbol: 'AAPL' }),
      decision: { action: 'TRADE', tradeId: BOOK_TRADE_ID },
      management: {
        actions: [{ kind: 'MARK', createdAt: '2026-07-20T16:00:00Z' }],
        links: [{ relation: 'ENTRY', tradeId: BOOK_TRADE_ID }]
      },
      trade: { trade },
      adoptionReviews: []
    },
    positionEnsemble: positionStoredEnsemble()
  };
}

function focusedHomeDocuments(symbol, price) {
  const base = populatedBookDocuments();
  const copy = key => JSON.parse(JSON.stringify(base[key]));
  const documents = {
    research: copy('research'), history: copy('history'), expirations: copy('expirations'),
    chain: copy('chain'), news: copy('news')
  };
  documents.research.symbol = symbol;
  documents.research.displayPrice = price;
  Object.assign(documents.research.quote, {
    symbol, bid: price - 0.02, ask: price + 0.02, last: price, prevClose: price - 1
  });
  documents.history.symbol = symbol;
  documents.history.candles = documents.history.candles.map((candle, index) => Object.assign({}, candle, {
    open: price - 2 + index, high: price + index, low: price - 3 + index, close: price - 1 + index
  }));
  documents.expirations.symbol = symbol;
  documents.chain.underlying = symbol;
  documents.chain.underlyingPrice = price;
  documents.news.symbol = symbol;
  documents.news.items[0].headline = `${symbol} focused research receipt`;
  return documents;
}

function twoPositionBookDocuments() {
  const documents = populatedBookDocuments();
  const secondPlan = plan(32, { id: SECOND_BOOK_PLAN_ID, symbol: 'AAPL' });
  const secondTrade = JSON.parse(JSON.stringify(documents.activeTrades[0]));
  Object.assign(secondTrade, {
    id: SECOND_BOOK_TRADE_ID,
    strategy: 'PUT_DEBIT_SPREAD',
    thesis: 'Backend-owned second AAPL position ownership sentinel.',
    entryNetPremiumCents: -28700,
    proposedNetCents: -28700,
    maxLossCents: 28700,
    maxProfitCents: 71300,
    breakevens: [207.87],
    popEntry: 0.49,
    unrealizedPnlCents: -8400,
    decisionUnrealizedPnlCents: -8400
  });
  secondTrade.legs = [
    {
      type: 'PUT', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 210, expiration: '2026-08-21', entryPrice: 4.72
    },
    {
      type: 'PUT', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 200, expiration: '2026-08-21', entryPrice: 1.85
    }
  ];
  const secondDetail = JSON.parse(JSON.stringify(documents.tradeDetail));
  secondDetail.trade = secondTrade;
  secondDetail.current = Object.assign({}, secondDetail.current, {
    tradeId: SECOND_BOOK_TRADE_ID,
    closeCostCents: 37100,
    unrealizedCents: -8400,
    decisionUnrealizedCents: -8400,
    popNow: 0.46
  });
  secondDetail.marksHistory = secondDetail.marksHistory.map((mark, index) => Object.assign({}, mark, {
    tradeId: SECOND_BOOK_TRADE_ID,
    unrealizedCents: index ? -8400 : -3900,
    decisionUnrealizedCents: index ? -8400 : -3900
  }));
  secondDetail.payoff = [
    { price: 190, profitCents: 71300 },
    { price: 207.87, profitCents: 0 },
    { price: 222.22, profitCents: -28700 }
  ];
  const secondManagement = JSON.parse(JSON.stringify(documents.management));
  secondManagement.plan = secondPlan;
  secondManagement.decision.tradeId = SECOND_BOOK_TRADE_ID;
  secondManagement.management.links = [{ relation: 'ENTRY', tradeId: SECOND_BOOK_TRADE_ID }];
  secondManagement.trade = { trade: secondTrade };
  const secondEnsemble = JSON.parse(JSON.stringify(documents.positionEnsemble));
  secondEnsemble.plan = secondPlan;
  secondEnsemble.ensemble.id = SECOND_BOOK_ENSEMBLE_ID;
  secondEnsemble.ensemble.fingerprint = SECOND_BOOK_ENSEMBLE_FINGERPRINT;

  documents.activeTrades.push(secondTrade);
  documents.summary.openTradesCount = 2;
  documents.heat.activeTrades = 2;
  documents.heat.totalMaxLossCents += secondTrade.maxLossCents;
  documents.greeks.positions.push({ id: SECOND_BOOK_TRADE_ID });
  documents.planPortfolio.push({
    plan: secondPlan,
    decision: { action: 'TRADE', tradeId: SECOND_BOOK_TRADE_ID },
    tradeId: SECOND_BOOK_TRADE_ID,
    mark: secondDetail.current
  });
  documents.tradeDetails = {
    [BOOK_TRADE_ID]: documents.tradeDetail,
    [SECOND_BOOK_TRADE_ID]: secondDetail
  };
  documents.managementByPlan = {
    [BOOK_PLAN_ID]: documents.management,
    [SECOND_BOOK_PLAN_ID]: secondManagement
  };
  documents.positionEnsembleByPlan = {
    [BOOK_PLAN_ID]: documents.positionEnsemble,
    [SECOND_BOOK_PLAN_ID]: secondEnsemble
  };
  return documents;
}

function positionIdentity(overrides = {}) {
  return Object.assign({
    family: 'DEBIT_CALL_SPREAD',
    template: null,
    label: 'Debit call spread',
    summary: 'Buy a lower-strike call and sell a higher-strike call for defined upside participation.',
    definedRisk: true,
    blockedByDefault: false,
    custom: false
  }, overrides);
}

function strategyCatalog() {
  return {
    families: [
      'CALL_DEBIT_SPREAD', 'CASH_SECURED_PUT', 'PROTECTIVE_PUT', 'NAKED_CALL'
    ],
    catalog: [
      {
        name: 'CALL_DEBIT_SPREAD',
        display: 'Debit call spread',
        category: 'Directional',
        summary: 'Defined-risk upside participation with a sold call funding part of the debit.',
        payoffShape: '2,26 25,26 45,5 62,5',
        foundationalRank: 2,
        definedRisk: true,
        blockedByDefault: false,
        scenarioEnabled: true,
        backtestEnabled: true,
        recommendationEnabled: true,
        primaryIntent: 'DIRECTIONAL',
        intents: ['DIRECTIONAL']
      },
      {
        name: 'CASH_SECURED_PUT',
        display: 'Cash-secured put',
        category: 'Income',
        summary: 'Collect premium while reserving enough cash to buy shares at the strike.',
        payoffShape: '2,26 26,26 45,8 62,8',
        foundationalRank: 1,
        definedRisk: true,
        blockedByDefault: false,
        scenarioEnabled: true,
        backtestEnabled: true,
        recommendationEnabled: true,
        primaryIntent: 'INCOME',
        intents: ['INCOME', 'ACQUIRE']
      },
      {
        name: 'PROTECTIVE_PUT',
        display: 'Protective put',
        category: 'Shares & protection',
        summary: 'Keep share upside while adding a defined downside floor.',
        payoffShape: '2,24 24,24 62,4',
        foundationalRank: 3,
        definedRisk: true,
        blockedByDefault: false,
        scenarioEnabled: true,
        backtestEnabled: true,
        recommendationEnabled: true,
        primaryIntent: 'HEDGE',
        intents: ['HEDGE']
      },
      {
        name: 'NAKED_CALL',
        display: 'Naked call',
        category: 'Undefined risk',
        summary: 'An uncovered short call has unlimited rally risk.',
        payoffShape: '2,6 28,6 62,27',
        foundationalRank: 99,
        definedRisk: false,
        blockedByDefault: true,
        scenarioEnabled: false,
        backtestEnabled: false,
        recommendationEnabled: false,
        primaryIntent: 'INCOME',
        intents: ['INCOME']
      }
    ],
    templates: []
  };
}

function candidate() {
  return {
    id: CANDIDATE_ID,
    label: 'Backend debit call spread',
    displayName: 'Backend debit call spread',
    strategy: 'CALL_DEBIT_SPREAD',
    symbol: 'AMD',
    qty: 1,
    entryNetPremiumCents: -12345,
    maxLossCents: 12345,
    maxProfitCents: 87655,
    assignmentProb: 0.08,
    breakevens: [101.23],
    freshness: 'FRESH',
    sourceKind: 'BACKEND_TEST_RECEIPT',
    whyConsidered: 'Selected by the backend recommendation service.',
    identity: positionIdentity(),
    legs: [
      {
        type: 'CALL', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
        multiplier: 100, strike: 100, expiration: '2026-08-21', entryPrice: 6
      },
      {
        type: 'CALL', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
        multiplier: 100, strike: 110, expiration: '2026-08-21', entryPrice: 2
      }
    ],
    evaluation: {
      available: true,
      assessment: {
        mechanics: { eligible: true },
        coherence: {
          verdict: 'COHERENT',
          reasons: ['The backend package fits the declared objective and duration.']
        },
        economics: {
          verdict: 'FAVORABLE',
          placement: 'WORTH_INVESTIGATING',
          label: 'Worth investigating',
          marketEvAfterCostsCents: -5200,
          realizedVolEvAfterCostsCents: 1450,
          realisticEvLowAfterCostsCents: -300,
          realisticEvHighAfterCostsCents: 3400,
          realisticEvMaterialityCents: 1000,
          realisticEvBasis: 'REALIZED_VOL_ZERO_DRIFT_SENSITIVITY',
          marketEvRole: 'Risk-neutral price-consistency and cost benchmark; not an independent edge test.',
          observedEvidence: true
        }
      },
      capital: { incrementalCents: 12345 },
      risk: {
        pop: 0.63,
        expectedValueCents: 1450,
        terminalPayoff: {
          schemaVersion: 'risk-terminal-payoff-1',
          modelVersion: 'payoff-curve-1',
          available: true,
          anchorSpotCents: 10000,
          expiration: '2026-08-21',
          basis: 'EXPIRATION_INTRINSIC',
          entryBasis: 'CAPTURED_CANDIDATE_NET',
          feesIncluded: false,
          points: [
            { price: 90, profitCents: -99900 },
            { price: 100, profitCents: 77700 },
            { price: 110, profitCents: 22200 }
          ]
        },
        scenarios: [
          { underlyingMovePct: -0.20, pnlCents: -12345 },
          { underlyingMovePct: 0, pnlCents: 77700 },
          { underlyingMovePct: 0.20, pnlCents: 22200 }
        ]
      }
    }
  };
}

function incoherentCandidate() {
  const row = candidate();
  row.id = 'candidate_backend_incoherent';
  row.label = 'Backend adjacent alternative';
  row.displayName = 'Backend adjacent alternative';
  row.evaluation.assessment.coherence = {
    verdict: 'INCOHERENT',
    reasons: ['This package conflicts with the declared objective.']
  };
  return row;
}

function unfavorableCandidate() {
  const row = candidate();
  row.id = 'candidate_backend_unfavorable';
  row.label = 'Backend adverse comparison';
  row.displayName = 'Backend adverse comparison';
  row.evaluation.assessment.economics = {
    verdict: 'UNFAVORABLE',
    placement: 'LEARN_FROM',
    label: 'Unfavorable at these prices',
    summary: 'The package is mechanically valid, but its modeled after-cost economics are adverse.',
    marketEvAfterCostsCents: -1800,
    realizedVolEvAfterCostsCents: -37000,
    realisticEvLowAfterCostsCents: -42000,
    realisticEvHighAfterCostsCents: -32000,
    realisticEvBasis: 'REALIZED_VOL_ZERO_DRIFT_SENSITIVITY',
    marketEvRole: 'Risk-neutral price-consistency and cost benchmark; not an independent edge test.',
    observedEvidence: true
  };
  return row;
}

function favorableMixedFitCandidate() {
  const row = candidate();
  row.id = 'candidate_backend_favorable_mixed_fit';
  row.label = 'Backend favorable mixed-fit comparison';
  row.displayName = 'Backend favorable mixed-fit comparison';
  row.evaluation.assessment.coherence = {
    verdict: 'MIXED',
    reasons: ['The package has favorable economics but only partially matches the declared direction.']
  };
  row.evaluation.assessment.economics.summary =
    'The observed realistic-measure point estimate shows a material after-cost advantage.';
  return row;
}

function fourLegCandidate() {
  const row = candidate();
  row.id = 'candidate_backend_four_leg';
  row.label = 'Backend canonical iron condor';
  row.displayName = 'Backend canonical iron condor';
  row.strategy = 'IRON_CONDOR';
  row.identity = positionIdentity({
    family: 'IRON_CONDOR', label: 'Iron condor',
    summary: 'A defined-risk range package with strictly ordered protective wings.'
  });
  row.legs = [
    { type: 'PUT', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 90, expiration: '2026-08-21', entryPrice: 1 },
    { type: 'PUT', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 95, expiration: '2026-08-21', entryPrice: 2 },
    { type: 'CALL', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 105, expiration: '2026-08-21', entryPrice: 2 },
    { type: 'CALL', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 110, expiration: '2026-08-21', entryPrice: 1 }
  ];
  return row;
}

function customCandidate(position) {
  const preview = customTradePreview(position).preview;
  return {
    id: CUSTOM_CANDIDATE_ID,
    selected: true,
    label: 'Exact backend debit spread',
    displayName: 'Exact backend debit spread',
    strategy: 'CUSTOM',
    symbol: 'AMD',
    qty: position.qty,
    entryNetPremiumCents: preview.entryNetPremiumCents,
    maxLossCents: preview.maxLossCents,
    maxProfitCents: preview.maxProfitCents,
    assignmentProb: 0.04,
    breakevens: preview.breakevens,
    freshness: 'FRESH',
    sourceKind: 'BUILDER',
    whyConsidered: 'Exact package selected after backend repricing.',
    identity: positionIdentity(),
    legs: position.legs.map((leg, index) => Object.assign({}, leg, {
      entryPrice: index === 0 ? 6.2 : 3.1
    })),
    evaluation: {
      available: true,
      assessment: {
        mechanics: { eligible: true },
        economics: {
          marketEvAfterCostsCents: -800,
          realizedVolEvAfterCostsCents: 2121,
          realisticEvLowAfterCostsCents: 900,
          realisticEvHighAfterCostsCents: 3300,
          realisticEvBasis: 'REALIZED_VOL_ZERO_DRIFT_SENSITIVITY'
        }
      },
      capital: { incrementalCents: 31000 },
      risk: {
        pop: preview.popEntry,
        expectedValueCents: 2121,
        terminalPayoff: {
          schemaVersion: 'risk-terminal-payoff-1',
          modelVersion: 'payoff-curve-1',
          available: true,
          anchorSpotCents: 10000,
          expiration: '2026-08-21',
          basis: 'EXPIRATION_INTRINSIC',
          entryBasis: 'CAPTURED_CANDIDATE_NET',
          feesIncluded: false,
          points: preview.payoff
        },
        scenarios: []
      }
    }
  };
}

function customTradePreview(position) {
  const valid = Array.isArray(position.legs) && position.legs.length === 2;
  const blockReasons = valid ? [] : ['A one-leg draft is blocked by the backend risk service.'];
  return {
    preview: {
      ok: valid,
      entryNetPremiumCents: valid ? -31000 : -62000,
      maxLossCents: valid ? 31000 : 62000,
      maxProfitCents: valid ? 69000 : null,
      reserveCents: valid ? 31000 : 62000,
      popEntry: valid ? 0.61 : null,
      breakevens: valid ? [103.1] : [],
      freshness: 'FRESH',
      evidence: { source: 'BACKEND_TEST_RECEIPT', lane: 'OBSERVED' },
      blockReasons,
      legs: (position.legs || []).map((leg, index) => ({
        leg,
        fill: index === 0 ? 6.2 : 3.1
      })),
      payoff: valid ? [
        { price: 90, profitCents: -31000 },
        { price: 100, profitCents: 424200 },
        { price: 110, profitCents: 69000 }
      ] : []
    },
    evaluation: { available: valid },
    guardrails: {
      level: valid ? 'PASS' : 'BLOCK',
      blockReasons,
      warnings: []
    },
    requiredAcks: [],
    ackToken: valid ? 'ack-draft-test' : null,
    identity: positionIdentity()
  };
}

function ensemble(version = 13, market = {}) {
  const spot = market.spot == null ? 100 : market.spot;
  const symbol = market.symbol || 'AMD';
  const shift = spot - 100;
  const ensembleId = market.id || ENSEMBLE_ID;
  const fingerprint = market.fingerprint || ENSEMBLE_FINGERPRINT;
  const asOf = new Date(market.asOf == null ? 1784563200000 : market.asOf).toISOString();
  const atmIv = market.atmIv == null ? 0.30 : market.atmIv;
  const expiration = market.expiration || '2026-08-21';
  const shifted = rows => rows.map(value => value + shift);
  const samples = Array.from({ length: 48 }, (_, pathIndex) => Array.from({ length: 11 }, (_, step) => {
    const trend = (pathIndex - 23.5) * step / 118;
    const noise = Math.sin((pathIndex + 3) * (step + 1) * 0.71) * (0.25 + step * 0.08);
    return Number((spot + trend + noise).toFixed(2));
  }));
  const stepBands = Array.from({ length: 11 }, (_, step) => ({
    step, sessionProgress: step * 2.1,
    p10: spot - step * 0.45, p25: spot - step * 0.22,
    p50: spot + step * 0.08, p75: spot + step * 0.34, p90: spot + step * 0.58
  }));
  const sampleSourcePathIndices = samples.map((unused, index) => 100 + index);
  const sampleFocusIndex = Math.floor(samples.length / 2);
  const pnlStepBands = stepBands.map((band, step) => ({
    step, sessionProgress: band.sessionProgress,
    pnlP10Cents: -18000 - step * 1200,
    pnlP25Cents: -9000 - step * 400,
    pnlP50Cents: step * 700,
    pnlP75Cents: 9000 + step * 950,
    pnlP90Cents: 18000 + step * 1600
  }));
  const pnlDisplayPaths = samples.map((path, pathIndex) => ({
    sourcePathIndex: sampleSourcePathIndices[pathIndex],
    role: pathIndex === sampleFocusIndex ? 'FOCUS' : 'CONTEXT',
    steps: path.map((price, step) => ({
      step, sessionProgress: step * 2.1,
      pnlCents: Math.round((price - spot) * 10000 + step * 350)
    }))
  }));
  return {
    plan: plan(version),
    ensemble: {
      id: ensembleId,
      fingerprint,
      basis: 'PARAMETRIC'
    },
    preview: {
      paths: 500,
      horizonDays: 21,
      endP50: 104,
      pathModelVersion: 'path-model-test-v1',
      samples,
      sampleSourcePathIndices,
      sampleFocusIndex,
      stepBands,
      bands: [
        { p10: 100, p50: 100, p90: 100 },
        { p10: 98, p50: 101, p90: 103 },
        { p10: 99, p50: 102, p90: 106 },
        { p10: 101, p50: 104, p90: 108 }
      ],
      receipt: {
        symbol,
        worldId: 'observed',
        datasetId: DATASET_ID,
        asOf,
        anchorSpot: spot,
        anchorSource: 'BACKEND_TEST_RECEIPT',
        anchorFreshness: 'FRESH',
        modelVersion: 'path-model-test-v1',
        spec: { volAnnual: atmIv }
      },
      marketImplied: { atmIv, expiration },
      canvasModel: { valuationStepDays: 7 },
      canvas: {
        displayPathRule: 'TERMINAL_QUANTILES',
        displayPathCount: samples.length,
        displayPathSourceIndices: sampleSourcePathIndices,
        underlying: [
          { day: 0, p10: 100, p50: 100, p90: 100, focusPrice: 100, atmIv: 0.30 },
          { day: 21, p10: 101, p50: 104, p90: 108, focusPrice: 104, atmIv: 0.29 }
        ],
        positions: [{
          key: `PROPOSED:${CANDIDATE_ID}`,
          proposed: true,
          stepBands: pnlStepBands,
          displayPaths: pnlDisplayPaths,
          days: [
            { focusValueCents: -12345, focusPnlCents: 0, greeks: {} },
            { focusValueCents: 9000, focusPnlCents: 21345, greeks: {} }
          ]
        }]
      }
    }
  };
}

function decisionPreview(requestBody, selected = candidate(), version = 14) {
  const instruction = requestBody.orderInstruction;
  const limit = instruction.limitNetCents;
  const proposedNetCents = selected.entryNetPremiumCents;
  const executable = instruction.type === 'MARKET' || limit <= proposedNetCents;
  const isCustom = selected.id === CUSTOM_CANDIDATE_ID;
  const restingReason = executable ? null
    : 'Your limit is more favorable than the executable market. The order is RESTING and is not presently executable.';
  return {
    plan: plan(version),
    selected,
    preview: {
      entryNetPremiumCents: proposedNetCents,
      maxLossCents: selected.maxLossCents,
      maxProfitCents: selected.maxProfitCents,
      reserveCents: selected.maxLossCents,
      popEntry: selected.evaluation?.risk?.pop ?? selected.pop,
      breakevens: selected.breakevens,
      ok: executable,
      blockReasons: restingReason ? [restingReason] : [],
      freshness: 'FRESH',
      evidence: { source: 'BACKEND_TEST_RECEIPT', lane: 'OBSERVED' },
      payoff: isCustom ? customTradePreview({ legs: selected.legs }).preview.payoff : [
        { price: 90, profitCents: -99900 },
        { price: 100, profitCents: 77700 },
        { price: 110, profitCents: 22200 }
      ]
    },
    evaluation: {},
    guardrails: { level: executable ? 'PASS' : 'BLOCK',
      blockReasons: restingReason ? [restingReason] : [], warnings: [] },
    requiredAcks: [],
    ackToken: 'ack-desk-test',
    order: {
      qty: requestBody.qty,
      proposedNetCents,
      feesOverrideCents: 0,
      orderInstruction: instruction,
      executability: executable ? 'IMMEDIATE' : 'RESTING',
      presentlyExecutable: executable,
      executableNetCents: proposedNetCents,
      valuedNetCents: executable ? proposedNetCents : limit,
      valuationBasis: executable ? 'EXECUTABLE_BOOK' : 'RESTING_LIMIT'
    }
  };
}

function scenarioResponse(marker, overrides = {}) {
  const valuationFingerprint = `valuation-${marker}`;
  const focusSourcePathIndex = 17;
  const targetRatio = marker === 'second' ? 1.05 : marker === 'invalid' ? 1.09 : 0.95;
  const hasRequestBody = overrides.requestBody && typeof overrides.requestBody === 'object';
  const requestedWaypoints = hasRequestBody
    ? (Array.isArray(overrides.requestBody.waypoints) ? overrides.requestBody.waypoints : [])
    : [{ dayIndex: 10, priceRatio: targetRatio, tolerance: 0.02 }];
  const requestedPathWaypoints = hasRequestBody && Array.isArray(overrides.requestBody.pathWaypoints)
    ? overrides.requestBody.pathWaypoints : [];
  const conditioningPathWaypoints = requestedPathWaypoints.length
    ? requestedPathWaypoints
    : requestedWaypoints.map(pin => ({
        sessionProgress: pin.dayIndex,
        priceRatio: pin.priceRatio,
        ...(pin.tolerance == null ? {} : { tolerance: pin.tolerance })
      }));
  const focusPrices = marker === 'second'
    ? [100, 101.8, 101.1, 103.4, 102.7, 105, 104.2, 106.1, 105.4, 107, 106.2]
    : marker === 'invalid'
      ? [100, 102.5, 101.7, 105.2, 104.1, 109, 107.4, 110.1, 108.8, 111, 109.5]
      : [100, 98.4, 99.1, 97.2, 97.9, 95, 95.8, 94.1, 94.9, 93.6, 94.3];
  const contextOne = marker === 'first'
    ? [100, 98.9, 99.5, 97.6, 98.4, 95.7, 96.3, 94.8, 95.2, 94.1, 94.8]
    : [100, 101.2, 100.7, 102.9, 102.1, targetRatio * 100 - 0.8,
      103.9, 105.2, 104.7, 106.1, 105.6];
  const contextTwo = marker === 'first'
    ? [100, 97.8, 98.7, 96.9, 97.3, 94.4, 95.2, 93.8, 94.6, 93.1, 94]
    : [100, 102.1, 101.4, 103.8, 103, targetRatio * 100 + 0.9,
      105, 106.8, 105.9, 107.6, 106.9];
  const scenarioPricePaths = [
    { sourcePathIndex: 9, role: 'CONTEXT', prices: contextOne },
    { sourcePathIndex: focusSourcePathIndex, role: 'FOCUS', prices: focusPrices },
    { sourcePathIndex: 31, role: 'CONTEXT', prices: contextTwo }
  ];
  const scenarioPnlPaths = scenarioPricePaths.map(row => ({
    sourcePathIndex: row.sourcePathIndex,
    role: row.role,
    steps: row.prices.map((price, step) => ({
      step, sessionProgress: step,
      pnlCents: Math.round((price - 100) * 8200 + step * 420)
    }))
  }));
  const scenarioPnlBands = focusPrices.map((price, step) => {
    const middle = Math.round((price - 100) * 8200 + step * 420);
    return {
      step, sessionProgress: step,
      pnlP10Cents: middle - step * 1800 - 4000,
      pnlP25Cents: middle - step * 900 - 2000,
      pnlP50Cents: middle,
      pnlP75Cents: middle + step * 900 + 2000,
      pnlP90Cents: middle + step * 1800 + 4000
    };
  });
  const receipt = Object.assign({
    ensembleId: ENSEMBLE_ID,
    ensembleFingerprint: ENSEMBLE_FINGERPRINT,
    selectedCandidateId: CANDIDATE_ID,
    contextRev: 7,
    worldId: 'observed',
    datasetId: DATASET_ID,
    valuationFingerprint,
    pathModelVersion: 'path-model-test-v1',
    anchorSource: 'BACKEND_TEST_RECEIPT',
    anchorFreshness: 'FRESH',
    conditioningAssumptions: {
      horizonDays: 21,
      waypoints: requestedWaypoints
    },
    conditioningPathWaypoints
  }, overrides.receipt || {});
  return {
    testMarker: marker,
    plan: plan(14),
    ensemble: { id: ENSEMBLE_ID, fingerprint: ENSEMBLE_FINGERPRINT },
    receipt,
    paths: {
      totalPathCount: 500,
      paths: scenarioPricePaths,
      receipt: {
        sourcePathCount: 500,
        returnedPathCount: 3,
        withinToleranceCount: 18,
        focusSourcePathIndex
      }
    },
    checkpoints: {
      displayPathRule: 'NEAREST_AUTHORED_WAYPOINTS',
      displayPathCount: scenarioPricePaths.length,
      displayPathSourceIndices: scenarioPricePaths.map(row => row.sourcePathIndex),
      focusSourcePathIndex,
      modelReceipt: { valuationFingerprint },
      underlying: [
        { day: 0, p10: 100, p50: 100, p90: 100, focusPrice: 100, atmIv: 0.30 },
        { day: 10, p10: 98, p50: 102, p90: 110, focusPrice: targetRatio * 100, atmIv: 0.32 }
      ],
      underlyingSteps: [
        { step: 0, sessionProgress: 0, focusPrice: 100, atmIv: 0.30 },
        { step: 1, sessionProgress: 5, focusPrice: marker === 'first' ? 97 : marker === 'invalid' ? 106 : 103, atmIv: 0.31 },
        { step: 2, sessionProgress: 10, focusPrice: targetRatio * 100, atmIv: 0.32 }
      ],
      positions: [{
        key: `PROPOSED:${CANDIDATE_ID}`,
        proposed: true,
        stepBands: scenarioPnlBands,
        displayPaths: scenarioPnlPaths,
        days: [
          { focusValueCents: -12345, focusPnlCents: 0, greeks: {} },
          { focusValueCents: 2500, focusPnlCents: 14845, greeks: {} }
        ],
        steps: [
          { step: 0, sessionProgress: 0, focusValueCents: -12345, focusPnlCents: 0, greeks: {} },
          { step: 1, sessionProgress: 5, focusValueCents: -7345, focusPnlCents: 5000, greeks: {} },
          { step: 2, sessionProgress: 10, focusValueCents: 2500, focusPnlCents: 14845, greeks: {} }
        ]
      }]
    }
  };
}

function positionScenarioResponse(body, options = {}) {
  const focusSourcePathIndex = 43;
  const expectedFocus = options.positionScenarioWrongFocus
    ? 'trade_from_another_position' : BOOK_TRADE_ID;
  const valuationFingerprint = 'position-valuation-fingerprint-test';
  const focusedPackageFingerprint = '6f2d1cc75a85109c281787666209feba3f5e0b9bd37a27cb6675bd4492e3f158';
  const focusedPackageProvenance = {
    contractVersion: 'focused-position-package-2',
    key: expectedFocus,
    source: 'PRACTICE_TRADE',
    lane: 'PRACTICE',
    symbol: 'AAPL',
    packageQuantity: 1,
    legCount: 2,
    exactPackageCashCents: -6150,
    entryBasisCents: 6250,
    valuationAsOf: '2026-07-20T00:00:00Z',
    entryCreatedAt: '2026-07-18T15:42:00Z',
    dataProvenance: 'OBSERVED',
    dataAge: 'FRESH',
    dataSource: 'BOOK_TEST_EXECUTABLE_RECEIPT',
    entrySnapshotFingerprint: '1e295802bc0d204326b8ce9c0f1170e48ba906bb3c926d79f0aca5f220949467',
    priceAuthorities: ['OBSERVED']
  };
  const returnedFingerprint = options.positionScenarioWrongFingerprint
    ? 'ensemble-fingerprint-from-another-fan' : BOOK_ENSEMBLE_FINGERPRINT;
  const normalizedPathWaypoints = Array.isArray(body.pathWaypoints) && body.pathWaypoints.length
    ? body.pathWaypoints
    : (body.waypoints || []).map(pin => ({
        sessionProgress: pin.dayIndex,
        priceRatio: pin.priceRatio,
        ...(pin.tolerance == null ? {} : { tolerance: pin.tolerance })
      }));
  const positionSourcePaths = [
    { sourcePathIndex: 11, role: 'CONTEXT', pnl: [24680, 13100, 18200, -8200, -28600, -42800] },
    { sourcePathIndex: focusSourcePathIndex, role: 'FOCUS', pnl: [24680, 15000, 17500, -5000, -25000, -43200] },
    { sourcePathIndex: 70, role: 'CONTEXT', pnl: [24680, 16400, 11200, 3300, -21400, -40100] }
  ];
  const positionStepBands = [24680, 15000, 17500, -5000, -25000, -43200]
    .map((middle, step) => ({
      step, sessionProgress: step,
      pnlP10Cents: middle - step * 2800 - 5000,
      pnlP25Cents: middle - step * 1300 - 2500,
      pnlP50Cents: middle,
      pnlP75Cents: middle + step * 1400 + 2500,
      pnlP90Cents: middle + step * 3000 + 5000
    }));
  return {
    plan: plan(31, { id: BOOK_PLAN_ID, symbol: 'AAPL' }),
    ensemble: { id: BOOK_ENSEMBLE_ID, fingerprint: returnedFingerprint, basis: 'PARAMETRIC' },
    receipt: {
      contractVersion: 'scenario-animation-1',
      ensembleId: BOOK_ENSEMBLE_ID,
      ensembleFingerprint: returnedFingerprint,
      pathModelVersion: 'position-path-model-test-v1',
      symbol: 'AAPL',
      worldId: 'observed',
      datasetId: DATASET_ID,
      contextRev: 7,
      focusPositionKey: expectedFocus,
      valuationFingerprint,
      focusedPackageFingerprint,
      focusedPackageProvenance,
      anchorSource: 'BOOK_TEST_EXECUTABLE_RECEIPT',
      anchorFreshness: 'FRESH',
      conditioningAssumptions: { waypoints: body.waypoints },
      conditioningPathWaypoints: options.positionScenarioWrongPathWaypoints
        ? [{ sessionProgress: 1, priceRatio: 1.2 }]
        : normalizedPathWaypoints
    },
    paths: {
      totalPathCount: 500,
      selection: 'NEAREST_AUTHORED_WAYPOINTS',
      bandBasis: 'CONDITIONED_NEAREST_QUINTILE_PLUS_FULL_TOLERANCE_SET',
      bandPathCount: 100,
      bands: [222.22, 215.1, 218.2, 199.9, 190.3, 177.78].map((median, step) => ({
        step, sessionProgress: step,
        p10: median - step * 2.4, p25: median - step * 1.1,
        p50: median, p75: median + step * 1.2, p90: median + step * 2.6
      })),
      paths: [
        {
          sourcePathIndex: 11,
          role: 'CONTEXT',
          prices: [222.22, 214.8, 217.4, 201.2, 194.8, 181.4]
        },
        {
          sourcePathIndex: focusSourcePathIndex,
          role: 'FOCUS',
          prices: [222.22, 215.1, 218.2, 199.9, 190.3, 177.78]
        },
        {
          sourcePathIndex: 70,
          role: 'CONTEXT',
          prices: [222.22, 217.4, 211.6, 215, 196.1, 183.2]
        }
      ],
      receipt: {
        requestedLimit: body.limit,
        returnedPathCount: 3,
        sourcePathCount: 500,
        withinToleranceCount: 21,
        rule: 'NEAREST_AUTHORED_WAYPOINTS',
        focusSourcePathIndex
      }
    },
    checkpoints: {
      displayPathRule: 'NEAREST_AUTHORED_WAYPOINTS',
      displayPathCount: positionSourcePaths.length,
      displayPathSourceIndices: positionSourcePaths.map(row => row.sourcePathIndex),
      focusSourcePathIndex,
      modelReceipt: {
        ensembleFingerprint: returnedFingerprint,
        focusPositionKey: expectedFocus,
        focusSourcePathIndex,
        valuationFingerprint,
        focusedPackageFingerprint,
        focusedPackageProvenance
      },
      underlying: [
        {
          day: 0, p10: 222.22, p50: 222.22, p90: 222.22,
          focusPrice: 222.22, atmIv: 0.27
        },
        {
          day: 5, p10: 174.6, p50: 181.4, p90: 186.7,
          focusPrice: 177.78, atmIv: 0.39
        }
      ],
      underlyingSteps: [
        { step: 0, sessionProgress: 0, focusPrice: 222.22, atmIv: 0.27 },
        { step: 1, sessionProgress: 1, focusPrice: 215.1, atmIv: 0.30 },
        { step: 2, sessionProgress: 2, focusPrice: 218.2, atmIv: 0.31 },
        { step: 3, sessionProgress: 3, focusPrice: 199.9, atmIv: 0.34 },
        { step: 4, sessionProgress: 4, focusPrice: 190.3, atmIv: 0.37 },
        { step: 5, sessionProgress: 5, focusPrice: 177.78, atmIv: 0.39 }
      ],
      positions: [{
        key: expectedFocus,
        source: 'PRACTICE_TRADE',
        stepBands: positionStepBands,
        displayPaths: positionSourcePaths.map(row => ({
          sourcePathIndex: row.sourcePathIndex,
          role: row.role,
          steps: row.pnl.map((pnlCents, step) => ({ step, sessionProgress: step, pnlCents }))
        })),
        days: [
          {
            day: 0, focusValueCents: 18530, focusPnlCents: 24680,
            greeks: {
              deltaShares: 37.25, gammaSharesPerDollar: 1.75,
              thetaCentsPerDay: -1234, vegaCentsPerPoint: 1850
            }
          },
          {
            day: 5, focusValueCents: -49350, focusPnlCents: -43200,
            greeks: {
              deltaShares: 4.5, gammaSharesPerDollar: 0.32,
              thetaCentsPerDay: -315, vegaCentsPerPoint: 640
            }
          }
        ],
        steps: [
          {
            step: 0, sessionProgress: 0, focusValueCents: 18530, focusPnlCents: 24680,
            greeks: {
              deltaShares: 37.25, gammaSharesPerDollar: 1.75,
              thetaCentsPerDay: -1234, vegaCentsPerPoint: 1850
            }
          },
          {
            step: 1, sessionProgress: 1, focusValueCents: 8830, focusPnlCents: 15000,
            greeks: {
              deltaShares: 31.8, gammaSharesPerDollar: 1.42,
              thetaCentsPerDay: -1090, vegaCentsPerPoint: 1620
            }
          },
          {
            step: 2, sessionProgress: 2, focusValueCents: 11330, focusPnlCents: 17500,
            greeks: {
              deltaShares: 28.4, gammaSharesPerDollar: 1.17,
              thetaCentsPerDay: -910, vegaCentsPerPoint: 1410
            }
          },
          {
            step: 3, sessionProgress: 3, focusValueCents: -11150, focusPnlCents: -5000,
            greeks: {
              deltaShares: 18.2, gammaSharesPerDollar: 0.88,
              thetaCentsPerDay: -720, vegaCentsPerPoint: 1120
            }
          },
          {
            step: 4, sessionProgress: 4, focusValueCents: -31150, focusPnlCents: -25000,
            greeks: {
              deltaShares: 9.7, gammaSharesPerDollar: 0.55,
              thetaCentsPerDay: -480, vegaCentsPerPoint: 820
            }
          },
          {
            step: 5, sessionProgress: 5, focusValueCents: -49350, focusPnlCents: -43200,
            greeks: {
              deltaShares: 4.5, gammaSharesPerDollar: 0.32,
              thetaCentsPerDay: -315, vegaCentsPerPoint: 640
            }
          }
        ]
      }]
    }
  };
}

async function installBackend(page, options = {}) {
  const requests = [];
  let scenarioCalls = 0;
  let scenarioFailuresRemaining = Number(options.scenarioFailures || 0);
  let selectFailuresRemaining = Number(options.selectFailures || 0);
  let declarationFailuresRemaining = Number(options.declarationFailures || 0);
  let staleCreateResponsesRemaining = options.staleCreatePlan ? 1 : 0;
  let planVersion = 10;
  let selectedCandidate = null;
  let activeWorld = 'observed';
  let activeMarketLane = 'OBSERVED';
  let activeDataset = DATASET_ID;
  let worldRevision = WORLD_REVISION;
  let worldEpoch = WORLD_EPOCH;
  let simulatedPlanCreated = false;
  let simulatedSession = null;
  let currentPlanSymbol = String(options.ideaSymbol || 'AMD').toUpperCase();
  const marketFreshness = options.marketFreshness || 'FRESH';
  const bookDocuments = options.bookDocuments || emptyBookDocuments();
  const homeContextBySymbol = options.homeContextBySymbol || {};
  const homeContextDelayBySymbol = options.homeContextDelayBySymbol || {};
  const catalogDocument = Object.prototype.hasOwnProperty.call(options, 'strategyCatalog')
    ? options.strategyCatalog : strategyCatalog();
  const tradeDetails = bookDocuments.tradeDetails || (bookDocuments.tradeDetail
    ? { [BOOK_TRADE_ID]: bookDocuments.tradeDetail } : {});
  const managementByPlan = bookDocuments.managementByPlan || (bookDocuments.management
    ? { [BOOK_PLAN_ID]: bookDocuments.management } : {});
  const positionEnsembleByPlan = bookDocuments.positionEnsembleByPlan
    || (bookDocuments.positionEnsemble
      ? { [BOOK_PLAN_ID]: bookDocuments.positionEnsemble } : {});
  const bookPlanIds = new Set((bookDocuments.planPortfolio || [])
    .map(row => row?.plan?.id).filter(Boolean));
  const sourceCandidates = options.strategyCandidates === undefined
    ? [candidate()] : options.strategyCandidates;
  const strategyCandidates = sourceCandidates.map(row => options.marketFreshness
    ? Object.assign({}, row, { freshness: marketFreshness }) : row);
  const strategyRejected = options.strategyRejected || [];
  const strategyNotes = options.strategyNotes || [];
  let hasCompetition = false;
  let strategyRunNumber = 0;
  let strategyRunId = null;
  const strategyInputHash = 'f'.repeat(64);
  let quote = Object.assign({
    symbol: 'AMD', bid: 99, ask: 101, last: 100,
    source: 'BACKEND_TEST_RECEIPT', freshness: marketFreshness, asOf: 1784563200000,
    evidence: { source: 'BACKEND_TEST_RECEIPT', lane: 'OBSERVED', provenance: 'OBSERVED' }
  }, options.quote || {});
  let chainIv = 0.30;
  let chainAsOf = 1784563200000;
  let currentExpiration = '2026-08-21';
  let ensembleGeneration = 0;
  let storedEnsemble = null;
  let storedOutcomes = [];
  let researchFailuresRemaining = options.failResearchOnce ? 1 : 0;
  const planOverrides = Object.assign({
    worldId: options.observedWorldId === undefined ? null : options.observedWorldId,
    riskMode: Object.prototype.hasOwnProperty.call(options, 'planRiskMode')
      ? options.planRiskMode : 'balanced',
    contextRev: 7
  }, options.activePlanOverrides || {});

  function currentPlanId() {
    return activeWorld === 'observed' ? PLAN_ID : SIM_PLAN_ID;
  }

  function currentProvenance() {
    return activeMarketLane === 'SIMULATED' ? 'SIMULATED' : 'OBSERVED';
  }

  function currentPlan(version = planVersion) {
    return plan(version, Object.assign({}, planOverrides, {
      id: currentPlanId(),
      symbol: currentPlanSymbol,
      marketKind: activeWorld === 'observed' ? 'OBSERVED' : 'SIMULATED',
      worldId: activeWorld === 'observed' ? planOverrides.worldId : activeWorld
    }));
  }

  function rejectDeclarationWithConflict(route) {
    declarationFailuresRemaining -= 1;
    Object.assign(planOverrides, options.declarationConflictOverrides || {});
    planOverrides.contextRev = Number(planOverrides.contextRev || 0) + 1;
    planVersion += 1;
    selectedCandidate = null;
    storedEnsemble = null;
    storedOutcomes = [];
    hasCompetition = false;
    return route.fulfill({ status: 409, contentType: 'application/json',
      body: JSON.stringify({ error: 'Plan version conflict.' }) });
  }

  function strategyState() {
    return {
      runId: strategyRunId,
      state: 'CURRENT',
      inputHash: strategyInputHash,
      createdAt: '2026-07-20T16:00:00Z',
      result: {
        candidates: strategyCandidates,
        rejected: strategyRejected,
        notes: strategyNotes,
        strategyRunId,
        strategyRunState: 'CURRENT'
      }
    };
  }

  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    const body = request.postData() ? request.postDataJSON() : null;
    requests.push({ method, path: url.pathname, query: url.search, body });
    const activePlanPath = `/api/plans/${currentPlanId()}`;
    const tradeDetailMatch = url.pathname.match(/^\/api\/trades\/([^/]+)$/);
    const tradeDetailId = tradeDetailMatch && decodeURIComponent(tradeDetailMatch[1]);
    const manageMatch = url.pathname.match(/^\/api\/plans\/([^/]+)\/manage$/);
    const managePlanId = manageMatch && decodeURIComponent(manageMatch[1]);
    const positionEnsembleMatch = url.pathname.match(
      /^\/api\/plans\/([^/]+)\/outcomes\/ensemble\/latest$/);
    const positionEnsemblePlanId = positionEnsembleMatch
      && decodeURIComponent(positionEnsembleMatch[1]);
    const homeResearchMatch = url.pathname.match(
      /^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news))?$/);
    const homeResearchSymbol = homeResearchMatch
      && decodeURIComponent(homeResearchMatch[1]).toUpperCase();
    const homeResearchPart = homeResearchMatch && (homeResearchMatch[2] || 'research');

    let response;
    if (method === 'GET' && url.pathname === '/api/strategies' && catalogDocument) {
      if (options.strategyCatalogDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.strategyCatalogDelayMs));
      }
      response = catalogDocument;
    } else if (method === 'GET' && url.pathname === '/api/config') {
      response = {
        fixturesOnly: false,
        world: activeWorld,
        activeDataset,
        activeDatasetName: activeMarketLane === 'OBSERVED'
          ? 'Desk observed test dataset' : 'Desk simulated test dataset',
        marketLane: activeMarketLane,
        scenarioMode: activeMarketLane === 'SIMULATED'
      };
    } else if (method === 'GET' && url.pathname === '/api/status') {
      response = { ok: true, status: 'READY', fixturesOnly: false };
    } else if (method === 'GET' && url.pathname === '/api/world') {
      response = {
        world: options.failWorldVerification && activeWorld !== 'observed' ? 'observed' : activeWorld,
        revision: worldRevision,
        epoch: worldEpoch
      };
    } else if (method === 'GET' && url.pathname === '/api/sim/market') {
      response = { sessions: simulatedSession ? [simulatedSession] : [] };
    } else if (method === 'POST' && url.pathname === '/api/sim/market') {
      simulatedSession = {
        id: SIM_WORLD_ID,
        status: 'CREATED',
        name: body.name,
        rehearsal: false,
        config: {
          symbolBetas: options.simExclusionReason ? {} : { AMD: 1 }
        }
      };
      response = { worldId: SIM_WORLD_ID, status: 'CREATED' };
    } else if (method === 'GET' && url.pathname === `/api/sim/market/${SIM_WORLD_ID}/anchors`) {
      response = {
        worldId: SIM_WORLD_ID,
        anchors: options.simExclusionReason ? [] : [{ symbol: 'AMD', anchorPrice: 100 }],
        excluded: options.simExclusionReason
          ? [{ symbol: 'AMD', reason: options.simExclusionReason }] : []
      };
    } else if (method === 'POST' && url.pathname === `/api/sim/market/${SIM_WORLD_ID}/start`) {
      simulatedSession = Object.assign({}, simulatedSession, { status: 'RUNNING' });
      response = { worldId: SIM_WORLD_ID, status: 'RUNNING' };
    } else if (method === 'PUT' && url.pathname === '/api/world') {
      if (options.failWorldPut) {
        await route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: 'The simulated market could not become active.' }) });
        return;
      }
      activeWorld = body.world;
      activeMarketLane = activeWorld === 'observed' ? 'OBSERVED' : 'SIMULATED';
      activeDataset = activeWorld === 'observed' ? DATASET_ID : SIM_DATASET_ID;
      worldRevision += 1;
      worldEpoch = `${activeWorld}-epoch-desk-${worldRevision}`;
      quote = Object.assign({}, quote, {
        source: activeMarketLane === 'OBSERVED' ? 'BACKEND_TEST_RECEIPT' : 'SIMULATED_DESK_TEST',
        freshness: activeMarketLane === 'OBSERVED' ? marketFreshness : 'FRESH',
        asOf: quote.asOf + 60000,
        evidence: {
          source: activeMarketLane === 'OBSERVED' ? 'BACKEND_TEST_RECEIPT' : 'SIMULATED_DESK_TEST',
          lane: activeMarketLane,
          provenance: currentProvenance()
        }
      });
      planVersion = activeWorld === 'observed' ? 10 : 20;
      selectedCandidate = null;
      hasCompetition = false;
      strategyRunId = null;
      storedEnsemble = null;
      storedOutcomes = [];
      response = { world: activeWorld, revision: worldRevision, epoch: worldEpoch };
    } else if (method === 'GET' && url.pathname === '/api/account') {
      response = { account: {
        id: ACCOUNT_ID,
        name: 'Desk practice account',
        cashCents: bookDocuments.summary.cashCents,
        reservedCents: bookDocuments.summary.reservedCents,
        buyingPowerCents: bookDocuments.summary.buyingPowerCents
      } };
    } else if (method === 'GET' && url.pathname === '/api/trades') {
      response = {
        trades: bookDocuments.activeTrades,
        total: bookDocuments.activeTrades.length,
        page: 0,
        size: 100
      };
    } else if (method === 'GET' && url.pathname === '/api/positions') {
      response = {
        positions: bookDocuments.sharePositions,
        note: bookDocuments.sharePositions.length
          ? 'Authoritative Practice share positions.' : 'No Practice share positions.'
      };
    } else if (method === 'GET' && url.pathname === '/api/portfolio/summary') {
      if (options.bookCoreDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.bookCoreDelayMs));
      }
      response = bookDocuments.summary;
    } else if (method === 'GET' && url.pathname === '/api/portfolio/heat') {
      response = bookDocuments.heat;
    } else if (method === 'GET' && url.pathname === '/api/portfolio/greeks') {
      response = bookDocuments.greeks;
    } else if (method === 'GET' && url.pathname === '/api/portfolio/book-risk') {
      response = bookDocuments.bookRisk;
    } else if (method === 'GET' && url.pathname === '/api/plans/portfolio') {
      response = { plans: bookDocuments.planPortfolio, market: activeMarketLane };
    } else if (method === 'GET' && url.pathname === '/api/universe') {
      response = {
        active: {
          source: 'test', sectorKey: 'CORE', label: 'Deterministic test universe',
          symbols: options.universeSymbols || ['AAPL']
        },
        sectors: options.universeSectors || [], world: activeWorld, lane: activeMarketLane
      };
    } else if (method === 'GET' && tradeDetailId && tradeDetails[tradeDetailId]) {
      response = tradeDetails[tradeDetailId];
    } else if (method === 'GET' && homeResearchSymbol
        && Object.prototype.hasOwnProperty.call(homeContextBySymbol, homeResearchSymbol)
        && homeContextBySymbol[homeResearchSymbol][homeResearchPart]) {
      const delay = Number(homeContextDelayBySymbol[homeResearchSymbol] || 0);
      if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay));
      response = JSON.parse(JSON.stringify(
        homeContextBySymbol[homeResearchSymbol][homeResearchPart]));
      if (homeResearchPart === 'chain') {
        currentExpiration = url.searchParams.get('expiration') || response.expiration;
        response.expiration = currentExpiration;
      }
    } else if (method === 'GET' && url.pathname === '/api/research/AAPL'
        && bookDocuments.research) {
      if (options.homeContextDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.homeContextDelayMs));
      }
      response = bookDocuments.research;
    } else if (method === 'GET' && url.pathname === '/api/research/AAPL/history'
        && bookDocuments.history) {
      response = bookDocuments.history;
    } else if (method === 'GET' && url.pathname === '/api/research/AAPL/expirations'
        && bookDocuments.expirations) {
      response = bookDocuments.expirations;
    } else if (method === 'GET' && url.pathname === '/api/research/AAPL/chain'
        && bookDocuments.chain) {
      currentExpiration = url.searchParams.get('expiration') || bookDocuments.chain.expiration;
      response = Object.assign({}, bookDocuments.chain, {
        expiration: currentExpiration
      });
    } else if (method === 'GET' && url.pathname === '/api/research/AAPL/news'
        && bookDocuments.news) {
      if (options.homeContextDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.homeContextDelayMs));
      }
      response = bookDocuments.news;
    } else if (method === 'GET' && managePlanId && managementByPlan[managePlanId]) {
      response = managementByPlan[managePlanId];
    } else if (method === 'GET' && positionEnsemblePlanId
        && positionEnsembleByPlan[positionEnsemblePlanId]) {
      response = JSON.parse(JSON.stringify(positionEnsembleByPlan[positionEnsemblePlanId]));
    } else if (method === 'POST'
        && url.pathname === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`
        && bookDocuments.positionEnsemble) {
      if (options.positionScenarioDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.positionScenarioDelayMs));
      }
      response = positionScenarioResponse(body, options);
    }
    else if (method === 'POST' && url.pathname === '/api/research/scout') {
      response = options.scoutResponse || {
        picks: [],
        searched: Array.isArray(body && body.universe) ? body.universe.length : 0
      };
    }
    else if (method === 'GET' && url.pathname === '/api/quotes') {
      const requested = String(url.searchParams.get('symbols') || quote.symbol || 'AMD')
        .split(',').map(symbol => symbol.trim().toUpperCase()).filter(Boolean);
      response = {
        marketLane: activeMarketLane,
        world: activeWorld,
        quotes: requested.map(symbol => {
          const researchQuote = bookDocuments.research
            && String(bookDocuments.research.symbol || '').toUpperCase() === symbol
            ? bookDocuments.research.quote : null;
          return Object.assign({}, researchQuote || quote, { symbol, refreshing: false });
        })
      };
    } else if (method === 'GET' && url.pathname === '/api/research/AMD') {
      if (researchFailuresRemaining > 0) {
        researchFailuresRemaining -= 1;
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'market_unavailable', detail: 'The observed AMD research receipt is temporarily unavailable.' })
        });
        return;
      }
      response = {
        symbol: 'AMD',
        quote: Object.assign({}, quote, { asOfEpochMs: quote.asOf }),
        displayPrice: Object.prototype.hasOwnProperty.call(options, 'researchDisplayPrice')
          ? options.researchDisplayPrice : (quote.bid + quote.ask) / 2,
        priceIsPreviousClose: options.researchPriceIsPreviousClose === true,
        markBasis: options.researchMarkBasis || null,
        marketLane: activeMarketLane,
        freshness: quote.freshness,
        evidence: {
          summary: quote.evidence,
          inputs: { quote: quote.evidence }
        },
        expirations: ['2026-08-21'],
        planEligible: true,
        planEligibility: 'Ready for the active observed market.'
      };
    } else if (method === 'GET' && url.pathname === '/api/research/AMD/expirations') {
      response = { asOfDate: '2026-07-20', expirations: ['2026-08-21'] };
    } else if (method === 'GET' && url.pathname === '/api/research/AMD/chain') {
      currentExpiration = url.searchParams.get('expiration') || '2026-08-21';
      response = {
        underlying: 'AMD', expiration: currentExpiration,
        source: activeMarketLane === 'OBSERVED' ? 'BACKEND_TEST_RECEIPT' : 'SIMULATED_DESK_TEST',
        freshness: activeMarketLane === 'OBSERVED' ? marketFreshness : 'FRESH',
        asOfEpochMs: activeMarketLane === 'OBSERVED' ? chainAsOf : chainAsOf + 60000,
        evidence: {
          source: activeMarketLane === 'OBSERVED' ? 'BACKEND_TEST_RECEIPT' : 'SIMULATED_DESK_TEST',
          lane: activeMarketLane,
          provenance: currentProvenance()
        },
        calls: [
          { strike: 95, bid: 8.8, ask: 9.2, iv: chainIv },
          { strike: 100, bid: 5.8, ask: 6.2, iv: chainIv },
          { strike: 105, bid: 2.9, ask: 3.1, iv: chainIv },
          { strike: 110, bid: 1.8, ask: 2.2, iv: chainIv }
        ],
        puts: [
          { strike: 95, bid: 1.4, ask: 1.8, iv: chainIv },
          { strike: 100, bid: 5.4, ask: 5.8, iv: chainIv },
          { strike: 105, bid: 8.5, ask: 8.9, iv: chainIv }
        ]
      };
    } else if (method === 'GET' && url.pathname === '/api/plans') {
      response = {
        world: activeWorld,
        market: activeMarketLane,
        plans: Array.isArray(options.listedPlans)
          ? options.listedPlans
          : (activeWorld === 'observed' || simulatedPlanCreated ? [currentPlan()] : [])
      };
    } else if (method === 'GET' && Array.isArray(options.listedPlans)
        && options.listedPlans.some(row => String(row.id) === decodeURIComponent(url.pathname.slice('/api/plans/'.length)))
        && url.pathname.startsWith('/api/plans/')) {
      response = options.listedPlans.find(row => String(row.id)
        === decodeURIComponent(url.pathname.slice('/api/plans/'.length)));
    } else if (method === 'GET' && url.pathname === activePlanPath) {
      response = currentPlan();
    } else if (method === 'POST' && url.pathname === '/api/plans') {
      if (staleCreateResponsesRemaining > 0) {
        staleCreateResponsesRemaining -= 1;
        response = options.staleCreatePlan;
      } else {
      // Production create is idempotent by canonical active-Plan identity. In particular, a
      // different risk default resumes the existing Plan and preserves its persisted posture.
      if (activeWorld !== 'observed') simulatedPlanCreated = true;
      if (body && body.symbol) {
        currentPlanSymbol = String(body.symbol).toUpperCase();
        if (bookDocuments.research
            && String(bookDocuments.research.symbol || '').toUpperCase() === currentPlanSymbol) {
          quote = Object.assign({}, bookDocuments.research.quote, {
            asOf: bookDocuments.research.quote.asOf
              || bookDocuments.research.quote.asOfEpochMs,
            evidence: bookDocuments.research.quote.evidence
              || { source: bookDocuments.research.quote.source,
                lane: activeMarketLane, provenance: currentProvenance() }
          });
        }
      }
      if (body && Object.prototype.hasOwnProperty.call(body, 'horizonDays')) {
        planOverrides.horizonDays = body.horizonDays;
      }
      response = currentPlan();
      }
    } else if (method === 'PUT' && url.pathname === `${activePlanPath}/intent`) {
      if (declarationFailuresRemaining > 0) {
        await rejectDeclarationWithConflict(route);
        return;
      }
      if (!body || Number(body.expectedVersion) !== Number(planVersion)) {
        await route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: 'Plan version conflict.' }) });
        return;
      }
      planOverrides.intent = body.intent == null ? null : String(body.intent).toUpperCase();
      planVersion += 1;
      selectedCandidate = null;
      storedEnsemble = null;
      storedOutcomes = [];
      hasCompetition = false;
      response = currentPlan();
    } else if (method === 'PUT' && url.pathname === `${activePlanPath}/context`) {
      if (declarationFailuresRemaining > 0) {
        await rejectDeclarationWithConflict(route);
        return;
      }
      if (!body || Number(body.expectedVersion) !== Number(planVersion)) {
        await route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: 'Plan version conflict.' }) });
        return;
      }
      for (const key of ['thesis', 'horizonDays', 'riskMode']) {
        if (Object.prototype.hasOwnProperty.call(body, key)) planOverrides[key] = body[key];
      }
      for (const key of body.clear || []) {
        if (['thesis', 'horizonDays', 'riskMode'].includes(key)) planOverrides[key] = null;
      }
      planOverrides.contextRev = Number(planOverrides.contextRev || 0) + 1;
      planVersion += 1;
      selectedCandidate = null;
      storedEnsemble = null;
      storedOutcomes = [];
      hasCompetition = false;
      response = currentPlan();
    } else if (method === 'GET' && url.pathname === `${activePlanPath}/strategy/latest`) {
      response = hasCompetition ? {
        strategy: strategyState(),
        ...(selectedCandidate ? { selected: selectedCandidate } : {})
      } : {};
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/strategy/run`) {
      if (options.strategyRunDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.strategyRunDelayMs));
      }
      if (selectedCandidate && strategyCandidates.some(row => row.id === selectedCandidate.id)) {
        selectedCandidate = null;
      }
      strategyRunNumber += 1;
      strategyRunId = `strategy_run_${strategyRunNumber}`;
      hasCompetition = true;
      response = { plan: currentPlan(), strategy: strategyState() };
    } else if (method === 'PUT' && url.pathname === `${activePlanPath}/strategy/select`) {
      if (options.selectDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.selectDelayMs));
      }
      if (selectFailuresRemaining > 0) {
        selectFailuresRemaining -= 1;
        await route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: 'selection_unavailable',
            detail: 'The requested comparison could not be selected.' }) });
        return;
      }
      const requested = strategyCandidates.find(row => row.id === body.candidateId);
      if (!requested) {
        await route.fulfill({ status: 404, contentType: 'application/json',
          body: JSON.stringify({ error: `No current candidate ${body.candidateId}.` }) });
        return;
      }
      if (!selectedCandidate || selectedCandidate.id !== requested.id) {
        planVersion += 1;
        selectedCandidate = Object.assign({}, requested, { selected: true });
        storedOutcomes = [];
      }
      response = {
        plan: currentPlan(),
        selection: { candidateId: selectedCandidate.id, planVersion }
      };
    } else if (method === 'POST' && url.pathname === '/api/trades/preview') {
      if (options.draftPreviewDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.draftPreviewDelayMs));
      }
      response = customTradePreview(body);
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/strategy/custom`) {
      if (options.customDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.customDelayMs));
      }
      planVersion += 1;
      selectedCandidate = customCandidate(body.position);
      storedOutcomes = [];
      response = {
        plan: currentPlan(),
        strategy: {
          state: 'CURRENT',
          result: {
            candidate: selectedCandidate,
            candidates: [candidate(), selectedCandidate]
          }
        },
        preview: customTradePreview(body.position).preview,
        identity: positionIdentity()
      };
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/outcomes/ensemble/paths`) {
      scenarioCalls += 1;
      if (scenarioFailuresRemaining > 0) {
        scenarioFailuresRemaining -= 1;
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            error: 'scenario_conditioning_unavailable',
            detail: 'The stored ensemble could not be conditioned for this selected scenario.'
          })
        });
        return;
      }
      const requestedPins = Array.isArray(body.pathWaypoints) && body.pathWaypoints.length
        ? body.pathWaypoints : body.waypoints;
      const terminal = requestedPins[requestedPins.length - 1].priceRatio;
      const scenarioCandidateId = selectedCandidate?.id || CANDIDATE_ID;
      if (terminal < 1) {
        await new Promise(resolve => setTimeout(resolve, 180));
        response = scenarioResponse('first', {
          requestBody: body,
          receipt: { selectedCandidateId: scenarioCandidateId }
        });
      } else if (terminal > 1.08) {
        response = scenarioResponse('invalid', {
          requestBody: body,
          receipt: {
            selectedCandidateId: scenarioCandidateId,
            ensembleFingerprint: 'wrong-ensemble-fingerprint'
          }
        });
      } else {
        response = scenarioResponse('second', {
          requestBody: body,
          receipt: { selectedCandidateId: scenarioCandidateId }
        });
      }
    } else if (method === 'GET' && url.pathname === `${activePlanPath}/outcomes/ensemble/latest`) {
      if (!options.latestEnsembleEnabled || !storedEnsemble) {
        await route.fulfill({ status: 404, contentType: 'application/json',
          body: JSON.stringify({ error: 'No current stored ensemble.' }) });
        return;
      }
      response = JSON.parse(JSON.stringify(storedEnsemble));
      response.preview.marketImplied.atmIv = chainIv;
      response.preview.marketImplied.expiration = '2026-08-21';
      response.plan = currentPlan();
    } else if (method === 'GET' && url.pathname === `${activePlanPath}/outcomes/latest`) {
      response = { plan: currentPlan(), outcomes: storedOutcomes };
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/outcomes/ensemble`) {
      if (options.ensembleDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.ensembleDelayMs));
      }
      if (options.rollQuoteOnFirstEnsemble && ensembleGeneration === 0) {
        quote = Object.assign({}, quote, {
          bid: quote.bid + 2,
          ask: quote.ask + 2,
          last: quote.last + 2,
          asOf: quote.asOf + 60000
        });
      }
      if (options.rollChainOnFirstEnsemble && ensembleGeneration === 0) {
        chainIv += 0.07;
      }
      ensembleGeneration += 1;
      const mark = (quote.bid + quote.ask) / 2;
      const id = ensembleGeneration === 1 ? ENSEMBLE_ID : `${ENSEMBLE_ID}_${ensembleGeneration}`;
      const fingerprint = ensembleGeneration === 1
        ? ENSEMBLE_FINGERPRINT : `${ENSEMBLE_FINGERPRINT}-${ensembleGeneration}`;
      storedEnsemble = ensemble(planVersion, {
        id, fingerprint, symbol: currentPlanSymbol, spot: mark, asOf: quote.asOf,
        atmIv: chainIv, expiration: currentExpiration
      });
      storedEnsemble.plan = currentPlan(planVersion);
      storedEnsemble.preview.receipt.worldId = activeWorld;
      storedEnsemble.preview.receipt.datasetId = activeDataset;
      storedEnsemble.preview.receipt.anchorSource = quote.source;
      storedEnsemble.preview.receipt.anchorFreshness = quote.freshness;
      if (Array.isArray(options.canvasComparison)) {
        storedEnsemble.preview.canvas.comparison = JSON.parse(JSON.stringify(options.canvasComparison));
      }
      if (Array.isArray(options.canvasPositions)) {
        storedEnsemble.preview.canvas.positions = JSON.parse(JSON.stringify(options.canvasPositions));
      }
      response = storedEnsemble;
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/outcomes/run`) {
      const ensembleRef = storedEnsemble && storedEnsemble.ensemble;
      const fingerprint = ensembleRef && ensembleRef.id === body.ensembleId
        ? ensembleRef.fingerprint : ENSEMBLE_FINGERPRINT;
      const savedOutcome = {
        candidateId: selectedCandidate.id,
        ensembleId: body.ensembleId,
        ensembleFingerprint: fingerprint,
        basis: 'PARAMETRIC',
        result: {
          paths: 500, horizonDays: 21, winRatePct: 63,
          p50Cents: 1450, p5Cents: -12345, bands: [{ p10Cents: -8000 }]
        }
      };
      storedOutcomes.push(Object.assign({}, savedOutcome, savedOutcome.result));
      delete storedOutcomes[storedOutcomes.length - 1].result;
      response = {
        plan: currentPlan(),
        ensemble: {
          id: body.ensembleId,
          fingerprint,
          basis: 'PARAMETRIC'
        },
        outcome: savedOutcome
      };
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/decision/preview`) {
      if (options.decisionPreviewDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.decisionPreviewDelayMs));
      }
      response = decisionPreview(body, selectedCandidate, planVersion);
      response.plan = currentPlan(planVersion);
      response.preview.freshness = quote.freshness;
      response.preview.evidence = { source: quote.source, lane: activeMarketLane };
      if (options.unavailableDecisionPreview) {
        const reason = 'Cannot execute AMD from a stale observed option book.';
        response.preview = Object.assign({}, response.preview, {
          ok: false,
          entryNetPremiumCents: 0,
          maxLossCents: 0,
          maxProfitCents: 0,
          reserveCents: 0,
          blockReasons: [reason],
          legs: [],
          payoff: []
        });
        response.order = Object.assign({}, response.order, {
          proposedNetCents: 0,
          executability: 'UNAVAILABLE',
          presentlyExecutable: false,
          executableNetCents: null,
          valuedNetCents: null,
          valuationBasis: 'UNAVAILABLE'
        });
        response.guardrails = { level: 'WARN', blockReasons: [], warnings: [reason] };
      }
      if (options.blockDecisionPreview) {
        response.preview.ok = false;
        response.preview.blockReasons = ['The exact package exceeds the backend loss limit.'];
        response.guardrails = {
          level: 'BLOCK',
          blockReasons: ['The exact package exceeds the backend loss limit.'],
          warnings: []
        };
        response.accountFit = { overRiskCapital: true };
      }
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/decision/trade`) {
      if (options.commitDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.commitDelayMs));
      }
      planVersion += 1;
      if (options.commitAddsTrade) {
        const committedTrade = Object.assign({}, populatedBookDocuments().activeTrades[0], {
          id: 'trade-desk-test', symbol: 'AMD', strategy: selectedCandidate?.strategy || 'DEBIT_CALL_SPREAD',
          qty: body.qty || 1, entryUnderlyingCents: 10000, status: 'ACTIVE'
        });
        bookDocuments.activeTrades = [committedTrade].concat(
          bookDocuments.activeTrades.filter(trade => trade.id !== committedTrade.id));
        bookDocuments.summary.openTradesCount = bookDocuments.activeTrades.length;
        tradeDetails[committedTrade.id] = Object.assign({}, populatedBookDocuments().tradeDetail, {
          trade: committedTrade
        });
      }
      response = { plan: currentPlan(), decision: { state: 'COMMITTED' }, trade: { id: 'trade-desk-test' } };
    } else {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: `Unhandled deterministic Desk route: ${method} ${url.pathname}` })
      });
      return;
    }

    if (response && response.plan && !bookPlanIds.has(response.plan.id)) {
      response.plan = currentPlan(response.plan.version);
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(response) });
  });

  return {
    requests,
    scenarioCalls: () => scenarioCalls,
    planVersion: () => planVersion,
    setQuote(next) {
      quote = Object.assign({}, quote, next);
    },
    setChainIv(next, asOf = chainAsOf + 60000) {
      chainIv = next;
      chainAsOf = asOf;
    },
    count(method, pathname) {
      return requests.filter(row => row.method === method && row.path === pathname).length;
    }
  };
}

async function openAuthoritativeDesk(options = {}) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, options);
  await page.goto(deskUrl);
  await waitForDeskBoot(page);
  await page.locator('#threadNewIdea').click();
  try {
    await page.waitForFunction(id => window.decide
      && window.decide.backendPhase === 'ready'
      && window.decide.candId === id
      && window.decide.orderPreview, options.expectedCandidateId || CANDIDATE_ID, { timeout: 10000 });
  } catch (error) {
    const diagnosis = await page.evaluate(() => {
      const state = window.DeskBackend && window.DeskBackend.state();
      return {
        phase: window.decide && window.decide.backendPhase,
        candidateId: window.decide && window.decide.candId,
        backendError: window.decide && window.decide.backendError,
        bridgeError: state && state.error && (state.error.stack || state.error.message),
        plan: state && state.plan,
        marketIdentity: state && state.market && state.market.identity
      };
    });
    throw new Error(`${error.message}\nDesk diagnosis: ${JSON.stringify(diagnosis)}`
      + `\nPage errors: ${JSON.stringify(pageErrors)}\nRequests: ${JSON.stringify(backend.requests)}`);
  }
  return { context, page, pageErrors, backend };
}

async function waitForDeskBoot(page) {
  try {
    await page.waitForFunction(() => window.DeskBackend
      && window.StrikeBenchDesk
      && typeof document.querySelector('#threadNewIdea')?.onclick === 'function',
    null, { timeout: 10000 });
  } catch (error) {
    const diagnosis = await page.evaluate(() => ({
      readyState: document.readyState,
      bridge: !!window.DeskBackend,
      consumer: !!window.StrikeBenchDesk,
      newIdeaWired: typeof document.querySelector('#threadNewIdea')?.onclick,
      book: window.DeskBackend && window.DeskBackend.state().book,
      stageAuthority: document.querySelector('#stage')?.getAttribute('data-book-authority')
    }));
    throw new Error(`${error.message}\nDesk boot diagnosis: ${JSON.stringify(diagnosis)}`);
  }
}

test('HTTP Home hydrates ambient universe quotes without Plans, trades, or shares', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = emptyBookDocuments();
  const marketDocuments = populatedBookDocuments();
  bookDocuments.research = marketDocuments.research;
  bookDocuments.history = marketDocuments.history;
  bookDocuments.expirations = marketDocuments.expirations;
  bookDocuments.chain = marketDocuments.chain;
  bookDocuments.news = marketDocuments.news;
  const backend = await installBackend(page, {
    bookDocuments,
    universeSymbols: ['AAPL', 'MSFT']
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => {
      const book = window.DeskBackend.state().book;
      return book?.data?.homeContext?.phase === 'ready'
        && book.data.homeContext.rows.length === 2;
    }, null, { timeout: 10000 });

    const rendered = await page.evaluate(() => {
      const data = window.DeskBackend.state().book.data;
      return {
        plans: data.accountPlans,
        tradeCount: data.activeTrades.length,
        shareCount: data.sharePositions.length,
        symbols: data.homeContext.symbols,
        rows: data.homeContext.rows.map(row => ({
          symbol: row.symbol,
          quoteSymbol: row.research?.quote?.symbol,
          source: row.research?.quote?.source,
          displayPrice: row.research?.displayPrice
        })),
        watchPrices: Array.from(document.querySelectorAll('[data-auth-market-symbol] em'))
          .map(node => node.textContent.trim()),
        visibleSymbols: Array.from(document.querySelectorAll('[data-auth-market-symbol]'))
          .map(node => node.getAttribute('data-auth-market-symbol')),
        text: document.querySelector('#stage').textContent.replace(/\s+/g, ' ').trim()
      };
    });
    assert.deepEqual(rendered.plans, [], 'no saved Plan is required to establish ambient market context');
    assert.equal(rendered.tradeCount, 0);
    assert.equal(rendered.shareCount, 0);
    assert.deepEqual(rendered.symbols, ['AAPL', 'MSFT']);
    assert.deepEqual(rendered.rows, [
      { symbol: 'AAPL', quoteSymbol: 'AAPL', source: 'BOOK_TEST_RESEARCH_RECEIPT', displayPrice: 222.22 },
      { symbol: 'MSFT', quoteSymbol: 'MSFT', source: 'BACKEND_TEST_RECEIPT', displayPrice: 100 }
    ]);
    assert.deepEqual(rendered.watchPrices, ['$222.22', '$100.00'],
      'bounded Quote receipts keep every Home watch row visibly priced');
    assert.deepEqual(rendered.visibleSymbols, ['AAPL', 'MSFT'],
      'the empty Book still renders its active-universe market watch');
    assert.match(rendered.text, /Backend research sentinel headline/i,
      'the focused ambient symbol still hydrates source-backed research');
    assert.equal(backend.count('GET', '/api/universe'), 1);
    assert.equal(backend.count('GET', '/api/quotes'), 1,
      'ambient symbols use the bounded quote batch independently of Plan ownership');
    assert.equal(backend.count('GET', '/api/research/AAPL'), 1);
    assert.equal(backend.count('GET', '/api/research/AAPL/history'), 1);
    assert.ok(backend.count('GET', '/api/research/AAPL/expirations') <= 2,
      'the focused expiration read stays bounded even when the entry prefetch races Home');
    assert.equal(backend.count('GET', '/api/research/AAPL/chain'), 1,
      'Home loads one bounded focused chain rather than warming the universe');
    assert.deepEqual(pageErrors, [], `ambient Home emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('served Home command search retargets symbols and sectors through the backend universe', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = emptyBookDocuments();
  const marketDocuments = populatedBookDocuments();
  Object.assign(bookDocuments, {
    research: marketDocuments.research,
    history: marketDocuments.history,
    expirations: marketDocuments.expirations,
    chain: marketDocuments.chain,
    news: marketDocuments.news
  });
  const backend = await installBackend(page, {
    bookDocuments,
    universeSymbols: ['AAPL'],
    universeSectors: [{
      key: 'SEMICONDUCTORS', label: 'Semiconductors', symbols: ['AMD', 'NVDA']
    }]
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.data?.homeContext?.phase === 'ready',
      null, { timeout: 10000 });
    await page.evaluate(() => {
      window.__fixtureContextCalls = 0;
      const chain = window.renderChainBand;
      const sector = window.renderSectorBand;
      window.renderChainBand = function(...args) {
        window.__fixtureContextCalls += 1;
        return chain.apply(this, args);
      };
      window.renderSectorBand = function(...args) {
        window.__fixtureContextCalls += 1;
        return sector.apply(this, args);
      };
    });

    await page.locator('#cmd').fill('AMD');
    await page.locator('#cmd').press('Enter');
    await page.waitForFunction(() => {
      const contextState = window.DeskBackend.state().book?.data?.homeContext;
      return contextState?.phase === 'ready' && contextState.detailSymbol === 'AMD'
        && window.HOME_AUTH_SYMBOL === 'AMD';
    }, null, { timeout: 10000 });
    assert.equal(await page.evaluate(() => window.state.level), 'book',
      'an AMD command cannot be intercepted by a staged prototype position');

    await page.locator('#cmd').fill('semis');
    await page.locator('#cmd').press('Enter');
    await page.waitForFunction(() => {
      const lens = window.DeskBackend.state().book?.data?.homeContext?.sectorLens;
      return lens?.available === true && lens.key === 'SEMICONDUCTORS'
        && window.DeskBackend.state().book.data.homeContext.phase === 'ready';
    }, null, { timeout: 10000 });

    const result = await page.evaluate(() => {
      const home = window.DeskBackend.state().book.data.homeContext;
      return {
        fixtureCalls: window.__fixtureContextCalls,
        detailSymbol: home.detailSymbol,
        lens: home.sectorLens,
        visibleSymbols: Array.from(document.querySelectorAll('[data-auth-market-symbol]'))
          .map(node => node.getAttribute('data-auth-market-symbol')),
        text: document.querySelector('#stage').textContent.replace(/\s+/g, ' ').trim()
      };
    });
    assert.equal(result.fixtureCalls, 0,
      'served command routing does not invoke prototype market or sector renderers');
    assert.equal(result.detailSymbol, 'AMD');
    assert.deepEqual(result.lens.symbols, ['AMD', 'NVDA']);
    assert.deepEqual(result.visibleSymbols, ['AMD', 'NVDA']);
    assert.match(result.text, /Semiconductors · backend universe/i);
    assert.doesNotMatch(result.text, /VISUAL FIXTURE/i);
    assert.ok(backend.count('GET', '/api/research/AMD') >= 1);
    assert.ok(backend.count('GET', '/api/quotes') >= 2,
      'sector retargeting hydrates its bounded backend symbol set through the quote batch');
    assert.deepEqual(pageErrors, [], `backend Home command routing emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a user focus supersedes slower initial Home market hydration', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = emptyBookDocuments();
  const homeContextBySymbol = {
    AAPL: focusedHomeDocuments('AAPL', 222.22),
    AMD: focusedHomeDocuments('AMD', 168.45)
  };
  const backend = await installBackend(page, {
    bookDocuments,
    universeSymbols: ['AAPL', 'AMD'],
    homeContextBySymbol,
    homeContextDelayBySymbol: { AAPL: 300, AMD: 5 }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => {
      const home = window.DeskBackend.state().book?.data?.homeContext;
      return home?.phase === 'loading' && home.detailSymbol === 'AAPL';
    }, null, { timeout: 10000 });

    await page.evaluate(() => window.focusSymbol('AMD'));
    await page.waitForFunction(() => {
      const home = window.DeskBackend.state().book?.data?.homeContext;
      return home?.phase === 'ready' && home.detailSymbol === 'AMD'
        && window.HOME_AUTH_SYMBOL === 'AMD';
    }, null, { timeout: 10000 });
    const acceptedGeneration = await page.evaluate(() =>
      window.DeskBackend.state().book.data.homeContext.requestId);

    // AAPL waits twice (parallel base reads, then its chain), so give the superseded request enough
    // time to complete and prove that neither its bridge publish nor its UI callback can win late.
    await page.waitForTimeout(750);
    const settled = await page.evaluate(() => {
      const home = window.DeskBackend.state().book.data.homeContext;
      return {
        requestId: home.requestId,
        detailSymbol: home.detailSymbol,
        visibleSymbol: window.HOME_AUTH_SYMBOL,
        heading: document.querySelector('#chainBand')?.textContent.replace(/\s+/g, ' ').trim(),
        news: document.querySelector('#newsBand')?.textContent.replace(/\s+/g, ' ').trim()
      };
    });
    assert.equal(settled.requestId, acceptedGeneration);
    assert.equal(settled.detailSymbol, 'AMD');
    assert.equal(settled.visibleSymbol, 'AMD');
    assert.match(settled.heading, /AMD · OBSERVED/i);
    assert.match(settled.news, /AMD focused research receipt/i);
    assert.ok(backend.count('GET', '/api/research/AAPL') >= 1,
      'the deliberately stale initial request really completed behind the user focus');
    assert.deepEqual(pageErrors, [], `initial Home focus race emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('rapid Home focus changes retain the newest symbol when responses resolve out of order', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = emptyBookDocuments();
  const homeContextBySymbol = {
    AAPL: focusedHomeDocuments('AAPL', 222.22),
    AMD: focusedHomeDocuments('AMD', 168.45),
    NVDA: focusedHomeDocuments('NVDA', 191.15)
  };
  const backend = await installBackend(page, {
    bookDocuments,
    universeSymbols: ['AAPL', 'AMD', 'NVDA'],
    homeContextBySymbol,
    homeContextDelayBySymbol: { AMD: 300, NVDA: 5 }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() =>
      window.DeskBackend.state().book?.data?.homeContext?.phase === 'ready',
    null, { timeout: 10000 });

    await page.evaluate(() => window.focusSymbol('AMD'));
    await page.waitForFunction(() =>
      window.DeskBackend.state().book?.data?.homeContext?.detailLoading === 'AMD');
    await page.evaluate(() => window.focusSymbol('NVDA'));
    await page.waitForFunction(() => {
      const home = window.DeskBackend.state().book?.data?.homeContext;
      return home?.phase === 'ready' && home.detailSymbol === 'NVDA'
        && window.HOME_AUTH_SYMBOL === 'NVDA';
    }, null, { timeout: 10000 });
    const acceptedGeneration = await page.evaluate(() =>
      window.DeskBackend.state().book.data.homeContext.requestId);

    await page.waitForTimeout(750);
    const settled = await page.evaluate(() => {
      const home = window.DeskBackend.state().book.data.homeContext;
      return {
        requestId: home.requestId,
        detailSymbol: home.detailSymbol,
        visibleSymbol: window.HOME_AUTH_SYMBOL,
        selectedRows: Array.from(document.querySelectorAll('[data-auth-market-symbol].on'))
          .map(node => node.getAttribute('data-auth-market-symbol')),
        heading: document.querySelector('#chainBand')?.textContent.replace(/\s+/g, ' ').trim(),
        news: document.querySelector('#newsBand')?.textContent.replace(/\s+/g, ' ').trim()
      };
    });
    assert.equal(settled.requestId, acceptedGeneration);
    assert.equal(settled.detailSymbol, 'NVDA');
    assert.equal(settled.visibleSymbol, 'NVDA');
    assert.deepEqual(settled.selectedRows, ['NVDA']);
    assert.match(settled.heading, /NVDA · OBSERVED/i);
    assert.match(settled.news, /NVDA focused research receipt/i);
    assert.ok(backend.count('GET', '/api/research/AMD') >= 1,
      'the older AMD request was in flight when NVDA became the focus owner');
    assert.deepEqual(pageErrors, [], `out-of-order Home focus emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Home renders an authoritative empty Practice book without staged holdings', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const marketDocuments = populatedBookDocuments();
  const bookDocuments = emptyBookDocuments();
  bookDocuments.planPortfolio = marketDocuments.planPortfolio;
  bookDocuments.planPortfolio[0].plan.originPlanId = 'plan_home_origin';
  Object.assign(bookDocuments.planPortfolio[0].plan.context, {
    targetCents: 23000,
    holdingsShares: 100,
    costBasisCents: 20000,
    priceAssumptionCents: 22100,
    assignmentPreference: 'AVOID'
  });
  bookDocuments.research = marketDocuments.research;
  bookDocuments.history = marketDocuments.history;
  bookDocuments.expirations = marketDocuments.expirations;
  bookDocuments.chain = marketDocuments.chain;
  bookDocuments.news = marketDocuments.news;
  const backend = await installBackend(page, {
    bookDocuments, bookCoreDelayMs: 150, homeContextDelayMs: 250
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('#stage[data-book-authority="loading"]');
    assert.deepEqual(await page.evaluate(() => [
      'book', 'riskMain', 'bookrisk', 'chainBand', 'univBand', 'sectorBand', 'newsBand'
    ].map(id => ({ id, rects: document.getElementById(id).getClientRects().length }))), [
      { id: 'book', rects: 0 }, { id: 'riskMain', rects: 0 }, { id: 'bookrisk', rects: 0 },
      { id: 'chainBand', rects: 0 }, { id: 'univBand', rects: 0 },
      { id: 'sectorBand', rects: 0 }, { id: 'newsBand', rects: 0 }
    ], 'the loading authority layer cannot leave hidden cockpit panels in layout');
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });
    await page.waitForSelector('#stage[data-book-authority="empty"]', { timeout: 10000 });
    assert.equal(await page.evaluate(() => window.DeskBackend.state().book.data.homeContext.phase),
      'loading', 'the account and Plan actions render before a cold Research source finishes');
    await page.waitForFunction(() => window.DeskBackend.state().book?.data?.homeContext?.phase === 'ready',
      null, { timeout: 10000 });

    for (const viewport of [
      { width: 1280, height: 800 },
      { width: 390, height: 844 }
    ]) {
      await page.setViewportSize(viewport);
      const rendered = await page.evaluate(() => {
        const bridge = window.DeskBackend.state();
        const stage = document.querySelector('#stage');
        const fixtureFlag = document.querySelector('#fixtureFlag');
        return {
          authority: stage && stage.getAttribute('data-book-authority'),
          phase: bridge.book && bridge.book.phase,
          activeTrades: bridge.book && bridge.book.data
            && bridge.book.data.activeTrades.length,
          openTradesCount: bridge.book && bridge.book.data
            && bridge.book.data.portfolio.summary.openTradesCount,
          text: stage && stage.textContent.replace(/\s+/g, ' ').trim(),
          visibleCards: Array.from(document.querySelectorAll('#book .card[data-id]'))
            .filter(card => card.getClientRects().length).map(card => card.dataset.id),
          cockpitPanels: ['book', 'riskMain', 'bookrisk', 'chainBand', 'univBand', 'sectorBand', 'newsBand']
            .map(id => {
              const node = document.getElementById(id);
              const rect = node.getBoundingClientRect();
              return { id, visible: node.getClientRects().length > 0, width: rect.width, height: rect.height };
            }),
          cockpitGeometry: Object.fromEntries(
            ['book', 'chainBand', 'univBand', 'sectorBand', 'newsBand'].map(id => {
              const rect = document.getElementById(id).getBoundingClientRect();
              return [id, {
                left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
                width: rect.width, height: rect.height
              }];
            })
          ),
          planActions: document.querySelectorAll('[data-auth-plan-id]').length,
          planActionTags: Array.from(document.querySelectorAll('[data-auth-plan-id]'))
            .map(node => node.tagName),
          marketRows: document.querySelectorAll('[data-auth-market-symbol]').length,
          newsLinks: document.querySelectorAll('#newsBand .authhomenews a').length,
          candleBodies: document.querySelectorAll('#authHomeHistory rect').length,
          chainRows: document.querySelectorAll('#chainBand .authchainrow').length,
          riskMapCount: document.querySelectorAll('#authBookRiskViz').length,
          boardTemplateAreas: getComputedStyle(document.querySelector('#stage .board'))
            .gridTemplateAreas,
          authorityOverlayVisible: document.getElementById('bookAuthState').getClientRects().length > 0,
          fullHeightEmptyOverlay: document.querySelectorAll('#bookAuthState .authempty').length,
          fixtureFlagVisible: !!fixtureFlag && fixtureFlag.getClientRects().length > 0,
          documentOverflow: document.documentElement.scrollWidth
            > document.documentElement.clientWidth
        };
      });
      assert.equal(rendered.authority, 'empty');
      assert.equal(rendered.phase, 'ready',
        'an empty typed roster is a successful backend receipt, not a transport failure');
      assert.equal(rendered.activeTrades, 0);
      assert.equal(rendered.openTradesCount, 0);
      assert.deepEqual(rendered.visibleCards, [],
        `${viewport.width}px HTTP Home does not render the six offline fixture positions`);
      assert.equal(rendered.fixtureFlagVisible, false,
        'served Home is not labeled as a fixture after its authoritative empty receipt arrives');
      assert.match(rendered.text, /No open Practice positions/i);
      assert.match(rendered.text, /Market pulse & chain[\s\S]*AAPL[\s\S]*Price history/i,
        'the empty account retains current market evidence without exposing ingestion receipts');
      assert.match(rendered.text, /Research & news[\s\S]*Backend research sentinel headline/i,
        'the empty account retains research headlines without repeating provenance in the heading');
      assert.doesNotMatch(rendered.text,
        /BOOK_TEST_RESEARCH_RECEIPT|source-owned|source-backed|stored coverage|trend threshold/i,
        'Home keeps ingestion and model diagnostics out of product copy');
      assert.ok(rendered.candleBodies >= 2,
        'Home renders backend-owned observed OHLC candles instead of an empty canvas');
      assert.ok(rendered.chainRows >= 3,
        'Home renders a bounded nearest-strike slice from the authoritative chain');
      assert.equal(rendered.planActions, 1, 'the owning Plan remains an explicit action');
      assert.deepEqual(rendered.planActionTags, ['BUTTON'],
        'Plans are controls, not inert receipt rows');
      assert.equal(rendered.marketRows, 1, 'the Plan-symbol market watch remains available');
      assert.equal(rendered.newsLinks, 1, 'the source-backed headline remains a real link');
      assert.equal(rendered.authorityOverlayVisible, false,
        'the empty Book does not leave a full-board authority overlay over Home');
      assert.equal(rendered.fullHeightEmptyOverlay, 0,
        'the screenshot-era three-column empty receipt canvas is absent');
      const emptyRisk = rendered.cockpitPanels.find(panel => panel.id === 'riskMain');
      assert.deepEqual(emptyRisk, { id: 'riskMain', visible: false, width: 0, height: 0 },
        `${viewport.width}px does not reserve a large chart tile for zero plottable positions`);
      const emptyBookRisk = rendered.cockpitPanels.find(panel => panel.id === 'bookrisk');
      assert.deepEqual(emptyBookRisk, { id: 'bookrisk', visible: false, width: 0, height: 0 },
        `${viewport.width}px does not reserve a second empty risk receipt tile`);
      assert.equal(rendered.riskMapCount, 0,
        'the empty Practice receipt never mounts a zero-valued risk-map SVG');
      assert.doesNotMatch(rendered.boardTemplateAreas, /\bmap\b/,
        `${viewport.width}px gives the empty Home grid to actionable account and evidence panels`);
      rendered.cockpitPanels.filter(panel => panel.id !== 'riskMain' && panel.id !== 'bookrisk').forEach(panel => {
        assert.equal(panel.visible, true, `${viewport.width}px ${panel.id} remains visible`);
        assert.ok(panel.width >= (viewport.width <= 500 ? 350 : 200),
          `${viewport.width}px ${panel.id} keeps useful width (${panel.width}px)`);
        assert.ok(panel.height >= 180,
          `${viewport.width}px ${panel.id} keeps useful height (${panel.height}px)`);
      });
      if (viewport.width === 1280) {
        const { book, chainBand, univBand, sectorBand, newsBand } = rendered.cockpitGeometry;
        assert.ok(Math.abs(chainBand.top - book.top) < 2 && Math.abs(book.top - sectorBand.top) < 2,
          'the empty desktop cockpit keeps chain, roster, and market watch in one first row');
        assert.ok(Math.abs(newsBand.top - univBand.top) < 2 && newsBand.top >= chainBand.bottom - 2,
          'research/news and working Plans share the second row without overlapping the market row');
        assert.ok(chainBand.width > book.width * 2.5,
          'the information-dense chain/candle panel receives three times the empty roster width');
        assert.ok(chainBand.width > sectorBand.width * 1.4,
          'the chain/candle panel remains wider than the bounded market watch');
        assert.ok(Math.abs(chainBand.width - newsBand.width) < 3
          && Math.abs(newsBand.width - univBand.width) < 3,
        'the two full-width evidence/Plan panels balance the second row');
      }
      assert.doesNotMatch(rendered.text, /Covered strangle|Short-put campaign|Past stop/i,
        'offline position stories cannot survive in the served empty book');
      assert.doesNotMatch(rendered.text, /six staged design positions|Optional gaps|Loaded 20/i,
        'implementation receipts do not replace the Home experience');
      assert.equal(rendered.documentOverflow, false,
        `${viewport.width}px authoritative empty Home remains horizontally contained`);
    }

    await page.evaluate(() => {
      window.__homeResumeContext = null;
      window.__homeOpenIdea = window.DeskBackend.openIdea;
      window.DeskBackend.openIdea = context => {
        window.__homeResumeContext = context;
        return Promise.resolve(null);
      };
    });
    await page.locator(`[data-auth-plan-id="${BOOK_PLAN_ID}"]`).click();
    await page.waitForFunction(() => window.__homeResumeContext != null);
    assert.deepEqual(await page.evaluate(() => ({
      planId: window.__homeResumeContext.planId,
      symbol: window.__homeResumeContext.symbol,
      goal: window.__homeResumeContext.goal,
      horizon: window.__homeResumeContext.horizon,
      originPlanId: window.__homeResumeContext.originPlanId,
      targetCents: window.__homeResumeContext.targetCents,
      holdingsShares: window.__homeResumeContext.holdingsShares,
      costBasisCents: window.__homeResumeContext.costBasisCents,
      priceAssumptionCents: window.__homeResumeContext.priceAssumptionCents,
      assignmentPreference: window.__homeResumeContext.assignmentPreference
    })), {
      planId: BOOK_PLAN_ID, symbol: 'AAPL', goal: 'INCOME', horizon: '45 days',
      originPlanId: 'plan_home_origin', targetCents: 23000, holdingsShares: 100,
      costBasisCents: 20000, priceAssumptionCents: 22100, assignmentPreference: 'AVOID'
    }, 'Home resumes the exact clicked Plan instead of minting or guessing another one');
    await page.evaluate(() => {
      window.DeskBackend.openIdea = window.__homeOpenIdea;
      if (window.decide) window.exitDecide();
    });

    assert.equal(backend.count('GET', '/api/trades'), 1);
    assert.equal(backend.count('GET', '/api/positions'), 1);
    assert.equal(backend.count('GET', '/api/portfolio/summary'), 1);
    assert.equal(backend.count('GET', '/api/research/AAPL'), 1);
    assert.equal(backend.count('GET', '/api/research/AAPL/news'), 1);
    assert.equal(backend.count('GET', '/api/trades/' + BOOK_TRADE_ID), 0,
      'an empty roster cannot trigger a fixture or speculative position-detail read');
    assert.deepEqual(pageErrors, [], `empty Practice book emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Home renders the synchronized Book total without summing independent position fans', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = populatedBookDocuments();
  bookDocuments.bookRisk.practice.measuredBook = measuredJointBookReceipt();
  const backend = await installBackend(page, { bookDocuments });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('#stage[data-book-authority="ready"] #authBookFan');
    await page.waitForFunction(() => document.querySelector('#authBookFan')?.textContent.includes('BOOK'));
    const rendered = await page.evaluate(() => ({
      hint: document.querySelector('#riskMain .lenshd .hint')?.textContent.trim(),
      bookLegend: document.querySelector('#authBookFanLegend .booktotal')?.textContent
        .replace(/\s+/g, ' ').trim(),
      readout: document.querySelector('#bookFanReadout')?.textContent.replace(/\s+/g, ' ').trim(),
      chartText: document.querySelector('#authBookFan')?.textContent.replace(/\s+/g, ' ').trim(),
      aggregatePaths: document.querySelectorAll('#authBookFan path[stroke="var(--text)"]').length,
      positionEntries: document.querySelector('#authBookFan')?._bfmap?.entries?.map(row => row.id) || [],
      measuredFingerprint: window.DeskBackend.state().book.data.portfolio.bookRisk
        .practice.measuredBook.scenario.jointFingerprint
    }));
    assert.match(rendered.hint, /book total = synchronized paths/i);
    assert.match(rendered.hint, /colors = independent positions/i);
    assert.match(rendered.bookLegend, /BOOKsynchronized total\+\$460 62% gain odds/i);
    assert.match(rendered.readout, /Book total · synchronized measured paths/i);
    assert.match(rendered.readout, /colored position projections are independent/i);
    assert.match(rendered.chartText, /BOOK \+\$460/i);
    assert.ok(rendered.aggregatePaths >= 2,
      'the one renderer paints synchronized Book paths and its median from the canonical receipt');
    assert.deepEqual(rendered.positionEntries, [BOOK_TRADE_ID],
      'hover/click identity remains the independent position, never a fabricated aggregate position');
    assert.equal(rendered.measuredFingerprint, 'joint-book-browser-receipt');
    assert.equal(backend.count('GET', '/api/portfolio/book-risk'), 1,
      'the aggregate reuses the existing Book-risk API rather than adding a fan endpoint');
    assert.deepEqual(pageErrors, [], `joint Book fan emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Home resumes the exact clicked Plan in the authoritative Desk', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = emptyBookDocuments();
  bookDocuments.planPortfolio = [{ plan: plan(10, { id: PLAN_ID, symbol: 'AMD' }) }];
  const backend = await installBackend(page, { bookDocuments });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector(`#stage[data-book-authority="empty"] [data-auth-plan-id="${PLAN_ID}"]`);
    await page.locator(`[data-auth-plan-id="${PLAN_ID}"]`).click();
    await page.waitForFunction(planId => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().plan?.id === planId, PLAN_ID, { timeout: 10000 });

    assert.equal(await page.locator('#decideStage.on').count(), 1,
      'the saved Plan opens in the approved Desk surface');
    assert.equal(await page.evaluate(() => window.decide.resumePlanId), PLAN_ID);
    assert.equal(backend.count('GET', `/api/plans/${PLAN_ID}`), 1,
      'the bridge loads the exact clicked Plan');
    assert.equal(backend.count('GET', '/api/plans'), 0,
      'exact resume does not guess among similar active Plans');
    assert.equal(backend.count('POST', '/api/plans'), 0,
      'exact resume cannot mint a replacement Plan');
    assert.deepEqual(pageErrors, [], `exact Home Plan resume emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Home preserves canonical ACQUIRE and EXIT declarations, including absent thesis and horizon', async () => {
  for (const intent of ['ACQUIRE', 'EXIT']) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    const exact = { id: PLAN_ID, symbol: 'AMD', intent, thesis: null, horizonDays: null };
    const bookDocuments = emptyBookDocuments();
    bookDocuments.planPortfolio = [{ plan: plan(10, exact) }];
    const backend = await installBackend(page, {
      bookDocuments,
      activePlanOverrides: exact
    });
    try {
      await page.goto(deskUrl);
      await waitForDeskBoot(page);
      await page.waitForSelector(`[data-auth-plan-id="${PLAN_ID}"]`);
      await page.locator(`[data-auth-plan-id="${PLAN_ID}"]`).click();
      await page.waitForFunction(() => window.decide?.backendPhase === 'declaration-required'
        || window.decide?.backendPhase === 'error', null, { timeout: 10000 });
      const diagnosis = await page.evaluate(() => ({
        phase: window.decide?.backendPhase,
        missingDeclarations: window.decide?.missingDeclarations,
        error: window.decide?.backendError,
        bridgeError: window.DeskBackend.state().error?.message,
        plan: window.DeskBackend.state().plan,
        planIdentity: window.DeskBackend.state().planIdentity,
        context: window.DeskBackend.state().context
      }));
      assert.equal(diagnosis.phase, 'declaration-required',
        `${intent} exact resume failed: ${JSON.stringify(diagnosis)}; requests=${JSON.stringify(backend.requests)}`);
      assert.deepEqual(diagnosis.missingDeclarations, ['view', 'horizon'],
        'the Desk names only the declarations that are genuinely absent');
      assert.equal(diagnosis.plan?.id, PLAN_ID);
      await page.locator('[data-dec="intent"]').click();
      assert.equal(await page.locator(`[data-obj="goal"][data-val="${intent[0] + intent.slice(1).toLowerCase()}"]`).getAttribute('class'),
        'on', `${intent} remains visibly selected in the complete canonical intent control`);

      assert.deepEqual(await page.evaluate(() => ({
        visibleGoal: window.decide.goal,
        visibleView: window.decide.view,
        visibleHorizon: window.decide.horizon,
        requested: {
          goal: window.DeskBackend.state().context.goal,
          view: window.DeskBackend.state().context.view,
          horizon: window.DeskBackend.state().context.horizon
        },
        plan: {
          intent: window.DeskBackend.state().plan.intent,
          thesis: window.DeskBackend.state().plan.context.thesis,
          horizonDays: window.DeskBackend.state().plan.context.horizonDays
        }
      })), {
        visibleGoal: intent[0] + intent.slice(1).toLowerCase(),
        visibleView: 'Undeclared',
        visibleHorizon: 'Undeclared',
        requested: { goal: intent, view: null, horizon: null },
        plan: { intent, thesis: null, horizonDays: null }
      }, `${intent} resumes its exact canonical declarations without UI defaults becoming backend facts`);
      assert.equal(backend.count('GET', `/api/plans/${PLAN_ID}`), 1);
      assert.equal(backend.count('GET', '/api/plans'), 0);
      assert.equal(backend.count('POST', '/api/plans'), 0,
        `${intent} resume cannot create a defaulted INCOME Plan`);
      assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/run`), 0,
        `${intent} cannot rank strategies until its absent declarations are explicit`);
      assert.deepEqual(pageErrors, [], `${intent} exact resume emitted page errors: ${pageErrors.join('\n')}`);
    } finally {
      await context.close();
    }
  }
});

test('editing a resumed exact Plan declaration updates that Plan instead of minting a replacement', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const exact = {
    id: PLAN_ID, symbol: 'AMD', intent: 'INCOME', thesis: 'neutral', horizonDays: 30,
    riskMode: 'balanced', contextRev: 11
  };
  const bookDocuments = emptyBookDocuments();
  bookDocuments.planPortfolio = [{ plan: plan(10, exact) }];
  const backend = await installBackend(page, { bookDocuments, activePlanOverrides: exact });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector(`[data-auth-plan-id="${PLAN_ID}"]`);
    await page.locator(`[data-auth-plan-id="${PLAN_ID}"]`).click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'ready', null, { timeout: 10000 });

    const createsBefore = backend.count('POST', '/api/plans');
    await page.locator('[data-dec="intent"]').click();
    await page.locator('[data-obj="view"][data-val="Bullish"]').click();
    await page.waitForFunction(planId => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().plan?.id === planId
      && window.DeskBackend.state().plan?.context?.thesis === 'bullish', PLAN_ID,
    { timeout: 10000 });

    const contextUpdates = backend.requests.filter(row => row.method === 'PUT'
      && row.path === `/api/plans/${PLAN_ID}/context`);
    assert.equal(contextUpdates.length, 1);
    assert.equal(contextUpdates[0].body.thesis, 'bullish');
    assert.equal(contextUpdates[0].body.expectedVersion, 11,
      'the declaration edit is versioned after the initial strategy selection');
    assert.equal(backend.count('POST', '/api/plans'), createsBefore,
      'editing a visible declaration never forks a hidden replacement Plan');
    assert.equal(await page.evaluate(() => window.decide.resumePlanId), PLAN_ID);
    assert.equal(await page.evaluate(() => window.decide.view), 'Bullish');
    assert.deepEqual(pageErrors, [], `exact Plan declaration edit emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('declaration reload clears every scenario pin before rebuilding the exact Plan', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    await page.locator('#decideStage .srow[data-si="5"]').click();
    await page.waitForFunction(candidateId => {
      const candidate = window.decide?.cands.find(row => row.id === candidateId);
      return window.DeskBackend.state().animation?.testMarker === 'second'
        && candidate?.authoritativeAnimation?.testMarker === 'second';
    }, CANDIDATE_ID, { timeout: 10000 });

    assert.deepEqual(await page.evaluate(candidateId => ({
      scenario: Object.hasOwn(window.pinnedScen, candidateId),
      move: Object.hasOwn(window.pinMag, candidateId),
      volatility: Object.hasOwn(window.pinIV, candidateId),
      days: Object.hasOwn(window.pinDays, candidateId)
    }), CANDIDATE_ID), {
      scenario: true, move: true, volatility: true, days: true
    }, 'selecting a scenario establishes the complete visible pin state');

    await page.locator('[data-dec="intent"]').click();
    await page.locator('[data-obj="view"][data-val="Bullish"]').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().plan?.context?.thesis === 'bullish'
      && window.DeskBackend.state().mutationPending === false, null, { timeout: 10000 });

    const cleared = await page.evaluate(candidateId => {
      const active = window.decide.cands.find(row => row.id === candidateId);
      return {
        scenario: Object.hasOwn(window.pinnedScen, candidateId),
        move: Object.hasOwn(window.pinMag, candidateId),
        volatility: Object.hasOwn(window.pinIV, candidateId),
        days: Object.hasOwn(window.pinDays, candidateId),
        timeUnit: Object.hasOwn(window.pinTUnit, candidateId),
        visiblePins: document.querySelectorAll('#decideStage .srow.pinned').length,
        bridgeAnimation: window.DeskBackend.state().animation,
        visibleAnimation: window.decide.animation,
        candidateAnimation: active?.authoritativeAnimation || null
      };
    }, CANDIDATE_ID);
    assert.deepEqual(cleared, {
      scenario: false,
      move: false,
      volatility: false,
      days: false,
      timeUnit: false,
      visiblePins: 0,
      bridgeAnimation: null,
      visibleAnimation: null,
      candidateAnimation: null
    }, 'a declaration change cannot carry a stale conditioned scenario into the new valuation');
    assert.equal(backend.scenarioCalls(), 1,
      'the old pin is cleared rather than silently replayed against the rebuilt ensemble');
    assert.deepEqual(pageErrors, [], `declaration scenario reset emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a rejected declaration edit restores the accepted Plan and Retry remains usable', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const exact = {
    id: PLAN_ID, symbol: 'AMD', intent: 'INCOME', thesis: 'neutral', horizonDays: 30,
    riskMode: 'balanced', contextRev: 11
  };
  const bookDocuments = emptyBookDocuments();
  bookDocuments.planPortfolio = [{ plan: plan(10, exact) }];
  const backend = await installBackend(page, {
    bookDocuments, activePlanOverrides: exact, declarationFailures: 1,
    declarationConflictOverrides: { thesis: 'bearish' }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector(`[data-auth-plan-id="${PLAN_ID}"]`);
    await page.locator(`[data-auth-plan-id="${PLAN_ID}"]`).click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'ready', null,
      { timeout: 10000 });
    const acceptedVersion = await page.evaluate(() => window.DeskBackend.state().plan.version);

    await page.locator('[data-dec="intent"]').click();
    await page.locator('[data-obj="view"][data-val="Bullish"]').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'error', null,
      { timeout: 10000 });
    assert.equal(await page.evaluate(() => window.decide.view), 'Bearish',
      'the visible declaration rehydrates the newer Plan version the backend accepted');
    assert.equal(await page.evaluate(() => window.decide.resumePlanContext.view), 'bearish');
    assert.equal(await page.evaluate(() => window.DeskBackend.state().plan.context.thesis), 'bearish');
    assert.equal(await page.evaluate(() => window.DeskBackend.state().plan.version),
      acceptedVersion + 1, 'the conflict refresh adopts the concurrently advanced Plan version');

    await page.locator('[data-dec="retry"]').click();
    await page.waitForFunction(planId => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().plan?.id === planId, PLAN_ID, { timeout: 10000 });
    assert.equal(backend.count('POST', '/api/plans'), 0,
      'retry continues the exact Plan instead of minting a replacement');
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/context`), 1,
      'retry does not replay the rejected optimistic declaration');
    assert.deepEqual(pageErrors, [], `declaration rollback emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Home lists and hydrates only Plans owned by the active account', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = emptyBookDocuments();
  const marketDocuments = populatedBookDocuments();
  const ownPlan = plan(31, { id: BOOK_PLAN_ID, symbol: 'AAPL' });
  const otherPlan = plan(32, { id: 'plan_other_account', symbol: 'NVDA', accountId: 'account_other' });
  bookDocuments.planPortfolio = [{ plan: otherPlan }, { plan: ownPlan }];
  bookDocuments.research = marketDocuments.research;
  bookDocuments.news = marketDocuments.news;
  const backend = await installBackend(page, { bookDocuments });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.data?.homeContext?.phase === 'ready',
      null, { timeout: 10000 });

    assert.deepEqual(await page.evaluate(() => ({
      planIds: Array.from(document.querySelectorAll('[data-auth-plan-id]'))
        .map(node => node.getAttribute('data-auth-plan-id')),
      symbols: window.DeskBackend.state().book.data.homeContext.symbols,
      accountPlanIds: window.DeskBackend.state().book.data.accountPlans
        .map(row => row.plan.id)
    })), {
      planIds: [BOOK_PLAN_ID],
      symbols: ['AAPL'],
      accountPlanIds: [BOOK_PLAN_ID]
    });
    assert.equal(backend.count('GET', '/api/research/AAPL'), 1);
    assert.equal(backend.count('GET', '/api/research/NVDA'), 0,
      'another account\'s Plan cannot drive this Home market context');
    assert.deepEqual(pageErrors, [], `account-scoped Home emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Home working rail excludes closed, archived, and non-editable Plans', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = emptyBookDocuments();
  bookDocuments.planPortfolio = [
    { plan: plan(40, { id: PLAN_ID, symbol: 'AMD', status: 'ACTIVE', open: true }) },
    { plan: plan(41, { id: 'plan_closed', symbol: 'NVDA', status: 'ACTIVE', open: false }) },
    { plan: plan(42, { id: 'plan_archived', symbol: 'AAPL', status: 'ARCHIVED', open: true }) },
    { plan: plan(43, { id: 'plan_frozen', symbol: 'MU', status: 'DRAFT', open: true,
      assumptionsEditable: false }) }
  ];
  const backend = await installBackend(page, { bookDocuments, universeSymbols: ['SPY'] });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });

    assert.deepEqual(await page.evaluate(() => Array.from(document.querySelectorAll('[data-auth-plan-id]'))
      .map(node => node.getAttribute('data-auth-plan-id'))), [PLAN_ID],
    'only open, editable DRAFT/ACTIVE Plans appear as working actions');
    assert.deepEqual(await page.evaluate(() => window.DeskBackend.state().book.data.homeContext.symbols),
      ['AMD', 'SPY'], 'closed and frozen Plan symbols cannot drive the working market rail; the ambient universe remains additive');
    assert.equal(backend.count('GET', '/api/research/NVDA'), 0);
    assert.equal(backend.count('GET', '/api/research/AAPL'), 0);
    assert.equal(backend.count('GET', '/api/research/MU'), 0);
    assert.deepEqual(pageErrors, [], `working Plan filtering emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Home keeps active trades, share inventory, and market research usable without a Plan', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = populatedBookDocuments();
  bookDocuments.planPortfolio = [];
  bookDocuments.sharePositions = [{
    symbol: 'AAPL', shares: 100, freeShares: 75, lockedShares: 25,
    avgCostCents: 20000, lastCents: 22222, marketValueCents: 2222200,
    unrealizedCents: 222200, gainPct: 0.1111
  }];
  bookDocuments.summary.sharesPositions = 1;
  bookDocuments.summary.sharesValueCents = 2222200;
  const backend = await installBackend(page, { bookDocuments });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.data?.homeContext?.phase === 'ready',
      null, { timeout: 10000 });

    const home = await page.evaluate(tradeId => ({
      plans: document.querySelectorAll('[data-auth-plan-id]').length,
      cardVisible: document.querySelector(`#book [data-id="${tradeId}"]`)?.getClientRects().length > 0,
      shareText: document.querySelector('#bookrisk')?.textContent.replace(/\s+/g, ' ').trim(),
      analyzeShares: document.querySelectorAll('#bookrisk [data-auth-newidea-symbol="AAPL"]').length,
      contextSymbols: window.DeskBackend.state().book.data.homeContext.symbols,
      researchText: document.querySelector('#newsBand')?.textContent
    }), BOOK_TRADE_ID);
    assert.equal(home.plans, 0);
    assert.equal(home.cardVisible, true, 'an active trade remains a first-class clickable Home row');
    assert.match(home.shareText, /Share inventory[\s\S]*AAPL · 100 shares[\s\S]*75 free · 25 locked/i);
    assert.equal(home.analyzeShares, 1, 'share inventory retains an analysis action without an owning Plan');
    assert.deepEqual(home.contextSymbols, ['AAPL'],
      'owned trade/share symbols drive market context when no Plan exists');
    assert.match(home.researchText, /Backend research sentinel headline/i);

    await page.locator(`#book [data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.state?.level === 'position'
      && window.DeskBackend.state().position?.data?.trade?.id === tradeId,
    BOOK_TRADE_ID, { timeout: 10000 });
    assert.equal(backend.count('GET', `/api/trades/${BOOK_TRADE_ID}`), 1);
    assert.equal(backend.requests.some(row => row.path.includes('/manage')), false,
      'a usable unplanned position never fabricates an owning Plan request');
    assert.deepEqual(pageErrors, [], `Plan-free Home emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('delayed Home context preserves roster and Plan DOM identity, focus, and scroll', async () => {
  const context = await browser.newContext({ viewport: { width: 390, height: 520 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = populatedBookDocuments();
  const backend = await installBackend(page, { bookDocuments, homeContextDelayMs: 800 });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.data?.homeContext?.phase === 'loading'
      && document.querySelector('[data-auth-plan-id]'), null, { timeout: 10000 });
    await page.locator(`[data-auth-plan-id="${BOOK_PLAN_ID}"]`).focus();
    await page.evaluate(tradeId => {
      const board = document.querySelector('#stage .board');
      board.scrollTop = Math.min(120, Math.max(0, board.scrollHeight - board.clientHeight));
      window.__homeStable = {
        card: document.querySelector(`#book [data-id="${tradeId}"]`),
        plan: document.querySelector('[data-auth-plan-id]'),
        focused: document.activeElement,
        scrollTop: board.scrollTop
      };
    }, BOOK_TRADE_ID);
    await page.waitForFunction(() => window.DeskBackend.state().book?.data?.homeContext?.phase === 'ready',
      null, { timeout: 10000 });

    assert.deepEqual(await page.evaluate(tradeId => {
      const stable = window.__homeStable;
      const board = document.querySelector('#stage .board');
      return {
        sameCard: stable.card === document.querySelector(`#book [data-id="${tradeId}"]`),
        samePlan: stable.plan === document.querySelector('[data-auth-plan-id]'),
        sameFocus: stable.focused === document.activeElement,
        scrollBefore: stable.scrollTop,
        scrollAfter: board.scrollTop,
        newsReady: /Backend research sentinel headline/i.test(document.querySelector('#newsBand').textContent)
      };
    }, BOOK_TRADE_ID), {
      sameCard: true,
      samePlan: true,
      sameFocus: true,
      scrollBefore: await page.evaluate(() => window.__homeStable.scrollTop),
      scrollAfter: await page.evaluate(() => window.__homeStable.scrollTop),
      newsReady: true
    }, 'optional Research hydration patches its panels without remounting the interactive Book');
    assert.equal(backend.count('GET', '/api/research/AAPL'), 1);
    assert.deepEqual(pageErrors, [], `delayed Home patch emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('missing observed history leaves Position Bloom usable with its structural trade receipt', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  documents.history = {
    symbol: 'AAPL', source: null, freshness: 'MISSING', candles: [],
    evidence: {
      source: null, age: 'MISSING', freshness: 'MISSING', provenance: 'MISSING'
    }
  };
  const backend = await installBackend(page, { bookDocuments: documents });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('#book .card[data-id="' + BOOK_TRADE_ID + '"]',
      { timeout: 10000 });
    await page.evaluate(() => {
      window.__positionPhases = [];
      document.addEventListener('strikebench:desk-backend', event => {
        if (String(event.detail?.phase || '').startsWith('position-')) {
          window.__positionPhases.push(event.detail.phase);
        }
      });
    });
    await page.locator('#book .card[data-id="' + BOOK_TRADE_ID + '"]').click();
    await page.waitForFunction(tradeId => {
      const position = window.DeskBackend.state().position;
      return position?.phase === 'partial'
        && position.data?.trade?.id === tradeId
        && position.data?.auxiliaryPending === false;
    }, BOOK_TRADE_ID, { timeout: 10000 });

    const rendered = await page.evaluate(tradeId => {
      const position = window.DeskBackend.state().position;
      const host = document.querySelector(`[data-auth-position-detail="${tradeId}"]`);
      return {
        phase: position.phase,
        tradeId: position.data.trade.id,
        history: position.data.history,
        missing: position.missing.map(row => ({ key: row.key, message: row.error?.message })),
        phases: window.__positionPhases,
        payoffPaths: host.querySelectorAll('svg.authpayoff path').length,
        payoffSvg: host.querySelectorAll('svg.authpayoff').length,
        legs: host.querySelectorAll('.authleg').length,
        failed: host.querySelectorAll('.authfailed').length,
        loading: /Loading the exact position/i.test(host.textContent),
        sourceAction: host.querySelector('a[href="/#/data/sources"]')?.textContent.trim(),
        text: host.textContent.replace(/\s+/g, ' ').trim()
      };
    }, BOOK_TRADE_ID);
    assert.equal(rendered.phase, 'partial');
    assert.equal(rendered.tradeId, BOOK_TRADE_ID);
    assert.equal(rendered.history, null);
    assert.deepEqual(rendered.phases, ['position-loading', 'position-partial', 'position-partial'],
      'the exact trade renders before optional evidence settles, then remains a typed partial receipt');
    assert.equal(rendered.payoffSvg, 1, 'the server payoff remains visible without observed history');
    assert.equal(rendered.legs, 2, 'the exact package legs remain visible without observed history');
    assert.equal(rendered.failed, 0);
    assert.equal(rendered.loading, false, 'missing history cannot strand the Bloom in a skeleton');
    assert.equal(rendered.sourceAction, 'Review sources & fetch bars →');
    assert.match(rendered.text, /Price history unavailable/i);
    assert.match(rendered.missing.find(row => row.key === 'history')?.message || '',
      /daily history is not stored/i);
    assert.equal(backend.count('GET', '/api/trades/' + BOOK_TRADE_ID), 1);
    assert.equal(backend.count('GET', '/api/research/AAPL/history'), 2,
      'Home context and the focused Position each receive an explicit history receipt');
    assert.deepEqual(pageErrors, [], `partial Position Bloom emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a structural Position failure renders its error and an in-place retry', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  documents.tradeDetails = {};
  const backend = await installBackend(page, { bookDocuments: documents });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('#book .card[data-id="' + BOOK_TRADE_ID + '"]',
      { timeout: 10000 });
    await page.locator('#book .card[data-id="' + BOOK_TRADE_ID + '"]').click();
    await page.waitForFunction(() => window.DeskBackend.state().position?.phase === 'error',
      null, { timeout: 10000 });
    const host = page.locator(`[data-auth-position-detail="${BOOK_TRADE_ID}"]`);
    await assert.doesNotReject(() => host.locator('[data-auth-position-retry]').waitFor());
    assert.match(await host.textContent(), /This position did not finish loading/i);
    assert.match(await host.textContent(), /position detail is unavailable/i);
    assert.equal(await host.locator('.authpositionnotice').count(), 0,
      'a structural read failure cannot masquerade as a continuing load');

    await host.locator('[data-auth-position-retry]').click();
    await page.waitForFunction(() => window.DeskBackend.state().position?.phase === 'error',
      null, { timeout: 10000 });
    assert.equal(backend.count('GET', '/api/trades/' + BOOK_TRADE_ID), 2,
      'Retry position repeats the structural backend read in place');
    assert.equal(await host.locator('[data-auth-position-retry]').count(), 1);
    assert.deepEqual(pageErrors, [], `Position error state emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Position Bloom renders backend trade, payoff, summary, and Research receipts only', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  const backend = await installBackend(page, { bookDocuments: documents });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });
    await page.waitForSelector('#stage[data-book-authority="ready"] #book .card[data-id="'
      + BOOK_TRADE_ID + '"]', { timeout: 10000 });

    const home = await page.evaluate(tradeId => {
      const state = window.DeskBackend.state();
      const card = document.querySelector('#book .card[data-id="' + tradeId + '"]');
      const stage = document.querySelector('#stage');
      return {
        authority: stage && stage.getAttribute('data-book-authority'),
        phase: state.book && state.book.phase,
        ids: state.book.data.activeTrades.map(trade => trade.id),
        totalValueCents: state.book.data.portfolio.summary.totalValueCents,
        totalPnlCents: state.book.data.portfolio.summary.totalPnlCents,
        text: stage.textContent.replace(/\s+/g, ' ').trim(),
        cardText: card && card.textContent.replace(/\s+/g, ' ').trim(),
        workingIdeas: document.querySelector('#univBand .eyebrow')?.textContent.trim(),
        riskHint: document.querySelector('#riskMain .lenshd .hint')?.textContent.trim(),
        riskPoints: document.querySelectorAll('#authBookRiskViz [data-auth-risk-position]').length,
        authoredSparkPaths: card ? card.querySelectorAll('.cplspark path').length : 0,
        visibleCardIds: Array.from(document.querySelectorAll('#book .card[data-id]'))
          .filter(row => row.getClientRects().length).map(row => row.dataset.id)
      };
    }, BOOK_TRADE_ID);
    assert.equal(home.authority, 'ready');
    assert.equal(home.phase, 'ready');
    assert.deepEqual(home.ids, [BOOK_TRADE_ID]);
    assert.deepEqual(home.visibleCardIds, [BOOK_TRADE_ID],
      'the served roster contains exactly the active backend trade');
    assert.equal(home.totalValueCents, 9876543);
    assert.equal(home.totalPnlCents, -123457);
    assert.match(home.text, /\$98,765/,
      'the Home headline renders the backend account-equity sentinel');
    assert.match(home.text, /−\$1,235|-\$1,235/,
      'the Home headline renders the backend since-funding P/L sentinel');
    assert.match(home.cardText, /AAPL/i);
    assert.match(home.cardText, /call debit spread/i);
    assert.match(home.cardText, /246\.80|247/,
      'the roster exposes the backend unrealized P/L sentinel');
    assert.equal(home.authoredSparkPaths, 0,
      'Home leaves mark history blank until a stored marksHistory receipt is loaded');
    assert.equal(home.workingIdeas, 'Working ideas');
    assert.equal(home.riskHint, 'chance of profit × max loss');
    assert.equal(home.riskPoints, 1, 'each plotted position is a focus control');
    assert.doesNotMatch(home.text,
      /\b(?:authoritative|backend-owned|source-owned|working plans|mark coverage|stored coverage)\b/i,
      'Home speaks in product language rather than implementation vocabulary');
    assert.doesNotMatch(home.text, /Covered strangle|Short-put campaign|Visual fixture/i);

    await page.locator('#authBookRiskViz [data-auth-risk-position="' + BOOK_TRADE_ID + '"]').click();
    await page.waitForFunction(tradeId => {
      const bridge = window.DeskBackend && window.DeskBackend.state();
      return bridge && bridge.position && bridge.position.phase === 'ready'
        && bridge.position.data.trade.id === tradeId;
    }, BOOK_TRADE_ID, { timeout: 10000 });
    await page.waitForFunction(tradeId => window.state && window.state.level === 'position'
      && window.state.focus === tradeId, BOOK_TRADE_ID, { timeout: 10000 });

    const position = await page.evaluate(tradeId => {
      const bridge = window.DeskBackend.state();
      const receipt = bridge.position.data;
      const model = window.byId && window.byId[tradeId];
      const stage = document.querySelector('#stage');
      const scenario = model && window.scenData(model);
      const positionViewport = document.querySelector('[data-auth-position-detail].authpos');
      const researchBand = positionViewport?.querySelector('.authresearchgrid');
      const scenarioBand = positionViewport?.querySelector('.authscenstage');
      const viewportRect = positionViewport?.getBoundingClientRect();
      const researchRect = researchBand?.getBoundingClientRect();
      const scenarioRect = scenarioBand?.getBoundingClientRect();
      const researchPanels = Array.from(researchBand?.children || []);
      const payPanel = positionViewport?.querySelector('.authpaypanel');
      const positionSide = positionViewport?.querySelector('.authposside');
      const sideRect = positionSide?.getBoundingClientRect();
      const legRows = Array.from(positionSide?.querySelectorAll('.authleg') || []);
      return {
        id: receipt.trade.id,
        symbol: receipt.trade.symbol,
        unrealizedCents: receipt.tradeDetail.current.unrealizedCents,
        researchSource: receipt.research.quote.source,
        historySource: receipt.history.source,
        headline: receipt.news.items[0].headline,
        planId: receipt.plan.id,
        authoritative: model && model.authoritative,
        payoffAtSentinel: model && window.payFor(model, 222.22),
        payoffPoints: model && model.payoffPoints,
        scenarioUnavailable: !!(scenario && scenario.unavailable),
        payKeyCount: payPanel?.querySelectorAll('.authpaykey > span').length || 0,
        payoffOverlayLabels: payPanel?.querySelectorAll('svg .pfplat, svg .pfbe, svg .pfmk, svg .pfxp').length || 0,
        legRows: legRows.length,
        legsContained: legRows.every(row => {
          const rect = row.getBoundingClientRect();
          return rect.top >= sideRect.top - 1 && rect.bottom <= sideRect.bottom + 1;
        }),
        researchPanelCount: researchPanels.length,
        researchPanelsContained: researchPanels.every(panel => {
          const panelRect = panel.getBoundingClientRect();
          return panelRect.left >= researchRect.left - 1
            && panelRect.right <= researchRect.right + 1;
        }),
        researchBeforeScenario: !!researchRect && !!scenarioRect
          && researchRect.top < scenarioRect.top,
        researchVisibleAtInitialScroll: !!researchRect && !!viewportRect
          && researchRect.top < viewportRect.bottom && researchRect.bottom > viewportRect.top,
        text: stage.textContent.replace(/\s+/g, ' ').trim(),
        documentOverflow: document.documentElement.scrollWidth
          > document.documentElement.clientWidth
      };
    }, BOOK_TRADE_ID);
    assert.equal(position.id, BOOK_TRADE_ID);
    assert.equal(position.symbol, 'AAPL');
    assert.equal(position.unrealizedCents, 24680);
    assert.equal(position.researchSource, 'BOOK_TEST_RESEARCH_RECEIPT');
    assert.equal(position.historySource, 'BOOK_TEST_HISTORY_RECEIPT');
    assert.equal(position.headline, 'Backend research sentinel headline');
    assert.equal(position.planId, BOOK_PLAN_ID);
    assert.equal(position.authoritative, true);
    assert.ok(Math.abs(position.payoffAtSentinel - 246.8) < 1e-9,
      'Position payoff interpolates the server checkpoint at the exact backend mark');
    assert.deepEqual(position.payoffPoints, [
      { price: 200, profit: -432.1 },
      { price: 222.22, profit: 246.8 },
      { price: 230, profit: 1567.9 }
    ]);
    assert.equal(position.scenarioUnavailable, true,
      'without a position-focused backend canvas, the browser does not invent scenario P/L');
    assert.equal(position.payKeyCount, 5,
      'the payoff keeps its values in a dedicated rail instead of overlaying the curve');
    assert.equal(position.payoffOverlayLabels, 0,
      'the Position hero leaves its curve free of competing value labels');
    assert.equal(position.legRows, 2);
    assert.equal(position.legsContained, true,
      'the Position hero keeps every leg visible inside its top-right decision panel');
    assert.equal(position.researchPanelCount, 4,
      'market, history, news, and management each receive a readable panel');
    assert.equal(position.researchPanelsContained, true,
      'the expanded Position research band remains horizontally contained');
    assert.equal(position.researchBeforeScenario, true,
      'desktop Position Bloom places current Research and news before the scenario workspace');
    assert.equal(position.researchVisibleAtInitialScroll, true,
      'desktop users can see source-owned market context before choosing a scenario');
    assert.match(position.text, /Backend research sentinel headline/i,
      'Position Bloom restores its backend Research/news context');
    assert.match(position.text, /Payoff at expiration/i);
    assert.match(position.text, /Idea & management/i);
    assert.doesNotMatch(position.text,
      /\b(?:authoritative position|backend checkpoints|exact stored legs|quote receipt|source-backed news|plan & management|conditioned|KEYWORD_DERIVED|MANAGE_REVIEW)\b/i,
      'Position Bloom keeps implementation vocabulary out of the user-facing surface');
    assert.match(position.text,
      /scenario P\/L.*remain blank|scenario.*unavailable|authoritative scenario.*required/i,
      'missing backend position checkpoints are disclosed instead of calculated locally');
    assert.doesNotMatch(position.text, /MI400 ramp|Visual fixture|Time decay pays you about/i,
      'the focused backend position does not inherit static fixture research or mechanics');
    assert.equal(position.documentOverflow, false);

    assert.equal(backend.count('GET', '/api/trades/' + BOOK_TRADE_ID), 1);
    assert.equal(backend.count('GET', '/api/research/AAPL'), 1);
    assert.equal(backend.count('GET', '/api/research/AAPL/history'), 2,
      'Home context and the focused Position each receive an explicit history receipt');
    assert.equal(backend.count('GET', '/api/research/AAPL/news'), 1);
    assert.equal(backend.count('GET', '/api/plans/' + BOOK_PLAN_ID + '/manage'), 1);
    assert.equal(backend.count('GET', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/latest`), 1,
      'Position Bloom names the owning Plan stored ensemble as an optional receipt');
    assert.equal(backend.count('POST', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble`), 0,
      'Position Bloom never creates a replacement fan');
    assert.equal(backend.count('POST', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`), 0,
      'opening a Position does not condition paths until a scenario is selected');
    assert.deepEqual(pageErrors, [], `authoritative Position Bloom emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('global New idea from Position Bloom starts a mutable Plan and preserves the focused Position', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  const frozenPositionPlan = plan(31, {
    id: BOOK_PLAN_ID,
    symbol: 'AAPL',
    status: 'POSITION_OPEN',
    assumptionsEditable: false
  });
  documents.planPortfolio[0].plan = frozenPositionPlan;
  documents.management.plan = frozenPositionPlan;
  documents.positionEnsemble.plan = frozenPositionPlan;
  const backend = await installBackend(page, {
    bookDocuments: documents,
    listedPlans: [frozenPositionPlan]
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.DeskBackend.state().position?.phase === 'ready'
      && window.state?.level === 'position' && window.state?.focus === tradeId,
    BOOK_TRADE_ID, { timeout: 10000 });
    await page.evaluate(tradeId => {
      window.__positionCardBeforeNewIdea = document.querySelector(`.card[data-id="${tradeId}"]`);
    }, BOOK_TRADE_ID);

    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(planId => window.decide?.backendPhase === 'error'
      || (window.decide?.backendPhase === 'ready'
        && window.DeskBackend.state().plan?.id === planId), PLAN_ID, { timeout: 10000 });
    const diagnosis = await page.evaluate(() => ({
      phase: window.decide?.backendPhase,
      error: window.decide?.backendError,
      plan: window.DeskBackend.state().plan,
      bridgeError: window.DeskBackend.state().error
    }));
    assert.equal(diagnosis.phase, 'ready',
      `fresh Position-context idea failed: ${JSON.stringify(diagnosis)}`);

    const opened = await page.evaluate(tradeId => ({
      planId: window.DeskBackend.state().plan?.id,
      planEditable: window.DeskBackend.state().plan?.assumptionsEditable,
      decidePlanId: window.decide?.resumePlanId,
      decideSymbol: window.decide?.sym,
      level: window.state?.level,
      focus: window.state?.focus,
      positionTradeId: window.DeskBackend.state().position?.data?.trade?.id,
      cardPreserved: window.__positionCardBeforeNewIdea
        === document.querySelector(`.card[data-id="${tradeId}"]`),
      interrupted: /decision is frozen|authoritative data interrupted/i.test(
        document.querySelector('#decideStage')?.textContent || '')
    }), BOOK_TRADE_ID);
    assert.deepEqual(opened, {
      planId: PLAN_ID,
      planEditable: true,
      decidePlanId: null,
      decideSymbol: 'AAPL',
      level: 'position',
      focus: BOOK_TRADE_ID,
      positionTradeId: BOOK_TRADE_ID,
      cardPreserved: true,
      interrupted: false
    }, 'global New idea inherits only the Position symbol while preserving its bloom identity');

    assert.equal(backend.count('GET', `/api/plans/${BOOK_PLAN_ID}`), 0,
      'fresh idea lookup never hydrates the frozen Position Plan as a working Plan');
    assert.equal(backend.count('POST', '/api/plans'), 1,
      'the backend creates a distinct mutable working Plan when only a frozen match exists');
    const create = backend.requests.find(row => row.method === 'POST' && row.path === '/api/plans');
    assert.equal(create.body.symbol, 'AAPL');
    assert.equal(create.body.originPlanId, null,
      'a global idea is not silently linked to or derived from the frozen Position Plan');

    await page.locator('[data-dec="back"]').click();
    await page.waitForFunction(tradeId => !window.decide && window.state?.level === 'position'
      && window.state?.focus === tradeId, BOOK_TRADE_ID);
    assert.equal(await page.evaluate(tradeId => window.__positionCardBeforeNewIdea
      === document.querySelector(`.card[data-id="${tradeId}"]`), BOOK_TRADE_ID), true,
    'closing the fresh idea returns to the same Position Bloom DOM identity');
    assert.deepEqual(pageErrors, [],
      `Position to global New idea emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('New idea rotates a stale create idempotency key after a Plan changes declarations', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const stalePlan = plan(14, {
    id: 'plan_changed_after_create', symbol: 'AMD', intent: 'HEDGE',
    thesis: 'bearish', horizonDays: 30
  });
  const backend = await installBackend(page, {
    listedPlans: [],
    staleCreatePlan: stalePlan
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'error'
      || window.decide?.backendPhase === 'ready', null, { timeout: 10000 });
    const diagnosis = await page.evaluate(() => ({
      phase: window.decide?.backendPhase,
      error: window.decide?.backendError,
      plan: window.DeskBackend.state().plan
    }));
    assert.equal(diagnosis.phase, 'ready', JSON.stringify(diagnosis));
    assert.equal(diagnosis.plan.id, PLAN_ID);
    assert.equal(diagnosis.plan.intent, 'INCOME');
    const creates = backend.requests.filter(row => row.method === 'POST'
      && row.path === '/api/plans');
    assert.equal(creates.length, 2,
      'the stale create result is retried once with a new idempotency key');
    assert.notEqual(creates[0].body.clientRequestId, creates[1].body.clientRequestId);
    assert.equal(backend.count('GET', '/api/plans'), 2,
      'the Desk re-lists before creating a duplicate Plan');
  } finally {
    await context.close();
  }
});

test('Position Bloom opens on its backend median story with a useful paused P/L fan', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  documents.positionEnsemble.preview.spot = 222.22;
  documents.positionEnsemble.preview.decisionMap = { terminal: { p50: 222.22 } };
  const backend = await installBackend(page, { bookDocuments: documents });
  const stageSelector = `#authScenStage-${BOOK_TRADE_ID}`;
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForSelector(`${stageSelector}[data-position-scenario="ready"] path.focusghost`,
      { timeout: 10000 });
    const opened = await page.evaluate(({ tradeId, stageSelector }) => {
      const stage = document.querySelector(stageSelector);
      const state = window.scenSt(tradeId);
      const focus = stage.querySelector('path.focuspath');
      const ghost = stage.querySelector('path.focusghost');
      window.__defaultPositionStage = stage;
      return {
        pinned: window.pinnedScen[tradeId],
        progress: state.t,
        playing: state.playing,
        pathSpace: stage.querySelector('.authpathchart')?.getAttribute('data-path-space'),
        stageText: stage.textContent.replace(/\s+/g, ' ').trim(),
        focusLength: focus?.getTotalLength() || 0,
        ghostLength: ghost?.getTotalLength() || 0
      };
    }, { tradeId: BOOK_TRADE_ID, stageSelector });
    assert.equal(opened.pinned, 4,
      'the ensemble terminal median selects the nearest named Flat/range story');
    assert.equal(opened.progress, 0,
      'the useful default is paused at now rather than silently autoplaying');
    assert.equal(opened.playing, false);
    assert.equal(opened.pathSpace, 'pnl');
    assert.match(opened.stageText, /What happens if — Flat \/ range/i);
    assert.doesNotMatch(opened.stageText, /select a scenario|conditioned|receipt|authoritative/i);
    assert.ok(opened.ghostLength > 0 && opened.focusLength > 0,
      'the full backend path remains visible as context at t=0 instead of leaving a void');

    await page.locator(`${stageSelector} [data-auth-path-view="price"]`).click();
    await page.waitForSelector(`${stageSelector} .authpathchart[data-path-space="price"]`);
    const priceView = await page.evaluate(stageSelector => ({
      space: document.querySelector(stageSelector)?.querySelector('.authpathchart')
        ?.getAttribute('data-path-space'),
      sameStage: window.__defaultPositionStage === document.querySelector(stageSelector)
    }), stageSelector);
    assert.equal(priceView.space, 'price');
    assert.equal(priceView.sameStage, true,
      'switching chart meaning preserves the mounted Position scenario surface');
    await page.locator(`${stageSelector} [data-auth-path-view="pnl"]`).click();
    await page.waitForSelector(`${stageSelector} .authpathchart[data-path-space="pnl"]`);

    await page.locator(`${stageSelector} [data-auth-focus-path]`).dispatchEvent('click');
    await page.waitForFunction(tradeId => window.scenSt(tradeId).t > 0,
      BOOK_TRADE_ID, { timeout: 3000 });
    assert.equal(backend.count('POST', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`), 1,
      'the default and playback reuse one backend projection');
    const request = backend.requests.find(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`);
    assert.equal(request.body.limit, 48);
    assert.equal(Object.hasOwn(request.body, 'paths'), false);
    assert.deepEqual(pageErrors, [], `default Position story emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Position scenario renders the stored noisy path neighborhood and exact checkpoint journey', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { bookDocuments: populatedBookDocuments() });
  const stageSelector = `#authScenStage-${BOOK_TRADE_ID}`;
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.DeskBackend.state().position?.phase === 'ready'
      && window.state?.level === 'position' && window.state?.focus === tradeId,
    BOOK_TRADE_ID, { timeout: 10000 });
    await page.waitForSelector(`${stageSelector}[data-position-scenario="idle"]`);

    await page.locator(`${stageSelector} [data-auth-scenario="0"]`).click();
    await page.waitForSelector(`${stageSelector}[data-position-scenario="ready"] path.focuspath`,
      { timeout: 10000 });
    // The ready event and the request promise both reconcile this bounded panel. Let that
    // microtask pair settle before pinning its identity for resize/scrub assertions.
    await page.waitForTimeout(80);

    const ready = await page.evaluate(({ tradeId, stageSelector }) => {
      const stage = document.querySelector(stageSelector);
      const response = window.byId[tradeId].authoritativeAnimation;
      const storedPaths = response.paths.paths;
      const focus = storedPaths.find(row => row.role === 'FOCUS');
      const deltas = focus.prices.slice(1).map((price, index) => price - focus.prices[index]);
      let directionChanges = 0;
      for (let index = 1; index < deltas.length; index += 1) {
        if (Math.sign(deltas[index]) !== Math.sign(deltas[index - 1])) directionChanges += 1;
      }
      window.__positionScenarioStage = stage;
      return {
        state: stage.getAttribute('data-position-scenario'),
        contextPaths: stage.querySelectorAll('path.context').length,
        focusPaths: stage.querySelectorAll('path.focuspath').length,
        bandCount: stage.querySelectorAll('path.pathband').length,
        focusPrices: focus.prices,
        focusSourcePathIndex: focus.sourcePathIndex,
        receiptFocusKey: response.receipt.focusPositionKey,
        modelFocusKey: response.checkpoints.modelReceipt.focusPositionKey,
        valuationFingerprint: response.checkpoints.modelReceipt.valuationFingerprint,
        chartSpace: stage.querySelector('.authpathchart')?.getAttribute('data-path-space'),
        pnlPathCount: window.authPositionPnlFan(window.byId[tradeId])?.paths?.length || 0,
        directionChanges,
        tileText: stage.querySelector('[data-auth-scenario="0"]').textContent
          .replace(/\s+/g, ' ').trim(),
        text: stage.textContent.replace(/\s+/g, ' ').trim()
      };
    }, { tradeId: BOOK_TRADE_ID, stageSelector });

    assert.equal(ready.state, 'ready');
    assert.equal(ready.contextPaths, 2,
      'the panel retains neighboring trajectories from the stored selection');
    assert.equal(ready.focusPaths, 1,
      'the backend-selected representative trajectory is emphasized exactly once');
    assert.equal(ready.bandCount, 2,
      'outer P10-P90 and inner P25-P75 stored quantile context remain visible');
    assert.deepEqual(ready.focusPrices, [222.22, 215.1, 218.2, 199.9, 190.3, 177.78],
      'the visible focus path is the stored backend response, not a browser substitute');
    assert.ok(ready.directionChanges >= 1,
      'the stored focus trajectory keeps its non-linear session noise instead of becoming a straight ray');
    assert.equal(ready.focusSourcePathIndex, 43);
    assert.equal(ready.receiptFocusKey, BOOK_TRADE_ID);
    assert.equal(ready.modelFocusKey, BOOK_TRADE_ID);
    assert.equal(ready.valuationFingerprint, 'position-valuation-fingerprint-test');
    assert.equal(ready.chartSpace, 'pnl',
      'Position Bloom defaults to the exact package P/L transform of the selected market paths');
    assert.equal(ready.pnlPathCount, 3,
      'the P/L fan retains the three backend-valued source rows in this focused fixture');
    assert.match(ready.tileText, /Market crash.*−\$432|Market crash.*-\$432/,
      'the selected tile exposes the backend target-day P/L');
    assert.match(ready.text, /What happens if — Market crash/i);
    assert.match(ready.text, /this position across the same paths/i);
    assert.match(ready.text, /one market fan.*this position’s response/i);
    assert.match(ready.text, /3 of 500 paths from this market fan/i);
    assert.doesNotMatch(ready.text,
      /Conditioned Monte Carlo|model position-path-model-test-v1|valuation position-valuation|stored paths/i,
      'the user-facing chart explains the market story without exposing simulation receipts');
    assert.doesNotMatch(ready.text, /browser-generated|locally generated|straight-line/i,
      'the authoritative panel makes no claim of a local or straight-line simulation');

    const scrub = page.locator(`${stageSelector} .scrub[data-wf="scrub"]`);
    await scrub.evaluate(input => {
      input.value = '40';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await page.waitForFunction(selector => {
      const stage = document.querySelector(selector);
      return stage?.querySelector('[data-live="px"]')?.textContent === '$218.20'
        && /\$175/.test(stage?.querySelector('[data-live="heroProj"]')?.textContent || '');
    }, stageSelector);
    const middle = await page.evaluate(stageSelector => {
      const stage = document.querySelector(stageSelector);
      return {
        price: stage.querySelector('[data-live="px"]').textContent,
        pnl: stage.querySelector('[data-live="heroProj"]').textContent,
        progress: Number(stage.querySelector('.scrub').value)
      };
    }, stageSelector);
    assert.deepEqual(middle, { price: '$218.20', pnl: '$175', progress: 40 },
      'scrubbing lands on the exact second stored valuation checkpoint');

    await scrub.evaluate(input => {
      input.value = '100';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await page.waitForFunction(selector => document.querySelector(selector)
      ?.querySelector('[data-live="px"]')?.textContent === '$177.78', stageSelector);
    const terminal = await page.evaluate(stageSelector => {
      const stage = document.querySelector(stageSelector);
      const svg = stage.querySelector('.authpathchart');
      const focus = svg.querySelector('path.focuspath');
      const end = focus.getPointAtLength(focus.getTotalLength());
      const viewWidth = svg.viewBox.baseVal.width;
      const clip = svg.querySelector('clipPath rect');
      return {
        price: stage.querySelector('[data-live="px"]').textContent,
        pnl: stage.querySelector('[data-live="heroProj"]').textContent,
        endX: end.x,
        viewWidth,
        clipX: Number(clip.getAttribute('x')),
        revealWidth: Number(clip.getAttribute('width')),
        riskMarker: stage.querySelectorAll('.authscenrisk circle.live').length,
        payoffMarker: document.querySelectorAll('.authpayoff .authpayframe').length
      };
    }, stageSelector);
    assert.equal(terminal.price, '$177.78');
    assert.match(terminal.pnl, /−\$432|-\$432/);
    assert.ok(terminal.endX >= terminal.viewWidth - 11,
      'the selected stored trajectory spans the chart through its conditioned target');
    assert.ok(terminal.revealWidth >= terminal.viewWidth - terminal.clipX - 12,
      'the fully scrubbed journey reveals the complete scenario path');
    assert.equal(terminal.riskMarker, 1,
      'the focused risk journey uses the same backend valuation frame');
    assert.equal(terminal.payoffMarker, 1,
      'the stored payoff carries the same scenario checkpoint marker');

    await scrub.evaluate(input => {
      input.value = '0';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await page.locator(`${stageSelector} [data-wf="spd"][data-s="4"]`).click();
    await page.locator(`${stageSelector} [data-wf="playtoggle"]`).click();
    await page.waitForFunction(tradeId => window.scenSt(tradeId).t === 1,
      BOOK_TRADE_ID, { timeout: 5000 });
    const played = await page.evaluate(stageSelector => {
      const stage = document.querySelector(stageSelector);
      return {
        price: stage.querySelector('[data-live="px"]').textContent,
        pnl: stage.querySelector('[data-live="heroProj"]').textContent,
        scrub: Number(stage.querySelector('.scrub').value)
      };
    }, stageSelector);
    assert.equal(played.price, '$177.78');
    assert.match(played.pnl, /−\$432|-\$432/);
    assert.equal(played.scrub, 100,
      'playback completes on the same exact endpoint as direct checkpoint scrubbing');

    await page.setViewportSize({ width: 390, height: 844 });
    const resized = await page.evaluate(stageSelector => ({
      sameNode: window.__positionScenarioStage === document.querySelector(stageSelector),
      documentOverflow: document.documentElement.scrollWidth
        > document.documentElement.clientWidth
    }), stageSelector);
    assert.equal(resized.sameNode, true,
      'responsive adaptation preserves the mounted scenario-stage DOM identity');
    assert.equal(resized.documentOverflow, false,
      'the conditioned path panel remains contained at mobile width');

    const projection = backend.requests.find(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`);
    assert.equal(projection.body.focusPositionKey, BOOK_TRADE_ID);
    assert.equal(projection.body.limit, 48,
      'the bounded renderer requests enough stored neighbors for credible trajectory texture');
    assert.equal(Object.hasOwn(projection.body, 'paths'), false,
      'the browser sends constraints and never supplies invented path coordinates');
    assert.equal(backend.count('POST', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble`), 0,
      'Position playback does not create a second ensemble');
    assert.deepEqual(pageErrors, [], `authoritative Position scenario emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('unpinning a Position scenario during a delayed response cannot restore cleared projection state', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    positionScenarioDelayMs: 500
  });
  const stageSelector = `#authScenStage-${BOOK_TRADE_ID}`;
  const projectionPath = `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`;
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.DeskBackend.state().position?.phase === 'ready'
      && window.state?.level === 'position' && window.state?.focus === tradeId,
    BOOK_TRADE_ID, { timeout: 10000 });

    await page.locator(`${stageSelector} [data-auth-scenario="0"]`).click();
    for (let attempt = 0; attempt < 120 && backend.count('POST', projectionPath) < 1; attempt += 1) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    const inFlightDiagnostic = await page.evaluate(({ tradeId, stageSelector }) => ({
      level: window.state?.level,
      focus: window.state?.focus,
      pinned: window.pinnedScen?.[tradeId],
      positionScenario: window.DeskBackend.state().positionScenario,
      positionPhase: window.byId?.[tradeId]?._positionScenarioPhase,
      stagePhase: document.querySelector(stageSelector)?.getAttribute('data-position-scenario'),
      stageText: document.querySelector(stageSelector)?.textContent.replace(/\s+/g, ' ').trim()
    }), { tradeId: BOOK_TRADE_ID, stageSelector });
    assert.equal(backend.count('POST', projectionPath), 1,
      `the test clears the scenario only after its conditioned request is in flight: ${JSON.stringify({ inFlightDiagnostic, pageErrors })}`);

    await page.locator(`${stageSelector} [data-auth-scenario="0"]`).click();
    await page.waitForSelector(`${stageSelector}[data-position-scenario="idle"]`);
    await page.waitForFunction(() => window.DeskBackend.state().positionScenario?.phase === 'ready',
      null, { timeout: 5000 });
    await page.waitForTimeout(80);

    const cleared = await page.evaluate(({ tradeId, stageSelector }) => {
      const stage = document.querySelector(stageSelector);
      const position = window.byId[tradeId];
      return {
        stagePhase: stage.getAttribute('data-position-scenario'),
        pinned: Object.prototype.hasOwnProperty.call(window.pinnedScen, tradeId),
        pinnedTiles: stage.querySelectorAll('[data-auth-scenario].pinned').length,
        chartCount: stage.querySelectorAll('.authpathchart').length,
        scenarioPhase: position._positionScenarioPhase,
        animationRestored: !!position.authoritativeAnimation,
        text: stage.textContent.replace(/\s+/g, ' ').trim()
      };
    }, { tradeId: BOOK_TRADE_ID, stageSelector });
    assert.equal(cleared.stagePhase, 'idle');
    assert.equal(cleared.pinned, false);
    assert.equal(cleared.pinnedTiles, 0);
    assert.equal(cleared.chartCount, 0);
    assert.equal(cleared.scenarioPhase, 'idle');
    assert.equal(cleared.animationRestored, false,
      'the late authoritative response cannot repopulate a scenario the user cleared');
    assert.match(cleared.text, /Select a scenario to reveal its stored trajectories/i);
    assert.equal(backend.count('POST', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble`), 0);
    assert.deepEqual(pageErrors, [], `delayed Position scenario emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('reopening cached Position A after Position B restores adapter ownership before conditioning A', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { bookDocuments: twoPositionBookDocuments() });
  const firstDetailPath = `/api/trades/${BOOK_TRADE_ID}`;
  const secondDetailPath = `/api/trades/${SECOND_BOOK_TRADE_ID}`;
  const firstProjectionPath = `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`;
  const secondProjectionPath = `/api/plans/${SECOND_BOOK_PLAN_ID}/outcomes/ensemble/paths`;
  const firstStage = `#authScenStage-${BOOK_TRADE_ID}`;
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });
    await page.waitForSelector(`#book .card[data-id="${BOOK_TRADE_ID}"]`);
    await page.waitForSelector(`#book .card[data-id="${SECOND_BOOK_TRADE_ID}"]`);

    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.DeskBackend.state().position?.phase === 'ready'
      && window.DeskBackend.state().position?.data?.trade?.id === tradeId,
    BOOK_TRADE_ID, { timeout: 10000 });
    assert.equal(backend.count('GET', firstDetailPath), 1);

    await page.evaluate(() => window.go('book'));
    await page.waitForFunction(() => window.state?.level === 'book');
    await page.locator(`#book .card[data-id="${SECOND_BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.DeskBackend.state().position?.phase === 'ready'
      && window.DeskBackend.state().position?.data?.trade?.id === tradeId,
    SECOND_BOOK_TRADE_ID, { timeout: 10000 });
    assert.equal(backend.count('GET', secondDetailPath), 1);

    await page.evaluate(() => window.go('book'));
    await page.waitForFunction(() => window.state?.level === 'book');
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.state?.level === 'position'
      && window.state?.focus === tradeId
      && window.DeskBackend.state().position?.phase === 'ready'
      && window.DeskBackend.state().position?.data?.trade?.id === tradeId,
    BOOK_TRADE_ID, { timeout: 10000 });
    assert.equal(backend.count('GET', firstDetailPath), 2,
      'the cached A card reloads the global adapter after B owned it');

    const restored = await page.evaluate(tradeId => ({
      adapterTradeId: window.DeskBackend.state().position.data.trade.id,
      adapterPlanId: window.DeskBackend.state().position.data.plan.id,
      cardTradeId: window.byId[tradeId]._positionData.trade.id,
      cardPlanId: window.byId[tradeId]._positionData.plan.id
    }), BOOK_TRADE_ID);
    assert.equal(restored.adapterTradeId, BOOK_TRADE_ID);
    assert.equal(restored.adapterPlanId, BOOK_PLAN_ID);
    assert.equal(restored.cardTradeId, BOOK_TRADE_ID);
    assert.equal(restored.cardPlanId, BOOK_PLAN_ID);

    await page.locator(`${firstStage} [data-auth-scenario="0"]`).click();
    await page.waitForSelector(`${firstStage}[data-position-scenario="ready"] path.focuspath`,
      { timeout: 10000 });
    const conditioned = await page.evaluate(({ firstId, secondId, stageSelector }) => {
      const adapter = window.DeskBackend.state();
      const first = window.byId[firstId];
      const second = window.byId[secondId];
      return {
        adapterTradeId: adapter.position.data.trade.id,
        scenarioTradeId: adapter.positionScenario.tradeId,
        responseFocusKey: adapter.positionScenario.data.receipt.focusPositionKey,
        visibleFocusKey: first.authoritativeAnimation.receipt.focusPositionKey,
        firstStageReady: document.querySelector(stageSelector)
          ?.getAttribute('data-position-scenario'),
        secondAnimation: !!second.authoritativeAnimation
      };
    }, {
      firstId: BOOK_TRADE_ID,
      secondId: SECOND_BOOK_TRADE_ID,
      stageSelector: firstStage
    });
    assert.equal(conditioned.adapterTradeId, BOOK_TRADE_ID);
    assert.equal(conditioned.scenarioTradeId, BOOK_TRADE_ID);
    assert.equal(conditioned.responseFocusKey, BOOK_TRADE_ID);
    assert.equal(conditioned.visibleFocusKey, BOOK_TRADE_ID);
    assert.equal(conditioned.firstStageReady, 'ready');
    assert.equal(conditioned.secondAnimation, false,
      'B cannot receive or render a focused scenario requested from A');

    const firstProjection = backend.requests.find(row => row.method === 'POST'
      && row.path === firstProjectionPath);
    assert.ok(firstProjection, 'A sends one focused stored-ensemble projection');
    assert.equal(firstProjection.body.ensembleId, BOOK_ENSEMBLE_ID);
    assert.equal(firstProjection.body.focusPositionKey, BOOK_TRADE_ID);
    assert.equal(backend.count('POST', secondProjectionPath), 0,
      'B\'s owning Plan is never conditioned while A is focused');
    assert.deepEqual(pageErrors, [], `A→B→A Position ownership emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Position scenarios condition the owning Plan stored ensemble on the exact authoritative trade', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { bookDocuments: populatedBookDocuments() });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });
    await page.evaluate(async ({ tradeId, planId }) => {
      await window.DeskBackend.loadPosition(tradeId, { symbol: 'AAPL', planId });
    }, { tradeId: BOOK_TRADE_ID, planId: BOOK_PLAN_ID });

    const requestedWaypoints = [
      { dayIndex: 5, priceRatio: 0.98, tolerance: 0.015 },
      { dayIndex: 10, priceRatio: 0.95 }
    ];
    const result = await page.evaluate(async ({ waypoints, limit }) => {
      const phases = [];
      const listener = event => {
        if (String(event.detail?.phase || '').startsWith('position-scenario-')) {
          phases.push(event.detail.phase);
        }
      };
      document.addEventListener('strikebench:desk-backend', listener);
      const before = window.DeskBackend.state();
      const response = await window.DeskBackend.positionScenario({ waypoints, limit });
      const after = window.DeskBackend.state();
      document.removeEventListener('strikebench:desk-backend', listener);
      const focused = response.checkpoints.positions.find(row => row.key === before.position.data.trade.id);
      return {
        phases,
        bookPhase: after.book.phase,
        positionPhase: after.position.phase,
        scenarioPhase: after.positionScenario.phase,
        newIdeaRequestSeqBefore: before.requestSeq,
        newIdeaRequestSeqAfter: after.requestSeq,
        storedEnsembleIdBefore: before.position.data.positionEnsemble.ensemble.id,
        storedEnsembleFingerprintBefore: before.position.data.positionEnsemble.ensemble.fingerprint,
        storedEnsembleIdAfter: after.position.data.positionEnsemble.ensemble.id,
        storedEnsembleFingerprintAfter: after.position.data.positionEnsemble.ensemble.fingerprint,
        responseEnsembleId: response.ensemble.id,
        responseEnsembleFingerprint: response.ensemble.fingerprint,
        focusPositionKey: response.receipt.focusPositionKey,
        returnedPathWaypoints: response.receipt.conditioningPathWaypoints,
        focusSourcePathIndex: response.paths.receipt.focusSourcePathIndex,
        focusedSteps: focused.steps.map(step => step.focusPnlCents),
        valuationFingerprint: response.receipt.valuationFingerprint,
        modelValuationFingerprint: response.checkpoints.modelReceipt.valuationFingerprint
      };
    }, { waypoints: requestedWaypoints, limit: 7 });

    assert.deepEqual(result.phases, ['position-scenario-loading', 'position-scenario-ready']);
    assert.equal(result.bookPhase, 'ready');
    assert.equal(result.positionPhase, 'ready');
    assert.equal(result.scenarioPhase, 'ready');
    assert.equal(result.newIdeaRequestSeqAfter, result.newIdeaRequestSeqBefore,
      'Position conditioning does not cancel or advance New Idea work');
    assert.equal(result.storedEnsembleIdBefore, BOOK_ENSEMBLE_ID);
    assert.equal(result.storedEnsembleIdAfter, BOOK_ENSEMBLE_ID);
    assert.equal(result.responseEnsembleId, BOOK_ENSEMBLE_ID);
    assert.equal(result.storedEnsembleFingerprintBefore, BOOK_ENSEMBLE_FINGERPRINT);
    assert.equal(result.storedEnsembleFingerprintAfter, BOOK_ENSEMBLE_FINGERPRINT);
    assert.equal(result.responseEnsembleFingerprint, BOOK_ENSEMBLE_FINGERPRINT);
    assert.equal(result.focusPositionKey, BOOK_TRADE_ID);
    assert.deepEqual(result.returnedPathWaypoints, requestedWaypoints.map(pin => ({
      sessionProgress: pin.dayIndex,
      priceRatio: pin.priceRatio,
      ...(pin.tolerance == null ? {} : { tolerance: pin.tolerance })
    })), 'the backend may return normalized intraday path waypoints derived from day-level constraints');
    assert.equal(result.focusSourcePathIndex, 43);
    assert.deepEqual(result.focusedSteps, [24680, 15000, 17500, -5000, -25000, -43200]);
    assert.equal(result.valuationFingerprint, result.modelValuationFingerprint);

    const projectionRequests = backend.requests.filter(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`);
    assert.equal(projectionRequests.length, 1);
    assert.deepEqual(projectionRequests[0].body, {
      ensembleId: BOOK_ENSEMBLE_ID,
      waypoints: requestedWaypoints,
      limit: 7,
      focusPositionKey: BOOK_TRADE_ID
    }, 'the browser sends selection constraints and the exact trade id, not generated paths');
    assert.equal(backend.count('GET', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/latest`), 1);
    assert.equal(backend.requests.filter(row => row.method === 'POST'
      && /\/outcomes\/ensemble$/.test(row.path)).length, 0,
    'Position conditioning never creates a New Idea or Position fan');
    assert.deepEqual(pageErrors, [], `Position scenario emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Position scenario rejects substituted focused identity without disturbing other reads', async () => {
  for (const failure of [
    { option: 'positionScenarioWrongFocus', label: 'focus' },
    { option: 'positionScenarioWrongFingerprint', label: 'fingerprint' },
    {
      option: 'positionScenarioWrongPathWaypoints', label: 'explicit path waypoints',
      pathWaypoints: [{ sessionProgress: 5, priceRatio: 0.975, tolerance: 0.01 }]
    }
  ]) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    const backendOptions = { bookDocuments: populatedBookDocuments(), [failure.option]: true };
    const backend = await installBackend(page, backendOptions);
    try {
      await page.goto(deskUrl);
      await waitForDeskBoot(page);
      await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
        null, { timeout: 10000 });
      const rejected = await page.evaluate(async ({ tradeId, planId, pathWaypoints }) => {
        await window.DeskBackend.loadPosition(tradeId, { symbol: 'AAPL', planId });
        const before = window.DeskBackend.state();
        try {
          await window.DeskBackend.positionScenario({
            waypoints: [{ dayIndex: 10, priceRatio: 0.95, tolerance: 0.02 }],
            ...(pathWaypoints ? { pathWaypoints } : {}),
            limit: 5
          });
          return { rejected: false };
        } catch (error) {
          const after = window.DeskBackend.state();
          return {
            rejected: true,
            message: error.message,
            scenarioPhase: after.positionScenario.phase,
            scenarioError: after.positionScenario.error.message,
            bookPhase: after.book.phase,
            positionPhase: after.position.phase,
            newIdeaRequestSeqBefore: before.requestSeq,
            newIdeaRequestSeqAfter: after.requestSeq,
            storedEnsembleId: after.position.data.positionEnsemble.ensemble.id,
            storedEnsembleFingerprint: after.position.data.positionEnsemble.ensemble.fingerprint
          };
        }
      }, {
        tradeId: BOOK_TRADE_ID, planId: BOOK_PLAN_ID,
        pathWaypoints: failure.pathWaypoints || null
      });
      assert.equal(rejected.rejected, true, `${failure.label} substitution is rejected`);
      assert.match(rejected.message,
        /did not retain the linked Plan, focused trade, stored ensemble, path, and valuation identity/);
      assert.equal(rejected.scenarioPhase, 'error');
      assert.equal(rejected.scenarioError, rejected.message);
      assert.equal(rejected.bookPhase, 'ready');
      assert.equal(rejected.positionPhase, 'ready');
      assert.equal(rejected.newIdeaRequestSeqAfter, rejected.newIdeaRequestSeqBefore);
      assert.equal(rejected.storedEnsembleId, BOOK_ENSEMBLE_ID);
      assert.equal(rejected.storedEnsembleFingerprint, BOOK_ENSEMBLE_FINGERPRINT);
      if (failure.pathWaypoints) {
        const projection = backend.requests.find(row => row.method === 'POST'
          && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`);
        assert.deepEqual(projection.body.pathWaypoints, failure.pathWaypoints);
        assert.equal(Object.hasOwn(projection.body, 'waypoints'), false,
          'fractional and day-level waypoints cannot compete as two conditioning owners');
      }
      assert.equal(backend.requests.filter(row => row.method === 'POST'
        && /\/outcomes\/ensemble$/.test(row.path)).length, 0);
    } finally {
      await context.close();
    }
  }
});

test('an interrupted authoritative load renders one actionable state and retries in place', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    const backend = await installBackend(page, { failResearchOnce: true });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide && window.decide.backendPhase === 'error');

    for (const viewport of [{ width: 1280, height: 800 }, { width: 390, height: 844 }]) {
      await page.setViewportSize(viewport);
      const failed = await page.evaluate(() => {
        const panel = document.querySelector('.authfailed');
        const rect = panel && panel.getBoundingClientRect();
        return {
          panelCount: document.querySelectorAll('.authfailed').length,
          skeletonCount: document.querySelectorAll('.authpending,.authline').length,
          retryCount: document.querySelectorAll('.authfailed [data-dec="retry"]').length,
          text: panel && panel.textContent.replace(/\s+/g, ' ').trim(),
          contained: !!rect && rect.left >= -1 && rect.right <= window.innerWidth + 1,
          documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth
        };
      });
      assert.equal(failed.panelCount, 1);
      assert.equal(failed.skeletonCount, 0, 'a failed request is not presented as an endless loading skeleton');
      assert.equal(failed.retryCount, 1);
      assert.match(failed.text, /observed AMD research receipt is temporarily unavailable/i);
      assert.match(failed.text, /no prototype values were substituted/i);
      assert.equal(failed.contained, true, `${viewport.width}px error state stays inside the viewport`);
      assert.equal(failed.documentOverflow, false, `${viewport.width}px error state has no horizontal page overflow`);
    }

    await page.locator('.authfailed [data-dec="retry"]').click();
    await page.waitForFunction(id => window.decide
      && window.decide.backendPhase === 'ready'
      && window.decide.candId === id, CANDIDATE_ID, { timeout: 10000 });
    assert.equal(backend.count('GET', '/api/research/AMD'), 2);
    assert.equal(await page.locator('.authfailed').count(), 0);
    assert.deepEqual(pageErrors, [], `authoritative retry emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a zero-candidate backend result remains a stable Desk with screening receipts', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    strategyCandidates: [],
    strategyNotes: [
      'The observed AMD chain was analyzed from the prior close; no eligible package survived.'
    ],
    strategyRejected: [
      {
        strategy: 'CASH_SECURED_PUT',
        reasons: ['Assignment chance exceeds the declared cap.']
      },
      {
        family: 'IRON_CONDOR',
        displayName: 'Iron condor',
        blockReasons: ['No liquid two-sided package fits the exact horizon.']
      }
    ],
    marketFreshness: 'STALE'
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide
      && window.decide.backendPhase === 'strategy-empty', null, { timeout: 10000 });

    const initialRun = backend.requests.find(row => row.method === 'POST'
      && row.path === `/api/plans/${PLAN_ID}/strategy/run`);
    assert.deepEqual(initialRun.body, {},
      'unchanged visible defaults are not serialized as hidden backend screens');
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 0);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 0);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 0);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 0);

    for (const viewport of [{ width: 1280, height: 800 }, { width: 390, height: 844 }]) {
      await page.setViewportSize(viewport);
      const empty = await page.evaluate(() => {
        const surface = document.querySelector('.decgrid.authempty');
        const cards = Array.from(document.querySelectorAll('.decgrid.authempty .emptycard'));
        return {
          phase: window.decide && window.decide.backendPhase,
          candidateCount: window.decide && window.decide.cands.length,
          text: surface && surface.textContent.replace(/\s+/g, ' ').trim(),
          rejectionLabels: Array.from(surface?.querySelectorAll('.emptyfact b') || [])
            .map(node => node.textContent.trim()),
          cardCount: cards.length,
          cardsContained: cards.every(card => {
            const rect = card.getBoundingClientRect();
            return rect.left >= -1 && rect.right <= window.innerWidth + 1;
          }),
          failedCount: document.querySelectorAll('.authfailed').length,
          pendingCount: document.querySelectorAll('.authpending').length,
          payoffCount: document.querySelectorAll('#decPay').length,
          documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth
        };
      });
      assert.equal(empty.phase, 'strategy-empty');
      assert.equal(empty.candidateCount, 0);
      assert.equal(empty.cardCount, 3,
        `${viewport.width}px preserves declaration, explanation, and market context`);
      assert.match(empty.text, /observed AMD chain was analyzed from the prior close/i);
      assert.match(empty.text, /assignment chance exceeds the declared cap/i);
      assert.match(empty.text, /no liquid two-sided package fits the exact horizon/i);
      assert.ok(empty.rejectionLabels.includes('Iron condor'),
        'screening receipts lead with the backend human display name');
      assert.equal(empty.rejectionLabels.includes('IRON CONDOR'), false,
        'screening receipts do not expose the backend family enum as the user-facing name');
      assert.match(empty.text, /no package fits this exact idea yet/i);
      assert.match(empty.text, /STALE/i);
      assert.equal(empty.failedCount, 0,
        'a valid empty competition is not presented as a transport failure');
      assert.equal(empty.pendingCount, 0,
        'a valid empty competition does not remain in a loading state');
      assert.equal(empty.payoffCount, 0,
        'the Desk does not fabricate a payoff without a selected backend package');
      assert.equal(empty.cardsContained, true,
        `${viewport.width}px empty-state cards remain horizontally contained`);
      assert.equal(empty.documentOverflow, false,
        `${viewport.width}px empty competition has no horizontal page overflow`);
    }

    const reopened = await page.evaluate(async () => {
      const state = await window.DeskBackend.openIdea(
        Object.assign({}, window.DeskBackend.state().context));
      return {
        phase: window.decide.backendPhase,
        candidates: state.candidates.length,
        notes: state.strategyNotes,
        rejections: state.rejections.length
      };
    });
    assert.equal(reopened.phase, 'strategy-empty');
    assert.equal(reopened.candidates, 0);
    assert.equal(reopened.rejections, 2);
    assert.match(reopened.notes.join(' '), /prior close/i);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/run`), 1,
      'the current fingerprinted empty result is reused instead of refreshing forever');
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 0);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 0);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 0);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 0);
    assert.deepEqual(pageErrors, [], `empty competition emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('served Desk replaces fixture candidates and payoff with backend-owned receipts', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    const rendered = await page.evaluate(candidateId => {
      const active = window.decide.cands.find(row => row.id === candidateId);
      return {
        enabled: window.DeskBackend.enabled(),
        authoritative: active.authoritative,
        selectedId: window.decide.candId,
        label: document.querySelector('.fanr.sel .fnm')?.textContent,
        payoffAtSpot: window.payFor(active, 100),
        payoffPoints: active.payoffPoints,
        pathCount: document.querySelectorAll('#decPay path').length,
        provenance: window.decide.provenance,
        ensembleId: window.decide.ensemble.ensemble.id,
        marketIdentity: window.DeskBackend.state().market.identity,
        planRiskMode: window.DeskBackend.state().plan.context.riskMode,
        planHorizonDays: window.DeskBackend.state().plan.context.horizonDays,
        visibleHorizon: window.decide.horizon,
        positionIdentity: active.positionIdentity,
        deskPickId: window.decide.deskPickId,
        pickBadgeCandidate: document.querySelector('.fanr .pickbadge')?.closest('.fanr')?.dataset.cand,
        fanSummary: document.querySelector('.dcleft .modebar .hint')?.textContent,
        scenarioHint: document.querySelector('.scenpanel > .lenshd .hint')?.textContent,
        monteCarloStats: document.querySelector('.mcstats')?.textContent,
        maxStoredPathSegments: Math.max(0, ...Array.from(document.querySelectorAll('#mcFan path[d]'))
          .map(path => (path.getAttribute('d').match(/L/g) || []).length)),
        monteCarloPathCount: document.querySelectorAll('#mcFan path[d]').length
      };
    }, CANDIDATE_ID);

    assert.equal(rendered.enabled, true, 'HTTP-served Desk enables the backend bridge');
    assert.equal(rendered.authoritative, true, 'selected candidate is backend-owned');
    assert.equal(rendered.selectedId, CANDIDATE_ID);
    assert.match(rendered.label, /Backend debit call spread/);
    assert.equal(rendered.payoffAtSpot, 777,
      'the chart interpolates the backend payoff checkpoint instead of recomputing the legs');
    assert.deepEqual(rendered.payoffPoints, [
      { price: 90, profit: -999 },
      { price: 100, profit: 777 },
      { price: 110, profit: 222 }
    ]);
    assert.ok(rendered.pathCount > 0, 'authoritative payoff renders into the served SVG');
    assert.equal(rendered.provenance.source, 'BACKEND_TEST_RECEIPT');
    assert.equal(rendered.ensembleId, ENSEMBLE_ID);
    assert.deepEqual(rendered.marketIdentity, {
      world: 'observed',
      revision: WORLD_REVISION,
      epoch: WORLD_EPOCH,
      datasetId: DATASET_ID,
      marketLane: 'OBSERVED',
      accountId: ACCOUNT_ID
    }, 'quote, chain, dataset, world, and account share one OBSERVED market identity');
    assert.equal(rendered.planRiskMode, 'balanced',
      'the reused Plan retains the visible Balanced risk posture');
    assert.equal(rendered.planHorizonDays, 45,
      'a brand-new Income declaration is owned by the backend at 45 sessions');
    assert.equal(rendered.visibleHorizon, '45 days',
      'the Desk header shows the same new Income horizon');
    assert.deepEqual(rendered.positionIdentity, positionIdentity(),
      'candidate risk and structure labels come from the canonical backend catalog receipt');
    assert.equal(rendered.deskPickId, CANDIDATE_ID);
    assert.equal(rendered.pickBadgeCandidate, CANDIDATE_ID,
      'Desk Pick is bound to the coherent, favorable backend assessment, not a row index');
    assert.match(rendered.fanSummary, /1 endorsable/);
    assert.match(rendered.scenarioHint, /simulated paths.*selected package valued/,
      'preserving scenario DOM identity still refreshes its selected-package state');
    assert.match(rendered.monteCarloStats, /63\.0%/,
      'the evidence panel renders statistics from the exact stored-outcome response shape');
    assert.ok(rendered.maxStoredPathSegments > 2,
      'the visible stored trajectories retain a multi-checkpoint stochastic journey');
    assert.ok(rendered.monteCarloPathCount >= 48,
      'the instrument renders the bounded 48-path stored ensemble texture plus its bands');
    assert.equal(backend.count('POST', '/api/plans'), 0,
      'the exact matching Plan is reused instead of creating a duplicate');
    assert.equal(backend.count('POST', '/api/strategies/identify'), 0,
      'a candidate that already carries canonical identity does not require fallback classification');
    const strategyRun = backend.requests.find(row => row.method === 'POST'
      && row.path === `/api/plans/${PLAN_ID}/strategy/run`);
    assert.deepEqual(strategyRun.body, {},
      'the first strategy run carries no implicit 0DTE, loss, POP, or assignment screen');

    const marketPreview = backend.requests.find(row =>
      row.method === 'POST' && row.path === `/api/plans/${PLAN_ID}/decision/preview`);
    assert.deepEqual(marketPreview.body.orderInstruction, { type: 'MARKET', timeInForce: 'DAY' },
      'market is a typed execution instruction, separate from debit economics');
    assert.equal(Object.hasOwn(marketPreview.body, 'proposedNetCents'), false,
      'the bridge does not overload signed economics as an instruction');

    for (const viewport of [
      { width: 1280, height: 800 },
      { width: 1440, height: 900 },
      { width: 1920, height: 1080 },
      { width: 2560, height: 1440 },
      { width: 390, height: 844 }
    ]) {
      await page.setViewportSize(viewport);
      const scenarioGeometry = await page.locator('#decideStage .scenpanel').evaluate(panel => {
        const rows = Array.from(panel.querySelectorAll('.srow'));
        return rows.map(row => {
          const outer = row.getBoundingClientRect();
          const children = Array.from(row.children)
            .filter(child => getComputedStyle(child).display !== 'none')
            .map(child => child.getBoundingClientRect());
          return {
            text: row.textContent,
            childrenContained: children.every(rect => rect.left >= outer.left - 1
              && rect.right <= outer.right + 1 && rect.top >= outer.top - 1
              && rect.bottom <= outer.bottom + 1)
          };
        });
      });
      scenarioGeometry.forEach(row => {
        assert.equal(row.childrenContained, true,
          `${viewport.width}px scenario content stays inside its tile`);
        assert.doesNotMatch(row.text, /condition/i,
          `${viewport.width}px does not leak the former condition label`);
      });
      const containment = await page.evaluate(() => ({
        documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
        offenders: ['.dcleft', '.declegpanel', '.pickmap', '.scenpanel', '.execute']
          .flatMap(selector => Array.from(document.querySelectorAll(`#decideStage ${selector}`)))
          .filter(element => {
            const rect = element.getBoundingClientRect();
            return rect.left < -1 || rect.right > window.innerWidth + 1;
          }).map(element => element.className)
      }));
      assert.equal(containment.documentOverflow, false,
        `${viewport.width}px authoritative Desk has no horizontal document overflow`);
      assert.deepEqual(containment.offenders, [],
        `${viewport.width}px authoritative decision panels remain horizontally contained`);
    }
    assert.deepEqual(pageErrors, [], `served Desk emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Desk loads the server strategy catalog and accounts for families outside the ranked field', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    strategyRejected: [{
      strategy: 'CASH_SECURED_PUT',
      reasons: ['Backend assignment chance exceeds the declared cap.']
    }]
  });
  try {
    assert.equal(backend.count('GET', '/api/strategies'), 1,
      'the capability catalog is loaded once alongside the context-specific competition');
    assert.deepEqual(await page.evaluate(() => ({
      families: window.DeskBackend.state().strategyCatalog?.families,
      visibleCount: window.decide.strategyCatalog?.catalog?.length
    })), {
      families: ['CALL_DEBIT_SPREAD', 'CASH_SECURED_PUT', 'PROTECTIVE_PUT', 'NAKED_CALL'],
      visibleCount: 4
    }, 'the Desk retains the exact server-owned catalog receipt');

    await page.locator('[data-dec="lefttool"][data-tool="catalog"]').click();
    await page.waitForSelector('#decideStage .catalogdrawer .catalogrow');
    const drawer = await page.evaluate(() => ({
      heading: document.querySelector('.catalogdrawer .lenshd')?.textContent
        .replace(/\s+/g, ' ').trim(),
      intro: document.querySelector('.catalogdrawer .catalogintro')?.textContent
        .replace(/\s+/g, ' ').trim(),
      rows: Array.from(document.querySelectorAll('.catalogdrawer .catalogrow')).map(row => ({
        name: row.querySelector('.catalogcopy b')?.textContent.trim(),
        reason: row.querySelector('.catalogcopy small')?.textContent.trim(),
        state: row.querySelector('.catalogstate')?.textContent.trim(),
        tag: row.tagName,
        candidateId: row.getAttribute('data-cand'),
        classes: Array.from(row.classList)
      })),
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth
    }));
    assert.match(drawer.heading, /Supported strategy catalog.*4 server-owned families/i);
    assert.match(drawer.intro, /catalog stays complete/i);
    assert.deepEqual(new Set(drawer.rows.map(row => row.name)), new Set([
      'Debit call spread', 'Cash-secured put', 'Protective put', 'Naked call'
    ]), 'the drawer renders every family in the server receipt, not just ranked candidates');
    const rowsByName = Object.fromEntries(drawer.rows.map(row => [row.name, row]));
    assert.equal(rowsByName['Debit call spread'].state, 'compared now');
    assert.equal(rowsByName['Cash-secured put'].state, 'screened out');
    assert.equal(rowsByName['Protective put'].state, 'other intent');
    assert.equal(rowsByName['Naked call'].state, 'blocked');
    assert.equal(drawer.rows[0].tag, 'BUTTON');
    assert.equal(drawer.rows[0].candidateId, CANDIDATE_ID,
      'only the currently ranked family links back to its exact backend candidate');
    assert.match(rowsByName['Cash-secured put'].reason,
      /Backend assignment chance exceeds the declared cap/i,
      'a server rejection explains an applicable family that did not survive screening');
    assert.equal(rowsByName['Protective put'].tag, 'DIV');
    assert.ok(rowsByName['Naked call'].classes.includes('blocked'));
    assert.equal(drawer.overflow, false);
    assert.deepEqual(pageErrors, [], `strategy catalog drawer emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a slow additive strategy catalog never blocks the exact Plan and recommendation flow', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { strategyCatalogDelayMs: 1800 });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(id => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().selected?.id === id
      && window.decide.orderPreview?.selected?.id === id,
    CANDIDATE_ID, { timeout: 1400 });
    assert.equal(await page.evaluate(() => window.DeskBackend.state().strategyCatalog), null,
      'the recommendation is ready while the independent catalog receipt is still pending');
    await page.waitForFunction(() => window.DeskBackend.state().strategyCatalog?.catalog?.length === 4,
      null, { timeout: 5000 });
    assert.equal(backend.count('GET', '/api/strategies'), 1);
    assert.equal(await page.evaluate(() => window.decide.backendPhase), 'ready',
      'late catalog disclosure does not overwrite the financial workflow phase');
    assert.deepEqual(pageErrors, [], `slow strategy catalog emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('the same AMD declaration reopens on a server-created simulated world and Plan', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    const transitioned = await page.evaluate(async () => {
      const before = window.DeskBackend.state();
      let cleared = null;
      document.addEventListener('strikebench:desk-backend', event => {
        if (event.detail.phase !== 'world-transition') return;
        const state = event.detail.state;
        cleared = {
          market: state.market,
          plan: state.plan,
          strategy: state.strategy,
          candidates: state.candidates.length,
          selected: state.selected,
          ensemble: state.ensemble,
          outcome: state.outcome,
          decisionPreview: state.decisionPreview
        };
      });
      const context = Object.assign({}, before.context, {
        symbol: 'AMD', goal: 'Income', view: 'Neutral', horizon: '1 day'
      });
      const state = await window.DeskBackend.transitionWorld('sim', context);
      return {
        beforePlan: before.plan.id,
        beforeEnsemble: before.ensemble.ensemble.id,
        cleared,
        phase: window.decide.backendPhase,
        plan: {
          id: state.plan.id,
          marketKind: state.plan.marketKind,
          worldId: state.plan.worldId,
          symbol: state.plan.symbol,
          intent: state.plan.intent,
          horizonDays: state.plan.context.horizonDays
        },
        market: state.market.identity,
        ensembleId: state.ensemble.ensemble.id,
        ensembleWorld: state.ensemble.preview.receipt.worldId,
        ensembleDataset: state.ensemble.preview.receipt.datasetId,
        selectedId: state.selected.id,
        visibleCandidateId: window.decide.candId,
        mode: window.MKT_MODE,
        observedOn: document.querySelector('#mktMode [data-mkt="observed"]')?.classList.contains('on'),
        simulatedOn: document.querySelector('#mktMode [data-mkt="sim"]')?.classList.contains('on'),
        simulatedBody: document.body.classList.contains('mkt-sim')
      };
    });

    assert.equal(transitioned.beforePlan, PLAN_ID);
    assert.equal(transitioned.beforeEnsemble, ENSEMBLE_ID);
    assert.deepEqual(transitioned.cleared, {
      market: null,
      plan: null,
      strategy: null,
      candidates: 0,
      selected: null,
      ensemble: null,
      outcome: null,
      decisionPreview: null
    }, 'the verified world boundary clears every observed financial artifact before reopening');
    assert.equal(transitioned.phase, 'ready');
    assert.deepEqual(transitioned.plan, {
      id: SIM_PLAN_ID,
      marketKind: 'SIMULATED',
      worldId: SIM_WORLD_ID,
      symbol: 'AMD',
      intent: 'INCOME',
      horizonDays: 1
    }, 'the declaration is preserved while Plan ownership moves to the new simulated world');
    assert.deepEqual(transitioned.market, {
      world: SIM_WORLD_ID,
      revision: WORLD_REVISION + 1,
      epoch: `${SIM_WORLD_ID}-epoch-desk-${WORLD_REVISION + 1}`,
      datasetId: SIM_DATASET_ID,
      marketLane: 'SIMULATED',
      accountId: ACCOUNT_ID
    });
    assert.notEqual(transitioned.ensembleId, transitioned.beforeEnsemble,
      'the observed ensemble cannot cross the world boundary');
    assert.equal(transitioned.ensembleWorld, SIM_WORLD_ID);
    assert.equal(transitioned.ensembleDataset, SIM_DATASET_ID);
    assert.equal(transitioned.selectedId, CANDIDATE_ID);
    assert.equal(transitioned.visibleCandidateId, CANDIDATE_ID);
    assert.equal(transitioned.mode, 'sim');
    assert.equal(transitioned.observedOn, false);
    assert.equal(transitioned.simulatedOn, true);
    assert.equal(transitioned.simulatedBody, true);

    assert.equal(backend.count('POST', '/api/sim/market'), 1);
    const create = backend.requests.find(row => row.method === 'POST' && row.path === '/api/sim/market');
    assert.deepEqual(create.body.symbols, { AMD: 1 });
    assert.equal(create.body.allowFictional, false);
    assert.equal(backend.count('POST', `/api/sim/market/${SIM_WORLD_ID}/start`), 1);
    const worldChange = backend.requests.find(row => row.method === 'PUT' && row.path === '/api/world');
    assert.deepEqual(worldChange.body, { world: SIM_WORLD_ID });
    const planCreates = backend.requests.filter(row => row.method === 'POST' && row.path === '/api/plans');
    assert.equal(planCreates.length, 1,
      'the simulation receives a new market-owned Plan instead of reusing the observed Plan');
    assert.equal(planCreates[0].body.symbol, 'AMD');
    assert.equal(planCreates[0].body.intent, 'INCOME');
    assert.equal(planCreates[0].body.horizonDays, 1);
    assert.equal(backend.count('POST', `/api/plans/${SIM_PLAN_ID}/strategy/run`), 1);
    assert.equal(backend.count('POST', `/api/plans/${SIM_PLAN_ID}/outcomes/ensemble`), 1);
    assert.equal(backend.count('POST', `/api/plans/${SIM_PLAN_ID}/outcomes/run`), 1);
    assert.equal(backend.count('POST', `/api/plans/${SIM_PLAN_ID}/decision/preview`), 1);
    assert.deepEqual(pageErrors, [], `simulated-world transition emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a simulated-world symbol exclusion cannot optimistically flip the visible lane', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    simExclusionReason: 'AMD has no complete server-owned anchor history.'
  });
  try {
    const result = await page.evaluate(async () => {
      const before = window.DeskBackend.state();
      try {
        await window.DeskBackend.transitionWorld('sim', Object.assign({}, before.context, {
          symbol: 'AMD', goal: 'Income', view: 'Neutral', horizon: '1 day'
        }));
        return { rejected: false };
      } catch (error) {
        const after = window.DeskBackend.state();
        return {
          rejected: true,
          message: error.message,
          world: after.market.identity.world,
          lane: after.market.identity.marketLane,
          planId: after.plan.id,
          ensembleId: after.ensemble.ensemble.id,
          pending: after.mutationPending,
          mode: window.MKT_MODE,
          observedOn: document.querySelector('#mktMode [data-mkt="observed"]')?.classList.contains('on'),
          simulatedOn: document.querySelector('#mktMode [data-mkt="sim"]')?.classList.contains('on'),
          simulatedBody: document.body.classList.contains('mkt-sim')
        };
      }
    });
    assert.equal(result.rejected, true);
    assert.match(result.message, /AMD has no complete server-owned anchor history/i);
    assert.equal(result.world, 'observed');
    assert.equal(result.lane, 'OBSERVED');
    assert.equal(result.planId, PLAN_ID);
    assert.equal(result.ensembleId, ENSEMBLE_ID,
      'a rejected transition leaves the observed evaluation intact');
    assert.equal(result.pending, false);
    assert.equal(result.mode, 'observed');
    assert.equal(result.observedOn, true);
    assert.equal(result.simulatedOn, false);
    assert.equal(result.simulatedBody, false);
    assert.equal(backend.count('GET', `/api/sim/market/${SIM_WORLD_ID}/anchors`), 1);
    assert.equal(backend.count('POST', `/api/sim/market/${SIM_WORLD_ID}/start`), 0);
    assert.equal(backend.count('PUT', '/api/world'), 0,
      'the excluded simulation is never promoted to the active server world');
    assert.deepEqual(pageErrors, [], `simulated exclusion emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('unavailable execution preserves candidate economics without promoting zero sentinels', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    unavailableDecisionPreview: true,
    marketFreshness: 'STALE'
  });
  try {
    const rendered = await page.evaluate(candidateId => {
      const active = window.decide.cands.find(row => row.id === candidateId);
      const dock = document.querySelector('.execute');
      const state = window.DeskBackend.state();
      return {
        payoffPoints: active.payoffPoints,
        payoffAtSpot: window.payFor(active, 100),
        payoffPathCount: document.querySelectorAll('#decPay path[d]').length,
        dockText: dock?.textContent.replace(/\s+/g, ' ').trim(),
        reviewDisabled: dock?.querySelector('[data-dec="review"]')?.disabled,
        orderState: window.decide.orderPreview.order.executability,
        valuedNetCents: window.decide.orderPreview.order.valuedNetCents,
        candidateFreshness: active.backend.freshness,
        quoteFreshness: state.market.quote.freshness,
        chainFreshness: state.market.chain.freshness,
        ensembleAnchorFreshness: state.ensemble.preview.receipt.anchorFreshness,
        previewFreshness: window.decide.orderPreview.preview.freshness,
        deskPickId: window.decide.deskPickId,
        pickBadges: document.querySelectorAll('.fanr .pickbadge').length,
        rankRead: document.querySelector('.dcleft .modebar .hint')?.textContent
      };
    }, CANDIDATE_ID);

    assert.equal(rendered.payoffAtSpot, 777);
    assert.equal(rendered.payoffPoints.length, 3,
      'the preview-independent evaluation payoff remains active');
    assert.ok(rendered.payoffPathCount > 0,
      'an unavailable execution book cannot blank the candidate payoff chart');
    assert.equal(rendered.orderState, 'UNAVAILABLE');
    assert.equal(rendered.valuedNetCents, null);
    assert.equal(rendered.candidateFreshness, 'STALE');
    assert.equal(rendered.quoteFreshness, 'STALE');
    assert.equal(rendered.chainFreshness, 'STALE');
    assert.equal(rendered.ensembleAnchorFreshness, 'STALE',
      'analysis retains the prior-close anchor provenance through the ensemble');
    assert.equal(rendered.previewFreshness, 'STALE');
    assert.equal(rendered.deskPickId, null,
      'an unavailable exact package cannot retain the Desk Pick endorsement');
    assert.equal(rendered.pickBadges, 0);
    assert.match(rendered.rankRead, /favorable economic.*execution unavailable/i);
    assert.match(rendered.dockText, /candidate −\$123 debit · execution unavailable/i);
    assert.match(rendered.dockText, /book unavailable/i);
    assert.match(rendered.dockText, /Cannot execute AMD from a stale observed option book/i);
    assert.doesNotMatch(rendered.dockText, /backend proposed \+\$0|collect \+\$0|pay \+\$0/i);
    assert.equal(rendered.reviewDisabled, true);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1,
      'stale same-lane observations still support explicitly labeled analysis');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 1,
      'the selected package is evaluated before execution availability is assessed');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 1,
      'placement uses the separate executable-book preview contract');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/trade`), 0,
      'an unavailable stale book cannot reach commitment');
    assert.deepEqual(pageErrors, [], `unavailable execution emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a missing analyzed package price cannot become a zero-cent limit', async () => {
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    unavailableDecisionPreview: true
  });
  try {
    const state = await page.evaluate(candidateId => {
      const active = window.decide.cands.find(row => row.id === candidateId);
      active.credit = null;
      active.net = null;
      window.decide.order.type = 'limit';
      window.decide.order.price = null;
      window.decide.orderOpen = true;
      window.decide.orderPreview = null;
      window.decide.order.previewPending = false;
      window.renderDecide();
      return {
        request: window.currentOrderRequest(),
        control: document.querySelector('.execute .efield .mv')?.textContent,
        executeText: document.querySelector('.execute')?.textContent.replace(/\s+/g, ' ').trim()
      };
    }, CANDIDATE_ID);
    assert.equal(state.request, null);
    assert.match(state.control, /set price/i);
    assert.doesNotMatch(state.executeText, /\+\$0|−\$0|limit 0/i);
    assert.deepEqual(pageErrors, [], `missing-limit guard emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('missing authoritative POP or EV stays absent from the risk map', async () => {
  const { context, page, pageErrors } = await openAuthoritativeDesk();
  try {
    const map = await page.evaluate(candidateId => {
      const active = window.decide.cands.find(row => row.id === candidateId);
      active.pop = null;
      active.edge = null;
      window.drawDecMap();
      return {
        marks: document.querySelectorAll('#decMap [data-mapi]').length,
        text: document.querySelector('#decMap')?.textContent
      };
    }, CANDIDATE_ID);
    assert.equal(map.marks, 0);
    assert.match(map.text, /comparison metrics unavailable/i);
    assert.deepEqual(pageErrors, [], `missing comparison metrics emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('realistic-measure EV drives favorable candidate presentation while market EV stays a cost benchmark', async () => {
  const { context, page, pageErrors } = await openAuthoritativeDesk();
  try {
    const presented = await page.evaluate(candidateId => {
      const candidate = window.decide.cands.find(row => row.id === candidateId);
      const economics = window.DeskBackend.state().candidates[0]
        .evaluation.assessment.economics;
      return {
        edge: candidate.edge,
        edgeLow: candidate.edgeLow,
        edgeHigh: candidate.edgeHigh,
        edgeBasis: candidate.edgeBasis,
        marketCostBenchmark: candidate.marketCostBenchmark,
        marketEvRole: candidate.marketEvRole,
        verdict: economics.verdict,
        mapRangeCount: document.querySelectorAll('#decMap [data-edge-range]').length,
        mapText: document.querySelector('#decMap')?.textContent.replace(/\s+/g, ' ').trim(),
        briefText: document.querySelector('.decisionbrief')?.textContent.replace(/\s+/g, ' ').trim()
      };
    }, CANDIDATE_ID);
    assert.deepEqual({
      edge: presented.edge,
      edgeLow: presented.edgeLow,
      edgeHigh: presented.edgeHigh,
      edgeBasis: presented.edgeBasis,
      marketCostBenchmark: presented.marketCostBenchmark,
      marketEvRole: presented.marketEvRole,
      verdict: presented.verdict,
      mapRangeCount: presented.mapRangeCount
    }, {
      edge: 14.5,
      edgeLow: -3,
      edgeHigh: 34,
      edgeBasis: 'REALIZED_VOL_AFTER_COSTS',
      marketCostBenchmark: -52,
      marketEvRole: 'Risk-neutral price-consistency and cost benchmark; not an independent edge test.',
      verdict: 'FAVORABLE',
      mapRangeCount: 1
    });
    assert.match(presented.mapText, /realized-vol EV · after costs/i);
    assert.match(presented.briefText, /realistic EV[\s\S]*\+\$15/i);
    assert.match(presented.briefText, /cost benchmark[\s\S]*(?:−|-)\$52/i);
    assert.deepEqual(pageErrors, [], `realistic-EV presentation emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Desk consumes the Research canonical display mark instead of rebuilding a quote midpoint', async () => {
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    quote: { bid: 90, ask: 110, last: 99 },
    researchDisplayPrice: 107.25,
    researchMarkBasis: 'EXECUTABLE_MARK'
  });
  try {
    const mark = await page.evaluate(() => {
      const state = window.DeskBackend.state();
      return {
        spot: state.market.spot,
        candidateSpot: window.decide.cands[0].spot,
        markBasis: state.market.provenance.quote.markBasis,
        quoteBid: state.market.quote.bid,
        quoteAsk: state.market.quote.ask
      };
    });
    assert.deepEqual(mark, {
      spot: 107.25,
      candidateSpot: 107.25,
      markBasis: 'EXECUTABLE_MARK',
      quoteBid: 90,
      quoteAsk: 110
    });
    assert.notEqual(mark.spot, (mark.quoteBid + mark.quoteAsk) / 2,
      'a divergent canonical mark proves the browser did not reconstruct the midpoint');
    assert.deepEqual(pageErrors, [], `canonical display-mark wiring emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('served Desk resumes a canonical observed Plan with its persisted risk posture', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    planRiskMode: 'conservative'
  });
  try {
    const resumed = await page.evaluate(() => {
      const state = window.DeskBackend.state();
      return {
        phase: window.decide.backendPhase,
        planId: state.plan && state.plan.id,
        worldId: state.plan && state.plan.worldId,
        accountId: state.plan && state.plan.accountId,
        riskMode: state.plan && state.plan.context && state.plan.context.riskMode,
        candidateCount: window.decide.cands.length
      };
    });

    assert.deepEqual(resumed, {
      phase: 'ready',
      planId: PLAN_ID,
      worldId: null,
      accountId: ACCOUNT_ID,
      riskMode: 'conservative',
      candidateCount: 1
    }, 'the adapter maps the real observed Plan shape and keeps persisted mutable context authoritative');
    assert.equal(backend.count('POST', '/api/plans'), 0,
      'a different header risk default does not reject or duplicate the canonical active Plan');
    assert.deepEqual(pageErrors, [], `canonical Plan resume emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Home resumes an observed Plan by atomically returning from an active simulated world', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = emptyBookDocuments();
  bookDocuments.planPortfolio = [{ plan: plan(10, { id: PLAN_ID, symbol: 'AMD' }) }];
  const backend = await installBackend(page, { bookDocuments, universeSymbols: ['AMD'] });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
      null, { timeout: 10000 });
    await page.evaluate(async () => {
      await window.DeskBackend.transitionWorld('sim', {
        symbol: 'AMD', reopen: false
      });
      await window.DeskBackend.loadBook();
    });
    await page.waitForFunction(planId => {
      const state = window.DeskBackend.state();
      return state.book?.phase === 'ready'
        && state.book.identity?.marketLane === 'SIMULATED'
        && !!document.querySelector(`[data-auth-plan-id="${planId}"]`);
    }, PLAN_ID, { timeout: 10000 });

    await page.locator(`[data-auth-plan-id="${PLAN_ID}"]`).click();
    await page.waitForFunction(planId => {
      const state = window.DeskBackend.state();
      return window.decide?.backendPhase === 'ready'
        && state.market?.identity?.marketLane === 'OBSERVED'
        && state.plan?.id === planId
        && window.decide?.resumePlanId === planId;
    }, PLAN_ID, { timeout: 10000 });

    const resumed = await page.evaluate(() => {
      const state = window.DeskBackend.state();
      return {
        planId: state.plan.id,
        resumePlanId: window.decide.resumePlanId,
        lane: state.market.identity.marketLane,
        world: state.market.identity.world,
        phase: window.decide.backendPhase,
        error: window.decide.backendError
      };
    });
    assert.deepEqual(resumed, {
      planId: PLAN_ID,
      resumePlanId: PLAN_ID,
      lane: 'OBSERVED',
      world: 'observed',
      phase: 'ready',
      error: null
    });
    const transitions = backend.requests
      .filter(row => row.method === 'PUT' && row.path === '/api/world')
      .map(row => row.body.world);
    assert.deepEqual(transitions, [SIM_WORLD_ID, 'observed'],
      'Plan resume crosses the verified world boundary before requesting the exact Plan');
    assert.equal(backend.count('GET', `/api/plans/${PLAN_ID}`), 1);
    assert.doesNotMatch(await page.locator('body').innerText(),
      /returned Plan does not belong to this Desk account and market/i);
    assert.deepEqual(pageErrors, [], `cross-lane Plan resume emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('served Desk fingerprints outcomes from the canonical research-owned quote', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    const settled = await page.evaluate(() => {
      const state = window.DeskBackend.state();
      return {
        phase: window.decide.backendPhase,
        researchSymbol: state.market.research.symbol,
        freshness: state.market.quote.freshness,
        ensembleId: state.ensemble.ensemble.id,
        outcomeEnsembleId: state.outcome.outcome.ensembleId
      };
    });
    assert.deepEqual(settled, {
      phase: 'ready',
      researchSymbol: 'AMD',
      freshness: 'FRESH',
      ensembleId: ENSEMBLE_ID,
      outcomeEnsembleId: ENSEMBLE_ID
    });
    assert.equal(backend.count('GET', '/api/research/AMD'), 1,
      'the quote and its research/evidence receipt come from the canonical research service');
    assert.equal(backend.count('GET', '/api/quotes'), 1,
      'Home may read its bounded ambient watch independently; the Plan quote still comes from canonical Research');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1,
      'Strategy and Outcomes start after the quote receipt settles');
    assert.deepEqual(pageErrors, [], `research quote wiring emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Desk Pick preserves backend rank while selecting the coherent assessed candidate', async () => {
  const first = incoherentCandidate();
  const second = candidate();
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    strategyCandidates: [first, second]
  });
  try {
    const view = await page.evaluate(() => ({
      order: Array.from(document.querySelectorAll('.fanr[data-cand]')).map(row => row.dataset.cand),
      dimmed: Array.from(document.querySelectorAll('.fanr[data-cand]')).map(row => row.classList.contains('dis')),
      selectedId: window.decide.candId,
      deskPickId: window.decide.deskPickId,
      badgeId: document.querySelector('.fanr .pickbadge')?.closest('.fanr')?.dataset.cand
    }));
    assert.deepEqual(view.order, [first.id, second.id],
      'the Desk does not silently rewrite the backend recommendation rank');
    assert.deepEqual(view.dimmed, [false, false],
      'backend-assessed adjacent alternatives are not re-screened and dimmed by browser-only caps');
    assert.equal(view.selectedId, second.id);
    assert.equal(view.deskPickId, second.id);
    assert.equal(view.badgeId, second.id);
    const selection = backend.requests.find(row => row.method === 'PUT'
      && row.path === `/api/plans/${PLAN_ID}/strategy/select`);
    assert.equal(selection.body.candidateId, second.id,
      'the selected package and visible Desk Pick share one backend candidate identity');
    assert.deepEqual(pageErrors, [], `Desk Pick flow emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('an adverse-only competition requires an explicit comparison selection before outcomes', async () => {
  const adverse = unfavorableCandidate();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { strategyCandidates: [adverse] });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'comparison-required'
      && window.DeskBackend.state().mutationPending === false, null, { timeout: 10000 });
    await page.waitForSelector('#decMap [data-mapi]');

    const comparison = await page.evaluate(() => ({
      candidateIds: Array.from(document.querySelectorAll('.fanr[data-cand]')).map(row => row.dataset.cand),
      previewedId: window.decide.candId,
      selectedId: window.DeskBackend.state().selected?.id || null,
      deskPickId: window.decide.deskPickId,
      badges: document.querySelectorAll('.fanr .pickbadge').length,
      sectionLabel: document.querySelector('.modebar .eyebrow')?.textContent.trim(),
      qualityLabel: document.querySelector('.fanr .ffit')?.textContent.trim(),
      qualityReason: document.querySelector('.fanr .why')?.textContent.trim(),
      metricText: document.querySelector('.fanr .fr3')?.textContent
        .replace(/\s+/g, ' ').trim(),
      rankRead: document.querySelector('.modebar .hint')?.textContent.trim(),
      payoffTitle: document.querySelector('#decideStage .dccenter .paytitle .lbl')?.textContent.trim(),
      selectedRows: document.querySelectorAll('.fanr.sel').length,
      riskMapMarks: document.querySelectorAll('#decMap [data-mapi]').length,
      selectedRiskMapHalos: document.querySelectorAll('#decMap circle[stroke="#fff"]').length,
      riskMapText: document.querySelector('#decMap')?.textContent.replace(/\s+/g, ' ').trim(),
      economicVerdict: window.DeskBackend.state().candidates[0].evaluation.assessment.economics.verdict,
      ensemble: window.DeskBackend.state().ensemble,
      outcome: window.DeskBackend.state().outcome,
      preview: window.DeskBackend.state().decisionPreview
    }));
    assert.deepEqual(comparison.candidateIds, [adverse.id],
      'the adverse package remains available for comparison');
    assert.equal(comparison.previewedId, adverse.id,
      'the ranked package may occupy the comparison surface without becoming Plan state');
    assert.equal(comparison.selectedId, null,
      'ranking alone cannot durably select an adverse package');
    assert.equal(comparison.economicVerdict, 'UNFAVORABLE');
    assert.equal(comparison.deskPickId, null,
      'a coherent declaration fit is not promoted over an unfavorable economic verdict');
    assert.equal(comparison.badges, 0);
    assert.equal(comparison.sectionLabel, 'Engine comparisons');
    assert.equal(comparison.qualityLabel, 'Unfavorable',
      'a coherent intent fit with adverse economics is never presented as merely coherent');
    assert.match(comparison.qualityReason, /modeled after-cost economics are adverse/i,
      'the ranked row itself explains why this is not a recommendation');
    assert.match(comparison.metricText, /chance\s+63%/i);
    assert.match(comparison.metricText, /stress[\s\S]*(?:−|-)\$123/i);
    assert.match(comparison.metricText, /capital[\s\S]*\$123/i,
      'comparison-required keeps the backend POP, stress, and capital field visible');
    assert.equal(comparison.rankRead, 'no endorsable pick · 1 comparison');
    assert.match(comparison.payoffTitle, /^Comparison preview payoff/,
      'the adverse package is labeled as a comparison rather than the recommendation');
    assert.equal(comparison.ensemble, null);
    assert.equal(comparison.outcome, null);
    assert.equal(comparison.preview, null);
    assert.equal(comparison.selectedRows, 0,
      'the previewed first row is not styled as the Plan selection');
    assert.equal(comparison.riskMapMarks, 1,
      'its authoritative POP and realistic after-cost EV remain available for comparison');
    assert.match(comparison.riskMapText, /realized-vol EV · after costs/i);
    assert.equal(comparison.selectedRiskMapHalos, 0,
      'the comparison mark has no false white selected-package halo');
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 0,
      'the Desk does not turn rank into a hidden Plan mutation');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 0,
      'the market distribution waits for an explicit comparison choice');

    await page.locator(`.fanr[data-cand="${adverse.id}"]`).click();
    await page.waitForFunction(candidateId => {
      const state = window.DeskBackend.state();
      return window.decide?.backendPhase === 'ready'
        && state.selected?.id === candidateId
        && state.outcome?.outcome?.candidateId === candidateId
        && window.decide.orderPreview?.selected?.id === candidateId;
    }, adverse.id, { timeout: 10000 });

    const selected = backend.requests.find(row => row.method === 'PUT'
      && row.path === `/api/plans/${PLAN_ID}/strategy/select`);
    assert.equal(selected.body.candidateId, adverse.id,
      'an explicit click records the exact comparison through the canonical selection API');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 1);
    assert.deepEqual(pageErrors, [], `adverse comparison flow emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('favorable economics remain visible when objective fit prevents endorsement', async () => {
  const mixed = favorableMixedFitCandidate();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const backend = await installBackend(page, { strategyCandidates: [mixed] });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'comparison-required'
      && window.DeskBackend.state().mutationPending === false, null, { timeout: 10000 });
    const view = await page.evaluate(() => ({
      deskPickId: window.decide.deskPickId,
      rankRead: document.querySelector('.modebar .hint')?.textContent.trim(),
      quality: document.querySelector('.fanr .ffit')?.textContent.trim(),
      notice: document.querySelector('.dcleft .backendnotice')?.textContent
        .replace(/\s+/g, ' ').trim(),
      pathNotice: document.querySelector('.evsimstage .backendnotice')?.textContent
        .replace(/\s+/g, ' ').trim()
    }));
    assert.equal(view.deskPickId, null,
      'a mixed objective fit is not silently promoted to Desk Pick');
    assert.equal(view.rankRead, '1 favorable economic · no exact-fit pick · 1 comparison');
    assert.equal(view.quality, 'Favorable economics · mixed fit');
    assert.match(view.notice, /favorable after-cost economics.*none fully matches/i);
    assert.match(view.pathNotice, /Favorable economics; mixed objective fit/i);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 0,
      'visible positive economics do not bypass explicit mixed-fit selection');
  } finally {
    await context.close();
  }
});

test('a failed explicit comparison selection clears its queued scenario and restores comparison truth', async () => {
  const adverse = unfavorableCandidate();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    strategyCandidates: [adverse], selectFailures: 1, selectDelayMs: 180
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'comparison-required'
      && window.DeskBackend.state().mutationPending === false, null, { timeout: 10000 });
    await page.locator('#decideStage .srow[data-si="5"]').click();
    await page.waitForFunction(() => window.decide?.backendError
      && window.DeskBackend.state().mutationPending === false, null, { timeout: 10000 });

    const restored = await page.evaluate(candidateId => {
      const row = window.decide.cands.find(candidate => candidate.id === candidateId);
      return {
        phase: window.decide.backendPhase,
        selected: window.DeskBackend.state().selected,
        outcome: window.DeskBackend.state().outcome,
        ensemble: window.DeskBackend.state().ensemble,
        pinned: Object.prototype.hasOwnProperty.call(window.pinnedScen, candidateId),
        queued: row?._authoritativeScenarioQueued,
        selectedRows: document.querySelectorAll('.fanr.sel').length,
        message: window.decide.backendError
      };
    }, adverse.id);
    assert.equal(restored.phase, 'comparison-required');
    assert.equal(restored.selected, null);
    assert.equal(restored.outcome, null);
    assert.equal(restored.ensemble, null);
    assert.equal(restored.pinned, false,
      'the unaccepted package cannot retain a scenario pin');
    assert.equal(restored.queued, false,
      'the failed selection cannot remain queued for a later unrelated receipt');
    assert.equal(restored.selectedRows, 0);
    assert.match(restored.message, /requested comparison could not be selected/i);
    assert.equal(backend.scenarioCalls(), 0);
    assert.deepEqual(pageErrors, [], `failed comparison selection emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('four-leg decisions keep readable stacked legs, a useful risk map, and balanced analysis columns', async () => {
  const condor = fourLegCandidate();
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    strategyCandidates: [condor], expectedCandidateId: condor.id
  });
  try {
    for (const viewport of [
      { width: 1280, height: 800 },
      { width: 1440, height: 900 },
      { width: 1920, height: 1080 },
      { width: 2560, height: 1440 }
    ]) {
      await page.setViewportSize(viewport);
      const geometry = await page.evaluate(() => {
        const rect = selector => document.querySelector(selector)?.getBoundingClientRect();
        const left = rect('#decideStage .dcleft');
        const center = rect('#decideStage .dccenter');
        const right = rect('#decideStage .dcright');
        const map = rect('#decideStage .pickmap');
        const rail = document.querySelector('#decideStage .declegpanel .declegs');
        const rows = Array.from(rail?.querySelectorAll('.legr') || []);
        const selectedName = document.querySelector('#decideStage .fanr.sel .fnm');
        const selectedHeader = selectedName?.closest('.fr1');
        return {
          left: left?.width || 0,
          center: center?.width || 0,
          right: right?.width || 0,
          mapHeight: map?.height || 0,
          legCount: rows.length,
          legRailScrolls: !!rail && rail.scrollHeight > rail.clientHeight + 2,
          legsContained: rows.every(row => {
            const box = row.getBoundingClientRect();
            const parent = rail.getBoundingClientRect();
            return box.left >= parent.left - 1 && box.right <= parent.right + 1;
          }),
          metadataFits: rows.every(row => {
            const meta = row.querySelector('.lgm');
            return !meta || meta.scrollWidth <= meta.clientWidth + 1;
          }),
          metadataWidths: rows.map(row => {
            const meta = row.querySelector('.lgm');
            return meta ? { client: meta.clientWidth, scroll: meta.scrollWidth } : null;
          }),
          selectedNameFits: !!selectedName && selectedName.scrollWidth <= selectedName.clientWidth + 1
            && selectedName.getBoundingClientRect().bottom <= selectedHeader.getBoundingClientRect().bottom + 1,
          selectedNameWraps: selectedName ? getComputedStyle(selectedName).whiteSpace === 'normal' : false,
          documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth
        };
      });
      assert.equal(geometry.legCount, 4);
      assert.equal(geometry.legRailScrolls, false,
        `${viewport.width}px shows a canonical four-leg package without hiding rows in a rail`);
      assert.equal(geometry.legsContained, true,
        `${viewport.width}px keeps every full-width leg inside the workbench`);
      assert.equal(geometry.metadataFits, true,
        `${viewport.width}px keeps exact contract metadata readable on its single line: ${JSON.stringify(geometry)}`);
      assert.ok(geometry.left >= viewport.width * .32,
        `${viewport.width}px reserves useful width for ideas and legs: ${JSON.stringify(geometry)}`);
      assert.ok(geometry.right >= viewport.width * .26,
        `${viewport.width}px reserves useful width for evidence and paths: ${JSON.stringify(geometry)}`);
      assert.ok(geometry.center <= viewport.width * .42,
        `${viewport.width}px payoff remains the center without consuming the canvas: ${JSON.stringify(geometry)}`);
      assert.ok(geometry.mapHeight >= 170,
        `${viewport.width}px keeps the risk/reward map legible: ${JSON.stringify(geometry)}`);
      assert.equal(geometry.selectedNameFits, true,
        `${viewport.width}px keeps the selected exact package name fully visible: ${JSON.stringify(geometry)}`);
      assert.equal(geometry.selectedNameWraps, true,
        `${viewport.width}px wraps rather than ellipsizes the selected exact package name`);
      assert.equal(geometry.documentOverflow, false);
    }

    for (const viewport of [
      { width: 390, height: 844 },
      { width: 375, height: 812 },
      { width: 320, height: 740 }
    ]) {
      await page.setViewportSize(viewport);
      const mobile = await page.evaluate(() => {
        const panel = document.querySelector('#decideStage .declegpanel');
        const rail = panel?.querySelector('.declegs');
        const rows = Array.from(rail?.querySelectorAll('.legr') || []);
        const rowRects = rows.map(row => row.getBoundingClientRect());
        const map = document.querySelector('#decideStage .pickmap');
        const mapRect = map?.getBoundingClientRect();
        const selectedName = document.querySelector('#decideStage .fanr.sel .fnm');
        return {
          legCount: rows.length,
          labels: rows.map(row => row.textContent.replace(/\s+/g, ' ').trim()),
          strikes: rows.map(row => row.querySelector('.legr-top .mv')?.textContent.trim()),
          rowsStacked: rowRects.every((rect, index) => index === 0
            || rect.top >= rowRects[index - 1].bottom - 1),
          rowsReadable: rows.every((row, index) => {
            const rect = rowRects[index];
            const meta = row.querySelector('.lgm');
            return rect.height >= 42
              && rect.left >= -1 && rect.right <= innerWidth + 1
              && (!meta || getComputedStyle(meta).whiteSpace === 'normal')
              && (!meta || meta.scrollWidth <= meta.clientWidth + 1);
          }),
          railClips: !!rail && rail.scrollHeight > rail.clientHeight + 2,
          panelContained: !!panel && panel.scrollWidth <= panel.clientWidth + 1,
          mapContained: !!mapRect && mapRect.left >= -1 && mapRect.right <= innerWidth + 1,
          mapHeight: mapRect?.height || 0,
          selectedNameReadable: !!selectedName
            && selectedName.scrollWidth <= selectedName.clientWidth + 1
            && getComputedStyle(selectedName).textOverflow !== 'ellipsis',
          documentOverflow: document.documentElement.scrollWidth
            > document.documentElement.clientWidth
        };
      });
      assert.equal(mobile.legCount, 4);
      assert.deepEqual(mobile.strikes, ['90', '95', '105', '110'],
        `${viewport.width}px preserves all four exact strikes in package order`);
      assert.equal(mobile.rowsStacked, true,
        `${viewport.width}px renders one readable full-width leg per row`);
      assert.equal(mobile.rowsReadable, true,
        `${viewport.width}px wraps contract metadata without clipping: ${JSON.stringify(mobile)}`);
      assert.equal(mobile.railClips, false,
        `${viewport.width}px does not hide legs in a nested rail`);
      assert.equal(mobile.panelContained, true);
      assert.equal(mobile.mapContained, true);
      assert.ok(mobile.mapHeight >= 200,
        `${viewport.width}px retains a useful linked risk map below the legs`);
      assert.equal(mobile.selectedNameReadable, true);
      assert.equal(mobile.documentOverflow, false);
    }
    assert.deepEqual(pageErrors, [], `four-leg responsive decision emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('920 and 1000 pixel decisions stack structurally with contained scenario controls', async () => {
  const condor = fourLegCandidate();
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    strategyCandidates: [condor], expectedCandidateId: condor.id
  });
  try {
    for (const viewport of [{ width: 920, height: 820 }, { width: 1000, height: 900 }]) {
      await page.setViewportSize(viewport);
      if (await page.locator('#decideStage .srow-ctl').count() === 0) {
        await page.locator('#decideStage .srow[data-si="5"]').click();
        await page.waitForFunction(() => window.decide?.animation
          && document.querySelector('#decideStage .srow-ctl'), null, { timeout: 10000 });
      }
      const geometry = await page.evaluate(() => {
        const wrap = document.querySelector('#decideStage .decwrap');
        const grid = document.querySelector('#decideStage .decgrid');
        const columns = ['.dcleft', '.dccenter', '.dcright'].map(selector =>
          document.querySelector(`#decideStage ${selector}`)?.getBoundingClientRect());
        const control = document.querySelector('#decideStage .srow-ctl');
        const controlBox = control?.getBoundingClientRect();
        const controlParts = Array.from(control?.children || [])
          .filter(element => getComputedStyle(element).display !== 'none')
          .map(element => ({ className: element.className, box: element.getBoundingClientRect() }));
        const selectedName = document.querySelector('#decideStage .fanr.sel .fnm');
        return {
          layout: getComputedStyle(grid).display,
          direction: getComputedStyle(grid).flexDirection,
          columnWidths: columns.map(rect => rect?.width || 0),
          columnTops: columns.map(rect => rect?.top || 0),
          columnsContained: columns.every(rect => rect && rect.left >= -1 && rect.right <= innerWidth + 1),
          controlContained: !!controlBox && controlBox.left >= -1 && controlBox.right <= innerWidth + 1
            && controlParts.every(row => row.box.left >= controlBox.left - 1
              && row.box.right <= controlBox.right + 1),
          controlParts: controlParts.map(row => ({
            className: row.className,
            left: Math.round(row.box.left),
            right: Math.round(row.box.right)
          })),
          selectedNameVisible: !!selectedName
            && selectedName.scrollWidth <= selectedName.clientWidth + 1
            && getComputedStyle(selectedName).textOverflow !== 'ellipsis',
          wrapContained: wrap.scrollWidth <= wrap.clientWidth + 1,
          documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
          scenarioPinned: document.querySelector('#decideStage .srow.pinned')?.getAttribute('data-si'),
          scenarioControls: {
            scrub: !!control?.querySelector('.scrub'),
            move: !!control?.querySelector('[data-asm="mag"]'),
            vol: !!control?.querySelector('[data-asm="iv"]'),
            time: !!control?.querySelector('[data-asm="days"]')
          }
        };
      });
      assert.equal(geometry.layout, 'flex');
      assert.equal(geometry.direction, 'column');
      assert.equal(geometry.columnsContained, true);
      assert.ok(geometry.columnWidths.every(width => width >= viewport.width - 20),
        `${viewport.width}px gives each decision surface full readable width: ${JSON.stringify(geometry)}`);
      assert.ok(geometry.columnTops[0] < geometry.columnTops[1]
        && geometry.columnTops[1] < geometry.columnTops[2],
      `${viewport.width}px orders ideas/legs, payoff/scenarios, then evidence: ${JSON.stringify(geometry)}`);
      assert.equal(geometry.controlContained, true,
        `${viewport.width}px scenario toolbar stays inside its panel: ${JSON.stringify(geometry)}`);
      assert.equal(geometry.selectedNameVisible, true,
        `${viewport.width}px selected exact package remains readable`);
      assert.equal(geometry.wrapContained, true);
      assert.equal(geometry.documentOverflow, false);
      assert.equal(geometry.scenarioPinned, '5');
      assert.deepEqual(geometry.scenarioControls, { scrub: true, move: true, vol: true, time: true });
    }
    assert.deepEqual(pageErrors, [], `intermediate decision layout emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a favorable coherent package receives Desk Pick ahead of a higher-ranked adverse comparison', async () => {
  const adverse = unfavorableCandidate();
  const favorable = candidate();
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    strategyCandidates: [adverse, favorable]
  });
  try {
    const view = await page.evaluate(() => ({
      order: Array.from(document.querySelectorAll('.fanr[data-cand]')).map(row => row.dataset.cand),
      selectedId: window.decide.candId,
      deskPickId: window.decide.deskPickId,
      badgeId: document.querySelector('.fanr .pickbadge')?.closest('.fanr')?.dataset.cand
    }));
    assert.deepEqual(view.order, [adverse.id, favorable.id],
      'frontend endorsement does not reorder the backend competition');
    assert.equal(view.selectedId, favorable.id);
    assert.equal(view.deskPickId, favorable.id);
    assert.equal(view.badgeId, favorable.id);
    const selection = backend.requests.find(row => row.method === 'PUT'
      && row.path === `/api/plans/${PLAN_ID}/strategy/select`);
    assert.equal(selection.body.candidateId, favorable.id);
    assert.deepEqual(pageErrors, [], `economic Desk Pick flow emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('stored ensembles are reused only for the same authoritative quote and market-implied calibration', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    latestEnsembleEnabled: true
  });
  try {
    const strategyPath = `/api/plans/${PLAN_ID}/strategy/run`;
    const ensemblePath = `/api/plans/${PLAN_ID}/outcomes/ensemble`;
    const outcomePath = `/api/plans/${PLAN_ID}/outcomes/run`;
    assert.equal(backend.count('POST', ensemblePath), 1);
    assert.equal(backend.count('POST', outcomePath), 1);

    const matching = await page.evaluate(async () => {
      const state = await window.DeskBackend.openIdea(Object.assign({}, window.DeskBackend.state().context));
      return {
        ensembleId: state.ensemble.ensemble.id,
        candidateId: state.selected.id,
        monteCarloStats: document.querySelector('.mcstats')?.textContent
      };
    });
    assert.equal(matching.ensembleId, ENSEMBLE_ID);
    assert.equal(matching.candidateId, CANDIDATE_ID);
    assert.equal(backend.count('POST', strategyPath), 1,
      'the exact strategy run identity is reusable while market inputs match');
    assert.equal(backend.count('POST', ensemblePath), 1,
      'the matching stored fan is repainted rather than regenerated');
    assert.equal(backend.count('POST', outcomePath), 1,
      'the matching selected-package outcome is restored with that fan');
    assert.match(matching.monteCarloStats, /63\.0%/,
      'the flattened latest-outcomes read retains the same displayed statistics as the run response');

    backend.setQuote({ bid: 101, ask: 103, last: 102, asOf: 1784563260000 });
    const moved = await page.evaluate(async () => {
      const state = await window.DeskBackend.openIdea(Object.assign({}, window.DeskBackend.state().context));
      return {
        ensembleId: state.ensemble.ensemble.id,
        anchorSpot: state.ensemble.preview.receipt.anchorSpot,
        outcomeEnsembleId: state.outcome.outcome.ensembleId
      };
    });
    assert.equal(backend.count('POST', strategyPath), 2,
      'a new quote receipt refreshes the ranked field');
    assert.equal(backend.count('POST', ensemblePath), 2,
      'a stored fan at the old quote anchor is never mixed with the new market header');
    assert.equal(moved.ensembleId, `${ENSEMBLE_ID}_2`);
    assert.equal(moved.anchorSpot, 102);
    assert.equal(moved.outcomeEnsembleId, moved.ensembleId);

    backend.setChainIv(0.37, 1784563200000);
    const recalibrated = await page.evaluate(async () => {
      const state = await window.DeskBackend.openIdea(Object.assign({}, window.DeskBackend.state().context));
      return {
        ensembleId: state.ensemble.ensemble.id,
        storedVol: state.ensemble.preview.receipt.spec.volAnnual,
        outcomeEnsembleId: state.outcome.outcome.ensembleId
      };
    });
    assert.equal(backend.count('POST', strategyPath), 3,
      'a new option-chain receipt refreshes the ranked field');
    assert.equal(backend.count('POST', ensemblePath), 3,
      'current chain-implied IV cannot repaint a fan calibrated to the prior surface');
    assert.equal(recalibrated.ensembleId, `${ENSEMBLE_ID}_3`);
    assert.equal(recalibrated.storedVol, 0.37);
    assert.equal(recalibrated.outcomeEnsembleId, recalibrated.ensembleId);
    assert.deepEqual(pageErrors, [], `ensemble freshness emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a scenario chosen while the exact preview is pending is conditioned after the Plan mutation releases', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { decisionPreviewDelayMs: 900 });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide?.ensemble
      && window.DeskBackend.state().mutationPending === true
      && document.querySelector('#decideStage .srow[data-si="5"]'),
    null, { timeout: 10000 });

    await page.locator('#decideStage .srow[data-si="5"]').click();
    await page.waitForFunction(candidateId => {
      const candidate = window.decide?.cands.find(row => row.id === candidateId);
      return candidate?._authoritativeScenarioQueued === true
        && Object.prototype.hasOwnProperty.call(window.pinnedScen, candidateId);
    }, CANDIDATE_ID, { timeout: 3000 });
    const queued = await page.evaluate(candidateId => ({
      pinned: window.pinnedScen[candidateId],
      mutationPending: window.DeskBackend.state().mutationPending,
      conditionedCalls: window.DeskBackend.state().animation ? 1 : 0,
      pathStatus: document.querySelector('.evsimstage')?.textContent.replace(/\s+/g, ' ').trim()
    }), CANDIDATE_ID);
    assert.equal(queued.pinned, 5,
      'the user-selected hypothesis remains visibly pinned during the exact preview');
    assert.equal(queued.mutationPending, true);
    assert.equal(queued.conditionedCalls, 0,
      'no scenario request races the still-owned Plan mutation');
    assert.match(queued.pathStatus, /market story selected.*finishing this idea.*finding matching paths/i,
      'the path panel explains the queued lifecycle instead of appearing inert');

    await page.waitForFunction(() => window.DeskBackend.state().animation?.testMarker === 'second',
      null, { timeout: 10000 });
    const completed = await page.evaluate(candidateId => {
      const candidate = window.decide.cands.find(row => row.id === candidateId);
      const focus = candidate.authoritativeAnimation?.paths?.paths
        ?.find(row => row.role === 'FOCUS');
      const targetStep = Number(window.decide._mc?.targetStep || 0);
      const visibleFocusPrices = (focus?.prices || []).slice(0, targetStep + 1);
      const deltas = visibleFocusPrices.slice(1)
        .map((price, index) => Number(price) - Number(visibleFocusPrices[index]));
      let directionChanges = 0;
      for (let index = 1; index < deltas.length; index += 1) {
        if (Math.sign(deltas[index]) !== Math.sign(deltas[index - 1])) directionChanges += 1;
      }
      return {
        mutationPending: window.DeskBackend.state().mutationPending,
        queued: candidate._authoritativeScenarioQueued,
        marker: candidate.authoritativeAnimation?.testMarker,
        pathCount: window.decide._mc?.paths?.length,
        visibleFocusPrices,
        directionChanges,
        targetStep
      };
    }, CANDIDATE_ID);
    assert.equal(completed.mutationPending, false);
    assert.equal(completed.queued, false);
    assert.equal(completed.marker, 'second');
    assert.ok(completed.pathCount > 1,
      'the queued selection resolves to its backend path neighborhood without another click');
    assert.deepEqual(completed.visibleFocusPrices, [100, 101.8, 101.1, 103.4, 102.7, 105]);
    assert.equal(completed.targetStep, 5);
    assert.ok(completed.directionChanges >= 4,
      'the conditioned focus keeps server-selected session noise instead of becoming a straight endpoint ray');
    assert.equal(backend.scenarioCalls(), 1,
      'one early click becomes exactly one conditioned-path request after readiness');
    const request = backend.requests.find(row => row.method === 'POST'
      && row.path === `/api/plans/${PLAN_ID}/outcomes/ensemble/paths`);
    assert.equal(request.body.ensembleId, ENSEMBLE_ID);
    assert.ok(Array.isArray(request.body.waypoints) && request.body.waypoints.length > 0);
    assert.equal(Object.hasOwn(request.body, 'paths'), false,
      'the queued browser intent still supplies constraints only, never trajectories');
    assert.deepEqual(pageErrors, [], `queued scenario lifecycle emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a rejected conditioned-path request stays unavailable until an explicit retry succeeds', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({ scenarioFailures: 1 });
  try {
    const ensembleBuilds = backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`);
    await page.locator('#decideStage .srow[data-si="5"]').click();
    await page.waitForSelector('[data-conditioned-paths="unavailable"]', { timeout: 10000 });

    const rejected = await page.evaluate(candidateId => {
      const candidate = window.decide.cands.find(row => row.id === candidateId);
      return {
        pinned: window.pinnedScen[candidateId],
        error: candidate._authoritativeScenarioError,
        localAnimation: window.decide.animation,
        visibleFan: window.decide._mc,
        genericFanSvg: !!document.querySelector('#decideStage #mcFan'),
        errorText: document.querySelector('[data-conditioned-paths="unavailable"]')?.textContent
          .replace(/\s+/g, ' ').trim(),
        scenarioRead: document.querySelector('#decideStage .srow-ctl .wf-main')?.textContent
          .replace(/\s+/g, ' ').trim(),
        scenarioHint: document.querySelector('#decideStage .scenpanel .lenshd .hint')?.textContent.trim()
      };
    }, CANDIDATE_ID);
    assert.equal(rejected.pinned, 5, 'the rejected hypothesis remains selected for retry');
    assert.match(rejected.error, /stored ensemble could not be conditioned/i);
    assert.equal(rejected.localAnimation, null);
    assert.equal(rejected.visibleFan, null,
      'the unconditioned ensemble is not substituted after the selected projection fails');
    assert.equal(rejected.genericFanSvg, false);
    assert.match(rejected.errorText, /market story is temporarily unavailable/i);
    assert.match(rejected.errorText, /matching paths could not be loaded/i);
    assert.match(rejected.errorText, /general market fan stays hidden/i);
    assert.match(rejected.errorText, /try this story again/i);
    assert.match(rejected.scenarioRead, /market story unavailable/i);
    assert.match(rejected.scenarioHint, /scenario unavailable/i);
    assert.equal(backend.scenarioCalls(), 1);

    await page.getByRole('button', { name: 'Try this story again' }).click();
    await page.waitForFunction(() => window.DeskBackend.state().animation?.testMarker === 'second'
      && document.querySelector('#decideStage #mcFan'), null, { timeout: 10000 });
    const recovered = await page.evaluate(candidateId => {
      const candidate = window.decide.cands.find(row => row.id === candidateId);
      return {
        error: candidate._authoritativeScenarioError,
        bridgeError: window.DeskBackend.state().error,
        marker: candidate.authoritativeAnimation?.testMarker,
        pathCount: window.decide._mc?.paths?.length,
        valuationFingerprint: window.decide._mc?.valuationFingerprint,
        errorPanel: !!document.querySelector('[data-conditioned-paths="unavailable"]'),
        scenarioHint: document.querySelector('#decideStage .scenpanel .lenshd .hint')?.textContent.trim()
      };
    }, CANDIDATE_ID);
    assert.equal(recovered.error, null);
    assert.equal(recovered.bridgeError, null,
      'a successful retry clears the bridge error as well as the candidate-local error');
    assert.equal(recovered.marker, 'second');
    assert.ok(recovered.pathCount > 1);
    assert.ok(recovered.valuationFingerprint,
      'the retry displays the server-valued conditioned projection, not the generic fan');
    assert.equal(recovered.errorPanel, false);
    assert.match(recovered.scenarioHint, /simulated paths.*selected story playing through the desk/i);
    assert.equal(backend.scenarioCalls(), 2);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), ensembleBuilds,
      'retry conditions the existing ensemble instead of generating a replacement');
    assert.deepEqual(pageErrors, [], `conditioned-path rejection emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('multi-day New Idea scenarios validate the authored session receipt without rejecting derived path pins', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    const requestedWaypoints = [
      { dayIndex: 4, priceRatio: 0.985, tolerance: 0.015 },
      { dayIndex: 10, priceRatio: 1.05, tolerance: 0.02 }
    ];
    const result = await page.evaluate(async waypoints => {
      const response = await window.DeskBackend.scenarioAnimation({ waypoints, days: 10 });
      const state = window.DeskBackend.state();
      return {
        returnedWaypoints: response.receipt.conditioningAssumptions.waypoints,
        returnedPathWaypoints: response.receipt.conditioningPathWaypoints,
        activeMarker: state.animation && state.animation.testMarker,
        bridgeError: state.error && state.error.message
      };
    }, requestedWaypoints);

    assert.deepEqual(result.returnedWaypoints, requestedWaypoints,
      'the controller echoes the authored day-level constraints as the conditioning assumptions');
    assert.deepEqual(result.returnedPathWaypoints, requestedWaypoints.map(pin => ({
      sessionProgress: pin.dayIndex,
      priceRatio: pin.priceRatio,
      tolerance: pin.tolerance
    })), 'the controller may also disclose normalized path pins derived from those day constraints');
    assert.equal(result.activeMarker, 'second',
      'a valid multi-day receipt becomes the active authoritative animation');
    assert.equal(result.bridgeError, null);

    const requests = backend.requests.filter(row => row.method === 'POST'
      && row.path === `/api/plans/${PLAN_ID}/outcomes/ensemble/paths`);
    assert.equal(requests.length, 1);
    assert.deepEqual(requests[0].body, {
      ensembleId: ENSEMBLE_ID,
      limit: 48,
      waypoints: requestedWaypoints
    });
    assert.equal(Object.hasOwn(requests[0].body, 'pathWaypoints'), false,
      'derived path pins remain a response receipt, not a second browser conditioning owner');
    assert.deepEqual(pageErrors, [], `multi-day scenario receipt emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('limit re-preview preserves the ensemble and stale scenario responses cannot replace current truth', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    const ensemblePath = `/api/plans/${PLAN_ID}/outcomes/ensemble`;
    const outcomePath = `/api/plans/${PLAN_ID}/outcomes/run`;
    const pathsPath = `/api/plans/${PLAN_ID}/outcomes/ensemble/paths`;
    const ensembleBefore = backend.count('POST', ensemblePath);
    const outcomeBefore = backend.count('POST', outcomePath);

    const candidateBefore = await page.evaluate(candidateId => {
      const row = window.decide.cands.find(candidate => candidate.id === candidateId);
      return {
        payoffPoints: row.payoffPoints,
        net: row.net,
        maxLoss: row.maxLoss,
        maxProfit: row.maxProfit,
        pop: row.pop
      };
    }, CANDIDATE_ID);

    await page.evaluate(() => window.DeskBackend.repreviewOrder({
      type: 'LIMIT', timeInForce: 'DAY', qty: 1, limitNetCents: -12345
    }));

    const previews = backend.requests.filter(row =>
      row.method === 'POST' && row.path === `/api/plans/${PLAN_ID}/decision/preview`);
    const limitPreview = previews[previews.length - 1];
    assert.deepEqual(limitPreview.body.orderInstruction, {
      type: 'LIMIT',
      timeInForce: 'DAY',
      limitNetCents: -12345
    }, 'a debit limit remains a signed whole-cent nested instruction');
    assert.equal(backend.count('POST', ensemblePath), ensembleBefore,
      'execution-instruction edits do not regenerate the market ensemble');
    assert.equal(backend.count('POST', outcomePath), outcomeBefore,
      'execution-instruction edits do not rerun the market distribution');
    const candidateAfter = await page.evaluate(candidateId => {
      const row = window.decide.cands.find(candidate => candidate.id === candidateId);
      return {
        payoffPoints: row.payoffPoints,
        net: row.net,
        maxLoss: row.maxLoss,
        maxProfit: row.maxProfit,
        pop: row.pop,
        instruction: window.decide.orderPreview.order.orderInstruction.type,
        deskPickId: window.decide.deskPickId,
        pickBadges: document.querySelectorAll('.fanr .pickbadge').length
      };
    }, CANDIDATE_ID);
    const economicsAfter = Object.assign({}, candidateAfter);
    delete economicsAfter.instruction;
    delete economicsAfter.deskPickId;
    delete economicsAfter.pickBadges;
    assert.deepEqual(economicsAfter, candidateBefore,
      'LIMIT preview stays in the execution dock and cannot replace candidate/outcome economics');
    assert.equal(candidateAfter.instruction, 'LIMIT');
    assert.equal(candidateAfter.deskPickId, CANDIDATE_ID,
      'a resting limit changes execution timing without demoting the analytical Desk Pick');
    assert.equal(candidateAfter.pickBadges, 1);

    const race = await page.evaluate(async () => {
      const first = window.DeskBackend.scenarioAnimation({ movePct: -5, days: 10 });
      const second = window.DeskBackend.scenarioAnimation({ movePct: 5, days: 10 });
      const values = await Promise.all([first, second]);
      return {
        first: values[0],
        secondMarker: values[1] && values[1].testMarker,
        activeMarker: window.DeskBackend.state().animation.testMarker,
        activeEnsemble: window.DeskBackend.state().animation.ensemble.id
      };
    });
    assert.equal(race.first, null, 'the superseded scenario response is ignored');
    assert.equal(race.secondMarker, 'second');
    assert.equal(race.activeMarker, 'second', 'latest scenario response owns animation state');
    assert.equal(race.activeEnsemble, ENSEMBLE_ID);
    const synchronizedMidpoint = await page.evaluate(candidateId => {
      const active = window.decide.cands.find(candidate => candidate.id === candidateId);
      return window.authoritativeFrame(active, 0.5);
    }, CANDIDATE_ID);
    assert.equal(synchronizedMidpoint.price, 103,
      'the animated underlying follows the server-valued intraday focus checkpoint');
    assert.equal(synchronizedMidpoint.pnl, 50,
      'payoff and risk readouts follow the same server-valued intraday checkpoint');

    const conditioned = backend.requests.filter(row => row.method === 'POST' && row.path === pathsPath);
    assert.equal(conditioned.length, 2);
    conditioned.forEach(row => {
      assert.equal(row.body.ensembleId, ENSEMBLE_ID,
        'every conditioned projection selects from the active immutable ensemble');
      assert.ok(Array.isArray(row.body.waypoints) && row.body.waypoints.length,
        'the browser sends scenario hypotheses as selection constraints, not generated paths');
      assert.equal(Object.hasOwn(row.body, 'paths'), false,
        'the browser never supplies financial trajectories');
    });

    const invalid = await page.evaluate(async () => {
      try {
        await window.DeskBackend.scenarioAnimation({ movePct: 9, days: 10 });
        return { rejected: false };
      } catch (error) {
        return {
          rejected: true,
          message: error.message,
          activeMarker: window.DeskBackend.state().animation.testMarker
        };
      }
    });
    assert.equal(invalid.rejected, true);
    assert.match(invalid.message, /did not retain the active Plan, candidate, ensemble, and valuation identity/);
    assert.equal(invalid.activeMarker, 'second',
      'an identity-invalid response cannot replace the last authoritative animation');
    assert.equal(backend.scenarioCalls(), 3);
    assert.deepEqual(pageErrors, [], `bridge contract emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('exact-package drafts are previewed and selected by the backend on the existing ensemble', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    latestEnabled: true,
    draftPreviewDelayMs: 450
  });
  try {
    const ensemblePath = `/api/plans/${PLAN_ID}/outcomes/ensemble`;
    const outcomePath = `/api/plans/${PLAN_ID}/outcomes/run`;
    const customPath = `/api/plans/${PLAN_ID}/strategy/custom`;
    const ensembleBefore = backend.count('POST', ensemblePath);
    const outcomeBefore = backend.count('POST', outcomePath);

    const baseline = await page.evaluate(async () => {
      await window.DeskBackend.scenarioAnimation({ movePct: 5, days: 10 });
      const visible = window.activeCand();
      const scenario = window.scenData(visible);
      window.__draftBaselineMc = window.decide._mc;
      window.__draftBaselineScenario = document.querySelector('#decideStage .scenpanel');
      return {
        activeId: visible.id,
        activePayoffAtSpot: window.payFor(visible, 100),
        frame: window.authoritativeFrame(visible, 0.5),
        scenarioPnl: scenario.rows.map(row => row.pl),
        animationMarker: window.DeskBackend.state().animation.testMarker
      };
    });

    await page.locator('#decideStage .declegpanel [data-dec="editlegs"]').click();
    await page.locator('#decideStage .declegpanel [data-leg="rm"][data-li="1"]')
      .evaluate(node => node.click());
    await page.waitForFunction(() => window.decide.draftPending
      && window.decide.buildLegs?.length === 1);

    const pending = await page.evaluate(() => {
      const visible = window.activeCand();
      return {
        activeId: visible.id,
        activePayoffAtSpot: window.payFor(visible, 100),
        frame: window.authoritativeFrame(visible, 0.5),
        scenarioPnl: window.scenData(visible).rows.map(row => row.pl),
        sameMc: window.decide._mc === window.__draftBaselineMc,
        sameScenarioNode: document.querySelector('#decideStage .scenpanel')
          === window.__draftBaselineScenario,
        animationMarker: window.DeskBackend.state().animation.testMarker,
        workbenchLegs: document.querySelectorAll('#decideStage .declegpanel .legr').length,
        payoffTitle: document.querySelector('#decideStage .dccenter .paytitle')?.textContent
          .replace(/\s+/g, ' ').trim()
      };
    });

    assert.equal(pending.activeId, CANDIDATE_ID);
    assert.equal(pending.activePayoffAtSpot, baseline.activePayoffAtSpot);
    assert.deepEqual(pending.frame, baseline.frame,
      'pending draft edits preserve the selected conditioned valuation checkpoints');
    assert.deepEqual(pending.scenarioPnl, baseline.scenarioPnl,
      'pending draft edits cannot flash browser-generated scenario values');
    assert.equal(pending.sameMc, true,
      'pending draft edits preserve the paths paired with those checkpoints');
    assert.equal(pending.sameScenarioNode, true,
      'pending draft edits preserve scenario DOM identity and playback state');
    assert.equal(pending.animationMarker, baseline.animationMarker);
    assert.equal(pending.workbenchLegs, 1,
      'the edited leg package remains visible while its preview is pending');
    assert.match(pending.payoffTitle, /draft repricing.*selected package remains shown until applied/i);

    await page.waitForFunction(() => !window.decide.draftPending
      && /one-leg draft is blocked/i.test(window.decide.draftError || ''));
    const invalid = await page.evaluate(() => {
      const state = window.DeskBackend.state();
      const visible = window.activeCand();
      return {
        selectedId: state.selected.id,
        visibleSelectedId: window.decide.candId,
        activeId: visible.id,
        activePayoffAtSpot: window.payFor(visible, 100),
        payoffTitle: document.querySelector('#decideStage .dccenter .paytitle')?.textContent.replace(/\s+/g, ' ').trim(),
        workbenchLegs: document.querySelectorAll('#decideStage .declegpanel .legr').length,
        workbenchText: document.querySelector('#decideStage .declegpanel')?.textContent.replace(/\s+/g, ' ').trim(),
        frame: window.authoritativeFrame(visible, 0.5),
        scenarioPnl: window.scenData(visible).rows.map(row => row.pl),
        sameMc: window.decide._mc === window.__draftBaselineMc,
        animationMarker: state.animation.testMarker,
        valid: state.draft.valid,
        error: state.draft.error,
        ensembleId: state.ensemble.ensemble.id
      };
    });

    assert.equal(invalid.selectedId, CANDIDATE_ID,
      'a blocked analysis cannot replace the selected recommendation');
    assert.equal(invalid.visibleSelectedId, CANDIDATE_ID,
      'the prior candidate remains visible while an invalid draft is explained');
    assert.equal(invalid.activeId, CANDIDATE_ID,
      'an invalid draft cannot replace the selected candidate financial surface');
    assert.equal(invalid.activePayoffAtSpot, 777,
      'the selected backend payoff remains visible instead of a browser-calculated zero shell');
    assert.match(invalid.payoffTitle, /Backend debit call spread.*selected package remains shown until applied/i);
    assert.equal(invalid.workbenchLegs, 1,
      'the edited draft legs remain visible independently from the selected payoff');
    assert.match(invalid.workbenchText, /one-leg draft is blocked/i);
    assert.deepEqual(invalid.frame, baseline.frame,
      'a blocked draft leaves the selected conditioned valuation intact');
    assert.deepEqual(invalid.scenarioPnl, baseline.scenarioPnl,
      'a blocked draft leaves the selected scenario checkpoints intact');
    assert.equal(invalid.sameMc, true,
      'a blocked draft leaves its matching conditioned paths intact');
    assert.equal(invalid.animationMarker, baseline.animationMarker);
    assert.equal(invalid.valid, false);
    assert.match(invalid.error, /one-leg draft is blocked/i);
    assert.equal(invalid.ensembleId, ENSEMBLE_ID);
    assert.equal(backend.count('POST', customPath), 0,
      'invalid pure previews never invoke the mutating custom-selection route');

    const decisionPath = `/api/plans/${PLAN_ID}/decision/preview`;
    const decisionBeforeCancel = backend.count('POST', decisionPath);
    await page.locator('#decideStage .declegpanel [data-dec="canceldraft"]').click();
    await page.waitForFunction(() => window.decide.mode === 'engine'
      && window.decide.orderPreview && !window.decide.order.previewPending);
    const restored = await page.evaluate(() => ({
      activeId: window.activeCand().id,
      reviewDisabled: document.querySelector('#decideStage .execute [data-dec="review"]')?.disabled,
      animationMarker: window.DeskBackend.state().animation.testMarker
    }));
    assert.equal(restored.activeId, CANDIDATE_ID);
    assert.equal(restored.reviewDisabled, false,
      'canceling a draft restores an actionable backend order preview');
    assert.equal(restored.animationMarker, baseline.animationMarker);
    assert.equal(backend.count('POST', decisionPath), decisionBeforeCancel + 1,
      'canceling a draft explicitly reprices the restored selected instruction');

    const invalidRequest = backend.requests.filter(row =>
      row.method === 'POST' && row.path === '/api/trades/preview').at(-1);
    assert.equal(invalidRequest.body.strategy, 'CUSTOM');
    assert.equal(invalidRequest.body.qty, 1);
    assert.equal(invalidRequest.body.riskMode, 'balanced');
    assert.equal(invalidRequest.body.fillNature, 'PROPOSED');
    assert.equal(invalidRequest.body.source, 'BUILDER');
    assert.equal(invalidRequest.body.proposedNetCents, null,
      'the browser cannot constrain the backend package price');
    assert.deepEqual(invalidRequest.body.legs[0], {
      action: 'BUY',
      type: 'CALL',
      strike: 100,
      expiration: '2026-08-21',
      ratio: 1,
      multiplier: 100,
      positionEffect: 'OPEN',
      entryPrice: null
    }, 'draft legs retain the exact backend contract and request fresh executable pricing');

    const draft = await page.evaluate(async candidateId => {
      const result = await window.DeskBackend.previewDraft([
        { t: 'c', k: 100, q: 1, expiration: '2026-08-21', multiplier: 100 },
        { t: 'c', k: 105, q: -1, expiration: '2026-08-21', multiplier: 100 }
      ], candidateId);
      const state = window.DeskBackend.state();
      return {
        valid: result.valid,
        selectedId: state.selected.id,
        ensembleId: state.ensemble.ensemble.id,
        payoffPoints: result.candidate.payoffPoints,
        sourceKind: result.candidate.backend.sourceKind,
        identity: result.candidate.positionIdentity
      };
    }, CANDIDATE_ID);

    assert.equal(draft.valid, true);
    assert.equal(draft.selectedId, CANDIDATE_ID,
      'a successful preview is still pure until the user explicitly applies it');
    assert.equal(draft.ensembleId, ENSEMBLE_ID);
    assert.equal(draft.sourceKind, 'EXACT_BACKEND_PREVIEW');
    assert.deepEqual(draft.identity, positionIdentity());
    assert.deepEqual(draft.payoffPoints, [
      { price: 90, profit: -310 },
      { price: 100, profit: 4242 },
      { price: 110, profit: 690 }
    ], 'draft payoff points are the server sentinel, not a browser leg calculation');

    const validRequest = backend.requests.filter(row =>
      row.method === 'POST' && row.path === '/api/trades/preview').at(-1);
    assert.deepEqual(validRequest.body.legs.map(leg => ({
      action: leg.action,
      type: leg.type,
      strike: leg.strike,
      ratio: leg.ratio,
      positionEffect: leg.positionEffect,
      entryPrice: leg.entryPrice
    })), [
      { action: 'BUY', type: 'CALL', strike: 100, ratio: 1, positionEffect: 'OPEN', entryPrice: null },
      { action: 'SELL', type: 'CALL', strike: 105, ratio: 1, positionEffect: 'OPEN', entryPrice: null }
    ]);

    const applied = await page.evaluate(async () => {
      const result = await window.DeskBackend.useDraft();
      return {
        selectedId: result.selected.id,
        selectedIdentity: result.selected.positionIdentity,
        ensembleId: result.ensemble.ensemble.id,
        ensembleFingerprint: result.ensemble.ensemble.fingerprint,
        decisionSelectedId: result.decisionPreview.selected.id,
        draft: result.draft
      };
    });

    assert.equal(applied.selectedId, CUSTOM_CANDIDATE_ID);
    assert.deepEqual(applied.selectedIdentity, positionIdentity());
    assert.equal(applied.ensembleId, ENSEMBLE_ID);
    assert.equal(applied.ensembleFingerprint, ENSEMBLE_FINGERPRINT);
    assert.equal(applied.decisionSelectedId, CUSTOM_CANDIDATE_ID,
      'the next order preview is bound to the newly selected exact package');
    assert.equal(applied.draft, null);
    assert.equal(backend.count('POST', customPath), 1,
      'only explicit apply mutates the Plan strategy selection');
    assert.equal(backend.count('POST', ensemblePath), ensembleBefore,
      'applying an exact package reuses the immutable market ensemble');
    assert.equal(backend.count('POST', outcomePath), outcomeBefore + 1,
      'the exact package receives a fresh valuation on that same ensemble');

    const customRequest = backend.requests.find(row =>
      row.method === 'POST' && row.path === customPath);
    assert.deepEqual(customRequest.body.position, validRequest.body,
      'selection applies the exact package that was purely previewed');
    const rerun = backend.requests.filter(row =>
      row.method === 'POST' && row.path === outcomePath).at(-1);
    assert.equal(rerun.body.ensembleId, ENSEMBLE_ID);
    assert.equal(backend.count('POST', '/api/strategies/identify'), 0,
      'preview and custom-selection receipts retain canonical strategy identity');

    const reopened = await page.evaluate(async () => {
      const context = Object.assign({}, window.DeskBackend.state().context);
      const state = await window.DeskBackend.openIdea(context);
      return {
        selectedId: state.selected.id,
        visibleIds: window.decide.cands.map(candidate => candidate.id),
        bridgeIds: state.candidates.map(candidate => candidate.id)
      };
    });
    assert.equal(reopened.selectedId, CUSTOM_CANDIDATE_ID,
      'a server-current exact package survives reopening without tab-local cache state');
    assert.ok(reopened.visibleIds.includes(CUSTOM_CANDIDATE_ID));
    assert.ok(reopened.bridgeIds.includes(CUSTOM_CANDIDATE_ID));
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/run`), 1,
      'an exact strategy run receipt reuses the current competition and independent selection');

    const refreshed = await page.evaluate(async () => {
      const context = Object.assign({}, window.DeskBackend.state().context);
      window.sessionStorage.clear();
      const state = await window.DeskBackend.openIdea(context);
      return {
        selectedId: state.selected.id,
        visibleIds: window.decide.cands.map(candidate => candidate.id),
        bridgeIds: state.candidates.map(candidate => candidate.id)
      };
    });
    assert.equal(refreshed.selectedId, CUSTOM_CANDIDATE_ID,
      'a required competition refresh retains the server-current exact package');
    assert.ok(refreshed.visibleIds.includes(CUSTOM_CANDIDATE_ID));
    assert.ok(refreshed.bridgeIds.includes(CUSTOM_CANDIDATE_ID));
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/run`), 2,
      'missing tab-local market identity refreshes the competition without overwriting the exact selection');
    assert.deepEqual(pageErrors, [], `exact-package bridge emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('unknown risk remains explicit and backend blocks cannot enter review or commitment', async () => {
  const unclassified = candidate();
  unclassified.identity = null;
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    strategyCandidates: [unclassified],
    blockDecisionPreview: true
  });
  try {
    const rendered = await page.evaluate(() => ({
      risk: document.querySelector('.fanr.sel .rchip')?.textContent.trim(),
      riskTitle: document.querySelector('.fanr.sel .fanicon')?.getAttribute('title'),
      reviewDisabled: document.querySelector('[data-dec="review"]')?.disabled,
      dock: document.querySelector('.execute')?.textContent,
      deskPickId: window.decide.deskPickId,
      pickBadges: document.querySelectorAll('.fanr .pickbadge').length
    }));
    assert.equal(rendered.risk, 'unknown');
    assert.match(rendered.riskTitle, /classification unavailable/i);
    assert.equal(rendered.reviewDisabled, true);
    assert.match(rendered.dock, /guardrails block this exact package/i,
      'execution pricing and placement guardrails remain separately visible');
    assert.match(rendered.dock, /exceeds the backend loss limit/i);
    assert.equal(rendered.deskPickId, null,
      'a final exact-package guardrail block removes the Desk Pick endorsement');
    assert.equal(rendered.pickBadges, 0);

    const guard = await page.evaluate(() => {
      window.decAction('review');
      return { review: window.decide.order.review, error: window.decide.backendError };
    });
    assert.equal(guard.review, false);
    assert.match(guard.error, /exceeds the backend loss limit/i);
    assert.deepEqual(pageErrors, [], `eligibility rendering emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a committed Practice trade is reconciled into Book without a page reload', async () => {
  const bookDocuments = emptyBookDocuments();
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    bookDocuments,
    commitAddsTrade: true
  });
  try {
    const navigationCount = await page.evaluate(() => performance.getEntriesByType('navigation').length);
    await page.evaluate(() => {
      window.__commitPhases = [];
      document.addEventListener('strikebench:desk-backend', event => {
        if (String(event.detail?.phase || '').startsWith('commit')
            || event.detail?.phase === 'committed') {
          window.__commitPhases.push(event.detail.phase);
        }
      });
      window.__commitReceipt = window.DeskBackend.commitOrder(
        { type: 'MARKET', timeInForce: 'DAY', qty: 1 }, []);
    });
    await page.waitForSelector('#stage[data-book-authority="ready"] '
      + '#book .card[data-id="trade-desk-test"]', { timeout: 10000 });
    await page.waitForFunction(() => window.state?.level === 'book'
      && !document.querySelector('#decideStage')?.classList.contains('on'),
    null, { timeout: 10000 });

    const reconciled = await page.evaluate(async () => {
      const receipt = await window.__commitReceipt;
      const book = window.DeskBackend.state().book;
      const card = document.querySelector('#book .card[data-id="trade-desk-test"]');
      return {
        receiptTradeId: receipt.trade.id,
        activeIds: book.data.activeTrades.map(trade => trade.id),
        recentTradeId: book.data.recentTradeId,
        phases: window.__commitPhases,
        cardText: card.textContent.replace(/\s+/g, ' ').trim(),
        highlighted: card.classList.contains('justopened'),
        navigationCount: performance.getEntriesByType('navigation').length
      };
    });
    assert.equal(reconciled.receiptTradeId, 'trade-desk-test');
    assert.deepEqual(reconciled.activeIds, ['trade-desk-test']);
    assert.equal(reconciled.recentTradeId, 'trade-desk-test',
      'the reconciled roster owns the just-opened highlight receipt');
    assert.deepEqual(reconciled.phases, ['committed', 'commit-reconciled']);
    assert.match(reconciled.cardText, /AMD/i);
    assert.match(reconciled.cardText, /opened just now/i);
    assert.equal(reconciled.highlighted, true);
    assert.equal(reconciled.navigationCount, navigationCount,
      'the new package appears through authoritative Book invalidation, not a document reload');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/trade`), 1);
    assert.equal(backend.count('GET', '/api/trades'), 2,
      'Book is re-read once after the initial Home load and successful commitment');
    assert.deepEqual(pageErrors, [], `commit reconciliation emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('order commitment serializes Plan mutations and clears a superseded governor refresh', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({ commitDelayMs: 320 });
  try {
    const result = await page.evaluate(async candidateId => {
      const commit = window.DeskBackend.commitOrder({ type: 'MARKET', timeInForce: 'DAY', qty: 1 }, []);
      await new Promise(resolve => setTimeout(resolve, 20));
      let competingError = null;
      try { await window.DeskBackend.chooseCandidate(candidateId); }
      catch (error) { competingError = error.message; }
      window.decide.govs.risk = 6666;
      window.decide.govExplicit.risk = true;
      const control = document.createElement('input');
      control.setAttribute('data-gov', 'risk');
      document.body.appendChild(control);
      control.dispatchEvent(new Event('change', { bubbles: true }));
      control.remove();
      const pendingDuring = window.DeskBackend.state().mutationPending;
      const committed = await commit;
      return {
        competingError,
        pendingDuring,
        pendingAfter: window.DeskBackend.state().mutationPending,
        tradeId: committed.trade.id
      };
    }, CANDIDATE_ID);
    assert.match(result.competingError, /current Plan change/);
    assert.equal(result.pendingDuring, true);
    assert.equal(result.pendingAfter, false);
    assert.equal(result.tradeId, 'trade-desk-test');
    await new Promise(resolve => setTimeout(resolve, 800));
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/trade`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/run`), 1,
      'a cap change for the committed idea cannot replay after the journey leaves Decide');
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 1,
      'no second selection request can race a pending commit');
    assert.deepEqual(pageErrors, [], `serialized commitment emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a detached order completion cannot close the replacement idea', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({ commitDelayMs: 320 });
  try {
    await page.evaluate(() => {
      window.__detachedCommit = window.DeskBackend.commitOrder(
        { type: 'MARKET', timeInForce: 'DAY', qty: 1 }, []);
    });
    for (let attempt = 0; attempt < 100
      && backend.count('POST', `/api/plans/${PLAN_ID}/decision/trade`) < 1; attempt += 1) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/trade`), 1,
      'the backend commitment is in flight');

    await page.locator('[data-dec="back"]').click();
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(id => {
      const state = window.DeskBackend.state();
      return window.decide && window.decide.backendPhase === 'ready'
        && state.selected && state.selected.id === id
        && !state.mutationPending;
    }, CANDIDATE_ID, { timeout: 10000 });
    const committed = await page.evaluate(() => window.__detachedCommit);
    assert.equal(committed.trade.id, 'trade-desk-test');

    await new Promise(resolve => setTimeout(resolve, 800));
    const replacement = await page.evaluate(() => ({
      open: !!window.decide,
      phase: window.decide && window.decide.backendPhase,
      selected: window.DeskBackend.state().selected && window.DeskBackend.state().selected.id
    }));
    assert.deepEqual(replacement, { open: true, phase: 'ready', selected: CANDIDATE_ID },
      'the old commitment receipt cannot schedule exit against the replacement journey');
    assert.deepEqual(pageErrors, [], `detached commitment emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('canceling exact-package application cannot publish draft state into the replacement idea', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({ customDelayMs: 320 });
  try {
    await page.evaluate(async candidateId => {
      await window.DeskBackend.previewDraft([
        { t: 'c', k: 100, q: 1, expiration: '2026-08-21', multiplier: 100 },
        { t: 'c', k: 105, q: -1, expiration: '2026-08-21', multiplier: 100 }
      ], candidateId);
      window.__detachedDraft = window.DeskBackend.useDraft();
    }, CANDIDATE_ID);
    for (let attempt = 0; attempt < 100
      && backend.count('POST', `/api/plans/${PLAN_ID}/strategy/custom`) < 1; attempt += 1) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/custom`), 1,
      'the exact-package mutation is in flight');
    const outcomeBefore = backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`);

    await page.locator('[data-dec="back"]').click();
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(id => {
      const state = window.DeskBackend.state();
      return window.decide && window.decide.backendPhase === 'ready'
        && state.selected && state.selected.id === id
        && state.outcome && state.outcome.outcome
        && !state.mutationPending;
    }, CUSTOM_CANDIDATE_ID, { timeout: 10000 });
    const canceledResult = await page.evaluate(() => window.__detachedDraft);
    assert.equal(canceledResult, null);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), outcomeBefore + 1,
      'only the replacement journey values the now-canonical custom package');
    assert.equal(await page.locator('.authpending').count(), 0);
    assert.deepEqual(pageErrors, [], `detached draft application emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('rapid candidate clicks keep the visible package aligned with the accepted backend selection', async () => {
  const adjacent = incoherentCandidate();
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    strategyCandidates: [candidate(), adjacent],
    selectDelayMs: 180
  });
  try {
    await page.locator(`.fanr[data-cand="${adjacent.id}"]`).click();
    await page.getByRole('button', { name: /^Crash -20% path/ }).click();
    await page.locator(`.fanr[data-cand="${CANDIDATE_ID}"]`).click();
    await page.waitForFunction(id => {
      const state = window.DeskBackend.state();
      return window.decide.backendPhase === 'ready'
        && window.decide.candId === id
        && state.selected && state.selected.id === id
        && state.outcome && state.outcome.outcome.candidateId === id
        && window.decide.orderPreview && window.decide.orderPreview.selected.id === id;
    }, adjacent.id, { timeout: 10000 });

    const aligned = await page.evaluate(() => ({
      visible: window.decide.candId,
      selected: window.DeskBackend.state().selected.id,
      outcome: window.DeskBackend.state().outcome.outcome.candidateId,
      preview: window.decide.orderPreview.selected.id,
      pending: window.DeskBackend.state().mutationPending
    }));
    assert.deepEqual(aligned, {
      visible: adjacent.id,
      selected: adjacent.id,
      outcome: adjacent.id,
      preview: adjacent.id,
      pending: false
    });
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 2,
      'the ignored second click does not launch a competing selection mutation');
    assert.equal(backend.scenarioCalls(), 0,
      'a scenario pin cannot race the in-flight candidate selection');
    assert.equal(await page.locator('.ensembleresult').count(), 1,
      'the stored fan remains visible instead of getting stranded on a loading state');
    assert.deepEqual(pageErrors, [], `candidate click serialization emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('the latest governor change runs after an in-flight selection instead of being dropped', async () => {
  const adjacent = incoherentCandidate();
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    strategyCandidates: [candidate(), adjacent],
    selectDelayMs: 220
  });
  try {
    await page.evaluate(candidateId => {
      window.DeskBackend.chooseCandidate(candidateId).catch(() => {});
      window.decide.govs.risk = 7777;
      window.decide.govExplicit.risk = true;
      const control = document.createElement('input');
      control.setAttribute('data-gov', 'risk');
      document.body.appendChild(control);
      control.dispatchEvent(new Event('change', { bubbles: true }));
      control.remove();
    }, adjacent.id);
    await page.waitForFunction(() => {
      const state = window.DeskBackend.state();
      return window.decide.backendPhase === 'ready'
        && !state.mutationPending
        && state.context && state.context.governors
        && Number(state.context.governors.risk) === 7777;
    }, null, { timeout: 10000 });

    const state = await page.evaluate(() => ({
      visible: window.decide.candId,
      selected: window.DeskBackend.state().selected.id,
      outcome: window.DeskBackend.state().outcome.outcome.candidateId,
      risk: window.DeskBackend.state().context.governors.risk
    }));
    assert.equal(state.visible, state.selected);
    assert.equal(state.outcome, state.selected);
    assert.equal(state.risk, 7777);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/run`), 2,
      'the queued governor value refreshes the canonical competition after selection releases');
    assert.deepEqual(pageErrors, [], `queued governor refresh emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a governor refresh clears the prior package and every scenario pin before recomputation', async () => {
  const { context, page, pageErrors } = await openAuthoritativeDesk({ strategyRunDelayMs: 700 });
  try {
    await page.locator('#decideStage .srow[data-si="5"]').click();
    await page.waitForFunction(id => Object.prototype.hasOwnProperty.call(window.pinnedScen, id)
      && window.DeskBackend.state().animation, CANDIDATE_ID, { timeout: 10000 });
    await page.evaluate(() => {
      window.decide.govs.risk = 7777;
      window.decide.govExplicit.risk = true;
      const control = document.createElement('input');
      control.setAttribute('data-gov', 'risk');
      document.body.appendChild(control);
      control.dispatchEvent(new Event('change', { bubbles: true }));
      control.remove();
    });
    await page.waitForFunction(id => window.DeskBackend.state().context?.governors?.risk === 7777
      && window.DeskBackend.state().mutationPending
      && window.decide.cands.length === 0
      && !Object.prototype.hasOwnProperty.call(window.pinnedScen, id),
    CANDIDATE_ID, { timeout: 10000 });
    const invalidated = await page.evaluate(() => ({
      candidates: window.decide.cands.length,
      ensemble: window.decide.ensemble,
      outcome: window.decide.outcome,
      preview: window.decide.orderPreview,
      pins: Object.keys(window.pinnedScen)
    }));
    assert.deepEqual(invalidated, {
      candidates: 0, ensemble: null, outcome: null, preview: null, pins: []
    }, 'the previous exact financial artifact is absent throughout the refreshed competition');
    await page.waitForFunction(id => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().selected?.id === id
      && !window.DeskBackend.state().mutationPending,
    CANDIDATE_ID, { timeout: 10000 });
    assert.deepEqual(pageErrors, [], `governor refresh invalidation emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('the initial backend selection cannot be superseded by a second idea load', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { selectDelayMs: 180 });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    for (let attempt = 0; attempt < 100
      && backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`) < 1; attempt += 1) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 1,
      'the first Plan selection is in flight');

    const competing = await page.evaluate(async () => {
      try {
        await window.DeskBackend.openIdea(Object.assign({}, window.DeskBackend.state().context));
        return { blocked: false };
      } catch (error) {
        return { blocked: true, message: error.message };
      }
    });
    assert.equal(competing.blocked, true);
    assert.match(competing.message, /current Plan change/);
    await page.waitForFunction(id => window.decide
      && window.decide.backendPhase === 'ready'
      && window.decide.candId === id, CANDIDATE_ID, { timeout: 10000 });
    assert.equal((await page.evaluate(() => window.DeskBackend.state().mutationPending)), false,
      'the request-owned mutation gate releases after selection and downstream valuation finish');
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 1,
      'the blocked refresh cannot launch a second Plan mutation');
    assert.deepEqual(pageErrors, [], `initial selection serialization emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('New Idea keeps one source-aligned market fan and lets a selected future drive the desk', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    const initial = await page.evaluate(candidateId => {
      const candidate = window.decide.cands.find(row => row.id === candidateId);
      const price = window.decide._mc;
      const pnl = window.authoritativePnlFan(candidate);
      return {
        chartSpace: document.querySelector('#mcFan')?.getAttribute('data-path-space'),
        heading: document.querySelector('.ensembleresult .mchd')?.textContent
          .replace(/\s+/g, ' ').trim(),
        priceSources: price.sourcePathIndices,
        pnlSources: pnl.sourcePathIndices,
        priceFocus: price.focusPathIndex,
        pnlFocus: pnl.focusPathIndex,
        fingerprint: price.fingerprint,
        visiblePath: document.querySelector('#mcFan [data-mc-line="0"]')?.getAttribute('d')
      };
    }, CANDIDATE_ID);
    assert.equal(initial.chartSpace, 'pnl',
      'a selected package defaults to the useful P/L transform, not another unlabeled price fan');
    assert.match(initial.heading, /AMD.*500 price paths.*21 sessions.*one market fan/i);
    assert.deepEqual(initial.pnlSources, initial.priceSources,
      'price and package P/L paths retain the same backend source-row identities');
    assert.equal(initial.pnlFocus, initial.priceFocus,
      'the emphasized median row is identical in price and P/L space');
    assert.equal(initial.priceSources.length, 48);

    await page.locator('[data-dec="pathview"][data-path-view="price"]').click();
    await page.waitForSelector('#mcFan[data-path-space="price"]');
    const priceBefore = await page.evaluate(() => ({
      fingerprint: document.querySelector('#mcFan')?.getAttribute('data-ensemble-fingerprint'),
      path: document.querySelector('#mcFan [data-mc-line="0"]')?.getAttribute('d')
    }));
    await page.evaluate(() => window.renderDecide());
    await page.waitForSelector('#mcFan[data-path-space="price"]');
    const priceAfter = await page.evaluate(() => ({
      fingerprint: document.querySelector('#mcFan')?.getAttribute('data-ensemble-fingerprint'),
      path: document.querySelector('#mcFan [data-mc-line="0"]')?.getAttribute('d')
    }));
    assert.deepEqual(priceAfter, priceBefore,
      'unrelated rendering cannot reshuffle an immutable ensemble fingerprint');
    assert.equal(priceAfter.fingerprint, initial.fingerprint);

    await page.locator('#mcFan [data-mc-path="0"]').dispatchEvent('mouseover');
    const priceReadout = await page.locator('#mcPathReadout').textContent();
    assert.match(priceReadout, /\$.*%.*click to play .* across the desk/i,
      'hover explains the selected possible future and its whole-desk action');

    await page.locator('[data-dec="pathview"][data-path-view="pnl"]').click();
    await page.waitForSelector('#mcFan[data-path-space="pnl"]');
    await page.locator('#mcFan [data-mc-path="0"]').dispatchEvent('mouseover');
    const pnlReadout = await page.locator('#mcPathReadout').textContent();
    assert.match(pnlReadout, /Ends .*underlying \$.*click to play .* across the desk/i,
      'P/L hover pairs the package consequence with the same underlying source path');

    await page.waitForTimeout(1000);
    const futurePoint = await page.locator('#mcFan [data-mc-path]').last().evaluate(path => {
      const point = path.getPointAtLength(path.getTotalLength() * 0.78);
      const matrix = path.getScreenCTM();
      return {
        x: matrix.a * point.x + matrix.c * point.y + matrix.e,
        y: matrix.b * point.x + matrix.d * point.y + matrix.f
      };
    });
    await page.mouse.click(futurePoint.x, futurePoint.y);
    await page.waitForFunction(candidateId => {
      const candidate = window.decide?.cands.find(row => row.id === candidateId);
      return candidate?.authoritativeAnimation && window.decide?._mc?.focusSourcePathIndex != null
        && Object.prototype.hasOwnProperty.call(window.pinnedScen, candidateId);
    }, CANDIDATE_ID, { timeout: 10000 });
    const driven = await page.evaluate(candidateId => ({
      pinnedScenario: window.pinnedScen[candidateId],
      animationFingerprint: window.decide.animation?.ensemble?.fingerprint,
      payoffMarker: document.querySelectorAll('#decPay .livept, #decPay .authpayframe').length,
      chartSpace: document.querySelector('#mcFan')?.getAttribute('data-path-space')
    }), CANDIDATE_ID);
    assert.ok(Number.isInteger(driven.pinnedScenario),
      'clicking a possible future selects the nearest named scenario on the same desk');
    assert.equal(driven.animationFingerprint, ENSEMBLE_FINGERPRINT);
    assert.equal(driven.chartSpace, 'pnl',
      'scenario selection preserves the user-selected package lens');
    const request = backend.requests.find(row => row.method === 'POST'
      && row.path === `/api/plans/${PLAN_ID}/outcomes/ensemble/paths`);
    assert.ok(request, 'path interaction uses the existing backend scenario projection');
    assert.equal(Object.hasOwn(request.body, 'paths'), false,
      'the browser sends a market-story constraint and never invents trajectory coordinates');
    assert.deepEqual(pageErrors, [], `interactive market fan emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('New Idea pages whole rows, composes in the left rail, and focuses Book without losing the idea', async () => {
  const rows = Array.from({ length: 9 }, (_, index) => {
    const row = JSON.parse(JSON.stringify(candidate()));
    row.id = index === 0 ? CANDIDATE_ID : `candidate_page_${index}`;
    row.label = `Income structure ${index + 1}`;
    row.displayName = row.label;
    return row;
  });
  const heldKey = 'trade-book-lens';
  const stepBands = [
    { sessionProgress: 0, pnlP10Cents: 0, pnlP25Cents: 0, pnlP50Cents: 0,
      pnlP75Cents: 0, pnlP90Cents: 0 },
    { sessionProgress: 21, pnlP10Cents: -9000, pnlP25Cents: -3000, pnlP50Cents: 2200,
      pnlP75Cents: 6100, pnlP90Cents: 10500 }
  ];
  const canvasPositions = [
    {
      key: `PROPOSED:${CANDIDATE_ID}`, proposed: true, stepBands,
      displayPaths: [{ sourcePathIndex: 17, role: 'FOCUS', steps: [
        { sessionProgress: 0, pnlCents: 0 }, { sessionProgress: 21, pnlCents: 2200 }
      ] }]
    },
    {
      key: heldKey, proposed: false, stepBands,
      displayPaths: [{ sourcePathIndex: 17, role: 'FOCUS', steps: [
        { sessionProgress: 0, pnlCents: 0 }, { sessionProgress: 21, pnlCents: 2200 }
      ] }]
    }
  ];
  const canvasComparison = [
    { key: `PROPOSED:${CANDIDATE_ID}`, label: 'This idea', proposed: true,
      horizonP5Cents: -12345, horizonP50Cents: 1450, horizonP95Cents: 12000,
      chanceOfGainPct: 63 },
    { key: heldKey, label: 'AMD income position', proposed: false,
      horizonP5Cents: -9000, horizonP50Cents: 2200, horizonP95Cents: 10500,
      chanceOfGainPct: 68 }
  ];
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    strategyCandidates: rows, canvasComparison, canvasPositions
  });
  try {
    assert.equal(await page.locator('.fanr[data-cand]').count(), 5,
      'the 900px viewport renders one page of whole ranked rows');
    assert.equal(await page.locator('.fitpager').count(), 1);
    const firstPageFits = await page.evaluate(() => {
      const fan = document.querySelector('.dcleft .fan');
      const pager = fan?.querySelector('.fitpager');
      const rows = Array.from(fan?.querySelectorAll('.fanr') || []);
      const ceiling = pager?.getBoundingClientRect().top ?? fan?.getBoundingClientRect().bottom;
      return rows.every(row => row.getBoundingClientRect().bottom <= ceiling + 0.5);
    });
    assert.equal(firstPageFits, true, 'no ranked row is clipped behind its pager');

    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.evaluate(() => window.renderDecide());
    assert.equal(await page.locator('.fanr[data-cand]').count(), 5,
      'the 1080px desktop keeps the selected explanation and every ranked row whole');
    const desktopPageFits = await page.evaluate(() => {
      const fan = document.querySelector('.dcleft .fan');
      const pager = fan?.querySelector('.fitpager');
      const rows = Array.from(fan?.querySelectorAll('.fanr') || []);
      const ceiling = pager?.getBoundingClientRect().top ?? fan?.getBoundingClientRect().bottom;
      return rows.every(row => row.getBoundingClientRect().bottom <= ceiling + 0.5);
    });
    assert.equal(desktopPageFits, true, 'the principal desktop viewport has no partial ranked row');

    await page.locator('[data-dec="candpage"][data-page="1"]').click();
    assert.equal(await page.locator('.fanr[data-cand]').count(), 4);
    assert.equal(await page.locator('.fanr[data-cand]').first().getAttribute('data-cand'),
      'candidate_page_5');

    await page.locator('.decintent').click();
    assert.equal(await page.locator('.ideacomposer').count(), 1,
      'the idea editor occupies the decision rail instead of becoming a transient header strip');
    assert.equal(await page.locator('.intentpop').count(), 0);
    await page.locator('[data-obj="goal"][data-val="Hedge"]').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'ready'
      && window.decide?.goal === 'Hedge' && !window.DeskBackend.state().mutationPending,
    null, { timeout: 10000 });
    assert.equal(await page.locator('.ideacomposer').count(), 1,
      'the structural composer stays open while one declaration is refined');
    await page.locator('[data-dec="intentdone"]').click();

    await page.locator('[data-inspect="book"]').click();
    await page.locator(`[data-dec="bookfocus"][data-book-key="${heldKey}"]`).click();
    assert.equal(await page.locator('#bookFocusChart').count(), 1);
    assert.match(await page.locator('.bookfocushead').textContent(), /AMD income position/i);
    assert.ok(await page.locator('#bookFocusChart path').count() >= 2,
      'the focused Book row brings its backend P/L journey into the center');
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#bookFocusChart').count(), 0);
    assert.equal(await page.locator('#decPay').count(), 1,
      'one step back restores the idea without closing the decision surface');
    assert.deepEqual(pageErrors, [], `fit/composer/Book focus emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('the initial ensemble build keeps candidate changes behind one coherent mutation', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const adjacent = incoherentCandidate();
  const backend = await installBackend(page, {
    strategyCandidates: [candidate(), adjacent],
    ensembleDelayMs: 320
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    for (let attempt = 0; attempt < 100
      && backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`) < 1; attempt += 1) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1,
      'the authoritative ensemble is in flight');

    const competing = await page.evaluate(async candidateId => {
      try {
        await window.DeskBackend.chooseCandidate(candidateId);
        return { blocked: false };
      } catch (error) {
        return { blocked: true, message: error.message };
      }
    }, adjacent.id);
    assert.equal(competing.blocked, true);
    assert.match(competing.message, /current Plan change/);

    await page.waitForFunction(id => {
      const state = window.DeskBackend.state();
      return window.decide && window.decide.backendPhase === 'ready'
        && state.selected && state.selected.id === id
        && state.ensemble && state.ensemble.ensemble
        && state.outcome && state.outcome.outcome
        && !state.mutationPending;
    }, CANDIDATE_ID, { timeout: 10000 });
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 1,
      'the competing click cannot supersede the initial selection');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 1,
      'one selected package is valued against the completed fan');
    assert.deepEqual(pageErrors, [], `initial ensemble serialization emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('backing out of a slow idea load queues a clean re-entry instead of stranding the Desk', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { ensembleDelayMs: 320 });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    for (let attempt = 0; attempt < 100
      && backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`) < 1; attempt += 1) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1,
      'the first idea fan is in flight');

    await page.locator('[data-dec="back"]').click();
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(id => {
      const state = window.DeskBackend.state();
      return window.decide && window.decide.backendPhase === 'ready'
        && state.selected && state.selected.id === id
        && state.outcome && state.outcome.outcome
        && !state.mutationPending;
    }, CANDIDATE_ID, { timeout: 10000 });

    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 2,
      'the canceled fan is not promoted into the re-entered idea');
    assert.equal(await page.locator('.authpending').count(), 0,
      'the re-entered Desk leaves its loading skeleton');
    assert.deepEqual(pageErrors, [], `canceled idea re-entry emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a presentation observer exception cannot cancel the authoritative outcome pipeline', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page);
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.evaluate(() => {
      const original = window.StrikeBenchDesk.backendChanged;
      let failedOnce = false;
      window.StrikeBenchDesk.backendChanged = payload => {
        if (!failedOnce && payload.phase === 'ensemble') {
          failedOnce = true;
          throw new Error('Injected presentation observer failure.');
        }
        return original(payload);
      };
    });

    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(candidateId => {
      const state = window.DeskBackend.state();
      return window.decide?.backendPhase === 'ready'
        && state.outcome?.outcome?.candidateId === candidateId
        && window.decide.ensemble?.ensemble
        && window.decide.outcome?.outcome
        && window.decide.orderPreview?.selected?.id === candidateId
        && state.mutationPending === false;
    }, CANDIDATE_ID, { timeout: 10000 });

    const settled = await page.evaluate(() => {
      const state = window.DeskBackend.state();
      return {
        error: state.error?.message || null,
        presentationError: state.presentationError,
        ensembleId: state.ensemble?.ensemble?.id,
        outcomeEnsembleId: state.outcome?.outcome?.ensembleId,
        visibleEnsembleId: window.decide.ensemble?.ensemble?.id,
        visibleOutcomeEnsembleId: window.decide.outcome?.outcome?.ensembleId
      };
    });
    assert.equal(settled.error, null,
      'a caught presentation failure is not promoted into a financial workflow failure');
    assert.equal(settled.presentationError?.phase, 'ensemble');
    assert.match(settled.presentationError?.message || '', /Injected presentation observer failure/);
    assert.equal(settled.outcomeEnsembleId, settled.ensembleId);
    assert.equal(settled.visibleEnsembleId, settled.ensembleId,
      'a later complete-state notification rehydrates the presentation frame it missed');
    assert.equal(settled.visibleOutcomeEnsembleId, settled.ensembleId);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 1,
      'the stored fan still reaches the canonical selected-package valuation');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 1,
      'the exact execution preview still follows the completed outcome');
    assert.deepEqual(pageErrors, [], `observer isolation emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a newer server-owned quote observation is accepted without chasing the open market', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { rollQuoteOnFirstEnsemble: true });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide && window.decide.backendPhase === 'ready', null,
      { timeout: 10000 });
    const state = await page.evaluate(() => {
      const bridge = window.DeskBackend.state();
      return {
        error: bridge.error && bridge.error.message,
        ensembleId: bridge.ensemble && bridge.ensemble.ensemble.id,
        outcomeEnsembleId: bridge.outcome && bridge.outcome.outcome.ensembleId,
        anchorSpot: bridge.ensemble && bridge.ensemble.preview.receipt.anchorSpot,
        anchorAsOf: bridge.ensemble && bridge.ensemble.preview.receipt.asOf,
        pending: bridge.mutationPending
      };
    });
    assert.equal(state.error, null);
    assert.equal(state.ensembleId, ENSEMBLE_ID);
    assert.equal(state.outcomeEnsembleId, state.ensembleId);
    assert.equal(state.anchorSpot, 102);
    assert.equal(Date.parse(state.anchorAsOf), 1784563260000);
    assert.equal(state.pending, false);
    assert.equal(backend.count('GET', '/api/research/AMD'), 1,
      'the client does not chase a live quote that advanced inside the authoritative build');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1,
      'the newer server receipt is the single stored fan used by the Desk');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 1,
      'the server-owned fan receives one selected-package valuation');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 1,
      'execution preview is created only after that exact fan reaches ready');
    assert.deepEqual(pageErrors, [], `mid-flight market convergence emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a newer server-owned option calibration is accepted without a second full-chain read', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { rollChainOnFirstEnsemble: true });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide && window.decide.backendPhase === 'ready', null,
      { timeout: 10000 });
    const state = await page.evaluate(() => {
      const bridge = window.DeskBackend.state();
      return {
        error: bridge.error && bridge.error.message,
        ensembleId: bridge.ensemble && bridge.ensemble.ensemble.id,
        outcomeEnsembleId: bridge.outcome && bridge.outcome.outcome.ensembleId,
        storedVol: bridge.ensemble && bridge.ensemble.preview.receipt.spec.volAnnual,
        marketImpliedVol: bridge.ensemble && bridge.ensemble.preview.marketImplied.atmIv,
        pending: bridge.mutationPending
      };
    });
    assert.equal(state.error, null);
    assert.equal(state.ensembleId, ENSEMBLE_ID);
    assert.equal(state.outcomeEnsembleId, state.ensembleId);
    assert.equal(state.storedVol, 0.37);
    assert.equal(state.marketImpliedVol, 0.37);
    assert.equal(state.pending, false);
    assert.equal(backend.count('GET', '/api/research/AMD/chain'), 1,
      'the client does not chase an option surface calibrated inside the authoritative build');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 1);
    assert.deepEqual(pageErrors, [], `mid-flight option-surface convergence emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Home asks the canonical Scout for the configured-universe redeployment frontier', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const scoutResponse = {
    searched: 105,
    picks: [{
      symbol: 'MU',
      opportunity: { score: 84, summary: 'Rich volatility with liquid income structures.' },
      bestIdea: {
        family: 'CASH_SECURED_PUT', displayName: 'Cash-secured put',
        economicVerdict: 'FAVORABLE', realizedVolEvAfterCostsCents: 16100
      }
    }],
    compensation: [{ symbol: 'MU', strategy: 'CASH_SECURED_PUT', score: 77.4 }],
    frontier: {
      schemaVersion: 'redeployment-frontier-v1',
      universe: { source: 'CONFIGURED', label: 'Active optionable universe', symbols: ['MU', 'AMD', 'NVDA'] },
      destinationAccountId: 'acct-practice',
      decisionRanking: [{
        evaluationId: 'eval-mu', symbol: 'MU', strategy: 'CASH_SECURED_PUT',
        decisionScore: 84, economicVerdict: 'FAVORABLE', qualification: 'QUALIFIED',
        dataCompleteness: { status: 'OBSERVED_COMPLETE' },
        bookImpacts: [{ accountId: 'acct-practice', status: 'IMPROVES' }]
      }],
      compensationRanking: [{ symbol: 'MU', strategy: 'CASH_SECURED_PUT', score: 77.4 }],
      notes: ['Decision economics and compensation are independent rankings.']
    }
  };
  const backend = await installBackend(page, { scoutResponse });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('[data-auth-opportunity-scan]');
    await page.locator('[data-auth-opportunity-scan]').click();
    await page.waitForSelector('.opportunityrow');
    const result = await page.evaluate(() => window.HOME_OPPORTUNITY.data);

    assert.deepEqual(result, scoutResponse,
      'the bridge preserves the canonical Scout response for the Home lens');
    assert.match(await page.locator('.opportunityrow').textContent(),
      /MU.*Cash-secured put.*favorable.*qualified.*Book improves.*\+\$161.*EV/i,
      'Home turns the canonical frontier into a useful package and Book-fit lens');
    assert.match(await page.locator('.opportunitycomp').textContent(),
      /Compensation.*separate view.*MU 77.*never overrides decision economics/i,
      'carry compensation remains visibly separate from the decision order');
    const request = backend.requests.find(row => row.method === 'POST'
      && row.path === '/api/research/scout');
    assert.deepEqual(request.body, {
      horizons: ['45d'],
      maxPicks: 5,
      riskMode: 'balanced',
      allow0dte: false,
      intents: ['INCOME']
    });
  } finally {
    await context.close();
  }
});
