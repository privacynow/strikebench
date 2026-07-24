'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const publicJs = path.resolve(__dirname, '../src/main/resources/public/js');
let browser;

before(async () => { browser = await chromium.launch(); });
after(async () => { if (browser) await browser.close(); });

async function appHarness() {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  await page.setContent('<!doctype html><html><body style="min-height:2600px"><main id="app" data-ready="true"><div class="route-mount"></div></main></body></html>');
  await page.addScriptTag({ path: path.join(publicJs, 'ui.js') });
  let source = fs.readFileSync(path.join(publicJs, 'app.js'), 'utf8');
  const bootBlock = `  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }`;
  assert.ok(source.includes(bootBlock), 'test harness disables only the production boot call');
  source = source.replace(bootBlock, '  window.__spaTestBoot = boot;');
  await page.addScriptTag({ content: source });
  await page.evaluate(() => {
    window.Workspace = { saves: 0, save() { this.saves += 1; } };
    window.Product = window.Product || { Routes: { valid: () => true, navOwner: route => route } };
    location.hash = '#/home';
  });
  return page;
}

test('ordinary destination refresh keeps the route mount, draft, focus, scroll, disclosure and subscriptions', async () => {
  const page = await appHarness();
  try {
    const result = await page.evaluate(async () => {
      const appRoot = document.getElementById('app');
      const mount = document.querySelector('.route-mount');
      let paints = 0;
      async function view(host) {
        paints += 1;
        const input = document.createElement('input');
        input.id = 'owner-draft'; input.value = 'server value';
        const fact = document.createElement('p');
        fact.id = 'fresh-fact'; fact.textContent = 'paint ' + paints;
        host.append(input, UI.expandable('Advanced draft', () => {
          const detail = document.createElement('p'); detail.textContent = 'retained detail'; return detail;
        }, { stateKey: 'spa-owner-disclosure' }), fact);
        App.onEvent('owner.test', () => {}, App.navToken);
      }
      App.navToken = 7;
      App._eventHandlers = [];
      await view(mount);
      mount.querySelector('.xp-head').click();
      const input = document.getElementById('owner-draft');
      input.value = 'uncommitted owner draft'; input.focus(); input.setSelectionRange(5, 11);
      window.scrollTo(0, 360);
      const rootRef = appRoot, mountRef = mount;
      App.onEvent('global.test', () => {}); // app-wide subscription must survive route refresh
      App._destinationSeam = {
        key: '#/home', hash: '#/home', route: 'home', params: [], token: 7,
        mount, view, refreshing: null, queued: false
      };
      await App.refreshDestination();
      return {
        sameRoot: rootRef === document.getElementById('app'),
        sameMount: mountRef === document.querySelector('.route-mount'),
        draft: document.getElementById('owner-draft').value,
        focus: document.activeElement && document.activeElement.id,
        selection: [document.activeElement.selectionStart, document.activeElement.selectionEnd],
        scroll: window.scrollY,
        expanded: document.querySelector('.xp-head').getAttribute('aria-expanded'),
        detail: document.querySelector('.xp-body').textContent,
        routeHandlers: App._eventHandlers.filter(handler => handler.token === 7).length,
        globalHandlers: App._eventHandlers.filter(handler => handler.token === undefined).length,
        paints,
        ready: appRoot.getAttribute('data-ready')
      };
    });
    assert.equal(result.sameRoot, true);
    assert.equal(result.sameMount, true);
    assert.equal(result.draft, 'uncommitted owner draft');
    assert.equal(result.focus, 'owner-draft');
    assert.deepEqual(result.selection, [5, 11]);
    assert.equal(result.scroll, 360);
    assert.equal(result.expanded, 'true');
    assert.match(result.detail, /retained detail/);
    assert.equal(result.routeHandlers, 1, 'old route subscription retired; refreshed owner registers once');
    assert.equal(result.globalHandlers, 1, 'app-wide subscription survives');
    assert.equal(result.paints, 2);
    assert.equal(result.ready, 'true');
  } finally { await page.close(); }
});

test('component refresh preserves its panel and neighbors, and a lens change waits for pending work', async () => {
  const page = await appHarness();
  try {
    const result = await page.evaluate(async () => {
      const routeMount = document.querySelector('.route-mount');
      const neighbor = document.createElement('input');
      neighbor.id = 'neighbor-draft'; neighbor.value = 'do not touch';
      const panel = document.createElement('section'); panel.id = 'portfolio-mode-panel';
      routeMount.append(neighbor, panel);
      let panelPaints = 0;
      async function paintPanel(host) {
        panelPaints += 1;
        const input = document.createElement('input');
        input.id = 'book-settings-draft'; input.value = 'saved fact';
        host.append(input, document.createTextNode('panel ' + panelPaints));
      }
      await paintPanel(panel);
      const draft = document.getElementById('book-settings-draft');
      draft.value = 'editing account name'; draft.focus();
      const panelRef = panel, neighborRef = neighbor, routeRef = routeMount;
      await App.refreshComponent(panel, paintPanel, { routeError: 'portfolio' });
      const preservedDraft = panelRef.querySelector('#book-settings-draft').value;
      panelRef.querySelector('#book-settings-draft').value = 'old account must not leak';
      await App.refreshComponent(panel, paintPanel, { routeError: 'portfolio', preserveDraft: false });
      const componentState = {
        sameRoute: routeRef === document.querySelector('.route-mount'),
        samePanel: panelRef === document.getElementById('portfolio-mode-panel'),
        sameNeighbor: neighborRef === document.getElementById('neighbor-draft'),
        neighborValue: neighborRef.value,
        preservedDraft,
        resetDraft: panelRef.querySelector('#book-settings-draft').value,
        panelFocus: document.activeElement && document.activeElement.id,
        panelPaints
      };

      let destinationPaints = 0;
      async function destinationView(host) {
        destinationPaints += 1;
        const busy = document.createElement('button'); busy.id = 'pending-owner';
        busy.setAttribute('aria-busy', 'true'); host.appendChild(busy);
      }
      routeMount.replaceChildren();
      await destinationView(routeMount);
      App.navToken = 11;
      App._destinationSeam = { key: '#/home', hash: '#/home', route: 'home', params: [], token: 11,
        mount: routeMount, view: destinationView, refreshing: null, queued: false };
      const lens = App.refreshLens();
      await new Promise(resolve => setTimeout(resolve, 25));
      const pendingStayedMounted = destinationPaints === 1
        && document.getElementById('pending-owner').getAttribute('aria-busy') === 'true';
      document.getElementById('pending-owner').removeAttribute('aria-busy');
      await lens;
      return Object.assign(componentState, {
        pendingStayedMounted,
        destinationPaints
      });
    });
    assert.equal(result.sameRoute, true);
    assert.equal(result.samePanel, true);
    assert.equal(result.sameNeighbor, true);
    assert.equal(result.neighborValue, 'do not touch');
    assert.equal(result.preservedDraft, 'editing account name');
    assert.equal(result.resetDraft, 'saved fact', 'account/year switches may opt out of cross-owner draft restore');
    assert.equal(result.panelFocus, 'book-settings-draft');
    assert.equal(result.panelPaints, 3);
    assert.equal(result.pendingStayedMounted, true, 'pending owner stays connected until it clears aria-busy');
    assert.equal(result.destinationPaints, 2);
  } finally { await page.close(); }
});

test('only boot, hash navigation, route-error retry, and no-owner fallback may call App.render', () => {
  const files = fs.readdirSync(publicJs).filter(name => name.endsWith('.js'));
  const calls = [];
  files.forEach(file => {
    fs.readFileSync(path.join(publicJs, file), 'utf8').split('\n').forEach((line, index) => {
      if (/\bApp\.render\s*\(/.test(line) && !/^\s*(?:\/\/|\*)/.test(line)) {
        calls.push({ file, line: index + 1, text: line.trim() });
      }
    });
  });
  assert.deepEqual(calls.map(call => call.file), ['app.js', 'app.js', 'app.js', 'app.js']);
  assert.ok(calls.some(call => call.text.includes('route-retry')));
  assert.equal(calls.filter(call => call.text === 'App.render();').length, 2,
    'hashchange and initial boot are the only unconditional root renders');
  assert.equal(calls.filter(call => call.text === 'await App.render();').length, 1,
    'no-mounted-owner correctness fallback remains explicit');
});
