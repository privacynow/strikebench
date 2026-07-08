'use strict';
const { spawn } = require('node:child_process');
const fs = require('node:fs'); const os = require('node:os'); const path = require('node:path');
const { chromium } = require('playwright');
const PORT = '7431', BASE = `http://localhost:${PORT}`;
(async () => {
  const dbDir = fs.mkdtempSync(path.join(os.tmpdir(), 'inst2-'));
  const modelsDir = path.join(dbDir, 'models');
  const server = spawn(process.env.JAVA_BIN, ['-jar', '../target/strikebench.jar'], {
    env: { ...process.env, PORT, FIXTURES_ONLY: 'true', DB_PATH: path.join(dbDir, 's.db'), MODELS_DIR: modelsDir },
    stdio: 'ignore' });
  for (let i = 0; i < 60; i++) { try { if ((await fetch(BASE + '/api/status')).ok) break; } catch (e) {} await new Promise(r => setTimeout(r, 500)); }
  console.log('pre-install manifest:', (await fetch(BASE + '/models/manifest.json')).status);
  // Drive the REAL user flow through the UI: enable card -> install -> intake appears
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(BASE + '/'); await page.waitForSelector('#app[data-ready="true"]');
  if (await page.locator('#welcome-skip').count()) { await page.click('#welcome-skip'); await page.waitForSelector('#app[data-ready="true"]'); }
  await page.evaluate(() => App.navigate('#/recommend/manual'));
  await page.waitForSelector('#assist-enable', { timeout: 15000 });
  await page.click('#assist-install');
  await page.waitForSelector('#assist-intake', { timeout: 300000 }); // card swaps in after install + re-render
  console.log('intake card appeared after in-UI install ✓');
  console.log('post-install manifest (no restart):', (await fetch(BASE + '/models/manifest.json')).status);
  await page.fill('#assist-text', 'bullish on AAPL this month, risk at most $250');
  await page.click('#assist-parse');
  await page.waitForSelector('#assist-chips .chip', { timeout: 240000 });
  const chips = await page.textContent('#assist-chips');
  console.log('parsed:', chips.slice(0, 140));
  await browser.close(); server.kill();
})().catch(e => { console.error('FATAL', e); process.exit(1); });
