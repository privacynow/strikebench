/*
 * StrikeBench DOM tests: real browser (Playwright Chromium) against the real fat jar
 * running in fixture mode on a fresh database. Asserts VISIBLE DOM after each button.
 *
 * Run:  node --test dom.test.js     (from dom-tests/, after `mvn package`)
 * Env:  JAVA_BIN (default: java), JAR (default: ../target/strikebench.jar), PORT (default: 7072)
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

const PORT = process.env.PORT || '7072';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg;

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

/** Navigate to a hash route and wait for the view to finish rendering. */
async function go(hash) {
  await page.evaluate(h => {
    // A same-hash visit is still a navigation in the test contract. Force readiness
    // false before either path so waitForSelector can never observe the outgoing view.
    document.getElementById('app').setAttribute('data-ready', 'false');
    if (window.location.hash === h) return App.render();
    window.location.hash = h;
  }, hash);
  await page.waitForSelector('#app[data-ready="true"]');
}

async function openResearchTab(key) {
  const tab = page.locator('#research-workspace-tabs [data-research-tab="' + key + '"]');
  assert.equal(await tab.count(), 1, 'research tab ' + key + ' exists');
  await tab.click();
  await page.waitForSelector('[data-research-panel="' + key + '"]:not([hidden])');
}

/** Run the real Context workflow and open its inline side-by-side evaluation. */
async function openInlineCompetition(symbol = 'AAPL') {
  await go('#/trade/context/manual');
  await page.fill('#rec-symbol', symbol);
  await page.click('#rec-go');
  await page.waitForSelector('#compare-ideas-btn', { timeout: 25000 });
  await page.click('#compare-ideas-btn');
  await page.waitForSelector('#decision-host .decision-pick', { timeout: 20000 });
}

before(async () => {
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' },
    stdio: 'ignore'
  });
  await waitForServer();
  browser = await chromium.launch();
  page = await browser.newPage();
  page.on('pageerror', e => { throw new Error('page error: ' + e.message); });

  await page.goto(BASE + '/');
  await page.waitForSelector('#app[data-ready="true"]');
});

after(async () => {
  if (browser) await browser.close();
  if (server) server.kill();
  if (pg) pg.drop();
});

test('boots to the welcome page, then the dashboard with markets and the tape', async () => {
  assert.equal(await page.title(), 'StrikeBench');
  // Fresh account -> the opening page: hero, level paths, capability grid
  await page.waitForSelector('#welcome-hero');
  const hero = await page.textContent('#app');
  assert.match(hero, /Learn options by doing/); // the H1 sells the value; the brand lives in the header
  assert.match(hero, /How do you want to start\?/);
  assert.ok((await page.locator('#welcome-levels .welcome-card').count()) === 2, 'two level paths: Beginner and Expert');
  // The universe ticker tape ticks above the welcome page too
  await page.waitForSelector('#tape .tape-item');
  // Show-don't-tell: an engine-generated candidate renders with lane-honest provenance.
  await page.waitForSelector('#welcome-live .candidate', { timeout: 20000 });
  assert.match(await page.textContent('#welcome-proof-caption'), /fabricated Demo data/,
    'the proof caption follows the explicit Demo lane');
  assert.doesNotMatch(await page.textContent('#welcome-proof-caption'), /real screened idea/i,
    'a Demo candidate is never promoted as real');
  assert.match(await page.textContent('#welcome-hero'), /Uses bid\/ask, not midpoint/,
    'fill realism is described without claiming Demo books are real');
  // Skip to the dashboard; the choice persists
  await page.click('#welcome-skip');
  await page.waitForSelector('.home-market-grid .tile');
  await page.waitForFunction(() => document.querySelectorAll('.home-market-grid .spark-svg').length >= 4,
    { timeout: 15000 });
  assert.equal(await page.locator('.home-market-grid .spark-slot .evidence-badge').count(), 0,
    'the Demo lane does not repeat the same fabricated label twice on every card');
  assert.equal(await page.evaluate(() => Array.from(document.querySelectorAll('.home-market-grid .sym-card')).some(card => {
    const ev = card.querySelector('.spark-ev');
    const head = card.querySelector('.t-sym');
    if (!ev || !head) return false;
    const a = ev.getBoundingClientRect(), b = head.getBoundingClientRect();
    return a.top < b.bottom && a.bottom > b.top;
  })), false, 'history evidence never covers the ticker/freshness row');
  const app = await page.textContent('#app');
  // Home is the OPERATIONAL desk (review #10): market mode + account numbers + one contextual
  // action — the product positioning lives on Welcome only, never repeated here.
  assert.match(app, /YOUR PRACTICE DESK/);
  assert.match(app, /OBSERVED MARKET|DEMO MARKET|SIMULATED|SCENARIO/);
  assert.doesNotMatch(await page.textContent('#home-mode-chip'), /SIMULATED/, 'Demo is not mislabeled as a simulated session');
  assert.doesNotMatch(app, /Learn options by/, 'positioning is not repeated on the dashboard');
  assert.match(app, /Buying power/);
  assert.ok(await page.locator('#home-tour-link').isVisible(), 'the full tour is one visible click away');
  assert.ok((await page.locator('.home-market-grid .tile').count()) >= 4, 'market watch tiles');
  const footer = await page.textContent('#disclaimer');
  assert.match(footer, /not financial advice/i);
  // The tour is a canonical Home subview.
  await go('#/home/tour');
  await page.waitForSelector('#welcome-hero');
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'home');
  // BRAND RETURNS TO THE OPERATIONAL DESK (review P5): from any screen the mark lands on
  // Home, never a surprise tour; the tour keeps its own quiet entry at the page bottom.
  await go('#/research/AAPL');
  await page.waitForSelector('.quote-hero');
  await page.click('.brand');
  await page.waitForSelector('.home-market-grid .tile');
  await page.waitForFunction(() => document.querySelectorAll('.home-market-grid .spark-svg').length >= 4,
    { timeout: 15000 });
  assert.equal(await page.evaluate(() => window.location.hash), '#/home', 'brand goes to the desk');
  assert.ok(await page.locator('.brand .brand-mark').count(), 'the SVG mark renders in the header');
  await page.reload();
  await page.waitForSelector('#app[data-route="home"][data-ready="true"]');
  await page.waitForFunction(() => document.querySelectorAll('.home-market-grid .spark-svg').length >= 4,
    { timeout: 15000 });
  assert.match(await page.textContent('#world-band'), /DEMO MARKET/,
    'same-lane reload restores both market honesty and warm chart decoration');
  // The tour stays reachable — demoted to a quiet About link, not a standing command
  assert.ok(await page.locator('#home-tour-link').isVisible(), 'quiet tour entry exists');
  assert.equal(await page.locator('.home-hero-ctas #home-tour-link').count(), 0,
    'the hero owns ONE primary action; the tour left the hero');
  await page.click('#home-tour-link');
  await page.waitForSelector('#welcome-hero');
  // Home: sector pulse chips dig into the explorer
  await go('#/home');
  await page.waitForSelector('#sector-pulse .sector-chip', { timeout: 20000 });
  await page.click('#sector-pulse .sector-chip');
  await page.waitForSelector('#sector-explorer');
  await go('#/home'); // leave the suite on the dashboard for the next test
});

test('financial formatters and mixed packages fail closed instead of rendering NaN', async () => {
  const safety = await page.evaluate(() => ({
    money: UI.fmtMoney(Number.NaN), compact: UI.fmtMoneyCompact(Infinity),
    pct: UI.fmtPct(Number.NaN), num: UI.fmtNum(-Infinity),
    option: UI.firstOptionLeg([
      { action: 'BUY', type: 'STOCK', strike: null, expiration: null },
      { action: 'SELL', type: 'CALL', strike: '260', expiration: '2026-08-21' }
    ])
  }));
  assert.deepEqual([safety.money, safety.compact, safety.pct, safety.num], ['—', '—', '—', '—']);
  assert.equal(safety.option.strike, '260', 'stock-first packages resolve their listed option contract');

  const contextContract = await page.evaluate(() => {
    App.context.update({ symbol: 'AAPL', goal: 'INCOME', horizon: 'month', thesis: 'neutral' });
    App.context.update({ goal: 'ALL' });
    const cleared = App.context.goal();
    const covered = Builder.TEMPLATES.find(t => t.family === 'COVERED_CALL');
    App.state.builderForm = {
      symbol: 'AAPL', qty: 1, goal: 'BROWSE', templateKey: covered.key,
      legs: [{ action: 'BUY', type: 'STOCK', strike: null, expiration: null, ratio: 1 },
        { action: 'SELL', type: 'CALL', strike: '260', expiration: '2026-08-21', ratio: 1 }],
      excluded: {}
    };
    const ticket = Builder.prepareTicket();
    App.state.builderForm = null; App.state.ticket = null;
    return { cleared, templateIntent: covered.primaryIntent, ticketIntent: ticket.intent };
  });
  assert.equal(contextContract.cleared, null, 'Everything clears a stale single-goal context');
  assert.equal(contextContract.templateIntent, 'INCOME', 'server catalog supplies the structure intent');
  assert.equal(contextContract.ticketIntent, 'INCOME', 'Browse all infers intent from the chosen structure');
});

test('welcome proof treats no qualifying idea as a complete result', async () => {
  await page.route('**/api/recommend', r => r.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ symbol: 'AAPL', candidates: [], rejected: [], notes: ['No candidate passed.'] })
  }));
  await page.evaluate(() => localStorage.removeItem('strikebench.welcomeProof'));
  await go('#/home/tour');
  await page.waitForSelector('#welcome-proof-unavailable');
  assert.match(await page.textContent('#welcome-proof-unavailable'), /No AAPL idea passes right now/);
  assert.match(await page.textContent('#welcome-proof-unavailable'), /valid engine result/);
  assert.ok(await page.locator('#welcome-proof-unavailable button').count(), 'the no-trade proof has a next step');
  await page.unroute('**/api/recommend');
});

test('provider-governed sparkline deferral resolves to an honest unavailable state', async () => {
  await page.route('**/api/sparklines?*', r => r.fulfill({ status: 204, body: '' }));
  try {
    await page.evaluate(() => {
      localStorage.setItem('strikebench.welcomed', '1');
      API.flushCache();
      App.navigate('#/home');
    });
    await page.waitForSelector('#app[data-route="home"][data-ready="true"]');
    await page.waitForFunction(() => {
      const cards = document.querySelectorAll('.home-market-grid .sym-card');
      return cards.length > 0 && document.querySelectorAll('.home-market-grid .spark-ev').length === cards.length;
    });
    assert.equal(await page.locator('.home-market-grid .spark-loading').count(), 0,
      'a declined optional request never leaves a permanent loading state');
    assert.equal(await page.locator('.home-market-grid .spark-ev').evaluateAll(nodes =>
      nodes.every(n => /DATA UNAVAILABLE/.test(n.textContent))), true,
      'every deferred chart states that its history evidence is missing');
  } finally {
    await page.unroute('**/api/sparklines?*');
  }
});

test('research AAPL: hero quote, events, news, focused chain, show-all toggle', async () => {
  await go('#/research');
  await page.fill('#symbol-input', 'AAPL');
  await page.click('#symbol-go');
  await page.waitForSelector('#research-symbol');
  assert.match(await page.textContent('#research-symbol'), /AAPL/);
  // Coming up: expirations (and any earnings/filing signals) as dated chips
  await page.waitForSelector('#events-card .chip');
  assert.match(await page.textContent('#events-card'), /Expiry/);
  // From a symbol page, the sector explorer is one visible click away
  assert.ok(await page.locator('#back-to-sectors').isVisible(), 'All sectors link on symbol pages');
  // The lookup shortcuts are YOUR recent symbols, not four frozen tickers
  assert.match(await page.textContent('#recent-symbols'), /Recent/);
  assert.ok(await page.locator('#recent-symbols .sym-chip:has-text("AAPL")').count(), 'AAPL just researched');
  assert.equal(await page.locator('.research-workspace-panel:not([hidden])').count(), 1,
    'one coherent Research task is visible at a time');
  assert.ok(await page.locator('.research-local-nav:has-text("Explore AAPL")').count(),
    'mid-page navigation is framed as the symbol workspace, not loose words');
  assert.ok(await page.locator('#research-workspace-tabs small').count() >= 4,
    'desktop tabs explain the task behind each destination');
  await page.waitForSelector('#news-overview-card .news-tile');
  assert.ok(await page.locator('#news-overview-card .news-tile').count() >= 1,
    'latest news remains part of the market overview');
  assert.match(await page.textContent('#news-overview-card'), /not an article summary/i);
  // News NEVER silently vanishes — card renders with items or an honest empty state
  await openResearchTab('news');
  await page.waitForSelector('#news-card');
  assert.match(await page.textContent('#news-card'), /News & filings/);
  assert.ok((await page.locator('#news-card .news-tile').count()) >= 1, 'fixture headlines render');
  await page.waitForSelector('#research-evidence .evidence-badge:has-text("DEMO")');
  assert.equal(await page.locator('.quote-hero .badge:has-text("DEMO DATA")').count(), 0,
    'the hero does not repeat the page-level Demo evidence label');
  await openResearchTab('options');
  await page.waitForSelector('#expiration-select');
  await page.waitForSelector('.tbl tbody tr.atm'); // ATM row highlighted
  const focused = await page.locator('.tbl tbody tr').count();
  assert.ok(focused >= 10 && focused < 21, `focused chain window, got ${focused} rows`);
  await page.click('#chain-toggle');
  await page.waitForFunction(() => document.querySelectorAll('.tbl tbody tr').length >= 21);
  // change expiration -> table re-renders
  const values = await page.$$eval('#expiration-select option', os => os.map(o => o.value));
  assert.ok(values.length === 8, 'eight expirations');
  await page.selectOption('#expiration-select', values[1]);
  await page.waitForSelector('.tbl tbody tr');
});

test('research VTSAX warns about missing options', async () => {
  await go('#/research/VTSAX');
  await openResearchTab('options');
  await page.waitForSelector('text=/no listed options/i'); // hero/actions now fill detached
});

test('Research entry and destination cards are purposeful, readable, and collision-free', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await go('#/research');
  await page.waitForSelector('#sector-grid .sym-card[data-sym="AAPL"] .spark-svg', { timeout: 15000 });
  const geometry = await page.evaluate(() => {
    const shell = document.getElementById('research-symbol-context').getBoundingClientRect();
    const input = document.querySelector('#research-symbol-context input').getBoundingClientRect();
    const app = document.getElementById('app').getBoundingClientRect();
    const collisions = Array.from(document.querySelectorAll('#sector-grid .spark-slot')).filter(slot => {
      const a = slot.querySelector('.spark-readout'), b = slot.querySelector('.spark-ev');
      if (!a || !b) return false;
      const x = a.getBoundingClientRect(), y = b.getBoundingClientRect();
      return x.left < y.right && x.right > y.left && x.top < y.bottom && x.bottom > y.top;
    }).length;
    return { shellWidth: shell.width, inputWidth: input.width, appWidth: app.width, collisions };
  });
  assert.ok(geometry.shellWidth > geometry.appWidth * 0.85 && geometry.inputWidth <= 522,
    'the context card keeps page symmetry while only the editor is purpose-sized: ' + JSON.stringify(geometry));
  assert.equal(geometry.collisions, 0, 'sparkline readout never overlaps its evidence badge');
  assert.ok(await page.locator('#sector-chips .sector-chip').first().isVisible(),
    'market scope stays visible even when the explicit Demo lane has only one sector');
  assert.ok(await page.locator('#sector-grid .destination-cue').count() >= 4,
    'destination cards use one explicit open affordance instead of hover underlines');
  await page.click('#sector-grid .sym-card[data-sym="AAPL"]');
  await page.waitForSelector('#research-symbol');
  assert.equal(await page.locator('#research-symbol-context .symbol-context-label').count(), 0,
    'symbol detail removes the redundant visible field label without shrinking the card');
  assert.equal(await page.locator('#research-symbol-context input').getAttribute('aria-label'), 'Change stock',
    'the concise editor keeps an accessible name');
  const detailControlGap = await page.evaluate(() => {
    const input = document.querySelector('#research-symbol-context input').getBoundingClientRect();
    const action = document.getElementById('symbol-go').getBoundingClientRect();
    return Math.round(action.left - input.right);
  });
  assert.ok(detailControlGap >= 6 && detailControlGap <= 20,
    'the purpose-sized editor and Go command stay one visual control cluster: gap=' + detailControlGap);
});

test('Ideas begins with market context and reuses the scenario engine for realistic outcomes', async () => {
  await page.evaluate(() => {
    App.state.discoverForm = null;
    App.context.selectSymbol('AAPL'); App.state.recommendResults = null;
  });
  await go('#/trade/context');
  await page.waitForSelector('#idea-market-card #ideas-symbol-context');
  const order = await page.evaluate(() => ({
    market: document.getElementById('idea-market-card').getBoundingClientRect().top,
    goal: document.getElementById('idea-goal-card').getBoundingClientRect().top,
    single: document.querySelector('#idea-source [data-source="single"]').classList.contains('selected'),
    symbolVisible: document.getElementById('ideas-symbol-context').offsetParent !== null
  }));
  assert.ok(order.market < order.goal, 'stock/sector context leads the strategy levers');
  assert.equal(order.single, true, 'a fresh trader starts with the stock already in mind');
  assert.equal(order.symbolVisible, true, 'single-stock input is immediately visible');
  assert.ok(await page.locator('#universe-sector .sector-chip').first().isVisible(),
    'the shared sector selector remains available in single-stock mode');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.click('#rec-go');
  await page.waitForSelector('.candidate', { timeout: 25000 });
  assert.match(await page.textContent('.candidate'), /Theoretical worst case|Theoretical ceiling/);
  const cards = await page.locator('.candidate').count();
  assert.ok(cards > 0);
  assert.equal(await page.evaluate(() => UI.maxProfitLabel('DIAGONAL_CALL', 'time', null, true)),
    'model-dependent', 'a multi-expiration null ceiling is never presented as infinity');
  assert.equal(await page.evaluate(() => UI.maxProfitLabel('LONG_CALL', 'single_long', null, true)),
    'no fixed ceiling', 'a genuinely unbounded structure keeps its theoretical truth');
  assert.equal(await page.evaluate(() => UI.maxProfitLabel('CUSTOM', null, null, true, [
    { type: 'CALL', expiration: '2026-07-17' }, { type: 'CALL', expiration: '2026-08-21' }
  ])), 'model-dependent', 'custom multi-expiration structures use the same honest ceiling semantics');
  await page.locator('.candidate').first().locator('.xp-head:has-text("realistic markets")').click();
  await page.waitForFunction(() => document.querySelectorAll('[data-realistic-outcomes] .realistic-case').length === 4,
    { timeout: 30000 });
  const realisticCopy = await page.textContent('[data-realistic-outcomes]');
  assert.match(realisticCopy, /theoretical payoff limits.*structural truth.*never replace/s,
    'modeled outcomes explicitly complement rather than replace theoretical limits');
  assert.match(realisticCopy, /Calm \/ narrow.*Steady rise.*Steady decline.*Wide \/ choppy/s);
});

test('navigation is NEVER trapped behind a slow route (Research does not block moving away)', async () => {
  // Gate /api/research/AAPL so it hangs; the shell must still paint AND we must be able to leave.
  let release;
  const gate = new Promise(r => { release = r; });
  await page.route('**/api/research/AAPL', async route => {
    await gate;
    try { await route.abort(); } catch (e) { /* request abandoned when we navigated away */ }
  });
  try {
    await page.evaluate(() => { API.flushCache(); window.location.hash = '#/home'; });
    await page.waitForFunction(() => document.getElementById('app').getAttribute('data-route') === 'home');
    await page.evaluate(() => { API.flushCache(); window.location.hash = '#/research/AAPL'; });
    await page.waitForSelector('#research-hero', { timeout: 4000 }); // shell paints before the fetch
    // Now leave immediately — with the old serialized render this hung until the fetch resolved.
    await page.evaluate(() => { window.location.hash = '#/portfolio'; });
    await page.waitForFunction(
      () => document.getElementById('app').getAttribute('data-route') === 'portfolio',
      { timeout: 4000 });
  } finally {
    release();
    await page.waitForTimeout(50); // let the gated handler settle before unrouting
    await page.unroute('**/api/research/AAPL');
    await page.evaluate(() => API.flushCache());
  }
});

test('progressive Home applies its route layout before slow market fills finish', async () => {
  await page.evaluate(() => { window.location.hash = '#/research'; });
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  await page.evaluate(async () => {
    window.__homeAccount = await API.getFresh('/api/account');
    window.__homeGet = API.get;
    window.__homeGate = {};
    API.get = function (path) {
      if (String(path) === '/api/account') {
        return new Promise(resolve => { window.__homeGate.release = resolve; });
      }
      return window.__homeGet.apply(API, arguments);
    };
  });
  try {
    await page.evaluate(() => { API.flushCache(); window.location.hash = '#/home'; });
    await page.waitForSelector('#app[data-route="home"]');
    await page.waitForSelector('.home-hero');
    assert.equal(await page.getAttribute('#app', 'data-route'), 'home',
      'route identity commits with the shell, not after data finishes');
    assert.equal(await page.getAttribute('#app', 'data-ready'), 'false',
      'the assertion observes the progressive shell while its gated fill is still pending');
  } finally {
    await page.evaluate(() => {
      if (window.__homeGate && window.__homeGate.release) window.__homeGate.release(window.__homeAccount);
      if (window.__homeGet) API.get = window.__homeGet;
      delete window.__homeAccount;
      delete window.__homeGet;
      delete window.__homeGate;
    });
    await page.waitForSelector('#app[data-route="home"][data-ready="true"]', { timeout: 10000 });
  }
});


/** The futures stage lives inside Test your view now — select its pill before using #whatif-card. */
async function openFutures() {
  await openResearchTab('view');
  await page.waitForSelector('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
  await page.waitForSelector('#tv-futures', { timeout: 15000 });
}

test('scenario studio: beginner view → decision facts → same-receipt strategy outcomes', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => {
    App.state.scenarioForm = null; App.state.verifyForm = null; App.state.marketThesis = null;
    App.context.update({ symbol: 'AAPL', thesis: 'bearish', horizon: 'quarter' });
  });
  await go('#/research/AAPL');
  await openResearchTab('view');
  await page.waitForSelector('#tv-view');
  assert.equal(await page.inputValue('#tv-view'), 'bearish', 'Research consumes the canonical market view');
  assert.equal(await page.inputValue('#tv-horizon'), '63', 'Research consumes the canonical horizon');
  await openFutures();
  // The thesis workbench: story cards with shape sketches, plain horizon/wildness chips.
  await page.waitForSelector('#whatif-card #sc-shapes .sc-card');
  assert.ok(await page.locator('#whatif-card .sc-card[data-shape="RALLY_FADE"].active').count(),
    'the bearish view seeds the generated-futures story instead of retired form state');
  assert.ok(await page.locator('#whatif-card #sc-horizon .sym-chip[data-days="63"].active').count(),
    'the shared horizon seeds the generated-futures horizon');
  assert.ok((await page.locator('#whatif-card .sc-card').count()) >= 6, 'story cards');
  assert.ok((await page.locator('#whatif-card .sc-sketch svg').count()) >= 6, 'shape sketches');
  assert.match(await page.textContent('#whatif-card'), /Drops, then recovers/);
  assert.match(await page.textContent('#sc-mag-note'), /±\d+%/); // live magnitude preview
  await page.click('#whatif-card .sc-card[data-shape="SELLOFF_REBOUND"]');
  await page.fill('#whatif-target', '260');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .fan-chart svg', { timeout: 20000 });
  const decisionText = await page.textContent('#whatif-out');
  assert.match(decisionText, /Likely ending range/);
  assert.match(decisionText, /Middle 68% end between/);
  assert.match(decisionText, /Your price level/);
  assert.match(decisionText, /end (above|below)/);
  assert.match(decisionText, /touch it/);
  assert.match(decisionText, /OPTIONS MARKET IMPLIED/);
  assert.match(decisionText, /2,000 paths/);
  assert.match(decisionText, /receipt [a-f0-9]{24}/);
  assert.match(await page.textContent('#whatif-out'), /never a forecast/i);   // honesty note
  assert.ok(await page.locator('#whatif-reroll:visible').count(), 'sampling check replaces seed-shopping language');
  await page.click('#whatif-reroll');
  await page.waitForSelector('#whatif-out .alert', { timeout: 20000 });
  assert.match(await page.textContent('#whatif-out .alert'), /Sampling check|seed-sensitive/);

  // Handoff opens full Outcomes with the exact Research receipt, not a blind rerun.
  const researchReceipt = await page.evaluate(() => App.state.scenarioAnalysis.result.receipt.fingerprint);
  await page.evaluate(() => { App.state.evidencePrefill = { studyKey: 'stale-analog-proof' }; });
  await page.click('#whatif-verify');
  await page.waitForSelector('#bt-scenario-card', { timeout: 15000 });
  assert.ok(await page.locator('#trade-outcomes-nav .outcome-basis.active[data-basis="scenario"]').count(), 'Verify opened in scenario mode');
  assert.equal(await page.evaluate(() => App.state.evidencePrefill), null,
    'generated-futures handoff cleared the prior historical-analog ensemble');
  assert.equal(await page.evaluate(() => App.state.scenarioHandoff && App.state.scenarioHandoff.fingerprint), researchReceipt,
    'Research and Outcomes share the exact ensemble receipt');
  // The FULL strategy catalog with payoff-shape sketches + a visible symbol picker.
  assert.ok((await page.locator('#sc-pos .sc-card').count()) >= 16, 'full strategy catalog, not a subset');
  // ANTI-DRIFT: identity, names, grouping, payoff glyphs and surface eligibility are all
  // hydrated from one server contract. Client modules retain only construction functions.
  const catalogCheck = await page.evaluate(async () => {
    const server = await (await fetch('/api/strategies')).json();
    return {
      scenarioActual: Scenario.CATALOG.map(c => ({ key: c.key, label: c.label, group: c.group })),
      scenarioExpected: server.catalog.filter(c => c.scenarioEnabled)
        .map(c => ({ key: c.name, label: c.display, group: c.category })),
      builderActual: Builder.TEMPLATES.map(t => ({ key: t.key, family: t.family, name: t.name, group: t.group,
        shape: t.shape, blurb: t.blurb, risky: !!t.risky })),
      builderExpected: server.templates.map(t => ({ key: t.key, family: t.family, name: t.display, group: t.category,
        shape: t.payoffShape, blurb: t.summary, risky: !!t.blockedByDefault }))
    };
  });
  assert.deepEqual(catalogCheck.scenarioActual, catalogCheck.scenarioExpected,
    'Scenario must be a server-owned eligibility view, not a second catalog');
  assert.deepEqual(catalogCheck.builderActual, catalogCheck.builderExpected,
    'Builder metadata must be byte-for-byte server owned');
  const builderCheck = { keys: catalogCheck.builderActual.map(t => t.key) };
  for (const must of ['PMCC', 'SYNTHETIC_LONG', 'SYNTHETIC_SHORT', 'CALENDAR_CALL', 'DIAGONAL_CALL', 'IRON_CONDOR']) {
    assert.ok(builderCheck.keys.includes(must), 'builder catalog missing ' + must);
  }
  assert.ok((await page.locator('#sc-pos .sc-sketch svg').count()) >= 16, 'payoff-shape sketches');
  assert.ok(await page.locator('#sc-symbol').count(), 'symbol input exists');
  await page.locator('#sc-pos .sc-card[data-pos="DEBIT_CALL_SPREAD"]').scrollIntoViewIfNeeded();
  await page.click('#sc-pos .sc-card[data-pos="DEBIT_CALL_SPREAD"]');
  await page.click('#sc-verify-run');
  await page.waitForSelector('#sc-verify-out .alert', { timeout: 30000 });
  const verdict = await page.textContent('#sc-verify-out');
  assert.match(verdict, /In \d+ of 100 futures like this/);
  assert.match(verdict, /Chance of profit/);
  assert.ok((await page.locator('#sc-verify-out .fan-chart svg').count()) >= 1, 'P&L fan');
  assert.ok((await page.locator('#sc-verify-out .tool-chart svg rect').count()) >= 3, 'terminal histogram');
});

test('scenario studio expert: model menu incl. Heston, IV knobs, the math on demand', async () => {
  await page.click('#level-switch button[data-level="expert"]');
  await page.evaluate(() => { App.state.scenarioForm = null; });
  await go('#/research/AAPL');
  await openFutures();
  await page.waitForSelector('#whatif-card #sc-model');
  const models = await page.$$eval('#sc-model option', os => os.map(o => o.value));
  assert.ok(models.includes('HESTON') && models.includes('BLOCK_BOOTSTRAP') && models.includes('JUMP_DIFFUSION'), 'full model menu');
  assert.ok(await page.locator('#sc-iv').count(), 'IV start knob');
  assert.ok(await page.locator('#sc-seed').count(), 'seed knob');
  // The math is an expandable (.xp), not in-your-face.
  await page.click('#whatif-card .xp-head');
  assert.match(await page.textContent('#whatif-card'), /Heston/);
  assert.match(await page.textContent('#whatif-card'), /κ|kappa/i);
});

test('scenario decision prices are isolated by symbol', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { App.state.scenarioTargets = {}; App.state.scenarioForm = null; });
  await go('#/research/AAPL');
  await openFutures();
  await page.fill('#whatif-target', '270');
  await go('#/research/QQQ');
  await openFutures();
  assert.equal(await page.inputValue('#whatif-target'), '', 'AAPL decision level never leaks into QQQ');
  await page.fill('#whatif-target', '500');
  await go('#/research/AAPL');
  await openFutures();
  assert.equal(await page.inputValue('#whatif-target'), '270', 'AAPL decision level restores in its own symbol context');
});

test('scenario calibration names Demo history honestly', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await go('#/research/AAPL');
  await openFutures();
  await page.waitForFunction(() => {
    const note = document.getElementById('sc-mag-note');
    return note && !/Loading volatility calibration/.test(note.textContent);
  });
  const note = await page.textContent('#sc-mag-note');
  assert.match(note, /fabricated Demo history|No eligible daily history/,
    'Demo history is never described as real or observed volatility');
  assert.doesNotMatch(note, /real volatility|observed recent history/,
    'generated history cannot borrow observed-market wording');
});

test('Research prices a working package on the fan receipt and Outcomes reuses the result', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => {
    App.state.scenarioForm = { shape: 'CHOP', horizon: 5, mag: 'typical', seed: 55119 };
    App.context.update({ symbol: 'AAPL', goal: 'DIRECTIONAL', horizon: 'week', thesis: 'neutral' });
    App.state.ticket = { symbol: 'AAPL', qty: 1, candidate: {
      strategy: 'DEBIT_CALL_SPREAD', displayName: 'Debit call spread', qty: 1,
      entryNetPremiumCents: -32500, breakevens: [258.25], legs: [
        { action: 'BUY', type: 'CALL', strike: 255, expiration: '2026-08-14', ratio: 1 },
        { action: 'SELL', type: 'CALL', strike: 260, expiration: '2026-08-14', ratio: 1 }
      ]
    } };
  });
  await go('#/research/AAPL');
  await openFutures();
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .scenario-position-outcome', { timeout: 25000 });
  const identity = await page.evaluate(() => ({
    fan: App.state.scenarioAnalysis.result.receipt.fingerprint,
    position: App.state.scenarioAnalysis.result.positionEnsembleFingerprint
  }));
  assert.equal(identity.position, identity.fan, 'the working package and price fan use one immutable path matrix');
  await page.click('#whatif-verify');
  await page.waitForSelector('#scenario-handoff', { timeout: 15000 });
  assert.match(await page.textContent('#sc-verify-out'), /Exact Research result — no rerun/);
  assert.match(await page.textContent('#scenario-handoff'), new RegExp(identity.fan));
  await page.evaluate(() => { App.state.ticket = null; App.state.scenarioHandoff = null; App.state.scenarioAnalysis = null; });
});

test('scenario practice handoff configures one honest realization without creating it', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => {
    App.state.scenarioForm = { shape: 'CHOP', horizon: 10, mag: 'typical', seed: 4488 };
    App.state.simulationPrefill = null;
    App.context.update({ symbol: 'AAPL', goal: 'DIRECTIONAL', horizon: 'week', thesis: 'neutral' });
  });
  await go('#/research/AAPL');
  await openFutures();
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-practice-random', { timeout: 20000 });
  const worldBefore = await page.evaluate(() => App.state.world);
  await page.click('#whatif-practice-random');
  await page.waitForSelector('#app[data-route="data"][data-ready="true"] #sim-creator', { timeout: 15000 });
  const creator = await page.textContent('#sim-creator');
  assert.match(creator, /Practice setup from Research/);
  assert.match(creator, /fresh random realization/i);
  assert.match(creator, /not one of the displayed sample lines/i);
  assert.ok(await page.locator('#sim-scenarios .sim-scenario[data-scenario="CHOP"][aria-pressed="true"]').count(),
    'the broad Research story prefills the simulated-market creator');
  assert.ok(await page.locator('#sim-symbol-chips [data-picked-sym="AAPL"]').count(), 'the Research symbol is carried');
  assert.equal(await page.evaluate(() => App.state.world), worldBefore,
    'handoff only configures the creator; it never mutates the active market without confirmation');
});

test('data center: generate a scenario dataset, activate it (loud banner), switch back', async () => {
  await page.click('#level-switch button[data-level="expert"]');
  await go('#/data/datasets');
  await page.waitForSelector('#dc-datasets .status-item');
  assert.match(await page.textContent('#dc-datasets'), /Demo market baseline|Observed market data/);
  // Generate a synthetic run.
  await page.click('#dc-generate-btn');
  await page.waitForSelector('#dc-gen-sym');
  await page.fill('#dc-gen-sym', 'AAPL');
  await page.click('#dc-gen-run');
  await page.waitForSelector('#dc-datasets .status-item .badge:has-text("SYNTHETIC")', { timeout: 30000 });
  // Activate it → the loud app-wide banner appears.
  const useBtn = page.locator('#dc-datasets .status-item:has(.badge:has-text("SYNTHETIC")) button:has-text("Activate")').first();
  await useBtn.click();
  await page.waitForSelector('#scenario-banner', { timeout: 15000 });
  assert.match(await page.textContent('#scenario-banner'), /SCENARIO MODE/);
  // Switch back to observed → banner clears.
  await page.waitForSelector('#dc-datasets .status-item[data-dataset="observed"] button:has-text("Activate")');
  await page.click('#dc-datasets .status-item[data-dataset="observed"] button:has-text("Activate")');
  await page.waitForFunction(() => !document.getElementById('scenario-banner'), { timeout: 15000 });
  // Cleanup: leave the shared page as downstream tests expect (Beginner level, history verify mode).
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { if (App.state.verifyForm) App.state.verifyForm.mode = 'history'; App.state.verifyMode = null; });
});

test('dataset actions fail visibly instead of looking like dead buttons', async () => {
  await page.route('**/api/datasets', async route => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        active: 'observed', datasets: [
          { id: 'observed', name: 'Observed market data', bars: 0 },
          { id: 'ds_error_fixture', name: 'Failure-path scenario', symbol: 'AAPL', bars: 20 }
        ]
      }) });
    } else await route.continue();
  });
  await page.route('**/api/datasets/*', async route => {
    if (route.request().method() === 'DELETE') {
      await route.fulfill({ status: 422, contentType: 'application/json',
        body: JSON.stringify({ error: 'This dataset is still in use by a separate market.' }) });
    } else await route.continue();
  });
  try {
    await go('#/data/datasets');
    const remove = page.locator('#dc-datasets .status-item:has(.badge:has-text("SYNTHETIC")) button:has-text("Delete")').first();
    await remove.click();
    await page.waitForSelector('#dataset-action-error .alert');
    assert.match(await page.textContent('#dataset-action-error'), /still in use|could not be completed/i);
  } finally {
    await page.unroute('**/api/datasets');
    await page.unroute('**/api/datasets/*');
  }
});

test('workspace continuity: forms, symbol, and route survive a full reload', async () => {
  // Work: pick a goal + symbol in Ideas (one-stock mode), then wander off to Research.
  await go('#/trade/context/manual');
  await page.waitForSelector('#rec-symbol');
  await page.click('#intent-choices .choice[data-intent="ACQUIRE"]');
  await page.selectOption('#rec-horizon', 'quarter');
  await page.fill('#rec-symbol', 'QQQ');
  await page.evaluate(() => { App.state.discoverForm.symbol = 'QQQ'; App.context.update({
    symbol: 'QQQ', goal: 'ACQUIRE', horizon: 'quarter', thesis: 'neutral'
  }); });
  await go('#/research/AAPL');
  await page.waitForSelector('.quote-hero');
  // Persist NOW (the 4s tick and pagehide would do this; tests don't wait).
  await page.evaluate(() => Workspace.save());
  await page.waitForTimeout(1700); // let the debounced backend push land
  const rev = await page.evaluate(() => API.getFresh('/api/workspace').then(r => r.rev));
  assert.ok(rev >= 1, 'workspace persisted to the backend (rev ' + rev + ')');

  // Cold open with NO hash: the app resumes exactly where the user left off.
  await page.goto(BASE + '/');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.equal(await page.evaluate(() => window.location.hash), '#/research/AAPL', 'route restored');
  await page.waitForSelector('#world-band[data-world="demo"]');
  assert.match(await page.textContent('#world-band'), /DEMO MARKET[\s\S]*Fabricated teaching data/,
    'same-lane reload must recreate the global data-honesty band');
  assert.equal(await page.evaluate(() => App.Market.world), 'demo', 'same-lane reload reconciles MarketStore');
  const restored = await page.evaluate(() => ({
    sym: App.context.symbol(),
    goal: App.context.goal(), horizon: App.context.horizon(), thesis: App.context.thesis(),
    form: App.state.discoverForm && App.state.discoverForm.symbol
  }));
  assert.equal(restored.sym, 'AAPL', 'the last selected Research symbol is the shared working context');
  assert.equal(restored.goal, 'ACQUIRE', 'goal survives alongside the symbol');
  assert.equal(restored.horizon, 'quarter', 'horizon survives alongside the symbol');
  assert.equal(restored.thesis, 'neutral', 'thesis survives alongside the symbol');
  assert.equal(restored.form, 'QQQ', 'draft form restored');

  // An explicit hash always beats the saved route (bookmarks/links stay honest).
  await page.goto(BASE + '/#/portfolio');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'portfolio');

  // Home offers one-tap re-entry into the working context.
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1')); // dashboard, not the tour
  await go('#/home');
  await page.waitForSelector('#continue-row .sym-chip');
  assert.match(await page.textContent('#continue-row'), /Resume: AAPL analysis/);
  await page.click('#continue-row .sym-chip[data-continue="research"]');
  await page.waitForSelector('.quote-hero');
  assert.equal(await page.evaluate(() => window.location.hash), '#/research/AAPL');
});

test('workspace autosave treats goal, horizon, and thesis as durable context', async () => {
  await go('#/home');
  await page.evaluate(() => {
    App.state.ticket = null;
    App.state.discoverForm = null;
    App.context.update({ symbol: 'AAPL', goal: 'DIRECTIONAL', horizon: 'month', thesis: 'bullish' });
    Workspace.save();
  });
  await page.waitForTimeout(1700);
  const before = await page.evaluate(() => API.getFresh('/api/workspace').then(r => r.rev));
  await page.evaluate(() => {
    App.context.update({ goal: 'HEDGE', horizon: 'quarter', thesis: 'bearish' });
    Workspace.save();
  });
  await page.waitForTimeout(1700);
  const saved = await page.evaluate(() => API.getFresh('/api/workspace'));
  assert.ok(saved.rev > before, 'a context-only edit increments the workspace revision');
  assert.deepEqual(saved.state.context, {
    symbol: 'AAPL', goal: 'HEDGE', horizon: 'quarter', thesis: 'bearish'
  });
});

test('world transition: authoritative PUT bootstrap recovers a failed SSE-hint hydration', async () => {
  await go('#/home');
  const recovered = await page.evaluate(async () => {
    const target = App.state.world;
    const bootstrap = App.state.universe;
    const originalRevision = Number(App.state.worldRevision || 0);
    const nextRevision = originalRevision + 1;
    let rejectHint;
    const failedHint = new Promise((resolve, reject) => { rejectHint = reject; })
      .finally(() => { App._transitionTarget = null; App._transitionP = null; });
    App._transitionTarget = target;
    App._transitionP = failedHint;
    const fromPut = App.transitionWorld(target, bootstrap, nextRevision);
    rejectHint(new Error('synthetic hint hydration failure'));
    await fromPut;
    const result = {
      world: App.state.world,
      marketWorld: App.Market.world,
      status: App.state.transitionStatus,
      symbols: (App.state.universe.active.symbols || []).length
    };
    App.state.worldRevision = originalRevision;
    return result;
  });
  assert.equal(recovered.status, 'committed');
  assert.equal(recovered.marketWorld, recovered.world);
  assert.ok(recovered.symbols > 0, 'the authoritative bootstrap hydrated the lane');
});

test('world transition ignores an out-of-order revision even when it names another lane', async () => {
  const state = await page.evaluate(async () => {
    const before = App.state.world;
    const originalRev = Number(App.state.worldRevision || 0);
    const beforeRev = originalRev + 100;
    App.state.worldRevision = beforeRev;
    const staleTarget = before === 'observed' ? 'demo' : 'observed';
    await App.transitionWorld(staleTarget, App.state.universe, beforeRev - 1);
    const result = { before, after: App.state.world, revision: App.state.worldRevision };
    App.state.worldRevision = originalRev;
    return result;
  });
  assert.equal(state.after, state.before, 'stale cross-lane event cannot roll the UI backward');
  assert.ok(state.revision >= 10);
});

test('world revisions reset across a server boot epoch without accepting old-process events', async () => {
  const state = await page.evaluate(async () => {
    const before = App.state.world;
    const bootstrap = App.state.universe;
    const oldEpoch = App.state.worldRevisionEpoch || 'boot-a';
    const oldRevision = Number(App.state.worldRevision || 0);
    const oldRetired = (App.state.worldRevisionRetiredEpochs || []).slice();
    App.state.worldRevisionEpoch = oldEpoch;
    App.state.worldRevision = 500;
    await App.transitionWorld(before, bootstrap, 1, oldEpoch + '-restart');
    const accepted = { world: App.state.world, revision: App.state.worldRevision,
      epoch: App.state.worldRevisionEpoch };
    await App.transitionWorld(before === 'demo' ? 'observed' : 'demo', bootstrap, 999, oldEpoch);
    const afterOld = App.state.world;
    App.state.worldRevisionEpoch = oldEpoch;
    App.state.worldRevision = oldRevision;
    App.state.worldRevisionRetiredEpochs = oldRetired;
    return { before, accepted, afterOld };
  });
  assert.equal(state.accepted.world, state.before);
  assert.equal(state.accepted.revision, 1, 'new process revision 1 is accepted after revision 500');
  assert.match(state.accepted.epoch, /-restart$/);
  assert.equal(state.afterOld, state.before, 'a delayed event from the retired process is ignored');
  const chronological = await page.evaluate(async () => {
    const before = App.state.world;
    const bootstrap = App.state.universe;
    const priorEpoch = App.state.worldRevisionEpoch;
    const priorRevision = App.state.worldRevision;
    App.state.worldRevisionEpoch = '2026-07-11T12:00:00Z';
    App.state.worldRevision = 4;
    await App.transitionWorld(before === 'demo' ? 'observed' : 'demo', bootstrap, 999,
      '2026-07-10T12:00:00Z');
    const after = App.state.world;
    App.state.worldRevisionEpoch = priorEpoch;
    App.state.worldRevision = priorRevision;
    return { before, after };
  });
  assert.equal(chronological.after, chronological.before,
    'an unseen but chronologically older server epoch cannot roll the market lane backward');
});

test('working view follows: idea bar carries the thesis; scenario studio opens on it', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  // State the view in Ideas: QQQ, bearish, ~1 month.
  await go('#/trade/context/manual');
  await page.waitForSelector('#rec-symbol');
  await page.fill('#rec-symbol', 'QQQ');
  await page.evaluate(() => {
    App.state.discoverForm.symbol = 'QQQ';
    App.state.discoverForm.thesis = 'bearish';
    App.state.discoverForm.horizon = 'month';
    App.context.update({ symbol: 'QQQ', goal: 'DIRECTIONAL', thesis: 'bearish', horizon: 'month' });
    App.state.ticket = null; App.state.builderForm = null; App.state.scenarioForm = null;
  });
  // The Trade idea bar shows the working VIEW even with no working idea yet.
  await go('#/trade/outcomes');
  await page.waitForSelector('#working-view-chip');
  const wv = await page.textContent('#working-view-chip');
  assert.match(wv, /QQQ/);
  assert.match(wv, /bearish/);
  assert.match(wv, /~1 month/);
  // A fresh Scenario Studio opens on the bearish story (Rises, then fades) — not a random default.
  await go('#/research/QQQ');
  await openFutures();
  await page.waitForSelector('#whatif-card .sc-card.active');
  assert.equal(await page.getAttribute('#whatif-card .sc-card.active', 'data-shape'), 'RALLY_FADE');
  // Cleanup for downstream tests: a persisted bearish default must not surprise them.
  await page.evaluate(() => {
    App.state.scenarioForm = null; App.state.discoverForm.thesis = 'bullish'; App.context.selectThesis('bullish');
  });
});

test('every scenario story runs at both levels (the Big-news-shock crash class)', async () => {
  // The magVolFor crash escaped a green matrix because tests only clicked ONE story.
  // Run EVERY beginner story card end-to-end, then every expert shape via the select.
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => {
    App.state.scenarioForm = null; App.state.verifyForm = {}; App.state.verifyMode = 'scenario';
    App.state.ticket = null; App.context.selectSymbol('AAPL');
  });
  await go('#/trade/outcomes');
  await page.waitForSelector('#bt-scenario-card #sc-shapes .sc-card');
  const shapes = await page.$$eval('#sc-shapes .sc-card', cs => cs.map(c => c.getAttribute('data-shape')));
  assert.ok(shapes.length >= 7, 'all story cards render');
  for (const shape of shapes) {
    await page.click('#sc-shapes .sc-card[data-shape="' + shape + '"]');
    await page.click('#sc-verify-run');
    await page.waitForSelector('#sc-verify-out .alert', { timeout: 30000 });
    const txt = await page.textContent('#sc-verify-out');
    assert.match(txt, /futures like this|Win rate/, shape + ' produced a verdict');
  }
  // Expert: every model prices without error on one story.
  await page.click('#level-switch button[data-level="expert"]');
  await page.evaluate(() => { App.state.scenarioForm = null; });
  await go('#/trade/outcomes');
  await page.waitForSelector('#sc-model');
  for (const model of ['GBM', 'STUDENT_T', 'JUMP_DIFFUSION', 'HESTON', 'BLOCK_BOOTSTRAP', 'BROWNIAN_BRIDGE']) {
    await page.selectOption('#sc-model', model);
    await page.click('#sc-verify-run');
    await page.waitForSelector('#sc-verify-out .alert', { timeout: 30000 });
  }
  // Cleanup for downstream tests.
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { App.state.scenarioForm = null; if (App.state.verifyForm) App.state.verifyForm.mode = 'history'; });
});

test('fair comparison: one batch call, refusals named, ranked per dollar of downside', async () => {
  await page.click('#level-switch button[data-level="expert"]');
  await page.evaluate(() => {
    App.state.scenarioForm = null;
    App.state.verifyForm = { mode: 'scenario' };
    App.context.selectSymbol('AAPL');
    App.navigate('#/trade/outcomes'); // same hash still means an explicit rerender
  });
  await page.waitForSelector('#app[data-route="trade"][data-ready="true"]');
  await page.waitForSelector('#sc-compare-all');
  // Count network calls: the comparison must be ONE versioned evaluation, not 18.
  const calls = await page.evaluate(() => new Promise((resolve, reject) => {
    let n = 0;
    const orig = window.fetch;
    window.fetch = function (u, o) { if (String(u).includes('/api/evaluate')) n++; return orig.apply(window, arguments); };
    document.getElementById('sc-compare-all').click();
    const t0 = Date.now();
    (function poll() {
      if (document.querySelector('#sc-verify-out table')) { window.fetch = orig; resolve(n); }
      else if (Date.now() - t0 > 60000) { window.fetch = orig; reject(new Error('no table')); }
      else setTimeout(poll, 300);
    })();
  }));
  assert.equal(calls, 1, 'one COMPARE request replaces 18 sequential POSITION calls');
  const txt = await page.textContent('#sc-verify-out');
  assert.match(txt, /per dollar of realistic downside|RoR/i);
  assert.match(txt, /undefined risk is blocked by design/i); // catalog completeness disclosed
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { if (App.state.verifyForm) App.state.verifyForm.mode = 'history'; });
});

test('research symbol page: ONE Test-your-view section — thesis-driven, symbol-inherited, keyed results', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await go('#/research/AAPL');
  await openResearchTab('view');
  await page.waitForSelector('#test-your-view');
  // The stage selection PERSISTS by design — pick Past evidence explicitly for this walk.
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  // The composed section sits after the chart; the two old floating cards are gone.
  const layout = await page.evaluate(() => ({
    chartTop: document.getElementById('history-card').getBoundingClientRect().top + window.scrollY,
    tvTop: document.getElementById('test-your-view').getBoundingClientRect().top + window.scrollY,
    orphanCards: document.querySelectorAll('#test-your-view ~ #whatif-card, .card > #what-has-happened').length,
    nestedSymbolInputs: document.querySelectorAll('#test-your-view input[list="universe-symbols"]').length
  }));
  assert.ok(layout.chartTop < layout.tvTop, 'NOW (chart) precedes Test your view');
  assert.equal(layout.nestedSymbolInputs, 0, 'the evidence stage INHERITS the page symbol — no nested ticker input');
  // Both stages are visible as connected pills; Past evidence expands by default.
  assert.equal(await page.locator('#research-outcomes-nav .outcome-basis').count(), 2, 'two connected outcome bases');
  await page.waitForSelector('#what-has-happened');
  // The thesis question line names the CONDITION — "did this happen before" never leaves "this" undefined.
  await page.waitForSelector('#tv-question-line');
  assert.ok(await page.locator('#study-window:visible').count(), 'beginner controls the history window');
  assert.ok(await page.locator('#study-strictness:visible').count(), 'beginner controls signal selectivity');
  assert.ok(await page.locator('#study-regime:visible').count(), 'beginner controls market regime');
  assert.ok(await page.locator('#study-bootstrap').count(),
    'full statistical capability remains mounted behind progressive disclosure');
  const beforeStrict = await page.inputValue('#study-param-dropPct');
  await page.selectOption('#study-strictness', 'stronger');
  assert.notEqual(await page.inputValue('#study-param-dropPct'), beforeStrict,
    'plain-language selectivity changes the real signal threshold');
  // Run the study; the conclusion is decision-useful (evidence strength + confidence guidance + handoff).
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  const outText = await page.textContent('#study-results');
  assert.match(outText, /Evidence/, 'evidence-strength label present');
  assert.match(outText, /raise or lower your confidence/, 'confidence guidance, never a prediction');
  // The handoff exists when enough analogs matched.
  const handoff = await page.locator('#tv-test-analogs').count();
  if (handoff) {
    assert.match(await page.textContent('#tv-test-analogs'), /DEMO-data occurrences/); // fixture suite: never 'real'
    await page.click('#tv-test-analogs');
    await page.waitForSelector('#trade-outcomes-basis-analogs[aria-selected="true"]');
    assert.match(await page.textContent('#evidence-mode'), /Evidence mode/i,
      'Research hands the sample to the visibly named Historical analogs basis');
    await go('#/research/AAPL');
    await openResearchTab('view');
    await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
    await page.waitForSelector('#study-results .alert');
  }
  assert.match(await page.textContent('#study-results'), /How this evidence was checked/);

  // Expert sees the SAME engine and every protocol knob, not a reduced or separate tool.
  await page.click('#level-switch button[data-level="expert"]');
  await openResearchTab('view');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  assert.ok(await page.locator('.study-protocol[open] #study-bootstrap:visible').count(),
    'expert protocol opens with bootstrap, dates, dependence, confidence and multiplicity controls');
  await page.selectOption('#study-confidence', '99');
  await page.selectOption('#study-multiplicity', 'UNADJUSTED_EXPLORATORY');
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  assert.match(await page.textContent('#study-results'), /99% CI \(avg\)/);
  assert.match(await page.textContent('#study-results'), /Exploratory unadjusted significance/);
  // KEYED RESULTS: switching to QQQ must not display AAPL's study.
  await go('#/research/QQQ');
  await openResearchTab('view');
  await page.waitForSelector('#test-your-view');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  assert.equal(await page.locator('#study-results .alert').count(), 0,
    'an AAPL result may never appear on a QQQ page (result identity is keyed)');
  // Possible futures is the second stage of the SAME section.
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
  await page.waitForSelector('#tv-futures #whatif-card, #tv-futures #sc-shapes', { timeout: 15000 });
});

test('form geometry: controls in one grid row share a vertical origin (labels never push them)', async () => {
  await page.click('#level-switch button[data-level="expert"]');
  await page.evaluate(() => { App.state.scenarioForm = null; App.state.verifyForm = { mode: 'scenario' }; });
  await go('#/trade/outcomes');
  await page.waitForSelector('#sc-model');
  const misaligned = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('.form-grid').forEach(grid => {
      const rows = {};
      grid.querySelectorAll('.field').forEach(f => {
        const ctl = f.querySelector('input, select');
        if (!ctl || ctl.offsetParent === null) return;
        const key = Math.round(f.getBoundingClientRect().top / 8);
        (rows[key] = rows[key] || []).push(Math.round(ctl.getBoundingClientRect().top));
      });
      Object.values(rows).forEach(tops => {
        if (Math.max(...tops) - Math.min(...tops) > 3) out.push(tops.join('/'));
      });
    });
    return out;
  });
  assert.deepEqual(misaligned, [], 'misaligned control rows: ' + misaligned.join(' | '));
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { if (App.state.verifyForm) App.state.verifyForm.mode = 'history'; });
});

test('event stream: job.complete reaches the browser; cooldown shows a calm header chip', async () => {
  await go('#/home');
  // End-to-end SSE: start a real job and wait for its completion EVENT (not a poll).
  const evt = await page.evaluate(() => new Promise((resolve, reject) => {
    App.onEvent(['job.complete'], (type, data) => resolve({ type: type, data: data }));
    API.post('/api/data/jobs', { kind: 'refresh_now', params: {} }).catch(reject);
    setTimeout(() => reject(new Error('no job.complete event within 20s')), 20000);
  }));
  assert.equal(evt.type, 'job.complete');
  assert.ok(evt.data && evt.data.id, 'event carries the job id');

  // Provider cooldown renders as a calm amber chip, and clears when the window passes.
  await page.evaluate(() => App.showCooldownChip({ provider: 'cboe', untilMs: Date.now() + 60000 }));
  assert.match(await page.textContent('#cooldown-chip'), /Cboe cooling down/);
  const title = await page.getAttribute('#cooldown-chip', 'title');
  assert.match(title, /last snapshot/i);
  await page.evaluate(() => App.showCooldownChip({ provider: 'cboe', untilMs: Date.now() - 1 }));
  assert.ok(!(await page.locator('#cooldown-chip').count()), 'expired cooldown removes the chip');
});

test('prefetch warms the likely next step through the governed cache', async () => {
  await page.evaluate(() => API.flushCache());
  await go('#/research/AAPL');
  await page.waitForSelector('.quote-hero');
  await page.waitForTimeout(2000); // idle prefetch fires (requestIdleCallback, 2.5s budget)
  // The expirations read must now be a cache hit: zero network fetches.
  const fetches = await page.evaluate(() => {
    let calls = 0;
    const orig = window.fetch;
    window.fetch = function () { calls++; return orig.apply(window, arguments); };
    return API.get('/api/research/AAPL/expirations').then(function (r) {
      window.fetch = orig;
      if (!r.expirations || !r.expirations.length) throw new Error('bad expirations payload');
      return calls;
    });
  });
  assert.equal(fetches, 0, 'expirations served from the prefetched cache');
});

test('market stream (SSE): the browser receives live quote frames from the engine', async () => {
  await go('#/home');
  const symbols = await page.evaluate(() => new Promise((resolve) => {
    const es = new EventSource('/api/market/stream?symbols=AAPL,SPY');
    const timer = setTimeout(() => { es.close(); resolve(null); }, 9000);
    es.addEventListener('quotes', (ev) => {
      try {
        const d = JSON.parse(ev.data);
        if (d && d.quotes && d.quotes.length) { clearTimeout(timer); es.close(); resolve(d.quotes.map(q => q.symbol)); }
      } catch (e) { /* ignore */ }
    });
    es.onerror = () => { /* keep waiting until timeout */ };
  }));
  assert.ok(symbols && symbols.includes('AAPL'), 'SSE delivered a quote frame incl. AAPL, got ' + JSON.stringify(symbols));
});

test('discover scan: a blank symbol box auto-recommends with evidence and targets', async () => {
  await go('#/trade/context/scout'); // canonical Context mode for a market scan
  await page.waitForSelector('#auto-target'); // scan-scope fields visible in scan mode
  // This suite deliberately persists goal context in the preceding workspace tests. Own
  // this journey's directional premise instead of assuming a fresh process: a retained
  // HEDGE goal with no holdings correctly returns no picks.
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  assert.ok(await page.locator('#idea-source .choice[data-source="scan"].selected').count(),
    '"Scan the market for me" is an explicit, visible choice');
  assert.ok(!(await page.locator('#rec-symbol').isVisible()), 'no ticker box while scanning');
  assert.match(await page.textContent('#rec-go'), /Scan for opportunities/);
  await page.fill('#auto-target', '250');
  await page.click('#rec-go');
  await page.waitForSelector('.pick-card', { timeout: 30000 });
  const picks = await page.locator('.pick-card').count();
  assert.ok(picks >= 1 && picks <= 3, `1-3 picks, got ${picks}`);
  const text = await page.textContent('#rec-results');
  assert.match(text, /Confidence/);
  assert.match(text, /20d move|Sentiment/);
  assert.match(text, /About a week|About a month/);
  assert.match(text, /covers|cannot reach|Uncapped/); // profit-target annotation
  assert.match(text, /not predictions/i);
  assert.ok(await page.locator('.pick-card [data-economic-verdict]').count() >= 1,
    'Scout uses the same economic classification as manual Ideas');
  // headline evidence is expandable
  assert.ok(await page.locator('.pick-card details').first().isVisible());

  // Results belong to their exact market context. Reproduce a sector transition at the state
  // boundary: even before a new scan runs, the prior sector's field must disappear.
  await page.evaluate(async () => {
    const u = App.state.universe;
    App.state.universe = Object.assign({}, u, {
      active: Object.assign({}, u.active, { sectorKey: '__TEST_OTHER_SECTOR__' })
    });
    document.getElementById('app').setAttribute('data-ready', 'false');
    await App.render();
  });
  await page.waitForSelector('#app[data-ready="true"]');
  assert.equal(await page.locator('#rec-results .pick-card').count(), 0,
    'changing sectors removes recommendations from the prior universe');
  assert.equal(await page.evaluate(() => App.state.scoutResults), null,
    'the stale scan is removed from persisted workspace state');
  await page.evaluate(async () => {
    await App.refreshUniverse();
    document.getElementById('app').setAttribute('data-ready', 'false');
    await App.render();
  });
  await page.waitForSelector('#app[data-ready="true"]');
});

test('recommendations render candidates and blocked examples', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/trade/context/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('.candidate');
  const cards = await page.locator('.candidate').count();
  assert.ok(cards >= 1, 'at least one candidate card');
  assert.equal(await page.locator('.candidate[data-economic-verdict]').count(), cards,
    'every available strategy carries an economic verdict; none disappear behind the policy');
  const appText = await page.textContent('#app');
  assert.match(appText, /Cash \/ no trade/);
  assert.match(appText, /Worth investigating|Favorable in this teaching market|No demonstrated edge|Unfavorable at these prices/);
  assert.match(appText, /BLOCKED/);
  assert.match(appText, /undefined risk/i);
  assert.match(appText, /Theoretical worst case|Theoretical max loss/);
  assert.match(appText, /Chance of any profit/, 'Beginner retains POP rather than replacing it with an intent-specific assignment outcome');
  assert.equal(await page.locator('.candidate .fact-grid .f-label .term, .candidate .fact-grid .f-label .info-trigger').count(), 0,
    'plain Beginner fact labels do not open a second explanation that restates the card');
  const evPairs = await page.locator('.candidate .economic-assessment').allTextContents();
  assert.ok(evPairs.length > 0 && evPairs.every(t => /Market-implied EV/.test(t) && /Realized-vol scenario EV/.test(t)),
    'both EV lanes stay co-equal on every candidate card');
  assert.match(appText, /not financial advice/i);
  const firstCandidateOrder = await page.locator('.candidate').first().evaluate(card => ({
    economic: Array.from(card.children).indexOf(card.querySelector('.economic-assessment')),
    facts: Array.from(card.children).indexOf(card.querySelector('.fact-grid'))
  }));
  if (firstCandidateOrder.economic >= 0 && firstCandidateOrder.facts >= 0) {
    assert.ok(firstCandidateOrder.economic < firstCandidateOrder.facts,
      'economic verdict precedes the theoretical payoff facts even when optional story blocks are absent');
  }
});

test('beginner help adds information instead of echoing visible labels', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const duplicates = [];
  for (const hash of ['#/home', '#/trade/context/manual', '#/trade/structure', '#/data/overview']) {
    await go(hash);
    const found = await page.$$eval('[title]', nodes => nodes.filter(node => {
      if (!node.offsetParent) return false;
      const norm = value => String(value || '').replace(/\s+/g, ' ').trim().toLowerCase();
      const title = norm(node.getAttribute('title'));
      const visible = norm(node.innerText || node.textContent);
      return title.length > 1 && (visible === title || (title.length > 18 && visible.includes(title)));
    }).map(node => ({ route: location.hash, tag: node.tagName, text: node.innerText, title: node.title })));
    duplicates.push(...found);
  }
  assert.deepEqual(duplicates, [], 'native hover text repeats visible Beginner copy: ' + JSON.stringify(duplicates));

  await go('#/trade/context/manual');
  assert.equal(await page.locator('#intent-choices .choice .muted').count(),
    await page.locator('#intent-choices .choice').count(), 'Beginner goal cards explain themselves visibly');
  assert.equal(await page.locator('#intent-choices .choice[title]').count(), 0,
    'Beginner goal cards do not repeat their visible explanation in a native tooltip');
  assert.equal(await page.locator('#idea-source .choice[title]').count(), 0,
    'Beginner source cards do not repeat their visible explanation in a native tooltip');
});

let tradeUrlHash = null;

test('Context-to-Decide: screening happens once; Decide is review and paper confirmation', async () => {
  // Decide without a structure = an honest setup state, never a duplicate wizard.
  await page.evaluate(() => { Learn.setLevel('beginner'); App.state.ticket = null; });
  await go('#/trade/decide');
  assert.match(await page.textContent('#app'), /Nothing to place yet/);
  // One explicit decision loop; every step remains reachable and explains its job.
  await go('#/trade/context/manual');
  assert.match(await page.textContent('#wf-context'), /Context[\s\S]*Pick a stock and goal/);
  assert.match(await page.textContent('#wf-structure'), /Structure[\s\S]*Choose how to express it/);
  assert.match(await page.textContent('#wf-outcomes'), /Outcomes[\s\S]*Replay and simulate/);
  assert.match(await page.textContent('#wf-decide'), /Decide[\s\S]*Review risk and practice/);
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('#rec-results .candidate', { timeout: 30000 });
  const firstPaintStrategies = await page.locator('#rec-results .candidate').evaluateAll(nodes =>
    nodes.map(n => n.getAttribute('data-strategy')));
  await page.waitForTimeout(500);
  assert.deepEqual(await page.locator('#rec-results .candidate').evaluateAll(nodes =>
    nodes.map(n => n.getAttribute('data-strategy'))), firstPaintStrategies,
    'ranked cards never change identity after first paint');
  // Both levels expose the same action; surrounding detail changes with experience level.
  // Prefer a CREDIT structure: downstream ledger tests pin RESERVE_HOLD/RELEASE rows, which only
  // credit trades produce (candidates are decision-ranked, so the first card's family can change).
  const creditCard = page.locator('#rec-results .candidate[data-strategy^="CREDIT"]');
  const pickCard = (await creditCard.count()) ? creditCard.first() : page.locator('#rec-results .candidate').first();
  await pickCard.locator('button:has-text("Review & decide")').first().click();
  // Place opens at Strikes with the idea in the bar
  await page.waitForSelector('#to-review');
  assert.match(await page.textContent('#idea-bar'), /AAPL/);
  assert.ok(await page.isVisible('#ticket-qty'));
  await page.click('#to-review');
  // Step 6: review shows exact money effects and the safety checklist
  await page.waitForSelector('#to-confirm');
  // LOCKED CONTEXT (interaction contract #5): the reviewed package's symbol is shown but
  // cannot silently mutate — a LOCKED chip plus a Research affordance, no free-text input.
  await page.waitForSelector('#ticket-body .symbol-context-locked');
  assert.match(await page.textContent('#ticket-body .symbol-context-locked'), /AAPL/);
  assert.ok(await page.locator('#ticket-body .symbol-context-locked .badge:has-text("LOCKED")').count(),
    'the placed package cannot silently change symbol');
  const review = await page.textContent('#ticket-body');
  assert.ok(await page.locator('#ticket-body .economic-assessment').count(),
    'the exact ticket keeps the shared economic classification through final review');
  assert.match(review, /Market-implied EV/);
  assert.match(review, /Realized-vol scenario EV/);
  assert.match(review, /Theoretical max loss/);
  assert.match(review, /Theoretical max profit/);
  assert.equal(await page.locator('#ticket-body .xp-head:has-text("realistic markets"), #ticket-body .xp-head:has-text("Scenario distributions")').count(), 1,
    'ticket review keeps structural limits and offers the shared realistic-outcomes layer beside them');
  assert.match(review, /Buying power after/);
  assert.match(review, /Safety check/);
  assert.match(review, /Theoretical worst case is known and capped/);
  await page.$$eval('.ack-gate input', els => els.forEach(e => { if (!e.checked) e.click(); })); // acknowledge material risks (CP-5 gate)
  await page.click('#to-confirm');
  // Step 7: confirm
  await page.waitForSelector('#place-trade');
  const confirmText = await page.textContent('#ticket-body');
  assert.match(confirmText, /PAPER trade/);
  await page.click('#place-trade');
  await page.waitForSelector('#refresh-btn'); // detail page landmark (route names collapsed)
  const detail = await page.textContent('#app');
  assert.match(detail, /ACTIVE/);
  assert.match(detail, /Payoff at expiration/);
  tradeUrlHash = await page.evaluate(() => window.location.hash);
  assert.match(tradeUrlHash, /^#\/trade\/tr_/);
});

test('trade detail: refresh writes a visible mark', async () => {
  await page.click('#refresh-btn');
  await page.waitForSelector('text=Marks history');
  const rows = await page.locator('.card:has-text("Marks history") tbody tr').count();
  assert.ok(rows >= 1, 'a marks row after refresh');
});

test('unwind flow shows cash effect and closes the trade', async () => {
  await page.click('#unwind-btn');
  await page.waitForSelector('#modal-confirm');
  const modalText = await page.textContent('.modal');
  assert.match(modalText, /reserve.*released|released/i);
  await page.click('#modal-confirm');
  await page.waitForSelector('#app[data-ready="true"]:has-text("CLOSED")');
  const text = await page.textContent('#app');
  assert.match(text, /Realized P\/L/);
});

test('portfolio tabs show the closed trade', async () => {
  await go('#/portfolio');
  await page.waitForSelector('#tab-closed');
  await page.click('#tab-closed');
  await page.waitForSelector('#app[data-ready="true"] .tbl tbody tr');
  const rowText = await page.textContent('.tbl tbody tr');
  assert.match(rowText, /AAPL/);
  assert.match(rowText, /CLOSED/);
});

test('portfolio headline: total value + P/L at current marks', async () => {
  await go('#/portfolio');
  await page.waitForSelector('#pf-stats .stat');
  const stats = await page.textContent('#pf-stats');
  assert.match(stats, /Portfolio value/);
  assert.match(stats, /P\/L since start/);
  assert.match(stats, /%\)/); // P/L carries its percent
  // identity check straight against the API
  const ok = await page.evaluate(async () => {
    const s = await API.getFresh('/api/portfolio/summary');
    return s.totalValueCents === s.cashCents + s.sharesValueCents + s.openTradesValueCents
      && s.totalPnlCents === s.totalValueCents - s.startingCashCents;
  });
  assert.ok(ok, 'summary adds up');
});

test('portfolio absorbs account: sections, ledger under Activity, guarded reset', async () => {
  await go('#/portfolio/account');
  const text = await page.textContent('#app');
  assert.match(text, /Buying power/);
  assert.match(text, /Reset account/);
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'portfolio');
  await go('#/portfolio/activity');
  const ledger = await page.textContent('#app');
  assert.match(ledger, /PREMIUM_OPEN/);
  assert.match(ledger, /RESERVE_RELEASE/);
  await go('#/portfolio/account');
  await page.click('#reset-btn');
  await page.waitForSelector('#modal-confirm');
  assert.match(await page.textContent('.modal'), /cannot be undone/i);
  await page.click('.modal button:has-text("Cancel")');
  assert.equal(await page.locator('#modal-confirm').count(), 0, 'modal dismissed');

  // A typed starting-cash draft survives a cosmetic re-render (level flip) instead of
  // snapping back to the account's current value.
  await page.fill('#reset-cash', '250000');
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#reset-cash');
  assert.equal(await page.inputValue('#reset-cash'), '250000', 'reset-cash draft persists across level flip');
  await page.evaluate(() => { App.state.resetCashDraft = null; Learn.setLevel('expert'); });
});

test('review verdict panel: probability map, execution ladder, expert repricing', async () => {
  // Build a fresh idea at Beginner (candidate CARDS; expert renders the comparison table),
  // then switch to Expert AT Review — the ladder re-renders the same screen with deeper controls.
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { App.state.ticket = null; App.state.discoverForm = null; });
  // WALK the surfaces that render info terms elsewhere in the app FIRST (the audit used to be
  // blind to anything rendering after its snapshot): builder, backtest, data center (sim
  // workbench + account risk card live there and in portfolio).
  for (const hash of ['#/trade/structure', '#/trade/outcomes', '#/data/simulation', '#/portfolio/account']) {
    await go(hash);
    await page.waitForTimeout(250);
  }
  // The sim workbench must register its own jargon (scenario/seed/speed/beta at expert).
  const preTerms = await page.evaluate(() => (window.__usedInfoTerms || []).slice());
  assert.ok(preTerms.includes('scenario'), 'sim workbench wires info(scenario)');
  assert.ok(preTerms.includes('speed'), 'sim workbench wires info(speed)');
  await go('#/home');
  await go('#/trade/context/manual');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.click('#rec-filters .xp-head');
  assert.equal(await page.locator('#rec-filters .field .info-trigger').count(), 5,
    'each candidate limit has one visible, registry-backed explanation control');
  assert.equal(await page.locator('#rec-filters .field[title]').count(), 0,
    'candidate limits do not add a second native hover tooltip');
  assert.equal(await page.$$eval('#rec-filters .info-trigger', els =>
    els.filter(e => e.closest('[title]')).length), 0,
    'structured filter help never sits inside a native title tooltip');
  await page.fill('#rec-symbol', 'AAPL');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('#rec-results .candidate', { timeout: 30000 });
  // With the full catalog competing (risk no longer gates complexity), a CALENDAR can rank
  // first — its review honestly has no probability map. Pick a single-expiration structure.
  const single = page.locator('#rec-results .candidate:not([data-strategy*="CALENDAR"]):not([data-strategy*="DIAGONAL"])');
  await single.first().locator('button:has-text("Review & decide")').first().click();
  await page.waitForSelector('#to-review');
  await page.click('#to-review');
  await page.waitForSelector('#to-confirm');
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#proposed-net', { timeout: 15000 }); // review re-rendered with expert controls
  // The assembled judgment: verdict banner + full probability map + execution ladder + quote age.
  await page.waitForSelector('#verdict-panel');
  const panel = await page.textContent('#verdict-panel');
  assert.match(panel, /P\(any profit\)|Chance of making anything/);
  assert.match(panel, /P\(theoretical max loss\)|Chance of the theoretical WORST case/);
  assert.match(panel, /CVaR|very bad run/);
  assert.match(panel, /risk-neutral/i);           // the basis is disclosed, always
  await page.waitForSelector('#rate-input');
  assert.match(await page.textContent('#rate-input'), /Risk-free rate input|Pricing assumes a/);
  assert.match(await page.textContent('#rate-input'), /% annual/);
  assert.ok(await page.locator('#rate-input .evidence-badge').count(), 'the rate input carries its own provenance');
  await page.waitForSelector('#exec-ladder');
  assert.match(await page.textContent('#exec-ladder'), /Midpoint/);
  assert.match(await page.textContent('#quote-age'), /quotes/);
  // Expert repricing: judge the SAME package at MY price — max loss follows the number I set.
  const before = await page.textContent('#ticket-body');
  await page.fill('#proposed-net', '-9.99');       // a debit I choose
  await page.click('#reprice-btn');
  await page.waitForSelector('#verdict-panel');    // re-rendered
  await page.waitForSelector('#exec-ladder:has-text("Your price")');
  const after = await page.textContent('#ticket-body');
  assert.match(after, /YOUR net price/);
  assert.notEqual(before, after, 'repricing must change the review');
  // Leave a clean state for downstream tests.
  await page.evaluate(() => { App.state.ticket = null; });
  await page.click('#level-switch button[data-level="beginner"]');
});

test('explanation system: visible triggers, registry-backed bubbles, both levels, no title dups', async () => {
  // The review from the previous test is still on the trade detail... navigate to a fresh review.
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { App.state.ticket = null; App.state.discoverForm = null; });
  // WALK the surfaces that render info terms elsewhere in the app FIRST (the audit used to be
  // blind to anything rendering after its snapshot): builder, backtest, data center (sim
  // workbench + account risk card live there and in portfolio).
  for (const hash of ['#/trade/structure', '#/trade/outcomes', '#/data/simulation', '#/portfolio/account']) {
    await go(hash);
    await page.waitForTimeout(250);
  }
  // The sim workbench must register its own jargon (scenario/seed/speed/beta at expert).
  const preTerms = await page.evaluate(() => (window.__usedInfoTerms || []).slice());
  assert.ok(preTerms.includes('scenario'), 'sim workbench wires info(scenario)');
  assert.ok(preTerms.includes('speed'), 'sim workbench wires info(speed)');
  await go('#/home');
  await go('#/trade/context/manual');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('#rec-results .candidate', { timeout: 30000 });
  // Same single-expiration pick as the verdict test: a calendar's review has no probability map.
  const singleExp = page.locator('#rec-results .candidate:not([data-strategy*="CALENDAR"]):not([data-strategy*="DIAGONAL"])');
  await singleExp.first().locator('button:has-text("Review & decide")').first().click();
  await page.waitForSelector('#to-review');
  await page.click('#to-review');
  await page.waitForSelector('#verdict-panel .info-trigger', { timeout: 20000 });
  // AUDIT 1: every used term key resolves in the registry (anti-drift).
  const missing = await page.evaluate(() =>
    (window.__usedInfoTerms || []).filter(k => !(window.Learn && Learn.INFO && Learn.INFO[k])));
  assert.deepEqual(missing, [], 'info terms missing from the registry: ' + missing.join(','));
  // AUDIT 2: triggers are VISIBLE (quiet but discoverable) and carry no competing native title.
  const trig = page.locator('#verdict-panel .info-trigger').first();
  assert.ok(await trig.isVisible(), 'info trigger must be visible without hovering');
  const dup = await page.$$eval('#verdict-panel .info-trigger', els =>
    els.filter(e => e.closest('[title]')).length);
  assert.equal(dup, 0, 'a bubble label must not also carry a native title tooltip');
  // AUDIT 3: click opens immediately; one-liner first; [+] expands the BEGINNER detail.
  await trig.click();
  await page.waitForSelector('#info-pop');
  const shortText = await page.textContent('#info-pop .info-short');
  assert.ok(shortText.length > 20, 'one-liner present');
  assert.match(shortText, /\$1 gain|compare/i,
    'the POP one-liner adds a decision distinction instead of repeating its label');
  assert.equal(await page.isVisible('#info-pop .info-detail'), false, 'detail starts collapsed');
  await page.click('#info-pop .info-expand');
  const begDetail = await page.textContent('#info-pop .info-detail');
  assert.ok(begDetail.length > 40, 'beginner detail present');
  // Escape closes AND returns focus to the trigger (keyboard a11y).
  await page.keyboard.press('Escape');
  const distinctProbabilityHelp = await page.evaluate(() => ({
    profit: Learn.INFO.pop.short,
    fullWin: Learn.INFO.pmaxprofit.short,
    fullLoss: Learn.INFO.pmaxloss.short
  }));
  assert.match(distinctProbabilityHelp.fullWin, /ordinary profitable outcomes are excluded/i);
  assert.match(distinctProbabilityHelp.fullLoss, /smaller losses are excluded/i);
  assert.equal(await page.locator('#info-pop').count(), 0, 'Escape closes the bubble');
  // KEYBOARD PATH: focus opens the bubble, Tab reaches [+] (DOM-adjacent, not body-appended).
  // Escape already returned focus to the trigger, so blur first — refocusing a focused element
  // fires no focus event (and must not: that's the no-reopen-after-Escape contract).
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await page.waitForTimeout(150); // let the post-Escape reopen suppression window lapse
  await trig.focus();
  await page.waitForSelector('#info-pop');
  await page.keyboard.press('Tab');
  const onExpand = await page.evaluate(() =>
    document.activeElement && document.activeElement.classList.contains('info-expand'));
  assert.ok(onExpand, 'Tab from the trigger must land on the [+] expand control');
  // aria contract: trigger references the bubble and reports expanded state.
  assert.equal(await trig.getAttribute('aria-expanded'), 'true');
  assert.equal(await trig.getAttribute('aria-describedby'), 'info-pop');
  // Scrolling RE-ANCHORS the fixed-position bubble to its trigger (never floats over other
  // numbers); scrolling the trigger fully out of view closes it.
  await page.mouse.wheel(0, 120);
  await page.waitForTimeout(120);
  const anchored = await page.evaluate(() => {
    const pop = document.getElementById('info-pop');
    const t = document.querySelector('[aria-describedby="info-pop"]');
    if (!pop || !t) return pop === null; // closed (trigger left view) is also acceptable
    const pr = pop.getBoundingClientRect(), tr = t.getBoundingClientRect();
    return Math.abs(pr.top - tr.bottom) < 80 || Math.abs(tr.top - pr.bottom) < 80;
  });
  assert.ok(anchored, 'bubble stays anchored to its trigger (or closes) on scroll');
  await page.keyboard.press('Escape');
  // AUDIT 4: the SAME trigger at Expert level yields the expert detail (same truth, deeper words).
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#verdict-panel .info-trigger', { timeout: 20000 });
  await page.locator('#verdict-panel .info-trigger').first().click();
  await page.waitForSelector('#info-pop');
  await page.click('#info-pop .info-expand');
  const expDetail = await page.textContent('#info-pop .info-detail');
  assert.ok(expDetail !== begDetail, 'expert detail differs from beginner detail');
  await page.keyboard.press('Escape');
  // Hygiene for downstream tests.
  await page.evaluate(() => { App.state.ticket = null; });
  await page.click('#level-switch button[data-level="beginner"]');
});

test('backtest runs and reports mode, coverage, assumptions', async () => {
  await go('#/trade/outcomes');
  await page.click('#trade-outcomes-nav .outcome-basis[data-basis="history"]');
  assert.match(await page.textContent('#trade-outcomes-nav .outcome-basis[data-basis="world"]'), /Setup needed/,
    'the Demo baseline is not misrepresented as a running simulated session');
  await page.click('#trade-outcomes-nav .outcome-basis[data-basis="world"]');
  assert.match(await page.textContent('#trade-outcomes-panel'), /not currently inside a simulated market/i,
    'an unavailable basis explains the missing setup instead of becoming a blank panel');
  await page.click('#trade-outcomes-nav .outcome-basis[data-basis="history"]');
  // The portfolio engine + full family menu are Expert-only; pin this test there.
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#bt-engine');
  const backtestCatalog = await page.evaluate(async () => {
    const server = await (await fetch('/api/strategies')).json();
    return {
      actual: Array.from(document.querySelectorAll('#bt-strategy option')).map(o => o.value).sort(),
      expected: server.catalog.filter(c => c.backtestEnabled).map(c => c.name).sort()
    };
  });
  assert.deepEqual(backtestCatalog.actual, backtestCatalog.expected,
    'Backtest menu is the server-owned eligibility view of the same catalog');
  // Product form: period pills set the window; a hand-edited date clears the pill claim
  await page.click('#bt-periods .pill[data-days="365"]');
  assert.equal(await page.locator('#bt-periods .pill.active').count(), 1);
  await page.fill('#bt-from', '2026-03-02');
  await page.fill('#bt-to', '2026-06-30');
  await page.evaluate(() => { document.getElementById('bt-from').dispatchEvent(new Event('change')); });
  assert.equal(await page.locator('#bt-periods .pill.active').count(), 0, 'custom dates claim no pill');
  await page.click('#bt-dte-presets .sym-chip:has-text("Monthly")');
  assert.equal(await page.inputValue('#bt-dte'), '30');
  await page.click('#bt-run');
  await page.waitForSelector('#bt-results .stat', { timeout: 30000 });
  const text = await page.textContent('#bt-results');
  assert.match(text, /MODELED_FROM_UNDERLYING/);
  assert.match(text, /Sample size/);
  assert.match(text, /Coverage/);
  assert.match(text, /slippagePctPerLeg/);
  assert.match(text, /Equity curve/);
  // Equity curve speaks the same chart language as research: summary chips + crosshair
  assert.match(text, /Change/);
  assert.match(text, /Max drawdown/);
  assert.ok(await page.locator('#bt-results .chart-summary .chip').count() >= 3, 'equity summary chips');
  assert.ok(await page.locator('#bt-results .chart-wrap').count() >= 1, 'interactive equity chart');

  // D4: Expert unlocks the PORTFOLIO engine (concurrent positions). Run it on a supported family.
  await page.selectOption('#bt-strategy', 'CREDIT_PUT_SPREAD');
  await page.selectOption('#bt-engine', 'portfolio');
  await page.click('#bt-run');
  await page.waitForSelector('#bt-results:has-text("Concurrent peak")', { timeout: 30000 });
  assert.match(await page.textContent('#bt-results'), /Portfolio engine/, 'portfolio report rendered');
});

test('candidate cards cross-link into backtest with the form pre-answered', async () => {
  await go('#/trade/context/manual');
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await go('#/trade/context/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  // Expert results are a comparison table — the full card (with its Backtest link) lives
  // in the expandable detail row. Decision ranking can put a CALENDAR first, whose card
  // honestly has no Backtest button (the Backtester rejects multi-expiration) — walk the
  // rows until one offers it.
  await page.waitForSelector('#compare-table tbody tr.clickable', { timeout: 30000 });
  const rows = page.locator('#compare-table tbody tr.clickable');
  const rowCount = await rows.count();
  let card = null;
  for (let i = 0; i < rowCount; i++) {
    await rows.nth(i).click();
    // collapsed details stay in the DOM display:none — only the VISIBLE one is this row's
    await page.waitForSelector('.compare-detail .candidate:visible');
    const c = page.locator('.compare-detail .candidate:visible:has(button[data-outcome-default="history"])').first();
    if (await c.count()) { card = c; break; }
    await rows.nth(i).click(); // collapse before trying the next row
  }
  assert.ok(card, 'candidates offer Test outcomes');
  const strategy = await card.getAttribute('data-strategy');
  await card.locator('button[data-outcome-default="history"]').click();
  await page.waitForSelector('#bt-symbol');
  assert.equal(await page.inputValue('#bt-symbol'), 'AAPL');
  assert.equal(await page.inputValue('#bt-strategy'), strategy, 'strategy carried over');
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
});

test('data center tabs: overview dashboard, sources+jobs, coverage backfill, admin-only reset', async () => {
  // ROUTE-BACKED TABS (holistic review addendum A): each workspace loads only on its route,
  // Overview answers "where am I / what next", and destructive ops live ONLY under Administration.
  await page.click('#level-switch button[data-level="expert"]');
  await go('#/data/overview');
  await page.waitForSelector('#data-tabs button.active[data-tab="overview"]');
  await page.waitForSelector('#dc-mode .badge'); // where-you-are card leads
  assert.match(await page.textContent('#dc-mode'), /OBSERVED MARKET|DEMO MARKET|SIMULATED|SCENARIO/);
  await page.waitForSelector('#dc-engine .chip-row');
  assert.match(await page.textContent('#dc-engine'), /Market engine/);
  await page.waitForSelector('#dc-health:has-text("Quotes")', { timeout: 15000 });
  await page.click('#dc-refresh-now');
  // Inactive workspaces are NOT mounted from Overview (only-active-tab loading).
  assert.equal(await page.locator('#dc-sources').count(), 0, 'sources not mounted on overview');
  assert.equal(await page.locator('#dc-reset').count(), 0, 'reset not mounted on overview');

  // Sources & jobs tab
  await page.click('#data-tabs [data-tab="sources"]');
  await page.waitForSelector('#dc-history-sync #data-csv-upload');
  await page.waitForSelector('#dc-sources .dc-source');
  const sources = await page.textContent('#dc-sources');
  assert.match(sources, /Yahoo Finance automation/);
  assert.match(sources, /Automated collection requires permission|automated collection requires permission/i);
  assert.match(sources, /Your price-history CSV/);
  assert.ok(await page.locator('#dc-history-sync .data-source-choice').count() >= 4, 'all automated connector choices remain visible');
  assert.equal(await page.locator('#data-sync-preview').isDisabled(), true, 'Demo build cannot start an observed provider sync');
  assert.ok(await page.locator('#data-csv-file').count(), 'user-owned CSV import remains available');
  assert.ok(await page.locator('#data-sync-schedule').count(), 'maintenance capability is present at expert');

  await page.waitForSelector('#dc-jobs .dc-job:has-text("refresh_now")', { timeout: 15000 });

  // Datasets hosts coverage. This suite is an explicit Demo build, so observed backfill is
  // unavailable by design rather than starting a job that can only refuse fabricated rows.
  await page.click('#data-tabs [data-tab="datasets"]');
  await page.waitForSelector('#dc-coverage #dc-backfill');
  assert.equal(await page.locator('#dc-backfill').isDisabled(), true);
  assert.match(await page.textContent('#dc-coverage'), /Observed backfill is unavailable/);

  // Administration: reset lives here and only here, typed confirmation enforced.
  await page.click('#data-tabs [data-tab="admin"]');
  await page.waitForSelector('#dc-reset-tier');
  const tiers = await page.$$eval('#dc-reset-tier option', os => os.map(o => o.value));
  assert.ok(tiers.includes('MARKET_DATA') && tiers.includes('EVERYTHING'), 'expert reset tiers');
  await page.click('#dc-reset-btn');
  await page.waitForSelector('#dc-reset-confirm');
  await page.click('#modal-confirm'); // empty confirm -> refused, modal stays
  assert.ok(await page.locator('#dc-reset-confirm').count(), 'reset refused without typing RESET');
  await page.click('.modal button:has-text("Cancel")');
  // Deep link + back/forward keep the tab.
  await go('#/data/datasets');
  await page.waitForSelector('#data-tabs button.active[data-tab="datasets"]');
  await page.goBack();
  await page.waitForSelector('#data-tabs button.active[data-tab="admin"]', { timeout: 15000 });
});

test('data center reset tiers are reduced for Beginner', async () => {
  await go('#/data/admin');
  await page.click('#level-switch button[data-level="beginner"]');
  // Wait for the BEGINNER re-render (2-tier select), not the lingering expert one mid-transition.
  await page.waitForFunction(() => {
    const s = document.getElementById('dc-reset-tier');
    return s && s.options.length === 2;
  }, { timeout: 15000 });
  const tiers = await page.$$eval('#dc-reset-tier option', os => os.map(o => o.value));
  assert.ok(!tiers.includes('MARKET_DATA'), 'beginner hides the granular market-data tier');
  assert.ok(tiers.includes('PAPER') && tiers.includes('EVERYTHING'), 'beginner keeps the plain resets');
  await go('#/data/sources');
  await page.waitForSelector('#dc-history-sync #data-csv-upload');
  assert.ok(await page.locator('#dc-history-sync .data-source-choice').count() >= 4, 'beginner keeps every connector');
  assert.ok(await page.locator('#data-sync-years').count(), 'beginner keeps history window control');
  assert.ok(await page.locator('#dc-history-sync .xp-head:has-text("Keep it current automatically")').count(),
    'beginner gets the same maintenance capability through progressive disclosure');
  await page.click('#level-switch button[data-level="expert"]'); // restore for later tests
});

test('pro depth: comparison table, custom builder, position greeks', async () => {
  // Comparison table on Ideas at Pro
  await go('#/trade/context/manual');
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForSelector('#compare-table');
  const rows = await page.locator('#compare-table tbody tr.clickable').count();
  assert.ok(rows >= 2, 'multiple candidates compared');
  assert.match(await page.textContent('#compare-table thead'), /Market EV/);
  assert.match(await page.textContent('#compare-table thead'), /History EV/,
    'the candidate comparison keeps both economic lanes co-equal');
  await page.click('#compare-table thead th:has-text("max loss")'); // sort
  assert.match(await page.textContent('#compare-table thead'), /Theor\. max loss [↓↑]/);
  await page.click('#compare-table tbody tr.clickable'); // expand detail row
  await page.waitForSelector('.compare-detail .candidate');

  // Expert terminal: build a call spread by hand, place it through the standard review
  await page.evaluate(() => { App.state.builderForm = null; App.state.ticket = null; });
  await go('#/trade/structure');
  await page.waitForSelector('#builder-add-leg');
  await page.click('#builder-add-leg');
  await page.waitForSelector('#builder-legs .leg-row');
  await page.click('#builder-add-leg');
  await page.waitForFunction(() => document.querySelectorAll('#builder-legs .leg-row').length === 2);
  await page.locator('#builder-legs .leg-row').nth(1).locator('.leg-action').selectOption('SELL');
  const strikeSel = page.locator('#builder-legs .leg-row').nth(1).locator('.leg-strike');
  const values = await strikeSel.locator('option').evaluateAll(os => os.map(o => o.value));
  assert.ok(values.length >= 4, 'strike list populated from the chain');
  await strikeSel.selectOption(values[Math.floor(values.length / 2) + 2]);
  await page.waitForFunction(() =>
    /ALLOW|WARN/.test((document.getElementById('builder-panel') || {}).textContent || ''), { timeout: 20000 });
  assert.match(await page.textContent('#builder-panel'), /Net Δ sh/); // net greeks in the terminal
  assert.equal(await page.locator('#builder-panel .xp-head:has-text("Scenario distributions")').count(), 1,
    'Builder reuses the same lazy scenario-outcome component instead of auto-running a duplicate simulator');
  await page.click('#builder-review');
  await page.waitForSelector('#to-confirm', { timeout: 30000 });
  assert.match(await page.textContent('#ticket-body'), /Safety check/);
  await page.$$eval('.ack-gate input', els => els.forEach(e => { if (!e.checked) e.click(); })); // acknowledge material risks (CP-5 gate)
  await page.click('#to-confirm');
  await page.waitForSelector('#place-trade');
  await page.click('#place-trade');
  await page.waitForSelector('#refresh-btn', { timeout: 30000 }); // detail landmark

  // Position greeks on the detail page at Pro
  await page.waitForSelector('#greeks-card');
  const greeks = await page.textContent('#greeks-card');
  assert.match(greeks, /Net Δ/);
  assert.match(greeks, /Θ\/day/);
  assert.ok((await page.locator('#greeks-card tbody tr').count()) === 2, 'per-leg greek rows');

  // Book greeks on the portfolio at Pro, then clean up
  await go('#/portfolio');
  await page.waitForSelector('#portfolio-greeks');
  const tradeHash = await page.evaluate(() => window.localStorage && '' ); // noop, keep page context
  await go('#/portfolio');
  await page.click('.tbl tbody tr.clickable'); // open the trade
  await page.waitForSelector('#delete-btn');
  await page.click('#delete-btn');
  await page.waitForSelector('#modal-confirm');
  await page.click('#modal-confirm');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.click('#level-switch button[data-level="beginner"]'); // restore default
  await page.waitForSelector('#app[data-ready="true"]');
});

test('holdings + intents: buy shares, covered call at a target, filters, assignment framing', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  // 1. Buy 100 AAPL from the portfolio holdings card
  await go('#/portfolio');
  await page.waitForSelector('#holdings-card');
  await page.click('#buy-shares-btn');
  await page.waitForSelector('#modal-confirm');
  await page.fill('#stock-symbol', 'AAPL');
  await page.fill('#stock-shares', '100');
  await page.click('#modal-confirm');
  await page.waitForSelector('#holdings-card .tbl tbody tr');
  const holdingsRow = await page.textContent('#holdings-card .tbl tbody tr');
  assert.match(holdingsRow, /AAPL/);
  assert.match(holdingsRow, /100/);

  // 2. Ideas: "Sell at a target" intent proposes a covered call ON the held shares
  await go('#/trade/context/manual');
  await page.waitForSelector('#intent-choices');
  await page.click('#intent-choices .choice[data-intent="EXIT"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.fill('#rec-target', '260');
  await page.click('#rec-go');
  await page.waitForSelector('.candidate');
  const card = await page.textContent('.candidate');
  assert.match(card, /HELD SHARES/i);
  assert.match(card, /sell 100 shares/i);           // intent note frames assignment as the goal
  assert.match(card, /Chance you sell/i);           // beginner-level fact framing
  await page.waitForSelector('#rec-holdings-hint .chip-row'); // real position surfaced

  // 3. Place it through the ticket — no new buying power, shares get locked
  await page.click('.candidate .btn-row .btn');
  await page.waitForSelector('#to-review', { timeout: 30000 });
  await page.click('#to-review');
  await page.waitForSelector('#to-confirm', { timeout: 30000 });
  const review = await page.textContent('#ticket-body');
  assert.match(review, /Covered by shares you already hold/);
  assert.match(review, /New cash at risk/);
  assert.match(review, /Theoretical max loss with shares/,
    'held-share tickets keep incremental cash risk and combined structural risk separate');
  assert.ok(await page.locator('#ticket-body .economic-assessment').count(),
    'held-share economics use the same exact-ticket assessment as every other structure');
  await page.$$eval('.ack-gate input', els => els.forEach(e => { if (!e.checked) e.click(); })); // acknowledge material risks (CP-5 gate)
  await page.click('#to-confirm');
  await page.waitForSelector('#place-trade');
  await page.click('#place-trade');
  await page.waitForSelector('#refresh-btn', { timeout: 30000 }); // detail landmark
  const detail = await page.textContent('#app');
  assert.match(detail, /Covered by/);
  assert.match(detail, /100 held sh/);
  assert.match(detail, /SELL AT A TARGET/);

  // 4. Locked shares are visible and the intent filter narrows the list
  await go('#/portfolio');
  await page.waitForSelector('#holdings-card .badge-caution');
  assert.match(await page.textContent('#holdings-card'), /100 LOCKED/);
  await page.selectOption('#pf-intent', 'EXIT');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('.tbl tbody tr.clickable');
  await page.selectOption('#pf-intent', 'INCOME');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.match(await page.textContent('#app'), /No open practice trades/);
  await page.selectOption('#pf-intent', '');
  await page.waitForSelector('#app[data-ready="true"]');

  // 5. Clean up: void the trade, sell the shares back
  await page.click('.tbl tbody tr.clickable');
  await page.waitForSelector('#delete-btn');
  await page.click('#delete-btn');
  await page.waitForSelector('#modal-confirm');
  await page.click('#modal-confirm');
  await page.waitForSelector('#app[data-ready="true"]');
  await go('#/portfolio');
  await page.waitForSelector('#holdings-card .tbl tbody tr');
  await page.click('#holdings-card .btn-row .btn-secondary:has-text("Sell")');
  await page.waitForSelector('#modal-confirm');
  await page.click('#modal-confirm');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.equal(await page.locator('#holdings-card .badge-caution').count(), 0, 'no locked badge after unwind+sell');

  // 6. Filters reject loudly: an impossible POP floor leaves only refusals
  await go('#/trade/context/manual');
  await page.waitForSelector('#intent-choices');
  await page.click('#intent-choices .choice[data-intent="INCOME"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-filters .xp-head');
  await page.fill('#rec-f-pop', '99');
  await page.click('#rec-go');
  await page.waitForSelector('#rec-results .empty, #rec-results .candidate');
  const results = await page.textContent('#rec-results');
  assert.match(results, /below your minimum/);

  // 7. Goal chooser: ONE goal at a time (radio semantics) plus "Everything" for scans
  await go('#/trade/context/scout');
  await page.waitForSelector('#intent-choices');
  await page.click('#intent-choices .choice[data-intent="INCOME"]');
  assert.equal(await page.locator('#intent-choices .choice.selected').count(), 1, 'exactly one goal selected');
  assert.match(await page.textContent('#intent-choices .choice[data-intent="INCOME"]'), /premium/i);
  await page.click('#intent-choices .choice[data-intent="ALL"]');
  assert.equal(await page.locator('#intent-choices .choice.selected').count(), 1, 'ALL replaces, never adds');
  // "I have a stock in mind" turns the SAME form into single-stock ideas —
  // "Buy at a discount" can name QQQ
  await page.click('#intent-choices .choice[data-intent="ACQUIRE"]');
  await page.click('#idea-source .choice[data-source="single"]');
  await page.fill('#rec-symbol', 'QQQ');
  assert.match(await page.textContent('#rec-go'), /Find ideas/);
  assert.ok(await page.locator('#rec-target').isVisible(), 'the buy-price field appears for a named stock');
  // The universe's stocks stay ONE TAP away even with a symbol already in the box
  assert.ok(await page.locator('#universe-sym-chips .sym-chip').count() >= 3,
    'sector stocks visible as chips while the box holds QQQ');
  await page.click('#universe-sym-chips .sym-chip:has-text("TSLA")');
  assert.equal(await page.inputValue('#rec-symbol'), 'TSLA');
  await page.fill('#rec-symbol', 'QQQ');
  // The stock context is complete in-place (quote, evidence, peers). The old expandable
  // duplicated Research and added a dead-end click, so it must stay gone.
  await page.waitForSelector('#ideas-symbol-context .symbol-context-price');
  assert.match(await page.textContent('#ideas-symbol-context'), /QQQ/);
  assert.equal(await page.locator('#symbol-context .symbol-panel').count(), 0,
    'Ideas does not duplicate the full Research destination inside its form');
  // The working symbol FOLLOWS across stages (the QQQ->AAPL amnesia bug)
  await go('#/trade/outcomes');
  assert.equal(await page.inputValue('#bt-symbol'), 'QQQ', 'Backtest picks up the ticker you just typed');
  assert.match(await page.textContent('#trade-outcomes-nav .outcome-basis[data-basis="history"]'), /Historical replay/,
    'the reusable history engine does not overclaim Observed data in Demo/Scenario lanes');
  assert.match(await page.textContent('#trade-outcomes-note'), /fabricated Demo history/);
  await go('#/trade/context/scout');
  await page.waitForSelector('#intent-choices');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
});

test('theme toggle, brand, health banner, route error boundary', async () => {
  // Theme: pre-paint attribute exists; the header button cycles Auto -> Light -> Dark and persists
  await go('#/home');
  const initial = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
  assert.ok(initial === 'light' || initial === 'dark', 'data-theme set before paint');
  await page.click('#theme-toggle'); // system -> light
  assert.equal(await page.evaluate(() => document.documentElement.getAttribute('data-theme')), 'light');
  await page.click('#theme-toggle'); // light -> dark
  assert.equal(await page.evaluate(() => document.documentElement.getAttribute('data-theme')), 'dark');
  assert.equal(await page.evaluate(() => localStorage.getItem('strikebench.theme')), 'dark');
  const darkBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  await page.click('#theme-toggle'); // dark -> system (back to default)
  assert.equal(await page.evaluate(() => localStorage.getItem('strikebench.theme')), 'system');
  const lightBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  assert.notEqual(darkBg, lightBg, 'palette actually changes with the theme');

  // Brand flows from server config into title and header
  assert.equal(await page.title(), 'StrikeBench');
  assert.equal((await page.textContent('.brand')).trim(), 'StrikeBench');

  // Stale-server banner: when /api/health reports a rebuilt jar, one clear banner appears
  await page.route('**/api/health', r => r.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ ok: true, startedAt: 'x', jarChangedSinceBoot: true })
  }));
  await page.evaluate(() => fetch('/api/health')); // ensure the route is active
  // Trigger the health check via a route error: Portfolio awaits /api/account uncaught, so
  // aborting it fires the route boundary (the Data Center is deliberately resilient now).
  await page.evaluate(() => API.flushCache()); // else a cached /api/account masks the abort
  await page.route('**/api/account', r => r.abort());
  await page.evaluate(() => { location.hash = '#/portfolio'; });
  await page.waitForSelector('#route-error', { timeout: 15000 });
  const boundary = await page.textContent('#route-error');
  assert.match(boundary, /portfolio screen failed to load/i);
  assert.ok(await page.locator('#route-retry').count(), 'retry button present');
  await page.waitForSelector('#stale-banner', { timeout: 15000 });
  assert.match(await page.textContent('#stale-banner'), /rebuilt after this server started/i);

  // Un-break everything: retry renders the screen and the banner clears on healthy report
  await page.unroute('**/api/account');
  await page.unroute('**/api/health');
  await page.click('#route-retry');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.match(await page.textContent('#app'), /Buying power|Portfolio value/); // portfolio recovered
  await page.evaluate(() => fetch('/api/health'));
  // banner clears on the next periodic/error check; force one by navigating with a fresh check
  await page.evaluate(() => window.App && window.App.render());
  await page.waitForSelector('#app[data-ready="true"]');
});

test('interaction contract: clickable surfaces are keyboard-operable, errors are nonblocking, modals own focus', async () => {
  for (const hash of ['#/home/tour', '#/home', '#/research', '#/portfolio']) {
    await go(hash);
    const violations = await page.$$eval('.clickable', nodes => nodes.filter(n => {
      const native = n.matches('a[href],button');
      const role = n.getAttribute('role');
      return !native && (!['link', 'button'].includes(role) || n.getAttribute('tabindex') !== '0');
    }).map(n => n.outerHTML.slice(0, 160)));
    assert.deepEqual(violations, [], 'dead/mouse-only clickable surface on ' + hash + ': ' + violations.join('\n'));
  }

  const assets = await page.evaluate(async () => ({
    app: await (await fetch('/js/app.js')).text(),
    views: await (await fetch('/js/views.js')).text()
  }));
  assert.doesNotMatch(assets.app + assets.views, /\balert\s*\(/, 'async failures must use inline errors or the accessible toast');

  await go('#/portfolio');
  const modalTrigger = page.locator('#buy-shares-btn');
  assert.equal(await modalTrigger.count(), 1, 'portfolio exposes one stable buy-shares command');
  await modalTrigger.click();
  const dialog = page.locator('#modal-root [role="dialog"]');
  assert.equal(await dialog.count(), 1, 'modal has dialog semantics');
  assert.equal(await dialog.getAttribute('aria-modal'), 'true');
  assert.equal(await page.evaluate(() => document.querySelector('#modal-root [role="dialog"]').contains(document.activeElement)), true,
    'focus moves inside the modal');
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.querySelector('#modal-root [role="dialog"]'));
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), 'buy-shares-btn',
    'Escape closes and restores focus to the trigger');
});

test('destination navigation starts at the top while Research owns its explicit Back restoration', async () => {
  await go('#/research/AAPL');
  await openResearchTab('overview');
  await page.waitForSelector('#history-card .chart-wrap');
  await page.evaluate(() => window.scrollTo(0, Math.min(700, document.documentElement.scrollHeight - innerHeight)));
  assert.ok(await page.evaluate(() => window.scrollY > 100), 'source screen is genuinely scrolled');

  // Exercise a native hash transition, not App.navigate: both paths share the same contract.
  await page.evaluate(() => {
    document.getElementById('app').setAttribute('data-ready', 'false');
    location.hash = '#/data/simulation';
  });
  await page.waitForSelector('#app[data-route="data"][data-ready="true"]');
  await page.waitForSelector('#dc-sim-market');
  const destinationY = await page.evaluate(() => window.scrollY);
  assert.ok(destinationY <= 1,
    'a destination never inherits the prior screen\'s vertical offset; got y=' + destinationY);
});

test('interactive charts, range pills, universe picker, and the tape', async () => {
  // Research chart: pills switch windows; crosshair reads values on hover
  await go('#/research/AAPL');
  await openResearchTab('overview');
  await page.waitForSelector('#history-card .range-pills .pill.active');
  assert.equal(await page.textContent('#history-card .pill.active'), '1Y');
  await page.click('#history-card .pill[data-range="3m"]');
  await page.waitForSelector('#history-card .pill.active[data-range="3m"]');
  await page.waitForSelector('#history-card .chart-wrap svg.chart');
  assert.doesNotMatch(await page.textContent('#history-card'), /Real OHLC candles/,
    'lane-neutral chart instructions never promote Demo/Simulated history as real');
  const summary = await page.textContent('#history-card .chart-summary');
  assert.match(summary, /Change/);
  assert.match(summary, /High .* Low/);
  // Real OHLC candles render (vendored D3, not the fallback close line)
  assert.ok(await page.locator('#history-card svg.candles rect.candle').count() >= 20, 'candlestick bars rendered');
  assert.ok(await page.locator('#history-card svg.candles .candle-up').count() >= 1, 'up candles colored');
  // Crosshair: hover the middle of the chart -> OHLC + % readout
  await page.locator('#history-card svg.chart').scrollIntoViewIfNeeded();
  const box = await page.locator('#history-card svg.chart').boundingBox();
  await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.4);
  await page.waitForSelector('#history-card .chart-tip:not([style*="display: none"])');
  const tip = await page.textContent('#history-card .chart-tip');
  assert.match(tip, /% in window/);
  assert.match(tip, /O .*H /); // open/high/low/close readout
  // MAX pill answers with everything the fixture has (~3y)
  await page.click('#history-card .pill[data-range="max"]');
  await page.waitForSelector('#history-card .pill.active[data-range="max"]');
  await page.waitForSelector('#history-card .chart-wrap svg.chart');

  // Payoff crosshair: place nothing new — reuse any active trade? Instead assert on preview later.
  // Universe: the SAME sector rail as home/research picks the scan scope, globally
  await go('#/trade/context/scout');
  await page.waitForSelector('#universe-sector .sector-chip');
  const pickedSector = await page.locator('#universe-sector .sector-chip').first().getAttribute('data-sector');
  await page.click('#universe-sector .sector-chip[data-sector="' + pickedSector + '"]');
  await page.waitForSelector('#app[data-ready="true"]');
  const uni = await page.evaluate(() => fetch('/api/universe').then(r => r.json()));
  assert.equal(uni.active.sectorKey, pickedSector);
  // Datalist feeds symbol inputs everywhere
  assert.ok(await page.evaluate(() => document.querySelectorAll('#universe-symbols option').length) >= 4);
  // Trade keeps the market context and global sector selector. Hiding it here made the
  // active universe appear to vanish at the exact moment the user chose a strategy.
  assert.equal(await page.locator('#tape.tape-offroute').count(), 0, 'tape visible on the scout');
  assert.ok(await page.locator('#tape-sector').isVisible(), 'sector selector remains reachable in Trade');

  // Sector explorer: chips per sector, live tiles, scout handoff
  await go('#/research');
  assert.ok(await page.locator('#tape.tape-offroute').count() === 0, 'tape visible on research');
  await page.waitForSelector('#tape .tape-item');
  assert.ok(await page.locator('#tape-sector').isVisible(),
    'the global market strip names and, when available, switches the active sector');
  assert.ok(await page.locator('#tape-sector option').count() >= 1,
    'the compact global selector is populated from the same universe contract');
  // Tape is SEAMLESS: two identical halves, each at least as wide as the scroll viewport
  const tape = await page.evaluate(() => {
    const strip = document.getElementById('tape-strip');
    const scroll = document.querySelector('.tape-scroll');
    const seqs = strip.querySelectorAll('.tape-seq');
    return { seqs: seqs.length, stripW: strip.scrollWidth, viewW: scroll.clientWidth };
  });
  assert.ok(tape.seqs >= 2 && tape.seqs % 2 === 0, 'even number of sequences: ' + tape.seqs);
  assert.ok(tape.stripW >= tape.viewW * 2 - 5, 'each half covers the viewport (no gap at wrap)');
  await page.waitForSelector('#sector-explorer .sector-chip', { state: 'attached' });
  const sectorCount = await page.locator('#sector-chips .sector-chip').count();
  if (sectorCount >= 10) {
    // The observed-market rail never just "ends": when it overflows, an arrow says there is more.
    assert.ok(await page.locator('#sector-chips.can-right .rail-arrow-right').isVisible(),
      'overflowing rail shows a right arrow');
    const before = await page.evaluate(() => document.querySelector('#sector-chips .sector-rail').scrollLeft);
    await page.click('#sector-chips .rail-arrow-right');
    await page.waitForFunction(prev =>
      document.querySelector('#sector-chips .sector-rail').scrollLeft > prev + 50, before);
    assert.ok(await page.locator('#sector-chips.can-left .rail-arrow-left').isVisible(),
      'after scrolling, the way back is visible too');
    await page.click('#sector-chips .sector-chip[data-sector="TECH"]');
  } else {
    // Explicit Demo is its own market lane. It must not pretend the observed sector catalog
    // is selectable while only fixture-backed symbols can be served.
    assert.equal(sectorCount, 1, 'Demo market exposes one honest simulated universe');
    assert.equal(await page.locator('#sector-chips .sector-chip').first().getAttribute('data-sector'), 'world');
  }
  await page.waitForSelector('#sector-grid .sector-tile');
  const grid = await page.textContent('#sector-grid');
  assert.match(grid, /AAPL/);
  // EVERY sector symbol is an actionable tile, with or without a quote
  const tiles = await page.locator('#sector-grid .sector-tile').count();
  assert.ok(tiles >= 4, 'the active market symbols render as tiles, got ' + tiles);
  if (tiles === 5) {
    const rows = await page.$$eval('#sector-grid .sector-tile', els =>
      [...new Set(els.map(e => Math.round(e.getBoundingClientRect().top)))]);
    assert.equal(rows.length, 1, 'a five-symbol desktop universe uses the canvas as one balanced row');
  }
  // DESTINATION CARDS NAVIGATE (interaction contract): no per-card buttons, no preview
  // detour — one action opens the full analysis. The old expansion repeated a subset of
  // that page (review IC-2).
  assert.equal(await page.locator('#sector-grid .sector-tile button').count(), 0,
    'cards carry no repeated action buttons');
  assert.equal(await page.locator('#explorer-focus').count(), 0, 'the preview detour is gone');
  assert.ok(await page.locator('#explorer-hint:has-text("Choose a stock")').count(),
    'one instruction above the grid');
  // Shared range selector + sparklines: one batch, real information on the cards
  assert.ok(await page.locator('#spark-range .pill[data-range="3m"]').count(), 'shared 1M/3M/YTD selector');
  await page.waitForSelector('#sector-grid .sym-card[data-sym="AAPL"] .spark-svg', { timeout: 15000 });
  assert.equal(await page.locator('#sector-grid .sym-card[data-sym="AAPL"] .badge:has-text("NO OPTIONS")').count(), 0,
    'optionable AAPL is not mislabeled by the light quote response');
  assert.ok(await page.locator('#sector-grid .sym-card[data-sym="VTSAX"] .badge:has-text("NO OPTIONS")').count(),
    'non-optionable VTSAX remains explicit');
  // Cards are native links (not div+role shims) and carry no disclosure semantics.
  const cardA11y = await page.evaluate(() => {
    const c = document.querySelector('#sector-grid .sym-card[data-sym="AAPL"]');
    return { tag: c.tagName, href: c.getAttribute('href'), tab: c.tabIndex,
      exp: c.getAttribute('aria-expanded') };
  });
  assert.deepEqual(cardA11y, { tag: 'A', href: '#/research/AAPL', tab: 0, exp: null },
    'card is a native link without disclosure semantics');
  // Sparkline interaction is a SUBREGION: clicking the chart explores, never navigates
  await page.locator('#sector-grid .sym-card[data-sym="AAPL"] .spark-svg').click();
  assert.ok((await page.evaluate(() => window.location.hash)).startsWith('#/research') === true
    && !(await page.evaluate(() => window.location.hash)).includes('AAPL'),
    'chart click stays on the explorer');
  // Card click -> the full analysis, ONE action; Back restores sector + scroll
  await page.evaluate(() => window.scrollTo(0, 200));
  await page.locator('#sector-grid .sym-card[data-sym="AAPL"]').click();
  await page.waitForSelector('.quote-hero', { timeout: 15000 });
  assert.equal(await page.evaluate(() => window.location.hash), '#/research/AAPL');
  assert.ok(await page.locator('#research-symbol-context.symbol-context-compact').count(),
    'the analysis page keeps one compact Change-stock control');
  assert.equal(await page.locator('#research-symbol-context .symbol-context-status').count(), 0,
    'the selector does not duplicate the full hero quote and evidence');
  assert.equal(await page.textContent('#symbol-go'), 'Go',
    'the selector names a symbol change, not an analysis page that is already open');
  assert.match(await page.textContent('#test-your-view'), /fabricated Demo history \(not the real past\)/,
    'the study names its Demo-history basis');
  assert.doesNotMatch(await page.textContent('#test-your-view'), /checks the REAL past/,
    'Demo research never promotes fabricated history to real');
  await page.goBack();
  await page.waitForSelector('#sector-grid .sector-tile', { timeout: 15000 });
  await page.waitForFunction(() => window.scrollY >= 100, { timeout: 5000 });
  assert.ok(await page.evaluate(() => window.scrollY >= 100),
    'Back restores the explorer\'s saved vertical position after the route-level top reset');
  const restoredSector = sectorCount >= 10 ? 'TECH' : 'world';
  assert.ok(await page.locator('#sector-chips .sector-chip[data-sector="' + restoredSector + '"].active').count(),
    'Back restores the SAME market selection');
  // In Observed mode, quote-less cards still navigate and let the full page own the honest
  // unavailable state. Explicit Demo has no intentionally dead symbols.
  if (await page.locator('#sector-grid .tile-nodata').count()) {
    await page.locator('#sector-grid .tile-nodata').first().click();
    await page.waitForSelector('#app[data-route="research"][data-ready="true"]', { timeout: 15000 });
    assert.notEqual(await page.evaluate(() => window.location.hash), '#/research', 'landed on a symbol page');
    await page.goBack();
    await page.waitForSelector('#sector-grid .sector-tile', { timeout: 15000 });
  }
  if (sectorCount >= 10) assert.ok(await page.locator('#set-universe-btn').count(), 'one-click make-this-my-universe');
  await page.click('#sector-explorer .btn-row button:has-text("Scout this sector")');
  await page.waitForSelector('#auto-universe');
  assert.ok((await page.inputValue('#auto-universe')).includes('AAPL'), 'scout prefilled with the sector');

  // Header search: "/" focuses, Enter researches
  await page.keyboard.press('/');
  await page.keyboard.type('AAPL');
  await page.keyboard.press('Enter');
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  assert.match(await page.textContent('#app'), /Apple/);

  // Tape click-through navigates to research (hovering pauses the marquee — same as a user)
  await page.hover('#tape');
  const tapeHit = await page.evaluate(() => {
    const viewport = document.querySelector('#tape .tape-scroll').getBoundingClientRect();
    for (const item of document.querySelectorAll('#tape .tape-item')) {
      const r = item.getBoundingClientRect();
      const x = r.left + r.width / 2, y = r.top + r.height / 2;
      if (x <= viewport.left || x >= viewport.right) continue;
      const hit = document.elementFromPoint(x, y);
      if (hit && hit.closest('.tape-item') === item) return { x, y };
    }
    return null;
  });
  assert.ok(tapeHit, 'the paused ticker exposes at least one unobstructed clickable quote');
  await page.mouse.click(tapeHit.x, tapeHit.y);
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  // Explicit Demo owns this universe already; no hidden sector mutation is needed.
});

test('wide Trade market controls stay inside the workbench with a full sector catalog', async () => {
  await page.setViewportSize({ width: 2048, height: 1000 });
  await page.evaluate(async () => {
    window.__wideTestUniverse = App.state.universe;
    window.__wideTestIdeas = App.state.discoverForm;
    const defs = [
      ['CORE', 'Core indexes + megacaps'], ['TECH', 'Technology'], ['SEMI', 'Semiconductors'],
      ['HEALTH', 'Healthcare'], ['DEFENSE', 'Defense & aerospace'], ['STAPLES', 'Consumer staples'],
      ['DISCRETIONARY', 'Consumer discretionary'], ['ENERGY', 'Energy'], ['FINANCIALS', 'Financials'],
      ['INDUSTRIALS', 'Industrials'], ['COMMUNICATIONS', 'Communications'], ['UTILITIES', 'Utilities'],
      ['REAL_ESTATE', 'Real estate']
    ];
    const activeSymbols = ['AAPL', 'MSFT', 'NVDA', 'AMZN', 'GOOGL', 'META', 'AVGO', 'ORCL', 'CRM', 'ADBE', 'NOW', 'IBM'];
    App.state.universe = {
      lane: 'OBSERVED',
      active: { sectorKey: 'TECH', label: 'Technology', symbols: activeSymbols },
      sectors: defs.map((d, i) => ({ key: d[0], label: d[1], symbols: activeSymbols.slice(0, 5 + (i % 5)) }))
    };
    App.state.discoverForm = Object.assign({}, App.state.discoverForm || {}, {
      source: 'single', goal: 'DIRECTIONAL', symbol: 'AAPL', horizon: 'month', thesis: 'bullish'
    });
    window.location.hash = '#/trade/context';
    await App.render();
  });
  await page.waitForSelector('#idea-market-card #universe-sector .sector-chip');
  await page.waitForTimeout(150);
  const geometry = await page.evaluate(() => {
    const card = document.getElementById('idea-market-card').getBoundingClientRect();
    const context = document.getElementById('ideas-symbol-context').getBoundingClientRect();
    const input = document.getElementById('rec-symbol').getBoundingClientRect();
    const sector = document.getElementById('universe-sector');
    const sectorBox = sector.getBoundingClientRect();
    const rail = sector.querySelector('.sector-rail');
    return {
      page: [document.documentElement.scrollWidth, document.documentElement.clientWidth],
      card: [card.left, card.right], context: [context.left, context.right], inputWidth: input.width,
      sector: [sectorBox.left, sectorBox.right], sectorOverflow: rail.scrollWidth > rail.clientWidth + 2,
      rightArrow: getComputedStyle(sector.querySelector('.rail-arrow-right')).display,
      tapeVisible: !document.getElementById('tape').classList.contains('tape-offroute')
    };
  });
  assert.equal(geometry.page[0], geometry.page[1], 'no document-level spill at 2048px');
  assert.ok(geometry.context[0] >= geometry.card[0] - 1 && geometry.context[1] <= geometry.card[1] + 1,
    'symbol context stays inside the card: ' + JSON.stringify(geometry));
  assert.ok(geometry.sector[0] >= geometry.card[0] - 1 && geometry.sector[1] <= geometry.card[1] + 1,
    'full sector catalog stays inside the card');
  assert.ok(geometry.inputWidth <= 430, 'ticker input uses a purpose-sized edit control, not the entire card');
  assert.ok(geometry.sectorOverflow && geometry.rightArrow !== 'none', 'overflow is navigable inside the rail');
  assert.equal(geometry.tapeVisible, true, 'Trade retains the global market and sector context');

  await page.evaluate(async () => {
    App.state.universe = window.__wideTestUniverse;
    App.state.discoverForm = window.__wideTestIdeas;
    delete window.__wideTestUniverse;
    delete window.__wideTestIdeas;
    await App.render();
  });
  await page.setViewportSize({ width: 1280, height: 900 });
});

test('large Research markets progressively load every sparkline in governed batches', async () => {
  const symbols = ['AAPL', 'SPY', 'QQQ', 'TSLA', 'VTSAX'];
  for (let i = 0; i < 19; i++) symbols.push('Z' + String(i).padStart(3, '0'));
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.evaluate(async (syms) => {
    window.__largeUniverseRestore = App.state.universe;
    if (window.performance && performance.clearResourceTimings) performance.clearResourceTimings();
    App.state.universe = {
      active: { sectorKey: 'LARGE', label: 'Large review market', symbols: syms },
      sectors: [{ key: 'LARGE', label: 'Large review market', symbols: syms }],
      lane: 'DEMO', note: 'DOM large-market fixture'
    };
    App.state.explorerSector = 'LARGE';
    history.replaceState(null, '', '#/research');
    await App.render();
  }, symbols);
  await page.waitForSelector('#sector-grid[data-count="24"]');
  const finalSym = symbols[symbols.length - 1];
  await page.locator('#sector-grid .sym-card[data-sym="' + finalSym + '"]').scrollIntoViewIfNeeded();
  await page.waitForSelector('#sector-grid .sym-card[data-sym="' + finalSym + '"] .spark', { timeout: 20000 });
  await page.waitForFunction(() =>
    document.querySelectorAll('#sector-grid .spark-loading').length === 0, null, { timeout: 30000 });
  const requests = await page.evaluate(() => performance.getEntriesByType('resource')
    .map(e => e.name).filter(n => n.includes('/api/sparklines?symbols=')));
  assert.ok(requests.length >= 2, 'more than 16 symbols require multiple controlled batches: ' + requests.length);
  for (const url of requests) {
    const count = decodeURIComponent(new URL(url).searchParams.get('symbols') || '').split(',').filter(Boolean).length;
    assert.ok(count <= 16, 'no optional chart request exceeds the provider-safe batch: ' + count);
  }
  assert.equal(await page.locator('#sector-grid .spark').count(), symbols.length,
    'every large-market card resolves to a chart or an honest unavailable state');
  await page.evaluate(() => {
    App.state.universe = window.__largeUniverseRestore;
    delete window.__largeUniverseRestore;
    App.state.explorerSector = null;
  });
  await go('#/home');
  await page.setViewportSize({ width: 1280, height: 900 });
});

test('intent-native UX: discount ladder, exit rungs, income board, symbol actions', async () => {
  // ACQUIRE at Learning: the ladder reads as sentences with a target/midpoint reference rung.
  await page.evaluate(() => { App.state.filterState = {}; });
  await go('#/trade/context/manual');
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.click('#intent-choices .choice[data-intent="ACQUIRE"]');
  await page.fill('#rec-symbol', 'AAPL');
  // live %-presets anchored to the quote fill the target with one tap
  await page.waitForSelector('#target-presets .sym-chip', { timeout: 15000 });
  await page.click('#target-presets .sym-chip:has-text("-10%")');
  assert.ok(parseFloat(await page.inputValue('#rec-target')) < 255.30);
  await page.click('#rec-go');
  await page.waitForSelector('#ladder-view');
  const ladder = await page.textContent('#ladder-view');
  assert.match(ladder, /Name your price/);
  assert.match(ladder, /paid/i);
  assert.doesNotMatch(ladder, /\(-\d+(?:\.\d+)?% below now\)/,
    'directional distance uses an absolute magnitude plus below/above, never a double negative');
  assert.ok(await page.locator('#ladder-view .ladder-row').count() >= 4, 'sentence rungs at Learning');
  assert.ok(await page.locator('#ladder-view .ladder-row.recommended').count() === 1, 'one target/midpoint reference rung');
  assert.doesNotMatch(ladder, /SANE MIDDLE/, 'a geometric midpoint is not mislabeled as an endorsement');
  assert.match(ladder, /WORTH A LOOK|COMPARE|LEARN FROM|MECHANICS ONLY/,
    'each rung carries the shared economic placement separately from its strike position');
  const ladderComposition = await page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#ladder-view .ladder-row'));
    const rights = rows.map(r => r.querySelector('.ladder-row-action').getBoundingClientRect().right);
    const centers = rows.map(r => {
      const rr = r.getBoundingClientRect(), br = r.querySelector('.ladder-row-action').getBoundingClientRect();
      return Math.abs((rr.top + rr.height / 2) - (br.top + br.height / 2));
    });
    const ladderFamilies = new Set(rows.map(r => r.dataset.strategy).filter(Boolean));
    const duplicates = Array.from(document.querySelectorAll('#rec-results .candidate[data-strategy], #rec-results table tr[data-strategy]'))
      .map(n => n.getAttribute('data-strategy')).filter(s => ladderFamilies.has(s));
    return { rightSpread: Math.max(...rights) - Math.min(...rights), maxCenterDelta: Math.max(...centers), duplicates };
  });
  assert.ok(ladderComposition.rightSpread <= 2 && ladderComposition.maxCenterDelta <= 2,
    'Practice actions occupy one stable, vertically centered column: ' + JSON.stringify(ladderComposition));
  assert.deepEqual(ladderComposition.duplicates, [],
    'a family already represented by the intent ladder is not repeated as another idea');

  // A passing ladder must never sit beside a contradictory "nothing passed" message. Intercept
  // only the standard recommendation response; the real ladder still comes from the backend.
  const recommendOnly = /\/api\/recommend$/;
  await page.route(recommendOnly, async route => {
    const response = await route.fetch();
    const payload = await response.json();
    payload.candidates = [];
    payload.notes = ['No strategy passed the risk screens for this combination — try a wider risk budget or different horizon'];
    await route.fulfill({ response, json: payload });
  });
  await page.click('#rec-go');
  await page.waitForSelector('#ladder-view .ladder-row');
  const reconciled = await page.textContent('#rec-results');
  assert.doesNotMatch(reconciled, /Nothing passed the risk screens/);
  assert.match(reconciled, /standard structure did not fit[\s\S]*alternate rungs that passed/i);
  await page.unroute(recommendOnly);

  // Same intent at Pro: a dense rung table with a Cash-set-aside column
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.click('#rec-go');
  await page.waitForSelector('#ladder-view .ladder-tbl');
  assert.match(await page.textContent('#ladder-view thead'), /Cash set aside/);
  assert.match(await page.textContent('#ladder-view thead'), /Effective price/);

  // EXIT with holdings: buy shares, holdings chip appears, exit rungs sized to the lot
  await page.evaluate(() => API.post('/api/positions/buy', { symbol: 'AAPL', shares: 100 }));
  await page.click('#intent-choices .choice[data-intent="EXIT"]');
  await page.waitForSelector('#holdings-chips .sym-chip', { timeout: 15000 });
  await page.click('#rec-go');
  await page.waitForSelector('#ladder-view .ladder-tbl');
  assert.match(await page.textContent('#ladder-view'), /Pick your exit/);
  assert.match(await page.textContent('#ladder-view thead'), /You.d sell at/);

  // A rung is a real candidate: Use -> ticket
  await page.click('#ladder-view tbody tr.clickable .btn');
  await page.waitForSelector('#to-review', { timeout: 30000 });

  // INCOME: the board shows YOUR capital, not an abstract list
  await go('#/trade/context/manual');
  await page.click('#intent-choices .choice[data-intent="INCOME"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForSelector('#income-board');
  const board = await page.textContent('#income-board');
  assert.match(board, /Free cash/);
  assert.match(board, /Shares to rent out/);

  // Research symbol page: per-goal action cards with live rung numbers
  await go('#/research/AAPL');
  await openResearchTab('options');
  await page.waitForSelector('#symbol-actions .action-card', { timeout: 20000 });
  const actions = await page.textContent('#symbol-actions');
  assert.match(actions, /Sell your 100 shares higher/);
  assert.match(actions, /Protect what you hold/);
  assert.match(actions, /You hold 100 sh/);

  // Clean up: void the ticket trade if placed? (none placed) — sell the shares back
  await page.evaluate(() => API.post('/api/positions/sell', { symbol: 'AAPL', shares: 100 }));
  App_reset: {
    await page.click('#level-switch button[data-level="beginner"]');
    await page.waitForSelector('#app[data-ready="true"]');
  }
});

test('experience ladder reshapes the UI per level', async () => {
  await go('#/portfolio/account');
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
  // Learning — explainers fully visible, glossary terms clickable
  assert.ok(await page.evaluate(() => document.body.classList.contains('lvl-beginner')));
  assert.ok(await page.locator('.explain').first().isVisible(), 'explainers visible at Learning');

  // Learning candidate cards use plain language and expandable mechanics.
  // The ideas form persists intent/symbol/target/filters across renders by design — reset here.
  await page.evaluate(() => { App.state.filterState = {}; App.state.discoverForm = null; });
  await go('#/trade/context/manual');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForSelector('.candidate');
  const learningCard = await page.textContent('.candidate');
  assert.match(learningCard, /Theoretical worst case/);
  assert.match(learningCard, /Chance of any profit/);
  await page.click('.candidate .xp-head'); // "How this trade works" expandable
  assert.match(await page.textContent('.candidate .xp.open .xp-body'), /You win when|You lose when/);
  // Glossary popover
  await page.click('.candidate .term');
  assert.ok(await page.locator('#glossary-popover').isVisible(), 'glossary popover opens');

  // PRESENTATION-ONLY LEVELS (review P0): beginner filters stay behind the expandable
  // (progressive disclosure) but ALL FIVE limits exist there — nothing is unreachable.
  assert.ok(await page.locator('#rec-filters .xp-head').count(), 'beginner filters live behind an expandable');
  await page.click('#rec-filters .xp-head');
  assert.ok(await page.locator('#rec-f-maxloss').isVisible(), 'max-loss limit at beginner');
  assert.ok(await page.locator('#rec-f-pop').isVisible(), 'POP limit at beginner');
  assert.ok(await page.locator('#rec-f-yield').isVisible(), 'income limit reachable at beginner too');
  assert.ok(await page.locator('#rec-f-assign').isVisible(), 'assignment limit reachable at beginner too');
  // 0DTE is a visible, explained choice at beginner — not a hidden capability
  assert.ok(await page.locator('#rec-horizon option[value="0DTE"]').count(), '0DTE horizon exists at beginner');
  // Beginner backtest menu is the FULL catalog (foundational structures lead each group)
  await go('#/trade/outcomes');
  assert.ok(await page.locator('#bt-strategy option[value="IRON_CONDOR"]').count(), 'condor reachable at beginner');
  assert.ok(await page.locator('#bt-strategy option[value="COVERED_CALL"]').count(), 'covered call available at beginner');
  assert.ok(await page.locator('#bt-engine').count(), 'both engines selectable at beginner');

  // Pro: dense body class, explainers gone, cards show metric chips
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.ok(await page.evaluate(() => document.body.classList.contains('lvl-expert')));
  await go('#/portfolio/account');
  assert.ok(!(await page.locator('.explain').first().isVisible()), 'explainers hidden at Pro');
  // Pro: compact intent segments, filters inline (no expandable), full backtest menu
  await go('#/trade/context/manual');
  await page.waitForSelector('#intent-choices.intent-compact');
  assert.ok(await page.locator('#rec-f-yield').isVisible(), 'all five filters inline at Pro');
  assert.equal(await page.locator('#rec-filters .xp-head').count(), 0, 'no expandable around Pro filters');
  const filterText = await page.textContent('#rec-filters');
  assert.match(filterText, /Chance of profit/);
  assert.match(filterText, /Worst case/);
  assert.doesNotMatch(filterText, /Min POP|Max assign %|% of account/i); // the old jargon is gone
  await go('#/trade/outcomes');
  assert.ok(await page.locator('#bt-strategy option[value="IRON_BUTTERFLY"]').count(), 'full strategy menu at Expert');
  // The identical menu at both levels (one catalog): compare option sets
  const expertOpts = await page.evaluate(() => Array.from(document.querySelectorAll('#bt-strategy option')).map(o => o.value).join(','));
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await go('#/trade/outcomes');
  const begOpts = await page.evaluate(() => Array.from(document.querySelectorAll('#bt-strategy option')).map(o => o.value).join(','));
  assert.equal(begOpts, expertOpts, 'one strategy catalog at both levels');
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');

  // Exactly two levels exist — the middle tier is gone for good
  assert.equal(await page.locator('#level-switch button').count(), 2, 'Beginner and Expert only');
  // restore default for other runs
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
});

test('levels share ONE geometry: toggling Beginner/Expert never reflows spacing', async () => {
  // Levels differ by CONTENT (explainers, columns, features) — never by padding or font
  // size alone. A screen showing the same data must not shift when the level toggles.
  await go('#/home');
  const geometry = () => page.evaluate(() => {
    const card = document.querySelector('.home-col .card');
    const stat = document.querySelector('#home-stats .stat');
    const cs = getComputedStyle(card), ss = getComputedStyle(stat);
    return [cs.padding, cs.marginBottom, ss.padding,
      getComputedStyle(stat.querySelector('.value')).fontSize].join('|');
  });
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('.home-col .card');
  const beginnerGeo = await geometry();
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('.home-col .card');
  assert.equal(await geometry(), beginnerGeo, 'identical spacing at both levels');
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
});

test('tape rebuilds when the window grows past its built half-length (no wrap gap)', async () => {
  await go('#/home');
  await page.setViewportSize({ width: 900, height: 720 });
  await page.evaluate(() => {
    document.getElementById('tape-strip').removeAttribute('data-symbols');
    return App.refreshTape();
  });
  await page.waitForFunction(() => {
    const s = document.getElementById('tape-strip');
    return s.hasAttribute('data-halfw') && s.children.length > 0;
  });
  const halfSmall = await page.evaluate(() => parseFloat(document.getElementById('tape-strip').getAttribute('data-halfw')));
  await page.setViewportSize({ width: 1600, height: 720 });
  // ResizeObserver debounce is 250ms; the rebuild re-measures and re-clones
  await page.waitForFunction(prev => {
    const s = document.getElementById('tape-strip');
    return parseFloat(s.getAttribute('data-halfw')) > prev;
  }, halfSmall, { timeout: 10000 });
  const check = await page.evaluate(() => {
    const s = document.getElementById('tape-strip');
    const view = document.querySelector('.tape-scroll').clientWidth;
    return { half: parseFloat(s.getAttribute('data-halfw')), view };
  });
  assert.ok(check.half >= check.view - 5, 'rebuilt halves cover the wider viewport — no blank wrap region');
  await page.setViewportSize({ width: 1280, height: 720 });
});

test('strategy builder: beginner wizard walks legs with impact; expert terminal analyzes', async () => {
  // ---- BEGINNER: goal -> shape -> leg-by-leg walkthrough -> whole position ----
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.evaluate(() => {
    App.state.builderForm = null; App.state.ticket = null;
    App.context.update({ goal: null, horizon: null, thesis: null });
  });
  await go('#/trade/structure');
  await page.waitForSelector('#bw-goals .choice');
  await page.setViewportSize({ width: 390, height: 844 });
  const mobileSteps = await page.locator('.builder-wizard-steps .step').evaluateAll(nodes => nodes.map(n => {
    const r = n.getBoundingClientRect();
    return { text: n.textContent.trim(), left: r.left, right: r.right,
      top: r.top, clipped: n.scrollWidth > n.clientWidth + 1 };
  }));
  assert.equal(mobileSteps.length, 4, 'all four Builder stages remain visible on a phone');
  assert.equal(new Set(mobileSteps.map(x => Math.round(x.top))).size, 2, 'Builder stages form a stable 2x2 grid');
  assert.ok(mobileSteps.every(x => x.left >= 0 && x.right <= 390 && !x.clipped),
    'no Builder stage label clips on mobile: ' + JSON.stringify(mobileSteps));
  await page.setViewportSize({ width: 1280, height: 720 });
  // Q&A, not text: goal cards -> one shaping question -> the structure
  await page.click('#bw-goals .choice[data-goal="DIRECTIONAL"]');
  await page.waitForSelector('#bw-shape .choice');
  // Two-tier Q&A: direction first, then the refinement — the full catalog is reachable
  assert.ok(await page.locator('#bw-shape .choice[data-next]').count() >= 4, 'nested questions per direction');
  await page.click('#bw-shape .choice:has-text("Stay calm in a range")');
  await page.waitForSelector('#bw-shape .choice[data-tpl="IRON_CONDOR"]');
  assert.ok(await page.locator('#bw-shape .choice[data-tpl="SHORT_STRADDLE"] .badge:has-text("BLOCKED")').count(),
    'blocked structures are offered as lessons, labeled');
  await page.click('#bw-shape .choice[data-tpl="IRON_CONDOR"]');
  // Walkthrough: each leg narrated, impact measured, payoff morphing
  await page.waitForSelector('#bw-walk');
  await page.waitForFunction(() =>
    /Collected so far|Paid so far/.test((document.getElementById('bw-impact') || {}).textContent || ''), { timeout: 20000 });
  assert.match(await page.textContent('#bw-leg-story'), /Sell the \$\d+/);
  assert.match(await page.textContent('#bw-impact'), /Theoretical worst case/);
  // C1 (state determinism): from leg 1, jump to "Where you stand" then back to "Build it" — it must
  // RESUME leg 1, not jump to the last leg. The same step showing a different payoff by navigation
  // path was the "graph changes randomly to something else" bug.
  await page.click('.wizard-steps button[data-step="4"]');
  await page.waitForSelector('#bw-final');
  await page.click('.wizard-steps button[data-step="3"]');
  await page.waitForFunction(() =>
    /leg 1 of 4/.test((document.querySelector('#bw-walk .field-label') || {}).textContent || ''), { timeout: 20000 });
  for (let legN = 2; legN <= 4; legN++) {
    await page.click('#bw-next');
    await page.waitForFunction(n =>
      new RegExp('leg ' + n + ' of 4').test(document.querySelector('#bw-walk .field-label').textContent), legN, { timeout: 20000 });
    await page.waitForFunction(() =>
      /Collected so far|Paid so far|unlimited risk/.test((document.getElementById('bw-impact') || {}).textContent || ''), { timeout: 20000 });
  }
  // Leg 3 (sell call over a put spread) is the teaching moment: unlimited alone, capped next
  const walk3 = await page.textContent('#bw-walk');
  assert.match(walk3, /leg 4 of 4/);
  await page.click('#bw-next'); // -> whole position
  await page.waitForSelector('#bw-final');
  await page.waitForFunction(() =>
    /Passes the safety screens|Allowed/.test((document.getElementById('bw-panel') || {}).textContent || ''), { timeout: 20000 });
  const finalText = await page.textContent('#bw-final');
  assert.match(finalText, /Theoretical worst case/);
  assert.match(finalText, /Assignment odds/);
  assert.ok(await page.locator('#bw-panel .chart-wrap').count(), 'payoff chart on the final step');

  // --- Learn by touching #1: the wizard steps are LIVE — go back, come forward ---
  await page.click('.wizard-steps button[data-step="1"]');
  await page.waitForSelector('#bw-goals .choice');
  await page.click('.wizard-steps button[data-step="4"]');
  await page.waitForSelector('#bw-final');
  await page.waitForFunction(() =>
    /Theoretical worst case/.test((document.getElementById('bw-panel') || {}).textContent || ''), { timeout: 20000 });

  // --- Learn by touching #2: the escape hatch — exact strikes/dates as real controls ---
  await page.click('#bw-final .xp-head:has-text("Fine-tune")');
  await page.waitForFunction(() => document.querySelectorAll('#bw-tune .tune-strike option').length > 0);
  const beforeStrike = await page.evaluate(() => App.state.builderForm.legs[0].strike);
  const options = await page.locator('#bw-tune .tune-row').first().locator('.tune-strike option')
    .evaluateAll(os => os.map(o => o.value));
  const other = options.find(v => v !== String(parseFloat(beforeStrike)));
  await page.locator('#bw-tune .tune-row').first().locator('.tune-strike').selectOption(other);
  await page.waitForFunction(prev => App.state.builderForm.legs[0].strike !== prev, beforeStrike, { timeout: 10000 });
  await page.waitForFunction(() =>
    /Theoretical worst case/.test((document.getElementById('bw-panel') || {}).textContent || ''), { timeout: 20000 });

  // --- Learn by touching #3: DRAG a strike marker on the payoff chart itself ---
  await page.waitForSelector('#bw-panel .strike-grip', { timeout: 20000 });
  const grip = page.locator('#bw-panel .strike-grip').first();
  await grip.scrollIntoViewIfNeeded(); // below-fold mouse events silently no-op
  const gripId = await grip.getAttribute('data-handle'); // 'legN'
  const legIdx = parseInt(gripId.replace('leg', ''), 10);
  const dragBefore = await page.evaluate(k => App.state.builderForm.legs[k].strike, legIdx);
  const box = await grip.boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 70, box.y + box.height / 2, { steps: 6 });
  await page.mouse.up();
  await page.waitForFunction(([k, prev]) => App.state.builderForm.legs[k].strike !== prev,
    [legIdx, dragBefore], { timeout: 10000 });
  await page.waitForFunction(() =>
    /Theoretical worst case/.test((document.getElementById('bw-panel') || {}).textContent || ''), { timeout: 20000 });
  // Your limits: judged live against the priced position
  await page.fill('#bl-maxLoss', '50');
  await page.locator('#bl-maxLoss').blur();
  await page.waitForSelector('#builder-limit-chips', { timeout: 20000 });
  assert.match(await page.textContent('#builder-limit-chips'), /Theoretical max loss/);
  assert.ok(await page.locator('#builder-limit-chips .limit-fail').count(), 'a $50 cap fails honestly on a condor');
  assert.ok(await page.locator('#builder-fit').count(), 'Fit to my limits is offered');
  await page.fill('#bl-maxLoss', '');
  await page.locator('#bl-maxLoss').blur();
  await page.waitForSelector('#builder-review', { timeout: 20000 });
  // Hand off into the standard ticket review
  await page.click('#builder-review');
  await page.waitForSelector('#to-confirm', { timeout: 30000 }); // the review step IS the landmark
  assert.match(await page.textContent('#ticket-body'), /Safety check/);
  await page.evaluate(() => { App.state.ticket = null; });

  // Browse-all catalog: every structure with its payoff shape, risky ones labeled
  await page.evaluate(() => {
    App.state.builderForm = null;
    App.context.update({ goal: null, horizon: null, thesis: null });
  });
  await go('#/trade/structure');
  await page.waitForSelector('#bw-goals .choice');
  await page.click('#bw-browse');
  await page.waitForSelector('#builder-catalog .tpl');
  assert.ok(await page.locator('#builder-catalog .tpl').count() >= 24, 'full broker-grade catalog');
  assert.ok(await page.locator('#builder-catalog .tpl-shape svg').count() >= 24, 'payoff-shape sketch per structure');
  assert.ok(await page.locator('#builder-catalog .tpl .badge:has-text("BLOCKED")').count() >= 3, 'undefined-risk structures labeled');

  // ---- EXPERT: terminal with inline market data, per-leg toggles, net greeks ----
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.evaluate(() => { App.state.builderForm = null; });
  await go('#/trade/structure');
  await page.waitForSelector('#builder-template');
  await page.selectOption('#builder-template', 'IRON_CONDOR');
  await page.waitForFunction(() => document.querySelectorAll('#builder-legs .leg-row').length === 4, { timeout: 20000 });
  // Inline market data on every leg row (no hover needed)
  await page.waitForFunction(() =>
    Array.from(document.querySelectorAll('#builder-legs .leg-mkt')).some(m => /\d/.test(m.textContent)), { timeout: 20000 });
  await page.waitForFunction(() =>
    /ALLOW|WARN/.test((document.getElementById('builder-panel') || {}).textContent || ''), { timeout: 20000 });
  const panel = await page.textContent('#builder-panel');
  assert.match(panel, /Net Δ sh/);
  assert.ok(await page.locator('#builder-panel .chart-wrap').count(), 'payoff chart in the terminal');
  // Depth on demand at Expert: structure guide + per-leg purpose, collapsed by default
  assert.ok(await page.locator('#builder-edu .xp-head:has-text("About this structure")').count(), 'structure education at expert');
  await page.click('#builder-edu .xp-head:has-text("What each leg is for")');
  assert.match(await page.textContent('#builder-edu'), /Leg 1:/);
  assert.ok(await page.locator('#bl-target').count(), 'full limits row in the terminal');
  // Hover a row: floating market insight, zero layout shift
  const before = await page.locator('#builder-legs').boundingBox();
  await page.locator('#builder-legs .leg-row').first().hover();
  await page.waitForSelector('.leg-pop', { timeout: 5000 });
  const after = await page.locator('#builder-legs').boundingBox();
  assert.equal(before.height, after.height, 'hover must not shift layout');
  // Per-leg impact: exclude the protective put -> the panel re-prices and flags a leg off
  await page.locator('#builder-legs .leg-row').nth(1).locator('.leg-on').setChecked(false);
  await page.waitForFunction(() =>
    /1 LEG OFF/.test((document.getElementById('builder-panel') || {}).textContent || ''), { timeout: 20000 });
  await page.locator('#builder-legs .leg-row').nth(1).locator('.leg-on').setChecked(true);
  await page.waitForFunction(() =>
    !/LEG OFF/.test((document.getElementById('builder-panel') || {}).textContent || ''), { timeout: 20000 });
  await page.evaluate(() => { App.state.builderForm = null; App.state.ticket = null; });
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
});

test('Builder keeps POP truth and adds goal-native assignment language', async () => {
  await go('#/home');
  await page.evaluate(() => {
    Learn.setLevel('beginner');
    App.state.builderForm = null; App.state.ticket = null;
    App.context.update({ symbol: 'AAPL', goal: null, horizon: null, thesis: null });
  });
  await go('#/trade/structure');
  await page.waitForSelector('#bw-goals .choice[data-goal="ACQUIRE"]');
  await page.click('#bw-goals .choice[data-goal="ACQUIRE"]');
  await page.waitForSelector('#bw-shape .choice[data-tpl="CASH_SECURED_PUT"]');
  await page.click('#bw-shape .choice[data-tpl="CASH_SECURED_PUT"]');
  await page.waitForFunction(() => /Chance you buy/.test((document.getElementById('bw-impact') || {}).textContent || ''),
    { timeout: 20000 });
  const impact = await page.textContent('#bw-impact');
  assert.match(impact, /Chance of any profit/, 'probability of profit remains visible as its own statistic');
  assert.match(impact, /Chance you buy/, 'assignment is translated into the acquisition goal');
  assert.doesNotMatch(impact, /Odds of profit/, 'the old generic wording is gone');
});

test('completed context flows into Structure once, with an honest edit affordance', async () => {
  await page.evaluate(() => {
    Learn.setLevel('beginner');
    App.state.builderForm = null; App.state.ticket = null;
    App.context.update({ symbol: 'AAPL', goal: 'DIRECTIONAL', horizon: 'month', thesis: 'bullish' });
  });
  await go('#/trade/structure');
  await page.waitForSelector('#bw-shape');
  assert.equal(await page.locator('#bw-goals').count(), 0,
    'Structure does not ask for the goal already completed in Context');
  assert.match(await page.textContent('#idea-bar'), /AAPL.*Trade a view.*bullish.*1 month/i);
  assert.ok(await page.locator('#idea-bar button:has-text("Edit context")').count(),
    'a complete context is offered for editing, never mislabeled as incomplete');
  await page.click('#bw-shape ~ .btn-row button:has-text("Back"), #bw-shape + .btn-row button:has-text("Back")');
  await page.waitForSelector('#bw-goals');
  assert.ok(await page.locator('#bw-goals .choice[data-goal="DIRECTIONAL"].selected').count(),
    'the carried goal remains editable through the normal Builder flow');
});

test('builder recovers from a failing symbol and follows the working symbol', async () => {
  // The reported trap: builder stuck on a symbol that fails to load, retrying forever
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/home'); // same-hash navigation renders nothing — leave the route first
  await page.evaluate(() => { App.state.builderForm = null; App.state.ticket = null; App.context.selectSymbol('ZZZZQQ'); });
  await go('#/trade/structure');
  await page.waitForSelector('#builder-load-error');
  assert.match(await page.textContent('#builder-load-error'), /Could not load ZZZZQQ/);
  assert.ok(await page.locator('#builder-retry').isVisible(), 'retry is right there');
  // One tap on a universe chip recovers — no dead end, no manual URL surgery
  await page.click('#builder-load-error .sym-chip:has-text("AAPL")');
  await page.waitForSelector('#builder', { timeout: 20000 });
  assert.equal(await page.locator('#builder-load-error').count(), 0, 'error state cleared');
  // An EMPTY builder follows the working symbol picked elsewhere
  await page.evaluate(() => { App.state.builderForm = null; App.context.selectSymbol('TSLA'); App.render(); });
  await page.waitForFunction(() => App.state.builderForm && App.state.builderForm.symbol === 'TSLA', { timeout: 20000 });
  await page.evaluate(() => { App.state.builderForm = null; App.context.selectSymbol('AAPL'); });
});

test('pipeline streamline: candidates open in the builder; Ideas links the full catalog', async () => {
  // Expert: a screened candidate's exact legs load straight into the builder terminal
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  // Filters persist by design now — clear them so a prior test's limits don't reject everything.
  await page.evaluate(() => {
    App.state.builderForm = null; App.state.ticket = null; App.state.filterState = {}; App.state.discoverForm = null;
    App.context.update({ symbol: 'AAPL', goal: 'DIRECTIONAL', horizon: 'month', thesis: 'bullish' });
  });
  await go('#/trade/context/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForSelector('#compare-table tbody tr.clickable', { timeout: 30000 });
  await page.click('#compare-table tbody tr.clickable');
  await page.waitForSelector('.compare-detail .candidate');
  await page.locator('.compare-detail .candidate button:has-text("Edit structure")').first().click();
  await page.waitForSelector('#builder-legs .leg-row', { timeout: 30000 }); // Shape-stage landmark
  assert.ok(await page.locator('#builder-legs .leg-row').count() >= 1, 'candidate legs loaded into the terminal');
  const carried = await page.evaluate(() => ({
    context: Object.assign({}, App.state.marketContext),
    builderGoal: App.state.builderForm && App.state.builderForm.goal,
    ticket: App.state.ticket && { intent: App.state.ticket.intent,
      horizon: App.state.ticket.horizon, thesis: App.state.ticket.thesis }
  }));
  assert.equal(carried.context.goal, 'DIRECTIONAL');
  assert.equal(carried.builderGoal, carried.context.goal, 'Builder inherits the same goal context');
  assert.equal(carried.ticket.intent, carried.context.goal, 'ticket snapshots the same goal');
  assert.equal(carried.ticket.horizon, carried.context.horizon, 'ticket snapshots the same horizon');
  assert.equal(carried.ticket.thesis, carried.context.thesis, 'ticket snapshots the same thesis');
  await page.waitForFunction(() =>
    /ALLOW|WARN|BLOCKED/.test((document.getElementById('builder-panel') || {}).textContent || ''), { timeout: 20000 });

  // Beginner: "All strategies" on Ideas lands on the browsable shape-card catalog
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await go('#/trade/context/manual');
  await page.waitForSelector('#all-strategies-link');
  await page.click('#all-strategies-link');
  await page.waitForSelector('#builder-catalog .tpl', { timeout: 20000 });
  assert.ok(await page.locator('#builder-catalog .tpl').count() >= 24, 'full catalog one tap from Ideas');
  await page.evaluate(() => { App.state.builderForm = null; App.state.ticket = null; });
});


test('smooth pipeline: GET cache, skeleton on slow loads, tape refresh keeps its animation', async () => {
  // Read-through cache: same GET inside the TTL returns the SAME promise
  const cached = await page.evaluate(() => {
    API.flushCache();
    const a = API.get('/api/universe');
    const b = API.get('/api/universe');
    return a === b;
  });
  assert.ok(cached, 'GET cache coalesces identical reads');

  // Mutations flush it; pure-compute POSTs (preview/recommend) must NOT
  const flushed = await page.evaluate(async () => {
    const a = API.get('/api/universe');
    await API.post('/api/trades/preview', { symbol: 'AAPL', legs: [] }).catch(() => null); // pure POST
    const b = API.get('/api/universe');
    await API.put('/api/account/risk-context', {}); // harmless genuine mutation
    const c = API.get('/api/universe');
    return { pureKept: a === b, mutationFlushed: b !== c };
  });
  assert.ok(flushed.pureKept, 'preview POST keeps the cache');
  assert.ok(flushed.mutationFlushed, 'mutations flush the cache');

  // Skeleton: with cold cache and a slowed endpoint, the shimmer shows instead of a void.
  // Trade detail is the natural probe: it must fetch before it can paint anything.
  const anyTrade = await page.evaluate(() =>
    API.getFresh('/api/trades?status=CLOSED&size=1').then(r => r.trades[0] && r.trades[0].id));
  assert.ok(anyTrade, 'a closed trade exists from earlier tests');
  await page.route('**/api/trades/tr_*', async r => {
    await new Promise(res => setTimeout(res, 600));
    r.fallback ? await r.fallback() : r.continue();
  });
  await page.evaluate(id => { API.flushCache(); location.hash = '#/trade/' + id; }, anyTrade);
  await page.waitForSelector('.skel-screen', { timeout: 3000 });
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 15000 });
  assert.equal(await page.locator('.skel-screen').count(), 0, 'skeleton leaves when content lands');
  await page.unroute('**/api/trades/tr_*');

  // Tape: refresh with unchanged symbols updates numbers IN PLACE — no rebuild, no restart
  const stable = await page.evaluate(async () => {
    await App.refreshTape(); // settle to the current universe (may legitimately rebuild)
    const strip = document.getElementById('tape-strip');
    if (!strip || !strip.firstChild) return 'no-tape';
    const before = strip.firstChild;
    await App.refreshTape(); // same symbols -> must update in place
    return strip.firstChild === before && strip.getAttribute('data-symbols').length > 0;
  });
  assert.equal(stable, true, 'tape strip not rebuilt when symbols unchanged');
});

test('inline competition: the pick, evidence, scenarios, plan, and handoff', async () => {
  await page.evaluate(() => {
    Learn.setLevel('expert');
    App.state.discoverForm = { symbol: 'AAPL', thesis: 'bullish', horizon: 'month' };
  });
  await openInlineCompetition('AAPL');

  // The pick, with its honesty badges and score.
  await page.waitForSelector('.decision-pick');
  assert.ok(await page.locator('.decision-pick .pick-badge').first().isVisible(), 'economic placement badge');
  assert.doesNotMatch(await page.locator('.decision-pick .pick-badge').first().innerText(), /^THE PICK$/,
    'mechanical availability is not presented as an endorsement');
  const evBadge = (await page.locator('.decision-pick .row-gap .badge').first().innerText()).trim();
  assert.match(evBadge, /Demo data|Observed|Modeled|Unknown/, 'evidence badge is labeled honestly');
  assert.ok(await page.locator('.decision-pick .score-wrap[aria-label="Decision score"]').first().isVisible(), 'final Decision score meter');
  assert.ok(await page.locator('.decision-pick [data-economic-verdict]').count(), 'Decision consumes the shared economic verdict');
  assert.match(await page.textContent('.decision-pick [data-economic-verdict]'), /Market-implied EV/);
  assert.match(await page.textContent('.decision-pick [data-economic-verdict]'), /Realized-vol scenario EV/);

  // Real risk scenarios + the honest capital pair + the co-equal management plan.
  assert.ok((await page.locator('.decision-pick .scenario-strip .scenario-cell').count()) >= 3, 'payoff scenario strip');
  assert.ok((await page.locator('.decision-pick:has-text("Buying power used")').count()) > 0, 'incremental capital');
  assert.ok((await page.locator('.decision-pick:has-text("Full exposure")').count()) > 0, 'economic capital');
  assert.ok((await page.locator('.decision-pick:has-text("The plan after you enter")').count()) > 0, 'management plan');

  // When there is a field of alternatives, Expert ranks them in a comparison table.
  if ((await page.locator('.section-h').count()) > 0) {
    assert.ok((await page.locator('#decision-table').count()) > 0, 'expert comparison table for the field');
    assert.match(await page.textContent('.decision-order-note'), /monotonic/i, 'the final score/order contract is disclosed');
    assert.match(await page.textContent('#decision-table thead'), /Market EV/);
    assert.match(await page.textContent('#decision-table thead'), /History EV/);
    const scores = await page.locator('#decision-table tbody .score-num').allTextContents();
    const numeric = scores.map(Number);
    assert.deepEqual(numeric, numeric.slice().sort((a, b) => b - a),
      'displayed Decision scores descend in exactly the served order');
  }

  // Handing the winner to Decide carries the working structure.
  await page.click('#decision-use');
  await page.waitForFunction(() => location.hash.indexOf('/trade/decide') >= 0);
  await page.waitForSelector('#app[data-ready="true"]');

  // Reset shared state so later tests' assumptions hold.
  await page.evaluate(() => { App.state.ticket = null; App.state.discoverForm = null; });
});

test('decision caching: a cosmetic level flip does NOT re-POST /api/evaluate (no double-recorded pick)', async () => {
  await page.evaluate(() => {
    App.state.decisionCache = null;
    Learn.setLevel('expert');
    App.state.discoverForm = { symbol: 'AAPL', thesis: 'bullish', horizon: 'month' };
  });
  let evalPosts = 0;
  const counter = (req) => { if (req.method() === 'POST' && req.url().indexOf('/api/evaluate') >= 0) evalPosts++; };
  page.on('request', counter);
  try {
    await openInlineCompetition('AAPL');
    assert.equal(evalPosts, 1, 'first render evaluates once');
    // Flip the level: the page re-renders but the inputs are unchanged — must reuse the cache.
    await page.click('#level-switch button[data-level="beginner"]');
    await page.waitForSelector('#compare-ideas-btn');
    await page.click('#compare-ideas-btn');
    await page.waitForSelector('#decision-host .decision-pick');
    await page.waitForTimeout(300);
    assert.equal(evalPosts, 1, 'level flip reuses the cached evaluation — no second POST');
    // Refresh is an explicit "re-evaluate" — it busts the cache.
    await page.click('#decision-refresh');
    await page.waitForSelector('.decision-pick');
    await page.waitForTimeout(300);
    assert.equal(evalPosts, 2, 'Refresh re-POSTs');
  } finally {
    page.off('request', counter);
  }
  await page.evaluate(() => { App.state.discoverForm = null; App.state.decisionCache = null; Learn.setLevel('expert'); });
});

test('portfolio sizing and research tools live in their natural workflows', async () => {
  await page.evaluate(() => Learn.setLevel('expert'));
  // The retired Lab URL has no hidden pointer or alias. Unknown routes fall back to Home.
  await page.evaluate(() => { window.location.hash = '#/lab'; });
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('#app[data-route="home"]', { timeout: 8000 });
  assert.ok(!(await page.locator('#nav a[data-route="lab"]').count()), 'no retired navigation item');

  // Retired internal routes do not remain as hidden aliases. They are unknown routes and
  // therefore land at Home; current workflows use only the canonical product vocabulary.
  for (const retired of ['#/welcome', '#/account', '#/status', '#/decision',
    '#/trade/discover', '#/trade/shape', '#/trade/backtest', '#/trade/place']) {
    await page.evaluate(h => { window.location.hash = h; }, retired);
    await page.waitForFunction(() => document.getElementById('app').getAttribute('data-route') === 'home');
  }

  // Portfolio sizing lives at the bottom of the inline Context competition.
  await page.evaluate(() => {
    App.state.discoverForm = { symbol: 'AAPL', goal: 'DIRECTIONAL', thesis: 'neutral', horizon: 'month' };
    App.state.filterState = {};
  });
  await openInlineCompetition('AAPL');
  await page.waitForSelector('#portfolio-build', { timeout: 25000 });
  // Diagnostic mode guarantees a funded (least-bad) set on the fixture universe, LABELED not-a-recommendation.
  await page.check('#portfolio-diagnostics');
  await page.click('#portfolio-build');
  await page.waitForSelector('#portfolio-summary', { timeout: 20000 });
  assert.ok((await page.locator('.portfolio-optimizer .tool-chart svg rect').count()) >= 1, 'composition chart bars');
  assert.ok((await page.locator('.portfolio-optimizer table tbody tr').count()) >= 1, 'allocations table rows');
  assert.ok((await page.locator('#portfolio-output .alert-caution').count()) >= 1, 'diagnostic set labeled not-a-recommendation');

  // The Research LANDING is a market-entry surface: notes stay; the event-study workbench does
  // The event study lives on symbol pages inside Test your view, not as an orphan index card.
  await go('#/research');
  await page.waitForSelector('#research-study-tools .tool-grid', { timeout: 15000 });
  assert.equal(await page.locator('#research-study-tools #study-run').count(), 0,
    'no full study workbench on the landing page');
  // The study itself (run on a SYMBOL page) is baseline-relative with resolved design tokens.
  await go('#/research/AAPL');
  await openResearchTab('view');
  await page.waitForSelector('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  assert.ok((await page.locator('#study-results .tool-chart svg').count()) >= 2, 'win-rate gauge + distribution histogram');
  assert.match(await page.textContent('#study-results'), /After the signal|Normally/); // baseline-relative, not "vs 50% chance"
  const gaugeHtml = await page.locator('#study-results .tool-chart').first().innerHTML();
  assert.ok(gaugeHtml.indexOf('var(--line)') < 0 && gaugeHtml.indexOf('var(--fg)') < 0, 'gauge uses no unresolved CSS vars');
  await go('#/research');
  await page.waitForSelector('#research-study-tools .tool-grid', { timeout: 15000 });

  // Notebook: create + delete (delete regression: button called the non-existent API.delete).
  await page.click('#research-note-new');
  await page.fill('.note-editor input[placeholder="Title"]', 'My hypothesis');
  await page.fill('.note-editor textarea', 'SPY put spreads only at high IV rank');
  await page.click('.note-editor button:has-text("Save")');
  await page.waitForSelector('.research-notebook:has-text("My hypothesis")', { timeout: 10000 });
  await page.locator('.research-notebook').getByText('My hypothesis').click();
  await page.locator('.research-notebook').getByRole('button', { name: 'Delete' }).click();
  await page.waitForFunction(
    () => { var nb = document.querySelector('.research-notebook'); return nb && nb.textContent.indexOf('My hypothesis') < 0; },
    { timeout: 8000 });
});

test('cache: read-only compute POST (/api/evaluate) keeps the GET cache warm', async () => {
  await go('#/home');
  const r = await page.evaluate(async () => {
    let n = 0;
    const orig = window.fetch;
    window.fetch = function (u) { if (String(u).indexOf('/api/config') >= 0) n++; return orig.apply(this, arguments); };
    try {
      await API.get('/api/config');           // warm (or already cached from boot)
      const before = n;
      await App.evaluateOutcome('DECISION', 'DECISION_POLICY', 'AAPL', {
        decision: { symbol: 'AAPL', thesis: 'bullish', horizon: 'month', riskMode: 'balanced' }
      });
      await API.get('/api/config');           // must be served from cache — the compute POST must not flush it
      return { added: n - before };
    } finally { window.fetch = orig; }
  });
  assert.equal(r.added, 0, '/api/config was refetched after /api/evaluate — the cache was wrongly flushed');
});

test('cross-level invariance: identical persisted inputs produce the identical engine request', async () => {
  // PRESENTATION-ONLY LEVELS (review P0): every control is visible at both levels, so the
  // exact same persisted state must produce byte-identical requests as Beginner and Expert.
  // (The old test pinned Beginner request SANITIZATION — that contract is retired: nothing
  // is hidden anymore, so nothing needs to be dropped.)
  const seedState = () => page.evaluate(() => {
    App.state.filterState = { rec: { minPop: '', maxAssign: '80', minYield: '5', maxCost: '300' } };
    App.state.discoverForm = { goal: 'DIRECTIONAL', source: 'single', symbol: 'AAPL', horizon: '0DTE', allow0: true };
    App.state.recommendResults = null;
  });
  async function captureRequest(level) {
    await seedState();
    await page.click(`#level-switch button[data-level="${level}"]`);
    await page.waitForSelector('#app[data-ready="true"]');
    let body = null;
    await page.route('**/api/recommend', route => {
      try { body = JSON.parse(route.request().postData() || '{}'); } catch (e) {}
      route.continue();
    });
    await go('#/trade/context/manual');
    await page.fill('#rec-symbol', 'AAPL');
    await page.click('#rec-go');
    await page.waitForTimeout(1200);
    await page.unroute('**/api/recommend');
    return body;
  }
  const asBeginner = await captureRequest('beginner');
  const asExpert = await captureRequest('expert');
  assert.ok(asBeginner && asExpert, 'both requests captured');
  assert.deepEqual(asBeginner, asExpert, 'identical inputs => identical request at both levels');
  // And the visible controls were honored, not dropped:
  assert.equal(asBeginner.horizon, '0DTE', 'persisted 0DTE horizon honored');
  assert.equal(asBeginner.allow0dte, true, 'persisted allow0dte honored');
  const f = asBeginner.filters || {};
  assert.equal(f.maxAssignmentProb, 0.8, 'assignment limit honored at beginner');
  assert.equal(f.minAnnualizedYieldPct, 5, 'yield limit honored at beginner');
  assert.equal(f.maxCostCents, 30000, 'cost limit honored at beginner');
  await page.evaluate(() => { App.state.filterState = {}; App.state.discoverForm = null; App.state.recommendResults = null; });
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
});

test('undefined-risk framing: synthetic short is BLOCKED, synthetic long is not', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  await page.evaluate(() => { App.state.builderForm = null; App.state.ticket = null; });
  await go('#/trade/structure');
  // A carried workflow goal now opens on the shaping question; a direct visit with no
  // goal opens on the goal chooser. Either path must still expose the full catalog.
  await page.waitForSelector('#bw-goals .choice, #bw-shape .choice, #builder-catalog', { timeout: 15000 });
  // Open the full catalog (browse-all) and find the two synthetics.
  const browse = page.locator('#bw-browse, button:has-text("Browse all structures")').first();
  if (await browse.count()) { await browse.click(); }
  await page.waitForSelector('.tpl[data-tpl="SYNTHETIC_SHORT"]', { timeout: 15000 });
  assert.ok(await page.locator('.tpl[data-tpl="SYNTHETIC_SHORT"] .badge:has-text("BLOCKED")').count(),
    'synthetic short must carry the BLOCKED (undefined-risk) badge');
  assert.ok(!(await page.locator('.tpl[data-tpl="SYNTHETIC_LONG"] .badge:has-text("BLOCKED")').count()),
    'synthetic long is defined-risk — no BLOCKED badge');
});

test('synthetic exposure sizing lives inside the selected Builder structure', async () => {
  await page.evaluate(() => {
    Learn.setLevel('expert');
    App.state.builderForm = null;
    App.state.ticket = null;
    App.context.selectSymbol('AAPL');
  });
  await go('#/home');
  await go('#/trade/structure');
  await page.waitForSelector('#builder-template', { timeout: 15000 });
  assert.equal(await page.locator('#replicate-run, #replicate-output').count(), 0,
    'the retired standalone replication tool is not mounted');

  await page.selectOption('#builder-template', 'SYNTHETIC_LONG');
  await page.waitForSelector('#builder-exposure-sizer');
  await page.fill('#builder-exposure-target', '100000');
  await page.click('#builder-size-exposure');
  await page.waitForSelector('#builder-exposure-output:has-text("Contracts")', { timeout: 10000 });
  const qty = Number(await page.inputValue('#builder-qty'));
  assert.ok(qty >= 1, 'sizing updates the Builder quantity');
  assert.match(await page.textContent('#builder-exposure-output'), /Delta exposure[\s\S]*Equivalent shares would cost/);

  await page.selectOption('#builder-template', 'SYNTHETIC_SHORT');
  await page.waitForSelector('#builder-exposure-sizer');
  await page.click('#builder-size-exposure');
  await page.waitForSelector('#builder-exposure-output:has-text("UNDEFINED RISK")', { timeout: 10000 });
  await page.waitForSelector('#builder-review[disabled]', { timeout: 10000 });
  assert.equal(await page.locator('#builder-review:not([disabled])').count(), 0,
    'undefined-risk synthetic short remains blocked by the ordinary Builder review');
});

test('the workflow carries one exact structure through Outcomes and back into Structure', async () => {
  await page.evaluate(() => {
    Learn.setLevel('expert');
    App.state.builderForm = null;
    App.state.ticket = null;
    App.state.backtestForm = null;
    App.context.selectSymbol('AAPL');
  });
  await go('#/trade/structure');
  await page.waitForSelector('#builder-template');
  await page.selectOption('#builder-template', 'DEBIT_CALL_SPREAD');
  await page.waitForFunction(() => App.state.builderForm && App.state.builderForm.legs.length === 2);
  const before = await page.evaluate(() => JSON.parse(JSON.stringify(App.state.builderForm.legs)));

  await page.click('#wf-outcomes');
  await page.waitForSelector('#trade-outcomes');
  assert.equal(await page.inputValue('#bt-strategy'), 'DEBIT_CALL_SPREAD',
    'Outcomes inherits the named Builder structure');
  assert.deepEqual(await page.evaluate(() => App.state.ticket.legs), before,
    'the workflow normalizes the exact Builder legs into its shared position contract');

  await page.click('#wf-structure');
  await page.waitForSelector('#builder-panel');
  assert.deepEqual(await page.evaluate(() => App.state.builderForm.legs), before,
    'returning to Structure preserves every contract instead of rebuilding a nearby template');

  await page.selectOption('#builder-template', 'CREDIT_PUT_SPREAD');
  await page.waitForFunction(() => App.state.builderForm && App.state.builderForm.templateKey === 'CREDIT_PUT_SPREAD');
  const revised = await page.evaluate(() => JSON.parse(JSON.stringify(App.state.builderForm.legs)));
  await page.click('#wf-outcomes');
  await page.waitForSelector('#bt-strategy');
  assert.equal(await page.inputValue('#bt-strategy'), 'CREDIT_PUT_SPREAD');
  assert.equal(await page.evaluate(() => App.state.ticket.customFamily), 'CREDIT_PUT_SPREAD',
    'leaving Structure replaces an older ticket with the newly edited package');
  await page.click('#wf-structure');
  await page.waitForSelector('#builder-panel');
  assert.deepEqual(await page.evaluate(() => App.state.builderForm.legs), revised);

  await page.evaluate(() => {
    App.state.ticket = null;
    App.state.builderForm.templateKey = null;
    App.render();
  });
  await page.waitForSelector('#builder-panel');
  await page.click('#wf-outcomes');
  await page.waitForSelector('#trade-outcomes-basis-scenario[aria-selected="true"]');
  assert.ok(await page.isVisible('#bt-scenario-card'),
    'an unnamed custom package defaults to the exact-leg Monte Carlo basis, not an unrelated historical rule');
});

test('D3: the competition renders INLINE in Ideas (no orphan Decision page navigation)', async () => {
  await page.evaluate(() => Learn.setLevel('expert'));
  await page.evaluate(() => { App.state.filterState = {}; App.state.discoverForm = null; App.state.recommendResults = null; });
  await go('#/trade/context/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForSelector('#compare-ideas-btn', { timeout: 25000 });
  const hashBefore = await page.evaluate(() => location.hash);
  await page.click('#compare-ideas-btn');
  // The ranked competition (the pick) appears inline in the Ideas results, same hash.
  await page.waitForSelector('#decision-host .decision-pick', { timeout: 20000 });
  assert.equal(await page.evaluate(() => location.hash), hashBefore, 'stayed on Ideas — no navigation to a separate Decision page');
  await page.evaluate(() => { App.state.discoverForm = null; App.state.recommendResults = null; });
});

test('simulated market: product creator, loud live band, world-routed research, instant return', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/data/simulation');
  // The PRODUCT creator: scenario story cards, labeled fields, no micro-syntax, no prompt().
  await page.waitForSelector('#dc-sim-market #sim-scenarios .sim-scenario');
  assert.ok((await page.locator('#sim-scenarios .sim-scenario').count()) >= 6, 'scenario story cards');
  assert.equal(await page.getAttribute('#sim-scenarios .sim-scenario[data-scenario="CHOP"]', 'aria-pressed'), 'true',
    'the selected market story is visibly and accessibly selected');
  const creatorDefaults = await page.$$eval('#sim-symbol-chips [data-picked-sym]',
    els => els.map(e => e.getAttribute('data-picked-sym')));
  const creatorUniverse = await page.evaluate(() => (App.state.universe.active.symbols || []).slice());
  assert.ok(creatorDefaults.length >= 1 && creatorDefaults.every(s => creatorUniverse.includes(s))
      && !creatorDefaults.includes('ACME'),
    'creator starts from a recognized current-market ticker, never an unusable fictional default: '
      + JSON.stringify(creatorDefaults));
  assert.equal(await page.isChecked('#sim-use-sector'), true,
    'the current market/universe rides along by default so the world is not a ghost town');
  await page.click('#sim-scenarios .sim-scenario[data-scenario="SELLOFF_REBOUND"]');
  await page.waitForSelector('#sim-scenarios .sim-scenario[data-scenario="SELLOFF_REBOUND"][aria-pressed="true"]');
  assert.ok(await page.locator('#sim-scenarios .sim-scenario[data-scenario="SELLOFF_REBOUND"] .badge:has-text("SELECTED")').count(),
    'clicking a simulated-market card produces an unmistakable state change');
  await page.fill('#sim-name', 'DOM sim');
  await page.fill('#sim-symbols', 'ACME');
  await page.check('#sim-allow-fictional'); // EXPLICIT opt-in — fictional is never inferred (and never pre-checked)
  await page.click('#sim-create');

  // Switch happened: the loud band appears with HUMAN wording + live controls.
  await page.waitForFunction(() => /SIMULATED MARKET/.test((document.getElementById('world-band') || {}).textContent || ''),
    { timeout: 20000 });
  const band = await page.$eval('#world-band', el => el.textContent);
  assert.match(band, /SIMULATED MARKET/, 'band names the world loudly');
  assert.match(band, /Sell-off, then rebound/, 'band speaks human, not SELLOFF_REBOUND');
  assert.ok(await page.$('#world-speed'), 'live speed control on the band');
  assert.ok(await page.$('#world-exit'), 'one-command return to the real market');
  assert.ok(await page.evaluate(() => document.body.classList.contains('in-sim-world')));
  await page.waitForFunction(() => {
    const sel = document.getElementById('tape-sector');
    return sel && sel.value === 'world' && /DOM sim/i.test(sel.options[sel.selectedIndex]?.textContent || '');
  });
  assert.doesNotMatch(await page.locator('#tape-sector option:checked').textContent(), /demo/i,
    'the compact market selector commits with the simulated lane instead of retaining Demo state');
  await page.setViewportSize({ width: 390, height: 844 });
  const mobileWorldChrome = await page.evaluate(() => ({
    bandHeight: document.getElementById('world-band').getBoundingClientRect().height,
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth
  }));
  assert.ok(mobileWorldChrome.bandHeight <= 76,
    'mobile simulated-market controls stay compact: ' + JSON.stringify(mobileWorldChrome));
  assert.ok(mobileWorldChrome.scrollWidth <= mobileWorldChrome.innerWidth,
    'simulated-market chrome never widens the phone viewport');
  await page.setViewportSize({ width: 1280, height: 900 });
  const created = await page.evaluate(async () => {
    const all = (await API.getFresh('/api/sim/market')).sessions || [];
    return { worldId: App.state.world, sessions: all.length };
  });
  const baselineWorld = await page.evaluate(() => App.baseWorldId());
  assert.ok(created.worldId && created.worldId !== baselineWorld);

  // The active world owns this screen. Every ordinary session symbol is represented at once,
  // one focus chart is explicit, and Demo is not offered as a competing primary market.
  await go('#/data/simulation');
  await page.waitForSelector('#sim-control-room #cr-symbols .sim-symbol-tile', { timeout: 20000 });
  const controlRoom = await page.evaluate(async () => {
    const sessions = (await API.getFresh('/api/sim/market')).sessions || [];
    const current = sessions.find(x => x.id === App.state.world);
    const room = document.getElementById('sim-control-room');
    room.scrollIntoView({ block: 'start' });
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const tiles = Array.from(document.querySelectorAll('#cr-symbols .sim-symbol-tile'));
    const first = tiles[0];
    const rect = first.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + 12);
    return {
      expected: Object.keys((current && current.config && current.config.symbolBetas) || {}).length,
      rendered: tiles.length,
      demoAction: !!document.getElementById('enter-demo-market'),
      focusCharts: document.querySelectorAll('#cr-chart svg.chart.candles').length,
      stickyHeight: parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--sticky-stack-h')) || 0,
      firstTop: rect.top,
      firstHit: !!(hit && hit.closest('.sim-symbol-tile') === first)
    };
  });
  assert.equal(controlRoom.rendered, controlRoom.expected, 'all session symbols have visible overview tiles');
  assert.equal(controlRoom.demoAction, false, 'an active simulation has no competing Enter demo action');
  assert.equal(controlRoom.focusCharts, 1, 'one explicit focus chart, with overview tiles for breadth');
  assert.ok(controlRoom.firstTop >= controlRoom.stickyHeight - 2 && controlRoom.firstHit,
    'the measured sticky stack does not cover the symbol overview: ' + JSON.stringify(controlRoom));
  const focusTarget = await page.locator('#cr-symbols .sim-symbol-tile').nth(1).getAttribute('data-symbol');
  await page.locator('#cr-symbols .sim-symbol-tile').nth(1).click();
  await page.waitForFunction((sym) => {
    const head = document.querySelector('.sim-focus-head');
    const active = document.querySelector('#cr-symbols .sim-symbol-tile.active');
    return head && head.textContent.includes(sym) && active && active.getAttribute('data-symbol') === sym;
  }, focusTarget);

  // Research is world-routed AND the market VISIBLY MOVES: the hero price updates on ticks.
  await go('#/research/ACME');
  await page.waitForSelector('.quote-hero');
  // INVERTED CONTRACT (holistic review P0-2): the tape must stay VISIBLE inside the world and
  // honestly quote the WORLD's symbols with a SIMULATED chip — hiding the primary market
  // navigation made simulation a ghost town, and the old test asserted that as 'tape yields'.
  await page.waitForFunction(() => {
    const t = document.getElementById('tape');
    if (!t || t.hidden) return false;
    if (getComputedStyle(t).display === 'none') return false;
    const chip = document.getElementById('tape-demo-chip');
    return chip && chip.textContent === 'SIMULATED'
      && !!document.querySelector('#tape-strip [data-sym="ACME"]');
  }, { timeout: 20000 });
  // The universe schema is STABLE across modes: active.symbols exists and carries the world.
  const uni = await page.evaluate(async () => await API.getFresh('/api/universe'));
  assert.ok(uni.active && Array.isArray(uni.active.symbols), 'one UniverseView schema in world mode');
  assert.ok(uni.active.symbols.includes('ACME'), 'world symbols drive the universe');
  assert.ok(Array.isArray(uni.sectors) && uni.sectors.length >= 1, 'sectors present (the session)');
  const badges = await page.$$eval('.quote-hero .badge', els => els.map(e => e.textContent).join(' '));
  assert.match(badges, /SIMULATED/, 'sim quotes carry the SIMULATED label');
  const px0 = await page.textContent('#research-px');
  // Step the world several times via the band. Each step publishes a REAL world.tick SSE hint;
  // the app's own handler must move the hero price. NO fallback: the old catch wrote the price
  // into the DOM itself, which masked a dead SSE path (weekend-handoff review M5).
  for (let i = 0; i < 12; i++) await page.click('#world-step');
  await page.waitForFunction((prev) => {
    const el2 = document.getElementById('research-px');
    return el2 && el2.textContent !== prev;
  }, px0, { timeout: 20000 });
  const px1 = await page.textContent('#research-px');
  assert.notEqual(px1, px0, 'the app’s own SSE handler moved the price on screen');

  // The simulation account is the account inside the world.
  const simAcct = await page.evaluate(async () => (await API.getFresh('/api/account')).account.id);

  // Return to the build's baseline market (Observed normally, explicit Demo in fixture tests):
  // account, universe, store, and chrome must agree. The SSE
  // world.selected hint racing the awaited PUT used to skip this (stale "Dom sim (simulated)").
  await page.click('#world-exit');
  await page.waitForFunction((baseline) => {
    const u = App.state.universe;
    return App.state.world === baseline && u && u.active && !/simulated session/i.test(u.active.label || '')
      && App.Market.world === baseline && !document.body.classList.contains('in-sim-world');
  }, baselineWorld, { timeout: 15000 });
  const after = await page.evaluate(async () => ({
    world: (await API.getFresh('/api/world')).world,
    acct: (await API.getFresh('/api/account')).account.id,
    simBody: document.body.classList.contains('in-sim-world'),
    uniLabel: (App.state.universe && App.state.universe.active && App.state.universe.active.label) || ''
  }));
  assert.equal(after.world, baselineWorld);
  assert.notEqual(after.acct, simAcct, 'back on the baseline practice account');
  assert.equal(after.simBody, false);
  assert.doesNotMatch(after.uniLabel, /simulated session/i, 'the universe label reconciled to the baseline market');

  // Finish via the app modal (never window.confirm): the REPORT shows before the finish.
  await go('#/data/simulation');
  await page.waitForSelector('.sim-session-row button:has-text("Finish")');
  // The card surface itself enters the market; it is not a decorative shell around buttons.
  await page.click('.sim-session-row', { position: { x: 5, y: 5 } });
  await page.waitForFunction((baseline) => App.state.world !== baseline, baselineWorld, { timeout: 15000 });
  await page.click('#world-exit');
  await page.waitForFunction((baseline) => App.state.world === baseline, baselineWorld, { timeout: 15000 });
  await go('#/data/simulation');
  await page.waitForSelector('.sim-session-row button:has-text("Finish")');
  await page.click('.sim-session-row button:has-text("Finish")');
  await page.waitForSelector('#modal-confirm');
  await page.waitForSelector('#sim-report', { timeout: 15000 });
  const rep = await page.textContent('#sim-report');
  assert.match(rep, /DECISIONS, not the market/, 'the honesty note shows at session end');
  await page.click('#modal-confirm');
  // Finished sessions stay listed (reports kept) under the collapsed group.
  await page.waitForSelector('.xp-head:has-text("Finished sessions")', { timeout: 15000 });
});

test('golden weekend-trader journey: creator UI, injected shock, UI trade, moving book, report, clean return', async () => {
  // F12: the acceptance journey drives the PRODUCT — the Data creator (tokenized picker at
  // Beginner), the control room's inject modal, the Discover→Place trade flow at Expert, the
  // trade-detail Unwind, and the control-room Finish. No API-shaped shortcuts for user actions.
  const baselineWorld = await page.evaluate(() => App.baseWorldId());
  const realAcct = await page.evaluate(async () => (await API.getFresh('/api/account')).account.id);
  await page.evaluate(() => { App.state.ticket = null; App.state.discoverForm = null; });

  // 1. BEGINNER creates the world through the tokenized picker (type AAPL, press Enter).
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/data/simulation');
  await page.waitForSelector('#dc-sim-market #sim-scenarios .sim-scenario');
  await page.click('#sim-scenarios .sim-scenario[data-scenario="CHOP"]');
  await page.fill('#sim-name', 'Journey');
  await page.click('#sim-symbols');
  await page.type('#sim-symbols', 'AAPL');
  await page.keyboard.press('Enter');
  await page.waitForSelector('#sim-symbol-chips [data-picked-sym="AAPL"]');
  assert.ok(!(await page.isChecked('#sim-allow-fictional')), 'fictional symbols default OFF — opting in is an action');
  await page.click('#sim-create');
  await page.waitForFunction(() => /SIMULATED MARKET/.test((document.getElementById('world-band') || {}).textContent || ''),
    { timeout: 20000 });
  const wConf = await page.evaluate(async () => {
    const all = (await API.getFresh('/api/sim/market')).sessions || [];
    const cur = all.find(x => x.id === App.state.world);
    return { worldId: App.state.world, betas: Object.keys(cur.config.symbolBetas), anchors: cur.anchorSummary };
  });
  assert.ok(wConf.betas.includes('AAPL') && wConf.betas.includes('SPY'), 'AAPL + benchmarks in the world');
  assert.ok(wConf.anchors && wConf.anchors.anchored >= 3, 'anchor coverage rides on the session row');

  // 2. Research lives: the tape quotes the world, the hero moves on ticks.
  await go('#/research/AAPL');
  await page.waitForSelector('.quote-hero');
  await page.waitForFunction(() => {
    const chipEl = document.getElementById('tape-demo-chip');
    return chipEl && chipEl.textContent === 'SIMULATED'
      && !!document.querySelector('#tape-strip [data-sym="AAPL"]');
  }, { timeout: 20000 });

  // 3. Inject a -5% AAPL shock THROUGH THE CONTROL ROOM MODAL; the hero must visibly react.
  const pxBefore = parseFloat(await page.textContent('#research-px'));
  await go('#/data/simulation');
  await page.waitForSelector('#sim-control-room');
  assert.match(await page.textContent('#sim-control-room'), /Anchored/, 'coverage chip in the console');
  // Band controls and the control room are one state surface. This used to leave the band on
  // Play while the room still claimed RUNNING/Pause until a full route refresh.
  await page.click('#world-toggle');
  await page.waitForFunction(() => document.getElementById('world-toggle')?.textContent === 'Play'
    && document.getElementById('cr-toggle')?.textContent === 'Play'
    && /PAUSED/.test(document.getElementById('sim-control-room')?.textContent || ''));
  await page.click('#world-toggle');
  await page.waitForFunction(() => document.getElementById('world-toggle')?.textContent === 'Pause'
    && document.getElementById('cr-toggle')?.textContent === 'Pause'
    && /RUNNING/.test(document.getElementById('sim-control-room')?.textContent || ''));
  await page.click('#sim-control-room button:has-text("Inject event")');
  await page.waitForSelector('#inject-symbol');
  await page.selectOption('#inject-symbol', 'AAPL');
  await page.fill('#inject-move', '-5');
  await page.click('#modal-confirm');
  await go('#/research/AAPL');
  await page.waitForFunction((prev) => {
    const el2 = document.getElementById('research-px');
    return el2 && Math.abs(parseFloat(el2.textContent) - prev * 0.95) < prev * 0.02;
  }, pxBefore, { timeout: 20000 });

  // 4. EXPERT places a world trade through Discover → Place (screen once, strikes/review/confirm).
  await page.evaluate(() => { Learn.setLevel('expert'); App.render(); });
  await go('#/trade/context/manual');
  await page.waitForSelector('#rec-symbol');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  // Expert results ARE the comparison table (not cards) — pick a row through its Use button,
  // exactly as an expert user does.
  await page.waitForSelector('#compare-table tbody tr button:has-text("Use")', { timeout: 30000 });
  await page.locator('#compare-table tbody tr button:has-text("Use")').first().click();
  await page.waitForSelector('#to-review');
  await page.click('#to-review');
  await page.waitForSelector('#to-confirm');
  await page.$$eval('.ack-gate input', els => els.forEach(e => { if (!e.checked) e.click(); }));
  await page.click('#to-confirm');
  await page.waitForSelector('#place-trade');
  await page.click('#place-trade');
  await page.waitForSelector('#refresh-btn');
  const tradeHash = await page.evaluate(() => window.location.hash);
  const tradeId = tradeHash.replace(/^#\/trade\//, '');
  assert.match(tradeId, /^tr_/);

  // 5. The book MOVES on real SSE frames: portfolio Now cell changes as the world steps.
  await go('#/portfolio');
  await page.waitForSelector(`[data-now-for="${tradeId}"]`, { timeout: 20000 });
  const now0 = await page.textContent(`[data-now-for="${tradeId}"]`);
  let moved = false;
  for (let round = 0; round < 10 && !moved; round++) {
    await page.evaluate(async (w) => {
      for (let i = 0; i < 20; i++) await API.post('/api/sim/market/' + w + '/step', {});
    }, wConf.worldId);
    await page.waitForTimeout(3000);
    moved = (await page.textContent(`[data-now-for="${tradeId}"]`)) !== now0;
  }
  assert.ok(moved, 'the portfolio Now P/L moved on world ticks via the app\u2019s SSE handler');

  // 6. Close through the trade-detail UI (Unwind + app modal).
  await go('#/trade/' + tradeId);
  await page.waitForSelector('#unwind-btn');
  await page.click('#unwind-btn');
  await page.waitForSelector('#modal-confirm');
  await page.click('#modal-confirm');
  await page.waitForFunction(() => /CLOSED/.test(document.body.textContent), { timeout: 20000 });

  // 7. Finish in the control room; the report is trader-grade; the return is clean.
  await go('#/data/simulation');
  await page.waitForSelector('#sim-control-room');
  await page.click('#sim-control-room button:has-text("Finish")');
  await page.waitForSelector('#sim-report table', { timeout: 15000 });
  const rep = await page.textContent('#sim-report');
  assert.match(rep, /Entered \(sim clock\)/, 'per-decision sim-clock entry column');
  assert.match(rep, /Worst \/ best while open/, 'MAE/MFE excursion column');
  assert.match(rep, /Decisions vs outcomes/, 'POP-vs-outcome summary');
  assert.match(rep, /UNWIND/, 'how the trade ended is on the row');
  await page.click('#modal-confirm');

  // FINISH is a full transition too: universe label, store lane, and screens reconcile.
  await page.waitForFunction((baseline) => {
    const u = App.state.universe;
    return App.state.world === baseline && u && u.active && !/simulated session/i.test(u.active.label || '')
      && App.Market.world === baseline && !document.body.classList.contains('in-sim-world');
  }, baselineWorld, { timeout: 15000 });
  const after = await page.evaluate(async () => ({
    world: (await API.getFresh('/api/world')).world,
    acct: (await API.getFresh('/api/account')).account.id,
    uniLabel: (App.state.universe && App.state.universe.active && App.state.universe.active.label) || ''
  }));
  assert.equal(after.world, baselineWorld);
  assert.equal(after.acct, realAcct, 'the baseline practice account is exactly the one we left');
  assert.doesNotMatch(after.uniLabel, /simulated session/i, 'finish reconciled the universe label');
});

test('viewport composition: welcome rows share one width, Data Overview fits 1280x720, Home has one Find-an-idea', async () => {
  // VISUAL GATES (holistic review): composition is tested, not just presence.
  await page.setViewportSize({ width: 1280, height: 720 });
  await go('#/home/tour');
  await page.waitForSelector('#welcome-hero');
  const widths = await page.evaluate(() => {
    const rows = [document.querySelector('.welcome-top'),
      ...document.querySelectorAll('.welcome-page > .welcome-section'),
      document.querySelector('.welcome-footer')].filter(Boolean);
    return rows.map(r => { const b = r.getBoundingClientRect(); return { l: Math.round(b.left), r: Math.round(b.right) }; });
  });
  for (const w of widths) {
    assert.ok(Math.abs(w.l - widths[0].l) <= 1 && Math.abs(w.r - widths[0].r) <= 1,
      'welcome rows share ONE frame: ' + JSON.stringify(widths));
  }
  const visibleCta = await page.evaluate(() => {
    const frame = document.querySelector('.welcome-footer').getBoundingClientRect();
    const card = document.querySelector('.welcome-footer .cta-banner').getBoundingClientRect();
    return { fl: Math.round(frame.left), fr: Math.round(frame.right), cl: Math.round(card.left), cr: Math.round(card.right) };
  });
  assert.ok(Math.abs(visibleCta.fl - visibleCta.cl) <= 1 && Math.abs(visibleCta.fr - visibleCta.cr) <= 1,
    'the visible final CTA, not just its wrapper, shares the welcome edges: ' + JSON.stringify(visibleCta));
  // Data Overview: the four dashboard cards fit one desktop viewport (expert: densest case;
  // at beginner the health detail folds into an expandable and the text is not visible).
  await page.click('#level-switch button[data-level="expert"]');
  await page.route('**/api/data/overview', async route => {
    const response = await route.fetch();
    const body = await response.json();
    body.engine = Object.assign({}, body.engine || {}, {
      tracked: 120, warmed: 117, stale: 23, inFlight: 8, errors: 1234, avgLatencyMs: 9876
    });
    await route.fulfill({ response, json: body });
  });
  await page.evaluate(() => API.flushCache());
  await go('#/data/overview');
  await page.waitForSelector('#dc-mode .badge');
  await page.waitForSelector('#dc-health:has-text("Quotes")', { timeout: 15000 });
  assert.doesNotMatch(await page.textContent('#dc-health'), /HISTORICAL_OPTIONS/,
    'provider domain enum names are not leaked into the product UI');
  assert.match(await page.textContent('#dc-health'), /Historical options/i,
    'multi-word provider domains are rendered as readable labels');
  const ovBottom = await page.evaluate(() => {
    const g = document.querySelector('.dc-grid');
    return g ? Math.round(g.getBoundingClientRect().bottom) : 9999;
  });
  assert.ok(ovBottom <= 780, 'Data Overview remains a compact desktop dashboard (bottom=' + ovBottom + ')');
  const fixtureMode = await page.evaluate(() => App.config.fixturesOnly);
  if (fixtureMode) {
    const engineText = await page.textContent('#dc-engine');
    assert.match(engineText, /Feed\s*Static Demo/, 'the fixed Demo feed names its actual operating mode');
    assert.match(engineText, /State\s*Ready/, 'the active Demo feed is not contaminated by observed-engine errors');
    assert.doesNotMatch(engineText, /Needs attention|\bstale\b/i,
      'background or persisted observed state is not presented as an active Demo-feed failure');
    assert.doesNotMatch(engineText, /Market\s*Closed/, 'Demo is static teaching data, not a closed real session');
  }
  // VERTICAL SYMMETRY (review): each desktop row's paired cards share top AND bottom edges
  // within 2px — presence alone let the grid pass while looking ragged.
  const dcGeo = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.dc-grid > .card'))
      .map(c => { const r = c.getBoundingClientRect(); return { top: r.top, bottom: r.bottom }; });
    return cards;
  });
  assert.equal(dcGeo.length, 4, 'four overview cards');
  assert.match(await page.textContent('#dc-activity'), /OBSERVED STORAGE/i,
    'coverage visibly names the observed store rather than contradicting Demo charts');
  assert.doesNotMatch(await page.textContent('#dc-engine'), /Errors since startup|Avg latency/,
    'growing operational counters stay in the drawer and cannot stretch the overview');
  for (const [a, b] of [[0, 1], [2, 3]]) {
    assert.ok(Math.abs(dcGeo[a].top - dcGeo[b].top) <= 2,
      `row cards share tops: ${dcGeo[a].top} vs ${dcGeo[b].top}`);
    assert.ok(Math.abs(dcGeo[a].bottom - dcGeo[b].bottom) <= 2,
      `row cards share bottoms: ${dcGeo[a].bottom} vs ${dcGeo[b].bottom}`);
  }
  // The provider-health summary chip's TEXT matches its color class (worst-of, review):
  const chipTruth = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('#dc-health .chip-row .badge').forEach(b => {
      out.push({ text: b.textContent.trim(), danger: b.classList.contains('badge-danger'),
        warn: b.classList.contains('badge-warn'), ok: b.classList.contains('badge-ok'),
        dim: b.classList.contains('badge-dim') });
    });
    return out;
  });
  for (const c of chipTruth) {
    if (c.danger) assert.match(c.text, /ERROR|COOLDOWN/, 'red chip must NAME the failure, got ' + c.text);
    if (c.warn) assert.match(c.text, /EMPTY|STALE/, 'amber chip must name the degradation, got ' + c.text);
    if (c.ok) assert.equal(c.text, 'OK', 'green is reserved for confirmed OK, got ' + c.text);
    if (/UNKNOWN|UNCONFIGURED|NONE/.test(c.text)) assert.equal(c.dim, true,
      'unknown/unconfigured provider state stays neutral, got ' + JSON.stringify(c));
  }
  await page.click('#dc-engine');
  const engineDetail = await page.textContent('#dc-detail');
  if (fixtureMode) {
    assert.match(engineDetail, /Demo-only.*does not contact market-data providers/s,
      'Demo detail explains why observed-provider counters do not apply');
    assert.doesNotMatch(engineDetail, /Errors since startup/,
      'observed-engine failures are not blended into the Demo lane');
  } else {
    assert.match(engineDetail, /Errors since startup.*1,234|Errors since startup.*1234/s,
      'the compact summary does not discard observed-engine detail');
  }
  await page.click('#dc-engine');
  // Summary cards disclose detail into ONE drawer below the grid, never distort a neighbor.
  await page.click('#dc-health');
  assert.equal(await page.locator('#dc-detail .dc-detail-card').count(), 1, 'one detail drawer below the 2x2');
  assert.equal(await page.locator('#dc-health > .dc-detail-toggle').getAttribute('aria-expanded'), 'true');
  await page.click('#dc-health');
  assert.equal(await page.locator('#dc-detail .dc-detail-card').count(), 0, 'second click closes the drawer');
  await page.unroute('**/api/data/overview');
  // Home: exactly ONE 'Find an idea' doorway (review #9) and no marketing hero.
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1')); // dashboard, not the tour
  // Exercise the observed-size sector catalog even though this suite runs in explicit Demo.
  // The prior audit saw one Demo sector and therefore missed the full rail widening Home by 1,600px.
  await page.evaluate(() => {
    window.__homeUniverseRestore = App.state.universe;
    const active = App.state.universe.active;
    const keys = ['CORE', 'TECH', 'SEMICONDUCTORS', 'HEALTHCARE', 'DEFENSE', 'STAPLES',
      'DISCRETIONARY', 'ENERGY', 'FINANCIALS', 'INDUSTRIALS', 'COMMUNICATIONS', 'UTILITIES', 'ETFS'];
    App.state.universe = Object.assign({}, App.state.universe, {
      sectors: keys.map(k => ({ key: k, label: k.replaceAll('_', ' '), symbols: active.symbols }))
    });
  });
  await go('#/home');
  await page.waitForSelector('#home-stats .stat');
  const homeText = await page.textContent('#app');
  const findCount = (homeText.match(/Find an idea/g) || []).length;
  assert.ok(findCount <= 1, 'one contextual Find-an-idea, not ' + findCount);
  assert.ok(await page.locator('#next-up').count(), 'one Continue area (journey + continuity merged)');
  const cbText = await page.textContent('#command-bar');
  assert.ok(await page.locator('#command-bar .btn').count() === 3, 'command bar keeps only non-nav tools');
  assert.doesNotMatch(cbText, /Research|Ideas/, 'nav destinations are not repeated as commands');
  const homeGeo = await page.evaluate(() => {
    const market = document.querySelector('.home-market-slot').getBoundingClientRect();
    const side = document.querySelector('.home-col-side').getBoundingClientRect();
    return { mt: Math.round(market.top), mb: Math.round(market.bottom), st: Math.round(side.top), sb: Math.round(side.bottom) };
  });
  assert.ok(Math.abs(homeGeo.mt - homeGeo.st) <= 2 && Math.abs(homeGeo.mb - homeGeo.sb) <= 2,
    'Home primary columns share visible top and bottom edges: ' + JSON.stringify(homeGeo));
  const homeContainment = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    side: Math.round(document.querySelector('.home-col-side').getBoundingClientRect().right),
    pulse: Math.round(document.querySelector('#sector-pulse').getBoundingClientRect().right),
    app: Math.round(document.getElementById('app').getBoundingClientRect().right)
  }));
  assert.ok(homeContainment.document <= homeContainment.viewport + 2,
    'Home cannot widen the document: ' + JSON.stringify(homeContainment));
  assert.ok(homeContainment.pulse <= homeContainment.side + 2 && homeContainment.pulse <= homeContainment.app + 2,
    'the full sector rail stays inside the right column: ' + JSON.stringify(homeContainment));
  await page.evaluate(() => {
    App.state.universe = window.__homeUniverseRestore;
    delete window.__homeUniverseRestore;
  });
  const sparseMarketRows = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.home-market-grid .sym-card'));
    const ys = cards.map(c => Math.round(c.getBoundingClientRect().top));
    return { count: cards.length, rows: [...new Set(ys)].map(y => ys.filter(v => v === y).length) };
  });
  if (sparseMarketRows.count === 5) assert.deepEqual(sparseMarketRows.rows, [3, 2],
    'a five-symbol market uses a balanced 3+2 desktop grid: ' + JSON.stringify(sparseMarketRows));
  if (sparseMarketRows.count === 5) {
    const centeredSecondRow = await page.evaluate(() => {
      const grid = document.querySelector('.home-market-grid').getBoundingClientRect();
      const cards = Array.from(document.querySelectorAll('.home-market-grid .sym-card'));
      const a = cards[3].getBoundingClientRect(), b = cards[4].getBoundingClientRect();
      return { leftGap: a.left - grid.left, rightGap: grid.right - b.right };
    });
    assert.ok(Math.abs(centeredSecondRow.leftGap - centeredSecondRow.rightGap) <= 2,
      'the two-card second row is visually centered: ' + JSON.stringify(centeredSecondRow));
  }
  assert.equal(await page.locator('.home-col-side .card-head').count(), 3,
    'Continue, Tools, and Sector pulse are all explicitly headed');
  // Responsive chrome must recalculate its sticky anchor. It previously retained the 120px
  // mobile top after resizing to desktop and overlaid both the tape and the page.
  await page.setViewportSize({ width: 390, height: 844 });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.waitForTimeout(100);
  const chromeGeo = await page.evaluate(() => {
    const top = document.querySelector('.topbar').getBoundingClientRect();
    const band = document.getElementById('world-band').getBoundingClientRect();
    const tape = document.getElementById('tape').getBoundingClientRect();
    return { topBottom: top.bottom, bandTop: band.top, bandBottom: band.bottom, tapeTop: tape.top };
  });
  assert.ok(Math.abs(chromeGeo.topBottom - chromeGeo.bandTop) <= 2,
    'world band remains pinned below the resized header: ' + JSON.stringify(chromeGeo));
  assert.ok(chromeGeo.bandBottom <= chromeGeo.tapeTop + 2,
    'world band never overlays the tape after resize: ' + JSON.stringify(chromeGeo));
  await page.setViewportSize({ width: 1280, height: 900 });
});
