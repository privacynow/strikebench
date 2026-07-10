/* StrikeBench workspace continuity: the desk remembers what you were doing.
 *
 * CLIENT-AUTHORITATIVE: App.state owns the shape (draft forms, working idea, working symbol,
 * route). This module persists a durable subset — never result payloads — to localStorage
 * (instant, survives reload) and to the backend (debounced PUT /api/workspace, survives
 * devices/restarts when signed in). Boot hydrates whichever copy is newest; the /api/events
 * stream announces revisions so a second tab can adopt them while hidden.
 */
(function () {
  'use strict';

  var LS_KEY = 'strikebench.workspace';
  // Draft forms carry the user's thinking; results are refetched (fresh data beats stale payloads).
  var FORM_KEYS = ['discoverForm', 'builderForm', 'backtestForm', 'verifyForm', 'scenarioForm', 'ideasForm'];

  var rev = 0;             // last backend revision seen (multi-tab adopt guard)
  var started = false;
  var lastSavedJson = '';  // dirty check — the 4s tick only writes when something changed

  function snapshot() {
    var s = {
      v: 1,
      route: window.location.hash || '#/home',
      symbol: (window.App && App.state.lastRecommendSymbol) || null,
      forms: {},
      ticket: (window.App && App.state.ticket) || null,
      savedAt: Date.now()
    };
    FORM_KEYS.forEach(function (k) { if (App.state[k]) s.forms[k] = App.state[k]; });
    return s;
  }

  function apply(s) {
    if (!s || s.v !== 1 || !window.App) return false;
    if (s.symbol) App.state.lastRecommendSymbol = s.symbol;
    Object.keys(s.forms || {}).forEach(function (k) {
      if (FORM_KEYS.indexOf(k) >= 0 && s.forms[k]) App.state[k] = s.forms[k];
    });
    if (s.ticket) App.state.ticket = s.ticket;
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
    var json = JSON.stringify({ route: s.route, symbol: s.symbol, forms: s.forms, ticket: s.ticket });
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
  function hydrate(remote) {
    var local = readLocal();
    var localState = local && local.state && local.state.v === 1 ? local.state : null;
    var remoteState = remote && remote.state && remote.state.v === 1 ? remote.state : null;
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
    // Leaving the page: localStorage synchronously; the backend via keepalive fetch (a normal
    // request would be cancelled by the unload).
    window.addEventListener('pagehide', function () {
      var s = snapshot();
      persistLocal(s);
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
  function onRemoteRev(newRev) {
    if (!newRev || newRev <= rev) return;
    rev = newRev;
    if (!document.hidden) return; // visible tab keeps its own state; it will overwrite on next save
    API.getFresh('/api/workspace').then(function (r) {
      if (r && r.state && r.state.v === 1) {
        apply(r.state);
        lastSavedJson = ''; // adopted — next tick re-saves the merged view
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
