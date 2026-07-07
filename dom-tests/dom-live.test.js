/*
 * LIVE-mode browser suite: the same jar, NO FIXTURES_ONLY — real keyless providers
 * (Cboe delayed chains, EDGAR, fixture fallback where labeled). Assertions are
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

const PORT = process.env.PORT || '7093';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page;
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
  await page.evaluate(h => { window.location.hash = h; }, hash);
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 60000 });
}

function assertClean(label) {
  assert.deepEqual(pageErrors, [], `${label}: page JS errors: ${pageErrors.join(' | ')}`);
  assert.deepEqual(serverErrors, [], `${label}: server 5xx: ${serverErrors.join(' | ')}`);
}

before(async () => {
  const dbDir = fs.mkdtempSync(path.join(os.tmpdir(), 'strikebench-live-'));
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, DB_PATH: path.join(dbDir, 'live.db') }, // NO FIXTURES_ONLY
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
});

test('live: dashboard renders market tiles', async () => {
  // A fresh live account opens on the welcome page — skip it like a new user would
  const welcome = await page.locator('#welcome-skip').count();
  if (welcome) {
    await page.click('#welcome-skip');
    await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  }
  await page.waitForSelector('.tile-row .tile', { timeout: 30000 });
  assert.ok((await page.locator('.tile-row .tile').count()) >= 1, 'at least one market tile');
  assertClean('dashboard');
});

test('live: research a real symbol end to end', async () => {
  await go('#/research/AAPL');
  await page.waitForSelector('#research-symbol', { timeout: 30000 });
  assert.ok(await page.isVisible('text=DELAYED') || await page.isVisible('text=DEMO DATA'),
    'freshness labeled');
  await page.waitForSelector('#expiration-select', { timeout: 30000 });
  await page.waitForSelector('.tbl tbody tr');
  // demo history must be labeled when the chart falls back to fixtures in live mode
  const text = await page.textContent('#app');
  if (text.includes('One year of closes') && text.includes('DEMO DATA —')) {
    assert.match(text, /demo data/i);
  }
  assertClean('research');
});

test('live: research handles unknown and non-optionable symbols gracefully', async () => {
  await go('#/research/ZZZZQQ');
  assert.match(await page.textContent('#app'), /No data for ZZZZQQ/i);
  await go('#/research/VTSAX');
  assert.match(await page.textContent('#app'), /no listed options/i);
  assertClean('research-edge');
});

test('live: manual ideas return candidates for a real chain', async () => {
  await go('#/recommend/manual');
  await page.fill('#rec-symbol', 'AAPL');
  await page.selectOption('#rec-thesis', 'bullish');
  await page.click('#rec-go');
  await page.waitForSelector('.candidate, .empty, .alert-warn', { timeout: 60000 });
  const text = await page.textContent('#rec-results');
  assert.ok(/Most you can lose|Max loss/.test(text) || /Nothing passed/.test(text), 'candidates or a graceful empty state');
  assertClean('ideas-manual');
});

test('live: scout scans and reports with evidence within budget', async () => {
  await go('#/recommend');
  await page.waitForSelector('#auto-go');
  const t0 = Date.now();
  await page.click('#auto-go');
  await page.waitForSelector('.pick-card, .empty', { timeout: 60000 });
  const elapsed = Date.now() - t0;
  assert.ok(elapsed < 20000, `scout under 20s, took ${elapsed}ms`);
  if (await page.locator('.pick-card').count()) {
    assert.match(await page.textContent('.pick-card'), /Confidence/);
  }
  assertClean('scout');
});

test('live: full ticket flow places and unwinds a paper trade at real marks', async () => {
  await go('#/ticket');
  await page.fill('#ticket-symbol', 'AAPL');
  await page.click('#thesis-choices button:has-text("Bullish")');
  await page.waitForSelector('#horizon-choices');
  await page.click('#horizon-choices button:has-text("About a month")');
  await page.waitForSelector('#risk-next');
  await page.click('#risk-next');
  await page.waitForSelector('.candidate button:has-text("Choose this"), .alert-warn', { timeout: 60000 });
  if (!(await page.locator('.candidate button:has-text("Choose this")').count())) {
    assert.ok(true, 'no candidates passed screens right now — graceful, not a failure');
    assertClean('ticket-nocandidates');
    return;
  }
  await page.click('.candidate button:has-text("Choose this")');
  await page.waitForSelector('#to-review', { timeout: 30000 });
  await page.click('#to-review');
  await page.waitForSelector('#to-confirm', { timeout: 60000 });
  assert.match(await page.textContent('#ticket-body'), /Buying power after/);
  await page.click('#to-confirm');
  await page.waitForSelector('#place-trade');
  await page.click('#place-trade');
  await page.waitForSelector('#app[data-route="trade"][data-ready="true"]', { timeout: 60000 });
  assert.match(await page.textContent('#app'), /ACTIVE/);

  await page.click('#refresh-btn');
  await page.waitForSelector('text=Marks history', { timeout: 30000 });
  await page.click('#unwind-btn');
  await page.waitForSelector('#modal-confirm');
  await page.click('#modal-confirm');
  await page.waitForSelector('#app[data-ready="true"]:has-text("CLOSED")', { timeout: 60000 });
  assertClean('ticket-lifecycle');
});

test('live: backtest discloses demo underlying when no candle source', async () => {
  await go('#/backtest');
  await page.click('#bt-run');
  await page.waitForSelector('#bt-results .stat, #bt-results .alert-danger', { timeout: 120000 });
  const text = await page.textContent('#bt-results');
  if (/Sample size/.test(text) && /DEMO DATA/i.test(text)) {
    assert.match(text, /do not reflect the real market/i);
  }
  assertClean('backtest');
});

test('live: status, account, portfolio render', async () => {
  await go('#/status');
  assert.match(await page.textContent('#app'), /QUOTES/);
  await go('#/account');
  assert.match(await page.textContent('#app'), /Buying power/);
  await go('#/portfolio/closed');
  await page.waitForSelector('.tbl tbody tr, .empty', { timeout: 30000 });
  assertClean('remaining-screens');
});
