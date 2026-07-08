/* StrikeBench app shell: hash router, header state, boot. Attached to window (jsdom-friendly). */
(function () {
  'use strict';

  var App = {
    state: { ticket: null, lastRecommendSymbol: null },

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
      // The readiness flag drops SYNCHRONOUSLY on render start — the crossfade below is
      // async, and anything watching data-ready (tests, tooling) must never catch the
      // outgoing screen still claiming to be ready for the new route.
      root.setAttribute('data-ready', 'false');

      var hash = window.location.hash || '#/home';
      var parts = hash.replace(/^#\//, '').split('/').filter(function (p) { return p.length; });
      var route = parts[0] || 'home';
      var params = parts.slice(1);
      // IA merges keep old URLs alive: the tour lives inside Home, Account inside Portfolio,
      // and Ideas/Ticket/Backtest are stages of the Trade workbench
      if (route === 'welcome') { route = 'home'; params = ['tour']; }
      if (route === 'account') { route = 'portfolio'; params = ['account']; }
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
        a.classList.toggle('active', a.getAttribute('data-route') === route
          || (route === 'trade' && a.getAttribute('data-route') === 'portfolio'));
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
      // The ticker is CONTEXT, not chrome: it accompanies market-facing screens only
      var tape = document.getElementById('tape');
      if (tape) {
        var showTape = route === 'home' || route === 'research';
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
    if (storedRisk) risk.value = storedRisk;
    risk.addEventListener('change', function () {
      try { window.localStorage.setItem('strikebench.riskMode', risk.value); } catch (e) { /* ignore */ }
    });
  }

  function applyLevelSideEffects() {
    // Beginners don't get the aggressive risk mode
    var risk = document.getElementById('risk-mode');
    var aggressive = risk.querySelector('option[value="aggressive"]');
    var beginner = document.body.classList.contains('lvl-beginner');
    aggressive.hidden = beginner;
    aggressive.disabled = beginner;
    if (beginner && risk.value === 'aggressive') risk.value = 'conservative';
  }

  async function boot() {
    initHeader();
    applyLevelSideEffects();
    initTheme();
    initSearch();
    try {
      var cfg = await API.get('/api/config');
      if (cfg && cfg.disclaimer) document.getElementById('disclaimer').textContent = cfg.disclaimer;
      if (cfg && cfg.brand && cfg.brand.name) applyBrand(cfg.brand);
    } catch (e) { /* footer keeps its static text */ }
    checkServerHealth();
    setInterval(checkServerHealth, 5 * 60 * 1000);
    refreshUniverse();
    setInterval(refreshTape, 45 * 1000);
    window.addEventListener('hashchange', App.render);
    App.render();
  }

  // ---- Universe: one global setting feeding the tape, the scout, and symbol suggestions ----

  App.refreshUniverse = refreshUniverse;
  App.refreshTape = refreshTape; // exposed so tests can pin the no-rebuild behavior
  async function refreshUniverse() {
    try {
      var u = await API.get('/api/universe');
      App.state.universe = u;
      var dl = document.getElementById('universe-symbols');
      if (dl) {
        dl.innerHTML = '';
        (u.active.symbols || []).forEach(function (s) {
          dl.appendChild(UI.el('option', { value: s }));
        });
      }
      await refreshTape();
    } catch (e) { /* the tape is decorative; screens still work */ }
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
      var symbolsKey = quotes.map(function (q) { return q.symbol; }).join(',');
      if (strip.getAttribute('data-symbols') === symbolsKey && strip.children.length) {
        quotes.forEach(function (q) {
          var last = parseFloat(q.last), prev = parseFloat(q.prevClose);
          var pct = prev ? (last - prev) / prev * 100 : 0;
          strip.querySelectorAll('.tape-item[data-sym="' + q.symbol + '"]').forEach(function (item) {
            item.children[1].textContent = last.toFixed(2);
            var d = item.children[2];
            d.className = pct >= 0 ? 'gain' : 'loss';
            d.textContent = (pct >= 0 ? '\u25B2' : '\u25BC') + Math.abs(pct).toFixed(2) + '%';
          });
        });
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
      strip.style.animation = '';
      strip.style.animationDuration = Math.max(20, Math.round(halfW / 55)) + 's'; // ~55 px/s
    } catch (e) { tape.hidden = true; }
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
    if (el) el.textContent = brand.name;
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
