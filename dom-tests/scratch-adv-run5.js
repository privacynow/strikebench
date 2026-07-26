'use strict';
/* Run 5: (a) does the streaming patch path find .scoutresultcard? (b) focus loss while streaming
   (c) with the proposed selector fix applied at runtime. (d) View-changes-after-scan asymmetry. */
const path = require('node:path');
const H = require('./scratch-adv-m5');

async function boot(page, url) {
  await H.installWorld(page, { positions: 1, shares: 0, workingIdeas: 5, scout: 'idle' });
  await H.bootHome(page, url);
  await page.waitForSelector('#authHomeOpportunity .opportunitylens.expanded');
  await H.declare(page);
}

(async () => {
  const server = H.http.createServer(H.serve);
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}/index.html`;
  const browser = await H.chromium.launch({ headless: true });

  // ---------- A/B: focus during streaming, without and with the selector fix ----------
  for (const patched of [false, true]) {
    H.setStream({ gapMs: 180, frames: 200, complete: false });
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await context.newPage();
    page.setDefaultTimeout(20000);
    await boot(page, url);
    if (patched) {
      await page.evaluate(() => {
        const original = window.authPatchScoutResult;
        window.authPatchScoutResult = function () {
          const host = document.getElementById('authHomeOpportunity') || document.getElementById('authEmptyOpportunity');
          const card = host && host.querySelector('.scoutresults');
          if (!host || !card) { window.authRenderOpportunityOnly(); return; }
          card.innerHTML = window.authScoutResultHTML(true);
          window.authBindOpportunityRows(card);
        };
        return typeof original === 'function';
      });
    }
    // instrument
    await page.evaluate(() => {
      window.__counts = { patchCalls: 0, fullRenders: 0, cardFound: 0, replacements: 0 };
      const patch = window.authPatchScoutResult, full = window.authRenderOpportunityOnly;
      window.authRenderOpportunityOnly = function () { window.__counts.fullRenders++; return full.apply(this, arguments); };
      window.authPatchScoutResult = function () {
        window.__counts.patchCalls++;
        const host = document.getElementById('authHomeOpportunity');
        if (host && host.querySelector('.scoutresultcard')) window.__counts.cardFound++;
        return patch.apply(this, arguments);
      };
      const host = document.getElementById('authHomeOpportunity');
      new MutationObserver(muts => { muts.forEach(m => { if (m.type === 'childList' && m.addedNodes.length) window.__counts.replacements++; }); })
        .observe(host, { childList: true, subtree: false });
      window.__t0 = performance.now();
    });
    await page.evaluate(() => document.querySelector('#authHomeOpportunity [data-auth-opportunity-scan]').click());
    await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'loading');
    await page.waitForTimeout(400);

    const input = page.locator('[data-auth-workbench-query]');
    await input.click({ force: true });
    await page.keyboard.type('A');
    const t0 = await page.evaluate(() => {
      const n = document.querySelector('[data-auth-workbench-query]');
      window.__node = n;
      return { value: n.value, active: document.activeElement === n, tag: document.activeElement.tagName };
    });
    await page.waitForTimeout(700);
    const t1 = await page.evaluate(() => {
      const n = document.querySelector('[data-auth-workbench-query]');
      return { sameNode: window.__node === n, value: n.value, activeIsQuery: document.activeElement === n,
        activeTag: document.activeElement.tagName };
    });
    await page.keyboard.type('M');
    await page.waitForTimeout(150);
    const t2 = await page.evaluate(() => ({
      value: document.querySelector('[data-auth-workbench-query]').value,
      counts: window.__counts, elapsed: Math.round(performance.now() - window.__t0),
      cards: document.querySelectorAll('.scoutresultcard').length,
      results: document.querySelectorAll('.scoutresults').length,
      homeQuery: window.homeIdea && window.homeIdea.query
    }));
    console.log(`### focus patched=${patched}`, JSON.stringify({ t0, t1, t2 }));
    await page.screenshot({ path: path.join(H.SHOTS, `stream-focus-patched${patched ? 1 : 0}.png`) });
    await context.close();
  }

  // ---------- C: view change after a COMPLETED scan ----------
  {
    H.setStream({ gapMs: 25, frames: 12, complete: true });
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    const page = await context.newPage();
    page.setDefaultTimeout(20000);
    await boot(page, url);
    await page.evaluate(() => document.querySelector('#authHomeOpportunity [data-auth-opportunity-scan]').click());
    await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'ready', null, { timeout: 25000 });
    await page.waitForTimeout(200);
    const before = await page.evaluate(() => ({
      view: window.homeIdea.view, phase: window.HOME_OPPORTUNITY.phase,
      rows: Array.from(document.querySelectorAll('.opportunityrow')).map(r => r.textContent.replace(/\s+/g, ' ').trim()),
      stale: /stale|out of date|re-?scan|no longer|does not match/i.exec(document.getElementById('authHomeOpportunity').innerText)
    }));
    await page.evaluate(() => document.querySelector('[data-auth-workbench-view="Bearish"]').click());
    await page.waitForTimeout(250);
    const after = await page.evaluate(() => ({
      view: window.homeIdea.view, phase: window.HOME_OPPORTUNITY.phase,
      rows: Array.from(document.querySelectorAll('.opportunityrow')).map(r => r.textContent.replace(/\s+/g, ' ').trim()),
      stale: /stale|out of date|re-?scan|no longer|does not match/i.exec(document.getElementById('authHomeOpportunity').innerText)
    }));
    console.log('### view-after-scan BEFORE', JSON.stringify(before));
    console.log('### view-after-scan AFTER ', JSON.stringify(after));
    console.log('### rows identical:', JSON.stringify(before.rows) === JSON.stringify(after.rows));
    await page.screenshot({ path: path.join(H.SHOTS, 'view-changed-after-scan-1920.png') });

    // and the asymmetric control mid-scan
    H.setStream({ gapMs: 180, frames: 200, complete: false });
    await page.evaluate(() => document.querySelector('#authHomeOpportunity [data-auth-opportunity-scan]').click());
    await page.waitForFunction(() => window.HOME_OPPORTUNITY?.phase === 'loading');
    await page.waitForTimeout(900);
    const mid = await page.evaluate(() => ({ phase: window.HOME_OPPORTUNITY.phase, partial: window.HOME_OPPORTUNITY.partial.length }));
    await page.evaluate(() => document.querySelector('[data-auth-workbench-risk="Aggressive"]').click());
    await page.waitForTimeout(300);
    const post = await page.evaluate(() => ({
      phase: window.HOME_OPPORTUNITY.phase, partial: window.HOME_OPPORTUNITY.partial.length,
      body: document.getElementById('authHomeOpportunity').innerText.replace(/\s+/g, ' ').slice(0, 200),
      cancelWord: /cancel|abort|stopped|discard/i.exec(document.getElementById('authHomeOpportunity').innerText)
    }));
    console.log('### risk-mid-scan MID', JSON.stringify(mid), 'POST', JSON.stringify(post));
    await page.screenshot({ path: path.join(H.SHOTS, 'risk-changed-during-scan-1920.png') });
    await context.close();
  }

  await browser.close();
  server.close();
})();
