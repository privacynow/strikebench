'use strict';

/*
 * Packaged product journey. The source-served contract and visual lanes can prove exact mappings
 * and geometry, but only this lane proves that the jar we ship, its real routes, a migrated fresh
 * database and the browser compose into the same Home -> New Idea workflow.
 */

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { startPackagedApp } = require('./packaged-app');

let app;
let context;
let page;
let governedSymbol;

before(async () => {
  app = await startPackagedApp({ label: 'desk-home-to-new-idea' });
  ({ context, page } = await app.newPage({ width: 1920, height: 1080 }));
});

after(async () => {
  if (app) await app.stop();
  else if (context) await context.close();
});

async function waitForHome(targetPage = page) {
  await targetPage.waitForFunction(() => window.DeskBackend
    && window.StrikeBenchDesk
    && window.DeskBackend.state().book?.phase === 'ready'
    && document.querySelector('#board')
    && document.querySelector('#threadNewIdea'));
}

async function declareIdea(targetPage = page) {
  await targetPage.locator('[data-auth-scout-scope="broad"]').click();
  await targetPage.waitForFunction(() => window.HOME_SCOUT?.scope === 'broad');
  await targetPage.locator('[data-auth-scout-goal="INCOME"]').click();
  await targetPage.waitForFunction(() => window.HOME_SCOUT?.goal === 'INCOME');
  await targetPage.locator('[data-auth-workbench-view="neutral"]').click();
  await targetPage.waitForFunction(() => window.homeIdea?.view === 'neutral');
  await targetPage.locator('[data-auth-workbench-horizon="45"]').click();
  await targetPage.waitForFunction(() => window.homeIdea?.horizon === '45 trading days');
  await targetPage.locator('[data-auth-workbench-risk="balanced"]').click();
  await targetPage.waitForFunction(() => window.homeIdea?.riskMode === 'balanced');
}

async function openCanonicalIdea(targetPage) {
  await waitForHome(targetPage);
  await targetPage.locator('#threadNewIdea').click();
  await targetPage.waitForFunction(() =>
    document.activeElement?.hasAttribute('data-auth-workbench-query'));
  await declareIdea(targetPage);
  const governed = await targetPage.evaluate(() => ({
    symbols: authHomeWorkbenchSymbols(),
    universe: authoritativeUniverse(),
    phase: window.DeskBackend.state().universePhase,
    error: window.DeskBackend.state().universeError
  }));
  const symbol = governed.symbols[0];
  assert.match(symbol || '', /^[A-Z][A-Z0-9.-]*$/,
    `the packaged fixture universe must expose at least one governed ticker: ${JSON.stringify(governed)}`);
  const query = targetPage.locator('[data-auth-workbench-query]');
  await query.fill(symbol);
  await targetPage.locator(`[data-auth-workbench-symbol="${symbol}"]`).click();
  await targetPage.waitForFunction(expected => window.homeIdea?.symbol === expected
    && window.decide == null, symbol);
  await targetPage.waitForFunction(() =>
    document.querySelector('[data-auth-workbench-analyze]:not([disabled])'));
  await targetPage.locator('[data-auth-workbench-analyze]').click();

  await targetPage.waitForFunction(expected => window.DeskBackend.state().plan?.symbol === expected
    && window.decide
    && Array.isArray(window.decide.cands)
    && window.decide.cands.length > 0, symbol, { timeout: 90_000 });

  if (await targetPage.locator('.fanr').count()) {
    const phase = await targetPage.evaluate(() => window.decide.backendPhase);
    if (phase === 'comparison-required') await targetPage.locator('.fanr').first().click();
  }
  await targetPage.waitForFunction(() => window.decide?.backendPhase === 'ready'
    && window.decide?.candId
    && window.decide?.orderPreview
    && document.querySelector('#decideStage .declegpanel')
    && document.querySelector('#decPay path, #decPay polyline'),
  null, { timeout: 90_000 });
  return symbol;
}

async function usefulIdeaFacts(targetPage = page) {
  await targetPage.waitForSelector('#mcFan .fan-interaction');
  return targetPage.evaluate(() => {
    function visible(selector) {
      const node = document.querySelector(selector);
      if (!node) return false;
      const box = node.getBoundingClientRect();
      return box.width > 0 && box.height > 0;
    }
    const state = window.DeskBackend.state();
    return {
      phase: window.decide?.backendPhase || null,
      planId: state.plan?.id || null,
      selectedId: state.selected?.id || null,
      candidateId: window.decide?.candId || null,
      candidateRows: document.querySelectorAll('.fanr').length,
      legRows: document.querySelectorAll('#decideStage .declegs .legr').length,
      payoffVisible: visible('#decPay') && !!document.querySelector('#decPay path, #decPay polyline'),
      pathsVisible: visible('#mcFan') && !!document.querySelector('#mcFan .fan-interaction'),
      pathStatsVisible: visible('.ensembleresult .mcstats'),
      marketVisible: visible('#decMarketPanel'),
      actionVisible: visible('.decdock') && !!document.querySelector('.decdock button')
    };
  });
}

function contractIdentity(leg) {
  return {
    action: String(leg.action || '').toUpperCase(),
    type: String(leg.type || '').toUpperCase(),
    strike: leg.strike == null ? null : Number(leg.strike),
    expiration: leg.expiration == null ? null : String(leg.expiration),
    ratio: Number(leg.ratio || 1),
    multiplier: Number(leg.multiplier || (String(leg.type || '').toUpperCase() === 'STOCK' ? 1 : 100)),
    positionEffect: String(leg.positionEffect || 'OPEN').toUpperCase()
  };
}

async function chooseExecutablePracticeOrder(targetPage) {
  const ids = await targetPage.evaluate(() => window.decide.cands.map(row => String(row.id)));
  const attempts = [];
  for (const id of ids) {
    if (await targetPage.evaluate(expected => String(window.decide.candId) !== expected, id)) {
      await targetPage.locator(`.fanr[data-cand="${id}"]`).click();
      await targetPage.waitForFunction(expected => window.decide?.backendPhase === 'ready'
        && String(window.decide?.candId) === expected
        && String(window.decide?.orderPreview?.selected?.id) === expected,
      id, { timeout: 90_000 });
    }
    if (!await targetPage.locator('[data-lane="paper"]').count()) {
      const destination = targetPage.locator('[data-dec="ticket"].place');
      if (await destination.count()) await destination.click();
      else await targetPage.locator('[data-dec="ticket"].ticketedit').click();
      await targetPage.waitForSelector('[data-lane="paper"]');
    }
    await targetPage.locator('[data-lane="paper"]').click();
    const result = await targetPage.evaluate(() => {
      const review = document.querySelector('[data-dec="review"]');
      const preview = window.decide?.orderPreview;
      return {
        id: String(window.decide?.candId || ''),
        enabled: !!review && !review.disabled,
        reasons: preview?.guardrails?.reasons || preview?.accountFit?.reasons || [],
        executability: preview?.order?.price?.executability || null
      };
    });
    attempts.push(result);
    if (result.enabled) return result.id;
  }
  assert.fail(`the packaged fixture exposed no reviewable Practice package: ${JSON.stringify(attempts)}`);
}

async function commitSelectedPracticeOrder(targetPage) {
  const candidateId = await chooseExecutablePracticeOrder(targetPage);
  const before = await targetPage.evaluate(() => ({
    planId: window.DeskBackend.state().plan.id,
    declarations: {
      intent: window.DeskBackend.state().plan.intent,
      thesis: window.DeskBackend.state().plan.context?.thesis,
      horizonDays: window.DeskBackend.state().plan.context?.horizonDays,
      riskMode: window.DeskBackend.state().plan.context?.riskMode
    },
    contracts: window.DeskBackend.state().selected.legs
  }));

  await targetPage.locator('[data-dec="review"]:not([disabled])').click();
  await targetPage.waitForSelector('[data-dec="confirm"]');
  while (await targetPage.locator('[data-order-ack]:not(:checked)').count()) {
    const acknowledgment = targetPage.locator('[data-order-ack]:not(:checked)').first();
    const acknowledgmentId = await acknowledgment.getAttribute('data-order-ack');
    await acknowledgment.click();
    await targetPage.waitForFunction(id =>
      window.decide?.order?.acknowledged?.[id] === true, acknowledgmentId);
  }
  await targetPage.waitForFunction(() => {
    const confirm = document.querySelector('[data-dec="confirm"]');
    return confirm && !confirm.disabled;
  });
  const reviewedPreview = await targetPage.evaluate(() =>
    JSON.parse(JSON.stringify(window.DeskBackend.state().decisionPreview?.preview || null)));
  await targetPage.locator('[data-dec="confirm"]').click();
  await targetPage.waitForFunction(() => {
    const state = window.DeskBackend.state();
    const activeTrades = state.book?.data?.practiceBook?.snapshot?.activeTrades;
    return (window.decide == null
        && state.book?.phase === 'ready'
        && Array.isArray(activeTrades)
        && activeTrades.length > 0)
      || window.decide?.backendPhase === 'error';
  }, null, { timeout: 90_000 });
  const commitFailure = await targetPage.evaluate(async reviewed => {
    if (window.decide?.backendPhase !== 'error') return null;
    const failure = {
      backendError: window.decide.backendError,
      bridgeError: window.DeskBackend.state().error?.message || window.DeskBackend.state().error,
      operation: window.decide.backendOperation,
      reviewPreview: reviewed,
      immediateRepreview: null
    };
    try {
      window.decide.order.committing = false;
      await window.DeskBackend.repreviewOrder(window.currentOrderRequest());
      failure.immediateRepreview = JSON.parse(JSON.stringify(
        window.DeskBackend.state().decisionPreview?.preview || null));
    } catch (error) {
      failure.repreviewError = error?.message || String(error);
    }
    return failure;
  }, reviewedPreview);
  assert.equal(commitFailure, null,
    `the exact packaged Practice order must commit before Position analysis: ${JSON.stringify(commitFailure)}`);
  const committed = await targetPage.evaluate(expectedCandidateId => {
    const state = window.DeskBackend.state();
    const rows = state.book.data.practiceBook.snapshot.activeTrades;
    const trade = rows.at(-1);
    return {
      trade,
      candidateId: expectedCandidateId,
      bookPhase: state.book.phase
    };
  }, candidateId);
  assert.ok(committed.trade?.id, `the committed Practice package must appear in the Book: ${JSON.stringify(committed)}`);
  return {
    ...before,
    candidateId,
    tradeId: String(committed.trade.id),
    trade: committed.trade
  };
}

test('the shipped jar completes Home to canonical New Idea without a source-server substitute', async () => {
  const response = await page.goto(app.base);
  assert.equal(response.status(), 200);
  const cacheControl = response.headers()['cache-control'] || '';
  assert.ok(cacheControl.includes('no-store')
      || (cacheControl.includes('max-age=0') && cacheControl.includes('must-revalidate')),
  `the packaged desk must revalidate markup and scripts instead of pairing stale assets; got ${cacheControl}`);
  await waitForHome();

  assert.equal(await page.locator('#stage').getAttribute('data-book-authority'), 'empty');
  assert.equal(await page.locator('.homeworkbenchpanel').count(), 1,
    'Home owns one permanent idea/scout workbench');

  assert.equal(await page.locator('#board:visible').count(), 1,
    'New idea focuses the Home workbench rather than mounting a second screen');
  governedSymbol = await openCanonicalIdea(page);

  const receipt = await page.evaluate(() => ({
    planId: window.DeskBackend.state().plan.id,
    planSymbol: window.DeskBackend.state().plan.symbol,
    selectedId: window.DeskBackend.state().selected?.id,
    candidateId: window.decide.candId,
    declarations: {
      goal: window.DeskBackend.state().plan.intent,
      view: window.DeskBackend.state().plan.context?.thesis,
      horizon: window.DeskBackend.state().plan.context?.horizonDays,
      risk: window.DeskBackend.state().plan.context?.riskMode
    },
    candidateRows: document.querySelectorAll('.fanr').length,
    legRows: document.querySelectorAll('#decideStage .declegs .legr').length
  }));
  assert.equal(receipt.planSymbol, governedSymbol);
  assert.equal(receipt.selectedId, receipt.candidateId,
    'the visible package and backend selection retain one identity');
  assert.deepEqual(receipt.declarations, {
    goal: 'INCOME',
    view: 'neutral',
    horizon: 45,
    risk: 'balanced'
  });
  assert.ok(receipt.candidateRows > 0);
  assert.ok(receipt.legRows > 0);

  const initialFacts = await usefulIdeaFacts();
  assert.deepEqual({
    phase: initialFacts.phase,
    selectedIdentity: initialFacts.selectedId === initialFacts.candidateId,
    hasCandidates: initialFacts.candidateRows > 0,
    hasLegs: initialFacts.legRows > 0,
    payoffVisible: initialFacts.payoffVisible,
    pathsVisible: initialFacts.pathsVisible,
    pathStatsVisible: initialFacts.pathStatsVisible,
    marketVisible: initialFacts.marketVisible,
    actionVisible: initialFacts.actionVisible
  }, {
    phase: 'ready',
    selectedIdentity: true,
    hasCandidates: true,
    hasLegs: true,
    payoffVisible: true,
    pathsVisible: true,
    pathStatsVisible: true,
    marketVisible: true,
    actionVisible: true
  }, `a newly opened idea must be immediately useful: ${JSON.stringify(initialFacts)}`);

  const visibleAnalysis = await page.evaluate(() => {
    const fan = document.querySelector('#mcFan');
    const stats = document.querySelector('.ensembleresult .mcstats');
    const wrap = document.querySelector('.decwrap');
    const fanBox = fan?.getBoundingClientRect();
    const statsBox = stats?.getBoundingClientRect();
    const scrollingAncestors = [];
    for (let node = fan?.parentElement; node && !node.classList.contains('decgrid');
      node = node.parentElement) {
      const style = getComputedStyle(node);
      if (['auto', 'scroll'].includes(style.overflowY)
          && node.scrollHeight > node.clientHeight + 2) {
        scrollingAncestors.push(node.className || node.tagName);
      }
    }
    return {
      fanWidth: fanBox?.width || 0,
      fanHeight: fanBox?.height || 0,
      statsVisible: !!statsBox && statsBox.width > 0 && statsBox.height > 0,
      scrollingAncestors,
      deskScroll: wrap ? wrap.scrollHeight - wrap.clientHeight : null,
      pageScroll: document.documentElement.scrollHeight - document.documentElement.clientHeight
    };
  });
  assert.ok(visibleAnalysis.fanWidth > 300 && visibleAnalysis.fanHeight > 180,
    `the shipped Evidence & Paths fan must be a visible analysis surface: ${JSON.stringify(visibleAnalysis)}`);
  assert.equal(visibleAnalysis.statsVisible, true,
    'the fan statistics remain visible with the plot');
  assert.deepEqual(visibleAnalysis.scrollingAncestors, [],
    'the shipped fan cannot be buried in a nested desktop scroller');
  assert.ok(visibleAnalysis.deskScroll <= 2 && visibleAnalysis.pageScroll <= 2,
    `canonical New Idea must fit the 1920x1080 desktop canvas: ${JSON.stringify(visibleAnalysis)}`);

  await page.locator('[data-dec="inspect"][data-inspect="fit"]').click();
  await page.waitForFunction(() =>
    document.querySelector('[data-dec="inspect"][data-inspect="fit"]')?.classList.contains('on'));
  await page.locator('[data-dec="back"]').first().click();
  await page.waitForSelector('#board');
  await page.waitForFunction(() => window.decide == null);

  const workingIdea = page.locator(`[data-auth-plan-id="${receipt.planId}"]`);
  await workingIdea.waitFor();
  await workingIdea.click();
  await page.waitForFunction(expected => window.decide?.backendPhase === 'ready'
      && String(window.DeskBackend.state().plan?.id || '') === String(expected),
    receipt.planId, { timeout: 90_000 });
  assert.equal(await page.evaluate(() => window.decide.sym), governedSymbol,
    'the Home working-idea row resumes the same durable Plan rather than starting a lookalike idea');
  const resumedFacts = await usefulIdeaFacts();
  assert.deepEqual({
    planId: resumedFacts.planId,
    candidateIdentity: resumedFacts.selectedId === resumedFacts.candidateId,
    selectedCandidate: resumedFacts.candidateId,
    hasCandidates: resumedFacts.candidateRows > 0,
    hasLegs: resumedFacts.legRows > 0,
    payoffVisible: resumedFacts.payoffVisible,
    pathsVisible: resumedFacts.pathsVisible,
    pathStatsVisible: resumedFacts.pathStatsVisible,
    marketVisible: resumedFacts.marketVisible,
    actionVisible: resumedFacts.actionVisible
  }, {
    planId: receipt.planId,
    candidateIdentity: true,
    selectedCandidate: receipt.candidateId,
    hasCandidates: true,
    hasLegs: true,
    payoffVisible: true,
    pathsVisible: true,
    pathStatsVisible: true,
    marketVisible: true,
    actionVisible: true
  }, `one click on a saved Working Idea must restore its complete analysis: ${JSON.stringify(resumedFacts)}`);
  await page.locator('[data-dec="back"]').first().click();
  await page.waitForFunction(() => window.decide == null && document.querySelector('#board'));

  assert.deepEqual(app.pageErrors, [], `packaged Desk page errors:\n${app.pageErrors.join('\n')}`);
  assert.deepEqual(app.serverErrors, [],
    `packaged Desk 5xx responses:\n${app.serverErrors.join('\n')}\n${app.log()}`);
  assert.ok(app.requests.some(row => row === 'POST /api/plans'),
    'the browser created the Plan through the packaged API');
  assert.ok(app.requests.some(row => row.includes('/api/plans/')),
    'the canonical analysis read the persisted Plan rather than a browser-only fixture');
});

test('the shipped Position forks its exact held package and declarations into canonical New Idea', async () => {
  const journey = await startPackagedApp({ label: 'desk-position-to-exact-new-idea' });
  const opened = await journey.newPage({ width: 1920, height: 1080 });
  const journeyPage = opened.page;
  try {
    const response = await journeyPage.goto(journey.base);
    assert.equal(response.status(), 200);
    const symbol = await openCanonicalIdea(journeyPage);
    const committed = await commitSelectedPracticeOrder(journeyPage);
    const heldContracts = committed.trade.legs.map(contractIdentity);

    await journeyPage.waitForSelector(`#book .card[data-id="${committed.tradeId}"]`);
    await journeyPage.locator(`#book .card[data-id="${committed.tradeId}"]`).click();
    await journeyPage.waitForFunction(tradeId => {
      const state = window.DeskBackend.state();
      const detail = document.querySelector(`[data-auth-position-detail="${tradeId}"]`);
      return window.state?.level === 'position'
        && state.position?.phase === 'ready'
        && String(state.position?.data?.trade?.id || '') === String(tradeId)
        && detail?.querySelector('[data-auth-manage="resume"]');
    }, committed.tradeId, { timeout: 90_000 });

    const positionReceipt = await journeyPage.evaluate(tradeId => {
      const position = window.byId[tradeId];
      const plan = position?._positionData?.plan || position?._plan;
      return {
        sourcePlanId: plan?.id || null,
        planStatus: plan?.status || null,
        assumptionsEditable: plan?.assumptionsEditable,
        declarations: {
          intent: plan?.intent,
          thesis: plan?.context?.thesis,
          horizonDays: plan?.context?.horizonDays,
          riskMode: plan?.context?.riskMode
        },
        contracts: (position?.legs || []).map(leg => ({
          action: String(leg.action || '').toUpperCase(),
          type: String(leg.type || '').toUpperCase(),
          strike: leg.k == null ? null : Number(leg.k),
          expiration: leg.expiration == null ? null : String(leg.expiration),
          ratio: Number(leg.ratio || 1),
          multiplier: Number(leg.multiplier || (leg.t === 's' ? 1 : 100)),
          positionEffect: String(leg.positionEffect || 'OPEN').toUpperCase()
        }))
      };
    }, committed.tradeId);
    assert.deepEqual(positionReceipt.contracts, heldContracts,
      'Position renders the same contracts that the committed package owns');
    assert.deepEqual(positionReceipt.declarations, committed.declarations,
      'the frozen owning Plan retains the exact declaration receipt');
    assert.equal(positionReceipt.sourcePlanId, committed.planId);
    assert.equal(positionReceipt.assumptionsEditable, false,
      'a committed held package is not edited by reopening its frozen decision');

    await journeyPage.locator(
      `[data-auth-position-detail="${committed.tradeId}"] .authposlegs [data-auth-manage="resume"]`).click();
    await journeyPage.waitForFunction(() => window.decide?.backendPhase === 'ready',
      null, { timeout: 90_000 });

    const fork = await journeyPage.evaluate(() => {
      const state = window.DeskBackend.state();
      function identity(leg) {
        return {
          action: String(leg.action || '').toUpperCase(),
          type: String(leg.type || '').toUpperCase(),
          strike: leg.strike == null ? null : Number(leg.strike),
          expiration: leg.expiration == null ? null : String(leg.expiration),
          ratio: Number(leg.ratio || 1),
          multiplier: Number(leg.multiplier || (String(leg.type || '').toUpperCase() === 'STOCK' ? 1 : 100)),
          positionEffect: String(leg.positionEffect || 'OPEN').toUpperCase()
        };
      }
      return {
        planId: state.plan?.id || null,
        originPlanId: state.plan?.originPlanId || state.plan?.context?.originPlanId || null,
        sourcePlanId: window.decide.sourcePlanId,
        sourcePositionId: window.decide.sourcePositionId,
        managementIntent: window.decide.managementIntent,
        forkDurability: window.decide._positionForkDurability,
        mode: window.decide.mode,
        draftPending: window.decide.draftPending,
        declarations: {
          intent: state.plan?.intent,
          thesis: state.plan?.context?.thesis,
          horizonDays: state.plan?.context?.horizonDays,
          riskMode: state.plan?.context?.riskMode
        },
        transientDraftContracts: (window.decide.buildLegs || []).map(identity),
        selectedContracts: (state.selected?.legs || []).map(identity),
        visibleLegRows: document.querySelectorAll('#decideStage .declegs .legr').length
      };
    });
    assert.notEqual(fork.planId, committed.planId,
      'Position analysis creates a fresh mutable child Plan rather than mutating the historical decision');
    assert.equal(fork.sourcePlanId, committed.planId);
    assert.equal(fork.sourcePositionId, committed.tradeId);
    assert.equal(fork.managementIntent, 'MANAGE_HELD_PACKAGE');
    assert.equal(fork.forkDurability, null,
      'the exact position fork has completed before the workbench becomes ready');
    assert.equal(fork.mode, 'engine',
      'the persisted CUSTOM package returns through the canonical selected-package state');
    assert.equal(fork.draftPending, false);
    assert.deepEqual(fork.declarations, committed.declarations,
      'the child Plan preserves goal, view, horizon, and risk posture exactly');
    assert.deepEqual(fork.selectedContracts, heldContracts,
      'the canonical custom-package owner persists those same exact contracts');
    assert.deepEqual(fork.transientDraftContracts, [],
      'after persistence the workbench has no second browser-only draft owner');
    assert.equal(fork.visibleLegRows, heldContracts.length,
      'the visible canonical leg workbench renders every persisted held contract');
    assert.ok(journey.requests.some(row => row === 'POST /api/plans'),
      'the packaged API created the origin-linked child Plan');
    assert.ok(journey.requests.some(row => /POST \/api\/plans\/[^/]+\/strategy\/custom/.test(row)),
      'the packaged custom-package owner accepted the held contracts');
    assert.deepEqual(journey.pageErrors, [],
      `Position → New Idea page errors:\n${journey.pageErrors.join('\n')}`);
    assert.deepEqual(journey.serverErrors, [],
      `Position → New Idea 5xx responses:\n${journey.serverErrors.join('\n')}\n${journey.log()}`);
    assert.equal(symbol, committed.trade.symbol);
  } finally {
    await journey.stop();
  }
});

test('the shipped world switch clears old analysis before publishing coherent Simulated and provider-isolated base receipts', async () => {
  const journey = await startPackagedApp({ label: 'desk-base-simulated-base' });
  const opened = await journey.newPage({ width: 1920, height: 1080 });
  const journeyPage = opened.page;
  try {
    const response = await journeyPage.goto(journey.base);
    assert.equal(response.status(), 200);
    await openCanonicalIdea(journeyPage);
    const observedBefore = await journeyPage.evaluate(() => {
      const state = window.DeskBackend.state();
      window.__journeyWorldTransitions = [];
      document.addEventListener('strikebench:desk-backend', event => {
        const detail = event.detail || {};
        if (detail.phase !== 'world-transition') return;
        const next = detail.state || {};
        window.__journeyWorldTransitions.push({
          artifactsCleared: detail.artifactsCleared === true,
          target: detail.target,
          lane: detail.config?.marketLane || null,
          market: next.market,
          book: next.book,
          plan: next.plan,
          strategy: next.strategy,
          candidates: Array.isArray(next.candidates) ? next.candidates.length : null,
          selected: next.selected,
          ensemble: next.ensemble,
          outcome: next.outcome,
          decisionPreview: next.decisionPreview
        });
      });
      return {
        world: state.market.identity.world,
        lane: state.market.identity.marketLane,
        datasetId: state.market.identity.datasetId,
        planId: state.plan.id,
        candidateId: state.selected.id,
        ensembleId: state.ensemble.ensemble.id,
        ensembleWorld: state.ensemble.preview.receipt.worldId,
        ensembleDataset: state.ensemble.preview.receipt.datasetId
      };
    });
    assert.deepEqual({
      world: observedBefore.world,
      lane: observedBefore.lane
    }, {
      world: 'demo',
      lane: 'DEMO'
    }, 'the provider-isolated packaged lane is honestly labeled Demo, never Observed');

    await journeyPage.locator('#mktMode [data-mkt="sim"]').click();
    await journeyPage.waitForFunction(() => {
      const state = window.DeskBackend.state();
      return state.market?.identity?.marketLane === 'SIMULATED'
        && state.plan?.marketKind === 'SIMULATED'
        && state.ensemble?.preview?.receipt?.worldId === state.market.identity.world
        && state.ensemble?.preview?.receipt?.datasetId === state.market.identity.datasetId
        && window.decide?.backendPhase === 'ready';
    }, null, { timeout: 90_000 });
    const simulated = await journeyPage.evaluate(() => {
      const state = window.DeskBackend.state();
      return {
        world: state.market.identity.world,
        lane: state.market.identity.marketLane,
        datasetId: state.market.identity.datasetId,
        planId: state.plan.id,
        candidateId: state.selected.id,
        ensembleId: state.ensemble.ensemble.id,
        ensembleWorld: state.ensemble.preview.receipt.worldId,
        ensembleDataset: state.ensemble.preview.receipt.datasetId,
        observedOn: document.querySelector('#mktMode [data-mkt="observed"]')?.classList.contains('on'),
        simulatedOn: document.querySelector('#mktMode [data-mkt="sim"]')?.classList.contains('on'),
        simulatedBody: document.body.classList.contains('mkt-sim'),
        visibleHeader: document.querySelector('#mktMode [data-mkt="sim"]')?.textContent.trim(),
        transitions: window.__journeyWorldTransitions.slice()
      };
    });
    const toSimulated = simulated.transitions.find(row =>
      row.artifactsCleared && row.lane === 'SIMULATED');
    assert.deepEqual(toSimulated && {
      market: toSimulated.market,
      book: toSimulated.book,
      plan: toSimulated.plan,
      strategy: toSimulated.strategy,
      candidates: toSimulated.candidates,
      selected: toSimulated.selected,
      ensemble: toSimulated.ensemble,
      outcome: toSimulated.outcome,
      decisionPreview: toSimulated.decisionPreview
    }, {
      market: null,
      book: null,
      plan: null,
      strategy: null,
      candidates: 0,
      selected: null,
      ensemble: null,
      outcome: null,
      decisionPreview: null
    }, 'the accepted world boundary publishes no old-world financial artifact');
    assert.notEqual(simulated.world, observedBefore.world);
    assert.equal(simulated.datasetId, observedBefore.datasetId,
      'a simulated world may deliberately retain the selected analysis dataset; world and lane own the boundary');
    assert.notEqual(simulated.planId, observedBefore.planId);
    assert.notEqual(simulated.ensembleId, observedBefore.ensembleId);
    assert.equal(simulated.ensembleWorld, simulated.world);
    assert.equal(simulated.ensembleDataset, simulated.datasetId);
    assert.equal(simulated.lane, 'SIMULATED');
    assert.equal(simulated.observedOn, false);
    assert.equal(simulated.simulatedOn, true);
    assert.equal(simulated.simulatedBody, true);
    assert.match(simulated.visibleHeader, /SIMULATED/i);

    await journeyPage.locator('#mktMode [data-mkt="observed"]').click();
    await journeyPage.waitForFunction(expected => {
      const state = window.DeskBackend.state();
      const coherent = state.market?.identity?.marketLane === expected.lane
        && state.market?.identity?.world === expected.world
        && state.plan?.marketKind === expected.planKind
        && state.ensemble?.preview?.receipt?.worldId === state.market.identity.world
        && state.ensemble?.preview?.receipt?.datasetId === state.market.identity.datasetId;
      return (coherent && window.decide?.backendPhase === 'ready')
        || window.decide?.backendPhase === 'error';
    }, { lane: observedBefore.lane, world: observedBefore.world, planKind: observedBefore.lane },
    { timeout: 90_000 });
    const observedAgain = await journeyPage.evaluate(() => {
      const state = window.DeskBackend.state();
      return {
        phase: window.decide?.backendPhase || null,
        backendError: window.decide?.backendError || null,
        bridgeError: state.error?.message || state.error || null,
        workspace: state.workspace?.receipt || null,
        world: state.market?.identity?.world || null,
        lane: state.market?.identity?.marketLane || null,
        datasetId: state.market?.identity?.datasetId || null,
        planId: state.plan?.id || null,
        ensembleId: state.ensemble?.ensemble?.id || null,
        ensembleWorld: state.ensemble?.preview?.receipt?.worldId || null,
        ensembleDataset: state.ensemble?.preview?.receipt?.datasetId || null,
        observedOn: document.querySelector('#mktMode [data-mkt="observed"]')?.classList.contains('on'),
        simulatedOn: document.querySelector('#mktMode [data-mkt="sim"]')?.classList.contains('on'),
        simulatedBody: document.body.classList.contains('mkt-sim'),
        visibleHeader: document.querySelector('#mktMode [data-mkt="observed"]')?.textContent.trim(),
        transitions: window.__journeyWorldTransitions.slice()
      };
    });
    assert.equal(observedAgain.phase, 'ready',
      `returning to the provider-isolated base market must reopen the idea: ${JSON.stringify(observedAgain)}`);
    const toBase = observedAgain.transitions.find(row =>
      row.artifactsCleared && row.lane === observedBefore.lane);
    assert.deepEqual(toBase && {
      market: toBase.market,
      book: toBase.book,
      plan: toBase.plan,
      strategy: toBase.strategy,
      candidates: toBase.candidates,
      selected: toBase.selected,
      ensemble: toBase.ensemble,
      outcome: toBase.outcome,
      decisionPreview: toBase.decisionPreview
    }, {
      market: null,
      book: null,
      plan: null,
      strategy: null,
      candidates: 0,
      selected: null,
      ensemble: null,
      outcome: null,
      decisionPreview: null
    }, 'returning to the base market clears every Simulated artifact before its replacement loads');
    assert.equal(observedAgain.world, observedBefore.world,
      'the base-side action returns to the server-declared provider-isolated baseline');
    assert.equal(observedAgain.lane, observedBefore.lane);
    assert.notEqual(observedAgain.planId, simulated.planId);
    assert.notEqual(observedAgain.ensembleId, simulated.ensembleId);
    assert.equal(observedAgain.ensembleWorld, observedAgain.world);
    assert.equal(observedAgain.ensembleDataset, observedAgain.datasetId);
    assert.equal(observedAgain.observedOn, true);
    assert.equal(observedAgain.simulatedOn, false);
    assert.equal(observedAgain.simulatedBody, false);
    assert.match(observedAgain.visibleHeader, /DEMO/i,
      'fixture isolation must remain visible after selecting the observed world; it cannot claim observed evidence');
    assert.ok(journey.requests.filter(row => row === 'PUT /api/world').length >= 2,
      'both visible switches cross the packaged server world boundary');
    assert.ok(journey.requests.some(row => row === 'POST /api/sim/market'),
      'Simulated uses the existing server-owned market creator');
    assert.deepEqual(journey.pageErrors, [],
      `base market ↔ Simulated page errors:\n${journey.pageErrors.join('\n')}`);
    assert.deepEqual(journey.serverErrors, [],
      `base market ↔ Simulated 5xx responses:\n${journey.serverErrors.join('\n')}\n${journey.log()}`);
  } finally {
    await journey.stop();
  }
});
