/*
 * TRADER/OWN Journey C: an adopted Plan renders the current-package and
 * campaign-anchored questions beside one another from the single Manage payload.
 * The backend composer is covered by PlanAdoptionReviewServiceTest; this focused
 * browser acceptance pins the two experience levels and the 2560/mobile reflow.
 *
 * Run: node --test adoption-review.test.js
 */
'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const path = require('node:path');
const { chromium } = require('playwright');
const { freshDb } = require('./pgtest');

const PORT = process.env.PORT || '7193';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg, plan;
const pageErrors = [];
const serverErrors = [];
let manageReads = 0;
let duplicateAnalysisReads = 0;

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { if ((await fetch(`${BASE}/api/status`)).ok) return; } catch (_) { /* booting */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error('server did not start');
}

async function api(method, route, body) {
  const response = await fetch(BASE + route, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${method} ${route} -> ${response.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

function adoptionReview() {
  return {
    anchor: {
      receiptId: 'prec_adoption_exact_9',
      structureId: 'pstr_mu_exact_9',
      structureLabel: 'Existing AAPL position',
      accountId: 'acct_existing_ira',
      accountName: 'Existing-position IRA',
      symbol: 'AAPL',
      positionState: 'OPEN',
      authority: 'USER_ALLOCATED',
      marksAsOf: '2026-07-15T12:00:00Z',
      evidenceLevel: 'OBSERVED_BROKER',
      frozenObjectiveRevisionId: 'aor_accumulate_1',
      legs: [{
        legNo: 1, instrumentType: 'STOCK', action: 'BUY', symbol: 'AAPL',
        quantity: 100, multiplier: 1, openingFill: 120, mid: 109.50,
        priceAuthority: 'USER_REPORTED'
      }]
    },
    currentObjective: {
      id: 'aor_income_2', revisionNo: 2, objective: 'INCOME',
      direction: 'NON_DIRECTIONAL', assignmentPreference: 'ACCEPT'
    },
    freshEyes: {
      available: true,
      question: 'Would you open the exact position you still own today, ignoring sunk campaign cash?',
      basis: 'Current observed executable marks; sunk campaign cash excluded. The latest objective supplies coherence only.',
      analysis: {
        accountName: 'Existing-position IRA',
        marketLane: 'OBSERVED',
        preview: { entryNetPremiumCents: -1095000, maxLossCents: 1095000, popEntry: 0.54 },
        evaluation: {
          assessment: {
            economics: {
              verdict: 'MIXED', label: 'MIXED — price and purpose disagree',
              summary: 'Current observed pricing is judged without treating the old purchase price as new cash.',
              marketEvAfterCostsCents: -1200, realizedVolEvAfterCostsCents: 850,
              estimatedRoundTripFeesCents: 0,
              reasons: ['The forward market and realized-volatility lenses disagree.']
            },
            coherence: {
              verdict: 'MIXED',
              directionAssessment: 'The shares remain directional while the current objective is income.',
              durationAssessment: 'The stock has no contractual expiry.',
              reasons: ['Objective revision 2 is evaluated prospectively.']
            }
          },
          participation: {
            localParticipationBps: 10000,
            terminalUpsideCaptureBps: 10000,
            intervalStartCents: 9855,
            intervalEndCents: 12045,
            terminalDate: 'open-ended',
            localBasis: 'Current observed mark', terminalBasis: 'Share participation', regimePoints: []
          },
          capital: { economicCents: 1095000 },
          coverage: { inputs: { underlying: { level: 'OBSERVED_LIVE', detail: 'Broker executable mark' } }, limitations: [] }
        }
      }
    },
    campaignAnchored: {
      available: true,
      question: 'What has the explicitly linked whole campaign earned or lost, beside the frozen adopted baseline?',
      basis: "CampaignService accounting uses the full history of explicitly confirmed members. Adoption receipt prec_adoption_exact_9 anchors position identity only, not the campaign's arithmetic start. Tracked tax basis remains separate.",
      campaigns: [{
        id: 'camp_mu_1', title: 'AAPL accumulation campaign', status: 'ACTIVE', pendingCount: 0,
        accountObjectiveRevisionId: 'aor_accumulate_1',
        economicBasis: { available: true, perShareCents: 11250 },
        yield: {
          available: true, realizedPeriodPct: -8.75, headlinePeriodPct: 12.00,
          peakCommittedCapitalCents: 1200000, days: 45
        },
        counterfactuals: {
          cash: { available: true, deltaCents: -105000 },
          buyAndHold: { available: true, deltaCents: 22000 }
        }
      }]
    }
  };
}

function managePayload() {
  return {
    plan,
    decision: null,
    management: {
      activeTradeId: null,
      actions: [], reviews: [],
      trackedStructure: {
        structureId: 'pstr_mu_exact_9', label: 'Existing AAPL position',
        accountName: 'Existing-position IRA', symbol: 'AAPL', role: 'ADOPTION',
        positionState: 'OPEN', openQuantityRemaining: 100,
        marksAsOf: '2026-07-15T12:00:00Z',
        legs: [{ instrumentType: 'STOCK', symbol: 'AAPL', quantity: 100, fillPrice: 120 }]
      }
    },
    trade: null,
    adoptionReviews: [adoptionReview()]
  };
}

async function goManage() {
  await page.evaluate(id => {
    document.getElementById('app').setAttribute('data-ready', 'false');
    const hash = `#/plan/${id}/manage-review`;
    if (location.hash === hash) return App.render();
    location.hash = hash;
  }, plan.id);
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  try {
    await page.waitForSelector('.adoption-review', { timeout: 15000 });
  } catch (error) {
    const state = await page.evaluate(() => ({
      hash: location.hash,
      text: document.getElementById('app')?.innerText?.slice(0, 1200),
      routeError: document.getElementById('route-error')?.innerText || null
    }));
    throw new Error(`adoption review did not render: ${JSON.stringify(state)}; page errors: ${pageErrors.join(' | ')}; manage reads: ${manageReads}`, { cause: error });
  }
}

async function setLevel(level) {
  await page.evaluate(value => Learn.setLevel(value), level);
  await page.waitForFunction(value => Learn.currentLevel() === value, level);
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
}

before(async () => {
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: { ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true' },
    stdio: 'ignore'
  });
  await waitForServer();
  plan = await api('POST', '/api/plans', {
    clientRequestId: 'journey-c-browser', symbol: 'AAPL', intent: 'INCOME',
    title: 'Existing AAPL position', thesis: '', horizonDays: 30, riskMode: 'balanced'
  });
  // The browser half isolates presentation while PlanAdoptionReviewServiceTest exercises the
  // real adoption write. Give this otherwise ordinary fixture Plan the same persisted arrival
  // posture as an adopted Plan so the canonical live band—not a second route—owns the review.
  pg.sql(`UPDATE plans SET furthest_stage='MANAGE_REVIEW', status='POSITION_OPEN' WHERE id='${plan.id}'`);
  plan = { ...plan, furthestStage: 'MANAGE_REVIEW', status: 'POSITION_OPEN', assumptionsEditable: false };

  browser = await chromium.launch();
  page = await browser.newPage({ viewport: { width: 2560, height: 1200 } });
  page.on('pageerror', error => pageErrors.push(String(error)));
  page.on('response', response => {
    if (response.status() >= 500) serverErrors.push(`${response.status()} ${response.url()}`);
  });
  await page.route(`**/api/plans/${plan.id}/manage*`, async route => {
    manageReads += 1;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(managePayload()) });
  });
  page.on('request', request => {
    if (/\/api\/portfolio\/accounts\/[^/]+\/analyze|\/api\/campaigns\//.test(request.url())) {
      duplicateAnalysisReads += 1;
    }
  });
  await page.goto(BASE + '/');
  await page.waitForSelector('#app[data-ready="true"]', { timeout: 30000 });
  if (await page.locator('#welcome-skip').count()) {
    await page.click('#welcome-skip');
    await page.waitForSelector('#app[data-ready="true"]');
  }
});

after(async () => {
  if (browser) await browser.close();
  if (server) server.kill();
  if (pg) pg.drop();
});

for (const level of ['beginner', 'expert']) {
  test(`adopted Plan keeps both questions and the exact receipt visible at ${level} level`, async () => {
    await page.setViewportSize({ width: 2560, height: 1200 });
    await setLevel(level);
    await goManage();

    assert.equal(await page.locator('.adoption-review').count(), 1, 'one review surface owns the journey');
    assert.equal(await page.locator('.adoption-lens').count(), 2, 'the two questions are sibling lenses');
    const text = await page.locator('.adoption-review').innerText();
    assert.match(text, /Would you open the exact position you still own today/);
    assert.match(text, /whole campaign earned or lost, beside the frozen adopted baseline/);
    assert.match(text, /anchors position identity only, not the campaign's arithmetic start/);
    assert.match(text, /prec_adoption_exact_9/);
    assert.match(text, /Current package price/i);
    assert.match(text, /Campaign-adjusted economic basis/i);
    assert.match(text, /tracked tax basis/i);
    assert.match(text, /objective revision 2/i);
    assert.doesNotMatch(text, /averaged|blended result/i);

    const layout = await page.evaluate(() => {
      const grid = document.querySelector('.adoption-two-lens');
      const lenses = [...grid.children].map(node => node.getBoundingClientRect());
      return {
        columns: getComputedStyle(grid).gridTemplateColumns.split(' ').length,
        widths: lenses.map(rect => rect.width),
        tops: lenses.map(rect => Math.round(rect.top)),
        overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth
      };
    });
    assert.equal(layout.columns, 2, '2560px uses the real estate for a true comparison');
    assert.ok(layout.widths.every(width => width >= 500), `both lenses remain readable: ${layout.widths}`);
    assert.deepEqual(layout.tops, [layout.tops[0], layout.tops[0]], 'lenses align at the top');
    assert.ok(layout.overflow <= 1, `no wide-screen overflow (${layout.overflow}px)`);

    assert.equal(await page.locator('#plan-open-book').count(), 1,
      'the existing tracked-position action remains the one Book entry point');
    assert.equal(await page.locator('.adoption-review button', { hasText: /Book/ }).count(), 0,
      'the comparison does not duplicate the Book journey');
  });
}

test('the same two questions reflow into one readable mobile column', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setLevel('beginner');
  await goManage();
  const layout = await page.evaluate(() => {
    const grid = document.querySelector('.adoption-two-lens');
    return {
      columns: getComputedStyle(grid).gridTemplateColumns.split(' ').length,
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      rightmost: Math.max(...[...document.querySelectorAll('.adoption-review button, .adoption-review table')]
        .map(node => node.getBoundingClientRect().right))
    };
  });
  assert.equal(layout.columns, 1);
  assert.ok(layout.overflow <= 1, `mobile has no horizontal overflow (${layout.overflow}px)`);
  assert.ok(layout.rightmost <= 390, `all controls and receipts stay reachable (${layout.rightmost}px)`);
  assert.equal(await page.locator('.adoption-lens').count(), 2);
  assert.deepEqual(pageErrors, [], `page errors: ${pageErrors.join(' | ')}`);
  assert.deepEqual(serverErrors, [], `server errors: ${serverErrors.join(' | ')}`);
  assert.ok(manageReads >= 3, 'each level/viewport reads the one canonical Manage payload');
  assert.equal(duplicateAnalysisReads, 0, 'the browser never starts a second analysis or campaign API fan');
});
