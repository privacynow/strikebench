'use strict';
/* SCRATCH — market/history/chain/news lane matrix. Not part of any lane. */

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const fixtures = require('./fixtures');
const market = require('./fixtures/market');
const wire = require('./fixtures/wire');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');
const OUT = path.resolve('/private/tmp/claude-501/-Users-tinker-output-optin/59dd524a-adcb-4f18-bffe-c79781e37c8f/scratchpad/market');
const SYMBOL = wire.GOLDEN_SYMBOL;

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
  if (file !== PUBLIC && !file.startsWith(`${PUBLIC}${path.sep}`)) { res.writeHead(403).end('forbidden'); return; }
  fs.readFile(file, (error, body) => {
    if (error) { res.writeHead(error.code === 'ENOENT' ? 404 : 500).end(error.message); return; }
    res.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' });
    res.end(body);
  });
}

/** quote row derived from the research lane state so /api/quotes agrees with /api/research. */
function quotesBody(lanes, symbols) {
  const research = lanes.research.status === 200 ? lanes.research.body : null;
  return {
    marketLane: 'OBSERVED', world: 'observed',
    quotes: symbols.map(symbol => {
      const priced = research && research.displayPrice != null && symbol === SYMBOL;
      return {
        symbol,
        priced: !!priced,
        displayPrice: priced ? research.displayPrice : null,
        displayChangePct: priced ? research.displayChangePct : null,
        markBasis: priced ? 'MID' : null,
        priceIsPreviousClose: false,
        freshness: priced ? research.freshness : 'UNAVAILABLE',
        source: priced ? 'FIXTURE_EXECUTABLE_BOOK' : null,
        asOfEpochMs: priced ? wire.OBSERVED_AT_MS : null,
        quote: priced ? Object.assign({}, research.quote, { symbol }) : null,
        evidence: priced ? { provenance: 'OBSERVED', age: 'REALTIME', source: 'FIXTURE_EXECUTABLE_BOOK' } : null,
        unavailableReason: priced ? null : `No usable price for ${symbol}.`
      };
    })
  };
}

async function installWorld(page, state) {
  const world = fixtures.desk(state);
  const lanes = market.marketDocuments({
    symbol: SYMBOL, quote: state.quote, history: state.history,
    chain: state.chain, news: state.news, newsCount: state.newsCount == null ? 5 : state.newsCount,
    historySessions: state.historySessions == null ? 60 : state.historySessions
  });
  const universe = { symbols: [SYMBOL], world: 'observed', lane: 'OBSERVED',
    sectors: [{ key: 'fx', label: 'Fixture sector', symbols: [SYMBOL] }] };
  const calls = [];
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const at = url.pathname;
    calls.push(at);
    const research = at.match(/^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news|expected-move))?$/);
    let out = null;   // {status, body}
    let body;
    if (at === '/api/config') body = { fixturesOnly: false, world: 'observed', marketLane: 'OBSERVED', scenarioMode: false };
    else if (at === '/api/status') body = { ok: true, status: 'READY', fixturesOnly: false };
    else if (at === '/api/world') body = { world: 'observed', revision: 1, epoch: 1 };
    else if (at === '/api/workspace') body = { rev: 1, updatedAt: '2026-07-25T12:00:00Z', supportedVersion: 1, world: 'observed', marketLane: 'OBSERVED', accountId: 'acct-1', context: null, transition: null, unreadable: null };
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
    else if (at === '/api/universe') body = universe;
    else if (at === '/api/strategies') body = { catalog: [] };
    else if (at === '/api/quotes') body = quotesBody(lanes, String(url.searchParams.get('symbols') || SYMBOL).split(',').map(s => s.trim().toUpperCase()));
    else if (research) {
      const lane = research[2] || 'research';
      if (lane === 'expected-move') body = { available: false, reason: 'No expected-move receipt in this fixture world.' };
      else out = lanes[lane === 'research' ? 'research' : lane];
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
    return b && b.getBoundingClientRect().height > 20;
  }, null, { timeout: 15000 });
  await page.waitForTimeout(900);
}

/** What each of the four lanes actually shows on Home. */
async function readLanes(page) {
  return page.evaluate(() => {
    const text = el => el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : null;
    const chainBand = document.getElementById('chainBand');
    const newsBand = document.getElementById('newsBand');
    const hero = chainBand && chainBand.querySelector('.authmarkethero');
    const heroNotice = chainBand && chainBand.querySelector('.authslotnotice');
    const histWrap = chainBand && chainBand.querySelector('[data-hist-host]');
    const histSvg = histWrap && histWrap.querySelector('[data-hist-svg]');
    const chainRows = chainBand ? chainBand.querySelectorAll('.authchainrow') : [];
    const chainSlice = chainBand && chainBand.querySelector('.authchainslice');
    const newsLinks = newsBand ? newsBand.querySelectorAll('#homeNewsList a, #homeNewsList .authlistrow') : [];
    return {
      bandText: text(chainBand).slice(0, 200),
      hero: hero ? text(hero) : null,
      heroNotice: heroNotice ? text(heroNotice) : null,
      histPresent: !!histWrap,
      histPaths: histSvg ? histSvg.querySelectorAll('path,rect,line').length : 0,
      histRead: text(histWrap && histWrap.querySelector('[data-hist-read]')),
      histUnavailable: text(histWrap && histWrap.querySelector('.histunavailable')),
      histPills: histWrap ? Array.from(histWrap.querySelectorAll('[data-hist-pill]')).map(b => b.textContent + (b.disabled ? '(off)' : '')) : [],
      chainRows: chainRows.length,
      chainText: chainSlice ? text(chainSlice).slice(0, 160) : null,
      chainReceipt: text(chainBand && chainBand.querySelector('.authreceipt')),
      newsCount: newsLinks.length,
      newsHint: text(newsBand && newsBand.querySelector('.hint')),
      newsText: text(newsBand).slice(0, 160)
    };
  });
}

const S = ['ready', 'stale', 'missing', 'error'];

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const server = http.createServer(servePublic);
  await new Promise((res, rej) => { server.once('error', rej); server.listen(0, '127.0.0.1', res); });
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await chromium.launch({ headless: true });

  const only = process.argv[2];
  const combos = [];
  if (only === 'diag') {
    combos.push({ quote: 'ready', history: 'ready', chain: 'ready', news: 'ready' });
  } else {
    for (const q of S) for (const h of S) for (const c of S) for (const n of S) combos.push({ quote: q, history: h, chain: c, news: n });
  }
  const results = [];
  for (const combo of combos) {
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(String(e && e.message || e)));
    await installWorld(page, Object.assign({ positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' }, combo));
    try {
      await bootHome(page, url);
      const read = await readLanes(page);
      results.push({ combo, read, errors });
      if (only === 'diag') {
        await page.screenshot({ path: path.join(OUT, 'diag.png'), fullPage: true });
        console.log(JSON.stringify(read, null, 2));
        console.log('errors', errors);
      }
    } catch (e) {
      results.push({ combo, read: null, errors: errors.concat([String(e.message)]) });
    }
    await context.close();
  }
  fs.writeFileSync(path.join(OUT, 'matrix.json'), JSON.stringify(results, null, 2));
  if (only !== 'diag') {
    const key = c => `${c.quote[0]}${c.history[0]}${c.chain[0]}${c.news[0]}`;
    console.log('combo | hero | hist | chainRows | news | errors');
    results.forEach(r => {
      const d = r.read || {};
      console.log([key(r.combo),
        d.hero ? 'HERO' : d.heroNotice ? 'notice' : 'NONE',
        d.histPresent ? `${d.histPaths}p` : 'ABSENT',
        d.chainRows,
        d.newsCount,
        r.errors.length].join(' | '));
    });
  }
  await browser.close();
  await new Promise(r => server.close(r));
})();
