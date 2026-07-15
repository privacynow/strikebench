/*
 * Auth-on browser gate. A signed-out browser may fetch only the public bootstrap contract;
 * owner-scoped APIs must remain untouched until authentication succeeds.
 */
'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const path = require('node:path');
const { chromium } = require('playwright');
const { freshDb } = require('./pgtest');

const PORT = process.env.PORT || '7191';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg;
const apiRequests = [];
const pageErrors = [];
const serverErrors = [];

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { if ((await fetch(`${BASE}/api/status`)).ok) return; } catch (e) { /* not up yet */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error('auth-on server did not start');
}

before(async () => {
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: {
      ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true', AUTH_ENABLED: 'true',
      OIDC_CLIENT_ID: 'browser-test-client', OIDC_CLIENT_SECRET: 'browser-test-secret',
      AUTH_ALLOWED_EMAILS: 'learner@example.com'
    },
    stdio: 'ignore'
  });
  await waitForServer();
  browser = await chromium.launch();
  page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on('request', request => {
    const url = new URL(request.url());
    if (url.origin === BASE && url.pathname.startsWith('/api/')) apiRequests.push(url.pathname);
  });
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.origin === BASE && url.pathname.startsWith('/api/') && response.status() >= 500) {
      serverErrors.push(`${response.status()} ${url.pathname}`);
    }
  });
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(BASE + '/#/plans');
  await page.waitForSelector('#app[data-ready="true"] .signin-card');
});

after(async () => {
  if (browser) await browser.close();
  if (server) server.kill();
  if (pg) pg.drop();
});

test('signed-out private instance renders one focused login surface', async () => {
  assert.equal(await page.locator('.signin-card h1').textContent(), 'Sign in');
  assert.match(await page.locator('.signin-card').textContent(), /private/i);
  assert.equal(await page.locator('.signin-card a').getAttribute('href'), '/auth/login');
  assert.equal(await page.locator('#nav:visible, #bottom-nav:visible, .topbar-controls:visible').count(), 0);
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(serverErrors, []);
});

test('signed-out boot touches only public authentication and configuration APIs', () => {
  const allowed = new Set(['/api/auth/me', '/api/config']);
  const protectedRequests = apiRequests.filter(request => !allowed.has(request));
  assert.deepEqual(protectedRequests, [], `protected APIs were called before sign-in: ${protectedRequests.join(', ')}`);
  assert.ok(apiRequests.includes('/api/auth/me'));
  assert.ok(apiRequests.includes('/api/config'));
});

test('login surface remains usable without overflow on a narrow phone', async () => {
  await page.setViewportSize({ width: 320, height: 700 });
  const geometry = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
    card: document.querySelector('.signin-card').getBoundingClientRect().toJSON()
  }));
  assert.ok(geometry.scroll <= geometry.client + 1, `login page overflows: ${JSON.stringify(geometry)}`);
  assert.ok(geometry.card.left >= 0 && geometry.card.right <= geometry.client);
  assert.ok(geometry.card.width >= 280, 'login action remains touch-usable');
});
