'use strict';
/* Round 3: chain notice wording with expirations READY + chain 502/404. */
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const fixtures = require('./fixtures');
const market = require('./fixtures/market');
const golden = require('./fixtures/golden');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');
const SYMBOL = golden.SYMBOL;
function contentType(f) { return f.endsWith('.html') ? 'text/html; charset=utf-8' : f.endsWith('.js') ? 'text/javascript; charset=utf-8' : f.endsWith('.css') ? 'text/css; charset=utf-8' : 'application/octet-stream'; }
function servePublic(req, res) {
  const u = new URL(req.url, 'http://127.0.0.1');
  const pn = u.pathname === '/' ? '/index.html' : decodeURIComponent(u.pathname);
  const file = path.resolve(PUBLIC, `.${pn}`);
  if (file !== PUBLIC && !file.startsWith(`${PUBLIC}${path.sep}`)) return res.writeHead(403).end('x');
  fs.readFile(file, (e, b) => { if (e) return res.writeHead(404).end(e.message); res.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' }); res.end(b); });
}
function quotesBody(lanes, symbols) {
  const r = lanes.research.status === 200 ? lanes.research.body : null;
  return { marketLane: 'OBSERVED', quotes: symbols.map(symbol => {
    const priced = !!(r && r.displayPrice != null && symbol === SYMBOL), q = priced ? r.quote : null;
    return { symbol, priced, displayPrice: priced ? r.displayPrice : null, displayChangePct: priced ? r.displayChangePct : null,
      markBasis: priced ? 'MID' : 'UNAVAILABLE', priceIsPreviousClose: false,
      quoteUnavailableReason: priced ? null : `No usable price for ${symbol}.`,
      last: priced ? q.last : null, bid: priced ? q.bid : null, ask: priced ? q.ask : null,
      prevClose: priced ? q.prevClose : null, optionable: priced,
      freshness: priced ? r.freshness : 'UNAVAILABLE', source: priced ? q.source : null,
      evidence: priced ? { provenance: 'OBSERVED', age: r.freshness, source: q.source } : null,
      asOf: priced ? q.asOfEpochMs : null, refreshing: false, quote: priced ? q : null };
  }) };
}
async function install(page, state) {
  const world = fixtures.desk({ positions: 4, workingIdeas: 5 });
  const lanes = market.marketDocuments({ symbol: SYMBOL, quote: 'ready', history: 'ready',
    chain: state.chain, news: 'ready', expirations: state.expirations || 'ready' });
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), at = url.pathname;
    const research = at.match(/^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news|expected-move))?$/);
    let body, out = null;
    if (at === '/api/config') body = { fixturesOnly: false, world: 'observed', marketLane: 'OBSERVED', scenarioMode: false };
    else if (at === '/api/status') body = { ok: true, status: 'READY', fixturesOnly: false };
    else if (at === '/api/world') body = { world: 'observed', revision: 1, epoch: 1 };
    else if (at === '/api/workspace') body = { rev: 1, supportedVersion: 1, world: 'observed', marketLane: 'OBSERVED', accountId: 'acct-1', context: null };
    else if (at === '/api/account') body = { account: { id: 'acct-1', cashCents: 5000000, buyingPowerCents: 9700000 }, ledger: [] };
    else if (at === '/api/portfolio/summary') body = world.book.summary;
    else if (at === '/api/portfolio/heat') body = world.book.heat;
    else if (at === '/api/portfolio/greeks') body = world.book.greeks;
    else if (at === '/api/portfolio/book-risk') body = world.book.bookRisk;
    else if (at === '/api/portfolio/accounts') body = [{ id: 'acct-1', name: 'P' }];
    else if (at === '/api/positions') body = world.book.positionBook;
    else if (at === '/api/trades') body = world.book.tradePage;
    else if (at === '/api/plans') body = world.plans;
    else if (at === '/api/plans/portfolio') body = world.planPortfolio;
    else if (at === '/api/universe') body = { symbols: [SYMBOL], sectors: [] };
    else if (at === '/api/strategies') body = { catalog: [] };
    else if (at === '/api/quotes') body = quotesBody(lanes, String(url.searchParams.get('symbols') || SYMBOL).split(',').map(s => s.trim().toUpperCase()));
    else if (research) { const lane = research[2] || 'research'; if (lane === 'expected-move') body = { available: false }; else out = lanes[lane]; }
    else body = {};
    if (out) await route.fulfill({ status: out.status, contentType: 'application/json', body: JSON.stringify(out.body) });
    else await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
}
async function main() {
  const server = http.createServer(servePublic);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await chromium.launch({ headless: true });
  const out = {};
  for (const state of [{ chain: 'error', expirations: 'ready' }, { chain: 'missing', expirations: 'ready' }]) {
    const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await ctx.newPage();
    await install(page, state);
    await page.goto(url);
    await page.waitForTimeout(2800);
    out[state.chain] = await page.evaluate(() => ({
      notice: (document.querySelector('#chainBand .authslotnotice') || {}).textContent || null,
      retry: document.querySelectorAll('#chainBand .authchainslice button').length,
      band: document.getElementById('chainBand').textContent.replace(/\s+/g, ' ').slice(0, 220)
    }));
    await ctx.close();
  }
  await browser.close();
  await new Promise(r => server.close(r));
  console.log(JSON.stringify(out, null, 2));
}
main().catch(e => { console.error(e); process.exit(1); });
