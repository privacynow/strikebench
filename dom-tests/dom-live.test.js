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
    assert.doesNotMatch(await page.textContent('#home-market-watch'), /DEMO DATA/i,
      'Observed Home never substitutes Demo quotes');
  } else {
    assert.match(await page.textContent('#home-market-watch'), /market data unavailable/i,
      'provider outage is an explicit state');
  }
  assertClean('dashboard');
});

test('live: research a real symbol end to end', async () => {
  await go('#/research/AAPL');
  await page.waitForSelector('#research-symbol', { timeout: 30000 });
  const quoteBadge = await page.textContent('.quote-hero .badge');
  assert.match(quoteBadge, /REALTIME|DELAYED|EOD|STALE/i, 'observed quote age is labeled');
  assert.doesNotMatch(quoteBadge, /DEMO|SIMULATED/i, 'Observed quote never comes from another lane');
  await page.click('#research-workspace-tabs [data-research-tab="options"]');
  await page.waitForSelector('#expiration-select', { timeout: 30000 });
  await page.waitForSelector('.tbl tbody tr');
  const hist = await page.textContent('#history-card');
  assert.doesNotMatch(hist, /DEMO DATA/i, 'Observed Research never substitutes Demo candles');
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

test('live: manual ideas return candidates for a real chain', async () => {
  await go('#/trade/context/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('.candidate, .empty, .alert-warn', { timeout: 60000 });
  const text = await page.textContent('#rec-results');
  assert.ok(/Theoretical worst case|Theor\. max loss/.test(text) || /Nothing passed/.test(text),
    'candidates preserve theoretical risk truth or render a graceful empty state');
  assertClean('ideas-manual');
});

test('live: scout scans and reports with evidence within budget', async () => {
  await go('#/trade/context/scout');
  await page.waitForSelector('#auto-target');
  const t0 = Date.now();
  await page.click('#rec-go');
  await page.waitForSelector('.pick-card, .empty', { timeout: 60000 });
  const elapsed = Date.now() - t0;
  assert.ok(elapsed < 20000, `scout under 20s, took ${elapsed}ms`);
  if (await page.locator('.pick-card').count()) {
    assert.match(await page.textContent('.pick-card'), /Confidence/);
  }
  assertClean('scout');
});

test('live: full ticket flow places and unwinds a paper trade at real marks', async () => {
  await go('#/trade/context/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.click('#intent-choices .choice[data-intent="DIRECTIONAL"]');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  const useBtn = '#rec-results .candidate button:has-text("Review & decide")';
  await page.waitForSelector(useBtn + ', .alert-warn', { timeout: 60000 });
  if (!(await page.locator(useBtn).count())) {
    assert.ok(true, 'no candidates passed screens right now — graceful, not a failure');
    assertClean('ticket-nocandidates');
    return;
  }
  await page.locator(useBtn).first().click();
  await page.waitForSelector('#to-review', { timeout: 30000 });
  await page.click('#to-review');
  await page.waitForSelector('#to-confirm', { timeout: 60000 });
  assert.match(await page.textContent('#ticket-body'), /Buying power after/);
  await page.$$eval('.ack-gate input', els => els.forEach(e => { if (!e.checked) e.click(); })); // acknowledge material risks (CP-5 gate)
  await page.click('#to-confirm');
  await page.waitForSelector('#place-trade');
  await page.click('#place-trade');
  await page.waitForSelector('#refresh-btn', { timeout: 60000 }); // detail landmark
  assert.match(await page.textContent('#app'), /ACTIVE/);

  await page.click('#refresh-btn');
  await page.waitForSelector('text=Marks history', { timeout: 30000 });
  await page.click('#unwind-btn');
  await page.waitForSelector('#modal-confirm');
  await page.click('#modal-confirm');
  await page.waitForSelector('#app[data-ready="true"]:has-text("CLOSED")', { timeout: 60000 });
  assertClean('ticket-lifecycle');
});

test('live: observed backtest never substitutes demo history', async () => {
  await go('#/trade/outcomes');
  await page.click('#bt-run');
  await page.waitForSelector('#bt-results .stat, #bt-results .alert-danger', { timeout: 120000 });
  const text = await page.textContent('#bt-results');
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
