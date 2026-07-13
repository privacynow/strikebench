/* StrikeBench Plan store: durable journey facts from /api/plans, transient focus in workspace. */
(function () {
  'use strict';

  var items = [];
  var collections = {};
  var libraryItems = [];
  var libraryLoaded = false;
  var initialized = false;
  var loadingByMarket = {};
  var libraryLoading = null;
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

  function marketKey(kind, world) {
    return String(kind || '').toUpperCase() === 'SIMULATED' ? String(world || '')
      : String(kind || '').toUpperCase() === 'DEMO' ? 'demo' : 'observed';
  }

  function currentMarketKey() { return currentMarket().world; }
  function planMarketKey(plan) { return marketKey(plan && plan.marketKind, plan && plan.worldId); }

  function upsert(list, plan, include) {
    var next = (list || []).filter(function (p) { return p.id !== plan.id; });
    if (include) next.unshift(plan);
    return next;
  }

  function syncCurrent(key) {
    if (key !== currentMarketKey()) return;
    items = (collections[key] || []).slice();
    App.state.plans = items.slice();
    App.state.planCollections = Object.assign({}, collections);
    App.state.activePlanByMarket = App.state.activePlanByMarket || {};
    var remembered = App.state.activePlanByMarket[key];
    var active = items.some(function (p) { return p.id === remembered; }) ? remembered
      : items.some(function (p) { return p.id === App.state.activePlanId; }) ? App.state.activePlanId
      : items.length ? items[0].id : null;
    App.state.activePlanId = active;
    App.state.activePlanByMarket[key] = active;
    renderBar();
  }

  function rememberActive(plan) {
    var key = planMarketKey(plan);
    App.state.activePlanByMarket = App.state.activePlanByMarket || {};
    App.state.activePlanByMarket[key] = plan.id;
    if (key === currentMarketKey()) App.state.activePlanId = plan.id;
  }

  function requestId() {
    if (window.crypto && window.crypto.randomUUID) return 'plan-' + window.crypto.randomUUID();
    return 'plan-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  }

  function replace(plan) {
    var key = planMarketKey(plan);
    var open = plan.open !== false && plan.status !== 'ARCHIVED';
    collections[key] = upsert(collections[key], plan, open);
    libraryItems = upsert(libraryItems, plan, true);
    if (open && !(App.state.activePlanByMarket || {})[key]) rememberActive(plan);
    syncCurrent(key);
    return plan;
  }

  async function load(force) {
    var requestedKey = currentMarketKey();
    if (loadingByMarket[requestedKey]) return loadingByMarket[requestedKey];
    loadingByMarket[requestedKey] = (force ? API.getFresh('/api/plans') : API.get('/api/plans')).then(function (r) {
      var key = r && r.world ? String(r.world) : requestedKey;
      collections[key] = ((r && r.plans) || []).filter(function (p) {
        return p.open !== false && p.status !== 'ARCHIVED';
      });
      initialized = true;
      syncCurrent(key);
      return collections[key].slice();
    }).finally(function () { delete loadingByMarket[requestedKey]; });
    return loadingByMarket[requestedKey];
  }

  async function library(force) {
    if (libraryLoading) return libraryLoading;
    var path = '/api/plans?scope=all&openOnly=false';
    libraryLoading = (force ? API.getFresh(path) : API.get(path)).then(function (r) {
      libraryItems = (r && r.plans) || [];
      libraryLoaded = true;
      var grouped = {};
      libraryItems.forEach(function (plan) {
        if (plan.open === false || plan.status === 'ARCHIVED') return;
        var key = planMarketKey(plan);
        grouped[key] = grouped[key] || [];
        grouped[key].push(plan);
      });
      Object.keys(grouped).forEach(function (key) { collections[key] = grouped[key]; });
      syncCurrent(currentMarketKey());
      return libraryItems.slice();
    }).finally(function () { libraryLoading = null; });
    return libraryLoading;
  }

  async function matching(symbol, intent) {
    symbol = String(symbol || '').trim().toUpperCase();
    intent = intent == null ? null : String(intent).trim().toUpperCase();
    var all = await library(true);
    return all.filter(function (plan) {
      return plan.open !== false && plan.status !== 'ARCHIVED'
        && planMarketKey(plan) === currentMarketKey()
        && plan.symbol === symbol && (plan.intent || null) === intent;
    });
  }

  async function refresh() {
    await Promise.all([load(true), library(true)]);
    if ((window.location.hash || '#/home').startsWith('#/home') && App.render) await App.render();
    return items.slice();
  }

  function findKnown(id) {
    // The same Plan can appear in the current collection, a stashed collection, and the
    // all-market library. SSE/library refreshes complete independently; an older library row
    // must never shadow a lifecycle update just loaded for the active market.
    var candidates = [];
    var current = (collections[currentMarketKey()] || []).find(function (p) { return p.id === id; });
    if (current) candidates.push(current);
    Object.keys(collections).forEach(function (key) {
      if (key === currentMarketKey()) return;
      var found = (collections[key] || []).find(function (p) { return p.id === id; });
      if (found) candidates.push(found);
    });
    var library = libraryItems.find(function (p) { return p.id === id; });
    if (library) candidates.push(library);
    return candidates.reduce(function (best, plan) {
      return !best || Number(plan.version || 0) > Number(best.version || 0) ? plan : best;
    }, null);
  }

  async function get(id, force) {
    var found = findKnown(id);
    if (found && !force) return found;
    return replace(await (force ? API.getFresh('/api/plans/' + id) : API.get('/api/plans/' + id)));
  }

  async function create(fields) {
    var body = Object.assign({ clientRequestId: requestId() }, fields || {});
    var plan = replace(await API.post('/api/plans', body));
    rememberActive(plan);
    App.state.provisionalPlansByMarket = App.state.provisionalPlansByMarket || {};
    delete App.state.provisionalPlansByMarket[currentMarketKey()];
    if (window.Workspace) Workspace.save();
    return plan;
  }

  async function promote(fields) {
    var provisional = currentDraft() || {};
    return create(Object.assign({}, provisional, fields || {}, {
      symbol: (fields && fields.symbol) || provisional.symbol,
      clientRequestId: provisional.clientRequestId || requestId()
    }));
  }

  function currentDraft() {
    return (App.state.provisionalPlansByMarket || {})[currentMarketKey()] || null;
  }

  function provisional(symbol) {
    symbol = String(symbol || '').trim().toUpperCase();
    var market = currentMarket();
    App.state.provisionalPlansByMarket = App.state.provisionalPlansByMarket || {};
    var key = currentMarketKey();
    var existing = App.state.provisionalPlansByMarket[key];
    if (!existing || existing.symbol !== symbol || existing.marketKind !== market.kind
        || existing.worldId !== (market.kind === 'SIMULATED' ? market.world : null)) {
      existing = Object.assign({}, existing || {}, { clientRequestId: requestId(), symbol: symbol,
        marketKind: market.kind, worldId: market.kind === 'SIMULATED' ? market.world : null });
      App.state.provisionalPlansByMarket[key] = existing;
    }
    return existing;
  }

  function active() {
    return findKnown(App.state.activePlanId) || null;
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
    if (plan.open === false && plan.status !== 'ARCHIVED') {
      plan = replace(await API.put('/api/plans/' + plan.id + '/open', {
        expectedVersion: plan.version, open: true
      }));
    } else replace(plan);
    rememberActive(plan);
    renderBar();
    App.navigate(path(plan, stage));
    if (window.Workspace) Workspace.save();
    return plan;
  }

  async function setStage(plan, stage) {
    var updated = replace(await API.put('/api/plans/' + plan.id + '/stage', {
      expectedVersion: plan.version, stage: stage
    }));
    rememberActive(updated);
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

  async function fitStrategy(plan, controls) {
    var out = await API.post('/api/plans/' + plan.id + '/strategy/fit',
      Object.assign({ expectedVersion: plan.version }, controls || {}));
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
      replace(out.plan); rememberActive(out.plan);
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

  function rehearsals(planId, force) {
    var path = '/api/plans/' + planId + '/rehearsals';
    return force ? API.getFresh(path) : API.get(path);
  }

  async function createRehearsal(plan, request) {
    var out = await API.post('/api/plans/' + plan.id + '/rehearsals',
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

  function latestManagement(planId, force) {
    var path = '/api/plans/' + planId + '/manage';
    return force ? API.getFresh(path) : API.get(path);
  }

  async function manage(plan, action, request) {
    var out = await API.post('/api/plans/' + plan.id + '/manage/' + action,
      Object.assign({ expectedVersion: plan.version }, request || {}));
    if (out.plan) replace(out.plan);
    return out;
  }

  async function reviewCash(plan) {
    var out = await API.post('/api/plans/' + plan.id + '/manage/review', { expectedVersion: plan.version });
    if (out.plan) replace(out.plan);
    return out;
  }

  async function closeChip(plan) {
    var wasActive = App.state.activePlanId === plan.id;
    var updated = await API.put('/api/plans/' + plan.id + '/open', {
      expectedVersion: plan.version, open: false
    });
    replace(updated);
    items = (collections[currentMarketKey()] || []).slice();
    if (wasActive) {
      App.state.activePlanId = items.length ? items[0].id : null;
      App.state.activePlanByMarket[currentMarketKey()] = App.state.activePlanId;
    }
    syncCurrent(currentMarketKey());
    if (window.Workspace) Workspace.save();
    if (wasActive) {
      if (items.length) await focus(items[0]);
      else App.navigate('#/home');
    }
    return updated;
  }

  async function archive(plan) {
    var wasActive = App.state.activePlanId === plan.id;
    var updated = replace(await API.post('/api/plans/' + plan.id + '/archive', {
      expectedVersion: plan.version
    }));
    await library(true);
    if (wasActive) {
      App.state.activePlanId = items.length ? items[0].id : null;
      App.state.activePlanByMarket[currentMarketKey()] = App.state.activePlanId;
      syncCurrent(currentMarketKey());
    }
    if (window.Workspace) Workspace.save();
    return updated;
  }

  async function marketChanged() {
    if (!initialized) return;
    await load(true);
    var match = (window.location.hash || '').match(/^#\/plan\/([^/?]+)/);
    if (match && match[1] !== 'new' && match[1] !== pendingFocusId
        && !items.some(function (p) { return p.id === match[1]; })) {
      if (UI.toast) UI.toast('Your Plans are preserved, but they belong to another market. Open one from Home to switch markets.');
      window.location.hash = '#/home';
    }
  }

  function baseTitle(plan) {
    var prefix = plan.symbol + ' · ';
    return String(plan.title || '').startsWith(prefix)
      ? String(plan.title).slice(prefix.length) : String(plan.title || plan.intent || 'Plan');
  }

  function identity(plan, pool) {
    pool = pool || items;
    var title = baseTitle(plan);
    var key = [planMarketKey(plan), plan.symbol, String(plan.intent || ''), title,
      plan.context && plan.context.horizonDays || ''].join('|');
    var cohort = pool.filter(function (candidate) {
      return [planMarketKey(candidate), candidate.symbol, String(candidate.intent || ''), baseTitle(candidate),
        candidate.context && candidate.context.horizonDays || ''].join('|') === key;
    }).sort(function (a, b) {
      var byTime = String(a.createdAt || '').localeCompare(String(b.createdAt || ''));
      return byTime || String(a.id).localeCompare(String(b.id));
    });
    var index = cohort.findIndex(function (candidate) { return candidate.id === plan.id; });
    var duplicate = cohort.length > 1 && index >= 0 ? 'Plan ' + (index + 1) + ' of ' + cohort.length : null;
    var stamp = plan.updatedAt || plan.createdAt;
    var updated = null;
    if (stamp) {
      var when = new Date(stamp);
      if (!isNaN(when.getTime())) updated = 'Updated ' + when.toLocaleString([], {
        month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'
      });
    }
    return { title: title, duplicate: duplicate, updated: updated,
      full: title + (duplicate ? ' · ' + duplicate : '') };
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
    var desktop = UI.el('div', { class: 'plan-bar-desktop' },
      UI.el('span', { class: 'plan-bar-label' }, 'Plans'));
    items.forEach(function (plan) {
      var planIdentity = identity(plan, items);
      var planLabel = planIdentity.title
        + (plan.context && plan.context.horizonDays ? ' · ' + plan.context.horizonDays + ' days' : '');
      if (planIdentity.duplicate) planLabel += ' · ' + planIdentity.duplicate;
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
      desktop.appendChild(chip);
    });
    desktop.appendChild(UI.el('button', { type: 'button', class: 'plan-new-btn',
      onclick: function () { App.navigate('#/research'); } }, '+ New plan'));
    var picker = UI.el('select', { class: 'plan-picker', id: 'plan-picker', 'aria-label': 'Open Plan',
      onchange: function () {
        var plan = items.find(function (p) { return p.id === picker.value; });
        if (plan) focus(plan).catch(function (e) { UI.toast(e.message, 'error'); });
      } }, items.map(function (plan) {
        var label = identity(plan, items);
        return UI.el('option', { value: plan.id, selected: plan.id === App.state.activePlanId ? 'selected' : null },
          plan.symbol + ' · ' + label.full);
      }));
    var mobile = UI.el('div', { class: 'plan-bar-mobile' }, picker,
      UI.el('button', { type: 'button', class: 'plan-new-btn', 'aria-label': 'Start a new Plan',
        onclick: function () { App.navigate('#/research'); } }, '+ New'));
    inner.appendChild(desktop);
    inner.appendChild(mobile);
    host.appendChild(inner);
  }

  window.PlanStore = {
    init: function () { return load(true); }, load: load, refresh: refresh, library: library,
    get: get, create: create, promote: promote,
    provisional: provisional, currentDraft: currentDraft, active: active, focus: focus, path: path, setStage: setStage,
    updateContext: updateContext, claimIntent: claimIntent, closeChip: closeChip,
    latestStrategy: latestStrategy, runStrategy: runStrategy, fitStrategy: fitStrategy,
    selectCandidate: selectCandidate, saveCustom: saveCustom,
    latestScout: latestScout, runScout: runScout, spawnScoutedPlan: spawnScoutedPlan,
    latestOutcomes: latestOutcomes, runEnsemble: runEnsemble,
    runOutcome: runOutcome, runBacktest: runBacktest,
    rehearsals: rehearsals, createRehearsal: createRehearsal,
    latestDecision: latestDecision, previewDecision: previewDecision,
    tradeDecision: tradeDecision, cashDecision: cashDecision,
    latestManagement: latestManagement, manage: manage, reviewCash: reviewCash,
    marketChanged: marketChanged, renderBar: renderBar, ui: ui, matching: matching, identity: identity,
    archive: archive,
    all: function () { return items.slice(); }, allMarkets: function () { return libraryItems.slice(); },
    currentMarketKey: currentMarketKey, marketKey: planMarketKey,
    libraryLoaded: function () { return libraryLoaded; }
  };
})();
