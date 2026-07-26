'use strict';
/* Run 3: narrow viewports (1000, 390, 375) — declare by dispatching real clicks in-page. */
const path = require('node:path');
const H = require('./scratch-adv-m5');

const FIX = '#riskMain>.homeworkbenchpanel{flex:1;min-height:0;overflow:hidden}';
const VIEWPORTS = [{ width: 1000, height: 800 }, { width: 390, height: 844 }, { width: 375, height: 812 }];

async function declareInPage(page) {
  await page.evaluate(() => {
    ['[data-auth-scout-goal="INCOME"]', '[data-auth-workbench-view="Neutral"]',
      '[data-auth-workbench-horizon="45 trading days"]', '[data-auth-workbench-risk="Balanced"]']
      .forEach(sel => { const n = document.querySelector(sel); if (n) n.click(); });
  });
  await page.waitForFunction(() => window.HOME_SCOUT?.goal && window.homeIdea?.view
    && window.homeIdea?.horizon && window.homeIdea?.riskMode);
}

(async () => {
  const server = H.http.createServer(H.serve);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await H.chromium.launch({ headless: true });
  H.setStream({ gapMs: 25, frames: 20, complete: true });

  for (const vp of VIEWPORTS) {
    for (const withFix of [false, true]) {
      const context = await browser.newContext({ viewport: vp });
      const page = await context.newPage();
      page.setDefaultTimeout(20000);
      await H.installWorld(page, { positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' });
      await H.bootHome(page, url);
      await page.waitForSelector('#authHomeOpportunity .opportunitylens.expanded');
      await declareInPage(page);
      await page.evaluate(() => document.querySelector('#authHomeOpportunity [data-auth-opportunity-scan]').click());
      await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'ready', null, { timeout: 25000 });
      if (withFix) await page.addStyleTag({ content: FIX });
      await page.waitForTimeout(250);
      const m = await page.evaluate(() => {
        const rowsHost = document.querySelector('#authHomeOpportunity .opportunityrows');
        const root = document.querySelector('.homeworkbenchpanel');
        const comp = document.querySelector('.opportunitycomp');
        const lanes = Array.from(document.querySelectorAll('#authHomeOpportunity .opportunityrow span small'))
          .map(s => ({ t: s.textContent.slice(0, 24), client: s.clientWidth, scroll: s.scrollWidth,
            trunc: s.scrollWidth - s.clientWidth, fs: getComputedStyle(s).fontSize }));
        return {
          lanes,
          compInner: comp ? comp.innerText : null,
          compSmallBoxes: comp ? Array.from(comp.querySelectorAll('b small')).map(s => ({ d: getComputedStyle(s).display, w: s.getBoundingClientRect().width, h: s.getBoundingClientRect().height })) : [],
          panel: { clientH: root.clientHeight, scrollH: root.scrollHeight, ov: getComputedStyle(root).overflowY },
          rowsScroll: rowsHost.scrollHeight - rowsHost.clientHeight,
          rowsOverflowY: getComputedStyle(rowsHost).overflowY,
          docWidth: document.documentElement.scrollWidth,
          boardScroll: (() => { const b = document.getElementById('board'); return { c: b.clientHeight, s: b.scrollHeight }; })(),
          innerScrollers: Array.from(document.querySelectorAll('.homeworkbenchpanel *,#bookrisk *'))
            .filter(n => n.scrollHeight > n.clientHeight + 2 && /auto|scroll/.test(getComputedStyle(n).overflowY)).length,
          nestedOverflow: Array.from(root.querySelectorAll('.scoutbar,.scoutresults,.homeideasearch'))
            .filter(n => n.scrollHeight - n.clientHeight > 2 || n.scrollWidth - n.clientWidth > 2).map(n => n.className)
        };
      });
      console.log(`### ${vp.width}x${vp.height} fix=${withFix} ` + JSON.stringify(m));
      await page.screenshot({ path: path.join(H.SHOTS, `narrow-fix${withFix ? 1 : 0}-${vp.width}x${vp.height}.png`), fullPage: false });
      await context.close();
    }
  }
  await browser.close();
  server.close();
})();
