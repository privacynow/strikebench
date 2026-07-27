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
const { launchChromium } = require('./browser');

const fixtures = require('./fixtures');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');
const SHOTS = process.env.STRIKEBENCH_VISUAL_SHOTS
  ? path.resolve(process.env.STRIKEBENCH_VISUAL_SHOTS)
  : path.join(__dirname, 'shots', 'visual');

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
const DOCUMENT_LAYOUT_WIDTH = 1500;

/** Content states from §16.3 that change composition rather than wording. */
const CONTENT_STATES = [
  { name: 'empty book', desk: { positions: 0, shares: 0, workingIdeas: 0, scout: 'idle' } },
  { name: 'one position, no ideas',
    desk: { positions: 1, shares: 0, workingIdeas: 0, scout: 'idle' } },
  { name: 'one position', desk: { positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' } },
  { name: 'populated book',
    desk: { positions: 4, shares: 1, workingIdeas: 5, scout: 'complete' } },
  { name: 'twelve positions, twenty ideas',
    desk: { positions: 12, shares: 2, workingIdeas: 20, mixedIdeas: true, scout: 'complete' } },
  { name: 'degraded market lanes',
    desk: { positions: 4, workingIdeas: 5, quote: 'stale', history: 'missing',
      chain: 'error', news: 'error', scout: 'failed' } }
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
  browser = await launchChromium();
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
function applyIdeaMarketState(idea, marketState) {
  if (!idea || !marketState) return;
  const symbol = fixtures.wire.GOLDEN_SYMBOL;
  if (marketState.quote) {
    idea.market.research = fixtures.market.researchDetail(marketState.quote, { symbol });
  }
  if (marketState.history) {
    idea.market.history = fixtures.market.history(marketState.history,
      { symbol, range: 'max', sessions: 60 });
  }
  if (marketState.chain) {
    idea.market.chain = fixtures.market.chain(marketState.chain, {
      symbol,
      expiration: fixtures.wire.FAR_EXPIRATION,
      strikes: [235, 240, 245, 250, 255, 260, 265, 270, 275]
    });
    idea.market.expirations = fixtures.market.expirations(marketState.chain, { symbol });
    idea.market.expectedMove = fixtures.market.expectedMove(marketState.chain, {
      symbol, expiration: fixtures.wire.FAR_EXPIRATION
    });
  }
  if (marketState.news) {
    idea.market.news = fixtures.market.news(marketState.news,
      { symbol, count: marketState.newsCount || 20 });
  }
}

async function installWorld(page, state) {
  const world = fixtures.desk(state);
  page.__strikebenchVisualWorld = world;
  const idea = state && state.idea ? fixtures.newIdea.documents(state.idea) : null;
  if (idea && state.idea && state.idea.market) {
    applyIdeaMarketState(idea, state.idea.market);
  }
  let ideaPlanVersion = idea ? idea.plan.version : 0;
  let ideaSelected = null;
  let strategyRan = false;
  let workspaceRev = 1;
  let workspaceContext = {
    version: 1,
    world: 'observed',
    datasetId: idea ? fixtures.newIdea.DATASET_ID : null,
    marketLane: 'OBSERVED',
    accountId: fixtures.wire.ACCOUNT_ID,
    generation: 1,
    subject: 'BOOK',
    symbol: null,
    positionId: null,
    ideaId: null,
    evaluationId: null,
    routeState: 'book'
  };
  /* A streamed/completed/error Scout state can only exist after the scan declarations were
     accepted. Keep the visual fixture internally coherent: result rows must not sit underneath
     an undeclared-control warning that could never accompany them in the product. Idle fixtures
     intentionally retain null declarations so that honest gating remains covered. */
  if (state && state.scout && state.scout !== 'idle') {
    Object.assign(workspaceContext, {
      scopeType: 'BROAD_MARKET',
      sectorKey: null,
      goal: 'INCOME',
      view: 'Neutral',
      horizonDays: 45,
      riskPosture: 'Balanced'
    });
  }
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const at = url.pathname;
    const method = route.request().method();
    let requestBody = null;
    if (method !== 'GET' && method !== 'HEAD') {
      try { requestBody = route.request().postDataJSON(); } catch (ignored) { requestBody = {}; }
    }
    const research = at.match(
      /^\/api\/research\/([^/]+)(?:\/(history|expected-move|expirations|chain|news))?$/);
    const ideaPlanPath = idea
      ? `/api/plans/${encodeURIComponent(fixtures.newIdea.PLAN_ID)}` : null;
    const currentIdeaPlan = () => idea
      ? fixtures.newIdea.plan(ideaPlanVersion) : null;
    const workspaceReceipt = () => ({
      rev: workspaceRev,
      updatedAt: '2026-07-25T12:00:00Z',
      supportedVersion: 1,
      world: 'observed',
      marketLane: 'OBSERVED',
      accountId: fixtures.wire.ACCOUNT_ID,
      context: workspaceContext,
      transition: null,
      unreadable: null
    });
    let body;
    let status = 200;
    if (at === '/api/config') {
      body = { fixturesOnly: false, world: 'observed', activeDataset: idea
        ? fixtures.newIdea.DATASET_ID : null, marketLane: 'OBSERVED', scenarioMode: false };
    } else if (at === '/api/status') body = { ok: true, status: 'READY', fixturesOnly: false };
    else if (at === '/api/world') body = { world: 'observed', revision: 1, epoch: 1 };
    else if (at === '/api/workspace' && method === 'PATCH') {
      workspaceRev += 1;
      workspaceContext = Object.assign({
        version: 1,
        world: 'observed',
        datasetId: idea ? fixtures.newIdea.DATASET_ID : null,
        marketLane: 'OBSERVED',
        accountId: fixtures.wire.ACCOUNT_ID,
        generation: 1
      }, workspaceContext || {}, requestBody || {});
      body = workspaceReceipt();
    } else if (at === '/api/workspace') {
      body = workspaceReceipt();
    } else if (at === '/api/account') {
      body = { account: { id: fixtures.wire.ACCOUNT_ID, cashCents: 5_000_000,
        buyingPowerCents: 9_700_000 }, ledger: [] };
    } else if (at === '/api/portfolio/book') {
      body = fixtures.book.practiceBookRead(world.book, {
        accountId: fixtures.wire.ACCOUNT_ID,
        snapshotId: `pbs_visual_${world.name || 'state'}`
      });
    }
    else if (at === '/api/portfolio/accounts') {
      body = [{ id: fixtures.wire.ACCOUNT_ID, name: 'Practice ••••0001' }];
    }
    else if (at === '/api/positions') body = world.book.positionBook;
    else if (at === '/api/trades') body = world.book.tradePage;
    else if (at === '/api/plans' && idea && method === 'GET') {
      body = { plans: [currentIdeaPlan()], market: 'OBSERVED', world: 'observed' };
    }
    else if (at === '/api/plans' && idea && method === 'POST') {
      ideaPlanVersion += 1;
      body = currentIdeaPlan();
    }
    else if (at === '/api/plans') body = world.plans;
    else if (at === '/api/plans/portfolio') body = world.planPortfolio;
    else if (at === '/api/universe') body = world.market.universe || {
      active: { symbols: [fixtures.wire.GOLDEN_SYMBOL] },
      symbols: [{ symbol: fixtures.wire.GOLDEN_SYMBOL, name: 'Golden Systems' }],
      sectors: [{ key: 'SYNTHETIC', label: 'Synthetic test sector',
        symbols: [fixtures.wire.GOLDEN_SYMBOL] }]
    };
    else if (at === '/api/strategies') body = idea ? idea.catalog : { catalog: [] };
    else if (research) {
      const lane = research[2] === 'expected-move' ? 'expectedMove'
        : research[2] || 'research';
      const document = (idea ? idea.market : world.market)[lane] !== undefined
        ? (idea ? idea.market : world.market)[lane]
        : (idea ? idea.market : world.market).research;
      if (document && typeof document.status === 'number'
          && Object.prototype.hasOwnProperty.call(document, 'body')) {
        status = document.status;
        body = document.body;
      } else body = document;
    } else if (idea && method === 'GET' && at === ideaPlanPath) {
      body = currentIdeaPlan();
    } else if (idea && method === 'GET' && at === `${ideaPlanPath}/strategy/latest`) {
      if (!strategyRan) {
        status = 404;
        body = { error: 'No current strategy competition.' };
      } else {
        body = fixtures.newIdea.strategy(idea.candidates,
          ideaSelected ? Object.assign({}, ideaSelected, { selected: true }) : null);
      }
    } else if (idea && method === 'POST' && at === `${ideaPlanPath}/strategy/run`) {
      strategyRan = true;
      body = {
        plan: currentIdeaPlan(),
        strategy: fixtures.newIdea.strategy(idea.candidates).strategy
      };
    } else if (idea && method === 'PUT' && at === `${ideaPlanPath}/strategy/select`) {
      const requested = idea.candidates.find(candidate =>
        String(candidate.id) === String(requestBody && requestBody.candidateId));
      if (!requested) {
        status = 404;
        body = { error: 'The requested fixture comparison is not current.' };
      } else {
        ideaPlanVersion += 1;
        ideaSelected = requested;
        body = {
          plan: currentIdeaPlan(),
          selection: { candidateId: requested.id, planVersion: ideaPlanVersion }
        };
      }
    } else if (idea && method === 'GET' && at === `${ideaPlanPath}/outcomes/ensemble/latest`) {
      status = 404;
      body = { error: 'No stored fixture ensemble.' };
    } else if (idea && method === 'GET' && at === `${ideaPlanPath}/outcomes/latest`) {
      body = { plan: currentIdeaPlan(), outcomes: [] };
    } else if (idea && method === 'POST' && at === `${ideaPlanPath}/outcomes/ensemble`) {
      body = fixtures.newIdea.ensemble(ideaSelected || idea.primary, ideaPlanVersion);
    } else if (idea && method === 'POST' && at === `${ideaPlanPath}/outcomes/run`) {
      body = fixtures.newIdea.outcome(ideaSelected || idea.primary, ideaPlanVersion);
    } else if (idea && method === 'GET' && at === `${ideaPlanPath}/decision/latest`) {
      status = 404;
      body = { error: 'No stored fixture decision.' };
    } else if (idea && method === 'POST' && at === `${ideaPlanPath}/decision/preview`) {
      body = fixtures.newIdea.decisionPreview(
        ideaSelected || idea.primary, requestBody || {}, ideaPlanVersion);
    } else if (idea && method === 'POST' && at === `${ideaPlanPath}/outcomes/ensemble/paths`) {
      body = fixtures.newIdea.scenario(
        ideaSelected || idea.primary, requestBody || {}, ideaPlanVersion);
    } else if (at === '/api/research/scout' && method === 'POST') {
      const scan = world.scout;
      if (scan && scan.requested) {
        await route.fulfill({
          status: scan.status || 200,
          contentType: 'application/x-ndjson; charset=utf-8',
          body: scan.ndjson
        });
        return;
      }
      status = 409;
      body = { error: 'Scout was not requested in the idle fixture.' };
    } else if (at.startsWith('/api/trades/')) {
      const id = decodeURIComponent(at.slice('/api/trades/'.length));
      body = world.book.tradeDetails[id] || { trade: null };
    } else body = {};
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  });
  return Object.assign({}, world, idea ? { idea } : {});
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
  try {
    await page.waitForFunction(() => window.DeskBackend != null && window.WORKSPACE != null);
  } catch (error) {
    const diagnosis = await page.evaluate(() => ({
      readyState: document.readyState,
      deskBackend: typeof window.DeskBackend,
      workspace: typeof window.WORKSPACE,
      body: document.body && document.body.textContent.slice(0, 500)
    }));
    throw new Error(`${error.message}\nDesk boot diagnosis: ${JSON.stringify(diagnosis)}`);
  }
  await page.waitForSelector('#board');
  await page.waitForFunction(() => {
    const board = document.getElementById('board');
    return board != null && board.getBoundingClientRect().height > 40;
  });
  await page.waitForTimeout(120); // one layout pass after the last hydration render
  const scan = page.__strikebenchVisualWorld && page.__strikebenchVisualWorld.scout;
  if (scan && scan.requested) {
    let state;
    if (scan.body) {
      state = { phase: 'complete', data: scan.body, error: null, progress: null, partial: [] };
    } else if (scan.cancelled) {
      const lastProgress = scan.frames.slice().reverse().find(frame => frame.type === 'progress');
      state = {
        phase: 'cancelled', data: null, error: null,
        progress: lastProgress && lastProgress.progress || null,
        partial: scan.partialPicks || []
      };
    } else if (scan.frames.some(frame => frame.type === 'error')) {
      const lastProgress = scan.frames.slice().reverse().find(frame => frame.type === 'progress');
      const errorFrame = scan.frames.find(frame => frame.type === 'error');
      state = {
        phase: 'failed', data: null,
        error: errorFrame && errorFrame.error || 'The opportunity scan could not finish.',
        progress: lastProgress && lastProgress.progress || null,
        partial: scan.partialPicks || []
      };
    } else if (scan.partialPicks && scan.partialPicks.length) {
      const lastProgress = scan.frames.slice().reverse().find(frame => frame.type === 'progress');
      state = {
        phase: 'partial', data: null, error: null,
        progress: lastProgress && lastProgress.progress || null,
        partial: scan.partialPicks
      };
    } else {
      state = {
        phase: 'starting', data: null, error: null,
        progress: null, partial: []
      };
    }
    await page.evaluate(next => {
      window.HOME_OPPORTUNITY = next;
      window.authRenderOpportunityFrame();
    }, state);
    await page.waitForTimeout(80);
  }
}

/**
 * Enter the canonical New Idea surface with the four declarations the product requires. This is
 * the same `enterDecide` owner Home, Scout, and Position use; the test does not mount a second
 * document or call a presentation-only renderer.
 */
async function openNewIdea(page, options) {
  const candidateId = fixtures.newIdea.documents().primary.id;
  await page.evaluate(symbol => {
    window.enterDecide('idea', null, 'New idea', null, symbol, null, {
      goal: 'Income',
      view: 'Neutral',
      horizon: '45 trading days',
      riskMode: 'Balanced'
    }, { restoring: true });
  }, fixtures.wire.GOLDEN_SYMBOL);
  try {
    /* A complete ranked field selects its first comparison for immediate analysis. The
       selection is not an endorsement: the exact verdict remains whatever the backend receipt
       says. This is the one-click product journey the visual lane must protect. */
    await page.waitForFunction(() => window.decide
      && (window.decide.backendPhase === 'comparison-required'
        || window.decide.backendPhase === 'ready'),
    null, { timeout: 20000 });
    const initial = await page.evaluate(() => ({
      phase: window.decide && window.decide.backendPhase,
      candidateId: window.decide && window.decide.candId
    }));
    if (initial.candidateId !== candidateId) {
      await page.locator(`.fanr[data-cand="${candidateId}"]`).click();
    }
    await page.waitForFunction(expectedId => window.decide
      && window.decide.backendPhase === 'ready'
      && window.decide.candId === expectedId
      && window.decide.orderPreview
      && document.querySelector('#decideStage .declegpanel')
      && document.querySelector('#mcFan path[d]'),
    candidateId, { timeout: 20000 });
  } catch (error) {
    const diagnosis = await page.evaluate(() => {
      const bridge = window.DeskBackend && window.DeskBackend.state();
      return {
        phase: window.decide && window.decide.backendPhase,
        candidateId: window.decide && window.decide.candId,
        presentationError: window.decide && window.decide.backendError,
        bridgeError: bridge && bridge.error
          && (bridge.error.stack || bridge.error.message || String(bridge.error)),
        plan: bridge && bridge.plan,
        body: document.body.textContent.slice(0, 1200)
      };
    });
    throw new Error(`${error.message}\nCanonical New Idea diagnosis: ${JSON.stringify(diagnosis)}`);
  }
  await page.waitForTimeout(160);
}

async function selectIdeaCandidate(page, candidate) {
  await page.locator(`.fanr[data-cand="${candidate.id}"]`).click();
  await page.waitForFunction(candidateId => window.decide
    && window.decide.backendPhase === 'ready'
    && window.decide.candId === candidateId
    && window.decide.orderPreview
    && window.decide.orderPreview.selected
    && window.decide.orderPreview.selected.id === candidateId,
  candidate.id, { timeout: 20000 });
  await page.waitForTimeout(120);
}

/** A DOM element is a nested vertical scroller only when it both declares scroll and overflows. */
async function ideaScrollOwners(page) {
  return page.evaluate(() => {
    const root = document.getElementById('decideStage');
    if (!root) return [];
    return Array.from(root.querySelectorAll('*')).filter(el => {
      const style = getComputedStyle(el);
      const box = el.getBoundingClientRect();
      return box.width > 2 && box.height > 2
        && /(auto|scroll)/.test(style.overflowY)
        && el.scrollHeight > el.clientHeight + 2;
    }).map(el => ({
      selector: el.tagName.toLowerCase()
        + (el.id ? `#${el.id}` : '')
        + (el.className && typeof el.className === 'string'
          ? `.${el.className.trim().split(/\s+/).slice(0, 3).join('.')}` : ''),
      clientHeight: el.clientHeight,
      scrollHeight: el.scrollHeight
    }));
  });
}

/**
 * Clipping inside New Idea. SVG drawing primitives are coordinate-space graphics rather than
 * boxes, but their owning SVG is still measured. No product panel or content class is filtered.
 */
async function ideaClippedElements(page) {
  return page.evaluate(() => {
    const root = document.getElementById('decideStage');
    if (!root) return [{ selector: '#decideStage', client: '0x0', content: '0x0',
      text: 'New Idea did not mount.' }];
    const clipped = [];
    root.querySelectorAll('*').forEach(el => {
      if (el.closest('svg') && el.tagName.toLowerCase() !== 'svg') return;
      const style = getComputedStyle(el);
      if (style.display === 'none' || style.visibility === 'hidden') return;
      const box = el.getBoundingClientRect();
      if (box.width < 1 || box.height < 1) return;
      if (/(auto|scroll)/.test(style.overflowX + style.overflowY)) return;
      const cuts = style.overflowX === 'hidden' || style.overflowY === 'hidden'
        || style.overflow === 'hidden' || style.textOverflow === 'ellipsis';
      if (!cuts) return;
      const overWide = el.scrollWidth > el.clientWidth + 2;
      const overTall = el.scrollHeight > el.clientHeight + 2;
      if (!overWide && !overTall) return;
      clipped.push({
        selector: el.tagName.toLowerCase()
          + (el.id ? `#${el.id}` : '')
          + (el.className && typeof el.className === 'string'
            ? `.${el.className.trim().split(/\s+/).slice(0, 3).join('.')}` : ''),
        client: `${el.clientWidth}x${el.clientHeight}`,
        content: `${el.scrollWidth}x${el.scrollHeight}`,
        text: (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 72)
      });
    });
    return clipped;
  });
}

/** Major New Idea regions must follow one another; overlapping canvases are never an affordance. */
async function ideaMajorOverlaps(page) {
  return page.evaluate(() => {
    function visible(selector) {
      const element = document.querySelector(selector);
      if (!element) return null;
      const style = getComputedStyle(element);
      const box = element.getBoundingClientRect();
      return style.display === 'none' || style.visibility === 'hidden'
        || box.width < 2 || box.height < 2 ? null : { selector, box };
    }
    const pairs = [
      ['.dccpay', '.scenpanel'],
      ['.scenpanel', '.marketlens'],
      ['.fan', '.declegpanel'],
      ['.declegpanel', '.pickmap'],
      ['.decisionbrief', '.inspectrail'],
      ['.inspectrail', '.inspectwell']
    ];
    const hits = [];
    pairs.forEach(pair => {
      const first = visible(pair[0]), second = visible(pair[1]);
      if (!first || !second) return;
      const x = Math.min(first.box.right, second.box.right)
        - Math.max(first.box.left, second.box.left);
      const y = Math.min(first.box.bottom, second.box.bottom)
        - Math.max(first.box.top, second.box.top);
      if (x > 2 && y > 2) {
        hits.push(`${pair[0]} over ${pair[1]} (${Math.round(x)}x${Math.round(y)}px)`);
      }
    });
    return hits;
  });
}

function includesDecimal(text, value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return false;
  const variants = new Set([
    String(value),
    String(numeric),
    numeric.toFixed(1),
    numeric.toFixed(2)
  ]);
  return Array.from(variants).some(variant => text.includes(variant));
}

/**
 * Inspect one settled New Idea state and return every geometry/interaction violation together.
 * Tests assert after unpinned, pinned, review, and alternate-package states have all been reached,
 * so one early defect cannot hide the rest of the surface.
 */
async function inspectNewIdea(page, viewport, candidate, stateLabel, options) {
  const settings = Object.assign({ requireNewsAction: false, requirePinned: false,
    requireReview: false }, options || {});
  const failures = [];
  const prefix = `${viewport.name} · ${stateLabel}`;
  const geometry = await page.evaluate(() => {
    const stage = document.getElementById('decideStage');
    const fan = document.getElementById('mcFan');
    const fanInk = (() => {
      if (!fan) return null;
      const paths = Array.from(fan.querySelectorAll('path[d]'));
      const boxes = paths.map(path => {
        try { return path.getBBox(); } catch (ignored) { return null; }
      }).filter(box => box && box.width > 0);
      const view = fan.viewBox && fan.viewBox.baseVal;
      if (!boxes.length || !view || !(view.width > 0) || !(view.height > 0)) return null;
      const left = Math.min(...boxes.map(box => box.x));
      const top = Math.min(...boxes.map(box => box.y));
      const right = Math.max(...boxes.map(box => box.x + box.width));
      const bottom = Math.max(...boxes.map(box => box.y + box.height));
      return {
        widthPct: (right - left) / view.width * 100,
        heightPct: (bottom - top) / view.height * 100,
        clipPath: getComputedStyle(fan).clipPath
      };
    })();
    const rect = element => {
      const box = element && element.getBoundingClientRect();
      return box ? {
        left: box.left, top: box.top, right: box.right, bottom: box.bottom,
        width: box.width, height: box.height
      } : null;
    };
    const unpin = document.querySelector('.scenpanel .srow-ctl [data-wf="unpin"]');
    const unpinBefore = unpin && getComputedStyle(unpin, '::before');
    const unpinAfter = unpin && getComputedStyle(unpin, '::after');
    return {
      pageScrollWidth: document.documentElement.scrollWidth,
      pageClientWidth: document.documentElement.clientWidth,
      pageScrollHeight: document.documentElement.scrollHeight,
      pageClientHeight: document.documentElement.clientHeight,
      bodyOverflowY: getComputedStyle(document.body).overflowY,
      stage: rect(stage),
      stageScrollHeight: stage && stage.scrollHeight,
      stageClientHeight: stage && stage.clientHeight,
      stageOverflowY: stage && getComputedStyle(stage).overflowY,
      fan: rect(fan),
      fanPaths: fan ? fan.querySelectorAll('path[d]').length : 0,
      fanInk,
      pinnedRows: document.querySelectorAll('.scenpanel .srow.pinned').length,
      pinnedControls: document.querySelectorAll('.scenpanel .srow-ctl').length,
      unpin: rect(unpin),
      unpinInk: unpin ? {
        before: unpinBefore.content,
        beforeWidth: parseFloat(unpinBefore.width) || 0,
        after: unpinAfter.content,
        afterWidth: parseFloat(unpinAfter.width) || 0
      } : null,
      review: rect(document.querySelector('.reviewexec'))
    };
  });
  if (geometry.pageScrollWidth > geometry.pageClientWidth + 1) {
    failures.push(`${prefix}: horizontal page overflow ${geometry.pageScrollWidth}px in `
      + `${geometry.pageClientWidth}px`);
  }
  if (!geometry.stage || geometry.stage.width < 100 || geometry.stage.height < 100) {
    failures.push(`${prefix}: canonical New Idea stage is not visibly mounted`);
  }
  if (settings.requirePinned && (!geometry.pinnedRows || !geometry.pinnedControls)) {
    failures.push(`${prefix}: pinned scenario did not expose its controls`);
  }
  if (settings.requirePinned && (!geometry.unpin || !geometry.unpinInk
      || geometry.unpinInk.before === 'none' || geometry.unpinInk.after === 'none'
      || geometry.unpinInk.beforeWidth < 1 || geometry.unpinInk.afterWidth < 1)) {
    failures.push(`${prefix}: pinned scenario exposes a blank/unusable unpin control `
      + `(${JSON.stringify({ box: geometry.unpin, ink: geometry.unpinInk })})`);
  }
  if (settings.requireReview && !geometry.review) {
    failures.push(`${prefix}: Review did not open the exact order review`);
  }

  const clips = await ideaClippedElements(page);
  clips.forEach(clip => failures.push(`${prefix}: ${clip.selector} clips ${clip.content} into `
    + `${clip.client} — "${clip.text}"`));
  (await ideaMajorOverlaps(page)).forEach(hit =>
    failures.push(`${prefix}: major regions overlap: ${hit}`));
  const evidenceCollisions = await page.evaluate(() => Array.from(
    document.querySelectorAll('#decideStage .evgrid .evrow')).flatMap((row, index) => {
    const key = row.querySelector('.evk');
    const value = row.querySelector('.evtxt');
    if (!key || !value) return [];
    const rowBox = row.getBoundingClientRect();
    const keyBox = key.getBoundingClientRect();
    const valueBox = value.getBoundingClientRect();
    const collides = keyBox.right > valueBox.left + 1;
    const escapes = keyBox.left < rowBox.left - 1 || valueBox.right > rowBox.right + 1;
    return collides || escapes ? [{
      index,
      key: (key.textContent || '').trim(),
      value: (value.textContent || '').trim(),
      keyRight: Math.round(keyBox.right),
      valueLeft: Math.round(valueBox.left),
      rowLeft: Math.round(rowBox.left),
      rowRight: Math.round(rowBox.right),
      valueRight: Math.round(valueBox.right)
    }] : [];
  }));
  evidenceCollisions.forEach(collision =>
    failures.push(`${prefix}: Evidence row ${collision.index + 1} overlaps or escapes: `
      + `${JSON.stringify(collision)}`));

  const legs = await page.evaluate(() => Array.from(
    document.querySelectorAll('#decideStage .declegpanel .legr')).map(row => {
      const box = row.getBoundingClientRect();
      const parent = row.parentElement.getBoundingClientRect();
      return {
        text: (row.textContent || '').replace(/\s+/g, ' ').trim(),
        contained: box.left >= parent.left - 1 && box.right <= parent.right + 1
          && box.top >= parent.top - 1 && box.bottom <= parent.bottom + 1
      };
    }));
  if (legs.length !== candidate.legs.length) {
    failures.push(`${prefix}: ${candidate.legs.length}-leg package rendered ${legs.length} leg rows`);
  }
  candidate.legs.forEach((leg, index) => {
    const rendered = legs[index];
    if (!rendered) return;
    if (!rendered.contained) failures.push(`${prefix}: leg ${index + 1} escapes its workbench`);
    if (!includesDecimal(rendered.text, leg.strike)) {
      failures.push(`${prefix}: leg ${index + 1} hides strike ${leg.strike}: "${rendered.text}"`);
    }
    if (!includesDecimal(rendered.text, leg.quoteBid)
        || !includesDecimal(rendered.text, leg.quoteAsk)) {
      failures.push(`${prefix}: leg ${index + 1} hides bid/ask ${leg.quoteBid} / `
        + `${leg.quoteAsk}: "${rendered.text}"`);
    }
  });

  const scrollOwners = await ideaScrollOwners(page);
  if (viewport.width <= DOCUMENT_LAYOUT_WIDTH) {
    if (!geometry.pageScrollHeight || geometry.pageScrollHeight <= geometry.pageClientHeight) {
      failures.push(`${prefix}: document layout does not expose the one expected page scroller`);
    }
    scrollOwners.forEach(owner => failures.push(`${prefix}: document layout has nested vertical scroller `
      + `${owner.selector} (${owner.scrollHeight}px in ${owner.clientHeight}px)`));
    if (!geometry.fan || geometry.fan.height < 150 || geometry.fan.width < 150
        || geometry.fanPaths < 1) {
      failures.push(`${prefix}: Evidence & Paths fan is not useful/visible in the document layout `
        + `(box ${geometry.fan ? `${Math.round(geometry.fan.width)}x${Math.round(geometry.fan.height)}` : 'missing'}, `
        + `${geometry.fanPaths} paths)`);
    }
  } else {
    if (!geometry.fan || geometry.fan.height < 120 || geometry.fan.width < 240
        || geometry.fanPaths < 1) {
      failures.push(`${prefix}: Evidence & Paths fan is not useful/visible `
        + `(box ${geometry.fan ? `${Math.round(geometry.fan.width)}x${Math.round(geometry.fan.height)}` : 'missing'}, `
        + `${geometry.fanPaths} paths)`);
    }
    scrollOwners.forEach(owner => failures.push(`${prefix}: desktop has nested vertical scroller `
      + `${owner.selector} (${owner.scrollHeight}px in ${owner.clientHeight}px)`));
    if (viewport.height >= 1150 && geometry.fan
        && (geometry.fan.height > 360 || geometry.fan.width / geometry.fan.height < 1.25)) {
      failures.push(`${prefix}: tall desktop stretches Evidence & Paths out of landscape geometry `
        + `(${Math.round(geometry.fan.width)}x${Math.round(geometry.fan.height)})`);
    }
  }
  if (geometry.fan && (!geometry.fanInk || geometry.fanInk.widthPct < 50
      || geometry.fanInk.heightPct < 8
      || /inset\([^)]*(?:[1-9]\d*|0?\.\d+)%/.test(geometry.fanInk.clipPath || ''))) {
    failures.push(`${prefix}: Evidence & Paths has a box and path nodes but no useful visible ink `
      + `(${geometry.fanInk
        ? `${geometry.fanInk.widthPct.toFixed(1)}% × ${geometry.fanInk.heightPct.toFixed(1)}%, `
          + `clip ${geometry.fanInk.clipPath}`
        : 'no measurable path ink'})`);
  }

  if (viewport.width <= PHONE_WIDTH) {
    const targets = await page.evaluate(() => Array.from(document.querySelectorAll(
      '#decideStage button, #decideStage [role="button"], #decideStage a[href], '
      + '#decideStage input, #decideStage select, #decideStage textarea')).filter(el => {
      const style = getComputedStyle(el);
      const box = el.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && !el.disabled
        && box.width > 0 && box.height > 0;
    }).map(el => {
      const box = el.getBoundingClientRect();
      return {
        label: (el.getAttribute('aria-label') || el.textContent || el.value || '')
          .trim().replace(/\s+/g, ' ').slice(0, 44),
        width: Math.round(box.width),
        height: Math.round(box.height)
      };
    }));
    targets.filter(target => target.width < 40 || target.height < 40).forEach(target => {
      failures.push(`${prefix}: touch target "${target.label}" is ${target.width}x${target.height}, `
        + 'below the 40px minimum');
    });
  }

  if (settings.requireNewsAction) {
    const news = await page.evaluate(() => {
      const host = document.querySelector('#decideStage .marketlens');
      const links = host ? host.querySelectorAll('.authnews a').length : 0;
      const action = host && Array.from(host.querySelectorAll('button, a')).find(el =>
        /(?:\\+\\d+\\s+more|more headlines|view all|open research|all headlines)/i.test(
          `${el.textContent || ''} ${el.getAttribute('aria-label') || ''}`));
      return {
        visibleHeadlines: links,
        action: action ? (action.textContent || action.getAttribute('aria-label') || '').trim() : null
      };
    });
    if (news.visibleHeadlines < 1) failures.push(`${prefix}: no research headline is visible`);
    if (!news.action) {
      failures.push(`${prefix}: 20-headline receipt has no actionable overflow disclosure`);
    }
  }
  return failures;
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
      // Ellipsis is safe only when the complete value remains available without guesswork.
      // A prior version assumed every ellipsis had a title and consequently green-lit clipped
      // strikes, receipts, and actions that had no reversible disclosure at all.
      if (style.textOverflow === 'ellipsis') {
        const fullText = (el.textContent || '').trim().replace(/\s+/g, ' ');
        const title = (el.getAttribute('title') || '').trim().replace(/\s+/g, ' ');
        const aria = (el.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ');
        const owner = el.closest('button, a[href], [role="button"]');
        const ownerText = owner
          ? `${owner.getAttribute('title') || ''} ${owner.getAttribute('aria-label') || ''}`
            .trim().replace(/\s+/g, ' ')
          : '';
        if ((title && title.length >= fullText.length)
            || (aria && aria.length >= fullText.length)
            || (ownerText && ownerText.length >= fullText.length)) return;
      }
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

for (const state of CONTENT_STATES) {
  for (const viewport of VIEWPORTS) {
    test(`Home composes with ${state.name} at ${viewport.name}`, async () => {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    try {
      await installWorld(page, state.desk);
      await bootHome(page);
      await page.screenshot({ path: path.join(SHOTS,
        `home-${state.name.replace(/[^a-z0-9]+/gi, '-')}-${viewport.name}.png`) });

      const page_ = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
        boardHeight: Math.round(document.getElementById('board').getBoundingClientRect().height)
      }));
      assert.ok(page_.scrollWidth <= page_.clientWidth + 1,
        `${state.name} scrolls sideways at ${viewport.name}: content ${page_.scrollWidth}px in `
        + `${page_.clientWidth}px. Wide content must scroll inside its own container, never the page.`);
      assert.ok(page_.boardHeight > 80,
        `${state.name} left Home essentially empty at ${viewport.name} (${page_.boardHeight}px). `
        + 'An empty or degraded state must still say what it knows and why, not collapse.');

      const clipped = await clippedElements(page);
      const marketClipDiagnosis = clipped.some(entry => /authmarketpulse/.test(entry.selector))
        ? await page.evaluate(() => {
          const panel = document.querySelector('.authmarketpulse');
          const describe = node => {
            const box = node.getBoundingClientRect();
            return {
              className: node.className,
              top: Math.round(box.top), bottom: Math.round(box.bottom),
              height: Math.round(box.height),
              clientHeight: node.clientHeight, scrollHeight: node.scrollHeight,
              children: node.classList.contains('pulsecols') || node.classList.contains('authhomehistory')
                || node.classList.contains('histwrap') || node.classList.contains('authchainslice')
                ? Array.from(node.children).map(describe) : undefined
            };
          };
          return Array.from(panel.children).map(describe);
        }) : null;
      const boardClipDiagnosis = clipped.some(entry => /#board\b/.test(entry.selector))
        ? await page.evaluate(() => {
          const board = document.getElementById('board');
          if (!board) return null;
          const box = board.getBoundingClientRect();
          return {
            top: Math.round(box.top), bottom: Math.round(box.bottom),
            height: Math.round(box.height),
            clientHeight: board.clientHeight, scrollHeight: board.scrollHeight,
            rows: getComputedStyle(board).gridTemplateRows,
            children: Array.from(board.children).map(child => {
              const childBox = child.getBoundingClientRect();
              return {
                id: child.id, className: child.className,
                top: Math.round(childBox.top), bottom: Math.round(childBox.bottom),
                height: Math.round(childBox.height),
                clientHeight: child.clientHeight, scrollHeight: child.scrollHeight,
                overflow: `${getComputedStyle(child).overflowX}/${getComputedStyle(child).overflowY}`,
                children: child.scrollHeight > child.clientHeight + 2
                  ? Array.from(child.children).map(grandchild => {
                    const grandchildBox = grandchild.getBoundingClientRect();
                    return {
                      className: grandchild.className,
                      top: Math.round(grandchildBox.top), bottom: Math.round(grandchildBox.bottom),
                      height: Math.round(grandchildBox.height),
                      clientHeight: grandchild.clientHeight, scrollHeight: grandchild.scrollHeight,
                      overflow: `${getComputedStyle(grandchild).overflowX}/${getComputedStyle(grandchild).overflowY}`
                    };
                  }) : undefined
              };
            })
          };
        }) : null;
      const scoutClipDiagnosis = clipped.some(entry => /opportunityrows/.test(entry.selector))
        ? await page.evaluate(() => {
          const rows = document.querySelector('.opportunityrows.provisional');
          if (!rows) return null;
          const describe = node => {
            const box = node.getBoundingClientRect();
            return {
              className: node.className,
              top: Math.round(box.top), bottom: Math.round(box.bottom),
              height: Math.round(box.height),
              clientHeight: node.clientHeight, scrollHeight: node.scrollHeight,
              marginTop: getComputedStyle(node).marginTop,
              padding: getComputedStyle(node).padding,
              gap: getComputedStyle(node).gap,
              children: Array.from(node.children).map(child => {
                const childBox = child.getBoundingClientRect();
                return {
                  className: child.className,
                  top: Math.round(childBox.top), bottom: Math.round(childBox.bottom),
                  height: Math.round(childBox.height),
                  clientHeight: child.clientHeight, scrollHeight: child.scrollHeight
                };
              })
            };
          };
          return describe(rows);
        }) : null;
      assert.deepEqual(clipped, [],
        `${state.name} content is cut off at ${viewport.name}:\n`
        + clipped.map(c => `  ${c.selector} draws ${c.client} around ${c.content} — "${c.text}"`).join('\n')
        + (marketClipDiagnosis ? `\n  Market children: ${JSON.stringify(marketClipDiagnosis)}` : '')
        + (boardClipDiagnosis ? `\n  Board geometry: ${JSON.stringify(boardClipDiagnosis)}` : '')
        + (scoutClipDiagnosis ? `\n  Scout geometry: ${JSON.stringify(scoutClipDiagnosis)}` : ''));

      const collisions = await overlappingText(page);
      assert.deepEqual(collisions, [],
        `${state.name} draws text over text at ${viewport.name}:\n  ${collisions.join('\n  ')}`);

      assert.deepEqual(pageErrors, [],
        `${state.name} emitted page errors at ${viewport.name}: ${pageErrors.join('\n')}`);
    } finally {
      await context.close();
    }
    });
  }
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

      const geometry = await page.evaluate(() => {
        const board = document.querySelector('.lv-position .board');
        const support = document.querySelector('.lv-position .authresearchgrid');
        const silentRoots = Array.from(document.querySelectorAll(
          '.lv-position .focus, .lv-position .focus>.card, '
          + '.lv-position .focus>.card>.cdetail, .lv-position .authpos')).flatMap(node => {
          const style = getComputedStyle(node);
          const hidden = /hidden|clip/.test(`${style.overflow} ${style.overflowY}`);
          return hidden && node.scrollHeight > node.clientHeight + 2
            ? [`${node.className}: ${node.scrollHeight}px in ${node.clientHeight}px`] : [];
        });
        return {
          scrollWidth: document.documentElement.scrollWidth,
          clientWidth: document.documentElement.clientWidth,
          boardOverflow: board ? board.scrollHeight - board.clientHeight : 0,
          supportHeight: support ? Math.round(support.getBoundingClientRect().height) : 0,
          silentRoots
        };
      });
      assert.ok(geometry.scrollWidth <= geometry.clientWidth + 1,
        `the Position bloom scrolls the page sideways at ${viewport.name}: `
        + `${geometry.scrollWidth}px in ${geometry.clientWidth}px.`);
      if (viewport.width > DOCUMENT_LAYOUT_WIDTH) {
        assert.ok(geometry.boardOverflow <= 2,
          `the bounded Position board scrolls by ${geometry.boardOverflow}px at ${viewport.name}`);
        assert.deepEqual(geometry.silentRoots, [],
          `Position silently clips an overflowing root at ${viewport.name}: `
          + geometry.silentRoots.join(', '));
      }
      if (viewport.width > DOCUMENT_LAYOUT_WIDTH && viewport.height >= 1150) {
        assert.ok(geometry.supportHeight <= 430,
          `Position support stretches to ${geometry.supportHeight}px at ${viewport.name}`);
      }

      const clipped = await clippedElements(page);
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

test('full-desktop Home gives each job one visible owner and no default board scroll', async () => {
  const failures = [];
  for (const viewport of VIEWPORTS.filter(row => row.width >= 1500)) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, { positions: 4, workingIdeas: 5, scout: 'complete' });
      await bootHome(page);
      const measured = await page.evaluate(() => {
        const box = id => {
          const node = document.getElementById(id);
          const rect = node && node.getBoundingClientRect();
          return node && rect ? {
            width: Math.round(rect.width), height: Math.round(rect.height),
            left: Math.round(rect.left), right: Math.round(rect.right),
            top: Math.round(rect.top), bottom: Math.round(rect.bottom),
            display: getComputedStyle(node).display
          } : null;
        };
        const board = document.getElementById('board');
        const activity = document.getElementById('activityBand');
        const history = document.querySelector('#chainBand .authhomehistory');
        const chain = document.querySelector('#chainBand .authchainslice');
        const actualNestedScrollers = Array.from(document.querySelectorAll(
          '#riskMain .opportunityrows, #activityBand, #sectorBand .authmarketwatch, '
          + '#newsBand .authhomenews'))
          .filter(node => {
            const style = getComputedStyle(node);
            return /(auto|scroll)/.test(style.overflowY)
              && node.scrollHeight > node.clientHeight + 2;
          })
          .map(node => ({
            id: node.id || node.className,
            client: Math.round(node.clientHeight),
            scroll: Math.round(node.scrollHeight)
          }));
        return {
          boardOverflow: board.scrollHeight - board.clientHeight,
          fanState: document.getElementById('stage').getAttribute('data-book-fan-state'),
          scoutState: document.getElementById('stage').getAttribute('data-scout-state'),
          gridRows: getComputedStyle(board).gridTemplateRows,
          activityOwnsBook: document.getElementById('book').parentElement === activity,
          activityOwnsIdeas: document.getElementById('univBand').parentElement === activity,
          market: box('chainBand'), scout: box('riskMain'), book: box('bookrisk'),
          watch: box('sectorBand'), news: box('newsBand'), activity: box('activityBand'),
          bookFacts: document.querySelector('#bookrisk .bookonefacts')?.textContent
            .replace(/\s+/g, ' ').trim() || '',
          historyWidth: history ? Math.round(history.getBoundingClientRect().width) : 0,
          chainWidth: chain ? Math.round(chain.getBoundingClientRect().width) : 0,
          actualNestedScrollers
        };
      });
      if (measured.boardOverflow > 2) {
        failures.push(`${viewport.name}: default Home board scrolls by ${measured.boardOverflow}px`);
      }
      if (!measured.activityOwnsBook || !measured.activityOwnsIdeas) {
        failures.push(`${viewport.name}: Positions and Working ideas do not share the activity owner`);
      }
      const regions = {
        Market: measured.market, Scout: measured.scout,
        Book: measured.book, Watch: measured.watch, News: measured.news, Activity: measured.activity
      };
      for (const [name, box] of Object.entries(regions)) {
        if (!box || box.display === 'none' || box.width < 80 || box.height < 80) {
          failures.push(`${viewport.name}: ${name} is not a useful visible Home region (${JSON.stringify(box)})`);
        }
      }
      if (measured.fanState === 'unavailable') {
        if (!/(?:Chance.*Max loss.*Entry credit.*(?:Final )?Expiry|Max loss.*Concentration)/i
          .test(measured.bookFacts)) {
          failures.push(`${viewport.name}: unavailable measured paths erased useful structural Book facts `
            + `(${JSON.stringify(measured.bookFacts)})`);
        }
      }
      if (measured.chainWidth < 300) {
        failures.push(`${viewport.name}: option chain is only ${measured.chainWidth}px wide`);
      }
      if (measured.historyWidth < 320) {
        failures.push(`${viewport.name}: market history is only ${measured.historyWidth}px wide`);
      }
      const decisionTop = Math.min(
        measured.book?.top ?? Number.POSITIVE_INFINITY,
        measured.activity?.top ?? Number.POSITIVE_INFINITY,
        measured.news?.top ?? Number.POSITIVE_INFINITY
      );
      const orientationBottom = measured.market?.bottom ?? Number.NEGATIVE_INFINITY;
      const decisionGap = decisionTop - orientationBottom;
      if (!Number.isFinite(decisionGap) || decisionGap < 0 || decisionGap > 18) {
        failures.push(`${viewport.name}: the orientation field leaves a ${decisionGap}px dead band `
          + `before the decision row (${JSON.stringify({
            market: measured.market, news: measured.news, scout: measured.scout,
            book: measured.book, activity: measured.activity
          })})`);
      }
      if (measured.scoutState !== 'idle') {
        const watchGap = (measured.watch?.top ?? Number.POSITIVE_INFINITY)
          - (measured.scout?.bottom ?? Number.NEGATIVE_INFINITY);
        if (!Number.isFinite(watchGap) || watchGap < 0 || watchGap > 18
          || measured.scout.height >= measured.market.height * .8) {
          failures.push(`${viewport.name}: completed Scout did not remain a bounded workbench above `
            + `the Watchlist (${JSON.stringify({
              state: measured.scoutState, rows: measured.gridRows, market: measured.market,
              scout: measured.scout, watch: measured.watch, watchGap
            })})`);
        }
      }
      if (measured.actualNestedScrollers.length > 1) {
          failures.push(`${viewport.name}: ${measured.actualNestedScrollers.length} nested lists scroll at rest `
          + `(${JSON.stringify(measured.actualNestedScrollers)}; scout=${measured.scoutState}; `
          + `rows=${measured.gridRows})`);
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `full-desktop Home does not honor its six-owner composition:\n  ${failures.join('\n  ')}`);
});

test('wide sparse Home preserves exact Book facts when measured paths are unavailable', async () => {
  const failures = [];
  for (const viewport of VIEWPORTS.filter(row =>
    (row.width === 2560 && row.height === 1440) || (row.width === 2000 && row.height === 963))) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      // No linked Plan ensemble is deliberately the degraded receipt that previously left a
      // three-column blank monument between Scout and the activity rail.
      await installWorld(page, { positions: 1, shares: 0, workingIdeas: 0, scout: 'idle' });
      await bootHome(page);
      const measured = await page.evaluate(() => {
        const rect = selector => {
          const node = document.querySelector(selector);
          const box = node && node.getBoundingClientRect();
          return box ? { left: box.left, right: box.right, width: box.width, height: box.height } : null;
        };
        const board = document.getElementById('board');
        const book = document.getElementById('bookrisk');
        const activity = document.getElementById('activityBand');
        return {
          fanState: document.getElementById('stage').getAttribute('data-book-fan-state'),
          bookDisplay: getComputedStyle(book).display,
          boardOverflow: board.scrollHeight - board.clientHeight,
          scout: rect('#riskMain'),
          activity: rect('#activityBand'),
          activityOverflow: activity.scrollHeight - activity.clientHeight,
          startIdea: rect('#authEmptyNewIdea'),
          book: rect('#bookrisk'),
          facts: document.querySelector('#bookrisk .bookonefacts')?.textContent
            .replace(/\s+/g, ' ').trim() || '',
          history: rect('#chainBand .authhomehistory'),
          chain: rect('#chainBand .authchainslice')
        };
      });
      if (measured.fanState !== 'unavailable') {
        failures.push(`${viewport.name}: sparse fixture did not exercise unavailable Book fan`);
      }
      if (measured.bookDisplay === 'none' || !measured.book
          || measured.book.width < 80 || measured.book.height < 80) {
        failures.push(`${viewport.name}: unavailable measured paths hide the useful Book receipt`);
      }
      if (!/Chance.*Max loss.*Entry credit.*(?:Final )?Expiry/i.test(measured.facts)) {
        failures.push(`${viewport.name}: structural Book facts are incomplete `
          + `(${JSON.stringify(measured.facts)})`);
      }
      if (!measured.scout || !measured.book || !measured.activity
          || measured.book.left - measured.scout.right > 16
          || measured.activity.left - measured.book.right > 16) {
        failures.push(`${viewport.name}: Scout, Book facts, and activity do not use the decision row `
          + `(${JSON.stringify({
            scout: measured.scout, book: measured.book, activity: measured.activity
          })})`);
      }
      if (measured.boardOverflow > 2) {
        failures.push(`${viewport.name}: sparse default Home scrolls by ${measured.boardOverflow}px`);
      }
      if (!measured.startIdea || measured.startIdea.bottom > measured.activity.bottom + 1
          || measured.activityOverflow > 2) {
        failures.push(`${viewport.name}: empty Working Ideas action is unreachable inside Activity `
          + `(${JSON.stringify({
            action: measured.startIdea,
            activity: measured.activity,
            overflow: measured.activityOverflow
          })})`);
      }
      if (!measured.history || measured.history.width < 320
          || !measured.chain || measured.chain.width < 280) {
        failures.push(`${viewport.name}: market/chain lost useful width `
          + `(${JSON.stringify({ history: measured.history, chain: measured.chain })})`);
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `wide sparse Home loses useful Book facts without measured paths:\n  ${failures.join('\n  ')}`);
});

test('one-position Home fan uses the shared path kernel and keeps its exact facts on one line', async () => {
  const failures = [];
  for (const viewport of VIEWPORTS.filter(row =>
    (row.width === 2560 && row.height === 1440)
      || (row.width === 2000 && row.height === 963)
      || (row.width === 1920 && row.height === 1080))) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, { positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' });
      await bootHome(page);
      const measured = await page.evaluate(() => {
        const position = POS[0];
        BOOK_FAN.rows[position.id] = {
          phase: 'ready',
          frames: [
            { x: 0, p10: -90, p25: -30, p50: 0, p75: 35, p90: 100 },
            { x: 45, p10: -850, p25: -220, p50: 190, p75: 480, p90: 920 }
          ],
          paths: [[0, 190], [0, -420]],
          progress: [0, 45],
          chance: 71,
          finalOptionExpiration: '2026-12-18'
        };
        renderAuthoritativeBookFan();
        const svg = document.getElementById('authBookFan');
        const facts = Array.from(document.querySelectorAll('#authBookFanFacts .authmetric'));
        return {
          fanState: document.getElementById('stage').getAttribute('data-book-fan-state'),
          sharedMap: svg._pathFanMap === svg._bfmap,
          pathSpace: svg.getAttribute('data-path-space'),
          medians: svg.querySelectorAll('.fan-series-median').length,
          texts: facts.map(node => node.textContent.replace(/\s+/g, ' ').trim()),
          values: facts.map(node => {
            const value = node.querySelector('b');
            const style = getComputedStyle(value);
            return {
              whiteSpace: style.whiteSpace,
              width: value.clientWidth,
              scrollWidth: value.scrollWidth,
              height: value.getBoundingClientRect().height,
              lineHeight: parseFloat(style.lineHeight)
            };
          })
        };
      });
      if (measured.fanState !== 'ready' || !measured.sharedMap
          || measured.pathSpace !== 'pnl' || measured.medians < 1) {
        failures.push(`${viewport.name}: Book did not use the shared P/L fan `
          + `(${JSON.stringify(measured)})`);
      }
      if (!measured.texts.some(text => /Chance\s*73%/.test(text))
          || !measured.texts.some(text => /Final expiry\s*2026-12-18/.test(text))) {
        failures.push(`${viewport.name}: exact one-position facts are missing (${measured.texts.join(' | ')})`);
      }
      measured.values.forEach((value, index) => {
        if (value.whiteSpace !== 'nowrap' || value.scrollWidth > value.width + 1
            || value.height > value.lineHeight + 2) {
          failures.push(`${viewport.name}: fact ${index + 1} wraps or clips (${JSON.stringify(value)})`);
        }
      });
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `one-position Book fan/facts diverge across desktop sizes:\n  ${failures.join('\n  ')}`);
});

test('Home chain books stage exact contracts while strike controls preview and pin the chart', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.stack || error.message));
  try {
    await installWorld(page, {
      positions: 4, workingIdeas: 5, scout: 'idle',
      idea: { primaryLegCount: 4 }
    });
    await bootHome(page);
    for (const selector of [
      '[data-auth-scout-goal="INCOME"]',
      '[data-auth-workbench-view="Neutral"]',
      '[data-auth-workbench-horizon="45 trading days"]',
      '[data-auth-workbench-risk="Balanced"]'
    ]) {
      await page.locator(selector).click();
    }
    const row = page.locator('#chainBand .authchainrow[data-chain-k]').first();
    const strikeButton = row.locator('[data-chain-pin]');
    await strikeButton.waitFor();
    const strike = await row.getAttribute('data-chain-k');

    await strikeButton.focus();
    assert.match(await page.locator('#chainBand [data-hist-cross]').textContent(),
      new RegExp(`strike\\s+${String(strike).replace('.', '\\.')}`, 'i'),
      'keyboard focus previews the selected strike on the market chart');

    await strikeButton.press('Enter');
    assert.equal(await strikeButton.getAttribute('aria-pressed'), 'true',
      'Enter pins the focused chain strike');
    assert.equal(await row.evaluate(node => node.classList.contains('on')), true,
      'the pinned strike remains visibly selected');
    await page.evaluate(() => document.activeElement && document.activeElement.blur());
    assert.match(await page.locator('#chainBand [data-hist-cross]').textContent(),
      new RegExp(`strike\\s+${String(strike).replace('.', '\\.')}`, 'i'),
      'the chart rail survives focus leaving the pinned row');

    await strikeButton.focus();
    await strikeButton.press('Enter');
    assert.equal(await strikeButton.getAttribute('aria-pressed'), 'false',
      'choosing the same strike again unpins it');
    assert.equal(await page.locator('#chainBand [data-hist-cross]').textContent(), '',
      'unpinning clears the shared chart rail');
    const buyCall = row.locator('[data-chain-open="BUY_CALL"]');
    await buyCall.click();
    await page.locator('#decideStage.on').waitFor();
    try {
      await page.waitForFunction(expected => {
        const leg = document.querySelector('#decideStage .declegs .legr');
        return leg && leg.textContent.includes(String(expected));
      }, strike, { timeout: 10000 });
    } catch (error) {
      const diagnosis = await page.evaluate(() => ({
        pendingFork: window.AUTH_PENDING_FORK,
        phase: window.decide && window.decide.backendPhase,
        mode: window.decide && window.decide.mode,
        draftError: window.decide && window.decide.draftError,
        text: document.querySelector('#decideStage')?.innerText.slice(0, 2000)
      }));
      throw new Error(`exact Home chain leg did not arrive: ${JSON.stringify(diagnosis)}`, { cause: error });
    }
    assert.match(await page.locator('#decideStage .declegs .legr').first().textContent(),
      /BUY\s*CALL/i, 'the ask action stages a buy call, not an ambiguous strike-only idea');
    assert.equal(await page.locator('#decideStage .declegs .legr').first().textContent()
      .then(text => text.includes(String(strike))), true,
    'the exact Home strike survives into canonical New Idea');
    assert.deepEqual(pageErrors, [], `chain inspection emitted page errors: ${pageErrors.join('\n')}`);
  } finally {
    await context.close();
  }
});

test('empty Home reallocates Book and empty activity space to discovery', async () => {
  const failures = [];
  for (const viewport of VIEWPORTS.filter(row => row.width >= 1500)) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, { positions: 0, shares: 0, workingIdeas: 0, scout: 'idle' });
      await bootHome(page);
      const measured = await page.evaluate(() => {
        const board = document.getElementById('board').getBoundingClientRect();
        const scout = document.getElementById('riskMain').getBoundingClientRect();
        const market = document.getElementById('chainBand').getBoundingClientRect();
        const watch = document.getElementById('sectorBand').getBoundingClientRect();
        const news = document.getElementById('newsBand').getBoundingClientRect();
        return {
          bookDisplay: getComputedStyle(document.getElementById('bookrisk')).display,
          activityDisplay: getComputedStyle(document.getElementById('activityBand')).display,
          marketShare: market.width / board.width,
          scoutShare: scout.width / board.width,
          newsShare: news.width / board.width,
          marketHeightShare: market.height / board.height,
          rightContained: scout.left >= market.right - 1 && watch.left >= market.right - 1
            && news.left >= market.right - 1,
          rightOrdered: scout.bottom <= watch.top + 1 && watch.bottom <= news.top + 1,
          // The board owns one 10px design-system inset. Measure against its usable content box,
          // not the outer border box, so a deliberate gutter is not mistaken for dead space.
          fillsBoard: market.top <= board.top + 11 && market.bottom >= board.bottom - 11
            && scout.top <= board.top + 11 && news.bottom >= board.bottom - 11
        };
      });
      if (measured.bookDisplay !== 'none') failures.push(`${viewport.name}: empty Book still reserves its panel`);
      if (measured.activityDisplay !== 'none') failures.push(`${viewport.name}: empty activity still reserves its rail`);
      if (measured.marketShare < .54 || measured.marketHeightShare < .94) {
        failures.push(`${viewport.name}: Market does not own the empty Book field `
          + `(${(measured.marketShare * 100).toFixed(0)}% × `
          + `${(measured.marketHeightShare * 100).toFixed(0)}%)`);
      }
      if (measured.scoutShare < .40) failures.push(`${viewport.name}: discovery receives only ${(measured.scoutShare * 100).toFixed(0)}%`);
      if (measured.newsShare < .3) failures.push(`${viewport.name}: research receives only ${(measured.newsShare * 100).toFixed(0)}%`);
      if (!measured.rightContained || !measured.rightOrdered || !measured.fillsBoard) {
        failures.push(`${viewport.name}: discovery column is not one ordered, contained use of `
          + `the empty Book field: ${JSON.stringify(measured)}`);
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `empty Home preserves dead rectangles instead of reallocating them:\n  ${failures.join('\n  ')}`);
});

test('mobile Home has one page scroller and releases every nested list', async () => {
  const failures = [];
  for (const viewport of VIEWPORTS.filter(row => row.width <= PHONE_WIDTH)) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, {
        positions: 12, shares: 2, workingIdeas: 20, mixedIdeas: true, scout: 'complete'
      });
      await bootHome(page);
      const measured = await page.evaluate(() => {
        const board = document.getElementById('board');
        const nested = Array.from(board.querySelectorAll('*')).filter(node => {
          const style = getComputedStyle(node);
          return /(auto|scroll)/.test(style.overflowY)
            && node.scrollHeight > node.clientHeight + 2;
        }).map(node => node.id || node.className);
        return {
          boardScrolls: board.scrollHeight > board.clientHeight + 2,
          nested,
          activityOwnsBook: document.getElementById('book').parentElement === document.getElementById('activityBand'),
          activityOwnsIdeas: document.getElementById('univBand').parentElement === document.getElementById('activityBand'),
          liquidityFacts: Array.from(document.querySelectorAll('.liquiditylegend [data-liquidity-fact]'))
            .map(node => ({
              key: node.getAttribute('data-liquidity-fact'),
              text: (node.textContent || '').replace(/\s+/g, ' ').trim(),
              clipped: node.scrollWidth > node.clientWidth + 1
            }))
        };
      });
      if (!measured.boardScrolls) failures.push(`${viewport.name}: the page owner does not scroll`);
      if (measured.nested.length) failures.push(`${viewport.name}: nested scrollers remain (${measured.nested.join(', ')})`);
      if (!measured.activityOwnsBook || !measured.activityOwnsIdeas) {
        failures.push(`${viewport.name}: activity ownership changes on mobile`);
      }
      const cashKeys = measured.liquidityFacts.map(fact => fact.key);
      if (!['encumbered', 'pending', 'free'].every(key => cashKeys.includes(key))
          || measured.liquidityFacts.some(fact => fact.clipped || !/\$(?:\d|—)|Unavailable/i.test(fact.text))) {
        failures.push(`${viewport.name}: exact cash facts are missing or clipped `
          + `(${JSON.stringify(measured.liquidityFacts)})`);
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `mobile Home violates its single-scroller contract:\n  ${failures.join('\n  ')}`);
});

test('mobile Home exposes every visible action as a real touch target', async () => {
  const failures = [];
  for (const viewport of VIEWPORTS.filter(row => row.width <= PHONE_WIDTH)) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, {
        positions: 12, shares: 2, workingIdeas: 20, mixedIdeas: true, scout: 'complete'
      });
      await bootHome(page);
      const targets = await page.evaluate(() => Array.from(document.querySelectorAll(
        '#stage button, #stage [role="button"], #stage a[href], #stage input, #stage select'))
        .filter(node => {
          const style = getComputedStyle(node);
          const box = node.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' && !node.disabled
            && box.width > 0 && box.height > 0;
        }).map(node => {
          const box = node.getBoundingClientRect();
          return {
            label: (node.getAttribute('aria-label') || node.textContent || node.value || '')
              .trim().replace(/\s+/g, ' ').slice(0, 52),
            width: Math.round(box.width), height: Math.round(box.height)
          };
        }));
      targets.filter(target => target.width < 40 || target.height < 40).forEach(target => {
        failures.push(`${viewport.name}: "${target.label}" is ${target.width}x${target.height}`);
      });
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `mobile Home still contains sub-40px actions:\n  ${failures.join('\n  ')}`);
});

test('mobile Scout rows keep strategy, verdict, EV, and action in separate readable cells', async () => {
  const failures = [];
  for (const viewport of VIEWPORTS.filter(row => row.width <= PHONE_WIDTH)) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, { positions: 4, workingIdeas: 5, scout: 'complete' });
      await bootHome(page);
      const collisions = await page.evaluate(() =>
        Array.from(document.querySelectorAll('#riskMain .opportunityrow')).flatMap((row, rowIndex) => {
          const children = Array.from(row.children).filter(node => getComputedStyle(node).display !== 'none');
          const hits = [];
          for (let left = 0; left < children.length; left += 1) {
            for (let right = left + 1; right < children.length; right += 1) {
              const a = children[left].getBoundingClientRect();
              const b = children[right].getBoundingClientRect();
              const overlapX = Math.min(a.right, b.right) - Math.max(a.left, b.left);
              const overlapY = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
              if (overlapX > 2 && overlapY > 2) {
                hits.push(`row ${rowIndex + 1}: ${children[left].tagName} over `
                  + `${children[right].tagName} (${Math.round(overlapX)}x${Math.round(overlapY)})`);
              }
            }
          }
          return hits;
        }));
      if (collisions.length) failures.push(`${viewport.name}: ${collisions.join(', ')}`);
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `mobile Scout result facts paint over each other:\n  ${failures.join('\n  ')}`);
});

test('intermediate Home reveals Scout evidence and gives the compact market strip an overflow cue', async () => {
  const failures = [];
  for (const viewport of [
    { width: 1440, height: 900, name: '1440x900' },
    { width: 1000, height: 800, name: '1000x800' }
  ]) {
    const context = await browser.newContext({ viewport });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    try {
      await installWorld(page, { positions: 4, workingIdeas: 5, scout: 'complete' });
      await bootHome(page);
      const measured = await page.evaluate(() => {
        const laneFacts = Array.from(document.querySelectorAll(
          '#riskMain .scoutlane>small')).map(node => ({
          text: (node.textContent || '').trim(),
          clipped: node.scrollWidth > node.clientWidth + 1,
          whiteSpace: getComputedStyle(node).whiteSpace
        }));
        const host = document.querySelector('.thread:not(.zoomed) .thctx');
        const strip = host && host.querySelector('.mktstrip');
        const style = strip && getComputedStyle(strip);
        let reachable = true;
        if (strip && strip.scrollWidth > strip.clientWidth + 1) {
          strip.scrollLeft = strip.scrollWidth;
          reachable = strip.scrollLeft > 0;
        }
        return {
          laneFacts,
          hostOverflow: host && getComputedStyle(host).overflowX,
          stripOverflow: style && style.overflowX,
          mask: style && (style.maskImage || style.webkitMaskImage),
          reachable
        };
      });
      if (!measured.laneFacts.length
          || measured.laneFacts.some(row => !row.text || row.clipped || row.whiteSpace === 'nowrap')) {
        failures.push(`${viewport.name}: Scout evidence remains clipped `
          + `(${JSON.stringify(measured.laneFacts)})`);
      }
      if (viewport.width === 1000 && (measured.hostOverflow !== 'hidden'
          || measured.stripOverflow !== 'auto' || !measured.mask
          || measured.mask === 'none' || !measured.reachable)) {
        failures.push(`${viewport.name}: market strip has no honest reachable overflow cue `
          + `(${JSON.stringify(measured)})`);
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `intermediate Home hides evidence or command-strip overflow:\n  ${failures.join('\n  ')}`);
});

test('a full activity rail shows both sections and expands through one overflow owner', async () => {
  /*
   * Positions and Working ideas now share one outer overflow owner. The child roster must start at
   * its first card; scroll snapping or a header cannot hide that card inside the combined rail.
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
        const activity = document.querySelector('#activityBand');
        const header = document.querySelector('#book .rosterhd');
        const card = document.querySelector('#book .card[data-id]');
        const ideasHeader = document.querySelector('#univBand .lenshd');
        const idea = document.querySelector('#homePlansList [data-auth-plan-id]');
        if (!list || !activity || !header || !card) return null;
        const rail = activity.getBoundingClientRect();
        const inRail = node => {
          if (!node || getComputedStyle(node).display === 'none') return false;
          const box = node.getBoundingClientRect();
          return box.top >= rail.top - 1 && box.bottom <= rail.bottom + 1;
        };
        return { scrollTop: Math.round(list.scrollTop), activityScrollTop: Math.round(activity.scrollTop),
          covered: Math.round(header.getBoundingClientRect().bottom - card.getBoundingClientRect().top),
          positionHeadingVisible: inRail(header), positionRowVisible: inRail(card),
          ideasHeadingVisible: inRail(ideasHeader), ideaRowVisible: inRail(idea),
          railBounds: { top: Math.round(rail.top), bottom: Math.round(rail.bottom) },
          ideaBounds: idea ? {
            top: Math.round(idea.getBoundingClientRect().top),
            bottom: Math.round(idea.getBoundingClientRect().bottom)
          } : null,
          childScrollers: [list, document.getElementById('homePlansList')].filter(Boolean).filter(node => {
            const style = getComputedStyle(node);
            return /(auto|scroll)/.test(style.overflowY) && node.scrollHeight > node.clientHeight + 2;
          }).length
        };
      });
      if (measured && measured.covered > 2) {
        failures.push(`${viewport.name}: the header covers ${measured.covered}px of the first card `
          + `(child ${measured.scrollTop}, activity ${measured.activityScrollTop})`);
      }
      if (measured && measured.scrollTop !== 0) {
        failures.push(`${viewport.name}: child roster became a second scroller (${measured.scrollTop}px)`);
      }
      if (measured && measured.activityScrollTop !== 0) {
        failures.push(`${viewport.name}: activity rail hid its own heading at `
          + `${measured.activityScrollTop}px on first paint`);
      }
      if (viewport.width >= 1500 && measured
          && (!measured.positionHeadingVisible || !measured.positionRowVisible
            || !measured.ideasHeadingVisible || !measured.ideaRowVisible)) {
        failures.push(`${viewport.name}: the bounded rail does not show both section headings and a row `
          + `(${JSON.stringify(measured)})`);
      }
      if (measured && measured.childScrollers) {
        failures.push(`${viewport.name}: ${measured.childScrollers} child activity lists scroll`);
      }
      if (viewport.width >= 1500) {
        await page.locator('[data-activity-expand="positions"]').click();
        await page.locator('[data-activity-expand="ideas"]').click();
        const expanded = await page.evaluate(() => {
          const activity = document.getElementById('activityBand');
          const childScrollers = [document.getElementById('book'), document.getElementById('homePlansList')]
            .filter(Boolean).filter(node => {
              const style = getComputedStyle(node);
              return /(auto|scroll)/.test(style.overflowY) && node.scrollHeight > node.clientHeight + 2;
            }).length;
          return {
            positions: Array.from(document.querySelectorAll('#book .card[data-id]'))
              .filter(node => getComputedStyle(node).display !== 'none').length,
            ideas: Array.from(document.querySelectorAll('#homePlansList [data-auth-plan-id]'))
              .filter(node => getComputedStyle(node).display !== 'none').length,
            outerScrolls: activity.scrollHeight > activity.clientHeight + 2,
            childScrollers
          };
        });
        if (expanded.positions !== 12 || expanded.ideas !== 20
            || !expanded.outerScrolls || expanded.childScrollers) {
          failures.push(`${viewport.name}: expansion does not expose 12+20 rows through the one rail `
            + `(${JSON.stringify(expanded)})`);
        }
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `the full activity rail violates its one-owner disclosure contract:\n  ${failures.join('\n  ')}`);
});

test('Home share overflow is an action, not decorative unavailable inventory', async () => {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  try {
    await installWorld(page, { positions: 1, shares: 5, workingIdeas: 0, scout: 'idle' });
    await bootHome(page);
    assert.equal(await page.locator('#book .authsharerow:visible').count(), 2,
      'the resting activity rail keeps two representative share rows');
    const disclosure = page.locator('#book [data-share-expand]');
    await disclosure.waitFor();
    assert.match(await disclosure.textContent(), /Show 3 more share lines/);
    await disclosure.click();
    assert.equal(await page.locator('#book .authsharerow:visible').count(), 5,
      'the same activity owner reveals every share line');
    assert.equal(await page.locator('#book [data-share-expand]').count(), 0);
  } finally {
    await context.close();
  }
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
          '#activityBand, #riskMain .authbookpanel, #bookrisk .authbookpanel, '
          + '#chainBand .authbookpanel, #sectorBand .authbookpanel, #newsBand .authbookpanel'))
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
      overprints.forEach(hit => failures.push(`${viewport.name}: ${hit}`));
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
        if (el.closest('.overflowmeta')) return;            // layout disclosure, not a domain fact
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

test('mobile one-click New Idea is one complete contained reading column', async () => {
  const failures = [];
  for (const viewport of VIEWPORTS.filter(row => row.width <= PHONE_WIDTH)) {
    const context = await browser.newContext({ viewport });
    const page = await context.newPage();
    page.setDefaultTimeout(22000);
    try {
      await installWorld(page, {
        positions: 1, workingIdeas: 0, scout: 'idle',
        idea: { primaryLegCount: 4 }
      });
      await bootHome(page);
      await openNewIdea(page);
      const measured = await page.evaluate(() => {
        const host = document.querySelector('#decideStage .decgrid');
        const left = host && host.querySelector('.dcleft');
        const center = host && host.querySelector('.dccenter');
        const right = host && host.querySelector('.dcright');
        const outer = host && host.getBoundingClientRect();
        const first = left && left.getBoundingClientRect();
        const second = center && center.getBoundingClientRect();
        const third = right && right.getBoundingClientRect();
        const contains = box => !!(outer && box
          && box.left >= outer.left - 1 && box.right <= outer.right + 1);
        const style = host && getComputedStyle(host);
        return {
          pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
          display: style && style.display,
          direction: style && style.flexDirection,
          contained: contains(first) && contains(second) && contains(third),
          ordered: !!(first && second && third
            && first.bottom <= second.top + 1 && second.bottom <= third.top + 1),
          ready: window.decide && window.decide.backendPhase,
          candidateId: window.decide && window.decide.candId,
          payoff: !!document.querySelector('#decideStage #decPay path[d]'),
          paths: !!document.querySelector('#decideStage #mcFan path[d]'),
          legs: document.querySelectorAll('#decideStage .declegpanel .legr').length,
          action: !!document.querySelector('#decideStage .decdock [data-dec="review"]')
        };
      });
      if (measured.pageOverflow > 1 || measured.display !== 'flex'
          || measured.direction !== 'column' || !measured.contained || !measured.ordered
          || measured.ready !== 'ready' || !measured.candidateId || !measured.payoff
          || !measured.paths || measured.legs !== 4 || !measured.action) {
        failures.push(`${viewport.name}: ${JSON.stringify(measured)}`);
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `one-click New Idea lost a complete contained mobile reading flow:\n  ${failures.join('\n  ')}`);
});

for (const viewport of VIEWPORTS) {
  test(`canonical New Idea remains complete through package, scenario, and review states at ${viewport.name}`,
    async () => {
      const context = await browser.newContext({
        viewport: { width: viewport.width, height: viewport.height }
      });
      const page = await context.newPage();
      page.setDefaultTimeout(22000);
      const pageErrors = [];
      page.on('pageerror', error => pageErrors.push(error.stack || error.message));
      const fixture = fixtures.newIdea.documents({ primaryLegCount: 4 });
      const failures = [];
      try {
        await installWorld(page, {
          positions: 4,
          workingIdeas: 5,
          scout: 'idle',
          idea: { primaryLegCount: 4 }
        });
        await bootHome(page);
        await openNewIdea(page);
        await page.screenshot({
          path: path.join(SHOTS, `new-idea-${viewport.name}.png`),
          fullPage: viewport.width <= DOCUMENT_LAYOUT_WIDTH
        });

        failures.push(...await inspectNewIdea(
          page, viewport, fixture.primary, '4-leg · unpinned',
          { requireNewsAction: true }));

        const scenario = page.locator('#decideStage .scenpanel .srow').nth(1);
        if (!await scenario.count()) {
          failures.push(`${viewport.name} · 4-leg · pinned: no scenario tile is actionable`);
        } else {
          await scenario.click();
          try {
            await page.waitForFunction(() => window.decide && window.decide.animation
              && document.querySelector('.scenpanel .srow.pinned')
              && document.querySelector('.scenpanel .srow-ctl'), null, { timeout: 12000 });
          } catch (error) {
            failures.push(`${viewport.name} · 4-leg · pinned: scenario conditioning did not settle: `
              + error.message.split('\n')[0]);
          }
          await page.screenshot({
            path: path.join(SHOTS, `new-idea-pinned-${viewport.name}.png`),
            fullPage: viewport.width <= DOCUMENT_LAYOUT_WIDTH
          });
          failures.push(...await inspectNewIdea(
            page, viewport, fixture.primary, '4-leg · pinned',
            { requirePinned: true }));
        }

        const review = page.locator('#decideStage [data-dec="review"]:not([disabled])').last();
        if (!await review.count()) {
          failures.push(`${viewport.name} · 4-leg · review: no enabled Review action`);
        } else {
          await review.click();
          try {
            await page.waitForSelector('#decideStage .reviewexec', { state: 'visible' });
          } catch (error) {
            failures.push(`${viewport.name} · 4-leg · review: review surface did not open`);
          }
          await page.screenshot({
            path: path.join(SHOTS, `new-idea-review-${viewport.name}.png`),
            fullPage: viewport.width <= DOCUMENT_LAYOUT_WIDTH
          });
          failures.push(...await inspectNewIdea(
            page, viewport, fixture.primary, '4-leg · review',
            { requireReview: true }));
          const cancel = page.locator('#decideStage [data-dec="cancel"]');
          if (await cancel.count()) await cancel.click();
        }

        try {
          await selectIdeaCandidate(page, fixture.alternate);
        } catch (error) {
          failures.push(`${viewport.name} · 1-leg · unpinned: alternate package did not settle: `
            + error.message.split('\n')[0]);
        }
        if (await page.evaluate(id => window.decide && window.decide.candId === id,
          fixture.alternate.id)) {
          failures.push(...await inspectNewIdea(
            page, viewport, fixture.alternate, '1-leg · unpinned'));
        }

        pageErrors.forEach(error =>
          failures.push(`${viewport.name}: browser error: ${String(error).split('\n')[0]}`));
        assert.deepEqual(failures, [],
          `canonical New Idea violates the visual/interaction contract:\n  ${failures.join('\n  ')}`);
      } finally {
        await context.close();
      }
    });
}

test('desktop Market receipts own disjoint rows across complete and degraded lanes', async () => {
  const viewports = [
    { width: 1920, height: 1080, name: '1920x1080' },
    { width: 2000, height: 963, name: '2000x963' },
    { width: 2560, height: 1440, name: '2560x1440' }
  ];
  const states = [
    { name: 'ready', market: {} },
    { name: 'stale', market: { quote: 'stale', history: 'stale',
      chain: 'stale', news: 'stale' } },
    /* Keep the valuation anchor available while independently degrading the three receipts this
       layout owns. A missing quote is a different candidate-entry gate, not a Market-row geometry
       state, and would correctly prevent the canonical idea from mounting. */
    { name: 'missing', market: { history: 'missing',
      chain: 'missing', news: 'missing' } },
    { name: 'provider-error', market: { history: 'error',
      chain: 'error', news: 'error' } }
  ];
  const failures = [];
  for (const viewport of viewports) {
    for (const state of states) {
      const context = await browser.newContext({ viewport });
      const page = await context.newPage();
      page.setDefaultTimeout(22000);
      try {
        const installed = await installWorld(page, {
          positions: 4,
          workingIdeas: 5,
          scout: 'idle',
          idea: { primaryLegCount: 4 }
        });
        await bootHome(page);
        await openNewIdea(page);
        /* Entry pricing needs a viable quote and chain. Exercise degraded Market receipts after
           the canonical idea is mounted: these reads are independent and must be able to fail
           without either unmounting the analysis or painting through their neighboring rows. */
        if (state.name !== 'ready') {
          applyIdeaMarketState(installed.idea, state.market);
          await page.evaluate(symbol => {
            delete window.HIST_STORE[window.histKey(symbol)];
            window.ensureHist(symbol);
            window.decMarketCtx(true);
            window.patchDecMarketPanel();
          }, fixtures.wire.GOLDEN_SYMBOL);
        }
        await page.waitForFunction(() => {
          const history = window.historySlot && window.historySlot(window.decide && window.decide.sym);
          const contextState = window.decide && window.decide._marketCtx;
          return history && history.phase !== 'loading'
            && contextState && contextState.phase !== 'loading';
        });
        await page.waitForTimeout(80);
        const measured = await page.evaluate(() => {
          const host = document.querySelector('#decideStage .marketlens');
          const box = element => {
            if (!element) return null;
            const style = getComputedStyle(element);
            const value = element.getBoundingClientRect();
            if (style.display === 'none' || style.visibility === 'hidden'
                || value.width < 1 || value.height < 1) return null;
            return {
              left: value.left, top: value.top, right: value.right, bottom: value.bottom,
              width: value.width, height: value.height
            };
          };
          const entries = selector => Array.from(host.querySelectorAll(selector)).map(element => ({
            text: (element.textContent || '').replace(/\s+/g, ' ').trim(),
            rect: box(element)
          })).filter(entry => entry.rect);
          const columns = Array.from(host.querySelectorAll('.marketctx>.marketcol')).map(element => {
            const rect = box(element);
            return {
              rect,
              clientHeight: element.clientHeight,
              scrollHeight: element.scrollHeight,
              children: Array.from(element.children).map(child => ({
                text: (child.textContent || '').replace(/\s+/g, ' ').trim(),
                rect: box(child)
              })).filter(entry => entry.rect)
            };
          });
          return {
            scenario: box(document.querySelector('#decideStage .scenpanel')),
            market: box(host),
            history: box(host.querySelector('.histwrap')),
            marketContext: box(host.querySelector('.marketctx')),
            historyChildren: entries('.histwrap>.histctl, .histwrap>.histchart, '
              + '.histwrap>.histstrip, .histwrap>.histread, .histwrap>.histunavailable'),
            headings: entries('.marketctx .lenshd.sub'),
            columns,
            historyRead: (host.querySelector('.histread')?.textContent || '').trim(),
            historyUnavailable: !!box(host.querySelector('.histunavailable')),
            executionReceipt: (host.querySelector('.packagebook .pricereceipt')?.textContent || '')
              .replace(/\s+/g, ' ').trim(),
            chainRows: host.querySelectorAll('.packagebook .authchainrow').length,
            visibleNews: Array.from(host.querySelectorAll('[data-news-item]')).filter(element =>
              box(element)).length,
            totalNews: host.querySelectorAll('[data-news-item]').length,
            newsDisclosure: (host.querySelector('.authnews [data-news-disclosure]')?.textContent || '').trim(),
            marketNotices: Array.from(host.querySelectorAll('.marketctx .authslotnotice'))
              .map(element => (element.textContent || '').replace(/\s+/g, ' ').trim())
          };
        });
        const label = `${viewport.name} · ${state.name}`;
        if (!measured.scenario || !measured.market) {
          failures.push(`${label}: scenario or Market panel is absent`);
          continue;
        }
        if (measured.scenario.bottom > measured.market.top + 1) {
          failures.push(`${label}: How it reacts overlaps Market by `
            + `${Math.round(measured.scenario.bottom - measured.market.top)}px`);
        }
        if (!measured.history || !measured.marketContext) {
          failures.push(`${label}: history or market context is absent`);
          continue;
        }
        if (measured.history.bottom > measured.marketContext.top + 1) {
          failures.push(`${label}: market context begins `
            + `${Math.round(measured.history.bottom - measured.marketContext.top)}px before `
            + 'the history owner ends');
        }
        measured.historyChildren.forEach(historyChild => {
          if (historyChild.rect.bottom > measured.history.bottom + 1
              || historyChild.rect.top < measured.history.top - 1) {
            failures.push(`${label}: history child "${historyChild.text.slice(0, 48)}" `
              + 'paints outside the history owner');
          }
          measured.headings.forEach(heading => {
            const overlapX = Math.min(heading.rect.right, historyChild.rect.right)
              - Math.max(heading.rect.left, historyChild.rect.left);
            const overlapY = Math.min(heading.rect.bottom, historyChild.rect.bottom)
              - Math.max(heading.rect.top, historyChild.rect.top);
            if (overlapX > 1 && overlapY > 1) {
              failures.push(`${label}: "${heading.text}" overpaints history child `
                + `"${historyChild.text.slice(0, 40)}" `
                + `(${Math.round(overlapX)}x${Math.round(overlapY)}px)`);
            }
          });
        });
        if (measured.marketContext.bottom > measured.market.bottom + 1) {
          failures.push(`${label}: market context escapes the Market panel by `
            + `${Math.round(measured.marketContext.bottom - measured.market.bottom)}px`);
        }
        if (measured.headings.length !== 2
            || !measured.headings.some(row => /Execution evidence/i.test(row.text))
            || !measured.headings.some(row => /Research & news/i.test(row.text))) {
          failures.push(`${label}: both context headings are not visible and distinct`);
        }
        measured.columns.forEach((column, index) => {
          if (!column.rect) {
            failures.push(`${label}: market context column ${index + 1} is absent`);
            return;
          }
          column.children.forEach(child => {
            if (child.rect.left < column.rect.left - 1 || child.rect.right > column.rect.right + 1
                || child.rect.top < column.rect.top - 1
                || child.rect.bottom > column.rect.bottom + 1) {
              failures.push(`${label}: context child "${child.text.slice(0, 48)}" `
                + `escapes column ${index + 1}`);
            }
          });
        });
        if (state.name === 'ready' || state.name === 'stale') {
          if (!measured.historyRead) failures.push(`${label}: stored-history receipt is blank`);
          if (!/Executable now/i.test(measured.executionReceipt) || measured.chainRows !== 2) {
            failures.push(`${label}: exact execution receipt or its two nearby chain rows are lost`);
          }
          if (measured.visibleNews !== 2 || measured.totalNews !== 20
              || !/\+18 more headlines/i.test(measured.newsDisclosure)) {
            failures.push(`${label}: compact news does not retain 2 visible / 20 total headlines `
              + `(visible ${measured.visibleNews}, total ${measured.totalNews}, `
              + `action "${measured.newsDisclosure}")`);
          }
        } else {
          if (!measured.historyUnavailable) {
            failures.push(`${label}: missing/failed history has no settled unavailable receipt`);
          }
          /* The selected package keeps its already-captured execution book when an ambient
             refresh fails; only the independently unavailable news lane needs a new notice. */
          if (measured.marketNotices.length < 1) {
            failures.push(`${label}: the unavailable context has no visible reason`);
          }
        }

        /* The disclosure keeps the resting panel compact, but all headlines must remain
           reachable through its one intentional list scroller. One representative viewport is
           sufficient; the disjoint-row matrix above already covers the other geometries. */
        if (viewport.name === '1920x1080' && state.name === 'ready') {
          await page.locator('#decideStage .marketlens [data-news-disclosure]').click();
          const expanded = await page.evaluate(() => {
            const owner = document.querySelector('#decideStage .marketlens .authnews');
            const items = Array.from(owner.querySelectorAll('[data-news-item]'));
            owner.scrollTop = owner.scrollHeight;
            const ownerBox = owner.getBoundingClientRect();
            const lastBox = items[items.length - 1].getBoundingClientRect();
            return {
              visible: items.filter(item => getComputedStyle(item).display !== 'none').length,
              scrollable: owner.scrollHeight > owner.clientHeight + 1,
              lastReachable: lastBox.bottom <= ownerBox.bottom + 1
                && lastBox.top >= ownerBox.top - 1
            };
          });
          if (expanded.visible !== 20 || !expanded.scrollable || !expanded.lastReachable) {
            failures.push(`${label}: expanded news does not make all 20 headlines reachable `
              + `through one list scroller (${JSON.stringify(expanded)})`);
          }
        }
      } catch (error) {
        failures.push(`${viewport.name} · ${state.name}: ${error.message.split('\n')[0]}`);
      } finally {
        await context.close();
      }
    }
  }
  assert.deepEqual(failures, [],
    `Market/history sibling ownership failed:\n  ${failures.join('\n  ')}`);
});

test('captured package and nearby chain keep every exact fact inside one non-scrolling owner', async () => {
  const viewports = [
    { width: 2000, height: 963, name: '2000x963' },
    { width: 1920, height: 1080, name: '1920x1080' },
    { width: 2560, height: 1440, name: '2560x1440' },
    { width: 1440, height: 900, name: '1440x900' },
    { width: 1000, height: 800, name: '1000x800' },
    { width: 390, height: 844, name: '390x844' }
  ];
  const failures = [];
  for (const viewport of viewports) {
    const context = await browser.newContext({ viewport });
    const page = await context.newPage();
    page.setDefaultTimeout(22000);
    try {
      await installWorld(page, {
        positions: 4,
        workingIdeas: 5,
        scout: 'idle',
        idea: { primaryLegCount: 4 }
      });
      await bootHome(page);
      await openNewIdea(page);
      await page.waitForFunction(() => {
        const market = window.decide && window.decide._marketCtx;
        return market && market.phase !== 'loading'
          && document.querySelectorAll('#decideStage .packagebooknear .authchainrow').length === 2;
      });
      await page.waitForTimeout(80);
      const measured = await page.evaluate(() => {
        const owner = document.querySelector('#decideStage .marketlens .packagebook');
        const receipt = owner && owner.querySelector('.pricereceipt');
        const near = owner && owner.querySelector('.packagebooknear');
        const rect = element => {
          const value = element && element.getBoundingClientRect();
          return value && {
            left: value.left, top: value.top, right: value.right, bottom: value.bottom,
            width: value.width, height: value.height
          };
        };
        const inside = (child, parent) => child.left >= parent.left - 1
          && child.right <= parent.right + 1
          && child.top >= parent.top - 1
          && child.bottom <= parent.bottom + 1;
        const exactFacts = owner ? Array.from(owner.querySelectorAll(
          '.prhd .hint, .prk, .prv, .prmeta span, .authchainhead>*, '
          + '.authchainrow>*, .compactchainreceipt'
        )) : [];
        const ownerRect = rect(owner);
        const receiptRect = rect(receipt);
        const nearRect = rect(near);
        const children = near ? Array.from(near.children).map(element => ({
          text: (element.textContent || '').replace(/\s+/g, ' ').trim(),
          rect: rect(element)
        })) : [];
        return {
          owner: ownerRect,
          receipt: receiptRect,
          near: nearRect,
          ownerOverflowY: owner && getComputedStyle(owner).overflowY,
          nearOverflowY: near && getComputedStyle(near).overflowY,
          childrenInside: !!nearRect && children.every(child =>
            child.rect && inside(child.rect, nearRect)),
          clippedFacts: exactFacts.filter(element =>
            element.scrollWidth > element.clientWidth + 1
              || element.scrollHeight > element.clientHeight + 1).map(element => ({
            text: (element.textContent || '').replace(/\s+/g, ' ').trim(),
            clientWidth: element.clientWidth,
            scrollWidth: element.scrollWidth,
            clientHeight: element.clientHeight,
            scrollHeight: element.scrollHeight
          })),
          text: (owner && owner.textContent || '').replace(/\s+/g, ' ').trim()
        };
      });
      const label = viewport.name;
      if (!measured.owner || !measured.receipt || !measured.near) {
        failures.push(`${label}: captured receipt or nearby-chain owner is absent`);
        continue;
      }
      if (!measured.childrenInside) {
        failures.push(`${label}: a nearby-chain child paints outside its owner`);
      }
      if (measured.clippedFacts.length) {
        failures.push(`${label}: exact facts are clipped ${JSON.stringify(measured.clippedFacts)}`);
      }
      if (['auto', 'scroll'].includes(measured.ownerOverflowY)
          || ['auto', 'scroll'].includes(measured.nearOverflowY)) {
        failures.push(`${label}: package evidence introduced a nested vertical scroller`);
      }
      for (const expected of [
        'Option net', 'Package net', 'Opening fees', 'After fees',
        'Executable now', 'Call bid / ask', 'Put bid / ask'
      ]) {
        if (!measured.text.includes(expected)) {
          failures.push(`${label}: exact package/chain fact "${expected}" is absent`);
        }
      }
    } catch (error) {
      failures.push(`${viewport.name}: ${error.message.split('\n')[0]}`);
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `Captured-package geometry failed:\n  ${failures.join('\n  ')}`);
});

test('the expected-move overlay draws the backend range, never a reusable client cone', async () => {
  const failures = [];
  for (const state of ['ready', 'stale', 'missing', 'error']) {
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await context.newPage();
    page.setDefaultTimeout(22000);
    try {
      await installWorld(page, {
        positions: 1,
        workingIdeas: 0,
        scout: 'idle',
        idea: { primaryLegCount: 4, expectedMove: state }
      });
      await bootHome(page);
      await openNewIdea(page);
      await page.waitForFunction(() => Object.keys(window.EM_STORE || {}).length > 0
        && Object.values(window.EM_STORE).every(slot => slot.phase !== 'loading'));
      await page.waitForTimeout(80);
      const rendered = await page.evaluate(() => {
        const chart = document.querySelector('#decideStage .marketlens [data-hist-svg]');
        const band = chart && chart.querySelector('[data-expected-move-band]');
        const rails = chart ? Array.from(chart.querySelectorAll('[data-expected-move-rail]')) : [];
        return {
          bandTag: band && band.tagName.toLowerCase(),
          rails: rails.map(line => ({
            tag: line.tagName.toLowerCase(),
            y1: line.getAttribute('y1'),
            y2: line.getAttribute('y2')
          })),
          labels: chart ? Array.from(chart.querySelectorAll('.expectedmovelabel'))
            .map(node => node.textContent.trim()) : [],
          slotPhases: Object.values(window.EM_STORE || {}).map(slot => slot.phase)
        };
      });
      if (state === 'ready') {
        if (rendered.bandTag !== 'rect' || rendered.rails.length !== 3
            || rendered.rails.some(rail => rail.tag !== 'line' || rail.y1 !== rail.y2)) {
          failures.push(`ready: expected a rectangular range plus three horizontal receipt rails, got `
            + JSON.stringify(rendered));
        }
        const label = rendered.labels.join(' ');
        for (const expected of ['$274.60', '$251.35', '$229.75']) {
          if (!label.includes(expected)) failures.push(`ready: exact receipt price ${expected} is absent`);
        }
      } else if (rendered.bandTag || rendered.rails.length || rendered.labels.length) {
        failures.push(`${state}: degraded expected-move evidence still draws a market range `
          + JSON.stringify(rendered));
      }
    } finally {
      await context.close();
    }
  }
  assert.deepEqual(failures, [],
    `expected-move rendering diverges from its server receipt:\n  ${failures.join('\n  ')}`);
});

test('narrow history charts prioritize exact contract rails within their label capacity', async () => {
  const context = await browser.newContext({ viewport: { width: 320, height: 700 } });
  const page = await context.newPage();
  page.setDefaultTimeout(22000);
  try {
    await installWorld(page, {
      positions: 1, workingIdeas: 0, scout: 'idle',
      idea: { primaryLegCount: 4, expectedMove: 'ready' }
    });
    await bootHome(page);
    await openNewIdea(page);
    const labels = await page.evaluate(() => {
      const chart = document.querySelector('#decideStage .marketlens [data-hist-svg]');
      if (!chart) return { count: 0, retainedContract: false, outOfBounds: ['missing chart'] };
      const height = chart.viewBox.baseVal.height;
      const nodes = Array.from(chart.querySelectorAll(
        '[data-expected-move-label], [data-history-rail]'));
      return {
        count: nodes.length,
        retainedContract: nodes.some(node => node.hasAttribute('data-history-rail')),
        outOfBounds: nodes.filter(node => {
          const y = Number(node.getAttribute('y'));
          return !Number.isFinite(y) || y < 0 || y > height;
        }).map(node => node.textContent.trim())
      };
    });
    assert.ok(labels.count > 0 && labels.count <= 3,
      `320px history rail renders ${labels.count} competing labels`);
    assert.equal(labels.retainedContract, true,
      'narrow label priority dropped every strike/break-even receipt');
    assert.deepEqual(labels.outOfBounds, [], 'a narrow history label is outside the SVG');
  } finally {
    await context.close();
  }
});
