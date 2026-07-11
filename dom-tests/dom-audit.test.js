/*
 * DESIGN-LANGUAGE AUDIT: the defects the owner kept re-reporting (mismatched control
 * heights, horizontal overflow, emoji instead of SVG icons) are exactly the kind that
 * slip past feature tests. This suite walks every route at desktop, laptop, and phone
 * widths and asserts three invariants mechanically:
 *   1. The page NEVER scrolls horizontally.
 *   2. No emoji anywhere in rendered text — pictograms are the shared SVG icon set.
 *   3. Form controls come in exactly the sanctioned sizes (--ctl-h / --ctl-h-sm / --ctl-h-xs).
 *
 * Run:  node --test dom-audit.test.js
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

const PORT = process.env.PORT || '7181';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg;

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { if ((await fetch(`${BASE}/api/status`)).ok) return; } catch (e) { /* not up */ }
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error('server did not start');
}

async function go(hash) {
  await page.evaluate(h => { window.location.hash = h; }, hash);
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  await page.waitForTimeout(400); // async cards (explorer tiles, ladders) settle
}

before(async () => {
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' },
    stdio: 'ignore'
  });
  await waitForServer();
  browser = await chromium.launch();
  page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
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

const ROUTES = ['#/home', '#/home/tour', '#/research', '#/research/AAPL', '#/trade/discover',
  '#/trade/discover/manual', '#/trade/shape', '#/trade/place', '#/trade/verify',
  '#/portfolio', '#/portfolio/activity', '#/portfolio/account', '#/status',
  '#/data/simulation', '#/data/datasets', '#/data/sources', '#/data/admin'];
const WIDTHS = [2048, 1920, 1440, 1280, 1000, 390, 375, 320]; // include the wide desktop that exposed the trade-form spill

// Sanctioned control heights: --ctl-h (38), --ctl-h-sm / --ctl-h-xs (30), plus the
// tape/level-switch micro scale (<=28) which is exempted by selector below.
const ALLOWED_HEIGHTS = [38, 30, 42, 46]; // welcome hero uses a deliberate 42px desktop CTA and 46px default
const TOLERANCE = 1.5;

function auditInPage() {
  const out = { overflow: [], emoji: [], controls: [] };
  const doc = document.documentElement;
  if (doc.scrollWidth > doc.clientWidth + 2) {
    // Animated/local-scroll children can be thousands of pixels wide without widening the
    // document. Name the elements nearest the page edge instead; those are the constraints
    // that actually produce small whole-page overflows.
    const edge = [];
    document.querySelectorAll('body *').forEach(el => {
      const r = el.getBoundingClientRect();
      if (r.right > doc.clientWidth + .5 && r.right <= doc.clientWidth + 12 && r.left >= -12) {
        const cs = getComputedStyle(el);
        edge.push((el.id || el.className || el.tagName).toString().slice(0, 42)
          + '@' + r.left.toFixed(1) + '..' + r.right.toFixed(1)
          + '/w=' + cs.width + '/min=' + cs.minWidth + '/ox=' + cs.overflowX);
      }
    });
    out.overflow.push('page ' + doc.scrollWidth + '>' + doc.clientWidth + ' edge=' + edge.slice(0, 8).join(','));
  }
  const emojiRe = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}]/u;
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let n;
  while ((n = walker.nextNode())) {
    if (emojiRe.test(n.textContent)) {
      const p = n.parentElement;
      out.emoji.push((p.id || p.className || p.tagName).toString().slice(0, 40) + '::' + n.textContent.trim().slice(0, 20));
    }
  }
  document.querySelectorAll(
    '#app input:not([type=checkbox]):not([type=radio]), #app select, #app .btn, #app .goal-chip'
  ).forEach(el => {
    const h = el.getBoundingClientRect().height;
    if (h > 0 && !window.__allowedHeights.some(a => Math.abs(h - a) <= window.__tolerance)) {
      out.controls.push((el.id || el.className || el.tagName).toString().slice(0, 50) + '=' + h.toFixed(1) + 'px');
    }
  });
  return out;
}

for (const width of WIDTHS) {
  test(`design audit at ${width}px: no overflow, no emoji, one control scale`, async () => {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(([a, t]) => { window.__allowedHeights = a; window.__tolerance = t; },
      [ALLOWED_HEIGHTS, TOLERANCE]);
    const failures = [];
    for (const route of ROUTES) {
      await go(route);
      await page.evaluate(([a, t]) => { window.__allowedHeights = a; window.__tolerance = t; },
        [ALLOWED_HEIGHTS, TOLERANCE]);
      const res = await page.evaluate(auditInPage);
      for (const o of res.overflow) failures.push(`${route}@${width}: OVERFLOW ${o}`);
      for (const e of res.emoji) failures.push(`${route}@${width}: EMOJI ${e}`);
      for (const c of res.controls) failures.push(`${route}@${width}: CONTROL-HEIGHT ${c}`);
    }
    assert.deepEqual(failures, [], failures.join('\n'));
  });
}
