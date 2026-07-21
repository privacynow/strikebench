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
    id: overrides.id || PLAN_ID,
    version,
    open: true,
    status: 'ACTIVE',
    symbol: 'AMD',
    intent: 'INCOME',
    marketKind: overrides.marketKind || 'OBSERVED',
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
        economics: {
          verdict: 'FAVORABLE',
          placement: 'WORTH_INVESTIGATING',
          label: 'Worth investigating',
          marketEvAfterCostsCents: 1450,
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
    marketEvAfterCostsCents: -37000,
    observedEvidence: true
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
  let activeWorld = 'observed';
  let activeMarketLane = 'OBSERVED';
  let activeDataset = DATASET_ID;
  let worldRevision = WORLD_REVISION;
  let worldEpoch = WORLD_EPOCH;
  let simulatedPlanCreated = false;
  let simulatedSession = null;
  const marketFreshness = options.marketFreshness || 'FRESH';
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
  let quote = {
    symbol: 'AMD', bid: 99, ask: 101, last: 100,
    source: 'BACKEND_TEST_RECEIPT', freshness: marketFreshness, asOf: 1784563200000,
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

  function currentPlanId() {
    return activeWorld === 'observed' ? PLAN_ID : SIM_PLAN_ID;
  }

  function currentProvenance() {
    return activeMarketLane === 'SIMULATED' ? 'SIMULATED' : 'OBSERVED';
  }

  function currentPlan(version = planVersion) {
    return plan(version, Object.assign({}, planOverrides, {
      id: currentPlanId(),
      marketKind: activeWorld === 'observed' ? 'OBSERVED' : 'SIMULATED',
      worldId: activeWorld === 'observed' ? planOverrides.worldId : activeWorld
    }));
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

    let response;
    if (method === 'GET' && url.pathname === '/api/config') {
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
      response = { account: { id: ACCOUNT_ID, name: 'Desk practice account' } };
    }
    else if (method === 'GET' && url.pathname === '/api/quotes') {
      response = {
        marketLane: activeMarketLane,
        world: activeWorld,
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
      response = {
        underlying: 'AMD', expiration: '2026-08-21',
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
        plans: activeWorld === 'observed' || simulatedPlanCreated ? [currentPlan()] : []
      };
    } else if (method === 'GET' && url.pathname === activePlanPath) {
      response = currentPlan();
    } else if (method === 'POST' && url.pathname === '/api/plans') {
      // Production create is idempotent by canonical active-Plan identity. In particular, a
      // different risk default resumes the existing Plan and preserves its persisted posture.
      if (activeWorld !== 'observed') simulatedPlanCreated = true;
      response = currentPlan();
    } else if (method === 'GET' && url.pathname === `${activePlanPath}/strategy/latest`) {
      response = hasCompetition ? {
        strategy: strategyState(),
        ...(selectedCandidate ? { selected: selectedCandidate } : {})
      } : {};
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/strategy/run`) {
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
        id, fingerprint, spot: mark, asOf: quote.asOf, atmIv: chainIv, expiration: '2026-08-21'
      });
      storedEnsemble.plan = currentPlan(planVersion);
      storedEnsemble.preview.receipt.worldId = activeWorld;
      storedEnsemble.preview.receipt.datasetId = activeDataset;
      storedEnsemble.preview.receipt.anchorSource = quote.source;
      storedEnsemble.preview.receipt.anchorFreshness = quote.freshness;
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
    } else if (method === 'POST' && url.pathname === `${activePlanPath}/decision/preview`) {
      response = decisionPreview(body, selectedCandidate, planVersion);
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
      response = { plan: currentPlan(), decision: { state: 'COMMITTED' }, trade: { id: 'trade-desk-test' } };
    } else {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: `Unhandled deterministic Desk route: ${method} ${url.pathname}` })
      });
      return;
    }

    if (response && response.plan) {
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
        blockReasons: ['No liquid two-sided package fits the exact horizon.']
      }
    ],
    marketFreshness: 'STALE'
  });
  try {
    await page.goto(deskUrl);
    await page.waitForSelector('#stage.lv-book #homeRiskMap');
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
      'Desk Pick is bound to the coherent, favorable backend assessment, not a row index');
    assert.match(rendered.fanSummary, /1 endorsable/);
    assert.match(rendered.scenarioHint, /selected outcome stored/,
      'preserving scenario DOM identity still refreshes its authoritative receipt state');
    assert.ok(rendered.maxStoredPathSegments > 2,
      'the visible stored trajectories retain a multi-checkpoint stochastic journey');
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
        previewFreshness: window.decide.orderPreview.preview.freshness
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

test('adverse coherent economics stay visible as a selected comparison without becoming Desk Pick', async () => {
  const adverse = unfavorableCandidate();
  const { context, page, pageErrors, backend } = await openAuthoritativeDesk({
    strategyCandidates: [adverse],
    expectedCandidateId: adverse.id
  });
  try {
    const view = await page.evaluate(() => ({
      candidateIds: Array.from(document.querySelectorAll('.fanr[data-cand]')).map(row => row.dataset.cand),
      selectedId: window.decide.candId,
      deskPickId: window.decide.deskPickId,
      badges: document.querySelectorAll('.fanr .pickbadge').length,
      rankRead: document.querySelector('.modebar .hint')?.textContent.trim(),
      payoffTitle: document.querySelector('#decideStage .dccenter .paytitle .lbl')?.textContent.trim(),
      economicVerdict: window.DeskBackend.state().selected.evaluation.assessment.economics.verdict
    }));
    assert.deepEqual(view.candidateIds, [adverse.id],
      'the adverse package remains available for comparison');
    assert.equal(view.selectedId, adverse.id,
      'the highest backend-ranked comparison can remain selected when no endorsement exists');
    assert.equal(view.economicVerdict, 'UNFAVORABLE');
    assert.equal(view.deskPickId, null,
      'a coherent declaration fit is not promoted over an unfavorable economic verdict');
    assert.equal(view.badges, 0);
    assert.equal(view.rankRead, 'no endorsable pick · 1 comparison');
    assert.match(view.payoffTitle, /^Selected comparison payoff/,
      'the selected adverse package is labeled as a comparison rather than the recommendation');
    const selection = backend.requests.find(row => row.method === 'PUT'
      && row.path === `/api/plans/${PLAN_ID}/strategy/select`);
    assert.equal(selection.body.candidateId, adverse.id,
      'selection identity stays backend-owned even when no Desk Pick exists');
    assert.deepEqual(pageErrors, [], `adverse comparison flow emitted page errors: ${pageErrors.join('\n')}`);
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
