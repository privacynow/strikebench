/* StrikeBench Data Center view. Loaded after views.js for shared view primitives. */
(function () {
  'use strict';

  var S = window.ViewShared;
  var el = S.el, fmtMoney = S.fmtMoney, pnlSpan = S.pnlSpan, fmtPct = S.fmtPct,
      fmtNum = S.fmtNum, badge = S.badge, explain = S.explain,
      alertBox = S.alertBox, table = S.table, chip = S.chip;
  var visibleCommand = S.visibleCommand, focusPlanFrom = S.focusPlanFrom,
      pressable = S.pressable, prettyStrategy = S.prettyStrategy;
  var historyFetch = window.ViewResearch.historyFetch,
      lazySparklines = window.ViewResearch.lazySparklines;

  // ---------- 9. Data status ----------

  var STATE_BADGE = { OK: 'badge-ok', ERROR: 'badge-danger', EMPTY: 'badge-caution', UNKNOWN: 'badge-dim', UNCONFIGURED: 'badge-dim' };

  var JOB_BADGE = { DONE: 'badge-ok', RUNNING: 'badge-caution', QUEUED: 'badge-dim', FAILED: 'badge-danger', CANCELLED: 'badge-dim' };
  function dcProgress(done, total) {
    var pct = total > 0 ? Math.round(done / total * 100) : 0;
    return el('div', { class: 'dc-bar' }, el('div', { class: 'dc-bar-fill', style: 'width:' + pct + '%' }));
  }

  // The Data Center: where data comes from, how fresh it is, what we hold, and the jobs + resets
  // that manage it. A real operational hub, ladder-tuned. Progressive paint; each section fills
  // its own captured card (token-guarded so leaving the route never paints stale data).
  async function data(root, params) {
    var level = Learn.currentLevel();
    var token = App.navToken;
    // One authorization read owns every app-wide data control on this route. Source descriptions,
    // coverage, and job results remain readable; mutation affordances fail closed.
    var dataAccessP = API.get('/api/data/overview').catch(function () { return null; });
    // ROUTE-BACKED SUBNAVIGATION (holistic review addendum A): Data is five workspaces, not one
    // scroll. Data owns its canonical route; back/forward, bookmarks and workspace restore keep the tab.
    var TABS = [
      { key: 'overview', label: 'Overview' },
      { key: 'simulation', label: 'Simulated market' },
      { key: 'datasets', label: 'Datasets' },
      { key: 'sources', label: 'Sources & jobs' },
      { key: 'admin', label: 'Administration' }
    ];
    var tab = params && params[0] ? String(params[0]).toLowerCase() : 'overview';
    if (!TABS.some(function (t) { return t.key === tab; })) tab = 'overview';
    root.appendChild(el('h1', {}, 'Data center'));
    var dataTabs = el('div', { class: 'tabs data-tabs', id: 'data-tabs', role: 'tablist',
      'aria-label': 'Data center sections' },
      TABS.map(function (t) {
        return el('button', {
          type: 'button', id: 'data-tab-' + t.key, 'aria-controls': 'data-tab-panel',
          class: t.key === tab ? 'active' : '', 'data-tab': t.key, role: 'tab',
          'aria-selected': t.key === tab ? 'true' : 'false',
          tabindex: t.key === tab ? '0' : '-1',
          // Administration starts HIDDEN and appears only once authorization confirms — the
          // old version rendered it and hid it later (a flash), and stayed visible on error.
          style: t.key === 'admin' ? 'display:none' : null,
          onclick: function () { App.navigate('#/data/' + t.key); }
        }, t.label, el('span', { class: 'badge badge-ok', id: 'data-tab-badge-' + t.key,
          style: 'display:none; margin-left:6px' }));
      }));
    UI.bindTabList(dataTabs, function (button) { App.navigate('#/data/' + button.getAttribute('data-tab')); });
    var dataTabsWrap = el('div', { class: 'data-tabs-wrap' }, dataTabs);
    function syncDataTabsEdge() {
      dataTabsWrap.classList.toggle('at-end', dataTabs.scrollLeft + dataTabs.clientWidth >= dataTabs.scrollWidth - 2);
    }
    dataTabs.addEventListener('scroll', syncDataTabsEdge, { passive: true });
    root.appendChild(dataTabsWrap);
    requestAnimationFrame(syncDataTabsEdge);
    var dataPanel = el('section', { id: 'data-tab-panel', role: 'tabpanel',
      'aria-labelledby': 'data-tab-' + tab });
    root.appendChild(dataPanel);
    root = dataPanel;
    function setTabBadge(key, text, cls) {
      var b = document.getElementById('data-tab-badge-' + key);
      if (!b) return;
      b.textContent = text || '';
      b.className = 'badge ' + (cls || 'badge-ok');
      b.style.display = text ? '' : 'none';
      b.style.marginLeft = '6px';
    }
    // Quiet live state on the tabs themselves: light cached GETs now, SSE hints keep them true.
    var badgeTimer = null;
    async function fillTabBadges() {
      try {
        var ss = (await API.get('/api/sim/market')).sessions || [];
        var open = ss.filter(function (x) { return x.status !== 'FINISHED'; });
        var ticking = open.some(function (x) { return !!x.running; });
        setTabBadge('simulation', ticking ? 'LIVE' : open.length ? 'OPEN' : null,
          ticking ? 'badge-ok' : 'badge-warn');
      } catch (e) { /* badge only */ }
      try {
        var cfgd = await API.get('/api/config');
        setTabBadge('datasets', cfgd && cfgd.scenarioMode ? '1 ACTIVE' : null, 'badge-warn');
      } catch (e) { /* badge only */ }
      try {
        var jj = (await API.get('/api/data/jobs')).jobs || [];
        var run = jj.filter(function (x) { return x.status === 'RUNNING' || x.status === 'QUEUED'; }).length;
        setTabBadge('sources', run ? run + ' RUNNING' : null);
      } catch (e) { /* badge only */ }
    }
    fillTabBadges();
    // F14: Administration is authorization-gated — hidden entirely for non-admin users (the
    // server enforces regardless; the tab must not advertise what the caller cannot do).
    (async function gateAdminTab() {
      try {
        var ov = await dataAccessP;
        var btn = document.querySelector('#data-tabs [data-tab="admin"]');
        if (ov && ov.admin) {
          if (btn) btn.style.display = '';
          dataTabsWrap.classList.add('has-admin');
        }
        else if (tab === 'admin') App.navigate('#/data/overview');
      } catch (e) { /* stays hidden — fail closed both visually and on the server */ }
    })();
    if (App.onEvent) {
      App.onEvent(['job.progress', 'job.complete', 'dataset.selected', 'world.selected', 'world.control'], function () {
        clearTimeout(badgeTimer);
        badgeTimer = setTimeout(function () { if (App.alive(token)) fillTabBadges(); }, 400);
      }, token);
    }
    if (tab === 'overview') {
      root.appendChild(el('p', { class: 'dc-intro' },
        'Observed, Demo, Simulated and Scenario data stay separate. Missing observed data stays unavailable; fabricated values never replace it.'));
    }

    var engineCard = el('div', { class: 'card dc-overview-card', id: 'dc-engine' }, UI.spinner('Checking the market engine…'));
    // ---- Block S: the simulated-market workbench — a product surface, not a technical utility.
    // Beginner: scenario story cards + plain symbol list + speed words, everything else defaulted.
    // Expert: per-symbol beta/price rows, model seed, full knobs. Both levels: labeled fields,
    // info() on every technical term, app modals (never window.prompt/confirm).
    var SIM_SCENARIOS = [
      { key: 'CHOP', blurb: 'Sideways drift \u2014 quiet premium-selling weather.' },
      { key: 'TREND_UP', blurb: 'A steady climb with normal wobble.' },
      { key: 'TREND_DOWN', blurb: 'A steady decline with normal wobble.' },
      { key: 'SELLOFF_REBOUND', blurb: 'Falls hard, then recovers \u2014 tests your nerve.' },
      { key: 'RALLY_FADE', blurb: 'Rises first, then gives it back.' },
      { key: 'VOL_EVENT', blurb: 'Calm prices, jumpy options \u2014 volatility is the story.' }
    ];
    var activeSimulation = App.state.world && App.state.world !== 'observed' && App.state.world !== 'demo';
    var simCard = el('div', { class: 'card', id: 'dc-sim-market' }, UI.cardHeader('Simulated market'));
    if (activeSimulation) {
      simCard.appendChild(el('p', { class: 'muted small sim-active-note' },
        'Generated prices and fills stay inside this session and its separate simulation account.'));
    } else {
      simCard.appendChild(explain(beginnerDC()
        ? 'A practice market that MOVES: generated prices and option chains stream like the real thing, on any day, at any speed. Everything is loudly labeled \u2014 nothing here is real, and a separate simulation account keeps your practice account untouched.'
        : 'Deterministic per-session worlds: a beta factor model on fixed 30-sim-second quanta (speed never changes the path), a full generated option book, replayable from seed + event log. A separate simulation account is the only lane that trades a world.'));
    }
    // Demo remains reachable through the market switcher, but it must not compete with the
    // active world's control room. Two simultaneous primary market choices made the lane look
    // unresolved even though the backend was already inside the simulation.
    if (!activeSimulation) {
      simCard.appendChild(el('div', { class: 'demo-market-choice' },
        el('div', {}, el('b', {}, 'Built-in demo market'),
          el('p', { class: 'muted small' }, 'A fixed, fabricated market for learning the interface. It is isolated from observed data and uses its own account.')),
        el('span', { class: 'spacer' }),
        App.state.world === 'demo'
          ? el('span', { class: 'badge badge-warn' }, 'ACTIVE')
          : el('button', { class: 'btn btn-sm btn-secondary', id: 'enter-demo-market',
            onclick: function () { App.switchWorld('demo', this); } }, 'Enter demo market')));
    }
    function beginnerDC() { return window.Learn && Learn.currentLevel() === 'beginner'; }
    if (tab === 'simulation') root.appendChild(simCard);
    (async function () {
      if (!simCard.isConnected) return; // inactive tab: none of this loads (addendum B)
      var activeUniverse = (App.state.universe && App.state.universe.active) || {};
      var activeSymbols = (activeUniverse.symbols || []).slice();
      var wt = (App.context.symbol() || activeSymbols[0] || 'SPY').toUpperCase();
      var curSector = activeUniverse.sectorKey || '';
      var st = { scenario: 'CHOP', speed: 26,
        sectorKey: curSector !== 'world' ? curSector : '',
        includeActiveUniverse: curSector === 'world', symbolsText: wt, allowFictional: false,
        name: '', seed: '',
        rows: [{ symbol: wt, beta: 1, spot: '' }] };

      async function refreshSim() {
        var box = document.getElementById('dc-sim-sessions');
        if (!box) return;
        box.innerHTML = '';
        var sessions = [];
        try { sessions = (await API.getFresh('/api/sim/market')).sessions || []; }
        catch (e) { box.appendChild(alertBox('warn', 'Could not load sessions: ' + e.message)); return; }
        if (!sessions.length) {
          box.appendChild(el('div', { class: 'muted small' }, 'No simulated sessions yet \u2014 create one below.'));
          return;
        }
        var live = sessions.filter(function (x) { return x.status !== 'FINISHED'; });
        var done = sessions.filter(function (x) { return x.status === 'FINISHED'; });
        // A RUNNING WORLD DOMINATES this tab (addendum A2): the active session renders as a full
        // control room, other live sessions as rows, and the creator collapses to a side action.
        var current = null;
        live.forEach(function (x) { if (x.id === App.state.world) current = x; });
        if (current) {
          box.appendChild(controlRoom(current));
          if (App.state.focusSimControlRoom === current.id) {
            delete App.state.focusSimControlRoom;
            window.requestAnimationFrame(function () { window.requestAnimationFrame(function () {
              var room = document.getElementById('sim-control-room');
              if (room) room.scrollIntoView({ block: 'start' });
            }); });
          }
        }
        live.forEach(function (sx) { if (!current || sx.id !== current.id) box.appendChild(sessionRow(sx, false)); });
        var creatorEl = document.getElementById('sim-creator');
        var creatorToggle = document.getElementById('sim-creator-toggle');
        if (creatorEl) {
          if (current) {
            creatorEl.style.display = creatorEl.dataset.forced === '1' ? '' : 'none';
            if (!creatorToggle) {
              creatorToggle = el('button', { class: 'btn btn-sm btn-secondary', id: 'sim-creator-toggle',
                style: 'margin-top:8px', onclick: function () {
                  creatorEl.dataset.forced = creatorEl.style.display === 'none' ? '1' : '';
                  creatorEl.style.display = creatorEl.style.display === 'none' ? '' : 'none';
                } }, 'Create another session\u2026');
              creatorEl.parentNode.insertBefore(creatorToggle, creatorEl);
            }
          } else {
            creatorEl.style.display = '';
            if (creatorToggle) creatorToggle.remove();
          }
        }
        if (done.length) {
          box.appendChild(UI.expandable('Finished sessions (' + done.length + ') \u2014 reports kept',
            function () {
              var slot = el('div', {});
              done.forEach(function (sx) { slot.appendChild(sessionRow(sx, true)); });
              return slot;
            }, { stateKey: 'simulation-finished-sessions' }));
        }
      }

      async function waitForPreparedWorld(worldId, button) {
        var deadline = Date.now() + 120000;
        while (Date.now() < deadline) {
          var sessions = (await API.getFresh('/api/sim/market')).sessions || [];
          var session = sessions.find(function (x) { return x.id === worldId; });
          if (!session) throw new Error('The new simulated session could not be found.');
          if (session.status === 'CREATED' || session.status === 'PAUSED' || session.status === 'RUNNING') return session;
          if (session.status === 'FAILED') throw new Error('The simulated market could not finish preparing. Its session row keeps the anchor report.');
          if (button) {
            var pendingCount = (session.anchorSummary && session.anchorSummary.pending) || 0;
            button.textContent = pendingCount ? 'Preparing ' + pendingCount + ' symbols…' : 'Calibrating market…';
          }
          await new Promise(function (resolve) { setTimeout(resolve, 500); });
        }
        throw new Error('The market is still preparing. It remains saved here; enter it when its status changes to READY.');
      }

      /** The RUNNING world's console: clock, live symbols, P/L, and every action in one place. */
      function controlRoom(sx) {
        var cfg = sx.config || {};
        var rehearsal = sx.rehearsal || null;
        var syms = Object.keys(cfg.symbolBetas || {});
        App.state.simFocus = App.state.simFocus || {};
        var focusSym = syms.indexOf(App.state.simFocus[sx.id]) >= 0 ? App.state.simFocus[sx.id] : syms[0];
        var chartHost = null;
        var marketOverview = el('div', { class: 'sim-symbol-grid', id: 'cr-symbols', role: 'listbox',
          'aria-label': 'Session symbols' });
        syms.forEach(function (sym) {
          var tile = el('div', {
            class: 'sim-symbol-tile sym-card' + (sym === focusSym ? ' active' : ''),
            'data-symbol': sym, 'data-sym': sym, role: 'option', 'aria-selected': String(sym === focusSym),
            onclick: function () { selectFocus(sym); }
          },
            el('span', { class: 'sim-symbol-head' },
              el('b', {}, sym),
              el('span', { class: 'sim-symbol-price', 'data-cr-sym': sym }, '\u2026')),
            el('span', { class: 'sim-symbol-delta', 'data-cr-delta': sym }),
            el('span', { class: 'spark-slot' }, el('span', { class: 'muted small' }, 'chart queued')));
          marketOverview.appendChild(pressable(tile, 'Focus ' + sym + ' in this simulated market', 'option'));
        });
        function selectFocus(sym) {
          focusSym = sym;
          App.state.simFocus[sx.id] = sym;
          marketOverview.querySelectorAll('[role="option"]').forEach(function (btn) {
            var active = btn.getAttribute('data-symbol') === sym;
            btn.classList.toggle('active', active);
            btn.setAttribute('aria-selected', String(active));
          });
          if (chartHost) drawFocus();
        }
        var room = el('div', { class: 'card-slim', id: 'sim-control-room', 'data-sim-id': sx.id,
          style: 'margin:6px 0; padding:12px' },
          UI.cardHeader((rehearsal ? 'Plan rehearsal \u2014 ' : 'Control room \u2014 ') + (sx.name || sx.id),
            el('span', { class: 'badge ' + (sx.running ? 'badge-ok' : 'badge-warn'), id: 'cr-status' },
              sx.running ? 'RUNNING' : 'PAUSED')),
          el('div', { class: 'chip-row' },
            chip('Sim clock', el('span', { id: 'cr-clock' }, String(sx.simTime || '').replace('T', ' '))),
            rehearsal ? chip('Exact path', String(rehearsal.pathIndex + 1) + ' · ' + rehearsal.selection.toLowerCase())
              : chip('Scenario', App.scenarioLabel(cfg.scenario)),
            chip('Speed', el('span', { id: 'cr-speed' }, (sx.speed || cfg.speed || 1) + '\u00d7')),
            rehearsal ? chip('Receipt', String(rehearsal.fingerprint).slice(0, 12) + '…') : chip('Seed', String(cfg.seed)),
            sx.modelVersion ? chip('Model', sx.modelVersion) : null),
          el('div', { class: 'sim-market-overview' },
            el('div', { class: 'sim-overview-head' },
              el('div', {}, el('b', {}, 'Market overview'),
                el('div', { class: 'muted small' }, syms.length + ' symbols in this session \u00b7 select one to focus'))),
            marketOverview),
          el('div', { class: 'chip-row', id: 'cr-pl' }, el('span', { class: 'muted small' }, 'Loading positions\u2026')),
          el('div', { class: 'btn-row', style: 'margin-top:6px' },
            el('button', { class: 'btn btn-sm', id: 'cr-toggle', 'data-running': String(!!sx.running), onclick: async function () {
              try {
                var running = document.getElementById('cr-toggle').dataset.running !== 'true';
                await API.post('/api/sim/market/' + sx.id + '/' + (running ? 'start' : 'pause'), {});
                App.emitEvent('world.control', { world: sx.id, running: running });
                App.refreshWorldBand();
              }
              catch (e) { UI.toast(e.message || 'Could not change simulation playback', 'error'); } } }, sx.running ? 'Pause' : 'Play'),
            el('button', { class: 'btn btn-sm', onclick: async function () {
              try { await API.post('/api/sim/market/' + sx.id + '/step', {}); } catch (e) { UI.toast(e.message || 'Could not advance the simulated market', 'error'); } } }, 'Step'),
            rehearsal ? null : el('button', { class: 'btn btn-sm', onclick: function () { injectModal(sx); } }, 'Inject event'),
            el('button', { class: 'btn btn-sm', onclick: function () { showReport(sx); } }, 'Report'),
            rehearsal ? el('button', { class: 'btn btn-sm', onclick: function () {
              focusPlanFrom(this, rehearsal.planId, 'EVIDENCE');
            } }, 'Return to Plan') : el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research'); } }, 'Start a Plan'),
            el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/portfolio'); } }, 'Simulated portfolio'),
            el('button', { class: 'btn btn-sm btn-danger', onclick: function () { finishModal(sx); } }, 'Finish')));
        if (rehearsal) room.insertBefore(alertBox('caution', 'Exact Plan rehearsal', [
          'Spot and implied volatility replay stored path ' + (rehearsal.pathIndex + 1) + ' from ensemble ' + rehearsal.ensembleId + '.',
          'The receipt is ' + rehearsal.fingerprint + '. Shocks are disabled because they would break that identity.'
        ]), room.children[2]);
        // ---- The CONSOLE parts (F9): breadth, anchor coverage, focus chart, live P/L, heat,
        // event timeline. Everything below reuses existing primitives — no duplicate engines.
        var breadthChip = el('span', { class: 'chip', id: 'cr-breadth' },
          el('span', { class: 'chip-label' }, 'Breadth'), el('b', {}, '\u2026'));
        room.querySelector('.chip-row').appendChild(breadthChip);
        if (sx.anchorSummary) {
          var cov = sx.anchorSummary;
          room.querySelector('.chip-row').appendChild(chip('Anchored',
            String(cov.anchored || 0) + (cov.excluded ? ' \u00b7 ' + cov.excluded + ' excluded' : '')
              + (cov.pending ? ' \u00b7 ' + cov.pending + ' resolving' : ''),
            'Symbols with a priced start anchor; excluded symbols are named in the provenance table below.'));
        }
        // Focus chart: the same range-pill chart Research uses, on the tapped symbol.
        chartHost = el('div', { id: 'cr-chart', style: 'margin-top:6px' });
        room.appendChild(chartHost);
        function drawFocus() {
          chartHost.innerHTML = '';
          chartHost.appendChild(el('div', { class: 'sim-focus-head' },
            el('div', {}, el('span', { class: 'muted small' }, 'FOCUS'), el('b', {}, focusSym)),
            rehearsal ? el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
              focusPlanFrom(this, rehearsal.planId, 'EVIDENCE');
            } }, 'Source Plan \u2192') : el('a', { href: '#/research/' + encodeURIComponent(focusSym) }, 'Full research \u2192')));
          chartHost.appendChild(UI.rangeChart({ initial: '3m', fetch: historyFetch(focusSym) }));
        }
        drawFocus();
        var overviewSparks = lazySparklines(marketOverview, syms, {
          range: function () { return '1m'; },
          valid: function () { return room.isConnected && App.state.world === sx.id; },
          paint: function (row) {
            var slot = marketOverview.querySelector('.sim-symbol-tile[data-sym="' + row.symbol + '"] .spark-slot');
            if (!slot) return;
            slot.innerHTML = '';
            slot.appendChild(UI.sparkline(row, { height: 26 }));
          }
        });
        function paint() {
          var up = 0, down = 0;
          syms.forEach(function (sym) {
            var q = App.Market && App.Market.get(sym);
            if (!q) return;
            var last = parseFloat(q.last), prev = parseFloat(q.prevClose);
            if (isFinite(last) && isFinite(prev) && prev) (last >= prev ? up++ : down++);
            var slot = room.querySelector('[data-cr-sym="' + sym + '"]');
            if (slot) slot.textContent = fmtNum(q.last);
            var dslot = room.querySelector('[data-cr-delta="' + sym + '"]');
            if (dslot) { dslot.innerHTML = ''; dslot.appendChild(UI.delta(q.last, q.prevClose)); }
          });
          var b = room.querySelector('#cr-breadth b');
          if (b && (up + down) > 0) {
            b.textContent = up + '\u25B2 / ' + down + '\u25BC';
          }
          var c = room.querySelector('#cr-clock');
          if (App.Market && App.Market.simTime && c) c.textContent = App.Market.simTime.replace('T', ' ');
        }
        paint();
        // Live P/L: refreshed on stream frames, throttled to the server's mark-memo cadence —
        // the old version fetched ONCE and went stale (F9).
        var plLast = 0;
        var sparkLast = 0;
        async function fillPl() {
          try {
            var sum = await API.getFresh('/api/portfolio/summary');
            var pl = room.querySelector('#cr-pl');
            if (!pl || !sum || !room.isConnected) return;
            pl.innerHTML = '';
            if (sum.totalValueCents !== undefined && sum.totalValueCents !== null) {
              pl.appendChild(chip('Simulation account', UI.fmtMoney(sum.totalValueCents)));
            }
            if (sum.totalPnlCents !== undefined && sum.totalPnlCents !== null) {
              pl.appendChild(chip('P/L this session', pnlSpan(sum.totalPnlCents)));
            }
            try {
              var heat = await API.get('/api/portfolio/heat');
              if (heat && heat.totalWorstCaseCents !== undefined && heat.totalWorstCaseCents !== null) {
                pl.appendChild(chip('Book heat', UI.fmtMoney(heat.totalWorstCaseCents),
                  'Total worst case across open simulated positions.'));
              }
            } catch (e2) { /* heat is optional decoration here */ }
          } catch (e) {
            var pl2 = room.querySelector('#cr-pl');
            if (pl2 && room.isConnected) {
              pl2.innerHTML = '';
              pl2.appendChild(el('span', { class: 'muted small' }, 'Simulation account summary is temporarily unavailable.'));
              pl2.appendChild(el('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: fillPl }, 'Retry'));
            }
          }
        }
        fillPl();
        if (App.Market) App.Market.subscribe(function () {
          if (!room.isConnected) return;
          paint();
          var nowT = Date.now();
          if (nowT - plLast > 8000) { plLast = nowT; fillPl(); }
          if (nowT - sparkLast > 30000) { sparkLast = nowT; overviewSparks.reload(); }
        }, token);
        // Anchor provenance + event timeline: on-demand expandables (the detail endpoints load
        // only when opened — same only-what-you-open discipline as the tabs).
        room.appendChild(UI.expandable('Anchors & provenance \u2014 what this world is built on', function () {
          var slot = el('div', {}, UI.spinner('Loading provenance\u2026'));
          API.getFresh('/api/sim/market/' + sx.id + '/anchors').then(function (doc) {
            slot.innerHTML = '';
            var rows = (doc.anchors || []).map(function (a) {
              return el('tr', {},
                el('td', {}, el('b', {}, a.symbol)),
                el('td', {}, a.tier || ''),
                el('td', {}, a.price !== undefined ? fmtNum(a.price) : '\u2014'),
                el('td', { class: 'muted small' }, a.basis || ''),
                el('td', { class: 'muted small' }, a.calibration || ''));
            });
            (doc.excluded || []).forEach(function (x) {
              rows.push(el('tr', {},
                el('td', {}, el('b', {}, x.symbol)),
                el('td', {}, el('span', { class: 'badge badge-warn' }, 'EXCLUDED')),
                el('td', {}, '\u2014'),
                el('td', { class: 'muted small', colspan: '2' }, x.reason || '')));
            });
            if (doc.note) slot.appendChild(alertBox('caution', 'Late resolution', [doc.note]));
            slot.appendChild(table(['Symbol', 'Tier', 'Anchor', 'Basis', 'Calibration'], rows));
          }).catch(function (e) { slot.innerHTML = ''; slot.appendChild(alertBox('warn', 'Provenance unavailable: ' + e.message)); });
          return slot;
        }));
        room.appendChild(UI.expandable(rehearsal ? 'Replay identity \u2014 exact source and receipt'
          : 'Event timeline \u2014 every injected shock, replayable', function () {
          var slot = el('div', {}, UI.spinner('Loading events\u2026'));
          API.getFresh('/api/sim/market/' + sx.id + '/report').then(function (rep) {
            slot.innerHTML = '';
            if (rep.rehearsal) {
              slot.appendChild(el('div', { class: 'chip-row' },
                chip('Plan', rep.rehearsal.planId), chip('Ensemble', rep.rehearsal.ensembleId),
                chip('Path', String(Number(rep.rehearsal.pathIndex) + 1) + ' · ' + rep.rehearsal.selection.toLowerCase()),
                chip('Source model', rep.rehearsal.modelVersion), chip('Stored rate', fmtPct(rep.rehearsal.rateAnnual))));
              slot.appendChild(el('p', { class: 'muted small rehearsal-receipt' }, 'Receipt ' + rep.rehearsal.fingerprint));
              return;
            }
            var evs = rep.events || [];
            if (!evs.length) { slot.appendChild(el('div', { class: 'muted small' }, 'No injected events yet.')); return; }
            slot.appendChild(table(['Quantum', 'Kind', 'Symbol', 'Value'], evs.map(function (e2) {
              return el('tr', {},
                el('td', {}, String(e2.quantum)),
                el('td', {}, e2.kind),
                el('td', {}, e2.symbol || '\u2014'),
                el('td', {}, String(e2.value)));
            })));
          }).catch(function (e) { slot.innerHTML = ''; slot.appendChild(alertBox('warn', 'Events unavailable: ' + e.message)); });
          return slot;
        }));
        return room;
      }

      function sessionRow(sx, finished) {
        var cfg = sx.config || {};
        var rehearsal = sx.rehearsal || null;
        var active = App.state.world === sx.id;
        var preparing = sx.status === 'PREPARING';
        var failed = sx.status === 'FAILED';
        var rowInteractive = finished || (!preparing && !failed);
        var titleId = 'sim-session-title-' + sx.id;
        function openSession() {
          if (finished) { showReport(sx); return; }
          if (preparing || failed) return;
          App.state.focusSimControlRoom = sx.id;
          App.switchWorld(sx.id);
        }
        var summary = el('button', {
          type: 'button', class: 'sim-session-summary', disabled: !rowInteractive,
          'aria-label': finished ? 'Open report for ' + (sx.name || sx.id)
            : 'Enter simulated market ' + (sx.name || sx.id),
          onclick: openSession
        },
          el('b', { id: titleId }, sx.name || sx.id),
          rehearsal ? el('span', { class: 'badge badge-info' }, 'PLAN REHEARSAL') : null,
          el('span', { class: 'badge ' + (sx.running ? 'badge-ok' : failed ? 'badge-danger' : preparing ? 'badge-caution' : finished ? 'badge-dim' : 'badge-warn') },
            finished ? 'FINISHED' : failed ? 'FAILED' : preparing ? 'PREPARING' : sx.running ? 'RUNNING' : 'READY'),
          el('span', { class: 'muted small sim-session-meta' }, (rehearsal
            ? rehearsal.symbol + ' · path ' + (rehearsal.pathIndex + 1) + ' · ' + rehearsal.selection.toLowerCase()
              + ' · receipt ' + String(rehearsal.fingerprint).slice(0, 12) + '…'
            : App.scenarioLabel(cfg.scenario) + ' \u00b7 seed ' + cfg.seed)
            + (sx.eventCount ? ' \u00b7 ' + sx.eventCount + ' injected event' + (sx.eventCount === 1 ? '' : 's') : '')
            + (sx.simTime ? ' \u00b7 ' + String(sx.simTime).replace('T', ' ') : '')));
        var actions = el('div', { class: 'sim-session-actions', 'aria-label': 'Session controls' });
        var row = el('article', { class: 'card-slim sim-session-row' + (rowInteractive ? ' clickable' : ''),
          'data-sim-id': sx.id, 'aria-labelledby': titleId, style: 'margin:6px 0' }, summary, actions);
        if (!finished) {
          actions.appendChild(el('button', { class: 'btn btn-sm', disabled: preparing || failed, onclick: function () {
            if (!active) App.state.focusSimControlRoom = sx.id;
            App.switchWorld(active ? App.baseWorldId() : sx.id, this); } }, active
              ? (App.config && App.config.fixturesOnly ? 'Back to demo' : 'Back to real')
              : 'Enter this market'));
          actions.appendChild(el('button', { class: 'btn btn-sm', disabled: preparing || failed, onclick: async function () {
            try {
              var running = !sx.running;
              await API.post('/api/sim/market/' + sx.id + '/' + (sx.running ? 'pause' : 'start'), {});
              App.emitEvent('world.control', { world: sx.id, running: running });
              App.refreshWorldBand();
            }
            catch (e) { UI.toast(e.message || 'Could not change simulation playback', 'error'); } } }, sx.running ? 'Pause' : 'Start'));
          if (!rehearsal) actions.appendChild(el('button', { class: 'btn btn-sm', id: 'sim-inject-' + sx.id, disabled: preparing || failed, onclick: function () {
            injectModal(sx); } }, 'Inject event'));
        }
        actions.appendChild(el('button', { class: 'btn btn-sm', onclick: function () { showReport(sx); } }, 'Report'));
        if (!finished) {
          actions.appendChild(el('button', { class: 'btn btn-sm btn-danger', onclick: function () {
            finishModal(sx); } }, 'Finish'));
        }
        row.addEventListener('click', function (e) {
          if (e.target.closest('button,a,input,select,textarea')) return;
          openSession();
        });
        return row;
      }

      /** Inject-event: an app modal with a SYMBOL PICKER — never window.prompt (review P2). */
      function injectModal(sx) {
        var syms = Object.keys((sx.config || {}).symbolBetas || {});
        var symSel = el('select', { id: 'inject-symbol' }, syms.map(function (x) { return el('option', { value: x }, x); }));
        var moveIn = el('input', { type: 'number', id: 'inject-move', value: '-5', min: '-50', max: '50', step: '0.5' });
        var volIn = el('input', { type: 'number', id: 'inject-vol', value: '0', min: '-50', max: '100', step: '1' });
        var body = el('div', { class: 'form-grid' },
          UI.field('Symbol', symSel, { labelClass: 'field-label' }),
          UI.field('Price shock %', moveIn, { labelClass: 'field-label' }),
          UI.field('Volatility shift (IV points)', volIn, { labelClass: 'field-label' }),
          el('div', { class: 'muted small', style: 'grid-column:1/-1' },
            'The shock lands immediately and is recorded in the session\u2019s event log \u2014 replays include it.'));
        UI.confirmModal('Inject a market event', body, 'Inject', async function () {
          var pct = parseFloat(moveIn.value), vol = parseFloat(volIn.value);
          if (!isFinite(pct) && !isFinite(vol)) throw new Error('Enter a price shock or a volatility shift');
          var payload = {};
          if (isFinite(pct) && pct !== 0) { payload.symbol = symSel.value; payload.movePct = pct / 100; }
          if (isFinite(vol) && vol !== 0) payload.volShift = vol / 100;
          if (!Object.keys(payload).length) throw new Error('Nothing to inject \u2014 both fields are zero');
          await API.post('/api/sim/market/' + sx.id + '/event', payload);
          refreshSim(); App.refreshWorldBand();
        }, false);
      }

      /** Finish: shows THE REPORT first (the session's whole point), then confirms the finish. */
      function finishModal(sx) {
        var body = el('div', {}, UI.spinner('Loading the session report\u2026'));
        UI.confirmModal('Finish this session?', body, 'Finish session', async function () {
          var wasActive = App.state.world === sx.id;
          var result = await API.del('/api/sim/market/' + sx.id);
          // Finishing the ACTIVE world is a full market transition — never a bare state
          // assignment (review P0 #2: the old path skipped cache/store/universe/render and
          // left "Dom sim (simulated)" all over the observed screens).
          if (wasActive) await App.transitionWorld(result.world || App.baseWorldId(), result.universe,
            result.revision, result.epoch);
          refreshSim(); App.refreshWorldBand();
          if (sx.rehearsal && sx.rehearsal.planId) {
            await PlanStore.load(true);
            await PlanStore.focus(sx.rehearsal.planId, 'MANAGE_REVIEW');
          }
        }, true);
        API.getFresh('/api/sim/market/' + sx.id + '/report').then(function (rep) {
          body.innerHTML = '';
          body.appendChild(reportNode(rep));
          body.appendChild(el('div', { class: 'muted small', style: 'margin-top:8px' },
            'Finishing pauses the world for good. The report stays available under \u201cFinished sessions\u201d.'));
        }).catch(function (e) { body.innerHTML = ''; body.appendChild(alertBox('warn', 'Report unavailable: ' + e.message)); });
      }

      function reportNode(rep) {
        var n = el('div', { id: 'sim-report' },
          el('div', { class: 'chips' },
            rep.rehearsal ? el('span', { class: 'chip' }, 'Plan path ' + (Number(rep.rehearsal.pathIndex) + 1)) : null,
            el('span', { class: 'chip' }, 'Trades: ' + (rep.trades || []).length),
            el('span', { class: 'chip' }, 'Resolved: ' + rep.resolved),
            el('span', { class: 'chip' }, 'Win rate: ' + (rep.winRate == null ? '\u2014' : rep.winRate + '%')),
            el('span', { class: 'chip' }, 'Decision P/L: ' + UI.fmtMoney(rep.decisionPnlCents)),
            el('span', { class: 'chip' }, 'Model ' + (rep.modelVersion || 'sim-1'))),
          el('div', { class: 'muted small', style: 'margin-top:6px' }, rep.note));
        // The trader's ledger of DECISIONS: what was entered (on the sim clock), what the odds
        // said, how far it swung either way while open, and how it ended.
        var rows = rep.trades || [];
        if (rows.length) {
          n.appendChild(el('div', { style: 'overflow-x:auto; margin-top:8px' },
            el('table', { class: 'tbl' },
              el('thead', {}, el('tr', {},
                el('th', {}, 'Trade'),
                el('th', {}, 'Entered (sim clock)'),
                el('th', {}, 'Entry'),
                el('th', {}, 'POP'),
                el('th', {}, 'Worst / best while open'),
                el('th', {}, 'Result'),
                el('th', {}, 'How it ended'))),
              el('tbody', {}, rows.map(function (t) {
                return el('tr', {},
                  el('td', {}, el('b', {}, t.symbol), ' ', prettyStrategy(t.strategy) + ' x' + t.qty),
                  el('td', {}, t.laneEntryTime ? t.laneEntryTime.replace('T', ' ') : '\u2014'),
                  el('td', {}, pnlSpan(t.entryNetPremiumCents)),
                  el('td', {}, t.popEntry == null ? '\u2014' : Math.round(t.popEntry * 100) + '%'),
                  el('td', {}, t.maeCents == null ? '\u2014'
                    : el('span', {}, pnlSpan(t.maeCents), ' / ', pnlSpan(t.mfeCents))),
                  el('td', {}, t.decisionPnlCents == null && t.realizedPnlCents == null
                    ? el('span', { class: 'badge badge-dim' }, t.status)
                    : pnlSpan(t.decisionPnlCents != null ? t.decisionPnlCents : t.realizedPnlCents)),
                  el('td', { class: 'muted small' }, t.closeReason || '\u2014'));
              })))));
        }
        var pvo = rep.popVsOutcome;
        if (pvo && (pvo.highPopTrades || pvo.lowPopTrades)) {
          n.appendChild(el('div', { class: 'muted small', style: 'margin-top:6px' },
            'Decisions vs outcomes: POP\u226550% entries won '
            + (pvo.highPopWinRate == null ? '\u2014' : pvo.highPopWinRate + '%') + ' of ' + pvo.highPopTrades
            + '; below-50% entries won '
            + (pvo.lowPopWinRate == null ? '\u2014' : pvo.lowPopWinRate + '%') + ' of ' + pvo.lowPopTrades
            + '. ' + (pvo.note || '')));
        }
        var evs = rep.events || [];
        if (evs.length) {
          n.appendChild(el('div', { class: 'muted small', style: 'margin-top:4px' },
            'Injected events: ' + evs.map(function (e) {
              return e.kind + (e.symbol ? ' ' + e.symbol : '') + ' @ tick ' + e.quantum;
            }).join(' \u00b7 ')));
        }
        return n;
      }

      function showReport(sx) {
        var holder = document.getElementById('sim-report-holder');
        if (holder) holder.innerHTML = '';
        else {
          holder = el('div', { id: 'sim-report-holder', class: 'card-slim', style: 'margin:8px 0' });
          var box = document.getElementById('dc-sim-sessions');
          box.parentNode.insertBefore(holder, box.nextSibling);
        }
        holder.appendChild(el('b', {}, 'Session report \u2014 ' + (sx.name || sx.id)));
        API.getFresh('/api/sim/market/' + sx.id + '/report')
          .then(function (rep) { holder.appendChild(reportNode(rep)); })
          .catch(function (e) { holder.appendChild(alertBox('warn', 'Report unavailable: ' + e.message)); });
      }

      simCard.appendChild(el('div', { id: 'dc-sim-sessions' }));

      // ---- The creator ----
      var creator = el('div', { id: 'sim-creator', style: 'margin-top:10px' });
      simCard.appendChild(creator);
      renderCreator();

      function scenarioCards() {
        var wrap = el('div', { class: 'grid grid-3', id: 'sim-scenarios' });
        SIM_SCENARIOS.forEach(function (sc) {
          var selected = st.scenario === sc.key;
          var card = el('button', { class: 'choice sim-scenario' + (selected ? ' selected' : ''),
            type: 'button', 'data-scenario': sc.key, 'aria-pressed': selected ? 'true' : 'false', onclick: function () {
              st.scenario = sc.key; renderCreator();
            } },
            el('span', { class: 'choice-head' }, el('b', {}, App.scenarioLabel(sc.key)),
              selected ? el('span', { class: 'badge badge-info' }, 'SELECTED') : null),
            el('div', { class: 'muted small' }, sc.blurb));
          wrap.appendChild(card);
        });
        return wrap;
      }

      function labeled(text, node, infoKey) {
        return UI.field(infoKey ? [text, UI.info(infoKey)] : text, node, { labelClass: 'field-label' });
      }

      function renderCreator() {
        creator.innerHTML = '';
        var beginner = beginnerDC();
        creator.appendChild(el('div', { class: 'muted small', style: 'margin-bottom:6px' },
          'Create a session'));
        // Scenario: story cards at both levels (the story IS the configuration).
        var scLbl = el('div', { class: 'field-label' }, 'What kind of market?');
        scLbl.appendChild(UI.info('scenario'));
        creator.appendChild(scLbl);
        creator.appendChild(scenarioCards());

        var nameIn = el('input', { type: 'text', id: 'sim-name', value: st.name || '', placeholder: 'Weekend review' });
        nameIn.oninput = function () { st.name = nameIn.value; };
        var grid = el('div', { class: 'form-grid', style: 'margin-top:8px' }, labeled('Session name', nameIn));

        if (beginner) {
          // F10 TOKENIZED PICKER: type a symbol, press Enter — it becomes a removable chip.
          // Pasting "AAPL, MSFT" still works as a convenience, but comma syntax is never
          // required knowledge. Suggestions offer the current sector + held symbols one-tap.
          st.symbolsList = st.symbolsList || (st.symbolsText
            ? st.symbolsText.split(',').map(function (x) { return x.trim().toUpperCase(); }).filter(Boolean)
            : []);
          function syncSymbolsText() { st.symbolsText = st.symbolsList.join(', '); }
          var known = {};
          ((App.state.universe && App.state.universe.sectors) || []).forEach(function (sec) {
            (sec.symbols || []).forEach(function (x) { known[x] = true; });
          });
          // STABLE GEOMETRY: both rows exist from the first paint with reserved height — a chip
          // appearing on blur must never shift the Create button mid-click (level-geometry law).
          var chipsWrap = el('div', { class: 'btn-row', id: 'sim-symbol-chips',
            style: 'flex-wrap:wrap; gap:4px; margin:2px 0; min-height:30px' });
          var suggWrap = el('div', { class: 'btn-row muted small', id: 'sim-symbol-suggestions',
            style: 'flex-wrap:wrap; gap:4px; min-height:30px' });
          var symsIn = el('input', { type: 'text', id: 'sim-symbols', value: '',
            list: 'universe-symbols', placeholder: 'Type a symbol, press Enter' });
          function renderChips() {
            chipsWrap.innerHTML = '';
            st.symbolsList.forEach(function (sym, i) {
              var unknown = !known[sym];
              chipsWrap.appendChild(el('span', {
                class: 'chip' + (unknown ? ' chip-warn' : ''), 'data-picked-sym': sym,
                title: unknown ? 'Not a recognized ticker — it will be excluded unless "made-up tickers" is allowed below' : null
              }, el('b', {}, sym), unknown ? el('span', { class: 'muted small' }, ' ?') : null,
                el('button', { class: 'chip-x', 'aria-label': 'Remove ' + sym, onclick: function () {
                  st.symbolsList.splice(i, 1); syncSymbolsText(); renderChips();
                } }, '\u00d7')));
            });
          }
          function addTokens(text) {
            String(text || '').split(/[,\s]+/).forEach(function (part) {
              var sym = part.trim().toUpperCase();
              if (sym && st.symbolsList.indexOf(sym) < 0) st.symbolsList.push(sym);
            });
            syncSymbolsText(); renderChips();
          }
          symsIn.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); addTokens(symsIn.value); symsIn.value = ''; }
          });
          symsIn.addEventListener('change', function () { if (symsIn.value.trim()) { addTokens(symsIn.value); symsIn.value = ''; } });
          symsIn.addEventListener('paste', function () {
            setTimeout(function () { addTokens(symsIn.value); symsIn.value = ''; }, 0);
          });
          st.addPickerTokens = addTokens; // create-time: consume any residue still in the box
          st.pickerInput = function () { return symsIn.value; };
          grid.appendChild(labeled('Stocks to include', symsIn));
          creator.appendChild(chipsWrap);
          creator.appendChild(suggWrap);
          renderChips();
          // One-tap suggestions: current sector leaders + your held symbols — filled IN PLACE
          // (the row is already mounted with reserved height, so nothing below it moves).
          (function () {
            var sugg = [];
            var u = App.state.universe;
            if (u && u.active && u.active.symbols) sugg = sugg.concat(u.active.symbols.slice(0, 8));
            API.get('/api/positions').then(function (ps) {
              ((ps && ps.positions) || []).forEach(function (pp) { if (pp.shares > 0) sugg.push(pp.symbol); });
              renderSugg();
            }).catch(renderSugg);
            function renderSugg() {
              if (!suggWrap.isConnected) return;
              var uniq = sugg.filter(function (x, i) { return sugg.indexOf(x) === i && st.symbolsList.indexOf(x) < 0; });
              if (!uniq.length) return;
              suggWrap.appendChild(el('span', {}, 'Suggestions:'));
              uniq.slice(0, 10).forEach(function (sym) {
                suggWrap.appendChild(el('button', { class: 'btn btn-sm btn-secondary',
                  onclick: function () { addTokens(sym); this.remove(); } }, sym));
              });
            }
          })();
          var sectorChk = el('input', { type: 'checkbox', id: 'sim-use-sector' });
          sectorChk.checked = !!st.sectorKey || !!st.includeActiveUniverse;
          sectorChk.onchange = function () {
            if (!sectorChk.checked) { st.sectorKey = ''; st.includeActiveUniverse = false; return; }
            if (curSector === 'world') { st.sectorKey = ''; st.includeActiveUniverse = true; }
            else { st.sectorKey = curSector || 'CORE'; st.includeActiveUniverse = false; }
          };
          if (!st.sectorKey && !st.includeActiveUniverse && sectorChk.checked === false && curSector !== 'world') {
            st.sectorKey = curSector || 'CORE'; sectorChk.checked = true; // default ON
          }
          grid.appendChild(el('div', { class: 'field' },
            el('label', { class: 'sim-sector-choice', for: sectorChk.id }, sectorChk,
              el('span', {},
                el('span', { class: 'field-label' },
                  curSector === 'world' ? 'Bring my current market along' : 'Bring my current sector along'),
                el('span', { class: 'muted small' },
                  (curSector === 'world' ? (activeUniverse.label || 'Current market') + ' \u00b7 ' + activeSymbols.length + ' symbols'
                    : (st.sectorKey || 'CORE')) + ' \u2014 so the tape and explorer stay alive')))));
          // Honest units: speed multiplies TIME, so the label states what one 6.5h session
          // becomes. (The old '10x = a day in ~40 min' claim was mathematically false.)
          var speedSel = el('select', { id: 'sim-speed' },
            el('option', { value: '1' }, 'Real time \u2014 a session takes 6.5 hours (1\u00d7)'),
            el('option', { value: '26' }, 'One session in ~15 minutes (26\u00d7)'),
            el('option', { value: '78' }, 'One session in ~5 minutes (78\u00d7)'),
            el('option', { value: '390' }, 'One session in ~1 minute (390\u00d7)'));
          speedSel.value = String(st.speed);
          speedSel.onchange = function () { st.speed = parseFloat(speedSel.value); };
          grid.appendChild(labeled('How fast should time pass?', speedSel, 'speed'));
          creator.appendChild(grid);
          creator.appendChild(el('div', { class: 'muted small', style: 'margin:6px 0' },
            'Your held stocks plus SPY and QQQ ride along automatically. Recognized tickers anchor to the current market\'s last available price; one with no price is excluded (never invented). Made-up tickers are excluded unless you explicitly enable them below; when enabled, they start at $100. A fresh seed is drawn \u2014 the session report shows it so the exact market can be replayed.'));
        } else {
          // Expert: per-symbol rows (symbol / beta / start price) — no micro-syntax.
          var rowsWrap = el('div', { id: 'sim-symbol-rows' });
          st.rows.forEach(function (r, i) {
            var symIn = el('input', { type: 'text', value: r.symbol, placeholder: 'SYM', style: 'max-width:110px' });
            symIn.oninput = function () { r.symbol = symIn.value.toUpperCase(); };
            var betaIn = el('input', { type: 'number', value: String(r.beta), step: '0.1', min: '-3', max: '3', style: 'max-width:90px' });
            betaIn.oninput = function () { r.beta = parseFloat(betaIn.value); };
            var spotIn = el('input', { type: 'number', value: r.spot, placeholder: 'market anchor', min: '0.01', step: '0.01', style: 'max-width:120px' });
            spotIn.oninput = function () { r.spot = spotIn.value; };
            rowsWrap.appendChild(el('div', { class: 'btn-row', style: 'margin:4px 0' },
              symIn, betaIn, spotIn,
              el('button', { class: 'btn btn-sm btn-secondary', 'aria-label': 'Remove symbol', onclick: function () {
                st.rows.splice(i, 1); if (!st.rows.length) st.rows.push({ symbol: '', beta: 1, spot: '' });
                renderCreator();
              } }, '\u00d7')));
          });
          var secSel = el('select', { id: 'sim-sector' }, el('option', { value: '' }, 'No sector \u2014 only my rows'));
          if (curSector === 'world' && activeSymbols.length) {
            secSel.appendChild(el('option', { value: '__ACTIVE__' },
              (activeUniverse.label || 'Current market') + ' (' + activeSymbols.length + ')'));
          }
          (App.state.universe && App.state.universe.sectors || []).forEach(function (sec) {
            if (sec.key === 'world') return;
            var o = el('option', { value: sec.key }, sec.label + ' (' + sec.symbols.length + ')');
            secSel.appendChild(o);
          });
          secSel.value = st.includeActiveUniverse ? '__ACTIVE__' : (st.sectorKey || '');
          secSel.onchange = function () {
            st.includeActiveUniverse = secSel.value === '__ACTIVE__';
            st.sectorKey = st.includeActiveUniverse ? '' : secSel.value;
          };
          creator.appendChild(el('div', { class: 'btn-row', style: 'margin:4px 0' },
            el('span', { class: 'muted small' }, 'Background sector'), secSel,
            el('span', { class: 'muted small' }, 'anchored from warm data only \u2014 never a provider burst')));
          var rowHead = el('div', { class: 'btn-row muted small' },
            el('span', { style: 'width:110px' }, 'Symbol'),
            (function () { var x = el('span', { style: 'width:90px' }, 'Beta'); x.appendChild(UI.info('beta')); return x; })(),
            (function () { var x = el('span', { style: 'width:120px' }, 'Start price'); x.appendChild(UI.info('marketanchor')); return x; })());
          creator.appendChild(rowHead);
          creator.appendChild(rowsWrap);
          creator.appendChild(el('button', { class: 'btn btn-sm btn-secondary', id: 'sim-add-symbol', onclick: function () {
            st.rows.push({ symbol: '', beta: 1, spot: '' }); renderCreator();
          } }, '+ Add symbol'));

          var volIn = el('input', { type: 'number', id: 'sim-vol', value: String(st.vol || 30), min: '5', max: '200' });
          volIn.oninput = function () { st.vol = parseFloat(volIn.value); };
          var seedIn = el('input', { type: 'number', id: 'sim-seed', value: st.seed || '', placeholder: 'auto' });
          seedIn.oninput = function () { st.seed = seedIn.value; };
          var speedIn = el('input', { type: 'number', id: 'sim-speed', value: String(st.speed), min: '1', max: '2000' });
          speedIn.oninput = function () { st.speed = parseFloat(speedIn.value); };
          grid.appendChild(labeled('Market volatility %/yr', volIn));
          grid.appendChild(labeled('Seed (blank = fresh)', seedIn, 'seed'));
          grid.appendChild(labeled('Speed \u00d7 (sim-seconds per real second)', speedIn, 'speed'));
          creator.appendChild(grid);
        }

        var fictChk = el('input', { type: 'checkbox', id: 'sim-allow-fictional' });
        fictChk.checked = !!st.allowFictional;
        fictChk.onchange = function () { st.allowFictional = fictChk.checked; };
        creator.appendChild(el('label', { class: 'btn-row muted small', style: 'align-items:center; gap:6px; margin-top:6px' },
          fictChk, el('span', {}, 'Allow made-up tickers (explicit demo instruments; they start at $100)')));
        var createBtn = el('button', { class: 'btn', id: 'sim-create', style: 'margin-top:8px', onclick: async function () {
          createBtn.disabled = true;
          try {
            var symbols = {}, spots = {};
            if (beginner) {
              if (st.addPickerTokens && st.pickerInput && st.pickerInput().trim()) {
                st.addPickerTokens(st.pickerInput()); // whatever is still typed counts too
              }
              (st.symbolsList && st.symbolsList.length ? st.symbolsList
                : (st.symbolsText || wt).split(',').map(function (x) { return x.trim().toUpperCase(); }))
                .forEach(function (sym) { if (sym) symbols[sym] = 1.0; });
            } else {
              st.rows.forEach(function (r) {
                var sym = (r.symbol || '').trim().toUpperCase();
                if (!sym) return;
                symbols[sym] = isFinite(r.beta) ? r.beta : 1.0;
                var sp = parseFloat(r.spot);
                if (isFinite(sp) && sp > 0) spots[sym] = sp;
              });
            }
            if (st.includeActiveUniverse) {
              activeSymbols.forEach(function (sym) {
                if (sym && symbols[sym] === undefined) symbols[sym] = 1.0;
              });
            }
            if (!Object.keys(symbols).length) throw new Error('Add at least one symbol');
            var payload = { name: st.name || 'Simulated session', symbols: symbols,
              scenario: st.scenario, speed: st.speed, allowFictional: !!st.allowFictional };
            if (st.sectorKey) payload.sectorKey = st.sectorKey;
            if (Object.keys(spots).length) payload.spots = spots;
            // Persisted selections are never silently discarded (review P0): the vol/seed inputs
            // render at Expert, but a value SET there still applies if the world is created at
            // Beginner — per-symbol calibration remains the default when both are blank.
            if (st.vol) payload.volAnnual = st.vol / 100;
            if (st.seed) payload.seed = parseInt(st.seed, 10);
            var res = await API.post('/api/sim/market', payload);
            if (res.excluded && res.excluded.length && UI.toast) {
              UI.toast(res.excluded.length + ' symbol(s) excluded \u2014 no price available: '
                + res.excluded.map(function (x) { return x.symbol; }).join(', '));
            }
            if (res.resolving) {
              createBtn.textContent = 'Preparing market…';
              await refreshSim();
              await waitForPreparedWorld(res.worldId, createBtn);
            }
            await API.post('/api/sim/market/' + res.worldId + '/start', {});
            App.state.focusSimControlRoom = res.worldId;
            var entered = await App.switchWorld(res.worldId, createBtn);
            if (!entered) return;
            await refreshSim();
          } catch (e) { UI.toast(e.message || 'Could not create the simulated session', 'error'); }
          createBtn.disabled = false;
          createBtn.textContent = 'Create & enter';
        } }, 'Create & enter');
        creator.appendChild(createBtn);
      }

      if (App.onEvent) App.onEvent('world.control', function (type, data) {
        if (!App.alive(token) || !data || !data.world) return;
        var activeRoom = document.getElementById('sim-control-room');
        if (!activeRoom || activeRoom.dataset.simId !== data.world) {
          // A non-active row changed in another tab. Rehydrate the small session list; the active
          // control room, chart and focus are never replaced for its own controls.
          refreshSim();
          return;
        }
        if (typeof data.running === 'boolean') {
          var status = activeRoom.querySelector('#cr-status');
          if (status) {
            status.textContent = data.running ? 'RUNNING' : 'PAUSED';
            status.className = 'badge ' + (data.running ? 'badge-ok' : 'badge-warn');
          }
          var toggle = activeRoom.querySelector('#cr-toggle');
          if (toggle) {
            toggle.dataset.running = String(data.running);
            toggle.textContent = data.running ? 'Pause' : 'Play';
          }
        }
        if (data.speed !== undefined) {
          var speed = activeRoom.querySelector('#cr-speed');
          if (speed) speed.textContent = data.speed + '\u00d7';
        }
      }, token);
      if (App.onEvent) App.onEvent('world.resolving', function () {
        if (App.alive(token)) refreshSim();
      }, token);
      refreshSim();
    })();

    var datasetsCard = el('div', { class: 'card', id: 'dc-datasets' }, UI.cardHeader('Datasets & scenarios'), UI.spinner('Loading datasets…'));
    var coverageCard = el('div', { class: 'card', id: 'dc-coverage' }, UI.cardHeader('What data we hold'), UI.spinner('Loading coverage…'));
    var jobsCard = el('div', { class: 'card', id: 'dc-jobs' }, UI.cardHeader('Jobs'), UI.spinner('Loading jobs…'));
    var sourcesCard = el('div', { class: 'card', id: 'dc-sources' }, UI.cardHeader('Data sources'), UI.spinner('Loading sources…'));
    var syncCard = el('div', { class: 'card', id: 'dc-history-sync' },
      UI.cardHeader('Get & maintain daily price history'), UI.spinner('Planning available sources…'));
    var healthCard = el('div', { class: 'card dc-overview-card', id: 'dc-health' });
    var resetCard = el('div', { class: 'card', id: 'dc-reset' });
    // The Overview "where you are" card: current analysis mode + the recommended next action.
    var modeCard = el('div', { class: 'card dc-overview-card', id: 'dc-mode' }, UI.cardHeader('Where you are'), UI.spinner('Checking\u2026'));
    async function fillMode() {
      if (!modeCard.isConnected) return;
      var world = App.state.world || 'observed';
      var lines = [], actions = [];
      try {
        if (world === 'demo') {
          lines.push(el('div', { class: 'chip-row' },
            el('span', { class: 'badge badge-warn' }, 'DEMO MARKET'),
            el('b', {}, 'Built-in fabricated teaching data'),
            el('span', { class: 'muted small' }, 'isolated demo account')));
          actions.push(el('button', { class: 'btn', onclick: function () { App.navigate('#/research'); } }, 'Explore demo data'));
          if (!(App.config && App.config.fixturesOnly)) {
            actions.push(el('button', { class: 'btn btn-secondary', onclick: function () {
              App.switchWorld('observed', this);
            } }, 'Return to observed market'));
          }
        } else if (world !== 'observed') {
          var ss = (await API.get('/api/sim/market')).sessions || [];
          var cur = null;
          ss.forEach(function (x) { if (x.id === world) cur = x; });
          lines.push(el('div', { class: 'chip-row' },
            el('span', { class: 'badge badge-sim' }, 'SIMULATED MARKET'),
            el('b', {}, (cur && cur.name) || world),
            cur && cur.simTime ? el('span', { class: 'muted small' }, 'sim clock ' + String(cur.simTime).replace('T', ' ')) : null));
          actions.push(el('button', { class: 'btn', onclick: function () { App.navigate('#/data/simulation'); } }, 'Open the control room'));
          actions.push(el('button', { class: 'btn btn-secondary', onclick: function () { App.navigate('#/research'); } }, 'Research this market'));
        } else {
          var cfgd = await API.get('/api/config');
          if (cfgd && cfgd.scenarioMode) {
            lines.push(el('div', { class: 'chip-row' },
              el('span', { class: 'badge badge-warn' }, 'SCENARIO DATASET'),
              el('b', {}, cfgd.activeDatasetName || 'Generated scenario'),
              el('span', { class: 'muted small' }, 'analysis reads this generated history')));
            actions.push(el('button', { class: 'btn', onclick: function () { App.navigate('#/data/datasets'); } }, 'Manage datasets'));
            actions.push(el('button', { class: 'btn btn-secondary', onclick: function () { App.navigate('#/research'); } }, 'Research under this scenario'));
          } else {
            var u = App.state.universe;
            lines.push(el('div', { class: 'chip-row' },
              el('span', { class: 'badge badge-ok' }, 'OBSERVED MARKET'),
              el('span', { class: 'muted small' }, u && u.active ? ('universe: ' + (u.active.label || '') + ' \u00b7 ' + (u.active.symbols || []).length + ' symbols') : '')));
            actions.push(el('button', { class: 'btn', onclick: function () { App.navigate('#/research'); } }, 'Start a Plan'));
            actions.push(el('button', { class: 'btn btn-secondary', onclick: function () { App.navigate('#/data/simulation'); } }, 'Practice in a simulated market'));
          }
        }
      } catch (e) { lines.push(el('div', { class: 'muted' }, 'Mode unavailable: ' + e.message)); }
      if (!App.alive(token) || !modeCard.isConnected) return;
      modeCard.innerHTML = '';
      modeCard.appendChild(UI.cardHeader('Where you are'));
      lines.forEach(function (l) { modeCard.appendChild(l); });
      modeCard.appendChild(el('div', { class: 'btn-row', style: 'margin-top:8px' }, actions));
      syncOverviewToggle(modeCard, 'mode');
    }
    var activityCard = el('div', { class: 'card dc-overview-card', id: 'dc-activity' },
      UI.cardHeader('Running activity & coverage'), UI.spinner('Checking\u2026'));
    async function fillActivity() {
      if (!activityCard.isConnected) return;
      var linesA = [];
      try {
        var jj = (await API.get('/api/data/jobs')).jobs || [];
        var run = jj.filter(function (x) { return x.status === 'RUNNING' || x.status === 'QUEUED'; });
        var failed = jj.filter(function (x) { return x.status === 'FAILED'; });
        linesA.push(el('div', { class: 'status-item' },
          el('span', { class: 'badge ' + (run.length ? 'badge-ok' : 'badge-dim') }, run.length + ' RUNNING'),
          el('span', { class: 'muted small' }, failed.length ? failed.length + ' failed \u2014 see Sources & jobs' : 'background jobs'),
          el('span', { class: 'spacer' }),
          el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { App.navigate('#/data/sources'); } }, 'Sources & jobs \u2192')));
      } catch (e) { /* summary only */ }
      try {
        var cov = await API.get('/api/data/coverage');
        var sum = (cov && cov.summary) || {};
        linesA.push(el('div', { class: 'status-item' },
          el('span', { class: 'badge badge-dim' }, 'OBSERVED STORAGE'),
          el('span', { class: 'muted small' }, (sum.underlyingSymbols || 0) + ' symbols \u00b7 '
            + (sum.underlyingBars || 0) + ' daily bars'),
          el('span', { class: 'spacer' }),
          el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { App.navigate('#/data/datasets'); } }, 'Datasets \u2192')));
      } catch (e) { /* summary only */ }
      if (!App.alive(token) || !activityCard.isConnected) return;
      activityCard.innerHTML = '';
      activityCard.appendChild(UI.cardHeader('Running activity & coverage'));
      if (!linesA.length) activityCard.appendChild(el('div', { class: 'muted small' }, 'Nothing running.'));
      linesA.forEach(function (l) { activityCard.appendChild(l); });
      syncOverviewToggle(activityCard, 'activity');
    }
    var dcDetailBuilders = {};
    function openOverviewDetail(key, card, forceRefresh) {
      var host = document.getElementById('dc-detail');
      if (!host) return;
      var already = host.getAttribute('data-detail') === key;
      host.innerHTML = '';
      host.removeAttribute('data-detail');
      [modeCard, activityCard, engineCard, healthCard].forEach(function (c) {
        c.classList.remove('expanded');
        var toggle = c.querySelector(':scope > .dc-detail-toggle');
        if (toggle) { toggle.setAttribute('aria-expanded', 'false'); toggle.textContent = 'Open details ›'; }
      });
      if (already && !forceRefresh) return;
      host.setAttribute('data-detail', key);
      card.classList.add('expanded');
      var activeToggle = card.querySelector(':scope > .dc-detail-toggle');
      if (activeToggle) { activeToggle.setAttribute('aria-expanded', 'true'); activeToggle.textContent = 'Close details ‹'; }
      var detail = el('section', { class: 'card dc-detail-card' },
        UI.cardHeader(card.getAttribute('data-detail-title') || 'Data details'));
      var builder = dcDetailBuilders[key];
      detail.appendChild(builder ? builder() : el('p', { class: 'muted' }, 'Details are still loading.'));
      host.appendChild(detail);
      detail.scrollIntoView({ block: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    }
    function syncOverviewToggle(card, key) {
      var toggle = card.querySelector(':scope > .dc-detail-toggle');
      if (!toggle) {
        toggle = el('button', { type: 'button', class: 'dc-detail-toggle',
          'aria-controls': 'dc-detail', onclick: function (e) {
            e.stopPropagation(); openOverviewDetail(key, card);
          } }, 'Open details ›');
        card.appendChild(toggle);
      }
      var expanded = document.getElementById('dc-detail')?.getAttribute('data-detail') === key;
      toggle.setAttribute('aria-expanded', expanded ? 'true' : 'false');
      toggle.textContent = expanded ? 'Close details ‹' : 'Open details ›';
    }
    function registerOverviewCard(card, key, title) {
      card.setAttribute('data-detail-title', title);
      card.addEventListener('click', function (e) {
        if (e.target.closest('button,a,input,select')) return;
        openOverviewDetail(key, card);
      });
      syncOverviewToggle(card, key);
    }
    if (tab === 'overview') {
      var ovGrid = el('div', { class: 'dc-grid' });
      [modeCard, activityCard, engineCard, healthCard].forEach(function (c) { ovGrid.appendChild(c); });
      root.appendChild(ovGrid);
      // Expanded detail lives BELOW the 2x2 summary (review visual): opening a drawer must
      // never stretch a neighbor card into an empty wall.
      root.appendChild(el('div', { id: 'dc-detail', 'aria-live': 'polite' }));
      registerOverviewCard(modeCard, 'mode', 'Market and analysis mode');
      registerOverviewCard(activityCard, 'activity', 'Stored coverage and background work');
      registerOverviewCard(engineCard, 'engine', 'Per-symbol market engine state');
      registerOverviewCard(healthCard, 'health', 'Provider and request health');
      dcDetailBuilders.mode = function () {
        return el('div', {},
          el('p', {}, 'Market lanes stay isolated: Observed uses eligible market sources, Demo uses fabricated teaching data, Simulated uses a generated exchange, and Scenario changes analysis history only.'),
          el('div', { class: 'btn-row' },
            el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research'); } }, 'Open Research'),
            el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { App.navigate('#/data/datasets'); } }, 'Manage datasets')));
      };
      dcDetailBuilders.activity = function () {
        var holder = el('div', {}, UI.spinner('Loading stored coverage and jobs…'));
        Promise.all([API.get('/api/data/coverage'), API.get('/api/data/jobs')]).then(function (vals) {
          holder.innerHTML = '';
          var sum = (vals[0] && vals[0].summary) || {}, jobs = (vals[1] && vals[1].jobs) || [];
          holder.appendChild(el('div', { class: 'chip-row' },
            chip('Observed symbols', String(sum.underlyingSymbols || 0)),
            chip('Stored daily bars', String(sum.underlyingBars || 0)),
            chip('Jobs in progress', String(jobs.filter(function (j) { return j.status === 'RUNNING' || j.status === 'QUEUED'; }).length))));
          holder.appendChild(el('p', { class: 'muted small' },
            'Stored coverage is observed historical inventory. Provider health reports the latest source request. '
            + 'A closed market or unavailable live candle source does not erase stored past observations. '
            + (App.state.world === 'demo' ? 'Demo charts use separate built-in teaching history and are not counted here.' : '')));
        }).catch(function () {
          holder.innerHTML = '';
          holder.appendChild(el('p', { class: 'muted' }, 'Coverage detail is unavailable right now.'));
        });
        return holder;
      };
    }
    fillMode();
    fillActivity();
    if (tab === 'datasets') [datasetsCard, coverageCard].forEach(function (c) { root.appendChild(c); });
    if (tab === 'sources') [syncCard, sourcesCard, jobsCard].forEach(function (c) { root.appendChild(c); });
    if (tab === 'admin') {
      root.appendChild(explain('Destructive operations live HERE and only here — separated from routine data work on purpose. Every reset asks for its scope, states its consequences, and requires typed confirmation.'));
      root.appendChild(resetCard);
    }

    // --- Datasets: observed vs saved synthetic runs; the ACTIVE analysis dataset switch ---
    async function loadDatasets() {
      if (!datasetsCard.isConnected) return;
      var data;
      try { data = await API.getFresh('/api/datasets'); } catch (e) {
        if (!App.alive(token)) return;
        datasetsCard.innerHTML = ''; datasetsCard.appendChild(UI.cardHeader('Datasets & scenarios'));
        datasetsCard.appendChild(alertBox('warn', 'Datasets unavailable.')); return;
      }
      if (!App.alive(token)) return;
      var active = data.active, rows = data.datasets || [];
      datasetsCard.innerHTML = '';
      datasetsCard.appendChild(UI.cardHeader('Datasets & scenarios'));
      var datasetActionError = el('div', { id: 'dataset-action-error', 'aria-live': 'polite' });
      datasetsCard.appendChild(datasetActionError);
      async function runDatasetAction(button, action) {
        datasetActionError.innerHTML = '';
        button.disabled = true;
        try { await action(); }
        catch (e) {
          datasetActionError.appendChild(alertBox('warn', 'Dataset action could not be completed',
            [String((e && e.message) || e)]));
          button.disabled = false;
        }
      }
      if (level === 'beginner') datasetsCard.appendChild(explain(App.config && App.config.fixturesOnly
        ? 'This build’s baseline is fabricated Demo data. Saved scenario runs are separate made-up futures you generated; neither is observed market history.'
        : '“Observed” is real market data. Saved scenario runs are made-up futures you generated — pick one to explore the whole app as if that future happened. Real data is never overwritten.'));
      if (active !== 'observed') datasetsCard.appendChild(alertBox('caution',
        'SCENARIO MODE — analysis screens are using a synthetic dataset, not market data. Switch back to '
          + (App.config && App.config.fixturesOnly ? 'the Demo baseline' : 'Observed') + ' below when done.'));
      rows.forEach(function (d) {
        var isActive = d.id === active;
        datasetsCard.appendChild(el('div', { class: 'status-item', 'data-dataset': d.id },
          el('span', { class: 'badge ' + (d.id === 'observed' ? (App.config && App.config.fixturesOnly ? 'badge-warn' : 'badge-ok') : 'badge-caution') },
            d.id === 'observed' ? (App.config && App.config.fixturesOnly ? 'DEMO BASELINE' : 'OBSERVED') : 'SYNTHETIC'),
          el('b', {}, d.id === 'observed' && App.config && App.config.fixturesOnly ? 'Demo market baseline' : d.name),
          el('span', { class: 'muted small' }, d.id === 'observed' ? '' : (d.bars + ' bars')),
          el('span', { class: 'spacer' }),
          isActive ? el('span', { class: 'badge badge-ok' }, 'ACTIVE')
            : el('button', { class: 'btn btn-sm btn-secondary', onclick: async function () {
                var button = this;
                await runDatasetAction(button, async function () {
                  await API.put('/api/datasets/active', { id: d.id });
                  App.refreshScenarioBanner && App.refreshScenarioBanner();
                  await loadDatasets();
                });
              } }, 'Activate'),
          isActive && d.id !== 'observed' && d.symbol ? el('button', { class: 'btn btn-sm',
            title: 'Open this active scenario on the stock analysis page', onclick: function () {
              App.context.selectSymbol(d.symbol);
              App.navigate('#/research/' + encodeURIComponent(d.symbol));
            } }, 'Open Research') : null,
          d.id !== 'observed' ? el('button', { class: 'btn btn-sm btn-secondary', title: 'Delete this run',
            onclick: async function () {
              var button = this;
              await runDatasetAction(button, async function () {
                await API.del('/api/datasets/' + d.id);
                App.refreshScenarioBanner && App.refreshScenarioBanner();
                await loadDatasets();
              });
            } }, 'Delete') : null));
      });
      // Generate a new synthetic run right here (the same Scenario Studio as Research/Verify).
      var genWrap = el('div', { id: 'dc-generate', style: 'margin-top:8px' });
      var genOpen = el('button', { class: 'btn btn-sm', id: 'dc-generate-btn' }, 'Generate a scenario dataset…');
      genOpen.addEventListener('click', function () {
        genOpen.style.display = 'none';
        var sym = el('input', { type: 'text', id: 'dc-gen-sym', value: App.context.symbol('AAPL'), list: 'universe-symbols', style: 'max-width:110px' });
        App.state.dataScenarioForm = App.state.dataScenarioForm || {};
        var f = Scenario.form(level, null, {}, App.state.dataScenarioForm);
        var go = el('button', { class: 'btn', id: 'dc-gen-run' }, 'Generate & save');
        var note = el('div', { class: 'muted small' });
        go.addEventListener('click', async function () {
          go.disabled = true; note.textContent = 'Generating…';
          try {
            var r = await API.post('/api/datasets/generate', { symbol: sym.value.toUpperCase(), spec: f.getSpec() });
            // SCENARIO READY (holistic review Phase 5): a finished generation is a decision
            // point with next actions, not a bare "Saved" line the user must go hunting after.
            note.innerHTML = '';
            note.appendChild(el('div', { class: 'card-slim', id: 'scenario-ready', style: 'margin-top:8px; padding:10px' },
              el('div', { class: 'chip-row' },
                el('span', { class: 'badge badge-ok' }, 'SCENARIO READY'),
                el('b', {}, r.name || 'Generated scenario'),
                el('span', { class: 'muted small' }, (r.bars || '?') + ' daily bars · generated, not history')),
              el('div', { class: 'btn-row', style: 'margin-top:6px' },
                el('button', { class: 'btn btn-sm', onclick: async function () {
                  try {
                    await API.put('/api/datasets/active', { id: r.datasetId });
                    App.refreshScenarioBanner && App.refreshScenarioBanner();
                    App.context.selectSymbol(sym.value);
                    App.navigate('#/research/' + encodeURIComponent((sym.value || '').toUpperCase()));
                  } catch (e2) { UI.toast(e2.message || 'Could not activate this scenario', 'error'); }
                } }, 'Use it — explore in Research'),
                el('button', { class: 'btn btn-sm btn-secondary', onclick: async function () {
                  try { await API.del('/api/datasets/' + r.datasetId); note.innerHTML = 'Deleted.'; loadDatasets(); }
                  catch (e2) { UI.toast(e2.message || 'Could not delete this scenario', 'error'); }
                } }, 'Delete'))));
            loadDatasets();
          } catch (e) { note.textContent = 'Failed: ' + ((e && e.message) || e); }
          finally { go.disabled = false; }
        });
        genWrap.appendChild(UI.symbolContext({ mode: 'editable', id: 'dataset-symbol-context',
          input: sym, label: level === 'beginner' ? 'Which stock should this scenario follow?' : 'Scenario symbol' }));
        genWrap.appendChild(f.el);
        genWrap.appendChild(el('div', { class: 'btn-row' }, go));
        genWrap.appendChild(note);
      });
      genWrap.appendChild(genOpen);
      datasetsCard.appendChild(genWrap);
    }
    loadDatasets();

    // --- Jobs (declared first: engine/coverage actions start jobs and refresh this panel) ---
    var jobsTimer = null;
    async function loadJobs(reportFailure) {
      if (!jobsCard.isConnected) return;
      if (jobsTimer) { clearTimeout(jobsTimer); jobsTimer = null; } // one live poll chain, never stacked
      var data;
      try { data = await API.getFresh('/api/data/jobs'); } catch (e) {
        if (reportFailure) throw e;
        return;
      }
      if (!App.alive(token)) { if (jobsTimer) clearTimeout(jobsTimer); return; }
      var jobs = data.jobs || [];
      var access = await dataAccessP;
      var canAdmin = !!(access && access.admin);
      jobsCard.innerHTML = '';
      jobsCard.appendChild(UI.cardHeader('Jobs', el('button', { class: 'btn btn-sm btn-secondary', onclick: function () {
        visibleCommand(this, function () { return loadJobs(true); }, 'Jobs could not be refreshed.');
      } }, 'Refresh')));
      if (level === 'beginner') jobsCard.appendChild(explain('Background tasks that fetch or refresh data. Progress and results show here.'));
      if (!jobs.length) { jobsCard.appendChild(UI.emptyState('No jobs yet', 'Warm the engine or backfill history to create one.')); return; }
      var anyRunning = false;
      function jobName(kind) {
        return ({ sync_underlying: 'Update daily history',
          refresh_now: 'Refresh current quotes', warm_universe: 'Warm market cache',
          snapshot_now: 'Record option snapshot', import_options_csv: 'Import option history' })[kind] || kind;
      }
      jobs.forEach(function (j) {
        if (j.status === 'RUNNING' || j.status === 'QUEUED') anyRunning = true;
        var row = el('div', { class: 'dc-job' },
          el('div', { class: 'chip-row', style: 'align-items:center;margin:0' },
            el('span', { class: 'badge ' + (JOB_BADGE[j.status] || 'badge-dim') }, j.status),
            el('b', {}, jobName(j.kind)),
            el('span', { class: 'muted' }, j.done + '/' + j.total + ' · ' + j.rowsWritten + ' rows'),
            el('span', { class: 'spacer' }),
            (j.status === 'RUNNING' || j.status === 'QUEUED') && (canAdmin || !['sync_underlying', 'import_options_csv'].includes(j.kind))
              ? el('button', { class: 'btn btn-sm btn-secondary', onclick: function () {
                visibleCommand(this, async function () {
                  await API.post('/api/data/jobs/' + j.id + '/cancel', {});
                  await loadJobs(true);
                }, 'The job could not be cancelled.');
              } }, 'Cancel')
              : (j.status === 'FAILED' || j.status === 'CANCELLED') && (canAdmin || !['sync_underlying', 'import_options_csv'].includes(j.kind))
                ? el('button', { class: 'btn btn-sm btn-secondary', onclick: function () {
                  visibleCommand(this, async function () {
                    await API.post('/api/data/jobs/' + j.id + '/retry', {});
                    await loadJobs(true);
                  }, 'The job could not be retried.');
                } }, 'Retry')
                : null),
          dcProgress(j.done, j.total),
          j.message ? el('div', { class: 'muted small' }, j.message) : (j.error ? el('div', { class: 'loss small' }, j.error) : null),
          UI.expandable('Per-symbol details', function () {
            var detail = el('div', {}, UI.spinner('Loading item checkpoints…'));
            API.getFresh('/api/data/jobs/' + j.id).then(function (d) {
              detail.innerHTML = '';
              var items = d.items || [];
              if (!items.length) { detail.appendChild(el('p', { class: 'muted' }, 'No item detail.')); return; }
              detail.appendChild(table(['Item', 'State', 'Rows', 'Result'], items.map(function (x) {
                return el('tr', {}, el('td', {}, el('b', {}, x.label)),
                  el('td', {}, el('span', { class: 'badge ' + (x.status === 'DONE' ? 'badge-ok'
                    : x.status === 'FAILED' ? 'badge-danger' : x.status === 'PENDING' ? 'badge-caution' : 'badge-dim') }, x.status)),
                  el('td', {}, String(x.rowsWritten || 0)), el('td', { class: 'muted small' }, x.note || ''));
              })));
            }).catch(function (e) { detail.innerHTML = ''; detail.appendChild(alertBox('warn', e.message)); });
            return detail;
          }));
        jobsCard.appendChild(row);
      });
      // Poll is the FALLBACK: with the event stream connected, job.progress events drive the
      // refresh and a slow 10s poll just catches anything missed; without it, 2s keeps it live.
      var pollMs = App.eventsStreamHealthy && App.eventsStreamHealthy() ? 10000 : 2000;
      if (anyRunning && App.alive(token)) jobsTimer = setTimeout(loadJobs, pollMs);
    }

    // Live progress: job events re-render the card the moment the backend reports movement.
    // Debounced (events can arrive per item); dies with the route via the render token.
    if (App.onEvent) {
      var jobsEventTimer = null;
      App.onEvent(['job.progress', 'job.complete'], function () {
        clearTimeout(jobsEventTimer);
        jobsEventTimer = setTimeout(function () { if (App.alive(token)) loadJobs(); }, 300);
      }, token);
    }

    async function startJob(kind, params) {
      await API.post('/api/data/jobs', { kind: kind, params: params || {} });
      await loadJobs();
    }

    // --- Engine status ---
    (async function fillEngine() {
      if (!engineCard.isConnected) return;
      var ov;
      try { ov = await API.get('/api/data/overview'); } catch (e) {
        if (!App.alive(token)) return;
        engineCard.innerHTML = ''; engineCard.appendChild(UI.cardHeader(
          el('span', {}, 'Market engine', UI.info('marketengine'))));
        engineCard.appendChild(alertBox('warn', 'Engine status unavailable.'));
        syncOverviewToggle(engineCard, 'engine');
        renderReset(false); // fail closed on the destructive panel
        return;
      }
      if (!App.alive(token)) return;
      renderReset(!!ov.admin); // the reset panel is admin-gated
      var e = ov.engine || {};
      var isDemoEngine = ov.marketLane === 'DEMO';
      var isObservedEngine = ov.marketLane === 'OBSERVED';
      var activeUniverseSize = App.state.universe && App.state.universe.active
        ? (App.state.universe.active.symbols || []).length : 0;
      engineCard.innerHTML = '';
      engineCard.appendChild(UI.cardHeader(el('span', {}, 'Market engine', UI.info('marketengine')),
        el('button', { class: 'btn btn-sm', id: 'dc-refresh-now', onclick: function () {
          visibleCommand(this, function () { return startJob('refresh_now', {}); },
            'Current quotes could not be refreshed.');
        } }, 'Refresh now')));
      engineCard.appendChild(el('div', { class: 'chip-row' },
        isDemoEngine ? chip('Feed', 'Static Demo')
          : !isObservedEngine ? chip('Engine', 'Observed background')
          : chip('Market', ov.marketOpen ? 'Open' : 'Closed'),
        isDemoEngine ? chip('Symbols', String(activeUniverseSize)) : chip('Warmed', (e.warmed || 0) + ' / ' + (e.tracked || 0)),
        chip('State', isDemoEngine ? 'Ready' : e.errors ? 'Needs attention' : e.inFlight ? e.inFlight + ' refreshing'
          : e.stale ? e.stale + ' stale' : 'Ready')));
      if (isDemoEngine) {
        engineCard.appendChild(el('p', { class: 'muted small data-health-basis' },
          'Fabricated prices and chains are ready without provider requests.'));
      } else if (!isObservedEngine) {
        engineCard.appendChild(el('p', { class: 'muted small data-health-basis' },
          'Observed data keeps refreshing in the background for an immediate return.'));
      } else if (e.symbols && e.symbols.length) {
        var tracked = el('div', { class: 'chip-row dc-engine-tracked' },
          el('span', { class: 'muted small' }, 'Tracked'));
        e.symbols.slice(0, 6).forEach(function (s) {
          tracked.appendChild(el('span', { class: 'badge ' + (s.error ? 'badge-danger'
            : s.refreshing ? 'badge-caution' : 'badge-dim') }, s.symbol));
        });
        if (e.symbols.length > 6) tracked.appendChild(el('span', { class: 'muted small' }, '+' + (e.symbols.length - 6) + ' more'));
        engineCard.appendChild(tracked);
      }
      syncOverviewToggle(engineCard, 'engine');
      dcDetailBuilders.engine = function () {
        var detail = el('div', {},
          isDemoEngine ? alertBox('warn',
            'The explicit Demo market uses fabricated teaching prices and an isolated account.') : null,
          !isObservedEngine && !ov.fixturesOnly ? el('p', { class: 'muted small' },
            'The observed provider engine remains active in the background. The metrics below describe that observed engine, not the active generated market.') : null);
        if (isDemoEngine && ov.fixturesOnly) {
          detail.appendChild(el('p', {},
            'This review build is Demo-only. Its static teaching feed does not contact market-data providers.'));
          return detail;
        }
        detail.appendChild(
          el('div', { class: 'chip-row' },
            chip('Refreshing', String(e.inFlight || 0)),
            chip('Stale', String(e.stale || 0)),
            isDemoEngine ? null : chip('Refresh every', (e.refreshInterval || 0) + 's'),
            chip('Avg latency', (e.avgLatencyMs || 0) + ' ms'),
            e.errors ? chip('Errors since startup', String(e.errors),
              'Total provider/refresh errors since this server started — not a current-failure count.') : null));
        if (!(e.symbols && e.symbols.length)) {
          detail.appendChild(el('p', { class: 'muted' }, 'No symbols are tracked yet.'));
          return detail;
        }
        detail.appendChild(table(['Symbol', 'Fresh', 'Source', 'Age', 'State'], e.symbols.map(function (s) {
            return el('tr', {},
              el('td', {}, el('b', {}, s.symbol)),
              el('td', {}, badge(s.freshness)),
              el('td', { class: 'muted' }, s.source || '—'),
              el('td', { class: 'muted' }, s.ageMs >= 0 ? Math.round(s.ageMs / 1000) + 's' : '—'),
              el('td', {}, s.refreshing ? el('span', { class: 'badge badge-caution' }, 'refreshing') : (s.error ? el('span', { class: 'badge badge-danger', title: s.error }, 'error') : el('span', { class: 'muted' }, 'ok'))));
          })));
        return detail;
      };
      if (document.getElementById('dc-detail')?.getAttribute('data-detail') === 'engine') {
        openOverviewDetail('engine', engineCard, true);
      }
    })();

    // --- Coverage ---
    (async function fillCoverage() {
      if (!coverageCard.isConnected) return;
      var data;
      try { data = await API.get('/api/data/coverage'); } catch (e) {
        if (!App.alive(token)) return;
        coverageCard.innerHTML = ''; coverageCard.appendChild(UI.cardHeader('What data we hold'));
        coverageCard.appendChild(alertBox('warn', 'Coverage unavailable.')); return;
      }
      if (!App.alive(token)) return;
      var sum = data.summary || {}, syms = data.symbols || [];
      coverageCard.innerHTML = '';
      var uni = (App.state.universe && App.state.universe.active && App.state.universe.active.symbols) || [];
      coverageCard.appendChild(UI.cardHeader('What data we hold',
        el('button', { class: 'btn btn-sm', id: 'dc-backfill',
          disabled: App.config && App.config.fixturesOnly,
          title: App.config && App.config.fixturesOnly
            ? 'Observed backfill is unavailable in this explicit Demo build'
            : 'Choose a source, preview missing dates, then update only what is needed',
          onclick: function () { App.state.dataSyncForm = App.state.dataSyncForm || {};
            App.state.dataSyncForm.symbols = uni.join(', '); App.navigate('#/data/sources'); } }, 'Get or update history')));
      if (App.config && App.config.fixturesOnly) coverageCard.appendChild(alertBox('caution',
        'Observed backfill is unavailable in this explicit Demo build. Demo history stays isolated.'));
      if (level === 'beginner') coverageCard.appendChild(explain('The price history we have stored and its source. Backfill pulls observed daily history when an allowed source is configured; Demo rows never enter Observed coverage.'));
      coverageCard.appendChild(el('div', { class: 'chip-row' },
        chip('Underlying symbols', String(sum.underlyingSymbols || 0)),
        chip('Daily bars', String(sum.underlyingBars || 0)),
        chip('Option symbols', String(sum.optionSymbols || 0)),
        chip('Option rows', String(sum.optionRows || 0)),
        chip('Observed', (sum.observedUnderlyingSymbols || 0) + ' symbols')));
      if (!syms.length) {
        coverageCard.appendChild(UI.emptyState('No stored history yet',
          'Open Sources & jobs, choose an eligible source, and preview exactly what will be downloaded.'));
        return;
      }
      function coverageRow(s, includeBasis) {
        return el('tr', {},
          el('td', {}, el('b', {}, s.symbol)),
          el('td', { class: 'muted' }, s.underlyingBars ? (s.underlyingFrom + ' → ' + s.underlyingTo) : '—'),
          el('td', {}, s.underlyingBars
            ? el('span', { class: 'badge ' + (s.underlyingObserved ? 'badge-ok' : 'badge-dim') }, s.underlyingObserved ? 'observed' : 'demo')
            : el('span', { class: 'muted' }, '—')),
          el('td', { class: 'muted' }, String(s.underlyingBars || 0)),
          includeBasis ? el('td', { class: 'muted small' }, (s.underlyingBasis || '—') + (s.underlyingSources ? ' · ' + s.underlyingSources : '')) : null,
          el('td', { class: 'muted' }, s.optionRows ? (s.optionDays + ' days / ' + s.optionRows + ' rows') : '—'));
      }
      var rows = syms.slice(0, level === 'beginner' ? 12 : syms.length).map(function (s) {
        return coverageRow(s, level === 'expert');
      });
      coverageCard.appendChild(table(level === 'expert'
        ? ['Symbol', 'Underlying range', 'Evidence', 'Bars', 'Basis & sources', 'Options']
        : ['Symbol', 'Underlying range', 'Evidence', 'Bars', 'Options'], rows));
      if (level === 'beginner') {
        coverageCard.appendChild(UI.expandable('Review basis and sources for all ' + syms.length + ' symbols', function () {
          return table(['Symbol', 'Underlying range', 'Evidence', 'Bars', 'Basis & sources', 'Options'],
            syms.map(function (s) { return coverageRow(s, true); }));
        }, { stateKey: 'data-symbol-coverage-sources' }));
      }
    })();

    // --- Daily-history acquisition: one guided workflow over every lawful connector. ---
    (async function fillHistorySync() {
      if (!syncCard.isConnected) return;
      var access = await dataAccessP;
      if (!access || !access.admin) {
        if (!App.alive(token) || !syncCard.isConnected) return;
        syncCard.innerHTML = '';
        syncCard.appendChild(UI.cardHeader('Get & maintain daily price history'));
        syncCard.appendChild(alertBox('caution', 'History management requires admin access', [
          'This changes the shared observed-data inventory and provider allowance. You can still inspect source eligibility, stored coverage, and completed jobs on this screen.'
        ]));
        return;
      }
      var doc;
      try { doc = await API.getFresh('/api/data/sync'); }
      catch (e) {
        if (!App.alive(token)) return;
        syncCard.innerHTML = ''; syncCard.appendChild(UI.cardHeader('Get & maintain daily price history'));
        syncCard.appendChild(alertBox('warn', 'History setup is unavailable: ' + e.message)); return;
      }
      if (!App.alive(token) || !syncCard.isConnected) return;
      var connectors = doc.connectors || [];
      function parseSymbolText(raw) {
        return String(raw || '').split(/[\s,]+/).map(function (s) { return s.trim().toUpperCase(); })
          .filter(function (s, i, all) { return /^[A-Z0-9.^_-]{1,20}$/.test(s) && all.indexOf(s) === i; }).slice(0, 120);
      }
      var automated = connectors.filter(function (c) { return c.automated; });
      var eligible = automated.filter(function (c) { return c.eligible; });
      var active = (App.state.universe && App.state.universe.active) || {};
      var defaultSymbols = (active.symbols || []).join(', ');
      var st = App.state.dataSyncForm || (App.state.dataSyncForm = {
        scope: 'sector', symbols: defaultSymbols, source: doc.recommendedSource || '', years: 5,
        from: '', to: doc.latestCompletedSession || ''
      });
      if (!st.symbols) st.symbols = defaultSymbols;
      if (!st.source || !automated.some(function (c) { return c.key === st.source && c.eligible; })) {
        st.source = doc.recommendedSource && doc.recommendedSource !== 'none'
          ? doc.recommendedSource : (eligible[0] && eligible[0].key) || '';
      }
      var planDoc = null;
      function invalidatePlan() {
        planDoc = null;
        if (startBtn) startBtn.disabled = true;
        if (startReason) startReason.textContent = 'Inputs changed. Preview the updated request before starting.';
        if (planSlot) {
          planSlot.innerHTML = '';
          planSlot.appendChild(el('p', { class: 'muted small' }, 'Inputs changed. Preview the updated request before starting.'));
        }
      }
      syncCard.innerHTML = '';
      syncCard.appendChild(UI.cardHeader('Get & maintain daily price history',
        el('span', { class: 'badge badge-ok' }, 'ADDITIVE · RESUMABLE')));
      syncCard.appendChild(explain(level === 'beginner'
        ? 'Choose stocks and a permitted source. StrikeBench checks what dates are already stored, shows the request cost, and fetches only missing daily history. Successful observed history is saved locally and reused after restart; nothing generated can enter Observed data.'
        : 'Missing-range planning is per symbol over observed trading sessions, with a short revision overlap. Successful on-demand observed reads write through to the same local store. Source identity, adjusted/raw basis, request allowance, cursor, and rejected-row quarantine survive restarts.'));

      var sourceGrid = el('div', { class: 'data-source-picker', role: 'group', 'aria-label': 'Automated daily price source' });
      var sourceDetailKey = App.state.dataSourceDetails || '';
      var sourceDetail = el('div', { class: 'data-source-detail', id: 'data-source-detail', 'aria-live': 'polite' });
      automated.forEach(function (c) {
        var chosen = c.key === st.source;
        var showing = c.key === sourceDetailKey;
        sourceGrid.appendChild(el('button', { type: 'button', class: 'data-source-choice' + (chosen ? ' active' : ''),
          'aria-pressed': chosen ? 'true' : 'false', 'aria-expanded': showing ? 'true' : 'false',
          'data-source-key': c.key,
          onclick: function () {
            App.state.dataSourceDetails = showing ? '' : c.key;
            if (c.eligible) st.source = c.key;
            App.state.dataSyncForm = st;
            fillHistorySync();
          } },
          el('span', { class: 'chip-row' },
            el('b', {}, c.name),
            el('span', { class: 'badge ' + (c.eligible ? 'badge-ok' : 'badge-dim') }, c.eligible ? 'READY' : 'SETUP NEEDED')),
          el('span', { class: 'small data-source-covers' }, c.covers),
          el('span', { class: 'muted small' }, c.access + ' · ' + c.cadence),
          el('span', { class: 'data-source-affordance small' }, showing ? 'Hide details ↑'
            : (c.eligible ? 'Select & review →' : 'Review setup →'))));
      });
      if (!automated.length) sourceGrid.appendChild(el('p', { class: 'muted' }, 'No automated candle connectors are present in this build.'));
      var detailConnector = automated.find(function (c) { return c.key === sourceDetailKey; });
      if (detailConnector) {
        sourceDetail.appendChild(el('div', { class: 'data-source-detail-head' },
          el('b', {}, detailConnector.name),
          el('span', { class: 'badge ' + (detailConnector.eligible ? 'badge-ok' : 'badge-dim') },
            detailConnector.eligible ? 'READY' : 'SETUP NEEDED')));
        sourceDetail.appendChild(el('div', { class: 'data-source-detail-grid' },
          el('div', {}, el('span', { class: 'muted small' }, 'Permission & use'),
            el('p', {}, detailConnector.rights)),
          el('div', {}, el('span', { class: 'muted small' }, 'History & price basis'),
            el('p', {}, detailConnector.history + ' · ' + detailConnector.adjustment)),
          el('div', {}, el('span', { class: 'muted small' }, 'Setup'),
            el('p', {}, detailConnector.setup)),
          el('div', {}, el('span', { class: 'muted small' }, 'Request allowance'),
            el('p', {}, detailConnector.dailyLimit > 0
              ? detailConnector.remainingToday + ' of ' + detailConnector.dailyLimit + ' requests left today'
              : 'Usage follows your provider plan'))));
        if (detailConnector.note) sourceDetail.appendChild(el('p', { class: 'muted small data-source-note' }, detailConnector.note));
      }
      syncCard.appendChild(el('section', { class: 'data-acquire-section' },
        el('h3', {}, '1. Choose an automated source'), sourceGrid,
        detailConnector ? sourceDetail : null));
      if (!eligible.length) {
        var sourceBridge = alertBox('caution', 'No automated daily-price source is active. Quotes and option chains can still work, but charts, HV, realized-volatility EV, and favorable observed verdicts need daily history.', [
          'Authorized personal/local use: set YAHOO_ENABLED=true and YAHOO_AUTOMATION_PERMISSION_CONFIRMED=true, then restart. Do not enable this on a hosted service without the required rights.',
          'Official path: configure a Polygon or Alpha Vantage key under the terms of your plan.',
          'No automation: import a price-history CSV you already have permission to use.'
        ]);
        sourceBridge.appendChild(el('div', { class: 'btn-row' }, el('button', {
          type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
            var target = document.getElementById('data-csv-import');
            if (target) { target.open = true; target.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
          }
        }, 'Use my CSV')));
        syncCard.appendChild(sourceBridge);
      }

      var sectorBtn = el('button', { type: 'button', class: st.scope === 'sector' ? 'active' : '',
        'aria-pressed': String(st.scope === 'sector'),
        onclick: function () { st.scope = 'sector'; st.symbols = defaultSymbols; symbolsInput.value = st.symbols; syncScope(); invalidatePlan(); } },
        'Current sector · ' + ((active.symbols || []).length || 0));
      var customBtn = el('button', { type: 'button', class: st.scope === 'custom' ? 'active' : '',
        'aria-pressed': String(st.scope === 'custom'),
        onclick: function () { st.scope = 'custom'; syncScope(); invalidatePlan(); symbolsInput.focus(); } }, 'Choose symbols');
      var symbolsInput = el('input', { type: 'text', id: 'data-sync-symbols', value: st.symbols,
        placeholder: 'AAPL, MSFT, SPY', list: 'universe-symbols', oninput: function () { st.symbols = this.value; st.scope = 'custom'; syncScope(); invalidatePlan(); } });
      function syncScope() {
        sectorBtn.classList.toggle('active', st.scope === 'sector');
        customBtn.classList.toggle('active', st.scope === 'custom');
        sectorBtn.setAttribute('aria-pressed', String(st.scope === 'sector'));
        customBtn.setAttribute('aria-pressed', String(st.scope === 'custom'));
        symbolsInput.disabled = st.scope === 'sector';
        App.state.dataSyncForm = st;
      }
      syncScope();
      var years = el('select', { id: 'data-sync-years', onchange: function () { st.years = parseInt(this.value, 10); App.state.dataSyncForm = st; invalidatePlan(); } },
        [1, 2, 5, 10, 20].map(function (y) { return el('option', { value: y }, y + (y === 1 ? ' year' : ' years')); }));
      years.value = String(Number(st.years) || 5);
      var fromInput = el('input', { type: 'date', id: 'data-sync-from', value: st.from || '',
        onchange: function () { st.from = this.value; App.state.dataSyncForm = st; invalidatePlan(); } });
      var toInput = el('input', { type: 'date', id: 'data-sync-to', value: st.to || doc.latestCompletedSession || '',
        onchange: function () { st.to = this.value; App.state.dataSyncForm = st; invalidatePlan(); } });
      var scopeGrid = el('div', { class: 'form-grid data-acquire-grid' },
        el('div', { class: 'field data-symbol-scope' }, el('label', { for: 'data-sync-symbols' }, 'Stocks'),
          el('div', { class: 'level-switch data-scope-switch', role: 'group', 'aria-label': 'Symbol scope' },
            sectorBtn, customBtn), symbolsInput),
        UI.field('History window', years),
        level === 'expert' ? UI.field('Exact start (optional)', fromInput) : null,
        level === 'expert' ? UI.field('Through completed session', toInput) : null);
      syncCard.appendChild(el('section', { class: 'data-acquire-section' }, el('h3', {}, '2. Choose coverage'), scopeGrid));
      if (level === 'beginner') syncCard.appendChild(UI.expandable('Choose exact dates (optional)', function () {
        return el('div', { class: 'form-grid grid-2' },
          UI.field('Start date', fromInput),
          UI.field('Through completed session', toInput));
      }));

      var planSlot = el('div', { id: 'data-sync-plan', 'aria-live': 'polite' });
      var startReason = el('div', { class: 'muted small', id: 'data-sync-start-reason' },
        'Preview the selected source, symbols, and date range before starting an update.');
      var startBtn = el('button', { class: 'btn', id: 'data-sync-start', disabled: true,
        'aria-describedby': 'data-sync-start-reason', onclick: async function () {
        if (!planDoc) return;
        startBtn.disabled = true;
        try {
          await startJob('sync_underlying', { source: planDoc.source.key,
            symbols: (planDoc.plans || []).map(function (p) { return p.symbol; }),
            from: String(planDoc.effectiveFrom), to: String(planDoc.to) });
          planSlot.innerHTML = '';
          planSlot.appendChild(alertBox('ok', 'History update started. Progress is shown in Jobs below; each completed symbol becomes available to Research immediately.'));
          startReason.textContent = 'Update running. Progress and any per-symbol failures appear in Jobs below.';
        } catch (e) {
          planSlot.appendChild(alertBox('warn', e.message));
          startReason.textContent = 'The update did not start. Review the message, then preview again if inputs changed.';
          startBtn.disabled = false;
        }
      } }, 'Start update');
      var previewBtn = el('button', { class: 'btn', id: 'data-sync-preview', disabled: !st.source,
        onclick: async function () {
          previewBtn.disabled = true; planSlot.innerHTML = ''; planSlot.appendChild(UI.spinner('Checking stored dates and request cost…'));
          try {
            planDoc = await API.post('/api/data/sync/plan', { source: st.source,
              symbols: parseSymbolText(st.symbols), years: Number(st.years) || 5,
              from: level === 'expert' && st.from ? st.from : null, to: st.to || null });
            planSlot.innerHTML = '';
            planSlot.appendChild(el('div', { class: 'data-plan-summary' },
              el('div', { class: 'chip-row' },
                chip('Symbols', String(planDoc.symbols)), chip('Missing sessions', String(planDoc.missingSessions)),
                chip('Estimated requests', String(planDoc.estimatedRequests), 'Requests are planned before work starts and still pass through the durable provider allowance.'),
                chip('Range', String(planDoc.effectiveFrom) + ' → ' + String(planDoc.to))),
              planDoc.limitation ? alertBox('caution', planDoc.limitation) : null,
              planDoc.missingSessions === 0
                ? alertBox('ok', 'This range is already covered. No provider request is needed.')
                : el('p', { class: 'muted small' }, 'Existing rows are kept. A three-session overlap lets adjusted providers revise recent split/dividend history safely.')));
            var remaining = planDoc.source.dailyLimit > 0 ? planDoc.source.remainingToday : Number.MAX_SAFE_INTEGER;
            startBtn.disabled = planDoc.missingSessions === 0 || planDoc.estimatedRequests > remaining;
            if (planDoc.missingSessions === 0) {
              startReason.textContent = 'Start update is unavailable because this history range is already covered.';
            } else if (planDoc.estimatedRequests > remaining) {
              startReason.textContent = 'Start update is unavailable until the request allowance resets or the scope is narrowed.';
              planSlot.appendChild(alertBox('warn',
                'This plan needs ' + planDoc.estimatedRequests + ' requests but only ' + remaining + ' remain today. Narrow the scope or resume after the allowance resets.'));
            } else {
              startReason.textContent = 'Ready to update ' + planDoc.symbols + ' symbol'
                + (planDoc.symbols === 1 ? '' : 's') + ' across ' + planDoc.missingSessions + ' missing sessions.';
            }
          } catch (e) {
            planDoc = null;
            startBtn.disabled = true;
            startReason.textContent = 'Start update is unavailable because the preview did not complete.';
            planSlot.innerHTML = '';
            planSlot.appendChild(alertBox('warn', e.message));
          }
          previewBtn.disabled = !st.source;
        } }, 'Preview update');
      syncCard.appendChild(el('section', { class: 'data-acquire-section' },
        el('h3', {}, '3. Preview, then run'), el('div', { class: 'btn-row' }, previewBtn, startBtn),
        startReason, planSlot));

      // User-owned CSV is a peer capability, not an inferior fallback. It is often the safest
      // route for broker/Yahoo exports because no automated collection occurs.
      var fileIn = el('input', { type: 'file', id: 'data-csv-file', accept: '.csv,text/csv' });
      var csvSymbol = el('input', { type: 'text', id: 'data-csv-symbol', value: App.context.symbol(),
        placeholder: 'Only needed if file has no Symbol column', list: 'universe-symbols' });
      var csvBasis = el('select', { id: 'data-csv-basis' },
        el('option', { value: 'AUTO' }, 'Auto-detect adjusted close'),
        el('option', { value: 'RAW' }, 'Raw prices'),
        el('option', { value: 'ADJUSTED' }, 'File is adjusted'));
      var csvLabel = el('input', { type: 'text', id: 'data-csv-label', placeholder: 'Broker or source name' });
      var csvResult = el('div', { id: 'data-csv-result', 'aria-live': 'polite' });
      var uploadBtn = el('button', { class: 'btn', id: 'data-csv-upload', onclick: async function () {
        if (!fileIn.files || !fileIn.files[0]) { csvResult.innerHTML = ''; csvResult.appendChild(alertBox('warn', 'Choose a CSV file first.')); return; }
        uploadBtn.disabled = true; csvResult.innerHTML = ''; csvResult.appendChild(UI.spinner('Validating and importing…'));
        try {
          var fd = new FormData(); fd.append('file', fileIn.files[0]); fd.append('symbol', csvSymbol.value || '');
          fd.append('basis', csvBasis.value); fd.append('sourceLabel', csvLabel.value || fileIn.files[0].name);
          var r = await API.upload('/api/data/import/underlying', fd);
          csvResult.innerHTML = '';
          csvResult.appendChild(alertBox(r.rowsWritten ? 'ok' : 'caution', r.note));
          csvResult.appendChild(el('div', { class: 'chip-row' },
            chip('Imported', r.rowsWritten + ' rows'), chip('Series basis', r.barBasis,
              r.barBasis === 'CLOSE_ONLY' ? 'Observed closes are present; intraday high/low are not observed.' : 'Observed OHLC fields are present.'),
            r.quarantined ? chip('Quarantined', r.quarantined + ' rows') : null));
          if (r.rowsWritten && !(App.config && App.config.fixturesOnly)) csvResult.appendChild(
            el('button', { class: 'btn btn-sm', onclick: function () {
              App.navigate('#/research/' + encodeURIComponent((csvSymbol.value || App.context.symbol('SPY')).toUpperCase()));
            } }, 'Open Research'));
          loadJobs();
        } catch (e) { csvResult.innerHTML = ''; csvResult.appendChild(alertBox('warn', e.message)); }
        uploadBtn.disabled = false;
      } }, 'Validate & import');
      var importBody = el('div', { class: 'data-import-grid' },
        UI.field('CSV file', fileIn),
        UI.field('Symbol fallback', csvSymbol),
        UI.field(['Price basis', UI.info('adjustedprices')], csvBasis),
        UI.field('Source label', csvLabel), uploadBtn, csvResult);
      var csvImport = UI.expandable('Import a CSV you already own', function () { return importBody; },
        { open: !eligible.length });
      csvImport.id = 'data-csv-import';
      syncCard.appendChild(csvImport);

      var schedule = doc.schedule || {};
      var scheduleToggle = el('input', { type: 'checkbox', id: 'data-sync-schedule',
        disabled: !st.source || App.config && App.config.fixturesOnly });
      scheduleToggle.checked = !!schedule.enabled;
      var scheduleStatus = el('div', { class: 'muted small', id: 'data-sync-schedule-status' },
        schedule.lastRunDate ? ('Last scheduled session: ' + schedule.lastRunDate + ' · ' + (schedule.lastStatus || ''))
          : 'No automatic update has run yet.');
      var scheduleBody = el('div', { class: 'data-schedule' },
        el('label', { class: 'toggle-row' }, scheduleToggle,
          el('span', {}, el('b', {}, 'Keep this history current after each market close'),
            el('span', { class: 'muted small' }, ' Once per completed session, after a 20-minute grace period — never hourly.'))),
        el('div', { class: 'btn-row' }, el('button', { class: 'btn btn-sm', id: 'data-sync-schedule-save',
          disabled: !st.source || App.config && App.config.fixturesOnly, onclick: async function () {
          try {
            var saved = await API.put('/api/data/sync/schedule', { enabled: scheduleToggle.checked,
              source: st.source, symbols: parseSymbolText(st.symbols), years: Number(st.years) || 5 });
            scheduleStatus.textContent = saved.enabled ? 'Automatic daily maintenance is on.' : 'Automatic maintenance is off.';
          } catch (e) { scheduleStatus.textContent = e.message; scheduleToggle.checked = false; }
        } }, 'Save maintenance setting')), scheduleStatus);
      syncCard.appendChild(level === 'expert' ? el('section', { class: 'data-acquire-section' },
        el('h3', {}, 'Maintenance schedule'), scheduleBody)
        : UI.expandable('Keep it current automatically', function () { return scheduleBody; }));

      var cursorRows = (doc.cursors || []).map(function (c) { return el('tr', {},
        el('td', {}, el('b', {}, c.symbol)), el('td', {}, c.source),
        el('td', {}, el('span', { class: 'badge ' + (c.status === 'COMPLETE' ? 'badge-ok'
          : c.status === 'FAILED' ? 'badge-danger' : c.status === 'RUNNING' ? 'badge-caution' : 'badge-dim') }, c.status)),
        el('td', { class: 'muted' }, c.lastSuccessDate || '—'), el('td', { class: 'muted' }, String(c.rowsWritten || 0)),
        el('td', { class: 'muted small' }, c.note || '')); });
      var diagnostics = el('div', {},
        cursorRows.length ? table(['Symbol', 'Source', 'State', 'Through', 'Rows', 'Note'], cursorRows)
          : el('p', { class: 'muted' }, 'No sync cursors yet.'),
        doc.quarantine && doc.quarantine.total ? alertBox('caution', doc.quarantine.total
          + ' rejected row(s) are quarantined and are not used by analysis.') : null);
      syncCard.appendChild(level === 'expert' ? el('section', { class: 'data-acquire-section' },
        el('h3', {}, 'Cursors & rejected rows', UI.info('synccursor')), diagnostics)
        : UI.expandable('Update history & rejected rows', function () { return diagnostics; }));
    })();

    // --- Sources ---
    (async function fillSources() {
      if (!sourcesCard.isConnected) return;
      var data;
      try { data = await API.get('/api/data/sources'); } catch (e) {
        if (!App.alive(token)) return;
        sourcesCard.innerHTML = ''; sourcesCard.appendChild(UI.cardHeader('Data sources'));
        sourcesCard.appendChild(alertBox('warn', 'Sources unavailable.')); return;
      }
      if (!App.alive(token)) return;
      sourcesCard.innerHTML = '';
      sourcesCard.appendChild(UI.cardHeader('Other market-data feeds'));
      sourcesCard.appendChild(el('p', { class: 'muted small' },
        'Daily-price setup lives in the history workflow above. These distinct feeds supply current options, filings, headlines, rates, or licensed option history.'));
      var support = el('div', { class: 'data-feed-list' });
      (data.feeds || []).forEach(function (s) {
        support.appendChild(el('div', { class: 'data-feed-row' + (s.enabled ? ' on' : '') },
          el('div', { class: 'data-feed-heading' },
            el('span', { class: 'badge ' + (s.enabled ? 'badge-ok' : 'badge-dim') }, s.enabled ? 'ON' : 'OFF'),
            el('b', {}, s.name),
            el('span', { class: 'muted small data-feed-covers' }, s.covers)),
          el('div', { class: 'muted small' }, s.license),
          el('div', { class: 'small' }, s.hint)));
      });
      if (!support.childElementCount) support.appendChild(el('p', { class: 'muted' }, 'No supporting feeds are registered.'));
      sourcesCard.appendChild(UI.expandable('Review ' + (data.feeds || []).length + ' feed details', function () { return support; },
        { stateKey: 'data-feed-details' }));
    })();

    // --- Provider health (the old status view, kept as detail) ---
    (async function fillHealth() {
      if (!healthCard.isConnected) return;
      var s;
      try { s = await API.get('/api/status'); } catch (e) { return; }
      if (!App.alive(token)) return;
      function domainLabel(domain) {
        var words = String(domain || '').toLowerCase().replace(/_/g, ' ');
        return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Unknown';
      }
      healthCard.innerHTML = '';
      var body = el('div', {});
      var grid = el('div', { class: 'grid grid-2' });
      Object.keys(s.domains || {}).forEach(function (domain) {
        var items = s.domains[domain];
        var card = el('div', { class: 'card', style: 'margin-bottom:0' }, UI.cardHeader(domainLabel(domain)));
        if (!items.length) card.appendChild(el('p', { class: 'muted' }, 'No providers registered.'));
        items.forEach(function (p) {
          card.appendChild(el('div', { class: 'status-item' },
            el('span', { class: 'badge ' + (STATE_BADGE[p.state] || 'badge-dim') }, p.state),
            el('b', {}, p.provider), el('span', { class: 'spacer' }), el('span', { class: 'muted' }, p.detail || '')));
        });
        grid.appendChild(card);
      });
      body.appendChild(grid);
      healthCard.appendChild(UI.cardHeader('Provider health'));
      // OVERVIEW IS A DASHBOARD (review visual #6): a one-line per-domain summary is always
      // visible; the full per-source grid expands on demand at BOTH levels — the expert
      // full-grid default alone pushed Overview past a 720px viewport.
      var sumRow = el('div', { class: 'chip-row' });
      Object.keys(s.domains || {}).forEach(function (domain) {
        var items = s.domains[domain] || [];
        // The chip TEXT is the SAME aggregated worst state as its color (review: a red chip
        // literally said 'OK' whenever the failing provider wasn't listed first).
        var worstState = items.reduce(function (acc, p2) {
          var rank = { ERROR: 4, COOLDOWN: 4, EMPTY: 3, STALE: 3, UNKNOWN: 2, UNCONFIGURED: 1, OK: 0 };
          return (rank[p2.state] || 0) > (rank[acc] || 0) ? p2.state : acc;
        }, items.length ? items[0].state : 'NONE');
        var worst = worstState === 'COOLDOWN' ? 'badge-danger'
          : (STATE_BADGE[worstState] || 'badge-dim');
        var okCount = items.filter(function (p2) { return p2.state === 'OK'; }).length;
        sumRow.appendChild(el('span', { class: 'chip', title: okCount + ' of ' + items.length
            + ' sources OK \u2014 the badge shows the WORST source state in this domain' },
          el('span', { class: 'chip-label' }, domainLabel(domain)),
          el('b', {}, el('span', { class: 'badge ' + worst }, items.length ? worstState : 'NONE'))));
      });
      healthCard.appendChild(sumRow);
      healthCard.appendChild(el('p', { class: 'muted small data-health-basis' },
        'Latest source requests; stored history is tracked separately.'));
      syncOverviewToggle(healthCard, 'health');
      dcDetailBuilders.health = function () {
        var detail = el('div', {},
          el('p', { class: 'muted small' },
            'Health is the latest source request. Stored historical coverage is separate and may remain available after the market closes or a source is temporarily empty.'),
          body);
        // Keep the operational evidence reachable at both levels; Beginner gets a
        // plain-language collapsed entry instead of losing the capability.
        detail.appendChild(UI.expandable(Learn.currentLevel() === 'beginner'
          ? 'Technical performance (typical and slower requests)'
          : 'API latency (p50/p95 by route class)', function () {
            var holder = el('div', {}, UI.spinner('Reading metrics\u2026'));
            API.getFresh('/api/metrics').then(function (m) {
              holder.innerHTML = '';
              var lat = (m && m.latency) || {};
              var keys = Object.keys(lat);
              if (!keys.length) { holder.appendChild(el('p', { class: 'muted' }, 'No samples yet.')); return; }
              holder.appendChild(table(['Route class', 'Samples', 'p50', 'p95', 'Max'], keys.sort().map(function (k) {
                var v = lat[k];
                var ms = function (x) { return (x / 1000).toFixed(1) + ' ms'; };
                return el('tr', {},
                  el('td', {}, el('b', {}, k)),
                  el('td', { class: 'muted' }, String(v.samples)),
                  el('td', {}, ms(v.p50Micros || 0)),
                  el('td', {}, ms(v.p95Micros || 0)),
                  el('td', { class: 'muted' }, ms(v.maxMicros || 0)));
              })));
              holder.appendChild(el('p', { class: 'muted small' },
                'Since server start. Cold first-hits and warm cache-hits are mixed \u2014 read p95 as the honest worst-typical.'));
            }).catch(function (e2) {
              holder.innerHTML = '';
              holder.appendChild(el('p', { class: 'muted' }, 'Metrics unavailable: ' + e2.message));
            });
            return holder;
          }));
        return detail;
      };
      if (document.getElementById('dc-detail')?.getAttribute('data-detail') === 'health') {
        openOverviewDetail('health', healthCard, true);
      }
    })();

    // --- Reset (danger; admin-gated) ---
    // The Administration tab resolves its own gate: renderReset used to ride on fillEngine,
    // which only runs when the Overview tab is mounted (route-backed tabs split them).
    (async function fillAdmin() {
      if (!resetCard.isConnected) return;
      try {
        var ov = await API.get('/api/data/overview');
        if (!App.alive(token)) return;
        renderReset(!!ov.admin);
      } catch (e) { if (App.alive(token)) renderReset(false); }
    })();
    function renderReset(admin) {
      resetCard.innerHTML = '';
      resetCard.appendChild(UI.cardHeader('Reset data'));
      if (!admin) {
        resetCard.appendChild(alertBox('warn', 'Resetting data requires admin access. On a public deployment, sign in as an admin (AUTH_ADMIN_EMAILS) or set ADMIN_TOKEN; locally it is enabled by default.'));
        return;
      }
      resetCard.appendChild(explain('Wipe stored data back toward a fresh install. This never touches real money, but it cannot be undone. History and jobs are cleared per the tier you pick.'));
      var TIERS = [
        { key: 'MARKET_DATA', label: 'Market history only', blurb: 'Clears stored price/option history + data jobs. Keeps your practice account and research.' },
        { key: 'RESEARCH', label: 'Research & backtests', blurb: 'Clears saved evaluations, recommendations, backtests, and notes.' },
        { key: 'PAPER', label: 'Practice account & trades', blurb: 'Voids trades and positions and re-funds a fresh account.' },
        { key: 'EVERYTHING', label: 'Everything (fresh start)', blurb: 'Wipes ALL stored data and re-seeds a brand-new funded account.' }
      ];
      var choices = TIERS;
      var sel = el('select', { id: 'dc-reset-tier' }, choices.map(function (t) { return el('option', { value: t.key }, t.label); }));
      var blurb = el('div', { class: 'muted small', id: 'dc-reset-blurb', style: 'margin-top:4px' });
      function syncBlurb() { var t = choices.find(function (x) { return x.key === sel.value; }); blurb.textContent = t ? t.blurb : ''; }
      sel.addEventListener('change', syncBlurb); syncBlurb();
      resetCard.appendChild(el('div', { class: 'btn-row' },
        el('label', { class: 'muted', for: 'dc-reset-tier' }, 'What to clear '), sel,
        el('button', { class: 'btn btn-danger', id: 'dc-reset-btn', onclick: function () {
          var tier = sel.value;
          var conf = el('input', { type: 'text', id: 'dc-reset-confirm', placeholder: 'type RESET', style: 'max-width:140px' });
          UI.confirmModal('Reset ' + (choices.find(function (x) { return x.key === tier; }) || {}).label + '?',
            el('div', {},
              el('p', {}, 'This permanently clears the data for this tier. Type ', el('b', {}, 'RESET'), ' to confirm.'),
              el('div', { class: 'btn-row' }, conf)),
            'Reset', async function () {
              if ((conf.value || '').trim().toUpperCase() !== 'RESET') throw new Error('Type RESET to confirm.');
              await API.post('/api/data/reset', { tier: tier, confirm: true });
              App.render();
            }, true);
        } }, 'Reset…')));
      resetCard.appendChild(blurb);
    }

    loadJobs();
  }


  window.ViewData = Object.freeze({ data: data });
})();
