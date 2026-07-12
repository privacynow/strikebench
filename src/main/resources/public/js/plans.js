/* StrikeBench Plan store: durable journey facts from /api/plans, transient focus in workspace. */
(function () {
  'use strict';

  var items = [];
  var initialized = false;
  var loading = null;
  var pendingFocusId = null;

  var STAGE_PATH = {
    UNDERSTAND: 'understand', EVIDENCE: 'evidence', STRATEGY: 'strategy',
    OUTCOMES: 'outcomes', DECIDE: 'decide', MANAGE_REVIEW: 'manage-review'
  };

  function currentMarket() {
    var world = (window.App && App.state.world) || (window.App && App.config && App.config.world) || 'observed';
    return { kind: world === 'demo' ? 'DEMO' : world === 'observed' ? 'OBSERVED' : 'SIMULATED',
      world: world };
  }

  function requestId() {
    if (window.crypto && window.crypto.randomUUID) return 'plan-' + window.crypto.randomUUID();
    return 'plan-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  }

  function replace(plan) {
    var i = items.findIndex(function (p) { return p.id === plan.id; });
    if (i >= 0) items[i] = plan; else items.unshift(plan);
    if (window.App) {
      App.state.plans = items.slice();
      if (!App.state.activePlanId && plan.open !== false) App.state.activePlanId = plan.id;
    }
    renderBar();
    return plan;
  }

  async function load(force) {
    if (loading) return loading;
    loading = (force ? API.getFresh('/api/plans') : API.get('/api/plans')).then(function (r) {
      items = (r && r.plans) || [];
      initialized = true;
      App.state.plans = items.slice();
      if (App.state.activePlanId && !items.some(function (p) { return p.id === App.state.activePlanId; })) {
        App.state.activePlanId = null;
      }
      if (!App.state.activePlanId && items.length) App.state.activePlanId = items[0].id;
      renderBar();
      return items;
    }).finally(function () { loading = null; });
    return loading;
  }

  async function get(id, force) {
    var found = items.find(function (p) { return p.id === id; });
    if (found && !force) return found;
    return replace(await (force ? API.getFresh('/api/plans/' + id) : API.get('/api/plans/' + id)));
  }

  async function create(fields) {
    var body = Object.assign({ clientRequestId: requestId() }, fields || {});
    var plan = replace(await API.post('/api/plans', body));
    App.state.activePlanId = plan.id;
    App.state.provisionalPlan = null;
    if (window.Workspace) Workspace.save();
    return plan;
  }

  async function promote(fields) {
    var provisional = App.state.provisionalPlan || {};
    return create(Object.assign({}, provisional, fields || {}, {
      symbol: (fields && fields.symbol) || provisional.symbol,
      clientRequestId: provisional.clientRequestId || requestId()
    }));
  }

  function provisional(symbol) {
    symbol = String(symbol || '').trim().toUpperCase();
    var market = currentMarket();
    var existing = App.state.provisionalPlan;
    if (!existing || existing.symbol !== symbol || existing.marketKind !== market.kind
        || existing.worldId !== (market.kind === 'SIMULATED' ? market.world : null)) {
      existing = { clientRequestId: requestId(), symbol: symbol, marketKind: market.kind,
        worldId: market.kind === 'SIMULATED' ? market.world : null };
      App.state.provisionalPlan = existing;
    }
    return existing;
  }

  function active() {
    return items.find(function (p) { return p.id === App.state.activePlanId; }) || null;
  }

  function ui(planId) {
    App.state.planUi = App.state.planUi || {};
    return App.state.planUi[planId] = App.state.planUi[planId] || {};
  }

  function path(plan, stage) {
    var key = String(stage || plan.activeStage || 'UNDERSTAND').toUpperCase().replace('-', '_');
    return '#/plan/' + plan.id + '/' + (STAGE_PATH[key] || 'understand');
  }

  async function focus(plan, stage) {
    if (typeof plan === 'string') plan = await get(plan);
    var market = currentMarket();
    var target = plan.marketKind === 'SIMULATED' ? plan.worldId
      : plan.marketKind === 'DEMO' ? 'demo' : 'observed';
    if (market.world !== target) {
      pendingFocusId = plan.id;
      try {
        var switched = await App.switchWorld(target);
        if (!switched) throw new Error('The plan could not open because its market did not switch.');
        await load(true);
        plan = await get(plan.id, true);
      } finally { pendingFocusId = null; }
    }
    App.state.activePlanId = plan.id;
    renderBar();
    App.navigate(path(plan, stage));
    if (window.Workspace) Workspace.save();
    return plan;
  }

  async function setStage(plan, stage) {
    var updated = replace(await API.put('/api/plans/' + plan.id + '/stage', {
      expectedVersion: plan.version, stage: stage
    }));
    App.state.activePlanId = updated.id;
    return updated;
  }

  async function updateContext(plan, patch) {
    return replace(await API.put('/api/plans/' + plan.id + '/context',
      Object.assign({ expectedVersion: plan.version }, patch || {})));
  }

  async function claimIntent(plan, intent) {
    return replace(await API.put('/api/plans/' + plan.id + '/intent', {
      expectedVersion: plan.version, intent: intent
    }));
  }

  async function latestStrategy(planId, force) {
    return force ? API.getFresh('/api/plans/' + planId + '/strategy/latest')
      : API.get('/api/plans/' + planId + '/strategy/latest');
  }

  async function runStrategy(plan, controls) {
    var out = await API.post('/api/plans/' + plan.id + '/strategy/run', controls || {});
    if (out.plan) replace(out.plan);
    return out;
  }

  async function selectCandidate(plan, candidateId) {
    var out = await API.put('/api/plans/' + plan.id + '/strategy/select', {
      expectedVersion: plan.version, candidateId: candidateId
    });
    if (out.plan) replace(out.plan);
    return out;
  }

  async function saveCustom(plan, position) {
    var out = await API.post('/api/plans/' + plan.id + '/strategy/custom', {
      expectedVersion: plan.version, position: position
    });
    if (out.plan) replace(out.plan);
    return out;
  }

  function latestScout(planId, scope, force) {
    var path = '/api/plans/' + planId + '/scout/latest?scope=' + encodeURIComponent(scope || 'PEERS');
    return force ? API.getFresh(path) : API.get(path);
  }

  async function runScout(plan, controls) {
    return API.post('/api/plans/' + plan.id + '/scout/run', controls || {});
  }

  async function spawnScoutedPlan(plan, candidateId, role) {
    var out = await API.post('/api/plans/' + plan.id + '/scout/spawn', {
      clientRequestId: requestId(), candidateId: candidateId, role: role
    });
    if (out.plan) {
      replace(out.plan); App.state.activePlanId = out.plan.id;
    }
    return out;
  }

  function latestOutcomes(planId, force) {
    var path = '/api/plans/' + planId + '/outcomes/latest';
    return force ? API.getFresh(path) : API.get(path);
  }

  async function runEnsemble(plan, request) {
    var out = await API.post('/api/plans/' + plan.id + '/outcomes/ensemble',
      Object.assign({ expectedVersion: plan.version }, request || {}));
    if (out.plan) replace(out.plan);
    return out;
  }

  async function runOutcome(plan, request) {
    var out = await API.post('/api/plans/' + plan.id + '/outcomes/run',
      Object.assign({ expectedVersion: plan.version }, request || {}));
    if (out.plan) replace(out.plan);
    return out;
  }

  async function runBacktest(plan, request) {
    var out = await API.post('/api/plans/' + plan.id + '/outcomes/backtest',
      Object.assign({ expectedVersion: plan.version }, request || {}));
    if (out.plan) replace(out.plan);
    return out;
  }

  function latestDecision(planId, force) {
    var path = '/api/plans/' + planId + '/decision/latest';
    return force ? API.getFresh(path) : API.get(path);
  }

  async function previewDecision(plan, request) {
    return API.post('/api/plans/' + plan.id + '/decision/preview',
      Object.assign({ expectedVersion: plan.version }, request || {}));
  }

  async function tradeDecision(plan, request) {
    var out = await API.post('/api/plans/' + plan.id + '/decision/trade',
      Object.assign({ expectedVersion: plan.version }, request || {}));
    if (out.plan) replace(out.plan);
    return out;
  }

  async function cashDecision(plan, request) {
    var out = await API.post('/api/plans/' + plan.id + '/decision/cash',
      Object.assign({ expectedVersion: plan.version }, request || {}));
    if (out.plan) replace(out.plan);
    return out;
  }

  async function closeChip(plan) {
    var wasActive = App.state.activePlanId === plan.id;
    var updated = await API.put('/api/plans/' + plan.id + '/open', {
      expectedVersion: plan.version, open: false
    });
    items = items.filter(function (p) { return p.id !== updated.id; });
    App.state.plans = items.slice();
    if (wasActive) App.state.activePlanId = items.length ? items[0].id : null;
    renderBar();
    if (window.Workspace) Workspace.save();
    if (wasActive) {
      if (items.length) await focus(items[0]);
      else App.navigate('#/home');
    }
    return updated;
  }

  async function marketChanged() {
    if (!initialized) return;
    await load(true);
    var match = (window.location.hash || '').match(/^#\/plan\/([^/?]+)/);
    if (match && match[1] !== 'new' && match[1] !== pendingFocusId
        && !items.some(function (p) { return p.id === match[1]; })) {
      if (UI.toast) UI.toast('This market has a different set of plans');
      window.location.hash = '#/home';
    }
  }

  function renderBar() {
    var host = document.getElementById('plan-bar-root');
    if (!host || !window.UI || !window.App) return;
    host.innerHTML = '';
    if (!items.length && !(window.location.hash || '').startsWith('#/plan/')) {
      host.hidden = true; return;
    }
    host.hidden = false;
    var inner = UI.el('div', { class: 'plan-bar-inner' });
    inner.appendChild(UI.el('span', { class: 'plan-bar-label' }, 'Plans'));
    items.forEach(function (plan) {
      var planLabel = plan.title.replace(plan.symbol + ' · ', '')
        + (plan.context && plan.context.horizonDays ? ' · ' + plan.context.horizonDays + ' days' : '');
      var chip = UI.el('div', { class: 'plan-chip' + (plan.id === App.state.activePlanId ? ' active' : ''),
        'data-plan-id': plan.id },
        UI.el('button', { type: 'button', class: 'plan-chip-main', 'aria-label': 'Open ' + plan.symbol + ' · ' + planLabel,
          onclick: function () { focus(plan).catch(function (e) { UI.toast(e.message, 'error'); }); } },
          UI.el('span', { class: 'plan-chip-symbol' }, plan.symbol),
          UI.el('span', { class: 'plan-chip-title' }, planLabel)),
        UI.el('button', { type: 'button', class: 'plan-chip-close', 'aria-label': 'Remove ' + plan.title + ' from open plans',
          onclick: function (event) {
            event.stopPropagation(); closeChip(plan).catch(function (e) { UI.toast(e.message, 'error'); });
          } }, '×'));
      inner.appendChild(chip);
    });
    inner.appendChild(UI.el('button', { type: 'button', class: 'plan-new-btn',
      onclick: function () { App.navigate('#/research'); } }, '+ New plan'));
    host.appendChild(inner);
  }

  window.PlanStore = {
    init: function () { return load(true); }, load: load, get: get, create: create, promote: promote,
    provisional: provisional, active: active, focus: focus, path: path, setStage: setStage,
    updateContext: updateContext, claimIntent: claimIntent, closeChip: closeChip,
    latestStrategy: latestStrategy, runStrategy: runStrategy,
    selectCandidate: selectCandidate, saveCustom: saveCustom,
    latestScout: latestScout, runScout: runScout, spawnScoutedPlan: spawnScoutedPlan,
    latestOutcomes: latestOutcomes, runEnsemble: runEnsemble,
    runOutcome: runOutcome, runBacktest: runBacktest,
    latestDecision: latestDecision, previewDecision: previewDecision,
    tradeDecision: tradeDecision, cashDecision: cashDecision,
    marketChanged: marketChanged, renderBar: renderBar, ui: ui,
    all: function () { return items.slice(); }
  };
})();
