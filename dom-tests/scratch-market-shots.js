'use strict';
/* SCRATCH — screenshots + the sourceless-headline click behaviour. */

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const market = require('./fixtures/market');
const wire = require('./fixtures/wire');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');
const OUT = '/private/tmp/claude-501/-Users-tinker-output-optin/59dd524a-adcb-4f18-bffe-c79781e37c8f/scratchpad/market';

function contentType(f) {
  if (f.endsWith('.html')) return 'text/html; charset=utf-8';
  if (f.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (f.endsWith('.css')) return 'text/css; charset=utf-8';
  return 'application/octet-stream';
}
function servePublic(req, res) {
  const p = new URL(req.url, 'http://127.0.0.1').pathname;
  const file = path.resolve(PUBLIC, `.${p === '/' ? '/index.html' : decodeURIComponent(p)}`);
  fs.readFile(file, (e, b) => e ? res.writeHead(404).end(e.message)
    : (res.writeHead(200, { 'Content-Type': contentType(file) }), res.end(b)));
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const server = http.createServer(servePublic);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1600, height: 900 } });
  const page = await context.newPage();
  await page.route('**/api/**', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await page.goto(url);
  await page.waitForFunction(() => typeof window.authNewsHTML === 'function');

  const news = market.news('ready', { symbol: wire.GOLDEN_SYMBOL, count: 5 }).body;
  news.items.forEach(i => { i.url = null; });
  await page.evaluate(doc => {
    const host = document.createElement('div');
    host.id = 'probeNews';
    host.style.cssText = 'position:fixed;left:0;top:0;z-index:99999;background:#111;padding:8px;width:520px';
    host.innerHTML = window.authNewsHTML({ news: doc });
    document.body.appendChild(host);
  }, news);
  const before = context.pages().length;
  const [popup] = await Promise.all([
    context.waitForEvent('page', { timeout: 4000 }).catch(() => null),
    page.click('#probeNews a')
  ]);
  console.log(JSON.stringify({
    pagesBefore: before,
    pagesAfter: context.pages().length,
    popupUrl: popup ? popup.url() : null
  }, null, 2));

  await browser.close();
  await new Promise(r => server.close(r));
})();
