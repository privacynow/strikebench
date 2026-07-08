/*
 * ON-DEVICE ASSIST suite: exercises the browser AI layer against the REAL vendored models
 * (transformers.js + quantized ONNX served from /models). Two contracts under test:
 *   1. Free-text intake parses goal/thesis/symbol/$-limit and fills the form — numbers come
 *      only from the user's words; nothing is auto-submitted.
 *   2. Headline sentiment badges are display-only decoration on the news card.
 * The whole suite SKIPS (does not fail) when data/models is absent — the assist layer is
 * optional by design, and every other suite runs without it.
 *
 * Run:  MODELS_DIR=/abs/path/to/data/models node --test dom-assist.test.js
 */
'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { chromium } = require('playwright');

const PORT = process.env.PORT || '7191';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';
const MODELS = process.env.MODELS_DIR || path.resolve(__dirname, '../data/models');

let server, browser, page;
const available = fs.existsSync(path.join(MODELS, 'manifest.json'));

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { if ((await fetch(`${BASE}/api/status`)).ok) return; } catch (e) { /* not up */ }
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error('server did not start');
}

before(async () => {
  if (!available) return;
  const dbDir = fs.mkdtempSync(path.join(os.tmpdir(), 'strikebench-assist-'));
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, DB_PATH: path.join(dbDir, 'a.db'), FIXTURES_ONLY: 'true', MODELS_DIR: MODELS },
    stdio: 'ignore'
  });
  await waitForServer();
  browser = await chromium.launch();
  page = await browser.newPage();
  page.on('pageerror', e => { throw new Error('page error: ' + e.message); });
  await page.goto(BASE + '/');
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  if (await page.locator('#welcome-skip').count()) {
    await page.click('#welcome-skip');
    await page.waitForSelector('#app[data-ready="true"]');
  }
});

after(async () => {
  if (browser) await browser.close();
  if (server) server.kill();
});

test('models are served from disk with hard caching, never from the jar', { skip: !available }, async () => {
  const r = await fetch(BASE + '/models/manifest.json');
  assert.equal(r.status, 200);
  assert.match(r.headers.get('cache-control') || '', /immutable/);
  const m = await r.json();
  assert.equal(m.models.zeroshot.id, 'nli-deberta-v3-xsmall'); // the ONE model — Apache-2.0, distribution-clean
});

test('free-text intake: model classifies, regex extracts, human confirms', { skip: !available }, async () => {
  await page.evaluate(() => { location.hash = '#/recommend/manual'; });
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('#assist-intake', { timeout: 20000 });
  await page.fill('#assist-text',
    'I think TSLA drops hard over the next month, but I do not want to lose more than $400');
  await page.click('#assist-parse');
  // First run compiles WASM + loads the 87MB zero-shot model from localhost — be generous
  await page.waitForSelector('#assist-chips .chip', { timeout: 240000 });
  const chips = await page.textContent('#assist-chips');
  assert.match(chips, /TSLA/);
  assert.match(chips, /Trade a view|DIRECTIONAL/);
  assert.match(chips, /bearish/);
  assert.match(chips, /\$400\.00/);
  assert.match(chips, /month/);
  // Apply fills the visible form — nothing is submitted on our behalf
  await page.click('#assist-apply');
  assert.equal(await page.inputValue('#rec-symbol'), 'TSLA');
  assert.equal(await page.inputValue('#rec-thesis'), 'bearish');
  assert.equal(await page.inputValue('#rec-f-maxloss'), '400');
  assert.equal(await page.locator('#rec-results .candidate').count(), 0, 'no auto-submit, ever');
});

test('keyword goals beat the model: "buy some more" = Buy at a discount, lowercase ticker', { skip: !available }, async () => {
  // The exact user-reported failure: a bearish sentence about ADDING shares was read as
  // "Earn income · 21%" and Apply changed nothing. Now the user's own verbs win, the chip
  // quotes them, lowercase "qqq" resolves against the universe, and Apply visibly fills.
  await page.evaluate(() => { location.hash = '#/trade/discover'; });
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('#assist-text', { timeout: 20000 });
  await page.fill('#assist-text', 'I think qqq will go down further, I want to buy some more');
  await page.click('#assist-parse');
  await page.waitForSelector('#assist-chips .chip', { timeout: 240000 });
  const chips = await page.textContent('#assist-chips');
  assert.match(chips, /Buy at a discount/);
  assert.match(chips, /your words/);
  assert.match(chips, /buy some more/);
  assert.match(chips, /QQQ/);
  assert.doesNotMatch(chips, /Earn income/);
  await page.click('#assist-apply');
  assert.equal(await page.inputValue('#rec-symbol'), 'QQQ');
  assert.ok(await page.locator('#intent-choices .choice[data-intent="ACQUIRE"].selected').count(),
    'the goal chip is actually applied');
  assert.ok(await page.locator('#rec-target').isVisible(), 'the buy-price question appears');
  assert.equal(await page.locator('#rec-results .candidate').count(), 0, 'still no auto-submit');
});

test('news sentiment badges: real model output, display-only', { skip: !available }, async () => {
  await page.evaluate(() => { location.hash = '#/research/AAPL'; });
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('#news-card', { timeout: 30000 });
  // The zero-shot model scores headlines (loads on first use); badges prove end-to-end inference
  await page.waitForSelector('#news-card .assist-sent', { timeout: 240000 });
  const badges = await page.locator('#news-card .assist-sent').allTextContents();
  assert.ok(badges.length >= 2, 'multiple headlines scored');
  assert.ok(badges.every(b => /^(POSITIVE|NEGATIVE|NEUTRAL) \d+%$/.test(b)), 'every badge = valid label + confidence');
  // Pin the ROBUST case only: "Apple beats expectations…" must score positive. (Model
  // verdicts on ambiguous headlines are opinions — pin the contract, not opinions.)
  const rows = await page.evaluate(() =>
    Array.from(document.querySelectorAll('#news-card .status-item')).map(r =>
      ((r.querySelector('.assist-sent') || {}).textContent || '') + '|' + r.querySelector('a').textContent));
  const beats = rows.find(r => /beats expectations/.test(r));
  assert.ok(beats && /POSITIVE/.test(beats), 'the earnings-beat headline scores positive: ' + beats);
  assert.match(await page.textContent('#news-card .assist-note'), /display only/i);
});
