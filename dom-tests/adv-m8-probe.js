'use strict';
/* ADVERSARY probe for the M8 market/chain/research findings. Read-only measurement. */
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const fixtures = require('./fixtures');
const market = require('./fixtures/market');
const wire = require('./fixtures/wire');
const golden = require('./fixtures/golden');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');
const OUT = '/private/tmp/claude-501/-Users-tinker-output-optin/59dd524a-adcb-4f18-bffe-c79781e37c8f/scratchpad/advm8';
fs.mkdirSync(OUT, { recursive: true });
const SYMBOL = golden.SYMBOL;

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

/* /api/quotes rows, built to the QuoteView Java record (ApiResponses.java:77-131):
   an unpriced row carries markBasis "UNAVAILABLE" and a quoteUnavailableReason. */
function quotesBody(lanes, symbols) {
  const research = lanes.research.status === 200 ? lanes.research.body : null;
  return {
    marketLane: 'OBSERVED', world: 'observed',
    quotes: symbols.map(symbol => {
      const priced = !!(research && research.displayPrice != null && symbol === SYMBOL);
      const q = priced ? research.quote : null;
      return {
        symbol,
        priced,
        displayPrice: priced ? research.displayPrice : null,
        displayChangePct: priced ? research.displayChangePct : null,
        markBasis: priced ? 'MID' : 'UNAVAILABLE',
        priceIsPreviousClose: false,
        quoteUnavailableReason: priced ? null : `No usable price for ${symbol} in the active market.`,
        last: priced ? q.last : null, bid: priced ? q.bid : null,
        ask: priced ? q.ask : null, prevClose: priced ? q.prevClose : null,
        optionable: priced,
        freshness: priced ? research.freshness : 'UNAVAILABLE',
        source: priced ? (q && q.source) : null,
        evidence: priced ? { provenance: 'OBSERVED', age: research.freshness, source: q && q.source } : null,
        asOf: priced ? (q && q.asOfEpochMs) : null,
        refreshing: false,
        quote: priced ? q : null
      };
    })
  };
}

async function installWorld(page, state) {
  const world = fixtures.desk(state);
  const lanes = market.marketDocuments({
    symbol: SYMBOL, quote: state.quote || 'ready', history: state.history || 'ready',
    chain: state.chain || 'ready', news: state.news || 'ready',
    newsCount: state.newsCount == null ? 5 : state.newsCount,
    historySessions: state.historySessions == null ? 60 : state.historySessions,
    chainStrikes: state.chainStrikes
  });
  if (state.chainStrikes) {
    lanes.chain = market.chain(state.chain || 'ready', { symbol: SYMBOL, strikes: state.chainStrikes });
  }
  if (state.trendSessions != null && lanes.research.status === 200 && lanes.research.body.regime) {
    lanes.research.body.regime.trendSessions = state.trendSessions;
  }
  if (state.nullNewsUrls && lanes.news.status === 200) {
    lanes.news.body.items.forEach(item => { item.url = null; });
  }
  const calls = [];
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const at = url.pathname;
    calls.push(route.request().method() + ' ' + at + url.search);
    const research = at.match(/^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news|expected-move))?$/);
    let body, out = null;
    if (at === '/api/config') body = { fixturesOnly: false, world: 'observed', marketLane: 'OBSERVED', scenarioMode: false };
    else if (at === '/api/status') body = { ok: true, status: 'READY', fixturesOnly: false };
    else if (at === '/api/world') body = { world: 'observed', revision: 1, epoch: 1 };
    else if (at === '/api/workspace') body = { rev: 1, updatedAt: '2026-07-25T12:00:00Z', supportedVersion: 1, world: 'observed', marketLane: 'OBSERVED', accountId: 'acct-1', context: page.__ctx || null, transition: null, unreadable: null };
    else if (at === '/api/account') body = { account: { id: 'acct-1', cashCents: 5000000, buyingPowerCents: 9700000 }, ledger: [] };
    else if (at === '/api/portfolio/summary') body = world.book.summary;
    else if (at === '/api/portfolio/heat') body = world.book.heat;
    else if (at === '/api/portfolio/greeks') body = world.book.greeks;
    else if (at === '/api/portfolio/book-risk') body = world.book.bookRisk;
    else if (at === '/api/portfolio/accounts') body = [{ id: 'acct-1', name: 'Practice ••••0001' }];
    else if (at === '/api/positions') body = world.book.positionBook;
    else if (at === '/api/trades') body = world.book.tradePage;
    else if (at === '/api/plans') body = world.plans;
    else if (at === '/api/plans/portfolio') body = world.planPortfolio;
    else if (at === '/api/universe') body = world.market.universe || { symbols: [SYMBOL, 'ZAA', 'ZBM'], sectors: [] };
    else if (at === '/api/strategies') body = { catalog: [] };
    else if (at === '/api/quotes') body = quotesBody(lanes, String(url.searchParams.get('symbols') || SYMBOL).split(',').map(s => s.trim().toUpperCase()));
    else if (research) {
      const lane = research[2] || 'research';
      if (lane === 'expected-move') body = { available: false, reason: 'No expected-move receipt in this fixture world.' };
      else out = lanes[lane];
    } else if (at.startsWith('/api/trades/')) {
      const id = decodeURIComponent(at.slice('/api/trades/'.length));
      body = world.book.tradeDetails[id] || { trade: null };
    } else body = {};
    if (out) await route.fulfill({ status: out.status, contentType: 'application/json', body: JSON.stringify(out.body) });
    else await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  return { world, lanes, calls };
}

async function bootHome(page, url) {
  await page.goto(url);
  await page.waitForFunction(() => window.DeskBackend != null && window.WORKSPACE != null);
  await page.waitForSelector('#board');
  await page.waitForFunction(() => {
    const b = document.getElementById('chainBand');
    return b && b.textContent.trim().length > 20;
  }, null, { timeout: 15000 });
  await page.waitForTimeout(700);
}

async function main() {
  const server = http.createServer(servePublic);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await chromium.launch({ headless: true });
  const results = {};

  async function withWorld(state, fn, viewport) {
    const ctx = await browser.newContext({ viewport: viewport || { width: 1920, height: 1080 } });
    const page = await ctx.newPage();
    const errs = [];
    page.on('pageerror', e => errs.push(e.message));
    const w = await installWorld(page, state);
    await bootHome(page, url);
    const out = await fn(page, w);
    await ctx.close();
    return { out, errs };
  }

  const heroRead = () => ({
    hero: (document.querySelector('#chainBand .authmarkethero') || {}).textContent || null,
    notice: (document.querySelector('#chainBand .authslotnotice') || {}).textContent || null,
    facts: (document.querySelector('#chainBand .amh-facts') || {}).textContent || null,
    chainBandHasStale: /stale/i.test(document.getElementById('chainBand').textContent),
    watchRow: (document.querySelector('#sectorBand .authmarketrow.on') || {}).textContent || null
  });

  // 1. stale vs ready hero
  results.heroReady = (await withWorld({ positions: 4, workingIdeas: 5, quote: 'ready' }, p => p.evaluate(heroRead))).out;
  results.heroStale = (await withWorld({ positions: 4, workingIdeas: 5, quote: 'stale' }, p => p.evaluate(heroRead))).out;
  // 2. quote error / missing
  results.heroQuoteError = (await withWorld({ positions: 4, workingIdeas: 5, quote: 'error' }, p => p.evaluate(heroRead))).out;
  results.heroQuoteMissing = (await withWorld({ positions: 4, workingIdeas: 5, quote: 'missing' }, p => p.evaluate(heroRead))).out;

  // 3. trend label
  for (const n of [20, 60, 63, 252]) {
    results['trend' + n] = (await withWorld({ positions: 4, trendSessions: n }, p => p.evaluate(() =>
      (document.querySelector('#chainBand .amh-facts') || {}).textContent))).out;
  }

  // 4. pills with 60 sessions
  results.pills = (await withWorld({ positions: 4, historySessions: 60 }, async page => {
    const out = {};
    for (const key of ['1d', '1w', '1m', '3m', '6m', 'ytd', '1y', '5y', 'all']) {
      const sel = `#chainBand [data-hist-host="home"] [data-hist-pill="${key}"]`;
      const disabled = await page.locator(sel).isDisabled();
      if (!disabled) await page.locator(sel).click();
      await page.waitForTimeout(60);
      out[key] = await page.evaluate(() => {
        const wrap = document.querySelector('#chainBand [data-hist-host="home"]');
        const svg = wrap.querySelector('[data-hist-svg]');
        return { len: svg.innerHTML.length, hash: svg.innerHTML.length + ':' + svg.innerHTML.slice(0, 60),
          receipt: wrap.querySelector('[data-hist-read]').textContent,
          onPill: Array.from(wrap.querySelectorAll('.histpill.on')).map(b => b.textContent).join(',') };
      });
      out[key].disabled = disabled;
    }
    out.storedBars = await page.evaluate(() => (window.HIST_STORE[Object.keys(window.HIST_STORE)[0]] || {}).bars.length);
    return out;
  })).out;

  // 5. chain rows with 25 strikes
  const wide = []; for (let s = 220; s <= 280; s += 2.5) wide.push(s);
  results.chainWide = (await withWorld({ positions: 4, chainStrikes: wide }, p => p.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#chainBand .authchainrow'));
    const first = rows[0];
    return {
      count: rows.length,
      strikes: rows.map(r => r.querySelector('b').textContent),
      moreText: /\+\s*\d+\s*more/i.test(document.getElementById('chainBand').textContent),
      receipt: (document.querySelector('#chainBand .authreceipt') || {}).textContent,
      rowTag: first && first.tagName, rowTabIndex: first && first.tabIndex,
      rowRole: first && first.getAttribute('role'), rowTitle: first && first.getAttribute('title'),
      scrolls: (() => { const c = document.querySelector('#chainBand .authchainrows'); if (!c) return null;
        const st = getComputedStyle(c); return { ox: st.overflowX, oy: st.overflowY, sh: c.scrollHeight, ch: c.clientHeight }; })()
    };
  }))).out;

  // 6. chain row click
  results.chainClick = (await withWorld({ positions: 4 }, async page => {
    const before = await page.evaluate(() => ({ level: window.state.level, sym: window.HOME_AUTH_SYMBOL, board: document.getElementById('chainBand').textContent.length }));
    await page.locator('#chainBand .authchainrow').first().click();
    await page.waitForTimeout(400);
    const after = await page.evaluate(() => ({ level: window.state.level, sym: window.HOME_AUTH_SYMBOL, board: document.getElementById('chainBand').textContent.length }));
    const kb = await page.evaluate(() => {
      const r = document.querySelector('#chainBand .authchainrow');
      r.focus(); return document.activeElement === r;
    });
    return { before, after, focusable: kb };
  })).out;

  // 7. chain notice punctuation
  results.chainErr = (await withWorld({ positions: 4, chain: 'error' }, p => p.evaluate(() =>
    (document.querySelector('#chainBand .authslotnotice') || {}).textContent))).out;
  results.chainMissing = (await withWorld({ positions: 4, chain: 'missing' }, p => p.evaluate(() =>
    (document.querySelector('#chainBand .authslotnotice') || {}).textContent))).out;

  // 8. history missing / error
  const histRead = () => {
    const wrap = document.querySelector('#chainBand [data-hist-host="home"]');
    return {
      pills: Array.from(wrap.querySelectorAll('.histpill')).map(b => b.textContent + (b.disabled ? '(off)' : '')),
      chips: Array.from(wrap.querySelectorAll('.histchip')).map(b => b.textContent + (b.classList.contains('on') ? '(on)' : '')),
      svgText: wrap.querySelector('[data-hist-svg]').textContent,
      unavailable: (wrap.querySelector('.histunavailable') || {}).textContent || null,
      receipt: wrap.querySelector('[data-hist-read]').textContent,
      phase: window.HIST_STORE[document.querySelector('#chainBand [data-hist-host="home"]').getAttribute('data-hist-sym')].phase
    };
  };
  results.histMissing = (await withWorld({ positions: 4, history: 'missing' }, p => p.evaluate(histRead))).out;
  results.histError = (await withWorld({ positions: 4, history: 'error' }, p => p.evaluate(histRead))).out;

  // 9. expected-move chip
  results.expMove = (await withWorld({ positions: 4 }, async (page, w) => {
    const r = await page.evaluate(() => {
      const wrap = document.querySelector('#chainBand [data-hist-host="home"]');
      const chip = wrap.querySelector('[data-hist-chip="cone"]');
      const sym = wrap.getAttribute('data-hist-sym');
      return { chipClass: chip.className, coneOn: window.MKT_CHART.ov.cone,
        overlayExpiry: window.histOverlayFor('home', sym).expiry,
        chainExpiration: (function () { const row = window.authHomeContextRow(window.BOOK_AUTH.data, sym); return row && row.chain && row.chain.expiration; })(),
        emStore: Object.keys(window.EM_STORE) };
    });
    r.emCalls = w.calls.filter(c => /expected-move/.test(c));
    return r;
  })).out;

  // 10. focus persistence
  results.persist = (await withWorld({ positions: 4 }, async page => {
    const patches = [];
    page.on('request', req => { if (req.method() === 'PATCH' && /workspace/.test(req.url())) patches.push(req.postData()); });
    const before = await page.evaluate(() => window.HOME_AUTH_SYMBOL);
    const other = await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('#sectorBand [data-auth-market-symbol]'))
        .map(b => b.getAttribute('data-auth-market-symbol'));
      return btns.find(s => s !== window.HOME_AUTH_SYMBOL) || null;
    });
    if (other) await page.locator(`#sectorBand [data-auth-market-symbol="${other}"]`).click();
    await page.waitForTimeout(900);
    const after = await page.evaluate(() => ({ sym: window.HOME_AUTH_SYMBOL, ws: JSON.parse(JSON.stringify(window.WORKSPACE)) }));
    return { before, other, after, patches, fields: await page.evaluate(() => window.WORKSPACE_CLIENT_FIELDS) };
  })).out;

  // 11. news with null urls + counts
  results.news = (await withWorld({ positions: 4, newsCount: 20, nullNewsUrls: true }, p => p.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('#newsBand #homeNewsList > *'));
    const html = window.authNewsHTML ? window.authNewsHTML({ items: Array.from({ length: 20 }, (_, i) => ({ headline: 'H' + i, url: null, source: 'S' })) }, 'GLDN') : null;
    return {
      homeRows: rows.length,
      homeAnchors: document.querySelectorAll('#newsBand #homeNewsList a').length,
      homeHash: document.querySelectorAll('#newsBand a[href="#"]').length,
      header: (document.querySelector('#newsBand .lenshd') || {}).textContent,
      authNewsHTMLExists: typeof window.authNewsHTML,
      authNewsHTMLHashLinks: html ? (html.match(/href="#"/g) || []).length : null,
      authNewsHTMLAnchors: html ? (html.match(/<a /g) || []).length : null,
      authNewsHTMLMore: html ? /more/i.test(html) : null
    };
  }))).out;

  await browser.close();
  await new Promise(r => server.close(r));
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
  console.log(JSON.stringify(results, null, 2));
}
main().catch(e => { console.error(e); process.exit(1); });
