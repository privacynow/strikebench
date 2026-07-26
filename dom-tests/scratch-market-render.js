'use strict';
/* SCRATCH — measure the shared renderers directly in the booted page. */

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const market = require('./fixtures/market');
const wire = require('./fixtures/wire');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');

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
  const server = http.createServer(servePublic);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1920, height: 1080 } })).newPage();
  await page.route('**/api/**', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
  await page.goto(url);
  await page.waitForFunction(() => typeof window.authNewsHTML === 'function');

  const news20 = market.news('ready', { symbol: wire.GOLDEN_SYMBOL, count: 20 }).body;
  const newsNoUrl = JSON.parse(JSON.stringify(news20));
  newsNoUrl.items.forEach(i => { i.url = null; });
  const chainWide = market.chain('ready', {
    symbol: wire.GOLDEN_SYMBOL,
    strikes: Array.from({ length: 25 }, (_, i) => 220 + i * 2.5)
  }).body;

  const out = await page.evaluate(([news, newsNoUrlDoc, chain]) => {
    const parse = html => { const d = document.createElement('div'); d.innerHTML = html; return d; };
    const a = parse(window.authNewsHTML({ news: news }));
    const b = parse(window.authNewsHTML({ news: newsNoUrlDoc }));
    const c = parse(window.authHomeChainHTML({ chain: chain }, 250, 'GLDN'));
    return {
      positionNews: {
        headerCountWouldSay: news.items.length,
        anchorsRendered: a.querySelectorAll('a').length,
        anyMoreText: /more/i.test(a.textContent),
        lastHeadline: a.querySelectorAll('a b')[a.querySelectorAll('a b').length - 1].textContent
      },
      positionNewsNoUrl: {
        anchors: b.querySelectorAll('a').length,
        hrefs: Array.from(b.querySelectorAll('a')).slice(0, 3).map(x => x.getAttribute('href')),
        targets: Array.from(b.querySelectorAll('a')).slice(0, 1).map(x => x.getAttribute('target'))
      },
      chain: {
        strikesInDocument: chain.calls.length,
        rowsRendered: c.querySelectorAll('.authchainrow').length,
        receipt: c.querySelector('.authreceipt').textContent,
        strikesShown: Array.from(c.querySelectorAll('.authchainrow b')).map(x => x.textContent),
        anyMore: /more/i.test(c.textContent)
      }
    };
  }, [news20, newsNoUrl, chainWide]);

  console.log(JSON.stringify(out, null, 2));
  await browser.close();
  await new Promise(r => server.close(r));
})();
