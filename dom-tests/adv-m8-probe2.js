'use strict';
/* ADVERSARY probe round 2: news renderers, dead href behaviour, visual-lane envelope, reload. */
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const fixtures = require('./fixtures');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');

function contentType(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.css')) return 'text/css; charset=utf-8';
  if (file.endsWith('.svg')) return 'image/svg+xml';
  return 'application/octet-stream';
}
function servePublic(req, res) {
  const requested = new URL(req.url, 'http://127.0.0.1');
  const pathname = requested.pathname === '/' ? '/index.html' : decodeURIComponent(requested.pathname);
  const file = path.resolve(PUBLIC, `.${pathname}`);
  if (file !== PUBLIC && !file.startsWith(`${PUBLIC}${path.sep}`)) return res.writeHead(403).end('forbidden');
  fs.readFile(file, (error, body) => {
    if (error) return res.writeHead(error.code === 'ENOENT' ? 404 : 500).end(error.message);
    res.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' });
    res.end(body);
  });
}

/* The EXACT router desk.visual.test.js installs (lines 106-140), copied verbatim. */
async function installVisualWorld(page, state) {
  const world = fixtures.desk(state);
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const at = url.pathname;
    const research = at.match(/^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news))?$/);
    let body;
    if (at === '/api/config') body = { fixturesOnly: false, world: 'observed', marketLane: 'OBSERVED', scenarioMode: false };
    else if (at === '/api/status') body = { ok: true, status: 'READY', fixturesOnly: false };
    else if (at === '/api/world') body = { world: 'observed', revision: 1, epoch: 1 };
    else if (at === '/api/workspace') {
      body = { rev: 1, updatedAt: '2026-07-25T12:00:00Z', supportedVersion: 1, world: 'observed',
        marketLane: 'OBSERVED', accountId: 'acct-1', context: null, transition: null, unreadable: null };
    } else if (at === '/api/account') body = { account: { id: 'acct-1', cashCents: 5000000, buyingPowerCents: 9700000 }, ledger: [] };
    else if (at === '/api/portfolio/summary') body = world.book.summary;
    else if (at === '/api/portfolio/heat') body = world.book.heat;
    else if (at === '/api/portfolio/greeks') body = world.book.greeks;
    else if (at === '/api/portfolio/book-risk') body = world.book.bookRisk;
    else if (at === '/api/portfolio/accounts') body = [{ id: 'acct-1', name: 'Practice ••••0001' }];
    else if (at === '/api/positions') body = world.book.positionBook;
    else if (at === '/api/trades') body = world.book.tradePage;
    else if (at === '/api/plans') body = world.plans;
    else if (at === '/api/plans/portfolio') body = world.planPortfolio;
    else if (at === '/api/universe') body = world.market.universe || { symbols: [], sectors: [] };
    else if (at === '/api/strategies') body = { catalog: [] };
    else if (research) {
      const lane = research[2] || 'research';
      body = world.market[lane] !== undefined ? world.market[lane] : world.market.research;
    } else if (at.startsWith('/api/trades/')) {
      const id = decodeURIComponent(at.slice('/api/trades/'.length));
      body = world.book.tradeDetails[id] || { trade: null };
    } else body = {};
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  return world;
}

async function main() {
  const server = http.createServer(servePublic);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await chromium.launch({ headless: true });
  const out = {};

  // ---- A. desk.visual.test.js router, ALL-READY world ----
  {
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await ctx.newPage();
    await installVisualWorld(page, { positions: 4, workingIdeas: 5 });
    await page.goto(url);
    await page.waitForFunction(() => window.DeskBackend != null && window.WORKSPACE != null);
    await page.waitForSelector('#board');
    await page.waitForTimeout(2500);
    out.visualLane = await page.evaluate(() => ({
      chainBandText: document.getElementById('chainBand').textContent.replace(/\s+/g, ' ').trim().slice(0, 320),
      hero: (document.querySelector('#chainBand .authmarkethero') || {}).textContent || null,
      histPaths: document.querySelectorAll('#chainBand [data-hist-svg] path').length,
      histRects: document.querySelectorAll('#chainBand [data-hist-svg] rect').length,
      chainRows: document.querySelectorAll('#chainBand .authchainrow').length,
      newsRows: document.querySelectorAll('#newsBand #homeNewsList > *').length,
      researchDocKeys: (function () {
        const d = window.BOOK_AUTH && window.BOOK_AUTH.data && window.BOOK_AUTH.data.homeContext;
        const row = d && d.rows && d.rows[0];
        return row ? { symbol: row.symbol, hasResearch: !!row.research, hasHistory: !!row.history,
          hasChain: !!row.chain, hasNews: !!row.news, missing: (row.missing || []).map(m => m.key) } : null;
      })()
    }));
    await page.screenshot({ path: '/private/tmp/claude-501/-Users-tinker-output-optin/59dd524a-adcb-4f18-bffe-c79781e37c8f/scratchpad/advm8/visual-lane-ready.png' });
    await ctx.close();
  }

  // ---- B. authNewsHTML with the REAL shape ----
  {
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await ctx.newPage();
    await installVisualWorld(page, { positions: 4 });
    await page.goto(url);
    await page.waitForFunction(() => typeof window.authNewsHTML === 'function');
    out.newsRenderer = await page.evaluate(() => {
      const items = Array.from({ length: 20 }, (_, i) => ({ headline: 'Headline ' + (i + 1), url: null, source: 'Fixture Wire' }));
      const html = window.authNewsHTML({ news: { items: items } });
      const box = document.createElement('div');
      box.innerHTML = html;
      const anchors = Array.from(box.querySelectorAll('a'));
      return {
        anchors: anchors.length,
        hashHrefs: anchors.filter(a => a.getAttribute('href') === '#').length,
        targets: anchors.map(a => a.getAttribute('target'))[0],
        moreText: /more/i.test(html),
        lastHeadline: anchors.length ? anchors[anchors.length - 1].textContent : null,
        headerWouldSay: items.length
      };
    });
    // Does a href="#" target=_blank anchor open a second desk?
    out.deadHrefPopup = await page.evaluate(() => {
      const box = document.createElement('div');
      box.innerHTML = window.authNewsHTML({ news: { items: [{ headline: 'H', url: null, source: 'S' }] } });
      document.body.appendChild(box);
      return box.querySelector('a').href;
    });
    const before = ctx.pages().length;
    const popupWait = page.waitForEvent('popup', { timeout: 3000 }).catch(() => null);
    await page.evaluate(() => document.querySelector('body > div:last-child a').click());
    const popup = await popupWait;
    out.deadHref = { resolvedHref: out.deadHrefPopup, pagesBefore: before, popupUrl: popup ? popup.url() : null };
    await ctx.close();
  }

  // ---- C. reload persistence of market focus ----
  {
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await ctx.newPage();
    await installVisualWorld(page, { positions: 4 });
    await page.goto(url);
    await page.waitForTimeout(2000);
    const first = await page.evaluate(() => window.HOME_AUTH_SYMBOL);
    const other = await page.evaluate(() => {
      const b = Array.from(document.querySelectorAll('#sectorBand [data-auth-market-symbol]'))
        .map(x => x.getAttribute('data-auth-market-symbol')).find(s => s !== window.HOME_AUTH_SYMBOL);
      return b || null;
    });
    if (other) { await page.locator(`#sectorBand [data-auth-market-symbol="${other}"]`).click(); await page.waitForTimeout(800); }
    const afterClick = await page.evaluate(() => window.HOME_AUTH_SYMBOL);
    await page.reload();
    await page.waitForTimeout(2500);
    const afterReload = await page.evaluate(() => ({ sym: window.HOME_AUTH_SYMBOL, ws: window.WORKSPACE.marketSymbol }));
    out.reload = { first, other, afterClick, afterReload };
    await ctx.close();
  }

  await browser.close();
  await new Promise(r => server.close(r));
  console.log(JSON.stringify(out, null, 2));
}
main().catch(e => { console.error(e); process.exit(1); });
