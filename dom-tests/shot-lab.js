/* Screenshots of the redesigned Research Lab: Beginner/Expert × light/dark, plus mobile.
 * Run from dom-tests/ after `mvn package`:  node shot-lab.js
 */
'use strict';
const { spawn } = require('node:child_process');
const path = require('node:path');
const { chromium } = require('playwright');
const { freshDb } = require('./pgtest');

const PORT = process.env.PORT || '7079';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';
const OUT = path.resolve(__dirname, 'shots');

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { if ((await fetch(`${BASE}/api/status`)).ok) return; } catch (e) {}
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error('server did not start');
}

(async () => {
  const pg = freshDb();
  const server = spawn(JAVA, ['-jar', JAR], { env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' }, stdio: 'ignore' });
  try {
    await waitForServer();
    const browser = await chromium.launch();

    async function shot(name, { level, dark, mobile, run }) {
      const page = await browser.newPage({
        viewport: mobile ? { width: 390, height: 844 } : { width: 1280, height: 900 },
        colorScheme: dark ? 'dark' : 'light'
      });
      await page.goto(BASE + '/');
      await page.waitForSelector('#app[data-ready="true"]');
      await page.evaluate(l => Learn.setLevel(l), level);
      if (dark) await page.evaluate(() => localStorage.setItem('strikebench.theme', 'dark'));
      await page.evaluate(() => { window.location.hash = '#/lab'; });
      await page.waitForSelector('.lab-grid');
      await page.waitForSelector('.lab-hero');
      if (run) {
        // Exercise the optimizer + hypothesis so results (verdict/facts/charts) are visible.
        // Diagnostic mode (Expert) funds the least-bad set on the fixture universe; normal mode
        // honestly funds nothing (no positive-EV idea) — either is a valid thing to screenshot.
        if (await page.locator('#lab-opt-diag').count()) await page.check('#lab-opt-diag');
        await page.click('#lab-opt-run');
        await page.waitForFunction(() => {
          const out = document.querySelector('#lab-opt-out');
          return out && (out.querySelector('#lab-opt-summary')
            || /positive modeled expected value|Nothing funded/.test(out.textContent));
        }, { timeout: 20000 });
        await page.click('#lab-hyp-run');
        await page.waitForSelector('#lab-hyp-out .alert', { timeout: 20000 });
      }
      await page.waitForTimeout(700);
      await page.screenshot({ path: path.join(OUT, name), fullPage: true });
      console.log('wrote', name);
      await page.close();
    }

    await shot('lab-v2-beginner.png', { level: 'beginner', run: true });
    await shot('lab-v2-expert.png', { level: 'expert', run: true });
    await shot('lab-v2-dark.png', { level: 'expert', dark: true, run: true });
    await shot('lab-v2-mobile.png', { level: 'beginner', mobile: true, run: false });

    await browser.close();
  } finally {
    server.kill();
    pg.drop();
  }
})();
