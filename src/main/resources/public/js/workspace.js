/* StrikeBench workspace continuity: the desk remembers Plan focus and presentation.
 *
 * Durable Plan facts stay server-owned. This module persists a small presentation subset
 * and per-market provisional Plan drafts — never results or priced artifacts — to localStorage
 * (instant, survives reload) and to the backend (debounced PUT /api/workspace, survives
 * devices/restarts when signed in). Boot hydrates whichever copy is newest; the /api/events
 * stream announces revisions so a second tab can adopt them while hidden.
 */
(function () {
  'use strict';

  // Namespaced PER SIGNED-IN SUBJECT: two profiles sharing a browser must never hydrate each
  // other's symbols, forms, or working ticket from a shared local copy. Anonymous = '.local'.
  var LS_KEY = 'strikebench.workspace.local';
  function setUserKey(userKey) {
    LS_KEY = 'strikebench.workspace.' + (userKey && String(userKey).trim() ? String(userKey).trim() : 'local');
  }
  var FORM_KEYS = ['dataScenarioForm', 'dataSyncForm'];

  function canonicalRoute(hash) {
    hash = hash || '#/home';
    return /^#\/(home(?:\/tour)?|research|plan\/(?:new\?symbol=[A-Z0-9._-]+|[^/?]+\/(?:understand|evidence|strategy|outcomes|decide|manage-review))|portfolio(?:\/(?:construct|positions|active|closed|activity|record|account|trade\/tr_[A-Za-z0-9_-]+))?|data\/(?:overview|datasets|simulation|sources|admin))(?:\?.*)?$/i.test(hash)
      ? hash : '#/home';
  }

  var rev = 0;             // last backend revision seen (multi-tab adopt guard)
  var started = false;
  var lastSavedJson = '';  // dirty check — the 4s tick only writes when something changed

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
            if (e && e.mode) out[id] = { evidenceMode: e.mode,
              contextRev: App.state.planUi[id].contextRev || null };
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
      ui.evidence.mode = s.planPresentation[id].evidenceMode || 'past';
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
  function pushRemote(s) {
    clearTimeout(pushTimer);
    pushTimer = setTimeout(function () {
      API.put('/api/workspace', s).then(function (r) {
        if (r && r.rev) rev = r.rev;
      }).catch(function () { /* offline/backend down — localStorage still has it */ });
    }, 1500);
  }

  /** Saves if anything changed since the last save. Cheap: one small JSON stringify. */
  function saveIfDirty() {
    if (!started || !window.App || !window.API) return;
    var s = snapshot();
    var json = JSON.stringify({ route: s.route, world: s.world, activePlanId: s.activePlanId,
      activePlanByMarket: s.activePlanByMarket, provisionalPlansByMarket: s.provisionalPlansByMarket,
      planPresentation: s.planPresentation, forms: s.forms });
    if (json === lastSavedJson) return;
    lastSavedJson = json;
    persistLocal(s);
    pushRemote(s);
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
    // A tab that adopted another tab's state while hidden still SHOWS its old DOM —
    // re-render once when the user comes back to it.
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && adoptedWhileHidden) {
        adoptedWhileHidden = false;
        if (window.App && App.render) App.render();
      }
    });
    // Leaving the page: localStorage synchronously; the backend via keepalive fetch (a normal
    // request would be cancelled by the unload).
    window.addEventListener('pagehide', function () {
      var s = snapshot();
      persistLocal(s);
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
  function onRemoteRev(newRev) {
    if (!newRev || newRev <= rev) return;
    rev = newRev;
    if (!document.hidden) return; // visible tab keeps its own state; it will overwrite on next save
    API.getFresh('/api/workspace').then(function (r) {
      if (r && r.state && r.state.v === 2) {
        apply(r.state);
        // Rebase the dirty-check on the ADOPTED state: re-pushing what we just pulled would
        // bump the rev and ping-pong forever between two hidden tabs. Only a genuine local
        // change after this may write again.
        var s = snapshot();
        lastSavedJson = JSON.stringify({ route: s.route, world: s.world, activePlanId: s.activePlanId,
          activePlanByMarket: s.activePlanByMarket, provisionalPlansByMarket: s.provisionalPlansByMarket,
          planPresentation: s.planPresentation, forms: s.forms });
        persistLocal(s);
        adoptedWhileHidden = true; // the DOM still shows pre-adoption state — refresh on return
      }
    }).catch(function () { /* ignore */ });
  }

  window.Workspace = {
    hydrate: hydrate,
    start: start,
    save: saveIfDirty,
    snapshot: snapshot,
    onRemoteRev: onRemoteRev
  };
})();
