/*
 * BOOK-RISK LANE (§10.2): seeds a battery-scenario-6 shaped multi-account tracked book
 * (semiconductor short premium + covered call, an 8/07 cluster, an INTC sell-then-rebuy churn
 * pair, a JEPQ collision) through the real API against a fresh database, opens the Book risk
 * tab at both experience levels, and asserts the headline numbers and labels render with
 * units and honest coverage disclosures. The fixture market has no OBSERVED marks for a
 * tracked external account, so this also pins the degradation story: disclosures, never zeros.
 *
 * Run:  node --test dom-bookrisk.test.js
 *       node --test --test-name-pattern "book risk" dom-bookrisk.test.js
 */
'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const path = require('node:path');
const { chromium } = require('playwright');
const { freshDb } = require('./pgtest');

const PORT = process.env.PORT || '7191';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg;
const pageErrors = [];

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

function tradeTx(occurredAt, externalRef, leg) {
  return {
    occurredAt, eventType: 'TRADE', fillNature: 'EXECUTED', cashAmountCents: null,
    feesCents: 0, taxCategory: null, source: 'MANUAL', externalRef, notes: null,
    legs: [{ section1256: false, ...leg }]
  };
}

async function go(hash) {
  await page.evaluate(h => {
    document.getElementById('app').setAttribute('data-ready', 'false');
    if (window.location.hash === h) return App.render();
    window.location.hash = h;
  }, hash);
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  await page.waitForTimeout(300);
}

async function setLevel(level) {
  await page.evaluate(l => Learn.setLevel(l), level);
}

before(async () => {
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' },
    stdio: 'ignore'
  });
  await waitForServer();

  // Two tracked accounts shaped like the Vanguard month (battery scenario 6).
  const ira = await api('POST', '/api/portfolio/accounts',
    { name: 'Vanguard IRA', accountType: 'TRADITIONAL_IRA', lotMethod: 'FIFO', openingCashCents: 25000000 });
  await api('POST', `/api/portfolio/accounts/${ira.id}/objective`,
    { objective: 'ACCUMULATE', direction: 'BULLISH', assignmentPreference: 'ACCEPT' });
  await api('POST', `/api/portfolio/accounts/${ira.id}/transactions`, tradeTx('2026-07-01', 'br-1', {
    instrumentType: 'OPTION', action: 'SELL', positionEffect: 'OPEN', symbol: 'NVDA',
    optionType: 'PUT', strike: 950, expiration: '2026-08-07', quantity: 1, multiplier: 100, price: 30 }));
  await api('POST', `/api/portfolio/accounts/${ira.id}/transactions`, tradeTx('2026-07-01', 'br-2', {
    instrumentType: 'STOCK', action: 'BUY', positionEffect: 'OPEN', symbol: 'NVDA',
    quantity: 100, multiplier: 1, price: 900 }));
  await api('POST', `/api/portfolio/accounts/${ira.id}/transactions`, tradeTx('2026-07-02', 'br-3', {
    instrumentType: 'OPTION', action: 'SELL', positionEffect: 'OPEN', symbol: 'NVDA',
    optionType: 'CALL', strike: 1000, expiration: '2026-08-07', quantity: 2, multiplier: 100, price: 25 }));
  await api('POST', `/api/portfolio/accounts/${ira.id}/transactions`, tradeTx('2026-07-02', 'br-4', {
    instrumentType: 'OPTION', action: 'SELL', positionEffect: 'OPEN', symbol: 'AMD',
    optionType: 'PUT', strike: 160, expiration: '2026-08-07', quantity: 1, multiplier: 100, price: 4 }));
  await api('POST', `/api/portfolio/accounts/${ira.id}/transactions`, tradeTx('2026-07-03', 'br-5', {
    instrumentType: 'OPTION', action: 'SELL', positionEffect: 'OPEN', symbol: 'MU',
    optionType: 'PUT', strike: 120, expiration: '2026-08-07', quantity: 1, multiplier: 100, price: 2.5 }));

  const taxable = await api('POST', '/api/portfolio/accounts',
    { name: 'Taxable brokerage', accountType: 'TAXABLE', lotMethod: 'FIFO', openingCashCents: 20000000 });
  await api('POST', `/api/portfolio/accounts/${taxable.id}/transactions`, tradeTx('2026-05-05', 'br-6', {
    instrumentType: 'STOCK', action: 'BUY', positionEffect: 'OPEN', symbol: 'INTC',
    quantity: 100, multiplier: 1, price: 100 }));
  await api('POST', `/api/portfolio/accounts/${taxable.id}/transactions`, tradeTx('2026-06-01', 'br-7', {
    instrumentType: 'STOCK', action: 'BUY', positionEffect: 'OPEN', symbol: 'JEPQ',
    quantity: 100, multiplier: 1, price: 50 }));
  await api('POST', `/api/portfolio/accounts/${taxable.id}/transactions`, tradeTx('2026-06-16', 'br-8', {
    instrumentType: 'OPTION', action: 'SELL', positionEffect: 'OPEN', symbol: 'JEPQ',
    optionType: 'CALL', strike: 55, expiration: '2026-08-07', quantity: 1, multiplier: 100, price: 1 }));
  await api('POST', `/api/portfolio/accounts/${taxable.id}/transactions`, tradeTx('2026-06-20', 'br-9', {
    instrumentType: 'STOCK', action: 'SELL', positionEffect: 'CLOSE', symbol: 'INTC',
    quantity: 50, multiplier: 1, price: 118 }));
  await api('POST', `/api/portfolio/accounts/${taxable.id}/transactions`, tradeTx('2026-06-27', 'br-10', {
    instrumentType: 'STOCK', action: 'BUY', positionEffect: 'OPEN', symbol: 'INTC',
    quantity: 50, multiplier: 1, price: 140 }));

  browser = await chromium.launch();
  page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  page.on('pageerror', e => pageErrors.push(String(e)));
  await page.goto(BASE + '/');
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  const skip = await page.locator('#welcome-skip').count();
  if (skip) { await page.click('#welcome-skip'); await page.waitForSelector('#app[data-ready="true"]'); }
});

after(async () => {
  if (browser) await browser.close();
  if (server) server.kill();
  if (pg) pg.drop();
});

test('the book-risk API serves the lane with disclosures, never fabricated numbers', async () => {
  const lane = await api('GET', '/api/portfolio/book-risk');
  assert.equal(lane.accounts.length, 2, 'both active tracked accounts render in the lane');
  assert.match(lane.basis, /from open tracked lots directly/i);
  const ira = lane.accounts.find(a => a.name === 'Vanguard IRA');
  // The fixture market holds no OBSERVED marks for an external tracked book: every option lot
  // must be disclosed as unmarked, never valued from Demo data or silently dropped.
  assert.equal(ira.greeks.optionLots, 4);
  assert.equal(ira.greeks.markedOptionLots, 0);
  assert.equal(ira.greeks.betaWeightedDollarDeltaCents, null);
  assert.match(ira.greeks.greekCoverage, /0 of 4 option lots; 4 lack observed marks/);
  assert.match(ira.stress.sentence, /cannot be stressed — no observed underlying mark/);
  assert.equal(ira.stress.unmarkedObligationCents, 12300000, 'NVDA 950 + AMD 160 + MU 120 strike cash');
  const cluster = ira.expiries.rows.find(r => r.date === '2026-08-07');
  assert.equal(cluster.notionalCents, 32300000, '950x100 + 1000x200 + 160x100 + 120x100');
  assert.equal(cluster.flagged, true, 'four lots on one session is a cluster');
  assert.match(ira.expiries.clusterNote, /\$323,000\.00 notional expires 2026-08-07/);
  assert.equal(ira.themes.rows[0].label, 'Semiconductors');
  assert.match(ira.themes.classificationLabel, /classification, not measured correlation/);
  assert.equal(ira.contradictions.length, 1, 'short puts vs covered calls inside one theme');
  assert.match(ira.contradictions[0].message, /both long \(short puts\) and short \(covered calls\)/);
  assert.match(ira.contradictions[0].message, /despite a bullish stated goal .*revision 1/);

  const taxable = lane.accounts.find(a => a.name === 'Taxable brokerage');
  assert.equal(taxable.churn.pairs.length, 1);
  assert.equal(taxable.churn.pairs[0].costCents, 110000);
  assert.match(taxable.churn.pairs[0].message,
    /You sold INTC at \$118\.00 and rebought at \$140\.00 — the round trip cost \$1,100\.00 on 50 shares\./);
  assert.equal(taxable.collisions.length, 1, 'JEPQ covered-call fund collision');
  assert.match(taxable.collisions[0], /JEPQ is a covered-call fund/);

  assert.ok(lane.crossAccount, 'two accounts produce a cross-account subtotal');
  assert.match(lane.crossAccount.stressNote, /never fungible across accounts/);
  assert.ok(lane.practice, 'practice lane rides side-by-side');
  assert.match(lane.practice.basis, /never numerically netted/);
});

test('the book risk tab renders headline numbers and labels with units at expert level', async () => {
  await setLevel('expert');
  await go('#/portfolio/book/risk');
  await page.waitForSelector('.book-risk-account', { timeout: 15000 });
  const text = await page.locator('#portfolio-book-panel').innerText();

  // Risk header: vocabulary labels and units, with honest unavailability (no fabricated zeros).
  assert.match(text, /Beta-weighted dollar delta/i);
  assert.match(text, /Net dollar delta \(unweighted\)/i);
  assert.match(text, /VEGA/i); assert.match(text, /\/ vol pt/);
  assert.match(text, /GAMMA/i); assert.match(text, /1% underlying move/);
  assert.match(text, /Delta aggregated over 0 of 4 option lots; 4 lack observed marks\./);

  // Stressed assignment block with dollars on both sides of the comparison.
  assert.match(text, /If these names fall 10%/);
  assert.match(text, /\$168,650\.00 recorded cash/);
  assert.match(text, /\$123,000\.00/);

  // Expiry calendar rows carry the date, dollar notional, and the cluster flag.
  assert.match(text, /2026-08-07/);
  assert.match(text, /\$323,000\.00 notional expires 2026-08-07/);
  assert.match(text, /CLUSTER/);

  // Theme concentration is labeled a classification; the contradiction and collision render.
  assert.match(text, /Semiconductors/);
  assert.match(text, /classification, not measured correlation/);
  assert.match(text, /both long \(short puts\) and short \(covered calls\)/);
  assert.match(text, /JEPQ is a covered-call fund/);

  // Churn line with exact dollars and shares.
  assert.match(text, /You sold INTC at \$118\.00 and rebought at \$140\.00/);
  assert.match(text, /\$1,100\.00 on 50 shares/);

  // Cross-account subtotal + practice lane side-by-side, never netted.
  assert.match(text, /All tracked accounts/);
  assert.match(text, /never fungible across accounts/);
  assert.match(text, /Practice account — side by side/i);
  assert.match(text, /never netted/i);

  assert.deepEqual(pageErrors, [], 'no page JS errors on the book risk tab');
});

test('beginner level keeps the same numbers with plainer framing and info coverage', async () => {
  await setLevel('beginner');
  await go('#/portfolio/book/risk');
  await page.waitForSelector('.book-risk-account', { timeout: 15000 });
  const text = await page.locator('#portfolio-book-panel').innerText();

  assert.match(text, /Beta-weighted dollar delta/i, 'labels survive the level switch (level = lens)');
  assert.match(text, /If these names fall 10%/);
  assert.match(text, /You sold INTC at \$118\.00 and rebought at \$140\.00/);
  assert.match(text, /classification, not measured correlation/);

  // "What does that mean?" coverage: the vocabulary terms open the level-aware info bubble.
  const term = page.locator('#portfolio-book-panel button.term', { hasText: 'Beta-weighted dollar delta' }).first();
  await term.click();
  await page.waitForSelector('#info-pop', { timeout: 5000 });
  const bubble = await page.locator('#info-pop').innerText();
  assert.match(bubble, /market|beta/i, 'the bubble explains beta weighting in plain language');
  await page.keyboard.press('Escape');

  assert.deepEqual(pageErrors, [], 'no page JS errors at beginner level');
});

test('the lane stays inside the viewport at narrow widths', async () => {
  await setLevel('expert');
  for (const width of [1100, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await go('#/portfolio/book/risk');
    await page.waitForSelector('.book-risk-account', { timeout: 15000 });
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    assert.ok(overflow <= 1, `no horizontal overflow at ${width}px (got ${overflow}px)`);
  }
  await page.setViewportSize({ width: 1600, height: 1000 });
  assert.deepEqual(pageErrors, [], 'no page JS errors at narrow widths');
});
