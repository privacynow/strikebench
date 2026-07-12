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

  // Namespaced PER SIGNED-IN SUBJECT: two profiles sharing a browser must never hydrate each
  // other's symbols, forms, or working ticket from a shared local copy. Anonymous = '.local'.
  var LS_KEY = 'strikebench.workspace.local';
  function setUserKey(userKey) {
    LS_KEY = 'strikebench.workspace.' + (userKey && String(userKey).trim() ? String(userKey).trim() : 'local');
  }
  // Draft forms carry the user's thinking; results are refetched (fresh data beats stale payloads).
  var FORM_KEYS = ['discoverForm', 'builderForm', 'backtestForm', 'verifyForm', 'scenarioForm', 'ideasForm', 'dataSyncForm'];

  var rev = 0;             // last backend revision seen (multi-tab adopt guard)
  var started = false;
  var lastSavedJson = '';  // dirty check — the 4s tick only writes when something changed

  function snapshot() {
    var s = {
      v: 1,
      route: window.location.hash || '#/home',
      world: (window.App && App.state.world) || null,
      symbol: (window.App && App.context.symbol()) || null,
      forms: {},
      ticket: (window.App && App.state.ticket) || null,
      // The THESIS WORKFLOW is part of the workspace (holistic review #11): the per-symbol
      // market thesis, the keyed research study selection, and an active evidence handoff all
      // survive a reload — losing them mid-investigation reset the user's thinking.
      marketThesis: (window.App && App.state.marketThesis) || null,
      // F6: SELECTION ONLY — the results cache holds full study payloads (analog paths and all);
      // persisting it restored stale evidence and could blow the 128KB workspace cap. Results
      // are keyed server-side and refetched fresh.
      researchStudy: (window.App && App.state.researchStudy)
        ? { mode: App.state.researchStudy.mode || 'past',
            params: App.state.researchStudy.params || null }
        : null,
      evidencePrefill: (window.App && App.state.evidencePrefill) || null,
      savedAt: Date.now()
    };
    FORM_KEYS.forEach(function (k) { if (App.state[k]) s.forms[k] = App.state[k]; });
    return s;
  }

  function apply(s) {
    if (!s || s.v !== 1 || !window.App) return false;
    if (s.world) App.state.world = s.world;
    if (s.symbol) App.context.selectSymbol(s.symbol);
    if (s.marketThesis) App.state.marketThesis = s.marketThesis;
    // Selection only (F6): results/questions rebuild fresh — never restore stale evidence.
    if (s.researchStudy) {
      App.state.researchStudy = { mode: s.researchStudy.mode || 'past',
        params: s.researchStudy.params || null };
    }
    if (s.evidencePrefill) App.state.evidencePrefill = s.evidencePrefill;
    Object.keys(s.forms || {}).forEach(function (k) {
      if (FORM_KEYS.indexOf(k) >= 0 && s.forms[k]) App.state[k] = s.forms[k];
    });
    if (s.ticket) {
      // HONESTY GUARD: a restored ticket re-enters at the strikes step, never at a
      // review/confirm armed with the OLD session's prices — marks have moved since. The
      // normal flow re-previews at current marks before anything can be placed.
      var t = s.ticket;
      if (t.step && t.step > 5) t.step = 5;
      delete t.preview;
      App.state.ticket = t;
    }
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
    var json = JSON.stringify({ route: s.route, world: s.world, symbol: s.symbol, forms: s.forms, ticket: s.ticket });
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
      if (r && r.state && r.state.v === 1) {
        apply(r.state);
        // Rebase the dirty-check on the ADOPTED state: re-pushing what we just pulled would
        // bump the rev and ping-pong forever between two hidden tabs. Only a genuine local
        // change after this may write again.
        var s = snapshot();
        lastSavedJson = JSON.stringify({ route: s.route, world: s.world, symbol: s.symbol, forms: s.forms, ticket: s.ticket });
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
