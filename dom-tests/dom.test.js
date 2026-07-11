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
  await page.evaluate(h => { window.location.hash = h; }, hash);
  await page.waitForSelector('#app[data-ready="true"]');
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
  page.on('response', async r => {
    if (r.url().includes('/api/recommend')) {
      console.log('REC-DEBUG:', r.status(), (await r.text().catch(() => '')).slice(0, 400));
    }
  });

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
  assert.ok(await page.locator('.home-market-grid .spark-slot .evidence-badge').count() >= 4,
    'Home names history provenance separately from quote freshness');
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
  // Legacy #/welcome redirects into the adaptive Home's tour view
  await go('#/welcome');
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
  assert.match(await page.textContent('#recent-symbols'), /Recent:/);
  assert.ok(await page.locator('#recent-symbols .sym-chip:has-text("AAPL")').count(), 'AAPL just researched');
  // News NEVER silently vanishes — card renders with items or an honest empty state
  await page.waitForSelector('#news-card');
  assert.match(await page.textContent('#news-card'), /News & filings/);
  assert.ok((await page.locator('#news-card .status-item').count()) >= 1, 'fixture headlines render');
  await page.waitForSelector('.quote-hero .badge:has-text("DEMO DATA")');
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
  await page.waitForSelector('text=/no listed options/i'); // hero/actions now fill detached
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
  await page.waitForSelector('#tv-stages .pill[data-mode="futures"]');
  await page.click('#tv-stages .pill[data-mode="futures"]');
  await page.waitForSelector('#tv-futures', { timeout: 15000 });
}

test('scenario studio: beginner story cards → fan of futures → strategy verdict in plain dollars', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { App.state.scenarioForm = null; App.state.verifyForm = null; });
  await go('#/research/AAPL');
  await openFutures();
  // The thesis workbench: story cards with shape sketches, plain horizon/wildness chips.
  await page.waitForSelector('#whatif-card #sc-shapes .sc-card');
  assert.ok((await page.locator('#whatif-card .sc-card').count()) >= 6, 'story cards');
  assert.ok((await page.locator('#whatif-card .sc-sketch svg').count()) >= 6, 'shape sketches');
  assert.match(await page.textContent('#whatif-card'), /Drops, then recovers/);
  assert.match(await page.textContent('#sc-mag-note'), /±\d+%/); // live magnitude preview
  await page.click('#whatif-card .sc-card[data-shape="SELLOFF_REBOUND"]');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .fan-chart svg', { timeout: 20000 });
  assert.match(await page.textContent('#whatif-out'), /8 in 10 end between/); // plain-language fan summary
  assert.match(await page.textContent('#whatif-out'), /never a forecast/i);   // honesty note

  // Handoff: "Test a strategy under this" opens Verify in scenario mode.
  await page.click('#whatif-verify');
  await page.waitForSelector('#bt-scenario-card', { timeout: 15000 });
  assert.ok(await page.locator('#bt-mode .pill.active[data-mode="scenario"]').count(), 'Verify opened in scenario mode');
  // The FULL strategy catalog with payoff-shape sketches + a visible symbol picker.
  assert.ok((await page.locator('#sc-pos .sc-card').count()) >= 16, 'full strategy catalog, not a subset');
  // ANTI-DRIFT: the frontend catalog must name only real backend StrategyFamily values —
  // a hand-maintained duplicate list is exactly how the six-strategy subset happened.
  const familyCheck = await page.evaluate(async () => {
    const fams = (await (await fetch('/api/strategies')).json()).families;
    return Scenario.CATALOG.map(c => c.key).filter(k => !fams.includes(k));
  });
  assert.deepEqual(familyCheck, [], 'catalog keys unknown to the backend: ' + familyCheck.join(','));
  // ...and the Builder's template catalog: every template maps to a REGISTRY family (or CUSTOM),
  // and the named coverage the product promises (PMCC, synthetics, calendars) actually exists.
  const builderCheck = await page.evaluate(async () => {
    const fams = (await (await fetch('/api/strategies')).json()).families.concat(['CUSTOM']);
    const tpls = Builder.TEMPLATES || [];
    return {
      unknown: tpls.map(t => t.family).filter(f => !fams.includes(f)),
      keys: tpls.map(t => t.key)
    };
  });
  assert.deepEqual(builderCheck.unknown, [], 'builder template families unknown to the backend: ' + builderCheck.unknown.join(','));
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

test('workspace continuity: forms, symbol, and route survive a full reload', async () => {
  // Work: pick a goal + symbol in Ideas (one-stock mode), then wander off to Research.
  await go('#/recommend/manual');
  await page.waitForSelector('#rec-symbol');
  await page.fill('#rec-symbol', 'QQQ');
  await page.evaluate(() => { App.state.discoverForm.symbol = 'QQQ'; App.state.lastRecommendSymbol = 'QQQ'; });
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
    sym: App.state.lastRecommendSymbol,
    form: App.state.discoverForm && App.state.discoverForm.symbol
  }));
  assert.equal(restored.sym, 'QQQ', 'working symbol restored');
  assert.equal(restored.form, 'QQQ', 'draft form restored');

  // An explicit hash always beats the saved route (bookmarks/links stay honest).
  await page.goto(BASE + '/#/portfolio');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'portfolio');

  // Home offers one-tap re-entry into the working context.
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1')); // dashboard, not the tour
  await go('#/home');
  await page.waitForSelector('#continue-row .sym-chip');
  assert.match(await page.textContent('#continue-row'), /Resume: QQQ analysis/);
  await page.click('#continue-row .sym-chip[data-continue="research"]');
  await page.waitForSelector('.quote-hero');
  assert.equal(await page.evaluate(() => window.location.hash), '#/research/QQQ');
});

test('world transition: authoritative PUT bootstrap recovers a failed SSE-hint hydration', async () => {
  await go('#/home');
  const recovered = await page.evaluate(async () => {
    const target = App.state.world;
    const bootstrap = App.state.universe;
    const nextRevision = Number(App.state.worldRevision || 0) + 1;
    let rejectHint;
    const failedHint = new Promise((resolve, reject) => { rejectHint = reject; })
      .finally(() => { App._transitionTarget = null; App._transitionP = null; });
    App._transitionTarget = target;
    App._transitionP = failedHint;
    const fromPut = App.transitionWorld(target, bootstrap, nextRevision);
    rejectHint(new Error('synthetic hint hydration failure'));
    await fromPut;
    return {
      world: App.state.world,
      marketWorld: App.Market.world,
      status: App.state.transitionStatus,
      symbols: (App.state.universe.active.symbols || []).length
    };
  });
  assert.equal(recovered.status, 'committed');
  assert.equal(recovered.marketWorld, recovered.world);
  assert.ok(recovered.symbols > 0, 'the authoritative bootstrap hydrated the lane');
});

test('working view follows: idea bar carries the thesis; scenario studio opens on it', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  // State the view in Ideas: QQQ, bearish, ~1 month.
  await go('#/recommend/manual');
  await page.waitForSelector('#rec-symbol');
  await page.fill('#rec-symbol', 'QQQ');
  await page.evaluate(() => {
    App.state.discoverForm.symbol = 'QQQ';
    App.state.discoverForm.thesis = 'bearish';
    App.state.discoverForm.horizon = 'month';
    App.state.lastRecommendSymbol = 'QQQ';
    App.state.ticket = null; App.state.builderForm = null; App.state.scenarioForm = null;
  });
  // The Trade idea bar shows the working VIEW even with no working idea yet.
  await go('#/trade/verify');
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
  await page.evaluate(() => { App.state.scenarioForm = null; App.state.discoverForm.thesis = 'bullish'; });
});

test('every scenario story runs at both levels (the Big-news-shock crash class)', async () => {
  // The magVolFor crash escaped a green matrix because tests only clicked ONE story.
  // Run EVERY beginner story card end-to-end, then every expert shape via the select.
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { App.state.scenarioForm = null; App.state.verifyForm = { mode: 'scenario' }; App.state.lastRecommendSymbol = 'AAPL'; });
  await go('#/trade/verify');
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
  await go('#/trade/verify');
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
    App.state.lastRecommendSymbol = 'AAPL';
    App.navigate('#/trade/verify'); // same hash still means an explicit rerender
  });
  await page.waitForSelector('#app[data-route="trade"][data-ready="true"]');
  await page.waitForSelector('#sc-compare-all');
  // Count network calls: the comparison must be ONE POST, not 18.
  const calls = await page.evaluate(() => new Promise((resolve, reject) => {
    let n = 0;
    const orig = window.fetch;
    window.fetch = function (u, o) { if (String(u).includes('/api/sim/')) n++; return orig.apply(window, arguments); };
    document.getElementById('sc-compare-all').click();
    const t0 = Date.now();
    (function poll() {
      if (document.querySelector('#sc-verify-out table')) { window.fetch = orig; resolve(n); }
      else if (Date.now() - t0 > 60000) { window.fetch = orig; reject(new Error('no table')); }
      else setTimeout(poll, 300);
    })();
  }));
  assert.ok(calls <= 2, 'batched: ' + calls + ' sim calls (was 18 sequential)');
  const txt = await page.textContent('#sc-verify-out');
  assert.match(txt, /per dollar of realistic downside|RoR/i);
  assert.match(txt, /undefined risk is blocked by design/i); // catalog completeness disclosed
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { if (App.state.verifyForm) App.state.verifyForm.mode = 'history'; });
});

test('research symbol page: ONE Test-your-view section — thesis-driven, symbol-inherited, keyed results', async () => {
  await go('#/research/AAPL');
  await page.waitForSelector('#test-your-view');
  // The stage selection PERSISTS by design — pick Past evidence explicitly for this walk.
  await page.click('#tv-stages .pill[data-mode="past"]');
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
  assert.equal(await page.locator('#tv-stages .pill').count(), 2, 'two connected stages');
  await page.waitForSelector('#what-has-happened');
  // The thesis question line names the CONDITION — "did this happen before" never leaves "this" undefined.
  await page.waitForSelector('#tv-question-line');
  // Run the study; the conclusion is decision-useful (evidence strength + confidence guidance + handoff).
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  const outText = await page.textContent('#study-results');
  assert.match(outText, /Evidence/, 'evidence-strength label present');
  assert.match(outText, /raise or lower your confidence/, 'confidence guidance, never a prediction');
  // The handoff exists when enough analogs matched.
  const handoff = await page.locator('#tv-test-analogs').count();
  if (handoff) assert.match(await page.textContent('#tv-test-analogs'), /DEMO-data occurrences/); // fixture suite: never 'real'
  // KEYED RESULTS: switching to QQQ must not display AAPL's study.
  await go('#/research/QQQ');
  await page.waitForSelector('#test-your-view');
  await page.click('#tv-stages .pill[data-mode="past"]');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  assert.equal(await page.locator('#study-results .alert').count(), 0,
    'an AAPL result may never appear on a QQQ page (result identity is keyed)');
  // Possible futures is the second stage of the SAME section.
  await page.click('#tv-stages .pill[data-mode="futures"]');
  await page.waitForSelector('#tv-futures #whatif-card, #tv-futures #sc-shapes', { timeout: 15000 });
});

test('form geometry: controls in one grid row share a vertical origin (labels never push them)', async () => {
  await page.click('#level-switch button[data-level="expert"]');
  await page.evaluate(() => { App.state.scenarioForm = null; App.state.verifyForm = { mode: 'scenario' }; });
  await go('#/trade/verify');
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
  await go('#/recommend/scout'); // legacy alias — forces scan mode
  await page.waitForSelector('#auto-target'); // scan-scope fields visible in scan mode
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
  // headline evidence is expandable
  assert.ok(await page.locator('.pick-card details').first().isVisible());
});

test('recommendations render candidates and blocked examples', async () => {
  await go('#/recommend/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('.candidate');
  const cards = await page.locator('.candidate').count();
  assert.ok(cards >= 1, 'at least one candidate card');
  const appText = await page.textContent('#app');
  assert.match(appText, /BLOCKED/);
  assert.match(appText, /undefined risk/i);
  assert.match(appText, /Most you can lose|Max loss/); // beginner vs expert wording
  assert.match(appText, /not financial advice/i);
});

let tradeUrlHash = null;

test('discover-to-place: screening happens ONCE, in Discover; Place is strikes/review/confirm', async () => {
  // Place without an idea = an honest empty state, never a duplicate wizard
  await page.evaluate(() => { App.state.ticket = null; });
  await go('#/trade/place');
  assert.match(await page.textContent('#app'), /Nothing to place yet/);
  // The four Trade sections carry PLAIN names — no invented jargon
  await go('#/trade/discover/manual');
  assert.equal(await page.textContent('#wb-discover'), 'Ideas');
  assert.equal(await page.textContent('#wb-shape'), 'Builder');
  assert.equal(await page.textContent('#wb-verify'), 'Backtest');
  assert.equal(await page.textContent('#wb-place'), 'Place');
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
  // Button label differs by level (beginner: Practice this trade / expert: Use in trade ticket).
  // Prefer a CREDIT structure: downstream ledger tests pin RESERVE_HOLD/RELEASE rows, which only
  // credit trades produce (candidates are decision-ranked, so the first card's family can change).
  const creditCard = page.locator('#rec-results .candidate[data-strategy^="CREDIT"]');
  const pickCard = (await creditCard.count()) ? creditCard.first() : page.locator('#rec-results .candidate').first();
  await pickCard.locator('button:has-text("Practice this trade"), button:has-text("Use in trade ticket")').first().click();
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
  assert.match(review, /Max loss/);
  assert.match(review, /Buying power after/);
  assert.match(review, /Safety check/);
  assert.match(review, /Worst case is known and capped/);
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
  await go('#/account'); // legacy URL -> Portfolio's Account section
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
  for (const hash of ['#/trade/shape', '#/trade/verify', '#/data/simulation', '#/portfolio/account']) {
    await go(hash);
    await page.waitForTimeout(250);
  }
  // The sim workbench must register its own jargon (scenario/seed/speed/beta at expert).
  const preTerms = await page.evaluate(() => (window.__usedInfoTerms || []).slice());
  assert.ok(preTerms.includes('scenario'), 'sim workbench wires info(scenario)');
  assert.ok(preTerms.includes('speed'), 'sim workbench wires info(speed)');
  await go('#/home');
  await go('#/trade/discover/manual');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('#rec-results .candidate', { timeout: 30000 });
  // With the full catalog competing (risk no longer gates complexity), a CALENDAR can rank
  // first — its review honestly has no probability map. Pick a single-expiration structure.
  const single = page.locator('#rec-results .candidate:not([data-strategy*="CALENDAR"]):not([data-strategy*="DIAGONAL"])');
  await single.first().locator('button:has-text("Practice this trade"), button:has-text("Use in trade ticket")').first().click();
  await page.waitForSelector('#to-review');
  await page.click('#to-review');
  await page.waitForSelector('#to-confirm');
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#proposed-net', { timeout: 15000 }); // review re-rendered with expert controls
  // The assembled judgment: verdict banner + full probability map + execution ladder + quote age.
  await page.waitForSelector('#verdict-panel');
  const panel = await page.textContent('#verdict-panel');
  assert.match(panel, /P\(any profit\)|Chance of making anything/);
  assert.match(panel, /P\(max loss\)|Chance of the WORST case/);
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
  for (const hash of ['#/trade/shape', '#/trade/verify', '#/data/simulation', '#/portfolio/account']) {
    await go(hash);
    await page.waitForTimeout(250);
  }
  // The sim workbench must register its own jargon (scenario/seed/speed/beta at expert).
  const preTerms = await page.evaluate(() => (window.__usedInfoTerms || []).slice());
  assert.ok(preTerms.includes('scenario'), 'sim workbench wires info(scenario)');
  assert.ok(preTerms.includes('speed'), 'sim workbench wires info(speed)');
  await go('#/home');
  await go('#/trade/discover/manual');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('#rec-results .candidate', { timeout: 30000 });
  // Same single-expiration pick as the verdict test: a calendar's review has no probability map.
  const singleExp = page.locator('#rec-results .candidate:not([data-strategy*="CALENDAR"]):not([data-strategy*="DIAGONAL"])');
  await singleExp.first().locator('button:has-text("Practice this trade"), button:has-text("Use in trade ticket")').first().click();
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
  assert.equal(await page.isVisible('#info-pop .info-detail'), false, 'detail starts collapsed');
  await page.click('#info-pop .info-expand');
  const begDetail = await page.textContent('#info-pop .info-detail');
  assert.ok(begDetail.length > 40, 'beginner detail present');
  // Escape closes AND returns focus to the trigger (keyboard a11y).
  await page.keyboard.press('Escape');
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
  await go('#/backtest');
  // The portfolio engine + full family menu are Expert-only; pin this test there.
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#bt-engine');
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
  await go('#/recommend/manual');
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await go('#/recommend/manual');
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
    const c = page.locator('.compare-detail .candidate:visible:has(button:has-text("Backtest this"))').first();
    if (await c.count()) { card = c; break; }
    await rows.nth(i).click(); // collapse before trying the next row
  }
  assert.ok(card, 'candidates offer Backtest this');
  const strategy = await card.getAttribute('data-strategy');
  await card.locator('button:has-text("Backtest this")').click();
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
  await page.waitForSelector('#dc-health:has-text("QUOTES")', { timeout: 15000 });
  await page.click('#dc-refresh-now');
  // Inactive workspaces are NOT mounted from Overview (only-active-tab loading).
  assert.equal(await page.locator('#dc-sources').count(), 0, 'sources not mounted on overview');
  assert.equal(await page.locator('#dc-reset').count(), 0, 'reset not mounted on overview');

  // Sources & jobs tab
  await page.click('#data-tabs [data-tab="sources"]');
  await page.waitForSelector('#dc-sources .dc-source');
  const sources = await page.textContent('#dc-sources');
  assert.match(sources, /Yahoo Finance/);
  assert.match(sources, /PERSONAL/);
  assert.match(sources, /licensed · internal-use/); // the owned-CSV path

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
  await page.click('#level-switch button[data-level="expert"]'); // restore for later tests
});

test('pro depth: comparison table, custom builder, position greeks', async () => {
  // Comparison table on Ideas at Pro
  await go('#/recommend/manual');
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForSelector('#compare-table');
  const rows = await page.locator('#compare-table tbody tr.clickable').count();
  assert.ok(rows >= 2, 'multiple candidates compared');
  await page.click('#compare-table thead th:has-text("Max loss")'); // sort
  assert.match(await page.textContent('#compare-table thead'), /Max loss [↓↑]/);
  await page.click('#compare-table tbody tr.clickable'); // expand detail row
  await page.waitForSelector('.compare-detail .candidate');

  // Expert terminal: build a call spread by hand, place it through the standard review
  await page.evaluate(() => { App.state.builderForm = null; App.state.ticket = null; });
  await go('#/ticket/builder');
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
  await go('#/recommend/manual');
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
  await go('#/recommend/manual');
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
  await go('#/recommend/scout');
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
  // The underlying's chart & news sit behind a quiet expandable, never in the way
  await page.waitForSelector('#symbol-context .xp-head');
  assert.match(await page.textContent('#symbol-context .xp-head'), /QQQ/);
  await page.click('#symbol-context .xp-head');
  await page.waitForSelector('#symbol-context .symbol-panel .chart-wrap svg.chart', { timeout: 15000 });
  assert.ok(await page.locator('#symbol-context .symbol-panel .range-pills .pill').count() >= 5,
    'same range pills as Research');
  // The panel's ONE action is the deliberate exit to the destination page — no duplicate
  // of the host form's own strategy workflow (interaction contract #3).
  assert.ok(await page.locator('#symbol-context button:has-text("Open full analysis")').count(),
    'panel offers the full-analysis exit');
  assert.equal(await page.locator('#symbol-context button:has-text("Find strategies")').count(), 0,
    'no duplicate strategy command inside the strategy form');
  // The working symbol FOLLOWS across stages (the QQQ->AAPL amnesia bug)
  await go('#/trade/verify');
  assert.equal(await page.inputValue('#bt-symbol'), 'QQQ', 'Backtest picks up the ticker you just typed');
  assert.equal(await page.textContent('#bt-mode .pill[data-mode="history"]'), 'Historical replay',
    'the reusable history engine does not overclaim Observed data in Demo/Scenario lanes');
  assert.match(await page.textContent('#bt-mode-note'), /fabricated Demo history/);
  await go('#/recommend/scout');
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

test('interactive charts, range pills, universe picker, and the tape', async () => {
  // Research chart: pills switch windows; crosshair reads values on hover
  await go('#/research/AAPL');
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
  await go('#/recommend/scout');
  await page.waitForSelector('#universe-sector .sector-chip');
  const pickedSector = await page.locator('#universe-sector .sector-chip').first().getAttribute('data-sector');
  await page.click('#universe-sector .sector-chip[data-sector="' + pickedSector + '"]');
  await page.waitForSelector('#app[data-ready="true"]');
  const uni = await page.evaluate(() => fetch('/api/universe').then(r => r.json()));
  assert.equal(uni.active.sectorKey, pickedSector);
  // Datalist feeds symbol inputs everywhere
  assert.ok(await page.evaluate(() => document.querySelectorAll('#universe-symbols option').length) >= 4);
  // The ticker is CONTEXT now: hidden on non-market screens like the scout
  assert.ok(await page.locator('#tape.tape-offroute').count(), 'tape hidden on the scout');

  // Sector explorer: chips per sector, live tiles, scout handoff
  await go('#/research');
  assert.ok(await page.locator('#tape.tape-offroute').count() === 0, 'tape visible on research');
  await page.waitForSelector('#tape .tape-item');
  // Tape is SEAMLESS: two identical halves, each at least as wide as the scroll viewport
  const tape = await page.evaluate(() => {
    const strip = document.getElementById('tape-strip');
    const scroll = document.querySelector('.tape-scroll');
    const seqs = strip.querySelectorAll('.tape-seq');
    return { seqs: seqs.length, stripW: strip.scrollWidth, viewW: scroll.clientWidth };
  });
  assert.ok(tape.seqs >= 2 && tape.seqs % 2 === 0, 'even number of sequences: ' + tape.seqs);
  assert.ok(tape.stripW >= tape.viewW * 2 - 5, 'each half covers the viewport (no gap at wrap)');
  await page.waitForSelector('#sector-explorer .sector-chip');
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
  await page.click('#tape .tape-item >> nth=4');
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  // Explicit Demo owns this universe already; no hidden sector mutation is needed.
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
  // ACQUIRE at Learning: the ladder reads as sentences with a recommended rung
  await go('#/recommend/manual');
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
  assert.ok(await page.locator('#ladder-view .ladder-row').count() >= 4, 'sentence rungs at Learning');
  assert.ok(await page.locator('#ladder-view .ladder-row.recommended').count() === 1, 'one recommended rung');

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
  await go('#/recommend/manual');
  await page.click('#intent-choices .choice[data-intent="INCOME"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForSelector('#income-board');
  const board = await page.textContent('#income-board');
  assert.match(board, /Free cash/);
  assert.match(board, /Shares to rent out/);

  // Research symbol page: per-goal action cards with live rung numbers
  await go('#/research/AAPL');
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
  await go('#/recommend/manual');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForSelector('.candidate');
  const learningCard = await page.textContent('.candidate');
  assert.match(learningCard, /Most you can lose/);
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
  await go('#/backtest');
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
  await go('#/recommend/manual');
  await page.waitForSelector('#intent-choices.intent-compact');
  assert.ok(await page.locator('#rec-f-yield').isVisible(), 'all five filters inline at Pro');
  assert.equal(await page.locator('#rec-filters .xp-head').count(), 0, 'no expandable around Pro filters');
  const filterText = await page.textContent('#rec-filters');
  assert.match(filterText, /Chance of profit/);
  assert.match(filterText, /Worst case/);
  assert.doesNotMatch(filterText, /Min POP|Max assign %|% of account/i); // the old jargon is gone
  await go('#/backtest');
  assert.ok(await page.locator('#bt-strategy option[value="IRON_BUTTERFLY"]').count(), 'full strategy menu at Expert');
  // The identical menu at both levels (one catalog): compare option sets
  const expertOpts = await page.evaluate(() => Array.from(document.querySelectorAll('#bt-strategy option')).map(o => o.value).join(','));
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await go('#/backtest');
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
  await page.evaluate(() => { App.state.builderForm = null; App.state.ticket = null; });
  await go('#/ticket/builder');
  await page.waitForSelector('#bw-goals .choice');
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
  assert.match(await page.textContent('#bw-impact'), /Worst case/);
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
  assert.match(finalText, /Most you can lose/);
  assert.match(finalText, /Assignment odds/);
  assert.ok(await page.locator('#bw-panel .chart-wrap').count(), 'payoff chart on the final step');

  // --- Learn by touching #1: the wizard steps are LIVE — go back, come forward ---
  await page.click('.wizard-steps button[data-step="1"]');
  await page.waitForSelector('#bw-goals .choice');
  await page.click('.wizard-steps button[data-step="4"]');
  await page.waitForSelector('#bw-final');
  await page.waitForFunction(() =>
    /Most you can lose/.test((document.getElementById('bw-panel') || {}).textContent || ''), { timeout: 20000 });

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
    /Most you can lose/.test((document.getElementById('bw-panel') || {}).textContent || ''), { timeout: 20000 });

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
    /Most you can lose/.test((document.getElementById('bw-panel') || {}).textContent || ''), { timeout: 20000 });
  // Your limits: judged live against the priced position
  await page.fill('#bl-maxLoss', '50');
  await page.locator('#bl-maxLoss').blur();
  await page.waitForSelector('#builder-limit-chips', { timeout: 20000 });
  assert.match(await page.textContent('#builder-limit-chips'), /Max loss/);
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
  await page.evaluate(() => { App.state.builderForm = null; });
  await go('#/ticket/builder');
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
  await go('#/ticket/builder');
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

test('builder recovers from a failing symbol and follows the working symbol', async () => {
  // The reported trap: builder stuck on a symbol that fails to load, retrying forever
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/home'); // same-hash navigation renders nothing — leave the route first
  await page.evaluate(() => { App.state.builderForm = null; App.state.ticket = null; App.state.lastRecommendSymbol = 'ZZZZQQ'; });
  await go('#/ticket/builder');
  await page.waitForSelector('#builder-load-error');
  assert.match(await page.textContent('#builder-load-error'), /Could not load ZZZZQQ/);
  assert.ok(await page.locator('#builder-retry').isVisible(), 'retry is right there');
  // One tap on a universe chip recovers — no dead end, no manual URL surgery
  await page.click('#builder-load-error .sym-chip:has-text("AAPL")');
  await page.waitForSelector('#builder', { timeout: 20000 });
  assert.equal(await page.locator('#builder-load-error').count(), 0, 'error state cleared');
  // An EMPTY builder follows the working symbol picked elsewhere
  await page.evaluate(() => { App.state.builderForm = null; App.state.lastRecommendSymbol = 'TSLA'; App.render(); });
  await page.waitForFunction(() => App.state.builderForm && App.state.builderForm.symbol === 'TSLA', { timeout: 20000 });
  await page.evaluate(() => { App.state.builderForm = null; App.state.lastRecommendSymbol = 'AAPL'; });
});

test('pipeline streamline: candidates open in the builder; Ideas links the full catalog', async () => {
  // Expert: a screened candidate's exact legs load straight into the builder terminal
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForSelector('#app[data-ready="true"]');
  // Filters persist by design now — clear them so a prior test's limits don't reject everything.
  await page.evaluate(() => { App.state.builderForm = null; App.state.ticket = null; App.state.filterState = {}; App.state.discoverForm = null; });
  await go('#/recommend/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForSelector('#compare-table tbody tr.clickable', { timeout: 30000 });
  await page.click('#compare-table tbody tr.clickable');
  await page.waitForSelector('.compare-detail .candidate');
  await page.locator('.compare-detail .candidate button:has-text("Open in builder")').first().click();
  await page.waitForSelector('#builder-legs .leg-row', { timeout: 30000 }); // Shape-stage landmark
  assert.ok(await page.locator('#builder-legs .leg-row').count() >= 1, 'candidate legs loaded into the terminal');
  await page.waitForFunction(() =>
    /ALLOW|WARN|BLOCKED/.test((document.getElementById('builder-panel') || {}).textContent || ''), { timeout: 20000 });

  // Beginner: "All strategies" on Ideas lands on the browsable shape-card catalog
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
  await go('#/recommend/manual');
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

test('decision page: recommendations-as-a-competition — the pick, evidence, scenarios, plan, handoff', async () => {
  await page.evaluate(() => {
    Learn.setLevel('expert');
    App.state.discoverForm = { symbol: 'AAPL', thesis: 'bullish', horizon: 'month' };
  });
  await go('#/decision/AAPL');

  // The pick, with its honesty badges and score.
  await page.waitForSelector('.decision-pick');
  assert.ok(await page.locator('.decision-pick .pick-badge').first().isVisible(), 'THE PICK badge');
  const evBadge = (await page.locator('.decision-pick .row-gap .badge').first().innerText()).trim();
  assert.match(evBadge, /Demo data|Observed|Modeled|Unknown/, 'evidence badge is labeled honestly');
  assert.ok(await page.locator('.decision-pick .score-wrap').first().isVisible(), 'risk-adjusted score meter');

  // Real risk scenarios + the honest capital pair + the co-equal management plan.
  assert.ok((await page.locator('.decision-pick .scenario-strip .scenario-cell').count()) >= 3, 'payoff scenario strip');
  assert.ok((await page.locator('.decision-pick:has-text("Buying power used")').count()) > 0, 'incremental capital');
  assert.ok((await page.locator('.decision-pick:has-text("Full exposure")').count()) > 0, 'economic capital');
  assert.ok((await page.locator('.decision-pick:has-text("The plan after you enter")').count()) > 0, 'management plan');

  // When there is a field of alternatives, Expert ranks them in a comparison table.
  if ((await page.locator('.section-h').count()) > 0) {
    assert.ok((await page.locator('#decision-table').count()) > 0, 'expert comparison table for the field');
  }

  // Handing the winner off to the Place stage carries the working idea.
  await page.click('#decision-use');
  await page.waitForFunction(() => location.hash.indexOf('/trade/place') >= 0);
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
    await go('#/decision/AAPL');
    await page.waitForSelector('.decision-pick');
    assert.equal(evalPosts, 1, 'first render evaluates once');
    // Flip the level: the page re-renders but the inputs are unchanged — must reuse the cache.
    await page.click('#level-switch button[data-level="beginner"]');
    await page.waitForSelector('.decision-pick');
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

  // Portfolio sizing lives at the bottom of the Decision competition.
  await page.evaluate(() => {
    App.state.discoverForm = { symbol: 'AAPL', goal: 'DIRECTIONAL', thesis: 'neutral', horizon: 'month' };
    App.state.filterState = {};
  });
  await go('#/decision');
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
  await page.waitForSelector('#tv-stages .pill[data-mode="past"]');
  await page.click('#tv-stages .pill[data-mode="past"]');
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
      await API.post('/api/evaluate', { symbol: 'AAPL', thesis: 'bullish', horizon: 'month', riskMode: 'balanced' });
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
    await go('#/recommend/manual');
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
  await go('#/trade/shape');
  await page.waitForSelector('#bw-goals .choice, #builder-catalog', { timeout: 15000 });
  // Open the full catalog (browse-all) and find the two synthetics.
  const browse = page.locator('#bw-browse, button:has-text("Browse all structures")').first();
  if (await browse.count()) { await browse.click(); }
  await page.waitForSelector('.tpl[data-tpl="SYNTHETIC_SHORT"]', { timeout: 15000 });
  assert.ok(await page.locator('.tpl[data-tpl="SYNTHETIC_SHORT"] .badge:has-text("BLOCKED")').count(),
    'synthetic short must carry the BLOCKED (undefined-risk) badge');
  assert.ok(!(await page.locator('.tpl[data-tpl="SYNTHETIC_LONG"] .badge:has-text("BLOCKED")').count()),
    'synthetic long is defined-risk — no BLOCKED badge');
});

test('D3: the competition renders INLINE in Ideas (no orphan Decision page navigation)', async () => {
  await page.evaluate(() => Learn.setLevel('expert'));
  await page.evaluate(() => { App.state.filterState = {}; App.state.discoverForm = null; App.state.recommendResults = null; });
  await go('#/recommend/manual');
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
  await go('#/trade/discover/manual');
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
  await go('#/data/overview');
  await page.waitForSelector('#dc-mode .badge');
  await page.waitForSelector('#dc-health:has-text("QUOTES")', { timeout: 15000 });
  const ovBottom = await page.evaluate(() => {
    const g = document.querySelector('.dc-grid');
    return g ? Math.round(g.getBoundingClientRect().bottom) : 9999;
  });
  assert.ok(ovBottom <= 730, 'Data Overview fits a 720px viewport (bottom=' + ovBottom + ')');
  if (await page.evaluate(() => App.config.fixturesOnly)) {
    const engineText = await page.textContent('#dc-engine');
    assert.match(engineText, /Feed\s*Static Demo/, 'the fixed Demo feed names its actual operating mode');
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
  // Expanded detail lives BELOW the grid (drawer), never inside a row card
  assert.ok(await page.locator('#dc-detail .xp-head').count() >= 1, 'detail drawer below the 2x2');
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
