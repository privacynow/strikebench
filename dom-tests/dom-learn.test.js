/* Program ONE R6 Learn: real SPA route, registry aggregation, responsive density, and a11y. */
'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const path = require('node:path');
const { chromium } = require('playwright');
const { freshDb } = require('./pgtest');

const PORT = process.env.PORT || '7192';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg;
const pageErrors = [];

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { if ((await fetch(`${BASE}/api/status`)).ok) return; } catch (e) { /* retry */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error('server did not start');
}

async function go(hash) {
  await page.evaluate(destination => {
    document.getElementById('app').setAttribute('data-ready', 'false');
    if (window.location.hash === destination) return App.render();
    window.location.hash = destination;
  }, hash);
  await page.waitForFunction(expected => window.location.hash === expected
    && App._lastRenderedRoute === expected
    && document.getElementById('app').getAttribute('data-ready') === 'true', hash);
}

before(async () => {
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' },
    stdio: 'ignore'
  });
  await waitForServer();
  browser = await chromium.launch({ headless: true });
  page = await browser.newPage({ viewport: { width: 2560, height: 1400 } });
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(BASE + '/#/home');
  await page.waitForSelector('#app[data-ready="true"]');
});

after(async () => {
  if (browser) await browser.close();
  if (server) server.kill('SIGTERM');
  if (pg) pg.drop();
});

test('Learn is one responsive SPA view over the shared registries', async () => {
  await page.evaluate(() => { window.__learnSpaMarker = 'same-document'; });
  await page.click('#learn-link');
  await page.waitForFunction(() => window.location.hash === '#/learn'
    && App._lastRenderedRoute === '#/learn'
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  assert.equal(await page.evaluate(() => window.__learnSpaMarker), 'same-document',
    'Learn navigation preserves the running SPA document');
  await page.waitForSelector('#learn-page h1');
  assert.match(await page.locator('#learn-page h1').innerText(), /Understand the language/i);

  const coverage = await page.locator('#learn-page').evaluate(node => ({
    displayed: {
      vocabulary: Number(node.dataset.vocabularyCount),
      info: Number(node.dataset.infoCount),
      glossary: Number(node.dataset.glossaryCount),
      concepts: Number(node.dataset.conceptCount),
      strategies: Number(node.dataset.strategyCount)
    },
    registry: {
      vocabulary: Object.keys(Learn.VOCABULARY || {}).length,
      info: Object.keys(Learn.INFO || {}).length,
      glossary: Object.keys(Learn.GLOSSARY || {}).length,
      strategies: Object.keys(Learn.STRATEGY_GUIDE || {}).length
    }
  }));
  assert.equal(coverage.displayed.vocabulary, coverage.registry.vocabulary);
  assert.equal(coverage.displayed.info, coverage.registry.info);
  assert.equal(coverage.displayed.glossary, coverage.registry.glossary);
  assert.equal(coverage.displayed.strategies, coverage.registry.strategies);
  assert.equal(await page.locator('.learn-concept-card').count(), coverage.displayed.concepts);
  assert.equal(await page.locator('.learn-strategy-card').count(), coverage.displayed.strategies);
  assert.ok(await page.locator('.learn-source-vocabulary').count() > 0,
    'canonical product vocabulary is visibly distinguished');
  assert.ok(await page.locator('.learn-provenance-legend').getByText('Product vocabulary').count() > 0);

  const search = page.getByLabel('Find a concept or strategy');
  await search.fill('assignment capital');
  await page.click('[data-learn-filter="vocabulary"]');
  const visibleVocabulary = page.locator('.learn-concept-card:not([hidden])');
  assert.ok(await visibleVocabulary.count() >= 1, 'search finds the canonical assignment-capital term');
  const wrongSource = await visibleVocabulary.evaluateAll(cards => cards.filter(card =>
    !(card.dataset.provenance || '').split(/\s+/).includes('vocabulary')).length);
  assert.equal(wrongSource, 0, 'the product-vocabulary filter is provenance based');

  await search.fill('');
  await page.click('[data-learn-filter="strategy"]');
  assert.equal(await page.locator('.learn-strategy-card:not([hidden])').count(), coverage.registry.strategies);
  assert.equal(await page.locator('.learn-concept-card:not([hidden])').count(), 0);
  const destinations = await page.locator('.learn-use-plan').evaluateAll(links =>
    links.map(link => link.getAttribute('href')));
  assert.ok(destinations.length === coverage.registry.strategies);
  assert.ok(destinations.every(href => href === '#/research'),
    'without an active Plan every guide enters through canonical Research');

  await page.click('#level-switch [data-level="expert"]');
  await page.waitForFunction(() => Learn.currentLevel() === 'expert'
    && document.getElementById('app').getAttribute('data-ready') === 'true');
  assert.ok(await page.locator('.learn-concept-card [data-level-depth="expert"]').count() > 0,
    'Expert uses the registry expert depth');
  assert.ok(await page.locator('.learn-registry-key').count() > 0,
    'Expert can audit the underlying registry keys');

  await page.click('[data-learn-filter="all"]');
  const desktop = await page.evaluate(() => {
    const app = document.getElementById('app').getBoundingClientRect();
    const concepts = getComputedStyle(document.getElementById('learn-concept-grid')).gridTemplateColumns.split(' ').length;
    const strategies = getComputedStyle(document.getElementById('learn-strategy-grid')).gridTemplateColumns.split(' ').length;
    return { appWidth: app.width, concepts, strategies,
      overflow: document.documentElement.scrollWidth - window.innerWidth };
  });
  assert.ok(desktop.appWidth > 2000, `2560px desktop uses the canvas (${desktop.appWidth}px)`);
  assert.ok(desktop.concepts >= 4, `concepts compare across ${desktop.concepts} desktop columns`);
  assert.ok(desktop.strategies >= 3, `strategies compare across ${desktop.strategies} desktop columns`);
  assert.ok(desktop.overflow <= 1, `desktop has no horizontal overflow (${desktop.overflow}px)`);

  const unnamed = await page.locator('#learn-page').evaluate(root => Array.from(
    root.querySelectorAll('input,button,a')).filter(control => {
      if (control.hidden) return false;
      if (control.matches('a') && control.textContent.trim()) return false;
      if (control.matches('button') && control.textContent.trim()) return false;
      return !(control.getAttribute('aria-label') || control.getAttribute('aria-labelledby')
        || (control.id && root.querySelector(`label[for="${CSS.escape(control.id)}"]`))
        || control.closest('label'));
    }).map(control => control.outerHTML.slice(0, 180)));
  assert.deepEqual(unnamed, [], `Learn controls have accessible names: ${unnamed.join('\n')}`);

  await page.setViewportSize({ width: 390, height: 844 });
  await go('#/learn');
  const mobile = await page.evaluate(() => ({
    columns: getComputedStyle(document.getElementById('learn-concept-grid')).gridTemplateColumns.split(' ').length,
    overflow: document.documentElement.scrollWidth - window.innerWidth,
    buttonWidth: document.querySelector('.learn-use-plan').getBoundingClientRect().width,
    cardWidth: document.querySelector('.learn-strategy-card').getBoundingClientRect().width
  }));
  assert.equal(mobile.columns, 1);
  assert.ok(mobile.overflow <= 1, `390px Learn has no horizontal overflow (${mobile.overflow}px)`);
  assert.ok(Math.abs(mobile.buttonWidth - mobile.cardWidth + 30) < 4,
    'mobile Plan command fills the card content width');
  assert.deepEqual(pageErrors, [], `Learn emitted page errors: ${pageErrors.join('\n')}`);
});
