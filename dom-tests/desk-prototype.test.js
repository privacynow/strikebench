'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

const DESK_URL = pathToFileURL(path.resolve(
  __dirname, '../src/main/resources/public/desk.html')).href;

const DESKTOPS = [
  { width: 1280, height: 800 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
  { width: 2560, height: 1440 }
];

let browser;

before(async () => { browser = await chromium.launch({ headless: true }); });
after(async () => { if (browser) await browser.close(); });

async function openDesk(viewport) {
  const page = await browser.newPage({ viewport });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(DESK_URL);
  await page.waitForSelector('#stage.lv-book #homeRiskMap');
  await page.waitForFunction(() => document.querySelectorAll('#book .card').length === 6);
  return { page, errors };
}

function near(actual, expected, tolerance = 2) {
  return Math.abs(actual - expected) <= tolerance;
}

function contained(inner, outer, tolerance = 2) {
  return inner.left >= outer.left - tolerance
    && inner.top >= outer.top - tolerance
    && inner.right <= outer.right + tolerance
    && inner.bottom <= outer.bottom + tolerance;
}

function assertNoOverflow(metrics, label) {
  Object.entries(metrics).forEach(([selector, value]) => {
    assert.ok(value.xOverflow <= 1,
      `${label} ${selector} has no horizontal overflow (${value.xOverflow}px)`);
    assert.ok(value.yOverflow <= 1,
      `${label} ${selector} has no vertical overflow (${value.yOverflow}px)`);
  });
}

async function overflowMetrics(page, selectors) {
  return page.evaluate(list => Object.fromEntries(list.map(selector => {
    const node = document.querySelector(selector);
    if (!node) throw new Error(`Missing overflow target: ${selector}`);
    return [selector, {
      xOverflow: Math.max(0, node.scrollWidth - node.clientWidth),
      yOverflow: Math.max(0, node.scrollHeight - node.clientHeight)
    }];
  })), selectors);
}

test('Home keeps the complete two-row cockpit bounded from 1280 through 2560', async () => {
  for (const viewport of DESKTOPS) {
    const { page, errors } = await openDesk(viewport);
    try {
      const geometry = await page.evaluate(() => {
        function rect(selector) {
          const node = document.querySelector(selector);
          if (!node) throw new Error(`Missing geometry target: ${selector}`);
          const box = node.getBoundingClientRect();
          return {
            left: box.left, top: box.top, right: box.right, bottom: box.bottom,
            width: box.width, height: box.height,
            visible: getComputedStyle(node).display !== 'none'
              && getComputedStyle(node).visibility !== 'hidden'
          };
        }
        return {
          board: rect('#board'),
          book: rect('#book'),
          risk: rect('#riskMain'),
          chain: rect('#chainBand'),
          bookRisk: rect('#bookrisk'),
          universe: rect('#univBand'),
          sector: rect('#sectorBand'),
          news: rect('#newsBand'),
          riskMap: rect('#homeRiskMap'),
          candles: rect('#homeCandles'),
          positionCount: document.querySelectorAll('#book .card').length
        };
      });
      const label = `${viewport.width}x${viewport.height}`;
      const top = [geometry.book, geometry.risk, geometry.chain];
      const lower = [geometry.bookRisk, geometry.universe, geometry.sector, geometry.news];

      assert.equal(geometry.positionCount, 6, `${label} keeps every held line in the roster`);
      [...top, ...lower].forEach((panel, index) => {
        assert.equal(panel.visible, true, `${label} panel ${index + 1} is visible`);
        assert.ok(panel.width >= 200, `${label} panel ${index + 1} has useful width (${panel.width}px)`);
        assert.ok(panel.height >= 200, `${label} panel ${index + 1} has useful height (${panel.height}px)`);
        assert.ok(contained(panel, geometry.board, 15),
          `${label} panel ${index + 1} stays inside the board`);
      });

      assert.ok(top.every(panel => near(panel.top, top[0].top)),
        `${label} roster, risk map, and chain share the first row`);
      assert.ok(lower.every(panel => near(panel.top, lower[0].top)),
        `${label} Book Risk, universe, sector, and news share the second row`);
      assert.ok(lower[0].top > top[0].bottom,
        `${label} cockpit rows do not overlap`);
      assert.ok(near(geometry.book.left, geometry.board.left + 14),
        `${label} cockpit starts at the board inset`);
      assert.ok(near(geometry.chain.right, geometry.board.right - 14),
        `${label} first row uses the full desktop width`);
      assert.ok(near(geometry.news.right, geometry.board.right - 14),
        `${label} second row uses the full desktop width`);
      assert.ok(near(geometry.news.bottom, geometry.board.bottom - 14),
        `${label} lower lenses use the available height`);
      assert.ok(geometry.riskMap.width > 150 && geometry.riskMap.height > 80,
        `${label} risk map remains a useful chart`);
      assert.ok(geometry.candles.width > 150 && geometry.candles.height > 50,
        `${label} chain context remains a useful chart`);

      assertNoOverflow(await overflowMetrics(page,
        ['html', 'body', '#app', '#stage', '#board', '#book']), label);
      assert.deepEqual(errors, [], `${label} emitted page errors: ${errors.join('\n')}`);
    } finally {
      await page.close();
    }
  }
});

test('SMH Position keeps held legs visible and scenario motion continuous through an adjustment', async () => {
  const { page, errors } = await openDesk({ width: 1440, height: 900 });
  try {
    await page.locator('#book .card[data-id="smh-cc"]').click();
    await page.waitForSelector('#focus .card[data-id="smh-cc"] .legspanel');
    await page.waitForTimeout(420);

    const workbench = page.locator('#focus .legspanel');
    assert.match(await workbench.innerText(), /Structure workbench/i);
    assert.match(await workbench.locator('.badge').innerText(), /Held/i);
    assert.equal(await workbench.locator('.legr').count(), 2,
      'the held stock and short call are both visible');

    const before = await page.locator('#focus .prnode').evaluateAll(nodes =>
      nodes.map(node => node.getAttribute('transform')));
    await page.locator('#focus .srow[data-si="0"]').click();
    await page.waitForSelector('#focus .srow-ctl');
    await page.waitForFunction(initial => Array.from(document.querySelectorAll('#focus .prnode'))
      .some((node, index) => node.getAttribute('transform') !== initial[index]), before);

    await page.locator('#focus [data-wf="playtoggle"]').click();
    await page.evaluate(() => {
      window.__smhScenarioNode = document.querySelector('#focus .scenbox');
      window.__smhScenarioControls = document.querySelector('#focus .srow-ctl');
    });
    const moveBefore = await page.locator('#focus [data-asm="mag"] .mv').innerText();
    await page.locator('#focus [data-wf="magup"]').click();
    const scenarioIdentity = await page.evaluate(() => ({
      panel: window.__smhScenarioNode === document.querySelector('#focus .scenbox'),
      controls: window.__smhScenarioControls === document.querySelector('#focus .srow-ctl'),
      connected: window.__smhScenarioControls.isConnected,
      opacity: getComputedStyle(window.__smhScenarioControls).opacity,
      animation: getComputedStyle(window.__smhScenarioControls).animationName
    }));
    assert.deepEqual(scenarioIdentity, {
      panel: true, controls: true, connected: true, opacity: '1', animation: 'none'
    }, 'assumption changes patch the retained scenario controls without flashing them');
    assert.notEqual(await page.locator('#focus [data-asm="mag"] .mv').innerText(), moveBefore,
      'the retained move control applies the new assumption');

    await page.locator(
      '#focus [data-adjleg="strike"][data-ali="1"][data-d="1"]').click();
    await page.waitForSelector('#focus .pendingbar');
    assert.match(await page.locator('#focus .legspanel.editing .badge').innerText(), /Proposed/i);
    const impact = await page.locator('#focus .pendingbar').evaluate(node => {
      const detail = document.querySelector('#focus .cdetail');
      const own = node.getBoundingClientRect();
      const parent = detail.getBoundingClientRect();
      return {
        own: {
          left: own.left, top: own.top, right: own.right, bottom: own.bottom,
          width: own.width, height: own.height
        },
        parent: {
          left: parent.left, top: parent.top, right: parent.right, bottom: parent.bottom,
          width: parent.width, height: parent.height
        }
      };
    });
    assert.ok(contained(impact.own, impact.parent),
      'the first-edit impact remains inside the position detail');
    assert.ok(impact.own.height < impact.parent.height * 0.26,
      'the adjustment impact is a bounded bar rather than a new scrolling screen');

    assertNoOverflow(await overflowMetrics(page, [
      'html', 'body', '#app', '#stage', '#board', '#focus',
      '#focus .card', '#focus .cdetail', '#focus .legspanel', '#focus .pendingbar'
    ]), 'SMH Position');
    assert.deepEqual(errors, [], `SMH Position emitted page errors: ${errors.join('\n')}`);
  } finally {
    await page.close();
  }
});

test('New Idea retargets NVDA, retains its scenario, and bounds Review in the existing dock', async () => {
  const { page, errors } = await openDesk({ width: 1440, height: 900 });
  try {
    await page.locator('#threadNewIdea').click();
    await page.waitForSelector('#decideStage.on .decwrap');
    await page.waitForTimeout(420);

    await page.locator('#decideStage .decintent').click();
    await page.locator('#decideStage [data-dec="pick"]').click();
    await page.locator('#univq').fill('NVDA');
    await page.waitForSelector('#decideStage [data-pick="NVDA"]');
    await page.locator('#decideStage [data-pick="NVDA"]').click();
    assert.match(await page.locator('#decideStage .decintent b').innerText(), /^NVDA/);

    await page.locator(
      '#decideStage .declegpanel [data-leg="strike"][data-d="1"]').first().click();
    assert.match(await page.locator('#decideStage .declegstate').innerText(), /Your draft/i);

    await page.locator('#decideStage .scenpanel .srow[data-si="0"]').click();
    await page.waitForSelector('#decideStage .scenpanel .srow-ctl');
    await page.locator('#decideStage .scenpanel [data-wf="playtoggle"]').click();
    await page.evaluate(() => {
      window.__ideaScenarioNode = document.querySelector('#decideStage .scenpanel');
      window.__ideaScenarioControls = document.querySelector('#decideStage .srow-ctl');
    });

    await page.locator(
      '#decideStage [data-dec="inspect"][data-inspect="mechanics"]').click();
    let identity = await page.evaluate(() => ({
      panel: window.__ideaScenarioNode === document.querySelector('#decideStage .scenpanel'),
      controls: window.__ideaScenarioControls === document.querySelector('#decideStage .srow-ctl'),
      connected: window.__ideaScenarioNode.isConnected
    }));
    assert.deepEqual(identity, { panel: true, controls: true, connected: true },
      'inspector changes re-seat the exact scenario node');

    await page.locator('#decideStage .scenpanel [data-wf="magup"]').click();
    identity = await page.evaluate(() => ({
      panel: window.__ideaScenarioNode === document.querySelector('#decideStage .scenpanel'),
      controls: window.__ideaScenarioControls === document.querySelector('#decideStage .srow-ctl'),
      connected: window.__ideaScenarioNode.isConnected,
      opacity: getComputedStyle(window.__ideaScenarioControls).opacity,
      animation: getComputedStyle(window.__ideaScenarioControls).animationName
    }));
    assert.deepEqual(identity, {
      panel: true, controls: true, connected: true, opacity: '1', animation: 'none'
    }, 'assumption changes patch the New Idea scenario without replacing or flashing it');

    await page.locator('#decideStage .decdock [data-dec="review"]').click();
    await page.waitForSelector('#decideStage .reviewbar');
    const review = await page.evaluate(() => {
      function rect(selector) {
        const box = document.querySelector(selector).getBoundingClientRect();
        return {
          left: box.left, top: box.top, right: box.right, bottom: box.bottom,
          width: box.width, height: box.height
        };
      }
      return {
        bar: rect('#decideStage .reviewbar'),
        dock: rect('#decideStage .decdock'),
        wrap: rect('#decideStage .decwrap'),
        grid: rect('#decideStage .decgrid'),
        sameScenario: window.__ideaScenarioNode
          === document.querySelector('#decideStage .scenpanel')
      };
    });
    assert.equal(review.sameScenario, true, 'Review preserves the active scenario node');
    assert.ok(contained(review.bar, review.dock), 'Review remains inside the existing execution dock');
    assert.ok(contained(review.dock, review.wrap), 'the execution dock remains inside the viewport surface');
    assert.ok(review.grid.bottom <= review.dock.top + 2,
      'Review does not append below or overlap the analysis grid');
    assert.ok(review.bar.height <= 80, `Review stays compact (${review.bar.height}px)`);

    assertNoOverflow(await overflowMetrics(page, [
      'html', 'body', '#app', '#decideStage', '#decideStage .decwrap',
      '#decideStage .decgrid', '#decideStage .dcleft', '#decideStage .dccenter',
      '#decideStage .dcright', '#decideStage .decdock', '#decideStage .reviewbar'
    ]), 'New Idea Review');
    assert.deepEqual(errors, [], `New Idea emitted page errors: ${errors.join('\n')}`);
  } finally {
    await page.close();
  }
});
