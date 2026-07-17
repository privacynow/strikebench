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

let server, browser, page, adminContext, pg, oidcServer, oidcIssuer;
const apiRequests = [];
const pageErrors = [];
const serverErrors = [];

const oidcKeys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const publicJwk = Object.assign(oidcKeys.publicKey.export({ format: 'jwk' }), {
  kid: 'strikebench-browser-test', use: 'sig', alg: 'RS256', key_ops: ['verify']
});
const loginCodes = new Map();
const identities = Object.freeze({
  learner: { subject: 'browser-learner', email: 'learner@example.com', name: 'Browser Learner' },
  reviewer: { subject: 'browser-reviewer', email: 'reviewer@example.com', name: 'Browser Reviewer' },
  intruder: { subject: 'browser-intruder', email: 'intruder@example.com', name: 'Browser Intruder' }
});
let nextIdentityKey = null;

function json(res, value) {
  const body = JSON.stringify(value);
  res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function signedIdToken(nonce, identity) {
  const now = Math.floor(Date.now() / 1000);
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const unsigned = `${encode({ alg: 'RS256', typ: 'JWT', kid: publicJwk.kid })}.${encode({
    iss: oidcIssuer, sub: identity.subject, aud: 'browser-test-client',
    iat: now, exp: now + 300, nonce,
    email: identity.email, email_verified: true, name: identity.name
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
      assert.ok(identities[nextIdentityKey], 'the browser test must select an issuer identity before login');
      const code = crypto.randomBytes(18).toString('base64url');
      loginCodes.set(code, { nonce: url.searchParams.get('nonce'), identity: identities[nextIdentityKey] });
      nextIdentityKey = null;
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
        const login = loginCodes.get(code);
        assert.ok(login, 'authorization code is one-shot and issuer-owned');
        loginCodes.delete(code);
        json(res, { access_token: 'browser-access-token', token_type: 'Bearer', expires_in: 300,
          id_token: signedIdToken(login.nonce, login.identity) });
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

function trackPage(candidate) {
  candidate.on('request', request => {
    const url = new URL(request.url());
    if (url.origin === BASE && url.pathname.startsWith('/api/')) apiRequests.push(url.pathname);
  });
  candidate.on('response', response => {
    const url = new URL(response.url());
    if (url.origin === BASE && url.pathname.startsWith('/api/') && response.status() >= 500) {
      serverErrors.push(`${response.status()} ${url.pathname}`);
    }
  });
  candidate.on('pageerror', error => pageErrors.push(error.message));
}

async function loginAs(context, identityKey) {
  nextIdentityKey = identityKey;
  const candidate = await context.newPage();
  trackPage(candidate);
  await candidate.goto(`${BASE}/auth/login`);
  await candidate.waitForURL(`${BASE}/#/home`, { timeout: 15_000 });
  await candidate.waitForSelector('#app[data-route="home"][data-ready="true"]');
  return candidate;
}

before(async () => {
  await startOidcIssuer();
  pg = freshDb();
  server = spawn(JAVA, ['-jar', JAR], {
    env: {
      ...process.env, PORT, ...pg.env, FIXTURES_ONLY: 'true', AUTH_ENABLED: 'true',
      OIDC_CLIENT_ID: 'browser-test-client', OIDC_CLIENT_SECRET: 'browser-test-secret',
      OIDC_ISSUER: oidcIssuer, OIDC_CALLBACK_URL: `${BASE}/auth/callback`,
      AUTH_POST_LOGIN_URL: '/#/home',
      AUTH_ALLOWED_EMAILS: 'learner@example.com,reviewer@example.com',
      AUTH_ADMIN_EMAILS: 'learner@example.com', AUTH_SESSION_IDLE_SECONDS: '5'
    },
    stdio: 'ignore'
  });
  await waitForServer();
  browser = await chromium.launch();
  adminContext = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  page = await adminContext.newPage();
  trackPage(page);
  await page.goto(BASE + '/#/home');
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
  nextIdentityKey = 'learner';
  await page.locator('.signin-card a').click();
  await page.waitForURL(`${BASE}/#/home`, { timeout: 15_000 });
  await page.waitForSelector('#app[data-route="home"][data-ready="true"]');

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
  assert.equal(await page.locator('#nav a[data-route~="home"].active').count(), 1);
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(serverErrors, []);
});

test('two signed-in identities are isolated and non-admin routes fail with 403', async () => {
  const ownerRecords = await page.evaluate(async () => {
    const request = async (path, options = {}) => {
      const response = await fetch(path, options);
      const body = await response.json();
      if (!response.ok) throw new Error(`${path} returned ${response.status}: ${JSON.stringify(body)}`);
      return body;
    };
    const plan = await request('/api/plans', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clientRequestId: 'auth-owner-plan', symbol: 'AAPL', intent: 'INCOME',
        thesis: 'neutral', horizonDays: 30, riskMode: 'conservative', title: 'Owner A private Plan' })
    });
    await request('/api/workspace', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ route: 'plans', privateMarker: 'owner-a-only' })
    });
    const account = await request('/api/portfolio/accounts', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Owner A private book', accountType: 'TAXABLE',
        lotMethod: 'FIFO', openingCashCents: 2500000 })
    });
    return { planId: plan.id, accountId: account.id };
  });

  const memberContext = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const memberPage = await loginAs(memberContext, 'reviewer');
  const isolation = await memberPage.evaluate(async ids => {
    const read = async (path, options) => {
      const response = await fetch(path, options);
      let body = null;
      try { body = await response.json(); } catch (e) { /* status is still the contract */ }
      return { status: response.status, body };
    };
    return {
      me: await read('/api/auth/me'),
      plans: await read('/api/plans?scope=all&openOnly=false'),
      planById: await read(`/api/plans/${ids.planId}`),
      workspace: await read('/api/workspace'),
      accounts: await read('/api/portfolio/accounts'),
      accountById: await read(`/api/portfolio/accounts/${ids.accountId}`),
      admin: await read('/api/data/reset', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tier: 'PAPER', confirm: false })
      })
    };
  }, ownerRecords);

  assert.equal(isolation.me.body.user.email, 'reviewer@example.com');
  assert.equal(isolation.plans.status, 200);
  assert.deepEqual(isolation.plans.body.plans, []);
  assert.equal(isolation.planById.status, 404);
  assert.equal(isolation.workspace.status, 200);
  assert.equal(isolation.workspace.body.rev, 0);
  assert.equal(isolation.workspace.body.state, undefined);
  assert.equal(isolation.accounts.status, 200);
  assert.deepEqual(isolation.accounts.body.accounts, []);
  assert.equal(isolation.accountById.status, 404);
  assert.equal(isolation.admin.status, 403);
  assert.equal(isolation.admin.body.error, 'forbidden');

  await memberPage.goto(`${BASE}/auth/logout`);
  await memberPage.waitForURL(`${BASE}/#/home`);
  await memberPage.waitForSelector('#app[data-ready="true"] .signin-card');
  const afterLogout = await memberPage.evaluate(async () => ({
    me: await (await fetch('/api/auth/me')).json(),
    protectedStatus: (await fetch('/api/account')).status
  }));
  assert.equal(afterLogout.me.authenticated, false);
  assert.equal(afterLogout.protectedStatus, 401);
  await memberContext.close();
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(serverErrors, []);
});

test('an idle authenticated server session expires and loses protected access', async () => {
  const expiryContext = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const expiryPage = await loginAs(expiryContext, 'reviewer');
  assert.equal(await expiryPage.evaluate(async () => (await fetch('/api/account')).status), 200);

  await expiryPage.waitForTimeout(7_000);
  const expired = await expiryPage.evaluate(async () => ({
    protectedStatus: (await fetch('/api/account')).status,
    me: await (await fetch('/api/auth/me')).json()
  }));
  assert.equal(expired.protectedStatus, 401);
  assert.equal(expired.me.authenticated, false);
  await expiryContext.close();
  assert.deepEqual(serverErrors, []);
});

test('the OIDC allowlist denies a verified but unapproved identity', async () => {
  const deniedContext = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const deniedPage = await deniedContext.newPage();
  trackPage(deniedPage);
  nextIdentityKey = 'intruder';
  const response = await deniedPage.goto(`${BASE}/auth/login`);
  assert.equal(response.status(), 401);
  assert.match(await deniedPage.locator('body').innerText(), /not permitted/i);
  const me = await deniedPage.evaluate(async () => (await fetch('/api/auth/me')).json());
  assert.equal(me.authenticated, false);
  await deniedContext.close();
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(serverErrors, []);
});
