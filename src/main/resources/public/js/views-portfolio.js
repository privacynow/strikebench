/* StrikeBench Portfolio and position-detail views. Loaded after views.js. */
(function () {
  'use strict';

  var S = window.ViewShared;
  var el = S.el, fmtMoney = S.fmtMoney, pnlSpan = S.pnlSpan, fmtPct = S.fmtPct,
      fmtNum = S.fmtNum, badge = S.badge, explain = S.explain,
      alertBox = S.alertBox, stat = S.stat, table = S.table, chip = S.chip;
  var reported = S.reported, activeDecisionPnl = S.activeDecisionPnl,
      closedDecisionPnl = S.closedDecisionPnl, positiveInteger = S.positiveInteger,
      visibleCommand = S.visibleCommand, focusPlanFrom = S.focusPlanFrom,
      startPlan = S.startPlan, legLabel = S.legLabel, fmtBreakeven = S.fmtBreakeven,
      prettyStrategy = S.prettyStrategy, isYoungTrade = S.isYoungTrade,
      pressable = S.pressable, intentBadge = S.intentBadge;
  var guideBlock = window.ViewPlan.guideBlock,
      planMarketLabel = window.ViewPlan.marketLabel;

  // ---- Shared position controls ----

  function stockOrderModal(side, symbol, maxShares) {
    var symInput = el('input', { type: 'text', id: 'stock-symbol', value: symbol || '' });
    var qty = el('input', { type: 'number', id: 'stock-shares', min: '1',
      value: side === 'sell' && maxShares ? String(maxShares) : '100' });
    var preview = el('div', { id: 'stock-order-preview', 'aria-live': 'polite' },
      el('span', { class: 'muted small' }, 'Enter a symbol and share count to price this order.'));
    var previewSeq = 0, previewTimer = null;
    async function loadPreview() {
      var seq = ++previewSeq;
      var shares = Number(qty.value), sym = symInput.value.trim().toUpperCase();
      if (!sym || !Number.isInteger(shares) || shares <= 0) return;
      preview.innerHTML = '';
      preview.appendChild(UI.spinner('Pricing at the current ' + (side === 'buy' ? 'ask' : 'bid') + '…'));
      try {
        var p = await API.post('/api/positions/preview', { side: side, symbol: sym, shares: shares });
        if (seq !== previewSeq || !preview.isConnected) return;
        preview.innerHTML = '';
        if (!p.ok) preview.appendChild(alertBox('warn', 'This order cannot be placed', p.blockReasons));
        if (p.warnings && p.warnings.length) preview.appendChild(alertBox('caution', 'Quote context', p.warnings));
        preview.appendChild(el('div', { class: 'chip-row' },
          chip(side === 'buy' ? 'Estimated cash outlay' : 'Estimated proceeds', fmtMoney(p.totalCents)),
          chip('Executable ' + (side === 'buy' ? 'ask' : 'bid') + ' / share', fmtMoney(p.pricePerShareCents)),
          side === 'sell' && p.estimatedRealizedPnlCents != null
            ? chip('Estimated realized P/L', pnlSpan(p.estimatedRealizedPnlCents)) : null,
          side === 'buy' ? chip('Buying power after', fmtMoney(p.buyingPowerAfterCents)) : null));
        preview.appendChild(el('div', { class: 'muted small' },
          'Estimate only. Confirm rechecks the current executable quote and account immediately before the practice fill.'));
      } catch (e) {
        if (seq !== previewSeq || !preview.isConnected) return;
        preview.innerHTML = '';
        preview.appendChild(alertBox('warn', 'Could not price this order', [e.message]));
      }
    }
    function queuePreview() {
      clearTimeout(previewTimer);
      previewTimer = setTimeout(loadPreview, 250);
    }
    symInput.addEventListener('input', queuePreview);
    qty.addEventListener('input', queuePreview);
    UI.confirmModal(side === 'buy' ? 'Buy shares' : 'Sell shares',
      el('div', {},
        explain(side === 'buy'
          ? 'Fills at the current ask. Owning 100+ shares unlocks covered calls (income, selling at a target) and protective puts.'
          : 'Fills at the current bid. Shares locked under an open covered call or collar cannot be sold until that trade closes.'),
        UI.field('Symbol', symInput),
        UI.field('Shares', qty, { className: 'stock-order-shares' }),
        preview),
      side === 'buy' ? 'Buy' : 'Sell',
      async function () {
        var shares = positiveInteger(qty.value, 'Shares');
        if (side === 'sell' && maxShares && shares > maxShares) {
          throw new Error('Only ' + maxShares + ' shares are available to sell.');
        }
        var check = await API.post('/api/positions/preview',
          { side: side, symbol: symInput.value.trim(), shares: shares });
        if (!check.ok) throw new Error((check.blockReasons || ['This order cannot be placed.']).join(' '));
        await API.post('/api/positions/' + side,
          { symbol: symInput.value.trim(), shares: shares });
        App.render();
      });
    queuePreview();
  }

  function portfolioComposition(perSymbol) {
    var rows = Object.keys(perSymbol || {}).map(function (symbol) { return { symbol: symbol, value: Number(perSymbol[symbol] || 0) }; });
    var total = rows.reduce(function (sum, row) { return sum + row.value; }, 0) || 1;
    return el('div', { class: 'allocation-composition', role: 'img', 'aria-label': 'Capital allocation by symbol' },
      el('div', { class: 'allocation-bar' }, rows.map(function (row, index) {
        var pct = row.value / total * 100;
        return el('span', { class: 'allocation-segment allocation-color-' + (index % 6), style: 'width:' + pct.toFixed(2) + '%',
          'aria-label': row.symbol + ' ' + pct.toFixed(1) + '%' });
      })),
      el('div', { class: 'allocation-legend' }, rows.map(function (row, index) {
        return el('span', {}, el('i', { class: 'allocation-swatch allocation-color-' + (index % 6) }),
          row.symbol + ' ' + (row.value / total * 100).toFixed(0) + '%');
      })));
  }

  async function openOptimizationPlan(allocation, form) {
    var evaluation = allocation.eval || {};
    var candidate = evaluation.candidate || {};
    var symbol = String(evaluation.symbol || (evaluation.spec && evaluation.spec.symbol) || candidate.symbol || '').toUpperCase();
    var intent = candidate.intent || form.goal || 'DIRECTIONAL';
    var existing = await PlanStore.matching(symbol, intent);
    var horizonDays = Product.Horizon.sessions(form.horizon);
    var plan = await PlanStore.create({ originPlanId: existing.length ? existing[0].id : null,
      symbol: symbol, intent: intent, thesis: form.thesis,
      horizonDays: horizonDays, riskMode: form.riskMode });
    if (candidate.legs && candidate.legs.length) {
      var saved = await PlanStore.saveCustom(plan, { symbol: symbol,
        strategy: candidate.strategy || evaluation.family || 'CUSTOM',
        qty: Math.max(1, Number(candidate.qty || 1) * Number(allocation.units || 1)), legs: candidate.legs,
        thesis: form.thesis, horizon: form.horizon, riskMode: form.riskMode,
        intent: intent, source: 'PORTFOLIO_CONSTRUCT' });
      plan = saved.plan;
    }
    return PlanStore.focus(plan, 'STRATEGY');
  }

  function renderPortfolioConstructionResult(host, data, form) {
    host.innerHTML = '';
    var result = data && data.optimization || {};
    var allocations = result.allocations || [];
    if (!allocations.length) {
      host.appendChild(UI.emptyState('Nothing belongs in the draft', (result.notes && result.notes[0])
        || 'No candidate passed the economic and capital constraints. Cash remains the answer.'));
      return;
    }
    host.appendChild(alertBox(result.diagnostic || result.teachingOnly ? 'caution' : 'ok',
      result.diagnostic ? 'Diagnostic comparison set — not a recommendation'
        : result.teachingOnly ? 'Practice construction — generated or incomplete evidence'
          : 'Construction draft — every allocation still needs its own Plan review',
      result.diagnostic ? ['Mixed, adverse, or unavailable ideas may appear so you can inspect the least-bad set.']
        : ['This allocates research capital only. It does not place trades or reserve buying power.']));
    host.appendChild(el('div', { class: 'fact-grid portfolio-construct-summary', id: 'portfolio-summary' },
      UI.fact('Capital in draft', fmtMoney(result.capitalUsedCents)),
      UI.fact('Positions', String(allocations.length)),
      UI.fact('Market EV after costs', result.marketEvAfterCostsCents == null ? '\u2014' : pnlSpan(result.marketEvAfterCostsCents),
        result.marketEvAfterCostsCents == null ? '' : result.marketEvAfterCostsCents >= 0 ? 'f-ok' : 'f-danger'),
      UI.fact('History EV after costs', result.realizedVolEvAfterCostsCents == null ? '\u2014' : pnlSpan(result.realizedVolEvAfterCostsCents),
        result.realizedVolEvAfterCostsCents == null ? '' : result.realizedVolEvAfterCostsCents >= 0 ? 'f-ok' : 'f-danger'),
      UI.fact('Worst modeled tail', el('span', { class: 'loss' }, fmtMoney(-Math.abs(result.totalTailLossCents || 0))), 'f-danger'),
      UI.fact('Average Decision score', String(Math.round(result.avgScore || 0)))));
    host.appendChild(el('h3', {}, 'Capital by symbol'));
    host.appendChild(portfolioComposition(result.perSymbolCents));
    if (Learn.currentLevel() === 'expert') {
      host.appendChild(table(['Symbol', 'Structure', 'Verdict', 'Units', 'Capital', 'Score', 'Market EV', 'History EV', ''], allocations.map(function (allocation) {
        var evaluation = allocation.eval || {}, candidate = evaluation.candidate || {};
        var symbol = evaluation.symbol || (evaluation.spec && evaluation.spec.symbol) || candidate.symbol || '\u2014';
        var economics = evaluation.economics || {};
        var button = el('button', { type: 'button', class: 'btn btn-sm' }, 'Review in Plan');
        button.onclick = function () {
          visibleCommand(button, function () { return openOptimizationPlan(allocation, form); },
            'This allocation could not be opened in a Plan.');
        };
        return el('tr', {}, el('td', {}, symbol),
          el('td', {}, candidate.displayName || evaluation.family || '\u2014'),
          el('td', {}, economics.label || economics.verdict || 'Unavailable'), el('td', {}, String(allocation.units)),
          el('td', {}, fmtMoney(allocation.capitalCents)), el('td', {}, UI.scoreBar(evaluation.decisionScore || 0, 'Decision score')),
          el('td', {}, evaluation.economics && evaluation.economics.marketEvAfterCostsCents != null ? pnlSpan(evaluation.economics.marketEvAfterCostsCents) : '\u2014'),
          el('td', {}, evaluation.economics && evaluation.economics.realizedVolEvAfterCostsCents != null ? pnlSpan(evaluation.economics.realizedVolEvAfterCostsCents) : '\u2014'),
          el('td', {}, button));
      })));
    } else {
      host.appendChild(el('div', { class: 'portfolio-allocation-grid' }, allocations.map(function (allocation) {
        var evaluation = allocation.eval || {}, candidate = evaluation.candidate || {};
        var symbol = evaluation.symbol || (evaluation.spec && evaluation.spec.symbol) || candidate.symbol || '\u2014';
        var economics = evaluation.economics || {};
        var button = el('button', { type: 'button', class: 'btn btn-sm' }, 'Review in a new Plan');
        button.onclick = function () {
          visibleCommand(button, function () { return openOptimizationPlan(allocation, form); },
            'This allocation could not be opened in a Plan.');
        };
        return el('article', { class: 'card portfolio-allocation-card' },
          el('div', { class: 'plan-book-head' }, el('div', {}, el('div', { class: 'eyebrow' }, 'ALLOCATION DRAFT'),
            el('h3', {}, symbol)),
            el('span', { class: 'badge badge-dim' }, economics.label || economics.verdict || 'Unavailable')),
          el('p', {}, candidate.displayName || evaluation.family || 'Structure'),
          el('div', { class: 'chip-row' }, chip('Capital', fmtMoney(allocation.capitalCents)),
            chip('Units', String(allocation.units)),
            chip('Decision score', String(Math.round(evaluation.decisionScore || 0))),
            chip('Market EV', evaluation.economics && evaluation.economics.marketEvAfterCostsCents != null
              ? pnlSpan(evaluation.economics.marketEvAfterCostsCents) : '\u2014'),
            chip('History EV', evaluation.economics && evaluation.economics.realizedVolEvAfterCostsCents != null
              ? pnlSpan(evaluation.economics.realizedVolEvAfterCostsCents) : '\u2014')), button);
      })));
    }
    (result.notes || []).forEach(function (note) { host.appendChild(el('p', { class: 'muted small' }, note)); });
  }

  function portfolioConstruct(acct) {
    var state = App.state.portfolioConstruct = App.state.portfolioConstruct || {};
    var form = {
      budget: state.budget || Math.round(acct.buyingPowerCents / 100), scope: state.scope || 'universe',
      goal: state.goal || '', thesis: state.thesis || 'neutral', horizon: state.horizon || 'month',
      objective: state.objective || 'DECISION', maxPositions: state.maxPositions || 8,
      maxSymbolPct: state.maxSymbolPct || 40, maxPerPosition: state.maxPerPosition || '', diagnostic: !!state.diagnostic,
      riskMode: (document.getElementById('risk-mode') || {}).value || 'conservative'
    };
    function select(id, values, current) {
      var node = el('select', { id: id }, values.map(function (value) {
        return el('option', { value: value[0], selected: value[0] === current ? 'selected' : null }, value[1]);
      })); return node;
    }
    var budget = el('input', { id: 'portfolio-budget', type: 'number', min: '0', step: '1000', value: form.budget });
    var scope = select('portfolio-scope', [['universe', 'Current universe'], ['symbol', 'Working stock only']], form.scope);
    var goal = select('portfolio-goal', [['', 'Any goal']].concat((Learn.INTENTS || []).map(function (meta) { return [meta.key, meta.label]; })), form.goal);
    var thesis = select('portfolio-thesis', [['neutral', 'Neutral'], ['bullish', 'Bullish'], ['bearish', 'Bearish'], ['volatile', 'Big move']], form.thesis);
    var horizon = select('portfolio-horizon', [['week', 'About 1 week'], ['month', 'About 1 month'], ['quarter', 'About 3 months']], form.horizon);
    var objective = select('portfolio-objective', [['DECISION', 'Best Decision score'], ['MARKET_EV', 'Best market EV after costs'], ['HISTORY_EV', 'Best history EV after costs']], form.objective);
    var maxPositions = el('input', { id: 'portfolio-max-positions', type: 'number', min: '1', max: '20', value: form.maxPositions });
    var maxSymbol = el('input', { id: 'portfolio-max-symbol-pct', type: 'number', min: '5', max: '100', step: '5', value: form.maxSymbolPct });
    var maxPer = el('input', { id: 'portfolio-max-position', type: 'number', min: '0', step: '100', value: form.maxPerPosition, placeholder: '25% default' });
    var diagnostic = el('input', { id: 'portfolio-diagnostics', type: 'checkbox', checked: form.diagnostic ? '' : null });
    function field(label, node) { return UI.field(label, node); }
    var primary = el('div', { class: 'form-grid portfolio-construct-primary' }, field('Capital to allocate ($)', budget), field('Ideas from', scope), field('Goal', goal), field('Market view', thesis), field('Horizon', horizon));
    var advancedBody = el('div', { class: 'portfolio-construct-advanced' },
      el('div', { class: 'form-grid' }, field('Rank by', objective), field('Maximum positions', maxPositions),
        field('Maximum per symbol (%)', maxSymbol), field('Maximum per position ($)', maxPer)),
      el('label', { class: 'check-row' }, diagnostic,
        Learn.currentLevel() === 'beginner' ? ' Show a diagnostic comparison when nothing earns a favorable verdict' : ' Diagnostic mode: include viable non-favorable ideas'));
    var output = el('div', { class: 'portfolio-construct-output', id: 'portfolio-output' });
    var run = el('button', { type: 'button', class: 'btn', id: 'portfolio-build', onclick: async function () {
      form = { budget: Number(budget.value), scope: scope.value, goal: goal.value, thesis: thesis.value, horizon: horizon.value,
        objective: objective.value, maxPositions: Number(maxPositions.value), maxSymbolPct: Number(maxSymbol.value),
        maxPerPosition: maxPer.value, diagnostic: diagnostic.checked,
        riskMode: (document.getElementById('risk-mode') || {}).value || 'conservative' };
      Object.assign(state, form); run.disabled = true; output.innerHTML = ''; output.appendChild(UI.spinner('Scanning and constructing one coherent draft\u2026'));
      var working = App.context.symbol();
      var request = { totalCapitalCents: Math.round(form.budget * 100), intent: form.goal || null,
        thesis: form.thesis, horizon: form.horizon, riskMode: form.riskMode, objective: form.objective,
        maxPositions: form.maxPositions, maxSymbolPct: form.maxSymbolPct / 100,
        maxPerPositionCents: form.maxPerPosition ? Math.round(Number(form.maxPerPosition) * 100) : null,
        diagnostic: form.diagnostic };
      if (form.scope === 'symbol') {
        if (!working) { output.innerHTML = ''; output.appendChild(alertBox('warn', 'Choose a working stock in Research first.')); run.disabled = false; return; }
        request.universe = [working];
      }
      try { state.result = await API.post('/api/optimize', request); renderPortfolioConstructionResult(output, state.result, form); }
      catch (e) { output.innerHTML = ''; output.appendChild(alertBox('danger', 'Construction failed', [e.message])); }
      finally { run.disabled = false; }
    } }, 'Build construction draft');
    var card = el('section', { class: 'card portfolio-construct', id: 'portfolio-construct' },
      UI.cardHeader('Construct across ideas', el('span', { class: 'badge badge-dim' }, 'DRAFT ONLY')),
      el('p', { class: 'muted' }, Learn.currentLevel() === 'beginner'
        ? 'Ask how several ideas could fit together without placing anything. StrikeBench keeps cash as the baseline and opens each allocation through its own Plan.'
        : 'Allocate a research budget across economically eligible evaluations with explicit concentration, objective and evidence constraints. No trade or reserve is created.'),
      primary,
      Learn.currentLevel() === 'beginner' ? UI.expandable('More construction controls', function () { return advancedBody; }) : advancedBody,
      el('div', { class: 'btn-row' }, run), output);
    if (state.result) renderPortfolioConstructionResult(output, state.result, form);
    return card;
  }

  function portfolioModeNav(tracked) {
    var list = el('div', { class: 'portfolio-mode seg', role: 'tablist', 'aria-label': 'Portfolio workspace' },
      el('button', { type: 'button', role: 'tab', class: tracked ? '' : 'active', 'aria-selected': tracked ? 'false' : 'true',
        tabindex: tracked ? '-1' : '0',
        onclick: function () { App.navigate('#/portfolio/positions'); } }, 'Paper account'),
      el('button', { type: 'button', role: 'tab', class: tracked ? 'active' : '', 'aria-selected': tracked ? 'true' : 'false',
        tabindex: tracked ? '0' : '-1',
        onclick: function () { App.navigate('#/portfolio/book/overview'); } }, 'Tracked accounts'));
    return UI.bindTabList(list, function (tab) { tab.click(); });
  }

  var PORTFOLIO_ACCOUNT_TYPES = [
    ['TAXABLE', 'Taxable brokerage'], ['TRADITIONAL_IRA', 'Traditional IRA'], ['ROTH_IRA', 'Roth IRA'],
    ['TRADITIONAL_401K', 'Traditional 401(k)'], ['ROTH_401K', 'Roth 401(k)']
  ];

  function portfolioAccountTypeLabel(value) {
    var match = PORTFOLIO_ACCOUNT_TYPES.find(function (row) { return row[0] === value; });
    return match ? match[1] : String(value || '').replaceAll('_', ' ').toLowerCase();
  }

  function portfolioAccountForm(existing, onSaved) {
    existing = existing || {};
    var archived = existing.status === 'ARCHIVED';
    function opt(value, label, selected) { return el('option', { value: value, selected: value === selected ? 'selected' : null }, label); }
    var name = el('input', { type: 'text', maxlength: '100', value: existing.name || '', placeholder: 'e.g. Main taxable account' });
    var type = el('select', {}, PORTFOLIO_ACCOUNT_TYPES.map(function (row) { return opt(row[0], row[1], existing.accountType || 'TAXABLE'); }));
    var broker = el('input', { type: 'text', maxlength: '100', value: existing.broker || '', placeholder: 'Optional' });
    var method = el('select', {}, [['FIFO', 'FIFO · oldest lots first'], ['LIFO', 'LIFO · newest lots first'], ['HIFO', 'HIFO · tax-aware basis/proceeds']]
      .map(function (row) { return opt(row[0], row[1], existing.lotMethod || 'FIFO'); }));
    var opening = el('input', { type: 'number', min: '0', step: '0.01', value: '', placeholder: 'Optional' });
    var st = el('input', { type: 'number', min: '0', max: '100', step: '0.1', value: existing.shortTermTaxRateBps == null ? '' : existing.shortTermTaxRateBps / 100 });
    var lt = el('input', { type: 'number', min: '0', max: '100', step: '0.1', value: existing.longTermTaxRateBps == null ? '' : existing.longTermTaxRateBps / 100 });
    var ordinary = el('input', { type: 'number', min: '0', max: '100', step: '0.1', value: existing.ordinaryTaxRateBps == null ? '' : existing.ordinaryTaxRateBps / 100 });
    var state = el('input', { type: 'number', min: '0', max: '100', step: '0.1', value: existing.stateTaxRateBps == null ? '' : existing.stateTaxRateBps / 100 });
    if (existing.id) type.disabled = true;
    if (archived) [name, broker, method, st, lt, ordinary, state].forEach(function (control) { control.disabled = true; });
    var taxFields = el('div', { class: 'form-grid portfolio-tax-rate-fields' },
      UI.field('Short-term scenario rate %', st), UI.field('Long-term scenario rate %', lt),
      UI.field('Ordinary-income scenario rate %', ordinary), UI.field('State scenario rate %', state));
    var taxNote = el('p', { class: 'muted small' },
      'Not tax advice. These user-supplied rates drive only a reviewed-year scenario. They never rewrite lots, basis, transactions, or claim to calculate tax owed.');
    function syncTax() { taxFields.hidden = taxNote.hidden = type.value !== 'TAXABLE'; }
    type.addEventListener('change', syncTax); syncTax();
    function bps(input) {
      if (input.value === '') return null;
      var n = Number(input.value); if (!Number.isFinite(n) || n < 0 || n > 100) throw new Error('Tax rates must be between 0% and 100%.');
      return Math.round(n * 100);
    }
    var msg = el('div', { class: 'small', 'aria-live': 'polite' });
    var save = el('button', { type: 'button', class: 'btn', disabled: archived ? 'disabled' : null, onclick: async function () {
      save.disabled = true; save.setAttribute('aria-busy', 'true'); msg.textContent = '';
      try {
        var payload = { name: name.value, accountType: type.value, broker: existing.id ? broker.value : (broker.value || null),
          lotMethod: method.value, shortTermTaxRateBps: bps(st), longTermTaxRateBps: bps(lt),
          ordinaryTaxRateBps: bps(ordinary), stateTaxRateBps: bps(state) };
        if (!existing.id && opening.value !== '') {
          var openingValue = Number(opening.value);
          if (!Number.isFinite(openingValue) || openingValue < 0) throw new Error('Opening cash must be a non-negative amount.');
          payload.openingCashCents = Math.round(openingValue * 100);
        }
        var saved = existing.id ? await API.put('/api/portfolio/accounts/' + existing.id, payload)
          : await API.post('/api/portfolio/accounts', payload);
        App.state.portfolioBookAccountId = saved.id;
        try { localStorage.setItem('strikebench.portfolioBookAccount', saved.id); } catch (e) { /* optional */ }
        UI.toast(existing.id ? 'Account settings saved' : 'Tracked account created', 'ok');
        if (onSaved) await onSaved(saved);
      } catch (e) { msg.textContent = e.message || String(e); msg.className = 'small loss'; }
      finally { save.disabled = archived; save.removeAttribute('aria-busy'); }
    } }, existing.id ? 'Save settings' : 'Create tracked account');
    return el('div', { class: 'portfolio-account-form' },
      el('div', { class: 'form-grid' }, UI.field('Account name', name), UI.field('Account type', type,
        existing.id ? { hint: 'The tax wrapper is fixed for this book. Use a separate tracked account for another wrapper.' } : null),
        UI.field('Broker', broker), UI.field('Tax-lot method', method,
          { hint: 'HIFO uses the highest remaining basis for long lots and the lowest remaining opening proceeds for short lots. Future closes use this method; realized matches never change.' }),
        existing.id ? null : UI.field('Opening cash $', opening, { hint: 'Establishes this tracked book’s opening balance; it is not treated as an in-period contribution, and practice cash is untouched.' })),
      taxFields,
      taxNote,
      Learn.currentLevel() === 'beginner' ? explain('A taxable account estimates current capital-gains and income tax from the rates you enter. IRA and 401(k) activity is tracked, but per-trade gains are not presented as currently taxable.') : null,
      el('div', { class: 'btn-row' }, save), msg);
  }

  function portfolioBookTabs(section) {
    var tabs = [['overview', 'Overview'], ['activity', 'Activity'], ['performance', 'Performance'],
      ['tax', 'Taxes & export'], ['settings', 'Settings']];
    var list = el('div', { class: 'tabs portfolio-book-tabs', role: 'tablist', 'aria-label': 'Tracked account sections' },
      tabs.map(function (tab) { return el('button', { type: 'button', role: 'tab', class: section === tab[0] ? 'active' : '',
        'aria-selected': section === tab[0] ? 'true' : 'false', tabindex: section === tab[0] ? '0' : '-1',
        onclick: function () { App.navigate('#/portfolio/book/' + tab[0]); } }, tab[1]); }));
    return UI.bindTabList(list, function (tab) { tab.click(); });
  }

  function portfolioPositionLabel(p) {
    return p.instrumentType === 'STOCK' ? p.symbol + ' shares'
      : p.symbol + ' ' + p.expiration + ' · ' + fmtNum(Number(p.strike), 2) + ' ' + p.optionType.toLowerCase();
  }

  function portfolioRollPosition(account, position) {
    var longSide = position.side === 'LONG';
    var quantity = el('input', { type: 'number', min: '1', max: String(position.quantity), step: '1', value: String(position.quantity) });
    var closePrice = el('input', { type: 'number', min: '0', step: '0.0001',
      value: position.liquidationPrice == null ? '' : Number(position.liquidationPrice).toFixed(4), placeholder: 'Exact closing fill' });
    var nextStrike = el('input', { type: 'number', min: '0.0001', step: '0.01', value: Number(position.strike).toFixed(2) });
    var nextExpiry = el('input', { type: 'date' });
    var openPrice = el('input', { type: 'number', min: '0', step: '0.0001', placeholder: 'Exact replacement fill' });
    var fees = el('input', { type: 'number', min: '0', step: '0.01', value: '0.00' });
    var occurred = el('input', { type: 'datetime-local', step: '1', value: portfolioNowLocal() });
    var source = portfolioSelect([['MANUAL', 'Entered manually'], ['BROKER', 'Copied from broker']], 'MANUAL');
    var reference = el('input', { type: 'text', maxlength: '160', placeholder: 'Optional order or statement reference' });
    var notes = el('textarea', { rows: '2', maxlength: '1000', placeholder: 'Why you rolled or what changed' });
    var section1256 = el('input', { type: 'checkbox', checked: position.section1256 ? 'checked' : null,
      disabled: 'disabled' });
    var body = el('div', { class: 'book-roll-form' },
      el('p', {}, el('b', {}, portfolioPositionLabel(position)), ' · ', longSide ? 'long' : 'short', ' · ', position.quantity, ' open'),
      el('p', { class: 'muted small' }, 'This records one linked transaction: the old contract closes and realizes its gain or loss; the replacement opens with its own exact basis. Net premium carryover is reported separately and never substituted into tax basis.'),
      el('div', { class: 'form-grid book-roll-grid' },
        UI.field('Contracts to roll', quantity), UI.field('Activity date and time', occurred),
        UI.field('Exact closing price $', closePrice), UI.field('Replacement strike $', nextStrike),
        UI.field('Replacement expiration', nextExpiry), UI.field('Exact replacement price $', openPrice),
        UI.field('Total fees $', fees), UI.field('Source', source),
        UI.field('Broker reference', reference)),
      el('label', { class: 'check-row' }, section1256,
        el('span', {}, 'Section 1256 contract', el('small', {}, 'Tax classification carries from the position being rolled.'))),
      UI.field('Notes', notes));
    UI.confirmModal('Roll ' + position.symbol + ' ' + position.optionType.toLowerCase(), body, 'Record roll', async function () {
      var qty = Number(quantity.value), closing = Number(closePrice.value), opening = Number(openPrice.value);
      var strike = Number(nextStrike.value), feeAmount = Number(fees.value || 0);
      if (!Number.isInteger(qty) || qty < 1 || qty > position.quantity) throw new Error('Contracts must be between 1 and ' + position.quantity + '.');
      if (!Number.isFinite(closing) || closing < 0) throw new Error('Enter the exact closing fill price.');
      if (!Number.isFinite(opening) || opening < 0) throw new Error('Enter the exact replacement fill price.');
      if (!Number.isFinite(strike) || strike <= 0 || !nextExpiry.value) throw new Error('Enter the replacement strike and expiration.');
      if (!Number.isFinite(feeAmount) || feeAmount < 0) throw new Error('Fees must be zero or more.');
      var explicit1256 = Boolean(position.section1256);
      var out = await API.post('/api/portfolio/accounts/' + account.id + '/transactions', {
        occurredAt: portfolioInstant(occurred, 'Activity date and time'), eventType: 'ROLL',
        cashAmountCents: null, feesCents: Math.round(feeAmount * 100), taxCategory: null,
        source: source.value, externalRef: reference.value || null, notes: notes.value || null,
        legs: [
          { instrumentType: 'OPTION', action: longSide ? 'SELL' : 'BUY', positionEffect: 'CLOSE',
            symbol: position.symbol, optionType: position.optionType, strike: position.strike,
            expiration: position.expiration, quantity: qty, multiplier: position.multiplier,
            price: closing, section1256: explicit1256 },
          { instrumentType: 'OPTION', action: longSide ? 'BUY' : 'SELL', positionEffect: 'OPEN',
            symbol: position.symbol, optionType: position.optionType, strike: strike,
            expiration: nextExpiry.value, quantity: qty, multiplier: position.multiplier,
            price: opening, section1256: explicit1256 }
        ]
      });
      UI.toast('Roll recorded · net premium ' + fmtMoney(out.roll.premiumCarryoverCents, { plus: true }) + ' before fees', 'ok');
      await App.render();
    });
  }

  function renderPortfolioBookOverview(root, account, summary) {
    var stats = el('div', { class: 'grid grid-4 book-summary-stats' },
      stat('Total value', summary.totalValueCents == null
        ? el('span', { class: 'muted' }, 'Unavailable') : fmtMoney(summary.totalValueCents),
        summary.complete ? 'Cash plus executable liquidation value.' : 'A total is withheld because one or more positions cannot be marked.'),
      stat('Cash in this book', fmtMoney(summary.bookCashCents), 'Recorded cash effects only; this never includes practice cash.'),
      stat('Realized P/L', pnlSpan(summary.realizedPnlCents), 'Exact matched-lot gains and losses after recorded opening and closing fees.'),
      stat('Unrealized P/L', summary.unrealizedPnlCents == null ? el('span', { class: 'muted' }, 'Unavailable') : pnlSpan(summary.unrealizedPnlCents),
        'What closing the recorded open lots at executable sides would produce, before new close fees.'));
    root.appendChild(stats);
    if (!summary.complete) root.appendChild(alertBox('caution', 'Current value is partial', [
      'Missing observed executable marks: ' + (summary.missingMarks || []).join(', ') + '. The book keeps basis and activity intact and does not turn missing prices into zero or use Demo, simulated, or modeled prices for an external account.'
    ]));

    var col = summary.collateral;
    root.appendChild(el('section', { class: 'card card-slim book-capital' },
      UI.cardHeader('Cash and obligations'),
      el('div', { class: 'chip-row' }, chip('Known cash blocked', fmtMoney(col.knownBlockedCashCents)),
        chip('Available cash', col.availableCashCents == null ? 'Not estimated' : fmtMoney(col.availableCashCents)),
        col.cashSecuredPutContracts ? chip('Cash-secured puts', String(col.cashSecuredPutContracts)) : null,
        col.definedRiskPutContracts ? chip('Put spreads', String(col.definedRiskPutContracts)) : null,
        col.coveredCallContracts ? chip('Covered calls', String(col.coveredCallContracts)) : null,
        col.uncoveredShortCallShares ? el('span', { class: 'badge badge-danger' }, col.uncoveredShortCallShares + ' uncovered call shares') : null),
      el('p', { class: 'muted small' }, (col.notes || []).join(' '))));

    var positions = summary.positions || [];
    var posCard = el('section', { class: 'card book-positions' }, UI.cardHeader('Open positions',
      el('span', { class: 'badge badge-dim' }, positions.length + ' instrument' + (positions.length === 1 ? '' : 's'))));
    if (!positions.length) posCard.appendChild(UI.emptyState('No open positions recorded',
      'Use Activity to record a broker fill, assignment, exercise, expiration, or stock transaction.'));
    else if (Learn.currentLevel() === 'beginner') {
      var list = el('div', { class: 'book-position-list' });
      positions.forEach(function (p) {
        list.appendChild(el('article', { class: 'book-position-row' },
          el('div', {}, el('h3', {}, portfolioPositionLabel(p)),
            el('p', { class: 'muted' }, (p.side === 'LONG' ? 'You own ' : 'You are short ') + p.quantity + (p.instrumentType === 'OPTION' ? ' contract' + (p.quantity === 1 ? '' : 's') : ' share' + (p.quantity === 1 ? '' : 's')))),
          el('div', { class: 'book-position-facts' },
            chip(p.side === 'LONG' ? 'Cost basis' : 'Opening proceeds', fmtMoney(p.openAmountCents)),
            chip('Close value now', p.liquidationValueCents == null ? 'Unavailable' : fmtMoney(p.liquidationValueCents)),
            chip('Gain / loss now', p.unrealizedPnlCents == null ? 'Unavailable' : pnlSpan(p.unrealizedPnlCents))),
          el('div', { class: 'muted small' }, p.complete
            ? [p.provenance, p.age === 'NOT_APPLICABLE' ? null : p.age, p.source].filter(Boolean).join(' · ')
            : 'No executable closing price is available.'),
          p.instrumentType === 'OPTION' ? el('div', { class: 'btn-row' },
            el('button', { type: 'button', class: 'btn btn-secondary btn-sm', disabled: account.status === 'ARCHIVED' ? 'disabled' : null,
              onclick: function () { portfolioRollPosition(account, p); } }, 'Roll position')) : null));
      });
      posCard.appendChild(list);
    } else {
      posCard.appendChild(table(['Position', 'Side', 'Qty', 'Basis / proceeds', 'Close price', 'Liquidation value', 'Unrealized', 'Evidence', 'Action'],
        positions.map(function (p) { return el('tr', {}, el('td', {}, el('b', {}, portfolioPositionLabel(p))),
          el('td', {}, p.side), el('td', {}, String(p.quantity) + (p.multiplier !== 1 ? ' ×' + p.multiplier : '')),
          el('td', {}, fmtMoney(p.openAmountCents)), el('td', {}, p.liquidationPrice == null ? '—' : '$' + Number(p.liquidationPrice).toFixed(4)),
          el('td', {}, p.liquidationValueCents == null ? '—' : fmtMoney(p.liquidationValueCents)),
          el('td', {}, p.unrealizedPnlCents == null ? '—' : pnlSpan(p.unrealizedPnlCents)),
          el('td', {}, badge(p.provenance), p.age === 'NOT_APPLICABLE' ? null
            : (p.age && p.age !== p.provenance ? el('span', { class: 'muted small' }, ' ' + p.age) : null)),
          el('td', {}, p.instrumentType === 'OPTION' ? el('button', { type: 'button', class: 'btn btn-secondary btn-sm',
            disabled: account.status === 'ARCHIVED' ? 'disabled' : null, onclick: function () { portfolioRollPosition(account, p); } }, 'Roll') : '—')); })));
    }
    root.appendChild(posCard);

    var allocation = summary.allocation || { byAssetClass: [], bySector: [], byDirection: [], bySymbol: [] };
    function allocationRows(rows, basisLabel) {
      var box = el('div', { class: 'book-exposure-rows' });
      (rows || []).forEach(function (a) {
        box.appendChild(el('div', { class: 'allocation-row' },
          el('div', { class: 'allocation-label' }, el('b', {}, a.label),
            el('span', { class: 'muted small' }, fmtPct(a.percentOfTotal) + ' ' + basisLabel)),
          el('div', { class: 'allocation-track', role: 'img',
            'aria-label': a.label + ' ' + fmtPct(a.percentOfTotal) + ' of ' + basisLabel },
            el('span', { style: 'width:' + Math.max(1, Math.round((a.percentOfTotal || 0) * 100)) + '%' })),
          el('div', { class: 'book-exposure-money' },
            el('span', {}, 'Long ', fmtMoney(a.longExposureCents)),
            el('span', {}, 'Short ', fmtMoney(a.shortExposureCents)),
            el('b', { class: a.netExposureCents < 0 ? 'loss' : '' }, 'Net ', fmtMoney(a.netExposureCents, { plus: true })))))
      });
      return box;
    }
    var allocCard = el('section', { class: 'card book-allocation' }, UI.cardHeader('Allocation & market exposure'),
      el('p', { class: 'muted small' }, 'Cash stays in capital allocation but is not market exposure. Security long and short are positive magnitudes; gross adds them and net subtracts short from long. Missing marks are excluded, never treated as zero.'),
      el('div', { class: 'chip-row book-exposure-totals' },
        chip('Security long', fmtMoney(allocation.longExposureCents || 0)),
        chip('Security short', fmtMoney(allocation.shortExposureCents || 0)),
        chip('Market gross', fmtMoney(allocation.grossExposureCents || 0)),
        chip('Market net', fmtMoney(allocation.netExposureCents || 0, { plus: true }))));
    var allocationGroups = el('div', { class: 'book-allocation-groups' },
      el('section', { class: 'book-allocation-group' }, el('h3', {}, 'By asset class'), allocationRows(allocation.byAssetClass, 'of known capital')),
      el('section', { class: 'book-allocation-group' }, el('h3', {}, 'By sector'), allocationRows(allocation.bySector, 'of market gross')),
      el('section', { class: 'book-allocation-group' }, el('h3', {}, 'Long and short'), allocationRows(allocation.byDirection, 'of market gross')));
    allocCard.appendChild(allocationGroups);
    allocCard.appendChild(UI.expandable(
      el('span', {}, 'By symbol · ', (allocation.bySymbol || []).length, ' rows'),
      function () { return allocationRows(allocation.bySymbol, 'of market gross'); }));
    root.appendChild(allocCard);
  }

  function portfolioNowLocal() {
    var now = new Date(), offset = now.getTimezoneOffset() * 60000;
    return new Date(now.getTime() - offset).toISOString().slice(0, 19);
  }

  function portfolioInstant(input, label) {
    var parsed = new Date(input.value);
    if (!input.value || isNaN(parsed.getTime())) throw new Error((label || 'Date and time') + ' is required.');
    return parsed.toISOString();
  }

  function portfolioOccurredLabel(raw) {
    if (!raw) return 'Date unavailable';
    if (/T12:00:00(?:\.0+)?(?:Z|\+00:00)$/.test(raw)) {
      return new Date(raw).toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' });
    }
    var parsed = new Date(raw);
    if (isNaN(parsed.getTime())) return String(raw).slice(0, 10);
    return parsed.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
  }

  function portfolioTaxDate(raw) {
    var parsed = new Date(raw);
    if (!raw || isNaN(parsed.getTime())) return String(raw || '').slice(0, 10);
    var parts = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York',
      year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(parsed);
    var values = {};
    parts.forEach(function (part) { if (part.type !== 'literal') values[part.type] = part.value; });
    return values.year + '-' + values.month + '-' + values.day;
  }

  function portfolioLegLabel(leg) {
    if (leg.instrumentType === 'STOCK') return leg.action + ' ' + leg.positionEffect.toLowerCase() + ' '
      + leg.quantity + ' ' + leg.symbol + ' shares @ $' + Number(leg.price).toFixed(4);
    return leg.action + ' ' + leg.positionEffect.toLowerCase() + ' ' + leg.quantity + ' '
      + leg.symbol + ' ' + leg.expiration + ' ' + Number(leg.strike).toFixed(2) + ' '
      + leg.optionType.toLowerCase() + ' @ $' + Number(leg.price).toFixed(4) + ' ×' + leg.multiplier;
  }

  function portfolioSelect(values, current, attrs) {
    return el('select', attrs || {}, values.map(function (row) {
      return el('option', { value: row[0], selected: row[0] === current ? 'selected' : null }, row[1]);
    }));
  }

  function portfolioLegEditor(defaults, remove) {
    defaults = defaults || {};
    var instrument = portfolioSelect([['STOCK', 'Shares'], ['OPTION', 'Option contract']], defaults.instrumentType || 'STOCK');
    var action = portfolioSelect([['BUY', 'Buy'], ['SELL', 'Sell']], defaults.action || 'BUY');
    var effect = portfolioSelect([['OPEN', 'Open a position'], ['CLOSE', 'Close a position']], defaults.positionEffect || 'OPEN');
    var symbol = el('input', { type: 'text', maxlength: '20', value: defaults.symbol || '', placeholder: 'AAPL', list: 'universe-symbols' });
    var optionType = portfolioSelect([['CALL', 'Call'], ['PUT', 'Put']], defaults.optionType || 'CALL');
    var strike = el('input', { type: 'number', min: '0.0001', step: '0.01', value: defaults.strike || '', placeholder: '250.00' });
    var expiration = el('input', { type: 'date', value: defaults.expiration || '' });
    var quantity = el('input', { type: 'number', min: '1', max: '10000000', step: '1', value: defaults.quantity || '1' });
    var multiplier = el('input', { type: 'number', min: '1', max: '10000', step: '1', value: defaults.multiplier || '100' });
    var price = el('input', { type: 'number', min: '0', step: '0.0001', value: defaults.price == null ? '' : defaults.price, placeholder: '0.00' });
    var section1256 = el('input', { type: 'checkbox', checked: defaults.section1256 ? 'checked' : null });
    var automatic1256 = App.config && Array.isArray(App.config.broadBasedIndexOptionSymbols)
      ? App.config.broadBasedIndexOptionSymbols.join(', ') : 'known broad-based index roots and listed series';
    var section1256Field = el('label', { class: 'check-row book-1256-flag' }, section1256,
      el('span', {}, 'Other Section 1256 contract', el('small', {}, 'Automatic: ' + automatic1256 + '. Check this only for another eligible contract confirmed by your broker.')));
    var optionFields = [optionType, strike, expiration, multiplier];
    var row = el('fieldset', { class: 'book-leg-row' },
      el('legend', {}, defaults.legend || 'Security leg'),
      el('div', { class: 'book-leg-grid' },
        UI.field('Instrument', instrument), UI.field('Action', action), UI.field('Position', effect),
        UI.field('Symbol', symbol),
        UI.field('Option type', optionType), UI.field('Strike $', strike), UI.field('Expiration', expiration),
        UI.field('Quantity', quantity), UI.field('Multiplier', multiplier), UI.field('Exact price $', price), section1256Field),
      remove ? el('button', { type: 'button', class: 'btn btn-secondary btn-sm book-leg-remove',
        'aria-label': 'Remove this security leg', onclick: function () { row.remove(); } }, 'Remove leg') : null);
    function syncInstrument() {
      var isOption = instrument.value === 'OPTION';
      optionFields.forEach(function (node) { var field = node.closest('.field'); if (field) field.hidden = !isOption; });
      section1256Field.hidden = !isOption;
      if (!isOption) multiplier.value = '1';
      else if (multiplier.value === '1') multiplier.value = '100';
    }
    instrument.addEventListener('change', syncInstrument); syncInstrument();
    row.read = function () {
      var qty = Number(quantity.value), mult = Number(multiplier.value), px = Number(price.value);
      if (!symbol.value.trim()) throw new Error('Every security leg needs a symbol.');
      if (!Number.isInteger(qty) || qty <= 0) throw new Error('Every leg quantity must be a positive whole number.');
      if (!Number.isInteger(mult) || mult <= 0) throw new Error('Every leg multiplier must be a positive whole number.');
      if (!Number.isFinite(px) || px < 0) throw new Error('Every leg needs an exact non-negative price.');
      if (instrument.value === 'OPTION' && (!strike.value || !expiration.value)) {
        throw new Error('Every option leg needs a strike and expiration.');
      }
      return { instrumentType: instrument.value, action: action.value, positionEffect: effect.value,
        symbol: symbol.value.trim().toUpperCase(), optionType: instrument.value === 'OPTION' ? optionType.value : null,
        strike: instrument.value === 'OPTION' ? strike.value : null,
        expiration: instrument.value === 'OPTION' ? expiration.value : null, quantity: qty,
        multiplier: instrument.value === 'OPTION' ? mult : 1, price: px,
        section1256: instrument.value === 'OPTION' && section1256.checked ? true : null };
    };
    row.controls = { instrument: instrument, action: action, effect: effect, symbol: symbol,
      optionType: optionType, strike: strike, expiration: expiration, quantity: quantity,
      multiplier: multiplier, price: price, section1256: section1256 };
    return row;
  }

  function portfolioTransactionForm(account) {
    var cashEvents = ['DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT', 'INTEREST', 'DIVIDEND', 'FEE', 'ADJUSTMENT'];
    var event = portfolioSelect([
      ['TRADE', 'Buy, sell, open, or close securities'], ['ROLL', 'Roll an option position'], ['ASSIGNMENT', 'Record an option assignment'],
      ['EXERCISE', 'Record an option exercise'], ['EXPIRATION', 'Record an option expiration'],
      ['DEPOSIT', 'Deposit'], ['WITHDRAWAL', 'Withdrawal'], ['TRANSFER_IN', 'Transfer in'],
      ['TRANSFER_OUT', 'Transfer out'], ['INTEREST', 'Interest received'], ['DIVIDEND', 'Dividend received'],
      ['FEE', 'Account fee'], ['ADJUSTMENT', 'Cash adjustment']
    ], 'TRADE', { id: 'portfolio-book-event' });
    var occurred = el('input', { type: 'datetime-local', step: '1', value: portfolioNowLocal() });
    var amount = el('input', { type: 'number', step: '0.01', value: '', placeholder: '0.00' });
    var fees = el('input', { type: 'number', min: '0', step: '0.01', value: '0.00' });
    var source = portfolioSelect([['MANUAL', 'Entered manually'], ['BROKER', 'Copied from broker']], 'MANUAL');
    var taxCategory = portfolioSelect([['', 'Default treatment'], ['ORDINARY_INTEREST', 'Ordinary interest'],
      ['ORDINARY_DIVIDEND', 'Ordinary dividend'], ['QUALIFIED_DIVIDEND', 'Qualified dividend'],
      ['CAPITAL_GAIN_DISTRIBUTION', 'Capital-gain distribution']], '');
    var reference = el('input', { type: 'text', maxlength: '160', placeholder: 'Optional order or statement reference' });
    var notes = el('textarea', { rows: '2', maxlength: '1000', placeholder: 'Optional notes' });
    var cashField = UI.field('Amount $', amount, { hint: 'Enter a positive amount; withdrawals, transfers out, and fees become cash outflows. Adjustments may be signed.' });
    var feeField = UI.field('Total fees $', fees, { hint: 'Allocated across exact legs and included in tax-lot basis/proceeds.' });
    var taxField = UI.field('Tax category', taxCategory);
    var legs = el('div', { class: 'book-legs', id: 'portfolio-book-legs' });
    var addLeg = el('button', { type: 'button', class: 'btn btn-secondary btn-sm', onclick: function () {
      legs.appendChild(portfolioLegEditor({}, true));
    } }, '+ Security leg');
    var guidance = el('div', { class: 'muted small book-event-guidance' });

    function addConversionLegs(kind) {
      var optionAction = kind === 'ASSIGNMENT' ? 'BUY' : 'SELL';
      var option = portfolioLegEditor({ legend: kind === 'ASSIGNMENT' ? 'Assigned option (close at $0)' : 'Exercised option (close at $0)',
        instrumentType: 'OPTION', action: optionAction, positionEffect: 'CLOSE', price: '0.00' }, false);
      var stock = portfolioLegEditor({ legend: 'Resulting share transaction at the strike', instrumentType: 'STOCK',
        action: 'BUY', positionEffect: 'OPEN', quantity: '100' }, false);
      option.controls.effect.disabled = true; option.controls.price.disabled = true;
      stock.controls.instrument.disabled = true;
      function syncStockDirection() {
        var buyShares = kind === 'ASSIGNMENT'
          ? option.controls.optionType.value === 'PUT'
          : option.controls.optionType.value === 'CALL';
        stock.controls.action.value = buyShares ? 'BUY' : 'SELL';
        stock.controls.effect.value = buyShares ? 'OPEN' : 'CLOSE';
      }
      function syncDeliverable() {
        var contracts = Number(option.controls.quantity.value), contractMultiplier = Number(option.controls.multiplier.value);
        if (Number.isInteger(contracts) && contracts > 0 && Number.isInteger(contractMultiplier) && contractMultiplier > 0) {
          stock.controls.quantity.value = String(contracts * contractMultiplier);
        }
      }
      option.controls.quantity.addEventListener('input', syncDeliverable);
      option.controls.multiplier.addEventListener('input', syncDeliverable);
      option.controls.optionType.addEventListener('change', syncStockDirection);
      syncStockDirection(); syncDeliverable();
      legs.append(option, stock);
    }

    function addRollLegs() {
      var closing = portfolioLegEditor({ legend: 'Contract being closed', instrumentType: 'OPTION',
        action: 'SELL', positionEffect: 'CLOSE' }, false);
      var replacement = portfolioLegEditor({ legend: 'Replacement contract being opened', instrumentType: 'OPTION',
        action: 'BUY', positionEffect: 'OPEN' }, false);
      closing.controls.instrument.disabled = true; closing.controls.effect.disabled = true;
      replacement.controls.instrument.disabled = true; replacement.controls.effect.disabled = true;
      replacement.controls.action.disabled = true; replacement.controls.symbol.disabled = true;
      replacement.controls.optionType.disabled = true; replacement.controls.quantity.disabled = true;
      replacement.controls.multiplier.disabled = true; replacement.controls.section1256.disabled = true;
      function syncRoll() {
        replacement.controls.action.value = closing.controls.action.value === 'SELL' ? 'BUY' : 'SELL';
        replacement.controls.symbol.value = closing.controls.symbol.value;
        replacement.controls.optionType.value = closing.controls.optionType.value;
        replacement.controls.quantity.value = closing.controls.quantity.value;
        replacement.controls.multiplier.value = closing.controls.multiplier.value;
        replacement.controls.section1256.checked = closing.controls.section1256.checked;
      }
      [closing.controls.action, closing.controls.symbol, closing.controls.optionType,
        closing.controls.quantity, closing.controls.multiplier, closing.controls.section1256]
        .forEach(function (control) { control.addEventListener('input', syncRoll); control.addEventListener('change', syncRoll); });
      syncRoll(); legs.append(closing, replacement);
    }

    function syncEvent() {
      legs.innerHTML = '';
      var isCash = cashEvents.indexOf(event.value) >= 0;
      cashField.hidden = !isCash;
      feeField.hidden = isCash;
      taxField.hidden = event.value !== 'DIVIDEND';
      taxCategory.innerHTML = '';
      [['', 'Ordinary dividend'], ['QUALIFIED_DIVIDEND', 'Qualified dividend'],
        ['CAPITAL_GAIN_DISTRIBUTION', 'Capital-gain distribution']].forEach(function (row) {
        taxCategory.appendChild(el('option', { value: row[0] }, row[1]));
      });
      legs.hidden = isCash; addLeg.hidden = isCash || ['ASSIGNMENT', 'EXERCISE', 'ROLL'].indexOf(event.value) >= 0;
      if (isCash) {
        guidance.textContent = event.value === 'ADJUSTMENT'
          ? 'Use a signed adjustment only when reconciling to a statement; keep the reason in Notes.'
          : 'This records cash in the tracked account only. It never changes the practice account.';
        return;
      }
      if (event.value === 'ASSIGNMENT' || event.value === 'EXERCISE') {
        addConversionLegs(event.value);
        guidance.textContent = 'Record exactly one closing equity-option leg and its resulting stock delivery. Broad-based Section 1256 index options are cash-settled: record their exact settlement as a closing option transaction instead. Share quantity follows contracts × the contract multiplier, including adjusted contracts. The put/call sets buy versus sell; choose Open or Close to match whether the delivery created a new share position or offset one you already held.';
      } else if (event.value === 'ROLL') {
        addRollLegs();
        guidance.textContent = 'Close the recorded option and open its replacement together. Choose Sell to close a long position or Buy to close a short position. Strike or expiration must change; exact realized P/L, replacement basis, fees, and net premium carryover are stored separately.';
      } else {
        var first = portfolioLegEditor({ instrumentType: 'OPTION', positionEffect: event.value === 'EXPIRATION' ? 'CLOSE' : 'OPEN',
          action: event.value === 'EXPIRATION' ? 'SELL' : 'BUY', price: event.value === 'EXPIRATION' ? '0.00' : '' }, false);
        if (event.value === 'EXPIRATION') { first.controls.effect.disabled = true; first.controls.price.disabled = true; }
        legs.appendChild(first);
        guidance.textContent = event.value === 'EXPIRATION'
          ? 'Add each expired contract as a closing leg at $0. The lot matcher verifies that the exact position was open.'
          : 'One transaction may contain every stock and option leg in an exact package. Use Open/Close explicitly so basis cannot be inferred incorrectly.';
      }
    }
    event.addEventListener('change', syncEvent); syncEvent();

    var message = el('div', { class: 'small', 'aria-live': 'polite' });
    var save = el('button', { type: 'button', class: 'btn', disabled: account.status === 'ARCHIVED' ? 'disabled' : null,
      onclick: async function () {
        save.disabled = true; save.setAttribute('aria-busy', 'true'); message.textContent = '';
        try {
          var isCash = cashEvents.indexOf(event.value) >= 0;
          var cashAmount = isCash ? Number(amount.value) : null;
          var feeAmount = Number(fees.value || 0);
          if (isCash && (!Number.isFinite(cashAmount) || cashAmount === 0 || (event.value !== 'ADJUSTMENT' && cashAmount < 0))) {
            throw new Error(event.value === 'ADJUSTMENT' ? 'Enter a non-zero signed cash adjustment.' : 'Enter a positive cash amount.');
          }
          if (!Number.isFinite(feeAmount) || feeAmount < 0) throw new Error('Fees must be zero or more.');
          var legInputs = isCash ? [] : Array.from(legs.querySelectorAll('.book-leg-row')).map(function (row) { return row.read(); });
          await API.post('/api/portfolio/accounts/' + account.id + '/transactions', {
            occurredAt: portfolioInstant(occurred, 'Activity date and time'), eventType: event.value,
            cashAmountCents: isCash ? Math.round(cashAmount * 100) : null,
            feesCents: Math.round(feeAmount * 100), taxCategory: taxCategory.value || null,
            source: source.value, externalRef: reference.value || null, notes: notes.value || null, legs: legInputs
          });
          UI.toast('Activity recorded in ' + account.name, 'ok');
          await App.render();
        } catch (e) { message.textContent = e.message || String(e); message.className = 'small loss'; }
        finally { save.disabled = account.status === 'ARCHIVED'; save.removeAttribute('aria-busy'); }
      } }, 'Record activity');
    return el('section', { class: 'card book-record-card' },
      UI.cardHeader('Record activity', el('span', { class: 'badge badge-dim' }, 'APPEND-ONLY')),
      el('p', { class: 'muted' }, Learn.currentLevel() === 'beginner'
        ? 'Copy what actually happened at your broker. StrikeBench keeps exact share and option lots, cash, fees, and basis; it never guesses whether a leg opened or closed.'
        : 'Post normalized cash or market activity to the owner-scoped accounting book. Corrections use offsetting entries; recorded history is not rewritten.'),
      el('p', { class: 'muted small' }, 'Enter market activity oldest to newest so every close can match the lots that existed at that time. CSV imports sort transaction groups and keep every multi-leg package atomic.'),
      el('div', { class: 'form-grid book-transaction-meta' }, UI.field('What happened?', event), UI.field('Date and time', occurred),
        UI.field('Source', source), UI.field('Broker reference', reference,
          { hint: 'Required for broker-sourced activity so a repeated fill cannot be recorded twice.' }), cashField, feeField, taxField),
      guidance, legs,
      el('div', { class: 'btn-row' }, addLeg), UI.field('Notes', notes),
      el('div', { class: 'btn-row' }, save), message);
  }

  function portfolioImportCard(account) {
    var file = el('input', { type: 'file', accept: '.csv,text/csv', 'aria-label': 'Portfolio activity CSV' });
    var result = el('div', { class: 'small', 'aria-live': 'polite' });
    function renderImportResult(out) {
      result.innerHTML = '';
      if (!out) return;
      result.className = 'book-import-result';
      var importMessage = 'Imported ' + out.transactionsWritten + ' transaction' + (out.transactionsWritten === 1 ? '' : 's')
        + ' from ' + out.rowsRead + ' row' + (out.rowsRead === 1 ? '' : 's') + '. '
        + (out.rejectedRows ? out.rejectedRows + ' row' + (out.rejectedRows === 1 ? '' : 's') + ' quarantined.' : 'No rows were rejected.');
      result.appendChild(el('p', { class: out.rejectedRows ? 'caution' : 'gain' }, importMessage));
      if (out.rejectedRows) {
        var rejects = out.quarantine || [];
        result.appendChild(UI.expandable(
          el('span', {}, 'Review quarantined rows · ', rejects.length),
          function () { return el('div', { class: 'book-import-rejects' },
            rejects.slice(0, 100).map(function (row) { return el('div', { class: 'book-import-reject-row' },
              el('b', {}, 'Line ' + row.line),
              el('span', { class: 'muted' }, row.transactionRef || 'No transaction reference'),
              el('span', {}, row.reason)); }),
            rejects.length > 100 ? el('p', { class: 'muted small' }, 'Showing 100 of ' + rejects.length + ' rows. Download the complete list below.') : null); }));
        result.appendChild(el('button', { type: 'button', class: 'btn btn-secondary btn-sm', onclick: function () {
          var quote = function (value) { return '"' + String(value == null ? '' : value).replaceAll('"', '""') + '"'; };
          var csv = 'line,transaction_ref,reason\r\n' + rejects.map(function (row) {
            return [row.line, quote(row.transactionRef), quote(row.reason)].join(',');
          }).join('\r\n');
          var url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
          var link = el('a', { href: url, download: 'StrikeBench-portfolio-import-rejects.csv' });
          document.body.appendChild(link); link.click(); link.remove(); setTimeout(function () { URL.revokeObjectURL(url); }, 0);
        } }, 'Download all rejects'));
      }
      result.appendChild(el('p', { class: 'muted small' }, out.note));
    }
    renderImportResult((App.state.portfolioImportResults || {})[account.id]);
    var upload = el('button', { type: 'button', class: 'btn btn-secondary', disabled: account.status === 'ARCHIVED' ? 'disabled' : null,
      onclick: async function () {
        if (!file.files || !file.files[0]) { result.textContent = 'Choose a CSV file first.'; result.className = 'small loss'; return; }
        upload.disabled = true; upload.setAttribute('aria-busy', 'true'); result.textContent = 'Validating transaction groups…';
        try {
          var fd = new FormData(); fd.append('file', file.files[0]);
          var out = await API.upload('/api/portfolio/accounts/' + account.id + '/import.csv', fd);
          App.state.portfolioImportResults = App.state.portfolioImportResults || {};
          App.state.portfolioImportResults[account.id] = out;
          UI.toast('Imported ' + out.transactionsWritten + ' transaction' + (out.transactionsWritten === 1 ? '' : 's')
            + (out.rejectedRows ? ' · ' + out.rejectedRows + ' rows need attention' : ''), out.rejectedRows ? 'warn' : 'ok');
          await App.render();
        } catch (e) { result.textContent = e.message || String(e); result.className = 'small loss'; }
        finally { upload.disabled = account.status === 'ARCHIVED'; upload.removeAttribute('aria-busy'); }
      } }, 'Import CSV');
    return el('section', { class: 'card card-slim book-import-card' },
      UI.cardHeader('Import a broker history'),
      el('p', { class: 'muted small' }, 'Transactions are sorted chronologically. Every reference is atomic: all legs land together or every row in that package is quarantined with a reason. Other valid references still import, and stable references prevent duplicates. To add history older than activity already recorded here, use a new tracked account.'),
      el('div', { class: 'book-import-row' }, file, upload,
        el('a', { class: 'btn btn-secondary', href: '/api/portfolio/import-template.csv', download: 'StrikeBench-portfolio-import-template.csv' }, 'Download template')),
      result);
  }

  function portfolioTransactionRow(tx) {
    var cashClass = tx.cashEffectCents > 0 ? 'gain' : tx.cashEffectCents < 0 ? 'loss' : '';
    var summary = el('div', { class: 'book-transaction-summary' },
      el('div', {}, el('b', {}, tx.eventType.replaceAll('_', ' ').toLowerCase()),
        el('span', { class: 'muted small' }, portfolioOccurredLabel(tx.occurredAt))),
      el('div', { class: cashClass }, fmtMoney(tx.cashEffectCents, { plus: true })));
    return UI.expandable(summary, function () {
      return el('div', { class: 'book-transaction-detail' },
        el('div', { class: 'chip-row' }, chip('Source', tx.source), chip('Fees', fmtMoney(tx.feesCents)),
          tx.taxCategory ? chip('Tax category', tx.taxCategory.replaceAll('_', ' ').toLowerCase()) : null,
          tx.externalRef ? chip('Reference', tx.externalRef) : null),
        (tx.legs || []).length ? el('div', { class: 'book-transaction-legs' }, (tx.legs || []).map(function (leg) {
          return el('div', { class: 'book-transaction-leg' }, el('span', {}, portfolioLegLabel(leg)),
            el('span', { class: 'muted small' }, 'Gross ' + fmtMoney(leg.grossAmountCents) + (leg.allocatedFeeCents ? ' · fee ' + fmtMoney(leg.allocatedFeeCents) : '')));
        })) : el('p', { class: 'muted small' }, 'Cash-only activity.'),
        tx.notes ? el('p', {}, tx.notes) : null,
        tx.roll ? el('div', { class: 'book-roll-summary' },
          el('b', {}, 'Linked roll'),
          el('div', { class: 'chip-row' }, chip('Closing premium', fmtMoney(tx.roll.closingPremiumCents, { plus: true })),
            chip('Replacement premium', fmtMoney(tx.roll.openingPremiumCents, { plus: true })),
            chip('Net premium before fees', fmtMoney(tx.roll.premiumCarryoverCents, { plus: true }))),
          el('p', { class: 'muted small' }, 'The close realized normally across ' + tx.roll.realizedMatchIds.length
            + ' matched lot' + (tx.roll.realizedMatchIds.length === 1 ? '' : 's') + '; replacement lot ' + tx.roll.replacementLotId + ' keeps its own exact basis.')) : null,
        el('div', { class: 'muted small' }, 'Transaction ' + tx.id + ' · recorded history is append-only.'));
    });
  }

  async function renderPortfolioBookActivity(root, account) {
    root.appendChild(portfolioTransactionForm(account));
    root.appendChild(portfolioImportCard(account));
    var pageSize = 25, pageIndex = 0;
    var data = await API.getFresh('/api/portfolio/accounts/' + account.id + '/transactions?page=0&size=' + pageSize);
    var transactions = data.transactions || [];
    var countBadge = el('span', { class: 'badge badge-dim' }, transactions.length + (transactions.length === pageSize ? '+' : '') + ' shown');
    var journal = el('section', { class: 'card book-journal' }, UI.cardHeader('Transaction history', countBadge));
    if (!transactions.length) journal.appendChild(UI.emptyState('No activity recorded', 'Record a cash movement, stock or option fill, assignment, exercise, or expiration above.'));
    else transactions.forEach(function (tx) { journal.appendChild(portfolioTransactionRow(tx)); });
    if (transactions.length === pageSize) {
      var olderMessage = el('div', { class: 'small', 'aria-live': 'polite' });
      var older = el('button', { type: 'button', class: 'btn btn-secondary', onclick: async function () {
        older.disabled = true; older.setAttribute('aria-busy', 'true'); olderMessage.textContent = '';
        try {
          pageIndex++;
          var next = await API.getFresh('/api/portfolio/accounts/' + account.id + '/transactions?page=' + pageIndex + '&size=' + pageSize);
          var rows = next.transactions || [];
          rows.forEach(function (tx) { journal.insertBefore(portfolioTransactionRow(tx), loadRow); });
          transactions = transactions.concat(rows);
          countBadge.textContent = transactions.length + (rows.length === pageSize ? '+' : '') + ' shown';
          if (rows.length < pageSize) loadRow.remove();
        } catch (e) {
          pageIndex--;
          olderMessage.textContent = e.message || String(e); olderMessage.className = 'small loss';
        } finally { older.disabled = false; older.removeAttribute('aria-busy'); }
      } }, 'Load older activity');
      var loadRow = el('div', { class: 'book-journal-more' }, older, olderMessage);
      journal.appendChild(loadRow);
    }
    root.appendChild(journal);
  }

  function portfolioValuationForm(account, performance) {
    var last = (performance.valuations || []).length ? performance.valuations[performance.valuations.length - 1] : null;
    var asOf = el('input', { type: 'datetime-local', step: '1', value: portfolioNowLocal() });
    var total = el('input', { type: 'number', min: '0', step: '0.01', value: '', placeholder: last ? (last.totalValueCents / 100).toFixed(2) : '100000.00' });
    var cash = el('input', { type: 'number', step: '0.01', value: '', placeholder: 'Optional' });
    var securities = el('input', { type: 'number', step: '0.01', value: '', placeholder: 'Optional' });
    var reference = el('input', { type: 'text', maxlength: '160', placeholder: 'Optional statement reference' });
    var notes = el('input', { type: 'text', maxlength: '1000', placeholder: 'Optional note' });
    var message = el('div', { class: 'small', 'aria-live': 'polite' });
    var current = el('button', { type: 'button', class: 'btn btn-secondary btn-sm', onclick: async function () {
      current.disabled = true; current.setAttribute('aria-busy', 'true');
      try {
        var summary = await API.getFresh('/api/portfolio/accounts/' + account.id + '/summary');
        if (summary.totalValueCents == null) throw new Error('Current total is unavailable until every open position has an executable closing mark.');
        total.value = (summary.totalValueCents / 100).toFixed(2);
        cash.value = (summary.bookCashCents / 100).toFixed(2);
        securities.value = (summary.securitiesLiquidationValueCents / 100).toFixed(2);
        message.textContent = 'Filled from the current executable liquidation view.'; message.className = 'small muted';
      } catch (e) { message.textContent = e.message || String(e); message.className = 'small loss'; }
      finally { current.disabled = false; current.removeAttribute('aria-busy'); }
    } }, 'Use current book value');
    var save = el('button', { type: 'button', class: 'btn', disabled: account.status === 'ARCHIVED' ? 'disabled' : null,
      onclick: async function () {
        save.disabled = true; save.setAttribute('aria-busy', 'true'); message.textContent = '';
        try {
          var totalValue = Number(total.value), cashValue = cash.value === '' ? null : Number(cash.value),
            securitiesValue = securities.value === '' ? null : Number(securities.value);
          if (!Number.isFinite(totalValue) || totalValue < 0) throw new Error('Enter a non-negative total account value.');
          if (cashValue != null && !Number.isFinite(cashValue)) throw new Error('Enter a valid cash value.');
          if (securitiesValue != null && !Number.isFinite(securitiesValue)) throw new Error('Enter a valid securities value.');
          await API.post('/api/portfolio/accounts/' + account.id + '/valuations', {
            asOf: portfolioInstant(asOf, 'Snapshot date and time'), totalValueCents: Math.round(totalValue * 100),
            cashCents: cashValue == null ? null : Math.round(cashValue * 100),
            securitiesValueCents: securitiesValue == null ? null : Math.round(securitiesValue * 100),
            source: 'MANUAL', externalRef: reference.value || null, notes: notes.value || null
          });
          UI.toast('Account-value reconciliation recorded', 'ok'); await App.render();
        } catch (e) { message.textContent = e.message || String(e); message.className = 'small loss'; }
        finally { save.disabled = account.status === 'ARCHIVED'; save.removeAttribute('aria-busy'); }
      } }, 'Record reconciliation');
    return el('section', { class: 'card book-valuation-form' },
      UI.cardHeader('Reconcile an account value', current),
      el('p', { class: 'muted small' }, 'StrikeBench records observed executable-side values automatically at a fixed cadence. Add a broker or statement value here when you want an independent reconciliation point. Cash and securities are optional detail, but when both are entered they must add to the total exactly.'),
      el('div', { class: 'form-grid' }, UI.field('As of date and time', asOf), UI.field('Total account value $', total),
        UI.field('Cash $ (optional)', cash), UI.field('Securities $ (optional)', securities),
        UI.field('Statement reference', reference), UI.field('Notes', notes)),
      el('div', { class: 'btn-row' }, save), message);
  }

  async function renderPortfolioBookPerformance(root, account) {
    var performance = await API.getFresh('/api/portfolio/accounts/' + account.id + '/performance');
    var values = performance.valuations || [];
    var completeValues = values.filter(function (v) { return v.complete !== false; });
    var benchmark = performance.benchmark || { symbol: 'SPY', points: [] };
    root.appendChild(el('div', { class: 'grid grid-4 book-performance-stats' },
      stat('Time-weighted return', performance.timeWeightedReturn == null ? 'Unavailable' : fmtPct(performance.timeWeightedReturn, 2),
        performance.timeWeightedReturn == null
          ? 'Needs a complete after-flow valuation on every deposit, withdrawal, or transfer date.'
          : 'Geometrically chains each valuation period after removing external cash flows.'),
      stat('Money-weighted return (IRR)', performance.moneyWeightedIrr == null ? 'Unavailable' : fmtPct(performance.moneyWeightedIrr, 2),
        'Annualized XIRR from the actual dates and amounts of contributions, withdrawals, and ending value.'),
      stat('Maximum drawdown', performance.maxDrawdown == null ? 'Unavailable' : fmtPct(performance.maxDrawdown, 2),
        performance.drawdownPeakAt && performance.drawdownTroughAt
          ? 'Cash-flow-adjusted decline from ' + UI.fmtDate(performance.drawdownPeakAt) + ' to ' + UI.fmtDate(performance.drawdownTroughAt) + '.'
          : 'Largest cash-flow-adjusted peak-to-trough decline in the recorded window.'),
      stat((benchmark.symbol || 'SPY') + ' benchmark', benchmark.returnValue == null ? 'Unavailable' : fmtPct(benchmark.returnValue, 2),
        benchmark.note || 'Observed benchmark return over the same valuation window.')));
    root.appendChild(el('div', { class: 'chip-row book-performance-secondary' },
      chip('Investment gain', performance.investmentGainCents == null ? 'Unavailable' : fmtMoney(performance.investmentGainCents),
        'Ending value minus starting value and net external flows.'),
      chip('Modified Dietz', performance.modifiedDietzReturn == null ? 'Unavailable' : fmtPct(performance.modifiedDietzReturn, 2),
        'A time-weighted cash-flow approximation retained for reconciliation; TWR and IRR above are separate metrics.'),
      chip('Dietz annualized', performance.annualizedReturn == null ? 'Unavailable' : fmtPct(performance.annualizedReturn, 2),
        'Annualized Modified Dietz approximation, shown only for windows of at least 30 days.'),
      chip('Income recorded', fmtMoney((performance.interestIncomeCents || 0) + (performance.dividendIncomeCents || 0)),
        'Interest ' + fmtMoney(performance.interestIncomeCents || 0) + ' · dividends ' + fmtMoney(performance.dividendIncomeCents || 0) + '.')));
    var chartCard = el('section', { class: 'card book-performance-chart' }, UI.cardHeader(
      benchmark.points && benchmark.points.length >= 2 ? 'Account value vs ' + benchmark.symbol : 'Historical account value',
      el('span', { class: 'badge badge-dim' }, values.length + ' valuation' + (values.length === 1 ? '' : 's'))));
    if (completeValues.length >= 2) {
      var chartValues;
      if (benchmark.points && benchmark.points.length >= 2) {
        chartValues = benchmark.points.map(function (p) {
          return { date: UI.fmtDate(p.asOf), value: p.portfolioValueCents,
            benchmark: p.normalizedBenchmarkValueCents };
        });
        chartCard.appendChild(el('div', { class: 'chip-row book-chart-legend' },
          chip('Solid line', account.name + ' · flow-adjusted'), chip('Dashed line', benchmark.symbol + ' · observed')));
      } else chartValues = completeValues.map(function (v) {
        return { date: UI.fmtDate(v.asOf), value: v.totalValueCents };
      });
      chartCard.appendChild(UI.lineChart(chartValues, { money: true, baseline: chartValues[0].value,
        compareKey: chartValues[0].benchmark == null ? null : 'benchmark', primaryLabel: account.name + ' index',
        compareLabel: benchmark.symbol }));
      chartCard.appendChild(el('div', { class: 'book-valuation-list' }, values.slice().reverse().map(function (v) {
        return el('div', { class: 'book-valuation-row' }, el('span', {}, UI.fmtDate(v.asOf)),
          el('b', {}, fmtMoney(v.totalValueCents)), el('span', { class: 'muted small' }, v.source
            + (v.complete === false ? ' · partial' : ' · complete')
            + (v.notes ? ' · ' + v.notes : '')));
      })));
    } else chartCard.appendChild(UI.emptyState('Two complete valuations are needed for performance',
      values.length ? 'Partial valuations remain visible, but a return is not calculated until two complete account values exist.' : 'Record an account value now, then another later.'));
    if (benchmark.returnValue == null) chartCard.appendChild(el('p', { class: 'muted small' }, benchmark.note));
    chartCard.appendChild(el('p', { class: 'muted small' }, performance.note));
    root.appendChild(chartCard);
    root.appendChild(portfolioValuationForm(account, performance));
  }

  function portfolioTaxFacts(report) {
    return el('div', { class: 'grid grid-4 book-tax-stats' },
      stat('Short-term gains', pnlSpan(report.shortTermGainCents), 'Matched taxable lots held one year or less; short positions remain short-term in this estimator.'),
      stat('Long-term gains', pnlSpan(report.longTermGainCents), 'Matched long lots held more than one year.'),
      stat('Interest + dividends', fmtMoney((report.ordinaryInterestCents || 0) + (report.ordinaryDividendCents || 0)
        + (report.qualifiedDividendCents || 0) + (report.capitalGainDistributionCents || 0)), 'Recorded income categories for this year.'),
      stat('Section 1256 (60 / 40)', pnlSpan(report.section1256GainCents || 0),
        'Identified broad-based index contracts: ' + fmtMoney(report.section1256LongTermCents || 0)
          + ' long-term and ' + fmtMoney(report.section1256ShortTermCents || 0) + ' short-term.'),
      stat('User-rate scenario', report.scenarioTotalTaxCents == null ? 'Not calculated' : fmtMoney(report.scenarioTotalTaxCents),
        report.accountType !== 'TAXABLE' ? 'Not applicable to this retirement wrapper.'
          : report.scenarioTotalTaxCents == null ? 'Withheld; the tax-rules notice below explains the missing input or unsupported case.'
          : report.scenarioStateTaxCents == null ? 'Federal scenario only; no state scenario rate is entered.'
            : 'Federal plus state scenario using the rates in Settings. This is not tax owed.'));
  }

  function portfolioTaxReconciliation(report, account, year) {
    var saved = report.reconciliation;
    function dollars(amount) { return amount && amount.brokerCents != null ? (amount.brokerCents / 100).toFixed(2) : ''; }
    function moneyInput(label, amount, opts) {
      opts = opts || {};
      return { label: label, input: el('input', { type: 'number', step: '0.01', min: opts.nonnegative ? '0' : null,
        value: dollars(amount), placeholder: 'Optional', 'aria-label': label }) };
    }
    var fields = {
      shortTerm: moneyInput('Final short-term $', saved && saved.shortTermGain),
      longTerm: moneyInput('Final long-term $', saved && saved.longTermGain),
      wash: moneyInput('Wash adjustment $', saved && saved.washAdjustment, { nonnegative: true }),
      section1256: moneyInput('Section 1256 net $', saved && saved.section1256Gain),
      interest: moneyInput('Broker interest $', saved && saved.interest),
      ordinaryDividend: moneyInput('Broker ordinary dividends $', saved && saved.ordinaryDividend),
      qualifiedDividend: moneyInput('Broker qualified dividends $', saved && saved.qualifiedDividend),
      capitalGainDistribution: moneyInput('Broker capital-gain distributions $', saved && saved.capitalGainDistribution)
    };
    var status = el('select', { 'aria-label': 'Reconciliation status' },
      [['DRAFT', 'Draft · still reviewing'], ['RECONCILED', 'Reconciled · compared with forms']]
        .map(function (row) { return el('option', { value: row[0],
          selected: row[0] === (saved ? saved.status : 'DRAFT') ? 'selected' : null }, row[1]); }));
    var reference = el('input', { type: 'text', maxlength: '200', value: saved && saved.formReference || '',
      placeholder: 'e.g. corrected 1099-B dated 2026-02-15', 'aria-label': 'Broker form reference' });
    var notes = el('textarea', { maxlength: '2000', rows: '3', placeholder: 'Optional reconciliation notes',
      'aria-label': 'Reconciliation notes' }, saved && saved.notes || '');
    function fieldNodes(keys) {
      return keys.map(function (key) { return UI.field(fields[key].label, fields[key].input); });
    }
    function cents(node, label) {
      if (node.value.trim() === '') return null;
      var value = Number(node.value), result = Math.round(value * 100);
      if (!Number.isFinite(value) || !Number.isSafeInteger(result)) throw new Error(label + ' is outside the supported money range.');
      return result;
    }
    var message = el('div', { class: 'small', 'aria-live': 'polite' });
    var save = el('button', { type: 'button', class: 'btn', onclick: async function () {
      save.disabled = true; save.setAttribute('aria-busy', 'true'); message.textContent = ''; message.className = 'small';
      try {
        var payload = { status: status.value, formReference: reference.value || null,
          shortTermGainCents: cents(fields.shortTerm.input, fields.shortTerm.label),
          longTermGainCents: cents(fields.longTerm.input, fields.longTerm.label),
          washAdjustmentCents: cents(fields.wash.input, fields.wash.label),
          section1256GainCents: cents(fields.section1256.input, fields.section1256.label),
          interestCents: cents(fields.interest.input, fields.interest.label),
          ordinaryDividendCents: cents(fields.ordinaryDividend.input, fields.ordinaryDividend.label),
          qualifiedDividendCents: cents(fields.qualifiedDividend.input, fields.qualifiedDividend.label),
          capitalGainDistributionCents: cents(fields.capitalGainDistribution.input, fields.capitalGainDistribution.label),
          notes: notes.value || null };
        await API.put('/api/portfolio/accounts/' + account.id + '/tax/' + year + '/reconciliation', payload);
        UI.toast('Broker-form reconciliation saved', 'ok'); await App.render();
      } catch (e) { message.textContent = e.message || String(e); message.className = 'small loss'; }
      finally { save.disabled = false; save.removeAttribute('aria-busy'); }
    } }, saved ? 'Update reconciliation' : 'Save reconciliation');
    var clear = saved ? el('button', { type: 'button', class: 'btn btn-secondary', onclick: async function () {
      clear.disabled = save.disabled = true; clear.setAttribute('aria-busy', 'true'); message.textContent = ''; message.className = 'small';
      try {
        await API.del('/api/portfolio/accounts/' + account.id + '/tax/' + year + '/reconciliation');
        UI.toast('Broker-form reconciliation cleared', 'ok'); await App.render();
      } catch (e) { message.textContent = e.message || String(e); message.className = 'small loss'; }
      finally { clear.disabled = save.disabled = false; clear.removeAttribute('aria-busy'); }
    } }, 'Clear reconciliation') : null;
    var card = el('section', { class: 'card book-tax-reconciliation' },
      UI.cardHeader('Broker-form reconciliation', el('span', { class: 'badge ' + (saved && saved.status === 'RECONCILED' ? 'badge-ok' : 'badge-dim') }, saved ? saved.status : 'NOT STARTED')),
      el('p', { class: 'muted' }, 'Enter totals from your broker forms. StrikeBench compares them with this recorded book; it never overwrites lots, basis, or transactions.'),
      el('p', { class: 'muted small' }, 'Short- and long-term fields mean the final totals after combining the applicable broker forms, including any Section 1256 character. The separate Section 1256 field reconciles that component.'),
      el('div', { class: 'form-grid book-tax-reconciliation-meta' }, UI.field('Status', status), UI.field('Form reference', reference)),
      el('div', { class: 'form-grid book-tax-reconciliation-core' }, fieldNodes(['shortTerm', 'longTerm', 'wash', 'section1256'])),
      UI.expandable('Interest and dividend forms', function () {
        return el('div', { class: 'form-grid' }, fieldNodes(['interest', 'ordinaryDividend', 'qualifiedDividend', 'capitalGainDistribution']));
      }, { open: Learn.currentLevel() === 'expert' }), UI.field('Notes', notes, { className: 'book-tax-reconciliation-notes' }));
    if (saved) {
      var comparisons = [
        ['Final short-term total', saved.shortTermGain], ['Final long-term total', saved.longTermGain],
        ['Wash adjustment', saved.washAdjustment], ['Section 1256 net gain / loss', saved.section1256Gain],
        ['Interest', saved.interest], ['Ordinary dividends', saved.ordinaryDividend],
        ['Qualified dividends', saved.qualifiedDividend], ['Capital-gain distributions', saved.capitalGainDistribution]
      ].filter(function (row) { return row[1].brokerCents != null; });
      card.appendChild(el('div', { class: 'book-tax-comparison' },
        el('h3', {}, 'Recorded book vs broker forms'),
        el('p', { class: 'muted small' }, 'Difference is broker form minus StrikeBench. A non-zero amount is a prompt to investigate, not an automatic correction.'),
        table(['Field', 'StrikeBench', 'Broker form', 'Difference'], comparisons.map(function (row) {
          return el('tr', {}, el('td', { class: 'book-tax-comparison-field' }, row[0]),
            el('td', { 'data-label': 'StrikeBench' }, fmtMoney(row[1].strikeBenchCents)),
            el('td', { 'data-label': 'Broker form' }, fmtMoney(row[1].brokerCents)),
            el('td', { 'data-label': 'Difference' }, pnlSpan(row[1].differenceCents)));
        }))));
    }
    card.appendChild(el('div', { class: 'btn-row' }, save, clear)); card.appendChild(message);
    return card;
  }

  async function renderPortfolioBookTax(root, account) {
    var currentYear = new Date().getFullYear();
    var yearValue = App.state.portfolioTaxYear || currentYear;
    var year = el('input', { type: 'number', min: '1970', max: '9999', step: '1', value: yearValue, 'aria-label': 'Tax year' });
    year.addEventListener('change', function () {
      var parsed = Number(year.value); if (!Number.isInteger(parsed) || parsed < 1970 || parsed > 9999) return;
      App.state.portfolioTaxYear = parsed; App.render();
    });
    root.appendChild(el('div', { class: 'book-tax-heading' },
      el('div', {}, el('h2', {}, 'Tax basis and reconciliation'),
        el('p', { class: 'muted' }, account.accountType === 'TAXABLE'
          ? 'Not tax advice. Recorded facts and a bounded user-rate scenario for reconciliation, not a tax filing, tax owed, or broker 1099.'
          : 'Not tax advice. Basis and performance remain tracked; current capital-gains tax is not assigned inside this retirement wrapper.')),
      UI.field('Tax year', year)));
    var taxData;
    try {
      taxData = await Promise.all([
        API.getFresh('/api/portfolio/accounts/' + account.id + '/tax?year=' + encodeURIComponent(yearValue)),
        API.getFresh('/api/portfolio/accounts/' + account.id + '/lots?includeClosed=false')
      ]);
    } catch (e) {
      if (!String(e.message || e).includes('year-end mark')) throw e;
      var markMessage = el('div', { class: 'small', 'aria-live': 'polite' });
      var markButton = el('button', { type: 'button', class: 'btn', onclick: async function () {
        markButton.disabled = true; markButton.setAttribute('aria-busy', 'true'); markMessage.textContent = '';
        try {
          await API.post('/api/portfolio/accounts/' + account.id + '/tax/' + yearValue + '/mark-1256', {});
          UI.toast('Observed year-end Section 1256 marks applied', 'ok'); await App.render();
        } catch (markError) { markMessage.textContent = markError.message || String(markError); markMessage.className = 'small loss'; }
        finally { markButton.disabled = false; markButton.removeAttribute('aria-busy'); }
      } }, 'Apply observed year-end marks');
      root.appendChild(alertBox('caution', 'Section 1256 year-end mark required', [e.message || String(e)]));
      root.appendChild(el('section', { class: 'card card-slim' }, UI.cardHeader('Complete the tax-year close'),
        el('p', { class: 'muted' }, 'This command uses only stored observed option marks, recognizes the year-end gain or loss at 60/40 character, and resets the open lot basis. It refuses missing or generated marks.'),
        el('div', { class: 'btn-row' }, markButton), markMessage));
      return;
    }
    var report = taxData[0], openLots = taxData[1].lots || [];
    root.appendChild(portfolioTaxFacts(report));
    var rulesNotice = alertBox(report.rules.status === 'REVIEWED' ? 'caution' : 'danger',
      report.rules.status === 'REVIEWED' ? 'Reviewed common-case worksheet' : 'Tax rules not reviewed for ' + yearValue,
      [report.note]);
    rulesNotice.appendChild(el('p', { class: 'small book-tax-sources' }, 'Primary references: ',
      (report.rules.sources || []).map(function (source, index) {
        return el('span', {}, index ? ' · ' : '', el('a', { href: source.url, target: '_blank', rel: 'noopener noreferrer' }, source.title));
      })));
    root.appendChild(rulesNotice);
    if (account.accountType === 'TAXABLE') root.appendChild(portfolioTaxReconciliation(report, account, yearValue));
    var openCard = el('section', { class: 'card book-open-tax-lots' }, UI.cardHeader('Open tax lots',
      el('span', { class: 'badge badge-dim' }, openLots.length + ' lot' + (openLots.length === 1 ? '' : 's'))));
    if (!openLots.length) openCard.appendChild(UI.emptyState('No open tax lots',
      'Recorded stock and option openings appear here until they are closed, assigned, exercised, or expired.'));
    else if (Learn.currentLevel() === 'beginner') {
      openLots.forEach(function (lot) {
        openCard.appendChild(el('div', { class: 'book-open-lot-row' },
          el('div', {}, el('b', {}, portfolioPositionLabel(lot)),
            el('span', { class: 'muted small' }, 'Opened ' + portfolioOccurredLabel(lot.openedAt))),
          el('div', { class: 'chip-row' }, chip(lot.side === 'LONG' ? 'Basis remaining' : 'Proceeds remaining', fmtMoney(lot.remainingOpenAmountCents)),
            chip('Quantity open', String(lot.remainingQuantity)), chip('Side', lot.side.toLowerCase()),
            lot.section1256 ? chip('Tax character', 'Section 1256 · 60 / 40') : null)));
      });
    } else {
      openCard.appendChild(table(['Opened', 'Position', 'Side', 'Remaining qty', 'Multiplier', 'Tax character', 'Remaining basis / proceeds'],
        openLots.map(function (lot) { return el('tr', {}, el('td', {}, portfolioOccurredLabel(lot.openedAt)),
          el('td', {}, el('b', {}, portfolioPositionLabel(lot))), el('td', {}, lot.side.toLowerCase()),
          el('td', {}, String(lot.remainingQuantity)), el('td', {}, String(lot.multiplier)),
          el('td', {}, lot.section1256 ? 'Section 1256 · 60 / 40' : 'Holding period'),
          el('td', {}, fmtMoney(lot.remainingOpenAmountCents))); })));
    }
    root.appendChild(openCard);
    var realized = report.realizedLots || [];
    var realizedCard = el('section', { class: 'card book-realized' }, UI.cardHeader('Realized tax lots',
      el('span', { class: 'badge badge-dim' }, realized.length + ' match' + (realized.length === 1 ? '' : 'es'))));
    if (!realized.length) realizedCard.appendChild(UI.emptyState('No realized lots in ' + yearValue,
      'Closing stock or option lots, assignment, exercise, and expiration populate this ledger.'));
    else if (Learn.currentLevel() === 'beginner') {
      realized.forEach(function (r) {
        var character = r.section1256 ? 'Section 1256 · 60 / 40' : r.holdingTerm.replaceAll('_', ' ').toLowerCase();
        realizedCard.appendChild(el('div', { class: 'book-realized-lot-row' },
          el('div', {}, el('b', {}, r.symbol + ' · ' + r.instrumentType.toLowerCase()),
            el('span', { class: 'muted small' }, 'Closed ' + portfolioTaxDate(r.closedAt) + ' ET · '
              + r.side.toLowerCase() + ' · quantity ' + r.quantity)),
          el('div', { class: 'chip-row' },
            chip('Taxable gain / loss', pnlSpan((r.realizedGainCents || 0) + (r.washSaleAdjustmentCents || 0))),
            chip('Modeled wash candidate', fmtMoney(r.washSaleAdjustmentCents || 0)), chip('Character', character)),
          el('p', { class: 'muted small book-realized-lot-detail' }, 'Opening basis / proceeds ',
            el('b', {}, fmtMoney(r.openAmountCents)), ' · closing proceeds / cost ',
            el('b', {}, fmtMoney(r.closeAmountCents)), ' · raw gain / loss ', pnlSpan(r.realizedGainCents))));
      });
    } else realizedCard.appendChild(table(['Closed (ET)', 'Symbol', 'Instrument', 'Side', 'Qty', 'Opening basis / proceeds', 'Closing proceeds / cost', 'Raw realized', 'Modeled wash candidate', 'Worksheet realized', 'Character'],
      realized.map(function (r) { return el('tr', {}, el('td', {}, portfolioTaxDate(r.closedAt)), el('td', {}, el('b', {}, r.symbol)),
        el('td', {}, r.instrumentType.toLowerCase()), el('td', {}, r.side.toLowerCase()), el('td', {}, String(r.quantity)),
        el('td', {}, fmtMoney(r.openAmountCents)), el('td', {}, fmtMoney(r.closeAmountCents)),
        el('td', {}, pnlSpan(r.realizedGainCents)), el('td', {}, fmtMoney(r.washSaleAdjustmentCents || 0)),
        el('td', {}, pnlSpan((r.realizedGainCents || 0) + (r.washSaleAdjustmentCents || 0))),
        el('td', {}, r.section1256 ? 'Section 1256 · 60 / 40' : r.holdingTerm.replaceAll('_', ' ').toLowerCase())); })));
    root.appendChild(realizedCard);
    root.appendChild(el('section', { class: 'card card-slim book-exports' }, UI.cardHeader('Export exact records'),
      el('p', { class: 'muted small' }, 'CSV exports every normalized transaction leg. The row marked as the primary transaction row carries net cash and total fees once, so summing a multi-leg package cannot double-count it. Excel includes Summary, Transactions, Lots, Realized, Performance, and Tax sheets with numeric cells and no executable formulas.'),
      el('div', { class: 'btn-row' },
        el('a', { class: 'btn btn-secondary', href: '/api/portfolio/accounts/' + account.id + '/export.csv', download: '' }, 'Download transactions CSV'),
        el('a', { class: 'btn', href: '/api/portfolio/accounts/' + account.id + '/export.xlsx?year=' + encodeURIComponent(yearValue), download: '' }, 'Download Excel workbook'))));
  }

  async function renderPortfolioBookSettings(root, account) {
    if (App.state.portfolioBookNew) {
      root.appendChild(el('section', { class: 'card book-new-account' }, UI.cardHeader('Add another tracked account',
        el('button', { type: 'button', class: 'btn btn-secondary btn-sm', onclick: function () {
          App.state.portfolioBookNew = false; App.render();
        } }, 'Cancel')),
        portfolioAccountForm(null, async function () {
          App.state.portfolioBookNew = false; App.navigate('#/portfolio/book/overview');
        })));
    }
    root.appendChild(el('section', { class: 'card book-settings' }, UI.cardHeader('Account settings'),
      portfolioAccountForm(account, async function () { await App.render(); })));
    var statusMessage = el('div', { class: 'small', 'aria-live': 'polite' });
    var toggle = el('button', { type: 'button', class: account.status === 'ARCHIVED' ? 'btn' : 'btn btn-danger', onclick: async function () {
      toggle.disabled = true; toggle.setAttribute('aria-busy', 'true'); statusMessage.textContent = '';
      try {
        if (account.status === 'ARCHIVED') await API.post('/api/portfolio/accounts/' + account.id + '/restore', {});
        else await API.del('/api/portfolio/accounts/' + account.id);
        UI.toast(account.status === 'ARCHIVED' ? 'Tracked account restored' : 'Tracked account archived', 'ok');
        await App.render();
      } catch (e) { statusMessage.textContent = e.message || String(e); statusMessage.className = 'small loss'; }
      finally { toggle.disabled = false; toggle.removeAttribute('aria-busy'); }
    } }, account.status === 'ARCHIVED' ? 'Restore account' : 'Archive account');
    root.appendChild(el('section', { class: 'card card-slim book-account-status' },
      UI.cardHeader('Record status', el('span', { class: 'badge ' + (account.status === 'ARCHIVED' ? 'badge-caution' : 'badge-ok') }, account.status)),
      el('p', { class: 'muted' }, account.status === 'ARCHIVED'
        ? 'The full transaction, lot, valuation, performance, and tax history remains readable. Restore the account to add records.'
        : 'Archiving makes this account read-only without erasing its accounting history or exports.'),
      el('div', { class: 'btn-row' }, toggle), statusMessage));
    root.appendChild(el('section', { class: 'card card-slim book-boundary' }, UI.cardHeader('Accounting boundary'),
      el('p', {}, 'Tracked accounts are an owner-scoped record of external activity. They never place an order, reserve practice buying power, or mutate the practice ledger.'),
      el('p', { class: 'muted small' }, 'Adjusted contracts are supported when the recorded multiplier is the complete share deliverable. Record cash or other non-share deliverables as separate statement adjustments so the book reconciles exactly.'),
        el('p', { class: 'muted small' }, 'Recorded same-instrument wash-sale deferrals and identified Section 1256 60/40 treatment are included. Qualified-covered-call rules, straddles, loss limits and carryovers, state-specific rules, return-of-capital basis allocation, and filing elections still require reconciliation against broker tax forms and a qualified tax professional.')));
  }

  async function portfolioBook(root, params) {
    var section = ['overview', 'activity', 'performance', 'tax', 'settings'].includes(params[0]) ? params[0] : 'overview';
    var data = await API.getFresh('/api/portfolio/accounts');
    var accounts = data.accounts || [];
    if (!accounts.length) {
      root.appendChild(el('section', { class: 'card portfolio-book-start' }, UI.cardHeader('Set up a tracked account'),
        el('p', {}, 'Record brokerage, IRA, or 401(k) activity without changing the practice account.'),
        portfolioAccountForm(null, async function () { App.navigate('#/portfolio/book/overview'); })));
      return;
    }
    var remembered = App.state.portfolioBookAccountId;
    if (!remembered) try { remembered = localStorage.getItem('strikebench.portfolioBookAccount'); } catch (e) { /* optional */ }
    var account = accounts.find(function (a) { return a.id === remembered; }) || accounts.find(function (a) { return a.status === 'ACTIVE'; }) || accounts[0];
    App.state.portfolioBookAccountId = account.id;
    var picker = el('select', { id: 'portfolio-book-account', 'aria-label': 'Tracked account' }, accounts.map(function (a) {
      return el('option', { value: a.id, selected: a.id === account.id ? 'selected' : null }, a.name + ' · ' + portfolioAccountTypeLabel(a.accountType) + (a.status === 'ARCHIVED' ? ' · archived' : ''));
    }));
    picker.addEventListener('change', function () {
      App.state.portfolioBookAccountId = picker.value;
      try { localStorage.setItem('strikebench.portfolioBookAccount', picker.value); } catch (e) { /* optional */ }
      App.render();
    });
    root.appendChild(el('div', { class: 'portfolio-book-context' },
      el('div', {}, el('div', { class: 'eyebrow' }, 'TRACKED ACCOUNT'), el('h2', {}, account.name),
        el('p', { class: 'muted' }, portfolioAccountTypeLabel(account.accountType) + (account.broker ? ' · ' + account.broker : '') + ' · ' + account.lotMethod + ' lots')),
      el('div', { class: 'portfolio-book-picker' }, picker,
        el('button', { type: 'button', class: 'btn btn-secondary btn-sm', onclick: function () {
          App.state.portfolioBookNew = true; App.navigate('#/portfolio/book/settings');
        } }, '+ Account'))));
    root.appendChild(portfolioBookTabs(section));
    if (account.status === 'ARCHIVED') root.appendChild(alertBox('caution', 'This tracked account is archived and read-only.', ['Restore it in Settings before recording new activity.']));
    var loading = el('div', { class: 'book-section-loading', 'aria-live': 'polite' },
      UI.spinner('Loading ' + section.replaceAll('_', ' ') + '…'));
    root.appendChild(loading);
    try {
      if (section === 'overview') {
        var summary = await API.getFresh('/api/portfolio/accounts/' + account.id + '/summary');
        renderPortfolioBookOverview(root, account, summary);
        return;
      }
      if (section === 'activity') return await renderPortfolioBookActivity(root, account);
      if (section === 'performance') return await renderPortfolioBookPerformance(root, account);
      if (section === 'tax') return await renderPortfolioBookTax(root, account);
      return await renderPortfolioBookSettings(root, account);
    } finally {
      loading.remove();
    }
  }

  async function portfolio(root, params) {
    // One money home: Positions (holdings + trades) | Activity (the ledger) | Account
    // (starting cash, reset).
    var trackedBook = params[0] === 'book';
    var directTradeId = params[0] === 'trade' ? params[1] : null;
    var section = directTradeId || params[0] === 'positions' || params[0] === 'active' || params[0] === 'closed' ? 'positions'
      : params[0] === 'activity' ? 'activity' : params[0] === 'account' ? 'account'
        : params[0] === 'record' ? 'record' : params[0] === 'construct' ? 'construct' : 'positions';
    var tab = params[0] === 'closed' ? 'closed' : 'active';
    var page = parseInt(params[1] || '0', 10);
    root.appendChild(el('h1', {}, 'Portfolio'));
    root.appendChild(portfolioModeNav(trackedBook));
    if (trackedBook) {
      await portfolioBook(root, params.slice(1));
      return;
    }
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
    var paperTabs = el('div', { class: 'tabs', role: 'tablist', 'aria-label': 'Paper account sections' },
      el('button', { role: 'tab', 'aria-selected': section === 'construct' ? 'true' : 'false', tabindex: section === 'construct' ? '0' : '-1', class: section === 'construct' ? 'active' : '', id: 'pf-sec-construct',
        onclick: function () { App.navigate('#/portfolio/construct'); } }, 'Construct'),
      el('button', { role: 'tab', 'aria-selected': section === 'positions' ? 'true' : 'false', tabindex: section === 'positions' ? '0' : '-1', class: section === 'positions' ? 'active' : '', id: 'pf-sec-positions',
        onclick: function () { App.navigate('#/portfolio/positions'); } }, 'Positions'),
      el('button', { role: 'tab', 'aria-selected': section === 'activity' ? 'true' : 'false', tabindex: section === 'activity' ? '0' : '-1', class: section === 'activity' ? 'active' : '', id: 'pf-sec-activity',
        onclick: function () { App.navigate('#/portfolio/activity'); } }, 'Activity'),
      el('button', { role: 'tab', 'aria-selected': section === 'record' ? 'true' : 'false', tabindex: section === 'record' ? '0' : '-1', class: section === 'record' ? 'active' : '', id: 'pf-sec-record',
        onclick: function () { App.navigate('#/portfolio/record'); } }, 'Your record'),
      el('button', { role: 'tab', 'aria-selected': section === 'account' ? 'true' : 'false', tabindex: section === 'account' ? '0' : '-1', class: section === 'account' ? 'active' : '', id: 'pf-sec-account',
        onclick: function () { App.navigate('#/portfolio/account'); } }, 'Account'));
    root.appendChild(UI.bindTabList(paperTabs, function (tabButton) { tabButton.click(); }));

    if (directTradeId) {
      root.appendChild(el('div', { class: 'btn-row' },
        el('a', { class: 'btn btn-secondary btn-sm', href: '#/portfolio/positions' }, '← All positions')));
      await tradeDetail(root, [directTradeId], { inline: false });
      return;
    }

    if (section === 'construct') {
      root.appendChild(portfolioConstruct(acct));
      return;
    }

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
            stat('Decision P/L', pnlSpan(cal.totalPnlCents),
              'For covered strategies, this includes the held-share move because that is the payoff the prediction judged.')));
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
          return UI.field(label,
            el('input', { type: 'number', id: id, step: '100', value: val != null ? Math.round(val / 100) : '' }));
        }
        function fldInfo(id, label, val, termKey) {
          return UI.field([label, UI.info(termKey)],
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
          el('label', { class: 'muted', for: 'reset-cash' }, 'Starting cash ($) '), cashInput,
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
    var greeksP = tab === 'active'
      ? API.get('/api/portfolio/greeks').catch(function () { return null; }) : null;
    var positionsP = API.get('/api/positions').catch(function (e) { return { error: e }; });
    var planBookP = API.get('/api/plans/portfolio').catch(function (e) { return { plans: [], error: e }; });
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
          var heat = null;
          try { heat = await API.get('/api/portfolio/heat'); }
          catch (e) { /* heat is additive — the page never fails on it */ }
          function heatChips() {
            return heat && heat.activeTrades > 0 ? el('div', { class: 'chip-row' },
              el('b', { style: 'margin-right:4px' }, 'Book heat'),
              chip('Total worst case', fmtMoney(heat.totalMaxLossCents)),
              chip('Short-vol trades', String(heat.shortVolTrades)),
              chip('Top-symbol share', heat.concentrationPct + '%',
                'How much of the total worst case sits in ONE symbol.'),
              heat.earlyAssignmentLiquidityCents > 0 ? chip('Early-assignment cash demand',
                fmtMoney(heat.earlyAssignmentLiquidityCents),
                'Temporary gross cash needed if every short put assigns before protective puts are exercised or sold. This is not max loss.') : null,
              heat.physicalAssignmentCashCents > 0 ? chip('Cash-secured shares delivered',
                fmtMoney(heat.physicalAssignmentCashCents),
                'Strike cash used when funded single short puts settle into shares.') : null,
              heat.physicalAssignmentCashCents > 0 ? chip('BP after funded assignment',
                fmtMoney(heat.postPhysicalAssignmentBuyingPowerCents),
                'Buying power after strike cash is paid and the matching cash-secured reserve is released.') : null) : null;
          }
          function greekChips() {
            return el('div', { class: 'chip-row', style: 'align-items:center' },
              el('b', { style: 'margin-right:4px' }, 'Book greeks'),
              chip('Net \u0394', fmtNum(pg.deltaShares, 0) + ' sh'),
              chip('\u0393', fmtNum(pg.gammaShares, 2) + ' sh/$'),
              chip('\u0398/day', pnlSpan(pg.thetaPerDay * 100)),
              chip('Vega/pt', pnlSpan(pg.vegaPerPoint * 100)),
              pg.complete ? null : el('span', { class: 'badge badge-caution' }, 'PARTIAL'));
          }
          if (Learn.currentLevel() === 'expert') {
            if (heat && heat.activeTrades > 0) {
              root.appendChild(el('div', { class: 'card card-slim', id: 'portfolio-heat' }, heatChips()));
            }
            root.appendChild(el('div', { class: 'card card-slim', id: 'portfolio-greeks' }, greekChips()));
          } else {
            root.appendChild(el('div', { class: 'card card-slim', id: 'portfolio-greeks' },
              UI.cardHeader('How your open positions react'),
              el('p', { class: 'muted small' },
                'These sensitivities estimate how the whole practice book responds to price, time, and volatility. They use the same current marks and math shown in Expert.'),
              UI.expandable('See exact portfolio sensitivity', function () {
                return el('div', { class: 'portfolio-risk-detail' }, heatChips(), greekChips());
              })));
          }
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
                        visibleCommand(this, function () {
                          return startPlan({ intent: 'EXIT', symbol: pv.symbol }, 'STRATEGY');
                        }, 'A sell-at-a-target Plan could not be opened.');
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
    var fSym = el('input', { type: 'text', id: 'pf-symbol', placeholder: 'symbol',
      'aria-label': 'Filter positions by symbol', style: 'max-width:110px' });
    var fIntent = el('select', { id: 'pf-intent', 'aria-label': 'Filter positions by goal', style: 'max-width:170px' },
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
      function externalField(label, input, hint) {
        return UI.field(label, input, { hint: hint });
      }
      var sym = el('input', { type: 'text', id: 'ext-symbol', list: 'universe-symbols', placeholder: 'AAPL' });
      var qty = el('input', { type: 'number', id: 'ext-qty', min: '1', max: '100', value: '1' });
      var net = el('input', { type: 'number', id: 'ext-net', step: '0.01', placeholder: '175.00' });
      var fees = el('input', { type: 'number', id: 'ext-fees', step: '0.01', min: '0', placeholder: '2.00' });
      box.appendChild(el('div', { class: 'form-grid external-trade-summary' },
        externalField('Symbol', sym), externalField('Contracts', qty),
        externalField('Actual package net $', net, 'Total dollars: + credit / \u2212 debit'),
        externalField('Total fees $', fees, 'All opening commissions and fees')));
      var legsBox = el('div', { id: 'ext-legs' });
      function legRow() {
        var action = el('select', { class: 'x-act' }, el('option', {}, 'SELL'), el('option', {}, 'BUY'));
        var type = el('select', { class: 'x-type' }, el('option', {}, 'PUT'), el('option', {}, 'CALL'));
        var strike = el('input', { class: 'x-strike', type: 'number', step: '0.5', placeholder: '250' });
        var expiration = el('input', { class: 'x-exp', type: 'date' });
        var fill = el('input', { class: 'x-fill', type: 'number', step: '0.01', placeholder: '3.10' });
        return el('div', { class: 'ext-leg' },
          externalField('Action', action), externalField('Type', type), externalField('Strike', strike),
          externalField('Expiration', expiration), externalField('Your fill $/share', fill,
            'Required when the contract has expired'),
          el('button', { type: 'button', class: 'btn btn-sm btn-secondary ext-leg-remove',
            'aria-label': 'Remove this leg', onclick: function (ev) { ev.target.closest('.ext-leg').remove(); } }, '\u00d7'));
      }
      legsBox.appendChild(legRow());
      box.appendChild(el('div', { class: 'plan-section-head external-legs-head' },
        el('div', {}, el('h4', {}, 'Exact option legs'),
          el('p', { class: 'muted small' }, 'Leg fills are per share; package net above is total dollars.')),
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
          onclick: function () { legsBox.appendChild(legRow()); } }, '+ Leg')));
      box.appendChild(legsBox);
      var execDate = el('input', { type: 'date', id: 'ext-date' });
      var brokerIn = el('input', { type: 'text', id: 'ext-broker', placeholder: 'E*TRADE' });
      var refIn = el('input', { type: 'text', id: 'ext-ref', placeholder: 'Order number' });
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
          API.flushCache();
          await App.render();
        } catch (e) { msg.textContent = 'Refused: ' + (e.message || e); }
        saveBtn.disabled = false;
      } }, 'Record trade');
      box.appendChild(el('div', { class: 'form-grid external-trade-meta' },
        externalField('Executed on', execDate), externalField('Broker (optional)', brokerIn),
        externalField('Order number (optional)', refIn)));
      box.appendChild(el('label', { class: 'check-row external-past-check' }, pastChk,
        el('span', {}, 'Past trade — contracts may have expired; use my recorded fills instead of a live-book check.')));
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
        ? UI.emptyState('No open practice trades', 'Start a Plan, compare risk-screened structures, and decide with the worst case visible before you commit.', 'Start a Plan', function () { App.navigate('#/research'); })
        : UI.emptyState('Nothing closed yet', 'Closed, settled, and voided trades land here.'));
      return;
    }
    var planBook = await planBookP;
    if (planBook.error) {
      tradesCard.appendChild(alertBox('warn', 'Plan links are temporarily unavailable',
        ['Your trades remain visible and usable. Refresh to restore their Manage & Review shortcuts.']));
    }
    var linkedPlans = {};
    ((planBook && planBook.plans) || []).forEach(function (row) {
      if (row.tradeId) linkedPlans[row.tradeId] = row.plan;
    });
    var rows = data.trades.map(function (t) {
      return pressable(el('tr', {
        class: 'clickable', onclick: async function () {
          var plan = linkedPlans[t.id];
          if (plan) {
            try { await PlanStore.focus(plan, 'MANAGE_REVIEW'); }
            catch (e) { UI.toast(e.message, 'error'); }
          } else { App.navigate('#/portfolio/trade/' + t.id); }
        }
      },
        el('td', {}, el('b', {}, t.symbol)),
        el('td', {}, prettyStrategy(t.strategy),
          t.origin === 'EXTERNAL' ? el('span', { class: 'badge badge-warn', style: 'margin-left:6px', title: 'A real trade you recorded — tracked and judged here, but your practice cash was never touched.' }, 'EXTERNAL') : null,
          t.intent && t.intent !== 'DIRECTIONAL' ? el('span', { style: 'margin-left:6px' }, intentBadge(t.intent)) : null),
        el('td', {}, 'x' + t.qty),
        el('td', {}, pnlSpan(t.entryNetPremiumCents)),
        el('td', { class: 'loss' }, fmtMoney(t.maxLossCents)),
        tab === 'active'
          ? el('td', { 'data-now-for': t.id, title: t.sharesLocked > 0 ? 'Decision P/L: held-share move plus the option package' : '' },
              activeDecisionPnl(t) !== undefined && activeDecisionPnl(t) !== null
                ? pnlSpan(activeDecisionPnl(t)) : el('span', { class: 'muted' }, '—'))
          : null,
        el('td', {}, tab === 'active' ? el('span', { class: 'muted' }, UI.fmtDate(t.createdAt)) : pnlSpan(closedDecisionPnl(t))),
        el('td', {}, el('span', { class: 'badge ' + (t.status === 'ACTIVE' ? 'badge-ok' : t.status === 'DELETED' ? 'badge-danger' : 'badge-dim') }, t.status))),
        'Open ' + t.symbol + ' ' + prettyStrategy(t.strategy), 'link');
    });
    tradesCard.appendChild(table(
      tab === 'active'
        ? ['Symbol', 'Strategy', 'Qty', 'Entry credit/debit', 'Theor. max loss', 'Now', 'Opened', 'Status']
        : ['Symbol', 'Strategy', 'Qty', 'Entry credit/debit', 'Theor. max loss', 'Decision P/L', 'Status'], rows));
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
            cell.appendChild(activeDecisionPnl(ft) !== undefined && activeDecisionPnl(ft) !== null
              ? pnlSpan(activeDecisionPnl(ft)) : el('span', { class: 'muted' }, '—'));
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

  async function tradeDetail(root, params, options) {
    options = options || {};
    var id = options.tradeId || params[0];
    var d = options.data || await API.get('/api/trades/' + id);
    var managedPlan = options.plan || null;
    var t = d.trade;
    var active = t.status === 'ACTIVE';
    var optionPnl = active
      ? (d.current ? d.current.unrealizedCents : null)
      : t.realizedPnlCents;
    var pnl = active
      ? (d.current && d.current.decisionUnrealizedCents !== null && d.current.decisionUnrealizedCents !== undefined
          ? d.current.decisionUnrealizedCents : optionPnl)
      : closedDecisionPnl(t);
    var combinedOutcome = t.sharesLocked > 0 && pnl !== null && pnl !== undefined;

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
        managedPlan ? null : el('button', { class: 'btn btn-sm btn-secondary', style: 'margin-left:8px',
          title: 'Full analysis for ' + t.symbol + ' \u2014 the trade itself stays exactly as placed',
          onclick: function () { App.navigate('#/research/' + encodeURIComponent(t.symbol)); } }, 'Research')),
      el('div', { class: 'muted' },
        (combinedOutcome ? (active ? 'Decision P/L now (held shares + options, before close fees)' : 'Decision outcome (held shares + options)')
          : (active ? 'Unrealized (before close fees)' : 'Realized P/L')) + ' · opened ' + UI.fmtDate(t.createdAt)
        + (t.closeReason ? ' · ' + t.closeReason : '')),
      // The first thing a novice sees after placing is a red number — the bid/ask cost of
      // entering. Say so, once, while the trade is young, instead of letting it read as a mistake.
      active && pnl !== null && pnl < 0 && isYoungTrade(t.createdAt)
        ? explain('New positions start a little down — that is the bid/ask spread and opening fees already paid, not a calculation mistake. The trade still has to earn those costs back.')
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
        combinedOutcome ? chip('Option package P/L', optionPnl === null || optionPnl === undefined ? '—' : pnlSpan(optionPnl)) : null,
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

    if (d.current && d.current.greeks) {
      var gk = d.current.greeks;
      var greekCard = el('div', { class: 'card', id: 'greeks-card' },
        UI.cardHeader(Learn.currentLevel() === 'expert' ? 'Position greeks' : 'How this position reacts',
          gk.complete ? null : el('span', { class: 'badge badge-caution' }, 'PARTIAL')));
      function positionGreekDetail() {
        var detail = el('div', { class: 'position-greek-detail' },
          el('div', { class: 'chip-row' },
          chip('Net \u0394', fmtNum(gk.deltaShares, 0) + ' sh'),
          chip('\u0393', fmtNum(gk.gammaShares, 2) + ' sh/$'),
          chip('\u0398/day', pnlSpan(gk.thetaPerDay * 100)),
          chip('Vega/pt', pnlSpan(gk.vegaPerPoint * 100))));
        if (d.current.legGreeks && d.current.legGreeks.length) {
          detail.appendChild(table(['Leg', 'Bid', 'Ask', '\u0394', '\u0393', '\u0398', 'Vega', 'IV'],
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
        detail.appendChild(el('p', { class: 'muted' },
          'Share-equivalent \u0394/\u0393; \u0398 and vega in dollars per day / per vol point. Model statistics from current marks.'));
        return detail;
      }
      if (Learn.currentLevel() === 'expert') {
        greekCard.appendChild(positionGreekDetail());
      } else {
        greekCard.appendChild(el('p', { class: 'muted small' },
          'Price, time, and volatility can move an option position in different ways. The exact sensitivities use the same current marks shown in Expert.'));
        greekCard.appendChild(UI.expandable('See exact position sensitivities', positionGreekDetail));
      }
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
                if (managedPlan) await PlanStore.manage(managedPlan, 'refresh', {});
                else await API.post('/api/trades/' + id + '/refresh');
                await App.render();
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
                ? 'Closing now brings ' + fmtMoney(d.current.closeCostCents, { plus: true }) + ' before close fees. '
                  + 'This uses executable closing sides: sell longs at bid and buy shorts at ask. '
                  + 'The header P/L uses those same closing sides and also includes the opening premium and opening fees, so it is not the same number as this close cash flow. ' : '';
              UI.confirmModal('Close (unwind) this trade?',
                el('div', {},
                  el('p', {}, est + 'The reserve of this trade is released and the result becomes final.'),
                  explain('Unwinding = doing the opposite of every leg at current prices.')),
                'Close position',
                async function () {
                  if (managedPlan) {
                    var out = await PlanStore.manage(managedPlan, 'unwind', { confirm: true });
                    await PlanStore.focus(out.plan, 'MANAGE_REVIEW');
                  } else {
                    await API.post('/api/trades/' + id + '/unwind', { confirm: true });
                    App.render();
                  }
                });
            }
          }, 'Unwind…', el('span', { class: 'btn-sub' }, 'close now at market')),
          el('button', {
            class: 'btn btn-secondary', id: 'settle-btn', onclick: function () {
              UI.confirmModal('Settle at expiration value?',
                el('p', {}, 'Only possible after all legs expired. Cash settles at intrinsic value.'),
                'Settle',
                async function () {
                  if (managedPlan) {
                    var out = await PlanStore.manage(managedPlan, 'settle', { confirm: true });
                    await PlanStore.focus(out.plan, 'MANAGE_REVIEW');
                  } else {
                    await API.post('/api/trades/' + id + '/settle', { confirm: true });
                    App.render();
                  }
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
                  if (managedPlan) {
                    var out = await PlanStore.manage(managedPlan, 'roll', { confirm: true });
                    var saved = await PlanStore.saveCustom(out.plan, out.rolledPosition);
                    var planUi = PlanStore.ui(saved.plan.id);
                    planUi.strategyView = 'builder';
                    await PlanStore.focus(saved.plan, 'STRATEGY');
                  } else {
                    await API.post('/api/trades/' + id + '/unwind', { confirm: true });
                    var rolled = (t.legs || []).map(function (l) {
                      var leg = { action: l.action, type: l.stock ? 'STOCK' : l.type, strike: String(l.strike || ''), ratio: l.ratio || 1 };
                      if (!l.stock && l.expiration) {
                        var d2 = new Date(l.expiration); d2.setDate(d2.getDate() + 28);
                        leg.expiration = d2.toISOString().slice(0, 10);
                      }
                      return leg;
                    });
                    var seed = { symbol: t.symbol, qty: t.qty, goal: t.intent || 'DIRECTIONAL', templateKey: null,
                      step: 4, legIdx: 0, legs: rolled, excluded: {} };
                    var plan = await startPlan({ symbol: t.symbol, intent: t.intent || 'DIRECTIONAL',
                      horizon: 'month', thesis: 'neutral' }, 'STRATEGY');
                    if (plan) {
                      var planUi = PlanStore.ui(plan.id);
                      planUi.strategyView = 'builder';
                      planUi.buildState = planUi.buildState || {};
                      planUi.buildState.builderForm = seed;
                      App.render();
                    }
                  }
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
                  if (managedPlan) {
                    var out = await PlanStore.manage(managedPlan, 'void', { confirm: true });
                    await PlanStore.focus(out.plan, 'MANAGE_REVIEW');
                  } else {
                    await API.del('/api/trades/' + id + '?confirm=true');
                    App.render();
                  }
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


  window.ViewPortfolio = Object.freeze({ portfolio: portfolio, tradeDetail: tradeDetail });
})();
