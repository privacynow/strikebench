/* Screenshots: continue-row on Home + the calm cooldown chip. */
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const { freshDb } = require('./pgtest');
const path = require('path');

const PORT = 7094, BASE = 'http://localhost:' + PORT;
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
  const page = await browser.newPage({ viewport: { width: 1380, height: 900 } });
  await page.goto(BASE + '/'); await page.waitForSelector('#app[data-ready="true"]');
  await page.evaluate(() => {
    localStorage.setItem('strikebench.welcomed', '1');
    App.context.selectSymbol('QQQ');
    App.state.ticket = { symbol: 'AAPL', candidate: { strategy: 'DEBIT_CALL_SPREAD' }, step: 5 };
  });
  await page.evaluate(() => { location.hash = '#/home'; return App.render(); });
  await page.waitForSelector('#continue-row .sym-chip');
  await page.evaluate(() => App.showCooldownChip({ provider: 'cboe', untilMs: Date.now() + 22 * 60000 }));
  await page.waitForTimeout(900);
  await page.screenshot({ path: 'shots/cont-home.png' });
  await browser.close(); server.kill(); pg.drop();
  console.log('done');
})().catch(e => { console.error(e); process.exit(1); });
