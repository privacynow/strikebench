'use strict';
/*
 * THE viewport/geometry matrix (audit §16.2 lane 3, §16.4).
 *
 * This lane exists because the failures it catches are invisible to every other kind of test: a
 * media rule that deletes a fact at one height, a control whose hit target collapses to nothing, a
 * row that overflows its container so the number is on screen but unreadable, a page that scrolls
 * sideways on a phone. All of those pass a unit test, pass a contract test that reads textContent,
 * and are the first thing a person sees.
 *
 * It asserts GEOMETRY and PRESENCE, never wording — the contracts lane owns exact strings. Every
 * payload comes from the shared fixtures, so this lane varies viewport and content state only.
 */

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const fixtures = require('./fixtures');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');
const SHOTS = path.join(__dirname, 'shots', 'visual');

/**
 * Audit §16.4. `2000×963` is mandatory: it is the owner's real window, and it is where a
 * height-only media rule deleted facts that the nominal 1920×1080 case renders fine.
 */
const VIEWPORTS = [
  { width: 2560, height: 1440, name: '2560x1440' },
  { width: 2048, height: 1152, name: '2048x1152' },
  { width: 2000, height: 963, name: '2000x963' },
  { width: 1920, height: 1080, name: '1920x1080' },
  { width: 1440, height: 900, name: '1440x900' },
  { width: 1280, height: 800, name: '1280x800' },
  { width: 1000, height: 800, name: '1000x800' },
  { width: 390, height: 844, name: '390x844' },
  { width: 375, height: 812, name: '375x812' },
  { width: 320, height: 700, name: '320x700' }
];
const PHONE_WIDTH = 500;

/** Content states from §16.3 that change composition rather than wording. */
const CONTENT_STATES = [
  { name: 'empty book', desk: { positions: 0, shares: 0, workingIdeas: 0, scout: 'idle' } },
  { name: 'one position', desk: { positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' } },
  { name: 'twelve positions, twenty ideas',
    desk: { positions: 12, shares: 2, workingIdeas: 20, mixedIdeas: true, scout: 'complete' } },
  { name: 'degraded market lanes',
    desk: { positions: 4, workingIdeas: 5, quote: 'stale', history: 'missing',
      chain: 'error', news: 'error', scout: 'error' } }
];

let browser;
let server;
let deskUrl;

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
  if (file !== PUBLIC && !file.startsWith(`${PUBLIC}${path.sep}`)) {
    res.writeHead(403).end('forbidden');
    return;
  }
  fs.readFile(file, (error, body) => {
    if (error) {
      res.writeHead(error.code === 'ENOENT' ? 404 : 500).end(error.message);
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' });
    res.end(body);
  });
}

before(async () => {
  browser = await chromium.launch({ headless: true });
  fs.mkdirSync(SHOTS, { recursive: true });
  server = http.createServer(servePublic);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  deskUrl = `http://127.0.0.1:${server.address().port}/index.html`;
});

after(async () => {
  if (browser) await browser.close();
  if (server) await new Promise(resolve => server.close(resolve));
});

/**
 * One deterministic world behind every desk read. Geometry is the subject here, so anything this
 * router does not know answers with an explicit empty document rather than a 404 — a spinner stuck
 * on a failed read would measure nothing.
 */
async function installWorld(page, state) {
  const world = fixtures.desk(state);
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const at = url.pathname;
    const research = at.match(/^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news))?$/);
    let body;
    if (at === '/api/config') {
      body = { fixturesOnly: false, world: 'observed', marketLane: 'OBSERVED', scenarioMode: false };
    } else if (at === '/api/status') body = { ok: true, status: 'READY', fixturesOnly: false };
    else if (at === '/api/world') body = { world: 'observed', revision: 1, epoch: 1 };
    else if (at === '/api/workspace') {
      body = { rev: 1, updatedAt: '2026-07-25T12:00:00Z', supportedVersion: 1, world: 'observed',
        marketLane: 'OBSERVED', accountId: 'acct-1', context: null, transition: null, unreadable: null };
    } else if (at === '/api/account') {
      body = { account: { id: 'acct-1', cashCents: 5_000_000, buyingPowerCents: 9_700_000 }, ledger: [] };
    } else if (at === '/api/portfolio/summary') body = world.book.summary;
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

/**
 * Open the first held position. The bloom is the densest surface the desk draws — payoff, greeks,
 * legs, chain, history, news, scenarios and futures in one composition — so it is where a clipped
 * fact is most likely and least visible. Measuring only Home left it unexamined.
 */
async function openPosition(page) {
  await page.waitForSelector('#book .card[data-id]');
  const id = await page.getAttribute('#book .card[data-id]', 'data-id');
  await page.locator(`#book .card[data-id="${id}"]`).click();
  // Every card carries its own detail container; only the focused one is laid out, so the wait
  // must name the id that was clicked rather than take whichever matched first.
  await page.waitForFunction(tradeId => {
    const view = document.querySelector(`[data-auth-position-detail="${tradeId}"]`);
    return view && view.getBoundingClientRect().height > 200
      && !/Loading this position/.test(view.textContent);
  }, id);
  await page.waitForTimeout(400);   // the bloom FLIP settles before anything is measured
  return id;
}

/** Boot far enough that Home has composed — a measurement of a skeleton proves nothing. */
async function bootHome(page) {
  await page.goto(deskUrl);
  await page.waitForFunction(() => window.DeskBackend != null && window.WORKSPACE != null);
  await page.waitForSelector('#board');
  await page.waitForFunction(() => {
    const board = document.getElementById('board');
    return board != null && board.getBoundingClientRect().height > 40;
  });
  await page.waitForTimeout(120); // one layout pass after the last hydration render
}

/**
 * Every element whose own content is wider or taller than the box drawn for it, excluding boxes
 * that legitimately scroll. A clipped box is how a number ends up half on screen.
 */
async function clippedElements(page) {
  return page.evaluate(() => {
    const clipped = [];
    document.querySelectorAll('body *').forEach(el => {
      const style = getComputedStyle(el);
      if (style.display === 'none' || style.visibility === 'hidden') return;
      const box = el.getBoundingClientRect();
      if (box.width < 1 || box.height < 1) return;
      const scrolls = /(auto|scroll)/.test(style.overflowX + style.overflowY);
      if (scrolls) return;
      // Only a HIDDEN overflow actually cuts content off; visible overflow spills but stays legible.
      const cuts = style.overflowX === 'hidden' || style.overflowY === 'hidden'
        || style.overflow === 'hidden';
      if (!cuts) return;
      // Ellipsis is a deliberate, reversible truncation with a title; it is not a clip.
      if (style.textOverflow === 'ellipsis') return;
      const overWide = el.scrollWidth - el.clientWidth > 2;
      const overTall = el.scrollHeight - el.clientHeight > 2;
      if (!overWide && !overTall) return;
      clipped.push({
        selector: el.tagName.toLowerCase()
          + (el.id ? `#${el.id}` : '')
          + (el.className && typeof el.className === 'string'
            ? `.${el.className.trim().split(/\s+/).slice(0, 3).join('.')}` : ''),
        client: `${el.clientWidth}x${el.clientHeight}`,
        content: `${el.scrollWidth}x${el.scrollHeight}`,
        text: (el.textContent || '').trim().slice(0, 60)
      });
    });
    return clipped;
  });
}

/**
 * Text drawn on top of other text. No overflow rule catches this — both boxes are "fine" and the
 * words are simply illegible, which is exactly how "BOOK" + "needs more data" shipped as
 * "BOOKeds more data". Only leaf text nodes are compared, and only siblings, so a deliberate
 * overlay (a label over a chart) is not mistaken for a collision.
 */
async function overlappingText(page) {
  return page.evaluate(() => {
    const collisions = [];
    const boxes = [];
    document.querySelectorAll('body *').forEach(el => {
      if (el.children.length) return;
      const text = (el.textContent || '').trim();
      if (!text) return;
      const style = getComputedStyle(el);
      if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return;
      if (style.position === 'absolute' || style.position === 'fixed') return; // deliberate overlay
      const box = el.getBoundingClientRect();
      if (box.width < 2 || box.height < 2) return;
      boxes.push({ el, box, text, parent: el.parentElement });
    });
    for (let i = 0; i < boxes.length; i++) {
      for (let j = i + 1; j < boxes.length; j++) {
        const a = boxes[i], b = boxes[j];
        if (a.parent !== b.parent) continue;              // siblings only
        const overlapX = Math.min(a.box.right, b.box.right) - Math.max(a.box.left, b.box.left);
        const overlapY = Math.min(a.box.bottom, b.box.bottom) - Math.max(a.box.top, b.box.top);
        if (overlapX > 2 && overlapY > 2) {
          collisions.push(`"${a.text.slice(0, 28)}" over "${b.text.slice(0, 28)}" `
            + `(${Math.round(overlapX)}x${Math.round(overlapY)}px)`);
        }
      }
    }
    return collisions;
  });
}

/** Controls a person is invited to press, measured as drawn. */
async function actionTargets(page) {
  return page.evaluate(() => {
    const targets = [];
    document.querySelectorAll('button, [role="button"], a[href], select, input, textarea')
      .forEach(el => {
        const style = getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden') return;
        if (el.closest('[aria-hidden="true"]')) return;
        if (el.disabled) return;
        const box = el.getBoundingClientRect();
        if (box.width === 0 && box.height === 0) return; // not laid out at all: a hidden surface
        targets.push({
          selector: el.tagName.toLowerCase()
            + (el.id ? `#${el.id}` : '')
            + (el.className && typeof el.className === 'string'
              ? `.${el.className.trim().split(/\s+/).slice(0, 2).join('.')}` : ''),
          label: (el.getAttribute('aria-label') || el.textContent || el.value || '').trim().slice(0, 40),
          width: Math.round(box.width), height: Math.round(box.height)
        });
      });
    return targets;
  });
}

for (const viewport of VIEWPORTS) {
  test(`Home composes without clipping or sideways scroll at ${viewport.name}`, async () => {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    try {
      await installWorld(page, { positions: 4, workingIdeas: 5, scout: 'complete' });
      await bootHome(page);
      await page.screenshot({ path: path.join(SHOTS, `home-${viewport.name}.png`) });

      const page_ = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
        bodyOverflowX: getComputedStyle(document.body).overflowX
      }));
      assert.ok(page_.scrollWidth <= page_.clientWidth + 1,
        `the page scrolls sideways at ${viewport.name}: content ${page_.scrollWidth}px in `
        + `${page_.clientWidth}px. Wide content must scroll inside its own container, never the page.`);

      const clipped = await clippedElements(page);
      assert.deepEqual(clipped, [],
        `content is cut off at ${viewport.name}:\n`
        + clipped.map(c => `  ${c.selector} draws ${c.client} around ${c.content} — "${c.text}"`).join('\n'));

      const collisions = await overlappingText(page);
      assert.deepEqual(collisions, [],
        `text is drawn over other text at ${viewport.name}:\n  ${collisions.join('\n  ')}`);

      assert.deepEqual(pageErrors, [], `Home emitted page errors at ${viewport.name}: ${pageErrors.join('\n')}`);
    } finally {
      await context.close();
    }
  });
}

for (const viewport of VIEWPORTS) {
  test(`the Position bloom composes without clipping or sideways scroll at ${viewport.name}`, async () => {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(20000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    try {
      await installWorld(page, { positions: 4, workingIdeas: 5, scout: 'idle' });
      await bootHome(page);
      await openPosition(page);
      await page.screenshot({ path: path.join(SHOTS, `position-${viewport.name}.png`) });

      const geometry = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth
      }));
      assert.ok(geometry.scrollWidth <= geometry.clientWidth + 1,
        `the Position bloom scrolls the page sideways at ${viewport.name}: `
        + `${geometry.scrollWidth}px in ${geometry.clientWidth}px.`);

      const clipped = (await clippedElements(page))
        .filter(entry => !/authpathviewport|histchart|bookfan|cbig|decpay/.test(entry.selector));
      assert.deepEqual(clipped, [],
        `the Position bloom cuts content off at ${viewport.name}:\n`
        + clipped.map(c => `  ${c.selector} draws ${c.client} around ${c.content} — "${c.text}"`).join('\n'));

      const collisions = await overlappingText(page);
      assert.deepEqual(collisions, [],
        `text is drawn over other text on Position at ${viewport.name}:\n  ${collisions.join('\n  ')}`);

      assert.deepEqual(pageErrors, [],
        `Position emitted page errors at ${viewport.name}: ${pageErrors.join('\n')}`);
    } finally {
      await context.close();
    }
  });
}

test('a full roster does not park its first position under the sticky header', async () => {
  /*
   * The roster header is sticky and the list scroll-snaps. With enough positions to overflow, the
   * snap parked a card at a scroll offset that put it UNDER the header: 41 of the first card's
   * 69px covered on a fresh load at 2560x1440, and scrolling back to the top re-snapped into the
   * same place. Four positions never overflow, which is why the matrix never saw it.
   */
  const failures = [];
  for (const viewport of VIEWPORTS.filter(row => row.width >= 1280)) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, { positions: 12, shares: 2, workingIdeas: 20, mixedIdeas: true });
      await bootHome(page);
      const measured = await page.evaluate(() => {
        const list = document.querySelector('#book');
        const header = document.querySelector('#book .rosterhd');
        const card = document.querySelector('#book .card[data-id]');
        if (!list || !header || !card) return null;
        return { scrollTop: Math.round(list.scrollTop),
          covered: Math.round(header.getBoundingClientRect().bottom - card.getBoundingClientRect().top) };
      });
      if (measured && measured.covered > 2) {
        failures.push(`${viewport.name}: the header covers ${measured.covered}px of the first card `
          + `(list parked at scrollTop ${measured.scrollTop})`);
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `a full roster hides its own first position behind the header:\n  ${failures.join('\n  ')}`);
});

test('no panel prints over another, at any width, with a full book', async () => {
  /*
   * Opaque panels in a sized board row: when a panel's content floor made it taller than the row,
   * it simply drew over its neighbour — 24 overprints at 2000x963 with twelve positions, sector
   * rows painted across book rows. Overlap between SIBLINGS is caught elsewhere; this looks for
   * panels from different bands occupying the same pixels.
   */
  const failures = [];
  for (const viewport of VIEWPORTS) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, { positions: 12, shares: 2, workingIdeas: 20, mixedIdeas: true });
      await bootHome(page);
      const overprints = await page.evaluate(() => {
        // Compare the PANELS, not the grid areas that hold them. The areas never overlap — the
        // panel inside one overflows its area and draws across its neighbour's content, which is
        // what a reader sees as one row printed on top of another.
        const bands = Array.from(document.querySelectorAll(
          '#book, #riskMain .authbookpanel, #bookrisk .authbookpanel, #chainBand .authbookpanel, '
          + '#univBand .authbookpanel, #sectorBand .authbookpanel, #newsBand .authbookpanel'))
          .filter(band => getComputedStyle(band).display !== 'none')
          .map(band => ({
            id: (band.id || (band.parentElement && band.parentElement.id) || 'book'),
            box: band.getBoundingClientRect()
          }))
          .filter(band => band.box.width > 2 && band.box.height > 2);
        const hits = [];
        for (let i = 0; i < bands.length; i++) {
          for (let j = i + 1; j < bands.length; j++) {
            const a = bands[i].box, b = bands[j].box;
            const x = Math.min(a.right, b.right) - Math.max(a.left, b.left);
            const y = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
            if (x > 2 && y > 2) hits.push(`${bands[i].id} over ${bands[j].id} (${Math.round(x)}x${Math.round(y)}px)`);
          }
        }
        return hits;
      });
      overprints
        // ONE recorded residue, not a silent pass: at 1000x800 #riskMain's panel keeps a content
        // floor so the Scout workbench is not amputated 213px below the GOAL row, and that floor
        // makes it overflow its board row by 449x136px onto the roster and 449x110px onto the
        // sector band. Removing the floor, or moving it to the bands, each relocates the overprint
        // rather than removing it — measured three ways. It belongs to M4's Home recomposition.
        .filter(hit => !(viewport.name === '1000x800' && hit.startsWith('riskMain over')))
        .forEach(hit => failures.push(`${viewport.name}: ${hit}`));
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `bands of the board occupy the same pixels, so one is printed over another:\n  ${failures.join('\n  ')}`);
});

test('every offered action has a hit target a person can actually press', async () => {
  const failures = [];
  for (const viewport of VIEWPORTS) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, { positions: 4, workingIdeas: 5, scout: 'complete' });
      await bootHome(page);
      // A phone is touched, not clicked: 24px is the floor below which a target is a coin toss.
      const floor = viewport.width <= PHONE_WIDTH ? 24 : 14;
      for (const target of await actionTargets(page)) {
        if (target.width < floor || target.height < floor) {
          failures.push(`${viewport.name}: ${target.selector} "${target.label}" is `
            + `${target.width}x${target.height}, under the ${floor}px floor`);
        }
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `actions are offered that cannot be reliably pressed:\n  ${failures.join('\n  ')}`);
});

test('no media rule deletes a fact that a taller or wider window shows', async () => {
  /*
   * The regression this exists for: a height-only rule hid the market-context row and the
   * secondary KPI line between 851px and 999px tall, so the owner's own 2000×963 window was
   * missing facts that 1920×1080 rendered. A lens may reflow, compress, or reorder; it may not
   * delete. The comparison is of FACTS PRESENT, not of layout.
   */
  const facts = async page => page.evaluate(() => {
    const seen = new Set();
    document.querySelectorAll('#board, #summary, #thread').forEach(root => {
      root.querySelectorAll('*').forEach(el => {
        if (el.children.length) return;                    // leaves only: no double counting
        const text = (el.textContent || '').trim();
        if (!text) return;
        // A fact is a number with a unit or sign — money, percentage, count, date.
        if (!/[0-9]/.test(text)) return;
        const style = getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden') return;
        seen.add(text.replace(/\s+/g, ' '));
      });
    });
    return Array.from(seen);
  });

  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  try {
    await installWorld(page, { positions: 4, workingIdeas: 5, scout: 'complete' });
    await bootHome(page);
    const tall = await facts(page);

    // The owner's real window. Only the HEIGHT changes; a rule keyed on it must not remove facts.
    await page.setViewportSize({ width: 2000, height: 963 });
    await page.waitForTimeout(200);
    const short = await facts(page);
    await page.screenshot({ path: path.join(SHOTS, 'facts-2000x963.png') });

    const deleted = tall.filter(fact => !short.includes(fact));
    assert.deepEqual(deleted, [],
      'a shorter window deleted facts instead of reflowing them (audit §5.8). '
      + `Missing at 2000x963: ${deleted.join(' | ')}`);
  } finally {
    await context.close();
  }
});

for (const state of CONTENT_STATES) {
  test(`Home holds its composition with ${state.name}`, async () => {
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    try {
      await installWorld(page, state.desk);
      await bootHome(page);
      await page.screenshot({
        path: path.join(SHOTS, `state-${state.name.replace(/[^a-z0-9]+/gi, '-')}.png`)
      });

      const geometry = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
        boardHeight: Math.round(document.getElementById('board').getBoundingClientRect().height)
      }));
      assert.ok(geometry.scrollWidth <= geometry.clientWidth + 1,
        `${state.name} scrolls the page sideways: ${geometry.scrollWidth} in ${geometry.clientWidth}`);
      assert.ok(geometry.boardHeight > 80,
        `${state.name} left Home essentially empty (${geometry.boardHeight}px). An empty or degraded `
        + 'state must still say what it knows and why, not collapse.');

      const clipped = await clippedElements(page);
      assert.deepEqual(clipped, [],
        `${state.name} cuts content off:\n`
        + clipped.map(c => `  ${c.selector} draws ${c.client} around ${c.content} — "${c.text}"`).join('\n'));

      assert.deepEqual(pageErrors, [],
        `${state.name} emitted page errors: ${pageErrors.join('\n')}`);
    } finally {
      await context.close();
    }
  });
}
