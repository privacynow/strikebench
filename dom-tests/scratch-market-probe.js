'use strict';
/* SCRATCH — targeted probes for the market/history/chain/news surface. */

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const fixtures = require('./fixtures');
const market = require('./fixtures/market');
const wire = require('./fixtures/wire');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');
const OUT = path.resolve('/private/tmp/claude-501/-Users-tinker-output-optin/59dd524a-adcb-4f18-bffe-c79781e37c8f/scratchpad/market');
const SYMBOL = wire.GOLDEN_SYMBOL;      // GLDN
const SECOND = 'ZAA';

function contentType(f) {
  if (f.endsWith('.html')) return 'text/html; charset=utf-8';
  if (f.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (f.endsWith('.css')) return 'text/css; charset=utf-8';
  return 'application/octet-stream';
}
function servePublic(req, res) {
  const p = new URL(req.url, 'http://127.0.0.1').pathname;
  const file = path.resolve(PUBLIC, `.${p === '/' ? '/index.html' : decodeURIComponent(p)}`);
  if (file !== PUBLIC && !file.startsWith(`${PUBLIC}${path.sep}`)) { res.writeHead(403).end('x'); return; }
  fs.readFile(file, (e, b) => e ? res.writeHead(404).end(e.message)
    : (res.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' }), res.end(b)));
}

async function install(page, opts) {
  const o = Object.assign({ quote: 'ready', history: 'ready', chain: 'ready', news: 'ready',
    newsCount: 5, historySessions: 60, expirations: null, chainStrikes: null,
    trendSessions: null, symbols: [SYMBOL, SECOND] }, opts);
  const world = fixtures.desk({ positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' });
  const lanesFor = symbol => {
    const l = market.marketDocuments({ symbol, quote: o.quote, history: o.history, chain: o.chain,
      news: o.news, newsCount: o.newsCount, historySessions: o.historySessions,
      expirations: o.expirations });
    if (o.chainStrikes && l.chain.status === 200) {
      l.chain = market.chain(o.chain, { symbol, strikes: o.chainStrikes });
    }
    if (o.trendSessions != null && l.research.status === 200 && l.research.body.regime) {
      l.research.body.regime.trendSessions = o.trendSessions;
    }
    return l;
  };
  const lanes = { [SYMBOL]: lanesFor(SYMBOL), [SECOND]: lanesFor(SECOND) };
  const universe = { symbols: o.symbols, world: 'observed', lane: 'OBSERVED',
    active: { symbols: o.symbols }, scout: { symbols: o.symbols },
    sectors: [{ key: 'fx', label: 'Fixture sector', symbols: o.symbols }] };
  const workspace = { rev: 1, updatedAt: '2026-07-25T12:00:00Z', supportedVersion: 1,
    world: 'observed', marketLane: 'OBSERVED', accountId: 'acct-1',
    context: null, transition: null, unreadable: null };
  const patches = [];
  await page.route('**/api/**', async route => {
    const req = route.request();
    const url = new URL(req.url());
    const at = url.pathname;
    if (req.method() === 'PATCH' || (req.method() === 'POST' && at === '/api/workspace')) {
      const sent = JSON.parse(req.postData() || '{}');
      patches.push({ at, sent });
      workspace.rev += 1;
      workspace.context = Object.assign({}, workspace.context || {}, sent);
      await route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(Object.assign({}, workspace, { rev: workspace.rev })) });
      return;
    }
    const research = at.match(/^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news|expected-move))?$/);
    let out = null, body;
    if (at === '/api/config') body = { fixturesOnly: false, world: 'observed', marketLane: 'OBSERVED', scenarioMode: false };
    else if (at === '/api/status') body = { ok: true, status: 'READY', fixturesOnly: false };
    else if (at === '/api/world') body = { world: 'observed', revision: 1, epoch: 1 };
    else if (at === '/api/workspace') body = workspace;
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
    else if (at === '/api/quotes') {
      const syms = String(url.searchParams.get('symbols') || SYMBOL).split(',').map(s => s.trim().toUpperCase());
      body = { marketLane: 'OBSERVED', world: 'observed', quotes: syms.map(symbol => {
        const l = lanes[symbol];
        const r = l && l.research.status === 200 ? l.research.body : null;
        const priced = !!(r && r.displayPrice != null);
        return { symbol, priced, displayPrice: priced ? r.displayPrice : null,
          displayChangePct: priced ? r.displayChangePct : null, markBasis: priced ? 'MID' : null,
          priceIsPreviousClose: false, freshness: priced ? r.freshness : 'UNAVAILABLE',
          source: priced ? 'FIXTURE_EXECUTABLE_BOOK' : null,
          asOfEpochMs: priced ? wire.OBSERVED_AT_MS : null,
          quote: priced ? Object.assign({}, r.quote, { symbol }) : null,
          evidence: priced ? { provenance: 'OBSERVED', age: 'REALTIME', source: 'FIXTURE_EXECUTABLE_BOOK' } : null,
          unavailableReason: priced ? null : `No usable price for ${symbol}.` };
      }) };
    } else if (research) {
      const sym = decodeURIComponent(research[1]).toUpperCase();
      const lane = research[2] || 'research';
      const l = lanes[sym];
      if (!l) body = {};
      else if (lane === 'expected-move') body = { available: false, reason: 'No expected-move receipt in this fixture world.' };
      else out = l[lane];
    } else if (at.startsWith('/api/trades/')) {
      body = world.book.tradeDetails[decodeURIComponent(at.slice('/api/trades/'.length))] || { trade: null };
    } else body = {};
    if (out) await route.fulfill({ status: out.status, contentType: 'application/json', body: JSON.stringify(out.body) });
    else await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  return { patches, workspace };
}

async function boot(page, url) {
  await page.goto(url);
  await page.waitForFunction(() => window.DeskBackend != null && window.WORKSPACE != null);
  await page.waitForFunction(() => {
    const b = document.getElementById('chainBand');
    return b && b.getBoundingClientRect().height > 20;
  }, null, { timeout: 15000 });
  await page.waitForTimeout(900);
}

const probes = {};

probes.persistFocus = async (page, url) => {
  const w = await install(page, {});
  await boot(page, url);
  const before = await page.evaluate(() => window.HOME_AUTH_SYMBOL);
  await page.click(`[data-auth-market-symbol="${SECOND}"]`);
  await page.waitForTimeout(1200);
  const after = await page.evaluate(() => window.HOME_AUTH_SYMBOL);
  const patchedFields = w.patches.map(p => Object.keys(p.sent).join(','));
  const savedContext = JSON.parse(JSON.stringify(w.workspace.context || {}));
  await page.reload();
  await page.waitForFunction(() => window.DeskBackend != null && window.WORKSPACE != null);
  await page.waitForTimeout(2500);
  const afterReload = await page.evaluate(() => ({
    home: window.HOME_AUTH_SYMBOL,
    marketSymbol: window.WORKSPACE.marketSymbol,
    heroSym: (document.querySelector('.amh-symbol') || {}).textContent || null,
    newsHint: (document.querySelector('#newsBand .hint') || {}).textContent || null
  }));
  return { before, after, patchedFields, savedContext, afterReload,
    clientFields: await page.evaluate(() => window.WORKSPACE_CLIENT_FIELDS) };
};

probes.chainReasons = async (page, url) => {
  const out = {};
  for (const state of ['missing', 'error']) {
    const ctx = await page.context().newPage();
    ctx.on('pageerror', () => {});
    await install(ctx, { chain: state, expirations: 'ready' });
    await boot(ctx, url);
    out[state] = await ctx.evaluate(() => {
      const t = el => el ? el.textContent.replace(/\s+/g, ' ').trim() : null;
      return { notice: t(document.querySelector('#chainBand .authslotnotice')),
        headOK: !!document.querySelector('#chainBand .authchainhead') };
    });
    await ctx.close();
  }
  return out;
};

probes.trendLabel = async (page, url) => {
  const out = {};
  for (const n of [20, 60, 63, 252]) {
    const ctx = await page.context().newPage();
    await install(ctx, { trendSessions: n });
    await boot(ctx, url);
    out[n] = await ctx.evaluate(() => {
      const el = document.querySelector('.amh-facts');
      return el ? el.textContent.replace(/\s+/g, ' ').trim() : null;
    });
    await ctx.close();
  }
  return out;
};

probes.pills = async (page, url) => {
  await install(page, { historySessions: 60 });
  await boot(page, url);
  const out = {};
  for (const key of ['1d', '1w', '1m', '3m', '6m', 'ytd', '1y', '5y', 'all']) {
    const btn = await page.$(`#chainBand [data-hist-pill="${key}"]`);
    if (!btn) { out[key] = 'no pill'; continue; }
    const disabled = await btn.evaluate(b => b.disabled);
    if (disabled) { out[key] = `DISABLED: ${await btn.evaluate(b => b.title)}`; continue; }
    await btn.click();
    await page.waitForTimeout(250);
    out[key] = await page.evaluate(() => {
      const wrap = document.querySelector('#chainBand [data-hist-host]');
      const svg = wrap && wrap.querySelector('[data-hist-svg]');
      return {
        read: (wrap.querySelector('[data-hist-read]') || {}).textContent,
        marks: svg ? svg.querySelectorAll('path[d],rect.candle,g rect').length : 0,
        svgChildren: svg ? svg.innerHTML.length : 0
      };
    });
  }
  return out;
};

probes.news20 = async (page, url) => {
  await install(page, { newsCount: 20 });
  await boot(page, url);
  return page.evaluate(() => {
    const list = document.getElementById('homeNewsList');
    const meta = document.querySelector('#newsBand .overflowmeta');
    const read = meta && meta.querySelector('.overflowread');
    const rows = list ? list.querySelectorAll('a,[data-scroll-row]') : [];
    const box = list ? list.getBoundingClientRect() : null;
    return {
      hint: (document.querySelector('#newsBand .hint') || {}).textContent,
      rows: rows.length,
      firstHref: rows[0] ? rows[0].getAttribute('href') : null,
      overflowText: read ? read.textContent : null,
      overflowTag: read ? read.tagName : null,
      overflowClickable: read ? (read.onclick != null || read.tagName === 'BUTTON' || read.getAttribute('role') === 'button') : null,
      listScrolls: list ? { clientH: Math.round(list.clientHeight), scrollH: Math.round(list.scrollHeight), overflowY: getComputedStyle(list).overflowY } : null,
      boxH: box ? Math.round(box.height) : null
    };
  });
};

probes.chainWide = async (page, url) => {
  const strikes = [];
  for (let k = 220; k <= 280; k += 2.5) strikes.push(k);
  await install(page, { chainStrikes: strikes });
  await boot(page, url);
  return page.evaluate(() => {
    const rows = document.querySelectorAll('#chainBand .authchainrow');
    const first = rows[0];
    return {
      rendered: rows.length,
      receipt: (document.querySelector('#chainBand .authreceipt') || {}).textContent,
      rowTag: first ? first.tagName : null,
      rowTitle: first ? first.getAttribute('title') : null,
      rowTabIndex: first ? first.tabIndex : null,
      rowRole: first ? first.getAttribute('role') : null,
      anyMore: /\+\s*\d+\s*more/i.test(document.getElementById('chainBand').textContent)
    };
  });
};

probes.chainClick = async (page, url) => {
  await install(page, {});
  await boot(page, url);
  const before = await page.evaluate(() => ({ level: window.state && window.state.level, sym: window.HOME_AUTH_SYMBOL }));
  const row = await page.$('#chainBand .authchainrow');
  if (!row) return { error: 'no row' };
  await row.click();
  await page.waitForTimeout(600);
  const after = await page.evaluate(() => ({
    level: window.state && window.state.level,
    decideOpen: !!document.querySelector('#decideStage, .decstage'),
    bodyClass: document.body.className,
    location: location.hash
  }));
  return { before, after };
};

probes.newsOverflowMobile = async (page, url) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await install(page, { newsCount: 20 });
  await boot(page, url);
  return page.evaluate(() => {
    const list = document.getElementById('homeNewsList');
    const read = document.querySelector('#newsBand .overflowread');
    return {
      rows: list ? list.querySelectorAll('a,[data-scroll-row]').length : 0,
      clientH: list ? Math.round(list.clientHeight) : null,
      scrollH: list ? Math.round(list.scrollHeight) : null,
      overflowY: list ? getComputedStyle(list).overflowY : null,
      overflowRead: read ? read.textContent : null,
      readTag: read ? read.tagName : null,
      metaVisible: (function () {
        const m = document.querySelector('#newsBand .overflowmeta');
        if (!m) return null;
        const s = getComputedStyle(m);
        return { display: s.display, box: Math.round(m.getBoundingClientRect().height) };
      })(),
      lastRowVisible: (function () {
        const list = document.getElementById('homeNewsList');
        const rows = list.querySelectorAll('a,[data-scroll-row]');
        const last = rows[rows.length - 1];
        const lb = last.getBoundingClientRect(), pb = list.getBoundingClientRect();
        return { lastTop: Math.round(lb.top), listBottom: Math.round(pb.bottom),
          maskImage: getComputedStyle(list).maskImage.slice(0, 60) };
      })()
    };
  });
};

probes.staleQuote = async (page, url) => {
  await install(page, { quote: 'stale' });
  await boot(page, url);
  return page.evaluate(() => {
    const t = id => { const e = document.getElementById(id); return e ? e.textContent.replace(/\s+/g, ' ').trim() : null; };
    const hero = document.querySelector('.authmarkethero');
    const row = document.querySelector(`[data-auth-market-row-symbol]`);
    const d = window.DeskBackend.state().book.data;
    const ctxRow = (d.homeContext.rows || []).find(r => r.symbol === window.HOME_AUTH_SYMBOL);
    return {
      hero: hero ? hero.textContent.replace(/\s+/g, ' ').trim() : null,
      heroHasStale: hero ? /stale/i.test(hero.textContent) : null,
      watchRow: row ? row.textContent.replace(/\s+/g, ' ').trim() : null,
      chainBandHasStale: /stale/i.test(t('chainBand') || ''),
      sectorBandHasStale: /stale/i.test(t('sectorBand') || ''),
      quoteFreshnessOnWire: ctxRow && ctxRow.research && ctxRow.research.freshness,
      quoteAsOf: ctxRow && ctxRow.research && ctxRow.research.quote && ctxRow.research.quote.asOfEpochMs,
      quoteSource: ctxRow && ctxRow.research && ctxRow.research.quote && ctxRow.research.quote.source
    };
  });
};

probes.expectedMove = async (page, url) => {
  const seen = [];
  page.on('request', r => { if (/expected-move/.test(r.url())) seen.push(r.url()); });
  await install(page, {});
  await boot(page, url);
  const chipState = await page.evaluate(() => {
    const chip = document.querySelector('#chainBand [data-hist-chip="cone"]');
    const overlay = window.histOverlayFor('home', window.HOME_AUTH_SYMBOL);
    return { chipClass: chip && chip.className, chipTitle: chip && chip.title,
      coneOn: window.MKT_CHART.ov.cone, overlayExpiry: overlay && overlay.expiry,
      chainExpiration: (function () {
        const d = window.DeskBackend.state().book.data;
        const row = (d.homeContext.rows || []).find(r => r.symbol === window.HOME_AUTH_SYMBOL);
        return row && row.chain && row.chain.expiration;
      })() };
  });
  await page.waitForTimeout(500);
  return { expectedMoveRequests: seen, chipState };
};

probes.shots = async (page, url) => {
  const shot = async (name, el) => {
    const node = await page.$(el);
    if (node) await node.screenshot({ path: path.join(OUT, name + '.png') });
    else await page.screenshot({ path: path.join(OUT, name + '.png') });
  };
  await install(page, { quote: 'error' });
  await boot(page, url);
  await shot('quote-error-band', '#chainBand');
  const ctx2 = await page.context().newPage();
  await install(ctx2, { historySessions: 60 });
  await boot(ctx2, url);
  await ctx2.click('#chainBand [data-hist-pill="1y"]');
  await ctx2.waitForTimeout(400);
  const n2 = await ctx2.$('#chainBand .authhomehistory');
  if (n2) await n2.screenshot({ path: path.join(OUT, 'pill-1y-60-sessions.png') });
  await ctx2.click('#chainBand [data-hist-pill="1d"]');
  await ctx2.waitForTimeout(400);
  const n3 = await ctx2.$('#chainBand .authhomehistory');
  if (n3) await n3.screenshot({ path: path.join(OUT, 'pill-1d.png') });
  await ctx2.close();
  const ctx3 = await page.context().newPage();
  await install(ctx3, { history: 'missing' });
  await boot(ctx3, url);
  const n4 = await ctx3.$('#chainBand');
  if (n4) await n4.screenshot({ path: path.join(OUT, 'history-missing-band.png') });
  await ctx3.close();
  return { wrote: fs.readdirSync(OUT).filter(f => f.endsWith('.png')) };
};

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const server = http.createServer(servePublic);
  await new Promise((r, j) => { server.once('error', j); server.listen(0, '127.0.0.1', r); });
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await chromium.launch({ headless: true });
  const which = process.argv.slice(2);
  const results = {};
  for (const name of Object.keys(probes)) {
    if (which.length && !which.includes(name)) continue;
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await context.newPage();
    const errs = [];
    page.on('pageerror', e => errs.push(String(e.message)));
    try { results[name] = await probes[name](page, url); }
    catch (e) { results[name] = { FAILED: e.message }; }
    if (errs.length) results[name + ':pageerrors'] = errs;
    await context.close();
  }
  console.log(JSON.stringify(results, null, 2));
  fs.writeFileSync(path.join(OUT, 'probes.json'), JSON.stringify(results, null, 2));
  await browser.close();
  await new Promise(r => server.close(r));
})();
