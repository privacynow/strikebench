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
  // A list/library reconciliation is a read snapshot. If a Plan response is applied after
  // that read begins (create, progress, decision, focused GET, etc.), the older snapshot must
  // not erase the newer Plan or steal its focus when it eventually resolves.
  var appliedPlanRevision = 0;

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
    appliedPlanRevision++;
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
    if (loadingByMarket[requestedKey]) {
      if (!force) return loadingByMarket[requestedKey];
      // A forced reconciliation must be newer than the lifecycle mutation that requested
      // it. Reusing an older in-flight read can briefly resurrect an archived Plan on Home.
      try { await loadingByMarket[requestedKey]; } catch (e) { /* the forced read retries */ }
      if (loadingByMarket[requestedKey]) return loadingByMarket[requestedKey];
    }
    var readRevision = appliedPlanRevision;
    loadingByMarket[requestedKey] = (force ? API.getFresh('/api/plans') : API.get('/api/plans')).then(function (r) {
      var key = r && r.world ? String(r.world) : requestedKey;
      if (appliedPlanRevision !== readRevision) {
        return (collections[key] || []).slice();
      }
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
    if (libraryLoading) {
      if (!force) return libraryLoading;
      // Force means "after the in-flight read", never "reuse whichever request began first".
      try { await libraryLoading; } catch (e) { /* the forced read retries */ }
      if (libraryLoading) return libraryLoading;
    }
    var path = '/api/plans?scope=all&openOnly=false';
    var readRevision = appliedPlanRevision;
    libraryLoading = (force ? API.getFresh(path) : API.get(path)).then(function (r) {
      if (appliedPlanRevision !== readRevision) return libraryItems.slice();
      libraryItems = (r && r.plans) || [];
      libraryLoaded = true;
      var grouped = {};
      libraryItems.forEach(function (plan) {
        if (plan.open === false || plan.status === 'ARCHIVED') return;
        var key = planMarketKey(plan);
        grouped[key] = grouped[key] || [];
        grouped[key].push(plan);
      });
      // The all-market response is authoritative. Replacing the map also removes Plans
      // archived or deleted while this tab was disconnected or the server restarted.
      collections = grouped;
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
    // Store reconciliation is data, not navigation. Mounted owners (the Home attention rail,
    // an open Plan library drawer, etc.) update their own nodes from this typed local event.
    // App.render() here used to tear down the entire desk on every plan.updated SSE hint.
    if (window.App && App.emitEvent) App.emitEvent('plans.refreshed', { reason: 'store-refresh' });
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

  function active() {
    return findKnown(App.state.activePlanId) || null;
  }

  function ui(planId) {
    App.state.planUi = App.state.planUi || {};
    return App.state.planUi[planId] = App.state.planUi[planId] || {};
  }

  function path(plan, stage) {
    var key = String(stage || plan.furthestStage || 'UNDERSTAND').toUpperCase().replace('-', '_');
    return '#/plan/' + plan.id + '/' + (STAGE_PATH[key] || 'understand');
  }

  function confirmLeavingSimulation(plan) {
    return new Promise(function (resolve) {
      var settled = false;
      function finish(value) { if (!settled) { settled = true; resolve(value); } }
      var target = plan.marketKind === 'DEMO' ? 'the Demo market'
        : plan.marketKind === 'OBSERVED' ? 'the Observed market' : 'another simulated market';
      UI.confirmModal('Switch markets to open this Plan?', UI.el('div', {},
        UI.el('p', {}, UI.el('b', {}, plan.title), ' belongs to ', target, '.'),
        UI.el('p', { class: 'muted' }, 'Your current simulated session keeps running in the background. The entire workspace, prices, account, and Plan bar will switch together.')),
      'Switch & open Plan', function () { finish(true); }, false, function () { finish(false); });
    });
  }

  async function focus(plan, stage) {
    if (typeof plan === 'string') plan = await get(plan);
    var market = currentMarket();
    var target = plan.marketKind === 'SIMULATED' ? plan.worldId
      : plan.marketKind === 'DEMO' ? 'demo' : 'observed';
    if (market.world !== target) {
      var leavingSimulation = market.world !== 'observed' && market.world !== 'demo';
      if (leavingSimulation && !(await confirmLeavingSimulation(plan))) return null;
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
    App.navigate(path(plan, stage));
    if (window.Workspace) Workspace.save();
    return plan;
  }

  async function advance(plan, stage) {
    var updated = replace(await API.post('/api/plans/' + plan.id + '/progress', { stage: stage }));
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

  async function clearCandidate(plan) {
    var out = await API.del('/api/plans/' + encodeURIComponent(plan.id)
      + '/strategy/selection?expectedVersion=' + encodeURIComponent(plan.version));
    if (out.plan) replace(out.plan);
    return out;
  }

  async function saveCustom(plan, position) {
    if (!position || !position.strategy || !Number.isInteger(Number(position.qty)) || Number(position.qty) < 1
        || !position.source || !['PROPOSED', 'EXECUTED'].includes(position.fillNature)
        || !Array.isArray(position.legs) || !position.legs.length
        || position.legs.some(function (leg) {
          return !leg || !leg.action || !leg.type || !leg.positionEffect
            || !Number.isInteger(Number(leg.ratio)) || Number(leg.ratio) < 1
            || !Number.isInteger(Number(leg.multiplier)) || Number(leg.multiplier) < 1;
        })) {
      throw new Error('Plan structures must use the current exact-position contract.');
    }
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

  function latestEnsemble(planId, force) {
    var path = '/api/plans/' + planId + '/outcomes/ensemble/latest';
    return force ? API.getFresh(path) : API.get(path);
  }

  /** Pure projection of a stored fan for scenario playback. It never persists assumptions or
   *  creates paths; the response carries the authoritative valuation checkpoints to interpolate. */
  function scenarioAnimation(plan, request) {
    return API.post('/api/plans/' + plan.id + '/outcomes/ensemble/paths', request || {});
  }

  async function adoptResearchEnsemble(plan, receipt) {
    receipt = receipt || {};
    var out = await API.post('/api/plans/' + plan.id + '/outcomes/ensemble', {
      expectedVersion: plan.version,
      researchReceiptId: receipt.id || receipt.researchReceiptId,
      expectedFingerprint: receipt.fingerprint || receipt.expectedFingerprint
    });
    if (out.plan) replace(out.plan);
    return out;
  }

  async function runOutcome(plan, request) {
    var out = await API.post('/api/plans/' + plan.id + '/outcomes/run',
      Object.assign({ expectedVersion: plan.version }, request || {}));
    if (out.plan) replace(out.plan);
    return out;
  }

  async function compareOutcomes(plan, request) {
    var out = await API.post('/api/plans/' + plan.id + '/outcomes/compare',
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

  /** The third decision outcome: the structure was placed at the user's real broker and the
   *  fills are recorded into the chosen tracked account atomically with the frozen decision. */
  async function brokerDecision(plan, request) {
    var out = await API.post('/api/plans/' + plan.id + '/decision/broker',
      Object.assign({ expectedVersion: plan.version }, request || {}));
    if (out.plan) replace(out.plan);
    return out;
  }

  /** Adopts an as-is tracked position into a mid-journey Plan (ADOPTION receipt over the
   *  existing lots). The Plan arrives with a live position and an undeclared view. */
  async function adoptPosition(request) {
    var out = await API.post('/api/plans/adopt', request || {});
    if (out.plan) { replace(out.plan); rememberActive(out.plan); }
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
    var routeMatch = (window.location.hash || '').match(/^#\/plan\/([^/?]+)/);
    var viewingThisPlan = !!(routeMatch && routeMatch[1] === plan.id);
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
    if (wasActive && viewingThisPlan) {
      if (items.length) await focus(items[0]);
      else App.navigate('#/home');
    } else if (window.App && App.emitEvent) {
      App.emitEvent('plans.refreshed', { reason: 'tab-closed', planId: plan.id });
    }
    return updated;
  }

  function removeLocal(planId) {
    Object.keys(collections).forEach(function (key) {
      collections[key] = (collections[key] || []).filter(function (p) { return p.id !== planId; });
    });
    libraryItems = libraryItems.filter(function (p) { return p.id !== planId; });
    Object.keys(App.state.activePlanByMarket || {}).forEach(function (key) {
      if (App.state.activePlanByMarket[key] === planId) {
        var next = (collections[key] || [])[0];
        App.state.activePlanByMarket[key] = next ? next.id : null;
      }
    });
    syncCurrent(currentMarketKey());
  }

  async function deleteDraft(plan) {
    await API.del('/api/plans/' + encodeURIComponent(plan.id) + '?expectedVersion=' + encodeURIComponent(plan.version));
    removeLocal(plan.id);
    if (window.Workspace) Workspace.save();
    return plan.id;
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
    var duplicate = null;
    if (cohort.length > 1 && index >= 0) {
      var values = function (field) {
        return new Set(cohort.map(function (candidate) {
          var value = candidate.context && candidate.context[field];
          return value == null || value === '' ? '__unset__' : String(value);
        }));
      };
      var context = plan.context || {};
      if (values('thesis').size > 1) duplicate = context.thesis ? 'View ' + context.thesis : 'View not set';
      else if (values('targetCents').size > 1) duplicate = context.targetCents == null ? 'Target not set'
        : 'Target $' + (Number(context.targetCents) / 100).toLocaleString(undefined,
          { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      else if (values('holdingsShares').size > 1) duplicate = context.holdingsShares == null
        ? 'Shares not set' : String(context.holdingsShares) + ' shares';
      else if (values('costBasisCents').size > 1) duplicate = context.costBasisCents == null
        ? 'Basis not set' : 'Basis $' + (Number(context.costBasisCents) / 100).toLocaleString(undefined,
          { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      else if (values('priceAssumptionCents').size > 1) duplicate = context.priceAssumptionCents == null
        ? 'Price not set' : 'Price $' + (Number(context.priceAssumptionCents) / 100).toLocaleString(undefined,
          { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      else if (values('riskMode').size > 1) duplicate = context.riskMode
        ? 'Risk ' + String(context.riskMode).toLowerCase() : 'Risk not set';
      else {
        var stages = new Set(cohort.map(function (candidate) { return String(candidate.furthestStage || 'UNDERSTAND'); }));
        if (stages.size > 1) duplicate = 'At ' + String(plan.furthestStage || 'UNDERSTAND')
          .replaceAll('_', ' ').toLowerCase();
        else {
          var started = new Date(plan.createdAt || '');
          duplicate = isNaN(started.getTime()) ? 'Earlier saved work' : 'Started ' + started.toLocaleString([], {
            month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit'
          });
        }
      }
    }
    var stamp = plan.updatedAt || plan.createdAt;
    var updated = null;
    if (stamp) {
      var when = new Date(stamp);
      if (!isNaN(when.getTime())) updated = 'Updated ' + when.toLocaleString([], {
        month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit'
      });
    }
    return { title: title, duplicate: duplicate, updated: updated,
      full: (duplicate ? duplicate + ' · ' : '') + title };
  }


  window.PlanStore = {
    init: function () { return load(true); }, load: load, refresh: refresh, library: library,
    get: get, create: create,
    active: active, focus: focus, path: path, advance: advance,
    updateContext: updateContext, claimIntent: claimIntent, closeChip: closeChip,
    latestStrategy: latestStrategy, runStrategy: runStrategy, fitStrategy: fitStrategy,
    selectCandidate: selectCandidate, clearCandidate: clearCandidate, saveCustom: saveCustom,
    latestScout: latestScout, runScout: runScout, spawnScoutedPlan: spawnScoutedPlan,
    latestOutcomes: latestOutcomes, runEnsemble: runEnsemble,
    latestEnsemble: latestEnsemble, scenarioAnimation: scenarioAnimation,
    adoptResearchEnsemble: adoptResearchEnsemble,
    runOutcome: runOutcome, compareOutcomes: compareOutcomes, runBacktest: runBacktest,
    rehearsals: rehearsals, createRehearsal: createRehearsal,
    latestDecision: latestDecision, previewDecision: previewDecision,
    tradeDecision: tradeDecision, cashDecision: cashDecision, brokerDecision: brokerDecision,
    adoptPosition: adoptPosition,
    latestManagement: latestManagement, manage: manage, reviewCash: reviewCash,
    marketChanged: marketChanged, ui: ui, matching: matching, identity: identity,
    archive: archive, deleteDraft: deleteDraft,
    all: function () { return items.slice(); }, allMarkets: function () { return libraryItems.slice(); },
    currentMarketKey: currentMarketKey, marketKey: planMarketKey,
    libraryLoaded: function () { return libraryLoaded; }
  };
})();
