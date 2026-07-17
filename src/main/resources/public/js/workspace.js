/* StrikeBench workspace continuity: the desk remembers Plan focus and presentation.
 *
 * Durable Plan facts stay server-owned. This module persists a small presentation subset
 * and per-market provisional Plan drafts — never results or priced artifacts — to localStorage
 * (instant, survives reload) and to the backend (debounced PUT /api/workspace, survives
 * devices/restarts when signed in). Boot hydrates whichever copy is newest; a visible tab
 * reconciles the remote revision after returning from the background.
 */
(function () {
  'use strict';

  // Namespaced PER SIGNED-IN SUBJECT: two profiles sharing a browser must never hydrate each
  // other's symbols, forms, or working ticket from a shared local copy. Anonymous = '.local'.
  var LS_KEY = 'strikebench.workspace.local';
  function setUserKey(userKey) {
    LS_KEY = 'strikebench.workspace.' + (userKey && String(userKey).trim() ? String(userKey).trim() : 'local');
  }
  var FORM_KEYS = ['dataScenarioForm', 'dataSyncForm', 'positionDrafts'];

  function canonicalRoute(hash) {
    return Product.Routes.canonical(hash);
  }

  var rev = 0;             // last backend revision seen (multi-tab adopt guard)
  var started = false;
  var lastSavedJson = '';  // dirty check — the 4s tick only writes when something changed
  var lastRemoteJson = ''; // last state the backend actually acknowledged

  function snapshot() {
    var s = {
      v: 2,
      route: canonicalRoute(window.location.hash),
      world: (window.App && App.state.world) || null,
      activePlanId: (window.App && App.state.activePlanId) || null,
      activePlanByMarket: (window.App && App.state.activePlanByMarket)
        ? Object.assign({}, App.state.activePlanByMarket) : null,
      planPresentation: window.App && App.state.planUi
        ? Object.keys(App.state.planUi).reduce(function (out, id) {
            var e = App.state.planUi[id] && App.state.planUi[id].evidence;
            var planUi = App.state.planUi[id] || {};
            var thesis = planUi.thesis || {};
            if (e && e.mode || thesis.setup || planUi.strategyView) out[id] = { evidenceMode: e && e.mode || null,
              evidenceSetup: thesis.setup || null,
              strategyView: planUi.strategyView || null,
              contextRev: planUi.contextRev || null };
            return out;
          }, {}) : null,
      provisionalPlansByMarket: (window.App && App.state.provisionalPlansByMarket)
        ? Object.assign({}, App.state.provisionalPlansByMarket) : null,
      forms: {},
      savedAt: Date.now()
    };
    FORM_KEYS.forEach(function (k) { if (App.state[k]) s.forms[k] = App.state[k]; });
    return s;
  }

  function apply(s) {
    if (!s || s.v !== 2 || !window.App) return false;
    // The workspace remembers work, never the active market. Market lane is server-owned and
    // may change only through App.transitionWorld so account, universe, stream and banners move
    // atomically. Applying this field directly in a hidden tab split those owners apart.
    if (s.provisionalPlansByMarket) {
      App.state.provisionalPlansByMarket = Object.assign({}, s.provisionalPlansByMarket);
    }
    if (s.activePlanId) App.state.activePlanId = s.activePlanId;
    if (s.activePlanByMarket) App.state.activePlanByMarket = Object.assign({}, s.activePlanByMarket);
    Object.keys(s.planPresentation || {}).forEach(function (id) {
      App.state.planUi = App.state.planUi || {};
      var ui = App.state.planUi[id] = App.state.planUi[id] || {};
      ui.contextRev = s.planPresentation[id].contextRev || null;
      ui.evidence = ui.evidence || {};
      ui.thesis = ui.thesis || {};
      if (s.planPresentation[id].evidenceMode) ui.evidence.mode = s.planPresentation[id].evidenceMode;
      if (s.planPresentation[id].evidenceSetup) ui.thesis.setup = s.planPresentation[id].evidenceSetup;
      if (s.planPresentation[id].strategyView) ui.strategyView = s.planPresentation[id].strategyView;
    });
    Object.keys(s.forms || {}).forEach(function (k) {
      if (FORM_KEYS.indexOf(k) >= 0 && s.forms[k]) App.state[k] = s.forms[k];
    });
    return true;
  }

  function persistLocal(s) {
    try { localStorage.setItem(LS_KEY, JSON.stringify({ rev: rev, state: s })); } catch (e) { /* private mode */ }
  }

  function readLocal() {
    try { return JSON.parse(localStorage.getItem(LS_KEY) || 'null'); } catch (e) { return null; }
  }

  var pushTimer = null;
  function stateJson(s) {
    return JSON.stringify({ route: s.route, world: s.world, activePlanId: s.activePlanId,
      activePlanByMarket: s.activePlanByMarket, provisionalPlansByMarket: s.provisionalPlansByMarket,
      planPresentation: s.planPresentation, forms: s.forms });
  }

  function pushRemote(s, json) {
    clearTimeout(pushTimer);
    pushTimer = setTimeout(function () {
      API.put('/api/workspace', s).then(function (r) {
        if (r && r.rev) rev = r.rev;
        lastRemoteJson = json;
      }).catch(function () { /* offline/backend down — localStorage still has it */ });
    }, 1500);
  }

  /** Saves if anything changed since the last save. Cheap: one small JSON stringify. */
  function saveIfDirty() {
    if (!started || !window.App || !window.API) return;
    // A parked tab must never overwrite work completed in the visible tab. It reconciles
    // before streams resume when the user returns.
    if (document.hidden) return;
    var s = snapshot();
    var json = stateJson(s);
    if (json === lastSavedJson) return;
    lastSavedJson = json;
    persistLocal(s);
    pushRemote(s, json);
  }

  /**
   * Boot hydration. `remote` is the (already fetched) GET /api/workspace payload or null.
   * Newest copy wins: both blobs carry a client-clock savedAt, so the comparison is honest
   * even though local saves don't bump the backend rev.
   */
  function hydrate(remote, userKey) {
    if (userKey !== undefined) setUserKey(userKey);
    var local = readLocal();
    var localState = local && local.state && local.state.v === 2 ? local.state : null;
    var remoteState = remote && remote.state && remote.state.v === 2 ? remote.state : null;
    if (remote && remote.rev) rev = remote.rev;
    lastRemoteJson = remoteState ? stateJson(remoteState) : '';
    var chosen = null;
    if (localState && remoteState) chosen = (localState.savedAt || 0) >= (remoteState.savedAt || 0) ? localState : remoteState;
    else chosen = localState || remoteState;
    if (chosen && apply(chosen)) return chosen;
    return null;
  }

  /** Wires the save triggers: navigation, a dirty-checked tick, and leaving the page. */
  function start() {
    if (started) return;
    started = true;
    lastSavedJson = ''; // first save after boot always writes
    window.addEventListener('hashchange', saveIfDirty);
    setInterval(saveIfDirty, 4000);
    // The app owns the one visibility reconciliation sequence (world, workspace, mounted
    // destination, then streams). `reconcile()` below reports a hidden adoption so that
    // sequence can refresh the live destination once without a competing root repaint.
    // Leaving the page: localStorage synchronously; the backend via keepalive fetch (a normal
    // request would be cancelled by the unload).
    window.addEventListener('pagehide', function () {
      var s = snapshot();
      persistLocal(s);
      var json = stateJson(s);
      // lastSavedJson includes a debounced local save that may not have reached the server yet.
      // Compare against the last acknowledged remote state so pagehide flushes pending work.
      if (json === lastRemoteJson) return;
      lastSavedJson = json;
      clearTimeout(pushTimer);
      if (window.App && App.state.serverStale) return;
      try {
        fetch('/api/workspace', {
          method: 'PUT', keepalive: true,
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(s)
        });
      } catch (e) { /* best effort */ }
    });
  }

  /** Another tab (or device) wrote rev N. Adopt only while hidden — never yank live work. */
  var adoptedWhileHidden = false;
  function adoptRemote(remote, hidden) {
    if (!remote || !remote.rev || remote.rev <= rev
        || !remote.state || remote.state.v !== 2) return false;
    rev = remote.rev;
    apply(remote.state);
    var s = snapshot();
    lastSavedJson = stateJson(s);
    lastRemoteJson = lastSavedJson;
    persistLocal(s);
    adoptedWhileHidden = !!hidden;
    return true;
  }

  function onRemoteRev(newRev) {
    if (!newRev || newRev <= rev) return;
    if (!document.hidden) return; // visible tab keeps its own state; it will overwrite on next save
    API.getFresh('/api/workspace').then(function (r) {
      // Visibility may change while this request is in flight. Once visible, the app's
      // ordered world/workspace reconciliation owns adoption; a late hidden-tab callback
      // must not mutate state after that sequence already decided what to paint.
      if (document.hidden) adoptRemote(r, true);
    }).catch(function () { /* ignore */ });
  }

  function reconcile() {
    if (!started || !window.API) return Promise.resolve(false);
    var adoptedBeforeVisibility = adoptedWhileHidden;
    adoptedWhileHidden = false;
    return API.getFresh('/api/workspace').then(function (remote) {
      return adoptRemote(remote, false) || adoptedBeforeVisibility;
    }).catch(function () { return adoptedBeforeVisibility; });
  }

  window.Workspace = {
    hydrate: hydrate,
    start: start,
    save: saveIfDirty,
    snapshot: snapshot,
    onRemoteRev: onRemoteRev,
    reconcile: reconcile
  };
})();
