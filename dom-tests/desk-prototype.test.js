'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

const DESK_URL = pathToFileURL(path.resolve(
  __dirname, '../src/main/resources/public/index.html')).href;

const DESKTOPS = [
  { width: 1280, height: 800 },
  { width: 1366, height: 768 },
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
      `${label} ${selector} has no horizontal overflow (${value.xOverflow}px; ${JSON.stringify(value.offenders || [])})`);
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
      yOverflow: Math.max(0, node.scrollHeight - node.clientHeight),
      offenders: selector === '#app' ? Array.from(node.querySelectorAll('*')).map(child => {
        const rect = child.getBoundingClientRect();
        return { tag: child.tagName, cls: String(child.className || '').slice(0, 80), right: rect.right };
      }).filter(row => row.right > document.documentElement.clientWidth + 1).slice(0, 8) : []
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

    const riskIdentity = await page.locator('#focus .posriskmap').evaluate(svg => ({
      labels: svg.querySelectorAll('.prnode text').length,
      nodes: Array.from(svg.querySelectorAll('.prnode')).map(node => ({
        aria: node.getAttribute('aria-label'),
        title: node.querySelector('title')?.textContent,
        tabIndex: node.getAttribute('tabindex')
      }))
    }));
    assert.equal(riskIdentity.labels, 0,
      'position-risk dots stay graphical instead of printing tickers inside every mark');
    assert.equal(riskIdentity.nodes.length, 6, 'the full held book remains represented');
    riskIdentity.nodes.forEach(node => {
      assert.ok(node.aria && node.aria === node.title,
        'each graphical mark retains an exact accessible hover/focus identity');
      assert.equal(node.tabIndex, '0', 'each graphical mark is keyboard focusable');
    });

    await page.locator('#focus [data-pospane="mechanics"]').click();
    const persistentContext = page.locator('#focus .poscontext');
    await persistentContext.waitFor();
    assert.match(await persistentContext.innerText(), /SMH/i);
    assert.match(await persistentContext.innerText(), /fixture/i,
      'staged position research is labeled honestly until Book is backend-connected');

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
    assert.equal(await persistentContext.isVisible(), true,
      'quote, event, and research context remain visible while an adjustment is open');
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

test('New Idea keeps six exact legs readable beside ideas across desktop and mobile structures', async () => {
  const viewports = [
    { width: 1280, height: 720 },
    { width: 1280, height: 800 },
    { width: 1366, height: 768 },
    { width: 1440, height: 900 },
    { width: 1920, height: 1080 },
    { width: 2048, height: 1080 },
    { width: 2560, height: 1440 },
    { width: 1000, height: 800 },
    { width: 390, height: 844 },
    { width: 375, height: 812 },
    { width: 320, height: 700 }
  ];

  for (const viewport of viewports) {
    const { page, errors } = await openDesk(viewport);
    const label = `${viewport.width}x${viewport.height}`;
    try {
      await page.locator('#threadNewIdea').evaluate(node => node.click());
      await page.waitForSelector('#decideStage.on .decwrap');
      await page.locator('#univq').fill('AMD');
      await page.locator('#univq').press('Enter');
      await page.locator('#decideStage [data-cand="i-ic"]').click();
      assert.equal(await page.locator('#decideStage .declegpanel .legr').count(), 4,
        `${label} renders the complete condor package`);
      /* This is a geometry matrix, and the Add control intentionally lives inside the
         panel-local scroll rail at short heights. Invoke the same DOM handler without
         coupling the layout assertion to Playwright's auto-scroll heuristics. */
      await page.locator('#decideStage .declegpanel [data-addleg]').evaluate(node => node.click());
      await page.locator('#decideStage .declegpanel [data-addleg]').evaluate(node => node.click());
      assert.equal(await page.locator('#decideStage .declegpanel .legr').count(), 6,
        `${label} renders the extended six-leg package`);
      await page.waitForTimeout(420);

      const layout = await page.evaluate(() => {
        const left = document.querySelector('#decideStage .dcleft');
        const center = document.querySelector('#decideStage .dccenter');
        const right = document.querySelector('#decideStage .dcright');
        const fan = left.querySelector('.fan');
        const panel = left.querySelector('.declegpanel');
        const rail = panel.querySelector('.declegs');
        const map = left.querySelector('.pickmap');
        const wrap = document.querySelector('#decideStage .decwrap');
        const grid = document.querySelector('#decideStage .decgrid');
        const panelRect = panel.getBoundingClientRect();
        const fanRect = fan.getBoundingClientRect();
        const mapRect = map.getBoundingClientRect();
        const centerRect = center.getBoundingClientRect();
        const rightRect = right.getBoundingClientRect();
        const leftRect = left.getBoundingClientRect();
        const leftOffenders = Array.from(left.querySelectorAll('*')).map(node => {
          const rect = node.getBoundingClientRect();
          return {
            tag: node.tagName,
            cls: String(node.className || '').slice(0, 90),
            right: Math.round((rect.right - leftRect.right) * 10) / 10,
            width: Math.round(rect.width * 10) / 10,
            scroll: Math.max(0, node.scrollWidth - node.clientWidth)
          };
        }).filter(row => row.right > 1 || row.scroll > 1).slice(0, 8);
        const legRects = Array.from(rail.querySelectorAll('.legr')).map(leg => {
          const rect = leg.getBoundingClientRect();
          return {
            left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
            width: rect.width, contentOverflow: Math.max(0, leg.scrollWidth - leg.clientWidth)
          };
        });
        return {
          panelInLeft: panel.parentElement.classList.contains('leftsupport'),
          panelAbsentFromCenter: !center.querySelector('.declegpanel'),
          ideasBeforeLegs: Boolean(fan.compareDocumentPosition(panel)
            & Node.DOCUMENT_POSITION_FOLLOWING),
          legsBeforeMap: Boolean(panel.compareDocumentPosition(map)
            & Node.DOCUMENT_POSITION_FOLLOWING),
          gridDisplay: getComputedStyle(grid).display,
          gridDirection: getComputedStyle(grid).flexDirection,
          supportDisplay: getComputedStyle(panel.parentElement).display,
          wrapOverflowY: getComputedStyle(wrap).overflowY,
          leftOverflowY: getComputedStyle(left).overflowY,
          railDirection: getComputedStyle(rail).flexDirection,
          railOverflowX: getComputedStyle(rail).overflowX,
          railOverflowY: getComputedStyle(rail).overflowY,
          railXOverflow: Math.max(0, rail.scrollWidth - rail.clientWidth),
          railYOverflow: Math.max(0, rail.scrollHeight - rail.clientHeight),
          panelXOverflow: Math.max(0, panel.scrollWidth - panel.clientWidth),
          wrapXOverflow: Math.max(0, wrap.scrollWidth - wrap.clientWidth),
          leftXOverflow: Math.max(0, left.scrollWidth - left.clientWidth),
          leftYOverflow: Math.max(0, left.scrollHeight - left.clientHeight),
          leftOffenders,
          panelRect: {
            left: panelRect.left, top: panelRect.top,
            right: panelRect.right, bottom: panelRect.bottom, width: panelRect.width
          },
          fanRect: {
            top: fanRect.top, bottom: fanRect.bottom, height: fanRect.height
          },
          mapRect: {
            left: mapRect.left, top: mapRect.top, right: mapRect.right,
            bottom: mapRect.bottom, width: mapRect.width, height: mapRect.height
          },
          centerWidth: centerRect.width,
          rightWidth: rightRect.width,
          legRects
        };
      });

      assert.equal(layout.panelInLeft, true, `${label} keeps the workbench in the idea rail`);
      assert.equal(layout.panelAbsentFromCenter, true,
        `${label} does not duplicate the workbench above the payoff`);
      assert.equal(layout.ideasBeforeLegs, true, `${label} ranks ideas before their exact legs`);
      assert.equal(layout.legsBeforeMap, true, `${label} treats the risk map as supporting context`);
      assert.ok(layout.legRects.every(rect => rect.contentOverflow <= 1),
        `${label} does not clip controls or per-leg economics`);
      assert.ok(layout.panelXOverflow <= 1 && layout.wrapXOverflow <= 1 && layout.leftXOverflow <= 1,
        `${label} contains the leg structure without widening a panel or the overlay `
          + `(panel ${layout.panelXOverflow}px, wrap ${layout.wrapXOverflow}px, left ${layout.leftXOverflow}px; `
          + `offenders ${JSON.stringify(layout.leftOffenders)})`);
      if (viewport.width > 1000) {
        assert.ok(layout.leftYOverflow <= 1,
          `${label} keeps candidates, stacked legs, and the risk map inside the left decision rail `
            + `(overflow ${layout.leftYOverflow}px)`);
      }
      assert.equal(layout.railDirection, 'column', `${label} stacks every exact leg at full width`);
      assert.ok(layout.railXOverflow <= 1, `${label} needs no sideways scrolling for exact legs`);
      layout.legRects.forEach((rect, index) => {
        assert.ok(rect.width >= layout.panelRect.width - 28,
          `${label} leg ${index + 1} uses the available panel width`);
        assert.ok(rect.left >= layout.panelRect.left - 1 && rect.right <= layout.panelRect.right + 1,
          `${label} leg ${index + 1} stays inside the workbench`);
        if (index) assert.ok(rect.top > layout.legRects[index - 1].top,
          `${label} leg ${index + 1} follows the preceding leg vertically`);
      });

      if (viewport.width >= 1900 && viewport.height <= 1150) {
        assert.equal(layout.supportDisplay, 'grid',
          `${label} gives the exact package and supporting map one bounded short-desktop row`);
        assert.ok(layout.panelRect.top - layout.fanRect.bottom <= 16,
          `${label} places the selected strategy legs directly after the ranked ideas`);
        assert.ok(layout.mapRect.left > layout.panelRect.right,
          `${label} places the supporting map beside, not beneath, the exact package`);
        assert.ok(Math.abs(layout.mapRect.height - (layout.panelRect.bottom - layout.panelRect.top)) <= 2,
          `${label} gives both support instruments the same bounded row height`);
        assert.ok(layout.mapRect.width >= 170,
          `${label} keeps the supporting map wide enough to read (${layout.mapRect.width}px)`);
      } else if (viewport.width >= 1900) {
        assert.ok(layout.panelRect.top - layout.fanRect.bottom <= 16,
          `${label} places the selected strategy legs directly after the ranked ideas`);
        assert.ok(layout.mapRect.top > layout.panelRect.bottom,
          `${label} stacks the useful risk map below the full-width leg workbench`);
        assert.ok(Math.abs(layout.mapRect.width - layout.panelRect.width) <= 2,
          `${label} gives legs and risk map the same left-rail width`);
        assert.ok(layout.mapRect.height >= (viewport.height >= 1000 ? 210 : 180),
          `${label} keeps the risk map large enough to read (${layout.mapRect.height}px)`);
      }

      if (viewport.width <= 1000) {
        assert.equal(layout.gridDisplay, 'flex', `${label} structurally stacks decision areas`);
        assert.equal(layout.gridDirection, 'column', `${label} uses a vertical narrow-screen reading order`);
        assert.equal(layout.wrapOverflowY, 'auto', `${label} gives vertical movement to the one overlay reading surface`);
        assert.equal(layout.leftOverflowY, 'visible', `${label} does not create a nested left-column scroller`);
        assert.equal(layout.railOverflowY, 'visible', `${label} exposes the complete narrow-screen leg stack`);
        assert.ok(layout.railYOverflow <= 1, `${label} does not add a nested narrow-screen leg scroller`);
      } else {
        assert.equal(layout.gridDisplay, 'grid', `${label} retains the desktop analysis grid`);
        assert.equal(layout.railOverflowX, 'hidden', `${label} does not expose a horizontal leg rail`);
        assert.equal(layout.railOverflowY, 'auto', `${label} contains only genuinely long leg stacks locally`);
        assert.ok(layout.rightWidth >= (viewport.width >= 1280 ? 350 : 300),
          `${label} gives Evidence & Paths useful horizontal room (${layout.rightWidth}px)`);
        assert.ok(layout.centerWidth / layout.rightWidth <= 2.1,
          `${label} keeps the payoff center proportionate to Evidence & Paths`);
      }

      assertNoOverflow(await overflowMetrics(page,
        ['html', 'body', '#app', '#decideStage']), `${label} six-leg decision`);
      assert.deepEqual(errors, [], `${label} emitted page errors: ${errors.join('\n')}`);
    } finally {
      await page.close();
    }
  }
});
