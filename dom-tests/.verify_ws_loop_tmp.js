'use strict';
// Real-browser demonstration: load the REAL app (real workspace.js) with document.hidden
// spoofed to true (background tab), and count PUT /api/workspace over 40s.
// Correct behavior: exactly 1 PUT (the boot-armed first save), then quiet.
const { chromium } = require('playwright');

(async () => {
  const base = 'http://127.0.0.1:' + (process.env.APP_PORT || '7442');
  const browser = await chromium.launch();
  const page = await browser.newPage();

  // Background-tab spoof: workspace.js reads document.hidden only.
  await page.addInitScript(() => {
    Object.defineProperty(Document.prototype, 'hidden', { get: () => true, configurable: true });
    Object.defineProperty(Document.prototype, 'visibilityState', { get: () => 'hidden', configurable: true });
  });

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

  const putsAfterBoot = puts.filter(t => t >= t0).length;
  const getsAfterBoot = gets.filter(t => t >= t0).length;
  console.log('REAL BROWSER, hidden tab, 40s idle after boot (no user input at all):');
  console.log('  PUT /api/workspace count:', putsAfterBoot);
  console.log('  GET /api/workspace count (post-boot refetches):', getsAfterBoot);
  console.log(putsAfterBoot >= 4
    ? 'CONFIRMED in real browser: self-sustaining autosave loop (expected exactly 1 PUT).'
    : (putsAfterBoot <= 1 ? 'REFUTED in real browser: went quiet after ' + putsAfterBoot + ' PUT.'
       : 'PARTIAL: ' + putsAfterBoot + ' PUTs in 40s.'));
  await browser.close();
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
