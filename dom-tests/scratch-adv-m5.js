'use strict';
/* ADVERSARY harness for the M5 Scout findings. Serves src/main/resources/public over a real
   http server, streams a REAL NDJSON /api/research/scout with paced frames, and measures. */

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const fixtures = require('./fixtures');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');
const SHOTS = path.join(__dirname, 'shots', 'adv-m5');
fs.mkdirSync(SHOTS, { recursive: true });

const SCOUT_SYMBOLS = ['MU', 'XOM', 'JPM', 'PFE', 'KO'];

/* Five reachable backend states, one per row. */
const DECISION_RANKING = [
  { symbol: 'MU', strategy: 'CREDIT_PUT_SPREAD', decisionScore: 84, economicVerdict: 'FAVORABLE',
    qualification: 'QUALIFIED', dataCompleteness: { status: 'OBSERVED_COMPLETE' },
    identity: { key: 'MU|CREDIT_PUT_SPREAD|1' }, evaluationId: 'ev-mu',
    bookImpacts: [{ accountId: 'acct-practice', status: 'IMPROVES' }] },
  { symbol: 'XOM', strategy: 'CREDIT_PUT_SPREAD', decisionScore: 71, economicVerdict: 'FAVORABLE',
    qualification: 'COMPARE_CAREFULLY', dataCompleteness: { status: 'NON_OBSERVED_INPUTS' },
    identity: { key: 'XOM|CREDIT_PUT_SPREAD|1' }, evaluationId: 'ev-xom',
    bookImpacts: [{ accountId: 'acct-practice', status: 'WORSENS' }] },
  { symbol: 'JPM', strategy: 'CREDIT_PUT_SPREAD', decisionScore: 60, economicVerdict: 'UNFAVORABLE',
    qualification: 'UNFAVORABLE', dataCompleteness: { status: 'OBSERVED_COMPLETE' },
    identity: { key: 'JPM|CREDIT_PUT_SPREAD|1' }, evaluationId: 'ev-jpm',
    bookImpacts: [{ accountId: 'acct-practice', status: 'WORSENS' }] },
  { symbol: 'PFE', strategy: 'IRON_CONDOR', decisionScore: 51, economicVerdict: 'UNAVAILABLE',
    qualification: 'ECONOMICS_UNAVAILABLE', dataCompleteness: { status: 'PARTIAL_EVIDENCE' },
    identity: { key: 'PFE|IRON_CONDOR|1' }, evaluationId: 'ev-pfe',
    bookImpacts: [{ accountId: 'acct-practice', status: 'UNCHANGED' }] },
  { symbol: 'KO', strategy: 'COVERED_CALL', decisionScore: 44, economicVerdict: 'FAVORABLE',
    qualification: 'ACCOUNT_BLOCKED', dataCompleteness: { status: 'OBSERVED_COMPLETE' },
    identity: { key: 'KO|COVERED_CALL|1' }, evaluationId: 'ev-ko',
    bookImpacts: [{ accountId: 'acct-practice', status: 'BLOCKED' }] }
];

function pickFor(row, ev) {
  return {
    symbol: row.symbol,
    opportunity: { score: row.decisionScore },
    bestIdea: {
      available: true, family: row.strategy,
      displayName: row.strategy === 'CREDIT_PUT_SPREAD' ? 'Bull put spread'
        : row.strategy === 'IRON_CONDOR' ? 'Iron condor' : 'Covered call',
      economicVerdict: row.economicVerdict, horizon: 'month',
      realizedVolEvAfterCostsCents: ev,
      evaluationId: row.evaluationId, resultKey: row.identity.key
    }
  };
}

const SCOUT_RESULT = {
  searched: 5,
  picks: DECISION_RANKING.map((row, i) => pickFor(row, [16100, 8200, -4300, null, 2100][i])),
  frontier: {
    universe: { source: 'CURATED', label: 'Cross-sector opportunity frontier', symbols: SCOUT_SYMBOLS },
    destinationAccountId: 'acct-practice',
    decisionRanking: DECISION_RANKING,
    compensationRanking: [
      { symbol: 'MU', strategy: 'CREDIT_PUT_SPREAD', score: 62.4, evaluationId: 'ev-mu',
        components: [{ name: 'Annualized premium yield', weight: 0.35, value: 1.0,
          note: '4837.5%/yr on the risk capital, IF repeatable' }] },
      { symbol: 'XOM', strategy: 'CREDIT_PUT_SPREAD', score: 58.1, evaluationId: 'ev-xom',
        components: [{ name: 'Annualized premium yield', weight: 0.35, value: 1.0,
          note: '5120.0%/yr on the risk capital, IF repeatable' }] }
    ],
    notes: ['Decision economics and compensation are independent rankings.']
  }
};

function progressFrame(n) {
  return { type: 'progress', progress: {
    phase: 'SCANNING', phaseCompleted: n, phaseTotal: 40, symbol: SCOUT_SYMBOLS[n % 5],
    counts: { universeConsidered: n, evidenceEligible: n, packagesEvaluated: n * 3,
      rowsRetained: Math.min(5, Math.floor(n / 4)) },
    message: null,
    pick: n % 4 === 0 && n > 0 ? SCOUT_RESULT.picks[Math.min(4, Math.floor(n / 4) - 1)] : null
  } };
}

function contentType(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.css')) return 'text/css; charset=utf-8';
  if (file.endsWith('.svg')) return 'image/svg+xml';
  return 'application/octet-stream';
}

/* frameGapMs=null → never completes (endless stream, for the focus probe). */
let STREAM = { gapMs: 180, frames: 40, complete: true };

function serve(req, res) {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.pathname === '/api/research/scout') {
    res.writeHead(200, { 'Content-Type': 'application/x-ndjson', 'Cache-Control': 'no-store' });
    let n = 0;
    const timer = setInterval(() => {
      if (res.writableEnded) { clearInterval(timer); return; }
      n += 1;
      res.write(JSON.stringify(progressFrame(n)) + '\n');
      if (n >= STREAM.frames) {
        clearInterval(timer);
        if (STREAM.complete) { res.write(JSON.stringify({ type: 'complete', result: SCOUT_RESULT }) + '\n'); res.end(); }
      }
    }, STREAM.gapMs);
    req.on('close', () => clearInterval(timer));
    return;
  }
  const pathname = url.pathname === '/' ? '/index.html' : decodeURIComponent(url.pathname);
  const file = path.resolve(PUBLIC, `.${pathname}`);
  if (file !== PUBLIC && !file.startsWith(`${PUBLIC}${path.sep}`)) { res.writeHead(403).end('forbidden'); return; }
  fs.readFile(file, (error, body) => {
    if (error) { res.writeHead(error.code === 'ENOENT' ? 404 : 500).end(error.message); return; }
    res.writeHead(200, { 'Content-Type': contentType(file), 'Cache-Control': 'no-store' });
    res.end(body);
  });
}

async function installWorld(page, state) {
  const world = fixtures.desk(state);
  const universe = {
    symbols: SCOUT_SYMBOLS.map(s => ({ symbol: s, name: s + ' Inc' })),
    scout: { symbols: SCOUT_SYMBOLS },
    active: { symbols: SCOUT_SYMBOLS.slice(0, 3) },
    sectors: [
      { key: 'SEMICONDUCTORS', label: 'Semiconductors', symbols: ['MU'] },
      { key: 'ENERGY', label: 'Energy', symbols: ['XOM'] },
      { key: 'FINANCIALS', label: 'Financials', symbols: ['JPM'] },
      { key: 'HEALTHCARE', label: 'Healthcare', symbols: ['PFE'] },
      { key: 'STAPLES', label: 'Consumer staples', symbols: ['KO'] }
    ]
  };
  await page.route('**/api/**', async route => {
    const at = new URL(route.request().url()).pathname;
    if (at === '/api/research/scout') { await route.continue(); return; }
    const research = at.match(/^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news))?$/);
    let body;
    if (at === '/api/config') body = { fixturesOnly: false, world: 'observed', marketLane: 'OBSERVED', scenarioMode: false };
    else if (at === '/api/status') body = { ok: true, status: 'READY', fixturesOnly: false };
    else if (at === '/api/world') body = { world: 'observed', revision: 1, epoch: 1 };
    else if (at === '/api/workspace') body = { rev: 1, updatedAt: '2026-07-25T12:00:00Z', supportedVersion: 1, world: 'observed', marketLane: 'OBSERVED', accountId: 'acct-practice', context: null, transition: null, unreadable: null };
    else if (at === '/api/account') body = { account: { id: 'acct-practice', cashCents: 5000000, buyingPowerCents: 9700000 }, ledger: [] };
    else if (at === '/api/portfolio/summary') body = world.book.summary;
    else if (at === '/api/portfolio/heat') body = world.book.heat;
    else if (at === '/api/portfolio/greeks') body = world.book.greeks;
    else if (at === '/api/portfolio/book-risk') body = world.book.bookRisk;
    else if (at === '/api/portfolio/accounts') body = [{ id: 'acct-practice', name: 'Practice ••••0001' }];
    else if (at === '/api/positions') body = world.book.positionBook;
    else if (at === '/api/trades') body = world.book.tradePage;
    else if (at === '/api/plans') body = world.plans;
    else if (at === '/api/plans/portfolio') body = world.planPortfolio;
    else if (at === '/api/universe') body = universe;
    else if (at === '/api/strategies') body = { catalog: [] };
    else if (research) { const lane = research[2] || 'research'; body = world.market[lane] !== undefined ? world.market[lane] : world.market.research; }
    else if (at.startsWith('/api/trades/')) body = world.book.tradeDetails[decodeURIComponent(at.slice('/api/trades/'.length))] || { trade: null };
    else body = {};
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  return world;
}

async function bootHome(page, url) {
  await page.goto(url);
  await page.waitForFunction(() => window.DeskBackend != null && window.WORKSPACE != null);
  await page.waitForSelector('#board');
  await page.waitForFunction(() => {
    const b = document.getElementById('board');
    return b != null && b.getBoundingClientRect().height > 40;
  });
  await page.waitForTimeout(200);
}

async function declare(page) {
  await page.locator('[data-auth-scout-goal="INCOME"]').click();
  await page.locator('[data-auth-workbench-view="Neutral"]').click();
  await page.locator('[data-auth-workbench-horizon="45 trading days"]').click();
  await page.locator('[data-auth-workbench-risk="Balanced"]').click();
  await page.waitForFunction(() => window.HOME_SCOUT?.goal && window.homeIdea?.view
    && window.homeIdea?.horizon && window.homeIdea?.riskMode);
}

module.exports = { PUBLIC, SHOTS, SCOUT_SYMBOLS, SCOUT_RESULT, DECISION_RANKING,
  serve, installWorld, bootHome, declare, chromium, http,
  setStream(next) { STREAM = Object.assign({}, STREAM, next); } };
