'use strict';
// Real-browser demo against the AS-CITED (HEAD) workspace.js, injected over the running app.
const { chromium } = require('playwright');
const fs = require('fs');

(async () => {
  const base = 'http://127.0.0.1:' + (process.env.APP_PORT || '7442');
  const headWs = fs.readFileSync(__dirname + '/.ws_head_tmp.js', 'utf8');
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.addInitScript(() => {
    Object.defineProperty(Document.prototype, 'hidden', { get: () => true, configurable: true });
    Object.defineProperty(Document.prototype, 'visibilityState', { get: () => 'hidden', configurable: true });
  });
  await page.route('**/js/workspace.js', route => route.fulfill({
    status: 200, contentType: 'application/javascript', body: headWs
  }));

  const puts = [];
  const gets = [];
  page.on('request', req => {
    if (req.url().endsWith('/api/workspace')) {
      if (req.method() === 'PUT') puts.push(Date.now());
      if (req.method() === 'GET') gets.push(Date.now());
    }
  });

  await page.goto(base + '/#/home');
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  const t0 = Date.now();
  await page.waitForTimeout(40000);

  const p = puts.filter(t => t >= t0);
  const g = gets.filter(t => t >= t0).length;
  console.log('REAL BROWSER, HEAD workspace.js, hidden tab, 40s idle after boot:');
  console.log('  PUT /api/workspace count:', p.length,
    ' intervals(s):', p.slice(1).map((t, i) => ((t - p[i]) / 1000).toFixed(1)).join(','));
  console.log('  GET /api/workspace count:', g);
  console.log(p.length >= 4
    ? 'CONFIRMED in real browser: self-sustaining autosave loop (correct behavior: exactly 1 PUT).'
    : (p.length <= 1 ? 'REFUTED in real browser: quiet after ' + p.length + ' PUT.'
       : 'PARTIAL: ' + p.length + ' PUTs.'));
  await browser.close();
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
