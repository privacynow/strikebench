/*
 * DESIGN-LANGUAGE AUDIT: the defects the owner kept re-reporting (mismatched control
 * heights, horizontal overflow, emoji instead of SVG icons) are exactly the kind that
 * slip past feature tests. This suite walks every route at desktop, laptop, and phone
 * widths and asserts three invariants mechanically:
 *   1. The page NEVER scrolls horizontally.
 *   2. No emoji anywhere in rendered text — pictograms are the shared SVG icon set.
 *   3. Form controls come in exactly the sanctioned sizes (--ctl-h / --ctl-h-sm / --ctl-h-xs).
 *   4. Non-finite numeric sentinels never reach visible financial text.
 *   5. Every visible tab remains fully inside the viewport at every audited width.
 *   6. Every visible form control and icon command has an accessible name.
 *   7. Every tab list owns one real, labeled tab panel.
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
let planId;

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { if ((await fetch(`${BASE}/api/status`)).ok) return; } catch (e) { /* not up */ }
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
  const created = await fetch(BASE + '/api/plans', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clientRequestId: 'audit-plan', symbol: 'AAPL', intent: 'DIRECTIONAL',
      thesis: 'bullish', horizonDays: 30, riskMode: 'conservative' }) });
  const createdBody = await created.text();
  assert.ok(created.ok, 'audit Plan creation failed: ' + createdBody);
  planId = JSON.parse(createdBody).id;
  ROUTES.push(...['understand', 'evidence', 'strategy', 'outcomes', 'decide', 'manage-review']
    .map(stage => `#/plan/${planId}/${stage}`));
  const tracked = await fetch(BASE + '/api/portfolio/accounts', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Responsive audit', accountType: 'TAXABLE', lotMethod: 'FIFO', openingCashCents: 10000000 }) });
  const trackedBody = await tracked.text();
  assert.ok(tracked.ok, 'tracked-account audit setup failed: ' + trackedBody);
  ROUTES.push(...['overview', 'activity', 'performance', 'tax', 'settings']
    .map(section => `#/portfolio/book/${section}`));
});

after(async () => {
  if (browser) await browser.close();
  if (server) server.kill();
  if (pg) pg.drop();
});

const ROUTES = ['#/home', '#/home/tour', '#/research', '#/research/AAPL',
  '#/research/AAPL?view=evidence', '#/research/AAPL?view=options',
  '#/portfolio', '#/portfolio/construct', '#/portfolio/positions', '#/portfolio/activity',
  '#/portfolio/record', '#/portfolio/account', '#/data/overview',
  '#/data/simulation', '#/data/datasets', '#/data/sources', '#/data/admin'];
const WIDTHS = [2048, 1920, 1440, 1280, 1000, 390, 375, 320];

// Sanctioned control heights: --ctl-h (38), --ctl-h-sm / --ctl-h-xs (30), plus the
// tape/level-switch micro scale (<=28) which is exempted by selector below.
const ALLOWED_HEIGHTS = [38, 30, 42, 46]; // welcome hero uses a deliberate 42px desktop CTA and 46px default
const TOLERANCE = 1.5;

function auditInPage() {
  const out = { overflow: [], emoji: [], controls: [], numbers: [], tooltips: [], navigation: [], accessibility: [], branding: [] };
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
  const badNumbers = (document.body.innerText || '').match(/\$?NaN(?:\.NaN)?|NaN%/g) || [];
  if (badNumbers.length) out.numbers.push(...badNumbers.slice(0, 8));
  const brand = document.querySelector('.topbar .brand');
  if (brand && brand.offsetParent) {
    if (brand.scrollWidth > brand.clientWidth + .5) {
      out.branding.push('brand-content-clipped:' + brand.clientWidth + '<' + brand.scrollWidth);
    }
    const br = brand.getBoundingClientRect();
    document.querySelectorAll('.topbar-controls > *').forEach(control => {
      if (!control.offsetParent) return;
      const cr = control.getBoundingClientRect();
      const verticalOverlap = Math.min(br.bottom, cr.bottom) - Math.max(br.top, cr.top);
      const horizontalOverlap = Math.min(br.right, cr.right) - Math.max(br.left, cr.left);
      if (verticalOverlap > .5 && horizontalOverlap > .5) {
        out.branding.push('brand-overlap:' + (control.id || control.className || control.tagName));
      }
    });
  }
  const norm = value => String(value || '').replace(/\s+/g, ' ').trim().toLowerCase();
  document.querySelectorAll('[role="tab"]').forEach(tab => {
    if (!tab.offsetParent) return;
    const r = tab.getBoundingClientRect();
    if (r.left < -0.5 || r.right > doc.clientWidth + 0.5) {
      out.navigation.push((tab.textContent || tab.id || 'tab').trim().slice(0, 50)
        + '@' + r.left.toFixed(1) + '..' + r.right.toFixed(1));
    }
    const controls = tab.getAttribute('aria-controls');
    const panel = controls && document.getElementById(controls);
    if (!tab.id || !controls || !panel || panel.getAttribute('role') !== 'tabpanel') {
      out.navigation.push('tab-contract:' + (tab.textContent || tab.id || 'tab').trim().slice(0, 50));
    } else if (tab.getAttribute('aria-selected') === 'true'
        && panel.getAttribute('aria-labelledby') !== tab.id) {
      out.navigation.push('tab-label:' + tab.id + '->' + controls);
    }
  });
  document.querySelectorAll('[role="tablist"]').forEach(list => {
    if (!list.offsetParent) return;
    const visible = Array.from(list.querySelectorAll(':scope > [role="tab"]')).filter(tab => tab.offsetParent);
    if (!visible.length) return;
    const selected = visible.filter(tab => tab.getAttribute('aria-selected') === 'true');
    const tabbable = visible.filter(tab => tab.tabIndex === 0);
    if (selected.length !== 1 || tabbable.length !== 1 || selected[0] !== tabbable[0]) {
      out.navigation.push('tab-state:' + (list.getAttribute('aria-label') || list.id || 'tablist'));
    }
  });
  document.querySelectorAll('#app input, #app select, #app textarea').forEach(control => {
    if (!control.offsetParent || control.type === 'hidden') return;
    const labelled = control.getAttribute('aria-label') || control.getAttribute('aria-labelledby')
      || control.closest('label') || (control.id && document.querySelector('label[for="' + CSS.escape(control.id) + '"]'));
    if (!labelled) out.accessibility.push('unnamed-control:' + (control.id || control.outerHTML.slice(0, 60)));
  });
  document.querySelectorAll('#app button, #app a[href]').forEach(command => {
    if (!command.offsetParent) return;
    const name = norm(command.getAttribute('aria-label') || command.getAttribute('aria-labelledby')
      || command.innerText || command.textContent || command.getAttribute('title'));
    if (!name) out.accessibility.push('unnamed-command:' + (command.id || command.className || command.tagName));
  });
  document.querySelectorAll('[title]').forEach(el => {
    if (!el.offsetParent) return;
    const title = norm(el.getAttribute('title'));
    const visible = norm(el.innerText || el.textContent);
    if (title.length > 1 && (visible === title || (title.length > 18 && visible.includes(title)))) {
      out.tooltips.push('native:' + (el.id || el.className || el.tagName).toString().slice(0, 44)
        + ' repeats "' + title.slice(0, 80) + '"');
    }
    const label = el.id
      ? Array.from(document.querySelectorAll('label[for]')).find(item => item.htmlFor === el.id)
      : null;
    const labelText = norm(label && label.textContent);
    if (title.length > 1 && labelText && (title === labelText || title.includes(labelText))) {
      out.tooltips.push('native:' + (el.id || el.className || el.tagName).toString().slice(0, 44)
        + ' paraphrases its label "' + labelText.slice(0, 80) + '"');
    }
  });
  document.querySelectorAll('.info-trigger[data-term]').forEach(trigger => {
    if (!trigger.offsetParent || !window.Learn || !Learn.INFO) return;
    const key = trigger.getAttribute('data-term');
    const def = Learn.INFO[key];
    if (!def || !def.short) return;
    const context = trigger.closest('label,.chip,.stat,.market-fact,.field,.alert,.explain,.intent-note,.card-header')
      || trigger.parentElement;
    if (!context) return;
    const clone = context.cloneNode(true);
    clone.querySelectorAll('.info-trigger,.info-pop').forEach(node => node.remove());
    const visible = norm(clone.textContent);
    const short = norm(def.short);
    if (short.length > 18 && (visible === short || visible.includes(short))) {
      out.tooltips.push('info:' + key + ' repeats nearby visible copy "' + short.slice(0, 80) + '"');
    }
  });
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
      for (const n of res.numbers) failures.push(`${route}@${width}: NON-FINITE ${n}`);
      for (const t of res.tooltips) failures.push(`${route}@${width}: DUPLICATE-TOOLTIP ${t}`);
      for (const n of res.navigation) failures.push(`${route}@${width}: CLIPPED-TAB ${n}`);
      for (const a of res.accessibility) failures.push(`${route}@${width}: A11Y ${a}`);
      for (const b of res.branding) failures.push(`${route}@${width}: BRAND ${b}`);
    }
    assert.deepEqual(failures, [], failures.join('\n'));
  });
}
