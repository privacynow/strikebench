/* StrikeBench Plan journey and ranked-strategy views. Loaded after views.js. */
(function () {
  'use strict';

  var S = window.ViewShared;
  var el = S.el, fmtMoney = S.fmtMoney, pnlSpan = S.pnlSpan, fmtPct = S.fmtPct,
      fmtNum = S.fmtNum, badge = S.badge, explain = S.explain,
      alertBox = S.alertBox, stat = S.stat, table = S.table, chip = S.chip;
  var positiveInteger = S.positiveInteger, visibleCommand = S.visibleCommand,
      focusPlanFrom = S.focusPlanFrom, planIntentDestination = S.planIntentDestination,
      legLabel = S.legLabel, fmtBreakeven = S.fmtBreakeven,
      prettyStrategy = S.prettyStrategy, intentBadge = S.intentBadge,
      riskMode = S.riskMode, prettyPricingMode = S.prettyPricingMode,
      pressable = S.pressable, startPlan = S.startPlan;
  var research = window.ViewResearch.research,
      verdictPanel = window.ViewResearch.verdictPanel;
  var icon = UI.icon;

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

  function economicRank(c) {
    var v = economicVerdict(c);
    return v === 'FAVORABLE' ? 3 : v === 'MIXED' ? 2 : v === 'UNAVAILABLE' ? 1 : 0;
  }

  function marketEvAfterCosts(c) {
    return c && c.economics && c.economics.marketEvAfterCostsCents;
  }

  function historyEvAfterCosts(c) {
    return c && c.economics && c.economics.realizedVolEvAfterCostsCents;
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
                fmtMoney(e.marketEvAfterCostsCents, { plus: true })) : 'Unavailable'),
        chip(el('span', {}, 'Realized-vol scenario EV', UI.info('evhistvol')),
          e.realizedVolEvAfterCostsCents !== null && e.realizedVolEvAfterCostsCents !== undefined
            ? el('span', { class: e.realizedVolEvAfterCostsCents >= 0 ? 'gain' : 'loss' },
                fmtMoney(e.realizedVolEvAfterCostsCents, { plus: true })) : 'Unavailable'),
        chip('Estimated round-trip fees', fmtMoney(e.estimatedRoundTripFeesCents || 0))));
    if (e.reasons && e.reasons.length) {
      block.appendChild(UI.expandable('Why this classification', function () {
        return el('ul', { class: 'rationale' }, e.reasons.map(function (r) { return el('li', {}, r); }));
      }));
    }
    return block;
  }

  function evaluationLevelBadge(level) {
    level = String(level || 'UNKNOWN').toUpperCase();
    var label = level.replace('OBSERVED_', '').replace('DEMO_FIXTURE', 'DEMO').replaceAll('_', ' ');
    var cls = level === 'OBSERVED_LIVE' ? 'badge-ok'
      : level === 'OBSERVED_DELAYED' ? 'badge-warn'
      : level === 'MODELED' ? 'badge-caution'
      : level === 'DEMO_FIXTURE' ? 'badge-warn'
      : level === 'SIMULATED' ? 'badge-sim' : 'badge-dim';
    return el('span', { class: 'badge ' + cls }, label);
  }

  function managementReceipt(management, beginner, open) {
    if (!management || !(management.rules || []).length) return null;
    return UI.expandable(beginner ? 'How you would manage this trade' : 'Mechanical management plan', function () {
      return el('div', { class: 'candidate-management-receipt' },
        management.summary ? el('p', {}, management.summary) : null,
        el('ul', { class: 'plan-rules' }, management.rules.map(function (rule) {
          return el('li', {}, el('b', {}, String(rule.kind || 'Rule').replaceAll('_', ' ').toLowerCase() + ': '),
            rule.trigger, ' → ', el('span', { class: 'plan-action' }, rule.action));
        })));
    }, { open: !!open });
  }

  function candidateEvaluationReceipt(c, beginner) {
    var analysis = c && c.evaluation;
    if (!analysis) return null;
    var wrap = el('div', { class: 'candidate-evaluation-receipt' });
    var management = managementReceipt(analysis.management, beginner, beginner && !!c.selected);
    if (management) wrap.appendChild(management);
    if (beginner) {
      var beginnerFailures = analysis.explanation && analysis.explanation.failureModes || [];
      if (beginnerFailures.length) wrap.appendChild(UI.expandable('What could make this lose', function () {
        return el('ul', {}, beginnerFailures.map(function (failure) { return el('li', {}, failure); }));
      }));
      return wrap.hasChildNodes() ? wrap : null;
    }
    var evidence = analysis.evidence || {};
    var dimensions = evidence.perDimension || {};
    if (Object.keys(dimensions).length) wrap.appendChild(UI.expandable('Evidence by input', function () {
      var rows = Object.keys(dimensions).map(function (name) {
        return el('div', { class: 'evidence-row' }, el('span', { class: 'ev-dim' }, name), evaluationLevelBadge(dimensions[name]));
      });
      if (evidence.note) rows.push(el('p', { class: 'muted small' }, evidence.note));
      return el('div', { class: 'evidence-grid' }, rows);
    }));
    var score = analysis.score || {};
    if ((score.components || []).length) wrap.appendChild(UI.expandable('How the Decision score was built', function () {
      var body = el('div', {});
      if (score.gatePassed === false && (score.gateFailures || []).length) {
        body.appendChild(alertBox('warn', 'Failed a hard check', score.gateFailures));
      }
      body.appendChild(table(['Factor', 'Score', 'Weight', 'Why'], score.components.map(function (component) {
        return el('tr', {}, el('td', {}, component.name), el('td', {}, Math.round(Number(component.value || 0) * 100) + '%'),
          el('td', {}, Math.round(Number(component.weight || 0) * 100) + '%'), el('td', { class: 'muted' }, component.note || '—'));
      })));
      body.appendChild(el('p', { class: 'muted small' },
        'Normalized ' + Math.round(Number(score.normalizedScore || 0)) + ' → risk-adjusted '
        + Math.round(Number(score.riskAdjustedScore || 0)) + ' inside the economic tier → Decision score '
        + Math.round(Number(c.decisionScore || 0)) + '.'));
      return body;
    }));
    var explanation = analysis.explanation || {};
    if ((explanation.assumptions || []).length || (explanation.failureModes || []).length) {
      wrap.appendChild(UI.expandable('Assumptions and failure modes', function () {
        return el('div', {},
          (explanation.assumptions || []).length ? el('div', {}, el('b', {}, 'Assumptions'),
            el('ul', {}, explanation.assumptions.map(function (item) { return el('li', {}, item); }))) : null,
          (explanation.failureModes || []).length ? el('div', {}, el('b', {}, 'Failure modes'),
            el('ul', {}, explanation.failureModes.map(function (item) { return el('li', {}, item); }))) : null);
      }));
    }
    return wrap.hasChildNodes() ? wrap : null;
  }

  function tradeIntent(value) {
    var intent = String(value || '').toUpperCase();
    return ['DIRECTIONAL', 'INCOME', 'HEDGE', 'ACQUIRE', 'EXIT'].indexOf(intent) >= 0
      ? intent : 'DIRECTIONAL';
  }

  async function openCandidateAsPlan(c, rawSymbol, extra) {
    var symbol = String(rawSymbol || App.context.symbol('AAPL')).toUpperCase();
    var intent = tradeIntent(c && c.intent || App.context.goal());
    var horizon = App.context.horizon('month');
    var thesis = App.context.thesis('neutral');
    App.context.update({ symbol: symbol, goal: intent, horizon: horizon, thesis: thesis });
    var days = Product.Horizon.sessions(horizon);
    var plan = await PlanStore.create({ symbol: symbol, intent: intent, thesis: thesis,
      horizonDays: days, riskMode: riskMode() });
    var position = Object.assign({ symbol: symbol, strategy: c.strategy || 'CUSTOM', qty: c.qty || 1,
      legs: (c.legs || []).map(function (leg) { return {
        action: leg.action, type: leg.stock ? 'STOCK' : leg.type,
        strike: leg.stock || leg.type === 'STOCK' ? null : String(leg.strike),
        expiration: leg.stock || leg.type === 'STOCK' ? null : leg.expiration,
        ratio: leg.ratio || 1, entryPrice: leg.entryPrice == null ? null : String(leg.entryPrice)
      }; }), thesis: thesis, horizon: horizon, riskMode: riskMode(), intent: intent,
      useHeldShares: !!c.usesHeldShares, recommendationId: c.recommendationId || null, source: 'PLAN'
    }, extra || {});
    var selected = await PlanStore.saveCustom(plan, position);
    await PlanStore.focus(selected.plan, 'DECIDE');
    return selected.plan;
  }

  function candidateWorkflowActions(c, symbolForTicket, beginner) {
    var symbol = symbolForTicket || App.context.symbol();
    return el('div', { class: 'btn-row candidate-workflow-actions' },
      el('button', { class: 'btn', onclick: function () {
        visibleCommand(this, function () { return openCandidateAsPlan(c, symbol); },
          'This package could not be saved to a Plan.');
      } }, beginner ? 'Use this in a Plan' : 'Continue with this package'));
  }

  /** Learning-level card: plain language first, numbers second, mechanics on tap. */
  function beginnerCandidateCard(c, withUse, symbolForTicket) {
    var g = Learn.STRATEGY_GUIDE[c.strategy] || {};
    var assignGoal = c.intent === 'EXIT' || c.intent === 'ACQUIRE';
    var maxLossFact = c.usesHeldShares && c.maxLossCents === 0
        ? UI.fact('New cash at risk', '$0')
        : UI.fact('Theoretical worst case', fmtMoney(c.maxLossCents), 'f-danger');
    var profitFact = UI.fact('Chance of any profit', fmtPct(c.pop));
    var assignmentFact = assignGoal && c.assignmentProb !== null && c.assignmentProb !== undefined
      ? UI.fact(c.intent === 'EXIT' ? 'Chance you sell' : 'Chance you buy', fmtPct(c.assignmentProb), 'f-ok') : null;
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
        UI.fact('Theoretical ceiling', UI.maxProfitLabel(
          c.strategy, c.structureGroup, c.maxProfitCents, true, c.legs), 'f-ok'),
        profitFact,
        assignmentFact),
      el('p', { style: 'margin:6px 0' },
        c.entryNetPremiumCents >= 0
          ? el('span', {}, 'You collect ', el('b', { class: 'gain' }, fmtMoney(c.entryNetPremiumCents)), ' up front (a ', UI.term('credit'), ').')
          : el('span', {}, 'You pay ', el('b', {}, fmtMoney(-c.entryNetPremiumCents)), ' up front (a ', UI.term('debit'), ').'),
        (c.breakevens || []).length
          ? el('span', {}, ' The ', UI.term('breakeven'), ' is at ', el('b', {}, c.breakevens.map(fmtBreakeven).join(' / ')), '.')
          : null));
    var econ = economicAssessmentBlock(c);
    if (econ) card.insertBefore(econ, card.querySelector('.fact-grid'));
    var gb = guideBlock(c.strategy);
    // Beginner's first disclosure must explain the structure itself. The economic verdict
    // remains prominent above the facts, while its deeper scoring rationale follows the
    // plain-language win/loss mechanics instead of displacing them.
    if (gb) card.insertBefore(gb, econ || card.children[2] || null);
    if (window.Scenario) card.appendChild(Scenario.realisticOutcomes(symbolForTicket || App.context.symbol(), c));
    var beginnerReceipt = candidateEvaluationReceipt(c, true);
    if (beginnerReceipt) card.appendChild(beginnerReceipt);
    card.appendChild(UI.expandable('The exact contracts \u2014 ' + c.qty + ' lot' + (c.qty > 1 ? 's' : '') + ' (each line \u00d7' + c.qty + ')', function () {
      return el('div', {},
        el('div', { class: 'mono', style: 'margin-bottom:6px' }, c.label),
        el('ul', {}, c.legs.map(function (l) { return el('li', {}, legLabel(l)); })),
        el('p', {}, 'Prices shown are the executable ', UI.term('bid/ask', 'bid/ask'), ' sides — what a fill would actually cost right now.'));
    }));
    if (c.warnings && c.warnings.length) card.appendChild(alertBox('warn', 'Before you decide', c.warnings));
    if (withUse) card.appendChild(candidateWorkflowActions(c, symbolForTicket, true));
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
        c.decisionScore !== null && c.decisionScore !== undefined
          ? UI.scoreBar(c.decisionScore, 'Decision score — the shared economic, risk and evidence ranking') : null),
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
        c.annualizedYieldPct !== null && c.annualizedYieldPct !== undefined ? chip('Net premium yield/yr', fmtNum(c.annualizedYieldPct, 1) + '%') : null,
        c.effectivePrice ? chip(c.intent === 'ACQUIRE' ? 'Effective buy' : 'Effective sell', '$' + c.effectivePrice) : null,
        chip('Breakeven', (c.breakevens || []).map(fmtBreakeven).join(' / ') || '—'),
        chip('Confidence', fmtPct(c.confidence))),
      explain(c.beginnerExplanation));
    var econ = economicAssessmentBlock(c);
    if (econ) card.insertBefore(econ, card.querySelector('.chip-row'));
    card.appendChild(el('div', { class: 'chip-row expert-only' },
      !c.economics && c.expectedValueCents !== null && c.expectedValueCents !== undefined
        ? chip(el('span', {}, 'Market EV (pre-fee)', UI.info('ev')), fmtMoney(c.expectedValueCents, { plus: true })) : null,
      !c.economics ? chip(el('span', {}, 'History EV', UI.info('evhistvol')), 'Unavailable') : null,
      chip('Liquidity', fmtNum(c.liquidityScore, 2))));
    if (window.Scenario) card.appendChild(Scenario.realisticOutcomes(symbolForTicket || App.context.symbol(), c));
    var expertReceipt = candidateEvaluationReceipt(c, false);
    if (expertReceipt) card.appendChild(expertReceipt);
    if (c.warnings && c.warnings.length) card.appendChild(alertBox('warn', 'Heads up', c.warnings));
    card.appendChild(el('details', { class: 'qa' },
      el('summary', {}, 'Why this idea — and what would kill it'),
      el('dl', {},
        el('dt', {}, 'Why considered'), el('dd', {}, c.whyConsidered),
        el('dt', {}, 'Best upside'), el('dd', {}, c.bestUpside),
        el('dt', {}, 'Biggest risk'), el('dd', {}, c.biggestRisk),
        el('dt', {}, 'What would invalidate it'), el('dd', {}, c.wouldInvalidate))));
    if (withUse) card.appendChild(candidateWorkflowActions(c, symbolForTicket, false));
    return card;
  }

  function strategyMeta(name) {
    return ((App.strategyCatalog && App.strategyCatalog.catalog) || []).find(function (m) { return m.name === name; }) || null;
  }

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
        var card = opts.renderCard ? opts.renderCard(x.c) : candidateCard(x.c, true);
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

  function comparisonTable(candidates, options) {
    options = options || {};
    var sortKey = 'rank', sortDir = 1;
    // The served order IS the DecisionPolicy ranking — stamp it
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
      { key: 'economicVerdict', label: 'Economic view', get: economicRank, render: function (c) { var v = economicVerdict(c); return el('span', { class: 'badge economic-table-' + String(v || 'unknown').toLowerCase() }, (c.economics && c.economics.label) || v || '—'); } },
      { key: 'displayName', label: 'Strategy', get: function (c) { return c.displayName; }, render: function (c) { return el('b', {}, c.displayName); } },
      { key: 'entryNetPremiumCents', label: 'Cost/Credit', get: function (c) { return c.entryNetPremiumCents; }, render: function (c) { return pnlSpan(c.entryNetPremiumCents); } },
      { key: 'maxLossCents', label: 'Theor. max loss', get: function (c) { return c.usesHeldShares && c.combinedMaxLossCents ? c.combinedMaxLossCents : c.maxLossCents; }, render: function (c) { return c.usesHeldShares && c.maxLossCents === 0 ? el('span', {}, '$0*') : el('span', { class: 'loss' }, fmtMoney(c.maxLossCents)); } },
      { key: 'maxProfitCents', label: 'Theor. max profit', get: function (c) { var k = UI.profitCeilingKind(c.strategy, c.structureGroup, c.maxProfitCents, c.legs); return k === 'uncapped' ? Infinity : k === 'model-dependent' ? -Infinity : c.maxProfitCents; }, render: function (c) { var k = UI.profitCeilingKind(c.strategy, c.structureGroup, c.maxProfitCents, c.legs); return k === 'model-dependent' ? el('span', { class: 'muted' }, 'model-dependent') : k === 'uncapped' ? el('span', { class: 'gain' }, '\u221E') : el('span', { class: 'gain' }, fmtMoney(c.maxProfitCents)); } },
      { key: 'rr', label: 'R:R', get: rrValue, render: function (c) { var v = rrValue(c); return el('span', {}, v === -1 ? '\u2014' : v === Infinity ? '\u221E' : fmtNum(v, 2)); } },
      { key: 'pop', label: 'POP', get: function (c) { return c.pop === null || c.pop === undefined ? -1 : c.pop; }, render: function (c) { return el('span', {}, fmtPct(c.pop)); } },
      { key: 'marketEv', label: 'Market EV', get: function (c) { var v = marketEvAfterCosts(c); return v === null || v === undefined ? (c.expectedValueCents == null ? -Infinity : c.expectedValueCents) : v; }, render: function (c) { var v = marketEvAfterCosts(c); return v !== null && v !== undefined ? pnlSpan(v) : c.expectedValueCents !== null && c.expectedValueCents !== undefined ? el('span', {}, pnlSpan(c.expectedValueCents), el('small', { class: 'muted' }, ' pre-fee')) : '—'; } },
      { key: 'historyEv', label: 'History EV', get: function (c) { var v = historyEvAfterCosts(c); return v === null || v === undefined ? -Infinity : v; }, render: function (c) { var v = historyEvAfterCosts(c); return v === null || v === undefined ? '—' : pnlSpan(v); } },
      { key: 'breakevens', label: 'Breakevens', get: function (c) { return (c.breakevens || []).length ? parseFloat(c.breakevens[0]) : 0; }, render: function (c) { return el('span', { class: 'mono' }, (c.breakevens || []).map(fmtBreakeven).join(' / ') || '\u2014'); } },
      { key: 'assignmentProb', label: 'Assign%', get: function (c) { return c.assignmentProb === null || c.assignmentProb === undefined ? -1 : c.assignmentProb; }, render: function (c) { return el('span', {}, c.assignmentProb === null || c.assignmentProb === undefined ? '\u2014' : fmtPct(c.assignmentProb)); } },
      { key: 'annualizedYieldPct', label: 'Net premium yield/yr', get: function (c) { return c.annualizedYieldPct === null || c.annualizedYieldPct === undefined ? -1 : c.annualizedYieldPct; }, render: function (c) { return el('span', {}, c.annualizedYieldPct === null || c.annualizedYieldPct === undefined ? '\u2014' : fmtNum(c.annualizedYieldPct, 1) + '%'); } },
      { key: 'liquidityScore', label: 'Liq', get: function (c) { return c.liquidityScore; }, render: function (c) { return el('span', {}, fmtNum(c.liquidityScore, 2)); } },
      { key: 'decisionScore', label: 'Decision score', get: function (c) { return c.decisionScore; }, render: function (c) { return c.decisionScore === null || c.decisionScore === undefined ? '\u2014' : el('b', {}, fmtNum(c.decisionScore, 0)); } }
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
            if (options.onRow) { options.onRow(c); return; }
            var open = detailRow.style.display !== 'none';
            detailRow.style.display = open ? 'none' : '';
            this.setAttribute('aria-expanded', String(!open));
            if (!open && !detailRow.firstChild.firstChild) {
              detailRow.firstChild.appendChild(candidateCard(c, options.withUse !== false));
            }
          }
        }, COLS.map(function (col) { return el('td', {}, col.render(c)); }).concat([
          el('td', {}, el('button', {
            class: 'btn btn-sm', disabled: options.actionDisabled && options.actionDisabled(c) ? '' : null,
            onclick: function (e) {
              e.stopPropagation();
              if (options.onAction) options.onAction(c, this);
            }
          }, options.actionLabel ? options.actionLabel(c) : 'Select'))])),
          'Show details for ' + c.displayName, 'button');
        body.appendChild(row);
        body.appendChild(detailRow);
      });
      wrap.appendChild(el('div', { class: 'tbl-wrap' },
        el('table', { class: 'tbl' }, el('thead', {}, head), body)));
      wrap.appendChild(el('p', { class: 'muted', style: 'margin:8px 0 0' },
        'Click a column to sort, a row to expand the full card. EV lanes are after estimated round-trip fees unless a fallback cell explicitly says pre-fee. Candidates are priced at executable bid/ask.'
        + (candidates.some(function (c) { return c.usesHeldShares && c.maxLossCents === 0; })
            ? ' $0* = no new cash at risk, covered by held shares (sorted by the worst case including those shares).' : '')));
    }
    render();
    return wrap;
  }


  var PLAN_STAGES = Object.freeze([
    { key: 'UNDERSTAND', path: 'understand', n: '1', label: 'Understand', question: 'What is this market doing?' },
    { key: 'EVIDENCE', path: 'evidence', n: '2', label: 'Evidence', question: 'What followed similar conditions, and what could happen?' },
    { key: 'STRATEGY', path: 'strategy', n: '3', label: 'Strategy', question: 'How can I express this view?' },
    { key: 'OUTCOMES', path: 'outcomes', n: '4', label: 'Outcomes', question: 'How does this exact position fare?' },
    { key: 'DECIDE', path: 'decide', n: '5', label: 'Decide', question: 'Trade it, or stay in cash?' },
    { key: 'MANAGE_REVIEW', path: 'manage-review', n: '6', label: 'Manage & Review', question: 'What happened versus what I expected?' }
  ].map(Object.freeze));

  function planMarketLabel(plan) {
    return plan.marketKind === 'SIMULATED' ? 'Simulated market'
      : plan.marketKind === 'DEMO' ? 'Demo market' : 'Observed market';
  }

  function planStageByPath(path) {
    return PLAN_STAGES.find(function (s) { return s.path === path; }) || PLAN_STAGES[0];
  }

  function confirmArchivePlan(plan, leavePlan) {
    UI.confirmModal('Archive this Plan?', el('div', {},
      el('p', {}, el('b', {}, plan.title), ' leaves the working collection.'),
      el('p', { class: 'muted' }, 'Its evidence, selected structure, outcomes, decision, and review history remain as a read-only record. Account positions and trades are unchanged.')),
    'Archive Plan', async function () {
      await PlanStore.archive(await PlanStore.get(plan.id, true));
      if (leavePlan) App.navigate('#/home');
      else await App.render();
    });
  }

  function confirmDeletePlan(plan, leavePlan) {
    UI.confirmModal('Delete this draft Plan?', el('div', {},
      el('p', {}, el('b', {}, plan.title), ' and its unfinished studies, proposed trades, and scenario runs will be permanently removed.'),
      el('p', { class: 'muted' }, 'A Plan with a decision, rehearsal, trade, or review record cannot be deleted; it must be archived.')),
    'Delete draft', async function () {
      try {
        await PlanStore.deleteDraft(await PlanStore.get(plan.id, true));
        if (leavePlan) App.navigate('#/home');
        else await App.render();
      } catch (e) { UI.toast(e.message, 'error'); }
    });
  }

  function planHeader(plan, provisional) {
    var context = plan.context || {};
    var title = plan.title || (plan.symbol + ' · New plan');
    return el('header', { class: 'plan-header', id: 'plan-header' },
      el('div', { class: 'plan-header-main' },
        el('div', { class: 'eyebrow' }, provisional ? 'NEW PLAN' : 'WORKING PLAN'),
        el('h1', {}, title),
        el('div', { class: 'plan-header-facts' },
          el('span', { class: 'badge badge-dim' }, planMarketLabel(plan)),
          plan.intent ? intentBadge(plan.intent) : el('span', { class: 'badge badge-dim' }, 'Intent not chosen'),
          context.horizonDays ? chip('Horizon', context.horizonDays + ' trading sessions') : null,
          context.targetCents ? chip('Target', fmtMoney(context.targetCents)) : null),
        provisional ? null : el('div', { class: 'plan-header-receipt expert-only', 'aria-label': 'Plan identity receipt' },
          el('span', {}, 'Plan v' + plan.version),
          el('span', {}, 'Context r' + ((context && context.rev) || 1)),
          el('span', {}, String(plan.status || 'ACTIVE').replaceAll('_', ' ').toLowerCase()))),
      provisional ? null : el('div', { class: 'plan-header-actions' },
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', id: 'plan-edit-context',
          onclick: function () {
            var editor = document.getElementById('plan-context-editor');
            if (editor) { editor.hidden = !editor.hidden; if (!editor.hidden) editor.querySelector('input,select').focus(); }
          } }, plan.assumptionsEditable === true ? 'Edit view & limits' : 'Revise this Plan'),
        plan.assumptionsEditable === true ? el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
          id: 'plan-delete', onclick: function () { confirmDeletePlan(plan, true); } }, icon('trash', 15), ' Delete draft') : null,
        plan.status === 'POSITION_OPEN' ? null : el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
          id: 'plan-archive', onclick: function () { confirmArchivePlan(plan, true); } }, icon('archive', 15), ' Archive')));
  }

  function planRail(plan, active, provisional) {
    var status = plan.status || 'DRAFT';
    var manageReady = ['DECIDED_CASH', 'POSITION_OPEN', 'CLOSED'].indexOf(status) >= 0;
    return el('nav', { class: 'plan-rail', 'aria-label': 'Plan journey' },
      el('ol', {}, PLAN_STAGES.map(function (stage) {
        var selected = stage.key === active.key;
        var locked = provisional ? stage.key !== 'UNDERSTAND' : stage.key === 'MANAGE_REVIEW' && !manageReady;
        return el('li', { class: (selected ? 'active ' : '') + (locked ? 'locked' : 'ready') },
          el('button', { type: 'button', disabled: locked ? 'disabled' : null,
            'aria-current': selected ? 'step' : null,
            'aria-label': stage.label + '. ' + (locked ? 'Unlocks after a decision or rehearsal.' : stage.question),
            onclick: async function () {
              if (selected || provisional) return;
              App.navigate(PlanStore.path(plan, stage.key));
            } },
            el('span', { class: 'plan-stage-number' }, stage.n),
            el('span', { class: 'plan-stage-label' }, stage.label)));
      })));
  }

  function planContextEditor(plan) {
    var c = plan.context || {};
    var canRewriteGoal = plan.assumptionsEditable === true;
    var thesis = el('select', { id: 'plan-thesis' },
      ['', 'bullish', 'bearish', 'neutral', 'volatile'].map(function (v) {
        return el('option', { value: v, selected: (c.thesis || '') === v ? 'selected' : null }, v || 'Not set');
      }));
    var horizon = el('input', { id: 'plan-horizon-days', type: 'number', min: '1', max: '730', value: c.horizonDays || Product.Horizon.sessions('month') });
    var target = el('input', { id: 'plan-target-price', type: 'number', min: '0.01', step: '0.01',
      value: c.targetCents ? (c.targetCents / 100).toFixed(2) : '' });
    var risk = el('select', { id: 'plan-risk-mode' }, ['conservative', 'balanced', 'aggressive'].map(function (v) {
      return el('option', { value: v, selected: (c.riskMode || 'conservative') === v ? 'selected' : null },
        v === 'conservative' ? 'Cautious' : v === 'balanced' ? 'Standard' : 'High');
    }));
    function editorValues(nextIntent) {
      return {
        originPlanId: plan.id, symbol: plan.symbol, intent: nextIntent,
        thesis: thesis.value || null, horizonDays: Number(horizon.value),
        targetCents: target.value ? Math.round(Number(target.value) * 100) : null,
        riskMode: risk.value, holdingsShares: c.holdingsShares, costBasisCents: c.costBasisCents,
        priceAssumptionCents: c.priceAssumptionCents
      };
    }
    async function createLinkedGoal(nextIntent) {
      var created = await PlanStore.create(editorValues(nextIntent));
      UI.toast('Revised Plan created; the frozen decision remains unchanged.');
      await PlanStore.focus(created, 'STRATEGY');
      return created;
    }
    var save = el('button', { type: 'button', class: 'btn', id: 'plan-save-context', onclick: async function () {
      save.disabled = true;
      try {
        if (!canRewriteGoal) {
          await createLinkedGoal(plan.intent);
          return;
        }
        var clears = [];
        if (!thesis.value) clears.push('thesis');
        if (!target.value) clears.push('targetCents');
        var updated = await PlanStore.updateContext(plan, {
          thesis: thesis.value || null, horizonDays: Number(horizon.value),
          targetCents: target.value ? Math.round(Number(target.value) * 100) : null,
          riskMode: risk.value, clear: clears
        });
        App.context.update({ symbol: updated.symbol, goal: updated.intent,
          thesis: updated.context.thesis, horizon: updated.context.horizonDays + 'd' });
        App.render();
      } catch (e) { UI.toast(e.message, 'error'); save.disabled = false; }
    } }, canRewriteGoal ? 'Save view & limits' : 'Create revised Plan');
    var goalChoices = el('div', { class: 'choice-grid plan-intent-grid plan-fork-intents', hidden: '' });
    (Learn.INTENTS || []).filter(function (meta) { return meta.key !== plan.intent; }).forEach(function (meta) {
      goalChoices.appendChild(el('button', { type: 'button', class: 'choice-card', onclick: function () {
        var nextIntent = meta.key;
        UI.confirmModal(canRewriteGoal ? 'Change this Plan to ' + meta.label + '?' : 'Create a linked ' + meta.label + ' Plan?',
          el('div', {},
            el('p', {}, canRewriteGoal
              ? 'The ticker and assumptions stay. Evidence, proposed trades and outcome runs that depend on the old goal become stale.'
              : 'This Plan has a frozen decision. A linked Plan preserves that history while carrying its ticker and assumptions forward.'),
            el('p', { class: 'muted' }, canRewriteGoal
              ? 'You return to Strategy to choose a new structure or option type.'
              : 'The linked Plan starts fresh at Strategy.')),
          canRewriteGoal ? 'Change goal' : 'Create linked Plan', async function () {
            if (!canRewriteGoal) { await createLinkedGoal(nextIntent); return; }
            try {
              var live = await PlanStore.get(plan.id, true);
              var updated = await PlanStore.claimIntent(live, nextIntent);
              App.context.update({ symbol: updated.symbol, goal: updated.intent,
                thesis: updated.context.thesis, horizon: updated.context.horizonDays + 'd' });
              await PlanStore.focus(updated, 'STRATEGY');
            } catch (e) {
              UI.toast((e && e.message) || 'The goal could not be changed.', 'error');
            }
          });
      } }, el('b', {}, meta.label), el('span', {}, meta.story || meta.blurb)));
    });
    var changeGoal = el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-change-goal', onclick: function () {
      goalChoices.hidden = !goalChoices.hidden;
      changeGoal.setAttribute('aria-expanded', String(!goalChoices.hidden));
    }, 'aria-expanded': 'false' }, canRewriteGoal ? 'Change goal' : 'Start a linked revision');
    var changeStructure = canRewriteGoal ? el('button', { type: 'button', class: 'btn btn-secondary',
      id: 'plan-change-structure', onclick: function () {
        PlanStore.ui(plan.id).strategyView = 'builder';
        focusPlanFrom(this, plan, 'STRATEGY');
      } }, 'Choose structure or option type') : null;
    function planField(label, input) {
      return UI.field(label, input);
    }
    return el('section', { class: 'plan-context-editor card', id: 'plan-context-editor', hidden: '' },
      UI.cardHeader('Edit this Plan'),
      el('p', { class: 'muted' }, 'Goal: ', el('b', {}, planIntentLabel(plan.intent)),
        canRewriteGoal
          ? '. You can change the goal and structure until you record a decision.'
          : '. Its decision is frozen; a linked revision preserves that history.'),
      el('div', { class: 'form-grid plan-context-fields' },
        planField('View', thesis), planField('Horizon (trading sessions)', horizon),
        planField('Target price', target), planField('Risk budget', risk)),
      el('div', { class: 'btn-row' }, save, changeGoal, changeStructure), goalChoices);
  }

  async function planStartCard(symbol) {
    App.context.selectSymbol(symbol);
    var market = App.state.world === 'demo' ? 'DEMO' : App.state.world === 'observed' ? 'OBSERVED' : 'SIMULATED';
    var eligibilityP = API.get('/api/research/' + encodeURIComponent(symbol)).then(function (data) {
      return { eligible: !!data.planEligible, detail: data.planEligibility || '' };
    }).catch(function (e) {
      return { eligible: false, detail: (e && e.message) || (symbol + ' is unavailable in this market.') };
    });
    var decisionHost = el('div', { class: 'plan-start-decision', id: 'plan-start-decision' });
    async function begin(intent) {
      decisionHost.innerHTML = '';
      decisionHost.setAttribute('aria-busy', 'true');
      var handoff = { symbol: symbol, intent: intent };
      var thesis = App.context.thesis(null);
      var horizon = App.context.horizon(null);
      if (thesis) handoff.thesis = thesis;
      if (horizon) handoff.horizon = horizon;
      var opened = await startPlan(handoff, planIntentDestination(intent));
      if (!opened) {
        decisionHost.removeAttribute('aria-busy');
        decisionHost.innerHTML = '';
        decisionHost.appendChild(alertBox('warn', 'This Plan could not start',
          ['The current market or selected assumptions could not open a Plan. Review the message above, then try again or choose another stock.']));
        decisionHost.appendChild(el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
          App.navigate('#/research');
        } }, 'Choose a stock in this market'));
      }
      return opened;
    }
    var eligibility = await eligibilityP;
    var card = el('section', { class: 'card plan-start-card research-plan-start', id: 'plan-start' },
      UI.cardHeader('Turn this research into a Plan'),
      el('p', { class: 'muted' }, 'Choose what you want the position to accomplish. This creates a durable workspace only after you choose.'));
    var unavailableReason = eligibility.detail || 'Required market inputs are unavailable.';
    if (!eligibility.eligible) card.appendChild(alertBox('caution',
      'An options Plan cannot start for ' + symbol + ' in the ' + market.toLowerCase() + ' market',
      ['Research above remains available. Each unavailable goal below names the missing input.']));
    var choices = el('div', { class: 'choice-grid plan-intent-grid' });
    (Learn.INTENTS || []).forEach(function (meta) {
      var reasonId = 'plan-goal-unavailable-' + String(meta.key || '').toLowerCase();
      var intentButton = el('button', { type: 'button', class: 'choice-card',
        'aria-describedby': eligibility.eligible ? null : reasonId,
        disabled: eligibility.eligible ? null : 'disabled', onclick: function () {
        var button = this;
        visibleCommand(button, function () { return begin(meta.key); }, 'This goal could not be set.');
      } }, el('b', {}, meta.label), el('span', {}, meta.story || meta.blurb || 'Build a plan around this goal.'),
      eligibility.eligible ? null : el('span', { class: 'choice-unavailable', id: reasonId },
        'Unavailable: ' + unavailableReason));
      choices.appendChild(eligibility.eligible ? pressable(intentButton, meta.label, 'button') : intentButton);
    });
    card.appendChild(choices);
    if (!eligibility.eligible) card.appendChild(el('div', { class: 'btn-row' },
      el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () { App.navigate('#/research'); } },
        'Choose another stock')));
    card.appendChild(decisionHost);
    return card;
  }

  function transitionalPlanStage(root, plan, stage) {
    var routes = {
      UNDERSTAND: { title: 'Understand ' + plan.symbol, body: 'Review the market picture before choosing a structure.', route: PlanStore.path(plan, 'UNDERSTAND'), action: 'Open market picture' },
      EVIDENCE: { title: 'Test the view', body: 'Study past analogs and possible futures without requiring a structure.', route: PlanStore.path(plan, 'EVIDENCE'), action: 'Open evidence' },
      MANAGE_REVIEW: { title: 'Manage & Review', body: 'Compare what happened with the expectation frozen at the decision.', route: '#/portfolio', action: 'Open Portfolio' }
    };
    var copy = routes[stage.key];
    root.appendChild(el('section', { class: 'plan-stage-frame', id: 'plan-stage-' + stage.path },
      el('div', { class: 'plan-stage-heading' }, el('div', { class: 'eyebrow' }, stage.question), el('h2', {}, copy.title),
        el('p', { class: 'muted' }, copy.body)),
      el('div', { class: 'card plan-stage-transition' },
        el('p', {}, 'Carried forward: ', el('b', {}, plan.title)),
        el('button', { type: 'button', class: 'btn', onclick: function () { App.navigate(copy.route); } }, copy.action))));
  }

  function planOwnedStage(root, plan, stage) {
    var copy = {
      UNDERSTAND: { title: 'Understand ' + plan.symbol,
        body: 'Read the price, volatility, dated events and source-backed news before forming a trade.' },
      EVIDENCE: { title: 'Test the view',
        body: 'Compare conditional history with explicit possible-futures models. They inform one plan but remain separate evidence bases.' },
      STRATEGY: { title: 'Choose an expression',
        body: 'Compare the ranked field, shape exact contracts, inspect the option book, or scout a linked alternative without leaving this Plan.' },
      OUTCOMES: { title: 'Test the exact position',
        body: 'Judge the selected contracts under market-implied odds, the same stored futures, matching past episodes, and a no-look-ahead rule replay.' },
      DECIDE: { title: 'Trade it, or stay in cash',
        body: 'Reprice the exact Plan package, retain theoretical risk and realistic context, then freeze one deliberate decision.' },
      MANAGE_REVIEW: { title: 'Manage & review',
        body: 'Act on the linked position without leaving this Plan, then compare the result with the expectation frozen at Decide.' }
    }[stage.key];
    var content = el('div', { class: 'plan-stage-content', id: 'plan-stage-content' });
    var headingId = 'plan-stage-title-' + stage.path;
    var context = plan.context || {};
    var carried = [plan.symbol, plan.intent ? planIntentLabel(plan.intent) : 'goal not chosen',
      context.thesis ? context.thesis + ' view' : 'view not set',
      context.horizonDays ? context.horizonDays + ' trading sessions' : null, planMarketLabel(plan)].filter(Boolean).join(' · ');
    root.appendChild(el('section', { class: 'plan-stage-frame', id: 'plan-stage-' + stage.path,
      'aria-labelledby': headingId },
      el('div', { class: 'plan-stage-heading' },
        el('div', { class: 'eyebrow' }, stage.question),
        el('h2', { id: headingId, tabindex: '-1' }, copy.title),
        el('p', { class: 'muted' }, copy.body),
        el('div', { class: 'plan-stage-carry', 'aria-label': 'Context carried into this stage' },
          el('span', { class: 'plan-stage-carry-label' }, 'Carried into this step'),
          el('span', { class: 'plan-stage-carry-value' }, carried),
          el('span', { class: 'plan-stage-carry-receipt expert-only' },
            'context r' + (context.rev || 1) + ' · plan v' + plan.version))),
      content));
    return content;
  }

  function appendPlanStageNext(host, plan, title, detail, stage, label) {
    host.appendChild(el('div', { class: 'plan-next-action', 'data-recommended-next': stage },
      el('div', {}, el('div', { class: 'eyebrow' }, 'RECOMMENDED NEXT'), el('b', {}, title),
        el('p', { class: 'muted' }, detail)),
      el('button', { type: 'button', class: 'btn', onclick: async function () {
        this.disabled = true;
        try {
          var live = await PlanStore.get(plan.id, true);
          var moved = await PlanStore.advance(live, stage);
          App.navigate(PlanStore.path(moved, stage));
        } catch (e) { this.disabled = false; UI.toast(e.message, 'error'); }
      } }, label)));
  }

  function planHorizonName(plan) {
    var days = plan.context && plan.context.horizonDays;
    if (!days) return 'month';
    if (days <= 1) return '0DTE';
    if (days <= 10) return 'week';
    if (days <= 45) return 'month';
    return 'quarter';
  }

  function planIntentLabel(intent) {
    var meta = (Learn.INTENTS || []).find(function (item) { return item.key === intent; });
    return meta ? meta.label : String(intent || '').replaceAll('_', ' ').toLowerCase();
  }

  function planCandidateActions(planRef, candidate, ui, repaint) {
    async function choose(adjust) {
      var livePlan = await PlanStore.get(planRef.plan.id, true);
      var out = await PlanStore.selectCandidate(livePlan, candidate.id);
      planRef.plan = out.plan;
      if (planRef.result && planRef.result.candidates) {
        planRef.result.candidates.forEach(function (c) { c.selected = c.id === candidate.id; });
      }
      planRef.selected = candidate;
      ui.selectedCandidate = candidate;
      candidate.selected = true;
      ui.strategyFocusCandidate = candidate.id;
      if (adjust) {
        ui.buildState = ui.buildState || {};
        Builder.adoptTicket({
          world: App.state.world, symbol: planRef.plan.symbol, candidate: candidate,
          qty: candidate.qty || 1, intent: planRef.plan.intent,
          horizon: planHorizonName(planRef.plan),
          thesis: planRef.plan.context && planRef.plan.context.thesis
        }, ui.buildState, {
          goal: planRef.plan.intent, horizon: planHorizonName(planRef.plan),
          thesis: planRef.plan.context && planRef.plan.context.thesis
        });
        ui.strategyView = 'builder';
      }
      UI.toast('Structure selected for this Plan');
      await repaint();
    }
    return el('div', { class: 'btn-row plan-candidate-actions' },
      el('button', { type: 'button', class: 'btn', disabled: candidate.selected ? '' : null,
        onclick: function () { choose(false).catch(function (e) { UI.toast(e.message, 'error'); }); } },
        candidate.selected ? 'Selected for this Plan' : 'Select this structure'),
      el('button', { type: 'button', class: 'btn btn-secondary',
        onclick: function () { choose(true).catch(function (e) { UI.toast(e.message, 'error'); }); } },
        'Adjust exact contracts'));
  }

  function candidatePackageKey(candidate) {
    if (!candidate) return '';
    return JSON.stringify([candidate.strategy, candidate.qty || 1, (candidate.legs || []).map(function (leg) {
      return [leg.action, leg.type, String(leg.strike == null ? '' : leg.strike), leg.expiration, leg.ratio || 1];
    })]);
  }

  function clearPlanStructureButton(planRef, ui, repaint) {
    if (!planRef.selected) return null;
    return el('button', { type: 'button', class: 'btn btn-secondary plan-clear-structure', onclick: async function () {
      var button = this;
      button.disabled = true;
      try {
        var live = await PlanStore.get(planRef.plan.id, true);
        var out = await PlanStore.clearCandidate(live);
        planRef.plan = out.plan;
        planRef.selected = null;
        ui.selectedCandidate = null;
        ui.selectedLadderKey = null;
        if (planRef.result && planRef.result.candidates) {
          planRef.result.candidates.forEach(function (candidate) { candidate.selected = false; });
        }
        UI.toast('Structure cleared; the comparison remains available');
        await repaint();
      } catch (e) {
        button.disabled = false;
        UI.toast((e && e.message) || 'The structure could not be cleared.', 'error');
      }
    } }, 'Clear selection');
  }

  function planStrategyFilterPanel(ui, onShapeChange) {
    var filters = ui.strategyFilters = ui.strategyFilters || {};
    function numField(key, id, beginnerLabel, expertLabel, infoKey, attrs) {
      var input = el('input', Object.assign({ type: 'number', id: id, placeholder: 'any', value: filters[key] || '' }, attrs || {}));
      input.addEventListener('input', function () { filters[key] = input.value; });
      input.addEventListener('change', function () { if (onShapeChange) onShapeChange(); });
      return el('div', { class: 'field' },
        el('label', { for: id }, Learn.currentLevel() === 'beginner' ? beginnerLabel : expertLabel, UI.info(infoKey)), input);
    }
    var fields = [
      numField('maxLoss', 'plan-f-maxloss', 'Most I am willing to lose ($)', 'Worst case \u2264 $', 'filterloss', { min: '0', step: '50' }),
      numField('minPop', 'plan-f-pop', 'Minimum chance of any profit (%)', 'Chance of profit \u2265 %', 'filterpop', { min: '0', max: '100', step: '5' }),
      numField('maxAssign', 'plan-f-assign', 'Chance I end up with shares (max %)', 'Assignment \u2264 %', 'filterassignment', { min: '0', max: '100', step: '5' }),
      numField('minYield', 'plan-f-yield', 'Income pace (min %/yr)', 'Income \u2265 %/yr', 'filteryield', { min: '0', step: '1' }),
      numField('maxCost', 'plan-f-cost', 'Cash I pay up front (max $)', 'Cash outlay \u2264 $', 'filtercost', { min: '0', step: '50' })
    ];
    var grid = el('div', { class: 'form-grid grid-5 plan-filter-grid' }, fields);
    var node = Learn.currentLevel() === 'beginner'
      ? UI.expandable('Only show proposed trades that fit my limits', function () {
          return el('div', {}, grid,
            el('p', { class: 'muted small' }, 'The same five limits stay available. Refused structures remain listed with the exact reason.'));
        })
      : el('div', { class: 'plan-filter-expert' }, grid,
          el('p', { class: 'muted small' }, 'Blank means no extra limit; the Plan risk budget still applies.'));
    node.id = 'plan-strategy-filters';
    return node;
  }

  function planRiskBudgetReceipt(plan, result) {
    var policy = App.state.riskBudget || {};
    var modeName = String(plan.context && plan.context.riskMode || 'conservative').toLowerCase();
    var mode = (policy.modes || []).find(function (item) { return item.mode === modeName; });
    var basis = Number(policy.basisCents || 0);
    var percent = mode && Number(mode.percent);
    var acquire = plan.intent === 'ACQUIRE';
    var budget = result && result.riskBudgetCents != null ? Number(result.riskBudgetCents)
      : acquire && basis > 0 ? basis : mode && Number(mode.effectiveBudgetCents);
    var line = acquire && budget === basis
      ? fmtMoney(budget) + ' available buying power backs the possible share purchase.'
      : budget != null && percent != null && basis > 0
        ? fmtMoney(budget) + ' = ' + fmtNum(percent * 100, 0) + '% of ' + fmtMoney(basis) + ' current buying power.'
        : budget != null ? 'This Plan can put up to ' + fmtMoney(budget) + ' at risk in one idea.'
          : 'The server applies this Plan’s selected capital budget to every proposed trade.';
    return el('div', { class: 'plan-budget-receipt', id: 'plan-budget-receipt' },
      el('div', {}, el('span', { class: 'eyebrow' }, acquire ? 'PURCHASE CAPITAL' : 'PER-IDEA BUDGET'),
        el('b', {}, line)),
      el('p', { class: 'muted small' }, mode && mode.capped
        ? 'Your declared risk capital caps this amount. The full catalog remains available for learning.'
        : acquire
          ? 'Cash-secured puts reserve strike × 100 shares. This is a purchase commitment, not a small option-risk allowance.'
          : 'This controls sizing and screening, not the strategy catalog or the math.'));
  }

  function strategyShape(name) {
    var meta = strategyMeta(name);
    if (!meta || !meta.payoffShape) return null;
    var span = el('span', { class: 'plan-teaching-shape', 'aria-hidden': 'true' });
    span.innerHTML = '<svg viewBox="0 0 64 28" width="64" height="28">'
      + '<line x1="0" y1="14" x2="64" y2="14" class="tpl-shape-zero"/>'
      + '<polyline points="' + meta.payoffShape + '" class="tpl-shape-line"/></svg>';
    return span;
  }

  function planRejectedTeaching(rejected, ui, repaint) {
    var budget = (rejected || []).filter(function (item) {
      return (item.reasons || []).some(function (reason) { return /above this Plan's .* budget/i.test(reason); });
    });
    var other = (rejected || []).filter(function (item) { return budget.indexOf(item) < 0; });
    if (!budget.length && !other.length) return null;
    var host = el('section', { class: 'plan-rejected-teaching', id: 'plan-rejected-teaching' });
    if (budget.length) {
      host.appendChild(el('div', { class: 'plan-section-head' }, el('div', {},
        el('h3', {}, 'Useful structures above this Plan’s budget'),
        el('p', { class: 'muted' }, 'They are not recommendations at this size, but they remain visible so you can learn the shape, compare the real one-lot risk, or change the Plan deliberately.'))));
      var list = el('div', { class: 'plan-budget-teaching-list' });
      budget.forEach(function (item) {
        var meta = strategyMeta(item.strategy) || {};
        list.appendChild(el('div', { class: 'plan-budget-teaching-row' },
          strategyShape(item.strategy),
          el('div', {}, el('b', {}, item.displayName || meta.display || item.strategy),
            el('p', { class: 'muted small' }, meta.summary || 'A listed strategy with a one-lot position larger than this Plan allows.')),
          el('div', { class: 'plan-budget-teaching-reason' },
            el('span', { class: 'badge badge-caution' }, 'ABOVE BUDGET'),
            el('span', {}, (item.reasons || []).join(' ')))));
      });
      host.appendChild(list);
      host.appendChild(el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn btn-secondary',
        onclick: function () {
          ui.strategyView = 'builder';
          repaint().catch(function (e) { UI.toast((e && e.message) || 'All strategies could not open.', 'error'); });
        } }, 'Explore these in All strategies')));
    }
    if (other.length) {
      host.appendChild(UI.expandable('Why ' + other.length + ' other structure' + (other.length === 1 ? ' was' : 's were') + ' refused', function () {
        return el('div', {}, other.map(function (item) {
          return alertBox('warn', item.displayName || item.strategy || 'Structure', (item.reasons || [item.reason]).filter(Boolean));
        }));
      }));
    }
    return host;
  }

  function planLadderView(result, planRef, ui, repaint) {
    var intent = planRef.plan.intent;
    var rungs = (result && result.rungs || []).filter(function (c) { return !!UI.firstOptionLeg(c && c.legs); });
    var copy = {
      ACQUIRE: ['Name your buy price', 'Each rung is a cash-secured put. Pick a stock price you would genuinely accept, then compare payment, effective purchase price and assignment chance.'],
      EXIT: ['Name your sale price', 'Each rung is a covered call against shares you own. Pick a price you would genuinely accept, then compare payment and call-away chance.'],
      HEDGE: ['Choose a protection floor', 'Each rung buys a put floor under shares you own. Compare the guaranteed floor, premium and expiration.']
    }[intent];
    if (!copy) return null;
    var wrap = el('section', { class: 'card plan-intent-ladder', id: 'plan-intent-ladder' },
      UI.cardHeader(copy[0], el('span', { class: 'badge badge-dim' }, rungs.length + ' RUNGS')),
      el('p', { class: 'muted' }, copy[1]));
    if (!rungs.length) {
      wrap.appendChild(UI.emptyState('No listed rung fits right now', (result && result.notes || []).join(' ') || 'The active book and Plan limits produced no usable strike.'));
      return wrap;
    }
    var spot = Number(result.spot || 0);
    var target = planRef.plan.context && planRef.plan.context.targetCents ? planRef.plan.context.targetCents / 100 : null;
    var reference = 0, best = Infinity;
    function ladderEconomicsBadge(c) {
      var verdict = economicVerdict(c);
      var labels = { FAVORABLE: 'WORTH A LOOK', MIXED: 'COMPARE', UNFAVORABLE: 'LEARN FROM', UNAVAILABLE: 'MECHANICS ONLY' };
      var classes = { FAVORABLE: 'badge-ok', MIXED: 'badge-caution', UNFAVORABLE: 'badge-danger', UNAVAILABLE: 'badge-dim' };
      return verdict ? el('span', { class: 'badge ' + classes[verdict] }, labels[verdict]) : null;
    }
    rungs.forEach(function (c, i) {
      var leg = UI.firstOptionLeg(c.legs), strike = Number(leg.strike);
      var distance = target ? Math.abs(strike - target) : Math.abs(i - Math.floor(rungs.length / 2));
      if (distance < best) { best = distance; reference = i; }
    });
    async function choose(c, button) {
      button.disabled = true;
      try {
        var live = await PlanStore.get(planRef.plan.id, true);
        var out = await PlanStore.saveCustom(live, { symbol: live.symbol, strategy: c.strategy, qty: c.qty || 1,
          legs: c.legs, thesis: live.context && live.context.thesis, horizon: planHorizonName(live),
          riskMode: live.context && live.context.riskMode, intent: live.intent, source: 'INTENT_LADDER' });
        planRef.plan = out.plan;
        planRef.selected = out.strategy && out.strategy.result && out.strategy.result.candidate;
        ui.selectedCandidate = planRef.selected;
        ui.selectedLadderKey = candidatePackageKey(planRef.selected || c);
        UI.toast('Exact ladder package selected for this Plan');
        await repaint();
        var selectedRow = document.querySelector('#plan-intent-ladder .ladder-row.selected, #plan-intent-ladder tr.selected');
        if (selectedRow) selectedRow.scrollIntoView({ block: 'center', behavior: 'smooth' });
      } catch (e) { button.disabled = false; UI.toast(e.message, 'error'); }
    }
    function facts(c) {
      var leg = UI.firstOptionLeg(c.legs), strike = Number(leg.strike);
      var pct = spot ? (strike - spot) / spot * 100 : null;
      var effective = c.effectivePrice == null ? strike : Number(c.effectivePrice);
      var effectivePct = spot ? (effective - spot) / spot * 100 : null;
      var vsNow = effectivePct == null ? '\u2014' : Math.abs(effectivePct).toFixed(1) + '% '
        + (effectivePct < 0 ? 'below now' : effectivePct > 0 ? 'above now' : 'at now');
      if (intent === 'ACQUIRE') return [
        chip('Buy strike', '$' + fmtNum(strike, 2)), chip('Paid now', fmtMoney(c.entryNetPremiumCents)),
        chip('Effective buy', c.effectivePrice == null ? '\u2014' : '$' + fmtNum(c.effectivePrice, 2)),
        chip('Discount after premium', vsNow),
        chip('Chance you buy', fmtPct(c.assignmentProb))];
      if (intent === 'EXIT') return [
        chip('Sell strike', '$' + fmtNum(strike, 2)), chip('Paid now', fmtMoney(c.entryNetPremiumCents)),
        chip('Effective sale', c.effectivePrice == null ? '\u2014' : '$' + fmtNum(c.effectivePrice, 2)),
        chip('Sale level vs now', vsNow),
        chip('Chance you sell', fmtPct(c.assignmentProb))];
      return [chip('Floor', '$' + fmtNum(strike, 2)), chip('vs now', pct == null ? '\u2014' : Math.abs(pct).toFixed(1) + '% ' + (pct < 0 ? 'below' : 'above')),
        chip('Premium at risk', fmtMoney(c.maxLossCents)), chip('Until', leg.expiration)];
    }
    if (Learn.currentLevel() === 'beginner') {
      var list = el('div', { class: 'ladder-sentences' });
      rungs.forEach(function (c, i) {
        var selected = (ui.selectedLadderKey || candidatePackageKey(planRef.selected)) === candidatePackageKey(c);
        var button = el('button', { type: 'button', class: 'btn btn-sm', disabled: selected ? '' : null },
          selected ? 'Selected' : 'Select this rung');
        button.onclick = function () { choose(c, button); };
        list.appendChild(el('div', { class: 'ladder-row' + (i === reference ? ' recommended' : '') + (selected ? ' selected' : ''),
          'aria-current': selected ? 'true' : null },
          el('div', { class: 'ladder-row-badges' },
            i === reference ? el('span', { class: 'badge badge-dim' }, target ? 'CLOSEST TO YOUR TARGET' : 'MIDDLE RUNG') : null,
            ladderEconomicsBadge(c)),
          el('div', { class: 'chip-row' }, facts(c)), button,
          UI.expandable('See exact contracts and risk', function () { return candidateCard(c, false, planRef.plan.symbol); })));
      });
      wrap.appendChild(list);
    } else {
      var tbody = el('tbody', {});
      rungs.forEach(function (c, i) {
        var selected = (ui.selectedLadderKey || candidatePackageKey(planRef.selected)) === candidatePackageKey(c);
        var leg = UI.firstOptionLeg(c.legs), button = el('button', { type: 'button', class: 'btn btn-sm',
          disabled: selected ? '' : null }, selected ? 'Selected' : 'Select');
        var effective = c.effectivePrice == null ? Number(leg.strike) : Number(c.effectivePrice);
        var vsNow = spot ? (effective - spot) / spot * 100 : null;
        button.onclick = function () { choose(c, button); };
        tbody.appendChild(el('tr', { class: (i === reference ? 'ladder-recommended ' : '') + (selected ? 'selected' : ''),
          'aria-current': selected ? 'true' : null },
          el('td', {}, '$' + fmtNum(leg.strike, 2)), el('td', {}, fmtMoney(c.entryNetPremiumCents)),
          el('td', {}, c.effectivePrice == null ? '\u2014' : '$' + fmtNum(c.effectivePrice, 2)),
          el('td', {}, vsNow == null ? '\u2014' : Math.abs(vsNow).toFixed(1) + '% ' + (vsNow < 0 ? 'below' : vsNow > 0 ? 'above' : 'at now')),
          el('td', {}, c.assignmentProb == null ? '\u2014' : fmtPct(c.assignmentProb)),
          el('td', {}, c.annualizedYieldPct == null ? '\u2014' : fmtNum(c.annualizedYieldPct, 1) + '%'),
          el('td', {}, ladderEconomicsBadge(c) || '\u2014'), el('td', {}, button)));
      });
      wrap.appendChild(el('div', { class: 'tbl-wrap' }, el('table', { class: 'tbl ladder-tbl' },
        el('thead', {}, el('tr', {}, ['Strike', 'Premium', 'Effective price', 'vs now', 'Assignment', 'Yield/yr', 'Economics', ''].map(function (h) { return el('th', {}, h); }))), tbody)));
    }
    return wrap;
  }

  function planIncomeBoard(planRef, result, account, positions) {
    var symbol = planRef.plan.symbol;
    var holding = (positions || []).find(function (p) { return p.symbol === symbol; });
    var candidates = result && result.candidates || [];
    var best = candidates.filter(function (c) { return Number(c.entryNetPremiumCents) > 0; })
      .sort(function (a, b) { return Number(b.entryNetPremiumCents) - Number(a.entryNetPremiumCents); })[0];
    return el('section', { class: 'card plan-income-board', id: 'plan-income-board' },
      UI.cardHeader('Your income picture'),
      el('div', { class: 'chip-row' },
        chip('Free buying power', account ? fmtMoney(account.buyingPowerCents) : '\u2014'),
        chip('Shares available', holding ? holding.freeShares + ' ' + symbol : 'none'),
        best ? chip('Largest listed credit', fmtMoney(best.entryNetPremiumCents)) : null,
        best && best.annualizedYieldPct != null ? chip('Income pace', fmtNum(best.annualizedYieldPct, 1) + '%/yr') : null),
      el('p', { class: 'muted' }, Learn.currentLevel() === 'beginner'
        ? 'Income uses cash or shares you already have to collect option premium. The payment is real; so is the obligation to buy or sell shares if assigned.'
        : 'Yield uses opening premium after fees over the shares or full strike cash backing the obligation. Assignment odds are the trade-off, not automatically a failure.'));
  }

  function renderPlanCandidateField(host, planRef, ui, repaint) {
    var candidates = planRef.result && planRef.result.candidates || [];
    if (!candidates.length) {
      host.appendChild(UI.emptyState('No structure passed this screen',
        'The refused list below keeps the reasons visible. Change a limit only if it still matches your Plan.'));
      return;
    }
    if (Learn.currentLevel() === 'expert') {
      var tableCard = comparisonTable(candidates, {
        withUse: false,
        actionLabel: function (c) { return c.selected ? 'Selected' : 'Select'; },
        actionDisabled: function (c) { return !!c.selected; },
        onAction: function (c, button) {
          button.disabled = true;
          PlanStore.get(planRef.plan.id, true).then(function (live) {
            return PlanStore.selectCandidate(live, c.id);
          }).then(function (out) {
            planRef.plan = out.plan;
            candidates.forEach(function (x) { x.selected = x.id === c.id; });
            planRef.selected = c;
            ui.selectedCandidate = c;
            ui.strategyFocusCandidate = c.id;
            UI.toast('Structure selected for this Plan');
            return repaint();
          }).catch(function (e) { button.disabled = false; UI.toast(e.message, 'error'); });
        },
        onRow: function (c) {
          var detail = document.getElementById('plan-candidate-detail');
          if (!detail) return;
          detail.innerHTML = '';
          detail.appendChild(candidateCard(c, false, planRef.plan.symbol));
          detail.appendChild(planCandidateActions(planRef, c, ui, repaint));
          detail.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        }
      });
      host.appendChild(tableCard);
      var expertDetail = el('div', { id: 'plan-candidate-detail' });
      host.appendChild(expertDetail);
      if (ui.strategyFocusCandidate) {
        var focusedExpert = candidates.find(function (c) { return c.id === ui.strategyFocusCandidate; });
        if (focusedExpert) {
          expertDetail.appendChild(candidateCard(focusedExpert, false, planRef.plan.symbol));
          expertDetail.appendChild(planCandidateActions(planRef, focusedExpert, ui, repaint));
          expertDetail.classList.add('plan-return-focus');
          setTimeout(function () { if (expertDetail.isConnected) expertDetail.scrollIntoView({ block: 'center', behavior: 'smooth' }); }, 80);
        }
        delete ui.strategyFocusCandidate;
      }
      return;
    }
    host.appendChild(el('div', { class: 'plan-proposed-heading' },
      el('div', {}, el('span', { class: 'eyebrow' }, 'TOP PROPOSED TRADE'), el('h3', {}, 'Best fit under this Plan’s current limits')),
      el('span', { class: 'muted small' }, 'Ranked, not guaranteed')));
    candidates.forEach(function (c, index) { c._servedRank = index + 1; });
    renderRankedCards(host, candidates, { id: 'plan-ranked-cards', renderCard: function (c) {
      var card = candidateCard(c, false, planRef.plan.symbol);
      card.dataset.candidateId = c.id;
      if (c.selected) card.classList.add('plan-selected-candidate');
      card.appendChild(planCandidateActions(planRef, c, ui, repaint));
      return card;
    } });
    if (ui.strategyFocusCandidate) {
      var focused = host.querySelector('[data-candidate-id="' + CSS.escape(ui.strategyFocusCandidate) + '"]');
      if (focused) {
        focused.classList.add('plan-return-focus');
        setTimeout(function () { if (focused.isConnected) focused.scrollIntoView({ block: 'center', behavior: 'smooth' }); }, 80);
      }
      delete ui.strategyFocusCandidate;
    }
  }

  async function planStrategyStage(root, initialPlan, stage) {
    var planRef = { plan: initialPlan, result: null, selected: null };
    var ui = PlanStore.ui(initialPlan.id);
    ui.strategyView = ui.strategyView || 'compare';
    ui.strategyFilters = ui.strategyFilters || {};
    ui.buildState = ui.buildState || {};
    var content = planOwnedStage(root, initialPlan, stage);
    var selector = el('div', { class: 'plan-tool-selector', role: 'tablist',
      'aria-label': 'Strategy tools' });
    var body = el('div', { class: 'plan-strategy-body', id: 'plan-strategy-body' });
    content.appendChild(selector);
    content.appendChild(body);

    if (!initialPlan.intent) {
      body.appendChild(el('div', { class: 'card' },
        UI.cardHeader('What should this Plan do?'),
        el('p', { class: 'muted' }, 'This choice becomes part of the Plan. It does not hide any strategy or change the math.'),
        el('div', { class: 'choice-grid plan-intent-grid' }, (Learn.INTENTS || []).map(function (meta) {
          return el('button', { type: 'button', class: 'choice-card', onclick: function () {
            var button = this;
            button.disabled = true;
            button.setAttribute('aria-busy', 'true');
            PlanStore.claimIntent(planRef.plan, meta.key).then(function (updated) {
              planRef.plan = updated;
              return PlanStore.focus(updated, 'STRATEGY');
            }).catch(function (e) {
              button.disabled = false;
              button.removeAttribute('aria-busy');
              UI.toast((e && e.message) || 'This goal could not be set.', 'error');
            });
          } }, el('b', {}, meta.label), el('span', {}, meta.story || meta.blurb));
        }))));
      return;
    }

    try {
      var latest = await PlanStore.latestStrategy(initialPlan.id, true);
      planRef.result = latest && latest.strategy && latest.strategy.result || null;
      planRef.selected = latest && latest.selected || null;
      ui.selectedCandidate = planRef.selected;
      if (ui.strategyDraftDirty) planRef.result = null;
    } catch (e) {
      body.appendChild(alertBox('warn', 'Could not restore the last strategy comparison', [e.message]));
    }

    var beginner = Learn.currentLevel() === 'beginner';
    var modes = beginner ? [
      { key: 'compare', label: 'Proposed trades', icon: 'scope', note: 'Ranked for this Plan' },
      { key: 'builder', label: 'All strategies', icon: 'pen', note: 'Visual payoff guide' },
      { key: 'chain', label: 'Option prices', icon: 'grid', note: 'Calls, puts and strikes' },
      { key: 'scout', label: 'Scout', icon: 'compass', note: 'Similar setups and offsets' }
    ] : [
      { key: 'compare', label: 'Ranked field', icon: 'scope', note: 'Economics · score · fit' },
      { key: 'builder', label: 'Builder', icon: 'pen', note: 'Exact contracts' },
      { key: 'chain', label: 'Chain', icon: 'grid', note: 'Inspect the book' },
      { key: 'scout', label: 'Scout', icon: 'compass', note: 'Similar setups · better fits · offsets' }
    ];
    modes.forEach(function (mode) {
      selector.appendChild(el('button', { type: 'button', role: 'tab',
        class: 'plan-tool' + (ui.strategyView === mode.key ? ' active' : ''),
        'data-strategy-tool': mode.key,
        'aria-selected': ui.strategyView === mode.key ? 'true' : 'false',
        onclick: function () { ui.strategyView = mode.key; paint().catch(function (e) { UI.toast(e.message, 'error'); }); }
      }, icon(mode.icon), el('span', {}, el('b', {}, mode.label), el('small', {}, mode.note))));
    });
    UI.bindTabList(selector, function (button) {
      ui.strategyView = button.getAttribute('data-strategy-tool');
      paint().then(function () { button.focus(); }).catch(function (e) { UI.toast(e.message, 'error'); });
    });

    async function paint() {
      selector.querySelectorAll('.plan-tool').forEach(function (button, index) {
        var active = modes[index].key === ui.strategyView;
        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', String(active));
      });
      selector.syncTabs();
      body.innerHTML = '';
      if (ui.strategyView === 'builder') {
        body.appendChild(el('div', { class: 'plan-tool-intro' },
          el('h3', {}, beginner ? 'Every strategy, shown by payoff shape' : 'Build the exact package'),
          el('p', { class: 'muted' }, beginner
            ? 'Choose a visual strategy card, then walk through what each contract adds. No strategy is removed; risky structures explain why the safety screen refuses them.'
            : 'The Plan fixes ' + planRef.plan.symbol + ' and ' + planIntentLabel(planRef.plan.intent)
              + '. Every structure, strike, quantity and limit remains available here.')));
        await Builder.render(body, {
          state: ui.buildState, lockedSymbol: planRef.plan.symbol, lockedGoal: planRef.plan.intent,
          startInCatalog: beginner,
          thesis: planRef.plan.context && planRef.plan.context.thesis,
          horizon: planHorizonName(planRef.plan),
          completeLabel: 'Save exact package to Plan',
          fitToLimits: async function (controls) {
            var live = await PlanStore.get(planRef.plan.id, true);
            planRef.plan = live;
            var out = await PlanStore.fitStrategy(live, controls);
            if (out.plan) planRef.plan = out.plan;
            return out;
          },
          onComplete: function (ticket) {
            if (!ticket) return;
            var position = {
              symbol: planRef.plan.symbol, strategy: ticket.customFamily || 'CUSTOM', qty: ticket.qty,
              legs: ticket.legs, thesis: planRef.plan.context && planRef.plan.context.thesis,
              horizon: planHorizonName(planRef.plan), riskMode: planRef.plan.context && planRef.plan.context.riskMode,
              intent: planRef.plan.intent, source: 'BUILDER'
            };
            PlanStore.get(planRef.plan.id, true).then(function (live) {
              planRef.plan = live;
              return PlanStore.saveCustom(live, position);
            }).then(function (out) {
              planRef.plan = out.plan;
              planRef.selected = out.strategy && out.strategy.result && out.strategy.result.candidate;
              ui.strategyView = 'compare';
              UI.toast('Exact contracts saved to this Plan');
              return paint();
            }).catch(function (e) { UI.toast(e.message, 'error'); });
          }
        });
        return;
      }
      if (ui.strategyView === 'chain') {
        body.appendChild(el('div', { class: 'plan-tool-intro' },
          el('h3', {}, planRef.plan.symbol + ' option chain'),
          el('p', { class: 'muted' }, 'Inspect the live book. In Expert, B starts a custom package from that exact contract.')));
        await research(body, ['__plan', planRef.plan.symbol, 'strategy'], {
          plan: planRef.plan, stage: 'strategy', chainOnly: true,
          onBuildLeg: function (seed) { ui.buildState.builderForm = seed; ui.strategyView = 'builder'; paint(); }
        });
        return;
      }
      if (ui.strategyView === 'scout') {
        ui.scoutScope = ui.scoutScope || 'PEERS';
        ui.scoutResults = ui.scoutResults || {};
        var scoutScopes = [
          { key: 'PEERS', label: 'Similar setups', role: 'PEER', note: 'Look for the same view among related stocks' },
          { key: 'ALTERNATIVES', label: 'Better fits', role: 'ALTERNATIVE', note: 'Look across this market for another stock that may express the same goal better' },
          { key: 'HEDGES', label: 'Offsets', role: 'HEDGE', note: 'Look for a separate idea that may offset this Plan’s directional or sector risk' }
        ];
        var scopeRow = el('div', { class: 'segmented plan-scout-scopes', role: 'tablist', 'aria-label': 'Scout job' });
        scoutScopes.forEach(function (scope) {
          scopeRow.appendChild(el('button', { type: 'button', role: 'tab',
            'data-scout-scope': scope.key,
            class: ui.scoutScope === scope.key ? 'active' : '',
            'aria-selected': String(ui.scoutScope === scope.key), onclick: function () {
              ui.scoutScope = scope.key; paint().catch(function (e) { UI.toast(e.message, 'error'); });
            } }, scope.label));
        });
        UI.bindTabList(scopeRow, function (button) {
          ui.scoutScope = button.getAttribute('data-scout-scope');
          paint().then(function () {
            var selectedScope = body.querySelector('[data-scout-scope="' + ui.scoutScope + '"]');
            if (selectedScope) selectedScope.focus();
          }).catch(function (e) { UI.toast(e.message, 'error'); });
        });
        var scopeMeta = scoutScopes.find(function (scope) { return scope.key === ui.scoutScope; });
        var scoutHead = el('div', { class: 'card plan-scout-head' },
          UI.cardHeader('Scout around ' + planRef.plan.symbol),
          el('p', { class: 'muted' }, scopeMeta.note + '. This keeps ' + planRef.plan.symbol
            + ' as the current Plan; a pick opens a separate linked Plan instead of mixing two stocks in one package.'),
          scopeRow,
          el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn', id: 'plan-run-scout',
            onclick: async function () {
              this.disabled = true; this.setAttribute('aria-busy', 'true');
              try {
                var out = await PlanStore.runScout(planRef.plan, { scope: ui.scoutScope, maxPicks: 4, allow0dte: false });
                ui.scoutResults[ui.scoutScope] = out.scout && out.scout.result;
                await paint();
              } catch (e) { UI.toast(e.message, 'error'); this.disabled = false; this.removeAttribute('aria-busy'); }
            } }, ui.scoutResults[ui.scoutScope] ? 'Refresh this scan' : 'Scan ' + scopeMeta.label.toLowerCase())));
        body.appendChild(scoutHead);
        if (!ui.scoutResults[ui.scoutScope]) {
          try {
            var restoredScout = await PlanStore.latestScout(planRef.plan.id, ui.scoutScope, true);
            ui.scoutResults[ui.scoutScope] = restoredScout && restoredScout.scout && restoredScout.scout.result || null;
          } catch (e) { /* a missing prior run is the normal first-use state */ }
        }
        var scoutResult = ui.scoutResults[ui.scoutScope];
        if (!scoutResult) {
          body.appendChild(UI.emptyState('No ' + scopeMeta.label.toLowerCase() + ' scan yet',
            'Run this focused scan when you already care about ' + planRef.plan.symbol
              + '. Use the universe Scout on Research when you do not have a ticker yet.'));
          return;
        }
        if (!scoutResult.candidates || !scoutResult.candidates.length) {
          body.appendChild(UI.emptyState('Nothing matched this Scout job',
            (scoutResult.economicMessage || 'No related symbol passed.')
              + ' Refreshing rechecks current prices and evidence; it does not change this Plan.'));
        } else {
          body.appendChild(el('p', { class: 'muted' }, scoutResult.economicMessage));
          var scoutGrid = el('div', { class: 'plan-scout-results', id: 'plan-scout-results' });
          scoutResult.candidates.forEach(function (candidate) {
            var card = candidateCard(candidate, false, candidate.symbol);
            card.insertBefore(el('div', { class: 'plan-scout-symbol' },
              el('b', {}, candidate.symbol),
              el('span', { class: 'badge badge-dim' }, String(candidate.scoutThesis || 'unknown').toLowerCase())), card.firstChild);
            card.appendChild(el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn',
              onclick: async function () {
                this.disabled = true; this.setAttribute('aria-busy', 'true');
                try {
                  var out = await PlanStore.spawnScoutedPlan(planRef.plan, candidate.id, scopeMeta.role);
                  UI.toast(candidate.symbol + ' opened as a separate linked Plan');
                  await PlanStore.focus(out.plan, 'STRATEGY');
                } catch (e) { UI.toast(e.message, 'error'); this.disabled = false; this.removeAttribute('aria-busy'); }
              } }, 'Open as linked Plan')));
            scoutGrid.appendChild(card);
          });
          body.appendChild(scoutGrid);
        }
        if (scoutResult.notes && scoutResult.notes.length) {
          body.appendChild(UI.expandable('Scout notes and skipped symbols', function () {
            return el('ul', { class: 'rationale' }, scoutResult.notes.map(function (note) { return el('li', {}, note); }));
          }));
        }
        return;
      }

      var filters = ui.strategyFilters;
      var allow0 = el('input', { type: 'checkbox', id: 'plan-strategy-0dte', checked: filters.allow0dte ? '' : null });
      allow0.addEventListener('change', function () {
        filters.allow0dte = allow0.checked;
        planRef.result = null;
        ui.strategyDraftDirty = true;
        paint().catch(function (e) { UI.toast(e.message, 'error'); });
      });
      function requestValues() {
        var f = {};
        if (filters.minPop) f.minPop = parseFloat(filters.minPop) / 100;
        if (filters.maxAssign) f.maxAssignmentProb = parseFloat(filters.maxAssign) / 100;
        if (filters.minYield) f.minAnnualizedYieldPct = parseFloat(filters.minYield);
        if (filters.maxCost) f.maxCostCents = Math.round(parseFloat(filters.maxCost) * 100);
        return {
          allow0dte: !!filters.allow0dte,
          maxLossCents: filters.maxLoss ? Math.round(parseFloat(filters.maxLoss) * 100) : null,
          filters: Object.keys(f).length ? f : null
        };
      }
      if (['ACQUIRE', 'EXIT', 'HEDGE'].indexOf(planRef.plan.intent) >= 0) {
        try {
          var ladderRequest = Object.assign({ symbol: planRef.plan.symbol, intent: planRef.plan.intent,
            thesis: planRef.plan.context && planRef.plan.context.thesis,
            horizon: planHorizonName(planRef.plan), riskMode: planRef.plan.context && planRef.plan.context.riskMode,
            holdings: {
              sharesOwned: planRef.plan.context && planRef.plan.context.holdingsShares,
              costBasisCents: planRef.plan.context && planRef.plan.context.costBasisCents,
              targetPriceCents: planRef.plan.context && planRef.plan.context.targetCents
            } }, requestValues());
          var ladderAndResearch = await Promise.all([
            API.post('/api/research/' + encodeURIComponent(planRef.plan.symbol) + '/intent-ladder', ladderRequest),
            API.get('/api/research/' + encodeURIComponent(planRef.plan.symbol)).catch(function () { return null; })
          ]);
          var ladder = ladderAndResearch[0];
          ladder.spot = ladderAndResearch[1] && ladderAndResearch[1].displayPrice;
          body.appendChild(planLadderView(ladder, planRef, ui, paint));
        } catch (e) {
          body.appendChild(alertBox('warn', 'The intent ladder is unavailable', [e.message]));
        }
      } else if (planRef.plan.intent === 'INCOME') {
        try {
          var incomeData = await Promise.all([API.get('/api/account'), API.get('/api/positions')]);
          body.appendChild(planIncomeBoard(planRef, planRef.result, incomeData[0].account, incomeData[1].positions));
        } catch (e) {
          body.appendChild(alertBox('warn', 'The income capital summary is unavailable', [e.message]));
        }
      }
      var controls = el('div', { class: 'card plan-strategy-controls' },
        UI.cardHeader(beginner ? 'Find proposed trades for this Plan' : 'Compare structures for this Plan',
          el('span', { class: 'badge badge-dim' }, planRef.plan.symbol + ' · ' + planIntentLabel(planRef.plan.intent))),
        el('p', { class: 'muted' }, beginner
          ? 'StrikeBench compares the complete strategy catalog using this Plan’s goal, view, time, holdings, account and risk budget. Poor fits stay visible as refused or teaching cases.'
          : 'The server uses the Plan’s thesis, horizon, holdings, target, account and risk budget. Optional limits narrow the same complete catalog.'),
        planRiskBudgetReceipt(planRef.plan, planRef.result),
        planStrategyFilterPanel(ui, function () {
          planRef.result = null;
          ui.strategyDraftDirty = true;
          paint().catch(function (e) { UI.toast(e.message, 'error'); });
        }),
        el('label', { class: 'check-row', for: 'plan-strategy-0dte' }, allow0, ' Include same-day expirations (0DTE)'),
        el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn', id: 'plan-run-strategy',
          onclick: async function () {
            this.disabled = true; this.setAttribute('aria-busy', 'true');
            try {
              var out = await PlanStore.runStrategy(planRef.plan, requestValues());
              planRef.plan = out.plan;
              planRef.result = out.strategy && out.strategy.result;
              ui.strategyDraftDirty = false;
              await paint();
            } catch (e) { UI.toast(e.message, 'error'); this.disabled = false; this.removeAttribute('aria-busy'); }
          } }, planRef.result ? 'Refresh proposed trades' : beginner ? 'Find proposed trades' : 'Run ranked field')));
      body.appendChild(controls);
      var selectedInField = planRef.result && planRef.selected
        && (planRef.result.candidates || []).some(function (candidate) { return candidate.id === planRef.selected.id; });
      if (planRef.selected && !selectedInField) {
      var selectedCard = el('div', { class: 'card plan-selected-structure' },
          UI.cardHeader('Selected structure', el('span', { class: 'badge badge-ok' }, 'PLAN OWNED')),
          el('p', { class: 'muted' }, 'This exact package came from the Builder or a linked Scout pick. Run Compare to place it beside the full field.'));
        selectedCard.appendChild(candidateCard(planRef.selected, false, planRef.plan.symbol));
        selectedCard.appendChild(el('div', { class: 'btn-row' },
          planCandidateActions(planRef, planRef.selected, ui, paint), clearPlanStructureButton(planRef, ui, paint)));
        body.appendChild(selectedCard);
      }
      if (!planRef.result) {
        body.appendChild(UI.emptyState(ui.strategyDraftDirty ? 'Limits changed — rerun the field' : 'No comparison has run yet',
          ui.strategyDraftDirty
            ? 'The prior result is still in Plan history, but it is hidden here because it did not use the limits now on screen.'
            : 'Run the complete field once. The Plan saves the exact ranked result so a reload cannot change what you saw.'));
        if (planRef.selected) appendPlanStrategyNext(body, planRef, paint);
        return;
      }
      body.appendChild(el('div', { class: 'card plan-strategy-summary' },
        UI.cardHeader(beginner ? 'Proposed trades, in rank order' : 'Ranked field', el('span', { class: 'badge badge-dim' }, planRef.result.candidates.length + ' candidates')),
        el('p', {}, planRef.result.economicMessage || 'Compare mechanics, evidence, costs and economic placement together.'),
        el('div', { class: 'chip-row' },
          chip('Cash / no trade', '$0 modeled P/L'),
          chip('Favorable', planRef.result.favorableCount || 0), chip('Mixed', planRef.result.mixedCount || 0),
          chip('Unfavorable', planRef.result.unfavorableCount || 0), chip('Unavailable', planRef.result.unavailableCount || 0))));
      var field = el('div', { id: 'plan-strategy-results' });
      body.appendChild(field);
      renderPlanCandidateField(field, planRef, ui, paint);
      if (planRef.selected) {
        body.appendChild(el('div', { class: 'plan-selection-actions' }, clearPlanStructureButton(planRef, ui, paint)));
      }
      if (planRef.result.rejected && planRef.result.rejected.length) {
        body.appendChild(planRejectedTeaching(planRef.result.rejected, ui, paint));
      }
      if (planRef.selected || (planRef.result.candidates || []).some(function (c) { return c.selected; })) {
        appendPlanStrategyNext(body, planRef, paint);
      }
    }
    await paint();
  }

  function appendPlanStrategyNext(host, planRef) {
    appendPlanStageNext(host, planRef.plan, 'Structure chosen',
      'Test this exact package across separate outcome bases.', 'OUTCOMES', 'Continue to Outcomes');
  }

  function planOutcomeBasisCard(run) {
    var result = run && run.result || run || {};
    var title = { RISK_NEUTRAL: 'Market-implied odds', PARAMETRIC: 'Model futures',
      HISTORICAL_ANALOGS: 'Past analogs', CONDITIONAL_BOOTSTRAP: 'Analog resamples' }[run.basis] || run.basis;
    var ev = result.expectedPnlCents;
    if (ev == null) ev = result.expectedValueAfterFeesCents;
    if (ev == null && result.metrics) ev = result.metrics.expectedValueAfterFeesCents;
    var pop = result.winRatePct;
    if (pop == null && result.probabilityMap) pop = result.probabilityMap.pAnyProfit * 100;
    if (pop == null && result.metrics) pop = Number(result.metrics['probabilityMap.pAnyProfit']) * 100;
    return el('div', { class: 'plan-outcome-summary-card', 'data-basis-summary': run.basis },
      el('div', { class: 'plan-outcome-summary-head' }, el('b', {}, title),
        run.ensembleId ? el('span', { class: 'badge badge-dim' }, 'PINNED PATHS') : null),
      el('div', { class: 'chip-row' },
        pop != null && isFinite(pop) ? chip('Chance of profit', Math.round(pop) + '%') : null,
        ev != null ? chip('Expected P/L', pnlSpan(ev)) : null,
        result.p50Cents != null ? chip('Typical', pnlSpan(result.p50Cents)) : null),
      el('p', { class: 'muted small' }, run.interpretation || 'A separate outcome lens.'));
  }

  function planOutcomeWorkspace(config) {
    return Outcomes.workspace(config);
  }

  function renderPlanMarketOutcome(result) {
    result = result || {};
    var metrics = result.metrics || {};
    function value(key) { return result[key] != null ? result[key] : metrics[key]; }
    var map = result.probabilityMap || {};
    function prob(key) {
      var v = map[key] != null ? map[key] : metrics['probabilityMap.' + key];
      return v == null ? null : Number(v);
    }
    var pAny = prob('pAnyProfit'), pMax = prob('pMaxProfit'), pLoss = prob('pMaxLoss');
    var ev = value('expectedValueAfterFeesCents');
    return el('div', { class: 'plan-market-outcome' },
      alertBox(ev != null && ev >= 0 ? 'ok' : 'caution',
        'Market-implied economics for the exact package', [
          'These are the option market’s risk-neutral odds at the captured executable entry, not a forecast.',
          ev == null ? 'Expected value is unavailable.' : 'Expected value after estimated round-trip fees: ' + UI.fmtMoneyCompact(ev) + '.'
        ]),
      el('div', { class: 'grid grid-4' },
        stat('Chance of any profit', pAny == null ? '—' : Math.round(pAny * 100) + '%'),
        stat('Chance of theoretical max profit', pMax == null ? '—' : Math.round(pMax * 100) + '%'),
        stat('Chance of theoretical max loss', pLoss == null ? '—' : Math.round(pLoss * 100) + '%'),
        stat('Expected P/L after costs', ev == null ? '—' : pnlSpan(ev))),
      el('p', { class: 'muted small' }, value('source') ? 'Priced from ' + value('source') + ' · ' + (value('freshness') || 'source age unavailable') : 'The saved result retains its captured market inputs.'));
  }

  function renderPlanBacktestReport(host, report) {
    host.innerHTML = '';
    if (!report) return;
    if (report.demoUnderlying) host.appendChild(alertBox('danger',
      'Demo price history — this replay teaches mechanics and does not describe the real market.'));
    host.appendChild(alertBox(report.pricingMode === 'HISTORICAL_CHAIN' || report.pricingMode === 'OBSERVED_FROM_HISTORY' ? 'ok' : 'caution',
      'Pricing: ' + prettyPricingMode(report.pricingMode) + ' · confidence ' + (report.confidence || 'unknown'),
      [report.pricingMode === 'HISTORICAL_CHAIN'
        ? 'Historical option observations were available.'
        : 'At least some option prices were modeled from the underlying history; the result is not a record of executable historical fills.']));
    var assumptions = report.assumptions || {};
    if (assumptions.annualRate != null) {
      host.appendChild(el('p', { class: 'muted small plan-replay-inputs' },
        'Replay model inputs: ' + (Number(assumptions.annualRate) * 100).toFixed(2) + '% annual rate from '
          + (assumptions.rateSource || 'an unavailable source') + ' (' + String(assumptions.rateProvenance || 'unknown').toLowerCase()
          + ') · ' + (Number(assumptions.fallbackVolatility) * 100).toFixed(0)
          + '% modeled volatility only when trailing history is unavailable. The rate is held constant; it is not a historical yield curve.'));
    }
    host.appendChild(el('div', { class: 'grid grid-4' },
      stat('Completed trades', String(report.sampleSize || 0)),
      stat('Win rate', fmtPct(report.winRate)),
      stat('Average return on risk', fmtPct(report.avgReturnOnRisk)),
      stat('Max drawdown', fmtPct(report.maxDrawdownPct)),
      report.concurrentPeak != null ? stat('Peak positions', String(report.concurrentPeak)) : null,
      report.startingCents != null ? stat('Start → end', UI.fmtMoneyCompact(report.startingCents) + ' → ' + UI.fmtMoneyCompact(report.endingCents)) : null));
    if (report.equityCurve && report.equityCurve.length > 1) {
      host.appendChild(el('div', { class: 'card' }, UI.cardHeader('Equity curve'),
        UI.lineChart(report.equityCurve.map(function (point) {
          return { date: point.date, value: point.equityCents };
        }), { money: true })));
    }
    if (report.trades && report.trades.length) {
      var tradeTable = table(['Entry', 'Exit', 'Position', 'P/L', 'Worst case', 'Why closed'],
        report.trades.map(function (trade) {
          return el('tr', {}, el('td', { class: 'muted', 'data-label': 'Entry' }, trade.entryDate),
            el('td', { class: 'muted', 'data-label': 'Exit' }, trade.exitDate),
            el('td', { class: 'plan-replay-position', 'data-label': 'Position' }, trade.label || prettyStrategy(trade.strategy)),
            el('td', { 'data-label': 'P/L' }, pnlSpan(trade.pnlCents)),
            el('td', { class: 'loss', 'data-label': 'Worst case' }, trade.maxLossCents != null ? fmtMoney(trade.maxLossCents) : '—'),
            el('td', { class: 'muted', 'data-label': 'Why closed' }, trade.exitReason));
        }));
      tradeTable.classList.add('plan-replay-trades');
      host.appendChild(el('div', { class: 'card' }, UI.cardHeader('Trades (' + report.trades.length + ')'), tradeTable));
    }
    (report.notes || []).forEach(function (note) { host.appendChild(alertBox('warn', note)); });
    if (report.disclaimer) host.appendChild(el('p', { class: 'muted small' }, report.disclaimer));
  }

  function planProposalComparisonView(comparison, planRef, ui) {
    if (!comparison) return null;
    var items = comparison.items || [];
    var fingerprint = comparison.ensembleFingerprint || '';
    function economicsBadge(item) {
      if (item.key === 'CASH') return el('span', { class: 'badge badge-dim' }, 'CASH BASELINE');
      var verdict = item.economicVerdict || 'UNAVAILABLE';
      var labels = { FAVORABLE: 'FAVORABLE', MIXED: 'MIXED', UNFAVORABLE: 'UNFAVORABLE', UNAVAILABLE: 'INCOMPLETE' };
      var classes = { FAVORABLE: 'badge-ok', MIXED: 'badge-caution', UNFAVORABLE: 'badge-danger', UNAVAILABLE: 'badge-dim' };
      return el('span', { class: 'badge ' + classes[verdict] }, 'BOOK: ' + labels[verdict]);
    }
    function reviewAction(item) {
      if (!item.candidateId) return null;
      if (item.selected) return el('span', { class: 'badge badge-ok' }, 'CURRENT STRUCTURE');
      var button = el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: async function () {
        button.disabled = true;
        try {
          ui.strategyView = 'compare'; ui.strategyFocusCandidate = item.candidateId;
          App.navigate(PlanStore.path(planRef.plan, 'STRATEGY'));
        } catch (e) { button.disabled = false; UI.toast(e.message, 'error'); }
      } }, Learn.currentLevel() === 'beginner' ? 'Review this trade' : 'Review in Strategy');
      return button;
    }
    function itemCard(item) {
      var refused = item.refusalReason;
      return el('article', { class: 'plan-proposal-result' + (item.selected ? ' selected' : '') + (refused ? ' refused' : ''),
        'data-comparison-candidate': item.candidateId || item.key },
        el('div', { class: 'plan-proposal-result-head' },
          el('div', {}, el('span', { class: 'eyebrow' }, '#' + item.rank + ' ON THIS LENS'),
            el('h4', {}, item.displayName || item.strategy)),
          el('div', { class: 'chip-row' }, economicsBadge(item), reviewAction(item))),
        refused ? alertBox('caution', 'Could not value this package on the shared paths', [refused])
          : el('div', { class: 'plan-proposal-facts' },
              stat('Chance this package profits', item.winRatePct == null ? '—' : Math.round(item.winRatePct) + '%'),
              stat('Expected P/L after costs', item.expectedPnlCents == null ? '—' : pnlSpan(item.expectedPnlCents)),
              stat('Typical outcome', item.p50Cents == null ? '—' : pnlSpan(item.p50Cents)),
              stat('Bad 1-in-20 outcome', item.p5Cents == null ? '—' : pnlSpan(item.p5Cents)),
              stat('Theoretical max loss', item.maxLossCents == null ? '—' : fmtMoney(item.maxLossCents))),
        item.key === 'CASH'
          ? el('p', { class: 'muted small' }, 'Doing nothing stays at $0 with no modeled tail loss or transaction cost.')
          : el('p', { class: 'muted small' }, 'Quantity x' + item.qty + ' · captured entry '
              + (item.entryCostCents == null ? 'unavailable' : UI.fmtMoneyCompact(item.entryCostCents))
              + ' · estimated round-trip costs ' + UI.fmtMoneyCompact(item.roundTripFeesCents || 0)
              + ' · EV per $ of modeled p5 downside ' + (item.tailReturnScore == null ? '—' : fmtNum(item.tailReturnScore, 2))));
    }
    var body;
    if (Learn.currentLevel() === 'beginner') {
      var first = items.slice(0, 3);
      var cash = items.find(function (item) { return item.key === 'CASH'; });
      if (cash && !first.includes(cash)) first.push(cash);
      var shown = new Set(first.map(function (item) { return item.key; }));
      var remaining = items.filter(function (item) { return !shown.has(item.key); });
      body = el('div', { class: 'plan-proposal-beginner' },
        el('div', { class: 'plan-proposal-card-grid' }, first.map(itemCard)),
        remaining.length ? UI.expandable('Show all ' + items.length + ' compared choices', function () {
          return el('div', { class: 'plan-proposal-card-grid' }, remaining.map(itemCard));
        }) : null);
    } else {
      body = el('div', { class: 'tbl-wrap plan-proposal-table-wrap' },
        el('table', { class: 'tbl plan-proposal-table' },
          el('thead', {}, el('tr', {}, ['#', 'Proposal', 'Economic view', 'Win %', 'EV after costs',
            'Typical', 'p5 outcome', 'Theor. max loss'].map(function (label) { return el('th', {}, label); }))),
          el('tbody', {}, items.map(function (item) {
            return el('tr', { class: item.selected ? 'selected' : '', 'data-comparison-candidate': item.candidateId || item.key },
              el('td', {}, '#' + item.rank), el('td', { class: 'plan-proposal-name-cell' },
                el('b', {}, item.displayName || item.strategy),
                item.refusalReason ? el('div', { class: 'loss small' }, item.refusalReason) : null,
                reviewAction(item)),
              el('td', {}, economicsBadge(item)),
              el('td', {}, item.winRatePct == null ? '—' : Math.round(item.winRatePct) + '%'),
              el('td', {}, item.expectedPnlCents == null ? '—' : pnlSpan(item.expectedPnlCents)),
              el('td', {}, item.p50Cents == null ? '—' : pnlSpan(item.p50Cents)),
              el('td', {}, item.p5Cents == null ? '—' : pnlSpan(item.p5Cents)),
              el('td', {}, item.maxLossCents == null ? '—' : fmtMoney(item.maxLossCents)));
          }))));
    }
    var leader = items[0];
    return el('section', { class: 'plan-proposal-comparison-result', 'data-comparison-basis': comparison.basis },
      el('div', { class: 'plan-section-head' }, el('div', {}, el('span', { class: 'eyebrow' }, 'SAME PATHS · DIFFERENT STRUCTURES'),
        el('h3', {}, 'Which proposal handles this evidence best?'),
        el('p', { class: 'muted' }, comparison.interpretation || 'Every proposal used one exact path ensemble.')),
        fingerprint ? el('span', { class: 'badge badge-dim mono' }, 'RECEIPT ' + fingerprint.slice(0, 12) + '…') : null),
      el('p', { class: 'muted small' }, 'The BOOK badge preserves the option-market economic assessment. The figures below answer a separate question: how each package behaved on this one shared evidence set.'),
      leader && leader.key === 'CASH' ? alertBox('caution', 'Cash leads on this outcome lens', [
        'Every valued proposal ranked below the zero-risk, zero-cost baseline after estimated costs. The trades remain available to study; this lens does not endorse them.'
      ]) : null,
      body,
      el('p', { class: 'muted small' }, Learn.currentLevel() === 'beginner'
        ? 'All rows used the same saved receipt, captured proposal entry, quantity and estimated costs. Higher ranks balance the expected result against the bad outcomes; cash stayed at $0.'
        : comparison.fairness + ' Outcome rank is after-cost EV per dollar of modeled p5 downside; refusals sort last.'));
  }

  async function planOutcomesStage(root, initialPlan, stage) {
    var planRef = { plan: initialPlan };
    var ui = PlanStore.ui(initialPlan.id);
    ui.outcomes = ui.outcomes || { mode: 'market' };
    ui.outcomeRuns = ui.outcomeRuns || {};
    ui.outcomeComparisons = ui.outcomeComparisons || {};
    ui.backtest = ui.backtest || {};
    var content = planOwnedStage(root, initialPlan, stage);
    var latest = await PlanStore.latestOutcomes(initialPlan.id, true);
    var selected = latest.selected;
    if (!selected) {
      content.appendChild(UI.emptyState('Choose a structure first',
        'Outcomes evaluates one exact package. Return to Strategy, select it, then every lens here uses those same contracts.',
        'Open Strategy', function () { focusPlanFrom(this, planRef.plan, 'STRATEGY'); }));
      return;
    }
    (latest.outcomes || []).forEach(function (run) { ui.outcomeRuns[run.basis] = run; });
    (latest.comparisons || []).forEach(function (run) { ui.outcomeComparisons[run.basis] = run; });
    var allBacktests = latest.backtests || [];
    var latestBacktest = allBacktests.find(function (run) {
      return run.currentContext && run.state === 'CURRENT';
    }) || null;
    content.appendChild(el('div', { class: 'card plan-outcome-position' },
      UI.cardHeader('Exact position being tested', el('span', { class: 'badge badge-ok' }, 'PLAN OWNED')),
      el('div', { class: 'chip-row' }, chip('Structure', selected.displayName || prettyStrategy(selected.strategy)),
        chip('Contracts', (selected.legs || []).length + ' legs · x' + (selected.qty || 1)),
        chip('Entry', fmtMoney(selected.entryNetPremiumCents, { plus: true }))),
      UI.expandable('Show the exact contracts and structural risk', function () {
        return candidateCard(selected, false, planRef.plan.symbol);
      })));

    var comparison = el('section', { class: 'plan-outcome-comparison', id: 'plan-outcome-comparison' },
      el('div', { class: 'plan-section-head' }, el('div', {}, el('h3', {}, 'One position, separate lenses'),
        el('p', { class: 'muted' }, 'Each result keeps its own interpretation. Agreement raises confidence; disagreement is information, never an averaged probability.'))));
    function paintComparison() {
      comparison.querySelectorAll('.plan-outcome-summary-grid,.empty').forEach(function (node) { node.remove(); });
      var runs = Object.keys(ui.outcomeRuns).map(function (key) { return ui.outcomeRuns[key]; }).filter(Boolean);
      if (!runs.length) comparison.appendChild(UI.emptyState('No outcome lens has run yet', 'Start with market-implied odds, then add a model or historical lens.'));
      else comparison.appendChild(el('div', { class: 'plan-outcome-summary-grid' }, runs.map(planOutcomeBasisCard)));
    }
    paintComparison();
    content.appendChild(comparison);

    var workspace = planOutcomeWorkspace({ id: 'plan-outcomes', state: ui.outcomes, label: 'Outcome basis',
      modes: [
        { key: 'market', label: 'Market odds', description: 'Implied by the option book',
          note: 'Risk-neutral odds and after-cost EV from the captured executable package; market pricing, not a forecast.',
          render: renderMarket },
        { key: 'model', label: 'Model futures', description: 'The Evidence ensemble',
          note: 'The exact position on the same stored path matrix and IV trajectory shown in Evidence.', render: renderModel },
        { key: 'analogs', label: 'Past analogs', description: 'Matching historical episodes',
          note: 'Direct analog occurrences or whole-path bootstrap resamples. Conditional history, never model odds.', render: renderAnalogs },
        { key: 'backtest', label: 'Rule replay', description: 'Repeated historical entries',
          note: 'A named strategy rule replayed without look-ahead. This is not the exact listed contract package.', render: renderBacktest }
      ] });
    content.appendChild(workspace.el);
    appendPlanStageNext(content, planRef.plan, 'Compare the position with cash',
      'Decide reprices these exact contracts and preserves either Trade or Cash for later review.',
      'DECIDE', 'Continue to Decide');

    async function runBasis(basis, button, extra) {
      button.disabled = true; button.setAttribute('aria-busy', 'true');
      try {
        var live = await PlanStore.get(planRef.plan.id, true); planRef.plan = live;
        var out = await PlanStore.runOutcome(live, Object.assign({ basis: basis }, extra || {}));
        ui.outcomeRuns[basis] = out.outcome;
        paintComparison();
        return out.outcome;
      } finally { button.disabled = false; button.removeAttribute('aria-busy'); }
    }

    async function compareBasis(basis, button, extra) {
      button.disabled = true; button.setAttribute('aria-busy', 'true');
      try {
        var live = await PlanStore.get(planRef.plan.id, true); planRef.plan = live;
        var out = await PlanStore.compareOutcomes(live, Object.assign({ basis: basis }, extra || {}));
        ui.outcomeComparisons[basis] = out.comparison;
        return out.comparison;
      } finally { button.disabled = false; button.removeAttribute('aria-busy'); }
    }

    function renderMarket(host) {
      var saved = ui.outcomeRuns.RISK_NEUTRAL;
      var status = el('div', { class: 'plan-outcome-action-status', 'aria-live': 'polite' });
      host.appendChild(el('div', { class: 'card' }, UI.cardHeader('What is the option market pricing?'),
        el('p', { class: 'muted' }, 'This is the common market-implied baseline used by Strategy and the exact ticket. It does not claim to predict the stock.'),
        el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn', onclick: async function () {
          try { saved = await runBasis('RISK_NEUTRAL', this); workspace.refresh(); }
          catch (e) { status.innerHTML = ''; status.appendChild(alertBox('caution', 'Market odds are unavailable for this package', [e.message])); }
        } }, saved ? 'Refresh market odds' : 'Calculate market odds')), status));
      if (saved) host.appendChild(renderPlanMarketOutcome(saved.result || saved));
    }

    function renderModel(host) {
      var saved = ui.outcomeRuns.PARAMETRIC;
      var compared = ui.outcomeComparisons.PARAMETRIC;
      var evidenceUi = PlanStore.ui(planRef.plan.id);
      var status = el('div', { class: 'plan-outcome-action-status', 'aria-live': 'polite' });
      host.appendChild(el('div', { class: 'card' }, UI.cardHeader('Use the Evidence ensemble'),
        el('p', { class: 'muted' }, evidenceUi.planEnsembleFingerprint
          ? 'Ready: exact stored receipt ' + evidenceUi.planEnsembleFingerprint.slice(0, 12) + '…'
          : 'If Evidence already ran possible futures, the server restores that exact matrix. Otherwise open Evidence and set the scenario first.'),
        el('div', { class: 'btn-row' },
          el('button', { type: 'button', class: 'btn', onclick: async function () {
            try { saved = await runBasis('PARAMETRIC', this,
              evidenceUi.planEnsembleId ? { ensembleId: evidenceUi.planEnsembleId } : {}); workspace.refresh(); }
            catch (e) { status.innerHTML = ''; status.appendChild(alertBox('danger', 'Could not test the position on these futures', [e.message])); }
          } }, saved ? 'Run again on stored futures' : 'Test this position on the stored futures'),
          el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () {
            focusPlanFrom(this, planRef.plan, 'EVIDENCE');
          } }, 'Review or change the scenario'),
          el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-compare-parametric', onclick: async function () {
            try {
              compared = await compareBasis('PARAMETRIC', this, evidenceUi.planEnsembleId
                ? { ensembleId: evidenceUi.planEnsembleId } : {});
              workspace.refresh();
            } catch (e) { status.innerHTML = ''; status.appendChild(alertBox('danger', 'Could not compare the Plan proposals', [e.message])); }
          } }, compared ? 'Refresh proposal comparison' : (Learn.currentLevel() === 'beginner'
            ? 'Compare all proposed trades here' : 'Compare Plan proposals on this ensemble'))), status));
      if (saved) host.appendChild(Scenario.pnlView(saved.result || saved, Learn.currentLevel()));
      if (compared) host.appendChild(planProposalComparisonView(compared, planRef, ui));
    }

    function renderAnalogs(host) {
      var direct = ui.outcomeRuns.HISTORICAL_ANALOGS;
      var bootstrap = ui.outcomeRuns.CONDITIONAL_BOOTSTRAP;
      var directComparison = ui.outcomeComparisons.HISTORICAL_ANALOGS;
      var bootstrapComparison = ui.outcomeComparisons.CONDITIONAL_BOOTSTRAP;
      var status = el('div', { class: 'plan-outcome-action-status', 'aria-live': 'polite' });
      host.appendChild(el('div', { class: 'card' }, UI.cardHeader('Price the package on the Plan’s stored analog sample'),
        el('p', { class: 'muted' }, 'Direct occurrences answer “what happened in these matches?” Bootstrap resamples the whole paths to show sampling uncertainty. Neither becomes a forecast.'),
        el('div', { class: 'btn-row' },
          el('button', { type: 'button', class: 'btn', onclick: async function () {
            try { direct = await runBasis('HISTORICAL_ANALOGS', this); workspace.refresh(); }
            catch (e) { status.innerHTML = ''; status.appendChild(alertBox('danger', 'Direct analog outcomes are unavailable', [e.message])); }
          } }, direct ? 'Refresh direct analogs' : 'Run on direct analogs'),
          el('button', { type: 'button', class: 'btn btn-secondary', onclick: async function () {
            try { bootstrap = await runBasis('CONDITIONAL_BOOTSTRAP', this); workspace.refresh(); }
            catch (e) { status.innerHTML = ''; status.appendChild(alertBox('danger', 'Bootstrap outcomes are unavailable', [e.message])); }
          } }, bootstrap ? 'Refresh bootstrap' : 'Run conditional bootstrap'),
          el('button', { type: 'button', class: 'btn btn-ghost', onclick: function () { focusPlanFrom(this, planRef.plan, 'EVIDENCE'); } }, 'Review Past evidence')), status));
      if (direct) host.appendChild(el('div', { class: 'card' }, UI.cardHeader('Direct occurrences'), Scenario.pnlView(direct.result || direct, Learn.currentLevel())));
      if (bootstrap) host.appendChild(el('div', { class: 'card' }, UI.cardHeader('Whole-path bootstrap'), Scenario.pnlView(bootstrap.result || bootstrap, Learn.currentLevel())));
      host.appendChild(el('div', { class: 'card plan-analog-comparison-actions' },
        UI.cardHeader('Compare the Plan proposals on one historical sample'),
        el('p', { class: 'muted' }, 'This changes the structure being judged, not the evidence. Every row uses the same stored occurrences and its own captured entry and quantity.'),
        el('div', { class: 'btn-row' },
          el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-compare-analogs', onclick: async function () {
            try { directComparison = await compareBasis('HISTORICAL_ANALOGS', this); workspace.refresh(); }
            catch (e) { status.innerHTML = ''; status.appendChild(alertBox('danger', 'Could not compare proposals on direct analogs', [e.message])); }
          } }, directComparison ? 'Refresh direct comparison' : 'Compare on direct analogs'),
          el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-compare-bootstrap', onclick: async function () {
            try { bootstrapComparison = await compareBasis('CONDITIONAL_BOOTSTRAP', this); workspace.refresh(); }
            catch (e) { status.innerHTML = ''; status.appendChild(alertBox('danger', 'Could not compare proposals on resampled analogs', [e.message])); }
          } }, bootstrapComparison ? 'Refresh bootstrap comparison' : 'Compare on analog resamples'))));
      if (directComparison) host.appendChild(planProposalComparisonView(directComparison, planRef, ui));
      if (bootstrapComparison) host.appendChild(planProposalComparisonView(bootstrapComparison, planRef, ui));
    }

    function renderBacktest(host) {
      var form = ui.backtest;
      var today = new Date();
      var toDefault = today.toISOString().slice(0, 10);
      var fromDefault = new Date(today.getTime() - 365 * 86400000).toISOString().slice(0, 10);
      var from = el('input', { id: 'plan-replay-from', type: 'date', value: form.from || fromDefault });
      var to = el('input', { id: 'plan-replay-to', type: 'date', value: form.to || toDefault });
      var engine = el('select', { id: 'plan-replay-engine' }, el('option', { value: 'single' }, 'One trade at a time'),
        el('option', { value: 'portfolio' }, 'A book of overlapping trades'));
      engine.value = form.engine || 'single';
      var planSessions = planRef.plan.context.horizonDays || Product.Horizon.sessions('month');
      var defaultDte = Product.Horizon.expiryDays(Product.Horizon.keyForSessions(planSessions));
      var dte = el('input', { id: 'plan-replay-dte', type: 'number', min: '1', max: '365', value: form.targetDte || defaultDte });
      var qty = el('input', { id: 'plan-replay-qty', type: 'number', min: '1', max: '100', value: form.qty || 1 });
      var every = el('input', { id: 'plan-replay-spacing', type: 'number', min: '1', max: '60', value: form.entryEveryDays || 5 });
      var cash = el('input', { id: 'plan-replay-cash', type: 'number', min: '1', step: '1000', value: form.startingCash || 100000 });
      var slip = el('input', { id: 'plan-replay-slippage', type: 'number', min: '0', max: '10', step: '0.1', value: form.slippagePct == null ? 0.5 : form.slippagePct });
      function rememberForm() {
        form.from=from.value; form.to=to.value; form.engine=engine.value; form.targetDte=dte.value;
        form.qty=qty.value; form.entryEveryDays=every.value; form.startingCash=cash.value; form.slippagePct=slip.value;
      }
      [from,to,engine,dte,qty,every,cash,slip].forEach(function (input) { input.addEventListener('change', function () {
        rememberForm();
      }); });
      var results = el('div', { id: 'plan-backtest-result' });
      var history = el('div', { id: 'plan-backtest-history' });
      function setRange(months, button) {
        var end = new Date();
        var start = new Date(end);
        if (months === 'max') start = new Date('2000-01-01T00:00:00Z');
        else start.setUTCMonth(start.getUTCMonth() - months);
        to.value = end.toISOString().slice(0, 10);
        from.value = start.toISOString().slice(0, 10);
        rememberForm();
        button.parentNode.querySelectorAll('button').forEach(function (node) { node.classList.toggle('active', node === button); });
      }
      function setDte(days, button) {
        dte.value = String(days); rememberForm();
        button.parentNode.querySelectorAll('button').forEach(function (node) { node.classList.toggle('active', node === button); });
      }
      function paintHistory() {
        history.innerHTML = '';
        if (!allBacktests.length) return;
        history.appendChild(UI.expandable('Previous Plan replays (' + allBacktests.length + ')', function () {
          return el('div', { class: 'plan-backtest-history-list' }, allBacktests.map(function (run) {
            var load = el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: async function () {
              this.disabled = true;
              try {
                var report = await API.get('/api/plans/' + planRef.plan.id + '/outcomes/backtests/' + run.backtestId);
                renderPlanBacktestReport(results, report); results.scrollIntoView({ behavior: 'smooth', block: 'start' });
              } catch (e) { UI.toast(e.message, 'error'); }
              finally { this.disabled = false; }
            } }, 'Open result');
            return el('div', { class: 'status-item plan-backtest-history-row' },
              el('div', {}, el('b', {}, UI.fmtDate(run.createdAt)),
                el('div', { class: 'muted small' }, (run.engineKind === 'portfolio' ? 'Overlapping book' : 'One trade at a time')
                  + ' · ' + String(run.sampleSize || 0) + ' completed · ' + fmtPct(run.winRate))),
              el('div', { class: 'chip-row' }, run.currentContext && run.state === 'CURRENT'
                ? el('span', { class: 'badge badge-ok' }, 'CURRENT ASSUMPTIONS')
                : el('span', { class: 'badge badge-dim' }, 'OLDER ASSUMPTIONS'), load));
          }));
        }));
      }
      var rangePresets = el('div', { class: 'segmented plan-backtest-presets', 'aria-label': 'Replay date range' },
        [['6M',6],['1Y',12],['3Y',36],['MAX','max']].map(function (preset) {
          return el('button', { type: 'button', onclick: function () { setRange(preset[1], this); } }, preset[0]);
        }));
      var dtePresets = el('div', { class: 'segmented plan-backtest-presets', 'aria-label': 'Target days to expiry' },
        [['Weekly',7],['Monthly',30],['Quarterly',90]].map(function (preset) {
          return el('button', { type: 'button', onclick: function () { setDte(preset[1], this); } }, preset[0]);
        }));
      host.appendChild(el('div', { class: 'card' }, UI.cardHeader('Replay ' + (selected.displayName || prettyStrategy(selected.strategy))),
        el('p', { class: 'muted' }, 'Symbol and strategy come from this Plan. The replay builds the named rule repeatedly; it does not pretend today’s exact strikes existed in the past.'),
        el('div', { class: 'plan-backtest-preset-row' },
          el('div', {}, el('span', { class: 'muted small' }, 'Quick range'), rangePresets),
          el('div', {}, el('span', { class: 'muted small' }, 'Common expiry window'), dtePresets)),
        el('div', { class: 'form-grid' },
          UI.field('From', from),
          UI.field('To', to),
          UI.field(['Target DTE', UI.info('dte')], dte),
          UI.field('Replay engine', engine)),
        UI.expandable('Replay controls', function () { return el('div', { class: 'form-grid' },
          UI.field('Contracts per entry', qty),
          UI.field('Enter every N sessions', every),
          UI.field('Starting capital $', cash),
          UI.field('Extra slippage per leg %', slip)); }),
        el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn', onclick: async function () {
          this.disabled=true; this.setAttribute('aria-busy','true'); results.innerHTML=''; results.appendChild(UI.spinner('Replaying without look-ahead…'));
          try {
            var live=await PlanStore.get(planRef.plan.id,true); planRef.plan=live;
            var out=await PlanStore.runBacktest(live,{engine:engine.value,from:from.value,to:to.value,
              targetDte:positiveInteger(dte.value,'Target DTE',365),entryEveryDays:positiveInteger(every.value,'Entry spacing',60),
              qty:positiveInteger(qty.value,'Quantity',100),slippagePct:Math.max(0,Number(slip.value))/100,
              startingCashCents:Math.round(Number(cash.value)*100),maxConcurrent:4,shortDelta:0.30,widthPct:0.05,
              profitTargetPct:0.5,stopFraction:0.8,rollDte:7});
            latestBacktest=out.backtest; renderPlanBacktestReport(results,out.report);
            latest=await PlanStore.latestOutcomes(planRef.plan.id,true); allBacktests=latest.backtests||[]; paintHistory();
          } catch(e){results.innerHTML='';results.appendChild(alertBox('danger','Replay failed',[e.message]));}
          finally{this.disabled=false;this.removeAttribute('aria-busy');}
        } }, latestBacktest ? 'Run another replay' : 'Run historical replay'))));
      host.appendChild(results);
      host.appendChild(history); paintHistory();
      if (latestBacktest && latestBacktest.backtestId) {
        API.get('/api/plans/' + planRef.plan.id + '/outcomes/backtests/' + latestBacktest.backtestId).then(function (report) {
          if (results.isConnected && !results.hasChildNodes()) renderPlanBacktestReport(results, report);
        }).catch(function () { /* summary remains in the comparison; rerun is available */ });
      }
    }
  }

  function renderFrozenPlanDecision(host, plan, decision, insideManage) {
    function label(value) { return String(value || '').replaceAll('_', ' ').toLowerCase(); }
    var traded = decision.action === 'TRADE';
    host.appendChild(alertBox(traded ? 'ok' : 'caution',
      traded ? 'Paper position opened from this Plan' : 'Cash was the decision', [
        traded ? 'The exact priced package and account snapshot are frozen beside the linked paper trade.'
          : 'Doing nothing is a first-class decision. The rejected package remains frozen for review against cash.',
        'Decision time: ' + (decision.quoteAsOf || decision.createdAt || 'captured by the server')
      ]));
    host.appendChild(el('div', { class: 'grid grid-4 plan-decision-facts' },
      stat('Action', traded ? 'TRADE' : 'CASH'),
      stat('Theoretical max loss', decision.maxLossCents == null ? '—' : fmtMoney(decision.maxLossCents)),
      stat('Chance of any profit', decision.pop == null ? '—' : fmtPct(decision.pop)),
      stat('Market EV after costs', decision.evMarketCents == null ? '—' : pnlSpan(decision.evMarketCents)),
      stat('Realized-vol scenario EV', decision.evHistvolCents == null ? '—' : pnlSpan(decision.evHistvolCents)),
      stat('Economic placement', label(decision.economicVerdict || 'UNAVAILABLE')),
      stat('Evidence', label(decision.evidenceProvenance || 'UNKNOWN')),
      stat('Buying power at decision', decision.buyingPowerCents == null ? '—' : fmtMoney(decision.buyingPowerCents))));
    if (decision.legs && decision.legs.length) host.appendChild(UI.expandable('Frozen listed contracts', function () {
      function exactPrice(value) { return value == null ? '—' : '$' + String(value); }
      return table(['Side', 'Contract', 'Bid', 'Ask', 'Fill'], decision.legs.map(function (leg) {
        var strike = leg.strikePrice == null ? '' : ' ' + exactPrice(leg.strikePrice);
        return el('tr', {}, el('td', {}, leg.action), el('td', {}, leg.type + strike + (leg.expiration ? ' · ' + leg.expiration : '')),
          el('td', {}, exactPrice(leg.bidPrice)), el('td', {}, exactPrice(leg.askPrice)),
          el('td', {}, exactPrice(leg.fillPrice)));
      }));
    }));
    if (!insideManage) host.appendChild(el('div', { class: 'plan-next-action' },
      el('div', {}, el('b', {}, 'Decision frozen'), el('p', { class: 'muted' },
        'Manage & Review now compares what happens with what this decision expected.')),
      el('button', { type: 'button', class: 'btn', onclick: async function () {
        var live = await visibleCommand(this, function () { return PlanStore.get(plan.id, true); },
          'This Plan could not be refreshed.');
        if (live) focusPlanFrom(this, live, 'MANAGE_REVIEW');
      } }, 'Open Manage & Review')));
  }

  async function planDecideStage(root, initialPlan, stage) {
    var planRef = { plan: initialPlan };
    var ui = PlanStore.ui(initialPlan.id);
    ui.decision = ui.decision || { qty: null, proposedNetCents: null, feesOverrideCents: null, note: '' };
    var state = ui.decision;
    var content = planOwnedStage(root, initialPlan, stage);
    var latest = await PlanStore.latestDecision(initialPlan.id, true);
    if (latest.decision && initialPlan.status !== 'ACTIVE') {
      renderFrozenPlanDecision(content, initialPlan, latest.decision);
      return;
    }
    var selected = latest.selected;
    if (!selected) {
      content.appendChild(UI.emptyState('Choose a structure before deciding',
        'Decide never invents or recaptures a package. Select exact contracts in Strategy first.',
        'Open Strategy', function () { focusPlanFrom(this, planRef.plan, 'STRATEGY'); }));
      return;
    }
    var selectedPackageKey = selected.id || JSON.stringify({ strategy: selected.strategy,
      qty: selected.qty, legs: selected.legs || [] });
    if (state.selectedPackageKey !== selectedPackageKey) {
      state.selectedPackageKey = selectedPackageKey;
      state.qty = selected.qty || 1;
      state.proposedNetCents = null;
      state.preview = null;
      state.acks = {};
    }
    content.appendChild(el('div', { class: 'card plan-decision-position' },
      UI.cardHeader('Exact package under decision', el('span', { class: 'badge badge-ok' }, 'LOCKED TO PLAN')),
      el('div', { class: 'chip-row' }, chip('Structure', selected.displayName || prettyStrategy(selected.strategy)),
        chip('Contracts', (selected.legs || []).length + ' legs'), chip('Plan intent', planIntentLabel(initialPlan.intent))),
      UI.expandable('Show the selected contracts and prior economics', function () { return candidateCard(selected, false, initialPlan.symbol); })));

    var qty = el('input', { type: 'number', min: '1', max: '100', value: state.qty, id: 'plan-decision-qty' });
    var limit = el('input', { type: 'number', step: '0.01', id: 'plan-decision-price',
      placeholder: 'Use executable package price', value: state.proposedNetCents == null ? '' : (state.proposedNetCents / 100).toFixed(2) });
    var fees = el('input', { type: 'number', step: '0.01', min: '0', id: 'plan-decision-fees',
      placeholder: 'Platform default', value: state.feesOverrideCents == null ? '' : (state.feesOverrideCents / 100).toFixed(2) });
    var note = el('textarea', { id: 'plan-decision-note', rows: '2', placeholder: 'Optional: what would make this decision right or wrong?' }, state.note || '');
    var review = el('div', { id: 'plan-decision-review', 'aria-live': 'polite' });
    function dollars(input, label, nonnegative) {
      if (!input.value) return null;
      var value = Number(input.value);
      if (!isFinite(value) || (nonnegative && value < 0)) throw new Error(label + ' is not a valid dollar amount.');
      return Math.round(value * 100);
    }
    function request() {
      state.qty = positiveInteger(qty.value, 'Quantity', 100);
      state.proposedNetCents = dollars(limit, 'Package price', false);
      state.feesOverrideCents = dollars(fees, 'Fees', true);
      state.note = note.value.trim();
      return { qty: state.qty, proposedNetCents: state.proposedNetCents,
        feesOverrideCents: state.feesOverrideCents, note: state.note };
    }
    content.appendChild(el('div', { class: 'card plan-decision-controls' }, UI.cardHeader('Price the decision now'),
      el('p', { class: 'muted' }, 'The server reprices the Plan’s contracts against the current book. A blank price freezes the executable package price shown by this review.'),
      el('div', { class: 'form-grid plan-decision-form' },
        el('div', { class: 'field' }, el('label', { for: 'plan-decision-qty' }, 'Quantity'), qty),
        el('div', { class: 'field' }, el('label', { for: 'plan-decision-price' }, 'Net price $ (+credit / −debit)'), limit)),
      UI.expandable('Fees and decision note', function () { return el('div', { class: 'form-grid' },
        el('div', { class: 'field' }, el('label', { for: 'plan-decision-fees' }, 'Fees per side $'), fees),
        el('div', { class: 'field' }, el('label', { for: 'plan-decision-note' }, 'Decision note'), note)); }),
      el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn', id: 'plan-review-order', onclick: async function () {
        this.disabled = true; review.innerHTML = ''; review.appendChild(UI.spinner('Repricing the exact package…'));
        try {
          var live = await PlanStore.get(planRef.plan.id, true); planRef.plan = live;
          state.preview = await PlanStore.previewDecision(live, request());
          state.proposedNetCents = state.preview.order.proposedNetCents;
          limit.value = (state.proposedNetCents / 100).toFixed(2);
          paintPreview();
        } catch (e) { review.innerHTML = ''; review.appendChild(alertBox('danger', 'Could not review this order', [e.message])); }
        finally { this.disabled = false; }
      } }, state.preview ? 'Refresh exact review' : 'Review exact order'))));
    content.appendChild(review);

    function paintPreview() {
      var result = state.preview;
      if (!result) return;
      var p = result.preview, economics = result.economics;
      review.innerHTML = '';
      review.appendChild(economicAssessmentBlock({ economics: economics, economicVerdict: economics && economics.verdict }));
      review.appendChild(verdictPanel(p, Learn.currentLevel() === 'beginner', true).node);
      var blocks = (p.blockReasons || []).concat(result.guardrails && result.guardrails.blockReasons || []);
      var warnings = (p.warnings || []).concat(result.guardrails && result.guardrails.warnings || []);
      if (blocks.length) {
        review.appendChild(alertBox('danger', 'This package cannot be opened', blocks));
        var execution = p.analytics && p.analytics.executionQuality;
        var executableNet = execution && execution.executableNetCents;
        if (executableNet != null && blocks.some(function (reason) { return /more favorable than the executable market/i.test(reason); })) {
          review.appendChild(el('div', { class: 'btn-row plan-stale-limit-recovery' },
            el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-use-executable', onclick: function () {
              state.proposedNetCents = executableNet;
              limit.value = (executableNet / 100).toFixed(2);
              var reviewButton = document.getElementById('plan-review-order');
              if (reviewButton) reviewButton.click();
            } }, 'Use current executable ' + fmtMoney(executableNet, { plus: true }) + ' & review')));
        }
      }
      if (warnings.length) review.appendChild(alertBox('caution', 'Review these conditions', warnings));
      review.appendChild(el('div', { class: 'grid grid-4 plan-decision-math' },
        stat('Cost / credit', fmtMoney(p.entryNetPremiumCents, { plus: true })),
        stat('Theoretical max loss', el('span', { class: 'loss' }, fmtMoney(p.maxLossCents))),
        stat('Theoretical max profit', UI.maxProfitLabel(selected.strategy, selected.structureGroup,
          p.maxProfitCents, Learn.currentLevel() === 'beginner', p.legs)),
        stat('Chance of any profit', fmtPct(p.popEntry)),
        stat('Market EV after costs', economics && economics.marketEvAfterCostsCents != null ? pnlSpan(economics.marketEvAfterCostsCents) : '—'),
        stat('Realized-vol scenario EV', economics && economics.realizedVolEvAfterCostsCents != null ? pnlSpan(economics.realizedVolEvAfterCostsCents) : '—'),
        stat('Buying power after', fmtMoney(p.buyingPowerAfterCents)),
        stat('Opening fees', fmtMoney(p.feesOpenCents))));
      if (window.Scenario && selected.legs && selected.legs.length) review.appendChild(UI.expandable(
        'Realistic calm, up, down and choppy outcomes', function () {
          return Scenario.realisticOutcomes(initialPlan.symbol,
            Object.assign({}, selected, { qty: state.qty, entryNetPremiumCents: p.entryNetPremiumCents }));
        }));
      var required = result.requiredAcks || [];
      state.acks = {};
      var trade = el('button', { type: 'button', class: 'btn', id: 'plan-place-trade', disabled: 'disabled' }, 'Open paper position');
      function refresh() {
        var complete = required.every(function (ack) { return state.acks[ack.id]; });
        trade.disabled = !p.ok || !complete;
      }
      if (required.length) review.appendChild(el('div', { class: 'card card-slim ack-gate' },
        UI.cardHeader('Acknowledge material risks'), required.map(function (ack) {
          var box = el('input', { type: 'checkbox', id: 'plan-' + ack.id, onchange: function () { state.acks[ack.id] = box.checked; refresh(); } });
          return el('label', { class: 'ack-row', for: 'plan-' + ack.id }, box, el('span', {}, ack.label));
        })));
      trade.onclick = async function () {
        trade.disabled = true;
        try {
          var live = await PlanStore.get(planRef.plan.id, true);
          var req = request(); req.ackToken = result.ackToken;
          req.acknowledgedRisks = required.filter(function (ack) { return state.acks[ack.id]; }).map(function (ack) { return ack.id; });
          var out = await PlanStore.tradeDecision(live, req);
          state.preview = null;
          await PlanStore.focus(out.plan, 'MANAGE_REVIEW');
        } catch (e) { trade.disabled = false; review.appendChild(alertBox('danger', 'The position was not opened', [e.message])); }
      };
      var cash = el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-stay-cash', onclick: function () {
        UI.confirmModal('Stay in cash for this Plan?', el('p', {},
          'StrikeBench will freeze this package and its current odds as the rejected alternative, then review cash against it.'),
        'Choose cash', async function () {
          try {
            var live = await PlanStore.get(planRef.plan.id, true);
            var out = await PlanStore.cashDecision(live, request());
            state.preview = null;
            await PlanStore.focus(out.plan, 'MANAGE_REVIEW');
          } catch (e) { UI.toast(e.message, 'error'); }
        });
      } }, 'Stay in cash');
      review.appendChild(el('div', { class: 'plan-decision-actions' },
        el('div', {}, el('b', {}, 'Make the decision'), el('p', { class: 'muted' },
          'Trade and cash both preserve this exact comparison for later review.')),
        el('div', { class: 'btn-row' }, trade, cash)));
      refresh();
    }
    if (state.preview) paintPreview();
  }

  function planManagementTimeline(host, management, includeReviews) {
    var actions = management && management.actions || [];
    if (actions.length) host.appendChild(el('section', { class: 'card plan-management-timeline' },
      UI.cardHeader('Plan timeline'),
      el('div', { class: 'plan-timeline-list' }, actions.map(function (action) {
        var value = action.realizedCents != null ? pnlSpan(action.realizedCents)
          : action.unrealizedCents != null ? pnlSpan(action.unrealizedCents) : null;
        return el('div', { class: 'status-item' },
          el('span', { class: 'badge ' + (action.kind === 'CLOSE' || action.kind === 'SETTLE' ? 'badge-ok' : 'badge-dim') }, action.kind),
          el('span', { class: 'plan-timeline-note' }, action.note || 'Plan action'),
          el('span', { class: 'plan-timeline-value' }, value),
          el('span', { class: 'muted plan-timeline-date' }, UI.fmtDate(action.at)));
      }))));
    var reviews = includeReviews === false ? [] : management && management.reviews || [];
    if (reviews.length) host.appendChild(el('section', { class: 'card plan-review-results' },
      UI.cardHeader('Decision review'),
      el('p', { class: 'muted' }, 'Plan reviews stay separate from recommendation calibration unless the underlying trade carries observed or broker evidence.'),
      table(['Lane', 'Benchmark', 'Predicted', 'Result'], reviews.map(function (review) {
        return el('tr', {},
          el('td', {}, String(review.category || '').replaceAll('_', ' ').toLowerCase()),
          el('td', {}, String(review.benchmarkKind || '').replaceAll('_', ' ').toLowerCase()),
          el('td', {}, review.predictedPop == null ? '—' : fmtPct(review.predictedPop)),
          el('td', {}, review.realizedCents == null ? '—' : pnlSpan(review.realizedCents)));
      }))));
  }

  function planCashReview(host, plan, decision, management) {
    var created = new Date(decision.createdAt || decision.quoteAsOf);
    var horizon = Number(decision.reviewHorizonDays || 30);
    var due = new Date(created.getTime()); due.setUTCDate(due.getUTCDate() + horizon);
    var dueNow = Date.now() >= due.getTime();
    host.appendChild(alertBox('caution', 'Cash was an active decision', [
      'The rejected strategy, its modeled odds, and its exact quoted package remain frozen.',
      'Review horizon: ' + horizon + ' days · ' + UI.fmtDate(due.toISOString())
    ]));
    host.appendChild(el('div', { class: 'grid grid-3 plan-cash-benchmarks' },
      stat('Cash', '$0 market P/L', 'Cash is the zero-risk benchmark before any interest assumption.'),
      stat('Stock from decision', dueNow ? 'Ready for review' : 'Pending',
        'Buy-and-hold is measured from the underlying price frozen at Decide to the review-horizon close.'),
      stat('Rejected strategy', dueNow ? 'Ready for review' : 'Pending',
        'The exact rejected package is judged separately; it never enters trade calibration.')));
    if (!dueNow) host.appendChild(el('div', { class: 'plan-next-action' },
      el('div', {}, el('b', {}, 'Opportunity review is scheduled'), el('p', { class: 'muted' },
        'Until ' + UI.fmtDate(due.toISOString()) + ', no outcome is claimed. You can still inspect the frozen decision below.'))));
    if (dueNow && !(management && management.reviews && management.reviews.length)) {
      host.appendChild(el('div', { class: 'plan-next-action' },
        el('div', {}, el('b', {}, 'The review horizon has arrived'), el('p', { class: 'muted' },
          'Record cash, risk-matched stock, and the frozen-IV rejected-package counterfactual as separate benchmarks.')),
        el('button', { type: 'button', class: 'btn', id: 'plan-run-cash-review', onclick: async function () {
          this.disabled = true;
          try { await PlanStore.reviewCash(await PlanStore.get(plan.id, true)); App.render(); }
          catch (e) { this.disabled = false; UI.toast(e.message, 'error'); }
        } }, 'Record opportunity review')));
    }
    if (management && management.reviews && management.reviews.length) {
      host.appendChild(el('section', { class: 'card' }, UI.cardHeader('Recorded opportunity review'),
        table(['Benchmark', 'Start', 'End', 'Result'], management.reviews.map(function (review) {
          return el('tr', {}, el('td', {}, review.benchmarkKind),
            el('td', {}, review.benchmarkStartCents == null ? '—' : fmtMoney(review.benchmarkStartCents)),
            el('td', {}, review.benchmarkEndCents == null ? '—' : fmtMoney(review.benchmarkEndCents)),
            el('td', {}, review.realizedCents == null ? '—' : pnlSpan(review.realizedCents)));
        }))));
    }
    renderFrozenPlanDecision(host, plan, decision, true);
  }

  async function planManageStage(root, plan, stage) {
    var content = planOwnedStage(root, plan, stage);
    var data;
    try { data = await PlanStore.latestManagement(plan.id, true); }
    catch (e) {
      content.appendChild(alertBox('danger', 'Manage & Review could not load', [e.message]));
      return;
    }
    var decision = data.decision;
    if (!decision) {
      var rehearsalDoc = null;
      try { rehearsalDoc = await PlanStore.rehearsals(plan.id, true); } catch (e2) { /* management remains usable */ }
      var rehearsals = rehearsalDoc && rehearsalDoc.rehearsals || [];
      if (rehearsals.length) {
        content.appendChild(el('section', { class: 'card plan-rehearsal-review' },
          UI.cardHeader('Management rehearsals'),
          el('p', { class: 'muted' },
            'These are exact paths selected from this Plan’s stored ensemble. They practice decisions; they do not add probability evidence.'),
          rehearsals.map(function (item) {
            return el('div', { class: 'status-item' },
              el('span', { class: 'badge ' + (item.status === 'FINISHED' ? 'badge-dim' : 'badge-info') }, item.status),
              el('span', {}, el('b', {}, item.selection.toLowerCase() + ' path ' + (item.pathIndex + 1)),
                el('span', { class: 'muted small' }, ' · receipt ' + item.fingerprint.slice(0, 12) + '…')),
              el('span', { class: 'spacer' }),
              item.status !== 'FINISHED' ? el('button', { type: 'button', class: 'btn btn-sm', onclick: async function () {
                App.state.focusSimControlRoom = item.worldId;
                try { await App.switchWorld(item.worldId, this); App.navigate('#/data/simulation'); }
                catch (e) { UI.toast(e.message, 'error'); }
              } }, 'Open rehearsal') : null);
          })));
        planManagementTimeline(content, data.management);
        return;
      }
      content.appendChild(UI.emptyState('No decision has been frozen',
        'Manage & Review unlocks after this Plan records Trade, Cash, or an exact rehearsal.', 'Open Decide', function () {
          focusPlanFrom(this, plan, 'DECIDE');
        }));
      return;
    }
    if (decision.action === 'CASH') {
      planCashReview(content, plan, decision, data.management);
      planManagementTimeline(content, data.management, false);
      return;
    }
    if (!data.trade) {
      content.appendChild(alertBox('danger', 'The linked position is unavailable', [
        'The decision is preserved, but its trade record could not be loaded. No management action was attempted.'
      ]));
      renderFrozenPlanDecision(content, plan, decision, true);
      return;
    }
    content.appendChild(el('section', { class: 'card card-slim plan-frozen-expectation' },
      el('div', {}, el('b', {}, 'Frozen at Decide'), el('p', { class: 'muted' },
        'POP ' + (decision.pop == null ? '—' : fmtPct(decision.pop)) + ' · market EV '
          + (decision.evMarketCents == null ? '—' : fmtMoney(decision.evMarketCents, { plus: true }))
          + ' · ' + String(decision.economicVerdict || 'unavailable').replaceAll('_', ' ').toLowerCase())),
      el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
        var box = document.getElementById('plan-frozen-decision-detail');
        box.hidden = !box.hidden;
      } }, 'Inspect decision')));
    var frozen = el('div', { id: 'plan-frozen-decision-detail', hidden: '' });
    renderFrozenPlanDecision(frozen, plan, decision, true); content.appendChild(frozen);
    await window.ViewPortfolio.tradeDetail(content, [], { plan: plan, tradeId: decision.tradeId, data: data.trade });
    planManagementTimeline(content, data.management);
  }

  async function planWorkspace(root, params) {
    var id = params[0] || '';
    var rawStage = (params[1] || 'understand').split('?')[0];
    var plan = await PlanStore.get(id);
    var targetWorld = plan.marketKind === 'SIMULATED' ? plan.worldId : plan.marketKind === 'DEMO' ? 'demo' : 'observed';
    if (App.state.world !== targetWorld) { await PlanStore.focus(plan, rawStage); return; }
    App.state.activePlanByMarket = App.state.activePlanByMarket || {};
    App.state.activePlanByMarket[PlanStore.marketKey(plan)] = plan.id;
    App.state.activePlanId = plan.id;
    if (window.Workspace) Workspace.save();
    PlanStore.renderBar();
    var stage = planStageByPath(rawStage);
    root.appendChild(planHeader(plan, false));
    root.appendChild(planRail(plan, stage, false));
    root.appendChild(planContextEditor(plan));
    if (stage.key === 'UNDERSTAND' || stage.key === 'EVIDENCE') {
      var owned = planOwnedStage(root, plan, stage);
      if (stage.key === 'UNDERSTAND') owned.appendChild(el('div', {
        class: 'plan-scope-strip', id: 'plan-understand-scope', 'aria-label': 'Saved Plan focus'
      }, el('span', { class: 'eyebrow' }, 'SAVED PLAN FOCUS'),
      el('b', {}, plan.symbol + ' · ' + (plan.intent ? planIntentLabel(plan.intent) : 'Goal not chosen')),
      el('span', { class: 'muted' }, (plan.context && plan.context.horizonDays ? plan.context.horizonDays : 21)
        + ' trading sessions · ' + planMarketLabel(plan))));
      await research(owned, ['__plan', plan.symbol, stage.path], { plan: plan, stage: stage.path });
      if (stage.key === 'UNDERSTAND') appendPlanStageNext(owned, plan, 'Test the view',
        'Use conditional history and possible futures before choosing a structure.',
        'EVIDENCE', 'Continue to Evidence');
      else appendPlanStageNext(owned, plan, 'Choose how to express the view',
        'Compare structures, shape exact contracts, inspect the chain, or Scout related Plans.',
        'STRATEGY', 'Continue to Strategy');
    } else if (stage.key === 'STRATEGY') {
      await planStrategyStage(root, plan, stage);
    } else if (stage.key === 'OUTCOMES') {
      await planOutcomesStage(root, plan, stage);
    } else if (stage.key === 'DECIDE') {
      await planDecideStage(root, plan, stage);
    } else if (stage.key === 'MANAGE_REVIEW') {
      await planManageStage(root, plan, stage);
    } else transitionalPlanStage(root, plan, stage);
  }

  async function renderPlanLibrary(host, options) {
    options = options || {};
    var full = options.full === true;
    host.innerHTML = '';
    var countLabel = el('span', { class: 'muted small plan-library-count' }, 'Loading…');
    host.appendChild(UI.cardHeader(options.title || 'Plans',
      el('div', { class: 'btn-row plan-library-commands' },
        countLabel,
        !full ? el('a', { class: 'btn btn-sm btn-secondary', href: '#/plans' }, 'View all') : null,
        el('button', { type: 'button', class: 'btn btn-sm', onclick: function () {
          App.navigate('#/research');
        } }, '+ New Plan'))));
    var loading = UI.spinner('Loading your Plans…');
    host.appendChild(loading);

    var plans;
    try { plans = await PlanStore.library(true); }
    catch (e) {
      loading.remove();
      host.appendChild(alertBox('warn', 'Plan library unavailable', [e.message]));
      return;
    }
    loading.remove();
    var currentKey = PlanStore.currentMarketKey();
    var working = plans.filter(function (p) { return p.status !== 'ARCHIVED' && p.open !== false; });
    var closedTabs = plans.filter(function (p) { return p.status !== 'ARCHIVED' && p.open === false; });
    var archived = plans.filter(function (p) { return p.status === 'ARCHIVED'; });
    var current = working.filter(function (p) { return PlanStore.marketKey(p) === currentKey; });
    var elsewhere = working.filter(function (p) { return PlanStore.marketKey(p) !== currentKey; });
    if (!working.length && !archived.length && !closedTabs.length) {
      if (options.removeWhenEmpty) { host.remove(); return; }
      host.appendChild(UI.emptyState('No working Plans yet',
        'Choose a stock in Research, then carry one Plan through evidence, strategy, outcomes, and a decision.',
        'Open Research', function () { App.navigate('#/research'); }));
      return;
    }

    var quoteBySymbol = {};
    var portfolioByPlan = {};
    var sessionById = {};
    try {
      var symbols = Array.from(new Set(current.map(function (p) { return p.symbol; })));
      var fills = await Promise.all([
        symbols.length ? API.get('/api/quotes?symbols=' + symbols.join(',')) : Promise.resolve({ quotes: [] }),
        API.get('/api/plans/portfolio'),
        plans.some(function (p) { return p.marketKind === 'SIMULATED'; })
          ? API.get('/api/sim/market') : Promise.resolve({ sessions: [] })
      ]);
      (fills[0].quotes || []).forEach(function (q) { quoteBySymbol[q.symbol] = q; });
      (fills[1].plans || []).forEach(function (row) { portfolioByPlan[row.plan.id] = row; });
      (fills[2].sessions || []).forEach(function (session) { sessionById[session.id] = session; });
    } catch (e2) { /* Navigation remains available without decorative marks. */ }

    function stageName(plan) {
      return String(plan.furthestStage || 'UNDERSTAND').replaceAll('_', ' ').toLowerCase();
    }

    function planTile(plan) {
      var sameMarket = PlanStore.marketKey(plan) === currentKey;
      var terminalSession = plan.marketKind === 'SIMULATED' && sessionById[plan.worldId]
        && sessionById[plan.worldId].status === 'FINISHED';
      var row = portfolioByPlan[plan.id] || {};
      var decision = row.decision || {};
      var mark = row.mark;
      var quote = sameMarket ? quoteBySymbol[plan.symbol] : null;
      var live = mark && mark.decisionUnrealizedCents != null
        ? el('span', { class: 'home-plan-live' }, 'Now ', pnlSpan(mark.decisionUnrealizedCents))
        : quote ? el('span', { class: 'home-plan-live' }, fmtNum(quote.last), ' ', UI.delta(quote.last, quote.prevClose))
        : el('span', { class: 'muted small' }, terminalSession ? 'Session finished · review its report'
          : sameMarket ? 'Market mark unavailable' : 'Switches market when opened');
      var planIdentity = PlanStore.identity(plan, working);
      var actions = el('div', { class: 'home-plan-actions' });
      actions.appendChild(el('button', { type: 'button', class: 'btn btn-sm', onclick: function () {
        if (terminalSession) {
          App.state.focusSimControlRoom = plan.worldId;
          App.navigate('#/data/simulation');
          return;
        }
        focusPlanFrom(this, plan, plan.furthestStage);
      } }, terminalSession ? 'Review session' : sameMarket
        ? (plan.open === false ? 'Open Plan' : row.tradeId ? 'Manage Plan'
          : plan.status === 'DECIDED_CASH' || plan.status === 'CLOSED' ? 'Review Plan' : 'Resume Plan')
        : 'Switch market & open'));
      if (plan.status !== 'POSITION_OPEN') actions.appendChild(el('button', { type: 'button',
        class: 'btn btn-sm btn-secondary', 'aria-label': 'Archive ' + plan.title,
        onclick: function () { confirmArchivePlan(plan, false); } }, icon('archive', 15), ' Archive'));
      if (plan.assumptionsEditable === true) actions.appendChild(el('button', { type: 'button',
        class: 'btn btn-sm btn-secondary', 'aria-label': 'Delete draft ' + plan.title,
        onclick: function () { confirmDeletePlan(plan, false); } }, icon('trash', 15), ' Delete'));
      var actionLabel = decision.action === 'CASH' ? 'Cash decision'
        : row.tradeId ? 'Position open' : plan.status === 'CLOSED' ? 'Reviewed' : 'Working';
      return el('article', { class: 'home-plan-tile' + (plan.id === App.state.activePlanId ? ' active' : ''),
        'data-plan-id': plan.id },
        el('div', { class: 'home-plan-tile-head' },
          el('div', {}, el('div', { class: 'eyebrow' }, plan.symbol + ' · ' + planIntentLabel(plan.intent)),
            el('h3', {}, planIdentity.title)),
          el('div', { class: 'home-plan-badges' },
            planIdentity.duplicate ? el('span', { class: 'badge badge-info plan-duplicate-badge' }, planIdentity.duplicate) : null,
            full ? el('span', { class: 'badge ' + (row.tradeId ? 'badge-ok' : decision.action === 'CASH' ? 'badge-caution' : 'badge-dim') }, actionLabel) : null,
            el('span', { class: 'badge ' + (sameMarket ? 'badge-info' : 'badge-dim') }, planMarketLabel(plan)))),
        el('div', { class: 'home-plan-meta' },
          chip('Stage', stageName(plan)),
          plan.context && plan.context.horizonDays ? chip('Horizon', plan.context.horizonDays + ' sessions') : null,
          plan.context && plan.context.thesis ? chip('View', plan.context.thesis) : null,
          full && decision.economicVerdict ? chip('Decision', String(decision.economicVerdict).toLowerCase()) : null,
          full && decision.pop != null ? chip('POP at decision', fmtPct(decision.pop)) : null,
          planIdentity.updated ? el('span', { class: 'muted small plan-updated-at' }, planIdentity.updated) : null,
          live), actions);
    }

    function group(title, rows, note) {
      if (!rows.length) return null;
      var limit = full ? 8 : 3;
      var ordered = rows.slice().sort(function (a, b) {
        if (a.id === App.state.activePlanId) return -1;
        if (b.id === App.state.activePlanId) return 1;
        return String(b.updatedAt || b.createdAt || '').localeCompare(String(a.updatedAt || a.createdAt || ''));
      });
      var expanded = false;
      var grid = el('div', { class: 'home-plan-grid' });
      var count = el('span', { class: 'muted small home-plan-group-count' });
      var toggle = ordered.length > limit ? el('button', { type: 'button',
        class: 'btn btn-sm btn-secondary home-plan-group-toggle', 'aria-expanded': 'false' }) : null;
      function paint() {
        var shown = expanded ? ordered : ordered.slice(0, limit);
        grid.replaceChildren.apply(grid, shown.map(planTile));
        count.textContent = expanded || ordered.length <= limit
          ? ordered.length + (ordered.length === 1 ? ' Plan' : ' Plans')
          : limit + ' of ' + ordered.length + ' Plans';
        if (toggle) {
          toggle.textContent = expanded ? 'Show fewer' : 'Show all ' + ordered.length;
          toggle.setAttribute('aria-expanded', String(expanded));
        }
      }
      if (toggle) toggle.onclick = function () { expanded = !expanded; paint(); };
      var heading = el('div', { class: 'home-plan-group-title' },
        el('div', {}, el('h3', {}, title), note ? el('span', { class: 'muted small' }, note) : null), count);
      paint();
      return el('section', { class: 'home-plan-group', 'data-plan-group': title.toLowerCase().replaceAll(' ', '-') },
        el('div', { class: 'home-plan-group-head' }, heading, toggle), grid);
    }

    countLabel.textContent = working.length + ' working ' + (working.length === 1 ? 'Plan' : 'Plans');
    var here = group('In this market', current, 'These Plans use the prices and account on screen now.');
    var there = group('Other markets', elsewhere, 'Opening one switches the full market first.');
    if (here) host.appendChild(here);
    if (there) host.appendChild(there);
    if (archived.length) host.appendChild(UI.expandable('Archived Plans (' + archived.length + ')', function () {
      return el('div', { class: 'home-plan-archive' }, archived.map(function (plan) {
        return el('div', { class: 'status-item' }, el('b', {}, plan.symbol), el('span', {}, plan.title),
          el('span', { class: 'badge badge-dim' }, planMarketLabel(plan)),
          el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
            focusPlanFrom(this, plan, plan.furthestStage);
          } }, 'Review'));
      }));
    }));
    if (closedTabs.length) host.appendChild(UI.expandable('Closed Plan tabs (' + closedTabs.length + ')', function () {
      return el('div', { class: 'home-plan-archive home-plan-closed-tabs' }, closedTabs.map(function (plan) {
        var actions = el('div', { class: 'btn-row' },
          el('button', { type: 'button', class: 'btn btn-sm', onclick: function () {
            focusPlanFrom(this, plan, plan.furthestStage);
          } }, PlanStore.marketKey(plan) === currentKey ? 'Reopen' : 'Switch market & reopen'));
        if (plan.assumptionsEditable === true) actions.appendChild(el('button', { type: 'button',
          class: 'btn btn-sm btn-secondary', onclick: function () { confirmDeletePlan(plan, false); } },
        icon('trash', 14), ' Delete draft'));
        else if (plan.status !== 'POSITION_OPEN') actions.appendChild(el('button', { type: 'button',
          class: 'btn btn-sm btn-secondary', onclick: function () { confirmArchivePlan(plan, false); } },
        icon('archive', 14), ' Archive'));
        return el('div', { class: 'status-item' }, el('b', {}, plan.symbol), el('span', {}, plan.title),
          el('span', { class: 'badge badge-dim' }, planMarketLabel(plan)), actions);
      }));
    }));
  }

  async function plansHome(root) {
    try { await PlanStore.load(true); }
    catch (e) { /* The library renders the visible failure. */ }
    root.appendChild(el('div', { class: 'page-heading plan-library-heading' },
      el('div', {}, el('div', { class: 'eyebrow' }, 'YOUR WORK'), el('h1', {}, 'Plans'),
        el('p', { class: 'muted page-intro' },
          'One place for every saved market view, structure, outcome test, decision, and review.'))));
    var library = el('section', { class: 'card home-plan-library plan-library-page', id: 'plans-library' });
    root.appendChild(library);
    await renderPlanLibrary(library, { full: true, title: 'Plan library' });
  }

  window.ViewPlan = Object.freeze({
    stages: PLAN_STAGES,
    planWorkspace: planWorkspace,
    plansHome: plansHome,
    renderLibrary: renderPlanLibrary,
    planStartCard: planStartCard,
    outcomeWorkspace: planOutcomeWorkspace,
    intentLabel: planIntentLabel,
    marketLabel: planMarketLabel,
    confirmArchive: confirmArchivePlan,
    confirmDelete: confirmDeletePlan,
    guideBlock: guideBlock,
    economicVerdict: economicVerdict,
    marketEvAfterCosts: marketEvAfterCosts,
    economicAssessmentBlock: economicAssessmentBlock
  });
})();
