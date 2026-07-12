/* StrikeBench views: one render function per screen. Attached to window, jsdom-friendly. */
(function () {
  'use strict';

  var el = UI.el, fmtMoney = UI.fmtMoney, pnlSpan = UI.pnlSpan, fmtPct = UI.fmtPct,
      fmtNum = UI.fmtNum, badge = UI.freshnessBadge, explain = UI.explain,
      alertBox = UI.alertBox, stat = UI.stat, table = UI.table, chip = UI.chip;

  var CORE_SYMBOLS = ['AAPL', 'SPY', 'QQQ', 'TSLA'];

  function positiveInteger(value, label, max) {
    var n = Number(value);
    if (!Number.isInteger(n) || n < 1 || (max && n > max)) {
      throw new Error((label || 'Value') + ' must be a whole number from 1' + (max ? ' to ' + max : '') + '.');
    }
    return n;
  }

  var icon = UI.icon;

  /**
   * The opening page: what this is, who it's for, and three one-click ways in — shown to
   * fresh accounts (and always at #/welcome). The competition leads with a landing page;
   * ours leads with a landing page wired to LIVE local data (the tape above it already ticks).
   */
  async function welcome(root) {
    var stepsAnchorId = 'how-it-works';
    var outer = root;
    root = el('div', { class: 'welcome-page' });
    outer.appendChild(root);

    // ---- Top fold: the pitch on the left, LIVE proof on the right — promise, evidence,
    // and both primary actions land above the fold on a desktop ----
    var liveHost = el('div', { class: 'showcase-frame', id: 'welcome-live' },
      el('div', { class: 'showcase-loading' }, UI.spinner('Asking the engine for a live example\u2026')));
    var deco = (function () {
      var svgNS = 'http://www.w3.org/2000/svg';
      var d = document.createElementNS(svgNS, 'svg');
      d.setAttribute('class', 'hero-deco');
      d.setAttribute('viewBox', '0 0 340 150');
      d.setAttribute('aria-hidden', 'true');
      var zero = document.createElementNS(svgNS, 'path');
      zero.setAttribute('class', 'deco-zero');
      zero.setAttribute('d', 'M0 96 H340');
      var tent = document.createElementNS(svgNS, 'path');
      tent.setAttribute('d', 'M0 128 H70 L128 34 H208 L266 128 H340'); // an iron condor, quietly
      d.appendChild(zero); d.appendChild(tent);
      return d;
    })();
    root.appendChild(el('div', { class: 'welcome-top' },
      heroBlock('welcome', {
        id: 'welcome-hero',
        deco: deco,
        top: el('div', { class: 'hero-brandline' }, UI.brandMark(30),
          el('span', { class: 'eyebrow' }, 'PAPER TRADING \u00B7 LOCAL-FIRST \u00B7 FREE')),
        title: el('h1', { class: 'hero-title' }, 'Learn options by ', el('span', { class: 'grad' }, 'doing'), ',', el('br', {}),
          'with honest numbers.'),
        sub: el('p', { class: 'hero-sub' },
          'Screen strategies against your goal, practice with \$100,000 in paper money, and verify honestly.'),
        ctas: [
          el('button', {
            class: 'btn btn-lg', onclick: function () {
              try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
              App.navigate('#/recommend/manual');
            }
          }, 'Find my first idea'),
          el('button', {
            class: 'btn btn-lg btn-ghost', onclick: function () {
              var t = document.getElementById(stepsAnchorId);
              if (t) t.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
          }, 'See how it works')],
        extras: el('div', { class: 'trust-row' },
          ['No signup', 'No cloud', 'Uses bid/ask, not midpoint', 'Undefined risk blocked']
            .map(function (t) { return el('span', {}, t); }))
      }),
      el('section', { class: 'welcome-section welcome-live-col' },
        el('div', { class: 'live-head' },
          el('div', { class: 'eyebrow' }, 'FROM THE ENGINE'),
          el('button', { class: 'btn-link', id: 'welcome-skip', onclick: function () {
            try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
            App.navigate('#/home');
          } }, 'Skip \u2192 dashboard')),
        el('div', { class: 'showcase' }, liveHost,
          el('p', { class: 'showcase-caption', id: 'welcome-proof-caption' }, welcomeProofCaption())))));
    function welcomeProofCaption() {
      if (App.config && App.config.scenarioMode) return 'A screened scenario result \u2014 modeled, not observed.';
      if (App.state.world === 'demo') return 'A screened teaching idea \u2014 generated from fabricated Demo data.';
      if (App.state.world && App.state.world !== 'observed') return 'A screened simulated-market idea \u2014 generated, not observed.';
      return 'A screened idea from observed market inputs \u2014 generated this second.';
    }
    function welcomeProofReady(c) {
      return c && c.maxLossCents > 0 && c.maxProfitCents !== null
        && c.maxProfitCents !== undefined && typeof c.pop === 'number';
    }
    function welcomeProofChoice(candidates) {
      return (candidates || []).find(welcomeProofReady) || (candidates || [])[0];
    }
    (async function () {
      // LAST-KNOWN FIRST (review #15): the opening composition must never be half spinner on a
      // slow provider. Render the cached proof instantly, then refresh it live and re-store.
      // The proof cache carries its CONTEXT (review P2 #7): a candidate produced in a
      // simulated world, under a scenario dataset, or a week ago must not flash as
      // "LIVE FROM THE ENGINE" on an observed page.
      // BOOT-LANE RACE FIX (review P1 #7): wait for the boot world determination before
      // building the context — a reload inside a simulated world must not save a simulated
      // candidate under the observed cache key. Keyed by the immutable dataset ID, not name.
      var proofToken = App.navToken;
      if (App.worldReady) { try { await App.worldReady; } catch (e) { /* lane decided anyway */ } }
      if (!App.alive(proofToken)) return; // navigated away while the lane was deciding
      var proofCaption = document.getElementById('welcome-proof-caption');
      if (proofCaption) proofCaption.textContent = welcomeProofCaption();
      var proofCtx = {
        world: App.state.world || 'observed',
        scenario: (App.config && App.config.scenarioMode && App.config.activeDataset) || 'observed',
        riskMode: 'conservative'
      };
      var cached = null;
      try {
        var raw = JSON.parse(localStorage.getItem('strikebench.welcomeProof') || 'null');
        if (raw && welcomeProofReady(raw.candidate)
            && raw.world === proofCtx.world && raw.scenario === proofCtx.scenario
            && raw.riskMode === proofCtx.riskMode && raw.asOf && (Date.now() - raw.asOf) < 24 * 3600 * 1000) {
          cached = raw.candidate;
        }
      } catch (e) { /* fresh */ }
      if (cached) {
        liveHost.innerHTML = '';
        liveHost.appendChild(candidateCard(cached, false));
        liveHost.appendChild(el('p', { class: 'muted small', style: 'margin:4px 0 0' }, 'Refreshing with a live run\u2026'));
      }
      try {
        var refresh = API.post('/api/recommend', { symbol: 'AAPL', thesis: 'bullish', horizon: 'month', riskMode: 'conservative' });
        var r = await Promise.race([refresh, new Promise(function (_, reject) {
          setTimeout(function () { reject(new Error('The current market check is still running.')); }, 6000);
        })]);
        if (r.candidates && r.candidates.length) {
          var proofCandidate = welcomeProofChoice(r.candidates);
          liveHost.innerHTML = '';
          liveHost.appendChild(candidateCard(proofCandidate, false));
          try {
            localStorage.setItem('strikebench.welcomeProof', JSON.stringify(
              Object.assign({ candidate: proofCandidate, asOf: Date.now() }, proofCtx)));
          } catch (e) { /* private mode */ }
        } else if (!cached) {
          liveHost.innerHTML = '';
          liveHost.appendChild(el('div', { class: 'empty-state', id: 'welcome-proof-unavailable' },
            el('b', {}, 'No AAPL idea passes right now'),
            el('p', { class: 'muted small' },
              'That is a valid engine result, not a missing demo. Open Research to inspect the evidence or choose another stock.'),
            el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research/AAPL'); } }, 'Research AAPL')));
        }
      } catch (e) {
        if (!cached) {
          liveHost.innerHTML = '';
          liveHost.appendChild(el('div', { class: 'empty-state', id: 'welcome-proof-unavailable' },
            el('b', {}, 'The current market check is still running'),
            el('p', { class: 'muted small' }, 'The rest of StrikeBench is ready. Research opens immediately while the feed catches up.'),
            el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research'); } }, 'Open Research')));
        }
      }
    })();

    // ---- Two doors, by experience ----
    // The beginner door enters the GUIDED first workflow (review IC-3): step 1 of the
    // first-week journey is researching a stock — not an unexplained empty form.
    var LEVEL_CARDS = [
      { level: 'beginner', title: 'Teach me', ic: 'sprout',
        blurb: 'Question-driven flows, plain language \u2014 every idea explains itself.',
        cta: 'Start as a beginner', go: '#/research',
        toast: 'Step 1: pick a stock that interests you \u2014 the Next-up list on Home walks the rest.' },
      { level: 'expert', title: 'Give me the terminal', ic: 'bolt',
        blurb: 'Dense tables, greeks, per-leg impact, inline filters.',
        cta: 'Open the terminal', go: '#/research/AAPL' }
    ];
    root.appendChild(section('CHOOSE YOUR ALTITUDE', 'How do you want to start?',
      el('div', { class: 'welcome-grid', id: 'welcome-levels' }, LEVEL_CARDS.map(function (c) {
        function start() {
          Learn.setLevel(c.level);
          try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
          if (c.toast && UI.toast) UI.toast(c.toast);
          App.navigate(c.go);
        }
        var card = el('div', { class: 'card welcome-card clickable', onclick: start },
          el('div', { class: 'icon-tile' }, icon(c.ic)),
          el('h3', {}, c.title),
          el('p', {}, c.blurb),
          el('button', {
            class: 'btn', 'data-welcome-level': c.level, onclick: function (e) { e.stopPropagation(); start(); }
          }, c.cta));
        return pressable(card, c.title + ': ' + c.blurb, 'link');
      }))));

    // ---- How it works: the four-step promise, full width, original ghost numerals ----
    var STEPS = [
      ['Pick a goal', 'or a ticker you care about'],
      ['Screen ideas', 'refusals explained, risk capped'],
      ['Practice the trade', 'with the $100k paper account'],
      ['Review honestly', 'marks, P/L, what changed']
    ];
    var stepper = el('div', { class: 'stepper' }, STEPS.map(function (st, i) {
      return el('div', { class: 'step-block' },
        el('div', { class: 'step-num' }, '0' + (i + 1)),
        el('div', { class: 'step-text' }, el('b', {}, st[0]), el('span', {}, st[1])));
    }));
    var how = section('HOW IT WORKS', 'Four steps, no account required', stepper);
    how.id = stepsAnchorId;
    root.appendChild(how);

    // ---- One slim footer row: the sector hook + the exit ----
    root.appendChild(el('div', { class: 'welcome-footer' },
      el('div', { class: 'cta-banner' },
        el('div', {},
          el('b', {}, 'Thirteen sector universes, one flick away.'),
          el('p', { class: 'muted' }, 'Tech, semiconductors, healthcare, defense and more \u2014 feeding the ticker, the scout, and every symbol box.')),
        el('button', { class: 'btn', onclick: function () { App.navigate('#/research'); } }, 'Open the sector explorer'))));

    function section(eyebrow, title, contentNode) {
      return el('section', { class: 'welcome-section' },
        el('div', { class: 'eyebrow' }, eyebrow),
        el('h2', { class: 'welcome-h2' }, title),
        contentNode);
    }
  }

  function welcomed() {
    try { return window.localStorage.getItem('strikebench.welcomed') === '1'; } catch (e) { return true; }
  }

  function legLabel(leg) {
    if (leg.type === 'STOCK') return leg.action + ' ' + (leg.ratio * 100) + ' shares';
    return leg.action + ' ' + leg.ratio + 'x ' + stripZeros(leg.strike) + leg.type.charAt(0) + ' ' + leg.expiration;
  }

  function stripZeros(s) {
    return String(parseFloat(s));
  }

  function fmtBreakeven(b) {
    var v = parseFloat(b);
    return isNaN(v) ? String(b) : (Math.round(v * 100) / 100).toString();
  }

  function prettyStrategy(s) {
    return (s || '').replace(/_/g, ' ').toLowerCase().replace(/^./, function (c) { return c.toUpperCase(); });
  }

  function riskMode() { return document.getElementById('risk-mode').value; }

  function brandName() {
    return (App.state.brand && App.state.brand.name) || 'StrikeBench';
  }

  // ---------- 0. Home dashboard ----------

  async function home(root, params) {
    if (params && params[0] === 'tour') return welcome(root);
    // Paint the frame FIRST, fill every card as its data arrives: the screen must never
    // be a blank void while accounts and quotes load. Each section fills independently
    // and fails visibly (empty state), never silently.
    // The opening page's DNA is folded INTO the dashboard — a welcoming hero band with
    // the account numbers in it, and the full tour one visible click away. Streamlined,
    // not discarded: fresh accounts still open on the full hero page.
    // OPERATIONAL HERO (review #10): Home is the user's DESK — market mode, account numbers,
    // and one contextual next action. The product positioning ("Learn options by doing") lives
    // on the Welcome/tour page only; repeating it here made Home a compressed landing page.
    var statsAnchor = el('div', { class: 'grid grid-4', id: 'home-stats' });
    var heroMode = el('span', { class: 'badge badge-ok', id: 'home-mode-chip' }, 'OBSERVED MARKET');
    if (App.config && App.config.scenarioMode) {
      heroMode = el('span', { class: 'badge badge-warn', id: 'home-mode-chip' },
        'SCENARIO: ' + (App.config.activeDatasetName || 'generated data'));
    } else if (App.state.world === 'demo') {
      heroMode = el('span', { class: 'badge badge-warn', id: 'home-mode-chip' }, 'DEMO MARKET');
    } else if (App.state.world && App.state.world !== 'observed') {
      heroMode = el('span', { class: 'badge badge-sim', id: 'home-mode-chip' }, 'SIMULATED MARKET SESSION');
    }
    var t0 = App.state.ticket;
    var heroPlaceable = !!(t0 && (t0.candidate || (t0.custom && t0.legs && t0.legs.length)));
    var heroSub = el('p', { class: 'home-hero-sub', id: 'home-context-line' },
      heroPlaceable
        ? 'A working idea on ' + t0.symbol + ' is ready to place \u2014 or keep exploring.'
        : 'Screened ideas, honest odds, worst case always known before you commit.');
    var heroCta = heroPlaceable
      ? el('button', { class: 'btn', onclick: function () { App.navigate('#/trade/place'); } },
          'Place ' + t0.symbol + ' \u2192')
      : el('button', { class: 'btn', onclick: function () { App.navigate('#/trade/discover'); } }, 'Find an idea');
    // The hero owns THE single primary next action (review P5): the tour demoted to a
    // quiet entry at the bottom of the page — onboarding is not a permanent command.
    root.appendChild(heroBlock('dashboard', {
      eyebrow: ['YOUR PRACTICE DESK ', heroMode],
      title: el('h1', { class: 'home-hero-title' }, 'Your ', el('span', { class: 'grad' }, 'desk'), '.'),
      sub: heroSub,
      aside: statsAnchor,
      ctas: [heroCta]
    }));

    // First contact: a fresh account that hasn't dismissed the welcome gets the opening
    // page instead — decided by the same account fetch that fills the stat row.
    var acct = null;
    try {
      acct = (await API.get('/api/account')).account;
    } catch (e) { /* dashboard still renders; stats show an honest note below */ }
    if (!welcomed()) {
      if (acct && !acct.hasTraded) { root.innerHTML = ''; return welcome(root); }
      try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
    }
    if (acct) {
      statsAnchor.appendChild(stat('Cash', fmtMoney(acct.cashCents), 'Practice money available. Nothing here is real dollars.'));
      statsAnchor.appendChild(stat('Reserved', fmtMoney(acct.reservedCents), 'Held to cover the worst case of your open trades.'));
      statsAnchor.appendChild(stat('Buying power', fmtMoney(acct.buyingPowerCents), 'Cash minus reserves — what you can still put at risk.'));
      statsAnchor.appendChild(stat('Started with', fmtMoney(acct.startingCashCents)));
    } else {
      statsAnchor.appendChild(alertBox('warn', 'Account unavailable right now', ['Retry from the Account screen.']));
    }

    var colL = el('section', { class: 'home-col home-market-slot', 'aria-label': 'Market watch' });
    var colR = el('div', { class: 'home-col home-col-side' });
    var posAnchor = el('div', { id: 'open-trades-anchor', class: 'home-wide' });
    root.appendChild(el('div', { class: 'home-cols home-layout' }, colL, colR, posAnchor));
    var pulseAnchor = el('div', { id: 'sector-pulse-anchor' });
    // Sector pulse: the shared sector rail — same affordance as Research and Trade.
    // Renders instantly (usable before quotes answer); day moves fill in async.
    (function sectorPulse() {
      if (!App.state.universe) return;
      var pulse = el('div', { class: 'card', id: 'sector-pulse' },
        UI.cardHeader('Sector pulse',
          el('a', { href: '#/research', class: 'muted', style: 'font-size:12.5px' }, 'Explore sectors \u2192')),
        sectorRail({
          onPick: function (sec) {
            App.state.explorerSector = sec.key;
            App.navigate('#/research');
          }
        }),
        App.state.world === 'demo'
          ? el('p', { class: 'muted small', style: 'margin:10px 4px 0' },
              'One fixed teaching universe is active. Open Research for its five stocks, or start a moving session from Simulate.')
          : null,
        explain('Day change of each sector\u2019s ETF proxy. Tap a sector to open its symbols in the explorer.'));
      if (pulseAnchor.isConnected) pulseAnchor.replaceWith(pulse);
      else pulseAnchor.appendChild(pulse); // not yet mounted: fill in place, mount later
    })();


    // Markets: shell now, tiles when the ONE batch-quotes call answers (this used to be
    // eight sequential full-research calls that blocked the whole dashboard).
    var uni = App.state.universe && App.state.universe.active;
    // Eight symbols use desktop real estate without turning Home into the full explorer.
    // Smaller viewports progressively show the first six/four; the full universe stays one tap away.
    var marketSymbols = uni && uni.symbols && uni.symbols.length ? uni.symbols.slice(0, 8) : CORE_SYMBOLS;
    var tiles = el('div', { class: 'home-market-grid' });
    colL.appendChild(el('div', { class: 'card home-market-card', id: 'home-market-watch' },
      UI.cardHeader('Market watch' + (uni && App.state.world !== 'demo' ? ' — ' + uni.label : ''),
        el('a', { href: '#/research', class: 'muted home-view-all' },
          'View all ' + ((uni && uni.symbols && uni.symbols.length) || marketSymbols.length) + ' symbols →')),
      tiles,
      el('p', { class: 'muted small market-watch-caption' },
        'Select a stock for its chart, options, volatility, events and historical evidence.')));
    var marketsFill = (async function fillMarkets() {
      var rows = [];
      try {
        rows = (await API.get('/api/quotes?symbols=' + marketSymbols.join(','))).quotes || [];
      } catch (e) { /* handled below as the empty state */ }
      if (!rows.length) {
        tiles.appendChild(UI.emptyState('Market data unavailable right now',
          'The quote providers did not answer. See the Data screen for per-source health.',
          'Check data status', function () { App.navigate('#/status'); }));
        return;
      }
      tiles.setAttribute('data-count', String(Math.min(rows.length, 8)));
      rows.forEach(function (q) {
        var sparkSlot = el('div', { class: 'spark-slot' });
        tiles.appendChild(el('a', {
          class: 'tile sym-card', 'data-sym': q.symbol,
          href: '#/research/' + q.symbol,
          'aria-label': 'Open ' + q.symbol + ' full analysis'
        },
          el('div', { class: 't-sym' }, q.symbol, ' ',
            App.state.world === 'demo' ? null : badge(q.freshness)),
          el('div', { class: 't-px' }, fmtNum(q.last)),
          UI.delta(q.last, q.prevClose),
          el('div', { class: 't-nm' }, q.description || ''),
          sparkSlot,
          el('span', { class: 'destination-cue', 'aria-hidden': 'true' }, icon('chevron-right', 16))));
      });
      // The SAME sparkline card as Research, ONE batch call; Home cards go straight to the
      // full analysis (no preview layer here — review P5.8).
      function paintHomeSpark(row) {
        var slot = tiles.querySelector('.sym-card[data-sym="' + row.symbol + '"] .spark-slot');
        if (!slot) return;
        slot.innerHTML = '';
        slot.appendChild(UI.sparkline(row, { height: 30 }));
        // In Demo, the global band and each quote already say Demo; a second identical history
        // chip on every card adds noise, not honesty. Other lanes retain separate history evidence.
        if (row.evidence && !(App.state.world === 'demo' && row.evidence.provenance === 'DEMO')) {
          slot.appendChild(UI.evidenceBadge(row.evidence, { className: 'spark-ev', compact: true }));
        }
      }
      try {
        var sp = await API.prefetch('/api/sparklines?symbols=' + rows.map(function (q) { return q.symbol; }).join(',') + '&range=3m');
        if (!sp) {
          rows.forEach(function (q) { paintHomeSpark(missingSparkRow(q.symbol,
            'History lookup yielded to interactive market work. Open analysis to try on demand.')); });
          return;
        }
        var returned = {};
        (sp.sparklines || []).forEach(function (row) {
          returned[row.symbol] = true;
          paintHomeSpark(row);
        });
        rows.forEach(function (q) {
          if (!returned[q.symbol]) paintHomeSpark(missingSparkRow(q.symbol, 'Daily history was not returned for this symbol.'));
        });
      } catch (e) {
        rows.forEach(function (q) { paintHomeSpark(missingSparkRow(q.symbol,
          'Chart unavailable right now; the quote remains usable.')); });
      }
    })();

    // Open positions (review #8): a 256px empty card was wasted real estate — with no trades
    // the whole card stays OFF the page (Next up carries the one-line state + action instead);
    // with trades, three USEFUL rows: live P/L, max loss, opened.
    var hasOpenTrades = false;
    var tradesFill = (async function fillTrades() {
      try {
        var open = await API.get('/api/trades?status=ACTIVE&size=3');
        if (!open.trades.length) { posAnchor.remove(); return; }
        hasOpenTrades = true;
        var posCard = el('div', { class: 'card home-wide', id: 'open-trades-card' }, UI.cardHeader('Open practice trades',
          el('a', { href: '#/portfolio' }, 'All trades \u2192')));
        posCard.appendChild(table(['Symbol', 'Strategy', 'Now', 'Theor. max loss', 'Opened'],
          open.trades.map(function (t) {
            return pressable(el('tr', { class: 'clickable', onclick: function () { App.navigate('#/trade/' + t.id); } },
              el('td', {}, el('b', {}, t.symbol)),
              el('td', {}, prettyStrategy(t.strategy) + ' x' + t.qty),
              el('td', {}, t.unrealizedPnlCents !== undefined && t.unrealizedPnlCents !== null
                ? pnlSpan(t.unrealizedPnlCents) : el('span', { class: 'muted' }, '\u2014')),
              el('td', { class: 'loss' }, fmtMoney(t.maxLossCents)),
              el('td', { class: 'muted' }, UI.fmtDate(t.createdAt))), 'Open ' + t.symbol + ' trade');
          })));
        posAnchor.replaceWith(posCard);
      } catch (e) {
        posAnchor.replaceWith(el('div', { class: 'card' }, alertBox('warn', 'Could not load open trades', [e.message])));
      }
    })();

    // NEXT UP (review #9): ONE contextual card replaces the journey card + continuity row +
    // empty-trades CTA — the single most useful next action first, everything else quiet.
    (function nextUp() {
      var card = el('div', { class: 'card card-slim', id: 'next-up' }, UI.cardHeader('Continue'));
      var any = false;
      // The beginner first-week path, folded in (retires itself after the first trade).
      if (Learn.currentLevel() === 'beginner' && acct && !acct.hasTraded) {
        var steps = [
          { label: 'Research a stock', done: (function () { try { return (JSON.parse(localStorage.getItem('strikebench.recent') || '[]')).length > 0; } catch (e) { return false; } })(), hash: '#/research' },
          { label: 'Get a screened idea', done: !!App.state.recommendResults || !!App.state.scoutResults, hash: '#/trade/discover' },
          { label: 'Practice-place it', done: false, hash: '#/trade/discover' },
          { label: 'Know the exit plan', done: false, hash: '#/trade/discover' }
        ];
        card.appendChild(el('div', { class: 'journey', id: 'journey-card' }, steps.map(function (st2, i) {
          return el('button', { class: 'journey-step' + (st2.done ? ' done' : ''), type: 'button',
            onclick: function () { App.navigate(st2.hash); } },
            el('span', { class: 'ck-mark' }, st2.done ? '' : String(i + 1)),
            el('span', {}, st2.label));
        })));
        any = true;
      }
      var chips = [];
      var t = App.state.ticket;
      var placeable = !!(t && (t.candidate || (t.custom && t.legs && t.legs.length)));
      if (placeable) {
        chips.push(el('button', { class: 'sym-chip', 'data-continue': 'idea',
          onclick: function () { App.navigate('#/trade/place'); } },
          'Working idea: ' + t.symbol + ' ' + (t.custom ? 'custom strategy'
            : (t.candidate && t.candidate.strategy ? prettyStrategy(t.candidate.strategy) : 'trade')) + ' →'));
      }
      var sym = App.context.symbol();
      if (sym) {
        // Context, not command duplication (review P5): these chips RESUME where you were.
        chips.push(el('button', { class: 'sym-chip', 'data-continue': 'research',
          onclick: function () { App.navigate('#/research/' + sym); } }, 'Resume: ' + sym + ' analysis →'));
      }
      if (chips.length) {
        card.appendChild(el('div', { class: 'chip-row', id: 'continue-row' }, chips));
        any = true;
      }
      if (!any) {
        card.appendChild(el('button', { class: 'home-start-research', type: 'button',
          onclick: function () { App.navigate('#/research'); } },
          el('b', {}, 'Start with a stock you know'),
          el('span', { class: 'muted small' }, 'Open Research, choose a ticker, then carry that view into a strategy.')));
      }
      colR.appendChild(card);
    })();

    // Command bar (review P5): ONLY shortcuts the primary nav does not already carry —
    // Research and Ideas live in the top nav and the hero; repeating them here was noise.
    colR.appendChild(el('div', { class: 'card card-slim', id: 'command-bar' },
      UI.cardHeader('Tools'),
      el('div', { class: 'btn-row', style: 'flex-wrap:wrap' },
        [['Builder', '#/trade/shape', 'target'], ['Backtest', '#/trade/verify', 'chart'],
         ['Simulate', '#/data/simulation', 'flask']].map(function (a) {
          return el('button', { class: 'btn btn-sm btn-secondary', title: a[0],
            onclick: function () { App.navigate(a[1]); } }, icon(a[2], 14), ' ', a[0]);
        }))));
    colR.appendChild(pulseAnchor); // sector pulse LAST: on phones the next action leads
    // The tour's quiet home: a muted line at the very bottom (review P5 — onboarding is
    // reachable, never a standing primary action). Same id so muscle memory + tests hold.
    root.appendChild(el('p', { class: 'muted small home-about-row' },
      el('a', { href: '#/home/tour', id: 'home-tour-link',
        title: 'The opening page: what StrikeBench is, how it works, the live engine demo' },
        'About StrikeBench \u00b7 take the tour')));

    // Wait for the fills so data-ready means READY (tests and users agree on that).
    await Promise.all([marketsFill, tradesFill]);
  }

  /** Under ~a day old — the window where the spread-cost note earns its keep. */
  /** Raw pricing-tier enums, in words a person reads. */
  function prettyPricingMode(mode) {
    return {
      HISTORICAL_CHAIN: 'real option history (observed chains)',
      OBSERVED_FROM_HISTORY: 'real option history (observed marks)',
      MODELED_FROM_UNDERLYING: 'modeled from the stock\u2019s own moves (no real option history)',
      PAYOFF_ONLY: 'payoff math only (no pricing data)'
    }[mode] || mode;
  }

  function isYoungTrade(createdAt) {
    if (!createdAt) return false;
    var t = new Date(createdAt).getTime();
    return isFinite(t) && (Date.now() - t) < 26 * 3600 * 1000;
  }

  /**
   * ProductHero primitive (review addendum D / sequence #7): ONE hero composition — frame,
   * eyebrow, title, sub, CTA row, optional aside — with a variant class. Welcome (landing)
   * and Home (operational desk) stay DIFFERENT pages sharing identical bones.
   */
  /**
   * ONE structural class system (`.product-hero` / `.ph-inner` / `.ph-text` / `.ph-ctas`) with
   * variant modifiers — the legacy classes ride along for existing CSS, but shared styling
   * targets the ph-* frame so the two pages cannot drift structurally (review P3 #10).
   */
  function heroBlock(variant, opts) {
    if (variant === 'welcome') {
      return el('section', { class: 'product-hero ph-welcome hero', id: opts.id || null },
        opts.deco || null,
        el('div', { class: 'ph-inner hero-inner' },
          el('div', { class: 'ph-text' },
            opts.top || (opts.eyebrow ? el('div', { class: 'eyebrow' }, opts.eyebrow) : null),
            opts.title,
            opts.sub || null),
          opts.ctas ? el('div', { class: 'ph-ctas hero-ctas' }, opts.ctas) : null,
          opts.extras || null));
    }
    return el('section', { class: 'product-hero ph-dashboard home-hero' },
      el('div', { class: 'ph-inner home-hero-top' },
        el('div', { class: 'ph-text home-hero-text' },
          el('div', { class: 'eyebrow' }, opts.eyebrow),
          opts.title,
          opts.sub || null),
        opts.aside || null,
        opts.ctas ? el('div', { class: 'ph-ctas home-hero-ctas' }, opts.ctas) : null));
  }

  /** Keyboard + AT semantics for clickable tiles/rows (review #12: no mouse-only navigation). */
  function pressable(node, label, role) {
    node.setAttribute('role', role || node.getAttribute('role') || 'link');
    node.setAttribute('tabindex', '0');
    if (label) node.setAttribute('aria-label', label);
    node.addEventListener('keydown', function (e) {
      if (e.target !== node) return; // nested buttons own their own keyboard activation
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); node.click(); }
    });
    return node;
  }

  function quickAction(title, hint, hash, ic) {
    return pressable(el('div', { class: 'tile qa-tile clickable', onclick: function () { App.navigate(hash); } },
      el('div', { class: 'qa-head' },
        el('div', { class: 'icon-tile icon-tile-sm' }, icon(ic || 'compass', 18)),
        el('div', {}, el('div', { class: 't-sym' }, title),
          el('div', { class: 'muted qa-hint' }, hint)))), title + ': ' + hint, 'link');
  }

  // ---------- 2. Research ----------

  async function research(root, params) {
    var symbol = (params[0] || '').toUpperCase();
    if (symbol) App.context.selectSymbol(symbol);
    var input = el('input', { type: 'text', id: 'symbol-input', placeholder: 'Ticker, e.g. AAPL', value: symbol });
    var go = function () { if (input.value.trim()) App.navigate('#/research/' + input.value.trim().toUpperCase()); };

    root.appendChild(el('h1', {}, 'Research'));
    var recentBox = el('div', { class: 'research-recents', id: 'recent-symbols' });
    var researchContext = UI.symbolContext({
      mode: 'editable', id: 'research-symbol-context', input: input,
      label: symbol ? 'Change stock' : Learn.currentLevel() === 'beginner'
        ? 'Which stock do you want to understand?' : 'Research symbol',
      compact: !!symbol, showStatus: !symbol, hideLabel: !!symbol, class: 'symbol-context-purpose',
      commitLabel: symbol ? 'Go' : 'Open analysis', commitId: 'symbol-go', onCommit: go
    });
    var contextExtras = el('div', { class: 'symbol-context-extras' },
      symbol ? el('a', { href: '#/research', class: 'context-extras-action', id: 'back-to-sectors' }, 'All sectors') : null,
      recentBox);
    root.appendChild(el('div', { class: 'research-symbol-shell' }, researchContext, contextExtras));
    function renderRecents() {
      recentBox.innerHTML = '';
      var list = recentSymbols();
      if (!list.length) return;
      recentBox.appendChild(el('span', { class: 'context-extras-label' }, 'Recent'));
      list.forEach(function (s) {
        recentBox.appendChild(el('button', { class: 'sym-chip' + (s === symbol ? ' active' : ''),
          onclick: function () { App.navigate('#/research/' + s); } }, s));
      });
    }
    renderRecents();

    if (!symbol) {
      // Non-blocking: paint into a captured child and fill it detached, so the route returns
      // immediately (navigating away is never trapped behind the sector-quote batch).
      var idxBody = el('div', { id: 'research-index-body' });
      root.appendChild(idxBody);
      var _it = App.navToken;
      (async function fillIndex() {
        await sectorExplorer(idxBody, 'research');
        if (!App.alive(_it)) return;
        idxBody.appendChild(await studyToolsSection());
      })();
      return;
    }

    // PROGRESSIVE PAINT: the shell + independent sections (events, price history, news) render
    // immediately; the hero, "what you can do", and the chain fill when /api/research lands (now a
    // parallelized, faster call). No more all-or-nothing blank page behind one request.
    var heroCard = el('div', { class: 'card', id: 'research-hero' }, UI.spinner('Loading ' + symbol + '…'));
    root.appendChild(heroCard);
    var researchP = API.get('/api/research/' + symbol);

    // One dense destination becomes a calm workspace. All capabilities remain mounted, but only
    // one coherent task is visible at a time; the hero and symbol context stay fixed above it.
    App.state.researchTabBySymbol = App.state.researchTabBySymbol || {};
    var selectedResearchTab = App.state.researchTabBySymbol[symbol] || 'overview';
    var researchPanels = {};
    var researchTabDefs = [
      { key: 'overview', label: 'Market picture', sub: 'Price, events and latest news', icon: 'chart' },
      { key: 'view', label: 'Evidence & scenarios', sub: 'Historical analogs and possible futures', icon: 'scope' },
      { key: 'options', label: 'Options', sub: 'Chains, expirations and trade paths', icon: 'grid' },
      { key: 'news', label: 'News & filings', sub: 'Headlines and source documents', icon: 'flag' }
    ];
    var researchTabs = el('div', { class: 'research-local-tabs', id: 'research-workspace-tabs', role: 'tablist' },
      researchTabDefs.map(function (t) {
        return el('button', { type: 'button', role: 'tab', id: 'research-tab-' + t.key,
          'data-research-tab': t.key, 'aria-controls': 'research-panel-' + t.key,
          tabindex: selectedResearchTab === t.key ? '0' : '-1',
          class: selectedResearchTab === t.key ? 'active' : '',
          'aria-selected': selectedResearchTab === t.key ? 'true' : 'false',
          onclick: function () { activateResearchTab(t.key); },
          onkeydown: function (e) {
            if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
            e.preventDefault();
            var i = researchTabDefs.findIndex(function (x) { return x.key === t.key; });
            var step = e.key === 'ArrowRight' ? 1 : -1;
            var next = researchTabDefs[(i + step + researchTabDefs.length) % researchTabDefs.length];
            activateResearchTab(next.key, true);
          }
        }, icon(t.icon, 16), el('span', {}, el('b', {}, t.label), el('small', {}, t.sub)));
      }));
    var researchNav = el('nav', { class: 'research-local-nav', 'aria-label': symbol + ' research workspace' },
      el('div', { class: 'research-local-nav-head' },
        el('b', {}, 'Explore ' + symbol),
        el('span', { class: 'muted' }, 'One workspace, four connected views.')),
      researchTabs);
    var researchWorkspace = el('div', { class: 'research-workspace', id: 'research-workspace' });
    ['overview', 'view', 'options', 'news'].forEach(function (key) {
      var panel = el('section', { class: 'research-workspace-panel', id: 'research-panel-' + key,
        role: 'tabpanel', 'aria-labelledby': 'research-tab-' + key, 'data-research-panel': key,
        hidden: selectedResearchTab === key ? null : '' });
      researchPanels[key] = panel;
      researchWorkspace.appendChild(panel);
    });
    function activateResearchTab(key, focus) {
      selectedResearchTab = key;
      App.state.researchTabBySymbol[symbol] = key;
      researchTabs.querySelectorAll('[role="tab"]').forEach(function (b) {
        var active = b.getAttribute('data-research-tab') === key;
        b.classList.toggle('active', active);
        b.setAttribute('aria-selected', active ? 'true' : 'false');
        b.setAttribute('tabindex', active ? '0' : '-1');
        if (active && focus) b.focus();
      });
      Object.keys(researchPanels).forEach(function (k) { researchPanels[k].hidden = k !== key; });
    }
    root.appendChild(researchNav);
    root.appendChild(researchWorkspace);

    researchPanels.overview.appendChild(comingUp(symbol, false)); // dated events; self-fetches
    var newsOverviewCard = el('div', { class: 'card', id: 'news-overview-card' },
      UI.cardHeader('Latest news & filings'), UI.spinner('Loading headlines…'));
    researchPanels.overview.appendChild(newsOverviewCard);
    var actionsAnchor = el('div', { id: 'symbol-actions-anchor' }); // filled after r: the action cards, or the no-options warning
    researchPanels.options.appendChild(actionsAnchor);

    // Price history — its own endpoint, independent of /api/research.
    researchPanels.overview.appendChild(el('div', { class: 'card', id: 'history-card' },
      UI.cardHeader('Price history'),
      UI.rangeChart({ initial: '1y', fetch: historyFetch(symbol) }),
      explain('When observed OHLC is available, candles show open/high/low/close and volume. Close-only imports render as a line instead of inventing ranges. The evidence badge names whether history is observed, simulated, modeled, or Demo.')));

    // The Research progression on a symbol: NOW (chart/news above) → TEST YOUR VIEW (past
    // evidence + possible futures under ONE thesis) → express it in Trade.
    researchPanels.view.appendChild(testYourViewSection(symbol));

    var chainAnchor = el('div', { id: 'chain-anchor' });
    researchPanels.options.appendChild(chainAnchor);

    // News & filings ALWAYS render (independent endpoint) — a card that silently vanishes reads as
    // "where has news gone?", not as an empty feed.
    var newsCard = el('div', { class: 'card', id: 'news-card' }, UI.cardHeader('News & filings'), UI.spinner('Loading news…'));
    researchPanels.news.appendChild(newsCard);
    (async function fillNews() {
      var newsItems = [];
      try { newsItems = ((await API.get('/api/research/' + symbol + '/news')).items || []); }
      catch (e) { /* empty state below */ }
      var isFiling = function (n) { return /edgar|sec/i.test(n.source || '') || /filing$/i.test(n.headline || ''); };
      function newsContext(n) {
        var h = String(n.headline || '').toLowerCase();
        if (isFiling(n)) return 'Official company disclosure. Open the source to inspect the filing type, dates and material changes.';
        if (/earnings|results|revenue|guidance|outlook/.test(h)) return 'Results or guidance can reset price and implied volatility. Check the actual release and whether it falls inside your horizon.';
        if (/regulat|probe|lawsuit|antitrust|recall/.test(h)) return 'Regulatory or legal news can create gap risk. Verify the scope and source before changing a thesis.';
        if (/analyst|upgrade|downgrade|price target/.test(h)) return 'Analyst opinion, not a company disclosure. Inspect the changed assumptions rather than relying on the headline.';
        if (/launch|product|approval|contract|deal/.test(h)) return 'A business catalyst may affect growth expectations. Open the source to judge timing, scale and whether it is already priced in.';
        return 'Headline-based context only. Open the source before treating it as evidence or a catalyst.';
      }
      function newsTile(n) {
        var when = n.publishedEpochMs ? new Date(n.publishedEpochMs).toISOString().slice(0, 10) : '';
        return el('article', { class: 'news-tile' },
          el('div', { class: 'news-meta' },
            el('span', { class: 'badge badge-dim' }, isFiling(n) ? 'FILING' : 'NEWS'),
            el('span', { class: 'muted' }, [when, n.source].filter(Boolean).join(' · '))),
          n.url ? el('a', { class: 'news-headline', href: n.url, target: '_blank', rel: 'noopener' }, n.headline)
            : el('b', { class: 'news-headline' }, n.headline),
          el('p', { class: 'muted news-context' }, newsContext(n)));
      }
      newsOverviewCard.innerHTML = '';
      newsOverviewCard.appendChild(UI.cardHeader('Latest news & filings',
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', id: 'open-news-tab',
          onclick: function () { activateResearchTab('news', true); } }, 'View all')));
      newsCard.innerHTML = '';
      newsCard.appendChild(UI.cardHeader('News & filings'));
      if (!newsItems.length) {
        newsOverviewCard.appendChild(el('p', { class: 'muted' },
          'No headline or filing source answered for ' + symbol + '. Price research remains available.'));
        newsCard.appendChild(UI.emptyState('No headlines right now',
          'No news source answered for ' + symbol + '. See the Data screen for per-source health.',
          'Check data status', function () { App.navigate('#/status'); }));
        return;
      }
      var headlines = newsItems.filter(function (n) { return !isFiling(n); }).slice(0, 8);
      var filings = newsItems.filter(isFiling).slice(0, 5);
      newsOverviewCard.appendChild(el('div', { class: 'news-grid news-grid-overview' },
        newsItems.slice(0, 3).map(newsTile)));
      newsOverviewCard.appendChild(el('p', { class: 'muted small news-honesty' },
        'Context is classified from the headline and source; it is not an article summary. Open the source for the facts.'));
      if (headlines.length) {
        newsCard.appendChild(el('h3', { class: 'news-section-title' }, 'Headlines'));
        newsCard.appendChild(el('div', { class: 'news-grid' }, headlines.map(newsTile)));
      }
      if (filings.length) {
        newsCard.appendChild(el('h3', { class: 'news-section-title' }, 'Corporate filings (SEC)'));
        if (Learn.currentLevel() === 'beginner') {
          newsCard.appendChild(explain('Official documents companies must file: 10-K (annual report), 10-Q (quarterly), 8-K (something important just happened). Big moves often start here.'));
        }
        newsCard.appendChild(el('div', { class: 'news-grid' }, filings.map(newsTile)));
      }
      newsCard.appendChild(el('p', { class: 'muted small news-honesty' },
        'StrikeBench does not summarize article bodies here. Headline context is a navigation aid, not evidence by itself.'));
    })();

    // Hero + option-dependent sections fill WITHOUT blocking the route: research() returns after
    // painting the shell above, so a new navigation is never trapped behind this fetch. A stale
    // fill (the user already navigated away) bails on the render token.
    var _rt = App.navToken;
    researchP.then(function (r) {
      if (!App.alive(_rt)) return;
      rememberRecent(symbol);
      renderRecents();
      var q = r.quote;

      // Live hero price: the MarketStore feeds it straight from the SSE stream — zero extra
      // GETs per tick (holistic review Phase 3). The light quote fetch survives only as a
      // fallback for the first frame after entering a world.
      if (App.Market && App.state.world && App.state.world !== 'observed') {
        function paintHero(row) {
          var px = document.getElementById('research-px');
          if (px) px.textContent = fmtNum(row.last);
          var d = px && px.nextElementSibling;
          if (d && d.classList.contains('delta')) d.replaceWith(UI.delta(row.last, row.prevClose));
        }
        App.Market.subscribe(function () {
          if (!App.alive(_rt)) return;
          var row = App.Market.get(symbol);
          if (row) paintHero(row);
        }, _rt);
        App.onEvent && App.onEvent('world.tick', async function () {
          if (!App.alive(_rt) || App.Market.get(symbol)) return; // store covers it — no fetch
          try {
            var fresh = await API.getFresh('/api/quotes?symbols=' + encodeURIComponent(symbol));
            var row = fresh && fresh.quotes && fresh.quotes[0];
            if (App.alive(_rt) && row) paintHero(row);
          } catch (e) { /* next tick retries */ }
        }, _rt);
      }

    var evProfile = r.evidence || {};
    var evInputs = evProfile.inputs || {};
    var evSummary = evProfile.summary || q.evidence;
    var inputKeys = Object.keys(evInputs);
    var sig = function (e) { return e ? [e.provenance, e.age, e.source].join('|') : ''; };
    var allSame = inputKeys.length > 1 && inputKeys.every(function (key) { return sig(evInputs[key]) === sig(evSummary); });
    var evidenceDetail = inputKeys.length && !allSame ? UI.expandable('Input evidence detail', function () {
      return el('div', { class: 'chip-row evidence-detail-row' }, inputKeys.map(function (key) {
        return el('span', { class: 'evidence-input' }, key + ' ', UI.evidenceBadge(evInputs[key], { compact: true }));
      }));
    }) : null;
    var evidenceLine = el('div', { class: 'page-evidence', id: 'research-evidence' },
      el('b', {}, 'Evidence for this analysis'), UI.evidenceBadge(evSummary),
      evSummary && evSummary.provenance === 'MISSING'
        ? el('span', { class: 'muted' }, 'Some calculations are unavailable rather than filled with demo data.')
        : evSummary && evSummary.provenance === 'MIXED'
          ? el('span', { class: 'muted' }, 'Inputs come from different evidence types; each is named below.') : null,
      allSame ? el('span', { class: 'muted small' }, 'All inputs use this same evidence basis.') : null,
      el('span', { class: 'spacer' }),
      evidenceDetail);

    var secondaryMarketDetail = UI.expandable(
      Learn.currentLevel() === 'beginner' ? 'More market detail' : 'Quote, volatility & benchmark detail',
      function () {
        return el('div', {},
          el('div', { class: 'chip-row' },
            chip('Prev close', fmtNum(q.prevClose)),
            chip(el('span', {}, 'IV rank', UI.info('ivrank')), 'building history'),
            chip('Options', r.optionable ? 'yes' : 'no')),
          explain('IV is the move the options market is pricing in; HV is what the stock actually did lately. IV far above HV means options are relatively expensive to buy and richer to sell.'),
          (r.benchmarks && r.benchmarks.length) ? el('div', { class: 'chip-row' }, r.benchmarks.map(function (b) {
            return chip(b.symbol, fmtNum(b.last));
          })) : null);
      });
    var hero = el('div', { class: 'card research-hero-card' },
      el('div', { class: 'quote-hero' },
        el('span', { class: 'sym', id: 'research-symbol' }, symbol),
        el('span', { class: 'px', id: 'research-px' }, fmtNum(q.last)),
        UI.delta(q.last, q.prevClose),
        evSummary && evSummary.provenance === 'DEMO' ? null : badge(r.freshness),
        el('span', { class: 'spacer' }),
        el('button', {
          class: 'btn btn-sm btn-secondary', id: 'research-buy-shares',
          onclick: function () { stockOrderModal('buy', symbol); }
        }, 'Buy shares'),
        r.optionable ? el('button', {
          class: 'btn btn-sm', onclick: function () {
            App.context.selectSymbol(symbol);
            App.state.ideasPrefill = { symbol: symbol, autorun: true }; // asks for a goal, then runs
            App.navigate('#/recommend/manual');
          }
        }, 'Find strategies') : null),
      el('div', { class: 'nm', style: 'margin-top:2px' }, q.description || ''),
      evidenceLine,
      el('div', { class: 'chip-row' },
        chip('Bid/Ask', fmtNum(q.bid) + ' / ' + fmtNum(q.ask)),
        chip('Day', fmtNum(q.dayLow) + ' – ' + fmtNum(q.dayHigh)),
        chip('IV (ATM)', fmtPct(r.ivAtm)),
        chip('HV 30d', fmtPct(r.hv30))),
      secondaryMarketDetail);
    heroCard.replaceWith(hero);

    if (!r.optionable) {
      actionsAnchor.replaceWith(alertBox('warn', symbol + ' has no listed options (mutual funds and some securities cannot be option-traded). You can still study its price history below.'));
    }

    // What you can do with this symbol — each GOAL gets its own live one-liner, computed
    // from real ladder rungs (not marketing copy), with one-tap handoff into that flow.
    if (r.optionable) {
      (async function symbolActions() {
        try {
          var level = Learn.currentLevel();
          var held = null;
          try {
            var ps = (await API.get('/api/positions')).positions || [];
            held = ps.find(function (x) { return x.symbol === symbol; }) || null;
          } catch (eh) { /* fine */ }
          async function rung(intentKey) {
            try {
              var lad = await API.post('/api/recommend/ladder', { symbol: symbol, intent: intentKey });
              var rungs = lad.rungs || [];
              return rungs.length ? rungs[Math.min(1, rungs.length - 1)] : null;
            } catch (er) { return null; }
          }
          var cards = [];
          function actionCard(iconName, title, lineNode, intentKey, cta) {
            return el('div', { class: 'card action-card' },
              el('div', { class: 'action-head' }, el('span', { class: 'icon-tile icon-tile-sm' }, icon(iconName)), el('b', {}, title)),
              el('p', { class: 'muted action-line' }, lineNode),
              el('button', {
                class: 'btn btn-sm', onclick: function () {
                  App.state.ideasPrefill = { intent: intentKey, symbol: symbol, autorun: true };
                  App.navigate('#/recommend/manual');
                }
              }, cta));
          }
          // Fetch only the ladders this holding-state actually renders, in parallel \u2014
          // a holder never pays for the acquire ladder it would discard, and exit+hedge
          // stop stacking into a serial chain.
          var needExitHedge = held && held.freeShares >= 100;
          var ladders = await Promise.all([
            !held ? rung('acquire') : null,
            needExitHedge ? rung('exit') : null,
            needExitHedge ? rung('hedge') : null
          ]);
          var acq = ladders[0], ex = ladders[1], hg = ladders[2];
          if (acq && !held) {
            var kA = parseFloat(acq.legs[0].strike);
            cards.push(actionCard('tag', 'Own it cheaper',
              el('span', {}, 'Get paid ', el('b', { class: 'gain' }, fmtMoney(acq.entryNetPremiumCents)),
                ' now to buy at $' + kA + ' \u2014 effectively $' + acq.effectivePrice + '/sh.'),
              'ACQUIRE', 'Name your price'));
          }
          if (needExitHedge) {
            if (ex) {
              cards.push(actionCard('flag', 'Sell your ' + held.shares + ' shares higher',
                el('span', {}, 'Get paid ', el('b', { class: 'gain' }, fmtMoney(ex.entryNetPremiumCents)),
                  ' now to sell at $' + parseFloat(ex.legs[0].strike) + ' (effectively $' + ex.effectivePrice + '/sh).'),
                'EXIT', 'Pick your exit'));
            }
            if (hg) {
              cards.push(actionCard('shield', 'Protect what you hold',
                el('span', {}, 'A floor at $' + parseFloat(hg.legs[0].strike) + ' costs ',
                  el('b', { class: 'loss' }, fmtMoney(hg.maxLossCents)), ' until ' + (hg.legs[0].expiration || '') + '.'),
                'HEDGE', 'Pick your floor'));
            }
          }
          cards.push(actionCard('compass', 'Trade a view',
            el('span', {}, 'Bullish, bearish, calm, or wild \u2014 screen defined-risk structures for it.'),
            'DIRECTIONAL', 'Screen ideas'));
          if (level !== 'expert' && cards.length > 3) cards = cards.slice(0, 3);
          var section = el('div', { class: 'card', id: 'symbol-actions' },
            UI.cardHeader('What you can do with ' + symbol,
              held ? el('span', { class: 'muted' }, 'You hold ' + held.shares + ' sh @ ' + fmtMoney(held.avgCostCents) + '/sh') : null),
            el('div', { class: 'welcome-grid action-grid' }, cards));
          actionsAnchor.replaceWith(section);
        } catch (e) {
          // Additive, but a silent disappearance reads as inconsistent — leave a quiet retry.
          if (actionsAnchor.isConnected) {
            actionsAnchor.replaceWith(el('div', { class: 'card', id: 'symbol-actions' },
              alertBox('warn', 'Couldn’t load actions for ' + symbol + ' right now.'),
              el('div', { class: 'btn-row' },
                el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { App.render(); } }, 'Retry'))));
          }
        }
      })();
    }

    // Option chain (fills the anchor placed above the news card, so page order is preserved).
    if (r.optionable && r.expirations && r.expirations.length) {
      var showAll = false;
      var chainBody = el('div', {});
      var select = el('select', { id: 'expiration-select' }, r.expirations.map(function (d) {
        return el('option', { value: d }, d + '  (' + daysUntil(d) + 'd)');
      }));
      select.addEventListener('change', function () { loadChain(select.value); });
      var toggle = el('button', { class: 'btn btn-secondary btn-sm', id: 'chain-toggle', onclick: function () {
        showAll = !showAll;
        toggle.textContent = showAll ? 'Focus near the money' : 'Show all strikes';
        loadChain(select.value);
      } }, 'Show all strikes');
      var chainCard = el('div', { class: 'card' },
        UI.cardHeader('Option chain', toggle),
        explain('Each row is one strike: calls on the left, puts on the right. Green-tinted cells are in the money; the highlighted row is closest to the current price.'),
        el('div', { class: 'btn-row', style: 'margin-top:0' }, el('label', { class: 'muted' }, 'Expiration '), select),
        chainBody);
      chainAnchor.replaceWith(chainCard);

      var chainSeq = 0;
      var loadChain = async function (exp) {
        // Sequence token: a slow response for a previously-selected expiration must never
        // overwrite the chain the user is currently looking at.
        var seq = ++chainSeq;
        chainBody.innerHTML = '';
        chainBody.appendChild(UI.spinner('Loading chain…'));
        var chain;
        try { chain = await API.get('/api/research/' + symbol + '/chain?expiration=' + exp); }
        catch (e) {
          if (seq !== chainSeq) return;
          chainBody.innerHTML = '';
          chainBody.appendChild(alertBox('warn', 'Chain unavailable for ' + exp));
          return;
        }
        if (seq !== chainSeq) return; // a newer expiration was selected while this one loaded
        chainBody.innerHTML = '';
        var byStrike = {};
        (chain.calls || []).forEach(function (c) { byStrike[c.strike] = { call: c }; });
        (chain.puts || []).forEach(function (p) { (byStrike[p.strike] = byStrike[p.strike] || {}).put = p; });
        var strikes = Object.keys(byStrike).map(parseFloat).sort(function (a, b) { return a - b; });
        var spot = parseFloat(chain.underlyingPrice);
        var atmIdx = 0, best = Infinity;
        strikes.forEach(function (k, i) { var d = Math.abs(k - spot); if (d < best) { best = d; atmIdx = i; } });
        var visible = showAll ? strikes : strikes.slice(Math.max(0, atmIdx - 6), atmIdx + 7);

        var learning = Learn.currentLevel() === 'beginner';
        var rows = visible.map(function (k) {
          var pair = byStrike[k] || {};
          var c = pair.call || {}, p = pair.put || {};
          var isAtm = k === strikes[atmIdx];
          if (learning) {
            return el('tr', { class: isAtm ? 'atm' : '' },
              el('td', { class: k < spot ? 'itm' : '' }, c.bid !== undefined ? fmtNum(c.bid) + ' / ' + fmtNum(c.ask) : '—'),
              el('td', {}, el('b', {}, stripZeros(k))),
              el('td', { class: k > spot ? 'itm' : '' }, p.bid !== undefined ? fmtNum(p.bid) + ' / ' + fmtNum(p.ask) : '—'));
          }
          function buildBtn(type) {
            return el('button', { class: 'btn btn-sm btn-secondary chain-build', title: 'Start a build with this ' + type.toLowerCase() + ' leg in the strategy builder',
              onclick: function () {
                App.state.builderForm = { symbol: symbol, qty: 1, goal: 'BROWSE', templateKey: null, step: 4, legIdx: 0,
                  legs: [{ action: 'BUY', type: type, strike: String(k), expiration: select.value, ratio: 1 }], excluded: {} };
                App.navigate('#/trade/shape');
              } }, 'B');
          }
          return el('tr', { class: isAtm ? 'atm' : '' },
            el('td', {}, buildBtn('CALL')),
            el('td', { class: k < spot ? 'itm' : '' }, c.bid !== undefined ? fmtNum(c.bid) + ' / ' + fmtNum(c.ask) : '—'),
            el('td', { class: k < spot ? 'itm' : '' }, c.delta !== undefined && c.delta !== null ? fmtNum(c.delta) : '—'),
            el('td', { class: k < spot ? 'itm' : '' }, c.iv ? fmtPct(c.iv) : '—'),
            el('td', { class: k < spot ? 'itm' : '', title: 'open interest / volume' },
              (c.openInterest != null ? fmtNum(c.openInterest, 0) : '—') + ' / ' + (c.volume != null ? fmtNum(c.volume, 0) : '—')),
            el('td', {}, el('b', {}, stripZeros(k))),
            el('td', { class: k > spot ? 'itm' : '', title: 'open interest / volume' },
              (p.openInterest != null ? fmtNum(p.openInterest, 0) : '—') + ' / ' + (p.volume != null ? fmtNum(p.volume, 0) : '—')),
            el('td', { class: k > spot ? 'itm' : '' }, p.iv ? fmtPct(p.iv) : '—'),
            el('td', { class: k > spot ? 'itm' : '' }, p.delta !== undefined && p.delta !== null ? fmtNum(p.delta) : '—'),
            el('td', { class: k > spot ? 'itm' : '' }, p.bid !== undefined ? fmtNum(p.bid) + ' / ' + fmtNum(p.ask) : '—'),
            el('td', {}, buildBtn('PUT')));
        });
        chainBody.appendChild(el('div', { class: 'chip-row' },
          chip('Underlying', fmtNum(spot)), chip('Strikes', visible.length + ' of ' + strikes.length), badge(chain.freshness)));
        if (learning) {
          chainBody.appendChild(UI.expandable('How to read this table', function () {
            return el('div', {},
              el('p', {}, 'Each row is one ', UI.term('strike'), '. The left price is the ', UI.term('call'),
                ' (bets on rising), the right is the ', UI.term('put'), ' (bets on falling). Each shows ',
                UI.term('bid/ask', 'bid / ask'), ' — you buy at the ask and sell at the bid.'),
              el('p', {}, 'Green-tinted cells are in the money; the highlighted row is closest to the current price.'));
          }));
        }
        chainBody.appendChild(table(
          learning ? ['Call price', 'Strike', 'Put price']
                   : ['', 'Call bid/ask', 'Call Δ', 'Call IV', 'C OI/Vol', 'Strike', 'P OI/Vol', 'Put IV', 'Put Δ', 'Put bid/ask', ''], rows));
      };
      loadChain(r.expirations[0]);
      // MATERIAL-MOVE refresh (the admitted chain-cadence leftover): when the streamed spot
      // moves >0.5% from the loaded chain's underlying, refetch the visible expiration —
      // throttled to one reload per 5s, and only while this chain is on screen.
      (function chainCadence() {
        if (!App.Market) return;
        var lastReload = 0, loadedSpot = null;
        var origLoad = loadChain;
        loadChain = async function (exp) { await origLoad(exp); loadedSpot = null; };
        App.Market.subscribe(function () {
          if (!App.alive(_rt) || !chainBody.isConnected) return;
          var q = App.Market.get(symbol);
          if (!q || !isFinite(parseFloat(q.last))) return;
          var live = parseFloat(q.last);
          if (loadedSpot === null) { loadedSpot = live; return; }
          var nowT = Date.now();
          if (Math.abs(live - loadedSpot) / loadedSpot > 0.005 && nowT - lastReload > 5000) {
            lastReload = nowT;
            loadedSpot = live;
            API.invalidate(['/api/research/' + symbol + '/chain']);
            var sel = document.getElementById('expiration-select');
            origLoad(sel ? sel.value : r.expirations[0]);
          }
        }, _rt);
      })();
    }
    }, function (e) {
      if (!App.alive(_rt)) return;
      heroCard.replaceWith(alertBox('danger', 'No data for ' + symbol + '. Check the ticker.'));
      actionsAnchor.remove(); chainAnchor.remove();
      // The scenario studio must not offer to simulate futures for a symbol the app just said
      // it knows nothing about — swap it for the same honest empty state its siblings show.
      var wi = document.getElementById('whatif-card');
      if (wi) {
        wi.innerHTML = '';
        wi.appendChild(UI.cardHeader('What COULD happen next?'));
        wi.appendChild(UI.emptyState('Needs a price anchor first',
          (App.config && App.config.fixturesOnly)
            ? 'The Demo market covers AAPL, SPY, QQQ, TSLA and VTSAX — try one of those.'
            : 'Simulations start from the active market lane\'s disclosed price. Check the ticker, or Data for source health.'));
      }
    });
  }

  var EARNINGS_RE = /earnings|quarterly results|guidance|earnings call/i;

  /**
   * "Coming up" — the dated events that can move a symbol: option expirations, earnings
   * signals from headlines, and the latest SEC filing. One helper, two shapes: a full card
   * on Research, a slim strip above single-symbol trade ideas. Fills itself; never blank —
   * the card form shows an honest note when nothing dated is known.
   */
  function comingUp(symbol, asStrip, preExpirations) {
    var body = el('div', { class: 'chip-row' });
    var host = asStrip
      ? el('div', { class: 'events-strip', id: 'events-strip' }, body)
      : el('div', { class: 'card', id: 'events-card' }, UI.cardHeader('Coming up for ' + symbol), body,
          Learn.currentLevel() === 'beginner'
            ? explain('Dates that can move the stock and its options: expiration days (contracts die at 4pm ET), earnings mentions from headlines, and official SEC filings.')
            : null);
    (async function fill() {
      var had = false;
      // Expirations + news are independent \u2014 fetch them together. If the caller already
      // has the expirations (the research page loads them with the quote), skip that call.
      var exP = (preExpirations && preExpirations.length)
        ? Promise.resolve({ expirations: preExpirations })
        : API.get('/api/research/' + symbol + '/expirations').catch(function () { return { expirations: [] }; });
      var newsP = API.get('/api/research/' + symbol + '/news').catch(function () { return { items: [] }; });
      try {
        var ex = (await exP).expirations || [];
        var now = Date.now();
        ex.slice(0, 3).forEach(function (d) {
          var dte = Math.max(0, Math.round((new Date(d + 'T16:00:00') - now) / 86400000));
          body.appendChild(chip('Expiry', d + ' \u00B7 ' + dte + 'd'));
          had = true;
        });
      } catch (e) { /* chips below may still land */ }
      // The ESTIMATED earnings window from filing cadence — a real (labeled) date, not a keyword.
      try {
        var rsch = await API.get('/api/research/' + symbol).catch(function () { return null; });
        var ee = rsch && rsch.earningsEstimate;
        if (ee && ee.date) {
          body.appendChild(chip(ee.confirmed ? 'Earnings' : 'Earnings (est.)', '~' + ee.date + ' \u00b1' + ee.windowDays + 'd',
            (ee.confirmed ? 'Confirmed date.' : 'ESTIMATED from ' + ee.basis + ' \u2014 not a confirmed calendar date.')));
          had = true;
        }
      } catch (e) { /* estimate is additive */ }
      try {
        var items = (await newsP).items || [];
        var earn = items.find(function (n) { return EARNINGS_RE.test(n.headline || ''); });
        if (earn) {
          body.appendChild(el('a', { href: earn.url, target: '_blank', rel: 'noopener', class: 'chip', title: earn.headline },
            el('span', { class: 'chip-label' }, 'Earnings'), el('b', {}, 'in the news')));
          had = true;
        }
        var filings = items.filter(function (n) { return /edgar|sec/i.test(n.source || '') || /filing$/i.test(n.headline || ''); })
          .sort(function (a, b) { return (b.publishedEpochMs || 0) - (a.publishedEpochMs || 0); });
        if (filings.length) {
          var f = filings[0];
          var when = f.publishedEpochMs ? new Date(f.publishedEpochMs).toISOString().slice(0, 10) : '';
          body.appendChild(el('a', { href: f.url, target: '_blank', rel: 'noopener', class: 'chip', title: f.headline },
            el('span', { class: 'chip-label' }, 'Last filing'), el('b', {}, f.headline.replace(/ filing$/i, '') + (when ? ' \u00B7 ' + when : ''))));
          had = true;
        }
      } catch (e) { /* fine */ }
      if (!had) {
        if (asStrip) { host.remove(); return; } // decoration in Trade — vanish quietly
        body.appendChild(el('span', { class: 'muted' }, 'No dated events known right now \u2014 demo mode carries limited news.'));
      }
    })();
    return host;
  }

  /**
   * History fetcher shared by Research and every symbolPanel: same ranges, same badges,
   * and an HONEST reason when a symbol has no candles — "not enough data for this window"
   * blamed the window when the truth is there is no daily-candle source without a key
   * (Cboe covers quotes/chains only; Stooq blocks non-browser clients).
   */
  function historyFetch(symbol) {
    return async function (range) {
      var hist = await API.get('/api/research/' + symbol + '/history?range=' + range);
      var candles = hist.candles || [];
      var closeOnly = hist.barBasis === 'CLOSE_ONLY' || hist.barBasis === 'MIXED';
      return {
        range: hist.range,
        candles: closeOnly ? null : candles,
        series: closeOnly ? candles.map(function (c) { return { date: String(c.date), value: parseFloat(c.close) }; }) : null,
        badge: UI.evidenceBadge(hist.evidence),
      note: hist.evidence && hist.evidence.provenance === 'DEMO'
          ? explain('This chart is fabricated teaching data from the explicit Demo market. It is not a substitute for observed history.')
          : hist.evidence && hist.evidence.provenance === 'MODELED'
            ? explain('This chart is a generated Scenario dataset. Its modeled path is separate from observed market history.')
            : hist.evidence && hist.evidence.provenance === 'OBSERVED' && candles.length
              ? explain((closeOnly
                  ? 'These are observed closing prices through ' + candles[candles.length - 1].date
                    + '. Daily highs and lows were not in the source and are not drawn as candles. '
                  : 'These are stored historical OHLC observations through ' + candles[candles.length - 1].date + '. ')
                  + (hist.priceBasis === 'ADJUSTED' ? 'Prices are adjusted for corporate actions. ' : '')
                  + 'They remain available when the market is closed; closed means no new session updates, not that past observations disappear.')
              : null,
        emptyText: candles.length ? null
          : (App.config && App.config.fixturesOnly)
            ? 'Demo mode ships price history for AAPL, SPY, QQQ and TSLA only \u2014 try one of those.'
            : Learn.currentLevel() === 'beginner'
              ? 'Observed daily history is unavailable for ' + symbol + '. Quotes and option chains may still work; Data explains what each source provides.'
              : 'No observed daily history for ' + symbol + '. Connect or backfill an eligible candle source from Data → Sources & jobs.'
      };
    };
  }

  function missingSparkRow(symbol, note) {
    return { symbol: symbol, available: false, closes: [], note: note,
      evidence: { provenance: 'MISSING', age: 'MISSING', source: 'daily history unavailable' } };
  }

  /**
   * Viewport-governed sparkline loader. One API batch is capped at 16 so optional chart
   * decoration cannot fan a large universe into a provider burst. Cards enqueue only when
   * visible or near-visible; scrolling therefore completes large simulated worlds instead
   * of leaving every card after number 16 blank.
   */
  function lazySparklines(container, symbols, opts) {
    opts = opts || {};
    symbols = (symbols || []).slice();
    var pending = new Set(), generation = 0, pumping = false, pumpScheduled = false, observer = null;

    function slotFor(sym) {
      return container.querySelector('.sym-card[data-sym="' + sym + '"] .spark-slot');
    }
    function markQueued(sym) {
      var slot = slotFor(sym);
      if (!slot) return;
      slot.innerHTML = '';
      slot.appendChild(el('div', { class: 'spark spark-loading' },
        el('span', { class: 'muted' }, 'chart queued')));
    }
    function enqueue(sym) {
      if (!sym || !container.isConnected || (opts.valid && !opts.valid())) return;
      pending.add(sym);
      if (!pumpScheduled) {
        pumpScheduled = true;
        Promise.resolve().then(function () { pumpScheduled = false; pump(); });
      }
    }
    async function pump() {
      if (pumping) return;
      pumping = true;
      var activeBatch = [];
      try {
        while (pending.size && container.isConnected && (!opts.valid || opts.valid())) {
          var mine = generation;
          var batch = Array.from(pending).slice(0, 16);
          activeBatch = batch;
          batch.forEach(function (sym) { pending.delete(sym); });
          var data = await API.prefetch('/api/sparklines?symbols='
            + encodeURIComponent(batch.join(',')) + '&range=' + encodeURIComponent(opts.range()));
          if (mine !== generation || !container.isConnected || (opts.valid && !opts.valid())) continue;
          if (!data) {
            batch.forEach(function (sym) {
              if (opts.paint) opts.paint(missingSparkRow(sym,
                'Chart deferred to protect interactive market requests. Open analysis to try on demand.'));
              var card = container.querySelector('.sym-card[data-sym="' + sym + '"]');
              if (observer && card) observer.unobserve(card);
            });
            activeBatch = [];
            break;
          }
          var returned = {};
          (data.sparklines || []).forEach(function (row) {
            returned[row.symbol] = true;
            if (opts.paint) opts.paint(row);
            var card = container.querySelector('.sym-card[data-sym="' + row.symbol + '"]');
            if (observer && card) observer.unobserve(card);
          });
          batch.forEach(function (sym) {
            if (returned[sym]) return;
            if (opts.paint) opts.paint(missingSparkRow(sym, 'Daily history was not returned for this symbol.'));
          });
          activeBatch = [];
        }
      } catch (e) {
        activeBatch.forEach(function (sym) {
          if (opts.paint) opts.paint(missingSparkRow(sym, 'Chart unavailable right now; the quote remains usable.'));
          var card = container.querySelector('.sym-card[data-sym="' + sym + '"]');
          if (observer && card) observer.unobserve(card);
        });
      } finally {
        pumping = false;
        if (pending.size && container.isConnected && (!opts.valid || opts.valid())) pump();
      }
    }
    function startObserving() {
      if (observer) observer.disconnect();
      if (window.IntersectionObserver) {
        observer = new IntersectionObserver(function (entries) {
          entries.forEach(function (entry) {
            if (entry.isIntersecting) enqueue(entry.target.getAttribute('data-sym'));
          });
        }, { rootMargin: '240px 0px' });
        symbols.forEach(function (sym) {
          var card = container.querySelector('.sym-card[data-sym="' + sym + '"]');
          if (card) observer.observe(card);
        });
      } else {
        symbols.forEach(enqueue);
      }
    }
    function reload() {
      generation++;
      pending.clear();
      symbols.forEach(markQueued);
      startObserving();
    }
    reload();
    return {
      reload: reload,
      disconnect: function () { generation++; pending.clear(); if (observer) observer.disconnect(); }
    };
  }

  // One ETF proxy per sector — used anywhere a sector shows its day move
  var SECTOR_ETF_MAP = { TECH: 'XLK', SEMICONDUCTORS: 'SMH', HEALTHCARE: 'XLV', DEFENSE: 'ITA',
    STAPLES: 'XLP', DISCRETIONARY: 'XLY', ENERGY: 'XLE', FINANCIALS: 'XLF', INDUSTRIALS: 'XLI',
    COMMUNICATIONS: 'XLC', UTILITIES: 'XLU', CORE: 'SPY', ETFS: 'QQQ', DEMO: 'SPY' };

  /**
   * THE sector affordance, everywhere: one horizontally-scrolling rail of pills — sector
   * name + its ETF-proxy day move — with a single active state. Home's pulse, Research's
   * explorer, and Trade's scan scope all render THIS, so sectors look and behave the same
   * on every screen. Renders instantly; the day moves fill in when quotes answer.
   * opts: { id, active, onPick(sector) }
   */
  /** Honest word for study events: only observed history may be called REAL (P0 blocker). */
  function occurrenceWord(r) {
    if (r.observed) return 'real past occurrences';
    return r.evidence === 'DEMO_FIXTURE'
      ? 'DEMO-data occurrences (not real history)'
      : 'generated-scenario occurrences (not real history)';
  }

  /** Page-level history wording follows the active lane; labels never promote Demo/Scenario data. */
  function activeHistoryBasis() {
    if (App.config && App.config.scenarioMode) return {
      plain: 'the active generated Scenario history', expert: 'generated Scenario event study', observed: false
    };
    if (App.state.world === 'demo') return {
      plain: 'fabricated Demo history (not the real past)',
      expert: 'fabricated Demo history (not the real past)', observed: false
    };
    if (App.state.world && App.state.world !== 'observed') return {
      plain: 'this simulated market\'s generated history', expert: 'simulated-world event study', observed: false
    };
    return { plain: 'the stock\'s observed past', expert: 'observed-history event study', observed: true };
  }

  function sectorRail(opts) {
    opts = opts || {};
    var u = App.state.universe;
    var rail = el('div', { class: 'sector-rail', role: 'tablist' });
    var wrap = el('div', { class: 'sector-rail-wrap', id: opts.id || null });
    if (!u) return wrap;
    // Overflow must be VISIBLE: arrows appear whenever there is more rail off-screen
    // (a fade with no affordance reads as "the list just ends here").
    function arrow(dir) {
      return el('button', {
        class: 'rail-arrow rail-arrow-' + dir, type: 'button',
        'aria-label': dir === 'left' ? 'Scroll sectors left' : 'Scroll sectors right',
        onclick: function () {
          rail.scrollBy({ left: (dir === 'left' ? -1 : 1) * Math.max(160, rail.clientWidth * 0.7), behavior: 'smooth' });
        }
      }, dir === 'left' ? '\u2039' : '\u203A');
    }
    function syncOverflow() {
      var canLeft = rail.scrollLeft > 4;
      var canRight = rail.scrollLeft + rail.clientWidth < rail.scrollWidth - 4;
      wrap.classList.toggle('can-left', canLeft);
      wrap.classList.toggle('can-right', canRight);
    }
    rail.addEventListener('scroll', syncOverflow, { passive: true });
    if (window.ResizeObserver) new ResizeObserver(syncOverflow).observe(rail);
    setTimeout(syncOverflow, 0);
    wrap.appendChild(arrow('left'));
    wrap.appendChild(rail);
    wrap.appendChild(arrow('right'));
    var sectors = u.sectors.filter(function (sec) { return sec.key !== 'DEMO' || u.note; });
    var deltas = {};
    sectors.forEach(function (sec) {
      var d = el('span', { class: 'sr-delta' });
      deltas[sec.key] = d;
      rail.appendChild(el('button', {
        class: 'sector-chip' + (opts.active === sec.key ? ' active' : ''),
        'data-sector': sec.key, type: 'button', role: 'tab',
        'aria-selected': opts.active === sec.key ? 'true' : 'false',
        title: sec.symbols.length + ' symbols',
        onclick: function () {
          var me = this;
          rail.querySelectorAll('.sector-chip').forEach(function (x) {
            x.classList.toggle('active', x === me);
            x.setAttribute('aria-selected', x === me ? 'true' : 'false');
          });
          opts.onPick(sec);
        }
      }, el('span', {}, sec.label), d));
    });
    (async function fillDeltas() {
      try {
        var pairs = sectors.filter(function (sec) { return SECTOR_ETF_MAP[sec.key]; });
        var etfs = pairs.map(function (sec) { return SECTOR_ETF_MAP[sec.key]; })
          .filter(function (v, i, a) { return a.indexOf(v) === i; });
        var data = await API.get('/api/quotes?symbols=' + etfs.join(','));
        var byEtf = {};
        (data.quotes || []).forEach(function (q) { byEtf[q.symbol] = q; });
        pairs.forEach(function (sec) {
          var q = byEtf[SECTOR_ETF_MAP[sec.key]];
          if (!q || !q.prevClose) return;
          var pct = (parseFloat(q.last) - parseFloat(q.prevClose)) / parseFloat(q.prevClose) * 100;
          var d = deltas[sec.key];
          d.className = 'sr-delta ' + (pct >= 0 ? 'gain' : 'loss');
          d.textContent = (pct >= 0 ? '\u25B2' : '\u25BC') + Math.abs(pct).toFixed(2) + '%';
        });
      } catch (e) { /* the rail works without the decoration */ }
      syncOverflow(); // deltas widen the chips — re-measure
    })();
    return wrap;
  }

  /**
   * Rich symbol context in one reusable block: live quote row, the SAME interactive
   * range-pill candle chart Research uses, the coming-up events strip, and top headlines.
   * Expands from sector-explorer tiles and sits behind a quiet expandable in Trade —
   * one chart-and-news language everywhere.
   */
  /**
   * ONE SYMBOL CONTEXT (interaction contract / review IC-4). symbolStatus attaches a live
   * quote/freshness/lane chip to any EDITABLE symbol input: same look, same data, every
   * surface. Debounced, supersede-guarded; failures show honestly as 'no live quote'.
   */
  /**
   * LOCKED symbol context (interaction contract #5): a placed/reviewed package shows its
   * symbol but cannot silently mutate it — changing means going back through the workflow.
   */
  function lockedSymbolBar(symbol, opts) {
    opts = opts || {};
    return UI.symbolContext({
      mode: 'locked', symbol: symbol, id: opts.id || 'locked-symbol',
      label: opts.label || 'Position symbol',
      onResearch: function () { App.navigate('#/research/' + symbol); },
      onChange: opts.noChange ? null : function () {
        App.state.ideasPrefill = {};
        App.navigate('#/trade/discover');
      }
    });
  }

  function symbolPanel(symbol, opts) {
    opts = opts || {};
    symbol = symbol.toUpperCase();
    var panel = el('div', { class: 'symbol-panel', 'data-symbol': symbol });
    var qrow = el('div', { class: 'sp-quote' }, el('b', { class: 'sp-sym' }, symbol));
    panel.appendChild(qrow);
    (async function fillQuote() {
      try {
        var q = ((await API.get('/api/quotes?symbols=' + symbol)).quotes || [])[0];
        if (!q) return;
        qrow.appendChild(el('span', { class: 'sp-px' }, fmtNum(q.last)));
        qrow.appendChild(UI.delta(q.last, q.prevClose));
        if (q.freshness) qrow.appendChild(badge(q.freshness));
        if (q.description) qrow.appendChild(el('span', { class: 'muted sp-nm' }, q.description));
      } catch (e) { /* chart + news still render */ }
    })();
    panel.appendChild(UI.rangeChart({ initial: opts.range || '6m', fetch: historyFetch(symbol) }));
    panel.appendChild(comingUp(symbol, true));
    var newsBox = el('div', { class: 'sp-news' });
    panel.appendChild(newsBox);
    (async function fillNews() {
      try {
        var items = ((await API.get('/api/research/' + symbol + '/news')).items || []).slice(0, opts.newsCount || 3);
        if (!items.length) {
          newsBox.appendChild(el('p', { class: 'muted' }, 'No headlines right now.'));
          return;
        }
        items.forEach(function (n) {
          newsBox.appendChild(el('div', { class: 'status-item' },
            el('a', { href: n.url, target: '_blank', rel: 'noopener' }, n.headline),
            el('span', { class: 'spacer' }),
            el('span', { class: 'muted' }, n.source)));
        });
      } catch (e) {
        newsBox.appendChild(el('p', { class: 'muted' }, 'Headlines unavailable right now.'));
      }
    })();
    if (!opts.noActions) {
      // The panel lives where leaving the screen loses work (a Trade form); its one action
      // is the deliberate exit to the destination page — never a duplicate of the host form.
      panel.appendChild(el('div', { class: 'btn-row' },
        el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research/' + symbol); } }, 'Open full analysis')));
    }
    return panel;
  }

  /**
   * Sector explorer: dig into any sector from any tab — live quote tiles per symbol with
   * one-tap actions (Research / Ideas / Trade), and a one-click "make this my universe".
   * `context` seeds which action leads (research vs ideas).
   */
  async function sectorExplorer(root, context) {
    var u = App.state.universe;
    if (!u) { try { await App.refreshUniverse(); u = App.state.universe; } catch (e) { /* offline */ } }
    if (!u) { root.appendChild(UI.emptyState('Universe unavailable', 'Check the Data screen.')); return; }
    var selectedKey = App.state.explorerSector || u.active.sectorKey || u.sectors[0].key;

    var rail = sectorRail({
      id: 'sector-chips', active: selectedKey,
      onPick: function (sec) { selectedKey = sec.key; App.state.explorerSector = sec.key; load(); }
    });
    var grid = el('div', { class: 'tile-row', id: 'sector-grid' });
    var head = el('div', { class: 'btn-row', style: 'margin-top:0' });
    // ONE shared range selector for every card's sparkline (never per-card controls) + one
    // plain instruction instead of dozens of repeated buttons (review).
    App.state.explorerRange = App.state.explorerRange || '3m';
    var RANGES = [{ k: '1m', l: '1M' }, { k: '3m', l: '3M' }, { k: 'ytd', l: 'YTD' }];
    var rangeRow = el('div', { class: 'range-pills', id: 'spark-range', role: 'group', 'aria-label': 'Sparkline window' },
      RANGES.map(function (r) {
        return el('button', {
          class: 'pill' + (App.state.explorerRange === r.k ? ' active' : ''), type: 'button', 'data-range': r.k,
          onclick: function () {
            App.state.explorerRange = r.k;
            rangeRow.querySelectorAll('.pill').forEach(function (b) {
              b.classList.toggle('active', b.getAttribute('data-range') === r.k);
            });
            if (sparkLoader) sparkLoader.reload(); // same sector, new window; visible cards update in place
          }
        }, r.l);
      }));
    var instruction = el('p', { class: 'muted explorer-hint', id: 'explorer-hint' },
      'Choose a stock to open its full analysis \u2014 chart, events, headlines, chains and strategies.');
    var quoteBasis = App.state.world === 'demo'
      ? 'Fabricated Demo quotes for this teaching universe.'
      : App.state.world && App.state.world !== 'observed'
        ? 'Generated quotes from the active simulated market.'
        : 'Observed-source quotes for the selected sector.';
    var card = el('div', { class: 'card', id: 'sector-explorer' },
      UI.cardHeader('Explore by sector'), rail, head, instruction, rangeRow, grid,
      explain(quoteBasis + ' Tap a card to open the stock, or point the scout at a whole sector. '
        + (u.note ? u.note : '')));
    root.appendChild(card);

    var currentSector = null;
    var sparkLoader = null;

    var loadSeq = 0; // supersede token: a slow quote batch for a PREVIOUS sector must not paint over the current one
    async function load() {
      var seq = ++loadSeq;
      if (sparkLoader) { sparkLoader.disconnect(); sparkLoader = null; }
      head.innerHTML = '';
      grid.innerHTML = '';
      grid.appendChild(UI.spinner('Loading sector quotes…'));
      var sector = u.sectors.find(function (s) { return s.key === selectedKey; }) || u.sectors[0];
      currentSector = sector;
      head.appendChild(el('b', {}, sector.label));
      head.appendChild(el('span', { class: 'muted' }, sector.symbols.length + ' symbols'));
      head.appendChild(el('span', { class: 'spacer' }));
      if (u.active.sectorKey !== sector.key) {
        head.appendChild(el('button', {
          class: 'btn btn-sm btn-secondary', id: 'set-universe-btn', onclick: async function () {
            try {
              if (App.state.world && App.state.world !== 'observed') {
                if (UI.toast) UI.toast('You are in a simulated session \u2014 its symbols ARE the market here.');
                return;
              }
              await API.put('/api/universe', { sector: sector.key });
              await App.refreshUniverse();
              App.render();
            } catch (e) { UI.toast(e.message || 'Could not change the active universe', 'error'); }
          }
        }, 'Make this my universe'));
      } else {
        head.appendChild(el('span', { class: 'badge badge-ok' }, 'YOUR UNIVERSE'));
      }
      head.appendChild(el('button', {
        class: 'btn btn-sm', onclick: function () {
          App.state.discoverForm = Object.assign({}, App.state.discoverForm || {},
            { universe: sector.symbols.join(','), symbol: '' });
          App.navigate('#/recommend/scout');
        }
      }, 'Scout this sector'));
      try {
        var data = await API.get('/api/quotes?symbols=' + sector.symbols.join(','));
        if (seq !== loadSeq) return; // a newer sector was picked while this loaded
        grid.innerHTML = '';
        grid.setAttribute('data-count', String(sector.symbols.length));
        // EVERY symbol gets an actionable tile \u2014 a sector click must always land somewhere
        // clickable. Symbols without a live quote say so honestly but still link onward.
        var bySym = {};
        (data.quotes || []).forEach(function (q) { bySym[q.symbol] = q; });
        // DESTINATION CARDS NAVIGATE (interaction contract #1): a card that says AAPL opens
        // AAPL's full analysis in ONE action — the old in-place preview repeated a subset of
        // that page and cost an extra decision. Sector, range, and scroll are preserved so
        // Back lands exactly where you left.
        sector.symbols.forEach(function (s) {
          var q = bySym[s];
          var last = q ? parseFloat(q.last) : null;
          var prev = q ? parseFloat(q.prevClose) : null;
          var pct = q && prev ? (last - prev) / prev * 100 : null;
          var sparkSlot = el('div', { class: 'spark-slot' });
          var tile = el('a', { class: 'tile sector-tile sym-card clickable' + (q ? '' : ' tile-nodata'),
            'data-sym': s,
            href: '#/research/' + s,
            'aria-label': 'Open ' + s + ' full analysis',
            title: 'Open ' + s + ' \u2014 full analysis' },
            el('div', { class: 't-sym' }, s,
              q && !q.optionable ? el('span', { class: 'badge badge-dim', style: 'margin-left:6px' }, 'NO OPTIONS') : null,
              q ? null : el('span', { class: 'badge badge-dim', style: 'margin-left:6px' }, 'NO QUOTE')),
            el('div', { class: 't-px' }, q ? last.toFixed(2) : '\u2014'),
            pct === null
              ? el('div', { class: 'muted t-move' }, 'no quote right now')
              : el('div', { class: 't-move ' + (pct >= 0 ? 'gain' : 'loss') }, (pct >= 0 ? '\u25B2 ' : '\u25BC ') + Math.abs(pct).toFixed(2) + '%'),
            q && q.description ? el('div', { class: 't-nm muted' }, q.description) : null,
            sparkSlot,
            el('span', { class: 'destination-cue', 'aria-hidden': 'true' }, icon('chevron-right', 16)));
          tile.addEventListener('click', function (e) {
            // The sparkline is a deliberate interaction SUBREGION (hover/keys explore the
            // chart) — clicking it must not yank the user off the page.
            if (e.target.closest('.spark')) { e.preventDefault(); return; }
            App.state.explorerScroll = window.scrollY; // Back restores the exact spot
          });
          tile.addEventListener('keydown', function (e) {
            var spark = sparkSlot.firstChild;
            if (spark && spark.exploreKey && spark.exploreKey(e.key)) { e.preventDefault(); }
          });
          grid.appendChild(tile);
        });
        // Return continuity: coming Back from a symbol page lands on the same scroll spot.
        if (App.state.explorerScroll != null) {
          var y = App.state.explorerScroll;
          App.state.explorerScroll = null;
          requestAnimationFrame(function () { window.scrollTo(0, y); });
        }
        sparkLoader = lazySparklines(grid, sector.symbols, {
          range: function () { return App.state.explorerRange; },
          valid: function () { return seq === loadSeq && grid.isConnected; },
          paint: function (row) {
            var t2 = grid.querySelector('.sym-card[data-sym="' + row.symbol + '"] .spark-slot');
            if (!t2) return;
            t2.innerHTML = '';
            t2.appendChild(UI.sparkline(row, { height: 36 }));
            // Evidence is server-computed and rendered verbatim; availability never implies Observed.
            if (row.evidence && !(App.state.world === 'demo' && row.evidence.provenance === 'DEMO')) {
              t2.appendChild(UI.evidenceBadge(row.evidence, { className: 'spark-ev', compact: true }));
            }
          }
        });
        // LIVE CARDS (review gap): prices follow the market stream in place — a simulated
        // session's ticks repaint the grid without rebuilding it.
        App.Market.subscribe(function (frame) {
          (frame.quotes ? Object.keys(frame.quotes) : []).forEach(function (symKey) {
            var t2 = grid.querySelector('.sym-card[data-sym="' + symKey + '"]');
            if (!t2) return;
            var fq = frame.quotes[symKey];
            var lastF = parseFloat(fq.last), prevF = parseFloat(fq.prevClose);
            var px = t2.querySelector('.t-px');
            if (px && isFinite(lastF)) {
              if (px.textContent !== lastF.toFixed(2)) {
                px.textContent = lastF.toFixed(2);
                t2.classList.remove('live-tick-up', 'live-tick-down');
                void t2.offsetWidth; // restart the tint animation
                t2.classList.add(lastF >= prevF ? 'live-tick-up' : 'live-tick-down');
              }
            }
            var mv = t2.querySelector('.t-move');
            if (mv && isFinite(lastF) && isFinite(prevF) && prevF) {
              var p2 = (lastF - prevF) / prevF * 100;
              mv.className = 't-move ' + (p2 >= 0 ? 'gain' : 'loss');
              mv.textContent = (p2 >= 0 ? '\u25B2 ' : '\u25BC ') + Math.abs(p2).toFixed(2) + '%';
            }
          });
        }, App.navToken);
        if (!(data.quotes || []).length && u.note) {
          grid.appendChild(explain('Demo mode carries data only for the built-in symbols \u2014 each ticker still links to research and ideas so you can explore the flow.'));
        }
      } catch (e) {
        if (seq !== loadSeq) return;
        grid.innerHTML = '';
        grid.appendChild(alertBox('warn', 'Could not load sector quotes', [e.message]));
      }
    }
    await load();
  }

  /** Recently researched symbols — the lookup shortcuts that actually mean something. */
  function recentSymbols() {
    try { return JSON.parse(window.localStorage.getItem('strikebench.recent') || '[]'); }
    catch (e) { return []; }
  }
  function rememberRecent(sym) {
    try {
      var list = recentSymbols().filter(function (s2) { return s2 !== sym; });
      list.unshift(sym);
      window.localStorage.setItem('strikebench.recent', JSON.stringify(list.slice(0, 6)));
    } catch (e) { /* cosmetic */ }
  }

  function daysUntil(iso) {
    var d = Math.round((new Date(iso + 'T12:00:00') - Date.now()) / 86400000);
    return d < 0 ? 0 : d;
  }

  // ---------- 3. Recommendations ----------

  /** Expandable "How this trade works" block from the per-strategy guide. */
  function guideBlock(strategy, open) {
    var g = Learn.STRATEGY_GUIDE[strategy];
    if (!g) return null;
    return UI.expandable('How this trade works — and what can go wrong', function () {
      return el('div', {},
        el('p', {}, el('i', {}, g.story)),
        el('ol', {}, g.how.map(function (s) { return el('li', {}, s); })),
        el('div', { class: 'fact-row' },
          el('span', {}, el('b', { class: 'gain' }, 'You win when: '), g.win)),
        el('div', { class: 'fact-row' },
          el('span', {}, el('b', { class: 'loss' }, 'You lose when: '), g.lose)),
        el('p', {}, el('b', {}, 'Easy to miss: '), g.watch));
    }, { open: !!open });
  }

  /** One-line human framing of the candidate against the user's goal, shown on every level. */
  function intentNoteBlock(c) {
    if (!c.intentNote) return null;
    return el('div', { class: 'intent-note' }, c.intentNote);
  }

  function heldSharesBadge(c) {
    if (!c.usesHeldShares) return null;
    return el('span', { class: 'badge badge-caution' },
      'USES ' + (c.sharesNeeded ? c.sharesNeeded + ' ' : '') + 'HELD SHARES');
  }

  function economicVerdict(c) {
    return (c && (c.economicVerdict || (c.economics && c.economics.verdict))) || null;
  }

  function economicAssessmentBlock(c) {
    var e = c && c.economics;
    if (!e) return null;
    var v = economicVerdict(c);
    var cls = v === 'FAVORABLE' ? 'economic-favorable'
      : v === 'UNFAVORABLE' ? 'economic-unfavorable'
      : v === 'UNAVAILABLE' ? 'economic-unavailable' : 'economic-mixed';
    var block = el('div', { class: 'economic-assessment ' + cls, 'data-economic-verdict': v },
      el('div', { class: 'economic-assessment-head' },
        el('span', { class: 'badge' }, e.label || v)),
      el('p', {}, e.summary || ''),
      el('div', { class: 'chip-row economic-ev-lanes' },
        chip(el('span', {}, 'Market-implied EV', UI.info('ev')),
          e.marketEvAfterCostsCents !== null && e.marketEvAfterCostsCents !== undefined
            ? el('span', { class: e.marketEvAfterCostsCents >= 0 ? 'gain' : 'loss' },
                fmtMoney(e.marketEvAfterCostsCents, { plus: true })) : 'Unavailable',
          'Expected result under the option market’s implied distribution, after estimated round-trip fees.'),
        chip(el('span', {}, 'Realized-vol scenario EV', UI.info('vrp')),
          e.realizedVolEvAfterCostsCents !== null && e.realizedVolEvAfterCostsCents !== undefined
            ? el('span', { class: e.realizedVolEvAfterCostsCents >= 0 ? 'gain' : 'loss' },
                fmtMoney(e.realizedVolEvAfterCostsCents, { plus: true })) : 'Unavailable',
          'Expected result if this stock’s observed realized volatility is the better guide, after estimated round-trip fees.'),
        chip('Estimated round-trip fees', fmtMoney(e.estimatedRoundTripFeesCents || 0))));
    if (e.reasons && e.reasons.length) {
      block.appendChild(UI.expandable('Why this classification', function () {
        return el('ul', { class: 'rationale' }, e.reasons.map(function (r) { return el('li', {}, r); }));
      }));
    }
    return block;
  }

  /** Learning-level card: plain language first, numbers second, mechanics on tap. */
  function beginnerCandidateCard(c, withUse, symbolForTicket) {
    var g = Learn.STRATEGY_GUIDE[c.strategy] || {};
    var assignGoal = c.intent === 'EXIT' || c.intent === 'ACQUIRE';
    var maxLossFact = c.usesHeldShares && c.maxLossCents === 0
        ? el('div', { class: 'fact' },
            el('div', { class: 'f-label' }, UI.term('covered', 'New cash at risk')),
            el('div', { class: 'f-value' }, '$0'))
        : el('div', { class: 'fact f-danger' },
            el('div', { class: 'f-label' }, UI.term('max loss', 'Theoretical worst case')),
            el('div', { class: 'f-value' }, fmtMoney(c.maxLossCents)));
    var thirdFact = assignGoal && c.assignmentProb !== null && c.assignmentProb !== undefined
        ? el('div', { class: 'fact f-ok' },
            el('div', { class: 'f-label' }, UI.term('assignment', c.intent === 'EXIT' ? 'Chance you sell' : 'Chance you buy')),
            el('div', { class: 'f-value' }, fmtPct(c.assignmentProb)))
        : el('div', { class: 'fact' },
            el('div', { class: 'f-label' }, UI.term('pop', 'Chance of any profit')),
            el('div', { class: 'f-value' }, fmtPct(c.pop)));
    var card = el('div', { class: 'candidate', 'data-strategy': c.strategy,
      'data-economic-verdict': economicVerdict(c) || 'UNKNOWN' },
      el('div', { class: 'head' },
        el('h3', {}, c.displayName),
        intentBadge(c.intent),
        heldSharesBadge(c),
        badge(c.freshness)),
      g.story ? el('div', { class: 'muted', style: 'margin:2px 0 4px' }, g.story) : null,
      intentNoteBlock(c),
      el('div', { class: 'fact-grid' },
        maxLossFact,
        el('div', { class: 'fact f-ok' },
          el('div', { class: 'f-label' }, UI.term('max profit', 'Theoretical ceiling')),
          el('div', { class: 'f-value' }, UI.maxProfitLabel(
            c.strategy, c.structureGroup, c.maxProfitCents, true, c.legs))),
        thirdFact),
      el('p', { style: 'margin:6px 0' },
        c.entryNetPremiumCents >= 0
          ? el('span', {}, 'You collect ', el('b', { class: 'gain' }, fmtMoney(c.entryNetPremiumCents)), ' up front (a ', UI.term('credit'), ').')
          : el('span', {}, 'You pay ', el('b', {}, fmtMoney(-c.entryNetPremiumCents)), ' up front (a ', UI.term('debit'), ').'),
        (c.breakevens || []).length
          ? el('span', {}, ' The ', UI.term('breakeven'), ' is at ', el('b', {}, c.breakevens.map(fmtBreakeven).join(' / ')), '.')
          : null));
    var econ = economicAssessmentBlock(c);
    if (econ) card.insertBefore(econ, card.children[2] || null);
    var gb = guideBlock(c.strategy);
    // Beginner's first disclosure must explain the structure itself. The economic verdict
    // remains prominent above the facts, while its deeper scoring rationale follows the
    // plain-language win/loss mechanics instead of displacing them.
    if (gb) card.insertBefore(gb, econ || card.children[2] || null);
    if (window.Scenario) card.appendChild(Scenario.realisticOutcomes(symbolForTicket || App.context.symbol(), c));
    card.appendChild(UI.expandable('The exact contracts \u2014 ' + c.qty + ' lot' + (c.qty > 1 ? 's' : '') + ' (each line \u00d7' + c.qty + ')', function () {
      return el('div', {},
        el('div', { class: 'mono', style: 'margin-bottom:6px' }, c.label),
        el('ul', {}, c.legs.map(function (l) { return el('li', {}, legLabel(l)); })),
        el('p', {}, 'Prices shown are the executable ', UI.term('bid/ask', 'bid/ask'), ' sides — what a fill would actually cost right now.'));
    }));
    if (c.warnings && c.warnings.length) card.appendChild(alertBox('warn', 'Before you decide', c.warnings));
    card.appendChild(el('div', { class: 'btn-row' },
      withUse ? el('button', {
        class: 'btn', onclick: function () {
          App.state.ticket = { world: App.state.world || 'observed', candidate: c, symbol: symbolForTicket || App.context.symbol(), step: 5 };
          App.navigate('#/trade/place');
        }
      }, 'Practice this trade') : null));
    return card;
  }

  function candidateCard(c, withUse, symbolForTicket) {
    if (Learn.currentLevel() === 'beginner') return beginnerCandidateCard(c, withUse, symbolForTicket);
    var card = el('div', { class: 'candidate', 'data-strategy': c.strategy,
      'data-economic-verdict': economicVerdict(c) || 'UNKNOWN' },
      el('div', { class: 'head' },
        el('h3', {}, c.displayName),
        intentBadge(c.intent),
        heldSharesBadge(c),
        badge(c.freshness),
        UI.scoreBar(c.score)),
      el('div', { class: 'label-line' }, c.label + '  ·  qty ' + c.qty),
      intentNoteBlock(c),
      el('div', { class: 'chip-row' },
        chip('Cost/credit', fmtMoney(c.entryNetPremiumCents, { plus: true })),
        chip('Theoretical max loss', el('span', { class: 'loss' }, fmtMoney(c.maxLossCents))),
        c.combinedMaxLossCents !== null && c.combinedMaxLossCents !== undefined
          ? chip('Theoretical worst case w/ shares', el('span', { class: 'loss' }, fmtMoney(c.combinedMaxLossCents))) : null,
        chip('Theoretical max profit', UI.maxProfitLabel(
          c.strategy, c.structureGroup, c.maxProfitCents, false, c.legs)),
        chip(el('span', {}, 'POP', UI.info('pop')), fmtPct(c.pop)),
        c.assignmentProb !== null && c.assignmentProb !== undefined
          ? chip(el('span', {}, 'Assignment', UI.info('assignment')), fmtPct(c.assignmentProb)) : null,
        c.annualizedYieldPct !== null && c.annualizedYieldPct !== undefined ? chip('Yield/yr', fmtNum(c.annualizedYieldPct, 1) + '%') : null,
        c.effectivePrice ? chip(c.intent === 'ACQUIRE' ? 'Effective buy' : 'Effective sell', '$' + c.effectivePrice) : null,
        chip('Breakeven', (c.breakevens || []).map(fmtBreakeven).join(' / ') || '—'),
        chip('Confidence', fmtPct(c.confidence))),
      explain(c.beginnerExplanation));
    var econ = economicAssessmentBlock(c);
    if (econ) card.insertBefore(econ, card.children[2] || null);
    card.appendChild(el('div', { class: 'chip-row expert-only' },
      c.decisionScore !== null && c.decisionScore !== undefined
        ? chip(el('span', {}, 'Decision score', UI.info('decisionscore')), fmtNum(c.decisionScore, 0)) : null,
      c.expectedValueCents !== null && c.expectedValueCents !== undefined
        ? chip(el('span', {}, 'Model EV', UI.info('ev')), fmtMoney(c.expectedValueCents, { plus: true })) : null,
      chip('Liquidity', fmtNum(c.liquidityScore, 2)),
      chip(el('span', {}, 'Screen score', UI.info('screenscore')), fmtNum(c.score, 0))));
    if (window.Scenario) card.appendChild(Scenario.realisticOutcomes(symbolForTicket || App.context.symbol(), c));
    if (c.warnings && c.warnings.length) card.appendChild(alertBox('warn', 'Heads up', c.warnings));
    card.appendChild(el('details', { class: 'qa' },
      el('summary', {}, 'Why this idea — and what would kill it'),
      el('dl', {},
        el('dt', {}, 'Why considered'), el('dd', {}, c.whyConsidered),
        el('dt', {}, 'Best upside'), el('dd', {}, c.bestUpside),
        el('dt', {}, 'Biggest risk'), el('dd', {}, c.biggestRisk),
        el('dt', {}, 'What would invalidate it'), el('dd', {}, c.wouldInvalidate))));
    if (withUse) {
      card.appendChild(el('div', { class: 'btn-row' },
        el('button', {
          class: 'btn', onclick: function () {
            App.state.ticket = { world: App.state.world || 'observed', candidate: c, symbol: symbolForTicket || App.context.symbol(), step: 5 };
            App.navigate('#/trade/place');
          }
        }, 'Use in trade ticket'),
        el('button', {
          class: 'btn btn-sm btn-secondary', onclick: function () {
            App.state.builderForm = {
              symbol: symbolForTicket || App.context.symbol(),
              qty: c.qty || 1, goal: null, templateKey: null, step: 4, legIdx: 0, excluded: {},
              legs: (c.legs || []).map(function (l) {
                return { action: l.action, type: l.stock ? 'STOCK' : l.type,
                         strike: l.stock ? null : String(l.strike),
                         expiration: l.stock ? null : l.expiration, ratio: l.ratio || 1 };
              })
            };
            App.navigate('#/trade/shape');
          }
        }, 'Open in builder'),
        canBacktest(c.strategy) ? el('button', {
          class: 'btn btn-sm btn-secondary', onclick: function () {
            App.state.backtestPrefill = { symbol: symbolForTicket || App.context.symbol(), strategy: c.strategy };
            App.navigate('#/trade/verify');
          }
        }, 'Backtest this') : null));
    }
    return card;
  }

  function strategyMeta(name) {
    return ((App.strategyCatalog && App.strategyCatalog.catalog) || []).find(function (m) { return m.name === name; }) || null;
  }

  function canBacktest(name) {
    var meta = strategyMeta(name);
    return !!(meta && meta.backtestEnabled);
  }

  var THESIS_BADGE = { BULLISH: 'badge-ok', BEARISH: 'badge-danger', NEUTRAL: 'badge-dim', VOLATILE: 'badge-warn' };

  /** Pro: side-by-side strategy comparison — sortable columns, expandable detail rows. */
  /**
   * Beginner candidate list: ranked cards with rank numbers. Long lists open with DIVERSE
   * representatives (max 2 per structure shape, top 5 of the engine's order) plus
   * 'Show all N ranked strategies' — diversity is presentation, the ranking is the truth.
   */
  function renderRankedCards(results, candidates, opts) {
    opts = opts || {};
    var host = el('div', { id: opts.id || 'ranked-cards' });
    results.appendChild(host);
    function rankBadge(i) {
      return el('span', { class: 'badge badge-dim rank-badge', title: 'Rank in this list\u2019s ordering (best first)' }, '#' + (i + 1));
    }
    function paint(showAll) {
      host.innerHTML = '';
      var shown = [];
      if (showAll || candidates.length <= 5) {
        candidates.forEach(function (c, i) { shown.push({ c: c, rank: (c._servedRank || (i + 1)) - 1 }); });
      } else {
        var perGroup = {};
        for (var i = 0; i < candidates.length && shown.length < 5; i++) {
          var g = candidates[i].structureGroup || 'other';
          var n = perGroup[g] || 0;
          if (n >= 2) continue;
          perGroup[g] = n + 1;
          shown.push({ c: candidates[i], rank: (candidates[i]._servedRank || (i + 1)) - 1 });
        }
      }
      shown.forEach(function (x) {
        var card = candidateCard(x.c, true);
        var head = card.querySelector('h3, .cand-head, .card-head') || card.firstChild;
        if (head && head.insertBefore) head.insertBefore(rankBadge(x.rank), head.firstChild);
        else card.insertBefore(rankBadge(x.rank), card.firstChild);
        host.appendChild(card);
      });
      if (!showAll && shown.length < candidates.length) {
        host.appendChild(el('div', { class: 'btn-row' }, el('button', {
          class: 'btn btn-secondary', id: 'show-all-ranked',
          onclick: function () { paint(true); }
        }, 'Show all ' + candidates.length + ' ranked strategies')));
      }
    }
    paint(candidates.length <= 5);
  }

  /** Beginner keeps the complete catalog, but endorsement and teaching cases no longer blur. */
  function renderEconomicGroups(results, candidates) {
    candidates.forEach(function (c, i) { c._servedRank = i + 1; });
    var primary = candidates.filter(function (c) {
      var v = economicVerdict(c);
      return v !== 'UNFAVORABLE' && v !== 'UNAVAILABLE';
    });
    var learning = candidates.filter(function (c) {
      var v = economicVerdict(c);
      return v === 'UNFAVORABLE' || v === 'UNAVAILABLE';
    });
    if (primary.length) {
      results.appendChild(el('h3', { class: 'economic-group-title' }, 'Candidates to investigate'));
      renderRankedCards(results, primary, { id: 'ranked-cards-primary' });
    }
    if (learning.length) {
      var section = el('details', { class: 'card economic-learning-group', open: primary.length ? null : '' },
        el('summary', {}, 'Learn from ' + learning.length + ' trade-off' + (learning.length === 1 ? '' : 's')));
      var body = el('div', { class: 'economic-learning-body' });
      section.appendChild(body);
      learning.forEach(function (c) {
        var card = candidateCard(c, true);
        var head = card.querySelector('h3') || card.firstChild;
        if (head && head.insertBefore) head.insertBefore(
          el('span', { class: 'badge badge-dim rank-badge' }, '#' + c._servedRank), head.firstChild);
        body.appendChild(card);
      });
      results.appendChild(section);
    }
  }

  function comparisonTable(candidates) {
    var sortKey = 'rank', sortDir = 1;
    // The served order IS the ranking (decision-ranked; screen order on fallback) — stamp it
    // once so re-sorting by any column keeps the true rank visible on every row (review P1).
    var rankOf = new Map();
    candidates.forEach(function (c, i) { rankOf.set(c, i + 1); });
    function rrValue(c) {
      var denom = c.maxLossCents > 0 ? c.maxLossCents : (c.combinedMaxLossCents || 0);
      if (denom <= 0) return -1;
      var k = UI.profitCeilingKind(c.strategy, c.structureGroup, c.maxProfitCents, c.legs);
      return k === 'uncapped' ? Infinity : k === 'model-dependent' ? -1 : c.maxProfitCents / denom;
    }
    var COLS = [
      { key: 'rank', label: '#', get: function (c) { return rankOf.get(c); }, render: function (c) { return el('span', { class: 'muted', title: 'Rank in the served ordering (best first)' }, '#' + rankOf.get(c)); } },
      { key: 'economicVerdict', label: 'Economic view', get: function (c) { var v = economicVerdict(c); return v === 'FAVORABLE' ? 3 : v === 'MIXED' ? 2 : v === 'UNFAVORABLE' ? 1 : 0; }, render: function (c) { var v = economicVerdict(c); return el('span', { class: 'badge economic-table-' + String(v || 'unknown').toLowerCase() }, (c.economics && c.economics.label) || v || '—'); } },
      { key: 'displayName', label: 'Strategy', get: function (c) { return c.displayName; }, render: function (c) { return el('b', {}, c.displayName); } },
      { key: 'entryNetPremiumCents', label: 'Cost/Credit', get: function (c) { return c.entryNetPremiumCents; }, render: function (c) { return pnlSpan(c.entryNetPremiumCents); } },
      { key: 'maxLossCents', label: 'Theor. max loss', get: function (c) { return c.usesHeldShares && c.combinedMaxLossCents ? c.combinedMaxLossCents : c.maxLossCents; }, render: function (c) { return c.usesHeldShares && c.maxLossCents === 0 ? el('span', {}, '$0*') : el('span', { class: 'loss' }, fmtMoney(c.maxLossCents)); } },
      { key: 'maxProfitCents', label: 'Theor. max profit', get: function (c) { var k = UI.profitCeilingKind(c.strategy, c.structureGroup, c.maxProfitCents, c.legs); return k === 'uncapped' ? Infinity : k === 'model-dependent' ? -Infinity : c.maxProfitCents; }, render: function (c) { var k = UI.profitCeilingKind(c.strategy, c.structureGroup, c.maxProfitCents, c.legs); return k === 'model-dependent' ? el('span', { class: 'muted' }, 'model-dependent') : k === 'uncapped' ? el('span', { class: 'gain' }, '\u221E') : el('span', { class: 'gain' }, fmtMoney(c.maxProfitCents)); } },
      { key: 'rr', label: 'R:R', get: rrValue, render: function (c) { var v = rrValue(c); return el('span', {}, v === -1 ? '\u2014' : v === Infinity ? '\u221E' : fmtNum(v, 2)); } },
      { key: 'pop', label: 'POP', get: function (c) { return c.pop === null || c.pop === undefined ? -1 : c.pop; }, render: function (c) { return el('span', {}, fmtPct(c.pop)); } },
      { key: 'expectedValueCents', label: 'EV', get: function (c) { return c.expectedValueCents === null || c.expectedValueCents === undefined ? -Infinity : c.expectedValueCents; }, render: function (c) { return c.expectedValueCents === null || c.expectedValueCents === undefined ? el('span', {}, '\u2014') : pnlSpan(c.expectedValueCents); } },
      { key: 'breakevens', label: 'Breakevens', get: function (c) { return (c.breakevens || []).length ? parseFloat(c.breakevens[0]) : 0; }, render: function (c) { return el('span', { class: 'mono' }, (c.breakevens || []).map(fmtBreakeven).join(' / ') || '\u2014'); } },
      { key: 'assignmentProb', label: 'Assign%', get: function (c) { return c.assignmentProb === null || c.assignmentProb === undefined ? -1 : c.assignmentProb; }, render: function (c) { return el('span', {}, c.assignmentProb === null || c.assignmentProb === undefined ? '\u2014' : fmtPct(c.assignmentProb)); } },
      { key: 'annualizedYieldPct', label: 'Yield/yr', get: function (c) { return c.annualizedYieldPct === null || c.annualizedYieldPct === undefined ? -1 : c.annualizedYieldPct; }, render: function (c) { return el('span', {}, c.annualizedYieldPct === null || c.annualizedYieldPct === undefined ? '\u2014' : fmtNum(c.annualizedYieldPct, 1) + '%'); } },
      { key: 'liquidityScore', label: 'Liq', get: function (c) { return c.liquidityScore; }, render: function (c) { return el('span', {}, fmtNum(c.liquidityScore, 2)); } },
      { key: 'decisionScore', label: 'Decision score', get: function (c) { return c.decisionScore; }, render: function (c) { return c.decisionScore === null || c.decisionScore === undefined ? '\u2014' : el('b', {}, fmtNum(c.decisionScore, 0)); } },
      { key: 'score', label: 'Screen score', get: function (c) { return c.score; }, render: function (c) { return fmtNum(c.score, 0); } }
    ];
    var wrap = el('div', { class: 'card', id: 'compare-table' });
    function render() {
      wrap.innerHTML = '';
      var sorted = candidates.slice().sort(function (a, b) {
        var col = COLS.find(function (x) { return x.key === sortKey; });
        var va = col.get(a), vb = col.get(b);
        return (va < vb ? -1 : va > vb ? 1 : 0) * sortDir;
      });
      var head = el('tr', {}, COLS.map(function (col) {
        return el('th', {}, el('button', {
          class: 'table-sort', type: 'button',
          onclick: function () {
            if (sortKey === col.key) sortDir = -sortDir; else { sortKey = col.key; sortDir = -1; }
            render();
          }
        }, col.label + (sortKey === col.key ? (sortDir < 0 ? ' \u2193' : ' \u2191') : '')));
      }).concat([el('th', {}, '')]));
      var body = el('tbody', {});
      sorted.forEach(function (c) {
        var detailRow = el('tr', { class: 'compare-detail', style: 'display:none' },
          el('td', { colspan: String(COLS.length + 1) }));
        var row = pressable(el('tr', {
          class: 'clickable', 'aria-expanded': 'false', onclick: function () {
            var open = detailRow.style.display !== 'none';
            detailRow.style.display = open ? 'none' : '';
            this.setAttribute('aria-expanded', String(!open));
            if (!open && !detailRow.firstChild.firstChild) {
              detailRow.firstChild.appendChild(candidateCard(c, true));
            }
          }
        }, COLS.map(function (col) { return el('td', {}, col.render(c)); }).concat([
          el('td', {}, el('button', {
            class: 'btn btn-sm', onclick: function (e) {
              e.stopPropagation();
              App.state.ticket = { world: App.state.world || 'observed', candidate: c, symbol: App.context.symbol(), step: 5 };
              App.navigate('#/trade/place');
            }
          }, 'Use'))])), 'Show details for ' + c.displayName, 'button');
        body.appendChild(row);
        body.appendChild(detailRow);
      });
      wrap.appendChild(el('div', { class: 'tbl-wrap' },
        el('table', { class: 'tbl' }, el('thead', {}, head), body)));
      wrap.appendChild(el('p', { class: 'muted', style: 'margin:8px 0 0' },
        'Click a column to sort, a row to expand the full card. Candidates priced at executable bid/ask.'
        + (candidates.some(function (c) { return c.usesHeldShares && c.maxLossCents === 0; })
            ? ' $0* = no new cash at risk, covered by held shares (sorted by the worst case including those shares).' : '')));
    }
    render();
    return wrap;
  }

  async function discoverStage(root, params) {
    // ONE form, no second nav row. Two questions, in the user's own order: WHAT are you
    // trying to do (goal chips), and WHERE should ideas come from — an EXPLICIT choice
    // between "Scan the market for me" (the scout) and "I have a stock in mind", exactly
    // the two experiences the old tabs held, minus the second navigation level. Every
    // goal keeps the ticker box, so "Buy at a discount" can name a stock.
    var saved = App.state.discoverForm;
    if (!saved) {
      // one-time migration from the pre-merge two-tab state
      var m0 = App.state.ideasForm || {};
      var s0 = App.state.scoutForm || {};
      saved = App.state.discoverForm = {
        goal: m0.intent || s0.goal || 'DIRECTIONAL',
        goalExplicit: !!(m0.intent || s0.goal),
        source: 'single',
        symbol: m0.symbol || '', target: m0.target || '',
        universe: s0.universe || '', scanTarget: s0.target || '',
        h0: !!s0.h0, hW: s0.hW !== false, hM: s0.hM !== false
      };
    }
    var prefill = App.state.ideasPrefill || {};
    App.state.ideasPrefill = null;
    if (prefill.symbol) { saved.symbol = prefill.symbol; saved.source = 'single'; }
    if (prefill.intent) { saved.goal = prefill.intent; saved.goalExplicit = true; }
    // COMPLETED INTENT IS NOT REQUESTED TWICE (interaction contract #4): a 'Find strategies'
    // click arrives with the symbol; when a goal is already known (handed over, or chosen
    // before and persisted) the search RUNS. Only a genuinely ambiguous goal asks.
    var autorun = !!prefill.autorun && !!prefill.symbol && !!saved.goalExplicit;
    var pendingAutorun = !!prefill.autorun && !!prefill.symbol && !saved.goalExplicit;
    // Route seeds keep every old link honest: /manual = one-stock mode, /scout = scan mode
    if (params && params[0] === 'manual') saved.source = 'single';
    if (params && params[0] === 'scout') saved.source = 'scan';
    var goal = saved.goal || 'DIRECTIONAL';
    var source = saved.source || 'single';
    function remember(patch) {
      saved = App.state.discoverForm = Object.assign({}, App.state.discoverForm, patch || {});
    }
    remember({ goal: goal });

    var level = Learn.currentLevel();
    var beginner = level === 'beginner';
    var sym = el('input', { type: 'text', id: 'rec-symbol', list: 'universe-symbols',
      placeholder: 'AAPL',
      value: prefill.symbol || App.context.symbol() || saved.symbol || 'AAPL' });
    var thesis = el('select', { id: 'rec-thesis' },
      ['bullish', 'bearish', 'neutral', 'volatile'].map(function (t) {
        return el('option', { value: t, selected: t === saved.thesis ? '' : null }, t);
      }));
    // PRESENTATION-ONLY LEVELS (review P0): 0DTE exists at BOTH levels — beginner defaults
    // steer away from it, the engine's same-day warnings still fire, but nothing is unreachable
    // and no persisted choice is silently rewritten.
    var horizons = ['week', 'month', 'quarter', '0DTE'];
    var horizon = el('select', { id: 'rec-horizon' },
      horizons.map(function (h) { return el('option', { value: h, selected: h === (saved.horizon || 'month') ? '' : null }, h); }));
    horizon.addEventListener('change', function () { remember({ horizon: horizon.value }); });
    var allow0 = el('input', { type: 'checkbox', id: 'rec-0dte', checked: saved.allow0 ? '' : null });
    allow0.addEventListener('change', function () { remember({ allow0: allow0.checked }); });
    var target = el('input', { type: 'number', id: 'rec-target', min: '0', step: '0.5', placeholder: 'optional',
      value: prefill.symbol ? '' : (saved.target || '') });
    target.addEventListener('change', function () { remember({ target: target.value }); });
    // One-tap targets anchored to the live price: +5/+10/+20% for exits, -5/-10/-15% for buys/floors
    var targetPresets = el('div', { class: 'chip-row', id: 'target-presets', style: 'margin-top:4px' });
    var presetSeq = 0; // sequence token: a slow response for the PREVIOUS symbol must never win
    async function renderTargetPresets() {
      var seq = ++presetSeq;
      targetPresets.innerHTML = '';
      if (!sym.value.trim()) return;
      var offsets = goal === 'EXIT' ? [5, 10, 20] : [-5, -10, -15];
      try {
        var q = await API.get('/api/quotes?symbols=' + sym.value.trim().toUpperCase());
        if (seq !== presetSeq) return; // superseded by a newer symbol/goal
        targetPresets.innerHTML = '';
        if (!q.quotes.length) return;
        var last = parseFloat(q.quotes[0].last);
        // Pre-open/after-hours the "last" price is the PRIOR CLOSE, not a live quote \u2014 say so instead
        // of implying it's today's price (a trader taps "+10% exit" thinking it's off the live price).
        var closed = App.config && App.config.marketOpen === false;
        offsets.forEach(function (o) {
          var px = last * (1 + o / 100);
          targetPresets.appendChild(el('button', {
            class: 'sym-chip', type: 'button', onclick: function () {
              target.value = px.toFixed(2);
              remember({ target: target.value });
            }
          }, (o > 0 ? '+' : '') + o + '% \u2192 $' + px.toFixed(2)));
        });
        if (closed) targetPresets.appendChild(el('span', { class: 'muted', style: 'font-size:11.5px' },
          'vs the prior close $' + last.toFixed(2) + ' \u2014 market closed'));
      } catch (e) { /* presets are sugar */ }
    }
    var wantShares = el('input', { type: 'number', id: 'rec-shares', min: '100', step: '100', placeholder: '100',
      value: saved.wantShares || '' });
    wantShares.addEventListener('change', function () { remember({ wantShares: wantShares.value }); });

    // Scan-scope fields (shown when there is no symbol): universe, expirations, $ goal
    var universe = el('input', { type: 'text', id: 'auto-universe',
      placeholder: 'override just this scan (comma-separated)', value: saved.universe || '' });
    var uniData = App.state.universe;
    var sectorSel = sectorRail({
      id: 'universe-sector', active: uniData && uniData.active ? uniData.active.sectorKey : null,
      onPick: async function (sec) {
        try {
          if (sec.key === 'world' || (App.state.world && App.state.world !== 'observed')) {
            if (UI.toast) UI.toast('You are in a simulated session \u2014 its symbols ARE the market here.');
            return;
          }
          await API.put('/api/universe', { sector: sec.key });
          await App.refreshUniverse();
          App.render(); // header tape + labels update everywhere
        } catch (e) { UI.toast(e.message || 'Could not change the active universe', 'error'); }
      }
    });
    // The rail must SHOW the persisted selection even when this screen is the first one rendered
    // (App.state.universe not warmed yet) — an unhighlighted rail reads as an inert control.
    if (!uniData || !uniData.active) {
      API.get('/api/universe').then(function (u2) {
        App.state.universe = u2;
        var k2 = u2 && u2.active && u2.active.sectorKey;
        var chip2 = k2 && sectorSel.querySelector('.sector-chip[data-sector="' + k2 + '"]');
        if (chip2) {
          sectorSel.querySelectorAll('.sector-chip').forEach(function (x) { x.classList.remove('active'); });
          chip2.classList.add('active');
          chip2.setAttribute('aria-selected', 'true');
        }
      }).catch(function () { /* rail stays usable without the highlight */ });
    }
    var scanTarget = el('input', { type: 'number', id: 'auto-target', min: '0', step: '50',
      placeholder: 'optional', value: saved.scanTarget || '' });
    var h0 = el('input', { type: 'checkbox', id: 'auto-h-0dte', checked: saved.h0 ? '' : null });
    var hW = el('input', { type: 'checkbox', id: 'auto-h-week', checked: saved.hW === false ? null : '' });
    var hM = el('input', { type: 'checkbox', id: 'auto-h-month', checked: saved.hM === false ? null : '' });
    [h0, hW, hM].forEach(function (n) {
      n.addEventListener('change', function () { remember({ h0: h0.checked, hW: hW.checked, hM: hM.checked }); });
    });
    universe.addEventListener('change', function () { remember({ universe: universe.value }); });
    scanTarget.addEventListener('change', function () { remember({ scanTarget: scanTarget.value }); });
    thesis.addEventListener('change', function () { remember({ thesis: thesis.value }); });

    var filters = filterPanel('rec');
    var results = el('div', { id: 'rec-results' });
    var holdingsHint = el('div', { id: 'rec-holdings-hint' });

    // 1. WHY are you here — the goal decides which questions even make sense.
    // Beginner gets story cards; Expert gets a compact segmented row.
    var GOAL_CHOICES = (Learn.INTENTS || []).concat([{
      key: 'ALL', label: 'Everything', icon: 'grid',
      blurb: 'Scan every goal at once — ideas come back grouped by goal.'
    }]);
    var expertChooser = !beginner;
    var intentRow = el('div', {
      class: 'choice-row' + (expertChooser ? ' intent-compact' : ''), id: 'intent-choices'
    }, GOAL_CHOICES.map(function (i) {
        return el('button', {
          class: 'choice' + (goal === i.key ? ' selected' : ''), 'data-intent': i.key,
          title: i.blurb,
          onclick: function () {
            goal = i.key;
            remember({ goal: goal, goalExplicit: true });
            results.innerHTML = ''; App.state.recommendResults = null; App.state.scoutResults = null;
            intentRow.querySelectorAll('.choice').forEach(function (b) { b.classList.remove('selected'); });
            this.classList.add('selected');
            sync();
            if (pendingAutorun && source === 'single') {
              pendingAutorun = false;
              setTimeout(function () { if (goBtn && goBtn.isConnected) goBtn.click(); }, 0);
            }
          }
        }, expertChooser
             ? el('b', {}, i.label)
             : el('span', { class: 'choice-head' }, icon(i.icon, 17), el('b', {}, i.label)),
           expertChooser ? null : el('span', { class: 'muted' }, i.blurb));
      }));

    var SOURCES = [
      { key: 'scan', label: beginner ? 'Scan the market for me' : 'Scan for me', icon: 'magnifier',
        blurb: 'The scout reads momentum, news and volatility across your universe and brings back ideas with evidence.' },
      { key: 'single', label: beginner ? 'I have a stock in mind' : 'One stock', icon: 'tag',
        blurb: 'Name the ticker and every idea is built for it.' }
    ];
    var sourceRow = el('div', {
      class: 'choice-row' + (expertChooser ? ' intent-compact' : ''), id: 'idea-source'
    }, SOURCES.map(function (o) {
        return el('button', {
          class: 'choice' + (source === o.key ? ' selected' : ''), 'data-source': o.key,
          title: o.blurb,
          onclick: function () {
            source = o.key;
            remember({ source: source });
            results.innerHTML = ''; App.state.recommendResults = null; App.state.scoutResults = null;
            sourceRow.querySelectorAll('.choice').forEach(function (b) { b.classList.remove('selected'); });
            this.classList.add('selected');
            sync();
          }
        }, expertChooser
             ? el('b', {}, o.label)
             : el('span', { class: 'choice-head' }, icon(o.icon, 17), el('b', {}, o.label)),
           expertChooser ? null : el('span', { class: 'muted' }, o.blurb));
      }));
    // The active universe/sector stays visible as one-tap chips even while a symbol
    // sits in the box (the datalist only helps when typing — this is always there).
    var symChips = el('div', { class: 'sym-chips', id: 'universe-sym-chips' });
    function renderSymChips() {
      symChips.innerHTML = '';
      var syms = (App.state.universe && App.state.universe.active.symbols) || [];
      var cur = sym.value.trim().toUpperCase();
      syms.forEach(function (s2) {
        symChips.appendChild(el('button', {
          class: 'sym-chip' + (s2 === cur ? ' active' : ''), type: 'button',
          onclick: function () {
            sym.value = s2;
            App.context.selectSymbol(s2);
            remember({ symbol: s2 });
            sync();
          }
        }, s2));
      });
    }
    var symField = UI.symbolContext({
      mode: 'editable', id: 'ideas-symbol-context', input: sym,
      label: beginner ? 'Which stock?' : 'Symbol', class: 'ideas-symbol-context'
    });
    symChips.classList.add('symbol-context-peers');
    symField.appendChild(symChips);
    var thesisField = el('div', { class: 'field' }, el('label', {}, 'Your view'), thesis);
    var targetField = el('div', { class: 'field', style: 'display:none' },
      el('label', { id: 'rec-target-label' }, 'Target price ($/sh)'), target, targetPresets);
    var sharesField = el('div', { class: 'field', style: 'display:none' }, el('label', {}, 'Shares you want'), wantShares);
    var horizonField = el('div', { class: 'field' }, el('label', {}, 'Horizon'), horizon);
    var zeroField = el('div', { class: 'field inline-check', style: 'align-self:end' },
      allow0, el('label', { for: 'rec-0dte', style: 'text-transform:none;letter-spacing:0;font-size:13.5px;font-weight:500' },
        beginner ? 'Allow same-day (0DTE) expiry \u2014 fast and unforgiving' : 'Allow same-day (0DTE) expiry'));
    var sectorField = el('div', { class: 'field', style: 'grid-column: 1 / -1' },
      el('label', {}, 'Market sector (applies everywhere)'), sectorSel);
    var oneOffField = el('div', { class: 'field', style: 'grid-column: 1 / -1' },
      el('label', {}, 'Scan override'), universe);
    var scanTargetField = el('div', { class: 'field' }, el('label', {}, 'Target profit ($)'), scanTarget);
    var expiryRow = el('div', { class: 'btn-row' },
      el('span', { class: 'muted' }, 'Expirations:'),
      el('label', { class: 'inline-check' }, h0, ' 0DTE'),
      el('label', { class: 'inline-check' }, hW, ' Weekly'),
      el('label', { class: 'inline-check' }, hM, ' Monthly'));
    var goBtn; // created below; sync() relabels it

    // scan = the scout pipeline; "Everything" on one named stock also scans (universe = [it])
    function scanMode() { return source === 'scan' || goal === 'ALL'; }

    function sync() {
      var scan = scanMode();
      symField.style.display = source === 'single' ? '' : 'none';
      thesisField.style.display = !scan && goal === 'DIRECTIONAL' ? '' : 'none';
      targetField.style.display = !scan && (goal === 'EXIT' || goal === 'ACQUIRE' || goal === 'HEDGE') ? '' : 'none';
      sharesField.style.display = !scan && goal === 'ACQUIRE' ? '' : 'none';
      horizonField.style.display = scan ? 'none' : '';
      zeroField.style.display = scan ? 'none' : '';
      // Sector context is never hidden. In one-stock mode it updates the peer suggestions;
      // in scout mode it is also the scan universe. One persisted control, two honest uses.
      sectorField.style.display = '';
      oneOffField.style.display = scan && source === 'scan' ? '' : 'none';
      scanTargetField.style.display = scan ? '' : 'none';
      expiryRow.style.display = scan ? '' : 'none';
      var lbl = document.getElementById('rec-target-label');
      if (lbl) {
        lbl.textContent = goal === 'EXIT' ? 'Price you would happily sell at ($/sh)'
          : goal === 'ACQUIRE' ? 'Price you would happily pay ($/sh)'
          : 'Protect below ($/sh, optional)';
      }
      if (goBtn) goBtn.textContent = scan ? 'Scan for opportunities' : 'Find ideas';
      if (!scan && (goal === 'EXIT' || goal === 'ACQUIRE' || goal === 'HEDGE')) renderTargetPresets();
      renderSymChips();
      refreshHoldingsHint();
    }

    var holdingsSeq = 0;
    async function refreshHoldingsHint() {
      var seq = ++holdingsSeq;
      holdingsHint.innerHTML = '';
      if (goal === 'DIRECTIONAL' || goal === 'ALL') return;
      try {
        var all = (await API.get('/api/positions')).positions || [];
        if (seq !== holdingsSeq) return; // superseded
        holdingsHint.innerHTML = '';
        // EXIT/HEDGE start from what you OWN: your holdings as one-tap chips
        if ((goal === 'EXIT' || goal === 'HEDGE') && all.length) {
          holdingsHint.appendChild(el('div', { class: 'chip-row', id: 'holdings-chips' },
            el('span', { class: 'muted' }, 'Your holdings:'),
            all.map(function (p) {
              var g = p.gainPct !== null && p.gainPct !== undefined
                ? el('b', { class: p.gainPct >= 0 ? 'gain' : 'loss' }, ' ' + (p.gainPct >= 0 ? '+' : '') + p.gainPct.toFixed(1) + '%')
                : null;
              return el('button', {
                class: 'sym-chip' + (p.symbol === sym.value.trim().toUpperCase() ? ' active' : ''),
                onclick: function () {
                  sym.value = p.symbol;
                  source = 'single';
                  App.context.selectSymbol(p.symbol);
                  remember({ symbol: p.symbol, source: 'single' });
                  sourceRow.querySelectorAll('.choice').forEach(function (b) {
                    b.classList.toggle('selected', b.getAttribute('data-source') === 'single');
                  });
                  sync();
                }
              }, p.symbol + ' \u00B7 ' + p.shares + ' sh', g);
            })));
        }
        if (scanMode()) {
          if (goal === 'EXIT' || goal === 'HEDGE') {
            holdingsHint.appendChild(explain(all.length
              ? 'The scan covers every holding above — tap one to focus on it.'
              : 'You hold no shares yet — buy shares first, or name a stock to see buy-write style ideas.'));
          }
          return;
        }
        var mine = all.find(function (x) { return x.symbol === sym.value.trim().toUpperCase(); });
        if (mine) {
          holdingsHint.appendChild(el('div', { class: 'chip-row' },
            chip('You hold', mine.shares + ' sh'),
            chip('Free', mine.freeShares + ' sh'),
            chip(UI.term('cost basis', 'Avg basis'), fmtMoney(mine.avgCostCents) + '/sh'),
            mine.gainPct !== null && mine.gainPct !== undefined
              ? chip('Since purchase', el('span', { class: mine.gainPct >= 0 ? 'gain' : 'loss' },
                  (mine.gainPct >= 0 ? '+' : '') + mine.gainPct.toFixed(1) + '%')) : null));
        } else if (goal === 'EXIT' || goal === 'HEDGE') {
          holdingsHint.appendChild(explain('You do not hold ' + (sym.value.trim().toUpperCase() || 'this symbol')
            + ' — ideas below include buying the shares first (buy-write style). Options work in 100-share lots.'));
        }
      } catch (e) { /* hint only */ }
    }
    sym.addEventListener('input', function () {
      var v = sym.value.trim().toUpperCase();
      remember({ symbol: v });
      // The working symbol follows you: Builder, Backtest and the ticket all seed from it.
      if (v) App.context.selectSymbol(v);
      sync();
    });


    goBtn = el('button', {
      class: 'btn', id: 'rec-go', onclick: async function () {
        results.innerHTML = '';
        if (scanMode()) return runScan();
        return runSingle();
      }
    }, 'Find ideas');
    if (autorun) {
      // Deferred one tick: the form below must be in the DOM before results paint into it.
      setTimeout(function () {
        if (!goBtn.isConnected) return; // navigated away before the tick
        if (saved.goalExplicit && !scanMode()) {
          goBtn.click();
        } else {
          var chooser = document.getElementById('intent-choices');
          if (chooser) { chooser.scrollIntoView({ block: 'center' }); }
          if (UI.toast) UI.toast('Pick a goal \u2014 ideas for ' + (saved.symbol || '') + ' run the moment you do.');
        }
      }, 0);
    }

    root.appendChild(el('section', { class: 'card idea-market-card', id: 'idea-market-card' },
      UI.cardHeader('Start with a stock or market'),
      explain(beginner
        ? 'Name the stock already on your mind, or ask the scout to search a sector. This context follows the rest of the workflow.'
        : 'Choose one symbol or a sector scope before setting the strategy objective.'),
      sourceRow,
      el('div', { class: 'idea-market-controls' }, symField, sectorField, oneOffField)));

    root.appendChild(el('section', { class: 'card idea-goal-card', id: 'idea-goal-card' },
      UI.cardHeader('What do you want the position to do?',
        el('a', {
          class: 'muted', id: 'all-strategies-link', href: '#/trade/shape', style: 'font-size:12.5px',
          onclick: function () {
            App.state.builderForm = { symbol: App.context.symbol('AAPL'), qty: 1,
              goal: 'BROWSE', templateKey: null, step: 2, legIdx: 0, legs: [], excluded: {} };
          }
        }, 'All strategies \u2192')),
      explain(beginner
        ? 'Choose the job: grow, earn income, protect shares, buy lower, or sell at a target. Every result still shows its evidence and known worst case.'
        : 'Objective, view, horizon and constraints drive one complete ranked field; the market context above stays fixed.'),
      intentRow,
      el('div', { class: 'form-grid', style: 'margin-top:10px' },
        thesisField,
        targetField,
        sharesField,
        horizonField,
        scanTargetField,
        zeroField),
      expiryRow,
      holdingsHint,
      filters.node,
      el('div', { class: 'btn-row' }, goBtn)));
    root.appendChild(results);
    sync();
    // A finished scan OR single-symbol result survives navigation / level switch — losing a scan
    // (or having to re-click "Find ideas") was rightly a complaint. Restore from cache when it
    // matches the current symbol+goal; the render fns already take the level, so a level flip
    // re-renders the cached payload instead of re-fetching.
    if (scanMode() && App.state.scoutResults) {
      renderScoutResults(results, App.state.scoutResults);
    } else if (!scanMode() && App.state.recommendResults
        && App.state.recommendResults.symbol === (sym.value || '').trim().toUpperCase()
        && App.state.recommendResults.goal === goal) {
      var rr = App.state.recommendResults;
      renderResults(rr.r, rr.goal, rr.body, rr.extras);
    }

    var goSeq = 0; // supersede token: a slow scan/ideas run must not overwrite (or persist) fresher results
    async function runScan() {
      var seq = ++goSeq;
      results.appendChild(UI.spinner('Scanning and deriving views\u2026'));
      try {
        var horizonsSel = [];
        if (h0.checked) horizonsSel.push('0DTE');
        if (hW.checked) horizonsSel.push('week');
        if (hM.checked) horizonsSel.push('month');
        var body = { horizons: horizonsSel, riskMode: riskMode(), allow0dte: h0.checked };
        body.intents = goal === 'ALL'
          ? (Learn.INTENTS || []).map(function (i) { return i.key; })
          : [goal];
        var f = filters.value();
        if (f) body.filters = f;
        var flMax = filters.maxLossCents();
        if (flMax) body.maxLossCents = flMax;
        var symVal = sym.value.trim().toUpperCase();
        if (source === 'single' && symVal) body.universe = [symVal]; // "Everything" for one named stock
        else if (universe.value.trim()) body.universe = universe.value.split(',').map(function (x) { return x.trim(); }).filter(Boolean);
        if (scanTarget.value) body.targetProfitCents = Math.round(parseFloat(scanTarget.value) * 100);
        var scan = await API.post('/api/recommend/auto', body);
        if (seq !== goSeq) return; // a newer run superseded this
        App.state.scoutResults = scan;
        renderScoutResults(results, scan);
      } catch (e) {
        if (seq !== goSeq) return;
        results.innerHTML = '';
        results.appendChild(alertBox('danger', e.message));
      }
    }

    async function runSingle() {
      var seq = ++goSeq;
      results.appendChild(UI.spinner('Screening strategies\u2026'));
      try {
        App.context.selectSymbol(sym.value);
        // The 0DTE controls are VISIBLE at both levels (presentation-only levels, review P0):
        // what the user set is what the engine receives — no silent rewrites.
        var body = {
          symbol: sym.value.trim(), horizon: horizon.value,
          riskMode: riskMode(), allow0dte: allow0.checked, avoidEarnings: true,
          intent: goal
        };
        if (goal === 'DIRECTIONAL') body.thesis = thesis.value;
        var f = filters.value();
        if (f) body.filters = f;
        var flMax = filters.maxLossCents();
        if (flMax) body.maxLossCents = flMax;
        // Holdings context: real position (if any) + the user's target price.
        if (goal !== 'DIRECTIONAL') {
          var holdings = {};
          var targetApplies = goal === 'EXIT' || goal === 'ACQUIRE' || goal === 'HEDGE';
          if (targetApplies && target.value) holdings.targetPriceCents = Math.round(parseFloat(target.value) * 100);
          if (goal === 'ACQUIRE' && wantShares.value) holdings.sharesOwned = parseInt(wantShares.value, 10);
          if (goal !== 'ACQUIRE') {
            try {
              var all = (await API.get('/api/positions')).positions || [];
              var mine = all.find(function (x) { return x.symbol === sym.value.trim().toUpperCase(); });
              if (mine) {
                holdings.sharesOwned = mine.freeShares;
                holdings.costBasisCents = mine.avgCostCents;
              }
            } catch (e) { /* engine copes without */ }
          }
          if (Object.keys(holdings).length) body.holdings = holdings;
        }
        // recommend + the goal-native ladder + spot quote all depend only on `body` — the
        // ladder does NOT need the recommend result — so fire them together instead of chaining.
        var isLadderGoal = goal === 'ACQUIRE' || goal === 'EXIT' || goal === 'HEDGE';
        var recP = API.post('/api/recommend', body);
        var ladderP = isLadderGoal ? API.post('/api/recommend/ladder', body).catch(function () { return null; }) : null;
        var quotesP = isLadderGoal ? API.get('/api/quotes?symbols=' + body.symbol.toUpperCase()).catch(function () { return null; }) : null;
        var acctP = goal === 'INCOME' ? API.get('/api/account').catch(function () { return null; }) : null;
        var incHeldP = goal === 'INCOME' ? API.get('/api/positions').catch(function () { return null; }) : null;

        var r = await recP;
        var extras = {};
        if (isLadderGoal) {
          var lad = await ladderP; if (lad) extras.ladder = lad;                 // rungs across strikes
          var q = await quotesP; if (q) extras.spot = q.quotes.length ? parseFloat(q.quotes[0].last) : null;
        }
        if (goal === 'INCOME') {
          var ac = await acctP; if (ac) extras.acct = ac.account;
          var hd = await incHeldP; if (hd) extras.held = hd.positions;           // board is optional
        }
        if (seq !== goSeq) return; // a newer run superseded this
        App.state.recommendResults = { symbol: (body.symbol || '').toUpperCase(), goal: goal, r: r, body: body, extras: extras };
        renderResults(r, goal, body, extras);
      } catch (e) {
        if (seq !== goSeq) return;
        results.innerHTML = '';
        results.appendChild(alertBox('danger', e.message));
      }
    }

    function renderResults(r, intentKey, body, extras) {
      extras = extras || {};
      results.innerHTML = '';
      // What's coming up for this symbol — the dates that can invalidate an idea
      if (body && body.symbol) results.appendChild(comingUp(body.symbol.toUpperCase(), true));
      // Intent-native lead view first: the ladder IS the product for these goals
      if (extras.ladder && extras.ladder.rungs && extras.ladder.rungs.length) {
        results.appendChild(ladderView(extras.ladder, intentKey, {
          symbol: (body && body.symbol ? body.symbol : r.symbol).toUpperCase(),
          spot: extras.spot,
          targetPrice: body && body.holdings && body.holdings.targetPriceCents ? body.holdings.targetPriceCents / 100 : null,
          basisCents: body && body.holdings ? body.holdings.costBasisCents : null
        }));
      }
      if (intentKey === 'INCOME' && extras.acct) {
        results.appendChild(incomeBoard(r, extras.acct, extras.held));
      }
      results.appendChild(el('div', { class: 'chip-row' },
        chip('Risk budget (this trade)', fmtMoney(r.riskBudgetCents), 'The most this one trade may risk under your header risk mode.'),
        chip('Mode', r.riskMode.toLowerCase()),
        chip('Candidates', String(r.candidates.length))));
      if (r.candidates.length) {
        results.appendChild(el('div', { class: 'economic-baseline', id: 'cash-baseline' },
          el('div', {}, el('b', {}, 'Cash / no trade'), el('span', { class: 'badge badge-dim' }, 'BASELINE')),
          el('p', {}, 'Doing nothing has $0 market P/L and no option execution cost. A structure should earn its place by improving the outcome under your evidence and risk limits.')));
      }
      // Recommendations-as-a-competition: score these side by side INLINE (D3 — no orphan surface).
      if (body && body.symbol && r.candidates.length > 1) {
        var compareHost = el('div', { id: 'decision-host', style: 'margin-top:8px' });
        results.appendChild(el('div', { class: 'btn-row', style: 'margin:8px 0' },
          el('button', {
            class: 'btn btn-secondary', id: 'compare-ideas-btn',
            onclick: function () { renderCompetition(compareHost, body.symbol.toUpperCase()); }
          }, 'Compare these side by side →')));
        results.appendChild(compareHost);
      }
      (r.notes || []).forEach(function (n) { results.appendChild(alertBox('warn', n)); });
      if (!r.candidates.length) {
        results.appendChild(UI.emptyState('Nothing passed the risk screens',
          'Try a different horizon, a wider risk budget, or another symbol.'));
      }
      var hasLadder = extras.ladder && extras.ladder.rungs && extras.ladder.rungs.length;
      if (hasLadder && r.candidates.length) {
        results.appendChild(el('h3', {}, 'Other structures for this goal'));
      }
      if (r.economicMessage) {
        results.appendChild(alertBox(r.favorableCount > 0 ? 'ok' : 'warn', r.economicMessage));
      }
      if (Learn.currentLevel() === 'expert' && r.candidates.length > 1) {
        // Expert receives the COMPLETE ranking immediately (ranking truth, review P1).
        results.appendChild(comparisonTable(r.candidates));
      } else {
        // Beginner receives every capability, organized by economic meaning rather than hidden.
        renderEconomicGroups(results, r.candidates);
      }
      if (r.rejected && r.rejected.length) {
        var rej = el('div', { class: 'card' },
          UI.cardHeader('Looked at and refused'),
          explain('Learning what gets blocked — and why — matters as much as what gets suggested.'));
        r.rejected.forEach(function (x) {
          var reasons = (x.reasons || []).join(' ');
          // The covered call is the strategy beginners come FOR — a jargon refusal at the
          // default settings read as a dead end. Say what to do about it, with a way there.
          var needsShares = /shares|covered|free share/i.test(reasons) || /COVERED_CALL|PROTECTIVE/i.test(x.strategy || '');
          rej.appendChild(el('div', { class: 'status-item' },
            el('span', { class: 'badge badge-danger' }, 'BLOCKED'),
            el('span', {}, el('b', {}, x.displayName), ' \u2014 ',
              needsShares && Learn.currentLevel() === 'beginner'
                ? 'needs 100 shares you own first — covered trades rent out shares you hold. '
                : reasons + ' ',
              needsShares ? el('a', { href: '#/portfolio', onclick: function () { App.navigate('#/portfolio'); } },
                'Buy practice shares in Portfolio \u2192') : null)));
        });
        results.appendChild(rej);
      }
      results.appendChild(el('p', { class: 'muted' }, r.disclaimer));
    }
  }

  /**
   * THE TRADE WORKBENCH: one place, four stages — Discover (scout / my idea), Shape (the
   * builder), Verify (backtest), Place (strikes -> review -> confirm). A persistent idea bar
   * carries the working idea between stages so nothing is lost crossing them.
   * #/trade/tr_* stays the trade DETAIL page (dispatcher below).
   */
  // Plain words only — "Shape" and "Verify" read as jargon to anyone who didn't build
  // this app. The keys stay stable (they live in URLs and tests); the LABELS say what
  // each section actually is.
  var WB_STAGES = [
    { key: 'discover', label: 'Ideas', hint: 'find or describe a trade idea' },
    { key: 'shape', label: 'Builder', hint: 'build multi-leg strategies with live risk' },
    { key: 'verify', label: 'Backtest', hint: 'how the strategy would have gone, honestly' },
    { key: 'place', label: 'Place', hint: 'strikes, review, paper confirm' }
  ];

  function ideaBar(stage) {
    var t = App.state.ticket;
    var b = App.state.builderForm;
    var label = null, symbol = null;
    if (t && t.candidate) { symbol = t.symbol; label = t.candidate.displayName + ' · qty ' + (t.qty || t.candidate.qty || 1); }
    else if (t && t.custom && t.legs && t.legs.length) { symbol = t.symbol; label = t.legs.length + '-leg custom · qty ' + (t.qty || 1); }
    else if (b && b.legs && b.legs.length) { symbol = b.symbol; label = b.legs.length + ' leg' + (b.legs.length > 1 ? 's' : '') + ' in the builder'; }
    var bar = el('div', { class: 'idea-bar', id: 'idea-bar' });
    if (label) {
      bar.appendChild(el('span', { class: 'idea-chip' }, icon('target', 14), el('b', {}, symbol), ' ', label));
      if (stage !== 'place' && t && (t.candidate || (t.custom && t.legs && t.legs.length))) {
        bar.appendChild(el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/trade/place'); } }, 'Place \u2192'));
      }
      bar.appendChild(el('button', {
        class: 'btn btn-sm btn-secondary', id: 'idea-clear', title: 'Drop the working idea',
        onclick: function () { App.state.ticket = null; App.state.builderForm = null; App.render(); }
      }, 'Clear'));
    } else {
      // No working IDEA yet \u2014 but the working VIEW (symbol \u00b7 goal \u00b7 thesis \u00b7 horizon) still
      // follows the user, so the bar offers context instead of a dead sentence.
      var wv = workingViewLabel();
      if (wv) {
        bar.appendChild(el('span', { class: 'idea-chip', id: 'working-view-chip' }, icon('compass', 14), wv));
        bar.appendChild(el('button', { class: 'btn btn-sm btn-secondary',
          onclick: function () { App.navigate('#/trade/discover'); } }, 'Find ideas'));
      } else {
        bar.appendChild(el('span', { class: 'muted' }, 'No working idea yet \u2014 pick one from Ideas or make one in the Builder.'));
      }
    }
    return bar;
  }

  /**
   * The working VIEW in one plain sentence \u2014 the thesis context that follows the user across
   * Research and Trade (persisted in the workspace): "QQQ \u00b7 Trade a view: bearish \u00b7 ~1 month".
   * Null when there's nothing meaningful yet.
   */
  function workingViewLabel() {
    var f = App.state.discoverForm || {};
    var sym = (App.context.symbol() || f.symbol || '').toUpperCase();
    if (!sym) return null;
    var parts = [sym];
    var goal = f.goal && f.goal !== 'ALL' ? f.goal : null;
    var meta = goal && (Learn.INTENTS || []).find(function (i) { return i.key === goal; });
    if (meta) parts.push(meta.label + ((goal === 'DIRECTIONAL' && f.thesis) ? ': ' + f.thesis : ''));
    var HZ = { '0DTE': 'today', week: '~1 week', month: '~1 month', quarter: '~3 months' };
    if (f.horizon && HZ[f.horizon]) parts.push(HZ[f.horizon]);
    return parts.join(' \u00b7 ');
  }

  async function workbench(root, params) {
    var stage = 'discover';
    WB_STAGES.forEach(function (s2) { if (params[0] === s2.key) stage = s2.key; });
    root.appendChild(el('h1', {}, 'Trade'));
    // Place is GATED until an idea exists: a clickable stage that lands on an empty
    // screen reads as broken, not as guidance.
    var t0 = App.state.ticket;
    var hasPlaceable = !!(t0 && (t0.candidate || (t0.custom && t0.legs && t0.legs.length)));
    root.appendChild(el('div', { class: 'tabs wb-stages' }, WB_STAGES.map(function (s2, i) {
      var gated = s2.key === 'place' && !hasPlaceable && stage !== 'place';
      return el('button', {
        class: (s2.key === stage ? 'active' : ''), id: 'wb-' + s2.key,
        disabled: gated ? '' : null,
        title: gated ? 'Nothing to place yet — pick one from Ideas or make one in the Builder' : s2.hint,
        onclick: function () { App.navigate('#/trade/' + s2.key); }
      }, s2.label);
    })));
    root.appendChild(ideaBar(stage));
    if (stage === 'discover') await discoverStage(root, params.slice(1));
    else if (stage === 'shape') {
      await Builder.render(root);
      // Exposure replication belongs with construction: size a
      // synthetic to a dollar exposure, then hand the legs to the builder above. Collapsed —
      // genuinely optional detail (interaction contract #3).
      App.state.tradeReplicator = App.state.tradeReplicator || {};
      var rctx = { symbol: (App.state.builderForm && App.state.builderForm.symbol)
        || App.context.symbol() || App.state.tradeReplicator.symbol || 'SPY' };
      root.appendChild(UI.expandable('Replicate an ETF exposure \u2014 synthetic sizing', function () {
        return replicateCard(Learn.currentLevel(), rctx);
      }));
    }
    else if (stage === 'verify') await backtest(root);
    else await ticket(root);
  }

  async function trade(root, params) {
    if (params[0] && /^tr_/.test(params[0])) return tradeDetail(root, params);
    return workbench(root, params);
  }

  // ---- Shared intent + filter controls ----

  var INTENT_BADGE = { DIRECTIONAL: 'badge-dim', INCOME: 'badge-ok', ACQUIRE: 'badge-ok', EXIT: 'badge-warn', HEDGE: 'badge-caution' };

  function intentBadge(intent) {
    if (!intent || intent === 'DIRECTIONAL') return null;
    var meta = (Learn.INTENTS || []).find(function (i) { return i.key === intent; });
    return el('span', { class: 'badge ' + (INTENT_BADGE[intent] || 'badge-dim') },
      meta ? meta.label.toUpperCase() : intent);
  }

  /**
   * Screen-by-what-matters panel, reshaped per experience level (structure, not captions):
   * Beginner gets two plain-language limits behind a friendly expandable; Expert gets the
   * full five behind an expandable; Pro gets all five inline — no clicks, maximum density.
   * Returns {node, value()} where value() is the engine Filters object (fractions for
   * probabilities) or null when everything is blank.
   */
  function filterPanel(idPrefix) {
    var level = Learn.currentLevel();
    // Persist the five limits per surface so they survive a re-render / level switch / tab hop
    // (single-owner + seed + write-back — the state-ownership convention).
    App.state.filterState = App.state.filterState || {};
    var saved = App.state.filterState[idPrefix] = App.state.filterState[idPrefix] || {};
    var mkFilter = function (key, attrs) {
      var input = el('input', Object.assign({ type: 'number', placeholder: 'any' }, attrs));
      if (saved[key] !== undefined && saved[key] !== '') input.value = saved[key];
      input.addEventListener('input', function () { saved[key] = input.value; });
      return input;
    };
    var minPop = mkFilter('minPop', { id: idPrefix + '-f-pop', min: '0', max: '100', step: '5' });
    var maxAssign = mkFilter('maxAssign', { id: idPrefix + '-f-assign', min: '0', max: '100', step: '5' });
    var minYield = mkFilter('minYield', { id: idPrefix + '-f-yield', min: '0', step: '1' });
    var maxCost = mkFilter('maxCost', { id: idPrefix + '-f-cost', min: '0', step: '50' });
    var maxLoss = mkFilter('maxLoss', { id: idPrefix + '-f-maxloss', min: '0', step: '50' });
    var node;
    // ALL FIVE limits exist at BOTH levels (presentation-only levels, review P0): Beginner gets
    // them collapsed behind the expandable in plain words; Expert gets the same five inline.
    // A value set anywhere applies everywhere — persisted selections are never dropped.
    var fieldRow = function (label, input, tip) {
      return el('div', { class: 'field', title: tip }, el('label', {}, label), input);
    };
    var TIPS = {
      pop: 'POP — the modeled probability the trade ends with ANY profit. 70 keeps only ideas with at least a 70% chance. Model output, pre-commission.',
      assign: 'Probability of ending up assigned shares on the short leg(s). For sell-at-target and buy-at-discount goals assignment IS the point — leave blank there.',
      yld: 'Share-backed income only (covered calls, cash-secured puts, collars). Annualized so a 2-week and a 2-month trade compare fairly — 0.5% over two weeks is about 13%/yr. Blank = no floor.',
      cost: 'The most you pay up front (debit trades). Credit trades collect cash instead — cap those with the worst case.',
      loss: 'Hard cap on the modeled maximum loss — your per-idea risk budget in dollars. Blank = sized by the header risk mode.'
    };
    if (level === 'beginner') {
      node = UI.expandable('Only show ideas that fit my limits', function () {
        return el('div', {},
          el('div', { class: 'form-grid' },
            fieldRow(UI.term('max loss', 'The most I am willing to lose ($)'), maxLoss, TIPS.loss),
            fieldRow(UI.term('pop', 'Minimum chance of any profit (%)'), minPop, TIPS.pop),
            fieldRow(UI.term('assignment', 'Chance I end up with shares (max %)'), maxAssign, TIPS.assign),
            fieldRow('Income pace (min %/yr)', minYield, TIPS.yld),
            fieldRow('Cash I pay up front (max $)', maxCost, TIPS.cost)),
          explain('Ideas outside your limits are not hidden silently — the results call them out with the exact reason, so you can see what you are screening out.'));
      });
    } else {
      // Plain trader language, units in the label, the fine print one hover away.
      // "Worst case \u2264 $" IS the per-idea risk budget (the old % -of-account knob merged
      // into it; the header risk mode still sizes ideas when this is blank).
      node = el('div', { class: 'card compact-filters' },
        el('div', { class: 'form-grid grid-5' },
          fieldRow('Chance of profit \u2265 %', minPop, TIPS.pop),
          fieldRow('Assignment risk \u2264 %', maxAssign, TIPS.assign),
          fieldRow('Income rate \u2265 %/yr', minYield, TIPS.yld),
          fieldRow('Cash outlay \u2264 $', maxCost, TIPS.cost),
          fieldRow('Worst case \u2264 $', maxLoss, TIPS.loss)),
        el('p', { class: 'muted', style: 'margin:6px 0 0; font-size:12px' },
          'Blank = no limit. Hover a label for exactly what it means; refused ideas always say which limit they broke.'));
    }
    node.id = idPrefix + '-filters';
    return {
      node: node,
      value: function () {
        // Every limit is VISIBLE at both levels, so every set value is honored — identical
        // inputs produce identical requests at both levels (cross-level invariance, review P0).
        var f = {};
        if (minPop.value) f.minPop = parseFloat(minPop.value) / 100;
        if (maxAssign.value) f.maxAssignmentProb = parseFloat(maxAssign.value) / 100;
        if (minYield.value) f.minAnnualizedYieldPct = parseFloat(minYield.value);
        if (maxCost.value) f.maxCostCents = Math.round(parseFloat(maxCost.value) * 100);
        return Object.keys(f).length ? f : null;
      },
      maxLossCents: function () {
        return maxLoss.value ? Math.round(parseFloat(maxLoss.value) * 100) : null;
      }
    };
  }

  // ---- Scout: the auto-recommender ----

  function renderScoutResults(results, r) {
    results.innerHTML = '';
    results.appendChild(el('div', { class: 'chip-row' },
      chip('Picks', String(r.picks.length)),
      chip('Risk budget (per idea)', fmtMoney(r.riskBudgetCents), 'The most any single scanned idea may risk under your header risk mode.')));
    (r.notes || []).forEach(function (n) { results.appendChild(alertBox('warn', n)); });
    if (!r.picks.length) {
      results.appendChild(UI.emptyState('No opportunities passed the screens',
        'Try widening the universe, allowing more expirations, or relaxing your filters.'));
    }
    var intentsSeen = [];
    r.picks.forEach(function (p) { if (intentsSeen.indexOf(p.intent) < 0) intentsSeen.push(p.intent); });
    var GROUPS = { DIRECTIONAL: 'Trading a view', INCOME: 'Income opportunities',
      ACQUIRE: 'Buy-at-a-discount candidates', EXIT: 'Harvesting your holdings', HEDGE: 'Protecting your holdings' };
    // A 20-pick "Everything" scan rendered as an ~18,500px wall of full cards. Structure it:
    // a jump row up top, the top 3 per goal expanded, and the rest one honest tap away.
    if (r.picks.length > 6 && intentsSeen.length > 1) {
      results.appendChild(el('div', { class: 'chip-row', id: 'scout-jump' }, intentsSeen.map(function (ik) {
        var n = r.picks.filter(function (p) { return p.intent === ik; }).length;
        return el('button', { class: 'sym-chip', type: 'button', onclick: function () {
          var t2 = document.getElementById('scout-g-' + ik);
          if (t2) t2.scrollIntoView({ behavior: 'smooth', block: 'start' });
        } }, (GROUPS[ik] || ik) + ' (' + n + ')');
      })));
    }
    function renderGroup(ik, picks) {
      if (intentsSeen.length > 1) results.appendChild(el('h2', { class: 'scout-group', id: 'scout-g-' + ik }, GROUPS[ik] || ik));
      var top = picks.slice(0, 3), rest = picks.slice(3);
      top.forEach(function (pick) { results.appendChild(pickCard(pick)); });
      if (rest.length) {
        var slot = el('div', {});
        slot.appendChild(el('button', { class: 'btn btn-secondary btn-sm', type: 'button', onclick: function () {
          slot.innerHTML = '';
          rest.forEach(function (pick) { slot.appendChild(pickCard(pick)); });
        } }, 'Show ' + rest.length + ' more ' + (GROUPS[ik] || ik).toLowerCase()));
        results.appendChild(slot);
      }
    }
    if (intentsSeen.length > 1) {
      intentsSeen.forEach(function (ik) { renderGroup(ik, r.picks.filter(function (p) { return p.intent === ik; })); });
    } else if (r.picks.length) {
      renderGroup(intentsSeen[0], r.picks);
    }
    if (r.skipped && r.skipped.length) {
      var sk = el('div', { class: 'card' }, UI.cardHeader('Skipped'));
      r.skipped.forEach(function (s) { sk.appendChild(el('div', { class: 'status-item' }, el('span', { class: 'muted' }, s))); });
      results.appendChild(sk);
    }
    results.appendChild(el('p', { class: 'muted' }, r.disclaimer));
  }

  function pickCard(pick) {
    var s = pick.signals;
    var card = el('div', { class: 'card pick-card' },
      el('div', { class: 'quote-hero' },
        el('a', { class: 'sym pick-symbol-link', href: '#/research/' + pick.symbol,
          title: 'Open full analysis for ' + pick.symbol }, pick.symbol),
        intentBadge(pick.intent),
        el('span', { class: 'badge ' + (THESIS_BADGE[s.thesis] || 'badge-dim') }, s.thesis),
        chip('Confidence', fmtPct(s.confidence)),
        chip('Opportunity', fmtNum(pick.opportunityScore, 2))));
    card.appendChild(el('div', { class: 'chip-row' },
      s.ret20d !== null && s.ret20d !== undefined ? chip('20d move', (s.ret20d >= 0 ? '+' : '') + (s.ret20d * 100).toFixed(1) + '%') : null,
      chip('Sentiment', (s.sentimentScore >= 0 ? '+' : '') + s.sentimentScore.toFixed(2)),
      s.ivAtm ? chip('IV', fmtPct(s.ivAtm)) : null,
      s.hv30 ? chip('HV', fmtPct(s.hv30)) : null,
      chip('Vol', s.volSignal.toLowerCase()),
      s.eventRisk ? el('span', { class: 'badge badge-warn' }, 'EVENT RISK') : null));
    var why = el('ul', { class: 'rationale' }, (s.rationale || []).map(function (line) { return el('li', {}, line); }));
    card.appendChild(why);
    var evidence = (s.positiveHeadlines || []).map(function (h) { return el('dd', { class: 'gain' }, '+ ' + h); })
      .concat((s.negativeHeadlines || []).map(function (h) { return el('dd', { class: 'loss' }, '− ' + h); }));
    if (evidence.length) {
      card.appendChild(el('details', { class: 'qa' },
        el('summary', {}, 'Headline evidence (' + evidence.length + ')'),
        el('dl', {}, evidence)));
    }
    (pick.horizons || []).forEach(function (h) {
      card.appendChild(el('h3', {}, h.horizon === '0DTE' ? 'Today (0DTE)' : h.horizon === 'week' ? 'About a week' : 'About a month'));
      (h.notes || []).forEach(function (n) { card.appendChild(alertBox('warn', n)); });
      if (!h.candidates.length && !(h.notes || []).length) {
        card.appendChild(el('p', { class: 'muted' }, 'Nothing passed the risk screens for this horizon.'));
      }
      h.candidates.forEach(function (sc) {
        if (sc.economics) {
          sc.candidate.economics = sc.economics;
          sc.candidate.economicVerdict = sc.economics.verdict;
          sc.candidate.economicPlacement = sc.economics.placement;
        }
        if (sc.decisionScore !== null && sc.decisionScore !== undefined) sc.candidate.decisionScore = sc.decisionScore;
        var cc = candidateCard(sc.candidate, true, pick.symbol);
        if (sc.targetFit) {
          cc.insertBefore(alertBox(sc.targetFit.indexOf('covers') >= 0 ? 'ok' : 'warn', sc.targetFit), cc.querySelector('.btn-row'));
        }
        card.appendChild(cc);
      });
    });
    return card;
  }

  /**
   * The intent-NATIVE result views. A generic candidate list treats "buy AAPL cheaper" the
   * same as "trade a condor" — these don't:
   *   ACQUIRE  -> a discount ladder ("name your price")
   *   EXIT     -> exit rungs vs your basis ("pick where you're happy to sell")
   *   HEDGE    -> insurance quotes ("pick your floor, see the premium")
   * Shaped per level: Beginner reads as sentences with a recommended rung, Expert gets the
   * table with tap-to-expand cards, Pro gets every rung dense with all columns.
   */
  function ladderView(result, intent, ctx) {
    var rungs = result.rungs || [];
    var level = Learn.currentLevel();
    var wrap = el('div', { class: 'card', id: 'ladder-view' });
    var titles = {
      ACQUIRE: ['Name your price', 'Each rung is a cash-secured put: pick the price you\u2019d happily pay, get paid to wait for it.'],
      EXIT: ['Pick your exit', 'Each rung is a covered call on your shares: pick the price you\u2019d happily sell at, get paid to be patient.'],
      HEDGE: ['Pick your floor', 'Each rung is a protective put: a guaranteed minimum sale price until expiration, for a premium.']
    };
    wrap.appendChild(UI.cardHeader(titles[intent][0]));
    wrap.appendChild(explain(titles[intent][1]));
    if (!rungs.length) {
      wrap.appendChild(UI.emptyState('No tradable rungs right now', (result.notes || []).join(' ')));
      return wrap;
    }

    function strikeOf(c) { return parseFloat(c.legs[0].strike); }
    function spotPct(c, strike) {
      return ctx.spot ? ((strike - ctx.spot) / ctx.spot * 100) : null;
    }
    function gainVsBasis(c) {
      if (!ctx.basisCents || !c.effectivePrice) return null;
      return (parseFloat(c.effectivePrice) * 100 - ctx.basisCents) / ctx.basisCents * 100;
    }
    // Recommended rung: closest to the user's target if given, else the middle of the ladder
    var recommended = 0;
    if (ctx.targetPrice) {
      var best = Infinity;
      rungs.forEach(function (c, i) {
        var d = Math.abs(strikeOf(c) - ctx.targetPrice);
        if (d < best) { best = d; recommended = i; }
      });
    } else {
      recommended = Math.min(rungs.length - 1, Math.floor(rungs.length / 2));
    }

    function sentence(c) {
      var k = strikeOf(c);
      var pct = spotPct(c, k);
      if (intent === 'ACQUIRE') {
        return el('span', {}, 'Buy at ', el('b', {}, '$' + k), pct !== null ? ' (' + pct.toFixed(1) + '% below now)' : '',
          ' \u2014 you\u2019re paid ', el('b', { class: 'gain' }, fmtMoney(c.entryNetPremiumCents)), ' to wait; if it gets there you own it at ',
          el('b', {}, '$' + c.effectivePrice), '. Chance: ' + fmtPct(c.assignmentProb) + '.');
      }
      if (intent === 'EXIT') {
        var g = gainVsBasis(c);
        return el('span', {}, 'Sell at ', el('b', {}, '$' + k),
          ' \u2014 paid ', el('b', { class: 'gain' }, fmtMoney(c.entryNetPremiumCents)), ' now; if called away you effectively sell at ',
          el('b', {}, '$' + c.effectivePrice), g !== null ? ' (' + (g >= 0 ? '+' : '') + g.toFixed(1) + '% vs what you paid)' : '',
          '. Chance you sell: ' + fmtPct(c.assignmentProb) + '.');
      }
      var floorPct = pct !== null ? Math.abs(pct).toFixed(1) : null;
      return el('span', {}, 'Floor at ', el('b', {}, '$' + k), floorPct ? ' (' + floorPct + '% below now)' : '',
        ' \u2014 costs ', el('b', { class: 'loss' }, fmtMoney(c.maxLossCents)), ' until ' + (c.legs[0].expiration || '') + '.');
    }

    if (level === 'beginner') {
      // Sentences, recommended rung first and marked; the exact contracts stay one tap away
      var list = el('div', { class: 'ladder-sentences' });
      rungs.forEach(function (c, i) {
        var row = el('div', { class: 'ladder-row' + (i === recommended ? ' recommended' : '') },
          i === recommended ? el('span', { class: 'badge badge-ok' }, 'A SANE MIDDLE') : null,
          sentence(c),
          el('button', {
            class: 'btn btn-sm', style: 'margin-left:auto', onclick: function () {
              App.state.ticket = { world: App.state.world || 'observed', candidate: c, symbol: ctx.symbol, step: 5 };
              App.navigate('#/trade/place');
            }
          }, 'Practice this'));
        list.appendChild(row);
      });
      wrap.appendChild(list);
    } else {
      var pro = level === 'expert';
      var headers = intent === 'ACQUIRE'
        ? ['You\u2019d buy at', 'vs now', 'Paid to wait', 'Effective price', 'Chance', 'Yield/yr while waiting'].concat(pro ? ['Cash set aside'] : [])
        : intent === 'EXIT'
          ? ['You\u2019d sell at', 'vs now', 'Paid now', 'Effective sale', ctx.basisCents ? 'vs your basis' : 'Chance', 'Chance you sell'].concat(pro ? ['Yield/yr'] : [])
          : ['Floor', 'vs now', 'Cost', 'Cost as % of shares', 'Until'].concat(pro ? ['POP w/ shares'] : []);
      var rows = rungs.map(function (c, i) {
        var k = strikeOf(c);
        var pct = spotPct(c, k);
        var cells;
        if (intent === 'ACQUIRE') {
          cells = [el('b', {}, '$' + k), pct === null ? '\u2014' : pct.toFixed(1) + '%',
            el('span', { class: 'gain' }, fmtMoney(c.entryNetPremiumCents)), '$' + c.effectivePrice,
            fmtPct(c.assignmentProb), c.annualizedYieldPct != null ? fmtNum(c.annualizedYieldPct, 1) + '%' : '\u2014'];
          if (pro) cells.push(fmtMoney(c.maxLossCents));
        } else if (intent === 'EXIT') {
          var g = gainVsBasis(c);
          cells = [el('b', {}, '$' + k), pct === null ? '\u2014' : '+' + pct.toFixed(1) + '%',
            el('span', { class: 'gain' }, fmtMoney(c.entryNetPremiumCents)), '$' + c.effectivePrice,
            ctx.basisCents ? el('span', { class: g >= 0 ? 'gain' : 'loss' }, (g >= 0 ? '+' : '') + g.toFixed(1) + '%') : fmtPct(c.assignmentProb),
            fmtPct(c.assignmentProb)];
          if (pro) cells.push(c.annualizedYieldPct != null ? fmtNum(c.annualizedYieldPct, 1) + '%' : '\u2014');
        } else {
          var costPct = ctx.spot ? (c.maxLossCents / (ctx.spot * 100 * (c.qty || 1))) : null;
          cells = [el('b', {}, '$' + k), pct === null ? '\u2014' : pct.toFixed(1) + '%',
            el('span', { class: 'loss' }, fmtMoney(c.maxLossCents)),
            costPct === null ? '\u2014' : (costPct * 100).toFixed(2) + '%',
            (c.legs[0].expiration || '')];
          if (pro) cells.push(fmtPct(c.pop));
        }
        var tds = cells.map(function (v) { return el('td', {}, v); });
        tds.push(el('td', {}, el('button', {
          class: 'btn btn-sm', onclick: function (e) {
            e.stopPropagation();
            App.state.ticket = { world: App.state.world || 'observed', candidate: c, symbol: ctx.symbol, step: 5 };
            App.navigate('#/trade/place');
          }
        }, 'Use')));
        var detail = el('tr', { class: 'compare-detail', style: 'display:none' },
          el('td', { colspan: String(headers.length + 1) }));
        var row = pressable(el('tr', {
          class: 'clickable' + (i === recommended ? ' ladder-recommended' : ''), 'aria-expanded': 'false', onclick: function () {
            var open = detail.style.display !== 'none';
            detail.style.display = open ? 'none' : '';
            this.setAttribute('aria-expanded', String(!open));
            if (!open && !detail.firstChild.firstChild) detail.firstChild.appendChild(candidateCard(c, true, ctx.symbol));
          }
        }, tds), 'Show details for ' + c.displayName, 'button');
        return [row, detail];
      });
      var tbody = el('tbody', {});
      rows.forEach(function (pair) { tbody.appendChild(pair[0]); tbody.appendChild(pair[1]); });
      wrap.appendChild(el('div', { class: 'tbl-wrap' },
        el('table', { class: 'tbl ladder-tbl' },
          el('thead', {}, el('tr', {}, headers.concat(['']).map(function (h) { return el('th', {}, h); }))),
          tbody)));
      wrap.appendChild(el('p', { class: 'muted' }, 'Tap a rung for the full card. The highlighted rung is '
        + (ctx.targetPrice ? 'closest to your target.' : 'a sane middle of the ladder.')));
    }
    return wrap;
  }

  /** INCOME: a yield board over the user's ACTUAL capital, not an abstract list. */
  function incomeBoard(r, acct, held) {
    var board = el('div', { class: 'card', id: 'income-board' });
    board.appendChild(UI.cardHeader('Your income picture'));
    var shares = (held || []).map(function (p) { return p.shares + ' ' + p.symbol; }).join(', ');
    var best = (r.candidates || []).filter(function (c) { return c.entryNetPremiumCents > 0; })
      .sort(function (a, b) { return b.entryNetPremiumCents - a.entryNetPremiumCents; })[0];
    board.appendChild(el('div', { class: 'chip-row' },
      chip('Free cash', fmtMoney(acct.buyingPowerCents)),
      shares ? chip('Shares to rent out', shares) : null,
      best ? chip('Best single idea pays', el('b', { class: 'gain' }, fmtMoney(best.entryNetPremiumCents))) : null,
      best && best.annualizedYieldPct != null ? chip('At', fmtNum(best.annualizedYieldPct, 1) + '%/yr on its capital') : null));
    board.appendChild(explain(Learn.currentLevel() === 'beginner'
      ? 'Income means selling option premium against money or shares you already have. You keep the premium no matter what; the trade-off is assignment — being made to buy (puts) or sell (calls) at the strike.'
      : 'Premium against your capital. Yield is quoted on the capital each structure actually ties up; assignment odds are the price of that yield.'));
    return board;
  }

  // ---- Manual: "I have a view" ----

  // ---------- 4. Guided ticket ----------

  // Screening lives in Discover now — Place is purely: Strikes -> Review -> Confirm.
  var STEPS = ['Strikes', 'Review', 'Confirm']; // displayed; internal t.step stays 5/6/7

  async function ticket(root) {
    var t = App.state.ticket = App.state.ticket || {};
    t.symbol = t.symbol || App.context.symbol('AAPL');
    // The engine sizes candidates (qty may be 3 for "cover all 300 shares") — never
    // silently reset that to 1; the user can still change it on the Strikes step.
    t.qty = t.qty || (t.candidate && t.candidate.qty) || 1;
    var hasIdea = !!(t.candidate || (t.custom && t.legs && t.legs.length));
    if (!hasIdea) {
      root.appendChild(el('div', { class: 'card' },
        UI.emptyState('Nothing to place yet',
          'Pick an idea on the Ideas tab or build one leg-by-leg in the Builder — it lands here for the strike check, the honest review, and the paper confirm.',
          'Find an idea', function () { App.navigate('#/trade/discover'); }),
        el('div', { class: 'btn-row', style: 'justify-content:center' },
          el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { App.navigate('#/trade/shape'); } },
            'Or open the builder'))));
      return;
    }
    t.step = Math.max(5, Math.min(7, t.step || 5));

    root.appendChild(el('div', { class: 'wizard-steps' }, STEPS.map(function (s, i) {
      var n = i + 5; // internal numbering
      var cls = n === t.step ? ' active' : (n < t.step ? ' done' : '');
      return el('span', { class: 'step' + cls },
        el('span', { class: 'dot' }, n < t.step ? '✓' : String(i + 1)), s);
    })));
    var body = el('div', { class: 'card', id: 'ticket-body' });
    root.appendChild(body);

    function rerender() { App.render(); }
    function nav(step) {
      if (step < 5) { App.navigate('#/trade/discover/manual'); return; } // back past Strikes = re-discover
      t.step = step;
      rerender();
    }

    function backNext(backStep, nextNode) {
      return el('div', { class: 'btn-row' },
        el('button', { class: 'btn btn-secondary', onclick: function () { nav(backStep); } }, '← Back'),
        nextNode || null);
    }

    if (t.step === 5) {
      var c = t.candidate;
      if (!c) { t.step = 6; rerender(); return; } // custom legs skip the strike picker
      t.legs = t.legs && t.legsFor === c.label ? t.legs : JSON.parse(JSON.stringify(c.legs));
      t.legsFor = c.label;
      body.appendChild(el('h2', { class: 'mt0' }, 'Strikes & size'));
      body.appendChild(explain('These strikes came from the screen. Adjust them if you like — the preview re-checks everything against live data.'));
      var exp = (t.legs.find(function (l) { return l.type !== 'STOCK'; }) || {}).expiration;
      var chain = null;
      var chainError = null;
      if (exp) {
        try { chain = await API.get('/api/research/' + t.symbol + '/chain?expiration=' + exp); }
        catch (e) { chainError = e; }
      }
      if (chainError) {
        body.appendChild(alertBox('warn', 'Could not load the option chain',
          ['Strikes cannot be adjusted right now — the candidate\u2019s own strikes are kept. ' + (chainError.message || '')]));
      }
      var strikes = chain ? (chain.calls || []).map(function (q) { return q.strike; }) : [];
      t.legs.forEach(function (leg, i) {
        if (leg.type === 'STOCK') {
          body.appendChild(el('div', { class: 'chip-row' }, chip('Leg ' + (i + 1), legLabel(leg))));
          return;
        }
        var legStrikes = strikes.length ? strikes : [leg.strike]; // chain failure: keep the candidate's strike visible
        var select = el('select', { id: 'leg-strike-' + i, style: 'max-width:130px' }, legStrikes.map(function (k) {
          return el('option', { value: k, selected: parseFloat(k) === parseFloat(leg.strike) ? '' : null }, stripZeros(k));
        }));
        select.addEventListener('change', function () { leg.strike = select.value; });
        body.appendChild(el('div', { class: 'btn-row', style: 'margin-top:6px' },
          el('span', { class: 'badge ' + (leg.action === 'BUY' ? 'badge-ok' : 'badge-warn') }, leg.action),
          el('span', {}, leg.ratio + 'x'), select,
          el('span', { class: 'muted' }, leg.type + ' · expires ' + leg.expiration)));
      });
      var qtyInput = el('input', { type: 'number', id: 'ticket-qty', min: '1', max: '100', value: t.qty || c.qty || 1, style: 'max-width:110px' });
      body.appendChild(el('div', { class: 'field', style: 'margin-top:12px' }, el('label', {}, 'Quantity'), qtyInput));
      body.appendChild(backNext(4, el('button', {
        class: 'btn', id: 'to-review', onclick: function () {
          try { t.qty = positiveInteger(qtyInput.value || '1', 'Quantity', 100); }
          catch (e) { UI.toast(e.message, 'error'); return; }
          nav(6);
        }
      }, 'Review →')));
    }

    if (t.step === 6) {
      var c6 = t.candidate;
      if (!c6 && !t.custom) { nav(4); return; }
      body.appendChild(el('h2', { class: 'mt0' }, 'Review before you commit'));
      body.appendChild(UI.spinner('Previewing against live data…'));
      try {
        var previewReq = {
          symbol: t.symbol, strategy: t.custom ? (t.customFamily || 'CUSTOM') : c6.strategy, qty: t.qty,
          legs: t.legs, thesis: t.thesis, horizon: t.horizon, riskMode: riskMode()
        };
        if (c6 && c6.intent && c6.intent !== 'DIRECTIONAL') previewReq.intent = c6.intent;
        if (c6 && c6.usesHeldShares) previewReq.useHeldShares = true;
        if (t.proposedNetCents !== undefined && t.proposedNetCents !== null) previewReq.proposedNetCents = t.proposedNetCents;
        if (t.feesOverrideCents !== undefined && t.feesOverrideCents !== null) previewReq.feesOverrideCents = t.feesOverrideCents;
        var res = await API.post('/api/trades/preview', previewReq);
        t.previewReq = previewReq;
        var p = res.preview, g = res.guardrails;
        body.innerHTML = '';
        body.appendChild(el('h2', { class: 'mt0' }, 'Review before you commit'));
        // LOCKED CONTEXT (interaction contract #5): the reviewed package's symbol cannot
        // silently mutate — changing goes back through the workflow.
        body.appendChild(lockedSymbolBar(t.symbol));
        var vp = verdictPanel(p, Learn.currentLevel() === 'beginner');
        body.appendChild(vp.node);
        if (res.accountFit) {
          var af = res.accountFit;
          body.appendChild(el('div', { class: 'chip-row', id: 'account-fit' },
            el('b', { style: 'margin-right:4px' }, 'Your real account'),
            af.pctOfNlv !== undefined ? chip('of NLV', af.pctOfNlv + '%') : null,
            af.pctOfCashBp !== undefined ? chip('of cash BP', af.pctOfCashBp + '%') : null,
            af.pctOfMarginBp !== undefined ? chip('of margin BP', af.pctOfMarginBp + '%') : null,
            af.pctOfRiskCapital !== undefined ? chip('of risk capital',
              el('span', { class: af.overRiskCapital ? 'loss' : '' }, af.pctOfRiskCapital + '%')) : null));
          if (af.overRiskCapital) {
            body.appendChild(alertBox('warn', 'Bigger than YOUR risk-capital line',
              ['The theoretical worst case exceeds the per-trade risk capital you set for your real account.']));
          }
        }
        if (!p.ok) body.appendChild(alertBox('danger', 'Blocked', p.blockReasons));
        if (g && g.blockReasons && g.blockReasons.length) body.appendChild(alertBox('danger', 'Guardrails', g.blockReasons));
        var warns = (p.warnings || []).concat(g ? g.warnings || [] : []);
        if (warns.length) body.appendChild(alertBox('warn', 'Heads up', warns));
        // Safety checklist: the guardrail verdict, restated as things a human can verify
        var checks = [];
        var coveredByShares = t.previewReq && t.previewReq.useHeldShares;
        checks.push({ state: p.ok && (p.maxLossCents > 0 || coveredByShares) ? 'pass' : 'fail',
          text: p.maxLossCents > 0
            ? 'Theoretical worst case is known and capped at ' + fmtMoney(p.maxLossCents) + (p.feesOpenCents ? ' (+' + fmtMoney(p.feesOpenCents) + ' fees)' : '')
            : coveredByShares
              ? 'No new cash at risk — the trade is backed by shares you already hold (their own downside continues)'
              : 'Theoretical worst case could not be verified' });
        var overBudget = (g ? (g.warnings || []) : []).some(function (w) { return w.indexOf('risk budget') >= 0; });
        checks.push({ state: overBudget ? 'warn' : 'pass',
          text: overBudget ? 'Bigger than your chosen risk-mode budget — allowed, but oversized'
                           : 'Fits inside your risk-mode budget' });
        if (t.previewReq && t.previewReq.useHeldShares) {
          checks.push({ state: 'pass',
            text: 'Covered by shares you already hold — they are locked while this trade is open and can be called away at the strike' });
        }
        var pe = p.evidence || {};
        var expectedProv = App.state.world === 'demo' ? 'DEMO'
          : App.state.world && App.state.world !== 'observed' ? 'SIMULATED' : 'OBSERVED';
        var provenanceMatches = pe.provenance === expectedProv
          || (expectedProv === 'OBSERVED' && pe.provenance === 'BROKER');
        checks.push({ state: !provenanceMatches ? 'fail'
            : pe.age === 'STALE' || pe.age === 'MISSING' ? 'fail'
            : pe.age === 'DELAYED' || pe.age === 'EOD' ? 'warn' : 'pass',
          text: !provenanceMatches
            ? 'Data-lane mismatch: this ' + expectedProv + ' ticket was priced from ' + (pe.provenance || 'unknown') + ' data'
            : pe.provenance === 'DEMO' ? 'Priced inside the explicit Demo market on fabricated teaching data'
            : pe.provenance === 'SIMULATED' ? 'Priced inside this simulated market and its isolated account'
            : pe.age === 'REALTIME' ? 'Priced on observed real-time quotes'
            : 'Priced on observed ' + (pe.age || p.freshness) + ' quotes — executable prices may move' });
        var zeroDteWarn = warns.some(function (w) { return w.indexOf('0DTE') >= 0; });
        if (zeroDteWarn) checks.push({ state: 'warn', text: 'Expires TODAY — value can go to zero within hours' });
        var closedWarn = warns.some(function (w) { return w.toLowerCase().indexOf('market is closed') >= 0; });
        if (closedWarn) checks.push({ state: 'warn', text: 'Market is closed — fills simulate against the last session' });
        (p.blockReasons || []).forEach(function (r) { checks.push({ state: 'fail', text: r }); });
        body.appendChild(el('div', { class: 'card', style: 'margin:10px 0; box-shadow:none' },
          el('h3', { class: 'mt0' }, 'Safety check'),
          el('div', { class: 'checklist' }, checks.map(function (ck) {
            return el('div', { class: 'ck ck-' + ck.state },
              el('span', { class: 'ck-mark' }, ck.state === 'pass' ? '\u2713' : ck.state === 'warn' ? '!' : '\u2715'),
              el('span', {}, ck.text));
          }))));

        body.appendChild(el('div', { class: 'grid grid-4' },
          stat('Cost / credit', fmtMoney(p.entryNetPremiumCents, { plus: true }), 'Negative = you pay this to open. Positive = you collect it.'),
          stat('Theoretical max loss', el('span', { class: 'loss' }, fmtMoney(p.maxLossCents)), 'The structural worst case for this position, fees excluded.'),
          stat('Theoretical max profit', UI.maxProfitLabel(t.previewReq && t.previewReq.strategy,
            t.candidate && t.candidate.structureGroup, p.maxProfitCents, Learn.currentLevel() === 'beginner', p.legs)),
          stat('Fees', fmtMoney(p.feesOpenCents), '$0.65 per contract per leg by default.'),
          stat(UI.term('pop', Learn.currentLevel() === 'beginner' ? 'Chance of any profit' : 'POP'), fmtPct(p.popEntry), 'Modeled probability of any profit at expiration — before commissions, not a promise.'),
          stat('Breakevens', (p.breakevens || []).map(fmtBreakeven).join(' / ') || '—'),
          stat('Buying power after', fmtMoney(p.buyingPowerAfterCents), 'Drops by exactly the theoretical max loss plus fees.'),
          stat('Cash after', fmtMoney(p.cashAfterCents))));
        if (p.ok && window.Scenario) {
          var scenarioPkg = t.candidate || {
            strategy: t.previewReq && t.previewReq.strategy,
            legs: t.previewReq && t.previewReq.legs,
            qty: t.previewReq && t.previewReq.qty
          };
          if (scenarioPkg && scenarioPkg.legs && scenarioPkg.legs.length) {
            // Preserve this reviewed ticket's exact opening value, including a user-entered limit.
            scenarioPkg = Object.assign({}, scenarioPkg, { entryNetPremiumCents: p.entryNetPremiumCents });
            body.appendChild(Scenario.realisticOutcomes(
              t.symbol || (t.previewReq && t.previewReq.symbol), scenarioPkg));
          }
        }
        // EXPLICIT budget reconciliation (risk/experience decoupling): the header's selected
        // limit is a rule this ticket either fits or exceeds — say which, in dollars and %.
        (function budgetLine() {
          try {
          // ONE SOURCE OF TRUTH (review P0): the SERVER's /api/risk-budget numbers — the same
          // effective budget the engine sized with and the guardrail advisory warned against.
          // No client percentage arithmetic; if the contract hasn't loaded, the line waits.
          var riskSel = document.getElementById('risk-mode');
          var modeKey = riskSel ? riskSel.value : 'conservative';
          function renderLine(rb) {
            if (!rb || !rb.modes || !p.maxLossCents) return;
            var m = null;
            rb.modes.forEach(function (x) { if (x.mode === modeKey) m = x; });
            if (!m || !m.effectiveBudgetCents) return;
            var budget = m.effectiveBudgetCents;
            var ratio = Math.round(p.maxLossCents / budget * 100);
            var line = el('div', { class: 'muted small', id: 'budget-reconcile',
              style: ratio > 100 ? 'color: var(--risk-danger-solid, #c53030)' : '' },
              'This position risks ' + fmtMoney(p.maxLossCents) + ' \u2014 ' + ratio + '% of your selected '
              + fmtMoney(budget) + ' per-idea limit (' + m.label
              + (m.capped ? ', capped by your declared risk capital' : '') + ').'
              + (ratio > 100 ? ' Exceeding it is allowed here \u2014 deliberately, and acknowledged below.' : ''));
            var stale = body.querySelector('#budget-reconcile');
            if (stale) stale.replaceWith(line); else body.appendChild(line);
          }
          if (App.state.riskBudget) renderLine(App.state.riskBudget);
          else API.get('/api/risk-budget').then(function (rb) {
            App.state.riskBudget = rb;
            if (body.isConnected) renderLine(rb);
          }).catch(function () { /* context line only */ });
          } catch (e) { /* the reconciliation line is context, never a blocker */ }
        })();
        // Expert: judge the package at YOUR limit, not the model's executable assumption.
        if (Learn.currentLevel() === 'expert') {
          var netIn = el('input', { type: 'number', step: '0.01', id: 'proposed-net',
            placeholder: 'e.g. ' + (p.entryNetPremiumCents / 100).toFixed(2),
            value: t.proposedNetCents !== undefined && t.proposedNetCents !== null ? (t.proposedNetCents / 100).toFixed(2) : '' });
          var feesIn = el('input', { type: 'number', step: '0.01', id: 'fees-override',
            value: t.feesOverrideCents !== undefined && t.feesOverrideCents !== null ? (t.feesOverrideCents / 100).toFixed(2) : '' });
          body.appendChild(el('div', { class: 'card card-slim', style: 'margin:8px 0' },
            el('h3', { class: 'mt0' }, 'Evaluate at your price'),
            el('div', { class: 'form-grid' },
              el('div', { class: 'field' }, el('label', {}, 'Net price $ (+credit / \u2212debit)'), netIn),
              el('div', { class: 'field' }, el('label', {}, 'Fees $ (blank = default)'), feesIn),
              el('div', { class: 'field' }, el('label', {}, '\u00a0'), el('button', {
                class: 'btn btn-sm', id: 'reprice-btn', onclick: function () {
                  t.proposedNetCents = netIn.value === '' ? null : Math.round(parseFloat(netIn.value) * 100);
                  t.feesOverrideCents = feesIn.value === '' ? null : Math.round(parseFloat(feesIn.value) * 100);
                  nav(6);
                }
              }, 'Re-price'))),
            el('div', { class: 'muted small' }, 'Theoretical max loss, breakevens, POP and EV all follow the price YOU set \u2014 use it to judge a limit order or a real fill.')));
        }
        // The SERVER's required-acknowledgment list is the contract (client wording is a fallback);
        // the signed token + checked ids travel with the create call, where they are ENFORCED.
        var serverAcks = res.requiredAcks || vp.requiredAcks;
        t.ackToken = res.ackToken || null;
        var ackState = {};
        var continueBtn = el('button', {
          class: 'btn', id: 'to-confirm', disabled: 'disabled',
          onclick: function () {
            t.preview = p;
            t.ackIds = serverAcks.filter(function (ak) { return ackState[ak.id]; })
              .map(function (ak) { return ak.id; });
            nav(7);
          }
        }, 'Continue →');
        function refreshGate() {
          var allAcked = serverAcks.every(function (ak) { return ackState[ak.id]; });
          if (p.ok && allAcked) continueBtn.removeAttribute('disabled');
          else continueBtn.setAttribute('disabled', 'disabled');
        }
        if (serverAcks.length) {
          body.appendChild(el('div', { class: 'card card-slim ack-gate', style: 'margin:8px 0' },
            el('h3', { class: 'mt0' }, 'Before you continue'),
            serverAcks.map(function (ak) {
              var cb = el('input', { type: 'checkbox', id: ak.id });
              cb.addEventListener('change', function () { ackState[ak.id] = cb.checked; refreshGate(); });
              return el('label', { class: 'ack-row', for: ak.id }, cb, el('span', {}, ak.label));
            })));
        }
        body.appendChild(backNext(5, continueBtn));
        refreshGate();
      } catch (e) {
        body.innerHTML = '';
        body.appendChild(alertBox('danger', e.message));
        body.appendChild(backNext(5));
      }
    }

    if (t.step === 7) {
      var p7 = t.preview;
      if (!p7) { nav(6); return; }
      body.appendChild(el('h2', { class: 'mt0' }, 'Place paper trade'));
      body.appendChild(alertBox('warn', 'This is a PAPER trade — practice money only. It will reserve ' + fmtMoney(p7.reserveCents) +
        ' and reduce buying power by ' + fmtMoney(p7.buyingPowerBeforeCents - p7.buyingPowerAfterCents) + '.'));
      body.appendChild(el('div', { class: 'chip-row' }, t.legs.map(function (l, i) { return chip('Leg ' + (i + 1), legLabel(l)); })));
      body.appendChild(backNext(6, el('button', {
        class: 'btn', id: 'place-trade', onclick: async function () {
          var btn = document.getElementById('place-trade');
          btn.disabled = true;
          try {
            var placeBody = Object.assign({}, t.previewReq);
            if (t.recommendationId) placeBody.recommendationId = t.recommendationId; // close the calibration loop
            if (t.ackToken) { placeBody.ackToken = t.ackToken; placeBody.acknowledgedRisks = t.ackIds || []; }
            var res = await API.post('/api/trades', placeBody);
            App.state.ticket = null;
            App.navigate('#/trade/' + res.trade.id);
          } catch (e) {
            btn.disabled = false;
            var prev = document.getElementById('place-error');
            if (prev) prev.remove();
            var err = alertBox('danger', e.message, e.payload && e.payload.reasons);
            err.id = 'place-error';
            body.appendChild(err);
          }
        }
      }, 'Place paper trade')));
    }
  }

  function stockOrderModal(side, symbol, maxShares) {
    var symInput = el('input', { type: 'text', id: 'stock-symbol', value: symbol || '' });
    var qty = el('input', { type: 'number', id: 'stock-shares', min: '1',
      value: side === 'sell' && maxShares ? String(maxShares) : '100' });
    UI.confirmModal(side === 'buy' ? 'Buy shares' : 'Sell shares',
      el('div', {},
        explain(side === 'buy'
          ? 'Fills at the current ask. Owning 100+ shares unlocks covered calls (income, selling at a target) and protective puts.'
          : 'Fills at the current bid. Shares locked under an open covered call or collar cannot be sold until that trade closes.'),
        el('div', { class: 'field' }, el('label', {}, 'Symbol'), symInput),
        el('div', { class: 'field', style: 'margin-top:8px' }, el('label', {}, 'Shares'), qty)),
      side === 'buy' ? 'Buy' : 'Sell',
      async function () {
        var shares = positiveInteger(qty.value, 'Shares');
        if (side === 'sell' && maxShares && shares > maxShares) {
          throw new Error('Only ' + maxShares + ' shares are available to sell.');
        }
        await API.post('/api/positions/' + side,
          { symbol: symInput.value.trim(), shares: shares });
        App.render();
      });
  }

  async function portfolio(root, params) {
    // One money home: Positions (holdings + trades) | Activity (the ledger) | Account
    // (starting cash, reset). The old #/account URL lands on the Account section.
    var section = params[0] === 'activity' ? 'activity' : params[0] === 'account' ? 'account' : params[0] === 'record' ? 'record' : 'positions';
    var tab = params[0] === 'closed' ? 'closed' : 'active';
    var page = parseInt(params[1] || '0', 10);
    root.appendChild(el('h1', {}, 'Portfolio'));

    // Account + summary are both needed on every section — fetch them together, not in series.
    var summaryP = API.get('/api/portfolio/summary').catch(function () { return null; });
    var acctData = await API.get('/api/account');
    var acct = acctData.account;
    // The headline this page exists for: total value + P/L at current marks (liquidation
    // view, pre-close-fees). Falls back to plain account stats if marks are unavailable.
    var statsRow = el('div', { class: 'grid grid-4', id: 'pf-stats', style: 'margin-bottom:14px' });
    root.appendChild(statsRow);
    try {
      var sum = await summaryP;
      if (!sum) throw new Error('summary unavailable');
      var pct = sum.startingCashCents ? (sum.totalPnlCents / sum.startingCashCents) * 100 : 0;
      statsRow.appendChild(stat('Portfolio value',
        el('span', {}, fmtMoney(sum.totalValueCents),
          sum.complete ? null : el('span', { class: 'badge badge-caution', style: 'margin-left:6px' }, 'PARTIAL')),
        'Cash + your shares + what closing every open trade would pay right now, before close fees.'));
      statsRow.appendChild(stat('P/L since start',
        el('span', { class: sum.totalPnlCents >= 0 ? 'gain' : 'loss' },
          fmtMoney(sum.totalPnlCents, { plus: true }) + ' (' + (pct >= 0 ? '+' : '') + pct.toFixed(2) + '%)'),
        'Everything measured against the ' + fmtMoney(sum.startingCashCents) + ' you started with.'));
      statsRow.appendChild(stat('Cash', fmtMoney(sum.cashCents),
        fmtMoney(sum.reservedCents) + ' of it is reserved to cover open-trade worst cases.'));
      statsRow.appendChild(stat('Buying power', fmtMoney(sum.buyingPowerCents),
        'Cash minus reserves — what you can still put at risk.'));
    } catch (e) {
      statsRow.appendChild(stat('Cash', fmtMoney(acct.cashCents)));
      statsRow.appendChild(stat('Reserved', fmtMoney(acct.reservedCents)));
      statsRow.appendChild(stat('Buying power', fmtMoney(acct.buyingPowerCents)));
      statsRow.appendChild(stat('Started with', fmtMoney(acct.startingCashCents)));
    }
    root.appendChild(el('div', { class: 'tabs' },
      el('button', { class: section === 'positions' ? 'active' : '', id: 'pf-sec-positions',
        onclick: function () { App.navigate('#/portfolio'); } }, 'Positions'),
      el('button', { class: section === 'activity' ? 'active' : '', id: 'pf-sec-activity',
        onclick: function () { App.navigate('#/portfolio/activity'); } }, 'Activity'),
      el('button', { class: section === 'record' ? 'active' : '', id: 'pf-sec-record',
        onclick: function () { App.navigate('#/portfolio/record'); } }, 'Your record'),
      el('button', { class: section === 'account' ? 'active' : '', id: 'pf-sec-account',
        onclick: function () { App.navigate('#/portfolio/account'); } }, 'Account')));

    if (section === 'record') {
      // The learning loop, closed: what the model PREDICTED vs what actually happened to YOUR
      // trades. Nothing else in this product category is honest enough to show this.
      var recCard = el('div', { class: 'card', id: 'your-record' },
        UI.cardHeader('Your record — predicted vs real'),
        explain(Learn.currentLevel() === 'beginner'
          ? 'Every idea you took came with a predicted chance of profit. This compares those predictions with what actually happened — the honest way to know whether the process (and the model) is working.'
          : 'Calibration: surfaced recommendations vs resolved outcomes, bucketed by predicted POP. Well-calibrated = realized win rate tracks the prediction.'),
        UI.spinner('Loading your record…'));
      root.appendChild(recCard);
      try {
        var cal = await API.get('/api/calibration');
        recCard.removeChild(recCard.lastChild);
        var resolved = cal.resolved || 0;
        if (!resolved) {
          recCard.appendChild(UI.emptyState('No closed recommendations yet',
            'Take an idea, close it (unwind or settle), and the predicted-vs-real record starts building here.'));
        } else {
          recCard.appendChild(el('div', { class: 'grid grid-4' },
            stat('Resolved trades', String(resolved)),
            stat('Win rate', cal.overallWinRate !== null && cal.overallWinRate !== undefined ? fmtPct(cal.overallWinRate) : '—'),
            stat('Total P/L', pnlSpan(cal.totalPnlCents))));
          if (cal.reliability && cal.reliability.length) {
            recCard.appendChild(el('div', { class: 'field-label', style: 'margin-top:10px' }, 'Reliability — the model said vs what happened'));
            recCard.appendChild(table(['Predicted chance', 'Actual win rate', 'Trades'],
              cal.reliability.map(function (b) {
                return el('tr', {},
                  el('td', {}, b.bucket || (fmtPct(b.fromPop) + ' – ' + fmtPct(b.toPop))),
                  el('td', {}, b.realizedWinRate !== undefined && b.realizedWinRate !== null ? fmtPct(b.realizedWinRate) : '—'),
                  el('td', { class: 'muted' }, String(b.n || b.count || 0)));
              })));
            recCard.appendChild(el('div', { class: 'muted small' },
              'Well-calibrated means the two columns roughly match. A big gap is a lesson about the model, the market — or the trades you pick.'));
          }
          if (cal.note) { recCard.appendChild(el('div', { class: 'muted small' }, cal.note));
          }
        }
      } catch (e) {
        recCard.removeChild(recCard.lastChild);
        recCard.appendChild(alertBox('warn', 'Record unavailable right now', [e.message]));
      }
      return;
    }

    if (section === 'activity') {
      root.appendChild(el('div', { class: 'card' },
        UI.cardHeader('Ledger'),
        explain('Every cash or reserve movement, newest first. The ledger is append-only: nothing is ever erased.'),
        table(['Date', 'Type', 'Amount', 'Cash after', 'Reserved after', 'Memo'],
          (acctData.ledger || []).map(function (r) {
            return el('tr', {},
              el('td', { class: 'muted' }, UI.fmtDate(r.ts)),
              el('td', {}, el('span', { class: 'badge badge-dim' }, r.type)),
              el('td', {}, pnlSpan(r.amountCents)),
              el('td', {}, fmtMoney(r.cashAfterCents)),
              el('td', {}, fmtMoney(r.reservedAfterCents)),
              el('td', { class: 'muted' }, r.memo || ''));
          }))));
      return;
    }
    if (section === 'account') {
      // Keep a typed starting-cash draft across re-renders (e.g. a level flip) instead of
      // snapping back to the account's current value and silently discarding the edit.
      var draftCash = App.state.resetCashDraft;
      var cashInput = el('input', { type: 'number', id: 'reset-cash',
        value: draftCash != null ? draftCash : Math.round(acct.startingCashCents / 100),
        min: '1000', step: '1000', style: 'max-width:150px' });
      cashInput.addEventListener('input', function () { App.state.resetCashDraft = cashInput.value; });
      // The REAL account, self-declared: size warnings and account-fit percentages use these
      // denominators — paper cash was never the number that mattered (the MU sizing lesson).
      var rcBeginner = Learn.currentLevel() === 'beginner';
      var rcCard = el('div', { class: 'card', id: 'risk-context-card' },
        UI.cardHeader('My real account (optional)'),
        explain(rcBeginner
          ? 'Tell StrikeBench about your REAL brokerage account and every trade review will show its worst case as a share of YOUR money, not the practice account.'
          : 'Self-declared denominators for account-fit: % of NLV / cash BP / margin BP, and a hard per-trade risk-capital line. Never inferred from one broker field.'));
      root.appendChild(rcCard);
      (async function () {
        var rc = {};
        try { rc = await API.get('/api/account/risk-context'); } catch (e) { /* form still renders */ }
        function fld(id, label, val) {
          return el('div', { class: 'field' }, el('label', {}, label),
            el('input', { type: 'number', id: id, step: '100', value: val != null ? Math.round(val / 100) : '' }));
        }
        function fldInfo(id, label, val, termKey) {
          var lbl = el('label', {}, label);
          lbl.appendChild(UI.info(termKey));
          return el('div', { class: 'field' }, lbl,
            el('input', { type: 'number', id: id, step: '100', value: val != null ? Math.round(val / 100) : '' }));
        }
        var grid = el('div', { class: 'form-grid' },
          fldInfo('rc-nlv', 'Account value (NLV) $', rc.nlvCents, 'nlv'),
          fld('rc-cash', 'Cash buying power $', rc.cashBpCents),
          rcBeginner ? null : fld('rc-margin', 'Margin buying power $', rc.marginBpCents),
          rcBeginner ? null : fld('rc-maint', 'Maintenance req. $', rc.maintenanceCents),
          fldInfo('rc-risk', 'Risk capital per trade $', rc.riskCapitalCents, 'riskcapital'));
        var saveBtn = el('button', { class: 'btn btn-sm', id: 'rc-save', onclick: async function () {
          saveBtn.disabled = true;
          function cents(id) { var n = document.getElementById(id); if (!n || n.value === '') return null; return Math.round(parseFloat(n.value) * 100); }
          try {
            await API.put('/api/account/risk-context', {
              nlvCents: cents('rc-nlv'), cashBpCents: cents('rc-cash'),
              marginBpCents: cents('rc-margin'), maintenanceCents: cents('rc-maint'),
              riskCapitalCents: cents('rc-risk') });
            saveBtn.textContent = 'Saved';
            setTimeout(function () { saveBtn.textContent = 'Save'; saveBtn.disabled = false; }, 1200);
          } catch (e) {
            saveBtn.disabled = false;
            rcCard.appendChild(alertBox('danger', 'Could not save: ' + e.message));
          }
        } }, 'Save');
        rcCard.appendChild(grid);
        rcCard.appendChild(el('div', { class: 'btn-row' }, saveBtn));
      })();
      root.appendChild(el('div', { class: 'card' },
        UI.cardHeader('Reset account'),
        explain('Resetting voids open practice trades, removes ALL share holdings, and sets cash to the amount below. History stays in the ledger and audit log.'),
        el('div', { class: 'btn-row' },
          el('label', { class: 'muted' }, 'Starting cash ($) '), cashInput,
          el('button', {
            class: 'btn btn-danger', id: 'reset-btn', onclick: function () {
              var cents = Math.round(parseFloat(cashInput.value || '0') * 100);
              UI.confirmModal('Reset paper account?',
                el('div', {},
                  el('p', {}, 'Cash will be set to ' + fmtMoney(cents) + '. ' + (acct.hasTraded ? 'Your open practice trades will be voided.' : '')),
                  explain('This cannot be undone, but it never touches real money.')),
                'Reset account',
                async function () {
                  await API.post('/api/account/reset', { startingCashCents: cents, confirm: true, force: acct.hasTraded });
                  App.state.resetCashDraft = null;
                  App.navigate('#/portfolio/account');
                }, true);
            }
          }, 'Reset…'))));
      return;
    }

    // Positions view: greeks (expert), holdings, and the trades page are independent —
    // fire them all now and await each where it renders, so they overlap on the wire
    // instead of stacking into a five-request waterfall.
    var pff = App.state.portfolioFilter || {};
    var statusParamEarly = tab === 'active' ? 'ACTIVE' : 'CLOSED';
    var fqEarly = '';
    if (pff.symbol) fqEarly += '&symbol=' + encodeURIComponent(pff.symbol);
    if (pff.intent) fqEarly += '&intent=' + encodeURIComponent(pff.intent);
    var greeksP = (Learn.currentLevel() === 'expert' && tab === 'active')
      ? API.get('/api/portfolio/greeks').catch(function () { return null; }) : null;
    var positionsP = API.get('/api/positions').catch(function (e) { return { error: e }; });
    var tradesP = API.get('/api/trades?status=' + statusParamEarly + '&page=' + page + '&size=15' + fqEarly);
    var tradesExtraP = (tab === 'closed' && page === 0)
      ? Promise.all([
          API.get('/api/trades?status=EXPIRED&page=0&size=50' + fqEarly),
          API.get('/api/trades?status=DELETED&page=0&size=50' + fqEarly)
        ]) : null;

    if (greeksP) {
      try {
        var pg = await greeksP;
        if (pg && pg.positions && pg.positions.length) {
          try {
            var heat = await API.get('/api/portfolio/heat');
            if (heat && heat.activeTrades > 0) {
              root.appendChild(el('div', { class: 'card card-slim', id: 'portfolio-heat' },
                el('div', { class: 'chip-row' },
                  el('b', { style: 'margin-right:4px' }, 'Book heat'),
                  chip('Total worst case', fmtMoney(heat.totalMaxLossCents)),
                  chip('Short-vol trades', String(heat.shortVolTrades)),
                  chip('Top-symbol share', heat.concentrationPct + '%',
                    'How much of the total worst case sits in ONE symbol.'),
                  heat.assignmentCashCents > 0 ? chip('If every short put assigns', fmtMoney(heat.assignmentCashCents),
                    'Cash needed to take delivery on ALL short puts at their strikes.') : null,
                  heat.assignmentCashCents > 0 ? chip('BP after assignment', fmtMoney(heat.postAssignmentBuyingPowerCents)) : null)));
            }
          } catch (e) { /* heat is additive — the page never fails on it */ }
          root.appendChild(el('div', { class: 'card card-slim', id: 'portfolio-greeks' },
            el('div', { class: 'chip-row', style: 'align-items:center' },
              el('b', { style: 'margin-right:4px' }, 'Book greeks'),
              chip('Net \u0394', fmtNum(pg.deltaShares, 0) + ' sh'),
              chip('\u0393', fmtNum(pg.gammaShares, 2) + ' sh/$'),
              chip('\u0398/day', pnlSpan(pg.thetaPerDay * 100)),
              chip('Vega/pt', pnlSpan(pg.vegaPerPoint * 100)),
              pg.complete ? null : el('span', { class: 'badge badge-caution' }, 'PARTIAL'))));
        }
      } catch (e) { /* advisory only */ }
    }

    // Equity holdings: first-class shares with basis, lock state, and a covered-call nudge.
    try {
      var posRes = await positionsP;
      if (posRes && posRes.error) throw posRes.error;
      var held = (posRes && posRes.positions) || [];
      var holdCard = el('div', { class: 'card', id: 'holdings-card' },
        UI.cardHeader('Shares you hold', el('button', {
          class: 'btn btn-sm btn-secondary', id: 'buy-shares-btn',
          onclick: function () { stockOrderModal('buy', App.context.symbol('AAPL')); }
        }, 'Buy shares')));
      var holdLvl = Learn.currentLevel();
      if (!held.length) {
        holdCard.appendChild(explain('No shares yet. Owning shares unlocks covered calls (income and selling at a target) and protective puts.'));
      } else {
        // Same data, level-shaped: plain-language column names for Beginners; an extra
        // Unrealized $ column at Expert (percent alone hides position size).
        var holdHeaders = holdLvl === 'beginner'
            ? ['Symbol', 'Shares', 'What you paid /sh', 'Price now', 'Worth now', 'Since you bought', '']
            : ['Symbol', 'Shares', 'Avg basis', 'Last', 'Value', 'Unrealized', 'Since purchase', ''];
        holdCard.appendChild(table(holdHeaders,
          held.map(function (pv) {
            var gain = pv.gainPct !== null && pv.gainPct !== undefined
              ? el('span', { class: pv.gainPct >= 0 ? 'gain' : 'loss' }, (pv.gainPct >= 0 ? '+' : '') + pv.gainPct.toFixed(1) + '%')
              : el('span', { class: 'muted' }, '\u2014');
            return el('tr', {},
              el('td', {}, el('b', {}, pv.symbol),
                pv.lockedShares > 0 ? el('span', { class: 'badge badge-caution', style: 'margin-left:6px' }, pv.lockedShares + ' LOCKED') : null),
              el('td', {}, String(pv.shares)),
              el('td', {}, fmtMoney(pv.avgCostCents)),
              el('td', {}, pv.lastCents !== null && pv.lastCents !== undefined ? fmtMoney(pv.lastCents) : '\u2014'),
              el('td', {}, pv.marketValueCents !== null && pv.marketValueCents !== undefined ? fmtMoney(pv.marketValueCents) : '\u2014'),
              holdLvl === 'expert' ? el('td', {}, pnlSpan(pv.unrealizedCents)) : null,
              el('td', {}, gain),
              el('td', {}, el('div', { class: 'btn-row', style: 'margin:0' },
                pv.gainPct !== null && pv.gainPct !== undefined && pv.gainPct >= 5 && pv.freeShares >= 100
                  ? el('button', {
                      class: 'btn btn-sm', onclick: function () {
                        App.state.ideasPrefill = { intent: 'EXIT', symbol: pv.symbol };
                        App.navigate('#/recommend/manual');
                      }
                    }, 'Sell at a target\u2026') : null,
                el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { stockOrderModal('buy', pv.symbol); } }, 'Buy'),
                el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { stockOrderModal('sell', pv.symbol, pv.freeShares); } }, 'Sell'))));
          })));
        holdCard.appendChild(explain(holdLvl === 'beginner'
            ? 'LOCKED shares are promised to an open covered call or collar — they can be called away at that trade\u2019s strike and cannot be sold until it closes. "Sell at a target" gets you paid to wait for your price.'
            : 'Locked shares back an open covered call or collar and free up when that trade closes. "Sell at a target" proposes covered calls at your price.'));
      }
      root.appendChild(holdCard);
    } catch (e) {
      // The card must not vanish silently — it holds the only Buy-shares entry point.
      root.appendChild(el('div', { class: 'card', id: 'holdings-card' },
        UI.cardHeader('Shares you hold'),
        alertBox('warn', 'Could not load your share holdings', [e.message]),
        el('div', { class: 'btn-row' },
          el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { App.render(); } }, 'Retry'))));
    }

    // ONE trades card: segmented Active|Closed in the header, filters inside, then the
    // table (or an honest empty state) and pagination — no second tab row, no floating
    // filter strip, no orphaned pager.
    var seg = el('div', { class: 'seg' },
      el('button', { class: tab === 'active' ? 'active' : '', id: 'tab-active', type: 'button',
        onclick: function () { App.navigate('#/portfolio/active'); } }, 'Active'),
      el('button', { class: tab === 'closed' ? 'active' : '', id: 'tab-closed', type: 'button',
        onclick: function () { App.navigate('#/portfolio/closed'); } }, 'Closed'));
    var fSym = el('input', { type: 'text', id: 'pf-symbol', placeholder: 'symbol', style: 'max-width:110px' });
    var fIntent = el('select', { id: 'pf-intent', style: 'max-width:170px' },
      [el('option', { value: '' }, 'any goal')].concat((Learn.INTENTS || []).map(function (i) {
        return el('option', { value: i.key }, i.label);
      })));
    var applyFilters = function () {
      App.state.portfolioFilter = { symbol: fSym.value.trim(), intent: fIntent.value };
      // A new filter starts from page 0 — keeping a deep page index fakes an empty result
      App.navigate('#/portfolio/' + tab);
    };
    var pf = App.state.portfolioFilter || {};
    fSym.value = pf.symbol || '';
    fIntent.value = pf.intent || '';
    fSym.addEventListener('change', applyFilters);
    fIntent.addEventListener('change', applyFilters);
    // The learning loop's import path: record a trade you ACTUALLY placed at your broker.
    // Structured legs against live contracts — no free-form text, no paper-cash mutation.
    var extCard = el('div', { class: 'card', id: 'record-real-card' });
    extCard.appendChild(UI.expandable('Record a real trade (from your broker)', (function () {
      var box = el('div', {});
      box.appendChild(explain('Enter the exact contracts and your ACTUAL net fill. The trade is tracked, marked and judged like any other — but your practice cash is never touched, and its outcome feeds Your record.'));
      var sym = el('input', { type: 'text', id: 'ext-symbol', list: 'universe-symbols', placeholder: 'AAPL', style: 'max-width:110px' });
      var qty = el('input', { type: 'number', id: 'ext-qty', min: '1', max: '100', value: '1', style: 'max-width:80px' });
      var net = el('input', { type: 'number', id: 'ext-net', step: '0.01', placeholder: '+credit / \u2212debit $' });
      var fees = el('input', { type: 'number', id: 'ext-fees', step: '0.01', placeholder: 'fees $' });
      var legsBox = el('div', { id: 'ext-legs' });
      function legRow() {
        return el('div', { class: 'btn-row ext-leg', style: 'margin:4px 0' },
          el('select', { class: 'x-act' }, el('option', {}, 'SELL'), el('option', {}, 'BUY')),
          el('select', { class: 'x-type' }, el('option', {}, 'PUT'), el('option', {}, 'CALL')),
          el('input', { class: 'x-strike', type: 'number', step: '0.5', placeholder: 'strike', style: 'max-width:100px' }),
          el('input', { class: 'x-exp', type: 'date', style: 'max-width:150px' }),
          el('input', { class: 'x-fill', type: 'number', step: '0.01', placeholder: 'fill $/sh', style: 'max-width:100px',
            title: 'Your per-leg fill — required for past trades whose contracts have expired' }),
          el('button', { class: 'btn btn-sm', onclick: function (ev) { ev.target.closest('.ext-leg').remove(); } }, '\u00d7'));
      }
      legsBox.appendChild(legRow());
      var execDate = el('input', { type: 'date', id: 'ext-date', title: 'When the trade actually executed' });
      var brokerIn = el('input', { type: 'text', id: 'ext-broker', placeholder: 'broker (optional)', style: 'max-width:130px' });
      var refIn = el('input', { type: 'text', id: 'ext-ref', placeholder: 'order # (optional)', style: 'max-width:130px' });
      var pastChk = el('input', { type: 'checkbox', id: 'ext-past' });
      var msg = el('div', { class: 'muted small', id: 'ext-msg' });
      var saveBtn = el('button', { class: 'btn btn-sm', id: 'ext-save', onclick: async function () {
        saveBtn.disabled = true; msg.textContent = '';
        try {
          var legs = Array.prototype.map.call(legsBox.querySelectorAll('.ext-leg'), function (row) {
            var fillV = row.querySelector('.x-fill').value;
            var lg = { action: row.querySelector('.x-act').value, type: row.querySelector('.x-type').value,
                       strike: row.querySelector('.x-strike').value, expiration: row.querySelector('.x-exp').value, ratio: 1 };
            if (fillV !== '') lg.entryPrice = fillV;
            return lg;
          }).filter(function (l) { return l.strike && l.expiration; });
          if (pastChk.checked && legs.some(function (l) { return !l.entryPrice; })) {
            throw new Error('A past trade needs YOUR fill price on every leg — the live book is gone.');
          }
          if (!legs.length) throw new Error('Add at least one leg (strike + expiration).');
          if (net.value === '') throw new Error('The actual net fill is required.');
          var orderQty = positiveInteger(qty.value || '1', 'Quantity', 100);
          var netValue = Number(net.value);
          var feeValue = fees.value === '' ? null : Number(fees.value);
          if (!Number.isFinite(netValue)) throw new Error('Enter a valid net fill.');
          if (feeValue !== null && (!Number.isFinite(feeValue) || feeValue < 0)) throw new Error('Fees must be zero or more.');
          var t = await API.post('/api/trades/external', {
            symbol: (sym.value || '').toUpperCase(), strategy: 'CUSTOM', qty: orderQty,
            legs: legs, proposedNetCents: Math.round(netValue * 100),
            feesOverrideCents: feeValue === null ? null : Math.round(feeValue * 100),
            executedAt: execDate.value || null, broker: brokerIn.value || null, orderRef: refIn.value || null,
            historical: pastChk.checked, source: 'IMPORT' });
          msg.textContent = 'Recorded ' + t.id + ' — it now appears below with an EXTERNAL badge.';
          App.render();
        } catch (e) { msg.textContent = 'Refused: ' + (e.message || e); }
        saveBtn.disabled = false;
      } }, 'Record trade');
      box.appendChild(el('div', { class: 'btn-row' },
        el('span', { class: 'muted small' }, 'Symbol'), sym,
        el('span', { class: 'muted small' }, 'Qty'), qty, net, fees,
        el('button', { class: 'btn btn-sm', onclick: function () { legsBox.appendChild(legRow()); } }, '+ Leg')));
      box.appendChild(legsBox);
      box.appendChild(el('div', { class: 'btn-row' },
        el('span', { class: 'muted small' }, 'Executed'), execDate, brokerIn, refIn,
        el('label', { class: 'muted small', style: 'display:flex;gap:4px;align-items:center' }, pastChk,
          el('span', {}, 'Past trade (contracts may have expired \u2014 uses your fills, no live check)'))));
      box.appendChild(el('div', { class: 'btn-row' }, saveBtn));
      box.appendChild(msg);
      return box;
    })(), false));
    root.appendChild(extCard);

    var tradesCard = el('div', { class: 'card', id: 'trades-card' },
      UI.cardHeader('Practice trades', seg),
      explain('Click any row for the payoff chart, live marks, and close/settle actions.'),
      el('div', { class: 'btn-row', id: 'pf-filters', style: 'margin-top:0' },
        el('span', { class: 'muted' }, 'Filter:'), fSym, fIntent));
    root.appendChild(tradesCard);

    // Trades were prefetched (tradesP/tradesExtraP) alongside greeks + positions above.
    var data = await tradesP;
    if (tradesExtraP) {
      // Settled/voided trades ride along on the first page only (up to 50 each) —
      // repeating them on every page made them look like distinct trades.
      var extra = await tradesExtraP;
      data.trades = data.trades.concat(extra[0].trades, extra[1].trades);
    }
    if (!data.trades.length) {
      tradesCard.classList.add('is-empty');
      tradesCard.appendChild(tab === 'active'
        ? UI.emptyState('No open practice trades', 'Find a risk-screened idea and practice it — the worst case is known before you commit.', 'Find an idea', function () { App.navigate('#/trade/discover'); })
        : UI.emptyState('Nothing closed yet', 'Closed, settled, and voided trades land here.'));
      return;
    }
    var rows = data.trades.map(function (t) {
      return pressable(el('tr', {
        class: 'clickable', onclick: function () { App.navigate('#/trade/' + t.id); }
      },
        el('td', {}, el('b', {}, t.symbol)),
        el('td', {}, prettyStrategy(t.strategy),
          t.origin === 'EXTERNAL' ? el('span', { class: 'badge badge-warn', style: 'margin-left:6px', title: 'A real trade you recorded — tracked and judged here, but your practice cash was never touched.' }, 'EXTERNAL') : null,
          t.intent && t.intent !== 'DIRECTIONAL' ? el('span', { style: 'margin-left:6px' }, intentBadge(t.intent)) : null),
        el('td', {}, 'x' + t.qty),
        el('td', {}, pnlSpan(t.entryNetPremiumCents)),
        el('td', { class: 'loss' }, fmtMoney(t.maxLossCents)),
        tab === 'active'
          ? el('td', { 'data-now-for': t.id }, t.unrealizedPnlCents !== undefined && t.unrealizedPnlCents !== null
              ? pnlSpan(t.unrealizedPnlCents) : el('span', { class: 'muted' }, '—'))
          : null,
        el('td', {}, tab === 'active' ? el('span', { class: 'muted' }, UI.fmtDate(t.createdAt)) : pnlSpan(t.realizedPnlCents)),
        el('td', {}, el('span', { class: 'badge ' + (t.status === 'ACTIVE' ? 'badge-ok' : t.status === 'DELETED' ? 'badge-danger' : 'badge-dim') }, t.status))),
        'Open ' + t.symbol + ' ' + prettyStrategy(t.strategy), 'link');
    });
    tradesCard.appendChild(table(
      tab === 'active'
        ? ['Symbol', 'Strategy', 'Qty', 'Entry credit/debit', 'Theor. max loss', 'Now', 'Opened', 'Status']
        : ['Symbol', 'Strategy', 'Qty', 'Entry credit/debit', 'Theor. max loss', 'Realized P/L', 'Status'], rows));
    // Inside a simulated session the book visibly MOVES: each world tick (already server-throttled)
    // refreshes the active rows' Now P/L in place — light trades-list fetch, no re-render.
    if (tab === 'active' && App.onEvent && App.state.world && App.state.world !== 'observed') {
      var _prt = App.navToken, _nowLast = 0;
      App.onEvent('world.tick', async function () {
        if (!App.alive(_prt)) return;
        var nowMs = Date.now();
        if (nowMs - _nowLast < 8000) return; // marks memoize ~10s server-side — refetching faster buys nothing
        _nowLast = nowMs;
        try {
          var fresh = await API.getFresh('/api/trades?status=ACTIVE&page=' + page + '&size=15');
          if (!App.alive(_prt) || !fresh || !fresh.trades) return;
          fresh.trades.forEach(function (ft) {
            var cell = document.querySelector('[data-now-for="' + ft.id + '"]');
            if (!cell) return;
            while (cell.firstChild) cell.removeChild(cell.firstChild);
            cell.appendChild(ft.unrealizedPnlCents !== undefined && ft.unrealizedPnlCents !== null
              ? pnlSpan(ft.unrealizedPnlCents) : el('span', { class: 'muted' }, '—'));
          });
        } catch (e) { /* next tick retries */ }
      }, _prt);
    }
    if (data.total > 15) {
      tradesCard.appendChild(el('div', { class: 'btn-row' },
        page > 0 ? el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { App.navigate('#/portfolio/' + tab + '/' + (page - 1)); } }, '← Newer') : null,
        (page + 1) * 15 < data.total ? el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { App.navigate('#/portfolio/' + tab + '/' + (page + 1)); } }, 'Older →') : null));
    }
  }

  // ---------- 6. Trade detail + 7. confirm flows ----------

  async function tradeDetail(root, params) {
    var id = params[0];
    var d = await API.get('/api/trades/' + id);
    var t = d.trade;
    var active = t.status === 'ACTIVE';
    var pnl = active
      ? (d.current ? d.current.unrealizedCents : null)
      : t.realizedPnlCents;

    root.appendChild(el('div', { class: 'card' },
      el('div', { class: 'quote-hero' },
        el('span', { class: 'sym' }, t.symbol),
        el('span', { class: 'nm' }, prettyStrategy(t.strategy) + ' · x' + t.qty),
        t.origin === 'EXTERNAL' ? el('span', { class: 'badge badge-warn', title: 'Real trade recorded from your broker — outcomes feed Your record; paper cash untouched.' }, 'EXTERNAL') : null,
        intentBadge(t.intent),
        el('span', { class: 'badge ' + (active ? 'badge-ok' : t.status === 'DELETED' ? 'badge-danger' : 'badge-dim') }, t.status),
        el('span', { class: 'spacer' }),
        pnl !== null && pnl !== undefined
          ? el('span', { class: 'px ' + (pnl >= 0 ? 'gain' : 'loss') }, fmtMoney(pnl, { plus: true }))
          : null,
        el('button', { class: 'btn btn-sm btn-secondary', style: 'margin-left:8px',
          title: 'Full analysis for ' + t.symbol + ' \u2014 the trade itself stays exactly as placed',
          onclick: function () { App.navigate('#/research/' + t.symbol); } }, 'Research')),
      el('div', { class: 'muted' },
        (active ? 'Unrealized (before close fees)' : 'Realized P/L') + ' · opened ' + UI.fmtDate(t.createdAt)
        + (t.closeReason ? ' · ' + t.closeReason : '')),
      // The first thing a novice sees after placing is a red number — the bid/ask cost of
      // entering. Say so, once, while the trade is young, instead of letting it read as a mistake.
      active && pnl !== null && pnl < 0 && isYoungTrade(t.createdAt)
        ? explain('New positions start a little down — that\u2019s the bid/ask spread you paid to enter, not a mistake. It fades as the trade develops.')
        : null,
      el('div', { class: 'chip-row' }, t.legs.map(function (l, i) { return chip('Leg ' + (i + 1), legLabel(l)); })),
      el('div', { class: 'chip-row' },
        chip('Entry', fmtMoney(t.entryNetPremiumCents, { plus: true })),
        t.sharesLocked > 0 && t.maxLossCents === 0
          ? chip('New cash at risk', '$0 (covered)')
          : chip('Theoretical max loss', el('span', { class: 'loss' }, fmtMoney(t.maxLossCents))),
        chip('Theoretical max profit', UI.maxProfitLabel(t.strategy, null, t.maxProfitCents,
          Learn.currentLevel() === 'beginner', t.legs)),
        chip('Breakeven', (t.breakevens || []).map(fmtBreakeven).join(' / ') || '—'),
        chip('POP entry', fmtPct(t.popEntry)),
        d.current && d.current.popNow !== null && d.current.popNow !== undefined ? chip('POP now', fmtPct(d.current.popNow)) : null,
        t.sharesLocked > 0 ? chip('Covered by', t.sharesLocked + ' held sh (locked)') : null,
        chip('Fees', fmtMoney(t.feesOpenCents + t.feesCloseCents)))));
    if (t.sharesLocked > 0 && active) {
      root.appendChild(el('div', { class: 'card' },
        explain('This trade is covered by ' + t.sharesLocked + ' shares you hold — they cannot be sold while it is open. '
          + 'If the short call finishes in the money at expiration, those shares are called away at the strike (that is the plan for a sell-at-target trade).')));
    }

    if (Learn.currentLevel() !== 'expert') {
      var detailGuide = guideBlock(t.strategy);
      if (detailGuide) root.appendChild(el('div', { class: 'card' }, detailGuide));
    }

    if (Learn.currentLevel() === 'expert' && d.current && d.current.greeks) {
      var gk = d.current.greeks;
      var greekCard = el('div', { class: 'card', id: 'greeks-card' },
        UI.cardHeader('Position greeks', gk.complete ? null : el('span', { class: 'badge badge-caution' }, 'PARTIAL')),
        el('div', { class: 'chip-row' },
          chip('Net \u0394', fmtNum(gk.deltaShares, 0) + ' sh'),
          chip('\u0393', fmtNum(gk.gammaShares, 2) + ' sh/$'),
          chip('\u0398/day', pnlSpan(gk.thetaPerDay * 100)),
          chip('Vega/pt', pnlSpan(gk.vegaPerPoint * 100))));
      if (d.current.legGreeks && d.current.legGreeks.length) {
        greekCard.appendChild(table(['Leg', 'Bid', 'Ask', '\u0394', '\u0393', '\u0398', 'Vega', 'IV'],
          d.current.legGreeks.map(function (lg) {
            return el('tr', {},
              el('td', { class: 'mono' }, lg.leg),
              el('td', {}, lg.bid || '\u2014'),
              el('td', {}, lg.ask || '\u2014'),
              el('td', {}, lg.delta === null || lg.delta === undefined ? '\u2014' : fmtNum(lg.delta, 2)),
              el('td', {}, lg.gamma === null || lg.gamma === undefined ? '\u2014' : fmtNum(lg.gamma, 3)),
              el('td', {}, lg.theta === null || lg.theta === undefined ? '\u2014' : fmtNum(lg.theta, 3)),
              el('td', {}, lg.vega === null || lg.vega === undefined ? '\u2014' : fmtNum(lg.vega, 3)),
              el('td', {}, lg.iv === null || lg.iv === undefined ? '\u2014' : fmtPct(lg.iv)));
          })));
      }
      greekCard.appendChild(el('p', { class: 'muted' },
        'Share-equivalent \u0394/\u0393; \u0398 and vega in dollars per day / per vol point. Model statistics from current marks.'));
      root.appendChild(greekCard);
    }

    if (d.payoff && d.payoff.length > 1) {
      var spotNow = d.current && d.current.underlyingCents ? d.current.underlyingCents / 100 : t.entryUnderlyingCents / 100;
      root.appendChild(el('div', { class: 'card' },
        UI.cardHeader('Payoff at expiration'),
        explain('Profit or loss for the whole position by expiration price. Dashed orange line = price now; BE dots mark breakevens.'),
        UI.payoffChart(d.payoff, { spot: spotNow, breakevens: t.breakevens })));
    }

    if (active) {
      root.appendChild(el('div', { class: 'card' },
        UI.cardHeader('Actions'),
        el('div', { class: 'btn-row', style: 'margin-top:0' },
          el('button', {
            class: 'btn btn-secondary', id: 'refresh-btn', onclick: async function (ev) {
              var btn = ev.currentTarget;
              btn.disabled = true;
              try {
                await API.post('/api/trades/' + id + '/refresh');
                App.render();
              } catch (e) {
                btn.disabled = false;
                var old = document.getElementById('refresh-error');
                if (old) old.remove();
                var err = alertBox('danger', 'Refresh failed', [e.message]);
                err.id = 'refresh-error';
                btn.closest('.card').appendChild(err);
              }
            }
          }, 'Refresh marks'),
          el('button', {
            class: 'btn', id: 'unwind-btn', onclick: function () {
              var est = d.current && d.current.closeCostCents !== null && d.current.closeCostCents !== undefined
                ? 'Closing now brings ' + fmtMoney(d.current.closeCostCents, { plus: true }) + ' before fees. '
                  + 'This is the executable close (selling at bid, buying back at ask) — the header\u2019s P/L is marked at the midpoint, so the two differ by the spread you pay to exit. ' : '';
              UI.confirmModal('Close (unwind) this trade?',
                el('div', {},
                  el('p', {}, est + 'The reserve of this trade is released and the result becomes final.'),
                  explain('Unwinding = doing the opposite of every leg at current prices.')),
                'Close position',
                async function () {
                  await API.post('/api/trades/' + id + '/unwind', { confirm: true });
                  App.navigate('#/trade/' + id);
                });
            }
          }, 'Unwind…', el('span', { class: 'btn-sub' }, 'close now at market')),
          el('button', {
            class: 'btn btn-secondary', id: 'settle-btn', onclick: function () {
              UI.confirmModal('Settle at expiration value?',
                el('p', {}, 'Only possible after all legs expired. Cash settles at intrinsic value.'),
                'Settle',
                async function () {
                  await API.post('/api/trades/' + id + '/settle', { confirm: true });
                  App.navigate('#/trade/' + id);
                });
            }
          }, 'Settle…', el('span', { class: 'btn-sub' }, 'after expiration')),
          el('button', {
            class: 'btn btn-secondary', id: 'roll-btn', onclick: function () {
              UI.confirmModal('Roll this position?',
                el('div', {},
                  el('p', {}, 'Rolling = two paper orders: close this position at market now, then reopen the same structure with every option leg moved ~1 month later. Both orders pay fees; the builder opens so you can adjust strikes before placing.'),
                  explain('Management plans often say "roll at 21 DTE" — this is that action, honestly priced at current quotes.')),
                'Close & rebuild',
                async function () {
                  await API.post('/api/trades/' + id + '/unwind', { confirm: true });
                  var rolled = (t.legs || []).map(function (l) {
                    var leg = { action: l.action, type: l.stock ? 'STOCK' : l.type, strike: String(l.strike || ''), ratio: l.ratio || 1 };
                    if (!l.stock && l.expiration) {
                      var d2 = new Date(l.expiration); d2.setDate(d2.getDate() + 28);
                      leg.expiration = d2.toISOString().slice(0, 10);
                    }
                    return leg;
                  });
                  App.state.builderForm = { symbol: t.symbol, qty: t.qty, goal: 'BROWSE', templateKey: null,
                    step: 4, legIdx: 0, legs: rolled, excluded: {} };
                  App.navigate('#/trade/shape');
                });
            }
          }, 'Roll…', el('span', { class: 'btn-sub' }, 'close + reopen ~1 month out')),
          el('span', { class: 'spacer' }),
          el('button', {
            class: 'btn btn-danger', id: 'delete-btn', onclick: function () {
              UI.confirmModal('Void this trade?',
                el('div', {},
                  el('p', {}, 'This voids the trade as if it never happened: entry cash and fees are reversed, the reserve is released.'),
                  UI.alertBox('warn', 'Practice-only affordance: real brokers have no undo — losses do not un-happen. Voiding a losing trade erases the lesson with it; prefer Unwind to practice honest exits.'),
                  explain('Everything stays visible in the ledger and audit log — nothing is hidden.')),
                'Void trade',
                async function () {
                  await API.del('/api/trades/' + id + '?confirm=true');
                  App.navigate('#/portfolio');
                }, true);
            }
          }, 'Void…', el('span', { class: 'btn-sub' }, 'erase — practice only')))));
    }

    if (d.marksHistory && d.marksHistory.length) {
      // Enough refreshes to draw? Show unrealized P/L over time with the standard
      // interactive chart before the raw table.
      var markPts = d.marksHistory
        .filter(function (m) { return m.unrealizedCents !== null && m.unrealizedCents !== undefined; })
        .map(function (m) { return { date: (m.ts || '').slice(0, 16).replace('T', ' '), value: m.unrealizedCents }; })
        .reverse();
      root.appendChild(el('div', { class: 'card' },
        UI.cardHeader('Marks history'),
        markPts.length >= 3 ? UI.lineChart(markPts, { money: true }) : null,
        table(['When', 'Underlying', 'Close cost', 'Unrealized', 'POP', 'Freshness'],
          d.marksHistory.map(function (m) {
            return el('tr', {},
              el('td', { class: 'muted' }, (m.ts || '').replace('T', ' ').slice(0, 16)),
              el('td', {}, m.underlyingCents !== null && m.underlyingCents !== undefined ? fmtMoney(m.underlyingCents) : '—'),
              el('td', {}, m.closeCostCents !== null && m.closeCostCents !== undefined ? fmtMoney(m.closeCostCents) : '—'),
              el('td', {}, pnlSpan(m.unrealizedCents)),
              el('td', {}, fmtPct(m.popNow)),
              el('td', {}, badge(m.freshness)));
          }))));
    }

    if (d.audit && d.audit.length) {
      root.appendChild(el('div', { class: 'card' },
        UI.cardHeader('Audit trail'),
        d.audit.map(function (a) {
          return el('div', { class: 'status-item' },
            el('span', { class: 'badge ' + (a.level === 'BLOCK' ? 'badge-danger' : a.level === 'WARN' ? 'badge-warn' : 'badge-dim') }, a.level),
            el('span', {}, a.action),
            el('span', { class: 'spacer' }),
            el('span', { class: 'muted' }, (a.ts || '').replace('T', ' ').slice(0, 16)));
        })));
    }
  }

  // ---------- 7b. Scenario surfaces (the thesis workbench + "imagine a future") ----------

  /** Research: "I think X happens next" → hundreds of simulated futures → hand off to Verify. */
  function whatIfCard(symbol) {
    var level = Learn.currentLevel();
    var historyBasis = activeHistoryBasis();
    var card = el('div', { class: 'card', id: 'whatif-card' });
    card.appendChild(UI.cardHeader('What COULD happen next?'));
    card.appendChild(explain(level === 'beginner'
      ? 'Pick the story you believe and we draw hundreds of model-generated “possible futures” for ' + symbol + ' — so you can see the range of outcomes, then test a strategy against it. Historical evidence checks the same belief against ' + historyBasis.plain + '.'
      : 'Parameterize a scenario (model, drift, vol, jumps, IV path) and preview the Monte-Carlo fan; hand it to Verify to run a structure against it. The paired evidence lens is a ' + historyBasis.expert + '.'));
    var f = Scenario.form(level, symbol);
    card.appendChild(f.el);
    var out = el('div', { id: 'whatif-out' });
    var previewSeq = 0;
    var toVerify = el('button', { class: 'btn btn-secondary', id: 'whatif-verify', style: 'display:none' }, 'Test a strategy under this »');
    var run = el('button', { class: 'btn', id: 'whatif-run' }, level === 'beginner' ? 'Show me 200 possible futures' : 'Preview the fan');
    var reroll = el('button', { class: 'btn btn-sm btn-secondary', id: 'whatif-reroll', title: 'New random seed — a different set of futures' }, 'Re-roll');
    run.addEventListener('click', async function () {
      var seq = ++previewSeq;
      run.disabled = true; out.innerHTML = ''; out.appendChild(UI.spinner('Drawing futures…'));
      try {
        var p = await App.evaluateOutcome('PATHS', 'PARAMETRIC', symbol, { over: f.getSpec() });
        if (seq !== previewSeq) return;
        out.innerHTML = '';
        out.appendChild(Scenario.fanChart(p));
        toVerify.style.display = '';
      } catch (e) {
        if (seq === previewSeq) { out.innerHTML = ''; out.appendChild(alertBox('danger', 'Could not simulate', [String((e && e.message) || e)])); }
      }
      finally { if (seq === previewSeq) run.disabled = false; }
    });
    reroll.addEventListener('click', function () { f.reroll(); run.click(); });
    toVerify.addEventListener('click', function () {
      App.context.selectSymbol(symbol);
      App.state.evidencePrefill = null; // generated futures must never inherit an earlier analog ensemble
      App.state.verifyMode = 'scenario'; // the handoff: Verify opens in "Imagine a future"
      App.navigate('#/trade/verify');
    });
    card.appendChild(el('div', { class: 'btn-row' }, run, reroll, toVerify));
    card.appendChild(out);
    return card;
  }

  /** Verify → "Imagine a future": run a position through the scenario's Monte Carlo. */
  function scenarioVerifyPanel(host) {
    host.innerHTML = '';
    var level = Learn.currentLevel();
    var vf = App.state.verifyForm = App.state.verifyForm || {};
    var symbol = ((App.context.symbol() || vf.simSymbol || 'AAPL') + '').toUpperCase();
    vf.simSymbol = symbol;
    var card = el('div', { class: 'card', id: 'bt-scenario-card' });

    // The MARKET CONTEXT SELECTOR: sector (the same polished rail as everywhere else) +
    // searchable symbol + the FULL universe as one non-wrapping peer rail. The old version
    // wrapped eight bare pills with no way to change sector — scattered, and a dead end.
    var symInput = el('input', { type: 'text', id: 'sc-symbol', value: symbol, list: 'universe-symbols', style: 'max-width:120px' });
    function switchSymbol(raw) {
      var s2 = (raw || '').trim().toUpperCase();
      if (!s2 || s2 === vf.simSymbol) return;
      vf.simSymbol = s2;
      App.context.selectSymbol(s2);
      scenarioVerifyPanel(host); // self-rebuild: header, peers, working-idea eligibility all follow
    }
    symInput.addEventListener('change', function () { switchSymbol(symInput.value); });
    symInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') switchSymbol(symInput.value); });
    card.appendChild(UI.cardHeader('Imagine a future for ' + symbol));
    card.appendChild(UI.symbolContext({
      mode: 'editable', id: 'scenario-symbol-context', input: symInput,
      label: level === 'beginner' ? 'Which stock?' : 'Scenario symbol',
      commitLabel: 'Apply', onCommit: switchSymbol
    }));
    // EVIDENCE MODE banner: Research handed over its analog windows — the runs below price on
    // THOSE real occurrences (clearable; falls back to generated futures).
    var evb = App.state.evidencePrefill;
    if (evb && evb.symbol === symbol && evb.source) {
      var evBanner = el('div', { class: 'alert alert-caution', id: 'evidence-mode' },
        el('b', {}, 'Evidence mode: '),
        'strategies below run on ' + (evb.label || 'the study\u2019s historical analog windows')
          + ' \u2014 conditional history, not a model. Horizon locked to the study\u2019s '
          + evb.horizonDays + ' days. ',
        el('button', { class: 'btn btn-sm btn-secondary', id: 'evidence-clear', onclick: function () {
          App.state.evidencePrefill = null; App.render();
        } }, 'Use generated futures instead'));
      card.appendChild(evBanner);
    }
    // Sector first: picking one switches the shared universe (same behavior as Ideas/Research)
    // and refreshes the peer rail below.
    card.appendChild(el('div', { class: 'field' },
      el('label', {}, 'Universe'),
      sectorRail({ onPick: function () { setTimeout(function () { scenarioVerifyPanel(host); }, 250); } })));
    var peerRail = el('div', { class: 'peer-rail', id: 'sc-uni-chips' });
    ((App.state.universe && App.state.universe.active && App.state.universe.active.symbols) || [])
      .forEach(function (u) {
        peerRail.appendChild(el('button', { type: 'button', class: 'sym-chip' + (u === symbol ? ' active' : ''),
          onclick: function () { switchSymbol(u); } }, u));
      });
    card.appendChild(el('div', { class: 'field' }, el('label', {}, 'Stocks in this universe'), peerRail));
    var demoLane = App.config && App.config.fixturesOnly && (App.state.world || 'demo') === 'demo';
    var entryBasis = demoLane
      ? 'the built-in demo option book (fabricated teaching prices)'
      : 'the active market\u2019s executable option quotes when a listed contract matches';
    card.appendChild(explain(level === 'beginner'
      ? 'Three choices: which stock, what story you believe (the price path), and which position (the payoff shape). We run the position through hundreds of simulated futures and report, in plain dollars, how it tends to end. The entry is priced from ' + entryBasis + '.'
      : 'Monte-Carlo the position across the scenario paths. Entry is priced from ' + entryBasis + '; exits are BSM along the IV path (default: the active chain\u2019s ATM IV). The verdict measures your scenario against that entry basis.'));
    var f = Scenario.form(level, symbol);
    card.appendChild(f.el);

    // The position: your working idea (same symbol only) or the FULL strategy catalog —
    // the same breadth as "All strategies", each with its payoff-shape sketch. The sketch is
    // the strategy's profit-vs-price identity; the story cards above sketch the PRICE PATH.
    var working = Scenario.workingLegs();
    var workingSymbol = App.state.ticket && App.state.ticket.symbol;
    var workingHere = working && workingSymbol === symbol;
    var picked = { key: workingHere ? (vf.quick === undefined ? 'WORKING' : vf.quick) : (vf.quick || 'DEBIT_CALL_SPREAD') };
    if (!workingHere && picked.key === 'WORKING') picked.key = 'DEBIT_CALL_SPREAD';
    var posWrap = el('div', { id: 'sc-pos' });
    function posCard(key, label, pay, group) {
      return el('button', { type: 'button', 'data-pos': key,
        class: 'q-card sc-card' + (picked.key === key ? ' active' : ''),
        onclick: function () {
          picked.key = key; vf.quick = key;
          posWrap.querySelectorAll('.sc-card').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-pos') === key); });
        } },
        pay ? Scenario.sketch(pay) : null,
        el('b', {}, label),
        group ? el('div', { class: 'muted small' }, group) : null);
    }
    posWrap.appendChild(el('div', { class: 'field-label', style: 'margin-top:10px' },
      'The position — payoff shape at expiry' + (level === 'beginner' ? ' (what you make or lose at each price)' : '')));
    var posGrid = el('div', { class: 'q-grid sc-grid sc-pos-grid' });
    if (workingHere) posGrid.appendChild(posCard('WORKING', 'Your working idea', null, working.length + ' leg' + (working.length > 1 ? 's' : '') + ' from the ticket'));
    Scenario.CATALOG.forEach(function (q) { posGrid.appendChild(posCard(q.key, q.label, q.pay, q.group)); });
    posWrap.appendChild(posGrid);
    card.appendChild(posWrap);

    var out = el('div', { id: 'sc-verify-out' });
    var actionSeq = 0;
    async function spotFor() {
      var qd = await API.get('/api/quotes?symbols=' + symbol);
      var row = (qd.quotes || [])[0] || {};
      var spot = parseFloat(row.last || row.prevClose);
      if (!isFinite(spot) || spot <= 0) throw new Error('No price for ' + symbol + ' — check the ticker.');
      return spot;
    }
    var run = el('button', { class: 'btn', id: 'sc-verify-run' }, level === 'beginner' ? 'Run it through the futures' : 'Run Monte Carlo');
    run.addEventListener('click', async function () {
      var seq = ++actionSeq;
      run.disabled = true;
      if (compareBtn) compareBtn.disabled = true;
      out.innerHTML = ''; out.appendChild(UI.spinner('Simulating…'));
      try {
        var spec = f.getSpec();
        var legs, spot = null;
        if (picked.key === 'WORKING' && workingHere) {
          legs = working;
        } else {
          spot = await spotFor();
          var qk = Scenario.CATALOG.find(function (x) { return x.key === picked.key; }) || Scenario.CATALOG[0];
          legs = qk.legs(spot, spec.horizonDays + 10);
        }
        // The position AND the do-nothing baseline, on the SAME seeded paths — comparative
        // evidence is what makes a 51% world actionable ("better than just holding shares?").
        // EVIDENCE MODE: when Research handed over its analog windows, the strategy runs on the
        // EXACT historical occurrences the study displayed — one conditional sample, two views.
        var ev = App.state.evidencePrefill;
        var evActive = ev && ev.symbol === symbol && ev.source;
        var basis = evActive ? ev.source : 'PARAMETRIC';
        var study = evActive ? ev.study : null;
        var rP = App.evaluateOutcome('POSITION', basis, symbol, {
          position: App.outcomePosition(picked.key, legs, 1), over: spec, iv: f.getIv(), study: study
        });
        var basP = App.evaluateOutcome('POSITION', basis, symbol, {
          position: App.outcomePosition('BUY_AND_HOLD',
            [{ action: 'BUY', type: 'STOCK', strike: 0, expiryDay: 0, ratio: 1 }], 1),
          over: spec, iv: f.getIv(), study: study
        })
          .catch(function () { return null; });
        var r = await rP, bas = await basP;
        if (seq !== actionSeq) return;
        out.innerHTML = '';
        if (evActive && ev.studyKey && r.studyKey && r.studyKey !== ev.studyKey) {
          out.appendChild(alertBox('warn', 'The underlying data changed since the study was run',
            ['This run re-derived the analogs against CURRENT data \u2014 re-run Past evidence in Research if this surprises you.']));
        }
        if (r.sourceNote) {
          out.appendChild(alertBox('caution', evActive && ev.source === 'HISTORICAL_ANALOGS'
            ? (r.observed ? 'Priced on REAL history, not a model' : 'Priced on DEMO/generated history \u2014 not real, not a model')
            : 'Priced on resampled history', [r.sourceNote]));
        }
        out.appendChild(el('div', { class: 'muted small', style: 'margin:4px 0' },
          f.describe() + ' · entry cost ' + UI.fmtMoneyCompact(r.entryCostCents)));
        out.appendChild(Scenario.pnlView(r, level));
        if (bas) {
          out.appendChild(el('div', { class: 'chip-row' },
            chip('Baseline: just hold 100 shares', UI.fmtMoneyCompact(bas.expectedPnlCents) + ' · ' + Math.round(bas.winRatePct) + '%',
              'The same simulated futures applied to plain stock — the strategy has to beat doing nothing.'),
            chip('This position vs baseline',
              UI.fmtMoneyCompact(r.expectedPnlCents - bas.expectedPnlCents),
              'Expected P&L difference on IDENTICAL paths. Positive = the structure adds value under your scenario.')));
        }
      } catch (e) {
        if (seq === actionSeq) { out.innerHTML = ''; out.appendChild(alertBox('danger', 'Simulation failed', [String((e && e.message) || e)])); }
      }
      finally { if (seq === actionSeq) { run.disabled = false; if (compareBtn) compareBtn.disabled = false; } }
    });

    // The junior's core ask: ONE market view, EVERY compatible structure, identical paths.
    var compareBtn = el('button', { class: 'btn btn-secondary', id: 'sc-compare-all' },
      level === 'beginner' ? 'Which strategy fits this story best?' : 'Compare all strategies');
    compareBtn.addEventListener('click', async function () {
      var seq = ++actionSeq;
      compareBtn.disabled = true; run.disabled = true;
      out.innerHTML = '';
      out.appendChild(UI.spinner('Running every structure on one identical set of futures…'));
      try {
        var spec = f.getSpec(); // one spec, one seed — every structure sees the SAME futures
        var spot = await spotFor();
        // Evidence mode carries into the COMPARISON too: a screen that says Evidence Mode must
        // never silently compare on generated Monte Carlo paths (holistic review #10).
        var evC = App.state.evidencePrefill;
        var evOnC = evC && evC.symbol === symbol && evC.source;
        var positions = Scenario.CATALOG.map(function (q2) {
          return App.outcomePosition(q2.key, q2.legs(spot, spec.horizonDays + 10), 1);
        });
        var cmp = await App.evaluateOutcome('COMPARE', evOnC ? evC.source : 'PARAMETRIC', symbol,
          { positions: positions, over: spec, iv: f.getIv(), study: evOnC ? evC.study : null });
        if (seq !== actionSeq) return;
        if (evOnC && evC.studyKey && cmp.studyKey && cmp.studyKey !== evC.studyKey) {
          out.appendChild(alertBox('warn', 'The underlying data changed since the study was run',
            ['The comparison re-derived the analogs against CURRENT data — re-run Past evidence in Research if this surprises you.']));
        }
        var results = (cmp.results || []).map(function (x) {
          return { q: Scenario.CATALOG.find(function (c2) { return c2.key === x.key; }), r: x.result,
                   fees: x.feesCents || 0 };
        }).filter(function (x) { return x.q; });
        if (!results.length) throw new Error('Nothing could be priced for ' + symbol + '.');
        // FAIR ranking: expected P&L per dollar of realistic downside (|p5|) — raw dollars would
        // let a 100-share CSP dwarf a small spread purely by size. EV stays visible alongside.
        results.forEach(function (x) {
          var evNet = x.r.expectedPnlCents - x.fees; // R9: judged net of round-trip commissions
          x.evNet = evNet;
          x.ror = x.r.p5Cents < 0 ? evNet / Math.abs(x.r.p5Cents) : (evNet > 0 ? 99 : 0);
        });
        results.sort(function (a, b) { return b.ror - a.ror; });
        out.innerHTML = '';
        out.appendChild(el('div', { class: 'muted small', style: 'margin:4px 0' },
          (cmp.pathSource
            ? 'EVIDENCE MODE: every structure ran on the SAME ' + results[0].r.paths
              + ' historical analog windows (' + (cmp.observed ? 'real past occurrences' : 'demo/generated history — not real')
              + '), entries at market quotes where a listed contract matched. '
            : f.describe() + ' — every structure ran on the SAME ' + results[0].r.paths
              + ' futures (one seed-matched set), entries at market quotes where a listed contract matched. ')
          + 'Ranked by expected gain per dollar of realistic downside (1-in-20 bad run) so small and large positions compare fairly. '
          + 'The baseline every structure must beat: CASH \u2014 doing nothing risks nothing and costs nothing.'));
        function rowFor(x) {
          return el('tr', {},
            el('td', {}, el('b', {}, x.q.label), ' ', el('span', { class: 'muted small' }, x.q.group)),
            el('td', {}, Math.round(x.r.winRatePct) + '%'),
            el('td', {}, pnlSpan(x.r.expectedPnlCents)),
            el('td', {}, pnlSpan(x.r.p5Cents)),
            el('td', { title: 'expected P&L per $1 of 1-in-20 downside' },
              x.ror >= 99 ? '∞' : x.ror.toFixed(2)),
            level === 'beginner' ? null : el('td', {}, UI.fmtMoneyCompact(x.r.entryCostCents)),
            el('td', {}, el('button', { class: 'btn btn-sm', onclick: (function (key) { return function () {
              picked.key = key; vf.quick = key;
              posWrap.querySelectorAll('.sc-card').forEach(function (b2) { b2.classList.toggle('active', b2.getAttribute('data-pos') === key); });
              run.click();
            }; })(x.q.key) }, 'Details')));
        }
        var headers = level === 'beginner'
          ? ['Strategy', 'Chance of profit', 'Expected', 'Bad run (1 in 20)', 'Gain per $ risked', '']
          : ['Structure', 'Win %', 'E[P&L]', 'p5', 'RoR', 'Entry', ''];
        if (level === 'beginner' && results.length > 3) {
          // Progressive disclosure: the best three expressions first; the full field one tap away.
          out.appendChild(el('div', { class: 'field-label' }, 'Best three fits for this story'));
          out.appendChild(UI.table(headers, results.slice(0, 3).map(rowFor)));
          var restSlot = el('div', {});
          restSlot.appendChild(el('button', { class: 'btn btn-secondary btn-sm', onclick: function () {
            restSlot.innerHTML = '';
            restSlot.appendChild(UI.table(headers, results.slice(3).map(rowFor)));
          } }, 'Show all ' + results.length + ' structures'));
          out.appendChild(restSlot);
        } else {
          out.appendChild(UI.table(headers, results.map(rowFor)));
        }
        if (cmp.refused && cmp.refused.length) {
          out.appendChild(el('div', { class: 'muted small' },
            'Could not be priced here: ' + cmp.refused.map(function (x) {
              var q3 = Scenario.CATALOG.find(function (c3) { return c3.key === x.key; });
              return (q3 ? q3.label : x.key) + ' (' + x.reason + ')';
            }).join('; ') + '.'));
        }
        out.appendChild(el('div', { class: 'muted small' },
          'Naked calls/puts and short straddles/strangles are not simulated — undefined risk is blocked by design across the app. '
          + 'A different story re-ranks the whole table; the honest conclusion can be that nothing beats the baseline.'));
      } catch (e) {
        if (seq === actionSeq) { out.innerHTML = ''; out.appendChild(alertBox('danger', 'Comparison failed', [String((e && e.message) || e)])); }
      }
      finally { if (seq === actionSeq) { compareBtn.disabled = false; run.disabled = false; } }
    });

    card.appendChild(el('div', { class: 'btn-row' }, run, compareBtn));
    card.appendChild(out);
    host.appendChild(card);
  }

  // ---------- 8. Backtest ----------

  async function backtest(root) {
    // A candidate card's "Backtest this" lands here with the form pre-answered.
    var prefill = App.state.backtestPrefill || {};
    App.state.backtestPrefill = null;
    // The WORKING IDEA prefills the whole form, not just the symbol: family from the candidate,
    // target DTE from its legs' actual expiration — verify the trade you are about to place.
    if (!prefill.strategy && App.state.ticket && App.state.ticket.candidate) {
      var wt = App.state.ticket;
      prefill.symbol = prefill.symbol || wt.symbol;
      prefill.strategy = wt.candidate.strategy;
      try {
        var wexp = (wt.legs || []).map(function (l) { return l.expiration; }).filter(Boolean).sort()[0];
        if (wexp) prefill.dte = Math.max(1, Math.round((new Date(wexp + 'T16:00:00') - Date.now()) / 86400000));
      } catch (e) { /* dte stays default */ }
    }
    // Persist the form per the state-ownership convention: symbol/strategy/window/dte survive nav
    // and level switches instead of resetting to defaults. Prefill (an explicit ask) wins over it.
    var bf = App.state.backtestForm = App.state.backtestForm || {};
    root.appendChild(el('p', { class: 'page-sub' },
      Learn.currentLevel() === 'beginner'
        ? 'Before risking even paper money on a rule like “sell a monthly covered call”, see how it would actually have gone — trade by trade, with honest labels on what is modeled.'
        : 'Replays a strategy rule day by day with no look-ahead — and labels exactly how much of its pricing is modeled.'));

    // Two ways to verify, at BOTH levels: replay the PAST (historical backtest) or imagine a
    // FUTURE (Monte-Carlo scenario). The scenario mode is often the friendlier one for beginners —
    // "what if it drops then recovers?" needs no data plumbing to be a great lesson.
    // ONE VERIFICATION-MODE SELECTOR (the admitted Phase-7 leftover): every way to test a
    // strategy sits behind one explicit choice, each honestly describing its evidence basis.
    // The five modes route to the EXISTING engines — no duplicate machinery.
    var vf = App.state.verifyForm = App.state.verifyForm || {};
    if (App.state.verifyMode) { vf.mode = App.state.verifyMode; App.state.verifyMode = null; } // handoff wins
    vf.mode = vf.mode || 'history';
    var inWorld = App.state.world && App.state.world !== App.baseWorldId();
    var scenarioActive = App.config && App.config.scenarioMode;
    var histWrap = el('div', { id: 'bt-history-mode' });
    var scenWrap = el('div', { id: 'bt-scenario-mode' });
    var worldWrap = el('div', { id: 'bt-world-mode' });
    // The SIMULATED SESSION verification surface (review P1 #5: a labeled mode must never be a
    // blank screen): the session's own trades ARE its verification — live P/L, the report, and
    // one-tap paths to place more or open the console.
    async function worldVerifyPanel(host) {
      host.innerHTML = '';
      host.appendChild(UI.spinner('Loading your session\u2019s record\u2026'));
      try {
        // GENUINE VERIFICATION (review P2): the session REPORT is the record — open AND
        // closed decisions, each with the predicted odds it was entered at and what actually
        // happened (result, worst/best while open, how it ended). Live Now P/L for open rows
        // merges in from the enriched trades list.
        var rep = await API.getFresh('/api/sim/market/' + encodeURIComponent(App.state.world) + '/report');
        var nowBy = {};
        try {
          ((await API.getFresh('/api/trades?status=ACTIVE&size=50')).trades || []).forEach(function (t) {
            if (t.unrealizedPnlCents !== undefined && t.unrealizedPnlCents !== null) nowBy[t.id] = t.unrealizedPnlCents;
          });
        } catch (e2) { /* Now column degrades to em-dash */ }
        var rows = rep.trades || [];
        var pvo = rep.popVsOutcome;
        host.innerHTML = '';
        host.appendChild(el('div', { class: 'card', id: 'world-verify' },
          UI.cardHeader('This simulated session judges your DECISIONS',
            el('span', { class: 'badge badge-sim' }, 'SIMULATED')),
          explain('Every entry was judged at the session\u2019s own clock and prices. Open decisions show live standing; closed ones show the predicted odds against what actually happened.'),
          el('div', { class: 'chip-row' },
            chip('Sim clock', String(rep.simTime || '').replace('T', ' ')),
            chip('Decisions', String(rows.length)),
            chip('Resolved', String(rep.resolved || 0)),
            rep.winRate !== null && rep.winRate !== undefined ? chip('Win rate', rep.winRate + '%') : null,
            rep.realizedPnlCents !== null && rep.realizedPnlCents !== undefined ? chip('Realized', fmtMoney(rep.realizedPnlCents)) : null),
          rows.length
            ? table(['Decision', 'Entered (sim clock)', 'Predicted odds', 'Now / Result', 'Worst / best while open', 'How it ended'],
                rows.map(function (t) {
                  var open = t.realizedPnlCents === null || t.realizedPnlCents === undefined;
                  return pressable(el('tr', { class: 'clickable', onclick: function () { App.navigate('#/trade/' + t.id); } },
                    el('td', {}, el('b', {}, t.symbol), ' ', prettyStrategy(t.strategy) + ' x' + t.qty),
                    el('td', { class: 'mono' }, t.laneEntryTime ? String(t.laneEntryTime).replace('T', ' ') : '\u2014'),
                    el('td', {}, t.popEntry !== null && t.popEntry !== undefined ? Math.round(t.popEntry * 100) + '%' : '\u2014'),
                    el('td', {}, open
                      ? (nowBy[t.id] !== undefined ? pnlSpan(nowBy[t.id]) : el('span', { class: 'badge badge-dim' }, t.status))
                      : pnlSpan(t.realizedPnlCents)),
                    el('td', {}, t.maeCents !== undefined && t.maeCents !== null
                      ? el('span', {}, pnlSpan(t.maeCents), ' / ', pnlSpan(t.mfeCents)) : el('span', { class: 'muted' }, '\u2014')),
                    el('td', { class: 'muted' }, open ? 'still open' : (t.closeReason || '\u2014'))), 'Open ' + t.symbol);
                }))
            : UI.emptyState('No practice trades in this session yet',
                'Find a screened idea and place it \u2014 the session report will judge the decision.',
                'Find ideas in this market', function () { App.navigate('#/trade/discover'); }),
          pvo && (pvo.highPopTrades || pvo.lowPopTrades)
            ? el('p', { class: 'muted', style: 'margin:8px 0 0' },
                'Calibration so far: ' + (pvo.highPopTrades || 0) + ' high-confidence entries (\u226550% predicted) won '
                + (pvo.highPopWinRate === null || pvo.highPopWinRate === undefined ? '\u2014' : pvo.highPopWinRate + '%') + ' of the time; '
                + (pvo.lowPopTrades || 0) + ' low-confidence entries won '
                + (pvo.lowPopWinRate === null || pvo.lowPopWinRate === undefined ? '\u2014' : pvo.lowPopWinRate + '%') + '. '
                + (pvo.note || ''))
            : null,
          el('div', { class: 'btn-row' },
            rows.length ? el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/trade/discover'); } }, 'Find ideas') : null,
            el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { App.navigate('#/data/simulation'); } }, 'Open the control room'),
            el('button', { class: 'btn btn-sm btn-secondary', title: 'Your observed-market record \u2014 simulated sessions are scored here, in their own report, never mixed into it',
              onclick: function () { App.navigate('#/portfolio/record'); } }, 'Your observed record \u2192'))));
      } catch (e) {
        host.innerHTML = '';
        host.appendChild(alertBox('warn', 'Session record unavailable', [String((e && e.message) || e)]));
      }
    }
    var historyNote = App.state.world === 'demo'
      ? 'Replays fabricated Demo history with no look-ahead; every result stays labeled DEMO.'
      : App.state.world && App.state.world !== 'observed'
        ? 'Replays this simulated market\u2019s generated history with no look-ahead.'
        : 'Replays observed daily history with no look-ahead.';
    var outcomeWorkspace = Outcomes.workspace({
      id: 'trade-outcomes', state: vf, label: 'How to test this strategy',
      modes: [
        { key: 'history', label: 'Historical replay', description: 'A rule across past daily prices',
          note: historyNote, render: function (host) { host.appendChild(histWrap); } },
        { key: 'dataset', label: 'Saved scenario', description: 'A generated path saved as candles',
          available: !!scenarioActive,
          note: scenarioActive
            ? 'Your generated scenario dataset is active; the replay reads it and names it.'
            : 'Activate a generated dataset first, then replay it as a disclosed synthetic history.',
          unavailableReason: 'No generated dataset is active in this market.',
          setupLabel: 'Open Datasets', setup: function () { App.navigate('#/data/datasets'); },
          render: function (host) { host.appendChild(histWrap); } },
        { key: 'scenario', label: 'Monte Carlo futures', description: 'Many paths from an explicit model',
          note: 'Hundreds of generated paths from a model you pick: odds of that model, not a forecast.',
          render: function (host) {
            if (!scenWrap.hasChildNodes()) scenarioVerifyPanel(scenWrap);
            host.appendChild(scenWrap);
          } },
        { key: 'analogs', label: 'Historical analogs', description: 'The Research study\u2019s signal episodes',
          available: !!App.state.evidencePrefill,
          note: 'Every strategy runs on the exact conditional-history sample Research found.',
          unavailableReason: 'Run Past evidence on a Research symbol, then hand its occurrences to Trade.',
          setupLabel: 'Find historical analogs',
          setup: function () { App.navigate('#/research/' + App.context.symbol('AAPL')); },
          render: function (host) {
            if (!scenWrap.hasChildNodes()) scenarioVerifyPanel(scenWrap);
            host.appendChild(scenWrap);
          } },
        { key: 'world', label: 'Simulated session', description: 'Decisions inside a moving practice market',
          available: !!inWorld,
          note: inWorld
            ? 'Judge entries against the simulated market\u2019s own clock, fills and outcomes.'
            : 'Create a live generated market with its own clock and practice account.',
          unavailableReason: 'You are not currently inside a simulated market.',
          setupLabel: 'Open Simulated market',
          setup: function () { App.navigate('#/data/simulation'); },
          render: function (host) {
            host.appendChild(worldWrap);
            return worldVerifyPanel(worldWrap);
          } }
      ]
    });
    root.appendChild(outcomeWorkspace.el);
    root = histWrap; // everything below (the historical form + reports) lives in history mode
    var sym = el('input', { type: 'text', id: 'bt-symbol', value: prefill.symbol || App.context.symbol() || bf.symbol || 'AAPL', list: 'universe-symbols' });
    // Typing a symbol here makes it the working symbol app-wide (Backtest → Builder carries it).
    sym.addEventListener('input', function () {
      bf.symbol = sym.value;
      var s = sym.value.trim().toUpperCase(); if (s) App.context.selectSymbol(s);
    });
    // ONE menu for both levels, grouped by goal with the foundational structure leading each
    // group (education ordering, never a gate — presentation-only levels, review P0).
    var BT_GROUPS = [
      { label: 'Trade a view', families: [] },
      { label: 'Earn income', families: [] },
      { label: 'Buy at a discount', families: [] },
      { label: 'Protect shares', families: [] }
    ];
    ((App.strategyCatalog && App.strategyCatalog.catalog) || [])
      .filter(function (m) { return m.backtestEnabled; })
      .sort(function (a, b) { return a.foundationalRank - b.foundationalRank || a.display.localeCompare(b.display); })
      .forEach(function (m) {
        var label = m.name === 'CASH_SECURED_PUT' ? 'Buy at a discount'
          : m.primaryIntent === 'HEDGE' ? 'Protect shares'
          : m.primaryIntent === 'INCOME' ? 'Earn income' : 'Trade a view';
        BT_GROUPS.find(function (g) { return g.label === label; }).families.push(m.name);
      });
    var btLevel = Learn.currentLevel();
    // PRESENTATION-ONLY LEVELS (review P0): the FULL catalog is discoverable at both levels.
    // Beginner sees the same menu with the foundational structures first in each goal group —
    // progressive ordering, never a locked door.
    var btDefaultStrat = prefill.strategy || bf.strategy || 'DEBIT_CALL_SPREAD';
    var strat = el('select', { id: 'bt-strategy' },
      BT_GROUPS.map(function (g) {
        return el('optgroup', { label: g.label }, g.families.map(function (s) {
          var meta = strategyMeta(s);
          return el('option', { value: s, selected: s === btDefaultStrat ? '' : null }, meta ? meta.display : prettyStrategy(s));
        }));
      }));
    strat.addEventListener('change', function () { bf.strategy = strat.value; });
    var today = new Date();
    var toDefault = today.toISOString().slice(0, 10);
    var fromDefault = new Date(today.getTime() - 182 * 86400000).toISOString().slice(0, 10);
    var from = el('input', { type: 'date', id: 'bt-from', value: bf.from || fromDefault });
    var to = el('input', { type: 'date', id: 'bt-to', value: bf.to || toDefault });
    var dte = el('input', { type: 'number', id: 'bt-dte', value: prefill.dte || bf.dte || '30', min: '1', max: '365' });
    from.addEventListener('change', function () { bf.from = from.value; });
    to.addEventListener('change', function () { bf.to = to.value; });
    dte.addEventListener('change', function () { bf.dte = dte.value; });
    var out = el('div', { id: 'bt-results' });

    // Period pills: the same range language as every chart in the app. Dates stay
    // editable for exact windows; the pills just fill them.
    var PERIODS = [{ label: '3M', days: 91 }, { label: '6M', days: 182 }, { label: '1Y', days: 365 }, { label: '2Y', days: 730 }];
    var periodRow = el('div', { class: 'range-pills', id: 'bt-periods', role: 'group', 'aria-label': 'Backtest window' },
      PERIODS.map(function (p) {
        return el('button', {
          class: 'pill' + (p.days === 182 ? ' active' : ''), type: 'button', 'data-days': String(p.days),
          onclick: function () {
            from.value = new Date(Date.now() - p.days * 86400000).toISOString().slice(0, 10);
            to.value = new Date().toISOString().slice(0, 10);
            bf.from = from.value; bf.to = to.value; // persist what the pill changed
            periodRow.querySelectorAll('.pill').forEach(function (b) {
              b.classList.toggle('active', b.getAttribute('data-days') === String(p.days));
            });
          }
        }, p.label);
      }));
    // Hand-edited dates mean "custom window" — no pill should claim it.
    [from, to].forEach(function (n) {
      n.addEventListener('change', function () {
        periodRow.querySelectorAll('.pill').forEach(function (b) { b.classList.remove('active'); });
      });
    });
    var DTE_PRESETS = [{ label: 'Weekly', v: 7 }, { label: 'Monthly', v: 30 }, { label: 'Quarterly', v: 90 }];
    var dtePresets = el('div', { class: 'chip-row', id: 'bt-dte-presets', style: 'margin-top:4px' },
      DTE_PRESETS.map(function (p) {
        return el('button', { class: 'sym-chip', type: 'button', onclick: function () { dte.value = String(p.v); bf.dte = dte.value; } }, p.label);
      }));

    // BOTH levels get both engines (presentation-only levels): beginner default stays the
    // simpler single-position engine, with plain words explaining the difference.
    var engine = el('select', { id: 'bt-engine' },
      el('option', { value: 'single' }, btLevel === 'beginner' ? 'One trade at a time (simplest)' : 'Single position (one trade at a time)'),
      el('option', { value: 'portfolio' }, btLevel === 'beginner' ? 'A book of trades at once' : 'Portfolio (concurrent positions, mechanical exits)'));
    engine.value = bf.engine || 'single';
    engine.addEventListener('change', function () { bf.engine = engine.value; });
    root.appendChild(el('div', { class: 'card' },
      btLevel === 'beginner'
        ? explain('Every strategy is here — the simpler ones lead each group. Target DTE is how far out each trade’s expiration is: Monthly (30 days) is the classic starting point.')
        : null,
      UI.symbolContext({
        mode: 'editable', id: 'backtest-symbol-context', input: sym,
        label: btLevel === 'beginner' ? 'Which stock?' : 'Backtest symbol'
      }),
      el('div', { class: 'form-grid' },
        el('div', { class: 'field' }, el('label', {}, 'Strategy'), strat),
        engine ? el('div', { class: 'field' }, el('label', {}, 'Engine'), engine) : null,
        el('div', { class: 'field' }, el('label', {},
          btLevel === 'beginner' ? 'Days to expiration' : 'Target DTE', UI.info('dte')), dte, dtePresets),
        el('div', { class: 'field' }, el('label', {}, 'Window'), periodRow),
        el('div', { class: 'field' }, el('label', {}, 'From'), from),
        el('div', { class: 'field' }, el('label', {}, 'To'), to)),
      engine ? explain('The portfolio engine currently supports bull put credit spreads and bull call debit spreads. It can overlap positions and apply profit-target, stop and time exits inside one capital pool.') : null,
      el('div', { class: 'btn-row' },
        el('button', {
          class: 'btn', id: 'bt-run', onclick: async function () {
            var btn = document.getElementById('bt-run');
            btn.disabled = true;
            out.innerHTML = '';
            out.appendChild(UI.spinner('Running backtest…'));
            try {
              if (engine && engine.value === 'portfolio') {
                var preport = await API.post('/api/backtest/portfolio', {
                  symbol: sym.value.trim(), strategy: strat.value, from: from.value, to: to.value,
                  targetDte: parseInt(dte.value, 10), entryEveryDays: 5, maxConcurrent: 4, qty: 1,
                  shortDelta: 0.30, widthPct: 0.05, profitTargetPct: 0.5, stopFraction: 0.8,
                  rollDte: 7, startingCashCents: 10000000
                });
                renderPortfolioReport(preport);
              } else {
                renderReport(await API.post('/api/backtest', {
                  symbol: sym.value.trim(), strategy: strat.value,
                  from: from.value, to: to.value, targetDte: parseInt(dte.value, 10)
                }));
              }
            } catch (e) {
              out.innerHTML = '';
              out.appendChild(alertBox('danger', e.message));
            } finally { btn.disabled = false; }
          }
        }, 'Run backtest'))));
    root.appendChild(out);

    try {
      var past = await API.get('/api/backtests');
      if (past.backtests && past.backtests.length) {
        root.appendChild(el('div', { class: 'card' },
          UI.cardHeader('Previous runs'),
          past.backtests.slice(0, 10).map(function (b) {
            return el('div', { class: 'status-item' },
              el('a', {
                href: '#', onclick: async function (e) {
                  e.preventDefault();
                  out.innerHTML = '';
                  out.appendChild(UI.spinner('Loading saved report…'));
                  try {
                    renderReport(await API.get('/api/backtests/' + b.id));
                  } catch (err) {
                    out.innerHTML = '';
                    out.appendChild(alertBox('danger', 'Could not load that saved report', [err.message]));
                  }
                  window.scrollTo(0, 0);
                }
              }, (b.request.symbol || '?') + ' · ' + prettyStrategy(b.request.strategy) + ' · ' + (b.request.from || '') + ' → ' + (b.request.to || '')),
              el('span', { class: 'spacer' }),
              el('span', { class: 'muted' }, (b.createdAt || '').slice(0, 10)));
          })));
      }
    } catch (e) { /* optional */ }

    function renderReport(r) {
      out.innerHTML = '';
      // LOUD, FIRST: demo underlying. The strikes are anchored to a FAKE price series, so they can
      // look absurd next to the real quote (a trader saw 210 strikes on AAPL-at-$315). Say so before
      // anything reads as a real result.
      if (r.demoUnderlying) {
        out.appendChild(alertBox('danger',
          'Demo price data — NOT the real market. ' + r.symbol + '’s price history here is built-in placeholder data, '
          + 'so the strikes and every result below are anchored to fake prices and do not reflect the real market. '
          + 'For a real backtest, add a free Polygon or Alpha Vantage key (see the Data screen) and re-run.'));
      }
      var modeKind = r.pricingMode === 'HISTORICAL_CHAIN' ? 'ok' : r.pricingMode === 'MODELED_FROM_UNDERLYING' ? 'warn' : 'danger';
      out.appendChild(alertBox(modeKind, 'Pricing: ' + prettyPricingMode(r.pricingMode) + ' — confidence ' + r.confidence + '. ' +
        (r.pricingMode !== 'HISTORICAL_CHAIN' ? 'These are modeled option prices, not observed ones.' : '')));
      out.appendChild(el('div', { class: 'grid grid-4' },
        stat('Sample size', String(r.sampleSize), 'Completed trades. Below ~20, treat every number as anecdote.'),
        stat('Win rate', fmtPct(r.winRate)),
        stat('Avg return on risk', fmtPct(r.avgReturnOnRisk), 'Average P/L divided by max loss per trade.'),
        stat('Max drawdown', fmtPct(r.maxDrawdownPct)),
        stat('Coverage', r.daysCovered + '/' + r.daysRequested + ' days'),
        stat('Start → end', UI.fmtMoneyCompact(r.startingCents) + ' → ' + UI.fmtMoneyCompact(r.endingCents)),
        r.assignments !== null && r.assignments !== undefined && r.sampleSize > 0
          ? stat('Assigned at expiry', r.assignments + '/' + r.sampleSize,
              'Expirations where the short strike finished in the money — shares change hands in the real world.') : null,
        r.worstTrade ? stat('Worst trade', pnlSpan(r.worstTrade.pnlCents)) : null));
      if (r.equityCurve && r.equityCurve.length > 1) {
        // Same chart language as research/history: summary chips up top, crosshair below.
        var eq = r.equityCurve.map(function (p) { return { date: p.date, value: p.equityCents }; });
        var eqStart = eq[0].value, eqEnd = eq[eq.length - 1].value;
        var eqHigh = eq.reduce(function (m, p) { return Math.max(m, p.value); }, -Infinity);
        var eqLow = eq.reduce(function (m, p) { return Math.min(m, p.value); }, Infinity);
        var eqPct = eqStart ? (eqEnd - eqStart) / Math.abs(eqStart) * 100 : 0;
        out.appendChild(el('div', { class: 'card' },
          UI.cardHeader('Equity curve'),
          el('div', { class: 'chip-row chart-summary' },
            el('span', { class: 'chip' }, el('b', {}, eq[0].date + ' → ' + eq[eq.length - 1].date)),
            el('span', { class: 'chip' }, 'Change ',
              el('b', { class: eqEnd - eqStart >= 0 ? 'gain' : 'loss' },
                (eqEnd - eqStart >= 0 ? '+' : '') + UI.fmtMoneyCompact(eqEnd - eqStart) + ' (' + (eqPct >= 0 ? '+' : '') + eqPct.toFixed(1) + '%)')),
            el('span', { class: 'chip' }, 'High ', el('b', {}, UI.fmtMoneyCompact(eqHigh)), ' · Low ', el('b', {}, UI.fmtMoneyCompact(eqLow))),
            el('span', { class: 'chip' }, 'Max drawdown ', el('b', { class: 'loss' }, fmtPct(r.maxDrawdownPct)))),
          UI.lineChart(eq, { money: true })));
      }
      if (r.trades && r.trades.length) {
        // "Underlying @ entry" gives the strikes context — 210-strike legs make sense next to
        // "underlying $212 that day", and it makes demo prices vs the real quote obvious.
        out.appendChild(el('div', { class: 'card' },
          UI.cardHeader('Trades'),
          table(['Entry', 'Underlying @ entry', 'Exit', 'Position', 'Premium', 'Exit value', 'Fees', 'P/L', 'Why closed'],
            r.trades.map(function (t) {
              return el('tr', {},
                el('td', { class: 'muted' }, t.entryDate),
                el('td', { class: 'muted' }, t.entryUnderlyingCents ? fmtMoney(t.entryUnderlyingCents) : '—'),
                el('td', { class: 'muted' }, t.exitDate),
                el('td', { class: 'mono' }, t.label),
                el('td', {}, fmtMoney(t.entryNetPremiumCents, { plus: true })),
                el('td', {}, fmtMoney(t.exitValueCents, { plus: true })),
                el('td', {}, fmtMoney(t.feesCents)),
                el('td', {}, pnlSpan(t.pnlCents)),
                el('td', { class: 'muted' }, t.exitReason));
            }))));
      }
      if (r.skipped && r.skipped.length) {
        out.appendChild(el('div', { class: 'card' },
          UI.cardHeader('Skipped entries'),
          r.skipped.slice(0, 20).map(function (s) {
            return el('div', { class: 'status-item' }, el('span', { class: 'muted' }, s.date), el('span', {}, s.reason));
          })));
      }
      var assume = el('div', { class: 'card' }, UI.cardHeader('Assumptions'));
      Object.keys(r.assumptions || {}).forEach(function (k) {
        assume.appendChild(el('div', { class: 'status-item' }, el('b', {}, k), el('span', { class: 'spacer' }), el('span', { class: 'muted' }, String(r.assumptions[k]))));
      });
      out.appendChild(assume);
      (r.notes || []).forEach(function (n) { out.appendChild(alertBox('warn', n)); });
      out.appendChild(el('p', { class: 'muted' }, r.disclaimer));
    }

    // D4: the Expert portfolio engine's report (concurrent positions + mechanical exits).
    function renderPortfolioReport(r) {
      out.innerHTML = '';
      if (r.demoUnderlying) {
        out.appendChild(alertBox('danger', 'Demo price data — NOT the real market. ' + r.symbol
          + '’s price history here is placeholder data, so every result below is anchored to fake '
          + 'prices. Add a Polygon or Alpha Vantage key for a real backtest.'));
      }
      var modeKind = r.pricingMode === 'OBSERVED_FROM_HISTORY' ? 'ok' : r.pricingMode === 'MODELED_FROM_UNDERLYING' ? 'warn' : 'danger';
      out.appendChild(alertBox(modeKind, 'Portfolio engine · pricing mode ' + r.pricingMode + ' — confidence '
        + r.confidence + '. Concurrent positions with mechanical profit-target / stop / time exits.'));
      out.appendChild(el('div', { class: 'grid grid-4' },
        stat('Sample size', String(r.sampleSize), 'Completed trades.'),
        stat('Win rate', fmtPct(r.winRate)),
        stat('Avg return on risk', fmtPct(r.avgReturnOnRisk)),
        stat('Max drawdown', fmtPct(r.maxDrawdownPct)),
        stat('Concurrent peak', String(r.concurrentPeak), 'Most positions open at once.'),
        stat('Start → end', UI.fmtMoneyCompact(r.startingCents) + ' → ' + UI.fmtMoneyCompact(r.endingCents))));
      if (r.equityCurve && r.equityCurve.length > 1) {
        var eq = r.equityCurve.map(function (p) { return { date: p.date, value: p.equityCents }; });
        out.appendChild(el('div', { class: 'card' }, UI.cardHeader('Equity curve'), UI.lineChart(eq, { money: true })));
      }
      if (r.trades && r.trades.length) {
        out.appendChild(el('div', { class: 'card' },
          UI.cardHeader('Trades (' + r.trades.length + ')'),
          table(['Entry', 'Exit', 'Strategy', 'Credit/Debit', 'P/L', 'Theor. max loss', 'Why closed'],
            r.trades.map(function (t) {
              return el('tr', {},
                el('td', { class: 'muted' }, t.entryDate), el('td', { class: 'muted' }, t.exitDate),
                el('td', {}, prettyStrategy(t.strategy)),
                el('td', {}, fmtMoney(t.creditCents, { plus: true })),
                el('td', {}, pnlSpan(t.pnlCents)),
                el('td', { class: 'loss' }, fmtMoney(t.maxLossCents)),
                el('td', { class: 'muted' }, t.exitReason));
            }))));
      }
      (r.notes || []).forEach(function (n) { out.appendChild(alertBox('warn', n)); });
      out.appendChild(el('p', { class: 'muted' }, r.disclaimer));
    }
  }

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
  async function status(root, params) {
    var level = Learn.currentLevel();
    var token = App.navToken;
    // ROUTE-BACKED SUBNAVIGATION (holistic review addendum A): Data is five workspaces, not one
    // scroll. #/data aliases to Overview; back/forward, bookmarks and workspace restore keep the tab.
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
    var dataTabs = el('div', { class: 'tabs data-tabs', id: 'data-tabs', role: 'tablist' },
      TABS.map(function (t) {
        return el('button', {
          class: t.key === tab ? 'active' : '', 'data-tab': t.key, role: 'tab',
          'aria-selected': t.key === tab ? 'true' : 'false',
          // Administration starts HIDDEN and appears only once authorization confirms — the
          // old version rendered it and hid it later (a flash), and stayed visible on error.
          style: t.key === 'admin' ? 'display:none' : null,
          onclick: function () { App.navigate('#/data/' + t.key); }
        }, t.label, el('span', { class: 'badge badge-ok', id: 'data-tab-badge-' + t.key,
          style: 'display:none; margin-left:6px' }));
      }));
    var dataTabsWrap = el('div', { class: 'data-tabs-wrap' }, dataTabs);
    function syncDataTabsEdge() {
      dataTabsWrap.classList.toggle('at-end', dataTabs.scrollLeft + dataTabs.clientWidth >= dataTabs.scrollWidth - 2);
    }
    dataTabs.addEventListener('scroll', syncDataTabsEdge, { passive: true });
    root.appendChild(dataTabsWrap);
    requestAnimationFrame(syncDataTabsEdge);
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
        var ov = await API.get('/api/data/overview');
        var btn = document.querySelector('#data-tabs [data-tab="admin"]');
        if (ov && ov.admin) { if (btn) btn.style.display = ''; }
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
    var simCard = el('div', { class: 'card', id: 'dc-sim-market' },
      UI.cardHeader('Simulated market'),
      explain(beginnerDC()
        ? 'A practice market that MOVES: generated prices and option chains stream like the real thing, on any day, at any speed. Everything is loudly labeled \u2014 nothing here is real, and a separate simulation account keeps your practice account untouched.'
        : 'Deterministic per-session worlds: a beta factor model on fixed 30-sim-second quanta (speed never changes the path), a full generated option book, replayable from seed + event log. A separate simulation account is the only lane that trades a world.'));
    simCard.appendChild(el('div', { class: 'demo-market-choice' },
      el('div', {}, el('b', {}, 'Built-in demo market'),
        el('p', { class: 'muted small' }, 'A fixed, fabricated market for learning the interface. It is isolated from observed data and uses its own account.')),
      el('span', { class: 'spacer' }),
      App.state.world === 'demo'
        ? el('span', { class: 'badge badge-warn' }, 'ACTIVE')
        : el('button', { class: 'btn btn-sm btn-secondary', id: 'enter-demo-market',
          onclick: function () { App.switchWorld('demo'); } }, 'Enter demo market')));
    function beginnerDC() { return window.Learn && Learn.currentLevel() === 'beginner'; }
    if (tab === 'simulation') root.appendChild(simCard);
    (async function () {
      if (!simCard.isConnected) return; // inactive tab: none of this loads (addendum B)
      var activeUniverse = (App.state.universe && App.state.universe.active) || {};
      var activeSymbols = (activeUniverse.symbols || []).slice();
      var wt = (App.context.symbol() || activeSymbols[0] || 'SPY').toUpperCase();
      var curSector = activeUniverse.sectorKey || '';
      var st = { scenario: 'CHOP', speed: 26, sectorKey: curSector !== 'world' ? curSector : '',
        includeActiveUniverse: curSector === 'world', symbolsText: wt, allowFictional: false,
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
        if (current) box.appendChild(controlRoom(current));
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
            }));
        }
      }

      /** The RUNNING world's console: clock, live symbols, P/L, and every action in one place. */
      function controlRoom(sx) {
        var cfg = sx.config || {};
        var syms = Object.keys(cfg.symbolBetas || {});
        var focusSym = syms[0];
        var chartHost = null;
        var marketSelector = UI.symbolContext({
          mode: 'multi', id: 'cr-symbols', symbols: syms.slice(0, 12), symbol: focusSym,
          label: 'Session symbols', hint: 'Select one to focus the chart',
          onPick: function (sym) { focusSym = sym; if (chartHost) drawFocus(); }
        });
        marketSelector.querySelectorAll('[data-symbol]').forEach(function (btn) {
          var s0 = btn.getAttribute('data-symbol');
          btn.appendChild(el('b', { 'data-cr-sym': s0 }, '\u2026'));
        });
        if (syms.length > 12) marketSelector.querySelector('.symbol-context-symbols').appendChild(
          el('span', { class: 'muted small' }, '+' + (syms.length - 12) + ' more'));
        var room = el('div', { class: 'card-slim', id: 'sim-control-room', 'data-sim-id': sx.id,
          style: 'margin:6px 0; padding:12px' },
          UI.cardHeader('Control room \u2014 ' + (sx.name || sx.id),
            el('span', { class: 'badge ' + (sx.running ? 'badge-ok' : 'badge-warn'), id: 'cr-status' },
              sx.running ? 'RUNNING' : 'PAUSED')),
          el('div', { class: 'chip-row' },
            chip('Sim clock', el('span', { id: 'cr-clock' }, String(sx.simTime || '').replace('T', ' '))),
            chip('Scenario', App.scenarioLabel(cfg.scenario)),
            chip('Speed', el('span', { id: 'cr-speed' }, (sx.speed || cfg.speed || 1) + '\u00d7')),
            chip('Seed', String(cfg.seed)),
            sx.modelVersion ? chip('Model', sx.modelVersion) : null),
          marketSelector,
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
            el('button', { class: 'btn btn-sm', onclick: function () { injectModal(sx); } }, 'Inject event'),
            el('button', { class: 'btn btn-sm', onclick: function () { showReport(sx); } }, 'Report'),
            el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/trade/discover'); } }, 'Find strategies'),
            el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/portfolio'); } }, 'Simulated portfolio'),
            el('button', { class: 'btn btn-sm btn-danger', onclick: function () { finishModal(sx); } }, 'Finish'),
            el('button', { class: 'btn btn-sm btn-secondary', id: 'cr-exit', onclick: function () {
              App.switchWorld(App.baseWorldId()); } }, App.config && App.config.fixturesOnly ? 'Return to demo market' : 'Return to observed market')));
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
          chartHost.appendChild(el('div', { class: 'muted small', style: 'margin-bottom:2px' },
            focusSym + ' \u2014 tap another symbol chip to switch \u00b7 ',
            el('a', { href: '#/research/' + focusSym, onclick: function () { App.navigate('#/research/' + focusSym); } },
              'Full research \u2192')));
          chartHost.appendChild(UI.rangeChart({ initial: '3m', fetch: historyFetch(focusSym) }));
        }
        drawFocus();
        function paint() {
          var up = 0, down = 0;
          syms.forEach(function (sym) {
            var q = App.Market && App.Market.get(sym);
            if (!q) return;
            var last = parseFloat(q.last), prev = parseFloat(q.prevClose);
            if (isFinite(last) && isFinite(prev) && prev) (last >= prev ? up++ : down++);
            var slot = room.querySelector('[data-cr-sym="' + sym + '"]');
            if (slot) slot.textContent = fmtNum(q.last);
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
          } catch (e) { var pl2 = room.querySelector('#cr-pl'); if (pl2) pl2.innerHTML = ''; }
        }
        fillPl();
        if (App.Market) App.Market.subscribe(function () {
          if (!room.isConnected) return;
          paint();
          var nowT = Date.now();
          if (nowT - plLast > 8000) { plLast = nowT; fillPl(); }
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
        room.appendChild(UI.expandable('Event timeline \u2014 every injected shock, replayable', function () {
          var slot = el('div', {}, UI.spinner('Loading events\u2026'));
          API.getFresh('/api/sim/market/' + sx.id + '/report').then(function (rep) {
            slot.innerHTML = '';
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
        var active = App.state.world === sx.id;
        var row = el('div', { class: 'card-slim btn-row sim-session-row clickable', 'data-sim-id': sx.id, style: 'margin:6px 0' },
          el('b', {}, sx.name || sx.id),
          el('span', { class: 'badge ' + (sx.running ? 'badge-ok' : finished ? 'badge-dim' : 'badge-warn') },
            finished ? 'FINISHED' : sx.running ? 'RUNNING' : 'PAUSED'),
          el('span', { class: 'muted small' }, App.scenarioLabel(cfg.scenario)
            + ' \u00b7 seed ' + cfg.seed
            + (sx.eventCount ? ' \u00b7 ' + sx.eventCount + ' injected event' + (sx.eventCount === 1 ? '' : 's') : '')
            + (sx.simTime ? ' \u00b7 ' + String(sx.simTime).replace('T', ' ') : '')));
        if (!finished) {
          row.appendChild(el('button', { class: 'btn btn-sm', onclick: function () {
            App.switchWorld(active ? App.baseWorldId() : sx.id); } }, active
              ? (App.config && App.config.fixturesOnly ? 'Back to demo' : 'Back to real')
              : 'Enter this market'));
          row.appendChild(el('button', { class: 'btn btn-sm', onclick: async function () {
            try {
              var running = !sx.running;
              await API.post('/api/sim/market/' + sx.id + '/' + (sx.running ? 'pause' : 'start'), {});
              App.emitEvent('world.control', { world: sx.id, running: running });
              App.refreshWorldBand();
            }
            catch (e) { UI.toast(e.message || 'Could not change simulation playback', 'error'); } } }, sx.running ? 'Pause' : 'Start'));
          row.appendChild(el('button', { class: 'btn btn-sm', id: 'sim-inject-' + sx.id, onclick: function () {
            injectModal(sx); } }, 'Inject event'));
        }
        row.appendChild(el('button', { class: 'btn btn-sm', onclick: function () { showReport(sx); } }, 'Report'));
        if (!finished) {
          row.appendChild(el('button', { class: 'btn btn-sm btn-danger', onclick: function () {
            finishModal(sx); } }, 'Finish'));
        }
        row.addEventListener('click', function (e) {
          if (e.target.closest('button,a,input,select,textarea')) return;
          if (finished) showReport(sx);
          else App.switchWorld(sx.id);
        });
        return pressable(row, finished ? 'Open report for ' + (sx.name || sx.id)
          : 'Enter simulated market ' + (sx.name || sx.id), 'link');
      }

      /** Inject-event: an app modal with a SYMBOL PICKER — never window.prompt (review P2). */
      function injectModal(sx) {
        var syms = Object.keys((sx.config || {}).symbolBetas || {});
        var symSel = el('select', { id: 'inject-symbol' }, syms.map(function (x) { return el('option', { value: x }, x); }));
        var moveIn = el('input', { type: 'number', id: 'inject-move', value: '-5', min: '-50', max: '50', step: '0.5' });
        var volIn = el('input', { type: 'number', id: 'inject-vol', value: '0', min: '-50', max: '100', step: '1' });
        var body = el('div', { class: 'form-grid' },
          el('div', { class: 'field' }, el('label', { class: 'field-label' }, 'Symbol'), symSel),
          el('div', { class: 'field' }, el('label', { class: 'field-label' }, 'Price shock %'), moveIn),
          el('div', { class: 'field' }, el('label', { class: 'field-label' }, 'Volatility shift (IV points)'), volIn),
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
          await API.del('/api/sim/market/' + sx.id);
          // Finishing the ACTIVE world is a full market transition — never a bare state
          // assignment (review P0 #2: the old path skipped cache/store/universe/render and
          // left "Dom sim (simulated)" all over the observed screens).
          if (wasActive) await App.transitionWorld(App.baseWorldId());
          refreshSim(); App.refreshWorldBand();
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
            el('span', { class: 'chip' }, 'Trades: ' + (rep.trades || []).length),
            el('span', { class: 'chip' }, 'Resolved: ' + rep.resolved),
            el('span', { class: 'chip' }, 'Win rate: ' + (rep.winRate == null ? '\u2014' : rep.winRate + '%')),
            el('span', { class: 'chip' }, 'Realized: ' + UI.fmtMoney(rep.realizedPnlCents)),
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
                  el('td', {}, t.realizedPnlCents == null
                    ? el('span', { class: 'badge badge-dim' }, t.status)
                    : pnlSpan(t.realizedPnlCents)),
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
          var card = el('button', { class: 'choice sim-scenario' + (st.scenario === sc.key ? ' active' : ''),
            type: 'button', 'data-scenario': sc.key, onclick: function () {
              st.scenario = sc.key; renderCreator();
            } },
            el('b', {}, App.scenarioLabel(sc.key)),
            el('div', { class: 'muted small' }, sc.blurb));
          wrap.appendChild(card);
        });
        return wrap;
      }

      function labeled(text, node, infoKey) {
        var lbl = el('label', { class: 'field-label' }, text);
        if (infoKey) lbl.appendChild(UI.info(infoKey));
        return el('div', { class: 'field' }, lbl, node);
      }

      function renderCreator() {
        creator.innerHTML = '';
        var beginner = beginnerDC();
        creator.appendChild(el('div', { class: 'muted small', style: 'margin-bottom:6px' },
          'Create a session'));
        // Scenario: story cards at both levels (the story IS the configuration).
        var scLbl = el('label', { class: 'field-label' }, 'What kind of market?');
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
          grid.appendChild(labeled(curSector === 'world' ? 'Bring my current market along' : 'Bring my current sector along',
            el('div', { class: 'btn-row', style: 'align-items:center' }, sectorChk,
              el('span', { class: 'muted small' },
                (curSector === 'world' ? (activeUniverse.label || 'Current market') + ' \u00b7 ' + activeSymbols.length + ' symbols'
                  : (st.sectorKey || 'CORE')) + ' \u2014 so the tape and explorer stay alive'))));
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
          var speedIn = el('input', { type: 'number', id: 'sim-speed', value: String(st.speed), min: '1', max: '2000',
            title: 'Sim-seconds per real second. 1 = real time; 26 \u2248 one 6.5h session in 15 min; 390 \u2248 one session per minute.' });
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
            await API.post('/api/sim/market/' + res.worldId + '/start', {});
            await App.switchWorld(res.worldId);
            refreshSim();
          } catch (e) { UI.toast(e.message || 'Could not create the simulated session', 'error'); }
          createBtn.disabled = false;
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
            actions.push(el('button', { class: 'btn btn-secondary', onclick: function () { App.switchWorld('observed'); } }, 'Return to observed market'));
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
            actions.push(el('button', { class: 'btn', onclick: function () { App.navigate('#/trade/discover'); } }, 'Find an idea'));
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
              App.navigate('#/research/' + d.symbol);
            } }, 'Open Research') : null,
          isActive && d.id !== 'observed' && d.symbol ? el('button', { class: 'btn btn-sm',
            title: 'Compare strategies while this scenario remains active', onclick: function () {
              App.context.selectSymbol(d.symbol);
              App.navigate('#/trade/verify');
            } }, 'Compare strategies') : null,
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
        var f = Scenario.form(level, null);
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
                    App.navigate('#/research/' + (sym.value || '').toUpperCase());
                  } catch (e2) { UI.toast(e2.message || 'Could not activate this scenario', 'error'); }
                } }, 'Use it — explore in Research'),
                el('button', { class: 'btn btn-sm', onclick: async function () {
                  try {
                    await API.put('/api/datasets/active', { id: r.datasetId });
                    App.refreshScenarioBanner && App.refreshScenarioBanner();
                    App.context.selectSymbol(sym.value);
                    App.navigate('#/trade/verify');
                  } catch (e2) { UI.toast(e2.message || 'Could not activate this scenario', 'error'); }
                } }, 'Compare strategies under it'),
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
    async function loadJobs() {
      if (!jobsCard.isConnected) return;
      if (jobsTimer) { clearTimeout(jobsTimer); jobsTimer = null; } // one live poll chain, never stacked
      var data;
      try { data = await API.getFresh('/api/data/jobs'); } catch (e) { return; }
      if (!App.alive(token)) { if (jobsTimer) clearTimeout(jobsTimer); return; }
      var jobs = data.jobs || [];
      jobsCard.innerHTML = '';
      jobsCard.appendChild(UI.cardHeader('Jobs', el('button', { class: 'btn btn-sm btn-secondary', onclick: loadJobs }, 'Refresh')));
      if (level === 'beginner') jobsCard.appendChild(explain('Background tasks that fetch or refresh data. Progress and results show here.'));
      if (!jobs.length) { jobsCard.appendChild(UI.emptyState('No jobs yet', 'Warm the engine or backfill history to create one.')); return; }
      var anyRunning = false;
      function jobName(kind) {
        return ({ sync_underlying: 'Update daily history', backfill_underlying: 'Legacy history update',
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
            (j.status === 'RUNNING' || j.status === 'QUEUED')
              ? el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { API.post('/api/data/jobs/' + j.id + '/cancel', {}).then(loadJobs); } }, 'Cancel')
              : (j.status === 'FAILED' || j.status === 'CANCELLED')
                ? el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { API.post('/api/data/jobs/' + j.id + '/retry', {}).then(loadJobs); } }, 'Retry')
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
      var pollMs = App._eventsES ? 10000 : 2000;
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

    function startJob(kind, params) {
      return API.post('/api/data/jobs', { kind: kind, params: params || {} }).then(function () { loadJobs(); });
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
      engineCard.innerHTML = '';
      engineCard.appendChild(UI.cardHeader(el('span', {}, 'Market engine', UI.info('marketengine')),
        el('button', { class: 'btn btn-sm', id: 'dc-refresh-now', onclick: function () { startJob('refresh_now', {}); } }, 'Refresh now')));
      engineCard.appendChild(el('div', { class: 'chip-row' },
        ov.marketLane === 'DEMO' ? chip('Feed', 'Static Demo') : chip('Market', ov.marketOpen ? 'Open' : 'Closed'),
        chip('Warmed', (e.warmed || 0) + ' / ' + (e.tracked || 0)),
        chip('State', e.errors ? 'Needs attention' : e.inFlight ? e.inFlight + ' refreshing'
          : e.stale ? e.stale + ' stale' : 'Ready')));
      syncOverviewToggle(engineCard, 'engine');
      dcDetailBuilders.engine = function () {
        var detail = el('div', {},
          ov.marketLane === 'DEMO' ? alertBox('warn',
            'The explicit Demo market uses fabricated teaching prices and an isolated account.') : null,
          el('div', { class: 'chip-row' },
            chip('Refreshing', String(e.inFlight || 0)),
            chip('Stale', String(e.stale || 0)),
            ov.marketLane === 'DEMO' ? null : chip('Refresh every', (e.refreshInterval || 0) + 's'),
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
      var rows = syms.slice(0, level === 'beginner' ? 12 : syms.length).map(function (s) {
        return el('tr', {},
          el('td', {}, el('b', {}, s.symbol)),
          el('td', { class: 'muted' }, s.underlyingBars ? (s.underlyingFrom + ' → ' + s.underlyingTo) : '—'),
          el('td', {}, s.underlyingBars
            ? el('span', { class: 'badge ' + (s.underlyingObserved ? 'badge-ok' : 'badge-dim') }, s.underlyingObserved ? 'observed' : 'demo')
            : el('span', { class: 'muted' }, '—')),
          el('td', { class: 'muted' }, String(s.underlyingBars || 0)),
          level === 'expert' ? el('td', { class: 'muted small' }, (s.underlyingBasis || '—') + (s.underlyingSources ? ' · ' + s.underlyingSources : '')) : null,
          el('td', { class: 'muted' }, s.optionRows ? (s.optionDays + ' days / ' + s.optionRows + ' rows') : '—'));
      });
      coverageCard.appendChild(table(level === 'expert'
        ? ['Symbol', 'Underlying range', 'Evidence', 'Bars', 'Basis & sources', 'Options']
        : ['Symbol', 'Underlying range', 'Evidence', 'Bars', 'Options'], rows));
    })();

    // --- Daily-history acquisition: one guided workflow over every lawful connector. ---
    (async function fillHistorySync() {
      if (!syncCard.isConnected) return;
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
        from: '', to: doc.latestCompletedSession || '', basis: 'AUTO'
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
        if (planSlot) {
          planSlot.innerHTML = '';
          planSlot.appendChild(el('p', { class: 'muted small' }, 'Inputs changed. Preview the updated request before starting.'));
        }
      }
      syncCard.innerHTML = '';
      syncCard.appendChild(UI.cardHeader('Get & maintain daily price history',
        el('span', { class: 'badge badge-ok' }, 'ADDITIVE · RESUMABLE')));
      syncCard.appendChild(explain(level === 'beginner'
        ? 'Choose stocks and a permitted source. StrikeBench checks what dates are already stored, shows the request cost, and fetches only missing daily history. Nothing generated can enter Observed data.'
        : 'Missing-range planning is per symbol over observed trading sessions, with a short revision overlap. Source identity, adjusted/raw basis, request allowance, cursor, and rejected-row quarantine survive restarts.'));

      var sourceGrid = el('div', { class: 'data-source-picker', role: 'group', 'aria-label': 'Automated daily price source' });
      automated.forEach(function (c) {
        var chosen = c.key === st.source;
        sourceGrid.appendChild(el('button', { type: 'button', class: 'data-source-choice' + (chosen ? ' active' : ''),
          disabled: !c.eligible, 'aria-pressed': chosen ? 'true' : 'false', 'data-source-key': c.key,
          onclick: function () { st.source = c.key; App.state.dataSyncForm = st; fillHistorySync(); } },
          el('span', { class: 'chip-row' },
            el('b', {}, c.name),
            el('span', { class: 'badge ' + (c.eligible ? 'badge-ok' : 'badge-dim') }, c.eligible ? 'READY' : 'SETUP NEEDED')),
          el('span', { class: 'muted small' }, c.history),
          c.dailyLimit > 0 ? el('span', { class: 'small' }, c.remainingToday + ' of ' + c.dailyLimit + ' requests left today')
            : el('span', { class: 'small' }, 'Usage follows your provider plan')));
      });
      if (!automated.length) sourceGrid.appendChild(el('p', { class: 'muted' }, 'No automated candle connectors are present in this build.'));
      syncCard.appendChild(el('section', { class: 'data-acquire-section' },
        el('h3', {}, '1. Choose an automated source'), sourceGrid));
      if (!eligible.length) syncCard.appendChild(alertBox('caution',
        'No automated daily-price source is eligible yet. Use your own CSV below now, or connect an official keyed source. Yahoo automation remains blocked unless you have permission.'));

      var sectorBtn = el('button', { type: 'button', class: st.scope === 'sector' ? 'active' : '',
        onclick: function () { st.scope = 'sector'; st.symbols = defaultSymbols; symbolsInput.value = st.symbols; syncScope(); invalidatePlan(); } },
        'Current sector · ' + ((active.symbols || []).length || 0));
      var customBtn = el('button', { type: 'button', class: st.scope === 'custom' ? 'active' : '',
        onclick: function () { st.scope = 'custom'; syncScope(); invalidatePlan(); symbolsInput.focus(); } }, 'Choose symbols');
      var symbolsInput = el('input', { type: 'text', id: 'data-sync-symbols', value: st.symbols,
        placeholder: 'AAPL, MSFT, SPY', list: 'universe-symbols', oninput: function () { st.symbols = this.value; st.scope = 'custom'; syncScope(); invalidatePlan(); } });
      function syncScope() {
        sectorBtn.classList.toggle('active', st.scope === 'sector');
        customBtn.classList.toggle('active', st.scope === 'custom');
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
        el('div', { class: 'field data-symbol-scope' }, el('label', {}, 'Stocks'),
          el('div', { class: 'level-switch data-scope-switch' }, sectorBtn, customBtn), symbolsInput),
        el('div', { class: 'field' }, el('label', {}, 'History window'), years),
        level === 'expert' ? el('div', { class: 'field' }, el('label', {}, 'Exact start (optional)'), fromInput) : null,
        level === 'expert' ? el('div', { class: 'field' }, el('label', {}, 'Through completed session'), toInput) : null);
      syncCard.appendChild(el('section', { class: 'data-acquire-section' }, el('h3', {}, '2. Choose coverage'), scopeGrid));
      if (level === 'beginner') syncCard.appendChild(UI.expandable('Choose exact dates (optional)', function () {
        return el('div', { class: 'form-grid grid-2' },
          el('div', { class: 'field' }, el('label', {}, 'Start date'), fromInput),
          el('div', { class: 'field' }, el('label', {}, 'Through completed session'), toInput));
      }));

      var planSlot = el('div', { id: 'data-sync-plan', 'aria-live': 'polite' });
      var startBtn = el('button', { class: 'btn', id: 'data-sync-start', disabled: true, onclick: async function () {
        if (!planDoc) return;
        startBtn.disabled = true;
        try {
          await startJob('sync_underlying', { source: planDoc.source.key,
            symbols: (planDoc.plans || []).map(function (p) { return p.symbol; }),
            from: String(planDoc.effectiveFrom), to: String(planDoc.to) });
          planSlot.innerHTML = '';
          planSlot.appendChild(alertBox('ok', 'History update started. Progress is shown in Jobs below; each completed symbol becomes available to Research immediately.'));
        } catch (e) { planSlot.appendChild(alertBox('warn', e.message)); startBtn.disabled = false; }
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
            if (planDoc.estimatedRequests > remaining) planSlot.appendChild(alertBox('warn',
              'This plan needs ' + planDoc.estimatedRequests + ' requests but only ' + remaining + ' remain today. Narrow the scope or resume after the allowance resets.'));
          } catch (e) { planDoc = null; planSlot.innerHTML = ''; planSlot.appendChild(alertBox('warn', e.message)); }
          previewBtn.disabled = !st.source;
        } }, 'Preview update');
      syncCard.appendChild(el('section', { class: 'data-acquire-section' },
        el('h3', {}, '3. Preview, then run'), el('div', { class: 'btn-row' }, previewBtn, startBtn), planSlot));

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
            el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research/' + (csvSymbol.value || App.context.symbol('SPY')).toUpperCase()); } }, 'Open in Research'));
          loadJobs();
        } catch (e) { csvResult.innerHTML = ''; csvResult.appendChild(alertBox('warn', e.message)); }
        uploadBtn.disabled = false;
      } }, 'Validate & import');
      var importBody = el('div', { class: 'data-import-grid' },
        el('div', { class: 'field' }, el('label', {}, 'CSV file'), fileIn),
        el('div', { class: 'field' }, el('label', {}, 'Symbol fallback'), csvSymbol),
        el('div', { class: 'field' }, el('label', {}, 'Price basis', UI.info('adjustedprices')), csvBasis),
        el('div', { class: 'field' }, el('label', {}, 'Source label'), csvLabel), uploadBtn, csvResult);
      syncCard.appendChild(UI.expandable('Import a CSV you already own', function () { return importBody; },
        { open: !eligible.length }));

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
              source: st.source, symbols: parseSymbolText(st.symbols), years: Number(st.years) || 5, adjustment: st.basis || 'AUTO' });
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
      sourcesCard.appendChild(UI.cardHeader('Data sources'));
      if (level === 'beginner') sourcesCard.appendChild(explain('Every source says what it supplies, whether it is ready, and what permission or plan governs it. No source is called merely because it is free or keyless.'));
      sourcesCard.appendChild(el('h3', {}, 'Daily price-history connectors'));
      var grid = el('div', { class: 'grid grid-2' });
      (data.connectors || []).forEach(function (s) {
        grid.appendChild(el('div', { class: 'dc-source' + (s.eligible ? ' on' : '') },
          el('div', { class: 'chip-row', style: 'align-items:center;margin:0' },
            el('span', { class: 'badge ' + (s.eligible ? 'badge-ok' : 'badge-dim') }, s.eligible ? 'READY' : (s.configured ? 'BLOCKED' : 'SETUP')),
            el('b', {}, s.name),
            el('span', { class: 'spacer' }),
            el('span', { class: 'muted small' }, s.covers)),
          el('div', { class: 'muted small', style: 'margin-top:4px' }, s.access + ' · ' + s.cadence),
          el('div', { class: 'small', style: 'margin-top:4px' }, s.rights),
          el('div', { class: 'muted small', style: 'margin-top:4px' }, s.adjustment + ' · ' + s.history),
          s.dailyLimit > 0 ? el('div', { class: 'small', style: 'margin-top:4px' },
            s.remainingToday + '/' + s.dailyLimit + ' requests remain today ', UI.info('requestbudget')) : null,
          (!s.eligible || level === 'expert') ? el('div', { class: 'small', style: 'margin-top:5px' }, s.setup) : null,
          level === 'expert' ? el('div', { class: 'muted small', style: 'margin-top:4px' }, s.note) : null));
      });
      sourcesCard.appendChild(grid);
      sourcesCard.appendChild(el('h3', { style: 'margin-top:14px' }, 'Live market, filings, rates & option history'));
      var support = el('div', { class: 'grid grid-2' });
      (data.sources || []).filter(function (s) {
        return !/^(Yahoo|Alpha Vantage|Polygon|Stooq|Historical options CSV)/.test(s.name);
      }).forEach(function (s) {
        support.appendChild(el('div', { class: 'dc-source' + (s.enabled ? ' on' : '') },
          el('div', { class: 'chip-row', style: 'align-items:center;margin:0' },
            el('span', { class: 'badge ' + (s.enabled ? 'badge-ok' : 'badge-dim') }, s.enabled ? 'ON' : 'off'),
            el('b', {}, s.name), el('span', { class: 'spacer' }), el('span', { class: 'muted small' }, s.covers)),
          el('div', { class: 'muted small', style: 'margin-top:4px' }, s.license),
          el('div', { class: 'small', style: 'margin-top:4px' }, s.hint)));
      });
      sourcesCard.appendChild(support);
    })();

    // --- Provider health (the old status view, kept as detail) ---
    (async function fillHealth() {
      if (!healthCard.isConnected) return;
      var s;
      try { s = await API.get('/api/status'); } catch (e) { return; }
      if (!App.alive(token)) return;
      healthCard.innerHTML = '';
      var body = el('div', {});
      var grid = el('div', { class: 'grid grid-2' });
      Object.keys(s.domains || {}).forEach(function (domain) {
        var items = s.domains[domain];
        var card = el('div', { class: 'card', style: 'margin-bottom:0' }, UI.cardHeader(domain));
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
          el('span', { class: 'chip-label' }, domain),
          el('b', {}, el('span', { class: 'badge ' + worst }, items.length ? worstState : 'NONE'))));
      });
      healthCard.appendChild(sumRow);
      healthCard.appendChild(el('p', { class: 'muted small data-health-basis' },
        'Health is the latest source request. Stored historical coverage is separate and may remain available after the market closes or a source is temporarily empty.'));
      syncOverviewToggle(healthCard, 'health');
      dcDetailBuilders.health = function () {
        var detail = el('div', {}, body);
        // Observability for the operator (review P2 #9): p50/p95 by route class, live.
        if (Learn.currentLevel() === 'expert') {
          detail.appendChild(UI.expandable('API latency (p50/p95 by route class)', function () {
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
        }
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
        { key: 'MARKET_DATA', label: 'Market history only', blurb: 'Clears stored price/option history + data jobs. Keeps your paper account and research.' },
        { key: 'RESEARCH', label: 'Research & backtests', blurb: 'Clears saved evaluations, recommendations, backtests, and notes.' },
        { key: 'PAPER', label: 'Paper account & trades', blurb: 'Voids trades and positions and re-funds a fresh account.' },
        { key: 'EVERYTHING', label: 'Everything (fresh start)', blurb: 'Wipes ALL stored data and re-seeds a brand-new funded account.' }
      ];
      var choices = level === 'beginner' ? TIERS.filter(function (t) { return t.key === 'PAPER' || t.key === 'EVERYTHING'; }) : TIERS;
      var sel = el('select', { id: 'dc-reset-tier' }, choices.map(function (t) { return el('option', { value: t.key }, t.label); }));
      var blurb = el('div', { class: 'muted small', id: 'dc-reset-blurb', style: 'margin-top:4px' });
      function syncBlurb() { var t = choices.find(function (x) { return x.key === sel.value; }); blurb.textContent = t ? t.blurb : ''; }
      sel.addEventListener('change', syncBlurb); syncBlurb();
      resetCard.appendChild(el('div', { class: 'btn-row' },
        el('label', { class: 'muted' }, 'What to clear '), sel,
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

  // ---------- 1. Account ----------

  // ---- Decision: recommendations-as-a-competition (Phase 3) ----

  var EVIDENCE_BADGE = {
    OBSERVED_LIVE: ['badge-ok', 'Observed · live'],
    OBSERVED_DELAYED: ['badge-ok', 'Observed · delayed'],
    OBSERVED_EOD: ['badge-caution', 'Observed · EOD'],
    MODELED: ['badge-caution', 'Modeled'],
    DEMO_FIXTURE: ['badge-dim', 'Demo data'],
    UNKNOWN: ['badge-danger', 'Unknown']
  };
  function evidenceBadge(level) {
    var m = EVIDENCE_BADGE[level] || EVIDENCE_BADGE.UNKNOWN;
    return el('span', { class: 'badge ' + m[0], title: 'The least-certain data dimension sets this badge' }, m[1]);
  }
  function cap1(s) { return s ? s.charAt(0).toUpperCase() + s.slice(1) : s; }

  function scenarioStrip(risk, symbol) {
    var scen = (risk && risk.scenarios) || [];
    if (!scen.length) return null;
    return el('div', { class: 'scenario-strip' }, scen.map(function (s) {
      var move = Math.round(s.underlyingMovePct * 100);
      return el('div', { class: 'scenario-cell' },
        el('div', { class: 'sc-move' }, (move > 0 ? '+' : '') + move + '%'),
        el('div', { class: 'sc-pnl ' + (s.pnlCents >= 0 ? 'gain' : 'loss') }, UI.fmtMoneyCompact(s.pnlCents)));
    }));
  }

  function evidenceGrid(ev) {
    var dims = ev.perDimension || {};
    var rows = Object.keys(dims).map(function (k) {
      return el('div', { class: 'evidence-row' }, el('span', { class: 'ev-dim' }, k), evidenceBadge(dims[k]));
    });
    if (ev.note) rows.push(el('p', { class: 'muted small' }, ev.note));
    return el('div', { class: 'evidence-grid' }, rows);
  }

  function scoreGrid(sc) {
    var wrap = el('div', {});
    if (!sc.gatePassed && sc.gateFailures && sc.gateFailures.length) {
      wrap.appendChild(alertBox('warn', 'Failed a hard check', sc.gateFailures));
    }
    wrap.appendChild(UI.table(['Factor', 'Score', 'Weight'], (sc.components || []).map(function (co) {
      return el('tr', {}, el('td', {}, co.name), el('td', {}, Math.round(co.value * 100) + '%'),
        el('td', {}, Math.round(co.weight * 100) + '%'));
    })));
    wrap.appendChild(el('p', { class: 'muted small' },
      'Normalized ' + Math.round(sc.normalizedScore) + ' → risk-adjusted ' + Math.round(sc.riskAdjustedScore)
      + ' (haircut for evidence + tail risk).'));
    return wrap;
  }

  function planList(mgmt) {
    var wrap = el('div', {});
    if (mgmt && mgmt.summary) wrap.appendChild(el('p', { class: 'plan-summary' }, mgmt.summary));
    wrap.appendChild(el('ul', { class: 'plan-rules' }, ((mgmt && mgmt.rules) || []).map(function (r) {
      return el('li', {}, el('b', {}, cap1(r.kind) + ': '), r.trigger, ' → ', el('span', { class: 'plan-action' }, r.action));
    })));
    return wrap;
  }

  function useEval(c, symbol, recId) {
    App.state.ticket = { world: App.state.world || 'observed', candidate: c, symbol: symbol, step: 5, recommendationId: recId || null };
    App.navigate('#/trade/place');
  }

  function decisionTop(e, symbol, level, recId) {
    var c = e.candidate, risk = e.risk, capital = e.capital, sc = e.score;
    var verdict = economicVerdict(e) || 'UNAVAILABLE';
    var placement = verdict === 'FAVORABLE' ? 'WORTH INVESTIGATING'
      : verdict === 'MIXED' ? 'COMPARE CAREFULLY'
      : verdict === 'UNFAVORABLE' ? 'LEARNING EXAMPLE' : 'MECHANICS ONLY';
    var card = el('div', { class: 'card decision-pick', 'data-strategy': c.strategy });
    card.appendChild(UI.cardHeader(
      el('span', {}, el('span', { class: 'pick-badge' }, placement), ' ', c.displayName),
      el('span', { class: 'row-gap' }, evidenceBadge(e.evidence.rollup), UI.scoreBar(sc.riskAdjustedScore))));
    if (e.explanation && (e.explanation.whySelected || e.explanation.headline)) {
      card.appendChild(el('p', { class: 'decision-why' }, e.explanation.whySelected || e.explanation.headline));
    }
    var economics = economicAssessmentBlock(e);
    if (economics) card.appendChild(economics);
    card.appendChild(el('div', { class: 'chip-row' },
      chip('Cost/credit', fmtMoney(c.entryNetPremiumCents, { plus: true })),
      chip('Theoretical max loss', el('span', { class: 'loss' }, fmtMoney(risk.maxLossCents))),
      chip('Theoretical max profit', UI.maxProfitLabel(c.strategy, c.structureGroup,
        risk.maxProfitCents, level !== 'expert', c.legs)),
      chip('Chance of profit', fmtPct(risk.pop)),
      c.assignmentProb !== null && c.assignmentProb !== undefined ? chip('Assignment', fmtPct(c.assignmentProb)) : null));
    // The honest capital pair — buying power used vs full economic exposure.
    card.appendChild(el('div', { class: 'chip-row' },
      chip('Buying power used', fmtMoney(capital.incrementalCents)),
      chip('Full exposure', fmtMoney(capital.economicCents)),
      capital.returnOnCapitalPct != null ? chip('Best-case return', Math.round(capital.returnOnCapitalPct) + '%') : null,
      capital.annualizedRocPct != null ? chip('annualized', Math.round(capital.annualizedRocPct) + '%/yr') : null));
    var strip = scenarioStrip(risk, symbol);
    if (strip) {
      card.appendChild(el('div', { class: 'decision-sub' }, 'If ' + symbol + ' moves by expiry'));
      card.appendChild(strip);
    }
    if (level === 'expert') {
      card.appendChild(UI.expandable('Evidence by dimension', function () { return evidenceGrid(e.evidence); }));
      card.appendChild(UI.expandable('How this score was built', function () { return scoreGrid(sc); }));
    }
    card.appendChild(UI.expandable('The plan after you enter', function () { return planList(e.management); },
      { open: level === 'beginner' }));
    if (e.explanation && e.explanation.failureModes && e.explanation.failureModes.length) {
      card.appendChild(UI.expandable('What could go wrong', function () {
        return el('ul', {}, e.explanation.failureModes.map(function (f) { return el('li', {}, f); }));
      }));
    }
    card.appendChild(el('div', { class: 'btn-row' },
      el('button', { class: 'btn', id: 'decision-use', onclick: function () { useEval(c, symbol, recId); } }, 'Practice this trade')));
    return card;
  }

  function decisionAltList(evals, symbol) {
    return el('div', { class: 'alt-list' }, evals.map(function (e) {
      var c = e.candidate;
      return el('div', { class: 'alt-row', 'data-strategy': c.strategy },
        el('div', { class: 'alt-main' }, el('b', {}, c.displayName),
          el('span', { class: 'badge economic-table-' + String(economicVerdict(e) || 'unknown').toLowerCase() },
            (e.economics && e.economics.label) || 'Economics unavailable'),
          el('span', { class: 'muted' }, '  ' + c.label)),
        el('div', { class: 'alt-facts' },
          chip('Decision score', Math.round(e.score.riskAdjustedScore), 'Risk-adjusted: gates, six weighted factors, then haircuts for evidence quality and tail risk. May rank differently than the quick screen score — this one decides.'),
          chip('Market EV', e.economics && e.economics.marketEvAfterCostsCents != null
            ? fmtMoney(e.economics.marketEvAfterCostsCents, { plus: true }) : 'Unavailable'),
          chip('History EV', e.economics && e.economics.realizedVolEvAfterCostsCents != null
            ? fmtMoney(e.economics.realizedVolEvAfterCostsCents, { plus: true }) : 'Unavailable'),
          chip('Theoretical max loss', el('span', { class: 'loss' }, fmtMoney(e.risk.maxLossCents)))),
        el('button', { class: 'btn btn-sm', onclick: function () { useEval(c, symbol); } }, 'Use'));
    }));
  }

  function decisionTable(evals, symbol) {
    var rows = evals.map(function (e) {
      var c = e.candidate, r = e.risk;
      return el('tr', { 'data-strategy': c.strategy },
        el('td', {}, c.displayName),
        el('td', {}, el('span', { class: 'badge economic-table-' + String(economicVerdict(e) || 'unknown').toLowerCase() },
          (e.economics && e.economics.label) || 'Unavailable')),
        el('td', {}, UI.scoreBar(e.score.riskAdjustedScore)),
        el('td', {}, evidenceBadge(e.evidence.rollup)),
        el('td', {}, fmtMoney(c.entryNetPremiumCents, { plus: true })),
        el('td', {}, el('span', { class: 'loss' }, fmtMoney(r.maxLossCents))),
        el('td', {}, UI.maxProfitLabel(c.strategy, c.structureGroup, r.maxProfitCents, false, c.legs)),
        el('td', {}, fmtPct(r.pop)),
        el('td', {}, e.economics && e.economics.marketEvAfterCostsCents != null
          ? fmtMoney(e.economics.marketEvAfterCostsCents, { plus: true }) : '—'),
        el('td', {}, e.economics && e.economics.realizedVolEvAfterCostsCents != null
          ? fmtMoney(e.economics.realizedVolEvAfterCostsCents, { plus: true }) : '—'),
        el('td', {}, el('span', { class: 'loss' }, fmtMoney(r.tailLossCents))),
        el('td', {}, el('button', { class: 'btn btn-sm', onclick: function () { useEval(c, symbol); } }, 'Use')));
    });
    return el('div', { id: 'decision-table' },
      UI.table(['Structure', 'Economic read', 'Decision score', 'Evidence', 'Cost', 'Theor. max loss',
        'Theor. max profit', 'POP', 'Market EV', 'History EV', 'Tail loss', ''], rows));
  }

  /**
   * The recommendations-as-a-competition render, reusable both as the standalone #/decision page
   * and INLINE inside the Ideas results (D3 — it is no longer an orphan surface). Fills `host`.
   */
  async function renderCompetition(host, symbol) {
    var level = Learn.currentLevel();
    var form = App.state.discoverForm || {};
    var riskMode = 'balanced';
    try { var rm = document.getElementById('risk-mode'); if (rm && rm.value) riskMode = rm.value; } catch (e) { /* default */ }
    var body = {
      symbol: symbol,
      thesis: form.thesis || 'neutral',
      horizon: form.horizon || 'month',
      riskMode: riskMode,
      intent: form.goal || form.intent || null
    };
    // Level flips and re-renders must NOT re-POST /api/evaluate: the endpoint records the
    // pick as a recommendation for calibration, so a cosmetic re-render would double-record.
    // Cache by the exact inputs; an in-flight guard also collapses the nav+render double-call
    // (and view-transition re-renders) onto ONE request. Refresh busts the cache.
    var cacheKey = JSON.stringify(body) + '|world=' + (App.state.world || 'observed')
      + '|scenario=' + ((App.config && App.config.scenarioMode && App.config.activeDataset) || 'observed');
    var cached = App.state.decisionCache;
    var data;
    if (cached && cached.key === cacheKey) {
      data = cached.data;
    } else {
      host.innerHTML = '';
      host.appendChild(UI.spinner('Scoring the alternatives…'));
      var inflight = App.state.decisionInflight;
      var promise;
      if (inflight && inflight.key === cacheKey) {
        promise = inflight.promise;                       // a concurrent render already asked
      } else {
        promise = App.evaluateOutcome('DECISION', 'DECISION_POLICY', symbol, { decision: body });
        App.state.decisionInflight = { key: cacheKey, promise: promise };
      }
      try { data = await promise; }
      catch (e) {
        if (App.state.decisionInflight && App.state.decisionInflight.key === cacheKey) App.state.decisionInflight = null;
        host.innerHTML = '';
        host.appendChild(alertBox('error', 'Could not compare ideas', [String((e && e.message) || e)]));
        return;
      }
      if (App.state.decisionInflight && App.state.decisionInflight.key === cacheKey) App.state.decisionInflight = null;
      App.state.decisionCache = { key: cacheKey, data: data };
    }
    host.innerHTML = '';
    var evals = (data && data.evaluations) || [];
    if (!evals.length) {
      host.appendChild(UI.emptyState('No comparable ideas',
        'The engine found nothing viable for this goal on ' + symbol + '. Try another goal or stock.'));
      return;
    }
    host.appendChild(decisionTop(evals[0], symbol, level, data && data.recommendationId));
    if (evals.length > 1) {
      host.appendChild(el('h2', { class: 'section-h' }, level === 'beginner' ? 'Other ways to play it' : 'The full field'));
      host.appendChild(el('p', { class: 'muted small decision-order-note' }, level === 'beginner'
        ? 'Order: trades that pass the checks, then the economic read, then Decision score. A learning example never outranks a mechanically sound idea whose economics are simply unavailable.'
        : 'Sort policy: mechanical eligibility → economic tier (favorable, mixed, unavailable, unfavorable) → Decision score within each tier.'));
      host.appendChild(level === 'beginner' ? decisionAltList(evals.slice(1), symbol) : decisionTable(evals, symbol));
    }
    // The portfolio optimizer — "size the strongest ideas across a budget" — lives with the
    // competition it operates on.
    App.state.portfolioOptimizer = App.state.portfolioOptimizer || {};
    host.appendChild(el('h2', { class: 'section-h' }, 'Size the strongest ideas across a budget'));
    host.appendChild(optimizerCard(level, App.state.portfolioOptimizer));
  }

  async function decision(root, params) {
    var level = Learn.currentLevel();
    var form = App.state.discoverForm || {};
    var symbol = (params[0] || App.context.symbol() || form.symbol || '').toUpperCase();

    root.appendChild(el('h1', {}, 'Compare ideas'));
    if (level === 'beginner') {
      root.appendChild(explain('The same goal, a few different ways — judged side by side. The first is the strongest available comparison, not an automatic recommendation; its economic label tells you whether to investigate it, compare carefully, study only the mechanics, or learn from it as a counterexample.'));
    }
    if (!symbol) {
      root.appendChild(UI.emptyState('No stock chosen yet', 'Pick a stock and a goal first, then compare the ideas.',
        'Find ideas', function () { App.navigate('#/trade/discover'); }));
      return;
    }
    var decisionContext = lockedSymbolBar(symbol, { id: 'decision-symbol-context', label: 'Compared symbol' });
    decisionContext.querySelector('.symbol-context-actions').appendChild(
      el('button', { class: 'btn btn-sm btn-secondary', id: 'decision-refresh', onclick: function () {
        App.state.decisionCache = null; App.render();
      } }, 'Refresh'));
    root.appendChild(decisionContext);

    var host = el('div', { id: 'decision-host' });
    root.appendChild(host);
    // Detached: renderCompetition fills the captured host, so the route returns immediately and
    // a new navigation is never trapped behind the evaluation call.
    renderCompetition(host, symbol).catch(function () { /* handled inside */ });
  }

  // ---- Shared Research/Trade workbench components ----

  var CHART_PALETTE = ['#2f6bde', '#7c4fe0', '#3aa76d', '#e0912f', '#d64545', '#2ba3b8', '#b8556f', '#6b8e23'];
  function chartColor(i) { return CHART_PALETTE[i % CHART_PALETTE.length]; }

  function toolField(label, input) {
    return el('div', { class: 'field' }, el('label', {}, label), input);
  }

  /** A stat/fact tile (label over value), matching the tiles the recommendation pages use. */
  function metricFact(label, value, cls) {
    return el('div', { class: 'fact' + (cls ? ' ' + cls : '') },
      el('div', { class: 'f-label' }, label),
      el('div', { class: 'f-value' }, value));
  }

  /** A card header with an icon-tile accent, like the recommendation surfaces. */
  function toolHeader(iconName, title, right) {
    return UI.cardHeader(
      el('span', { class: 'tool-title' }, el('span', { class: 'icon-tile icon-tile-sm' }, icon(iconName)), el('b', {}, title)),
      right || null);
  }

  /** Horizontal stacked composition bar (SVG, hover per segment) + a value legend. */
  function compositionChart(segments) {
    var total = segments.reduce(function (s, x) { return s + Math.max(0, x.value); }, 0) || 1;
    var x = 0, rects = '';
    segments.forEach(function (s, i) {
      var w = Math.max(0, s.value) / total * 100;
      rects += '<rect x="' + x.toFixed(3) + '" y="0" width="' + Math.max(0, w - 0.4).toFixed(3) + '" height="22" rx="1.5" fill="'
        + chartColor(i) + '"><title>' + s.label + ' — ' + fmtMoney(s.value) + ' (' + w.toFixed(0) + '%)</title></rect>';
      x += w;
    });
    var svg = '<svg viewBox="0 0 100 22" preserveAspectRatio="none" width="100%" height="22" role="img" aria-label="Allocation by symbol">' + rects + '</svg>';
    var legend = el('div', { class: 'chart-legend' }, segments.map(function (s, i) {
      return el('span', { class: 'chart-legend-item' },
        el('span', { class: 'chart-swatch', style: 'background:' + chartColor(i) }),
        el('b', {}, s.label), ' ', el('span', { class: 'muted' }, fmtMoney(s.value)));
    }));
    return el('div', {}, el('div', { class: 'tool-chart', html: svg }), legend);
  }

  /** A 0..1 bar with a baseline marker; fill greens above baseline, reds below (SVG, hover). */
  function gaugeChart(value01, baseline01, caption) {
    var v = Math.max(0, Math.min(1, value01)) * 100, b = Math.max(0, Math.min(1, baseline01)) * 100;
    var col = value01 >= baseline01 ? 'var(--risk-ok-solid,#3aa76d)' : 'var(--risk-danger-solid,#d64545)';
    var svg = '<svg viewBox="0 0 100 16" preserveAspectRatio="none" width="100%" height="16" role="img">'
      + '<rect x="0" y="5" width="100" height="6" rx="3" fill="var(--border)"/>'
      + '<rect x="0" y="5" width="' + v.toFixed(2) + '" height="6" rx="3" fill="' + col + '"><title>' + Math.round(v) + '%</title></rect>'
      + '<line x1="' + b.toFixed(2) + '" y1="1.5" x2="' + b.toFixed(2) + '" y2="14.5" stroke="var(--text)" stroke-width="0.8"/>'
      + '<circle cx="' + v.toFixed(2) + '" cy="8" r="2.4" fill="' + col + '" stroke="var(--surface)" stroke-width="0.8"/></svg>';
    return el('div', {}, el('div', { class: 'tool-chart', html: svg }),
      caption ? el('div', { class: 'muted small' }, caption) : null);
  }

  // ---- Optimizer ----
  function optimizerCard(level, ctx) {
    var f = ctx || (App.state.portfolioOptimizer = App.state.portfolioOptimizer || {});
    var defaultBudget = f.budget || (ctx && ctx.accountCents ? Math.round(ctx.accountCents / 100) : 25000);
    var card = el('div', { class: 'card tool-card portfolio-optimizer' });
    card.appendChild(toolHeader('grid', 'Build a portfolio'));
    card.appendChild(explain('Allocate your account across the strongest ideas in your universe — diversified by symbol and capped per position. Only ideas that pass the risk screens are funded.'));

    var budget = el('input', { type: 'number', id: 'portfolio-budget', value: defaultBudget, min: '0', step: '1000' });
    var goal = el('select', { id: 'portfolio-goal' },
      el('option', { value: '' }, 'Any goal'), el('option', { value: 'INCOME' }, 'Income'),
      el('option', { value: 'DIRECTIONAL' }, 'Directional'));
    if (f.goal) goal.value = f.goal;
    // Scan scope: honor the shared context's Working stock — "my whole universe" or just that stock.
    var scope = el('select', { id: 'portfolio-scope' },
      el('option', { value: 'universe' }, 'My whole universe'),
      el('option', { value: 'symbol' }, 'Just the working stock'));
    scope.value = f.scope || 'universe';
    var fields = [toolField('Budget ($)', budget), toolField('Ideas from', scope), toolField('Goal', goal)];
    var objective = null, maxPos = null, maxSym = null;
    if (level === 'expert') {
      objective = el('select', { id: 'portfolio-objective' }, el('option', { value: 'score' }, 'Best score'), el('option', { value: 'ev' }, 'Best expected value'));
      if (f.objective) objective.value = f.objective;
      maxPos = el('input', { type: 'number', id: 'portfolio-max-positions', value: f.maxPos || 8, min: '1', max: '20' });
      maxSym = el('input', { type: 'number', id: 'portfolio-max-symbol-pct', value: f.maxSym || 40, min: '5', max: '100', step: '5' });
      fields.push(toolField('Rank by', objective), toolField('Max positions', maxPos), toolField('Max % per symbol', maxSym));
    }
    card.appendChild(el('div', { class: 'form-grid' }, fields));

    // Expert-only diagnostic mode: normally the optimizer funds ONLY positive-expected-value ideas
    // (it must not present a money-losing portfolio as an answer). Diagnostic mode surfaces the
    // least-bad set anyway, clearly labeled — for inspecting a weak universe, not for acting on.
    var diag = null;
    if (level === 'expert') {
      diag = el('input', { type: 'checkbox', id: 'portfolio-diagnostics' });
      if (f.diagnostic) diag.checked = true;
      card.appendChild(el('label', { class: 'tool-check' }, diag,
        el('span', {}, ' Diagnostic mode — show the least-bad set even if no idea has positive expected value')));
    }

    var out = el('div', { id: 'portfolio-output', class: 'tool-output' });
    var run = el('button', { class: 'btn', id: 'portfolio-build' }, 'Build portfolio');
    run.addEventListener('click', async function () {
      f.budget = +budget.value; f.goal = goal.value; f.scope = scope.value;
      if (objective) { f.objective = objective.value; f.maxPos = +maxPos.value; f.maxSym = +maxSym.value; }
      if (diag) f.diagnostic = diag.checked;
      run.disabled = true; out.innerHTML = ''; out.appendChild(UI.spinner('Scanning the universe and allocating…'));
      var sym = (ctx && ctx.symbol) || App.context.symbol();
      var body = {
        totalCapitalCents: Math.round((+budget.value || 0) * 100),
        intent: goal.value || null,
        objective: objective ? objective.value : 'score',
        maxPositions: maxPos ? +maxPos.value : null,
        maxSymbolPct: maxSym ? +maxSym.value / 100 : null,
        diagnostic: diag ? diag.checked : false
      };
      if (scope.value === 'symbol' && sym) body.universe = [sym];   // A5: the working stock actually scopes the optimizer
      try { var _d = await API.post('/api/optimize', body); f.result = _d; renderOptimization(out, _d, level); }
      catch (e) { out.innerHTML = ''; out.appendChild(alertBox('danger', 'Optimize failed', [String((e && e.message) || e)])); }
      finally { run.disabled = false; }
    });
    card.appendChild(el('div', { class: 'btn-row' }, run));
    card.appendChild(out);
    if (f.result) renderOptimization(out, f.result, level); // survive a level flip / nav without re-scanning
    return card;
  }

  function renderOptimization(out, data, level) {
    out.innerHTML = '';
    var o = (data && data.optimization) || {};
    var allocs = o.allocations || [];
    if (!allocs.length) {
      out.appendChild(UI.emptyState('Nothing funded', (o.notes && o.notes[0]) || 'No viable ideas fit the budget. Raise it or widen the goal.'));
      return;
    }
    var perSym = o.perSymbolCents || {};
    var nSym = Object.keys(perSym).length;
    // Diagnostic sets are NOT a recommendation — say so loudly before anything reads as an answer.
    if (o.diagnostic) {
      out.appendChild(alertBox('caution', 'Diagnostic set — NOT a recommendation. '
        + 'No idea in this universe has positive modeled expected value; this is the least-bad allocation, shown for inspection only.'));
    }
    // Conclusion first: what this portfolio IS, in one sentence. In diagnostic mode it is the
    // "least-bad set", never "Funded" (which reads as an endorsement).
    var lead = o.diagnostic ? 'Least-bad set: ' : 'Funded ';
    out.appendChild(el('div', { class: 'tool-verdict' },
      el('b', {}, lead + allocs.length + ' position' + (allocs.length === 1 ? '' : 's')
        + ' across ' + nSym + ' symbol' + (nSym === 1 ? '' : 's') + ' for ' + fmtMoney(o.capitalUsedCents) + '.'),
      ' Expected value ', pnlSpan(o.expectedValueCents),
      ', worst-case tail ', el('span', { class: 'loss' }, fmtMoney(-Math.abs(o.totalTailLossCents || 0))), '.'));

    out.appendChild(el('div', { class: 'fact-grid', id: 'portfolio-summary' },
      metricFact('Capital used', fmtMoney(o.capitalUsedCents)),
      metricFact('Positions', String(allocs.length)),
      metricFact('Expected value', pnlSpan(o.expectedValueCents), o.expectedValueCents >= 0 ? 'f-ok' : 'f-danger'),
      metricFact('Tail risk', el('span', { class: 'loss' }, fmtMoney(-Math.abs(o.totalTailLossCents || 0))), 'f-danger'),
      metricFact('Avg score', String(Math.round(o.avgScore || 0)))));

    var segments = Object.keys(perSym).map(function (k) { return { label: k, value: perSym[k] }; });
    if (segments.length) {
      out.appendChild(el('div', { class: 'decision-sub' }, 'How the capital is spread'));
      out.appendChild(compositionChart(segments));
    }

    var rows = allocs.map(function (a) {
      var e = a.eval || {}, sp = e.spec || {}, cand = e.candidate || {}, sc = e.score || {};
      var name = cand.displayName || sp.family || '—';
      if (level === 'expert') {
        return el('tr', {},
          el('td', {}, sp.symbol || '—'), el('td', {}, name),
          el('td', {}, String(a.units)), el('td', {}, fmtMoney(a.capitalCents)),
          el('td', {}, UI.scoreBar(sc.riskAdjustedScore || 0)));
      }
      return el('tr', {},
        el('td', {}, el('b', {}, sp.symbol || '—')),
        el('td', {}, name),
        el('td', {}, a.units + '×  ' + fmtMoney(a.capitalCents)));
    });
    out.appendChild(level === 'expert'
      ? UI.table(['Symbol', 'Structure', 'Units', 'Capital', 'Score'], rows)
      : UI.table(['Symbol', 'Idea', 'Allocation'], rows));
    (o.notes || []).forEach(function (n) { out.appendChild(el('div', { class: 'muted small' }, n)); });
  }

  // ---- Hypothesis tester ----
  // A distribution histogram of forward returns — green bars for gaining outcomes, red for losing.
  function distChart(buckets) {
    if (!buckets || !buckets.length) return null;
    var max = 1; buckets.forEach(function (b) { max = Math.max(max, b.count); });
    var n = buckets.length, bw = 100 / n;
    var bars = buckets.map(function (b, i) {
      var h = b.count / max * 30;
      var mid = (b.fromPct + b.toPct) / 2;
      var col = mid >= 0 ? 'var(--risk-ok-solid,#3aa76d)' : 'var(--risk-danger-solid,#d64545)';
      var x = i * bw + bw * 0.12, w = bw * 0.76;
      return '<rect x="' + x.toFixed(2) + '" y="' + (34 - h).toFixed(2) + '" width="' + w.toFixed(2)
        + '" height="' + h.toFixed(2) + '" rx="0.6" fill="' + col + '"><title>' + b.fromPct + '% to ' + b.toPct + '%: ' + b.count + '</title></rect>';
    }).join('');
    var svg = '<svg viewBox="0 0 100 40" preserveAspectRatio="none" width="100%" height="70" role="img">'
      + '<line x1="0" y1="34" x2="100" y2="34" stroke="var(--border)" stroke-width="0.5"/>' + bars + '</svg>';
    return el('div', {}, el('div', { class: 'tool-chart', html: svg }),
      el('div', { class: 'muted small' }, 'Forward-return distribution after the signal (each bar = a return range)'));
  }

  // "Test an idea" — a real research-question workbench: pick a question, and we compare what happened
  // AFTER the signal against the stock's own normal behavior (baseline), with sample size, a win-rate
  // edge, a significance test, a bootstrap confidence interval, a distribution, and example dates.
  /**
   * TEST YOUR VIEW — the thesis stage of Research on a symbol (adversarial-review refactor):
   * one shared MarketThesis (direction + horizon) drives BOTH connected stages —
   *   Past evidence  : what followed similar conditions in the active lane's disclosed history
   *   Possible futures: what a stochastic model says could happen from here
   * Both inherit the page symbol (no nested ticker), results are keyed by symbol+question+
   * params+range and never restored across a mismatch, and the evidence hands its EXACT analog
   * windows to Trade as a path source: the next answer after you state a view.
   */
  function testYourViewSection(symbol) {
    var level = Learn.currentLevel();
    var historyBasis = activeHistoryBasis();
    App.state.marketThesis = App.state.marketThesis || {};
    var th = App.state.marketThesis[symbol] = App.state.marketThesis[symbol]
      || { direction: 'rebound', horizonDays: 10 };
    var wrap = el('div', { class: 'card', id: 'test-your-view' });
    wrap.appendChild(UI.cardHeader('Test your view \u2014 ' + symbol));
    wrap.appendChild(explain(level === 'beginner'
      ? 'Say what you think ' + symbol + ' does next. StrikeBench checks ' + historyBasis.plain + ' for similar moments (and what followed), and draws model-generated possible futures — then you can test strategies against either.'
      : 'One thesis drives both lenses: the ' + historyBasis.expert + ' (conditional history, bootstrap CI) and the parametric Monte Carlo fan. The analog windows behind the study are reusable as a strategy path source in Trade.'));

    // ---- The shared thesis row ----
    var dirSel = el('select', { id: 'tv-direction' },
      el('option', { value: 'rebound' }, level === 'beginner' ? 'It fell — I expect a rebound' : 'Pullback \u2192 rebound'),
      el('option', { value: 'bounce' }, level === 'beginner' ? 'Big down day — I expect a bounce' : 'Oversold \u2192 bounce'),
      el('option', { value: 'breakout' }, level === 'beginner' ? 'New high — I expect follow-through' : 'Breakout \u2192 follow-through'),
      el('option', { value: 'momentum' }, level === 'beginner' ? 'It has been strong — I expect more' : 'Momentum persists'),
      el('option', { value: 'streak' }, level === 'beginner' ? 'Up-day streak — I expect it to continue' : 'Streak continues'));
    dirSel.value = th.direction;
    var horIn = el('input', { type: 'number', id: 'tv-horizon', value: String(th.horizonDays), min: '1', max: '60' });
    var thesisRow = el('div', { class: 'form-grid' },
      el('div', { class: 'field' }, el('label', { class: 'field-label' }, 'What do you think happens?'), dirSel),
      el('div', { class: 'field' }, el('label', { class: 'field-label' }, 'Over how many trading days?'), horIn));
    wrap.appendChild(thesisRow);

    // ---- Two connected evidence bases through the shared Outcomes component ----
    var tvState = App.state.researchStudy = App.state.researchStudy || {};
    tvState.mode = tvState.mode || 'past';
    var outcomeWorkspace = Outcomes.workspace({
      id: 'research-outcomes', state: tvState, label: symbol + ' evidence basis',
      modes: [
        { key: 'past', label: 'Past evidence',
          description: 'What followed similar conditions?',
          note: 'Conditional history: independent signal episodes compared with the stock\u2019s normal behavior.',
          render: function (host) { host.appendChild(evidenceStage(symbol, th, level)); } },
        { key: 'futures', label: 'Possible futures',
          description: 'What could happen from here?',
          note: 'Parametric scenarios: generated paths from an explicit model, never a forecast.',
          render: function (host) { host.appendChild(futuresStage(symbol, th, level)); } }
      ]
    });
    wrap.appendChild(outcomeWorkspace.el);

    dirSel.addEventListener('change', function () { th.direction = dirSel.value; outcomeWorkspace.refresh(); });
    horIn.addEventListener('change', function () {
      th.horizonDays = Math.max(1, Math.min(60, parseInt(horIn.value, 10) || 10)); outcomeWorkspace.refresh();
    });
    return wrap;
  }

  /** thesis direction -> the event-study question that tests it. */
  var THESIS_QUESTION = { rebound: 'pullback_rebound', bounce: 'oversold_bounce',
    breakout: 'breakout_followthrough', momentum: 'momentum', streak: 'up_streak' };

  /** The study's identity: results may only render when the visible controls match this key. */
  function studyClientKey(symbol, qKey, params, from, to) {
    var ordered = {};
    Object.keys(params || {}).sort().forEach(function (k) { ordered[k] = params[k]; });
    return symbol + '|' + qKey + '|' + (from || '') + '..' + (to || '') + '|' + JSON.stringify(ordered);
  }

  /** PAST EVIDENCE: the event study, inheriting the page symbol + the shared thesis. */
  function evidenceStage(symbol, thesis, level) {
    var f = App.state.researchStudy;
    f.results = f.results || {};
    f.protocolBySymbol = f.protocolBySymbol || {};
    var protocol = f.protocolBySymbol[symbol] = Object.assign({
      window: '3y', from: '', to: '', strictness: 'balanced', regime: 'ALL',
      eventSpacing: thesis.horizonDays, minSample: 15, confidencePct: 95,
      bootstrapSamples: 800, multiplicity: 'CATALOG_BONFERRONI', splitHalf: true
    }, f.protocolBySymbol[symbol] || {});
    var stage = el('div', { id: 'what-has-happened' });
    var qKey = THESIS_QUESTION[thesis.direction] || 'pullback_rebound';
    var picker = el('div', { id: 'study-question-picker' });
    stage.appendChild(picker);
    var out = el('div', { id: 'study-results', class: 'tool-output' });
    var run = el('button', { class: 'btn', id: 'study-run', disabled: 'disabled' }, 'Check the past');
    stage.appendChild(el('div', { class: 'btn-row' }, run));
    stage.appendChild(out);

    var paramInputs = {}, protocolInputs = {};
    function isoYearsAgo(years) {
      var d = new Date(); d.setUTCFullYear(d.getUTCFullYear() - years);
      return d.toISOString().slice(0, 10);
    }
    function today() { return new Date().toISOString().slice(0, 10); }
    function windowFrom(value) { return value === '1y' ? isoYearsAgo(1) : value === 'max' ? '2000-01-01' : isoYearsAgo(3); }
    if (!protocol.from) protocol.from = windowFrom(protocol.window);
    if (!protocol.to) protocol.to = today();

    function currentParams(q) {
      var params = {};
      (q.params || []).forEach(function (pr) {
        params[pr.key] = paramInputs[pr.key] ? +paramInputs[pr.key].value
          : (f.params && f.params[q.key] && f.params[q.key][pr.key]) != null
            ? f.params[q.key][pr.key] : pr.def;
      });
      params.forward = thesis.horizonDays; // the THESIS horizon is the study horizon — one truth
      params.eventSpacing = +(protocolInputs.eventSpacing ? protocolInputs.eventSpacing.value : protocol.eventSpacing || thesis.horizonDays);
      params.minSample = +(protocolInputs.minSample ? protocolInputs.minSample.value : protocol.minSample);
      params.confidencePct = +(protocolInputs.confidencePct ? protocolInputs.confidencePct.value : protocol.confidencePct);
      params.bootstrapSamples = +(protocolInputs.bootstrapSamples ? protocolInputs.bootstrapSamples.value : protocol.bootstrapSamples);
      params.regime = protocolInputs.regime ? protocolInputs.regime.value : protocol.regime;
      params.multiplicity = protocolInputs.multiplicity ? protocolInputs.multiplicity.value : protocol.multiplicity;
      params.splitHalf = protocolInputs.splitHalf ? protocolInputs.splitHalf.checked : protocol.splitHalf;
      return params;
    }

    function rememberProtocol() {
      if (protocolInputs.window) protocol.window = protocolInputs.window.value;
      if (protocolInputs.from) protocol.from = protocolInputs.from.value;
      if (protocolInputs.to) protocol.to = protocolInputs.to.value;
      if (protocolInputs.strictness) protocol.strictness = protocolInputs.strictness.value;
      if (protocolInputs.regime) protocol.regime = protocolInputs.regime.value;
      if (protocolInputs.eventSpacing) protocol.eventSpacing = +protocolInputs.eventSpacing.value;
      if (protocolInputs.minSample) protocol.minSample = +protocolInputs.minSample.value;
      if (protocolInputs.confidencePct) protocol.confidencePct = +protocolInputs.confidencePct.value;
      if (protocolInputs.bootstrapSamples) protocol.bootstrapSamples = +protocolInputs.bootstrapSamples.value;
      if (protocolInputs.multiplicity) protocol.multiplicity = protocolInputs.multiplicity.value;
      if (protocolInputs.splitHalf) protocol.splitHalf = protocolInputs.splitHalf.checked;
    }

    function invalidateVisible() {
      rememberProtocol();
      if (out.children.length) {
        out.innerHTML = '';
        out.appendChild(el('p', { class: 'muted study-rerun-note' }, 'Study settings changed — run the study again to refresh the evidence.'));
      }
    }

    function strictValue(qKey0, param, mode) {
      if (mode === 'balanced') return param.def;
      var loose = mode === 'more';
      if (param.key === 'dropPct') return qKey0 === 'pullback_rebound'
        ? (loose ? 3 : 8) : (loose ? 2 : 5);
      if (param.key === 'thresholdPct') return loose ? 3 : 10;
      if (param.key === 'streak') return loose ? 2 : 5;
      if (param.key === 'lookback' && qKey0 === 'breakout_followthrough') return loose ? 10 : 60;
      return param.def;
    }

    function buildPicker(cat) {
      picker.innerHTML = '';
      paramInputs = {}; protocolInputs = {};
      var q = cat.find(function (x) { return x.key === qKey; }) || cat[0];
      picker.appendChild(el('div', { class: 'study-question-line', id: 'tv-question-line' },
        el('b', {}, q.title), el('span', { class: 'muted' }, q.plain + ' · ' + thesis.horizonDays + '-trading-day outcome')));

      var signalFields = [];
      (q.params || []).forEach(function (pr) {
        if (pr.key === 'forward') return;
        var saved = (f.params && f.params[q.key] && f.params[q.key][pr.key]);
        var inp = el('input', { type: 'number', id: 'study-param-' + pr.key,
          value: saved == null ? strictValue(q.key, pr, protocol.strictness) : saved,
          min: String(pr.min), max: String(pr.max), step: '1' });
        paramInputs[pr.key] = inp;
        signalFields.push(toolField((level === 'beginner' ? pr.label : pr.label + ' (' + pr.unit + ')'), inp));
      });

      protocolInputs.window = el('select', { id: 'study-window' },
        el('option', { value: '1y' }, 'Past year'), el('option', { value: '3y' }, 'Past 3 years'),
        el('option', { value: 'max' }, 'All available history'),
        el('option', { value: 'custom' }, 'Custom dates'));
      protocolInputs.window.value = protocol.window;
      protocolInputs.strictness = el('select', { id: 'study-strictness' },
        el('option', { value: 'more' }, level === 'beginner' ? 'More examples (looser signal)' : 'Loose signal / more events'),
        el('option', { value: 'balanced' }, 'Balanced signal'),
        el('option', { value: 'stronger' }, level === 'beginner' ? 'Fewer, stronger examples' : 'Strict signal / fewer events'),
        el('option', { value: 'custom' }, 'Custom thresholds'));
      protocolInputs.strictness.value = protocol.strictness;
      protocolInputs.regime = el('select', { id: 'study-regime' },
        el('option', { value: 'ALL' }, 'All market conditions'),
        el('option', { value: 'ABOVE_200DMA' }, 'Above 200-day average'),
        el('option', { value: 'BELOW_200DMA' }, 'Below 200-day average'),
        el('option', { value: 'HIGH_VOL' }, 'Higher-volatility regimes'),
        el('option', { value: 'LOW_VOL' }, 'Lower-volatility regimes'));
      protocolInputs.regime.value = protocol.regime;

      var beginnerControls = el('div', { class: 'form-grid study-beginner-controls' },
        toolField('History to check', protocolInputs.window),
        toolField('How selective?', protocolInputs.strictness),
        toolField('Market conditions', protocolInputs.regime));
      picker.appendChild(beginnerControls);

      protocolInputs.from = el('input', { type: 'date', id: 'study-from', value: protocol.from });
      protocolInputs.to = el('input', { type: 'date', id: 'study-to', value: protocol.to, max: today() });
      protocolInputs.eventSpacing = el('input', { type: 'number', id: 'study-spacing', min: '1',
        max: String(thesis.horizonDays), value: String(Math.min(thesis.horizonDays, protocol.eventSpacing || thesis.horizonDays)) });
      protocolInputs.minSample = el('input', { type: 'number', id: 'study-min-sample', min: '5', max: '100', value: String(protocol.minSample) });
      protocolInputs.confidencePct = el('select', { id: 'study-confidence' },
        el('option', { value: '90' }, '90%'), el('option', { value: '95' }, '95%'), el('option', { value: '99' }, '99%'));
      protocolInputs.confidencePct.value = String(protocol.confidencePct);
      protocolInputs.bootstrapSamples = el('input', { type: 'number', id: 'study-bootstrap', min: '200', max: '10000', step: '100', value: String(protocol.bootstrapSamples) });
      protocolInputs.multiplicity = el('select', { id: 'study-multiplicity' },
        el('option', { value: 'CATALOG_BONFERRONI' }, level === 'beginner' ? 'Protect against lucky findings' : 'Bonferroni across question catalog'),
        el('option', { value: 'UNADJUSTED_EXPLORATORY' }, level === 'beginner' ? 'Exploratory (more false alarms)' : 'Unadjusted exploratory'));
      protocolInputs.multiplicity.value = protocol.multiplicity;
      protocolInputs.splitHalf = el('input', { type: 'checkbox', id: 'study-split-half', checked: protocol.splitHalf ? '' : null });

      var exactFields = el('div', { class: 'form-grid study-protocol-grid' }, signalFields,
        toolField(level === 'beginner' ? 'Start date' : 'Sample start', protocolInputs.from),
        toolField(level === 'beginner' ? 'End date' : 'Sample end', protocolInputs.to),
        toolField(level === 'beginner' ? 'Days between examples' : 'Minimum event separation (days)', protocolInputs.eventSpacing),
        toolField(level === 'beginner' ? 'Minimum examples' : 'Minimum event sample', protocolInputs.minSample),
        toolField('Confidence', protocolInputs.confidencePct),
        toolField(level === 'beginner' ? 'Resamples' : 'Bootstrap draws', protocolInputs.bootstrapSamples),
        toolField(level === 'beginner' ? 'Lucky-finding protection' : 'Multiple-testing policy', protocolInputs.multiplicity),
        el('div', { class: 'field inline-check study-split-check' }, protocolInputs.splitHalf,
          el('label', { for: 'study-split-half' }, level === 'beginner' ? 'Check both halves of history' : 'Split-half consistency check')));
      var protocolDetails = el('details', { class: 'study-protocol', open: level === 'expert' ? '' : null },
        el('summary', {}, level === 'beginner' ? 'Fine-tune the evidence check' : 'Study protocol · full controls'),
        el('div', { class: 'study-protocol-body' }, exactFields,
          el('p', { class: 'muted small' }, level === 'beginner'
            ? 'The default keeps examples independent, compares them with non-signal days, resamples the events, and protects against finding a pattern by luck.'
            : 'Signal detection uses no future data. The baseline is the disjoint non-signal complement. Event overlap reduces effective sample size; moving-block bootstrap, a two-sided z-test, Cohen’s d, split-half consistency and catalog-wide multiplicity are reported separately.')));
      picker.appendChild(protocolDetails);
      picker.appendChild(el('p', { class: 'muted small study-description' }, q.description));

      protocolInputs.window.addEventListener('change', function () {
        if (protocolInputs.window.value !== 'custom') protocolInputs.from.value = windowFrom(protocolInputs.window.value);
        invalidateVisible();
      });
      protocolInputs.strictness.addEventListener('change', function () {
        if (protocolInputs.strictness.value === 'custom') { invalidateVisible(); return; }
        (q.params || []).forEach(function (pr) {
          if (pr.key !== 'forward' && paramInputs[pr.key]) paramInputs[pr.key].value = strictValue(q.key, pr, protocolInputs.strictness.value);
        });
        invalidateVisible();
      });
      [protocolInputs.regime, protocolInputs.to, protocolInputs.eventSpacing,
        protocolInputs.minSample, protocolInputs.confidencePct, protocolInputs.bootstrapSamples,
        protocolInputs.multiplicity, protocolInputs.splitHalf].forEach(function (node) {
          node.addEventListener('change', invalidateVisible);
        });
      protocolInputs.from.addEventListener('change', function () {
        protocolInputs.window.value = 'custom'; invalidateVisible();
      });
      Object.keys(paramInputs).forEach(function (key) {
        paramInputs[key].addEventListener('change', function () {
          protocolInputs.strictness.value = 'custom'; invalidateVisible();
        });
      });
      run.disabled = false;
      // KEYED RESTORE: show a stored result ONLY when its key matches the visible controls —
      // an AAPL result may never flash on a QQQ page (adversarial review P1).
      rememberProtocol();
      var key = studyClientKey(symbol, q.key, currentParams(q), protocol.from, protocol.to);
      out.innerHTML = '';
      if (f.results[key]) renderQuestion(out, f.results[key], level, symbol, thesis);
    }

    run.addEventListener('click', async function () {
      var cat = f.questions || [];
      var q = cat.find(function (x) { return x.key === qKey; }) || cat[0];
      if (!q) return;
      var params = currentParams(q);
      f.params = f.params || {}; f.params[q.key] = params;
      rememberProtocol();
      run.disabled = true; out.innerHTML = ''; out.appendChild(UI.spinner('Replaying ' + symbol + '\u2019s history\u2026'));
      var body = { key: q.key, symbol: symbol, from: protocol.from, to: protocol.to, params: params };
      try {
        var _d = await API.post('/api/research/event-studies', body);
        var key = _d.studyKey || studyClientKey(symbol, q.key, params, protocol.from, protocol.to);
        f.results[key] = _d;
        f.results[studyClientKey(symbol, q.key, params, protocol.from, protocol.to)] = _d; // client-key alias
        var keys = Object.keys(f.results);
        if (keys.length > 16) delete f.results[keys[0]]; // bounded memory
        renderQuestion(out, _d, level, symbol, thesis);
      }
      catch (e) { out.innerHTML = ''; out.appendChild(alertBox('danger', 'Study failed', [String((e && e.message) || e)])); }
      finally { run.disabled = false; }
    });

    (async function loadCatalog() {
      var cat = f.questions;
      if (!cat) {
        try { cat = (await API.get('/api/research/questions')).questions || []; f.questions = cat; }
        catch (e) { picker.appendChild(alertBox('warn', 'Could not load the question catalog.')); return; }
      }
      if (!cat.length) { picker.appendChild(alertBox('warn', 'No questions available.')); return; }
      buildPicker(cat);
    })();
    return stage;
  }

  /** POSSIBLE FUTURES: the Monte-Carlo fan, inheriting symbol + thesis horizon. */
  function futuresStage(symbol, thesis, level) {
    var host = el('div', { id: 'tv-futures' });
    var inner = whatIfCard(symbol);
    // Composed stage: the card chrome is the section's — strip the duplicate card skin.
    inner.classList.remove('card');
    host.appendChild(inner);
    return host;
  }

  /**
   * The assembled pre-trade judgment (CP-5): one conclusion-first banner + the probability map +
   * execution cost + the plan — same truth at both levels, beginner gets sentences, expert density.
   * Returns {node, requiredAcks: [{id, label}]} so the caller can gate the Continue button.
   */
  function verdictPanel(p, beginner) {
    var a = p.analytics || {};
    var prob = a.probabilityMap || {};
    var exec = a.executionQuality || {};
    var plan = a.managementPlan || {};
    var wrap = el('div', { id: 'verdict-panel' });
    var kind = a.verdict === 'favorable' ? 'ok' : a.verdict === 'unfavorable' ? 'danger' : 'caution';
    if (a.verdict) {
      wrap.appendChild(alertBox(kind, (a.verdict === 'favorable' ? 'Looks reasonable'
        : a.verdict === 'unfavorable' ? 'The odds are against this trade' : 'Mixed picture'),
        [a.verdictReason || '']));
    }
    if (prob.pAnyProfit !== undefined) {
      wrap.appendChild(el('div', { class: 'grid grid-4', id: 'prob-map' },
        stat(el('span', {}, beginner ? 'Chance of making anything' : 'P(any profit)', UI.info('pop')), fmtPct(prob.pAnyProfit)),
        stat(el('span', {}, beginner ? 'Chance of the FULL theoretical win' : 'P(theoretical max profit)', UI.info('pmaxprofit')), fmtPct(prob.pMaxProfit)),
        stat(el('span', {}, beginner ? 'Chance of the theoretical WORST case' : 'P(theoretical max loss)', UI.info('pmaxloss')),
          el('span', { class: prob.pMaxLoss > 0.4 ? 'loss' : '' }, fmtPct(prob.pMaxLoss))),
        stat(el('span', {}, beginner ? 'A very bad run costs' : 'CVaR 95%', UI.info('cvar')),
          el('span', { class: 'loss' }, fmtMoney(prob.cvar95Cents)))));
      wrap.appendChild(el('div', { class: 'muted small' },
        (prob.basis || '') + (prob.timeBasis ? ' \u00b7 time: ' + prob.timeBasis : '')));
    }
    if (a.rate && a.rate.annual !== undefined && a.rate.annual !== null) {
      var rateEvidence = a.rate.evidence || {};
      var modeledRate = rateEvidence.provenance === 'MODELED' || rateEvidence.provenance === 'MISSING';
      wrap.appendChild(el('div', { class: 'model-input-line muted small', id: 'rate-input' },
        el('span', {}, beginner ? 'Pricing assumes a ' : 'Risk-free rate input '),
        el('b', {}, (Number(a.rate.annual) * 100).toFixed(beginner ? 1 : 3) + '% annual'),
        ' ', UI.evidenceBadge(rateEvidence), UI.info('riskfreerate'),
        modeledRate ? el('span', {}, ' Default assumption; no eligible rate source answered.') : null));
    }
    if (exec.midNetCents !== undefined && exec.midNetCents !== null) {
      var ladder = el('div', { class: 'chip-row', id: 'exec-ladder' },
        chip('Midpoint', fmtMoney(exec.midNetCents, { plus: true }), 'The package priced at every leg\u2019s midpoint — rarely fully fillable, but the honest reference.'),
        chip('Executable now', fmtMoney(exec.executableNetCents, { plus: true }), 'Crossing every book: buys at the ask, sells at the bid.'),
        exec.proposedNetCents !== undefined ? chip('Your price', fmtMoney(exec.proposedNetCents, { plus: true })) : null,
        exec.concessionVsMidCents !== undefined ? chip(
          el('span', {}, beginner ? 'Cost of entering' : 'Concession vs mid', UI.info('concession')),
          el('span', { class: 'loss' }, fmtMoney(exec.concessionVsMidCents)
            + (exec.concessionPctOfMid !== undefined ? ' (' + Math.round(Math.abs(exec.concessionPctOfMid) * 100) + '% of mid)' : ''))) : null,
        exec.exitSpreadEstimateCents !== undefined ? chip('Exit will cost ~', fmtMoney(exec.exitSpreadEstimateCents),
          'Half the package spread again, on the way out.') : null);
      wrap.appendChild(el('div', { class: 'card card-slim', style: 'margin:8px 0' },
        el('h3', { class: 'mt0' }, beginner ? 'What entering actually costs' : 'Execution quality'), ladder));
    }
    if (plan.rules && plan.rules.length) {
      var planBody = el('ul', { class: 'plan-rules' }, plan.rules.map(function (r) { return el('li', {}, r); }));
      wrap.appendChild(beginner
        ? el('div', { class: 'card card-slim', style: 'margin:8px 0' },
            el('h3', { class: 'mt0' }, 'Your plan for this trade (' + (plan.regime || '') + ')'), planBody)
        : UI.expandable('Management plan \u00b7 ' + (plan.regime || ''), planBody, false));
    }
    if (a.evaluatedAtEpochMs || a.asOfEpochMs) {
      var evalMs = a.evaluatedAtEpochMs || a.asOfEpochMs;
      var ageMin = Math.max(0, Math.round((Date.now() - evalMs) / 60000));
      var srcTxt = '';
      if (a.sourceAsOfEpochMs) {
        var srcAge = Math.max(0, Math.round((Date.now() - a.sourceAsOfEpochMs) / 60000));
        srcTxt = 'Data stamped ' + (srcAge <= 1 ? 'just now' : srcAge + ' min ago') + ' \u00b7 ';
      }
      wrap.appendChild(el('div', { class: 'muted small', id: 'quote-age' },
        srcTxt + 'evaluated ' + (ageMin <= 1 ? 'just now' : ageMin + ' min ago')
        + ' on ' + (a.freshness || p.freshness) + ' quotes.'));
    }
    // Material-risk acknowledgments: the trade stays PLACEABLE, but never silently.
    var acks = [];
    if (p.expectedValueCents !== null && p.expectedValueCents !== undefined && p.expectedValueCents < 0) {
      acks.push({ id: 'ack-ev', label: 'I understand the model expects this trade to LOSE '
        + fmtMoney(-p.expectedValueCents) + ' on average at the market\u2019s own volatility.' });
    }
    if (exec.concessionPctOfMid !== undefined && Math.abs(exec.concessionPctOfMid) > 0.10) {
      acks.push({ id: 'ack-exec', label: 'I understand entering surrenders '
        + Math.round(Math.abs(exec.concessionPctOfMid) * 100) + '% of the package midpoint to the bid/ask spread.' });
    }
    if ((plan.regime || '').indexOf('near-expiry') >= 0) {
      acks.push({ id: 'ack-dte', label: 'I understand only ' + plan.sessions + ' trading session'
        + (plan.sessions === 1 ? '' : 's') + ' remain\u2014gamma, weekend gaps and pin risk dominate.' });
    }
    return { node: wrap, requiredAcks: acks };
  }

  function renderQuestion(out, r, level, symbol, thesis) {
    out.innerHTML = '';
    var protocol = r.protocol || { minSample: 15, confidencePct: 95, bootstrapSamples: 800,
      eventSpacingDays: r.forwardDays, effectiveEventBlock: 1, regime: 'ALL',
      multiplicity: 'CATALOG_BONFERRONI', splitHalfCheck: true,
      baseline: 'NON_SIGNAL_COMPLEMENT', criticalZ: 2.576 };
    var few = r.conditioned.sample < protocol.minSample;
    var kind = few ? 'warn' : (r.significant ? (r.winRateEdgePct > 0 ? 'ok' : 'danger') : 'caution');
    out.appendChild(alertBox(kind, r.verdict));
    out.appendChild(el('p', { class: 'muted small' }, r.question));
    // EVIDENCE STRENGTH in one word (beginner-first), never z-score-first: sample size,
    // significance, effect size and split-half consistency roll into weak/moderate/strong.
    var strength = few ? 'weak'
      : (r.significant && Math.abs(r.effectSize || 0) >= 0.2 && r.holdout === 'held') ? 'strong'
      : r.significant ? 'moderate' : 'weak';
    out.appendChild(el('div', { class: 'chip-row', style: 'margin-top:2px' },
      evidenceBadge(r.evidence),
      chip('Evidence', strength,
        'Rolls up sample size, statistical significance, effect size and split-half consistency. Even strong evidence describes the PAST — use it to raise or lower your confidence, never as a prediction.'),
      chip('Signal episodes', String(r.conditioned.sample),
        'Signals are separated by the protocol spacing. If outcome windows still overlap, the effective sample and bootstrap are dependence-adjusted.')));
    out.appendChild(el('div', { class: 'muted small' },
      'Use this to raise or lower your confidence in the view \u2014 it does not predict the next occurrence.'));
    var regimeText = protocol.regime === 'ABOVE_200DMA' ? 'above the 200-day average'
      : protocol.regime === 'BELOW_200DMA' ? 'below the 200-day average'
      : protocol.regime === 'HIGH_VOL' ? 'higher-volatility regimes'
      : protocol.regime === 'LOW_VOL' ? 'lower-volatility regimes' : 'all market conditions';
    out.appendChild(el('details', { class: 'study-result-protocol' },
      el('summary', {}, level === 'beginner' ? 'How this evidence was checked' : 'Protocol and assumptions'),
      el('div', { class: 'study-result-protocol-body' },
        el('div', { class: 'chip-row' },
          chip('Window', r.from + ' \u2192 ' + r.to),
          chip('Regime', regimeText),
          chip('Event spacing', protocol.eventSpacingDays + ' days'),
          chip('Minimum sample', String(protocol.minSample)),
          chip('Confidence', protocol.confidencePct + '%'),
          chip('Bootstrap', protocol.bootstrapSamples + ' draws'),
          chip('Multiple tests', protocol.multiplicity === 'CATALOG_BONFERRONI' ? 'catalog-adjusted' : 'unadjusted exploratory'),
          chip('Split-half', protocol.splitHalfCheck ? 'on' : 'off')),
        el('p', { class: 'muted small' }, level === 'beginner'
          ? 'Each signal uses only information known on that date. Similar dates are separated before counting; ordinary non-signal dates form the comparison group. Resampling estimates uncertainty.'
          : 'No-look-ahead signal; disjoint non-signal baseline; effective event block ' + protocol.effectiveEventBlock
            + '; two-sided z threshold |z| ' + protocol.criticalZ + '; moving-block bootstrap; Cohen\u2019s d; optional split-half consistency.'))));
    // Conditioned win rate vs the baseline (the baseline bar marker) — the honest comparison.
    out.appendChild(gaugeChart(r.conditioned.winRatePct / 100, r.baseline.winRatePct / 100,
      'Positive ' + Math.round(r.conditioned.winRatePct) + '% of the time after the signal vs ' + Math.round(r.baseline.winRatePct) + '% normally — over ' + r.conditioned.sample + ' signals'));
    var facts = el('div', { class: 'fact-grid' },
      metricFact('After the signal', Math.round(r.conditioned.winRatePct) + '% up', r.winRateEdgePct >= 0 ? 'f-ok' : 'f-danger'),
      metricFact('Normally', Math.round(r.baseline.winRatePct) + '% up'),
      metricFact('Edge', (r.winRateEdgePct >= 0 ? '+' : '') + r.winRateEdgePct + ' pts', r.winRateEdgePct >= 0 ? 'f-ok' : 'f-danger'));
    out.appendChild(facts);
    var mean = el('div', { class: 'fact-grid' },
      metricFact('Avg return', (r.conditioned.meanReturnPct >= 0 ? '+' : '') + r.conditioned.meanReturnPct + '%', r.meanEdgePct >= 0 ? 'f-ok' : 'f-danger'),
      metricFact('Worst case', r.conditioned.worstPct + '%', 'f-danger'),
      metricFact('Best case', '+' + r.conditioned.bestPct + '%', 'f-ok'));
    out.appendChild(mean);
    var dc = distChart(r.distribution);
    if (dc) out.appendChild(dc);
    if (r.holdout) {
      out.appendChild(el('div', { class: 'muted small' },
        r.holdout === 'held' ? 'Split-half consistency: the edge pointed the same way in both halves of the window.'
          : 'Split-half consistency: the edge lived in only ONE half of the window \u2014 possibly one regime\u2019s story.'));
    }
    if (level === 'expert') {
      out.appendChild(el('div', { class: 'chip-row' },
        chip('z-score', String(r.zScore)),
        chip(protocol.confidencePct + '% CI (avg)', r.ciLowPct + '% … ' + r.ciHighPct + '%'),
        r.effectSize !== null && r.effectSize !== undefined
          ? chip('Effect size', String(r.effectSize), 'Cohen\u2019s d: the edge measured in units of the stock\u2019s normal noise. Under ~0.2 is negligible even when statistically significant.') : null,
        r.holdout ? chip('Consistency', r.holdout === 'held' ? 'held' : 'faded',
          'Split-half check on the SAME window \u2014 in-sample consistency, not genuine out-of-sample validation.') : null,
        chip('Baseline avg', (r.baseline.meanReturnPct >= 0 ? '+' : '') + r.baseline.meanReturnPct + '%'),
        chip('Significant', r.significant ? 'yes' : 'no',
          protocol.multiplicity === 'CATALOG_BONFERRONI'
            ? 'Uses the catalog-adjusted critical z shown in the protocol.'
            : 'Exploratory and unadjusted; repeated searching raises false-positive risk.')));
    }
    if (r.exampleDates && r.exampleDates.length) {
      out.appendChild(el('div', { class: 'muted small', style: 'margin-top:4px' }, 'Example signal dates: ' + r.exampleDates.join(', ')));
    }
    (r.notes || []).forEach(function (n) { out.appendChild(el('div', { class: 'muted small' }, n)); });
    // THE DOWNSTREAM CONSEQUENCE: the exact analog windows become a strategy path source —
    // research completed here is the starting point in Trade, not a report with no next step.
    if (symbol && r.analogPaths && r.analogPaths.length >= 5) {
      out.appendChild(el('div', { class: 'btn-row', style: 'margin-top:8px' },
        el('button', { class: 'btn', id: 'tv-test-analogs', onclick: function () {
          App.state.evidencePrefill = {
            symbol: symbol,
            source: 'HISTORICAL_ANALOGS',
            study: { key: r.key, from: r.from, to: r.to,
              params: (App.state.researchStudy.params || {})[r.key] || {} },
            studyKey: r.studyKey,
            events: r.conditioned.sample,
            horizonDays: r.forwardDays,
            label: r.conditioned.sample + ' ' + occurrenceWord(r) + ' (' + r.from + ' to ' + r.to + ')'
          };
          App.context.selectSymbol(symbol);             // Verify anchors on the study's symbol
          App.state.verifyMode = 'scenario';            // ...and opens on "Imagine a future"
          App.navigate('#/trade/verify');
        } }, 'Test strategies on these ' + r.conditioned.sample + ' ' + occurrenceWord(r) + ' \u2192')));
    }
  }

  // ---- ETF / exposure replicator ----
  function replicateCard(level, ctx) {
    var f = App.state.tradeReplicator = App.state.tradeReplicator || {};
    var card = el('div', { class: 'card tool-card' });
    card.appendChild(toolHeader('coins', 'Replicate an exposure'));
    card.appendChild(explain('Get the price exposure of owning shares — for far less capital — with a synthetic options position.'));

    var symbol = el('input', { type: 'text', id: 'replicate-symbol', value: (ctx && ctx.symbol) || f.symbol || 'SPY', list: 'universe-symbols' });
    var target = el('input', { type: 'number', id: 'replicate-target', value: f.target || 50000, min: '0', step: '1000' });
    var dir = el('select', { id: 'replicate-direction' }, el('option', { value: 'long' }, 'Bullish (long)'), el('option', { value: 'short' }, 'Bearish (short)'));
    dir.value = f.dir || 'long';
    card.appendChild(el('div', { class: 'form-grid' },
      toolField('Underlying', symbol), toolField('Target exposure ($)', target), toolField('Direction', dir)));

    var out = el('div', { id: 'replicate-output', class: 'tool-output' });
    var run = el('button', { class: 'btn', id: 'replicate-run' }, 'Size it');
    run.addEventListener('click', async function () {
      f.symbol = symbol.value.toUpperCase(); f.target = +target.value; f.dir = dir.value;
      run.disabled = true; out.innerHTML = ''; out.appendChild(UI.spinner('Sizing…'));
      var body = { symbol: symbol.value, targetExposureCents: Math.round((+target.value || 0) * 100), bullish: dir.value === 'long' };
      try { var _d = await API.post('/api/trade/replicate', body); f.result = _d; renderReplication(out, _d); }
      catch (e) { out.innerHTML = ''; out.appendChild(alertBox('danger', 'Replicate failed', [String((e && e.message) || e)])); }
      finally { run.disabled = false; }
    });
    card.appendChild(el('div', { class: 'btn-row' }, run));
    card.appendChild(out);
    if (f.result) renderReplication(out, f.result); // survive a level flip / nav
    return card;
  }

  function renderReplication(out, r) {
    out.innerHTML = '';
    if (!r.contracts) {
      out.appendChild(alertBox('warn', (r.notes && r.notes[0]) || 'Could not size a replication.'));
      if (r.evidence) out.appendChild(UI.evidenceBadge(r.evidence));
      return;
    }
    out.appendChild(el('p', { class: 'decision-why' }, r.structure));
    if (r.evidence) out.appendChild(el('div', { class: 'chip-row' }, UI.evidenceBadge(r.evidence)));
    out.appendChild(el('div', { class: 'chip-row' },
      chip('Contracts', String(r.contracts)),
      chip('Delta exposure', pnlSpan(r.deltaExposureCents)),
      chip('Shares would cost', fmtMoney(r.shareCostCents)),
      chip('Est. margin', fmtMoney(r.estMarginCents))));
    // Capital efficiency: margin vs full share cost.
    var eff = r.shareCostCents > 0 ? r.estMarginCents / r.shareCostCents : 0;
    out.appendChild(gaugeChart(eff, 1, 'Ties up about ' + fmtPct(eff) + ' of the share cost'));
    (r.notes || []).forEach(function (n) { out.appendChild(el('div', { class: 'muted small' }, n)); });
    // Hand-off into the Trade builder for live risk. A BEARISH synthetic (long put + short call) has
    // an uncovered short call — unlimited upside risk — so the builder will BLOCK placement and show
    // the cliff. Label honestly per direction: "place" only when it's actually placeable.
    var isShort = r.deltaExposureCents < 0;
    if (isShort) {
      out.appendChild(el('div', { class: 'muted small', style: 'margin-top:6px' },
        el('b', { class: 'loss' }, 'Advanced / undefined risk: '),
        'the short synthetic’s uncovered call has unlimited upside risk — the builder will block placing it and show why.'));
    }
    out.appendChild(el('div', { class: 'btn-row' },
      el('button', {
        class: 'btn btn-sm', id: 'replicate-build', onclick: function () {
          App.context.selectSymbol(r.symbol);
          App.state.builderForm = {
            symbol: (r.symbol || '').toUpperCase(),
            qty: r.contracts || 1, goal: 'DIRECTIONAL',
            templateKey: isShort ? 'SYNTHETIC_SHORT' : 'SYNTHETIC_LONG',
            step: 3, legIdx: 0, excluded: {}, legs: []
          };
          App.navigate('#/trade/shape');
        }
      }, isShort ? 'Build & inspect in the Trade builder' : 'Build & place in the Trade builder')));
  }

  // ---- Notebook ----
  async function notebookCard() {
    var card = el('div', { class: 'card tool-card research-notebook' });
    var newBtn = el('button', { class: 'btn btn-sm', id: 'research-note-new' }, 'New note');
    card.appendChild(toolHeader('pen', 'Your research notes', newBtn));
    var list = el('div', { id: 'research-notes', class: 'tool-output' });
    card.appendChild(list);

    async function reload() {
      list.innerHTML = ''; list.appendChild(UI.spinner('Loading notes…'));
      try {
        var notes = (await API.get('/api/research/notes')).notes || [];
        list.innerHTML = '';
        if (!notes.length) list.appendChild(el('div', { class: 'muted small' }, 'No notes yet — save your hypotheses and conclusions here.'));
        notes.forEach(function (n) { list.appendChild(noteRow(n, reload)); });
      } catch (e) { list.innerHTML = ''; list.appendChild(alertBox('danger', 'Could not load notes', [String((e && e.message) || e)])); }
    }
    newBtn.addEventListener('click', function () {
      list.insertBefore(noteEditor(null, reload), list.firstChild);
    });
    await reload();
    return card;
  }

  function noteRow(n, reload) {
    return UI.expandable(
      el('span', {}, el('b', {}, n.title), '  ', el('span', { class: 'muted small' }, (n.updatedAt || '').slice(0, 10) + (n.tags ? '  · ' + n.tags : ''))),
      function () { return noteEditor(n, reload); });
  }

  function noteEditor(n, reload) {
    var title = el('input', { type: 'text', placeholder: 'Title', value: (n && n.title) || '' });
    var body = el('textarea', { rows: '4', placeholder: 'Your analysis…' }, (n && n.body) || '');
    var tags = el('input', { type: 'text', placeholder: 'tags (comma-separated)', value: (n && n.tags) || '' });
    var wrap = el('div', { class: 'note-editor' },
      toolField('Title', title), toolField('Notes', body), toolField('Tags', tags));
    var save = el('button', { class: 'btn btn-sm' }, 'Save');
    save.addEventListener('click', async function () {
      save.disabled = true;
      try {
        if (n && n.id) await API.put('/api/research/notes/' + n.id, { title: title.value, body: body.value, tags: tags.value });
        else await API.post('/api/research/notes', { title: title.value, body: body.value, tags: tags.value });
        await reload();
      } catch (e) { save.disabled = false; wrap.appendChild(alertBox('danger', String((e && e.message) || e))); }
    });
    var row = el('div', { class: 'btn-row' }, save);
    if (n && n.id) {
      var del = el('button', { class: 'btn btn-sm btn-danger' }, 'Delete');
      del.addEventListener('click', async function () {
        del.disabled = true;
        try { await API.del('/api/research/notes/' + n.id); await reload(); }
        catch (e) { del.disabled = false; wrap.appendChild(alertBox('danger', 'Could not delete', [String((e && e.message) || e)])); }
      });
      row.appendChild(del);
    }
    wrap.appendChild(row);
    return wrap;
  }



  /** Saved notes, rendered inside Research where studying lives. */
  async function studyToolsSection() {
    // The landing page is a MARKET-ENTRY surface: sectors, symbols, recents, saved work.
    // The event-study workbench lives on the SYMBOL page, inside the thesis workflow —
    // the full event study belongs on a symbol page, not on the market index.
    var wrap = el('div', { id: 'research-study-tools' });
    var grid = el('div', { class: 'tool-grid' });
    grid.appendChild(await notebookCard());
    wrap.appendChild(grid);
    return wrap;
  }

  window.Views = {
    home: home,
    research: research,
    trade: trade,
    portfolio: portfolio,
    status: status,
    decision: decision
  };
})();
