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
      el('section', { class: 'hero', id: 'welcome-hero' },
        deco,
        el('div', { class: 'hero-inner' },
          el('div', { class: 'hero-brandline' }, UI.brandMark(30),
            el('span', { class: 'eyebrow' }, 'PAPER TRADING \u00B7 LOCAL-FIRST \u00B7 FREE')),
          el('h1', { class: 'hero-title' }, 'Learn options by ', el('span', { class: 'grad' }, 'doing'), ',', el('br', {}),
            'with honest numbers.'),
          el('p', { class: 'hero-sub' },
            'Screen strategies against your goal, practice with $100,000 in paper money, and backtest honestly \u2014 all on your machine.'),
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
              })))),
      el('section', { class: 'welcome-section welcome-live-col' },
        el('div', { class: 'live-head' },
          el('div', { class: 'eyebrow' }, 'LIVE FROM THE ENGINE'),
          el('button', { class: 'btn-link', id: 'welcome-skip', onclick: function () {
            try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
            App.navigate('#/home');
          } }, 'Skip \u2192 dashboard')),
        el('div', { class: 'showcase' }, liveHost,
          el('p', { class: 'showcase-caption' },
            'A real screened idea \u2014 generated this second, refusals, odds, and assumptions included, like every idea here.')))));
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

    // ---- Two doors, by experience ----
    var LEVEL_CARDS = [
      { level: 'beginner', title: 'Teach me', ic: 'sprout',
        blurb: 'Question-driven flows, plain language \u2014 every idea explains itself.',
        cta: 'Start as a beginner', go: '#/recommend/manual' },
      { level: 'expert', title: 'Give me the terminal', ic: 'bolt',
        blurb: 'Dense tables, greeks, per-leg impact, inline filters.',
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
    var statsAnchor = el('div', { class: 'grid grid-4', id: 'home-stats' });
    var brand = App.state.brand || {};
    root.appendChild(el('section', { class: 'home-hero' },
      el('div', { class: 'home-hero-top' },
        el('div', { class: 'home-hero-text' },
          el('div', { class: 'eyebrow' }, 'PAPER TRADING \u00B7 LOCAL-FIRST \u00B7 HONEST NUMBERS'),
          el('h1', { class: 'home-hero-title' }, 'Learn options by ', el('span', { class: 'grad' }, 'doing'), '.'),
          el('p', { class: 'home-hero-sub' },
            brand.tagline || 'Screen ideas against your goal, practice with paper money, and backtest honestly \u2014 all on your machine.')),
        statsAnchor,
        el('div', { class: 'home-hero-ctas' },
          el('button', { class: 'btn', onclick: function () { App.navigate('#/trade/discover'); } }, 'Find an idea'),
          el('button', {
            class: 'btn btn-ghost', id: 'home-tour-link',
            title: 'The full opening page: how it works, the live engine demo, the two ways to start',
            onclick: function () { App.navigate('#/home/tour'); }
          }, 'Take the tour')))));

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

    var colL = el('div', { class: 'home-col' });
    var colR = el('div', { class: 'home-col' });
    root.appendChild(el('div', { class: 'home-cols' }, colL, colR));
    colR.appendChild(el('div', { id: 'sector-pulse-anchor' }));
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
        explain('Day change of each sector\u2019s ETF proxy. Tap a sector to open its symbols in the explorer.'));
      var anchor = document.getElementById('sector-pulse-anchor');
      if (anchor) anchor.replaceWith(pulse);
    })();


    // Markets: shell now, tiles when the ONE batch-quotes call answers (this used to be
    // eight sequential full-research calls that blocked the whole dashboard).
    var uni = App.state.universe && App.state.universe.active;
    // Four tiles keeps the dashboard on one desktop screen; the sector explorer has the rest
    var marketSymbols = uni && uni.symbols && uni.symbols.length ? uni.symbols.slice(0, 4) : CORE_SYMBOLS;
    var tiles = el('div', { class: 'tile-row' });
    colL.appendChild(el('div', { class: 'card' },
      UI.cardHeader('Markets' + (uni ? ' — ' + uni.label : '')),
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
      quickAction('Research a symbol', 'Quotes, chains, IV vs HV, history, news.', '#/research', 'scope'),
      quickAction('Scout opportunities', 'Momentum, sentiment, and volatility views.', '#/trade/discover', 'compass'),
      quickAction('Build a strategy', 'Multi-leg construction with live risk.', '#/trade/shape', 'target'),
      quickAction('Backtest a strategy', 'How a rule would have behaved.', '#/trade/verify', 'chart')));

    // Wait for the fills so data-ready means READY (tests and users agree on that).
    await Promise.all([marketsFill, tradesFill]);
  }

  function quickAction(title, hint, hash, ic) {
    return el('div', { class: 'tile qa-tile', onclick: function () { App.navigate(hash); } },
      el('div', { class: 'qa-head' },
        el('div', { class: 'icon-tile icon-tile-sm' }, icon(ic || 'compass', 18)),
        el('div', {}, el('div', { class: 't-sym' }, title),
          el('div', { class: 'muted qa-hint' }, hint))));
  }

  // ---------- 2. Research ----------

  async function research(root, params) {
    var symbol = (params[0] || '').toUpperCase();
    var input = el('input', { type: 'text', id: 'symbol-input', placeholder: 'Ticker, e.g. AAPL', value: symbol });
    var go = function () { if (input.value.trim()) App.navigate('#/research/' + input.value.trim().toUpperCase()); };
    input.addEventListener('keydown', function (e) { if (e.key === 'Enter') go(); });

    root.appendChild(el('h1', {}, 'Research'));
    var recentBox = el('span', { class: 'sym-chips', id: 'recent-symbols' });
    root.appendChild(el('div', { class: 'card' },
      el('div', { class: 'btn-row', style: 'margin-top:0' },
        input, el('button', { class: 'btn', id: 'symbol-go', onclick: go }, 'Look up'),
        symbol ? el('a', { href: '#/research', class: 'muted', id: 'back-to-sectors',
          style: 'font-size:12.5px; white-space:nowrap' }, '\u2190 All sectors') : null,
        el('span', { class: 'spacer' }),
        recentBox)));
    function renderRecents() {
      recentBox.innerHTML = '';
      var list = recentSymbols();
      if (!list.length) return;
      recentBox.appendChild(el('span', { class: 'muted', style: 'font-size:12px' }, 'Recent:'));
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
        idxBody.appendChild(await studyToolsSection()); // Stage E: the Lab's STUDY tools live in Research
      })();
      return;
    }

    // PROGRESSIVE PAINT: the shell + independent sections (events, price history, news) render
    // immediately; the hero, "what you can do", and the chain fill when /api/research lands (now a
    // parallelized, faster call). No more all-or-nothing blank page behind one request.
    var heroCard = el('div', { class: 'card', id: 'research-hero' }, UI.spinner('Loading ' + symbol + '…'));
    root.appendChild(heroCard);
    var researchP = API.get('/api/research/' + symbol);

    root.appendChild(comingUp(symbol, false)); // dated events; self-fetches (never waits on the hero call)
    var actionsAnchor = el('div', { id: 'symbol-actions-anchor' }); // filled after r: the action cards, or the no-options warning
    root.appendChild(actionsAnchor);

    // Price history — its own endpoint, independent of /api/research.
    root.appendChild(el('div', { class: 'card', id: 'history-card' },
      UI.cardHeader('Price history'),
      UI.rangeChart({ initial: '1y', fetch: historyFetch(symbol) }),
      explain('Real OHLC candles: green closed up, red closed down; slide across for open/high/low/close, change, and volume. Pills change the window; long windows aggregate to weekly candles.')));

    var chainAnchor = el('div', { id: 'chain-anchor' });
    root.appendChild(chainAnchor);

    // News & filings ALWAYS render (independent endpoint) — a card that silently vanishes reads as
    // "where has news gone?", not as an empty feed.
    var newsCard = el('div', { class: 'card', id: 'news-card' }, UI.cardHeader('News & filings'), UI.spinner('Loading news…'));
    root.appendChild(newsCard);
    (async function fillNews() {
      var newsItems = [];
      try { newsItems = ((await API.get('/api/research/' + symbol + '/news')).items || []); }
      catch (e) { /* empty state below */ }
      newsCard.innerHTML = '';
      newsCard.appendChild(UI.cardHeader('News & filings'));
      if (!newsItems.length) {
        newsCard.appendChild(UI.emptyState('No headlines right now',
          'No news source answered for ' + symbol + '. See the Data screen for per-source health.',
          'Check data status', function () { App.navigate('#/status'); }));
        return;
      }
      var isFiling = function (n) { return /edgar|sec/i.test(n.source || '') || /filing$/i.test(n.headline || ''); };
      var headlines = newsItems.filter(function (n) { return !isFiling(n); }).slice(0, 8);
      var filings = newsItems.filter(isFiling).slice(0, 5);
      var row = function (n) {
        var when = n.publishedEpochMs ? new Date(n.publishedEpochMs).toISOString().slice(0, 10) : '';
        return el('div', { class: 'status-item' },
          el('a', { href: n.url, target: '_blank', rel: 'noopener' }, n.headline),
          el('span', { class: 'spacer' }),
          when ? el('span', { class: 'muted' }, when + ' · ') : null,
          el('span', { class: 'muted' }, n.source));
      };
      headlines.forEach(function (n) { newsCard.appendChild(row(n)); });
      if (filings.length) {
        newsCard.appendChild(el('div', { class: 'field-label', style: 'margin-top:10px' }, 'Corporate filings (SEC)'));
        if (Learn.currentLevel() === 'beginner') {
          newsCard.appendChild(explain('Official documents companies must file: 10-K (annual report), 10-Q (quarterly), 8-K (something important just happened). Big moves often start here.'));
        }
        filings.forEach(function (n) { newsCard.appendChild(row(n)); });
      }
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
            App.state.ideasPrefill = { symbol: symbol }; // lands in the Discover form even over a saved symbol
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
                  App.state.ideasPrefill = { intent: intentKey, symbol: symbol };
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
    }, function (e) {
      if (!App.alive(_rt)) return;
      heroCard.replaceWith(alertBox('danger', 'No data for ' + symbol + '. Check the ticker.'));
      actionsAnchor.remove(); chainAnchor.remove();
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
      return {
        range: hist.range, candles: candles,
        badge: hist.demo ? el('span', { class: 'badge badge-warn' }, 'DEMO DATA') : null,
        note: hist.demo ? explain('This price history is built-in DEMO DATA \u2014 no live candle source is configured. Add a Polygon or Alpha Vantage key for real history.') : null,
        emptyText: candles.length ? null
          : 'No daily price history available for ' + symbol + '. Quotes and option chains are live (Cboe), '
            + 'but historical candles need a free Polygon or Alpha Vantage key \u2014 set POLYGON_API_KEY or '
            + 'ALPHAVANTAGE_API_KEY (or polygon.api.key in strikebench.properties) and restart. '
            + 'The Data screen shows per-source health.'
      };
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
      panel.appendChild(el('div', { class: 'btn-row' },
        el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research/' + symbol); } }, 'Full research'),
        el('button', {
          class: 'btn btn-sm btn-secondary', onclick: function () {
            App.state.ideasPrefill = { symbol: symbol };
            App.navigate('#/recommend/manual');
          }
        }, 'Trade ideas')));
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
    var focus = el('div', { id: 'explorer-focus' });
    var card = el('div', { class: 'card', id: 'sector-explorer' },
      UI.cardHeader('Explore by sector'), rail, head, focus, grid,
      explain('Live quotes per sector. Tap a symbol for its chart and headlines right here, or point the scout at a whole sector. '
        + (u.note ? u.note : '')));
    root.appendChild(card);

    var loadSeq = 0; // supersede token: a slow quote batch for a PREVIOUS sector must not paint over the current one
    async function load() {
      var seq = ++loadSeq;
      head.innerHTML = '';
      focus.innerHTML = '';
      focus.removeAttribute('data-symbol');
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
          App.state.discoverForm = Object.assign({}, App.state.discoverForm || {},
            { universe: sector.symbols.join(','), symbol: '' });
          App.navigate('#/recommend/scout');
        }
      }, 'Scout this sector'));
      try {
        var data = await API.get('/api/quotes?symbols=' + sector.symbols.join(','));
        if (seq !== loadSeq) return; // a newer sector was picked while this loaded
        grid.innerHTML = '';
        // EVERY symbol gets an actionable tile \u2014 a sector click must always land somewhere
        // clickable. Symbols without a live quote say so honestly but still link onward.
        var bySym = {};
        (data.quotes || []).forEach(function (q) { bySym[q.symbol] = q; });
        // Tap a tile -> the full symbol panel (range-pill chart, events, headlines)
        // opens in ONE predictable place: the focus slot above the grid. Mid-grid
        // expansion shoved tiles around and felt random.
        function toggleExpand(symq, tile) {
          var was = focus.getAttribute('data-symbol') === symq;
          focus.innerHTML = '';
          focus.removeAttribute('data-symbol');
          grid.querySelectorAll('.sector-tile.open').forEach(function (t) { t.classList.remove('open'); });
          if (was) return;
          focus.setAttribute('data-symbol', symq);
          tile.classList.add('open');
          focus.appendChild(el('div', { class: 'focus-wrap' },
            el('button', {
              class: 'focus-close', type: 'button', 'aria-label': 'Close ' + symq + ' details',
              onclick: function () { toggleExpand(symq, tile); }
            }, '\u00D7'),
            symbolPanel(symq)));
          focus.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
        sector.symbols.forEach(function (s) {
          var q = bySym[s];
          var last = q ? parseFloat(q.last) : null;
          var prev = q ? parseFloat(q.prevClose) : null;
          var pct = q && prev ? (last - prev) / prev * 100 : null;
          var tile = el('div', { class: 'tile sector-tile clickable' + (q ? '' : ' tile-nodata'),
            title: 'Tap for chart, events and headlines' },
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
              } }, 'Ideas') : null));
          tile.addEventListener('click', function (e) {
            if (e.target.closest('button, a')) return; // the tile's own actions win
            toggleExpand(s, tile);
          });
          grid.appendChild(tile);
        });
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
          App.navigate('#/trade/place');
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
            App.navigate('#/trade/place');
          }
        }, 'Use in trade ticket'),
        el('button', {
          class: 'btn btn-sm btn-secondary', onclick: function () {
            App.state.builderForm = {
              symbol: symbolForTicket || App.state.lastRecommendSymbol,
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
        BACKTESTABLE.indexOf(c.strategy) >= 0 ? el('button', {
          class: 'btn btn-sm btn-secondary', onclick: function () {
            App.state.backtestPrefill = { symbol: symbolForTicket || App.state.lastRecommendSymbol, strategy: c.strategy };
            App.navigate('#/trade/verify');
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
              App.navigate('#/trade/place');
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
        source: m0.symbol ? 'single' : 'scan',
        symbol: m0.symbol || '', target: m0.target || '',
        universe: s0.universe || '', scanTarget: s0.target || '',
        h0: !!s0.h0, hW: s0.hW !== false, hM: s0.hM !== false
      };
    }
    var prefill = App.state.ideasPrefill || {};
    App.state.ideasPrefill = null;
    if (prefill.symbol) { saved.symbol = prefill.symbol; saved.source = 'single'; }
    if (prefill.intent) saved.goal = prefill.intent;
    // Route seeds keep every old link honest: /manual = one-stock mode, /scout = scan mode
    if (params && params[0] === 'manual') saved.source = 'single';
    if (params && params[0] === 'scout') saved.source = 'scan';
    var goal = saved.goal || 'DIRECTIONAL';
    var source = saved.source || 'scan';
    function remember(patch) {
      saved = App.state.discoverForm = Object.assign({}, App.state.discoverForm, patch || {});
    }
    remember({ goal: goal });

    var level = Learn.currentLevel();
    var beginner = level === 'beginner';
    var sym = el('input', { type: 'text', id: 'rec-symbol', list: 'universe-symbols',
      placeholder: 'AAPL',
      value: saved.symbol || App.state.lastRecommendSymbol || 'AAPL' });
    var thesis = el('select', { id: 'rec-thesis' },
      ['bullish', 'bearish', 'neutral', 'volatile'].map(function (t) {
        return el('option', { value: t, selected: t === saved.thesis ? '' : null }, t);
      }));
    var horizons = beginner ? ['week', 'month', 'quarter'] : ['week', 'month', 'quarter', '0DTE'];
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
      id: 'universe-sector', active: uniData ? uniData.active.sectorKey : null,
      onPick: async function (sec) {
        try {
          await API.put('/api/universe', { sector: sec.key });
          await App.refreshUniverse();
          App.render(); // header tape + labels update everywhere
        } catch (e) { alert(e.message); }
      }
    });
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
            remember({ goal: goal });
            intentRow.querySelectorAll('.choice').forEach(function (b) { b.classList.remove('selected'); });
            this.classList.add('selected');
            sync();
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
            App.state.lastRecommendSymbol = s2;
            remember({ symbol: s2 });
            sync();
          }
        }, s2));
      });
    }
    var symField = el('div', { class: 'field' },
      el('label', {}, beginner ? 'Which stock?' : 'Symbol'), sym, symChips);
    // Chart + headlines for the underlying, one tap away — informative, never in the way
    var ctxAnchor = el('div', { id: 'symbol-context' });
    var ctxSym = null;
    function renderSymbolContext() {
      var v = source === 'single' ? sym.value.trim().toUpperCase() : '';
      if (v === ctxSym) return;
      ctxSym = v;
      ctxAnchor.innerHTML = '';
      if (!v) return;
      ctxAnchor.appendChild(UI.expandable('What\u2019s happening with ' + v + ' \u2014 chart, events & news',
        function () { return symbolPanel(v, { noActions: true }); }));
    }
    var thesisField = el('div', { class: 'field' }, el('label', {}, 'Your view'), thesis);
    var targetField = el('div', { class: 'field', style: 'display:none' },
      el('label', { id: 'rec-target-label' }, 'Target price ($/sh)'), target, targetPresets);
    var sharesField = el('div', { class: 'field', style: 'display:none' }, el('label', {}, 'Shares you want'), wantShares);
    var horizonField = el('div', { class: 'field' }, el('label', {}, 'Horizon'), horizon);
    var zeroField = el('div', { class: 'field inline-check', style: 'align-self:end' + (beginner ? ';display:none' : '') },
      allow0, el('label', { for: 'rec-0dte', style: 'text-transform:none;letter-spacing:0;font-size:13.5px;font-weight:500' }, 'Allow same-day (0DTE) expiry'));
    var sectorField = el('div', { class: 'field', style: 'grid-column: 1 / -1' },
      el('label', {}, 'Universe (applies everywhere)'), sectorSel);
    var oneOffField = el('div', { class: 'field', style: 'grid-column: 1 / -1' },
      el('label', {}, 'One-off universe for this scan only'), universe);
    var scanTargetField = el('div', { class: 'field' }, el('label', {}, 'Target profit ($)'), scanTarget);
    var expiryRow = el('div', { class: 'btn-row' },
      el('span', { class: 'muted' }, 'Expirations:'),
      beginner ? null : el('label', { class: 'inline-check' }, h0, ' 0DTE'),
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
      zeroField.style.display = scan || beginner ? 'none' : '';
      sectorField.style.display = scan && source === 'scan' ? '' : 'none';
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
      renderSymbolContext();
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
                  App.state.lastRecommendSymbol = p.symbol;
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
      if (v) App.state.lastRecommendSymbol = v;
      sync();
    });


    goBtn = el('button', {
      class: 'btn', id: 'rec-go', onclick: async function () {
        results.innerHTML = '';
        if (scanMode()) return runScan();
        return runSingle();
      }
    }, 'Find ideas');

    root.appendChild(el('div', { class: 'card' },
      UI.cardHeader('What are you trying to do?',
        el('a', {
          class: 'muted', id: 'all-strategies-link', href: '#/trade/shape', style: 'font-size:12.5px',
          onclick: function () {
            App.state.builderForm = { symbol: App.state.lastRecommendSymbol || 'AAPL', qty: 1,
              goal: 'BROWSE', templateKey: null, step: 2, legIdx: 0, legs: [], excluded: {} };
          }
        }, 'All strategies \u2192')),
      explain('Two questions: what are you trying to do, and where should ideas come from. "Scan the market for me" is the scout — momentum, news sentiment, and IV vs realized volatility across your universe, evidence shown. Only strategies whose worst case is known and capped pass the screens, and refusals are always explained.'),
      intentRow,
      el('div', { class: 'field-label', style: 'margin-top:12px' }, 'Where should ideas come from?'),
      sourceRow,
      el('div', { class: 'form-grid', style: 'margin-top:10px' },
        symField,
        thesisField,
        targetField,
        sharesField,
        horizonField,
        sectorField,
        scanTargetField,
        zeroField,
        oneOffField),
      expiryRow,
      holdingsHint,
      ctxAnchor,
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
        if (h0.checked && !beginner) horizonsSel.push('0DTE');
        if (hW.checked) horizonsSel.push('week');
        if (hM.checked) horizonsSel.push('month');
        var body = { horizons: horizonsSel, riskMode: riskMode(), allow0dte: h0.checked && !beginner };
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
        App.state.lastRecommendSymbol = sym.value.trim().toUpperCase();
        // Beginner never exposes 0DTE (the control is hidden) \u2014 a persisted Expert allow0dte
        // (or a stale '0DTE' horizon) must not leak same-day expiries into Beginner ideas.
        var hz = (beginner && horizon.value === '0DTE') ? 'month' : horizon.value;
        var body = {
          symbol: sym.value.trim(), horizon: hz,
          riskMode: riskMode(), allow0dte: !beginner && allow0.checked, avoidEarnings: true,
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
        chip('Risk budget', fmtMoney(r.riskBudgetCents)),
        chip('Mode', r.riskMode.toLowerCase()),
        chip('Candidates', String(r.candidates.length))));
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
            el('span', {}, el('b', {}, x.displayName), ' \u2014 ', (x.reasons || []).join(' '))));
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
      bar.appendChild(el('span', { class: 'muted' }, 'No working idea yet \u2014 pick one from Ideas or make one in the Builder.'));
    }
    return bar;
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
    else if (stage === 'shape') await Builder.render(root);
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
      // Plain trader language, units in the label, the fine print one hover away.
      // "Worst case ≤ $" IS the per-idea risk budget (the old % -of-account knob merged
      // into it; the header risk mode still sizes ideas when this is blank).
      var f = function (label, input, tip) {
        return el('div', { class: 'field', title: tip }, el('label', {}, label), input);
      };
      node = el('div', { class: 'card compact-filters' },
        el('div', { class: 'form-grid grid-5' },
          f('Chance of profit \u2265 %', minPop,
            'POP — the modeled probability the trade ends with ANY profit. 70 keeps only ideas with at least a 70% chance. Model output, pre-commission.'),
          f('Assignment risk \u2264 %', maxAssign,
            'Probability of ending up assigned shares on the short leg(s). For sell-at-target and buy-at-discount goals assignment IS the point — leave blank there.'),
          f('Income rate \u2265 %/yr', minYield,
            'Share-backed income only (covered calls, cash-secured puts, collars). Annualized so a 2-week and a 2-month trade compare fairly — 0.5% over two weeks is about 13%/yr. Blank = no floor.'),
          f('Cash outlay \u2264 $', maxCost,
            'The most you pay up front (debit trades). Credit trades collect cash instead — cap those with the worst case.'),
          f('Worst case \u2264 $', maxLoss,
            'Hard cap on the modeled maximum loss — your per-idea risk budget in dollars. Blank = sized by the header risk mode.')),
        el('p', { class: 'muted', style: 'margin:6px 0 0; font-size:12px' },
          'Blank = no limit. Hover a label for exactly what it means; refused ideas always say which limit they broke.'));
    }
    node.id = idPrefix + '-filters';
    return {
      node: node,
      value: function () {
        // Only submit filters VISIBLE at the current level. Beginner shows just minPop (+ maxLoss);
        // a persisted Expert value for maxAssign/minYield/maxCost must NOT silently constrain a
        // Beginner's ideas (they're hidden, so they'd be invisible rejections).
        var f = {};
        if (minPop.value) f.minPop = parseFloat(minPop.value) / 100;
        if (level !== 'beginner') {
          if (maxAssign.value) f.maxAssignmentProb = parseFloat(maxAssign.value) / 100;
          if (minYield.value) f.minAnnualizedYieldPct = parseFloat(minYield.value);
          if (maxCost.value) f.maxCostCents = Math.round(parseFloat(maxCost.value) * 100);
        }
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
              App.state.ticket = { candidate: c, symbol: ctx.symbol, step: 5 };
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
            App.state.ticket = { candidate: c, symbol: ctx.symbol, step: 5 };
            App.navigate('#/trade/place');
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

  // ---------- 4. Guided ticket ----------

  // Screening lives in Discover now — Place is purely: Strikes -> Review -> Confirm.
  var STEPS = ['Strikes', 'Review', 'Confirm']; // displayed; internal t.step stays 5/6/7

  async function ticket(root) {
    var t = App.state.ticket = App.state.ticket || {};
    t.symbol = t.symbol || App.state.lastRecommendSymbol || 'AAPL';
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
            var placeBody = Object.assign({}, t.previewReq);
            if (t.recommendationId) placeBody.recommendationId = t.recommendationId; // close the calibration loop
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
        await API.post('/api/positions/' + side,
          { symbol: symInput.value.trim(), shares: parseInt(qty.value, 10) });
        App.render();
      });
  }

  async function portfolio(root, params) {
    // One money home: Positions (holdings + trades) | Activity (the ledger) | Account
    // (starting cash, reset). The old #/account URL lands on the Account section.
    var section = params[0] === 'activity' ? 'activity' : params[0] === 'account' ? 'account' : 'positions';
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
      el('button', { class: section === 'account' ? 'active' : '', id: 'pf-sec-account',
        onclick: function () { App.navigate('#/portfolio/account'); } }, 'Account')));

    if (section === 'activity') {
      root.appendChild(el('div', { class: 'card' },
        UI.cardHeader('Ledger'),
        explain('Every cash or reserve movement, newest first. The ledger is append-only: nothing is ever erased.'),
        table(['Date', 'Type', 'Amount', 'Cash after', 'Reserved after', 'Memo'],
          (acctData.ledger || []).map(function (r) {
            return el('tr', {},
              el('td', { class: 'muted' }, r.ts ? r.ts.slice(0, 10) : ''),
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
      tradesCard.appendChild(tab === 'active'
        ? UI.emptyState('No open practice trades', 'Find a risk-screened idea and practice it — the worst case is known before you commit.', 'Find an idea', function () { App.navigate('#/trade/discover'); })
        : UI.emptyState('Nothing closed yet', 'Closed, settled, and voided trades land here.'));
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
    tradesCard.appendChild(table(['Symbol', 'Strategy', 'Qty', 'Entry credit/debit', 'Max loss', tab === 'active' ? 'Opened' : 'Realized P/L', 'Status'], rows));
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
    // Persist the form per the state-ownership convention: symbol/strategy/window/dte survive nav
    // and level switches instead of resetting to defaults. Prefill (an explicit ask) wins over it.
    var bf = App.state.backtestForm = App.state.backtestForm || {};
    root.appendChild(el('p', { class: 'page-sub' },
      Learn.currentLevel() === 'beginner'
        ? 'Before risking even paper money on a rule like “sell a monthly covered call”, see how it would actually have gone — trade by trade, with honest labels on what is modeled.'
        : 'Replays a strategy rule day by day with no look-ahead — and labels exactly how much of its pricing is modeled.'));
    var sym = el('input', { type: 'text', id: 'bt-symbol', value: prefill.symbol || bf.symbol || App.state.lastRecommendSymbol || 'AAPL', list: 'universe-symbols' });
    // Typing a symbol here makes it the working symbol app-wide (Backtest → Builder carries it).
    sym.addEventListener('input', function () {
      bf.symbol = sym.value;
      var s = sym.value.trim().toUpperCase(); if (s) App.state.lastRecommendSymbol = s;
    });
    // The strategy menu climbs the ladder with the user: Learning sees the beginner
    // families (mirroring the engine's learning risk rank), Expert unlocks defined-risk
    // premium selling, Pro sees everything backtestable.
    var BT_GROUPS = [
      { label: 'Trade a view', families: ['LONG_CALL', 'LONG_PUT', 'DEBIT_CALL_SPREAD', 'DEBIT_PUT_SPREAD',
                                          'LONG_STRADDLE', 'LONG_STRANGLE',
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
    var btDefaultStrat = prefill.strategy || bf.strategy || 'DEBIT_CALL_SPREAD';
    var strat = el('select', { id: 'bt-strategy' },
      BT_GROUPS.map(function (g) {
        var fams = btAllowed ? g.families.filter(function (s) { return btAllowed.indexOf(s) >= 0; }) : g.families;
        if (!fams.length) return null;
        return el('optgroup', { label: g.label }, fams.map(function (s) {
          return el('option', { value: s, selected: s === btDefaultStrat ? '' : null }, prettyStrategy(s));
        }));
      }));
    strat.addEventListener('change', function () { bf.strategy = strat.value; });
    var today = new Date();
    var toDefault = today.toISOString().slice(0, 10);
    var fromDefault = new Date(today.getTime() - 182 * 86400000).toISOString().slice(0, 10);
    var from = el('input', { type: 'date', id: 'bt-from', value: bf.from || fromDefault });
    var to = el('input', { type: 'date', id: 'bt-to', value: bf.to || toDefault });
    var dte = el('input', { type: 'number', id: 'bt-dte', value: bf.dte || '30', min: '1', max: '365' });
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

    // D4: Expert unlocks the PORTFOLIO engine (concurrent positions + mechanical exits) — the deeper
    // tool, not fewer words. Beginner stays on the single-position engine.
    var engine = null;
    if (btLevel !== 'beginner') {
      engine = el('select', { id: 'bt-engine' },
        el('option', { value: 'single' }, 'Single position (one trade at a time)'),
        el('option', { value: 'portfolio' }, 'Portfolio (concurrent positions, mechanical exits)'));
      engine.value = bf.engine || 'single';
      engine.addEventListener('change', function () { bf.engine = engine.value; });
    }
    root.appendChild(el('div', { class: 'card' },
      btLevel === 'beginner'
        ? explain('The strategy list matches your Beginner level — credit spreads, condors, collars and the rest unlock at Expert (the level switch is in the header). Target DTE is how far out each trade’s expiration is: Monthly (30 days) is the classic starting point.')
        : null,
      el('div', { class: 'form-grid' },
        el('div', { class: 'field' }, el('label', {}, 'Symbol'), sym),
        el('div', { class: 'field' }, el('label', {}, 'Strategy'), strat),
        engine ? el('div', { class: 'field' }, el('label', {}, 'Engine'), engine) : null,
        el('div', { class: 'field' }, el('label', {}, 'Target DTE'), dte, dtePresets),
        el('div', { class: 'field' }, el('label', {}, 'Window'), periodRow),
        el('div', { class: 'field' }, el('label', {}, 'From'), from),
        el('div', { class: 'field' }, el('label', {}, 'To'), to)),
      engine ? explain('Portfolio engine currently backtests CREDIT_PUT_SPREAD and DEBIT_CALL_SPREAD (delta-selected strikes, profit-target/stop/time exits, capital-gated concurrency).') : null,
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
          table(['Entry', 'Exit', 'Strategy', 'Credit/Debit', 'P/L', 'Max loss', 'Why closed'],
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
  async function status(root) {
    var level = Learn.currentLevel();
    var token = App.navToken;
    root.appendChild(el('h1', {}, 'Data center'));
    root.appendChild(explain('Where your market data comes from, how fresh it is, and what you can pull in. '
      + brandName() + ' chains several sources per job and falls back gracefully — DEMO DATA means the built-in fixture is serving.'));

    var engineCard = el('div', { class: 'card', id: 'dc-engine' }, UI.spinner('Checking the market engine…'));
    var coverageCard = el('div', { class: 'card', id: 'dc-coverage' }, UI.cardHeader('What data we hold'), UI.spinner('Loading coverage…'));
    var jobsCard = el('div', { class: 'card', id: 'dc-jobs' }, UI.cardHeader('Jobs'), UI.spinner('Loading jobs…'));
    var sourcesCard = el('div', { class: 'card', id: 'dc-sources' }, UI.cardHeader('Data sources'), UI.spinner('Loading sources…'));
    var healthCard = el('div', { class: 'card', id: 'dc-health' });
    var resetCard = el('div', { class: 'card', id: 'dc-reset' });
    [engineCard, coverageCard, jobsCard, sourcesCard, healthCard, resetCard].forEach(function (c) { root.appendChild(c); });

    // --- Jobs (declared first: engine/coverage actions start jobs and refresh this panel) ---
    var jobsTimer = null;
    async function loadJobs() {
      var data;
      try { data = await API.getFresh('/api/data/jobs'); } catch (e) { return; }
      if (!App.alive(token)) { if (jobsTimer) clearTimeout(jobsTimer); return; }
      var jobs = data.jobs || [];
      jobsCard.innerHTML = '';
      jobsCard.appendChild(UI.cardHeader('Jobs', el('button', { class: 'btn btn-sm btn-secondary', onclick: loadJobs }, 'Refresh')));
      if (level === 'beginner') jobsCard.appendChild(explain('Background tasks that fetch or refresh data. Progress and results show here.'));
      if (!jobs.length) { jobsCard.appendChild(UI.emptyState('No jobs yet', 'Warm the engine or backfill history to create one.')); return; }
      var anyRunning = false;
      jobs.forEach(function (j) {
        if (j.status === 'RUNNING' || j.status === 'QUEUED') anyRunning = true;
        var row = el('div', { class: 'dc-job' },
          el('div', { class: 'chip-row', style: 'align-items:center;margin:0' },
            el('span', { class: 'badge ' + (JOB_BADGE[j.status] || 'badge-dim') }, j.status),
            el('b', {}, j.kind),
            el('span', { class: 'muted' }, j.done + '/' + j.total + ' · ' + j.rowsWritten + ' rows'),
            el('span', { class: 'spacer' }),
            (j.status === 'RUNNING' || j.status === 'QUEUED')
              ? el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { API.post('/api/data/jobs/' + j.id + '/cancel', {}).then(loadJobs); } }, 'Cancel')
              : (j.status === 'FAILED' || j.status === 'CANCELLED')
                ? el('button', { class: 'btn btn-sm btn-secondary', onclick: function () { API.post('/api/data/jobs/' + j.id + '/retry', {}).then(loadJobs); } }, 'Retry')
                : null),
          dcProgress(j.done, j.total),
          j.message ? el('div', { class: 'muted small' }, j.message) : (j.error ? el('div', { class: 'loss small' }, j.error) : null));
        jobsCard.appendChild(row);
      });
      if (anyRunning && App.alive(token)) jobsTimer = setTimeout(loadJobs, 2000); // live progress while a job runs
    }

    function startJob(kind, params) {
      return API.post('/api/data/jobs', { kind: kind, params: params || {} }).then(function () { loadJobs(); });
    }

    // --- Engine status ---
    (async function fillEngine() {
      var ov;
      try { ov = await API.get('/api/data/overview'); } catch (e) {
        if (!App.alive(token)) return;
        engineCard.innerHTML = ''; engineCard.appendChild(UI.cardHeader('Market engine'));
        engineCard.appendChild(alertBox('warn', 'Engine status unavailable.')); return;
      }
      if (!App.alive(token)) return;
      var e = ov.engine || {};
      engineCard.innerHTML = '';
      engineCard.appendChild(UI.cardHeader('Market engine',
        el('button', { class: 'btn btn-sm', id: 'dc-refresh-now', onclick: function () { startJob('refresh_now', {}); } }, 'Refresh now')));
      if (ov.fixturesOnly) engineCard.appendChild(alertBox('warn', 'Fixtures-only mode: all data is simulated demo data.'));
      if (level === 'beginner') engineCard.appendChild(explain('An in-memory feed keeps your tickers fresh in the background so screens load instantly, instead of downloading on every click.'));
      engineCard.appendChild(el('div', { class: 'chip-row' },
        chip('Market', ov.marketOpen ? 'Open' : 'Closed'),
        chip('Warmed', (e.warmed || 0) + ' / ' + (e.tracked || 0)),
        chip('Refreshing', String(e.inFlight || 0)),
        chip('Stale', String(e.stale || 0)),
        chip('Refresh every', (e.refreshInterval || 0) + 's'),
        chip('Avg latency', (e.avgLatencyMs || 0) + ' ms'),
        e.errors ? chip('Errors', String(e.errors)) : null));
      if (level === 'expert' && e.symbols && e.symbols.length) {
        engineCard.appendChild(UI.expandable('Per-symbol engine state', function () {
          return table(['Symbol', 'Fresh', 'Source', 'Age', 'State'], e.symbols.map(function (s) {
            return el('tr', {},
              el('td', {}, el('b', {}, s.symbol)),
              el('td', {}, badge(s.freshness)),
              el('td', { class: 'muted' }, s.source || '—'),
              el('td', { class: 'muted' }, s.ageMs >= 0 ? Math.round(s.ageMs / 1000) + 's' : '—'),
              el('td', {}, s.refreshing ? el('span', { class: 'badge badge-caution' }, 'refreshing') : (s.error ? el('span', { class: 'badge badge-danger', title: s.error }, 'error') : el('span', { class: 'muted' }, 'ok'))));
          }));
        }));
      }
    })();

    // --- Coverage ---
    (async function fillCoverage() {
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
        el('button', { class: 'btn btn-sm', id: 'dc-backfill', title: 'Pull daily history for your universe',
          onclick: function () { startJob('backfill_underlying', { symbols: uni, years: 5 }); } }, 'Backfill history')));
      if (level === 'beginner') coverageCard.appendChild(explain('The price history we’ve stored, and whether it’s real (observed) or demo. Backfill pulls daily history for your universe so backtests use real data.'));
      coverageCard.appendChild(el('div', { class: 'chip-row' },
        chip('Underlying symbols', String(sum.underlyingSymbols || 0)),
        chip('Daily bars', String(sum.underlyingBars || 0)),
        chip('Option symbols', String(sum.optionSymbols || 0)),
        chip('Option rows', String(sum.optionRows || 0)),
        chip('Observed', (sum.observedUnderlyingSymbols || 0) + ' symbols')));
      if (!syms.length) {
        coverageCard.appendChild(UI.emptyState('No stored history yet',
          'Backfill history (above) to store daily bars. In live mode set YAHOO_ENABLED, or a Polygon/Alpha Vantage key, for real data.'));
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
          el('td', { class: 'muted' }, s.optionRows ? (s.optionDays + ' days / ' + s.optionRows + ' rows') : '—'));
      });
      coverageCard.appendChild(table(['Symbol', 'Underlying range', 'Evidence', 'Bars', 'Options'], rows));
    })();

    // --- Sources ---
    (async function fillSources() {
      var data;
      try { data = await API.get('/api/data/sources'); } catch (e) {
        if (!App.alive(token)) return;
        sourcesCard.innerHTML = ''; sourcesCard.appendChild(UI.cardHeader('Data sources'));
        sourcesCard.appendChild(alertBox('warn', 'Sources unavailable.')); return;
      }
      if (!App.alive(token)) return;
      sourcesCard.innerHTML = '';
      sourcesCard.appendChild(UI.cardHeader('Data sources'));
      if (level === 'beginner') sourcesCard.appendChild(explain('Where data can come from. Free/keyless sources work out of the box; keyed and licensed ones unlock more history. Each shows how it may be used.'));
      var grid = el('div', { class: 'grid grid-2' });
      (data.sources || []).forEach(function (s) {
        grid.appendChild(el('div', { class: 'dc-source' + (s.enabled ? ' on' : '') },
          el('div', { class: 'chip-row', style: 'align-items:center;margin:0' },
            el('span', { class: 'badge ' + (s.enabled ? 'badge-ok' : 'badge-dim') }, s.enabled ? 'ON' : 'off'),
            el('b', {}, s.name),
            el('span', { class: 'spacer' }),
            el('span', { class: 'muted small' }, s.covers)),
          el('div', { class: 'muted small', style: 'margin-top:4px' }, s.license),
          el('div', { class: 'small', style: 'margin-top:4px' }, s.hint)));
      });
      sourcesCard.appendChild(grid);
    })();

    // --- Provider health (the old status view, kept as detail) ---
    (async function fillHealth() {
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
      healthCard.appendChild(level === 'beginner' ? UI.expandable('Per-source status', function () { return body; }) : body);
    })();

    // --- Reset (danger) ---
    (function fillReset() {
      resetCard.innerHTML = '';
      resetCard.appendChild(UI.cardHeader('Reset data'));
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
    })();

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
    App.state.ticket = { candidate: c, symbol: symbol, step: 5, recommendationId: recId || null };
    App.navigate('#/trade/place');
  }

  function decisionTop(e, symbol, level, recId) {
    var c = e.candidate, risk = e.risk, capital = e.capital, sc = e.score;
    var card = el('div', { class: 'card decision-pick', 'data-strategy': c.strategy });
    card.appendChild(UI.cardHeader(
      el('span', {}, el('span', { class: 'pick-badge' }, 'THE PICK'), ' ', c.displayName),
      el('span', { class: 'row-gap' }, evidenceBadge(e.evidence.rollup), UI.scoreBar(sc.riskAdjustedScore))));
    if (e.explanation && (e.explanation.whySelected || e.explanation.headline)) {
      card.appendChild(el('p', { class: 'decision-why' }, e.explanation.whySelected || e.explanation.headline));
    }
    card.appendChild(el('div', { class: 'chip-row' },
      chip('Cost/credit', fmtMoney(c.entryNetPremiumCents, { plus: true })),
      chip('Max loss', el('span', { class: 'loss' }, fmtMoney(risk.maxLossCents))),
      chip('Max profit', risk.maxProfitCents === null || risk.maxProfitCents === undefined
        ? 'uncapped' : el('span', { class: 'gain' }, fmtMoney(risk.maxProfitCents))),
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
        el('div', { class: 'alt-main' }, el('b', {}, c.displayName), el('span', { class: 'muted' }, '  ' + c.label)),
        el('div', { class: 'alt-facts' },
          chip('Score', Math.round(e.score.riskAdjustedScore)),
          chip('Max loss', el('span', { class: 'loss' }, fmtMoney(e.risk.maxLossCents)))),
        el('button', { class: 'btn btn-sm', onclick: function () { useEval(c, symbol); } }, 'Use'));
    }));
  }

  function decisionTable(evals, symbol) {
    var rows = evals.map(function (e) {
      var c = e.candidate, r = e.risk;
      return el('tr', { 'data-strategy': c.strategy },
        el('td', {}, c.displayName),
        el('td', {}, UI.scoreBar(e.score.riskAdjustedScore)),
        el('td', {}, evidenceBadge(e.evidence.rollup)),
        el('td', {}, fmtMoney(c.entryNetPremiumCents, { plus: true })),
        el('td', {}, el('span', { class: 'loss' }, fmtMoney(r.maxLossCents))),
        el('td', {}, r.maxProfitCents == null ? 'uncapped' : fmtMoney(r.maxProfitCents)),
        el('td', {}, fmtPct(r.pop)),
        el('td', {}, el('span', { class: 'loss' }, fmtMoney(r.tailLossCents))),
        el('td', {}, el('button', { class: 'btn btn-sm', onclick: function () { useEval(c, symbol); } }, 'Use')));
    });
    return el('div', { id: 'decision-table' },
      UI.table(['Structure', 'Score', 'Evidence', 'Cost', 'Max loss', 'Max profit', 'POP', 'Tail loss', ''], rows));
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
    var cacheKey = JSON.stringify(body);
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
        promise = API.post('/api/evaluate', body);
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
      host.appendChild(level === 'beginner' ? decisionAltList(evals.slice(1), symbol) : decisionTable(evals, symbol));
    }
    // The portfolio optimizer — "size the strongest ideas across a budget" — lives with the
    // competition it operates on (construction belongs in the Trade family, not a separate Lab).
    App.state.labForm = App.state.labForm || {};
    var octx = App.state.labForm.ctx = App.state.labForm.ctx || {};
    host.appendChild(el('h2', { class: 'section-h' }, 'Size the strongest ideas across a budget'));
    host.appendChild(optimizerCard(level, octx));
  }

  async function decision(root, params) {
    var level = Learn.currentLevel();
    var form = App.state.discoverForm || {};
    var symbol = (params[0] || form.symbol || App.state.lastRecommendSymbol || '').toUpperCase();

    root.appendChild(el('h1', {}, 'Compare ideas'));
    if (level === 'beginner') {
      root.appendChild(explain('The same goal, a few different ways — scored side by side. The top one is our pick; we show WHY it wins, what could go wrong, and how to manage it after you enter.'));
    }
    if (!symbol) {
      root.appendChild(UI.emptyState('No stock chosen yet', 'Pick a stock and a goal first, then compare the ideas.',
        'Find ideas', function () { App.navigate('#/trade/discover'); }));
      return;
    }
    root.appendChild(el('div', { class: 'idea-source-row' },
      el('span', { class: 'muted' }, 'For '), el('b', {}, symbol),
      el('button', { class: 'btn btn-sm btn-secondary', id: 'decision-refresh', onclick: function () { App.state.decisionCache = null; App.render(); } }, 'Refresh')));

    var host = el('div', { id: 'decision-host' });
    root.appendChild(host);
    // Detached: renderCompetition fills the captured host, so the route returns immediately and
    // a new navigation is never trapped behind the evaluation call.
    renderCompetition(host, symbol).catch(function () { /* handled inside */ });
  }

  // ---- Research lab (Phase 5): optimizer, hypothesis tester, ETF replicator, notebook ----
  // Built to the same design language as Trade/Research: a hero band, ONE shared research
  // context every tool reads from, conclusion-first results (the answer leads), stat/fact
  // tiles, icon-tile accents, and interactive SVG charts.

  var LAB_PALETTE = ['#2f6bde', '#7c4fe0', '#3aa76d', '#e0912f', '#d64545', '#2ba3b8', '#b8556f', '#6b8e23'];
  function labColor(i) { return LAB_PALETTE[i % LAB_PALETTE.length]; }

  function labField(label, input) {
    return el('div', { class: 'field' }, el('label', {}, label), input);
  }

  /** A stat/fact tile (label over value), matching the tiles the recommendation pages use. */
  function labFact(label, value, cls) {
    return el('div', { class: 'fact' + (cls ? ' ' + cls : '') },
      el('div', { class: 'f-label' }, label),
      el('div', { class: 'f-value' }, value));
  }

  /** Hero band for the lab — same DNA as the home hero (eyebrow + gradient title + sub). */
  function labHero(level) {
    return el('section', { class: 'lab-hero' },
      el('div', { class: 'lab-hero-text' },
        el('div', { class: 'eyebrow' }, 'RESEARCH LAB'),
        el('h1', { class: 'lab-hero-title' }, 'Research before you ', el('span', { class: 'grad' }, 'risk'), '.'),
        el('p', { class: 'lab-hero-sub' }, level === 'beginner'
          ? 'Four honest tools: build a diversified portfolio, test whether a signal is real, replicate an exposure for less capital, and keep your notes.'
          : 'Portfolio construction, signal-significance testing, capital-efficient replication, and a notebook — all on the same evidence the recommendations use.')),
      el('div', { class: 'lab-hero-tools' },
        [['grid', 'Portfolio'], ['magnifier', 'Signal test'], ['coins', 'Replicate'], ['pen', 'Notebook']].map(function (t) {
          return el('span', { class: 'lab-hero-chip' },
            el('span', { class: 'icon-tile icon-tile-sm' }, icon(t[0])), el('b', {}, t[1]));
        })));
  }

  /**
   * ONE shared research context the four tools default from: the working stock (feeds the
   * signal test + replicator), the account size (feeds the optimizer budget), and — at Expert —
   * the history window (feeds the signal test). Editing it live-syncs the tool inputs without a
   * re-render, so in-progress results are never wiped.
   */
  function labContextBar(ctx, level) {
    var sym = el('input', { type: 'text', id: 'lab-ctx-sym', list: 'universe-symbols',
      value: ctx.symbol || App.state.lastRecommendSymbol || 'AAPL' });
    var acct = el('input', { type: 'number', id: 'lab-ctx-acct', min: '0', step: '1000',
      value: ctx.accountCents ? Math.round(ctx.accountCents / 100) : 25000 });
    ctx.symbol = (sym.value || '').toUpperCase();
    ctx.accountCents = Math.round((+acct.value || 0) * 100);
    sym.addEventListener('input', function () {
      ctx.symbol = sym.value.trim().toUpperCase();
      if (ctx.symbol) App.state.lastRecommendSymbol = ctx.symbol;
      ['lab-hyp-sym', 'lab-rep-sym'].forEach(function (id) { var e = document.getElementById(id); if (e) e.value = ctx.symbol; });
    });
    acct.addEventListener('input', function () {
      ctx.accountCents = Math.round((+acct.value || 0) * 100);
      var b = document.getElementById('lab-budget'); if (b) b.value = +acct.value || 0;
    });
    var fields = [labField('Working stock', sym), labField('Account size ($)', acct)];
    if (level === 'expert') {
      var from = el('input', { type: 'date', id: 'lab-ctx-from', value: ctx.from || '2023-01-01' });
      var to = el('input', { type: 'date', id: 'lab-ctx-to', value: ctx.to || new Date().toISOString().slice(0, 10) });
      ctx.from = from.value; ctx.to = to.value;
      from.addEventListener('change', function () { ctx.from = from.value; });
      to.addEventListener('change', function () { ctx.to = to.value; });
      fields.push(labField('History from', from), labField('to', to));
    }
    return el('div', { class: 'card lab-context' },
      el('div', { class: 'lab-context-head' },
        el('span', { class: 'eyebrow' }, 'SHARED RESEARCH CONTEXT'),
        el('span', { class: 'muted small' }, 'Every tool below starts here.')),
      el('div', { class: 'form-grid lab-context-grid' }, fields));
  }

  /** A card header with an icon-tile accent, like the recommendation surfaces. */
  function labHeader(iconName, title, right) {
    return UI.cardHeader(
      el('span', { class: 'lab-title' }, el('span', { class: 'icon-tile icon-tile-sm' }, icon(iconName)), el('b', {}, title)),
      right || null);
  }

  /** Horizontal stacked composition bar (SVG, hover per segment) + a value legend. */
  function compositionChart(segments) {
    var total = segments.reduce(function (s, x) { return s + Math.max(0, x.value); }, 0) || 1;
    var x = 0, rects = '';
    segments.forEach(function (s, i) {
      var w = Math.max(0, s.value) / total * 100;
      rects += '<rect x="' + x.toFixed(3) + '" y="0" width="' + Math.max(0, w - 0.4).toFixed(3) + '" height="22" rx="1.5" fill="'
        + labColor(i) + '"><title>' + s.label + ' — ' + fmtMoney(s.value) + ' (' + w.toFixed(0) + '%)</title></rect>';
      x += w;
    });
    var svg = '<svg viewBox="0 0 100 22" preserveAspectRatio="none" width="100%" height="22" role="img" aria-label="Allocation by symbol">' + rects + '</svg>';
    var legend = el('div', { class: 'lab-legend' }, segments.map(function (s, i) {
      return el('span', { class: 'lab-legend-item' },
        el('span', { class: 'lab-swatch', style: 'background:' + labColor(i) }),
        el('b', {}, s.label), ' ', el('span', { class: 'muted' }, fmtMoney(s.value)));
    }));
    return el('div', {}, el('div', { class: 'lab-chart', html: svg }), legend);
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
    return el('div', {}, el('div', { class: 'lab-chart', html: svg }),
      caption ? el('div', { class: 'muted small' }, caption) : null);
  }

  // ---- Optimizer ----
  function optimizerCard(level, ctx) {
    var f = App.state.labForm.opt = App.state.labForm.opt || {};
    var defaultBudget = f.budget || (ctx && ctx.accountCents ? Math.round(ctx.accountCents / 100) : 25000);
    var card = el('div', { class: 'card lab-card lab-optimizer' });
    card.appendChild(labHeader('grid', 'Build a portfolio'));
    card.appendChild(explain('Allocate your account across the strongest ideas in your universe — diversified by symbol and capped per position. Only ideas that pass the risk screens are funded.'));

    var budget = el('input', { type: 'number', id: 'lab-budget', value: defaultBudget, min: '0', step: '1000' });
    var goal = el('select', { id: 'lab-goal' },
      el('option', { value: '' }, 'Any goal'), el('option', { value: 'INCOME' }, 'Income'),
      el('option', { value: 'DIRECTIONAL' }, 'Directional'));
    if (f.goal) goal.value = f.goal;
    // Scan scope: honor the shared context's Working stock — "my whole universe" or just that stock.
    var scope = el('select', { id: 'lab-opt-scope' },
      el('option', { value: 'universe' }, 'My whole universe'),
      el('option', { value: 'symbol' }, 'Just the working stock'));
    scope.value = f.scope || 'universe';
    var fields = [labField('Budget ($)', budget), labField('Ideas from', scope), labField('Goal', goal)];
    var objective = null, maxPos = null, maxSym = null;
    if (level === 'expert') {
      objective = el('select', { id: 'lab-obj' }, el('option', { value: 'score' }, 'Best score'), el('option', { value: 'ev' }, 'Best expected value'));
      if (f.objective) objective.value = f.objective;
      maxPos = el('input', { type: 'number', id: 'lab-maxpos', value: f.maxPos || 8, min: '1', max: '20' });
      maxSym = el('input', { type: 'number', id: 'lab-maxsym', value: f.maxSym || 40, min: '5', max: '100', step: '5' });
      fields.push(labField('Rank by', objective), labField('Max positions', maxPos), labField('Max % per symbol', maxSym));
    }
    card.appendChild(el('div', { class: 'form-grid' }, fields));

    // Expert-only diagnostic mode: normally the optimizer funds ONLY positive-expected-value ideas
    // (it must not present a money-losing portfolio as an answer). Diagnostic mode surfaces the
    // least-bad set anyway, clearly labeled — for inspecting a weak universe, not for acting on.
    var diag = null;
    if (level === 'expert') {
      diag = el('input', { type: 'checkbox', id: 'lab-opt-diag' });
      if (f.diagnostic) diag.checked = true;
      card.appendChild(el('label', { class: 'lab-check' }, diag,
        el('span', {}, ' Diagnostic mode — show the least-bad set even if no idea has positive expected value')));
    }

    var out = el('div', { id: 'lab-opt-out', class: 'lab-out' });
    var run = el('button', { class: 'btn', id: 'lab-opt-run' }, 'Build portfolio');
    run.addEventListener('click', async function () {
      f.budget = +budget.value; f.goal = goal.value; f.scope = scope.value;
      if (objective) { f.objective = objective.value; f.maxPos = +maxPos.value; f.maxSym = +maxSym.value; }
      if (diag) f.diagnostic = diag.checked;
      run.disabled = true; out.innerHTML = ''; out.appendChild(UI.spinner('Scanning the universe and allocating…'));
      var sym = (ctx && ctx.symbol) || (App.state.lastRecommendSymbol || '').toUpperCase();
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
    out.appendChild(el('div', { class: 'lab-verdict' },
      el('b', {}, lead + allocs.length + ' position' + (allocs.length === 1 ? '' : 's')
        + ' across ' + nSym + ' symbol' + (nSym === 1 ? '' : 's') + ' for ' + fmtMoney(o.capitalUsedCents) + '.'),
      ' Expected value ', pnlSpan(o.expectedValueCents),
      ', worst-case tail ', el('span', { class: 'loss' }, fmtMoney(-Math.abs(o.totalTailLossCents || 0))), '.'));

    out.appendChild(el('div', { class: 'fact-grid', id: 'lab-opt-summary' },
      labFact('Capital used', fmtMoney(o.capitalUsedCents)),
      labFact('Positions', String(allocs.length)),
      labFact('Expected value', pnlSpan(o.expectedValueCents), o.expectedValueCents >= 0 ? 'f-ok' : 'f-danger'),
      labFact('Tail risk', el('span', { class: 'loss' }, fmtMoney(-Math.abs(o.totalTailLossCents || 0))), 'f-danger'),
      labFact('Avg score', String(Math.round(o.avgScore || 0)))));

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
  function hypothesisCard(level, ctx) {
    var f = App.state.labForm.hyp = App.state.labForm.hyp || {};
    var card = el('div', { class: 'card lab-card' });
    card.appendChild(labHeader('magnifier', 'Test an idea'));
    card.appendChild(explain('Does a signal actually predict what it claims, or is it just chance? We replay it over history and give an honest verdict.'));

    // The shared context is the owner — it wins over a stale per-tool run symbol on re-render.
    var symbol = el('input', { type: 'text', id: 'lab-hyp-sym', value: (ctx && ctx.symbol) || f.symbol || App.state.lastRecommendSymbol || 'AAPL', list: 'universe-symbols' });
    var fields = [labField('Stock', symbol)];
    var lookback = null, threshold = null, forward = null;
    if (level === 'expert') {
      lookback = el('input', { type: 'number', id: 'lab-hyp-lb', value: f.lookback || 20, min: '1', max: '250' });
      threshold = el('input', { type: 'number', id: 'lab-hyp-th', value: f.threshold == null ? 0 : f.threshold, step: '1' });
      forward = el('input', { type: 'number', id: 'lab-hyp-fw', value: f.forward || 10, min: '1', max: '120' });
      fields.push(labField('Momentum look-back (days)', lookback),
        labField('Trigger threshold (%)', threshold), labField('Hold forward (days)', forward));
    } else {
      card.appendChild(el('div', { class: 'muted small' }, 'Using a 20-day up-momentum signal, held 10 days.'));
    }
    card.appendChild(el('div', { class: 'form-grid' }, fields));

    var out = el('div', { id: 'lab-hyp-out', class: 'lab-out' });
    var run = el('button', { class: 'btn', id: 'lab-hyp-run' }, 'Run the test');
    run.addEventListener('click', async function () {
      f.symbol = symbol.value.toUpperCase();
      if (lookback) { f.lookback = +lookback.value; f.threshold = +threshold.value; f.forward = +forward.value; }
      run.disabled = true; out.innerHTML = ''; out.appendChild(UI.spinner('Replaying history…'));
      var to = (ctx && ctx.to) || new Date().toISOString().slice(0, 10);
      var body = {
        symbol: symbol.value, from: (ctx && ctx.from) || '2023-01-01', to: to,
        lookbackDays: lookback ? +lookback.value : 20,
        thresholdPct: threshold ? +threshold.value : 0,
        forwardDays: forward ? +forward.value : 10
      };
      try { var _d = await API.post('/api/lab/hypothesis', body); f.result = _d; renderHypothesis(out, _d, level); }
      catch (e) { out.innerHTML = ''; out.appendChild(alertBox('danger', 'Test failed', [String((e && e.message) || e)])); }
      finally { run.disabled = false; }
    });
    card.appendChild(el('div', { class: 'btn-row' }, run));
    card.appendChild(out);
    if (f.result) renderHypothesis(out, f.result, level); // survive a level flip / nav
    return card;
  }

  function renderHypothesis(out, r, level) {
    out.innerHTML = '';
    // Conclusion first: the verdict banner IS the answer.
    var kind = r.significant ? (r.winRate > 0.5 ? 'ok' : 'danger') : (r.sample < 20 ? 'warn' : 'caution');
    out.appendChild(alertBox(kind, r.verdict));
    out.appendChild(el('p', { class: 'muted small' }, r.hypothesis));
    out.appendChild(gaugeChart(r.winRate, 0.5,
      'Win rate ' + fmtPct(r.winRate) + ' vs 50% by chance — over ' + r.sample + ' signals'));
    var facts = el('div', { class: 'fact-grid' },
      labFact('Signals', String(r.sample)),
      labFact('Wins', String(r.wins), r.wins > r.sample - r.wins ? 'f-ok' : null),
      labFact('Edge', (r.edgePct >= 0 ? '+' : '') + r.edgePct + ' pts', r.edgePct >= 0 ? 'f-ok' : 'f-danger'));
    if (level === 'expert') facts.appendChild(labFact('z-score', String(r.zScore)));
    out.appendChild(facts);
    (r.notes || []).forEach(function (n) { out.appendChild(el('div', { class: 'muted small' }, n)); });
  }

  // ---- ETF / exposure replicator ----
  function replicateCard(level, ctx) {
    var f = App.state.labForm.rep = App.state.labForm.rep || {};
    var card = el('div', { class: 'card lab-card' });
    card.appendChild(labHeader('coins', 'Replicate an exposure'));
    card.appendChild(explain('Get the price exposure of owning shares — for far less capital — with a synthetic options position.'));

    var symbol = el('input', { type: 'text', id: 'lab-rep-sym', value: (ctx && ctx.symbol) || f.symbol || 'SPY', list: 'universe-symbols' });
    var target = el('input', { type: 'number', id: 'lab-rep-tgt', value: f.target || 50000, min: '0', step: '1000' });
    var dir = el('select', { id: 'lab-rep-dir' }, el('option', { value: 'long' }, 'Bullish (long)'), el('option', { value: 'short' }, 'Bearish (short)'));
    dir.value = f.dir || 'long';
    card.appendChild(el('div', { class: 'form-grid' },
      labField('Underlying', symbol), labField('Target exposure ($)', target), labField('Direction', dir)));

    var out = el('div', { id: 'lab-rep-out', class: 'lab-out' });
    var run = el('button', { class: 'btn', id: 'lab-rep-run' }, 'Size it');
    run.addEventListener('click', async function () {
      f.symbol = symbol.value.toUpperCase(); f.target = +target.value; f.dir = dir.value;
      run.disabled = true; out.innerHTML = ''; out.appendChild(UI.spinner('Sizing…'));
      var body = { symbol: symbol.value, targetExposureCents: Math.round((+target.value || 0) * 100), bullish: dir.value === 'long' };
      try { var _d = await API.post('/api/lab/replicate', body); f.result = _d; renderReplication(out, _d); }
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
    if (!r.contracts) { out.appendChild(alertBox('warn', (r.notes && r.notes[0]) || 'Could not size a replication.')); return; }
    out.appendChild(el('p', { class: 'decision-why' }, r.structure));
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
        class: 'btn btn-sm', id: 'lab-rep-build', onclick: function () {
          App.state.lastRecommendSymbol = (r.symbol || '').toUpperCase();
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
    var card = el('div', { class: 'card lab-card lab-notebook' });
    var newBtn = el('button', { class: 'btn btn-sm', id: 'lab-note-new' }, 'New note');
    card.appendChild(labHeader('pen', 'Your research notes', newBtn));
    var list = el('div', { id: 'lab-notes', class: 'lab-out' });
    card.appendChild(list);

    async function reload() {
      list.innerHTML = ''; list.appendChild(UI.spinner('Loading notes…'));
      try {
        var notes = (await API.get('/api/lab/notes')).notes || [];
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
      labField('Title', title), labField('Notes', body), labField('Tags', tags));
    var save = el('button', { class: 'btn btn-sm' }, 'Save');
    save.addEventListener('click', async function () {
      save.disabled = true;
      try {
        if (n && n.id) await API.put('/api/lab/notes/' + n.id, { title: title.value, body: body.value, tags: tags.value });
        else await API.post('/api/lab/notes', { title: title.value, body: body.value, tags: tags.value });
        await reload();
      } catch (e) { save.disabled = false; wrap.appendChild(alertBox('danger', String((e && e.message) || e))); }
    });
    var row = el('div', { class: 'btn-row' }, save);
    if (n && n.id) {
      var del = el('button', { class: 'btn btn-sm btn-danger' }, 'Delete');
      del.addEventListener('click', async function () {
        del.disabled = true;
        try { await API.del('/api/lab/notes/' + n.id); await reload(); }
        catch (e) { del.disabled = false; wrap.appendChild(alertBox('danger', 'Could not delete', [String((e && e.message) || e)])); }
      });
      row.appendChild(del);
    }
    wrap.appendChild(row);
    return wrap;
  }

  async function lab(root) {
    App.state.labForm = App.state.labForm || {};
    var level = Learn.currentLevel();
    var ctx = App.state.labForm.ctx = App.state.labForm.ctx || {};
    root.appendChild(labHero(level));
    root.appendChild(labContextBar(ctx, level));
    var grid = el('div', { class: 'lab-grid' });
    root.appendChild(grid);
    grid.appendChild(optimizerCard(level, ctx));   // spans full width on desktop
    grid.appendChild(hypothesisCard(level, ctx));
    grid.appendChild(replicateCard(level, ctx));
    grid.appendChild(await notebookCard());        // spans full width on desktop
  }

  /** The Lab's STUDY tools (signal test + notebook), rendered inside Research where studying lives. */
  async function studyToolsSection() {
    App.state.labForm = App.state.labForm || {};
    var level = Learn.currentLevel();
    var ctx = App.state.labForm.ctx = App.state.labForm.ctx || {};
    if (!ctx.symbol) ctx.symbol = App.state.lastRecommendSymbol || 'AAPL';
    var wrap = el('div', { id: 'research-study-tools' });
    wrap.appendChild(el('h2', { class: 'section-h' }, 'Research tools'));
    if (level === 'beginner') wrap.appendChild(explain('Test whether a price signal is real before you trust it, and keep notes on what you learn.'));
    var grid = el('div', { class: 'lab-grid' });
    grid.appendChild(hypothesisCard(level, ctx));
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
    decision: decision,
    lab: lab
  };
})();
