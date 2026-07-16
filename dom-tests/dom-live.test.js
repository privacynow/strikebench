/*
 * LIVE-mode browser suite: the same jar, NO FIXTURES_ONLY — real keyless providers.
 * The Observed lane fails closed: unavailable data is explicit and fixtures never substitute.
 * Assertions are
 * structural, not value-exact, because live markets move; any page JS error, any
 * /api 5xx, or any screen that fails to render is a hard failure.
 *
 * Run:  node --test dom-live.test.js   (needs network; skip in offline CI)
 */
'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { chromium } = require('playwright');
const { freshDb } = require('./pgtest');

const PORT = process.env.PORT || '7093';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg;
let planSequence = 0;
const pageErrors = [];
const serverErrors = [];

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(`${BASE}/api/status`);
      if (res.ok) return;
    } catch (e) { /* not up yet */ }
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error('server did not start');
}

async function go(hash) {
  await page.evaluate(h => {
    document.getElementById('app').setAttribute('data-ready', 'false');
    if (window.location.hash === h) return App.render();
    window.location.hash = h;
  }, hash);
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 60000 });
}

async function openPlan(symbol, stage = 'understand', intent = 'DIRECTIONAL', thesis = 'bullish') {
  const response = await fetch(BASE + '/api/plans', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clientRequestId: 'live-plan-' + (++planSequence), symbol, intent,
      thesis, horizonDays: 30, riskMode: 'conservative' })
  });
  const body = await response.text();
  assert.ok(response.ok, 'live Plan creation failed: ' + body);
  const plan = JSON.parse(body);
  await page.evaluate(async ({ id, stage }) => {
    await PlanStore.load(true);
    App.navigate('#/plan/' + id + '/' + stage);
  }, { id: plan.id, stage });
  await page.waitForFunction(({ id, stage }) => location.hash === '#/plan/' + id + '/' + stage
    && document.getElementById('app').getAttribute('data-ready') === 'true', { id: plan.id, stage },
    { timeout: 60000 });
  return plan;
}

async function runPlanField() {
  const compare = page.locator('.plan-tool').filter({ hasText: /Proposed trades|Ranked field/ });
  if (await compare.count()) await compare.click();
  await page.waitForSelector('#plan-run-strategy:not([disabled])', { timeout: 60000 });
  const responseP = page.waitForResponse(response => response.request().method() === 'POST'
    && /\/api\/plans\/[^/]+\/strategy\/run$/.test(new URL(response.url()).pathname), { timeout: 60000 });
  await page.click('#plan-run-strategy');
  const response = await responseP;
  if (!response.ok()) {
    await page.waitForSelector('#toast-root .toast-error, #plan-strategy-body .alert-danger', { timeout: 10000 });
    return false;
  }
  await page.waitForFunction(() => {
    const body = document.getElementById('plan-strategy-body');
    return !!body && (!!body.querySelector('#plan-strategy-results .candidate')
      || /No structure passed this screen|No candidate passed/.test(body.textContent || ''));
  }, null, { timeout: 60000 });
  return true;
}

async function runAndSelectListedCandidate(plan) {
  if (!(await runPlanField())) return false;
  if (!(await page.locator('#plan-strategy-results .candidate').count())) return false;
  const selected = await page.evaluate(async planId => {
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    const candidates = latest && latest.strategy && latest.strategy.result && latest.strategy.result.candidates || [];
    const candidate = candidates.find(item => {
      const expirations = new Set((item.legs || []).filter(leg => leg.type !== 'STOCK')
        .map(leg => leg.expiration));
      return expirations.size === 1
        && item.evaluation?.assessment?.mechanics?.eligible !== false;
    });
    if (!candidate) return false;
    const live = await PlanStore.get(planId, true);
    await PlanStore.selectCandidate(live, candidate.id);
    await App.render();
    return true;
  }, plan.id);
  if (selected) await page.waitForSelector('#plan-strategy-results button:has-text("Selected for this Plan")',
    { timeout: 30000 });
  return selected;
}

function assertClean(label) {
  assert.deepEqual(pageErrors, [], `${label}: page JS errors: ${pageErrors.join(' | ')}`);
  assert.deepEqual(serverErrors, [], `${label}: server 5xx: ${serverErrors.join(' | ')}`);
}

before(async () => {
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, ...pg.env }, // NO FIXTURES_ONLY
    stdio: 'ignore'
  });
  await waitForServer();
  browser = await chromium.launch();
  page = await browser.newPage();
  page.on('pageerror', e => pageErrors.push(e.message));
  page.on('response', r => { if (r.status() >= 500) serverErrors.push(r.status() + ' ' + r.url()); });
  await page.goto(BASE + '/');
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 60000 });
});

after(async () => {
  if (browser) await browser.close();
  if (server) server.kill();
  if (pg) pg.drop();
});

test('live: dashboard renders observed cards or an explicit unavailable state', async () => {
  // A fresh live account opens on the welcome page — skip it like a new user would
  const welcome = await page.locator('#welcome-skip').count();
  if (welcome) {
    await page.click('#welcome-skip');
    await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  }
  await page.waitForSelector('#home-market-watch .sym-card, #home-market-watch .empty', { timeout: 60000 });
  const cards = page.locator('#home-market-watch .sym-card');
  if (await cards.count()) {
    await page.waitForFunction(() => Array.from(document.querySelectorAll('#home-market-watch .sym-card'))
      .every(c => c.querySelector('.spark-svg, .spark-empty')), { timeout: 30000 });
    assert.doesNotMatch(await page.textContent('#home-market-watch'), /DEMO DATA/i,
      'Observed Home never substitutes Demo quotes');
    const missingCards = page.locator('#home-market-watch .sym-card:has(.spark-empty)');
    const missingCount = await missingCards.count();
    if (missingCount) {
      assert.ok(await page.locator('#home-market-watch .home-history-notice').isVisible(),
        'one section-level recovery note explains missing history');
      assert.equal(await page.locator('#home-market-watch .sym-card:has(.spark-empty) .spark-ev').count(), 0,
        'cards do not repeat the same missing-history badge under the section-level notice');
      const emptyLabels = await page.$$eval('#home-market-watch .sym-card:has(.spark-empty) .spark-empty',
        els => els.map(e => e.textContent.trim()));
      emptyLabels.forEach(label => assert.match(label, /Needs daily history|Chart (?:deferred|temporarily unavailable)|No daily history/,
        'each affected card names its actual chart state without repeating the section banner'));
    }
  } else {
    assert.match(await page.textContent('#home-market-watch'), /market data unavailable/i,
      'provider outage is an explicit state');
  }
  assertClean('dashboard');
});

test('live: visible market controls return Demo and simulated sessions to Observed', async () => {
  const observedAccount = await page.evaluate(async () => (await API.getFresh('/api/account')).account.id);
  await go('#/data/simulation');
  await page.waitForSelector('#enter-demo-market');
  await page.click('#enter-demo-market');
  await page.waitForFunction(() => App.state.world === 'demo' && App.Market.world === 'demo'
    && document.body.classList.contains('in-demo-world'), { timeout: 30000 });
  const demoAccount = await page.evaluate(async () => (await API.getFresh('/api/account')).account.id);
  assert.notEqual(demoAccount, observedAccount, 'Demo uses its isolated account');

  await page.click('#world-exit');
  await page.waitForFunction(() => App.state.world === 'observed' && App.Market.world === 'observed'
    && !document.getElementById('world-band') && App.state.universe && App.state.universe.active,
    { timeout: 30000 });
  let returned = await page.evaluate(async () => ({
    server: (await API.getFresh('/api/world')).world,
    client: App.state.world,
    store: App.Market.world,
    account: (await API.getFresh('/api/account')).account.id,
    transition: App.state.transitionStatus
  }));
  assert.deepEqual(returned, { server: 'observed', client: 'observed', store: 'observed',
    account: observedAccount, transition: 'committed' });

  // Build from the explicit Demo lane so the live test never depends on provider availability.
  await go('#/data/simulation');
  await page.click('#enter-demo-market');
  await page.waitForFunction(() => App.state.world === 'demo' && App.Market.world === 'demo',
    { timeout: 30000 });
  await go('#/data/simulation');
  await page.waitForSelector('#sim-create');
  await page.fill('#sim-name', 'Live return contract');
  await page.click('#sim-create');
  await page.waitForFunction(() => App.state.world !== 'demo' && App.state.world !== 'observed'
    && App.Market.world === App.state.world && document.body.classList.contains('in-sim-world'),
    { timeout: 30000 });
  const simulatedAccount = await page.evaluate(async () => (await API.getFresh('/api/account')).account.id);
  assert.notEqual(simulatedAccount, observedAccount, 'the simulated session uses its own account');

  await page.click('#world-exit');
  await page.waitForFunction(() => App.state.world === 'observed' && App.Market.world === 'observed'
    && !document.body.classList.contains('in-sim-world') && App.state.universe && App.state.universe.active,
    { timeout: 30000 });
  returned = await page.evaluate(async () => ({
    server: (await API.getFresh('/api/world')).world,
    client: App.state.world,
    store: App.Market.world,
    account: (await API.getFresh('/api/account')).account.id,
    transition: App.state.transitionStatus
  }));
  assert.deepEqual(returned, { server: 'observed', client: 'observed', store: 'observed',
    account: observedAccount, transition: 'committed' });
  assertClean('market-return-controls');
});

test('live: research a real symbol end to end', async () => {
  await go('#/research');
  await page.waitForSelector('#sector-grid .spark-svg, #sector-grid .spark-empty', { timeout: 30000 });
  if (await page.locator('#sector-grid .spark-empty').count()) {
    assert.ok(await page.locator('#sector-history-notice').isVisible(),
      'Research owns one explicit history recovery action');
    assert.equal(await page.locator('#sector-grid .spark-empty + .spark-ev').count(), 0,
      'the explorer does not repeat HISTORY UNAVAILABLE on every affected card');
    assert.equal(await page.locator('#sector-grid .spark-empty').count(),
      await page.locator('#sector-grid .spark-slot-missing').count(),
      'missing charts release unused chart height without hiding the unavailable state');
  }
  await go('#/research/AAPL');
  await page.waitForSelector('#symbol-proposed-trades', { timeout: 30000 });
  await page.waitForSelector('#research-symbol', { timeout: 30000 });
  const quoteBadge = await page.textContent('.quote-hero .badge');
  assert.match(quoteBadge, /REALTIME|DELAYED|EOD|STALE/i, 'observed quote age is labeled');
  assert.doesNotMatch(quoteBadge, /DEMO|SIMULATED/i, 'Observed quote never comes from another lane');
  await page.waitForSelector('#history-card');
  const hist = await page.textContent('#history-card');
  assert.doesNotMatch(hist, /DEMO DATA/i, 'Observed Research never substitutes Demo candles');
  assert.doesNotMatch(await page.textContent('#research-hero'), /building history|—\s*[–-]\s*—/i,
    'missing metrics use honest words instead of fake progress or punctuation placeholders');
  if (/unavailable/i.test(hist)) {
    assert.match(await page.textContent('#research-evidence'), /PARTIAL EVIDENCE/i,
      'working quote and chain inputs are not mislabeled as total data failure when history is missing');
  }
  const heroText = await page.textContent('#research-hero');
  if (/add history/i.test(heroText)) {
    assert.match(heroText, /\d+\/20 daily closes · add history/i,
      'HV names the shared twenty-close requirement and recovery action');
    assert.match(heroText, /\d+\/10 observed snapshot days/i,
      'IV rank remains visible and names its independent option-snapshot requirement');
  }
  await openPlan('AAPL', 'strategy');
  await page.locator('.plan-tool').filter({ hasText: /Chain|Option prices/ }).click();
  await page.waitForSelector('#expiration-select', { timeout: 30000 });
  await page.waitForSelector('.tbl tbody tr');
  assertClean('research');
});

test('live: research handles unknown and non-optionable symbols gracefully', async () => {
  await go('#/research/ZZZZQQ');
  await page.waitForSelector('text=/No data for ZZZZQQ/i', { timeout: 30000 }); // hero fills detached
  await go('#/research/VTSAX');
  await page.waitForSelector('text=/No data for VTSAX|no listed options/i', { timeout: 30000 });
  // A real, established stock with NO candle source (keyless: Stooq blocks bots, no
  // Polygon/AV key) must say WHY the chart is empty — never blame the window
  await go('#/research/PG');
  await page.waitForSelector('#history-card');
  await page.waitForFunction(() => {
    const t = (document.getElementById('history-card') || {}).textContent || '';
    return /Connect or backfill|Observed daily history is unavailable|No observed daily history|High/.test(t);
  }, { timeout: 30000 });
  const hist = await page.textContent('#history-card');
  assert.doesNotMatch(hist, /Not enough data for this window/);
  assertClean('research-edge');
});

test('live: a single-symbol Plan returns ranked structures for a real chain', async () => {
  const plan = await openPlan('AAPL', 'strategy');
  const completed = await runPlanField();
  const text = await page.textContent('#plan-strategy-body');
  assert.ok(!completed || /Theoretical max loss/.test(text)
      || /No structure passed this screen|No candidate passed/.test(text),
    'candidates preserve theoretical risk truth or render a graceful empty state');
  assert.match(await page.textContent('#plan-header'), new RegExp(plan.symbol));
  assertClean('plan-strategy');
});

test('live: universe Scout scans and reports with evidence within budget', async () => {
  const researchReadiness = await page.evaluate(async () => API.getFresh('/api/research/AAPL'));
  await go('#/research');
  await page.waitForSelector('#universe-scout-run');
  const t0 = Date.now();
  await page.click('#universe-scout-run');
  await page.waitForSelector('#universe-scout-results .universe-scout-pick, #universe-scout-results .empty',
    { timeout: 60000 });
  const elapsed = Date.now() - t0;
  assert.ok(elapsed < 20000, `scout under 20s, took ${elapsed}ms`);
  if (await page.locator('#universe-scout-results .universe-scout-pick').count()) {
    assert.match(await page.textContent('#universe-scout-results'), /Confidence|Evidence|Favorable|Mixed/i);
    if (researchReadiness.hv30 === null || researchReadiness.hv30 === undefined) {
      assert.match(await page.textContent('#universe-scout-results'),
        /favorable observed verdict cannot be formed|daily history is insufficient|every candidate failed a mechanical or account check|no executable option chain/i,
        'Scout distinguishes missing realized history, an unavailable executable chain, or a mechanical block from an unfavorable economic scan');
    }
  }
  assertClean('scout');
});

test('live: Plan Decide places and Manage unwinds a paper trade at real marks', async () => {
  const plan = await openPlan('AAPL', 'strategy');
  if (!(await runAndSelectListedCandidate(plan))) {
    assert.ok(true, 'no candidates passed screens right now — graceful, not a failure');
    assertClean('ticket-nocandidates');
    return;
  }
  await page.locator('.plan-rail button').filter({ hasText: 'Decide' }).click();
  await page.waitForSelector('#plan-review-order');
  await page.click('#plan-review-order');
  await page.waitForSelector('#plan-decision-review .plan-decision-math', { timeout: 60000 });
  assert.match(await page.textContent('#plan-decision-review'), /Buying power after/);
  await page.$$eval('#plan-decision-review .ack-gate input', els => els.forEach(e => { if (!e.checked) e.click(); }));
  if (!(await page.locator('#plan-place-trade').isEnabled())) {
    const review = await page.textContent('#plan-decision-review');
    assert.match(review, /This package cannot be opened/i,
      'a non-executable observed package is refused explicitly instead of timing out or switching lanes');
    assert.match(review, /OBSERVED|executable|STALE|market is closed/i,
      'the refusal names the observed execution-data condition');
    assert.doesNotMatch(review, /DEMO DATA|fabricated/i,
      'an observed execution gap never substitutes teaching-market prices');
    assert.ok(await page.locator('#plan-stay-cash').isEnabled(),
      'staying in cash remains an actionable decision when execution is unavailable');
    assertClean('ticket-non-executable');
    return;
  }
  await page.click('#plan-place-trade');
  await page.waitForSelector('#refresh-btn', { timeout: 60000 }); // detail landmark
  assert.match(await page.textContent('#app'), /ACTIVE/);

  await page.click('#refresh-btn');
  await page.waitForSelector('text=Marks history', { timeout: 30000 });
  await page.click('#unwind-btn');
  await page.waitForSelector('#close-transformation-preview');
  assert.match(await page.textContent('#close-transformation-preview'),
    /Cash \/ no position.*Closing cash flow.*Final position P\/L/s,
    'the observed close uses the same reviewed before/after transformation contract');
  await page.getByRole('button', { name: 'Close position' }).click();
  await page.waitForSelector('#plan-stage-manage-review .plan-review-results', { timeout: 60000 });
  assertClean('ticket-lifecycle');
});

test('live: observed backtest never substitutes demo history', async () => {
  const plan = await openPlan('AAPL', 'strategy');
  if (!(await runAndSelectListedCandidate(plan))) {
    assertClean('backtest-no-position');
    return;
  }
  await page.locator('.plan-rail button').filter({ hasText: 'Outcomes' }).click();
  await page.waitForSelector('#plan-outcomes');
  await page.click('#plan-outcomes-basis-backtest');
  await page.getByRole('button', { name: 'Run historical replay' }).click();
  await page.waitForSelector('#plan-backtest-result .grid, #plan-backtest-result .alert-danger', { timeout: 120000 });
  const text = await page.textContent('#plan-backtest-result');
  assert.doesNotMatch(text, /Demo price data|DEMO DATA/i,
    'Observed backtest must require observed/modeled-from-observed history instead of substituting fixtures');
  assertClean('backtest');
});

test('live: status, account, portfolio render', async () => {
  await go('#/data/overview');
  await page.waitForSelector('#dc-engine .chip-row', { timeout: 30000 }); // Data Center engine card
  assert.match(await page.textContent('#dc-engine'), /Market engine/);
  await go('#/portfolio/account');
  assert.match(await page.textContent('#app'), /Buying power/);
  await go('#/portfolio/closed');
  await page.waitForSelector('.tbl tbody tr, .empty', { timeout: 30000 });
  assertClean('remaining-screens');
});
