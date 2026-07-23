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
const WORKSPACE = BASE + '/workspace.html';
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, context, page, pg;
let planSequence = 0;

function latestMtime(root) {
  return fs.readdirSync(root, { withFileTypes: true }).reduce((latest, entry) => {
    const file = path.join(root, entry.name);
    return Math.max(latest, entry.isDirectory() ? latestMtime(file) : fs.statSync(file).mtimeMs);
  }, 0);
}

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


/** The Plan library lives in the Desk's archive drawer now — open it and wait for content. */
async function openPlanDrawer() {
  await go('#/home');
  await page.waitForSelector('#home-plan-drawer .xp-head');
  if (await page.locator('#home-plan-drawer .xp-head[aria-expanded="true"]').count() === 0) {
    await page.click('#home-plan-drawer .xp-head');
  }
  await page.waitForSelector('#plans-library');
}

/** Navigate to a hash route and wait for the view to finish rendering. */
async function go(hash) {
  await page.evaluate(h => {
    // A same-hash visit is still a navigation in the test contract. Force readiness
    // false before either path so waitForSelector can never observe the outgoing view.
    document.getElementById('app').setAttribute('data-ready', 'false');
    if (window.location.hash === h) return App.render();
    window.location.hash = h;
  }, hash);
  const renderedRoute = /^#\/research\/[^?]+\?view=/.test(hash) ? hash.split('?')[0] : hash;
  await page.waitForFunction(expected => window.location.hash === expected.hash
    && App._lastRenderedRoute === expected.renderedRoute
    && document.getElementById('app').getAttribute('data-ready') === 'true',
  { hash, renderedRoute }).catch(async error => {
      const state = await page.evaluate(() => ({ hash: location.hash,
        rendered: App._lastRenderedRoute, ready: document.getElementById('app')?.dataset.ready,
        route: document.getElementById('app')?.dataset.route,
        error: document.getElementById('route-error')?.textContent || null }));
      throw new Error('navigation did not settle for ' + hash + ': ' + JSON.stringify(state)
        + ' :: ' + error.message);
    });
}

async function captureSettled(name) {
  await page.waitForFunction(() => !document.querySelector('#toast-region .toast-message'), null, { timeout: 10000 });
  await page.evaluate(() => {
    window.scrollTo(0, 0);
    if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
  });
  await page.waitForTimeout(250); // keep production motion enabled and capture its settled state
  await page.screenshot({ path: path.join(__dirname, 'shots', name), fullPage: true });
}

/** Make a risk posture a real user-owned declaration through the visible canonical picker. */
async function chooseHeaderRisk(value) {
  const current = await page.locator('#risk-mode').evaluate(control => ({
    value: control.value, explicit: App.state.headerRiskExplicit
  }));
  if (current.value === value && current.explicit) return;
  // A test may deliberately clear declaration ownership while the presentation still shows
  // the prior value. Clicking an already-selected chip is correctly a no-op, so exercise a
  // different visible choice first and then the requested one. This keeps the declaration a
  // real user interaction instead of dispatching a synthetic change event.
  if (current.value === value && !current.explicit) {
    const alternate = value === 'balanced' ? 'conservative' : 'balanced';
    await page.click('#risk-mode');
    await page.locator(`#risk-mode-popover .choice-option[data-value="${alternate}"]`).click();
  }
  await page.click('#risk-mode');
  await page.locator(`#risk-mode-popover .choice-option[data-value="${value}"]`).click();
  await page.waitForFunction(() => App.state.headerRiskExplicit === true);
}

async function ensureExpanded(head) {
  if (await head.getAttribute('aria-expanded') !== 'true') await head.click();
  assert.equal(await head.getAttribute('aria-expanded'), 'true',
    'disclosure opens and exposes its controls');
}

async function assertNamedControls(scope = 'body') {
  const unnamed = await page.locator(scope).evaluate(root => {
    function visible(node) {
      const style = getComputedStyle(node), box = node.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0;
    }
    return Array.from(root.querySelectorAll('input,select,textarea')).filter(visible).filter(control => {
      if (control.type === 'hidden') return false;
      if (control.getAttribute('aria-label') || control.getAttribute('aria-labelledby')) return false;
      if (control.id && document.querySelector('label[for="' + CSS.escape(control.id) + '"]')) return false;
      return !control.closest('label');
    }).map(control => control.outerHTML.slice(0, 240));
  });
  assert.deepEqual(unnamed, [], 'visible form controls need real names in ' + scope + ':\n' + unnamed.join('\n'));
}

async function assertTabContracts(scope = 'body') {
  const violations = await page.locator(scope).evaluate(root => {
    function visible(node) {
      const style = getComputedStyle(node), box = node.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0;
    }
    return Array.from(root.querySelectorAll('[role="tablist"]')).filter(visible).flatMap(list => {
      const tabs = Array.from(list.querySelectorAll('[role="tab"]')).filter(visible);
      const selected = tabs.filter(tab => tab.getAttribute('aria-selected') === 'true');
      const named = list.getAttribute('aria-label') || list.getAttribute('aria-labelledby');
      const panelProblems = tabs.filter(tab => {
        const target = tab.getAttribute('aria-controls');
        const panel = target && document.getElementById(target);
        return !tab.id || !panel || panel.getAttribute('role') !== 'tabpanel'
          || (tab.getAttribute('aria-selected') === 'true' && panel.getAttribute('aria-labelledby') !== tab.id);
      });
      if (!named || selected.length !== 1 || selected[0].getAttribute('tabindex') !== '0'
          || tabs.some(tab => tab !== selected[0] && tab.getAttribute('tabindex') !== '-1')
          || panelProblems.length) {
        return [{ list: list.id || list.className, named, selected: selected.length,
          tabs: tabs.map(tab => ({ text: tab.textContent.trim(), selected: tab.getAttribute('aria-selected'),
            tabindex: tab.getAttribute('tabindex'), id: tab.id, controls: tab.getAttribute('aria-controls') })),
          panelProblems: panelProblems.map(tab => tab.id || tab.textContent.trim()) }];
      }
      return [];
    });
  });
  assert.deepEqual(violations, [], 'tab lists need one named roving selection in ' + scope + ': '
    + JSON.stringify(violations));
}

async function assertNoInternalChrome(scope = '#app') {
  const text = await page.locator(scope).innerText();
  const forbidden = [
    /\bPlan v\d+\b/i,
    /\bContext r\d+\b/i,
    /\bEconomic verdict\s+(?:FAVORABLE|MIXED|UNFAVORABLE|UNAVAILABLE|MECHANICALLY_INELIGIBLE)\b/i,
    /\breceipt\s+[a-f0-9]{12,}\b/i,
    /\b(?:ptx|plot|pcand|psr|tr|plan|ens)_[a-z0-9]{6,}\b/i,
    /\bAlt\+V\b/i,
    /\bidempotency\b/i
  ];
  const found = forbidden.filter(pattern => pattern.test(text)).map(String);
  assert.deepEqual(found, [], 'visible chrome must not expose transport internals: ' + found.join(', '));
}

async function openPlan(symbol, stage = 'understand', intentOverride, thesisOverride, horizonOverride) {
  // Test Plans must not inherit whichever durable Plan happens to be active from an
  // earlier case. Keep the fixture declaration deterministic; tests exercising a
  // different declaration pass it explicitly below.
  const horizon = horizonOverride || 'month';
  const horizonDays = horizon === '0DTE' ? 1 : horizon === 'week' ? 7
    : horizon === 'quarter' ? 63 : /^\d+d$/.test(horizon || '')
      ? parseInt(horizon, 10) : 30;
  const response = await fetch(BASE + '/api/plans', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clientRequestId: 'dom-plan-' + (++planSequence), symbol,
      intent: intentOverride || 'DIRECTIONAL', thesis: thesisOverride || 'bullish', horizonDays, riskMode: 'conservative' })
  });
  const body = await response.text();
  assert.ok(response.ok, 'test Plan creation failed: ' + body);
  const plan = JSON.parse(body);
  await page.evaluate(async ({ id, stagePath }) => {
    await PlanStore.load(true);
    App.navigate('#/plan/' + id + '/' + stagePath);
  }, { id: plan.id, stagePath: stage });
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  return plan;
}

async function openResearchTab(key) {
  if (await page.locator('#research-flow').count()) {
    const view = key === 'view' ? 'evidence' : key === 'options' ? 'options' : 'overview';
    const band = page.locator('#research-flow > .flow-band[data-band="' + view + '"]');
    assert.equal(await band.count(), 1, 'public Research band ' + view + ' exists');
    if ((await band.getAttribute('data-posture')) !== 'active') {
      await band.locator('.flow-band-invitation, .flow-band-conclusion').click();
    }
    await page.waitForFunction(expected => {
      const query = new URLSearchParams((location.hash.split('?')[1] || ''));
      const active = query.get('view') || 'overview';
      return active === expected
        && document.querySelector('#research-flow > [data-band="' + expected + '"]')?.dataset.posture === 'active'
        && document.getElementById('app').getAttribute('data-route') === 'research'
        && document.getElementById('app').getAttribute('data-ready') === 'true';
    }, view);
    if (key === 'news') {
      await page.waitForSelector('#open-news-tab');
      await page.click('#open-news-tab');
      await page.waitForSelector('#news-card:not([hidden])');
    }
    return;
  }
  if (key === 'overview' || key === 'news') {
    // Overview content (chart, events, news) lives on the PUBLIC research page now — the
    // journey's open beginning — not inside the Plan flow.
    const symbol = await page.evaluate(() => (PlanStore.active() || {}).symbol || App.context.symbol());
    assert.ok(symbol, 'a symbol exists before opening its public research page');
    await go('#/research/' + symbol);
    await page.waitForSelector('#research-hero');
  } else {
    const stagePath = key === 'view' ? 'evidence' : 'strategy';
    const planId = await page.evaluate(() => App.state.activePlanId);
    assert.ok(planId, 'an active Plan exists before navigating its bands');
    await go('#/plan/' + planId + '/' + stagePath);
    await page.waitForSelector('#plan-flow');
  }
  if (key === 'options') {
    await page.waitForSelector('.plan-tool-selector');
    await page.locator('.plan-tool').filter({ hasText: /Option prices|Chain/ }).click();
    await page.waitForSelector('#plan-strategy-body');
  }
  if (key === 'news') {
    await page.waitForSelector('#open-news-tab');
    await page.click('#open-news-tab');
    await page.waitForSelector('#news-card:not([hidden])');
  }
}

before(async () => {
  assert.ok(fs.existsSync(JAR), 'Build target/strikebench.jar before running the browser suite');
  assert.ok(fs.statSync(JAR).mtimeMs >= latestMtime(path.resolve(__dirname, '../src/main')),
    'target/strikebench.jar is older than src/main; run mvn package so browser evidence cannot certify stale UI or backend code');
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' },
    stdio: 'ignore'
  });
  await waitForServer();
  browser = await chromium.launch();
  context = await browser.newContext();
  page = await context.newPage();
  page.on('pageerror', e => { throw new Error('page error: ' + e.message); });

  await page.goto(WORKSPACE);
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
  // Show-don't-tell: an engine-generated candidate renders with lane-honest provenance.
  await page.waitForSelector('#welcome-live .candidate', { timeout: 20000 });
  assert.match(await page.textContent('#welcome-proof-caption'), /fabricated Demo data/,
    'the proof caption follows the explicit Demo lane');
  assert.doesNotMatch(await page.textContent('#welcome-proof-caption'), /real screened idea/i,
    'a Demo candidate is never promoted as real');
  assert.match(await page.textContent('#welcome-hero'), /Uses bid\/ask, not midpoint/,
    'fill realism is described without claiming Demo books are real');
  const welcomeVerdict = await page.locator('#welcome-live .candidate').getAttribute('data-economic-verdict');
  const welcomeProofGeometry = await page.evaluate(() => {
    const frame = document.querySelector('#welcome-live');
    const card = frame.querySelector('.welcome-proof-card');
    const style = getComputedStyle(frame);
    return {
      overflow: style.overflow,
      mask: style.maskImage || style.webkitMaskImage || 'none',
      frameBottom: Math.round(frame.getBoundingClientRect().bottom),
      cardBottom: Math.round(card.getBoundingClientRect().bottom),
      captionTop: Math.round(document.querySelector('#welcome-proof-caption').getBoundingClientRect().top),
      evLanes: card.querySelectorAll('.economic-ev-lanes .chip').length,
      facts: card.querySelectorAll('.welcome-proof-facts .fact').length
    };
  });
  assert.notEqual(welcomeProofGeometry.overflow, 'hidden', 'the live proof is never guillotined');
  assert.equal(welcomeProofGeometry.mask, 'none', 'the live proof is never dimmed with a mask');
  assert.ok(welcomeProofGeometry.cardBottom < welcomeProofGeometry.captionTop,
    'the visible proof card does not overlap the caption: ' + JSON.stringify(welcomeProofGeometry));
  assert.ok(welcomeProofGeometry.evLanes >= 3, 'both EV lanes and round-trip costs remain visible');
  assert.equal(welcomeProofGeometry.facts, 3, 'the compact proof retains its three decision facts');
  if (welcomeVerdict === 'UNFAVORABLE' || welcomeVerdict === 'UNAVAILABLE') {
    assert.match(await page.textContent('#welcome-proof-role'), /HOW STRIKEBENCH SAYS NO/);
    assert.match(await page.textContent('#welcome-proof-caption'), /counterexample, not an endorsement/i,
      'an adverse teaching case cannot sit under recommendation-shaped welcome framing');
  }
  assert.equal(await page.locator('#moved-terms .moved-term').count(), 7,
    'the tour anchors every familiar capability renamed by the Plan journey');
  assert.match(await page.textContent('#moved-terms'), /Formerly Trade.*Formerly Ideas.*Formerly Scan/s,
    'the mapping names the old nouns instead of making users rediscover them');
  // Skip to the dashboard; the choice persists
  let automaticScoutPosts = 0;
  const scoutProbe = request => {
    if (request.method() === 'POST' && /\/api\/research\/scout$/.test(request.url())) automaticScoutPosts++;
  };
  page.on('request', scoutProbe);
  await page.click('#welcome-skip');
  await page.waitForSelector('.home-market-grid .tile');
  await page.waitForFunction(() => document.querySelectorAll('.home-market-grid .spark-svg').length >= 4,
    { timeout: 15000 });
  page.off('request', scoutProbe);
  assert.equal(automaticScoutPosts, 0,
    'Home reads its governed teaching source only; Universe Scout belongs to Workspace');
  assert.equal(await page.locator('.home-market-grid .spark-slot .evidence-badge').count(), 0,
    'the Demo lane does not repeat the same fabricated label twice on every card');
  assert.equal(await page.evaluate(() => Array.from(document.querySelectorAll('.home-market-grid .sym-card')).some(card => {
    const ev = card.querySelector('.spark-ev');
    const head = card.querySelector('.t-sym');
    if (!ev || !head) return false;
    const a = ev.getBoundingClientRect(), b = head.getBoundingClientRect();
    return a.top < b.bottom && a.bottom > b.top;
  })), false, 'history evidence never covers the ticker/freshness row');
  const app = await page.textContent('#app');
  // Home is the OPERATIONAL desk (review #10): market mode + account numbers + one contextual
  // action — the product positioning lives on Welcome only, never repeated here.
  assert.match(app, /YOUR PRACTICE DESK/);
  assert.match(app, /OBSERVED MARKET|DEMO MARKET|SIMULATED|SCENARIO/);
  assert.doesNotMatch(await page.textContent('#home-mode-chip'), /SIMULATED/, 'Demo is not mislabeled as a simulated session');
  assert.doesNotMatch(app, /Learn options by/, 'positioning is not repeated on the dashboard');
  assert.match(app, /Buying power/);
  assert.ok(await page.locator('#home-tour-link').isVisible(), 'the full tour is one visible click away');
  assert.ok((await page.locator('.home-market-grid .tile').count()) >= 4, 'market watch tiles');
  assert.equal(await page.evaluate(() => document.querySelector('.home-hero').nextElementSibling.id),
    'home-screened-ideas', 'screened ideas are first in reading order after the desk hero');
  assert.ok(await page.locator('#home-first-steps').isVisible(),
    'Beginner gets a concise first-steps strip without losing any discovery control');
  assert.equal(await page.getByRole('button', { name: 'Find an idea', exact: true }).count(), 0,
    'the cold desk does not duplicate the teaser\'s Workspace entry');
  assert.equal(await page.locator('#home-plan-library').count(), 0,
    'a fresh desk does not spend a full-width card repeating the hero and Continue start action');
  assert.equal(await page.locator('#next-up').count(), 0,
    'Continue is absent until there is a real Plan to resume');
  assert.equal(await page.getByRole('button', { name: 'Start a Plan' }).count(), 0,
    'the empty hero does not create a second Plan entry journey');
  assert.equal(await page.getByRole('button', { name: 'Open in Workspace' }).count(), 1,
    'the read-only teaser owns the one Workspace entry');
  await captureSettled('trader-own-p15-home-cold-desktop.png');
  await page.setViewportSize({ width: 390, height: 844 });
  await captureSettled('trader-own-p15-home-cold-mobile.png');
  await page.setViewportSize({ width: 1280, height: 720 });
  const planCountBeforeTeaser = await page.evaluate(() => PlanStore.all().length);
  await page.getByRole('button', { name: 'Open in Workspace' }).click();
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  assert.match(await page.evaluate(() => location.hash), /^#\/research\//,
    'the teaser opens its ticker in the canonical Workspace');
  assert.equal(await page.evaluate(() => PlanStore.all().length), planCountBeforeTeaser,
    'opening a read-only teaser does not create a Plan');
  await page.evaluate(() => window.scrollTo(0, 0));
  const footer = await page.textContent('#disclaimer');
  assert.match(footer, /not financial advice/i);
  // The tour is a canonical Home subview.
  await go('#/home/tour');
  await page.waitForSelector('#welcome-hero');
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'home');
  // BRAND RETURNS TO THE OPERATIONAL DESK (review P5): from any screen the mark lands on
  // Home, never a surprise tour; the tour keeps its own quiet entry at the page bottom.
  await openPlan('AAPL');
  await page.waitForSelector('#plan-flow');
  await page.click('.brand');
  await page.waitForSelector('.home-market-grid .tile');
  await page.waitForFunction(() => document.querySelectorAll('.home-market-grid .spark-svg').length >= 4,
    { timeout: 15000 });
  assert.equal(await page.evaluate(() => window.location.hash), '#/home', 'brand goes to the desk');
  assert.ok(await page.locator('.brand .brand-mark').count(), 'the SVG mark renders in the header');
  await page.waitForSelector('#next-up');
  assert.equal(await page.locator('#home-plan-library').count(), 0,
    'the active Plan belongs to the hero and is not repeated in a one-item library');
  assert.match(await page.textContent('#next-up'), /Plan progress.*AAPL/s);
  assert.equal(await page.locator('#next-up button').count(), 0,
    'Plan progress supports the hero Continue command without duplicating it');
  await page.reload();
  await page.waitForSelector('#app[data-route="home"][data-ready="true"]');
  await page.waitForFunction(() => document.querySelectorAll('.home-market-grid .spark-svg').length >= 4,
    { timeout: 15000 });
  assert.match(await page.textContent('#world-band'), /DEMO MARKET/,
    'same-lane reload restores both market honesty and warm chart decoration');
  // The tour stays reachable — demoted to a quiet About link, not a standing command
  assert.ok(await page.locator('#home-tour-link').isVisible(), 'quiet tour entry exists');
  assert.equal(await page.locator('.home-hero-ctas #home-tour-link').count(), 0,
    'the hero owns ONE primary action; the tour left the hero');
  await page.click('#home-tour-link');
  await page.waitForSelector('#welcome-hero');
  // Home: sector pulse chips dig into the explorer
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1'));
  await go('#/home');
  await page.waitForSelector('#sector-pulse .sector-chip', { timeout: 20000 });
  await page.click('#sector-pulse .sector-chip');
  await page.waitForSelector('#sector-explorer');
  await go('#/home'); // leave the suite on the dashboard for the next test
});

test('first contact resolves Welcome before any dashboard paint', async () => {
  const account = await fetch(BASE + '/api/account').then(response => response.json());
  let released = false;
  await page.route('**/api/account', async route => {
    await new Promise(resolve => setTimeout(resolve, 260));
    released = true;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(account) });
  });
  await page.evaluate(() => localStorage.removeItem('strikebench.welcomed'));
  await page.goto(WORKSPACE + '?first-contact=' + Date.now() + '#/home');
  await page.waitForTimeout(80);
  assert.equal(released, false, 'the account decision is still pending during the paint probe');
  assert.equal(await page.locator('.home-hero').count(), 0,
    'Home is never mounted underneath an unresolved first-contact decision');
  await page.waitForSelector('#welcome-hero');
  await page.unroute('**/api/account');
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1'));
  await go('#/home');
});

test('Home teaser uses one governed source and one read-only Workspace entry', async () => {
  await page.evaluate(() => {
    Learn.setLevel('beginner');
    localStorage.setItem('strikebench.welcomed', '1');
    localStorage.removeItem('strikebench.homeIdeas');
  });
  let scoutPosts = 0;
  const observeScout = request => {
    if (request.method() === 'POST' && /\/api\/research\/scout$/.test(request.url())) scoutPosts++;
  };
  page.on('request', observeScout);
  await go('#/home');
  await page.waitForSelector('#home-screened-ideas .home-idea-row', { timeout: 20000 });
  assert.equal(scoutPosts, 0, 'a Home render never spends the universe Scout provider budget');
  assert.ok(await page.locator('#home-first-steps').isVisible(), 'Beginner gets first-step guidance');
  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#home-screened-ideas');
  assert.equal(await page.locator('#home-first-steps').count(), 0,
    'Expert removes teaching chrome while retaining the same screened-idea controls');
  assert.ok(await page.locator('#home-screened-ideas').isVisible(), 'Expert retains discovery capability');
  await page.evaluate(async () => { Learn.setLevel('beginner'); await App.render(); });
  await page.waitForSelector('#home-first-steps');
  page.off('request', observeScout);
  assert.equal(scoutPosts, 0, 'Home never runs the separate Universe Scout journey');
  assert.equal(await page.locator('#home-screened-ideas').getByRole('button', { name: /Scout|Refresh/i }).count(), 0,
    'the teaser has no Scout or refresh entry point');
  assert.match(await page.textContent('#home-ideas-basis'),
    /System-authored screening basis.*not silently copied into your Plan/i,
    'the teaser discloses its exact system-owned basis and its boundary');
  const symbols = await page.locator('#home-screened-ideas .home-idea-row').evaluateAll(rows =>
    rows.map(row => row.getAttribute('data-symbol')));
  assert.equal(symbols.length, 1, 'Home shows one teaser, not a second discovery catalog');

  const first = page.locator('#home-screened-ideas .home-idea-row').first();
  assert.equal(await first.getByRole('button', { name: 'Open in Workspace' }).count(), 1,
    'a read-only discovery row has one destination action; editing stays in Plan Strategy');
  const symbol = await first.getAttribute('data-symbol');
  const before = await page.evaluate(() => PlanStore.all().length);
  await first.getByRole('button', { name: 'Open in Workspace' }).click();
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]', { timeout: 30000 });
  assert.equal(await page.evaluate(() => App.context.symbol()), symbol);
  assert.equal(await page.evaluate(() => PlanStore.all().length), before,
    'the teaser does not bypass Ready to compare or create a Plan');
});

test('Universe Scout requires explicit canonical choices at 2560px and mobile', async () => {
  await page.setViewportSize({ width: 2560, height: 1440 });
  await go('#/research');
  await page.waitForSelector('#research-plan-scout');
  await page.evaluate(() => { window.__universeScoutRoot = document.getElementById('research-plan-scout'); });
  assert.equal(await page.locator('#workspace-plan-strip').count(), 0,
    'Research index does not duplicate the Plan library');
  assert.equal(await page.locator('#research-plan-scout select').count(), 0,
    'Universe Scout uses canonical choice controls, never bare selects');
  assert.equal(await page.locator('#research-plan-scout .choice-field').count(), 3,
    'goal, horizon, and risk posture are the three visible decisions');
  assert.equal(await page.locator('#research-plan-scout .choice-option.active').count(), 0,
    'a fresh Scout declaration starts blank');
  assert.equal(await page.locator('#universe-scout-run').isDisabled(), true);
  assert.match(await page.textContent('#universe-scout-readiness'), /goal.*horizon.*risk posture/i,
    'readiness names every missing decision-bearing input');
  const desktop = await page.evaluate(() => ({
    columns: getComputedStyle(document.querySelector('.universe-scout-controls')).gridTemplateColumns.split(' ').length,
    overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth
  }));
  assert.equal(desktop.columns, 3, 'wide desktop uses the comparison space for all three controls');
  assert.ok(desktop.overflow <= 1, 'the 2560px surface has no horizontal overflow');

  await page.click('#universe-scout-intent .choice-option[data-value="INCOME"]');
  await page.click('#universe-scout-horizon .choice-option[data-value="month"]');
  await page.click('#universe-scout-risk .choice-option[data-value="balanced"]');
  assert.equal(await page.locator('#universe-scout-run').isEnabled(), true);
  assert.match(await page.textContent('#universe-scout-readiness'), /exact goal, horizon, and risk posture/i);
  assert.match(await page.textContent('#universe-scout-horizon .control-consequence'), /21 trading sessions/i);
  let requestBody;
  await page.route('**/api/research/scout', async route => {
    requestBody = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      picks: [], notes: ['Focused rendered test.'], compensation: []
    }) });
  });
  await page.click('#universe-scout-run');
  await page.waitForSelector('#universe-scout-results .empty');
  assert.deepEqual({ horizons: requestBody.horizons, riskMode: requestBody.riskMode, intents: requestBody.intents },
    { horizons: ['month'], riskMode: 'balanced', intents: ['INCOME'] },
    'the request carries the exact visible declaration');
  await page.unroute('**/api/research/scout');

  await page.setViewportSize({ width: 390, height: 844 });
  const mobile = await page.evaluate(() => ({
    sameRoot: window.__universeScoutRoot === document.getElementById('research-plan-scout'),
    columns: getComputedStyle(document.querySelector('.universe-scout-controls')).gridTemplateColumns.split(' ').length,
    overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    actionVisible: !!document.querySelector('#universe-scout-run')?.offsetParent
  }));
  assert.deepEqual(mobile, { sameRoot: true, columns: 1, overflow: 0, actionVisible: true },
    'mobile reflows the same mounted controls and action without overflow');
  await page.click('#universe-scout-horizon .choice-option[data-value="week"]');
  assert.match(await page.textContent('#universe-scout-readiness'), /exact goal, horizon, and risk posture/i,
    'mobile retains the same consequence/readiness contract');
  await page.setViewportSize({ width: 1280, height: 720 });
});

test('Research creates an honest incomplete Plan, then the same Your view unlocks ranking', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  await page.evaluate(() => {
    localStorage.removeItem('strikebench.riskMode');
    App.state.headerRiskExplicit = false;
    App.context.update({ symbol: 'QQQ', goal: null, thesis: null, horizon: null });
  });
  await go('#/research/QQQ');
  await page.waitForSelector('#symbol-proposed-trades');
  assert.equal(await page.locator('#symbol-proposed-goal .choice-option').count(), 5,
    'the compact Research front door retains every goal');
  await captureSettled('trader-own-p15-research-symbol-desktop.png');
  await page.setViewportSize({ width: 390, height: 844 });
  await captureSettled('trader-own-p15-research-symbol-mobile.png');
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.click('#symbol-proposed-goal .choice-option[data-value="INCOME"]');
  assert.equal((await page.textContent('#symbol-find-proposed')).trim(), 'Continue to Your view');
  assert.match(await page.textContent('.symbol-proposed-missing'), /horizon.*risk posture/i,
    'Research names every missing assumption instead of filling a month or risk posture');
  await page.click('#symbol-find-proposed');
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]', { timeout: 30000 });
  assert.match(await page.evaluate(() => location.hash), /\/understand$/);
  const incomplete = await page.evaluate(() => {
    const plan = PlanStore.active();
    return { id: plan.id, symbol: plan.symbol, intent: plan.intent,
      thesis: plan.context.thesis, horizonDays: plan.context.horizonDays, riskMode: plan.context.riskMode };
  });
  assert.deepEqual(incomplete, { id: incomplete.id, symbol: 'QQQ', intent: 'INCOME',
    thesis: null, horizonDays: null, riskMode: null });
  assert.equal(await page.getAttribute('[data-band="strategy"]', 'data-posture'), 'locked',
    'Strategy remains visibly locked while the Plan declaration is incomplete');

  await page.waitForSelector('[data-band="view"] #plan-declare-view');
  await page.locator('[data-band="view"] .choice-field').filter({ hasText: 'Over the next' })
    .locator('.choice-option[data-value="21"]').click();
  await page.locator('#plan-thesis .choice-option[data-value="neutral"]').click();
  await page.locator('[data-band="view"] .choice-field').filter({ hasText: 'Risk budget' })
    .locator('.choice-option[data-value="conservative"]').click();
  await page.click('#plan-declare-view');
  await page.waitForSelector('[data-band="strategy"] .flow-band-invitation');
  await page.click('[data-band="strategy"] .flow-band-invitation');
  await page.waitForSelector('#plan-run-strategy');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-ranked-field .ranked-idea-hero', { timeout: 30000 });
  const field = await page.evaluate(async id => {
    const latest = await API.getFresh('/api/plans/' + id + '/strategy/latest');
    return latest.strategy && latest.strategy.result && latest.strategy.result.candidates || [];
  }, incomplete.id);
  assert.ok(field.length > 0, 'the exact same Plan ranks only after its missing declaration is saved');
});

test('Trade a view requires an explicit thesis and context revisions preserve the evidence protocol', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  await openPlan('AAPL', 'understand', 'DIRECTIONAL', 'bearish');
  await go('#/research/TSLA');
  await page.waitForSelector('#symbol-proposed-trades');
  await page.click('#symbol-proposed-goal .choice-option[data-value="DIRECTIONAL"]');
  assert.equal((await page.locator('#symbol-find-proposed').textContent()).trim(), 'Continue to Your view',
    'a new symbol cannot inherit the active Plan’s directional thesis, horizon, or risk posture');
  await page.click('#symbol-find-proposed');
  await page.waitForFunction(() => /\/plan\/plan_[^/]+\/understand$/.test(location.hash)
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.waitForSelector('#plan-thesis');
  const created = await page.evaluate(() => {
    const plan = PlanStore.active();
    return { id: plan.id, symbol: plan.symbol, intent: plan.intent, thesis: plan.context.thesis,
      horizonDays: plan.context.horizonDays, riskMode: plan.context.riskMode };
  });
  assert.deepEqual(created, { id: created.id, symbol: 'TSLA', intent: 'DIRECTIONAL', thesis: null,
    horizonDays: null, riskMode: null },
  'Trade a view records every unchosen fact honestly instead of silently writing defaults');
  assert.equal(await page.locator('#plan-thesis .choice-option.active').count(), 0,
    'Your view opens with no directional assumption preselected');

  await page.evaluate(async planId => {
    let live = await PlanStore.get(planId, true);
    live = await PlanStore.updateContext(live, {
      thesis: 'bearish', horizonDays: 21, riskMode: 'balanced'
    });
    await PlanStore.focus(live, 'EVIDENCE');
  }, created.id);
  await page.waitForFunction(() => /\/evidence$/.test(location.hash)
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.waitForSelector('#test-your-view');

  const preserved = await page.evaluate(async planId => {
    const ui = PlanStore.ui(planId);
    ui.evidence = {
      mode: 'past',
      protocolBySymbol: { TSLA: { confidencePct: 91, minSample: 22 } },
      params: { pullback_rebound: { threshold: -4 } },
      scenarioForm: { shape: 'CHOP' },
      results: { pullback_rebound: { stale: true } },
      planEnsembleId: 'stale-ensemble'
    };
    const live = await PlanStore.get(planId, true);
    ui.contextRev = live.context.rev;
    await PlanStore.updateContext(live, { horizonDays: Number(live.context.horizonDays) + 1 });
    await App.render();
    const evidence = PlanStore.ui(planId).evidence;
    return { protocol: evidence.protocolBySymbol.TSLA, params: evidence.params,
      scenarioForm: evidence.scenarioForm, results: evidence.results,
      ensemble: evidence.planEnsembleId, changed: evidence.assumptionsChanged };
  }, created.id);
  assert.deepEqual(preserved.protocol, { confidencePct: 91, minSample: 22 },
    'a context revision preserves the user-authored research protocol');
  assert.deepEqual(preserved.params, { pullback_rebound: { threshold: -4 } });
  assert.deepEqual(preserved.scenarioForm, { shape: 'CHOP' });
  assert.equal(preserved.results, undefined, 'only stale evidence results are cleared');
  assert.equal(preserved.ensemble, undefined, 'the prior ensemble cannot masquerade as current');
  assert.equal(preserved.changed, true);
});

test('Outcomes distinguishes a stale selection and reprices the same legs', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('SPY', 'strategy', 'DIRECTIONAL', 'bullish');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-ranked-field .ranked-idea-hero', { timeout: 30000 });
  await page.evaluate(async planId => {
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    const candidate = latest.strategy.result.candidates.find(item => item.id);
    let live = await PlanStore.get(planId, true);
    await PlanStore.selectCandidate(live, candidate.id);
    live = await PlanStore.get(planId, true);
    await PlanStore.updateContext(live, { horizonDays: Number(live.context.horizonDays) + 5 });
  }, plan.id);
  await go('#/plan/' + plan.id + '/outcomes');
  await page.waitForSelector('#plan-outcomes-prerequisite');
  assert.match(await page.textContent('#plan-outcomes-prerequisite'),
    /Reprice the prior structure[\s\S]*assumptions changed[\s\S]*old results remain historical evidence/i,
    'Outcomes names a stale prior package instead of pretending no structure was ever chosen');
  assert.equal(await page.locator('#plan-outcomes .outcome-basis-state:has-text("Setup needed")').count(), 4,
    'all four outcome lenses remain visible but unavailable until repricing');
  await page.getByRole('button', { name: 'Reprice the prior structure', exact: true }).click();
  await page.waitForSelector('.plan-tool[data-strategy-tool="yourTrade"][aria-pressed="true"]');
  assert.ok(await page.locator('#plan-strategy-body .position-leg').count() > 0,
    'repricing carries the prior exact legs into the direct editor');
});

test('plan foundation promotes once, survives reload, versions assumptions, and closes only the tab', async () => {
  await chooseHeaderRisk('balanced');
  await page.evaluate(() => App.context.update({ symbol: 'AAPL', goal: null, thesis: 'neutral', horizon: '21d' }));
  await go('#/research/AAPL');
  await page.waitForSelector('#symbol-proposed-trades');
  await page.waitForSelector('#research-hero .quote-hero');
  assert.equal(await page.locator('#plan-flow').count(), 0,
    'opening a stock is full Research; no Plan journey exists before the user chooses a goal');
  assert.ok(await page.locator('#events-card .event-timeline').count(), 'Research shows dated events as a timeline');
  assert.equal(await page.locator('.market-facts .info-trigger').count(), 4,
    'technical market facts carry visible explanations');
  assert.equal(await page.locator('#research-hero .xp.open').count(), 1,
    'desktop Research opens useful market detail instead of hiding it');
  // The journey's open beginning owns price history, dated events and source-backed news;
  // the Plan flow carries the declared view forward instead of re-rendering the market overview.
  await page.waitForSelector('#history-card');
  assert.ok(await page.locator('#news-overview-card').count(), 'the open beginning owns source-backed news');
  await page.waitForFunction(() => /fabricated|No headline/i.test(document.getElementById('news-overview-card')?.textContent || ''));
  assert.match(await page.textContent('#news-overview-card'), /Teaching catalysts.*fabricated/i,
    'Demo headlines are explicitly teaching prompts rather than apparent company news');
  assert.equal(await page.locator('#news-overview-card a.news-headline').count(), 0,
    'fabricated Demo catalysts never link to an apparent external article');
  await page.click('#symbol-proposed-goal .choice-option[data-value="INCOME"]');
  await page.click('#symbol-find-proposed');
  await page.waitForFunction(() => /^#\/plan\/plan_[^/]+\/strategy$/.test(location.hash));
  await page.waitForSelector('#plan-header, #route-error');
  assert.equal(await page.locator('#route-error').count(), 0,
    'persisted plan renders without a route error: ' + (await page.locator('#app').textContent()));
  const planHash = await page.evaluate(() => location.hash);
  assert.match(await page.textContent('#plan-header'), /AAPL.*Earn income.*Demo market/s);
  assert.equal((await page.locator('#nav a.active').textContent()).trim(), 'Workspace',
    'the primary navigation names the Workspace while a journey is open');
  assert.match(await page.textContent('#lane-chip'), /DEMO/,
    'the active execution market remains visible inside every Plan stage');
  await page.waitForSelector('#toast-region .toast-message');
  assert.match(await page.textContent('#toast-region'), /Plan created — AAPL · Earn income/,
    'creating a Plan is announced before the journey continues');
  assert.match(planHash, /\/strategy$/,
    'an explicit goal has one canonical destination: Strategy');
  // The retired chip bar's "appears exactly once" contract now lives on the Desk: the hero
  // owns the ACTIVE plan; the compact library never repeats it as a row.
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1'));
  await go('#/home');
  await page.waitForSelector('.home-hero-ctas button');
  assert.equal(await page.locator('.home-hero-ctas button').filter({ hasText: /^Continue AAPL/ }).count(), 1,
    'the promoted durable plan is owned exactly once by the Desk hero even when other plans are open');
  assert.equal(await page.evaluate(() => App.state.activePlanId), planHash.split('/')[2],
    'the hero continuation references the promoted plan');
  assert.equal(await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + planHash.split('/')[2] + '"]').count(), 0,
    'the hero-owned plan is never repeated as a compact desk row');
  await go(planHash.replace('/strategy', '/evidence'));
  await page.waitForSelector('#plan-flow');
  await page.waitForSelector('#plan-stage-evidence .plan-stage-carry');
  assert.match(await page.textContent('#plan-stage-evidence .plan-stage-carry'), /AAPL.*Earn income.*21 trading sessions.*Demo market/s,
    'the flow keeps the saved Plan context in the shared stage frame');
  assert.equal(await page.locator('#plan-understand-scope').count(), 0,
    'the flow does not repeat the same Plan context in a second scope card');
  assert.equal(await page.locator('#plan-flow > .flow-band').count(), 6, 'the full six-band journey appears after Plan creation');
  assert.equal(await page.locator('[data-band="live"]').getAttribute('data-posture'), 'locked',
    'Manage & Review is gated before a decision');
  assert.match(await page.textContent('[data-band="live"] .flow-band-locked'), /once you commit/i,
    'the locked live band keeps its title and states what unlocks it');
  assert.equal(await page.locator('#plan-archive').count(), 1,
    'a working Plan can be archived without deleting its evidence or history');
  assert.equal(await page.locator('#research-workspace-tabs').count(), 0,
    'the old Research-local workspace is gone; the flow bands own navigation');

  await page.click('#plan-edit-context');
  await page.waitForSelector('[data-band="view"] #plan-declare-view');
  assert.match(await page.textContent('[data-band="view"]'), /Goal:.*Earn income.*change the goal and structure/s,
    'a pre-decision Plan exposes editable goal, structure and assumptions');
  assert.ok(await page.locator('#plan-change-goal').count(), 'goal edit is available before a decision');
  assert.ok(await page.locator('#plan-change-structure').count(), 'structure and option type return through Strategy');
  await page.locator('[data-band="view"] .choice-segmented .choice-option').filter({ hasText: 'Custom' }).click();
  await page.fill('#plan-horizon-days', '45');
  await page.locator('#plan-thesis .choice-option').filter({ hasText: 'Down' }).click();
  await page.click('#plan-declare-view');
  await page.waitForFunction(() => /45 trading sessions/.test(document.getElementById('plan-header')?.textContent || ''));
  await page.reload();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  assert.equal(await page.evaluate(() => location.hash), planHash.replace('/strategy', '/understand'),
    'editing the declaration owns the truthful Understand deep link after reload');
  assert.match(await page.textContent('#plan-header'), /45 trading sessions/);
  assert.match(await page.textContent('#plan-header'), /AAPL.*Earn income/s);

  // Capture after the real entrance transition settles; production animations remain enabled.
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p2-understand-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(500);
  const mobileFlow = await page.evaluate(() => {
    const flow = document.querySelector('#plan-flow').getBoundingClientRect();
    return Array.from(document.querySelectorAll('#plan-flow > .flow-band')).map(band => {
      const r = band.getBoundingClientRect();
      const title = band.querySelector('.flow-band-title').getBoundingClientRect();
      return { band: band.dataset.band, left: r.left, right: r.right,
        titleLeft: title.left, titleRight: title.right, flowLeft: flow.left, flowRight: flow.right };
    });
  });
  assert.equal(mobileFlow.length, 6, 'all six journey bands remain rendered on mobile');
  for (const band of mobileFlow) {
    assert.ok(band.left >= band.flowLeft - 1 && band.right <= band.flowRight + 1
      && band.titleLeft >= band.left - 1 && band.titleRight <= band.right + 1,
      'mobile band is fully visible rather than clipped: ' + JSON.stringify(band));
  }
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p2-mobile-flow.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });

  // The 45-day edit above proves context persistence. Use a short evidence window for the
  // event-study assertion so the fixture has enough independent signal episodes to evaluate.
  if (await page.locator('[data-band="view"] #plan-declare-view').count() === 0) {
    await page.click('#plan-edit-context');
  }
  await page.waitForSelector('[data-band="view"] #plan-declare-view');
  await page.locator('[data-band="view"] .choice-segmented .choice-option').filter({ hasText: 'Custom' }).click();
  await page.fill('#plan-horizon-days', '10');
  await page.click('#plan-declare-view');
  await page.waitForFunction(() => /10 trading sessions/.test(document.getElementById('plan-header')?.textContent || ''));

  await go(planHash.replace('/strategy', '/evidence'));
  await page.waitForSelector('#test-your-view');
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  const studyText = await page.textContent('#study-results');
  assert.match(studyText, /Signal episodes/);

  await page.click('#research-outcomes-basis-futures');
  await page.waitForSelector('#whatif-card');
  const ensemblePattern = '**/api/plans/*/outcomes/ensemble';
  await page.route(ensemblePattern, route => route.fulfill({ status: 409, contentType: 'application/json',
    body: JSON.stringify({ error: 'plan_market_mismatch', market: 'DEMO', targetWorld: 'demo',
      detail: 'This Plan belongs to the Demo market. Open that market before running this analysis.' }) }));
  await page.click('#whatif-run');
  await page.waitForSelector('#plan-ensemble-market-mismatch');
  assert.match(await page.textContent('#plan-ensemble-market-mismatch'), /refused to mix prices/i,
    'a lane mismatch becomes an actionable Plan-market recovery, not raw server text');
  assert.ok(await page.locator('#plan-ensemble-market-mismatch button').count(),
    'the mismatch names the action that restores the Plan market');
  await page.unroute(ensemblePattern);
  await page.click('#whatif-run');
  await page.waitForSelector('.scenario-decision', { timeout: 25000 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(4200);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p2-evidence.png'), fullPage: true });

  const evidencePresentation = await page.evaluate(id => Workspace.snapshot().planPresentation[id], planHash.split('/')[2]);
  assert.equal(evidencePresentation.evidenceSetup, 'pullback_rebound',
    'workspace continuity remembers the explicit historical trigger needed to reconstruct Evidence');
  assert.doesNotMatch(JSON.stringify(evidencePresentation), /results|ensemble|fingerprint|preview/i,
    'the presentation snapshot never duplicates study results or priced Evidence artifacts');

  await page.reload();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await page.waitForSelector('#research-outcomes-basis-futures');
  if (await page.getAttribute('#research-outcomes-basis-futures', 'aria-pressed') !== 'true') {
    await page.click('#research-outcomes-basis-futures');
  }
  await page.waitForSelector('#tv-futures', { timeout: 15000 });
  assert.match(await page.evaluate(() => location.hash), /\/evidence$/,
    'reload returns to the durable Evidence stage');
  await page.click('#research-outcomes-basis-past');
  await page.waitForFunction(() => /Signal episodes/.test(document.getElementById('study-results')?.textContent || ''),
    { timeout: 20000 });
  assert.match(await page.textContent('#study-results'), /Signal episodes/,
    'the exact normalized study result restores after reload');

  // Closing moved from the chip strip to the Desk rows. Rows exclude the ACTIVE plan, so
  // hand the desk to a second journey first, then put this one away from its own row.
  const closedPlanId = planHash.split('/')[2];
  const helper = await page.evaluate(async () => {
    const plan = await PlanStore.create({ symbol: 'SPY', intent: 'DIRECTIONAL', thesis: 'bullish',
      horizonDays: 23, riskMode: 'conservative', title: 'SPY · Desk handoff' });
    return { id: plan.id };
  });
  await go('#/home');
  await page.waitForSelector('#home-plan-library .home-plan-compact-row[data-plan-id="' + closedPlanId + '"]');
  await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + closedPlanId + '"] .home-plan-compact-close').click();
  await page.waitForFunction((id) => !document.querySelector('#home-plan-library [data-plan-id="' + id + '"]'), closedPlanId);
  assert.equal(await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + closedPlanId + '"]').count(), 0,
    'closing a tab removes only that durable plan from the open collection');
  assert.equal(await page.evaluate(() => location.hash), '#/home',
    'closing a desk row never navigates away from the Desk');
  await openPlanDrawer();
  const closedTabsHead = page.locator('#plans-library .xp-head').filter({ hasText: 'Closed Plan tabs' });
  await closedTabsHead.waitFor();
  await ensureExpanded(closedTabsHead);
  assert.equal(await page.locator('.home-plan-closed-tabs .status-item').filter({ hasText: 'AAPL · Earn income' }).count(), 1,
    'the closed tab keeps its durable Plan reachable in the library');
  // Leave a clean desk for later journeys: retire the handoff helper as an archived record.
  await page.evaluate(async id => {
    const plan = await PlanStore.get(id, true);
    await API.post('/api/plans/' + id + '/archive', { expectedVersion: plan.version });
    await PlanStore.refresh();
  }, helper.id);
});

test('primary Plan navigation fails visibly and restores its command', async () => {
  await openPlan('AAPL', 'understand', 'INCOME');
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1'));
  await go('#/home');
  const continueButton = page.locator('button').filter({ hasText: /^Continue AAPL/ }).first();
  await continueButton.waitFor();
  await page.evaluate(() => {
    window.__realPlanFocus = PlanStore.focus;
    PlanStore.focus = function () { return Promise.reject(new Error('Plan focus integrity check')); };
  });
  try {
    await continueButton.click();
    await page.waitForSelector('#toast-region .toast-error');
    assert.match(await page.textContent('#toast-region'), /Plan focus integrity check/);
    assert.equal(await continueButton.isEnabled(), true, 'a refused Plan open restores the command');
    assert.equal(await continueButton.getAttribute('aria-busy'), null, 'pending state clears after failure');
  } finally {
    await page.evaluate(() => { PlanStore.focus = window.__realPlanFocus; delete window.__realPlanFocus; });
  }
});

test('a pre-decision Plan can change goal and returns to Strategy without forking', async () => {
  const created = await page.evaluate(async () => {
    const plan = await PlanStore.create({ symbol: 'SPY', intent: 'INCOME', thesis: 'neutral',
      horizonDays: 53, riskMode: 'conservative', title: 'Editable goal check' });
    await PlanStore.focus(plan, 'UNDERSTAND');
    return { id: plan.id };
  });
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await page.waitForSelector('#plan-header');
  if (await page.locator('#plan-change-goal').count() === 0) await page.click('#plan-edit-context');
  await page.waitForSelector('#plan-change-goal');
  await page.click('#plan-change-goal');
  await page.locator('.plan-fork-intents .choice-card').filter({ hasText: 'Protect my shares' }).click();
  await page.waitForSelector('#modal-confirm');
  assert.match(await page.textContent('#modal-root'), /marks.*stale|become stale/i,
    'changing a goal explains the dependent-result invalidation');
  await page.click('#modal-confirm');
  await page.waitForFunction(id => location.hash === '#/plan/' + id + '/strategy', created.id);
  await page.waitForSelector('#plan-strategy-body');
  assert.match(await page.textContent('#plan-header'), /Protect my shares/i);
  const persisted = await page.evaluate(id => API.getFresh('/api/plans/' + id), created.id);
  assert.equal(persisted.intent, 'HEDGE');
  assert.equal(persisted.assumptionsEditable, true);

  await page.click('#plan-edit-context');
  await page.click('#plan-change-structure');
  await page.waitForSelector('.plan-tool[aria-pressed="true"]:has-text("All strategies")');
  await page.waitForSelector('#plan-strategy-panel-builder:not([hidden]) #builder-catalog .tpl', { timeout: 20000 });
  assert.match(await page.textContent('#plan-strategy-body'), /Every strategy, shown by payoff shape/i,
    'changing structure opens the full visual catalog instead of reloading the same ranked view');
});

test('Plan flow orients both levels without hiding capabilities or stealing the journey', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('TSLA', 'evidence', 'DIRECTIONAL', 'bullish', '30d');
  await page.waitForSelector('#plan-flow');
  assert.equal(await page.locator('#plan-flow > .flow-band').count(), 6,
    'the whole journey stays visible as bands');
  assert.match(await page.textContent('[data-band="view"] .flow-band-conclusion'),
    /TSLA.*Up.*30 sessions/s,
    'a declared view band concludes with the view itself, not bare chrome');
  assert.match(await page.textContent('[data-band="outcomes"] .flow-band-locked'),
    /select a structure/i, 'locked bands state what unlocks them');
  assert.match(await page.textContent('[data-band="live"] .flow-band-locked'),
    /once you commit/i, 'the live band explains when it appears');
  const beforeViewOnlyNavigation = await page.evaluate(id => API.getFresh('/api/plans/' + id), plan.id);
  await go('#/plan/' + plan.id + '/evidence');
  await page.waitForSelector('#plan-stage-evidence');
  assert.match(await page.textContent('.plan-stage-carry'), /TSLA.*Demo market/s,
    'the evidence band names the context carried into it');
  const afterViewOnlyNavigation = await page.evaluate(id => API.getFresh('/api/plans/' + id), plan.id);
  assert.equal(afterViewOnlyNavigation.version, beforeViewOnlyNavigation.version,
    'viewing another band never competes with assumption or decision writes');
  assert.equal(afterViewOnlyNavigation.furthestStage, beforeViewOnlyNavigation.furthestStage,
    'deep links move attention, not durable progress');

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#plan-flow');
  assert.equal(await page.locator('#plan-flow > .flow-band').count(), 6,
    'Expert and Beginner see the same bands — level is a lens, not a fork');
  assert.equal(await page.locator('.plan-header-receipt, .plan-stage-carry-receipt').count(), 0,
    'transport versions never appear in Plan chrome');
  assert.doesNotMatch(await page.textContent('#plan-header'), /Plan v\d+|Context r\d+/i,
    'the Plan header speaks in user decisions rather than revision counters');
  await assertNoInternalChrome('#app');

  // Attention model: while the user works in evidence, strategy is a one-line invitation —
  // its full toolset is one tap away, never a second open workbench stacked below.
  assert.match(await page.textContent('[data-band="strategy"] .flow-band-invitation'),
    /Rank the complete field|compose your own/i,
    'the unfocused strategy band invites with what it holds');
  await page.click('[data-band="strategy"] .flow-band-invitation');
  await page.waitForSelector('#plan-strategy-body');
  await page.waitForSelector('#plan-strategy-panel-compare:not([hidden])');
  assert.equal(await page.locator('#plan-strategy-panel-compare:not([hidden])').count(), 1,
    'the ranked proposals are the band content itself');
  assert.equal(await page.locator('.plan-tool').count(), 4,
    'Build, Your trade, Chain, and Scout stay reachable as composers and pickers');

  await page.click('[data-band="view"] .flow-band-conclusion');
  await page.waitForSelector('[data-band="view"] #plan-declare-view');
  assert.ok(await page.locator('[data-band="view"] .choice-group').count() > 0,
    'revisiting the concluded view band reopens the declaration controls');
});

test('Plan Evidence changes analysis history in place without changing its execution market', async () => {
  await page.evaluate(async () => {
    Learn.setLevel('beginner');
    await API.put('/api/datasets/active', { id: 'observed' });
    await App.refreshScenarioBanner();
  });
  const generated = await page.evaluate(async () => API.post('/api/datasets/generate', {
    symbol: 'AAPL', spec: { model: 'GBM', shape: 'CHOP', horizonDays: 63, stepsPerDay: 4,
      driftAnnual: 0, volAnnual: 0.25, jumpsPerYear: 0, jumpMean: 0, jumpVol: 0,
      tailNu: 6, seed: 818181, paths: 20 }
  }));
  const plan = await openPlan('AAPL', 'evidence', 'DIRECTIONAL', 'bullish');
  await page.waitForSelector('#plan-analysis-dataset');
  const original = await page.evaluate(() => ({ hash: location.hash, world: App.state.world,
    marketWorld: App.Market.world }));
  assert.equal(await page.inputValue('#plan-analysis-dataset'), 'observed');
  const beginnerOptions = await page.locator('#plan-analysis-dataset option').count();
  assert.ok(beginnerOptions >= 2, 'Beginner can choose the same generated analysis histories');
  assert.match(await page.textContent('#plan-analysis-source'), /never the market or account used to price this Plan/i);

  await page.selectOption('#plan-analysis-dataset', generated.datasetId);
  await page.waitForFunction(id => App.config.activeDataset === id, generated.datasetId);
  await page.waitForSelector('#plan-analysis-dataset');
  assert.equal(await page.inputValue('#plan-analysis-dataset'), generated.datasetId);
  assert.equal(await page.evaluate(() => location.hash), original.hash, 'dataset selection stays inside Plan Evidence');
  assert.deepEqual(await page.evaluate(() => ({ world: App.state.world, marketWorld: App.Market.world })),
    { world: original.world, marketWorld: original.marketWorld },
    'analysis history does not mutate the execution market or market store');
  assert.equal(await page.locator('#scenario-banner').count(), 1, 'generated history is globally disclosed');
  assert.match(await page.textContent('#scenario-banner'), /outside this saved dataset have no scenario history.*execution prices and accounts are unchanged/is,
    'the banner describes the closed analysis dataset rather than implying a silent observed fallback');
  await page.waitForFunction(() => !document.getElementById('scenario-plan'));
  assert.equal(await page.locator('#scenario-banner .btn').filter({ hasText: /Plan AAPL|Open AAPL Plan/ }).count(), 0,
    'the scenario banner does not offer a redundant Plan handoff while already inside that Plan');
  assert.match(await page.textContent('#plan-analysis-source'), /generated daily bars.*execution market do not change/is);

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#plan-analysis-dataset');
  assert.equal(await page.locator('#plan-analysis-dataset option').count(), beginnerOptions,
    'Expert changes density and provenance detail, not capability');
  assert.match(await page.textContent('#plan-analysis-source'), new RegExp('dataset ' + generated.datasetId));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p2-analysis-dataset-expert.png'), fullPage: true });

  await page.selectOption('#plan-analysis-dataset', 'observed');
  await page.waitForFunction(() => App.config.activeDataset === 'observed');
  await page.waitForSelector('#plan-analysis-dataset');
  assert.equal(await page.locator('#scenario-banner').count(), 0);
  assert.equal(await page.evaluate(() => location.hash), original.hash);
  await page.evaluate(async values => {
    await API.del('/api/datasets/' + values.datasetId);
    const live = await PlanStore.get(values.planId, true);
    await API.post('/api/plans/' + values.planId + '/archive', { expectedVersion: live.version });
    await PlanStore.refresh();
  }, { datasetId: generated.datasetId, planId: plan.id });
});

test('Plan Strategy owns the ranked field, exact Builder, and chain without route handoffs', async () => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'strategy');
  await page.waitForSelector('#plan-strategy-body', { timeout: 15000 }).catch(async error => {
    throw new Error('Strategy stage did not compose: ' + (await page.locator('#app').textContent())
      + '\n' + error.message);
  });
  // The proposals are the band's content; composers and pickers live in one compose row.
  assert.equal(await page.locator('#plan-strategy-panel-compare:not([hidden])').count(), 1,
    'the ranked comparison renders as the band content without a tab');
  assert.equal(await page.locator('.plan-tool').count(), 4, 'one compose row owns the composers and pickers');
  await assertNamedControls('#plan-stage-strategy');
  await assertTabContracts('#plan-stage-strategy');
  // Composer chips are disclosure toggles: press opens beside the proposals, press again folds.
  await page.locator('.plan-tool[data-strategy-tool="builder"]').click();
  await page.waitForSelector('.plan-tool[data-strategy-tool="builder"][aria-pressed="true"]');
  await page.waitForSelector('#plan-strategy-panel-builder:not([hidden])');
  await page.locator('.plan-tool[data-strategy-tool="yourTrade"]').click();
  await page.waitForSelector('.plan-tool[data-strategy-tool="yourTrade"][aria-pressed="true"]');
  assert.equal(await page.locator('.plan-tool[data-strategy-tool="builder"][aria-pressed="true"]').count(), 0,
    'one composer opens at a time');
  assert.match(await page.textContent('.plan-tool-selector'), /Compose your own.*All strategies.*Your trade.*Option prices.*Scout/s,
    'Beginner gets plain, capability-oriented composer labels');
  await page.locator('.plan-tool[data-strategy-tool="yourTrade"]').click();
  await page.waitForSelector('#plan-strategy-panel-yourTrade[hidden]', { state: 'attached' });
  assert.equal(await page.locator('.plan-tool[aria-pressed="true"]').count(), 0,
    'a second press folds composition back to the proposals');
  assert.equal(await page.locator('#plan-strategy-filters .xp-head:has-text("Only show proposed trades")').getAttribute('aria-expanded'), 'true',
    'the five fit limits use the available desktop width at both levels');
  assert.equal(await page.locator('#plan-stage-strategy a[href^="#/trade"], #plan-stage-strategy button:has-text("Open strategy tools")').count(), 0,
    'Strategy has no cross-section Trade handoff');

  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .ranked-idea-hero', { timeout: 30000 });
  await page.waitForTimeout(500);
  const heroArrival = await page.evaluate(() => {
    const hero = document.querySelector('#plan-ranked-field .ranked-idea-hero');
    const action = hero.querySelector('.plan-candidate-actions .btn');
    const sticky = document.querySelector('.topbar').getBoundingClientRect().bottom;
    const heroBox = hero.getBoundingClientRect(), actionBox = action.getBoundingClientRect();
    return { heroTop: heroBox.top, actionBottom: actionBox.bottom, sticky, viewport: innerHeight };
  });
  assert.ok(heroArrival.heroTop >= heroArrival.sticky - 2 && heroArrival.actionBottom <= heroArrival.viewport,
    'the ranked answer and primary action arrive together inside one desktop viewport: ' + JSON.stringify(heroArrival));
  assert.match(await page.textContent('#plan-budget-receipt'), /\$[\d,.]+ = 1% of \$[\d,.]+ current buying power/,
    'the per-idea budget explains its live dollar basis on the face of the screen');
  const servedRankedCount = Number((await page.textContent('.plan-strategy-summary .badge')).match(/\d+/)?.[0] || 0);
  // Desktop master-detail: the selected structure fills the DETAIL column while the ranked MENU lists
  // every OTHER structure at once beside it — reached without scrolling, no "see all" card wall.
  assert.equal(await page.locator('.ranked-layout .ranked-detail .ranked-idea-hero').count(), 1,
    'the selected structure fills the detail column');
  const menuVisible = await page.locator('.ranked-menu .ranked-runner-list .ranked-idea:visible').count();
  assert.equal(menuVisible, servedRankedCount - 1,
    'the ranked menu shows every other structure at once (' + menuVisible + ' of ' + (servedRankedCount - 1) + ')');
  const responsiveLayouts = [];
  for (const viewport of [{ width: 2560, height: 1440 }, { width: 2048, height: 900 },
    { width: 1920, height: 900 }, { width: 1440, height: 900 }, { width: 1280, height: 800 },
    { width: 1000, height: 900 }, { width: 390, height: 844 }, { width: 375, height: 812 },
    { width: 320, height: 700 }]) {
    await page.setViewportSize(viewport);
    const layout = await page.evaluate(() => {
      const box = selector => document.querySelector(selector)?.getBoundingClientRect();
      const app = box('#app'), controls = box('.plan-strategy-controls');
      const controlsHead = box('.plan-strategy-controls > .card-head');
      const filters = box('#plan-strategy-filters');
      const field = box('#plan-ranked-field'), hero = box('#plan-ranked-field .ranked-idea-hero');
      const menu = box('.ranked-menu');
      return { viewport: innerWidth, appWidth: app?.width || 0,
        documentWidth: document.documentElement.scrollWidth,
        controlsWidth: controls?.width || 0, controlsDisplay: controls ? getComputedStyle(document.querySelector('.plan-strategy-controls')).display : '',
        controlsHeadTop: controlsHead?.top || 0, filtersTop: filters?.top || 0,
        menuWidth: menu?.width || 0,
        fieldWidth: field?.width || 0, heroWidth: hero?.width || 0, heroHeight: hero?.height || 0,
        tools: document.querySelectorAll('.plan-tool').length,
        limits: document.querySelectorAll('#plan-strategy-filters input').length };
    });
    responsiveLayouts.push(layout);
    assert.ok(layout.documentWidth <= viewport.width + 2,
      'Strategy remains page-contained at ' + viewport.width + 'px: ' + JSON.stringify(layout));
    assert.ok(layout.appWidth >= viewport.width * .88,
      'the workspace spends the available width at ' + viewport.width + 'px: ' + JSON.stringify(layout));
    assert.deepEqual([layout.tools, layout.limits], [4, 5],
      'responsive composition preserves every Strategy capability at ' + viewport.width + 'px');
    assert.ok(layout.heroWidth <= layout.fieldWidth + 2 && layout.controlsWidth <= layout.appWidth + 2,
      'controls and ranked answer stay inside their owners at ' + viewport.width + 'px');
    if (viewport.width >= 1500) {
      // Master-detail: the ranked menu rail sits beside a majority-width detail — the width is spent
      // reading ACROSS, not on an empty runner track (the prior problem) nor one tall single hero.
      assert.ok(layout.menuWidth > 0 && layout.heroWidth >= layout.fieldWidth * .55
        && layout.heroWidth <= layout.fieldWidth * .8,
        'wide Strategy reads across: ranked menu beside a majority-width detail: ' + JSON.stringify(layout));
      assert.ok(Math.abs(layout.controlsHeadTop - layout.filtersTop) < 16,
        'question, limits, and consequence begin together on wide Strategy: ' + JSON.stringify(layout));
    }
    if (viewport.width === 2560) {
      await page.screenshot({
        path: path.join(__dirname, 'shots/program-one-plan-strategy-2560.png'), fullPage: false
      });
    }
    if (viewport.width === 390) {
      await page.screenshot({
        path: path.join(__dirname, 'shots/program-one-plan-strategy-mobile.png'), fullPage: false
      });
    }
  }
  const ownerLayout = responsiveLayouts[0];
  assert.ok(ownerLayout.appWidth >= 2400 && ownerLayout.heroHeight < 2200,
    '2560×1440 uses the owner’s logical 5K workspace and removes the prior 2,700px hero tower: '
      + JSON.stringify(ownerLayout));
  await page.setViewportSize({ width: 1280, height: 800 });
  const payoffDebug = await page.evaluate(() => ({
    svg: document.querySelectorAll('#plan-ranked-field .ranked-idea-payoff svg.chart').length,
    shape: document.querySelectorAll('#plan-ranked-field .ranked-time-spread-shape').length,
    hosts: document.querySelectorAll('#plan-ranked-field .ranked-idea-payoff').length,
    heroStrategy: (document.querySelector('#plan-ranked-field .ranked-idea-hero') || { dataset: {} }).dataset.candidateId,
    hostHtml: (document.querySelector('#plan-ranked-field .ranked-idea-payoff') || { innerHTML: 'NONE' }).innerHTML.slice(0, 160)
  }));
  assert.ok(payoffDebug.svg + payoffDebug.shape === 1,
    'the decision hero uses the evaluation’s real payoff points or an honest multi-expiry shape disclosure: '
      + JSON.stringify(payoffDebug));
  const firstHeroId = await page.locator('#plan-ranked-field .ranked-idea-hero').getAttribute('data-candidate-id');
  const runner = page.locator('#plan-ranked-field .ranked-runner-list .ranked-idea:visible').first();
  if (await runner.count()) {
    await runner.getByRole('button', { name: /Review rank/ }).click();
    await page.waitForFunction(previous => document.querySelector('#plan-ranked-field .ranked-idea-hero')?.dataset.candidateId !== previous,
      firstHeroId);
    assert.match(await page.textContent('.plan-proposed-heading'), /STRUCTURE UNDER REVIEW.*Compare this alternative/s,
      'reviewing a runner promotes it into the full decision surface instead of expanding another card below');
  }
  const rankedText = await page.textContent('#plan-stage-strategy');
  assert.match(rankedText, /Cash \/ no trade/, 'the no-trade baseline remains in the ranked decision field');
  assert.ok(await page.locator('#plan-strategy-results .ranked-idea[data-economic-verdict]').count() >= 1,
    'every proposed structure carries its economic placement');
  const economics = await page.locator('#plan-strategy-results .ranked-idea-hero .economic-assessment').allTextContents();
  assert.ok(economics.length > 0 && economics.every(text => /Market-implied EV/.test(text) && /Realized-vol scenario EV/.test(text)),
    'both EV lanes remain co-equal on the Plan candidate cards');
  assert.ok(await page.locator('#plan-rejected-teaching').count(),
    'mechanically refused and over-budget structures remain available as named teaching cases');

  if (await page.locator('#plan-strategy-filters .xp-head').getAttribute('aria-expanded') !== 'true') {
    await page.locator('#plan-strategy-filters .xp-head').click();
  }
  await page.fill('#plan-f-pop', '70');
  await page.locator('#plan-f-pop').blur();
  await page.waitForSelector('#plan-stage-strategy:has-text("Limits changed — rerun the field")');
  assert.equal(await page.locator('#plan-strategy-results .ranked-idea').count(), 0,
    'a changed request cannot leave the prior ranked field under new limits');
  assert.equal(await page.locator('#plan-strategy-filters .xp-head').getAttribute('aria-expanded'), 'true',
    'the open filter disclosure survives the same-route repaint');
  await page.fill('#plan-f-pop', '');
  await page.locator('#plan-f-pop').blur();
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .ranked-idea-hero', { timeout: 30000 });
  const first = page.locator('#plan-strategy-results .ranked-idea-hero');
  assert.match(await first.textContent(), /Theoretical max loss|Chance of any profit/);
  assert.equal(await first.locator('[data-vocabulary="theoreticalMaxProfit"]').count(), 1,
    'best-case profit uses the one registry-backed explanation instead of unexplained model jargon');
  assert.doesNotMatch(await first.locator('.ranked-idea-facts').textContent(), /model-dependent|theoretical ceiling/i,
    'the primary recommendation facts use plain, decision-relevant language');
  const uncoveredTechnicalLabels = await first.locator('.ranked-idea-facts').evaluate(root =>
    Array.from(root.querySelectorAll('.chip-label')).filter(label =>
      /max loss|best possible profit|max profit|chance of any profit|market-implied ev|realized-vol scenario ev/i
        .test(label.textContent || '')
      && !label.querySelector('.term[data-term], .info-trigger[data-term]'))
      .map(label => label.textContent.trim()));
  assert.deepEqual(uncoveredTechnicalLabels, [],
    'new recommendation labels must be backed by both-level registry help: ' + uncoveredTechnicalLabels.join(', '));
  assert.match(await first.textContent(),
    /Why this ranks first[\s\S]*Data coverage for this analysis[\s\S]*Portfolio impact/i,
    'Beginner proposals retain the same behavior, coverage, and Practice-impact assessment as an exact trade');
  assert.match(await first.textContent(), /How you would manage this trade/,
    'Beginner retains the plain-language management capability from the decision analysis');
  await first.getByRole('button', { name: 'Select and continue to Outcomes', exact: true }).click();
  await page.waitForFunction(() => location.hash.endsWith('/outcomes'));
  await page.waitForSelector('#plan-outcomes', { timeout: 15000 });
  const outcomeArrival = await page.evaluate(() => ({
    workspace: !!document.getElementById('plan-outcomes'),
    prerequisite: !!document.getElementById('plan-outcomes-prerequisite'),
    routeError: document.getElementById('route-error')?.textContent || '',
    text: (document.getElementById('app')?.innerText || '').slice(0, 1200)
  }));
  assert.ok(outcomeArrival.workspace && !outcomeArrival.prerequisite && !outcomeArrival.routeError,
    'selection has an unmistakable consequence: the exact package advances directly to Outcomes: '
      + JSON.stringify(outcomeArrival));
  await go('#/plan/' + plan.id + '/strategy');
  await page.waitForSelector('#plan-ranked-field .ranked-idea-hero.selected');
  assert.match(await page.textContent('#plan-ranked-field .ranked-idea-hero'), /SELECTED STRUCTURE[\s\S]*Continue to Outcomes/i,
    'returning to Strategy promotes the chosen structure above every alternative with its next action adjacent');
  assert.equal(await page.locator('#plan-stage-strategy button:has-text("Continue to Outcomes")').count(), 1,
    'Strategy exposes one next action instead of repeating it below the decision field');
  await page.locator('#plan-ranked-field .ranked-idea-hero.selected').evaluate(node =>
    node.scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p3-strategy-compare.png'), fullPage: false });

  await page.reload();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await page.waitForSelector('#plan-strategy-results button:has-text("Continue to Outcomes")', { timeout: 20000 });
  assert.match(await page.evaluate(() => location.hash), new RegExp('/plan/' + plan.id + '/strategy$'),
    'reload restores the Plan-owned Strategy stage');

  await page.locator('.plan-tool').filter({ hasText: 'All strategies' }).click();
  await page.waitForSelector('#builder');
  await page.waitForSelector('#builder-catalog .tpl');
  await assertNamedControls('#plan-stage-strategy');
  await assertTabContracts('#plan-stage-strategy');
  assert.ok(await page.locator('#builder-catalog .tpl-shape').count() >= 20,
    'the visual all-strategies catalog remains a first-class Beginner capability');
  assert.equal(await page.locator('#builder-symbol-context').count(), 0,
    'the Builder does not recapture the Plan symbol');
  assert.match(await page.textContent('#plan-header'), /AAPL/);
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p3-strategy-builder.png'), fullPage: true });

  await page.locator('.plan-tool').filter({ hasText: 'Option prices' }).click();
  await page.waitForSelector('#chain-anchor .card, #chain-anchor + .card, #expiration-select', { timeout: 20000 });
  await assertNamedControls('#plan-stage-strategy');
  await assertTabContracts('#plan-stage-strategy');
  assert.equal(await page.locator('#symbol-actions').count(), 0,
    'the Chain tool does not duplicate the old strategy-action surface');
  assert.equal(await page.locator('a[href="#/trade/structure"]').count(), 0,
    'the Plan chain cannot escape to the removed Structure route');

  await page.locator('.plan-tool').filter({ hasText: 'Scout' }).click();
  await page.waitForSelector('#plan-run-scout');
  await assertNamedControls('#plan-stage-strategy');
  await assertTabContracts('#plan-stage-strategy');
  const scoutSelectorStyle = await page.evaluate(() => {
    const control = document.querySelector('.plan-scout-scopes');
    const active = control.querySelector('[aria-selected="true"]');
    const idle = control.querySelector('[aria-selected="false"]');
    return {
      display: getComputedStyle(control).display,
      controlBg: getComputedStyle(control).backgroundColor,
      activeBg: getComputedStyle(active).backgroundColor,
      idleBg: getComputedStyle(idle).backgroundColor,
      activeHeight: active.getBoundingClientRect().height
    };
  });
  assert.equal(scoutSelectorStyle.display, 'inline-flex', 'the Scout modes form one local segmented control');
  assert.notEqual(scoutSelectorStyle.controlBg, scoutSelectorStyle.activeBg,
    'the selected Scout mode has a visible active treatment');
  assert.notEqual(scoutSelectorStyle.activeBg, scoutSelectorStyle.idleBg,
    'active and idle Scout modes cannot collapse into raw browser buttons');
  assert.ok(scoutSelectorStyle.activeHeight >= 34, 'Scout mode targets remain usable');
  await page.locator('.plan-scout-scopes button').filter({ hasText: 'Better fits' }).click();
  await page.click('#plan-run-scout');
  await page.waitForSelector('#plan-scout-results .candidate', { timeout: 30000 });
  const scoutTheses = await page.locator('#plan-scout-results .plan-scout-symbol .badge').allTextContents();
  assert.ok(scoutTheses.length > 0 && scoutTheses.every(text => text.trim().toLowerCase() === plan.context.thesis.toLowerCase()),
    'focused Scout prices every candidate under the Plan-owned thesis instead of silently substituting a symbol signal');
  assert.equal(await page.locator('#plan-run-scout').textContent(), 'Refresh this scan',
    'a repeat Scout action is named as a refresh rather than an unexplained second run');
  const scoutedSymbol = (await page.locator('#plan-scout-results .plan-scout-symbol b').first().textContent()).trim();
  assert.notEqual(scoutedSymbol, 'AAPL', 'in-Plan Scout returns a separate underlying');
  await page.locator('#plan-scout-results button').filter({ hasText: 'Open as linked Plan' }).first().click();
  await page.waitForFunction((symbol) => location.hash.includes('/plan/')
    && document.querySelector('#plan-header h1')?.textContent.includes(symbol), scoutedSymbol);
  await page.waitForSelector('#plan-strategy-body');
  assert.match(await page.textContent('#plan-header'), new RegExp(scoutedSymbol),
    'the pick opens as a linked sibling rather than changing the origin Plan symbol');
  await page.waitForSelector('.plan-selected-structure.ranked-idea-hero', { timeout: 15000 });
  assert.equal(await page.locator('.plan-selected-structure.ranked-idea-hero').count(), 1,
    'a linked Scout pick uses the same selected decision hero as a ranked Plan pick');
  assert.equal(await page.locator('#plan-stage-strategy button:has-text("Continue to Outcomes")').count(), 1,
    'the linked selection has one adjacent next action and no duplicate stage footer');
  assert.ok(await page.locator('#plan-strategy-results button:has-text("Continue to Outcomes")').count()
      || await page.locator('#plan-run-strategy').count(),
    'the sibling Plan owns Strategy rather than a cross-underlying handoff');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p3-strategy-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });
});

test('Plan Strategy preserves intent-native ladders, income capital, and Expert density', async () => {
  await page.evaluate(() => {
    Learn.setLevel('beginner');
    App.context.update({ goal: 'ACQUIRE', thesis: 'neutral', horizon: 'month' });
  });
  await openPlan('QQQ', 'strategy', 'ACQUIRE', 'neutral', 'month');
  await page.waitForSelector('#plan-intent-ladder', { timeout: 30000 }).catch(async error => {
    throw new Error('Intent ladder did not render: ' + (await page.locator('#app').textContent()) + '\n' + error.message);
  });
  assert.match(await page.textContent('#plan-intent-ladder'), /Name your buy price.*Buy strike.*Discount after premium.*Chance you buy/s,
    'Buy-at-a-discount leads with a strike ladder rather than a generic strategy list');
  const totalRungs = Number((await page.textContent('#plan-intent-ladder .card-head')).match(/(\d+) RUNGS/)[1]);
  assert.ok(totalRungs > 3, 'fixture ladder exercises the compact-to-full disclosure');
  assert.equal(await page.locator('#plan-intent-ladder .ladder-row').count(), 3,
    'Beginner starts with three representative prices instead of a wall of full-width rows');
  assert.equal(await page.locator('#plan-ladder-toggle').getAttribute('aria-expanded'), 'false');
  await page.waitForTimeout(300); // observe the real card-arrival animation; never disable it for screenshots
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p11-ladder-beginner.png'), fullPage: true });
  await page.click('#plan-ladder-toggle');
  await page.waitForFunction(expected => document.querySelectorAll('#plan-intent-ladder .ladder-row').length === expected,
    totalRungs);
  assert.equal(await page.locator('#plan-ladder-toggle').getAttribute('aria-expanded'), 'true');
  const discountLabels = await page.locator('#plan-intent-ladder .ladder-row').evaluateAll(rows => rows.map(row => {
    const chip = Array.from(row.querySelectorAll('.chip')).find(item =>
      item.querySelector('.chip-label')?.textContent.trim() === 'Discount after premium');
    return chip ? chip.querySelector('b')?.textContent.trim() : '';
  }));
  assert.ok(discountLabels.every(value => /^\d+(?:\.\d+)?% (?:below|above|at) now$/.test(value)),
    'every acquire rung discloses its effective discount or premium to the current price');
  const firstLadderDetail = page.locator('#plan-intent-ladder .ladder-row').first();
  await firstLadderDetail.locator('.xp-head').click();
  assert.equal(await firstLadderDetail.locator('.candidate-workflow-actions').count(), 0,
    'a package already inside a Plan never offers a second Use-this-in-a-Plan path');
  await page.locator('#plan-intent-ladder .ladder-row .btn:has-text("Select this rung")').first().click();
  await page.waitForSelector('#plan-intent-ladder .ladder-row.selected');
  assert.match(await page.textContent('#plan-intent-ladder .inline-action-feedback'), /Rung selected.*Outcomes/is,
    'ladder selection reports its consequence inside the ladder');
  assert.equal(await page.locator('#plan-intent-ladder .ladder-row.selected').count(), 1,
    'a selected rung is highlighted in place instead of confirming below the fold');
  assert.ok(await page.locator('.plan-clear-structure').isVisible(),
    'a hypothetical Plan structure can be cleared before a decision');
  await page.locator('.plan-clear-structure').click();
  await page.waitForFunction(() => !document.querySelector('#plan-intent-ladder .ladder-row.selected'));
  assert.equal(await page.locator('.plan-clear-structure').count(), 0,
    'clearing the selection removes the durable chosen package while retaining the ladder');

  const incomePlan = await openPlan('SPY', 'strategy', 'INCOME');
  await page.waitForSelector('#plan-income-board');
  assert.match(await page.textContent('#plan-income-board'), /Free buying power.*Shares available/s,
    'income keeps its capital-and-collateral picture');
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p11-income-beginner.png'), fullPage: true });

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#plan-compose');
  assert.match(await page.textContent('.plan-tool-selector'), /Compose your own.*Builder.*Your trade.*Chain.*Scout/s,
    'Expert receives dense professional composer labels');
  assert.equal(await page.locator('#plan-strategy-filters .plan-filter-grid input').count(), 5,
    'Expert sees the same five limits inline');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results table tbody tr', { timeout: 30000 });
  const expertContainment = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    stage: document.querySelector('#plan-stage-strategy').getBoundingClientRect().width,
    results: document.querySelector('#plan-strategy-results').getBoundingClientRect().width,
    tableViewport: document.querySelector('#compare-table .tbl-wrap').getBoundingClientRect().width,
    tableContent: document.querySelector('#compare-table table').getBoundingClientRect().width
  }));
  assert.ok(expertContainment.document <= expertContainment.viewport + 2,
    'Expert ranked field must scroll inside its card, never widen the page: ' + JSON.stringify(expertContainment));
  assert.ok(expertContainment.results <= expertContainment.stage + 2,
    'ranked results remain contained by the Plan stage');
  assert.ok(expertContainment.tableContent > expertContainment.tableViewport,
    'the dense Expert table keeps its horizontal detail inside a deliberate local scroller');
  const expertRows = page.locator('#plan-strategy-results table tbody tr.clickable');
  assert.ok(await expertRows.count() >= 1, 'Expert ranked field has auditable rows');
  await expertRows.first().click();
  await page.waitForSelector('#plan-ranked-field .ranked-idea-hero .candidate-evaluation-receipt');
  const expertDetail = await page.textContent('#plan-ranked-field .ranked-idea-hero');
  assert.match(expertDetail, /Data coverage for this analysis/,
    'Expert retains one detailed per-input coverage receipt behind the ranking');
  assert.match(expertDetail, /How the Decision score was built/, 'Expert retains the score construction');
  assert.match(expertDetail, /Mechanical management plan/, 'Expert retains the management receipt');
  await page.locator('#plan-ranked-field .ranked-idea-hero').scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p14-strategy-audit-expert.png'), fullPage: false });
  const plansBeforeExpertSelect = await page.evaluate(async () => {
    const payload = await API.getFresh('/api/plans');
    return Array.isArray(payload) ? payload.length : (payload.plans || []).length;
  });
  await expertRows.first().locator('button').filter({ hasText: /Select \+ continue|Continue/ }).click();
  await page.waitForFunction(() => location.hash.endsWith('/outcomes')
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  const plansAfterExpertSelect = await page.evaluate(async () => {
    const payload = await API.getFresh('/api/plans');
    return Array.isArray(payload) ? payload.length : (payload.plans || []).length;
  });
  assert.equal(plansAfterExpertSelect, plansBeforeExpertSelect,
    'selecting an Expert ranked row updates this Plan and never creates a duplicate Plan');
  assert.match(await page.evaluate(() => location.hash), new RegExp('/plan/' + incomePlan.id + '/outcomes$'),
    'the ranked-row action advances inside the current Plan instead of leaving selection below the fold');
});

test('Plan Builder preserves the Beginner walkthrough and Expert exact-contract terminal', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('TSLA', 'strategy', 'DIRECTIONAL', 'neutral');
  await page.locator('.plan-tool').filter({ hasText: 'All strategies' }).click();
  await page.waitForSelector('#builder-catalog .tpl[data-tpl="IRON_CONDOR"]', { timeout: 20000 });
  assert.ok(await page.locator('#builder-catalog .tpl-shape svg').count() >= 24,
    'Beginner retains the complete visual payoff catalog');
  assert.ok(await page.locator('#builder-catalog .tpl .badge:has-text("BLOCKED")').count() >= 3,
    'undefined-risk structures remain visible as labeled lessons');
  await page.click('#builder-catalog .tpl[data-tpl="NAKED_PUT"]');
  await page.waitForSelector('#bw-walk');
  assert.ok(await page.locator('#bw-walk .xp-head:has-text("How this trade works")').count(),
    'the promised blocked-structure walkthrough exists instead of disappearing at the risky example');
  await page.locator('#bw-walk button').filter({ hasText: 'Back' }).click();
  await page.waitForSelector('#builder-catalog .tpl[data-tpl="IRON_CONDOR"]');
  await page.click('#builder-catalog .tpl[data-tpl="IRON_CONDOR"]');
  await page.waitForSelector('#bw-walk');
  await page.waitForFunction(() => /Theoretical max loss/.test(
    document.getElementById('bw-impact')?.textContent || ''), { timeout: 20000 }).catch(async error => {
      throw new Error('Builder leg impact did not price: '
        + (await page.locator('#bw-impact').textContent().catch(() => '<missing>')) + '\n' + error.message);
    });
  assert.match(await page.textContent('#bw-leg-story'), /Sell the \$\d+/,
    'the walkthrough explains what the current contract contributes');
  assert.match(await page.textContent('#bw-impact'), /Theoretical max loss/);
  for (let leg = 2; leg <= 4; leg++) {
    await page.click('#bw-next');
    await page.waitForFunction(n => new RegExp('leg ' + n + ' of 4').test(
      document.querySelector('#bw-walk .field-label')?.textContent || ''), leg, { timeout: 20000 });
  }
  await page.click('#bw-next');
  await page.waitForSelector('#bw-final');
  await page.waitForFunction(() => /Passes the safety screens|Allowed/.test(
    document.getElementById('bw-panel')?.textContent || ''), { timeout: 20000 });
  assert.ok(await page.locator('#bw-panel .chart-wrap').count(), 'the exact package retains its payoff chart');
  assert.match(await page.textContent('#bw-final'), /Assignment odds/);
  await page.click('#builder-review');
  await page.waitForSelector('.plan-selected-structure', { timeout: 30000 });
  const selected = await page.evaluate(async id => API.getFresh('/api/plans/' + id + '/strategy/latest'), plan.id);
  assert.equal(selected.selected.sourceKind, 'CUSTOM', 'the exact Builder package is durable Plan state');
  assert.equal(selected.selected.legs.length, 4, 'all four reviewed contracts survive the save');

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#plan-stage-strategy');
  await page.locator('.plan-tool').filter({ hasText: 'Builder' }).click();
  await page.waitForSelector('#builder-template');
  await page.selectOption('#builder-template', 'IRON_CONDOR');
  await page.waitForFunction(() => document.querySelectorAll('#builder-legs .leg-row').length === 4,
    { timeout: 20000 });
  await page.waitForFunction(() => /ALLOW|WARN/.test(document.getElementById('builder-panel')?.textContent || ''),
    { timeout: 20000 });
  assert.match(await page.textContent('#builder-panel'), /Net Δ sh/,
    'Expert retains live net Greeks for the exact editable package');
  assert.ok(await page.locator('#builder-add-leg').count(), 'Expert can add a custom contract leg');
  const before = await page.locator('#builder-legs .leg-row').count();
  await page.click('#builder-add-leg');
  await page.waitForFunction(n => document.querySelectorAll('#builder-legs .leg-row').length === n + 1, before);
  assert.equal(await page.locator('#builder-legs .leg-row').count(), before + 1,
    'custom multi-leg construction remains available inside the Plan');
  await page.evaluate(async () => { Learn.setLevel('beginner'); await App.render(); });
});

test('Plan Builder retries its provider locally without remounting Flow or losing the draft', async () => {
  await page.evaluate(() => Learn.setLevel('expert'));
  const plan = await openPlan('AAPL', 'strategy', 'DIRECTIONAL', 'bullish');
  await page.evaluate(id => {
    const ui = PlanStore.ui(id);
    ui.buildState = { builderForm: {
      symbol: 'AAPL', goal: 'DIRECTIONAL', qty: 3, step: 2,
      templateKey: null, legIdx: 0, legs: [], excluded: {}, limits: {}
    } };
    API.flushCache();
  }, plan.id);

  let attempts = 0;
  const failOnce = async route => {
    if (attempts++ === 0) {
      await route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ error: 'forced option-book interruption' }) });
    } else {
      await route.continue();
    }
  };
  await page.route('**/api/research/AAPL', failOnce);
  try {
    await page.locator('.plan-tool[data-strategy-tool="builder"]').click();
    await page.waitForSelector('#builder-load-error');
    await page.evaluate(() => {
      window.__builderRetryFlow = document.getElementById('plan-flow');
      window.__builderRetryPanel = document.getElementById('plan-strategy-panel-builder');
    });
    await page.click('#builder-load-error button:has-text("Retry")');
    await page.waitForSelector('#builder-template', { timeout: 20000 });
    const result = await page.evaluate(id => ({
      sameFlow: window.__builderRetryFlow === document.getElementById('plan-flow'),
      samePanel: window.__builderRetryPanel === document.getElementById('plan-strategy-panel-builder'),
      hash: location.hash,
      qty: PlanStore.ui(id).buildState.builderForm.qty,
      step: PlanStore.ui(id).buildState.builderForm.step,
      errorGone: !document.getElementById('builder-load-error')
    }), plan.id);
    assert.deepEqual(result, {
      sameFlow: true,
      samePanel: true,
      hash: '#/plan/' + plan.id + '/strategy',
      qty: 3,
      step: 2,
      errorGone: true
    }, 'retry repaints only the mounted Builder root and keeps its Plan-owned draft');
  } finally {
    await page.unroute('**/api/research/AAPL', failOnce);
    await page.evaluate(() => {
      delete window.__builderRetryFlow;
      delete window.__builderRetryPanel;
      Learn.setLevel('beginner');
    });
  }
});

test('Plan Builder loads every option leg from its own expiration and option-side book', async () => {
  const firstExpiry = '2026-08-14';
  const secondExpiry = '2026-09-18';
  await page.route('**/api/research/AAPL/chain?expiration=*', async route => {
    const expiration = new URL(route.request().url()).searchParams.get('expiration');
    const book = expiration === firstExpiry
      ? { calls: [{ strike: 110 }, { strike: 111 }], puts: [{ strike: 90 }, { strike: 91 }] }
      : { calls: [{ strike: 310 }, { strike: 311 }], puts: [{ strike: 220 }, { strike: 222 }] };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(book) });
  });

  await page.evaluate(() => Learn.setLevel('expert'));
  const plan = await openPlan('AAPL', 'strategy', 'DIRECTIONAL', 'neutral');
  await page.evaluate(({ id, firstExpiry, secondExpiry }) => {
    API.flushCache();
    const ui = PlanStore.ui(id);
    ui.strategyView = 'builder';
    ui.buildState = { builderForm: {
      symbol: 'AAPL', goal: 'DIRECTIONAL', qty: 1, step: 4,
      legs: [
        { action: 'BUY', type: 'CALL', strike: '111', expiration: firstExpiry, ratio: 1 },
        { action: 'SELL', type: 'PUT', strike: '222', expiration: secondExpiry, ratio: 1 }
      ]
    } };
    return App.render();
  }, { id: plan.id, firstExpiry, secondExpiry });
  await page.waitForFunction(() => document.querySelectorAll('#builder-legs .leg-row').length === 2);
  assert.deepEqual(await page.locator('#builder-legs .leg-row[data-leg="0"] .leg-strike option')
    .evaluateAll(options => options.map(option => option.value)), ['110', '111'],
  'the call leg uses the call grid from its own expiration');
  assert.deepEqual(await page.locator('#builder-legs .leg-row[data-leg="1"] .leg-strike option')
    .evaluateAll(options => options.map(option => option.value)), ['220', '222'],
  'the put leg uses the put grid from its own expiration');

  await page.unroute('**/api/research/AAPL/chain?expiration=*');
  await page.evaluate(async id => {
    const live = await PlanStore.get(id, true);
    await API.post('/api/plans/' + id + '/archive', { expectedVersion: live.version });
    await PlanStore.refresh();
    Learn.setLevel('beginner');
  }, plan.id);
});

test('Plan Builder retains synthetic exposure sizing at both experience levels', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('SPY', 'strategy', 'DIRECTIONAL', 'bullish');
  await page.locator('.plan-tool').filter({ hasText: 'All strategies' }).click();
  await page.waitForSelector('#builder-catalog .tpl[data-tpl="SYNTHETIC_LONG"]', { timeout: 20000 });
  await page.click('#builder-catalog .tpl[data-tpl="SYNTHETIC_LONG"]');
  await page.waitForSelector('#bw-walk');
  await page.click('#bw-next');
  await page.waitForFunction(() => /leg 2 of 2/i.test(document.querySelector('#bw-walk .field-label')?.textContent || ''));
  await page.click('#bw-next');
  await page.waitForSelector('#bw-final #builder-exposure-sizer');
  await page.fill('#builder-exposure-target', '50000');
  await page.click('#builder-size-exposure');
  await page.waitForFunction(() => /Contracts.*Delta exposure.*Equivalent shares would cost/s.test(
    document.getElementById('builder-exposure-output')?.textContent || ''), { timeout: 20000 });
  assert.match(await page.textContent('#builder-exposure-output'), /Contracts.*Equivalent shares would cost/s);
  assert.match(await page.evaluate(() => location.hash), new RegExp('/plan/' + plan.id + '/strategy$'),
    'exposure sizing stays inside the canonical Plan Builder');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p3-synthetic-exposure-beginner.png'), fullPage: true });

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#plan-stage-strategy');
  if (await page.locator('.plan-tool[data-strategy-tool="builder"]').getAttribute('aria-pressed') !== 'true') {
    await page.locator('.plan-tool').filter({ hasText: 'Builder' }).click();
  }
  await page.waitForSelector('#builder-template');
  if (await page.inputValue('#builder-template') !== 'SYNTHETIC_LONG') {
    await page.selectOption('#builder-template', 'SYNTHETIC_LONG');
  }
  await page.waitForSelector('#builder-exposure-sizer');
  assert.equal(await page.inputValue('#builder-exposure-target'), '50000',
    'the same Builder-owned sizing state survives the presentation-level change');
  if (!/Contracts.*Delta exposure/s.test(await page.textContent('#builder-exposure-output'))) {
    await page.click('#builder-size-exposure');
    await page.waitForFunction(() => /Contracts.*Delta exposure/s.test(
      document.getElementById('builder-exposure-output')?.textContent || ''), { timeout: 20000 });
  }
  assert.match(await page.textContent('#builder-exposure-output'), /Contracts.*Delta exposure/s,
    'Expert receives the same sized exposure result in the dense terminal');

  await page.evaluate(async id => {
    const live = await PlanStore.get(id, true);
    await API.post('/api/plans/' + id + '/archive', { expectedVersion: live.version });
    await PlanStore.refresh();
  }, plan.id);
});

test('Plan Outcomes gives an exact-contract prerequisite instead of a dead empty band', async () => {
  await page.evaluate(() => {
    Learn.setLevel('beginner');
    App.context.update({ symbol: 'TSLA', goal: 'DIRECTIONAL', thesis: 'neutral', horizon: '58d' });
  });
  const plan = await openPlan('TSLA', 'outcomes', 'DIRECTIONAL', 'neutral', '58d');
  await page.waitForSelector('#plan-flow');
  assert.equal(await page.locator('[data-band="outcomes"]').getAttribute('data-posture'), 'locked',
    'Outcomes without exact contracts is a locked band, never a dead empty stage');
  assert.match(await page.textContent('[data-band="outcomes"] .flow-band-title'), /Outcomes on your structure/,
    'the locked band keeps its title visible instead of hiding the capability');
  assert.match(await page.textContent('[data-band="outcomes"] .flow-band-locked'), /select a structure/i,
    'the locked state explains that choosing exact contracts unlocks the outcome lenses');
  const geometry = await page.locator('[data-band="outcomes"]').evaluate(card => {
    const box = card.getBoundingClientRect();
    return { height: box.height, pageWidth: document.documentElement.scrollWidth, viewport: innerWidth };
  });
  assert.ok(geometry.height < 360 && geometry.pageWidth <= geometry.viewport,
    'the locked band is compact and cannot widen the page: ' + JSON.stringify(geometry));
  assert.equal(await page.locator('[data-band="commit"]').getAttribute('data-posture'), 'locked',
    'Commit stays locked until a structure is selected');
  assert.match(await page.textContent('[data-band="commit"] .flow-band-locked'), /select a structure/i,
    'the locked Commit band names the same unlock condition');
  assert.equal(await page.locator('[data-band="strategy"]').getAttribute('data-posture'), 'active',
    'the flow leaves Strategy open as the one clear next action while outcomes wait');
  await go('#/plan/' + plan.id + '/strategy');
  await page.waitForSelector('#plan-stage-strategy');
  await page.evaluate(async id => {
    const live = await PlanStore.get(id, true);
    await API.post('/api/plans/' + id + '/archive', { expectedVersion: live.version });
    await PlanStore.refresh();
  }, plan.id);
});

test('Plan Outcomes reuses Evidence paths for one exact selected package', async () => {
  await page.evaluate(() => {
    Learn.setLevel('beginner');
    App.context.update({ symbol: 'QQQ', goal: 'DIRECTIONAL', thesis: 'bullish', horizon: '45d' });
  });
  const plan = await openPlan('QQQ', 'evidence', 'DIRECTIONAL', 'bullish', '45d');
  await page.waitForSelector('#test-your-view', { timeout: 15000 });
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.waitForSelector('#research-outcomes-basis-futures');
  await page.click('#research-outcomes-basis-futures');
  await page.waitForSelector('#whatif-card');
  await page.click('#whatif-run');
  await page.waitForSelector('.scenario-decision', { timeout: 30000 });
  const receipt = await page.evaluate(() => {
    const ui = PlanStore.ui(App.state.activePlanId).evidence;
    return { id: ui.planEnsembleId, fingerprint: ui.planEnsembleFingerprint };
  });
  assert.ok(receipt.id && receipt.fingerprint, 'Evidence saves the exact ensemble before Outcomes uses it');

  await go('#/plan/' + plan.id + '/strategy');
  await page.waitForSelector('#plan-run-strategy');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .ranked-idea-hero', { timeout: 30000 });
  await page.evaluate(async planId => {
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    const candidate = latest.strategy.result.candidates.find(item => {
      const expirations = new Set((item.legs || []).filter(leg => leg.type !== 'STOCK').map(leg => leg.expiration));
      return expirations.size === 1;
    });
    if (!candidate) throw new Error('fixture field contains no same-expiry package for market-odds test');
    const live = await PlanStore.get(planId, true);
    await PlanStore.selectCandidate(live, candidate.id);
    await App.render();
  }, plan.id);
  await page.waitForSelector('#plan-strategy-results button:has-text("Continue to Outcomes")');

  await go('#/plan/' + plan.id + '/outcomes');
  await page.waitForSelector('#plan-outcomes');
  assert.match(await page.textContent('#plan-outcomes-nav'), /Possible futures \(Monte Carlo\)/,
    'the modeled path lens uses the discoverable Monte Carlo name');
  await assertNamedControls('#plan-outcomes');
  await assertTabContracts('#plan-outcomes');
  assert.match(await page.textContent('.plan-outcome-position'), /Exact position being tested.*PLAN OWNED/s);
  const selectedSummary = await page.evaluate(async id => (await API.getFresh('/api/plans/' + id + '/outcomes/latest')).selected, plan.id);
  assert.ok((selectedSummary.breakevens || []).every(value => Number(value) > 0),
    'a listed option package never renders a zero-dollar breakeven: ' + JSON.stringify(selectedSummary.breakevens));
  assert.equal(await page.locator('.plan-outcome-position .candidate-contract-line').count(), selectedSummary.legs.length,
    'the exact listed contracts are visible without opening another candidate card');
  await page.waitForFunction(() => document.querySelectorAll('.plan-outcome-position .realistic-strip-case').length === 4,
    null, { timeout: 30000 });
  let repaintScenarioCalls = 0;
  await page.route('**/api/evaluate', async route => { repaintScenarioCalls++; await route.continue(); });
  await page.evaluate(() => App.render());
  await page.waitForSelector('.plan-outcome-position .realistic-strip-case');
  await page.waitForTimeout(250);
  await page.unroute('**/api/evaluate');
  assert.equal(repaintScenarioCalls, 0, 'a repaint reuses the exact package scenario strip instead of rerunning paths');
  assert.equal(await page.locator('#plan-outcomes input[name="symbol"], #plan-outcomes select[name="strategy"]').count(), 0,
    'Outcomes does not recapture the Plan symbol or structure');

  await page.getByRole('button', { name: 'Calculate market odds' }).click();
  await page.waitForSelector('.plan-market-outcome', { timeout: 30000 }).catch(async error => {
    throw new Error('Market odds did not render. Page: ' + (await page.locator('#app').textContent())
      + '\n' + error.message);
  });
  assert.match(await page.textContent('.plan-market-outcome'), /risk-neutral odds.*not a forecast/is);

  await page.click('#plan-outcomes-basis-model');
  await page.getByRole('button', { name: 'Test this position on the stored futures' }).click();
  await page.waitForSelector('[data-basis-summary="PARAMETRIC"]', { timeout: 30000 });
  const exactReuse = await page.evaluate(async ({ planId, ensembleId }) => {
    const latest = await API.getFresh('/api/plans/' + planId + '/outcomes/latest');
    const run = latest.outcomes.find(item => item.basis === 'PARAMETRIC');
    return { ensembleId: run && run.ensembleId, selected: latest.selected && latest.selected.id };
  }, { planId: plan.id, ensembleId: receipt.id });
  assert.equal(exactReuse.ensembleId, receipt.id,
    'the position is valued on the exact Evidence ensemble, not a regenerated lookalike');
  assert.ok(exactReuse.selected, 'one exact Plan package owns every outcome lens');

  await page.click('#plan-compare-parametric');
  await page.waitForSelector('.plan-proposal-comparison-result', { timeout: 30000 });
  const compared = await page.evaluate(async ({ planId, fingerprint }) => {
    const latest = await API.getFresh('/api/plans/' + planId + '/outcomes/latest');
    const comparison = latest.comparisons.find(item => item.basis === 'PARAMETRIC');
    const field = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    return { fingerprint: comparison && comparison.ensembleFingerprint,
      items: comparison && comparison.items.length,
      candidates: field.strategy.result.candidates.length,
      cash: comparison && comparison.items.some(item => item.key === 'CASH') };
  }, { planId: plan.id, fingerprint: receipt.fingerprint });
  assert.equal(compared.fingerprint, receipt.fingerprint,
    'every proposal is judged on the exact Evidence receipt rather than regenerated futures');
  assert.equal(compared.items, compared.candidates + 1,
    'the complete current proposal field plus cash is preserved in the comparison');
  assert.equal(compared.cash, true, 'cash is a first-class zero-risk baseline');
  assert.match(await page.textContent('.plan-proposal-comparison-result'),
    /Same paths.*different structures.*Which proposal handles this evidence best/is);
  const initialBeginnerRows = await page.locator('.plan-proposal-result').count();
  assert.ok(initialBeginnerRows >= 3 && initialBeginnerRows <= 4,
    'Beginner leads with a readable subset plus cash instead of a dense wall');
  const showAll = page.getByText(new RegExp('Show all ' + compared.items + ' compared choices'));
  if (await showAll.count()) {
    await showAll.click();
    assert.equal(await page.locator('.plan-proposal-result').count(), compared.items,
      'every compared proposal remains reachable at Beginner');
  }
  await page.reload();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await page.click('#plan-outcomes-basis-model');
  await page.waitForSelector('.plan-proposal-comparison-result', { timeout: 20000 });
  assert.match(await page.textContent('.plan-proposal-comparison-result .lineage-chip'), /Simulation.{0,3}#[0-9a-f]{6}/i,
    'the restored comparison names the exact stored fan it reprices — a durable receipt without an internal token');

  const reviewTrade = page.locator('.plan-proposal-comparison-result button').filter({ hasText: 'Review this trade' }).first();
  assert.equal(await reviewTrade.count(), 1, 'an alternative has an explicit return path to the owning Strategy step');
  await reviewTrade.click();
  await page.waitForSelector('#plan-stage-strategy .plan-return-focus', { timeout: 15000 });
  assert.match(await page.evaluate(() => location.hash), new RegExp('/plan/' + plan.id + '/strategy$'));
  await go('#/plan/' + plan.id + '/outcomes');
  await page.click('#plan-outcomes-basis-model');
  await page.waitForSelector('.plan-proposal-comparison-result');

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.click('#plan-outcomes-basis-model');
  await page.waitForSelector('.plan-proposal-table');
  assert.equal(await page.locator('.plan-proposal-table tbody tr').count(), compared.items,
    'Expert sees the complete comparison as one dense table');
  const visibleAction = await page.locator('.plan-proposal-table .plan-proposal-name-cell button').first().evaluate(button => {
    const cellRect = button.getBoundingClientRect();
    const wrapRect = button.closest('.tbl-wrap').getBoundingClientRect();
    return { left: cellRect.left, right: cellRect.right, wrapLeft: wrapRect.left, wrapRight: wrapRect.right };
  });
  assert.ok(visibleAction.left >= visibleAction.wrapLeft - 1 && visibleAction.right <= visibleAction.wrapRight + 1,
    'the Expert action fits in the initial table viewport without hidden horizontal navigation');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p4-comparison-expert.png'), fullPage: true });
  await page.evaluate(async () => { Learn.setLevel('beginner'); await App.render(); });
  await page.click('#plan-outcomes-basis-model');
  await page.waitForSelector('.plan-proposal-comparison-result');

  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p4-outcomes-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);
  const modeRects = await page.$$eval('#plan-outcomes-nav .outcome-basis', buttons => {
    const host = document.getElementById('plan-outcomes-nav').getBoundingClientRect();
    return buttons.map(button => { const r = button.getBoundingClientRect(); return { left:r.left,right:r.right,hostLeft:host.left,hostRight:host.right }; });
  });
  assert.equal(modeRects.length, 4);
  for (const item of modeRects) assert.ok(item.left >= item.hostLeft - 1 && item.right <= item.hostRight + 1,
    'all four Outcome bases stay visible on mobile');
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p4-outcomes-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });

  await page.click('#plan-outcomes-basis-backtest');
  await page.waitForSelector('#plan-backtest-result', { state: 'attached' });
  await assertNamedControls('#plan-outcomes');
  await assertTabContracts('#plan-outcomes');
  assert.match(await page.textContent('#plan-outcomes-panel'), /Replay.*QQQ|Historical replay/i);
  assert.equal(await page.locator('#plan-outcomes-panel input[type="date"]').count(), 2,
    'historical replay retains its useful controls at Beginner rather than hiding capability');
  assert.equal(await page.locator('#plan-outcomes-panel [aria-label="Replay date range"] button').count(), 4,
    'Beginner keeps fast date-window controls alongside exact dates');
  assert.equal(await page.locator('#plan-outcomes-panel [aria-label="Target days to expiry"] button').count(), 3,
    'Beginner keeps the familiar weekly, monthly, and quarterly expiry presets');
  await page.getByRole('button', { name: 'Quarterly' }).click();
  assert.equal(await page.locator('#plan-outcomes-panel input[type="number"]').first().inputValue(), '90',
    'the visual expiry preset drives the same exact DTE input');
  await page.getByRole('button', { name: 'Run historical replay' }).click();
  await page.waitForSelector('#plan-backtest-result .grid', { timeout: 30000 });
  assert.match(await page.textContent('#plan-backtest-result'),
    /Replay model inputs: .* annual rate from .*not a historical yield curve/i,
    'historical replay discloses its lane-owned rate and constant-rate convention');
  await captureSettled('plan-p4-backtest-desktop.png');
  await page.setViewportSize({ width: 390, height: 844 });
  const replayMobile = await page.evaluate(() => ({
    fits: document.documentElement.scrollWidth <= innerWidth, vw: innerWidth,
    offenders: Array.from(document.querySelectorAll('#app *')).map(node => {
      const r = node.getBoundingClientRect();
      return { id: node.id || '', cls: String(node.className).slice(0, 44), right: Math.round(r.right) };
    }).filter(row => row.right > innerWidth + 1).slice(0, 6)
  }));
  assert.equal(replayMobile.fits, true,
    'the replay model disclosure and report stay inside the mobile viewport: ' + JSON.stringify(replayMobile));
  assert.equal(await page.locator('.plan-replay-trades tbody tr').first().evaluate(row => getComputedStyle(row).display), 'grid',
    'mobile replay trades become readable labeled records rather than crushed table columns');
  assert.deepEqual(await page.locator('.plan-replay-trades tbody tr').first().locator('td').evaluateAll(cells =>
    cells.map(cell => cell.getAttribute('data-label'))),
    ['Entry', 'Exit', 'Position', 'P/L', 'Worst case', 'Why closed'],
    'each mobile replay trade labels dates, position, money, and close reason');
  assert.match(await page.locator('.plan-replay-trades tbody tr').first().textContent(), /EXPIRED|WINDOW_END|TIME|STOP|PROFIT_TARGET/,
    'the close reason remains visible in the mobile trade record');
  await captureSettled('plan-p4-backtest-mobile.png');
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.reload();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await page.click('#plan-outcomes-basis-backtest');
  await page.waitForSelector('#plan-backtest-result .grid', { timeout: 30000 });
  await page.waitForSelector('#plan-backtest-history .xp-head', { timeout: 5000 }).catch(async error => {
    const debug = await page.evaluate(async planId => ({
      latest: await API.getFresh('/api/plans/' + planId + '/outcomes/latest'),
      panel: document.getElementById('plan-outcomes-panel') && document.getElementById('plan-outcomes-panel').innerText
    }), plan.id);
    throw new Error('Saved replay result restored without its history control: ' + JSON.stringify(debug) + '\n' + error.message);
  });
  await page.locator('#plan-backtest-history .xp-head').click();
  assert.match(await page.textContent('#plan-backtest-history'), /Previous Plan replays \(1\).*CURRENT ASSUMPTIONS/s,
    'a saved replay survives restart with a visible Plan-owned history and no legacy page');
});

test('the scenario canvas authors waypoints on the outcomes fan with an honest fill label', async t => {
  await page.setViewportSize({ width: 2560, height: 1400 });
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'evidence', 'DIRECTIONAL', 'bullish');
  // A selected structure unlocks the outcomes band's model lens; seed it the way the app does.
  await page.evaluate(async planId => {
    const strategy = await API.post('/api/plans/' + planId + '/strategy/run', {});
    const candidate = strategy.strategy.result.candidates[0];
    let live = await PlanStore.get(planId, true);
    await PlanStore.selectCandidate(live, candidate.id);
    live = await PlanStore.get(planId, true);
    await PlanStore.runEnsemble(live, { over: { model: 'GBM', shape: 'CHOP', horizonDays: 30,
      stepsPerDay: 1, driftAnnual: 0, volAnnual: 0.25, jumpsPerYear: 0, jumpMean: 0, jumpVol: 0,
      tailNu: 6, heston: null, seed: 6121, paths: 80 } });
  }, plan.id);

  let ensembleRuns = 0;
  await page.route('**/api/plans/*/outcomes/ensemble', async route => { ensembleRuns++; await route.continue(); });
  await go('#/plan/' + plan.id + '/outcomes');
  await page.waitForSelector('#plan-outcomes');
  await page.click('#plan-outcomes-basis-model');
  await page.waitForSelector('#plan-scenario-canvas [data-canvas-fan] svg', { timeout: 20000 });
  assert.match(await page.textContent('#plan-scenario-canvas'), /Your hypothesis, never a forecast/,
    'the canvas never presents an authored path as a prediction');
  assert.equal(await page.locator('#plan-scenario-canvas .scenario-template-card').count(), 4,
    'earnings, target, sector, and historical inputs are one template surface on the same canvas');
  assert.ok(await page.locator('#plan-scenario-canvas .scenario-compare-card').count() >= 1,
    'the selected proposal or stock baseline is repriced on the canvas without a second journey');
  assert.match(await page.textContent('#plan-scenario-canvas .scenario-model-receipt'),
    /Calendar.*Risk-free rate.*Dividend.*Surface.*Settlement.*Exercise/s,
    'calendar, rates, dividends, surface, settlement, and exercise stay visible in one receipt');
  const wideCanvas = await page.evaluate(() => {
    const canvas = document.getElementById('plan-scenario-canvas').getBoundingClientRect();
    const templates = Array.from(document.querySelectorAll('.scenario-template-card')).map(node => node.getBoundingClientRect());
    const deck = Array.from(document.querySelectorAll('.scenario-control-deck > .scenario-canvas-section'))
      .map(node => node.getBoundingClientRect());
    return { width: canvas.width, templateRows: new Set(templates.map(r => Math.round(r.top))).size,
      deckSideBySide: deck.length === 2 && Math.abs(deck[0].top - deck[1].top) <= 2,
      overflow: document.documentElement.scrollWidth - innerWidth };
  });
  assert.ok(wideCanvas.width > 2000,
    'the 2560px workspace spends the desktop canvas instead of preserving an article-width gutter: ' + JSON.stringify(wideCanvas));
  assert.equal(wideCanvas.templateRows, 1,
    'the four compact template jobs share one wide-desktop row: ' + JSON.stringify(wideCanvas));
  assert.equal(wideCanvas.deckSideBySide, true,
    'day-by-day inputs and pricing assumptions remain co-visible at 2560px: ' + JSON.stringify(wideCanvas));
  assert.ok(wideCanvas.overflow <= 1, 'the wide canvas never creates page-level horizontal overflow');
  assert.equal(await page.getAttribute('#plan-scenario-canvas', 'data-waypoint-fill'), 'NONE',
    'an unpinned fan is honestly labeled plain Monte Carlo');
  assert.match(await page.textContent('#plan-scenario-canvas .canvas-fill-label'),
    /Click the chart to pin/, 'the empty canvas teaches the pin gesture instead of standing as dead space');

  // Place a waypoint with a synthesized click on the fan SVG (retry spots that hit a sample line).
  const fan = page.locator('#plan-scenario-canvas [data-canvas-fan] svg');
  await fan.scrollIntoViewIfNeeded();
  const box = await fan.boundingBox();
  for (const [fx, fy] of [[0.55, 0.32], [0.65, 0.42], [0.45, 0.26], [0.6, 0.5]]) {
    await page.mouse.click(box.x + box.width * fx, box.y + box.height * fy);
    if (await page.locator('#plan-scenario-canvas .fan-pin').count()) break;
  }
  assert.ok(await page.locator('#plan-scenario-canvas .fan-pin').count() >= 1,
    'clicking the fan places a visible waypoint pin');
  const runsBeforeRerun = ensembleRuns;
  await page.waitForSelector('#plan-scenario-canvas[data-waypoint-fill="EXACT_CONDITIONAL"]', { timeout: 30000 });
  assert.match(await page.textContent('#plan-scenario-canvas .canvas-fill-label'),
    /Exact conditional paths/, 'the honesty label is a first-class visible element after the pinned re-run');
  assert.ok(ensembleRuns > runsBeforeRerun,
    'placing a waypoint re-ran the fan in place through the outcomes API');
  assert.match(await page.textContent('#plan-scenario-canvas .scenario-pin-row'),
    /\$\d+(\.\d{2})? \([+-]\d+\.\d% vs today\) · session \d+ — (Mon|Tue|Wed|Thu|Fri) [A-Z][a-z]{2} \d+/,
    'every waypoint carries its units: dollars, percent vs today, and a dated trading session');
  await page.unroute('**/api/plans/*/outcomes/ensemble');

  // Save it as a named authored scenario; the chip carries the fill label and pin count.
  await page.fill('#plan-canvas-scenario-name', 'Touches the level midway');
  await page.click('#plan-canvas-save');
  await page.waitForSelector('.authored-scenario-card[data-fill="EXACT_CONDITIONAL"]', { timeout: 15000 });
  assert.match(await page.textContent('.authored-scenario-card'),
    /Touches the level midway.*Exact conditional.*1 waypoint/s,
    'the saved scenario card names the belief, its honesty label, and its waypoint count');

  // A real-input template extends the same fan and carries its own provenance receipt.
  const targetInput = page.locator('#plan-scenario-canvas input[id^="scenario-template-target-"]');
  await targetInput.fill('270');
  await page.getByRole('button', { name: 'Use target' }).click();
  await page.waitForSelector('#plan-scenario-canvas .scenario-template-receipt', { timeout: 30000 });
  assert.match(await page.textContent('#plan-scenario-canvas .scenario-template-receipt'),
    /DRIFT TO TARGET.*user's hypothesis.*no-hindsight boundary enforced/is,
    'the target template remains a hypothesis and freezes its input boundary');
  assert.equal(await page.evaluate(planId =>
    PlanStore.ui(planId).canvas.canvasModel.template.kind, plan.id), 'DRIFT_TO_TARGET',
  'the template receipt lives on the same Plan-owned canvas model');

  // Beginner gets a guide and the identical exact per-session IV controls used by Expert.
  await page.getByRole('button', { name: 'Fades slowly' }).click();
  await page.waitForFunction(planId => (PlanStore.ui(planId).canvas.canvasModel.ivNodes || []).length === 2,
    plan.id, { timeout: 30000 });
  const exactEditor = page.getByRole('button', { name: 'Edit the exact price and volatility for every session' });
  await exactEditor.click();
  await page.waitForSelector('#plan-scenario-canvas .scenario-day-table');
  const firstIv = page.locator('#plan-scenario-canvas input[id^="scenario-day-iv-"]').first();
  await firstIv.fill('37.5');
  await firstIv.press('Enter');
  await page.waitForFunction(planId => (PlanStore.ui(planId).canvas.canvasModel.ivNodes || [])
    .some(node => node.dayIndex === 0 && Math.abs(node.atmIv - .375) < 1e-9), plan.id, { timeout: 30000 });
  await page.waitForFunction(planId => {
    const canvas = PlanStore.ui(planId).canvas;
    const receipt = canvas.preview && canvas.preview.canvas && canvas.preview.canvas.modelReceipt;
    return receipt && (receipt.ivNodes || [])
      .some(node => node.dayIndex === 0 && Math.abs(node.atmIv - .375) < 1e-9);
  }, plan.id, { timeout: 30000 });
  await assertNamedControls('#plan-scenario-canvas');
  assert.match(await page.textContent('#plan-scenario-canvas .scenario-receipt-notes'),
    /P&L bands use every stored path.*representative path/is,
    'the receipt tells the reader which values are distributions and which Greeks follow one coherent trace');

  const responsiveBaseline = await page.evaluate(planId => {
    const canvas = PlanStore.ui(planId).canvas;
    const report = canvas.preview.canvas;
    const controls = Array.from(document.querySelectorAll('#plan-scenario-canvas input, #plan-scenario-canvas select, #plan-scenario-canvas button'));
    return {
      fingerprint: report.modelReceipt.fingerprint,
      canvasModel: JSON.stringify(canvas.canvasModel),
      underlying: JSON.stringify(report.underlying),
      positions: JSON.stringify((report.positions || []).map(position => position.key)),
      controlSignature: controls.map(node => node.id || node.getAttribute('aria-label')
        || (node.textContent || '').trim()).join('|'),
      controlCount: controls.length,
      dayRows: document.querySelectorAll('#plan-scenario-canvas .scenario-day-table tbody tr').length
    };
  }, plan.id);
  assert.ok(responsiveBaseline.fingerprint && responsiveBaseline.dayRows > 1,
    'the responsive audit begins from one exact stored Canvas and its complete day table');

  let responsiveEnsembleRuns = 0;
  const observeResponsiveEnsemble = request => {
    if (request.method() === 'POST' && /\/api\/plans\/[^/]+\/outcomes\/ensemble(?:\?|$)/.test(request.url())) {
      responsiveEnsembleRuns++;
    }
  };
  page.on('request', observeResponsiveEnsemble);
  t.after(() => page.off('request', observeResponsiveEnsemble));

  const widths = [
    { width: 2560, templateRows: 1, canvasRows: 1, deckRows: 1, receiptColumns: 4 },
    { width: 1920, templateRows: 1, canvasRows: 1, deckRows: 1, receiptColumns: 4 },
    { width: 1440, templateRows: 1, canvasRows: 1, deckRows: 1, receiptColumns: 4 },
    { width: 1000, templateRows: 2, canvasRows: 2, deckRows: 2, receiptColumns: 4 },
    { width: 390, templateRows: 4, canvasRows: 2, deckRows: 2, receiptColumns: 1 },
    { width: 375, templateRows: 4, canvasRows: 2, deckRows: 2, receiptColumns: 1 },
    { width: 320, templateRows: 4, canvasRows: 2, deckRows: 2, receiptColumns: 1 }
  ];
  for (const expected of widths) {
    const mobile = expected.width <= 390;
    await page.setViewportSize({ width: expected.width, height: mobile ? 844 : 1100 });
    const geometry = await page.evaluate(planId => {
      const root = document.getElementById('plan-scenario-canvas');
      const rootBox = root.getBoundingClientRect();
      const rows = selector => new Set(Array.from(document.querySelectorAll(selector))
        .filter(node => node.offsetParent !== null)
        .map(node => Math.round(node.getBoundingClientRect().top))).size;
      const columns = selector => getComputedStyle(document.querySelector(selector)).gridTemplateColumns
        .split(/\s+/).filter(Boolean).length;
      const allControls = Array.from(root.querySelectorAll('input,select,button'));
      const visibleControls = allControls.filter(node => node.offsetParent !== null);
      const freeControls = visibleControls.filter(node => !node.closest('.scenario-day-table-wrap,.scenario-position-table-wrap,.scenario-leg-table-wrap'));
      const primaryControls = Array.from(root.querySelectorAll(
        '.scenario-template-card input,.scenario-template-card button,.scenario-assumption-grid input,'
        + '.scenario-assumption-grid select,.scenario-iv-presets button,.scenario-canvas-save input,'
        + '.scenario-canvas-save button,.scenario-canvas-comparisons > .choice-field .choice-option'))
        .filter(node => node.offsetParent !== null);
      const wrappers = Array.from(root.querySelectorAll(
        '.scenario-day-table-wrap,.scenario-position-table-wrap,.scenario-leg-table-wrap'))
        .filter(node => node.offsetParent !== null);
      const templateCopy = Array.from(root.querySelectorAll('.scenario-template-card p,.scenario-template-card label'));
      const canvas = PlanStore.ui(planId).canvas;
      const report = canvas.preview.canvas;
      const rootChildrenOutside = Array.from(root.children).filter(node => node.offsetParent !== null).some(node => {
        const box = node.getBoundingClientRect();
        return box.left < rootBox.left - 1 || box.right > rootBox.right + 1;
      });
      return {
        viewport: innerWidth,
        pageOverflow: document.documentElement.scrollWidth - innerWidth,
        rootLeft: rootBox.left, rootRight: rootBox.right, rootWidth: rootBox.width,
        rootChildrenOutside,
        templateRows: rows('.scenario-template-card'),
        canvasRows: rows('.scenario-canvas-cols > *'),
        deckRows: rows('.scenario-control-deck > .scenario-canvas-section'),
        receiptColumns: columns('.scenario-receipt-grid'),
        comparisonModeRows: rows('.scenario-canvas-comparisons > .choice-field .choice-option'),
        fanWidth: root.querySelector('[data-canvas-fan] svg').getBoundingClientRect().width,
        freeControlOutside: freeControls.some(node => {
          const box = node.getBoundingClientRect();
          return box.left < rootBox.left - 1 || box.right > rootBox.right + 1;
        }),
        primaryMinHeight: Math.min(...primaryControls.map(node => node.getBoundingClientRect().height)),
        localScrollContained: wrappers.every(node => {
          const box = node.getBoundingClientRect();
          const overflow = getComputedStyle(node).overflowX;
          return box.left >= rootBox.left - 1 && box.right <= rootBox.right + 1
            && (overflow === 'auto' || overflow === 'scroll');
        }),
        minTemplateFont: Math.min(...templateCopy.map(node => parseFloat(getComputedStyle(node).fontSize))),
        clippedTemplateCopy: templateCopy.some(node => node.scrollWidth > node.clientWidth + 1),
        visibleTemplates: root.querySelectorAll('.scenario-template-card').length,
        comparisonModes: root.querySelectorAll('.scenario-canvas-comparisons > .choice-field .choice-option').length,
        fingerprint: report.modelReceipt.fingerprint,
        canvasModel: JSON.stringify(canvas.canvasModel),
        underlying: JSON.stringify(report.underlying),
        positions: JSON.stringify((report.positions || []).map(position => position.key)),
        controlSignature: allControls.map(node => node.id || node.getAttribute('aria-label')
          || (node.textContent || '').trim()).join('|'),
        controlCount: allControls.length,
        dayRows: root.querySelectorAll('.scenario-day-table tbody tr').length
      };
    }, plan.id);
    assert.ok(geometry.pageOverflow <= 1 && geometry.rootLeft >= 0
      && geometry.rootRight <= geometry.viewport + 1 && !geometry.rootChildrenOutside,
    expected.width + 'px keeps the full Canvas inside the page: ' + JSON.stringify(geometry));
    assert.deepEqual({ templateRows: geometry.templateRows, canvasRows: geometry.canvasRows,
      deckRows: geometry.deckRows, receiptColumns: geometry.receiptColumns },
    { templateRows: expected.templateRows, canvasRows: expected.canvasRows,
      deckRows: expected.deckRows, receiptColumns: expected.receiptColumns },
    expected.width + 'px uses the intended information composition');
    assert.equal(geometry.visibleTemplates, 4, expected.width + 'px preserves every real-input template');
    assert.equal(geometry.comparisonModes, 3, expected.width + 'px preserves every comparison question');
    assert.equal(geometry.freeControlOutside, false,
      expected.width + 'px keeps every non-table control directly reachable');
    assert.equal(geometry.localScrollContained, true,
      expected.width + 'px contains dense day and Greek tables in local scroll regions');
    assert.ok(geometry.minTemplateFont >= 11 && !geometry.clippedTemplateCopy,
      expected.width + 'px keeps template guidance legible and uncut: ' + JSON.stringify(geometry));
    assert.ok(geometry.primaryMinHeight >= (mobile ? 38 : 30),
      expected.width + 'px keeps primary controls usable: ' + JSON.stringify(geometry));
    if (mobile) {
      assert.equal(geometry.comparisonModeRows, 2,
        expected.width + 'px comparison modes form a deliberate 2+1 grid');
      assert.ok(geometry.fanWidth >= expected.width - 70,
        expected.width + 'px gives the price-path instrument the available phone width');
    }
    assert.equal(geometry.fingerprint, responsiveBaseline.fingerprint,
      expected.width + 'px still renders the identical immutable ensemble receipt');
    assert.equal(geometry.canvasModel, responsiveBaseline.canvasModel,
      expected.width + 'px changes presentation only, never Canvas assumptions');
    assert.equal(geometry.underlying, responsiveBaseline.underlying,
      expected.width + 'px keeps the identical stored path bands and IV series');
    assert.equal(geometry.positions, responsiveBaseline.positions,
      expected.width + 'px keeps the identical same-symbol position scope');
    assert.equal(geometry.controlSignature, responsiveBaseline.controlSignature,
      expected.width + 'px preserves control identity and reading order');
    assert.equal(geometry.controlCount, responsiveBaseline.controlCount,
      expected.width + 'px removes no capability');
    assert.equal(geometry.dayRows, responsiveBaseline.dayRows,
      expected.width + 'px preserves every exact day input');
  }
  assert.equal(responsiveEnsembleRuns, 0,
    'responsive presentation never regenerates or reruns the stored ensemble');
  await page.setViewportSize({ width: 1280, height: 720 });
});

test('a selected Plan future becomes one exact managed rehearsal with a durable receipt', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  // A unique horizon guarantees a fresh plan: the scenario-canvas spec pins waypoints on the
  // default-context AAPL plan, and creation dedupe would hand us its authored ensemble.
  const plan = await openPlan('AAPL', 'evidence', 'DIRECTIONAL', 'bullish', '38d');
  await page.waitForSelector('#test-your-view', { timeout: 15000 });
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.waitForSelector('#research-outcomes-basis-futures');
  await page.click('#research-outcomes-basis-futures');
  await page.waitForSelector('#whatif-card');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-rehearse-selected', { timeout: 30000 });
  const receipt = await page.evaluate(() => PlanStore.ui(App.state.activePlanId).evidence.planEnsembleFingerprint);
  assert.ok(receipt, 'the Plan owns the full ensemble before one path is rehearsed');

  await page.locator('#whatif-out .fan-sample-chooser button').first().click();
  assert.equal(await page.locator('#whatif-rehearse-selected').isDisabled(), false,
    'selecting a visible sample makes that exact path actionable');
  await page.click('#whatif-rehearse-selected');
  await page.waitForSelector('#sim-control-room', { timeout: 20000 });
  assert.match(await page.textContent('#sim-control-room'), /Exact Plan rehearsal.*path 1.*reproducibility receipt is preserved/is);
  await assertNoInternalChrome('#sim-control-room');
  assert.equal(await page.locator('#sim-control-room button:has-text("Inject event")').count(), 0,
    'event injection is absent because it would break the stored path identity');
  const live = await page.evaluate(async () => {
    const sessions = (await API.getFresh('/api/sim/market')).sessions;
    const current = sessions.find(item => item.id === App.state.world);
    return { world: App.state.world, rehearsal: current.rehearsal };
  });
  assert.equal(live.rehearsal.planId, plan.id);
  assert.equal(live.rehearsal.fingerprint, receipt);
  assert.equal(live.rehearsal.pathIndex, 0);
  assert.equal(live.rehearsal.selection, 'SAMPLE');

  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(4200); // let the real transition toast leave; animations stay enabled
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p7-rehearsal-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);
  const rehearsalOverflow = await page.locator('#sim-control-room').evaluate(node => ({
    contained: node.scrollWidth <= node.clientWidth + 1,
    width: node.clientWidth, scrollWidth: node.scrollWidth,
    offenders: Array.from(node.querySelectorAll('*')).map(el => {
      const r = el.getBoundingClientRect(); return { tag: el.tagName, cls: el.className || '', left:r.left, right:r.right };
    }).filter(x => x.right > innerWidth + 1 || x.left < -1).slice(0, 8)
  }));
  assert.equal(rehearsalOverflow.contained, true,
    'the rehearsal console stays contained on mobile: ' + JSON.stringify(rehearsalOverflow));
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p7-rehearsal-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });

  await page.click('#sim-control-room button:has-text("Step")');
  await page.click('#sim-control-room button:has-text("Finish")');
  await page.waitForSelector('#sim-report');
  assert.match(await page.textContent('#sim-report'), /exact path 1.*receipt/is);
  await page.click('#modal-confirm');
  await page.waitForSelector('#plan-stage-manage-review', { timeout: 20000 }).catch(async error => {
    throw new Error('Finished rehearsal did not return to its Plan: hash=' + await page.evaluate(() => location.hash)
      + ' world=' + await page.evaluate(() => App.state.world)
      + ' routeError=' + await page.locator('#route-error').count()
      + ' text=' + (await page.locator('#app').textContent()).slice(0, 2500) + '\n' + error.message);
  });
  await page.waitForSelector('#plan-stage-manage-review .plan-rehearsal-review, #plan-stage-manage-review .plan-management-timeline',
    { timeout: 20000 });
  assert.match(await page.textContent('#plan-stage-manage-review'), /Management rehearsals|exact path/i);
  assert.match(await page.textContent('#plan-stage-manage-review'), /simulation rehearsal|rehearsal result/i,
    'finish writes the rehearsal review into the owning Plan');
});

test('parallel Plans stay market-scoped, survive chip close, and open through one market transition', async () => {
  await page.setViewportSize({ width: 1280, height: 800 });
  const ids = await page.evaluate(async () => {
    const base = App.baseWorldId();
    if (App.state.world !== base) await App.switchWorld(base);
    await PlanStore.load(true);
    const first = await PlanStore.create({ symbol: 'QQQ', intent: 'INCOME', thesis: 'neutral',
      horizonDays: 30, riskMode: 'conservative', title: 'QQQ · Earn income' });
    const second = await PlanStore.create({ symbol: 'AAPL', intent: 'HEDGE', thesis: 'bearish',
      horizonDays: 45, riskMode: 'conservative', title: 'AAPL · Protect shares' });
    const closedFirst = await API.put('/api/plans/' + first.id + '/open', {
      expectedVersion: first.version, open: false
    });
    await PlanStore.load(true);

    const made = await API.post('/api/sim/market', { name: 'Plan collection world',
      symbols: { AAPL: 1.0 }, scenario: 'CHOP', speed: 26 });
    await API.post('/api/sim/market/' + made.worldId + '/start', {});
    await App.switchWorld(made.worldId);
    const simOne = await PlanStore.create({ symbol: 'AAPL', intent: 'DIRECTIONAL', thesis: 'bullish',
      horizonDays: 20, riskMode: 'conservative', title: 'AAPL · Bullish view' });
    const simTwo = await PlanStore.create({ symbol: 'SPY', intent: 'ACQUIRE', thesis: 'neutral',
      horizonDays: 20, riskMode: 'conservative', title: 'SPY · Buy at a discount' });
    return { base, worldId: made.worldId, first: closedFirst.id, second: second.id,
      simOne: simOne.id, simTwo: simTwo.id };
  });

  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1'));
  await go('#/home');
  await page.waitForSelector('#home-plan-library .home-plan-compact-list', { timeout: 20000 }).catch(async error => {
    throw new Error('Home Plan library did not settle: hash=' + await page.evaluate(() => location.hash)
      + ' routeError=' + await page.locator('#route-error').count()
      + ' text=' + (await page.locator('#app').textContent()).slice(0, 3000) + '\n' + error.message);
  });
  // Home deliberately paints before its durable Plan collection finishes reconciling. Assert the
  // settled ownership state, not the useful provisional list that may exist for a single frame.
  await page.waitForFunction(values => App.state.activePlanId === values.simTwo
    && !!document.querySelector('#home-plan-library .home-plan-compact-list')
    && !!document.querySelector('#home-plan-library [data-plan-id="' + values.simOne + '"]')
    && !document.querySelector('#home-plan-library [data-plan-id="' + values.simTwo + '"]')
    && /^Continue SPY/.test((document.querySelector('.home-hero-ctas button') || {}).textContent || ''),
  ids, { timeout: 45000 }).catch(async error => {
    const diagnosis = await page.evaluate(values => ({
      expected: values,
      world: App.state.world,
      activePlanId: App.state.activePlanId,
      hero: (document.querySelector('.home-hero-ctas button') || {}).textContent || '',
      rows: Array.from(document.querySelectorAll('#home-plan-library .home-plan-compact-row'))
        .map(row => row.getAttribute('data-plan-id')),
      routeError: document.querySelector('#route-error')?.textContent || '',
      plans: (PlanStore.all ? PlanStore.all() : []).map(plan => ({
        id: plan.id, symbol: plan.symbol, title: plan.title, market: plan.market,
        status: plan.status, open: plan.open
      }))
    }), ids);
    throw new Error(error.message + '\nParallel Plan diagnosis: ' + JSON.stringify(diagnosis));
  });
  assert.ok(await page.locator('#home-plan-library .home-plan-compact-row').count() <= 3,
    'Home shows a bounded alternative-Plan lens');
  assert.equal(await page.locator('#home-plan-library [data-plan-id="' + ids.simTwo + '"]').count(), 0,
    'the hero-owned active Plan is not repeated in the Home library');
  assert.equal(await page.locator('#home-plan-library button[aria-label^="Archive"], #home-plan-library button[aria-label^="Delete"]').count(), 0,
    'Home contains no destructive Plan-management commands');
  assert.equal(await page.locator('.home-hero-ctas button').filter({ hasText: /^Continue SPY/ }).count(), 1,
    'the hero owns the active Plan of the current execution market');
  const simOneRow = page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.simOne + '"]');
  assert.equal(await simOneRow.count(), 1, 'the other current-market Plan keeps a direct desk row');
  assert.match(await simOneRow.locator('.home-plan-compact-actions .btn').first().textContent(), /^Open$/,
    'a current-market row opens without a market transition');
  const secondRow = page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.second + '"]');
  assert.equal(await secondRow.count(), 1, 'an open Plan from another market stays visible on the desk');
  assert.match(await secondRow.locator('.home-plan-compact-actions .btn').first().textContent(), /Switch & open/,
    'another market’s Plan is labeled as a whole-market transition instead of blending in');
  await page.waitForTimeout(4200);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p8-library-desktop.png'), fullPage: true });

  await openPlanDrawer();
  await page.waitForSelector('#plans-library .home-plan-grid');
  const otherToggle = page.locator('#plans-library [data-plan-group="other-markets"] .home-plan-group-toggle');
  if (await otherToggle.count()) await otherToggle.click();
  await page.waitForSelector('#plans-library .xp-head:has-text("Closed Plan tabs")');
  await page.locator('#plans-library .xp-head:has-text("Closed Plan tabs")').click();
  const closedFirst = page.locator('.home-plan-closed-tabs .status-item').filter({ hasText: 'QQQ · Earn income' });
  assert.equal(await closedFirst.count(), 1, 'the Plan library retains a closed Plan outside the working count');
  assert.match(await closedFirst.getByRole('button').first().textContent(), /Switch market & reopen/);

  await closedFirst.getByRole('button', { name: 'Switch market & reopen' }).click();
  await page.waitForSelector('[role="dialog"]');
  assert.match(await page.textContent('[role="dialog"]'), /current simulated session keeps running.*workspace.*switch/is,
    'leaving a running simulation requires an explicit whole-workspace transition');
  await page.getByRole('button', { name: 'Switch & open Plan' }).click();
  await page.waitForFunction(id => location.hash.includes('/plan/' + id + '/'), ids.first, { timeout: 20000 });
  await page.waitForFunction(base => App.state.world === base && App.Market.world === base, ids.base);
  await go('#/home');
  await page.waitForSelector('#home-plan-library .home-plan-compact-list');
  await page.waitForFunction(id => PlanStore.all().some(plan => plan.id === id), ids.first, { timeout: 20000 });
  // The reopened Plan returns to the desk after the market commits: as the hero continuation
  // when it is the active plan, otherwise as its own direct compact row.
  await page.waitForFunction(id =>
    !!document.querySelector('#home-plan-library .home-plan-compact-row[data-plan-id="' + id + '"]')
    || (App.state.activePlanId === id
      && /^Continue QQQ/.test((document.querySelector('.home-hero-ctas button') || {}).textContent || '')),
  ids.first, { timeout: 20000 });
  assert.equal(await page.evaluate(id => PlanStore.all().some(plan => plan.id === id), ids.simOne), false,
    'the old market collection is stashed rather than blended into the current-market collection');

  // Putting a tab away happens from its desk row; rows exclude the ACTIVE plan, so hand the
  // desk to the other same-market journey first — from the canonical library, which lists
  // every working Plan regardless of hero ownership.
  await openPlanDrawer();
  await page.locator('#plans-library [data-plan-id="' + ids.second + '"] .home-plan-actions .btn:not(.btn-secondary)').click();
  await page.waitForFunction(id => location.hash.includes('/plan/' + id + '/'), ids.second, { timeout: 20000 });
  await go('#/home');
  await page.waitForSelector('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.first + '"]');
  await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.first + '"] .home-plan-compact-close').click();
  await page.waitForFunction(id => !document.querySelector('#home-plan-library [data-plan-id="' + id + '"]'), ids.first);
  await openPlanDrawer();
  const closedTabsHead = page.locator('#plans-library .xp-head').filter({ hasText: 'Closed Plan tabs' });
  await closedTabsHead.waitFor();
  await ensureExpanded(closedTabsHead);
  assert.equal(await page.locator('.home-plan-closed-tabs .status-item').filter({ hasText: 'QQQ · Earn income' }).count(), 1,
    'the tab × removes only the open-tab state; the durable Plan remains under closed tabs');

  await page.locator('#plans-library [data-plan-id="' + ids.simOne + '"] .home-plan-actions .btn:not(.btn-secondary)').click();
  await page.waitForFunction(world => App.state.world === world && App.Market.world === world, ids.worldId,
    { timeout: 20000 });
  await page.waitForFunction(id => location.hash.includes('/plan/' + id + '/'), ids.simOne);
  await page.waitForSelector('#plan-flow');
  const simOneHash = await page.evaluate(() => location.hash);
  await go('#/home');
  await page.waitForSelector('.home-hero-ctas button');
  assert.equal(await page.locator('.home-hero-ctas button').filter({ hasText: /^Continue AAPL/ }).count(), 1,
    'returning to the simulated market restores its active Plan to the hero');
  const restoredTwoRow = page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.simTwo + '"]');
  await restoredTwoRow.waitFor();
  assert.match(await restoredTwoRow.locator('.home-plan-compact-actions .btn').first().textContent(), /^Open$/,
    'returning to the simulated market restores its two-Plan open collection');

  await page.setViewportSize({ width: 390, height: 844 });
  await restoredTwoRow.waitFor({ state: 'visible' });
  assert.equal(await restoredTwoRow.locator('.home-plan-compact-actions .btn').first().isVisible(), true,
    'every current-market Plan keeps a usable desk row at the mobile viewport');
  await go(simOneHash);
  await page.waitForSelector('#plan-flow');
  const mobile = await page.evaluate(() => {
    const flow = document.querySelector('#plan-flow').getBoundingClientRect();
    return { overflow: document.documentElement.scrollWidth - innerWidth,
      bands: Array.from(document.querySelectorAll('#plan-flow > .flow-band')).map(band => {
        const r = band.getBoundingClientRect();
        const title = band.querySelector('.flow-band-title').getBoundingClientRect();
        return { band: band.dataset.band, left: r.left, right: r.right,
          titleLeft: title.left, titleRight: title.right, flowLeft: flow.left, flowRight: flow.right };
      }) };
  });
  assert.ok(mobile.overflow <= 0, 'parallel-Plan chrome does not widen the mobile viewport');
  assert.equal(mobile.bands.length, 6);
  mobile.bands.forEach(band => assert.ok(band.left >= band.flowLeft - 1 && band.right <= band.flowRight + 1
    && band.titleLeft >= band.left - 1 && band.titleRight <= band.right + 1,
    'all six journey bands remain fully visible on mobile: ' + JSON.stringify(band)));
  await page.waitForTimeout(4200);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p8-flow-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.reload();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await page.waitForFunction(id => window.App && App.state.activePlanId === id, ids.simOne, { timeout: 20000 });
  await go('#/home');
  await page.waitForSelector('.home-hero-ctas button');
  assert.equal(await page.locator('.home-hero-ctas button').filter({ hasText: /^Continue AAPL/ }).count(), 1,
    'reload restores the active Plan for this market instead of a Plan from another lane');
  await page.waitForSelector('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.simTwo + '"]',
    { timeout: 20000 });
  assert.match(await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.simTwo + '"]'
    + ' .home-plan-compact-actions .btn').first().textContent(), /^Open$/,
    'the current market collection restores from the server after reload');

  // Leave no active-world capacity behind for later journeys. The plans are retained as
  // archived records before their terminal world is finished.
  await page.evaluate(async values => {
    for (const id of [values.simOne, values.simTwo]) {
      const plan = await PlanStore.get(id, true);
      await API.post('/api/plans/' + id + '/archive', { expectedVersion: plan.version });
    }
    await App.switchWorld(values.base);
    await API.del('/api/sim/market/' + values.worldId);
    await PlanStore.refresh();
  }, ids);
});

test('equivalent Plan retries collapse while materially different Plans survive Home, mobile, and reload', async () => {
  await page.setViewportSize({ width: 1280, height: 760 });
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1'));
  const legacyLabels = await page.evaluate(() => {
    const first = { id: 'legacy-one', symbol: 'TSLA', intent: 'DIRECTIONAL',
      title: 'TSLA · neutral view', marketKind: 'DEMO', worldId: 'demo', furthestStage: 'STRATEGY',
      context: { thesis: 'neutral', horizonDays: 30, riskMode: 'conservative' },
      createdAt: '2026-07-14T10:11:12Z' };
    const second = { ...first, id: 'legacy-two', createdAt: '2026-07-14T10:11:13Z' };
    return [PlanStore.identity(first, [first, second]).duplicate,
      PlanStore.identity(second, [first, second]).duplicate];
  });
  assert.ok(legacyLabels.every(label => /^Started /.test(label)) && legacyLabels[0] !== legacyLabels[1],
    'indistinguishable surviving drafts use stable creation times instead of arbitrary ordinals');
  const ids = await page.evaluate(async () => {
    const base = App.baseWorldId();
    if (App.state.world !== base) await App.switchWorld(base);
    await PlanStore.load(true);
    // The disposable tab is created FIRST: desk rows exclude the ACTIVE plan (the hero owns
    // it), and this journey needs a closable non-active row later.
    const disposable = await PlanStore.create({ symbol: 'SPY', intent: 'INCOME', thesis: 'neutral',
      horizonDays: 19, riskMode: 'conservative', title: 'SPY · Disposable tab' });
    const one = await PlanStore.create({ symbol: 'AAPL', intent: 'DIRECTIONAL', thesis: 'bullish',
      horizonDays: 30, targetCents: 27123, riskMode: 'conservative', title: 'AAPL · Retry-safe audit' });
    const retry = await PlanStore.create({ symbol: 'AAPL', intent: 'DIRECTIONAL', thesis: 'bullish',
      horizonDays: 30, targetCents: 27123, riskMode: 'conservative', title: 'AAPL · Retry-safe audit' });
    const variant = await PlanStore.create({ symbol: 'AAPL', intent: 'DIRECTIONAL', thesis: 'bearish',
      horizonDays: 30, targetCents: 27123, riskMode: 'conservative', title: 'AAPL · Retry-safe audit' });
    return { one: one.id, retry: retry.id, variant: variant.id, disposable: disposable.id };
  });
  assert.equal(ids.retry, ids.one, 'a new request id cannot mint an identical active Plan');
  assert.notEqual(ids.variant, ids.one, 'a materially different horizon remains a separate Plan');

  await go('#/home');
  await page.waitForSelector('#home-plan-library .home-plan-compact-list');
  await page.waitForFunction(values => App.state.activePlanId === values.variant
    && /^Continue AAPL/.test((document.querySelector('.home-hero-ctas button') || {}).textContent || '')
    && !document.querySelector('#home-plan-library [data-plan-id="' + values.variant + '"]')
    && !!document.querySelector('#home-plan-library [data-plan-id="' + values.one + '"]')
    && !!document.querySelector('#home-plan-library [data-plan-id="' + values.disposable + '"]'),
  ids, { timeout: 20000 });
  assert.ok(await page.locator('#home-plan-library .home-plan-compact-row').count() <= 3,
    'Home keeps a bounded lens even when earlier journeys left other working Plans');
  assert.deepEqual(await page.evaluate(values => [values.one, values.variant].map(id =>
    PlanStore.allMarkets().some(plan => plan.id === id)), ids), [true, true],
    'both materially distinct Plans remain in the durable collection even when Home condenses them');
  assert.match(await page.locator('#home-plan-library').textContent(), /View bullish|View bearish/,
    'same-symbol Plans use their distinguishing assumption instead of an arbitrary ordinal');
  assert.equal(await page.locator('#home-plan-drawer .xp-head').count(), 1,
    'the canonical Plan library remains one action away from the condensed desk');
  assert.equal(await page.locator('.home-hero-ctas button').filter({ hasText: /^Continue AAPL/ }).count(), 1,
    'the active Plan remains directly reachable from the hero when the desk condenses');
  assert.equal(await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.variant + '"]').count(), 0,
    'the hero-owned active Plan is not repeated as a desk row');
  assert.equal(await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.disposable + '"]').count(), 1,
    'a non-active working Plan keeps its own closable desk row');

  await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.disposable + '"] .home-plan-compact-close').click();
  assert.equal(await page.evaluate(() => location.hash), '#/home',
    'closing a Plan tab from Home does not navigate into another Plan');
  await page.waitForFunction(id => !document.querySelector('#home-plan-library [data-plan-id="' + id + '"]'),
    ids.disposable);
  assert.equal(await page.locator('#home-plan-library [data-plan-id="' + ids.disposable + '"]').count(), 0,
    'a closed tab is not rendered in the compact working lens');
  await openPlanDrawer();
  await page.waitForSelector('#plans-library .xp-head:has-text("Closed Plan tabs")');
  await page.locator('#plans-library .xp-head:has-text("Closed Plan tabs")').click();
  await page.locator('.home-plan-closed-tabs .status-item').filter({ hasText: 'SPY · Disposable tab' })
    .getByRole('button', { name: 'Delete draft' }).click();
  await page.getByRole('button', { name: 'Delete draft' }).last().click();
  await page.waitForFunction(id => !PlanStore.allMarkets().some(p => p.id === id), ids.disposable);
  await page.waitForFunction(() => !(document.getElementById('plans-library')?.textContent || '')
    .includes('SPY · Disposable tab'));
  assert.equal(await page.locator('#plans-library').getByText('SPY · Disposable tab').count(), 0,
    'permanent draft deletion removes the Plan from the all-market library');

  await page.setViewportSize({ width: 390, height: 844 });
  const survivorRow = page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.one + '"]');
  await page.waitForFunction(values => App.state.activePlanId === values.variant
    && !!document.querySelector('#home-plan-library [data-plan-id="' + values.one + '"]'),
  ids, { timeout: 20000 });
  await survivorRow.waitFor({ state: 'visible' });
  const survivorAction = survivorRow.locator('.home-plan-compact-actions .btn').first();
  await survivorAction.waitFor({ state: 'visible' });
  assert.equal(await survivorAction.isVisible(), true,
    'the surviving Plan keeps a usable desk row at the mobile viewport');
  assert.match(await survivorRow.locator('.home-plan-compact-meta').textContent(), /View bullish/);
  const heroContinue = page.locator('.home-hero-ctas button').filter({ hasText: /^Continue AAPL/ });
  assert.equal(await heroContinue.count(), 1, 'the hero continuation stays reachable on mobile');
  assert.match(await heroContinue.textContent(), /View bearish/,
    'the hero names the distinguishing assumption of otherwise identical Plans');
  assert.doesNotMatch(await page.locator('#home-plan-library').textContent(), /Plan \d+ of \d+/,
    'meaningful assumption labels replace arbitrary duplicate ordinals');
  await page.reload();
  await page.waitForSelector('#app[data-route="home"][data-ready="true"]');
  await page.waitForSelector('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.one + '"]');
  assert.equal(await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids.one + '"]').count(), 1,
    'the single retry-safe identity survives restart');
  assert.equal(await page.locator('.home-hero-ctas button').filter({ hasText: /^Continue AAPL/ }).count(), 1,
    'the materially different Plan survives restart');

  await openPlanDrawer();
  await page.locator('#plans-library [data-plan-id="' + ids.one + '"] button[aria-label^="Archive"]').click();
  await page.waitForSelector('[role="dialog"]');
  await page.getByRole('button', { name: 'Archive Plan' }).click();
  await page.waitForFunction(id => !document.querySelector('#plans-library [data-plan-id="' + id + '"]'), ids.one);
  await page.waitForSelector('#plans-library .xp-head:has-text("Archived Plans")', { timeout: 15000 });
  assert.match(await page.textContent('#plans-library'), /Archived Plans \(\d+\)/,
    'archiving removes clutter while retaining a read-only record');
  await page.locator('#plans-library .xp-head:has-text("Archived Plans")').click();
  assert.match(await page.textContent('#plans-library .home-plan-archive'), /AAPL.*Retry-safe audit/s,
    'the archived Plan remains inspectable by identity');

  await page.evaluate(async values => {
    for (const id of [values.variant]) {
      const plan = await PlanStore.get(id, true);
      await API.post('/api/plans/' + id + '/archive', { expectedVersion: plan.version });
    }
    await PlanStore.refresh();
  }, ids);
  await page.setViewportSize({ width: 1280, height: 720 });
});

test('Home bounds a large same-market Plan collection without hiding reachability', async () => {
  await page.setViewportSize({ width: 1280, height: 760 });
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1'));
  const ids = await page.evaluate(async () => {
    const base = App.baseWorldId();
    if (App.state.world !== base) await App.switchWorld(base);
    await PlanStore.load(true);
    const seeds = [
      // Uncommon horizons make these six durable identities unique to this collection test;
      // equivalent-Plan deduplication is exercised separately and must not collapse this setup
      // into Plans intentionally left open by earlier journey tests.
      ['AAPL', 'DIRECTIONAL', 'bullish', 71, 'AAPL upside'],
      ['QQQ', 'INCOME', 'neutral', 72, 'QQQ income'],
      ['SPY', 'ACQUIRE', 'neutral', 73, 'SPY discount'],
      ['AAPL', 'HEDGE', 'bearish', 74, 'AAPL protection'],
      ['TSLA', 'EXIT', 'bullish', 75, 'TSLA target'],
      ['QQQ', 'DIRECTIONAL', 'bearish', 76, 'QQQ downside']
    ];
    const created = [];
    for (const seed of seeds) {
      created.push(await PlanStore.create({ symbol: seed[0], intent: seed[1], thesis: seed[2],
        horizonDays: seed[3], riskMode: 'conservative', title: seed[4] }));
    }
    return created.map(plan => plan.id);
  });

  await go('#/home');
  const compact = page.locator('#home-plan-library .home-plan-compact-list');
  await compact.waitFor({ state: 'visible' });
  await page.waitForFunction(activeId => App.state.activePlanId === activeId
    && !!document.querySelector('#home-plan-library .home-plan-compact-list')
    && document.querySelectorAll('#home-plan-library .home-plan-compact-row').length === 3
    && /^Continue QQQ/.test((document.querySelector('.home-hero-ctas button') || {}).textContent || '')
    && !document.querySelector('#home-plan-library [data-plan-id="' + activeId + '"]'),
  ids[5], { timeout: 45000 }).catch(async error => {
    const diagnosis = await page.evaluate(activeId => ({
      expectedActiveId: activeId,
      activePlanId: App.state.activePlanId,
      hero: (document.querySelector('.home-hero-ctas button') || {}).textContent || '',
      rows: Array.from(document.querySelectorAll('#home-plan-library .home-plan-compact-row'))
        .map(row => row.getAttribute('data-plan-id')),
      expectedRepeated: !!document.querySelector('#home-plan-library [data-plan-id="' + activeId + '"]'),
      routeError: document.querySelector('#route-error')?.textContent || '',
      plans: (PlanStore.all ? PlanStore.all() : []).map(plan => ({
        id: plan.id, symbol: plan.symbol, title: plan.title, market: plan.market,
        status: plan.status, open: plan.open
      }))
    }), activeId);
    throw new Error(error.message + '\nHome collection diagnosis: ' + JSON.stringify(diagnosis));
  });
  assert.equal(await compact.locator('.home-plan-compact-row').count(), 3,
    'the desk shows at most three alternatives to the hero-owned active Plan');
  assert.ok((await compact.locator('.home-plan-compact-meta').allTextContents()).some(text =>
    /View (?:bullish|bearish|neutral)/.test(text)),
    'compact Plan rows expose the assumption that distinguishes otherwise similar Plans');
  assert.equal(await page.locator('#home-plan-library .home-plan-compact-row[data-plan-id="' + ids[5] + '"]').count(), 0,
    'the hero-owned active Plan is not one of the bounded desk rows');
  const compactNote = page.locator('#home-plan-library .home-plan-compact-note');
  assert.match(await compactNote.textContent(), /\d+ more in the Plan library/,
    'the bounded desk names how many Plans remain in the Plan library');
  assert.match(await page.locator('#home-plan-library .plan-library-count').textContent(), /\d+ other Plans/);
  assert.equal(await page.locator('#home-plan-library [aria-label^="Archive"], #home-plan-library [aria-label^="Delete"]').count(), 0,
    'Plan lifecycle management is absent from the desk lens');
  await compactNote.getByRole('link', { name: 'the Plan library' }).click();
  await go('#/home'); // a same-hash visit is still a render; the note's request opens the drawer on this paint
  await page.waitForSelector('#home-plan-drawer .xp-head[aria-expanded="true"]');
  await page.waitForSelector('#plans-library [data-plan-group="in-this-market"]');
  const group = page.locator('#plans-library [data-plan-group="in-this-market"]');
  const showAll = group.getByRole('button', { name: /^Show all / });
  if (await showAll.count()) await showAll.click();
  for (const id of ids) assert.equal(await group.locator('[data-plan-id="' + id + '"]').count(), 1,
    'every Plan remains reachable in the canonical Plan library');
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p8-library-expanded-desktop.png'), fullPage: true });

  await page.evaluate(async planIds => {
    for (const id of planIds) {
      const plan = await PlanStore.get(id, true);
      await API.post('/api/plans/' + id + '/archive', { expectedVersion: plan.version });
    }
    await PlanStore.refresh();
  }, ids);
});

test('Home discards stale in-memory Plans after server-side lifecycle changes', async () => {
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1'));
  const stale = await openPlan('TSLA', 'understand', 'DIRECTIONAL');
  await page.evaluate(async id => {
    const plan = await API.getFresh('/api/plans/' + id);
    await API.post('/api/plans/' + id + '/archive', { expectedVersion: plan.version });
    if (!PlanStore.all().some(item => item.id === id)) throw new Error('test did not preserve stale client state');
    window.location.hash = '#/home';
  }, stale.id);
  await page.waitForSelector('#app[data-route="home"][data-ready="true"]');
  assert.equal(await page.locator('#home-plan-library [data-plan-id="' + stale.id + '"]').count(), 0,
    'the Home library reconciles to server truth instead of resurrecting the stale Plan');
  assert.doesNotMatch(await page.textContent('.home-hero'), /Continue TSLA/,
    'the primary continuation action is derived from the reconciled collection');
  assert.equal(await page.evaluate(id => PlanStore.all().some(item => item.id === id), stale.id), false,
    'the stale Plan is removed from the in-memory collection as well as the DOM');
});

test('Plan Decide freezes one server-owned package and opens the linked paper position atomically', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'strategy', 'DIRECTIONAL', 'bullish', '28d');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .ranked-idea-hero', { timeout: 30000 });
  const selectedQty = await page.evaluate(async planId => {
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    const listed = await API.getFresh('/api/research/AAPL/expirations');
    const lastListed = (listed.expirations || []).slice().sort().at(-1);
    const candidate = latest.strategy.result.candidates.find(item => {
      const expirations = new Set((item.legs || []).filter(leg => leg.type !== 'STOCK').map(leg => leg.expiration));
      return expirations.size === 1 && Array.from(expirations)[0] < lastListed;
    });
    if (!candidate) throw new Error('fixture ranking did not produce a single-expiration package with a later listed roll date');
    const live = await PlanStore.get(planId, true);
    await PlanStore.selectCandidate(live, candidate.id);
    await App.render();
    return candidate.qty || 1;
  }, plan.id);
  await go('#/plan/' + plan.id + '/decide');
  await page.waitForSelector('#plan-review-order');
  assert.equal(Number(await page.inputValue('#plan-decision-qty')), selectedQty,
    'Decide inherits the engine-sized quantity of the exact selected package');
  assert.equal(await page.locator('#plan-stage-decide input[name="symbol"], #plan-stage-decide select[name="strategy"]').count(), 0,
    'Decide derives symbol, intent, structure and legs from the Plan');
  await page.click('#plan-review-order');
  await page.waitForSelector('#plan-decision-review .plan-decision-math', { timeout: 30000 });
  const executablePrice = Number(await page.inputValue('#plan-decision-price'));
  await page.fill('#plan-decision-price', (executablePrice + 0.01).toFixed(2));
  await page.click('#plan-review-order');
  await page.waitForSelector('#plan-use-executable', { timeout: 30000 });
  assert.match(await page.textContent('#plan-use-executable'), /Use current executable/,
    'a moved or deliberately unfillable limit has an explicit, server-priced recovery');
  await page.click('#plan-use-executable');
  await page.waitForSelector('#plan-decision-review .plan-decision-math', { timeout: 30000 });
  await page.waitForFunction(() => !document.querySelector('#plan-use-executable'));
  const reviewText = await page.textContent('#plan-decision-review');
  assert.match(reviewText, /Theoretical max loss/);
  assert.match(reviewText, /Theoretical max profit/);
  assert.match(reviewText, /Market EV after costs/);
  assert.match(reviewText, /Realized-vol scenario EV/);
  assert.match(reviewText, /Chance of any profit/);
  assert.ok(Number(await page.inputValue('#plan-decision-price')) !== 0,
    'blank limit freezes to the executable package price reviewed by the server');

  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p5-decide-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p5-decide-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });

  for (const checkbox of await page.locator('#plan-decision-review .ack-gate input[type="checkbox"]').all()) {
    await checkbox.check();
  }
  await page.click('#plan-place-trade');
  await page.waitForFunction(id => location.hash === '#/plan/' + id + '/manage-review', plan.id,
    { timeout: 30000 }).catch(async error => {
      const state = await page.evaluate(async id => {
        let decision = null;
        try { decision = await API.getFresh('/api/plans/' + id + '/decision/latest'); }
        catch (requestError) { decision = { error: requestError.message }; }
        return {
          hash: location.hash,
          ready: document.getElementById('app')?.dataset.ready,
          routeError: document.getElementById('route-error')?.textContent || null,
          toast: document.getElementById('toast-region')?.textContent || null,
          review: document.getElementById('plan-decision-review')?.textContent || null,
          decision
        };
      }, plan.id);
      throw new Error('paper decision did not advance atomically: ' + JSON.stringify(state)
        + ' :: ' + error.message);
    });
  const frozen = await page.evaluate(async id => API.getFresh('/api/plans/' + id + '/decision/latest'), plan.id);
  assert.equal(frozen.plan.status, 'POSITION_OPEN');
  assert.equal(frozen.plan.furthestStage, 'MANAGE_REVIEW');
  assert.equal(frozen.decision.action, 'TRADE');
  assert.ok(frozen.decision.tradeId, 'the paper trade is linked inside the frozen decision');

  await page.waitForSelector('#plan-stage-manage-review .quote-hero');
  assert.equal(await page.locator('#plan-archive').count(), 0,
    'a Plan with an open position cannot be archived out from under management');
  assert.match(await page.textContent('#plan-stage-manage-review'), /Frozen at Decide/);
  assert.equal((await page.textContent('#plan-edit-context')).trim(), 'Revise this Plan');
  await page.click('#plan-edit-context');
  assert.equal((await page.textContent('#plan-save-context')).trim(), 'Create revised Plan',
    'editing a decided Plan creates a linked revision instead of rewriting the frozen decision');
  assert.match(await page.textContent('#plan-context-editor'), /linked revision preserves that history/i);
  await page.click('#plan-edit-context');
  await page.waitForSelector('#plan-stage-manage-review button:has-text("Refresh marks")', { timeout: 15000 });
  assert.match(await page.textContent('#plan-stage-manage-review'), /Refresh marks/);
  assert.equal(await page.locator('#plan-stage-manage-review a[href^="#/trade"], #plan-stage-manage-review .plan-stage-transition').count(), 0,
    'Manage stays inside the Plan instead of linking to a standalone Trade detail');
  await page.click('#refresh-btn');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForFunction(() => {
    const timeline = document.querySelector('.plan-management-timeline');
    return timeline && /Marked/.test(timeline.textContent || '');
  }, null, { timeout: 15000 });
  assert.match(await page.textContent('.plan-management-timeline'), /Marked/);
  await page.waitForTimeout(300); // real card-arrival motion, then inspect the settled frame
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p6-manage-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(300); // let responsive reflow and real animations settle
  const manageMobile = await page.evaluate(() => ({
    width: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
    offenders: Array.from(document.querySelectorAll('#plan-stage-manage-review *')).map(node => {
      const r = node.getBoundingClientRect();
      return { id: node.id || '', cls: String(node.className || '').slice(0, 80),
        left: Math.round(r.left), right: Math.round(r.right), width: Math.round(r.width) };
    }).filter(row => row.left < -1 || row.right > innerWidth + 1).slice(0, 12)
  }));
  assert.ok(manageMobile.scrollWidth <= manageMobile.width + 1,
    'active-position Manage must fit the mobile viewport: ' + JSON.stringify(manageMobile));
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p6-manage-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });

  await page.waitForSelector('#greeks-card');
  assert.match(await page.textContent('#greeks-card'), /How this position reacts/,
    'Beginner receives a plain-language entry to the same position sensitivities');
  await page.locator('#greeks-card .xp-head:has-text("Exact sensitivities by leg")').click();
  assert.match(await page.textContent('#greeks-card'), /Net delta.*Theta \/ day/s,
    'Beginner can inspect exact aggregate Greeks');
  assert.ok(await page.locator('#greeks-card tbody tr').count() >= 1,
    'Beginner can inspect the same per-leg Greek rows');

  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'expert'
    && document.getElementById('app')?.dataset.ready === 'true'
    && !App._rendering);
  await page.waitForSelector('#greeks-card tbody tr');
  assert.match(await page.textContent('#greeks-card'), /Net delta.*Theta \/ day/s,
    'Expert retains aggregate and per-leg position Greeks in Plan management');
  assert.ok(await page.locator('#greeks-card tbody tr').count() >= 1, 'per-leg Greek rows remain auditable');
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'beginner'
    && document.getElementById('app')?.dataset.ready === 'true'
    && !App._rendering
    && /How this position reacts/.test(document.getElementById('greeks-card')?.textContent || ''));

  await go('#/plan/' + plan.id + '/decide');
  // A frozen decision collapses the commit band to its conclusion; reopening it is one tap.
  await page.waitForSelector('.plan-decision-facts');
  assert.match(await page.textContent('#plan-stage-decide'), /Decision frozen/);
  assert.match(await page.textContent('#plan-stage-decide'), /Market EV after costs/);

  await go('#/plan/' + plan.id + '/evidence');
  await page.waitForSelector('#test-your-view');
  await page.waitForSelector('#tv-context');
  assert.equal(await page.locator('#tv-view, #tv-setup, #tv-horizon, #tv-change-view').count(), 0,
    'a frozen decision never presents mutable view assumptions — the view is quoted, not asked');
  assert.match(await page.textContent('.plan-frozen-context'), /linked revision.*without rewriting/i);

  await go('#/plan/' + plan.id + '/manage-review');
  await page.click('#roll-btn');
  await page.waitForSelector('.position-roll-workbench:not([hidden]) .position-editor');
  await page.click('.position-roll-workbench [data-position-command="analyze"]');
  await page.waitForSelector('#apply-roll-btn', { timeout: 30000 }).catch(async error => {
    const debug = await page.evaluate(async id => ({
      workbench: document.querySelector('.position-roll-workbench')?.textContent || null,
      toast: document.getElementById('toast-region')?.textContent || null,
      plan: await API.getFresh('/api/plans/' + id)
    }), plan.id);
    throw new Error('Roll review did not become applicable: ' + JSON.stringify(debug) + '\n' + error.message);
  });
  const rollApplyResponse = page.waitForResponse(response => response.url().endsWith('/api/position-transformations/apply')
    && response.request().method() === 'POST');
  await page.click('#apply-roll-btn');
  const rolledPlan = await (await rollApplyResponse).json();
  assert.equal(rolledPlan.plan.status, 'POSITION_OPEN');
  assert.notEqual(rolledPlan.trade.id, frozen.decision.tradeId,
    'the roll keeps a newly linked replacement open instead of reusing the closed trade');
  await page.waitForFunction(() => {
    const timeline = document.querySelector('.plan-management-timeline');
    return timeline && /Rolled/.test(timeline.textContent || '') && document.querySelector('#unwind-btn');
  }, null, { timeout: 30000 });
  assert.match(await page.textContent('.plan-management-timeline'), /Rolled/,
    'the Plan records one atomic roll and keeps the replacement under Manage & Review');
  await page.click('#unwind-btn');
  await page.waitForSelector('#close-transformation-preview');
  const closePreview = await page.textContent('#close-transformation-preview');
  assert.match(closePreview, /Before.*After/s);
  assert.match(closePreview, /Cash \/ no position/);
  assert.match(closePreview, /Closing cash flow.*Close fees.*Final position P\/L/s);
  assert.match(closePreview, /Broker reserve released.*Theoretical max loss after/s);
  const closeApplyResponse = page.waitForResponse(response => response.url().endsWith('/api/position-transformations/apply')
    && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Close position' }).click();
  const closedPlan = await (await closeApplyResponse).json();
  assert.equal(closedPlan.plan.status, 'CLOSED');
  // The stage fills asynchronously and a posture refresh may repaint it once more —
  // wait for the settled review content rather than sampling between paints.
  await page.waitForFunction(() => {
    const stage = document.getElementById('plan-stage-manage-review');
    const text = stage ? stage.textContent || '' : '';
    return /trade decision/i.test(text) && /plan position/i.test(text) && !document.querySelector('#unwind-btn');
  }, null, { timeout: 15000 });

  await openPlanDrawer();
  await page.waitForSelector('#plans-library .home-plan-tile');
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'home',
    'Plans have one canonical product home — the Desk drawer — instead of borrowing Portfolio');
  assert.equal(await page.locator('#nav a[data-route~="home"]').evaluate(node => node.classList.contains('active')), true,
    'the Desk item owns the plan-library destination');
  assert.equal(await page.locator('#nav a[data-route~="plans"]').count(), 0,
    'no navigation item claims a retired standalone Plans route');
  assert.match(await page.textContent('#plans-library'), /AAPL/);
  assert.match(await page.textContent('#plans-library'), /Review Plan/);
  const libraryCardText = await page.locator('#plans-library .home-plan-tile').first().innerText();
  assert.equal((libraryCardText.match(/AAPL/g) || []).length, 1,
    'a Plan card names its symbol once instead of repeating the derived title');

  await go('#/portfolio');
  await page.waitForSelector('#pf-sec-positions.active');
  assert.equal((await page.locator('#app > .route-mount > h1').textContent()).trim(), 'Book');
  assert.equal(await page.locator('#pf-sec-plans').count(), 0,
    'Portfolio owns money and positions without a second Plan library');
});

test('the third decision outcome records a broker placement into the tracked book', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'strategy', 'DIRECTIONAL', 'bullish', '56d');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .ranked-idea-hero', { timeout: 30000 });
  await page.evaluate(async planId => {
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    const candidate = latest.strategy.result.candidates.find(item => {
      const expirations = new Set((item.legs || []).filter(leg => leg.type !== 'STOCK').map(leg => leg.expiration));
      return expirations.size === 1;
    });
    const live = await PlanStore.get(planId, true);
    await PlanStore.selectCandidate(live, candidate.id);
    await App.render();
  }, plan.id);
  await go('#/plan/' + plan.id + '/decide');
  await page.waitForSelector('#plan-review-order');
  await page.click('#plan-review-order');
  await page.waitForSelector('#plan-decision-review .plan-decision-math', { timeout: 30000 });

  assert.equal(await page.locator('#plan-record-broker').count(), 1,
    'the commitment card offers the real-lane outcome beside practice and cash');
  const brokerAcks = await page.locator('#plan-decision-review .ack-gate input[type="checkbox"]').all();
  if (brokerAcks.length) {
    assert.ok(await page.locator('#plan-record-broker').isDisabled(),
      'material risks gate the broker record exactly like a practice placement');
    for (const checkbox of brokerAcks) await checkbox.check();
  }
  await page.waitForFunction(() => !document.querySelector('#plan-record-broker').disabled);
  await page.click('#plan-record-broker');
  await page.waitForSelector('#plan-broker-record .empty', { timeout: 15000 });
  assert.match(await page.textContent('#plan-broker-record'), /No tracked account yet/,
    'without a tracked account the card guides to the Book instead of dead-ending');

  await page.evaluate(async () => {
    await API.post('/api/portfolio/accounts', { name: 'Real brokerage', accountType: 'TAXABLE',
      broker: 'Example Broker', openingCashCents: 5000000 });
  });
  await page.click('#plan-record-broker');
  await page.click('#plan-record-broker');
  await page.waitForSelector('#plan-broker-account .choice-option', { timeout: 15000 });
  assert.match(await page.textContent('#plan-broker-record'), /Fills are recorded in Real brokerage/,
    'choosing the destination narrates its consequence');
  const fillInputs = await page.locator('#plan-broker-record .plan-broker-fills input').all();
  assert.ok(fillInputs.length >= 1, 'every leg gets an exact fill field');
  for (const input of fillInputs) {
    assert.ok((await input.inputValue()) !== '', 'fills prefill from the reviewed package prices');
  }
  await page.fill('#plan-broker-ref', 'order-4242');
  await page.fill('#plan-broker-fees', '1.30');
  await page.click('#plan-broker-submit');
  await page.waitForFunction(id => location.hash === '#/plan/' + id + '/manage-review', plan.id, { timeout: 30000 });
  await page.waitForSelector('#plan-tracked-structure', { timeout: 15000 });
  const liveStrip = await page.textContent('#plan-tracked-structure');
  assert.match(liveStrip, /Live at your broker/);
  assert.match(liveStrip, /Real brokerage/);
  assert.match(liveStrip, /OPEN/);
  const frozen = await page.evaluate(async id => API.getFresh('/api/plans/' + id + '/decision/latest'), plan.id);
  assert.equal(frozen.decision.action, 'BROKER');
  assert.equal(frozen.plan.status, 'POSITION_OPEN');
  assert.ok(!frozen.decision.tradeId, 'a broker placement opens no practice trade');

  const bookNavigationCommit = await page.locator('#plan-open-book').evaluate(button => {
    button.click();
    return { hash: location.hash,
      ready: document.getElementById('app')?.getAttribute('data-ready') };
  });
  assert.deepEqual(bookNavigationCommit,
    { hash: '#/portfolio/book/overview', ready: 'false' },
    'the URL and readiness marker commit together, so the outgoing Plan cannot masquerade as the Book');
  await page.waitForFunction(() => location.hash === '#/portfolio/book/overview'
    && App._lastRenderedRoute === '#/portfolio/book/overview'
    && document.getElementById('app')?.getAttribute('data-ready') === 'true');
  // The Book's account sections fill asynchronously after the route shell is ready —
  // wait for the tracked account to paint rather than sampling between fills.
  await page.waitForFunction(() => /Real brokerage/.test(document.getElementById('portfolio-mode-panel')?.textContent || ''),
    null, { timeout: 15000 });
  assert.match(await page.textContent('#portfolio-mode-panel'), /Real brokerage/,
    'the live strip hands off to the one accounting surface instead of duplicating it');
});

test('Practice roll edits, reviews, and applies one atomic before-after transformation at both levels', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const created = await page.evaluate(async () => {
    const expirations = (await API.getFresh('/api/research/AAPL/expirations')).expirations;
    const expiration = expirations[2];
    const order = { symbol: 'AAPL', strategy: 'DEBIT_CALL_SPREAD', qty: 1,
      thesis: 'bullish', horizon: 'month', riskMode: 'balanced', intent: 'DIRECTIONAL',
      source: 'DOM_ATOMIC_ROLL', fillNature: 'PROPOSED', legs: [
        { action: 'BUY', type: 'CALL', strike: '255', expiration: expiration,
          ratio: 1, multiplier: 100, positionEffect: 'OPEN' },
        { action: 'SELL', type: 'CALL', strike: '260', expiration: expiration,
          ratio: 1, multiplier: 100, positionEffect: 'OPEN' }
      ] };
    const preview = await API.post('/api/trades/preview', order);
    if (preview.requiredAcks && preview.requiredAcks.length) {
      order.acknowledgedRisks = preview.requiredAcks.map(item => item.id);
      order.ackToken = preview.ackToken;
    }
    const out = await API.post('/api/trades', order);
    return { id: out.trade.id, expiration: expiration };
  });

  await go('#/portfolio/trade/' + created.id);
  await page.waitForSelector('#roll-btn');
  await page.click('#roll-btn');
  await page.waitForSelector('.position-roll-workbench:not([hidden]) .position-editor');
  assert.match(await page.textContent('.position-roll-workbench'), /Build the replacement/);
  assert.equal(await page.evaluate(async id => (await API.getFresh('/api/trades/' + id)).trade.status, created.id),
    'ACTIVE', 'opening the editor never closes the current position');
  await page.click('.position-roll-workbench [data-position-command="analyze"]');
  await page.waitForSelector('#apply-roll-btn', { timeout: 30000 });
  const beginnerReview = await page.textContent('.position-roll-result');
  assert.match(beginnerReview, /Before.*After/s);
  assert.match(beginnerReview, /Realized on closing leg.*Replacement (credit|cost)/s);
  assert.match(beginnerReview, /Theoretical max loss before.*Theoretical max loss after/s);
  assert.match(beginnerReview, /commit together or nothing changes/i);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(300);
  await page.locator('.toast-message').waitFor({ state: 'detached', timeout: 6000 }).catch(() => {});
  const mobile = await page.evaluate(() => ({ width: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth }));
  assert.ok(mobile.scrollWidth <= mobile.width + 1,
    'the shared roll editor and review fit the mobile viewport: ' + JSON.stringify(mobile));
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-roll-beginner-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#roll-btn');
  await page.click('#roll-btn');
  await page.waitForSelector('.position-roll-workbench:not([hidden]) .position-editor');
  await page.click('.position-roll-workbench [data-position-command="analyze"]');
  await page.waitForSelector('#apply-roll-btn', { timeout: 30000 });
  assert.match(await page.textContent('.position-roll-result'), /Broker reserve before.*Broker reserve after/s,
    'Expert receives the same reviewed action with dense risk facts');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.locator('.toast-message').waitFor({ state: 'detached', timeout: 6000 }).catch(() => {});
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-roll-expert.png'), fullPage: true });

  await page.click('#apply-roll-btn');
  await page.waitForFunction(id => location.hash.startsWith('#/portfolio/trade/')
    && location.hash !== '#/portfolio/trade/' + id, created.id, { timeout: 30000 });
  const result = await page.evaluate(async oldId => {
    const oldTrade = (await API.getFresh('/api/trades/' + oldId)).trade;
    const newId = location.hash.split('/').pop();
    const replacement = (await API.getFresh('/api/trades/' + newId)).trade;
    return { oldStatus: oldTrade.status, newId: newId, newStatus: replacement.status,
      oldExpirations: oldTrade.legs.map(leg => leg.expiration).filter(Boolean),
      newExpirations: replacement.legs.map(leg => leg.expiration).filter(Boolean) };
  }, created.id);
  assert.equal(result.oldStatus, 'CLOSED');
  assert.equal(result.newStatus, 'ACTIVE');
  assert.notEqual(result.newId, created.id);
  assert.ok(result.newExpirations.every(value => value > created.expiration),
    'the replacement uses later listed expirations instead of a calendar guess');
  await page.evaluate(async () => { Learn.setLevel('beginner'); await App.render(); });
});

test('Practice partial close keeps survivor identity, exact history, and visible feedback at both levels', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const created = await page.evaluate(async () => {
    const expiration = (await API.getFresh('/api/research/AAPL/expirations')).expirations[2];
    const order = { symbol: 'AAPL', strategy: 'DEBIT_CALL_SPREAD', qty: 5,
      thesis: 'bullish', horizon: 'month', riskMode: 'balanced', intent: 'DIRECTIONAL',
      source: 'DOM_PARTIAL_CLOSE', fillNature: 'PROPOSED', legs: [
        { action: 'BUY', type: 'CALL', strike: '255', expiration: expiration,
          ratio: 1, multiplier: 100, positionEffect: 'OPEN' },
        { action: 'SELL', type: 'CALL', strike: '260', expiration: expiration,
          ratio: 1, multiplier: 100, positionEffect: 'OPEN' }
      ] };
    const preview = await API.post('/api/trades/preview', order);
    if (preview.requiredAcks && preview.requiredAcks.length) {
      order.acknowledgedRisks = preview.requiredAcks.map(item => item.id);
      order.ackToken = preview.ackToken;
    }
    return (await API.post('/api/trades', order)).trade;
  });

  await go('#/portfolio/trade/' + created.id);
  await page.waitForSelector('#partial-close-btn');
  await page.click('#partial-close-btn');
  await page.waitForSelector('.position-partial-workbench:not([hidden]) #partial-close-quantity');
  assert.match(await page.textContent('.position-partial-workbench'), /Reduce this position without resetting its history/);
  await page.fill('#partial-close-quantity', '2');
  assert.equal((await page.textContent('#partial-close-summary')).trim(), 'Close 2 · 3 remain');
  await page.click('#review-partial-close-btn');
  await page.waitForSelector('#apply-partial-close-btn', { timeout: 30000 });
  const beginnerReview = await page.textContent('.position-partial-result');
  assert.match(beginnerReview, /Before.*After/s);
  assert.match(beginnerReview, /Closing now.*2 of 5.*Still open.*3 packages/s);
  assert.match(beginnerReview, /Realized by this close.*Realized on this position to date/s);
  assert.match(beginnerReview, /original fills, opening-fee basis, and trade identity/i);
  assert.doesNotMatch(beginnerReview, /protects the short/i,
    'proportional size reduction must not be described as removing a hedge');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(300);
  const mobile = await page.evaluate(() => ({ width: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
    buttons: Array.from(document.querySelectorAll('.position-partial-workbench button')).map(node => {
      const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, text: node.textContent.trim() };
    }) }));
  assert.ok(mobile.scrollWidth <= mobile.width + 1,
    'partial-close controls fit the mobile viewport: ' + JSON.stringify(mobile));
  assert.ok(mobile.buttons.every(item => item.left >= -1 && item.right <= mobile.width + 1),
    'partial-close commands stay fully visible: ' + JSON.stringify(mobile.buttons));
  await page.locator('.position-partial-workbench').evaluate(node => node.scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(900); // keep real motion enabled; capture only after sticky/compositor motion settles
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-partial-close-beginner-mobile.png') });
  await page.setViewportSize({ width: 1280, height: 720 });

  const applyResponse = page.waitForResponse(response => response.url().endsWith('/api/position-transformations/apply')
    && response.request().method() === 'POST');
  await page.click('#apply-partial-close-btn');
  const applied = await (await applyResponse).json();
  assert.equal(applied.trade.id, created.id, 'the survivor keeps the same trade identity');
  assert.equal(applied.trade.qty, 3);
  assert.equal(applied.trade.status, 'ACTIVE');
  assert.equal(applied.transformation.action, 'PARTIAL_CLOSE');
  await page.waitForFunction(id => location.hash === '#/portfolio/trade/' + id
    && /x3/.test(document.querySelector('.quote-hero')?.textContent || ''), created.id, { timeout: 30000 });
  assert.match(await page.textContent('#app'), /Remaining entry basis.*Realized from partial closes/s,
    'the surviving position reconciles its residual basis and already-realized result in view');

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#partial-close-btn');
  await page.click('#partial-close-btn');
  await page.fill('#partial-close-quantity', '1');
  await page.click('#review-partial-close-btn');
  await page.waitForSelector('#apply-partial-close-btn', { timeout: 30000 });
  assert.match(await page.textContent('.position-partial-result'), /Broker reserve before.*Broker reserve after/s,
    'Expert receives the same action with dense exact risk facts');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.locator('.toast-message').waitFor({ state: 'detached', timeout: 6000 }).catch(() => {});
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-partial-close-expert.png'), fullPage: true });
  await page.evaluate(async () => { Learn.setLevel('beginner'); await App.render(); });
});

test('Practice position adjustment reuses the shared editor and preserves exact history at both levels', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const created = await page.evaluate(async () => {
    const expiration = (await API.getFresh('/api/research/AAPL/expirations')).expirations[2];
    const order = { symbol: 'AAPL', strategy: 'DEBIT_CALL_SPREAD', qty: 1,
      thesis: 'bullish', horizon: 'month', riskMode: 'balanced', intent: 'DIRECTIONAL',
      source: 'DOM_POSITION_ADJUSTMENT', fillNature: 'PROPOSED', legs: [
        { action: 'BUY', type: 'CALL', strike: '255', expiration: expiration,
          ratio: 1, multiplier: 100, positionEffect: 'OPEN' },
        { action: 'SELL', type: 'CALL', strike: '260', expiration: expiration,
          ratio: 1, multiplier: 100, positionEffect: 'OPEN' }
      ] };
    const preview = await API.post('/api/trades/preview', order);
    if (preview.requiredAcks && preview.requiredAcks.length) {
      order.acknowledgedRisks = preview.requiredAcks.map(item => item.id);
      order.ackToken = preview.ackToken;
    }
    return (await API.post('/api/trades', order)).trade;
  });

  await go('#/portfolio/trade/' + created.id);
  await page.waitForSelector('#adjust-position-btn');
  await page.click('#adjust-position-btn');
  await page.waitForSelector('.position-adjust-workbench:not([hidden]) .position-editor');
  assert.match(await page.textContent('.position-adjust-workbench'), /Edit the position that will remain open/);
  assert.equal(await page.locator('.position-adjust-workbench label').filter({ hasText: /^Position$/ }).count(), 0,
    'a surviving-composition editor never asks for an order open/close effect');
  assert.equal(await page.locator('.position-adjust-workbench label').filter({ hasText: /Fill|Package net|Fee treatment/ }).count(), 0,
    'the server owns retained fills and executable changed-quantity pricing');
  await page.locator('.position-adjust-workbench .position-leg').nth(1)
    .getByRole('button', { name: /Remove leg/ }).click();
  await page.click('.position-adjust-workbench [data-position-command="analyze"]');
  await page.waitForSelector('#apply-position-adjustment-btn', { timeout: 30000 });
  const beginnerReview = await page.textContent('.position-adjust-result');
  assert.match(beginnerReview, /Before.*After/s);
  assert.match(beginnerReview, /Remove option leg.*Realized by this action/s);
  assert.match(beginnerReview, /Theoretical max loss before.*Theoretical max loss after/s);
  assert.match(beginnerReview, /Stored fills stay attached|Unchanged quantities keep their stored fills/i);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(300);
  const mobile = await page.evaluate(() => ({ width: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
    buttons: Array.from(document.querySelectorAll('.position-adjust-workbench .position-editor-mode button, '
      + '.position-adjust-workbench .position-leg-remove, .position-adjust-workbench .position-editor-commands button, '
      + '.position-adjust-workbench #apply-position-adjustment-btn')).map(node => {
      const r = node.getBoundingClientRect(); return { left: r.left, right: r.right, text: node.textContent.trim() };
    }).filter(item => item.right > item.left),
    chain: (() => { const node = document.querySelector('.position-adjust-workbench .position-chain-rail');
      if (!node) return null; const r = node.getBoundingClientRect();
      return { left: r.left, right: r.right, client: node.clientWidth, scroll: node.scrollWidth }; })() }));
  assert.ok(mobile.scrollWidth <= mobile.width + 1,
    'the shared adjustment editor and review fit mobile: ' + JSON.stringify(mobile));
  assert.ok(mobile.buttons.every(item => item.left >= -1 && item.right <= mobile.width + 1),
    'visible adjustment commands stay inside mobile: ' + JSON.stringify(mobile.buttons));
  if (mobile.chain) assert.ok(mobile.chain.left >= -1 && mobile.chain.right <= mobile.width + 1,
    'the deliberately scrollable strike rail stays contained inside the viewport: ' + JSON.stringify(mobile.chain));
  await page.locator('.position-adjust-workbench').evaluate(node => node.scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(900);
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-adjust-beginner-mobile.png') });
  await page.setViewportSize({ width: 1280, height: 720 });

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#adjust-position-btn');
  await page.click('#adjust-position-btn');
  await page.waitForSelector('.position-adjust-workbench:not([hidden]) .position-editor');
  assert.equal(await page.locator('.position-adjust-workbench .position-leg').count(), 1,
    'the exact edited composition survives the presentation-level switch');
  await page.click('.position-adjust-workbench [data-position-command="analyze"]');
  await page.waitForSelector('#apply-position-adjustment-btn', { timeout: 30000 });
  assert.match(await page.textContent('.position-adjust-result'), /Broker reserve before.*Broker reserve after/s,
    'Expert receives the same reviewed action with dense accounting facts');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-adjust-expert.png'), fullPage: true });

  const applyResponse = page.waitForResponse(response => response.url().endsWith('/api/position-transformations/apply')
    && response.request().method() === 'POST');
  await page.click('#apply-position-adjustment-btn');
  const applied = await (await applyResponse).json();
  assert.equal(applied.trade.id, created.id, 'a leg adjustment preserves the position identity');
  assert.equal(applied.trade.status, 'ACTIVE');
  assert.equal(applied.trade.legs.length, 1);
  assert.equal(applied.transformation.action, 'REMOVE_LEG');
  await page.waitForFunction(id => location.hash === '#/portfolio/trade/' + id
    && /Realized from partial closes/.test(document.querySelector('#app')?.textContent || ''), created.id,
  { timeout: 30000 });
  await page.evaluate(async () => { Learn.setLevel('beginner'); await App.render(); });
});

test('Practice option lifecycle reviews assignment before atomically preserving the surviving hedge', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const created = await page.evaluate(async () => {
    const beforeShares = (await API.getFresh('/api/positions')).positions
      .find(position => position.symbol === 'AAPL')?.shares || 0;
    const expiration = (await API.getFresh('/api/research/AAPL/expirations')).expirations[2];
    const order = { symbol: 'AAPL', strategy: 'CREDIT_PUT_SPREAD', qty: 1,
      thesis: 'bullish', horizon: 'month', riskMode: 'balanced', intent: 'ACQUIRE',
      source: 'DOM_OPTION_LIFECYCLE', fillNature: 'PROPOSED', legs: [
        { action: 'SELL', type: 'PUT', strike: '260', expiration: expiration,
          ratio: 1, multiplier: 100, positionEffect: 'OPEN' },
        { action: 'BUY', type: 'PUT', strike: '250', expiration: expiration,
          ratio: 1, multiplier: 100, positionEffect: 'OPEN' }
      ] };
    const preview = await API.post('/api/trades/preview', order);
    if (preview.requiredAcks && preview.requiredAcks.length) {
      order.acknowledgedRisks = preview.requiredAcks.map(item => item.id);
      order.ackToken = preview.ackToken;
    }
    return { trade: (await API.post('/api/trades', order)).trade, beforeShares: beforeShares };
  });

  await go('#/portfolio/trade/' + created.trade.id);
  await page.waitForSelector('#option-lifecycle-btn');
  assert.equal(await page.locator('#settle-btn').count(), 0,
    'the visible flow uses the exact leg lifecycle review rather than a whole-position settle shortcut');
  await page.click('#option-lifecycle-btn');
  await page.waitForSelector('.position-lifecycle-workbench:not([hidden]) .position-lifecycle-leg');
  assert.equal(await page.locator('.position-lifecycle-leg').count(), 2,
    'every current option contract remains individually reviewable');
  await page.locator('.position-lifecycle-leg').first().getByRole('button', { name: 'Review assignment' }).click();
  await page.waitForSelector('#apply-option-lifecycle-btn', { timeout: 30000 });
  assert.equal(await page.locator('.position-lifecycle-leg.is-selected').count(), 1,
    'the chosen contract remains visibly selected beside its review');
  const beginnerReview = await page.textContent('#option-lifecycle-preview');
  assert.match(beginnerReview, /Assignment reviewed.*Nothing has changed yet/s);
  assert.match(beginnerReview, /Before.*After/s);
  assert.match(beginnerReview, /Selected contract.*260 PUT/s);
  assert.match(beginnerReview, /100 shares acquired at the strike/);
  assert.match(beginnerReview, /Option result realized.*Realized on position to date/s,
    'Beginner sees the actual money result without opening an accounting disclosure');
  assert.doesNotMatch(beginnerReview, /\bASSIGNMENT\b/,
    'the lifecycle workbench translates the internal event enum everywhere it is visible');
  assert.match(beginnerReview, /SETTLEMENT BASIS.*active lane mark/s);
  assert.match(beginnerReview, /See exact option accounting and reserve changes/,
    'Beginner keeps the exact accounting through progressive disclosure');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(300);
  const mobile = await page.evaluate(() => ({ width: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
    buttons: Array.from(document.querySelectorAll('.position-lifecycle-workbench button')).map(node => {
      const r = node.getBoundingClientRect();
      return { left: r.left, right: r.right, width: r.width, text: node.textContent.trim() };
    }).filter(item => item.width > 0) }));
  assert.ok(mobile.scrollWidth <= mobile.width + 1,
    'the lifecycle workbench fits the mobile viewport: ' + JSON.stringify(mobile));
  assert.ok(mobile.buttons.every(item => item.left >= -1 && item.right <= mobile.width + 1),
    'every lifecycle command remains fully visible on mobile: ' + JSON.stringify(mobile.buttons));
  await page.locator('.position-lifecycle-workbench').evaluate(node => node.scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(900);
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-lifecycle-beginner-mobile.png') });
  await page.setViewportSize({ width: 1280, height: 720 });

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.click('#option-lifecycle-btn');
  await page.waitForSelector('.position-lifecycle-workbench:not([hidden]) .position-lifecycle-leg');
  await page.locator('.position-lifecycle-leg').first().getByRole('button', { name: 'Review assignment' }).click();
  await page.waitForSelector('#apply-option-lifecycle-btn', { timeout: 30000 });
  const expertReview = await page.textContent('#option-lifecycle-preview');
  assert.match(expertReview, /Option result realized.*Realized on position to date/s);
  assert.match(expertReview, /Strike cash used.*Practice cash after/s);
  assert.match(expertReview, /Broker reserve before.*Broker reserve after/s,
    'Expert receives the same reviewed action with dense exact accounting');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-lifecycle-expert.png'), fullPage: true });

  const applyResponse = page.waitForResponse(response => response.url().endsWith('/api/position-transformations/apply')
    && response.request().method() === 'POST');
  await page.click('#apply-option-lifecycle-btn');
  const applied = await (await applyResponse).json();
  assert.equal(applied.transformation.action, 'ASSIGNMENT');
  assert.equal(applied.trade.id, created.trade.id, 'assignment preserves the position identity');
  assert.equal(applied.trade.status, 'ACTIVE', 'the surviving hedge remains managed');
  assert.equal(applied.trade.strategy, 'PROTECTIVE_PUT');
  assert.equal(applied.trade.intent, 'HEDGE');
  assert.equal(applied.trade.legs.length, 1);
  assert.equal(applied.trade.legs[0].action, 'BUY');
  assert.equal(applied.trade.legs[0].type, 'PUT');
  await page.waitForFunction(id => location.hash === '#/portfolio/trade/' + id
    && /Protective put/i.test(document.querySelector('.quote-hero')?.textContent || ''), created.trade.id,
  { timeout: 30000 });
  const afterShares = await page.evaluate(async () => (await API.getFresh('/api/positions')).positions
    .find(position => position.symbol === 'AAPL')?.shares || 0);
  assert.equal(afterShares, created.beforeShares + 100,
    'the assigned shares enter the existing Practice holdings book exactly once');
  await page.evaluate(async () => { Learn.setLevel('beginner'); await App.render(); });
});

test('Plan Manage keeps assigned shares visible when no option leg survives', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const result = await page.evaluate(async () => {
    const plan = await API.post('/api/plans', {
      clientRequestId: 'dom-plan-assignment-shares-' + Date.now(), symbol: 'AAPL', intent: 'ACQUIRE',
      title: 'Acquire shares through assignment', thesis: 'bullish', horizonDays: 30,
      riskMode: 'conservative'
    });
    const expiration = (await API.getFresh('/api/research/AAPL/expirations')).expirations[2];
    const selected = await API.post('/api/plans/' + plan.id + '/strategy/custom', {
      expectedVersion: plan.version,
      position: { symbol: 'AAPL', strategy: 'CASH_SECURED_PUT', qty: 1, fillNature: 'PROPOSED', legs: [
        { action: 'SELL', type: 'PUT', strike: '260', expiration: expiration,
          ratio: 1, multiplier: 100, positionEffect: 'OPEN' }
      ] }
    });
    const reviewed = await API.post('/api/plans/' + plan.id + '/decision/preview', {
      expectedVersion: selected.plan.version, qty: 1
    });
    const order = { expectedVersion: selected.plan.version, qty: 1,
      proposedNetCents: reviewed.order.proposedNetCents,
      acknowledgedRisks: (reviewed.requiredAcks || []).map(item => item.id) };
    if (reviewed.ackToken) order.ackToken = reviewed.ackToken;
    const opened = await API.post('/api/plans/' + plan.id + '/decision/trade', order);
    const request = { source: 'PRACTICE_TRADE', sourceId: opened.trade.id, planId: plan.id,
      expectedPlanVersion: opened.plan.version, action: 'ASSIGNMENT', legIndex: 0 };
    const lifecycle = await API.post('/api/position-transformations/preview', request);
    request.previewToken = lifecycle.previewToken;
    const applied = await API.post('/api/position-transformations/apply', request);
    return { planId: plan.id, tradeStatus: applied.trade.status,
      currentIdentity: applied.management.currentPosition.identity,
      currentLegs: applied.management.currentPosition.legs };
  });

  assert.equal(result.tradeStatus, 'EXPIRED', 'the option contract is complete');
  assert.equal(result.currentIdentity, 'Long shares');
  assert.equal(result.currentLegs.length, 1);
  assert.equal(result.currentLegs[0].instrumentType, 'STOCK');

  // Hold the small, post-ready Strategy-conclusion enrichment. It must update only that
  // folded conclusion; it cannot repaint the later Live band or steal keyboard focus.
  let releaseStrategyLatest;
  let markStrategyLatestStarted;
  const strategyLatestGate = new Promise(resolve => { releaseStrategyLatest = resolve; });
  const strategyLatestStarted = new Promise(resolve => { markStrategyLatestStarted = resolve; });
  const holdStrategyLatest = async route => {
    markStrategyLatestStarted();
    await strategyLatestGate;
    await route.continue();
  };
  await page.route(`**/api/plans/${result.planId}/strategy/latest`, holdStrategyLatest);
  await go('#/plan/' + result.planId + '/manage-review');
  await page.waitForSelector('.plan-current-receipt-position');
  await Promise.race([strategyLatestStarted,
    new Promise((_, reject) => setTimeout(() => reject(new Error('Strategy conclusion enrichment did not start')), 5000))]);
  await page.evaluate(() => {
    window.__managedReceiptNode = document.querySelector('.plan-current-receipt-position');
    window.__strategyConclusionButton = document.querySelector('[data-band="strategy"] .flow-band-conclusion');
    window.__strategyConclusionButton.focus({ preventScroll: true });
  });
  releaseStrategyLatest();
  await page.waitForFunction(() => /Selected structure:/.test(
    document.querySelector('[data-band="strategy"] .flow-band-conclusion-body')?.textContent || ''));
  assert.equal(await page.evaluate(() => window.__managedReceiptNode
    === document.querySelector('.plan-current-receipt-position')), true,
  'enriching the folded Strategy conclusion preserves the exact mounted Manage receipt');
  assert.equal(await page.evaluate(() => document.activeElement === window.__strategyConclusionButton), true,
    'conclusion enrichment preserves keyboard focus on the same button');
  await page.unroute(`**/api/plans/${result.planId}/strategy/latest`, holdStrategyLatest);
  const text = await page.textContent('#app');
  assert.match(text, /Current Plan position.*Long shares.*Account shares.*ASSIGNMENT/s);
  assert.doesNotMatch(text, /linked position is unavailable/i);
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-assigned-shares-plan-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  const mobile = await page.evaluate(() => ({ client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth }));
  assert.ok(mobile.scroll <= mobile.client + 1,
    'receipt-backed Plan position remains contained on mobile: ' + JSON.stringify(mobile));
  await page.locator('.plan-current-receipt-position').evaluate(node => node.scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(__dirname, 'shots/trader-own-p5-assigned-shares-plan-mobile.png') });
  await page.setViewportSize({ width: 1280, height: 720 });
});

test('financial formatters and mixed packages fail closed instead of rendering NaN', async () => {
  const safety = await page.evaluate(() => ({
    money: UI.fmtMoney(Number.NaN), compact: UI.fmtMoneyCompact(Infinity),
    pct: UI.fmtPct(Number.NaN), num: UI.fmtNum(-Infinity),
    option: UI.firstOptionLeg([
      { action: 'BUY', type: 'STOCK', strike: null, expiration: null },
      { action: 'SELL', type: 'CALL', strike: '260', expiration: '2026-08-21' }
    ])
  }));
  assert.deepEqual([safety.money, safety.compact, safety.pct, safety.num], ['—', '—', '—', '—']);
  assert.equal(safety.option.strike, '260', 'stock-first packages resolve their listed option contract');

  const contextContract = await page.evaluate(() => {
    const activePlanGoalBefore = App.context.goal();
    App.context.update({ symbol: 'AAPL', goal: 'INCOME', horizon: 'month', thesis: 'neutral' });
    const cleared = App.context.update({ goal: 'ALL' }).intent;
    const activePlanGoal = App.context.goal();
    const covered = Builder.TEMPLATES.find(t => t.family === 'COVERED_CALL');
    var owner = { builderForm: {
      symbol: 'AAPL', qty: 1, goal: 'INCOME', templateKey: covered.key,
      legs: [{ action: 'BUY', type: 'STOCK', strike: null, expiration: null, ratio: 1 },
        { action: 'SELL', type: 'CALL', strike: '260', expiration: '2026-08-21', ratio: 1 }],
      excluded: {}
    } };
    const ticket = Builder.prepareTicket(owner, { goal: 'INCOME', thesis: 'neutral', horizon: 'month' });
    return { cleared, activePlanGoalBefore, activePlanGoal,
      templateIntent: covered.primaryIntent, ticketIntent: ticket.intent };
  });
  assert.equal(contextContract.cleared, null, 'Everything clears a stale single-goal context');
  assert.equal(contextContract.activePlanGoal, contextContract.activePlanGoalBefore,
    'editing provisional discovery context never rewrites the Plan currently on screen');
  assert.equal(contextContract.templateIntent, 'INCOME', 'server catalog supplies the structure intent');
  assert.equal(contextContract.ticketIntent, 'INCOME', 'the explicit Plan goal survives Builder normalization');
  assert.equal(await page.evaluate(() => UI.maxProfitLabel('DIAGONAL_CALL', 'time', null, true)),
    'No single dollar ceiling', 'a multi-expiration package names the missing scalar without model jargon or fake infinity');
  const tieContract = await page.evaluate(() => {
    const candidate = (score, verdict) => ({ evaluation: { decisionScore: score,
      assessment: { economics: { verdict } } } });
    return {
      threshold: ViewPlan.nearTieScorePoints,
      inside: ViewPlan.candidatesNearTie(candidate(70, 'MIXED'), candidate(68, 'MIXED')),
      outside: ViewPlan.candidatesNearTie(candidate(70, 'MIXED'), candidate(67.9, 'MIXED')),
      differentVerdict: ViewPlan.candidatesNearTie(candidate(70, 'MIXED'), candidate(69, 'FAVORABLE')),
      explanation: Learn.INFO.ranktie.short
    };
  });
  assert.deepEqual(tieContract, { threshold: 2, inside: true, outside: false,
    differentVerdict: false, explanation: tieContract.explanation });
  assert.match(tieContract.explanation, /share an economic verdict.*2 points/i,
    'the near-tie threshold is principled, disclosed, and executable');
  assert.equal(await page.evaluate(() => UI.maxProfitLabel('LONG_CALL', 'single_long', null, true)),
    'no fixed ceiling', 'a genuinely unbounded structure keeps its theoretical truth');

  // Legibility invariant (the AMD bear-call-priced-as-a-debit): a "best possible profit" that is
  // not positive must never launder as a gain — neither in words nor in green styling.
  const profitTruth = await page.evaluate(() => ({
    negExpert: UI.maxProfitLabel('IRON_CONDOR', 'range_credit', -17500, false, []),
    negBeginner: UI.maxProfitLabel('IRON_CONDOR', 'range_credit', -17500, true, []),
    toneNeg: UI.maxProfitTone('IRON_CONDOR', 'range_credit', -17500, []),
    toneZero: UI.maxProfitTone('IRON_CONDOR', 'range_credit', 0, []),
    tonePos: UI.maxProfitTone('IRON_CONDOR', 'range_credit', 30000, []),
    toneUncapped: UI.maxProfitTone('LONG_CALL', 'single_long', null, [])
  }));
  assert.match(profitTruth.negExpert, /no profit possible/,
    'a negative best-case names itself as impossible profit, not a bare dollar figure');
  assert.match(profitTruth.negBeginner, /cannot make money/, 'the beginner label is just as honest');
  assert.equal(profitTruth.toneNeg, 'f-danger', 'a negative ceiling is never styled as a gain');
  assert.equal(profitTruth.toneZero, 'f-danger', 'a break-even ceiling is not a profit either');
  assert.equal(profitTruth.tonePos, 'f-ok', 'genuine upside keeps its green');
  assert.equal(profitTruth.toneUncapped, 'f-ok', 'uncapped upside keeps its green');

  // Info-tooltip invariant (B1): on a fine-pointer desktop the injected italic-i is removed from
  // the text flow (no baseline break) and the LABEL becomes the hover target.
  const infoContract = await page.evaluate(async () => {
    const host = document.createElement('span');
    host.textContent = 'Chance of any profit ';
    const trig = UI.info('pop');
    host.appendChild(trig);
    document.body.appendChild(host);
    await Promise.resolve(); // flush bindInfoHost's microtask
    const out = { hasInfoClass: host.classList.contains('has-info'),
      triggerWidth: trig.getBoundingClientRect().width,
      triggerFocusable: trig.tabIndex >= 0 || trig.tagName === 'BUTTON' };
    host.remove();
    return out;
  });
  // A1 (engine honesty): a gate-failed candidate (viable===false) is not a viable trade — the
  // field must not dress it as "the strongest fit". Missing/true viability reads as viable.
  const viability = await page.evaluate(() => ({
    gateFailed: ViewPlan.candidateViable({ evaluation: { viable: false } }),
    gatePassed: ViewPlan.candidateViable({ evaluation: { viable: true } }),
    unknown: ViewPlan.candidateViable({ evaluation: {} }),
    noEval: ViewPlan.candidateViable({})
  }));
  assert.equal(viability.gateFailed, false, 'a gate-failed structure is not a viable trade');
  assert.equal(viability.gatePassed, true, 'a gate-passed structure is viable');
  assert.equal(viability.unknown, true, 'absent viability is treated as viable (no false alarm)');
  assert.equal(viability.noEval, true, 'a candidate without an evaluation is not flagged unviable');

  assert.equal(infoContract.hasInfoClass, true,
    'the label hosting an info trigger becomes the desktop hover target');
  assert.ok(infoContract.triggerWidth <= 2,
    'the injected italic-i is pulled out of the text flow on a fine-pointer desktop — no baseline break');
  assert.ok(infoContract.triggerFocusable,
    'the icon stays keyboard-reachable even while visually hidden (a11y preserved)');
});

test('welcome proof treats no qualifying idea as a complete result', async () => {
  await page.route('**/api/welcome/teaching-example', r => r.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ symbol: 'AAPL', candidates: [], rejected: [], notes: ['No candidate passed.'] })
  }));
  await page.evaluate(() => localStorage.removeItem('strikebench.welcomeProof'));
  await go('#/home/tour');
  await page.waitForSelector('#welcome-proof-unavailable');
  assert.match(await page.textContent('#welcome-proof-unavailable'), /No AAPL idea passes right now/);
  assert.match(await page.textContent('#welcome-proof-unavailable'), /valid engine result/);
  assert.ok(await page.locator('#welcome-proof-unavailable button').count(), 'the no-trade proof has a next step');
  await page.unroute('**/api/welcome/teaching-example');
});

test('provider-governed sparkline deferral resolves to an honest unavailable state', async () => {
  let releaseOld;
  let oldStarted;
  const oldStartedP = new Promise(resolve => { oldStarted = resolve; });
  const releaseOldP = new Promise(resolve => { releaseOld = resolve; });
  let raceRequests = 0;
  const raceUrl = '**/api/sparklines?symbols=CACHE_RACE&range=3m';
  await page.route(raceUrl, async r => {
    raceRequests++;
    if (raceRequests === 1) {
      oldStarted();
      await releaseOldP;
      await r.fulfill({ contentType: 'application/json', body: JSON.stringify({ sparklines: [{ symbol: 'CACHE_RACE' }] }) });
      return;
    }
    await r.fulfill({ status: 204, body: '' });
  });
  const oldPrefetch = page.evaluate(() => API.prefetch('/api/sparklines?symbols=CACHE_RACE&range=3m'));
  await oldStartedP;
  await page.evaluate(() => API.flushCache());
  releaseOld();
  await oldPrefetch;
  const afterFlush = await page.evaluate(() => API.prefetch('/api/sparklines?symbols=CACHE_RACE&range=3m'));
  assert.equal(afterFlush, null, 'a pre-invalidation response cannot repopulate the current cache generation');
  assert.equal(raceRequests, 2, 'the post-invalidation prefetch reaches the provider instead of reusing stale speculation');
  await page.unroute(raceUrl);

  await page.route('**/api/sparklines?*', r => r.fulfill({ status: 204, body: '' }));
  try {
    await page.evaluate(() => {
      localStorage.setItem('strikebench.welcomed', '1');
      API.flushCache();
      App.navigate('#/home');
    });
    await page.waitForSelector('#app[data-route="home"][data-ready="true"]');
    await page.waitForSelector('.home-market-grid .sym-card');
    const cardCount = await page.locator('.home-market-grid .sym-card').count();
    for (let i = 0; i < cardCount; i++) {
      await page.locator('.home-market-grid .sym-card').nth(i).scrollIntoViewIfNeeded();
    }
    await page.waitForFunction(() => {
      const cards = document.querySelectorAll('.home-market-grid .sym-card');
      const settled = document.querySelectorAll('.home-market-grid .spark-slot-missing .spark-empty');
      return cards.length > 0 && settled.length === cards.length;
    });
    assert.equal(await page.locator('.home-market-grid .spark-loading').count(), 0,
      'a declined optional request never leaves a permanent loading state');
    assert.equal(await page.locator('.home-market-grid .spark-empty').evaluateAll(nodes =>
      nodes.every(n => /history|chart|interactive market work/i.test(n.getAttribute('aria-label') || n.textContent))), true,
      'every deferred chart carries a specific accessible reason without repeating a badge on every card');
    assert.match(await page.textContent('#home-history-notice'), /Charts unavailable.*current quotes remain usable/i,
      'one visible aggregate notice explains the missing charts and preserves the usable quote state');
  } finally {
    await page.unroute('**/api/sparklines?*');
  }
});

test('research AAPL: hero quote, events, news, focused chain, show-all toggle', async () => {
  await go('#/research');
  await page.fill('#symbol-input', 'AAPL');
  await page.click('#symbol-go');
  await page.waitForSelector('#symbol-proposed-trades');
  const plansBefore = await page.evaluate(() => PlanStore.all().length);
  await openResearchTab('overview');
  await page.waitForSelector('#research-symbol');
  assert.match(await page.textContent('#research-symbol'), /AAPL/);
  // Coming up: expirations (and any earnings/filing signals) as dated chips
  await page.waitForSelector('#events-card .event-timeline-item');
  assert.match(await page.textContent('#events-card'), /OPTION EXPIRY/i);
  assert.equal(await page.locator('.research-local-tabs [role="tab"]').count(), 0,
    'read-only Research no longer hides its work behind a local tab set');
  assert.equal(await page.locator('#research-flow > .flow-band').count(), 3,
    'understanding, evidence, and option inspection are bands in one mounted document');
  assert.equal(await page.locator('#plan-flow').count(), 0,
    'inspecting a symbol never mints a Plan or borrows the Plan journey flow');
  await page.evaluate(() => { window.__publicResearchFlow = document.getElementById('research-flow'); });
  await page.waitForSelector('#news-overview-card .news-tile');
  assert.ok(await page.locator('#news-overview-card .news-tile').count() >= 1,
    'latest news remains part of the market overview');
  assert.match(await page.textContent('#news-overview-card'), /not news about AAPL.*never become observed evidence/is,
    'fabricated teaching catalysts cannot read as article summaries or observed news');
  // News NEVER silently vanishes — card renders with items or an honest empty state
  await openResearchTab('news');
  await page.waitForSelector('#news-card');
  assert.match(await page.textContent('#news-card'), /Teaching catalysts — fabricated|News & filings/,
    'the full card names generated teaching prompts differently from observed news');
  assert.ok((await page.locator('#news-card .news-tile').count()) >= 1, 'fixture headlines render');
  await page.waitForSelector('#research-evidence .evidence-badge:has-text("DEMO")');
  assert.equal(await page.locator('.quote-hero .badge:has-text("DEMO DATA")').count(), 0,
    'the hero does not repeat the page-level Demo evidence label');
  await openResearchTab('options');
  assert.equal(await page.evaluate(() => window.__publicResearchFlow === document.getElementById('research-flow')), true,
    'moving between public Research bands updates the mounted document in place');
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
  assert.equal(await page.evaluate(() => PlanStore.all().length), plansBefore,
    'Overview, news and option-chain inspection remain read-only until an explicit Plan action');
});

test('Research renders versioned keyword sentiment and typed event risk without pretending it read articles', async () => {
  const news = {
    symbol: 'AAPL', scorerVersion: 'sentiment-keyword-v1', evidence: 'OBSERVED',
    note: 'Keyword-derived headline classification; open the source before treating a headline as evidence.',
    aggregate: { available: true, trend: 'POSITIVE', score: 0.5, totalHeadlines: 3, scoredHeadlines: 2,
      positiveHeadlines: 1, negativeHeadlines: 0, mixedHeadlines: 1, neutralHeadlines: 1,
      coverageRatio: 0.67, eventRisk: true, eventRiskHeadlines: 1, eventRiskFlags: ['EARNINGS'],
      basis: 'KEYWORD_DERIVED', scorerVersion: 'sentiment-keyword-v1',
      note: 'Keyword-derived headline classification; open the source before treating a headline as evidence.' },
    items: [
      { symbol: 'AAPL', headline: 'AAPL beats estimates as growth accelerates', source: 'Observed Wire',
        url: 'https://example.invalid/positive', publishedEpochMs: Date.now(), classification: 'POSITIVE', score: 1,
        basis: 'KEYWORD_DERIVED', positiveKeywords: ['beat', 'growth', 'accelerat'], negativeKeywords: [],
        eventRisk: true, eventRiskFlags: ['EARNINGS'], scorerVersion: 'sentiment-keyword-v1' },
      { symbol: 'AAPL', headline: 'AAPL outlook mixes growth with warning', source: 'Observed Wire',
        url: 'https://example.invalid/mixed', publishedEpochMs: Date.now() - 1000, classification: 'MIXED', score: 0,
        basis: 'KEYWORD_DERIVED', positiveKeywords: ['growth'], negativeKeywords: ['warning'],
        eventRisk: false, eventRiskFlags: [], scorerVersion: 'sentiment-keyword-v1' },
      { symbol: 'AAPL', headline: 'AAPL schedules investor presentation', source: 'Observed Wire',
        url: 'https://example.invalid/neutral', publishedEpochMs: Date.now() - 2000, classification: 'NEUTRAL', score: 0,
        basis: 'KEYWORD_DERIVED', positiveKeywords: [], negativeKeywords: [], eventRisk: false,
        eventRiskFlags: [], scorerVersion: 'sentiment-keyword-v1' }
    ]
  };
  news.eventRisk = [news.items[0]];
  const prior = await page.evaluate(() => ({
    world: App.state.world, level: Learn.currentLevel(), universe: App.state.universe,
    revision: App.state.worldRevision || 0, epoch: App.state.worldRevisionEpoch || null
  }));
  const observedUniverse = JSON.parse(JSON.stringify(prior.universe));
  observedUniverse.lane = 'OBSERVED';
  observedUniverse.active = Object.assign({}, observedUniverse.active, {
    label: 'Observed sentiment fixture', symbols: Array.from(new Set([
      'AAPL', ...((observedUniverse.active && observedUniverse.active.symbols) || [])
    ]))
  });
  let mockedWorld = prior.world;
  const worldRoute = async route => {
    if (route.request().method() === 'PUT') mockedWorld = route.request().postDataJSON().world;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      world: mockedWorld,
      universe: mockedWorld === 'observed' ? observedUniverse : prior.universe,
      revision: prior.revision,
      epoch: prior.epoch
    }) });
  };
  // The fixture jar deliberately refuses an Observed lane. Mock the complete selector
  // contract for this presentation test so App.switchWorld still moves the authoritative
  // response, App state, MarketStore, and universe together; never mutate lane owners by hand.
  await page.route('**/api/world', worldRoute);
  await page.route('**/api/research/AAPL/news', route => route.fulfill({
    contentType: 'application/json', body: JSON.stringify(news)
  }));
  try {
    const switched = await page.evaluate(async () => {
      API.flushCache();
      Learn.setLevel('beginner');
      return App.state.world === 'observed' || await App.switchWorld('observed');
    });
    assert.equal(switched, true, 'the sentiment fixture enters Observed through the canonical world transition');
    await go('#/research/AAPL');
    await page.waitForSelector('#news-sentiment-summary');
    const summary = await page.textContent('#news-sentiment-summary');
    assert.match(summary, /Positive keyword trend.*2 of 3 headlines matched.*67% coverage/is);
    assert.match(summary, /1 event-risk headline.*Earnings/is,
      'the aggregate exposes the typed event-risk feed beside coverage');
    assert.equal(await page.locator('#news-overview-card .news-tile .news-sentiment-positive').count(), 1,
      'each matching headline carries its colored but explicitly keyword-derived classification');
    assert.match(await page.textContent('#news-overview-card .news-tile .news-sentiment-positive'), /Positive.*keyword-derived/i);
    assert.match(await page.getAttribute('#news-overview-card .news-tile .news-sentiment-positive', 'title'), /positive: beat, growth, accelerat/i,
      'the inspectable matched-keyword basis is available without claiming article understanding');
    await page.click('#level-switch button[data-level="expert"]');
    await page.waitForFunction(() => /Scorer sentiment-keyword-v1/.test(
      document.getElementById('news-sentiment-summary')?.textContent || ''));
    assert.match(await page.textContent('#news-sentiment-summary'), /aggregate score 0\.50/i,
      'Expert sees the exact persisted scorer identity and aggregate value');
  } finally {
    await page.evaluate(async priorState => {
      API.flushCache();
      Learn.setLevel(priorState.level);
      if (App.state.world !== priorState.world) await App.switchWorld(priorState.world);
    }, prior);
    await page.unroute('**/api/research/AAPL/news');
    await page.unroute('**/api/world', worldRoute);
  }
});

test('research keeps non-optionable funds useful without inventing an options Plan or company catalyst', async () => {
  await go('#/research/VTSAX');
  await page.waitForSelector('#symbol-proposed-trades');
  assert.match(await page.textContent('#symbol-proposed-trades'), /no listed options/i);
  assert.ok(await page.locator('#history-card').count(), 'stock history remains available without an options Plan');
  assert.equal(await page.locator('#symbol-proposed-goal .choice-option').count(), 5,
    'all goals remain visible even when the market cannot price an option package');
  assert.equal(await page.locator('#symbol-find-proposed').isDisabled(), true,
    'one explicit command blocks creation of an unpriceable options Plan');
  assert.equal(await page.getAttribute('#symbol-find-proposed', 'aria-describedby'), 'symbol-proposed-unavailable',
    'the disabled command points assistive technology at its visible reason');
  assert.ok(await page.locator('#symbol-proposed-unavailable').isVisible(),
    'the data reason sits beside the disabled command rather than in a remote warning');
  await page.waitForSelector('#news-overview-card .news-tile');
  const prompts = await page.textContent('#news-overview-card');
  assert.match(prompts, /scenario prompts, not news about VTSAX/i);
  assert.doesNotMatch(prompts, /earnings call|options activity/i,
    'fund teaching prompts cannot claim company earnings or listed-options activity');
});

test('Research entry and destination cards are purposeful, readable, and collision-free', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => {
    localStorage.removeItem('strikebench.riskMode');
    App.state.headerRiskExplicit = false;
    App.context.update({ goal: null, thesis: null, horizon: null });
  });
  await go('#/research');
  await page.waitForSelector('#sector-grid .sym-card[data-sym="AAPL"] .spark-svg', { timeout: 15000 });
  const geometry = await page.evaluate(() => {
    const shell = document.getElementById('research-symbol-context').getBoundingClientRect();
    const input = document.querySelector('#research-symbol-context input').getBoundingClientRect();
    const app = document.getElementById('app').getBoundingClientRect();
    const collisions = Array.from(document.querySelectorAll('#sector-grid .spark-slot')).filter(slot => {
      const a = slot.querySelector('.spark-readout'), b = slot.querySelector('.spark-ev');
      if (!a || !b) return false;
      const x = a.getBoundingClientRect(), y = b.getBoundingClientRect();
      return x.left < y.right && x.right > y.left && x.top < y.bottom && x.bottom > y.top;
    }).length;
    return { shellWidth: shell.width, inputWidth: input.width, appWidth: app.width, collisions };
  });
  assert.ok(geometry.shellWidth > geometry.appWidth * 0.85 && geometry.inputWidth <= 522,
    'the context card keeps page symmetry while only the editor is purpose-sized: ' + JSON.stringify(geometry));
  assert.equal(await page.locator('#research-symbol-context .symbol-context-label').count(), 0,
    'the full-width Research context keeps its accessible name without a redundant visible field label');
  assert.equal(await page.getAttribute('#symbol-input', 'aria-label'), 'Which stock do you want to understand?',
    'the visually unlabeled Research input remains explicit to assistive technology');
  assert.equal(geometry.collisions, 0, 'sparkline readout never overlaps its evidence badge');
  assert.equal(await page.locator('#sector-history-notice').isVisible(), false,
    'a hidden history notice cannot survive as an empty warning bar');
  assert.ok(await page.locator('#sector-chips .sector-chip').first().isVisible(),
    'market scope stays visible even when the explicit Demo lane has only one sector');
  assert.ok(await page.locator('#sector-grid .destination-cue').count() >= 4,
    'destination cards use one explicit open affordance instead of hover underlines');
  await page.click('#sector-grid .sym-card[data-sym="AAPL"]');
  await page.waitForSelector('#symbol-proposed-trades');
  assert.doesNotMatch(await page.textContent('#research-hero'), /building history/i,
    'Research never presents an inert IV-rank progress placeholder');
  assert.equal(await page.locator('#research-hero #plan-understand-next').count(), 0,
    'public Research keeps Evidence in its named Flow band and does not duplicate that entry inside the quote hero');
  assert.match(await page.textContent('#symbol-proposed-trades'), /Choose the job, then continue/);
  assert.equal(await page.locator('.symbol-proposed-starting').count(), 0,
    'a fresh Research handoff has no invented starting assumptions');
  assert.match(await page.textContent('.symbol-proposed-missing'), /goal.*horizon.*risk posture/i,
    'the compact handoff names every fact that still belongs in Your view');
  assert.equal(await page.locator('#symbol-find-proposed').isDisabled(), true,
    'the action is unavailable until a goal is actually chosen');
  const intentChoices = await page.evaluate(() => {
    const card = document.querySelector('#symbol-proposed-trades');
    const select = document.querySelector('#symbol-proposed-goal');
    const action = document.querySelector('#symbol-find-proposed');
    const cardBox = card.getBoundingClientRect();
    return {
      goals: select.querySelectorAll('.choice-option').length,
      selectInside: select.getBoundingClientRect().left >= cardBox.left
        && select.getBoundingClientRect().right <= cardBox.right,
      actionInside: action.getBoundingClientRect().left >= cardBox.left
        && action.getBoundingClientRect().right <= cardBox.right,
      actionVisible: action.getBoundingClientRect().width > 120 && action.getBoundingClientRect().height > 30
    };
  });
  assert.equal(intentChoices.goals, 5, 'every Plan goal remains available before commitment');
  assert.ok(intentChoices.selectInside && intentChoices.actionInside && intentChoices.actionVisible,
    'the compact goal and action remain fully visible inside the discovery card: ' + JSON.stringify(intentChoices));
  await page.setViewportSize({ width: 1600, height: 900 });
  const desktopCommand = await page.evaluate(() => {
    const app = document.getElementById('app').getBoundingClientRect();
    const row = document.querySelector('.research-decision-row');
    const card = document.getElementById('symbol-proposed-trades').getBoundingClientRect();
    const copy = document.querySelector('.symbol-proposed-copy').getBoundingClientRect();
    const action = document.querySelector('.symbol-proposed-action').getBoundingClientRect();
    const select = document.getElementById('symbol-proposed-goal').getBoundingClientRect();
    const button = document.getElementById('symbol-find-proposed').getBoundingClientRect();
    return { appWidth: app.width, cardWidth: card.width, cardHeight: card.height,
      rowDisplay: getComputedStyle(row).display, copyTop: copy.top, actionTop: action.top,
      selectBottom: select.bottom, buttonBottom: button.bottom };
  });
  assert.equal(desktopCommand.rowDisplay, 'block',
    'wide desktop keeps quote context above the command instead of stretching two false peer cards');
  assert.ok(desktopCommand.cardWidth > desktopCommand.appWidth * .90 && desktopCommand.cardHeight < 180,
    'Ready to compare uses the desktop width as a compact action strip: ' + JSON.stringify(desktopCommand));
  assert.ok(Math.abs(desktopCommand.copyTop - desktopCommand.actionTop) < 12
      && Math.abs(desktopCommand.selectBottom - desktopCommand.buttonBottom) < 3,
    'copy and controls start together; the command aligns with its field rather than floating mid-card: '
      + JSON.stringify(desktopCommand));
  await page.locator('.research-decision-row').screenshot({
    path: path.join(__dirname, 'shots/program-one-research-ready-desktop.png')
  });
  await page.evaluate(() => { window.__responsiveResearchFlow = document.getElementById('research-flow'); });
  for (const viewport of [{ width: 2560, height: 1440 }, { width: 2048, height: 1000 },
    { width: 1920, height: 900 }, { width: 1440, height: 900 }, { width: 1280, height: 800 },
    { width: 1000, height: 900 }, { width: 390, height: 844 },
    { width: 375, height: 812 }, { width: 320, height: 700 }]) {
    await page.setViewportSize(viewport);
    const responsive = await page.evaluate(() => {
      const app = document.getElementById('app').getBoundingClientRect();
      const ready = document.getElementById('symbol-proposed-trades').getBoundingClientRect();
      const bar = document.querySelector('.plan-bar-inner')?.getBoundingClientRect();
      return { viewport: innerWidth, appWidth: app.width, readyWidth: ready.width, readyHeight: ready.height,
        planBarWidth: bar?.width || 0,
        documentWidth: document.documentElement.scrollWidth,
        goals: document.querySelectorAll('#symbol-proposed-goal .choice-option').length,
        sameFlow: window.__responsiveResearchFlow === document.getElementById('research-flow') };
    });
    assert.ok(responsive.documentWidth <= viewport.width + 2,
      'Research remains page-contained at ' + viewport.width + 'px: ' + JSON.stringify(responsive));
    assert.equal(responsive.goals, 5,
      'mobile and logical-5K layouts retain every Plan goal at ' + viewport.width + 'px');
    assert.equal(responsive.sameFlow, true,
      'responsive CSS never remounts the public Research SPA document');
    assert.ok(responsive.readyWidth <= responsive.appWidth + 2,
      'Ready to compare remains inside the Research workspace at ' + viewport.width + 'px');
    assert.ok(responsive.appWidth >= viewport.width * .88,
      'Research spends the available canvas at ' + viewport.width + 'px: ' + JSON.stringify(responsive));
    if (viewport.width === 2560) {
      assert.ok(responsive.appWidth >= 2400 && responsive.readyWidth >= responsive.appWidth * .90
          && responsive.readyHeight < 180,
        '2560×1440 spends the logical 5K canvas on a compact working command: ' + JSON.stringify(responsive));
      if (responsive.planBarWidth) assert.ok(responsive.planBarWidth >= responsive.appWidth * .98,
        'the persistent Plan context bar aligns with the widened Research document: ' + JSON.stringify(responsive));
      await page.click('#symbol-proposed-goal .choice-option[data-value="INCOME"]');
      assert.equal(await page.locator('#symbol-find-proposed').isDisabled(), false,
        'the actual 2560px goal control enables the canonical continuation');
      assert.equal((await page.textContent('#symbol-find-proposed')).trim(), 'Continue to Your view');
      assert.match(await page.textContent('.symbol-proposed-missing'), /horizon.*risk posture/i,
        'the 2560px command immediately removes the chosen goal from its visible missing facts');
      await page.screenshot({ path: path.join(__dirname, 'shots/program-one-research-ready-2560.png'), fullPage: false });
    }
    if (viewport.width === 390) {
      await page.click('#symbol-proposed-goal .choice-option[data-value="DIRECTIONAL"]');
      assert.equal(await page.locator('#symbol-find-proposed').isDisabled(), false,
        'the same mobile goal control enables the same canonical continuation');
      assert.equal((await page.textContent('#symbol-find-proposed')).trim(), 'Continue to Your view');
      assert.match(await page.textContent('.symbol-proposed-missing'), /view.*horizon.*risk posture/i,
        'mobile names the directional facts that still belong in Your view');
      await page.screenshot({ path: path.join(__dirname, 'shots/program-one-research-ready-mobile.png'), fullPage: false });
    }
  }
  await page.setViewportSize({ width: 1600, height: 900 });
  await page.click('#symbol-proposed-goal .choice-option[data-value="INCOME"]');
  assert.equal((await page.locator('#symbol-find-proposed').textContent()).trim(), 'Continue to Your view',
    'a non-directional goal still requires an explicit horizon and risk posture');
  await page.click('#symbol-proposed-goal .choice-option[data-value="DIRECTIONAL"]');
  assert.equal((await page.locator('#symbol-find-proposed').textContent()).trim(), 'Continue to Your view',
    'the same canonical command routes a directional Plan through all missing declarations');
  await page.setViewportSize({ width: 1280, height: 720 });

  const plansBeforeEvidence = await page.evaluate(() => PlanStore.all().length);
  await openResearchTab('view');
  await page.waitForSelector('#tv-view');
  assert.equal(await page.getByLabel('2 · What do you expect next?').count(), 1,
    'the forward outcome control follows the trigger and is associated with its visible label');
  assert.equal(await page.getByLabel('1 · What just happened?').count(), 1,
    'the historical trigger is asked first and is associated with its visible label');
  assert.equal(await page.getByLabel('3 · Over how many trading days?').count(), 1,
    'the public Research horizon control is associated with its visible label');
  const evidenceOrder = await page.evaluate(() => Array.from(document.querySelectorAll(
    '#test-your-view #tv-setup, #test-your-view #tv-view, #test-your-view #tv-horizon'
  )).map(node => node.id));
  assert.deepEqual(evidenceOrder, ['tv-setup', 'tv-view', 'tv-horizon'],
    'the evidence question reads in time order: trigger, expected outcome, then horizon');
  assert.equal(await page.locator('#tv-setup .info-trigger[data-term="historicalsetup"], #tv-view .info-trigger[data-term="thesis"], .tv-horizon-field .info-trigger[data-term="horizon"]').count(), 3,
    'all three technical labels use the same registry-backed explanation system');
  await page.evaluate(() => { window.__researchLensFlow = document.getElementById('research-flow'); });
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'expert' && document.querySelector('#tv-setup'));
  assert.equal(await page.evaluate(() => window.__researchLensFlow === document.getElementById('research-flow')), true,
    'Beginner/Expert changes the mounted Research lens without remounting the destination');
  await page.locator('#tv-setup .info-trigger[data-term="historicalsetup"]').first().evaluate(el => el.focus());
  await page.waitForSelector('#info-pop');
  assert.match(await page.textContent('#info-pop'), /ResearchQuestionEngine catalog key.*forward-outcome claim/is,
    'Expert opens the registry-backed technical definition (label hover / keyboard focus) instead of losing earlier explanations');
  await page.keyboard.press('Escape');
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'beginner' && document.querySelector('#tv-setup'));
  assert.equal(await page.locator('#tv-view .choice-option.active').count(), 0,
    'read-only Research never displays an implicit bullish default');
  assert.equal(await page.locator('#tv-setup .choice-option.active').count(), 0,
    'read-only Research never displays an implicit historical trigger');
  assert.equal(await page.inputValue('#tv-horizon'), '',
    'read-only Research never displays an implicit horizon');
  assert.match(await page.textContent('#test-your-view'), /Complete the question to test it/);
  assert.equal(await page.locator('#research-outcomes').count(), 0,
    'statistical lenses do not run under an assumption the user has not selected');
  for (const viewport of [{ width: 2560, height: 1440 }, { width: 2048, height: 1000 },
    { width: 1920, height: 900 }, { width: 1440, height: 900 }, { width: 1280, height: 800 },
    { width: 1000, height: 900 }, { width: 390, height: 844 },
    { width: 375, height: 812 }, { width: 320, height: 700 }]) {
    await page.setViewportSize(viewport);
    const evidenceLayout = await page.evaluate(() => {
      const app = document.getElementById('app').getBoundingClientRect();
      const setup = document.getElementById('tv-setup');
      const view = document.getElementById('tv-view');
      const horizon = document.querySelector('.tv-horizon-field');
      const columns = node => getComputedStyle(node.querySelector('.choice-group'))
        .gridTemplateColumns.split(' ').filter(Boolean).length;
      return {
        viewport: innerWidth,
        appWidth: app.width,
        documentWidth: document.documentElement.scrollWidth,
        setupTop: setup.getBoundingClientRect().top,
        viewTop: view.getBoundingClientRect().top,
        horizonTop: horizon.getBoundingClientRect().top,
        setupChoices: setup.querySelectorAll('.choice-option').length,
        viewChoices: view.querySelectorAll('.choice-option').length,
        setupColumns: columns(setup),
        viewColumns: columns(view),
        horizonInside: horizon.getBoundingClientRect().right <= app.right + 2,
        sameFlow: window.__researchLensFlow === document.getElementById('research-flow')
      };
    });
    assert.ok(evidenceLayout.documentWidth <= viewport.width + 2,
      'Evidence remains page-contained at ' + viewport.width + 'px: ' + JSON.stringify(evidenceLayout));
    assert.equal(evidenceLayout.sameFlow, true,
      'responsive Evidence composition never remounts the public Research SPA document');
    assert.equal(evidenceLayout.setupChoices, 5,
      'every historical trigger remains available at ' + viewport.width + 'px');
    assert.equal(evidenceLayout.viewChoices, 4,
      'every forward expectation remains available at ' + viewport.width + 'px');
    assert.ok(evidenceLayout.horizonInside,
      'the horizon control remains inside the workspace at ' + viewport.width + 'px');
    assert.ok(evidenceLayout.appWidth >= viewport.width * .88,
      'Evidence spends the available canvas at ' + viewport.width + 'px: ' + JSON.stringify(evidenceLayout));
    if (viewport.width === 2560) {
      assert.ok(evidenceLayout.appWidth >= 2400,
        'Evidence spends the 2560×1440 logical 5K workspace: ' + JSON.stringify(evidenceLayout));
      assert.ok(Math.max(evidenceLayout.setupTop, evidenceLayout.viewTop, evidenceLayout.horizonTop)
          - Math.min(evidenceLayout.setupTop, evidenceLayout.viewTop, evidenceLayout.horizonTop) < 16,
      'trigger, expectation, and horizon begin on one causal row at 2560px: '
          + JSON.stringify(evidenceLayout));
      assert.deepEqual([evidenceLayout.setupColumns, evidenceLayout.viewColumns], [5, 4],
        'wide Evidence uses natural five- and four-choice grids instead of stretched vertical controls');
      await page.screenshot({
        path: path.join(__dirname, 'shots/program-one-research-evidence-2560.png'), fullPage: false
      });
    }
    if (viewport.width === 390) {
      await page.screenshot({
        path: path.join(__dirname, 'shots/program-one-research-evidence-mobile.png'), fullPage: false
      });
    }
  }
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.click('#tv-view .choice-option[data-value="bullish"]');
  await page.fill('#tv-horizon', '20');
  await page.dispatchEvent('#tv-horizon', 'change');
  await page.waitForSelector('#research-outcomes');
  assert.match(await page.textContent('#tv-question-summary'), /After AAPL pulls back.*\d+ trading days.*support your view.*finish higher/i,
    'the chosen controls compose one readable cause-and-outcome question');
  assert.match(await page.textContent('#test-your-view'), /Sideways is what you expect next.*More- or less-volatile is the backdrop.*does not pretend to know whether the path was choppy/is,
    'the UI distinguishes a sideways thesis from measured volatility and does not mislabel volatility as choppiness');
  await page.setViewportSize({ width: 1600, height: 900 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(__dirname, 'shots/program-one-research-evidence-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });
  await chooseHeaderRisk('balanced');
  assert.equal(await page.evaluate(() => PlanStore.all().length), plansBeforeEvidence,
    'selecting and testing a public view does not silently create a Plan');
  await openResearchTab('overview');
  await page.click('#symbol-proposed-goal .choice-option[data-value="ACQUIRE"]');
  await page.click('#symbol-find-proposed');
  await page.waitForFunction(() => document.querySelector('#plan-header')?.textContent.includes('Buy at a discount')
    && location.hash.endsWith('/strategy'), null, { timeout: 15000 });
  await page.waitForSelector('#plan-strategy-body');
  assert.equal(await page.locator('.modal-overlay').count(), 0,
    'an explicit first goal commits directly; change confirmation is reserved for an existing Plan goal');
  assert.match(await page.textContent('#plan-header'), /Buy at a discount/);
  const carried = await page.evaluate(() => {
    const plan = PlanStore.active();
    return { intent: plan.intent, thesis: plan.context.thesis, horizonDays: plan.context.horizonDays };
  });
  assert.deepEqual(carried, { intent: 'ACQUIRE', thesis: 'bullish', horizonDays: 20 },
    'the canonical handoff carries the selected Research view and horizon into the Plan');
  assert.equal(await page.locator('#research-symbol-context').count(), 0,
    'symbol identity is immutable in the Plan header rather than recaptured in the stage');
});

test('Sector Explorer changes the observed universe without remounting the Research SPA', async () => {
  const prior = await page.evaluate(() => ({
    world: App.state.world,
    universe: App.state.universe,
    explorerSector: App.state.explorerSector
  }));
  let activeKey = 'TECH';
  const universe = () => ({
    active: activeKey === 'INDEX'
      ? { sectorKey: 'INDEX', label: 'Indexes', symbols: ['SPY'] }
      : { sectorKey: 'TECH', label: 'Technology', symbols: ['AAPL'] },
    sectors: [
      { key: 'TECH', label: 'Technology', symbols: ['AAPL'] },
      { key: 'INDEX', label: 'Indexes', symbols: ['SPY'] }
    ],
    lane: 'OBSERVED', note: 'SPA identity regression fixture'
  });
  const universeRoute = async route => {
    if (route.request().method() === 'PUT') {
      activeKey = route.request().postDataJSON().sector;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(universe()) });
  };
  await page.route('**/api/universe', universeRoute);
  try {
    await page.evaluate(async fake => {
      App.state.world = 'observed';
      App.state.universe = fake;
      App.state.explorerSector = 'INDEX';
      history.replaceState(null, '', '#/research');
      await App.render();
    }, universe());
    await page.waitForSelector('#set-universe-btn');
    await page.waitForSelector('#sector-grid .sym-card[data-sym="SPY"]');
    await page.evaluate(() => {
      window.__sectorExplorerIdentity = document.getElementById('sector-explorer');
      window.__sectorGridIdentity = document.getElementById('sector-grid');
    });
    await page.click('#set-universe-btn');
    await page.waitForSelector('#active-universe-status');
    const result = await page.evaluate(() => ({
      sameExplorer: window.__sectorExplorerIdentity === document.getElementById('sector-explorer'),
      sameGrid: window.__sectorGridIdentity === document.getElementById('sector-grid'),
      focused: document.activeElement && document.activeElement.id,
      activeUniverse: document.getElementById('sector-explorer').dataset.activeUniverse,
      hash: location.hash,
      symbolStillMounted: !!document.querySelector('#sector-grid .sym-card[data-sym="SPY"]')
    }));
    assert.deepEqual(result, {
      sameExplorer: true,
      sameGrid: true,
      focused: 'active-universe-status',
      activeUniverse: 'INDEX',
      hash: '#/research',
      symbolStillMounted: true
    }, 'persisting market scope updates the mounted Explorer and focus in place');
  } finally {
    await page.unroute('**/api/universe', universeRoute);
    await page.evaluate(async restore => {
      delete window.__sectorExplorerIdentity;
      delete window.__sectorGridIdentity;
      App.state.world = restore.world;
      App.state.universe = restore.universe;
      App.state.explorerSector = restore.explorerSector;
      history.replaceState(null, '', '#/home');
      await App.render();
    }, prior);
  }
});

test('navigation is NEVER trapped behind a slow route (Research does not block moving away)', async () => {
  // Gate the active public-Research producer itself so it hangs; the mounted shell must paint
  // AND a different destination must supersede it without waiting for the provider.
  let release;
  const gate = new Promise(r => { release = r; });
  await page.route('**/api/research/AAPL', async route => {
    await gate;
    try { await route.abort(); } catch (e) { /* request abandoned when we navigated away */ }
  });
  try {
    await page.evaluate(() => { API.flushCache(); window.location.hash = '#/home'; });
    await page.waitForFunction(() => document.getElementById('app').getAttribute('data-route') === 'home');
    await page.evaluate(() => API.flushCache());
    await page.evaluate(() => { window.location.hash = '#/research/AAPL'; });
    await page.waitForSelector('#research-flow', { timeout: 4000 });
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

test('progressive Home applies its route layout before slow market fills finish', async () => {
  await page.evaluate(() => { window.location.hash = '#/research'; });
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  await page.evaluate(async () => {
    window.__homeAccount = await API.getFresh('/api/account');
    window.__homeGet = API.get;
    window.__homeGate = {};
    API.get = function (path) {
      if (String(path) === '/api/account') {
        return new Promise(resolve => { window.__homeGate.release = resolve; });
      }
      return window.__homeGet.apply(API, arguments);
    };
  });
  try {
    await page.evaluate(() => { API.flushCache(); window.location.hash = '#/home'; });
    await page.waitForSelector('#app[data-route="home"]');
    await page.waitForSelector('.home-hero');
    assert.equal(await page.getAttribute('#app', 'data-route'), 'home',
      'route identity commits with the shell, not after data finishes');
    assert.equal(await page.getAttribute('#app', 'data-ready'), 'false',
      'the assertion observes the progressive shell while its gated fill is still pending');
  } finally {
    await page.evaluate(() => {
      if (window.__homeGate && window.__homeGate.release) window.__homeGate.release(window.__homeAccount);
      if (window.__homeGet) API.get = window.__homeGet;
      delete window.__homeAccount;
      delete window.__homeGet;
      delete window.__homeGate;
    });
    await page.waitForSelector('#app[data-route="home"][data-ready="true"]', { timeout: 10000 });
  }
});

test('returning Home paints a stable desk while durable Plans reconcile', async () => {
  await page.evaluate(() => { window.location.hash = '#/research'; });
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  await page.evaluate(async () => {
    window.__homePlans = await API.getFresh('/api/plans');
    window.__homeGetFresh = API.getFresh;
    window.__homePlanGate = {};
    API.getFresh = function (path) {
      if (String(path) === '/api/plans') {
        return new Promise(resolve => { window.__homePlanGate.release = resolve; });
      }
      return window.__homeGetFresh.apply(API, arguments);
    };
  });
  try {
    await page.evaluate(() => { window.location.hash = '#/home'; });
    await page.waitForSelector('#app[data-route="home"] #home-restore-shell');
    assert.equal(await page.getAttribute('#app', 'data-ready'), 'false');
    assert.match(await page.textContent('#home-restore-shell'), /Your desk.*Reconciling your current Plans/s,
      'the route shows a truthful Home frame instead of a blank page or stale continuation');
  } finally {
    await page.evaluate(() => {
      if (window.__homePlanGate && window.__homePlanGate.release) {
        window.__homePlanGate.release(window.__homePlans);
      }
      if (window.__homeGetFresh) API.getFresh = window.__homeGetFresh;
      delete window.__homePlans;
      delete window.__homeGetFresh;
      delete window.__homePlanGate;
    });
    await page.waitForSelector('#app[data-route="home"][data-ready="true"]', { timeout: 10000 });
    assert.equal(await page.locator('#home-restore-shell').count(), 0,
      'the reconciliation frame leaves no duplicate hero behind');
  }
});


/** The futures stage lives inside Test your view now — select its pill before using #whatif-card. */
async function openFutures() {
  await openResearchTab('view');
  if (await page.locator('#tv-setup .choice-option.active').count() === 0) {
    await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  }
  await page.waitForSelector('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
  await page.waitForSelector('#tv-futures', { timeout: 15000 });
}

test('scenario studio: beginner view → decision facts → same-receipt strategy outcomes', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await openPlan('AAPL', 'understand', 'DIRECTIONAL', 'bearish', 'quarter');
  await openResearchTab('view');
  await page.waitForSelector('#tv-context');
  assert.match(await page.textContent('#tv-context'), /Down over 63 trading days/,
    'the declared view and horizon are quoted, never re-asked');
  await openFutures();
  // The thesis workbench: story cards with shape sketches, plain horizon/wildness chips.
  await page.waitForSelector('#whatif-card #sc-shapes .sc-card');
  assert.ok(await page.locator('#whatif-card .sc-card[data-shape="RALLY_FADE"].active').count(),
    'the bearish view seeds the generated-futures story instead of retired form state');
  assert.ok(await page.locator('#whatif-card #sc-horizon .sym-chip[data-days="63"].active').count(),
    'the shared horizon seeds the generated-futures horizon');
  assert.ok((await page.locator('#whatif-card .sc-card').count()) >= 6, 'story cards');
  assert.ok((await page.locator('#whatif-card .sc-sketch svg').count()) >= 6, 'shape sketches');
  assert.match(await page.textContent('#whatif-card'), /Drops, then recovers/);
  await page.waitForFunction(() => /±\d+%/.test(document.querySelector('#sc-mag-note')?.textContent || ''));
  assert.match(await page.textContent('#sc-mag-note'), /±\d+%/); // live magnitude preview
  await page.click('#whatif-card .sc-card[data-shape="SELLOFF_REBOUND"]');
  await page.fill('#whatif-target', '260');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .fan-chart svg', { timeout: 20000 });
  const decisionText = await page.textContent('#whatif-out');
  assert.match(decisionText, /Likely ending range/);
  assert.match(decisionText, /Middle 68% end between/);
  assert.match(decisionText, /Your price level/);
  assert.match(decisionText, /end (above|below)/);
  assert.match(decisionText, /touch it/);
  assert.match(decisionText, /OPTIONS MARKET IMPLIED/);
  assert.match(decisionText, /2,000 paths/);
  assert.match(decisionText, /reproducible run saved/i);
  assert.match(await page.textContent('#whatif-out'), /never a forecast/i);   // honesty note
  assert.ok(await page.locator('#whatif-reroll:visible').count(), 'sampling check replaces seed-shopping language');
  await page.click('#whatif-reroll');
  await page.waitForSelector('#whatif-out .alert', { timeout: 20000 });
  assert.match(await page.textContent('#whatif-out .alert'), /Sampling check|seed-sensitive/);

  // The fan belongs to the Plan. Its decision receipt remains plan-owned and the next action
  // advances the same durable Plan instead of teleporting to the old Trade workspace.
  const planFan = await page.evaluate(() => {
    const state = PlanStore.ui(App.state.activePlanId).evidence;
    return { planId: App.state.activePlanId,
      fingerprint: state.planEnsembleFingerprint };
  });
  assert.match(planFan.fingerprint, /^[a-f0-9]{24,}$/);
  assert.equal(await page.locator('#whatif-verify').count(), 0,
    'the old cross-route Outcomes handoff is absent inside a Plan');
  await page.click('#whatif-act');
  await page.waitForFunction((id) => location.hash === '#/plan/' + id + '/strategy', planFan.planId);
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  assert.match(await page.evaluate(() => location.hash), new RegExp('#/plan/' + planFan.planId + '/strategy$'));
  assert.match(await page.textContent('#plan-header'), /AAPL/);

  // Catalog identity remains server-owned while Strategy is migrated into the Plan.
  const catalogCheck = await page.evaluate(async () => {
    const server = await (await fetch('/api/strategies')).json();
    return {
      scenarioActual: Scenario.CATALOG.map(c => ({ key: c.key, label: c.label, group: c.group })),
      scenarioExpected: server.catalog.filter(c => c.scenarioEnabled)
        .map(c => ({ key: c.name, label: c.display, group: c.category })),
      builderActual: Builder.TEMPLATES.map(t => ({ key: t.key, family: t.family, name: t.name, group: t.group,
        shape: t.shape, blurb: t.blurb, risky: !!t.risky })),
      builderExpected: server.templates.map(t => ({ key: t.key, family: t.family, name: t.display, group: t.category,
        shape: t.payoffShape, blurb: t.summary, risky: !!t.blockedByDefault }))
    };
  });
  assert.deepEqual(catalogCheck.scenarioActual, catalogCheck.scenarioExpected,
    'Scenario must be a server-owned eligibility view, not a second catalog');
  assert.deepEqual(catalogCheck.builderActual, catalogCheck.builderExpected,
    'Builder metadata must be byte-for-byte server owned');
  const builderCheck = { keys: catalogCheck.builderActual.map(t => t.key) };
  for (const must of ['PMCC', 'SYNTHETIC_LONG', 'SYNTHETIC_SHORT', 'CALENDAR_CALL', 'DIAGONAL_CALL', 'IRON_CONDOR']) {
    assert.ok(builderCheck.keys.includes(must), 'builder catalog missing ' + must);
  }
});

test('scenario studio expert: model menu incl. Heston, IV knobs, the math on demand', async () => {
  await page.click('#level-switch button[data-level="expert"]');
  await openPlan('AAPL');
  await openFutures();
  await page.waitForSelector('#whatif-card #sc-model');
  const models = await page.$$eval('#sc-model .choice-option', os => os.map(o => o.dataset.value));
  assert.ok(models.includes('HESTON') && models.includes('BLOCK_BOOTSTRAP') && models.includes('JUMP_DIFFUSION'), 'full model menu');
  assert.ok(await page.locator('#sc-iv').count(), 'IV start knob');
  assert.ok(await page.locator('#sc-seed').count(), 'seed knob');
  // The math is an expandable (.xp), not in-your-face.
  await page.click('#whatif-card .xp-head');
  assert.match(await page.textContent('#whatif-card'), /Heston/);
  assert.match(await page.textContent('#whatif-card'), /κ|kappa/i);
});

test('scenario decision prices are isolated by symbol', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => { App.state.planUi = {}; });
  const aaplPlan = await openPlan('AAPL');
  await openFutures();
  await page.fill('#whatif-target', '270');
  await openPlan('QQQ');
  await openFutures();
  assert.equal(await page.inputValue('#whatif-target'), '', 'AAPL decision level never leaks into QQQ');
  await page.fill('#whatif-target', '500');
  await go('#/plan/' + aaplPlan.id + '/evidence');
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await openFutures();
  assert.equal(await page.inputValue('#whatif-target'), '270', 'AAPL decision level restores in its own symbol context');
});

test('scenario calibration names Demo history honestly', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await openPlan('AAPL');
  await openFutures();
  await page.waitForFunction(() => {
    const note = document.getElementById('sc-mag-note');
    return note && !/Loading volatility calibration/.test(note.textContent);
  });
  const note = await page.textContent('#sc-mag-note');
  assert.match(note, /fabricated Demo history|No eligible daily history/,
    'Demo history is never described as real or observed volatility');
  assert.doesNotMatch(note, /real volatility|observed recent history/,
    'generated history cannot borrow observed-market wording');
});

test('Evidence remains structure-less until Strategy selects a position', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => {
    App.context.update({ symbol: 'AAPL', goal: 'DIRECTIONAL', horizon: 'week', thesis: 'neutral' });
  });
  await openPlan('AAPL', 'understand', 'DIRECTIONAL', 'neutral', 'week');
  await page.evaluate(() => {
    Object.assign(PlanStore.ui(App.state.activePlanId).scenarioForm = {},
      { shape: 'CHOP', horizon: 5, mag: 'typical', seed: 55119 });
  });
  await openFutures();
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .scenario-decision', { timeout: 25000 });
  assert.equal(await page.locator('#whatif-out .scenario-position-outcome').count(), 0,
    'Evidence stays price-distribution-only before the Plan selects a position');
  assert.ok(await page.locator('#whatif-act').count(), 'the same Plan continues into Strategy');
});

test('data center: generate a scenario dataset, activate it (loud banner), switch back', async () => {
  await page.click('#level-switch button[data-level="expert"]');
  await go('#/data/datasets');
  await page.waitForSelector('#dc-datasets .status-item');
  assert.match(await page.textContent('#dc-datasets'), /Demo market baseline|Observed market data/);
  // Generate a synthetic run.
  await page.click('#dc-generate-btn');
  await page.waitForSelector('#dc-gen-sym');
  await page.fill('#dc-gen-sym', 'AAPL');
  await page.click('#dc-gen-run');
  await page.waitForSelector('#dc-datasets .status-item .badge:has-text("SYNTHETIC")', { timeout: 30000 });
  // Activate it → the loud app-wide banner appears.
  const useBtn = page.locator('#dc-datasets .status-item:has(.badge:has-text("SYNTHETIC")) button:has-text("Activate")').first();
  await useBtn.click();
  await page.waitForSelector('#scenario-banner', { timeout: 15000 });
  assert.match(await page.textContent('#scenario-banner'), /SCENARIO MODE/);
  // Switch back to observed → banner clears.
  await page.waitForSelector('#dc-datasets .status-item[data-dataset="observed"] button:has-text("Activate")');
  await page.click('#dc-datasets .status-item[data-dataset="observed"] button:has-text("Activate")');
  await page.waitForFunction(() => !document.getElementById('scenario-banner'), { timeout: 15000 });
  // Cleanup: leave the shared page as downstream tests expect (Beginner level, history verify mode).
  await page.click('#level-switch button[data-level="beginner"]');
});

test('dataset actions fail visibly instead of looking like dead buttons', async () => {
  await page.route('**/api/datasets', async route => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        active: 'observed', datasets: [
          { id: 'observed', name: 'Observed market data', bars: 0 },
          { id: 'ds_error_fixture', name: 'Failure-path scenario', symbol: 'AAPL', bars: 20 }
        ]
      }) });
    } else await route.continue();
  });
  await page.route('**/api/datasets/*', async route => {
    if (route.request().method() === 'DELETE') {
      await route.fulfill({ status: 422, contentType: 'application/json',
        body: JSON.stringify({ error: 'This dataset is still in use by a separate market.' }) });
    } else await route.continue();
  });
  try {
    await go('#/data/datasets');
    const remove = page.locator('#dc-datasets .status-item:has(.badge:has-text("SYNTHETIC")) button:has-text("Delete")').first();
    await remove.click();
    await page.waitForSelector('#dataset-action-error .alert');
    assert.match(await page.textContent('#dataset-action-error'), /still in use|could not be completed/i);
  } finally {
    await page.unroute('**/api/datasets');
    await page.unroute('**/api/datasets/*');
  }
});

test('Data job cancel and retry commands fail visibly and restore pending state', async () => {
  let status = 'RUNNING';
  await page.route('**/api/data/jobs', async route => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        jobs: [{ id: 'job_contract', kind: 'refresh_now', status, done: 0, total: 1, rowsWritten: 0 }]
      }) });
    } else {
      await route.continue();
    }
  });
  await page.route('**/api/data/jobs/job_contract/*', async route => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'POST' && /\/(cancel|retry)$/.test(url.pathname)) {
      await route.fulfill({ status: 409, contentType: 'application/json',
        body: JSON.stringify({ error: (url.pathname.endsWith('/cancel') ? 'Cancel' : 'Retry')
          + ' state changed before this command.' }) });
    } else {
      await route.continue();
    }
  });
  try {
    await go('#/data/sources');
    const cancel = page.locator('#dc-jobs button').filter({ hasText: 'Cancel' });
    await cancel.waitFor();
    await cancel.click();
    await page.waitForSelector('#toast-region .toast-error');
    assert.match(await page.textContent('#toast-region'), /Cancel state changed/);
    assert.equal(await cancel.isEnabled(), true, 'Cancel restores after a refused command');

    status = 'FAILED';
    await page.locator('#dc-jobs button').filter({ hasText: 'Refresh' }).click();
    const retry = page.locator('#dc-jobs button').filter({ hasText: 'Retry' });
    await retry.waitFor();
    await retry.click();
    await page.waitForFunction(() => /Retry state changed/.test(document.getElementById('toast-region')?.textContent || ''));
    assert.equal(await retry.isEnabled(), true, 'Retry restores after a refused command');
  } finally {
    await page.unroute('**/api/data/jobs/job_contract/*');
    await page.unroute('**/api/data/jobs');
  }
});

test('workspace continuity: forms, symbol, and route survive a full reload', async () => {
  await page.evaluate(() => App.context.update({
    symbol: 'AAPL', goal: 'ACQUIRE', horizon: 'quarter', thesis: 'neutral'
  }));
  const plan = await openPlan('AAPL', 'understand', 'ACQUIRE', 'neutral', 'quarter');
  await page.waitForSelector('#plan-flow');
  const durablePlanRoute = await page.evaluate(() => location.hash);
  if (!await page.locator('[data-band="view"] #plan-declare-view').isVisible()) {
    await page.click('#plan-edit-context');
  }
  await page.waitForSelector('[data-band="view"] #plan-declare-view');
  await page.locator('[data-band="view"] .choice-segmented .choice-option').filter({ hasText: 'Custom' }).click();
  await page.fill('#plan-horizon-days', '63');
  await page.fill('#plan-target-price', '245');
  await page.click('#plan-declare-view');
  await page.waitForFunction(() => /63 trading sessions/.test(document.getElementById('plan-header')?.textContent || ''));
  // Persist presentation state now (the interval/pagehide path does this in normal use).
  const beforeSave = await page.evaluate(async id => ({
    plan: await API.getFresh('/api/plans/' + id), snapshot: Workspace.snapshot()
  }), plan.id);
  assert.equal(beforeSave.plan.intent, 'ACQUIRE', 'the Plan preserves the immutable intent');
  assert.equal(beforeSave.plan.context.horizonDays, 63, 'the Plan owns the edited horizon');
  assert.equal(beforeSave.snapshot.context, undefined,
    'workspace presentation never duplicates the Plan’s financial context');
  await page.evaluate(() => Workspace.save());
  await page.waitForTimeout(1700); // let the debounced backend push land
  const storedWorkspace = await page.evaluate(() => API.getFresh('/api/workspace'));
  const rev = storedWorkspace.rev;
  assert.ok(rev >= 1, 'workspace persisted to the backend (rev ' + rev + ')');
  assert.equal(storedWorkspace.state.context, undefined,
    'the workspace backend stores navigation/presentation, not a second trading context');

  // Cold open with NO hash: the app resumes exactly where the user left off.
  await page.goto(WORKSPACE);
  await page.waitForSelector('#app[data-ready="true"]');
  assert.equal(await page.evaluate(() => window.location.hash), durablePlanRoute,
    'the durable Plan route restores without reconstructing a Research destination');
  await page.waitForSelector('#world-band[data-world="demo"]');
  assert.match(await page.textContent('#world-band'), /DEMO MARKET[\s\S]*Fabricated teaching data/,
    'same-lane reload must recreate the global data-honesty band');
  assert.equal(await page.evaluate(() => App.Market.world), 'demo', 'same-lane reload reconciles MarketStore');
  const restored = await page.evaluate(() => ({
    sym: App.context.symbol(),
    goal: App.context.goal(), horizon: App.context.horizon(), thesis: App.context.thesis()
  }));
  assert.equal(restored.sym, 'AAPL', 'the Plan symbol is the shared working context');
  assert.equal(restored.goal, 'ACQUIRE', 'goal survives alongside the symbol');
  assert.equal(restored.horizon, '63d', 'the durable context revision owns the horizon');
  assert.equal(restored.thesis, 'neutral', 'thesis survives alongside the symbol');
  assert.match(await page.textContent('#plan-header'), /Target.*\$245\.00/s,
    'the Plan-owned target survives the reload');
  assert.equal(await page.evaluate(() => App.state.activePlanId), plan.id);

  // An explicit hash always beats the saved route (bookmarks/links stay honest).
  await page.goto(WORKSPACE + '#/portfolio');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'portfolio');

  // Home offers one-tap re-entry into the working context.
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1')); // dashboard, not the tour
  await go('#/home');
  await page.waitForSelector('#home-context-line', { state: 'attached' });
  assert.match(await page.textContent('#home-context-line'), /AAPL.*Buy at a discount/i);
  await page.getByRole('button', { name: /Continue AAPL/ }).click();
  await page.waitForFunction(id => location.hash.includes('/plan/' + id + '/'), plan.id);
});

test('world transition: authoritative PUT bootstrap recovers a failed SSE-hint hydration', async () => {
  await go('#/home');
  const recovered = await page.evaluate(async () => {
    const target = App.state.world;
    const bootstrap = App.state.universe;
    const originalRevision = Number(App.state.worldRevision || 0);
    const nextRevision = originalRevision + 1;
    let rejectHint;
    const failedHint = new Promise((resolve, reject) => { rejectHint = reject; })
      .finally(() => { App._transitionTarget = null; App._transitionP = null; });
    App._transitionTarget = target;
    App._transitionP = failedHint;
    const fromPut = App.transitionWorld(target, bootstrap, nextRevision);
    rejectHint(new Error('synthetic hint hydration failure'));
    await fromPut;
    const result = {
      world: App.state.world,
      marketWorld: App.Market.world,
      status: App.state.transitionStatus,
      symbols: (App.state.universe.active.symbols || []).length
    };
    App.state.worldRevision = originalRevision;
    return result;
  });
  assert.equal(recovered.status, 'committed');
  assert.equal(recovered.marketWorld, recovered.world);
  assert.ok(recovered.symbols > 0, 'the authoritative bootstrap hydrated the lane');
});

test('missing saved scenario repairs to baseline with a visible explanation', async () => {
  pg.sql("INSERT INTO settings(k,v,updated_at) VALUES('active_world:local','sim_missing_browser_session',now()) "
    + "ON CONFLICT (k) DO UPDATE SET v=excluded.v,updated_at=excluded.updated_at");
  await page.reload();
  await page.waitForSelector('#app[data-ready="true"]');
  const notice = page.locator('#world-repair-notice');
  await notice.waitFor();
  assert.match(await notice.textContent(),
    /Saved market unavailable[\s\S]*sim_missing_browser_session[\s\S]*returned you to the Demo baseline[\s\S]*no Plan or accounting records were rewritten/,
    'a repaired cold boot names the lost scenario and the baseline that replaced it');
  const repaired = await page.evaluate(async () => ({
    server: (await API.getFresh('/api/world')).world,
    app: App.state.world,
    market: App.Market.world,
    simulated: document.body.classList.contains('in-sim-world')
  }));
  assert.deepEqual(repaired, { server: 'demo', app: 'demo', market: 'demo', simulated: false },
    'server, app, market store, and chrome agree after repair');
  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: 844 });
    const geometry = await notice.evaluate(node => {
      const box = node.getBoundingClientRect();
      return { page: document.documentElement.scrollWidth, viewport: innerWidth,
        left: box.left, right: box.right, width: box.width };
    });
    assert.ok(geometry.page <= geometry.viewport && geometry.left >= 0 && geometry.right <= geometry.viewport,
      width + 'px repair explanation stays fully on-screen: ' + JSON.stringify(geometry));
  }
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.getByRole('button', { name: 'Dismiss market repair notice', exact: true }).click();
  assert.equal(await notice.count(), 0, 'the persistent explanation is dismissible');
});

test('workspace hydration cannot select a market lane', async () => {
  const result = await page.evaluate(() => {
    const before = { app: App.state.world, market: App.Market.world };
    const other = before.app === 'demo' ? 'observed' : 'demo';
    Workspace.hydrate({ rev: 0, state: { v: 1, world: other, forms: {}, savedAt: Date.now() + 1000 } },
      'lane-ownership-audit');
    const after = { app: App.state.world, market: App.Market.world };
    Workspace.hydrate(null, 'local');
    return { before, after };
  });
  assert.deepEqual(result.after, result.before,
    'saved work cannot bypass the server-owned world transition contract');
});

test('world transition ignores an out-of-order revision even when it names another lane', async () => {
  const state = await page.evaluate(async () => {
    const before = App.state.world;
    const originalRev = Number(App.state.worldRevision || 0);
    const beforeRev = originalRev + 100;
    App.state.worldRevision = beforeRev;
    const staleTarget = before === 'observed' ? 'demo' : 'observed';
    await App.transitionWorld(staleTarget, App.state.universe, beforeRev - 1);
    const result = { before, after: App.state.world, revision: App.state.worldRevision };
    App.state.worldRevision = originalRev;
    return result;
  });
  assert.equal(state.after, state.before, 'stale cross-lane event cannot roll the UI backward');
  assert.ok(state.revision >= 10);
});

test('world transition reconciles an equal revision when client lane owners disagree', async () => {
  const state = await page.evaluate(async () => {
    const target = App.state.world;
    const originalRev = Number(App.state.worldRevision || 0);
    const originalMarketWorld = App.Market.world;
    const revision = originalRev + 20;
    App.state.worldRevision = revision;
    App.Market.world = target === 'demo' ? 'observed' : 'demo';
    await App.transitionWorld(target, App.state.universe, revision, App.state.worldRevisionEpoch);
    const result = { app: App.state.world, market: App.Market.world,
      status: App.state.transitionStatus, revision: App.state.worldRevision };
    App.state.worldRevision = originalRev;
    App.Market.world = originalMarketWorld;
    return result;
  });
  assert.equal(state.app, state.market, 'equal revision repairs the stale market store');
  assert.equal(state.status, 'committed');
});

test('world revisions reset across a server boot epoch without accepting old-process events', async () => {
  const state = await page.evaluate(async () => {
    const before = App.state.world;
    const bootstrap = App.state.universe;
    const oldEpoch = App.state.worldRevisionEpoch || 'boot-a';
    const oldRevision = Number(App.state.worldRevision || 0);
    const oldRetired = (App.state.worldRevisionRetiredEpochs || []).slice();
    App.state.worldRevisionEpoch = oldEpoch;
    App.state.worldRevision = 500;
    await App.transitionWorld(before, bootstrap, 1, oldEpoch + '-restart');
    const accepted = { world: App.state.world, revision: App.state.worldRevision,
      epoch: App.state.worldRevisionEpoch };
    await App.transitionWorld(before === 'demo' ? 'observed' : 'demo', bootstrap, 999, oldEpoch);
    const afterOld = App.state.world;
    App.state.worldRevisionEpoch = oldEpoch;
    App.state.worldRevision = oldRevision;
    App.state.worldRevisionRetiredEpochs = oldRetired;
    return { before, accepted, afterOld };
  });
  assert.equal(state.accepted.world, state.before);
  assert.equal(state.accepted.revision, 1, 'new process revision 1 is accepted after revision 500');
  assert.match(state.accepted.epoch, /-restart$/);
  assert.equal(state.afterOld, state.before, 'a delayed event from the retired process is ignored');
  const chronological = await page.evaluate(async () => {
    const before = App.state.world;
    const bootstrap = App.state.universe;
    const priorEpoch = App.state.worldRevisionEpoch;
    const priorRevision = App.state.worldRevision;
    App.state.worldRevisionEpoch = '2026-07-11T12:00:00Z';
    App.state.worldRevision = 4;
    await App.transitionWorld(before === 'demo' ? 'observed' : 'demo', bootstrap, 999,
      '2026-07-10T12:00:00Z');
    const after = App.state.world;
    App.state.worldRevisionEpoch = priorEpoch;
    App.state.worldRevision = priorRevision;
    return { before, after };
  });
  assert.equal(chronological.after, chronological.before,
    'an unseen but chronologically older server epoch cannot roll the market lane backward');
});

test('every scenario story runs at both levels (the Big-news-shock crash class)', async () => {
  // The magVolFor crash escaped a green matrix because tests only clicked ONE story.
  // Run EVERY beginner story card end-to-end, then every expert shape via the select.
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => App.context.update({ symbol: 'AAPL', thesis: 'neutral', horizon: 'month' }));
  await openPlan('AAPL', 'understand', 'DIRECTIONAL', 'neutral', 'month');
  await openFutures();
  await page.waitForSelector('#whatif-card #sc-shapes .sc-card');
  await assertNamedControls('#whatif-card');
  const shapes = await page.$$eval('#whatif-card #sc-shapes .sc-card', cs => cs.map(c => c.getAttribute('data-shape')));
  assert.ok(shapes.length >= 7, 'all story cards render');
  for (const shape of shapes) {
    await page.click('#whatif-card #sc-shapes .sc-card[data-shape="' + shape + '"]');
    await page.click('#whatif-run');
    await page.waitForSelector('#whatif-out .scenario-decision', { timeout: 30000 });
    assert.match(await page.textContent('#whatif-out'), /Likely ending range/, shape + ' produced a decision-grade range');
  }
  // Expert: every model prices without error on one story.
  await page.click('#level-switch button[data-level="expert"]');
  await openFutures();
  await page.waitForSelector('#whatif-card #sc-model');
  await assertNamedControls('#whatif-card');
  for (const model of ['GBM', 'STUDENT_T', 'JUMP_DIFFUSION', 'HESTON', 'BLOCK_BOOTSTRAP', 'BROWNIAN_BRIDGE']) {
    await page.click('#whatif-card #sc-model .choice-option[data-value="' + model + '"]');
    await page.click('#whatif-run');
    await page.waitForSelector('#whatif-out .scenario-decision', { timeout: 30000 });
  }
  await page.click('#level-switch button[data-level="beginner"]');
});

test('research symbol page: ONE Test-your-view section — thesis-driven, symbol-inherited, keyed results', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  const researchPlan = await openPlan('AAPL');
  assert.equal(await page.locator('#history-card').count(), 0,
    'the flow leaves the market chart to the public research page — evidence inherits without duplicating');
  await openResearchTab('view');
  await page.waitForSelector('#test-your-view');
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  // The stage selection PERSISTS by design — pick Past evidence explicitly for this walk.
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  // Evidence is its own Plan stage: it inherits the symbol without duplicating Understand's chart.
  const layout = await page.evaluate(() => ({
    historyCards: document.querySelectorAll('#history-card').length,
    orphanCards: document.querySelectorAll('#test-your-view ~ #whatif-card, .card > #what-has-happened').length,
    nestedSymbolInputs: document.querySelectorAll('#test-your-view input[list="universe-symbols"]').length
  }));
  assert.equal(layout.historyCards, 0, 'Evidence does not duplicate Understand content');
  assert.equal(layout.nestedSymbolInputs, 0, 'the evidence stage INHERITS the page symbol — no nested ticker input');
  // Both stages are visible as connected pills; Past evidence expands by default.
  assert.equal(await page.locator('#research-outcomes-nav .outcome-basis').count(), 2, 'two connected outcome bases');
  await page.waitForSelector('#what-has-happened');
  await assertNamedControls('#test-your-view');
  await assertTabContracts('#test-your-view');
  // The thesis question line names the CONDITION — "did this happen before" never leaves "this" undefined.
  await page.waitForSelector('#tv-question-line');
  assert.ok(await page.locator('#study-window:visible').count(), 'beginner controls the history window');
  assert.ok(await page.locator('#study-strictness:visible').count(), 'beginner controls signal selectivity');
  assert.ok(await page.locator('#study-regime:visible').count(), 'beginner controls market regime');
  assert.ok(await page.locator('#study-bootstrap').count(),
    'full statistical capability remains mounted behind progressive disclosure');
  const beforeStrict = await page.inputValue('#study-param-dropPct');
  await page.locator('#study-strictness .choice-option').filter({ hasText: 'Only the strongest' }).click();
  assert.notEqual(await page.inputValue('#study-param-dropPct'), beforeStrict,
    'plain-language selectivity changes the real signal threshold');
  assert.match(await page.textContent('#study-strictness .control-consequence'),
    /below its \d+-day high|drop|threshold|streak|lookback/i,
    'the selectivity control states the concrete trigger it wrote');
  assert.equal(await page.locator('#what-has-happened select, .study-protocol-controls select').count(), 0,
    'no listbox remains in the study protocol panel');
  // Run the study; the conclusion is decision-useful (evidence strength + confidence guidance + handoff).
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  const outText = await page.textContent('#study-results');
  assert.match(outText, /Evidence/, 'evidence-strength label present');
  assert.match(outText, /raise or lower your confidence/, 'confidence guidance, never a prediction');
  const beginnerStats = page.locator('#study-results .xp-head').filter({ hasText: 'See the exact statistics' });
  assert.equal(await beginnerStats.count(), 1,
    'Beginner keeps the exact statistical diagnostics behind progressive disclosure');
  await beginnerStats.click();
  assert.match(await page.textContent('#study-results .study-exact-statistics'),
    /Overlap z screen.*CI \(avg\).*Effect size.*Baseline avg.*Clears z screen/s,
    'Beginner can inspect the same exact diagnostics as Expert');
  // The handoff exists when enough analogs matched.
  const handoff = await page.locator('#tv-test-analogs').count();
  if (handoff) {
    assert.match(await page.textContent('#tv-test-analogs'), /DEMO-data occurrences/); // fixture suite: never 'real'
    const hashBeforeAnalogs = await page.evaluate(() => location.hash);
    await page.click('#tv-test-analogs');
    await page.waitForFunction(prev => location.hash !== prev && location.hash.endsWith('/strategy'),
      hashBeforeAnalogs, { timeout: 15000 });
    await page.waitForSelector('#plan-stage-strategy');
    const analogSelection = await page.evaluate(() =>
      PlanStore.ui(App.state.activePlanId).evidence.analogSelection);
    assert.ok(analogSelection && analogSelection.events > 0 && analogSelection.studyKey,
      'the exact analog selection stays attached to the Plan for Strategy and Outcomes');
    await go('#/plan/' + researchPlan.id + '/evidence');
    await openResearchTab('view');
    await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
    await page.waitForSelector('#study-results .alert');
  }
  assert.match(await page.textContent('#study-results'), /How this evidence was checked/);

  // Expert sees the SAME engine and every protocol knob, not a reduced or separate tool.
  await page.click('#level-switch button[data-level="expert"]');
  await openResearchTab('view');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  assert.ok(await page.locator('.study-protocol[open] #study-bootstrap:visible').count(),
    'expert protocol opens with bootstrap, dates, dependence, confidence and multiplicity controls');
  await page.locator('#study-confidence .choice-option').filter({ hasText: '99' }).click();
  await page.locator('#study-multiplicity .choice-option').filter({ hasText: 'Exploratory' }).click();
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  assert.match(await page.textContent('#study-results'), /99% CI \(avg\)/);
  assert.match(await page.textContent('#study-results'), /Exploratory unadjusted significance/);
  // KEYED RESULTS: switching to QQQ must not display AAPL's study.
  await openPlan('QQQ');
  await openResearchTab('view');
  await page.waitForSelector('#test-your-view');
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  assert.equal(await page.locator('#study-results .alert').count(), 0,
    'an AAPL result may never appear on a QQQ page (result identity is keyed)');
  // Possible futures is the second stage of the SAME section.
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
  await page.waitForSelector('#tv-futures #whatif-card, #tv-futures #sc-shapes', { timeout: 15000 });
  await assertNamedControls('#test-your-view');
  await assertTabContracts('#test-your-view');
});

test('Research Coming up never relabels a past expiration as 0d', async () => {
  await page.route('**/api/research/AAPL/expirations', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ symbol: 'AAPL', asOfDate: '2026-07-12',
      expirations: ['2026-07-10', '2026-07-13', '2026-07-17'] })
  }));
  await page.evaluate(() => API.invalidate(['/api/research/AAPL/expirations']));
  await openPlan('AAPL');
  await openResearchTab('overview');
  await page.waitForSelector('#events-card:has-text("2026-07-13")');
  const events = await page.textContent('#events-card');
  assert.doesNotMatch(events, /2026-07-10/, 'past contracts are absent from Coming up');
  assert.match(await page.locator('#events-card .event-timeline-item:has-text("2026-07-13")').textContent(),
    /1 calendar day away/, 'DTE follows the lane date, not the browser timezone');
  await page.unroute('**/api/research/AAPL/expirations');
  await page.evaluate(() => API.invalidate(['/api/research/AAPL/expirations']));
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
  await openPlan('AAPL');
  await page.waitForSelector('#plan-flow');
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

test('universe Scout starts distinct Plans from the current Research market', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/research');
  await page.waitForSelector('#research-plan-scout');
  assert.equal(await page.evaluate(() => {
    const scout = document.getElementById('research-plan-scout');
    const sectors = document.getElementById('sector-explorer');
    return !!(scout.compareDocumentPosition(sectors) & Node.DOCUMENT_POSITION_FOLLOWING);
  }), true, 'Universe Scout appears before the ticker grid instead of below the entire Research index');
  assert.match(await page.textContent('#research-plan-scout'), /when you do not already have a ticker/i);
  await captureSettled('trader-own-p15-research-scout-desktop.png');
  await page.setViewportSize({ width: 390, height: 844 });
  await captureSettled('trader-own-p15-research-scout-mobile.png');
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.click('#universe-scout-intent .choice-option[data-value="DIRECTIONAL"]');
  await page.click('#universe-scout-horizon .choice-option[data-value="month"]');
  await page.click('#universe-scout-risk .choice-option[data-value="balanced"]');
  await page.click('#universe-scout-run');
  await page.waitForSelector('#universe-scout-results .universe-scout-pick', { timeout: 30000 });
  const picks = await page.locator('#universe-scout-results .universe-scout-pick').count();
  assert.ok(picks >= 1 && picks <= 5, 'Scout returns a short market field, got ' + picks);
  const text = await page.textContent('#universe-scout-results');
  assert.match(text, /Signal confidence/);
  assert.match(text, /Leading expression/);
  assert.ok(await page.locator('#universe-scout-results [data-economic-verdict]').count() >= 1,
    'universe Scout uses the same economic classification as single-symbol ranking');
  const first = page.locator('#universe-scout-results .universe-scout-pick').first();
  assert.equal(await first.locator(':scope > .btn-row .btn').count(), 1,
    'a Scout recommendation has exactly one workflow CTA into Plan Strategy');
  const symbol = await first.getAttribute('data-symbol');
  await first.getByRole('button', { name: /Use this in a Plan|Open .* in Strategy/ }).click();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  assert.match(await page.evaluate(() => location.hash), /\/strategy$/,
    'a leading expression lands at Strategy rather than dropping back at Understand');
  assert.match(await page.textContent('#plan-header'), new RegExp(symbol));
  const linked = await page.evaluate(async () => {
    const plan = PlanStore.active();
    const latest = await API.getFresh('/api/plans/' + plan.id + '/strategy/latest');
    return { selected: latest.selected, intent: plan.intent };
  });
  assert.ok(linked.selected && linked.selected.legs && linked.selected.legs.length,
    'the Scout leading expression is the exact package selected in the destination Plan');
});

test('beginner help adds information instead of echoing visible labels', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'strategy');
  await page.waitForSelector('#plan-strategy-body');
  const duplicates = [];
  for (const hash of ['#/home', '#/plan/' + plan.id + '/strategy', '#/data/overview']) {
    await go(hash);
    const found = await page.$$eval('[title]', nodes => nodes.filter(node => {
      if (!node.offsetParent) return false;
      const norm = value => String(value || '').replace(/\s+/g, ' ').trim().toLowerCase();
      const title = norm(node.getAttribute('title'));
      const visible = norm(node.innerText || node.textContent);
      return title.length > 1 && (visible === title || (title.length > 18 && visible.includes(title)));
    }).map(node => ({ route: location.hash, tag: node.tagName, text: node.innerText, title: node.title })));
    duplicates.push(...found);
  }
  assert.deepEqual(duplicates, [], 'native hover text repeats visible Beginner copy: ' + JSON.stringify(duplicates));

  await go('#/plan/' + plan.id + '/strategy');
  await page.waitForSelector('.plan-tool-selector');
  assert.equal(await page.locator('.plan-tool[title]').count(), 0,
    'Beginner Strategy tools explain themselves visibly instead of duplicating copy in hover text');
  assert.equal(await page.locator('#plan-strategy-filters label[title]').count(), 0,
    'plain filter labels have no competing native tooltip');
  await page.locator('#plan-strategy-filters .xp-head').click();
  assert.ok(await page.locator('#plan-strategy-filters .info-trigger').count() >= 1,
    'technical filter concepts retain visible, registry-backed help');
});

test('portfolio headline: total value + P/L at current marks', async () => {
  await go('#/portfolio/positions');
  await page.waitForSelector('#pf-stats .stat');
  await assertTabContracts('#app');
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
  await page.getByRole('tab', { name: 'Positions', exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  await page.waitForURL(/#\/portfolio\/activity$/);
  assert.equal(await page.getByRole('tab', { name: 'Activity', exact: true }).getAttribute('aria-selected'), 'true',
    'paper-account tabs use the shared roving keyboard contract');
});

test('Beginner keeps exact book sensitivity through progressive disclosure', async () => {
  await page.route('**/api/portfolio/greeks', route => route.fulfill({ contentType: 'application/json',
    body: JSON.stringify({ positions: [{ id: 'sensitivity-contract' }], deltaShares: 14.2,
      gammaShares: 1.1, thetaPerDay: -3.25, vegaPerPoint: 7.5, complete: true }) }));
  await page.route('**/api/portfolio/heat', route => route.fulfill({ contentType: 'application/json',
    body: JSON.stringify({ activeTrades: 1, totalMaxLossCents: 125000, shortVolTrades: 1,
      concentrationPct: 32, earlyAssignmentLiquidityCents: 0, physicalAssignmentCashCents: 0,
      postPhysicalAssignmentBuyingPowerCents: 0 }) }));
  await page.evaluate(() => {
    Learn.setLevel('beginner');
    API.invalidate(['/api/portfolio/greeks', '/api/portfolio/heat']);
  });
  await go('#/portfolio/positions');
  await page.waitForSelector('#portfolio-greeks');
  assert.match(await page.textContent('#portfolio-greeks'), /How your open positions react/);
  await page.locator('#portfolio-greeks .xp-head').click();
  assert.match(await page.textContent('#portfolio-greeks'), /Book greeks.*Net delta.*Vega \/ vol pt.*Book heat.*Theoretical max loss/s,
    'Beginner sees the primary sensitivities inline and can inspect the same exact book heat');
  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#portfolio-greeks');
  assert.match(await page.textContent('#portfolio-greeks'), /Book greeks.*Net delta/s,
    'Expert keeps the dense inline presentation');
  await page.unroute('**/api/portfolio/greeks');
  await page.unroute('**/api/portfolio/heat');
  await page.evaluate(() => Learn.setLevel('beginner'));
});

test('share holdings remain a priced Plan input instead of an orphan portfolio action', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/portfolio/positions');
  await page.waitForSelector('#holdings-card');
  const beforeShares = await page.evaluate(async () => {
    const positions = (await API.get('/api/positions')).positions || [];
    const row = positions.find(position => position.symbol === 'AAPL');
    return row ? Number(row.shares) : 0;
  });
  await page.click('#buy-shares-btn');
  await page.fill('#stock-symbol', 'AAPL');
  await page.fill('#stock-shares', '100');
  await page.waitForSelector('#stock-order-preview:has-text("Estimated cash outlay")', { timeout: 15000 });
  assert.match(await page.textContent('#stock-order-preview'),
    /Estimated cash outlay[\s\S]*Executable ask \/ share[\s\S]*Buying power after/,
    'the money-moving share action previews executable price and account consequence');
  await page.click('#modal-confirm');
  await page.waitForSelector('#modal-root [role="dialog"]', { state: 'detached' });
  await page.waitForSelector('#holdings-card tbody tr:has-text("AAPL")', { timeout: 15000 });
  const afterShares = await page.evaluate(async () => {
    const positions = (await API.getFresh('/api/positions')).positions || [];
    const row = positions.find(position => position.symbol === 'AAPL');
    return row ? Number(row.shares) : 0;
  });
  assert.equal(afterShares, beforeShares + 100, 'the confirmed order adds exactly 100 tracked shares');
  assert.match(await page.textContent('#holdings-card tbody tr:has-text("AAPL")'),
    new RegExp('AAPL[\\s\\S]*' + afterShares));

  const plan = await openPlan('AAPL', 'strategy', 'EXIT', 'neutral');
  await page.waitForSelector('#plan-intent-ladder', { timeout: 30000 });
  assert.match(await page.textContent('#plan-intent-ladder'), /Name your sale price[\s\S]*Chance you sell/,
    'held shares feed the goal-native covered-call ladder inside the Plan');
  const firstRung = page.locator('#plan-intent-ladder .ladder-row').first();
  await firstRung.locator('.xp-head').click();
  assert.match(await firstRung.textContent(), new RegExp('USES ' + afterShares + ' HELD SHARES', 'i'),
    'the proposed package names the collateral it consumes');

  await page.evaluate(async id => {
    const live = await PlanStore.get(id, true);
    await API.post('/api/plans/' + id + '/archive', { expectedVersion: live.version });
    await PlanStore.refresh();
  }, plan.id);
  await go('#/portfolio/positions');
  const row = page.locator('#holdings-card tbody tr:has-text("AAPL")');
  await row.getByRole('button', { name: 'Sell', exact: true }).click();
  await page.waitForSelector('#stock-order-preview:has-text("Estimated proceeds")', { timeout: 15000 });
  await page.click('#modal-confirm');
  await page.waitForSelector('#modal-root [role="dialog"]', { state: 'detached' });
  await page.waitForFunction(() => !Array.from(document.querySelectorAll('#holdings-card tbody tr'))
    .some(row => /AAPL/.test(row.textContent || '')), null, { timeout: 15000 });
});

test('portfolio absorbs account: sections, ledger under Activity, guarded reset', async () => {
  await go('#/portfolio/account');
  const text = await page.textContent('#app');
  assert.match(text, /Buying power/);
  assert.match(text, /Reset account/);
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'portfolio');
  await go('#/portfolio/activity');
  const ledger = await page.textContent('#app');
  assert.match(ledger, /Ledger.*append-only.*Date.*Type.*Amount.*Cash after.*Broker reserve after/s,
    'Activity owns the append-only account ledger and its accounting columns');
  assert.ok(await page.locator('#app table tbody tr').count() > 0,
    'Activity shows durable ledger entries without depending on which oldest event fits on page one');
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

test('tracked portfolios preserve external accounting, performance, tax, exports, and mobile navigation', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/portfolio/book/overview');
  assert.equal(await page.locator('#route-error').count(), 0, 'the canonical tracked-account route is reachable');
  await assertTabContracts('#app');
  if (!(await page.locator('.portfolio-book-start').count())) {
    // A tracked account already exists (the broker-promotion journey created one earlier);
    // the Book then offers new accounts through settings instead of the first-run form.
    await page.evaluate(() => { App.state.portfolioBookNew = true; });
    await go('#/portfolio/book/settings');
    await page.waitForSelector('.book-new-account');
  }
  await page.getByRole('textbox', { name: 'Account name', exact: true }).first().fill('Tracked taxable test');
  await page.getByRole('spinbutton', { name: 'Opening cash $', exact: true }).first().fill('100000');
  await page.getByRole('button', { name: 'Create tracked account', exact: true }).first().click();
  await page.waitForSelector('.portfolio-book-context:has-text("Tracked taxable test")');
  await page.waitForSelector('.book-summary-stats');
  assert.match(await page.textContent('#app'), /Cash in this book[\s\S]*\$100,000\.00/,
    'tracked opening cash is visible in its own book');

  await page.getByRole('tab', { name: 'Overview', exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  await page.waitForURL(/#\/portfolio\/book\/risk$/);
  assert.equal(await page.getByRole('tab', { name: 'Book risk', exact: true }).getAttribute('aria-selected'), 'true',
    'the Book sections use the shared roving keyboard contract');
  await page.getByRole('tab', { name: 'Activity', exact: true }).click();
  await page.waitForSelector('.book-activity-tabs');
  await page.getByRole('tab', { name: 'Record activity', exact: true }).click();
  await page.waitForSelector('.book-record-card');
  assert.equal(await page.getByRole('tab', { name: 'Activity', exact: true }).getAttribute('aria-selected'), 'true',
    'tracked-account tabs use the shared roving keyboard contract');
  await assertNamedControls('#app');
  assert.equal(await page.getByRole('button', { name: 'Tax contract classification', exact: true }).count(), 0,
    'Beginner records broker facts without a tax-contract override control');
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'expert'
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.waitForSelector('.book-shared-position-editor .position-editor');
  await page.getByRole('button', { name: 'Visual', exact: true }).click();
  await page.getByRole('button', { name: 'Tax contract classification', exact: true }).click();
  assert.match(await page.textContent('.book-record-card'),
    /Other Section 1256 contract[\s\S]*SPX, SPXW, SPXpm, XSP, NDX, NDXP, VIX, VIXW, RUT, RUTW, DJX, OEX, XEO/,
    'Expert retains the exceptional manual override and names the automatic taxonomy');
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'beginner'
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.waitForSelector('.book-record-card');
  await page.getByRole('combobox', { name: 'What happened?', exact: true }).selectOption('ASSIGNMENT');
  await page.waitForTimeout(50);
  const assignmentEditorState = await page.evaluate(() => ({
    event: document.querySelector('#portfolio-book-event')?.value,
    rows: document.querySelectorAll('#portfolio-book-legs .book-leg-row').length,
    hidden: document.querySelector('#portfolio-book-legs')?.hidden,
    guidance: document.querySelector('.book-event-guidance')?.textContent,
    symbolLabels: Array.from(document.querySelectorAll('#portfolio-book-legs .book-leg-row input[type="text"]'))
      .map(input => input.id && document.querySelector('label[for="' + input.id + '"]')?.textContent.trim())
  }));
  assert.equal(assignmentEditorState.rows, 2,
    'assignment builds its option and resulting-share rows: ' + JSON.stringify(assignmentEditorState));
  assert.deepEqual(assignmentEditorState.symbolLabels, ['Symbol', 'Symbol'],
    'both assignment legs retain explicit accessible symbol labels');
  assert.match(await page.textContent('.book-event-guidance'),
    /broad-based Section 1256 index options are cash-settled[\s\S]*closing option transaction/i,
    'physical-delivery entry tells users how to record an index cash settlement instead');
  const assignmentOption = page.locator('#portfolio-book-legs .book-leg-row').first();
  await assignmentOption.locator('input[type="text"]').first().fill('AAPL');
  await assignmentOption.locator('input[type="number"][step="0.01"]').fill('250');
  await page.getByRole('combobox', { name: 'What happened?', exact: true }).selectOption('EXPIRATION');
  await page.getByRole('combobox', { name: 'What happened?', exact: true }).selectOption('ASSIGNMENT');
  assert.equal(await page.locator('#portfolio-book-legs .book-leg-row').first()
    .locator('input[type="text"]').first().inputValue(), 'AAPL',
  'changing activity type preserves the assignment legs instead of erasing entered broker facts');
  assert.equal(await page.locator('#portfolio-book-legs .book-leg-row').first()
    .locator('input[type="number"][step="0.01"]').inputValue(), '250',
  'returning to an activity type restores its exact contract draft');
  await page.getByRole('combobox', { name: 'What happened?', exact: true }).selectOption('TRADE');
  await page.waitForSelector('.book-shared-position-editor .position-editor');
  await page.getByRole('textbox', { name: 'When it happened', exact: true }).fill('2026-07-15T10:30');
  await page.getByRole('button', { name: 'Record factual activity', exact: true }).click();
  await page.waitForSelector('.book-shared-position-editor .position-editor-result:has-text("RECORD requires")');
  assert.match(await page.textContent('.book-shared-position-editor .position-editor-result'),
    /RECORD requires every factual symbol, quantity, multiplier, contract, and positive fill price/,
    'the shared editor refuses to manufacture missing tracked-book facts');
  const leg = page.locator('.book-shared-position-editor .position-leg').first();
  await leg.getByRole('combobox', { name: 'Instrument', exact: true }).selectOption('STOCK');
  await leg.getByRole('textbox', { name: 'Symbol', exact: true }).fill('AAPL');
  await leg.getByRole('spinbutton', { name: 'Shares', exact: true }).fill('10');
  await leg.getByRole('spinbutton', { name: 'Exact fill $', exact: true }).fill('100');
  await page.getByRole('spinbutton', { name: 'Total fees $', exact: true }).fill('1');
  assert.equal(await page.getByRole('combobox', { name: 'Record source', exact: true }).count(), 0,
    'record source is derived instead of restated at Beginner');
  await page.getByRole('textbox', { name: 'Broker reference', exact: true }).fill('dom-stock-open-1');
  await page.getByRole('button', { name: 'Record factual activity', exact: true }).click();
  await page.waitForSelector('.book-shared-position-editor .position-editor-result:has-text("Recorded in")');
  assert.match(await page.locator('.book-shared-position-editor .position-editor-result').innerText(),
    /BOOK AFTER THIS RECORD[\s\S]*Realized P\/L[\s\S]*Unrealized P\/L[\s\S]*Cash in this book/i,
  'recording answers with refreshed profit and cash beside the exact trade editor');
  await page.getByRole('tab', { name: 'Overview', exact: true }).click();
  await page.getByRole('tab', { name: 'Activity', exact: true }).click();
  await page.getByRole('tab', { name: 'History', exact: true }).click();
  await page.waitForFunction(() => /2 shown/.test(document.querySelector('.book-journal')?.textContent || ''))
    .catch(async error => {
      throw new Error('Recorded stock did not reconcile into the journal: '
        + (await page.locator('.book-shared-position-editor').textContent().catch(() => '<editor missing>'))
        + '\nJOURNAL: ' + (await page.locator('.book-journal').textContent().catch(() => '<journal missing>'))
        + '\n' + error.message);
    });

  await page.getByRole('tab', { name: 'Record activity', exact: true }).click();
  await page.getByRole('combobox', { name: 'What happened?', exact: true }).selectOption('INTEREST');
  await page.getByRole('spinbutton', { name: 'Amount $', exact: true }).fill('5');
  await page.getByRole('textbox', { name: 'Broker reference', exact: true }).fill('dom-interest-1');
  await page.getByRole('button', { name: 'Record activity', exact: true }).click();
  await page.waitForSelector('.book-record-result:has-text("Activity recorded")');
  assert.match(await page.locator('.book-record-result').innerText(),
    /Realized P\/L[\s\S]*Unrealized P\/L[\s\S]*Cash in this book[\s\S]*\$99,004\.00/i,
  'cash activity reports its refreshed book consequence beside the command without repainting the page');
  await page.getByRole('tab', { name: 'Overview', exact: true }).click();
  await page.getByRole('tab', { name: 'Activity', exact: true }).click();
  await page.getByRole('tab', { name: 'History', exact: true }).click();
  await page.waitForFunction(() => /3 shown/.test(document.querySelector('.book-journal')?.textContent || ''))
    .catch(async error => {
      throw new Error('Interest did not reconcile into the journal: '
        + (await page.locator('.book-record-card').textContent().catch(() => '<record card missing>'))
        + '\nJOURNAL: ' + (await page.locator('.book-journal').textContent().catch(() => '<journal missing>'))
        + '\n' + error.message);
    });
  assert.match(await page.textContent('.book-journal'), /interest[\s\S]*\+\$5\.00/i,
    'cash income is a visible normalized transaction');

  const trackedId = await page.evaluate(() => App.state.portfolioBookAccountId);
  await page.getByRole('tab', { name: 'Overview', exact: true }).click();
  await page.waitForSelector('.book-positions');
  const overview = await page.textContent('#app');
  assert.match(overview, /AAPL shares[\s\S]*You own 10 shares[\s\S]*Cost basis[\s\S]*\$1,001\.00/,
    'stock basis includes the exact opening fee');
  assert.match(overview, /Cash in this book[\s\S]*\$99,004\.00/,
    'tracked cash includes the stock fill, fee, and interest without touching practice cash');
  assert.match(overview, /Current value is partial[\s\S]*Total value[\s\S]*Unavailable/,
    'the fixture market cannot manufacture a current value for an external tracked account');
  assert.match(overview, /does not turn missing prices into zero or use Demo, simulated, or modeled prices/,
    'the lane boundary is explained where the missing mark affects the total');
  assert.match(overview, /By asset class[\s\S]*By sector[\s\S]*Long and short/,
    'allocation exposes all three accounting lenses even while unavailable marks remain excluded');
  await captureSettled('p110-accounting-overview-desktop.png');

  const rollOpen = await page.evaluate(async id => API.post('/api/portfolio/accounts/' + id + '/transactions', {
    occurredAt: new Date(Date.now() - 5000).toISOString(), eventType: 'TRADE', fillNature: 'EXECUTED', cashAmountCents: null,
    feesCents: 100, taxCategory: null, source: 'MANUAL', externalRef: 'dom-roll-open-1',
    notes: 'Position to exercise the linked roll control', legs: [{
      instrumentType: 'OPTION', action: 'BUY', positionEffect: 'OPEN', symbol: 'QQQ', optionType: 'CALL',
      strike: 500, expiration: '2027-06-18', quantity: 1, multiplier: 100, price: 5, section1256: false
    }]
  }), trackedId);
  assert.equal(rollOpen.cashEffectCents, -50100, 'the option opening and fee post in exact cents');
  await go('#/portfolio/book/overview');
  await page.getByRole('button', { name: 'Roll position', exact: true }).click();
  await page.waitForSelector('.book-shared-position-editor .position-editor:has-text("Roll QQQ")');
  const rollEditor = page.locator('.book-shared-position-editor .position-editor');
  assert.equal(await page.getByRole('dialog').count(), 0,
    'Overview routes into the canonical Activity workbench instead of opening another roll UI');
  assert.equal(await rollEditor.getByRole('checkbox', { name: /Section 1256 contract/ }).count(), 0,
    'the linked roll preserves classification without exposing a Beginner tax override');
  const closeLeg = rollEditor.locator('.position-leg').nth(0);
  const replacementLeg = rollEditor.locator('.position-leg').nth(1);
  await closeLeg.getByRole('spinbutton', { name: 'Exact fill $', exact: true }).fill('7');
  await replacementLeg.getByRole('combobox', { name: 'Expiration', exact: true }).selectOption('__custom__');
  await replacementLeg.getByRole('textbox', { name: 'Unlisted expiration', exact: true }).fill('2027-09-17');
  await replacementLeg.getByRole('combobox', { name: 'Strike $', exact: true }).selectOption('__custom__');
  await replacementLeg.getByRole('spinbutton', { name: 'Unlisted strike', exact: true }).fill('510');
  await replacementLeg.getByRole('spinbutton', { name: 'Exact fill $', exact: true }).fill('9');
  await rollEditor.getByRole('spinbutton', { name: 'Total fees $', exact: true }).fill('2');
  await rollEditor.getByRole('button', { name: 'Record exact roll', exact: true }).click();
  await page.waitForSelector('.book-shared-position-editor .position-editor-result:has-text("ROLL RECORDED")', { timeout: 30000 })
    .catch(async error => {
      const diag = await page.evaluate(() => ({
        result: (document.querySelector('.book-shared-position-editor .position-editor-result') || {}).textContent?.slice(0, 260),
        toasts: (document.getElementById('toast-region') || {}).textContent?.slice(0, 200),
        alerts: Array.from(document.querySelectorAll('.book-shared-position-editor .alert')).map(a => a.textContent.slice(0, 120))
      }));
      throw new Error('DIAG ' + JSON.stringify(diag) + ' :: ' + error.message);
    });
  assert.match(await rollEditor.locator('.position-editor-result').innerText(),
    /Old result realized; replacement open[\s\S]*Net premium before fees[\s\S]*−\$200\.00/,
    'the canonical workbench reports the linked premium and refreshed book consequence in place');
  await go('#/portfolio/book/activity');
  await page.getByRole('tab', { name: 'History', exact: true }).click();
  await page.locator('.book-journal .xp-head').filter({ hasText: /roll/i }).first().click();
  assert.match(await page.textContent('.book-roll-summary'), /Linked roll[\s\S]*Net premium before fees[\s\S]*−\$200\.00/i,
    'one roll control records the realized close, linked replacement, and premium carryover');
  await page.getByRole('tab', { name: 'Record activity', exact: true }).click();
  await page.getByRole('combobox', { name: 'What happened?', exact: true }).selectOption('ROLL');
  await page.waitForSelector('.position-roll-picker');
  assert.match(await page.textContent('.position-roll-picker'), /Choose the option to roll[\s\S]*QQQ.*510\.00 call/i,
    'choosing Roll directly in Activity routes through the same open-position picker and canonical editor');
  await page.getByRole('combobox', { name: 'What happened?', exact: true }).selectOption('TRADE');
  await page.waitForSelector('.book-shared-position-editor .position-editor');

  const importDate = await page.evaluate(() => {
    const now = new Date();
    return [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-');
  });
  const mixedImport = 'transaction_ref,occurred_at,event_type,cash_effect_cents,fees_cents,tax_category,notes,leg_no,instrument,action,position_effect,symbol,option_type,strike,expiration,quantity,multiplier,price,section_1256\n'
    + 'dom-import-interest,' + importDate + ',INTEREST,250,0,ORDINARY_INTEREST,Valid imported income\n'
    + 'dom-import-bad,' + importDate + ',TRADE,,0,,,0,STOCK,BUY,OPEN,NVDA,,,,not-a-number,1,100,false\n';
  await page.getByRole('tab', { name: 'Import history', exact: true }).click();
  await page.getByLabel('Portfolio activity CSV').setInputFiles({
    name: 'mixed-portfolio-history.csv', mimeType: 'text/csv', buffer: Buffer.from(mixedImport)
  });
  await page.getByRole('button', { name: 'Import CSV', exact: true }).click();
  await page.waitForSelector('.book-import-result');
  assert.match(await page.textContent('.book-import-result'), /Imported 1 transaction from 2 rows\. 1 row quarantined\./,
    'a malformed row cannot discard the valid transaction');
  assert.match(await page.textContent('.book-import-result'), /Realized P\/L[\s\S]*Unrealized P\/L[\s\S]*Cash in this book/,
    'an import answers with refreshed profit and cash instead of only a ledger acknowledgement');
  await page.locator('.book-import-result button').filter({ hasText: 'Review quarantined rows' }).click();
  assert.match(await page.textContent('.book-import-rejects'), /Line 3[\s\S]*dom-import-bad[\s\S]*quantity must be an integer/,
    'the rejected row keeps its line, transaction reference, and reason');

  const brokerFixtures = await page.evaluate(async () => {
    const secondBook = await API.post('/api/portfolio/accounts', {
      name: 'Tracked retirement destination', accountType: 'TRADITIONAL_IRA', broker: 'Example broker',
      lotMethod: 'FIFO', openingCashCents: 2500000
    });
    const archivedBook = await API.post('/api/portfolio/accounts', {
      name: 'Archived mapping decoy', accountType: 'TAXABLE', broker: 'Old broker',
      lotMethod: 'FIFO', openingCashCents: 100000
    });
    await API.del('/api/portfolio/accounts/' + archivedBook.id);
    const observedTarget = await API.post('/api/plans', {
      clientRequestId: 'dom-broker-observed-target-' + Date.now(), symbol: 'QQQ',
      intent: 'DIRECTIONAL', title: 'Observed broker-link target', thesis: 'bullish',
      horizonDays: 91, riskMode: 'conservative'
    });
    const observedArchived = await API.post('/api/plans', {
      clientRequestId: 'dom-broker-observed-archived-' + Date.now(), symbol: 'QQQ',
      intent: 'DIRECTIONAL', title: 'Observed archived Plan decoy', thesis: 'neutral',
      horizonDays: 92, riskMode: 'conservative'
    });
    const demoTrap = await API.post('/api/plans', {
      clientRequestId: 'dom-broker-demo-trap-' + Date.now(), symbol: 'QQQ',
      intent: 'DIRECTIONAL', title: 'Demo broker-link trap', thesis: 'bearish',
      horizonDays: 93, riskMode: 'conservative'
    });
    return { secondBook, archivedBook, observedTarget, observedArchived, demoTrap };
  });
  // The fixture browser cannot enter the Observed lane. Seed the two library rows directly so
  // this presentation test can prove the wizard's server-market filters; the service/API tests
  // exercise the real OBSERVED-only commit boundary.
  pg.sql("UPDATE plans SET market_kind='OBSERVED',world_id=NULL WHERE id='"
    + brokerFixtures.observedTarget.id + "'; UPDATE plans SET market_kind='OBSERVED',world_id=NULL,"
    + "status='ARCHIVED',is_open=0 WHERE id='" + brokerFixtures.observedArchived.id + "'");
  await go('#/portfolio/book/activity');
  await page.getByRole('tab', { name: 'Import history', exact: true }).click();

  const brokerTime = await page.evaluate(() => new Date(Date.now() - 500).toISOString());
  const brokerPaste = 'confirmation_number,account,executed_at,symbol,type,action,position,quantity,multiplier,price,net_amount,fees,leg,market_price,as_of\n'
    + 'DOM-BROKER-EXACT,IRA ending 7788,' + brokerTime + ',AAPL,stock,buy,open,1,1,10,-10,0,0,11,' + brokerTime + '\n'
    + 'DOM-BROKER-USER,IRA ending 7788,' + brokerTime + ',MSFT,stock,buy,open,1,1,,-20,0,0,21,' + brokerTime + '\n'
    + 'DOM-BROKER-AUTH,IRA ending 7788,' + brokerTime + ',NVDA,stock,buy,open,1,1,,-30,0,0,31,' + brokerTime + '\n'
    + 'DOM-BROKER-EXACT-2,IRA ending 8899,' + brokerTime + ',QQQ,stock,buy,open,1,1,12,-12,0,0,13,' + brokerTime + '\n';
  await page.getByRole('combobox', { name: 'Broker export', exact: true }).selectOption('VANGUARD');
  await page.getByLabel('Source account label / last four', { exact: true }).fill('Statement envelope 2026-07');
  await page.getByLabel('Broker text', { exact: true }).fill(brokerPaste);
  await page.getByRole('button', { name: 'Preview broker facts', exact: true }).click();
  await page.waitForSelector('.broker-import-group');
  const brokerPreviewText = await page.textContent('.broker-import-preview');
  assert.match(brokerPreviewText, /4 packages ready for explicit verification/,
    'one statement-wide preview accounts for every package before any write');
  assert.match(brokerPreviewText, /EXACT FILLS/,
    'the preview identifies packages with exact broker fills');
  assert.match(brokerPreviewText, /PACKAGE NET — PENDING/,
    'the preview separates package-net-only groups for explicit resolution');
  assert.match(await page.textContent('.broker-import-preview'),
    /Pasted snapshot[\s\S]*(Observed re-mark|No eligible Observed current mark)/,
    'pasted statement marks stay visibly stale beside a provenance-qualified independent re-mark');
  const mappingControls = page.locator('.broker-account-mapping select');
  assert.equal(await mappingControls.count(), 2,
    'one statement-wide preview exposes one explicit destination for each source-account fingerprint');
  const mappingOptions = (await mappingControls.allTextContents()).join('\n');
  assert.match(mappingOptions, /Tracked taxable test[\s\S]*Tracked retirement destination/,
    'every active destination Book is available to the statement-wide mapper');
  assert.doesNotMatch(mappingOptions, /Archived mapping decoy/,
    'archived Books are never offered as broker destinations');
  for (const destination of await mappingControls.all()) {
    assert.equal(await destination.inputValue(), '', 'the currently open Book is never a silent destination default');
  }
  const sourceAccounts = await page.locator('.broker-import-group').evaluateAll(groups => groups.map(group => ({
    externalRef: group.querySelector('.broker-import-reference > b')?.textContent.trim(),
    fingerprint: (group.textContent.match(/acct-[a-f0-9]{24}/) || [])[0]
  })));
  const destinationByFingerprint = new Map();
  sourceAccounts.forEach(group => destinationByFingerprint.set(group.fingerprint,
    group.externalRef === 'DOM-BROKER-EXACT-2' ? brokerFixtures.secondBook.id : trackedId));
  for (const [fingerprint, destinationId] of destinationByFingerprint.entries()) {
    await page.locator('.broker-account-mapping .field').filter({ hasText: fingerprint })
      .locator('select').selectOption(destinationId);
  }
  const brokerStatePrivacy = await page.evaluate(rawValues => {
    const state = JSON.stringify(App.state);
    return { hasRawBrokerStore: Object.prototype.hasOwnProperty.call(App.state, 'portfolioBrokerImports'),
      leaked: rawValues.filter(value => state.includes(value)) };
  }, ['Statement envelope 2026-07', 'IRA ending 7788', 'IRA ending 8899', 'DOM-BROKER-EXACT']);
  assert.deepEqual(brokerStatePrivacy, { hasRawBrokerStore: false, leaked: [] },
    'raw statement text, source labels, and references remain in the mounted form closure, never App.state');
  for (const viewport of [{ width: 2560, height: 1440 }, { width: 390, height: 844 },
    { width: 375, height: 812 }, { width: 320, height: 700 }]) {
    await page.setViewportSize(viewport);
    const importGeometry = await page.locator('.broker-import-preview').evaluate(preview => {
      const box = preview.getBoundingClientRect();
      const controls = Array.from(preview.querySelectorAll('input,select,button')).map(node => {
        const rect = node.getBoundingClientRect();
        return { left: rect.left, right: rect.right, width: rect.width };
      });
      const firstEditor = preview.querySelector('.broker-import-editor-grid');
      const editorColumns = firstEditor ? getComputedStyle(firstEditor).gridTemplateColumns.split(' ').length : 0;
      return { page: document.documentElement.scrollWidth, viewport: innerWidth,
        left: box.left, right: box.right, width: box.width, controls: controls, editorColumns: editorColumns };
    });
    assert.ok(importGeometry.page <= importGeometry.viewport + 1
      && importGeometry.left >= 0 && importGeometry.right <= importGeometry.viewport + 1,
    viewport.width + 'px broker preview remains bounded to the viewport: ' + JSON.stringify(importGeometry));
    assert.equal(importGeometry.controls.some(control => control.left < 0
      || control.right > importGeometry.viewport + 1 || control.width < 1), false,
    viewport.width + 'px broker controls stay visible and operable: ' + JSON.stringify(importGeometry));
    if (viewport.width === 2560) assert.ok(importGeometry.editorColumns >= 5,
      '2560px uses a dense verification workbench instead of vertically stretching every field');
    if (viewport.width === 320) assert.equal(importGeometry.editorColumns, 1,
      '320px stacks the verification fields into one readable mobile column');
  }
  await page.setViewportSize({ width: 1280, height: 720 });
  assert.equal(await page.locator('.broker-import-group input[type="checkbox"]:checked').count(), 0,
    'no broker group is silently selected for confirmation');
  for (const checkbox of await page.locator('.broker-import-group input[type="checkbox"]').all()) {
    await checkbox.check();
  }
  const pendingQueueRequests = [];
  const rememberPendingQueueRequest = request => {
    if (request.url().includes('/api/portfolio/broker-imports?')) pendingQueueRequests.push(request.url());
  };
  page.on('request', rememberPendingQueueRequest);
  await page.getByRole('button', { name: 'Confirm selected facts', exact: true }).click();
  await page.waitForSelector('.broker-batch-adoption');
  assert.match(await page.textContent('.broker-import-confirm'),
    /2 exact transactions recorded; 2 packages quarantined pending per-leg fills/,
    'one atomic statement confirmation writes exact facts into both mapped Books and quarantines unresolved cash');
  assert.equal(await page.locator('.broker-batch-adoption').count(), 1,
    'mixed source accounts lead to one statement-wide adoption wizard, not duplicate account journeys');
  assert.equal(await page.locator('.broker-batch-row').count(), 2,
    'both exact positions remain together for the second explicit Plan decision');
  assert.match(await page.textContent('.broker-batch-adoption'),
    /Tracked taxable test[\s\S]*Tracked retirement destination|Tracked retirement destination[\s\S]*Tracked taxable test/,
    'every adoption row names the destination Book inside the one atomic wizard');

  const ibmBatchRow = page.locator('.broker-batch-row').filter({ hasText: 'DOM-BROKER-EXACT-2' });
  const aaplBatchRow = page.locator('.broker-batch-row').filter({ hasText: 'DOM-BROKER-EXACT' }).filter({ hasNotText: 'EXACT-2' });
  await aaplBatchRow.getByRole('combobox', { name: 'Plan choice', exact: true }).selectOption('ADOPT');
  await ibmBatchRow.getByRole('combobox', { name: 'Plan choice', exact: true }).selectOption('LINK');
  const planChoices = await ibmBatchRow.getByRole('combobox', { name: 'Existing Plan', exact: true }).allTextContents();
  assert.match(planChoices.join('\n'), /Observed broker-link target/,
    'an active Observed Plan for the imported symbol is available to LINK');
  assert.doesNotMatch(planChoices.join('\n'), /Demo broker-link trap|Observed archived Plan decoy/,
    'cross-market and archived Plans never appear as valid LINK destinations');
  await ibmBatchRow.getByRole('combobox', { name: 'Existing Plan', exact: true })
    .selectOption(brokerFixtures.observedTarget.id);

  const batchAdoptionRequests = [];
  let batchAdoptionAttempt = 0;
  await page.route('**/api/plans/adopt-batch', async route => {
    const request = route.request().postDataJSON();
    batchAdoptionRequests.push(request);
    batchAdoptionAttempt += 1;
    const replay = batchAdoptionAttempt > 1;
    const observedTarget = { ...brokerFixtures.observedTarget, marketKind: 'OBSERVED', worldId: null };
    const adoptedPlan = { ...observedTarget, id: 'plan_dom_broker_atomic', symbol: 'AAPL',
      title: 'AAPL imported broker position' };
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({
      items: request.items.map(item => ({ action: item.action,
        plan: item.action === 'LINK' ? observedTarget : adoptedPlan, artifacts: null, replayed: replay })),
      adopted: replay ? 0 : 1, linked: replay ? 0 : 1, skipped: 0, replayed: replay ? 2 : 0,
      note: 'The confirmed batch is atomic.'
    }) });
  });
  // The fixture build intentionally cannot switch to Observed. Set only the presentation owner
  // while the adopt endpoint is stubbed; PlanAdoptionBatchTest pins the real server-side lane gate.
  await page.evaluate(() => {
    App.state.world = 'observed';
    if (App.Market) App.Market.world = 'observed';
  });
  await page.getByRole('button', { name: 'Confirm batch adoption', exact: true }).click();
  await page.waitForSelector('.broker-batch-adoption:has-text("Batch adoption committed")');
  assert.match(await page.textContent('.broker-batch-adoption'), /1 new Plan, 1 linked, 0 skipped/,
    'one POST commits the mixed-account ADOPT and LINK choices as a statement-wide batch');
  assert.equal(batchAdoptionRequests.length, 1, 'the statement-wide confirmation sends one batch command');
  assert.deepEqual(batchAdoptionRequests[0].items.map(item => item.portfolioAccountId).sort(),
    [trackedId, brokerFixtures.secondBook.id].sort(),
  'the atomic batch carries both explicit destination Books');
  assert.deepEqual(batchAdoptionRequests[0].items.map(item => item.action).sort(), ['ADOPT', 'LINK'],
    'the batch preserves each row\'s deliberate action');
  await page.getByRole('button', { name: 'Confirm batch adoption', exact: true }).click();
  await page.waitForFunction(() => /2 unchanged replays/.test(document.querySelector('.broker-batch-adoption')?.textContent || ''));
  assert.match(await page.textContent('.broker-batch-adoption'), /0 new Plans, 0 linked, 0 skipped, 2 unchanged replays/,
    'a byte-equivalent retry reports unchanged replays instead of claiming duplicate Plan work');
  assert.deepEqual(batchAdoptionRequests[1], batchAdoptionRequests[0],
    'the replay sends the same stable client request IDs and allocations');

  await page.waitForSelector('.broker-pending-card:has-text("DOM-BROKER-AUTH")');
  for (const viewport of [{ width: 2560, height: 1440 }, { width: 390, height: 844 },
    { width: 375, height: 812 }, { width: 320, height: 700 }]) {
    await page.setViewportSize(viewport);
    const brokerGeometry = await page.locator('.book-import-card').evaluate(card => {
      const controls = Array.from(card.querySelectorAll('input,select,textarea,button')).filter(node => {
        const style = getComputedStyle(node), box = node.getBoundingClientRect();
        return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0;
      }).map(node => {
        const rect = node.getBoundingClientRect();
        return { left: rect.left, right: rect.right, width: rect.width };
      });
      const batch = card.querySelector('.broker-batch-row');
      const queue = card.querySelector('.broker-pending-queue');
      return { page: document.documentElement.scrollWidth, viewport: innerWidth,
        controls, batchColumns: batch ? getComputedStyle(batch).gridTemplateColumns.split(' ').length : 0,
        queueColumns: queue ? getComputedStyle(queue).gridTemplateColumns.split(' ').length : 0 };
    });
    assert.ok(brokerGeometry.page <= brokerGeometry.viewport + 1,
      viewport.width + 'px statement-wide broker journey has no horizontal overflow: ' + JSON.stringify(brokerGeometry));
    assert.equal(brokerGeometry.controls.some(control => control.left < 0
      || control.right > brokerGeometry.viewport + 1 || control.width < 1), false,
    viewport.width + 'px batch and queue controls remain visible and operable: ' + JSON.stringify(brokerGeometry));
    if (viewport.width === 2560) {
      assert.ok(brokerGeometry.batchColumns >= 3 && brokerGeometry.queueColumns >= 2,
        '2560px uses the desktop real estate for a three-column decision row and two-column queue');
    } else {
      assert.equal(brokerGeometry.batchColumns, 1, viewport.width + 'px stacks each batch decision coherently');
      assert.equal(brokerGeometry.queueColumns, 1, viewport.width + 'px keeps the resolution queue in one mobile column');
    }
  }
  await page.setViewportSize({ width: 1280, height: 720 });

  const rejectCard = page.locator('.broker-pending-card').filter({ hasText: 'DOM-BROKER-AUTH' });
  await rejectCard.getByRole('button', { name: 'Reject pending import', exact: true }).click();
  await page.waitForSelector('#modal-root [role="dialog"]');
  assert.match(await page.textContent('#modal-root [role="dialog"]'),
    /Remove DOM-BROKER-AUTH from the active resolution queue[\s\S]*identity and resolution history remain intact[\s\S]*reopen/i,
    'reject is a deliberate, explained, recoverable command');
  const stillOpenBeforeReject = await page.evaluate(async id => (await API.getFresh(
    '/api/portfolio/broker-imports?status=OPEN&limit=100&offset=0&portfolioAccountId=' + encodeURIComponent(id)))
    .imports.some(row => row.externalRef === 'DOM-BROKER-AUTH'), trackedId);
  assert.equal(stillOpenBeforeReject, true, 'opening the confirmation does not mutate the pending import');
  await page.click('#modal-confirm');
  await page.waitForSelector('#modal-root [role="dialog"]', { state: 'detached' });
  await page.waitForSelector('.broker-rejected-history:has-text("DOM-BROKER-AUTH")');
  assert.equal(await page.locator('.broker-pending-card').filter({ hasText: 'DOM-BROKER-AUTH' }).count(), 0,
    'confirmed rejection removes the package from the active queue');
  const rejectedHistory = page.locator('.broker-rejected-row').filter({ hasText: 'DOM-BROKER-AUTH' });
  await rejectedHistory.getByRole('button', { name: 'Reopen', exact: true }).click();
  await page.waitForSelector('.broker-pending-card:has-text("DOM-BROKER-AUTH")');
  const listQueries = pendingQueueRequests.map(url => new URL(url).searchParams);
  assert.ok(listQueries.some(query => query.get('status') === 'OPEN' && query.get('limit') === '100'
      && query.get('offset') === '0' && query.get('portfolioAccountId') === trackedId)
    && listQueries.some(query => query.get('status') === 'REJECTED' && query.get('limit') === '50'
      && query.get('offset') === '0' && query.get('portfolioAccountId') === trackedId),
  'the active queue and reversible history use separate bounded, account-scoped pages');
  page.off('request', rememberPendingQueueRequest);

  const provisionalCard = page.locator('.broker-pending-card').filter({ hasText: 'DOM-BROKER-USER' });
  await provisionalCard.getByRole('spinbutton', { name: 'Per-leg fill', exact: true }).fill('20');
  await provisionalCard.getByRole('combobox', { name: 'Where did these per-leg prices come from?', exact: true })
    .selectOption('USER_ALLOCATED');
  await provisionalCard.getByRole('button', { name: 'Record package resolution', exact: true }).click();
  await page.waitForSelector('#broker-pending-queue:has-text("Package resolution recorded · PROVISIONAL")');
  assert.match(await page.textContent('#broker-pending-queue'),
    /created no tracked transaction, lot, wash-sale basis, or export row/i,
    'a user allocation is immutable provisional history without contaminating tracked accounting');

  const attestationCard = page.locator('.broker-pending-card').filter({ hasText: 'DOM-BROKER-USER' });
  await attestationCard.getByRole('combobox', { name: 'Where did these per-leg prices come from?', exact: true })
    .selectOption('BROKER_REPORTED');
  await attestationCard.getByRole('button', { name: 'Attest with broker fills', exact: true }).click();
  await page.waitForSelector('#broker-pending-queue:has-text("Broker fills attested")');

  const authoritativeCard = page.locator('.broker-pending-card').filter({ hasText: 'DOM-BROKER-AUTH' });
  await authoritativeCard.getByRole('spinbutton', { name: 'Per-leg fill', exact: true }).fill('30');
  await authoritativeCard.getByRole('combobox', { name: 'Where did these per-leg prices come from?', exact: true })
    .selectOption('BROKER_REPORTED');
  await authoritativeCard.getByRole('button', { name: 'Record package resolution', exact: true }).click();
  await page.waitForSelector('#broker-pending-queue:has-text("Package resolution recorded · AUTHORITATIVE")');
  assert.match(await page.textContent('#broker-pending-queue'), /eligible for tracked tax basis/i,
    'broker-confirmed leg fills visibly create authoritative basis provenance');
  const resolvedImports = await page.evaluate(async () => API.getFresh('/api/portfolio/broker-imports?status=RESOLVED'));
  assert.deepEqual(resolvedImports.imports.map(row => row.resolution.taxBasisStatus).sort(),
    ['AUTHORITATIVE', 'AUTHORITATIVE'], 'only broker-attested packages become resolved tracked facts');
  const resolvedDetails = await page.evaluate(async rows => Promise.all(rows.map(row =>
    API.getFresh('/api/portfolio/broker-imports/' + encodeURIComponent(row.id)))), resolvedImports.imports);
  assert.deepEqual(resolvedDetails.map(row => row.resolution.taxBasisStatus).sort(),
    ['AUTHORITATIVE', 'AUTHORITATIVE'], 'authoritative status survives round-trip through detail APIs');
  const attestedDetail = resolvedDetails.find(row => row.externalRef === 'DOM-BROKER-USER');
  assert.deepEqual(attestedDetail.resolutionHistory.map(event => event.authority),
    ['USER_ALLOCATED', 'BROKER_REPORTED'],
  'later broker attestation appends to, rather than overwrites, provisional resolution history');
  await page.unroute('**/api/plans/adopt-batch');
  await page.evaluate(async () => {
    App.state.world = 'demo';
    if (App.Market) App.Market.world = 'demo';
    API.flushCache();
    await PlanStore.load(true);
  });
  await page.getByRole('tab', { name: 'History', exact: true }).click();
  await assertNoInternalChrome('#app');
  await captureSettled('p110-accounting-activity-desktop.png');

  const pastSnapshot = await page.evaluate(async id => API.post('/api/portfolio/accounts/' + id + '/valuations', {
    asOf: '2026-06-02T00:30:00Z', totalValueCents: 10000000, cashCents: 10000000,
    securitiesValueCents: 0, source: 'MANUAL', externalRef: 'dom-value-start', notes: 'Opening statement'
  }), trackedId);
  assert.ok(pastSnapshot.id, 'a prior exact valuation is recorded');
  pg.sql("INSERT INTO underlying_bar(symbol,d,close,source,observed,dataset_id,adjusted,quality_rank) VALUES "
    + "('SPY','2026-06-01',100,'dom-observed',1,'observed',1,10),"
    + "('SPY','2026-07-13',110,'dom-observed',1,'observed',1,10) ON CONFLICT DO NOTHING");
  await go('#/portfolio/book/performance');
  await page.getByRole('button', { name: 'Use current book value', exact: true }).click();
  await page.waitForFunction(() => /Current total is unavailable/.test(document.querySelector('.book-valuation-form')?.textContent || ''));
  assert.equal(await page.getByRole('spinbutton', { name: 'Total account value $', exact: true }).inputValue(), '',
    'generated marks never leak into the manual account-value form');
  await page.getByRole('spinbutton', { name: 'Total account value $', exact: true }).fill('101000');
  await page.getByRole('spinbutton', { name: 'Cash $ (optional)', exact: true }).fill('99004');
  await page.getByRole('spinbutton', { name: 'Securities $ (optional)', exact: true }).fill('1996');
  await page.getByLabel('As of date and time', { exact: true }).fill('2026-07-13T10:30');
  await page.getByRole('textbox', { name: 'Statement reference', exact: true }).fill('dom-value-later');
  await page.getByRole('textbox', { name: 'Notes', exact: true }).fill('Manual statement value');
  await page.getByRole('button', { name: 'Record reconciliation', exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll('.book-valuation-row').length === 2, null, { timeout: 10000 });
  const performanceApi = await (await fetch(BASE + '/api/portfolio/accounts/' + trackedId + '/performance')).json();
  assert.equal(performanceApi.netExternalFlowCents, 0,
    'account setup cash is the book baseline, not a fictitious contribution inside historical performance');
  assert.equal(performanceApi.investmentGainCents,
    performanceApi.endingValueCents - performanceApi.startingValueCents,
    'performance reconciles exactly when there are no real external flows between snapshots');
  assert.equal(await page.locator('.book-performance-chart svg.chart').count(), 1,
    'two snapshots produce the contribution-adjusted performance chart');
  const performanceDates = (await page.locator('.book-performance-chart svg.chart text').allTextContents())
    .filter(text => /^\d{4}-\d{2}-\d{2}$/.test(text));
  assert.equal(new Set(performanceDates).size, performanceDates.length,
    'a short performance series never paints duplicate date labels on top of each other');
  assert.match(await page.textContent('.book-valuation-list'), /2026-06-01/,
    'an evening US snapshot renders on the local calendar date, not the next UTC date');
  assert.doesNotMatch(await page.textContent('.book-valuation-list'), /2026-06-02/,
    'performance history never exposes the UTC slice as the user-facing date');
  assert.match(await page.textContent('#app'), /Income recorded[\s\S]*\$7\.50/,
    'performance includes both manually recorded and valid imported income');
  for (const label of ['Time-weighted return', 'Money-weighted return (IRR)', 'Maximum drawdown', 'SPY benchmark']) {
    const metric = await page.locator('.book-performance-stats .stat').filter({ hasText: label }).textContent();
    assert.doesNotMatch(metric, /Unavailable/, label + ' is computed in the golden tracked-book journey');
  }
  await captureSettled('p110-accounting-performance-desktop.png');

  await go('#/portfolio/book/settings');
  assert.equal(await page.getByRole('spinbutton', { name: 'Short-term scenario rate %', exact: true }).count(), 0,
    'Beginner account settings keep optional tax-rate scenarios out of the critical path');
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'expert'
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.waitForSelector('.portfolio-account-form');
  await page.getByRole('button', { name: 'Tax scenario (optional)', exact: true }).click();
  assert.match(await page.textContent('.portfolio-account-form'), /Not tax advice/,
    'the tax-rate settings carry the required tax-advice boundary');
  await page.getByRole('spinbutton', { name: 'Short-term scenario rate %', exact: true }).fill('32');
  await page.getByRole('spinbutton', { name: 'Long-term scenario rate %', exact: true }).fill('15');
  await page.getByRole('spinbutton', { name: 'Ordinary-income scenario rate %', exact: true }).fill('24');
  await page.getByRole('spinbutton', { name: 'State scenario rate %', exact: true }).fill('5');
  await page.getByRole('button', { name: 'Save settings', exact: true }).click();
  await page.waitForFunction(() => /Account settings saved/.test(document.getElementById('toast-region')?.textContent || ''));
  await page.evaluate(async id => {
    const occurredAt = new Date().toISOString();
    const record = (externalRef, action, effect, price) => API.post('/api/portfolio/accounts/' + id + '/transactions', {
      occurredAt, eventType: 'TRADE', fillNature: 'EXECUTED', cashAmountCents: null, feesCents: 0, taxCategory: null,
      source: 'MANUAL', externalRef, notes: 'Unreviewed-year wash disclosure control', legs: [{
        instrumentType: 'STOCK', action, positionEffect: effect, symbol: 'META', optionType: null,
        strike: null, expiration: null, quantity: 1, multiplier: 1, price, section1256: false
      }]
    });
    await record('dom-tax-loss-open', 'BUY', 'OPEN', 100);
    await record('dom-tax-loss-close', 'SELL', 'CLOSE', 90);
  }, trackedId);
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/portfolio/book/tax');
  assert.equal(await page.locator('#route-error').count(), 0,
    'the tracked-book tax route renders: ' + await page.textContent('#app'));
  assert.match(await page.textContent('.book-tax-heading'), /Not tax advice/,
    'the tax report carries the required tax-advice boundary before any scenario');
  assert.equal(await page.getByRole('tab', { name: 'Records & export', exact: true }).getAttribute('aria-selected'), 'true',
    'Beginner names the surface for its primary records-and-export job');
  assert.match(await page.textContent('#app'), /Records and tax summary[\s\S]*Tax summary[\s\S]*Short-term gain \/ loss[\s\S]*Long-term gain \/ loss[\s\S]*Interest \+ dividends/,
    'Beginner receives the compact recorded-book tax summary first');
  assert.equal(await page.locator('.book-tax-reconciliation').count(), 0,
    'broker-form and lot machinery stays secondary until requested');
  await page.getByRole('button', { name: 'Tax detail and lot reconciliation', exact: true }).click();
  assert.match(await page.textContent('#app'), /Tax rules not reviewed for 2026[\s\S]*user-rate scenario is withheld/,
    'the current provisional year preserves recorded facts while withholding an unreviewed tax scenario');
  assert.equal(await page.locator('.book-tax-sources a').count(), 4,
    'the worksheet exposes the primary tax-rule sources instead of asking users to trust an opaque ruleset');
  await page.getByRole('spinbutton', { name: 'Final short-term $', exact: true }).fill('125');
  await page.getByRole('button', { name: 'Interest and dividend forms', exact: true }).click();
  await page.getByRole('spinbutton', { name: 'Broker interest $', exact: true }).fill('7.50');
  await page.getByRole('textbox', { name: 'Broker form reference', exact: true }).fill('Corrected 1099 package');
  await page.getByRole('combobox', { name: 'Reconciliation status', exact: true }).selectOption('RECONCILED');
  await page.getByRole('button', { name: 'Save reconciliation', exact: true }).click();
  await page.waitForSelector('.book-tax-reconciliation:has-text("RECONCILED")');
  assert.match(await page.textContent('.book-tax-comparison'), /Recorded book vs broker forms[\s\S]*Difference[\s\S]*Final short-term total[\s\S]*\$125\.00/,
    'broker totals remain beside the tracked book with an explicit difference rather than overwriting it');
  assert.equal(await page.getByRole('link', { name: 'Download transactions CSV', exact: true }).count(), 1);
  assert.equal(await page.getByRole('link', { name: 'Download Excel workbook', exact: true }).count(), 1);
  assert.doesNotMatch(await page.textContent('#app'), /Rates needed/,
    'taxable rates unlock the explicit estimate without changing lot accounting');
  assert.match(await page.textContent('.book-open-tax-lots'), /AAPL shares[\s\S]*Basis remaining[\s\S]*\$1,001\.00/,
    'open tax lots expose their exact remaining basis in the product, not only in exports');
  assert.equal(await page.locator('.book-realized-lot-row').count(), 2,
    'Beginner gets a readable exact realized-lot row instead of an eleven-column ledger');
  assert.match(await page.textContent('.book-realized'), /META[\s\S]*Wash rule not applied — 2026 rules unreviewed/,
    'an unreviewed-year loss states that the wash rule was not applied instead of showing a decorative $0.00');
  assert.equal(await page.locator('.book-realized .tbl-wrap').count(), 0,
    'the dense realized-lot table is reserved for Expert');
  await captureSettled('p110-accounting-tax-desktop.png');

  await page.evaluate(() => Learn.setLevel('expert'));
  await go('#/portfolio/book/tax');
  assert.equal(await page.locator('.book-realized .tbl-wrap').count(), 1,
    'Expert retains every exact tax-lot column');
  assert.match(await page.textContent('.book-realized'), /Wash review[\s\S]*Wash rule not applied — 2026 rules unreviewed/,
    'the Expert ledger preserves the row-level unreviewed-rule disclosure');
  const expertTaxGeometry = await page.evaluate(() => {
    const wrap = document.querySelector('.book-realized .tbl-wrap');
    const box = wrap.getBoundingClientRect();
    return { page: document.documentElement.scrollWidth, viewport: innerWidth,
      left: box.left, right: box.right, client: wrap.clientWidth, scroll: wrap.scrollWidth };
  });
  assert.ok(expertTaxGeometry.page <= expertTaxGeometry.viewport,
    'the Expert tax ledger must scroll internally, not widen the page: ' + JSON.stringify(expertTaxGeometry));
  assert.ok(expertTaxGeometry.left >= 0 && expertTaxGeometry.right <= expertTaxGeometry.viewport,
    'the Expert ledger container remains fully on screen: ' + JSON.stringify(expertTaxGeometry));
  await page.evaluate(() => Learn.setLevel('beginner'));

  await go('#/portfolio/book/settings');
  await page.getByRole('button', { name: 'Archive account', exact: true }).click();
  await page.waitForSelector('.book-account-status:has-text("ARCHIVED")');
  await go('#/portfolio/book/activity');
  await page.getByRole('tab', { name: 'Record activity', exact: true }).click();
  assert.equal(await page.getByRole('button', { name: 'Record factual activity', exact: true }).isDisabled(), true,
    'archived tracked accounts are read-only in the UI as well as the service');
  assert.match(await page.textContent('.book-shared-position-editor'), /archived.*Restore it before recording/i,
    'the disabled command names the action that restores recording');
  await go('#/portfolio/book/settings');
  await page.getByRole('button', { name: 'Restore account', exact: true }).click();
  await page.waitForSelector('.book-account-status:has-text("Open")');

  for (const width of [390, 375, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await go('#/portfolio/book/activity');
    await page.getByRole('tab', { name: 'History', exact: true }).click();
    const geometry = await page.evaluate(() => {
      const tabs = document.querySelector('.portfolio-book-tabs');
      const boxes = Array.from(tabs.querySelectorAll('[role="tab"]')).map(tab => tab.getBoundingClientRect());
      const activityTabs = Array.from(document.querySelectorAll('.book-activity-tabs [role="tab"]'));
      return { body: document.documentElement.scrollWidth, viewport: innerWidth,
        clipped: boxes.some(box => box.left < 0 || box.right > innerWidth || box.width < 90),
        rows: new Set(boxes.map(box => Math.round(box.top))).size,
        activityWrapped: activityTabs.some(tab => getComputedStyle(tab).whiteSpace !== 'nowrap'
          || tab.getBoundingClientRect().height > 36) };
    });
    assert.ok(geometry.body <= geometry.viewport, width + 'px tracked activity has no page overflow: ' + JSON.stringify(geometry));
    assert.equal(geometry.clipped, false, width + 'px account tabs remain fully visible');
    assert.equal(geometry.rows, 3, width + 'px tracked-account navigation is the exact 2x3 grid');
    assert.equal(geometry.activityWrapped, false,
      width + 'px Activity tabs keep complete labels on one visible line');
    if (width === 390) {
      await captureSettled('p110-accounting-activity-mobile.png');
    }
    await go('#/portfolio/book/tax');
    const detailToggle = page.getByRole('button', { name: 'Tax detail and lot reconciliation', exact: true });
    if (await detailToggle.getAttribute('aria-expanded') === 'true') await detailToggle.click();
    const summaryGeometry = await page.locator('.book-tax-summary').evaluate(card => {
      const box = card.getBoundingClientRect();
      return { left: box.left, right: box.right, body: document.documentElement.scrollWidth, viewport: innerWidth };
    });
    assert.ok(summaryGeometry.body <= summaryGeometry.viewport && summaryGeometry.left >= 0
      && summaryGeometry.right <= summaryGeometry.viewport,
    width + 'px Beginner tax summary remains fully visible: ' + JSON.stringify(summaryGeometry));
    if (width === 390) await captureSettled('p110-accounting-tax-mobile.png');
    await ensureExpanded(detailToggle);
    const taxGeometry = await page.evaluate(() => {
      const card = document.querySelector('.book-tax-reconciliation');
      const box = card.getBoundingClientRect();
      const comparison = card.querySelector('.book-tax-comparison');
      return { body: document.documentElement.scrollWidth, viewport: innerWidth,
        cardLeft: box.left, cardRight: box.right,
        comparisonOverflow: comparison ? comparison.scrollWidth - comparison.clientWidth : 0,
        controls: Array.from(card.querySelectorAll('input,select,textarea,button')).some(node => {
          const b = node.getBoundingClientRect(); return b.left < 0 || b.right > innerWidth;
        }) };
    });
    assert.ok(taxGeometry.body <= taxGeometry.viewport,
      width + 'px tax reconciliation has no page overflow: ' + JSON.stringify(taxGeometry));
    assert.ok(taxGeometry.cardLeft >= 0 && taxGeometry.cardRight <= taxGeometry.viewport
        && taxGeometry.comparisonOverflow <= 1 && !taxGeometry.controls,
      width + 'px reconciliation card and controls remain fully visible: ' + JSON.stringify(taxGeometry));
  }
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.evaluate(() => Learn.setLevel('expert'));
});

test('TRADER/OWN R4.4: the Book account declares what it is FOR and keeps an immutable revision history', async () => {
  await page.setViewportSize({ width: 1280, height: 900 });
  if (await page.locator('#welcome-skip').count()) {
    await page.click('#welcome-skip');
    await page.waitForSelector('#app[data-ready="true"]');
  }
  await page.evaluate(() => Learn.setLevel('beginner'));
  // A dedicated account keeps this spec independent of every other tracked-book journey.
  const created = await fetch(BASE + '/api/portfolio/accounts', { method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Objective spec account', accountType: 'TAXABLE',
      lotMethod: 'FIFO', openingCashCents: 5000000 }) });
  const accountBody = await created.text();
  assert.ok(created.ok, 'tracked account for the objective spec: ' + accountBody);
  const account = JSON.parse(accountBody);
  await page.evaluate(id => {
    localStorage.setItem('strikebench.portfolioBookAccount', id);
    App.state.portfolioBookAccountId = id;
  }, account.id);
  await go('#/portfolio/book/overview');

  // Never declared: an invitation with the consequence, not a form dump.
  await page.waitForSelector('#account-objective-card[data-objective-state="undeclared"]');
  assert.match(await page.textContent('#account-objective-card'),
    /judges every idea against what you say this account is FOR/,
    'the undeclared card explains why declaring matters before showing any control');
  assert.equal(await page.locator('#account-objective-card [data-objective-controls]:not([hidden])').count(), 0,
    'controls stay closed until the owner asks to declare');

  // Declare INCOME + PREFER_BELOW_BASIS through the segmented controls (never a bare select).
  await page.click('#account-objective-declare');
  await page.waitForSelector('#account-objective-card [data-objective-controls]:not([hidden])');
  assert.equal(await page.locator('#account-objective-card select').count(), 0,
    'the objective controls are purpose-built choices, not bare listboxes');
  await page.click('#account-objective-choice .choice-option[data-value="INCOME"]');
  assert.match(await page.textContent('#account-objective-choice .control-consequence'),
    /premium collection is the goal/, 'the objective states its consequence before anything saves');
  assert.equal(await page.locator('#account-objective-direction').isVisible(), false,
    'INCOME never demands a direction');
  await page.click('#account-objective-choice .choice-option[data-value="DIRECTIONAL"]');
  assert.equal(await page.locator('#account-objective-direction').isVisible(), true,
    'DIRECTIONAL reveals the optional direction chips contextually');
  await page.click('#account-objective-choice .choice-option[data-value="INCOME"]');
  await page.click('#account-objective-assignment .choice-option[data-value="PREFER_BELOW_BASIS"]');
  assert.match(await page.textContent('#account-objective-assignment .control-consequence'),
    /below your basis counts in favor/, 'the assignment preference explains its reweighting');
  await page.click('#account-objective-save');

  // Declared: plain-language headline, quiet provenance, and the analyze-path consequence.
  await page.waitForSelector('#account-objective-card[data-objective-state="declared"][data-objective-revision="1"]');
  assert.match(await page.textContent('#account-objective-card [data-objective-headline]'),
    /Income — collect premium; assignment below basis welcome/,
    'the declared headline is plain language, never enum values');
  assert.match(await page.textContent('#account-objective-card'), /Revision 1 · declared/,
    'revision number and date read as quiet provenance');
  assert.match(await page.textContent('#account-objective-card'),
    /Every analysis on this account is now judged against this/,
    'the card states that the analyze path now judges against this objective');

  // A second declaration is revision 2, applied prospectively; history keeps both.
  await page.click('#account-objective-declare');
  await page.waitForSelector('#account-objective-card [data-objective-controls]:not([hidden])');
  await page.click('#account-objective-choice .choice-option[data-value="ACCUMULATE"]');
  await page.click('#account-objective-save');
  await page.waitForSelector('#account-objective-card[data-objective-revision="2"]');
  assert.match(await page.textContent('#account-objective-card [data-objective-headline]'),
    /Accumulate — build the share position/);
  await ensureExpanded(page.locator('#account-objective-card .xp-head').first());
  assert.equal(await page.locator('#account-objective-card [data-objective-history-row]').count(), 2,
    'history is a first-class record listing every revision');
  const firstRevision = await page.textContent('#account-objective-card [data-objective-history-row="1"]');
  assert.match(firstRevision, /Revision 1[\s\S]*Income/, 'revision 1 stays on record, oldest first');

  // Expert is a lens, not a fork: the same card adds raw enums and the inline revision series.
  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'expert'
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.waitForSelector('#account-objective-card[data-objective-revision="2"]');
  assert.match(await page.textContent('#account-objective-card'), /ACCUMULATE/,
    'expert sees the raw enum values alongside the plain headline');
  assert.equal(await page.locator('#account-objective-card [data-objective-history-row]').count(), 2,
    'expert reads the same revision series inline as a compact table');
  await captureSettled('book-account-objective.png');
});

test('campaign close review keeps one responsive Beginner/Expert journey and saves only the lesson locally', async t => {
  const fixture = await page.evaluate(async () => {
    const account = await API.post('/api/portfolio/accounts', {
      name: 'Journey E review book', accountType: 'TAXABLE', broker: 'Review fixture',
      lotMethod: 'FIFO', openingCashCents: 1000000
    });
    localStorage.setItem('strikebench.portfolioBookAccount', account.id);
    App.state.portfolioBookAccountId = account.id;
    const campaign = await API.post('/api/campaigns', {
      title: 'Journey E close review', symbol: 'AAPL'
    });
    return { accountId: account.id, campaignId: campaign.id };
  });

  await page.setViewportSize({ width: 2560, height: 1440 });
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/portfolio/book/overview');
  const campaign = page.locator('.campaign-row').filter({ hasText: 'Journey E close review' });
  await campaign.getByRole('button', { name: 'Close campaign & review', exact: true }).click();
  let review = page.locator('[data-campaign-review="' + fixture.campaignId + '"]');
  await review.waitFor();

  // The service tests pin how this typed review is derived from persisted decisions, marks,
  // and Canvas receipts. Here, make those exact API fields deterministic so the browser suite
  // exercises the available chart and the important withheld-cost presentation as well.
  const campaignPayload = await page.evaluate(async id => {
    const campaigns = (await API.getFresh('/api/campaigns')).campaigns || [];
    return campaigns.find(campaign => campaign.id === id);
  }, fixture.campaignId);
  campaignPayload.review.authoredVsRealized = {
    available: true, planId: 'plan-journey-e-ui', planTitle: 'AAPL income Plan',
    scenarioId: 'scenario-journey-e-ui', scenarioTitle: 'Dip then recover',
    authoredAt: '2026-07-01T14:00:00Z', contextRevision: 3,
    baseEnsembleId: 'ensemble-journey-e-ui',
    scenarioFingerprint: '1133557799aacceeff00112233445566778899aa',
    baseEnsembleFingerprint: '22446688aacceeff00112233445566778899aabb',
    waypointFill: 'EXACT_CONDITIONAL', anchorDate: '2026-07-01', anchorSpotCents: 10000,
    anchorSource: 'stooq', anchorFreshness: 'FRESH', marketLane: 'OBSERVED',
    realizedSource: 'stooq', realizedDataset: 'observed', adjusted: false,
    authored: [
      { tradingDay: 0, date: '2026-07-01', priceCents: 10000 },
      { tradingDay: 5, date: '2026-07-08', priceCents: 10800 },
      { tradingDay: 10, date: '2026-07-15', priceCents: 10300 }
    ],
    realized: [
      { tradingDay: 0, date: '2026-07-01', priceCents: 10000 },
      { tradingDay: 4, date: '2026-07-07', priceCents: 10400 },
      { tradingDay: 10, date: '2026-07-15', priceCents: 10100 }
    ],
    note: 'Authored 2026-07-01T14:00:00Z; exact saved pins, not a forecast. Realized closes are one coherent observed series.'
  };
  campaignPayload.review.protocolAdherence = [{
    planId: 'plan-journey-e-ui', decisionId: 'decision-target-ui', executionLane: 'PRACTICE',
    rule: 'TAKE_PROFIT', triggerPnlCents: 5000, triggerDaysToExpiry: null,
    ruleSummary: 'Take profit when the exact package reaches 50% of its opening credit.',
    status: 'OVERRIDDEN', triggeredAt: '2026-07-08T14:00:00Z',
    observedPnlAtTriggerCents: 5200, responseKind: 'PARTIAL_CLOSE',
    responseAt: '2026-07-10T14:00:00Z', responseResultCents: 1800,
    overrideSignedCostCents: null,
    note: 'Override recorded. Signed cost withheld because a partial close does not share the same whole-package quantity/basis.'
  }, {
    planId: 'plan-journey-e-ui', decisionId: 'decision-time-ui', executionLane: 'PRACTICE',
    rule: 'TIME_EXIT', triggerPnlCents: null, triggerDaysToExpiry: 21,
    ruleSummary: 'Review or roll the package at 21 days to expiry.', status: 'RESPECTED',
    triggeredAt: '2026-07-09T14:00:00Z', observedPnlAtTriggerCents: 2100,
    responseKind: 'ROLL', responseAt: '2026-07-09T14:05:00Z', responseResultCents: 2100,
    overrideSignedCostCents: null, note: 'The recorded roll respected the frozen time line.'
  }];
  const campaignListMatcher = url => url.pathname === '/api/campaigns';
  const campaignListRoute = async route => {
    if (route.request().method() !== 'GET') return route.fallback();
    return route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ campaigns: [campaignPayload] }) });
  };
  await page.route(campaignListMatcher, campaignListRoute);
  t.after(async () => page.unroute(campaignListMatcher, campaignListRoute));
  await go('#/portfolio/book/overview');
  review = page.locator('[data-campaign-review="' + fixture.campaignId + '"]');
  await review.waitFor();
  assert.match(await review.innerText(),
    /final review[\s\S]*Authored vs realized path[\s\S]*Your saved pins[\s\S]*Protocol adherence[\s\S]*Final campaign accounting[\s\S]*Recorded at broker[\s\S]*Practice[\s\S]*Cross-campaign pattern[\s\S]*Lesson note/i,
    'one close review contains authored-vs-realized, protocol, separated accounting lanes, patterns, and lesson');
  assert.match(await review.innerText(), /never numerically combined/i,
    'real and practice results carry the visible no-netting contract');
  assert.equal(await review.locator('.campaign-lane-result').count(), 2,
    'the two economic lanes are presented side by side instead of pooled');
  assert.equal(await review.locator('.campaign-review-chart .campaign-authored-line').count(), 1,
    'the available overlay draws the exact authored pins');
  assert.equal(await review.locator('.campaign-review-chart .campaign-realized-line').count(), 1,
    'the available overlay draws the coherent realized closes independently');
  assert.equal(await review.locator('.campaign-review-chart .campaign-authored-pin').count(), 3,
    'only the three exact saved pins receive authored markers');
  assert.match(await review.locator('.campaign-chart-legend').innerText(),
    /Your saved pins[\s\S]*Observed closes · stooq/,
    'the two paths remain visually and semantically labeled');
  assert.equal(await review.locator('.campaign-protocol-row').count(), 2,
    'the review renders each frozen mechanical rule once');
  assert.match(await review.locator('.campaign-protocol-row').first().innerText(),
    /overridden[\s\S]*partial close[\s\S]*Signed cost withheld[\s\S]*same whole-package quantity\/basis/i,
    'partial-package response is an override but never receives a fabricated comparable cost');
  assert.doesNotMatch(await review.locator('.campaign-protocol-row').first().innerText(),
    /Signed override cost/i,
    'the signed-cost metric is absent when the service withholds it for basis mismatch');
  assert.equal(await review.getByRole('textbox', { name: 'Lesson note', exact: true }).count(), 1,
    'Beginner owns the same editable lesson control');

  const desktopGeometry = await review.evaluate(node => {
    const grid = node.querySelector('.campaign-review-grid');
    const pane = node.querySelector('.campaign-review-pane');
    return {
      columns: getComputedStyle(grid).gridTemplateColumns.split(' ').filter(Boolean).length,
      gridWidth: grid.getBoundingClientRect().width,
      paneWidth: pane.getBoundingClientRect().width,
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth
    };
  });
  assert.equal(desktopGeometry.columns, 3, '2560px uses three readable review columns');
  assert.ok(desktopGeometry.gridWidth > 1500 && desktopGeometry.paneWidth > 400,
    'the review uses desktop real estate instead of leaving a narrow centered island: '
      + JSON.stringify(desktopGeometry));
  assert.ok(desktopGeometry.overflow <= 1, 'desktop review does not overflow horizontally');
  await captureSettled('journey-e-campaign-review-2560.png');

  const lesson = review.getByRole('textbox', { name: 'Lesson note', exact: true });
  await lesson.fill('Close when the frozen target fires; do not rewrite the thesis after the outcome.');
  await review.evaluate(node => { window.__journeyEReviewNode = node; });
  await review.getByRole('button', { name: 'Save lesson', exact: true }).click();
  await review.getByRole('status').filter({ hasText: 'Lesson saved' }).waitFor();
  assert.equal(await page.evaluate(id => {
    const current = document.querySelector('[data-campaign-review="' + id + '"]');
    return window.__journeyEReviewNode === current && current.isConnected;
  }, fixture.campaignId), true,
  'saving the lesson updates the component in place instead of repainting the SPA');
  campaignPayload.lessonNote = 'Close when the frozen target fires; do not rewrite the thesis after the outcome.';
  campaignPayload.review.lessonNote = campaignPayload.lessonNote;

  await page.click('#level-switch button[data-level="expert"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'expert'
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  const expertReview = page.locator('[data-campaign-review="' + fixture.campaignId + '"]');
  await expertReview.waitFor();
  assert.equal(await expertReview.locator('.campaign-lane-result').count(), 2,
    'Expert reads the same two result lanes');
  assert.equal(await expertReview.getByRole('textbox', { name: 'Lesson note', exact: true }).inputValue(),
    'Close when the frozen target fires; do not rewrite the thesis after the outcome.',
    'the owner-scoped lesson survives the presentation-level change');
  assert.equal(await expertReview.getByRole('button', { name: 'Save lesson', exact: true }).count(), 1,
    'Expert has the same control, not a duplicate journey');

  await page.setViewportSize({ width: 390, height: 844 });
  const mobileGeometry = await expertReview.evaluate(node => ({
    columns: getComputedStyle(node.querySelector('.campaign-review-grid')).gridTemplateColumns
      .split(' ').filter(Boolean).length,
    laneColumns: getComputedStyle(node.querySelector('.campaign-lane-results')).gridTemplateColumns
      .split(' ').filter(Boolean).length,
    overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth
  }));
  assert.equal(mobileGeometry.columns, 1, 'mobile review becomes one followable column');
  assert.equal(mobileGeometry.laneColumns, 1, 'mobile stacks the still-distinct lane cards');
  assert.ok(mobileGeometry.overflow <= 1,
    '390px review has no horizontal overflow: ' + JSON.stringify(mobileGeometry));
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'portfolio',
    'the review remains inside the canonical SPA Portfolio route');
  await captureSettled('journey-e-campaign-review-390.png');
});

test('TRADER/OWN interaction contract: vocabulary, local visual editor, and disclosure state survive levels', async () => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/portfolio/active');
  const vocabulary = await page.evaluate(() => Object.fromEntries(
    Object.entries(Learn.VOCABULARY).map(([key, value]) => [key, value.label])));
  assert.deepEqual(vocabulary, {
    assignmentCapital: 'Assignment capital', brokerReserve: 'Broker reserve',
    buyingPower: 'Buying power', netDelta: 'Net delta', gamma: 'Gamma',
    thetaPerDay: 'Theta / day', vegaPerVolPoint: 'Vega / vol pt',
    economicExposure: 'Economic exposure', theoreticalMaxLoss: 'Theoretical max loss',
    theoreticalMaxProfit: 'Theoretical max profit',
    scenarioLoss: 'Scenario loss', hypothetical: 'Hypothetical', practice: 'Practice',
    recordedAtBroker: 'Recorded at broker',
    campaignEconomicBasis: 'Campaign-adjusted economic basis', trackedTaxBasis: 'Tracked tax basis',
    campaignNetCredit: 'Campaign net credit', realizedVsHeadline: 'Realized vs headline yield',
    peakCommittedCapital: 'Peak committed capital', accumulationLedger: 'Accumulation ledger',
    counterfactualBenchmark: 'Counterfactual benchmark', churnCost: 'Churn round-trip cost',
    campaignReview: 'Close review', authoredVsRealized: 'Authored vs realized path',
    protocolAdherence: 'Protocol adherence', campaignPattern: 'Cross-campaign pattern',
    lessonNote: 'Lesson note',
    accountObjective: 'Account objective', coherenceVerdict: 'Coherence verdict',
    assignmentFit: 'Assignment fit', alertCenter: 'Needs attention', protocolBreach: 'Protocol trigger',
    pinRisk: 'Pin risk', extrinsicValue: 'Time value left', targetExposure: 'Target exposure',
    bookRisk: 'Book risk', betaWeightedDelta: 'Beta-weighted dollar delta',
    symbolConcentration: 'Symbol concentration',
    stressedAssignment: 'Stressed assignment capital', expiryCluster: 'Expiry cluster',
    themeConcentration: 'Theme concentration', pendingImport: 'Pending import',
    resolutionAuthority: 'Resolution authority', accountFingerprint: 'Source account fingerprint'
  }, 'one registry owns every capital, provenance, and basis term');
  const vocabularyHelp = await page.evaluate(() => Object.entries(Learn.VOCABULARY).map(([key, item]) => ({
    key, hasInfo: !!Learn.INFO[item.infoKey], short: Learn.INFO[item.infoKey]?.short || '',
    beginner: Learn.INFO[item.infoKey]?.beginner || '', expert: Learn.INFO[item.infoKey]?.expert || ''
  })));
  assert.deepEqual(vocabularyHelp.filter(item => !item.hasInfo || item.short.length < 20
    || item.beginner.length < 30 || item.expert.length < 30), [],
  'every canonical product term owns useful level-specific help in the one explanation registry');
  const editorContracts = await page.evaluate(() => {
    const text = '-2 XSP 13Jul27 500P x10 @12.34 CLOSE 1256\n+10 XSP @499.50 CLOSE';
    const parsed = PositionEditor.parseTerminal(text);
    const roundTrip = PositionEditor.parseTerminal(PositionEditor.terminalText(parsed));
    const restored = PositionEditor.cleanDraft({ occurredAt: '', legs: [{ instrumentType: 'OPTION',
      action: 'BUY', positionEffect: 'OPEN', symbol: 'XSP', optionType: 'CALL', strike: '500',
      expiration: '2027-07-13', quantity: '', multiplier: '', price: '' }] }, {});
    const analysis = PositionEditor.analysisPayload(PositionEditor.cleanDraft({ legs: [{ instrumentType: 'OPTION',
      action: 'BUY', positionEffect: 'OPEN', symbol: 'XSP', optionType: 'CALL', strike: '500',
      expiration: '2027-07-13', quantity: 1, multiplier: 100, price: '' }] }, {}));
    let blankFeeError = null;
    try {
      PositionEditor.recordPayload(PositionEditor.cleanDraft({ fillNature: 'EXECUTED', occurredAt: '2026-07-13T12:00:00Z',
        source: 'MANUAL', legs: [{ instrumentType: 'OPTION', action: 'BUY', positionEffect: 'OPEN',
          symbol: 'XSP', optionType: 'CALL', strike: '500', expiration: '2027-07-13', quantity: 1,
          multiplier: 100, price: '12.34' }] }, { allowRecord: true }));
    } catch (error) { blankFeeError = error.message; }
    let multiSymbolError = null;
    try {
      PositionEditor.analysisPayload(PositionEditor.cleanDraft({ legs: [
        { instrumentType: 'STOCK', action: 'BUY', positionEffect: 'OPEN', symbol: 'AAPL', quantity: 100, multiplier: 1, price: '250' },
        { instrumentType: 'OPTION', action: 'SELL', positionEffect: 'OPEN', symbol: 'MSFT', optionType: 'CALL',
          strike: '500', expiration: '2027-07-13', quantity: 1, multiplier: 100, price: '12.34' }
      ] }, {}));
    } catch (error) { multiSymbolError = error.message; }
    return {
      effects: roundTrip.map(leg => leg.positionEffect), section1256: roundTrip[0].section1256,
      multipliers: roundTrip.map(leg => leg.multiplier), occurredAt: restored.occurredAt,
      quantity: restored.legs[0].quantity, multiplier: restored.legs[0].multiplier,
      analysisKeys: Object.keys(analysis).sort(), blankFeeError, multiSymbolError,
      closeReason: PositionEditor.localPayoffUnavailableReason({ legs: parsed }),
      calendarReason: PositionEditor.localPayoffUnavailableReason({ legs: [
        { instrumentType: 'OPTION', positionEffect: 'OPEN', expiration: '2027-07-13' },
        { instrumentType: 'OPTION', positionEffect: 'OPEN', expiration: '2027-08-13' }
      ] })
    };
  });
  assert.deepEqual(editorContracts.effects, ['CLOSE', 'CLOSE'], 'Terminal preserves OPEN/CLOSE authority');
  assert.equal(editorContracts.section1256, true, 'Terminal preserves explicit Section 1256 authority');
  assert.deepEqual(editorContracts.multipliers, [10, 1], 'Terminal preserves option and stock multipliers');
  assert.deepEqual([editorContracts.occurredAt, editorContracts.quantity, editorContracts.multiplier], ['', '', ''],
    'restoring a draft never manufactures time, quantity, or multiplier facts');
  assert.deepEqual(editorContracts.analysisKeys,
    ['feesOverrideCents', 'fillNature', 'legs', 'proposedNetCents', 'qty', 'source', 'strategy', 'symbol'],
    'the editor emits only the current StrikeBench analysis contract');
  assert.match(editorContracts.blankFeeError, /exact total fees[\s\S]*explicit 0\.00/i,
    'Record never turns an unknown fee into a fabricated zero');
  assert.match(editorContracts.multiSymbolError, /one underlying per position package/i,
    'Analyze never silently prices another symbol against the first symbol quote');
  assert.match(editorContracts.closeReason, /existing position/i,
    'closing activity cannot receive a misleading standalone terminal-payoff chart');
  assert.match(editorContracts.calendarReason, /Calendars and diagonals/i,
    'mixed expirations cannot receive a misleading single-expiry payoff chart');
  const excessiveMultiplier = await page.evaluate(() => {
    try {
      PositionEditor.analysisPayload(PositionEditor.cleanDraft({ legs: [{ instrumentType: 'OPTION',
        action: 'BUY', positionEffect: 'OPEN', symbol: 'XSP', optionType: 'CALL', strike: '500',
        expiration: '2027-07-13', quantity: 1, multiplier: 10001, price: '' }] }, {}));
      return null;
    } catch (error) { return error.message; }
  });
  assert.match(excessiveMultiplier, /Complete the symbol, quantity/i,
    'the client rejects a multiplier beyond the same 10,000 bound as the server');
  const stockMultiplierError = await page.evaluate(() => {
    try {
      PositionEditor.analysisPayload(PositionEditor.cleanDraft({ legs: [{ instrumentType: 'STOCK',
        action: 'BUY', positionEffect: 'OPEN', symbol: 'AAPL', quantity: 500, multiplier: 100,
        price: '250' }] }, {}));
      return null;
    } catch (error) { return error.message; }
  });
  assert.match(stockMultiplierError, /Complete the symbol, quantity/i,
    'the shared editor represents stock as exact shares with multiplier one, never as an ambiguous lot');
  const disclosureIdentity = await page.evaluate(() => {
    const makePair = () => {
      const host = document.createElement('div');
      host.append(UI.expandable('Repeated disclosure', () => document.createTextNode('first')),
        UI.expandable('Repeated disclosure', () => document.createTextNode('second')));
      document.body.appendChild(host);
      return host;
    };
    UI.beginExpandableRender();
    const before = makePair();
    before.querySelectorAll('.xp-head')[0].click();
    before.remove();
    UI.beginExpandableRender();
    const after = makePair();
    const state = Array.from(after.querySelectorAll('.xp')).map(node => node.classList.contains('open'));
    after.remove();
    return state;
  });
  assert.deepEqual(disclosureIdentity, [true, false],
    'identical disclosure headings retain independent state across a repaint');
  const viewportDisclosure = await page.evaluate(() => {
    function mount(label) {
      const host = document.createElement('div');
      host.appendChild(UI.expandable(label, () => document.createTextNode('detail'),
        { open: 'desktop', stateKey: 'viewport-disclosure-contract' }));
      document.body.appendChild(host);
      return host;
    }
    const first = mount('Dynamic count (3)');
    const openedByDesktop = first.querySelector('.xp').classList.contains('open');
    first.querySelector('.xp-head').click();
    first.remove();
    const second = mount('Dynamic count (9)');
    const userChoiceSurvivesLabelChange = !second.querySelector('.xp').classList.contains('open');
    const stableKey = second.querySelector('.xp').dataset.expandableKey;
    second.remove();
    return { openedByDesktop, userChoiceSurvivesLabelChange,
      stableKey: /viewport-disclosure-contract/.test(stableKey || '') };
  });
  assert.deepEqual(viewportDisclosure,
    { openedByDesktop: true, userChoiceSurvivesLabelChange: true, stableKey: true },
  'desktop defaults and explicit user choices use stable disclosure identity rather than changing labels');
  // This contract asserts a rejected proposal against a Plan with no prior selection. Give it
  // a distinct durable identity so an earlier AAPL/bullish recommendation test cannot be reused
  // by the product's intentional equivalent-Plan deduplication.
  const editorPlan = await openPlan('AAPL', 'strategy', 'DIRECTIONAL', 'bullish', '43d');
  await page.click('#plan-tool-yourTrade');
  await page.waitForSelector('#plan-strategy-body .position-editor');
  await page.waitForSelector('#plan-strategy-body .position-chain-contract');
  await page.locator('#plan-strategy-body .position-chain-contract').first().click();
  await page.waitForFunction(() => document.querySelector('#plan-strategy-body .position-leg .position-strike-select')?.value);
  assert.equal(await page.locator('#plan-strategy-body .position-leg input[type="number"][placeholder="980"]').count(), 0,
    'visual mode uses chain-snapped strike selectors instead of free-number entry');
  assert.ok(await page.locator('#plan-strategy-body .position-leg .position-expiration-select option').count() > 2,
    'each visual leg owns its listed expiration choices');
  await page.waitForSelector('#plan-strategy-body .position-editor-visual svg.chart');
  await page.evaluate(() => {
    window.__positionEditorShell = document.querySelector('#plan-strategy-body .position-editor');
    window.__positionChartRegion = document.querySelector('#plan-strategy-body .position-chart-region');
  });
  const legCountBeforeAdd = await page.locator('#plan-strategy-body .position-leg').count();
  await page.getByRole('button', { name: '+ Add leg', exact: true }).click();
  await page.waitForFunction(expected => document.querySelectorAll('#plan-strategy-body .position-leg').length === expected,
    legCountBeforeAdd + 1);
  await page.waitForTimeout(260);
  const incrementalAdd = await page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#plan-strategy-body .position-leg'));
    const added = rows[rows.length - 1];
    const chartRegion = document.querySelector('#plan-strategy-body .position-chart-region');
    const remove = rows[0].querySelector('.position-leg-remove');
    const typeField = Array.from(rows[0].querySelectorAll('label')).find(label => /^Type\b/.test(label.textContent.trim()));
    const removeBox = remove.getBoundingClientRect();
    const typeBox = typeField && typeField.getBoundingClientRect();
    const overlaps = typeBox && !(removeBox.right <= typeBox.left || removeBox.left >= typeBox.right
      || removeBox.bottom <= typeBox.top || removeBox.top >= typeBox.bottom);
    const box = added.getBoundingClientRect();
    return {
      shellPreserved: window.__positionEditorShell === document.querySelector('#plan-strategy-body .position-editor'),
      chartRegionPreserved: window.__positionChartRegion === chartRegion,
      chartVisible: !!chartRegion.querySelector('svg.chart'),
      partial: chartRegion.classList.contains('is-partial'),
      highlighted: added.classList.contains('arrival-highlight'),
      focused: added.contains(document.activeElement),
      inViewport: box.top < innerHeight && box.bottom > 0,
      removeInHeader: !!remove.closest('.position-leg-head'),
      removeOverlapsType: !!overlaps
    };
  });
  assert.deepEqual(incrementalAdd, {
    shellPreserved: true, chartRegionPreserved: true, chartVisible: true, partial: true,
    highlighted: true, focused: true, inViewport: true, removeInHeader: true, removeOverlapsType: false
  }, 'adding a leg updates one row in place, brings it to the user, and keeps a plainly partial payoff visible');
  await page.locator('#plan-strategy-body .position-leg').last().getByRole('button', { name: /Remove leg/ }).click();
  await page.waitForFunction(expected => document.querySelectorAll('#plan-strategy-body .position-leg').length === expected,
    legCountBeforeAdd);
  await page.evaluate(() => { window.__mountedExactEditor = document.querySelector('#plan-strategy-panel-yourTrade .position-editor'); });
  await page.locator('.plan-tool[data-strategy-tool="builder"]').click();
  await page.waitForSelector('#plan-strategy-panel-builder:not([hidden])');
  assert.equal(await page.locator('#plan-strategy-panel-yourTrade[hidden] .position-editor').count(), 1,
    'switching to Builder hides the exact-package workspace without destroying it');
  await page.locator('.plan-tool[data-strategy-tool="yourTrade"]').click();
  await page.waitForSelector('#plan-strategy-panel-yourTrade:not([hidden]) .position-editor');
  assert.equal(await page.evaluate(() => window.__mountedExactEditor
    === document.querySelector('#plan-strategy-panel-yourTrade .position-editor')), true,
  'Builder and Your Trade are persistent views over their own work instead of mutually destructive remounts');
  assert.equal(await page.locator('#plan-strategy-panel-yourTrade .position-leg').count(), legCountBeforeAdd,
    'the exact package remains intact after visiting the guided Builder');
  await page.waitForSelector('#plan-strategy-body .strike-grip');
  const strikeBeforeKeyboardDrag = await page.locator('#plan-strategy-body .position-leg .position-strike-select').first().inputValue();
  const strikeHandle = page.locator('#plan-strategy-body .strike-grip').first();
  await strikeHandle.focus();
  await strikeHandle.press('ArrowRight');
  await strikeHandle.press('Enter');
  await page.waitForFunction(before => {
    const input = document.querySelector('#plan-strategy-body .position-leg .position-strike-select');
    return input && input.value !== before;
  }, strikeBeforeKeyboardDrag);
  const strikeAfterKeyboardDrag = await page.locator('#plan-strategy-body .position-leg .position-strike-select').first().inputValue();
  assert.notEqual(strikeAfterKeyboardDrag, strikeBeforeKeyboardDrag,
    'the accessible strike handle commits a neighboring listed strike into the shared leg state');
  await page.locator('#plan-strategy-body textarea[placeholder="Optional context"]').fill('Phase 3 draft survives reload and tabs');
  await page.evaluate(() => Workspace.save());
  await page.waitForTimeout(1800);
  const remoteDraft = await page.evaluate(async planId => {
    const workspace = await API.getFresh('/api/workspace');
    const state = workspace && workspace.state || {};
    return state.forms && state.forms.positionDrafts && state.forms.positionDrafts['plan:' + planId];
  }, editorPlan.id);
  assert.equal(remoteDraft && remoteDraft.notes, 'Phase 3 draft survives reload and tabs',
    'the current draft reaches durable workspace state instead of depending on an unload race');
  await page.reload();
  await page.waitForSelector('#app[data-ready="true"] #plan-strategy-body .position-editor');
  assert.equal(await page.locator('#plan-strategy-body textarea[placeholder="Optional context"]').inputValue(),
    'Phase 3 draft survives reload and tabs', 'reload restores the exact shared editor draft');
  assert.equal(await page.locator('#plan-strategy-body .position-leg .position-strike-select').first().inputValue(),
    strikeAfterKeyboardDrag, 'reload restores the chain-selected and handle-adjusted strike');
  assert.equal(await page.locator('#plan-tool-yourTrade').getAttribute('aria-pressed'), 'true',
    'reload returns to the editor rather than hiding the restored draft behind another Strategy tool');
  const listed = await page.evaluate(async () => {
    const research = await API.getFresh('/api/research/AAPL');
    const expiration = research.expirations[Math.min(2, research.expirations.length - 1)];
    const chain = await API.getFresh('/api/research/AAPL/chain?expiration=' + encodeURIComponent(expiration));
    const puts = (chain.puts || []).filter(row => Number.isFinite(Number(row.strike)))
      .sort((a, b) => Number(a.strike) - Number(b.strike));
    const highIndex = Math.max(1, Math.floor(puts.length / 2));
    const low = puts[highIndex - 1], high = puts[highIndex];
    return { expiration, low: Number(low.strike), high: Number(high.strike),
      lowAsk: Number(low.ask), highBid: Number(high.bid) };
  });
  assert.ok(listed.expiration && Number.isFinite(listed.low) && Number.isFinite(listed.high)
    && listed.lowAsk > 0 && listed.highBid > listed.lowAsk,
  'fixture chain supplies an executable two-contract credit package for the shared editor');
  await page.getByRole('button', { name: 'Terminal', exact: true }).click();
  const terminal = '-20 AAPL ' + listed.expiration + ' ' + listed.high + 'P @' + listed.highBid.toFixed(2) + '\n'
    + '+20 AAPL ' + listed.expiration + ' ' + listed.low + 'P @' + listed.lowAsk.toFixed(2);
  await page.locator('#plan-strategy-body .position-terminal').fill(terminal);
  await page.getByRole('button', { name: 'Apply lines', exact: true }).click();
  await page.waitForSelector('#plan-strategy-body .position-editor-visual svg.chart');
  await page.waitForFunction(() => /Bull put \(credit\) spread/i.test(
    document.querySelector('#plan-strategy-body .position-identity')?.textContent || ''));
  assert.match(await page.locator('#plan-strategy-body .position-identity').innerText(),
    /Hypothetical[\s\S]*structure identified[\s\S]*Bull put \(credit\) spread/i,
  'leg edits are debounced into the one server-owned catalog before a pricing analysis');
  assert.doesNotMatch(await page.getAttribute('#plan-strategy-body .position-editor-visual path.line', 'd'), /NaN/,
    'the exact 20-lot payoff never writes invalid path coordinates');
  await page.getByRole('button', { name: 'Visual', exact: true }).click();
  assert.equal(await page.locator('#plan-strategy-body .position-leg').count(), 2,
    'the visual view receives the same two edited legs');
  await page.getByRole('button', { name: 'Terminal', exact: true }).click();
  assert.equal((await page.locator('#plan-strategy-body .position-terminal').inputValue()).trim(), terminal,
    'visual and terminal entry preserve one shared draft');
  const beforeConstraint = await page.evaluate(async planId => {
    const plan = await PlanStore.get(planId, true);
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    return { version: plan.version, selected: latest.selected && latest.selected.id };
  }, editorPlan.id);
  await page.getByRole('spinbutton', { name: 'Package net $', exact: true }).fill('99999');
  const [optimisticResponse] = await Promise.all([
    page.waitForResponse(response => response.url().includes('/api/plans/' + editorPlan.id + '/strategy/custom')
      && response.request().method() === 'POST'),
    page.getByRole('button', { name: 'Analyze and use in this Plan', exact: true }).click()
  ]);
  assert.equal(optimisticResponse.status(), 200, 'an optimistic proposed limit still receives a complete analysis');
  await page.waitForSelector('#plan-strategy-body .position-editor-result:has-text("Not selected in this Plan")');
  assert.match(await page.locator('#plan-strategy-body .position-editor-result').innerText(),
    /(?:does not model resting limit orders|cannot claim this paper order filled)[\s\S]*did not become the Plan structure/i,
  'an impossible limit stays visible and plainly names non-selection');
  assert.equal(await page.getByRole('button', { name: 'Continue to Outcomes', exact: true }).count(), 0,
    'a constrained proposed limit cannot advance as though it were selected');
  const afterConstraint = await page.evaluate(async planId => {
    const plan = await PlanStore.get(planId, true);
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    return { version: plan.version, selected: latest.selected && latest.selected.id };
  }, editorPlan.id);
  assert.equal(afterConstraint.version, beforeConstraint.version,
    'a constrained analysis with no prior selection does not rewrite Plan state');
  assert.equal(afterConstraint.selected, null,
    'a constrained analysis cannot leave an older, unseen package selected');
  await page.getByRole('spinbutton', { name: 'Package net $', exact: true }).fill('');
  const [analysisResponse] = await Promise.all([
    page.waitForResponse(response => response.url().includes('/api/plans/' + editorPlan.id + '/strategy/custom')
      && response.request().method() === 'POST'),
    page.getByRole('button', { name: 'Analyze and use in this Plan', exact: true }).click()
  ]);
  assert.equal(analysisResponse.status(), 200, 'the shared Plan editor reaches the real exact-package endpoint');
  const analysisBody = await analysisResponse.json();
  assert.equal(analysisBody.identity && analysisBody.identity.family, 'CREDIT_PUT_SPREAD',
    'the server-owned catalog identifies the exact terminal package');
  await page.waitForSelector('#plan-strategy-body .position-identity:has-text("Bull put (credit) spread")');
  await page.waitForSelector('#plan-strategy-body .position-editor-result .inline-action-feedback');
  assert.match(await page.locator('#plan-strategy-body .position-editor-result').innerText(),
    /Analysis complete[\s\S]*Theoretical max loss[\s\S]*Market-implied EV after costs[\s\S]*WHAT THIS POSITION ACTUALLY EXPRESSES[\s\S]*Data coverage for this analysis[\s\S]*Portfolio impact[\s\S]*Entry-price receipt[\s\S]*Continue to Outcomes/i,
  'Analyze reports exact-package economics, behavior, coverage, portfolio impact, price provenance, and an in-view next action');
  for (const key of ['hypothetical', 'economicExposure', 'scenarioLoss']) {
    assert.ok(await page.locator('#plan-strategy-body [data-vocabulary="' + key + '"]').count() > 0,
      key + ' is wired to a visible registry-backed label in the shared editor');
  }
  await assertNoInternalChrome('#plan-strategy-body');
  const selectedExactPackage = await page.evaluate(async planId => {
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    return {
      id: latest.selected && latest.selected.id,
      family: latest.selected && latest.selected.strategy,
      legs: latest.selected && latest.selected.legs && latest.selected.legs.length
    };
  }, editorPlan.id);
  assert.ok(selectedExactPackage.id && selectedExactPackage.family === 'CREDIT_PUT_SPREAD'
    && selectedExactPackage.legs === 2,
  'the exact edited package becomes the durable Plan selection: ' + JSON.stringify(selectedExactPackage));
  const versionBeforeRejectedReplacement = await page.evaluate(async planId =>
    (await PlanStore.get(planId, true)).version, editorPlan.id);
  await page.getByRole('spinbutton', { name: 'Package net $', exact: true }).fill('99999');
  await Promise.all([
    page.waitForResponse(response => response.url().includes('/api/plans/' + editorPlan.id + '/strategy/custom')
      && response.request().method() === 'POST'),
    page.getByRole('button', { name: 'Analyze and use in this Plan', exact: true }).click()
  ]);
  await page.waitForSelector('#plan-strategy-body .position-editor-result:has-text("The prior selection was cleared")');
  const rejectedReplacement = await page.evaluate(async planId => ({
    plan: await PlanStore.get(planId, true),
    latest: await API.getFresh('/api/plans/' + planId + '/strategy/latest')
  }), editorPlan.id);
  assert.equal(rejectedReplacement.plan.version, versionBeforeRejectedReplacement + 1,
    'rejecting a replacement clears the old structure in one versioned write');
  assert.equal(rejectedReplacement.latest.selected, null,
    'the Plan never evaluates an older hidden structure after a replacement fails');
  await page.getByRole('spinbutton', { name: 'Package net $', exact: true }).fill('');
  await Promise.all([
    page.waitForResponse(response => response.url().includes('/api/plans/' + editorPlan.id + '/strategy/custom')
      && response.request().method() === 'POST'),
    page.getByRole('button', { name: 'Analyze and use in this Plan', exact: true }).click()
  ]);
  await page.waitForSelector('#plan-strategy-body .position-editor-result:has-text("Analysis complete")');
  await page.evaluate(id => { delete App.state.positionDrafts['plan:' + id]; }, editorPlan.id);
  await page.evaluate(() => App.render());
  await page.waitForSelector('#app[data-ready="true"] #plan-strategy-body .position-editor');
  await page.getByRole('button', { name: 'Terminal', exact: true }).click();
  assert.match(await page.locator('#plan-strategy-body .position-terminal').inputValue(), /-20 AAPL/,
    'a selected package seeds the shared editor after its local draft is cleared');
  await page.getByRole('button', { name: 'Visual', exact: true }).click();
  await page.waitForSelector('#plan-strategy-body .position-editor-visual svg.chart');
  const beginnerLayout = await page.locator('.position-entry-workbench').evaluate(node => {
    const legs = node.querySelector('.position-entry-legs').getBoundingClientRect();
    const visual = node.querySelector('.position-entry-visual').getBoundingClientRect();
    const command = node.querySelector('[data-position-command="analyze"]').getBoundingClientRect();
    return { columns: getComputedStyle(node).gridTemplateColumns.split(' ').length,
      aligned: Math.abs(legs.top - visual.top) <= 2,
      actionWithinWorkbenchViewport: command.bottom <= node.getBoundingClientRect().top + 800 };
  });
  assert.deepEqual(beginnerLayout, { columns: 2, aligned: true, actionWithinWorkbenchViewport: true },
    'Beginner gets the same legs-and-payoff workbench with its primary action in the first workbench viewport');
  await captureSettled('trader-own-p3-editor-beginner.png');

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#app[data-ready="true"] #plan-strategy-body .position-editor');
  await page.getByRole('button', { name: 'Terminal', exact: true }).click();
  assert.match(await page.locator('#plan-strategy-body .position-terminal').inputValue(), /-20 AAPL/,
    'the edited package survives a full level-driven repaint');
  const expertColumns = await page.locator('.position-entry-workbench').evaluate(node =>
    getComputedStyle(node).gridTemplateColumns.split(' ').length);
  assert.equal(expertColumns, 2, 'Expert places the payoff beside the exact legs');
  await page.waitForSelector('#plan-strategy-body .position-editor-visual svg.chart');
  assert.match(await page.locator('#plan-strategy-body .position-editor-result').innerText(),
    /POSITION STANCE[\s\S]*Dollar delta[\s\S]*Vega \/ vol point[\s\S]*Data coverage for this analysis[\s\S]*Portfolio impact/i,
    'Expert reloads the same exact assessment with dense stance, coverage, and lane impact detail');
  await captureSettled('trader-own-p3-editor-expert.png');

  await page.evaluate(async () => { Learn.setLevel('beginner'); await App.render(); });
  await page.waitForSelector('#app[data-ready="true"] #plan-strategy-body .position-editor');
  assert.match(await page.locator('#plan-strategy-body .position-terminal').inputValue(), /\+20 AAPL/,
    'the shared draft survives the complete Beginner/Expert round trip');
  assert.match(await page.locator('#plan-strategy-body .position-editor-result').innerText(),
    /Selected in this Plan[\s\S]*Market-implied EV after costs[\s\S]*WHAT THIS POSITION ACTUALLY EXPRESSES[\s\S]*Data coverage for this analysis[\s\S]*Continue to Outcomes/i,
  'the selected exact package keeps its complete assessment and next action after a level repaint');
  const selectedPackageNet = await page.getByRole('spinbutton', { name: 'Package net $', exact: true }).inputValue();
  await page.getByRole('spinbutton', { name: 'Package net $', exact: true }).fill('0.01');
  const staleSelection = await page.locator('#plan-strategy-body .position-editor-result').evaluate(node => ({
    stale: node.classList.contains('is-stale'),
    text: node.textContent,
    continueDisabled: node.querySelector('button') && node.querySelector('button').disabled
  }));
  assert.equal(staleSelection.stale, true, 'editing marks the retained result stale in place');
  assert.match(staleSelection.text, /Preview needs another analysis[\s\S]*Selected in this Plan/i,
    'the prior selection stays visible for comparison with an explicit stale warning');
  assert.equal(staleSelection.continueDisabled, true,
    'a stale retained selection cannot advance to Outcomes as though it matched the edited package');
  await page.getByRole('spinbutton', { name: 'Package net $', exact: true }).fill(selectedPackageNet);
  assert.equal(await page.locator('#plan-strategy-body .position-editor-result.is-stale').count(), 0,
    'restoring the exact selected fields reactivates the matching result without a repaint');
  await page.evaluate(() => App.render());
  await page.waitForSelector('#app[data-ready="true"] #plan-strategy-body .position-editor-result:has-text("Selected in this Plan")');
  await page.getByRole('button', { name: 'Visual', exact: true }).click();
  await page.waitForSelector('#plan-strategy-body .position-editor-visual svg.chart');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(100);
  const mobile = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    columns: getComputedStyle(document.querySelector('.position-entry-workbench')).gridTemplateColumns.split(' ').length,
    editorRight: Math.round(document.querySelector('.position-entry-workbench').getBoundingClientRect().right),
    appRight: Math.round(document.getElementById('app').getBoundingClientRect().right),
    visualBeforeLegs: document.querySelector('.position-entry-visual').getBoundingClientRect().top
      < document.querySelector('.position-entry-legs').getBoundingClientRect().top
  }));
  assert.ok(mobile.document <= mobile.viewport + 2 && mobile.editorRight <= mobile.appRight + 2,
    'the shared editor stays inside the mobile page: ' + JSON.stringify(mobile));
  assert.equal(mobile.columns, 1, 'mobile keeps the legs and visual in one readable sequence');
  assert.equal(mobile.visualBeforeLegs, true,
    'mobile presents the payoff and primary command before the long leg form instead of burying them below it');
  await captureSettled('trader-own-p3-editor-mobile.png');
  await page.setViewportSize({ width: 1280, height: 900 });

  const adjustedTerminal = '-1 AAPL ' + listed.expiration + ' ' + listed.high + 'P x10 @' + listed.highBid.toFixed(2) + '\n'
    + '+1 AAPL ' + listed.expiration + ' ' + listed.low + 'P x10 @' + listed.lowAsk.toFixed(2);
  await page.getByRole('button', { name: 'Terminal', exact: true }).click();
  await page.getByRole('spinbutton', { name: 'Package net $', exact: true }).fill('');
  await page.locator('#plan-strategy-body .position-terminal').fill(adjustedTerminal);
  const [adjustedResponse] = await Promise.all([
    page.waitForResponse(response => response.url().includes('/api/plans/' + editorPlan.id + '/strategy/custom')
      && response.request().method() === 'POST'),
    page.getByRole('button', { name: 'Analyze and use in this Plan', exact: true }).click()
  ]);
  assert.equal(adjustedResponse.status(), 200,
    'an adjusted x10 package reaches the same exact-package analysis endpoint');
  await page.waitForSelector('#plan-strategy-body .position-editor-result:has-text("Analysis complete")');
  const adjustedSelection = await page.evaluate(async planId => {
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    return {
      multipliers: (latest.selected && latest.selected.legs || []).map(leg => leg.multiplier),
      maxLossCents: latest.selected && latest.selected.maxLossCents,
      entryNetCents: latest.selected && latest.selected.entryNetPremiumCents
    };
  }, editorPlan.id);
  assert.deepEqual(adjustedSelection.multipliers, [10, 10],
    'normalized Plan storage preserves adjusted deliverables after the response/reload boundary');
  assert.ok(adjustedSelection.maxLossCents > 0 && Number.isInteger(adjustedSelection.entryNetCents),
    'the adjusted package returns exact decision money, not a visual-only approximation');
  await page.evaluate(() => App.render());
  await page.waitForSelector('#app[data-ready="true"] #plan-strategy-body .position-editor-result:has-text("Selected in this Plan")');
  assert.match(await page.locator('#plan-strategy-body .position-terminal').inputValue(), /x10/,
    'the adjusted draft and durable selected confirmation survive a full app repaint');
  await page.evaluate(id => { delete App.state.positionDrafts['plan:' + id]; }, editorPlan.id);
});

test('explanation system: visible triggers, registry-backed bubbles, both levels, no title dups', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  // Walk the current owners before auditing the registry so lazy-rendered terms are included.
  for (const hash of ['#/data/simulation', '#/portfolio/account']) {
    await go(hash);
    await page.waitForTimeout(250);
  }
  // The sim workbench must register its own jargon.
  const preTerms = await page.evaluate(() => (window.__usedInfoTerms || []).slice());
  assert.ok(preTerms.includes('scenario'), 'sim workbench wires info(scenario)');
  assert.ok(preTerms.includes('speed'), 'sim workbench wires info(speed)');
  const plan = await openPlan('AAPL', 'strategy', 'DIRECTIONAL', 'bullish', '57d');
  await page.waitForSelector('#plan-run-strategy');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .ranked-idea-hero', { timeout: 30000 });
  await page.evaluate(async planId => {
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    const candidate = latest.strategy.result.candidates.find(item => item.id && (() => {
      const expirations = new Set((item.legs || []).filter(leg => leg.type !== 'STOCK').map(leg => leg.expiration));
      return expirations.size === 1;
    })());
    if (!candidate) throw new Error('The explanation audit needs one persisted single-expiration candidate.');
    const live = await PlanStore.get(planId, true);
    await PlanStore.selectCandidate(live, candidate.id);
  }, plan.id);
  await go('#/plan/' + plan.id + '/decide');
  await page.waitForSelector('#plan-review-order');
  await page.click('#plan-review-order');
  await page.waitForSelector('#plan-decision-review .info-trigger', { state: 'attached', timeout: 30000 });
  // AUDIT 1: every used term key resolves in the registry (anti-drift).
  const missing = await page.evaluate(() =>
    (window.__usedInfoTerms || []).filter(k => !(window.Learn && Learn.INFO && Learn.INFO[k])));
  assert.deepEqual(missing, [], 'info terms missing from the registry: ' + missing.join(','));
  const additiveHelp = await page.evaluate(() => ({
    nlv: Learn.INFO.nlv.short,
    riskcapital: Learn.INFO.riskcapital.short,
    marketengine: Learn.INFO.marketengine.short,
    dayrange: Learn.INFO.dayrange.short,
    hv30: Learn.INFO.hv30.short,
    requestbudget: Learn.INFO.requestbudget.short,
    synccursor: Learn.INFO.synccursor.short,
    marketanchor: Learn.INFO.marketanchor.short
  }));
  assert.match(additiveHelp.nlv, /denominator|not the cash/i);
  assert.match(additiveHelp.riskcapital, /tighter|acknowledgment/i);
  assert.match(additiveHelp.marketengine, /refresh|stale/i);
  assert.match(additiveHelp.dayrange, /not as a forecast/i);
  assert.match(additiveHelp.hv30, /does not prove an edge/i);
  assert.match(additiveHelp.requestbudget, /pauses provider work/i);
  assert.match(additiveHelp.synccursor, /not downloaded again/i);
  assert.match(additiveHelp.marketanchor, /never rewrites the observed/i);
  // AUDIT 2: on a fine-pointer desktop the injected icon is visually removed (no baseline break)
  // but present + keyboard-reachable; the LABEL host carries the hover explanation. It never
  // pairs with a competing native title tooltip.
  const trig = page.locator('#plan-decision-review .info-trigger[data-term="pop"]').first();
  assert.equal(await trig.count(), 1, 'the info trigger is present in the DOM');
  // sr-only clips it to 1px (Playwright's isVisible still reports a 1px box as "visible", so
  // measure the flow width): the icon is pulled out of the text flow — no baseline break.
  const trigWidth = await trig.evaluate(el => el.getBoundingClientRect().width);
  assert.ok(trigWidth <= 2,
    'the injected icon is visually removed on desktop — the label carries the hover explanation');
  const hostMarked = await trig.evaluate(el => {
    var h = el.closest('.has-info'); return !!h || (el.parentElement && el.parentElement.classList.contains('has-info'));
  });
  assert.ok(hostMarked, 'the trigger’s label host is marked as the hover target');
  const dup = await page.$$eval('#plan-decision-review .info-trigger', els =>
    els.filter(e => e.closest('[title]')).length);
  assert.equal(dup, 0, 'a bubble label must not also carry a native title tooltip');
  // AUDIT 3: activating the (present, keyboard-reachable) trigger opens the bubble immediately;
  // one-liner first; [+] expands the BEGINNER detail. force:true because the icon is visually
  // clipped on desktop (the label hover is the pointer path; this exercises the tap/keyboard path).
  await trig.evaluate(el => el.click());
  await page.waitForSelector('#info-pop');
  const shortText = await page.textContent('#info-pop .info-short');
  assert.ok(shortText.length > 20, 'one-liner present');
  assert.match(shortText, /\$1 gain|compare/i,
    'the POP one-liner adds a decision distinction instead of repeating its label');
  assert.equal(await page.isVisible('#info-pop .info-detail'), false, 'detail starts collapsed');
  await page.click('#info-pop .info-expand');
  const begDetail = await page.textContent('#info-pop .info-detail');
  assert.ok(begDetail.length > 40, 'beginner detail present');
  // Escape closes AND returns focus to the trigger (keyboard a11y).
  await page.keyboard.press('Escape');
  const distinctProbabilityHelp = await page.evaluate(() => ({
    profit: Learn.INFO.pop.short,
    fullWin: Learn.INFO.pmaxprofit.short,
    fullLoss: Learn.INFO.pmaxloss.short
  }));
  assert.match(distinctProbabilityHelp.fullWin, /ordinary profitable outcomes are excluded/i);
  assert.match(distinctProbabilityHelp.fullLoss, /smaller losses are excluded/i);
  assert.equal(await page.locator('#info-pop').count(), 0, 'Escape closes the bubble');
  // KEYBOARD PATH: focus opens the bubble, Tab reaches [+] (DOM-adjacent, not body-appended).
  // Escape already returned focus to the trigger, so blur first — refocusing a focused element
  // fires no focus event (and must not: that's the no-reopen-after-Escape contract).
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  await page.waitForTimeout(150); // let the post-Escape reopen suppression window lapse
  await trig.focus();
  await page.waitForSelector('#info-pop');
  await page.keyboard.press('Tab');
  const onExpand = await page.evaluate(() =>
    document.activeElement && document.activeElement.classList.contains('info-expand'));
  assert.ok(onExpand, 'Tab from the trigger must land on the [+] expand control');
  // aria contract: trigger references the bubble and reports expanded state.
  assert.equal(await trig.getAttribute('aria-expanded'), 'true');
  assert.equal(await trig.getAttribute('aria-describedby'), 'info-pop');
  // Scrolling RE-ANCHORS the fixed-position bubble to its trigger (never floats over other
  // numbers); scrolling the trigger fully out of view closes it.
  await page.mouse.wheel(0, 120);
  await page.waitForTimeout(120);
  const anchored = await page.evaluate(() => {
    const pop = document.getElementById('info-pop');
    const t = document.querySelector('[aria-describedby="info-pop"]');
    if (!pop || !t) return pop === null; // closed (trigger left view) is also acceptable
    const pr = pop.getBoundingClientRect(), tr = t.getBoundingClientRect();
    return Math.abs(pr.top - tr.bottom) < 80 || Math.abs(tr.top - pr.bottom) < 80;
  });
  assert.ok(anchored, 'bubble stays anchored to its trigger (or closes) on scroll');
  await page.keyboard.press('Escape');
  // AUDIT 4: the SAME trigger at Expert level yields the expert detail (same truth, deeper words).
  await page.click('#level-switch button[data-level="expert"]');
  // Let the expert re-render settle before invoking the (freshly re-created) trigger — el.click()
  // fires immediately and must not race a detaching beginner-level node.
  await page.waitForFunction(() => window.Learn && Learn.currentLevel() === 'expert'
    && document.querySelector('#plan-decision-review .info-trigger[data-term="pop"]'));
  await page.waitForTimeout(150);
  await page.locator('#plan-decision-review .info-trigger[data-term="pop"]').first().evaluate(el => el.click());
  await page.waitForSelector('#info-pop');
  await page.click('#info-pop .info-expand');
  const expDetail = await page.textContent('#info-pop .info-detail');
  assert.ok(expDetail !== begDetail, 'expert detail differs from beginner detail');
  await page.keyboard.press('Escape');
  await page.click('#level-switch button[data-level="beginner"]');
});

test('data center tabs: overview dashboard, sources+jobs, coverage backfill, admin-only reset', async () => {
  // ROUTE-BACKED TABS (holistic review addendum A): each workspace loads only on its route,
  // Overview answers "where am I / what next", and destructive ops live ONLY under Administration.
  await page.click('#level-switch button[data-level="expert"]');
  await go('#/data/overview');
  await page.waitForSelector('#data-tabs button.active[data-tab="overview"]');
  await assertTabContracts('#app');
  await page.waitForSelector('#dc-mode .badge'); // where-you-are card leads
  assert.match(await page.textContent('#dc-mode'), /OBSERVED MARKET|DEMO MARKET|SIMULATED|SCENARIO/);
  await page.waitForSelector('#dc-engine .chip-row');
  assert.match(await page.textContent('#dc-engine'), /Market engine/);
  await page.waitForSelector('#dc-health:has-text("Quotes")', { timeout: 15000 });
  await page.route('**/api/data/jobs', async route => {
    if (route.request().method() === 'POST') {
      await route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ error: 'Quote refresh is temporarily unavailable.' }) });
    } else await route.continue();
  });
  await page.click('#dc-refresh-now');
  await page.waitForFunction(() => /Quote refresh is temporarily unavailable/.test(
    document.getElementById('toast-region')?.textContent || ''));
  assert.equal(await page.locator('#dc-refresh-now').isEnabled(), true,
    'Refresh now restores after a visible failure');
  assert.equal(await page.locator('#dc-refresh-now').getAttribute('aria-busy'), null);
  await page.unroute('**/api/data/jobs');
  await page.click('#dc-refresh-now');
  // Inactive workspaces are NOT mounted from Overview (only-active-tab loading).
  assert.equal(await page.locator('#dc-sources').count(), 0, 'sources not mounted on overview');
  assert.equal(await page.locator('#dc-reset').count(), 0, 'reset not mounted on overview');

  // Sources & jobs tab
  await page.click('#data-tabs [data-tab="sources"]');
  await page.waitForSelector('#data-csv-import .xp-head');
  await ensureExpanded(page.locator('#data-csv-import .xp-head'));
  await page.waitForSelector('#dc-history-sync #data-csv-upload');
  await assertNamedControls('#app');
  await assertTabContracts('#app');
  assert.equal(await page.locator('#data-source-detail').count(), 0,
    'source setup stays compact until the user asks for one source detail');
  await page.waitForSelector('#dc-sources .data-feed-row');
  assert.equal(await page.locator('#dc-sources .xp-head:has-text("License and usage details")').count(), 1,
    'feed identity and coverage stay visible while licensing remains secondary');
  const sources = await page.textContent('#dc-sources');
  const historySetup = await page.textContent('#dc-history-sync');
  assert.match(historySetup, /Yahoo Finance automation/);
  const readyAutomatedSources = await page.locator('#dc-history-sync .data-source-choice .badge-ok').count();
  if (!readyAutomatedSources) {
    assert.match(historySetup, /charts, HV, realized-volatility EV, and favorable observed verdicts/i,
      'when no automated source is active, the source bridge explains the product consequence of candle starvation');
  }
  assert.doesNotMatch(sources, /Yahoo Finance automation|Alpha Vantage|Stooq/,
    'daily-history connectors are not repeated in a second inventory');
  assert.match(sources, /Cboe|SEC EDGAR/);
  assert.equal((historySetup.match(/Yahoo Finance automation/g) || []).length, 1,
    'the daily source appears once in its operational workflow');
  assert.ok(await page.locator('#dc-history-sync .data-source-choice').count() >= 4, 'all automated connector choices remain visible');
  const yahooChoice = page.locator('#dc-history-sync .data-source-choice[data-source-key="yahoo"]');
  assert.equal(await yahooChoice.count(), 1);
  assert.equal(await yahooChoice.isEnabled(), true, 'setup guidance remains interactive even when a source is not eligible');
  await yahooChoice.click();
  await page.waitForSelector('#data-source-detail:has-text("Permission & use")');
  const yahooDetail = await page.textContent('#data-source-detail');
  assert.match(yahooDetail, /standing authorization|Automated collection requires permission|authorized personal use/i,
    'full rights and setup guidance is reachable from the compact source choice');
  assert.match(yahooDetail, /standing authorization.*(?:YAHOO_ENABLED=false|Fixtures-only Demo mode)/i,
    'the source detail names either its observed revocation control or the active Demo-lane exclusion');
  assert.ok(await page.locator('#dc-sources .data-feed-row').count(),
    'feed identity, status, coverage, and setup remain visible instead of hiding the entire card');
  assert.equal(await page.locator('#dc-sources .xp-head:has-text("License and usage details")').count(), 1,
    'only licensing prose remains progressively disclosed');
  assert.equal(await page.locator('#data-sync-preview').isDisabled(), true, 'Demo build cannot start an observed provider sync');
  assert.equal(await page.locator('#data-sync-start').getAttribute('aria-describedby'), 'data-sync-start-reason');
  assert.match(await page.textContent('#data-sync-start-reason'), /Preview the selected source/,
    'disabled Start update names the action needed to enable it');
  assert.ok(await page.locator('#data-csv-file').count(), 'user-owned CSV import remains available');
  assert.ok(await page.locator('#data-sync-schedule').count(), 'maintenance capability is present at expert');

  await page.waitForSelector('#dc-jobs .dc-job:has-text("refresh_now")', { timeout: 15000 });

  // Datasets hosts coverage. This suite is an explicit Demo build, so observed backfill is
  // unavailable by design rather than starting a job that can only refuse fabricated rows.
  await page.click('#data-tabs [data-tab="datasets"]');
  await page.waitForSelector('#dc-coverage #dc-backfill');
  assert.equal(await page.locator('#dc-backfill').isDisabled(), true);
  assert.match(await page.textContent('#dc-coverage'), /Observed backfill is unavailable/);

  // Administration: reset lives here and only here, typed confirmation enforced.
  await page.click('#data-tabs [data-tab="admin"]');
  await page.waitForSelector('#dc-reset-tier');
  await assertNamedControls('#app');
  await assertTabContracts('#app');
  const tiers = await page.$$eval('#dc-reset-tier option', os => os.map(o => o.value));
  assert.ok(tiers.includes('MARKET_DATA') && tiers.includes('EVERYTHING'), 'expert reset tiers');
  await page.click('#dc-reset-btn');
  await page.waitForSelector('#dc-reset-confirm');
  await page.click('#modal-confirm'); // empty confirm -> refused, modal stays
  assert.ok(await page.locator('#dc-reset-confirm').count(), 'reset refused without typing RESET');
  await page.click('.modal button:has-text("Cancel")');
  // Deep link + back/forward keep the tab.
  await go('#/data/datasets');
  await page.waitForSelector('#data-tabs button.active[data-tab="datasets"]');
  await page.goBack();
  await page.waitForSelector('#data-tabs button.active[data-tab="admin"]', { timeout: 15000 });
});

test('data center keeps every reset scope available to Beginner with plain guidance', async () => {
  await go('#/data/admin');
  await page.click('#level-switch button[data-level="beginner"]');
  // Wait for the Beginner re-render, not the lingering Expert node mid-transition.
  await page.waitForFunction(() => {
    const s = document.getElementById('dc-reset-tier');
    return s && s.options.length === 4 && document.body.classList.contains('lvl-beginner');
  }, { timeout: 15000 });
  const tiers = await page.$$eval('#dc-reset-tier option', os => os.map(o => o.value));
  assert.deepEqual(tiers, ['MARKET_DATA', 'RESEARCH', 'PAPER', 'EVERYTHING'],
    'Beginner keeps every reset capability instead of losing granular scopes');
  await go('#/data/sources');
  await page.waitForSelector('#data-csv-import .xp-head');
  await ensureExpanded(page.locator('#data-csv-import .xp-head'));
  await page.waitForSelector('#dc-history-sync #data-csv-upload');
  assert.ok(await page.locator('#dc-history-sync .data-source-choice').count() >= 4, 'beginner keeps every connector');
  assert.ok(await page.locator('#data-sync-years').count(), 'beginner keeps history window control');
  assert.ok(await page.locator('#dc-history-sync .xp-head:has-text("Keep it current automatically")').count(),
    'beginner gets the same maintenance capability through progressive disclosure');
  await go('#/data/datasets');
  await page.waitForFunction(() => !/Loading coverage/.test(document.getElementById('dc-coverage')?.textContent || ''),
    { timeout: 15000 });
  const coverageText = await page.textContent('#dc-coverage');
  if (/No stored history yet/.test(coverageText)) {
    assert.match(coverageText, /Open Sources & jobs.*eligible source.*preview exactly what will be downloaded/s,
      'an empty Beginner inventory names the acquisition path instead of offering a meaningless row detail');
    assert.ok(await page.locator('#dc-backfill').count(), 'the empty inventory keeps its history action');
  } else {
    await page.waitForSelector('#dc-coverage .xp-head:has-text("Review basis and sources")', { timeout: 15000 });
    await page.locator('#dc-coverage .xp-head').filter({ hasText: 'Review basis and sources' }).click();
    assert.match(await page.textContent('#dc-coverage'), /Basis & sources/,
      'Beginner can inspect provenance for every covered symbol');
  }
  await go('#/data/overview');
  await page.waitForSelector('#dc-health .dc-detail-toggle', { timeout: 15000 });
  await page.click('#dc-health .dc-detail-toggle');
  await page.waitForSelector('#dc-detail .xp-head:has-text("Technical performance")', { timeout: 15000 });
  assert.match(await page.textContent('#dc-detail'), /Technical performance/,
    'Beginner keeps operator diagnostics behind plain-language disclosure');
  await page.click('#level-switch button[data-level="expert"]'); // restore for later tests
});

test('non-admin Data access stays informative without dead app-wide mutation controls', async () => {
  await page.route('**/api/data/overview', async route => {
    const response = await route.fetch();
    const body = await response.json();
    body.admin = false;
    await route.fulfill({ response, json: body });
  });
  await page.evaluate(() => API.flushCache());
  await go('#/data/sources');
  await page.waitForSelector('#dc-history-sync:has-text("requires admin access")');
  assert.equal(await page.locator('#data-tabs [data-tab="admin"]:visible').count(), 0,
    'Administration is not advertised to a non-admin');
  assert.equal(await page.locator('#data-sync-preview, #data-csv-upload, #data-sync-schedule-save').count(), 0,
    'app-wide sync, import, and schedule controls are not rendered as dead actions');
  await page.waitForSelector('#dc-sources .data-feed-row');
  assert.match(await page.textContent('#dc-sources'), /Other market-data feeds/,
    'distinct supporting feeds remain readable');
  assert.match(await page.textContent('#dc-history-sync'), /History management requires admin access/,
    'the shared-history mutation boundary remains explicit');
  await page.unroute('**/api/data/overview');
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
  assert.match(await page.textContent('#stale-banner'), /restart required/i);
  assert.equal(await page.evaluate(() => App.state.serverStale), true,
    'the stale runtime is a state-changing safety boundary, not only a warning');
  const staleMutation = await page.evaluate(async () => {
    try { await API.put('/api/world', { world: 'demo' }); return 'unexpected success'; }
    catch (e) { return e.message; }
  });
  assert.match(staleMutation, /restart the app/i,
    'mutations are blocked before an old client can diverge from the server');

  // Un-break everything: retry renders the screen and the banner clears on healthy report
  await page.unroute('**/api/account');
  await page.unroute('**/api/health');
  await page.evaluate(() => App.checkServerHealth());
  await page.waitForSelector('#stale-banner', { state: 'detached' });
  assert.equal(await page.evaluate(() => App.state.serverStale), false);
  await page.click('#route-retry');
  await page.waitForSelector('#app[data-ready="true"]');
  assert.match(await page.textContent('#app'), /Buying power|Portfolio value/); // portfolio recovered
  await page.evaluate(() => fetch('/api/health'));
  // banner clears on the next periodic/error check; force one by navigating with a fresh check
  await page.evaluate(() => window.App && window.App.render());
  await page.waitForSelector('#app[data-ready="true"]');
});

test('interaction contract: clickable surfaces are keyboard-operable, errors are nonblocking, modals own focus', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  assert.equal(await page.getAttribute('#level-switch button[data-level="beginner"]', 'aria-pressed'), 'true');
  assert.equal(await page.getAttribute('#level-switch button[data-level="expert"]', 'aria-pressed'), 'false');
  for (const hash of ['#/home/tour', '#/home', '#/research', '#/portfolio']) {
    await go(hash);
    assert.equal(await page.evaluate(() => document.activeElement === document.querySelector('#app h1')), true,
      'a real route change moves focus to its page heading on ' + hash);
    assert.match(await page.textContent('#route-announcer'), /page loaded/i,
      'a real route change is announced on ' + hash);
    const violations = await page.$$eval('.clickable', nodes => nodes.filter(n => {
      const native = n.matches('a[href],button');
      const role = n.getAttribute('role');
      return !native && (!['link', 'button'].includes(role) || n.getAttribute('tabindex') !== '0');
    }).map(n => n.outerHTML.slice(0, 160)));
    assert.deepEqual(violations, [], 'dead/mouse-only clickable surface on ' + hash + ': ' + violations.join('\n'));
  }

  assert.equal(await page.locator('.skip-link').getAttribute('href'), '#app');
  await page.locator('.skip-link').focus();
  assert.equal(await page.locator('.skip-link').isVisible(), true, 'keyboard users can reveal the skip link');
  const routeBeforeSkip = await page.evaluate(() => location.hash);
  await page.locator('.skip-link').click();
  assert.equal(await page.evaluate(() => location.hash), routeBeforeSkip,
    'skip navigation does not mutate the SPA route hash');
  assert.equal(await page.evaluate(() => document.activeElement === document.getElementById('app')), true);

  const assets = await page.evaluate(async () => ({
    app: await (await fetch('/js/app.js')).text(),
    views: await (await fetch('/js/views.js')).text()
  }));
  assert.doesNotMatch(assets.app + assets.views, /\balert\s*\(/, 'async failures must use inline errors or the accessible toast');

  await go('#/portfolio/positions');
  const modalTrigger = page.locator('#buy-shares-btn');
  assert.equal(await modalTrigger.count(), 1, 'portfolio exposes one stable buy-shares command');
  await modalTrigger.click();
  const dialog = page.locator('#modal-root [role="dialog"]');
  assert.equal(await dialog.count(), 1, 'modal has dialog semantics');
  assert.equal(await dialog.getAttribute('aria-modal'), 'true');
  await assertNamedControls('#modal-root');
  assert.equal(await page.evaluate(() => document.querySelector('#modal-root [role="dialog"]').contains(document.activeElement)), true,
    'focus moves inside the modal');
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.querySelector('#modal-root [role="dialog"]'));
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), 'buy-shares-btn',
    'Escape closes and restores focus to the trigger');
});

test('destination navigation starts at the top while Research owns its explicit Back restoration', async () => {
  const plan = await openPlan('AAPL', 'strategy');
  await page.evaluate(() => window.scrollTo(0, Math.min(700, document.documentElement.scrollHeight - innerHeight)));
  assert.ok(await page.evaluate(() => window.scrollY > 100), 'the Plan stage is genuinely scrolled');
  await page.evaluate(planId => App.navigate('#/plan/' + planId + '/evidence'), plan.id);
  await page.waitForFunction(() => location.hash.endsWith('/evidence')
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.waitForFunction(() => {
    const band = document.querySelector('[data-band="evidence"]');
    if (!band) return false;
    const top = band.getBoundingClientRect().top;
    return top > -40 && top < innerHeight / 2;
  }, null, { timeout: 10000 });
  assert.ok(true, 'moving between Plan stages orients at the destination band, never a stale offset');
  await openResearchTab('overview');
  await page.waitForSelector('#history-card .chart-wrap');
  await page.evaluate(() => window.scrollTo(0, Math.min(700, document.documentElement.scrollHeight - innerHeight)));
  assert.ok(await page.evaluate(() => window.scrollY > 100), 'source screen is genuinely scrolled');

  // Exercise a native hash transition, not App.navigate: both paths share the same contract.
  await page.evaluate(() => {
    document.getElementById('app').setAttribute('data-ready', 'false');
    location.hash = '#/data/simulation';
  });
  await page.waitForSelector('#app[data-route="data"][data-ready="true"]');
  await page.waitForSelector('#dc-sim-market');
  const destinationY = await page.evaluate(() => window.scrollY);
  assert.ok(destinationY <= 1,
    'a destination never inherits the prior screen\'s vertical offset; got y=' + destinationY);
});

test('interactive charts, range pills, universe picker, and the tape', async () => {
  // Research chart: pills switch windows; crosshair reads values on hover
  await openPlan('AAPL');
  await openResearchTab('overview');
  await page.waitForSelector('#history-card .range-pills .pill.active');
  assert.equal(await page.textContent('#history-card .pill.active'), '1Y');
  await page.click('#history-card .pill[data-range="3m"]');
  await page.waitForSelector('#history-card .pill.active[data-range="3m"]');
  await page.waitForSelector('#history-card .chart-wrap svg.chart');
  assert.doesNotMatch(await page.textContent('#history-card'), /Real OHLC candles/,
    'lane-neutral chart instructions never promote Demo/Simulated history as real');
  const summary = await page.textContent('#history-card .chart-summary');
  assert.match(summary, /Change/);
  assert.match(summary, /High .* Low/);
  // Real OHLC candles render (vendored D3, not the fallback close line)
  assert.ok(await page.locator('#history-card svg.candles rect.candle').count() >= 20, 'candlestick bars rendered');
  assert.ok(await page.locator('#history-card svg.candles .candle-up').count() >= 1, 'up candles colored');
  const candleDatesFit = await page.evaluate(() => {
    const svg = document.querySelector('#history-card svg.candles');
    const box = svg.getBoundingClientRect();
    const dates = Array.from(svg.querySelectorAll('text.tick')).filter(t => /^\d{4}-\d{2}-\d{2}$/.test(t.textContent || ''))
      .map(t => t.getBoundingClientRect()).sort((a, b) => a.left - b.left);
    return {
      contained: dates.every(b => b.left >= box.left - 1 && b.right <= box.right + 1),
      separated: dates.every((b, i) => i === dates.length - 1 || b.right <= dates[i + 1].left - 2)
    };
  });
  assert.equal(candleDatesFit.contained, true, 'candlestick date labels stay inside the visible chart bounds');
  assert.equal(candleDatesFit.separated, true, 'candlestick date labels never collide');
  // Crosshair: hover the middle of the chart -> OHLC + % readout
  await page.locator('#history-card svg.chart').scrollIntoViewIfNeeded();
  const box = await page.locator('#history-card svg.chart').boundingBox();
  await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.4);
  await page.waitForSelector('#history-card .chart-tip:not([style*="display: none"])');
  const tip = await page.textContent('#history-card .chart-tip');
  assert.match(tip, /% in window/);
  assert.match(tip, /O .*H /); // open/high/low/close readout
  const chart = page.locator('#history-card svg.chart');
  assert.match(await chart.getAttribute('aria-label'), /arrow keys/i, 'chart names its keyboard interaction');
  assert.equal(await chart.getAttribute('tabindex'), '0');
  await chart.focus();
  await page.keyboard.press('Home');
  await page.waitForSelector('#history-card .chart-tip:not([style*="display: none"])');
  const firstKeyboardReadout = await page.textContent('#history-card .chart-tip');
  await page.keyboard.press('End');
  const lastKeyboardReadout = await page.textContent('#history-card .chart-tip');
  assert.notEqual(firstKeyboardReadout, lastKeyboardReadout,
    'keyboard users can inspect different chart sessions without a pointer');
  // MAX pill answers with everything the fixture has (~3y)
  await page.click('#history-card .pill[data-range="max"]');
  await page.waitForSelector('#history-card .pill.active[data-range="max"]');
  await page.waitForSelector('#history-card .chart-wrap svg.chart');

  // Sector explorer: chips per sector, live tiles, and the canonical universe Scout.
  await go('#/research');
  assert.ok(await page.locator('#tape.tape-offroute').count() === 0, 'tape visible on research');
  await page.waitForSelector('#tape .tape-item');
  assert.ok(await page.locator('#tape-sector').isVisible(),
    'the global market strip names and, when available, switches the active sector');
  assert.ok(await page.locator('#tape-sector option').count() >= 1,
    'the compact global selector is populated from the same universe contract');
  // Tape is SEAMLESS: two identical halves, each at least as wide as the scroll viewport
  const tape = await page.evaluate(() => {
    const strip = document.getElementById('tape-strip');
    const scroll = document.querySelector('.tape-scroll');
    const seqs = strip.querySelectorAll('.tape-seq');
    return { seqs: seqs.length, stripW: strip.scrollWidth, viewW: scroll.clientWidth };
  });
  assert.ok(tape.seqs >= 2 && tape.seqs % 2 === 0, 'even number of sequences: ' + tape.seqs);
  assert.ok(tape.stripW >= tape.viewW * 2 - 5, 'each half covers the viewport (no gap at wrap)');
  await page.waitForSelector('#sector-explorer .sector-chip', { state: 'attached' });
  assert.equal(await page.locator('#sector-chips .sector-rail').getAttribute('role'), 'group',
    'the market-sector filter does not masquerade as page tabs');
  assert.equal(await page.locator('#sector-chips .sector-chip').first().getAttribute('role'), null);
  assert.ok(['true', 'false'].includes(await page.locator('#sector-chips .sector-chip').first().getAttribute('aria-pressed')),
    'sector buttons expose pressed selector state');
  const sectorCount = await page.locator('#sector-chips .sector-chip').count();
  if (sectorCount >= 10) {
    // The observed-market rail never just "ends": when it overflows, an arrow says there is more.
    assert.ok(await page.locator('#sector-chips.can-right .rail-arrow-right').isVisible(),
      'overflowing rail shows a right arrow');
    const before = await page.evaluate(() => document.querySelector('#sector-chips .sector-rail').scrollLeft);
    await page.click('#sector-chips .rail-arrow-right');
    await page.waitForFunction(prev =>
      document.querySelector('#sector-chips .sector-rail').scrollLeft > prev + 50, before);
    assert.ok(await page.locator('#sector-chips.can-left .rail-arrow-left').isVisible(),
      'after scrolling, the way back is visible too');
    await page.click('#sector-chips .sector-chip[data-sector="TECH"]');
  } else {
    // Explicit Demo is its own market lane. It must not pretend the observed sector catalog
    // is selectable while only fixture-backed symbols can be served.
    assert.equal(sectorCount, 1, 'Demo market exposes one honest simulated universe');
    assert.equal(await page.locator('#sector-chips .sector-chip').first().getAttribute('data-sector'), 'world');
  }
  await page.waitForSelector('#sector-grid .sector-tile');
  const grid = await page.textContent('#sector-grid');
  assert.match(grid, /AAPL/);
  // EVERY sector symbol is an actionable tile, with or without a quote
  const tiles = await page.locator('#sector-grid .sector-tile').count();
  assert.ok(tiles >= 4, 'the active market symbols render as tiles, got ' + tiles);
  if (tiles === 5) {
    const rows = await page.$$eval('#sector-grid .sector-tile', els =>
      [...new Set(els.map(e => Math.round(e.getBoundingClientRect().top)))]);
    assert.equal(rows.length, 1, 'a five-symbol desktop universe uses the canvas as one balanced row');
  }
  // DESTINATION CARDS NAVIGATE (interaction contract): no per-card buttons, no preview
  // detour — one action opens the full analysis. The old expansion repeated a subset of
  // that page (review IC-2).
  assert.equal(await page.locator('#sector-grid .sector-tile button').count(), 0,
    'cards carry no repeated action buttons');
  assert.equal(await page.locator('#explorer-focus').count(), 0, 'the preview detour is gone');
  assert.ok(await page.locator('#explorer-hint:has-text("Choose a stock")').count(),
    'one instruction above the grid');
  // Shared range selector + sparklines: one batch, real information on the cards
  assert.ok(await page.locator('#spark-range .pill[data-range="3m"]').count(), 'shared 1M/3M/YTD selector');
  await page.waitForSelector('#sector-grid .sym-card[data-sym="AAPL"] .spark-svg', { timeout: 15000 });
  assert.equal(await page.locator('#sector-grid .sym-card[data-sym="AAPL"] .badge:has-text("NO OPTIONS")').count(), 0,
    'optionable AAPL is not mislabeled by the light quote response');
  assert.ok(await page.locator('#sector-grid .sym-card[data-sym="VTSAX"] .badge:has-text("NO OPTIONS")').count(),
    'non-optionable VTSAX remains explicit');
  // Cards are native links (not div+role shims) and carry no disclosure semantics.
  const cardA11y = await page.evaluate(() => {
    const c = document.querySelector('#sector-grid .sym-card[data-sym="AAPL"]');
    return { tag: c.tagName, href: c.getAttribute('href'), tab: c.tabIndex,
      exp: c.getAttribute('aria-expanded') };
  });
  assert.deepEqual(cardA11y, { tag: 'A', href: '#/research/AAPL', tab: 0, exp: null },
    'card is a native link into full Research without disclosure semantics');
  // Pointer movement/arrow keys explore; clicking anywhere opens full analysis before a Plan exists.
  await page.locator('#sector-grid .sym-card[data-sym="AAPL"] .spark-svg').click();
  await page.waitForSelector('#symbol-proposed-trades', { timeout: 15000 });
  await page.waitForSelector('#research-hero .quote-hero', { timeout: 15000 });
  assert.match(await page.textContent('#research-hero'), /IV rank.*not available in a generated market/is,
    'IV rank stays visible and lane-honest when observed snapshot history cannot apply');
  assert.equal(await page.evaluate(() => window.location.hash), '#/research/AAPL',
    'the chart area does not create a dead middle inside the destination card');
  await page.goBack();
  await page.waitForSelector('#sector-grid .sym-card[data-sym="AAPL"] .spark-svg', { timeout: 15000 });
  // Card click -> full Research; public analysis remains read-only, then one explicit goal
  // carries the selected view into a Plan. Back walks that history to the same explorer.
  const savedExplorerY = await page.evaluate(() => {
    window.scrollTo(0, 200);
    return window.scrollY;
  });
  assert.ok(savedExplorerY >= 100, 'the explorer has enough real content to save a meaningful position');
  await page.locator('#sector-grid .sym-card[data-sym="AAPL"]').click();
  await page.waitForSelector('#symbol-proposed-trades', { timeout: 15000 });
  const storedExplorerY = await page.evaluate(() => App.state.explorerScroll);
  assert.ok(storedExplorerY >= 50,
    'the destination card records the explorer position before navigation; got ' + storedExplorerY);
  await openResearchTab('view');
  await page.waitForSelector('#tv-view');
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.click('#tv-view .choice-option[data-value="bearish"]');
  await page.fill('#tv-horizon', '20');
  await page.dispatchEvent('#tv-horizon', 'change');
  await page.waitForSelector('#research-outcomes');
  await openResearchTab('overview');
  await chooseHeaderRisk('balanced');
  await page.click('#symbol-proposed-goal .choice-option[data-value="DIRECTIONAL"]');
  await page.click('#symbol-find-proposed');
  await page.waitForFunction(() => /^#\/plan\/plan_[^/]+\/strategy$/.test(location.hash)
    && document.getElementById('app').getAttribute('data-ready') === 'true', null, { timeout: 15000 });
  const promotedPlanId = await page.evaluate(() => location.hash.split('/')[2]);
  await go('#/plan/' + promotedPlanId + '/evidence');
  await page.waitForSelector('#test-your-view', { timeout: 15000 });
  assert.match(await page.evaluate(() => window.location.hash), /^#\/plan\/plan_[^/]+\/evidence$/);
  assert.match(await page.textContent('#plan-header'), /AAPL/,
    'the immutable Plan header owns symbol identity without a duplicate change-stock control');
  assert.match(await page.textContent('#test-your-view'), /fabricated Demo history \(not the real past\)/,
    'the study names its Demo-history basis');
  assert.doesNotMatch(await page.textContent('#test-your-view'), /checks the REAL past/,
    'Demo research never promotes fabricated history to real');
  await page.waitForSelector('#tv-context');
  assert.match(await page.textContent('#tv-context'), /Down over/,
    'the Plan Evidence stage receives the exact view selected in public Research');
  for (let i = 0; i < 6 && await page.evaluate(() => location.hash !== '#/research'); i++) {
    await page.goBack();
    await page.waitForFunction(() => document.getElementById('app').getAttribute('data-ready') === 'true');
  }
  await page.waitForFunction(() => location.hash === '#/research'
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.waitForSelector('#sector-grid .sector-tile', { timeout: 15000 });
  await page.waitForFunction(expected => Math.abs(window.scrollY - expected) <= 2,
    storedExplorerY, { timeout: 5000 });
  assert.ok(Math.abs(await page.evaluate(() => window.scrollY) - storedExplorerY) <= 2,
    'Back restores the explorer\'s exact saved vertical position after the route-level top reset');
  const restoredSector = sectorCount >= 10 ? 'TECH' : 'world';
  assert.ok(await page.locator('#sector-chips .sector-chip[data-sector="' + restoredSector + '"].active').count(),
    'Back restores the SAME market selection');
  // In Observed mode, quote-less cards still navigate and let the full page own the honest
  // unavailable state. Explicit Demo has no intentionally dead symbols.
  if (await page.locator('#sector-grid .tile-nodata').count()) {
    await page.locator('#sector-grid .tile-nodata').first().click();
    await page.waitForSelector('#symbol-proposed-trades', { timeout: 15000 });
    assert.match(await page.evaluate(() => window.location.hash), /^#\/research\//,
      'quote-less cards still open an honest analysis rather than becoming dead controls');
    await page.goBack();
    await page.waitForSelector('#sector-grid .sector-tile', { timeout: 15000 });
  }
  if (sectorCount >= 10) assert.ok(await page.locator('#set-universe-btn').count(), 'one-click make-this-my-universe');
  await page.click('#sector-explorer .btn-row button:has-text("Scout this sector")');
  await page.waitForSelector('#research-plan-scout');
  assert.match(await page.textContent('#research-plan-scout'), /current market|ticker/i,
    'sector exploration leads to the canonical universe Scout rather than a removed Trade screen');

  // Header search: "/" focuses, Enter researches
  await page.keyboard.press('/');
  await page.keyboard.type('AAPL');
  await page.keyboard.press('Enter');
  await page.waitForSelector('#symbol-proposed-trades');
  assert.equal(await page.evaluate(() => location.hash), '#/research/AAPL');

  // Tape click-through navigates to research (hovering pauses the marquee — same as a user)
  await go('#/research');
  await page.waitForSelector('#sector-explorer');
  await page.hover('#tape');
  const tapeHit = await page.evaluate(() => {
    const viewport = document.querySelector('#tape .tape-scroll').getBoundingClientRect();
    for (const item of document.querySelectorAll('#tape .tape-item')) {
      const r = item.getBoundingClientRect();
      const x = r.left + r.width / 2, y = r.top + r.height / 2;
      if (x <= viewport.left || x >= viewport.right) continue;
      const hit = document.elementFromPoint(x, y);
      if (hit && hit.closest('.tape-item') === item) return { x, y };
    }
    return null;
  });
  assert.ok(tapeHit, 'the paused ticker exposes at least one unobstructed clickable quote');
  await page.mouse.click(tapeHit.x, tapeHit.y);
  await page.waitForSelector('#symbol-proposed-trades');
  assert.match(await page.evaluate(() => location.hash), /^#\/research\/[A-Z0-9._-]+$/,
    'the tape and cards share one direct full-analysis destination contract');
  // Explicit Demo owns this universe already; no hidden sector mutation is needed.
});

test('partial observed history remains chartable and names its exact coverage', async () => {
  const pattern = '**/api/research/AAPL/history?range=1y';
  let partialHistory = true;
  await page.route(pattern, async route => {
    const response = await route.fetch();
    const body = await response.json();
    if (partialHistory) body.candles = (body.candles || []).slice(-30);
    body.source = 'stored:yahoo';
    body.freshness = 'EOD';
    body.evidence = { provenance: 'OBSERVED', age: 'EOD', source: 'stored:yahoo' };
    body.coverage = {
      requestedFrom: '2025-07-13', requestedTo: '2026-07-13',
      availableFrom: body.candles[0].date, availableTo: body.candles[body.candles.length - 1].date,
      availableSessions: body.candles.length, requestedSessions: partialHistory ? 260 : body.candles.length,
      coveragePct: partialHistory ? 12 : 100, complete: !partialHistory
    };
    await route.fulfill({ response, json: body });
  });
  try {
    await page.evaluate(() => API.flushCache());
    await openPlan('AAPL');
    await openResearchTab('overview');
    await page.waitForSelector('#history-card svg.chart');
    const text = await page.textContent('#history-card');
    assert.match(text, /Partial coverage: 30 observed sessions/,
      'real partial rows render with coverage disclosure rather than an unavailable state');
    assert.doesNotMatch(text, /history is unavailable|No observed daily history/i);
    const datesFit = await page.evaluate(() => {
      const svg = document.querySelector('#history-card svg.candles');
      const box = svg.getBoundingClientRect();
      const dates = Array.from(svg.querySelectorAll('text.tick'))
        .filter(t => /^\d{4}-\d{2}-\d{2}$/.test(t.textContent || ''))
        .map(t => t.getBoundingClientRect()).sort((a, b) => a.left - b.left);
      return {
        contained: dates.every(b => b.left >= box.left - 1 && b.right <= box.right + 1),
        separated: dates.every((b, i) => i === dates.length - 1 || b.right <= dates[i + 1].left - 2)
      };
    });
    assert.deepEqual(datesFit, { contained: true, separated: true },
      'partial-history date labels stay inside the chart without colliding');
    await page.click('#level-switch button[data-level="expert"]');
    await page.waitForSelector('#level-switch button[data-level="expert"][aria-pressed="true"]');
    await page.waitForSelector('#history-card svg.chart');
    assert.match(await page.textContent('#history-card'), /Partial coverage: 30 observed sessions/,
      'Expert sees the same mandatory coverage truth');
    await page.click('#level-switch button[data-level="beginner"]');
    await page.waitForSelector('#level-switch button[data-level="beginner"][aria-pressed="true"]');
    partialHistory = false;
    await page.evaluate(async () => { API.flushCache(); await App.render(); });
    await page.waitForSelector('#history-card svg.chart');
    assert.match(await page.textContent('#history-card'),
      /Stored observed history through .* remains available when the market is closed/,
      'complete observed history retains its baseline source-and-availability disclosure');
  } finally {
    await page.unroute(pattern);
    await page.evaluate(() => API.flushCache());
  }
});

test('Home keeps a partial quote batch actionable instead of leaving an empty well', async () => {
  await page.evaluate(() => {
    localStorage.setItem('strikebench.welcomed', '1');
    window.__partialHomeUniverse = App.state.universe;
    App.state.universe = Object.assign({}, App.state.universe, {
      active: Object.assign({}, App.state.universe.active, {
        label: 'Eight-symbol audit',
        symbols: ['AAPL', 'SPY', 'QQQ', 'TSLA', 'VTSAX', 'NOQ1', 'NOQ2', 'NOQ3']
      })
    });
    API.flushCache();
  });
  await go('#/home');
  await page.waitForFunction(() => document.querySelectorAll('#home-market-watch .sym-card').length === 8,
    { timeout: 15000 });
  const state = await page.evaluate(() => ({
    cards: document.querySelectorAll('#home-market-watch .sym-card').length,
    noQuote: document.querySelectorAll('#home-market-watch .tile-nodata').length,
    linked: Array.from(document.querySelectorAll('#home-market-watch .sym-card'))
      .every(c => /^#\/research\/[A-Z0-9._-]+$/.test(c.getAttribute('href') || ''))
  }));
  assert.equal(state.cards, 8, 'the selected market determines the card count, not a partial response');
  assert.ok(state.noQuote >= 3, 'missing quotes stay visible and honest');
  assert.equal(state.linked, true, 'every selected symbol still opens its own analysis');
  await page.evaluate(() => {
    App.state.universe = window.__partialHomeUniverse;
    delete window.__partialHomeUniverse;
    API.flushCache();
  });
});

test('large Research markets progressively load every sparkline in governed batches', async () => {
  const symbols = ['AAPL', 'SPY', 'QQQ', 'TSLA', 'VTSAX'];
  for (let i = 0; i < 19; i++) symbols.push('Z' + String(i).padStart(3, '0'));
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.evaluate(async (syms) => {
    window.__largeUniverseRestore = App.state.universe;
    if (window.performance && performance.clearResourceTimings) performance.clearResourceTimings();
    App.state.universe = {
      active: { sectorKey: 'LARGE', label: 'Large review market', symbols: syms },
      sectors: [{ key: 'LARGE', label: 'Large review market', symbols: syms }],
      lane: 'DEMO', note: 'DOM large-market fixture'
    };
    App.state.explorerSector = 'LARGE';
    history.replaceState(null, '', '#/research');
    await App.render();
  }, symbols);
  await page.waitForSelector('#sector-grid[data-count="24"]');
  const finalSym = symbols[symbols.length - 1];
  await page.locator('#sector-grid .sym-card[data-sym="' + finalSym + '"]').scrollIntoViewIfNeeded();
  await page.waitForSelector('#sector-grid .sym-card[data-sym="' + finalSym + '"] .spark', { timeout: 20000 });
  await page.waitForFunction(() =>
    document.querySelectorAll('#sector-grid .spark-loading').length === 0, null, { timeout: 30000 });
  const requests = await page.evaluate(() => performance.getEntriesByType('resource')
    .map(e => e.name).filter(n => n.includes('/api/sparklines?symbols=')));
  assert.ok(requests.length >= 2, 'more than 16 symbols require multiple controlled batches: ' + requests.length);
  for (const url of requests) {
    const count = decodeURIComponent(new URL(url).searchParams.get('symbols') || '').split(',').filter(Boolean).length;
    assert.ok(count <= 16, 'no optional chart request exceeds the provider-safe batch: ' + count);
  }
  assert.equal(await page.locator('#sector-grid .spark').count(), symbols.length,
    'every large-market card resolves to a chart or an honest unavailable state');
  await page.evaluate(() => {
    App.state.universe = window.__largeUniverseRestore;
    delete window.__largeUniverseRestore;
    App.state.explorerSector = null;
  });
  await go('#/home');
  await page.setViewportSize({ width: 1280, height: 900 });
});

test('shared editor records exact broker facts in the tracked book without touching practice cash', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const account = await page.evaluate(async () => {
    const created = await API.post('/api/portfolio/accounts', {
      name: 'Shared editor broker book', accountType: 'TAXABLE', broker: 'Fixture broker', lotMethod: 'FIFO',
      openingCashCents: 10000000, shortTermTaxRateBps: null, longTermTaxRateBps: null,
      ordinaryTaxRateBps: null, stateTaxRateBps: null
    });
    App.state.portfolioBookAccountId = created.id;
    localStorage.setItem('strikebench.portfolioBookAccount', created.id);
    return created;
  });
  await go('#/portfolio/book/activity');
  await page.getByRole('tab', { name: 'Record activity', exact: true }).click();
  await page.getByRole('combobox', { name: 'What happened?', exact: true }).selectOption('TRADE');
  await page.waitForSelector('.book-shared-position-editor .position-editor');
  const listed = await page.evaluate(async () => {
    const research = await API.getFresh('/api/research/AAPL');
    const expiration = research.expirations[Math.min(2, research.expirations.length - 1)];
    const chain = await API.getFresh('/api/research/AAPL/chain?expiration=' + encodeURIComponent(expiration));
    const puts = (chain.puts || []).map(row => Number(row.strike)).filter(Number.isFinite).sort((a, b) => a - b);
    const highIndex = Math.max(1, Math.floor(puts.length / 2));
    return { expiration, low: puts[highIndex - 1], high: puts[highIndex] };
  });
  await page.getByRole('button', { name: 'Terminal', exact: true }).click();
  await page.locator('.book-shared-position-editor .position-terminal').fill(
    '-1 AAPL ' + listed.expiration + ' ' + listed.high + 'P @3.10\n'
      + '+1 AAPL ' + listed.expiration + ' ' + listed.low + 'P @1.35');
  for (const label of ['Record source', 'Price meaning', 'Package net $', 'Fee treatment']) {
    assert.equal(await page.getByLabel(label, { exact: true }).count(), 0,
      label + ' is derived rather than asked of a Beginner recording a factual trade');
  }
  await page.getByRole('textbox', { name: 'When it happened', exact: true }).fill('2026-07-13T12:00');
  await page.getByRole('spinbutton', { name: 'Total fees $', exact: true }).fill('2.00');
  await page.getByRole('textbox', { name: 'Broker reference', exact: true }).fill('DOM-SHARED-EDITOR-001');
  const analysisResponsePromise = page.waitForResponse(response => response.url().endsWith(
    '/api/portfolio/accounts/' + account.id + '/analyze') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Analyze this trade', exact: true }).click();
  const trackedAnalysis = await (await analysisResponsePromise).json();
  assert.equal(trackedAnalysis.accountId, account.id, 'tracked Analyze uses the selected tracked account');
  assert.equal(trackedAnalysis.availableCashCents, 10000000, 'tracked Analyze uses tracked cash, not Practice buying power');
  assert.equal(trackedAnalysis.marketLane, 'DEMO',
    'the fixture journey names its actual Demo analysis evidence instead of claiming Observed');
  assert.equal(trackedAnalysis.evaluation.assessment.portfolioImpacts.real.lane, 'REAL',
    'tracked Analyze carries a lane-labeled Real before/after impact');
  assert.equal(trackedAnalysis.evaluation.assessment.portfolioImpacts.practice, null,
    'tracked Analyze never nets a Practice impact into the Real account');
  assert.ok(Number.isFinite(trackedAnalysis.evaluation.participation.localParticipationBps),
    'tracked Analyze returns the same participation metric as a proposed Plan trade');
  assert.match(await page.textContent('.book-shared-position-editor .position-editor-result'), /demo evidence/i,
    'the account and actual evidence lane are visible beside the analysis');
  assert.match(await page.textContent('.book-shared-position-editor .position-editor-result'),
    /Portfolio impact[\s\S]*Recorded at broker impact[\s\S]*Practice[\s\S]*No Practice destination/i,
    'the visible receipt separates the selected Real account from the unavailable Practice lane');
  const coverageDisclosure = page.locator('.book-shared-position-editor .position-editor-result .xp-head')
    .filter({ hasText: 'Data coverage for this analysis' }).first();
  assert.equal(await coverageDisclosure.count(), 1,
    'Beginner keeps the complete per-input coverage receipt behind progressive disclosure');
  await ensureExpanded(coverageDisclosure);
  assert.match(await coverageDisclosure.locator('..').textContent(), /pricing[\s\S]*(demo|observed|modeled)/i,
    'the coverage receipt names the pricing basis rather than hiding it at Beginner');
  const practiceBefore = await page.evaluate(async () => (await API.getFresh('/api/account')).account);
  const createResponse = page.waitForResponse(response => response.url().endsWith(
    '/api/portfolio/accounts/' + account.id + '/transactions') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Record factual activity', exact: true }).click();
  const response = await createResponse;
  const recordedRequest = response.request().postDataJSON();
  assert.equal(recordedRequest.fillNature, 'EXECUTED', 'Record fixes the factual price meaning internally');
  assert.equal(recordedRequest.source, 'BROKER', 'a stable broker reference derives broker provenance');
  assert.equal(recordedRequest.cashAmountCents, 17300,
    'the exact $175 package credit less $2 fees is derived from the two leg fills');
  const responseText = await response.text();
  assert.equal(response.status(), 201, 'tracked broker package was refused: ' + responseText);
  await page.waitForSelector('.book-shared-position-editor .position-editor-result:has-text("Recorded in")');
  assert.match(await page.locator('.book-shared-position-editor .position-editor-result').innerText(),
    /BOOK AFTER THIS RECORD[\s\S]*Realized P\/L[\s\S]*Unrealized P\/L[\s\S]*Cash in this book/i,
  'the factual command answers with current book profit and cash at the point of action');
  const summary = await page.evaluate(async id => API.getFresh('/api/portfolio/accounts/' + id + '/summary'), account.id);
  assert.equal(summary.bookCashCents, 10017300,
    'the tracked book receives the exact $175 package credit less $2 fees');
  assert.equal(summary.positions.length, 2, 'both exact option lots are present in the tracked book');
  const practiceAfter = await page.evaluate(async () => (await API.getFresh('/api/account')).account);
  assert.equal(practiceAfter.cashCents, practiceBefore.cashCents,
    'recording real broker facts never changes practice cash');
  assert.equal(practiceAfter.reservedCents, practiceBefore.reservedCents,
    'recording real broker facts never changes practice reserve');
  await page.waitForSelector('.book-shared-position-editor .position-editor');
  await page.getByRole('button', { name: 'Visual', exact: true }).click();
  await page.waitForSelector('.book-shared-position-editor .position-editor-visual svg.chart');
  await captureSettled('trader-own-p3-record-desktop.png');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(150);
  const containment = await page.evaluate(() => {
    const editor = document.querySelector('.book-shared-position-editor .position-editor');
    return { viewport: document.documentElement.clientWidth, document: document.documentElement.scrollWidth,
      editor: editor.scrollWidth, editorClient: editor.clientWidth };
  });
  assert.ok(containment.document <= containment.viewport + 1 && containment.editor <= containment.editorClient + 1,
    'the shared tracked-book editor remains contained on mobile: ' + JSON.stringify(containment));
  await captureSettled('trader-own-p3-record-mobile.png');
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByRole('tab', { name: 'Overview', exact: true }).click();
  await page.getByRole('tab', { name: 'Activity', exact: true }).click();
  await page.waitForFunction(() => /2 shown/.test(document.querySelector('.book-journal')?.textContent || ''),
    null, { timeout: 15000 });
  assert.equal(await page.locator('.book-journal [data-transaction-id].plan-return-focus').count(), 1,
    'opening Activity focuses the exact newly recorded ledger consequence');
  const tradeDisclosure = page.locator('.book-journal .xp-head').filter({ hasText: /trade/i }).first();
  await ensureExpanded(tradeDisclosure);
  const tradeDetail = await tradeDisclosure.locator('..').textContent();
  assert.match(tradeDetail, /AAPL[\s\S]*put/i,
    'the tracked transaction disclosure preserves both exact option legs');
  assert.match(tradeDetail, /ReferenceDOM-SHARED-EDITOR-001/i,
    'the tracked transaction disclosure preserves both exact option legs and the broker reference');
  await page.getByRole('tab', { name: 'Record activity', exact: true }).click();
  await page.waitForSelector('.book-shared-position-editor .position-editor');
  await page.getByRole('button', { name: 'Record factual activity', exact: true }).click();
  await page.waitForSelector('.book-shared-position-editor .position-editor-result:has-text("reference is already recorded")');
  const duplicateMessage = await page.textContent('.book-shared-position-editor .position-editor-result');
  assert.match(duplicateMessage, /Stable references identify the same broker fact/i,
    'a stable-reference retry is identified before the softer contract-similarity advisory');
  assert.doesNotMatch(duplicateMessage, /Add as a new lot/i,
    'idempotent identity is never presented as an intentional scale-in choice');
  const transactionsAfterRetry = await page.evaluate(async id =>
    (await API.getFresh('/api/portfolio/accounts/' + id + '/transactions?page=0&size=500')).transactions,
  account.id);
  assert.equal(transactionsAfterRetry.filter(tx => tx.externalRef === 'DOM-SHARED-EDITOR-001').length, 1,
    'the stable-reference retry leaves one authoritative ledger fact');
  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('.book-shared-position-editor .position-editor');
  assert.equal(await page.locator('.book-shared-position-editor .position-editor').count(), 1,
    'Expert uses the same tracked-book editor rather than a second implementation');
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

test('smooth pipeline: GET cache and tape refresh preserve continuity', async () => {
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
    await API.put('/api/account/risk-context', {}); // harmless genuine mutation
    const c = API.get('/api/universe');
    return { pureKept: a === b, mutationFlushed: b !== c };
  });
  assert.ok(flushed.pureKept, 'preview POST keeps the cache');
  assert.ok(flushed.mutationFlushed, 'mutations flush the cache');

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

test('pagehide flushes workspace state that is saved locally but not acknowledged remotely', async () => {
  const flushed = await page.evaluate(async () => {
    const originalFetch = window.fetch;
    const previous = App.state.dataScenarioForm;
    const calls = [];
    window.fetch = function (url, options) {
      if (String(url) === '/api/workspace') {
        calls.push({ method: options && options.method, keepalive: !!(options && options.keepalive) });
        return Promise.resolve(new Response('{"rev":999}', {
          status: 200, headers: { 'Content-Type': 'application/json' }
        }));
      }
      return originalFetch.apply(this, arguments);
    };
    App.state.dataScenarioForm = { pagehideProbe: Date.now() };
    Workspace.save(); // records locally and starts the 1.5s remote debounce
    window.dispatchEvent(new Event('pagehide'));
    await new Promise(resolve => setTimeout(resolve, 50));
    window.fetch = originalFetch;
    App.state.dataScenarioForm = previous;
    Workspace.save();
    App.subscribeMarketStream();
    return calls;
  });
  assert.deepEqual(flushed, [{ method: 'PUT', keepalive: true }],
    'pagehide sends the still-unacknowledged workspace immediately');
  await page.waitForTimeout(1700); // let the restored workspace reach the real backend
  await page.reload();
  await page.waitForSelector('#app[data-ready="true"]');
});

test('multiple tabs share one realtime stream pair without starving API reads', async () => {
  const extras = [];
  try {
    for (let i = 0; i < 4; i++) {
      const extra = await page.context().newPage();
      extras.push(extra);
      await extra.goto(WORKSPACE + '?stream-tab=' + i + '#/data/simulation');
      await extra.waitForSelector('#app[data-ready="true"]');
    }
    await page.waitForTimeout(500);
    const pages = [page, ...extras];
    const states = await Promise.all(pages.map(p => p.evaluate(() => {
      const lease = JSON.parse(localStorage.getItem('strikebench.realtime.leader.v1') || 'null');
      return { lease: lease && lease.id, market: !!App._marketES, events: !!App._eventsES };
    })));
    assert.equal(new Set(states.map(x => x.lease).filter(Boolean)).size, 1,
      'all tabs agree on one realtime leader: ' + JSON.stringify(states));
    assert.equal(states.filter(x => x.market && x.events).length, 1,
      'exactly one tab owns both EventSource connections: ' + JSON.stringify(states));
    const leaderIndex = states.findIndex(x => x.market && x.events);
    const leader = pages[leaderIndex];
    const follower = pages[leaderIndex === 0 ? 1 : 0];
    await leader.evaluate(() => {
      if (App._marketES) App._marketES.close();
      // Keep the leader lease alive while representing a transport that can no longer relay.
      App._marketES = { readyState: 2, close: function () {} };
    });
    await page.waitForTimeout(4500); // one lease renewal + health broadcast
    const brokenRelay = await follower.evaluate(() => ({
      healthy: App.marketStreamHealthy(),
      lease: JSON.parse(localStorage.getItem('strikebench.realtime.leader.v1') || 'null')
    }));
    assert.equal(brokenRelay.healthy, false,
      'a renewing leader with a broken SSE must not suppress follower polling');
    assert.equal(brokenRelay.lease && brokenRelay.lease.id, states[leaderIndex].lease,
      'the fallback is triggered by transport health, not only lease expiry');
    await leader.evaluate(() => { App._marketES = null; App.subscribeMarketStream(); });
    const sessionStatus = await extras[extras.length - 1].evaluate(async () => {
      const result = await Promise.race([
        fetch('/api/sim/market').then(r => r.status),
        new Promise(resolve => setTimeout(() => resolve('timeout'), 3000))
      ]);
      return result;
    });
    assert.equal(sessionStatus, 200, 'ordinary API reads remain available with five tabs open');
  } finally {
    await Promise.all(extras.map(p => p.close()));
  }
});

test('portfolio sizing and research tools live in their natural workflows', async () => {
  await page.evaluate(() => Learn.setLevel('expert'));
  const routeSources = await page.evaluate(async () => [
    await (await fetch('/js/app.js')).text(),
    await (await fetch('/js/views.js')).text(),
    await (await fetch('/js/builder.js')).text(),
    await (await fetch('/js/scenario.js')).text()
  ].join('\n'));
  assert.doesNotMatch(routeSources,
    /#\/(?:lab|welcome|account|status|decision|trade\/(?:discover|shape|backtest|place))(?:['"/?#]|$)/,
    'production UI source emits only canonical routes; retired paths stay deleted');
  // The retired Lab URL has no hidden pointer or alias. Unknown routes fall back to Home.
  await page.evaluate(() => { window.location.hash = '#/lab'; });
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('#app[data-route="home"]', { timeout: 8000 });
  assert.ok(!(await page.locator('#nav a[data-route="lab"]').count()), 'no retired navigation item');

  // Retired internal routes do not remain as hidden aliases. They are unknown routes and
  // therefore land at Home; current workflows use only the canonical product vocabulary.
  for (const retired of ['#/welcome', '#/account', '#/status', '#/decision',
    '#/trade/discover', '#/trade/shape', '#/trade/backtest', '#/trade/place']) {
    await page.evaluate(h => { window.location.hash = h; }, retired);
    await page.waitForFunction(() => document.getElementById('app').getAttribute('data-route') === 'home');
  }

  // Portfolio construction is a Portfolio job. It allocates across evaluated ideas but
  // cannot place anything; each chosen package must enter the canonical Plan workflow.
  await page.evaluate(() => {
    delete App.state.portfolioConstruct;
    App.state.headerRiskExplicit = false;
    const risk = document.getElementById('risk-mode');
    risk.value = '';
  });
  await go('#/portfolio/construct');
  await page.waitForSelector('#portfolio-construct');
  await assertNamedControls('#app');
  assert.equal(await page.locator('#pf-sec-construct.active').count(), 1, 'Construct is a first-class Portfolio section');
  await page.waitForSelector('#portfolio-build', { timeout: 25000 });
  assert.equal(await page.locator('#portfolio-objective').count(), 1, 'Expert sees the full objective controls inline');
  assert.equal(await page.locator('.portfolio-construct select').count(), 0,
    'decision-bearing construction controls are purpose-built choices, never bare selects');
  assert.equal(await page.locator('#portfolio-construct .choice-option.active').count(), 1,
    'only the explicitly named active-universe scope mechanic starts selected');
  assert.equal(await page.locator('#portfolio-build').isDisabled(), true,
    'Build starts disabled instead of inventing a decision declaration');
  assert.match(await page.textContent('#portfolio-construct-readiness'),
    /Missing facts: goal, market view, horizon, ranking objective, risk posture/,
    'the exact absent decision facts are visible');
  assert.equal(await page.locator('#portfolio-choose-risk').count(), 1,
    'missing risk points to the one header picker instead of duplicating it in Construct');

  let optimizePosts = [];
  const captureOptimize = async route => {
    if (route.request().method() === 'POST') optimizePosts.push(route.request().postDataJSON());
    await route.continue();
  };
  await page.route('**/api/optimize', captureOptimize);
  await page.locator('#portfolio-build').evaluate(button => button.click());
  assert.equal(optimizePosts.length, 0, 'a forced click cannot submit an undeclared construction');

  let routeMount = await page.locator('#app > .route-mount').elementHandle();
  await page.click('#portfolio-goal .choice-option[data-value="DIRECTIONAL"]');
  await page.click('#portfolio-thesis .choice-option[data-value="neutral"]');
  await page.click('#portfolio-horizon .choice-option[data-value="month"]');
  await page.click('#portfolio-objective .choice-option[data-value="DECISION"]');
  assert.match(await page.textContent('#portfolio-construct-readiness'), /Missing facts: risk posture/,
    'each owned choice removes only itself from the missing receipt');
  await page.click('#portfolio-choose-risk');
  assert.equal(await page.locator('#risk-mode-popover').isVisible(), true,
    'the Construct risk action opens the existing header picker');
  const priorRiskChoice = await page.locator('#risk-mode-popover .choice-option.active').getAttribute('data-value').catch(() => null);
  let constructRisk = priorRiskChoice === 'balanced' ? 'conservative' : 'balanced';
  await page.click(`#risk-mode-popover .choice-option[data-value="${constructRisk}"]`);
  assert.equal(await page.locator('#portfolio-build').isEnabled(), true,
    'Build enables only after the exact five decision facts are owned');
  assert.match(await page.textContent('#portfolio-construct-readiness'),
    new RegExp(`Declaration complete.*Risk: ${constructRisk === 'balanced' ? 'Standard' : 'Cautious'} from the one header picker`, 's'));

  const controlsDisclosure = page.locator('#portfolio-construct .xp > .xp-head').first();
  await ensureExpanded(controlsDisclosure);
  await page.fill('#portfolio-max-position', '12345');
  // Diagnostic mode guarantees a funded (least-bad) set on the fixture universe, LABELED not-a-recommendation.
  await page.check('#portfolio-diagnostics');
  await page.click('#portfolio-build');
  await page.waitForSelector('#portfolio-summary', { timeout: 20000 });
  assert.equal(optimizePosts.length, 1, 'one explicit Build creates one optimizer request');
  assert.deepEqual({ intent: optimizePosts[0].intent, thesis: optimizePosts[0].thesis,
    horizon: optimizePosts[0].horizon, objective: optimizePosts[0].objective,
    riskMode: optimizePosts[0].riskMode },
  { intent: 'DIRECTIONAL', thesis: 'neutral', horizon: 'month', objective: 'DECISION', riskMode: constructRisk },
  'the optimizer receives exactly the visible declaration');
  assert.ok((await page.locator('.allocation-bar .allocation-segment').count()) >= 1, 'composition bar segments');
  assert.ok((await page.locator('.portfolio-construct table tbody tr').count()) >= 1, 'allocations table rows');
  const allocationSymbols = await page.locator('.portfolio-construct table tbody tr td:first-child').allTextContents();
  assert.ok(allocationSymbols.every(s => /^[A-Z][A-Z0-9._-]*$/.test(s.trim())), 'every allocation names its real symbol');
  assert.ok(new Set(allocationSymbols).size > 1, 'fixture construction proves a multi-symbol draft, not duplicated cards');
  assert.ok((await page.locator('#portfolio-output .alert-caution').count()) >= 1, 'diagnostic set labeled not-a-recommendation');
  assert.match(await page.textContent('.portfolio-construct table thead'), /Verdict|Market EV|History EV/,
    'expert construction keeps both EV lanes and economic placement visible');

  // Header risk is the sole owner. Changing it repaints only Construct's local receipt/output:
  // route mount, focus, numeric draft, disclosure and every explicit choice remain in place.
  await page.focus('#portfolio-max-position');
  const nextRisk = constructRisk === 'balanced' ? 'aggressive' : 'balanced';
  await page.evaluate(value => {
    const risk = document.getElementById('risk-mode');
    App.state.headerRiskExplicit = true;
    risk.value = value;
    risk.dispatchEvent(new Event('change', { bubbles: true }));
  }, nextRisk);
  constructRisk = nextRisk;
  assert.equal(await page.evaluate(node => node === document.querySelector('#app > .route-mount'), routeMount), true,
    'header risk does not remount the Book destination');
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), 'portfolio-max-position',
    'local risk reconciliation preserves focus');
  assert.equal(await page.inputValue('#portfolio-max-position'), '12345', 'local risk reconciliation preserves the numeric draft');
  assert.equal(await page.locator('#portfolio-summary').count(), 0, 'changing a declared fact invalidates the stale result immediately');
  assert.equal(optimizePosts.length, 1, 'invalidation never silently reruns the optimizer');
  assert.equal(await page.locator('#portfolio-build').isEnabled(), true, 'the new explicit header risk keeps readiness complete');
  await page.click('#portfolio-build');
  await page.waitForSelector('#portfolio-summary', { timeout: 20000 });
  assert.equal(optimizePosts.length, 2, 'the revised exact declaration runs only on the next visible Build');
  assert.equal(optimizePosts[1].riskMode, constructRisk, 'the rebuilt field carries the revised header risk exactly');
  assert.match(await page.textContent('#portfolio-construction-receipt'),
    /Your decision.*Trade a view.*Stay near here.*About 1 month.*Decision score.*Source field.*Practice account.*Buying power at build/s,
    'the result humanizes and freezes both the decision declaration and its mechanical source context');

  // A result belongs to the exact source field too. Change the active universe while the
  // component is detached: returning must refuse the old result rather than displaying it
  // under the new market scope. Restore the fixture universe, then rebuild explicitly.
  const sourceSymbols = await page.evaluate(() => App.state.universe.active.symbols.slice());
  await go('#/portfolio/positions');
  await page.evaluate(() => {
    App.state.universe.active.symbols = App.state.universe.active.symbols.concat('CONTEXT_PROBE');
  });
  await go('#/portfolio/construct');
  await page.waitForSelector('#portfolio-construct');
  assert.equal(await page.locator('#portfolio-summary').count(), 0,
    'a detached active-universe change invalidates the old construction receipt');
  await page.evaluate(symbols => { App.state.universe.active.symbols = symbols; }, sourceSymbols);
  assert.equal(await page.locator('#portfolio-build').isEnabled(), true,
    'explicit decision choices survive while the changed source result does not');
  await page.click('#portfolio-build');
  await page.waitForSelector('#portfolio-summary', { timeout: 20000 });
  assert.equal(optimizePosts.length, 3, 'restoring source context still requires one visible rebuild');
  routeMount = await page.locator('#app > .route-mount').elementHandle();
  await page.unroute('**/api/optimize', captureOptimize);

  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(300); // capture after the real card-in transition, never by disabling motion
  const arrivalMotion = await page.locator('#portfolio-construct').evaluate(node => ({
    opacity: getComputedStyle(node).opacity,
    fillMode: getComputedStyle(node).animationFillMode
  }));
  assert.equal(arrivalMotion.opacity, '1', 'finished card motion never dims foreground content');
  assert.notEqual(arrivalMotion.fillMode, 'both', 'finished card motion releases its composited layer');
  await page.screenshot({ path: path.join(__dirname, 'shots/portfolio-construct-expert.png'), fullPage: true });

  // Beginner keeps the same levers and result, but progressive disclosure and visual
  // allocation cards replace the expert table.
  const constructGenerationBeforeLens = await page.evaluate(() => App.state.portfolioConstruct.componentGeneration);
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForFunction(before => Learn.currentLevel() === 'beginner'
    && App.state.portfolioConstruct.componentGeneration > before
    && document.getElementById('app').getAttribute('data-ready') === 'true', constructGenerationBeforeLens);
  assert.equal(await page.locator('#portfolio-objective').count(), 1,
    'Beginner uses the available desktop width for the same construction controls');
  assert.equal(await page.locator('#portfolio-goal .choice-option[data-value="DIRECTIONAL"].active').count(), 1,
    'level is a lens: the explicit goal survives');
  assert.equal(await page.locator('#portfolio-thesis .choice-option[data-value="neutral"].active').count(), 1,
    'level is a lens: the explicit view survives');
  assert.equal(await page.inputValue('#portfolio-max-position'), '12345',
    'level is a lens: the numeric constraint draft survives');
  const beginnerConstructionReceipt = await page.textContent('#portfolio-construction-receipt');
  assert.match(beginnerConstructionReceipt,
    /Your decision.*Trade a view.*Stay near here.*About 1 month.*Decision score.*Market.*(?:Observed market|Built-in demo market|Active simulated market|Scenario dataset)/s,
    'Beginner sees the human declaration and market label');
  assert.doesNotMatch(beginnerConstructionReceipt, /Declaration keys|Source keys/,
    'Beginner never sees internal declaration, world, dataset, or account IDs');
  assert.equal(await page.evaluate(node => node === document.querySelector('#app > .route-mount'), routeMount), true,
    'level refresh keeps the same mounted Book destination');
  assert.ok((await page.locator('.portfolio-allocation-card').count()) >= 1, 'Beginner receives visual allocation cards');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/portfolio-construct-beginner.png'), fullPage: true });
  for (const width of [2560, 1280, 390, 375, 320]) {
    await page.setViewportSize({ width, height: width >= 1000 ? 900 : 844 });
    const geometry = await page.locator('#portfolio-construct').evaluate(node => {
      const box = node.getBoundingClientRect();
      const app = document.getElementById('app').getBoundingClientRect();
      const choices = Array.from(node.querySelectorAll('#portfolio-goal .choice-option'));
      const measure = element => {
        if (!element) return null;
        const edge = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        return { left: Math.round(edge.left), right: Math.round(edge.right), width: Math.round(edge.width),
          scrollWidth: element.scrollWidth, overflowX: style.overflowX, display: style.display };
      };
      const isClipped = element => {
        for (let parent = element.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
          if (/hidden|clip/.test(getComputedStyle(parent).overflowX)) return true;
        }
        return false;
      };
      return { inner: innerWidth, doc: document.documentElement.scrollWidth,
        left: box.left, right: box.right, width: box.width, appWidth: app.width,
        roots: { html: measure(document.documentElement), body: measure(document.body),
          topbar: measure(document.querySelector('.topbar')), tape: measure(document.querySelector('.tape')),
          tapeScroll: measure(document.querySelector('.tape-scroll')), app: measure(document.getElementById('app')) },
        unclippedOffenders: Array.from(document.querySelectorAll('body *')).filter(element => {
          if (!element.offsetParent || isClipped(element)) return false;
          const edge = element.getBoundingClientRect();
          return edge.left < -1 || edge.right > innerWidth + 1;
        }).slice(0, 12).map(element => {
          const edge = element.getBoundingClientRect();
          return `${element.id || element.className || element.tagName}@${Math.round(edge.left)}..${Math.round(edge.right)}(sw:${element.scrollWidth})`;
        }),
        offenders: Array.from(document.querySelectorAll('body *')).filter(element => {
          if (!element.offsetParent) return false;
          const edge = element.getBoundingClientRect();
          return edge.left >= -1 && edge.right > innerWidth + 1 && edge.width < innerWidth * 1.8;
        }).slice(0, 12).map(element => {
          const edge = element.getBoundingClientRect();
          return `${element.id || element.className || element.tagName}@${Math.round(edge.left)}..${Math.round(edge.right)}`;
        }),
        choices: choices.length, visibleChoices: choices.filter(choice => {
          const r = choice.getBoundingClientRect();
          return r.width > 0 && r.height > 0 && r.left >= -0.5 && r.right <= innerWidth + 0.5;
        }).length };
    });
    assert.ok(geometry.doc <= geometry.inner + 1, `Construct has no ${width}px horizontal overflow: ${JSON.stringify(geometry)}`);
    assert.ok(geometry.left >= -0.5 && geometry.right <= geometry.inner + 0.5,
      `Construct stays within the ${width}px viewport`);
    assert.equal(geometry.visibleChoices, geometry.choices, `all goals remain reachable at ${width}px`);
    if (width === 2560) {
      assert.ok(geometry.appWidth >= 2400,
        `Construct uses the wide comparison canvas at 2560px: ${geometry.appWidth}`);
      assert.ok(geometry.width >= 2350,
        `the three-column declaration/result composition, not empty gutters, owns the field: ${geometry.width}`);
    }
    if (width <= 390) assert.ok(geometry.width >= width - 40,
      `Construct uses the mobile canvas at ${width}px: ${geometry.width}`);
    if (width === 2560 || width === 390) {
      await page.screenshot({ path: path.join(__dirname, 'shots', `portfolio-construct-${width}.png`), fullPage: true });
    }
  }
  // The OS motion preference changes ticker behavior, never page containment. Reduced motion
  // exposes the static strip through its own scroll viewport; the outer page frame stays fixed.
  for (const reducedMotion of ['reduce', 'no-preference']) {
    await page.emulateMedia({ reducedMotion });
    await page.setViewportSize({ width: 320, height: 844 });
    const tapeGeometry = await page.evaluate(() => {
      const tape = document.querySelector('.tape');
      const scroll = document.querySelector('.tape-scroll');
      return { inner: innerWidth, doc: document.documentElement.scrollWidth,
        tapeOverflow: getComputedStyle(tape).overflowX,
        scrollOverflow: getComputedStyle(scroll).overflowX,
        tapeRight: tape.getBoundingClientRect().right, scrollRight: scroll.getBoundingClientRect().right };
    });
    assert.ok(tapeGeometry.doc <= tapeGeometry.inner + 1,
      `${reducedMotion} motion keeps the ticker inside 320px: ${JSON.stringify(tapeGeometry)}`);
    assert.equal(tapeGeometry.tapeOverflow, 'hidden',
      `${reducedMotion} motion keeps the outer ticker frame clipped`);
    assert.ok(tapeGeometry.tapeRight <= 320.5 && tapeGeometry.scrollRight <= 320.5,
      `${reducedMotion} motion keeps both ticker frames inside the viewport: ${JSON.stringify(tapeGeometry)}`);
    assert.equal(tapeGeometry.scrollOverflow, reducedMotion === 'reduce' ? 'auto' : 'hidden',
      `${reducedMotion} motion uses the intended inner ticker behavior`);
  }
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.waitForTimeout(50);
  let pendingPlanPosts = 0;
  let planCreateBody = null;
  let customBody = null;
  const delayPlanCreate = async route => {
    if (route.request().method() === 'POST') {
      pendingPlanPosts++;
      planCreateBody = route.request().postDataJSON();
      await new Promise(resolve => setTimeout(resolve, 400));
    }
    await route.continue();
  };
  const captureCustom = async route => {
    if (route.request().method() === 'POST') customBody = route.request().postDataJSON();
    await route.continue();
  };
  await page.route('**/api/plans', delayPlanCreate);
  await page.route('**/api/plans/*/strategy/custom', captureCustom);
  const reviewPlan = page.locator('.portfolio-allocation-card button:has-text("Review in a new Plan")').first();
  await reviewPlan.click();
  assert.equal(await reviewPlan.isDisabled(), true, 'Plan creation disables its initiating command immediately');
  assert.equal(await reviewPlan.getAttribute('aria-busy'), 'true', 'Plan creation exposes a pending state');
  await reviewPlan.evaluate(button => button.click());
  await page.waitForSelector('.plan-selected-structure', { timeout: 15000 });
  const handoffText = await page.textContent('.plan-selected-structure');
  await page.unroute('**/api/plans', delayPlanCreate);
  await page.unroute('**/api/plans/*/strategy/custom', captureCustom);
  assert.equal(pendingPlanPosts, 1,
    'a second click during the pending state cannot double-submit Plan creation or custom strategy save');
  assert.deepEqual({ intent: planCreateBody.intent, thesis: planCreateBody.thesis,
    horizonDays: planCreateBody.horizonDays, riskMode: planCreateBody.riskMode },
  { intent: 'DIRECTIONAL', thesis: 'neutral', horizonDays: 21, riskMode: constructRisk },
  'allocation Plan creation carries the exact optimizer declaration with no fallback');
  assert.ok(customBody && customBody.position && customBody.position.strategy !== 'CUSTOM',
    'the exact evaluated package, never a generic fallback, enters Strategy');
  assert.deepEqual({ intent: customBody.position.intent, thesis: customBody.position.thesis,
    horizon: customBody.position.horizon, riskMode: customBody.position.riskMode },
  { intent: 'DIRECTIONAL', thesis: 'neutral', horizon: 'month', riskMode: constructRisk },
  'the saved exact package repeats the same declaration');
  assert.equal(await page.locator('#app[data-route="plan"]').count(), 1, 'allocation opens in the canonical Plan journey');
  assert.match(handoffText, /selected structure|selected package|working structure|plan owned/i,
    'the exact optimizer package survives the handoff: ' + handoffText.slice(0, 120));
  await page.evaluate(() => Learn.setLevel('expert'));

  // The Research LANDING is a market-entry surface: notes stay; the event-study workbench does
  // The event study lives on symbol pages inside Test your view, not as an orphan index card.
  await go('#/research');
  await page.waitForSelector('#research-study-tools .tool-grid', { timeout: 15000 });
  assert.equal(await page.locator('#research-study-tools #study-run').count(), 0,
    'no full study workbench on the landing page');
  // The study itself (run on a SYMBOL page) is baseline-relative with resolved design tokens.
  await openPlan('AAPL');
  await openResearchTab('view');
  // The causal trigger is a user-owned fact. Evidence lenses mount only after it is explicit;
  // this check must never resurrect the retired implicit pullback default just to reach Past.
  await page.waitForSelector('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.waitForSelector('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  assert.ok((await page.locator('#study-results .tool-chart svg').count()) >= 2, 'win-rate gauge + distribution histogram');
  assert.match(await page.textContent('#study-results'), /After the signal|Normally/); // baseline-relative, not "vs 50% chance"
  const gaugeHtml = await page.locator('#study-results .tool-chart').first().innerHTML();
  assert.ok(gaugeHtml.indexOf('var(--line)') < 0 && gaugeHtml.indexOf('var(--fg)') < 0, 'gauge uses no unresolved CSS vars');
  await go('#/research');
  await page.waitForSelector('#research-study-tools .tool-grid', { timeout: 15000 });

  // Notebook: create + delete (delete regression: button called the non-existent API.delete).
  await page.click('#research-note-new');
  await page.fill('.note-editor input[placeholder="Title"]', 'My hypothesis');
  await page.fill('.note-editor textarea', 'SPY put spreads only at high IV rank');
  await page.click('.note-editor button:has-text("Save")');
  await page.waitForSelector('.research-notebook:has-text("My hypothesis")', { timeout: 10000 });
  await page.locator('.research-notebook').getByText('My hypothesis').click();
  await page.locator('.research-notebook').getByRole('button', { name: 'Delete' }).click();
  await page.waitForFunction(
    () => { var nb = document.querySelector('.research-notebook'); return nb && nb.textContent.indexOf('My hypothesis') < 0; },
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
      await App.evaluateOutcome('DECISION', 'DECISION_POLICY', 'AAPL', {
        decision: { symbol: 'AAPL', intent: 'DIRECTIONAL', thesis: 'bullish',
          horizon: 'month', riskMode: 'balanced' }
      });
      await API.get('/api/config');           // must be served from cache — the compute POST must not flush it
      return { added: n - before };
    } finally { window.fetch = orig; }
  });
  assert.equal(r.added, 0, '/api/config was refetched after /api/evaluate — the cache was wrongly flushed');
});

test('simulated market: product creator, loud live band, world-routed research, instant return', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  await go('#/data/simulation');
  // The PRODUCT creator: scenario story cards, labeled fields, no micro-syntax, no prompt().
  await page.waitForSelector('#dc-sim-market #sim-scenarios .sim-scenario');
  await assertNamedControls('#app');
  await assertTabContracts('#app');
  assert.ok((await page.locator('#sim-scenarios .sim-scenario').count()) >= 6, 'scenario story cards');
  assert.equal(await page.getAttribute('#sim-scenarios .sim-scenario[data-scenario="CHOP"]', 'aria-pressed'), 'true',
    'the selected market story is visibly and accessibly selected');
  const creatorDefaults = await page.$$eval('#sim-symbol-chips [data-picked-sym]',
    els => els.map(e => e.getAttribute('data-picked-sym')));
  const creatorUniverse = await page.evaluate(() => (App.state.universe.active.symbols || []).slice());
  assert.ok(creatorDefaults.length >= 1 && creatorDefaults.every(s => creatorUniverse.includes(s))
      && !creatorDefaults.includes('ACME'),
    'creator starts from a recognized current-market ticker, never an unusable fictional default: '
      + JSON.stringify(creatorDefaults));
  assert.equal(await page.isChecked('#sim-use-sector'), true,
    'the current market/universe rides along by default so the world is not a ghost town');
  await page.click('#sim-scenarios .sim-scenario[data-scenario="SELLOFF_REBOUND"]');
  await page.waitForSelector('#sim-scenarios .sim-scenario[data-scenario="SELLOFF_REBOUND"][aria-pressed="true"]');
  assert.ok(await page.locator('#sim-scenarios .sim-scenario[data-scenario="SELLOFF_REBOUND"] .badge:has-text("SELECTED")').count(),
    'clicking a simulated-market card produces an unmistakable state change');
  await page.fill('#sim-name', 'DOM sim');
  await page.fill('#sim-symbols', 'ACME');
  await page.check('#sim-allow-fictional'); // EXPLICIT opt-in — fictional is never inferred (and never pre-checked)
  await page.click('#sim-create');

  // Switch happened: the loud band appears with HUMAN wording + live controls.
  await page.waitForFunction(() => /SIMULATED MARKET/.test((document.getElementById('world-band') || {}).textContent || ''),
    { timeout: 20000 });
  await page.waitForSelector('#sim-control-room #cr-symbols .sim-symbol-tile', { timeout: 20000 });
  assert.equal(await page.locator('#world-exit').isEnabled(), true,
    'Create & enter finishes with an immediately usable Return control');
  const band = await page.$eval('#world-band', el => el.textContent);
  assert.match(band, /SIMULATED MARKET/, 'band names the world loudly');
  assert.match(band, /Sell-off, then rebound/, 'band speaks human, not SELLOFF_REBOUND');
  assert.ok(await page.$('#world-speed'), 'live speed control on the band');
  assert.ok(await page.locator('#world-speed').evaluate(el => el.getBoundingClientRect().width >= 170),
    'desktop speed control shows the selected session-duration label instead of clipping it');
  assert.ok(await page.$('#world-exit'), 'one-command return to the real market');
  assert.ok(await page.evaluate(() => document.body.classList.contains('in-sim-world')));
  await page.waitForFunction(() => {
    const sel = document.getElementById('tape-sector');
    return sel && sel.value === 'world' && /DOM sim/i.test(sel.options[sel.selectedIndex]?.textContent || '');
  });
  assert.doesNotMatch(await page.locator('#tape-sector option:checked').textContent(), /demo/i,
    'the compact market selector commits with the simulated lane instead of retaining Demo state');
  await page.setViewportSize({ width: 390, height: 844 });
  const mobileWorldChrome = await page.evaluate(() => {
    const brand = document.querySelector('.topbar .brand');
    const brandRect = brand.getBoundingClientRect();
    const overlaps = Array.from(document.querySelectorAll('.topbar-controls > *'))
      .filter(control => control.offsetParent)
      .filter(control => {
        const rect = control.getBoundingClientRect();
        return Math.min(brandRect.bottom, rect.bottom) - Math.max(brandRect.top, rect.top) > .5
          && Math.min(brandRect.right, rect.right) - Math.max(brandRect.left, rect.left) > .5;
      }).map(control => control.id || control.className || control.tagName);
    return {
      bandHeight: document.getElementById('world-band').getBoundingClientRect().height,
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
      brandText: brand.textContent.trim(),
      brandClientWidth: brand.clientWidth,
      brandScrollWidth: brand.scrollWidth,
      brandOverlaps: overlaps
    };
  });
  assert.ok(mobileWorldChrome.bandHeight <= 76,
    'mobile simulated-market controls stay compact: ' + JSON.stringify(mobileWorldChrome));
  assert.ok(mobileWorldChrome.scrollWidth <= mobileWorldChrome.innerWidth,
    'simulated-market chrome never widens the phone viewport');
  assert.equal(mobileWorldChrome.brandText, 'StrikeBench');
  assert.ok(mobileWorldChrome.brandClientWidth + .5 >= mobileWorldChrome.brandScrollWidth,
    'the active simulated-market controls never clip the mobile brand');
  assert.deepEqual(mobileWorldChrome.brandOverlaps, [],
    'the active simulated-market controls never paint over the mobile brand');
  await page.setViewportSize({ width: 1280, height: 900 });
  const created = await page.evaluate(async () => {
    const all = (await API.getFresh('/api/sim/market')).sessions || [];
    return { worldId: App.state.world, sessions: all.length };
  });
  const baselineWorld = await page.evaluate(() => App.baseWorldId());
  assert.ok(created.worldId && created.worldId !== baselineWorld);

  // The active world owns this screen. Every ordinary session symbol is represented at once,
  // one focus chart is explicit, and Demo is not offered as a competing primary market.
  await go('#/data/simulation');
  await page.waitForSelector('#sim-control-room #cr-symbols .sim-symbol-tile', { timeout: 20000 });
  await page.waitForFunction(() => {
    const tiles = Array.from(document.querySelectorAll('#cr-symbols .sim-symbol-tile'));
    return tiles.length > 0 && tiles.every(tile =>
      tile.querySelector('.spark-slot .spark-svg, .spark-slot .spark-empty'));
  }, { timeout: 20000 });
  const controlRoom = await page.evaluate(async () => {
    const sessions = (await API.getFresh('/api/sim/market')).sessions || [];
    const current = sessions.find(x => x.id === App.state.world);
    const room = document.getElementById('sim-control-room');
    room.scrollIntoView({ block: 'start' });
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const tiles = Array.from(document.querySelectorAll('#cr-symbols .sim-symbol-tile'));
    const first = tiles[0];
    const rect = first.getBoundingClientRect();
    const hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + 12);
    return {
      expected: Object.keys((current && current.config && current.config.symbolBetas) || {}).length,
      rendered: tiles.length,
      demoAction: !!document.getElementById('enter-demo-market'),
      focusCharts: document.querySelectorAll('#cr-chart svg.chart.candles').length,
      queuedCharts: document.querySelectorAll('#cr-symbols .spark-loading').length,
      stickyHeight: parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--sticky-stack-h')) || 0,
      firstTop: rect.top,
      firstHit: !!(hit && hit.closest('.sim-symbol-tile') === first)
    };
  });
  assert.equal(controlRoom.rendered, controlRoom.expected, 'all session symbols have visible overview tiles');
  assert.equal(controlRoom.demoAction, false, 'an active simulation has no competing Enter demo action');
  assert.equal(controlRoom.focusCharts, 1, 'one explicit focus chart, with overview tiles for breadth');
  assert.equal(controlRoom.queuedCharts, 0, 'every visible overview chart resolves beyond the queued state');
  await page.route('**/api/portfolio/summary', route => route.abort());
  await page.evaluate(async () => { API.flushCache(); await App.render(); });
  await page.waitForSelector('#cr-pl:has-text("temporarily unavailable")', { timeout: 15000 });
  assert.ok(await page.locator('#cr-pl button:has-text("Retry")').count(),
    'a failed control-room account refresh stays visible and recoverable');
  await page.unroute('**/api/portfolio/summary');
  await page.click('#cr-pl button:has-text("Retry")');
  await page.waitForSelector('#cr-pl:has-text("Simulation account")', { timeout: 15000 });
  assert.ok(controlRoom.firstTop >= controlRoom.stickyHeight - 2 && controlRoom.firstHit,
    'the measured sticky stack does not cover the symbol overview: ' + JSON.stringify(controlRoom));
  const focusTarget = await page.locator('#cr-symbols .sim-symbol-tile').nth(1).getAttribute('data-symbol');
  await page.locator('#cr-symbols .sim-symbol-tile').nth(1).click();
  await page.waitForFunction((sym) => {
    const head = document.querySelector('.sim-focus-head');
    const active = document.querySelector('#cr-symbols .sim-symbol-tile.active');
    return head && head.textContent.includes(sym) && active && active.getAttribute('data-symbol') === sym;
  }, focusTarget);

  // The Plan is world-routed. The control room owns the moving tape; the focused Plan keeps
  // a compact shell so sticky market chrome does not bury its journey rail.
  await openPlan('ACME');
  await page.waitForSelector('#plan-flow');
  assert.match(await page.textContent('#plan-header'), /ACME.*Simulated market/s,
    'the persistent Plan header names the execution market');
  // The universe schema is STABLE across modes: active.symbols exists and carries the world.
  const uni = await page.evaluate(async () => await API.getFresh('/api/universe'));
  assert.ok(uni.active && Array.isArray(uni.active.symbols), 'one UniverseView schema in world mode');
  assert.ok(uni.active.symbols.includes('ACME'), 'world symbols drive the universe');
  assert.ok(Array.isArray(uni.sectors) && uni.sectors.length >= 1, 'sectors present (the session)');
  assert.match(await page.textContent('#plan-header .badge'), /Simulated market/,
    'the plan header badge names the simulated lane');
  await page.waitForFunction(() => {
    const el2 = document.getElementById('plan-live-px');
    return el2 && el2.textContent.trim() !== '';
  }, { timeout: 15000 });
  const px0 = await page.textContent('#plan-live-px');
  // Step the world several times via the band. Each step publishes a REAL world.tick SSE hint;
  // the app's own handler must move the hero price. NO fallback: the old catch wrote the price
  // into the DOM itself, which masked a dead SSE path (weekend-handoff review M5).
  for (let i = 0; i < 12; i++) await page.click('#world-step');
  await page.waitForFunction((prev) => {
    const el2 = document.getElementById('plan-live-px');
    return el2 && el2.textContent !== prev;
  }, px0, { timeout: 20000 });
  const px1 = await page.textContent('#plan-live-px');
  assert.notEqual(px1, px0, 'the app’s own SSE handler moved the in-plan price on screen');

  // The simulation account is the account inside the world.
  const simAcct = await page.evaluate(async () => (await API.getFresh('/api/account')).account.id);

  // Return to the build's baseline market (Observed normally, explicit Demo in fixture tests):
  // account, universe, store, and chrome must agree. The SSE
  // world.selected hint racing the awaited PUT used to skip this (stale "Dom sim (simulated)").
  await page.click('#world-exit');
  await page.waitForFunction((baseline) => {
    const u = App.state.universe;
    return App.state.world === baseline && u && u.active && !/simulated session/i.test(u.active.label || '')
      && App.Market.world === baseline && !document.body.classList.contains('in-sim-world');
  }, baselineWorld, { timeout: 15000 });
  await page.waitForFunction(() => App.state.transitionStatus === 'committed'
    && !App._rendering && !App._renderQueued
    && App._lastRenderedHash === window.location.hash
    && document.getElementById('app')?.getAttribute('data-ready') === 'true',
  null, { timeout: 15000 });
  const after = await page.evaluate(async () => ({
    world: (await API.getFresh('/api/world')).world,
    acct: (await API.getFresh('/api/account')).account.id,
    simBody: document.body.classList.contains('in-sim-world'),
    uniLabel: (App.state.universe && App.state.universe.active && App.state.universe.active.label) || ''
  }));
  assert.equal(after.world, baselineWorld);
  assert.notEqual(after.acct, simAcct, 'back on the baseline practice account');
  assert.equal(after.simBody, false);
  assert.doesNotMatch(after.uniLabel, /simulated session/i, 'the universe label reconciled to the baseline market');

  // Finish via the app modal (never window.confirm): the REPORT shows before the finish.
  await go('#/data/simulation');
  const createdSessionRow = page.locator('.sim-session-row[data-sim-id="' + created.worldId + '"]');
  const createdSessionFinish = createdSessionRow.getByRole('button', { name: 'Finish', exact: true });
  await createdSessionFinish.waitFor({ state: 'visible', timeout: 30000 }).catch(async error => {
    const diag = await page.evaluate(() => ({
      world: App.state.world,
      rail: (document.getElementById('dc-sim-sessions') || {}).textContent?.slice(0, 200),
      room: (document.getElementById('dc-sim-room') || {}).textContent?.slice(0, 200),
      rows: document.querySelectorAll('.sim-session-row').length,
      buttons: Array.from(document.querySelectorAll('.sim-session-row button')).map(b => b.textContent.trim()).slice(0, 8)
    }));
    throw new Error('DIAG ' + JSON.stringify(diag) + ' :: ' + error.message);
  });
  assert.equal(await page.locator('.sim-session-row[role="link"], .sim-session-row a button, .sim-session-row button button').count(), 0,
    'a simulated-session card never nests controls inside a link-like wrapper');
  assert.ok(await page.locator('.sim-session-row article').count() === 0,
    'session rows are the semantic article rather than a nested decorative wrapper');
  assert.ok(await page.locator('.sim-session-row .sim-session-summary').count() >= 1,
    'each session exposes one explicit primary card action alongside separate controls');
  for (const label of ['Enter this market', 'Pause', 'Inject event']) {
    assert.equal(await createdSessionRow.getByRole('button', { name: label, exact: true }).isEnabled(), true,
      label + ' stays actionable after returning from the running session');
  }
  await createdSessionRow.getByRole('button', { name: 'Inject event', exact: true }).click();
  await page.waitForSelector('#inject-symbol');
  await assertNamedControls('#modal-root');
  await page.keyboard.press('Escape');
  await page.waitForSelector('#modal-root [role="dialog"]', { state: 'detached' });
  // The card surface itself enters the market; it is not a decorative shell around buttons.
  await createdSessionRow.click({ position: { x: 5, y: 5 } });
  await page.waitForFunction((baseline) => App.state.world !== baseline, baselineWorld, { timeout: 15000 });
  await page.click('#world-exit');
  await page.waitForFunction((baseline) => App.state.world === baseline, baselineWorld, { timeout: 15000 });
  await go('#/data/simulation');
  await createdSessionFinish.waitFor({ state: 'visible', timeout: 30000 }).catch(async error => {
    const diag = await page.evaluate(() => ({
      world: App.state.world,
      rail: (document.getElementById('dc-sim-sessions') || {}).textContent?.slice(0, 200),
      room: (document.getElementById('dc-sim-room') || {}).textContent?.slice(0, 200),
      rows: document.querySelectorAll('.sim-session-row').length,
      buttons: Array.from(document.querySelectorAll('.sim-session-row button')).map(b => b.textContent.trim()).slice(0, 8)
    }));
    throw new Error('DIAG ' + JSON.stringify(diag) + ' :: ' + error.message);
  });
  await createdSessionFinish.click();
  await page.waitForSelector('#modal-confirm');
  await page.waitForSelector('#sim-report', { timeout: 15000 });
  const rep = await page.textContent('#sim-report');
  assert.match(rep, /DECISIONS, not the market/, 'the honesty note shows at session end');
  await page.click('#modal-confirm');
  // Finished sessions stay listed (reports kept) under the collapsed group.
  await page.waitForSelector('.xp-head:has-text("Finished sessions")', { timeout: 15000 });
});

test('viewport composition: welcome rows share one width, Data Overview fits 1280x720, Home has one Find-an-idea', async () => {
  // VISUAL GATES (holistic review): composition is tested, not just presence.
  await page.setViewportSize({ width: 1280, height: 720 });
  await go('#/home/tour');
  await page.waitForSelector('#welcome-hero');
  const widths = await page.evaluate(() => {
    const rows = [document.querySelector('.welcome-top'),
      ...document.querySelectorAll('.welcome-page > .welcome-section'),
      document.querySelector('.welcome-footer')].filter(Boolean);
    return rows.map(r => { const b = r.getBoundingClientRect(); return { l: Math.round(b.left), r: Math.round(b.right) }; });
  });
  for (const w of widths) {
    assert.ok(Math.abs(w.l - widths[0].l) <= 1 && Math.abs(w.r - widths[0].r) <= 1,
      'welcome rows share ONE frame: ' + JSON.stringify(widths));
  }
  const visibleCta = await page.evaluate(() => {
    const frame = document.querySelector('.welcome-footer').getBoundingClientRect();
    const card = document.querySelector('.welcome-footer .cta-banner').getBoundingClientRect();
    return { fl: Math.round(frame.left), fr: Math.round(frame.right), cl: Math.round(card.left), cr: Math.round(card.right) };
  });
  assert.ok(Math.abs(visibleCta.fl - visibleCta.cl) <= 1 && Math.abs(visibleCta.fr - visibleCta.cr) <= 1,
    'the visible final CTA, not just its wrapper, shares the welcome edges: ' + JSON.stringify(visibleCta));
  // Data Overview: the four dashboard cards fit one desktop viewport (expert: densest case;
  // at beginner the health detail folds into an expandable and the text is not visible).
  await page.click('#level-switch button[data-level="expert"]');
  await page.route('**/api/data/overview', async route => {
    const response = await route.fetch();
    const body = await response.json();
    body.engine = Object.assign({}, body.engine || {}, {
      tracked: 120, warmed: 117, stale: 23, inFlight: 8, errors: 1234, avgLatencyMs: 9876
    });
    await route.fulfill({ response, json: body });
  });
  await page.evaluate(() => API.flushCache());
  await go('#/data/overview');
  await page.waitForSelector('#dc-mode .badge');
  await page.waitForSelector('#dc-health:has-text("Quotes")', { timeout: 15000 });
  assert.doesNotMatch(await page.textContent('#dc-health'), /HISTORICAL_OPTIONS/,
    'provider domain enum names are not leaked into the product UI');
  assert.match(await page.textContent('#dc-health'), /Historical options/i,
    'multi-word provider domains are rendered as readable labels');
  const ovBottom = await page.evaluate(() => {
    const g = document.querySelector('.dc-grid');
    return g ? Math.round(g.getBoundingClientRect().bottom) : 9999;
  });
  assert.ok(ovBottom <= 780, 'Data Overview remains a compact desktop dashboard (bottom=' + ovBottom + ')');
  const fixtureMode = await page.evaluate(() => App.config.fixturesOnly);
  if (fixtureMode) {
    await page.waitForSelector('#dc-engine:has-text("Static Demo")', { timeout: 15000 });
    assert.match(await page.textContent('#dc-mode'), /Demo market.*fabricated teaching data/is,
      'the active-lane card names Demo plainly');
    const engineText = await page.textContent('#dc-engine');
    assert.match(engineText, /Feed\s*Static Demo/i,
      'the engine card names the active generated feed rather than blending observed evidence');
    assert.match(engineText, /State\s*Ready/i, 'the fixed teaching feed is ready without provider requests');
  }
  // VERTICAL SYMMETRY (review): each desktop row's paired cards share top AND bottom edges
  // within 2px — presence alone let the grid pass while looking ragged.
  const dcGeo = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.dc-grid > .card'))
      .map(c => { const r = c.getBoundingClientRect(); return { top: r.top, bottom: r.bottom }; });
    return cards;
  });
  assert.equal(dcGeo.length, 4, 'four overview cards');
  assert.match(await page.textContent('#dc-activity'), /OBSERVED STORAGE/i,
    'coverage visibly names the observed store rather than contradicting Demo charts');
  assert.doesNotMatch(await page.textContent('#dc-engine'), /Errors since startup|Avg latency/,
    'growing operational counters stay in the drawer and cannot stretch the overview');
  for (const [a, b] of [[0, 1], [2, 3]]) {
    assert.ok(Math.abs(dcGeo[a].top - dcGeo[b].top) <= 2,
      `row cards share tops: ${dcGeo[a].top} vs ${dcGeo[b].top}`);
    assert.ok(Math.abs(dcGeo[a].bottom - dcGeo[b].bottom) <= 2,
      `row cards share bottoms: ${dcGeo[a].bottom} vs ${dcGeo[b].bottom}`);
  }
  // The provider-health summary chip's TEXT matches its color class (worst-of, review):
  const chipTruth = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('#dc-health .chip-row .badge').forEach(b => {
      out.push({ text: b.textContent.trim(), danger: b.classList.contains('badge-danger'),
        warn: b.classList.contains('badge-warn'), ok: b.classList.contains('badge-ok'),
        dim: b.classList.contains('badge-dim') });
    });
    return out;
  });
  for (const c of chipTruth) {
    if (c.danger) assert.match(c.text, /ERROR|COOLDOWN/, 'red chip must NAME the failure, got ' + c.text);
    if (c.warn) assert.match(c.text, /EMPTY|STALE/, 'amber chip must name the degradation, got ' + c.text);
    if (c.ok) assert.equal(c.text, 'OK', 'green is reserved for confirmed OK, got ' + c.text);
    if (/UNKNOWN|UNCONFIGURED|NONE/.test(c.text)) assert.equal(c.dim, true,
      'unknown/unconfigured provider state stays neutral, got ' + JSON.stringify(c));
  }
  await page.click('#dc-engine');
  const engineDetail = await page.textContent('#dc-detail');
  if (fixtureMode) {
    assert.match(engineDetail, /Demo-only.*does not contact market-data providers/s,
      'Demo detail explains why observed-provider counters do not apply');
    assert.doesNotMatch(engineDetail, /Errors since startup/,
      'observed-engine failures are not blended into the Demo lane');
  } else {
    assert.match(engineDetail, /Errors since startup.*1,234|Errors since startup.*1234/s,
      'the compact summary does not discard observed-engine detail');
  }
  await page.click('#dc-engine');
  // Summary cards disclose detail into ONE drawer below the grid, never distort a neighbor.
  await page.click('#dc-health');
  assert.equal(await page.locator('#dc-detail .dc-detail-card').count(), 1, 'one detail drawer below the 2x2');
  assert.equal(await page.locator('#dc-health > .dc-detail-toggle').getAttribute('aria-expanded'), 'true');
  await page.click('#dc-health');
  assert.equal(await page.locator('#dc-detail .dc-detail-card').count(), 0, 'second click closes the drawer');
  for (const viewport of [{ width: 2560, height: 1440 }, { width: 390, height: 844 },
    { width: 375, height: 812 }, { width: 320, height: 700 }]) {
    await page.setViewportSize(viewport);
    const dataGeometry = await page.evaluate(() => {
      const app = document.getElementById('app').getBoundingClientRect();
      const grid = document.querySelector('.dc-grid').getBoundingClientRect();
      const cards = Array.from(document.querySelectorAll('.dc-grid > .card')).map(card => card.getBoundingClientRect());
      const tabs = Array.from(document.querySelectorAll('#data-tabs [role="tab"]'))
        .filter(tab => tab.offsetParent).map(tab => tab.getBoundingClientRect());
      return {
        viewport: innerWidth, documentWidth: document.documentElement.scrollWidth, appWidth: app.width,
        gridColumns: getComputedStyle(document.querySelector('.dc-grid')).gridTemplateColumns.split(' ').length,
        gridTop: grid.top, gridBottom: grid.bottom,
        tabsRows: new Set(tabs.map(box => Math.round(box.top))).size,
        clipped: cards.concat(tabs).some(box => box.left < -.5 || box.right > innerWidth + .5)
      };
    });
    assert.ok(dataGeometry.documentWidth <= dataGeometry.viewport + 1 && !dataGeometry.clipped,
      `Data Overview stays contained at ${viewport.width}px: ${JSON.stringify(dataGeometry)}`);
    if (viewport.width === 2560) {
      assert.ok(dataGeometry.appWidth >= 1700,
        `the bounded operational canvas remains substantial at 2560px: ${JSON.stringify(dataGeometry)}`);
      assert.equal(dataGeometry.gridColumns, 4,
        `all four status peers stay co-visible at 2560px: ${JSON.stringify(dataGeometry)}`);
      assert.ok(dataGeometry.gridTop < 300 && dataGeometry.gridBottom < 900,
        `the complete status decision fits high in the first desktop viewport: ${JSON.stringify(dataGeometry)}`);
    } else {
      assert.equal(dataGeometry.gridColumns, 1,
        `${viewport.width}px keeps one readable Data-card sequence: ${JSON.stringify(dataGeometry)}`);
      assert.equal(dataGeometry.tabsRows, 2,
        `${viewport.width}px keeps every Data destination in a compact two-row grid: ${JSON.stringify(dataGeometry)}`);
    }
  }
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.unroute('**/api/data/overview');
  // Home: exactly ONE 'Find an idea' doorway (review #9) and no marketing hero.
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1')); // dashboard, not the tour
  const compositionPlanId = await page.evaluate(async () => {
    await PlanStore.load(true);
    const plan = await PlanStore.create({ symbol: 'AAPL', intent: 'DIRECTIONAL', thesis: 'neutral',
      horizonDays: 62, riskMode: 'conservative', title: 'AAPL · Home composition audit' });
    return plan.id;
  });
  // Exercise the observed-size sector catalog even though this suite runs in explicit Demo.
  // The prior audit saw one Demo sector and therefore missed the full rail widening Home by 1,600px.
  await page.evaluate(() => {
    window.__homeUniverseRestore = App.state.universe;
    const active = App.state.universe.active;
    const keys = ['CORE', 'TECH', 'SEMICONDUCTORS', 'HEALTHCARE', 'DEFENSE', 'STAPLES',
      'DISCRETIONARY', 'ENERGY', 'FINANCIALS', 'INDUSTRIALS', 'COMMUNICATIONS', 'UTILITIES', 'ETFS'];
    App.state.universe = Object.assign({}, App.state.universe, {
      sectors: keys.map(k => ({ key: k, label: k.replaceAll('_', ' '), symbols: active.symbols }))
    });
  });
  await go('#/home');
  await page.waitForSelector('#home-stats .stat');
  const homeText = await page.textContent('#app');
  const findCount = (homeText.match(/Find an idea/g) || []).length;
  assert.ok(findCount <= 1, 'one contextual Find-an-idea, not ' + findCount);
  assert.ok(await page.locator('#next-up').count(), 'one static Plan-progress area supports the hero continuation');
  assert.equal(await page.locator('#next-up button').count(), 0,
    'the hero remains the only Continue command on Home');
  const cbText = await page.textContent('#command-bar');
  assert.ok(await page.locator('#command-bar .btn').count() === 3, 'command bar keeps only non-nav tools');
  assert.doesNotMatch(cbText, /Research|Ideas/, 'nav destinations are not repeated as commands');
  const homeGeo = await page.evaluate(() => {
    const market = document.querySelector('.home-market-slot').getBoundingClientRect();
    const side = document.querySelector('.home-col-side').getBoundingClientRect();
    return { mt: Math.round(market.top), mb: Math.round(market.bottom), st: Math.round(side.top), sb: Math.round(side.bottom) };
  });
  assert.ok(Math.abs(homeGeo.mt - homeGeo.st) <= 2 && Math.abs(homeGeo.mb - homeGeo.sb) <= 2,
    'Home primary columns share visible top and bottom edges: ' + JSON.stringify(homeGeo));
  const homeContainment = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    side: Math.round(document.querySelector('.home-col-side').getBoundingClientRect().right),
    pulse: Math.round(document.querySelector('#sector-pulse').getBoundingClientRect().right),
    app: Math.round(document.getElementById('app').getBoundingClientRect().right)
  }));
  assert.ok(homeContainment.document <= homeContainment.viewport + 2,
    'Home cannot widen the document: ' + JSON.stringify(homeContainment));
  assert.ok(homeContainment.pulse <= homeContainment.side + 2 && homeContainment.pulse <= homeContainment.app + 2,
    'the full sector rail stays inside the right column: ' + JSON.stringify(homeContainment));
  for (const viewport of [{ width: 2560, height: 1440 }, { width: 390, height: 844 },
    { width: 375, height: 812 }, { width: 320, height: 700 }]) {
    await page.setViewportSize(viewport);
    const deskGeometry = await page.evaluate(() => {
      const app = document.getElementById('app').getBoundingClientRect();
      const hero = document.querySelector('.home-hero').getBoundingClientRect();
      const ideas = document.getElementById('home-screened-ideas').getBoundingClientRect();
      const cols = document.querySelector('.home-cols');
      return {
        viewport: innerWidth, documentWidth: document.documentElement.scrollWidth, appWidth: app.width,
        heroTop: hero.top, heroBottom: hero.bottom, ideasBottom: ideas.bottom,
        columns: getComputedStyle(cols).gridTemplateColumns.split(' ').length,
        colsRight: cols.getBoundingClientRect().right
      };
    });
    assert.ok(deskGeometry.documentWidth <= deskGeometry.viewport + 1
      && deskGeometry.colsRight <= deskGeometry.viewport + .5,
    `Desk stays contained at ${viewport.width}px: ${JSON.stringify(deskGeometry)}`);
    if (viewport.width === 2560) {
      assert.ok(deskGeometry.appWidth >= 1700,
        `the bounded Desk canvas remains substantial at 2560px: ${JSON.stringify(deskGeometry)}`);
      assert.equal(deskGeometry.columns, 2,
        `market context and next actions remain co-visible at 2560px: ${JSON.stringify(deskGeometry)}`);
      assert.ok(deskGeometry.heroTop < 260 && deskGeometry.ideasBottom < 1000,
        `the Desk context and governed idea stay inside the first desktop viewport: ${JSON.stringify(deskGeometry)}`);
    } else {
      assert.equal(deskGeometry.columns, 1,
        `${viewport.width}px keeps one understandable Desk sequence: ${JSON.stringify(deskGeometry)}`);
    }
  }
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.evaluate(() => {
    App.state.universe = window.__homeUniverseRestore;
    delete window.__homeUniverseRestore;
  });
  // Geometry is asserted in the settled state while production entrance/hover motion stays
  // enabled. The preceding responsive sweep can leave the fixed Playwright pointer over a
  // card at one of the intermediate layouts, and cards may still be inside their 180ms rise.
  await page.mouse.move(0, 0);
  await page.waitForTimeout(250);
  const sparseMarketRows = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.home-market-grid .sym-card'));
    const ys = cards.map(c => Math.round(c.getBoundingClientRect().top));
    const rowTops = [];
    ys.forEach(y => {
      const row = rowTops.find(entry => Math.abs(entry.top - y) <= 2);
      if (row) row.count += 1; else rowTops.push({ top: y, count: 1 });
    });
    return { count: cards.length, rows: rowTops.map(row => row.count),
      cards: cards.map(c => ({ symbol: c.dataset.sym,
        child: Array.prototype.indexOf.call(c.parentElement.children, c) + 1,
        top: Math.round(c.getBoundingClientRect().top), column: getComputedStyle(c).gridColumn })) };
  });
  if (sparseMarketRows.count === 5) assert.deepEqual(sparseMarketRows.rows, [3, 2],
    'a five-symbol market uses a balanced 3+2 desktop grid: ' + JSON.stringify(sparseMarketRows));
  if (sparseMarketRows.count === 5) {
    const centeredSecondRow = await page.evaluate(() => {
      const grid = document.querySelector('.home-market-grid').getBoundingClientRect();
      const cards = Array.from(document.querySelectorAll('.home-market-grid .sym-card'));
      const a = cards[3].getBoundingClientRect(), b = cards[4].getBoundingClientRect();
      return { leftGap: a.left - grid.left, rightGap: grid.right - b.right };
    });
    assert.ok(Math.abs(centeredSecondRow.leftGap - centeredSecondRow.rightGap) <= 2,
      'the two-card second row is visually centered: ' + JSON.stringify(centeredSecondRow));
  }
  assert.equal(await page.locator('.home-col-side .card-head').count(), 3,
    'Plan progress, Tools, and Sector pulse are all explicitly headed');
  // Responsive chrome must recalculate its sticky anchor. It previously retained the 120px
  // mobile top after resizing to desktop and overlaid both the tape and the page.
  await go('#/home');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.waitForFunction(() => {
    const tape = document.getElementById('tape');
    const band = document.getElementById('world-band');
    const top = document.querySelector('.topbar');
    if (!tape || !band || !top || !tape.offsetParent || tape.getBoundingClientRect().height <= 0) return false;
    const topBox = top.getBoundingClientRect();
    const bandBox = band.getBoundingClientRect();
    const tapeBox = tape.getBoundingClientRect();
    return Math.abs(topBox.bottom - bandBox.top) <= 2 && bandBox.bottom <= tapeBox.top + 2;
  }, { timeout: 10000 });
  const chromeGeo = await page.evaluate(() => {
    const top = document.querySelector('.topbar').getBoundingClientRect();
    const band = document.getElementById('world-band').getBoundingClientRect();
    const tape = document.getElementById('tape').getBoundingClientRect();
    return { topBottom: top.bottom, bandTop: band.top, bandBottom: band.bottom, tapeTop: tape.top };
  });
  assert.ok(Math.abs(chromeGeo.topBottom - chromeGeo.bandTop) <= 2,
    'world band remains pinned below the resized header: ' + JSON.stringify(chromeGeo));
  assert.ok(chromeGeo.bandBottom <= chromeGeo.tapeTop + 2,
    'world band never overlays the tape after resize: ' + JSON.stringify(chromeGeo));
  await page.evaluate(async id => {
    const plan = await PlanStore.get(id, true);
    if (plan.status !== 'ARCHIVED') await API.post('/api/plans/' + id + '/archive', { expectedVersion: plan.version });
    await PlanStore.refresh();
  }, compositionPlanId);
  await page.setViewportSize({ width: 1280, height: 900 });
});

/*
 * ===== Program ONE R0: the contracts-as-code gate =====
 * Rails (one state owner, level-invariant keys, flip-without-loss), the control-language
 * primitives (never a bare listbox; live consequence), Flow bands (locked-with-reason,
 * conclusions not chrome), and the lineage chip. These are the rails every later band
 * builds on — they are pinned here before any band exists.
 */
test('Program ONE R0: rails, choice controls, and flow bands honor their contracts', async () => {
  await go('#/home');
  const r = await page.evaluate(async () => {
    const out = {};
    try { Rails.surface('plan:1:expert-panel'); out.keyGuard = 'no-throw'; }
    catch (e) { out.keyGuard = 'threw'; }

    const owned = Rails.surface('r0:test');
    owned.selection = 'condor';
    owned.result = { ev: 123, acks: ['capital'] };
    const before = Rails.snapshot();
    Learn.setLevel('expert'); await App.render();
    Learn.setLevel('beginner'); await App.render();
    out.flipIdentical = Rails.snapshot() === before;

    const host = document.createElement('div'); document.body.appendChild(host);
    const changed = [];
    const ctl = UI.segmented({
      label: 'How selective?',
      options: [
        { value: 'loose', label: 'More examples', detail: '3%' },
        { value: 'balanced', label: 'Balanced', detail: '5%' },
        { value: 'strict', label: 'Only the strongest', detail: '8%' }],
      value: 'balanced',
      onChange: v => changed.push(v),
      consequence: v => 'trigger threshold ' + ({ loose: '3', balanced: '5', strict: '8' })[v] + '%'
    });
    host.appendChild(ctl);
    out.consequenceInitial = host.querySelector('.control-consequence').textContent;
    host.querySelectorAll('.choice-option')[2].click();
    out.consequenceAfter = host.querySelector('.control-consequence').textContent;
    out.value = ctl.value();
    out.changed = changed.slice();
    out.noListbox = !host.querySelector('select');
    out.radiogroup = !!host.querySelector('[role="radiogroup"]');

    function blankChoice(id) {
      const node = UI.segmented({ id, label: 'Blank keyboard test',
        options: [{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }, { value: 'c', label: 'C' }],
        value: '' });
      host.appendChild(node);
      return node;
    }
    const blankRight = blankChoice('r0-blank-right');
    blankRight.querySelectorAll('.choice-option')[0].focus();
    blankRight.querySelectorAll('.choice-option')[0].dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }));
    out.blankRight = { value: blankRight.value(), focus: document.activeElement.dataset.value };
    const blankLeft = blankChoice('r0-blank-left');
    blankLeft.querySelectorAll('.choice-option')[0].focus();
    blankLeft.querySelectorAll('.choice-option')[0].dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true }));
    out.blankLeft = { value: blankLeft.value(), focus: document.activeElement.dataset.value };
    const blankPointer = blankChoice('r0-blank-pointer');
    blankPointer.querySelectorAll('.choice-option')[2].dispatchEvent(
      new MouseEvent('click', { bubbles: true, cancelable: true }));
    out.blankPointer = { value: blankPointer.value(), focus: document.activeElement.dataset.value };

    const fhost = document.createElement('div'); document.body.appendChild(fhost);
    const ctx = { declared: false, tested: false };
    const flow = Flow.render({ id: 'r0-flow', stateKey: 'flow:r0test', ctx, sections: [
      { key: 'view', title: 'Your view', complete: c => c.declared,
        render: h => h.appendChild(document.createTextNode('declare here')),
        conclusion: () => 'View: up over 3 weeks' },
      { key: 'test', title: 'Test it', complete: c => c.tested,
        lockedReason: () => 'Declare your view first.',
        render: h => h.appendChild(document.createTextNode('testing ui')) }
    ] });
    fhost.appendChild(flow.el);
    out.posturesBefore = [flow.posture('view'), flow.posture('test')];
    out.lockedReason = fhost.querySelector('[data-band="test"] .flow-band-locked').textContent;
    out.lockedVisible = !!fhost.querySelector('[data-band="test"] .flow-band-title');
    ctx.declared = true; flow.refresh();
    out.posturesAfter = [flow.posture('view'), flow.posture('test')];
    flow.fold('view');
    out.posturesFolded = [flow.posture('view'), flow.posture('test')];
    out.conclusion = fhost.querySelector('[data-band="view"] .flow-band-conclusion-body').textContent;
    fhost.querySelector('[data-band="view"] .flow-band-conclusion').click();
    out.revisitPosture = fhost.querySelector('[data-band="view"]').dataset.posture;

    const chip = UI.lineageChip({ id: 'e1', fingerprint: 'abcdef1234', basis: 'PARAMETRIC' });
    out.lineageNames = chip.textContent.indexOf('#abcdef') >= 0;
    out.lineageExplained = !!chip.querySelector('.info-trigger, .term');

    host.remove(); fhost.remove();
    Rails.reset('r0:test'); Rails.reset('flow:r0test');
    return out;
  });
  assert.equal(r.keyGuard, 'threw', 'Rails rejects level-bearing surface keys');
  assert.equal(r.flipIdentical, true, 'owned surface state is byte-identical across a Beginner→Expert→Beginner flip');
  assert.equal(r.consequenceInitial, 'trigger threshold 5%', 'a choice control states its consequence before anything runs');
  assert.equal(r.consequenceAfter, 'trigger threshold 8%', 'the consequence line follows the selection live');
  assert.deepEqual([r.value, r.changed], ['strict', ['strict']], 'choice controls round-trip their value');
  assert.equal(r.noListbox, true, 'the control language never renders a bare listbox');
  assert.equal(r.radiogroup, true, 'choice controls are accessible radiogroups');
  assert.deepEqual(r.blankRight, { value: 'b', focus: 'b' },
    'ArrowRight begins at the focused option when an explicit choice is still blank');
  assert.deepEqual(r.blankLeft, { value: 'c', focus: 'c' },
    'ArrowLeft wraps from the focused first option when an explicit choice is still blank');
  assert.deepEqual(r.blankPointer, { value: 'c', focus: 'c' },
    'pointer selection restores focus to the selected button after the control repaints');
  assert.deepEqual(r.posturesBefore, ['active', 'locked'], 'the first incomplete band is active; successors lock');
  assert.equal(r.lockedReason, 'Declare your view first.', 'locked bands state their reason');
  assert.equal(r.lockedVisible, true, 'locked bands stay visible — never hidden');
  assert.deepEqual(r.posturesAfter, ['revisit', 'active'],
    'completing a band while working inside it never yanks its content away');
  assert.deepEqual(r.posturesFolded, ['done', 'active'], 'an explicit fold concludes the band and its successor stays unlocked');
  assert.equal(r.conclusion, 'View: up over 3 weeks', 'done bands collapse to their conclusion, not bare chrome');
  assert.equal(r.revisitPosture, 'active', 'a conclusion reopens for revision on demand');
  assert.equal(r.lineageNames, true, 'the lineage chip names the stored ensemble fingerprint');
  assert.equal(r.lineageExplained, true, 'the lineage chip carries registry-backed explanation');
});

/*
 * The label audit, generalized (Program ONE §3): every VISIBLE technical label on the primary
 * surfaces is registry-covered or deliberately allowlisted as plain language. The allowlist is
 * a reviewed file — new labels cannot sneak in un-explained; seed with LABEL_ALLOWLIST_SEED=1.
 */
test('Program ONE R0: visible labels are registry-covered or reviewed plain language', async () => {
  const allowPath = path.join(__dirname, 'label-allowlist.json');
  const allow = new Set(JSON.parse(fs.readFileSync(allowPath, 'utf8')));
  const uncovered = new Map();
  async function collect(route) {
    const labels = await page.evaluate(() => {
      // House label carriers, verified against ui.js markup: stat -> .label, fact -> .f-label,
      // chip -> .chip-label, field -> label[for], plus the R0 choice controls and lens titles.
      const sel = '.stat > .label, .fact > .f-label, .chip-label, .field > label, '
        + '.field-label, .choice-option-label, .outcome-basis-title';
      const covered = node => node.querySelector('.term[data-term], .info-trigger, [data-vocabulary]')
        || node.closest('[data-vocabulary]');
      const ordinaryCarriers = Array.from(document.querySelectorAll(sel))
        .filter(n => n.offsetParent !== null && !covered(n))
        .map(n => ({ text: (n.textContent || '').trim().replace(/\s+/g, ' '),
          carrier: n.outerHTML.slice(0, 220) }))
        .filter(row => row.text && row.text.length >= 3 && row.text.length <= 64);
      // Headings, table columns, tabs, and disclosure buttons do not use the house metric
      // classes above. Audit technical language on those carriers without forcing every
      // ordinary action verb into the plain-language allowlist.
      const technical = /(?:\bbuying power\b|\b(?:net |dollar |beta-weighted )?delta\b|\bgamma\b|\btheta\b|\bvega\b|\bgreeks?\b|\bPOP\b|\bbreakevens?\b|\bMonte Carlo\b|\bmarket odds\b|\bpast analogs?\b|\brule replay\b|\bimplied volatility\b|\bIV(?: rank)?\b|\bCVaR\b|\bassignment capital\b|\bbroker reserve\b|\beconomic exposure\b|\btheoretical max (?:loss|profit)\b|\bscenario loss\b|\bskew\b|\bterm slope\b|\bsettlement\b|\bexercise\b)/i;
      const technicalCarriers = Array.from(document.querySelectorAll(
        '.card-head > h2, th, dt, [role="tab"], .xp-head'))
        .filter(n => n.offsetParent !== null && !covered(n))
        .map(n => ({ text: (n.textContent || '').trim().replace(/\s+/g, ' '),
          carrier: n.outerHTML.slice(0, 220) }))
        .filter(row => row.text && row.text.length <= 96 && technical.test(row.text));
      return ordinaryCarriers.concat(technicalCarriers);
    });
    labels.forEach(row => {
      if (!allow.has(row.text) && !uncovered.has(row.text)) uncovered.set(row.text,
        { route, carrier: row.carrier });
    });
  }
  async function auditRoute(route, afterOpen) {
    await go(route);
    if (afterOpen) await afterOpen();
    await collect(route);
  }

  await page.evaluate(() => Learn.setLevel('beginner'));
  for (const route of ['#/home', '#/research', '#/portfolio/positions', '#/data/overview']) {
    await auditRoute(route);
  }

  // Scenario 18 is not satisfied by auditing only generic route shells. Exercise a declared
  // Plan's ranked Strategy field and its exact selected-package Canvas, then both guarded Book
  // import and aggregate-risk workspaces. Every producer still reports into this one gate.
  const labelPlan = await openPlan('AAPL', 'strategy', 'DIRECTIONAL', 'bullish', '47d');
  await page.waitForSelector('#plan-strategy-body', { timeout: 30000 });
  if (await page.locator('#plan-run-strategy').isVisible()) await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .ranked-idea-hero', { timeout: 30000 });
  await collect('#/plan/{declared}/strategy');

  await page.evaluate(async planId => {
    const latest = await PlanStore.latestStrategy(planId, true);
    const candidate = latest.strategy.result.candidates[0];
    let live = await PlanStore.get(planId, true);
    await PlanStore.selectCandidate(live, candidate.id);
    live = await PlanStore.get(planId, true);
    await PlanStore.runEnsemble(live, { over: {
      model: 'GBM', shape: 'CHOP', horizonDays: 47, stepsPerDay: 1,
      driftAnnual: 0, volAnnual: 0.25, jumpsPerYear: 0, jumpMean: 0,
      jumpVol: 0, tailNu: 6, heston: null, seed: 1818, paths: 80
    } });
  }, labelPlan.id);
  await auditRoute('#/plan/' + labelPlan.id + '/outcomes', async () => {
    await page.waitForSelector('#plan-outcomes-basis-model');
    await page.click('#plan-outcomes-basis-model');
    await page.waitForSelector('#plan-scenario-canvas [data-canvas-fan] svg', { timeout: 30000 });
  });

  await page.evaluate(async () => {
    const listed = await API.getFresh('/api/portfolio/accounts');
    let account = (listed.accounts || []).find(row => row.status === 'ACTIVE');
    if (!account) account = await API.post('/api/portfolio/accounts', {
      name: 'Label audit brokerage', accountType: 'TAXABLE', lotMethod: 'FIFO', openingCashCents: 1000000
    });
    App.state.portfolioBookAccountId = account.id;
  });
  await auditRoute('#/portfolio/book/activity', async () => {
    await page.getByRole('tab', { name: 'Import history', exact: true }).click();
    await page.waitForSelector('.book-import-card');
  });
  await auditRoute('#/portfolio/book/risk', async () => {
    await page.waitForSelector('.book-risk-intro', { timeout: 30000 });
  });

  // The experience switch changes explanation depth, not the owned workflow. Revisit the
  // same declared artifacts in Expert so dense labels cannot evade the rendered gate.
  await page.evaluate(() => Learn.setLevel('expert'));
  await auditRoute('#/plan/' + labelPlan.id + '/strategy', async () => {
    await page.waitForSelector('#plan-strategy-results .ranked-idea-hero', { timeout: 30000 });
  });
  await auditRoute('#/plan/' + labelPlan.id + '/outcomes', async () => {
    await page.waitForSelector('#plan-outcomes-basis-model');
    await page.click('#plan-outcomes-basis-model');
    await page.waitForSelector('#plan-scenario-canvas [data-canvas-fan] svg', { timeout: 30000 });
  });
  await auditRoute('#/portfolio/book/activity', async () => {
    await page.getByRole('tab', { name: 'Import history', exact: true }).click();
    await page.waitForSelector('.book-import-card');
  });
  await auditRoute('#/portfolio/book/risk', async () => {
    await page.waitForSelector('.book-risk-intro', { timeout: 30000 });
  });

  if (process.env.LABEL_ALLOWLIST_SEED === '1' && uncovered.size) {
    fs.writeFileSync(allowPath,
      JSON.stringify([...new Set([...allow, ...uncovered.keys()])].sort(), null, 2) + '\n');
    return;
  }
  assert.deepEqual([...uncovered.keys()], [],
    'labels lacking registry coverage or allowlist review: '
      + [...uncovered].map(([t, detail]) => `"${t}" (${detail.route}; ${detail.carrier})`).join(', '));
});

test('Program ONE: the workspace flow is a dense attention document, not a wall', async () => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.evaluate(() => Learn.setLevel('beginner'));
  // A unique horizon guarantees a genuinely fresh plan — creation dedupes on matching context.
  await openPlan('AAPL', 'strategy', 'DIRECTIONAL', 'bullish', '37d');
  await page.waitForSelector('#plan-flow');
  await page.waitForSelector('#plan-strategy-body', { timeout: 30000 }).catch(async error => {
    const diag = await page.evaluate(() => ({
      hash: location.hash, ready: document.getElementById('app').dataset.ready,
      bands: Array.from(document.querySelectorAll('.flow-band')).map(b => b.dataset.band + ':' + b.dataset.posture),
      strategyText: (document.querySelector('[data-band="strategy"]') || {}).textContent?.slice(0, 220)
    }));
    throw new Error('DIAG ' + JSON.stringify(diag) + ' :: ' + error.message);
  }); // attention lands on the required next step
  assert.equal(await page.locator('.flow-band[data-posture="active"]').count(), 1,
    'exactly one band holds attention; the rest are conclusions, invitations, or locks');
  const geometry = await page.evaluate(() => ({
    doc: document.body.scrollHeight, viewport: innerWidth,
    compact: Array.from(document.querySelectorAll('.flow-band:not([data-posture="active"])'))
      .map(band => ({ band: band.dataset.band, h: band.offsetHeight })).filter(row => row.h >= 96)
  }));
  assert.ok(geometry.doc < 2600,
    'a declared view with nothing run fits a readable document, was ' + geometry.doc + 'px');
  assert.deepEqual(geometry.compact, [],
    'every non-active band is a compact row (title + one line)');
});

test('Program ONE: explicit Understand and same-hash band actions keep truthful SPA state', async () => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'strategy', 'DIRECTIONAL', 'bullish', '52d');
  await go('#/plan/' + plan.id + '/understand');
  await page.waitForSelector('[data-band="view"][data-posture="active"]');
  await page.evaluate(() => { window.__understandFlow = document.getElementById('plan-flow'); });
  await page.click('#plan-edit-context');
  await page.waitForSelector('[data-band="view"][data-posture="done"]');
  await page.click('#plan-edit-context');
  await page.waitForSelector('[data-band="view"][data-posture="active"]');
  assert.equal(await page.evaluate(() => location.hash), '#/plan/' + plan.id + '/understand');
  assert.equal(await page.evaluate(() => window.__understandFlow === document.getElementById('plan-flow')), true,
    'a same-hash band action reopens locally instead of remounting the Plan');
});

test('Program ONE: canonical routes preserve one mounted document and retire stale refreshes', async () => {
  await page.setViewportSize({ width: 1280, height: 720 });
  const plan = await openPlan('QQQ', 'strategy', 'DIRECTIONAL', 'bearish', '54d');
  await page.evaluate(() => {
    window.__canonicalFlow = document.getElementById('plan-flow');
    window.__canonicalToken = App.navToken;
  });
  await go('#/plan/' + plan.id + '/evidence');
  assert.equal(await page.evaluate(() => window.__canonicalFlow === document.getElementById('plan-flow')), true,
    'stage URLs move attention inside one mounted Plan document');
  assert.equal(await page.evaluate(() => window.__canonicalToken === App.navToken), true,
    'same-Plan stage navigation does not retire the document lifetime or its subscriptions');

  const riskState = await page.evaluate(() => {
    const control = document.getElementById('risk-mode');
    return { before: control.value, next: control.value === 'balanced' ? 'conservative' : 'balanced',
      planRisk: PlanStore.active().context.riskMode };
  });
  await page.focus('#risk-mode');
  await page.click('#risk-mode');
  await page.locator('#risk-mode-popover .choice-option[data-value="' + riskState.next + '"]').click();
  await page.waitForTimeout(100);
  assert.equal(await page.evaluate(() => window.__canonicalFlow === document.getElementById('plan-flow')), true,
    'changing the next-Plan risk default does not remount the current Plan Workspace');
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), 'risk-mode',
    'the header risk control keeps focus after its targeted update');
  assert.equal(await page.evaluate(() => PlanStore.active().context.riskMode), riskState.planRisk,
    'the header default never rewrites the existing Plan risk fact');
  if (riskState.before) {
    await page.click('#risk-mode');
    await page.locator('#risk-mode-popover .choice-option[data-value="' + riskState.before + '"]').click();
  }

  await page.evaluate(id => { location.hash = '#/plan/' + id + '/not-a-stage'; }, plan.id);
  await page.waitForFunction(() => location.hash === '#/home'
    && document.getElementById('app').dataset.route === 'home'
    && document.getElementById('app').dataset.ready === 'true');
  assert.equal(await page.locator('#plan-flow').count(), 0,
    'an invalid Plan stage canonicalizes to Home instead of masquerading as Understand');

  await go('#/research/AAPL');
  await page.evaluate(() => {
    window.__canonicalResearchFlow = document.getElementById('research-flow');
    window.__canonicalResearchToken = App.navToken;
    location.hash = '#/research/AAPL?view=not-a-band';
  });
  await page.waitForFunction(() => location.hash === '#/research/AAPL'
    && document.getElementById('app').dataset.ready === 'true');
  assert.equal(await page.evaluate(() => window.__canonicalResearchFlow === document.getElementById('research-flow')), true,
    'rejecting an invalid Research query keeps the already-mounted canonical document');
  assert.equal(await page.evaluate(() => window.__canonicalResearchToken === App.navToken), true,
    'query canonicalization does not retire live Research context');

  // A lens refresh that loses ownership must never mark or refocus its successor route.
  await page.evaluate(() => {
    window.__resolveOldLens = null;
    App._flowSeam = null;
    const mounted = App._researchSeam;
    mounted.refreshLens = () => new Promise(resolve => { window.__resolveOldLens = resolve; });
    window.__oldLensRefresh = App.refreshLens();
    App.navigate('#/home');
  });
  await page.waitForFunction(() => location.hash === '#/home'
    && document.getElementById('app').dataset.route === 'home'
    && document.getElementById('app').dataset.ready === 'true');
  await page.evaluate(async () => {
    window.__resolveOldLens();
    await window.__oldLensRefresh;
  });
  assert.equal(await page.locator('#app[data-route="home"][data-ready="true"]').count(), 1,
    'a superseded mounted refresh cannot unset or falsely complete the new route');
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.closest('#research-flow') !== null), false,
    'a superseded mounted refresh cannot restore focus into its detached Research document');
});

test('Program ONE: a slow canonical Plan preparation cannot steal the route back', async () => {
  await go('#/home');
  await chooseHeaderRisk('balanced');
  await page.evaluate(() => {
    App.state.researchHandoffBySymbol = {};
    App.context.update({ symbol: 'SPY', goal: null, thesis: 'neutral', horizon: '21d' });
  });
  await go('#/research/SPY');
  await page.click('#symbol-proposed-goal .choice-option[data-value="INCOME"]');

  let releasePrepare;
  let markStarted;
  const prepareGate = new Promise(resolve => { releasePrepare = resolve; });
  const prepareStarted = new Promise(resolve => { markStarted = resolve; });
  const holdRanking = async route => {
    if (route.request().method() === 'POST') {
      markStarted();
      await prepareGate;
    }
    await route.continue();
  };
  await page.route('**/api/plans/*/strategy/run', holdRanking);
  const rankingResponse = page.waitForResponse(response =>
    response.request().method() === 'POST' && /\/api\/plans\/[^/]+\/strategy\/run$/.test(response.url()));
  await page.click('#symbol-find-proposed');
  await prepareStarted;
  await go('#/home');
  releasePrepare();
  await rankingResponse;
  await page.waitForTimeout(300);
  assert.equal(await page.evaluate(() => location.hash), '#/home',
    'the mutation may finish and save, but it cannot focus a Plan after the user leaves');
  assert.equal(await page.locator('#app[data-route="home"][data-ready="true"]').count(), 1,
    'the successor destination remains fully owned after the slow preparation settles');
  await page.unroute('**/api/plans/*/strategy/run', holdRanking);
});

test('Program ONE: public Evidence stages one assumption-safe handoff and creates exactly one Plan', async () => {
  await go('#/home');
  await page.evaluate(() => Learn.setLevel('beginner'));
  await chooseHeaderRisk('balanced');
  const seed = await page.evaluate(async () => {
    const all = (await API.getFresh('/api/plans?scope=all&openOnly=false')).plans || [];
    const used = new Set(all.filter(plan => plan.symbol === 'TSLA' && plan.intent === 'DIRECTIONAL'
      && plan.context && plan.context.thesis === 'bearish')
      .map(plan => Number(plan.context.horizonDays)));
    const horizonDays = [23, 24, 25, 26, 27, 28, 29, 31, 32, 33, 34, 36].find(days => !used.has(days));
    if (!horizonDays) throw new Error('No free TSLA horizon for the canonical handoff regression');
    App.state.researchEvidenceBySymbol = App.state.researchEvidenceBySymbol || {};
    App.state.researchHandoffBySymbol = App.state.researchHandoffBySymbol || {};
    App.state.researchEvidenceBySymbol = {};
    App.state.researchHandoffBySymbol = {};
    App.context.update({ symbol: 'TSLA', goal: null, thesis: null, horizon: null });
    return { before: all.length, horizonDays };
  });

  await go('#/research/TSLA?view=evidence');
  await page.waitForSelector('#tv-setup');
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.click('#tv-view .choice-option[data-value="volatile"]');
  await page.fill('#tv-horizon', String(seed.horizonDays));
  await page.dispatchEvent('#tv-horizon', 'change');
  await page.waitForSelector('#research-outcomes-basis-futures');
  await page.click('#research-outcomes-basis-futures');
  await page.waitForSelector('#whatif-run:not([disabled])');
  await page.fill('#whatif-target', '300');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-act', { timeout: 30000 });
  assert.equal(await page.locator('#whatif-plan-goal').count(), 0,
    'Evidence has no second Plan-goal control or competing creation journey');
  await page.click('#whatif-act');
  await page.waitForFunction(() => location.hash === '#/research/TSLA'
    && document.getElementById('app').dataset.ready === 'true');
  assert.match(await page.textContent('#symbol-proposed-trades .symbol-proposed-carry'), /Possible-futures scenario/,
    'the result returns immediately to the one Ready-to-compare handoff');
  assert.equal(await page.evaluate(async () =>
    ((await API.getFresh('/api/plans?scope=all&openOnly=false')).plans || []).length), seed.before,
  'carrying public Evidence does not create a Plan by itself');

  // A symbol detour may clear the global provisional context; the symbol-owned staged
  // receipt remains the authoritative handoff when the user returns.
  await go('#/research/AAPL');
  await go('#/research/TSLA');
  const restoredHorizon = page.locator('#symbol-proposed-trades .symbol-proposed-starting .chip')
    .filter({ has: page.locator('.chip-label', { hasText: 'Horizon' }) }).locator('b');
  assert.equal((await restoredHorizon.textContent()).trim(), seed.horizonDays + ' sessions',
    'a ticker round-trip restores the staged receipt horizon instead of a default month');
  await page.click('#symbol-proposed-goal .choice-option[data-value="DIRECTIONAL"]');
  assert.equal((await page.textContent('#symbol-find-proposed')).trim(), 'Compare proposed trades',
    'a ticker round-trip restores the staged directional thesis instead of asking for it again');

  const laneOwner = await page.evaluate(() => ({ world: App.state.world,
    dataset: App.config && App.config.activeDataset }));
  await page.evaluate(async () => {
    App.state.world = 'simulated-owner-probe';
    await App.render();
  });
  await page.waitForSelector('#app[data-route="research"][data-ready="true"]');
  assert.equal(await page.locator('#symbol-proposed-trades .symbol-proposed-carry').count(), 0,
    'a same-symbol receipt from the prior economic lane cannot appear in a simulated world');
  await page.evaluate(async prior => {
    App.state.world = prior.world;
    await App.render();
    window.__datasetOwnerFlow = document.getElementById('research-flow');
  }, laneOwner);
  const fakeDatasetConfig = async route => {
    const response = await route.fetch();
    const body = await response.json();
    body.activeDataset = 'dataset-owner-probe';
    body.activeDatasetName = 'Remote dataset owner probe';
    body.scenarioMode = true;
    await route.fulfill({ response, json: body });
  };
  await page.route('**/api/config', fakeDatasetConfig);
  await page.evaluate(() => App.emitEvent('dataset.selected', { datasetId: 'dataset-owner-probe' }));
  await page.waitForFunction(() => App.config.activeDataset === 'dataset-owner-probe'
    && document.getElementById('app').dataset.ready === 'true'
    && document.getElementById('symbol-proposed-trades')
    && !document.querySelector('#symbol-proposed-trades .symbol-proposed-carry'));
  assert.equal(await page.locator('#symbol-proposed-trades .symbol-proposed-carry').count(), 0,
    'a same-symbol receipt from another analysis dataset cannot be applied');
  assert.equal(await page.evaluate(() => window.__datasetOwnerFlow === document.getElementById('research-flow')), true,
    'a remote dataset selection refreshes the mounted Evidence owner without remounting Research');
  await page.unroute('**/api/config', fakeDatasetConfig);
  await page.evaluate(() => App.emitEvent('dataset.selected', { datasetId: 'observed' }));
  await page.waitForFunction(expected => (App.config.activeDataset || null) === (expected || null)
    && document.getElementById('app').dataset.ready === 'true', laneOwner.dataset);
  await page.waitForSelector('#symbol-proposed-trades .symbol-proposed-carry');
  assert.match(await page.textContent('#symbol-proposed-trades .symbol-proposed-carry'), /Possible-futures scenario/,
    'returning to the original lane and dataset restores only its own staged receipt');

  // Changing any causal assumption retires both the staged receipt and its rendered result.
  await openResearchTab('view');
  await page.fill('#tv-horizon', '');
  await page.dispatchEvent('#tv-horizon', 'change');
  assert.equal(await page.evaluate(() => Object.keys(App.state.researchHandoffBySymbol || {}).length > 0), false,
    'making the causal question incomplete also retires a receipt authored under its old horizon');
  await page.click('#tv-view .choice-option[data-value="bearish"]');
  await page.fill('#tv-horizon', String(seed.horizonDays));
  await page.dispatchEvent('#tv-horizon', 'change');
  assert.equal(await page.evaluate(() => Object.keys(App.state.researchHandoffBySymbol || {}).length > 0), false,
    'a receipt authored under the prior thesis cannot merge into the new question');
  assert.equal(await page.locator('#symbol-proposed-trades .symbol-proposed-carry').count(), 0,
    'the folded Overview card does not advertise a stale incompatible receipt');
  await page.click('#research-outcomes-basis-futures');
  await page.waitForSelector('#whatif-run:not([disabled])');
  assert.equal(await page.locator('#whatif-act').count(), 0,
    'the old fan is not relabeled under the changed thesis');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-act', { timeout: 30000 });
  const publicFan = await page.evaluate(() => {
    const owned = Object.values(App.state.researchEvidenceBySymbol || {});
    for (const state of owned) {
      const evidence = state && (state.evidence || state);
      const run = evidence && evidence.whatifResults && evidence.whatifResults.TSLA;
      if (run && run.ensemble) return {
        id: run.ensemble.id,
        fingerprint: run.ensemble.fingerprint
      };
    }
    return null;
  });
  assert.ok(publicFan && /^rer_/.test(publicFan.id) && /^[a-f0-9]{64}$/.test(publicFan.fingerprint),
    'public Possible Futures exposes the opaque server receipt and exact artifact fingerprint');
  await page.click('#whatif-act');
  await page.waitForFunction(() => location.hash === '#/research/TSLA'
    && document.getElementById('app').dataset.ready === 'true');
  const stagedFan = await page.evaluate(() => {
    const staged = Object.values(App.state.researchHandoffBySymbol || {})
      .find(value => value && value.scenario);
    return staged && staged.scenario;
  });
  assert.deepEqual(stagedFan, { researchReceiptId: publicFan.id, fingerprint: publicFan.fingerprint },
    'the Ready-to-compare handoff carries only the exact opaque receipt identity');
  await page.click('#symbol-proposed-goal .choice-option[data-value="DIRECTIONAL"]');

  let failedAdoptionBody = null;
  const failEnsemble = async route => {
    if (route.request().method() === 'POST') {
      failedAdoptionBody = route.request().postDataJSON();
      await route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ error: 'forced staged-receipt failure' }) });
    } else await route.continue();
  };
  await page.route('**/api/plans/*/outcomes/ensemble', failEnsemble);
  await page.click('#symbol-find-proposed');
  await page.waitForFunction(() => /^#\/plan\/plan_[^/]+\/strategy$/.test(location.hash)
    && document.getElementById('app').dataset.ready === 'true', null, { timeout: 30000 });
  const createdPlanId = await page.evaluate(() => location.hash.split('/')[2]);
  assert.equal(await page.evaluate(() => PlanStore.active().context.targetCents), 30000,
    'the staged decision target survives the ticker round-trip and canonical handoff exactly');
  const afterFailure = await page.evaluate(async () =>
    ((await API.getFresh('/api/plans?scope=all&openOnly=false')).plans || []).length);
  assert.equal(afterFailure, seed.before + 1,
    'the canonical handoff creates exactly one durable Plan even when a receipt save fails');
  assert.match(await page.textContent('#plan-strategy-body'), /Some Evidence did not finish carrying/,
    'partial commit is named on the arrival screen while the failed receipt remains retryable');
  assert.equal(await page.evaluate(() => Object.keys(App.state.researchHandoffBySymbol || {}).length > 0), true,
    'the failed receipt remains staged for an idempotent retry');
  assert.deepEqual(failedAdoptionBody, {
    expectedVersion: failedAdoptionBody && failedAdoptionBody.expectedVersion,
    researchReceiptId: publicFan.id,
    expectedFingerprint: publicFan.fingerprint
  }, 'the canonical Plan endpoint receives no alternate spec, IV, levels, or regeneration inputs');
  const firstStrategyRunId = await page.evaluate(async id => {
    const latest = await PlanStore.latestStrategy(id, true);
    return latest && latest.strategy && latest.strategy.result && latest.strategy.result.strategyRunId;
  }, createdPlanId);
  assert.ok(firstStrategyRunId, 'the first handoff completed one durable ranking receipt');
  await page.unroute('**/api/plans/*/outcomes/ensemble', failEnsemble);

  let successfulAdoptionBody = null;
  const captureEnsemble = async route => {
    if (route.request().method() === 'POST') successfulAdoptionBody = route.request().postDataJSON();
    await route.continue();
  };
  await page.route('**/api/plans/*/outcomes/ensemble', captureEnsemble);
  await go('#/research/TSLA');
  await page.waitForSelector('#symbol-proposed-trades .symbol-proposed-carry');
  assert.equal(await page.textContent('#symbol-find-proposed'), 'Retry Evidence carry',
    'a partial carry resumes its exact durable Plan instead of asking for the goal again');
  assert.match(await page.textContent('#symbol-proposed-goal'), /Existing Plan[\s\S]*Trade a view/i,
    'the retry names the already-declared Plan target and exposes no alternate destination');
  assert.equal(await page.isEnabled('#symbol-find-proposed'), true,
    'the exact retry remains actionable without recreating the Plan declaration');
  await page.click('#symbol-find-proposed');
  await page.waitForFunction(id => location.hash === '#/plan/' + id + '/strategy'
    && document.getElementById('app').dataset.ready === 'true', createdPlanId, { timeout: 30000 });
  assert.equal(await page.evaluate(async () =>
    ((await API.getFresh('/api/plans?scope=all&openOnly=false')).plans || []).length), afterFailure,
  'retry reuses the same Plan instead of duplicating the journey');
  assert.equal(await page.evaluate(async id => {
    const latest = await PlanStore.latestStrategy(id, true);
    return latest && latest.strategy && latest.strategy.result && latest.strategy.result.strategyRunId;
  }, createdPlanId), firstStrategyRunId,
  'an Evidence-only retry preserves the already-successful ranking receipt');
  assert.equal(await page.evaluate(() => Object.keys(App.state.researchHandoffBySymbol || {}).length > 0), false,
    'a successful retry consumes the staged receipt');
  assert.equal(await page.locator('#plan-strategy-body .alert:has-text("Some Evidence did not finish")').count(), 0,
    'the partial-commit warning clears after the receipt is saved');
  await page.unroute('**/api/plans/*/outcomes/ensemble', captureEnsemble);
  assert.deepEqual(successfulAdoptionBody, {
    expectedVersion: successfulAdoptionBody && successfulAdoptionBody.expectedVersion,
    researchReceiptId: publicFan.id,
    expectedFingerprint: publicFan.fingerprint
  }, 'the successful retry promotes the same receipt through the same canonical endpoint');
  const ensembleReceipt = await page.evaluate(async id => {
    const saved = await API.getFresh('/api/plans/' + id + '/outcomes/ensemble/latest');
    return { id: saved && saved.ensemble && saved.ensemble.id,
      fingerprint: saved && saved.ensemble && saved.ensemble.fingerprint };
  }, createdPlanId);
  assert.ok(ensembleReceipt.id && ensembleReceipt.id !== publicFan.id,
    'the public receipt is promoted into a durable Plan-owned ensemble identity');
  assert.equal(ensembleReceipt.fingerprint, publicFan.fingerprint,
    'the reused Plan durably owns the exact fan shown in public Evidence, never a rerun');

  // The other Evidence lens uses the same handoff and augments this same Plan, rather than
  // exposing its own Plan creator.
  await go('#/research/TSLA?view=evidence');
  await page.click('#research-outcomes-basis-past');
  await page.waitForSelector('#study-run:not([disabled])');
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 30000 });
  await page.waitForSelector('#tv-test-analogs', { timeout: 15000 });
  await page.click('#tv-test-analogs');
  await page.waitForFunction(() => location.hash === '#/research/TSLA'
    && document.getElementById('app').dataset.ready === 'true');
  assert.match(await page.textContent('#symbol-proposed-trades .symbol-proposed-carry'), /Historical study/,
    'the analog study returns to the same Ready-to-compare handoff');
  await page.click('#symbol-find-proposed');
  await page.waitForFunction(id => location.hash === '#/plan/' + id + '/strategy'
    && document.getElementById('app').dataset.ready === 'true', createdPlanId, { timeout: 30000 });
  assert.equal(await page.evaluate(async () =>
    ((await API.getFresh('/api/plans?scope=all&openOnly=false')).plans || []).length), afterFailure,
  'the analog receipt also reuses the exact same Plan');
  const analogReceipt = await page.evaluate(async id => {
    const saved = await API.getFresh('/api/plans/' + id + '/evidence/latest');
    return { hasRequest: !!(saved && saved.evidence && saved.evidence.request),
      studyKey: saved && saved.evidence && saved.evidence.result && saved.evidence.result.studyKey,
      selection: PlanStore.ui(id).evidence && PlanStore.ui(id).evidence.analogSelection };
  }, createdPlanId);
  assert.ok(analogReceipt.hasRequest && analogReceipt.studyKey
    && analogReceipt.selection && analogReceipt.selection.studyKey,
  'the exact historical-study request, result, and selected analog receipt remain attached');
  assert.equal(analogReceipt.studyKey, analogReceipt.selection.studyKey,
    'the durable Plan study is the exact content-keyed result shown in public Evidence');
});

test('Program ONE: the evidence fan survives attention moves without re-rendering', async () => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.evaluate(() => Learn.setLevel('beginner'));
  // A unique horizon guarantees a genuinely fresh plan — creation dedupes on matching context.
  const plan = await openPlan('AAPL', 'evidence', 'DIRECTIONAL', 'bullish', '41d');
  await page.click('#tv-setup .choice-option[data-value="pullback_rebound"]');
  await page.waitForSelector('#research-outcomes-basis-futures');
  await page.click('#research-outcomes-basis-futures');
  await page.waitForSelector('#whatif-run');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out svg', { timeout: 30000 });
  await page.evaluate(() => { window.__fanCanvas = document.querySelector('#whatif-out svg'); });

  // Attention moves to strategy IN PLACE: the workspace document survives the navigation.
  await page.evaluate(() => { window.__flowNode = document.getElementById('plan-flow'); });
  await go('#/plan/' + plan.id + '/strategy');
  assert.equal(await page.evaluate(() => window.__flowNode === document.getElementById('plan-flow')), true,
    'same-plan stage navigation is a position change inside one live document, never a teardown');
  await page.waitForSelector('[data-band="evidence"].is-folded');
  assert.equal(await page.evaluate(() => window.__fanCanvas.isConnected), true,
    'the folded evidence band keeps its rendered fan mounted');

  // Reopening is one tap and greets the user with the SAME canvas — zero re-render.
  await page.click('[data-band="evidence"] .flow-band-invitation');
  await page.waitForSelector('[data-band="evidence"]:not(.is-folded)');
  assert.equal(await page.evaluate(() => location.hash), '#/plan/' + plan.id + '/evidence',
    'opening a Plan band updates the truthful deep link and browser history');
  assert.equal(await page.evaluate(() => window.__flowNode === document.getElementById('plan-flow')), true,
    'the routed invitation still moves inside the same mounted Plan document');
  assert.equal(await page.evaluate(() =>
    window.__fanCanvas === document.querySelector('#whatif-out svg')), true,
    'the same canvas node survives fold and reopen');
});
