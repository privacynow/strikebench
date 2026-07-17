'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const path = require('node:path');
const { chromium } = require('playwright');

let browser;

before(async () => { browser = await chromium.launch(); });
after(async () => { if (browser) await browser.close(); });

async function scenarioPage() {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  await page.setContent('<!doctype html><html><body><main id="root"></main></body></html>');
  await page.addStyleTag({ path: path.resolve(__dirname, '../src/main/resources/public/css/app.css') });
  await page.evaluate(() => {
    function append(parent, value) {
      if (value == null || value === false) return;
      if (Array.isArray(value)) { value.forEach(item => append(parent, item)); return; }
      parent.appendChild(value instanceof Node ? value : document.createTextNode(String(value)));
    }
    function el(tag, attrs, ...children) {
      const node = document.createElement(tag);
      Object.entries(attrs || {}).forEach(([key, value]) => {
        if (value == null) return;
        if (key === 'class') node.className = value;
        else if (key === 'html') node.innerHTML = value;
        else if (key.startsWith('on') && typeof value === 'function') {
          node.addEventListener(key.slice(2), value);
        } else if (key in node && key !== 'role' && !key.startsWith('aria-') && !key.startsWith('data-')) {
          try { node[key] = value; } catch (_) { node.setAttribute(key, String(value)); }
        } else node.setAttribute(key, String(value));
      });
      children.forEach(child => append(node, child));
      return node;
    }
    function choice(cfg) {
      let current = cfg.value;
      const group = el('div', { class: 'choice-group' });
      const wrap = el('div', { id: cfg.id || null },
        cfg.label ? el('div', { class: 'field-label' }, cfg.label) : null, group);
      function paint() {
        group.replaceChildren();
        cfg.options.forEach(option => group.appendChild(el('button', {
          type: 'button', class: 'choice-option' + (option.value === current ? ' active' : ''),
          'data-value': option.value, onclick: () => select(option.value)
        }, option.label)));
      }
      function select(value) {
        if (value === current) return;
        current = value; paint();
        if (cfg.onChange) cfg.onChange(value);
      }
      wrap.value = () => current;
      wrap.set = select;
      paint();
      return wrap;
    }
    window.UI = {
      el,
      info: key => el('button', { type: 'button', class: 'info-trigger', 'data-term': key }, 'i'),
      segmented: choice,
      chipSet: choice,
      expandable(summary, detail, opts) {
        opts = opts || {};
        const body = el('div', { class: 'xp-body' });
        let built = false;
        const head = el('button', { type: 'button', class: 'xp-head' }, summary);
        const wrap = el('div', { class: 'xp' }, head, body);
        function open() {
          if (!built) { append(body, typeof detail === 'function' ? detail() : detail); built = true; }
          wrap.classList.add('open');
        }
        head.addEventListener('click', open);
        if (opts.open) open();
        return wrap;
      }
    };
    window.Product = { Horizon: { sessions(value) {
      return value === 'week' ? 5 : value === 'month' ? 21 : value === 'quarter' ? 63 : 21;
    } } };
    window.Learn = { level: 'beginner', currentLevel() { return this.level; } };
    window.App = { context: { thesis() { return 'neutral'; } } };
    window.API = { get() {
      let close = 100;
      const candles = Array.from({ length: 80 }, (_, index) => {
        close *= 1 + (index % 5 === 0 ? 0.012 : index % 3 === 0 ? -0.007 : 0.003);
        return { close };
      });
      return Promise.resolve({ candles, evidence: { provenance: 'DEMO' } });
    } };
  });
  await page.addScriptTag({ path: path.resolve(__dirname, '../src/main/resources/public/js/scenario.js') });
  return page;
}

async function mount(page, level, state, seed) {
  await page.evaluate(({ level, state, seed }) => {
    Learn.level = level;
    window.__scenarioState = state;
    window.__scenarioForm = Scenario.form(level, 'AAPL', seed, window.__scenarioState);
    document.getElementById('root').replaceChildren(window.__scenarioForm.el);
  }, { level, state, seed });
}

async function receipt(page) {
  return page.evaluate(() => ({
    spec: window.__scenarioForm.getSpec(),
    iv: window.__scenarioForm.getIv(),
    state: JSON.parse(JSON.stringify(window.__scenarioState))
  }));
}

test('Beginner and Expert are presentation altitudes over one byte-identical scenario', async () => {
  const page = await scenarioPage();
  try {
    const state = { seed: 818181, whatifResults: { AAPL: {
      preview: { receipt: { fingerprint: 'shown-fan' }, bands: [{ day: 0, p50: 100 }] },
      ensemble: { id: 'pen_same', fingerprint: 'shown-fan' }
    } } };
    const seed = { thesis: 'bearish', horizonDays: 63 };
    await mount(page, 'beginner', state, seed);
    await page.waitForFunction(() => window.__scenarioState.calibration
      && window.__scenarioState.calibration.loaded);
    const beginner = await receipt(page);
    const beginnerControls = await page.evaluate(() => ({
      stories: document.querySelectorAll('#sc-shapes .sc-card').length,
      horizon: !!document.querySelector('#sc-horizon'),
      wildness: !!document.querySelector('#sc-mag')
    }));

    await mount(page, 'expert', beginner.state, seed);
    const expert = await receipt(page);
    const expertControls = await page.evaluate(() => ({
      stories: document.querySelectorAll('#sc-shapes .sc-card').length,
      horizon: !!document.querySelector('#sc-horizon'),
      wildness: !!document.querySelector('#sc-mag'),
      model: !!document.querySelector('#sc-model'),
      volBasis: document.querySelector('#sc-vol').dataset.basis,
      volValue: Number(document.querySelector('#sc-vol').value),
      ivValue: document.querySelector('#sc-iv').value,
      ivDisabled: document.querySelector('#sc-iv').disabled,
      ivNote: document.querySelector('#sc-iv-basis-note').textContent
    }));

    assert.deepEqual(expert.spec, beginner.spec,
      'level flip cannot change any path input, including model, seed, or calibration');
    assert.deepEqual(expert.iv, beginner.iv,
      'level flip cannot substitute an Expert-only IV path');
    assert.deepEqual(expert.state, beginner.state,
      'rendering Expert does not mutate the canonical owner state');
    assert.equal(expert.spec.seed, beginner.spec.seed);
    assert.deepEqual(expert.state.whatifResults, beginner.state.whatifResults,
      'the already-rendered result and its lineage survive the presentation-only level flip');
    assert.deepEqual(expertControls.stories, beginnerControls.stories);
    assert.ok(beginnerControls.horizon && beginnerControls.wildness
      && expertControls.horizon && expertControls.wildness && expertControls.model);
    assert.equal(expertControls.volBasis, 'lane');
    assert.ok(Math.abs(expertControls.volValue - beginner.spec.volAnnual * 100) < 0.0001,
      'Expert displays the Beginner lane-calibrated volatility, not another default');
    assert.equal(expertControls.ivValue, '');
    assert.equal(expertControls.ivDisabled, true);
    assert.match(expertControls.ivNote, /no hidden starting percentage/i);
    assert.equal(expert.iv, null, 'the lane ATM IV is resolved by the server at both levels');
  } finally {
    await page.close();
  }
});

test('Expert edits remain the same computation and readable receipt after returning to Beginner', async () => {
  const page = await scenarioPage();
  try {
    const state = { seed: 919191 };
    const seed = { thesis: 'volatile', horizonDays: 21 };
    await mount(page, 'expert', state, seed);
    await page.waitForFunction(() => window.__scenarioState.calibration
      && window.__scenarioState.calibration.loaded);
    await page.evaluate(() => {
      function change(id, value) {
        const input = document.getElementById(id);
        input.value = value;
        input.dispatchEvent(new Event('change', { bubbles: true }));
      }
      document.querySelector('#sc-model .choice-option[data-value="HESTON"]').click();
      change('sc-vol', 42);
      document.querySelector('#sc-iv-mode .choice-option[data-value="custom"]').click();
      change('sc-iv', 55);
      change('sc-ivlong', 40);
      change('sc-ivdrift', -7);
    });
    const expert = await receipt(page);
    await mount(page, 'beginner', expert.state, seed);
    const beginner = await receipt(page);
    await page.evaluate(() => {
      const head = Array.from(document.querySelectorAll('.xp-head'))
        .find(button => /every model assumption/i.test(button.textContent));
      head.click();
    });
    const facts = await page.textContent('#sc-assumption-facts');

    assert.deepEqual(beginner.spec, expert.spec);
    assert.deepEqual(beginner.iv, expert.iv);
    assert.equal(beginner.spec.model, 'HESTON');
    assert.equal(beginner.spec.volAnnual, 0.42);
    assert.equal(beginner.iv.startIv, 0.55);
    assert.equal(beginner.iv.longRunIv, 0.40);
    assert.match(facts, /42% annualized/);
    assert.match(facts, /Start 55%/);
    assert.match(facts, /Heston/);
  } finally {
    await page.close();
  }
});

test('an incomplete question has no silent shape or horizon', async () => {
  const page = await scenarioPage();
  try {
    await mount(page, 'beginner', { seed: 717171 }, {});
    const incomplete = await page.evaluate(() => {
      let error;
      try { window.__scenarioForm.getSpec(); } catch (caught) { error = caught.message; }
      return {
        shape: window.__scenarioState.shape,
        horizon: window.__scenarioState.horizon,
        activeShape: document.querySelectorAll('#sc-shapes .sc-card.active').length,
        activeHorizon: document.querySelectorAll('#sc-horizon .sym-chip.active').length,
        error,
        note: document.getElementById('sc-mag-note').textContent
      };
    });
    assert.equal(incomplete.shape, null);
    assert.equal(incomplete.horizon, null);
    assert.equal(incomplete.activeShape, 0);
    assert.equal(incomplete.activeHorizon, 0);
    assert.match(incomplete.error, /Choose a market story/);
    assert.match(incomplete.note, /will not silently assume one/);

    await page.click('#sc-shapes .sc-card[data-shape="SELLOFF_REBOUND"]');
    await page.click('#sc-horizon .sym-chip[data-days="10"]');
    const explicit = await receipt(page);
    assert.equal(explicit.spec.shape, 'SELLOFF_REBOUND');
    assert.equal(explicit.spec.horizonDays, 10);
  } finally {
    await page.close();
  }
});

test('the shared scenario controls use wide desktop space and reflow without mobile overflow', async () => {
  const page = await scenarioPage();
  try {
    const state = { seed: 616161 };
    const seed = { thesis: 'bullish', horizonDays: 21 };
    await page.setViewportSize({ width: 2560, height: 1200 });
    await mount(page, 'expert', state, seed);
    const desktop = await page.evaluate(() => ({
      storyColumns: getComputedStyle(document.getElementById('sc-shapes')).gridTemplateColumns.split(' ').length,
      parameterColumns: getComputedStyle(document.querySelector('.sc-param-grid')).gridTemplateColumns.split(' ').length,
      overflow: document.documentElement.scrollWidth - window.innerWidth
    }));
    assert.equal(desktop.storyColumns, 4, 'wide screens receive four useful story columns');
    assert.equal(desktop.parameterColumns, 2, 'dense parameters use the rail in readable pairs');
    assert.ok(desktop.overflow <= 0, 'the 2560-wide form does not create horizontal waste or overflow');

    await page.setViewportSize({ width: 390, height: 844 });
    const mobile = await page.evaluate(() => ({
      primaryColumns: getComputedStyle(document.querySelector('.sc-primary-controls')).gridTemplateColumns.split(' ').length,
      parameterColumns: getComputedStyle(document.querySelector('.sc-param-grid')).gridTemplateColumns.split(' ').length,
      overflow: document.documentElement.scrollWidth - window.innerWidth,
      widestControl: Math.max(...Array.from(document.querySelectorAll('.scenario-form input, .scenario-form button'))
        .map(node => node.getBoundingClientRect().right))
    }));
    assert.equal(mobile.primaryColumns, 1);
    assert.equal(mobile.parameterColumns, 1);
    assert.ok(mobile.overflow <= 0, 'the 390px form stays inside the viewport');
    assert.ok(mobile.widestControl <= 390, 'all interactive controls stay reachable on mobile');
  } finally {
    await page.close();
  }
});

test('Canvas helpers refuse hidden 30% IV and hidden 21-session stock horizons', async () => {
  const page = await scenarioPage();
  try {
    const result = await page.evaluate(() => {
      const missingPreview = { receipt: { spec: { volAnnual: 0 } }, canvas: { underlying: [] } };
      const pricedPreview = { receipt: { spec: { volAnnual: 0.42 } }, canvas: { underlying: [] } };
      const serverDay = { receipt: { spec: { volAnnual: 0.42 } },
        canvas: { underlying: [{ day: 3, atmIv: 0.37 }] } };
      const stock = { strategy: 'STOCK', legs: [
        { action: 'BUY', type: 'STOCK', stock: true, ratio: 100, multiplier: 1 }
      ] };
      const malformedOption = { strategy: 'LONG_CALL', legs: [
        { action: 'BUY', type: 'CALL', strike: 100, expiration: '', ratio: 1, multiplier: 100 }
      ] };
      const datedOption = { strategy: 'LONG_CALL', legs: [
        { action: 'BUY', type: 'CALL', strike: 100, expiration: '2027-08-20', ratio: 1, multiplier: 100 }
      ] };
      return {
        missingIv: Scenario.contracts.canvasIvValue(missingPreview, { ivNodes: [] }, 0),
        receiptIv: Scenario.contracts.canvasIvValue(pricedPreview, { ivNodes: [] }, 0),
        serverDayIv: Scenario.contracts.canvasIvValue(serverDay, { ivNodes: [] }, 3),
        explicitIv: Scenario.contracts.canvasIvValue(missingPreview, { ivNodes: [
          { dayIndex: 0, atmIv: 0.25 }, { dayIndex: 4, atmIv: 0.45 }
        ] }, 2),
        stockMissing: Scenario.contracts.realisticHorizon(stock, [
          { action: 'BUY', type: 'STOCK', expiryDay: 0 }
        ], {}),
        stockDeclared: Scenario.contracts.realisticHorizon(stock, [
          { action: 'BUY', type: 'STOCK', expiryDay: 0 }
        ], { horizonDays: 47 }),
        malformed: Scenario.contracts.realisticHorizon(malformedOption, [
          { action: 'BUY', type: 'CALL', expiryDay: 30 }
        ], {}),
        dated: Scenario.contracts.realisticHorizon(datedOption, [
          { action: 'BUY', type: 'CALL', expiryDay: 29 }
        ], {})
      };
    });

    assert.equal(result.missingIv, null,
      'a zero or absent receipt IV stays unavailable instead of becoming 30%');
    assert.equal(result.receiptIv, 0.42, 'the exact server receipt IV remains usable');
    assert.equal(result.serverDayIv, 0.37, 'a server-priced day owns the displayed IV');
    assert.ok(Math.abs(result.explicitIv - 0.35) < 1e-12,
      'explicit authored IV nodes interpolate without another source');
    assert.equal(result.stockMissing.days, null);
    assert.match(result.stockMissing.error, /explicit declared horizon.*no 21-session month/i);
    assert.equal(result.stockDeclared.days, 47);
    assert.equal(result.stockDeclared.source, 'declared horizon');
    assert.equal(result.malformed.days, null);
    assert.match(result.malformed.error, /exact valid expiration/i);
    assert.deepEqual(result.dated, { days: 29, source: 'earliest exact option expiration', error: null });
  } finally {
    await page.close();
  }
});
