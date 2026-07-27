'use strict';

/*
 * One owner for packaged-browser process isolation. Every *.journey.test.js asks this helper for
 * a fresh database, private port, JVM and browser; no suite copies lifecycle or cleanup code.
 * External market providers and background jobs are disabled explicitly, even though fixture mode
 * currently defaults most of them off. A CI journey must never spend a real provider allowance.
 */

const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const { once } = require('node:events');
const { spawn } = require('node:child_process');
const { launchChromium } = require('./browser');
const { freshDb } = require('./pgtest');

const ROOT = path.resolve(__dirname, '..');
const DEFAULT_JAR = path.join(ROOT, 'target', 'strikebench.jar');
const DIAGNOSTICS = path.join(ROOT, 'target', 'journey-diagnostics');
const ISOLATED_PRODUCT_ENV = Object.freeze({
  // FIXTURES_ONLY is the provider boundary. The explicit switches make the safety contract
  // readable in diagnostics and keep background writers off even if their fixture defaults
  // change later.
  FIXTURES_ONLY: 'true',
  YAHOO_ENABLED: 'false',
  YAHOO_HISTORY_SYNC_ENABLED: 'false',
  SNAPSHOT_ENABLED: 'false',
  PORTFOLIO_NAV_ENABLED: 'false',
  ARTIFACT_RETENTION_ENABLED: 'false',
  ENGINE_WARM_FULL_UNIVERSE: 'false'
});

/**
 * Compose the process environment for a packaged journey. Test-specific settings may enable
 * authentication or point OIDC at a local issuer, but they cannot cross the fixture/provider
 * boundary, select another database, or steal a shared application port.
 */
function packagedEnvironment(inherited, requested, database, port) {
  return {
    ...(inherited || {}),
    ...(requested || {}),
    ...ISOLATED_PRODUCT_ENV,
    ...(database || {}),
    PORT: String(port)
  };
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function processIsRunning(child) {
  return child.exitCode == null && child.signalCode == null;
}

async function freePort() {
  const candidate = net.createServer();
  await new Promise((resolve, reject) => {
    candidate.once('error', reject);
    candidate.listen(0, '127.0.0.1', resolve);
  });
  const port = candidate.address().port;
  await new Promise(resolve => candidate.close(resolve));
  return port;
}

async function waitForReady(base, server, log, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!processIsRunning(server)) {
      throw new Error(`packaged server exited ${server.exitCode ?? server.signalCode} before readiness\n${log()}`);
    }
    try {
      const response = await fetch(`${base}/api/status`, {
        signal: AbortSignal.timeout(1_500)
      });
      if (response.ok) return;
    } catch (ignored) {
      // Readiness is an event condition; retrying the probe is not a fixed UI wait.
    }
    await delay(100);
  }
  throw new Error(`packaged server did not become ready within ${timeoutMs}ms\n${log()}`);
}

function safeLabel(value) {
  return String(value || 'journey').replace(/[^a-z0-9_.-]+/gi, '-');
}

async function startPackagedApp(options = {}) {
  const label = safeLabel(options.label);
  const jar = path.resolve(options.jar || process.env.JAR || DEFAULT_JAR);
  if (!fs.existsSync(jar)) {
    throw new Error(`packaged journey requires ${jar}; build it before running this suite`);
  }

  const pg = freshDb();
  const port = Number(options.port || process.env.PORT || await freePort());
  const base = `http://127.0.0.1:${port}`;
  const requestedEnv = typeof options.env === 'function'
    ? options.env({ base, port: String(port) }) : (options.env || {});
  const lines = [];
  const append = chunk => {
    lines.push(String(chunk));
    // Keep diagnostics bounded if a broken server loops.
    if (lines.length > 4_000) lines.splice(0, lines.length - 4_000);
  };
  const log = () => lines.join('');

  const server = spawn(process.env.JAVA_BIN || 'java', ['-jar', jar], {
    cwd: ROOT,
    env: packagedEnvironment(process.env, {
      AUTH_ENABLED: 'false',
      ...requestedEnv
    }, pg.env, port),
    stdio: ['ignore', 'pipe', 'pipe']
  });
  server.stdout.on('data', append);
  server.stderr.on('data', append);

  let browser = null;
  try {
    await waitForReady(base, server, log);
    browser = await launchChromium();
  } catch (error) {
    if (processIsRunning(server)) {
      const exited = once(server, 'exit');
      server.kill();
      await Promise.race([exited, delay(3_000)]);
    }
    pg.drop();
    throw error;
  }

  const pageErrors = [];
  const serverErrors = [];
  const requests = [];
  const pages = [];

  function trackPage(page) {
    if (pages.includes(page)) return;
    pages.push(page);
    page.on('pageerror', error => pageErrors.push(error.stack || error.message));
    page.on('request', request => {
      const url = new URL(request.url());
      if (url.origin === base && url.pathname.startsWith('/api/')) {
        requests.push(`${request.method()} ${url.pathname}`);
      }
    });
    page.on('response', response => {
      const url = new URL(response.url());
      if (url.origin === base && url.pathname.startsWith('/api/') && response.status() >= 500) {
        serverErrors.push(`${response.status()} ${url.pathname}`);
      }
    });
  }

  async function newContext(viewport = { width: 1920, height: 1080 }) {
    return browser.newContext({ viewport });
  }

  async function newPage(viewport = { width: 1920, height: 1080 }) {
    const context = await newContext(viewport);
    const page = await context.newPage();
    page.setDefaultTimeout(30_000);
    trackPage(page);
    return { context, page };
  }

  async function stop() {
    fs.mkdirSync(DIAGNOSTICS, { recursive: true });
    try {
      for (let index = 0; index < pages.length; index++) {
        const page = pages[index];
        if (page.isClosed()) continue;
        try {
          await page.screenshot({
            path: path.join(DIAGNOSTICS, `${label}.${index + 1}.png`),
            fullPage: false
          });
          const state = await page.evaluate(() => ({
            url: location.href,
            title: document.title,
            readyState: document.readyState,
            appAuth: document.querySelector('#app')?.getAttribute('data-auth') || null,
            level: window.state?.level || null,
            bookPhase: window.DeskBackend?.state?.().book?.phase || null,
            workspaceRev: window.DeskBackend?.state?.().workspace?.receipt?.rev ?? null,
            decidePhase: window.decide?.backendPhase || null,
            visibleButtons: [...document.querySelectorAll('button:not([hidden])')]
              .filter(button => {
                const rect = button.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0;
              })
              .slice(0, 80)
              .map(button => (button.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 120))
          }));
          fs.writeFileSync(
            path.join(DIAGNOSTICS, `${label}.${index + 1}.state.json`),
            `${JSON.stringify(state, null, 2)}\n`
          );
        } catch (error) {
          append(`\nDiagnostic capture failed for page ${index + 1}: ${error.stack || error}\n`);
        }
      }
    } finally {
      try {
        if (browser) await browser.close();
      } catch (error) {
        append(`\nBrowser cleanup failed: ${error.stack || error}\n`);
      } finally {
        browser = null;
      }
      if (processIsRunning(server)) {
        const exited = once(server, 'exit');
        server.kill();
        await Promise.race([exited, delay(3_000)]);
      }
      if (processIsRunning(server)) {
        const exited = once(server, 'exit');
        server.kill('SIGKILL');
        await Promise.race([exited, delay(1_000)]);
      }
      pg.drop();
      fs.writeFileSync(path.join(DIAGNOSTICS, `${label}.server.log`), log());
    }
  }

  return {
    base,
    jar,
    log,
    newContext,
    newPage,
    pageErrors,
    requests,
    serverErrors,
    trackPage,
    stop
  };
}

module.exports = {
  freePort,
  ISOLATED_PRODUCT_ENV,
  packagedEnvironment,
  startPackagedApp
};
