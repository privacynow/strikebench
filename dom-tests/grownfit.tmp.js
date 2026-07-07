'use strict';
const { spawn } = require('node:child_process');
const fs = require('node:fs'); const os = require('node:os'); const path = require('node:path');
const { chromium } = require('playwright');
const PORT = '7353', BASE = `http://localhost:${PORT}`;
async function api(m, p, b) {
  const r = await fetch(BASE + p, { method: m, headers: { 'Content-Type': 'application/json' },
    body: b === undefined ? undefined : JSON.stringify(b) });
  const t = await r.text();
  if (!r.ok) throw new Error(m + ' ' + p + ' -> ' + r.status + ': ' + t.slice(0, 200));
  return t ? JSON.parse(t) : null;
}
(async () => {
  const dbDir = fs.mkdtempSync(path.join(os.tmpdir(), 'gf-'));
  const server = spawn(process.env.JAVA_BIN, ['-jar', '../target/strikebench.jar'], {
    env: { ...process.env, PORT, FIXTURES_ONLY: 'true', DB_PATH: path.join(dbDir, 's.db') }, stdio: 'ignore' });
  for (let i = 0; i < 60; i++) { try { if ((await fetch(BASE + '/api/status')).ok) break; } catch (e) {} await new Promise(r => setTimeout(r, 500)); }
  // Grow: shares + covered call + two spreads => populated holdings + trades table
  await api('POST', '/api/positions/buy', { symbol: 'AAPL', shares: 200 });
  const rec = await api('POST', '/api/recommend', { symbol: 'AAPL', intent: 'exit', riskMode: 'balanced' });
  const cc = rec.candidates.find(c => c.strategy === 'COVERED_CALL');
  await api('POST', '/api/trades', { symbol: 'AAPL', strategy: 'COVERED_CALL', qty: 1, intent: 'exit', useHeldShares: true,
    legs: cc.legs.map(l => ({ action: l.action, type: l.type, strike: l.strike, expiration: l.expiration, ratio: 1 })) });
  const exps = (await api('GET', '/api/research/AAPL/expirations')).expirations;
  for (const q of [1, 1]) {
    await api('POST', '/api/trades', { symbol: 'AAPL', strategy: 'CREDIT_PUT_SPREAD', qty: q,
      legs: [{ action: 'SELL', type: 'PUT', strike: '242.5', expiration: exps[3], ratio: 1 },
             { action: 'BUY', type: 'PUT', strike: '240', expiration: exps[3], ratio: 1 }],
      thesis: 'bullish', horizon: 'month', riskMode: 'balanced' });
  }
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(BASE + '/'); await page.waitForSelector('#app[data-ready="true"]');
  if (await page.locator('#welcome-skip').count()) { await page.click('#welcome-skip'); await page.waitForSelector('#app[data-ready="true"]'); }
  for (const [w, h] of [[1440, 900], [1280, 800]]) {
    await page.setViewportSize({ width: w, height: h });
    await page.evaluate(() => App.navigate('#/home'));
    await page.waitForSelector('#app[data-ready="true"]');
    await new Promise(r => setTimeout(r, 900));
    const s = await page.evaluate(() => {
      const app = document.getElementById('app');
      return { content: Math.round(app.getBoundingClientRect().bottom), vh: window.innerHeight };
    });
    console.log(w + 'x' + h + ' grown: content-bottom', s.content, s.content <= s.vh ? 'FITS' : 'OVER BY ' + (s.content - s.vh));
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await new Promise(r => setTimeout(r, 400));
  await page.screenshot({ path: 'shots/v3-home-grown.png' });
  await browser.close(); server.kill();
})().catch(e => { console.error(e); process.exit(1); });
