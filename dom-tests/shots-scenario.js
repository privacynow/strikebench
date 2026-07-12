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

  // Beginner: research what-if with fan
  await page.click('#level-switch button[data-level="beginner"]');
  await go('#/research/AAPL');
  await page.waitForSelector('#whatif-card .sc-card');
  await page.click('#whatif-card .sc-card[data-shape="SELLOFF_REBOUND"]');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .fan-chart svg', { timeout: 20000 });
  await page.locator('#whatif-card').scrollIntoViewIfNeeded();
  await page.waitForTimeout(400);
  await shot('scen-research-beginner');

  // Handoff to Verify scenario mode, run a call spread
  await page.click('#whatif-verify');
  await page.waitForSelector('#bt-scenario-card', { timeout: 15000 });
  await page.click('#sc-pos .sym-chip[data-pos="CALL_SPREAD"]');
  await page.click('#sc-verify-run');
  await page.waitForSelector('#sc-verify-out .alert', { timeout: 30000 });
  await page.waitForTimeout(400);
  await page.locator('#sc-verify-out').scrollIntoViewIfNeeded();
  await shot('scen-verify-beginner');

  // Expert: form + math expandable + run
  await page.click('#level-switch button[data-level="expert"]');
  await page.evaluate(() => { App.state.scenarioForm = null; });
  await go('#/research/AAPL');
  await page.waitForSelector('#whatif-card #sc-model');
  await page.selectOption('#sc-model', 'HESTON');
  await page.click('#whatif-card .xp-head');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .fan-chart svg', { timeout: 20000 });
  await page.locator('#whatif-card').scrollIntoViewIfNeeded();
  await page.waitForTimeout(400);
  await shot('scen-research-expert');

  // Data center datasets card (expert), with generate form open
  await go('#/data/overview');
  await page.waitForSelector('#dc-datasets .status-item');
  await page.click('#dc-generate-btn');
  await page.waitForSelector('#dc-gen-sym');
  await page.locator('#dc-datasets').scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  await shot('scen-datasets');

  // Dark mode fan
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.evaluate(() => { localStorage.setItem('strikebench.theme', 'dark'); document.documentElement.setAttribute('data-theme', 'dark'); });
  await go('#/research/AAPL');
  await page.waitForSelector('#whatif-card #sc-model');
  await page.click('#whatif-run');
  await page.waitForSelector('#whatif-out .fan-chart svg', { timeout: 20000 });
  await page.locator('#whatif-card').scrollIntoViewIfNeeded();
  await page.waitForTimeout(400);
  await shot('scen-research-dark');

  await browser.close(); server.kill(); pg.drop();
  console.log('done');
})().catch(e => { console.error(e); process.exit(1); });
