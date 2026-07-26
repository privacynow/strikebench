'use strict';
/* Run 6: (a) does renaming the Java component break the browser receipt?
          (b) what does dropping white-space:nowrap on .opportunityrow small actually do? */
const path = require('node:path');
const H = require('./scratch-adv-m5');

const WRAP = '.opportunityrow small{white-space:normal}';

const CLIPPED = () => {
  const clipped = [];
  document.querySelectorAll('body *').forEach(el => {
    const s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden') return;
    const b = el.getBoundingClientRect();
    if (b.width < 1 || b.height < 1) return;
    if (/(auto|scroll)/.test(s.overflowX + s.overflowY)) return;
    if (!(s.overflowX === 'hidden' || s.overflowY === 'hidden' || s.overflow === 'hidden')) return;
    if (s.textOverflow === 'ellipsis') return;
    if (el.scrollWidth - el.clientWidth <= 2 && el.scrollHeight - el.clientHeight <= 2) return;
    clipped.push(el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + '.' + String(el.className).trim().split(/\s+/).slice(0, 3).join('.'));
  });
  return clipped;
};

async function run(page, url, opts) {
  await H.installWorld(page, { positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' });
  await H.bootHome(page, url);
  await page.waitForSelector('#authHomeOpportunity .opportunitylens.expanded');
  await page.evaluate(() => {
    ['[data-auth-scout-goal="INCOME"]', '[data-auth-workbench-view="Neutral"]',
      '[data-auth-workbench-horizon="45 trading days"]', '[data-auth-workbench-risk="Balanced"]']
      .forEach(sel => document.querySelector(sel).click());
  });
  await page.waitForFunction(() => window.homeIdea?.riskMode);
  await page.evaluate(() => document.querySelector('#authHomeOpportunity [data-auth-opportunity-scan]').click());
  await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'ready', null, { timeout: 25000 });
  if (opts.wrap) await page.addStyleTag({ content: WRAP });
  await page.waitForTimeout(250);
}

(async () => {
  const server = H.http.createServer(H.serve);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await H.chromium.launch({ headless: true });
  H.setStream({ gapMs: 25, frames: 12, complete: true });

  // (a) rename the compensation component the way finding M5-comp-denominator-mislabelled proposes
  {
    const before = JSON.parse(JSON.stringify(H.SCOUT_RESULT.frontier.compensationRanking));
    H.SCOUT_RESULT.frontier.compensationRanking.forEach(row => {
      row.components[0].name = 'Annualized return on risk capital';
      row.components[0].note = '$1.00 theoretical max profit divided by $4.00 economic exposure = 25.00% over 3 calendar days; ~3041.67% annualized if repeatable.';
    });
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await context.newPage();
    page.setDefaultTimeout(20000);
    await run(page, url, {});
    const m = await page.evaluate(() => {
      const c = document.querySelector('.opportunitycomp');
      return { innerText: c.innerText, titles: Array.from(c.querySelectorAll('b')).map(b => b.getAttribute('title')),
        smalls: c.querySelectorAll('b small').length };
    });
    console.log('### renamed-component', JSON.stringify(m));
    await context.close();
    H.SCOUT_RESULT.frontier.compensationRanking.length = 0;
    before.forEach(r => H.SCOUT_RESULT.frontier.compensationRanking.push(r));
  }

  // (b) white-space:normal on the lane line
  for (const vp of [{ width: 1920, height: 1080 }, { width: 1440, height: 900 }, { width: 1280, height: 800 }, { width: 390, height: 844 }]) {
    for (const wrap of [false, true]) {
      const context = await browser.newContext({ viewport: vp });
      const page = await context.newPage();
      page.setDefaultTimeout(20000);
      await run(page, url, { wrap });
      const m = await page.evaluate(() => {
        const rows = Array.from(document.querySelectorAll('#authHomeOpportunity .opportunityrow'));
        const comp = document.querySelector('.opportunitycomp');
        const foot = document.querySelector('.authpulsefoot');
        const rowsHost = document.querySelector('#authHomeOpportunity .opportunityrows');
        return {
          rowH: rows.map(r => Math.round(r.getBoundingClientRect().height)),
          laneTrunc: rows.map(r => { const s = r.querySelector('span small'); return s.scrollWidth - s.clientWidth; }),
          laneLines: rows.map(r => { const s = r.querySelector('span small'); return Math.round(s.getBoundingClientRect().height); }),
          compBottom: comp ? Math.round(comp.getBoundingClientRect().bottom) : null,
          footBottom: foot ? Math.round(foot.getBoundingClientRect().bottom) : null,
          vh: window.innerHeight,
          rowsScroll: rowsHost.scrollHeight - rowsHost.clientHeight,
          boardScroll: document.getElementById('board').scrollHeight - document.getElementById('board').clientHeight
        };
      });
      const clipped = await page.evaluate(CLIPPED);
      console.log(`### wrap=${wrap} ${vp.width}x${vp.height} ` + JSON.stringify(m) + ' clipped=' + JSON.stringify(clipped));
      await page.screenshot({ path: path.join(H.SHOTS, `wrap${wrap ? 1 : 0}-${vp.width}x${vp.height}.png`) });
      await context.close();
    }
  }
  await browser.close();
  server.close();
})();
