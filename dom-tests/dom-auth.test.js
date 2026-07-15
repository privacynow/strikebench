/*
 * Auth-on browser gate. A signed-out browser may fetch only the public bootstrap contract;
 * owner-scoped APIs must remain untouched until authentication succeeds.
 */
'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const { freshDb } = require('./pgtest');

const PORT = process.env.PORT || '7191';
const BASE = `http://localhost:${PORT}`;
const JAR = process.env.JAR || path.resolve(__dirname, '../target/strikebench.jar');
const JAVA = process.env.JAVA_BIN || 'java';

let server, browser, page, pg, oidcServer, oidcIssuer;
const apiRequests = [];
const pageErrors = [];
const serverErrors = [];

const oidcKeys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const publicJwk = Object.assign(oidcKeys.publicKey.export({ format: 'jwk' }), {
  kid: 'strikebench-browser-test', use: 'sig', alg: 'RS256', key_ops: ['verify']
});
const loginCodes = new Map();

function json(res, value) {
  const body = JSON.stringify(value);
  res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function signedIdToken(nonce) {
  const now = Math.floor(Date.now() / 1000);
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const unsigned = `${encode({ alg: 'RS256', typ: 'JWT', kid: publicJwk.kid })}.${encode({
    iss: oidcIssuer, sub: 'browser-learner', aud: 'browser-test-client',
    iat: now, exp: now + 300, nonce,
    email: 'learner@example.com', email_verified: true, name: 'Browser Learner'
  })}`;
  return `${unsigned}.${crypto.sign('RSA-SHA256', Buffer.from(unsigned), oidcKeys.privateKey).toString('base64url')}`;
}

async function startOidcIssuer() {
  oidcServer = http.createServer((req, res) => {
    const url = new URL(req.url, oidcIssuer || 'http://127.0.0.1');
    if (url.pathname === '/.well-known/openid-configuration') {
      json(res, {
        issuer: oidcIssuer,
        authorization_endpoint: `${oidcIssuer}/authorize`,
        token_endpoint: `${oidcIssuer}/token`,
        jwks_uri: `${oidcIssuer}/jwks`,
        response_types_supported: ['code'],
        subject_types_supported: ['public'],
        id_token_signing_alg_values_supported: ['RS256'],
        scopes_supported: ['openid', 'email', 'profile'],
        token_endpoint_auth_methods_supported: ['client_secret_basic'],
        claims_supported: ['sub', 'aud', 'iss', 'exp', 'iat', 'nonce', 'email', 'email_verified', 'name']
      });
      return;
    }
    if (url.pathname === '/jwks') {
      json(res, { keys: [publicJwk] });
      return;
    }
    if (url.pathname === '/authorize') {
      assert.equal(url.searchParams.get('client_id'), 'browser-test-client');
      const code = crypto.randomBytes(18).toString('base64url');
      loginCodes.set(code, url.searchParams.get('nonce'));
      const callback = new URL(url.searchParams.get('redirect_uri'));
      callback.searchParams.set('code', code);
      callback.searchParams.set('state', url.searchParams.get('state'));
      res.writeHead(302, { Location: callback.toString() });
      res.end();
      return;
    }
    if (url.pathname === '/token' && req.method === 'POST') {
      const auth = Buffer.from((req.headers.authorization || '').replace(/^Basic /, ''), 'base64').toString();
      assert.equal(auth, 'browser-test-client:browser-test-secret');
      let body = '';
      req.on('data', chunk => { body += chunk; });
      req.on('end', () => {
        const code = new URLSearchParams(body).get('code');
        const nonce = loginCodes.get(code);
        assert.ok(nonce, 'authorization code is one-shot and issuer-owned');
        loginCodes.delete(code);
        json(res, { access_token: 'browser-access-token', token_type: 'Bearer', expires_in: 300,
          id_token: signedIdToken(nonce) });
      });
      return;
    }
    res.writeHead(404).end();
  });
  await new Promise((resolve, reject) => {
    oidcServer.once('error', reject);
    oidcServer.listen(0, '127.0.0.1', () => {
      oidcIssuer = `http://127.0.0.1:${oidcServer.address().port}`;
      resolve();
    });
  });
}

async function waitForServer(tries = 60) {
  for (let i = 0; i < tries; i++) {
    try { if ((await fetch(`${BASE}/api/status`)).ok) return; } catch (e) { /* not up yet */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error('auth-on server did not start');
}

before(async () => {
  await startOidcIssuer();
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: {
      ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true', AUTH_ENABLED: 'true',
      OIDC_CLIENT_ID: 'browser-test-client', OIDC_CLIENT_SECRET: 'browser-test-secret',
      OIDC_ISSUER: oidcIssuer, OIDC_CALLBACK_URL: `${BASE}/auth/callback`,
      AUTH_POST_LOGIN_URL: '/#/plans', AUTH_ALLOWED_EMAILS: 'learner@example.com'
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
  if (oidcServer) await new Promise(resolve => oidcServer.close(resolve));
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

test('verified OIDC sign-in reaches the owner-scoped application', async () => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.locator('.signin-card a').click();
  await page.waitForURL(`${BASE}/#/plans`, { timeout: 15_000 });
  await page.waitForSelector('#app[data-ready="true"] #plans-library');

  const session = await page.evaluate(async () => {
    const meResponse = await fetch('/api/auth/me');
    const accountResponse = await fetch('/api/account');
    const plansResponse = await fetch('/api/plans');
    const plans = await plansResponse.json();
    return {
      meStatus: meResponse.status,
      me: await meResponse.json(),
      accountStatus: accountResponse.status,
      plansStatus: plansResponse.status,
      planCount: plans.plans.length
    };
  });
  assert.equal(session.meStatus, 200);
  assert.equal(session.me.authenticated, true);
  assert.equal(session.me.user.email, 'learner@example.com');
  assert.equal(session.me.user.name, 'Browser Learner');
  assert.equal(session.accountStatus, 200);
  assert.equal(session.plansStatus, 200);
  assert.equal(session.planCount, 0);
  assert.equal(await page.locator('#nav a[data-route="plans"].active').count(), 1);
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(serverErrors, []);
});
