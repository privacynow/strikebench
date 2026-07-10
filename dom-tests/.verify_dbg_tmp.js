'use strict';
const { chromium } = require('playwright');
(async () => {
  const base = 'http://127.0.0.1:' + (process.env.APP_PORT || '7442');
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.addInitScript(() => {
    Object.defineProperty(Document.prototype, 'hidden', { get: () => true, configurable: true });
    Object.defineProperty(Document.prototype, 'visibilityState', { get: () => 'hidden', configurable: true });
  });
  page.on('pageerror', e => console.log('PAGEERROR:', e.message));
  page.on('console', m => { if (m.type() === 'error') console.log('CONSOLE:', m.text()); });
  await page.goto(base + '/#/home');
  await page.waitForTimeout(8000);
  const state = await page.evaluate(() => ({
    ready: document.getElementById('app') && document.getElementById('app').getAttribute('data-ready'),
    hash: location.hash,
    appHtmlLen: (document.getElementById('app') || {}).innerHTML ? document.getElementById('app').innerHTML.length : 0
  }));
  console.log(JSON.stringify(state));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
