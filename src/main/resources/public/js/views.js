/* StrikeBench views: one render function per screen. Attached to window, jsdom-friendly. */
(function () {
  'use strict';

  var el = UI.el, fmtMoney = UI.fmtMoney, pnlSpan = UI.pnlSpan, fmtPct = UI.fmtPct,
      fmtNum = UI.fmtNum, badge = UI.freshnessBadge, explain = UI.explain,
      alertBox = UI.alertBox, stat = UI.stat, table = UI.table, chip = UI.chip;

  var CORE_SYMBOLS = ['AAPL', 'SPY', 'QQQ', 'TSLA'];

  var icon = UI.icon;

  /**
   * The opening page: what this is, who it's for, and three one-click ways in — shown to
   * fresh accounts (and always at #/welcome). The competition leads with a landing page;
   * ours leads with a landing page wired to LIVE local data (the tape above it already ticks).
   */
  async function welcome(root) {
    var brand = (App.state.brand && App.state.brand.name) || 'StrikeBench';

    // ---- Hero: one promise, two doors, quiet proof ----
    var stepsAnchorId = 'how-it-works';
    root.appendChild(el('section', { class: 'hero', id: 'welcome-hero' },
      el('div', { class: 'hero-inner' },
        el('div', { class: 'eyebrow' }, 'PAPER TRADING \u00B7 LOCAL-FIRST \u00B7 FREE'),
        el('h1', { class: 'hero-title' }, 'Learn options by ', el('span', { class: 'grad' }, 'doing'), ',', el('br', {}),
          'with honest numbers.'),
        el('p', { class: 'hero-sub' },
          'Screen real strategies against your goal, practice them with a $100,000 paper account, and backtest without look-ahead \u2014 entirely on your machine.'),
        el('div', { class: 'hero-ctas' },
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
          }, 'See how it works')),
        el('div', { class: 'trust-row' },
          ['No signup', 'No cloud', 'Fills at real bid/ask', 'Undefined risk blocked by default']
            .map(function (t, i) {
              return el('span', {}, i ? el('span', { class: 'dot-sep' }, '\u00B7') : null, t);
            })))));

    // ---- Three doors, by experience ----
    var LEVEL_CARDS = [
      { level: 'beginner', title: 'Teach me', ic: 'sprout',
        blurb: 'Question-driven flows, plain language, tap-to-define terms \u2014 and every idea explains how it wins and loses.',
        cta: 'Start as a beginner', go: '#/recommend/manual' },
      { level: 'expert', title: 'Give me the terminal', ic: 'bolt',
        blurb: 'Dense tables, position greeks, multi-leg construction with per-leg impact, inline filters.',
        cta: 'Open the terminal', go: '#/research/AAPL' }
    ];
    root.appendChild(section('CHOOSE YOUR ALTITUDE', 'How do you want to start?',
      el('div', { class: 'welcome-grid', id: 'welcome-levels' }, LEVEL_CARDS.map(function (c) {
        return el('div', { class: 'card welcome-card' },
          el('div', { class: 'icon-tile' }, icon(c.ic)),
          el('h3', {}, c.title),
          el('p', {}, c.blurb),
          el('button', {
            class: 'btn', 'data-welcome-level': c.level, onclick: function () {
              Learn.setLevel(c.level);
              try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
              App.navigate(c.go);
            }
          }, c.cta));
      }))));

    // ---- Live proof: the engine, thinking, right now ----
    var liveHost = el('div', { class: 'showcase-frame', id: 'welcome-live' },
      el('div', { class: 'showcase-loading' }, UI.spinner('Asking the engine for a live example\u2026')));
    root.appendChild(section('LIVE FROM THE ENGINE', 'See it think',
      el('div', { class: 'showcase' },
        liveHost,
        el('p', { class: 'showcase-caption' },
          'A real screened idea \u2014 generated this second for a bullish month on AAPL at the conservative risk budget. Refusals, odds, and assumptions included, like every idea here.'))));
    (async function () {
      try {
        var r = await API.post('/api/recommend', { symbol: 'AAPL', thesis: 'bullish', horizon: 'month', riskMode: 'conservative' });
        liveHost.innerHTML = '';
        if (r.candidates && r.candidates.length) {
          liveHost.appendChild(candidateCard(r.candidates[0], false));
        }
      } catch (e) {
        liveHost.innerHTML = '';
        liveHost.appendChild(el('p', { class: 'muted', style: 'padding:16px' }, 'The engine needs the server running to demo itself.'));
      }
    })();

    // ---- What lives here ----
    var CAPS = [
      { ic: 'target', t: 'Goal-first ideas', d: 'Trade a view, earn income, buy at a discount, sell at your target, or protect what you hold.' },
      { ic: 'flask', t: 'Real mechanics', d: 'Executable fills, contracts die at 4pm ET, covered calls lock shares, assignment delivers them.' },
      { ic: 'chart', t: 'Honest backtests', d: 'Day-by-day replay, no look-ahead, and reports that label how much is modeled.' },
      { ic: 'scope', t: 'A market scout', d: 'Momentum, news sentiment, and IV-vs-realized signals \u2014 with their evidence attached.' }
    ];
    root.appendChild(section('THE TOOLKIT', 'What you can do here',
      el('div', { class: 'welcome-grid caps-grid' }, CAPS.map(function (c) {
        return el('div', { class: 'card welcome-card' },
          el('div', { class: 'icon-tile' }, icon(c.ic)),
          el('h3', {}, c.t),
          el('p', {}, c.d));
      }))));

    // ---- How it works: a real stepper, not a text pile ----
    var STEPS = [
      ['Pick a goal', 'or a ticker you care about'],
      ['Screen ideas', 'refusals explained, risk capped'],
      ['Practice the trade', 'with the $100k paper account'],
      ['Review honestly', 'marks, P/L, what changed']
    ];
    var stepper = el('div', { class: 'stepper' }, STEPS.map(function (s, i) {
      return el('div', { class: 'step-block' },
        el('div', { class: 'step-num' }, '0' + (i + 1)),
        el('div', { class: 'step-text' }, el('b', {}, s[0]), el('span', {}, s[1])));
    }));
    var how = section('HOW IT WORKS', 'Four steps, no account required', stepper);
    how.id = stepsAnchorId;
    root.appendChild(how);

    // ---- One banner, one action ----
    root.appendChild(el('div', { class: 'cta-banner' },
      el('div', {},
        el('b', {}, 'Thirteen sector universes, one flick away.'),
        el('p', { class: 'muted' }, 'Tech, semiconductors, healthcare, defense, staples and more \u2014 feeding the ticker, the scout, and every symbol box.')),
      el('button', { class: 'btn', onclick: function () { App.navigate('#/research'); } }, 'Open the sector explorer')));

    root.appendChild(el('div', { class: 'welcome-exit' },
      el('button', { class: 'btn btn-ghost', id: 'welcome-skip', onclick: function () {
        try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
        App.navigate('#/home');
      } }, 'Skip \u2014 take me to the dashboard')));

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

  async function home(root) {
    // Paint the frame FIRST, fill every card as its data arrives: the screen must never
    // be a blank void while accounts and quotes load. Each section fills independently
    // and fails visibly (empty state), never silently.
    root.appendChild(el('h1', {}, 'Overview'));
    var statsAnchor = el('div', { class: 'grid grid-4', id: 'home-stats' });
    root.appendChild(statsAnchor);

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

    // Sector pulse: one ETF proxy per sector, day change at a glance, click to dig in
    var SECTOR_ETF = { TECH: 'XLK', SEMICONDUCTORS: 'SMH', HEALTHCARE: 'XLV', DEFENSE: 'ITA',
      STAPLES: 'XLP', DISCRETIONARY: 'XLY', ENERGY: 'XLE', FINANCIALS: 'XLF', INDUSTRIALS: 'XLI',
      COMMUNICATIONS: 'XLC', UTILITIES: 'XLU', CORE: 'SPY', ETFS: 'QQQ', DEMO: 'SPY' };
    (async function sectorPulse() {
      try {
        var uniData = App.state.universe;
        if (!uniData) return;
        var pairs = uniData.sectors
          .filter(function (s) { return SECTOR_ETF[s.key]; })
          .map(function (s) { return { sector: s, etf: SECTOR_ETF[s.key] }; });
        var etfs = pairs.map(function (p) { return p.etf; }).filter(function (v, i, a) { return a.indexOf(v) === i; });
        var data = await API.get('/api/quotes?symbols=' + etfs.join(','));
        var byEtf = {};
        (data.quotes || []).forEach(function (q) { byEtf[q.symbol] = q; });
        var chips = [];
        var seen = {};
        pairs.forEach(function (p) {
          var q = byEtf[p.etf];
          if (!q || seen[p.sector.key]) return;
          seen[p.sector.key] = true;
          var pct = q.prevClose ? (parseFloat(q.last) - parseFloat(q.prevClose)) / parseFloat(q.prevClose) * 100 : 0;
          chips.push(el('button', {
            class: 'sector-chip', onclick: function () {
              App.state.explorerSector = p.sector.key;
              App.navigate('#/research');
            }
          }, p.sector.label + ' ', el('b', { class: pct >= 0 ? 'gain' : 'loss' },
            (pct >= 0 ? '\u25B2' : '\u25BC') + Math.abs(pct).toFixed(2) + '%')));
        });
        if (chips.length) {
          var pulse = el('div', { class: 'card', id: 'sector-pulse' },
            UI.cardHeader('Sector pulse',
              el('a', { href: '#/research', class: 'muted', style: 'font-size:12.5px' }, 'Explore sectors \u2192')),
            el('div', { class: 'sector-chips' }, chips),
            explain('Day change of each sector\u2019s ETF proxy. Tap a sector to open its symbols in the explorer.'));
          var anchor = document.getElementById('sector-pulse-anchor');
          if (anchor) anchor.replaceWith(pulse);
        }
      } catch (e) { /* pulse is decorative */ }
    })();
    var colL = el('div', { class: 'home-col' });
    var colR = el('div', { class: 'home-col' });
    root.appendChild(el('div', { class: 'home-cols' }, colL, colR));
    colR.appendChild(el('div', { id: 'sector-pulse-anchor' }));

    // Markets: shell now, tiles when the ONE batch-quotes call answers (this used to be
    // eight sequential full-research calls that blocked the whole dashboard).
    var uni = App.state.universe && App.state.universe.active;
    // Four tiles keeps the dashboard on one desktop screen; the sector explorer has the rest
    var marketSymbols = uni && uni.symbols && uni.symbols.length ? uni.symbols.slice(0, 4) : CORE_SYMBOLS;
    var tiles = el('div', { class: 'tile-row' });
    colL.appendChild(el('div', { class: 'card' },
      UI.cardHeader('Markets' + (uni ? ' — ' + uni.label : ''),
        el('a', { href: '#/welcome', class: 'muted', style: 'font-size:12.5px' }, 'Welcome tour')),
      tiles,
      explain('Tap a symbol to research it: chart, option chain, expected vs realized volatility, news.')));
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
      rows.forEach(function (q) {
        tiles.appendChild(el('div', {
          class: 'tile', onclick: function () { App.navigate('#/research/' + q.symbol); }
        },
          el('div', { class: 't-sym' }, q.symbol, ' ', badge(q.freshness)),
          el('div', { class: 't-px' }, fmtNum(q.last)),
          UI.delta(q.last, q.prevClose),
          el('div', { class: 't-nm' }, q.description || '')));
      });
    })();

    // Open positions: shell + async fill, same pattern
    var posCard = el('div', { class: 'card' }, UI.cardHeader('Open practice trades',
      el('a', { href: '#/portfolio' }, 'All trades \u2192')));
    colL.appendChild(posCard);
    var tradesFill = (async function fillTrades() {
      try {
        var open = await API.get('/api/trades?status=ACTIVE&size=5');
        if (!open.trades.length) {
          posCard.appendChild(UI.emptyState('No open trades yet',
            'Get risk-screened ideas for a symbol, then walk the guided ticket.',
            'Find trade ideas', function () { App.navigate('#/recommend'); }));
        } else {
          posCard.appendChild(table(['Symbol', 'Strategy', 'Qty', 'Max loss', 'Opened'],
            open.trades.map(function (t) {
              return el('tr', { class: 'clickable', onclick: function () { App.navigate('#/trade/' + t.id); } },
                el('td', {}, el('b', {}, t.symbol)),
                el('td', {}, prettyStrategy(t.strategy)),
                el('td', {}, 'x' + t.qty),
                el('td', { class: 'loss' }, fmtMoney(t.maxLossCents)),
                el('td', { class: 'muted' }, (t.createdAt || '').slice(0, 10)));
            })));
        }
      } catch (e) {
        posCard.appendChild(alertBox('warn', 'Could not load open trades', [e.message]));
      }
    })();

    colR.appendChild(el('div', { class: 'home-actions' },
      quickAction('Research a symbol', 'Quotes, chains, IV vs HV, history, news.', '#/research'),
      quickAction('Scout opportunities', 'Auto-derived views from momentum, sentiment, and volatility.', '#/recommend'),
      quickAction('Build a strategy', 'Multi-leg construction with live risk and per-leg impact.', '#/ticket/builder'),
      quickAction('Backtest a strategy', 'How a rule would have behaved — honestly labeled.', '#/backtest')));

    // Wait for the fills so data-ready means READY (tests and users agree on that).
    await Promise.all([marketsFill, tradesFill]);
  }

  function quickAction(title, hint, hash) {
    return el('div', { class: 'tile qa-tile', title: hint, onclick: function () { App.navigate(hash); } },
      el('div', { class: 't-sym' }, title),
      el('div', { class: 'muted qa-hint', style: 'margin-top:4px' }, hint));
  }

  // ---------- 2. Research ----------

  async function research(root, params) {
    var symbol = (params[0] || '').toUpperCase();
    var input = el('input', { type: 'text', id: 'symbol-input', placeholder: 'Ticker, e.g. AAPL', value: symbol });
    var go = function () { if (input.value.trim()) App.navigate('#/research/' + input.value.trim().toUpperCase()); };
    input.addEventListener('keydown', function (e) { if (e.key === 'Enter') go(); });

    root.appendChild(el('h1', {}, 'Research'));
    root.appendChild(el('div', { class: 'card' },
      el('div', { class: 'btn-row', style: 'margin-top:0' },
        input, el('button', { class: 'btn', id: 'symbol-go', onclick: go }, 'Look up'),
        el('span', { class: 'spacer' }),
        el('span', { class: 'sym-chips' },
          ((App.state.universe && App.state.universe.active.symbols) || CORE_SYMBOLS).slice(0, 6).map(function (s) {
            return el('button', { class: 'sym-chip', onclick: function () { App.navigate('#/research/' + s); } }, s);
          })))));

    if (!symbol) {
      await sectorExplorer(root, 'research');
      return;
    }

    var r;
    try { r = await API.get('/api/research/' + symbol); }
    catch (e) {
      root.appendChild(alertBox('danger', 'No data for ' + symbol + '. Check the ticker.'));
      return;
    }
    var q = r.quote;

    var hero = el('div', { class: 'card' },
      el('div', { class: 'quote-hero' },
        el('span', { class: 'sym', id: 'research-symbol' }, symbol),
        el('span', { class: 'px' }, fmtNum(q.last)),
        UI.delta(q.last, q.prevClose),
        badge(r.freshness),
        el('span', { class: 'spacer' }),
        el('button', {
          class: 'btn btn-sm btn-secondary', id: 'research-buy-shares',
          onclick: function () { stockOrderModal('buy', symbol); }
        }, 'Buy shares'),
        r.optionable ? el('button', {
          class: 'btn btn-sm', onclick: function () {
            App.state.lastRecommendSymbol = symbol;
            App.navigate('#/recommend/manual');
          }
        }, 'Get ideas for ' + symbol) : null),
      el('div', { class: 'nm', style: 'margin-top:2px' }, q.description || ''),
      el('div', { class: 'chip-row' },
        chip('Bid/Ask', fmtNum(q.bid) + ' / ' + fmtNum(q.ask)),
        chip('Prev close', fmtNum(q.prevClose)),
        chip('Day', fmtNum(q.dayLow) + ' – ' + fmtNum(q.dayHigh)),
        chip('IV (ATM)', fmtPct(r.ivAtm)),
        chip('HV 30d', fmtPct(r.hv30) + (r.historyDemo ? ' (demo)' : '')),
        chip('Options', r.optionable ? 'yes' : 'no')),
      explain('IV is the move the options market is pricing in; HV is what the stock actually did lately. IV far above HV → options are relatively expensive to buy (and richer to sell).'),
      (r.benchmarks && r.benchmarks.length) ? el('div', { class: 'chip-row' }, r.benchmarks.map(function (b) {
        return chip(b.symbol, fmtNum(b.last));
      })) : null);
    root.appendChild(hero);

    if (!r.optionable) {
      root.appendChild(alertBox('warn', symbol + ' has no listed options (mutual funds and some securities cannot be option-traded). You can still study its price history below.'));
    }

    // What you can do with this symbol — each GOAL gets its own live one-liner, computed
    // from real ladder rungs (not marketing copy), with one-tap handoff into that flow.
    if (r.optionable) {
      var actionsAnchor = el('div', { id: 'symbol-actions-anchor' });
      root.appendChild(actionsAnchor);
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
                  App.state.ideasPrefill = { intent: intentKey, symbol: symbol };
                  App.navigate('#/recommend/manual');
                }
              }, cta));
          }
          var acq = await rung('acquire');
          if (acq && !held) {
            var kA = parseFloat(acq.legs[0].strike);
            cards.push(actionCard('tag', 'Own it cheaper',
              el('span', {}, 'Get paid ', el('b', { class: 'gain' }, fmtMoney(acq.entryNetPremiumCents)),
                ' now to buy at $' + kA + ' \u2014 effectively $' + acq.effectivePrice + '/sh.'),
              'ACQUIRE', 'Name your price'));
          }
          if (held && held.freeShares >= 100) {
            var ex = await rung('exit');
            if (ex) {
              cards.push(actionCard('flag', 'Sell your ' + held.shares + ' shares higher',
                el('span', {}, 'Get paid ', el('b', { class: 'gain' }, fmtMoney(ex.entryNetPremiumCents)),
                  ' now to sell at $' + parseFloat(ex.legs[0].strike) + ' (effectively $' + ex.effectivePrice + '/sh).'),
                'EXIT', 'Pick your exit'));
            }
            var hg = await rung('hedge');
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
        } catch (e) { /* the section is additive */ }
      })();
    }

    // Price history: range pills (1M…MAX) + slide-to-read crosshair, like any finance site —
    // but with the honesty badge when the data is demo.
    root.appendChild(el('div', { class: 'card', id: 'history-card' },
      UI.cardHeader('Price history'),
      UI.rangeChart({
        initial: '1y',
        fetch: async function (range) {
          var hist = await API.get('/api/research/' + symbol + '/history?range=' + range);
          var series = (hist.candles || []).map(function (c) { return { date: c.date, value: parseFloat(c.close) }; });
          return {
            range: hist.range,
            series: series,
            badge: hist.demo ? el('span', { class: 'badge badge-warn' }, 'DEMO DATA') : null,
            note: hist.demo ? explain('This price history is built-in DEMO DATA — no live candle source is configured. Add a Polygon or Alpha Vantage key for real history.') : null
          };
        }
      }),
      explain('Slide across the chart to read the exact date, close, and change for that point. Pills change the window; MAX shows everything the data source has.')));

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
      root.appendChild(chainCard);

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
          return el('tr', { class: isAtm ? 'atm' : '' },
            el('td', { class: k < spot ? 'itm' : '' }, c.bid !== undefined ? fmtNum(c.bid) + ' / ' + fmtNum(c.ask) : '—'),
            el('td', { class: k < spot ? 'itm' : '' }, c.delta !== undefined && c.delta !== null ? fmtNum(c.delta) : '—'),
            el('td', { class: k < spot ? 'itm' : '' }, c.iv ? fmtPct(c.iv) : '—'),
            el('td', {}, el('b', {}, stripZeros(k))),
            el('td', { class: k > spot ? 'itm' : '' }, p.iv ? fmtPct(p.iv) : '—'),
            el('td', { class: k > spot ? 'itm' : '' }, p.delta !== undefined && p.delta !== null ? fmtNum(p.delta) : '—'),
            el('td', { class: k > spot ? 'itm' : '' }, p.bid !== undefined ? fmtNum(p.bid) + ' / ' + fmtNum(p.ask) : '—'));
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
                   : ['Call bid/ask', 'Call Δ', 'Call IV', 'Strike', 'Put IV', 'Put Δ', 'Put bid/ask'], rows));
      };
      loadChain(r.expirations[0]);
    }

    try {
      var news = await API.get('/api/research/' + symbol + '/news');
      if (news.items && news.items.length) {
        root.appendChild(el('div', { class: 'card' },
          UI.cardHeader('News & filings'),
          news.items.slice(0, 8).map(function (n) {
            return el('div', { class: 'status-item' },
              el('a', { href: n.url, target: '_blank', rel: 'noopener' }, n.headline),
              el('span', { class: 'spacer' }),
              el('span', { class: 'muted' }, n.source));
          })));
      }
    } catch (e) { /* optional */ }
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

    var chipRow = el('div', { class: 'sector-chips', id: 'sector-chips' });
    var grid = el('div', { class: 'tile-row', id: 'sector-grid' });
    var head = el('div', { class: 'btn-row', style: 'margin-top:0' });
    var card = el('div', { class: 'card', id: 'sector-explorer' },
      UI.cardHeader('Explore by sector'), chipRow, head, grid,
      explain('Live quotes per sector. Tap a symbol to research it, or point the scout at a whole sector. '
        + (u.note ? u.note : '')));
    root.appendChild(card);

    function renderChips() {
      chipRow.innerHTML = '';
      u.sectors.forEach(function (s) {
        if (s.key === 'DEMO' && !u.note) return; // demo sector only matters in demo mode
        chipRow.appendChild(el('button', {
          class: 'sector-chip' + (s.key === selectedKey ? ' active' : ''), 'data-sector': s.key,
          onclick: function () { selectedKey = s.key; App.state.explorerSector = s.key; load(); }
        }, s.label));
      });
    }

    async function load() {
      renderChips();
      head.innerHTML = '';
      grid.innerHTML = '';
      grid.appendChild(UI.spinner('Loading sector quotes…'));
      var sector = u.sectors.find(function (s) { return s.key === selectedKey; }) || u.sectors[0];
      head.appendChild(el('b', {}, sector.label));
      head.appendChild(el('span', { class: 'muted' }, sector.symbols.length + ' symbols'));
      head.appendChild(el('span', { class: 'spacer' }));
      if (u.active.sectorKey !== sector.key) {
        head.appendChild(el('button', {
          class: 'btn btn-sm btn-secondary', id: 'set-universe-btn', onclick: async function () {
            try {
              await API.put('/api/universe', { sector: sector.key });
              await App.refreshUniverse();
              App.render();
            } catch (e) { alert(e.message); }
          }
        }, 'Make this my universe'));
      } else {
        head.appendChild(el('span', { class: 'badge badge-ok' }, 'YOUR UNIVERSE'));
      }
      head.appendChild(el('button', {
        class: 'btn btn-sm', onclick: function () {
          App.state.scoutForm = Object.assign({}, App.state.scoutForm || {}, { universe: sector.symbols.join(',') });
          App.navigate('#/recommend/scout');
        }
      }, 'Scout this sector'));
      try {
        var data = await API.get('/api/quotes?symbols=' + sector.symbols.join(','));
        grid.innerHTML = '';
        // EVERY symbol gets an actionable tile \u2014 a sector click must always land somewhere
        // clickable. Symbols without a live quote say so honestly but still link onward.
        var bySym = {};
        (data.quotes || []).forEach(function (q) { bySym[q.symbol] = q; });
        sector.symbols.forEach(function (s) {
          var q = bySym[s];
          var last = q ? parseFloat(q.last) : null;
          var prev = q ? parseFloat(q.prevClose) : null;
          var pct = q && prev ? (last - prev) / prev * 100 : null;
          grid.appendChild(el('div', { class: 'tile sector-tile' + (q ? '' : ' tile-nodata') },
            el('div', { class: 't-sym' }, s,
              q && !q.optionable ? el('span', { class: 'badge badge-dim', style: 'margin-left:6px' }, 'NO OPTIONS') : null,
              q ? null : el('span', { class: 'badge badge-dim', style: 'margin-left:6px' }, 'NO LIVE DATA')),
            el('div', { class: 't-px' }, q ? last.toFixed(2) : '\u2014'),
            pct === null
              ? el('div', { class: 'muted' }, 'no quote right now')
              : el('div', { class: pct >= 0 ? 'gain' : 'loss' }, (pct >= 0 ? '\u25B2 ' : '\u25BC ') + Math.abs(pct).toFixed(2) + '%'),
            el('div', { class: 'btn-row', style: 'margin-top:8px' },
              el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research/' + s); } }, 'Research'),
              (!q || q.optionable) ? el('button', { class: 'btn btn-sm btn-secondary', onclick: function () {
                App.state.ideasPrefill = { symbol: s };
                App.navigate('#/recommend/manual');
              } }, 'Ideas') : null)));
        });
        if (!(data.quotes || []).length && u.note) {
          grid.appendChild(explain('Demo mode carries data only for the built-in symbols \u2014 each ticker still links to research and ideas so you can explore the flow.'));
        }
      } catch (e) {
        grid.innerHTML = '';
        grid.appendChild(alertBox('warn', 'Could not load sector quotes', [e.message]));
      }
    }
    await load();
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

  /** Learning-level card: plain language first, numbers second, mechanics on tap. */
  function beginnerCandidateCard(c, withUse, symbolForTicket) {
    var g = Learn.STRATEGY_GUIDE[c.strategy] || {};
    var assignGoal = c.intent === 'EXIT' || c.intent === 'ACQUIRE';
    var maxLossFact = c.usesHeldShares && c.maxLossCents === 0
        ? el('div', { class: 'fact' },
            el('div', { class: 'f-label' }, UI.term('covered', 'New cash at risk')),
            el('div', { class: 'f-value' }, '$0'))
        : el('div', { class: 'fact f-danger' },
            el('div', { class: 'f-label' }, UI.term('max loss', 'Most you can lose')),
            el('div', { class: 'f-value' }, fmtMoney(c.maxLossCents)));
    var thirdFact = assignGoal && c.assignmentProb !== null && c.assignmentProb !== undefined
        ? el('div', { class: 'fact f-ok' },
            el('div', { class: 'f-label' }, UI.term('assignment', c.intent === 'EXIT' ? 'Chance you sell' : 'Chance you buy')),
            el('div', { class: 'f-value' }, fmtPct(c.assignmentProb)))
        : el('div', { class: 'fact' },
            el('div', { class: 'f-label' }, UI.term('pop', 'Chance of any profit')),
            el('div', { class: 'f-value' }, fmtPct(c.pop)));
    var card = el('div', { class: 'candidate' },
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
          el('div', { class: 'f-label' }, UI.term('max profit', 'Most you can make')),
          el('div', { class: 'f-value' }, c.maxProfitCents === null || c.maxProfitCents === undefined
              ? 'no ceiling' : fmtMoney(c.maxProfitCents))),
        thirdFact),
      el('p', { style: 'margin:6px 0' },
        c.entryNetPremiumCents >= 0
          ? el('span', {}, 'You collect ', el('b', { class: 'gain' }, fmtMoney(c.entryNetPremiumCents)), ' up front (a ', UI.term('credit'), ').')
          : el('span', {}, 'You pay ', el('b', {}, fmtMoney(-c.entryNetPremiumCents)), ' up front (a ', UI.term('debit'), ').'),
        (c.breakevens || []).length
          ? el('span', {}, ' The ', UI.term('breakeven'), ' is at ', el('b', {}, c.breakevens.map(fmtBreakeven).join(' / ')), '.')
          : null));
    var gb = guideBlock(c.strategy);
    if (gb) card.appendChild(gb);
    card.appendChild(UI.expandable('The exact contracts (' + c.qty + 'x)', function () {
      return el('div', {},
        el('div', { class: 'mono', style: 'margin-bottom:6px' }, c.label),
        el('ul', {}, c.legs.map(function (l) { return el('li', {}, legLabel(l)); })),
        el('p', {}, 'Prices shown are the executable ', UI.term('bid/ask', 'bid/ask'), ' sides — what a fill would actually cost right now.'));
    }));
    if (c.warnings && c.warnings.length) card.appendChild(alertBox('warn', 'Before you decide', c.warnings));
    card.appendChild(el('div', { class: 'btn-row' },
      withUse ? el('button', {
        class: 'btn', onclick: function () {
          App.state.ticket = { candidate: c, symbol: symbolForTicket || App.state.lastRecommendSymbol, step: 5 };
          App.navigate('#/ticket');
        }
      }, 'Practice this trade') : null));
    return card;
  }

  function candidateCard(c, withUse, symbolForTicket) {
    if (Learn.currentLevel() === 'beginner') return beginnerCandidateCard(c, withUse, symbolForTicket);
    var card = el('div', { class: 'candidate', 'data-strategy': c.strategy },
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
        chip('Max loss', el('span', { class: 'loss' }, fmtMoney(c.maxLossCents))),
        c.combinedMaxLossCents !== null && c.combinedMaxLossCents !== undefined
          ? chip('Worst case w/ shares', el('span', { class: 'loss' }, fmtMoney(c.combinedMaxLossCents))) : null,
        chip('Max profit', c.maxProfitCents === null || c.maxProfitCents === undefined
          ? 'uncapped' : el('span', { class: 'gain' }, fmtMoney(c.maxProfitCents))),
        chip('POP', fmtPct(c.pop)),
        c.assignmentProb !== null && c.assignmentProb !== undefined ? chip('Assignment', fmtPct(c.assignmentProb)) : null,
        c.annualizedYieldPct !== null && c.annualizedYieldPct !== undefined ? chip('Yield/yr', fmtNum(c.annualizedYieldPct, 1) + '%') : null,
        c.effectivePrice ? chip(c.intent === 'ACQUIRE' ? 'Effective buy' : 'Effective sell', '$' + c.effectivePrice) : null,
        chip('Breakeven', (c.breakevens || []).map(fmtBreakeven).join(' / ') || '—'),
        chip('Confidence', fmtPct(c.confidence))),
      explain(c.beginnerExplanation));
    card.appendChild(el('div', { class: 'chip-row expert-only' },
      c.expectedValueCents !== null && c.expectedValueCents !== undefined ? chip('Model EV', fmtMoney(c.expectedValueCents, { plus: true })) : null,
      chip('Liquidity', fmtNum(c.liquidityScore, 2)),
      chip('Score', fmtNum(c.score, 0))));
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
            App.state.ticket = { candidate: c, symbol: symbolForTicket || App.state.lastRecommendSymbol, step: 5 };
            App.navigate('#/ticket');
          }
        }, 'Use in trade ticket'),
        BACKTESTABLE.indexOf(c.strategy) >= 0 ? el('button', {
          class: 'btn btn-sm btn-secondary', onclick: function () {
            App.state.backtestPrefill = { symbol: symbolForTicket || App.state.lastRecommendSymbol, strategy: c.strategy };
            App.navigate('#/backtest');
          }
        }, 'Backtest this') : null));
    }
    return card;
  }

  /** Families the day-by-day backtester can replay (single-expiration; no calendars/custom). */
  var BACKTESTABLE = ['LONG_CALL', 'LONG_PUT', 'DEBIT_CALL_SPREAD', 'DEBIT_PUT_SPREAD',
    'LONG_CALL_BUTTERFLY', 'LONG_PUT_BUTTERFLY', 'CREDIT_CALL_SPREAD', 'CREDIT_PUT_SPREAD',
    'IRON_CONDOR', 'IRON_BUTTERFLY', 'COVERED_CALL', 'CASH_SECURED_PUT',
    'PROTECTIVE_PUT', 'PROTECTIVE_COLLAR'];

  var THESIS_BADGE = { BULLISH: 'badge-ok', BEARISH: 'badge-danger', NEUTRAL: 'badge-dim', VOLATILE: 'badge-warn' };

  /** Pro: side-by-side strategy comparison — sortable columns, expandable detail rows. */
  function comparisonTable(candidates) {
    var sortKey = 'score', sortDir = -1;
    var COLS = [
      { key: 'displayName', label: 'Strategy', get: function (c) { return c.displayName; }, render: function (c) { return el('b', {}, c.displayName); } },
      { key: 'entryNetPremiumCents', label: 'Cost/Credit', get: function (c) { return c.entryNetPremiumCents; }, render: function (c) { return pnlSpan(c.entryNetPremiumCents); } },
      { key: 'maxLossCents', label: 'Max loss', get: function (c) { return c.usesHeldShares && c.combinedMaxLossCents ? c.combinedMaxLossCents : c.maxLossCents; }, render: function (c) { return c.usesHeldShares && c.maxLossCents === 0 ? el('span', {}, '$0*') : el('span', { class: 'loss' }, fmtMoney(c.maxLossCents)); } },
      { key: 'maxProfitCents', label: 'Max profit', get: function (c) { return c.maxProfitCents === null || c.maxProfitCents === undefined ? Infinity : c.maxProfitCents; }, render: function (c) { return c.maxProfitCents === null || c.maxProfitCents === undefined ? el('span', { class: 'gain' }, '\u221E') : el('span', { class: 'gain' }, fmtMoney(c.maxProfitCents)); } },
      { key: 'rr', label: 'R:R', get: function (c) { var denom = c.maxLossCents > 0 ? c.maxLossCents : (c.combinedMaxLossCents || 0); if (denom <= 0) return -1; return c.maxProfitCents === null || c.maxProfitCents === undefined ? Infinity : c.maxProfitCents / denom; }, render: function (c) { var v = COLS[4].get(c); return el('span', {}, v === -1 ? '\u2014' : v === Infinity ? '\u221E' : fmtNum(v, 2)); } },
      { key: 'pop', label: 'POP', get: function (c) { return c.pop === null || c.pop === undefined ? -1 : c.pop; }, render: function (c) { return el('span', {}, fmtPct(c.pop)); } },
      { key: 'expectedValueCents', label: 'EV', get: function (c) { return c.expectedValueCents === null || c.expectedValueCents === undefined ? -Infinity : c.expectedValueCents; }, render: function (c) { return c.expectedValueCents === null || c.expectedValueCents === undefined ? el('span', {}, '\u2014') : pnlSpan(c.expectedValueCents); } },
      { key: 'breakevens', label: 'Breakevens', get: function (c) { return (c.breakevens || []).length ? parseFloat(c.breakevens[0]) : 0; }, render: function (c) { return el('span', { class: 'mono' }, (c.breakevens || []).map(fmtBreakeven).join(' / ') || '\u2014'); } },
      { key: 'assignmentProb', label: 'Assign%', get: function (c) { return c.assignmentProb === null || c.assignmentProb === undefined ? -1 : c.assignmentProb; }, render: function (c) { return el('span', {}, c.assignmentProb === null || c.assignmentProb === undefined ? '\u2014' : fmtPct(c.assignmentProb)); } },
      { key: 'annualizedYieldPct', label: 'Yield/yr', get: function (c) { return c.annualizedYieldPct === null || c.annualizedYieldPct === undefined ? -1 : c.annualizedYieldPct; }, render: function (c) { return el('span', {}, c.annualizedYieldPct === null || c.annualizedYieldPct === undefined ? '\u2014' : fmtNum(c.annualizedYieldPct, 1) + '%'); } },
      { key: 'liquidityScore', label: 'Liq', get: function (c) { return c.liquidityScore; }, render: function (c) { return el('span', {}, fmtNum(c.liquidityScore, 2)); } },
      { key: 'score', label: 'Score', get: function (c) { return c.score; }, render: function (c) { return el('b', {}, fmtNum(c.score, 0)); } }
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
        return el('th', {
          style: 'cursor:pointer; white-space:nowrap',
          onclick: function () {
            if (sortKey === col.key) sortDir = -sortDir; else { sortKey = col.key; sortDir = -1; }
            render();
          }
        }, col.label + (sortKey === col.key ? (sortDir < 0 ? ' \u2193' : ' \u2191') : ''));
      }).concat([el('th', {}, '')]));
      var body = el('tbody', {});
      sorted.forEach(function (c) {
        var detailRow = el('tr', { class: 'compare-detail', style: 'display:none' },
          el('td', { colspan: String(COLS.length + 1) }));
        var row = el('tr', {
          class: 'clickable', onclick: function () {
            var open = detailRow.style.display !== 'none';
            detailRow.style.display = open ? 'none' : '';
            if (!open && !detailRow.firstChild.firstChild) {
              detailRow.firstChild.appendChild(candidateCard(c, true));
            }
          }
        }, COLS.map(function (col) { return el('td', {}, col.render(c)); }).concat([
          el('td', {}, el('button', {
            class: 'btn btn-sm', onclick: function (e) {
              e.stopPropagation();
              App.state.ticket = { candidate: c, symbol: App.state.lastRecommendSymbol, step: 5 };
              App.navigate('#/ticket');
            }
          }, 'Use'))]));
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

  async function recommend(root, params) {
    var tab = params && params[0] === 'manual' ? 'manual' : 'scout';
    root.appendChild(el('h1', {}, 'Trade ideas'));
    root.appendChild(el('p', { class: 'page-sub' }, 'Risk-screened educational candidates — sized to your risk budget, never advice.'));
    root.appendChild(el('div', { class: 'tabs' },
      el('button', { class: tab === 'scout' ? 'active' : '', id: 'tab-scout', onclick: function () { App.navigate('#/recommend/scout'); } }, 'Scout for me'),
      el('button', { class: tab === 'manual' ? 'active' : '', id: 'tab-manual', onclick: function () { App.navigate('#/recommend/manual'); } }, 'My own idea')));
    if (tab === 'scout') renderScout(root); else renderManual(root);
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
   * Learning gets two plain-language limits behind a friendly expandable; Confident gets the
   * full five behind an expandable; Pro gets all five inline — no clicks, maximum density.
   * Returns {node, value()} where value() is the engine Filters object (fractions for
   * probabilities) or null when everything is blank.
   */
  function filterPanel(idPrefix) {
    var level = Learn.currentLevel();
    var minPop = el('input', { type: 'number', id: idPrefix + '-f-pop', min: '0', max: '100', step: '5', placeholder: 'any' });
    var maxAssign = el('input', { type: 'number', id: idPrefix + '-f-assign', min: '0', max: '100', step: '5', placeholder: 'any' });
    var minYield = el('input', { type: 'number', id: idPrefix + '-f-yield', min: '0', step: '1', placeholder: 'any' });
    var maxCost = el('input', { type: 'number', id: idPrefix + '-f-cost', min: '0', step: '50', placeholder: 'any' });
    var maxLoss = el('input', { type: 'number', id: idPrefix + '-f-maxloss', min: '0', step: '50', placeholder: 'any' });
    var node;
    if (level === 'beginner') {
      // Two limits a first-time trader actually has: money and odds. The rest unlocks at Expert.
      node = UI.expandable('Only show ideas that fit my limits', function () {
        return el('div', {},
          el('div', { class: 'form-grid' },
            el('div', { class: 'field' },
              el('label', {}, UI.term('max loss', 'The most I am willing to lose ($)')), maxLoss),
            el('div', { class: 'field' },
              el('label', {}, UI.term('pop', 'Minimum chance of any profit (%)')), minPop)),
          explain('Ideas outside your limits are not hidden silently — the results call them out with the exact reason, so you can see what you are screening out.'));
      });
    } else {
      node = el('div', { class: 'card compact-filters' },
        el('div', { class: 'form-grid grid-5' },
          el('div', { class: 'field' }, el('label', {}, 'Min POP %'), minPop),
          el('div', { class: 'field' }, el('label', {}, 'Max assign %'), maxAssign),
          el('div', { class: 'field' }, el('label', {}, 'Min yield %/yr'), minYield),
          el('div', { class: 'field' }, el('label', {}, 'Max cost $'), maxCost),
          el('div', { class: 'field' }, el('label', {}, 'Max loss $'), maxLoss)));
    }
    node.id = idPrefix + '-filters';
    return {
      node: node,
      value: function () {
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

  function renderScout(root) {
    // Scout form + last scan survive re-renders (level switch, tab hop) the same way the
    // manual form does — losing a 20-second scan to a header click was rightly a complaint.
    var saved = App.state.scoutForm || {};
    // ONE goal at a time (or "Everything"): the old row of five checkboxes read like a
    // database form, not a question — and multi-ticking mixed unrelated result groups.
    // "Everything" keeps the multi-goal scan; back-compat maps a saved intents[] onto it.
    var goal = saved.goal
      || (saved.intents && saved.intents.length > 1 ? 'ALL' : null)
      || (saved.intents && saved.intents.length === 1 ? saved.intents[0] : 'DIRECTIONAL');
    var GOAL_CHOICES = (Learn.INTENTS || []).concat([{
      key: 'ALL', label: 'Everything', icon: 'grid',
      blurb: 'Scan all goals at once — results come back grouped by goal.'
    }]);
    function remember() {
      App.state.scoutForm = {
        universe: universe.value, target: target.value, maxRisk: maxRisk.value,
        h0: h0.checked, hW: hW.checked, hM: hM.checked, goal: goal
      };
    }
    var universe = el('input', { type: 'text', id: 'auto-universe', placeholder: 'override just this scan (comma-separated)', value: saved.universe || '' });
    var uniData = App.state.universe;
    var sectorSel = el('select', { id: 'universe-sector' },
      (uniData ? uniData.sectors : []).map(function (s) {
        return el('option', { value: s.key, selected: uniData.active.sectorKey === s.key ? '' : null },
          s.label + ' (' + s.symbols.length + ')');
      }));
    sectorSel.addEventListener('change', async function () {
      try {
        await API.put('/api/universe', { sector: sectorSel.value });
        await App.refreshUniverse();
        App.render(); // header tape + labels update everywhere
      } catch (e) { alert(e.message); }
    });
    var target = el('input', { type: 'number', id: 'auto-target', min: '0', step: '50', placeholder: 'optional', value: saved.target || '' });
    var maxRisk = el('input', { type: 'number', id: 'auto-maxrisk', min: '0.1', max: '50', step: '0.1', placeholder: 'default by mode', value: saved.maxRisk || '' });
    var h0 = el('input', { type: 'checkbox', id: 'auto-h-0dte', checked: saved.h0 ? '' : null });
    var showZeroDte = Learn.currentLevel() !== 'beginner';
    var hW = el('input', { type: 'checkbox', id: 'auto-h-week', checked: saved.hW === false ? null : '' });
    var hM = el('input', { type: 'checkbox', id: 'auto-h-month', checked: saved.hM === false ? null : '' });
    [universe, target, maxRisk, h0, hW, hM].forEach(function (n) { n.addEventListener('change', remember); });
    var filters = filterPanel('auto');
    var results = el('div', { id: 'auto-results' });

    // Goal selector: one tap answers "what am I hunting for?"; the blurb under it
    // restates the selected goal in a sentence so nobody has to guess what a scan will do.
    var goalBlurb = el('p', { class: 'muted goal-blurb', id: 'scout-goal-blurb' });
    var goalRow = el('div', { class: 'goal-row', id: 'scout-goal', role: 'radiogroup' },
      GOAL_CHOICES.map(function (g) {
        return el('button', {
          class: 'goal-chip' + (goal === g.key ? ' selected' : ''), 'data-intent': g.key,
          type: 'button', role: 'radio', 'aria-checked': goal === g.key ? 'true' : 'false',
          onclick: function () {
            goal = g.key;
            remember();
            goalRow.querySelectorAll('.goal-chip').forEach(function (b) {
              var on = b.getAttribute('data-intent') === g.key;
              b.classList.toggle('selected', on);
              b.setAttribute('aria-checked', on ? 'true' : 'false');
            });
            goalBlurb.textContent = g.blurb;
          }
        }, icon(g.icon, 15), el('span', {}, g.label));
      }));
    goalBlurb.textContent = (GOAL_CHOICES.find(function (g) { return g.key === goal; }) || GOAL_CHOICES[0]).blurb;

    root.appendChild(el('div', { class: 'card' },
      explain('The scout scans the universe, reads price momentum, news sentiment (simple keyword scoring), and IV vs realized volatility, then derives a view per symbol and proposes structures that fit your goal — "Sell at a target" and "Protect my shares" scan the shares you actually hold. Every pick shows its evidence.'),
      el('div', { class: 'field-label' }, 'I’m looking to…'),
      goalRow, goalBlurb,
      el('div', { class: 'form-grid' },
        el('div', { class: 'field' }, el('label', {}, 'Target profit ($)'), target),
        el('div', { class: 'field' }, el('label', {}, 'Max risk, % of account'), maxRisk),
        el('div', { class: 'field' }, el('label', {}, 'Universe (applies everywhere)'), sectorSel),
        el('div', { class: 'field', style: 'grid-column: 1 / -1' }, el('label', {}, 'One-off universe for this scan only'), universe)),
      el('div', { class: 'btn-row' },
        el('span', { class: 'muted' }, 'Expirations:'),
        showZeroDte ? el('label', { class: 'inline-check' }, h0, ' 0DTE') : null,
        el('label', { class: 'inline-check' }, hW, ' Weekly'),
        el('label', { class: 'inline-check' }, hM, ' Monthly')),
      filters.node,
      el('div', { class: 'btn-row' },
        el('button', {
          class: 'btn', id: 'auto-go', onclick: async function () {
            results.innerHTML = '';
            results.appendChild(UI.spinner('Scanning universe and deriving views…'));
            try {
              var horizons = [];
              if (h0.checked) horizons.push('0DTE');
              if (hW.checked) horizons.push('week');
              if (hM.checked) horizons.push('month');
              var body = { horizons: horizons, riskMode: riskMode(), allow0dte: h0.checked };
              body.intents = goal === 'ALL'
                ? (Learn.INTENTS || []).map(function (i) { return i.key; })
                : [goal];
              var f = filters.value();
              if (f) body.filters = f;
              var flMax = filters.maxLossCents();
              if (flMax) body.maxLossCents = flMax;
              if (universe.value.trim()) body.universe = universe.value.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
              if (target.value) body.targetProfitCents = Math.round(parseFloat(target.value) * 100);
              if (maxRisk.value) body.maxRiskPctOfAccount = parseFloat(maxRisk.value) / 100;
              var scan = await API.post('/api/recommend/auto', body);
              App.state.scoutResults = scan;
              renderScoutResults(results, scan);
            } catch (e) {
              results.innerHTML = '';
              results.appendChild(alertBox('danger', e.message));
            }
          }
        }, 'Scan for opportunities'))));
    root.appendChild(results);
    if (App.state.scoutResults) renderScoutResults(results, App.state.scoutResults);
  }

  function renderScoutResults(results, r) {
    results.innerHTML = '';
    results.appendChild(el('div', { class: 'chip-row' },
      chip('Picks', String(r.picks.length)),
      chip('Risk budget', fmtMoney(r.riskBudgetCents))));
    (r.notes || []).forEach(function (n) { results.appendChild(alertBox('warn', n)); });
    if (!r.picks.length) {
      results.appendChild(UI.emptyState('No opportunities passed the screens',
        'Try widening the universe, allowing more expirations, or relaxing your filters.'));
    }
    var intentsSeen = [];
    r.picks.forEach(function (p) { if (intentsSeen.indexOf(p.intent) < 0) intentsSeen.push(p.intent); });
    if (intentsSeen.length > 1) {
      var GROUPS = { DIRECTIONAL: 'Trading a view', INCOME: 'Income opportunities',
        ACQUIRE: 'Buy-at-a-discount candidates', EXIT: 'Harvesting your holdings', HEDGE: 'Protecting your holdings' };
      intentsSeen.forEach(function (ik) {
        results.appendChild(el('h2', { class: 'scout-group' }, GROUPS[ik] || ik));
        r.picks.filter(function (p) { return p.intent === ik; })
          .forEach(function (pick) { results.appendChild(pickCard(pick)); });
      });
    } else {
      r.picks.forEach(function (pick) { results.appendChild(pickCard(pick)); });
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
        el('span', { class: 'sym' }, pick.symbol),
        intentBadge(pick.intent),
        el('span', { class: 'badge ' + (THESIS_BADGE[s.thesis] || 'badge-dim') }, s.thesis),
        chip('Confidence', fmtPct(s.confidence)),
        chip('Opportunity', fmtNum(pick.opportunityScore, 2)),
        el('span', { class: 'spacer' }),
        el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { App.navigate('#/research/' + pick.symbol); } }, 'Research')));
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
   * Shaped per level: Learning reads as sentences with a recommended rung, Confident gets the
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
              App.state.ticket = { candidate: c, symbol: ctx.symbol, step: 5 };
              App.navigate('#/ticket');
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
            App.state.ticket = { candidate: c, symbol: ctx.symbol, step: 5 };
            App.navigate('#/ticket');
          }
        }, 'Use')));
        var detail = el('tr', { class: 'compare-detail', style: 'display:none' },
          el('td', { colspan: String(headers.length + 1) }));
        var row = el('tr', {
          class: 'clickable' + (i === recommended ? ' ladder-recommended' : ''), onclick: function () {
            var open = detail.style.display !== 'none';
            detail.style.display = open ? 'none' : '';
            if (!open && !detail.firstChild.firstChild) detail.firstChild.appendChild(candidateCard(c, true, ctx.symbol));
          }
        }, tds);
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

  function renderManual(root) {
    // Form state lives in App.state so a level switch (which re-renders the route) or a
    // "Sell at a target…" prefill from the portfolio survives instead of resetting to defaults.
    var saved = App.state.ideasForm || {};
    var prefill = App.state.ideasPrefill || {};
    App.state.ideasPrefill = null;
    var intent = prefill.intent || saved.intent || 'DIRECTIONAL';
    function remember(patch) {
      App.state.ideasForm = Object.assign({}, App.state.ideasForm || {}, patch);
    }
    remember({ intent: intent });
    var sym = el('input', { type: 'text', id: 'rec-symbol', placeholder: 'AAPL', list: 'universe-symbols',
      value: prefill.symbol || saved.symbol || App.state.lastRecommendSymbol || 'AAPL' });
    var thesis = el('select', { id: 'rec-thesis' },
      ['bullish', 'bearish', 'neutral', 'volatile'].map(function (t) { return el('option', { value: t }, t); }));
    var horizons = Learn.currentLevel() === 'beginner' ? ['week', 'month', 'quarter'] : ['week', 'month', 'quarter', '0DTE'];
    var horizon = el('select', { id: 'rec-horizon' },
      horizons.map(function (h) { return el('option', { value: h, selected: h === 'month' ? '' : null }, h); }));
    var maxRisk = el('input', { type: 'number', id: 'rec-maxrisk', min: '0.1', max: '50', step: '0.1', placeholder: 'default by mode' });
    var allow0 = el('input', { type: 'checkbox', id: 'rec-0dte' });
    var target = el('input', { type: 'number', id: 'rec-target', min: '0', step: '0.5', placeholder: 'optional',
      value: prefill.symbol ? '' : (saved.target || '') });
    target.addEventListener('change', function () { remember({ target: target.value }); });
    // One-tap targets anchored to the live price: +5/+10/+20% for exits, -5/-10/-15% for buys/floors
    var targetPresets = el('div', { class: 'chip-row', id: 'target-presets', style: 'margin-top:4px' });
    async function renderTargetPresets() {
      targetPresets.innerHTML = '';
      var offsets = intent === 'EXIT' ? [5, 10, 20] : [-5, -10, -15];
      try {
        var q = await API.get('/api/quotes?symbols=' + sym.value.trim().toUpperCase());
        if (!q.quotes.length) return;
        var last = parseFloat(q.quotes[0].last);
        offsets.forEach(function (o) {
          var px = last * (1 + o / 100);
          targetPresets.appendChild(el('button', {
            class: 'sym-chip', type: 'button', onclick: function () {
              target.value = px.toFixed(2);
              remember({ target: target.value });
            }
          }, (o > 0 ? '+' : '') + o + '% \u2192 $' + px.toFixed(2)));
        });
      } catch (e) { /* presets are sugar */ }
    }
    var wantShares = el('input', { type: 'number', id: 'rec-shares', min: '100', step: '100', placeholder: '100' });
    var filters = filterPanel('rec');
    var results = el('div', { id: 'rec-results' });
    var holdingsHint = el('div', { id: 'rec-holdings-hint' });

    // 1. WHY are you here — the intent decides which questions even make sense.
    // Learning/Confident get story cards (what each goal means); Pro gets a compact
    // segmented row — a pro knows what a covered call is for and wants the pixels back.
    var expertChooser = Learn.currentLevel() === 'expert';
    var intentRow = el('div', {
      class: 'choice-row' + (expertChooser ? ' intent-compact' : ''), id: 'intent-choices'
    }, (Learn.INTENTS || []).map(function (i) {
        return el('button', {
          class: 'choice' + (intent === i.key ? ' selected' : ''), 'data-intent': i.key,
          title: i.blurb,
          onclick: function () {
            intent = i.key;
            remember({ intent: intent });
            intentRow.querySelectorAll('.choice').forEach(function (b) { b.classList.remove('selected'); });
            this.classList.add('selected');
            syncIntentFields();
          }
        }, expertChooser
             ? el('b', {}, i.label)
             : el('span', { class: 'choice-head' }, icon(i.icon, 17), el('b', {}, i.label)),
           expertChooser ? null : el('span', { class: 'muted' }, i.blurb));
      }));

    var thesisField = el('div', { class: 'field' }, el('label', {}, 'Your view'), thesis);
    var targetField = el('div', { class: 'field', style: 'display:none' }, el('label', { id: 'rec-target-label' }, 'Target price ($/sh)'), target, targetPresets);
    var sharesField = el('div', { class: 'field', style: 'display:none' }, el('label', {}, 'Shares you want'), wantShares);

    function syncIntentFields() {
      thesisField.style.display = intent === 'DIRECTIONAL' ? '' : 'none';
      targetField.style.display = intent === 'EXIT' || intent === 'ACQUIRE' || intent === 'HEDGE' ? '' : 'none';
      sharesField.style.display = intent === 'ACQUIRE' ? '' : 'none';
      var lbl = document.getElementById('rec-target-label');
      if (lbl) {
        lbl.textContent = intent === 'EXIT' ? 'Price you would happily sell at ($/sh)'
          : intent === 'ACQUIRE' ? 'Price you would happily pay ($/sh)'
          : 'Protect below ($/sh, optional)';
      }
      if (intent === 'EXIT' || intent === 'ACQUIRE' || intent === 'HEDGE') renderTargetPresets();
      refreshHoldingsHint();
    }

    async function refreshHoldingsHint() {
      holdingsHint.innerHTML = '';
      if (intent === 'DIRECTIONAL') return;
      try {
        var all = (await API.get('/api/positions')).positions || [];
        // EXIT/HEDGE start from what you OWN: your holdings as one-tap chips
        if ((intent === 'EXIT' || intent === 'HEDGE') && all.length) {
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
                  remember({ symbol: p.symbol });
                  refreshHoldingsHint();
                }
              }, p.symbol + ' \u00B7 ' + p.shares + ' sh', g);
            })));
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
        } else if (intent === 'EXIT' || intent === 'HEDGE') {
          holdingsHint.appendChild(explain('You do not hold ' + (sym.value.trim().toUpperCase() || 'this symbol')
            + ' — ideas below include buying the shares first (buy-write style). Options work in 100-share lots.'));
        }
      } catch (e) { /* hint only */ }
    }
    sym.addEventListener('change', function () {
      remember({ symbol: sym.value.trim().toUpperCase() });
      if (intent !== 'DIRECTIONAL') renderTargetPresets();
      refreshHoldingsHint();
    });

    root.appendChild(el('div', { class: 'card' },
      el('h3', { class: 'mt0' }, 'What are you trying to do?'),
      explain('The engine only shows strategies whose worst case is known and capped up front, and it always explains what it refused and why. Goals that involve your shares read your real holdings.'),
      intentRow,
      el('div', { class: 'form-grid', style: 'margin-top:10px' },
        el('div', { class: 'field' }, el('label', {}, 'Symbol'), sym),
        thesisField,
        targetField,
        sharesField,
        el('div', { class: 'field' }, el('label', {}, 'Horizon'), horizon),
        el('div', { class: 'field' }, el('label', {}, 'Max risk, % of account'), maxRisk),
        el('div', { class: 'field inline-check not-beginner', style: 'align-self:end' + (Learn.currentLevel() === 'beginner' ? ';display:none' : '') }, allow0, el('label', { for: 'rec-0dte', style: 'text-transform:none;letter-spacing:0;font-size:13.5px;font-weight:500' }, 'Allow same-day (0DTE) expiry'))),
      holdingsHint,
      filters.node,
      el('div', { class: 'btn-row' },
        el('button', {
          class: 'btn', id: 'rec-go', onclick: async function () {
            results.innerHTML = '';
            results.appendChild(UI.spinner('Screening strategies…'));
            try {
              App.state.lastRecommendSymbol = sym.value.trim().toUpperCase();
              var body = {
                symbol: sym.value.trim(), horizon: horizon.value,
                riskMode: riskMode(), allow0dte: allow0.checked, avoidEarnings: true,
                intent: intent
              };
              if (intent === 'DIRECTIONAL') body.thesis = thesis.value;
              if (maxRisk.value) body.maxRiskPctOfAccount = parseFloat(maxRisk.value) / 100;
              var f = filters.value();
              if (f) body.filters = f;
              var flMax = filters.maxLossCents();
              if (flMax) body.maxLossCents = flMax;
              // Holdings context: real position (if any) + the user's target price.
              if (intent !== 'DIRECTIONAL') {
                var holdings = {};
                var targetApplies = intent === 'EXIT' || intent === 'ACQUIRE' || intent === 'HEDGE';
                if (targetApplies && target.value) holdings.targetPriceCents = Math.round(parseFloat(target.value) * 100);
                if (intent === 'ACQUIRE' && wantShares.value) holdings.sharesOwned = parseInt(wantShares.value, 10);
                if (intent !== 'ACQUIRE') {
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
              var r = await API.post('/api/recommend', body);
              var extras = {};
              if (intent === 'ACQUIRE' || intent === 'EXIT' || intent === 'HEDGE') {
                // The intent-native view: rungs of the same structure across strikes
                try { extras.ladder = await API.post('/api/recommend/ladder', body); } catch (e2) { /* cards still render */ }
                try {
                  var q = await API.get('/api/quotes?symbols=' + body.symbol.toUpperCase());
                  extras.spot = q.quotes.length ? parseFloat(q.quotes[0].last) : null;
                } catch (e3) { /* fine */ }
              }
              if (intent === 'INCOME') {
                try {
                  extras.acct = (await API.get('/api/account')).account;
                  extras.held = (await API.get('/api/positions')).positions;
                } catch (e4) { /* board is optional */ }
              }
              renderResults(r, intent, body, extras);
            } catch (e) {
              results.innerHTML = '';
              results.appendChild(alertBox('danger', e.message));
            }
          }
        }, 'Find ideas'))));
    root.appendChild(results);
    syncIntentFields();

    function renderResults(r, intentKey, body, extras) {
      extras = extras || {};
      results.innerHTML = '';
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
        chip('Risk budget', fmtMoney(r.riskBudgetCents)),
        chip('Mode', r.riskMode.toLowerCase()),
        chip('Candidates', String(r.candidates.length))));
      (r.notes || []).forEach(function (n) { results.appendChild(alertBox('warn', n)); });
      if (!r.candidates.length) {
        results.appendChild(UI.emptyState('Nothing passed the risk screens',
          'Try a different horizon, a wider risk budget, or another symbol.'));
      }
      var hasLadder = extras.ladder && extras.ladder.rungs && extras.ladder.rungs.length;
      if (hasLadder && r.candidates.length) {
        results.appendChild(el('h3', {}, 'Other structures for this goal'));
      }
      if (Learn.currentLevel() === 'expert' && r.candidates.length > 1) {
        results.appendChild(comparisonTable(r.candidates));
      } else {
        r.candidates.forEach(function (c) { results.appendChild(candidateCard(c, true)); });
      }
      if (r.rejected && r.rejected.length) {
        var rej = el('div', { class: 'card' },
          UI.cardHeader('Looked at and refused'),
          explain('Learning what gets blocked — and why — matters as much as what gets suggested.'));
        r.rejected.forEach(function (x) {
          rej.appendChild(el('div', { class: 'status-item' },
            el('span', { class: 'badge badge-danger' }, 'BLOCKED'),
            el('span', {}, el('b', {}, x.displayName), ' — ', (x.reasons || []).join(' '))));
        });
        results.appendChild(rej);
      }
      results.appendChild(el('p', { class: 'muted' }, r.disclaimer));
    }
  }

  // ---------- 4. Guided ticket ----------

  var STEPS = ['Thesis', 'Horizon', 'Risk', 'Strategy', 'Strikes', 'Review', 'Confirm'];

  async function ticket(root, params) {
    // Two ways to trade, one screen: the guided wizard, or the multi-leg builder.
    var mode = params && params[0] === 'builder' ? 'builder' : 'guided';
    root.appendChild(el('h1', {}, 'Trade'));
    root.appendChild(el('div', { class: 'tabs' },
      el('button', { class: mode === 'guided' ? 'active' : '', id: 'tab-guided',
        onclick: function () { App.navigate('#/ticket'); } }, 'Guided ticket'),
      el('button', { class: mode === 'builder' ? 'active' : '', id: 'tab-builder',
        onclick: function () { App.navigate('#/ticket/builder'); } }, 'Strategy builder')));
    if (mode === 'builder') { await Builder.render(root); return; }

    var t = App.state.ticket = App.state.ticket || {};
    t.step = t.step || 1;
    t.symbol = t.symbol || App.state.lastRecommendSymbol || 'AAPL';
    // The engine sizes candidates (qty may be 3 for "cover all 300 shares") — never
    // silently reset that to 1; the user can still change it on the Strikes step.
    t.qty = t.qty || (t.candidate && t.candidate.qty) || 1;
    // Ladder guard on persisted state: Learning users inheriting a 0DTE horizon fall
    // back to the guided flow. (Custom tickets are legitimate at EVERY level now — the
    // strategy builder hands off here for all three.)
    if (Learn.currentLevel() === 'beginner' && t.horizon === '0DTE') {
      t.horizon = null;
      t.step = Math.min(t.step, 2);
    }


    root.appendChild(el('div', { class: 'wizard-steps' }, STEPS.map(function (s, i) {
      var n = i + 1;
      var cls = n === t.step ? ' active' : (n < t.step ? ' done' : '');
      return el('span', { class: 'step' + cls },
        el('span', { class: 'dot' }, n < t.step ? '✓' : String(n)), s);
    })));
    var body = el('div', { class: 'card', id: 'ticket-body' });
    root.appendChild(body);

    function rerender() { App.render(); }
    function nav(step) { t.step = step; rerender(); }

    function choices(id, options, selected, onPick) {
      return el('div', { class: 'choice-row', id: id }, options.map(function (o) {
        return el('button', {
          class: 'choice' + (selected === o.value ? ' selected' : ''),
          onclick: function () { onPick(o.value); }
        }, o.label, o.hint ? el('small', {}, o.hint) : null);
      }));
    }

    function backNext(backStep, nextNode) {
      return el('div', { class: 'btn-row' },
        el('button', { class: 'btn btn-secondary', onclick: function () { nav(backStep); } }, '← Back'),
        nextNode || null);
    }

    if (t.step <= 3 && Learn.currentLevel() === 'expert') {
      // Pro quick-start: symbol + view + horizon in one screen
      var qsSym = el('input', { type: 'text', id: 'ticket-symbol', value: t.symbol, style: 'max-width:140px', list: 'universe-symbols' });
      var qsThesis = el('select', { id: 'qs-thesis' },
        ['bullish', 'bearish', 'neutral', 'volatile'].map(function (x) {
          return el('option', { value: x, selected: x === (t.thesis || 'bullish') ? '' : null }, x);
        }));
      var qsHorizon = el('select', { id: 'qs-horizon' },
        ['week', 'month', 'quarter', '0DTE'].map(function (x) {
          return el('option', { value: x, selected: x === (t.horizon || 'month') ? '' : null }, x);
        }));
      body.appendChild(el('h2', { class: 'mt0' }, 'Quick start'));
      body.appendChild(el('div', { class: 'btn-row', style: 'margin-top:4px' },
        qsSym, qsThesis, qsHorizon,
        el('button', {
          class: 'btn', id: 'risk-next', onclick: function () {
            t.symbol = qsSym.value.trim().toUpperCase();
            t.thesis = qsThesis.value;
            t.horizon = qsHorizon.value;
            nav(4);
          }
        }, 'Screen strategies \u2192'),
        el('button', {
          class: 'btn btn-secondary', id: 'custom-builder-btn', onclick: function () {
            App.state.lastRecommendSymbol = qsSym.value.trim().toUpperCase();
            App.navigate('#/ticket/builder');
          }
        }, 'Strategy builder')));
      return;
    }

    if (t.step === 1) {
      var symInput = el('input', { type: 'text', id: 'ticket-symbol', value: t.symbol, list: 'universe-symbols' });
      body.appendChild(el('h2', { class: 'mt0' }, 'What do you expect, and where?'));
      body.appendChild(el('div', { class: 'field', style: 'max-width:260px' }, el('label', {}, 'Symbol'), symInput));
      body.appendChild(explain('Pick the stock or ETF, then your honest expectation. Every later screen bends around this answer.'));
      body.appendChild(choices('thesis-choices', [
        { value: 'bullish', label: 'Bullish', hint: 'I expect it to go up' },
        { value: 'bearish', label: 'Bearish', hint: 'I expect it to go down' },
        { value: 'neutral', label: 'Neutral', hint: 'Mostly sideways from here' },
        { value: 'volatile', label: 'Volatile', hint: 'Big move coming, unsure which way' }
      ], t.thesis, function (v) { t.thesis = v; t.symbol = symInput.value.trim().toUpperCase(); nav(2); }));
    }

    if (t.step === 2) {
      body.appendChild(el('h2', { class: 'mt0' }, 'How long do you give the idea?'));
      body.appendChild(explain('Options lose value as time passes. Shorter horizons are cheaper but must be right faster. 0DTE expires TODAY — expert territory.'));
      var horizonOptions = [
        { value: 'week', label: 'About a week', hint: 'Fast, cheap, unforgiving' },
        { value: 'month', label: 'About a month', hint: 'The classic balance' },
        { value: 'quarter', label: 'A quarter', hint: 'Room to be early' }
      ];
      if (Learn.currentLevel() !== 'beginner') {
        horizonOptions.push({ value: '0DTE', label: 'Today (0DTE)', hint: 'Expires in hours. Extreme gamma.' });
      }
      body.appendChild(choices('horizon-choices', horizonOptions, t.horizon, function (v) { t.horizon = v; nav(3); }));
      body.appendChild(backNext(1));
    }

    if (t.step === 3) {
      body.appendChild(el('h2', { class: 'mt0' }, 'How much can this trade lose?'));
      body.appendChild(explain('The header risk mode caps your budget per trade. Everything shown later keeps the worst case inside that number.'));
      body.appendChild(el('div', { class: 'chip-row' },
        chip('Risk mode', riskMode()),
        chip('Budget', ({ learning: '1%', conservative: '1%', balanced: '2%', aggressive: '5%' }[riskMode()] || '1%') + ' of buying power')));
      body.appendChild(el('p', { class: 'muted' }, 'Change the risk mode in the header if this is not what you want.'));
      body.appendChild(backNext(2, el('button', { class: 'btn', id: 'risk-next', onclick: function () { nav(4); } }, 'Continue →')));
    }

    if (t.step === 4) {
      body.appendChild(el('h2', { class: 'mt0' }, 'Pick a strategy'));
      body.appendChild(UI.spinner('Screening strategies for ' + t.symbol + '…'));
      try {
        var r = await API.post('/api/recommend', {
          symbol: t.symbol, thesis: t.thesis || 'neutral', horizon: t.horizon || 'month',
          riskMode: riskMode(), allow0dte: t.horizon === '0DTE', avoidEarnings: true
        });
        body.innerHTML = '';
        body.appendChild(el('h2', { class: 'mt0' }, 'Pick a strategy'));
        if (!r.candidates.length) body.appendChild(alertBox('warn', 'Nothing passed the risk screens. Go back and change the horizon or symbol.'));
        r.candidates.forEach(function (c) {
          var card = candidateCard(c, false);
          card.appendChild(el('div', { class: 'btn-row' },
            el('button', { class: 'btn', onclick: function () { t.candidate = c; nav(5); } }, 'Choose this')));
          body.appendChild(card);
        });
        body.appendChild(backNext(3));
      } catch (e) {
        body.innerHTML = '';
        body.appendChild(alertBox('danger', e.message));
        body.appendChild(backNext(3));
      }
    }

    if (t.step === 5) {
      var c = t.candidate;
      if (!c) { nav(4); return; }
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
          t.qty = Math.max(1, parseInt(qtyInput.value || '1', 10));
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
          symbol: t.symbol, strategy: t.custom ? 'CUSTOM' : c6.strategy, qty: t.qty,
          legs: t.legs, thesis: t.thesis, horizon: t.horizon, riskMode: riskMode()
        };
        if (c6 && c6.intent && c6.intent !== 'DIRECTIONAL') previewReq.intent = c6.intent;
        if (c6 && c6.usesHeldShares) previewReq.useHeldShares = true;
        var res = await API.post('/api/trades/preview', previewReq);
        t.previewReq = previewReq;
        var p = res.preview, g = res.guardrails;
        body.innerHTML = '';
        body.appendChild(el('h2', { class: 'mt0' }, 'Review before you commit'));
        if (!p.ok) body.appendChild(alertBox('danger', 'Blocked', p.blockReasons));
        if (g && g.blockReasons && g.blockReasons.length) body.appendChild(alertBox('danger', 'Guardrails', g.blockReasons));
        var warns = (p.warnings || []).concat(g ? g.warnings || [] : []);
        if (warns.length) body.appendChild(alertBox('warn', 'Heads up', warns));
        // Safety checklist: the guardrail verdict, restated as things a human can verify
        var checks = [];
        var coveredByShares = t.previewReq && t.previewReq.useHeldShares;
        checks.push({ state: p.ok && (p.maxLossCents > 0 || coveredByShares) ? 'pass' : 'fail',
          text: p.maxLossCents > 0
            ? 'Worst case is known and capped at ' + fmtMoney(p.maxLossCents) + (p.feesOpenCents ? ' (+' + fmtMoney(p.feesOpenCents) + ' fees)' : '')
            : coveredByShares
              ? 'No new cash at risk — the trade is backed by shares you already hold (their own downside continues)'
              : 'Worst case could not be verified' });
        var overBudget = (g ? (g.warnings || []) : []).some(function (w) { return w.indexOf('risk budget') >= 0; });
        checks.push({ state: overBudget ? 'warn' : 'pass',
          text: overBudget ? 'Bigger than your chosen risk-mode budget — allowed, but oversized'
                           : 'Fits inside your risk-mode budget' });
        if (t.previewReq && t.previewReq.useHeldShares) {
          checks.push({ state: 'pass',
            text: 'Covered by shares you already hold — they are locked while this trade is open and can be called away at the strike' });
        }
        checks.push({ state: p.freshness === 'REALTIME' || p.freshness === 'FIXTURE' ? 'pass' : 'warn',
          text: p.freshness === 'FIXTURE' ? 'Priced on demo data (practice mode)'
              : p.freshness === 'REALTIME' ? 'Priced on real-time quotes'
              : 'Priced on ' + p.freshness + ' quotes — real fills may differ' });
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
          stat('Max loss', el('span', { class: 'loss' }, fmtMoney(p.maxLossCents)), 'The most this position can lose, fees excluded.'),
          stat('Max profit', p.maxProfitCents === null || p.maxProfitCents === undefined ? 'uncapped' : el('span', { class: 'gain' }, fmtMoney(p.maxProfitCents))),
          stat('Fees', fmtMoney(p.feesOpenCents), '$0.65 per contract per leg by default.'),
          stat('POP', fmtPct(p.popEntry), 'Modeled probability of any profit at expiration.'),
          stat('Breakevens', (p.breakevens || []).map(fmtBreakeven).join(' / ') || '—'),
          stat('Buying power after', fmtMoney(p.buyingPowerAfterCents), 'Drops by exactly max loss + fees.'),
          stat('Cash after', fmtMoney(p.cashAfterCents))));
        body.appendChild(backNext(5, el('button', {
          class: 'btn', id: 'to-confirm', disabled: p.ok ? null : 'disabled',
          onclick: function () { t.preview = p; nav(7); }
        }, 'Continue →')));
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
            var res = await API.post('/api/trades', t.previewReq);
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
        await API.post('/api/positions/' + side,
          { symbol: symInput.value.trim(), shares: parseInt(qty.value, 10) });
        App.render();
      });
  }

  async function portfolio(root, params) {
    var tab = params[0] === 'closed' ? 'closed' : 'active';
    var page = parseInt(params[1] || '0', 10);
    root.appendChild(el('h1', {}, 'Portfolio'));

    var acct = (await API.get('/api/account')).account;
    root.appendChild(el('div', { class: 'grid grid-3', style: 'margin-bottom:14px' },
      stat('Cash', fmtMoney(acct.cashCents)),
      stat('Reserved', fmtMoney(acct.reservedCents)),
      stat('Buying power', fmtMoney(acct.buyingPowerCents))));

    if (Learn.currentLevel() === 'expert' && tab === 'active') {
      try {
        var pg = await API.get('/api/portfolio/greeks');
        if (pg.positions && pg.positions.length) {
          root.appendChild(el('div', { class: 'card', id: 'portfolio-greeks' },
            UI.cardHeader('Book greeks', pg.complete ? null : el('span', { class: 'badge badge-caution' }, 'PARTIAL')),
            el('div', { class: 'chip-row' },
              chip('Net \u0394', fmtNum(pg.deltaShares, 0) + ' sh'),
              chip('\u0393', fmtNum(pg.gammaShares, 2) + ' sh/$'),
              chip('\u0398/day', pnlSpan(pg.thetaPerDay * 100)),
              chip('Vega/pt', pnlSpan(pg.vegaPerPoint * 100)))));
        }
      } catch (e) { /* advisory only */ }
    }

    // Equity holdings: first-class shares with basis, lock state, and a covered-call nudge.
    try {
      var held = (await API.get('/api/positions')).positions || [];
      var holdCard = el('div', { class: 'card', id: 'holdings-card' },
        UI.cardHeader('Shares you hold', el('button', {
          class: 'btn btn-sm btn-secondary', id: 'buy-shares-btn',
          onclick: function () { stockOrderModal('buy', App.state.lastRecommendSymbol || 'AAPL'); }
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

    root.appendChild(el('div', { class: 'tabs' },
      el('button', { class: tab === 'active' ? 'active' : '', id: 'tab-active', onclick: function () { App.navigate('#/portfolio/active'); } }, 'Active'),
      el('button', { class: tab === 'closed' ? 'active' : '', id: 'tab-closed', onclick: function () { App.navigate('#/portfolio/closed'); } }, 'Closed')));

    // Filter the trade list by the values that matter (symbol, goal)
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
    root.appendChild(el('div', { class: 'btn-row', id: 'pf-filters' },
      el('span', { class: 'muted' }, 'Filter:'), fSym, fIntent));

    var statusParam = tab === 'active' ? 'ACTIVE' : 'CLOSED';
    var fq = '';
    if (pf.symbol) fq += '&symbol=' + encodeURIComponent(pf.symbol);
    if (pf.intent) fq += '&intent=' + encodeURIComponent(pf.intent);
    var data = await API.get('/api/trades?status=' + statusParam + '&page=' + page + '&size=15' + fq);
    if (tab === 'closed' && page === 0) {
      // Settled/voided trades ride along on the first page only (up to 50 each) —
      // repeating them on every page made them look like distinct trades.
      var extra = await Promise.all([
        API.get('/api/trades?status=EXPIRED&page=0&size=50' + fq),
        API.get('/api/trades?status=DELETED&page=0&size=50' + fq)
      ]);
      data.trades = data.trades.concat(extra[0].trades, extra[1].trades);
    }
    if (!data.trades.length) {
      root.appendChild(el('div', { class: 'card' }, tab === 'active'
        ? UI.emptyState('No open practice trades', 'Walk the guided ticket to build one with capped, pre-known risk.', 'Open the trade ticket', function () { App.navigate('#/ticket'); })
        : UI.emptyState('Nothing closed yet', 'Closed, settled, and voided trades land here.')));
      return;
    }
    var rows = data.trades.map(function (t) {
      return el('tr', {
        class: 'clickable', onclick: function () { App.navigate('#/trade/' + t.id); }
      },
        el('td', {}, el('b', {}, t.symbol)),
        el('td', {}, prettyStrategy(t.strategy), t.intent && t.intent !== 'DIRECTIONAL' ? el('span', { style: 'margin-left:6px' }, intentBadge(t.intent)) : null),
        el('td', {}, 'x' + t.qty),
        el('td', {}, pnlSpan(t.entryNetPremiumCents)),
        el('td', { class: 'loss' }, fmtMoney(t.maxLossCents)),
        el('td', {}, tab === 'active' ? el('span', { class: 'muted' }, (t.createdAt || '').slice(0, 10)) : pnlSpan(t.realizedPnlCents)),
        el('td', {}, el('span', { class: 'badge ' + (t.status === 'ACTIVE' ? 'badge-ok' : t.status === 'DELETED' ? 'badge-danger' : 'badge-dim') }, t.status)));
    });
    root.appendChild(el('div', { class: 'card' },
      table(['Symbol', 'Strategy', 'Qty', 'Entry credit/debit', 'Max loss', tab === 'active' ? 'Opened' : 'Realized P/L', 'Status'], rows),
      explain('Click any row for the payoff chart, live marks, and close/settle actions.')));
    if (data.total > 15) {
      root.appendChild(el('div', { class: 'btn-row' },
        page > 0 ? el('button', { class: 'btn btn-secondary', onclick: function () { App.navigate('#/portfolio/' + tab + '/' + (page - 1)); } }, '← Newer') : null,
        (page + 1) * 15 < data.total ? el('button', { class: 'btn btn-secondary', onclick: function () { App.navigate('#/portfolio/' + tab + '/' + (page + 1)); } }, 'Older →') : null));
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
        intentBadge(t.intent),
        el('span', { class: 'badge ' + (active ? 'badge-ok' : t.status === 'DELETED' ? 'badge-danger' : 'badge-dim') }, t.status),
        el('span', { class: 'spacer' }),
        pnl !== null && pnl !== undefined
          ? el('span', { class: 'px ' + (pnl >= 0 ? 'gain' : 'loss') }, fmtMoney(pnl, { plus: true }))
          : null),
      el('div', { class: 'muted' },
        (active ? 'Unrealized (before close fees)' : 'Realized P/L') + ' · opened ' + (t.createdAt || '').slice(0, 10)
        + (t.closeReason ? ' · ' + t.closeReason : '')),
      el('div', { class: 'chip-row' }, t.legs.map(function (l, i) { return chip('Leg ' + (i + 1), legLabel(l)); })),
      el('div', { class: 'chip-row' },
        chip('Entry', fmtMoney(t.entryNetPremiumCents, { plus: true })),
        t.sharesLocked > 0 && t.maxLossCents === 0
          ? chip('New cash at risk', '$0 (covered)')
          : chip('Max loss', el('span', { class: 'loss' }, fmtMoney(t.maxLossCents))),
        chip('Max profit', t.maxProfitCents === null || t.maxProfitCents === undefined ? 'uncapped' : fmtMoney(t.maxProfitCents)),
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
                ? 'Closing now brings ' + fmtMoney(d.current.closeCostCents, { plus: true }) + ' before fees. ' : '';
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
          }, 'Unwind…'),
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
          }, 'Settle…'),
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
          }, 'Void…'))));
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

  // ---------- 8. Backtest ----------

  async function backtest(root) {
    // A candidate card's "Backtest this" lands here with the form pre-answered.
    var prefill = App.state.backtestPrefill || {};
    App.state.backtestPrefill = null;
    root.appendChild(el('h1', {}, 'Backtest a strategy'));
    root.appendChild(el('p', { class: 'page-sub' },
      Learn.currentLevel() === 'beginner'
        ? 'Before risking even paper money on a rule like “sell a monthly covered call”, see how it would actually have gone — trade by trade, with honest labels on what is modeled.'
        : 'Replays a strategy rule day by day with no look-ahead — and labels exactly how much of its pricing is modeled.'));
    var sym = el('input', { type: 'text', id: 'bt-symbol', value: prefill.symbol || 'AAPL', list: 'universe-symbols' });
    // The strategy menu climbs the ladder with the user: Learning sees the beginner
    // families (mirroring the engine's learning risk rank), Confident adds defined-risk
    // premium selling, Pro sees everything backtestable.
    var BT_GROUPS = [
      { label: 'Trade a view', families: ['LONG_CALL', 'LONG_PUT', 'DEBIT_CALL_SPREAD', 'DEBIT_PUT_SPREAD',
                                          'LONG_CALL_BUTTERFLY', 'LONG_PUT_BUTTERFLY'] },
      { label: 'Earn income', families: ['CREDIT_CALL_SPREAD', 'CREDIT_PUT_SPREAD', 'IRON_CONDOR',
                                         'IRON_BUTTERFLY', 'COVERED_CALL'] },
      { label: 'Buy at a discount', families: ['CASH_SECURED_PUT'] },
      { label: 'Protect shares', families: ['PROTECTIVE_PUT', 'PROTECTIVE_COLLAR'] }
    ];
    var btLevel = Learn.currentLevel();
    var BT_LEVEL_ALLOWED = {
      beginner: ['LONG_CALL', 'LONG_PUT', 'DEBIT_CALL_SPREAD', 'DEBIT_PUT_SPREAD',
                 'COVERED_CALL', 'CASH_SECURED_PUT', 'PROTECTIVE_PUT']
    };
    var btAllowed = BT_LEVEL_ALLOWED[btLevel] || null; // expert: everything
    // A "Backtest this" click is an explicit ask — never silently swap the strategy
    // because the current level's menu wouldn't normally show it.
    if (btAllowed && prefill.strategy && btAllowed.indexOf(prefill.strategy) < 0) {
      btAllowed = btAllowed.concat([prefill.strategy]);
    }
    var strat = el('select', { id: 'bt-strategy' },
      BT_GROUPS.map(function (g) {
        var fams = btAllowed ? g.families.filter(function (s) { return btAllowed.indexOf(s) >= 0; }) : g.families;
        if (!fams.length) return null;
        return el('optgroup', { label: g.label }, fams.map(function (s) {
          var pick = prefill.strategy ? s === prefill.strategy : s === 'DEBIT_CALL_SPREAD';
          return el('option', { value: s, selected: pick ? '' : null }, prettyStrategy(s));
        }));
      }));
    var today = new Date();
    var toDefault = today.toISOString().slice(0, 10);
    var fromDefault = new Date(today.getTime() - 182 * 86400000).toISOString().slice(0, 10);
    var from = el('input', { type: 'date', id: 'bt-from', value: fromDefault });
    var to = el('input', { type: 'date', id: 'bt-to', value: toDefault });
    var dte = el('input', { type: 'number', id: 'bt-dte', value: '30', min: '1', max: '365' });
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
        return el('button', { class: 'sym-chip', type: 'button', onclick: function () { dte.value = String(p.v); } }, p.label);
      }));

    root.appendChild(el('div', { class: 'card' },
      btLevel === 'beginner'
        ? explain('The strategy list matches your Learning level — credit spreads, condors and collars unlock at Confident, everything at Pro (the level switch is in the header). Target DTE is how far out each trade’s expiration is: Monthly (30 days) is the classic starting point.')
        : null,
      el('div', { class: 'form-grid' },
        el('div', { class: 'field' }, el('label', {}, 'Symbol'), sym),
        el('div', { class: 'field' }, el('label', {}, 'Strategy'), strat),
        el('div', { class: 'field' }, el('label', {}, 'Target DTE'), dte, dtePresets),
        el('div', { class: 'field' }, el('label', {}, 'Window'), periodRow),
        el('div', { class: 'field' }, el('label', {}, 'From'), from),
        el('div', { class: 'field' }, el('label', {}, 'To'), to)),
      el('div', { class: 'btn-row' },
        el('button', {
          class: 'btn', id: 'bt-run', onclick: async function () {
            var btn = document.getElementById('bt-run');
            btn.disabled = true;
            out.innerHTML = '';
            out.appendChild(UI.spinner('Running backtest…'));
            try {
              var report = await API.post('/api/backtest', {
                symbol: sym.value.trim(), strategy: strat.value,
                from: from.value, to: to.value, targetDte: parseInt(dte.value, 10)
              });
              renderReport(report);
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
      var modeKind = r.pricingMode === 'HISTORICAL_CHAIN' ? 'ok' : r.pricingMode === 'MODELED_FROM_UNDERLYING' ? 'warn' : 'danger';
      out.appendChild(alertBox(modeKind, 'Pricing mode: ' + r.pricingMode + ' — confidence ' + r.confidence + '. ' +
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
        out.appendChild(el('div', { class: 'card' },
          UI.cardHeader('Trades'),
          table(['Entry', 'Exit', 'Position', 'Premium', 'Exit value', 'Fees', 'P/L', 'Why closed'],
            r.trades.map(function (t) {
              return el('tr', {},
                el('td', { class: 'muted' }, t.entryDate), el('td', { class: 'muted' }, t.exitDate),
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
  }

  // ---------- 9. Data status ----------

  var STATE_BADGE = { OK: 'badge-ok', ERROR: 'badge-danger', EMPTY: 'badge-caution', UNKNOWN: 'badge-dim', UNCONFIGURED: 'badge-dim' };

  async function status(root) {
    root.appendChild(el('h1', {}, 'Data sources'));
    var s = await API.get('/api/status');
    root.appendChild(explain(brandName() + ' chains several data sources per domain and falls back gracefully. DEMO DATA means the built-in deterministic fixture is serving.'));
    if (s.fixturesOnly) root.appendChild(alertBox('warn', 'Running in fixtures-only mode: all data is simulated demo data.'));
    var grid = el('div', { class: 'grid grid-2' });
    Object.keys(s.domains || {}).forEach(function (domain) {
      var items = s.domains[domain];
      var card = el('div', { class: 'card', style: 'margin-bottom:0' }, UI.cardHeader(domain));
      if (!items.length) card.appendChild(el('p', { class: 'muted' }, 'No providers registered.'));
      items.forEach(function (p) {
        card.appendChild(el('div', { class: 'status-item' },
          el('span', { class: 'badge ' + (STATE_BADGE[p.state] || 'badge-dim') }, p.state),
          el('b', {}, p.provider),
          el('span', { class: 'spacer' }),
          el('span', { class: 'muted' }, p.detail || '')));
      });
      grid.appendChild(card);
    });
    root.appendChild(grid);
    try {
      var cfg = await API.get('/api/config');
      root.appendChild(el('div', { class: 'card', style: 'margin-top:16px' },
        UI.cardHeader('Configuration'),
        el('div', { class: 'status-item' }, el('b', {}, 'Fees'), el('span', { class: 'spacer' }), el('span', {}, fmtMoney(cfg.feePerContractCents) + ' per contract per leg + ' + fmtMoney(cfg.feePerOrderCents) + ' per order')),
        el('div', { class: 'status-item' }, el('b', {}, 'Port'), el('span', { class: 'spacer' }), el('span', {}, String(cfg.port))),
        el('div', { class: 'status-item' }, el('b', {}, 'Fixtures only'), el('span', { class: 'spacer' }), el('span', {}, String(cfg.fixturesOnly)))));
    } catch (e) { /* optional */ }
  }

  // ---------- 1. Account ----------

  async function account(root) {
    var data = await API.get('/api/account');
    var acct = data.account;
    root.appendChild(el('h1', {}, 'Paper account'));
    root.appendChild(el('div', { class: 'grid grid-4' },
      stat('Cash', fmtMoney(acct.cashCents), 'Practice money available. Nothing here is real dollars.'),
      stat('Reserved', fmtMoney(acct.reservedCents), 'Held aside to cover the worst case of your open trades.'),
      stat('Buying power', fmtMoney(acct.buyingPowerCents), 'Cash minus reserves — what you can still put at risk.'),
      stat('Started with', fmtMoney(acct.startingCashCents))));

    var cashInput = el('input', { type: 'number', id: 'reset-cash', value: Math.round(acct.startingCashCents / 100), min: '1000', step: '1000', style: 'max-width:150px' });
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
                App.navigate('#/account');
              }, true);
          }
        }, 'Reset…'))));

    var rows = (data.ledger || []).map(function (r) {
      return el('tr', {},
        el('td', { class: 'muted' }, r.ts ? r.ts.slice(0, 10) : ''),
        el('td', {}, el('span', { class: 'badge badge-dim' }, r.type)),
        el('td', {}, pnlSpan(r.amountCents)),
        el('td', {}, fmtMoney(r.cashAfterCents)),
        el('td', {}, fmtMoney(r.reservedAfterCents)),
        el('td', { class: 'muted' }, r.memo || ''));
    });
    root.appendChild(el('div', { class: 'card' },
      UI.cardHeader('Recent ledger'),
      explain('Every cash or reserve movement, newest first. The ledger is append-only: nothing is ever erased.'),
      table(['Date', 'Type', 'Amount', 'Cash after', 'Reserved after', 'Memo'], rows)));
  }

  window.Views = {
    home: home,
    welcome: welcome,
    account: account,
    research: research,
    recommend: recommend,
    ticket: ticket,
    portfolio: portfolio,
    trade: tradeDetail,
    backtest: backtest,
    status: status
  };
})();
