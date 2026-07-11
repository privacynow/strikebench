/* StrikeBench app shell: hash router, header state, boot. Attached to window (jsdom-friendly). */
(function () {
  'use strict';

  var App = {
    state: { ticket: null, lastRecommendSymbol: null },
    navToken: 0,

    /**
     * True while `token` is still the current route generation. Slow views paint their shell,
     * return immediately (so a new navigation is never blocked behind a slow fetch), and fill
     * their captured child elements from a detached async block guarded by this — a stale fill
     * from a route the user already left simply bails instead of painting over the new screen.
     */
    alive: function (token) { return token === App.navToken; },

    navigate: function (hash) {
      if (window.location.hash === hash) {
        App.render();
      } else {
        window.location.hash = hash;
      }
    },

    /** Renders the current route into #app. Sets data-ready="true" when done (used by tests). */
    render: async function () {
      // Serialize: a render kicked off mid-render (filter change -> blur -> render, then a tab
      // click -> hashchange -> render) must wait, or both append into the same cleared root.
      App._renderQueued = true;
      if (App._rendering) return;
      App._rendering = true;
      try {
        while (App._renderQueued) {
          App._renderQueued = false;
          await App._renderOnce();
        }
      } finally {
        App._rendering = false;
      }
    },

    _renderOnce: async function () {
      var root = document.getElementById('app');
      // Every render is a new route generation. A slow view returns after painting its shell
      // and fills the rest from a detached block guarded by App.alive(token); a later
      // navigation bumps the token so that stale fill bails instead of painting the wrong screen.
      var token = ++App.navToken;
      // The readiness flag drops SYNCHRONOUSLY on render start — the crossfade below is
      // async, and anything watching data-ready (tests, tooling) must never catch the
      // outgoing screen still claiming to be ready for the new route.
      root.setAttribute('data-ready', 'false');
      // Keep the header's dollar budget honest: every mutation flushes the GET cache and every
      // route change lands here, so trades/resets/world switches re-price the label (review P1).
      if (App.refreshRiskBudget) App.refreshRiskBudget();

      var hash = window.location.hash || '#/home';
      var parts = hash.replace(/^#\//, '').split('/').filter(function (p) { return p.length; });
      var route = parts[0] || 'home';
      var params = parts.slice(1);
      // IA merges keep old URLs alive: the tour lives inside Home, Account inside Portfolio,
      // and Ideas/Ticket/Backtest are stages of the Trade workbench
      if (route === 'welcome') { route = 'home'; params = ['tour']; }
      if (route === 'account') { route = 'portfolio'; params = ['account']; }
      // Lab dissolved: its tools moved to their natural homes (study→Research, optimizer→Decision,
      // replicator→Builder template). #/lab* keeps working by redirecting to Research (the study home).
      if (route === 'lab') { route = 'research'; params = []; }
      if (route === 'data') { route = 'status'; } // the nav says Data; params carry the Data tab
      if (route === 'recommend') { route = 'trade'; params = ['discover'].concat(params); }
      if (route === 'backtest') { route = 'trade'; params = ['verify']; }
      if (route === 'ticket') {
        route = 'trade';
        params = params[0] === 'builder' ? ['shape'] : ['place'];
      }
      var view = window.Views[route];
      if (!view) {
        route = 'home';
        params = [];
        view = window.Views.home;
      }

      document.querySelectorAll('#nav a, #bottom-nav a').forEach(function (a) {
        a.classList.toggle('active', a.getAttribute('data-route') === route);
      });

      // Swap = clear + paint a skeleton so the screen is never blank while data loads.
      // Wrapped in a View Transition when the browser has one (Chromium): the old screen
      // crossfades out instead of vanishing. #app carries its own view-transition-name so
      // the header/tape stay out of the animation (a frozen ticker snapshot reads as a jump).
      var skeleton = UI.skeleton();
      var swap = function () {
        root.setAttribute('data-ready', 'false');
        root.innerHTML = '';
        root.appendChild(skeleton);
      };
      var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      if (document.startViewTransition && !reduced) {
        try { await document.startViewTransition(swap).updateCallbackDone; } catch (e) { /* swap already ran */ }
      } else {
        swap();
      }
      // The skeleton leaves the moment the view appends its first real element — views
      // render progressively, so first content usually lands within a frame or two.
      var mo = new MutationObserver(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          for (var j = 0; j < muts[i].addedNodes.length; j++) {
            if (muts[i].addedNodes[j] !== skeleton) {
              if (skeleton.parentNode === root) root.removeChild(skeleton);
              mo.disconnect();
              return;
            }
          }
        }
      });
      mo.observe(root, { childList: true });

      try {
        await view(root, params);
      } catch (e) {
        root.innerHTML = '';
        root.appendChild(renderRouteError(route, e));
        checkServerHealth(); // a failing screen is the moment to look for a stale server
      } finally {
        mo.disconnect();
        if (skeleton.parentNode === root) root.removeChild(skeleton);
      }
      root.setAttribute('data-ready', 'true');
      root.setAttribute('data-route', route);
      prefetchForRoute(route, params); // idle-time warm-up of the likely next step (server-governed)
      if (window.Workspace) Workspace.save(); // navigation is a save point (dirty-checked, debounced push)
      // The ticker is CONTEXT, not chrome: it accompanies market-facing screens only
      var tape = document.getElementById('tape');
      if (tape) {
        var showTape = route === 'home' || route === 'research'
          || (route === 'status' && App.state.world && App.state.world !== 'observed');
        tape.classList.toggle('tape-offroute', !showTape);
        var strip = document.getElementById('tape-strip');
        if (showTape && strip && (!strip.children.length || strip.hasAttribute('data-stale'))) {
          refreshTape(); // deferred build: universe changed (or first build) while hidden
        }
      }
    }
  };

  /** Route-level error boundary: name the screen, show the reason, offer a retry. */
  function renderRouteError(route, e) {
    var box = UI.el('div', { class: 'card', id: 'route-error' },
      UI.alertBox('danger', 'The ' + route + ' screen failed to load', [e.message || 'Something went wrong']),
      UI.el('div', { class: 'btn-row' },
        UI.el('button', { class: 'btn', id: 'route-retry', onclick: function () { App.render(); } }, 'Retry'),
        UI.el('button', { class: 'btn btn-secondary', onclick: function () { App.navigate('#/status'); } }, 'Check data status')),
      UI.el('p', { class: 'muted' },
        'If this keeps happening after a retry, the server may need a restart (see the banner above if one appeared).'));
    return box;
  }

  /**
   * Stale-binary detection: if the jar was rebuilt under the running server, screens fail in
   * confusing ways. The server tells us via /api/health; we turn it into one clear banner.
   */
  async function checkServerHealth() {
    try {
      var h = await API.get('/api/health');
      var existing = document.getElementById('stale-banner');
      if (h && h.jarChangedSinceBoot) {
        if (!existing) {
          var banner = UI.el('div', { id: 'stale-banner' },
            UI.icon('warn', 15),
            ' The app was rebuilt after this server started \u2014 screens will misbehave until you restart it: stop the process, run ',
            UI.el('code', {}, 'java -jar target/strikebench.jar'),
            ' and reload this page.');
          document.body.insertBefore(banner, document.body.firstChild);
          document.body.classList.add('has-stale-banner');
          document.documentElement.style.setProperty('--stale-banner-h', banner.offsetHeight + 'px');
        }
      } else if (existing) {
        existing.remove();
        document.body.classList.remove('has-stale-banner');
      }
    } catch (e) { /* health is best-effort; the error boundary already told the user */ }
  }

  /**
   * SCENARIO MODE banner: when a synthetic dataset is the active analysis dataset, every screen
   * must say so loudly — simulated futures can never quietly masquerade as market data.
   */
  var scenarioBannerSeq = 0; // click handler AND the dataset.selected event both call this — only the latest applies
  async function refreshScenarioBanner() {
    var seq = ++scenarioBannerSeq;
    var cfg;
    try { cfg = await API.getFresh('/api/config'); App.config = cfg || App.config; } catch (e) { return; }
    if (seq !== scenarioBannerSeq) return; // a newer refresh superseded this one
    // Re-query AFTER the await (a concurrent refresh may have inserted one) and never
    // tolerate duplicates — two racing inserts once left an unremovable second banner.
    var existing = Array.prototype.slice.call(document.querySelectorAll('#scenario-banner'));
    if (cfg && cfg.scenarioMode) {
      var label = 'SCENARIO MODE — “' + (cfg.activeDatasetName || cfg.activeDataset || '')
        + '” replaces market data for ITS symbol; other symbols still show observed data. ';
      if (existing.length) {
        existing.forEach(function (b, i) { if (i === 0) b.childNodes[1].textContent = label; else b.remove(); });
        return;
      }
      // The banner LEADS somewhere (holistic review #8): the scenario's symbol drives one-tap
      // Research and strategy testing, and returning to observed data is right here — not a
      // dead-end state switch you can only undo by hunting through Data.
      var banner = UI.el('div', { id: 'scenario-banner' },
        UI.icon('warn', 15),
        document.createTextNode(label),
        UI.el('button', { class: 'btn btn-sm', id: 'scenario-research', onclick: function () {
          App.navigate('#/research'); } }, 'Research'),
        UI.el('button', { class: 'btn btn-sm', id: 'scenario-test', onclick: function () {
          App.navigate('#/trade/verify'); } }, 'Test strategies'),
        UI.el('a', { href: '#/data/datasets', onclick: function () { App.navigate('#/data/datasets'); } }, 'Manage'),
        UI.el('button', { class: 'btn btn-sm btn-secondary', id: 'scenario-exit', onclick: async function () {
          try {
            await API.put('/api/datasets/active', { id: 'observed' });
            refreshScenarioBanner();
            App.render();
          } catch (e) { alert(e.message); }
        } }, 'Back to observed data'));
      document.body.insertBefore(banner, document.getElementById('tape') || document.body.firstChild);
      // The scenario's own SYMBOL powers the Research/Test buttons (dataset rows carry it).
      (async function () {
        try {
          var ds = await API.get('/api/datasets');
          var act = (ds.datasets || []).filter(function (d) { return d.id === ds.active; })[0];
          var sym = act && (act.symbol || (act.symbols && act.symbols[0]));
          if (!sym) return;
          var rBtn = document.getElementById('scenario-research');
          var tBtn = document.getElementById('scenario-test');
          if (rBtn) { rBtn.textContent = 'Research ' + sym; rBtn.onclick = function () { App.navigate('#/research/' + sym); }; }
          if (tBtn) { tBtn.onclick = function () { App.state.lastRecommendSymbol = sym; App.navigate('#/trade/verify'); }; }
        } catch (e) { /* generic buttons stay */ }
      })();
    } else {
      existing.forEach(function (b) { b.remove(); });
    }
  }
  App.refreshScenarioBanner = refreshScenarioBanner;

  // ---- Block S8: the SIMULATED MARKET band + per-user world switch ----
  var worldBandSeq = 0;
  App.state.world = App.state.world || 'observed';
  App.state.worldGen = App.state.worldGen || 0; // stale-event token: bump on every switch

  // Human names for scenario enums — the band is permanent chrome, never raw SELLOFF_REBOUND.
  var SCENARIO_LABELS = {
    CHOP: 'Choppy drift', TREND_UP: 'Steady climb', GRIND_UP: 'Steady climb',
    TREND_DOWN: 'Steady decline', SELLOFF: 'Steady decline',
    SELLOFF_REBOUND: 'Sell-off, then rebound', RALLY_FADE: 'Rally, then fade',
    VOL_EVENT: 'Volatility event', NEWS_SHOCK: 'Volatility event'
  };
  App.scenarioLabel = function (key) {
    return SCENARIO_LABELS[String(key || '').toUpperCase()] ||
      String(key || '').toLowerCase().replace(/_/g, ' ');
  };
  // Speed = SIM-SECONDS PER REAL SECOND (1x = real time; a 6.5h session has 23,400 market
  // seconds). Presets are named by what a trader actually feels: how long one session takes.
  var SPEED_CHOICES = [1, 26, 78, 390, 1560];
  var SPEED_LABELS = {
    1: '1\u00d7 \u00b7 real time',
    26: '26\u00d7 \u00b7 session \u2248 15 min',
    78: '78\u00d7 \u00b7 session \u2248 5 min',
    390: '390\u00d7 \u00b7 session \u2248 1 min',
    1560: '1560\u00d7 \u00b7 session \u2248 15 s'
  };

  function bandLabelText(sess, cfg) {
    return App.scenarioLabel(cfg.scenario)
      + (sess && sess.simTime ? ' \u00b7 ' + sess.simTime.replace('T', ' ') + ' ET' : '');
  }

  /**
   * The SIMULATED MARKET band. Updates IN PLACE (label text, play/pause caption, speed value) so
   * keyboard focus survives the ~4s ticks; rebuilt only when it doesn't exist yet. Sticky under
   * the topbar so the mode stays visible on long screens.
   */
  async function refreshWorldBand() {
    var seq = ++worldBandSeq;
    var world = App.state.world;
    document.body.classList.toggle('in-sim-world', !!world && world !== 'observed');
    if (!world || world === 'observed') {
      Array.prototype.slice.call(document.querySelectorAll('#world-band')).forEach(function (b) { b.remove(); });
      return;
    }
    var sess = null;
    try {
      var all = (await API.getFresh('/api/sim/market')).sessions || [];
      sess = all.find(function (x) { return x.id === world; });
    } catch (e) { /* band still renders minimal */ }
    if (seq !== worldBandSeq || App.state.world !== world) return;
    var cfg = sess && sess.config ? sess.config : {};
    var playing = !!(sess && sess.running);

    var band = document.getElementById('world-band');
    if (band && band.dataset.world === world) {
      // IN-PLACE update: never tear down a band someone is tabbing through.
      var lbl = band.querySelector('.wb-label');
      if (lbl) lbl.textContent = bandLabelText(sess, cfg);
      var toggle = band.querySelector('#world-toggle');
      if (toggle && toggle.textContent !== (playing ? 'Pause' : 'Play')) {
        toggle.textContent = playing ? 'Pause' : 'Play';
      }
      var spd = band.querySelector('#world-speed');
      if (spd && sess && sess.speed && document.activeElement !== spd) {
        spd.value = String(Math.round(sess.speed));
      }
      band.dataset.playing = String(playing);
      return;
    }
    Array.prototype.slice.call(document.querySelectorAll('#world-band')).forEach(function (b) { b.remove(); });

    var speedSel = UI.el('select', { id: 'world-speed', 'aria-label': 'Playback speed',
      title: 'How fast simulated time passes', onchange: async function () {
        try { await API.post('/api/sim/market/' + world + '/speed', { speed: parseFloat(speedSel.value) }); }
        catch (e) { UI.toast ? UI.toast(e.message) : alert(e.message); }
      } },
      SPEED_CHOICES.map(function (x) { return UI.el('option', { value: String(x) }, SPEED_LABELS[x] || (x + '\u00d7')); }));
    if (sess && sess.speed && SPEED_CHOICES.indexOf(Math.round(sess.speed)) < 0) {
      speedSel.appendChild(UI.el('option', { value: String(sess.speed) }, sess.speed + '\u00d7'));
    }
    if (sess && sess.speed) speedSel.value = String(Math.round(sess.speed));

    band = UI.el('div', { id: 'world-band', 'data-world': world, role: 'status' },
      UI.icon('warn', 15),
      UI.el('b', { class: 'wb-tag' }, 'SIMULATED MARKET'),
      window.Learn && UI.info ? UI.info('world') : null,
      UI.el('span', { class: 'wb-label' }, bandLabelText(sess, cfg)),
      cfg.seed !== undefined ? UI.el('span', { class: 'wb-seed' }, 'seed ' + cfg.seed) : null,
      cfg.seed !== undefined && window.Learn && UI.info ? UI.info('seed') : null,
      UI.el('button', { class: 'btn btn-sm', id: 'world-toggle', onclick: async function () {
        var isPlaying = band.dataset.playing === 'true';
        try {
          await API.post('/api/sim/market/' + world + '/' + (isPlaying ? 'pause' : 'start'), {});
          band.dataset.playing = String(!isPlaying);
          var t = band.querySelector('#world-toggle');
          if (t) t.textContent = !isPlaying ? 'Pause' : 'Play';
        } catch (e) { alert(e.message); }
      } }, playing ? 'Pause' : 'Play'),
      UI.el('button', { class: 'btn btn-sm', id: 'world-step', onclick: async function () {
        try { await API.post('/api/sim/market/' + world + '/step', {}); worldTicked(); }
        catch (e) { alert(e.message); }
      } }, 'Step'),
      speedSel,
      UI.el('button', { class: 'btn btn-sm', id: 'world-report', onclick: function () { App.navigate('#/data/simulation'); } },
        'Report'),
      UI.el('button', { class: 'btn btn-sm', id: 'world-exit', onclick: function () { App.switchWorld('observed'); } },
        'Return to real market'));
    band.dataset.playing = String(playing);
    document.body.insertBefore(band, document.getElementById('tape') || document.body.firstChild);
    // Pin the band just below the sticky topbar so the mode is ALWAYS visible.
    var topbar = document.querySelector('.topbar');
    if (topbar) band.style.top = topbar.getBoundingClientRect().height + 'px';
  }
  App.refreshWorldBand = refreshWorldBand;

  /**
   * A tick landed: the market MOVED. Flush the world's cached GETs and let the current screen
   * refresh its visible numbers in place (views register via App.onEvent with their route token).
   */
  var _lastTickFlush = 0;
  function worldTicked() {
    // NOT a global cache flush per tick (holistic review #12): live surfaces are fed by the
    // MarketStore/SSE; cached GETs self-expire in 20s anyway. A rate-limited, TARGETED
    // invalidation keeps navigation fresh without refetch storms and tape flicker.
    var now = Date.now();
    if (now - _lastTickFlush > 10000) {
      _lastTickFlush = now;
      API.invalidate(['/api/research', '/api/quotes', '/api/trades', '/api/portfolio', '/api/positions']);
    }
    refreshWorldBand();
  }

  // ---- ONE idempotent market-context transition (review P0 #1/#2 — never assign
  // App.state.world directly). Every path lands here: the awaited PUT, the finish flow, the
  // world.selected SSE hint, multi-tab adoption, and visibility reconciliation. The old
  // same-id early-return let whichever arrived FIRST (usually the SSE hint) do a band-only
  // update, and the real caller then skipped the universe/render reconciliation entirely —
  // the "still says Dom sim (simulated)" defect.

  // Workflow state is OWNED by the market context (review P1 #4): the outgoing lane's
  // thinking is stashed, the incoming lane's restored — a simulated symbol, thesis, or study
  // must never leak into observed screens (or vice versa).
  var LANE_KEYS = ['lastRecommendSymbol', 'marketThesis', 'researchStudy', 'evidencePrefill',
    'ideasPrefill', 'backtestPrefill', 'discoverForm', 'builderForm', 'backtestForm',
    'verifyForm', 'scenarioForm', 'ideasForm', 'scoutResults',
    // Result objects are market-DERIVED state (review P0 #1): an observed evaluation must
    // never render inside a simulated market. HTTP-cache flushes don't touch these.
    'recommendResults', 'decisionCache', 'decisionInflight', 'filterState', 'scoutForm'];
  function stashLane(worldId) {
    App.state._laneStash = App.state._laneStash || {};
    var box = {};
    LANE_KEYS.forEach(function (k) { box[k] = App.state[k]; delete App.state[k]; });
    App.state._laneStash[worldId || 'observed'] = box;
  }
  function restoreLane(worldId) {
    var box = (App.state._laneStash || {})[worldId || 'observed'] || {};
    LANE_KEYS.forEach(function (k) { if (box[k] !== undefined) App.state[k] = box[k]; });
  }

  App.transitionWorld = function (worldId, bootstrap) {
    var target = worldId || 'observed';
    // Collapse CONCURRENT transitions to the same target into one reconciliation: the SSE
    // hint and the awaited PUT often land within milliseconds. Skipping was the bug;
    // doubling the work is merely wasteful — collapsing does it exactly once, fully.
    if (App._transitionTarget === target && App._transitionP) return App._transitionP;
    App._transitionTarget = target;
    // ATOMIC TRANSITION (review P1 #6): requested vs committed are separate — NOTHING about
    // the app's state changes until the target lane's universe has actually loaded. A failed
    // hydration leaves the app honestly in the PREVIOUS market, with a visible retry.
    App.state.transitionStatus = 'pending';
    App._transitionP = (async function () {
      try {
        // HYDRATE FIRST (no state mutation): the server already knows the caller's active
        // world, so this GET answers for the TARGET lane.
        // TRANSACTIONAL HANDOFF (review IC-1): when the server's PUT already returned the
        // target bootstrap, commit from THAT payload — no second fetch to fail separately.
        var u = bootstrap && bootstrap.active ? bootstrap : null;
        if (!u) {
          try {
            u = await API.getFresh('/api/universe');
          } catch (e1) {
            await new Promise(function (res) { setTimeout(res, 1500); });
            u = await API.getFresh('/api/universe'); // one quiet retry, then loud
          }
        }
        // COMMIT PHASE — synchronous, no awaits between these mutations:
        var from = App.state.world || 'observed';
        var changed = from !== target;
        App.state.world = target;
        App.state.worldGen++;         // discard SSE/stale fills from the world we just left
        if (changed) { stashLane(from); restoreLane(target); }
        // A working idea priced in the OTHER market cannot survive the switch: its quotes,
        // expirations and account lane belong to a different world (review P2).
        if (App.state.ticket && (App.state.ticket.world || 'observed') !== target) {
          App.state.ticket = null;
          if (UI.toast) UI.toast('Working idea cleared — it was priced in the other market');
        }
        API.flushCache();             // every cached GET belonged to the old world
        if (App.Market) { App.Market.quotes = {}; App.Market.seq = 0; App.Market.world = target; App.Market.simTime = null; }
        App.state.universe = u;
        var dl = document.getElementById('universe-symbols');
        if (dl) {
          dl.innerHTML = '';
          (u.active.symbols || []).forEach(function (sym) { dl.appendChild(UI.el('option', { value: sym })); });
        }
        refreshWorldBand();
        refreshScenarioBanner();      // dataset exclusivity is server-enforced; the banner must agree
        var oldBar = document.getElementById('transition-error');
        if (oldBar) oldBar.remove();  // a successful reconciliation clears the failure state
        App.state.transitionStatus = 'committed';
        // Decorative surfaces refresh after commit (their failure never fails the transition)
        try { await refreshTape(); } catch (eT) { /* tape is decorative */ }
        subscribeMarketStream();      // the stream's default symbol set follows the new universe
        // Route guard: a symbol page the new market cannot serve is a dead end — land on a
        // symbol that EXISTS here instead of preserving a route that 404s its data.
        var m = (window.location.hash || '').match(/^#\/research\/([A-Z0-9._-]+)/i);
        var syms = (u.active && u.active.symbols) || [];
        if (m && syms.length && syms.indexOf(m[1].toUpperCase()) < 0) {
          if (UI.toast) UI.toast(m[1].toUpperCase() + ' is not in this market \u2014 showing ' + syms[0]);
          App.navigate('#/research/' + syms[0]);
          return;
        }
        return App.render();          // same screen, new market under it — observed never stopped
      } catch (e2) {
        // FAILED: no state was committed — the app still (honestly) shows the previous market.
        App.state.transitionStatus = 'failed';
        if (UI.toast) UI.toast('Market switch incomplete — the universe did not load. Retrying may help.');
        var bar = document.getElementById('transition-error');
        if (!bar) {
          bar = UI.el('div', { id: 'transition-error', class: 'alert alert-danger', role: 'alert',
            style: 'margin:8px' },
            'The market switch could not finish loading (' + ((e2 && e2.message) || 'network error') + '). ',
            UI.el('button', { class: 'btn btn-sm', onclick: function () {
              bar.remove(); App.transitionWorld(target).catch(function () { /* banner re-renders */ });
            } }, 'Retry'));
          var appRoot = document.getElementById('app');
          if (appRoot) appRoot.insertBefore(bar, appRoot.firstChild);
        }
        throw e2;                     // callers OBSERVE the failure (review P1: never resolve-as-success)
      } finally {
        App._transitionTarget = null; App._transitionP = null;
      }
    })();
    return App._transitionP;
  };

  /** The one-command world switch: preserves route/symbol/level — it changes the MARKET, not you. */
  App.switchWorld = async function (worldId) {
    try {
      var res = await API.put('/api/world', { world: worldId });
      if (res && res.datasetReset && UI.toast) {
        UI.toast('Back on the real market — your scenario dataset was switched off too');
      }
      // Transition failures reject AND paint the #transition-error banner — no alert on top.
      // The PUT's response carries the target bootstrap: server and client commit together.
      await App.transitionWorld(worldId, res && res.universe).catch(function () { /* visible in the banner */ });
    } catch (e) { alert(e.message); }
  };

  /** Compat alias: every adoption is the SAME full transition. */
  App.adoptWorld = function (worldId) { return App.transitionWorld(worldId); };

  window.App = App;

  function initHeader() {
    // Experience ladder: Beginner / Expert
    Learn.applyLevel(Learn.currentLevel());
    document.querySelectorAll('#level-switch button').forEach(function (b) {
      b.addEventListener('click', function () {
        Learn.setLevel(b.getAttribute('data-level'));
        applyLevelSideEffects();
        App.render(); // screens adapt structurally, not just visually
      });
    });

    var risk = document.getElementById('risk-mode');
    var storedRisk = null;
    try { storedRisk = window.localStorage.getItem('strikebench.riskMode'); } catch (e) { /* ignore */ }
    // RISK IS A BUDGET, not an experience level: 'learning' left the selector (the Beginner
    // experience level owns guidance); stored legacy values migrate to Cautious.
    if (storedRisk === 'learning') { storedRisk = 'conservative';
      try { window.localStorage.setItem('strikebench.riskMode', 'conservative'); } catch (e) { /* ignore */ } }
    if (storedRisk) risk.value = storedRisk;
    // ONE SOURCE OF TRUTH (review P0): the SERVER computes every mode's dollar budget
    // (percent x buying power, capped by declared risk capital). The header only displays it —
    // no client percentage arithmetic anywhere. Refreshed via the GET cache: every mutation
    // flushes the cache, and every render calls this, so the label follows trades, resets,
    // stock buys and world switches.
    function labelRiskOptions(rb) {
      var byMode = {};
      (rb.modes || []).forEach(function (m) { byMode[m.mode] = m; });
      risk.querySelectorAll('option').forEach(function (o) {
        var m = byMode[o.value];
        if (!m) return;
        // Compact: consequence in the label, mechanics in the tooltip (review: no sentences in a select)
        o.textContent = m.label + ' \u00b7 ' + UI.fmtMoneyCompact(m.effectiveBudgetCents) + '/idea';
        o.title = (m.percent * 100) + '% of ' + UI.fmtMoneyCompact(rb.basisCents) + ' buying power'
          + (m.capped ? ' \u2014 capped by your declared risk capital' : '');
      });
      risk.title = 'Max capital one new idea may put at risk \u2014 '
        + 'computed by the server from your current buying power'
        + (rb.explicitCapCents ? ', capped by your declared risk capital of ' + UI.fmtMoneyCompact(rb.explicitCapCents) : '')
        + '. Never changes the math: the same contract has the same odds in every mode.';
    }
    App.refreshRiskBudget = function () {
      return API.get('/api/risk-budget').then(function (rb) {
        if (!rb || !rb.modes) return;
        App.state.riskBudget = rb; // the ticket reconciliation reads THIS, never recomputes
        labelRiskOptions(rb);
      }).catch(function () { /* base labels stand; ticket falls back to server preview data */ });
    };
    App.refreshRiskBudget();
    risk.addEventListener('change', function () {
      try { window.localStorage.setItem('strikebench.riskMode', risk.value); } catch (e) { /* ignore */ }
      // A budget change INVALIDATES budget-derived results (review #6): the header must never
      // say Standard while the page still shows Cautious sizing. That includes results parked
      // in OTHER lanes' stashes (review IC-1) — a lane switch must not resurrect old sizing.
      App.state.recommendResults = null;
      App.state.decisionCache = null;
      App.state.decisionInflight = null;
      App.state.scoutResults = null;
      var stash = App.state._laneStash || {};
      Object.keys(stash).forEach(function (lane) {
        ['recommendResults', 'decisionCache', 'decisionInflight', 'scoutResults'].forEach(function (k) {
          delete stash[lane][k];
        });
      });
      App.render();
    });
  }

  // EXPERIENCE IS PRESENTATION ONLY (review P0): the risk budget is selectable at BOTH levels —
  // the label shows the dollar consequence, the info bubble explains, and the per-trade
  // acknowledgment gates catch oversized positions. No level ever mutates a persisted choice.
  function applyLevelSideEffects() { /* intentionally empty — kept for the two call sites */ }

  /** Full-page sign-in screen shown only when the server requires auth and we're signed out. */
  function renderSignIn(me) {
    var app = document.getElementById('app');
    if (!app) return;
    app.innerHTML = '';
    app.appendChild(UI.el('div', { class: 'card signin-card' }, [
      UI.el('h1', {}, 'Sign in'),
      UI.el('p', { class: 'muted' },
        'This StrikeBench instance is private. Sign in with your Google account to continue.'),
      UI.el('a', { class: 'btn btn-lg', href: (me && me.loginUrl) || '/auth/login' }, 'Sign in with Google')
    ]));
    app.setAttribute('data-ready', 'true');
  }

  /** Appends a Sign out link to the header — only when auth is enabled and a user is signed in. */
  function addSignOut() {
    if (!App._me || !App._me.authEnabled || !App.authUser) return;
    var controls = document.querySelector('.topbar-controls');
    if (!controls || document.getElementById('sign-out')) return;
    controls.appendChild(UI.el('a',
      { class: 'btn-link', id: 'sign-out', href: (App._me && App._me.logoutUrl) || '/auth/logout', title: 'Sign out' },
      'Sign out'));
  }

  async function boot() {
    // Synchronous shell first — no network gates the header, theme, or search box.
    initHeader();
    applyLevelSideEffects();
    initTheme();
    initSearch();
    // Instant feedback: paint a skeleton into #app while the bootstrap loads (App.render swaps it).
    var app = document.getElementById('app');
    if (app && !app.firstChild) app.appendChild(UI.skeleton());

    // ONE parallel round-trip for auth + config + the saved workspace — not three serial ones.
    // Route data is never fetched before auth state is known: App.render() runs only AFTER this
    // resolves, and an enabled-but-unauthenticated session swaps straight to the sign-in screen.
    var pair = await Promise.all([
      API.get('/api/auth/me').catch(function () { return null; }),
      API.get('/api/config').catch(function () { return null; }),
      API.get('/api/workspace').catch(function () { return null; })
    ]);
    var me = pair[0], cfg = pair[1], wsRemote = pair[2];
    App._me = me;
    if (cfg && cfg.disclaimer) document.getElementById('disclaimer').textContent = cfg.disclaimer;
    if (cfg && cfg.brand && cfg.brand.name) applyBrand(cfg.brand);
    App.config = cfg || {}; // expose config (marketOpen, fixturesOnly, fees…) to views
    // Fail CLOSED: /api/config is always readable (auth-open allowlist), so it's the reliable
    // "is auth on?" signal. If auth is enabled and we don't have a confirmed authenticated session
    // (me null from a transient /auth/me failure, or explicitly not authenticated) → sign-in, not a
    // protected route that would 401 into a route error.
    var authOn = (cfg && cfg.authEnabled) || (me && me.authEnabled);
    if (authOn && !(me && me.authenticated)) { renderSignIn(me); return; }
    App.authUser = (me && me.user) || null;
    addSignOut();

    // Continuity: restore the saved workspace (forms, working idea, working symbol) and — on a
    // bare open only — the route the user was on. An explicit hash (bookmark/link) always wins.
    // The local copy is namespaced per signed-in subject so shared browsers never cross-hydrate.
    if (window.Workspace) {
      var wsUser = (me && me.user && (me.user.id || me.user.email)) || 'local';
      var ws = Workspace.hydrate(wsRemote, wsUser);
      if (ws && ws.route && (!window.location.hash || window.location.hash === '#')) {
        window.location.hash = ws.route;
      }
      Workspace.start();
    }

    checkServerHealth();
    setInterval(checkServerHealth, 5 * 60 * 1000);
    if (cfg && cfg.scenarioMode) refreshScenarioBanner(); // restore the loud banner across reloads
    // worldReady RESOLVES once the boot lane is decided (observed confirmed, or the world
    // transition committed/failed). Anything that derives market-context state on first paint
    // (the welcome proof cache, prefetch) awaits this instead of racing /api/world (review P2).
    App.worldReady = API.get('/api/world').then(function (w) {
      var boot = (w && w.world) || 'observed';
      // BOOT IS A TRANSITION TOO (review P1 #3): reloading inside a simulated world must
      // reconcile the store, universe, lane state, and screens — not just paint the band
      // over observed-market state.
      if (boot !== 'observed') return App.transitionWorld(boot).catch(function () { /* banner shown */ });
    }).catch(function () { /* observed */ });
    App.onEvent('world.tick', function (type, data) {
      if (!data || data.world !== App.state.world) return; // a world we already left — discard
      var gen = App.state.worldGen;
      setTimeout(function () { if (gen === App.state.worldGen) worldTicked(); }, 50);
    });
    // Multi-tab truth: another tab (same user) switched worlds — adopt it here too, band and all.
    App.onEvent('world.selected', function (type, data) {
      if (!data || data.world === undefined) return;
      App.transitionWorld(data.world).catch(function () { /* banner shown */ }); // idempotent: same-target calls collapse
    });
    // Belt-and-braces for tabs whose SSE dropped: re-check the server's world on return.
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState !== 'visible') return;
      API.getFresh('/api/world').then(function (w) {
        var srv = (w && w.world) || 'observed';
        if (srv !== App.state.world) App.transitionWorld(srv).catch(function () { /* banner shown */ });
      }).catch(function () { /* offline — next visit retries */ });
    });
    refreshUniverse();
    subscribeMarketStream();          // live-ish tape from the engine (SSE); poll is the fallback
    subscribeEvents();                // typed workspace events (jobs, datasets, provider cooldowns)
    setInterval(refreshTape, 45 * 1000);
    window.addEventListener('hashchange', App.render);
    App.render();
  }

  // ---- Universe: one global setting feeding the tape, the scout, and symbol suggestions ----

  App.refreshUniverse = refreshUniverse;
  App.refreshTape = refreshTape; // exposed so tests can pin the no-rebuild behavior
  async function refreshUniverse(opts) {
    try {
      var u = await API.getFresh('/api/universe'); // transitions must see THIS lane, never a cached one
      App.state.universe = u;
      var dl = document.getElementById('universe-symbols');
      if (dl) {
        dl.innerHTML = '';
        (u.active.symbols || []).forEach(function (s) {
          dl.appendChild(UI.el('option', { value: s }));
        });
      }
      await refreshTape();
      subscribeMarketStream(); // re-subscribe: the stream's default symbol set follows the new universe
    } catch (e) {
      if (opts && opts.strict) throw e; // transition correctness depends on this (review P1 #4)
      /* outside transitions the tape is decorative; screens still work */
    }
  }

  /**
   * Scrolling universe ticker under the header: live quotes, click-through to research, and
   * the sector switcher — universes are one flick away on EVERY tab. The marquee is seamless:
   * one logical sequence is measured, cloned until each half at least fills the viewport, and
   * animated by exactly -50% at constant pixel speed (a fixed duration on a short strip is
   * what caused the visible clip-and-reattach seam).
   */
  async function refreshTape() {
    var tape = document.getElementById('tape');
    var strip = document.getElementById('tape-strip');
    if (!tape || !strip || !App.state.universe) return;
    // NEVER build while hidden: display:none measures zero widths and produces a broken
    // marquee. Mark stale; the route toggle rebuilds the moment the tape is visible again.
    if (tape.classList.contains('tape-offroute')) {
      strip.setAttribute('data-stale', '1');
      return;
    }
    strip.removeAttribute('data-stale');
    try {
      var data = await API.get('/api/quotes');
      var quotes = data.quotes || [];
      if (!quotes.length) { tape.hidden = true; return; }

      // Same symbols as the strip already shows? Update the numbers IN PLACE and leave
      // the marquee running \u2014 a rebuild restarts the animation at 0%, which reads as the
      // whole ticker jumping every refresh. Rebuild only when the symbol set changes.
      // Honest labeling on the ONE always-visible market surface: demo quotes get a DEMO chip
      // at the tape's leading edge (the tiles were quadruple-badged while the tape said nothing).
      var allDemo = quotes.length && quotes.every(function (q) { return q.freshness === 'FIXTURE'; });
      var allSim = quotes.length && quotes.every(function (q) { return q.freshness === 'SIMULATED'; });
      var demoChip = document.getElementById('tape-demo-chip');
      var chipWanted = allSim ? 'SIMULATED' : (allDemo ? 'DEMO' : null);
      if (chipWanted && (!demoChip || demoChip.textContent !== chipWanted)) {
        if (demoChip) demoChip.remove();
        tape.insertBefore(UI.el('span', { id: 'tape-demo-chip', class: allSim ? 'badge badge-sim' : 'badge badge-dim',
          title: allSim ? 'These prices are generated by your simulated session \u2014 not the market.'
                        : 'These prices are built-in demo data, not the market.' }, chipWanted), tape.firstChild);
      } else if (!chipWanted && demoChip) { demoChip.remove(); }
      var symbolsKey = quotes.map(function (q) { return q.symbol; }).join(',');
      if (strip.getAttribute('data-symbols') === symbolsKey && strip.children.length) {
        updateTapePrices(quotes);
        tape.hidden = false;
        return;
      }

      function sequence(interactive) {
        var seq = UI.el('span', { class: 'tape-seq', 'aria-hidden': interactive ? null : 'true' });
        quotes.forEach(function (q) {
          var last = parseFloat(q.last), prev = parseFloat(q.prevClose);
          var pct = prev ? (last - prev) / prev * 100 : 0;
          seq.appendChild(UI.el('button', {
            class: 'tape-item', type: 'button', tabindex: interactive ? '0' : '-1', 'data-sym': q.symbol,
            onclick: function () { App.navigate('#/research/' + q.symbol); }
          },
            UI.el('b', {}, q.symbol),
            UI.el('span', {}, last.toFixed(2)),
            UI.el('span', { class: pct >= 0 ? 'gain' : 'loss' },
              (pct >= 0 ? '\u25B2' : '\u25BC') + Math.abs(pct).toFixed(2) + '%')));
        });
        return seq;
      }

      strip.innerHTML = '';
      strip.style.animation = 'none';
      strip.appendChild(sequence(true));
      tape.hidden = false;
      // Measure one sequence, then build two identical halves that each cover the viewport
      var seqW = strip.firstChild.getBoundingClientRect().width || 1;
      var containerW = tape.querySelector('.tape-scroll').getBoundingClientRect().width || seqW;
      var perHalf = Math.max(1, Math.ceil(containerW / seqW));
      for (var i = 1; i < perHalf * 2; i++) strip.appendChild(sequence(false));
      var halfW = seqW * perHalf;
      strip.setAttribute('data-symbols', symbolsKey);
      strip.setAttribute('data-halfw', String(Math.round(halfW)));
      strip.style.animation = '';
      strip.style.animationDuration = Math.max(20, Math.round(halfW / 55)) + 's'; // ~55 px/s
      wireTapeResize();
    } catch (e) { tape.hidden = true; }
  }

  /**
   * In-place price/delta update for the tape strip — used by both the /api/quotes poll and the
   * SSE market stream. Only touches symbols already on the strip (no marquee restart). Returns
   * true if the incoming set matches the strip (so the SSE path knows whether it applied).
   */
  function updateTapePrices(quotes) {
    var strip = document.getElementById('tape-strip');
    if (!strip || !strip.children.length) return false;
    // F7: PER-SYMBOL delta updates — the old whole-list identity check silently discarded every
    // frame whose symbol set or ORDER differed from the strip's (SSE supersets, world frames).
    // Symbols without a matching tape item simply don't paint; rebuilds stay refreshTape's job.
    quotes.forEach(function (q) {
      var last = parseFloat(q.last), prev = parseFloat(q.prevClose);
      if (!isFinite(last)) return;
      var pct = prev ? (last - prev) / prev * 100 : 0;
      strip.querySelectorAll('.tape-item[data-sym="' + q.symbol + '"]').forEach(function (item) {
        item.children[1].textContent = last.toFixed(2);
        var d = item.children[2];
        d.className = pct >= 0 ? 'gain' : 'loss';
        d.textContent = (pct >= 0 ? '▲' : '▼') + Math.abs(pct).toFixed(2) + '%';
      });
    });
    return true;
  }

  /**
   * Subscribe to the backend market engine's SSE stream so the tape is live-ish (a few seconds)
   * from server memory instead of a 45s poll. Pure enhancement: if EventSource is unavailable or
   * the stream errors, the poll (below) keeps the tape fresh. Reconnects on universe change.
   */
  /**
   * The frontend MARKET STORE: one home for streamed quote state. The SSE stream writes frames
   * here; the tape, quote heroes, and tiles SUBSCRIBE instead of refetching — a world tick paints
   * the screen from memory, not from another GET (holistic review Phase 3).
   */
  App.Market = {
    quotes: {}, seq: 0, world: 'observed', simTime: null, _subs: [],
    onFrame: function (data) {
      if (!data || !data.quotes) return;
      if (data.seq && data.seq <= App.Market.seq && data.world === App.Market.world) return; // stale frame
      // LANE PURITY (review P0 #2): frames from a world that is NOT the committed active lane
      // are DROPPED — an old EventSource can deliver after App.state.world changed, and a frame
      // must never be an instruction to switch lanes. Only transitionWorld moves the store.
      if ((data.world || 'observed') !== (App.state.world || 'observed')) return;
      if ((data.world || 'observed') !== App.Market.world) {
        // Same as the committed lane but the store lags (transition set it first): align quietly.
        App.Market.quotes = {};
        App.Market.seq = 0;
        App.Market.world = data.world || 'observed';
      }
      App.Market.seq = data.seq || (App.Market.seq + 1);
      App.Market.simTime = data.simTime || null;
      data.quotes.forEach(function (q) { App.Market.quotes[q.symbol] = q; });
      App.Market._subs = App.Market._subs.filter(function (h) {
        return h.token === undefined || App.alive(h.token);
      });
      App.Market._subs.forEach(function (h) {
        try { h.fn(data); } catch (e) { /* one bad subscriber never kills the feed */ }
      });
    },
    subscribe: function (fn, token) { App.Market._subs.push({ fn: fn, token: token }); },
    get: function (symbol) { return App.Market.quotes[(symbol || '').toUpperCase()]; }
  };

  function subscribeMarketStream() {
    if (!window.EventSource) return; // older engines fall back to polling
    try { if (App._marketES) { App._marketES.close(); App._marketES = null; } } catch (e) { /* ignore */ }
    var es;
    try { es = new EventSource('/api/market/stream'); } catch (e) { return; }
    App._marketES = es;
    App._marketESerrors = 0; // fresh error budget per subscription (universe change / reconnect)
    es.addEventListener('quotes', function (ev) {
      try {
        var data = JSON.parse(ev.data);
        if (data && data.quotes) {
          App.Market.onFrame(data);      // the store first — heroes/tiles subscribe there
          updateTapePrices(data.quotes); // then the tape's in-place update
        }
      } catch (e) { /* ignore a malformed frame */ }
    });
    es.onerror = function () {
      // Let it retry a couple of times; if it keeps failing, drop to polling only.
      App._marketESerrors = (App._marketESerrors || 0) + 1;
      if (App._marketESerrors > 3) { try { es.close(); } catch (e) {} App._marketES = null; }
    };
  }
  App.subscribeMarketStream = subscribeMarketStream;

  /**
   * The typed event stream (/api/events): small server hints — job progress, dataset switches,
   * provider cooldowns, workspace revisions. Events never carry payloads; interested screens
   * refetch. Views register with App.onEvent(types, fn, token): handlers die with their route
   * (token) so a screen you left never reacts to events. Reconnect is EventSource-native and
   * replays recent events via Last-Event-ID; anything older is covered by normal refetching.
   */
  App._eventHandlers = [];
  App.onEvent = function (types, fn, token) {
    var list = Array.isArray(types) ? types : [types];
    // Prune dead-route handlers at registration too — dispatch-only pruning would let a
    // quiet stream accumulate one detached-DOM closure per screen visit.
    App._eventHandlers = App._eventHandlers.filter(function (h) {
      return h.token === undefined || App.alive(h.token);
    });
    App._eventHandlers.push({ types: list, fn: fn, token: token });
  };
  function dispatchAppEvent(type, data) {
    App._eventHandlers = App._eventHandlers.filter(function (h) {
      return h.token === undefined || App.alive(h.token);
    });
    App._eventHandlers.forEach(function (h) {
      if (h.types.indexOf(type) >= 0) {
        try { h.fn(type, data); } catch (e) { /* a handler must never kill the stream */ }
      }
    });
  }

  function subscribeEvents() {
    if (!window.EventSource) return;
    var es;
    try { es = new EventSource('/api/events'); } catch (e) { return; }
    App._eventsES = es;
    ['job.progress', 'job.complete', 'dataset.selected', 'provider.cooldown', 'workspace.updated', 'world.tick', 'world.selected']
      .forEach(function (type) {
        es.addEventListener(type, function (ev) {
          var data = null;
          try { data = JSON.parse(ev.data); } catch (e) { /* hint only */ }
          if (type === 'dataset.selected') refreshScenarioBanner();
          if (type === 'provider.cooldown' && data) showCooldownChip(data);
          if (type === 'workspace.updated' && data && window.Workspace) Workspace.onRemoteRev(data.rev);
          dispatchAppEvent(type, data);
        });
      });
    // EventSource reconnects itself; events are hints, so a gap costs nothing but freshness.
  }

  /**
   * Calm provider-cooldown status: a small amber chip in the header — never a scary banner.
   * The app keeps working from stale snapshots and other sources; this just says why numbers
   * may pause. Removes itself when the cooldown ends.
   */
  function showCooldownChip(data) {
    var until = data.untilMs || 0;
    var existing = document.getElementById('cooldown-chip');
    if (existing) existing.remove();
    if (until <= Date.now()) return;
    var when = new Date(until);
    var hh = String(when.getHours()).padStart(2, '0') + ':' + String(when.getMinutes()).padStart(2, '0');
    var name = data.provider === 'cboe' ? 'Cboe'
      : data.provider ? data.provider.charAt(0).toUpperCase() + data.provider.slice(1) : 'A data source';
    var chip = UI.el('span', {
      id: 'cooldown-chip',
      title: name + ' rate-limited us; requests pause until ~' + hh
           + '. Showing the last snapshot and other sources meanwhile.'
    }, UI.icon('warn', 13), ' ' + name + ' cooling down · ' + hh);
    var controls = document.querySelector('.topbar-controls');
    if (controls) controls.insertBefore(chip, controls.firstChild);
    setTimeout(function () {
      var c = document.getElementById('cooldown-chip');
      if (c) c.remove();
    }, Math.min(until - Date.now(), 2147000000));
  }
  App.showCooldownChip = showCooldownChip; // exposed for tests

  /**
   * Careful prefetch: after a screen settles, warm the LIKELY next step through the normal
   * GET cache during idle time. The request is marked X-Priority: prefetch so the backend can
   * refuse it when heavy providers lack budget — client guesses, server governs.
   */
  function prefetchForRoute(route, params) {
    if (!window.API || !API.prefetch) return;
    var sym = null;
    if (route === 'research' && params[0] && /^[A-Z.\-]{1,10}$/.test(params[0])) sym = params[0];
    else if (route === 'trade') sym = (App.state.lastRecommendSymbol || '').toUpperCase() || null;
    if (!sym) return;
    var run = function () {
      // Research → Trade: the ticket/builder need expirations + a quote first thing.
      API.prefetch('/api/research/' + sym + '/expirations');
      if (route === 'trade') API.prefetch('/api/research/' + sym + '/history?range=6m');
    };
    if (window.requestIdleCallback) requestIdleCallback(run, { timeout: 2500 });
    else setTimeout(run, 700);
  }

  // A tape built at one width leaves a BLANK region each cycle if the window later grows
  // past its half-length (nothing re-measured it — that read as "the ticker ends in a gap,
  // then the wrap-around gets attached"). Rebuild when the container outgrows the halves.
  var tapeResizeWired = false;
  function wireTapeResize() {
    if (tapeResizeWired || !window.ResizeObserver) return;
    var scrollEl = document.querySelector('.tape-scroll');
    if (!scrollEl) return;
    tapeResizeWired = true;
    var t = null;
    new ResizeObserver(function () {
      clearTimeout(t);
      t = setTimeout(function () {
        var tape = document.getElementById('tape');
        var strip = document.getElementById('tape-strip');
        if (!tape || !strip || tape.hidden || tape.classList.contains('tape-offroute')) return;
        var half = parseFloat(strip.getAttribute('data-halfw') || '0');
        if (half && scrollEl.clientWidth > half) {
          strip.removeAttribute('data-symbols'); // skip the in-place path: full re-measure
          refreshTape();
        }
      }, 250);
    }).observe(scrollEl);
  }

  /** Header symbol search: type-ahead from the universe, Enter to research, "/" to focus. */
  function initSearch() {
    var box = document.getElementById('global-search');
    if (!box) return;
    box.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && box.value.trim()) {
        App.navigate('#/research/' + box.value.trim().toUpperCase());
        box.value = '';
        box.blur();
      }
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === '/' && !/INPUT|SELECT|TEXTAREA/.test(document.activeElement.tagName)) {
        e.preventDefault();
        box.focus();
      }
    });
  }

  /** Brand comes from server config (BRAND_NAME / BRAND_TAGLINE) — rename without a rebuild. */
  function applyBrand(brand) {
    App.state.brand = brand;
    document.title = brand.name;
    var el = document.querySelector('.brand');
    if (el) {
      // Replace only the TEXT node — the SVG mark stays (textContent wiped it once)
      for (var i = el.childNodes.length - 1; i >= 0; i--) {
        if (el.childNodes[i].nodeType === 3) el.removeChild(el.childNodes[i]);
      }
      el.appendChild(document.createTextNode(brand.name));
    }
  }

  // ---- Theme: system / light / dark, persisted; html[data-theme] is set pre-paint ----

  var THEME_MODES = ['system', 'light', 'dark'];
  var THEME_LABEL = { system: 'Auto', light: 'Light', dark: 'Dark' };
  var THEME_ICON = { system: 'halftone', light: 'sun', dark: 'moon' };
  var mediaDark = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function themeMode() {
    try {
      var stored = window.localStorage.getItem('strikebench.theme');
      return THEME_MODES.indexOf(stored) >= 0 ? stored : 'system';
    } catch (e) { return 'system'; }
  }

  function applyTheme(mode) {
    var dark = mode === 'dark' || (mode === 'system' && mediaDark && mediaDark.matches);
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
    var btn = document.getElementById('theme-toggle');
    if (btn) {
      btn.innerHTML = '';
      btn.appendChild(UI.icon(THEME_ICON[mode], 14));
      btn.appendChild(document.createTextNode(' ' + THEME_LABEL[mode]));
      btn.title = 'Theme: ' + (mode === 'system' ? 'follows your system' : mode) + '. Click to cycle Auto / Light / Dark.';
    }
  }

  function initTheme() {
    applyTheme(themeMode());
    var btn = document.getElementById('theme-toggle');
    if (btn) {
      btn.addEventListener('click', function () {
        var next = THEME_MODES[(THEME_MODES.indexOf(themeMode()) + 1) % THEME_MODES.length];
        try { window.localStorage.setItem('strikebench.theme', next); } catch (e) { /* ignore */ }
        applyTheme(next);
      });
    }
    if (mediaDark && mediaDark.addEventListener) {
      mediaDark.addEventListener('change', function () {
        if (themeMode() === 'system') applyTheme('system');
      });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
