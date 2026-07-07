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

const PORT = process.env.PORT || '7072';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page;

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
  const dbDir = fs.mkdtempSync(path.join(os.tmpdir(), 'strikebench-dom-'));
  server = spawn(JAVA, ['-jar', JAR], {
    env: {
      ...process.env,
      PORT,
      DB_PATH: path.join(dbDir, 'dom.db'),
      FIXTURES_ONLY: 'true'
    },
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
});

test('boots to the welcome page, then the dashboard with markets and the tape', async () => {
  assert.equal(await page.title(), 'StrikeBench');
  // Fresh account -> the opening page: hero, level paths, capability grid
  await page.waitForSelector('#welcome-hero');
  const hero = await page.textContent('#app');
  assert.match(hero, /StrikeBench/);
  assert.match(hero, /How do you want to start\?/);
  assert.ok((await page.locator('#welcome-levels .welcome-card').count()) === 3, 'three level paths');
  // The universe ticker tape ticks above the welcome page too
  await page.waitForSelector('#tape .tape-item');
  // Show-don't-tell: a REAL engine-generated candidate renders on the welcome page
  await page.waitForSelector('#welcome-live .candidate', { timeout: 20000 });
  // Skip to the dashboard; the choice persists
  await page.click('#welcome-skip');
  await page.waitForSelector('.tile-row .tile');
  const app = await page.textContent('#app');
  assert.match(app, /Overview/);
  assert.match(app, /Buying power/);
  assert.ok((await page.locator('.tile-row .tile').count()) === 4, 'four market tiles');
  const footer = await page.textContent('#disclaimer');
  assert.match(footer, /not financial advice/i);
  // #/welcome stays reachable after skipping — and the BRAND opens the product page
  await go('#/welcome');
  await page.waitForSelector('#welcome-hero');
  await go('#/home');
  await page.waitForSelector('.tile-row .tile');
  await page.click('.brand');
  await page.waitForSelector('#welcome-hero');
  // Home: sector pulse chips dig into the explorer
  await go('#/home');
  await page.waitForSelector('#sector-pulse .sector-chip', { timeout: 20000 });
  await page.click('#sector-pulse .sector-chip');
  await page.waitForSelector('#sector-explorer');
  await go('#/home'); // leave the suite on the dashboard for the next test
});

test('research AAPL: hero quote, focused chain, show-all toggle', async () => {
  await go('#/research');
  await page.fill('#symbol-input', 'AAPL');
  await page.click('#symbol-go');
  await page.waitForSelector('#research-symbol');
  assert.match(await page.textContent('#research-symbol'), /AAPL/);
  assert.ok(await page.isVisible('text=DEMO DATA'));
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
  const text = await page.textContent('#app');
  assert.match(text, /no listed options/i);
});

test('scout tab auto-recommends with evidence and targets', async () => {
  await go('#/recommend');
  await page.waitForSelector('#auto-go'); // scout is the default tab
  await page.fill('#auto-target', '250');
  await page.click('#auto-go');
  await page.waitForSelector('.pick-card', { timeout: 30000 });
  const picks = await page.locator('.pick-card').count();
  assert.ok(picks >= 1 && picks <= 3, `1-3 picks, got ${picks}`);
  const text = await page.textContent('#auto-results');
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
  assert.match(appText, /Most you can lose|Max loss/); // learning vs confident/pro wording
  assert.match(appText, /not financial advice/i);
});

let tradeUrlHash = null;

test('guided ticket end-to-end places a paper trade', async () => {
  await go('#/ticket');
  // Step 1: thesis
  await page.fill('#ticket-symbol', 'AAPL');
  await page.click('#thesis-choices button:has-text("Bullish")');
  // Step 2: horizon
  await page.waitForSelector('#horizon-choices');
  await page.click('#horizon-choices button:has-text("About a month")');
  // Step 3: risk
  await page.waitForSelector('#risk-next');
  await page.click('#risk-next');
  // Step 4: pick first screened strategy
  await page.waitForSelector('.candidate button:has-text("Choose this")');
  await page.click('.candidate button:has-text("Choose this")');
  // Step 5: strikes & size
  await page.waitForSelector('#to-review');
  assert.ok(await page.isVisible('#ticket-qty'));
  await page.click('#to-review');
  // Step 6: review shows exact money effects and the safety checklist
  await page.waitForSelector('#to-confirm');
  const review = await page.textContent('#ticket-body');
  assert.match(review, /Max loss/);
  assert.match(review, /Buying power after/);
  assert.match(review, /Safety check/);
  assert.match(review, /Worst case is known and capped/);
  await page.click('#to-confirm');
  // Step 7: confirm
  await page.waitForSelector('#place-trade');
  const confirmText = await page.textContent('#ticket-body');
  assert.match(confirmText, /PAPER trade/);
  await page.click('#place-trade');
  await page.waitForSelector('#app[data-route="trade"][data-ready="true"]');
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

test('account shows balances, ledger, and guarded reset', async () => {
  await go('#/account');
  const text = await page.textContent('#app');
  assert.match(text, /Buying power/);
  assert.match(text, /PREMIUM_OPEN/);
  assert.match(text, /RESERVE_RELEASE/);
  await page.click('#reset-btn');
  await page.waitForSelector('#modal-confirm');
  assert.match(await page.textContent('.modal'), /cannot be undone/i);
  await page.click('.modal button:has-text("Cancel")');
  assert.equal(await page.locator('#modal-confirm').count(), 0, 'modal dismissed');
});

test('backtest runs and reports mode, coverage, assumptions', async () => {
  await go('#/backtest');
  await page.fill('#bt-from', '2026-03-02');
  await page.fill('#bt-to', '2026-06-30');
  await page.click('#bt-run');
  await page.waitForSelector('#bt-results .stat', { timeout: 30000 });
  const text = await page.textContent('#bt-results');
  assert.match(text, /MODELED_FROM_UNDERLYING/);
  assert.match(text, /Sample size/);
  assert.match(text, /Coverage/);
  assert.match(text, /slippagePctPerLeg/);
  assert.match(text, /Equity curve/);
});

test('data status lists providers per domain', async () => {
  await go('#/status');
  const text = await page.textContent('#app');
  assert.match(text, /QUOTES/);
  assert.match(text, /fixture/);
  assert.match(text, /fixtures-only mode/i);
});

test('pro depth: comparison table, custom builder, position greeks', async () => {
  // Comparison table on Ideas at Pro
  await go('#/recommend/manual');
  await page.click('#level-switch button[data-level="pro"]');
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

  // Custom multi-leg builder: build a 255/260 call spread from scratch
  await go('#/ticket');
  await page.waitForSelector('#custom-builder-btn');
  await page.fill('#ticket-symbol', 'AAPL');
  await page.click('#custom-builder-btn');
  await page.waitForSelector('#custom-legs .custom-leg');
  const strike0 = page.locator('.custom-leg').nth(0).locator('select').nth(3);
  const values = await strike0.locator('option').evaluateAll(os => os.map(o => o.value));
  assert.ok(values.length >= 4, 'strike list populated from the chain');
  const mid = Math.floor(values.length / 2);
  await strike0.selectOption(values[mid]);
  await page.click('#add-leg');
  await page.waitForFunction(() => document.querySelectorAll('.custom-leg').length === 2);
  await page.locator('.custom-leg').nth(1).locator('select').nth(0).selectOption('SELL');
  await page.locator('.custom-leg').nth(1).locator('select').nth(3).selectOption(values[mid + 2]);
  await page.click('#to-review');
  await page.waitForSelector('#to-confirm', { timeout: 30000 });
  const review = await page.textContent('#ticket-body');
  assert.match(review, /Safety check/);
  await page.click('#to-confirm');
  await page.waitForSelector('#place-trade');
  await page.click('#place-trade');
  await page.waitForSelector('#app[data-route="trade"][data-ready="true"]', { timeout: 30000 });

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
  await page.click('#level-switch button[data-level="learning"]'); // restore default
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
  assert.match(card, /Chance you sell/i);           // learning-level fact framing
  await page.waitForSelector('#rec-holdings-hint .chip-row'); // real position surfaced

  // 3. Place it through the ticket — no new buying power, shares get locked
  await page.click('.candidate .btn-row .btn');
  await page.waitForSelector('#to-review', { timeout: 30000 });
  await page.click('#to-review');
  await page.waitForSelector('#to-confirm', { timeout: 30000 });
  const review = await page.textContent('#ticket-body');
  assert.match(review, /Covered by shares you already hold/);
  await page.click('#to-confirm');
  await page.waitForSelector('#place-trade');
  await page.click('#place-trade');
  await page.waitForSelector('#app[data-route="trade"][data-ready="true"]', { timeout: 30000 });
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

  // 7. Scout exposes intent checkboxes
  await go('#/recommend/scout');
  await page.waitForSelector('#auto-i-income');
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
  // Trigger the health check via a route error: break one API call so the boundary fires
  await page.route('**/api/status', r => r.abort());
  await page.evaluate(() => { location.hash = '#/status'; });
  await page.waitForSelector('#route-error', { timeout: 15000 });
  const boundary = await page.textContent('#route-error');
  assert.match(boundary, /status screen failed to load/i);
  assert.ok(await page.locator('#route-retry').count(), 'retry button present');
  await page.waitForSelector('#stale-banner', { timeout: 15000 });
  assert.match(await page.textContent('#stale-banner'), /rebuilt after this server started/i);

  // Un-break everything: retry renders the screen and the banner clears on healthy report
  await page.unroute('**/api/status');
  await page.unroute('**/api/health');
  await page.click('#route-retry');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.match(await page.textContent('#app'), /QUOTES/);
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
  // Crosshair: hover the middle of the chart -> tooltip with a date and % readout
  await page.locator('#history-card svg.chart').scrollIntoViewIfNeeded();
  const box = await page.locator('#history-card svg.chart').boundingBox();
  await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.4);
  await page.waitForSelector('#history-card .chart-tip:not([style*="display: none"])');
  assert.match(await page.textContent('#history-card .chart-tip'), /% in window/);
  // MAX pill answers with everything the fixture has (~3y)
  await page.click('#history-card .pill[data-range="max"]');
  await page.waitForSelector('#history-card .pill.active[data-range="max"]');
  await page.waitForSelector('#history-card .chart-wrap svg.chart');

  // Payoff crosshair: place nothing new — reuse any active trade? Instead assert on preview later.
  // Universe: tape exists, sector picker on the scout changes it globally
  await page.waitForSelector('#tape .tape-item');
  await go('#/recommend/scout');
  await page.waitForSelector('#universe-sector');
  await page.selectOption('#universe-sector', 'CORE');
  await page.waitForSelector('#app[data-ready="true"]');
  const uni = await page.evaluate(() => fetch('/api/universe').then(r => r.json()));
  assert.equal(uni.active.sectorKey, 'CORE');
  // Datalist feeds symbol inputs everywhere
  assert.ok(await page.evaluate(() => document.querySelectorAll('#universe-symbols option').length) >= 4);
  // Tape is SEAMLESS: two identical halves, each at least as wide as the scroll viewport
  const tape = await page.evaluate(() => {
    const strip = document.getElementById('tape-strip');
    const scroll = document.querySelector('.tape-scroll');
    const seqs = strip.querySelectorAll('.tape-seq');
    return { seqs: seqs.length, stripW: strip.scrollWidth, viewW: scroll.clientWidth };
  });
  assert.ok(tape.seqs >= 2 && tape.seqs % 2 === 0, 'even number of sequences: ' + tape.seqs);
  assert.ok(tape.stripW >= tape.viewW * 2 - 5, 'each half covers the viewport (no gap at wrap)');
  // The sector switcher lives IN the tape — universes reachable from every tab
  assert.ok(await page.locator('#tape-sector option').count() >= 10, 'sector switcher in the tape');

  // Sector explorer: chips per sector, live tiles, scout handoff
  await go('#/research');
  await page.waitForSelector('#sector-explorer .sector-chip');
  assert.ok(await page.locator('#sector-chips .sector-chip').count() >= 10, 'all sectors explorable');
  // TECH is NOT the active universe (CORE is, set above) — the set-universe action must show
  await page.click('#sector-chips .sector-chip[data-sector="TECH"]');
  await page.waitForSelector('#sector-grid .sector-tile');
  const grid = await page.textContent('#sector-grid');
  assert.match(grid, /AAPL/);
  assert.match(grid, /No data right now/); // fixture mode: non-demo symbols honestly labeled
  assert.ok(await page.locator('#set-universe-btn').count(), 'one-click make-this-my-universe');
  await page.click('#sector-explorer .btn-row button:has-text("Scout this sector")');
  await page.waitForSelector('#auto-universe');
  assert.ok((await page.inputValue('#auto-universe')).includes('SPY'), 'scout prefilled with the sector');

  // Header search: "/" focuses, Enter researches
  await page.keyboard.press('/');
  await page.keyboard.type('AAPL');
  await page.keyboard.press('Enter');
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  assert.match(await page.textContent('#app'), /Apple/);

  // Tape click-through navigates to research (hovering pauses the marquee — same as a user)
  await page.hover('#tape');
  await page.click('#tape .tape-item');
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  // Restore demo universe for other tests
  await page.evaluate(() => fetch('/api/universe', { method: 'PUT',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sector: 'DEMO' }) }));
});

test('experience ladder reshapes the UI per level', async () => {
  await go('#/account');
  await page.click('#level-switch button[data-level="learning"]');
  await page.waitForSelector('#app[data-ready="true"]');
  // Learning — explainers fully visible, glossary terms clickable
  assert.ok(await page.evaluate(() => document.body.classList.contains('lvl-learning')));
  assert.ok(await page.locator('.explain').first().isVisible(), 'explainers visible at Learning');

  // Learning candidate cards use plain language and expandable mechanics.
  // The ideas form persists intent/symbol/target across renders by design — reset it here.
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
  assert.ok(await page.locator('#rec-filters .xp-head').count(), 'learning filters live behind an expandable');
  await page.click('#rec-filters .xp-head');
  assert.ok(await page.locator('#rec-f-maxloss').isVisible(), 'max-loss limit at learning');
  assert.ok(await page.locator('#rec-f-pop').isVisible(), 'POP limit at learning');
  assert.equal(await page.locator('#rec-f-yield').count(), 0, 'yield/assignment filters hidden at learning');
  // Learning: backtest strategy menu is the beginner subset
  await go('#/backtest');
  assert.equal(await page.locator('#bt-strategy option[value="IRON_CONDOR"]').count(), 0, 'condor locked at learning');
  assert.ok(await page.locator('#bt-strategy option[value="COVERED_CALL"]').count(), 'covered call available at learning');

  // Pro: dense body class, explainers gone, cards show metric chips
  await page.click('#level-switch button[data-level="pro"]');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.ok(await page.evaluate(() => document.body.classList.contains('lvl-pro')));
  await go('#/account');
  assert.ok(!(await page.locator('.explain').first().isVisible()), 'explainers hidden at Pro');
  // Pro: compact intent segments, filters inline (no expandable), full backtest menu
  await go('#/recommend/manual');
  await page.waitForSelector('#intent-choices.intent-compact');
  assert.ok(await page.locator('#rec-f-yield').isVisible(), 'all five filters inline at Pro');
  assert.equal(await page.locator('#rec-filters .xp-head').count(), 0, 'no expandable around Pro filters');
  await go('#/backtest');
  assert.ok(await page.locator('#bt-strategy option[value="IRON_BUTTERFLY"]').count(), 'full strategy menu at Pro');

  // Confident: back to balanced — story cards return, filters behind an expandable again
  await page.click('#level-switch button[data-level="confident"]');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.ok(await page.evaluate(() => document.body.classList.contains('lvl-confident')));
  await go('#/recommend/manual');
  assert.equal(await page.locator('#intent-choices.intent-compact').count(), 0, 'story cards at Confident');
  assert.ok(await page.locator('#rec-filters .xp-head').count(), 'filters expandable at Confident');
  // restore default for other runs
  await page.click('#level-switch button[data-level="learning"]');
  await page.waitForSelector('#app[data-ready="true"]');
});
