/*
 * GROWN-DATABASE sweep: the fixture and live suites always start from a fresh DB, which is
 * exactly how the "works in tests, fails on my machine" class of bug slipped through. This
 * suite grows a database the way a real user would — shares, a share-covered call, a spread,
 * a closed trade, and a Plan replay — then walks EVERY route at EVERY experience level plus
 * every trade detail page. Any page JS error, any 5xx, or any route-level error boundary is
 * a hard failure.
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
const { freshDb } = require('./pgtest');

const PORT = process.env.PORT || '7171';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg;
let seededPlanId;
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

/** Creates a trade the way a real client does: preview, acknowledge the server's
 *  material-risk contract (R2), then create with the signed token attached. */
async function createTrade(body) {
  body = {
    ...body,
    fillNature: body.fillNature || 'PROPOSED',
    source: body.source || 'ANALYZE',
    legs: body.legs.map(leg => ({ multiplier: 100, positionEffect: 'OPEN', ...leg }))
  };
  const prev = await api('POST', '/api/trades/preview', body);
  if (prev.requiredAcks && prev.requiredAcks.length) {
    body = { ...body, acknowledgedRisks: prev.requiredAcks.map(a => a.id), ackToken: prev.ackToken };
  }
  return api('POST', '/api/trades', body);
}

async function transformPracticeTrade(tradeId, action) {
  const request = { source: 'PRACTICE_TRADE', sourceId: tradeId, action };
  const preview = await api('POST', '/api/position-transformations/preview', request);
  return api('POST', '/api/position-transformations/apply', {
    ...request, previewToken: preview.previewToken
  });
}

function isoDaysFromNow(days) {
  return new Date(Date.now() + days * 86400000).toISOString().slice(0, 10);
}

async function go(hash) {
  await page.evaluate(h => {
    document.getElementById('app').setAttribute('data-ready', 'false');
    if (window.location.hash === h) return App.render();
    window.location.hash = h;
  }, hash);
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
}

function assertClean(label) {
  assert.deepEqual(pageErrors, [], `${label}: page JS errors: ${pageErrors.join(' | ')}`);
  assert.deepEqual(serverErrors, [], `${label}: server 5xx: ${serverErrors.join(' | ')}`);
}

before(async () => {
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' },
    stdio: 'ignore'
  });
  await waitForServer();

  // ---- Grow the account the way a user would, entirely through the API ----
  await api('POST', '/api/positions/buy', { symbol: 'AAPL', shares: 200 });

  // A share-covered call via the EXIT intent (locks 100 of the 200)
  const incomePlan = await api('POST', '/api/plans', {
    clientRequestId: 'seeded-income-plan', symbol: 'AAPL', intent: 'EXIT',
    thesis: 'neutral', horizonDays: 30, riskMode: 'balanced'
  });
  const rec = await api('POST', `/api/plans/${incomePlan.id}/strategy/run`, {});
  const cc = rec.strategy.result.candidates.find(c => c.strategy === 'COVERED_CALL');
  assert.ok(cc, 'covered-call candidate for seeding');
  await createTrade({
    symbol: 'AAPL', strategy: 'COVERED_CALL', qty: 1, intent: 'exit', useHeldShares: true,
    legs: cc.legs.map(l => ({ action: l.action, type: l.type, strike: l.strike, expiration: l.expiration, ratio: 1 }))
  });

  // A plain credit spread, then a second one that gets closed (CLOSED-tab data)
  const exps = (await api('GET', '/api/research/AAPL/expirations')).expirations;
  const spread = qty => ({
    symbol: 'AAPL', strategy: 'CREDIT_PUT_SPREAD', qty, intent: 'directional',
    legs: [
      { action: 'SELL', type: 'PUT', strike: '242.5', expiration: exps[3], ratio: 1 },
      { action: 'BUY', type: 'PUT', strike: '240', expiration: exps[3], ratio: 1 }
    ],
    thesis: 'bullish', horizon: 'month', riskMode: 'balanced'
  });
  await createTrade(spread(1));
  const toClose = (await createTrade(spread(1))).trade.id;
  await transformPracticeTrade(toClose, 'CLOSE');

  // A persisted Plan replay. Outcomes own historical tests; there is no standalone backtest lane.
  const replayPlan = await api('POST', '/api/plans', {
    clientRequestId: 'seeded-replay-plan', symbol: 'AAPL', intent: 'EXIT',
    thesis: 'neutral', horizonDays: 30, riskMode: 'balanced'
  });
  seededPlanId = replayPlan.id;
  const replayCompetition = await api('POST', `/api/plans/${replayPlan.id}/strategy/run`, {});
  const replayCandidate = replayCompetition.strategy.result.candidates.find(c => c.strategy === 'COVERED_CALL')
    || replayCompetition.strategy.result.candidates[0];
  const replaySelection = await api('PUT', `/api/plans/${replayPlan.id}/strategy/select`, {
    candidateId: replayCandidate.id, expectedVersion: replayCompetition.plan.version
  });
  await api('POST', `/api/plans/${replayPlan.id}/outcomes/backtest`, {
    expectedVersion: replaySelection.plan.version, engine: 'single',
    from: isoDaysFromNow(-120), to: isoDaysFromNow(-1), targetDte: 30
  });

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
  if (pg) pg.drop();
});

function routes() {
  return ['#/home', '#/research', '#/research/AAPL', '#/portfolio', '#/portfolio/construct',
    '#/portfolio/positions', '#/portfolio/closed', '#/portfolio/activity', '#/portfolio/record',
    '#/portfolio/account', '#/data/overview',
    ...['understand', 'evidence', 'strategy', 'outcomes', 'decide', 'manage-review']
      .map(stage => `#/plan/${seededPlanId}/${stage}`)];
}

for (const level of ['beginner', 'expert']) {
  test(`grown DB: every route renders clean at ${level}`, async () => {
    await page.click(`#level-switch button[data-level="${level}"]`);
    await page.waitForSelector('#app[data-ready="true"]');
    for (const route of routes()) {
      await go(route);
      assert.equal(await page.locator('#route-error').count(), 0, `route error boundary on ${route} at ${level}`);
      assertClean(`${route} @ ${level}`);
    }
  });
}

test('grown DB: every current trade detail renders', async () => {
  const trades = [];
  for (const status of ['ACTIVE', 'CLOSED', 'EXPIRED', 'DELETED']) {
    const data = await api('GET', `/api/trades?status=${status}&size=50`);
    for (const t of data.trades) trades.push(t.id);
  }
  assert.ok(trades.length >= 3, 'seeded trades all present');
  for (const id of trades) {
    await go(`#/portfolio/trade/${id}`);
    assert.equal(await page.locator('#route-error').count(), 0, `detail boundary for ${id}`);
    assertClean(`detail ${id}`);
  }
});

test('grown DB: locked shares and filters behave with real mixed data', async () => {
  await go('#/portfolio/positions');
  assert.match(await page.textContent('#holdings-card'), /100 LOCKED/);
  await page.selectOption('#pf-intent', 'EXIT');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('.tbl tbody tr.clickable');
  assert.match(await page.textContent('#app'), /Covered call/,
    'the exit filter retains the share-backed target-sale position');
  assert.doesNotMatch(await page.textContent('#app'), /Credit put spread/,
    'the exit filter does not blend directional positions');
  await page.selectOption('#pf-intent', 'DIRECTIONAL');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.match(await page.textContent('#app'), /Credit put spread/,
    'the directional filter retains current-contract directional positions');
  assert.doesNotMatch(await page.textContent('#app'), /Covered call/,
    'the directional filter does not blend target-sale positions');
  await page.selectOption('#pf-intent', '');
  await page.waitForSelector('#app[data-ready="true"]');
  assertClean('portfolio filters');

  // Restore default level for other suites
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#app[data-ready="true"]');
});

/*
 * Alert center (spec 10.1): a protocol breach and an expiring position drive the Desk's
 * needs-attention ordering (urgent first), the nav badge shows the alert count, and the
 * breach row deep-links into the owning Plan's Manage band.
 */
test('alert center: Desk ordering, badge count, and the Manage-band deep link', async () => {
  // A plan-linked covered call, decided through the real Decide flow (plan_link ENTRY).
  await api('POST', '/api/positions/buy', { symbol: 'AAPL', shares: 100 });
  const alertPlan = await api('POST', '/api/plans', {
    clientRequestId: 'seeded-alert-plan', symbol: 'AAPL', intent: 'EXIT',
    thesis: 'neutral', horizonDays: 30, riskMode: 'balanced'
  });
  const run = await api('POST', `/api/plans/${alertPlan.id}/strategy/run`, {});
  const candidate = run.strategy.result.candidates.find(c => c.strategy === 'COVERED_CALL');
  assert.ok(candidate, 'covered-call candidate for the alert seed');
  const selected = await api('PUT', `/api/plans/${alertPlan.id}/strategy/select`, {
    candidateId: candidate.id, expectedVersion: run.plan.version
  });
  const reviewed = await api('POST', `/api/plans/${alertPlan.id}/decision/preview`, {
    expectedVersion: selected.plan.version, qty: 1
  });
  const order = { expectedVersion: selected.plan.version, qty: 1,
    proposedNetCents: reviewed.order.proposedNetCents,
    acknowledgedRisks: (reviewed.requiredAcks || []).map(a => a.id) };
  if (reviewed.ackToken) order.ackToken = reviewed.ackToken;
  const opened = await api('POST', `/api/plans/${alertPlan.id}/decision/trade`, order);

  // Force the stop breach deterministically: shrink the recorded credit so the live buyback
  // cost is far past 2x of it, then refresh the mark through the Manage band's own endpoint.
  pg.sql(`UPDATE trades SET entry_net_premium_cents = 10 WHERE id = '${opened.trade.id}'`);
  const planNow = await api('GET', `/api/plans/${alertPlan.id}`);
  await api('POST', `/api/plans/${alertPlan.id}/manage/refresh`, { expectedVersion: planNow.version });

  // An expiring position: the nearest listed expiration is within the week. Strikes come
  // from the live chain (executable both sides) so the seed never fights the book.
  const exps = (await api('GET', '/api/research/AAPL/expirations')).expirations;
  const chain = await api('GET', `/api/research/AAPL/chain?expiration=${exps[0]}`);
  const spot = Number(chain.underlyingPrice);
  const puts = (chain.puts || []).filter(q => Number(q.bid) > 0 && Number(q.ask) > 0)
    .sort((a, b) => Number(b.strike) - Number(a.strike));
  assert.ok(puts.length >= 2 && Number.isFinite(spot), 'executable near-dated puts exist in the fixture chain');
  const shortPut = puts.find(q => Number(q.strike) < spot); // out of the money: an honest small credit
  const longPut = puts.find(q => Number(q.strike) < Number(shortPut.strike));
  assert.ok(shortPut && longPut, 'a defined-risk near-dated put spread is available');
  await createTrade({
    symbol: 'AAPL', strategy: 'CREDIT_PUT_SPREAD', qty: 1, intent: 'directional',
    legs: [
      { action: 'SELL', type: 'PUT', strike: String(shortPut.strike), expiration: exps[0], ratio: 1 },
      { action: 'BUY', type: 'PUT', strike: String(longPut.strike), expiration: exps[0], ratio: 1 }
    ],
    thesis: 'bullish', horizon: 'week', riskMode: 'balanced'
  });

  const alertSet = await api('GET', '/api/alerts');
  assert.ok(alertSet.counts.urgent >= 1, 'the protocol stop breach is urgent: ' + JSON.stringify(alertSet.counts));
  assert.ok(alertSet.counts.total >= 2, 'breach + expiry both present');
  assert.equal(alertSet.exDividend.available, false, 'ex-dividend honesty: unavailable, never guessed');
  const breach = alertSet.alerts.find(a => a.kind === 'PROTOCOL_BREACH' && a.severity === 'URGENT');
  assert.ok(breach, 'urgent protocol breach alert exists');
  assert.equal(breach.planId, alertPlan.id, 'the breach maps to its owning Plan');
  assert.equal(breach.deepLink, `#/plan/${alertPlan.id}/manage-review`);
  assert.equal(alertSet.alerts[0].severity, 'URGENT', 'API ordering is severity-first');

  // The Desk: rail ordered urgent-first, badge carrying the count.
  await go('#/home');
  await page.waitForSelector('#home-plan-library .home-plan-compact-list', { timeout: 30000 });
  const desk = await page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#home-plan-library .home-plan-compact-list > article'));
    const badge = document.querySelector('.topbar .nav-alert-badge');
    return {
      severities: rows.map(r => r.getAttribute('data-alert-severity') || 'none'),
      firstHeadline: rows.length ? (rows[0].querySelector('.home-alert-headline') || {}).textContent || '' : '',
      badgeHidden: !badge || badge.hidden,
      badgeText: badge ? badge.textContent : ''
    };
  });
  assert.equal(desk.severities[0], 'urgent', 'the urgent breach leads the rail: ' + JSON.stringify(desk.severities));
  const rank = s => ({ urgent: 3, attention: 2, info: 1, none: 0 })[s];
  for (let i = 1; i < desk.severities.length; i++) {
    assert.ok(rank(desk.severities[i - 1]) >= rank(desk.severities[i]),
      'rail ordering is severity-first: ' + JSON.stringify(desk.severities));
  }
  assert.match(desk.firstHeadline, /past your loss line/, 'the breach headline renders on its row');
  assert.equal(desk.badgeHidden, false, 'the Desk nav badge is visible');
  assert.equal(desk.badgeText, String(alertSet.counts.total), 'the badge carries the alert count');

  // The breach row's headline deep-links into the owning Plan's Manage band.
  await page.click(`#home-plan-library .home-alert-headline[data-alert-id="${breach.id}"]`);
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  assert.equal(await page.evaluate(() => window.location.hash), `#/plan/${alertPlan.id}/manage-review`,
    'the deep link lands on the Manage band');
  assertClean('alert center desk');
});
