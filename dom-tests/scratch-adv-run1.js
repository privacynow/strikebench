'use strict';
/* Run 1: static geometry of a COMPLETED scan at the audit viewports + lane text + comp strip. */
const path = require('node:path');
const H = require('./scratch-adv-m5');

const VIEWPORTS = [
  { width: 2560, height: 1440 }, { width: 2000, height: 963 },
  { width: 1920, height: 1080 }, { width: 1440, height: 900 },
  { width: 1280, height: 800 }, { width: 1000, height: 800 },
  { width: 390, height: 844 }
];

(async () => {
  const server = H.http.createServer(H.serve);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await H.chromium.launch({ headless: true });
  // fast stream so "complete" arrives quickly
  H.setStream({ gapMs: 25, frames: 20, complete: true });

  for (const vp of VIEWPORTS) {
    const context = await browser.newContext({ viewport: vp });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await H.installWorld(page, { positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' });
    await H.bootHome(page, url);
    await page.waitForSelector('#authHomeOpportunity .opportunitylens.expanded');
    await H.declare(page);
    await page.locator('#authHomeOpportunity [data-auth-opportunity-scan]').click();
    await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'ready', null, { timeout: 20000 });
    await page.waitForTimeout(250);

    const m = await page.evaluate(() => {
      const vh = window.innerHeight;
      const box = el => el ? el.getBoundingClientRect() : null;
      const rows = Array.from(document.querySelectorAll('#authHomeOpportunity .opportunityrow'));
      const comp = document.querySelector('#authHomeOpportunity .opportunitycomp');
      const foot = document.querySelector('#authHomeOpportunity .authpulsefoot');
      const rowsHost = document.querySelector('#authHomeOpportunity .opportunityrows');
      const chain = [];
      let n = rowsHost;
      while (n && n !== document.body) {
        const cs = getComputedStyle(n);
        chain.push({ sel: n.tagName.toLowerCase() + (n.id ? '#' + n.id : '') + (typeof n.className === 'string' && n.className ? '.' + n.className.trim().split(/\s+/).join('.') : ''),
          clientH: n.clientHeight, scrollH: n.scrollHeight, overflowY: cs.overflowY });
        n = n.parentElement;
      }
      const laneMetrics = rows.map(r => {
        const s = r.querySelector('span small');
        return { text: (s.textContent || '').trim(), client: s.clientWidth, scroll: s.scrollWidth,
          truncated: s.scrollWidth - s.clientWidth, fontSize: getComputedStyle(s).fontSize,
          color: getComputedStyle(s).color, whiteSpace: getComputedStyle(s).whiteSpace,
          top: r.getBoundingClientRect().top, bottom: r.getBoundingClientRect().bottom,
          onScreen: r.getBoundingClientRect().bottom <= vh && r.getBoundingClientRect().top >= 0,
          symbol: (r.querySelector('strong') || {}).textContent };
      });
      const compSmall = comp ? Array.from(comp.querySelectorAll('b small')).map(s => ({
        text: s.textContent, display: getComputedStyle(s).display,
        w: s.getBoundingClientRect().width, h: s.getBoundingClientRect().height })) : [];
      return {
        vh,
        rowCount: rows.length,
        lanes: laneMetrics,
        compText: comp ? comp.innerText : null,
        compTitles: comp ? Array.from(comp.querySelectorAll('b')).map(b => b.getAttribute('title')) : [],
        compSmall,
        compRect: box(comp) && { y: box(comp).y, bottom: box(comp).bottom, onScreen: box(comp).bottom <= vh },
        footRect: box(foot) && { y: box(foot).y, bottom: box(foot).bottom, onScreen: box(foot).bottom <= vh },
        scrollChain: chain,
        boardScroll: (() => { const b = document.getElementById('board'); return b ? { clientH: b.clientHeight, scrollH: b.scrollHeight, overflowY: getComputedStyle(b).overflowY } : null; })(),
        panelOverflow: (() => { const p = document.querySelector('.homeworkbenchpanel'); return p ? { clientH: p.clientHeight, scrollH: p.scrollHeight, overflowY: getComputedStyle(p).overflowY } : null; })(),
        scoutResultCards: document.querySelectorAll('.scoutresultcard').length,
        scoutResults: document.querySelectorAll('.scoutresults').length
      };
    });
    console.log('=== ' + vp.width + 'x' + vp.height);
    console.log(JSON.stringify(m, null, 1));
    if (errors.length) console.log('PAGE ERRORS', errors);
    await page.screenshot({ path: path.join(H.SHOTS, `complete-${vp.width}x${vp.height}.png`), fullPage: false });
    await context.close();
  }
  await browser.close();
  server.close();
})();
