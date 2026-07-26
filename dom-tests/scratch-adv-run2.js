'use strict';
/* Run 2: (a) panel-constraint fix verification, (b) 1000/390 geometry with force clicks. */
const path = require('node:path');
const H = require('./scratch-adv-m5');

const FIX = '#riskMain>.homeworkbenchpanel{flex:1;min-height:0;overflow:hidden}';
const VIEWPORTS = [
  { width: 2560, height: 1440 }, { width: 2000, height: 963 }, { width: 1920, height: 1080 },
  { width: 1440, height: 900 }, { width: 1000, height: 800 }, { width: 390, height: 844 },
  { width: 375, height: 812 }
];

async function declareForce(page) {
  for (const sel of ['[data-auth-scout-goal="INCOME"]', '[data-auth-workbench-view="Neutral"]',
    '[data-auth-workbench-horizon="45 trading days"]', '[data-auth-workbench-risk="Balanced"]']) {
    await page.locator(sel).click({ force: true });
  }
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
      await declareForce(page);
      await page.locator('#authHomeOpportunity [data-auth-opportunity-scan]').click({ force: true });
      await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'ready', null, { timeout: 25000 });
      if (withFix) await page.addStyleTag({ content: FIX });
      await page.waitForTimeout(250);
      const m = await page.evaluate(() => {
        const vh = window.innerHeight;
        const rows = Array.from(document.querySelectorAll('#authHomeOpportunity .opportunityrow'));
        const comp = document.querySelector('#authHomeOpportunity .opportunitycomp');
        const foot = document.querySelector('#authHomeOpportunity .authpulsefoot');
        const rowsHost = document.querySelector('#authHomeOpportunity .opportunityrows');
        const root = document.querySelector('.homeworkbenchpanel');
        const rootBox = root.getBoundingClientRect();
        const overflowTargets = Array.from(root.querySelectorAll('.scoutbar,.scoutresults,.homeideasearch'));
        const structural = Array.from(root.querySelectorAll(
          '.opportunitycontrols,.scoutbar,.scoutresults,.homeideasearch,.scoutgo'));
        return {
          onScreen: rows.map(r => [(r.querySelector('strong') || {}).textContent,
            r.getBoundingClientRect().bottom <= vh && r.getBoundingClientRect().top >= 0]),
          comp: comp && { y: Math.round(comp.getBoundingClientRect().y), bottom: Math.round(comp.getBoundingClientRect().bottom), onScreen: comp.getBoundingClientRect().bottom <= vh },
          foot: foot && { y: Math.round(foot.getBoundingClientRect().y), bottom: Math.round(foot.getBoundingClientRect().bottom), onScreen: foot.getBoundingClientRect().bottom <= vh },
          rowsScroll: rowsHost.scrollHeight - rowsHost.clientHeight,
          rowsClass: rowsHost.className,
          overflowMetaOn: !!document.querySelector('.rowsoverflowmeta.on'),
          nestedOverflow: overflowTargets.filter(n => n.scrollHeight - n.clientHeight > 2 || n.scrollWidth - n.clientWidth > 2)
            .map(n => ({ c: n.className, v: n.scrollHeight - n.clientHeight, h: n.scrollWidth - n.clientWidth })),
          structuralClipping: structural.filter(n => {
            const b = n.getBoundingClientRect();
            return b.left < rootBox.left - 1 || b.right > rootBox.right + 1 || b.top < rootBox.top - 1 || b.bottom > rootBox.bottom + 1;
          }).map(n => n.className),
          workbenchChildrenFit: Array.from(root.children).every(n => {
            const b = n.getBoundingClientRect();
            return b.left >= rootBox.left - 1 && b.right <= rootBox.right + 1 && b.top >= rootBox.top - 1 && b.bottom <= rootBox.bottom + 1;
          }),
          docWidth: document.documentElement.scrollWidth,
          innerScrollers: Array.from(document.querySelectorAll('.homeworkbenchpanel *,#bookrisk *'))
            .filter(n => n.scrollHeight > n.clientHeight + 2 && /auto|scroll/.test(getComputedStyle(n).overflowY))
            .map(n => n.className).slice(0, 6),
          rowsOverflowY: getComputedStyle(rowsHost).overflowY
        };
      });
      console.log(`### ${vp.width}x${vp.height} fix=${withFix} ` + JSON.stringify(m));
      await page.screenshot({ path: path.join(H.SHOTS, `fix${withFix ? 1 : 0}-${vp.width}x${vp.height}.png`) });
      await context.close();
    }
  }
  await browser.close();
  server.close();
})();
