/*
 * StrikeBench strategy builder: broker-grade template catalog + unlimited leg editor +
 * a live risk panel that re-prices on every change. One structure for every experience
 * level — the ladder changes how much is explained, never what is shown. Attached to
 * window.Builder, rendered by views.js under the Ideas "Build a strategy" tab.
 */
(function () {
  'use strict';

  var el = UI.el, fmtMoney = UI.fmtMoney, fmtPct = UI.fmtPct, explain = UI.explain,
      chip = UI.chip, stat = UI.stat, alertBox = UI.alertBox, icon = UI.icon;

  var OUTLOOK = {
    bull: ['Bullish', 'badge-ok'], bear: ['Bearish', 'badge-danger'],
    neutral: ['Neutral', 'badge-dim'], vol: ['Big move', 'badge-warn'],
    income: ['Income', 'badge-ok'], protect: ['Protection', 'badge-caution']
  };

  /**
   * Template catalog. Each build(ctx) returns concrete legs from live chain strikes:
   * ctx.pick(exp, offset) = the strike `offset` steps from at-the-money (negative = below).
   * ctx.near / ctx.far are default expirations (~1 month / ~2-3 months out).
   * Templates the guardrails will refuse (undefined risk) are still listed — watching the
   * BLOCK arrive with its payoff cliff is the lesson — and labeled up front.
   */
  var TEMPLATES = [
    { key: 'LONG_CALL', group: 'Directional — bullish', name: 'Long call', outlook: 'bull', family: 'LONG_CALL',
      blurb: 'Stock up big — pay a premium, uncapped upside, lose at most the premium.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near)]; } },
    { key: 'DEBIT_CALL_SPREAD', group: 'Directional — bullish', name: 'Bull call spread', outlook: 'bull', family: 'DEBIT_CALL_SPREAD',
      blurb: 'Stock up some — cheaper than a call because you sell away the far upside.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'CREDIT_PUT_SPREAD', group: 'Directional — bullish', name: 'Bull put spread', outlook: 'bull', family: 'CREDIT_PUT_SPREAD',
      blurb: 'Stock NOT down — collect a credit now, keep it if price stays above your short strike.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near), leg('BUY', 'PUT', c.pick(c.near, -4), c.near)]; } },
    { key: 'CASH_SECURED_PUT', group: 'Directional — bullish', name: 'Cash-secured put', outlook: 'income', family: 'CASH_SECURED_PUT',
      blurb: 'Get paid to wait for your price — assignment buys the shares at the strike.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'BUY_WRITE', group: 'Directional — bullish', name: 'Covered call (buy-write)', outlook: 'income', family: 'COVERED_CALL',
      blurb: 'Buy 100 shares and rent them out immediately — premium now, capped upside.',
      build: function (c) { return [stockLeg('BUY', 1), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'RISK_REVERSAL', group: 'Directional — bullish', name: 'Risk reversal', outlook: 'bull', family: 'CUSTOM',
      blurb: 'Sell a put to pay for a call — strongly bullish, big reserve if wrong.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -3), c.near), leg('BUY', 'CALL', c.pick(c.near, 3), c.near)]; } },

    { key: 'LONG_PUT', group: 'Directional — bearish', name: 'Long put', outlook: 'bear', family: 'LONG_PUT',
      blurb: 'Stock down big — pay a premium, profit as it falls, lose at most the premium.',
      build: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 0), c.near)]; } },
    { key: 'DEBIT_PUT_SPREAD', group: 'Directional — bearish', name: 'Bear put spread', outlook: 'bear', family: 'DEBIT_PUT_SPREAD',
      blurb: 'Stock down some — cheaper than a put by selling away the far downside.',
      build: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 0), c.near), leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'CREDIT_CALL_SPREAD', group: 'Directional — bearish', name: 'Bear call spread', outlook: 'bear', family: 'CREDIT_CALL_SPREAD',
      blurb: 'Stock NOT up — collect a credit, keep it if price stays below your short strike.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 2), c.near), leg('BUY', 'CALL', c.pick(c.near, 4), c.near)]; } },

    { key: 'IRON_CONDOR', group: 'Neutral — income', name: 'Iron condor', outlook: 'income', family: 'IRON_CONDOR',
      blurb: 'Price stays in a range — sell both sides, wings cap the damage either way.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near), leg('BUY', 'PUT', c.pick(c.near, -4), c.near),
                                    leg('SELL', 'CALL', c.pick(c.near, 2), c.near), leg('BUY', 'CALL', c.pick(c.near, 4), c.near)]; } },
    { key: 'IRON_BUTTERFLY', group: 'Neutral — income', name: 'Iron butterfly', outlook: 'income', family: 'IRON_BUTTERFLY',
      blurb: 'Price pins near today — richer credit than a condor, narrower sweet spot.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, 0), c.near), leg('SELL', 'CALL', c.pick(c.near, 0), c.near),
                                    leg('BUY', 'PUT', c.pick(c.near, -3), c.near), leg('BUY', 'CALL', c.pick(c.near, 3), c.near)]; } },
    { key: 'SHORT_STRADDLE', group: 'Neutral — income', name: 'Short straddle', outlook: 'income', family: 'SHORT_STRADDLE', risky: true,
      blurb: 'Sell both at the money — maximum premium, UNLIMITED risk. Screened out here; shown so you can see why.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('SELL', 'PUT', c.pick(c.near, 0), c.near)]; } },
    { key: 'SHORT_STRANGLE', group: 'Neutral — income', name: 'Short strangle', outlook: 'income', family: 'SHORT_STRANGLE', risky: true,
      blurb: 'Sell both sides wider — same unlimited-risk problem, slightly more room.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 2), c.near), leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'CALENDAR_CALL', group: 'Neutral — income', name: 'Calendar call', outlook: 'neutral', family: 'CALENDAR_CALL',
      blurb: 'Sell the near month, own the far month — time decays faster up close.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'CALL', c.pick(c.far, 0), c.far)]; } },
    { key: 'CALENDAR_PUT', group: 'Neutral — income', name: 'Calendar put', outlook: 'neutral', family: 'CALENDAR_PUT',
      blurb: 'Same idea with puts — profits when price sits still and near IV bleeds.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.far, 0), c.far)]; } },

    { key: 'LONG_STRADDLE', group: 'Volatility — big move either way', name: 'Long straddle', outlook: 'vol', family: 'CUSTOM',
      blurb: 'Buy both at the money — direction unknown, size of the move is the bet.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.near, 0), c.near)]; } },
    { key: 'LONG_STRANGLE', group: 'Volatility — big move either way', name: 'Long strangle', outlook: 'vol', family: 'CUSTOM',
      blurb: 'Buy both sides out of the money — cheaper than a straddle, needs a bigger move.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 2), c.near), leg('BUY', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'CALL_BACKSPREAD', group: 'Volatility — big move either way', name: 'Call ratio backspread', outlook: 'vol', family: 'CUSTOM',
      blurb: 'Sell one call, buy two further up — loves a violent rally, hates a slow drift up.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'CALL', c.pick(c.near, 2), c.near, 2)]; } },
    { key: 'PUT_BACKSPREAD', group: 'Volatility — big move either way', name: 'Put ratio backspread', outlook: 'vol', family: 'CUSTOM',
      blurb: 'Sell one put, buy two further down — pays off in a crash, bleeds in a drift.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.near, -2), c.near, 2)]; } },

    { key: 'LONG_CALL_BUTTERFLY', group: 'Pinpoint — price targets', name: 'Call butterfly', outlook: 'neutral', family: 'LONG_CALL_BUTTERFLY',
      blurb: 'Bet on a specific landing spot — tiny cost, big payoff exactly there.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, -2), c.near), leg('SELL', 'CALL', c.pick(c.near, 0), c.near, 2),
                                    leg('BUY', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'LONG_PUT_BUTTERFLY', group: 'Pinpoint — price targets', name: 'Put butterfly', outlook: 'neutral', family: 'LONG_PUT_BUTTERFLY',
      blurb: 'Same pinpoint bet built from puts — useful when puts price better.',
      build: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 2), c.near), leg('SELL', 'PUT', c.pick(c.near, 0), c.near, 2),
                                    leg('BUY', 'PUT', c.pick(c.near, -2), c.near)]; } },

    { key: 'PROTECTIVE_PUT', group: 'Shares — income & protection', name: 'Protective put', outlook: 'protect', family: 'PROTECTIVE_PUT',
      blurb: 'Shares plus an insurance floor — pay a premium, cap the downside.',
      build: function (c) { return [stockLeg('BUY', 1), leg('BUY', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'COLLAR', group: 'Shares — income & protection', name: 'Collar', outlook: 'protect', family: 'PROTECTIVE_COLLAR',
      blurb: 'Floor below, ceiling above — the sold call pays for most of the insurance.',
      build: function (c) { return [stockLeg('BUY', 1), leg('BUY', 'PUT', c.pick(c.near, -2), c.near), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'DIAGONAL_CALL', group: 'Shares — income & protection', name: 'Diagonal call', outlook: 'income', family: 'DIAGONAL_CALL',
      blurb: 'A covered call built from options — own a far-dated call, rent out near ones.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.far, 0), c.far), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'DIAGONAL_PUT', group: 'Shares — income & protection', name: 'Diagonal put', outlook: 'income', family: 'DIAGONAL_PUT',
      blurb: 'The put-side twin — far-dated put anchors, near puts collect premium.',
      build: function (c) { return [leg('BUY', 'PUT', c.pick(c.far, 0), c.far), leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; } },

    { key: 'BLANK', group: 'Start from scratch', name: 'Custom (empty)', outlook: 'neutral', family: 'CUSTOM',
      blurb: 'No template — add every leg yourself, as many as you want.',
      build: function () { return []; } }
  ];

  function leg(action, type, strike, expiration, ratio) {
    return { action: action, type: type, strike: strike, expiration: expiration, ratio: ratio || 1 };
  }
  function stockLeg(action, ratio) {
    return { action: action, type: 'STOCK', strike: null, expiration: null, ratio: ratio || 1 };
  }

  // ---------- Screen ----------

  async function render(root) {
    var saved = App.state.builderForm || {};
    var st = {
      symbol: (saved.symbol || App.state.lastRecommendSymbol || 'AAPL').toUpperCase(),
      qty: saved.qty || 1,
      templateKey: saved.templateKey || null,
      legs: saved.legs ? saved.legs.map(function (l) { return Object.assign({}, l); }) : []
    };
    function remember() {
      App.state.builderForm = { symbol: st.symbol, qty: st.qty, templateKey: st.templateKey, legs: st.legs };
    }

    var level = Learn.currentLevel();
    var research = null, chainCache = {};
    var expirations = [], spot = null;

    async function loadSymbol() {
      research = await API.get('/api/research/' + st.symbol);
      expirations = research.expirations || [];
      spot = research.quote && research.quote.last ? parseFloat(research.quote.last) : null;
      chainCache = {};
    }
    function ensureChain(exp) {
      if (!chainCache[exp]) {
        chainCache[exp] = API.get('/api/research/' + st.symbol + '/chain?expiration=' + exp)
          .catch(function () { return { calls: [], puts: [] }; });
      }
      return chainCache[exp];
    }
    async function tplContext() {
      if (!expirations.length || spot === null) return null;
      var near = expirations[Math.min(2, expirations.length - 1)];
      var far = expirations[Math.min(5, expirations.length - 1)];
      var chains = {};
      chains[near] = await ensureChain(near);
      if (far !== near) chains[far] = await ensureChain(far);
      function strikesOf(exp) {
        return ((chains[exp] || {}).calls || []).map(function (q) { return parseFloat(q.strike); }).sort(function (a, b) { return a - b; });
      }
      return {
        near: near, far: far, spot: spot,
        pick: function (exp, offset) {
          var ks = strikesOf(exp);
          if (!ks.length) return null;
          var atm = 0, best = Infinity;
          ks.forEach(function (k, i) { var d = Math.abs(k - spot); if (d < best) { best = d; atm = i; } });
          var idx = Math.max(0, Math.min(ks.length - 1, atm + offset));
          return String(ks[idx]);
        }
      };
    }

    // ---- Layout: templates card, then editor + live panel side by side ----
    root.appendChild(el('h1', {}, 'Build a strategy'));
    root.appendChild(el('p', { class: 'page-sub' },
      'Pick a structure or stack legs by hand — the panel re-prices your exact contracts as you go, with the same honesty screens every trade passes.'));

    var symInput = el('input', { type: 'text', id: 'builder-symbol', list: 'universe-symbols', value: st.symbol });
    var qtyInput = el('input', { type: 'number', id: 'builder-qty', min: '1', max: '100', value: String(st.qty) });
    var tplWrap = el('div', { id: 'builder-templates' });
    var storySlot = el('div', { id: 'builder-story' });

    var topCard = el('div', { class: 'card' },
      explain('Every structure here is built from live strikes around the current price. The safety screens still apply: undefined-risk positions preview honestly, then get blocked at the door.'),
      el('div', { class: 'form-grid' },
        el('div', { class: 'field' }, el('label', {}, 'Symbol'), symInput),
        el('div', { class: 'field' }, el('label', {}, 'Contracts (qty)'), qtyInput)),
      el('div', { class: 'field-label', style: 'margin-top:8px' }, 'Structure'),
      tplWrap, storySlot);
    root.appendChild(topCard);

    var legsHost = el('div', { id: 'builder-legs' });
    var addBtn = el('button', { class: 'btn btn-sm btn-secondary', id: 'builder-add-leg' }, '+ Add a leg');
    var legsCard = el('div', { class: 'card' },
      UI.cardHeader('Legs'),
      explain('One row per contract. Hover a row for its live market (bid/ask, IV, greeks); click it for what the leg contributes to the whole position.'),
      legsHost,
      el('div', { class: 'btn-row' }, addBtn));

    var panel = el('div', { class: 'card builder-panel', id: 'builder-panel' });
    root.appendChild(el('div', { class: 'builder-cols' }, legsCard, panel));

    // ---- Template picker ----
    // Once a structure is chosen the full catalog collapses to a one-line summary so the
    // legs and the live panel are immediately in view; "Change" brings the catalog back.
    var tplCollapsed = !!st.templateKey;
    function renderTemplates() {
      tplWrap.innerHTML = '';
      var selected = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
      if (tplCollapsed && selected) {
        var o0 = OUTLOOK[selected.outlook];
        tplWrap.appendChild(el('div', { class: 'tpl-selected', id: 'tpl-selected' },
          el('b', {}, selected.name),
          el('span', { class: 'badge ' + o0[1] }, o0[0]),
          el('span', { class: 'muted tpl-blurb' }, selected.blurb),
          el('span', { class: 'spacer' }),
          el('button', {
            class: 'btn btn-sm btn-secondary', id: 'tpl-change', type: 'button',
            onclick: function () { tplCollapsed = false; renderTemplates(); }
          }, 'Change structure')));
        return;
      }
      var groups = [];
      TEMPLATES.forEach(function (t) { if (groups.indexOf(t.group) < 0) groups.push(t.group); });
      groups.forEach(function (g) {
        tplWrap.appendChild(el('div', { class: 'tpl-group-label' }, g));
        var row = el('div', { class: 'tpl-grid' + (level === 'pro' ? ' compact' : '') });
        TEMPLATES.filter(function (t) { return t.group === g; }).forEach(function (t) {
          var o = OUTLOOK[t.outlook];
          row.appendChild(el('button', {
            class: 'tpl' + (st.templateKey === t.key ? ' selected' : ''), type: 'button',
            'data-tpl': t.key, title: level === 'pro' ? t.blurb : null,
            onclick: function () { applyTemplate(t); }
          },
            el('span', { class: 'tpl-head' }, el('b', {}, t.name),
              el('span', { class: 'badge ' + o[1] }, o[0]),
              t.risky ? el('span', { class: 'badge badge-danger' }, 'USUALLY BLOCKED') : null),
            level === 'pro' ? null : el('span', { class: 'muted tpl-blurb' }, t.blurb)));
        });
        tplWrap.appendChild(row);
      });
    }

    function renderStory() {
      storySlot.innerHTML = '';
      if (level === 'pro') return;
      var t = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
      if (!t) return;
      var g = Learn.STRATEGY_GUIDE[t.family];
      if (!g) return;
      storySlot.appendChild(UI.expandable('How this trade works — and what can go wrong', function () {
        return el('div', {},
          el('p', {}, el('i', {}, g.story)),
          el('div', { class: 'fact-row' }, el('span', {}, el('b', { class: 'gain' }, 'You win when: '), g.win)),
          el('div', { class: 'fact-row' }, el('span', {}, el('b', { class: 'loss' }, 'You lose when: '), g.lose)),
          el('p', {}, el('b', {}, 'Easy to miss: '), g.watch));
      }, { open: level === 'learning' }));
    }

    async function applyTemplate(t) {
      st.templateKey = t.key;
      tplCollapsed = true;
      renderTemplates();
      var ctx = await tplContext();
      st.legs = ctx ? t.build(ctx).filter(function (l) { return l.type === 'STOCK' || l.strike; }) : [];
      remember();
      renderStory();
      await renderLegs();
      schedulePreview();
      if (!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)) {
        legsCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    }

    // ---- Legs editor ----
    var legPop = null;
    function hideLegPop() { if (legPop) { legPop.remove(); legPop = null; } }

    async function legMarket(l) {
      if (l.type === 'STOCK' || !l.expiration || !l.strike) return null;
      var chain = await ensureChain(l.expiration);
      var rows = l.type === 'CALL' ? (chain.calls || []) : (chain.puts || []);
      return rows.find(function (q) { return parseFloat(q.strike) === parseFloat(l.strike); }) || null;
    }

    function describeLeg(l) {
      if (l.type === 'STOCK') return (l.action === 'BUY' ? 'Buy' : 'Sell') + ' ' + (100 * l.ratio) + ' shares';
      return (l.action === 'BUY' ? 'Buy' : 'Sell') + ' ' + l.ratio + '× ' + l.strike + ' ' + l.type.toLowerCase()
        + (l.expiration ? ' · ' + l.expiration : '');
    }

    async function showLegPop(row, l) {
      var q = await legMarket(l);
      hideLegPop();
      if (!q || !row.isConnected) return;
      var dte = l.expiration ? Math.max(0, Math.round((new Date(l.expiration + 'T16:00:00') - Date.now()) / 86400000)) : null;
      var money = spot !== null && l.strike
        ? ((l.type === 'CALL' ? spot - parseFloat(l.strike) : parseFloat(l.strike) - spot) >= 0 ? 'in the money' : 'out of the money')
        : '';
      legPop = el('div', { class: 'leg-pop' },
        el('div', { class: 'tt-title' }, describeLeg(l)),
        el('div', { class: 'tt-line' }, 'Bid ' + (q.bid || '—') + ' · Ask ' + (q.ask || '—')),
        el('div', { class: 'tt-line' }, 'IV ' + (q.iv ? (q.iv * 100).toFixed(0) + '%' : '—')
          + ' · Δ ' + (q.delta !== null && q.delta !== undefined ? q.delta.toFixed(2) : '—')
          + ' · Θ ' + (q.theta !== null && q.theta !== undefined ? q.theta.toFixed(3) : '—')),
        el('div', { class: 'tt-line' }, 'OI ' + (q.openInterest !== null && q.openInterest !== undefined ? q.openInterest : '—')
          + ' · Vol ' + (q.volume !== null && q.volume !== undefined ? q.volume : '—')),
        el('div', { class: 'tt-line muted' }, (dte !== null ? dte + ' days left · ' : '') + money));
      document.body.appendChild(legPop);
      var r = row.getBoundingClientRect();
      legPop.style.top = (r.bottom + window.scrollY + 6) + 'px';
      legPop.style.left = Math.max(8, Math.min(r.left + window.scrollX, window.innerWidth - 280)) + 'px';
    }

    async function legDetail(container, l, idx) {
      container.innerHTML = '';
      container.appendChild(UI.spinner('Measuring this leg’s contribution…'));
      var q = await legMarket(l);
      var fill = q ? (l.action === 'BUY' ? parseFloat(q.ask) : parseFloat(q.bid)) : null;
      // fill is $/share; one contract = 100 shares; fmtMoney wants CENTS -> ×100×100
      var cash = fill !== null && !isNaN(fill) ? Math.round(fill * 100 * 100 * l.ratio * st.qty) * (l.action === 'SELL' ? 1 : -1) : null;
      var rows = el('div', {},
        el('div', { class: 'chip-row' },
          cash !== null ? chip(l.action === 'SELL' ? 'Brings in' : 'Costs', fmtMoney(Math.abs(cash))) : null,
          q && q.iv ? chip('IV', (q.iv * 100).toFixed(0) + '%') : null,
          q && q.delta !== undefined && q.delta !== null ? chip('Delta', q.delta.toFixed(2)) : null,
          q && q.gamma !== undefined && q.gamma !== null ? chip('Gamma', q.gamma.toFixed(4)) : null,
          q && q.theta !== undefined && q.theta !== null ? chip('Theta/day', q.theta.toFixed(3)) : null,
          q && q.vega !== undefined && q.vega !== null ? chip('Vega', q.vega.toFixed(3)) : null));
      // Impact: what the position looks like WITHOUT this leg — one extra preview, on demand.
      var others = validLegs().filter(function (x, i) { return i !== idx; });
      if (others.length && lastPreview) {
        try {
          var without = (await API.post('/api/trades/preview', {
            symbol: st.symbol, strategy: 'CUSTOM', qty: st.qty, legs: others
          })).preview;
          var d = el('div', { class: 'leg-impact' },
            el('b', {}, 'Without this leg: '),
            impactBit('max loss', without.maxLossCents, lastPreview.maxLossCents, true),
            impactBit('max profit', without.maxProfitCents, lastPreview.maxProfitCents, false),
            impactBit('POP', without.popEntry, lastPreview.popEntry, false, true));
          rows.appendChild(d);
        } catch (e) { /* impact is best-effort */ }
      }
      container.innerHTML = '';
      container.appendChild(rows);
    }

    function impactBit(label, without, current, isLoss, isPct) {
      function f(v) {
        if (v === null || v === undefined) return isPct ? '—' : 'uncapped';
        return isPct ? fmtPct(v) : fmtMoney(v);
      }
      return el('span', { class: 'impact-bit' },
        label + ' ', el('b', { class: isLoss ? 'loss' : '' }, f(current)), ' → ',
        el('b', { class: isLoss ? 'loss' : '' }, f(without)), '. ');
    }

    async function legRow(l, idx) {
      var row = el('div', { class: 'leg-row', 'data-leg': String(idx) });
      var detail = el('div', { class: 'leg-detail', style: 'display:none' });

      var action = el('select', { class: 'leg-action' },
        el('option', { value: 'BUY', selected: l.action === 'BUY' ? '' : null }, 'Buy'),
        el('option', { value: 'SELL', selected: l.action === 'SELL' ? '' : null }, 'Sell'));
      var type = el('select', { class: 'leg-type' },
        ['CALL', 'PUT', 'STOCK'].map(function (t) {
          return el('option', { value: t, selected: l.type === t ? '' : null }, t === 'STOCK' ? '100 shares' : t.toLowerCase());
        }));
      var exp = el('select', { class: 'leg-exp' },
        expirations.map(function (d) {
          return el('option', { value: d, selected: l.expiration === d ? '' : null }, d);
        }));
      var strike = el('select', { class: 'leg-strike' });
      var ratio = el('input', { class: 'leg-ratio', type: 'number', min: '1', max: '10', value: String(l.ratio || 1), title: 'Contracts per unit (ratio spreads)' });

      async function fillStrikes() {
        if (l.type === 'STOCK') return;
        var chain = await ensureChain(l.expiration);
        var ks = ((l.type === 'CALL' ? chain.calls : chain.puts) || []).map(function (q) { return q.strike; });
        strike.innerHTML = '';
        ks.forEach(function (k) {
          strike.appendChild(el('option', { value: String(k), selected: l.strike && parseFloat(k) === parseFloat(l.strike) ? '' : null }, String(k)));
        });
        if (!l.strike && ks.length) l.strike = String(ks[Math.floor(ks.length / 2)]);
        else if (l.strike && strike.value) l.strike = strike.value;
      }

      function syncStockMode() {
        var isStock = l.type === 'STOCK';
        exp.style.display = isStock ? 'none' : '';
        strike.style.display = isStock ? 'none' : '';
        if (isStock) { l.strike = null; l.expiration = null; }
        else if (!l.expiration) { l.expiration = expirations[Math.min(2, expirations.length - 1)]; exp.value = l.expiration; }
      }

      action.addEventListener('change', function () { l.action = action.value; remember(); schedulePreview(); });
      type.addEventListener('change', async function () {
        l.type = type.value; syncStockMode();
        if (l.type !== 'STOCK') { l.strike = null; await fillStrikes(); }
        remember(); schedulePreview();
      });
      exp.addEventListener('change', async function () {
        l.expiration = exp.value; l.strike = null;
        await fillStrikes(); remember(); schedulePreview();
      });
      strike.addEventListener('change', function () { l.strike = strike.value; remember(); schedulePreview(); });
      ratio.addEventListener('change', function () {
        l.ratio = Math.max(1, Math.min(10, parseInt(ratio.value, 10) || 1));
        ratio.value = String(l.ratio); remember(); schedulePreview();
      });

      function toggleDetail() {
        var open = detail.style.display !== 'none';
        detail.style.display = open ? 'none' : '';
        row.classList.toggle('open', !open);
        infoBtn.setAttribute('aria-expanded', String(!open));
        if (!open) legDetail(detail, l, idx);
      }
      var infoBtn = el('button', {
        class: 'btn btn-sm btn-secondary leg-info', type: 'button', 'aria-expanded': 'false',
        title: 'This leg’s market and its contribution to the position',
        onclick: function (e) { e.stopPropagation(); hideLegPop(); toggleDetail(); }
      }, 'Details');
      var remove = el('button', {
        class: 'btn btn-sm btn-secondary leg-remove', type: 'button', title: 'Remove this leg',
        onclick: function (e) {
          e.stopPropagation();
          hideLegPop();
          st.legs.splice(idx, 1);
          st.templateKey = null; // hand-edited: no longer the template
          remember(); renderLegs(); schedulePreview();
        }
      }, '✕');

      var controls = el('div', { class: 'leg-controls' }, action, type, exp, strike, ratio, infoBtn, remove);
      row.appendChild(controls);
      row.appendChild(detail);

      // Hover = market insight in a floating tip (zero layout shift); the Details button
      // (or a click on the row's empty space) expands the contribution card inline.
      var hoverT = null;
      row.addEventListener('mouseenter', function () {
        hoverT = setTimeout(function () { showLegPop(row, l); }, 150);
      });
      row.addEventListener('mouseleave', function () {
        if (hoverT) clearTimeout(hoverT);
        hideLegPop();
      });
      controls.addEventListener('click', function (e) {
        if (e.target.closest('select, input, button')) return; // controls are controls
        toggleDetail();
      });

      syncStockMode();
      if (l.type !== 'STOCK') await fillStrikes();
      return row;
    }

    async function renderLegs() {
      hideLegPop();
      legsHost.innerHTML = '';
      if (!st.legs.length) {
        legsHost.appendChild(UI.emptyState('No legs yet',
          'Pick a structure above or add legs by hand.'));
        return;
      }
      for (var i = 0; i < st.legs.length; i++) {
        legsHost.appendChild(await legRow(st.legs[i], i));
      }
    }

    addBtn.addEventListener('click', async function () {
      st.legs.push(leg('BUY', 'CALL', null, expirations[Math.min(2, expirations.length - 1)] || null));
      st.templateKey = null;
      remember();
      await renderLegs();
      schedulePreview();
    });

    // ---- Live panel ----
    var previewTimer = null, previewSeq = 0, lastPreview = null;
    function validLegs() {
      return st.legs
        .filter(function (l) { return l.type === 'STOCK' || (l.strike && l.expiration); })
        .map(function (l) {
          return { action: l.action, type: l.type, strike: l.type === 'STOCK' ? null : String(l.strike),
                   expiration: l.type === 'STOCK' ? null : l.expiration, ratio: l.ratio || 1 };
        });
    }
    function strategyLabel() {
      var t = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
      return t ? t.family : 'CUSTOM';
    }
    function schedulePreview() {
      if (previewTimer) clearTimeout(previewTimer);
      previewTimer = setTimeout(runPreview, 350);
    }

    function panelEmpty() {
      panel.innerHTML = '';
      panel.appendChild(UI.cardHeader('Where you stand'));
      panel.appendChild(UI.emptyState('Add a leg to see the numbers',
        'Cost, worst case, profit range, and the payoff chart appear here and update as you edit.'));
    }

    async function runPreview() {
      var legsNow = validLegs();
      if (!legsNow.length) { lastPreview = null; panelEmpty(); return; }
      var seq = ++previewSeq;
      panel.classList.add('updating');
      try {
        var res = await API.post('/api/trades/preview', {
          symbol: st.symbol, strategy: strategyLabel(), qty: st.qty, legs: legsNow
        });
        if (seq !== previewSeq) return;
        lastPreview = res.preview;
        renderPanel(res.preview, res.guardrails);
      } catch (e) {
        if (seq !== previewSeq) return;
        lastPreview = null;
        panel.innerHTML = '';
        panel.appendChild(UI.cardHeader('Where you stand'));
        panel.appendChild(alertBox('danger', 'Could not price this position', [e.message]));
      } finally {
        panel.classList.remove('updating');
      }
    }

    function renderPanel(p, guard) {
      panel.innerHTML = '';
      panel.appendChild(UI.cardHeader('Where you stand'));

      if (!p.ok) {
        panel.appendChild(alertBox('danger', 'This position would be refused', p.blockReasons));
      } else if (guard && guard.level === 'WARN') {
        panel.appendChild(alertBox('warn', 'Allowed, with cautions', guard.warnings));
      } else {
        panel.appendChild(alertBox('ok', 'Passes the safety screens', null));
      }

      var credit = p.entryNetPremiumCents;
      panel.appendChild(el('div', { class: 'grid grid-2 panel-stats' },
        stat(credit >= 0 ? 'You collect' : 'You pay', fmtMoney(Math.abs(credit)),
          credit >= 0 ? 'Premium received now. It is yours to keep only if the position works out.' : 'Cash leaving your account now, before fees.'),
        stat('Most you can lose',
          // "UNLIMITED" only when that is genuinely WHY it's blocked — a dead-contract or
          // no-mark block also carries maxLoss 0 and must not be dressed up as infinite risk.
          !p.ok && p.maxLossCents === 0 && (p.blockReasons || []).some(function (r) { return /undefined|unlimited/i.test(r); })
            ? el('span', { class: 'loss' }, 'UNLIMITED')
            : p.ok || p.maxLossCents > 0 ? el('span', { class: 'loss' }, fmtMoney(p.maxLossCents))
            : el('span', { class: 'muted' }, '—'),
          'The worst case at expiration, including every leg.'),
        stat('Most you can make', p.maxProfitCents === null || p.maxProfitCents === undefined
          ? el('span', { class: 'gain' }, 'Uncapped') : el('span', { class: 'gain' }, fmtMoney(p.maxProfitCents)),
          'The best case at expiration.'),
        stat(UI.term('pop', 'Chance of any profit'), p.popEntry !== null && p.popEntry !== undefined ? fmtPct(p.popEntry) : '—',
          'Model estimate under current volatility. Not a promise.'),
        p.assignmentProb !== null && p.assignmentProb !== undefined
          ? stat(UI.term('assignment', 'Assignment odds'), fmtPct(p.assignmentProb),
              'Chance any short strike finishes in the money and shares change hands.') : null,
        stat('Fees', fmtMoney(p.feesOpenCents), 'Per-contract fees to open, charged separately.')));

      if (p.breakevens && p.breakevens.length) {
        panel.appendChild(el('div', { class: 'chip-row' },
          chip(UI.term('breakeven', 'Breakevens'), p.breakevens.map(function (b) { return '$' + b; }).join(' / '))));
      }
      panel.appendChild(el('div', { class: 'chip-row' },
        chip('Buying power after', fmtMoney(p.buyingPowerAfterCents)),
        p.reserveCents ? chip(UI.term('reserve', 'Set aside'), fmtMoney(p.reserveCents)) : null));

      if (p.payoff && p.payoff.length > 1) {
        panel.appendChild(UI.payoffChart(p.payoff, {
          breakevens: p.breakevens, spot: p.underlyingCents ? p.underlyingCents / 100 : null
        }));
      } else if (p.legs && p.legs.length) {
        panel.appendChild(explain('Mixed expirations: the at-expiry chart does not exist for this position — its value depends on volatility after the near leg dies.'));
      }

      if (p.ok && p.warnings && p.warnings.length) {
        panel.appendChild(alertBox('warn', 'Heads up', p.warnings));
      }

      panel.appendChild(el('div', { class: 'btn-row' },
        el('button', {
          class: 'btn', id: 'builder-review',
          disabled: p.ok ? null : '',
          title: p.ok ? null : 'Fix the blocking issues first',
          onclick: function () {
            App.state.ticket = {
              symbol: st.symbol, custom: true, customFor: st.symbol,
              legs: validLegs(), qty: st.qty, step: 6,
              thesis: 'neutral', horizon: 'month'
            };
            App.navigate('#/ticket');
          }
        }, 'Review & place (paper) →')));
      panel.appendChild(explain('Placing runs the full ticket review: the same preview, a safety checklist, and an explicit confirm. Nothing is placed from this screen.'));
    }

    // ---- Wire the top controls ----
    symInput.addEventListener('change', async function () {
      var sym = symInput.value.trim().toUpperCase();
      if (!sym || sym === st.symbol) return;
      st.symbol = sym;
      App.state.lastRecommendSymbol = sym;
      st.legs = [];
      remember();
      try {
        await loadSymbol();
        var t = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
        if (t) await applyTemplate(t); else { await renderLegs(); panelEmpty(); }
      } catch (e) {
        legsHost.innerHTML = '';
        legsHost.appendChild(alertBox('danger', 'No data for ' + sym, [e.message]));
      }
    });
    qtyInput.addEventListener('change', function () {
      st.qty = Math.max(1, Math.min(100, parseInt(qtyInput.value, 10) || 1));
      qtyInput.value = String(st.qty);
      remember(); schedulePreview();
    });

    // ---- Boot ----
    try {
      await loadSymbol();
    } catch (e) {
      root.appendChild(alertBox('danger', 'Could not load ' + st.symbol, [e.message]));
      return;
    }
    renderTemplates();
    renderStory();
    if (!expirations.length) {
      legsHost.appendChild(UI.emptyState(st.symbol + ' has no listed options',
        'Pick an optionable symbol (try AAPL or SPY).'));
      panelEmpty();
    } else {
      await renderLegs();
      if (st.legs.length) schedulePreview(); else panelEmpty();
    }
  }

  window.Builder = { render: render, TEMPLATES: TEMPLATES };
})();
