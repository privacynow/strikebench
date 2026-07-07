/*
 * GROWN-DATABASE sweep: the fixture and live suites always start from a fresh DB, which is
 * exactly how the "works in tests, fails on my machine" class of bug slipped through. This
 * suite grows a database the way a real user would — shares, a share-covered call, a spread,
 * a closed trade, a backtest — then injects a LEGACY dead trade byte-identical in shape to
 * the pre-realism-fix TSLA collar found in the owner's real database (stock:true legs_json,
 * expired legs, no intent, bogus stored maxLoss=0/POP=1), and finally walks EVERY route at
 * EVERY experience level plus every trade detail page. Any page JS error, any 5xx, or any
 * route-level error boundary is a hard failure.
 *
 * Run:  node --test dom-seeded.test.js
 */
'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { chromium } = require('playwright');

const PORT = process.env.PORT || '7171';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, dbPath;
const pageErrors = [];
const serverErrors = [];

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { if ((await fetch(`${BASE}/api/status`)).ok) return; } catch (e) { /* not up */ }
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error('server did not start');
}

async function api(method, p, body) {
  const res = await fetch(BASE + p, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${p} -> ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

function isoDaysFromNow(days) {
  return new Date(Date.now() + days * 86400000).toISOString().slice(0, 10);
}

function sqlite(sql) {
  const out = spawnSync('sqlite3', [dbPath, sql], { encoding: 'utf8' });
  if (out.status !== 0) throw new Error('sqlite3 failed: ' + out.stderr);
  return out.stdout.trim();
}

async function go(hash) {
  await page.evaluate(h => { window.location.hash = h; }, hash);
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
}

function assertClean(label) {
  assert.deepEqual(pageErrors, [], `${label}: page JS errors: ${pageErrors.join(' | ')}`);
  assert.deepEqual(serverErrors, [], `${label}: server 5xx: ${serverErrors.join(' | ')}`);
}

before(async () => {
  const dbDir = fs.mkdtempSync(path.join(os.tmpdir(), 'strikebench-seeded-'));
  dbPath = path.join(dbDir, 'seeded.db');
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, DB_PATH: dbPath, FIXTURES_ONLY: 'true' },
    stdio: 'ignore'
  });
  await waitForServer();

  // ---- Grow the account the way a user would, entirely through the API ----
  await api('POST', '/api/positions/buy', { symbol: 'AAPL', shares: 200 });

  // A share-covered call via the EXIT intent (locks 100 of the 200)
  const rec = await api('POST', '/api/recommend', { symbol: 'AAPL', intent: 'exit', riskMode: 'balanced' });
  const cc = rec.candidates.find(c => c.strategy === 'COVERED_CALL');
  assert.ok(cc, 'covered-call candidate for seeding');
  await api('POST', '/api/trades', {
    symbol: 'AAPL', strategy: 'COVERED_CALL', qty: 1, intent: 'exit', useHeldShares: true,
    legs: cc.legs.map(l => ({ action: l.action, type: l.type, strike: l.strike, expiration: l.expiration, ratio: 1 }))
  });

  // A plain credit spread, then a second one that gets closed (CLOSED-tab data)
  const exps = (await api('GET', '/api/research/AAPL/expirations')).expirations;
  const spread = qty => ({
    symbol: 'AAPL', strategy: 'CREDIT_PUT_SPREAD', qty,
    legs: [
      { action: 'SELL', type: 'PUT', strike: '242.5', expiration: exps[3], ratio: 1 },
      { action: 'BUY', type: 'PUT', strike: '240', expiration: exps[3], ratio: 1 }
    ],
    thesis: 'bullish', horizon: 'month', riskMode: 'balanced'
  });
  await api('POST', '/api/trades', spread(1));
  const toClose = (await api('POST', '/api/trades', spread(1))).trade.id;
  await api('POST', `/api/trades/${toClose}/unwind`, { confirm: true });

  // A persisted backtest (previous-runs list data)
  await api('POST', '/api/backtest', {
    symbol: 'AAPL', strategy: 'COVERED_CALL',
    from: isoDaysFromNow(-120), to: isoDaysFromNow(-1), targetDte: 30
  });

  // ---- Inject the LEGACY dead collar exactly as found in the owner's real database ----
  // (pre-fix shape: stock leg serialized with "stock":true, both option legs expired,
  // stored max_loss_cents=0 and pop_entry=1.0, no intent column value, snapshot '{}')
  const acct = (await api('GET', '/api/account')).account.id;
  const dead = isoDaysFromNow(-1);
  const legsJson = JSON.stringify([
    { action: 'BUY', ratio: 1, entryPrice: 416.97, stock: true },
    { action: 'BUY', type: 'PUT', strike: 417.5, expiration: dead, ratio: 1, entryPrice: 0.005, stock: false },
    { action: 'SELL', type: 'CALL', strike: 420, expiration: dead, ratio: 1, entryPrice: 3.10, stock: false }
  ]).replaceAll("'", "''");
  sqlite(`INSERT INTO trades(id,account_id,symbol,strategy,status,qty,legs_json,thesis,horizon,risk_mode,
      entry_underlying_cents,entry_net_premium_cents,max_loss_cents,max_profit_cents,breakevens_json,pop_entry,
      fees_open_cents,fees_close_cents,realized_pnl_cents,close_reason,entry_snapshot_json,is_live,
      created_at,closed_at,updated_at,intent,shares_locked)
    VALUES ('tr_legacydeadcollar','${acct}','TSLA','PROTECTIVE_COLLAR','ACTIVE',1,'${legsJson}',
      'neutral','week','conservative',41697,-4169100,0,30900,'[]',1.0,130,0,NULL,NULL,'{}',0,
      '2026-07-06T01:24:00Z',NULL,'2026-07-06T01:24:00Z',NULL,0);`);

  browser = await chromium.launch();
  page = await browser.newPage();
  page.on('pageerror', e => pageErrors.push(e.message));
  page.on('response', r => { if (r.status() >= 500) serverErrors.push(r.status() + ' ' + r.url()); });
  await page.goto(BASE + '/');
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
});

after(async () => {
  if (browser) await browser.close();
  if (server) server.kill();
});

const ROUTES = ['#/home', '#/research/AAPL', '#/recommend/scout', '#/recommend/manual',
  '#/ticket', '#/portfolio', '#/portfolio/closed', '#/backtest', '#/account', '#/status'];

for (const level of ['beginner', 'expert']) {
  test(`grown DB: every route renders clean at ${level}`, async () => {
    await page.click(`#level-switch button[data-level="${level}"]`);
    await page.waitForSelector('#app[data-ready="true"]');
    for (const route of ROUTES) {
      await go(route);
      assert.equal(await page.locator('#route-error').count(), 0, `route error boundary on ${route} at ${level}`);
      assertClean(`${route} @ ${level}`);
    }
  });
}

test('grown DB: every trade detail renders, including the legacy dead collar', async () => {
  const trades = [];
  for (const status of ['ACTIVE', 'CLOSED', 'EXPIRED', 'DELETED']) {
    const data = await api('GET', `/api/trades?status=${status}&size=50`);
    for (const t of data.trades) trades.push(t.id);
  }
  assert.ok(trades.includes('tr_legacydeadcollar'), 'legacy trade is listed');
  assert.ok(trades.length >= 4, 'seeded trades all present');
  for (const id of trades) {
    await go(`#/trade/${id}`);
    assert.equal(await page.locator('#route-error').count(), 0, `detail boundary for ${id}`);
    assertClean(`detail ${id}`);
  }
  // The dead legacy trade must still offer a way out (settle) rather than erroring
  await go('#/trade/tr_legacydeadcollar');
  assert.ok(await page.locator('#settle-btn').count(), 'settle available for dead legacy trade');
});

test('grown DB: locked shares and filters behave with real mixed data', async () => {
  await go('#/portfolio');
  assert.match(await page.textContent('#holdings-card'), /100 LOCKED/);
  await page.selectOption('#pf-intent', 'EXIT');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('.tbl tbody tr.clickable');
  await page.selectOption('#pf-intent', 'DIRECTIONAL');
  await page.waitForSelector('#app[data-ready="true"]');
  // NULL-intent legacy trades count as directional — they must not vanish from filters
  assert.match(await page.textContent('#app'), /TSLA/);
  await page.selectOption('#pf-intent', '');
  await page.waitForSelector('#app[data-ready="true"]');
  assertClean('portfolio filters');

  // Restore default level for other suites
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
});
