'use strict';
/* Run 4: would the proposed panel-constraint rule break the VISUAL lane?
   Reproduces desk.visual.test.js clippedElements() at the full matrix, Home idle (as that lane
   runs it: no scan is ever clicked), with and without the rule. */
const H = require('./scratch-adv-m5');

const FIX = '#riskMain>.homeworkbenchpanel{flex:1;min-height:0;overflow:hidden}';
const VIEWPORTS = [
  { width: 2560, height: 1440 }, { width: 2048, height: 1152 }, { width: 2000, height: 963 },
  { width: 1920, height: 1080 }, { width: 1440, height: 900 }, { width: 1280, height: 800 },
  { width: 1000, height: 800 }, { width: 390, height: 844 }, { width: 375, height: 812 },
  { width: 320, height: 700 }
];
const STATES = [
  { name: 'empty book', desk: { positions: 0, shares: 0, workingIdeas: 0, scout: 'idle' } },
  { name: 'one position', desk: { positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' } },
  { name: 'twelve positions', desk: { positions: 12, shares: 2, workingIdeas: 20, mixedIdeas: true, scout: 'complete' } }
];

const CLIPPED = () => {
  const clipped = [];
  document.querySelectorAll('body *').forEach(el => {
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') return;
    const box = el.getBoundingClientRect();
    if (box.width < 1 || box.height < 1) return;
    if (/(auto|scroll)/.test(style.overflowX + style.overflowY)) return;
    const cuts = style.overflowX === 'hidden' || style.overflowY === 'hidden' || style.overflow === 'hidden';
    if (!cuts) return;
    if (style.textOverflow === 'ellipsis') return;
    if (el.scrollWidth - el.clientWidth <= 2 && el.scrollHeight - el.clientHeight <= 2) return;
    clipped.push({ sel: el.tagName.toLowerCase() + (el.id ? '#' + el.id : '')
      + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.') : ''),
      client: el.clientWidth + 'x' + el.clientHeight, content: el.scrollWidth + 'x' + el.scrollHeight,
      text: (el.textContent || '').trim().slice(0, 40) });
  });
  return clipped;
};

(async () => {
  const server = H.http.createServer(H.serve);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await H.chromium.launch({ headless: true });

  for (const state of STATES) {
    for (const vp of VIEWPORTS) {
      const out = {};
      for (const withFix of [false, true]) {
        const context = await browser.newContext({ viewport: vp });
        const page = await context.newPage();
        page.setDefaultTimeout(15000);
        await H.installWorld(page, state.desk);
        await H.bootHome(page, url);
        if (withFix) await page.addStyleTag({ content: FIX });
        await page.waitForTimeout(250);
        out[withFix ? 'fix' : 'base'] = await page.evaluate(CLIPPED);
        out[withFix ? 'fixPanel' : 'basePanel'] = await page.evaluate(() => {
          const p = document.querySelector('.homeworkbenchpanel');
          return p ? { h: p.clientHeight, s: p.scrollHeight } : null;
        });
        await context.close();
      }
      const newlyClipped = out.fix.filter(f => !out.base.some(b => b.sel === f.sel));
      if (newlyClipped.length || out.base.length) {
        console.log(`### ${state.name} ${vp.width}x${vp.height} panel base=${JSON.stringify(out.basePanel)} fix=${JSON.stringify(out.fixPanel)}`);
        if (out.base.length) console.log('   BASE CLIPPED:', JSON.stringify(out.base));
        if (newlyClipped.length) console.log('   NEW CLIPPED WITH FIX:', JSON.stringify(newlyClipped));
      } else {
        console.log(`### ${state.name} ${vp.width}x${vp.height} clean both; panel base=${JSON.stringify(out.basePanel)} fix=${JSON.stringify(out.fixPanel)}`);
      }
    }
  }
  await browser.close();
  server.close();
})();
