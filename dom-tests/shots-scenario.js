/* Screenshots of the Scenario Studio surfaces (beginner + expert, light + dark). */
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const { freshDb } = require('./pgtest');
const path = require('path');

const PORT = 7093, BASE = 'http://localhost:' + PORT;
const JAR = path.resolve(__dirname, '..', 'target', 'strikebench.jar');
const JAVA = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';

(async () => {
  const pg = freshDb();
  const server = spawn(JAVA, ['-jar', JAR], { env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' }, stdio: 'ignore' });
  for (let i = 0; i < 60; i++) {
    try { const r = await fetch(BASE + '/api/health'); if (r.ok) break; } catch (e) { }
    await new Promise(r => setTimeout(r, 500));
  }
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1380, height: 940 } });
  const shot = (n) => page.screenshot({ path: 'shots/' + n + '.png' });
  const go = async (h) => { await page.evaluate(x => { location.hash = x; }, h); await page.waitForSelector('#app[data-ready="true"]'); await page.waitForTimeout(900); };

  await page.goto(BASE + '/'); await page.waitForSelector('#app[data-ready="true"]');
  await page.evaluate(() => { localStorage.setItem('strikebench.welcomed', '1'); });

  const openFutures = async () => {
    await page.click('#research-flow [data-band="evidence"] .flow-band-invitation');
    await page.waitForSelector('[data-research-panel="view"]:not([hidden])');
    await page.click('#research-outcomes-nav .outcome-basis[data-basis="futures"]');
    await page.waitForSelector('#whatif-card');
  };

  // Beginner: Research decision range + fan
  await page.click('#level-switch button[data-level="beginner"]');
  await go('#/research/AAPL');
  await openFutures();
  await page.waitForSelector('#whatif-card .sc-card');
  await page.click('#whatif-card .sc-card[data-shape="SELLOFF_REBOUND"]');
  await page.fill('#whatif-target', '270');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .scenario-decision', { timeout: 20000 });
  await page.locator('#whatif-out').scrollIntoViewIfNeeded();
  await page.waitForTimeout(700); // let normal product motion settle; animations remain enabled
  await shot('scen-research-beginner');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('#whatif-out').scrollIntoViewIfNeeded();
  await page.waitForTimeout(500);
  await shot('scen-research-mobile');
  await page.setViewportSize({ width: 1380, height: 940 });

  // Handoff to Outcomes, then run a call spread.
  await page.click('#whatif-verify');
  await page.waitForSelector('#bt-scenario-card', { timeout: 15000 });
  await page.click('#sc-pos .sc-card[data-pos="DEBIT_CALL_SPREAD"]');
  await page.click('#sc-verify-run');
  await page.waitForSelector('#sc-verify-out .alert', { timeout: 30000 });
  await page.waitForTimeout(700);
  await page.locator('#sc-verify-out').scrollIntoViewIfNeeded();
  await shot('scen-verify-beginner');

  // Expert: form + math expandable + run
  await page.click('#level-switch button[data-level="expert"]');
  await page.evaluate(() => { App.state.scenarioForm = null; });
  await go('#/research/AAPL');
  await openFutures();
  await page.waitForSelector('#whatif-card #sc-model');
  await page.selectOption('#sc-model', 'HESTON');
  await page.click('#whatif-card .xp-head');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .fan-chart svg', { timeout: 20000 });
  await page.locator('#whatif-out').scrollIntoViewIfNeeded();
  await page.waitForTimeout(700);
  await shot('scen-research-expert');

  // Practice handoff: configure one realization, but do not create it automatically.
  await go('#/research/AAPL');
  await openFutures();
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-practice-random', { timeout: 20000 });
  await page.click('#whatif-practice-random');
  await page.waitForSelector('#sim-creator');
  await page.locator('#sim-creator').scrollIntoViewIfNeeded();
  await page.waitForTimeout(700);
  await shot('scen-practice-creator');

  // Dark mode fan
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.evaluate(() => { localStorage.setItem('strikebench.theme', 'dark'); document.documentElement.setAttribute('data-theme', 'dark'); });
  await go('#/research/AAPL');
  await openFutures();
  await page.waitForSelector('#whatif-card #sc-model');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .fan-chart svg', { timeout: 20000 });
  await page.locator('#whatif-card').scrollIntoViewIfNeeded();
  await page.waitForTimeout(400);
  await shot('scen-research-dark');

  await browser.close(); server.kill(); pg.drop();
  console.log('done');
})().catch(e => { console.error(e); process.exit(1); });
