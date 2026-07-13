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
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg;
let planSequence = 0;

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
  await page.evaluate(h => {
    // A same-hash visit is still a navigation in the test contract. Force readiness
    // false before either path so waitForSelector can never observe the outgoing view.
    document.getElementById('app').setAttribute('data-ready', 'false');
    if (window.location.hash === h) return App.render();
    window.location.hash = h;
  }, hash);
  await page.waitForSelector('#app[data-ready="true"]');
}

async function openPlan(symbol, stage = 'understand', intentOverride, thesisOverride) {
  const context = await page.evaluate(() => ({
    goal: App.context.goal('DIRECTIONAL'),
    thesis: App.context.thesis('bullish'),
    horizon: App.context.horizon('month')
  }));
  const horizonDays = context.horizon === '0DTE' ? 1 : context.horizon === 'week' ? 7
    : context.horizon === 'quarter' ? 63 : /^\d+d$/.test(context.horizon || '')
      ? parseInt(context.horizon, 10) : 30;
  const response = await fetch(BASE + '/api/plans', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clientRequestId: 'dom-plan-' + (++planSequence), symbol,
      intent: intentOverride || 'DIRECTIONAL', thesis: thesisOverride || context.thesis, horizonDays, riskMode: 'conservative' })
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
  const stage = key === 'view' ? 'Evidence' : key === 'options' ? 'Strategy' : 'Understand';
  const stagePath = key === 'view' ? 'evidence' : key === 'options' ? 'strategy' : 'understand';
  const button = page.locator('.plan-rail button').filter({ hasText: stage });
  assert.equal(await button.count(), 1, 'Plan stage ' + stage + ' exists');
  await button.click();
  await page.waitForFunction(path => location.hash.endsWith('/' + path)
    && document.getElementById('app').getAttribute('data-route') === 'plan'
    && document.getElementById('app').getAttribute('data-ready') === 'true', stagePath);
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

async function promoteEvidencePlan() {
  await page.click('#plan-evidence-first');
  const outcome = await page.waitForFunction(() => {
    if (/^#\/plan\/plan_[^/]+\/evidence$/.test(location.hash)) return 'created';
    const match = document.querySelector('.plan-existing-match');
    if (match) return 'match';
    const routeError = document.querySelector('#route-error, #plan-start .alert-danger');
    return routeError ? 'error:' + routeError.textContent.trim() : null;
  }, null, { timeout: 15000 });
  const state = await outcome.jsonValue();
  assert.doesNotMatch(state, /^error:/, 'promoting Research into a Plan failed visibly: ' + state);
  if (state === 'match') {
    const createSeparate = page.locator('.plan-existing-match button').filter({ hasText: 'Create separate Plan' });
    assert.equal(await createSeparate.count(), 1, 'a matching Plan offers one explicit separate-Plan choice');
    await createSeparate.click();
  }
  await page.waitForFunction(() => /^#\/plan\/plan_[^/]+\/evidence$/.test(location.hash), null,
    { timeout: 15000 });
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
}

before(async () => {
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' },
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
  if (welcomeVerdict === 'UNFAVORABLE' || welcomeVerdict === 'UNAVAILABLE') {
    assert.match(await page.textContent('#welcome-proof-role'), /HOW STRIKEBENCH SAYS NO/);
    assert.match(await page.textContent('#welcome-proof-caption'), /counterexample, not an endorsement/i,
      'an adverse teaching case cannot sit under recommendation-shaped welcome framing');
  }
  // Skip to the dashboard; the choice persists
  await page.click('#welcome-skip');
  await page.waitForSelector('.home-market-grid .tile');
  await page.waitForFunction(() => document.querySelectorAll('.home-market-grid .spark-svg').length >= 4,
    { timeout: 15000 });
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
  const footer = await page.textContent('#disclaimer');
  assert.match(footer, /not financial advice/i);
  // The tour is a canonical Home subview.
  await go('#/home/tour');
  await page.waitForSelector('#welcome-hero');
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'home');
  // BRAND RETURNS TO THE OPERATIONAL DESK (review P5): from any screen the mark lands on
  // Home, never a surprise tour; the tour keeps its own quiet entry at the page bottom.
  await openPlan('AAPL');
  await page.waitForSelector('.quote-hero');
  await page.click('.brand');
  await page.waitForSelector('.home-market-grid .tile');
  await page.waitForFunction(() => document.querySelectorAll('.home-market-grid .spark-svg').length >= 4,
    { timeout: 15000 });
  assert.equal(await page.evaluate(() => window.location.hash), '#/home', 'brand goes to the desk');
  assert.ok(await page.locator('.brand .brand-mark').count(), 'the SVG mark renders in the header');
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

test('plan foundation promotes once, survives reload, versions assumptions, and closes only the chip', async () => {
  await go('#/plan/new?symbol=AAPL');
  await page.waitForSelector('#plan-start');
  await page.waitForSelector('#research-hero .quote-hero');
  assert.equal(await page.locator('.plan-rail').count(), 0,
    'opening a stock is full Research; no Plan journey exists before the user chooses a goal');
  assert.ok(await page.locator('#events-card .event-timeline').count(), 'Research shows dated events as a timeline');
  assert.equal(await page.locator('.market-facts .info-trigger').count(), 4,
    'technical market facts carry visible explanations');
  assert.equal(await page.locator('#research-hero .xp.open').count(), 1,
    'desktop Research opens useful market detail instead of hiding it');
  await page.locator('#plan-start .choice-card').filter({ hasText: 'Earn income' }).click();
  await page.waitForFunction(() => /^#\/plan\/plan_[^/]+\/understand$/.test(location.hash));
  await page.waitForSelector('#plan-header, #route-error');
  assert.equal(await page.locator('#route-error').count(), 0,
    'persisted plan renders without a route error: ' + (await page.locator('#app').textContent()));
  const planHash = await page.evaluate(() => location.hash);
  assert.match(await page.textContent('#plan-header'), /AAPL.*Earn income.*Demo market/s);
  assert.equal(await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + planHash.split('/')[2] + '"]').count(), 1,
    'the promoted durable plan appears exactly once even when other plans are open');
  await page.waitForSelector('#research-symbol');
  assert.equal(await page.locator('.plan-rail li').count(), 6, 'the full six-stage journey appears after Plan creation');
  assert.equal(await page.locator('.plan-rail li').last().locator('button').isDisabled(), true,
    'Manage & Review is gated before a decision');
  assert.equal(await page.locator('#plan-archive').count(), 1,
    'a working Plan can be archived without deleting its evidence or history');
  await page.waitForSelector('#history-card');
  assert.equal(await page.locator('#research-workspace-tabs').count(), 0,
    'the old Research-local workspace is gone; the Plan rail owns navigation');
  assert.ok(await page.locator('#events-card').count(), 'Understand owns dated events');
  assert.ok(await page.locator('#news-overview-card').count(), 'Understand owns source-backed news');

  await page.click('#plan-edit-context');
  assert.match(await page.textContent('#plan-context-editor'), /Goal:.*Earn income.*linked Plan/s,
    'goal identity is visible and has an explicit fork path');
  await page.fill('#plan-horizon-days', '45');
  await page.selectOption('#plan-thesis', 'bearish');
  await page.click('#plan-save-context');
  await page.waitForFunction(() => /45 days/.test(document.getElementById('plan-header')?.textContent || ''));
  await page.reload();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  assert.equal(await page.evaluate(() => location.hash), planHash);
  assert.match(await page.textContent('#plan-header'), /45 days/);
  assert.match(await page.textContent('#plan-header'), /AAPL.*Earn income/s);

  // Capture after the real entrance transition settles; production animations remain enabled.
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p2-understand-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(500);
  const mobileRail = await page.evaluate(() => {
    const rail = document.querySelector('.plan-rail').getBoundingClientRect();
    return Array.from(document.querySelectorAll('.plan-rail button')).map(button => {
      const r = button.getBoundingClientRect();
      return { label: button.textContent.trim(), left: r.left, right: r.right,
        top: r.top, bottom: r.bottom, railLeft: rail.left, railRight: rail.right };
    });
  });
  assert.equal(mobileRail.length, 6, 'all six Plan stages remain rendered on mobile');
  for (const stage of mobileRail) {
    assert.ok(stage.left >= stage.railLeft - 1 && stage.right <= stage.railRight + 1,
      'mobile stage is fully visible rather than clipped: ' + JSON.stringify(stage));
  }
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p2-mobile-rail.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });

  // The 45-day edit above proves context persistence. Use a short evidence window for the
  // event-study assertion so the fixture has enough independent signal episodes to evaluate.
  await page.click('#plan-edit-context');
  await page.fill('#plan-horizon-days', '10');
  await page.click('#plan-save-context');
  await page.waitForFunction(() => /10 days/.test(document.getElementById('plan-header')?.textContent || ''));

  await page.locator('.plan-rail button').filter({ hasText: 'Evidence' }).click();
  await page.waitForSelector('#test-your-view');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  const studyText = await page.textContent('#study-results');
  assert.match(studyText, /Signal episodes/);

  await page.click('#research-outcomes-basis-futures');
  await page.waitForSelector('#whatif-card');
  await page.click('#whatif-run');
  await page.waitForSelector('.scenario-decision', { timeout: 25000 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(4200);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p2-evidence.png'), fullPage: true });

  await page.reload();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await page.waitForSelector('#tv-futures', { timeout: 15000 });
  assert.match(await page.evaluate(() => location.hash), /\/evidence$/,
    'reload returns to the durable Evidence stage');
  await page.click('#research-outcomes-basis-past');
  await page.waitForFunction(() => /Signal episodes/.test(document.getElementById('study-results')?.textContent || ''),
    { timeout: 20000 });
  assert.match(await page.textContent('#study-results'), /Signal episodes/,
    'the exact normalized study result restores after reload');

  const closedPlanId = planHash.split('/')[2];
  await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + closedPlanId + '"] .plan-chip-close').click();
  await page.waitForFunction((id) => !document.querySelector('.plan-chip[data-plan-id="' + id + '"]'), closedPlanId);
  assert.equal(await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + closedPlanId + '"]').count(), 0,
    'closing a chip removes only that durable plan from the open collection');
});

test('Plan stages orient both levels without hiding capabilities or stealing the journey', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('MSFT', 'understand');
  await page.waitForSelector('#plan-stage-understand');
  assert.equal(await page.locator('.plan-rail button').count(), 6, 'the whole Plan remains visible');
  assert.match(await page.textContent('.plan-stage-carry'), /MSFT.*Demo market/s,
    'the stage names the context carried into it');
  assert.match(await page.textContent('.plan-stage-heading'), /What is this market doing.*Understand MSFT/s,
    'the stage states the question it answers');
  assert.equal(await page.locator('.plan-next-action[data-recommended-next="EVIDENCE"]').count(), 1,
    'Beginner sees one highlighted forward action from Understand');

  await page.locator('.plan-rail button').filter({ hasText: 'Evidence' }).click();
  await page.waitForSelector('#plan-stage-evidence');
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id),
    'plan-stage-title-evidence', 'a stage change moves focus to the destination heading');
  assert.equal(await page.locator('.plan-rail [aria-current="step"]').count(), 1);
  assert.equal(await page.locator('.plan-next-action[data-recommended-next="STRATEGY"]').count(), 1);

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#plan-stage-evidence');
  assert.equal(await page.locator('.plan-header-receipt:visible').count(), 1,
    'Expert sees the compact Plan and context receipt');
  assert.match(await page.textContent('.plan-header-receipt'), /Plan v\d+.*Context r\d+/s);
  assert.match(await page.textContent('.plan-stage-carry-receipt'), /context r\d+.*plan v\d+/s);
  assert.equal(await page.locator('.plan-rail button').count(), 6,
    'Expert and Beginner have the same stage reachability');

  await page.locator('.plan-rail button').filter({ hasText: 'Strategy' }).click();
  await page.waitForSelector('#plan-strategy-body');
  assert.equal(await page.locator('.plan-tool').count(), 4,
    'Compare, Build, Chain, and Scout stay reachable from the Plan');
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id),
    'plan-stage-title-strategy');
});

test('Plan Strategy owns the ranked field, exact Builder, and chain without route handoffs', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'strategy');
  await page.waitForSelector('#plan-strategy-body', { timeout: 15000 }).catch(async error => {
    throw new Error('Strategy stage did not compose: ' + (await page.locator('#app').textContent())
      + '\n' + error.message);
  });
  assert.equal(await page.locator('.plan-tool').count(), 4, 'one in-stage selector owns all Strategy tools');
  assert.match(await page.textContent('.plan-tool-selector'), /Proposed trades.*All strategies.*Option prices.*Scout/s,
    'Beginner gets plain, capability-oriented Strategy labels');
  assert.ok(await page.locator('#plan-strategy-filters .xp-head:has-text("Only show proposed trades")').count(),
    'Beginner keeps all five limits behind progressive disclosure');
  assert.equal(await page.locator('#plan-stage-strategy a[href^="#/trade"], #plan-stage-strategy button:has-text("Open strategy tools")').count(), 0,
    'Strategy has no cross-section Trade handoff');

  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .candidate', { timeout: 30000 });
  assert.match(await page.textContent('#plan-strategy-results'), /TOP PROPOSED TRADE.*Other ranked structures/s,
    'Beginner sees a clear proposal first without losing the rest of the ranked field');
  const rankedText = await page.textContent('#plan-stage-strategy');
  assert.match(rankedText, /Cash \/ no trade/, 'the no-trade baseline remains in the ranked decision field');
  assert.ok(await page.locator('#plan-strategy-results .candidate[data-economic-verdict]').count() >= 1,
    'every proposed structure carries its economic placement');
  const economics = await page.locator('#plan-strategy-results .candidate .economic-assessment').allTextContents();
  assert.ok(economics.length > 0 && economics.every(text => /Market-implied EV/.test(text) && /Realized-vol scenario EV/.test(text)),
    'both EV lanes remain co-equal on the Plan candidate cards');
  assert.ok(await page.locator('#plan-stage-strategy .xp-head:has-text("structures were refused")').count(),
    'mechanically or economically refused structures remain inspectable with reasons');

  await page.locator('#plan-strategy-filters .xp-head').click();
  await page.fill('#plan-f-pop', '70');
  await page.locator('#plan-f-pop').blur();
  await page.waitForSelector('#plan-stage-strategy:has-text("Limits changed — rerun the field")');
  assert.equal(await page.locator('#plan-strategy-results .candidate').count(), 0,
    'a changed request cannot leave the prior ranked field under new limits');
  await page.locator('#plan-strategy-filters .xp-head').click();
  await page.fill('#plan-f-pop', '');
  await page.locator('#plan-f-pop').blur();
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .candidate', { timeout: 30000 });
  const first = page.locator('#plan-strategy-results .candidate').first();
  assert.match(await first.textContent(), /Theoretical|Chance of any profit/);
  assert.match(await first.textContent(), /How you would manage this trade/,
    'Beginner retains the plain-language management capability from the decision analysis');
  await first.locator('button').filter({ hasText: 'Select this structure' }).click();
  await page.waitForSelector('#plan-strategy-results button:has-text("Selected for this Plan")');
  await first.scrollIntoViewIfNeeded();
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p3-strategy-compare.png'), fullPage: false });

  await page.reload();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await page.waitForSelector('#plan-strategy-results button:has-text("Selected for this Plan")', { timeout: 20000 });
  assert.match(await page.evaluate(() => location.hash), new RegExp('/plan/' + plan.id + '/strategy$'),
    'reload restores the Plan-owned Strategy stage');

  await page.locator('.plan-tool').filter({ hasText: 'All strategies' }).click();
  await page.waitForSelector('#builder');
  await page.waitForSelector('#builder-catalog .tpl');
  assert.ok(await page.locator('#builder-catalog .tpl-shape').count() >= 20,
    'the visual all-strategies catalog remains a first-class Beginner capability');
  assert.equal(await page.locator('#builder-symbol-context').count(), 0,
    'the Builder does not recapture the Plan symbol');
  assert.match(await page.textContent('#plan-header'), /AAPL/);
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p3-strategy-builder.png'), fullPage: true });

  await page.locator('.plan-tool').filter({ hasText: 'Option prices' }).click();
  await page.waitForSelector('#chain-anchor .card, #chain-anchor + .card, #expiration-select', { timeout: 20000 });
  assert.equal(await page.locator('#symbol-actions').count(), 0,
    'the Chain tool does not duplicate the old strategy-action surface');
  assert.equal(await page.locator('a[href="#/trade/structure"]').count(), 0,
    'the Plan chain cannot escape to the removed Structure route');

  await page.locator('.plan-tool').filter({ hasText: 'Scout' }).click();
  await page.waitForSelector('#plan-run-scout');
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
  await page.locator('.plan-scout-scopes button').filter({ hasText: 'Alternatives' }).click();
  await page.click('#plan-run-scout');
  await page.waitForSelector('#plan-scout-results .candidate', { timeout: 30000 });
  const scoutedSymbol = (await page.locator('#plan-scout-results .plan-scout-symbol b').first().textContent()).trim();
  assert.notEqual(scoutedSymbol, 'AAPL', 'in-Plan Scout returns a separate underlying');
  await page.locator('#plan-scout-results button').filter({ hasText: 'Open as linked Plan' }).first().click();
  await page.waitForFunction((symbol) => location.hash.includes('/plan/')
    && document.querySelector('#plan-header h1')?.textContent.includes(symbol), scoutedSymbol);
  await page.waitForSelector('#plan-strategy-body');
  assert.match(await page.textContent('#plan-header'), new RegExp(scoutedSymbol),
    'the pick opens as a linked sibling rather than changing the origin Plan symbol');
  assert.ok(await page.locator('#plan-strategy-results button:has-text("Selected for this Plan")').count()
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
  await openPlan('QQQ', 'strategy', 'ACQUIRE');
  await page.waitForSelector('#plan-intent-ladder', { timeout: 30000 }).catch(async error => {
    throw new Error('Intent ladder did not render: ' + (await page.locator('#app').textContent()) + '\n' + error.message);
  });
  assert.match(await page.textContent('#plan-intent-ladder'), /Name your buy price.*Buy strike.*Chance you buy/s,
    'Buy-at-a-discount leads with a strike ladder rather than a generic strategy list');
  assert.ok(await page.locator('#plan-intent-ladder .ladder-row').count() >= 2, 'multiple price rungs remain selectable');
  await page.waitForTimeout(300); // observe the real card-arrival animation; never disable it for screenshots
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p11-ladder-beginner.png'), fullPage: true });
  await page.locator('#plan-intent-ladder .ladder-row .btn:has-text("Select this rung")').first().click();
  await page.waitForSelector('.plan-selected-structure, #plan-strategy-results button:has-text("Selected for this Plan")');

  const incomePlan = await openPlan('SPY', 'strategy', 'INCOME');
  await page.waitForSelector('#plan-income-board');
  assert.match(await page.textContent('#plan-income-board'), /Free buying power.*Shares available/s,
    'income keeps its capital-and-collateral picture');
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p11-income-beginner.png'), fullPage: true });

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#plan-stage-strategy');
  assert.match(await page.textContent('.plan-tool-selector'), /Ranked field.*Builder.*Chain.*Scout/s,
    'Expert receives dense professional tool labels');
  assert.equal(await page.locator('#plan-strategy-filters .plan-filter-grid input').count(), 5,
    'Expert sees the same five limits inline');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results table tbody tr', { timeout: 30000 });
  const expertRows = page.locator('#plan-strategy-results table tbody tr.clickable');
  assert.ok(await expertRows.count() >= 1, 'Expert ranked field has auditable rows');
  await expertRows.first().click();
  await page.waitForSelector('#plan-candidate-detail .candidate-evaluation-receipt');
  const expertDetail = await page.textContent('#plan-candidate-detail');
  assert.match(expertDetail, /Evidence by input/, 'Expert retains per-input evidence behind the ranking');
  assert.match(expertDetail, /How the Decision score was built/, 'Expert retains the score construction');
  assert.match(expertDetail, /Mechanical management plan/, 'Expert retains the management receipt');
  await page.locator('#plan-candidate-detail').scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p14-strategy-audit-expert.png'), fullPage: false });
  const plansBeforeExpertSelect = await page.evaluate(async () => {
    const payload = await API.getFresh('/api/plans');
    return Array.isArray(payload) ? payload.length : (payload.plans || []).length;
  });
  await expertRows.first().locator('button').filter({ hasText: /^Select$/ }).click();
  await page.waitForSelector('#plan-strategy-results button:has-text("Selected")');
  const plansAfterExpertSelect = await page.evaluate(async () => {
    const payload = await API.getFresh('/api/plans');
    return Array.isArray(payload) ? payload.length : (payload.plans || []).length;
  });
  assert.equal(plansAfterExpertSelect, plansBeforeExpertSelect,
    'selecting an Expert ranked row updates this Plan and never creates a duplicate Plan');
  assert.match(await page.evaluate(() => location.hash), new RegExp('/plan/' + incomePlan.id + '/strategy$'),
    'the ranked-row action remains inside the current Plan');
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
  await page.click('#builder-catalog .tpl[data-tpl="IRON_CONDOR"]');
  await page.waitForSelector('#bw-walk');
  await page.waitForFunction(() => /Theoretical worst case/.test(
    document.getElementById('bw-impact')?.textContent || ''), { timeout: 20000 });
  assert.match(await page.textContent('#bw-leg-story'), /Sell the \$\d+/,
    'the walkthrough explains what the current contract contributes');
  assert.match(await page.textContent('#bw-impact'), /Theoretical worst case/);
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

test('Plan Outcomes reuses Evidence paths for one exact selected package', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'evidence');
  await page.waitForSelector('#test-your-view', { timeout: 15000 });
  await page.click('#research-outcomes-basis-futures');
  await page.waitForSelector('#whatif-card');
  await page.click('#whatif-run');
  await page.waitForSelector('.scenario-decision', { timeout: 30000 });
  const receipt = await page.evaluate(() => {
    const ui = PlanStore.ui(App.state.activePlanId).evidence;
    return { id: ui.planEnsembleId, fingerprint: ui.planEnsembleFingerprint };
  });
  assert.ok(receipt.id && receipt.fingerprint, 'Evidence saves the exact ensemble before Outcomes uses it');

  await page.locator('.plan-rail button').filter({ hasText: 'Strategy' }).click();
  await page.waitForSelector('#plan-run-strategy');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .candidate', { timeout: 30000 });
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
  await page.waitForSelector('#plan-strategy-results button:has-text("Selected for this Plan")');

  await page.locator('.plan-rail button').filter({ hasText: 'Outcomes' }).click();
  await page.waitForSelector('#plan-outcomes');
  assert.match(await page.textContent('.plan-outcome-position'), /Exact position being tested.*PLAN OWNED/s);
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
  assert.match(await page.textContent('#plan-outcomes-panel'), /Replay.*AAPL|Historical replay/i);
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

test('a selected Plan future becomes one exact managed rehearsal with a durable receipt', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'evidence');
  await page.waitForSelector('#test-your-view', { timeout: 15000 });
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
  assert.match(await page.textContent('#sim-control-room'), /Exact Plan rehearsal.*path 1.*receipt/is);
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
  assert.match(await page.textContent('#plan-stage-manage-review'), /sim rehearsal|rehearsal result/i,
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
  await page.waitForSelector('#home-plan-library .home-plan-grid', { timeout: 20000 }).catch(async error => {
    throw new Error('Home Plan library did not settle: hash=' + await page.evaluate(() => location.hash)
      + ' routeError=' + await page.locator('#route-error').count()
      + ' text=' + (await page.locator('#app').textContent()).slice(0, 3000) + '\n' + error.message);
  });
  assert.equal(await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + ids.simOne + '"]').count(), 1);
  assert.equal(await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + ids.simTwo + '"]').count(), 1);
  assert.equal(await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + ids.second + '"]').count(), 0,
    'the Plan bar shows only the current execution market');
  assert.equal(await page.locator('#home-plan-library [data-plan-id="' + ids.first + '"]').count(), 1,
    'the Home library retains a closed Plan from another market');
  assert.match(await page.locator('#home-plan-library [data-plan-id="' + ids.first + '"] .home-plan-actions .btn:not(.btn-secondary)').textContent(),
    /Switch market & open/);
  assert.match(await page.locator('#home-plan-library [data-plan-id="' + ids.first + '"]').textContent(),
    /Switches market when opened/,
    'an inactive-market Plan never borrows a same-symbol quote from the active market');
  await page.waitForTimeout(4200);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p8-library-desktop.png'), fullPage: true });

  await page.locator('#home-plan-library [data-plan-id="' + ids.first + '"] .home-plan-actions .btn:not(.btn-secondary)').click();
  await page.waitForFunction(id => location.hash.includes('/plan/' + id + '/'), ids.first, { timeout: 20000 });
  await page.waitForFunction(base => App.state.world === base && App.Market.world === base, ids.base);
  assert.equal(await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + ids.first + '"]').count(), 1,
    'opening a closed library Plan reopens its chip after the market commits');
  assert.equal(await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + ids.simOne + '"]').count(), 0,
    'the old market collection is stashed rather than blended into the bar');

  await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + ids.first + '"] .plan-chip-close').click();
  await page.waitForFunction(id => !document.querySelector('.plan-chip[data-plan-id="' + id + '"]'), ids.first);
  await go('#/home');
  await page.waitForSelector('#home-plan-library [data-plan-id="' + ids.first + '"]');
  assert.match(await page.locator('#home-plan-library [data-plan-id="' + ids.first + '"] .home-plan-actions .btn:not(.btn-secondary)').textContent(), /Open Plan/,
    'the chip × removes only the open-tab state; the durable Plan remains in the library');

  await page.locator('#home-plan-library [data-plan-id="' + ids.simOne + '"] .home-plan-actions .btn:not(.btn-secondary)').click();
  await page.waitForFunction(world => App.state.world === world && App.Market.world === world, ids.worldId,
    { timeout: 20000 });
  await page.waitForFunction(id => location.hash.includes('/plan/' + id + '/'), ids.simOne);
  await page.waitForSelector('.plan-rail');
  assert.equal(await page.locator('#plan-bar-root .plan-chip').count(), 2,
    'returning to the simulated market restores its two-Plan open collection');

  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.locator('#plan-picker').isVisible(), true, 'mobile uses a dedicated Plan picker');
  assert.equal(await page.locator('#plan-picker option').count(), 2, 'every current-market Plan is reachable');
  const mobile = await page.evaluate(() => {
    const rail = document.querySelector('.plan-rail').getBoundingClientRect();
    return { overflow: document.documentElement.scrollWidth - innerWidth,
      stages: Array.from(document.querySelectorAll('.plan-rail button')).map(button => {
        const r = button.getBoundingClientRect(); return { left: r.left, right: r.right,
          top: r.top, bottom: r.bottom, railLeft: rail.left, railRight: rail.right };
      }) };
  });
  assert.ok(mobile.overflow <= 0, 'parallel-Plan chrome does not widen the mobile viewport');
  assert.equal(mobile.stages.length, 6);
  mobile.stages.forEach(stage => assert.ok(stage.left >= stage.railLeft - 1 && stage.right <= stage.railRight + 1,
    'all six stages remain fully visible in the 2-column mobile grid: ' + JSON.stringify(stage)));
  await page.waitForTimeout(4200);
  await page.screenshot({ path: path.join(__dirname, 'shots/plan-p8-picker-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.reload();
  await page.waitForSelector('#plan-bar-root .plan-chip.active');
  assert.equal(await page.locator('#plan-bar-root .plan-chip.active').getAttribute('data-plan-id'), ids.simOne,
    'reload restores the active Plan for this market instead of a Plan from another lane');
  assert.equal(await page.locator('#plan-bar-root .plan-chip').count(), 2,
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

test('duplicate Plan identities stay distinct across Home, desktop, mobile, and reload', async () => {
  await page.setViewportSize({ width: 1280, height: 760 });
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1'));
  const ids = await page.evaluate(async () => {
    const base = App.baseWorldId();
    if (App.state.world !== base) await App.switchWorld(base);
    await PlanStore.load(true);
    const one = await PlanStore.create({ symbol: 'NVDA', intent: 'DIRECTIONAL', thesis: 'bullish',
      horizonDays: 30, riskMode: 'conservative', title: 'NVDA · Restart clarity' });
    await new Promise(resolve => setTimeout(resolve, 10));
    const two = await PlanStore.create({ symbol: 'NVDA', intent: 'DIRECTIONAL', thesis: 'bullish',
      horizonDays: 30, riskMode: 'conservative', title: 'NVDA · Restart clarity' });
    return { one: one.id, two: two.id };
  });

  await go('#/home');
  await page.waitForSelector('#home-plan-library [data-plan-id="' + ids.two + '"]');
  const labels = await Promise.all([ids.one, ids.two].map(async id => ({
    home: await page.locator('#home-plan-library [data-plan-id="' + id + '"]').textContent(),
    bar: await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + id + '"]').textContent()
  })));
  assert.match(labels[0].home, /Plan 1 of 2/);
  assert.match(labels[1].home, /Plan 2 of 2/);
  assert.match(labels[0].home, /Updated /);
  assert.match(labels[0].bar, /Plan 1 of 2/);
  assert.match(labels[1].bar, /Plan 2 of 2/);

  await page.setViewportSize({ width: 390, height: 844 });
  const options = await page.locator('#plan-picker option').allTextContents();
  assert.ok(options.some(text => /NVDA.*Plan 1 of 2/.test(text)));
  assert.ok(options.some(text => /NVDA.*Plan 2 of 2/.test(text)));
  await page.reload();
  await page.waitForSelector('#plan-picker');
  const restored = await page.locator('#plan-picker option').allTextContents();
  assert.ok(restored.some(text => /NVDA.*Plan 1 of 2/.test(text)), 'first identity survives restart');
  assert.ok(restored.some(text => /NVDA.*Plan 2 of 2/.test(text)), 'second identity survives restart');

  await go('#/home');
  await page.locator('#home-plan-library [data-plan-id="' + ids.one + '"] button[aria-label^="Archive"]').click();
  await page.waitForSelector('[role="dialog"]');
  await page.getByRole('button', { name: 'Archive Plan' }).click();
  await page.waitForFunction(id => !document.querySelector('#home-plan-library [data-plan-id="' + id + '"]'), ids.one);
  await page.waitForSelector('#home-plan-library .xp-head:has-text("Archived Plans")', { timeout: 15000 });
  assert.match(await page.textContent('#home-plan-library'), /Archived Plans \(\d+\)/,
    'archiving removes clutter while retaining a read-only record');
  await page.locator('#home-plan-library .xp-head:has-text("Archived Plans")').click();
  assert.match(await page.textContent('#home-plan-library .home-plan-archive'), /NVDA.*Restart clarity/s,
    'the archived Plan remains inspectable by identity');

  await page.evaluate(async values => {
    for (const id of [values.one, values.two]) {
      const plan = await PlanStore.get(id, true);
      await API.post('/api/plans/' + id + '/archive', { expectedVersion: plan.version });
    }
    await PlanStore.refresh();
  }, ids);
  await page.setViewportSize({ width: 1280, height: 720 });
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
  assert.equal(await page.locator('#plan-bar-root .plan-chip[data-plan-id="' + stale.id + '"]').count(), 0,
    'the Plan bar reconciles to server truth instead of retaining a dead tab');
  assert.equal(await page.locator('#home-plan-library [data-plan-id="' + stale.id + '"]').count(), 0,
    'the Home library does not resurrect the stale Plan');
  assert.doesNotMatch(await page.textContent('.home-hero'), /Continue TSLA/,
    'the primary continuation action is derived from the reconciled collection');
  assert.equal(await page.evaluate(id => PlanStore.all().some(item => item.id === id), stale.id), false,
    'the stale Plan is removed from the in-memory collection as well as the DOM');
});

test('Plan Decide freezes one server-owned package and opens the linked paper position atomically', async () => {
  await page.evaluate(() => Learn.setLevel('beginner'));
  const plan = await openPlan('AAPL', 'strategy');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .candidate', { timeout: 30000 });
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
  await page.locator('.plan-rail button').filter({ hasText: 'Decide' }).click();
  await page.waitForSelector('#plan-review-order');
  assert.equal(await page.locator('#plan-stage-decide input[name="symbol"], #plan-stage-decide select[name="strategy"]').count(), 0,
    'Decide derives symbol, intent, structure and legs from the Plan');
  await page.click('#plan-review-order');
  await page.waitForSelector('#plan-decision-review .plan-decision-math', { timeout: 30000 });
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
  await page.waitForFunction(id => location.hash === '#/plan/' + id + '/manage-review', plan.id, { timeout: 30000 });
  const frozen = await page.evaluate(async id => API.getFresh('/api/plans/' + id + '/decision/latest'), plan.id);
  assert.equal(frozen.plan.status, 'POSITION_OPEN');
  assert.equal(frozen.plan.activeStage, 'MANAGE_REVIEW');
  assert.equal(frozen.decision.action, 'TRADE');
  assert.ok(frozen.decision.tradeId, 'the paper trade is linked inside the frozen decision');

  await page.waitForSelector('#plan-stage-manage-review .quote-hero');
  assert.equal(await page.locator('#plan-archive').count(), 0,
    'a Plan with an open position cannot be archived out from under management');
  assert.match(await page.textContent('#plan-stage-manage-review'), /Frozen at Decide/);
  assert.match(await page.textContent('#plan-stage-manage-review'), /Refresh marks/);
  assert.equal(await page.locator('#plan-stage-manage-review a[href^="#/trade"], #plan-stage-manage-review .plan-stage-transition').count(), 0,
    'Manage stays inside the Plan instead of linking to a standalone Trade detail');
  await page.click('#refresh-btn');
  await page.waitForSelector('#app[data-ready="true"]');
  await page.waitForSelector('.plan-management-timeline');
  assert.match(await page.textContent('.plan-management-timeline'), /MARK/);
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

  await page.evaluate(async () => { Learn.setLevel('expert'); await App.render(); });
  await page.waitForSelector('#greeks-card');
  assert.match(await page.textContent('#greeks-card'), /Net Δ.*Θ\/day/s,
    'Expert retains aggregate and per-leg position Greeks in Plan management');
  assert.ok(await page.locator('#greeks-card tbody tr').count() >= 1, 'per-leg Greek rows remain auditable');
  await page.evaluate(async () => { Learn.setLevel('beginner'); await App.render(); });

  await page.locator('.plan-rail button').filter({ hasText: 'Decide' }).click();
  await page.waitForSelector('.plan-decision-facts');
  assert.match(await page.textContent('#plan-stage-decide'), /Decision frozen/);
  assert.match(await page.textContent('#plan-stage-decide'), /Market EV after costs/);

  await page.locator('.plan-rail button').filter({ hasText: 'Manage' }).click();
  await page.click('#unwind-btn');
  await page.waitForSelector('[role="dialog"]');
  await page.getByRole('button', { name: 'Close position' }).click();
  await page.waitForFunction(id => location.hash === '#/plan/' + id + '/manage-review', plan.id);
  await page.waitForSelector('.plan-review-results');
  assert.match(await page.textContent('#plan-stage-manage-review'), /trade decision/i);
  assert.match(await page.textContent('#plan-stage-manage-review'), /plan position/i);

  await go('#/portfolio');
  await page.waitForSelector('#portfolio-plan-book .plan-book-card');
  assert.match(await page.textContent('#portfolio-plan-book'), /AAPL/);
  assert.match(await page.textContent('#portfolio-plan-book'), /Review Plan/);
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
    App.context.update({ symbol: 'AAPL', goal: 'INCOME', horizon: 'month', thesis: 'neutral' });
    App.context.update({ goal: 'ALL' });
    const cleared = App.context.goal();
    const covered = Builder.TEMPLATES.find(t => t.family === 'COVERED_CALL');
    var owner = { builderForm: {
      symbol: 'AAPL', qty: 1, goal: 'BROWSE', templateKey: covered.key,
      legs: [{ action: 'BUY', type: 'STOCK', strike: null, expiration: null, ratio: 1 },
        { action: 'SELL', type: 'CALL', strike: '260', expiration: '2026-08-21', ratio: 1 }],
      excluded: {}
    } };
    const ticket = Builder.prepareTicket(owner, { goal: 'BROWSE', thesis: 'neutral', horizon: 'month' });
    return { cleared, templateIntent: covered.primaryIntent, ticketIntent: ticket.intent };
  });
  assert.equal(contextContract.cleared, null, 'Everything clears a stale single-goal context');
  assert.equal(contextContract.templateIntent, 'INCOME', 'server catalog supplies the structure intent');
  assert.equal(contextContract.ticketIntent, 'INCOME', 'Browse all infers intent from the chosen structure');
  assert.equal(await page.evaluate(() => UI.maxProfitLabel('DIAGONAL_CALL', 'time', null, true)),
    'model-dependent', 'a multi-expiration null ceiling is never presented as infinity');
  assert.equal(await page.evaluate(() => UI.maxProfitLabel('LONG_CALL', 'single_long', null, true)),
    'no fixed ceiling', 'a genuinely unbounded structure keeps its theoretical truth');
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
  await page.route('**/api/sparklines?*', r => r.fulfill({ status: 204, body: '' }));
  try {
    await page.evaluate(() => {
      localStorage.setItem('strikebench.welcomed', '1');
      API.flushCache();
      App.navigate('#/home');
    });
    await page.waitForSelector('#app[data-route="home"][data-ready="true"]');
    await page.waitForFunction(() => {
      const cards = document.querySelectorAll('.home-market-grid .sym-card');
      return cards.length > 0 && document.querySelectorAll('.home-market-grid .spark-ev').length === cards.length;
    });
    assert.equal(await page.locator('.home-market-grid .spark-loading').count(), 0,
      'a declined optional request never leaves a permanent loading state');
    assert.equal(await page.locator('.home-market-grid .spark-ev').evaluateAll(nodes =>
      nodes.every(n => /HISTORY UNAVAILABLE/.test(n.textContent))), true,
      'every deferred chart states that its history evidence is missing');
  } finally {
    await page.unroute('**/api/sparklines?*');
  }
});

test('research AAPL: hero quote, events, news, focused chain, show-all toggle', async () => {
  await go('#/research');
  await page.fill('#symbol-input', 'AAPL');
  await page.click('#symbol-go');
  await page.waitForSelector('#plan-start');
  await promoteEvidencePlan();
  await openResearchTab('overview');
  await page.waitForSelector('#research-symbol');
  assert.match(await page.textContent('#research-symbol'), /AAPL/);
  // Coming up: expirations (and any earnings/filing signals) as dated chips
  await page.waitForSelector('#events-card .event-timeline-item');
  assert.match(await page.textContent('#events-card'), /OPTION EXPIRY/i);
  assert.equal(await page.locator('#research-workspace-tabs').count(), 0,
    'the old symbol-local tabs are deleted');
  assert.equal(await page.locator('.plan-rail li').count(), 6,
    'one Plan rail owns the whole journey');
  await page.waitForSelector('#news-overview-card .news-tile');
  assert.ok(await page.locator('#news-overview-card .news-tile').count() >= 1,
    'latest news remains part of the market overview');
  assert.match(await page.textContent('#news-overview-card'), /not an article summary/i);
  // News NEVER silently vanishes — card renders with items or an honest empty state
  await openResearchTab('news');
  await page.waitForSelector('#news-card');
  assert.match(await page.textContent('#news-card'), /News & filings/);
  assert.ok((await page.locator('#news-card .news-tile').count()) >= 1, 'fixture headlines render');
  await page.waitForSelector('#research-evidence .evidence-badge:has-text("DEMO")');
  assert.equal(await page.locator('.quote-hero .badge:has-text("DEMO DATA")').count(), 0,
    'the hero does not repeat the page-level Demo evidence label');
  await openPlan('AAPL', 'strategy', 'DIRECTIONAL');
  await openResearchTab('options');
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
  await openPlan('VTSAX');
  await openResearchTab('options');
  await page.waitForSelector('text=/no listed options/i'); // hero/actions now fill detached
});

test('Research entry and destination cards are purposeful, readable, and collision-free', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
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
  assert.equal(geometry.collisions, 0, 'sparkline readout never overlaps its evidence badge');
  assert.ok(await page.locator('#sector-chips .sector-chip').first().isVisible(),
    'market scope stays visible even when the explicit Demo lane has only one sector');
  assert.ok(await page.locator('#sector-grid .destination-cue').count() >= 4,
    'destination cards use one explicit open affordance instead of hover underlines');
  await page.click('#sector-grid .sym-card[data-sym="AAPL"]');
  await page.waitForSelector('#plan-start');
  assert.match(await page.textContent('#plan-start'), /Turn this research into a Plan/);
  await promoteEvidencePlan();
  await page.waitForSelector('#plan-header');
  assert.equal(await page.locator('#research-symbol-context').count(), 0,
    'symbol identity is immutable in the Plan header rather than recaptured in the stage');
  assert.match(await page.textContent('#plan-header'), /AAPL.*Intent not chosen/s);
  await page.locator('.plan-rail button').filter({ hasText: 'Evidence' }).click();
  await page.waitForSelector('#tv-view');
  assert.equal(await page.inputValue('#tv-view'), '',
    'a Plan without a thesis never displays an implicit bullish default');
  assert.match(await page.textContent('#test-your-view'), /Choose the view you want to test/);
  assert.equal(await page.locator('#research-outcomes').count(), 0,
    'statistical lenses do not run under an assumption the Plan has not recorded');
  await page.selectOption('#tv-view', 'bullish');
  await page.waitForSelector('#research-outcomes');
  assert.match(await page.textContent('.plan-stage-carry'), /bullish view/,
    'the visible Evidence controls and durable carried context agree');
  await page.locator('.plan-rail button').filter({ hasText: 'Strategy' }).click();
  await page.waitForSelector('#plan-strategy-body .plan-intent-grid .choice-card');
  const intentChoices = await page.evaluate(() => {
    const grid = document.querySelector('#plan-strategy-body .plan-intent-grid');
    const cards = Array.from(grid.querySelectorAll('.choice-card'));
    const gridBox = grid.getBoundingClientRect();
    return {
      count: cards.length,
      display: getComputedStyle(grid).display,
      cards: cards.map(card => {
        const css = getComputedStyle(card);
        const box = card.getBoundingClientRect();
        return {
          tag: card.tagName,
          textAlign: css.textAlign,
          minHeight: parseFloat(css.minHeight),
          inside: box.left >= gridBox.left - 1 && box.right <= gridBox.right + 1,
          visible: box.width > 120 && box.height >= 80
        };
      })
    };
  });
  assert.equal(intentChoices.display, 'grid', 'the intent chooser uses the shared choice grid');
  assert.equal(intentChoices.count, 5, 'every Plan intent remains available before commitment');
  assert.ok(intentChoices.cards.every(card => card.tag === 'BUTTON' && card.textAlign === 'left'
    && card.minHeight >= 80 && card.inside && card.visible),
  'intent choices are full pressable cards with stable geometry: ' + JSON.stringify(intentChoices));
});

test('navigation is NEVER trapped behind a slow route (Research does not block moving away)', async () => {
  // Gate /api/research/AAPL so it hangs; the shell must still paint AND we must be able to leave.
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
    await openPlan('AAPL');
    await page.waitForSelector('#research-hero', { timeout: 4000 }); // shell paints before the fetch
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


/** The futures stage lives inside Test your view now — select its pill before using #whatif-card. */
async function openFutures() {
  await openResearchTab('view');
  await page.waitForSelector('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
  await page.waitForSelector('#tv-futures', { timeout: 15000 });
}

test('scenario studio: beginner view → decision facts → same-receipt strategy outcomes', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  await page.evaluate(() => {
    App.context.update({ symbol: 'AAPL', thesis: 'bearish', horizon: 'quarter' });
  });
  await openPlan('AAPL');
  await openResearchTab('view');
  await page.waitForSelector('#tv-view');
  assert.equal(await page.inputValue('#tv-view'), 'bearish', 'Research consumes the canonical market view');
  assert.equal(await page.inputValue('#tv-horizon'), '63', 'Research consumes the canonical horizon');
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
  assert.match(decisionText, /receipt [a-f0-9]{24}/);
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
  const models = await page.$$eval('#sc-model option', os => os.map(o => o.value));
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
  await openPlan('AAPL');
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

test('workspace continuity: forms, symbol, and route survive a full reload', async () => {
  await page.evaluate(() => App.context.update({
    symbol: 'AAPL', goal: 'ACQUIRE', horizon: 'quarter', thesis: 'neutral'
  }));
  const plan = await openPlan('AAPL', 'understand', 'ACQUIRE', 'neutral');
  await page.waitForSelector('.quote-hero');
  const durablePlanRoute = await page.evaluate(() => location.hash);
  await page.click('#plan-edit-context');
  await page.fill('#plan-horizon-days', '63');
  await page.fill('#plan-target-price', '245');
  await page.click('#plan-save-context');
  await page.waitForFunction(() => /63 days/.test(document.getElementById('plan-header')?.textContent || ''));
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
  await page.goto(BASE + '/');
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
  await page.goto(BASE + '/#/portfolio');
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
  await openPlan('AAPL');
  await openFutures();
  await page.waitForSelector('#whatif-card #sc-shapes .sc-card');
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
  for (const model of ['GBM', 'STUDENT_T', 'JUMP_DIFFUSION', 'HESTON', 'BLOCK_BOOTSTRAP', 'BROWNIAN_BRIDGE']) {
    await page.selectOption('#whatif-card #sc-model', model);
    await page.click('#whatif-run');
    await page.waitForSelector('#whatif-out .scenario-decision', { timeout: 30000 });
  }
  await page.click('#level-switch button[data-level="beginner"]');
});

test('research symbol page: ONE Test-your-view section — thesis-driven, symbol-inherited, keyed results', async () => {
  await page.click('#level-switch button[data-level="beginner"]');
  const researchPlan = await openPlan('AAPL');
  assert.ok(await page.locator('#history-card').count(), 'Understand owns the market history');
  await openResearchTab('view');
  await page.waitForSelector('#test-your-view');
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
  // The thesis question line names the CONDITION — "did this happen before" never leaves "this" undefined.
  await page.waitForSelector('#tv-question-line');
  assert.ok(await page.locator('#study-window:visible').count(), 'beginner controls the history window');
  assert.ok(await page.locator('#study-strictness:visible').count(), 'beginner controls signal selectivity');
  assert.ok(await page.locator('#study-regime:visible').count(), 'beginner controls market regime');
  assert.ok(await page.locator('#study-bootstrap').count(),
    'full statistical capability remains mounted behind progressive disclosure');
  const beforeStrict = await page.inputValue('#study-param-dropPct');
  await page.selectOption('#study-strictness', 'stronger');
  assert.notEqual(await page.inputValue('#study-param-dropPct'), beforeStrict,
    'plain-language selectivity changes the real signal threshold');
  // Run the study; the conclusion is decision-useful (evidence strength + confidence guidance + handoff).
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  const outText = await page.textContent('#study-results');
  assert.match(outText, /Evidence/, 'evidence-strength label present');
  assert.match(outText, /raise or lower your confidence/, 'confidence guidance, never a prediction');
  // The handoff exists when enough analogs matched.
  const handoff = await page.locator('#tv-test-analogs').count();
  if (handoff) {
    assert.match(await page.textContent('#tv-test-analogs'), /DEMO-data occurrences/); // fixture suite: never 'real'
    await page.click('#tv-test-analogs');
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
  await page.selectOption('#study-confidence', '99');
  await page.selectOption('#study-multiplicity', 'UNADJUSTED_EXPLORATORY');
  await page.click('#study-run');
  await page.waitForSelector('#study-results .alert', { timeout: 20000 });
  assert.match(await page.textContent('#study-results'), /99% CI \(avg\)/);
  assert.match(await page.textContent('#study-results'), /Exploratory unadjusted significance/);
  // KEYED RESULTS: switching to QQQ must not display AAPL's study.
  await openPlan('QQQ');
  await openResearchTab('view');
  await page.waitForSelector('#test-your-view');
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="past"]');
  await page.waitForSelector('#study-run:not([disabled])', { timeout: 15000 });
  assert.equal(await page.locator('#study-results .alert').count(), 0,
    'an AAPL result may never appear on a QQQ page (result identity is keyed)');
  // Possible futures is the second stage of the SAME section.
  await page.click('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
  await page.waitForSelector('#tv-futures #whatif-card, #tv-futures #sc-shapes', { timeout: 15000 });
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
  await page.waitForSelector('.quote-hero');
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
  assert.match(await page.textContent('#research-plan-scout'), /when you do not already have a ticker/i);
  await page.selectOption('#universe-scout-intent', 'DIRECTIONAL');
  await page.selectOption('#universe-scout-horizon', 'month');
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
  const symbol = (await first.locator('h3').textContent()).trim();
  await first.getByRole('button', { name: new RegExp('Open ' + symbol + ' Plan') }).click();
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  assert.match(await page.textContent('#plan-header'), new RegExp(symbol));
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
});

test('portfolio absorbs account: sections, ledger under Activity, guarded reset', async () => {
  await go('#/portfolio/account');
  const text = await page.textContent('#app');
  assert.match(text, /Buying power/);
  assert.match(text, /Reset account/);
  assert.equal(await page.evaluate(() => document.getElementById('app').getAttribute('data-route')), 'portfolio');
  await go('#/portfolio/activity');
  const ledger = await page.textContent('#app');
  assert.match(ledger, /Ledger.*append-only.*Date.*Type.*Amount.*Cash after.*Reserved after.*DEPOSIT/s,
    'Activity owns the append-only account ledger even before a trade has been placed');
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
  const plan = await openPlan('AAPL', 'strategy');
  await page.click('#plan-run-strategy');
  await page.waitForSelector('#plan-strategy-results .candidate', { timeout: 30000 });
  await page.evaluate(async planId => {
    const latest = await API.getFresh('/api/plans/' + planId + '/strategy/latest');
    const candidate = latest.strategy.result.candidates.find(item => {
      const expirations = new Set((item.legs || []).filter(leg => leg.type !== 'STOCK').map(leg => leg.expiration));
      return expirations.size === 1;
    });
    const live = await PlanStore.get(planId, true);
    await PlanStore.selectCandidate(live, candidate.id);
  }, plan.id);
  await go('#/plan/' + plan.id + '/decide');
  await page.waitForSelector('#plan-review-order');
  await page.click('#plan-review-order');
  await page.waitForSelector('#plan-decision-review .info-trigger', { timeout: 30000 });
  // AUDIT 1: every used term key resolves in the registry (anti-drift).
  const missing = await page.evaluate(() =>
    (window.__usedInfoTerms || []).filter(k => !(window.Learn && Learn.INFO && Learn.INFO[k])));
  assert.deepEqual(missing, [], 'info terms missing from the registry: ' + missing.join(','));
  // AUDIT 2: triggers are VISIBLE (quiet but discoverable) and carry no competing native title.
  const trig = page.locator('#plan-decision-review .info-trigger[data-term="pop"]').first();
  assert.ok(await trig.isVisible(), 'info trigger must be visible without hovering');
  const dup = await page.$$eval('#plan-decision-review .info-trigger', els =>
    els.filter(e => e.closest('[title]')).length);
  assert.equal(dup, 0, 'a bubble label must not also carry a native title tooltip');
  // AUDIT 3: click opens immediately; one-liner first; [+] expands the BEGINNER detail.
  await trig.click();
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
  await page.waitForSelector('#plan-decision-review .info-trigger', { timeout: 20000 });
  await page.locator('#plan-decision-review .info-trigger[data-term="pop"]').first().click();
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
  await page.waitForSelector('#dc-mode .badge'); // where-you-are card leads
  assert.match(await page.textContent('#dc-mode'), /OBSERVED MARKET|DEMO MARKET|SIMULATED|SCENARIO/);
  await page.waitForSelector('#dc-engine .chip-row');
  assert.match(await page.textContent('#dc-engine'), /Market engine/);
  await page.waitForSelector('#dc-health:has-text("Quotes")', { timeout: 15000 });
  await page.click('#dc-refresh-now');
  // Inactive workspaces are NOT mounted from Overview (only-active-tab loading).
  assert.equal(await page.locator('#dc-sources').count(), 0, 'sources not mounted on overview');
  assert.equal(await page.locator('#dc-reset').count(), 0, 'reset not mounted on overview');

  // Sources & jobs tab
  await page.click('#data-tabs [data-tab="sources"]');
  await page.waitForSelector('#dc-history-sync #data-csv-upload');
  await page.waitForSelector('#dc-sources .dc-source');
  const sources = await page.textContent('#dc-sources');
  assert.match(sources, /Yahoo Finance automation/);
  assert.match(sources, /Automated collection requires permission|automated collection requires permission/i);
  assert.match(sources, /Your price-history CSV/);
  assert.ok(await page.locator('#dc-history-sync .data-source-choice').count() >= 4, 'all automated connector choices remain visible');
  assert.equal(await page.locator('#data-sync-preview').isDisabled(), true, 'Demo build cannot start an observed provider sync');
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

test('data center reset tiers are reduced for Beginner', async () => {
  await go('#/data/admin');
  await page.click('#level-switch button[data-level="beginner"]');
  // Wait for the BEGINNER re-render (2-tier select), not the lingering expert one mid-transition.
  await page.waitForFunction(() => {
    const s = document.getElementById('dc-reset-tier');
    return s && s.options.length === 2;
  }, { timeout: 15000 });
  const tiers = await page.$$eval('#dc-reset-tier option', os => os.map(o => o.value));
  assert.ok(!tiers.includes('MARKET_DATA'), 'beginner hides the granular market-data tier');
  assert.ok(tiers.includes('PAPER') && tiers.includes('EVERYTHING'), 'beginner keeps the plain resets');
  await go('#/data/sources');
  await page.waitForSelector('#dc-history-sync #data-csv-upload');
  assert.ok(await page.locator('#dc-history-sync .data-source-choice').count() >= 4, 'beginner keeps every connector');
  assert.ok(await page.locator('#data-sync-years').count(), 'beginner keeps history window control');
  assert.ok(await page.locator('#dc-history-sync .xp-head:has-text("Keep it current automatically")').count(),
    'beginner gets the same maintenance capability through progressive disclosure');
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
  await page.waitForSelector('#dc-sources .dc-source');
  assert.match(await page.textContent('#dc-sources'), /Daily price-history connectors/,
    'source eligibility remains readable');
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
  assert.equal(await page.evaluate(() => document.querySelector('#modal-root [role="dialog"]').contains(document.activeElement)), true,
    'focus moves inside the modal');
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => !document.querySelector('#modal-root [role="dialog"]'));
  assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), 'buy-shares-btn',
    'Escape closes and restores focus to the trigger');
});

test('destination navigation starts at the top while Research owns its explicit Back restoration', async () => {
  await openPlan('AAPL');
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
  assert.deepEqual(cardA11y, { tag: 'A', href: '#/plan/new?symbol=AAPL', tab: 0, exp: null },
    'card is a native link into the provisional Plan without disclosure semantics');
  // Pointer movement/arrow keys explore; clicking anywhere opens full analysis before a Plan exists.
  await page.locator('#sector-grid .sym-card[data-sym="AAPL"] .spark-svg').click();
  await page.waitForSelector('#plan-start', { timeout: 15000 });
  await page.waitForSelector('#research-hero .quote-hero', { timeout: 15000 });
  assert.equal(await page.evaluate(() => window.location.hash), '#/plan/new?symbol=AAPL',
    'the chart area does not create a dead middle inside the destination card');
  await page.goBack();
  await page.waitForSelector('#sector-grid .sym-card[data-sym="AAPL"] .spark-svg', { timeout: 15000 });
  // Card click -> provisional Plan; promotion replaces that URL and Back restores the explorer.
  await page.evaluate(() => window.scrollTo(0, 200));
  await page.locator('#sector-grid .sym-card[data-sym="AAPL"]').click();
  await page.waitForSelector('#plan-start', { timeout: 15000 });
  await promoteEvidencePlan();
  await page.waitForSelector('#test-your-view', { timeout: 15000 });
  assert.match(await page.evaluate(() => window.location.hash), /^#\/plan\/plan_[^/]+\/evidence$/);
  assert.match(await page.textContent('#plan-header'), /AAPL/,
    'the immutable Plan header owns symbol identity without a duplicate change-stock control');
  assert.match(await page.textContent('#test-your-view'), /fabricated Demo history \(not the real past\)/,
    'the study names its Demo-history basis');
  assert.doesNotMatch(await page.textContent('#test-your-view'), /checks the REAL past/,
    'Demo research never promotes fabricated history to real');
  await openResearchTab('overview'); // exercise the canonical stage rail
  await page.waitForSelector('#app[data-route="plan"][data-ready="true"]');
  await page.goBack(); // Understand -> Evidence
  await page.waitForFunction(() => /^#\/plan\/plan_[^/]+\/evidence$/.test(location.hash)
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.goBack(); // promoted Plan -> Research explorer
  await page.waitForFunction(() => location.hash === '#/research'
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  await page.waitForSelector('#sector-grid .sector-tile', { timeout: 15000 });
  await page.waitForFunction(() => window.scrollY >= 100, { timeout: 5000 });
  assert.ok(await page.evaluate(() => window.scrollY >= 100),
    'Back restores the explorer\'s saved vertical position after the route-level top reset');
  const restoredSector = sectorCount >= 10 ? 'TECH' : 'world';
  assert.ok(await page.locator('#sector-chips .sector-chip[data-sector="' + restoredSector + '"].active').count(),
    'Back restores the SAME market selection');
  // In Observed mode, quote-less cards still navigate and let the full page own the honest
  // unavailable state. Explicit Demo has no intentionally dead symbols.
  if (await page.locator('#sector-grid .tile-nodata').count()) {
    await page.locator('#sector-grid .tile-nodata').first().click();
    await page.waitForSelector('#plan-start', { timeout: 15000 });
    assert.match(await page.evaluate(() => window.location.hash), /^#\/plan\/new\?symbol=/,
      'quote-less cards still start an honest Plan rather than becoming dead controls');
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
  await page.waitForSelector('#plan-start');
  assert.equal(await page.evaluate(() => location.hash), '#/plan/new?symbol=AAPL');

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
  await page.waitForSelector('#plan-start');
  assert.match(await page.evaluate(() => location.hash), /^#\/plan\/new\?symbol=/,
    'the tape and cards share one Plan-start destination contract');
  // Explicit Demo owns this universe already; no hidden sector mutation is needed.
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
      .every(c => /^#\/plan\/new\?symbol=/.test(c.getAttribute('href') || ''))
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
  await go('#/portfolio/construct');
  await page.waitForSelector('#portfolio-construct');
  assert.equal(await page.locator('#pf-sec-construct.active').count(), 1, 'Construct is a first-class Portfolio section');
  await page.waitForSelector('#portfolio-build', { timeout: 25000 });
  assert.equal(await page.locator('#portfolio-objective').count(), 1, 'Expert sees the full objective controls inline');
  // Diagnostic mode guarantees a funded (least-bad) set on the fixture universe, LABELED not-a-recommendation.
  await page.check('#portfolio-diagnostics');
  await page.click('#portfolio-build');
  await page.waitForSelector('#portfolio-summary', { timeout: 20000 });
  assert.ok((await page.locator('.allocation-bar .allocation-segment').count()) >= 1, 'composition bar segments');
  assert.ok((await page.locator('.portfolio-construct table tbody tr').count()) >= 1, 'allocations table rows');
  const allocationSymbols = await page.locator('.portfolio-construct table tbody tr td:first-child').allTextContents();
  assert.ok(allocationSymbols.every(s => /^[A-Z][A-Z0-9._-]*$/.test(s.trim())), 'every allocation names its real symbol');
  assert.ok(new Set(allocationSymbols).size > 1, 'fixture construction proves a multi-symbol draft, not duplicated cards');
  assert.ok((await page.locator('#portfolio-output .alert-caution').count()) >= 1, 'diagnostic set labeled not-a-recommendation');
  assert.match(await page.textContent('.portfolio-construct table thead'), /Verdict|Market EV|History EV/,
    'expert construction keeps both EV lanes and economic placement visible');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(300); // capture after the real card-in transition, never by disabling motion
  await page.screenshot({ path: path.join(__dirname, 'shots/portfolio-construct-expert.png'), fullPage: true });

  // Beginner keeps the same levers and result, but progressive disclosure and visual
  // allocation cards replace the expert table.
  await page.click('#level-switch button[data-level="beginner"]');
  await page.waitForSelector('#portfolio-construct');
  assert.equal(await page.locator('#portfolio-objective').count(), 0, 'advanced controls begin collapsed at Beginner');
  await page.locator('#portfolio-construct .xp > .xp-head').first().click();
  assert.equal(await page.locator('#portfolio-objective').count(), 1, 'Beginner can reach the same objective control');
  assert.ok((await page.locator('.portfolio-allocation-card').count()) >= 1, 'Beginner receives visual allocation cards');
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(__dirname, 'shots/portfolio-construct-beginner.png'), fullPage: true });
  await page.locator('.portfolio-allocation-card button:has-text("Review in a new Plan")').first().click();
  await page.waitForSelector('.plan-selected-structure', { timeout: 15000 });
  assert.equal(await page.locator('#app[data-route="plan"]').count(), 1, 'allocation opens in the canonical Plan journey');
  assert.match(await page.textContent('.plan-selected-structure'), /Selected structure|PLAN OWNED/,
    'the exact optimizer package survives the handoff');
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
        decision: { symbol: 'AAPL', thesis: 'bullish', horizon: 'month', riskMode: 'balanced' }
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
  const mobileWorldChrome = await page.evaluate(() => ({
    bandHeight: document.getElementById('world-band').getBoundingClientRect().height,
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth
  }));
  assert.ok(mobileWorldChrome.bandHeight <= 76,
    'mobile simulated-market controls stay compact: ' + JSON.stringify(mobileWorldChrome));
  assert.ok(mobileWorldChrome.scrollWidth <= mobileWorldChrome.innerWidth,
    'simulated-market chrome never widens the phone viewport');
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
  await page.waitForSelector('.quote-hero');
  assert.match(await page.textContent('#plan-header'), /ACME.*Simulated market/s,
    'the persistent Plan header names the execution market');
  // The universe schema is STABLE across modes: active.symbols exists and carries the world.
  const uni = await page.evaluate(async () => await API.getFresh('/api/universe'));
  assert.ok(uni.active && Array.isArray(uni.active.symbols), 'one UniverseView schema in world mode');
  assert.ok(uni.active.symbols.includes('ACME'), 'world symbols drive the universe');
  assert.ok(Array.isArray(uni.sectors) && uni.sectors.length >= 1, 'sectors present (the session)');
  const badges = await page.$$eval('.quote-hero .badge', els => els.map(e => e.textContent).join(' '));
  assert.match(badges, /SIMULATED/, 'sim quotes carry the SIMULATED label');
  const px0 = await page.textContent('#research-px');
  // Step the world several times via the band. Each step publishes a REAL world.tick SSE hint;
  // the app's own handler must move the hero price. NO fallback: the old catch wrote the price
  // into the DOM itself, which masked a dead SSE path (weekend-handoff review M5).
  for (let i = 0; i < 12; i++) await page.click('#world-step');
  await page.waitForFunction((prev) => {
    const el2 = document.getElementById('research-px');
    return el2 && el2.textContent !== prev;
  }, px0, { timeout: 20000 });
  const px1 = await page.textContent('#research-px');
  assert.notEqual(px1, px0, 'the app’s own SSE handler moved the price on screen');

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
  await page.waitForSelector('.sim-session-row button:has-text("Finish")');
  // The card surface itself enters the market; it is not a decorative shell around buttons.
  await page.click('.sim-session-row', { position: { x: 5, y: 5 } });
  await page.waitForFunction((baseline) => App.state.world !== baseline, baselineWorld, { timeout: 15000 });
  await page.click('#world-exit');
  await page.waitForFunction((baseline) => App.state.world === baseline, baselineWorld, { timeout: 15000 });
  await go('#/data/simulation');
  await page.waitForSelector('.sim-session-row button:has-text("Finish")');
  await page.click('.sim-session-row button:has-text("Finish")');
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
  await page.unroute('**/api/data/overview');
  // Home: exactly ONE 'Find an idea' doorway (review #9) and no marketing hero.
  await page.evaluate(() => localStorage.setItem('strikebench.welcomed', '1')); // dashboard, not the tour
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
  assert.ok(await page.locator('#next-up').count(), 'one Continue area (journey + continuity merged)');
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
  await page.evaluate(() => {
    App.state.universe = window.__homeUniverseRestore;
    delete window.__homeUniverseRestore;
  });
  const sparseMarketRows = await page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll('.home-market-grid .sym-card'));
    const ys = cards.map(c => Math.round(c.getBoundingClientRect().top));
    return { count: cards.length, rows: [...new Set(ys)].map(y => ys.filter(v => v === y).length) };
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
    'Continue, Tools, and Sector pulse are all explicitly headed');
  // Responsive chrome must recalculate its sticky anchor. It previously retained the 120px
  // mobile top after resizing to desktop and overlaid both the tape and the page.
  await page.setViewportSize({ width: 390, height: 844 });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.waitForTimeout(100);
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
  await page.setViewportSize({ width: 1280, height: 900 });
});
