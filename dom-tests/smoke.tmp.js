'use strict';
const { spawn } = require('node:child_process');
const fs = require('node:fs'); const os = require('node:os'); const path = require('node:path');
const { chromium } = require('playwright');
const PORT = '7281', BASE = `http://localhost:${PORT}`;
(async () => {
  const dbDir = fs.mkdtempSync(path.join(os.tmpdir(), 'bs-'));
  const server = spawn(process.env.JAVA_BIN || 'java', ['-jar', '../target/strikebench.jar'], {
    env: { ...process.env, PORT, FIXTURES_ONLY: 'true', DB_PATH: path.join(dbDir, 's.db') }, stdio: 'ignore' });
  for (let i = 0; i < 60; i++) { try { if ((await fetch(BASE + '/api/status')).ok) break; } catch (e) {} await new Promise(r => setTimeout(r, 500)); }
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1360, height: 1000 } });
  const errs = [];
  page.on('pageerror', e => errs.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text().slice(0, 200)); });
  await page.goto(BASE + '/'); await page.waitForSelector('#app[data-ready="true"]');
  const skip = await page.locator('#welcome-skip').count();
  if (skip) { await page.click('#welcome-skip'); await page.waitForSelector('#app[data-ready="true"]'); }

  await page.evaluate(() => { location.hash = '#/recommend/builder'; });
  await page.waitForSelector('#builder-templates .tpl', { timeout: 20000 });
  await page.screenshot({ path: 'shots/bld-initial.png' });

  // Pick an iron condor -> 4 legs -> live panel
  await page.click('.tpl[data-tpl="IRON_CONDOR"]');
  await page.waitForSelector('#builder-legs .leg-row', { timeout: 15000 });
  const legRows = await page.locator('#builder-legs .leg-row').count();
  await page.waitForSelector('#builder-panel .stat', { timeout: 15000 });
  await new Promise(r => setTimeout(r, 700));
  await page.screenshot({ path: 'shots/bld-condor.png' });
  const panelText = await page.textContent('#builder-panel');
  console.log('LEGS:', legRows);
  console.log('PANEL HAS payoff chart:', await page.locator('#builder-panel .chart-wrap').count());
  console.log('PANEL verdict ok:', /Passes the safety screens|Allowed/.test(panelText));
  console.log('PANEL max loss:', /Most you can lose/.test(panelText));
  console.log('PANEL assignment:', /Assignment odds|assignment/i.test(panelText));

  // Hover a leg -> popover
  await page.locator('#builder-legs .leg-row').first().hover();
  await new Promise(r => setTimeout(r, 500));
  console.log('LEG POP:', await page.locator('.leg-pop').count());
  await page.screenshot({ path: 'shots/bld-hover.png' });

  // Click leg -> detail card with impact
  await page.locator('#builder-legs .leg-row .leg-info').first().click();
  await new Promise(r => setTimeout(r, 1200));
  console.log('LEG DETAIL:', (await page.textContent('#builder-legs .leg-detail')).slice(0, 160));
  await page.screenshot({ path: 'shots/bld-detail.png' });

  // Short straddle -> BLOCK + cliff chart
  await page.click('.tpl[data-tpl="SHORT_STRADDLE"]');
  await new Promise(r => setTimeout(r, 1200));
  const blockText = await page.textContent('#builder-panel');
  console.log('BLOCKED:', /would be refused/.test(blockText), '| chart:', await page.locator('#builder-panel .chart-wrap').count(),
    '| review disabled:', await page.locator('#builder-review[disabled]').count());
  await page.screenshot({ path: 'shots/bld-blocked.png' });

  // Back to condor, review handoff
  await page.click('.tpl[data-tpl="IRON_CONDOR"]');
  await new Promise(r => setTimeout(r, 1200));
  await page.click('#builder-review');
  await page.waitForSelector('#app[data-ready="true"]');
  await new Promise(r => setTimeout(r, 500));
  const ticketText = await page.textContent('#app');
  console.log('TICKET REVIEW:', /Safety check/.test(ticketText), '| step6:', /Review/.test(ticketText));
  await page.screenshot({ path: 'shots/bld-review.png' });

  console.log('ERRORS:', errs.length ? errs.slice(0, 5) : 'none');
  await browser.close(); server.kill();
})().catch(e => { console.error('FATAL', e); process.exit(1); });
