'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { launchChromium } = require('./browser');
/* §7.2 receipt shape, arithmetic and invariants live in ONE place — fixtures/price.js, which is
   checked field-by-field against the Java record by fixtures/fixtures.test.js in this same lane.
   This file supplies only the desk's default PROFILE (its source/freshness/fingerprint), so a
   drifting wire contract fails the self-check instead of quietly agreeing with a stale copy. */
const { packagePrice, unavailablePackagePrice, executionDecision } = require('./fixtures/price');
const { goldenGreeks, goldenMarketImpliedRisk } = require('./fixtures/golden');
const { resolveInteraction } = require('./fixtures/scenarios');
const { bookActionProjectionSet, staticTradeRecord } = require('./fixtures/book');
const scoutFixtures = require('./fixtures/scout');

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

test('the served Desk has one accepted declaration and no presentation-owned bridge backchannel', () => {
  const html = fs.readFileSync(path.join(PUBLIC, 'index.html'), 'utf8');
  const bridge = fs.readFileSync(path.join(PUBLIC, 'js/desk-backend.js'), 'utf8');
  assert.doesNotMatch(html, /resumePlanContext|decide\.planContext/,
    'the presentation cannot retain a shadow accepted Plan declaration');
  assert.doesNotMatch(html, /decide\.govs|govExplicit/,
    'the presentation cannot retain a shadow strategy-control document');
  assert.doesNotMatch(bridge, /window\.decide/,
    'the bridge cannot read presentation globals to construct backend requests');
  assert.match(bridge, /ideaDeclaration:\s*function/,
    'the bridge publishes the one canonical accepted declaration accessor');
  assert.match(bridge, /updateStrategyControls:\s*updateStrategyControls/,
    'strategy screens use one typed bridge operation');
});

test('the served Desk fails closed and exposes no dead browser-only assumption controls', () => {
  const html = fs.readFileSync(path.join(PUBLIC, 'index.html'), 'utf8');
  assert.match(html, /function renderStartupFailure\(/);
  assert.match(html, /No fallback workspace was opened/);
  assert.doesNotMatch(html, /\bGAP_STANCE\b|data-gapdial|function gapDialSeg/,
    'an assumption control without a canonical declaration/scenario endpoint cannot be visible');
  assert.doesNotMatch(html, /if\s*\(deskBackendEnabled\(\)\)\s*\{\s*if\(level===['"]book['"]\)/,
    'layout cannot retain an unreachable served-versus-fixture renderer fork');
  assert.doesNotMatch(html, /var incSpot=decisionSpot\(\)/,
    'leg editing cannot retain the deleted browser-only strike engine fallback');
  assert.doesNotMatch(html, /data-auth-newidea-symbol[^>]*>Shape\b/,
    'a Home action that only stages an underlying cannot be labeled as though analysis has run');
});

let browser;
let server;
let deskUrl;

/** One test builder for the backend's sole public quote shape. */
function quoteView(raw, overrides = {}) {
  const source = Object.assign({}, raw || {}, overrides);
  const has = key => Object.prototype.hasOwnProperty.call(overrides, key);
  const displayPrice = has('displayPrice') ? overrides.displayPrice
    : source.last != null ? source.last : source.prevClose != null ? source.prevClose : null;
  const previousClose = source.last == null && source.prevClose != null && displayPrice != null;
  return {
    symbol: source.symbol,
    description: source.description == null ? null : source.description,
    displayPrice,
    displayChangePct: source.displayChangePct == null
      ? source.changePct == null ? null : source.changePct : source.displayChangePct,
    markBasis: source.markBasis || (displayPrice == null
      ? 'UNAVAILABLE' : previousClose ? 'PREVIOUS_CLOSE' : 'LAST'),
    priceIsPreviousClose: previousClose,
    priced: displayPrice != null,
    quoteUnavailableReason: displayPrice == null
      ? source.quoteUnavailableReason || `No usable price for ${source.symbol}.` : null,
    last: source.last == null ? null : source.last,
    bid: source.bid == null ? null : source.bid,
    ask: source.ask == null ? null : source.ask,
    prevClose: source.prevClose == null ? null : source.prevClose,
    optionable: source.optionable !== false,
    freshness: source.freshness || (displayPrice == null ? 'UNAVAILABLE' : 'FRESH'),
    source: source.source || null,
    evidence: source.evidence || null,
    asOf: source.asOf == null ? source.asOfEpochMs == null ? null : source.asOfEpochMs : source.asOf,
    refreshing: source.refreshing === true
  };
}

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
  browser = await launchChromium();
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
    originPlanId: has('originPlanId') ? overrides.originPlanId : null,
    context: {
      thesis: has('thesis') ? overrides.thesis : 'neutral',
      horizonDays: has('horizonDays') ? overrides.horizonDays : 45,
      riskMode: has('riskMode') ? overrides.riskMode : 'balanced',
      targetCents: has('targetCents') ? overrides.targetCents : null,
      holdingsShares: has('holdingsShares') ? overrides.holdingsShares : null,
      costBasisCents: has('costBasisCents') ? overrides.costBasisCents : null,
      priceAssumptionCents: has('priceAssumptionCents') ? overrides.priceAssumptionCents : null,
      assignmentPreference: has('assignmentPreference') ? overrides.assignmentPreference : null,
      rev: has('contextRev') ? overrides.contextRev : 7
    }
  };
}

function practiceLiquidityReceipt(settlementCents, reserveCents, freeCents, overrides = {}) {
  const pendingCents = overrides.pendingCents == null ? 0 : overrides.pendingCents;
  const differenceCents = settlementCents - pendingCents - reserveCents - freeCents;
  const fact = (cents, basis, authority = 'SYSTEM_CALCULATED') =>
    ({ cents, authority, basis });
  return {
    schemaVersion: 'account-liquidity-v1',
    accountId: ACCOUNT_ID,
    lane: 'PRACTICE',
    settlementBalance: fact(settlementCents,
      'Exact Practice cash ledger balance; reserve remains inside cash.'),
    pendingActivity: fact(pendingCents,
      'Practice entries settle synchronously, so there is no pending broker activity.'),
    recordedOrReportedReserve: fact(reserveCents,
      'Exact reserve held by the canonical Practice ledger.'),
    theoreticalShortPutObligation: fact(0,
      'Gross strike obligation across active short puts from canonical trade geometry.'),
    genuinelyFreeBuyingPower: fact(freeCents,
      'Exact Practice cash less exact recorded reserve.'),
    concurrentCollateralIncome: {
      authority: 'UNAVAILABLE',
      basis: 'Practice cash has no broker-reported settlement-fund income receipt.'
    },
    reconciliationDifference: fact(differenceCents,
      'Settlement less pending activity, reserve, and genuinely free buying power.'),
    reconciliationStatus: differenceCents === 0 ? 'RECONCILED' : 'DIFFERENCE',
    reconciliationReason: differenceCents === 0
      ? 'Practice settlement, reserve, and buying power reconcile exactly.'
      : 'Practice ledger values do not reconcile; this is an invariant failure.',
    evidenceAsOf: '2026-07-24T17:26:00Z',
    sourceRefs: ['accounts.cash_cents', 'accounts.reserved_cents', 'ledger', 'trades']
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
      note: 'Authoritative empty Practice account receipt.',
      liquidity: practiceLiquidityReceipt(10000000, 0, 10000000)
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
    /* Book scope publishes only the units that add. There is no deltaShares/gammaShares field
       at all — a summed share delta across underlyings is not expressible. */
    greeks: {
      positions: [],
      netDollarDeltaCents: 0,
      grossDollarDeltaCents: 0,
      grossDollarDeltaBySymbolCents: {},
      dollarDeltaComplete: true,
      thetaCentsPerDay: 0,
      vegaCentsPerPoint: 0,
      perShareAvailable: false,
      perShareUnavailableReason: 'Share delta is not additive across underlyings.',
      activeTrades: 0,
      measuredTrades: 0,
      complete: true,
      basis: 'No active Practice positions.'
    },
    bookRisk: {
      accounts: [],
      crossAccount: null,
      practice: {
        dollarDeltaNetCents: 0,
        dollarDeltaGrossCents: 0,
        thetaCentsPerDay: 0,
        vegaCentsPerPoint: 0,
        perShareAvailable: false,
        perShareUnavailableReason: 'Share delta is not additive across underlyings.',
        complete: true,
        basis: 'PRACTICE_EXECUTABLE_MARKS'
      },
      basis: 'PRACTICE_EXECUTABLE_MARKS'
    },
    planPortfolio: []
  };
}

/** Exact v1 wire envelope returned by GET /api/portfolio/book in these browser contracts. */
function practiceBookDocument(documents) {
  const summary = JSON.parse(JSON.stringify(documents.summary));
  const heat = Object.assign({
    reservedCents: summary.reservedCents,
    assignmentReserveReleasedCents: 0
  }, JSON.parse(JSON.stringify(documents.heat)));
  const greeks = JSON.parse(JSON.stringify(documents.greeks));
  const shareRoster = documents.bookRisk.practice.shareRoster || {
    accountId: ACCOUNT_ID,
    denominatorCents: heat.totalMaxLossCents,
    positions: documents.activeTrades.length,
    rows: [],
    basis: 'Backend Book-share roster sentinel.'
  };
  const practiceRisk = Object.assign({},
    JSON.parse(JSON.stringify(documents.bookRisk.practice)), {
      shareRoster: JSON.parse(JSON.stringify(shareRoster))
    });
  const liquidity = summary.liquidity
    ? JSON.parse(JSON.stringify(summary.liquidity))
    : practiceLiquidityReceipt(summary.cashCents, summary.reservedCents,
      summary.buyingPowerCents);
  liquidity.accountId = ACCOUNT_ID;
  summary.liquidity = liquidity;
  const marksByTrade = {};
  if (documents.tradeDetail && documents.tradeDetail.current) {
    marksByTrade[documents.tradeDetail.current.tradeId] =
      JSON.parse(JSON.stringify(documents.tradeDetail.current));
  }
  Object.entries(documents.tradeDetails || {}).forEach(([id, detail]) => {
    if (detail && detail.current) marksByTrade[id] = JSON.parse(JSON.stringify(detail.current));
  });
  return {
    schemaVersion: 'practice-book-read-v1',
    snapshotId: 'pbs_backend_contract',
    account: {
      accountId: ACCOUNT_ID,
      name: 'Desk practice account',
      type: 'PAPER',
      worldId: null,
      startingBalanceCents: summary.startingCashCents,
      settlementBalanceCents: summary.cashCents,
      recordedReserveCents: summary.reservedCents,
      genuinelyFreeBuyingPowerCents: summary.buyingPowerCents
    },
    summary,
    snapshot: {
      schemaVersion: 'practice-book-snapshot-v1',
      snapshotId: 'pbs_backend_contract',
      accountId: ACCOUNT_ID,
      activeTrades: documents.activeTrades
        .map(trade => staticTradeRecord(trade, { accountId: ACCOUNT_ID })),
      marksByTrade,
      heat,
      openPositions: {
        openTradesCount: summary.openTradesCount,
        markedTradesCount: summary.complete ? summary.openTradesCount : 0,
        valueCents: summary.openTradesValueCents,
        unrealizedCents: summary.openTradesUnrealizedCents,
        complete: summary.complete,
        freshness: summary.freshness
      },
      dollarDelta: {
        grossCents: greeks.grossDollarDeltaCents,
        netCents: greeks.netDollarDeltaCents,
        symbolGrossCents: greeks.grossDollarDeltaBySymbolCents,
        tradeNetCents: {},
        complete: greeks.dollarDeltaComplete,
        basis: greeks.basis
      },
      greeks,
      asOf: '2026-07-20T16:00:00Z'
    },
    sharePositions: JSON.parse(JSON.stringify(documents.sharePositions)),
    bookRisk: practiceRisk,
    liquidity,
    declaredRiskContext: {
      riskCapitalCents: null, accountObjective: null, assignmentPreference: null
    },
    selectedBook: {
      accountId: ACCOUNT_ID,
      tradeIds: [],
      positions: [],
      grossMaxLossCents: 0,
      netDollarDeltaCents: 0,
      complete: true,
      selectedPositions: 0,
      bookRiskDenominatorCents: heat.totalMaxLossCents,
      bookRiskDenominatorBasis: shareRoster.basis,
      basis: 'No Practice positions selected.'
    },
    basis: documents.bookRisk.basis
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
  /* One batched receipt now carries the synchronized total, every held projection, and the
     matching per-symbol price fan. Reuse the exact PositionAnimation fixture so Book and Position
     prove they consume the same lifecycle rather than reopening every Plan. */
  const batch = positionScenarioResponse({ waypoints: [], limit: 3 });
  const grid = batch.checkpoints.underlyingSteps;
  const SESSION_DATES = grid.map(row => row.sessionDate);
  const stepBands = grid.map((row, index) => ({
    step: row.step,
    sessionProgress: row.sessionProgress,
    sessionDate: SESSION_DATES[index],
    pnlP5Cents: [0, -18000, -42000, -76000, -110000, -135000][index],
    pnlP10Cents: [0, -12000, -30000, -50000, -72000, -94000][index],
    pnlP25Cents: [0, -5000, -10000, -18000, -26000, -36000][index],
    pnlP50Cents: [0, 5000, 12000, 22000, 34000, 46000][index],
    pnlP75Cents: [0, 12000, 33000, 57000, 80000, 104000][index],
    pnlP90Cents: [0, 24000, 52000, 83000, 116000, 151000][index],
    pnlP95Cents: [0, 31000, 68000, 103000, 145000, 188000][index]
  }));
  const displayPaths = [
    [0, -8000, -18000, 2000, 22000, 46000],
    [0, 9000, 22000, 43000, 72000, 104000],
    [0, -16000, -42000, -76000, -108000, -135000]
  ].map((values, index) => ({
    sourcePathIndex: batch.paths.paths[index].sourcePathIndex,
    role: batch.paths.paths[index].role,
    steps: values.map((pnlCents, index) => ({
      step: stepBands[index].step,
      sessionProgress: stepBands[index].sessionProgress,
      sessionDate: stepBands[index].sessionDate,
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
      positions: [{
        key: BOOK_TRADE_ID,
        symbol: 'AAPL',
        label: 'AAPL · CALL_DEBIT_SPREAD',
        source: 'ACTIVE_PRACTICE_PACKAGE',
        anchorValueCents: -6150,
        anchorBasis: 'MODELED_CURRENT_VALUE',
        horizonP10Cents: -47800,
        horizonP50Cents: -43200,
        horizonP90Cents: -38200,
        chanceOfGainPct: 57,
        atmIvAnnual: 0.27,
        projection: batch.checkpoints.positions[0]
      }],
      markets: [{
        symbol: 'AAPL',
        anchorSpot: 222.22,
        basis: 'CONDITIONAL_BOOTSTRAP',
        projection: {
          stepBands: batch.paths.bands,
          samples: batch.paths.paths.map(row => row.prices),
          sampleSourcePathIndices: batch.paths.paths.map(row => row.sourcePathIndex),
          sampleFocusIndex: batch.paths.paths.findIndex(row => row.role === 'FOCUS'),
          receipt: {
            version: 'preview-display-projection-1',
            sourcePointCount: batch.paths.receipt.sourcePointCount,
            returnedPointCount: batch.paths.receipt.returnedPointCount,
            displaySteps: grid.map(row => row.step)
          }
        },
        underlyingSteps: batch.checkpoints.underlyingSteps,
        animation: batch.checkpoints.animation
      }],
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
    entryPrice: priceReceipt({
      quantity: 2, optionNetPremiumCents: -43210, grossPackageNetCents: -43210,
      openingFeesCents: 260, executableNetCents: -43210,
      valuationBasis: 'RECORDED_FILL', fingerprint: 'recorded-book-trade'
    }),
    maxLossCents: 43210,
    maxProfitCents: 156790,
    breakevens: [217.16],
    popEntry: 0.57,
    realizedPnlCents: 0,
    decisionPnlCents: 0,
    entrySnapshot: { source: 'BOOK_TEST_EXECUTABLE_RECEIPT', freshness: 'FRESH' },
    isLive: false,
    createdAt: '2026-07-20T15:00:00Z',
    updatedAt: '2026-07-20T16:00:00Z',
    intent: 'DIRECTIONAL',
    sharesLocked: 0,
    dataProvenance: 'OBSERVED',
    dataAge: 'CURRENT',
    dataSource: 'BOOK_TEST_EXECUTABLE_RECEIPT',
    unrealizedPnlCents: 24680,
    decisionUnrealizedPnlCents: 24680,
    currentMarketAvailability: currentAvailability(),
    /* The held line's own scenario receipt (TradeController.heldScenarios): one priced checkpoint
       per NAMED story move, valued server-side through the same curve that owns its terminal
       payoff. Held positions used to have no per-move receipt at all, which is why the desk priced
       the eight stories in the browser from the legs. */
    /* ONE held curve, on the trade. */
    terminalPayoff: {
      schemaVersion: 'risk-terminal-payoff-1',
      modelVersion: 'payoff-curve-1',
      available: true,
      anchorSpotCents: 22222,
      anchorPnlCents: 0,
      expiration: '2026-08-21',
      basis: 'EXPIRATION_INTRINSIC',
      entryBasis: 'RECORDED_ENTRY',
      feesIncluded: false,
      points: [
        { price: 200, profitCents: -43210 },
        { price: 222.22, profitCents: 24680 },
        { price: 230, profitCents: 156790 }
      ]
    },
    /* "If price holds" is the engine's own curve value at the spot the receipt names — not a
       browser interpolation printed as a financial fact. */
    spotPnl: {
      terminalPnlAtCurrentSpotCents: 24680,
      spotCents: 22222,
      spotBasis: 'LIVE_MARK',
      freshness: 'FRESH',
      withinServedCurve: true
    },
    scenarios: {
      available: true,
      anchorSpotCents: 22222,
      anchorBasis: 'MID',
      freshness: 'FRESH',
      source: 'BOOK_TEST_EXECUTABLE_RECEIPT',
      observedAt: Date.parse('2026-07-20T16:00:00Z'),
      values: [
        { story: 'MARKET_CRASH', underlyingMovePct: -0.20, targetUnderlyingCents: 17778, pnlCents: -43210, prob: null },
        { story: 'GAP_DOWN', underlyingMovePct: -0.09, targetUnderlyingCents: 20222, pnlCents: -31200, prob: null },
        { story: 'ORDERLY_PULLBACK', underlyingMovePct: -0.06, targetUnderlyingCents: 20889, pnlCents: -18400, prob: null },
        { story: 'CHOPPY_SIDEWAYS', underlyingMovePct: -0.01, targetUnderlyingCents: 22000, pnlCents: 12500, prob: null },
        { story: 'FLAT_RANGE', underlyingMovePct: 0, targetUnderlyingCents: 22222, pnlCents: 24680, prob: null },
        { story: 'GRIND_HIGHER', underlyingMovePct: 0.06, targetUnderlyingCents: 23555, pnlCents: 44300, prob: null },
        { story: 'STRONG_RALLY', underlyingMovePct: 0.13, targetUnderlyingCents: 25111, pnlCents: 68900, prob: null },
        { story: 'MELT_UP', underlyingMovePct: 0.20, targetUnderlyingCents: 26666, pnlCents: 92500, prob: null }
      ]
    }
  };
  const current = {
    tradeId: BOOK_TRADE_ID,
    ts: '2026-07-20T16:00:00Z',
    underlyingCents: 22222,
    unrealizedCents: 24680,
    decisionUnrealizedCents: 24680,
    currentClosePrice: priceReceipt({
      quantity: 2, optionNetPremiumCents: 18530, grossPackageNetCents: 18530,
      openingFeesCents: 260, executableNetCents: 18530,
      valuationBasis: 'EXECUTABLE_BOOK', fingerprint: 'current-book-trade',
      feeSide: 'CLOSING'
    }),
    indicativeUnrealizedCents: null,
    indicativeDecisionUnrealizedCents: null,
    popNow: 0.64,
    freshness: 'FRESH',
    greeks: {
      deltaShares: 37.25,
      gammaSharesPerDollar: 1.75,
      thetaCentsPerDay: -1234,
      vegaCentsPerPoint: 1850
    },
    legGreeks: [
      {
        leg: 'BUY 1x 215.0 CALL 2026-08-21', bid: '5.10', ask: '5.35', iv: 0.281,
        greeks: {
          deltaShares: 62.1, gammaSharesPerDollar: 2.1,
          thetaCentsPerDay: -910, vegaCentsPerPoint: 1320
        }
      },
      {
        leg: 'SELL 1x 225.00 CALL 2026-08-21', bid: '3.00', ask: '3.20', iv: 0.274,
        greeks: {
          deltaShares: -24.85, gammaSharesPerDollar: -0.35,
          thetaCentsPerDay: -324, vegaCentsPerPoint: 530
        }
      }
    ],
    availability: currentAvailability(),
    underlyingQuote: quoteView({
      symbol: 'AAPL', bid: 222.20, ask: 222.24, last: 222.22,
      source: 'BOOK_TEST_EXECUTABLE_RECEIPT', freshness: 'FRESH', asOf: 1784563200000,
      evidence: { source: 'BOOK_TEST_EXECUTABLE_RECEIPT', provenance: 'OBSERVED' }
    }),
    marketImpliedRisk: goldenMarketImpliedRisk({ pop: 0.64, underlyingCents: 22222 })
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
      audit: []
      /* No `payoff` here: ApiResponses.TradeDetail is five components and the held curve has one
         owner, trade.terminalPayoff. A second list on this envelope is what let a detail event
         overwrite the good curve with an empty array (audit §5.4). */
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
      note: 'Backend summary sentinel; reserve remains inside cash; BEFORE close fees.',
      liquidity: practiceLiquidityReceipt(9752000, 43210, 9708790)
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
    greeks: {
      /* The per-position row keeps its own share delta (one underlying, so the unit means
         something) beside the same exposure in the additive dollar unit. */
      positions: [{
        id: BOOK_TRADE_ID, symbol: 'AAPL', strategy: 'CREDIT_PUT_SPREAD', qty: 1,
        greeks: current.greeks, netDollarDeltaCents: 827695, unrealizedCents: 24680
      }],
      netDollarDeltaCents: 827695,
      grossDollarDeltaCents: 827695,
      grossDollarDeltaBySymbolCents: { AAPL: 827695 },
      dollarDeltaComplete: true,
      thetaCentsPerDay: -1234,
      vegaCentsPerPoint: 1850,
      perShareAvailable: false,
      perShareUnavailableReason: 'Share delta is not additive across underlyings.',
      activeTrades: 1,
      measuredTrades: 1,
      complete: true,
      basis: 'Backend aggregate Greeks sentinel.'
    },
    bookRisk: {
      accounts: [],
      crossAccount: null,
      practice: {
        dollarDeltaNetCents: 827695,
        dollarDeltaGrossCents: 827695,
        thetaCentsPerDay: -1234,
        vegaCentsPerPoint: 1850,
        perShareAvailable: false,
        perShareUnavailableReason: 'Share delta is not additive across underlyings.',
        complete: true,
        basis: 'PRACTICE_EXECUTABLE_MARKS',
        measuredBook: measuredJointBookReceipt()
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
      quote: quoteView({
        symbol: 'AAPL', bid: 222.20, ask: 222.24, last: 222.22,
        source: 'BOOK_TEST_RESEARCH_RECEIPT', freshness: 'FRESH', asOf: 1784563200000,
        evidence: { source: 'BOOK_TEST_RESEARCH_RECEIPT', provenance: 'OBSERVED' }
      }),
      marketLane: 'OBSERVED',
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
      asOfDate: '2026-07-20',
      /* Each row states its own distance in trading sessions (market calendar, holidays
         included) and calendar days, so the browser never counts weekdays to choose a chain. */
      expirations: [
        { date: '2026-08-21', tradingSessions: 24, calendarDays: 32 },
        { date: '2026-09-18', tradingSessions: 43, calendarDays: 60 }
      ]
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
  Object.assign(documents.research.quote, {
    symbol, displayPrice: price, bid: price - 0.02, ask: price + 0.02,
    last: price, prevClose: price - 1
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
    entryPrice: priceReceipt({
      quantity: 2, optionNetPremiumCents: -28700, grossPackageNetCents: -28700,
      openingFeesCents: 260, executableNetCents: -28700,
      valuationBasis: 'RECORDED_FILL', fingerprint: 'recorded-second-book-trade'
    }),
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
  /* The Practice Book is one batched measured receipt. Once the fixture adds a second active
     package, its measured scenario must add that exact package too; leaving the one-position
     receipt beside a two-position roster correctly fails the transport validator. */
  const measured = documents.bookRisk?.practice?.measuredBook;
  if (measured?.available === true && measured.scenario?.positions?.length) {
    const secondProjection = JSON.parse(JSON.stringify(measured.scenario.positions[0]));
    Object.assign(secondProjection, {
      key: SECOND_BOOK_TRADE_ID,
      symbol: 'AAPL',
      label: 'AAPL · PUT_DEBIT_SPREAD',
      anchorValueCents: -37100,
      horizonP10Cents: -28700,
      horizonP50Cents: -8400,
      horizonP90Cents: 18000,
      chanceOfGainPct: 46
    });
    measured.scenario.positions.push(secondProjection);
    measured.scenario.positionCount = measured.scenario.positions.length;
  }
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

function lifecycleAnalysisFixture(overrides = {}) {
  const presentation = Object.assign({
    evidenceState: 'SUFFICIENT',
    actionable: true,
    userFacingVerdict: 'Keep',
    userFacingStatus: 'On plan',
    tone: 'POSITIVE',
    trigger: {
      code: 'HOLD_VS_CLOSE_POSITIVE',
      label: 'Forward economics remain favorable',
      dimension: 'FORWARD_ECONOMICS',
      status: 'KEEP',
      basis: 'The existing held-position evaluator still supports the package on a fresh-eyes basis.'
    },
    sortPriority: 5
  }, overrides.presentation || {});
  const bookActions = overrides.bookActions || bookActionProjectionSet({
    id: overrides.tradeId || BOOK_TRADE_ID,
    qty: overrides.quantity == null ? 2 : overrides.quantity
  });
  return {
    lifecycle: {
      currentChoice: {
        freshEyesQuestion: 'Would you open this exact position today?',
        close: {
          executable: true,
          price: { grossPackageNetCents: -18530, afterFeeNetCents: -19050 }
        }
      },
      carryCollateral: { grossRemainingPremiumCents: 43210 },
      history: { grossPremiumCapturedPct: 37 },
      assignmentExit: { eventCrossings: [] }
    },
    decision: Object.assign({
      verdict: 'KEEP',
      summary: 'KEEP under this named policy; the backend owns the final surface semantics.',
      observedAt: '2026-07-20T16:00:00Z',
      marketSnapshotFingerprint: 'snapshot-lifecycle-presentation-test',
      projectionFingerprint: 'projection-lifecycle-presentation-test',
      presentation
    }, overrides.decision || {}),
    bookActions
  };
}

function positionIdentity(overrides = {}) {
  return Object.assign({
    family: 'DEBIT_CALL_SPREAD',
    template: null,
    label: 'Debit call spread',
    summary: 'Buy a lower-strike call and sell a higher-strike call for defined upside participation.',
    definedRisk: true,
    blockedByDefault: false,
    custom: false,
    fundingClass: 'DEFINED_RISK',
    capitalBasis: 'MAXIMUM_LOSS'
  }, overrides);
}

function strategyCatalog() {
  return {
    families: [
      'CALL_DEBIT_SPREAD', 'CREDIT_PUT_SPREAD', 'CALENDAR_CALL',
      'CASH_SECURED_PUT', 'PROTECTIVE_PUT', 'NAKED_CALL'
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
        name: 'CREDIT_PUT_SPREAD',
        display: 'Bull put credit spread',
        category: 'Income',
        summary: 'Defined-risk premium sale using a lower-strike protective put.',
        payoffShape: '2,26 24,26 43,7 62,7',
        foundationalRank: 2,
        definedRisk: true,
        blockedByDefault: false,
        scenarioEnabled: true,
        backtestEnabled: true,
        recommendationEnabled: true,
        primaryIntent: 'INCOME',
        intents: ['INCOME']
      },
      {
        name: 'CALENDAR_CALL',
        display: 'Call calendar',
        category: 'Time spreads',
        summary: 'Sell nearer-term time value against a longer-dated call.',
        payoffShape: '2,25 22,20 34,5 47,20 62,25',
        foundationalRank: 4,
        definedRisk: true,
        blockedByDefault: false,
        scenarioEnabled: true,
        backtestEnabled: true,
        recommendationEnabled: true,
        primaryIntent: 'DIRECTIONAL',
        intents: ['DIRECTIONAL', 'INCOME']
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

function capturedOptionLeg(leg, entryPrice, index = 0) {
  const sell = String(leg.action || '').toUpperCase() === 'SELL';
  const bid = sell ? entryPrice : Math.max(0.01, entryPrice - 0.2);
  const ask = sell ? entryPrice + 0.2 : entryPrice;
  return Object.assign({}, leg, {
    entryPrice,
    quoteBid: bid,
    quoteAsk: ask,
    quoteIv: 0.24 + (index * 0.01),
    quoteDelta: String(leg.type || '').toUpperCase() === 'PUT'
      ? -(0.32 + (index * 0.02))
      : 0.48 - (index * 0.04),
    quoteAsOfEpochMs: 1784563260000,
    quoteSource: 'BACKEND_TEST_RECEIPT',
    quoteFreshness: 'FRESH'
  });
}

function candidate() {
  const price = priceReceipt({ optionNetPremiumCents: -12345, openingFeesCents: 260,
    executableNetCents: -12345, fingerprint: 'price-candidate-debit' });
  const marketImpliedRisk = goldenMarketImpliedRisk({
    priceFingerprint: price.fingerprint,
    pop: 0.63,
    expectedValueCents: -5200,
    underlyingCents: 10000,
    cvar95Cents: -12345,
    stressLossCents: -12345
  });
  return {
    id: CANDIDATE_ID,
    label: 'Backend debit call spread',
    displayName: 'Backend debit call spread',
    strategy: 'CALL_DEBIT_SPREAD',
    symbol: 'AMD',
    qty: 1,
    price,
    maxLossCents: 12345,
    maxProfitCents: 87655,
    assignmentProb: 0.08,
    breakevens: [101.23],
    freshness: 'FRESH',
    sourceKind: 'BACKEND_TEST_RECEIPT',
    whyConsidered: 'Selected by the backend recommendation service.',
    identity: positionIdentity(),
    marketImpliedRisk,
    legs: [
      capturedOptionLeg({
        type: 'CALL', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
        multiplier: 100, strike: 100, expiration: '2026-08-21'
      }, 6, 0),
      capturedOptionLeg({
        type: 'CALL', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
        multiplier: 100, strike: 110, expiration: '2026-08-21'
      }, 2, 1)
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
        marketImpliedRisk,
        worstScenario: {
          severityAvailable: true,
          underlyingMovePct: -0.20,
          pnlCents: -12345,
          lossCents: 12345,
          comparisonLossCents: 12345,
          comparisonBasis: 'MAXIMUM_LOSS',
          lossSharePct: 100,
          severity: 'SEVERE',
          basis: 'WORST_NAMED_SCENARIO_VS_MAXIMUM_LOSS',
          unavailableReason: null
        },
        terminalPayoff: {
          schemaVersion: 'risk-terminal-payoff-1',
          modelVersion: 'payoff-curve-1',
          available: true,
          anchorSpotCents: 10000,
          anchorPnlCents: 424200,
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
          /* One checkpoint per NAMED story move — RiskProfiler.MOVES is that set, so the desk
             never has to fill a gap by interpolating between checkpoints. */
          { story: 'MARKET_CRASH', underlyingMovePct: -0.20, pnlCents: -12345 },
          { story: 'GAP_DOWN', underlyingMovePct: -0.09, pnlCents: 30100 },
          { story: 'ORDERLY_PULLBACK', underlyingMovePct: -0.06, pnlCents: 45200 },
          { story: 'CHOPPY_SIDEWAYS', underlyingMovePct: -0.01, pnlCents: 72400 },
          { story: 'FLAT_RANGE', underlyingMovePct: 0, pnlCents: 77700 },
          { story: 'GRIND_HIGHER', underlyingMovePct: 0.06, pnlCents: 61800 },
          { story: 'STRONG_RALLY', underlyingMovePct: 0.13, pnlCents: 40500 },
          { story: 'MELT_UP', underlyingMovePct: 0.20, pnlCents: 22200 }
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

/* The mock server, not the browser under test, publishes the canonical promotion decision. */
function backendEndorsement(row) {
  const mechanics = row?.evaluation?.assessment?.mechanics;
  const coherence = row?.evaluation?.assessment?.coherence?.verdict;
  const economics = row?.evaluation?.assessment?.economics?.verdict;
  const priced = row?.price?.valuationBasis !== 'UNAVAILABLE';
  const immediate = row?.price?.executability === 'IMMEDIATE';
  const endorsed = mechanics?.eligible !== false && row?.evaluation?.viable !== false
    && coherence === 'COHERENT' && economics === 'FAVORABLE' && priced && immediate;
  return {
    endorsed,
    status: endorsed ? 'ENDORSED' : 'COMPARISON',
    candidateId: row?.id || null,
    reasons: endorsed ? [] : ['The mock backend retained this package as a comparison.'],
    basis: 'Mock backend decision-policy receipt.'
  };
}

/* A ranked package the option book could not price. PackagePriceReceipt.unavailable(...) nulls
   every amount, source, freshness, stamp and fingerprint and keeps only the reason, so this is the
   exact wire shape — the compact constructor refuses an UNAVAILABLE basis beside any net. */
function unpricedCandidate() {
  const row = candidate();
  row.id = 'candidate_backend_unpriced';
  row.label = 'Backend unpriced package';
  row.displayName = 'Backend unpriced package';
  row.price = priceReceipt({
    optionNetPremiumCents: null, stockCashFlowCents: null, grossPackageNetCents: null,
    openingFeesCents: null, afterFeeNetCents: null, executableNetCents: null,
    valuationBasis: 'UNAVAILABLE', executability: 'UNAVAILABLE',
    source: null, freshness: null, observedAt: null, fingerprint: null,
    unavailableReason: 'No two-sided book priced both legs of this package.'
  });
  row.marketImpliedRisk = goldenMarketImpliedRisk({
    available: false,
    unavailableReason: 'No two-sided book priced both legs of this package.'
  });
  row.evaluation.risk.marketImpliedRisk = row.marketImpliedRisk;
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
    capturedOptionLeg({ type: 'PUT', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 90, expiration: '2026-08-21' }, 1, 0),
    capturedOptionLeg({ type: 'PUT', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 95, expiration: '2026-08-21' }, 2, 1),
    capturedOptionLeg({ type: 'CALL', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 105, expiration: '2026-08-21' }, 2, 2),
    capturedOptionLeg({ type: 'CALL', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 110, expiration: '2026-08-21' }, 1, 3)
  ];
  return row;
}

function customCandidate(position) {
  const preview = customTradePreview(position).preview;
  const marketImpliedRisk = goldenMarketImpliedRisk({
    priceFingerprint: preview.price.fingerprint,
    pop: 0.61,
    expectedValueCents: -800,
    underlyingCents: 10000,
    cvar95Cents: -31000,
    stressLossCents: -31000
  });
  return {
    id: CUSTOM_CANDIDATE_ID,
    selected: true,
    label: 'Exact backend debit spread',
    displayName: 'Exact backend debit spread',
    strategy: 'CUSTOM',
    symbol: 'AMD',
    qty: position.qty,
    price: preview.price,
    maxLossCents: preview.maxLossCents,
    maxProfitCents: preview.maxProfitCents,
    assignmentProb: 0.04,
    breakevens: preview.breakevens,
    freshness: 'FRESH',
    sourceKind: 'BUILDER',
    whyConsidered: 'Exact package selected after backend repricing.',
    identity: positionIdentity(),
    marketImpliedRisk,
    legs: position.legs.map((leg, index) =>
      capturedOptionLeg(leg, index === 0 ? 6.2 : 3.1, index)),
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
        marketImpliedRisk,
        terminalPayoff: {
          schemaVersion: 'risk-terminal-payoff-1',
          modelVersion: 'payoff-curve-1',
          available: true,
          anchorSpotCents: 10000,
          anchorPnlCents: 424200,
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
      price: priceReceipt({ quantity: position.qty || 1,
        optionNetPremiumCents: valid ? -31000 : -62000, openingFeesCents: 260,
        executableNetCents: valid ? -31000 : -62000, fingerprint: 'price-custom-draft' }),
      maxLossCents: valid ? 31000 : 62000,
      maxProfitCents: valid ? 69000 : null,
      reserveCents: valid ? 31000 : 62000,
      popEntry: valid ? 0.61 : null,
      breakevens: valid ? [103.1] : [],
      freshness: 'FRESH',
      evidence: { source: 'BACKEND_TEST_RECEIPT', lane: 'OBSERVED' },
      blockReasons,
      legs: (position.legs || []).map((leg, index) => Object.assign(
        { leg, fill: index === 0 ? 6.2 : 3.1 },
        capturedOptionLeg(leg, index === 0 ? 6.2 : 3.1, index)
      )),
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
    identity: positionIdentity(),
    execution: executionDecision({
      reviewAllowed: valid,
      confirmAllowed: valid,
      reasons: blockReasons
    })
  };
}

/*
 * The canvas keys its position row by the candidate it valued. This fixture used to hardcode
 * CANDIDATE_ID, so any suite selecting a different candidate got a canvas whose only row belonged
 * to someone else — the greeks lens then rendered "unavailable" and no cross-surface comparison of
 * greeks was possible. The key follows the candidate now, as it does in production.
 */
function animationTrack(frameCount, options = {}) {
  return {
    frameRule: 'SELECT_NEAREST_FRAME_NO_INTERPOLATION',
    frameSource: 'underlyingSteps',
    positionFrameSource: 'positions[].steps',
    frameCount,
    sourceStepCount: options.sourceStepCount || frameCount,
    stepsPerDay: options.stepsPerDay || 1,
    anchorSpot: options.anchorSpot == null ? 100 : options.anchorSpot,
    horizonSessions: options.horizonSessions == null ? Math.max(0, frameCount - 1) : options.horizonSessions,
    baselineAtmIv: options.baselineAtmIv == null ? 0.30 : options.baselineAtmIv,
    baselineAtmIvSource: 'TRACK_FRAME_0'
  };
}

function positionAnimation(frameCount, terminalFrameIndex, terminalSessionProgress, options = {}) {
  return {
    frameCount,
    terminalFrameIndex,
    terminalSessionProgress,
    finalOptionExpiration: options.finalOptionExpiration == null ? null : options.finalOptionExpiration,
    boundaryReason: options.boundaryReason || 'FINAL_CASH_SETTLEMENT',
    exposureResolvedAtBoundary: options.exposureResolvedAtBoundary !== false,
    unavailableReason: null
  };
}

function ensemble(version = 13, market = {}) {
  const candidateKey = market.candidateId || CANDIDATE_ID;
  const spot = market.spot == null ? 100 : market.spot;
  const symbol = market.symbol || 'AMD';
  const shift = spot - 100;
  const ensembleId = market.id || ENSEMBLE_ID;
  const fingerprint = market.fingerprint || ENSEMBLE_FINGERPRINT;
  const valuationFingerprint = market.valuationFingerprint
    || `valuation-${candidateKey}`;
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
    step: step * 21, sessionProgress: step * 2.1,
    p10: spot - step * 0.45, p25: spot - step * 0.22,
    p50: spot + step * 0.08, p75: spot + step * 0.34, p90: spot + step * 0.58
  }));
  const sampleSourcePathIndices = samples.map((unused, index) => 100 + index);
  const sampleFocusIndex = Math.floor(samples.length / 2);
  const pnlStepBands = stepBands.map((band, step) => ({
    step: band.step, sessionProgress: band.sessionProgress,
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
      step: stepBands[step].step, sessionProgress: stepBands[step].sessionProgress,
      pnlCents: Math.round((price - spot) * 10000 + step * 350)
    }))
  }));
  const focusTrack = samples[sampleFocusIndex].map((price, step) => step === 0 ? spot : price);
  const underlyingSteps = focusTrack.map((focusPrice, step) => {
    const sessionProgress = stepBands[step].sessionProgress;
    const rowIv = atmIv - step * 0.001;
    return {
      step: stepBands[step].step, sessionProgress, sessionDate: `2026-07-${String(20 + step).padStart(2, '0')}`,
      focusPrice, atmIv: rowIv,
      moveFromSpotPct: (focusPrice / spot - 1) * 100,
      ivShiftPoints: (rowIv - atmIv) * 100
    };
  });
  const positionSteps = pnlStepBands.map(row => ({
    step: row.step, sessionProgress: row.sessionProgress,
    focusValueCents: -12345 + row.pnlP50Cents,
    focusPnlCents: row.pnlP50Cents,
    greeks: goldenGreeks()
  }));
  return {
    plan: plan(version),
    ensemble: {
      id: ensembleId,
      fingerprint,
      basis: 'PARAMETRIC'
    },
    currency: market.currency || {
      current: true,
      status: 'CURRENT',
      reason: 'The mock backend confirms this stored fan is current.'
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
        animation: animationTrack(underlyingSteps.length, {
          sourceStepCount: 211, stepsPerDay: 10,
          anchorSpot: spot, horizonSessions: 21, baselineAtmIv: atmIv
        }),
        underlying: [
          { day: 0, p10: 100, p50: 100, p90: 100, focusPrice: 100, atmIv: 0.30 },
          { day: 21, p10: 101, p50: 104, p90: 108, focusPrice: 104, atmIv: 0.29 }
        ],
        underlyingSteps,
        modelReceipt: {
          ensembleFingerprint: fingerprint,
          selectedCandidateId: candidateKey,
          valuationFingerprint
        },
        positions: [{
          key: `PROPOSED:${candidateKey}`,
          proposed: true,
          stepBands: pnlStepBands,
          displayPaths: pnlDisplayPaths,
          days: [
            { focusValueCents: -12345, focusPnlCents: 0, greeks: goldenGreeks() },
            { focusValueCents: 9000, focusPnlCents: 21345, greeks: goldenGreeks() }
          ],
          steps: positionSteps,
          animation: positionAnimation(underlyingSteps.length, underlyingSteps.length - 1, 21, {
            finalOptionExpiration: expiration,
            boundaryReason: 'HORIZON_END_OPTION_OUTLIVES_TRACK',
            exposureResolvedAtBoundary: false
          })
        }]
      }
    }
  };
}

/* §7.2 package-price receipt, exactly as PackagePriceReceipt serializes it: all sixteen keys,
   explicit nulls for what is genuinely unknown. Fixtures build it through one helper so a surface
   that reads a field the server never sends fails here rather than on the owner's screen. */
/* observedAt is `Long observedAt` on the record — epoch MILLISECONDS of the quotes the price was
   struck from, produced by PackagePriceReceipt.observedAtOf over the legs' asOfEpochMs stamps.
   This fixture used to hand the browser an ISO-8601 string, which no producer ever sends. That
   single untruthful key is why the suite stayed green while the served order dock printed the raw
   number 1784913960000 to the owner: the mock silently satisfied a renderer that only works on
   strings. A fixture that lies about the wire type cannot catch a formatter that trusts it. */
const RECEIPT_OBSERVED_AT_EPOCH_MS = 1784913960000; // 2026-07-24T17:26:00Z
function priceReceipt({ quantity = 1, optionNetPremiumCents = null, stockCashFlowCents = 0,
  grossPackageNetCents = null, openingFeesCents = null, afterFeeNetCents = null,
  executableNetCents = null, restingLimitNetCents = null, valuationBasis = 'EXECUTABLE_BOOK',
  executability = 'IMMEDIATE', source = 'BACKEND_TEST_RECEIPT', freshness = 'FRESH',
  observedAt = RECEIPT_OBSERVED_AT_EPOCH_MS, fingerprint = 'price-fixture', feeSide = 'OPENING',
  unavailableReason = null } = {}) {
  const desk = { quantity, source, freshness, observedAt, fingerprint, feeSide };
  if (String(valuationBasis).toUpperCase() === 'UNAVAILABLE') {
    /* the canonical builder names this parameter `reason` */
    return unavailablePackagePrice(Object.assign({}, desk, {
      reason: unavailableReason || 'This package could not be priced.'
    }));
  }
  /* The desk's callers name the package net and let the option-only side follow, which is the
     opposite of the canonical builder's argument order; translate rather than re-derive. */
  const option = optionNetPremiumCents == null ? grossPackageNetCents : optionNetPremiumCents;
  const stock = grossPackageNetCents == null || option == null
    ? (stockCashFlowCents || 0) : grossPackageNetCents - option;
  const built = packagePrice(Object.assign({}, desk, {
    optionNetPremiumCents: option, stockCashFlowCents: stock, openingFeesCents,
    executableNetCents, restingLimitNetCents, valuationBasis, executability
  }));
  /* A few desk fixtures pin an after-fee net that differs from gross-minus-fees on purpose (a
     resting limit settles against the limit, not the book). Honour that explicitly. */
  return afterFeeNetCents == null ? built : Object.assign({}, built, { afterFeeNetCents });
}

function currentAvailability(overrides = {}) {
  return Object.assign({
    quoteAvailable: true, quoteUnavailableReason: null,
    closeAvailable: true, closeUnavailableReason: null,
    decisionPnlAvailable: true, decisionPnlUnavailableReason: null,
    popAvailable: true, popUnavailableReason: null,
    greeksAvailable: true, greeksUnavailableReason: null
  }, overrides);
}

function decisionPreview(requestBody, selected = candidate(), version = 14) {
  const instruction = requestBody.orderInstruction;
  const limit = instruction.limitNetCents;
  const naturalNetCents = selected.price.grossPackageNetCents;
  const executable = instruction.type === 'MARKET' || limit <= naturalNetCents;
  const isCustom = selected.id === CUSTOM_CANDIDATE_ID;
  const restingReason = executable ? null
    : 'Your limit is more favorable than the executable market. The order is RESTING and is not presently executable.';
  const rankedEndorsement = backendEndorsement(selected);
  const endorsement = executable && rankedEndorsement.endorsed
    ? rankedEndorsement
    : Object.assign({}, rankedEndorsement, {
        endorsed: false, status: 'COMPARISON',
        reasons: [restingReason || 'The exact mock MARKET package is blocked.']
      });
  const dockPrice = priceReceipt({ quantity: requestBody.qty,
    grossPackageNetCents: naturalNetCents,
    optionNetPremiumCents: selected.price.optionNetPremiumCents, openingFeesCents: 260,
    executableNetCents: naturalNetCents,
    restingLimitNetCents: executable ? null : limit,
    afterFeeNetCents: executable ? naturalNetCents - 260 : limit - 260,
    valuationBasis: executable ? 'EXECUTABLE_BOOK' : 'RESTING_LIMIT',
    executability: executable ? 'IMMEDIATE' : 'RESTING' });
  const fundingClass = selected.identity?.fundingClass || 'DEFINED_RISK';
  const capitalBasis = selected.identity?.capitalBasis || 'MAXIMUM_LOSS';
  const capitalUsedCents = selected.maxLossCents;
  const capitalCapCents = fundingClass === 'CASH_COLLATERAL' ? 5_000_000 : 1_000_000;
  const selectedCapital = capitalUsedCents == null ? null : {
    fundingClass,
    capitalBasis,
    capCents: capitalCapCents,
    usedCents: capitalUsedCents,
    remainingCents: Math.max(0, capitalCapCents - capitalUsedCents),
    overageCents: Math.max(0, capitalUsedCents - capitalCapCents),
    withinCap: capitalUsedCents <= capitalCapCents,
    basis: 'Mock exact-package capital measured by the backend fixture.',
    unavailableReason: null
  };
  return {
    plan: plan(version),
    selected,
    preview: {
      price: dockPrice,
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
    accountFit: selectedCapital ? {
      pctOfNlv: null, pctOfCashBp: null, pctOfMarginBp: null,
      pctOfRiskCapital: null, overRiskCapital: selectedCapital.withinCap ? null : true,
      selectedCapital
    } : null,
    requiredAcks: [],
    ackToken: 'ack-desk-test',
    order: { orderInstruction: instruction },
    endorsement,
    execution: executionDecision({
      reviewAllowed: executable,
      confirmAllowed: executable,
      reasons: restingReason ? [restingReason] : []
    })
  };
}

function scenarioResponse(marker, overrides = {}) {
  const valuationFingerprint = `valuation-${marker}`;
  const requestBody = overrides.requestBody && typeof overrides.requestBody === 'object'
    ? overrides.requestBody : null;
  const interaction = resolveInteraction(requestBody?.interaction);
  const exactSourcePathIndex = interaction?.sourcePathIndex == null
    ? null : Number(interaction.sourcePathIndex);
  const focusSourcePathIndex = exactSourcePathIndex == null ? 17 : exactSourcePathIndex;
  const hasConditioning = !!interaction
    || Array.isArray(requestBody?.waypoints) && requestBody.waypoints.length > 0
    || Array.isArray(requestBody?.pathWaypoints) && requestBody.pathWaypoints.length > 0;
  const selectionRule = exactSourcePathIndex != null
    ? 'EXACT_SOURCE_PATH'
    : hasConditioning ? 'NEAREST_AUTHORED_WAYPOINTS' : 'TERMINAL_QUANTILES';
  const targetRatio = marker === 'second' ? 1.05 : marker === 'invalid' ? 1.09 : 0.95;
  const hasRequestBody = requestBody != null;
  const requestedWaypoints = hasRequestBody
    ? (Array.isArray(requestBody.waypoints) ? requestBody.waypoints : [])
    : [{ dayIndex: 10, priceRatio: targetRatio, tolerance: 0.02 }];
  const requestedPathWaypoints = hasRequestBody && Array.isArray(requestBody.pathWaypoints)
    ? requestBody.pathWaypoints : [];
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
  const firstContextSourcePathIndex = focusSourcePathIndex === 9 ? 10 : 9;
  const secondContextSourcePathIndex = focusSourcePathIndex === 31 ? 32 : 31;
  const scenarioPricePaths = [
    { sourcePathIndex: firstContextSourcePathIndex, role: 'CONTEXT', prices: contextOne },
    { sourcePathIndex: focusSourcePathIndex, role: 'FOCUS', prices: focusPrices },
    { sourcePathIndex: secondContextSourcePathIndex, role: 'CONTEXT', prices: contextTwo }
  ];
  const scenarioGrid = focusPrices.map((unused, index) => ({
    step: index * 21,
    sessionProgress: index * 2.1
  }));
  const scenarioPnlPaths = scenarioPricePaths.map(row => ({
    sourcePathIndex: row.sourcePathIndex,
    role: row.role,
    steps: row.prices.map((price, step) => ({
      step: scenarioGrid[step].step, sessionProgress: scenarioGrid[step].sessionProgress,
      pnlCents: Math.round((price - 100) * 8200 + step * 420)
    }))
  }));
  const scenarioPnlBands = focusPrices.map((price, step) => {
    const middle = Math.round((price - 100) * 8200 + step * 420);
    return {
      step: scenarioGrid[step].step, sessionProgress: scenarioGrid[step].sessionProgress,
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
    conditioningPathWaypoints,
    interaction
  }, overrides.receipt || {});
  const scenarioCandidateId = receipt.selectedCandidateId || CANDIDATE_ID;
  return {
    testMarker: marker,
    plan: plan(14),
    ensemble: { id: ENSEMBLE_ID, fingerprint: ENSEMBLE_FINGERPRINT },
    receipt,
    paths: {
      totalPathCount: 500,
      selection: selectionRule,
      paths: scenarioPricePaths,
      bands: focusPrices.map((middle, index) => ({
        step: scenarioGrid[index].step,
        sessionProgress: scenarioGrid[index].sessionProgress,
        p10: middle - 4 - index * .2,
        p25: middle - 2 - index * .1,
        p50: middle,
        p75: middle + 2 + index * .1,
        p90: middle + 4 + index * .2
      })),
      receipt: {
        sourcePathCount: 500,
        returnedPathCount: 3,
        sourcePointCount: 211,
        returnedPointCount: scenarioGrid.length,
        withinToleranceCount: 18,
        focusSourcePathIndex,
        rule: selectionRule
      }
    },
    checkpoints: {
      displayPathRule: selectionRule,
      displayPathCount: scenarioPricePaths.length,
      displayPathSourceIndices: scenarioPricePaths.map(row => row.sourcePathIndex),
      focusSourcePathIndex,
      modelReceipt: { valuationFingerprint },
      animation: animationTrack(scenarioGrid.length, {
        sourceStepCount: 211, stepsPerDay: 10,
        anchorSpot: 100, horizonSessions: 21, baselineAtmIv: 0.30
      }),
      underlying: [
        { day: 0, p10: 100, p50: 100, p90: 100, focusPrice: 100, atmIv: 0.30 },
        { day: 10, p10: 98, p50: 102, p90: 110, focusPrice: targetRatio * 100, atmIv: 0.32 }
      ],
      underlyingSteps: focusPrices.map((focusPrice, index) => {
        const atmIv = .30 + index * .002;
        return {
          step: scenarioGrid[index].step,
          sessionProgress: scenarioGrid[index].sessionProgress,
          focusPrice,
          atmIv,
          moveFromSpotPct: focusPrice - 100,
          ivShiftPoints: (atmIv - .30) * 100
        };
      }),
      positions: [{
        key: `PROPOSED:${scenarioCandidateId}`,
        proposed: true,
        stepBands: scenarioPnlBands,
        displayPaths: scenarioPnlPaths,
        days: [
          { focusValueCents: -12345, focusPnlCents: 0, greeks: goldenGreeks() },
          { focusValueCents: 2500, focusPnlCents: 14845, greeks: goldenGreeks() }
        ],
        steps: scenarioPnlBands.map(row => ({
          step: row.step,
          sessionProgress: row.sessionProgress,
          focusValueCents: -12345 + row.pnlP50Cents,
          focusPnlCents: row.pnlP50Cents,
          greeks: goldenGreeks()
        })),
        animation: positionAnimation(scenarioGrid.length, scenarioGrid.length - 1, 21, {
          finalOptionExpiration: '2026-08-21',
          boundaryReason: 'HORIZON_END_OPTION_OUTLIVES_TRACK',
          exposureResolvedAtBoundary: false
        })
      }]
    }
  };
}

function positionScenarioResponse(body, options = {}) {
  const interaction = resolveInteraction(body.interaction);
  const exactSourcePathIndex = interaction?.sourcePathIndex == null
    ? null : Number(interaction.sourcePathIndex);
  const focusSourcePathIndex = exactSourcePathIndex == null ? 43 : exactSourcePathIndex;
  const conditioned = !!interaction
    || Array.isArray(body.pathWaypoints) && body.pathWaypoints.length > 0
    || Array.isArray(body.waypoints) && body.waypoints.length > 0;
  const selectionRule = exactSourcePathIndex != null
    ? 'EXACT_SOURCE_PATH'
    : conditioned ? 'NEAREST_AUTHORED_WAYPOINTS' : 'TERMINAL_QUANTILES';
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
  const projectionAnchorQuote = {
    symbol: 'AAPL',
    displayPrice: 222.22,
    markBasis: 'MID',
    priced: true,
    freshness: 'FRESH',
    source: 'BOOK_TEST_EXECUTABLE_RECEIPT',
    asOf: Date.parse('2026-07-20T15:42:00Z')
  };
  const scenarioProjection = {
    contractVersion: 'scenario-projection-1',
    basis: projectionAnchorQuote
      ? 'CURRENT_QUOTE_REBASED_SOURCE_RETURNS' : 'STORED_ENSEMBLE',
    sourceEnsembleId: BOOK_ENSEMBLE_ID,
    sourceEnsembleFingerprint: returnedFingerprint,
    anchorQuote: projectionAnchorQuote,
    anchorSpot: 222.22,
    anchorDate: '2026-07-20',
    horizonSessions: 29,
    transform: projectionAnchorQuote
      ? 'SCALE_EACH_SOURCE_PRICE_BY_PROJECTION_SPOT_OVER_SOURCE_SPOT_AND_TRUNCATE_V1'
      : 'IDENTITY',
    fingerprint: 'b50f52692cf37c94fb770df4c4695ebed98bb62a16cf5f0cc399031d4b8d62b2'
  };
  const normalizedPathWaypoints = Array.isArray(body.pathWaypoints) && body.pathWaypoints.length
    ? body.pathWaypoints
    : (body.waypoints || []).map(pin => ({
        sessionProgress: pin.dayIndex,
        priceRatio: pin.priceRatio,
        ...(pin.tolerance == null ? {} : { tolerance: pin.tolerance })
      }));
  const firstContextSourcePathIndex = focusSourcePathIndex === 11 ? 12 : 11;
  const secondContextSourcePathIndex = focusSourcePathIndex === 70 ? 71 : 70;
  const positionSourcePaths = [
    { sourcePathIndex: firstContextSourcePathIndex, role: 'CONTEXT', pnl: [24680, 13100, 18200, -8200, -28600, -42800] },
    { sourcePathIndex: focusSourcePathIndex, role: 'FOCUS', pnl: [24680, 15000, 17500, -5000, -25000, -43200] },
    { sourcePathIndex: secondContextSourcePathIndex, role: 'CONTEXT', pnl: [24680, 16400, 11200, 3300, -21400, -40100] }
  ];
  /* A CHECKPOINT SERIES whose lifecycle is named by PositionAnimation v2. Tests can vary the
     boundary without changing leg dates, proving the browser consumes the receipt rather than
     re-deriving a package life. */
  const POSITION_CHECKPOINTS = [0, 5, 11, 17, 23, 29];
  const POSITION_CHECKPOINT_DATES = ['2026-07-20', '2026-07-27', '2026-08-04', '2026-08-12', '2026-08-21', '2026-08-28'];
  const lifecycle = Object.assign({
    frameCount: POSITION_CHECKPOINTS.length,
    terminalFrameIndex: 4,
    terminalSessionProgress: POSITION_CHECKPOINTS[4],
    finalOptionExpiration: '2026-08-21',
    boundaryReason: 'FINAL_CASH_SETTLEMENT',
    exposureResolvedAtBoundary: true,
    unavailableReason: null
  }, options.positionAnimation || {});
  const positionStepBands = [24680, 15000, 17500, -5000, -25000, -43200]
    .map((middle, step) => ({
      step: POSITION_CHECKPOINTS[step], sessionProgress: POSITION_CHECKPOINTS[step],
      sessionDate: POSITION_CHECKPOINT_DATES[step],
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
      contractVersion: 'scenario-animation-2',
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
      anchorSpot: 222.22,
      conditioningAssumptions: { waypoints: body.waypoints },
      conditioningPathWaypoints: options.positionScenarioWrongPathWaypoints
        ? [{ sessionProgress: 1, priceRatio: 1.2 }]
        : normalizedPathWaypoints,
      requestedInteraction: body.interaction || null,
      projection: scenarioProjection,
      interactionTargetSpotCents: interaction && interaction.sourcePathIndex == null
        && interaction.movePct != null
        ? Math.round(22222 * (1 + interaction.movePct / 100))
        : null,
      interaction
    },
    paths: {
      totalPathCount: 500,
      selection: selectionRule,
      bandBasis: 'CONDITIONED_NEAREST_QUINTILE_PLUS_FULL_TOLERANCE_SET',
      bandPathCount: 100,
      bands: [222.22, 215.1, 218.2, 199.9, 190.3, 177.78].map((median, step) => ({
        step: POSITION_CHECKPOINTS[step]
          + (options.positionScenarioWrongProjectionGrid && step === 2 ? 1 : 0),
        sessionProgress: POSITION_CHECKPOINTS[step],
        p10: median - step * 2.4, p25: median - step * 1.1,
        p50: median, p75: median + step * 1.2, p90: median + step * 2.6
      })),
      paths: [
        {
          sourcePathIndex: firstContextSourcePathIndex,
          role: 'CONTEXT',
          prices: [222.22, 214.8, 217.4, 201.2, 194.8, 181.4]
        },
        {
          sourcePathIndex: focusSourcePathIndex,
          role: 'FOCUS',
          prices: [222.22, 215.1, 218.2, 199.9, 190.3, 177.78]
        },
        {
          sourcePathIndex: secondContextSourcePathIndex,
          role: 'CONTEXT',
          prices: [222.22, 217.4, 211.6, 215, 196.1, 183.2]
        }
      ],
      receipt: {
        requestedLimit: body.limit,
        returnedPathCount: 3,
        sourcePathCount: 500,
        sourcePointCount: 30,
        returnedPointCount: POSITION_CHECKPOINTS.length,
        withinToleranceCount: 21,
        rule: selectionRule,
        focusSourcePathIndex
      }
    },
    checkpoints: {
      displayPathRule: selectionRule,
      displayPathCount: positionSourcePaths.length,
      displayPathSourceIndices: positionSourcePaths.map(row => row.sourcePathIndex),
      focusSourcePathIndex,
      modelReceipt: {
        ensembleFingerprint: returnedFingerprint,
        focusPositionKey: expectedFocus,
        focusSourcePathIndex,
        valuationFingerprint,
        focusedPackageFingerprint,
        focusedPackageProvenance,
        scenarioProjection
      },
      animation: animationTrack(POSITION_CHECKPOINTS.length, {
        sourceStepCount: 30, anchorSpot: 222.22,
        horizonSessions: POSITION_CHECKPOINTS[POSITION_CHECKPOINTS.length - 1],
        baselineAtmIv: 0.27
      }),
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
      /* The dated session calendar rides UnderlyingStep — the one place the backend puts it.
         DisplayPositionStep is (step, sessionProgress, pnlCents) and carries no date, so a
         consumer looking for dates on the P/L steps finds none. */
      underlyingSteps: [222.22, 215.1, 218.2, 199.9, 190.3, 177.78].map((focusPrice, step) => ({
        step: POSITION_CHECKPOINTS[step], sessionProgress: POSITION_CHECKPOINTS[step],
        sessionDate: POSITION_CHECKPOINT_DATES[step], focusPrice,
        atmIv: [0.27, 0.30, 0.31, 0.34, 0.37, 0.39][step],
        moveFromSpotPct: (focusPrice / 222.22 - 1) * 100,
        ivShiftPoints: ([0.27, 0.30, 0.31, 0.34, 0.37, 0.39][step] - 0.27) * 100
      })),
      positions: [{
        key: expectedFocus,
        source: 'PRACTICE_TRADE',
        stepBands: positionStepBands,
        displayPaths: positionSourcePaths.map(row => ({
          sourcePathIndex: row.sourcePathIndex,
          role: row.role,
          steps: row.pnl.map((pnlCents, step) => ({
            step: POSITION_CHECKPOINTS[step], sessionProgress: POSITION_CHECKPOINTS[step], pnlCents
          }))
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
            step: POSITION_CHECKPOINTS[1], sessionProgress: POSITION_CHECKPOINTS[1], focusValueCents: 8830, focusPnlCents: 15000,
            greeks: {
              deltaShares: 31.8, gammaSharesPerDollar: 1.42,
              thetaCentsPerDay: -1090, vegaCentsPerPoint: 1620
            }
          },
          {
            step: POSITION_CHECKPOINTS[2], sessionProgress: POSITION_CHECKPOINTS[2], focusValueCents: 11330, focusPnlCents: 17500,
            greeks: {
              deltaShares: 28.4, gammaSharesPerDollar: 1.17,
              thetaCentsPerDay: -910, vegaCentsPerPoint: 1410
            }
          },
          {
            step: POSITION_CHECKPOINTS[3], sessionProgress: POSITION_CHECKPOINTS[3], focusValueCents: -11150, focusPnlCents: -5000,
            greeks: {
              deltaShares: 18.2, gammaSharesPerDollar: 0.88,
              thetaCentsPerDay: -720, vegaCentsPerPoint: 1120
            }
          },
          {
            step: POSITION_CHECKPOINTS[4], sessionProgress: POSITION_CHECKPOINTS[4], focusValueCents: -31150, focusPnlCents: -25000,
            greeks: {
              deltaShares: 9.7, gammaSharesPerDollar: 0.55,
              thetaCentsPerDay: -480, vegaCentsPerPoint: 820
            }
          },
          {
            step: POSITION_CHECKPOINTS[5], sessionProgress: POSITION_CHECKPOINTS[5], focusValueCents: -49350, focusPnlCents: -43200,
            greeks: {
              deltaShares: 4.5, gammaSharesPerDollar: 0.32,
              thetaCentsPerDay: -315, vegaCentsPerPoint: 640
            }
          }
        ],
        animation: lifecycle
      }]
    }
  };
}

async function installBackend(page, options = {}) {
  await page.addInitScript(() => {
    /* Test-only receipt lookup. Product code draws the server-owned curve and does not expose a
       second financial helper merely so tests can inspect fixture checkpoints. */
    window.__testNearestPayoffPoint = (candidate, price) => {
      const points = candidate && Array.isArray(candidate.payoffPoints)
        ? candidate.payoffPoints : [];
      if (!points.length || price < points[0].price || price > points[points.length - 1].price) {
        return null;
      }
      return points.reduce((best, point) => !best
        || Math.abs(point.price - price) < Math.abs(best.price - price) ? point : best, null);
    };
  });
  const requests = [];
  let scenarioCalls = 0;
  let positionScenarioCalls = 0;
  let scenarioFailuresRemaining = Number(options.scenarioFailures || 0);
  let selectFailuresRemaining = Number(options.selectFailures || 0);
  let declarationFailuresRemaining = Number(options.declarationFailures || 0);
  let staleCreateResponsesRemaining = options.staleCreatePlan ? 1 : 0;
  let planVersion = 10;
  let selectedCandidate = null;
  const baseMarketLane = options.baseMarketLane || options.activeMarketLane || 'OBSERVED';
  const baseWorld = options.baseWorld
    || (String(baseMarketLane).toUpperCase() === 'DEMO' ? 'demo' : 'observed');
  let activeWorld = options.activeWorld || baseWorld;
  let activeMarketLane = options.activeMarketLane || baseMarketLane;
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
  const strategyCandidates = sourceCandidates.map(row => Object.assign({}, row, {
    ...(options.marketFreshness ? { freshness: marketFreshness } : {}),
    evaluation: Object.assign({}, row.evaluation || {}, {
      /* StrategyEvaluation serializes this computed property on every real candidate. Keeping it
         only on the competition summary makes the mock wire less complete than production and
         turns an endorsed row into an unbadged comparison after any re-render. */
      endorsement: backendEndorsement(row)
    })
  }));
  const strategyRejected = options.strategyRejected || [];
  const strategyNotes = options.strategyNotes || [];
  let hasCompetition = false;
  let strategyRunNumber = 0;
  let strategyRunId = null;
  let strategyMarketReceipt = null;
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
  let storedRehearsals = Array.isArray(options.rehearsals)
    ? JSON.parse(JSON.stringify(options.rehearsals)) : [];
  let rehearsalFailuresRemaining = Number(options.failRehearsalTimes
    || (options.failRehearsalOnce ? 1 : 0));
  let researchFailuresRemaining = Number(options.failResearchTimes
    || (options.failResearchOnce ? 1 : 0));
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
    const deskPick = strategyCandidates.find(row => backendEndorsement(row).endorsed) || null;
    return {
      runId: strategyRunId,
      state: 'CURRENT',
      inputHash: strategyInputHash,
      createdAt: '2026-07-20T16:00:00Z',
      result: {
        candidates: strategyCandidates,
        rejected: strategyRejected,
        notes: strategyNotes,
        deskPickCandidateId: deskPick?.id || null,
        deskPickEndorsement: deskPick
          ? backendEndorsement(deskPick)
          : {
              endorsed: false, status: 'COMPARISON', candidateId: null,
              reasons: ['No mock package cleared every promotion gate.'],
              basis: 'Mock backend decision-policy receipt.'
            },
        strategyRunId,
        strategyRunState: 'CURRENT'
      }
    };
  }

  function currentStrategyMarketReceipt() {
    return {
      spotCents: Math.round(((quote.bid + quote.ask) / 2) * 100),
      quoteAsOf: Number(quote.asOf),
      quoteSource: String(quote.source),
      quoteFreshness: String(quote.freshness),
      atmIv: Number(chainIv)
    };
  }

  function strategyMarketCurrent() {
    const now = currentStrategyMarketReceipt();
    return strategyMarketReceipt
      && strategyMarketReceipt.spotCents === now.spotCents
      && strategyMarketReceipt.quoteAsOf === now.quoteAsOf
      && strategyMarketReceipt.quoteSource === now.quoteSource
      && strategyMarketReceipt.quoteFreshness === now.quoteFreshness
      && Math.abs(strategyMarketReceipt.atmIv - now.atmIv) < 0.000001;
  }

  // Broker import: one preview carrying an exact-fill package, a package-net-only package that
  // must be quarantined rather than posted, and a row the parser refused with its reason.
  const brokerImportAccounts = options.brokerImportAccounts || [
    { id: 'acct-taxable', name: 'Individual ••••4417' },
    { id: 'acct-ira', name: 'Roth IRA ••••9002' }
  ];
  const brokerImportPreviews = [];
  const brokerImportConfirms = [];
  const brokerImportPreview = options.brokerImportPreview || {
    parsed: {
      parserVersion: 'broker-import-1', sourceSystem: 'ETRADE', sourceLabel: 'E*TRADE / Power E*TRADE',
      previewFingerprint: 'preview-fp-1', rowsRead: 7,
      groups: [
        {
          groupKey: 'grp-exact', externalRef: 'ETR-88120', accountFingerprint: 'fp-taxable',
          broker: 'E*TRADE', occurredAt: '2026-07-21T14:31:00Z', packageNetCents: 214,
          feesCents: 130, kind: 'EXACT_FILLS', warnings: [], payloadFingerprint: 'pay-1',
          legs: [
            { line: 3, legNo: 1, instrumentType: 'OPTION', action: 'SELL', positionEffect: 'OPEN',
              symbol: 'AMD', optionType: 'PUT', strike: '160.00', expiration: '2026-08-21',
              quantity: 2, multiplier: 100, reportedPrice: '3.15', pastedMark: null,
              pastedMarkAsOf: null, checks: [] },
            { line: 4, legNo: 2, instrumentType: 'OPTION', action: 'BUY', positionEffect: 'OPEN',
              symbol: 'AMD', optionType: 'PUT', strike: '150.00', expiration: '2026-08-21',
              quantity: 2, multiplier: 100, reportedPrice: '1.01', pastedMark: null,
              pastedMarkAsOf: null,
              checks: [{ field: 'positionEffect', value: 'OPEN', sourceColumn: null,
                verify: true, note: 'the export did not state an opening or closing effect' }] }
          ]
        },
        {
          groupKey: 'grp-net', externalRef: 'ETR-88121', accountFingerprint: 'fp-taxable',
          broker: 'E*TRADE', occurredAt: '2026-07-21T15:02:00Z', packageNetCents: -450,
          feesCents: 65, kind: 'PACKAGE_NET_PENDING',
          warnings: ['This export reported only the package total for these legs.'],
          payloadFingerprint: 'pay-2',
          legs: [
            { line: 5, legNo: 1, instrumentType: 'OPTION', action: 'BUY', positionEffect: 'OPEN',
              symbol: 'NVDA', optionType: 'CALL', strike: '190.00', expiration: '2026-09-18',
              quantity: 1, multiplier: 100, reportedPrice: null, pastedMark: null,
              pastedMarkAsOf: null, checks: [] }
          ]
        }
      ],
      quarantine: [
        { line: 6, externalRef: 'ETR-88122', reason: 'the expiration column was empty' }
      ]
    },
    marks: [
      { groupKey: 'grp-exact', externalRef: 'ETR-88120', legs: [
        { legNo: 1, pastedMark: null, pastedMarkAsOf: null, pastedMarkIsCurrent: false,
          currentBid: '3.05', currentAsk: '3.25', currentMid: '3.15', provenance: 'OBSERVED',
          age: '2m', source: 'Cboe', observedEligible: true, currentEvidence: 'QUOTE', note: null },
        { legNo: 2, pastedMark: null, pastedMarkAsOf: null, pastedMarkIsCurrent: false,
          currentBid: null, currentAsk: null, currentMid: null, provenance: null,
          age: null, source: null, observedEligible: false, currentEvidence: null,
          note: 'no separately sourced mark is available for this leg' }
      ] }
    ],
    note: 'Preview only. No tracked account, lot, pending import, Plan, or order was changed.'
  };
  const brokerImportConfirm = options.brokerImportConfirm || {
    selected: 1, exactTransactions: 1, pendingImports: 0, duplicates: 0,
    items: [{ groupKey: 'grp-exact', externalRef: 'ETR-88120', kind: 'TRANSACTION',
      id: 'txn-1', duplicate: false, portfolioAccountId: 'acct-taxable', symbol: 'AMD', lots: [] }],
    note: 'One package was written to the ledger.'
  };

  // The persisted workspace context. `options.workspaceContext` seeds a restored session;
  // `options.workspaceUnreadable` / `options.workspaceTransition` seed the two receipts the desk
  // must say out loud instead of silently starting blank.
  let workspaceRev = options.workspaceRev == null ? 3 : options.workspaceRev;
  let workspaceTransition = options.workspaceTransition || null;
  let workspaceConflictsRemaining = Number(options.workspaceConflicts || 0);
  const workspacePatches = [];
  const adoptionRequests = [];
  const workspaceContext = Object.assign({
    version: 1, generation: 1, world: activeWorld, datasetId: activeDataset,
    marketLane: activeMarketLane, accountId: 'acct-1',
    scopeType: 'BROAD_MARKET', sectorKey: null, focusedSubject: 'BOOK', focusedSymbol: null,
    focusedPositionId: null, focusedIdeaId: null, focusedEvaluationId: null,
    goal: null, view: null, horizonDays: null, riskPosture: null,
    targetCents: null, shareQuantity: null, assignmentPreference: null,
    routeState: 'book', returnFocus: null
  }, options.workspaceContext || {});
  function workspaceState() {
    return {
      rev: workspaceRev, updatedAt: '2026-07-25T12:00:00Z', supportedVersion: 1,
      world: workspaceContext.world, datasetId: workspaceContext.datasetId,
      marketLane: workspaceContext.marketLane,
      accountId: workspaceContext.accountId,
      context: options.workspaceUnreadable || options.workspaceEmpty ? null : workspaceContext,
      transition: workspaceTransition,
      unreadable: options.workspaceUnreadable || null
    };
  }

  function identityHistory(symbol, identity) {
    const dataset = String(identity.datasetId || '');
    const anchor = dataset === SIM_DATASET_ID ? 80
      : dataset === DATASET_ID ? 100 : 120;
    return {
      symbol,
      source: `CACHE_TEST_${dataset}`,
      freshness: 'FRESH',
      candles: Array.from({ length: 12 }, (_, index) => ({
        date: `2026-07-${String(10 + index).padStart(2, '0')}`,
        open: anchor + index - 0.5,
        high: anchor + index + 1,
        low: anchor + index - 1,
        close: anchor + index,
        volume: 1000000 + index
      })),
      overlays: {
        sma20: Array(12).fill(null), sma50: Array(12).fill(null),
        bandUp: Array(12).fill(null), bandDn: Array(12).fill(null)
      },
      coverage: { availableSessions: 12, requestedSessions: 12, coveragePct: 100 }
    };
  }

  function identityExpectedMove(symbol, expiry, identity) {
    const dataset = String(identity.datasetId || '');
    const median = dataset === SIM_DATASET_ID ? 80
      : dataset === DATASET_ID ? 100 : 120;
    return {
      symbol,
      expiration: expiry,
      available: true,
      p16: median - 10,
      p50: median,
      p84: median + 10,
      p16MovePct: -10,
      p84MovePct: 10,
      atmIv: 0.30,
      horizonSessions: 24,
      anchorFreshness: 'FRESH',
      source: `CACHE_TEST_${dataset}`
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
    const exactPlanMatch = url.pathname.match(/^\/api\/plans\/([^/]+)$/);
    const exactPlanId = exactPlanMatch && decodeURIComponent(exactPlanMatch[1]);
    const rehearsalsMatch = url.pathname.match(/^\/api\/plans\/([^/]+)\/rehearsals$/);
    const rehearsalsPlanId = rehearsalsMatch && decodeURIComponent(rehearsalsMatch[1]);
    const homeResearchMatch = url.pathname.match(
      /^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news))?$/);
    const homeResearchSymbol = homeResearchMatch
      && decodeURIComponent(homeResearchMatch[1]).toUpperCase();
    const homeResearchPart = homeResearchMatch && (homeResearchMatch[2] || 'research');
    const expectedMoveMatch = url.pathname.match(
      /^\/api\/research\/([^/]+)\/expected-move$/);
    const expectedMoveSymbol = expectedMoveMatch
      && decodeURIComponent(expectedMoveMatch[1]).toUpperCase();
    // Capture the identity at request arrival. Tests may transition the workspace while an old
    // request is deliberately delayed; its eventual response must remain an old-world receipt.
    const requestMarketIdentity = {
      world: activeWorld, lane: activeMarketLane, datasetId: activeDataset
    };

    let response;
    if (method === 'GET' && url.pathname === '/api/auth/me') {
      response = {
        authEnabled: false, authenticated: true, user: null,
        loginUrl: '/oauth2/authorization/google', logoutUrl: '/logout'
      };
    } else if (method === 'GET' && url.pathname === '/api/strategies' && catalogDocument) {
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
        baselineWorld: baseWorld,
        revision: worldRevision,
        epoch: worldEpoch,
        workspace: workspaceState()
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
      const priorWorld = activeWorld;
      const priorLane = activeMarketLane;
      activeWorld = body.world;
      activeMarketLane = activeWorld === baseWorld ? baseMarketLane : 'SIMULATED';
      activeDataset = activeWorld === baseWorld ? DATASET_ID : SIM_DATASET_ID;
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
      if (priorWorld !== activeWorld) {
        const cleared = ['scopeType', 'sectorKey', 'focusedSubject', 'focusedSymbol',
          'focusedPositionId', 'focusedIdeaId', 'focusedEvaluationId', 'targetCents',
          'shareQuantity', 'routeState', 'returnFocus'].filter(field => workspaceContext[field] != null);
        cleared.forEach(field => { workspaceContext[field] = null; });
        workspaceContext.generation = Number(workspaceContext.generation || 0) + 1;
        workspaceRev += 1;
        workspaceTransition = {
          fromWorld: priorWorld, toWorld: activeWorld, cleared,
          reason: `the active market changed from ${priorWorld} to ${activeWorld}`
        };
      } else if (priorLane !== activeMarketLane) {
        workspaceRev += 1;
        workspaceTransition = null;
      }
      workspaceContext.world = activeWorld;
      workspaceContext.datasetId = activeDataset;
      workspaceContext.marketLane = activeMarketLane;
      response = {
        world: activeWorld, baselineWorld: baseWorld, revision: worldRevision, epoch: worldEpoch,
        workspace: workspaceState()
      };
    } else if (method === 'GET' && url.pathname === '/api/account') {
      response = { account: {
        id: ACCOUNT_ID,
        name: 'Desk practice account',
        cashCents: bookDocuments.summary.cashCents,
        reservedCents: bookDocuments.summary.reservedCents,
        buyingPowerCents: bookDocuments.summary.buyingPowerCents
      } };
    } else if (method === 'GET' && url.pathname === '/api/portfolio/book') {
      if (options.bookCoreDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.bookCoreDelayMs));
      }
      response = practiceBookDocument(bookDocuments);
      /* A dedicated adversarial case may poison the otherwise-static TradeRecord with current
         looking fields. Production never sends them; the test proves marksByTrade remains the
         sole current authority even if a future compatibility serializer accidentally does. */
      if (options.bookTradeCurrentConflict && response.snapshot.activeTrades[0]) {
        Object.assign(response.snapshot.activeTrades[0], options.bookTradeCurrentConflict);
      }
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
    } else if (method === 'GET' && url.pathname === '/api/plans/portfolio') {
      response = { plans: bookDocuments.planPortfolio, market: activeMarketLane };
    } else if (method === 'GET' && url.pathname === '/api/universe') {
      response = {
        active: {
          source: 'test', sectorKey: 'CORE', label: 'Deterministic test universe',
          symbols: options.universeSymbols || ['AMD', 'AAPL']
        },
        scout: {
          source: 'CURATED', label: 'Curated cross-sector opportunity universe',
          symbols: options.scoutSymbols || options.universeSymbols || ['AMD', 'AAPL']
        },
        sectors: options.universeSectors || [], world: activeWorld, lane: activeMarketLane
      };
    } else if (method === 'GET' && options.identityStampedMarketData
        && homeResearchSymbol && homeResearchPart === 'history') {
      const delay = Number((options.identityMarketDelayByDataset || {})[
        requestMarketIdentity.datasetId] || 0);
      if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay));
      response = identityHistory(homeResearchSymbol, requestMarketIdentity);
    } else if (method === 'GET' && options.identityStampedMarketData
        && expectedMoveSymbol) {
      const delay = Number((options.identityMarketDelayByDataset || {})[
        requestMarketIdentity.datasetId] || 0);
      if (delay > 0) await new Promise(resolve => setTimeout(resolve, delay));
      response = identityExpectedMove(expectedMoveSymbol,
        url.searchParams.get('expiry'), requestMarketIdentity);
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
      positionScenarioCalls += 1;
      response = positionScenarioResponse(body,
        options.positionScenarioWrongProjectionGridOnce
          ? Object.assign({}, options, {
              positionScenarioWrongProjectionGrid: positionScenarioCalls === 1
            })
          : options);
    }
    else if (method === 'POST' && url.pathname === '/api/position-transformations/preview') {
      if (options.positionTransformationError) {
        await route.fulfill({ status: 422, contentType: 'application/json',
          body: JSON.stringify({ error: options.positionTransformationError }) });
        return;
      }
      response = options.positionTransformationPreview || {
        transformation: {
          action: body.action,
          beforeIdentity: { label: 'Debit call spread' },
          afterIdentity: body.action === 'CLOSE' ? { label: 'Cash' } : { label: 'Debit call spread' },
          beforeRisk: { maxLossCents: 43210, reserveCents: 43210 },
          afterRisk: body.action === 'CLOSE' ? null : { maxLossCents: 21605, reserveCents: 21605 },
          beforeObligations: { putAssignmentCashCents: 0, callDeliveryShares: 200 },
          afterObligations: { putAssignmentCashCents: 0,
            callDeliveryShares: body.action === 'CLOSE' ? 0 : 100 },
          delta: { maxLossCents: body.action === 'CLOSE' ? -43210 : -21605,
            reserveCents: body.action === 'CLOSE' ? -43210 : -21605,
            putAssignmentCashCents: 0, callDeliveryShares: body.action === 'CLOSE' ? -200 : -100 },
          identityChanged: true,
          applicable: true,
          realizedClosingCents: 24160,
          warnings: body.action === 'PARTIAL_CLOSE'
            ? ['Partial close: 1 of 2 packages close; 1 survives as Debit call spread.'] : [],
          fingerprint: 'fixture-position-transformation-preview'
        },
        closingCashCents: body.action === 'CLOSE' ? -37580 : -18790,
        closingFeesCents: body.action === 'CLOSE' ? 520 : 260,
        actionRealizedPnlCents: body.action === 'CLOSE' ? 48320 : 24160,
        realizedPnlToDateCents: body.action === 'CLOSE' ? 48320 : 24160,
        previewToken: '1784563200.fixture-signed-preview',
        expiresAt: '2026-07-20T16:02:00Z'
      };
    }
    else if (method === 'POST' && url.pathname === '/api/position-transformations/apply') {
      response = options.positionTransformationApplied || {
        receiptId: 'prec_fixture_position_action',
        transformation: {
          action: body.action, applicable: true,
          fingerprint: 'fixture-position-transformation-preview'
        },
        trade: bookDocuments.tradeDetail && bookDocuments.tradeDetail.trade,
        plan: bookDocuments.management && bookDocuments.management.plan,
        management: bookDocuments.management && bookDocuments.management.management,
        actionRealizedPnlCents: body.action === 'CLOSE' ? 48320 : 24160,
        realizedPnlToDateCents: body.action === 'CLOSE' ? 48320 : 24160
      };
    }
    else if (method === 'POST' && url.pathname === '/api/research/scout') {
      if (options.scoutDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.scoutDelayMs));
      }
      response = options.scoutResponse || {
        picks: [],
        searched: Array.isArray(body && body.universe) ? body.universe.length : 0
      };
    }
    else if (method === 'GET' && url.pathname === '/api/quotes') {
      if (options.quoteBatchError) {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({
            error: 'market_unavailable',
            detail: options.quoteBatchError
          })
        });
        return;
      }
      const requested = String(url.searchParams.get('symbols') || quote.symbol || 'AMD')
        .split(',').map(symbol => symbol.trim().toUpperCase()).filter(Boolean);
      response = {
        marketLane: activeMarketLane,
        world: activeWorld,
        /* §5.5: a batch row IS a typed QuoteView — the same receipt the single-symbol Research
           document carries — so the browser passes it through instead of deciding a display price
           from `last` and the previous close. */
        quotes: requested.map(symbol => {
          const researchQuote = bookDocuments.research
            && String(bookDocuments.research.symbol || '').toUpperCase() === symbol
            ? bookDocuments.research.quote : null;
          const source = researchQuote || quote;
          return quoteView(source, { symbol, refreshing: false });
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
        quote: quoteView(quote, {
          displayPrice: Object.prototype.hasOwnProperty.call(options, 'researchDisplayPrice')
            ? options.researchDisplayPrice : (quote.bid + quote.ask) / 2,
          markBasis: options.researchMarkBasis || null,
          last: options.researchPriceIsPreviousClose === true ? null : quote.last,
          prevClose: options.researchPriceIsPreviousClose === true
            ? (Object.prototype.hasOwnProperty.call(options, 'researchDisplayPrice')
              ? options.researchDisplayPrice : quote.prevClose) : quote.prevClose
        }),
        marketLane: activeMarketLane,
        evidence: {
          summary: quote.evidence,
          inputs: { quote: quote.evidence }
        },
        expirations: ['2026-08-21'],
        planEligible: true,
        planEligibility: 'Ready for the active observed market.'
      };
    } else if (method === 'GET' && url.pathname === '/api/research/AMD/expirations') {
      response = { asOfDate: '2026-07-20',
        expirations: [{ date: '2026-08-21', tradingSessions: 24, calendarDays: 32 }] };
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
    } else if (method === 'GET' && rehearsalsPlanId) {
      response = { rehearsals: storedRehearsals };
    } else if (method === 'POST' && rehearsalsPlanId) {
      if (rehearsalFailuresRemaining > 0) {
        rehearsalFailuresRemaining -= 1;
        await route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: 'The rehearsal service is temporarily unavailable.' }) });
        return;
      }
      if (!body || Number(body.expectedVersion) !== Number(planVersion)) {
        await route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: 'Plan version conflict.' }) });
        return;
      }
      const envelope = storedEnsemble && storedEnsemble.ensemble;
      const created = {
        worldId: `sim-rehearsal-${storedRehearsals.length + 1}`,
        accountId: `acct-rehearsal-${storedRehearsals.length + 1}`,
        planId: rehearsalsPlanId,
        ensembleId: envelope && envelope.id,
        fingerprint: envelope && envelope.fingerprint,
        pathIndex: body.pathIndex == null ? 1 : Number(body.pathIndex),
        selection: String(body.selection || 'TYPICAL').toUpperCase(),
        symbol: currentPlanSymbol,
        modelVersion: 'desk-rehearsal-test-v1',
        rateAnnual: 0.043,
        stepSeconds: 1,
        knots: 46,
        status: 'READY',
        createdAt: '2026-07-26T18:00:00Z'
      };
      storedRehearsals.unshift(created);
      planVersion += 1;
      response = { rehearsal: created, plan: currentPlan() };
    } else if (method === 'GET' && url.pathname === '/api/plans') {
      response = {
        world: activeWorld,
        market: activeMarketLane,
        plans: Array.isArray(options.listedPlans)
          ? options.listedPlans
          : (activeWorld === 'observed' || simulatedPlanCreated ? [currentPlan()] : [])
      };
    } else if (method === 'GET' && url.pathname === activePlanPath) {
      // The active Plan detail endpoint is the canonical mutable document. A bounded Home
      // portfolio row is only a navigation snapshot and may be one version behind after an
      // accepted declaration/selection mutation.
      response = currentPlan();
    } else if (method === 'GET' && exactPlanId && bookPlanIds.has(exactPlanId)) {
      const row = (bookDocuments.planPortfolio || [])
        .find(entry => String(entry?.plan?.id || '') === exactPlanId);
      response = row && row.plan;
    } else if (method === 'GET' && Array.isArray(options.listedPlans)
        && options.listedPlans.some(row => String(row.id) === decodeURIComponent(url.pathname.slice('/api/plans/'.length)))
        && url.pathname.startsWith('/api/plans/')) {
      response = options.listedPlans.find(row => String(row.id)
        === decodeURIComponent(url.pathname.slice('/api/plans/'.length)));
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
      if (body && Object.prototype.hasOwnProperty.call(body, 'intent')) {
        planOverrides.intent = body.intent;
      }
      if (body && Object.prototype.hasOwnProperty.call(body, 'originPlanId')) {
        planOverrides.originPlanId = body.originPlanId;
      }
      for (const key of ['thesis', 'targetCents', 'riskMode', 'holdingsShares',
        'costBasisCents', 'priceAssumptionCents', 'assignmentPreference']) {
        if (body && Object.prototype.hasOwnProperty.call(body, key)) planOverrides[key] = body[key];
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
      for (const key of ['thesis', 'horizonDays', 'targetCents', 'riskMode', 'holdingsShares',
        'costBasisCents', 'priceAssumptionCents', 'assignmentPreference']) {
        if (Object.prototype.hasOwnProperty.call(body, key)) planOverrides[key] = body[key];
      }
      for (const key of body.clear || []) {
        if (['thesis', 'horizonDays', 'targetCents', 'riskMode', 'holdingsShares',
          'costBasisCents', 'priceAssumptionCents', 'assignmentPreference'].includes(key)) {
          planOverrides[key] = null;
        }
      }
      planOverrides.contextRev = Number(planOverrides.contextRev || 0) + 1;
      planVersion += 1;
      selectedCandidate = null;
      storedEnsemble = null;
      storedOutcomes = [];
      hasCompetition = false;
      response = currentPlan();
    } else if (method === 'GET' && url.pathname === `${activePlanPath}/strategy/latest`) {
      const current = strategyMarketCurrent();
      response = hasCompetition ? {
        strategy: strategyState(),
        currency: current ? {
          current: true, status: 'CURRENT',
          reason: 'The mock backend confirms this strategy run is current.'
        } : {
          current: false, status: 'MARKET_CHANGED',
          reason: 'The mock backend found a newer quote or volatility receipt.'
        },
        ...(selectedCandidate ? { selected: selectedCandidate } : {})
      } : {};
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/strategy/adopt`) {
      adoptionRequests.push(body);
      if (options.adoptionRefusal) {
        await route.fulfill({ status: 422, contentType: 'application/json',
          body: JSON.stringify({ error: options.adoptionRefusal }) });
        return;
      }
      planVersion += 1;
      // Adoption replaces the competition with the ONE package the scanned row showed.
      const adoptedCandidate = Object.assign({}, strategyCandidates[0], {
        id: 'cand-adopted', sourceEvaluationId: body.evaluationId
      });
      strategyCandidates.splice(0, strategyCandidates.length, adoptedCandidate);
      selectedCandidate = adoptedCandidate;
      strategyMarketReceipt = currentStrategyMarketReceipt();
      hasCompetition = true;
      const adoptedStrategy = strategyState();
      adoptedStrategy.result = {
        candidate: adoptedCandidate,
        strategyRunId: adoptedStrategy.runId,
        strategyRunState: 'CURRENT'
      };
      response = {
        plan: currentPlan(),
        strategy: adoptedStrategy,
        identity: { evaluationId: body.evaluationId, key: `mock:${body.evaluationId}` },
        evaluationId: body.evaluationId
      };
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/strategy/run`) {
      if (options.strategyRunDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.strategyRunDelayMs));
      }
      if (selectedCandidate && strategyCandidates.some(row => row.id === selectedCandidate.id)) {
        selectedCandidate = null;
      }
      strategyRunNumber += 1;
      strategyRunId = `strategy_run_${strategyRunNumber}`;
      strategyMarketReceipt = currentStrategyMarketReceipt();
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
      strategyMarketReceipt = currentStrategyMarketReceipt();
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
      const interaction = body.interaction || null;
      const terminal = interaction
        ? interaction.sourcePathIndex == null
          ? 1 + Number(interaction.movePct || 0) / 100
          : 1.05
        : requestedPins[requestedPins.length - 1].priceRatio;
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
      if (options.latestRevaluesSelectedCandidate && selectedCandidate) {
        const storedRef = storedEnsemble.ensemble;
        const storedPreview = storedEnsemble.preview;
        const storedReceipt = storedPreview.receipt;
        response = ensemble(planVersion, {
          id: storedRef.id,
          fingerprint: storedRef.fingerprint,
          symbol: currentPlanSymbol,
          spot: storedReceipt.anchorSpot,
          asOf: Date.parse(storedReceipt.asOf),
          atmIv: storedReceipt.spec.volAnnual,
          expiration: storedPreview.marketImplied.expiration,
          candidateId: selectedCandidate.id
        });
        response.plan = currentPlan(planVersion);
        response.preview.receipt.worldId = activeWorld;
        response.preview.receipt.datasetId = activeDataset;
        response.preview.receipt.anchorSource = quote.source;
        response.preview.receipt.anchorFreshness = quote.freshness;
      }
      const storedReceipt = response.preview.receipt;
      const currentSpot = (quote.bid + quote.ask) / 2;
      const currentAsOf = new Date(quote.asOf).toISOString();
      const current = Math.round(Number(storedReceipt.anchorSpot) * 100)
          === Math.round(currentSpot * 100)
        && String(storedReceipt.asOf) === currentAsOf
        && String(storedReceipt.anchorSource) === String(quote.source)
        && String(storedReceipt.anchorFreshness) === String(quote.freshness)
        && Math.abs(Number(storedReceipt.spec?.volAnnual) - Number(chainIv)) < 0.000001;
      response.currency = current ? {
        current: true,
        status: 'CURRENT',
        reason: 'The mock server confirms the Plan, quote, volatility, rate, and display receipts.'
      } : {
        current: false,
        status: 'MARKET_CHANGED',
        reason: 'The mock server found a newer quote or volatility receipt.'
      };
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
        atmIv: chainIv, expiration: currentExpiration,
        candidateId: (selectedCandidate && selectedCandidate.id)
          || (strategyCandidates[0] && strategyCandidates[0].id)
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
          maxLossCents: 0,
          maxProfitCents: 0,
          reserveCents: 0,
          blockReasons: [reason],
          legs: [],
          payoff: []
        });
        response.preview.price = priceReceipt({ optionNetPremiumCents: null, grossPackageNetCents: null,
          openingFeesCents: null, afterFeeNetCents: null, executableNetCents: null,
          valuationBasis: 'UNAVAILABLE', executability: 'UNAVAILABLE',
          fingerprint: null, unavailableReason: reason });
        response.guardrails = { level: 'WARN', blockReasons: [], warnings: [reason] };
        response.endorsement = {
          endorsed: false, status: 'COMPARISON', candidateId: selectedCandidate?.id || null,
          reasons: [reason], basis: 'Mock exact-package receipt.'
        };
        response.execution = executionDecision({
          reviewAllowed: false, confirmAllowed: false, reasons: [reason]
        });
      }
      if (options.blockDecisionPreview) {
        response.preview.ok = false;
        const reasons = response.preview.blockReasons?.length
          ? response.preview.blockReasons : ['The exact instruction is blocked.'];
        response.execution = executionDecision({
          reviewAllowed: false, confirmAllowed: false,
          reasons
        });
        response.preview.blockReasons = ['The exact package exceeds the backend loss limit.'];
        response.guardrails = {
          level: 'BLOCK',
          blockReasons: ['The exact package exceeds the backend loss limit.'],
          warnings: []
        };
        response.accountFit = { overRiskCapital: true };
        response.endorsement = {
          endorsed: false, status: 'COMPARISON', candidateId: selectedCandidate?.id || null,
          reasons: response.guardrails.blockReasons, basis: 'Mock exact-package receipt.'
        };
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
    } else if (method === 'GET' && url.pathname === '/api/workspace') {
      if (options.workspaceRequestFailure) {
        await route.fulfill({ status: 503, contentType: 'application/json',
          body: JSON.stringify({ error: 'The workspace store is temporarily unavailable.' }) });
        return;
      }
      if (options.workspaceDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.workspaceDelayMs));
      }
      response = workspaceState();
    } else if (method === 'PATCH' && url.pathname === '/api/workspace') {
      workspacePatches.push(body);
      if (options.workspacePatchDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.workspacePatchDelayMs));
      }
      if (workspaceConflictsRemaining > 0) {
        workspaceConflictsRemaining -= 1;
        if (options.workspaceConflictMutation) {
          Object.assign(workspaceContext, options.workspaceConflictMutation);
        }
        workspaceRev += 1;
        await route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: 'The workspace moved since this desk read it.' }) });
        return;
      }
      if (body.expectedRev != null && Number(body.expectedRev) !== workspaceRev) {
        await route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ error: 'The workspace moved since this desk read it.' }) });
        return;
      }
      const expectedIdentity = {
        expectedDatasetId: workspaceContext.datasetId,
        expectedMarketLane: workspaceContext.marketLane,
        expectedAccountId: workspaceContext.accountId,
        expectedGeneration: Number(workspaceContext.generation || 0)
      };
      for (const [field, expected] of Object.entries(expectedIdentity)) {
        if (body[field] != null && String(body[field]) !== String(expected)) {
          await route.fulfill({ status: 409, contentType: 'application/json',
            body: JSON.stringify({ error: `The workspace ${field} guard is stale.` }) });
          return;
        }
      }
      // The server merges: fields the patch omits keep their stored value. Reproducing that here
      // is the point — a harness that replaced the whole record could not catch a surface that
      // destroys a declaration by omission.
      Object.keys(body).forEach(field => {
        if (['version', 'expectedRev', 'world', 'expectedDatasetId', 'expectedMarketLane',
          'expectedAccountId', 'expectedGeneration', 'clear'].includes(field)) return;
        if (body[field] !== undefined) workspaceContext[field] = body[field];
      });
      (body.clear || []).forEach(field => { workspaceContext[field] = null; });
      workspaceRev += 1;
      response = workspaceState();
    } else if (method === 'GET' && url.pathname === '/api/portfolio/accounts') {
      response = brokerImportAccounts;
    } else if (method === 'POST' && url.pathname === '/api/portfolio/broker-imports/preview') {
      brokerImportPreviews.push(body);
      response = brokerImportPreview;
    } else if (method === 'POST' && url.pathname === '/api/portfolio/broker-imports/confirm') {
      brokerImportConfirms.push(body);
      response = brokerImportConfirm;
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
    workspacePatches: () => workspacePatches,
    adoptionRequests: () => adoptionRequests,
    workspaceRev: () => workspaceRev,
    workspaceContext: () => JSON.parse(JSON.stringify(workspaceContext)),
    mutateWorkspace(fields) {
      Object.assign(workspaceContext, fields || {});
      workspaceRev += 1;
      return workspaceState();
    },
    setMarketIdentity(world, lane, datasetId = workspaceContext.datasetId) {
      activeWorld = world;
      activeMarketLane = lane;
      activeDataset = datasetId;
      workspaceContext.world = world;
      workspaceContext.datasetId = datasetId;
      workspaceContext.marketLane = lane;
      workspaceRev += 1;
      return workspaceState();
    },
    brokerImportPreviews: () => brokerImportPreviews,
    brokerImportConfirms: () => brokerImportConfirms,
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

/* The workbench ships NO silent defaults (program §3.5): goal, view, horizon and risk posture all
   start unchosen and Scan/Analyze stay withheld until the user declares them. Tests that want to
   reach analysis must therefore declare, exactly as a user would. */
async function declareWorkbench(page, opts = {}) {
  const goal = opts.goal || 'INCOME';
  const view = opts.view || 'Neutral';
  // The control now names its unit: the backend counts TRADING days, not calendar days.
  const horizon = opts.horizon || '45 trading days';
  const risk = opts.risk || 'Balanced';
  await page.locator(`[data-auth-scout-goal="${goal}"]`).click();
  await page.locator(`[data-auth-workbench-view="${view}"]`).click();
  await page.locator(`[data-auth-workbench-horizon="${horizon}"]`).click();
  await page.locator(`[data-auth-workbench-risk="${risk}"]`).click();
  await page.waitForFunction(() => window.HOME_SCOUT?.goal && window.homeIdea?.view
    && window.homeIdea?.horizon && window.homeIdea?.riskMode);
}

function definedRiskCompensation(evaluationId, symbol = 'AAPL') {
  return {
    evaluationId,
    symbol,
    strategy: 'PUT_CREDIT_SPREAD',
    label: 'Premium compensation',
    score: 77.4,
    premium: {
      kind: 'DEFINED_RISK_PERIOD_PREMIUM',
      premiumCents: 44700,
      denominatorCents: 105000,
      holdingPeriodDays: 28,
      periodReturnPct: 42.5714285714,
      annualizedPct: null,
      basis: 'Net opening premium after commission divided by exact defined-risk economic exposure.'
    },
    components: []
  };
}

async function scoutRowReceipt(row) {
  return row.evaluate(node => ({
    tag: node.tagName,
    stage: node.getAttribute('data-scout-stage'),
    evaluationId: node.getAttribute('data-auth-evaluation'),
    resultKey: node.getAttribute('data-auth-result-key'),
    analyzeSymbol: node.getAttribute('data-auth-analyze-symbol'),
    name: node.querySelector('.opportunitymain>b')?.childNodes[0]?.textContent.trim(),
    facts: Object.fromEntries(Array.from(node.querySelectorAll('[data-scout-fact]')).map(fact => [
      fact.getAttribute('data-scout-fact'), fact.textContent.trim()
    ])),
    lanes: Object.fromEntries(Array.from(node.querySelectorAll('[data-scout-lane]')).map(lane => [
      lane.getAttribute('data-scout-lane'), {
        label: lane.querySelector('i')?.textContent.trim(),
        value: lane.querySelector('b')?.textContent.trim(),
        detail: lane.querySelector('small')?.textContent.trim()
      }
    ])),
    action: node.querySelector('.opportunitygo')?.textContent.trim(),
    text: node.textContent.replace(/\s+/g, ' ').trim()
  }));
}

async function startNewIdea(page, symbol = 'AMD') {
  /* Reaching analysis is not a boot-race test. Wait on the governed universe receipt that makes
     the requested symbol actionable instead of hoping it arrives within the locator's default
     timeout after the user has already typed. Dedicated loading/race tests exercise the earlier
     states explicitly. */
  await page.waitForFunction(expected => {
    const book = window.DeskBackend?.state().book;
    return ['ready', 'partial'].includes(book?.phase)
      && window.authoritativeUniverseSymbol?.(expected) === expected;
  }, symbol, { timeout: 15000 });
  await page.locator('#threadNewIdea').click();
  await page.waitForSelector('[data-auth-workbench-query]');
  await page.locator('[data-auth-workbench-query]').fill(symbol);
  /* The permanent workbench may become interactive a frame before its governed universe has
     composed.  A real user chooses the staged match; do not race Enter against that receipt and
     accidentally test an ungoverned ticker lookup. */
  const match = page.locator(`[data-auth-workbench-symbol="${symbol}"]`);
  await match.waitFor({ timeout: 15000 });
  await match.click();
  try {
    await page.waitForFunction(expected => window.homeIdea?.symbol === expected
      && window.decide == null, symbol);
  } catch (error) {
    const diagnosis = await page.evaluate(() => ({
      homeIdea: window.homeIdea || null,
      decide: window.decide && {
        symbol: window.decide.sym, phase: window.decide.backendPhase
      },
      workspace: window.WORKSPACE || null,
      query: document.querySelector('[data-auth-workbench-query]')?.value || null,
      body: document.body.textContent.slice(0, 800)
    }));
    error.message += `\nNew Idea staging diagnosis: ${JSON.stringify(diagnosis)}`;
    throw error;
  }
  await declareWorkbench(page);
  await page.locator('[data-auth-workbench-analyze]').click();
}

async function openAuthoritativeDesk(options = {}) {
  const context = await browser.newContext({
    viewport: options.viewport || { width: 1440, height: 900 }
  });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, options);
  await page.goto(deskUrl);
  await waitForDeskBoot(page);
  await startNewIdea(page, options.ideaSymbol || 'AMD');
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

test('missing DeskBackend renders one fail-closed startup state', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  await page.route('**/js/desk-backend.js*', route => route.fulfill({
    status: 200, contentType: 'text/javascript',
    body: 'window.DeskBackend = {}; /* partially loaded canonical bridge */'
  }));
  try {
    await page.goto(deskUrl);
    await page.waitForSelector('#app[data-auth="unavailable"]');
    const rendered = await page.locator('#app').textContent();
    assert.match(rendered, /Desk could not start.*canonical Desk service did not load/i);
    assert.match(rendered, /No market facts, recommendations, or account state were substituted/i);
    assert.equal(await page.locator('#stage').count(), 0,
      'the app is replaced by one failure owner instead of booting a legacy workspace');
    assert.deepEqual(pageErrors, [], `fail-closed startup emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('global New idea keeps the underlying absent until the user chooses it', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    universeSymbols: ['AMD', 'AAPL']
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.data?.homeContext?.phase === 'ready');
    const amdResearchBefore = backend.count('GET', '/api/research/AMD');
    await page.locator('#threadNewIdea').click();
    await page.waitForSelector('[data-auth-workbench-query]');
    await page.waitForFunction(() =>
      document.activeElement?.hasAttribute('data-auth-workbench-query'));

    const absent = await page.evaluate(() => ({
      symbol: window.homeIdea?.symbol,
      decide: window.decide,
      dialogs: document.querySelectorAll('.composepanel').length,
      deskVisible: getComputedStyle(document.querySelector('#stage')).display !== 'none',
      queryFocused: document.activeElement?.hasAttribute('data-auth-workbench-query')
    }));
    assert.equal(absent.symbol, null);
    assert.equal(absent.decide, null);
    assert.equal(absent.dialogs, 0);
    assert.equal(absent.deskVisible, true,
      'New idea focuses the permanent Home workbench instead of replacing or covering the desk');
    assert.equal(absent.queryFocused, true);
    assert.equal(backend.count('POST', '/api/plans'), 0,
      'focusing the workbench does not mint a Plan for an arbitrary ticker');
    assert.equal(backend.count('GET', '/api/research/AMD'), amdResearchBefore,
      'focusing the workbench does not secretly load AMD research');

    await page.locator('[data-auth-workbench-query]').fill('AMD');
    await page.locator('[data-auth-workbench-query]').press('Enter');
    await declareWorkbench(page);
    const staged = await page.evaluate(() => ({
      draftSymbol: window.homeIdea?.symbol,
      liveDecide: window.decide,
      composer: document.querySelectorAll('.ideacomposer').length,
      analyze: document.querySelector('[data-auth-workbench-analyze]')?.textContent
    }));
    assert.deepEqual(staged, {
      draftSymbol: 'AMD', liveDecide: null, composer: 0,
      analyze: 'Analyze AMD →'
    }, 'ticker selection stages the subject in place and leaves every declaration editable');
    assert.equal(backend.count('POST', '/api/plans'), 0,
      'ticker selection alone cannot mint a Plan or begin market work');
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 0);

    await page.locator('[data-auth-workbench-analyze]').click();
    try {
      await page.waitForFunction(() => window.decide?.backendPhase === 'ready'
        && window.DeskBackend.state().plan?.symbol === 'AMD', null, { timeout: 10000 });
    } catch (error) {
      const diagnosis = await page.evaluate(() => ({
        decide: window.decide && { symbol: window.decide.sym, phase: window.decide.backendPhase,
          error: window.decide.backendError, query: window.decide.pickq },
        bridge: window.DeskBackend.state()
      }));
      throw new Error(`${error.message}\n${JSON.stringify(diagnosis)}`);
    }
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 1,
      'the explicit Analyze action is the point at which the canonical idea workflow begins');
    assert.deepEqual(pageErrors, [], `permanent Home workbench emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Acquire requires an explicit stock-entry price and share quantity before analysis', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { universeSymbols: ['AMD', 'AAPL'] });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.locator('#threadNewIdea').click();
    await page.waitForSelector('[data-auth-workbench-query]');
    await declareWorkbench(page, { goal: 'ACQUIRE' });
    await page.locator('[data-auth-workbench-query]').fill('AMD');
    await page.locator('[data-auth-workbench-query]').press('Enter');

    assert.equal(await page.locator('[data-auth-workbench-target]').count(), 1);
    assert.equal(await page.locator('[data-auth-workbench-shares]').count(), 1);
    assert.equal(await page.locator('[data-auth-workbench-analyze]').isDisabled(), true,
      'Acquire cannot silently invent the desired stock price or quantity');
    assert.equal(backend.count('POST', '/api/plans'), 0);

    await page.locator('[data-auth-workbench-target]').fill('85.50');
    await page.locator('[data-auth-workbench-shares]').fill('300');
    assert.equal(await page.locator('[data-auth-workbench-analyze]').isDisabled(), false);
    assert.equal(backend.count('POST', '/api/plans'), 0,
      'goal-specific declarations remain local until Analyze');
    await page.locator('[data-auth-workbench-analyze]').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().plan?.intent === 'ACQUIRE'
      && window.DeskBackend.state().plan?.context?.targetCents === 8550
      && window.DeskBackend.state().plan?.context?.holdingsShares === 300,
    null, { timeout: 10000 });

    const create = backend.requests.find(row => row.method === 'POST' && row.path === '/api/plans');
    assert.equal(create.body.intent, 'ACQUIRE');
    assert.equal(create.body.targetCents, 8550);
    assert.equal(create.body.holdingsShares, 300);

    const createsAfterOpen = backend.count('POST', '/api/plans');
    await page.locator('[data-dec="intent"]').click();
    await page.locator('#acquireTarget').fill('82.25');
    await page.locator('#acquireShares').fill('200');
    await page.locator('[data-dec="analyzeidea"]').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().plan?.context?.targetCents === 8225
      && window.DeskBackend.state().plan?.context?.holdingsShares === 200,
    null, { timeout: 10000 });
    const contextUpdate = backend.requests.filter(row => row.method === 'PUT'
      && row.path === `/api/plans/${PLAN_ID}/context`).at(-1);
    assert.equal(contextUpdate.body.targetCents, 8225);
    assert.equal(contextUpdate.body.holdingsShares, 200);
    assert.equal(backend.count('POST', '/api/plans'), createsAfterOpen,
      'editing Acquire declarations reuses the canonical versioned Plan context API');

    await page.locator('#threadNewIdea').click();
    await page.waitForSelector('[data-auth-workbench-target]');
    assert.equal(await page.locator('[data-auth-workbench-target]').inputValue(), '',
      'a stock-specific acquisition price never leaks into the next underlying');
    assert.equal(await page.locator('[data-auth-workbench-shares]').inputValue(), '',
      'quantity-denominated willingness is declared for each fresh idea');
    assert.equal(await page.locator('.composepanel').count(), 0,
      'New idea returns to the permanent workbench instead of opening a replacement dialog');
    assert.deepEqual(pageErrors, [], `Acquire composer emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Home hydrates ambient universe quotes without Plans, trades, or shares', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
        tradeCount: data.practiceBook.snapshot.activeTrades.length,
        shareCount: data.practiceBook.sharePositions.length,
        symbols: data.homeContext.symbols,
        rows: data.homeContext.rows.map(row => ({
          symbol: row.symbol,
          quoteSymbol: row.research?.quote?.symbol,
          source: row.research?.quote?.source,
          displayPrice: row.research?.quote?.displayPrice
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

test('Home keeps quote failure reasons while focused history chain and news recover independently', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = emptyBookDocuments();
  const marketDocuments = populatedBookDocuments();
  bookDocuments.research = marketDocuments.research;
  bookDocuments.history = marketDocuments.history;
  bookDocuments.expirations = marketDocuments.expirations;
  bookDocuments.chain = marketDocuments.chain;
  bookDocuments.news = marketDocuments.news;
  await installBackend(page, {
    bookDocuments,
    universeSymbols: ['AAPL', 'MSFT'],
    quoteBatchError: 'The bounded market quote batch is temporarily unavailable.'
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => {
      const home = window.DeskBackend.state().book?.data?.homeContext;
      return home?.phase === 'ready' && home.rows.some(row =>
        row.symbol === 'AAPL' && row.research && row.history && row.chain && row.news);
    }, null, { timeout: 10000 });

    const receipt = await page.evaluate(() => {
      const home = window.DeskBackend.state().book.data.homeContext;
      const focused = home.rows.find(row => row.symbol === 'AAPL');
      const untouched = home.rows.find(row => row.symbol === 'MSFT');
      return {
        focused: {
          research: !!focused?.research,
          history: !!focused?.history,
          chain: !!focused?.chain,
          news: !!focused?.news
        },
        untouchedReason: untouched?.missing?.find(row =>
          String(row.key).startsWith('research:'))?.error?.message,
        text: document.querySelector('#stage').textContent.replace(/\s+/g, ' ').trim()
      };
    });
    assert.deepEqual(receipt.focused,
      { research: true, history: true, chain: true, news: true },
      'a watch-quote failure cannot suppress independently available focused market receipts');
    assert.match(receipt.untouchedReason,
      /bounded market quote batch is temporarily unavailable/i,
      'every untouched watch row retains the exact reason its quote is absent');
    assert.match(receipt.text, /Backend research sentinel headline/i,
      'focused news remains visible after the independent receipt recovers');
    assert.deepEqual(pageErrors, [],
      `independent Home receipt recovery emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('served Home command search retargets symbols and sectors through the backend universe', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    assert.match(result.text, /Semiconductors/i);
    assert.doesNotMatch(result.text, /backend universe/i,
      'the sector lens names the product concept without exposing implementation provenance');
    assert.doesNotMatch(result.text, /VISUAL FIXTURE/i);
    assert.ok(backend.count('GET', '/api/research/AMD') >= 1);
    assert.ok(backend.count('GET', '/api/quotes') >= 2,
      'sector retargeting hydrates its bounded backend symbol set through the quote batch');
    assert.deepEqual(pageErrors, [], `backend Home command routing emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Home sector and symbol focus preserve the bounded twelve-market watch', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const sectorSymbols = ['AMD', 'NVDA', 'MU', 'AVGO', 'INTC', 'QCOM', 'TXN', 'ADI', 'MRVL', 'ARM'];
  await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    universeSymbols: sectorSymbols.concat(['SPY', 'IWM', 'TLT', 'GLD', 'AAPL']),
    universeSectors: [{
      key: 'SEMICONDUCTORS', label: 'Semiconductors', symbols: sectorSymbols
    }, {
      key: 'TECH', label: 'Technology', symbols: ['AAPL']
    }]
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.data?.homeContext?.phase === 'ready');

    await page.evaluate(() => window.DeskBackend.focusBookSector('SEMICONDUCTORS'));
    await page.waitForFunction(expected => {
      const home = window.DeskBackend.state().book?.data?.homeContext;
      return home?.phase === 'ready' && home?.sectorLens?.key === 'SEMICONDUCTORS'
        && home.symbols.length === expected;
    }, sectorSymbols.length);
    assert.deepEqual(await page.evaluate(() =>
      window.DeskBackend.state().book.data.homeContext.symbols), sectorSymbols,
    'choosing a sector keeps every member that fits Home’s existing twelve-market bound');
    assert.deepEqual(await page.evaluate(() => ({
      choices: Array.from(document.querySelectorAll('.homesectorchips button'))
        .map(button => button.textContent.trim()),
      selected: document.querySelector('.homesectorchips button.on')?.textContent.trim()
    })), {
      choices: ['Broad market', 'Semiconductors', 'Technology'],
      selected: 'Semiconductors'
    }, 'the selected sector remains in the persistent Home lens beside every other sector');

    await page.evaluate(() => window.DeskBackend.focusBookSymbol('AAPL'));
    await page.waitForFunction(() => {
      const home = window.DeskBackend.state().book?.data?.homeContext;
      return home?.phase === 'ready' && home.detailSymbol === 'AAPL';
    });
    const focused = await page.evaluate(() =>
      window.DeskBackend.state().book.data.homeContext.symbols);
    assert.equal(focused.length, 11,
      'adding a focus to a ten-name sector does not collapse the watch to four rows');
    assert.equal(focused[0], 'AAPL');
    assert.deepEqual(focused.slice(1), sectorSymbols,
      'the focused market moves into the bounded watch without discarding its sector context');

    await page.evaluate(() => window.authStageHomeSymbol('AAPL'));
    await page.locator('[data-auth-workbench-clear]').click();
    assert.deepEqual(await page.evaluate(() => ({
      subject: window.WORKSPACE.focusedSubject,
      symbol: window.WORKSPACE.focusedSymbol,
      scope: window.WORKSPACE.scopeType,
      sector: window.WORKSPACE.sectorKey,
      query: window.homeIdea.query
    })), {
      subject: 'BOOK', symbol: null, scope: null, sector: null, query: ''
    }, 'Clear is a complete workspace transition, not a cosmetic input reset');
    assert.deepEqual(pageErrors, [],
      `bounded Home market focus emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a user focus supersedes slower initial Home market hydration', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    assert.match(settled.heading, /AMD/i);
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
  page.setDefaultTimeout(8000);
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
        selectedRows: Array.from(document.querySelectorAll('.authmarketrow.on [data-auth-market-symbol]'))
          .map(node => node.getAttribute('data-auth-market-symbol')),
        heading: document.querySelector('#chainBand')?.textContent.replace(/\s+/g, ' ').trim(),
        news: document.querySelector('#newsBand')?.textContent.replace(/\s+/g, ' ').trim()
      };
    });
    assert.equal(settled.requestId, acceptedGeneration);
    assert.equal(settled.detailSymbol, 'NVDA');
    assert.equal(settled.visibleSymbol, 'NVDA');
    assert.deepEqual(settled.selectedRows, ['NVDA']);
    assert.match(settled.heading, /NVDA/i);
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
  page.setDefaultTimeout(8000);
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
    bookDocuments, bookCoreDelayMs: 150, homeContextDelayMs: 250,
    universeSymbols: ['AAPL'], scoutSymbols: ['AAPL']
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('#stage[data-book-authority="loading"]');
    assert.deepEqual(await page.evaluate(() => [
      'activityBand', 'book', 'riskMain', 'bookrisk', 'chainBand', 'univBand', 'sectorBand', 'newsBand'
    ].map(id => ({ id, rects: document.getElementById(id).getClientRects().length }))), [
      { id: 'activityBand', rects: 0 }, { id: 'book', rects: 0 },
      { id: 'riskMain', rects: 0 }, { id: 'bookrisk', rects: 0 },
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
    await page.waitForSelector('#chainBand [data-hist-svg] rect', { timeout: 10000 });

    for (const viewport of [
      { width: 1280, height: 800 },
      { width: 1920, height: 1080 },
      { width: 2560, height: 1440 },
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
            && bridge.book.data.practiceBook.snapshot.activeTrades.length,
          openTradesCount: bridge.book && bridge.book.data
            && bridge.book.data.practiceBook.summary.openTradesCount,
          text: stage && stage.textContent.replace(/\s+/g, ' ').trim(),
          visibleCards: Array.from(document.querySelectorAll('#book .card[data-id]'))
            .filter(card => card.getClientRects().length).map(card => card.dataset.id),
          cockpitPanels: ['activityBand', 'book', 'riskMain', 'bookrisk', 'chainBand',
            'univBand', 'sectorBand', 'newsBand']
            .map(id => {
              const node = document.getElementById(id);
              const rect = node.getBoundingClientRect();
              return { id, visible: node.getClientRects().length > 0, width: rect.width, height: rect.height };
            }),
          cockpitGeometry: Object.fromEntries(
            ['activityBand', 'book', 'riskMain', 'chainBand', 'univBand',
              'sectorBand', 'newsBand'].map(id => {
              const rect = document.getElementById(id).getBoundingClientRect();
              return [id, {
                left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
                width: rect.width, height: rect.height
              }];
            })
          ),
          activityOwnsBook: document.getElementById('book').parentElement.id === 'activityBand',
          activityOwnsIdeas: document.getElementById('univBand').parentElement.id === 'activityBand',
          planActions: document.querySelectorAll('[data-auth-plan-id]').length,
          planActionTags: Array.from(document.querySelectorAll('[data-auth-plan-id]'))
            .map(node => node.tagName),
          marketRows: document.querySelectorAll('[data-auth-market-symbol]').length,
          newsLinks: document.querySelectorAll('#newsBand .authhomenews a').length,
          candleBodies: document.querySelectorAll('#chainBand [data-hist-svg] rect').length,
          chainRows: document.querySelectorAll('#chainBand .authchainrow').length,
          riskMapCount: document.querySelectorAll('#authBookRiskViz').length,
          scoutControls: document.querySelectorAll('#riskMain [data-auth-scout-goal]').length,
          liquiditySegments: document.querySelectorAll('.liquiditybar > i').length,
          boardTemplateAreas: getComputedStyle(document.querySelector('#stage .board'))
            .gridTemplateAreas,
          boardOverflows: document.querySelector('#stage .board').scrollHeight
            > document.querySelector('#stage .board').clientHeight + 2,
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
      assert.doesNotMatch(rendered.text, /No open Practice positions/i,
        'a truly empty Book does not spend a full panel apologizing for absent positions');
      assert.match(rendered.text, /Scan a market or shape one exact idea.*Broad market.*Income.*Directional.*Acquire.*Hedge.*Exit/i,
        'the empty Book leads with the permanent Scout/idea workbench and every supported goal');
      assert.match(rendered.text, /Market[\s\S]*AAPL[\s\S]*(?:daily bars|stored daily bars)/i,
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
      const opportunityHero = rendered.cockpitPanels.find(panel => panel.id === 'riskMain');
      assert.equal(opportunityHero.visible, true,
        `${viewport.width}px gives the primary Home canvas to opportunity discovery`);
      assert.ok(opportunityHero.width >= (viewport.width <= 500 ? 350 : 400),
        `${viewport.width}px Scout hero keeps useful width (${opportunityHero.width}px)`);
      assert.ok(opportunityHero.height >= (viewport.width <= 500 ? 400 : 300),
        `${viewport.width}px Scout hero keeps useful height (${opportunityHero.height}px)`);
      const emptyBookRisk = rendered.cockpitPanels.find(panel => panel.id === 'bookrisk');
      assert.deepEqual(emptyBookRisk, { id: 'bookrisk', visible: false, width: 0, height: 0 },
        `${viewport.width}px does not reserve a second empty risk receipt tile`);
      const emptyRoster = rendered.cockpitPanels.find(panel => panel.id === 'book');
      assert.deepEqual(emptyRoster, { id: 'book', visible: false, width: 0, height: 0 },
        `${viewport.width}px does not reserve a redundant apology panel for an empty roster`);
      assert.equal(rendered.riskMapCount, 0,
        'the empty Practice receipt never mounts a zero-valued risk-map SVG');
      assert.match(rendered.boardTemplateAreas, /\bmap\b/,
        `${viewport.width}px gives the empty Home hero to the actionable Scout`);
      assert.equal(rendered.scoutControls, 5,
        'one Home Scout surface owns all goal controls');
      assert.ok(rendered.liquiditySegments >= 1,
        'the top orientation row plots the same cash-truth receipt it labels');
      assert.equal(rendered.activityOwnsBook, true,
        'the adaptive activity rail owns the existing position roster without a second renderer');
      assert.equal(rendered.activityOwnsIdeas, true,
        'the adaptive activity rail owns the existing working-idea list without a second renderer');
      rendered.cockpitPanels.filter(panel =>
        !['bookrisk', 'book', 'activityBand', 'univBand'].includes(panel.id)).forEach(panel => {
        assert.equal(panel.visible, true, `${viewport.width}px ${panel.id} remains visible`);
        assert.ok(panel.width >= (viewport.width <= 500 ? 350 : 200),
          `${viewport.width}px ${panel.id} keeps useful width (${panel.width}px)`);
        const usefulHeight = ['sectorBand', 'newsBand'].includes(panel.id) ? 90 : 180;
        assert.ok(panel.height >= usefulHeight,
          `${viewport.width}px ${panel.id} keeps useful height (${panel.height}px)`);
      });
      const activity = rendered.cockpitPanels.find(panel => panel.id === 'activityBand');
      const ideas = rendered.cockpitPanels.find(panel => panel.id === 'univBand');
      assert.equal(activity.visible, true, `${viewport.width}px keeps the adaptive activity rail visible`);
      assert.equal(ideas.visible, true, `${viewport.width}px keeps the owning Plan actionable`);
      assert.ok(activity.height >= 70 && ideas.height >= 60,
        `${viewport.width}px activity is compact but usable (${activity.height}/${ideas.height}px)`);
      if (viewport.width >= 1200) {
        const { activityBand, riskMain, chainBand, sectorBand, newsBand } =
          rendered.cockpitGeometry;
        if (viewport.width >= 1500) {
          assert.ok(Math.abs(riskMain.top - chainBand.top) < 2
            && Math.abs(riskMain.bottom - chainBand.bottom) < 2,
          'Market and permanent Discovery share the wide desktop orientation-and-discovery band');
          assert.ok(activityBand.top >= Math.max(riskMain.bottom, chainBand.bottom) - 2,
          'the adaptive activity rail begins in the lower decide-and-monitor band');
          assert.ok(Math.abs(sectorBand.top - activityBand.top) < 2
            && newsBand.top >= sectorBand.bottom - 2,
          'Watch and Research share the lower market-intelligence column beside activity');
          assert.equal(rendered.boardOverflows, false,
            `${viewport.width}px default Home composition has no panel or board scroll`);
        } else {
          assert.ok(Math.abs(riskMain.top - chainBand.top) < 2,
            'the intermediate desktop leads with Scout and Market side by side');
          assert.ok(activityBand.top >= riskMain.bottom - 2
            && Math.abs(sectorBand.top - activityBand.top) < 2,
            'Activity and Watch form the next intermediate band');
          assert.ok(newsBand.top >= activityBand.bottom - 2,
            'News owns the final full-width intermediate band');
        }
      }
      assert.doesNotMatch(rendered.text, /Covered strangle|Short-put campaign|Past stop/i,
        'offline position stories cannot survive in the served empty book');
      assert.doesNotMatch(rendered.text, /six staged design positions|Optional gaps|Loaded 20/i,
        'implementation receipts do not replace the Home experience');
      assert.equal(rendered.documentOverflow, false,
        `${viewport.width}px authoritative empty Home remains horizontally contained`);
    }
    assert.equal(backend.count('POST', '/api/research/scout'), 0,
      'rendering the rich Home never spends a provider allowance; Scout remains explicit');

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
      horizonDays: window.__homeResumeContext.horizonDays,
      originPlanId: window.__homeResumeContext.originPlanId,
      targetCents: window.__homeResumeContext.targetCents,
      holdingsShares: window.__homeResumeContext.holdingsShares,
      costBasisCents: window.__homeResumeContext.costBasisCents,
      priceAssumptionCents: window.__homeResumeContext.priceAssumptionCents,
      assignmentPreference: window.__homeResumeContext.assignmentPreference
    })), {
      planId: BOOK_PLAN_ID, symbol: 'AAPL', goal: 'INCOME', horizonDays: 45,
      originPlanId: 'plan_home_origin', targetCents: 23000, holdingsShares: 100,
      costBasisCents: 20000, priceAssumptionCents: 22100, assignmentPreference: 'AVOID'
    }, 'Home resumes the exact clicked Plan instead of minting or guessing another one');
    await page.evaluate(() => {
      window.DeskBackend.openIdea = window.__homeOpenIdea;
      if (window.decide) window.exitDecide();
    });

    assert.equal(backend.count('GET', '/api/portfolio/book'), 1,
      'Home reads the whole Practice Book through one versioned receipt');
    assert.equal(backend.count('GET', '/api/trades'), 0);
    assert.equal(backend.count('GET', '/api/positions'), 0);
    assert.equal(backend.count('GET', '/api/portfolio/summary'), 0);
    assert.equal(backend.count('GET', '/api/portfolio/heat'), 0);
    assert.equal(backend.count('GET', '/api/portfolio/greeks'), 0);
    assert.equal(backend.count('GET', '/api/portfolio/book-risk'), 0);
    assert.equal(backend.count('GET', '/api/research/AAPL'), 1);
    assert.equal(backend.count('GET', '/api/research/AAPL/news'), 1);
    assert.equal(backend.count('GET', '/api/trades/' + BOOK_TRADE_ID), 0,
      'an empty roster cannot trigger a fixture or speculative position-detail read');
    assert.deepEqual(pageErrors, [], `empty Practice book emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('an empty Home with no working ideas gives Scout and market context the whole canvas', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const marketDocuments = populatedBookDocuments();
  const bookDocuments = emptyBookDocuments();
  bookDocuments.planPortfolio = [];
  bookDocuments.research = marketDocuments.research;
  bookDocuments.history = marketDocuments.history;
  bookDocuments.expirations = marketDocuments.expirations;
  bookDocuments.chain = marketDocuments.chain;
  bookDocuments.news = marketDocuments.news;
  const backend = await installBackend(page, {
    bookDocuments, universeSymbols: ['AAPL'], scoutSymbols: ['AAPL']
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('#stage[data-book-authority="empty"][data-plan-count="0"]',
      { timeout: 10000 });
    await page.waitForFunction(() => window.DeskBackend.state().book?.data?.homeContext?.phase === 'ready',
      null, { timeout: 10000 });
    const rendered = await page.evaluate(() => {
      const board = document.querySelector('#stage .board');
      const visible = id => document.getElementById(id).getClientRects().length > 0;
      const risk = document.getElementById('riskMain').getBoundingClientRect();
      const chain = document.getElementById('chainBand').getBoundingClientRect();
      const sector = document.getElementById('sectorBand').getBoundingClientRect();
      const news = document.getElementById('newsBand').getBoundingClientRect();
      return {
        hidden: ['book', 'bookrisk'].map(id => [id, visible(id)]),
        shown: ['riskMain', 'chainBand', 'sectorBand', 'newsBand', 'univBand'].map(id => [id, visible(id)]),
        marketAndDiscovery: Math.abs(chain.top - risk.top) < 2
          && Math.abs(chain.bottom - risk.bottom) < 2,
        supportAligned: Math.abs(sector.top - news.top) < 2
          && Math.abs(sector.bottom - news.bottom) < 2,
        supportWidths: [sector.width, news.width],
        boardOverflow: board.scrollHeight > board.clientHeight + 2,
        horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
        scoutButtons: document.querySelectorAll('#riskMain [data-auth-opportunity-scan]').length,
        sectorChipRows: document.querySelectorAll('.homesectorchips').length,
        sectorChips: document.querySelectorAll('.homesectorchips button').length,
        duplicateSectorTiles: document.querySelectorAll('#riskMain .scoutbreadth').length,
        text: board.textContent.replace(/\s+/g, ' ').trim()
      };
    });
    assert.deepEqual(rendered.hidden, [
      ['book', false], ['bookrisk', false]
    ], 'zero positions reserve no roster or futures cells');
    assert.deepEqual(rendered.shown, [
      ['riskMain', true], ['chainBand', true], ['sectorBand', true], ['newsBand', true], ['univBand', false]
    ], 'with no resumable idea, Home releases the empty activity rail to market and discovery');
    assert.equal(rendered.marketAndDiscovery, true,
      'Market and the permanent Discovery workbench share the top product band');
    assert.equal(rendered.supportAligned, true);
    assert.ok(Math.abs(rendered.supportWidths[0] - rendered.supportWidths[1]) < 3,
      'market watch and news share the supporting row after the empty idea cell disappears');
    assert.equal(rendered.boardOverflow, false);
    assert.equal(rendered.horizontalOverflow, false);
    assert.equal(rendered.scoutButtons, 1);
    assert.equal(rendered.sectorChipRows, 1,
      'the persistent market lens is one always-open chip row in the market panel');
    assert.ok(rendered.sectorChips >= 1,
      'the broad-market chip is always present; sector chips appear with the described universe');
    assert.equal(rendered.duplicateSectorTiles, 0,
      'the permanent top Market lens owns sector selection without a clipped second sector wall');
    assert.match(rendered.text, /Scan a market or shape one exact idea[\s\S]*Ticker or sector[\s\S]*Scan/i);
    assert.deepEqual(pageErrors, [], `zero-plan Home emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Home renders one synchronized Book total and its batched position contributions', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    await page.waitForFunction(() =>
      document.querySelector('#authBookFan')?._bfmap?.seriesEntries?.some(row =>
        row.id === 'trade_backend_book_receipt'));
    const rendered = await page.evaluate(() => ({
      hint: document.querySelector('#bookrisk .lenshd .hint')?.textContent.trim(),
      bookLegend: document.querySelector('#authBookFanLegend .booktotal')?.textContent
        .replace(/\s+/g, ' ').trim(),
      readout: document.querySelector('#bookFanReadout')?.textContent.replace(/\s+/g, ' ').trim(),
      chartText: document.querySelector('#authBookFan')?.textContent.replace(/\s+/g, ' ').trim(),
      aggregatePaths: document.querySelectorAll('#authBookFan path[stroke="var(--text)"]').length,
      positionEntries: document.querySelector('#authBookFan')?._bfmap?.seriesEntries?.map(row => row.id) || [],
      measuredFingerprint: window.DeskBackend.state().book.data.practiceBook.bookRisk
        .measuredBook.scenario.jointFingerprint
    }));
    assert.match(rendered.hint, /BOOK total/i);
    assert.match(rendered.hint, /colors = synchronized positions/i);
    assert.match(rendered.bookLegend, /BOOKeverything together\+\$460 62% gain odds/i);
    assert.match(rendered.readout, /Whole book · median \+\$460/i);
    assert.match(rendered.readout, /hover a colored path for that position/i);
    assert.match(rendered.chartText, /BOOK\s*\+\$460/i,
      'SVG label and value may occupy separate text nodes while retaining the exact receipt');
    assert.ok(rendered.aggregatePaths >= 2,
      'the one renderer paints synchronized Book paths and its median from the canonical receipt');
    assert.deepEqual(rendered.positionEntries, [BOOK_TRADE_ID],
      'hover/click identity remains the independent position, never a fabricated aggregate position');
    assert.equal(rendered.measuredFingerprint, 'joint-book-browser-receipt');
    assert.equal(backend.count('GET', '/api/portfolio/book'), 1,
      'the aggregate consumes the same canonical Practice Book receipt as every Home fact');
    assert.equal(backend.count('GET', '/api/portfolio/book-risk'), 0,
      'the aggregate does not open a parallel Book-risk read');
    assert.equal(backend.requests.filter(row => row.method === 'POST'
      && /\/outcomes\/ensemble\/paths$/.test(row.path)).length, 0,
    'Home receives every position contribution in the one Practice Book read; no Plan N+1 fan opens');
    assert.deepEqual(pageErrors, [], `joint Book fan emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Home resumes the exact clicked Plan in the authoritative Desk', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    page.setDefaultTimeout(8000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    const exact = {
      id: PLAN_ID, symbol: 'AMD', intent, thesis: null, horizonDays: null,
      assignmentPreference: 'WILLING'
    };
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
          horizonDays: window.DeskBackend.state().context.horizonDays,
          assignmentPreference: window.DeskBackend.ideaDeclaration().assignmentPreference
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
        requested: {
          goal: intent, view: null, horizonDays: null, assignmentPreference: 'WILLING'
        },
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
  page.setDefaultTimeout(8000);
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
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/context`), 0,
      'declarations stay staged until the explicit apply boundary');
    await page.locator('[data-dec="analyzeidea"]').click();
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
    await page.evaluate(() => window.DeskBackend.flushWorkspace());
    assert.deepEqual({
      goal: backend.workspaceContext().goal,
      view: backend.workspaceContext().view,
      horizonDays: backend.workspaceContext().horizonDays,
      riskPosture: backend.workspaceContext().riskPosture
    }, {
      goal: 'INCOME', view: 'bullish', horizonDays: 30, riskPosture: 'balanced'
    }, 'the accepted Plan declarations are persisted back into the one WorkspaceContext for Home, Back, and reload');
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
      scenario: true, move: false, volatility: false, days: false
    }, 'selecting a named story pins its identity without copying the server-owned numeric defaults');

    await page.locator('[data-dec="intent"]').click();
    await page.locator('[data-obj="view"][data-val="Bullish"]').click();
    await page.locator('[data-dec="analyzeidea"]').click();
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
  page.setDefaultTimeout(8000);
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
    await page.locator('[data-dec="analyzeidea"]').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'error', null,
      { timeout: 10000 });
    assert.equal(await page.evaluate(() => window.decide.view), 'Bearish',
      'the visible declaration rehydrates the newer Plan version the backend accepted');
    assert.equal(await page.evaluate(() => window.DeskBackend.ideaDeclaration().view), 'bearish');
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
  page.setDefaultTimeout(8000);
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
      /* the account's own Plan symbol leads the watch; the described universe fills the
         ambient market context; the other account's NVDA Plan never leaks in */
      symbols: ['AAPL', 'AMD'],
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
  page.setDefaultTimeout(8000);
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
  page.setDefaultTimeout(8000);
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
      shareText: document.querySelector('#book')?.textContent.replace(/\s+/g, ' ').trim(),
      analyzeShares: document.querySelectorAll('#book [data-auth-newidea-symbol="AAPL"]').length,
      contextSymbols: window.DeskBackend.state().book.data.homeContext.symbols,
      researchText: document.querySelector('#newsBand')?.textContent
    }), BOOK_TRADE_ID);
    assert.equal(home.plans, 0);
    assert.equal(home.cardVisible, true, 'an active trade remains a first-class clickable Home row');
    assert.match(home.shareText, /Share inventory[\s\S]*AAPL · 100 shares[\s\S]*75 free · 25 locked/i);
    assert.equal(home.analyzeShares, 1, 'share inventory retains a Shape action without an owning Plan');
    assert.deepEqual(home.contextSymbols, ['AAPL', 'AMD'],
      'owned trade/share symbols lead the market context; the described universe fills the watch');
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

test('lifecycle presentation receipt alone owns roster urgency, evidence gating, wording, and trigger', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = twoPositionBookDocuments();
  documents.tradeDetails[BOOK_TRADE_ID].analysis = lifecycleAnalysisFixture();
  /* One missing executable close is not a missing market. Preserve the independent quote, POP,
     and Greeks receipts while deliberately leaving stale P/L numbers in legacy fields; the Desk
     must obey availability and never display those stale dollars as current. */
  const partialAvailability = currentAvailability({
    closeAvailable: false,
    closeUnavailableReason: 'The exact executable close side is unavailable for the short put.',
    decisionPnlAvailable: false,
    decisionPnlUnavailableReason: 'Current position P/L requires the missing executable close side.'
  });
  const secondTrade = documents.activeTrades.find(row => row.id === SECOND_BOOK_TRADE_ID);
  secondTrade.currentMarketAvailability = partialAvailability;
  documents.tradeDetails[SECOND_BOOK_TRADE_ID].current.availability = partialAvailability;
  documents.tradeDetails[SECOND_BOOK_TRADE_ID].current.closeCostCents = null;
  documents.tradeDetails[SECOND_BOOK_TRADE_ID].current.unrealizedCents = -8400;
  documents.tradeDetails[SECOND_BOOK_TRADE_ID].current.decisionUnrealizedCents = null;
  documents.tradeDetails[SECOND_BOOK_TRADE_ID].analysis = lifecycleAnalysisFixture({
    presentation: {
      evidenceState: 'CURRENT_MARK_UNAVAILABLE',
      actionable: false,
      userFacingVerdict: 'No verdict · executable close unavailable',
      userFacingStatus: 'Executable close unavailable',
      tone: 'WARNING',
      trigger: {
        code: 'CURRENT_MARK_UNAVAILABLE',
        label: 'Executable close unavailable',
        dimension: 'MECHANICS',
        status: 'BLOCKED',
        basis: 'The exact executable close mark is missing from the backend receipt.'
      },
      sortPriority: 1
    },
    decision: {
      verdict: 'NEEDS_EVIDENCE',
      summary: 'NO VERDICT: current executable close evidence is unavailable; no action is recommended.'
    }
  });
  const backend = await installBackend(page, { bookDocuments: documents });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    try {
      await page.waitForFunction(() => window.DeskBackend.state().book?.data?.lifecycle?.phase === 'ready'
        && window.POS?.length === 2, null, { timeout: 10000 });
    } catch (error) {
      const diagnosis = await page.evaluate(() => ({
        book: window.DeskBackend.state().book,
        rows: window.POS?.map(row => ({
          id: row.id,
          lifecycle: row.lifecycle,
          phase: row._positionPhase
        })),
        body: document.body.textContent.slice(0, 1200)
      }));
      error.message += `\nLifecycle Book diagnosis: ${JSON.stringify(diagnosis)}`;
      throw error;
    }

    const home = await page.evaluate(() => ({
      roster: window.POS.map(row => row.id),
      badges: window.POS.map(row => document.querySelector(
        `#book .card[data-id="${row.id}"] .lifebadge`)?.textContent.trim()),
      priorities: window.POS.map(row => row.lifecycle.decision.presentation.sortPriority)
    }));
    assert.deepEqual(home.roster, [SECOND_BOOK_TRADE_ID, BOOK_TRADE_ID],
      'the backend sortPriority orders the roster; the browser has no verdict urgency table');
    assert.deepEqual(home.badges, ['Executable close unavailable', 'On plan']);
    assert.deepEqual(home.priorities, [1, 5]);

    await page.locator(`#book .card[data-id="${SECOND_BOOK_TRADE_ID}"]`).click();
    try {
      await page.waitForFunction(tradeId => window.state?.level === 'position'
        && window.DeskBackend.state().position?.data?.trade?.id === tradeId,
      SECOND_BOOK_TRADE_ID, { timeout: 10000 });
    } catch (error) {
      const diagnosis = await page.evaluate(tradeId => ({
        route: window.state,
        adapter: window.DeskBackend.state().position,
        cardPhase: window.byId?.[tradeId]?._positionPhase,
        cardDataId: window.byId?.[tradeId]?._positionData?.trade?.id,
        workspace: window.WORKSPACE,
        visibleCards: Array.from(document.querySelectorAll('#book .card')).map(row => ({
          id: row.getAttribute('data-id'),
          rects: row.getClientRects().length,
          cls: row.className
        }))
      }), SECOND_BOOK_TRADE_ID);
      error.message += `\nLifecycle Position diagnosis: ${JSON.stringify(diagnosis)}`;
      throw error;
    }
    const receipt = await page.locator(
      `[data-auth-position-detail="${SECOND_BOOK_TRADE_ID}"] .authlifecycle`).textContent();
    assert.match(receipt, /No verdict · executable close unavailable/i);
    assert.match(receipt, /NO VERDICT: current executable close evidence is unavailable/i);
    assert.match(receipt, /The exact executable close mark is missing/i);
    assert.doesNotMatch(receipt, /Defend|Action required/i,
      'the browser must not reinterpret an evidence failure as an actionable lifecycle verdict');
    const currentFacts = await page.evaluate(tradeId => {
      const host = document.querySelector(`[data-auth-position-detail="${tradeId}"]`);
      return Object.fromEntries(Array.from(host?.querySelectorAll(
        '.authposside > .authmetricgrid .authmetric') || []).map(row => [
        row.querySelector('span')?.textContent.trim(),
        {
          value: row.querySelector('b')?.textContent.trim(),
          reason: row.querySelector('small')?.textContent.trim() || '',
          title: row.getAttribute('title') || ''
        }
      ]));
    }, SECOND_BOOK_TRADE_ID);
    assert.equal(currentFacts['Open P/L'].value, 'Unavailable');
    assert.match(currentFacts['Open P/L'].reason, /missing executable close side/i);
    assert.equal(currentFacts['Closing cash flow'].value, 'Unavailable');
    assert.match(currentFacts['Closing cash flow'].reason, /exact executable close side/i);
    assert.match(currentFacts.Underlying.value, /^\$/);
    assert.match(currentFacts['POP now'].value, /%$/);
    assert.doesNotMatch(JSON.stringify(currentFacts), /−\\$84|\\-\\$84/,
      'a stale unrealizedPnlCents compatibility field cannot masquerade as current P/L');
    const management = page.locator(
      `.card.is-focus[data-id="${SECOND_BOOK_TRADE_ID}"] [data-auth-position-management]`);
    await management.waitFor();
    assert.match(await management.textContent(), /Management unavailable/i);
    assert.equal(await management.locator('[data-auth-management-preview], [data-auth-management-apply]').count(), 0,
      'missing evidence exposes neither a preview nor an apply affordance');
    assert.equal(backend.count('POST', '/api/position-transformations/preview'), 0);
    assert.equal(backend.count('POST', '/api/position-transformations/apply'), 0);
    assert.equal(backend.count('GET', `/api/trades/${SECOND_BOOK_TRADE_ID}`), 1);
    assert.deepEqual(pageErrors, [], `lifecycle presentation emitted page errors: ${pageErrors.join('\n')}`);

    const source = fs.readFileSync(path.join(PUBLIC, 'index.html'), 'utf8');
    assert.doesNotMatch(source, /function authLifecyclePriority\b/);
    assert.doesNotMatch(source, /function authLifecycleBlockedByEvidence\b/);
  } finally {
    await context.close();
  }
});

test('Position management renders exact Book projections and keyboard-confirms the existing signed transformation', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  documents.tradeDetail.analysis = lifecycleAnalysisFixture({
    tradeId: BOOK_TRADE_ID,
    quantity: documents.tradeDetail.trade.qty,
    bookActions: bookActionProjectionSet(documents.tradeDetail.trade)
  });
  const backend = await installBackend(page, { bookDocuments: documents });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.state?.level === 'position'
      && window.DeskBackend.state().position?.data?.trade?.id === tradeId
      && Array.from(document.querySelectorAll(
        `[data-auth-position-detail="${tradeId}"] [data-auth-position-management]`))
        .some(host => host.getClientRects().length && host.querySelector('[data-auth-close-quantity]')),
    BOOK_TRADE_ID);

    const management = page.locator(
      `.card.is-focus[data-id="${BOOK_TRADE_ID}"] [data-auth-position-management]`);
    const text = (await management.textContent()).replace(/\s+/g, ' ');
    assert.match(text, /Hold/);
    assert.match(text, /\$97,520/);
    assert.match(text, /\$432\.10/);
    assert.match(text, /Put obligation\s*\$0/);
    assert.match(text, /Action cash\s*\$0/);
    assert.match(text, /0 acted · 2 remain/);
    assert.match(text, /Accept call-away/);
    assert.equal(await page.locator('[data-auth-manage="resume"]').count(), 1,
      'Position keeps one canonical Edit as new idea action');

    await management.locator('[data-auth-management-preview="CALL_AWAY:2"]').click();
    await page.waitForFunction(() => document.querySelector(
      '.card.is-focus [data-auth-position-management] [data-auth-management-apply]'));
    const callAwayRequest = backend.requests.filter(row => row.method === 'POST'
      && row.path === '/api/position-transformations/preview').at(-1);
    assert.deepEqual(callAwayRequest.body, {
      source: 'PRACTICE_TRADE',
      sourceId: BOOK_TRADE_ID,
      planId: BOOK_PLAN_ID,
      expectedPlanVersion: 31,
      action: 'ASSIGNMENT',
      legIndex: 1
    }, 'call-away selects the one exact short call and reuses the existing lifecycle transformation');
    assert.match((await management.textContent()).replace(/\s+/g, ' '), /AAPL -200 sh/,
      'the selected call-away exposes the existing projection’s exact post-action shares');

    await management.locator('[data-auth-management-reset]').click();
    await management.locator('[data-auth-management-select="HOLD:0"]').click();
    assert.equal(backend.count('POST', '/api/position-transformations/preview'), 1,
      'Hold is the explicit current-book projection and never fabricates a transaction');

    const closeSelect = management.locator('[data-auth-close-quantity]');
    await closeSelect.selectOption('CLOSE_ALL:2');
    await management.locator('[data-auth-management-close]').click();
    await page.waitForFunction(() => document.querySelector(
      '.card.is-focus [data-auth-position-management] [data-auth-management-apply]'));
    const closeAllRequest = backend.requests.filter(row => row.method === 'POST'
      && row.path === '/api/position-transformations/preview').at(-1);
    assert.deepEqual(closeAllRequest.body, {
      source: 'PRACTICE_TRADE',
      sourceId: BOOK_TRADE_ID,
      planId: BOOK_PLAN_ID,
      expectedPlanVersion: 31,
      action: 'CLOSE'
    }, 'Close all reuses the existing whole-position transformation without a parallel quantity path');

    await management.locator('[data-auth-management-reset]').click();
    await closeSelect.selectOption('CLOSE_ONE:1');
    const review = management.locator('[data-auth-management-close]');
    await review.focus();
    await review.press('Enter');
    await page.waitForFunction(() =>
      window.DeskBackend.state && document.querySelector('[data-auth-management-apply]'));

    const previewRequest = backend.requests.filter(row => row.method === 'POST'
      && row.path === '/api/position-transformations/preview').at(-1);
    assert.deepEqual(previewRequest.body, {
      source: 'PRACTICE_TRADE',
      sourceId: BOOK_TRADE_ID,
      planId: BOOK_PLAN_ID,
      expectedPlanVersion: 31,
      action: 'PARTIAL_CLOSE',
      closeQuantity: 1
    }, 'the browser routes identity only; quantity, Plan version, and action are exact');
    const reviewText = (await management.textContent()).replace(/\s+/g, ' ');
    assert.match(reviewText, /Current close cash\s*−\$187\.90/);
    assert.match(reviewText, /Fees\s*−\$2\.60/);
    assert.match(reviewText, /Realized by action\s*\+\$241\.60/);
    assert.match(reviewText, /no change yet/i);
    const reviewGeometry = await management.evaluate(host => {
      const panel = host.closest('.authmanagementpanel');
      const panelRect = panel.getBoundingClientRect();
      const facts = host.querySelector('.authactionfacts');
      return {
        factsDisplay: getComputedStyle(facts).display,
        panelBottom: panelRect.bottom,
        childBottom: Math.max(...Array.from(host.children)
          .filter(node => getComputedStyle(node).display !== 'none')
          .map(node => node.getBoundingClientRect().bottom)),
        panelOverflowY: getComputedStyle(panel).overflowY
      };
    });
    assert.equal(reviewGeometry.factsDisplay, 'grid',
      'the signed review retains the exact projected post-action Book receipt');
    assert.ok(reviewGeometry.childBottom <= reviewGeometry.panelBottom + 1,
      `the signed review fits its desktop management panel: ${JSON.stringify(reviewGeometry)}`);

    const apply = management.locator('[data-auth-management-apply]');
    await apply.focus();
    await apply.press('Enter');
    await page.waitForFunction(() => document.querySelector('#stage')?.classList.contains('lv-book'));
    const applyRequest = backend.requests.filter(row => row.method === 'POST'
      && row.path === '/api/position-transformations/apply').at(-1);
    assert.deepEqual(applyRequest.body, Object.assign({}, previewRequest.body, {
      previewToken: '1784563200.fixture-signed-preview'
    }), 'apply repeats the exact reviewed request and adds only its signed preview token');
    assert.equal(backend.count('POST', '/api/position-transformations/preview'), 3);
    assert.equal(backend.count('POST', '/api/position-transformations/apply'), 1);
    assert.deepEqual(pageErrors, [], `Position management emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Position management assignment selects the exact short put without inventing a second action path', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  const trade = documents.activeTrades[0];
  trade.strategy = 'PUT_CREDIT_SPREAD';
  trade.legs = [
    {
      type: 'PUT', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 205, expiration: '2026-08-21', entryPrice: 1.25
    },
    {
      type: 'PUT', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 215, expiration: '2026-08-21', entryPrice: 3.41
    }
  ];
  documents.tradeDetail.analysis = lifecycleAnalysisFixture({
    tradeId: BOOK_TRADE_ID,
    quantity: trade.qty,
    bookActions: bookActionProjectionSet(trade)
  });
  const backend = await installBackend(page, { bookDocuments: documents });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    const management = page.locator(
      `.card.is-focus[data-id="${BOOK_TRADE_ID}"] [data-auth-position-management]`);
    await management.locator('[data-auth-management-preview="ASSIGNMENT:2"]').click();
    await page.waitForFunction(() => document.querySelector(
      '.card.is-focus [data-auth-position-management] [data-auth-management-apply]'));

    const request = backend.requests.filter(row => row.method === 'POST'
      && row.path === '/api/position-transformations/preview').at(-1);
    assert.deepEqual(request.body, {
      source: 'PRACTICE_TRADE',
      sourceId: BOOK_TRADE_ID,
      planId: BOOK_PLAN_ID,
      expectedPlanVersion: 31,
      action: 'ASSIGNMENT',
      legIndex: 1
    }, 'assignment selects the one exact short put and reuses the signed lifecycle transformation');
    assert.match((await management.textContent()).replace(/\s+/g, ' '), /AAPL 200 sh/,
      'the selected assignment exposes the existing projection’s exact post-action shares');
    assert.equal(backend.count('POST', '/api/position-transformations/apply'), 0,
      'review remains read-only until the explicit Apply action');
    assert.deepEqual(pageErrors, [], `Position assignment emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Position management is contained on mobile and Roll reuses the exact New Idea fork', async () => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  documents.planPortfolio[0].plan = plan(31, {
    id: BOOK_PLAN_ID, symbol: 'AAPL', status: 'POSITION_OPEN', assumptionsEditable: false
  });
  documents.tradeDetail.analysis = lifecycleAnalysisFixture({
    tradeId: BOOK_TRADE_ID,
    quantity: documents.tradeDetail.trade.qty,
    bookActions: bookActionProjectionSet(documents.tradeDetail.trade)
  });
  const backend = await installBackend(page, {
    bookDocuments: documents,
    workspaceContext: {
      accountId: ACCOUNT_ID, scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK',
      routeState: 'book', goal: 'INCOME', view: 'NEUTRAL',
      horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => {
      const host = document.querySelector(
        `.card.is-focus[data-id="${tradeId}"] [data-auth-position-management]`);
      const card = host && host.closest('.card');
      const position = window.DeskBackend.state().position;
      return window.state?.level === 'position'
        && position?.phase === 'ready'
        && position?.data?.auxiliaryPending !== true
        && host && host.getClientRects().length > 0
        && card && card.style.transform === '' && card.style.transition === '';
    },
      BOOK_TRADE_ID);

    const management = page.locator(
      `.card.is-focus[data-id="${BOOK_TRADE_ID}"] [data-auth-position-management]`);
    const geometry = await management.evaluate(host => {
      const rect = host.getBoundingClientRect();
      const controls = Array.from(host.querySelectorAll('button,select')).map(node => {
        const box = node.getBoundingClientRect();
        return { left: box.left, right: box.right, width: box.width, height: box.height,
          display: getComputedStyle(node).display, hidden: node.hidden };
      });
      return {
        viewport: document.documentElement.clientWidth,
        left: rect.left,
        right: rect.right,
        width: rect.width,
        display: getComputedStyle(host).display,
        text: host.textContent.replace(/\s+/g, ' ').trim(),
        overflowX: getComputedStyle(host).overflowX,
        overflowY: getComputedStyle(host).overflowY,
        controls
      };
    });
    assert.ok(geometry.left >= 0 && geometry.right <= geometry.viewport,
      `management panel stays inside 390px: ${JSON.stringify(geometry)}`);
    assert.ok(geometry.controls.every(row => row.left >= geometry.left - 1
      && row.right <= geometry.right + 1 && row.height >= 40),
    `every mobile management control is contained and touchable: ${JSON.stringify(geometry)}`);
    assert.notEqual(geometry.overflowY, 'scroll',
      'Position management participates in the one page scroller');

    const roll = management.locator('[data-auth-management-roll]');
    await roll.focus();
    await roll.press('Enter');
    await page.waitForFunction(([candidateId, tradeId]) => window.DeskBackend.state().selected?.id === candidateId
      && window.decide?.managementIntent === 'ROLL_HELD_PACKAGE'
      && window.decide?.sourcePositionId === tradeId,
    [CUSTOM_CANDIDATE_ID, BOOK_TRADE_ID]);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/custom`), 1,
      'Roll shapes the exact held package through the canonical New Idea custom-package owner');
    assert.equal(backend.count('POST', '/api/position-transformations/preview'), 0,
      'a roll is not previewed until exact replacement contracts exist');
    assert.deepEqual(pageErrors, [], `mobile Position management emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('delayed Home context preserves roster and Plan DOM identity, focus, and scroll', async () => {
  const context = await browser.newContext({ viewport: { width: 390, height: 520 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
  page.setDefaultTimeout(8000);
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
        legs: host.querySelectorAll('.legr').length,
        failed: host.querySelectorAll('.authfailed').length,
        loading: /Loading the exact position/i.test(host.textContent),
        sourceAction: host.querySelector('[data-hist-refresh]')?.textContent.trim(),
        sourceActionSymbol: host.querySelector('[data-hist-refresh]')?.getAttribute('data-hist-refresh'),
        deadLinks: host.querySelectorAll('a[href^="/#/"]').length,
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
    // The action offered beside a missing chart must be one the desk can actually perform. It was
    // a link to /#/data/sources — a route the deleted SPA owned — so pressing it reloaded the desk
    // onto a hash nothing handles.
    assert.equal(rendered.deadLinks, 0, 'no control points at a route this build does not serve');
    assert.equal(rendered.sourceAction, 'Refresh AAPL market data →');
    assert.equal(rendered.sourceActionSymbol, 'AAPL',
      'and it names the symbol whose data it will refresh');
    assert.match(rendered.text, /Price history unavailable/i);
    assert.match(rendered.missing.find(row => row.key === 'history')?.message || '',
      /daily history is not stored/i);
    assert.equal(backend.count('GET', '/api/trades/' + BOOK_TRADE_ID), 1);
    assert.equal(backend.count('GET', '/api/research/AAPL/history'), 1,
      'Home and Position share one complete stored-history artifact');
    assert.deepEqual(pageErrors, [], `partial Position Bloom emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a structural Position failure renders its error and an in-place retry', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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

test('Home 1-session and 5-session history use their actual daily observations instead of implying intraday data', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  documents.history.candles = [
    { date: '2026-07-10', open: 211, high: 214, low: 210, close: 213 },
    { date: '2026-07-13', open: 213, high: 216, low: 212, close: 215 },
    { date: '2026-07-14', open: 215, high: 218, low: 214, close: 217 },
    { date: '2026-07-15', open: 217, high: 220, low: 216, close: 219 },
    { date: '2026-07-16', open: 219, high: 221, low: 218, close: 220 },
    { date: '2026-07-17', open: 218, high: 221, low: 217, close: 220 },
    { date: '2026-07-20', open: 220, high: 223, low: 219, close: 222.22 }
  ];
  await installBackend(page, { bookDocuments: documents });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('#stage[data-book-authority="ready"] #chainBand [data-hist-host="home"] [data-hist-svg] rect');
    const oneDayButton = page.locator('#chainBand [data-hist-host="home"] [data-hist-pill="1d"]');
    assert.equal(await oneDayButton.isDisabled(), false, '1 session is a usable latest-session daily view');
    assert.equal((await oneDayButton.textContent()).trim(), '1 session');
    await oneDayButton.click();
    const oneDay = await page.evaluate(() => {
      const wrap = document.querySelector('#chainBand [data-hist-host="home"]');
      const svg = wrap.querySelector('[data-hist-svg]');
      const wicks = Array.from(svg.querySelectorAll('line'))
        .filter(line => ['var(--profit)', 'var(--loss)'].includes(line.getAttribute('stroke')));
      return {
        wicks: wicks.length,
        coneLabel: Array.from(svg.querySelectorAll('text')).some(node => node.textContent === 'today'),
        receipt: wrap.querySelector('[data-hist-read]').textContent
      };
    });
    assert.equal(oneDay.wicks, 1);
    assert.equal(oneDay.coneLabel, false);
    assert.match(oneDay.receipt, /1 session · daily bars/i);

    await page.locator('#chainBand [data-hist-host="home"] [data-hist-pill="1w"]').click();
    assert.equal((await page.locator('#chainBand [data-hist-host="home"] [data-hist-pill="1w"]').textContent()).trim(), '5 sessions');
    const oneWeek = await page.evaluate(() => {
      const wrap = document.querySelector('#chainBand [data-hist-host="home"]');
      const wicks = Array.from(wrap.querySelectorAll('[data-hist-svg] line'))
        .filter(line => ['var(--profit)', 'var(--loss)'].includes(line.getAttribute('stroke')))
        .map(line => Number(line.getAttribute('x1')));
      return {
        wicks: wicks.length,
        coneLabel: Array.from(wrap.querySelectorAll('[data-hist-svg] text'))
          .some(node => node.textContent === 'today'),
        receipt: wrap.querySelector('[data-hist-read]').textContent,
        observedWidth: Math.max(...wicks) - Math.min(...wicks),
        plotWidth: wrap.querySelector('[data-hist-svg]').viewBox.baseVal.width
      };
    });
    assert.equal(oneWeek.wicks, 5);
    assert.equal(oneWeek.coneLabel, false);
    assert.match(oneWeek.receipt, /5 sessions · daily bars/i);
    assert.ok(oneWeek.observedWidth > oneWeek.plotWidth * 0.6,
      `five actual sessions own the 1W plot (${oneWeek.observedWidth}/${oneWeek.plotWidth})`);
    assert.deepEqual(pageErrors, [], `short Home history presets emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('New Idea exposes every returned headline through one actionable list and releases it to mobile page scroll', async () => {
  const market = focusedHomeDocuments('AMD', 168.45);
  const seed = market.news.items[0];
  market.news.items = Array.from({ length: 20 }, (_, index) => Object.assign({}, seed, {
    headline: `AMD research headline ${index + 1}`,
    url: `https://example.test/amd-research-${index + 1}`
  }));
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    viewport: { width: 1920, height: 1080 },
    homeContextBySymbol: { AMD: market }
  });
  try {
    const owner = page.locator('#decMarketPanel [data-news-owner]');
    await owner.locator('[data-news-disclosure]').waitFor();
    const initial = await owner.evaluate(node => ({
      owners: document.querySelectorAll('#decMarketPanel [data-news-owner]').length,
      total: node.querySelectorAll('[data-news-item]').length,
      visible: Array.from(node.querySelectorAll('[data-news-item]'))
        .filter(row => !row.hidden).length,
      hidden: node.querySelectorAll('[data-news-extra][hidden]').length,
      action: node.querySelector('[data-news-disclosure]')?.textContent.trim()
    }));
    assert.deepEqual(initial, {
      owners: 1, total: 20, visible: 2, hidden: 18, action: '+18 more headlines'
    });

    await owner.locator('[data-news-disclosure]').click();
    assert.equal(await owner.locator('[data-news-disclosure]').getAttribute('aria-expanded'), 'true');
    assert.equal(await owner.locator('[data-news-extra][hidden]').count(), 0);
    assert.equal(await owner.locator('[data-news-item]').count(), 20);

    await page.setViewportSize({ width: 390, height: 844 });
    const mobile = await owner.evaluate(node => {
      const style = getComputedStyle(node);
      const pageOwner = node.closest('.decwrap');
      return {
        overflowY: style.overflowY,
        documentCanScroll: document.documentElement.scrollHeight > innerHeight + 2,
        pageOwnerOverflowY: pageOwner && getComputedStyle(pageOwner).overflowY,
        horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1
      };
    });
    assert.equal(mobile.overflowY, 'visible', 'mobile headlines participate in the one page scroller');
    assert.equal(mobile.documentCanScroll, true, 'expanded mobile research remains reachable by page scroll');
    assert.equal(mobile.pageOwnerOverflowY, 'visible',
      'the document, not a nested New Idea panel, owns mobile scrolling');
    assert.equal(mobile.horizontalOverflow, false);
    await owner.locator('[data-news-item]').last().scrollIntoViewIfNeeded();
    assert.equal(await owner.locator('[data-news-item]').last().isVisible(), true,
      'the last returned headline remains reachable through the owning document scroll');
    assert.deepEqual(pageErrors, [], `headline disclosure emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Position Bloom renders backend trade, payoff, summary, and Research receipts only', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  documents.tradeDetail.current.greeks = {
    deltaShares: -42.63,
    gammaSharesPerDollar: 0.17328,
    thetaCentsPerDay: 126.898,
    vegaCentsPerPoint: 746.85
  };
  const backend = await installBackend(page, {
    bookDocuments: documents,
    bookTradeCurrentConflict: {
      decisionUnrealizedPnlCents: -999999,
      currentUnderlyingCents: 101,
      currentMarketAvailability: currentAvailability(),
      greeks: {
        deltaShares: 999, gammaSharesPerDollar: 999,
        thetaCentsPerDay: 99900, vegaCentsPerPoint: 99900
      }
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    try {
      await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready',
        null, { timeout: 10000 });
    } catch (error) {
      const diagnosis = await page.evaluate(() => ({
        book: window.DeskBackend.state().book,
        rows: window.POS?.map(row => ({ id: row.id, phase: row._positionPhase })),
        body: document.body.textContent.slice(0, 1200)
      }));
      error.message += `\nCached Position Book diagnosis: ${JSON.stringify(diagnosis)}`;
      throw error;
    }
    await page.waitForSelector('#stage[data-book-authority="ready"] #book .card[data-id="'
      + BOOK_TRADE_ID + '"]', { timeout: 10000 });
    await page.waitForSelector('#authBookFanLegend [data-fan-pos="'
      + BOOK_TRADE_ID + '"]', { timeout: 10000 });

    const home = await page.evaluate(tradeId => {
      const state = window.DeskBackend.state();
      const card = document.querySelector('#book .card[data-id="' + tradeId + '"]');
      const stage = document.querySelector('#stage');
      return {
        authority: stage && stage.getAttribute('data-book-authority'),
        positionCount: stage && stage.getAttribute('data-position-count'),
        phase: state.book && state.book.phase,
        schemaVersion: state.book.data.practiceBook.schemaVersion,
        snapshotId: state.book.data.practiceBook.snapshotId,
        ids: state.book.data.practiceBook.snapshot.activeTrades.map(trade => trade.id),
        poisonedTradePnl: state.book.data.practiceBook.snapshot.activeTrades[0]
          .decisionUnrealizedPnlCents,
        positionPnl: window.POS[0]?.pnl,
        positionSpot: window.POS[0]?.spot,
        positionDelta: window.POS[0]?.greeks?.deltaShares,
        totalValueCents: state.book.data.practiceBook.summary.totalValueCents,
        totalPnlCents: state.book.data.practiceBook.summary.totalPnlCents,
        thetaCentsPerDay: state.book.data.practiceBook.snapshot.greeks.thetaCentsPerDay,
        text: stage.textContent.replace(/\s+/g, ' ').trim(),
        cardText: card && card.textContent.replace(/\s+/g, ' ').trim(),
        workingIdeas: document.querySelector('#univBand .eyebrow')?.textContent.trim(),
        riskHint: document.querySelector('#bookrisk .lenshd .hint')?.textContent.trim(),
        riskPoints: document.querySelectorAll('#authBookRiskViz [data-auth-risk-position]').length,
        riskMapCount: document.querySelectorAll('#authBookRiskViz').length,
        singleReceipt: document.querySelector('.bookonefacts')?.textContent
          .replace(/\s+/g, ' ').trim(),
        fanWidth: document.querySelector('#authBookFan')?.getBoundingClientRect().width,
        futuresWidth: document.querySelector('#bookrisk')?.getBoundingClientRect().width,
        bookRiskVisible: document.querySelector('#bookrisk')?.getClientRects().length > 0,
        boardOverflows: document.querySelector('#stage .board').scrollHeight
          > document.querySelector('#stage .board').clientHeight + 2,
        boardSize: {
          client: document.querySelector('#stage .board').clientHeight,
          scroll: document.querySelector('#stage .board').scrollHeight,
          template: getComputedStyle(document.querySelector('#stage .board')).gridTemplateRows
        },
        lowerRowHeight: document.querySelector('#book')?.getBoundingClientRect().height,
        authoredSparkPaths: card ? card.querySelectorAll('.cplspark path').length : 0,
        visibleCardIds: Array.from(document.querySelectorAll('#book .card[data-id]'))
          .filter(row => row.getClientRects().length).map(row => row.dataset.id)
      };
    }, BOOK_TRADE_ID);
    assert.equal(home.authority, 'ready');
    assert.equal(home.positionCount, '1');
    assert.equal(home.phase, 'ready');
    assert.equal(home.schemaVersion, 'practice-book-read-v1');
    assert.equal(home.snapshotId, 'pbs_backend_contract');
    assert.deepEqual(home.ids, [BOOK_TRADE_ID]);
    assert.equal(home.poisonedTradePnl, -999999,
      'the adversarial compatibility field reached the browser');
    assert.equal(home.positionPnl, 246.8,
      'marksByTrade wins when the static trade row carries a conflicting current P/L');
    assert.equal(home.positionSpot, 222.22,
      'marksByTrade wins when the static trade row carries a conflicting current quote');
    assert.equal(home.positionDelta, -42.63,
      'marksByTrade wins when the static trade row carries conflicting current Greeks');
    assert.deepEqual(home.visibleCardIds, [BOOK_TRADE_ID],
      'the served roster contains exactly the active backend trade');
    assert.equal(home.totalValueCents, 9876543);
    assert.equal(home.totalPnlCents, -123457);
    assert.equal(home.thetaCentsPerDay, -1234,
      'the bridge preserves the server signed Greek rather than normalizing or recomputing it');
    assert.match(home.text, /\$98,765/,
      'the Home headline renders the backend account-equity sentinel');
    assert.match(home.text, /−\$1,234\.57|-\$1,234\.57/,
      'the Home headline renders the backend since-funding P/L sentinel');
    assert.match(home.cardText, /AAPL/i);
    assert.match(home.cardText, /call debit spread/i);
    assert.match(home.cardText, /246\.80|247/,
      'the roster exposes the backend unrealized P/L sentinel');
    assert.equal(home.authoredSparkPaths, 0,
      'Home leaves mark history blank until a stored marksHistory receipt is loaded');
    assert.equal(home.workingIdeas, 'Working ideas');
    assert.match(home.riskHint, /BOOK total · colors = synchronized positions/i,
      'the compact legend names the measured whole-book series and its position contributions');
    assert.equal(home.riskPoints, 0,
      'one position does not pretend that a one-dot comparison is a useful risk map');
    assert.equal(home.riskMapCount, 0,
      'the sparse hero gives its canvas to measured futures rather than an empty comparative facet');
    assert.match(home.singleReceipt, /Chance.*Max loss.*Entry credit.*Expiry/i,
      'the displaced one-dot facet becomes an exact single-position receipt');
    assert.ok(home.fanWidth > home.futuresWidth * 0.6,
      `the sparse position fan earns the majority of its compact Futures panel (${home.fanWidth}/${home.futuresWidth})`);
    assert.equal(home.bookRiskVisible, true,
      'one position keeps one compact Futures panel beside the permanent Home workbench');
    assert.ok(home.boardSize.scroll - home.boardSize.client <= 320,
      `below the full desktop widths the board scrolls modestly, never unboundedly (${JSON.stringify(home.boardSize)})`);
    assert.ok(home.lowerRowHeight >= 140 && home.lowerRowHeight <= 260,
      `the sparse roster rail is content-sized rather than a void (${home.lowerRowHeight}px)`);
    assert.doesNotMatch(home.text,
      /\b(?:authoritative|backend-owned|source-owned|working plans|mark coverage|stored coverage)\b/i,
      'Home speaks in product language rather than implementation vocabulary');
    assert.doesNotMatch(home.text, /Covered strangle|Short-put campaign|Visual fixture/i);

    await page.locator('#authBookFanLegend [data-fan-pos="' + BOOK_TRADE_ID + '"]').click();
    await page.waitForFunction(tradeId => {
      const bridge = window.DeskBackend && window.DeskBackend.state();
      return bridge && bridge.position && bridge.position.phase === 'ready'
        && bridge.position.data.trade.id === tradeId;
    }, BOOK_TRADE_ID, { timeout: 10000 });
    await page.waitForSelector(`#authScenStage-${BOOK_TRADE_ID}[data-position-scenario="ready"]`,
      { timeout: 10000 });
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
      const legRows = Array.from(positionSide?.querySelectorAll('.legr') || []);
      const focusedCard = document.querySelector(`.focus .card[data-id="${tradeId}"]`);
      const accentProbe = document.createElement('span');
      accentProbe.style.color = 'var(--accent)';
      document.body.appendChild(accentProbe);
      const accentColor = getComputedStyle(accentProbe).color;
      accentProbe.remove();
      return {
        id: receipt.trade.id,
        symbol: receipt.trade.symbol,
        unrealizedCents: receipt.tradeDetail.current.unrealizedCents,
        researchSource: receipt.research.quote.source,
        historySource: receipt.history.source,
        headline: receipt.news.items[0].headline,
        planId: receipt.plan.id,
        backendReceiptId: model && model._trade && model._trade.id,
        payoffAtSentinel: model && model.payoffPoints.find(point => point.price === 222.22)?.profit,
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
        scenarioBeforeResearch: !!researchRect && !!scenarioRect
          && scenarioRect.top < researchRect.top,
        scenarioVisibleAtInitialScroll: !!scenarioRect && !!viewportRect
          && scenarioRect.top < viewportRect.bottom && scenarioRect.bottom > viewportRect.top,
        researchVisibleAtInitialScroll: !!researchRect && !!viewportRect
          && researchRect.top < viewportRect.bottom && researchRect.bottom > viewportRect.top,
        legText: legRows.map(row => row.textContent.replace(/\s+/g, ' ').trim()),
        legBookSides: legRows.map(row => Array.from(row.querySelectorAll('.legdetail-book span'))
          .filter(cell => /^(?:bid|ask)\b/i.test(cell.textContent.trim()))
          .map(cell => ({ text: cell.textContent.trim(),
            executable: cell.classList.contains('executable') }))),
        editActions: positionSide?.querySelectorAll('[data-auth-manage="resume"]').length || 0,
        inlineLegControls: positionSide?.querySelectorAll('[data-leg]').length || 0,
        currentGreeks: Object.fromEntries(Array.from(
          positionSide?.querySelectorAll('.authposgreeks [data-live]') || []
        ).map(node => [node.getAttribute('data-live'), node.textContent.trim()])),
        recedeVisible: document.querySelector('.recede')?.getClientRects().length > 0,
        recedeDebug: {
          stageCount: stage?.getAttribute('data-position-count'),
          children: document.querySelector('.recede')?.children.length,
          html: document.querySelector('.recede')?.innerHTML,
          display: getComputedStyle(document.querySelector('.recede')).display
        },
        focusedBorder: focusedCard && getComputedStyle(focusedCard).borderTopColor,
        accentColor,
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
    assert.equal(position.backendReceiptId, BOOK_TRADE_ID,
      'the held model retains the exact backend trade receipt instead of a browser-authority flag');
    assert.ok(Math.abs(position.payoffAtSentinel - 246.8) < 1e-9,
      'Position payoff interpolates the server checkpoint at the exact backend mark');
    assert.deepEqual(position.payoffPoints, [
      { price: 200, profit: -432.1 },
      { price: 222.22, profit: 246.8 },
      { price: 230, profit: 1567.9 }
    ]);
    assert.equal(position.scenarioUnavailable, false,
      'the held package is valued on its owning unconditioned stored fan');
    assert.equal(position.payKeyCount, 5,
      'the payoff keeps its values in a dedicated rail instead of overlaying the curve');
    assert.equal(position.payoffOverlayLabels, 0,
      'the Position hero leaves its curve free of competing value labels');
    assert.equal(position.legRows, 2);
    assert.equal(position.legsContained, true,
      'the Position hero keeps every leg visible inside its top-right decision panel');
    assert.match(position.legText[0], /BUY.?CALL.?215.?2 contracts.?bid 5\.10.*ask 5\.35/i,
      'the held leg keeps strike, quantity, and its exact current two-sided book visible');
    assert.match(position.legText[1], /SELL.?CALL.?225.?2 contracts.?bid 3\.00.*ask 3\.20/i);
    assert.deepEqual(position.legBookSides.map(row => row.map(cell => cell.executable)),
      [[true, false], [false, true]],
      'closing a held long highlights bid, while buying back a held short highlights ask');
    assert.equal(position.editActions, 1,
      'the held package has one explicit Edit-as-new-idea journey');
    assert.equal(position.inlineLegControls, 0,
      'the immutable held package does not masquerade as an inline editor');
    assert.deepEqual(position.currentGreeks, {
      delta: '-43 sh', theta: '+$1.27', vega: '+$7.47', gamma: '0.173'
    }, 'Position renders all four exact current sensitivities from the focused trade receipt');
    assert.equal(position.researchPanelCount, 5,
      'market, chain, history, news, and management each receive one readable shared panel');
    assert.equal(position.researchPanelsContained, true,
      'the expanded Position research band remains horizontally contained');
    assert.equal(position.scenarioBeforeResearch, true,
      'desktop Position composes current position, possible futures, then supporting market receipts');
    assert.equal(position.scenarioVisibleAtInitialScroll, true,
      'the scenario spectrum and path fan are visible in the initial desktop composition');
    assert.equal(position.researchVisibleAtInitialScroll, true,
      'desktop users can see source-owned market context before choosing a scenario');
    assert.equal(position.recedeVisible, false,
      `one focused position does not reserve an empty sibling column (${JSON.stringify(position.recedeDebug)})`);
    assert.notEqual(position.focusedBorder, position.accentColor,
      'opening recency remains a badge and does not turn the whole Position cyan');
    assert.match(position.text, /Backend research sentinel headline/i,
      'Position Bloom restores its backend Research/news context');
    assert.match(position.text, /Payoff at expiration/i);
    assert.match(position.text, /Idea & management/i);
    assert.doesNotMatch(position.text,
      /\b(?:authoritative position|backend checkpoints|exact stored legs|quote receipt|source-backed news|plan & management|conditioned|KEYWORD_DERIVED|MANAGE_REVIEW)\b/i,
      'Position Bloom keeps implementation vocabulary out of the user-facing surface');
    assert.match(position.text, /What happens if — Possible futures/i,
      'Position opens on the honest unconditioned fan without inventing a named story');
    assert.doesNotMatch(position.text, /MI400 ramp|Visual fixture|Time decay pays you about/i,
      'the focused backend position does not inherit static fixture research or mechanics');
    assert.equal(position.documentOverflow, false);

    assert.equal(backend.count('GET', '/api/trades/' + BOOK_TRADE_ID), 1);
    assert.equal(backend.count('GET', '/api/portfolio/book'), 1);
    assert.equal(backend.count('GET', '/api/portfolio/summary'), 0);
    assert.equal(backend.count('GET', '/api/portfolio/heat'), 0);
    assert.equal(backend.count('GET', '/api/portfolio/greeks'), 0);
    assert.equal(backend.count('GET', '/api/portfolio/book-risk'), 0);
    assert.equal(backend.count('GET', '/api/research/AAPL'), 1);
    assert.equal(backend.count('GET', '/api/research/AAPL/history'), 1,
      'Home context and the focused Position share one complete stored-history receipt');
    assert.equal(backend.count('GET', '/api/research/AAPL/news'), 1);
    assert.equal(backend.count('GET', '/api/plans/' + BOOK_PLAN_ID + '/manage'), 1);
    assert.equal(backend.count('GET', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/latest`), 1,
      'Position Bloom names the owning Plan stored ensemble as an optional receipt');
    assert.equal(backend.count('POST', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble`), 0,
      'Position Bloom never creates a replacement fan');
    const positionPaths = backend.requests.filter(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`);
    assert.ok(positionPaths.some(row => row.body.interaction == null
      && !Array.isArray(row.body.waypoints)),
      'opening a Position reuses the owning unconditioned stored fan');
    assert.equal(positionPaths.every(row => row.body.ensembleId === BOOK_ENSEMBLE_ID), true,
      'every Position projection names the exact immutable ensemble loaded from its owning Plan');
    assert.equal(positionPaths.filter(row => row.body.interaction != null
      || Array.isArray(row.body.waypoints)).length, 0,
      'opening a Position does not condition paths until a scenario is selected');
    assert.deepEqual(pageErrors, [], `authoritative Position Bloom emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Position composes ready and unavailable futures without desktop clipping or mobile nested scroll', async () => {
  async function openPosition(documents, viewport) {
    const context = await browser.newContext({ viewport });
    const page = await context.newPage();
    page.setDefaultTimeout(12000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    await installBackend(page, { bookDocuments: documents });
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.state?.level === 'position'
      && window.state?.focus === tradeId
      && ['partial', 'ready'].includes(window.DeskBackend.state().position?.phase),
    BOOK_TRADE_ID);
    return { context, page, pageErrors };
  }
  async function measure(page) {
    return page.evaluate(() => {
      const host = document.querySelector('[data-auth-position-detail].authpos');
      const hero = host?.querySelector('.authposhero');
      const side = hero?.querySelector('.authposside');
      const scenarios = host?.querySelector('.authscenstage');
      const area = scenarios?.querySelector('.authscenarioarea');
      const fan = scenarios?.querySelector('.authpathpanel');
      const legs = hero?.querySelector('.authposlegs');
      const research = host?.querySelector('.authresearchgrid');
      const researchPanels = Array.from(research?.children || []);
      const hostRect = host?.getBoundingClientRect();
      const rect = node => node?.getBoundingClientRect();
      const inside = node => {
        const box = rect(node);
        return !!box && !!hostRect && box.top >= hostRect.top - 1
          && box.bottom <= hostRect.bottom + 1;
      };
      const within = (owner, node) => {
        const box = rect(node);
        const boundary = rect(owner);
        return !!box && !!boundary && box.top >= boundary.top - 1
          && box.bottom <= boundary.bottom + 1;
      };
      const verticalOwners = Array.from(host?.querySelectorAll(
        '.authnews,.declegs,.authpathviewport,.authresearchgrid') || [])
        .filter(node => ['auto', 'scroll'].includes(getComputedStyle(node).overflowY))
        .map(node => node.className);
      const edit = host?.querySelector('[data-auth-manage="resume"]');
      const bottomPanels = researchPanels.map(panel => {
        const heading = panel.querySelector('.lenshd .eyebrow')?.textContent.trim() || '';
        const directChildren = Array.from(panel.children)
          .filter(node => rect(node)?.width > 0 && rect(node)?.height > 0);
        const actualScrollers = Array.from(panel.querySelectorAll('*')).filter(node => {
          const style = getComputedStyle(node);
          return ['auto', 'scroll'].includes(style.overflowY)
            && node.scrollHeight > node.clientHeight + 2;
        });
        return {
          heading,
          client: panel.clientHeight,
          scroll: panel.scrollHeight,
          childrenInside: directChildren.every(node => within(panel, node)),
          actualScrollers: actualScrollers.map(node => String(node.className || ''))
        };
      });
      return {
        scenarioState: scenarios?.getAttribute('data-position-scenario'),
        hostClient: host?.clientHeight, hostScroll: host?.scrollHeight,
        heroInside: inside(hero), scenariosInside: inside(scenarios), researchInside: inside(research),
        scenarioHeight: rect(scenarios)?.height || 0,
        heroHeight: rect(hero)?.height || 0,
        researchHeight: rect(research)?.height || 0,
        heroClient: hero?.clientHeight || 0,
        sideClient: side?.clientHeight || 0,
        sideScroll: side?.scrollHeight || 0,
        scenarioClient: scenarios?.clientHeight || 0,
        scenarioScroll: scenarios?.scrollHeight || 0,
        researchClient: research?.clientHeight || 0,
        researchScroll: research?.scrollHeight || 0,
        areaHeight: rect(area)?.height || 0,
        fanHeight: rect(fan)?.height || 0,
        fanWidth: rect(fan)?.width || 0,
        fanClient: fan?.clientHeight || 0,
        fanScroll: fan?.scrollHeight || 0,
        fanChildrenInside: Array.from(fan?.children || [])
          .filter(node => rect(node)?.width > 0 && rect(node)?.height > 0)
          .every(node => within(fan, node)),
        fanChildren: Array.from(fan?.children || []).map(node => ({
          className: String(node.className || ''),
          top: Math.round(rect(node)?.top || 0), bottom: Math.round(rect(node)?.bottom || 0)
        })),
        legsClient: legs?.clientHeight || 0,
        legsScroll: legs?.scrollHeight || 0,
        legsTop: Math.round(rect(legs)?.top || 0),
        legsBottom: Math.round(rect(legs)?.bottom || 0),
        legChildrenInside: Array.from(legs?.children || [])
          .filter(node => rect(node)?.width > 0 && rect(node)?.height > 0)
          .every(node => within(legs, node)),
        legChildren: Array.from(legs?.children || []).map(node => ({
          className: String(node.className || ''),
          top: Math.round(rect(node)?.top || 0), bottom: Math.round(rect(node)?.bottom || 0)
        })),
        editInside: within(legs, edit),
        bottomPanels,
        chainRows: research?.querySelectorAll('.authposchain .authchainrow').length || 0,
        chainMore: research?.querySelector('.authpositionchainmore')?.textContent
          .replace(/\s+/g, ' ').trim() || '',
        chainMoreHeight: rect(research?.querySelector('.authpositionchainmore'))?.height || 0,
        horizontalOverflow: document.documentElement.scrollWidth
          > document.documentElement.clientWidth + 1,
        hostOverflowY: host && getComputedStyle(host).overflowY,
        verticalOwners,
        editHeight: rect(edit)?.height || 0
      };
    });
  }

  const readyDocuments = populatedBookDocuments();
  const chainStrikes = [200, 205, 210, 215, 220, 225, 230, 235, 240];
  readyDocuments.chain.calls = chainStrikes.map((strike, index) => ({
    strike, bid: 13 - index, ask: 13.25 - index
  }));
  readyDocuments.chain.puts = chainStrikes.map((strike, index) => ({
    strike, bid: 2 + index, ask: 2.25 + index
  }));
  const newsSeed = readyDocuments.news.items[0];
  readyDocuments.news.items = Array.from({ length: 12 }, (_, index) => Object.assign({}, newsSeed, {
    headline: `Position geometry headline ${index + 1}`,
    url: `https://example.test/position-geometry-${index + 1}`
  }));
  const ready = await openPosition(readyDocuments, { width: 2000, height: 963 });
  try {
    await ready.page.waitForSelector(
      `#authScenStage-${BOOK_TRADE_ID}[data-position-scenario="ready"] .authpathchart`);
    await ready.page.waitForTimeout(500);
    const at2000 = await measure(ready.page);
    if (process.env.POSITION_CAPTURE_DIR) {
      await ready.page.screenshot({
        path: `${process.env.POSITION_CAPTURE_DIR}/position-ready-2000x963.png`,
        fullPage: true
      });
    }
    assert.equal(at2000.horizontalOverflow, false);
    assert.equal(at2000.hostScroll <= at2000.hostClient + 2, true,
      `2000x963 Position fits its default canvas (${JSON.stringify(at2000)})`);
    assert.equal(at2000.heroInside && at2000.scenariosInside && at2000.researchInside, true,
      'current position, possible futures, and supporting market evidence are all visible');
    assert.ok(at2000.areaHeight >= 220 && at2000.fanHeight >= 170 && at2000.fanWidth >= 360,
      `the scenario spectrum and path fan retain useful geometry (${JSON.stringify(at2000)})`);
    assert.equal(at2000.fanScroll <= at2000.fanClient + 2 && at2000.fanChildrenInside, true,
      `every path receipt remains visible inside its panel (${JSON.stringify(at2000)})`);
    assert.equal(at2000.legChildrenInside && at2000.editInside, true,
      `the exact held legs and their one edit action remain visible (${JSON.stringify(at2000)})`);
    assert.equal(at2000.sideScroll <= at2000.sideClient + 2, true,
      `the complete Position-now receipt fits without hidden overflow (${JSON.stringify(at2000)})`);
    const fixedAt2000 = at2000.bottomPanels.filter(panel => panel.heading !== 'News');
    assert.equal(fixedAt2000.every(panel => panel.scroll <= panel.client + 2
      && panel.childrenInside), true,
    `every non-list supporting receipt fits without hidden clipping (${JSON.stringify(at2000.bottomPanels)})`);
    const scrollersAt2000 = at2000.bottomPanels.flatMap(panel =>
      panel.actualScrollers.map(className => ({ heading: panel.heading, className })));
    assert.equal(scrollersAt2000.every(row => row.heading === 'News'
      && /authnews/.test(row.className)), true,
    `News is the only genuine bottom-rail list overflow owner (${JSON.stringify(scrollersAt2000)})`);
    assert.equal(at2000.chainRows, 5,
      'Position keeps the five nearest actionable strikes in the default supporting rail');
    assert.match(at2000.chainMore, /Open 4 more nearby strikes in New idea/i,
      'the complete chain remains reachable through the existing New Idea journey');

    await ready.page.setViewportSize({ width: 2560, height: 1440 });
    const at2560 = await measure(ready.page);
    if (process.env.POSITION_CAPTURE_DIR) {
      await ready.page.screenshot({
        path: `${process.env.POSITION_CAPTURE_DIR}/position-ready-2560x1440.png`,
        fullPage: true
      });
    }
    assert.equal(at2560.horizontalOverflow, false);
    assert.equal(at2560.hostScroll <= at2560.hostClient + 2, true);
    assert.equal(at2560.heroInside && at2560.scenariosInside && at2560.researchInside, true);
    assert.equal(at2560.sideScroll <= at2560.sideClient + 2, true);
    assert.equal(at2560.bottomPanels.filter(panel => panel.heading !== 'News')
      .every(panel => panel.scroll <= panel.client + 2 && panel.childrenInside), true,
    `the supporting receipts remain fully contained at 2560 (${JSON.stringify(at2560.bottomPanels)})`);
    assert.equal(at2560.chainRows, 5);

    await ready.page.setViewportSize({ width: 390, height: 844 });
    const mobile = await measure(ready.page);
    if (process.env.POSITION_CAPTURE_DIR) {
      await ready.page.screenshot({
        path: `${process.env.POSITION_CAPTURE_DIR}/position-ready-390x844.png`,
        fullPage: true
      });
    }
    assert.equal(mobile.horizontalOverflow, false);
    assert.equal(mobile.hostOverflowY, 'visible');
    assert.deepEqual(mobile.verticalOwners, [],
      'Position mobile uses the page as its one vertical scroll owner');
    assert.equal(mobile.bottomPanels.every(panel => panel.scroll <= panel.client + 2
      && panel.childrenInside), true,
    `mobile releases every supporting receipt into the page without clipping (${JSON.stringify(mobile.bottomPanels)})`);
    assert.ok(mobile.chainMoreHeight >= 40,
      `the full-chain journey remains a mobile touch target (${mobile.chainMoreHeight}px)`);
    assert.ok(mobile.editHeight >= 40,
      `the one Edit action remains a touch target (${mobile.editHeight}px)`);
    assert.deepEqual(ready.pageErrors, []);
  } finally {
    await ready.context.close();
  }

  const unavailableDocuments = populatedBookDocuments();
  unavailableDocuments.positionEnsemble = null;
  const unavailable = await openPosition(unavailableDocuments, { width: 2560, height: 1440 });
  try {
    await unavailable.page.waitForSelector(
      `#authScenStage-${BOOK_TRADE_ID}[data-position-scenario="unavailable"]`);
    await unavailable.page.waitForTimeout(500);
    const desktop = await measure(unavailable.page);
    if (process.env.POSITION_CAPTURE_DIR) {
      await unavailable.page.screenshot({
        path: `${process.env.POSITION_CAPTURE_DIR}/position-unavailable-2560x1440.png`,
        fullPage: true
      });
    }
    assert.ok(desktop.scenarioHeight > 0 && desktop.scenarioHeight <= 90,
      `unavailable paths collapse to one receipt rather than a chart void (${desktop.scenarioHeight}px)`);
    assert.equal(desktop.hostScroll <= desktop.hostClient + 2, true);
    assert.equal(desktop.heroInside && desktop.scenariosInside && desktop.researchInside, true);

    await unavailable.page.setViewportSize({ width: 390, height: 844 });
    const mobile = await measure(unavailable.page);
    if (process.env.POSITION_CAPTURE_DIR) {
      await unavailable.page.screenshot({
        path: `${process.env.POSITION_CAPTURE_DIR}/position-unavailable-390x844.png`,
        fullPage: true
      });
    }
    assert.equal(mobile.horizontalOverflow, false);
    assert.equal(mobile.hostOverflowY, 'visible');
    assert.deepEqual(mobile.verticalOwners, []);
    assert.ok(mobile.scenarioHeight <= 150,
      `mobile unavailable paths remain a compact receipt (${mobile.scenarioHeight}px)`);
    assert.deepEqual(unavailable.pageErrors, []);
  } finally {
    await unavailable.context.close();
  }
});

test('Position keeps recorded payoff and saved futures when the current executable mark is unavailable', async () => {
  const context = await browser.newContext({ viewport: { width: 2000, height: 963 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const documents = populatedBookDocuments();
  const detailedTrade = JSON.parse(JSON.stringify(documents.tradeDetail.trade));
  /* Reproduce a lean Book roster followed by the complete focused receipt. The recorded payoff
     and scenarios deliberately exist only on GET /api/trades/:id; today's executable mark does
     not exist at all. */
  delete documents.activeTrades[0].terminalPayoff;
  delete documents.activeTrades[0].spotPnl;
  delete documents.activeTrades[0].scenarios;
  documents.tradeDetail.trade = detailedTrade;
  documents.tradeDetail.current = null;
  documents.tradeDetail.analysis = lifecycleAnalysisFixture({
    presentation: {
      evidenceState: 'CURRENT_MARK_UNAVAILABLE',
      actionable: false,
      userFacingVerdict: 'No verdict · executable close unavailable',
      userFacingStatus: 'Executable close unavailable',
      tone: 'WARNING',
      trigger: {
        code: 'CURRENT_MARK_UNAVAILABLE',
        label: 'Executable close unavailable',
        dimension: 'MECHANICS',
        status: 'BLOCKED',
        basis: 'The exact executable close mark is missing from the backend receipt.'
      },
      sortPriority: 1
    },
    decision: {
      verdict: 'NEEDS_EVIDENCE',
      summary: 'NO VERDICT: current executable close evidence is unavailable; no action is recommended.'
    }
  });
  documents.tradeDetail.analysis.lifecycle.currentChoice.close = {
    executable: false, price: null, unavailableReason: 'The current executable close mark is unavailable.'
  };
  await installBackend(page, { bookDocuments: documents });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForSelector(
      `#authScenStage-${BOOK_TRADE_ID}[data-position-scenario="ready"] .authpathchart`);
    await page.waitForSelector(`#authPay-${BOOK_TRADE_ID} path`);
    await page.waitForTimeout(500);
    const result = await page.evaluate(tradeId => {
      const host = document.querySelector(`[data-auth-position-detail="${tradeId}"]`);
      const payoff = host?.querySelector(`#authPay-${CSS.escape(tradeId)}`);
      const fan = host?.querySelector(`#authPath-${CSS.escape(tradeId)}`);
      const stage = host?.querySelector('.authscenstage');
      const legs = host?.querySelector('.authposlegs');
      const legRows = Array.from(legs?.querySelectorAll('.legr') || []);
      const text = host?.textContent.replace(/\s+/g, ' ').trim() || '';
      const rect = node => node?.getBoundingClientRect();
      const inside = (owner, node) => {
        const a = rect(owner), b = rect(node);
        return !!a && !!b && b.top >= a.top - 1 && b.bottom <= a.bottom + 1
          && b.left >= a.left - 1 && b.right <= a.right + 1;
      };
      return {
        text,
        payoffPaths: payoff?.querySelectorAll('path').length || 0,
        fanPaths: fan?.querySelectorAll('[data-fan-line]').length || 0,
        scenarioState: stage?.getAttribute('data-position-scenario'),
        legRows: legRows.length,
        legsContained: legRows.every(row => inside(legs, row) && inside(host, row)),
        legsBox: rect(legs) && {
          top: Math.round(rect(legs).top), bottom: Math.round(rect(legs).bottom)
        },
        legBoxes: legRows.map(row => ({
          top: Math.round(rect(row).top), bottom: Math.round(rect(row).bottom)
        })),
        hostClient: host?.clientHeight || 0,
        hostScroll: host?.scrollHeight || 0,
        payoffInside: inside(host, payoff),
        fanInside: inside(stage, fan),
        horizontalOverflow: document.documentElement.scrollWidth
          > document.documentElement.clientWidth + 1
      };
    }, BOOK_TRADE_ID);
    assert.ok(result.payoffPaths > 0,
      'the entry-owned terminal payoff renders without a current executable close');
    assert.ok(result.fanPaths > 0,
      'the saved authoritative fan values the exact held package without a current mark');
    assert.equal(result.scenarioState, 'ready');
    assert.match(result.text, /No verdict · executable close unavailable/i);
    assert.match(result.text, /exact executable close mark is missing/i);
    assert.match(result.text, /Closing cash flow\s*unavailable/i);
    assert.match(result.text, /Management unavailable/i);
    assert.doesNotMatch(result.text,
      /PositionAnimation|frame-selection|lifecycle contract|omitted the exact/i,
      'backend contract vocabulary never becomes recovery copy');
    assert.equal(result.horizontalOverflow, false);
    assert.equal(result.hostScroll <= result.hostClient + 2, true,
      `the unavailable-current-mark state fits the full desktop canvas (${JSON.stringify(result)})`);
    assert.equal(result.payoffInside && result.fanInside, true);
    assert.equal(result.legRows, 2);
    assert.equal(result.legsContained, true,
      `the unavailable-current-mark evidence receipt does not clip the exact held legs (${JSON.stringify(result)})`);
    if (process.env.POSITION_CAPTURE_DIR) {
      await page.screenshot({
        path: `${process.env.POSITION_CAPTURE_DIR}/position-current-mark-unavailable-2000x963.png`,
        fullPage: true
      });
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(250);
    const mobile = await page.evaluate(tradeId => {
      const host = document.querySelector(`[data-auth-position-detail="${tradeId}"]`);
      const legs = Array.from(host?.querySelectorAll('.authposlegs .legr') || []);
      const targets = Array.from(host?.querySelectorAll(
        '[data-auth-position-retry],[data-auth-manage="resume"]') || []);
      const verticalOwners = Array.from(host?.querySelectorAll(
        '.authnews,.declegs,.authpathviewport,.authresearchgrid') || [])
        .filter(node => ['auto', 'scroll'].includes(getComputedStyle(node).overflowY))
        .map(node => String(node.className || ''));
      return {
        horizontalOverflow: document.documentElement.scrollWidth
          > document.documentElement.clientWidth + 1,
        hostOverflowY: host && getComputedStyle(host).overflowY,
        verticalOwners,
        legRows: legs.length,
        targetHeights: targets.map(node => node.getBoundingClientRect().height),
        payoffPaths: host?.querySelectorAll(`#authPay-${CSS.escape(tradeId)} path`).length || 0,
        fanPaths: host?.querySelectorAll(`#authPath-${CSS.escape(tradeId)} [data-fan-line]`).length || 0
      };
    }, BOOK_TRADE_ID);
    assert.equal(mobile.horizontalOverflow, false);
    assert.equal(mobile.hostOverflowY, 'visible');
    assert.deepEqual(mobile.verticalOwners, [],
      'missing-current-mark Position uses the page as its only mobile scroll owner');
    assert.equal(mobile.legRows, 2);
    assert.equal(mobile.payoffPaths > 0 && mobile.fanPaths > 0, true);
    assert.equal(mobile.targetHeights.every(height => height >= 40), true,
      `mobile recovery/edit actions remain touch targets (${JSON.stringify(mobile.targetHeights)})`);
    if (process.env.POSITION_CAPTURE_DIR) {
      await page.screenshot({
        path: `${process.env.POSITION_CAPTURE_DIR}/position-current-mark-unavailable-390x844.png`,
        fullPage: true
      });
    }
    assert.deepEqual(pageErrors, []);
  } finally {
    await context.close();
  }
});

test('Position path refusal is compact, actionable, and never exposes transport contract language', async () => {
  const context = await browser.newContext({ viewport: { width: 2000, height: 963 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    positionScenarioWrongProjectionGridOnce: true
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForSelector(
      `#authScenStage-${BOOK_TRADE_ID}[data-position-scenario="error"]`);
    const before = await page.evaluate(tradeId => {
      const host = document.querySelector(`[data-auth-position-detail="${tradeId}"]`);
      const stage = host?.querySelector('.authscenstage');
      return {
        text: stage?.textContent.replace(/\s+/g, ' ').trim() || '',
        height: stage?.getBoundingClientRect().height || 0,
        hostClient: host?.clientHeight || 0,
        hostScroll: host?.scrollHeight || 0,
        retry: !!stage?.querySelector('[data-auth-position-futures-retry]'),
        canonicalEditCount: document.querySelectorAll('[data-auth-manage="resume"]').length
      };
    }, BOOK_TRADE_ID);
    assert.match(before.text, /Possible futures unavailable/i);
    assert.doesNotMatch(before.text,
      /PositionAnimation|frame-selection|lifecycle contract|omitted the exact/i);
    assert.equal(before.retry, true);
    assert.equal(before.canonicalEditCount, 1,
      'path recovery reuses the one exact held-package edit journey');
    assert.ok(before.height > 0 && before.height <= 110,
      `a rejected saved fan is a compact recovery receipt (${before.height}px)`);
    assert.equal(before.hostScroll <= before.hostClient + 2, true);

    const requestsBefore = backend.requests.filter(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`).length;
    await page.evaluate(tradeId => {
      /* A conditioned story can fail after its pin is accepted. Retry must not be suppressed by
         that pin; it returns to the exact unconditioned saved fan and reads it again. */
      window.pinnedScen[tradeId] = 1;
    }, BOOK_TRADE_ID);
    await page.locator('[data-auth-position-futures-retry]').click();
    await page.waitForSelector(
      `#authScenStage-${BOOK_TRADE_ID}[data-position-scenario="ready"] .authpathchart`);
    await page.waitForSelector(
      `#authScenStage-${BOOK_TRADE_ID}[data-position-scenario="ready"] [data-fan-line]`);
    const requestsAfter = backend.requests.filter(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`).length;
    const retried = backend.requests.filter(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`).at(-1);
    assert.ok(requestsAfter > requestsBefore,
      'Retry paths repeats only the stored-fan valuation read');
    assert.equal(retried.body.ensembleId, BOOK_ENSEMBLE_ID,
      'recovery reprojects the exact refreshed Plan ensemble, never a default or replacement fan');
    const recovered = await page.evaluate(tradeId => {
      const stage = document.querySelector(
        `#authScenStage-${tradeId}[data-position-scenario="ready"]`);
      return {
        text: stage?.textContent.replace(/\s+/g, ' ').trim() || '',
        pinned: Object.prototype.hasOwnProperty.call(window.pinnedScen, tradeId),
        paths: stage?.querySelectorAll('[data-fan-line]').length || 0
      };
    }, BOOK_TRADE_ID);
    assert.equal(recovered.pinned, false,
      'retry clears the failed story pin before restoring the base fan');
    assert.ok(recovered.paths > 0);
    assert.doesNotMatch(recovered.text,
      /PositionAnimation|frame-selection|lifecycle contract|omitted the exact/i);
    if (process.env.POSITION_CAPTURE_DIR) {
      await page.screenshot({
        path: `${process.env.POSITION_CAPTURE_DIR}/position-path-recovered-2000x963.png`,
        fullPage: true
      });
    }
    assert.deepEqual(pageErrors, []);
  } finally {
    await context.close();
  }
});

test('global New idea from Position starts blank, then preserves the focused Position after explicit analysis', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    await page.waitForSelector('[data-auth-workbench-query]');
    assert.deepEqual(await page.evaluate(() => ({
      symbol: window.homeIdea?.symbol,
      level: window.state?.level,
      positionFocus: window.state?.focus,
      decide: window.decide
    })), {
      symbol: null,
      level: 'book',
      positionFocus: BOOK_TRADE_ID,
      decide: null
    }, 'an ambient Position never becomes the global New idea default or a modal composer');
    assert.equal(backend.count('POST', '/api/plans'), 0);
    await page.locator('[data-auth-workbench-query]').fill('AAPL');
    await page.locator('[data-auth-workbench-query]').press('Enter');
    await declareWorkbench(page);
    await page.locator('[data-auth-workbench-analyze]').click();
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
      decidePlanId: PLAN_ID,
      decideSymbol: 'AAPL',
      level: 'book',
      focus: BOOK_TRADE_ID,
      positionTradeId: BOOK_TRADE_ID,
      cardPreserved: true,
      interrupted: false
    }, 'the explicitly chosen subject opens while preserving the Position identity underneath');

    assert.equal(backend.count('GET', `/api/plans/${BOOK_PLAN_ID}`), 0,
      'fresh idea lookup never hydrates the frozen Position Plan as a working Plan');
    assert.equal(backend.count('POST', '/api/plans'), 1,
      'the backend creates a distinct mutable working Plan when only a frozen match exists');
    const create = backend.requests.find(row => row.method === 'POST' && row.path === '/api/plans');
    assert.equal(create.body.symbol, 'AAPL');
    assert.equal(create.body.originPlanId, null,
      'a global idea is not silently linked to or derived from the frozen Position Plan');

    await page.locator('[data-dec="back"]').click();
    await page.waitForFunction(tradeId => !window.decide && window.state?.level === 'book'
      && window.state?.focus === tradeId, BOOK_TRADE_ID);
    assert.equal(await page.evaluate(tradeId => window.__positionCardBeforeNewIdea
      === document.querySelector(`.card[data-id="${tradeId}"]`), BOOK_TRADE_ID), true,
    'closing the fresh idea returns to Home while preserving the same Position DOM identity');
    assert.deepEqual(pageErrors, [],
      `Position to global New idea emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('New idea rotates a stale create idempotency key after a Plan changes declarations', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    await startNewIdea(page);
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

test('Position opens on the unconditioned stored P/L fan and reuses it for playback', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    await page.waitForSelector(`${stageSelector}[data-position-scenario="ready"] path.fan-focusghost`,
      { timeout: 10000 });
    const opened = await page.evaluate(({ tradeId, stageSelector }) => {
      const stage = document.querySelector(stageSelector);
      const state = window.scenSt(tradeId);
      const focus = stage.querySelector('path.fan-focus');
      const ghost = stage.querySelector('path.fan-focusghost');
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
    assert.equal(opened.pinned, undefined,
      'opening a held position does not silently condition the stored market fan');
    assert.equal(opened.progress, 1,
      'the resting fan shows the complete possible-futures field');
    assert.equal(opened.playing, false);
    assert.equal(opened.pathSpace, 'pnl');
    assert.match(opened.stageText, /What happens if — Possible futures/i);
    assert.doesNotMatch(opened.stageText, /select a scenario|conditioned|authoritative/i);
    assert.ok(opened.ghostLength > 0 && opened.focusLength > 0,
      'the full backend path remains visible as context at t=0 instead of leaving a void');

    await page.locator(`${stageSelector} [data-auth-path-view="price"]`).click();
    await page.waitForSelector(`${stageSelector} .authpathchart[data-path-space="price"]`);
    const priceView = await page.evaluate(stageSelector => ({
      space: document.querySelector(stageSelector)?.querySelector('.authpathchart')
        ?.getAttribute('data-path-space'),
      stageCount: document.querySelectorAll(stageSelector).length
    }), stageSelector);
    assert.equal(priceView.space, 'price');
    assert.equal(priceView.stageCount, 1,
      'switching chart meaning rebinds the one Position scenario component without duplicating it');
    await page.locator(`${stageSelector} [data-auth-path-view="pnl"]`).click();
    await page.waitForSelector(`${stageSelector} .authpathchart[data-path-space="pnl"]`);

    const projectionsBeforePlayback = backend.count('POST',
      `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`);
    await page.locator('[data-auth-position-detail] [data-auth-manage="forward"]').click();
    await page.waitForFunction(tradeId => window.scenSt(tradeId).t > 0,
      BOOK_TRADE_ID, { timeout: 3000 });
    assert.equal(backend.count('POST', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`),
      projectionsBeforePlayback, 'playback reuses the unconditioned stored projection');
    const requests = backend.requests.filter(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`
      && row.body.focusPositionKey === BOOK_TRADE_ID
      && row.body.interaction == null
      && !Array.isArray(row.body.waypoints));
    assert.ok(requests.length >= 1,
      'the resting Position fan is the exact held package valued on its owning stored ensemble');
    assert.ok(requests.some(row => row.body.limit === 24));
    requests.forEach(row => assert.equal(Object.hasOwn(row.body, 'paths'), false));
    assert.deepEqual(pageErrors, [], `default Position fan emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('HTTP Position scenario renders the stored noisy path neighborhood and exact checkpoint journey', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    await page.waitForSelector(`${stageSelector}[data-position-scenario="ready"]`);

    await page.locator(`${stageSelector} .srow[data-si="0"]`).click();
    await page.waitForSelector(`${stageSelector}[data-position-scenario="ready"] path.fan-focus`,
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
        contextPaths: stage.querySelectorAll('path.fan-context').length,
        focusPaths: stage.querySelectorAll('path.fan-focus').length,
        bandCount: stage.querySelectorAll('.authpathchart path.fan-band').length,
        focusPrices: focus.prices,
        focusSourcePathIndex: focus.sourcePathIndex,
        interaction: response.receipt.interaction,
        receiptFocusKey: response.receipt.focusPositionKey,
        modelFocusKey: response.checkpoints.modelReceipt.focusPositionKey,
        valuationFingerprint: response.checkpoints.modelReceipt.valuationFingerprint,
        chartSpace: stage.querySelector('.authpathchart')?.getAttribute('data-path-space'),
        pnlPathCount: window.authPositionPnlFan(window.byId[tradeId])?.paths?.length || 0,
        directionChanges,
        tileText: stage.querySelector('.srow[data-si="0"]').textContent
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
    assert.deepEqual(ready.interaction, {
      story: 'MARKET_CRASH',
      movePct: -20,
      ivShiftPoints: 14,
      elapsedSessions: 5,
      sourcePathIndex: null
    }, 'the exact server receipt round-trips the compact Position story declaration');
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
    assert.match(ready.text, /3 of 500 paths from this market fan/i);
    assert.doesNotMatch(ready.text,
      /Conditioned Monte Carlo|model position-path-model-test-v1|valuation position-valuation|stored paths/i,
      'the user-facing chart explains the market story without exposing simulation receipts');
    assert.doesNotMatch(ready.text, /browser-generated|locally generated|straight-line/i,
      'the authoritative panel makes no claim of a local or straight-line simulation');

    /* PositionAnimation v2 names checkpoint index 4 as the terminal cash-settlement frame.
       Half of that exact index lands on checkpoint 2 — no browser date arithmetic participates. */
    const scrub = page.locator(`${stageSelector} .scrub[data-wf="scrub"]`);
    await scrub.evaluate(input => {
      input.value = '50';
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
    assert.deepEqual(middle, { price: '$218.20', pnl: '+$175', progress: 50 },
      'scrubbing lands on the exact second stored valuation checkpoint');

    await scrub.evaluate(input => {
      input.value = '100';
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    /* Fully scrubbed lands on the exact backend terminal frame, price $190.30. */
    await page.waitForFunction(selector => document.querySelector(selector)
      ?.querySelector('[data-live="px"]')?.textContent === '$190.30', stageSelector);
    const terminal = await page.evaluate(stageSelector => {
      const stage = document.querySelector(stageSelector);
      const svg = stage.querySelector('.authpathchart');
      const focus = svg.querySelector('path.fan-focus');
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
    assert.equal(terminal.price, '$190.30');
    assert.match(terminal.pnl, /−\$250|-\$250/);
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
    assert.equal(played.price, '$190.30');
    assert.match(played.pnl, /−\$250|-\$250/);
    assert.equal(played.scrub, 100,
      'playback completes on the same exact endpoint as direct checkpoint scrubbing');

    await page.setViewportSize({ width: 390, height: 844 });
    const resized = await page.evaluate(stageSelector => ({
      sameNode: window.__positionScenarioStage === document.querySelector(stageSelector),
      documentOverflow: document.documentElement.scrollWidth
        > document.documentElement.clientWidth,
      overflowers: Array.from(document.querySelectorAll('body *')).filter(node => {
        const rect = node.getBoundingClientRect();
        return rect.width > 0 && (rect.right > document.documentElement.clientWidth + 1
          || rect.left < -1);
      }).slice(0, 12).map(node => ({
        tag: node.tagName, id: node.id, className: String(node.className || ''),
        left: Math.round(node.getBoundingClientRect().left),
        right: Math.round(node.getBoundingClientRect().right)
      }))
    }), stageSelector);
    assert.equal(resized.sameNode, true,
      'responsive adaptation preserves the mounted scenario-stage DOM identity');
    assert.equal(resized.documentOverflow, false,
      `the conditioned path panel remains contained at mobile width: ${JSON.stringify(resized.overflowers)}`);

    const projection = backend.requests.find(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`
      && row.body.interaction?.story === 'MARKET_CRASH');
    assert.ok(projection, 'the named story adds one conditioned read of the owning stored ensemble');
    assert.equal(projection.body.focusPositionKey, BOOK_TRADE_ID);
    assert.equal(projection.body.limit, 48,
      'the bounded renderer requests enough stored neighbors for credible trajectory texture');
    assert.deepEqual(projection.body.interaction, {
      story: 'MARKET_CRASH',
      movePct: null,
      ivShiftPoints: null,
      elapsedSessions: null,
      sourcePathIndex: null
    }, 'the browser sends only the named story and leaves its numeric policy to the backend');
    for (const field of ['canvas', 'waypoints', 'pathWaypoints', 'paths']) {
      assert.equal(Object.hasOwn(projection.body, field), false,
        `the named story does not send browser-authored ${field}`);
    }
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
  page.setDefaultTimeout(8000);
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
    await page.waitForSelector(`${stageSelector}[data-position-scenario="ready"]`);

    await page.locator(`${stageSelector} .srow[data-si="0"]`).click();
    for (let attempt = 0; attempt < 120 && !backend.requests.some(row => row.method === 'POST'
      && row.path === projectionPath
      && row.body.interaction?.story === 'MARKET_CRASH'); attempt += 1) {
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
    assert.equal(backend.requests.filter(row => row.method === 'POST'
      && row.path === projectionPath
      && row.body.interaction?.story === 'MARKET_CRASH').length, 1,
      `the test clears the scenario only after its conditioned request is in flight: ${JSON.stringify({ inFlightDiagnostic, pageErrors })}`);

    await page.locator(`${stageSelector} .srow[data-si="0"]`).click();
    await page.waitForSelector(`${stageSelector}[data-position-scenario="ready"]`);
    await page.waitForFunction(() => window.DeskBackend.state().positionScenario?.phase === 'ready',
      null, { timeout: 5000 });
    await page.waitForTimeout(80);

    const cleared = await page.evaluate(({ tradeId, stageSelector }) => {
      const stage = document.querySelector(stageSelector);
      const position = window.byId[tradeId];
      return {
        stagePhase: stage.getAttribute('data-position-scenario'),
        pinned: Object.prototype.hasOwnProperty.call(window.pinnedScen, tradeId),
        pinnedTiles: stage.querySelectorAll('.srow.pinned').length,
        chartCount: stage.querySelectorAll('.authpathchart').length,
        scenarioPhase: position._positionScenarioPhase,
        animationRestored: !!position.authoritativeAnimation,
        conditionedWaypoints: position.authoritativeAnimation?.receipt
          ?.conditioningAssumptions?.waypoints?.length || 0,
        text: stage.textContent.replace(/\s+/g, ' ').trim()
      };
    }, { tradeId: BOOK_TRADE_ID, stageSelector });
    assert.equal(cleared.stagePhase, 'ready');
    assert.equal(cleared.pinned, false);
    assert.equal(cleared.pinnedTiles, 0);
    assert.equal(cleared.chartCount, 1);
    assert.equal(cleared.scenarioPhase, 'ready');
    assert.equal(cleared.animationRestored, true,
      'unpinning returns to the useful unconditioned stored fan');
    assert.equal(cleared.conditionedWaypoints, 0,
      'the late conditioned response cannot overwrite the restored possible-futures field');
    assert.match(cleared.text, /What happens if — Possible futures/i);
    assert.equal(backend.count('POST', `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble`), 0);
    assert.deepEqual(pageErrors, [], `delayed Position scenario emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('reopening cached Position A after Position B restores adapter ownership before conditioning A', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    try {
      await page.waitForFunction(tradeId => window.DeskBackend.state().position?.phase === 'ready'
        && window.DeskBackend.state().position?.data?.trade?.id === tradeId,
      BOOK_TRADE_ID, { timeout: 10000 });
    } catch (error) {
      const diagnosis = await page.evaluate(tradeId => ({
        route: window.state,
        adapter: window.DeskBackend.state().position,
        cardPhase: window.byId?.[tradeId]?._positionPhase,
        cardDataId: window.byId?.[tradeId]?._positionData?.trade?.id,
        workspace: window.WORKSPACE
      }), BOOK_TRADE_ID);
      error.message += `\nCached Position diagnosis: ${JSON.stringify(diagnosis)}`;
      throw error;
    }
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
    assert.equal(backend.count('GET', firstDetailPath), 1,
      'Home, cached Position A, and its reopen share one exact trade-detail receipt');

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

    await page.waitForSelector(`${firstStage}[data-position-scenario="ready"]`);
    await page.locator(`${firstStage} .srow[data-si="0"]`).click();
    await page.waitForSelector(`${firstStage}[data-position-scenario="ready"] path.fan-focus`,
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
        secondConditioned: !!(second.authoritativeAnimation?.receipt
          ?.conditioningAssumptions?.waypoints?.length)
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
    assert.equal(conditioned.secondConditioned, false,
      'B can retain its independent possible-futures fan but cannot receive A\'s conditioned story');

    const firstProjection = backend.requests.find(row => row.method === 'POST'
      && row.path === firstProjectionPath
      && row.body.interaction?.story === 'MARKET_CRASH');
    assert.ok(firstProjection, 'A sends one focused stored-ensemble projection');
    assert.equal(firstProjection.body.ensembleId, BOOK_ENSEMBLE_ID);
    assert.equal(firstProjection.body.focusPositionKey, BOOK_TRADE_ID);
    assert.equal(backend.requests.filter(row => row.method === 'POST'
      && row.path === secondProjectionPath
      && row.body.interaction?.story === 'MARKET_CRASH').length, 0,
    'B\'s owning Plan is never conditioned while A is focused');
    assert.deepEqual(pageErrors, [], `A→B→A Position ownership emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Position exact-path interaction preserves immutable source identity on the authoritative trade', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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

    const requestedInteraction = {
      story: null,
      movePct: null,
      ivShiftPoints: 2,
      elapsedSessions: 10,
      sourcePathIndex: 70
    };
    const result = await page.evaluate(async ({ interaction, limit }) => {
      const phases = [];
      const listener = event => {
        if (String(event.detail?.phase || '').startsWith('position-scenario-')) {
          phases.push(event.detail.phase);
        }
      };
      document.addEventListener('strikebench:desk-backend', listener);
      const before = window.DeskBackend.state();
      const response = await window.DeskBackend.positionScenario({ interaction, limit });
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
        returnedInteraction: response.receipt.interaction,
        returnedPathWaypoints: response.receipt.conditioningPathWaypoints,
        pathSelection: response.paths.selection,
        focusSourcePathIndex: response.paths.receipt.focusSourcePathIndex,
        focusedSteps: focused.steps.map(step => step.focusPnlCents),
        valuationFingerprint: response.receipt.valuationFingerprint,
        modelValuationFingerprint: response.checkpoints.modelReceipt.valuationFingerprint
      };
    }, { interaction: requestedInteraction, limit: 7 });

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
    assert.deepEqual(result.returnedInteraction, requestedInteraction,
      'the server round-trips the exact user declaration as immutable lineage');
    assert.deepEqual(result.returnedPathWaypoints, [],
      'an exact stored path needs no browser- or server-authored waypoint surrogate');
    assert.equal(result.pathSelection, 'EXACT_SOURCE_PATH');
    assert.equal(result.focusSourcePathIndex, requestedInteraction.sourcePathIndex,
      'the focused source row is the exact immutable index the user selected');
    assert.deepEqual(result.focusedSteps, [24680, 15000, 17500, -5000, -25000, -43200]);
    assert.equal(result.valuationFingerprint, result.modelValuationFingerprint);

    const projectionRequests = backend.requests.filter(row => row.method === 'POST'
      && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`);
    const conditionedRequests = projectionRequests.filter(row =>
      row.body.interaction?.sourcePathIndex === requestedInteraction.sourcePathIndex);
    assert.equal(conditionedRequests.length, 1,
      'one explicit story adds exactly one conditioned read beside the batched Book receipt');
    assert.deepEqual(conditionedRequests[0].body, {
      ensembleId: BOOK_ENSEMBLE_ID,
      interaction: requestedInteraction,
      limit: 7,
      focusPositionKey: BOOK_TRADE_ID
    }, 'the browser sends the immutable source identity and exact trade id, not generated paths');
    for (const field of ['canvas', 'waypoints', 'pathWaypoints', 'paths']) {
      assert.equal(Object.hasOwn(conditionedRequests[0].body, field), false,
        `the exact-path action does not send browser-authored ${field}`);
    }
    assert.equal(projectionRequests.filter(row => row.body.interaction == null).length, 0,
      'the default Position fan is the position slice in the one Practice Book read, not a Plan N+1 request');
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
      option: 'positionScenarioWrongProjectionGrid',
      label: 'projection animation grid',
      expected: /omitted the exact PositionAnimation v2 lifecycle and frame-selection contract/
    },
    {
      option: 'positionScenarioWrongPathWaypoints', label: 'explicit path waypoints',
      pathWaypoints: [{ sessionProgress: 5, priceRatio: 0.975, tolerance: 0.01 }]
    }
  ]) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    page.setDefaultTimeout(8000);
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
      assert.match(rejected.message, failure.expected
        || /did not retain the linked Plan, focused trade, stored ensemble, path, and valuation identity/);
      assert.equal(rejected.scenarioPhase, 'error');
      assert.equal(rejected.scenarioError, rejected.message);
      assert.equal(rejected.bookPhase, 'ready');
      assert.equal(rejected.positionPhase, 'ready');
      assert.equal(rejected.newIdeaRequestSeqAfter, rejected.newIdeaRequestSeqBefore);
      assert.equal(rejected.storedEnsembleId, BOOK_ENSEMBLE_ID);
      assert.equal(rejected.storedEnsembleFingerprint, BOOK_ENSEMBLE_FINGERPRINT);
      if (failure.pathWaypoints) {
        const projection = backend.requests.find(row => row.method === 'POST'
          && row.path === `/api/plans/${BOOK_PLAN_ID}/outcomes/ensemble/paths`
          && Array.isArray(row.body.pathWaypoints));
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
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    /* Home's ambient hydration absorbs one transient research failure on its own;
       two failures guarantee the Decide read is also interrupted. */
    failResearchTimes: 2, universeSymbols: ['AAPL', 'AMD']
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
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
    /* ambient Home hydration (interrupted) + the Decide read (interrupted) + one retry */
    assert.equal(backend.count('GET', '/api/research/AMD'), 3);
    assert.equal(await page.locator('.authfailed').count(), 0);
    assert.deepEqual(pageErrors, [], `authoritative retry emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a zero-candidate backend result remains a stable Desk with screening receipts', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    await startNewIdea(page);
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
      assert.match(empty.text, /no package fits this idea yet/i);
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
        enabled: !!window.DeskBackend,
        backendReceiptId: active.backend && active.backend.id,
        selectedId: window.decide.candId,
        label: document.querySelector('.fanr.sel .fnm')?.textContent,
        payoffAtSpot: window.__testNearestPayoffPoint(active, 100)?.profit ?? null,
        payoffPoints: active.payoffPoints,
        pathCount: document.querySelectorAll('#decPay path').length,
        provenance: window.DeskBackend.state().market.provenance,
        ensembleId: window.decide.ensemble.ensemble.id,
        marketIdentity: window.DeskBackend.state().market.identity,
        planRiskMode: window.DeskBackend.state().plan.context.riskMode,
        planHorizonDays: window.DeskBackend.state().plan.context.horizonDays,
        visibleHorizon: window.decide.horizon,
        positionIdentity: active.positionIdentity,
        deskPickId: window.decide.deskPickId,
        pickBadgeCandidate: document.querySelector('.fanr.pick')?.dataset.cand,
        fanSummary: document.querySelector('.dcleft .modebar .hint')?.textContent,
        scenarioHint: document.querySelector('.scenpanel > .lenshd .hint')?.textContent,
        monteCarloStats: document.querySelector('.mcstats')?.textContent,
        maxStoredPathSegments: Math.max(0, ...Array.from(document.querySelectorAll('#mcFan path[d]'))
          .map(path => (path.getAttribute('d').match(/L/g) || []).length)),
        monteCarloPathCount: document.querySelectorAll('#mcFan path[d]').length
      };
    }, CANDIDATE_ID);

    assert.equal(rendered.enabled, true, 'HTTP-served Desk enables the backend bridge');
    assert.equal(rendered.backendReceiptId, CANDIDATE_ID,
      'the selected candidate retains its exact backend receipt instead of a parallel authority flag');
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
    assert.equal(rendered.visibleHorizon, '45 trading days',
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
      families: ['CALL_DEBIT_SPREAD', 'CREDIT_PUT_SPREAD', 'CALENDAR_CALL',
        'CASH_SECURED_PUT', 'PROTECTIVE_PUT', 'NAKED_CALL'],
      visibleCount: 6
    }, 'the Desk retains the exact server-owned catalog receipt');

    assert.match(await page.locator('.strategycoverage').textContent(),
      /1 exact.*6 strategy families available.*Put verticals and calendars/i,
      'strategy breadth is visible before opening the complete catalog');
    await page.locator('.strategycoverage').click();
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
    assert.match(drawer.heading, /Supported strategies.*6 families/i);
    assert.match(drawer.intro, /Every family remains visible/i);
    assert.deepEqual(new Set(drawer.rows.map(row => row.name)), new Set([
      'Debit call spread', 'Bull put credit spread', 'Call calendar',
      'Cash-secured put', 'Protective put', 'Naked call'
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
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { strategyCatalogDelayMs: 1800 });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
    await page.waitForFunction(id => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().selected?.id === id
      && window.decide.orderPreview?.selected?.id === id,
    CANDIDATE_ID, { timeout: 1400 });
    assert.equal(await page.evaluate(() => window.DeskBackend.state().strategyCatalog), null,
      'the recommendation is ready while the independent catalog receipt is still pending');
    await page.waitForFunction(() => window.DeskBackend.state().strategyCatalog?.catalog?.length === 6,
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
        symbol: 'AMD', goal: 'Income', view: 'Neutral', horizonDays: 1
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

test('the UI market switch preserves the complete accepted Plan declaration and no old-world identity', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    const accepted = await page.evaluate(async () => {
      const current = window.DeskBackend.ideaDeclaration();
      await window.DeskBackend.updatePlanDeclaration(Object.assign({}, current, {
        view: 'Bullish',
        horizonDays: 30,
        riskMode: 'aggressive',
        targetCents: 17750,
        holdingsShares: 300,
        costBasisCents: 14400,
        priceAssumptionCents: 15500,
        assignmentPreference: 'WILLING'
      }));
      return window.DeskBackend.ideaDeclaration();
    });
    assert.deepEqual(accepted, {
      symbol: 'AMD',
      planId: PLAN_ID,
      goal: 'INCOME',
      view: 'bullish',
      horizonDays: 30,
      riskMode: 'aggressive',
      targetCents: 17750,
      holdingsShares: 300,
      costBasisCents: 14400,
      priceAssumptionCents: 15500,
      assignmentPreference: 'WILLING',
      originPlanId: null
    }, 'the bridge exposes only the declaration accepted by the exact Plan');

    await page.evaluate(() => window.setMktMode('sim'));
    await page.waitForFunction(id => {
      const state = window.DeskBackend.state();
      return window.decide?.backendPhase === 'ready'
        && state.plan?.id === id
        && state.plan?.marketKind === 'SIMULATED';
    }, SIM_PLAN_ID, { timeout: 10000 });

    const planCreates = backend.requests.filter(row =>
      row.method === 'POST' && row.path === '/api/plans');
    assert.equal(planCreates.length, 1,
      'the UI transition mints exactly one new-world Plan');
    const request = planCreates[0].body;
    assert.deepEqual({
      symbol: request.symbol,
      intent: request.intent,
      thesis: request.thesis,
      horizonDays: request.horizonDays,
      riskMode: request.riskMode,
      targetCents: request.targetCents,
      holdingsShares: request.holdingsShares,
      costBasisCents: request.costBasisCents,
      priceAssumptionCents: request.priceAssumptionCents,
      assignmentPreference: request.assignmentPreference
    }, {
      symbol: 'AMD',
      intent: 'INCOME',
      thesis: 'bullish',
      horizonDays: 30,
      riskMode: 'aggressive',
      targetCents: 17750,
      holdingsShares: 300,
      costBasisCents: 14400,
      priceAssumptionCents: 15500,
      assignmentPreference: 'WILLING'
    }, 'the market switch carries every accepted declaration into the new-world Plan');
    assert.equal(request.planId, undefined);
    assert.equal(request.evaluationId, undefined);
    assert.equal(request.originPlanId, null,
      'old-world Plan, evaluation, and lineage identifiers cannot cross the boundary');
    assert.deepEqual(await page.evaluate(() => window.DeskBackend.ideaDeclaration()), {
      symbol: 'AMD',
      planId: SIM_PLAN_ID,
      goal: 'INCOME',
      view: 'bullish',
      horizonDays: 30,
      riskMode: 'aggressive',
      targetCents: 17750,
      holdingsShares: 300,
      costBasisCents: 14400,
      priceAssumptionCents: 15500,
      assignmentPreference: 'WILLING',
      originPlanId: null
    }, 'the accepted declaration is re-normalized from the returned simulated Plan');
    assert.deepEqual(pageErrors, [], `UI market switch emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('return-to-base adopts the server-owned DEMO lane instead of relabelling it OBSERVED', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    activeMarketLane: 'DEMO',
    baseMarketLane: 'DEMO'
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    const result = await page.evaluate(async () => {
      await window.DeskBackend.transitionWorld('sim', {
        symbol: 'AMD', reopen: false
      });
      const verification = await window.DeskBackend.transitionWorld('observed', {
        symbol: 'AMD', reopen: false
      });
      const state = window.DeskBackend.state();
      return {
        verificationLane: verification.config.marketLane,
        workspaceWorld: state.workspace.receipt.world,
        workspaceLane: state.workspace.receipt.marketLane,
        phase: state.workspace.phase,
        error: state.error && state.error.message
      };
    });
    assert.deepEqual(result, {
      verificationLane: 'DEMO',
      workspaceWorld: 'demo',
      workspaceLane: 'DEMO',
      phase: 'ready',
      error: null
    });
    assert.deepEqual(backend.requests
      .filter(row => row.method === 'PUT' && row.path === '/api/world')
      .map(row => row.body.world), [SIM_WORLD_ID, 'demo']);
    assert.deepEqual(pageErrors, [],
      `DEMO return transition emitted page errors: ${pageErrors.join('\n')}`);
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
        payoffAtSpot: window.__testNearestPayoffPoint(active, 100)?.profit ?? null,
        payoffPathCount: document.querySelectorAll('#decPay path[d]').length,
        dockText: dock?.textContent.replace(/\s+/g, ' ').trim(),
        reviewDisabled: dock?.querySelector('[data-dec="review"]')?.disabled,
        orderState: window.decide.orderPreview.preview.price.executability,
        afterFeeNetCents: window.decide.orderPreview.preview.price.afterFeeNetCents,
        priceUnavailableReason: window.decide.orderPreview.preview.price.unavailableReason,
        candidateFreshness: active.backend.freshness,
        quoteFreshness: state.market.quote.freshness,
        chainFreshness: state.market.chain.freshness,
        ensembleAnchorFreshness: state.ensemble.preview.receipt.anchorFreshness,
        previewFreshness: window.decide.orderPreview.preview.freshness,
        deskPickId: window.decide.deskPickId,
        pickBadges: document.querySelectorAll('.fanr.pick').length,
        rankRead: document.querySelector('.dcleft .modebar .hint')?.textContent
      };
    }, CANDIDATE_ID);

    assert.equal(rendered.payoffAtSpot, 777);
    assert.equal(rendered.payoffPoints.length, 3,
      'the preview-independent evaluation payoff remains active');
    assert.ok(rendered.payoffPathCount > 0,
      'an unavailable execution book cannot blank the candidate payoff chart');
    assert.equal(rendered.orderState, 'UNAVAILABLE');
    assert.equal(rendered.afterFeeNetCents, null);
    assert.match(rendered.priceUnavailableReason, /stale observed option book/i,
      'an unpriced package says WHY rather than showing a zero');
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
    assert.match(rendered.dockText, /execution unavailable/i);
    assert.doesNotMatch(rendered.dockText, /candidate −\$123 debit/i,
      'an unavailable execution preview cannot fall back to the analyzed candidate price');
    assert.match(rendered.dockText, /book unavailable/i);
    assert.match(rendered.dockText,
      /Execution paused: Cannot execute AMD from a stale observed option book/i,
      'the exact package receipt owns the failure reason; ambient quote freshness cannot replace it');
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
      active.price.optionNetPremiumCents = null;
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
        text: document.querySelector('#decMap')?.textContent,
        compactAbsence: document.querySelector('.pickmap')?.classList.contains('no-points')
      };
    }, CANDIDATE_ID);
    assert.equal(map.marks, 0);
    assert.match(map.text, /Comparable chance and EV receipts are unavailable/i);
    assert.equal(map.compactAbsence, true,
      'an unavailable comparison map names the missing receipt without reserving a full chart');
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
        hasMarketCostAlias: Object.hasOwn(candidate, 'marketCostBenchmark'),
        marketCostBenchmarkCents: economics.marketEvAfterCostsCents,
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
      hasMarketCostAlias: presented.hasMarketCostAlias,
      marketCostBenchmarkCents: presented.marketCostBenchmarkCents,
      marketEvRole: presented.marketEvRole,
      verdict: presented.verdict,
      mapRangeCount: presented.mapRangeCount
    }, {
      edge: 14.5,
      edgeLow: -3,
      edgeHigh: 34,
      edgeBasis: 'REALIZED_VOL_AFTER_COSTS',
      hasMarketCostAlias: false,
      marketCostBenchmarkCents: -5200,
      marketEvRole: 'Risk-neutral price-consistency and cost benchmark; not an independent edge test.',
      verdict: 'FAVORABLE',
      mapRangeCount: 1
    });
    assert.match(presented.mapText, /realized-vol EV · after costs/i);
    assert.match(presented.briefText, /realized-volatility EV[\s\S]*\+\$14\.50/i);
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

test('selected package legs keep captured evidence even when the ambient chain has the same contracts', async () => {
  const exact = candidate();
  exact.price = priceReceipt(Object.assign({}, exact.price, {
    source: 'EXACT_PACKAGE_RECEIPT', freshness: 'DELAYED',
    observedAt: 1784563260000, fingerprint: 'exact-package-receipt'
  }));
  exact.legs = [
    {
      type: 'CALL', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 100, expiration: '2026-08-21', entryPrice: 12.4,
      quoteBid: 12.1, quoteAsk: 12.4, quoteAsOfEpochMs: 1784563260000,
      quoteSource: 'EXACT_CAPTURE', quoteFreshness: 'DELAYED',
      quoteIv: 0.77, quoteDelta: 0.31
    },
    {
      type: 'CALL', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 110, expiration: '2026-08-21', entryPrice: 3.3,
      quoteBid: 3.3, quoteAsk: 3.5, quoteAsOfEpochMs: 1784563260000,
      quoteSource: 'EXACT_CAPTURE', quoteFreshness: 'DELAYED',
      quoteIv: 0.66, quoteDelta: 0.22
    }
  ];
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    strategyCandidates: [exact]
  });
  try {
    const receipt = await page.evaluate(() => ({
      legText: document.querySelector('.declegpanel')?.textContent.replace(/\s+/g, ' ').trim(),
      packageText: document.querySelector('.packagebookcol')?.textContent.replace(/\s+/g, ' ').trim(),
      ambientExpiration: window.DeskBackend.state().market?.chain?.expiration
    }));
    assert.equal(receipt.ambientExpiration, '2026-08-21');
    assert.match(receipt.legText, /bid 12\.10\s*\/\s*ask 12\.40/i);
    assert.match(receipt.legText, /bid 3\.30\s*\/\s*ask 3\.50/i);
    assert.match(receipt.legText, /IV 77\.0%.*Δ 0\.31/i);
    assert.match(receipt.packageText, /EXACT_PACKAGE_RECEIPT.*DELAYED/i);
    assert.match(receipt.packageText, /5\.80\s*\/\s*6\.20/i,
      'the same-expiration nearby chain remains independently useful context');
    assert.doesNotMatch(receipt.legText, /5\.80\s*\/\s*6\.20/i,
      'ambient same-contract values cannot decorate a captured exact leg');
    assert.deepEqual(pageErrors, [],
      `exact package quote receipt emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a missing captured leg quote stays unavailable despite a populated ambient chain', async () => {
  const missing = candidate();
  missing.legs = missing.legs.map(leg => Object.assign({}, leg, {
    quoteBid: null, quoteAsk: null, quoteIv: null, quoteDelta: null,
    quoteSource: null, quoteFreshness: null, quoteAsOfEpochMs: null
  }));
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    strategyCandidates: [missing]
  });
  try {
    const rendered = await page.evaluate(() => ({
      exactLegs: document.querySelector('.declegpanel')?.textContent.replace(/\s+/g, ' ').trim(),
      nearby: document.querySelector('.packagebooknear')?.textContent.replace(/\s+/g, ' ').trim()
    }));
    assert.doesNotMatch(rendered.exactLegs, /bid 5\.80\s*\/\s*ask 6\.20/i);
    assert.doesNotMatch(rendered.exactLegs, /IV 42\.0%/i);
    assert.match(rendered.nearby, /5\.80\s*\/\s*6\.20/i,
      'ambient data is still present, but only in the nearby-chain owner');
    assert.deepEqual(pageErrors, [],
      `missing captured leg evidence emitted page errors: ${pageErrors.join('\n')}`);
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
  page.setDefaultTimeout(8000);
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
      badgeId: document.querySelector('.fanr.pick')?.dataset.cand
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

test('an adverse-only competition opens its first ranked comparison without pretending it is endorsed', async () => {
  const adverse = unfavorableCandidate();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { strategyCandidates: [adverse] });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
    await page.waitForFunction(candidateId => {
      const state = window.DeskBackend.state();
      return window.decide?.backendPhase === 'ready'
        && state.selected?.id === candidateId
        && state.outcome?.outcome?.candidateId === candidateId
        && window.decide.orderPreview?.selected?.id === candidateId;
    }, adverse.id, { timeout: 10000 });
    await page.waitForSelector('#mcFan .fan-interaction');
    await page.waitForSelector('#decMap [data-mapi]');

    const comparison = await page.evaluate(() => ({
      candidateIds: Array.from(document.querySelectorAll('.fanr[data-cand]')).map(row => row.dataset.cand),
      activeId: window.decide.candId,
      selectedId: window.DeskBackend.state().selected?.id || null,
      deskPickId: window.decide.deskPickId,
      badges: document.querySelectorAll('.fanr.pick').length,
      sectionLabel: document.querySelector('.modebar .eyebrow')?.textContent.trim(),
      qualityLabel: document.querySelector('.fanr .fvd')?.getAttribute('title'),
      qualityReason: document.querySelector('.fanr')?.getAttribute('title'),
      metrics: Array.from(document.querySelectorAll('.fanr .fnum,.fanr .fev'))
        .map(node => node.textContent.trim()),
      rankRead: document.querySelector('.modebar .hint')?.textContent.trim(),
      exactPayoffs: document.querySelectorAll('#decideStage #decPay').length,
      exactLegs: document.querySelectorAll('#decideStage .declegpanel').length,
      scenarioTiles: document.querySelectorAll('#decideStage .srow').length,
      marketPanels: document.querySelectorAll('#decideStage #decMarketPanel').length,
      executionReceipts: document.querySelectorAll('#decideStage #decMarketPanel .pricereceipt').length,
      orderDocks: document.querySelectorAll('#decideStage .execute').length,
      selectedRows: document.querySelectorAll('.fanr.sel').length,
      riskMapMarks: document.querySelectorAll('#decMap [data-mapi]').length,
      candidateActionLabel: document.querySelector('.fanr[data-cand]')?.getAttribute('aria-label'),
      riskMapAction: (() => {
        const dot = document.querySelector('#decMap [data-mapi]');
        return dot && {
          role: dot.getAttribute('role'),
          tabIndex: dot.getAttribute('tabindex'),
          label: dot.getAttribute('aria-label')
        };
      })(),
      selectedRiskMapHalos: document.querySelectorAll('#decMap circle[stroke="#fff"]').length,
      riskMapText: document.querySelector('#decMap')?.textContent.replace(/\s+/g, ' ').trim(),
      decisionHeadline: document.querySelector('.decisionbrief .dbtop b')?.textContent.trim(),
      economicVerdict: window.DeskBackend.state().candidates[0].evaluation.assessment.economics.verdict,
      ensembleCandidate: window.DeskBackend.state().outcome?.outcome?.candidateId || null,
      previewCandidate: window.decide?.orderPreview?.selected?.id || null,
      fanVisible: (() => {
        const fan = document.querySelector('#mcFan');
        const box = fan?.getBoundingClientRect();
        return !!box && box.width > 0 && box.height > 0
          && !!fan.querySelector('.fan-interaction');
      })()
    }));
    assert.deepEqual(comparison.candidateIds, [adverse.id],
      'the adverse package remains available for comparison');
    assert.equal(comparison.activeId, adverse.id);
    assert.equal(comparison.selectedId, adverse.id,
      'the first backend-ranked comparison becomes the immediate analysis subject');
    assert.equal(comparison.economicVerdict, 'UNFAVORABLE');
    assert.equal(comparison.deskPickId, null,
      'a coherent declaration fit is not promoted over an unfavorable economic verdict');
    assert.equal(comparison.badges, 0);
    assert.equal(comparison.sectionLabel, 'Ideas to compare');
    assert.match(comparison.qualityLabel, /^Unfavorable/i,
      'a coherent intent fit with adverse economics is never presented as merely coherent');
    assert.match(comparison.qualityReason,
      /Realized-volatility EV.*comparison, not a recommendation/i,
      'the ranked row itself explains why this is not a recommendation');
    assert.deepEqual(comparison.metrics,
      ['−$123.45', '$123.45', '63%', '$123.45', '−$370']);
    assert.equal(comparison.metrics[2], '63%');
    assert.equal(comparison.metrics[3], '$123.45');
    assert.equal(comparison.metrics[4], '−$370',
      'comparison-required keeps the backend chance, capital, and EV fields visible');
    assert.equal(comparison.rankRead, 'no endorsable pick · 1 comparison');
    assert.equal(comparison.exactPayoffs, 1);
    assert.equal(comparison.exactLegs, 1);
    assert.ok(comparison.scenarioTiles > 0);
    assert.equal(comparison.marketPanels, 1,
      'the selected comparison uses the one canonical market owner');
    assert.equal(comparison.executionReceipts, 1);
    assert.equal(comparison.orderDocks, 1);
    assert.match(comparison.candidateActionLabel,
      /Analyze .*Net debit .*maximum loss .*chance of profit .*capital .*after-cost EV/i,
      'the compact candidate row retains every financial fact in its accessible action name');
    assert.deepEqual(
      { role: comparison.riskMapAction.role, tabIndex: comparison.riskMapAction.tabIndex },
      { role: 'button', tabIndex: '0' },
      'each linked risk-map mark is a keyboard action, not a pointer-only decoration');
    assert.match(comparison.riskMapAction.label, /Analyze .*chance of profit .*after-cost EV/i);
    assert.equal(comparison.ensembleCandidate, adverse.id);
    assert.equal(comparison.previewCandidate, adverse.id);
    assert.equal(comparison.selectedRows, 1);
    assert.equal(comparison.riskMapMarks, 1,
      'its authoritative POP and realistic after-cost EV remain available for comparison');
    assert.match(comparison.riskMapText, /realized-vol EV · after costs/i);
    assert.equal(comparison.selectedRiskMapHalos, 1);
    assert.equal(comparison.fanVisible, true);
    assert.match(comparison.decisionHeadline, /Unfavorable/i,
      'automatic analysis never softens the adverse economic verdict');
    const selected = backend.requests.find(row => row.method === 'PUT'
      && row.path === `/api/plans/${PLAN_ID}/strategy/select`);
    assert.equal(selected.body.candidateId, adverse.id,
      'automatic analysis records the exact first-ranked comparison through the canonical selection API');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 1);
    assert.deepEqual(pageErrors, [], `adverse analysis flow emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('favorable economics remain visible when objective fit prevents endorsement', async () => {
  const mixed = favorableMixedFitCandidate();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const backend = await installBackend(page, { strategyCandidates: [mixed] });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
    await page.waitForFunction(candidateId => window.decide?.backendPhase === 'ready'
      && window.DeskBackend.state().selected?.id === candidateId
      && window.DeskBackend.state().mutationPending === false,
    mixed.id, { timeout: 10000 });
    const view = await page.evaluate(() => ({
      deskPickId: window.decide.deskPickId,
      selectedId: window.DeskBackend.state().selected?.id || null,
      rankRead: document.querySelector('.modebar .hint')?.textContent.trim(),
      quality: document.querySelector('.fanr .fvd')?.getAttribute('title'),
      decisionRead: document.querySelector('.decisionbrief')?.textContent
        .replace(/\s+/g, ' ').trim(),
      fanVisible: !!document.querySelector('#mcFan .fan-interaction')
    }));
    assert.equal(view.deskPickId, null,
      'a mixed objective fit is not silently promoted to Desk Pick');
    assert.equal(view.selectedId, mixed.id,
      'the first mixed-fit comparison is nevertheless opened as the immediate analysis subject');
    assert.equal(view.rankRead, '1 favorable economic · no exact-fit pick · 1 comparison');
    assert.match(view.quality, /^Favorable economics · mixed fit/i);
    assert.match(view.decisionRead, /mixed/i);
    assert.equal(view.fanVisible, true);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1,
      'opening a mixed-fit comparison produces analysis without creating an endorsement');
  } finally {
    await context.close();
  }
});

test('a failed automatic comparison selection restores an actionable neutral comparison field', async () => {
  const adverse = unfavorableCandidate();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    strategyCandidates: [adverse], selectFailures: 1, selectDelayMs: 180
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
    await page.waitForFunction(() => window.decide?.backendPhase === 'comparison-required'
      && window.decide?.backendError
      && window.DeskBackend.state().mutationPending === false, null, { timeout: 10000 });

    const restored = await page.evaluate(candidateId => {
      const row = window.decide.cands.find(candidate => candidate.id === candidateId);
      return {
        phase: window.decide.backendPhase,
        selected: window.DeskBackend.state().selected,
        outcome: window.DeskBackend.state().outcome,
        ensemble: window.DeskBackend.state().ensemble,
        pinned: Object.prototype.hasOwnProperty.call(window.pinnedScen, candidateId),
        queued: !!(row && row._authoritativeScenarioQueued),
        selectedRows: document.querySelectorAll('.fanr.sel').length,
        exactPayoffs: document.querySelectorAll('#decideStage #decPay').length,
        choiceTitle: document.querySelector('.comparisonchoice.hero h2')?.textContent.trim(),
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
    assert.equal(restored.exactPayoffs, 0);
    assert.equal(restored.choiceTitle, 'Choose one idea to inspect.');
    assert.match(restored.message, /requested comparison could not be selected/i);
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 1,
      'the automatic attempt is made exactly once and then leaves an actionable comparison field');
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
        const fan = rect('#decideStage .fan');
        const support = rect('#decideStage .leftsupport');
        const rail = document.querySelector('#decideStage .declegpanel .declegs');
        const rows = Array.from(rail?.querySelectorAll('.legr') || []);
        const selectedName = document.querySelector('#decideStage .fanr.sel .fnm');
        const selectedHeader = selectedName?.closest('.fanr');
        const idealLabel = document.querySelector('#decideStage #decMap .maplab');
        const zeroLine = document.querySelector('#decideStage #decMap line[stroke="var(--line)"][stroke-dasharray="3 4"]');
        let referenceAvoidsIdealLabel = true;
        if (idealLabel && zeroLine) {
          const labelBox = idealLabel.getBBox();
          const y = Number(zeroLine.getAttribute('y1'));
          const x2 = Number(zeroLine.getAttribute('x2'));
          referenceAvoidsIdealLabel = y < labelBox.y - 1 || y > labelBox.y + labelBox.height + 1
            || x2 <= labelBox.x - 2;
        }
        return {
          left: left?.width || 0,
          center: center?.width || 0,
          right: right?.width || 0,
          mapHeight: map?.height || 0,
          candidateToSupportGap: fan && support ? support.top - fan.bottom : null,
          supportBottomSlack: left && support ? left.bottom - support.bottom : null,
          referenceAvoidsIdealLabel,
          legCount: rows.length,
          legRailScrolls: !!rail && rail.scrollHeight > rail.clientHeight + 2,
          legRailSize: rail && { clientHeight: rail.clientHeight, scrollHeight: rail.scrollHeight,
            panelHeight: rail.closest('.declegpanel')?.clientHeight },
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
            return meta ? { client: meta.clientWidth, scroll: meta.scrollWidth,
              rowHeight: row.getBoundingClientRect().height,
              controlsWidth: row.querySelector('.legr-top')?.getBoundingClientRect().width,
              flex: getComputedStyle(row).flex, height: getComputedStyle(row).height,
              minHeight: getComputedStyle(row).minHeight,
              gridRows: getComputedStyle(row).gridTemplateRows } : null;
          }),
          selectedNameFits: !!selectedName && selectedName.scrollWidth <= selectedName.clientWidth + 1
            && selectedName.getBoundingClientRect().bottom <= selectedHeader.getBoundingClientRect().bottom + 1,
          selectedNameWraps: selectedName ? getComputedStyle(selectedName).whiteSpace === 'normal' : false,
          documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth
        };
      });
      assert.equal(geometry.legCount, 4);
      assert.equal(geometry.legRailScrolls, false,
        `${viewport.width}px shows a canonical four-leg package without hiding rows in a rail: ${JSON.stringify(geometry)}`);
      assert.equal(geometry.legsContained, true,
        `${viewport.width}px keeps every full-width leg inside the workbench`);
      assert.equal(geometry.metadataFits, true,
        `${viewport.width}px keeps exact contract metadata readable inside its row: ${JSON.stringify(geometry)}`);
      if (viewport.width <= 1500) {
        assert.ok(geometry.left >= viewport.width * .85
          && geometry.center >= viewport.width * .85
          && geometry.right >= viewport.width * .85,
        `${viewport.width}px uses one complete document column instead of three crushed columns: ${JSON.stringify(geometry)}`);
      } else {
        assert.ok(geometry.left >= viewport.width * .32,
          `${viewport.width}px reserves useful width for ideas and legs: ${JSON.stringify(geometry)}`);
        assert.ok(geometry.right >= viewport.width * .26,
          `${viewport.width}px reserves useful width for evidence and paths: ${JSON.stringify(geometry)}`);
        assert.ok(geometry.center <= viewport.width * .42,
          `${viewport.width}px payoff remains the center without consuming the canvas: ${JSON.stringify(geometry)}`);
      }
      assert.ok(geometry.mapHeight >= 170,
        `${viewport.width}px keeps the risk/reward map legible: ${JSON.stringify(geometry)}`);
      assert.ok(geometry.candidateToSupportGap >= -1 && geometry.candidateToSupportGap <= 12,
        `${viewport.width}px puts the leg workbench directly after the ranked ideas: ${JSON.stringify(geometry)}`);
      assert.ok(Math.abs(geometry.supportBottomSlack) <= 2,
        `${viewport.width}px lets the workbench/map support strip use the remaining rail height`);
      assert.equal(geometry.referenceAvoidsIdealLabel, true,
        `${viewport.width}px clips the zero-EV reference before the ideal-region caption`);
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
        const support = document.querySelector('#decideStage .leftsupport');
        const supportRect = support?.getBoundingClientRect();
        const centerRect = document.querySelector('#decideStage .dccenter')?.getBoundingClientRect();
        const panelRect = panel?.getBoundingClientRect();
        const mapSvg = map?.querySelector('svg');
        const mapLabels = Array.from(mapSvg?.querySelectorAll('.maplab') || []).map(label => label.getBBox());
        const selectedName = document.querySelector('#decideStage .fanr.sel .fnm');
        const expiry = panel?.querySelector('.decleghead')?.textContent.replace(/\s+/g, ' ').trim();
        return {
          legCount: rows.length,
          labels: rows.map(row => row.textContent.replace(/\s+/g, ' ').trim()),
          expiry,
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
          rowDebug: rows.map((row, index) => {
            const rect = rowRects[index];
            const meta = row.querySelector('.lgm');
            return { height: rect.height, left: rect.left, right: rect.right,
              metaWhiteSpace: meta && getComputedStyle(meta).whiteSpace,
              metaClient: meta && meta.clientWidth, metaScroll: meta && meta.scrollWidth };
          }),
          railClips: !!rail && rail.scrollHeight > rail.clientHeight + 2,
          panelContained: !!panel && panel.scrollWidth <= panel.clientWidth + 1,
          mapContained: !!mapRect && mapRect.left >= -1 && mapRect.right <= innerWidth + 1,
          mapBounds: mapRect && { left: mapRect.left, right: mapRect.right, viewport: innerWidth },
          mapHeight: mapRect?.height || 0,
          supportOwnsContent: !!supportRect && !!panelRect && !!mapRect
            && supportRect.height >= panelRect.height + mapRect.height - 1,
          supportPrecedesCenter: !!mapRect && !!centerRect && mapRect.bottom <= centerRect.top + 1,
          mapLabelsContained: !!mapSvg && mapLabels.every(box => box.x >= -1
            && box.x + box.width <= mapSvg.viewBox.baseVal.width + 1),
          mapLabelsSeparate: mapLabels.every((box, index) => mapLabels.slice(index + 1).every(other =>
            box.x + box.width <= other.x + 1 || other.x + other.width <= box.x + 1
            || box.y + box.height <= other.y + 1 || other.y + other.height <= box.y + 1)),
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
      assert.match(mobile.expiry, /exp 2026-08-21/i,
        `${viewport.width}px states the shared package expiration once in the workbench heading`);
      assert.ok(mobile.labels.every(label => /bid [\d.]+\s*\/\s*ask [\d.]+/i.test(label)),
        `${viewport.width}px uses the mobile second line for every captured executable book: ${JSON.stringify(mobile.labels)}`);
      assert.ok(mobile.labels.some(label => /bid [\d.]+\s*\/\s*ask [\d.]+/i.test(label))
        && mobile.labels.some(label => /IV 24\.0%/i.test(label)),
        `${viewport.width}px enriches listed legs with available book and volatility evidence`);
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
      assert.equal(mobile.supportOwnsContent, true,
        `${viewport.width}px gives the workbench and map real layout height instead of overflow-painting them`);
      assert.equal(mobile.supportPrecedesCenter, true,
        `${viewport.width}px completes the ideas/legs/map rail before the payoff column begins`);
      assert.equal(mobile.mapLabelsContained, true,
        `${viewport.width}px keeps every risk-map region caption inside the SVG`);
      assert.equal(mobile.mapLabelsSeparate, true,
        `${viewport.width}px keeps risk-map region captions from colliding`);
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
        try {
          await page.waitForFunction(() => window.decide?.animation
            && document.querySelector('#decideStage .srow-ctl'), null, { timeout: 10000 });
        } catch (error) {
          const diagnosis = await page.evaluate(() => ({
            mutationPending: window.DeskBackend.state().mutationPending,
            bridgeAnimation: window.DeskBackend.state().animation,
            bridgeError: window.DeskBackend.state().error,
            activeId: window.decide?.candId,
            pinned: Object.assign({}, window.pinnedScen),
            queued: window.decide?.cands.map(row => ({
              id: row.id, queued: row._authoritativeScenarioQueued,
              error: row._authoritativeScenarioError
            })),
            pathText: document.querySelector('.evsimstage')?.textContent
              .replace(/\s+/g, ' ').trim()
          }));
          error.message += `\nIntermediate scenario diagnosis: ${JSON.stringify(diagnosis)}`;
          throw error;
        }
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
            time: !!control?.querySelector('[data-asm="days"]'),
            stepperLabels: Array.from(control?.querySelectorAll('.mstp button') || [])
              .map(button => button.getAttribute('aria-label')),
            speedStates: Array.from(control?.querySelectorAll('.spd button') || [])
              .map(button => ({
                label: button.getAttribute('aria-label'),
                pressed: button.getAttribute('aria-pressed')
              }))
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
      assert.equal(geometry.scenarioControls.scrub, true);
      assert.equal(geometry.scenarioControls.move, true);
      assert.equal(geometry.scenarioControls.vol, true);
      assert.equal(geometry.scenarioControls.time, true);
      assert.ok(geometry.scenarioControls.stepperLabels.every(Boolean),
        `${viewport.width}px labels every scenario decrement/increment action`);
      assert.deepEqual(geometry.scenarioControls.speedStates.map(row => row.label),
        ['1 times playback speed', '2 times playback speed', '4 times playback speed']);
      assert.equal(geometry.scenarioControls.speedStates.filter(row => row.pressed === 'true').length, 1,
        `${viewport.width}px exposes one selected playback speed`);
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
      badgeId: document.querySelector('.fanr.pick')?.dataset.cand
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
    await page.evaluate(() => window.API.invalidate(['/api/research/AMD']));
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
    await page.evaluate(() => window.API.invalidate(['/api/research/AMD/chain']));
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

test('candidate changes reuse one price ensemble while replacing its exact package valuation canvas', async () => {
  const alternate = fourLegCandidate();
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    strategyCandidates: [candidate(), alternate],
    latestEnsembleEnabled: true,
    latestRevaluesSelectedCandidate: true
  });
  try {
    const ensemblePath = `/api/plans/${PLAN_ID}/outcomes/ensemble`;
    const outcomePath = `/api/plans/${PLAN_ID}/outcomes/run`;
    const before = await page.evaluate(() => {
      const envelope = window.DeskBackend.state().ensemble;
      const canvas = envelope.preview.canvas;
      return {
        selectedId: window.DeskBackend.state().selected.id,
        ensembleId: envelope.ensemble.id,
        ensembleFingerprint: envelope.ensemble.fingerprint,
        canvasPositionKey: canvas.positions[0].key,
        valuationFingerprint: canvas.modelReceipt.valuationFingerprint,
        visibleValuationFingerprint: document.querySelector('#mcFan')
          ?.getAttribute('data-valuation-fingerprint')
      };
    });
    assert.equal(before.canvasPositionKey, `PROPOSED:${CANDIDATE_ID}`);
    assert.equal(before.valuationFingerprint, `valuation-${CANDIDATE_ID}`);
    assert.equal(before.visibleValuationFingerprint, before.valuationFingerprint);

    await page.evaluate(candidateId => window.DeskBackend.chooseCandidate(candidateId), alternate.id);
    await page.waitForFunction(candidateId => {
      const state = window.DeskBackend.state();
      const canvas = state.ensemble?.preview?.canvas;
      return state.selected?.id === candidateId
        && state.outcome?.outcome?.candidateId === candidateId
        && canvas?.positions?.some(row => row.key === `PROPOSED:${candidateId}`)
        && document.querySelector('#mcFan')?.getAttribute('data-valuation-fingerprint')
          === canvas.modelReceipt?.valuationFingerprint
        && state.mutationPending === false;
    }, alternate.id, { timeout: 10000 });

    const after = await page.evaluate(() => {
      const envelope = window.DeskBackend.state().ensemble;
      const canvas = envelope.preview.canvas;
      return {
        selectedId: window.DeskBackend.state().selected.id,
        ensembleId: envelope.ensemble.id,
        ensembleFingerprint: envelope.ensemble.fingerprint,
        canvasPositionKey: canvas.positions[0].key,
        valuationFingerprint: canvas.modelReceipt.valuationFingerprint,
        visibleValuationFingerprint: document.querySelector('#mcFan')
          ?.getAttribute('data-valuation-fingerprint')
      };
    });
    assert.equal(after.selectedId, alternate.id);
    assert.equal(after.ensembleId, before.ensembleId,
      'candidate selection cannot regenerate the shared underlying price paths');
    assert.equal(after.ensembleFingerprint, before.ensembleFingerprint,
      'the immutable price-ensemble fingerprint remains stable across packages');
    assert.equal(after.canvasPositionKey, `PROPOSED:${alternate.id}`,
      'the stored fan is repainted with the newly selected exact package');
    assert.notEqual(after.valuationFingerprint, before.valuationFingerprint,
      'candidate-specific package valuation receives a distinct receipt');
    assert.equal(after.visibleValuationFingerprint, after.valuationFingerprint,
      'the rendered P/L fan exposes the exact package valuation receipt it displays');
    assert.equal(backend.count('POST', ensemblePath), 1,
      'the candidate switch performs no second price-path generation');
    assert.equal(backend.count('POST', outcomePath), 2,
      'each selected package receives its own outcome over the one price ensemble');
    assert.deepEqual(pageErrors, [],
      `same-ensemble candidate convergence emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a scenario chosen while the exact preview is pending is conditioned after the Plan mutation releases', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { decisionPreviewDelayMs: 900 });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
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
    assert.deepEqual(completed.visibleFocusPrices,
      [100, 101.8, 101.1, 103.4, 102.7, 105, 104.2, 106.1, 105.4, 107, 106.2]);
    assert.equal(completed.targetStep, 10,
      'the server-owned animation lifecycle keeps the complete returned checkpoint field');
    assert.ok(completed.directionChanges >= 8,
      'the conditioned focus keeps server-selected session noise instead of becoming a straight endpoint ray');
    assert.equal(backend.scenarioCalls(), 1,
      'one early click becomes exactly one conditioned-path request after readiness');
    const request = backend.requests.find(row => row.method === 'POST'
      && row.path === `/api/plans/${PLAN_ID}/outcomes/ensemble/paths`);
    assert.equal(request.body.ensembleId, ENSEMBLE_ID);
    assert.deepEqual(request.body.interaction, {
      story: 'GRIND_HIGHER',
      movePct: null,
      ivShiftPoints: null,
      elapsedSessions: null,
      sourcePathIndex: null
    }, 'the queued browser intent remains a story declaration, not a second numeric policy');
    for (const field of ['canvas', 'waypoints', 'pathWaypoints', 'paths']) {
      assert.equal(Object.hasOwn(request.body, field), false,
        `the queued story does not send browser-authored ${field}`);
    }
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
        price: row.price,
        collect: window.candCollect(row),
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
        price: row.price,
        collect: window.candCollect(row),
        maxLoss: row.maxLoss,
        maxProfit: row.maxProfit,
        pop: row.pop,
        instruction: window.decide.orderPreview.order.orderInstruction.type,
        deskPickId: window.decide.deskPickId,
        pickBadges: document.querySelectorAll('.fanr.pick').length
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
      const first = window.DeskBackend.scenarioAnimation({ interaction: {
        story: 'ORDERLY_PULLBACK', movePct: -5, ivShiftPoints: 2,
        elapsedSessions: 10, sourcePathIndex: null
      } });
      const second = window.DeskBackend.scenarioAnimation({ interaction: {
        story: 'GRIND_HIGHER', movePct: 5, ivShiftPoints: -2,
        elapsedSessions: 10, sourcePathIndex: null
      } });
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
    assert.equal(synchronizedMidpoint.price, 105,
      'the animated underlying follows the server-valued intraday focus checkpoint');
    assert.equal(synchronizedMidpoint.pnl, 431,
      'payoff and risk readouts follow the same server-valued intraday checkpoint');

    const conditioned = backend.requests.filter(row => row.method === 'POST' && row.path === pathsPath);
    assert.equal(conditioned.length, 2);
    conditioned.forEach(row => {
      assert.equal(row.body.ensembleId, ENSEMBLE_ID,
        'every conditioned projection selects from the active immutable ensemble');
      assert.ok(row.body.interaction?.story,
        'the browser sends a named user interaction, not generated scenario inputs');
      for (const field of ['canvas', 'waypoints', 'pathWaypoints', 'paths']) {
        assert.equal(Object.hasOwn(row.body, field), false,
          `the browser never supplies financial ${field}`);
      }
    });

    const invalid = await page.evaluate(async () => {
      try {
        await window.DeskBackend.scenarioAnimation({ interaction: {
          story: 'MELT_UP', movePct: 9, ivShiftPoints: -4,
          elapsedSessions: 10, sourcePathIndex: null
        } });
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
      await window.DeskBackend.scenarioAnimation({ interaction: {
        story: 'GRIND_HIGHER', movePct: 5, ivShiftPoints: -2,
        elapsedSessions: 10, sourcePathIndex: null
      } });
      const visible = window.activeCand();
      const scenario = window.scenData(visible);
      window.__draftBaselineMc = window.decide._mc;
      window.__draftBaselineScenario = document.querySelector('#decideStage .scenpanel');
      return {
        activeId: visible.id,
        activePayoffAtSpot: window.__testNearestPayoffPoint(visible, 100)?.profit ?? null,
        frame: window.authoritativeFrame(visible, 0.5),
        scenarioPnl: scenario.rows.map(row => row.pl),
        animationMarker: window.DeskBackend.state().animation.testMarker
      };
    });

    const inlineWorkbench = await page.evaluate(() => ({
      inlineControls: document.querySelectorAll('#decideStage .declegpanel .inlineleg [data-leg]').length,
      separateEditor: Boolean(document.querySelector('#decideStage .declegpanel [data-dec="editlegs"]')),
      text: document.querySelector('#decideStage .declegpanel')?.textContent.replace(/\s+/g, ' ').trim()
    }));
    assert.ok(inlineWorkbench.inlineControls >= 8,
      'the selected backend package is directly editable in its resting workbench');
    assert.equal(inlineWorkbench.separateEditor, false,
      'inline leg controls replace the redundant dedicated-editor transition');
    assert.match(inlineWorkbench.text, /bid 5\.80\s*\/\s*ask 6\.00/i,
      'the resting leg rows carry contract economics before the user edits them');
    await page.locator('#decideStage .declegpanel [data-leg="rm"][data-li="1"]')
      .evaluate(node => node.click());
    await page.waitForFunction(() => window.decide.draftPending
      && window.decide.buildLegs?.length === 1);

    const pending = await page.evaluate(() => {
      const visible = window.activeCand();
      return {
        activeId: visible.id,
        activePayoffAtSpot: window.__testNearestPayoffPoint(visible, 100)?.profit ?? null,
        frame: window.authoritativeFrame(visible, 0.5),
        scenarioPnl: window.scenData(visible).rows.map(row => row.pl),
        sameMc: window.decide._mc === window.__draftBaselineMc,
        sameScenarioNode: document.querySelector('#decideStage .scenpanel')
          === window.__draftBaselineScenario,
        animationMarker: window.DeskBackend.state().animation.testMarker,
        workbenchLegs: document.querySelectorAll('#decideStage .declegpanel .legr').length,
        payoffTitle: document.querySelector('#decideStage .dccenter .paytitle')?.textContent
          .replace(/\s+/g, ' ').trim(),
        scenarioTitle: document.querySelector('#decideStage .scenpanel .lenshd')?.textContent
          .replace(/\s+/g, ' ').trim(),
        evidenceTitle: document.querySelector('#decideStage .evsimstage')?.closest('.upanel')
          ?.querySelector('.lenshd')?.textContent.replace(/\s+/g, ' ').trim(),
        dockTitle: document.querySelector('#decideStage .execute .dockorder')?.textContent
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
    assert.match(pending.payoffTitle,
      /Previous selected idea payoff.*draft repricing.*previous selected idea remains unchanged until the exact draft is accepted/i);
    assert.match(pending.scenarioTitle, /previous selected idea/i);
    assert.match(pending.evidenceTitle, /previous selected idea/i);
    assert.match(pending.dockTitle, /previous selected idea/i);

    await page.waitForFunction(() => !window.decide.draftPending
      && /one-leg draft is blocked/i.test(window.decide.draftError || ''));
    const invalid = await page.evaluate(() => {
      const state = window.DeskBackend.state();
      const visible = window.activeCand();
      return {
        selectedId: state.selected.id,
        visibleSelectedId: window.decide.candId,
        activeId: visible.id,
        activePayoffAtSpot: window.__testNearestPayoffPoint(visible, 100)?.profit ?? null,
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
    assert.match(invalid.payoffTitle,
      /Previous selected idea payoff.*Backend debit call spread.*draft blocked.*previous selected idea remains unchanged until the exact draft is accepted/i);
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
    assert.equal(Object.hasOwn(invalidRequest.body, 'proposedNetCents'), false,
      'the browser does not send the removed aggregate proposal alias');
    assert.deepEqual(invalidRequest.body.orderInstruction, { type: 'MARKET', timeInForce: 'DAY' },
      'the browser constrains a proposed package only through its typed order instruction');
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
        selectedIdentity: result.selected.identity,
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
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/run`), 1,
      'clearing client idempotency storage cannot invalidate the server-current competition or exact selection');
    assert.deepEqual(pageErrors, [], `exact-package bridge emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Add leg waits for an explicit side, contract type, and listed strike', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    const previewPath = '/api/trades/preview';
    const before = backend.count('POST', previewPath);
    await page.click('[data-addleg]');
    await page.waitForSelector('.addlegpicker');
    await page.waitForTimeout(220);

    const staged = await page.evaluate(() => ({
      legCount: window.decide.buildLegs.length,
      side: window.decide.addLegDraft?.side,
      type: window.decide.addLegDraft?.type,
      strike: window.decide.addLegDraft?.strike,
      confirmDisabled: document.querySelector('[data-addleg-action="confirm"]')?.disabled
    }));
    assert.deepEqual(staged, {
      legCount: 2, side: null, type: null, strike: null, confirmDisabled: true
    }, 'opening Add leg does not invent a contract or start a preview');
    assert.equal(backend.count('POST', previewPath), before);

    await page.click('[data-addleg-choice="side"][data-value="SELL"]');
    await page.click('[data-addleg-choice="type"][data-value="p"]');
    await page.selectOption('[data-addleg-strike]', '95');
    await page.click('[data-addleg-action="confirm"]');
    await page.waitForTimeout(500);

    const request = backend.requests.filter(row =>
      row.method === 'POST' && row.path === previewPath).at(-1);
    assert.ok(request, 'the explicit contract starts the canonical backend preview');
    assert.deepEqual(request.body.legs.at(-1), {
      action: 'SELL',
      type: 'PUT',
      strike: 95,
      expiration: '2026-08-21',
      ratio: 1,
      multiplier: 100,
      positionEffect: 'OPEN',
      entryPrice: null
    });
    assert.deepEqual(pageErrors, [], `explicit Add leg emitted page errors: ${pageErrors.join('\n')}`);
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
      duplicateRiskChip: document.querySelector('.fanr.sel .rchip')?.textContent.trim(),
      riskTitle: document.querySelector('.fanr.sel .fanicon')?.getAttribute('title'),
      reviewDisabled: document.querySelector('[data-dec="review"]')?.disabled,
      dock: document.querySelector('.execute')?.textContent,
      deskPickId: window.decide.deskPickId,
      pickBadges: document.querySelectorAll('.fanr.pick').length
    }));
    assert.equal(rendered.duplicateRiskChip, undefined,
      'risk classification appears once on the canonical payoff glyph');
    assert.match(rendered.riskTitle, /classification unavailable/i);
    assert.equal(rendered.reviewDisabled, true);
    assert.match(rendered.dock, /Risk checks block this exact package/i,
      'execution pricing and placement risk checks remain separately visible');
    assert.match(rendered.dock, /exact package exceeds the loss limit/i);
    assert.equal(rendered.deskPickId, null,
      'a final exact-package guardrail block removes the Desk Pick endorsement');
    assert.equal(rendered.pickBadges, 0);

    const guard = await page.evaluate(() => {
      window.decAction('review');
      return { review: window.decide.order.review, error: window.decide.backendError };
    });
    assert.equal(guard.review, false);
    assert.match(guard.error, /exact package exceeds the loss limit/i);
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
        activeIds: book.data.practiceBook.snapshot.activeTrades.map(trade => trade.id),
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
    assert.equal(backend.count('GET', '/api/portfolio/book'), 2,
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
      window.DeskBackend.updateStrategyControls({
        values: { risk: 6666 }, explicit: { risk: true }
      });
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
    await startNewIdea(page);
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
    await startNewIdea(page);
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
    await page.locator('.scenpanel .srow[data-si="0"]').click();
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
      window.DeskBackend.updateStrategyControls({
        values: { risk: 7777 }, explicit: { risk: true }
      });
    }, adjacent.id);
    try {
      await page.waitForFunction(() => {
        const state = window.DeskBackend.state();
        return window.decide.backendPhase === 'ready'
          && !state.mutationPending
          && Number(state.strategyControls?.values?.risk) === 7777
          && state.strategyControls?.refreshPending === false
          && state.strategyControls?.appliedRevision === state.strategyControls?.revision;
      }, null, { timeout: 10000 });
    } catch (error) {
      const diagnostic = await page.evaluate(() => {
        const state = window.DeskBackend.state();
        return {
          phase: window.decide && window.decide.backendPhase,
          pending: state.mutationPending,
          context: state.context,
          bridgeError: state.error,
          presentationError: state.presentationError
        };
      });
      throw new Error(`${error.message}\nGovernor refresh diagnostic: ${JSON.stringify(diagnostic)}`);
    }

    const state = await page.evaluate(() => ({
      visible: window.decide.candId,
      selected: window.DeskBackend.state().selected.id,
      outcome: window.DeskBackend.state().outcome.outcome.candidateId,
      risk: window.DeskBackend.strategyControls().values.risk
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

test('strategy controls serialize every enforced governor under its exact backend fact name', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk();
  try {
    await page.evaluate(() => {
      window.DeskBackend.updateStrategyControls({
        values: {
          risk: 7777,
          minPop: 55,
          maxAsn: 35,
          bp: 25000,
          gapLoss: 50000
        },
        explicit: {
          risk: true,
          minPop: true,
          maxAsn: true,
          bp: true,
          gapLoss: true
        }
      });
    });
    await page.waitForFunction(() => {
      const controls = window.DeskBackend.strategyControls();
      return controls.refreshPending === false
        && controls.appliedRevision === controls.revision;
    }, null, { timeout: 10000 });

    const controls = await page.evaluate(() => window.DeskBackend.strategyControls());
    assert.deepEqual(controls.values, {
      risk: 7777, minPop: 55, maxAsn: 35, bp: 25000, gapLoss: 50000
    });
    assert.deepEqual(controls.supported, {
      risk: true, minPop: true, maxAsn: true, bp: true, gapLoss: true
    });
    assert.deepEqual(controls.unavailable, {});

    const runs = backend.requests.filter(row => row.method === 'POST'
      && row.path === `/api/plans/${PLAN_ID}/strategy/run`);
    assert.equal(runs.length, 2);
    assert.deepEqual(runs[1].body, {
      maxLossCents: 777700,
      filters: {
        minPop: 0.55,
        maxAssignmentProb: 0.35,
        maxCapitalRequiredCents: 2500000,
        maxMarketCrashLossCents: 5000000
      }
    }, 'the one typed operation preserves each declared fact and unit');
    assert.equal(Object.hasOwn(runs[1].body, 'maxCostCents'), false,
      'buying-power appetite is never misrepresented as a debit-cost cap');
    assert.equal(Object.hasOwn(runs[1].body.filters, 'bp'), false);
    assert.equal(Object.hasOwn(runs[1].body.filters, 'gapLoss'), false);
    assert.deepEqual(pageErrors, [], `strategy-control serialization emitted page errors: ${pageErrors.join('\n')}`);
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
      window.DeskBackend.updateStrategyControls({
        values: { risk: 7777 }, explicit: { risk: true }
      });
    });
    await page.waitForFunction(id => window.DeskBackend.state().strategyControls?.values?.risk === 7777
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
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { selectDelayMs: 180 });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
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
    assert.match(initial.heading, /AMD.*500 price paths.*21 sessions/i);
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
    assert.match(priceReadout, /Ends \$.*click to play .* across the desk/i,
      'hover names the server-supplied endpoint and its whole-desk action without deriving a second percentage');

    await page.locator('[data-dec="pathview"][data-path-view="pnl"]').click();
    await page.waitForSelector('#mcFan[data-path-space="pnl"]');
    await page.locator('#mcFan [data-mc-path="0"]').dispatchEvent('mouseover');
    const pnlReadout = await page.locator('#mcPathReadout').textContent();
    assert.match(pnlReadout, /Ends .*underlying ends \$.*click to play .* across the desk/i,
      'P/L hover pairs the package consequence with the same underlying source path');

    await page.locator('#mcFan').scrollIntoViewIfNeeded();
    await page.waitForTimeout(1000);
    const futurePoint = await page.locator('#mcFan [data-mc-path]').last().evaluate(path => {
      const point = path.getPointAtLength(path.getTotalLength() * 0.78);
      const matrix = path.getScreenCTM();
      const displayIndex = Number(path.getAttribute('data-mc-path'));
      const shown = window.decisionFan(window.activeCand());
      return {
        x: matrix.a * point.x + matrix.c * point.y + matrix.e,
        y: matrix.b * point.x + matrix.d * point.y + matrix.f,
        sourcePathIndex: shown.sourcePathIndices[displayIndex]
      };
    });
    await page.mouse.click(futurePoint.x, futurePoint.y);
    try {
      await page.waitForFunction(candidateId => {
        const candidate = window.decide?.cands.find(row => row.id === candidateId);
        return candidate?.authoritativeAnimation && window.decide?._mc?.focusSourcePathIndex != null
          && Object.prototype.hasOwnProperty.call(window.pinnedScen, candidateId);
      }, CANDIDATE_ID, { timeout: 10000 });
    } catch (error) {
      const diagnosis = await page.evaluate(candidateId => {
        const candidate = window.decide?.cands.find(row => row.id === candidateId);
        return {
          pinned: window.pinnedScen?.[candidateId],
          hover: window.decide?._mcHoverPath,
          source: candidate?._customSourcePathIndex,
          animation: !!candidate?.authoritativeAnimation,
          focus: window.decide?._mc?.focusSourcePathIndex,
          running: window.decide?.mcRunning,
          bridgeError: window.DeskBackend?.state().error || null,
          hit: document.elementFromPoint(window.innerWidth * .82, window.innerHeight * .5)?.outerHTML?.slice(0, 200)
        };
      }, CANDIDATE_ID);
      assert.fail(`exact-path click did not condition the selected future: ${JSON.stringify(diagnosis)}`);
    }
    const driven = await page.evaluate(candidateId => ({
      pinnedScenario: window.pinnedScen[candidateId],
      animationFingerprint: window.decide.animation?.ensemble?.fingerprint,
      receiptInteraction: window.decide.animation?.receipt?.interaction,
      focusSourcePathIndex: window.decide.animation?.paths?.receipt?.focusSourcePathIndex,
      payoffMarker: document.querySelectorAll('#decPay .livept, #decPay .authpayframe').length,
      chartSpace: document.querySelector('#mcFan')?.getAttribute('data-path-space')
    }), CANDIDATE_ID);
    assert.equal(driven.pinnedScenario, -9,
      'clicking a possible future selects the exact stored-path mode, not a nearby named story');
    assert.equal(driven.animationFingerprint, ENSEMBLE_FINGERPRINT);
    assert.equal(driven.receiptInteraction.sourcePathIndex, futurePoint.sourcePathIndex);
    assert.equal(driven.focusSourcePathIndex, futurePoint.sourcePathIndex,
      'the server focuses the immutable source row selected by the user');
    assert.equal(driven.chartSpace, 'pnl',
      'scenario selection preserves the user-selected package lens');
    const request = backend.requests.find(row => row.method === 'POST'
      && row.path === `/api/plans/${PLAN_ID}/outcomes/ensemble/paths`
      && row.body.interaction?.sourcePathIndex != null);
    assert.ok(request, 'path interaction uses the existing backend scenario projection');
    assert.equal(request.body.interaction.sourcePathIndex, futurePoint.sourcePathIndex);
    assert.equal(request.body.interaction.story, null);
    for (const field of ['canvas', 'waypoints', 'pathWaypoints', 'paths']) {
      assert.equal(Object.hasOwn(request.body, field), false,
        `the exact-path action does not send browser-authored ${field}`);
    }
    assert.deepEqual(pageErrors, [], `interactive market fan emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('New Idea measures one elegant overflow list, composes in the left rail, and compares the Book without losing the idea', async () => {
  const rows = Array.from({ length: 18 }, (_, index) => {
    const row = JSON.parse(JSON.stringify(candidate()));
    row.id = index === 0 ? CANDIDATE_ID : `candidate_page_${index}`;
    row.label = `Income structure ${index + 1}`;
    row.displayName = row.label;
    return row;
  });
  const heldKey = 'trade-book-lens';
  /* This comparison fixture changes only the comparison population. The exact proposed and held
     projections retain the full PositionAnimation v2 grid from the canonical ensemble fixture;
     a two-point visual shortcut is not a valid wire receipt. */
  const canonicalCanvas = ensemble().preview.canvas;
  const proposedProjection = JSON.parse(JSON.stringify(canonicalCanvas.positions[0]));
  const heldProjection = JSON.parse(JSON.stringify(proposedProjection));
  heldProjection.key = heldKey;
  heldProjection.proposed = false;
  const canvasPositions = [
    proposedProjection,
    heldProjection
  ];
  const canvasComparison = [
    { key: `PROPOSED:${CANDIDATE_ID}`, label: 'This idea', proposed: true,
      horizonP5Cents: -12345, horizonP50Cents: 1450, horizonP95Cents: 12000,
      chanceOfGainPct: 63 },
    { key: heldKey, label: 'AMD income position', proposed: false,
      horizonP5Cents: -9000, horizonP50Cents: 2200, horizonP95Cents: 10500,
      chanceOfGainPct: 68 }
  ];
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    viewport: { width: 1920, height: 1080 },
    strategyCandidates: rows, canvasComparison, canvasPositions
  });
  try {
    await page.waitForFunction(() => document.querySelector('.fanrows')?.classList.contains('hasoverflow'),
      null, { timeout: 5000 });
    const measuredOverflow = async () => page.evaluate(() => {
      const list = document.querySelector('.dcleft .fanrows');
      const fan = list?.closest('.fan');
      const support = document.querySelector('.dcleft .leftsupport');
      const meta = fan?.querySelector('.overflowmeta');
      const selected = list?.querySelector('.fanr.sel');
      const listBox = list?.getBoundingClientRect();
      const fanBox = fan?.getBoundingClientRect();
      const supportBox = support?.getBoundingClientRect();
      const selectedBox = selected?.getBoundingClientRect();
      return {
        rows: list?.querySelectorAll('.fanr[data-cand]').length || 0,
        measured: !!list && list.scrollHeight > list.clientHeight + 2,
        classed: list?.classList.contains('hasoverflow') || false,
        cue: meta?.classList.contains('on') || false,
        cueText: meta?.querySelector('.overflowread')?.textContent || '',
        snap: getComputedStyle(list).scrollSnapType,
        pager: fan?.querySelectorAll('.fitpager').length || 0,
        selectedVisible: !!selectedBox && !!listBox
          && selectedBox.top >= listBox.top - 1 && selectedBox.bottom <= listBox.bottom + 1,
        listOwned: Array.from(list?.querySelectorAll('.fanr') || [])
          .every(row => row.parentElement === list),
        supportSeparated: !!fanBox && !!supportBox && fanBox.bottom <= supportBox.top + 1,
        documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth
      };
    });
    let listState = await measuredOverflow();
    assert.equal(listState.rows, 18, 'every ranked idea remains in the one list DOM');
    assert.equal(listState.measured, true, JSON.stringify(listState));
    assert.equal(listState.classed, listState.measured);
    assert.equal(listState.cue, true);
    assert.match(listState.cueText, /^\+\d+ more$/);
    assert.match(listState.snap, /^y(?: |$)/);
    assert.equal(listState.pager, 0, 'the obsolete page-state grammar is absent');
    assert.equal(listState.selectedVisible, true);
    assert.equal(listState.listOwned, true);
    assert.equal(listState.supportSeparated, true,
      'the candidate list ends before the workbench/map support strip begins');
    assert.equal(listState.documentOverflow, false);

    await page.evaluate(() => {
      const list = document.querySelector('#decideStage .dcleft .fanrows');
      list.scrollTop = list.scrollHeight;
    });
    await page.waitForFunction(() => {
      const list = document.querySelector('#decideStage .dcleft .fanrows');
      return list?.scrollTop > 0
        && /above/.test(list.parentElement?.querySelector('.overflowread')?.textContent || '');
    },
    null, { timeout: 5000 });
    const scrolled = await page.evaluate(() => {
      const list = document.querySelector('#decideStage .dcleft .fanrows');
      const last = list?.querySelector('.fanr:last-child');
      const a = list?.getBoundingClientRect(), b = last?.getBoundingClientRect();
      return { rows: list?.querySelectorAll('.fanr').length || 0,
        lastVisible: !!a && !!b && b.top >= a.top - 1 && b.bottom <= a.bottom + 1,
        cue: list?.parentElement?.querySelector('.overflowread')?.textContent || '' };
    });
    assert.equal(scrolled.rows, 18);
    assert.equal(scrolled.lastVisible, true, JSON.stringify(scrolled));
    assert.match(scrolled.cue, /above/);

    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.evaluate(() => window.renderDecide());
    await page.waitForFunction(() => document.querySelector('.fanrows')?._elegantBound === true);
    listState = await measuredOverflow();
    assert.equal(listState.rows, 18,
      'the principal desktop keeps the full ranked field in one measured list');
    assert.equal(listState.classed, listState.measured, JSON.stringify(listState));
    assert.equal(listState.cue, listState.measured);
    assert.equal(listState.pager, 0);
    assert.equal(listState.supportSeparated, true);
    assert.equal(listState.documentOverflow, false);

    await page.locator('.decintent').click();
    assert.equal(await page.locator('.ideacomposer').count(), 1,
      'the idea editor occupies the decision rail instead of becoming a transient header strip');
    assert.equal(await page.locator('.intentpop').count(), 0);
    await page.locator('[data-obj="goal"][data-val="Hedge"]').click();
    assert.equal(await page.locator('.ideacomposer').count(), 1,
      'the complete composer stays open while declarations are staged together');
    assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/intent`), 0,
      'a staged goal does not mutate the Plan on click');
    await page.locator('[data-dec="analyzeidea"]').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'ready'
      && window.decide?.goal === 'Hedge' && !window.DeskBackend.state().mutationPending,
    null, { timeout: 10000 });
    assert.equal(await page.locator('.ideacomposer').count(), 0,
      'the explicit apply boundary returns to the ranked field');

    await page.locator('[data-inspect="book"]').click();
    assert.equal(await page.locator('.authcompare .acrow').count(), 3,
      'the Book lens keeps the proposed idea and held position on one comparison receipt');
    assert.match(await page.locator('#dec-inspect-panel').textContent(),
      /replayed through the .*simulated futures.*This idea.*AMD income position/s);
    assert.equal(await page.locator('#decPay').count(), 1,
      'opening Book comparison preserves the idea financial surface');
    assert.equal(await page.locator('.fanr[data-cand]').count(), 18,
      'Book comparison does not replace or rebuild the ranked idea field');
    assert.deepEqual(pageErrors, [], `overflow/composer/Book focus emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('New Idea from an active or resumed idea returns to the permanent workbench and creates only on Analyze', async () => {
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    bookDocuments: populatedBookDocuments(), universeSymbols: ['AMD', 'AAPL']
  });
  try {
    await page.setViewportSize({ width: 1920, height: 1080 });
    const scenarioOverlaps = await page.evaluate(() => Array.from(
      document.querySelectorAll('#decideStage .scenpanel .srow')
    ).flatMap((row, index) => {
      const name = row.querySelector('.snfull');
      if (!name || getComputedStyle(name).display === 'none') return [];
      const a = name.getBoundingClientRect();
      return Array.from(row.querySelectorAll('.smv,.sprob')).filter(node => {
        if (getComputedStyle(node).display === 'none') return false;
        const b = node.getBoundingClientRect();
        return Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1
          && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1;
      }).map(node => `${index}:${node.className}`);
    }));
    assert.deepEqual(scenarioOverlaps, [],
      '1920×1080 has one scenario-tile media owner and no name/value collisions');
    const createsBefore = backend.count('POST', '/api/plans');
    await page.locator('#threadNewIdea').click();
    await page.waitForSelector('#riskMain .homeworkbenchpanel [data-auth-workbench-query]');
    await page.waitForFunction(() => document.activeElement
      ?.matches('[data-auth-workbench-query]'));
    const returned = await page.evaluate(() => {
      const workbench = document.querySelector('#riskMain .homeworkbenchpanel');
      const market = document.querySelector('#chainBand .authmarketpulse');
      const futures = document.querySelector('#bookrisk .bookfan');
      return {
        level: document.querySelector('#stage')?.classList.contains('lv-book') ? 'book' : null,
        decide: window.decide,
        focused: document.activeElement?.matches('[data-auth-workbench-query]') || false,
        workbenches: document.querySelectorAll('.homeworkbenchpanel').length,
        popups: document.querySelectorAll('.replacementcomposer,.composepanel,.univpop').length,
        fieldControls: workbench?.querySelectorAll('[data-auth-scout-scope]').length,
        goalControls: workbench?.querySelectorAll('[data-auth-scout-goal]').length,
        viewControls: workbench?.querySelectorAll('[data-auth-workbench-view]').length,
        horizonControls: workbench?.querySelectorAll('[data-auth-workbench-horizon]').length,
        riskControls: workbench?.querySelectorAll('[data-auth-workbench-risk]').length,
        marketVisible: !!market && getComputedStyle(market).display !== 'none',
        futuresVisible: !!futures && getComputedStyle(futures).display !== 'none'
      };
    });
    assert.equal(returned.level, 'book');
    assert.equal(returned.decide, null);
    assert.equal(returned.focused, true,
      'New Idea returns attention to the permanent underlying field');
    assert.equal(returned.workbenches, 1,
      'Home owns one permanent Find & Shape workbench');
    assert.equal(returned.popups, 0,
      'New Idea never summons a replacement composer or universe popover');
    assert.equal(returned.fieldControls, 2);
    assert.equal(returned.goalControls, 5);
    assert.equal(returned.viewControls, 3);
    assert.equal(returned.horizonControls, 3);
    assert.equal(returned.riskControls, 3);
    assert.equal(returned.marketVisible, true,
      'Market remains co-visible while shaping another idea');
    assert.equal(returned.futuresVisible, true,
      'Possible futures remains in its compact Book panel instead of becoming the composer');
    assert.equal(backend.count('POST', '/api/plans'), createsBefore,
      'opening the workbench is not a Plan mutation');

    await page.locator('[data-auth-workbench-query]').fill('AAPL');
    await page.locator('[data-auth-workbench-query]').press('Enter');
    assert.deepEqual(await page.evaluate(() => ({
      stagedSymbol: window.homeIdea?.symbol,
      liveIdea: window.decide,
      workbenches: document.querySelectorAll('.homeworkbenchpanel').length
    })), { stagedSymbol: 'AAPL', liveIdea: null, workbenches: 1 },
    'ticker selection stages the next subject in Home without navigating or creating a Plan');
    assert.equal(backend.count('POST', '/api/plans'), createsBefore);
    await page.locator('[data-auth-workbench-analyze]').click();
    await page.waitForFunction(() => window.decide?.backendPhase === 'ready'
      && window.decide?.sym === 'AAPL',
    null, { timeout: 10000 });
    assert.equal(backend.count('POST', '/api/plans'), createsBefore + 1,
      'only Analyze crosses the canonical Plan boundary');
    assert.equal(await page.evaluate(() => window.decide?.canPick), true,
      'the selected subject is a fresh idea and remains replaceable');
    assert.equal(await page.locator('.replacementcomposer,.composepanel').count(), 0);
    assert.deepEqual(pageErrors, [], `Idea replacement emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('the initial ensemble build keeps candidate changes behind one coherent mutation', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
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
    await startNewIdea(page);
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
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { ensembleDelayMs: 320 });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
    for (let attempt = 0; attempt < 100
      && backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`) < 1; attempt += 1) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1,
      'the first idea fan is in flight');

    await page.locator('[data-dec="back"]').click();
    await startNewIdea(page);
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
  page.setDefaultTimeout(8000);
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

    await startNewIdea(page);
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
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { rollQuoteOnFirstEnsemble: true });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
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
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { rollChainOnFirstEnsemble: true });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await startNewIdea(page);
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
  page.setDefaultTimeout(8000);
  const exactPick = scoutFixtures.pick(0, { symbol: 'MU' });
  const exactEvaluation = exactPick.horizons[0].candidates[0].evaluation;
  const compensation = [definedRiskCompensation(exactEvaluation.id, 'MU')];
  const scoutResponse = {
    searched: 105,
    picks: [exactPick],
    compensation,
    frontier: {
      schemaVersion: 'redeployment-frontier-v1',
      universe: { source: 'CONFIGURED', label: 'Active optionable universe', symbols: ['MU', 'AMD', 'NVDA'] },
      destinationAccountId: 'acct-practice',
      decisionRanking: [{
        evaluationId: exactEvaluation.id, symbol: 'MU', strategy: 'PUT_CREDIT_SPREAD',
        decisionScore: 84, economicVerdict: 'FAVORABLE', qualification: 'QUALIFIED',
        identity: {
          key: exactPick.bestIdea.resultKey,
          evaluationId: exactEvaluation.id,
          expiration: '2026-08-21'
        },
        dataCompleteness: { status: 'OBSERVED_COMPLETE' },
        bookImpacts: [{ accountId: 'acct-practice', status: 'IMPROVES' }]
      }],
      compensationRanking: compensation,
      notes: ['Decision economics and compensation are independent rankings.']
    }
  };
  const broadSymbols = ['MU', 'XOM', 'JPM', 'PFE', 'KO'];
  const backend = await installBackend(page, {
    scoutResponse,
    universeSymbols: ['MU', 'AMD', 'NVDA'],
    scoutSymbols: broadSymbols,
    universeSectors: [
      { key: 'ENERGY', label: 'Energy', symbols: ['XOM'] },
      { key: 'FINANCIALS', label: 'Financials', symbols: ['JPM'] },
      { key: 'HEALTHCARE', label: 'Healthcare', symbols: ['PFE'] },
      { key: 'STAPLES', label: 'Consumer staples', symbols: ['KO'] },
      { key: 'SEMICONDUCTORS', label: 'Semiconductors', symbols: ['MU', 'AMD', 'NVDA'] }
    ]
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('[data-auth-opportunity-scan]');
    const idle = await page.evaluate(() => ({
      sectors: Array.from(document.querySelectorAll('.homesectorchips button'))
        .map(node => (node.textContent + ' ' + (node.getAttribute('title') || ''))
          .replace(/\s+/g, ' ').trim()),
      marketSectorChipRows: document.querySelectorAll('#chainBand .homesectorchips').length,
      scoutSectorSelectors: document.querySelectorAll('#riskMain .homesectorchips').length,
      duplicateSectorWalls: document.querySelectorAll('#riskMain .scoutbreadth').length,
      watch: Array.from(document.querySelectorAll('.authmarketrow'))
        .map(node => node.getAttribute('data-auth-market-row-symbol')),
      watchActions: Array.from(document.querySelectorAll('.authmarketrow')).map(node => ({
        action: node.querySelector('.authmarketgo')?.textContent.trim(),
        focus: node.querySelector('.authmarketfocus')?.getAttribute('data-auth-market-symbol'),
        contextWhiteSpace: getComputedStyle(node.querySelector('.authmarketfocus span')).whiteSpace
      })),
      heading: document.querySelector('#sectorBand .lenshd')?.textContent.replace(/\s+/g, ' ').trim()
    }));
    assert.ok(idle.sectors.some(text => /Energy.*1/i.test(text)));
    assert.ok(idle.sectors.some(text => /Healthcare.*1/i.test(text)));
    assert.equal(idle.marketSectorChipRows, 1,
      'the persistent market lens is an always-open chip row on the market panel');
    assert.equal(idle.scoutSectorSelectors, 0,
      'Scout does not own a duplicate selector that disappears with its results');
    assert.equal(idle.duplicateSectorWalls, 0,
      'the workbench reuses the top Market lens instead of duplicating all sectors');
    assert.ok(idle.watch.length > 4,
      `Home watch must cover markets and sectors rather than a fixed four (${idle.watch.join(', ')})`);
    assert.ok(['XOM', 'JPM', 'PFE', 'KO'].some(symbol => idle.watch.includes(symbol)),
      'at least one non-megacap cross-sector representative is visible at rest');
    assert.ok(idle.watchActions.every(row => row.action === 'Stage →'
      && row.focus && row.contextWhiteSpace === 'normal'),
    'every market row exposes separate focus and Stage actions; its receipt cell wraps (stacks the '
    + 'sector badge over the change/freshness) rather than clipping in the narrow watch column');
    assert.match(idle.heading, /Watchlist/i);
    const scanAction = await page.locator('.scoutbar [data-auth-opportunity-scan]').textContent();
    assert.match(scanAction, /Scan/i,
      'the minimal compose bar offers one adaptive Scan action, no catalog wall');
    assert.equal(await page.locator('.opportunitycontrol').first()
      .evaluate(node => getComputedStyle(node).borderTopWidth), '1px',
    'Home uses the same bordered declaration grammar as New Idea');
    await page.locator('[data-auth-workbench-query]').fill('healthcare');
    await page.waitForSelector('[data-auth-sector-match][data-auth-scout-sector="HEALTHCARE"]');
    assert.match(await page.locator('[data-auth-sector-match]').first().textContent(),
      /Healthcare.*optionable symbols.*Use sector/i,
      'the unified field accepts a sector name as well as an underlying');
    await page.locator('[data-auth-sector-match][data-auth-scout-sector="HEALTHCARE"]').click();
    await page.waitForFunction(() => window.HOME_SCOUT?.sector === 'HEALTHCARE');
    assert.match(await page.locator('.homesectorchips button.on').textContent(), /Healthcare/i,
      'sector search and the persistent Market lens chips are one state');
    await page.locator('.homesectorchips button[data-auth-scout-sector=""]').click();
    await page.waitForFunction(() => window.HOME_SCOUT?.sector == null);
    await declareWorkbench(page);
    await page.locator('[data-auth-opportunity-scan]').click();
    await page.waitForSelector('.opportunityrow');
    const result = await page.evaluate(() => window.HOME_OPPORTUNITY.data);

    assert.deepEqual(result, scoutResponse,
      'the bridge preserves the canonical Scout response for the Home lens');
    const receipt = await scoutRowReceipt(page.locator('.opportunityrow').first());
    assert.deepEqual(receipt.facts, {
      expiry: 'Exp 2026-08-21',
      net: 'Net credit $450',
      capital: 'Capital $1,050',
      'max-loss': 'Max loss $1,050'
    }, 'Home shows the exact dated package facts supplied by its retained evaluation');
    assert.deepEqual(receipt.lanes, {
      economics: {
        label: 'Economics', value: 'Favorable', detail: '+$74 after costs · Qualified'
      },
      evidence: {
        label: 'Evidence & events', value: 'Observed Complete', detail: 'Crosses Earnings'
      },
      compensation: {
        label: 'Compensation', value: '$447 premium',
        detail: 'on $1,050 · 28d · 42.57% period · not annualized'
      },
      book: {
        label: 'Book effect', value: 'Improves', detail: 'Destination-Book checks applied'
      }
    }, 'the four independent lanes retain their own authority and units');
    assert.equal(receipt.tag, 'BUTTON');
    assert.equal(receipt.action, 'Analyze →');
    assert.equal(await page.locator('.opportunitycomp').count(), 0,
      'the exact Compensation lane is the sole visible owner; no duplicate premium footer remains');
    const request = backend.requests.find(row => row.method === 'POST'
      && row.path === '/api/research/scout');
    assert.deepEqual(request.body, {
      /* The exact declaration travels. The browser used to collapse every horizon into
         'week'/'month' with its own thresholds, so 30 and 45 became the same scan and 8-10
         sessions bucketed differently here than in Java. */
      horizons: ['45d'],
      maxPicks: 5,
      riskMode: 'balanced',
      allow0dte: false,
      intents: ['INCOME'],
      thesisOverride: 'neutral',
      universe: broadSymbols
    });
    assert.match(await page.locator('.opportunitylens').textContent(),
      /Broad market 5.*Active names 3.*Income.*Directional.*Acquire.*Hedge.*Exit.*Bearish.*Neutral.*Bullish.*7 trading days.*30 trading days.*45 trading days.*Conservative.*Balanced.*Aggressive/i,
      'Home exposes field, goal, view, horizon, and risk controls without a parallel New idea surface');
  } finally {
    await context.close();
  }
});

test('populated Home keeps one permanent idea and Scout workbench without cannibalizing Market', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = populatedBookDocuments();
  const exactPick = scoutFixtures.pick(0, { symbol: 'MU' });
  const exactEvaluation = exactPick.horizons[0].candidates[0].evaluation;
  const finalScout = {
    searched: 5,
    counts: {
      universeConsidered: 5, evidenceEligible: 5, packagesEvaluated: 3, rowsRetained: 1
    },
    picks: [exactPick],
    frontier: {
      universe: { source: 'CURATED', label: 'Cross-sector opportunity frontier', symbols: ['MU', 'XOM', 'JPM', 'PFE', 'KO'] },
      destinationAccountId: 'acct-practice',
      decisionRanking: [{
        symbol: 'MU', strategy: 'PUT_CREDIT_SPREAD', decisionScore: 84,
        economicVerdict: 'FAVORABLE', qualification: 'QUALIFIED',
        evaluationId: exactEvaluation.id,
        identity: {
          key: exactPick.bestIdea.resultKey, evaluationId: exactEvaluation.id,
          expiration: '2026-08-21'
        },
        dataCompleteness: { status: 'OBSERVED_COMPLETE' },
        bookImpacts: [{ accountId: 'acct-practice', status: 'IMPROVES' }]
      }],
      compensationRanking: [definedRiskCompensation('evaluation_other_package', 'MU')],
      notes: ['Decision economics and compensation are independent rankings.']
    }
  };
  const backend = await installBackend(page, {
    bookDocuments,
    scoutResponse: finalScout,
    scoutSymbols: ['MU', 'XOM', 'JPM', 'PFE', 'KO'],
    universeSymbols: ['AAPL', 'SPY', 'QQQ', 'IWM', 'DIA'],
    universeSectors: [
      { key: 'ENERGY', label: 'Energy', symbols: ['XOM'] },
      { key: 'FINANCIALS', label: 'Financials', symbols: ['JPM'] },
      { key: 'HEALTHCARE', label: 'Healthcare', symbols: ['PFE'] },
      { key: 'STAPLES', label: 'Consumer staples', symbols: ['KO'] },
      { key: 'SEMICONDUCTORS', label: 'Semiconductors', symbols: ['MU'] }
    ]
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForSelector('#stage[data-book-authority="ready"] #riskMain #authHomeOpportunity .opportunitylens.expanded');
    await page.waitForSelector('#bookrisk #authBookFan');
    const desktopComposition = await page.evaluate(() => {
      const root = document.querySelector('.homeworkbenchpanel');
      const rootBox = root.getBoundingClientRect();
      const legend = document.getElementById('authBookFanLegend');
      const structural = Array.from(root.querySelectorAll(
        '.opportunitycontrols,.scoutbar,.scoutresults,.homeideasearch,.scoutgo'));
      const overflowTargets = Array.from(root.querySelectorAll(
        '.scoutbar,.scoutresults,.homeideasearch'));
      return {
        workbenchChildrenFit: Array.from(root.children).every(node => {
          const box = node.getBoundingClientRect();
          return box.left >= rootBox.left - 1 && box.right <= rootBox.right + 1
            && box.top >= rootBox.top - 1 && box.bottom <= rootBox.bottom + 1;
        }),
        structuralClipping: structural.filter(node => {
          const box = node.getBoundingClientRect();
          return box.left < rootBox.left - 1 || box.right > rootBox.right + 1
            || box.top < rootBox.top - 1 || box.bottom > rootBox.bottom + 1;
        }).map(node => node.className),
        nestedOverflow: overflowTargets.filter(node =>
          node.scrollHeight - node.clientHeight > 2 || node.scrollWidth - node.clientWidth > 2)
          .map(node => ({
            className: node.className,
            vertical: node.scrollHeight - node.clientHeight,
            horizontal: node.scrollWidth - node.clientWidth
          })),
        futuresOverflow: legend.scrollHeight - legend.clientHeight,
        declarationBorders: Array.from(document.querySelectorAll('.opportunitycontrol'))
          .every(node => parseFloat(getComputedStyle(node).borderTopWidth) >= 1),
        declarationButtonsTransparent: Array.from(document.querySelectorAll(
          '.homeworkbenchpanel .opportunitycontrol .oseg button:not(.on)'))
          .every(node => getComputedStyle(node).backgroundColor === 'rgba(0, 0, 0, 0)')
      };
    });
    assert.equal(desktopComposition.workbenchChildrenFit, true,
      'every permanent workbench section stays inside the Home panel at 1920×1080');
    assert.deepEqual(desktopComposition.structuralClipping, [],
      `Home structural sections must not be silently clipped: ${JSON.stringify(desktopComposition)}`);
    assert.deepEqual(desktopComposition.nestedOverflow, [],
      `Home permanent workbench sections must not hide overflow: ${JSON.stringify(desktopComposition)}`);
    assert.ok(desktopComposition.futuresOverflow <= 2,
      `the compact Futures receipt must not clip or require a nested scroller (${desktopComposition.futuresOverflow}px)`);
    assert.equal(desktopComposition.declarationBorders, true,
      'Home declaration groups retain the shared bordered control grammar');
    assert.equal(desktopComposition.declarationButtonsTransparent, true,
      'Home does not repaint New Idea segmented controls as unrelated black labels');
    const restingMarketRows = await page.locator('#sectorBand .authmarketrow').count();
    assert.ok(restingMarketRows >= 3);
    assert.equal(await page.locator('#sectorBand .opportunitylens').count(), 0,
      'Market is a market surface, never a container for Scout');
    assert.equal(await page.locator('#sectorBand .authmarketrow').count(), restingMarketRows,
      'the permanent workbench does not hide or replace the market watch');
    assert.equal(await page.locator('#sectorBand .opportunitylens').count(), 0);

    await page.evaluate(response => {
      window.DeskBackend.scoutOpportunities = function (_request, onProgress) {
        return new Promise(resolve => {
          setTimeout(() => onProgress({
            phase: 'IDEAS', completed: 1, total: 5, symbol: 'MU',
            message: 'A canonical candidate field is ready.',
            pick: response.picks[0]
          }), 30);
          setTimeout(() => resolve(response), 450);
        });
      };
    }, finalScout);
    await declareWorkbench(page);
    await page.locator('#authHomeOpportunity [data-auth-opportunity-scan]').click();
    await page.waitForSelector('#authHomeOpportunity .opportunityrows.provisional .opportunityrow');
    const progressive = await page.evaluate(() => {
      const hero = document.getElementById('riskMain').getBoundingClientRect();
      const rowNode = document.querySelector('#authHomeOpportunity .opportunityrow');
      const row = rowNode.getBoundingClientRect();
      const rows = rowNode.parentElement;
      const card = rows.closest('.scoutresultcard') || rows.parentElement;
      return {
        rowTop: row.top, rowBottom: row.bottom, heroTop: hero.top, heroBottom: hero.bottom,
        rowsTop: rows.getBoundingClientRect().top,
        rowsBottom: rows.getBoundingClientRect().bottom,
        rowsClient: rows.clientHeight, rowsScroll: rows.scrollHeight, rowsScrollTop: rows.scrollTop,
        cardTop: card.getBoundingClientRect().top, cardBottom: card.getBoundingClientRect().bottom,
        action: document.querySelector('#authHomeOpportunity .opportunitygo')?.textContent.trim(),
        marketRows: document.querySelectorAll('#sectorBand .authmarketrow').length,
        marketVisible: document.getElementById('sectorBand').getClientRects().length > 0
      };
    });
    assert.ok(progressive.rowBottom <= progressive.heroBottom + 1,
      `the first streamed package is visible inside the hero instead of below a clipped well (${JSON.stringify(progressive)})`);
    assert.equal(progressive.action, 'Analyze →');
    assert.equal(progressive.marketRows, restingMarketRows);
    assert.equal(progressive.marketVisible, true);

    await page.waitForSelector('#authHomeOpportunity .opportunityrows:not(.provisional) .opportunityrow');
    const completedWithoutCompensation = await scoutRowReceipt(
      page.locator('#authHomeOpportunity .opportunityrow').first());
    assert.equal(completedWithoutCompensation.lanes.economics.value, 'Favorable');
    assert.equal(completedWithoutCompensation.lanes.economics.detail,
      '+$74 after costs · Qualified');
    assert.equal(completedWithoutCompensation.lanes.book.value, 'Improves');
    assert.deepEqual(completedWithoutCompensation.lanes.compensation, {
      label: 'Compensation',
      value: 'Unavailable',
      detail: 'No compensation receipt for this package'
    }, 'a same-symbol compensation row for another package is not substituted for this package');
    assert.doesNotMatch(completedWithoutCompensation.text,
      /\$0 premium|0\.00%|24\.6%\/yr gross/i,
      'Home never substitutes zero or candidate yield for a missing compensation receipt');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(100);
    const mobile = await page.evaluate(() => {
      const hero = document.querySelector('.homeworkbenchpanel').getBoundingClientRect();
      const market = document.getElementById('chainBand').getBoundingClientRect();
      const rows = document.querySelector('#authHomeOpportunity .opportunityrows');
      return {
        documentWidth: document.documentElement.scrollWidth,
        heroBottom: hero.bottom,
        marketTop: market.top,
        rowsOverflow: getComputedStyle(rows).overflowY,
        innerScrollers: Array.from(document.querySelectorAll('.homeworkbenchpanel *,#bookrisk *'))
          .filter(node => node.scrollHeight > node.clientHeight + 2
            && /auto|scroll/.test(getComputedStyle(node).overflowY)).length
      };
    });
    assert.equal(mobile.documentWidth, 390, 'mobile Scout never creates horizontal page overflow');
    assert.ok(mobile.heroBottom <= mobile.marketTop,
      'mobile Scout expands in the page flow instead of painting over Market');
    assert.equal(mobile.rowsOverflow, 'visible');
    assert.equal(mobile.innerScrollers, 0,
      'mobile Scout uses the one page scroller instead of nesting a result viewport');

    await page.setViewportSize({ width: 2560, height: 1440 });
    await page.waitForTimeout(100);
    const wide = await page.evaluate(() => ({
      width: document.documentElement.scrollWidth,
      height: document.documentElement.scrollHeight,
      viewportWidth: innerWidth,
      viewportHeight: innerHeight
    }));
    assert.deepEqual(wide, {
      width: 2560, height: 1440, viewportWidth: 2560, viewportHeight: 1440
    }, 'wide Scout and Market compose without page scroll');

    await page.waitForSelector('#bookrisk #authBookFan');
    assert.equal(await page.locator('#riskMain #authHomeOpportunity').count(), 1,
      'the workbench remains part of Home after a completed scan');
    assert.equal(await page.locator('#sectorBand .authmarketrow').count(), restingMarketRows);
    // Assert the OUTCOME, not the call signature: the idea that opens must already hold what the
    // user declared on Home. Spying on enterDecide's arguments only proved one caller happened to
    // pass them along, which is exactly the per-route duplication the one context replaced.
    await page.locator('#authHomeOpportunity .opportunityrow').first().click();
    await page.waitForFunction(() => window.decide != null);
    assert.deepEqual(await page.evaluate(() => ({
      kind: window.decide.kind, positionId: window.decide.posId, label: window.decide.act,
      symbol: window.decide.sym, resumePlanId: window.decide.resumePlanId,
      goal: window.decide.goal, view: window.decide.view,
      horizon: window.decide.horizon, riskMode: window.decide.riskMode
    })), {
      kind: 'idea', positionId: null, label: 'New idea', symbol: 'MU', resumePlanId: null,
      goal: 'Income', view: 'Neutral', horizon: '45 trading days', riskMode: 'Balanced'
    }, 'the entire Scout result zooms directly into the canonical New Idea workspace');
    assert.deepEqual(await page.evaluate(() => ({
      goal: window.WORKSPACE.goal, view: window.WORKSPACE.view,
      horizon: workspaceHorizonLabel(window.WORKSPACE.horizonDays),
      riskPosture: window.WORKSPACE.riskPosture,
      focusedSymbol: window.WORKSPACE.focusedSymbol
    })), {
      goal: 'INCOME', view: 'Neutral', horizon: '45 trading days', riskPosture: 'Balanced',
      focusedSymbol: 'MU'
    }, 'one workspace context holds the declaration the idea was opened with');
    await page.evaluate(() => { if (typeof exitDecide === 'function') exitDecide(); });
    assert.deepEqual(pageErrors, [], `focused Home Scout emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Scout lifecycle streams exact rows, cancels and fails without losing work, then retries once', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));

  const partialPick = scoutFixtures.pick(0, { symbol: 'AAPL' });
  const evaluation = partialPick.horizons[0].candidates[0].evaluation;
  const finalScout = scoutFixtures.autoResult({
    pickCount: 0,
    counts: {
      universeConsidered: 5, evidenceEligible: 4, packagesEvaluated: 9, rowsRetained: 1
    }
  });
  finalScout.picks = [partialPick];
  finalScout.frontier = {
    universe: {
      source: 'CURATED', label: 'Cross-sector opportunity frontier',
      symbols: ['AAPL', 'SPY', 'QQQ', 'IWM', 'DIA']
    },
    destinationAccountId: 'acct-practice',
    decisionRanking: [{
      evaluationId: evaluation.id,
      identity: {
        key: partialPick.bestIdea.resultKey,
        evaluationId: evaluation.id,
        expiration: '2026-08-21'
      },
      symbol: 'AAPL',
      strategy: 'PUT_CREDIT_SPREAD',
      decisionScore: 84,
      economicVerdict: 'FAVORABLE',
      qualification: 'QUALIFIED',
      dataCompleteness: { status: 'OBSERVED_COMPLETE' },
      bookImpacts: [{ accountId: 'acct-practice', status: 'IMPROVES' }]
    }],
    compensationRanking: [{
      ...definedRiskCompensation(evaluation.id, 'AAPL')
    }],
    notes: ['Decision economics and compensation are independent rankings.']
  };

  await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    universeSymbols: ['AAPL', 'SPY', 'QQQ', 'IWM', 'DIA'],
    scoutSymbols: ['AAPL', 'SPY', 'QQQ', 'IWM', 'DIA']
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await declareWorkbench(page);

    await page.evaluate(({ pick, complete }) => {
      window.__scoutAttempts = 0;
      window.__scoutCancelCalls = 0;
      window.__scoutPending = null;
      window.DeskBackend.scoutOpportunities = function (_request, onProgress) {
        const attempt = ++window.__scoutAttempts;
        return new Promise((resolve, reject) => {
          window.__scoutPending = { attempt, reject };
          setTimeout(() => onProgress({
            phase: 'IDEAS',
            phaseCompleted: attempt === 2 ? 1 : 2,
            phaseTotal: 5,
            counts: attempt === 2 ? {
              universeConsidered: 1,
              evidenceEligible: 0,
              packagesEvaluated: 0,
              rowsRetained: 0
            } : {
              universeConsidered: 2,
              evidenceEligible: 1,
              packagesEvaluated: 3,
              rowsRetained: 1
            },
            symbol: 'AAPL',
            message: attempt === 2
              ? 'The retry is reading fresh evidence.'
              : 'One exact package is ready.',
            pick: attempt === 2 ? null : pick
          }), 30);
          if (attempt === 2) {
            setTimeout(() => reject(new Error(
              'The remaining field could not be read; earned packages are still valid.'
            )), 300);
          } else if (attempt === 3) {
            setTimeout(() => resolve(complete), 180);
          }
        });
      };
      window.DeskBackend.cancelScout = function () {
        window.__scoutCancelCalls += 1;
        const pending = window.__scoutPending;
        if (pending) {
          pending.reject(new DOMException('The scan was cancelled.', 'AbortError'));
          window.__scoutPending = null;
        }
      };
    }, { pick: partialPick, complete: finalScout });

    await page.locator('[data-auth-opportunity-scan]').click();
    assert.equal(await page.evaluate(() => window.HOME_OPPORTUNITY.phase), 'starting');
    assert.equal(await page.locator('[data-auth-opportunity-cancel]').textContent(), 'Cancel scan',
      'a running scan always has a visible cancellation action');

    await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'partial');
    const partialRow = page.locator('#authHomeOpportunity .opportunityrow').first();
    assert.equal(await partialRow.getAttribute('data-scout-stage'), 'provisional');
    assert.equal(await partialRow.getAttribute('data-auth-evaluation'), evaluation.id);
    assert.equal(await partialRow.getAttribute('data-auth-result-key'),
      partialPick.bestIdea.resultKey);
    const partialReceipt = await scoutRowReceipt(partialRow);
    assert.deepEqual(partialReceipt.facts, {
      expiry: 'Exp 2026-08-21',
      net: 'Net credit $450',
      capital: 'Capital $1,050',
      'max-loss': 'Max loss $1,050'
    }, 'the first streamed row already contains exact package facts');
    assert.deepEqual(partialReceipt.lanes, {
      economics: {
        label: 'Economics', value: 'Favorable', detail: '+$74 after costs'
      },
      evidence: {
        label: 'Evidence & events', value: 'Observed', detail: 'Crosses Earnings'
      },
      compensation: {
        label: 'Compensation', value: 'Unavailable',
        detail: 'No compensation receipt for this package'
      },
      book: {
        label: 'Book effect', value: 'Pending', detail: 'Applied after the scan completes'
      }
    }, 'a progressive row names all four lanes without inventing a compensation fallback');
    assert.doesNotMatch(partialReceipt.text, /\$0 premium|0\.00%|24\.6%\/yr gross/i,
      'missing compensation is unavailable, never zero or the candidate annualized-yield fallback');
    assert.equal(partialReceipt.tag, 'BUTTON');
    assert.equal(partialReceipt.action, 'Analyze →');
    assert.match((await page.locator('.scoutprogress').textContent()).replace(/\s+/g, ' '),
      /2 considered.*1 eligible.*3 priced.*1 retained/i,
      'the four monotonic counts are visible while the scan is still running');

    await page.locator('[data-auth-opportunity-cancel]').click();
    await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'cancelled');
    assert.equal(await page.evaluate(() => window.__scoutCancelCalls), 1);
    assert.equal(await page.locator('#authHomeOpportunity .opportunityrow').count(), 1,
      'cancellation preserves the exact row already earned');
    const cancelledText = await page.locator('#authHomeOpportunity').textContent();
    assert.match(cancelledText, /2 considered.*1 eligible.*3 priced.*1 retained/i,
      'cancellation preserves the count receipt');
    assert.match(cancelledText, /Scan cancelled/i,
      'cancellation explains the interruption');
    assert.match(cancelledText, /Retry scan/i,
      'cancellation offers one explicit retry');

    await page.locator('[data-auth-opportunity-scan]').click();
    await page.waitForFunction(() => ['starting', 'partial']
      .includes(window.HOME_OPPORTUNITY?.phase));
    await page.evaluate(() => {
      window.authRunOpportunityScan();
      window.authRunOpportunityScan();
    });
    assert.equal(await page.evaluate(() => window.__scoutAttempts), 2,
      'repeated starts during one active attempt are idempotent');
    await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'failed');
    assert.equal(await page.locator('#authHomeOpportunity .opportunityrow').count(), 1,
      'a terminal failure preserves earned rows');
    const failedText = await page.locator('#authHomeOpportunity').textContent();
    assert.match(failedText, /2 considered.*1 eligible.*3 priced.*1 retained/i,
      'failure preserves the last monotonic count receipt');
    assert.match(failedText, /Scan stopped before completion.*remaining field could not be read/i,
      'failure explains itself without erasing work');
    assert.match(failedText, /Retry scan/i,
      'failure offers one explicit retry');
    assert.match(failedText, /Stopped/i,
      'the retained progress receipt names the terminal state');
    assert.doesNotMatch(failedText, /\bScanning\b/i,
      'a stopped scan never continues to present its last in-flight phase as live');

    await page.locator('[data-auth-opportunity-scan]').click();
    await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'complete');
    assert.equal(await page.evaluate(() => window.__scoutAttempts), 3);
    const completeRow = page.locator('#authHomeOpportunity .opportunityrow').first();
    assert.equal(await completeRow.getAttribute('data-scout-stage'), 'complete');
    const completeReceipt = await scoutRowReceipt(completeRow);
    assert.deepEqual(completeReceipt.facts, partialReceipt.facts,
      'completion enriches the same exact package instead of replacing it');
    assert.deepEqual(completeReceipt.lanes, {
      economics: {
        label: 'Economics', value: 'Favorable', detail: '+$74 after costs · Qualified'
      },
      evidence: {
        label: 'Evidence & events', value: 'Observed Complete', detail: 'Crosses Earnings'
      },
      compensation: {
        label: 'Compensation', value: '$447 premium',
        detail: 'on $1,050 · 28d · 42.57% period · not annualized'
      },
      book: {
        label: 'Book effect', value: 'Improves', detail: 'Destination-Book checks applied'
      }
    }, 'completion fills compensation and Book lanes from their exact backend receipts');
    assert.match((await page.locator('#authHomeOpportunity').textContent()).replace(/\s+/g, ' '),
      /5 considered.*4 eligible.*9 priced.*1 retained/i);

    await completeRow.click();
    await page.waitForFunction(() => window.decide != null);
    assert.deepEqual(await page.evaluate(() => ({
      symbol: window.decide.sym,
      evaluationId: window.decide.evaluationId,
      goal: window.decide.goal,
      view: window.decide.view,
      horizon: window.decide.horizon,
      riskMode: window.decide.riskMode,
      focusedEvaluationId: window.WORKSPACE.focusedEvaluationId
    })), {
      symbol: 'AAPL',
      evaluationId: evaluation.id,
      goal: 'Income',
      view: 'Neutral',
      horizon: '45 trading days',
      riskMode: 'Balanced',
      focusedEvaluationId: evaluation.id
    }, 'Analyze opens canonical New Idea with the exact package identity and declarations');
    assert.deepEqual(pageErrors, [],
      `Scout lifecycle emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

/* ---------------------------------------------------------------------------------------------
   GEOMETRY LANE — Evidence & Paths (program §6.3, §10).
   A test that merely clicks the fan and passes is insufficient: the fan can be a one-pixel strip
   inside a scroller and still receive a synthetic click. These assertions describe the PRODUCT
   requirement — the active lens owns the remaining right-column height, the fan itself never
   scrolls, its plot is actually useful, and its stats stay visible.
   --------------------------------------------------------------------------------------------- */
for (const viewport of [{ width: 1920, height: 1080 }, { width: 2560, height: 1440 }]) {
  test(`Evidence & Paths fan owns a usable plot at ${viewport.width}x${viewport.height}`, async () => {
    const { context, page, pageErrors } = await openAuthoritativeDesk({ viewport });
    try {
      // Readiness is the DRAWN fan (its interaction surface) with its reveal animation settled —
      // a state signal, not a sleep. While the sweep runs the plot is intentionally clipped.
      await page.waitForSelector('#mcFan .fan-interaction');
      await page.waitForFunction(() => {
        const fan = document.querySelector('#mcFan');
        if (!fan || !fan.querySelector('.fan-interaction')) return false;
        const running = (fan.getAnimations ? fan.getAnimations() : [])
          .some(a => a.playState === 'running' || a.playState === 'pending');
        return !running;
      });
      const geo = await page.evaluate(() => {
        const fan = document.querySelector('#mcFan');
        const rect = fan.getBoundingClientRect();
        // Walk ancestors: none between the fan and the decide grid may be a scroll owner.
        const scrollingAncestors = [];
        for (let el = fan.parentElement; el && !el.classList.contains('decgrid'); el = el.parentElement) {
          const cs = getComputedStyle(el);
          const scrolls = (cs.overflowY === 'auto' || cs.overflowY === 'scroll')
            && el.scrollHeight - el.clientHeight > 2;
          if (scrolls) {
            scrollingAncestors.push({
              cls: (el.getAttribute('class') || el.tagName),
              overflow: el.scrollHeight - el.clientHeight
            });
          }
        }
        const stats = document.querySelector('.ensembleresult .mcstats');
        const statsRect = stats && stats.getBoundingClientRect();
        const right = document.querySelector('.deccol.dcright');
        const rightRect = right && right.getBoundingClientRect();
        // Hit-test the plot centre: the click target must actually be the fan, not an overlay.
        const cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
        const hit = document.elementFromPoint(cx, cy);
        return {
          plotHeight: Math.round(rect.height),
          plotWidth: Math.round(rect.width),
          scrollingAncestors,
          statsVisible: !!(statsRect && statsRect.height > 0 && statsRect.width > 0),
          withinRightColumn: !!(rightRect && rect.top >= rightRect.top - 1
            && rect.bottom <= rightRect.bottom + 1),
          centreHitsFan: !!(hit && (hit === fan || fan.contains(hit))),
          centreHit: hit ? (hit.tagName + '.' + (hit.getAttribute('class') || '')
            + (hit.closest('[data-mc-surface]') ? ' [in-mc-surface]' : '')
            + (hit.closest('#mcFan') ? ' [in-fan]' : '')) : null,
          // There must be exactly ONE market owner on this surface.
          marketPanels: document.querySelectorAll('.decgrid .marketlens').length,
          marketPanelsInRightColumn: document.querySelectorAll('.dcright .marketlens').length,
          // Only a DESIGNATED row list may own a scroller; a chart, fan, or analysis panel
          // that scrolls is an accident. Page-level scroll is never acceptable at these sizes.
          undesignatedScrollOwners: Array.from(document.querySelectorAll('.decwrap *'))
            .filter(el => {
              const cs = getComputedStyle(el);
              const v = (cs.overflowY === 'auto' || cs.overflowY === 'scroll')
                && el.scrollHeight - el.clientHeight > 2;
              const h = (cs.overflowX === 'auto' || cs.overflowX === 'scroll')
                && el.scrollWidth - el.clientWidth > 2;
              if (!v && !h) return false;
              // designated row lists: nearby-chain rows, candidate rail, news, overflow lists
              return !el.closest('.packagebooknear, .authnews, .elegantscroll, .authlist, .cands, .pickmap');
            })
            .map(el => (el.getAttribute('class') || el.tagName).slice(0, 44)),
          pageScrollX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
          pageScrollY: document.documentElement.scrollHeight - document.documentElement.clientHeight
        };
      });

      assert.ok(geo.plotHeight >= 260,
        `the active path fan needs a usable plot, got ${geo.plotHeight}px (want >=260)`);
      assert.deepEqual(geo.scrollingAncestors, [],
        `no ancestor of the fan may scroll: ${JSON.stringify(geo.scrollingAncestors)}`);
      assert.equal(geo.statsVisible, true, 'ensemble stats stay visible beside the fan');
      assert.equal(geo.withinRightColumn, true, 'the fan renders inside its own column bounds');
      assert.equal(geo.centreHitsFan, true,
        `the plot centre hit-tests to the fan — nothing overlays the interactive paths (hit=${geo.centreHit})`);
      assert.deepEqual(geo.undesignatedScrollOwners, [],
        `only a designated row list may scroll on the Idea surface: ${JSON.stringify(geo.undesignatedScrollOwners)}`);
      assert.equal(geo.pageScrollY, 0, 'the Idea surface does not scroll the page at desktop sizes');
      assert.equal(geo.pageScrollX, 0, 'no horizontal page overflow');
      assert.equal(geo.marketPanelsInRightColumn, 0,
        'the right column hosts the decision lens only — no second standing market band');
      assert.equal(geo.marketPanels, 1, 'exactly one market owner on the Idea surface');
      assert.deepEqual(pageErrors, [], `fan geometry emitted page errors: ${pageErrors.join('\n')}`);
    } finally {
      await context.close();
    }
  });
}



/* ---------------------------------------------------------------------------------------------
   STYLE SNAPSHOT INSTRUMENT (program §10 geometry lane).
   Captures computed styles + boxes for EVERY rendered element across the real surfaces and
   viewports, reusing this file's mocked backend so no fixture is duplicated. Skipped unless
   STYLE_SNAPSHOT_OUT is set, so it never slows the normal lane:

     STYLE_SNAPSHOT_OUT=/tmp/before.json node --test --test-name-pattern="style snapshot" desk-backend.test.js

   Existing verification only ever saw the 59-element boot DOM, which is why three separate CSS
   deletions passed review and still broke Idea/Position/mobile.
   --------------------------------------------------------------------------------------------- */
const SNAPSHOT_PROPS = ['display','position','overflowX','overflowY','width','height','marginTop',
  'marginBottom','marginLeft','marginRight','paddingTop','paddingBottom','paddingLeft','paddingRight',
  'fontSize','fontWeight','lineHeight','color','backgroundColor','borderTopWidth','borderTopColor',
  'borderRadius','flexGrow','flexShrink','flexBasis','flexDirection','flexWrap','gridTemplateColumns',
  'gridTemplateRows','gridArea','whiteSpace','textOverflow','clipPath','zIndex','opacity','minHeight',
  'maxHeight','minWidth','maxWidth','gap','alignItems','justifyContent','textAlign','transform'];

/* Wait for the DOM to STOP changing. Async receipts (news, quotes, opportunity rows) land at
   different moments per run; capturing mid-settle made the element count vary between identical
   runs, which an index-keyed diff then reported as hundreds of phantom changes. */
async function settleDom(page) {
  await page.waitForFunction(() => {
    const n = document.querySelectorAll('body *').length;
    const prev = window.__settleCount, stable = window.__settleStable || 0;
    window.__settleCount = n;
    window.__settleStable = (prev === n) ? stable + 1 : 0;
    const animating = (document.getAnimations ? document.getAnimations() : [])
      .some(a => a.playState === 'running' || a.playState === 'pending');
    return window.__settleStable >= 4 && !animating;
  }, null, { timeout: 20000, polling: 250 }).catch(() => {});
}

async function captureStyles(page) {
  await settleDom(page);
  /* Key every element by a STABLE structural path, never by index: an element appearing or
     disappearing must show up as exactly that, not shift every later row. */
  return page.evaluate(props => {
    const out = {};
    const path = el => {
      const parts = [];
      for (let n = el; n && n.tagName && n.tagName !== 'BODY'; n = n.parentElement) {
        let i = 1;
        for (let s = n.previousElementSibling; s; s = s.previousElementSibling) {
          if (s.tagName === n.tagName) i++;
        }
        parts.unshift(`${n.tagName}[${i}]`);
      }
      return parts.join('/');
    };
    document.querySelectorAll('body *').forEach(el => {
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      const cls = el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className;
      out[path(el)] = `.${cls}#${Math.round(r.width)}x${Math.round(r.height)}`
        + `@${Math.round(r.left)},${Math.round(r.top)}|${props.map(p => cs[p]).join('|')}`;
    });
    return out;
  }, SNAPSHOT_PROPS);
}

const SNAPSHOT_VIEWPORTS = [
  { width: 2560, height: 1440 }, { width: 1920, height: 1080 },
  { width: 1440, height: 900 }, { width: 390, height: 844 }
];

if (process.env.STYLE_SNAPSHOT_OUT) test('style snapshot across surfaces and viewports', async () => {
  const fsp = await import('node:fs/promises');
  const out = {};
  for (const viewport of SNAPSHOT_VIEWPORTS) {
    const tag = `${viewport.width}x${viewport.height}`;

    // --- Book / Home, and Position (same context: Position is reached from the Book) ---
    {
      const context = await browser.newContext({ viewport });
      const page = await context.newPage();
      page.setDefaultTimeout(15000);
      await installBackend(page, { bookDocuments: populatedBookDocuments() });
      await page.goto(deskUrl);
      await waitForDeskBoot(page);
      await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready', null, { timeout: 15000 });
      await page.waitForSelector(`#stage[data-book-authority="ready"] #book .card[data-id="${BOOK_TRADE_ID}"]`, { timeout: 15000 });
      await page.waitForFunction(() => {
        const running = document.getAnimations ? document.getAnimations() : [];
        return !running.some(a => a.playState === 'running' || a.playState === 'pending');
      }, null, { timeout: 15000 }).catch(() => {});
      out[`book@${tag}`] = await captureStyles(page);

      await page.evaluate(id => window.go('position', id), BOOK_TRADE_ID);
      await page.waitForSelector('#stage.lv-position', { timeout: 15000 }).catch(() => {});
      await page.waitForFunction(() => {
        const running = document.getAnimations ? document.getAnimations() : [];
        return !running.some(a => a.playState === 'running' || a.playState === 'pending');
      }, null, { timeout: 15000 }).catch(() => {});
      out[`position@${tag}`] = await captureStyles(page);
      await context.close();
    }

    // --- Idea / Decide ---
    {
      const { context, page } = await openAuthoritativeDesk({ viewport });
      await page.waitForSelector('#mcFan .mcinteraction', { timeout: 15000 }).catch(() => {});
      await page.waitForFunction(() => {
        const running = document.getAnimations ? document.getAnimations() : [];
        return !running.some(a => a.playState === 'running' || a.playState === 'pending');
      }, null, { timeout: 15000 }).catch(() => {});
      out[`idea@${tag}`] = await captureStyles(page);
      await context.close();
    }
  }
  await fsp.writeFile(process.env.STYLE_SNAPSHOT_OUT, JSON.stringify(out));
  const summary = Object.keys(out).map(k => `${k}=${Object.keys(out[k]).length}`).join(' ');
  console.log('STYLE_SNAPSHOT ' + summary);
});

/* Runtime selector census: which stylesheet selectors actually MATCH a live DOM, across every
   surface and viewport. Static class analysis cannot answer this (a selector can be reachable
   through markup no grep can see), so liveness is measured, not inferred.
     SELECTOR_CENSUS_IN=/tmp/selectors.json SELECTOR_CENSUS_OUT=/tmp/hits.json \
       node --test --test-name-pattern="selector census" desk-backend.test.js            */
if (process.env.SELECTOR_CENSUS_IN) test('selector census across surfaces', async () => {
  const fsp = await import('node:fs/promises');
  const selectors = JSON.parse(await fsp.readFile(process.env.SELECTOR_CENSUS_IN, 'utf8'));
  const hits = new Set();
  async function probe(page) {
    const matched = await page.evaluate(list => {
      const found = [];
      for (const sel of list) {
        // probe structure only: pseudo-classes/elements describe state, not reachability
        const probeSel = sel.replace(/::?[-\w]+(\([^)]*\))?/g, '').trim();
        if (!probeSel) { found.push(sel); continue; }
        try { if (document.querySelector(probeSel)) found.push(sel); } catch { found.push(sel); }
      }
      return found;
    }, selectors);
    matched.forEach(s => hits.add(s));
  }
  for (const viewport of [{ width: 2560, height: 1440 }, { width: 1920, height: 1080 },
                          { width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    {
      const context = await browser.newContext({ viewport });
      const page = await context.newPage();
      page.setDefaultTimeout(15000);
      await installBackend(page, { bookDocuments: populatedBookDocuments() });
      await page.goto(deskUrl);
      await waitForDeskBoot(page);
      await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready', null, { timeout: 15000 });
      await probe(page);
      await page.evaluate(id => window.go('position', id), BOOK_TRADE_ID);
      await page.waitForSelector('#stage.lv-position', { timeout: 15000 }).catch(() => {});
      await probe(page);
      await context.close();
    }
    {
      const { context, page } = await openAuthoritativeDesk({ viewport });
      await page.waitForSelector('#mcFan .mcinteraction', { timeout: 15000 }).catch(() => {});
      await probe(page);
      // exercise each inspect lens so lens-scoped rules are reachable
      for (const lens of ['fit', 'mechanics', 'book', 'paths']) {
        await page.locator(`[data-dec="inspect"][data-inspect="${lens}"]`).click({ timeout: 4000 }).catch(() => {});
        await probe(page);
      }
      await context.close();
    }
  }
  await fsp.writeFile(process.env.SELECTOR_CENSUS_OUT, JSON.stringify([...hits]));
  console.log(`SELECTOR_CENSUS matched=${hits.size} of ${selectors.length}`);
});


test('backend PositionAnimation v2 owns cash-calendar, outliving-option, stock, physical, and 0DTE boundaries', async () => {
  /* Leg dates are deliberately misleading in the cash-calendar case: the first expiry is earlier
     than the v2 final expiry. Every result must follow the backend terminal index and reason. */
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const bookDocuments = populatedBookDocuments();
  bookDocuments.bookRisk.practice.measuredBook = measuredJointBookReceipt();
  await installBackend(page, { bookDocuments });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready', null, { timeout: 12000 });
    await page.waitForFunction(id => {
      const slot = window.BOOK_FAN?.rows?.[id];
      return slot && slot.phase === 'ready' && (slot.frames || []).length > 0;
    }, BOOK_TRADE_ID, { timeout: 12000 });

    const bookLife = await page.evaluate(id => {
      const slot = window.BOOK_FAN.rows[id];
      return {
        lastSession: slot.frames[slot.frames.length - 1].x,
        frameCount: slot.frames.length,
        pathLengths: (slot.paths || []).map(p => p.length),
        finalOptionExpiration: slot.finalOptionExpiration,
        boundaryReason: slot.boundaryReason,
        exposureResolvedAtBoundary: slot.exposureResolvedAtBoundary,
        boundaryLabel: slot.boundaryLabel
      };
    }, BOOK_TRADE_ID);

    assert.deepEqual(bookLife, {
      lastSession: 23,
      frameCount: 5,
      pathLengths: [5, 5, 5],
      finalOptionExpiration: '2026-08-21',
      boundaryReason: 'FINAL_CASH_SETTLEMENT',
      exposureResolvedAtBoundary: true,
      boundaryLabel: 'cash settled · final expiry 08-21'
    }, 'Book uses the exact same v2 terminal index and resolution semantics as Position and Idea');

    const lifecycleCases = [
      {
        name: 'cash calendar',
        animation: {
          terminalFrameIndex: 4, terminalSessionProgress: 23,
          finalOptionExpiration: '2026-08-21',
          boundaryReason: 'FINAL_CASH_SETTLEMENT',
          exposureResolvedAtBoundary: true
        },
        legs: [{ expiration: '2026-07-25' }, { expiration: '2026-08-21' }],
        expectedKeep: 5, expectedLabel: 'cash settled · final expiry 08-21'
      },
      {
        name: 'option outlives track',
        animation: {
          terminalFrameIndex: 5, terminalSessionProgress: 29,
          finalOptionExpiration: '2026-09-18',
          boundaryReason: 'HORIZON_END_OPTION_OUTLIVES_TRACK',
          exposureResolvedAtBoundary: false
        },
        legs: [{ expiration: '2026-07-25' }],
        expectedKeep: 6, expectedLabel: 'horizon · option outlives track'
      },
      {
        name: 'stock only',
        animation: {
          terminalFrameIndex: 5, terminalSessionProgress: 29,
          finalOptionExpiration: null,
          boundaryReason: 'HORIZON_END_STOCK_EXPOSURE',
          exposureResolvedAtBoundary: false
        },
        legs: [{ t: 's' }],
        expectedKeep: 6, expectedLabel: 'horizon · shares remain open'
      },
      {
        name: 'stock plus option',
        animation: {
          terminalFrameIndex: 5, terminalSessionProgress: 29,
          finalOptionExpiration: '2026-08-21',
          boundaryReason: 'HORIZON_END_STOCK_EXPOSURE',
          exposureResolvedAtBoundary: false
        },
        legs: [{ t: 's' }, { expiration: '2026-08-21' }],
        expectedKeep: 6, expectedLabel: 'horizon · shares remain open'
      },
      {
        name: 'physical',
        animation: {
          terminalFrameIndex: 4, terminalSessionProgress: 23,
          finalOptionExpiration: '2026-08-21',
          boundaryReason: 'HORIZON_END_PHYSICAL_EXPOSURE',
          exposureResolvedAtBoundary: false
        },
        legs: [{ expiration: '2026-07-25' }, { expiration: '2026-08-21' }],
        expectedKeep: 5, expectedLabel: 'horizon · physical exposure remains'
      },
      {
        name: '0DTE cash',
        animation: {
          terminalFrameIndex: 1, terminalSessionProgress: 1,
          finalOptionExpiration: '2026-07-20',
          boundaryReason: 'FINAL_CASH_SETTLEMENT',
          exposureResolvedAtBoundary: true
        },
        legs: [{ expiration: '2026-07-20' }],
        firstCloseFrame: true,
        expectedKeep: 2, expectedLabel: 'cash settled · final expiry 07-20'
      }
    ].map(row => {
      const response = positionScenarioResponse({ waypoints: [], limit: 6 }, {
        positionAnimation: Object.assign({ frameCount: 6, unavailableReason: null }, row.animation)
      });
      if (row.firstCloseFrame) {
        response.checkpoints.underlyingSteps[1].sessionProgress = 1;
        response.checkpoints.positions[0].steps[1].sessionProgress = 1;
        response.checkpoints.positions[0].stepBands[1].sessionProgress = 1;
        response.checkpoints.positions[0].displayPaths.forEach(path => {
          path.steps[1].sessionProgress = 1;
        });
      }
      /* Renderers receive this typed result only after the bridge validates the wire arrays.
         These table-driven cases exercise renderer consumption of six server-owned boundaries;
         the integration paths above separately exercise the validator itself. */
      response.checkpoints.validatedAnimationBoundary = Object.assign({
        available: true,
        unavailableReason: null
      }, response.checkpoints.positions[0].animation);
      return Object.assign({}, row, { source: response.checkpoints });
    });

    const lifecycle = await page.evaluate(({ tradeId, cases }) => cases.map(row => {
      const p = {
        id: tradeId, authoritative: true, isHeld: true, legs: row.legs,
        authoritativeAnimation: { checkpoints: row.source }
      };
      const fan = {
        frames: row.source.positions[0].stepBands.map(step => ({
          sourceStep: step.step, day: step.sessionProgress, p50: step.pnlP50Cents
        })),
        pathProgress: row.source.positions[0].displayPaths[0].steps.map(step => step.sessionProgress),
        pathSteps: row.source.positions[0].displayPaths[0].steps.map(step => step.step),
        paths: row.source.positions[0].displayPaths.map(path => path.steps.map(step => step.pnlCents)),
        horizonDays: 29, targetDay: 29, targetStep: 5
      };
      const boundary = window.authAnimationBoundary(p, row.source);
      const cut = window.authFanAtBoundary(fan, p, row.source);
      return {
        name: row.name,
        terminalFrameIndex: boundary.terminalFrameIndex,
        terminalSessionProgress: boundary.terminalSessionProgress,
        finalOptionExpiration: boundary.finalOptionExpiration,
        boundaryReason: boundary.boundaryReason,
        exposureResolvedAtBoundary: boundary.exposureResolvedAtBoundary,
        label: window.authBoundaryLabel(p, row.source),
        frameCount: cut?.frames?.length,
        pathLengths: cut?.paths?.map(path => path.length)
      };
    }), { tradeId: BOOK_TRADE_ID, cases: lifecycleCases });

    lifecycle.forEach((actual, index) => {
      const expected = lifecycleCases[index];
      assert.equal(actual.terminalFrameIndex, expected.animation.terminalFrameIndex, expected.name);
      assert.equal(actual.terminalSessionProgress, expected.animation.terminalSessionProgress, expected.name);
      assert.equal(actual.finalOptionExpiration, expected.animation.finalOptionExpiration, expected.name);
      assert.equal(actual.boundaryReason, expected.animation.boundaryReason, expected.name);
      assert.equal(actual.exposureResolvedAtBoundary,
        expected.animation.exposureResolvedAtBoundary, expected.name);
      assert.equal(actual.label, expected.expectedLabel, expected.name);
      assert.equal(actual.frameCount, expected.expectedKeep, expected.name);
      assert.ok(actual.pathLengths.every(length => length === expected.expectedKeep), expected.name);
    });

    const exactResponse = scenarioResponse('second');
    exactResponse.checkpoints.validatedAnimationBoundary = Object.assign({
      available: true,
      unavailableReason: null
    }, exactResponse.checkpoints.positions[0].animation);
    const exactFrames = await page.evaluate(({ candidateId, source }) => {
      const p = {
        id: candidateId, authoritative: true, isHeld: false,
        authoritativeAnimation: { checkpoints: source }
      };
      return [0.24, 0.26, 0.74, 0.76].map(t => window.authoritativeFrame(p, t));
    }, { candidateId: CANDIDATE_ID, source: exactResponse.checkpoints });
    assert.deepEqual(exactFrames.map(frame => frame.frameIndex), [2, 3, 7, 8]);
    assert.deepEqual(exactFrames.map(frame => frame.price), [101.1, 103.4, 106.1, 105.4],
      'scrubbing selects exact served prices; no midpoint price is synthesized');
    assert.deepEqual(exactFrames.map(frame => frame.pnl), [98.6, 291.4, 529.6, 476.4],
      'scrubbing selects exact served P/L; no interpolated financial fact is synthesized');
    assert.ok(!exactFrames.some(frame => frame.price === 102.25 || frame.pnl === 195),
      'no browser interpolation-generated price or P/L can appear');
    assert.deepEqual(pageErrors, [], `v2 lifecycle playback emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

/* ======================================================================
   M0 — RENDERED-STRING CONTRACTS (audit §16.3, program §3.1/§3.2)

   Everything above asserts DOM structure, bridge state and request counts against mocked APIs.
   None of it asserts a money string a reader can see, which is how three rendering regressions
   reached the owner's screen while this suite reported a full green run. Each test below reads the
   exact text rendered into a named surface and says, in its own failure message, which defect it
   found and where. They are contract tests, not journeys: one defect, one surface, one string.
   ====================================================================== */

test('an unpriced package renders as unavailable in the candidate rail, never a fabricated +$0', async () => {
  /* candCollect() returns null for a package the book could not price. Its callers then ask
     `candCollect(c) >= 0` and `signed(candCollect(c))`. In JavaScript `null >= 0` is true and
     signed(null) is money(0) with a "+" in front, so BOTH the ranked rail and the risk-map hover
     card state that this package collects exactly zero premium — a number no receipt contains
     (§3.2: missing evidence never becomes a value). The unpriced row is a comparison row here;
     the priced candidate stays selected so the surface under test is the rail itself. */
  const unpriced = unpricedCandidate();
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    strategyCandidates: [candidate(), unpriced]
  });
  try {
    await page.waitForSelector(`.fanr[data-cand="${unpriced.id}"]`);
    const rendered = await page.evaluate(id => {
      const row = document.querySelector(`.fanr[data-cand="${id}"]`);
      const model = window.decide.cands.find(c => c.id === id);
      const net = row.querySelectorAll('.fnum')[0];
      window.showMapCard(id);
      const card = document.querySelector('#decMapCard');
      return {
        hasOptionNetAlias: Object.prototype.hasOwnProperty.call(model, 'optionNet'),
        optionNetPremiumCents: model.price.optionNetPremiumCents,
        collect: window.candCollect(model),
        valuationBasis: model.price.valuationBasis,
        unavailableReason: model.price.unavailableReason,
        railNet: net.textContent.trim(),
        railNetClass: net.className,
        railNetTitle: net.getAttribute('title'),
        mapCardShown: card.classList.contains('show'),
        riskMapPoint: !!document.querySelector(`#decMap [data-mapi="${id}"]`)
      };
    }, unpriced.id);

    // The receipt itself is unambiguous before anything renders it.
    assert.equal(rendered.valuationBasis, 'UNAVAILABLE');
    assert.equal(rendered.hasOptionNetAlias, false,
      'the bridge must not publish a second package-price projection beside PackagePriceReceipt');
    assert.equal(rendered.optionNetPremiumCents, null,
      'the package receipt must carry an unpriced option net as null, not as a zero');
    assert.equal(rendered.collect, null,
      'the candidate rail must consume the nullable PackagePriceReceipt directly');
    assert.match(rendered.unavailableReason, /priced both legs/i);

    assert.doesNotMatch(rendered.railNet, /\$\s*0(?:[^\d]|$)/,
      `New Idea candidate rail: the net-premium cell of an UNAVAILABLE package renders "${rendered.railNet}". `
      + 'candCollect() returns null and signed(null) prints "+$0", so the rail states that a package '
      + 'the option book refused to price collects exactly zero premium (audit §5.2.1, program §3.2).');
    assert.match(rendered.railNet, /^(?:—|–|-|n\/a|unavailable|unpriced)$/i,
      `New Idea candidate rail: the net-premium cell of an UNAVAILABLE package must carry an explicit `
      + `unavailable marker; it renders "${rendered.railNet}".`);
    assert.doesNotMatch(rendered.railNetClass, /\bpos\b/,
      `New Idea candidate rail: the net-premium cell of an UNAVAILABLE package is styled "${rendered.railNetClass}". `
      + '`candCollect(c) >= 0` is true for null, so an unpriced package is coloured as a credit.');

    assert.equal(rendered.riskMapPoint, false,
      'a package without comparable chance and EV receipts has no invented risk-map coordinate');
    assert.equal(rendered.mapCardShown, false,
      'an absent comparison point cannot open a fabricated risk-map receipt');

    assert.deepEqual(pageErrors, [], `unpriced candidate rendering emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('payoff renderer draws the server polyline and only in-domain backend scenario receipts', async () => {
  /* The chart may interpolate supplied points only to draw geometry. Scenario labels and amounts
     come from the backend scenario receipt, and an out-of-domain story cannot abort the chart. */
  const { context, page, pageErrors } = await openAuthoritativeDesk();
  try {
    const drawn = await page.evaluate(id => {
      const c = window.decide.cands.find(row => row.id === id);
      const svg = document.querySelector('#decPay');
      const priceAt = pct => c.spot * (1 + pct / 100);
      const scenarioMoves = c.evaluation.risk.scenarios.map(row =>
        Number(row.underlyingMovePct) * 100);
      // Empty the canvas first so what is counted is the output of exactly ONE drawPayoff call.
      // Without this, an aborted render leaves the previous frame on screen and reads as success.
      svg.innerHTML = '';
      window.drawPayoff(svg, c, true, null, { interactive: false, pinM: scenarioMoves[0] });
      return {
        pinnedMove: scenarioMoves[0],
        servedDomain: [c.payoffPoints[0].price, c.payoffPoints[c.payoffPoints.length - 1].price],
        crashValue: window.__testNearestPayoffPoint(c, priceAt(scenarioMoves[0]))?.profit ?? null,
        pricedValues: scenarioMoves.slice(1, 6)
          .map(move => window.__testNearestPayoffPoint(c, priceAt(move))?.profit ?? null),
        scenarioMarkers: Array.from(svg.querySelectorAll('circle.pfscen')).map(el => el.dataset.si),
        atSpotAnnotation: svg.querySelector('text.pfxp')?.textContent || null,
        pathCount: svg.querySelectorAll('path[d]').length
      };
    }, CANDIDATE_ID);

    // The premise: exactly one story move is off the receipt and five others are on it.
    assert.equal(drawn.pinnedMove, -20);
    assert.deepEqual(drawn.servedDomain, [90, 110]);
    assert.equal(drawn.crashValue, null, 'the −20% crash is genuinely off the served payoff curve');
    assert.ok(drawn.pricedValues.every(v => v != null),
      `the other five story moves are priced by the receipt (${JSON.stringify(drawn.pricedValues)})`);

    assert.deepEqual(drawn.scenarioMarkers, ['1', '2', '3', '4', '5'],
      'New Idea payoff hero: one unpriced scenario (−20%, off the served curve) removed the markers for the '
      + `five scenarios the engine DID price — drew [${drawn.scenarioMarkers.join(',')}] instead of [1,2,3,4,5]. `
      + 'the chart must place only the exact backend scenario receipts whose prices fit the supplied payoff domain.');
    assert.ok(drawn.pathCount > 0,
      'New Idea payoff hero: one unpriced scenario left the chart completely empty — the abort happens before '
      + `svg.innerHTML = g, so the curve, the axes and the shaded areas are never written (${drawn.pathCount} paths drawn).`);
    assert.ok(drawn.atSpotAnnotation && /\$/.test(drawn.atSpotAnnotation),
      'New Idea payoff hero: one unpriced scenario deleted the at-spot value annotation, which is drawn '
      + `after the scenario loop (rendered ${JSON.stringify(drawn.atSpotAnnotation)}).`);

    assert.deepEqual(pageErrors, [], `payoff scenario skip emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('the order receipt renders its epoch-millisecond observation as a human timestamp', async () => {
  /* PackagePriceReceipt.observedAt is `Long` — epoch ms of the quotes the price was struck from.
     packagePriceReceiptHTML formats it with
       String(price.observedAt).replace('T',' ').replace(/\..*$/,'')
     which is ISO-string surgery. Neither replacement matches a number, so the dock prints the raw
     count of milliseconds. The owner's capture of the served order controls shows 1784913960000
     sitting in the receipt's provenance line beside the source and freshness (audit §5.2.3). */
  const { context, page, pageErrors } = await openAuthoritativeDesk();
  try {
    await page.locator('.execute [data-dec="ticket"]').click();
    await page.waitForSelector('.execute .pricereceipt .prmeta');
    const receipt = await page.evaluate(() => {
      const meta = document.querySelector('.execute .pricereceipt .prmeta');
      const wire = window.decide.orderPreview.preview.price;
      return {
        wireObservedAt: wire.observedAt,
        wireType: typeof wire.observedAt,
        feeCell: Array.from(document.querySelectorAll('.execute .pricereceipt .prrow'))
          .find(row => /fees/i.test(row.querySelector('.prk')?.textContent || ''))
          ?.querySelector('.prv')?.textContent.trim(),
        subDollarFee: window.moneyCents(35, 'fee'),
        zeroFee: window.moneyCents(0, 'fee'),
        // Cell by cell: the provenance row concatenates its spans without separators, so the raw
        // epoch is invisible to a whole-row regex — exactly the kind of blind spot this lane exists
        // to remove.
        metaSpans: Array.from(meta.querySelectorAll('span')).map(el => el.textContent.trim())
      };
    });

    assert.equal(receipt.wireType, 'number',
      'the fixture must carry observedAt in the declared wire unit (epoch ms), as the record does');
    assert.equal(receipt.wireObservedAt, RECEIPT_OBSERVED_AT_EPOCH_MS);
    assert.equal(receipt.feeCell, '−$2.60',
      'the rendered opening fee preserves the receipt cents instead of rounding to whole dollars');
    assert.equal(receipt.subDollarFee, '−$0.35',
      'a sub-dollar exact fee remains visible rather than becoming $0');
    assert.equal(receipt.zeroFee, '$0.00',
      'an exact zero fee is stated without a misleading negative sign');

    const rawEpochCell = receipt.metaSpans.find(text => /^\d{10,}$/.test(text));
    assert.equal(rawEpochCell, undefined,
      `New Idea order dock, package-price receipt: the observation stamp renders as the bare number `
      + `"${rawEpochCell}" beside the source and freshness. observedAt is a Long of epoch milliseconds and `
      + 'the renderer applies ISO-string replacements that a number never matches, so the reader is shown '
      + `a millisecond count instead of a time (audit §5.2.3). Full row: ${JSON.stringify(receipt.metaSpans)}.`);
    /* A readable date, not a specific one. The first draft of this assertion required the literal
       "2026", which pinned a format rather than the property: authWhen — the desk's one time
       renderer — prints "Jul 24, 10:26 AM" for a recent stamp and would have failed while being
       entirely correct. Requiring a month name and a clock time keeps the real guarantee (an
       epoch is formatted from its declared unit) without dictating one locale's output. */
    assert.ok(receipt.metaSpans.some(text =>
      /(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)/i.test(text) && /\d{1,2}:\d{2}/.test(text)),
      `New Idea order dock, package-price receipt: no rendered provenance cell names a readable date; the row `
      + `reads ${JSON.stringify(receipt.metaSpans)}. An epoch stamp must be formatted from its declared unit.`);

    assert.deepEqual(pageErrors, [], `order receipt stamp emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('financial receipts preserve exact integer cents under one semantic money grammar', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  await installBackend(page, { bookDocuments: emptyBookDocuments() });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    const rendered = await page.evaluate(() => {
      const values = [-50, -49, -47, -1, 0, 1, 47, 49, 50];
      return {
        pnl: values.map(value => window.moneyPnlCents(value)),
        cashFlow: values.map(value => window.moneyCashFlowCents(value)),
        loss: values.map(value => window.moneyLossCents(value)),
        price: [0, 1, 47, 50].map(value => window.moneyPriceCents(value)),
        fee: [-47, 0, 47].map(value => window.moneyFeeCents(value)),
        malformed: [
          window.moneyPnlCents(null),
          window.moneyPnlCents(1.5),
          window.moneyPnlCents('47'),
          window.moneyPnlCents(Number.MAX_SAFE_INTEGER + 1)
        ],
        wholeAndCents: window.moneyBalanceCents(105047),
        compactAxis: window.fmtK(1250)
      };
    });

    assert.deepEqual(rendered.pnl, [
      '−$0.50', '−$0.49', '−$0.47', '−$0.01', '$0',
      '+$0.01', '+$0.47', '+$0.49', '+$0.50'
    ]);
    assert.deepEqual(rendered.cashFlow, rendered.pnl,
      'signed P/L and signed cash flow share spelling, but remain named semantic call sites');
    assert.deepEqual(rendered.loss, [
      '$0.50', '$0.49', '$0.47', '$0.01', '$0',
      '$0.01', '$0.47', '$0.49', '$0.50'
    ], 'a Max loss label receives a magnitude, not a second negative sign convention');
    assert.deepEqual(rendered.price, ['$0.00', '$0.01', '$0.47', '$0.50']);
    assert.deepEqual(rendered.fee, ['−$0.47', '$0.00', '−$0.47']);
    assert.deepEqual(rendered.malformed, ['—', '—', '—', '—'],
      'malformed or absent cent facts remain unavailable instead of being rounded or coerced');
    assert.equal(rendered.wholeAndCents, '$1,050.47');
    assert.equal(rendered.compactAxis, '1.3k',
      'compact chart-axis notation remains a separate coordinate formatter');
    assert.deepEqual(pageErrors, [], `money grammar emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('P/L surfaces keep signed semantics and unavailable fan facts carry no success or loss tone', async () => {
  const { context, page, pageErrors } = await openAuthoritativeDesk();
  try {
    const rendered = await page.evaluate(candidateId => {
      const candidate = window.decide.cands.find(row => row.id === candidateId);

      const scenarios = document.createElement('div');
      scenarios.innerHTML = window.decScenSpectrum(candidate);

      const fan = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      fan.id = 'signed-pnl-contract-fan';
      document.body.appendChild(fan);
      window.renderPathFan(fan, {
        space: 'pnl',
        horizonDays: 1,
        pathProgress: [0, 1],
        paths: [[-10, 20]],
        frames: [
          { day: 0, p10: -10, p25: -5, p50: 0, p75: 5, p90: 10 },
          { day: 1, p10: 0, p25: 5, p50: 10, p75: 15, p90: 20 }
        ]
      }, { defaultWidth: 520, defaultHeight: 150 });

      const stats = document.createElement('div');
      stats.innerHTML = window.mcStat('Median', window.moneyPnlCents(1450), true)
        + window.mcStat('P10', window.moneyPnlCents(null), null)
        + window.mcStat('P5', window.moneyPnlCents(-8000), false);

      candidate.jumpTail = {
        available: true,
        base: {
          sector: 'Technology',
          gapDir: '−',
          gapPct: 9,
          gapLossCents: -12345,
          expectedShortfallCents: -4567,
          pop: 0.42,
          atMaxLoss: false,
          undefinedRisk: false
        }
      };
      candidate.evaluation.risk.tailLossCents = 12345;
      candidate.pop = null;
      const tail = document.createElement('div');
      tail.innerHTML = window.tailBlock(candidate);

      const missingStat = stats.querySelectorAll('.mcv')[1];
      const result = {
        scenarioValues: Array.from(scenarios.querySelectorAll('.sv')).map(node =>
          node.textContent.trim()),
        fanText: fan.textContent.replace(/\s+/g, ' ').trim(),
        fanTitles: Array.from(fan.querySelectorAll('title')).map(node => node.textContent),
        statValues: Array.from(stats.querySelectorAll('.mcv')).map(node => node.textContent.trim()),
        missingStatClass: missingStat.className,
        tailText: tail.textContent.replace(/\s+/g, ' ').trim()
      };
      fan.remove();
      return result;
    }, CANDIDATE_ID);

    assert.deepEqual(rendered.scenarioValues, [
      '−$123.45', '+$301', '+$452', '+$724', '+$777', '+$618', '+$405', '+$222'
    ], 'named scenario P/L checkpoints use the same signed grammar as every other P/L receipt');
    assert.ok(rendered.fanTitles.some(text => /ends \+\$20(?:\D|$)/.test(text)),
      `P/L fan path titles must name positive terminal P/L explicitly; got ${JSON.stringify(rendered.fanTitles)}`);
    assert.match(rendered.fanText, /\+\$/,
      `P/L fan axes must distinguish gains from unsigned balances; rendered "${rendered.fanText}"`);
    assert.match(rendered.fanText, /−\$/,
      `P/L fan axes must distinguish losses from unsigned balances; rendered "${rendered.fanText}"`);
    assert.deepEqual(rendered.statValues, ['+$14.50', '—', '−$80']);
    assert.equal(rendered.missingStatClass, 'mcv',
      'an unavailable P/L statistic is neither a gain nor a loss');
    assert.match(rendered.tailText, /gap → −\$123\.45/);
    assert.match(rendered.tailText, /worst-5% −\$45\.67/);
    assert.match(rendered.tailText, /declared tail loss \$123\.45/);
    assert.match(rendered.tailText, /market-implied odds are unavailable/i);
    assert.doesNotMatch(rendered.tailText, /null%|undefined%|\+\$0|−\$0/);
    assert.deepEqual(pageErrors, [], `signed P/L rendering emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Home cash truth preserves typed authority and negative buying power while bars clamp geometry only', async () => {
  const documents = emptyBookDocuments();
  Object.assign(documents.summary, {
    cashCents: 1234567,
    reservedCents: 2345678,
    buyingPowerCents: 3456789,
    liquidity: {
      schemaVersion: 'account-liquidity-v1',
      accountId: ACCOUNT_ID,
      lane: 'TRACKED',
      settlementBalance: {
        cents: -25000,
        authority: 'MODEL_DERIVED',
        basis: 'Cash reconstructed from recorded tracked-account transactions.'
      },
      pendingActivity: {
        authority: 'UNAVAILABLE',
        basis: 'No broker-reported pending-activity value is linked.'
      },
      recordedOrReportedReserve: {
        cents: 1800000,
        authority: 'BROKER_REPORTED',
        basis: 'Broker-reported reserve or buying-power encumbrance.'
      },
      theoreticalShortPutObligation: {
        cents: 2200000,
        authority: 'MODEL_DERIVED',
        basis: 'Gross assignment-at-strike obligation for unpaired recorded short puts.'
      },
      genuinelyFreeBuyingPower: {
        cents: -250000,
        authority: 'BROKER_REPORTED',
        basis: 'Broker-reported genuinely available buying power.'
      },
      concurrentCollateralIncome: {
        authority: 'UNAVAILABLE',
        basis: 'No broker-reported settlement-fund rate is linked.'
      },
      reconciliationDifference: {
        authority: 'UNAVAILABLE',
        basis: 'Reconciliation needs complete broker liquidity components.'
      },
      reconciliationStatus: 'UNAVAILABLE',
      reconciliationReason: 'Recorded lots cannot establish broker buying power.',
      sourceRefs: ['portfolio_transaction', 'portfolio_lot']
    }
  });
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  await installBackend(page, { bookDocuments: documents });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => ['ready', 'partial'].includes(
      window.DeskBackend?.state().book?.phase));
    await page.waitForSelector('.liquidityfigure [data-liquidity-fact="settlement"]');
    const rendered = await page.evaluate(() => {
      function fact(key) {
        const node = document.querySelector(
          `.liquiditylegend [data-liquidity-fact="${key}"]`)
          || document.querySelector(`.liquidityfigure [data-liquidity-fact="${key}"]`);
        return {
          text: node?.textContent.replace(/\s+/g, ' ').trim(),
          authority: node?.dataset.authority,
          basis: node?.dataset.basis,
          reason: node?.dataset.unavailableReason || null,
          title: node?.getAttribute('title')
        };
      }
      return {
        figureText: document.querySelector('.liquidityfigure').textContent
          .replace(/\s+/g, ' ').trim(),
        settlement: fact('settlement'),
        encumbered: fact('encumbered'),
        pending: fact('pending'),
        free: fact('free'),
        segments: Array.from(document.querySelectorAll('.liquiditybar > i')).map(node => ({
          key: node.dataset.liquidityFact,
          width: Number.parseFloat(node.style.width)
        }))
      };
    });

    assert.match(rendered.settlement.text, /−\$250/);
    assert.equal(rendered.settlement.authority, 'MODEL_DERIVED');
    assert.match(rendered.settlement.basis, /reconstructed from recorded/i);
    assert.match(rendered.encumbered.text, /Encumbered \$18,000/);
    assert.equal(rendered.encumbered.authority, 'BROKER_REPORTED');
    assert.match(rendered.pending.text, /Pending —/);
    assert.equal(rendered.pending.authority, 'UNAVAILABLE');
    assert.match(rendered.pending.reason, /No broker-reported pending-activity/i);
    assert.match(rendered.pending.title, /No broker-reported pending-activity/i);
    assert.match(rendered.free.text, /Free −\$2,500/);
    assert.equal(rendered.free.authority, 'BROKER_REPORTED');
    assert.doesNotMatch(rendered.figureText, /\$12,345\.67|\$23,456\.78|\$34,567\.89/,
      'raw summary balances cannot replace the canonical liquidity receipt');
    assert.deepEqual(rendered.segments.map(row => row.key), ['encumbered'],
      'negative and unavailable facts remain visible facts but never become positive bar segments');
    assert.ok(rendered.segments.every(row => row.width >= 0 && row.width <= 100),
      `bar geometry must remain clamped: ${JSON.stringify(rendered.segments)}`);
    assert.deepEqual(pageErrors, [], `typed liquidity rendering emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

/* §16.3's cross-surface identity check needs ONE package whose every displayed fact comes from a
   single set of server numbers. These constants ARE that receipt: populatedBookDocuments() already
   states them for the held AAPL line, and goldenCandidate() restates the identical package as a
   ranked New Idea candidate. Any string difference between the three surfaces is therefore a
   presentation difference — there is no second number anywhere for them to disagree about. */
const GOLDEN = {
  symbol: 'AAPL',
  candidateId: 'candidate_golden_receipt',
  expiration: '2026-08-21',
  maxLossCents: 43210,           // $432.10
  maxProfitCents: 156790,        // $1,567.90
  optionNetPremiumCents: -43210, // −$432.10 paid to open
  popEntryPct: 57,               // popEntry 0.57 — the package's own chance of profit
  popNowPct: 64,                 // popNow 0.64 — a DIFFERENT fact, measured today
  unrealizedPnlCents: 24680,     // $246.80 open P/L on the held line
  breakeven: 217.16,
  lastPrice: 222.22,
  changePct: -0.84
};

function goldenBookDocuments() {
  const documents = populatedBookDocuments();
  documents.bookRisk.practice.measuredBook = measuredJointBookReceipt();
  /* One golden QuoteView is the source for Home, New Idea and Position. */
  Object.assign(documents.research.quote, {
    displayPrice: GOLDEN.lastPrice, displayChangePct: GOLDEN.changePct,
    last: GOLDEN.lastPrice, bid: GOLDEN.lastPrice - 0.02, ask: GOLDEN.lastPrice + 0.02,
    prevClose: 224.10, asOf: 1784563200000
  });
  /* One golden greeks receipt too. The canvas's frame at t=0 IS the package as it stands, so the
     live mark and the first modelled frame must be the same four numbers — otherwise the walk
     would compare two different measurements and call their difference a divergence. */
  documents.tradeDetail.current.greeks = goldenGreeks();
  documents.greeks.positions[0].greeks = goldenGreeks();
  documents.chain.asOfEpochMs = 1784563200000;
  documents.chain.calls = documents.chain.calls.map(row => Object.assign({ iv: 0.30 }, row));
  documents.chain.puts = documents.chain.puts.map(row => Object.assign({ iv: 0.30 }, row));
  return documents;
}

function goldenCandidate() {
  const row = candidate();
  const held = populatedBookDocuments().activeTrades[0];
  row.id = GOLDEN.candidateId;
  row.symbol = GOLDEN.symbol;
  row.label = 'Golden AAPL call debit spread';
  row.displayName = 'Golden AAPL call debit spread';
  row.legs = JSON.parse(JSON.stringify(held.legs));
  row.price = priceReceipt({ optionNetPremiumCents: GOLDEN.optionNetPremiumCents,
    openingFeesCents: 260, executableNetCents: GOLDEN.optionNetPremiumCents,
    fingerprint: 'price-golden-receipt' });
  row.maxLossCents = GOLDEN.maxLossCents;
  row.maxProfitCents = GOLDEN.maxProfitCents;
  row.breakevens = [GOLDEN.breakeven];
  row.evaluation.capital.incrementalCents = GOLDEN.maxLossCents;
  row.marketImpliedRisk = goldenMarketImpliedRisk({
    priceFingerprint: row.price.fingerprint,
    pop: GOLDEN.popEntryPct / 100,
    expectedValueCents: -5200,
    underlyingCents: Math.round(GOLDEN.lastPrice * 100),
    cvar95Cents: -GOLDEN.maxLossCents,
    stressLossCents: -GOLDEN.maxLossCents
  });
  row.evaluation.risk.marketImpliedRisk = row.marketImpliedRisk;
  row.evaluation.risk.terminalPayoff.anchorSpotCents = Math.round(GOLDEN.lastPrice * 100);
  row.evaluation.risk.terminalPayoff.expiration = GOLDEN.expiration;
  row.evaluation.risk.terminalPayoff.points = [
    { price: 200, profitCents: -GOLDEN.maxLossCents },
    { price: GOLDEN.lastPrice, profitCents: GOLDEN.unrealizedPnlCents },
    { price: 230, profitCents: GOLDEN.maxProfitCents }
  ];
  return row;
}

/* The amount inside a rendered money string, sign discarded. Two surfaces agreeing here while
   disagreeing on the exact string means one receipt with two grammars — a presentation split, not
   a wrong number. Keeping the two questions separate is what lets the failure say which it is. */
function moneyMagnitude(text) {
  const digits = String(text == null ? '' : text).replace(/[^\d.]/g, '');
  return digits === '' ? null : Number(digits);
}

test('Home, New Idea, and Position render one golden receipt identically', async () => {
  const context = await browser.newContext({ viewport: { width: 2560, height: 1440 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  await installBackend(page, {
    bookDocuments: goldenBookDocuments(),
    universeSymbols: [GOLDEN.symbol],
    scoutSymbols: [GOLDEN.symbol],
    strategyCandidates: [goldenCandidate()],
    ideaSymbol: GOLDEN.symbol,
    /* The ambient quote and the Book research quote are the SAME observation — one receipt is the
       premise of this test. The default fixture quote is an AMD one, and leaving it in place makes
       the ensemble's anchor provenance disagree with the surface's quote provenance. */
    quote: {
      symbol: GOLDEN.symbol, bid: GOLDEN.lastPrice - 0.02, ask: GOLDEN.lastPrice + 0.02,
      last: GOLDEN.lastPrice, prevClose: 224.10, changePct: GOLDEN.changePct,
      source: 'BOOK_TEST_RESEARCH_RECEIPT', freshness: 'FRESH', asOf: 1784563200000,
      evidence: { source: 'BOOK_TEST_RESEARCH_RECEIPT', lane: 'OBSERVED', provenance: 'OBSERVED' }
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.waitForSelector(`#book .card[data-id="${BOOK_TRADE_ID}"]`);
    await page.waitForSelector('.bookonefacts');
    await page.waitForFunction(symbol => Array.from(document.querySelectorAll('.authmarketrow'))
      .some(row => row.dataset.authMarketRowSymbol === symbol), GOLDEN.symbol);

    const home = await page.evaluate(tradeId => {
      const facts = {};
      document.querySelectorAll('.bookonefacts .authmetric').forEach(cell => {
        facts[cell.querySelector('span').textContent.trim()] = cell.querySelector('b').textContent.trim();
      });
      const card = document.querySelector(`#book .card[data-id="${tradeId}"]`);
      const marketRow = document.querySelector('.authmarketrow.on .authmarketfocus')
        || document.querySelector('.authmarketrow .authmarketfocus');
      return {
        pnl: card.querySelector('.cpnl').textContent.trim(),
        maxLoss: facts['Max loss'],
        pop: facts.Chance,
        expiry: facts['Final expiry'],
        price: marketRow.querySelector('em').textContent.trim(),
        // The watch row prefixes its sector group in a <small>; the change is the rest of the cell.
        change: marketRow.querySelector('span').lastChild.textContent.trim()
      };
    }, BOOK_TRADE_ID);

    await page.locator(`#authBookFanLegend [data-fan-pos="${BOOK_TRADE_ID}"]`).click();
    await page.waitForFunction(tradeId => window.state?.level === 'position'
      && window.state.focus === tradeId
      && window.DeskBackend.state().position?.phase === 'ready', BOOK_TRADE_ID);
    await page.waitForSelector('[data-auth-position-detail] .authpaykey');

    const position = await page.evaluate(() => {
      const view = document.querySelector('[data-auth-position-detail]');
      const pay = {};
      view.querySelectorAll('.authpaykey > span').forEach(cell => {
        pay[cell.childNodes[0].textContent.trim()] = cell.querySelector('b').textContent.trim();
      });
      const metrics = {};
      view.querySelectorAll('.authmetric').forEach(cell => {
        metrics[cell.querySelector('span').textContent.trim()] = cell.querySelector('b').textContent.trim();
      });
      const rows = {};
      view.querySelectorAll('.authlistrow').forEach(row => {
        rows[row.querySelector('b').textContent.trim()] = row.querySelector('span').textContent.trim();
      });
      return {
        pnl: pay['Open P/L'],
        maxLoss: pay['Max loss'],
        maxProfit: pay['Max profit'],
        price: metrics.Last,
        popNow: metrics['POP now'],
        breakeven: pay['Break-even'],
        expiry: rows.Expiry,
        metricLabels: Object.keys(metrics),
        payLabels: Object.keys(pay),
        greeks: Array.from(view.querySelectorAll('.mechg .mgt')).map(tile => ({
          key: tile.querySelector('.mgk').textContent.trim(),
          value: tile.querySelector('.mgv').textContent.trim()
        }))
      };
    });

    if (process.env.DESK_SHOTS) {
      await page.waitForTimeout(700);   // let the bloom FLIP settle before the evidence shot
      await page.screenshot({ path: 'shots/golden-position.png' });
    }

    await startNewIdea(page, GOLDEN.symbol);
    try {
      await page.waitForFunction(id => window.decide?.backendPhase === 'ready'
        && window.decide.candId === id && window.decide.orderPreview, GOLDEN.candidateId);
    } catch (error) {
      /* The idea lane refuses an outcome fan whose anchor provenance disagrees with the surface's
         quote, so name both when this walk cannot reach analysis. */
      const diagnosis = await page.evaluate(() => {
        const state = window.DeskBackend.state();
        return {
          phase: window.decide && window.decide.backendPhase,
          candId: window.decide && window.decide.candId,
          backendError: window.decide && window.decide.backendError,
          planSymbol: state.plan && state.plan.symbol,
          quote: state.market && state.market.quote,
          ensembleAnchor: state.ensemble && state.ensemble.preview.receipt
        };
      });
      throw new Error(`${error.message}\nNew Idea diagnosis: ${JSON.stringify(diagnosis)}`
        + `\nPage errors: ${pageErrors.join('\n')}`);
    }

    // The greeks live behind New Idea's mechanics lens; Position shows them in its own pane. Open
    // it before capturing, so this walk compares what a person can actually reach on both.
    await page.locator('#decideStage [data-inspect="mechanics"]').click();
    await page.waitForTimeout(200);
    await page.waitForSelector('#decideStage .mechg .mgt');

    const newIdea = await page.evaluate(id => {
      const kpis = {};
      document.querySelectorAll('.dccpay .kgrid .k').forEach(cell => {
        kpis[cell.querySelector('.lbl').textContent.trim()] = cell.querySelector('.v').textContent.trim();
      });
      // The calm hero keeps max profit, capital and break-even in a secondary row beside the grid.
      const secondary = {};
      document.querySelectorAll('.dccpay .ksecondary > span').forEach(cell => {
        const value = cell.querySelector('b').textContent.trim();
        secondary[cell.textContent.replace(value, '').trim()] = value;
      });
      const row = document.querySelector(`.fanr[data-cand="${id}"]`);
      const cells = row.querySelectorAll('.fnum');
      return {
        heroMaxLoss: kpis['Max loss'],
        heroMaxProfit: secondary['Max profit'],
        heroBreakeven: secondary['Break-even'],
        heroPop: kpis.Chance,
        heroNet: kpis['Net debit'] || kpis['Net credit'] || kpis['Net premium'],
        railMaxLoss: cells[1].textContent.trim(),
        railPop: cells[2].textContent.trim(),
        price: document.querySelector('.authmarkethero .amh-price').textContent.trim(),
        change: document.querySelector('.authmarkethero .amh-change').textContent.trim(),
        expiryHint: document.querySelector('.dccpay .paytitle .hint').textContent.trim(),
        kpiLabels: Object.keys(kpis).concat(Object.keys(secondary)),
        greeks: Array.from(document.querySelectorAll('#decideStage .mechg .mgt')).map(tile => ({
          key: tile.querySelector('.mgk').textContent.trim(),
          value: tile.querySelector('.mgv').textContent.trim()
        })),
        text: document.querySelector('#decideStage').textContent.replace(/\s+/g, ' ').trim()
      };
    }, GOLDEN.candidateId);

    const surfaces = JSON.stringify({ home, position,
      newIdea: Object.assign({}, newIdea, { text: undefined }) }, null, 1);
    const newIdeaExpiry = (newIdea.expiryHint.match(/\d{4}-\d{2}-\d{2}/) || [])[0];

    /* ---- 1. one authority: every surface received the SAME amount ----------------------
       These must hold before any string comparison means anything. If a magnitude disagrees the
       defect is a second source of truth (§3.1); if only the strings disagree the defect is a
       second display grammar for one number, which is what §16.3 is asking about. */
    assert.deepEqual(
      [moneyMagnitude(home.maxLoss), moneyMagnitude(newIdea.heroMaxLoss), moneyMagnitude(position.maxLoss)],
      [GOLDEN.maxLossCents / 100, GOLDEN.maxLossCents / 100, GOLDEN.maxLossCents / 100],
      `max loss reached the three surfaces as different amounts. ${surfaces}`);
    assert.deepEqual(
      [moneyMagnitude(home.pop), moneyMagnitude(newIdea.heroPop), moneyMagnitude(newIdea.railPop)],
      [GOLDEN.popEntryPct, GOLDEN.popEntryPct, GOLDEN.popEntryPct],
      `the package's entry chance reached the surfaces as different numbers. ${surfaces}`);
    assert.deepEqual([moneyMagnitude(home.pnl), moneyMagnitude(position.pnl)],
      [GOLDEN.unrealizedPnlCents / 100, GOLDEN.unrealizedPnlCents / 100],
      `open P/L reached Home and Position as different amounts. ${surfaces}`);
    assert.deepEqual(
      [moneyMagnitude(home.price), moneyMagnitude(newIdea.price), moneyMagnitude(position.price)],
      [GOLDEN.lastPrice, GOLDEN.lastPrice, GOLDEN.lastPrice],
      `the underlying price reached the surfaces as different amounts. ${surfaces}`);

    /* ---- 2. facts a surface genuinely does not carry — stated, never skipped ------------ */
    /* Position shows today's chance, a DIFFERENT measurement from the package's entry chance. */
    assert.equal(moneyMagnitude(position.popNow), GOLDEN.popNowPct,
      `Position states today's chance (${GOLDEN.popNowPct}%), not the package's entry chance; it rendered `
      + `"${position.popNow}".`);
    assert.ok(!position.metricLabels.includes('Chance'),
      'Position must not restate the entry chance beside today\'s, but its metrics are '
      + `${JSON.stringify(position.metricLabels)}.`);
    /* New Idea analyses a package nobody holds, so a mark-to-market P/L there would be invented. */
    assert.ok(!newIdea.kpiLabels.includes('Now') && !/\bOpen P\/L\b/.test(newIdea.text),
      `New Idea must not show an open P/L for an unheld package; its hero cells are ${JSON.stringify(newIdea.kpiLabels)}.`);
    /* The Position research panel carries no day change at all. */
    assert.ok(!position.metricLabels.some(label => /change/i.test(label)),
      `Position research metrics are ${JSON.stringify(position.metricLabels)}; a day change appearing here `
      + 'must come from the same served receipt, never a derived one.');
    /* Home and New Idea both carry it, and both must print the served percentage. */
    const servedChange = `${GOLDEN.changePct.toFixed(2)}% vs close`;
    assert.ok(home.change.includes(servedChange),
      `Home must render the served day change "${servedChange}"; it rendered "${home.change}".`);
    assert.ok(newIdea.change.includes(servedChange),
      `New Idea must render the served day change "${servedChange}"; it rendered "${newIdea.change}".`);

    /* ---- 3. one receipt, one rendered string --------------------------------------------
       Collected rather than asserted one at a time: a reader fixing this needs the whole list of
       surfaces that disagree, not whichever comparison happens to be written first. */
    const divergences = [];
    function sameString(fact, cells, why) {
      const distinct = Array.from(new Set(cells.map(cell => cell.text)));
      if (distinct.length <= 1) return;
      divergences.push(`${fact}: ` + cells.map(cell => `${cell.surface} "${cell.text}"`).join(', ')
        + `. ${why}`);
    }
    sameString('Max loss',
      [{ surface: 'Home single-position receipt', text: home.maxLoss },
        { surface: 'New Idea hero', text: newIdea.heroMaxLoss },
        { surface: 'New Idea rail', text: newIdea.railMaxLoss },
        { surface: 'Position payoff rail', text: position.maxLoss }],
      'maxLossDisp() prefixes a minus sign on the New Idea hero and rail while authMoney() prints the same '
      + 'magnitude unsigned on Home and Position, so a reader comparing an idea to the position it becomes '
      + 'is shown the one loss cap two ways.');
    sameString('Chance of profit',
      [{ surface: 'Home single-position receipt', text: home.pop },
        { surface: 'New Idea hero', text: newIdea.heroPop },
        { surface: 'New Idea rail', text: newIdea.railPop }],
      'the New Idea hero renders its unit inside a <small> element with a leading space, so one percentage '
      + 'is two strings on adjacent surfaces.');
    sameString('Open P/L',
      [{ surface: 'Home roster card', text: home.pnl },
        { surface: 'Position payoff rail', text: position.pnl }],
      'the roster card uses authMoney() and the Position payoff rail uses authSigned(), so the same open '
      + 'profit is signed on one surface and unsigned on the other.');
    sameString('Underlying price',
      [{ surface: 'Home market band', text: home.price },
        { surface: 'New Idea market hero', text: newIdea.price },
        { surface: 'Position research panel', text: position.price }],
      'Home formats the served price with toLocaleString and the other two with toFixed(2).');
    sameString('Max profit',
      [{ surface: 'New Idea hero', text: newIdea.heroMaxProfit },
        { surface: 'Position payoff rail', text: position.maxProfit }],
      'maxProfitDisp() signs the New Idea figure and authMoney() does not sign the Position one.');
    sameString('Break-even',
      [{ surface: 'New Idea hero', text: newIdea.heroBreakeven },
        { surface: 'Position payoff rail', text: position.breakeven }],
      'beLabel() rounds the served break-even to whole dollars and drops the currency mark, so New Idea '
      + 'states a materially coarser price than the Position built from the same receipt.');
    /* Greeks were the one fact in M7's acceptance list this walk never compared, so the four
       numbers that describe how a package MOVES could disagree between the surface that proposes
       it and the surface that holds it, and nothing would have said so. Both render through the
       same mechGraphic + GSPEC, which is precisely why a divergence here means a second source of
       greeks has appeared upstream. */
    assert.ok(newIdea.greeks.length >= 4,
      `New Idea must show the package's greeks; it showed ${JSON.stringify(newIdea.greeks)}.`);
    assert.ok(position.greeks.length >= 4,
      `Position must show the held package's greeks; it showed ${JSON.stringify(position.greeks)}.`);
    assert.deepEqual(newIdea.greeks.map(tile => tile.key), position.greeks.map(tile => tile.key),
      'New Idea and Position must name the greeks identically — they share one GSPEC.');
    newIdea.greeks.forEach((tile, index) => {
      sameString(`Greek ${tile.key}`,
        [{ surface: 'New Idea mechanics', text: tile.value },
          { surface: 'Position mechanics', text: position.greeks[index].value }],
        'one package moves one way; the proposing surface and the holding surface read the same '
        + 'greeks receipt.');
    });

    sameString('Expiry',
      [{ surface: 'Home single-position receipt', text: home.expiry },
        { surface: 'New Idea payoff hint', text: newIdeaExpiry },
        { surface: 'Position management rows', text: position.expiry }],
      'the package expires once.');

    assert.deepEqual(divergences, [],
      `ONE golden package receipt renders differently across Home, New Idea and Position (audit §16.3). `
      + `Every amount above agrees, so each entry below is one fact with two display grammars:\n  - `
      + `${divergences.join('\n  - ')}\nCaptured surfaces: ${surfaces}`);

    assert.deepEqual(pageErrors, [], `golden-receipt walk emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Import walks the backend broker journey without leaving the desk or pricing anything itself', async () => {
  const context = await browser.newContext({ viewport: { width: 2560, height: 1440 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { bookDocuments: populatedBookDocuments() });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');

    // The declaration the user already made. Import used to reload the page, which is precisely
    // what threw this away (audit §5.6).
    await page.evaluate(() => {
      window.WORKSPACE.goal = 'INCOME';
      window.WORKSPACE.view = 'Neutral';
      window.WORKSPACE.horizonDays = 45;
      window.WORKSPACE.riskPosture = 'Balanced';
    });
    const navigations = [];
    page.on('framenavigated', frame => { if (frame === page.mainFrame()) navigations.push(frame.url()); });

    await page.locator('#importBtn').click();
    await page.waitForSelector('#importStage .impgrid');
    assert.deepEqual(navigations, [], 'opening Import never navigates the page');

    await page.locator('[data-imp-broker="ETRADE"]').click();
    await page.locator('[data-imp-text]').fill('Symbol,Action,Qty\nAMD,SELL,2\n');
    await page.locator('[data-imp-act="read"]').click();
    await page.waitForSelector('.impgroup');

    const sent = backend.brokerImportPreviews();
    assert.equal(sent.length, 1, 'one preview request carries the pasted text verbatim');
    assert.equal(sent[0].parserVersion, 'broker-import-1');
    assert.equal(sent[0].sourceSystem, 'ETRADE');
    assert.ok(sent[0].text.includes('AMD,SELL,2'), 'the browser sends the rows unparsed');

    assert.deepEqual(await page.evaluate(() => Array.from(document.querySelectorAll('.ilhead'))
      .map(head => Array.from(head.children).map(cell => cell.textContent.trim()).filter(Boolean))),
      [['qty', 'contract', 'as filled', 'mark now'], ['qty', 'contract', 'as filled', 'mark now']],
      'the two price columns are named, so a filled price is never read as a current mark');

    const groups = await page.evaluate(() => Array.from(document.querySelectorAll('.impgroup'))
      .map(group => ({
        selected: group.classList.contains('on'),
        badge: group.querySelector('.badge').textContent.trim(),
        net: group.querySelector('.ignet').textContent.trim(),
        legs: Array.from(group.querySelectorAll('.ileg:not(.ilhead)')).map(leg => ({
          action: leg.querySelector('.ilact').textContent.trim(),
          symbol: leg.querySelector('.ilsym').textContent.trim(),
          contract: leg.querySelector('.ilcon').textContent.trim(),
          price: leg.querySelector('.ilpx').textContent.trim(),
          mark: leg.querySelector('.ilmark').textContent.trim(),
          inferred: !!leg.querySelector('.badge')
        })),
        hint: (group.querySelector('.ighint') || {}).textContent || null
      })));
    assert.equal(groups.length, 2);
    assert.equal(groups[0].selected, true, 'an exact-fill package starts selected — it is complete');
    assert.equal(groups[0].badge, 'exact fills');
    assert.equal(groups[0].net, '+$2.14',
      'the package net keeps the cents the statement reported — rounding it to +$2 would discard a stated fact');
    assert.deepEqual(groups[0].legs, [
      { action: 'SELL', symbol: 'AMD', contract: '2026-08-21 · 160.00 P', price: '$3.15',
        mark: '$3.15', inferred: false },
      { action: 'BUY', symbol: 'AMD', contract: '2026-08-21 · 150.00 P', price: '$1.01',
        mark: '—', inferred: true }
    ], 'every leg field is the parser\'s; a leg with no separately sourced mark shows an em dash, not a price');
    assert.equal(groups[1].selected, false,
      'a package-net-only group is opt-in, because confirming it quarantines rather than posts it');
    assert.equal(groups[1].badge, 'package net only');
    assert.equal(groups[1].legs[0].price, '—', 'no per-leg price is invented for a package-net row');
    assert.ok(groups[1].hint.includes('pending import'), 'and the surface says what will happen to it');

    const quarantined = await page.evaluate(() => Array.from(document.querySelectorAll('.iqrow'))
      .map(row => row.querySelector('.iqwhy').textContent.trim()));
    assert.deepEqual(quarantined, ['the expiration column was empty'],
      'a refused row is shown with the reason it was refused (§3.2), never dropped');

    // One destination per source account, chosen once — not one picker per package.
    assert.deepEqual(await page.evaluate(() => Array.from(document.querySelectorAll('.igdest'))
      .map(row => ({ label: row.querySelector('.lbl').textContent.trim(),
        fingerprint: row.querySelector('select').getAttribute('data-imp-dest'),
        chosen: row.querySelector('select').value }))),
      [{ label: '2 packages from E*TRADE', fingerprint: 'fp-taxable', chosen: 'acct-taxable' }],
      'both packages came from one brokerage account, so one control owns where they land');
    assert.equal(await page.locator('#decideStage .impdests').count(), 0,
      'the import destination block belongs to Import, not to the order ticket');
    if (process.env.DESK_SHOTS) await page.screenshot({ path: 'shots/import-preview.png' });

    await page.locator('[data-imp-act="confirm"]').click();
    await page.waitForSelector('[data-imp-act="book"]');
    if (process.env.DESK_SHOTS) await page.screenshot({ path: 'shots/import-result.png' });
    const confirms = backend.brokerImportConfirms();
    assert.equal(confirms.length, 1);
    assert.equal(confirms[0].previewFingerprint, 'preview-fp-1',
      'confirmation names the exact preview it was reviewing');
    assert.deepEqual(confirms[0].groups.map(group => group.groupKey), ['grp-exact'],
      'only the selected package is confirmed');
    assert.deepEqual(confirms[0].groups[0].legs.map(leg => leg.acknowledgeInferred), [false, true],
      'the leg the parser inferred is acknowledged; the fully-stated leg is not');
    assert.equal(confirms[0].groups[0].legs[0].strike, '160.00',
      'the confirmed leg echoes the parsed strike exactly, with no browser re-derivation');

    const result = await page.evaluate(() => Array.from(document.querySelectorAll('.kgrid .k'))
      .map(cell => cell.querySelector('.lbl').textContent.trim() + ': ' + cell.querySelector('.v').textContent.trim()));
    assert.deepEqual(result, ['Written to the ledger: 1', 'Held pending prices: 0', 'Already imported: 0']);

    assert.deepEqual(navigations, [], 'the whole journey stays on one page');
    assert.deepEqual(await page.evaluate(() => ({
      goal: window.WORKSPACE.goal, view: window.WORKSPACE.view,
      horizon: workspaceHorizonLabel(window.WORKSPACE.horizonDays),
      riskPosture: window.WORKSPACE.riskPosture
    })), { goal: 'INCOME', view: 'Neutral', horizon: '45 trading days', riskPosture: 'Balanced' },
      'and the declaration the user made before importing survives it');

    assert.deepEqual(pageErrors, [], `Import journey emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('the workspace context is restored at boot and patched — never replaced — as it changes', async () => {
  const context = await browser.newContext({ viewport: { width: 2560, height: 1440 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceRev: 7,
    workspaceContext: {
      scopeType: 'SECTOR', sectorKey: 'semiconductors', goal: 'INCOME', view: 'Neutral',
      horizonDays: 45, riskPosture: 'Balanced', focusedSymbol: 'MU'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.WORKSPACE.rev === 7);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');

    assert.deepEqual(await page.evaluate(() => ({
      scope: window.HOME_SCOUT.scope, sector: window.HOME_SCOUT.sector,
      goal: window.HOME_SCOUT.goal, view: window.homeIdea.view,
      horizon: window.homeIdea.horizon, risk: window.homeIdea.riskMode,
      symbol: window.homeIdea.symbol
    })), {
      scope: 'sector', sector: 'semiconductors', goal: 'INCOME', view: 'Neutral',
      horizon: '45 trading days', risk: 'Balanced', symbol: 'MU'
    }, 'the desk opens holding what the user declared last time, in the words the desk uses');

    // Change ONE declaration. The patch must name only what moved.
    const beforePatch = await page.evaluate(() => {
      window.__ordinaryWorkspaceTransitions = [];
      document.addEventListener('strikebench:desk-backend', event => {
        if (event.detail.phase === 'world-transition') {
          window.__ordinaryWorkspaceTransitions.push(event.detail);
        }
      });
      return {
        book: window.DeskBackend.state().book,
        generation: window.WORKSPACE.generation
      };
    });
    await page.evaluate(() => { window.homeIdea.view = 'Bullish'; window.workspaceSave(['view']); });
    await page.waitForFunction(() => window.WORKSPACE.rev === 8);
    const patches = backend.workspacePatches();
    assert.equal(patches.length, 1);
    assert.deepEqual(Object.keys(patches[0]).sort(),
      ['clear', 'expectedAccountId', 'expectedDatasetId', 'expectedGeneration',
        'expectedMarketLane', 'expectedRev', 'version', 'view', 'world'],
      'a declaration change sends that declaration and its guards — nothing else');
    assert.deepEqual(patches[0].clear, ['returnFocus'],
      'an absent return focus is expressed through the server clear grammar, never a no-op null');
    assert.equal(patches[0].view, 'Bullish');
    assert.equal(patches[0].expectedRev, 7, 'the write is guarded by the revision this desk read');
    assert.equal(patches[0].expectedDatasetId, DATASET_ID);
    assert.equal(patches[0].expectedMarketLane, 'OBSERVED');
    assert.equal(patches[0].expectedAccountId, 'acct-1');
    assert.equal(patches[0].expectedGeneration, 1,
      'every write also guards the complete market/account identity that owns this context');
    assert.equal(await page.evaluate(() => window.__ordinaryWorkspaceTransitions.length), 0,
      'an ordinary declaration is not a market transition');
    assert.equal(await page.evaluate(() => window.DeskBackend.state().book?.phase), 'ready',
      'an ordinary declaration must retain the already-authoritative Book receipt');
    const advancedGeneration = await page.evaluate(async () => {
      const prior = window.DeskBackend.state().workspace.receipt;
      const moved = JSON.parse(JSON.stringify(prior));
      moved.rev = Number(prior.rev) + 1;
      moved.context.generation = Number(prior.context.generation || 0) + 1;
      await window.DeskBackend.receiveWorkspaceEvent('world.selected', {
        workspace: moved
      });
      return {
        generation: window.WORKSPACE.generation,
        transitions: window.__ordinaryWorkspaceTransitions.length,
        bookPhase: window.DeskBackend.state().book?.phase
      };
    });
    assert.deepEqual(advancedGeneration, {
      generation: Number(beforePatch.generation) + 1,
      transitions: 0,
      bookPhase: 'ready'
    }, 'context generation is concurrency metadata; by itself it cannot invalidate market artifacts');

    assert.deepEqual(await page.evaluate(() => ({
      goal: window.WORKSPACE.goal, horizonDays: window.WORKSPACE.horizonDays,
      riskPosture: window.WORKSPACE.riskPosture, sectorKey: window.WORKSPACE.sectorKey
    })), { goal: 'INCOME', horizonDays: 45, riskPosture: 'Balanced', sectorKey: 'semiconductors' },
      'and every declaration the patch did not name is still there — omission never un-declares');

    assert.deepEqual(pageErrors, [], `workspace restore emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('workspace hydration is the boot barrier and the UI holds the bridge canonical object', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    workspaceDelayMs: 350,
    workspaceContext: {
      scopeType: 'SECTOR', sectorKey: 'healthcare', goal: 'INCOME',
      view: 'NEUTRAL', horizonDays: 30, riskPosture: 'BALANCED'
    },
    bookDocuments: populatedBookDocuments()
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().workspace?.phase === 'loading');
    await new Promise(resolve => setTimeout(resolve, 100));
    assert.equal(backend.count('GET', '/api/workspace'), 1);
    assert.equal(backend.count('GET', '/api/portfolio/book'), 0,
      'Book cannot begin before the persisted workspace is adopted');
    assert.equal(await page.evaluate(() => window.DeskBackend.state().book), null,
      'no Book receipt is composed against the pre-hydration context');

    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    assert.equal(backend.count('GET', '/api/portfolio/book'), 1);
    assert.equal(await page.evaluate(() =>
      window.WORKSPACE === window.DeskBackend.workspaceContext()), true,
      'the UI and bridge retain one exact workspace object, not synchronized copies');
    assert.deepEqual(await page.evaluate(() => ({
      scope: window.WORKSPACE.scopeType, sector: window.WORKSPACE.sectorKey,
      goal: window.WORKSPACE.goal, horizon: window.WORKSPACE.horizonDays
    })), { scope: 'SECTOR', sector: 'healthcare', goal: 'INCOME', horizon: 30 });
    assert.deepEqual(pageErrors, [], `workspace boot barrier emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a workspace restoration failure is visible but cannot strand the Book', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    workspaceRequestFailure: true,
    bookDocuments: populatedBookDocuments()
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().workspace?.phase === 'error'
      && window.DeskBackend.state().book?.phase === 'ready');

    const result = await page.evaluate(() => ({
      workspacePhase: window.DeskBackend.state().workspace.phase,
      workspaceError: window.DeskBackend.state().workspace.error?.message,
      bookPhase: window.DeskBackend.state().book.phase,
      declarations: {
        scope: window.WORKSPACE.scopeType,
        goal: window.WORKSPACE.goal,
        view: window.WORKSPACE.view,
        horizon: window.WORKSPACE.horizonDays,
        risk: window.WORKSPACE.riskPosture
      },
      notice: window.WORKSPACE_NOTICE,
      visible: document.body.textContent.replace(/\s+/g, ' ').trim()
    }));
    assert.equal(result.workspacePhase, 'error');
    assert.match(result.workspaceError, /workspace store is temporarily unavailable/i);
    assert.equal(result.bookPhase, 'ready');
    assert.deepEqual(result.declarations, {
      scope: null, goal: null, view: null, horizon: null, risk: null
    }, 'a failed restoration remains explicitly undeclared rather than inventing defaults');
    assert.match(result.notice, /workspace.*unavailable/i,
      'the restore failure is said out loud even though the Book remains usable');
    assert.match(result.visible, /AAPL/i, 'the held Book is still rendered');
    assert.equal(backend.count('GET', '/api/portfolio/book'), 1,
      'the Book load proceeds once after the failed additive restoration barrier');
    assert.deepEqual(pageErrors, [], `workspace failure/Book recovery emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a genuinely empty workspace stays undeclared and never autosaves a broad-market fiction', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    workspaceEmpty: true, workspaceRev: 0, bookDocuments: populatedBookDocuments()
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await new Promise(resolve => setTimeout(resolve, 150));
    assert.deepEqual(await page.evaluate(() => ({
      context: window.DeskBackend.state().workspace.receipt.context,
      scopeType: window.WORKSPACE.scopeType,
      focusedSubject: window.WORKSPACE.focusedSubject,
      routeState: window.WORKSPACE.routeState,
      displayedScope: window.HOME_SCOUT.scope
    })), {
      context: null, scopeType: null, focusedSubject: null, routeState: null,
      displayedScope: null
    }, 'navigation may render an idle Home, but no persisted declaration is manufactured');
    assert.equal(backend.workspacePatches().length, 0,
      'boot and ambient market rendering never turn an empty receipt into a stored default');
    assert.equal(backend.count('POST', '/api/research/scout'), 0,
      'an idle scope never silently launches a broad-market scan');
    assert.deepEqual(pageErrors, [], `empty workspace emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('an empty workspace adopts a world transition even though no context revision exists to advance', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    workspaceEmpty: true, workspaceRev: 0, bookDocuments: emptyBookDocuments()
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().workspace?.phase === 'ready');
    const moved = backend.setMarketIdentity(SIM_WORLD_ID, 'SIMULATED', SIM_DATASET_ID);
    // The real server has no workspace row to rewrite in this state. Only the authoritative
    // top-level market identity moves, so its workspace revision remains zero.
    moved.rev = 0;
    const observed = await page.evaluate(async receipt => {
      await window.DeskBackend.receiveWorkspaceEvent('world.selected', {
        world: receipt.world, revision: 1, epoch: 'empty-world-test', workspace: receipt
      });
      return {
        world: window.WORKSPACE.world,
        datasetId: window.WORKSPACE.datasetId,
        lane: window.WORKSPACE.marketLane,
        rev: window.WORKSPACE.rev,
        context: window.DeskBackend.state().workspace.receipt.context,
        declarations: {
          scopeType: window.WORKSPACE.scopeType,
          goal: window.WORKSPACE.goal,
          focusedSubject: window.WORKSPACE.focusedSubject
        }
      };
    }, moved);
    assert.deepEqual(observed, {
      world: SIM_WORLD_ID, datasetId: SIM_DATASET_ID, lane: 'SIMULATED', rev: 0,
      context: null,
      declarations: { scopeType: null, goal: null, focusedSubject: null }
    }, 'market identity changes atomically while the genuinely empty workspace stays undeclared');
    assert.equal(backend.workspacePatches().length, 0,
      'adopting the server transition cannot manufacture a context row');
    assert.deepEqual(pageErrors, [],
      `empty workspace transition emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Scout requires an explicit field while an exact staged symbol preserves the declared scan field', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    universeSymbols: ['AMD', 'AAPL'],
    workspaceContext: {
      scopeType: null, sectorKey: null, focusedSubject: 'BOOK', focusedSymbol: null,
      routeState: 'book', goal: 'INCOME', view: 'NEUTRAL',
      horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');

    assert.equal(await page.locator('[data-auth-opportunity-scan]').first().isDisabled(), true,
      'a field scan cannot start from an undeclared source scope');
    await page.evaluate(() => window.authRunOpportunityScan());
    await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'failed');
    assert.equal(backend.count('POST', '/api/research/scout'), 0,
      'the action boundary revalidates declarations even when invoked outside the button');
    assert.match(await page.evaluate(() => window.HOME_OPPORTUNITY.error), /market field/i);

    await page.locator('[data-auth-scout-scope="broad"]').first().click();
    await page.evaluate(() => window.DeskBackend.flushWorkspace());
    assert.equal(await page.locator('[data-auth-opportunity-scan]').first().isDisabled(), false,
      'the explicit broad-market declaration enables the field scan');
    assert.equal(backend.workspaceContext().scopeType, 'BROAD_MARKET');

    await page.evaluate(() => window.authStageHomeSymbol('AMD'));
    await page.evaluate(() => window.DeskBackend.flushWorkspace());
    assert.deepEqual(await page.evaluate(() => ({
      scope: window.WORKSPACE.scopeType,
      symbol: window.WORKSPACE.focusedSymbol,
      analyzeDisabled: document.querySelector('[data-auth-workbench-analyze]')?.disabled,
      scanDisabled: document.querySelector('[data-auth-opportunity-scan]')?.disabled
    })), {
      scope: 'BROAD_MARKET', symbol: 'AMD', analyzeDisabled: false, scanDisabled: false
    }, 'an exact ticker can be analyzed while the independently declared broad-market scan stays available');
    assert.deepEqual(pageErrors, [], `Scout scope gating emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Home declaration changes version and cancel Scout as one transaction', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    universeSymbols: ['AMD', 'AAPL'],
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      focusedEvaluationId: 'stale-scout-evaluation',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED',
      targetCents: 9000, shareQuantity: 200
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    const before = await page.evaluate(() => {
      window.__declarationCancelCalls = 0;
      window.DeskBackend.cancelScout = () => { window.__declarationCancelCalls += 1; };
      window.HOME_OPPORTUNITY = {
        phase: 'partial', data: null, error: null,
        progress: { phase: 'IDEAS', counts: { universeConsidered: 1 } }, partial: []
      };
      window.authRenderOpportunityFrame();
      return window.HOME_SCOUT_SEQ;
    });

    await page.locator('[data-auth-workbench-view="Bearish"]').click();
    assert.deepEqual(await page.evaluate(() => ({
      view: window.WORKSPACE.view,
      evaluationId: window.WORKSPACE.focusedEvaluationId,
      phase: window.HOME_OPPORTUNITY.phase,
      sequence: window.HOME_SCOUT_SEQ,
      cancels: window.__declarationCancelCalls
    })), {
      view: 'Bearish', evaluationId: null, phase: 'idle',
      sequence: before + 1, cancels: 1
    }, 'a changed view cancels the in-flight owner, versions callbacks, drops the stale result, '
      + 'and clears the result identity in one state transition');

    await page.locator('[data-auth-scout-goal="ACQUIRE"]').click();
    assert.deepEqual(await page.evaluate(() => ({
      goal: window.WORKSPACE.goal,
      target: window.WORKSPACE.targetCents,
      shares: window.WORKSPACE.shareQuantity,
      assignment: window.WORKSPACE.assignmentPreference,
      phase: window.HOME_OPPORTUNITY.phase
    })), {
      goal: 'ACQUIRE', target: null, shares: null, assignment: null, phase: 'idle'
    }, 'changing goals invalidates symbol-owned acquisition declarations instead of carrying them');

    await page.locator('[data-auth-workbench-target]').fill('82.25');
    await page.locator('[data-auth-workbench-shares]').fill('300');
    assert.deepEqual(await page.evaluate(() => ({
      target: window.WORKSPACE.targetCents,
      shares: window.WORKSPACE.shareQuantity,
      phase: window.HOME_OPPORTUNITY.phase
    })), { target: 8225, shares: 300, phase: 'idle' },
    'Acquire inputs use the same invalidating transaction while preserving the active input');
    assert.deepEqual(pageErrors, [],
      `declaration transaction emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('chain slices use a served anchor or render an explicitly unanchored listed window', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    universeSymbols: ['AMD', 'AAPL']
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    const result = await page.evaluate(() => {
      const quotes = Array.from({ length: 15 }, (_, index) => ({
        strike: 10 + index * 10, bid: 1, ask: 1.2
      }));
      const unanchored = { chain: {
        expiration: '2026-08-21', underlyingPrice: null,
        calls: quotes, puts: quotes
      } };
      const anchored = { chain: Object.assign({}, unanchored.chain, { underlyingPrice: 120 }) };
      const unanchoredHtml = window.authHomeChainHTML(unanchored, null, 'XYZ');
      const anchoredHtml = window.authHomeChainHTML(anchored, null, 'XYZ');
      const governedHomeHtml = window.authHomeChainHTML(anchored, null, 'XYZ', {
        limit: 9, moreAction: 'stage'
      });
      return {
        unanchoredRows: window.authHomeOptionRows(unanchored, null).map(row => row.strike),
        anchoredRows: window.authHomeOptionRows(anchored, null).map(row => row.strike),
        allAnchoredRows: window.authHomeOptionRows(
          anchored, null, Number.MAX_SAFE_INTEGER).map(row => row.strike),
        unanchoredMentions: /price anchor unavailable/i.test(unanchoredHtml),
        /* The strike is now its own actionable control inside the chain row. Assert the
           semantic class token rather than the old one-class element serialization. */
        unanchoredAtm: /class="[^"]*\batm\b[^"]*"/.test(unanchoredHtml),
        anchoredAtm: /class="[^"]*\batm\b[^"]*"/.test(anchoredHtml),
        governedReceipt: governedHomeHtml.match(
          /<div class="authreceipt">([^<]+)<\/div>/)?.[1] || '',
        governedMore: governedHomeHtml.match(
          /data-auth-newidea-symbol="XYZ"[^>]*>([^<]+)<\/button>/)?.[1] || ''
      };
    });
    assert.deepEqual(result.unanchoredRows, [40, 50, 60, 70, 80, 90, 100, 110, 120],
      'without a served anchor the chain shows a stable central listed-strike window');
    assert.ok(result.anchoredRows.includes(120),
      'the chain-owned underlying price centers a quote-less chain');
    assert.equal(result.allAnchoredRows.length, 15,
      'the chain renderer retains the complete listed-strike count outside its nearby window');
    assert.match(result.governedReceipt, /^9 of 15 strikes around the current price/,
      'Home tells the truth about its nearby slice instead of making nine rows look complete');
    assert.equal(result.governedMore, 'Stage XYZ for 6 more strikes →',
      'the remaining exact chain is staged through the existing governed New Idea owner');
    assert.equal(result.unanchoredMentions, true);
    assert.equal(result.unanchoredAtm, false,
      'an unanchored chain cannot invent an ATM strike');
    assert.equal(result.anchoredAtm, true);
  } finally {
    await context.close();
  }
});

test('workspace patches serialize, preserve newer optimistic intent, and encode explicit clears', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    workspacePatchDelayMs: 180,
    workspaceContext: {
      accountId: ACCOUNT_ID,
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().workspace?.phase === 'ready');
    const observed = await page.evaluate(async () => {
      const values = [];
      document.addEventListener('strikebench:desk-backend', event => {
        if (event.detail.phase === 'workspace-updated') values.push(window.WORKSPACE.view);
      });
      const first = window.DeskBackend.patchWorkspace({ view: 'BULLISH' });
      await new Promise(resolve => setTimeout(resolve, 70));
      const second = window.DeskBackend.patchWorkspace({ view: 'BEARISH' });
      const immediate = window.WORKSPACE.view;
      await Promise.all([first, second]);
      await window.DeskBackend.flushWorkspace();
      const cleared = window.DeskBackend.patchWorkspace({ view: null });
      await cleared;
      return { immediate, values, final: window.WORKSPACE.view };
    });
    assert.equal(observed.immediate, 'BEARISH');
    assert.equal(observed.values[0], 'BEARISH',
      'the completion of patch A cannot visibly roll back newer patch B');
    assert.equal(observed.final, null);
    const patches = backend.workspacePatches();
    assert.equal(patches.length, 3);
    assert.equal(patches[0].view, 'BULLISH');
    assert.equal(patches[1].view, 'BEARISH');
    assert.equal(patches[1].expectedRev, patches[0].expectedRev + 1,
      'the second batch waits for and uses the first batch revision');
    assert.equal(Object.prototype.hasOwnProperty.call(patches[2], 'view'), false);
    assert.ok(patches[2].clear.includes('view'),
      'null crosses the wire as an explicit clear, not a retained null field');
    assert.equal(backend.workspaceContext().view, null);
    assert.deepEqual(pageErrors, [], `workspace queue emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('ambient workspace receipts cannot roll back the batch currently on the wire', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    workspacePatchDelayMs: 180,
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().workspace?.phase === 'ready');

    const ambient = backend.mutateWorkspace({ goal: 'EXIT' });
    const observed = await page.evaluate(async receipt => {
      const pending = window.DeskBackend.patchWorkspace({ view: 'BULLISH' });
      await new Promise(resolve => setTimeout(resolve, 70));
      await window.DeskBackend.receiveWorkspaceEvent('workspace.updated', {
        rev: receipt.rev, workspace: receipt
      });
      const immediate = {
        goal: window.WORKSPACE.goal,
        view: window.WORKSPACE.view
      };
      await pending;
      await window.DeskBackend.flushWorkspace();
      return {
        immediate,
        final: {
          goal: window.WORKSPACE.goal,
          view: window.WORKSPACE.view
        }
      };
    }, ambient);
    assert.deepEqual(observed.immediate, { goal: 'EXIT', view: 'BULLISH' },
      'the foreign change is adopted while this desk keeps its active optimistic declaration');
    assert.deepEqual(observed.final, { goal: 'EXIT', view: 'BULLISH' });
    const patches = backend.workspacePatches();
    assert.equal(patches.length, 2,
      'the stale active write is rejected once and rebased once against the ambient receipt');
    assert.equal(patches[1].expectedRev, ambient.rev);
    assert.equal(backend.workspaceContext().goal, 'EXIT');
    assert.equal(backend.workspaceContext().view, 'BULLISH');
    assert.deepEqual(pageErrors, [], `active workspace intent/SSE emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('an ambient market transition drops old-market focus from an in-flight workspace retry', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    workspacePatchDelayMs: 180,
    workspaceContext: {
      scopeType: 'SYMBOL', focusedSubject: 'PACKAGE', focusedSymbol: 'AMD',
      focusedIdeaId: null, focusedEvaluationId: 'eval-amd-old-market',
      routeState: 'idea:AMD', goal: 'INCOME', view: 'NEUTRAL',
      horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().workspace?.phase === 'ready');

    await page.evaluate(() => {
      window.__crossMarketPatch = window.DeskBackend.patchWorkspace({
        view: 'BULLISH',
        scopeType: 'SYMBOL',
        focusedSubject: 'PACKAGE',
        focusedSymbol: 'NVDA',
        focusedEvaluationId: 'eval-nvda-old-market',
        routeState: 'idea:NVDA'
      });
    });
    await new Promise(resolve => setTimeout(resolve, 70));

    backend.setMarketIdentity(SIM_WORLD_ID, 'SIMULATED', SIM_DATASET_ID);
    const moved = backend.mutateWorkspace({
      scopeType: null, sectorKey: null, focusedSubject: null, focusedSymbol: null,
      focusedPositionId: null, focusedIdeaId: null, focusedEvaluationId: null,
      targetCents: null, shareQuantity: null, routeState: null, returnFocus: null
    });
    const immediate = await page.evaluate(async receipt => {
      await window.DeskBackend.receiveWorkspaceEvent('world.selected', {
        world: receipt.world, workspace: receipt
      });
      return {
        world: window.WORKSPACE.world,
        lane: window.WORKSPACE.marketLane,
        datasetId: window.WORKSPACE.datasetId,
        view: window.WORKSPACE.view,
        scopeType: window.WORKSPACE.scopeType,
        subject: window.WORKSPACE.focusedSubject,
        symbol: window.WORKSPACE.focusedSymbol,
        evaluationId: window.WORKSPACE.focusedEvaluationId,
        routeState: window.WORKSPACE.routeState
      };
    }, moved);
    assert.deepEqual(immediate, {
      world: SIM_WORLD_ID, lane: 'SIMULATED', datasetId: SIM_DATASET_ID,
      view: 'BULLISH',
      scopeType: null, subject: null, symbol: null, evaluationId: null, routeState: null
    }, 'the new identity is visible beside the queued declaration, never old-market focus');

    await page.evaluate(async () => {
      await window.__crossMarketPatch;
      await window.DeskBackend.flushWorkspace();
    });
    const patches = backend.workspacePatches();
    assert.equal(patches.length, 2,
      'the old-identity write conflicts once and is retried once against the new identity');
    assert.equal(patches[1].view, 'BULLISH',
      'portable user intent survives the identity transition');
    for (const field of ['scopeType', 'focusedSubject', 'focusedSymbol',
      'focusedEvaluationId', 'routeState']) {
      assert.equal(Object.prototype.hasOwnProperty.call(patches[1], field), false,
        `${field} from the old market cannot be replayed into the new one`);
      assert.equal((patches[1].clear || []).includes(field), false,
        `${field} is owned by the server transition, not a stale retry clear`);
    }
    assert.deepEqual({
      world: backend.workspaceContext().world,
      lane: backend.workspaceContext().marketLane,
      datasetId: backend.workspaceContext().datasetId,
      view: backend.workspaceContext().view,
      scopeType: backend.workspaceContext().scopeType,
      focusedSymbol: backend.workspaceContext().focusedSymbol,
      focusedEvaluationId: backend.workspaceContext().focusedEvaluationId,
      routeState: backend.workspaceContext().routeState
    }, {
      world: SIM_WORLD_ID, lane: 'SIMULATED', datasetId: SIM_DATASET_ID,
      view: 'BULLISH', scopeType: null, focusedSymbol: null,
      focusedEvaluationId: null, routeState: null
    });
    assert.deepEqual(pageErrors, [],
      `cross-market workspace retry emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a stale workspace revision is re-read, rebased, and retried exactly once', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    workspaceConflicts: 1,
    workspaceConflictMutation: { goal: 'EXIT' },
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().workspace?.phase === 'ready');
    await page.evaluate(async () => {
      await window.DeskBackend.patchWorkspace({ view: 'BULLISH' });
    });
    const patches = backend.workspacePatches();
    assert.equal(patches.length, 2, 'one conflict causes one retry, never an unbounded loop');
    assert.equal(patches[1].expectedRev, patches[0].expectedRev + 1);
    assert.deepEqual(await page.evaluate(() => ({
      goal: window.WORKSPACE.goal, view: window.WORKSPACE.view
    })), { goal: 'EXIT', view: 'BULLISH' },
      'the foreign change survives while this desk rebases only the field it owns');
    assert.deepEqual(backend.workspaceContext().goal, 'EXIT');
    assert.deepEqual(backend.workspaceContext().view, 'BULLISH');
    assert.deepEqual(pageErrors, [], `workspace conflict recovery emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a world event aborts Scout, clears old receipts once, and ignores its duplicate delivery', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    scoutDelayMs: 900,
    bookDocuments: populatedBookDocuments(),
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.evaluate(() => {
      window.__workspaceWorldEvents = [];
      document.addEventListener('strikebench:desk-backend', event => {
        if (event.detail.phase === 'world-transition') {
          window.__workspaceWorldEvents.push({
            cleared: event.detail.artifactsCleared,
            book: event.detail.state.book,
            world: event.detail.workspace?.world
          });
        }
      });
      window.__scoutOutcome = 'pending';
      window.DeskBackend.scoutOpportunities({
        scope: 'broad', universe: ['AMD'], horizons: ['45d'],
        maxPicks: 1, riskMode: 'balanced', intents: ['INCOME'],
        thesisOverride: 'neutral'
      }, () => {}).then(() => { window.__scoutOutcome = 'resolved'; })
        .catch(error => { window.__scoutOutcome = error.name || error.message; });
    });
    await page.waitForFunction(() => window.__scoutOutcome === 'pending');
    await page.waitForFunction(() => performance.getEntriesByType('resource')
      .some(entry => entry.name.includes('/api/research/scout'))
      || window.DeskBackend.state().book?.phase === 'ready');
    const eventReceipt = await page.evaluate(async () => {
      const prior = window.DeskBackend.state().workspace.receipt;
      const moved = JSON.parse(JSON.stringify(prior));
      moved.rev = Number(prior.rev) + 1;
      moved.world = 'sim-workspace-event';
      moved.marketLane = 'SIMULATED';
      moved.context.world = moved.world;
      moved.context.marketLane = moved.marketLane;
      moved.context.generation = Number(moved.context.generation || 0) + 1;
      ['scopeType', 'sectorKey', 'focusedSubject', 'focusedSymbol', 'focusedPositionId',
        'focusedIdeaId', 'focusedEvaluationId', 'targetCents', 'shareQuantity',
        'routeState', 'returnFocus'].forEach(field => { moved.context[field] = null; });
      const event = { world: moved.world, revision: 99, epoch: 'sim-event-99', workspace: moved };
      await window.DeskBackend.receiveWorkspaceEvent('world.selected', event);
      await window.DeskBackend.receiveWorkspaceEvent('world.selected', event);
      await new Promise(resolve => setTimeout(resolve, 40));
      return {
        receipt: moved,
        events: window.__workspaceWorldEvents,
        outcome: window.__scoutOutcome,
        bridgeWorld: window.DeskBackend.state().workspace.context.world,
        book: window.DeskBackend.state().book
      };
    });
    assert.equal(eventReceipt.events.length, 1,
      'the PUT/SSE duplicate identity cannot clear or render the world twice');
    assert.equal(eventReceipt.events[0].cleared, true);
    assert.equal(eventReceipt.events[0].book, null,
      'the transition event is published only after old-world receipts are gone');
    assert.equal(eventReceipt.bridgeWorld, 'sim-workspace-event');
    assert.notEqual(eventReceipt.outcome, 'resolved',
      'the old-world Scout cannot resolve into the new workspace');
    assert.deepEqual(pageErrors, [], `world event/Scout cancellation emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('dataset identity is part of workspace monotonicity and stale or duplicate events cannot resurrect it', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    const prior = await page.evaluate(() =>
      JSON.parse(JSON.stringify(window.DeskBackend.state().workspace.receipt)));
    const nextDataset = 'dataset-observed-replacement';
    const moved = backend.setMarketIdentity('observed', 'OBSERVED', nextDataset);
    moved.context.generation = Number(prior.context.generation || 0) + 1;
    moved.context.focusedSymbol = null;

    const observed = await page.evaluate(async ({ priorReceipt, movedReceipt }) => {
      const transitions = [];
      document.addEventListener('strikebench:desk-backend', event => {
        if (event.detail.phase === 'world-transition') {
          transitions.push({
            cleared: event.detail.artifactsCleared,
            datasetId: window.WORKSPACE.datasetId
          });
        }
      });
      await window.DeskBackend.receiveWorkspaceEvent('dataset.selected', {
        datasetId: movedReceipt.datasetId, workspace: movedReceipt
      });
      await window.DeskBackend.receiveWorkspaceEvent('dataset.selected', {
        datasetId: movedReceipt.datasetId, workspace: movedReceipt
      });
      await window.DeskBackend.receiveWorkspaceEvent('world.selected', {
        world: priorReceipt.world, workspace: priorReceipt
      });
      return {
        transitions,
        datasetId: window.WORKSPACE.datasetId,
        rev: window.WORKSPACE.rev,
        book: window.DeskBackend.state().book
      };
    }, { priorReceipt: prior, movedReceipt: moved });

    assert.deepEqual(observed.transitions, [{
      cleared: true, datasetId: nextDataset
    }], 'one dataset replacement invalidates market-owned artifacts exactly once');
    assert.equal(observed.datasetId, nextDataset,
      'a delayed old world receipt cannot resurrect the prior dataset');
    assert.equal(observed.rev, moved.rev);
    assert.equal(observed.book?.data, null,
      'the old dataset is gone before the replacement Book begins loading');
    assert.equal(observed.book?.phase, 'loading');
    assert.deepEqual(pageErrors, [], `dataset monotonicity emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

async function readIdentityScopedMarketArtifacts(page, symbol, expiry) {
  await page.evaluate(({ symbol: requestedSymbol, expiry: requestedExpiry }) => {
    window.ensureHist(requestedSymbol);
    window.ensureExpectedMove(requestedSymbol, requestedExpiry);
  }, { symbol, expiry });
  await page.waitForFunction(({ symbol: requestedSymbol, expiry: requestedExpiry }) => {
    const history = window.historySlot(requestedSymbol);
    const move = window.EM_STORE[window.emKey(requestedSymbol, requestedExpiry)];
    return history?.phase === 'ready' && move?.phase === 'ready';
  }, { symbol, expiry });
  return page.evaluate(({ symbol: requestedSymbol, expiry: requestedExpiry }) => {
    const historyKey = window.histKey(requestedSymbol);
    const moveKey = window.emKey(requestedSymbol, requestedExpiry);
    const history = window.HIST_STORE[historyKey];
    const move = window.EM_STORE[moveKey];
    return {
      apiIdentity: window.API.marketCacheIdentity(),
      deskIdentity: window.DeskBackend.marketIdentityKey(),
      historyKey,
      historySource: history.source,
      lastClose: history.bars[history.bars.length - 1].c,
      moveKey,
      moveSource: move.receipt.source,
      moveMedian: move.receipt.p50
    };
  }, { symbol, expiry });
}

test('market receipt caches refetch the same symbol and expiry across world and dataset identities', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    identityStampedMarketData: true,
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book'
    }
  });
  const symbol = 'CACHE', expiry = '2026-08-21';
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().workspace?.phase === 'ready');

    const observedFirst = await readIdentityScopedMarketArtifacts(page, symbol, expiry);
    assert.equal(observedFirst.apiIdentity, observedFirst.deskIdentity);
    assert.equal(observedFirst.historySource, `CACHE_TEST_${DATASET_ID}`);
    assert.equal(observedFirst.lastClose, 111);
    assert.equal(observedFirst.moveMedian, 100);
    await page.evaluate(async () => {
      await window.DeskBackend.patchWorkspace({ goal: 'INCOME' });
      await window.DeskBackend.flushWorkspace();
    });
    const declarationOnly = await readIdentityScopedMarketArtifacts(page, symbol, expiry);
    assert.equal(declarationOnly.historyKey, observedFirst.historyKey,
      'ordinary workspace revisions do not create a fictitious market identity');
    assert.equal(backend.count('GET', `/api/research/${symbol}/history`), 1);
    assert.equal(backend.count('GET', `/api/research/${symbol}/expected-move`), 1);

    const simulated = backend.setMarketIdentity(SIM_WORLD_ID, 'SIMULATED', SIM_DATASET_ID);
    await page.evaluate(receipt => window.DeskBackend.receiveWorkspaceEvent('world.selected', {
      world: receipt.world, workspace: receipt
    }), simulated);
    const simulatedRead = await readIdentityScopedMarketArtifacts(page, symbol, expiry);
    assert.equal(simulatedRead.apiIdentity, simulatedRead.deskIdentity);
    assert.equal(simulatedRead.historySource, `CACHE_TEST_${SIM_DATASET_ID}`);
    assert.equal(simulatedRead.lastClose, 91);
    assert.equal(simulatedRead.moveMedian, 80);
    assert.notEqual(simulatedRead.historyKey, observedFirst.historyKey);
    assert.notEqual(simulatedRead.moveKey, observedFirst.moveKey,
      'the same expiry in two worlds is two receipts');

    const replacementDataset = 'dataset-observed-replacement';
    const replacement = backend.setMarketIdentity('observed', 'OBSERVED', replacementDataset);
    await page.evaluate(receipt => window.DeskBackend.receiveWorkspaceEvent('dataset.selected', {
      datasetId: receipt.datasetId, workspace: receipt
    }), replacement);
    const replacementRead = await readIdentityScopedMarketArtifacts(page, symbol, expiry);
    assert.equal(replacementRead.historySource, `CACHE_TEST_${replacementDataset}`);
    assert.equal(replacementRead.lastClose, 131);
    assert.equal(replacementRead.moveMedian, 120);
    assert.notEqual(replacementRead.historyKey, observedFirst.historyKey,
      'one symbol in two datasets is two receipts');

    const observedAgain = backend.setMarketIdentity('observed', 'OBSERVED', DATASET_ID);
    await page.evaluate(receipt => window.DeskBackend.receiveWorkspaceEvent('dataset.selected', {
      datasetId: receipt.datasetId, workspace: receipt
    }), observedAgain);
    const observedSecond = await readIdentityScopedMarketArtifacts(page, symbol, expiry);
    assert.equal(observedSecond.historySource, `CACHE_TEST_${DATASET_ID}`);
    assert.equal(observedSecond.lastClose, 111);
    assert.equal(observedSecond.moveMedian, 100);

    assert.equal(backend.count('GET', `/api/research/${symbol}/history`), 4,
      'observed -> simulated -> replacement dataset -> observed refetches the same URL every time');
    assert.equal(backend.count('GET', `/api/research/${symbol}/expected-move`), 4,
      'returning to the original world cannot resurrect its still-live expected-move TTL entry');
    assert.deepEqual(pageErrors, [],
      `market identity cache transitions emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('SSE market adoption rejects late old-world history and expected-move artifacts', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    identityStampedMarketData: true,
    identityMarketDelayByDataset: { [DATASET_ID]: 260 },
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book'
    }
  });
  const symbol = 'LATE', expiry = '2026-08-21';
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().workspace?.phase === 'ready');

    await page.evaluate(({ symbol: requestedSymbol, expiry: requestedExpiry }) => {
      window.ensureHist(requestedSymbol);
      window.ensureExpectedMove(requestedSymbol, requestedExpiry);
    }, { symbol, expiry });
    for (let attempt = 0; attempt < 30
        && (backend.count('GET', `/api/research/${symbol}/history`) < 1
          || backend.count('GET', `/api/research/${symbol}/expected-move`) < 1); attempt++) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(backend.count('GET', `/api/research/${symbol}/history`), 1);
    assert.equal(backend.count('GET', `/api/research/${symbol}/expected-move`), 1);

    const simulated = backend.setMarketIdentity(SIM_WORLD_ID, 'SIMULATED', SIM_DATASET_ID);
    await page.evaluate(receipt => window.DeskBackend.receiveWorkspaceEvent('world.selected', {
      world: receipt.world, workspace: receipt
    }), simulated);
    const current = await readIdentityScopedMarketArtifacts(page, symbol, expiry);
    await new Promise(resolve => setTimeout(resolve, 320));
    const settled = await page.evaluate(({ symbol: requestedSymbol, expiry: requestedExpiry }) => {
      const identity = window.DeskBackend.marketIdentityKey();
      const history = window.historySlot(requestedSymbol);
      const move = window.expectedMoveReceipt(requestedSymbol, requestedExpiry);
      return {
        identity,
        apiIdentity: window.API.marketCacheIdentity(),
        historySource: history && history.source,
        lastClose: history && history.bars[history.bars.length - 1].c,
        moveSource: move && move.source,
        moveMedian: move && move.p50,
        historyKeys: Object.keys(window.HIST_STORE)
          .filter(key => key.endsWith('\u0000history\u0000' + requestedSymbol)),
        moveKeys: Object.keys(window.EM_STORE)
          .filter(key => key.endsWith('\u0000expected-move\u0000' + requestedSymbol
            + '\u0000' + requestedExpiry))
      };
    }, { symbol, expiry });

    assert.equal(current.historySource, `CACHE_TEST_${SIM_DATASET_ID}`);
    assert.deepEqual(settled, {
      identity: settled.identity,
      apiIdentity: settled.identity,
      historySource: `CACHE_TEST_${SIM_DATASET_ID}`,
      lastClose: 91,
      moveSource: `CACHE_TEST_${SIM_DATASET_ID}`,
      moveMedian: 80,
      historyKeys: [settled.identity + '\u0000history\u0000' + symbol],
      moveKeys: [settled.identity + '\u0000expected-move\u0000' + symbol + '\u0000' + expiry]
    }, 'late old-world promises cannot publish after the SSE identity is accepted');
    assert.equal(backend.count('GET', `/api/research/${symbol}/history`), 2);
    assert.equal(backend.count('GET', `/api/research/${symbol}/expected-move`), 2);
    assert.deepEqual(pageErrors, [],
      `late market receipt cancellation emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('same-world lane changes invalidate old market receipts through the atomic PUT response', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceContext: {
      world: 'observed', marketLane: 'OBSERVED', scopeType: 'BROAD_MARKET',
      focusedSubject: 'BOOK', routeState: 'book', goal: 'INCOME',
      view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    const scenarioWorkspace = backend.setMarketIdentity('observed', 'SCENARIO');
    await page.evaluate(async receipt => {
      await window.DeskBackend.receiveWorkspaceEvent('world.selected', {
        world: 'observed', revision: 42, epoch: 'scenario-42', workspace: receipt
      });
    }, scenarioWorkspace);
    await page.waitForFunction(() => window.DeskBackend.workspaceContext().marketLane === 'SCENARIO');
    const result = await page.evaluate(async () => {
      let transition = null;
      document.addEventListener('strikebench:desk-backend', event => {
        if (event.detail.phase === 'world-transition') {
          transition = {
            cleared: event.detail.artifactsCleared,
            bookAtPublish: event.detail.state.book,
            lane: event.detail.workspace?.marketLane
          };
        }
      });
      const response = await window.DeskBackend.transitionWorld('observed', { reopen: false });
      return {
        transition,
        responseLane: response.workspace.marketLane,
        contextLane: window.DeskBackend.workspaceContext().marketLane
      };
    });
    assert.deepEqual(result, {
      transition: { cleared: true, bookAtPublish: null, lane: 'OBSERVED' },
      responseLane: 'OBSERVED', contextLane: 'OBSERVED'
    }, 'world+dataset+lane+account—not the world string or context generation alone—own market artifacts');
    assert.deepEqual(pageErrors, [], `same-world lane transition emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('reload restores an exact position route and package Back returns to its saved focus', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'PACKAGE', focusedSymbol: 'AAPL',
      focusedPositionId: null, focusedIdeaId: BOOK_PLAN_ID, focusedEvaluationId: null,
      routeState: `idea:${BOOK_PLAN_ID}`, goal: 'INCOME', view: 'NEUTRAL',
      horizonDays: 45, riskPosture: 'BALANCED',
      returnFocus: {
        subject: 'POSITION', symbol: 'AAPL', positionId: BOOK_TRADE_ID,
        ideaId: null, evaluationId: null, scopeType: 'BROAD_MARKET',
        sectorKey: null, routeState: `position:${BOOK_TRADE_ID}`
      }
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(id => window.DeskBackend.ideaDeclaration()?.planId === id
      && window.WORKSPACE.focusedIdeaId === id, BOOK_PLAN_ID);
    assert.deepEqual(await page.evaluate(() => ({
      level: window.state.level, focus: window.state.focus,
      idea: window.decide?.sym, subject: window.WORKSPACE.focusedSubject
    })), {
      level: 'position', focus: BOOK_TRADE_ID, idea: 'AAPL', subject: 'PACKAGE'
    }, 'the package reopens over the exact position saved as its Back destination');

    await page.evaluate(() => window.exitDecide());
    await page.waitForFunction(id => window.WORKSPACE.focusedSubject === 'POSITION'
      && window.WORKSPACE.focusedPositionId === id, BOOK_TRADE_ID);
    await page.evaluate(() => window.DeskBackend.flushWorkspace());
    assert.deepEqual(await page.evaluate(() => ({
      level: window.state.level, focus: window.state.focus,
      subject: window.WORKSPACE.focusedSubject,
      position: window.WORKSPACE.focusedPositionId,
      route: window.WORKSPACE.routeState
    })), {
      level: 'position', focus: BOOK_TRADE_ID, subject: 'POSITION',
      position: BOOK_TRADE_ID, route: `position:${BOOK_TRADE_ID}`
    });
    assert.equal(backend.workspaceContext().focusedPositionId, BOOK_TRADE_ID);
    assert.deepEqual(pageErrors, [], `workspace route restoration emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('workspace market identity includes account and market readouts cannot rewrite an adopted receipt', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    const renderOwnership = await page.evaluate(() => {
      const before = {
        symbol: window.WORKSPACE.focusedSymbol,
        scope: window.WORKSPACE.scopeType,
        rev: window.WORKSPACE.rev
      };
      window.authRenderMarketPanels();
      window.authRenderMarketPanels();
      return {
        before,
        after: {
          symbol: window.WORKSPACE.focusedSymbol,
          scope: window.WORKSPACE.scopeType,
          rev: window.WORKSPACE.rev
        }
      };
    });
    assert.deepEqual(renderOwnership.after, renderOwnership.before,
      'rendering the ambient market cannot mutate the accepted workspace focus');
    assert.equal(backend.workspacePatches().length, 0,
      'no renderer owns a hidden workspace write');
    const result = await page.evaluate(async () => {
      const prior = window.DeskBackend.state().workspace.receipt;
      const changedAccount = JSON.parse(JSON.stringify(prior));
      changedAccount.rev = Number(prior.rev) + 1;
      changedAccount.accountId = 'acct-2';
      changedAccount.context.accountId = 'acct-2';
      changedAccount.context.generation = Number(changedAccount.context.generation || 0) + 1;
      const transitions = [];
      document.addEventListener('strikebench:desk-backend', event => {
        if (event.detail.phase === 'world-transition') {
          transitions.push({
            cleared: event.detail.artifactsCleared,
            accountId: window.WORKSPACE.accountId
          });
        }
      });
      await window.DeskBackend.receiveWorkspaceEvent('world.selected', {
        world: changedAccount.world, workspace: changedAccount
      });
      const before = {
        world: window.WORKSPACE.world,
        lane: window.WORKSPACE.marketLane,
        datasetId: window.WORKSPACE.datasetId,
        accountId: window.WORKSPACE.accountId,
        mode: window.WORKSPACE.marketMode
      };
      const accepted = window.syncMarketModeFromBackend({
        identity: {
          world: changedAccount.world,
          marketLane: changedAccount.marketLane,
          datasetId: 'unaccepted-dataset',
          accountId: changedAccount.accountId
        }
      });
      return {
        transitions, before, accepted,
        after: {
          world: window.WORKSPACE.world,
          lane: window.WORKSPACE.marketLane,
          datasetId: window.WORKSPACE.datasetId,
          accountId: window.WORKSPACE.accountId,
          mode: window.WORKSPACE.marketMode
        }
      };
    });
    assert.deepEqual(result.transitions, [{ cleared: true, accountId: 'acct-2' }],
      'account is part of the market artifact identity even when revision/world/lane match');
    assert.equal(result.accepted, false);
    assert.deepEqual(result.after, result.before,
      'a market payload may verify and paint, but cannot mutate workspace identity');
    assert.deepEqual(pageErrors, [], `workspace identity emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('workspace reload restores empty, symbol, and Scout-evaluation idea stages before a Plan exists', async () => {
  const stages = [
    {
      name: 'empty composer',
      workspace: {
        scopeType: 'BROAD_MARKET', focusedSubject: 'MARKET', focusedSymbol: null,
        focusedIdeaId: null, focusedEvaluationId: null, routeState: 'idea'
      },
      verify: async page => {
        await page.waitForFunction(() => document.activeElement
          && document.activeElement.hasAttribute('data-auth-workbench-query'));
        return page.evaluate(() => ({
          decide: window.decide,
          route: window.WORKSPACE.routeState,
          subject: window.WORKSPACE.focusedSubject
        }));
      },
      expected: { decide: null, route: 'idea', subject: 'MARKET' }
    },
    {
      name: 'symbol idea',
      workspace: {
        scopeType: 'BROAD_MARKET', focusedSubject: 'MARKET', focusedSymbol: 'AMD',
        focusedIdeaId: null, focusedEvaluationId: null, routeState: 'idea:AMD',
        goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
      },
      verify: async page => {
        await page.waitForFunction(() => window.decide?.sym === 'AMD');
        return page.evaluate(() => ({
          symbol: window.decide.sym,
          evaluationId: window.decide.evaluationId,
          route: window.WORKSPACE.routeState
        }));
      },
      expected: { symbol: 'AMD', evaluationId: null, route: 'idea:AMD' }
    },
    {
      name: 'Scout evaluation',
      workspace: {
        scopeType: 'BROAD_MARKET', focusedSubject: 'PACKAGE', focusedSymbol: 'AMD',
        focusedIdeaId: null, focusedEvaluationId: 'eval-restored-scout',
        routeState: 'idea:AMD', goal: 'INCOME', view: 'NEUTRAL',
        horizonDays: 45, riskPosture: 'BALANCED'
      },
      verify: async page => {
        await page.waitForFunction(() => window.decide?.evaluationId === 'eval-restored-scout');
        return page.evaluate(() => ({
          symbol: window.decide.sym,
          evaluationId: window.decide.evaluationId
        }));
      },
      expected: { symbol: 'AMD', evaluationId: 'eval-restored-scout' }
    },
    {
      name: 'exact Plan without redundant saved symbol',
      workspace: {
        scopeType: 'BROAD_MARKET', focusedSubject: 'PACKAGE', focusedSymbol: null,
        focusedIdeaId: PLAN_ID, focusedEvaluationId: null,
        routeState: `idea:${PLAN_ID}`, goal: 'INCOME', view: 'NEUTRAL',
        horizonDays: 45, riskPosture: 'BALANCED'
      },
      verify: async page => {
        await page.waitForFunction(planId => window.DeskBackend.state().plan?.id === planId
          && window.decide?.sym === 'AMD', PLAN_ID);
        return page.evaluate(() => ({
          planId: window.DeskBackend.state().plan?.id,
          symbol: window.decide?.sym,
          savedSymbol: window.WORKSPACE.focusedSymbol
        }));
      },
      expected: { planId: PLAN_ID, symbol: 'AMD', savedSymbol: 'AMD' }
    }
  ];
  for (const stage of stages) {
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    try {
      await installBackend(page, {
        bookDocuments: populatedBookDocuments(),
        workspaceContext: stage.workspace
      });
      await page.goto(deskUrl);
      await waitForDeskBoot(page);
      assert.deepEqual(await stage.verify(page), stage.expected, stage.name);
      assert.deepEqual(pageErrors, [], `${stage.name} emitted page errors: ${pageErrors.join('\n')}`);
    } finally {
      await context.close();
    }
  }
});

test('subject transitions clear symbol-owned facts, Back restores them, and Import is not a route', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceContext: {
      scopeType: 'BROAD_MARKET', focusedSubject: 'MARKET', focusedSymbol: 'AAPL',
      focusedPositionId: null, focusedIdeaId: null, focusedEvaluationId: 'eval-aapl',
      routeState: 'market:AAPL', goal: 'ACQUIRE', view: 'NEUTRAL',
      horizonDays: 45, riskPosture: 'BALANCED',
      targetCents: 19000, shareQuantity: 300, returnFocus: null
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    const importReceipt = await page.evaluate(() => {
      const before = JSON.stringify(window.WORKSPACE.returnFocus);
      window.openImport(null);
      const after = JSON.stringify(window.WORKSPACE.returnFocus);
      window.closeImport();
      return { before, after };
    });
    assert.deepEqual(importReceipt, { before: 'null', after: 'null' },
      'an overlay does not change the durable Back destination');

    await page.evaluate(() => window.authOpenSymbol('AMD', null));
    await page.waitForFunction(() => window.decide?.sym === 'AMD');
    assert.deepEqual(await page.evaluate(() => ({
      symbol: window.WORKSPACE.focusedSymbol,
      target: window.WORKSPACE.targetCents,
      shares: window.WORKSPACE.shareQuantity,
      evaluation: window.WORKSPACE.focusedEvaluationId,
      back: window.WORKSPACE.returnFocus
    })), {
      symbol: 'AMD', target: null, shares: null, evaluation: null,
      back: {
        subject: 'MARKET', symbol: 'AAPL', positionId: null, ideaId: null,
        evaluationId: 'eval-aapl', scopeType: 'BROAD_MARKET', sectorKey: null,
        targetCents: 19000, shareQuantity: 300, routeState: 'market:AAPL'
      }
    });
    await page.evaluate(() => window.exitDecide());
    await page.evaluate(() => window.DeskBackend.flushWorkspace());
    assert.deepEqual(await page.evaluate(() => ({
      symbol: window.WORKSPACE.focusedSymbol,
      target: window.WORKSPACE.targetCents,
      shares: window.WORKSPACE.shareQuantity,
      evaluation: window.WORKSPACE.focusedEvaluationId,
      route: window.WORKSPACE.routeState
    })), {
      symbol: 'AAPL', target: 19000, shares: 300,
      evaluation: 'eval-aapl', route: 'market:AAPL'
    }, 'Back restores the exact prior Acquire declaration, not only its ticker');
    assert.equal(backend.workspaceContext().targetCents, 19000);
    assert.equal(backend.workspaceContext().shareQuantity, 300);
    assert.deepEqual(pageErrors, [], `subject transitions emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a Position leg edit is selected through the durable Plan owner and survives reload', async () => {
  const documents = populatedBookDocuments();
  documents.planPortfolio[0].plan = plan(31, {
    id: BOOK_PLAN_ID, symbol: 'AAPL', status: 'POSITION_OPEN',
    assumptionsEditable: false
  });
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: documents,
    workspaceContext: {
      accountId: ACCOUNT_ID,
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.evaluate(id => {
      window.__positionForkEvents = [];
      document.addEventListener('strikebench:desk-backend', event => {
        const detail = event.detail || {};
        window.__positionForkEvents.push({
          phase: detail.phase,
          operation: detail.operation,
          error: detail.error && detail.error.message
        });
      });
      window.go('position', id);
      const position = window.byId[id];
      window.authForkPositionLegs(position, null, { act: 'strike', li: 0, d: 1 });
    }, BOOK_TRADE_ID);
    await page.waitForFunction(candidateId => window.DeskBackend.state().selected?.id === candidateId
      && window.decide?._positionForkDurability == null, CUSTOM_CANDIDATE_ID)
      .catch(async error => {
        const diagnosis = await page.evaluate(() => ({
          state: window.DeskBackend.state(),
          decide: window.decide && {
            phase: window.decide.backendPhase,
            error: window.decide.backendError,
            draftError: window.decide.draftError,
            durability: window.decide._positionForkDurability
          },
          pendingFork: window.AUTH_PENDING_FORK,
          events: window.__positionForkEvents
        }));
        throw new Error(`${error.message}\nPosition fork diagnosis: ${JSON.stringify(diagnosis)}`);
      });
    await page.evaluate(() => window.DeskBackend.flushWorkspace());
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/custom`), 1,
      'the exact fork is accepted by the existing Plan custom-package owner');
    assert.equal(backend.workspaceContext().focusedIdeaId, PLAN_ID);

    await page.reload();
    await waitForDeskBoot(page);
    await page.waitForFunction(([candidateId, planId]) =>
      window.DeskBackend.state().selected?.id === candidateId
      && window.decide?.resumePlanId === planId, [CUSTOM_CANDIDATE_ID, PLAN_ID])
      .catch(async error => {
        const diagnosis = await page.evaluate(() => ({
          state: window.DeskBackend.state(),
          decide: window.decide && {
            phase: window.decide.backendPhase,
            error: window.decide.backendError,
            resumePlanId: window.decide.resumePlanId,
            candidateId: window.decide.candId
          },
          workspace: window.WORKSPACE
        }));
        throw new Error(`${error.message}\nReloaded Position fork diagnosis: ${JSON.stringify(diagnosis)}`);
      });
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/custom`), 1,
      'reload reads the persisted exact package instead of rebuilding or re-posting it');
    assert.deepEqual(pageErrors, [], `durable position fork emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Position forks preserve every held contract through comparison-only and empty strategy fields', async () => {
  for (const field of [
    { name: 'comparison-only', candidates: [unfavorableCandidate()] },
    { name: 'empty', candidates: [] }
  ]) {
    const documents = populatedBookDocuments();
    documents.activeTrades[0].legs = [
      {
        type: 'CALL', action: 'BUY', positionEffect: 'OPEN', ratio: 1,
        multiplier: 100, strike: 213, expiration: '2026-08-21', entryPrice: 5.25
      },
      {
        type: 'CALL', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
        multiplier: 100, strike: 231, expiration: '2026-09-18', entryPrice: 3.09
      }
    ];
    documents.tradeDetail.trade = documents.activeTrades[0];
    documents.planPortfolio[0].plan = plan(31, {
      id: BOOK_PLAN_ID, symbol: 'AAPL', status: 'POSITION_OPEN',
      assumptionsEditable: false
    });
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    const backend = await installBackend(page, {
      bookDocuments: documents,
      strategyCandidates: field.candidates,
      workspaceContext: {
        accountId: ACCOUNT_ID,
        scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
        goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
      }
    });
    try {
      await page.goto(deskUrl);
      await waitForDeskBoot(page);
      await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
      await page.evaluate(id => {
        window.__positionForkEvents = [];
        document.addEventListener('strikebench:desk-backend', event => {
          const detail = event.detail || {};
          window.__positionForkEvents.push({
            phase: detail.phase,
            operation: detail.operation,
            error: detail.error && detail.error.message
          });
        });
        window.go('position', id);
        window.authForkPositionLegs(window.byId[id], null, null);
      }, BOOK_TRADE_ID);
      await page.waitForFunction(candidateId =>
        window.DeskBackend.state().selected?.id === candidateId
        && window.decide?._positionForkDurability == null,
      CUSTOM_CANDIDATE_ID, { timeout: 12000 }).catch(async error => {
        const diagnosis = await page.evaluate(() => ({
          state: window.DeskBackend.state(),
          decide: window.decide && {
            phase: window.decide.backendPhase,
            error: window.decide.backendError,
            draftError: window.decide.draftError,
            durability: window.decide._positionForkDurability
          },
          pendingFork: window.AUTH_PENDING_FORK,
          events: window.__positionForkEvents
        }));
        throw new Error(`${error.message}\n${field.name} fork diagnosis: ${JSON.stringify(diagnosis)}`);
      });

      const previewRequest = backend.requests.filter(row =>
        row.method === 'POST' && row.path === '/api/trades/preview').at(-1);
      const customRequest = backend.requests.filter(row => row.method === 'POST'
        && row.path === `/api/plans/${PLAN_ID}/strategy/custom`).at(-1);
      const exactLegs = [
        {
          action: 'BUY', type: 'CALL', strike: 213, expiration: '2026-08-21',
          ratio: 1, multiplier: 100, positionEffect: 'OPEN', entryPrice: null
        },
        {
          action: 'SELL', type: 'CALL', strike: 231, expiration: '2026-09-18',
          ratio: 1, multiplier: 100, positionEffect: 'OPEN', entryPrice: null
        }
      ];
      assert.deepEqual(previewRequest.body.legs, exactLegs,
        `${field.name}: preview preserves off-panel strikes and the second expiration exactly`);
      assert.deepEqual(customRequest.body.position.legs, exactLegs,
        `${field.name}: persistence uses the same exact held package, not a regenerated strategy`);
      assert.equal(backend.count('PUT', `/api/plans/${PLAN_ID}/strategy/select`), 0,
        `${field.name}: no generated candidate must be endorsed before the held package can be edited`);
      assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 1,
        `${field.name}: the existing ensemble owner starts only after the exact custom package is selected`);
      assert.deepEqual(pageErrors, [], `${field.name} exact Position fork emitted page errors: ${pageErrors.join('\n')}`);
    } finally {
      await context.close();
    }
  }
});

test('Position Forward Test saves one exact linked package, creates a durable rehearsal, and restores it', async () => {
  const documents = populatedBookDocuments();
  documents.planPortfolio[0].plan = plan(31, {
    id: BOOK_PLAN_ID, symbol: 'AAPL', status: 'POSITION_OPEN',
    assumptionsEditable: false
  });
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: documents,
    workspaceContext: {
      accountId: ACCOUNT_ID,
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.evaluate(id => window.go('position', id), BOOK_TRADE_ID);
    await page.locator('[data-auth-manage="forward"]').click();
    await page.waitForFunction(() =>
      window.DeskBackend.state().rehearsal?.phase === 'ready'
      && document.querySelector('.rehearsalstrip')?.textContent.includes('is ready'));

    const receipt = await page.evaluate(() => ({
      workflow: window.decide.rehearsalWorkflow,
      text: document.querySelector('.rehearsalstrip')?.textContent,
      editActions: document.querySelectorAll('#decide [data-auth-manage="resume"]').length
    }));
    assert.match(receipt.text, /Projected forward rehearsal/);
    assert.match(receipt.text, /modeled projection/i);
    assert.doesNotMatch(receipt.text, /historical backtest result/i);
    assert.equal(receipt.workflow.selection, 'TYPICAL');
    assert.equal(receipt.workflow.sourcePositionId, BOOK_TRADE_ID);
    assert.equal(receipt.editActions, 0,
      'the immutable Position editor action does not remain duplicated inside New Idea');

    const createPlan = backend.requests.find(row =>
      row.method === 'POST' && row.path === '/api/plans');
    assert.equal(createPlan.body.originPlanId, BOOK_PLAN_ID);
    const custom = backend.requests.find(row =>
      row.method === 'POST' && row.path === `/api/plans/${PLAN_ID}/strategy/custom`);
    assert.deepEqual(custom.body.position.legs.map(leg => ({
      action: leg.action, type: leg.type, strike: leg.strike,
      expiration: leg.expiration, ratio: leg.ratio, entryPrice: leg.entryPrice
    })), documents.activeTrades[0].legs.map(leg => ({
      action: leg.action, type: leg.type, strike: leg.strike,
      expiration: leg.expiration, ratio: leg.ratio, entryPrice: null
    })), 'the rehearsal values the exact held contracts without recycling historical entry prices');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/rehearsals`), 1);

    await page.evaluate(() => window.DeskBackend.flushWorkspace());
    await page.reload();
    await waitForDeskBoot(page);
    await page.waitForFunction(() =>
      window.DeskBackend.state().rehearsals?.length === 1
      && document.querySelector('.rehearsalstrip')?.textContent.includes('restored'));
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/rehearsals`), 1,
      'reload restores the durable rehearsal rather than creating another one');
    assert.deepEqual(pageErrors, [], `Forward Test emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('Position Forward Test publishes an explicit rehearsal error without mutating the held position', async () => {
  const documents = populatedBookDocuments();
  documents.planPortfolio[0].plan = plan(31, {
    id: BOOK_PLAN_ID, symbol: 'AAPL', status: 'POSITION_OPEN',
    assumptionsEditable: false
  });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: documents,
    failRehearsalOnce: true,
    workspaceContext: {
      accountId: ACCOUNT_ID,
      scopeType: 'BROAD_MARKET', focusedSubject: 'BOOK', routeState: 'book',
      goal: 'INCOME', view: 'NEUTRAL', horizonDays: 45, riskPosture: 'BALANCED'
    }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.evaluate(id => window.go('position', id), BOOK_TRADE_ID);
    await page.locator('[data-auth-manage="forward"]').click();
    await page.waitForFunction(() =>
      window.DeskBackend.state().rehearsal?.phase === 'error'
      && document.querySelector('.rehearsalstrip.error'));
    const message = await page.locator('.rehearsalstrip.error').innerText();
    assert.match(message, /temporarily unavailable/i);
    assert.match(message, /modeled projection/i);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/rehearsals`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/trade`), 0,
      'Forward Test never mutates or replaces the held trade');
    assert.deepEqual(pageErrors, [], `Forward Test error state emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('an unreadable stored workspace is reported with its reason instead of silently starting blank', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceUnreadable: { storedVersion: 99, supportedVersion: 1,
      reason: 'it was written by a newer build (version 99); this build reads version 1' }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.WORKSPACE_NOTICE != null);

    const notice = await page.evaluate(() => window.WORKSPACE_NOTICE);
    assert.match(notice, /not restored/, 'the desk says the workspace was not restored');
    assert.match(notice, /version 99/, 'and quotes the server\'s reason verbatim rather than paraphrasing it');
    assert.deepEqual(await page.evaluate(() => ({
      goal: window.WORKSPACE.goal, view: window.WORKSPACE.view,
      horizonDays: window.WORKSPACE.horizonDays, riskPosture: window.WORKSPACE.riskPosture
    })), { goal: null, view: null, horizonDays: null, riskPosture: null },
      'an unreadable context leaves every declaration undeclared — it never invents one (§3.5)');

    assert.deepEqual(pageErrors, [], `unreadable workspace emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a clicked Scout row opens the exact package it displayed, and a refusal says so', async () => {
  const context = await browser.newContext({ viewport: { width: 2560, height: 1440 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceContext: { goal: 'INCOME', view: 'Neutral', horizonDays: 45, riskPosture: 'Balanced' }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');

    // A completed scan whose row names the evaluation behind it.
    await page.evaluate(() => {
      window.HOME_OPPORTUNITY = { phase: 'complete', error: null, progress: null, partial: [], data: {
        picks: [{ symbol: 'AMD', opportunity: { score: 81 }, bestIdea: {
          available: true, evaluationId: 'eval-scout-77', resultKey: 'key-77',
          family: 'CREDIT_PUT_SPREAD', displayName: 'Bull put (credit) spread',
          economicVerdict: 'FAVORABLE', horizon: '45D', realizedVolEvAfterCostsCents: 4200
        } }], frontier: null, skipped: [], notes: []
      } };
      window.authRenderOpportunityOnly();
    });
    await page.waitForSelector('.opportunityrow[data-auth-evaluation]');
    assert.equal(await page.getAttribute('.opportunityrow[data-auth-evaluation]', 'data-auth-evaluation'),
      'eval-scout-77', 'the row on screen names the evaluation it is showing');

    await page.locator('.opportunityrow[data-auth-evaluation]').click();
    await page.waitForFunction(() => window.decide != null);
    assert.equal(await page.evaluate(() => window.decide.evaluationId), 'eval-scout-77',
      'the opened idea carries the clicked row\'s identity, not just its ticker');
    await page.waitForFunction(() => window.decide && window.decide.cands.length > 0);
    const adoptions = backend.adoptionRequests();
    assert.equal(adoptions.length, 1, 'exactly one adoption is requested for the clicked row');
    assert.equal(adoptions[0].evaluationId, 'eval-scout-77');
    assert.ok(adoptions[0].expectedVersion != null,
      'the adoption is guarded by the Plan version the desk was looking at');

    assert.deepEqual(pageErrors, [], `Scout adoption emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('a scanned package that can no longer be produced stops in adoption-unavailable and is never substituted', async () => {
  const context = await browser.newContext({ viewport: { width: 2560, height: 1440 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceContext: { goal: 'INCOME', view: 'Neutral', horizonDays: 45, riskPosture: 'Balanced' },
    adoptionRefusal: 'That scanned package (eval-scout-77) is no longer available in this market. '
      + 'Scan again to price it; no substitute package was selected.'
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.evaluate(() => {
      window.HOME_OPPORTUNITY = { phase: 'complete', error: null, progress: null, partial: [], data: {
        picks: [{ symbol: 'AMD', opportunity: { score: 81 }, bestIdea: {
          available: true, evaluationId: 'eval-scout-77', resultKey: 'key-77',
          family: 'CREDIT_PUT_SPREAD', displayName: 'Bull put (credit) spread',
          economicVerdict: 'FAVORABLE', horizon: '45D', realizedVolEvAfterCostsCents: 4200
        } }], frontier: null, skipped: [], notes: []
      } };
      window.authRenderOpportunityOnly();
    });
    await page.locator('.opportunityrow[data-auth-evaluation]').click();
    await page.waitForFunction(() => window.decide
      && window.decide.backendPhase === 'adoption-unavailable'
      && window.decide.adoptionError != null);

    const refusal = await page.textContent('#decideStage .emptycard.hero');
    assert.match(refusal, /no longer available in this market/,
      'the server\'s reason is shown verbatim');
    assert.match(refusal, /no substitute package was selected/,
      'and it states plainly that nothing was substituted');
    assert.match(refusal, /No fresh competition, package, payoff, paths, or order was generated/,
      'the terminal state names every financial artifact that remained absent');
    assert.equal(await page.locator('#decideStage .fanr[data-cand]').count(), 0,
      'no fresh comparison rows appear after the exact adoption is refused');
    assert.equal(await page.evaluate(() => window.decide.cands.length), 0,
      'the presentation retains no substitute candidate');
    assert.equal(backend.count('GET', `/api/plans/${PLAN_ID}/strategy/latest`), 0,
      'the normal strategy loader is never entered after a refused exact adoption');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/strategy/run`), 0,
      'the refusal cannot trigger a fresh competition');

    assert.deepEqual(pageErrors, [], `refused adoption emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('two structures on one symbol are two Scout rows, and each opens its own package', async () => {
  const context = await browser.newContext({ viewport: { width: 2560, height: 1440 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  await installBackend(page, {
    bookDocuments: populatedBookDocuments(),
    workspaceContext: { goal: 'INCOME', view: 'Neutral', horizonDays: 45, riskPosture: 'Balanced' }
  });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');

    // The frontier answers two different questions about ONE ticker. Both are real answers.
    await page.evaluate(() => {
      window.HOME_OPPORTUNITY = { phase: 'complete', error: null, progress: null, partial: [], data: {
        picks: [{ symbol: 'IWM', opportunity: { score: 74 }, signals: {}, bestIdea: {
          available: true, evaluationId: 'eval-spread', resultKey: 'key-spread',
          family: 'CREDIT_PUT_SPREAD', displayName: 'Bull put (credit) spread',
          economicVerdict: 'FAVORABLE', horizon: '45D', realizedVolEvAfterCostsCents: 3100
        } }],
        frontier: { destinationAccountId: 'acct-1', decisionRanking: [
          { symbol: 'IWM', strategy: 'CREDIT_PUT_SPREAD', economicVerdict: 'FAVORABLE',
            qualification: 'FAVORABLE', decisionScore: 74, evaluationId: 'eval-spread',
            identity: { key: 'key-spread', symbol: 'IWM', family: 'CREDIT_PUT_SPREAD',
              expiration: '2026-09-18' } },
          { symbol: 'IWM', strategy: 'COVERED_CALL', economicVerdict: 'FAVORABLE',
            qualification: 'FAVORABLE', decisionScore: 68, evaluationId: 'eval-covered',
            identity: { key: 'key-covered', symbol: 'IWM', family: 'COVERED_CALL',
              expiration: '2026-08-21' } }
        ] },
        skipped: [], notes: []
      } };
      window.authRenderOpportunityOnly();
    });
    await page.waitForSelector('.opportunityrow');

    const rows = await page.evaluate(() => Array.from(document.querySelectorAll('.opportunityrow'))
      .map(row => ({
        symbol: row.querySelector('strong').textContent.trim(),
        evaluation: row.getAttribute('data-auth-evaluation'),
        detail: row.querySelector('small').textContent.trim()
      })));
    assert.equal(rows.length, 2,
      'a bull put spread and a covered call on IWM are two answers, not one row that hides the other');
    assert.deepEqual(rows.map(row => row.evaluation), ['eval-spread', 'eval-covered'],
      'each row names its own evaluation, so clicking one cannot open the other');
    assert.deepEqual(rows.map(row => row.symbol), ['IWM', 'IWM']);
    assert.match(rows[0].detail, /Exp 2026-09-18/,
      'the row states the exact expiration the identity carries, rather than deriving one');
    assert.match(rows[1].detail, /Exp 2026-08-21/);

    assert.deepEqual(pageErrors, [], `multi-structure Scout emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('inline styles carry drawing data only — spacing and palette live in the stylesheet', () => {
  /*
   * Audit M3 acceptance. A component whose spacing lives at its call sites has as many grammars as
   * it has callers, which is how one concept ended up with several looks. Inline style is reserved
   * for values the STYLESHEET cannot know: a bar's computed width, a series' colour, a marker's
   * position. Everything else belongs to the component.
   */
  const html = fs.readFileSync(path.join(PUBLIC, 'index.html'), 'utf8');
  const DRAWING = /^(--[a-z-]+|width|height|left|right|top|bottom|background|background-color|stroke|fill|transform|opacity)$/;
  const offenders = [];
  for (const match of html.matchAll(/style="([^"]*)"/g)) {
    const declaration = match[1];
    // A declaration built from an expression is drawing data by construction — it interpolates a
    // computed number or colour. Only fully static declarations are judged property by property.
    if (declaration.includes("'+") || declaration.includes("+'")) {
      if (!DRAWING.test(declaration.split(':')[0].trim().replace(/^.*?([a-z-]+)$/, '$1'))
          && !/(width|left|right|top|bottom|background|--)/.test(declaration)) {
        offenders.push(declaration);
      }
      continue;
    }
    for (const rule of declaration.split(';').map(part => part.trim()).filter(Boolean)) {
      const property = rule.split(':')[0].trim();
      if (!DRAWING.test(property)) offenders.push(rule);
    }
  }
  assert.deepEqual(offenders, [],
    'these inline declarations are presentation, not drawing data, and belong in app.css:\n  '
    + offenders.join('\n  '));
});

test('one position reports one set of greeks, in one grammar, at rest and mid-scenario', async () => {
  /*
   * A held position had THREE greeks grammars on one screen: two hand-formatted .authmetric cells
   * in the position panel (delta as "42.60 sh"), four more hand-formatted cells in the scenario
   * row (delta as "42.60", no unit), and the GSPEC grammar that renderAtPrice patches INTO that
   * same scenario row the moment a frame renders (delta as "+43 sh"). The cell therefore changed
   * its own grammar when the user touched the scrubber. All three read GSPEC now.
   */
  const context = await browser.newContext({ viewport: { width: 2560, height: 1440 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  await installBackend(page, { bookDocuments: goldenBookDocuments() });
  try {
    await page.goto(deskUrl);
    await waitForDeskBoot(page);
    await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
    await page.locator(`#book .card[data-id="${BOOK_TRADE_ID}"]`).click();
    await page.waitForSelector('[data-auth-position-detail] .mechg .mgt');
    await page.waitForSelector('[data-auth-position-detail] [data-position-scenario="ready"]');

    const GREEKS = ['delta', 'theta', 'vega', 'gamma'];
    const atRest = await page.evaluate(keys => ({
      panel: Array.from(document.querySelectorAll('[data-auth-position-detail] .mechg .mgt'))
        .map(tile => `${tile.querySelector('.mgk').textContent.trim()}=${tile.querySelector('.mgv').textContent.trim()}`),
      scenario: keys.map(key => {
        const cell = document.querySelector(`[data-auth-position-detail] .authscenmetric b[data-live="${key}"]`);
        return cell ? `${cell.closest('.authscenmetric').querySelector('span').textContent.trim()}=${cell.textContent.trim()}` : null;
      }),
      caption: (document.querySelector('[data-auth-position-detail] .scenmetriccap') || {}).textContent || null
    }), GREEKS);

    assert.equal(atRest.panel.length, 4, 'the position states all four greeks');
    assert.ok(atRest.scenario.every(Boolean), 'the scenario row states all four greeks');

    /* The two readings are DIFFERENT moments — the position as it stands, and the end of the
       selected story — so their numbers legitimately differ. What must not differ is the grammar,
       and the surface must say which moment each group reports. */
    assert.ok(/selected future/i.test(atRest.caption || ''),
      'the scenario metrics must name the moment they describe, or two identical greek labels read '
      + `as one number; the caption was ${JSON.stringify(atRest.caption)}.`);
    /* Compare the GRAMMAR: currency mark, unit, precision, label. The sign is data — one reading
       is positive and the other negative — so it is normalised away, along with the digits. */
    const shape = entry => entry
      .replace(/[+\-\u2212]/g, '')
      .replace(/[\d,]+\.\d+/g, '#.#').replace(/[\d,]+/g, '#');
    assert.deepEqual(atRest.scenario.map(shape).sort(), atRest.panel.map(shape).sort(),
      'both readings of one greek must use one format — same label, same unit, same precision. '
      + `Panel ${JSON.stringify(atRest.panel)} vs scenario ${JSON.stringify(atRest.scenario)}.`);

    // Now let a scenario frame patch those cells and confirm the grammar does not change under it.
    await page.evaluate(() => {
      const p = window.byId[Object.keys(window.byId)[0]];
      if (window.renderAtPrice && window.positionSurf) window.renderAtPrice(window.positionSurf(p));
    });
    const afterFrame = await page.evaluate(keys => keys.map(key => {
      const cell = document.querySelector(`[data-auth-position-detail] .authscenmetric b[data-live="${key}"]`);
      return cell ? `${cell.closest('.authscenmetric').querySelector('span').textContent.trim()}=${cell.textContent.trim()}` : null;
    }), GREEKS);
    assert.deepEqual(afterFrame.map(shape), atRest.scenario.map(shape),
      'a rendered scenario frame must not restate a resting greek in a different grammar; '
      + `at rest ${JSON.stringify(atRest.scenario)}, after one frame ${JSON.stringify(afterFrame)}.`);

    assert.deepEqual(pageErrors, [], `greeks grammar walk emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

/*
 * New Idea geometry across the §16.4 desktop widths and a phone.
 *
 * This lives beside the harness rather than duplicating the full plan/strategy/ensemble mock in a
 * second test file. Nothing on the canonical analysis surface is exempt from clipping, overlap,
 * or the wide-desktop single-scroll-owner contract.
 */
for (const viewport of [
  { width: 2560, height: 1440, name: '2560x1440', wide: true },
  { width: 2000, height: 963, name: '2000x963', wide: true },
  { width: 1920, height: 1080, name: '1920x1080', wide: true },
  { width: 1440, height: 900, name: '1440x900', wide: true },
  { width: 390, height: 844, name: '390x844', wide: false }
]) {
  test(`New Idea composes without clipping or a nested scroller at ${viewport.name}`, async () => {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    await installBackend(page, { bookDocuments: populatedBookDocuments() });
    try {
      await page.goto(deskUrl);
      await waitForDeskBoot(page);
      await page.waitForFunction(() => window.DeskBackend.state().book?.phase === 'ready');
      await startNewIdea(page, 'AMD');
      await page.waitForFunction(() => window.decide?.backendPhase === 'ready');
      await page.waitForTimeout(400);
      if (process.env.DESK_SHOTS) {
        await page.screenshot({ path: `shots/visual/idea-${viewport.name}.png` });
      }

      const geometry = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth
      }));
      assert.ok(geometry.scrollWidth <= geometry.clientWidth + 1,
        `New Idea scrolls the page sideways at ${viewport.name}: `
        + `${geometry.scrollWidth}px in ${geometry.clientWidth}px.`);

      const clipped = await page.evaluate(() => {
        const out = [];
        document.querySelectorAll('#decideStage *').forEach(el => {
          const style = getComputedStyle(el);
          if (style.display === 'none' || style.visibility === 'hidden') return;
          const box = el.getBoundingClientRect();
          if (box.width < 1 || box.height < 1) return;
          if (/(auto|scroll)/.test(style.overflowX + style.overflowY)) return;
          if (!/hidden/.test(style.overflow + style.overflowX + style.overflowY)) return;
          if (style.textOverflow === 'ellipsis') {
            const fullText = (el.textContent || '').trim().replace(/\s+/g, ' ');
            const title = (el.getAttribute('title') || '').trim().replace(/\s+/g, ' ');
            const aria = (el.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ');
            const owner = el.closest('button, a[href], [role="button"]');
            const ownerText = owner
              ? `${owner.getAttribute('title') || ''} ${owner.getAttribute('aria-label') || ''}`
                .trim().replace(/\s+/g, ' ')
              : '';
            if ((title && title.length >= fullText.length)
                || (aria && aria.length >= fullText.length)
                || (ownerText && ownerText.length >= fullText.length)) return;
          }
          // Drawing surfaces size themselves to their viewBox; their overflow is not a lost fact.
          if (/^(svg|canvas)$/i.test(el.tagName)) return;
          if (el.scrollWidth - el.clientWidth <= 2 && el.scrollHeight - el.clientHeight <= 2) return;
          out.push(`${el.tagName.toLowerCase()}.${String(el.className).trim().split(/\s+/).slice(0, 2).join('.')} `
            + `draws ${el.clientWidth}x${el.clientHeight} around ${el.scrollWidth}x${el.scrollHeight}`);
        });
        return out;
      });
      assert.deepEqual(clipped, [],
        `New Idea cuts content off at ${viewport.name}:\n  ${clipped.join('\n  ')}`);

      if (viewport.wide) {
        const nestedScrollers = await page.evaluate(() => {
          const out = [];
          document.querySelectorAll('#decideStage *').forEach(el => {
            const style = getComputedStyle(el);
            if (!/(auto|scroll)/.test(style.overflowX + style.overflowY)) return;
            if (el.scrollHeight - el.clientHeight <= 4) return;
            out.push(`${el.tagName.toLowerCase()}.${String(el.className).trim().split(/\s+/).slice(0, 2).join('.')} `
              + `shows ${el.clientHeight}px of ${el.scrollHeight}px`);
          });
          return out;
        });
        assert.deepEqual(nestedScrollers, [],
          `New Idea has nested scrolling at wide desktop ${viewport.name}:\n  `
          + nestedScrollers.join('\n  '));
      }

      // The review measured "the scenario panel and Market panel overlap by roughly 242px" at
      // 2000x963 and heading collisions at 2560x1440. Clipping and overlap are different failures:
      // a clip cuts a fact off, an overlap makes two facts illegible on top of each other, and no
      // overflow rule catches the second.
      const collisions = await page.evaluate(() => {
        // Leaf TEXT against leaf text, anywhere on the surface. Panel-box comparison missed this
        // entirely: the boxes tile correctly and their contents still land on top of each other.
        const nodes = [];
        document.querySelectorAll('#decideStage *').forEach(el => {
          if (el.children.length) return;
          const text = (el.textContent || '').trim();
          if (!text) return;
          const style = getComputedStyle(el);
          if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return;
          if (style.position === 'absolute' || style.position === 'fixed') return;
          if (el.closest('svg')) return;                      // drawing space, not text flow
          const box = el.getBoundingClientRect();
          if (box.width < 2 || box.height < 2) return;
          nodes.push({ text, box, el });
        });
        const hits = [];
        for (let i = 0; i < nodes.length; i++) {
          for (let j = i + 1; j < nodes.length; j++) {
            const a = nodes[i], b = nodes[j];
            if (a.el.contains(b.el) || b.el.contains(a.el)) continue;
            const x = Math.min(a.box.right, b.box.right) - Math.max(a.box.left, b.box.left);
            const y = Math.min(a.box.bottom, b.box.bottom) - Math.max(a.box.top, b.box.top);
            if (x > 2 && y > 2) {
              const where = box => `${Math.round(box.top)}..${Math.round(box.bottom)}`;
              hits.push(`"${a.text.slice(0, 26)}" [${a.el.className || a.el.tagName} ${where(a.box)}] `
                + `over "${b.text.slice(0, 26)}" [${b.el.className || b.el.tagName} ${where(b.box)}] `
                + `(${Math.round(x)}x${Math.round(y)}px)`);
            }
          }
        }
        return hits;
      });
      assert.deepEqual(collisions, [],
        `New Idea prints one panel over another at ${viewport.name}:\n  ${collisions.join('\n  ')}`);

      assert.deepEqual(pageErrors, [],
        `New Idea emitted page errors at ${viewport.name}: ${pageErrors.join('\n')}`);
    } finally {
      await context.close();
    }
  });
}

test('candidate capital names the receipt it came from, and its absence carries a reason', async () => {
  /* Candidate capital and exact order-fit are separate receipts. Candidate rows may state their
     ranked capital, but Fit/governor headroom is unavailable until the selected exact preview
     publishes its own selectedCapital receipt. */
  const asCashSecuredPut = (row, id, label) => {
    row.id = id;
    row.label = label;
    row.displayName = label;
    row.strategy = 'CASH_SECURED_PUT';
    row.identity = positionIdentity({
      family: 'CASH_SECURED_PUT',
      label: 'Cash-secured put',
      summary: 'A short put backed by strike cash collateral.',
      fundingClass: 'CASH_COLLATERAL',
      capitalBasis: 'STRIKE_CASH_COLLATERAL'
    });
    row.legs = [{
      type: 'PUT', action: 'SELL', positionEffect: 'OPEN', ratio: 1,
      multiplier: 100, strike: 90, expiration: '2026-08-21', entryPrice: 2
    }];
    return row;
  };
  const zero = asCashSecuredPut(candidate(), 'candidate_cap_zero', 'Zero incremental capital');
  zero.evaluation.capital = { incrementalCents: 0 };
  const substituted = candidate();
  asCashSecuredPut(substituted, 'candidate_cap_substituted', 'Max loss is not capital');
  delete substituted.evaluation.capital;      // no capital receipt; maxLossCents 12345 survives
  const absent = candidate();
  absent.id = 'candidate_cap_absent';
  absent.label = 'Capital unavailable';
  absent.displayName = absent.label;
  delete absent.evaluation.capital;
  absent.maxLossCents = null;
  absent.evaluation.risk.terminalPayoff = {
    available: false,
    points: [],
    unavailableReason: 'Exact loss boundary is unavailable because one leg has no executable quote.'
  };
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    strategyCandidates: [candidate(), zero, substituted, absent]
  });
  try {
    await page.waitForSelector(`.fanr[data-cand="${absent.id}"]`);
    const state = await page.evaluate(ids => {
      const byId = id => window.decide.cands.find(row => row.id === id);
      const text = html => {
        const host = document.createElement('div');
        host.innerHTML = html;
        return {
          text: host.textContent.replace(/\s+/g, ' ').trim(),
          sliders: host.querySelectorAll('input[type="range"]').length,
          disabledSliders: host.querySelectorAll('input[type="range"]:disabled').length
        };
      };
      window.drawDecMap();
      const plotted = window.DEC_MPOS.map(row => row.id);
      const prior = window.decide.candId;
      const known = byId(ids.known);
      const knownFit = text(window.fitBudgetPanel(known));
      const knownGovernors = text(window.governorsPanel());
      const missing = byId(ids.substituted);
      window.decide.candId = ids.substituted;
      const missingFit = text(window.fitBudgetPanel(missing));
      const missingGovernors = text(window.governorsPanel());
      const zero = byId(ids.zero);
      window.decide.candId = ids.zero;
      const zeroFit = text(window.fitBudgetPanel(zero));
      const zeroGovernors = text(window.governorsPanel());
      window.decide.candId = prior;
      return {
        rows: [ids.known, ids.zero, ids.substituted, ids.absent].map(id => {
          const c = byId(id);
          const cell = document.querySelector(`.fanr[data-cand="${id}"] .fcol-cap`);
          const maxLossCells = document.querySelectorAll(`.fanr[data-cand="${id}"] .fnum`);
          return {
            id, cap: c.cap, capAuthority: c.capAuthority,
            capUnavailableReason: c.capUnavailableReason,
            maxLossUnavailableReason: c.maxLossUnavailableReason,
            capitalText: cell?.textContent.trim(),
            capitalTitle: cell?.getAttribute('title'),
            maxLossTitle: maxLossCells[1]?.getAttribute('title')
          };
        }),
        plotted, knownFit, knownGovernors, missingFit, missingGovernors, zeroFit, zeroGovernors
      };
    }, { known: CANDIDATE_ID, zero: zero.id, substituted: substituted.id, absent: absent.id });

    const [knownRead, zeroRead, substitutedRead, absentRead] = state.rows;
    assert.ok(knownRead && zeroRead && substitutedRead && absentRead,
      'all nullable-capital states must reach the desk model');

    assert.equal(knownRead.cap, 123.45);
    assert.equal(knownRead.capAuthority, 'CAPITAL_INCREMENTAL');
    assert.equal(knownRead.capUnavailableReason, null);

    assert.equal(zeroRead.cap, 0, 'a genuine backend zero remains distinct from absence');
    assert.equal(zeroRead.capAuthority, 'CAPITAL_INCREMENTAL');
    assert.equal(zeroRead.capitalText, '$0');
    assert.ok(state.plotted.includes(zero.id), 'a genuine zero-capital candidate remains drawable');
    assert.match(zeroRead.capitalTitle, /incremental capital/i);
    assert.match(state.knownFit.text, /\$123\.45/i,
      'the selected exact preview publishes its own account-fit amount');
    assert.match(state.knownFit.text, /remains after this package/i);
    assert.match(state.knownGovernors.text, /ties up \$123\.45/i);

    assert.match(state.zeroFit.text, /unavailable/i,
      'a different candidate cannot borrow the selected package’s account-fit receipt');
    assert.match(state.zeroFit.text, /exact order preview/i);
    assert.match(state.zeroGovernors.text, /ties up unavailable/i);
    assert.doesNotMatch(state.zeroFit.text, /\$0|within cap|remaining \$|over by/i,
      'the browser must not derive headroom from the candidate’s genuine zero-capital receipt');

    assert.equal(substitutedRead.cap, null,
      'max loss may not silently stand in for a missing capital receipt');
    assert.equal(substitutedRead.capAuthority, null);
    assert.match(substitutedRead.capUnavailableReason, /Maximum loss remains available separately/i);
    assert.equal(substitutedRead.capitalText, '—');
    assert.match(substitutedRead.capitalTitle, /not substituted for capital/i);
    assert.equal(state.plotted.includes(substituted.id), false,
      'unknown capital cannot originate plot geometry');
    assert.equal(state.missingFit.text,
      'Collateral requiredunavailableThe exact order preview has not supplied an account-fit receipt yet. No cap fit or remaining headroom is calculated.');
    assert.match(state.missingFit.text, /No cap fit or remaining headroom is calculated/i);
    assert.doesNotMatch(state.missingFit.text, /\$0|within cap|remaining \$|over by/i);
    assert.match(state.missingGovernors.text, /ties up unavailable/i);
    assert.match(state.missingGovernors.text, /No cap fit or headroom is calculated/i);
    assert.equal(state.missingGovernors.sliders, 5,
      'unknown package risk does not hide the user-owned screens and caps');
    assert.equal(state.missingGovernors.disabledSliders, 0,
      'all five controls are enforced by the one typed strategy-ranking request');
    assert.match(state.missingGovernors.text, /Package capital cap/i);
    assert.match(state.missingGovernors.text, /Max market-crash loss/i);
    assert.doesNotMatch(state.missingGovernors.text, /\$0|within cap|over by/i);

    assert.equal(absentRead.cap, null);
    assert.equal(absentRead.capAuthority, null);
    assert.equal(absentRead.capitalText, '—');
    assert.match(absentRead.maxLossUnavailableReason, /one leg has no executable quote/i);
    assert.match(absentRead.maxLossTitle, /one leg has no executable quote/i,
      'the rendered unavailable max loss carries the backend reason');

    assert.deepEqual(pageErrors, [],
      `candidate capital states emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});
