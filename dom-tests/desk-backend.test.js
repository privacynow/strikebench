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
const ACCOUNT_ID = 'account_desk_test';
const DATASET_ID = 'dataset-desk-test';
const WORLD_REVISION = 41;
const WORLD_EPOCH = 'observed-epoch-desk-41';

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
  const pathname = requestUrl.pathname === '/' ? '/desk.html' : decodeURIComponent(requestUrl.pathname);
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
  deskUrl = `http://127.0.0.1:${server.address().port}/desk.html`;
});

after(async () => {
  if (browser) await browser.close();
  if (server) await new Promise(resolve => server.close(resolve));
});

function plan(version, overrides = {}) {
  return {
    id: PLAN_ID,
    version,
    open: true,
    status: 'ACTIVE',
    symbol: 'AMD',
    intent: 'INCOME',
    marketKind: 'OBSERVED',
    worldId: overrides.worldId === undefined ? null : overrides.worldId,
    accountId: ACCOUNT_ID,
    context: {
      thesis: 'neutral',
      horizonDays: 1,
      riskMode: overrides.riskMode || 'balanced',
      rev: 7
    }
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
    custom: false
  }, overrides);
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
        economics: { marketEvAfterCostsCents: 1450 }
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
        economics: { marketEvAfterCostsCents: 2121 }
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
  const shift = spot - 100;
  const ensembleId = market.id || ENSEMBLE_ID;
  const fingerprint = market.fingerprint || ENSEMBLE_FINGERPRINT;
  const asOf = new Date(market.asOf == null ? 1784563200000 : market.asOf).toISOString();
  const atmIv = market.atmIv == null ? 0.30 : market.atmIv;
  const expiration = market.expiration || '2026-08-21';
  const shifted = rows => rows.map(value => value + shift);
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
      samples: [
        shifted([100, 99, 102, 104]),
        shifted([100, 101, 103, 106]),
        shifted([100, 98, 101, 103])
      ],
      bands: [
        { p10: 100, p50: 100, p90: 100 },
        { p10: 98, p50: 101, p90: 103 },
        { p10: 99, p50: 102, p90: 106 },
        { p10: 101, p50: 104, p90: 108 }
      ],
      receipt: {
        symbol: 'AMD',
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
        underlying: [
          { day: 0, p10: 100, p50: 100, p90: 100, focusPrice: 100, atmIv: 0.30 },
          { day: 21, p10: 101, p50: 104, p90: 108, focusPrice: 104, atmIv: 0.29 }
        ],
        positions: [{
          key: `PROPOSED:${CANDIDATE_ID}`,
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
      freshness: 'FRESH',
      evidence: { source: 'BACKEND_TEST_RECEIPT', lane: 'OBSERVED' },
      payoff: isCustom ? customTradePreview({ legs: selected.legs }).preview.payoff : [
        { price: 90, profitCents: -99900 },
        { price: 100, profitCents: 77700 },
        { price: 110, profitCents: 22200 }
      ]
    },
    evaluation: {},
    guardrails: { level: 'PASS', blockReasons: [], warnings: [] },
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
      waypoints: [{ dayIndex: 10, priceRatio: marker === 'second' ? 1.05 : 0.95, tolerance: 0.02 }]
    }
  }, overrides.receipt || {});
  return {
    testMarker: marker,
    plan: plan(14),
    ensemble: { id: ENSEMBLE_ID, fingerprint: ENSEMBLE_FINGERPRINT },
    receipt,
    paths: {
      totalPathCount: 500,
      paths: [
        { sourcePathIndex: 9, role: 'CONTEXT', prices: [100, 101, 102] },
        { sourcePathIndex: focusSourcePathIndex, role: 'FOCUS', prices: [100, 103, 105] },
        { sourcePathIndex: 31, role: 'CONTEXT', prices: [100, 102, 104] }
      ],
      receipt: {
        sourcePathCount: 500,
        returnedPathCount: 3,
        withinToleranceCount: 18,
        focusSourcePathIndex
      }
    },
    checkpoints: {
      focusSourcePathIndex,
      modelReceipt: { valuationFingerprint },
      underlying: [
        { day: 0, p10: 100, p50: 100, p90: 100, focusPrice: 100, atmIv: 0.30 },
        { day: 10, p10: 98, p50: 102, p90: 106, focusPrice: marker === 'second' ? 105 : 95, atmIv: 0.32 }
      ],
      underlyingSteps: [
        { step: 0, sessionProgress: 0, focusPrice: 100, atmIv: 0.30 },
        { step: 1, sessionProgress: 5, focusPrice: marker === 'second' ? 103 : 97, atmIv: 0.31 },
        { step: 2, sessionProgress: 10, focusPrice: marker === 'second' ? 105 : 95, atmIv: 0.32 }
      ],
      positions: [{
        key: `PROPOSED:${CANDIDATE_ID}`,
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

async function installBackend(page, options = {}) {
  const requests = [];
  let scenarioCalls = 0;
  let planVersion = 10;
  let selectedCandidate = null;
  const strategyCandidates = options.strategyCandidates || [candidate()];
  let hasCompetition = false;
  let strategyRunNumber = 0;
  let strategyRunId = null;
  const strategyInputHash = 'f'.repeat(64);
  let quote = {
    symbol: 'AMD', bid: 99, ask: 101, last: 100,
    source: 'BACKEND_TEST_RECEIPT', freshness: 'FRESH', asOf: 1784563200000,
    evidence: { source: 'BACKEND_TEST_RECEIPT', lane: 'OBSERVED', provenance: 'OBSERVED' }
  };
  let chainIv = 0.30;
  let chainAsOf = 1784563200000;
  let ensembleGeneration = 0;
  let storedEnsemble = null;
  let storedOutcomes = [];
  let researchFailuresRemaining = options.failResearchOnce ? 1 : 0;
  const planOverrides = {
    worldId: options.observedWorldId === undefined ? null : options.observedWorldId,
    riskMode: options.planRiskMode || 'balanced'
  };

  function currentPlan(version = planVersion) {
    return plan(version, planOverrides);
  }

  function strategyState() {
    return {
      runId: strategyRunId,
      state: 'CURRENT',
      inputHash: strategyInputHash,
      createdAt: '2026-07-20T16:00:00Z',
      result: {
        candidates: strategyCandidates,
        rejected: [],
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

    let response;
    if (method === 'GET' && url.pathname === '/api/config') {
      response = {
        fixturesOnly: false,
        world: 'observed',
        activeDataset: DATASET_ID,
        activeDatasetName: 'Desk observed test dataset',
        marketLane: 'OBSERVED',
        scenarioMode: false
      };
    } else if (method === 'GET' && url.pathname === '/api/status') {
      response = { ok: true, status: 'READY', fixturesOnly: false };
    } else if (method === 'GET' && url.pathname === '/api/world') {
      response = {
        world: 'observed',
        revision: WORLD_REVISION,
        epoch: WORLD_EPOCH
      };
    } else if (method === 'GET' && url.pathname === '/api/account') {
      response = { account: { id: ACCOUNT_ID, name: 'Desk practice account' } };
    }
    else if (method === 'GET' && url.pathname === '/api/quotes') {
      response = {
        marketLane: 'OBSERVED',
        world: 'observed',
        quotes: [Object.assign({}, quote, { refreshing: false })]
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
        displayPrice: (quote.bid + quote.ask) / 2,
        marketLane: 'OBSERVED',
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
      response = {
        underlying: 'AMD', expiration: '2026-08-21',
        source: 'BACKEND_TEST_RECEIPT', freshness: 'FRESH', asOfEpochMs: chainAsOf,
        evidence: {
          source: 'BACKEND_TEST_RECEIPT', lane: 'OBSERVED', provenance: 'OBSERVED'
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
      response = { world: 'observed', market: 'OBSERVED', plans: [currentPlan()] };
    } else if (method === 'GET' && url.pathname === `/api/plans/${PLAN_ID}`) {
      response = currentPlan();
    } else if (method === 'POST' && url.pathname === '/api/plans') {
      // Production create is idempotent by canonical active-Plan identity. In particular, a
      // different risk default resumes the existing Plan and preserves its persisted posture.
      response = currentPlan();
    } else if (method === 'GET' && url.pathname === `/api/plans/${PLAN_ID}/strategy/latest`) {
      response = hasCompetition ? {
        strategy: strategyState(),
        ...(selectedCandidate ? { selected: selectedCandidate } : {})
      } : {};
    } else if (method === 'POST' && url.pathname === `/api/plans/${PLAN_ID}/strategy/run`) {
      if (selectedCandidate && strategyCandidates.some(row => row.id === selectedCandidate.id)) {
        selectedCandidate = null;
      }
      strategyRunNumber += 1;
      strategyRunId = `strategy_run_${strategyRunNumber}`;
      hasCompetition = true;
      response = { plan: currentPlan(), strategy: strategyState() };
    } else if (method === 'PUT' && url.pathname === `/api/plans/${PLAN_ID}/strategy/select`) {
      if (options.selectDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.selectDelayMs));
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
      response = customTradePreview(body);
    } else if (method === 'POST' && url.pathname === `/api/plans/${PLAN_ID}/strategy/custom`) {
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
    } else if (method === 'POST' && url.pathname === `/api/plans/${PLAN_ID}/outcomes/ensemble/paths`) {
      scenarioCalls += 1;
      const terminal = body.waypoints[body.waypoints.length - 1].priceRatio;
      if (terminal < 1) {
        await new Promise(resolve => setTimeout(resolve, 180));
        response = scenarioResponse('first');
      } else if (terminal > 1.08) {
        response = scenarioResponse('invalid', {
          receipt: { ensembleFingerprint: 'wrong-ensemble-fingerprint' }
        });
      } else {
        response = scenarioResponse('second');
      }
    } else if (method === 'GET' && url.pathname === `/api/plans/${PLAN_ID}/outcomes/ensemble/latest`) {
      if (!options.latestEnsembleEnabled || !storedEnsemble) {
        await route.fulfill({ status: 404, contentType: 'application/json',
          body: JSON.stringify({ error: 'No current stored ensemble.' }) });
        return;
      }
      response = JSON.parse(JSON.stringify(storedEnsemble));
      response.preview.marketImplied.atmIv = chainIv;
      response.preview.marketImplied.expiration = '2026-08-21';
      response.plan = currentPlan();
    } else if (method === 'GET' && url.pathname === `/api/plans/${PLAN_ID}/outcomes/latest`) {
      response = { plan: currentPlan(), outcomes: storedOutcomes };
    } else if (method === 'POST' && url.pathname === `/api/plans/${PLAN_ID}/outcomes/ensemble`) {
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
        id, fingerprint, spot: mark, asOf: quote.asOf, atmIv: chainIv, expiration: '2026-08-21'
      });
      response = storedEnsemble;
    } else if (method === 'POST' && url.pathname === `/api/plans/${PLAN_ID}/outcomes/run`) {
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
      storedOutcomes.push(savedOutcome);
      response = {
        plan: currentPlan(),
        ensemble: {
          id: body.ensembleId,
          fingerprint,
          basis: 'PARAMETRIC'
        },
        outcome: savedOutcome
      };
    } else if (method === 'POST' && url.pathname === `/api/plans/${PLAN_ID}/decision/preview`) {
      response = decisionPreview(body, selectedCandidate, planVersion);
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
    } else if (method === 'POST' && url.pathname === `/api/plans/${PLAN_ID}/decision/trade`) {
      if (options.commitDelayMs) {
        await new Promise(resolve => setTimeout(resolve, options.commitDelayMs));
      }
      planVersion += 1;
      response = { plan: currentPlan(), decision: { state: 'COMMITTED' }, trade: { id: 'trade-desk-test' } };
    } else {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: `Unhandled deterministic Desk route: ${method} ${url.pathname}` })
      });
      return;
    }

    if (response && response.plan && response.plan.id === PLAN_ID) {
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
  await page.waitForSelector('#stage.lv-book #homeRiskMap');
  await page.locator('#threadNewIdea').click();
  try {
    await page.waitForFunction(id => window.decide
      && window.decide.backendPhase === 'ready'
      && window.decide.candId === id
      && window.decide.orderPreview, CANDIDATE_ID, { timeout: 10000 });
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

test('an interrupted authoritative load renders one actionable state and retries in place', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { failResearchOnce: true });
  try {
    await page.goto(deskUrl);
    await page.waitForSelector('#stage.lv-book #homeRiskMap');
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
        positionIdentity: active.positionIdentity,
        deskPickId: window.decide.deskPickId,
        pickBadgeCandidate: document.querySelector('.fanr .pickbadge')?.closest('.fanr')?.dataset.cand,
        fanSummary: document.querySelector('.dcleft .modebar .hint')?.textContent,
        scenarioHint: document.querySelector('.scenpanel > .lenshd .hint')?.textContent,
        maxStoredPathSegments: Math.max(0, ...Array.from(document.querySelectorAll('#mcFan path[d]'))
          .map(path => (path.getAttribute('d').match(/L/g) || []).length))
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
    assert.deepEqual(rendered.positionIdentity, positionIdentity(),
      'candidate risk and structure labels come from the canonical backend catalog receipt');
    assert.equal(rendered.deskPickId, CANDIDATE_ID);
    assert.equal(rendered.pickBadgeCandidate, CANDIDATE_ID,
      'Desk Pick is bound to the coherent backend assessment, not a row index');
    assert.match(rendered.fanSummary, /1 fit/);
    assert.match(rendered.scenarioHint, /selected outcome stored/,
      'preserving scenario DOM identity still refreshes its authoritative receipt state');
    assert.ok(rendered.maxStoredPathSegments > 2,
      'the visible stored trajectories retain a multi-checkpoint stochastic journey');
    assert.equal(backend.count('POST', '/api/plans'), 0,
      'the exact matching Plan is reused instead of creating a duplicate');
    assert.equal(backend.count('POST', '/api/strategies/identify'), 0,
      'a candidate that already carries canonical identity does not require fallback classification');

    const marketPreview = backend.requests.find(row =>
      row.method === 'POST' && row.path === `/api/plans/${PLAN_ID}/decision/preview`);
    assert.deepEqual(marketPreview.body.orderInstruction, { type: 'MARKET', timeInForce: 'DAY' },
      'market is a typed execution instruction, separate from debit economics');
    assert.equal(Object.hasOwn(marketPreview.body, 'proposedNetCents'), false,
      'the bridge does not overload signed economics as an instruction');

    for (const viewport of [
      { width: 1280, height: 800 },
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

test('unavailable execution preserves candidate economics without promoting zero sentinels', async () => {
  const { context, page, pageErrors } = await openAuthoritativeDesk({
    unavailableDecisionPreview: true
  });
  try {
    const rendered = await page.evaluate(candidateId => {
      const active = window.decide.cands.find(row => row.id === candidateId);
      const dock = document.querySelector('.execute');
      return {
        payoffPoints: active.payoffPoints,
        payoffAtSpot: window.payFor(active, 100),
        payoffPathCount: document.querySelectorAll('#decPay path[d]').length,
        dockText: dock?.textContent.replace(/\s+/g, ' ').trim(),
        reviewDisabled: dock?.querySelector('[data-dec="review"]')?.disabled,
        orderState: window.decide.orderPreview.order.executability,
        valuedNetCents: window.decide.orderPreview.order.valuedNetCents
      };
    }, CANDIDATE_ID);

    assert.equal(rendered.payoffAtSpot, 777);
    assert.equal(rendered.payoffPoints.length, 3,
      'the preview-independent evaluation payoff remains active');
    assert.ok(rendered.payoffPathCount > 0,
      'an unavailable execution book cannot blank the candidate payoff chart');
    assert.equal(rendered.orderState, 'UNAVAILABLE');
    assert.equal(rendered.valuedNetCents, null);
    assert.match(rendered.dockText, /candidate −\$123 debit · execution unavailable/i);
    assert.match(rendered.dockText, /book unavailable/i);
    assert.match(rendered.dockText, /Cannot execute AMD from a stale observed option book/i);
    assert.doesNotMatch(rendered.dockText, /backend proposed \+\$0|collect \+\$0|pay \+\$0/i);
    assert.equal(rendered.reviewDisabled, true);
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
    assert.equal(backend.count('GET', '/api/quotes'), 0,
      'the ambient market-engine snapshot is not mixed into Plan outcomes');
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
        candidateId: state.selected.id
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
        instruction: window.decide.orderPreview.order.orderInstruction.type
      };
    }, CANDIDATE_ID);
    assert.deepEqual(Object.assign({}, candidateAfter, { instruction: undefined }),
      Object.assign({}, candidateBefore, { instruction: undefined }),
      'LIMIT preview stays in the execution dock and cannot replace candidate/outcome economics');
    assert.equal(candidateAfter.instruction, 'LIMIT');

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
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({ latestEnabled: true });
  try {
    const ensemblePath = `/api/plans/${PLAN_ID}/outcomes/ensemble`;
    const outcomePath = `/api/plans/${PLAN_ID}/outcomes/run`;
    const customPath = `/api/plans/${PLAN_ID}/strategy/custom`;
    const ensembleBefore = backend.count('POST', ensemblePath);
    const outcomeBefore = backend.count('POST', outcomePath);

    const invalid = await page.evaluate(async candidateId => {
      await window.DeskBackend.previewDraft([{
        t: 'c', k: 100, q: 1, expiration: '2026-08-21', multiplier: 100
      }], candidateId);
      const state = window.DeskBackend.state();
      return {
        selectedId: state.selected.id,
        visibleSelectedId: window.decide.candId,
        valid: state.draft.valid,
        error: state.draft.error,
        ensembleId: state.ensemble.ensemble.id
      };
    }, CANDIDATE_ID);

    assert.equal(invalid.selectedId, CANDIDATE_ID,
      'a blocked analysis cannot replace the selected recommendation');
    assert.equal(invalid.visibleSelectedId, CANDIDATE_ID,
      'the prior candidate remains visible while an invalid draft is explained');
    assert.equal(invalid.valid, false);
    assert.match(invalid.error, /one-leg draft is blocked/i);
    assert.equal(invalid.ensembleId, ENSEMBLE_ID);
    assert.equal(backend.count('POST', customPath), 0,
      'invalid pure previews never invoke the mutating custom-selection route');

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
      dock: document.querySelector('.execute')?.textContent
    }));
    assert.equal(rendered.risk, 'unknown');
    assert.match(rendered.riskTitle, /classification unavailable/i);
    assert.equal(rendered.reviewDisabled, true);
    assert.match(rendered.dock, /guardrails block this exact package/i,
      'execution pricing and placement guardrails remain separately visible');
    assert.match(rendered.dock, /exceeds the backend loss limit/i);

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

test('the initial backend selection cannot be superseded by a second idea load', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { selectDelayMs: 180 });
  try {
    await page.goto(deskUrl);
    await page.waitForSelector('#stage.lv-book #homeRiskMap');
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
    await page.waitForSelector('#stage.lv-book #homeRiskMap');
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
    await page.waitForSelector('#stage.lv-book #homeRiskMap');
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

test('a quote rollover during fan generation converges on one fresh authoritative snapshot', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { rollQuoteOnFirstEnsemble: true });
  try {
    await page.goto(deskUrl);
    await page.waitForSelector('#stage.lv-book #homeRiskMap');
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide && window.decide.backendPhase === 'ready', null,
      { timeout: 10000 });
    const state = await page.evaluate(() => {
      const bridge = window.DeskBackend.state();
      return {
        error: bridge.error && bridge.error.message,
        ensembleId: bridge.ensemble && bridge.ensemble.ensemble.id,
        outcomeEnsembleId: bridge.outcome && bridge.outcome.outcome.ensembleId,
        pending: bridge.mutationPending
      };
    });
    assert.equal(state.error, null);
    assert.equal(state.ensembleId, `${ENSEMBLE_ID}_2`);
    assert.equal(state.outcomeEnsembleId, state.ensembleId);
    assert.equal(state.pending, false);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 2,
      'the mixed fan is discarded and rebuilt after recapturing the quote');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 1,
      'only the fresh fan receives a selected-package valuation');
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 1,
      'execution preview is created only after the coherent retry reaches ready');
    assert.deepEqual(pageErrors, [], `mid-flight market convergence emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('an option-surface rollover during fan generation converges before valuation', async () => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  const backend = await installBackend(page, { rollChainOnFirstEnsemble: true });
  try {
    await page.goto(deskUrl);
    await page.waitForSelector('#stage.lv-book #homeRiskMap');
    await page.locator('#threadNewIdea').click();
    await page.waitForFunction(() => window.decide && window.decide.backendPhase === 'ready', null,
      { timeout: 10000 });
    const state = await page.evaluate(() => {
      const bridge = window.DeskBackend.state();
      return {
        error: bridge.error && bridge.error.message,
        ensembleId: bridge.ensemble && bridge.ensemble.ensemble.id,
        outcomeEnsembleId: bridge.outcome && bridge.outcome.outcome.ensembleId,
        pending: bridge.mutationPending
      };
    });
    assert.equal(state.error, null);
    assert.equal(state.ensembleId, `${ENSEMBLE_ID}_2`);
    assert.equal(state.outcomeEnsembleId, state.ensembleId);
    assert.equal(state.pending, false);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/ensemble`), 2);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/outcomes/run`), 1);
    assert.equal(backend.count('POST', `/api/plans/${PLAN_ID}/decision/preview`), 1);
    assert.deepEqual(pageErrors, [], `mid-flight option-surface convergence emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});
