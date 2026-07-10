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
  // Show-don't-tell: a REAL engine-generated candidate renders on the welcome page
  await page.waitForSelector('#welcome-live .candidate', { timeout: 20000 });
  // Skip to the dashboard; the choice persists
  await page.click('#welcome-skip');
  await page.waitForSelector('.tile-row .tile');
  const app = await page.textContent('#app');
  assert.match(app, /Learn options by/); // the hero band lives ON the dashboard now
  assert.match(app, /Buying power/);
  assert.ok(await page.locator('#home-tour-link').isVisible(), 'the full tour is one visible click away');
  assert.ok((await page.locator('.tile-row .tile').count()) === 4, 'four market tiles');
  const footer = await page.textContent('#disclaimer');
  assert.match(footer, /not financial advice/i);
  // Legacy #/welcome redirects into the adaptive Home's tour view
  await go('#/welcome');
  await page.waitForSelector('#welcome-hero');
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'home');
  // The brand mark ALWAYS brings the welcome back — skipping it once is not goodbye
  await go('#/home');
  await page.waitForSelector('.tile-row .tile');
  await page.click('.brand');
  await page.waitForSelector('#welcome-hero');
  assert.ok(await page.locator('.brand .brand-mark').count(), 'the SVG mark renders in the header');
  await go('#/home');
  await page.waitForSelector('.tile-row .tile');
  await page.click('#home-tour-link'); // the hero band CTA is the second way back
  await page.waitForSelector('#welcome-hero');
  // Home: sector pulse chips dig into the explorer
  await go('#/home');
  await page.waitForSelector('#sector-pulse .sector-chip', { timeout: 20000 });
  await page.click('#sector-pulse .sector-chip');
  await page.waitForSelector('#sector-explorer');
  await go('#/home'); // leave the suite on the dashboard for the next test
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

test('scenario studio: beginner story cards → fan of futures → strategy verdict in plain dollars', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { App.state.scenarioForm = null; App.state.verifyForm = null; });
  await go('#/research/AAPL');
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
  assert.ok((await page.locator('#sc-verify-out .lab-chart svg rect').count()) >= 3, 'terminal histogram');
});

test('scenario studio expert: model menu incl. Heston, IV knobs, the math on demand', async () => {
  await page.click('#level-switch button[data-level="expert"]');
  await page.evaluate(() => { App.state.scenarioForm = null; });
  await go('#/research/AAPL');
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
  await go('#/status');
  await page.waitForSelector('#dc-datasets .status-item');
  assert.match(await page.textContent('#dc-datasets'), /Observed market data/);
  // Generate a synthetic run.
  await page.click('#dc-generate-btn');
  await page.waitForSelector('#dc-gen-sym');
  await page.fill('#dc-gen-sym', 'AAPL');
  await page.click('#dc-gen-run');
  await page.waitForSelector('#dc-datasets .status-item .badge:has-text("SYNTHETIC")', { timeout: 30000 });
  // Activate it → the loud app-wide banner appears.
  const useBtn = page.locator('#dc-datasets .status-item:has(.badge:has-text("SYNTHETIC")) button:has-text("Use")').first();
  await useBtn.click();
  await page.waitForSelector('#scenario-banner', { timeout: 15000 });
  assert.match(await page.textContent('#scenario-banner'), /SCENARIO MODE/);
  // Switch back to observed → banner clears.
  await page.waitForSelector('#dc-datasets .status-item:has-text("Observed") button:has-text("Use")');
  await page.click('#dc-datasets .status-item:has-text("Observed") button:has-text("Use")');
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
  assert.match(await page.textContent('#continue-row'), /Research QQQ/);
  await page.click('#continue-row .sym-chip[data-continue="research"]');
  await page.waitForSelector('.quote-hero');
  assert.equal(await page.evaluate(() => window.location.hash), '#/research/QQQ');
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
  await page.evaluate(() => { App.state.scenarioForm = null; App.state.verifyForm = { mode: 'scenario' }; App.state.lastRecommendSymbol = 'AAPL'; });
  await go('#/trade/verify');
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

test('research symbol page: Historical evidence sits between the chart and the futures card', async () => {
  await go('#/research/AAPL');
  await page.waitForSelector('#what-has-happened');
  const order = await page.evaluate(() => {
    const ids = ['history-card', 'what-has-happened', 'whatif-card'];
    const tops = ids.map(id => { const n = document.getElementById(id); return n ? n.getBoundingClientRect().top + window.scrollY : -1; });
    return tops;
  });
  assert.ok(order.every(t => t >= 0), 'all three cards present: ' + order.join(','));
  assert.ok(order[0] < order[1] && order[1] < order[2], 'NOW → evidence → futures order: ' + order.join(','));
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
  await go('#/home');
  await go('#/trade/discover/manual');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('#rec-results .candidate', { timeout: 30000 });
  await page.locator('#rec-results .candidate button:has-text("Practice this trade"), #rec-results .candidate button:has-text("Use in trade ticket")').first().click();
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
  await go('#/home');
  await go('#/trade/discover/manual');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.fill('#rec-symbol', 'AAPL');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('#rec-results .candidate', { timeout: 30000 });
  await page.locator('#rec-results .candidate button:has-text("Practice this trade"), #rec-results .candidate button:has-text("Use in trade ticket")').first().click();
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
  // Escape closes.
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('#info-pop').count(), 0, 'Escape closes the bubble');
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
  // in the expandable detail row
  await page.waitForSelector('#compare-table tbody tr.clickable', { timeout: 30000 });
  await page.click('#compare-table tbody tr.clickable');
  await page.waitForSelector('.compare-detail .candidate');
  const card = page.locator('.compare-detail .candidate:has(button:has-text("Backtest this"))').first();
  assert.ok(await card.count(), 'candidates offer Backtest this');
  const strategy = await card.getAttribute('data-strategy');
  await card.locator('button:has-text("Backtest this")').click();
  await page.waitForSelector('#bt-symbol');
  assert.equal(await page.inputValue('#bt-symbol'), 'AAPL');
  assert.equal(await page.inputValue('#bt-strategy'), strategy, 'strategy carried over');
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
});

test('data center: engine status, sources with license modes, coverage, jobs, tiered reset', async () => {
  await go('#/status');
  await page.click('#level-switch button[data-level="expert"]');
  // Engine status card
  await page.waitForSelector('#dc-engine .chip-row');
  assert.match(await page.textContent('#dc-engine'), /Market engine/);
  assert.match(await page.textContent('#dc-engine'), /Warmed/);
  // Source cards disclose each connector's license/use mode (Yahoo = personal-only)
  await page.waitForSelector('#dc-sources .dc-source');
  const sources = await page.textContent('#dc-sources');
  assert.match(sources, /Yahoo Finance/);
  assert.match(sources, /PERSONAL/);
  assert.match(sources, /licensed · internal-use/); // the owned-CSV path
  // Coverage + provider health (kept) render (health fills detached — wait for it)
  await page.waitForSelector('#dc-coverage');
  await page.waitForSelector('#dc-health:has-text("QUOTES")', { timeout: 15000 });
  assert.match(await page.textContent('#app'), /Fixtures-only|simulated demo/i);
  // Jobs: backfill the universe → its job row appears (other suites' jobs may already be listed)
  await page.click('#dc-backfill');
  await page.waitForSelector('#dc-jobs .dc-job:has-text("backfill_underlying")', { timeout: 15000 });
  assert.match(await page.textContent('#dc-jobs'), /backfill_underlying/);
  // Expert reset exposes all four tiers; the confirm needs the typed word
  const tiers = await page.$$eval('#dc-reset-tier option', os => os.map(o => o.value));
  assert.ok(tiers.includes('MARKET_DATA') && tiers.includes('EVERYTHING'), 'expert reset tiers');
  await page.click('#dc-reset-btn');
  await page.waitForSelector('#dc-reset-confirm');
  await page.click('#modal-confirm'); // empty confirm -> refused, modal stays
  assert.ok(await page.locator('#dc-reset-confirm').count(), 'reset refused without typing RESET');
  await page.click('.modal button:has-text("Cancel")');
});

test('data center reset tiers are reduced for Beginner', async () => {
  await go('#/status');
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
  // The working symbol FOLLOWS across stages (the QQQ->AAPL amnesia bug)
  await go('#/trade/verify');
  assert.equal(await page.inputValue('#bt-symbol'), 'QQQ', 'Backtest picks up the ticker you just typed');
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
  await page.click('#universe-sector .sector-chip[data-sector="CORE"]');
  await page.waitForSelector('#app[data-ready="true"]');
  const uni = await page.evaluate(() => fetch('/api/universe').then(r => r.json()));
  assert.equal(uni.active.sectorKey, 'CORE');
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
  assert.ok(await page.locator('#sector-chips .sector-chip').count() >= 10, 'all sectors explorable');
  // The rail never just "ends": when it overflows, an arrow says there is more
  assert.ok(await page.locator('#sector-chips.can-right .rail-arrow-right').isVisible(),
    'overflowing rail shows a right arrow');
  const before = await page.evaluate(() => document.querySelector('#sector-chips .sector-rail').scrollLeft);
  await page.click('#sector-chips .rail-arrow-right');
  await page.waitForFunction(prev =>
    document.querySelector('#sector-chips .sector-rail').scrollLeft > prev + 50, before);
  assert.ok(await page.locator('#sector-chips.can-left .rail-arrow-left').isVisible(),
    'after scrolling, the way back is visible too');
  // TECH is NOT the active universe (CORE is, set above) — the set-universe action must show
  await page.click('#sector-chips .sector-chip[data-sector="TECH"]');
  await page.waitForSelector('#sector-grid .sector-tile');
  const grid = await page.textContent('#sector-grid');
  assert.match(grid, /AAPL/);
  assert.match(grid, /NO LIVE DATA/); // fixture mode: non-demo symbols honestly labeled
  // EVERY sector symbol is an actionable tile, with or without a quote
  const tiles = await page.locator('#sector-grid .sector-tile').count();
  assert.ok(tiles >= 10, 'all TECH symbols render as tiles, got ' + tiles);
  assert.ok(await page.locator('#sector-grid .tile-nodata button:has-text("Research")').count() >= 1,
    'quote-less tiles still offer Research');
  // Tap a tile -> the symbol panel opens in ONE predictable slot above the grid
  await page.locator('#sector-grid .sector-tile:has-text("AAPL")').first().click();
  await page.waitForSelector('#explorer-focus .symbol-panel .range-pills .pill');
  await page.waitForSelector('#explorer-focus .symbol-panel .chart-wrap svg.chart', { timeout: 15000 });
  assert.ok(await page.locator('#explorer-focus .symbol-panel .sp-news .status-item').count() >= 1,
    'headlines inside the focus panel');
  assert.ok(await page.locator('#sector-grid .sector-tile.open').count() === 1, 'source tile highlighted');
  // Close: the panel's own X, or tapping the tile again
  await page.click('#explorer-focus .focus-close');
  assert.equal(await page.locator('#explorer-focus .symbol-panel').count(), 0, 'X closes the panel');
  assert.ok(await page.locator('#set-universe-btn').count(), 'one-click make-this-my-universe');
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
  // Restore demo universe for other tests
  await page.evaluate(() => API.put('/api/universe', { sector: 'DEMO' }));
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

  // Learning: filters expandable exposes ONLY the two plain limits (money + odds)
  assert.ok(await page.locator('#rec-filters .xp-head').count(), 'beginner filters live behind an expandable');
  await page.click('#rec-filters .xp-head');
  assert.ok(await page.locator('#rec-f-maxloss').isVisible(), 'max-loss limit at beginner');
  assert.ok(await page.locator('#rec-f-pop').isVisible(), 'POP limit at beginner');
  assert.equal(await page.locator('#rec-f-yield').count(), 0, 'yield/assignment filters hidden at beginner');
  // Learning: backtest strategy menu is the beginner subset
  await go('#/backtest');
  assert.equal(await page.locator('#bt-strategy option[value="IRON_CONDOR"]').count(), 0, 'condor locked at beginner');
  assert.ok(await page.locator('#bt-strategy option[value="COVERED_CALL"]').count(), 'covered call available at beginner');

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
  assert.ok(await page.locator('#bt-strategy option[value="IRON_BUTTERFLY"]').count(), 'full strategy menu at Pro');

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
    await API.put('/api/universe', { sector: 'DEMO' }); // mutation
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

test('consolidated Lab tools live in their homes: optimizer on Decision, study tools on Research', async () => {
  await page.evaluate(() => Learn.setLevel('expert'));
  // #/lab is dissolved — the URL still works but now renders Research (the study home).
  await page.evaluate(() => { window.location.hash = '#/lab'; });
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('#symbol-input', { timeout: 8000 }); // Research lookup = the Research view rendered
  assert.ok(!(await page.locator('#nav a[data-route="lab"]').count()), 'Lab nav item is gone');

  // OPTIMIZER now lives at the bottom of the Decision competition (construction, not a Lab silo).
  await page.evaluate(() => {
    App.state.discoverForm = { symbol: 'AAPL', goal: 'DIRECTIONAL', thesis: 'neutral', horizon: 'month' };
    App.state.filterState = {};
  });
  await go('#/decision');
  await page.waitForSelector('#lab-opt-run', { timeout: 25000 });
  // Diagnostic mode guarantees a funded (least-bad) set on the fixture universe, LABELED not-a-recommendation.
  await page.check('#lab-opt-diag');
  await page.click('#lab-opt-run');
  await page.waitForSelector('#lab-opt-summary', { timeout: 20000 });
  assert.ok((await page.locator('.lab-optimizer .lab-chart svg rect').count()) >= 1, 'composition chart bars');
  assert.ok((await page.locator('.lab-optimizer table tbody tr').count()) >= 1, 'allocations table rows');
  assert.ok((await page.locator('#lab-opt-out .alert-caution').count()) >= 1, 'diagnostic set labeled not-a-recommendation');

  // STUDY TOOLS (signal test + notebook) now live on the Research index.
  await go('#/research');
  await page.waitForSelector('#research-study-tools .lab-grid', { timeout: 15000 });
  // Icons render (magnifier was missing from ICON_PATHS -> empty box) — now in the study-tool header.
  assert.ok((await page.locator('#research-study-tools .lab-title .icon svg > *').count()) >= 1, 'study-tool icons render paths');
  // Research-question workbench: a REAL question (not the degenerate 20-day-momentum toy), run and
  // reported baseline-relative. The run button enables once the question catalog loads.
  await page.waitForSelector('#lab-hyp-run:not([disabled])', { timeout: 15000 });
  assert.ok((await page.locator('#lab-q-picker').count()) >= 1, 'question picker present');
  await page.click('#lab-hyp-run');
  await page.waitForSelector('#lab-hyp-out .alert', { timeout: 20000 });
  assert.ok((await page.locator('#lab-hyp-out .lab-chart svg').count()) >= 2, 'win-rate gauge + distribution histogram');
  assert.match(await page.textContent('#lab-hyp-out'), /After the signal|Normally/); // baseline-relative, not "vs 50% chance"
  // Charts use resolved design tokens, not the undefined --line/--fg (which rendered black).
  const gaugeHtml = await page.locator('#lab-hyp-out .lab-chart').first().innerHTML();
  assert.ok(gaugeHtml.indexOf('var(--line)') < 0 && gaugeHtml.indexOf('var(--fg)') < 0, 'gauge uses no unresolved CSS vars');

  // Notebook: create + delete (delete regression: button called the non-existent API.delete).
  await page.click('#lab-note-new');
  await page.fill('.note-editor input[placeholder="Title"]', 'My hypothesis');
  await page.fill('.note-editor textarea', 'SPY put spreads only at high IV rank');
  await page.click('.note-editor button:has-text("Save")');
  await page.waitForSelector('.lab-notebook:has-text("My hypothesis")', { timeout: 10000 });
  await page.locator('.lab-notebook').getByText('My hypothesis').click();
  await page.locator('.lab-notebook').getByRole('button', { name: 'Delete' }).click();
  await page.waitForFunction(
    () => { var nb = document.querySelector('.lab-notebook'); return nb && nb.textContent.indexOf('My hypothesis') < 0; },
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

test('level-leak: hidden Expert filters + 0DTE do not reach the engine at Beginner', async () => {
  // Set Expert-only filters + 0DTE in persisted state, then run ideas AS BEGINNER. The engine
  // request must NOT carry the hidden constraints (invisible rejections were the bug).
  await page.evaluate(() => {
    App.state.filterState = { rec: { minPop: '', maxAssign: '80', minYield: '5', maxCost: '300' } };
    App.state.discoverForm = { goal: 'DIRECTIONAL', source: 'single', symbol: 'AAPL', horizon: '0DTE', allow0: true };
  });
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');

  let body = null;
  await page.route('**/api/recommend', route => {
    try { body = JSON.parse(route.request().postData() || '{}'); } catch (e) {}
    route.continue();
  });
  await go('#/recommend/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#rec-go');
  await page.waitForFunction(() => window.__lastRec !== undefined || true); // let the request fire
  await page.waitForTimeout(1200);
  await page.unroute('**/api/recommend');

  assert.ok(body, 'recommend request captured');
  // Hidden Expert filters must be absent; 0DTE must be sanitized off for Beginner.
  const f = body.filters || {};
  assert.ok(!('maxAssignmentProb' in f), 'hidden assignment filter must not leak to Beginner');
  assert.ok(!('minAnnualizedYieldPct' in f), 'hidden yield filter must not leak to Beginner');
  assert.ok(!('maxCostCents' in f), 'hidden cost filter must not leak to Beginner');
  assert.equal(body.allow0dte, false, '0DTE must be off at Beginner even if persisted from Expert');
  assert.notEqual(body.horizon, '0DTE', 'a persisted 0DTE horizon must be sanitized at Beginner');
  await page.evaluate(() => { App.state.filterState = {}; App.state.discoverForm = null; });
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
