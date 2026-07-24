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
      pressable = S.pressable, startPlan = S.startPlan,
      planContextDeclared = S.planContextDeclared;
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
    }, { open: !!open, stateKey: 'strategy-guide-' + strategy });
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
    var economics = candidateEconomics(c);
    return economics ? economics.verdict : null;
  }

  // A1 (engine honesty): a candidate whose mechanical/economic gate FAILED ("cannot assess as a
  // trade" — the AMD credit spread that only debits at executable prices) is not a viable trade.
  // viable===false is the authoritative server signal (score.gatePassed()); missing/true = viable.
  function candidateViable(c) {
    return !(c && c.evaluation && c.evaluation.viable === false);
  }

  function candidateCoherence(c) {
    return c && c.evaluation && c.evaluation.assessment
      ? c.evaluation.assessment.coherence : null;
  }

  // Scan-level pill: only speak up when the structure disagrees with the declared view.
  // A coherent fit is the expected state and must not add noise to every card.
  function coherenceBadge(c) {
    var coherence = candidateCoherence(c);
    var v = coherence && coherence.verdict;
    if (v !== 'INCOHERENT' && v !== 'MIXED') return null;
    return el('span', {
      class: 'badge ' + (v === 'INCOHERENT' ? 'badge-danger' : 'badge-caution'),
      title: (coherence.reasons || []).join(' ')
    }, v === 'INCOHERENT' ? 'AGAINST YOUR VIEW' : 'PARTLY OFF-VIEW');
  }

  function coherenceBlock(c) {
    var coherence = candidateCoherence(c);
    var v = coherence && coherence.verdict;
    if (!v || v === 'UNDECLARED' || v === 'UNAVAILABLE') return null;
    var beginner = Learn.currentLevel() === 'beginner';
    var headline = v === 'COHERENT'
      ? (beginner ? 'Yes — it expresses the view you declared.' : 'Coherent with your declared view.')
      : v === 'MIXED'
        ? (beginner ? 'Partly — some of it works for your view, some against it.' : 'Mixed fit with your declared view.')
        : (beginner ? 'No — it works against the view you declared.' : 'Incoherent with your declared view.');
    var details = [coherence.directionAssessment, coherence.durationAssessment]
      .filter(function (line) { return line; });
    return el('section', {
      class: 'coherence-note coherence-' + v.toLowerCase(),
      'data-coherence-verdict': v
    },
      el('h4', {}, beginner ? 'Does this match your view?' : 'View coherence'),
      el('p', { class: 'coherence-headline' }, headline),
      details.length ? el('ul', { class: 'coherence-reasons' }, details.map(function (line) {
        return el('li', {}, line);
      })) : null,
      (coherence.reasons || []).length && !beginner
        ? el('p', { class: 'muted small' }, coherence.reasons.join(' ')) : null);
  }

  function planActionLabel(value) {
    return ({ ENTRY: 'Opened', MARK: 'Marked', ADJUST: 'Adjusted', ROLL: 'Rolled',
      PARTIAL_CLOSE: 'Partially closed', CLOSE: 'Closed', SETTLE: 'Settled', VOID: 'Voided',
      EXPIRATION: 'Expired', ASSIGNMENT: 'Assigned', EXERCISE: 'Exercised',
      REHEARSAL: 'Rehearsed' })[String(value || '').toUpperCase()] || 'Plan update';
  }

  function reviewLabel(value) {
    return ({ CASH_DECISION: 'Cash decision', TRADE_DECISION: 'Trade decision', SIM_REHEARSAL: 'Simulation rehearsal', CASH: 'Cash',
      STOCK: 'Shares', REJECTED_STRATEGY: 'Rejected position', PLAN_POSITION: 'Plan position' })[
      String(value || '').toUpperCase()] || 'Plan comparison';
  }

  function managementRuleLabel(value) {
    return ({ TAKE_PROFIT: 'Take profit', STOP_LOSS: 'Limit loss', TIME_EXIT: 'Time-based exit',
      ROLL: 'Consider rolling', ASSIGNMENT: 'Assignment plan', EXPIRATION: 'Expiration plan' })[
      String(value || '').toUpperCase()] || 'Management rule';
  }

  function candidateEconomics(c) {
    return c && c.evaluation && c.evaluation.assessment
      ? c.evaluation.assessment.economics : null;
  }

  function candidateFromEvaluation(item) {
    if (!item) return null;
    return item.evaluation && item.evaluation.candidate
      ? Object.assign({}, item.evaluation.candidate, { evaluation: item.evaluation }) : item;
  }

  function candidateDecisionScore(c) {
    return c && c.evaluation ? c.evaluation.decisionScore : null;
  }

  function candidatePop(c) {
    return c && c.evaluation && c.evaluation.risk ? c.evaluation.risk.pop : null;
  }

  function candidateContractLines(c, title) {
    var legs = c && c.legs || [];
    return el('section', { class: 'candidate-contract-lines', 'aria-label': title || 'Exact contracts' },
      el('div', { class: 'candidate-contract-lines-head' },
        el('span', { class: 'eyebrow' }, title || 'EXACT CONTRACTS'),
        el('span', { class: 'muted small' }, (c.qty || 1) + ' lot' + ((c.qty || 1) === 1 ? '' : 's'))),
      legs.length ? el('div', { class: 'candidate-contract-list' }, legs.map(function (leg) {
        return el('div', { class: 'candidate-contract-line' },
          el('span', { class: 'badge ' + (String(leg.action || '').toUpperCase() === 'BUY' ? 'badge-ok' : 'badge-caution') },
            String(leg.action || 'LEG').toUpperCase()),
          el('span', { class: 'mono' }, legLabel(leg)),
          Number(leg.ratio || 1) > 1 ? el('span', { class: 'muted small' }, 'ratio ' + leg.ratio) : null);
      })) : el('p', { class: 'muted small' }, 'No listed contracts are attached.'));
  }

  function candidatePositionSummary(c, title) {
    return el('div', { class: 'candidate-position-summary' },
      candidateContractLines(c, title),
      el('div', { class: 'candidate-position-summary-facts' },
        UI.fact(UI.vocabulary('theoreticalMaxLoss'), c.maxLossCents == null ? '—' : fmtMoney(c.maxLossCents), 'f-danger'),
        UI.fact(UI.vocabulary('theoreticalMaxProfit'), UI.maxProfitLabel(c.strategy, c.structureGroup,
          c.maxProfitCents, Learn.currentLevel() === 'beginner', c.legs),
          UI.maxProfitTone(c.strategy, c.structureGroup, c.maxProfitCents, c.legs)),
        UI.fact(UI.term('breakeven', 'Breakeven'), (c.breakevens || []).map(fmtBreakeven).join(' / ') || '—'),
        UI.fact('Chance of any profit', candidatePop(c) == null ? '—' : fmtPct(candidatePop(c)))));
  }

  function candidateFullAnalysis(c) {
    var beginner = Learn.currentLevel() === 'beginner';
    var analysis = c && c.evaluation || {};
    var block = el('div', { class: 'candidate-full-analysis' });
    var economics = economicAssessmentBlock(c, true);
    if (economics) block.appendChild(economics);
    var coherenceNote = coherenceBlock(c);
    if (coherenceNote) block.appendChild(coherenceNote);
    if (analysis.participation) block.appendChild(el('p', { class: 'participation-headline' },
      participationSentence(analysis.participation)));
    if (analysis.stance) block.appendChild(el('div', { class: 'chip-row stance-vector' },
      chip(UI.term('stancevector', 'Dollar delta'), fmtMoney(analysis.stance.dollarDeltaCents, { plus: true })),
      chip(UI.vocabulary('gamma', 'Dollar delta / 1% move'),
        fmtMoney(analysis.stance.gammaDollarDeltaCentsPerOnePercentMove, { plus: true })),
      chip(UI.vocabulary('vegaPerVolPoint', 'Vega / vol point'),
        fmtMoney(analysis.stance.vegaCentsPerVolPoint, { plus: true })),
      chip(UI.vocabulary('thetaPerDay'), fmtMoney(analysis.stance.thetaCentsPerDay, { plus: true }))));
    if (analysis.management && (analysis.management.rules || []).length) {
      block.appendChild(el('section', { class: 'candidate-management-receipt' },
        el('h4', {}, beginner ? 'How you would manage this trade' : 'Mechanical management plan'),
        analysis.management.summary ? el('p', {}, analysis.management.summary) : null,
        el('ul', { class: 'plan-rules' }, analysis.management.rules.map(function (rule) {
          return el('li', {}, el('b', {}, managementRuleLabel(rule.kind) + ': '),
            rule.trigger, ' → ', el('span', { class: 'plan-action' }, rule.action));
        }))));
    }
    var coverage = analysis.coverage || {}, inputs = coverage.inputs || {};
    if (Object.keys(inputs).length || (coverage.limitations || []).length) {
      block.appendChild(el('section', { class: 'candidate-full-coverage' }, el('h4', {}, 'Data coverage'),
        el('div', { class: 'evidence-grid' }, Object.keys(inputs).map(function (name) {
          var input = inputs[name] || {};
          return el('div', { class: 'evidence-row' }, el('span', { class: 'ev-dim' }, name),
            evaluationLevelBadge(input.level), el('span', { class: 'muted small' }, input.detail || ''));
        }).concat((coverage.limitations || []).map(function (limit) {
          return el('p', { class: 'muted small coverage-limit' }, limit);
        })))));
    }
    var score = analysis.score || {};
    if (!beginner && (score.components || []).length) {
      block.appendChild(el('section', {}, el('h4', {}, 'Decision score factors'),
        table(['Factor', 'Score', 'Weight', 'Why'], score.components.map(function (component) {
          return el('tr', {}, el('td', {}, component.name),
            el('td', {}, Math.round(Number(component.value || 0) * 100) + '%'),
            el('td', {}, Math.round(Number(component.weight || 0) * 100) + '%'),
            el('td', { class: 'muted' }, component.note || '—'));
        }))));
    }
    var explanation = analysis.explanation || {};
    var notes = (explanation.assumptions || []).concat(explanation.failureModes || []);
    if (notes.length) block.appendChild(el('section', {}, el('h4', {}, 'Assumptions and failure modes'),
      el('ul', {}, notes.map(function (item) { return el('li', {}, item); }))));
    if (c.warnings && c.warnings.length) block.appendChild(alertBox('warn', 'Before you decide', c.warnings));
    return block;
  }

  function economicRank(c) {
    var v = economicVerdict(c);
    return v === 'FAVORABLE' ? 3 : v === 'MIXED' ? 2 : v === 'UNAVAILABLE' ? 1 : 0;
  }

  function marketEvAfterCosts(c) {
    var economics = candidateEconomics(c);
    return economics && economics.marketEvAfterCostsCents;
  }

  function historyEvAfterCosts(c) {
    var economics = candidateEconomics(c);
    return economics && economics.realizedVolEvAfterCostsCents;
  }

  function economicAssessmentBlock(c, flat) {
    var e = candidateEconomics(c);
    if (!e) return null;
    var v = economicVerdict(c);
    var cls = v === 'FAVORABLE' ? 'economic-favorable'
      : v === 'UNFAVORABLE' ? 'economic-unfavorable'
      : v === 'UNAVAILABLE' ? 'economic-unavailable' : 'economic-mixed';
    var block = el('div', { class: 'economic-assessment ' + cls, 'data-economic-verdict': v },
      el('div', { class: 'economic-assessment-head' },
        el('span', { class: 'badge' }, e.label || UI.economicVerdictLabel(v))),
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
      var reasons = el('ul', { class: 'rationale' }, e.reasons.map(function (r) { return el('li', {}, r); }));
      block.appendChild(flat ? el('div', { class: 'economic-reasons-flat' }, el('b', {}, 'Why this classification'), reasons)
        : UI.expandable('Why this classification', function () { return reasons; },
          { stateKey: 'candidate-economics-' + (c.id || candidatePackageKey(c)) }));
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

  function managementReceipt(management, beginner, open, stateKey) {
    if (!management || !(management.rules || []).length) return null;
    return UI.expandable(beginner ? 'How you would manage this trade' : 'Mechanical management plan', function () {
      return el('div', { class: 'candidate-management-receipt' },
        management.summary ? el('p', {}, management.summary) : null,
        el('ul', { class: 'plan-rules' }, management.rules.map(function (rule) {
          return el('li', {}, el('b', {}, managementRuleLabel(rule.kind) + ': '),
            rule.trigger, ' → ', el('span', { class: 'plan-action' }, rule.action));
        })));
    }, { open: !!open, stateKey: stateKey ? 'candidate-management-' + stateKey : null });
  }

  function participationSentence(participation) {
    if (!participation) return null;
    var localCents = Math.round(Math.abs(Number(participation.localParticipationBps || 0)) / 100);
    var local = Number(participation.localParticipationBps || 0) < 0
      ? 'Right now, this position loses about ' + localCents + '\u00a2 when the equivalent shares gain $1.'
      : 'Right now, this position keeps about ' + localCents + '\u00a2 of each $1 gained by the equivalent shares.';
    if (participation.terminalUpsideCaptureBps === null || participation.terminalUpsideCaptureBps === undefined) {
      return local + ' Terminal capture needs path valuation because the expirations differ.';
    }
    var terminalCents = Math.round(Number(participation.terminalUpsideCaptureBps) / 100);
    var terminal = terminalCents < 0
      ? 'it loses about ' + Math.abs(terminalCents) + '\u00a2 for each $1 of upside'
      : 'it captures about ' + terminalCents + '\u00a2 per $1 of upside';
    return local + ' From ' + fmtMoney(participation.intervalStartCents) + ' to '
      + fmtMoney(participation.intervalEndCents) + ' by ' + (participation.terminalDate || 'expiry')
      + ', ' + terminal + '.';
  }

  function dataCoverageReceipt(coverage, stateKey) {
    coverage = coverage || {};
    var inputs = coverage.inputs || {};
    if (!Object.keys(inputs).length && !(coverage.limitations || []).length) return null;
    return UI.expandable('Data coverage for this analysis', function () {
      return el('div', { class: 'evidence-grid' }, Object.keys(inputs).map(function (name) {
        var input = inputs[name] || {};
        return el('div', { class: 'evidence-row' },
          el('span', { class: 'ev-dim' }, name), evaluationLevelBadge(input.level),
          el('span', { class: 'muted small' }, input.detail || ''));
      }).concat((coverage.limitations || []).map(function (limit) {
        return el('p', { class: 'muted small coverage-limit' }, limit);
      })));
    }, { stateKey: stateKey ? 'candidate-coverage-' + stateKey : null });
  }

  function portfolioImpactReceipt(impacts, beginner) {
    if (!impacts) return null;
    var lanes = [
      { key: 'practice', label: 'Practice', value: impacts.practice,
        empty: 'No Practice destination was selected for this analysis.' },
      { key: 'real', label: 'Recorded at broker', value: impacts.real,
        empty: 'No tracked account was selected for this analysis.' }
    ];
    if (impacts.real && !impacts.practice) lanes.reverse();
    var grid = el('div', { class: 'portfolio-impact-grid' });
    lanes.forEach(function (lane) {
      var value = lane.value;
      if (!value) {
        grid.appendChild(el('section', { class: 'portfolio-impact-lane unavailable', 'data-impact-lane': lane.key },
          el('b', {}, lane.label), el('p', { class: 'muted small' }, lane.empty)));
        return;
      }
      var before = value.symbolConcentrationBeforePct;
      var after = value.symbolConcentrationAfterPct;
      var body = el('section', { class: 'portfolio-impact-lane', 'data-impact-lane': lane.key },
        el('b', {}, lane.label + ' impact'),
        beginner
          ? el('p', {}, 'Net directional exposure changes from '
              + fmtMoney(value.netExposureBeforeCents, { plus: true }) + ' to '
              + fmtMoney(value.netExposureAfterCents, { plus: true }) + '. '
              + (before == null || after == null ? 'This starts a new concentration.'
                : 'Focused-symbol concentration moves from ' + Number(before).toFixed(1)
                  + '% to ' + Number(after).toFixed(1) + '%.'))
          : el('div', { class: 'grid grid-2 portfolio-impact-stats' },
              UI.stat(UI.term('stancevector', 'Gross delta before'),
                fmtMoney(value.grossExposureBeforeCents)),
              UI.stat(UI.term('stancevector', 'Gross delta after'),
                fmtMoney(value.grossExposureAfterCents)),
              UI.stat(UI.term('stancevector', 'Net delta before'),
                fmtMoney(value.netExposureBeforeCents, { plus: true })),
              UI.stat(UI.term('stancevector', 'Net delta after'),
                fmtMoney(value.netExposureAfterCents, { plus: true })),
              UI.stat(UI.vocabulary('symbolConcentration', 'Symbol concentration before'),
                before == null ? 'Empty book' : Number(before).toFixed(2) + '%'),
              UI.stat(UI.vocabulary('symbolConcentration', 'Symbol concentration after'),
                after == null ? 'Empty book' : Number(after).toFixed(2) + '%')),
        el('p', { class: 'muted small' }, value.basis));
      (value.concentrationChanges || []).forEach(function (change) {
        body.appendChild(el('p', { class: 'muted small' }, change));
      });
      grid.appendChild(body);
    });
    return el('section', { class: 'portfolio-impact-receipt', 'aria-label': 'Portfolio impact by lane' },
      el('h4', {}, 'Portfolio impact'), grid,
      (impacts.notes || []).map(function (note) { return el('p', { class: 'muted small' }, note); }));
  }

  function decisionMetricsReceipt(analysis, beginner, stateKey) {
    if (!analysis) return null;
    var participation = analysis.participation, stance = analysis.stance,
        implied = analysis.impliedStance, iv = analysis.ivContext,
        coverage = analysis.coverage || {}, capital = analysis.capital || {};
    var wrap = el('section', { class: 'decision-metrics-receipt', 'aria-label': 'Position behavior' },
      el('div', { class: 'eyebrow' }, beginner ? 'WHAT THIS POSITION ACTUALLY EXPRESSES' : 'POSITION STANCE'),
      participation ? el('p', { class: 'participation-headline' }, participationSentence(participation)) : null,
      implied ? el('p', { class: 'muted' }, implied.summary) : null);
    if (capital.economicCents != null || stance) wrap.appendChild(el('div', { class: 'chip-row decision-risk-strip' },
      capital.economicCents == null ? null
        : chip(UI.vocabulary('economicExposure'), fmtMoney(capital.economicCents)),
      !stance || stance.downsideLossTwoSigmaCents == null
        ? null : chip(el('span', {}, UI.vocabulary('scenarioLoss'), ' · down 2σ'),
          fmtMoney(stance.downsideLossTwoSigmaCents)),
      !stance || stance.upsideLossTwoSigmaCents == null
        ? null : chip(el('span', {}, UI.vocabulary('scenarioLoss'), ' · up 2σ'),
          fmtMoney(stance.upsideLossTwoSigmaCents))));
    if (iv && iv.entrySide === 'DEBIT') {
      if (iv.band === 'HIGH' || iv.band === 'VERY_HIGH') {
        wrap.appendChild(UI.actionFeedback('caution', 'Volatility is expensive for this debit', iv.message));
      } else {
        wrap.appendChild(el('p', { class: 'muted small' }, iv.message));
      }
    }
    if (beginner) {
      var regimes = participation && participation.regimePoints || [];
      if (regimes.length) wrap.appendChild(el('p', { class: 'muted small' },
        regimes.map(function (point) { return fmtMoney(point.priceCents) + ': ' + point.meaning; }).join('  ')));
      if (capital.annualizationNote) wrap.appendChild(el('p', { class: 'muted small' }, capital.annualizationNote));
      var beginnerCoverage = dataCoverageReceipt(coverage, stateKey);
      if (beginnerCoverage) wrap.appendChild(beginnerCoverage);
      var beginnerImpacts = analysis.assessment && analysis.assessment.portfolioImpacts;
      if (beginnerImpacts) wrap.appendChild(portfolioImpactReceipt(beginnerImpacts, true));
      return wrap;
    }
    if (stance) wrap.appendChild(el('div', { class: 'chip-row stance-vector' },
      chip(UI.term('stancevector', 'Dollar delta'), fmtMoney(stance.dollarDeltaCents, { plus: true })),
      chip(UI.vocabulary('gamma', 'Dollar delta / 1% move'),
        fmtMoney(stance.gammaDollarDeltaCentsPerOnePercentMove, { plus: true })),
      chip(UI.vocabulary('vegaPerVolPoint', 'Vega / vol point'),
        fmtMoney(stance.vegaCentsPerVolPoint, { plus: true })),
      chip(UI.vocabulary('thetaPerDay'), fmtMoney(stance.thetaCentsPerDay, { plus: true }))));
    if (participation) wrap.appendChild(UI.expandable('Participation definitions and regime points', function () {
      return el('div', {},
        el('p', {}, el('b', {}, 'Local: '), participation.localBasis),
        el('p', {}, el('b', {}, 'Terminal: '), participation.terminalBasis),
        (participation.regimePoints || []).length ? el('ul', {}, participation.regimePoints.map(function (point) {
          return el('li', {}, fmtMoney(point.priceCents) + ' \u2014 ' + point.meaning);
        })) : el('p', { class: 'muted' }, 'No strike changes the participation regime.'));
    }));
    var expertCoverage = dataCoverageReceipt(coverage, stateKey);
    if (expertCoverage) wrap.appendChild(expertCoverage);
    if (capital.annualizationNote) wrap.appendChild(el('p', { class: 'muted small' }, capital.annualizationNote));
    var impacts = analysis.assessment && analysis.assessment.portfolioImpacts;
    if (impacts) wrap.appendChild(portfolioImpactReceipt(impacts, false));
    return wrap;
  }

  function candidateEvaluationReceipt(c, beginner) {
    var analysis = c && c.evaluation;
    if (!analysis) return null;
    var stateKey = c.id || candidatePackageKey(c);
    var wrap = el('div', { class: 'candidate-evaluation-receipt' });
    var metrics = decisionMetricsReceipt(analysis, beginner, stateKey);
    if (metrics) wrap.appendChild(metrics);
    var management = managementReceipt(analysis.management, beginner, !!c.selected, stateKey);
    if (management) wrap.appendChild(management);
    if (beginner) {
      var beginnerFailures = analysis.explanation && analysis.explanation.failureModes || [];
      if (beginnerFailures.length) wrap.appendChild(UI.expandable('What could make this lose', function () {
        return el('ul', {}, beginnerFailures.map(function (failure) { return el('li', {}, failure); }));
      }, { stateKey: 'candidate-failure-modes-' + stateKey }));
      return wrap.hasChildNodes() ? wrap : null;
    }
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
        + Math.round(Number(candidateDecisionScore(c) || 0)) + '.'));
      return body;
    }, { stateKey: 'candidate-score-' + stateKey }));
    var explanation = analysis.explanation || {};
    if ((explanation.assumptions || []).length || (explanation.failureModes || []).length) {
      wrap.appendChild(UI.expandable('Assumptions and failure modes', function () {
        return el('div', {},
          (explanation.assumptions || []).length ? el('div', {}, el('b', {}, 'Assumptions'),
            el('ul', {}, explanation.assumptions.map(function (item) { return el('li', {}, item); }))) : null,
          (explanation.failureModes || []).length ? el('div', {}, el('b', {}, 'Failure modes'),
            el('ul', {}, explanation.failureModes.map(function (item) { return el('li', {}, item); }))) : null);
      }, { stateKey: 'candidate-assumptions-' + stateKey }));
    }
    return wrap.hasChildNodes() ? wrap : null;
  }

  function tradeIntent(value) {
    var intent = String(value || '').toUpperCase();
    return ['DIRECTIONAL', 'INCOME', 'HEDGE', 'ACQUIRE', 'EXIT'].indexOf(intent) >= 0
      ? intent : null;
  }

  async function openCandidateAsPlan(c, rawSymbol, options) {
    options = options || {};
    var symbol = String(rawSymbol || App.context.symbol('AAPL')).toUpperCase();
    var intent = tradeIntent(c && c.intent || App.context.goal());
    if (!intent) throw new Error('Choose what this idea should accomplish before creating a Plan.');
    var receipt = c && c._planContext || {};
    var horizon = Object.prototype.hasOwnProperty.call(options, 'horizon')
      ? options.horizon : (receipt.horizon || App.context.horizon(null));
    var evaluatedThesis = c && c.evaluation && c.evaluation.spec && c.evaluation.spec.thesis;
    var thesis = Object.prototype.hasOwnProperty.call(options, 'thesis')
      ? options.thesis : (receipt.thesis || evaluatedThesis || App.context.thesis(null));
    var candidateRisk = Object.prototype.hasOwnProperty.call(options, 'riskMode')
      ? options.riskMode : (receipt.riskMode
        || (App.state.headerRiskExplicit ? riskMode() : null));
    App.context.update({ symbol: symbol, goal: intent, horizon: horizon, thesis: thesis });
    var position = Object.assign({ symbol: symbol, strategy: c.strategy, qty: c.qty,
      legs: (c.legs || []).map(function (leg) { return {
        action: leg.action, type: leg.stock ? 'STOCK' : leg.type,
        strike: leg.stock || leg.type === 'STOCK' ? null : String(leg.strike),
        expiration: leg.stock || leg.type === 'STOCK' ? null : leg.expiration,
        ratio: leg.ratio, multiplier: leg.multiplier, positionEffect: leg.positionEffect,
        entryPrice: leg.entryPrice == null ? null : String(leg.entryPrice)
      }; }), thesis: thesis, horizon: horizon, riskMode: candidateRisk, intent: intent,
      useHeldShares: !!c.usesHeldShares, recommendationId: c.recommendationId || null,
      source: 'PLAN', fillNature: 'PROPOSED'
    }, options.position || {});
    return startPlan({ symbol: symbol, intent: intent, horizon: horizon, thesis: thesis,
      riskMode: candidateRisk },
      options.destination || 'STRATEGY', async function (plan) {
        var selected = await PlanStore.saveCustom(plan, position);
        var candidate = selected.strategy && selected.strategy.result && selected.strategy.result.candidate;
        if (!candidate || candidate.selected !== true) {
          throw new Error((selected.preview && selected.preview.blockReasons || []).join(' ')
            || 'This package was analyzed but could not become the Plan structure.');
        }
        var updated = selected.plan || plan;
        var ui = PlanStore.ui(updated.id);
        ui.strategyView = 'compare';
        ui.selectedCandidate = candidate;
        ui.strategyFocusCandidate = candidate.id;
        return updated;
      });
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
        : UI.fact(UI.vocabulary('theoreticalMaxLoss'), fmtMoney(c.maxLossCents), 'f-danger');
    var profitFact = UI.fact('Chance of any profit', fmtPct(candidatePop(c)));
    var assignmentFact = assignGoal && c.assignmentProb !== null && c.assignmentProb !== undefined
      ? UI.fact(c.intent === 'EXIT' ? 'Chance you sell' : 'Chance you buy', fmtPct(c.assignmentProb), 'f-ok') : null;
    var card = el('div', { class: 'candidate', 'data-strategy': c.strategy,
      'data-economic-verdict': economicVerdict(c) || 'UNKNOWN' },
      el('div', { class: 'head' },
        el('h3', {}, c.displayName),
        intentBadge(c.intent),
        heldSharesBadge(c),
        coherenceBadge(c),
        badge(c.freshness)),
      g.story ? el('div', { class: 'muted', style: 'margin:2px 0 4px' }, g.story) : null,
      intentNoteBlock(c),
      el('div', { class: 'fact-grid' },
        maxLossFact,
        UI.fact(UI.vocabulary('theoreticalMaxProfit', 'Best possible profit'), UI.maxProfitLabel(
          c.strategy, c.structureGroup, c.maxProfitCents, true, c.legs),
          UI.maxProfitTone(c.strategy, c.structureGroup, c.maxProfitCents, c.legs)),
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
    var coherenceNote = coherenceBlock(c);
    if (coherenceNote) card.insertBefore(coherenceNote, econ || card.querySelector('.fact-grid'));
    var gb = guideBlock(c.strategy);
    // Beginner's first disclosure must explain the structure itself. The economic verdict
    // remains prominent above the facts, while its deeper scoring rationale follows the
    // plain-language win/loss mechanics instead of displacing them.
    if (gb) card.insertBefore(gb, econ || card.children[2] || null);
    if (window.Scenario) card.appendChild(Scenario.realisticOutcomes(symbolForTicket || App.context.symbol(), c));
    var beginnerReceipt = candidateEvaluationReceipt(c, true);
    if (beginnerReceipt) card.appendChild(beginnerReceipt);
    card.appendChild(candidateContractLines(c, 'EXACT CONTRACTS'));
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
        coherenceBadge(c),
        badge(c.freshness),
        candidateDecisionScore(c) !== null && candidateDecisionScore(c) !== undefined
          ? UI.scoreBar(candidateDecisionScore(c), 'Decision score — the shared economic, risk and evidence ranking') : null),
      el('div', { class: 'label-line' }, c.label + '  ·  qty ' + c.qty),
      intentNoteBlock(c),
      el('div', { class: 'chip-row' },
        chip(UI.term('entrycashflow', 'Cost/credit'), fmtMoney(c.entryNetPremiumCents, { plus: true })),
        chip(UI.vocabulary('theoreticalMaxLoss'), el('span', { class: 'loss' }, fmtMoney(c.maxLossCents))),
        c.combinedMaxLossCents !== null && c.combinedMaxLossCents !== undefined
          ? chip(el('span', {}, UI.vocabulary('theoreticalMaxLoss'), ' with shares'),
            el('span', { class: 'loss' }, fmtMoney(c.combinedMaxLossCents))) : null,
        chip(UI.vocabulary('theoreticalMaxProfit'), UI.maxProfitLabel(
          c.strategy, c.structureGroup, c.maxProfitCents, false, c.legs)),
        chip(el('span', {}, 'POP', UI.info('pop')), fmtPct(candidatePop(c))),
        c.assignmentProb !== null && c.assignmentProb !== undefined
          ? chip(el('span', {}, 'Assignment', UI.info('assignment')), fmtPct(c.assignmentProb)) : null,
        c.annualizedYieldPct !== null && c.annualizedYieldPct !== undefined ? chip('Net premium yield/yr', fmtNum(c.annualizedYieldPct, 1) + '%') : null,
        c.effectivePrice ? chip(c.intent === 'ACQUIRE' ? 'Effective buy' : 'Effective sell', '$' + c.effectivePrice) : null,
        chip(UI.term('breakeven', 'Breakeven'), (c.breakevens || []).map(fmtBreakeven).join(' / ') || '—'),
        chip('Confidence', fmtPct(c.confidence))),
      explain(c.beginnerExplanation));
    var econ = economicAssessmentBlock(c);
    if (econ) card.insertBefore(econ, card.querySelector('.chip-row'));
    card.appendChild(el('div', { class: 'chip-row expert-only' },
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

  var NEAR_TIE_SCORE_POINTS = 2.0;

  function candidateRankReason(c) {
    var explanation = c && c.evaluation && c.evaluation.explanation || {};
    return explanation.whySelected || c.whyConsidered || explanation.headline
      || 'It ranks highest after the shared economic, risk, evidence, and cost checks.';
  }

  function candidatePayoffFigure(c, spot) {
    var risk = c && c.evaluation && c.evaluation.risk || {};
    var expirations = new Set((c.legs || []).filter(function (leg) {
      return !leg.stock && String(leg.type || '').toUpperCase() !== 'STOCK' && leg.expiration;
    }).map(function (leg) { return String(leg.expiration); }));
    var points = Number(spot) > 0 ? (risk.scenarios || []).map(function (point) {
      return { price: Number(spot) * (1 + Number(point.underlyingMovePct || 0)), profitCents: point.pnlCents };
    }) : [];
    var figure = el('figure', { class: 'ranked-idea-payoff' },
      el('figcaption', {}, el('b', {}, expirations.size > 1 ? 'Why there is no single payoff line' : 'Expiration payoff')));
    if (expirations.size > 1) {
      figure.appendChild(el('div', { class: 'ranked-time-spread-shape' }, strategyShape(c.strategy)));
      figure.appendChild(el('p', { class: 'muted' },
        'One option expires while another remains alive. A single terminal line would be misleading; Possible futures values both expiries along each path.'));
    } else if (points.length >= 2) {
      figure.appendChild(UI.payoffChart(points, { spot: Number(spot), breakevens: c.breakevens || [] }));
    } else {
      figure.appendChild(UI.emptyState('Payoff curve unavailable',
        'The ranking remains visible, but this package did not return enough defensible payoff points to draw a curve.'));
    }
    return figure;
  }

  function ideaPresentation(c, options) {
    options = options || {};
    var density = options.density || 'compact';
    var beginner = Learn.currentLevel() === 'beginner';
    var analysis = c && c.evaluation || {};
    var economics = candidateEconomics(c) || {};
    var verdict = economicVerdict(c) || 'UNAVAILABLE';
    var rank = Number(options.rank || 1);
    var article = el('article', {
      class: 'ranked-idea ranked-idea-' + density + (density === 'hero' ? ' candidate' : '')
        + (c.selected ? ' selected' : '')
        + (options.className ? ' ' + options.className : ''),
      'data-candidate-id': c.id || null,
      'data-symbol': c.symbol || null,
      'data-economic-verdict': verdict,
      'aria-label': 'Rank ' + rank + ': ' + (c.displayName || c.strategy)
    });
    var verdictClass = verdict === 'FAVORABLE' ? 'badge-ok'
      : verdict === 'UNFAVORABLE' ? 'badge-danger' : verdict === 'UNAVAILABLE' ? 'badge-dim' : 'badge-caution';
    article.appendChild(el('header', { class: 'ranked-idea-head' },
      el('div', {}, el('span', { class: 'eyebrow' }, options.kicker
        || (c.selected ? 'SELECTED STRUCTURE' : 'RANK ' + rank)),
        el('h2', {}, c.displayName || prettyStrategy(c.strategy)),
        c.label ? el('p', { class: 'muted mono' }, c.label) : null),
      el('div', { class: 'ranked-idea-badges' },
        c.selected ? el('span', { class: 'badge badge-ok' }, 'SELECTED') : null,
        intentBadge(c.intent), heldSharesBadge(c), coherenceBadge(c),
        analysis.evidence && analysis.evidence.rollup ? evaluationLevelBadge(analysis.evidence.rollup) : null,
        el('span', { class: 'badge ' + verdictClass }, economics.label || UI.economicVerdictLabel(verdict)))));

    var facts = el('div', { class: 'ranked-idea-facts' },
      chip(UI.vocabulary('theoreticalMaxLoss'), fmtMoney(c.maxLossCents)),
      chip(el('span', {}, 'Chance of any profit', UI.info('pop')),
        candidatePop(c) == null ? '\u2014' : fmtPct(candidatePop(c))),
      chip(el('span', {}, 'Market-implied EV', UI.info('ev')), economics.marketEvAfterCostsCents == null
        ? 'Unavailable' : pnlSpan(economics.marketEvAfterCostsCents)),
      chip(el('span', {}, 'Realized-vol scenario EV', UI.info('evhistvol')),
        economics.realizedVolEvAfterCostsCents == null
        ? 'Unavailable' : pnlSpan(economics.realizedVolEvAfterCostsCents)));
    if (density === 'hero') {
      facts.insertBefore(chip(
        UI.term('entrycashflow', c.entryNetPremiumCents < 0 ? 'You pay (debit)' : 'You collect (credit)'),
        el('span', { class: c.entryNetPremiumCents < 0 ? 'loss' : 'gain' },
          fmtMoney(Math.abs(c.entryNetPremiumCents)))), facts.firstChild);
      facts.insertBefore(chip(UI.vocabulary('theoreticalMaxProfit', beginner ? 'Best possible profit' : null),
        UI.maxProfitLabel(c.strategy, c.structureGroup, c.maxProfitCents, beginner, c.legs)), facts.children[2]);
    }

    if (density === 'row' || density === 'compact') {
      article.appendChild(el('p', { class: 'ranked-idea-summary' },
        (analysis.explanation && (analysis.explanation.headline || analysis.explanation.plainLanguage))
          || c.beginnerExplanation || candidateRankReason(c)));
      article.appendChild(facts);
      if (options.context) article.appendChild(options.context);
      if (options.action) article.appendChild(options.action);
      return article;
    }

    if (options.action) article.appendChild(options.action);

    var left = el('div', { class: 'ranked-idea-case' });
    var economic = economicAssessmentBlock(c, true);
    if (economic) left.appendChild(economic);
    var coherenceNote = coherenceBlock(c);
    if (coherenceNote) left.appendChild(coherenceNote);
    left.appendChild(el('section', { class: 'ranked-why-first' },
      el('h3', {}, c.selected ? 'Why this is your working structure' : 'Why this ranks first'),
      el('p', {}, candidateRankReason(c))));
    var guide = Learn.STRATEGY_GUIDE[c.strategy] || {};
    left.appendChild(el('div', { class: 'ranked-win-risk' },
      el('section', {}, el('h3', {}, 'How it can work'),
        el('p', {}, guide.win || c.bestUpside || (analysis.explanation && analysis.explanation.bestCase) || '\u2014')),
      el('section', {}, el('h3', {}, 'What can hurt'),
        el('p', {}, c.biggestRisk || (analysis.explanation && analysis.explanation.biggestRisk) || guide.lose || '\u2014'),
        el('p', { class: 'muted small' }, 'Reconsider if: ', c.wouldInvalidate
          || (analysis.explanation && analysis.explanation.wouldInvalidate) || '\u2014'))));
    left.appendChild(candidateContractLines(c, 'EXACT CONTRACTS'));

    var right = el('aside', { class: 'ranked-idea-visual' }, candidatePayoffFigure(c, options.spot), facts);
    if (window.Scenario) right.appendChild(Scenario.realisticOutcomes(options.symbol || c.symbol
      || App.context.symbol(), c, { autoRun: !!c.selected }));
    if (analysis.participation) right.appendChild(el('p', { class: 'participation-headline' },
      participationSentence(analysis.participation)));
    if (analysis.impliedStance && analysis.impliedStance.summary) {
      right.appendChild(el('p', { class: 'muted' }, analysis.impliedStance.summary));
    }
    if (!beginner && analysis.stance) right.appendChild(el('div', { class: 'chip-row stance-vector' },
      chip(UI.term('stancevector', 'Dollar delta'), fmtMoney(analysis.stance.dollarDeltaCents, { plus: true })),
      chip(UI.vocabulary('vegaPerVolPoint', 'Vega / vol point'),
        fmtMoney(analysis.stance.vegaCentsPerVolPoint, { plus: true })),
      chip(UI.vocabulary('thetaPerDay'), fmtMoney(analysis.stance.thetaCentsPerDay, { plus: true }))));

    article.appendChild(el('div', { class: 'ranked-idea-grid' }, left, right));
    if (c.warnings && c.warnings.length) article.appendChild(alertBox('warn', 'Before you decide', c.warnings));
    var secondary = el('div', { class: 'ranked-idea-secondary candidate-evaluation-receipt' });
    var management = managementReceipt(analysis.management, beginner, false, c.id || candidatePackageKey(c));
    if (management) secondary.appendChild(management);
    var coverage = dataCoverageReceipt(analysis.coverage, c.id || candidatePackageKey(c));
    if (coverage) secondary.appendChild(coverage);
    var impacts = analysis.assessment && analysis.assessment.portfolioImpacts;
    if (impacts) secondary.appendChild(portfolioImpactReceipt(impacts, beginner));
    if (!beginner && analysis.score && (analysis.score.components || []).length) {
      secondary.appendChild(UI.expandable('How the Decision score was built', function () {
        return table(['Factor', 'Score', 'Weight', 'Why'], analysis.score.components.map(function (component) {
          return el('tr', {}, el('td', {}, component.name),
            el('td', {}, Math.round(Number(component.value || 0) * 100) + '%'),
            el('td', {}, Math.round(Number(component.weight || 0) * 100) + '%'),
            el('td', { class: 'muted' }, component.note || '\u2014'));
        }));
      }, { stateKey: 'ranked-hero-score-' + (c.id || candidatePackageKey(c)) }));
    }
    if (secondary.hasChildNodes()) article.appendChild(secondary);
    return article;
  }

  function rankingSeparation(candidates) {
    if (!candidates || candidates.length < 2) {
      return el('p', { class: 'ranked-separation muted' }, 'Only one structure cleared the current screen.');
    }
    var first = candidates[0], second = candidates[1];
    var delta = Math.abs(Number(candidateDecisionScore(first) || 0) - Number(candidateDecisionScore(second) || 0));
    var close = candidatesNearTie(first, second);
    return el('p', { class: 'ranked-separation ' + (close ? 'close' : '') },
      el('b', {}, close ? 'Close call. ' : 'Ranking separation. '),
      close
        ? '#1 leads #2 by only ' + fmtNum(delta, 1) + ' Decision-score points; compare their risks, not just the rank.'
        : '#1 leads #2 by ' + fmtNum(delta, 1) + ' Decision-score points after economics, risk, evidence, and costs.',
      UI.info('ranktie'));
  }

  function candidatesNearTie(first, second) {
    if (!first || !second || economicVerdict(first) !== economicVerdict(second)) return false;
    return Math.abs(Number(candidateDecisionScore(first) || 0)
      - Number(candidateDecisionScore(second) || 0)) <= NEAR_TIE_SCORE_POINTS;
  }

  function strategyMeta(name) {
    return ((App.strategyCatalog && App.strategyCatalog.catalog) || []).find(function (m) { return m.name === name; }) || null;
  }

  /** Expert side-by-side strategy comparison — sortable columns, with one shared hero above. */
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
      { key: 'economicVerdict', label: 'Economic view', get: economicRank, render: function (c) { var v = economicVerdict(c), e = candidateEconomics(c); return el('span', { class: 'badge economic-table-' + String(v || 'unknown').toLowerCase() }, (e && e.label) || UI.economicVerdictLabel(v)); } },
      { key: 'displayName', label: 'Strategy', get: function (c) { return c.displayName; }, render: function (c) { return el('b', {}, c.displayName); } },
      { key: 'entryNetPremiumCents', label: 'Cost/Credit', infoKey: 'entrycashflow', get: function (c) { return c.entryNetPremiumCents; }, render: function (c) { return pnlSpan(c.entryNetPremiumCents); } },
      { key: 'maxLossCents', label: 'Theor. max loss', infoKey: 'theoreticalmaxloss', get: function (c) { return c.usesHeldShares && c.combinedMaxLossCents ? c.combinedMaxLossCents : c.maxLossCents; }, render: function (c) { return c.usesHeldShares && c.maxLossCents === 0 ? el('span', {}, '$0*') : el('span', { class: 'loss' }, fmtMoney(c.maxLossCents)); } },
      { key: 'maxProfitCents', label: 'Best possible profit', infoKey: 'maxprofit', get: function (c) { var k = UI.profitCeilingKind(c.strategy, c.structureGroup, c.maxProfitCents, c.legs); return k === 'uncapped' ? Infinity : k === 'model-dependent' ? -Infinity : c.maxProfitCents; }, render: function (c) { var k = UI.profitCeilingKind(c.strategy, c.structureGroup, c.maxProfitCents, c.legs); return el('span', { class: (k === 'uncapped' || (k === 'finite' && c.maxProfitCents > 0)) ? 'gain' : (k === 'finite' && c.maxProfitCents <= 0) ? 'loss' : 'muted' }, UI.maxProfitLabel(c.strategy, c.structureGroup, c.maxProfitCents, Learn.currentLevel() === 'beginner', c.legs)); } },
      { key: 'rr', label: 'R:R', get: rrValue, render: function (c) { var v = rrValue(c); return el('span', {}, v === -1 ? '\u2014' : v === Infinity ? '\u221E' : fmtNum(v, 2)); } },
      { key: 'pop', label: 'POP', infoKey: 'pop', get: function (c) { var v = candidatePop(c); return v === null || v === undefined ? -1 : v; }, render: function (c) { return el('span', {}, fmtPct(candidatePop(c))); } },
      { key: 'marketEv', label: 'Market EV', infoKey: 'ev', get: function (c) { var v = marketEvAfterCosts(c); return v === null || v === undefined ? -Infinity : v; }, render: function (c) { var v = marketEvAfterCosts(c); return v !== null && v !== undefined ? pnlSpan(v) : '—'; } },
      { key: 'historyEv', label: 'History EV', infoKey: 'evhistvol', get: function (c) { var v = historyEvAfterCosts(c); return v === null || v === undefined ? -Infinity : v; }, render: function (c) { var v = historyEvAfterCosts(c); return v === null || v === undefined ? '—' : pnlSpan(v); } },
      { key: 'breakevens', label: 'Breakevens', infoKey: 'breakeven', get: function (c) { return (c.breakevens || []).length ? parseFloat(c.breakevens[0]) : 0; }, render: function (c) { return el('span', { class: 'mono' }, (c.breakevens || []).map(fmtBreakeven).join(' / ') || '\u2014'); } },
      { key: 'assignmentProb', label: 'Assign%', infoKey: 'assignment', get: function (c) { return c.assignmentProb === null || c.assignmentProb === undefined ? -1 : c.assignmentProb; }, render: function (c) { return el('span', {}, c.assignmentProb === null || c.assignmentProb === undefined ? '\u2014' : fmtPct(c.assignmentProb)); } },
      { key: 'annualizedYieldPct', label: 'Net premium yield/yr', infoKey: 'filteryield', get: function (c) { return c.annualizedYieldPct === null || c.annualizedYieldPct === undefined ? -1 : c.annualizedYieldPct; }, render: function (c) { return el('span', {}, c.annualizedYieldPct === null || c.annualizedYieldPct === undefined ? '\u2014' : fmtNum(c.annualizedYieldPct, 1) + '%'); } },
      { key: 'liquidityScore', label: 'Liq', get: function (c) { return c.liquidityScore; }, render: function (c) { return el('span', {}, fmtNum(c.liquidityScore, 2)); } },
      { key: 'decisionScore', label: 'Decision score', infoKey: 'decisionscore', get: candidateDecisionScore, render: function (c) { var score = candidateDecisionScore(c); return score === null || score === undefined ? '\u2014' : el('b', {}, fmtNum(score, 0)); } }
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
        }, col.label + (sortKey === col.key ? (sortDir < 0 ? ' \u2193' : ' \u2191') : '')),
        col.infoKey ? UI.info(col.infoKey) : null);
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

  function confirmArchivePlan(plan, leavePlan, onChanged) {
    UI.confirmModal('Archive this Plan?', el('div', {},
      el('p', {}, el('b', {}, plan.title), ' leaves the working collection.'),
      el('p', { class: 'muted' }, 'Its evidence, selected structure, outcomes, decision, and review history remain as a read-only record. Account positions and trades are unchanged.')),
    'Archive Plan', async function () {
      await PlanStore.archive(await PlanStore.get(plan.id, true));
      if (leavePlan) App.navigate('#/home');
      else if (onChanged) await onChanged();
      else await App.refreshCurrentDestination(window.location.hash || '#/home');
    });
  }

  function confirmDeletePlan(plan, leavePlan, onChanged) {
    UI.confirmModal('Delete this draft Plan?', el('div', {},
      el('p', {}, el('b', {}, plan.title), ' and its unfinished studies, proposed trades, and scenario runs will be permanently removed.'),
      el('p', { class: 'muted' }, 'A Plan with a decision, rehearsal, trade, or review record cannot be deleted; it must be archived.')),
    'Delete draft', async function () {
      try {
        await PlanStore.deleteDraft(await PlanStore.get(plan.id, true));
        if (leavePlan) App.navigate('#/home');
        else if (onChanged) await onChanged();
        else await App.refreshCurrentDestination(window.location.hash || '#/home');
      } catch (e) { UI.toast(e.message, 'error'); }
    });
  }

  function planHeader(plan, provisional, onEditView) {
    var context = plan.context || {};
    var title = plan.title || (plan.symbol + ' · New plan');
    return el('header', { class: 'plan-header', id: 'plan-header' },
      el('div', { class: 'plan-header-main' },
        el('div', { class: 'eyebrow' }, provisional ? 'NEW PLAN' : 'WORKING PLAN'),
        el('h1', {}, title),
        el('div', { class: 'plan-header-facts' },
          el('span', { class: 'badge badge-dim' }, planMarketLabel(plan)),
          plan.intent ? intentBadge(plan.intent) : el('span', { class: 'badge badge-dim' }, 'Intent not chosen'),
          context.horizonDays ? chip(UI.term('horizon', 'Horizon'), context.horizonDays + ' trading sessions') : null,
          context.targetCents ? chip('Target', fmtMoney(context.targetCents)) : null,
          // Live market presence inside the journey: fed by the MarketStore SSE stream in
          // world lanes (the simulation is the spine — its motion stays visible while planning).
          el('span', { class: 'plan-live-quote', id: 'plan-live-quote', hidden: '' },
            el('span', { class: 'px', id: 'plan-live-px' })))),
      provisional ? null : el('div', { class: 'plan-header-actions' },
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', id: 'plan-edit-context',
          onclick: function () { if (onEditView) onEditView(); } },
          plan.assumptionsEditable === true ? 'Edit view & limits' : 'Revise this Plan'),
        plan.assumptionsEditable === true ? el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
          id: 'plan-delete', onclick: function () { confirmDeletePlan(plan, true); } }, icon('trash', 15), ' Delete draft') : null,
        plan.status === 'POSITION_OPEN' ? null : el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
          id: 'plan-archive', onclick: function () { confirmArchivePlan(plan, true); } }, icon('archive', 15), ' Archive')));
  }


  function planContextEditor(plan) {
    var c = plan.context || {};
    var canRewriteGoal = plan.assumptionsEditable === true;
    var thesis = UI.chipSet({ id: 'plan-thesis',
      options: [{ value: '', label: 'Not set' }].concat(THESIS_CHOICES.map(function (t) {
        return { value: t.value, label: t.label };
      })),
      value: c.thesis || '' });
    var horizon = el('input', { id: 'plan-horizon-days', type: 'number', min: '1', max: '730',
      value: c.horizonDays == null ? '' : c.horizonDays });
    var target = el('input', { id: 'plan-target-price', type: 'number', min: '0.01', step: '0.01',
      value: c.targetCents ? (c.targetCents / 100).toFixed(2) : '' });
    var risk = UI.segmented({ id: 'plan-risk-mode',
      options: [
        { value: 'conservative', label: 'Cautious' },
        { value: 'balanced', label: 'Standard' },
        { value: 'aggressive', label: 'High' }],
      value: c.riskMode || '' });
    function validatedEditorContext() {
      var horizonDays = Number(horizon.value);
      if (!Number.isInteger(horizonDays) || horizonDays < 1 || horizonDays > 730) {
        throw new Error('Choose a horizon from 1 to 730 trading sessions.');
      }
      var riskMode = risk.value();
      if (!riskMode) throw new Error('Choose a risk posture for this Plan.');
      return {
        thesis: thesis.value() || null,
        horizonDays: horizonDays,
        targetCents: target.value ? Math.round(Number(target.value) * 100) : null,
        riskMode: riskMode
      };
    }
    function editorValues(nextIntent) {
      var values = validatedEditorContext();
      return {
        originPlanId: plan.id, symbol: plan.symbol, intent: nextIntent,
        thesis: values.thesis, horizonDays: values.horizonDays,
        targetCents: values.targetCents, riskMode: values.riskMode,
        holdingsShares: c.holdingsShares, costBasisCents: c.costBasisCents,
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
        var values = validatedEditorContext();
        var clears = [];
        if (!values.thesis) clears.push('thesis');
        if (!target.value) clears.push('targetCents');
        var updated = await PlanStore.updateContext(plan, {
          thesis: values.thesis, horizonDays: values.horizonDays,
          targetCents: values.targetCents, riskMode: values.riskMode, clear: clears
        });
        App.context.update({ symbol: updated.symbol, goal: updated.intent,
          thesis: updated.context.thesis, horizon: updated.context.horizonDays + 'd' });
        await App.refreshMounted();
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
        planField('Target price $', target), planField('Risk budget', risk)),
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
    // band-flat: inside a flow band, interior cards flatten to hairline-separated groups —
    // the band is the one elevation step (Program ONE visual identity).
    var content = el('div', { class: 'plan-stage-content band-flat', id: 'plan-stage-content' });
    var headingId = 'plan-stage-title-' + stage.path;
    var context = plan.context || {};
    var carried = [plan.symbol, plan.intent ? planIntentLabel(plan.intent) : 'goal not chosen',
      context.thesis ? context.thesis + ' view' : 'view not set',
      context.horizonDays ? context.horizonDays + ' trading sessions' : null, planMarketLabel(plan)].filter(Boolean).join(' · ');
    root.appendChild(el('section', { class: 'plan-stage-frame', id: 'plan-stage-' + stage.path,
      'aria-labelledby': headingId },
      // Inside a flow band the band title owns the headline — the stage frame contributes
      // only its one-line purpose and the carried context, never a second stacked heading.
      // One dek: purpose sentence + carried context on two quiet lines — the view band's
      // conclusion and the plan header already own the loud statements of the same facts.
      el('div', { class: 'plan-stage-heading' },
        el('h2', { id: headingId, tabindex: '-1', class: 'sr-only' }, copy.title),
        el('p', { class: 'muted plan-stage-dek' }, copy.body),
        el('div', { class: 'plan-stage-carry', 'aria-label': 'Context carried into this stage' },
          el('span', { class: 'plan-stage-carry-label sr-only' }, 'Carried into this step'),
          el('span', { class: 'plan-stage-carry-value' }, carried))),
      content));
    return content;
  }

  function appendPlanStageNext(host, plan, title, detail, stage, label) {
    host.appendChild(el('div', { class: 'plan-next-action', 'data-recommended-next': stage },
      el('div', {}, el('div', { class: 'eyebrow' }, 'RECOMMENDED NEXT'), el('b', {}, title),
        el('p', { class: 'muted' }, detail)),
      el('button', { type: 'button', class: 'btn', onclick: async function () {
        this.disabled = true;
        var origin = window.location.hash;
        try {
          var live = await PlanStore.get(plan.id, true);
          var moved = await PlanStore.advance(live, stage);
          if (window.location.hash === origin) App.navigate(PlanStore.path(moved, stage));
        } catch (e) { if (this.isConnected) this.disabled = false; UI.toast(e.message, 'error'); }
      } }, label)));
  }

  function planHorizonName(plan) {
    var days = plan.context && plan.context.horizonDays;
    if (!days) return null;
    if (days <= 1) return '0DTE';
    if (days <= 10) return 'week';
    if (days <= 45) return 'month';
    return 'quarter';
  }

  function planIntentLabel(intent) {
    var meta = (Learn.INTENTS || []).find(function (item) { return item.key === intent; });
    return meta ? meta.label : String(intent || '').replaceAll('_', ' ').toLowerCase();
  }

  async function choosePlanCandidate(planRef, candidate, ui, repaint, adjust, button) {
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
    try {
      if (!candidate.selected) {
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
        ui.strategyAction = { kind: 'candidate', candidateId: candidate.id,
          title: (candidate.displayName || candidate.strategy) + ' selected',
          detail: adjust ? 'Opening the exact legs so you can change strikes, dates, or quantity.'
            : 'This exact package now carries into Outcomes. You can still change or clear it here.' };
      }
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
        await repaint();
        var builder = document.getElementById('plan-strategy-panel-builder');
        if (builder) builder.scrollIntoView({ block: 'start', behavior: 'smooth' });
        return;
      }
      await PlanStore.focus(planRef.plan, 'OUTCOMES');
    } catch (e) {
      button.disabled = false;
      button.removeAttribute('aria-busy');
      throw e;
    }
  }

  function planCandidateActions(planRef, candidate, ui, repaint) {
    var actions = el('div', { class: 'plan-candidate-action-block' });
    var buttons = el('div', { class: 'btn-row plan-candidate-actions' });
    function choose(adjust, button) {
      choosePlanCandidate(planRef, candidate, ui, repaint, adjust, button).catch(function (e) {
        UI.setActionFeedback(actions, 'danger', 'Could not select this structure', e.message || String(e));
      });
    }
    // A1: an unviable structure can still be STUDIED, but the action must not read as committing
    // to a trade — it is a teaching path, framed as such (primary emphasis drops too).
    var viable = candidateViable(candidate);
    buttons.appendChild(el('button', { type: 'button', class: viable ? 'btn' : 'btn btn-secondary',
      title: viable ? null : 'This structure is not tradeable at current prices — you can still study its shape and outcomes.',
      onclick: function () { choose(false, this); } },
      candidate.selected ? (viable ? 'Continue to Outcomes' : 'Study outcomes anyway')
        : (viable ? 'Select and continue to Outcomes' : 'Study this structure anyway')));
    buttons.appendChild(el('button', { type: 'button', class: 'btn btn-secondary',
      onclick: function () { choose(true, this); } }, 'Adjust exact contracts'));
    actions.appendChild(buttons);
    if (ui.strategyAction && ui.strategyAction.kind === 'candidate'
        && ui.strategyAction.candidateId === candidate.id) {
      actions.appendChild(UI.actionFeedback('ok', ui.strategyAction.title, ui.strategyAction.detail));
    }
    return actions;
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
        ui.strategyAction = { kind: 'clear', title: 'Structure cleared',
          detail: 'The ranked comparison remains available. Choose another package whenever you are ready.' };
        await repaint();
      } catch (e) {
        button.disabled = false;
        UI.toast((e && e.message) || 'The structure could not be cleared.', 'error');
      }
    } }, 'Clear selection');
  }

  function planStrategyFilterPanel(ui, onShapeChange, planId) {
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
    var node = UI.expandable('Only show proposed trades that fit my limits', function () {
      return el('div', { class: 'plan-filter-expert' }, grid,
        el('p', { class: 'muted small' }, Learn.currentLevel() === 'beginner'
          ? 'The same five limits stay available. Refused structures remain listed with the exact reason.'
          : 'Blank means no extra limit; the Plan risk budget still applies.'));
    }, { open: 'desktop', stateKey: 'plan-strategy-fit-limits-' + String(planId || 'draft') });
    node.id = 'plan-strategy-filters';
    return node;
  }

  function planRiskBudgetReceipt(plan, result) {
    var policy = App.state.riskBudget || {};
    var modeName = String(plan.context && plan.context.riskMode || '').toLowerCase();
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
      UI.expandable('How this budget is used', function () {
        return el('p', { class: 'muted small' }, mode && mode.capped
          ? 'Your declared risk capital caps this amount. The full catalog remains available for learning.'
          : acquire
            ? 'Cash-secured puts reserve strike × 100 shares. This is a purchase commitment, not a small option-risk allowance.'
            : 'This controls sizing and screening, not the strategy catalog or the math.');
      }, { stateKey: 'plan-risk-budget-method' }));
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
      }, { stateKey: 'plan-refused-structures' }));
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
    if (ui.strategyAction && ui.strategyAction.kind === 'ladder') {
      wrap.appendChild(UI.actionFeedback('ok', ui.strategyAction.title, ui.strategyAction.detail));
    }
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
    var selectedKey = ui.selectedLadderKey || candidatePackageKey(planRef.selected);
    async function choose(c, button) {
      button.disabled = true;
      try {
        var live = await PlanStore.get(planRef.plan.id, true);
        var out = await PlanStore.saveCustom(live, { symbol: live.symbol, strategy: c.strategy, qty: c.qty,
          legs: c.legs, thesis: live.context && live.context.thesis, horizon: planHorizonName(live),
          riskMode: live.context && live.context.riskMode, intent: live.intent,
          source: 'INTENT_LADDER', fillNature: 'PROPOSED' });
        planRef.plan = out.plan;
        planRef.selected = out.strategy && out.strategy.result && out.strategy.result.candidate;
        ui.selectedCandidate = planRef.selected;
        ui.selectedLadderKey = candidatePackageKey(planRef.selected || c);
        ui.strategyAction = { kind: 'ladder', candidateKey: ui.selectedLadderKey,
          title: 'Rung selected', detail: 'This exact strike, expiration, and quantity now carry into Outcomes.' };
        await repaint();
        var selectedRow = document.querySelector('#plan-intent-ladder .ladder-row.selected, #plan-intent-ladder tr.selected');
        if (selectedRow) selectedRow.scrollIntoView({ block: 'center', behavior: 'smooth' });
      } catch (e) {
        button.disabled = false;
        UI.setActionFeedback(wrap, 'danger', 'Could not select this rung', e.message || String(e));
      }
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
      var showAll = ui.expandedLadderIntent === intent;
      var visibleIndexes = rungs.map(function (_, i) { return i; });
      if (!showAll && rungs.length > 3) {
        visibleIndexes = [reference];
        for (var offset = 1; visibleIndexes.length < 3 && offset < rungs.length; offset++) {
          if (reference - offset >= 0) visibleIndexes.push(reference - offset);
          if (visibleIndexes.length < 3 && reference + offset < rungs.length) visibleIndexes.push(reference + offset);
        }
        var selectedIndex = rungs.findIndex(function (candidate) { return candidatePackageKey(candidate) === selectedKey; });
        if (selectedIndex >= 0 && visibleIndexes.indexOf(selectedIndex) < 0) {
          var replaceAt = visibleIndexes.reduce(function (pick, index, position) {
            if (index === reference) return pick;
            return pick < 0 || Math.abs(index - reference) > Math.abs(visibleIndexes[pick] - reference) ? position : pick;
          }, -1);
          visibleIndexes[replaceAt < 0 ? visibleIndexes.length - 1 : replaceAt] = selectedIndex;
        }
        visibleIndexes.sort(function (a, b) { return a - b; });
      }
      var list = el('div', { class: 'ladder-sentences', id: 'plan-ladder-rungs' });
      visibleIndexes.forEach(function (i) {
        var c = rungs[i];
        var selected = selectedKey === candidatePackageKey(c);
        var button = el('button', { type: 'button', class: 'btn btn-sm', disabled: selected ? '' : null },
          selected ? 'Selected' : 'Select this rung');
        button.onclick = function () { choose(c, button); };
        list.appendChild(el('div', { class: 'ladder-row' + (i === reference ? ' recommended' : '') + (selected ? ' selected' : ''),
          'aria-current': selected ? 'true' : null },
          el('div', { class: 'ladder-row-badges' },
            i === reference ? el('span', { class: 'badge badge-dim' }, target ? 'CLOSEST TO YOUR TARGET' : 'MIDDLE RUNG') : null,
            ladderEconomicsBadge(c)),
          el('div', { class: 'chip-row' }, facts(c)), button,
          UI.expandable('See exact contracts and risk', function () { return candidateCard(c, false, planRef.plan.symbol); },
            { stateKey: 'plan-ladder-contracts-' + candidatePackageKey(c) })));
      });
      wrap.appendChild(list);
      if (rungs.length > 3) {
        wrap.appendChild(el('div', { class: 'plan-ladder-toggle-row' }, el('button', {
          type: 'button', class: 'btn btn-secondary btn-sm', id: 'plan-ladder-toggle',
          'aria-expanded': showAll ? 'true' : 'false', 'aria-controls': 'plan-ladder-rungs',
          onclick: function () {
            ui.expandedLadderIntent = showAll ? null : intent;
            repaint().catch(function (e) { UI.toast(e.message, 'error'); });
          }
        }, showAll ? 'Show representative prices' : 'Show all ' + rungs.length + ' prices')));
      }
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
    // The Plan may DECLARE holdings the practice account does not carry (tracked elsewhere).
    // One screen must not answer "do I have shares?" two different ways: name the source.
    var declaredShares = planRef.plan.context && planRef.plan.context.holdingsShares;
    var sharesChip = holding ? holding.freeShares + ' ' + symbol
      : declaredShares ? declaredShares + ' ' + symbol + ' \u00b7 declared on this Plan'
      : 'none';
    var candidates = result && result.candidates || [];
    var best = candidates.filter(function (c) { return Number(c.entryNetPremiumCents) > 0; })
      .sort(function (a, b) { return Number(b.entryNetPremiumCents) - Number(a.entryNetPremiumCents); })[0];
    return el('section', { class: 'card plan-income-board', id: 'plan-income-board' },
      UI.cardHeader('Your income picture'),
      el('div', { class: 'chip-row' },
        chip('Free buying power', account ? fmtMoney(account.buyingPowerCents) : '\u2014'),
        chip('Shares available', sharesChip),
        best ? chip('Largest listed credit', fmtMoney(best.entryNetPremiumCents)) : null,
        best && best.annualizedYieldPct != null ? chip('Income pace', fmtNum(best.annualizedYieldPct, 1) + '%/yr') : null),
      el('p', { class: 'muted' }, Learn.currentLevel() === 'beginner'
        ? 'Income uses cash or shares you already have to collect option premium. The payment is real; so is the obligation to buy or sell shares if assigned.'
        : 'Yield uses opening premium after fees over the shares or full strike cash backing the obligation. Assignment odds are the trade-off, not automatically a failure.'),
      !holding && declaredShares ? el('p', { class: 'muted small' },
        'The ' + declaredShares + ' shares come from this Plan\'s declaration, not this practice account. '
        + 'Structures marked "uses held shares" assume you hold them wherever you would place the trade.') : null);
  }

  function renderPlanCandidateField(host, planRef, ui, repaint) {
    var candidates = planRef.result && planRef.result.candidates || [];
    if (!candidates.length) {
      host.appendChild(UI.emptyState('No structure passed this screen',
        'The refused list below keeps the reasons visible. Change a limit only if it still matches your Plan.'));
      return;
    }
    candidates.forEach(function (c, index) { c._servedRank = index + 1; });
    var selectedId = planRef.selected && planRef.selected.id;
    candidates.forEach(function (candidate) { candidate.selected = !!selectedId && candidate.id === selectedId; });
    var field = el('section', { class: 'plan-ranked-field', id: 'plan-ranked-field' });
    host.appendChild(field);

    function review(candidate) {
      ui.strategyFocusCandidate = candidate.id;
      paintField();
      var hero = field.querySelector('.ranked-idea-hero');
      if (hero) {
        hero.classList.add('plan-return-focus');
        hero.setAttribute('tabindex', '-1');
        hero.focus({ preventScroll: true });
        hero.scrollIntoView({ block: 'start', behavior: 'smooth' });
      }
    }

    function compactAlternative(candidate, rank, extra) {
      var button = el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
        onclick: function () { review(candidate); } }, 'Review rank ' + rank);
      var node = ideaPresentation(candidate, { density: 'compact', rank: rank,
        action: el('div', { class: 'ranked-runner-action' }, button) });
      if (extra) node.hidden = true;
      return node;
    }

    function paintField() {
      field.replaceChildren();
      var selected = selectedId && candidates.find(function (c) { return c.id === selectedId; });
      var focused = ui.strategyFocusCandidate
        && candidates.find(function (c) { return c.id === ui.strategyFocusCandidate; });
      var heroCandidate = selected || focused || candidates[0];
      var heroRank = candidates.indexOf(heroCandidate) + 1;
      var reviewingAlternative = !selected && heroRank > 1;
      // A1: an unassessable non-trade is never "the strongest fit / ready to test". When the hero
      // fails the gate, the field leads with an honest no-viable-trade state and the reasons; the
      // structure stays visible below as a teaching example, not a recommendation.
      var heroViable = candidateViable(heroCandidate);
      var anyViable = candidates.some(candidateViable);
      var heroEyebrow = !heroViable ? 'NO VIABLE TRADE'
        : selected ? 'YOUR WORKING STRUCTURE'
          : reviewingAlternative ? 'STRUCTURE UNDER REVIEW' : 'TOP PROPOSED TRADE';
      var heroHeading = !heroViable
        ? (anyViable ? 'The top structures are not tradeable as priced — assessable ones are ranked below'
          : 'No structure earns a viable trade for this Plan at current prices')
        : selected ? 'Selected package, ready to test'
          : reviewingAlternative ? 'Compare this alternative with the top-ranked fit'
            : 'The strongest fit under this Plan’s current assumptions';
      field.appendChild(el('div', { class: 'plan-proposed-heading' + (heroViable ? '' : ' plan-no-viable-trade') },
        el('div', {}, el('span', { class: 'eyebrow' }, heroEyebrow),
          el('h3', {}, heroHeading)),
        el('span', { class: 'muted small' }, 'Ranked, not guaranteed')));
      field.appendChild(rankingSeparation(candidates));
      // DESKTOP MASTER-DETAIL: the ranked MENU (every choice) sits BESIDE the selected structure's
      // full DETAIL, so the desk is read ACROSS and reached WITHOUT scrolling — instead of scrolling
      // past a tall hero to find the alternatives. Collapses to one column on narrow screens and for
      // the expert full-width table.
      var isExpert = Learn.currentLevel() === 'expert';
      var layout = el('div', { class: 'ranked-layout' + (isExpert ? ' ranked-layout-expert' : '') });
      var menuCol = el('div', { class: 'ranked-menu' });
      var detailCol = el('div', { class: 'ranked-detail' });
      layout.appendChild(menuCol);
      layout.appendChild(detailCol);
      field.appendChild(layout);
      if (!heroViable) {
        var heroMech = heroCandidate.evaluation && heroCandidate.evaluation.assessment
          && heroCandidate.evaluation.assessment.mechanics;
        detailCol.appendChild(alertBox('caution',
          'Not a tradeable setup at current prices — shown as a teaching example, not a recommendation',
          (heroMech && heroMech.reasons && heroMech.reasons.length) ? heroMech.reasons
            : ['No structure that fits this Plan clears the mechanical and economic checks required to trade it. '
              + 'Change the view, horizon, or limits — or study the shape below.']));
      }
      detailCol.appendChild(ideaPresentation(heroCandidate, { density: 'hero', rank: heroRank,
        kicker: heroViable ? undefined : 'NOT A VIABLE TRADE',
        spot: planRef.result && planRef.result.spotCents != null ? planRef.result.spotCents / 100 : null,
        action: planCandidateActions(planRef, heroCandidate, ui, repaint) }));
      if (ui.strategyReturnFocus) {
        // Cross-route arrival ("Review this trade" from Outcomes): same return-focus
        // consequence the in-page review path applies — the ring lands on whichever hero
        // renders (a standing selection outranks the reviewed alternative, by design).
        ui.strategyReturnFocus = false;
        var arrivedHero = field.querySelector('.ranked-idea-hero');
        if (arrivedHero) {
          arrivedHero.classList.add('plan-return-focus');
          arrivedHero.setAttribute('tabindex', '-1');
          arrivedHero.focus({ preventScroll: true });
          arrivedHero.scrollIntoView({ block: 'start', behavior: 'smooth' });
        }
      }

      var alternatives = candidates.filter(function (candidate) { return candidate.id !== heroCandidate.id; });
      if (isExpert) {
        // Expert keeps the full-width sortable table; the layout stacks (detail on top, table below)
        // so every column stays legible — the wide plan route gives the table the room it wanted.
        menuCol.appendChild(el('div', { class: 'plan-other-ranked-title' },
          el('h3', {}, 'Full ranked field'), el('p', { class: 'muted' }, 'Sort any column; review a row in the decision hero above.')));
        menuCol.appendChild(comparisonTable(candidates, {
          withUse: false,
          actionLabel: function (c) { return c.selected ? 'Continue' : 'Select + continue'; },
          actionDisabled: function () { return false; },
          onAction: function (c, button) {
            choosePlanCandidate(planRef, c, ui, repaint, false, button).catch(function (e) {
              UI.setActionFeedback(field, 'danger', 'Could not select this structure', e.message || String(e));
            });
          },
          onRow: review
        }));
        return;
      }

      if (!alternatives.length) { layout.classList.add('ranked-layout-solo'); return; }
      menuCol.appendChild(el('div', { class: 'plan-other-ranked-title' },
        el('h3', {}, selected ? 'The rest of the ranked field' : 'Every structure StrikeBench ranked'),
        el('p', { class: 'muted' }, 'Pick one to see its full detail beside the menu.')));
      var list = el('div', { class: 'ranked-runner-list' });
      // Desktop reads across, so the menu shows the whole field at once (no scroll to find a choice);
      // only the phone stack keeps it short behind a "see all".
      var narrow = typeof window !== 'undefined' && window.matchMedia
        && window.matchMedia('(max-width: 1099px)').matches;
      alternatives.forEach(function (candidate, index) {
        list.appendChild(compactAlternative(candidate, candidates.indexOf(candidate) + 1, narrow && index >= 2));
      });
      menuCol.appendChild(list);
      if (narrow && alternatives.length > 2) {
        var expanded = false;
        menuCol.appendChild(el('button', { type: 'button', class: 'btn btn-secondary ranked-show-all',
          'aria-expanded': 'false', onclick: function () {
            expanded = !expanded;
            list.querySelectorAll('.ranked-idea[hidden]').forEach(function (node) { node.hidden = !expanded; });
            if (!expanded) Array.from(list.children).slice(2).forEach(function (node) { node.hidden = true; });
            this.setAttribute('aria-expanded', String(expanded));
            this.textContent = expanded ? 'Show only the closest alternatives' : 'See all ' + candidates.length + ' ranked structures';
          } }, 'See all ' + candidates.length + ' ranked structures'));
      }
    }

    paintField();
  }

  async function planStrategyStage(root, initialPlan, stage) {
    var planRef = { plan: initialPlan, result: null, selected: null };
    var ui = PlanStore.ui(initialPlan.id);
    ui.strategyView = ui.strategyView || 'compare';
    ui.strategyFilters = ui.strategyFilters || {};
    ui.buildState = ui.buildState || {};
    var content = planOwnedStage(root, initialPlan, stage);
    var selector = el('div', { class: 'plan-tool-selector', role: 'group',
      'aria-label': 'Compose your own' });
    var body = el('div', { class: 'plan-strategy-body', id: 'plan-strategy-body' });
    var panels = {}, mountedPanels = {};
    var paintGeneration = 0;
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

    // An evidence handoff ("Find strategies for this view") arrives with intent: land on the
    // ranked field, name the fan it carries, and RUN the field if it has not run — the button
    // promised strategies, not another button. The ribbon lives as long as this stage
    // instance (it must survive the auto-run's own repaint), not one render.
    var handoff = ui.strategyAutoRun || null;
    if (handoff) {
      delete ui.strategyAutoRun;
      ui.strategyView = 'compare';
    }

    var beginner = Learn.currentLevel() === 'beginner';
    // ONE spec §2.2 band 3: the ranked field IS the band's opening content — proposals need
    // no tab. "Your trade" and Builder are its alternate composers; the chain and scout are
    // pickers inside composition — none of them a sibling of the proposals.
    var modes = beginner ? [
      { key: 'builder', label: 'All strategies', icon: 'pen', note: 'Choose a shape, then contracts' },
      { key: 'yourTrade', label: 'Your trade', icon: 'pen', note: 'Edit or paste an exact package' },
      { key: 'chain', label: 'Option prices', icon: 'grid', note: 'Calls, puts and strikes' },
      { key: 'scout', label: 'Scout', icon: 'compass', note: 'Similar setups and offsets' }
    ] : [
      { key: 'builder', label: 'Builder', icon: 'pen', note: 'Strategy-first construction' },
      { key: 'yourTrade', label: 'Your trade', icon: 'pen', note: 'Direct package editing' },
      { key: 'chain', label: 'Chain', icon: 'grid', note: 'Inspect the book' },
      { key: 'scout', label: 'Scout', icon: 'compass', note: 'Similar setups · better fits · offsets' }
    ];
    function newPanel(key) {
      return el('section', { id: 'plan-strategy-panel-' + key, role: 'region',
        'aria-labelledby': key === 'compare' ? null : 'plan-tool-' + key,
        'aria-label': key === 'compare' ? 'Proposed trades' : null,
        class: 'plan-strategy-panel', hidden: 'hidden' });
    }
    function panelFor(key) {
      if (panels[key]) return panels[key];
      var panel = newPanel(key);
      panels[key] = panel;
      body.appendChild(panel);
      return panel;
    }
    function activatePanel(key) {
      panelFor('compare').hidden = false; // the proposals are the band, never a tab
      modes.forEach(function (mode) { panelFor(mode.key).hidden = mode.key !== key; });
    }
    function repaint() { return paint(true); }
    // The proposals host mounts first, then the composition row, then the composer panels —
    // "Compose your own" sits UNDER the proposals it feeds, never above them.
    panelFor('compare');
    body.appendChild(selector);
    selector.classList.add('plan-compose-row');
    selector.id = 'plan-compose';
    selector.appendChild(el('span', { class: 'plan-compose-label' },
      el('b', {}, 'Compose your own'),
      el('small', { class: 'muted' }, beginner
        ? ' — yours enters the same ranked comparison'
        : ' — composed packages rank against the field')));
    // Composer chips are DISCLOSURE TOGGLES (aria-pressed + aria-expanded), not tabs: with
    // the composition folded, no chip is "selected" — a tablist contract would be a lie.
    modes.forEach(function (mode) {
      panelFor(mode.key);
      selector.appendChild(el('button', { type: 'button',
        id: 'plan-tool-' + mode.key, 'aria-controls': 'plan-strategy-panel-' + mode.key,
        class: 'plan-tool' + (ui.strategyView === mode.key ? ' active' : ''),
        'data-strategy-tool': mode.key,
        'aria-pressed': ui.strategyView === mode.key ? 'true' : 'false',
        'aria-expanded': ui.strategyView === mode.key ? 'true' : 'false',
        onclick: function () {
          // A second tap folds the composer back to the proposals-only band.
          ui.strategyView = ui.strategyView === mode.key ? 'compare' : mode.key;
          if (window.Workspace) Workspace.save();
          paint(false).catch(function (e) { UI.toast(e.message, 'error'); });
        }
      }, icon(mode.icon), el('span', {}, el('b', {}, mode.label), el('small', {}, mode.note))));
    });

    async function paint(refresh) {
      var generation = ++paintGeneration;
      selector.querySelectorAll('.plan-tool').forEach(function (button) {
        var active = button.getAttribute('data-strategy-tool') === ui.strategyView;
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', String(active));
        button.setAttribute('aria-expanded', String(active));
      });
      var mode = ui.strategyView;
      activatePanel(mode);
      // The proposals always paint; the open composer (if any) paints beside them.
      var targets = mode === 'compare' ? ['compare'] : ['compare', mode];
      for (var t = 0; t < targets.length; t++) {
        var key = targets[t];
        if (!refresh && mountedPanels[key]) continue;
        var currentPanel = panelFor(key);
        var stagedPanel = newPanel(key);
        stagedPanel.hidden = key !== 'compare' && key !== mode;
        // PositionEditor intentionally ignores market/payoff refreshes while detached. A
        // warm cached research response can settle in the microtask between renderMode and
        // the normal commit below, leaving Your trade permanently unpopulated. Mount its
        // fresh host first; the editor then initializes synchronously into a connected node.
        // Other async producers retain the detached atomic-commit path so stale results
        // cannot flash over a newer filter or Scout selection.
        var connectedInitialization = key === 'yourTrade';
        if (connectedInitialization) {
          currentPanel.replaceWith(stagedPanel);
          panels[key] = stagedPanel;
        }
        try { await renderMode(stagedPanel, key, generation); }
        catch (error) {
          if (generation !== paintGeneration) return;
          mountedPanels[key] = false;
          throw error;
        }
        // Another tool/filter/Scout action won while this producer was awaiting. Its result
        // owns the live panel; this detached staging tree is discarded without a stale flash.
        if (generation !== paintGeneration) return;
        if (!connectedInitialization) {
          currentPanel.replaceWith(stagedPanel);
          panels[key] = stagedPanel;
        }
        mountedPanels[key] = true;
      }
      if (generation === paintGeneration) activatePanel(mode);
    }

    async function renderMode(body, mode, generation) {
      if (mode === 'yourTrade') {
        PositionEditor.render(body, {
          stateKey: 'plan:' + planRef.plan.id,
          planId: planRef.plan.id,
          lockedSymbol: planRef.plan.symbol,
          title: beginner ? 'Edit or paste the exact trade you have in mind' : 'Edit an exact position package directly',
          description: beginner
            ? 'Use this when you already know the contracts. Add from the chain or type the legs; the payoff updates here, and Analyze names and assesses the finished package.'
            : 'This is the direct package editor, distinct from the strategy-first Builder. Terminal and visual entry share one draft; blank fills remain hypothetical and receive a coverage receipt.',
          analyzeLabel: 'Analyze and use in this Plan',
          initialSelection: planRef.selected,
          onAnalyze: async function (position) {
            var live = await PlanStore.get(planRef.plan.id, true);
            planRef.plan = live;
            position.thesis = live.context && live.context.thesis;
            position.horizon = planHorizonName(live);
            position.riskMode = live.context && live.context.riskMode;
            position.intent = live.intent;
            var out = await PlanStore.saveCustom(live, position);
            planRef.plan = out.plan;
            var analyzed = out.strategy && out.strategy.result && out.strategy.result.candidate;
            if (analyzed && analyzed.selected === true) {
              planRef.selected = analyzed;
              ui.selectedCandidate = analyzed;
            } else {
              planRef.selected = null;
              ui.selectedCandidate = null;
            }
            mountedPanels.compare = false;
            // The proposal field is deliberately co-visible with this editor. Refresh only that
            // panel now so a rejected replacement cannot leave the prior package looking selected
            // or keep its Outcomes action beside a truthful "Not selected" editor receipt.
            await paint(false);
            return out;
          }
        });
        return;
      }
      if (mode === 'builder') {
        body.appendChild(el('div', { class: 'plan-tool-intro' },
          el('h3', {}, beginner ? 'Choose a strategy by payoff shape, then build it' : 'Construct from a strategy template'),
          el('p', { class: 'muted' }, beginner
            ? 'Builder starts with the complete visual strategy catalog and guides each contract choice. Your Trade is the direct editor when you already know the package.'
            : 'Builder starts from a named strategy and guides construction for ' + planRef.plan.symbol + ' · '
              + planIntentLabel(planRef.plan.intent) + '. Your Trade remains the direct terminal and leg editor.')));
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
              intent: planRef.plan.intent, source: 'BUILDER', fillNature: 'PROPOSED'
            };
            PlanStore.get(planRef.plan.id, true).then(function (live) {
              planRef.plan = live;
              return PlanStore.saveCustom(live, position);
            }).then(function (out) {
              planRef.plan = out.plan;
              var selected = out.strategy && out.strategy.result && out.strategy.result.candidate;
              if (!selected || selected.selected !== true) {
                throw new Error((out.preview && out.preview.blockReasons || []).join(' ') || 'This package was analyzed but could not become the Plan structure.');
              }
              planRef.selected = selected;
              ui.strategyView = 'compare';
              mountedPanels.compare = false;
              UI.toast('Exact contracts saved to this Plan');
              return paint(true);
            }).catch(function (e) { UI.toast(e.message, 'error'); });
          }
        });
        return;
      }
      if (mode === 'chain') {
        body.appendChild(el('div', { class: 'plan-tool-intro' },
          el('h3', {}, planRef.plan.symbol + ' option chain'),
          el('p', { class: 'muted' }, 'Inspect the live book. In Expert, B starts a custom package from that exact contract.')));
        await research(body, ['__plan', planRef.plan.symbol, 'strategy'], {
          plan: planRef.plan, stage: 'strategy', chainOnly: true,
          onBuildLeg: function (seed) {
            ui.buildState.builderForm = seed;
            ui.strategyView = 'builder';
            mountedPanels.builder = false;
            paint(true).catch(function (e) { UI.toast(e.message, 'error'); });
          }
        });
        return;
      }
      if (mode === 'scout') {
        ui.scoutScope = ui.scoutScope || 'PEERS';
        ui.scoutResults = ui.scoutResults || {};
        var scopeKey = ui.scoutScope;
        var scoutScopes = [
          { key: 'PEERS', label: 'Similar setups', role: 'PEER', note: 'Look for the same view among related stocks' },
          { key: 'ALTERNATIVES', label: 'Better fits', role: 'ALTERNATIVE', note: 'Look across this market for another stock that may express the same goal better' },
          { key: 'HEDGES', label: 'Offsets', role: 'HEDGE', note: 'Look for a separate idea that may offset this Plan’s directional or sector risk' }
        ];
        var scopeRow = el('div', { class: 'segmented plan-scout-scopes', role: 'tablist', 'aria-label': 'Scout job' });
        scoutScopes.forEach(function (scope) {
          scopeRow.appendChild(el('button', { type: 'button', role: 'tab',
            id: 'plan-scout-tab-' + scope.key.toLowerCase(), 'aria-controls': 'plan-scout-panel',
            'data-scout-scope': scope.key,
            class: scopeKey === scope.key ? 'active' : '',
            'aria-selected': String(scopeKey === scope.key), onclick: function () {
              ui.scoutScope = scope.key; paint(true).catch(function (e) { UI.toast(e.message, 'error'); });
            } }, scope.label));
        });
        UI.bindTabList(scopeRow, function (button) {
          ui.scoutScope = button.getAttribute('data-scout-scope');
          paint(true).then(function () {
            var selectedScope = body.querySelector('[data-scout-scope="' + ui.scoutScope + '"]');
            if (selectedScope) selectedScope.focus();
          }).catch(function (e) { UI.toast(e.message, 'error'); });
        });
        var scopeMeta = scoutScopes.find(function (scope) { return scope.key === scopeKey; });
        var scopePanel = el('div', { id: 'plan-scout-panel', role: 'tabpanel',
          'aria-labelledby': 'plan-scout-tab-' + scopeKey.toLowerCase() });
        var scoutHead = el('div', { class: 'card plan-scout-head' },
          UI.cardHeader('Scout around ' + planRef.plan.symbol),
          scopeRow, scopePanel);
        scopePanel.append(el('p', { class: 'muted' }, scopeMeta.note + '. This keeps ' + planRef.plan.symbol
            + ' as the current Plan; a pick opens a separate linked Plan instead of mixing two stocks in one package.'),
          el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn', id: 'plan-run-scout',
            onclick: async function () {
              this.disabled = true; this.setAttribute('aria-busy', 'true');
              try {
                var requestGeneration = paintGeneration;
                var out = await PlanStore.runScout(planRef.plan, { scope: scopeKey, maxPicks: 4, allow0dte: false });
                if (requestGeneration !== paintGeneration || ui.scoutScope !== scopeKey) return;
                ui.scoutResults[scopeKey] = out.scout && out.scout.result;
                await paint(true);
              } catch (e) { UI.toast(e.message, 'error'); this.disabled = false; this.removeAttribute('aria-busy'); }
            } }, ui.scoutResults[scopeKey] ? 'Refresh this scan' : 'Scan ' + scopeMeta.label.toLowerCase())));
        body.appendChild(scoutHead);
        if (!ui.scoutResults[scopeKey]) {
          try {
            var restoredScout = await PlanStore.latestScout(planRef.plan.id, scopeKey, true);
            if (generation !== paintGeneration || ui.scoutScope !== scopeKey) return;
            ui.scoutResults[scopeKey] = restoredScout && restoredScout.scout && restoredScout.scout.result || null;
          } catch (e) { /* a missing prior run is the normal first-use state */ }
        }
        if (generation !== paintGeneration || ui.scoutScope !== scopeKey) return;
        var scoutResult = ui.scoutResults[scopeKey];
        if (!scoutResult) {
          scopePanel.appendChild(UI.emptyState('No ' + scopeMeta.label.toLowerCase() + ' scan yet',
            'Run this focused scan when you already care about ' + planRef.plan.symbol
              + '. Use the universe Scout on Research when you do not have a ticker yet.'));
          return;
        }
        if (!scoutResult.candidates || !scoutResult.candidates.length) {
          scopePanel.appendChild(UI.emptyState('Nothing matched this Scout job',
            (scoutResult.economicMessage || 'No related symbol passed.')
              + ' Refreshing rechecks current prices and evidence; it does not change this Plan.'));
        } else {
          scopePanel.appendChild(el('p', { class: 'muted' }, scoutResult.economicMessage));
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
          scopePanel.appendChild(scoutGrid);
        }
        if (scoutResult.notes && scoutResult.notes.length) {
          scopePanel.appendChild(UI.expandable('Scout notes and skipped symbols', function () {
            return el('ul', { class: 'rationale' }, scoutResult.notes.map(function (note) { return el('li', {}, note); }));
          }));
        }
        return;
      }

      if (handoff) {
        body.appendChild(el('div', { class: 'plan-arrival-ribbon', id: 'plan-strategy-arrival' },
          el('b', {}, 'Your view carried into Strategy.'),
          el('span', { class: 'muted' }, handoff.ensemble
            ? ' Structures below are ranked against it, on the same stored futures you analyzed. '
            : ' Structures below are ranked against it. '),
          handoff.ensemble ? UI.lineageChip(handoff.ensemble, 'same fan as Evidence') : null));
        if (!planRef.result && !ui.strategyDraftDirty) {
          // The auto-run is coming (mounted below): fixed-height ghosts state what is
          // happening where the results will land — never a bare empty-state under a promise.
          var ghostHost = el('div', { class: 'plan-ghost-results', id: 'plan-ghost-results' },
            el('p', { class: 'muted small', 'aria-live': 'polite' },
              'Ranking the complete field against your view…'));
          for (var g = 0; g < 5; g++) ghostHost.appendChild(el('div', { class: 'ghost-row' }));
          body.appendChild(ghostHost);
        }
      }
      if (ui.strategyRunWarning) {
        body.appendChild(alertBox('warn', 'The Plan is safe; ranking needs another run', [
          ui.strategyRunWarning,
          'Use the comparison control below to retry. Saved Plan context and Evidence receipts will not be recreated.'
        ]));
      }
      var warningOwner = ui.evidence && ui.evidence.carryWarningOwnerKey;
      var activeEvidenceOwner = window.ViewResearch && ViewResearch.evidenceOwnerKey
        ? ViewResearch.evidenceOwnerKey(planRef.plan.symbol) : null;
      var carryWarnings = warningOwner && activeEvidenceOwner && warningOwner !== activeEvidenceOwner
        ? [] : ui.evidence && ui.evidence.carryWarnings || [];
      if (carryWarnings.length) {
        body.appendChild(alertBox('warn', 'Some Evidence did not finish carrying into this Plan',
          carryWarnings.concat([
            'The failed receipt remains staged. Return to this symbol in Research and continue from Ready to compare to retry it; successful receipts will not be duplicated.'
          ])));
      }
      var filters = ui.strategyFilters;
      var allow0 = el('input', { type: 'checkbox', id: 'plan-strategy-0dte', checked: filters.allow0dte ? '' : null });
      allow0.addEventListener('change', function () {
        filters.allow0dte = allow0.checked;
        planRef.result = null;
        ui.strategyDraftDirty = true;
        paint(true).catch(function (e) { UI.toast(e.message, 'error'); });
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
          body.appendChild(planLadderView(ladder, planRef, ui, repaint));
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
        UI.cardHeader(el('span', {}, beginner ? 'Find proposed trades for this Plan' : 'Compare structures for this Plan',
          UI.info('proposedtrades')),
          el('span', { class: 'badge badge-dim' }, planRef.plan.symbol + ' · ' + planIntentLabel(planRef.plan.intent))),
        el('p', { class: 'muted' }, beginner
          ? 'StrikeBench compares the complete strategy catalog using this Plan’s goal, view, time, holdings, account and risk budget. Poor fits stay visible as refused or teaching cases.'
          : 'The server uses the Plan’s thesis, horizon, holdings, target, account and risk budget. Optional limits narrow the same complete catalog.'),
        planRiskBudgetReceipt(planRef.plan, planRef.result),
        planStrategyFilterPanel(ui, function () {
          planRef.result = null;
          ui.strategyDraftDirty = true;
          paint(true).catch(function (e) { UI.toast(e.message, 'error'); });
        }, planRef.plan.id),
        el('label', { class: 'check-row', for: 'plan-strategy-0dte' }, allow0, ' Include same-day expirations (0DTE)'),
        el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn', id: 'plan-run-strategy',
          onclick: async function () {
            this.disabled = true; this.setAttribute('aria-busy', 'true');
            try {
              var out = await PlanStore.runStrategy(planRef.plan, requestValues());
              planRef.plan = out.plan;
              planRef.result = out.strategy && out.strategy.result;
              ui.strategyDraftDirty = false;
              delete ui.strategyRunWarning;
              await paint(true);
              var arrival = document.querySelector('#plan-ranked-field .ranked-idea-hero');
              if (arrival) {
                arrival.setAttribute('tabindex', '-1');
                arrival.focus({ preventScroll: true });
                arrival.scrollIntoView({ block: 'start', behavior: 'smooth' });
                arrival.classList.add('arrival-highlight');
              }
            } catch (e) { UI.toast(e.message, 'error'); this.disabled = false; this.removeAttribute('aria-busy'); }
          } }, planRef.result ? 'Refresh proposed trades' : beginner ? 'Find proposed trades' : 'Run ranked field')));
      if (ui.strategyAction && ui.strategyAction.kind === 'clear') {
        controls.appendChild(UI.actionFeedback('ok', ui.strategyAction.title, ui.strategyAction.detail));
      }
      body.appendChild(controls);
      var selectedInField = planRef.result && planRef.selected
        && (planRef.result.candidates || []).some(function (candidate) { return candidate.id === planRef.selected.id; });
      if (planRef.selected && !selectedInField) {
        planRef.selected.selected = true;
        body.appendChild(ideaPresentation(planRef.selected, {
          density: 'hero', rank: 1, symbol: planRef.plan.symbol,
          className: 'plan-selected-structure',
          action: el('div', { class: 'plan-selection-command' },
            planCandidateActions(planRef, planRef.selected, ui, repaint),
            clearPlanStructureButton(planRef, ui, repaint))
        }));
      }
      if (!planRef.result) {
        // Under a handoff the ghosts above already state what is running — a contradictory
        // "nothing has run" card would make the promise look broken.
        if (!handoff) {
          body.appendChild(UI.emptyState(ui.strategyDraftDirty ? 'Limits changed — rerun the field' : 'No comparison has run yet',
            ui.strategyDraftDirty
              ? 'The prior result is still in Plan history, but it is hidden here because it did not use the limits now on screen.'
              : 'Run the complete field once. The Plan saves the exact ranked result so a reload cannot change what you saw.'));
        }
        return;
      }
      body.appendChild(el('div', { class: 'card plan-strategy-summary' },
        UI.cardHeader(beginner ? 'Proposed trades, in rank order' : 'Ranked field', el('span', { class: 'badge badge-dim' }, planRef.result.candidates.length + ' candidates')),
        el('p', {}, planRef.result.economicMessage || 'Compare mechanics, evidence, costs and economic placement together.'),
        el('div', { class: 'chip-row' },
          chip(UI.term('cashbaseline', 'Cash / no trade'), '$0 modeled P/L'),
          chip(UI.term('decisionscore', 'Favorable'), planRef.result.favorableCount || 0),
          chip(UI.term('decisionscore', 'Mixed'), planRef.result.mixedCount || 0),
          chip(UI.term('decisionscore', 'Unfavorable'), planRef.result.unfavorableCount || 0),
          chip(UI.term('decisionscore', 'Unavailable'), planRef.result.unavailableCount || 0))));
      var field = el('div', { id: 'plan-strategy-results' });
      body.appendChild(field);
      renderPlanCandidateField(field, planRef, ui, repaint);
      if (planRef.selected) {
        body.appendChild(el('div', { class: 'plan-selection-actions' }, clearPlanStructureButton(planRef, ui, repaint)));
      }
      if (planRef.result.rejected && planRef.result.rejected.length) {
        body.appendChild(planRejectedTeaching(planRef.result.rejected, ui, repaint));
      }
    }
    await paint(false);
    // Deliver the evidence handoff: run the field through its own visible control so the
    // user sees the same progress, result, and arrival focus as a manual run.
    if (handoff && !planRef.result && !ui.strategyDraftDirty) {
      var handoffRun = content.querySelector('#plan-run-strategy');
      if (handoffRun && !handoffRun.disabled) handoffRun.click();
    }
  }

  /** The stored fan a saved outcome run repriced on — ApiResponses.EnsembleRef shape or null. */
  function outcomeFanRef(run) {
    if (!run) return null;
    if (run.ensembleRef && run.ensembleRef.fingerprint) return run.ensembleRef;
    return run.ensembleFingerprint
      ? { id: run.ensembleId, fingerprint: run.ensembleFingerprint, basis: run.basis } : null;
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
    var fan = outcomeFanRef(run);
    return el('div', { class: 'plan-outcome-summary-card', 'data-basis-summary': run.basis },
      el('div', { class: 'plan-outcome-summary-head' }, el('b', {}, title),
        fan ? UI.lineageChip(fan)
          : run.ensembleId ? el('span', { class: 'badge badge-dim' }, 'PINNED PATHS') : null),
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
    // When the best case is not a profit, "chance of max profit" reads as a flat contradiction
    // to "chance of any profit 0%". Name it honestly and reconcile it in words.
    var cannotProfit = pAny != null && pAny <= 0.0005;
    var ev = value('expectedValueAfterFeesCents');
    return el('div', { class: 'plan-market-outcome' },
      alertBox(ev != null && ev >= 0 ? 'ok' : 'caution',
        'Market-implied economics for the exact package', [
          'These are the option market’s risk-neutral odds at the captured executable entry, not a forecast.',
          'Estimated by Monte-Carlo over the stored fan. The Strategy tab’s “Market-implied EV” is a '
            + 'closed-form estimate of the same package — the two methods can differ slightly; that is '
            + 'method variance, not a contradiction.',
          ev == null ? 'Expected value is unavailable.' : 'Expected value after estimated round-trip fees: ' + UI.fmtMoneyCompact(ev) + '.'
        ]),
      el('div', { class: 'grid grid-4' },
        stat('Chance of any profit', pAny == null ? '—' : Math.round(pAny * 100) + '%'),
        stat(cannotProfit ? 'Chance of the best case (still a loss)' : 'Chance of theoretical max profit',
          pMax == null ? '—' : Math.round(pMax * 100) + '%'),
        stat('Chance of theoretical max loss', pLoss == null ? '—' : Math.round(pLoss * 100) + '%'),
        stat('Expected P/L after costs', ev == null ? '—' : pnlSpan(ev))),
      cannotProfit ? el('p', { class: 'muted small' }, 'This position cannot profit — its best '
        + 'possible outcome is still a loss. A 0% chance of any profit and a high chance of the '
        + '“best case” describe the same losing position; they are not a contradiction.') : null,
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
    function economicsBadge(item) {
      if (item.key === 'CASH') return el('span', { class: 'badge badge-dim' }, 'CASH BASELINE');
      var verdict = item.economicVerdict || 'UNAVAILABLE';
      var classes = { FAVORABLE: 'badge-ok', MIXED: 'badge-caution', UNFAVORABLE: 'badge-danger', UNAVAILABLE: 'badge-dim' };
      return el('span', { class: 'badge ' + classes[verdict] }, 'MARKET VIEW: ' + UI.economicVerdictLabel(verdict));
    }
    function reviewAction(item) {
      if (!item.candidateId) return null;
      if (item.selected) return el('span', { class: 'badge badge-ok' }, 'CURRENT STRUCTURE');
      var button = el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: async function () {
        button.disabled = true;
        try {
          // Write through the plan's own UI rail: this handler's local ui belongs to the
          // outcomes view and dies with it — the strategy arrival reads PlanStore.ui.
          var planUi = PlanStore.ui(planRef.plan.id);
          planUi.strategyView = 'compare'; planUi.strategyFocusCandidate = item.candidateId;
          planUi.strategyReturnFocus = true; // one-shot: the arrival applies the highlight
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
              stat(UI.vocabulary('theoreticalMaxLoss'), item.maxLossCents == null ? '—' : fmtMoney(item.maxLossCents))),
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
      var cardGrid = el('div', { class: 'plan-proposal-card-grid' }, first.map(itemCard));
      var showAll = remaining.length ? el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () {
        remaining.forEach(function (item) { cardGrid.appendChild(itemCard(item)); });
        showAll.remove();
      } }, 'Show all ' + items.length + ' compared choices') : null;
      body = el('div', { class: 'plan-proposal-beginner' }, cardGrid,
        showAll ? el('div', { class: 'btn-row plan-proposal-show-all' }, showAll) : null);
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
        comparison.ensembleFingerprint ? UI.lineageChip({ id: comparison.ensembleId,
          fingerprint: comparison.ensembleFingerprint, basis: comparison.basis }, 'same fan as Evidence') : null),
      leader && leader.key === 'CASH' ? alertBox('caution', 'Cash leads on this outcome lens', [
        'Every valued proposal ranked below the zero-risk, zero-cost baseline after estimated costs. The trades remain available to study; this lens does not endorse them.'
      ]) : null,
      body,
      UI.expandable('How this comparison was kept fair', function () {
        return el('div', {},
          el('p', { class: 'muted small' }, 'The market-view badge preserves the option-market economic assessment. The figures above answer a separate question: how each package behaved on this one shared evidence set.'),
          el('p', { class: 'muted small' }, Learn.currentLevel() === 'beginner'
            ? 'All rows used the same saved receipt, captured proposal entry, quantity and estimated costs. Higher ranks balance the expected result against the bad outcomes; cash stayed at $0.'
            : comparison.fairness + ' Outcome rank is after-cost EV per dollar of modeled p5 downside; refusals sort last.'));
      }, { stateKey: 'plan-proposal-comparison-method' }));
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
      var staleSelection = latest.selectionState === 'STALE' ? latest.priorSelection : null;
      function openStrategyForSelection(button) {
        if (staleSelection && window.PositionEditor && PositionEditor.draftFromCandidate) {
          var draft = PositionEditor.draftFromCandidate(staleSelection,
            { lockedSymbol: planRef.plan.symbol });
          if (draft) {
            draft.selectedCandidateId = null;
            draft.selectedFingerprint = null;
            App.state.positionDrafts = App.state.positionDrafts || {};
            App.state.positionDrafts['plan:' + planRef.plan.id] = draft;
            ui.strategyView = 'yourTrade';
          }
        }
        focusPlanFrom(button || null, planRef.plan, 'STRATEGY');
      }
      content.appendChild(el('section', { class: 'card plan-outcomes-prerequisite', id: 'plan-outcomes-prerequisite' },
        UI.cardHeader(staleSelection ? 'Reprice the prior structure' : 'Choose exact contracts before testing outcomes'),
        el('p', { class: 'muted' },
          staleSelection
            ? 'The Plan assumptions changed after this package was selected. Its old results remain historical evidence, but they are not current. Reprice the same legs or choose a different structure before testing outcomes.'
            : 'A stock view is not yet a position. Strike, expiration, side, and quantity determine the odds, expected value, worst case, and path-by-path P/L.'),
        el('div', { class: 'plan-outcomes-prerequisite-grid' },
          UI.fact('Market-implied lens', 'POP + after-cost EV'),
          UI.fact('Possible futures (Monte Carlo)', 'p5 · median · p95'),
          UI.fact('Historical lens', 'No-look-ahead replay')),
        el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn',
          onclick: function () { openStrategyForSelection(this); }
        }, staleSelection ? 'Reprice the prior structure' : 'Choose contracts in Strategy'))));
      content.appendChild(planOutcomeWorkspace({ id: 'plan-outcomes', state: ui.outcomes, label: 'Outcome basis',
        modes: [
          { key: 'market', label: 'Market odds', info: 'marketodds', description: 'Implied by the option book', available: false,
            unavailableReason: 'Select exact contracts in Strategy before calculating the package odds.',
            setupLabel: staleSelection ? 'Reprice prior structure' : 'Choose exact contracts', setup: function () { openStrategyForSelection(null); } },
          { key: 'model', label: 'Possible futures (Monte Carlo)', info: 'possiblefutures', description: 'Position P/L across modeled paths', available: false,
            unavailableReason: 'Select exact contracts first; Monte Carlo then prices that same package across the stored possible futures.',
            setupLabel: staleSelection ? 'Reprice prior structure' : 'Choose exact contracts', setup: function () { openStrategyForSelection(null); } },
          { key: 'analogs', label: 'Past analogs', info: 'pastanalogs', description: 'Matching historical episodes', available: false,
            unavailableReason: 'Select exact contracts before applying the package to matching historical episodes.',
            setupLabel: staleSelection ? 'Reprice prior structure' : 'Choose exact contracts', setup: function () { openStrategyForSelection(null); } },
          { key: 'backtest', label: 'Rule replay', info: 'rulereplay', description: 'Repeated historical entries', available: false,
            unavailableReason: 'Select a structure before replaying its named entry and management rules.',
            setupLabel: staleSelection ? 'Reprice prior structure' : 'Choose exact contracts', setup: function () { openStrategyForSelection(null); } }
        ] }).el);
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
      candidatePositionSummary(selected, 'EXACT CONTRACTS BEING TESTED'),
      window.Scenario ? Scenario.realisticOutcomes(planRef.plan.symbol, selected, { autoRun: true }) : null,
      UI.expandable('Full analysis', function () { return candidateFullAnalysis(selected); },
        { stateKey: 'plan-outcome-full-analysis' })));

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
        { key: 'market', label: 'Market odds', info: 'marketodds', description: 'Implied by the option book',
          note: 'Risk-neutral odds and after-cost EV from the captured executable package; market pricing, not a forecast.',
          render: renderMarket },
        { key: 'model', label: 'Possible futures (Monte Carlo)', info: 'possiblefutures', description: 'The Evidence ensemble',
          note: 'The exact position on the same stored path matrix and IV trajectory shown in Evidence.', render: renderModel },
        { key: 'analogs', label: 'Past analogs', info: 'pastanalogs', description: 'Matching historical episodes',
          note: 'Direct analog occurrences or whole-path bootstrap resamples. Conditional history, never model odds.', render: renderAnalogs },
        { key: 'backtest', label: 'Rule replay', info: 'rulereplay', description: 'Repeated historical entries',
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
        if (out.outcome && out.ensemble) out.outcome.ensembleRef = out.ensemble;
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
      // The futures tool pins its fan at PlanStore.ui(id).evidence (the workbench surface);
      // reading the top level silently dropped the pin and let every run fall back to the
      // server-side "current ensemble" lookup instead of the exact fan on screen.
      var evidenceUi = PlanStore.ui(planRef.plan.id).evidence || {};
      var status = el('div', { class: 'plan-outcome-action-status', 'aria-live': 'polite' });
      var fanRef = outcomeFanRef(saved) || (evidenceUi.planEnsembleFingerprint
        ? { id: evidenceUi.planEnsembleId, fingerprint: evidenceUi.planEnsembleFingerprint, basis: 'PARAMETRIC' } : null);
      host.appendChild(el('div', { class: 'card' }, UI.cardHeader('Use the Evidence ensemble',
        fanRef ? UI.lineageChip(fanRef, 'same fan as Evidence') : null),
        el('p', { class: 'muted' }, evidenceUi.planEnsembleFingerprint
          ? 'Ready: the exact stored futures are verified and tied to this Plan.'
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
      // The scenario canvas (R4.5): author a believed path onto the same stored fan the position
      // lenses reprice; the studio re-runs it in place and keeps the Evidence pin in sync.
      if (window.Scenario && Scenario.canvasStudio) host.appendChild(Scenario.canvasStudio({ planRef: planRef }));
      if (saved) {
        // A result computed on a pinned (authored) fan says so every time it is shown.
        var savedFill = saved.waypointFill || (saved.ensembleRef && saved.ensembleRef.waypointFill) || 'NONE';
        if (savedFill === 'GUIDED_INTERPOLATION') host.appendChild(alertBox('caution',
          'Priced on an authored fan — guided interpolation',
          ['The paths under this result were bent through authored waypoints; this model’s fat tails and jumps are approximated near the pins, not exact.']));
        else if (savedFill === 'EXACT_CONDITIONAL') host.appendChild(el('p', { class: 'muted small' },
          'Priced on an authored fan — exact conditional paths, the model’s own randomness pinned through the authored waypoints.'));
        host.appendChild(Scenario.pnlView(saved.result || saved, Learn.currentLevel()));
      }
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
      var engine = UI.segmented({ id: 'plan-replay-engine',
        options: [
          { value: 'single', label: 'One trade at a time' },
          { value: 'portfolio', label: 'A book of overlapping trades' }],
        value: form.engine || 'single',
        onChange: function () { rememberForm(); } });
      var planSessions = planRef.plan.context && planRef.plan.context.horizonDays;
      var defaultDte = planSessions
        ? Product.Horizon.expiryDays(Product.Horizon.keyForSessions(planSessions)) : null;
      var dte = el('input', { id: 'plan-replay-dte', type: 'number', min: '1', max: '365',
        value: form.targetDte || defaultDte || '',
        placeholder: planSessions ? '' : 'Declare a Plan horizon first' });
      var qty = el('input', { id: 'plan-replay-qty', type: 'number', min: '1', max: '100', value: form.qty || 1 });
      var every = el('input', { id: 'plan-replay-spacing', type: 'number', min: '1', max: '60', value: form.entryEveryDays || 5 });
      var cash = el('input', { id: 'plan-replay-cash', type: 'number', min: '1', step: '1000', value: form.startingCash || 100000 });
      var slip = el('input', { id: 'plan-replay-slippage', type: 'number', min: '0', max: '10', step: '0.1', value: form.slippagePct == null ? 0.5 : form.slippagePct });
      function rememberForm() {
        form.from=from.value; form.to=to.value; form.engine=engine.value(); form.targetDte=dte.value;
        form.qty=qty.value; form.entryEveryDays=every.value; form.startingCash=cash.value; form.slippagePct=slip.value;
      }
      [from,to,dte,qty,every,cash,slip].forEach(function (input) { input.addEventListener('change', function () {
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
        }, { stateKey: 'plan-previous-replays' }));
      }
      var rangePresets = el('div', { class: 'segmented plan-backtest-presets', 'aria-label': 'Replay date range' },
        [['6M',6],['1Y',12],['3Y',36],['MAX','max']].map(function (preset) {
          return el('button', { type: 'button', onclick: function () { setRange(preset[1], this); } }, preset[0]);
        }));
      var dtePresets = el('div', { class: 'segmented plan-backtest-presets', 'aria-label': 'Target days to expiry' },
        [['Weekly',7],['Monthly',30],['Quarterly',90]].map(function (preset) {
          return el('button', { type: 'button', onclick: function () { setDte(preset[1], this); } }, preset[0]);
        }));
      // Decision moment: "does this rule survive history?" — the rule's parameters sit in a
      // rail BESIDE the equity/trade evidence they produce, not a viewport above it.
      var paramsCard = el('div', { class: 'card' }, UI.cardHeader('Replay ' + (selected.displayName || prettyStrategy(selected.strategy))),
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
            var out=await PlanStore.runBacktest(live,{engine:engine.value(),from:from.value,to:to.value,
              targetDte:positiveInteger(dte.value,'Target DTE',365),entryEveryDays:positiveInteger(every.value,'Entry spacing',60),
              qty:positiveInteger(qty.value,'Quantity',100),slippagePct:Math.max(0,Number(slip.value))/100,
              startingCashCents:Math.round(Number(cash.value)*100),maxConcurrent:4,shortDelta:0.30,widthPct:0.05,
              profitTargetPct:0.5,stopFraction:0.8,rollDte:7});
            latestBacktest=out.backtest; renderPlanBacktestReport(results,out.report);
            latest=await PlanStore.latestOutcomes(planRef.plan.id,true); allBacktests=latest.backtests||[]; paintHistory();
          } catch(e){results.innerHTML='';results.appendChild(alertBox('danger','Replay failed',[e.message]));}
          finally{this.disabled=false;this.removeAttribute('aria-busy');}
        } }, latestBacktest ? 'Run another replay' : 'Run historical replay')));
      host.appendChild(el('div', { class: 'band-cols' },
        el('div', { class: 'band-col-controls' }, paramsCard),
        el('div', { class: 'band-col-results' }, results, history)));
      paintHistory();
      if (latestBacktest && latestBacktest.backtestId) {
        API.get('/api/plans/' + planRef.plan.id + '/outcomes/backtests/' + latestBacktest.backtestId).then(function (report) {
          if (results.isConnected && !results.hasChildNodes()) renderPlanBacktestReport(results, report);
        }).catch(function () { /* summary remains in the comparison; rerun is available */ });
      }
    }
  }

  function renderFrozenPlanDecision(host, plan, decision, insideManage) {
    var traded = decision.action === 'TRADE';
    host.appendChild(alertBox(traded ? 'ok' : 'caution',
      traded ? el('span', {}, UI.vocabulary('practice'), ' position opened from this Plan') : 'Cash was the decision', [
        traded ? 'The exact priced package and account snapshot are frozen beside the linked practice trade.'
          : 'Doing nothing is a first-class decision. The rejected package remains frozen for review against cash.',
        'Decision time: ' + (decision.quoteAsOf || decision.createdAt || 'captured by the server')
      ]));
    host.appendChild(el('div', { class: 'grid grid-4 plan-decision-facts' },
      stat('Action', traded ? 'Practice trade' : 'Stayed in cash'),
      stat(UI.vocabulary('theoreticalMaxLoss'), decision.maxLossCents == null ? '—' : fmtMoney(decision.maxLossCents)),
      stat('Chance of any profit', decision.pop == null ? '—' : fmtPct(decision.pop)),
      stat('Market EV after costs', decision.evMarketCents == null ? '—' : pnlSpan(decision.evMarketCents)),
      stat('Realized-vol scenario EV', decision.evHistvolCents == null ? '—' : pnlSpan(decision.evHistvolCents)),
      stat('Economic view', UI.economicVerdictLabel(decision.economicVerdict)),
      stat('Evidence', UI.evidenceBadge(decision.evidenceProvenance || 'MISSING', { compact: true })),
      stat(UI.vocabulary('buyingPower', 'Buying power at decision'),
        decision.buyingPowerCents == null ? '—' : fmtMoney(decision.buyingPowerCents))));
    if (decision.legs && decision.legs.length) {
      function exactPrice(value) { return value == null ? '—' : '$' + String(value); }
      host.appendChild(el('section', { class: 'plan-frozen-contracts' },
        el('h3', {}, 'Frozen listed contracts'),
        table(['Side', 'Contract', 'Bid', 'Ask', 'Fill'], decision.legs.map(function (leg) {
        var strike = leg.strikePrice == null ? '' : ' ' + exactPrice(leg.strikePrice);
        return el('tr', {}, el('td', {}, leg.action), el('td', {}, leg.type + strike + (leg.expiration ? ' · ' + leg.expiration : '')),
          el('td', {}, exactPrice(leg.bidPrice)), el('td', {}, exactPrice(leg.askPrice)),
          el('td', {}, exactPrice(leg.fillPrice)));
        }))));
    }
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
      var staleSelection = latest.selectionState === 'STALE' ? latest.priorSelection : null;
      content.appendChild(UI.emptyState(staleSelection ? 'Reprice the prior structure before deciding'
        : 'Choose a structure before deciding', staleSelection
          ? 'The Plan assumptions changed after the prior package was selected. Decide will not reuse its old price or evidence silently.'
          : 'Decide never invents or recaptures a package. Select exact contracts in Strategy first.',
        staleSelection ? 'Reprice prior structure' : 'Open Strategy', function () {
          if (staleSelection && window.PositionEditor && PositionEditor.draftFromCandidate) {
            var draft = PositionEditor.draftFromCandidate(staleSelection,
              { lockedSymbol: planRef.plan.symbol });
            if (draft) {
              draft.selectedCandidateId = null;
              draft.selectedFingerprint = null;
              App.state.positionDrafts = App.state.positionDrafts || {};
              App.state.positionDrafts['plan:' + planRef.plan.id] = draft;
              ui.strategyView = 'yourTrade';
            }
          }
          focusPlanFrom(this, planRef.plan, 'STRATEGY');
        }));
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
    var decisionTop = el('div', { class: 'plan-decision-top-grid' });
    content.appendChild(decisionTop);
    decisionTop.appendChild(el('div', { class: 'card plan-decision-position' },
      UI.cardHeader('Exact package under decision', el('span', { class: 'badge badge-ok' }, 'LOCKED TO PLAN')),
      el('div', { class: 'chip-row' }, chip('Structure', selected.displayName || prettyStrategy(selected.strategy)),
        chip('Contracts', (selected.legs || []).length + ' legs'), chip('Plan intent', planIntentLabel(initialPlan.intent))),
      candidatePositionSummary(selected, 'EXACT CONTRACTS UNDER DECISION'),
      UI.expandable('Full analysis', function () { return candidateFullAnalysis(selected); },
        { stateKey: 'plan-decision-full-analysis' })));

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
    decisionTop.appendChild(el('div', { class: 'card plan-decision-controls' }, UI.cardHeader('Price the decision now'),
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
          paintPreview(true);
        } catch (e) { review.innerHTML = ''; review.appendChild(alertBox('danger', 'Could not review this order', [e.message])); }
        finally { this.disabled = false; }
      } }, state.preview ? 'Refresh exact review' : 'Review exact order'))));
    content.appendChild(review);

    function paintPreview(revealResult) {
      var result = state.preview;
      if (!result) return;
      var p = result.preview, evaluation = result.evaluation || {};
      var economics = evaluation.assessment && evaluation.assessment.economics;
      review.innerHTML = '';
      var primary = el('div', { class: 'plan-decision-review-primary' });
      var secondary = el('div', { class: 'plan-decision-review-secondary' });
      review.appendChild(el('div', { class: 'plan-decision-review-grid' }, primary, secondary));
      var economicBlock = economicAssessmentBlock({ evaluation: evaluation });
      if (economicBlock) primary.appendChild(economicBlock);
      var decisionActionAnchor = el('div', { class: 'plan-decision-action-anchor' });
      var metrics = decisionMetricsReceipt(evaluation, Learn.currentLevel() === 'beginner');
      if (metrics) secondary.appendChild(metrics);
      secondary.appendChild(verdictPanel(p, Learn.currentLevel() === 'beginner', true).node);
      var blocks = (p.blockReasons || []).concat(result.guardrails && result.guardrails.blockReasons || []);
      var warnings = (p.warnings || []).concat(result.guardrails && result.guardrails.warnings || []);
      if (blocks.length) {
        primary.appendChild(alertBox('danger', 'This package cannot be opened', blocks));
        var execution = p.analytics && p.analytics.executionQuality;
        var executableNet = execution && execution.executableNetCents;
        if (executableNet != null && blocks.some(function (reason) { return /more favorable than the executable market/i.test(reason); })) {
          primary.appendChild(el('div', { class: 'btn-row plan-stale-limit-recovery' },
            el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-use-executable', onclick: function () {
              state.proposedNetCents = executableNet;
              limit.value = (executableNet / 100).toFixed(2);
              var reviewButton = document.getElementById('plan-review-order');
              if (reviewButton) reviewButton.click();
            } }, 'Use current executable ' + fmtMoney(executableNet, { plus: true }) + ' & review')));
        }
      }
      if (warnings.length) primary.appendChild(alertBox('caution', 'Review these conditions', warnings));
      var decisionMath = el('div', { class: 'grid grid-4 plan-decision-math' },
        stat(UI.term('entrycashflow', 'Cost / credit'), fmtMoney(p.entryNetPremiumCents, { plus: true })),
        stat(UI.vocabulary('theoreticalMaxLoss'), el('span', { class: 'loss' }, fmtMoney(p.maxLossCents))),
        stat(UI.vocabulary('theoreticalMaxProfit'), UI.maxProfitLabel(selected.strategy, selected.structureGroup,
          p.maxProfitCents, Learn.currentLevel() === 'beginner', p.legs)),
        stat('Chance of any profit', fmtPct(p.popEntry)),
        stat('Market EV after costs', economics && economics.marketEvAfterCostsCents != null ? pnlSpan(economics.marketEvAfterCostsCents) : '—'),
        stat('Realized-vol scenario EV', economics && economics.realizedVolEvAfterCostsCents != null ? pnlSpan(economics.realizedVolEvAfterCostsCents) : '—'),
        stat(UI.vocabulary('buyingPower', 'Buying power after'), fmtMoney(p.buyingPowerAfterCents)),
        stat('Opening fees', fmtMoney(p.feesOpenCents)));
      var scenarioSummary = window.Scenario && selected.legs && selected.legs.length
        ? Scenario.realisticOutcomes(initialPlan.symbol,
          Object.assign({}, selected, { qty: state.qty, entryNetPremiumCents: p.entryNetPremiumCents }),
          { autoRun: true }) : null;
      var required = result.requiredAcks || [];
      // Acknowledgments survive level flips and re-renders (Program ONE §2.2 band 5); they
      // reset only when the package itself changes (the selectedPackageKey guard above).
      state.acks = state.acks || {};
      var trade = el('button', { type: 'button', class: 'btn', id: 'plan-place-trade', disabled: 'disabled' }, 'Open practice position');
      var broker = null, brokerHost = null;
      if ((planRef.plan.marketKind || initialPlan.marketKind) !== 'SIMULATED') {
        // The third outcome (real lane): the user already executed at their broker; StrikeBench
        // records the actual fills — it never places real orders. Practice buying-power blocks
        // do not gate this, but material-risk acknowledgments do.
        broker = el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-record-broker',
          disabled: 'disabled' }, 'I placed this with my broker');
        brokerHost = el('div', { class: 'plan-broker-record', id: 'plan-broker-record', hidden: '' });
        broker.onclick = function () {
          brokerHost.hidden = !brokerHost.hidden;
          if (!brokerHost.hidden) {
            paintBrokerCard();
            brokerHost.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
          }
        };
      }
      function refresh() {
        var complete = required.every(function (ack) { return state.acks[ack.id]; });
        trade.disabled = !p.ok || !complete;
        if (broker) broker.disabled = !complete;
      }
      if (required.length) primary.appendChild(el('div', { class: 'card card-slim ack-gate' },
        UI.cardHeader('Acknowledge material risks'), required.map(function (ack) {
          var box = el('input', { type: 'checkbox', id: 'plan-' + ack.id,
            checked: state.acks[ack.id] ? 'checked' : null,
            onchange: function () { state.acks[ack.id] = box.checked; refresh(); } });
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
      primary.appendChild(decisionActionAnchor);
      decisionActionAnchor.appendChild(el('div', { class: 'plan-decision-actions' },
        el('div', {}, el('b', {}, 'Make the decision'), el('p', { class: 'muted' },
          broker ? 'Practice it, stay in cash, or record the placement you already made at your broker — every outcome preserves this exact comparison for review.'
            : 'Trade and cash both preserve this exact comparison for later review.')),
        el('div', { class: 'btn-row' }, trade, cash, broker)));
      if (brokerHost) decisionActionAnchor.appendChild(brokerHost);

      function brokerLegLabel(leg) {
        if (!leg) return 'Leg';
        var action = leg.action === 'SELL' ? 'Sell' : 'Buy';
        if (leg.type === 'STOCK') {
          return action + ' ' + ((leg.ratio || 1) * (leg.multiplier || 1) * (state.qty || 1)) + ' shares';
        }
        return action + ' ' + ((leg.ratio || 1) * (state.qty || 1)) + '× ' + (leg.strike || '') + ' '
          + (leg.type || '').toLowerCase() + (leg.expiration ? ' · ' + leg.expiration : '');
      }
      function paintBrokerCard() {
        state.broker = state.broker || { accountId: null, externalRef: '', feesCents: null };
        var saved = state.broker;
        brokerHost.innerHTML = '';
        var card = el('div', { class: 'card card-slim plan-broker-card' },
          UI.cardHeader('Record the broker placement'),
          el('p', { class: 'muted' },
            'You executed this exact package at your real broker. StrikeBench records the actual fills in your tracked book — it never places real orders — and this Plan keeps managing the position.'));
        brokerHost.appendChild(card);
        var status = el('div', { class: 'plan-broker-status', 'aria-live': 'polite' });
        card.appendChild(UI.spinner('Loading tracked accounts…'));
        API.getFresh('/api/portfolio/accounts').then(function (data) {
          card.querySelectorAll('.loading').forEach(function (node) { node.remove(); });
          var accounts = (data.accounts || []).filter(function (account) { return account.status === 'ACTIVE'; });
          if (!accounts.length) {
            card.appendChild(UI.emptyState('No tracked account yet',
              'The Book records real brokerage activity. Set up a tracked account first; this decision stays open.',
              'Set up a tracked account', function () { App.navigate('#/portfolio/book/overview'); }));
            return;
          }
          if (!saved.accountId || !accounts.some(function (account) { return account.id === saved.accountId; })) {
            saved.accountId = accounts[0].id;
          }
          function accountName(id) {
            var match = accounts.find(function (account) { return account.id === id; });
            return match ? match.name : 'the tracked account';
          }
          card.appendChild(UI.chipSet({ id: 'plan-broker-account', label: 'Tracked account',
            options: accounts.map(function (account) {
              return { value: account.id, label: account.name,
                sub: account.broker || account.accountType };
            }),
            value: saved.accountId,
            onChange: function (value) { saved.accountId = value; },
            consequence: function (value) {
              return 'Fills are recorded in ' + accountName(value)
                + '; the Book computes profit from them, and the Live band of this Plan follows the position.';
            } }));
          var ref = el('input', { type: 'text', id: 'plan-broker-ref', value: saved.externalRef || '',
            placeholder: 'e.g. order # or confirmation id' });
          var feesInput = el('input', { type: 'number', step: '0.01', min: '0', id: 'plan-broker-fees',
            value: saved.feesCents == null ? '' : (saved.feesCents / 100).toFixed(2), placeholder: '0.00' });
          var fillInputs = (selected.legs || []).map(function (leg, index) {
            var defaultFill = p.legs && p.legs[index] && p.legs[index].mid != null
              ? p.legs[index].mid : leg.entryPrice;
            return el('input', { type: 'number', step: '0.01', min: '0', id: 'plan-broker-fill-' + index,
              value: defaultFill == null ? '' : String(defaultFill) });
          });
          card.appendChild(el('div', { class: 'form-grid plan-broker-form' },
            el('div', { class: 'field' }, el('label', { for: 'plan-broker-ref' }, 'Broker order reference'), ref),
            el('div', { class: 'field' }, el('label', { for: 'plan-broker-fees' }, 'Total fees $'), feesInput)));
          card.appendChild(el('div', { class: 'form-grid plan-broker-fills' },
            (selected.legs || []).map(function (leg, index) {
              return el('div', { class: 'field' },
                el('label', { for: 'plan-broker-fill-' + index }, brokerLegLabel(leg) + ' — fill $ / share'),
                fillInputs[index]);
            })));
          var submit = el('button', { type: 'button', class: 'btn', id: 'plan-broker-submit' }, 'Record placement');
          card.appendChild(el('div', { class: 'plan-decision-actions' },
            el('div', {}, el('p', { class: 'muted' },
              'One record: the frozen decision, the tracked fills, and the Plan-to-position link commit together — or not at all.')),
            el('div', { class: 'btn-row' }, submit)));
          card.appendChild(status);
          submit.onclick = async function () {
            submit.disabled = true; status.innerHTML = '';
            try {
              if (!ref.value.trim()) throw new Error('The broker order or confirmation reference is required.');
              saved.externalRef = ref.value.trim();
              saved.feesCents = dollars(feesInput, 'Fees', true);
              var fills = fillInputs.map(function (input, index) {
                if (!input.value) throw new Error('Leg ' + (index + 1) + ' needs its exact fill price.');
                return { legIndex: index, fillPrice: String(input.value) };
              });
              var live = await PlanStore.get(planRef.plan.id, true);
              var out = await PlanStore.brokerDecision(live, {
                qty: state.qty, proposedNetCents: state.proposedNetCents,
                portfolioAccountId: saved.accountId, externalRef: saved.externalRef,
                feesCents: saved.feesCents, fills: fills, note: state.note,
                ackToken: result.ackToken,
                acknowledgedRisks: required.filter(function (ack) { return state.acks[ack.id]; })
                  .map(function (ack) { return ack.id; })
              });
              state.preview = null;
              await PlanStore.focus(out.plan, 'MANAGE_REVIEW');
            } catch (e) {
              submit.disabled = false;
              status.appendChild(alertBox('danger', 'The placement was not recorded', [e.message]));
            }
          };
        }).catch(function (e) {
          card.querySelectorAll('.loading').forEach(function (node) { node.remove(); });
          card.appendChild(alertBox('danger', 'Tracked accounts could not load', [e.message]));
        });
      }
      if (scenarioSummary) primary.appendChild(scenarioSummary);
      primary.appendChild(decisionMath);
      refresh();
      if (revealResult) requestAnimationFrame(function () {
        decisionActionAnchor.classList.add('arrival-highlight');
        decisionActionAnchor.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      });
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
          el('span', { class: 'badge ' + (action.kind === 'CLOSE' || action.kind === 'SETTLE' ? 'badge-ok' : 'badge-dim') }, planActionLabel(action.kind)),
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
          el('td', {}, reviewLabel(review.category)),
          el('td', {}, reviewLabel(review.benchmarkKind)),
          el('td', {}, review.predictedPop == null ? '—' : fmtPct(review.predictedPop)),
          el('td', {}, review.realizedCents == null ? '—' : pnlSpan(review.realizedCents)));
      }))));
  }

  function receiptPositionCard(position) {
    var legs = position && position.legs || [];
    function fact(label, value, note) {
      return el('div', { class: 'plan-current-position-fact' },
        el('span', { class: 'eyebrow' }, label),
        el('strong', {}, value),
        note ? el('span', { class: 'muted small' }, note) : null);
    }
    return el('section', { class: 'card plan-current-receipt-position' },
      UI.cardHeader('Current Plan position'),
      el('div', { class: 'plan-current-position-summary' },
        fact('Position now', position.identity || 'Open position', 'Latest frozen transformation receipt'),
        position.holdingShares != null
          ? fact('Account shares', String(position.holdingShares),
            position.holdingAvgCostCents == null ? 'All Practice holdings in this symbol' : 'Account average basis ' + fmtMoney(position.holdingAvgCostCents))
          : null,
        fact('Last event', String(position.action || position.positionState || 'TRANSFORMATION').replaceAll('_', ' '),
          UI.fmtDate(position.createdAt || position.marksAsOf))),
      legs.length ? table(['Holding', 'Quantity', 'Basis / mark'], legs.map(function (leg) {
        var holding = leg.instrumentType === 'STOCK' ? leg.symbol + ' shares'
          : [leg.symbol, leg.expiration, leg.strike, leg.optionType].filter(Boolean).join(' ');
        return el('tr', {},
          el('td', {}, holding),
          el('td', {}, String(leg.quantity)),
          el('td', {}, leg.price == null ? 'Not reported' : fmtMoney(Math.round(Number(leg.price) * 100))));
      })) : alertBox('info', 'No open position remains', ['The latest frozen transformation receipt has no surviving legs.']),
      el('div', { class: 'btn-row' },
        el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () {
          App.navigate('#/portfolio');
        } }, 'Open Practice holdings')));
  }

  /** The live strip for a broker-recorded position: identity, account, and broker-reported
   *  fills from the receipt. Profit and tax depth stays in the Book — one accounting surface. */
  function trackedStructureCard(tracked) {
    var legs = tracked.legs || [];
    var open = (tracked.openQuantityRemaining || 0) > 0;
    function fact(label, value, note) {
      return el('div', { class: 'plan-current-position-fact' },
        el('span', { class: 'eyebrow' }, label),
        el('strong', {}, value),
        note ? el('span', { class: 'muted small' }, note) : null);
    }
    return el('section', { class: 'card plan-tracked-structure', id: 'plan-tracked-structure' },
      UI.cardHeader('Live at your broker', el('span', { class: 'badge ' + (open ? 'badge-ok' : 'badge-dim') },
        open ? 'OPEN' : 'CLOSED IN BOOK')),
      el('div', { class: 'plan-current-position-summary' },
        fact('Structure', tracked.label || 'Tracked structure', 'Broker-reported fills, recorded with this decision'),
        fact('Tracked account', tracked.accountName || 'Tracked account', tracked.symbol),
        fact('Recorded', UI.fmtDate(tracked.marksAsOf || tracked.createdAt),
          String(tracked.role || 'ENTRY').replaceAll('_', ' ').toLowerCase() + ' · '
            + String(tracked.positionState || '').replaceAll('_', ' ').toLowerCase())),
      legs.length ? table(['Holding', 'Quantity', 'Fill'], legs.map(function (leg) {
        var holding = leg.instrumentType === 'STOCK' ? leg.symbol + ' shares'
          : [leg.symbol, leg.expiration, leg.strike, leg.optionType].filter(Boolean).join(' ');
        return el('tr', {},
          el('td', {}, holding),
          el('td', {}, String(leg.quantity)),
          el('td', {}, leg.fillPrice == null ? 'Not reported'
            : fmtMoney(Math.round(Number(leg.fillPrice) * 100))));
      })) : null,
      el('p', { class: 'muted small' },
        'Profit, lots, and tax detail for this position live in the Book; this Plan reviews the decision against its declared view.'),
      el('div', { class: 'btn-row' },
        el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-open-book', onclick: function () {
          App.navigate('#/portfolio/book/overview');
        } }, 'Open in Book')));
  }

  function adoptionPct(value) {
    return value == null || value === '' ? '—' : Number(value).toFixed(2).replace(/\.00$/, '') + '%';
  }

  /**
   * Journey C's two questions share the ADOPTION receipt as identity, not arithmetic. Fresh eyes
   * is the canonical tracked-package assessment at today's marks. Campaign-anchored truth is the
   * canonical CampaignService aggregate. Rendering them as siblings prevents sunk cost from
   * leaking into the forward decision and prevents today's marks from rewriting campaign history.
   */
  function adoptionTwoLensCard(review) {
    var beginner = Learn.currentLevel() === 'beginner';
    var anchor = review.anchor || {};
    var fresh = review.freshEyes || {};
    var campaignLens = review.campaignAnchored || {};
    var currentObjective = review.currentObjective;
    var frame = el('section', { class: 'card adoption-review', 'data-adoption-receipt': anchor.receiptId || '' },
      el('div', { class: 'adoption-review-head' },
        el('div', {}, el('span', { class: 'eyebrow' }, 'ONE POSITION · TWO HONEST QUESTIONS'),
          el('h3', {}, 'Judge what remains without losing what happened'),
          el('p', { class: 'muted' }, beginner
            ? 'Today’s choice and the whole campaign are different questions. StrikeBench keeps both visible and never averages them.'
            : 'The current-package DecisionPolicy receipt and the campaign interpretation receipt remain disjoint.')),
        el('div', { class: 'adoption-anchor-chip' },
          el('span', { class: 'badge badge-info' }, 'ADOPTION RECEIPT'),
          el('span', { class: 'mono small' }, anchor.receiptId || 'Unavailable'))));

    function freshEyesCard() {
      var analysisDoc = fresh.analysis || {};
      var evaluation = analysisDoc.evaluation || {};
      var preview = analysisDoc.preview || {};
      var body = el('article', { class: 'adoption-lens adoption-fresh-eyes', 'data-lens': 'fresh-eyes' },
        el('div', { class: 'adoption-lens-title' },
          el('span', { class: 'adoption-lens-number' }, '1'),
          el('div', {}, el('h4', {}, UI.term('fresheyes', 'Fresh-eyes review')),
            el('p', {}, fresh.question || 'Would you open this position today?'))),
        el('p', { class: 'muted small adoption-lens-basis' }, fresh.basis || 'Current marks; sunk cash excluded.'));
      if (!fresh.available || !fresh.analysis) {
        body.appendChild(alertBox('info', 'Current analysis is unavailable', [
          fresh.unavailableReason || 'The adoption baseline remains visible, but current observed pricing is unavailable.'
        ]));
        return body;
      }
      var economics = economicAssessmentBlock({ evaluation: evaluation }, true);
      var coherence = coherenceBlock({ evaluation: evaluation });
      body.appendChild(el('div', { class: 'adoption-lens-facts' },
        stat('Current package price', preview.entryNetPremiumCents == null ? '—'
          : fmtMoney(preview.entryNetPremiumCents, { plus: true })),
        stat(UI.vocabulary('theoreticalMaxLoss'), preview.maxLossCents == null ? '—' : fmtMoney(preview.maxLossCents)),
        stat('Chance of any profit', preview.popEntry == null ? '—' : fmtPct(preview.popEntry))));
      if (economics) body.appendChild(economics);
      if (coherence) body.appendChild(coherence);
      var metrics = decisionMetricsReceipt(evaluation, beginner, 'adoption-' + (anchor.receiptId || 'position'));
      if (metrics) body.appendChild(UI.expandable(beginner ? 'How this position behaves now' : 'Current position receipt',
        function () { return metrics; }, { stateKey: 'adoption-fresh-' + (anchor.receiptId || 'position') }));
      body.appendChild(el('p', { class: 'muted small' },
        (analysisDoc.accountName || anchor.accountName || 'Tracked account') + ' · '
          + (analysisDoc.marketLane || 'UNKNOWN') + ' pricing · '
          + (currentObjective ? 'objective revision ' + currentObjective.revisionNo : 'objective not declared')));
      return body;
    }

    function campaignSummary(campaign) {
      var basis = campaign.economicBasis || {};
      var yieldView = campaign.yield || {};
      var counter = campaign.counterfactuals || {};
      return el('div', { class: 'adoption-campaign-summary', 'data-campaign-id': campaign.id },
        el('div', { class: 'adoption-campaign-title' }, el('b', {}, campaign.title || 'Linked campaign'),
          el('span', { class: 'badge ' + (campaign.status === 'ACTIVE' ? 'badge-ok' : 'badge-dim') },
            String(campaign.status || 'ACTIVE').toLowerCase())),
        el('div', { class: 'adoption-lens-facts' },
          stat(UI.vocabulary('campaignEconomicBasis'), basis.available && basis.perShareCents != null
            ? fmtMoney(basis.perShareCents) + ' / share' : 'Unavailable'),
          stat(UI.vocabulary('realizedVsHeadline'), yieldView.available
            ? adoptionPct(yieldView.realizedPeriodPct) + ' vs ' + adoptionPct(yieldView.headlinePeriodPct)
            : 'Unavailable'),
          stat('Versus cash', counter.cash && counter.cash.available && counter.cash.deltaCents != null
            ? fmtMoney(counter.cash.deltaCents, { plus: true }) : 'Unavailable'),
          stat('Versus buy & hold', counter.buyAndHold && counter.buyAndHold.available
            && counter.buyAndHold.deltaCents != null
            ? fmtMoney(counter.buyAndHold.deltaCents, { plus: true }) : 'Unavailable')),
        yieldView.available ? el('p', { class: 'muted small' },
          'Both yield figures use the same peak committed capital of '
            + fmtMoney(yieldView.peakCommittedCapitalCents) + ' over the same ' + yieldView.days
            + '-day window; annualized only if repeatable.') : null,
        campaign.pendingCount ? alertBox('caution', 'Campaign accounting is incomplete', [
          campaign.pendingCount + ' pending import package' + (campaign.pendingCount === 1 ? '' : 's')
            + ' keep tax-dependent figures withheld.'
        ]) : null);
    }

    function campaignCard() {
      var body = el('article', { class: 'adoption-lens adoption-campaign-lens', 'data-lens': 'campaign-anchored' },
        el('div', { class: 'adoption-lens-title' },
          el('span', { class: 'adoption-lens-number' }, '2'),
          el('div', {}, el('h4', {}, UI.term('campaign', 'Campaign-anchored review')),
            el('p', {}, campaignLens.question || 'What happened across the whole campaign?'))),
        el('p', { class: 'muted small adoption-lens-basis' }, campaignLens.basis || 'Confirmed campaign members only.'));
      if (!campaignLens.available || !(campaignLens.campaigns || []).length) {
        body.appendChild(alertBox('info', 'Campaign economics are not linked yet', [
          campaignLens.unavailableReason || 'The adoption receipt remains frozen; no campaign result was manufactured.'
        ]));
      } else {
        (campaignLens.campaigns || []).forEach(function (campaign) {
          body.appendChild(campaignSummary(campaign));
        });
      }
      body.appendChild(el('p', { class: 'muted small' },
        UI.vocabulary('campaignEconomicBasis'), ' never replaces ', UI.vocabulary('trackedTaxBasis'),
        '. Campaign membership and edits stay in the Book opened from the tracked-position card above.'));
      return body;
    }

    frame.appendChild(el('div', { class: 'adoption-two-lens' }, freshEyesCard(), campaignCard()));
    frame.appendChild(UI.expandable('Exact adopted baseline · ' + (anchor.legs || []).length + ' holding'
      + ((anchor.legs || []).length === 1 ? '' : 's'), function () {
      return el('div', { class: 'adoption-baseline-receipt' },
        el('p', { class: 'muted small' },
          'Frozen ' + UI.fmtDate(anchor.marksAsOf) + ' · ' + (anchor.authority || 'USER_ALLOCATED')
            + ' · ' + (anchor.evidenceLevel || 'UNKNOWN')
            + (anchor.frozenObjectiveRevisionId ? ' · objective receipt ' + anchor.frozenObjectiveRevisionId : ' · no objective was declared yet')),
        table(['Adopted holding', 'Quantity', 'Opening fill', 'Mark at adoption'], (anchor.legs || []).map(function (leg) {
          var holding = leg.instrumentType === 'STOCK' ? leg.symbol + ' shares'
            : [leg.symbol, leg.expiration, leg.strike, leg.optionType].filter(Boolean).join(' ');
          var mark = leg.mid != null ? leg.mid : leg.bid != null && leg.ask != null
            ? (Number(leg.bid) + Number(leg.ask)) / 2 : null;
          return el('tr', {}, el('td', {}, holding), el('td', {}, String(leg.quantity)),
            el('td', {}, leg.openingFill == null ? 'Not reported' : fmtMoney(Math.round(Number(leg.openingFill) * 100))),
            el('td', {}, mark == null ? 'Unavailable' : fmtMoney(Math.round(Number(mark) * 100))));
        })));
    }, { stateKey: 'adoption-baseline-' + (anchor.receiptId || 'position') }));
    return frame;
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
          try { await PlanStore.reviewCash(await PlanStore.get(plan.id, true)); await App.refreshMounted(); }
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
    if (!decision && data.management && data.management.trackedStructure) {
      // An ADOPTED position: the book position is real NOW; the deliberate next step is
      // declaring a view on it so the journey can argue about keeping, changing, or closing it.
      content.appendChild(trackedStructureCard(data.management.trackedStructure));
      (data.adoptionReviews || []).forEach(function (review) {
        content.appendChild(adoptionTwoLensCard(review));
      });
      content.appendChild(el('div', { class: 'plan-next-action' },
        el('div', {}, el('b', {}, 'Adopted as-is — what is your view on it now?'),
          el('p', { class: 'muted' },
            'Declare a view above and the evidence, ranking, and outcome lenses will argue about this exact position: keep it, reshape it, or close it.')),
        el('button', { type: 'button', class: 'btn', onclick: function () {
          var edit = document.getElementById('plan-edit-context');
          if (edit) edit.click();
        } }, 'Declare your view')));
      planManagementTimeline(content, data.management);
      return;
    }
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
              el('span', { class: 'badge ' + (item.status === 'FINISHED' ? 'badge-dim' : 'badge-info') },
                item.status === 'FINISHED' ? 'Completed' : 'In progress'),
              el('span', {}, el('b', {}, UI.rehearsalSelectionLabel(item.selection) + ' path ' + (item.pathIndex + 1))),
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
    if (decision.action === 'BROKER') {
      var tracked = data.management && data.management.trackedStructure;
      if (tracked) content.appendChild(trackedStructureCard(tracked));
      else content.appendChild(alertBox('danger', 'The tracked position is unavailable', [
        'The broker decision is preserved, but its tracked-book artifacts could not be loaded. Nothing was changed.'
      ]));
      renderFrozenPlanDecision(content, plan, decision, true);
      planManagementTimeline(content, data.management);
      return;
    }
    if (!data.trade && data.management && data.management.currentPosition
        && (data.management.currentPosition.legs || []).length) {
      content.appendChild(receiptPositionCard(data.management.currentPosition));
      content.appendChild(el('section', { class: 'card card-slim plan-frozen-expectation' },
        UI.cardHeader('Decision baseline preserved'),
        el('p', { class: 'muted' },
          'The option event changed the live position, while the original frozen decision remains available for review.')));
      renderFrozenPlanDecision(content, plan, decision, true);
      planManagementTimeline(content, data.management);
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
          + ' · ' + UI.economicVerdictLabel(decision.economicVerdict))),
      el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
        var box = document.getElementById('plan-frozen-decision-detail');
        box.hidden = !box.hidden;
      } }, 'Inspect decision')));
    var frozen = el('div', { id: 'plan-frozen-decision-detail', hidden: '' });
    renderFrozenPlanDecision(frozen, plan, decision, true); content.appendChild(frozen);
    var timelineOwner = el('div', { class: 'plan-management-timeline-owner' });
    function paintTimeline(management) {
      timelineOwner.replaceChildren();
      planManagementTimeline(timelineOwner, management);
    }
    await window.ViewPortfolio.tradeDetail(content, [], {
      plan: plan, tradeId: decision.tradeId, data: data.trade,
      onManagedRefresh: function (result) { paintTimeline(result && result.management); }
    });
    content.appendChild(timelineOwner);
    paintTimeline(data.management);
  }

  /* ===== Program ONE band 1: declare the view =====
   * The first move of the journey. The view is USER-DECLARED, never imposed: nothing below
   * this band ranks, simulates, or prices anything until direction and horizon exist. The
   * old hidden "Edit view & limits" editor and its listbox become visible chips here; the
   * frozen-plan linked-revision path is preserved through the same PlanStore semantics.
   */
  var THESIS_CHOICES = [
    { value: 'bullish', label: 'Up', sub: 'I expect it to rise' },
    { value: 'bearish', label: 'Down', sub: 'I expect it to fall' },
    { value: 'neutral', label: 'Sideways', sub: 'I expect a range' },
    { value: 'volatile', label: 'Big move, either way', sub: 'I expect a shake-up' }
  ];
  function thesisLabel(value) {
    var match = THESIS_CHOICES.find(function (t) { return t.value === value; });
    return match ? match.label : 'Not declared';
  }
  function viewDeclared(plan) {
    return planContextDeclared(plan);
  }
  function viewConclusion(plan) {
    var c = plan.context || {};
    var direction = c.thesis ? thesisLabel(c.thesis) : 'Not declared';
    return el('span', { class: 'view-conclusion' },
      el('b', {}, plan.symbol), ' · ', direction,
      ' over ', String(c.horizonDays), ' sessions · ', planIntentLabel(plan.intent),
      c.targetCents ? el('span', { class: 'muted' }, ' · target ' + fmtMoney(c.targetCents)) : null);
  }

  function declarationBand(host, ctx, api) {
    var plan = ctx.plan;
    var c = plan.context || {};
    if (plan.assumptionsEditable === false) {
      // A frozen decision means the declared view is history; revisions branch, never rewrite.
      var editor = planContextEditor(plan);
      editor.hidden = false;
      host.appendChild(editor);
      return;
    }
    var draft = Rails.surface('plan:' + plan.id + ':declaration',
      { thesis: c.thesis || null, horizonDays: c.horizonDays || null, intent: plan.intent || null,
        targetCents: c.targetCents || null, riskMode: c.riskMode || null,
        assignmentPreference: c.assignmentPreference || null });
    host.appendChild(el('p', { class: 'muted' },
      'Say what you believe before anything ranks or simulates. Every later band tests this — '
      + 'and tells you when your positions stop expressing it.'));
    host.appendChild(UI.symbolContext({ symbol: plan.symbol, mode: 'locked', compact: true }));

    if (!draft.intent) {
      var intents = el('div', { class: 'choice-grid plan-intent-grid' });
      (Learn.INTENTS || []).forEach(function (meta) {
        intents.appendChild(el('button', { type: 'button', class: 'choice-card', onclick: async function () {
          try {
            var live = await PlanStore.get(plan.id, true);
            ctx.plan = await PlanStore.claimIntent(live, meta.key);
            draft.intent = meta.key;
            if (ctx.refreshHeader) ctx.refreshHeader();
            api.refreshBand('view');
          } catch (e) { UI.toast(e.message, 'error'); }
        } }, el('b', {}, meta.label), el('span', {}, meta.story || meta.blurb)));
      });
      host.appendChild(UI.field('What should this Plan do?', intents, { className: 'declaration-goal' }));
      return; // direction/horizon follow once the goal exists — one decision at a time.
    }

    var thesisControl = UI.chipSet({
      id: 'plan-thesis',
      label: 'Your view on ' + plan.symbol, info: 'thesis',
      options: THESIS_CHOICES, value: draft.thesis,
      onChange: function (v) { draft.thesis = v; },
      consequence: function (v) {
        return v
          ? 'Proposals, evidence, and simulations will all be judged against "' + thesisLabel(v).toLowerCase()
            + '" over your horizon.'
          : 'Declare a direction — nothing ranks against an unstated view.';
      }
    });
    var horizonControl = UI.segmented({
      label: 'Over the next', info: 'horizon',
      options: [
        { value: 10, label: '2 weeks', detail: '10 sessions' },
        { value: 21, label: '1 month', detail: '21 sessions' },
        { value: 63, label: '3 months', detail: '63 sessions' },
        { value: 0, label: 'Custom' }],
      value: [10, 21, 63].indexOf(draft.horizonDays) >= 0 ? draft.horizonDays : (draft.horizonDays ? 0 : null),
      onChange: function (v) {
        if (v === 0) { customHorizon.hidden = false; customHorizon.querySelector('input').focus(); }
        else { customHorizon.hidden = true; draft.horizonDays = v; }
      },
      consequence: function (v) {
        var days = v === 0 ? draft.horizonDays : v;
        return days ? 'Studies and expirations will focus on roughly ' + days + ' trading sessions out.' : '';
      }
    });
    var customInput = el('input', { id: 'plan-horizon-days', type: 'number', min: '1', max: '730',
      value: draft.horizonDays || '', oninput: function () { draft.horizonDays = Number(this.value) || null; } });
    var customHorizon = el('div', { class: 'field', hidden: [10, 21, 63].indexOf(draft.horizonDays) >= 0 || !draft.horizonDays ? '' : null },
      el('label', { for: 'plan-horizon-days' }, 'Trading sessions'), customInput);
    var target = el('input', { id: 'plan-target-price', type: 'number', min: '0.01', step: '0.01',
      value: draft.targetCents ? (draft.targetCents / 100).toFixed(2) : '',
      oninput: function () { draft.targetCents = this.value ? Math.round(Number(this.value) * 100) : null; } });
    var risk = UI.chipSet({
      label: 'Risk budget', info: 'riskbudget',
      options: [
        { value: 'conservative', label: 'Cautious' },
        { value: 'balanced', label: 'Standard' },
        { value: 'aggressive', label: 'High' }],
      value: draft.riskMode,
      onChange: function (v) { draft.riskMode = v; }
    });
    // Assignment preference is part of the DECLARED view for goals where assignment is real:
    // it feeds the DecisionPolicy's "Assignment fit" factor. Direction-only goals never ask.
    var assignmentBearing = ['ACQUIRE', 'EXIT', 'INCOME'].indexOf(plan.intent) >= 0;
    var assignment = assignmentBearing ? UI.chipSet({
      id: 'plan-assignment-preference',
      label: 'If assignment comes up',
      options: [
        { value: '', label: 'No preference' },
        { value: 'AVOID', label: 'Avoid it' },
        { value: 'ACCEPT', label: 'Fine either way' },
        { value: 'PREFER_BELOW_BASIS', label: 'Welcome below my basis' },
        { value: 'SEEK', label: 'Assignment is the point' }],
      value: draft.assignmentPreference || '',
      onChange: function (v) { draft.assignmentPreference = v || null; },
      consequence: function (v) {
        return v === 'AVOID' ? 'Structures with high odds of being assigned rank lower for you.'
          : v === 'ACCEPT' ? 'Assignment odds never reweight the ranking.'
          : v === 'PREFER_BELOW_BASIS' ? 'Being assigned shares below your basis counts in a structure\'s favor; having shares called away counts against it.'
          : v === 'SEEK' ? 'Higher odds of being assigned count in a structure\'s favor (the wheel).'
          : 'Optional. Declare it and every proposed structure gains an "Assignment fit" ranking factor.';
      }
    }) : null;
    var save = el('button', { type: 'button', class: 'btn', id: 'plan-declare-view', onclick: async function () {
      if (!draft.thesis) {
        UI.toast('Declare a direction first — up, down, sideways, or big move.', 'error'); return;
      }
      if (!draft.horizonDays) { UI.toast('Pick a horizon — how long does this view get to play out?', 'error'); return; }
      if (!draft.riskMode) { UI.toast('Choose how much risk this Plan may take.', 'error'); return; }
      save.disabled = true;
      try {
        var updated = await PlanStore.updateContext(plan, {
          thesis: draft.thesis, horizonDays: draft.horizonDays,
          targetCents: draft.targetCents, riskMode: draft.riskMode,
          assignmentPreference: draft.assignmentPreference,
          clear: (draft.targetCents ? [] : ['targetCents']).concat(draft.thesis ? [] : ['thesis'])
            .concat(draft.assignmentPreference ? [] : ['assignmentPreference'])
        });
        App.context.update({ symbol: updated.symbol, goal: updated.intent,
          thesis: updated.context.thesis, horizon: updated.context.horizonDays + 'd' });
        ctx.plan = updated;
        if (ctx.refreshHeader) ctx.refreshHeader();
        api.fold('view'); // declaring is an explicit save-and-advance: conclude the band
        // Attention moves to the evidence interrogation (the journey's next question);
        // reopen both opens and scrolls — a bare scroll would land on a folded stub.
        api.reopen('evidence');
      } catch (e) { UI.toast(e.message, 'error'); save.disabled = false; }
    } }, viewDeclared(plan) ? 'Update the view' : 'Declare the view');
    host.appendChild(el('div', { class: 'declaration-grid' },
      thesisControl, horizonControl, customHorizon,
      UI.field('Price you care about (optional)', target), risk, assignment));

    // Goal and structure remain changeable until a decision freezes them — the affordances
    // the old context editor carried live here now, with the same confirmation semantics.
    var goalChoices = el('div', { class: 'choice-grid plan-intent-grid plan-fork-intents', hidden: '' });
    (Learn.INTENTS || []).filter(function (meta) { return meta.key !== plan.intent; }).forEach(function (meta) {
      goalChoices.appendChild(el('button', { type: 'button', class: 'choice-card', onclick: function () {
        UI.confirmModal('Change this Plan to ' + meta.label + '?',
          el('div', {},
            el('p', {}, 'The ticker and assumptions stay. Evidence, proposed trades and outcome runs that depend on the old goal become stale.'),
            el('p', { class: 'muted' }, 'You return to Strategy to choose a new structure or option type.')),
          'Change goal', async function () {
            try {
              var live = await PlanStore.get(plan.id, true);
              ctx.plan = await PlanStore.claimIntent(live, meta.key);
              App.context.update({ symbol: ctx.plan.symbol, goal: ctx.plan.intent,
                thesis: ctx.plan.context.thesis, horizon: ctx.plan.context.horizonDays + 'd' });
              // A changed goal means choosing a NEW structure — land on the Builder, as promised.
              PlanStore.ui(ctx.plan.id).strategyView = 'builder';
              await PlanStore.focus(ctx.plan, 'STRATEGY');
            } catch (e) { UI.toast((e && e.message) || 'The goal could not be changed.', 'error'); }
          });
      } }, el('b', {}, meta.label), el('span', {}, meta.story || meta.blurb)));
    });
    var changeGoal = el('button', { type: 'button', class: 'btn btn-secondary', id: 'plan-change-goal',
      'aria-expanded': 'false', onclick: function () {
        goalChoices.hidden = !goalChoices.hidden;
        changeGoal.setAttribute('aria-expanded', String(!goalChoices.hidden));
      } }, 'Change goal');
    var changeStructure = el('button', { type: 'button', class: 'btn btn-secondary',
      id: 'plan-change-structure', onclick: function () {
        PlanStore.ui(plan.id).strategyView = 'builder';
        api.reopen('strategy');
      } }, 'Choose structure or option type');
    host.appendChild(el('p', { class: 'muted' }, 'Goal: ', el('b', {}, planIntentLabel(plan.intent)),
      '. You can change the goal and structure until you record a decision.'));
    host.appendChild(el('div', { class: 'btn-row' }, save, changeGoal, changeStructure));
    host.appendChild(goalChoices);
    if (viewDeclared(plan)) {
      host.appendChild(el('p', { class: 'muted small' },
        'Changing the view marks evidence, proposed trades, and outcome runs from the old view stale — '
        + 'your study setup is kept.'));
    }
  }

  /* ===== Program ONE: the Workspace flow =====
   * One document of bands replaces the stage rail: declare → evidence → structure →
   * outcomes on it → commit → live. Bands lock with visible reasons, collapse to their
   * conclusions when done, and never hide a capability.
   */
  var FLOW_BANDS_BY_STAGE = { understand: 'view', evidence: 'evidence', strategy: 'strategy',
    outcomes: 'outcomes', decide: 'commit', 'manage-review': 'live' };
  var FLOW_STAGE_BY_BAND = { view: 'UNDERSTAND', evidence: 'EVIDENCE', strategy: 'STRATEGY',
    outcomes: 'OUTCOMES', commit: 'DECIDE', live: 'MANAGE_REVIEW' };

  async function planWorkspace(root, params) {
    var id = params[0] || '';
    var rawStage = (params[1] || 'understand').split('?')[0];
    // Band postures gate on plan status — they must never be computed from a stale cached
    // copy (a background list refresh can hold a pre-decision snapshot of this very plan).
    var plan = await PlanStore.get(id, true);
    var targetWorld = plan.marketKind === 'SIMULATED' ? plan.worldId : plan.marketKind === 'DEMO' ? 'demo' : 'observed';
    if (App.state.world !== targetWorld) { await PlanStore.focus(plan, rawStage); return; }
    App.state.activePlanByMarket = App.state.activePlanByMarket || {};
    App.state.activePlanByMarket[PlanStore.marketKey(plan)] = plan.id;
    App.state.activePlanId = plan.id;
    if (window.Workspace) Workspace.save();

    var ctx = { plan: plan };
    function navigatePlanBand(band) {
      var target = PlanStore.path(ctx.plan, FLOW_STAGE_BY_BAND[band] || 'UNDERSTAND');
      if (window.location.hash === target && flow) return flow.reopen(band);
      App.navigate(target);
    }
    // "Edit view & limits" is a toggle: it opens the view band, and a second press returns
    // attention to the band the user was working in (never stranding a live position folded).
    var editReturnBand = null;
    function toggleViewEditor() {
      var viewPosture = flow.posture('view');
      if (viewPosture === 'active' || viewPosture === 'revisit') {
        var back = editReturnBand;
        editReturnBand = null;
        if (back) { navigatePlanBand(back); return; }
        flow.fold('view');
        return;
      }
      editReturnBand = ['evidence', 'strategy', 'outcomes', 'commit', 'live'].find(function (key) {
        var posture = flow.posture(key);
        return posture === 'active' || posture === 'revisit';
      }) || null;
      navigatePlanBand('view');
    }
    var header = planHeader(plan, false, toggleViewEditor);
    root.appendChild(header);
    // Band actions that change the plan refresh the header facts in place — never a
    // route-level repaint for a field save.
    ctx.refreshHeader = function () {
      var next = planHeader(ctx.plan, false, toggleViewEditor);
      header.replaceWith(next);
      header = next;
      paintPlanQuote();
    };
    var _pt = App.navToken;
    function paintPlanQuote() {
      if (!(App.Market && App.state.world && App.state.world !== 'observed')) return;
      var row = App.Market.get(ctx.plan.symbol);
      var wrap = document.getElementById('plan-live-quote');
      var px = document.getElementById('plan-live-px');
      if (!row || !wrap || !px) return;
      wrap.hidden = false;
      px.textContent = fmtNum(row.last);
      var d = px.nextElementSibling;
      if (d && d.classList.contains('delta')) d.replaceWith(UI.delta(row.last, row.prevClose));
      else px.insertAdjacentElement('afterend', UI.delta(row.last, row.prevClose));
    }
    if (App.Market && App.state.world && App.state.world !== 'observed') {
      App.Market.subscribe(function () { if (App.alive(_pt)) paintPlanQuote(); }, _pt);
      paintPlanQuote();
    }
    function decisionDone() {
      return ['DECIDED_CASH', 'POSITION_OPEN', 'CLOSED'].indexOf(ctx.plan.status || 'DRAFT') >= 0;
    }
    function structureReady() {
      var stages = PLAN_STAGES.map(function (s) { return s.key; });
      // A STALE prior selection keeps the outcomes/commit bands reachable: their content is
      // the reprice affordance, which must never hide behind the lock it exists to resolve.
      if (ctx.selectionState === 'STALE' || ctx.selectionState === 'CURRENT') return true;
      return stages.indexOf(ctx.plan.furthestStage || 'UNDERSTAND') >= stages.indexOf('OUTCOMES');
    }
    var arrivalFocus = FLOW_BANDS_BY_STAGE[rawStage];
    if (arrivalFocus === 'view' && !params[1]) arrivalFocus = null;
    var flow = Flow.render({ id: 'plan-flow', stateKey: 'plan:' + plan.id + ':flow', ctx: ctx,
      focus: arrivalFocus || null, sections: [
      { key: 'view', title: 'Your view', info: 'thesis',
        onOpen: function () { navigatePlanBand('view'); },
        complete: function () { return viewDeclared(ctx.plan); },
        render: function (host, c, api) { declarationBand(host, c, api); },
        conclusion: function () { return viewConclusion(ctx.plan); } },
      { key: 'evidence', title: 'Does the evidence agree?',
        onOpen: function () { navigatePlanBand('evidence'); },
        // Ready before declaration on purpose: exploring history and simulated futures is HOW
        // a view forms — the futures tool here can adopt a tried view into the band above.
        // Only ranking (the strategy band) hard-requires the declared view. Optional: it
        // interrogates the journey rather than gating it, so it never holds default attention.
        ready: function () { return true; },
        optional: true,
        retainOnFold: true, // the fan canvas and study results survive folding by construction
        complete: function () { return false; },
        invitation: function () {
          // The futures tool owns its state at PlanStore.ui(id).evidence (the workbench surface).
          var evidenceUi = PlanStore.ui(ctx.plan.id).evidence || {};
          var fingerprint = evidenceUi.planEnsembleFingerprint;
          return fingerprint
            ? el('span', {}, 'Evidence consulted — simulation ',
                el('b', {}, '#' + String(fingerprint).slice(0, 6)),
                el('span', { class: 'muted' }, ' and its analysis are kept here'))
            : 'Optional — test your view against history and simulated futures before ranking.';
        },
        render: function (host, c, api) {
          if (!viewDeclared(ctx.plan)) {
            host.appendChild(el('p', { class: 'muted flow-band-note' },
              'No view declared yet — explore what history and simulated futures say, then adopt '
              + 'the view you believe. Nothing ranks until a view is declared above.'));
          }
          var owned = planOwnedStage(host, ctx.plan, planStageByPath('evidence'));
          return research(owned, ['__plan', ctx.plan.symbol, 'evidence'], {
            plan: ctx.plan, stage: 'evidence', onContextChanged: async function (updated) {
              ctx.plan = updated;
              if (ctx.refreshHeader) ctx.refreshHeader();
              await api.refreshBand('evidence');
            }
          });
        } },
      { key: 'strategy', title: 'How to express it',
        onOpen: function () { navigatePlanBand('strategy'); },
        ready: function () { return viewDeclared(ctx.plan); },
        lockedReason: function () { return 'Declare your view above — structures are ranked against it.'; },
        complete: function () { return structureReady(); },
        invitation: function () {
          return 'Rank the complete field against your view, or compose your own structure.';
        },
        render: function (host) { return planStrategyStage(host, ctx.plan, planStageByPath('strategy')); },
        conclusion: function () {
          // The conclusion carries the RESULT: the exact selected structure, visible on any
          // arrival without reopening the band.
          if (ctx.selectedSummary) {
            return el('span', { class: 'plan-selected-structure' },
              'Selected structure: ', el('b', {}, ctx.selectedSummary.name),
              ctx.selectedSummary.qty > 1 ? ' ×' + ctx.selectedSummary.qty : '',
              el('span', { class: 'muted' }, ' — open to compare or change it'));
          }
          return 'Structure selected — open to compare or change it';
        } },
      { key: 'outcomes', title: 'Outcomes on your structure',
        onOpen: function () { navigatePlanBand('outcomes'); },
        ready: function () { return structureReady(); },
        lockedReason: function () {
          return 'Unlocks after you select a structure above — then market odds, possible futures '
            + '(Monte Carlo), past analogs, and rule replay all run on your exact contracts.';
        },
        complete: function () { return false; },
        invitation: function () {
          return 'Test your selected structure: market odds, possible futures, and past analogs.';
        },
        render: function (host) { return planOutcomesStage(host, ctx.plan, planStageByPath('outcomes')); } },
      { key: 'commit', title: 'Commit',
        onOpen: function () { navigatePlanBand('commit'); },
        ready: function () { return structureReady(); },
        lockedReason: function () { return 'Unlocks after you select a structure above.'; },
        complete: function () { return decisionDone(); },
        invitation: function () {
          return 'Price the decision and commit — practice it, stay in cash, or record your broker placement.';
        },
        render: function (host) { return planDecideStage(host, ctx.plan, planStageByPath('decide')); },
        conclusion: function () {
          return ctx.plan.status === 'DECIDED_CASH' ? 'Decided: stayed in cash — review below'
            : 'Decision frozen — the position is live below';
        } },
      { key: 'live', title: 'Live: manage & review',
        onOpen: function () { navigatePlanBand('live'); },
        // A frozen decision OR a managed rehearsal makes this band live — rehearsals are
        // managed positions too (this also retires the old rail bug where the tooltip
        // promised rehearsals unlock review but the button stayed disabled).
        ready: function () {
          return decisionDone() || (ctx.plan.furthestStage === 'MANAGE_REVIEW');
        },
        lockedReason: function () { return 'Appears once you commit — trade it, rehearse it, or deliberately stay in cash.'; },
        complete: function () { return false; },
        render: function (host) { return planManageStage(host, ctx.plan, planStageByPath('manage-review')); } }
    ] });
    root.appendChild(flow.el);
    // The route is not ready merely because the band chrome exists. Async embedded producers
    // (notably Research inside Evidence) finish before App publishes data-ready=true.
    await flow.whenReady();
    if (arrivalFocus) {
      // An explicit stage deep-link carries intent: the flow already opened the target as
      // its attention focus (folding what attention left behind); bring it into view.
      flow.scrollTo(arrivalFocus);
    }
    // The in-place seam: navigating between stages of THIS plan updates the live document
    // (app.js seam branch) — attention moves, bands repaint, nothing tears down. Any error
    // there falls through to the full render, which remains the correctness owner.
    async function reloadMountedPlan() {
      ctx.plan = await PlanStore.get(plan.id, true);
      if (ctx.refreshHeader) ctx.refreshHeader();
      await flow.refresh(true);
    }
    App._flowSeam = { key: 'plan:' + plan.id, apply: async function (stage, generation) {
      var updatedPlan = await PlanStore.get(plan.id, true);
      if (generation != null && generation !== App._renderRequestGeneration) return;
      ctx.plan = updatedPlan;
      if (ctx.refreshHeader) ctx.refreshHeader();
      var band = FLOW_BANDS_BY_STAGE[stage];
      if (band) {
        await flow.setFocus(band);
        if (generation != null && generation !== App._renderRequestGeneration) return;
        flow.scrollTo(band);
        refineSelectionPosture(band);
      } else {
        await flow.setFocus(null);
      }
    }, refreshLens: reloadMountedPlan, reload: reloadMountedPlan };
    // Async posture refinement: a stale prior selection (context bump rolled progress back)
    // unlocks outcomes/commit so their reprice affordances are reachable. One fetch, no poll.
    var _ft = App.navToken;
    function refineSelectionPosture(focusBand) {
      if (decisionDone() || structureReady()) return;
      PlanStore.latestOutcomes(plan.id).then(function (latest) {
        if (!App.alive(_ft) || !latest || !latest.selectionState) return;
        if (latest.selectionState !== 'NONE' && ctx.selectionState !== latest.selectionState) {
          ctx.selectionState = latest.selectionState;
          flow.refreshBand('outcomes');
          // A deep-link that arrived at a locked band regains its attention the moment the
          // refinement unlocks it — the user asked for THIS band; honor it once it's real.
          if (focusBand && flow.posture(focusBand) === 'ready') {
            flow.reopen(focusBand);
          }
        }
      }).catch(function () { /* posture stays conservative on fetch failure */ });
    }
    refineSelectionPosture(arrivalFocus);
    // Conclusion enrichment: the concluded strategy band names the exact selected structure.
    if (structureReady() && flow.posture('strategy') === 'done') {
      PlanStore.latestStrategy(plan.id).then(function (latest) {
        var selected = latest && latest.selected;
        if (!App.alive(_ft) || !selected || flow.posture('strategy') !== 'done') return;
        ctx.selectedSummary = { name: selected.displayName || selected.strategy || 'Selected package',
          qty: Number(selected.qty) || 1 };
        // This only enriches the existing folded conclusion node. Repainting the band (or its
        // successors) would steal focus and briefly tear down the live Manage workspace even
        // though no readiness or completion fact changed.
        flow.refreshConclusion('strategy');
      }).catch(function () { /* the generic conclusion stands */ });
    }
  }

  async function renderPlanLibrary(host, options) {
    options = options || {};
    var owner = host;
    // The library has one mounted owner, but several legitimate data signals can ask it to
    // reconcile at once (for example the archive command and its plan.updated SSE event).
    // Both renders await server reads; without an owner generation, an older render can
    // resume after the newer one cleared the host and append a second set of groups. Only
    // the newest render for this exact owner may commit after an async boundary.
    var renderGeneration = (owner._planLibraryRenderGeneration || 0) + 1;
    owner._planLibraryRenderGeneration = renderGeneration;
    function isCurrentRender() {
      return owner._planLibraryRenderGeneration === renderGeneration;
    }
    async function repaintOwner() {
      if (owner.isConnected) await renderPlanLibrary(owner, options);
    }
    // Build off-DOM across every awaited market/portfolio read. An SSE refresh may legitimately
    // start a newer render while this one is waiting; keeping the prior complete tree mounted
    // preserves row identity and clickability until the newest generation commits atomically.
    host = document.createElement('div');
    function commit() {
      if (!isCurrentRender()) return false;
      owner.replaceChildren.apply(owner, Array.from(host.childNodes));
      return true;
    }
    var full = options.full === true;
    var compact = options.compact === true;
    var countLabel = el('span', { class: 'muted small plan-library-count' }, 'Loading…');
    host.appendChild(UI.cardHeader(options.title || 'Plans',
      el('div', { class: 'btn-row plan-library-commands' },
        countLabel,
        !full ? el('a', { class: 'btn btn-sm btn-secondary', href: '#/home' }, 'View all') : null,
        !compact ? el('button', { type: 'button', class: 'btn btn-sm', onclick: function () {
          App.navigate('#/research');
        } }, '+ New Plan') : null)));
    var loading = UI.spinner('Loading your Plans…');
    host.appendChild(loading);

    var plans;
    try { plans = await PlanStore.library(true); }
    catch (e) {
      if (!isCurrentRender()) return;
      loading.remove();
      host.appendChild(alertBox('warn', 'Plan library unavailable', [e.message]));
      commit();
      return;
    }
    if (!isCurrentRender()) return;
    loading.remove();
    var currentKey = PlanStore.currentMarketKey();
    var working = plans.filter(function (p) { return p.status !== 'ARCHIVED' && p.open !== false; });
    var closedTabs = plans.filter(function (p) { return p.status !== 'ARCHIVED' && p.open === false; });
    var archived = plans.filter(function (p) { return p.status === 'ARCHIVED'; });
    var current = working.filter(function (p) { return PlanStore.marketKey(p) === currentKey; });
    var elsewhere = working.filter(function (p) { return PlanStore.marketKey(p) !== currentKey; });
    var compactWorking = compact ? working.filter(function (plan) {
      return !options.excludePlanId || plan.id !== options.excludePlanId;
    }) : working;
    // The alert center drives the needs-attention ordering (spec 10.1): a plan carrying an
    // alert is enriched in place — never duplicated as a second row saying the same thing —
    // and outranks quiet plans by its worst severity, then recency.
    var alertList = compact && options.alerts ? options.alerts : [];
    var SEV_RANK = { URGENT: 3, ATTENTION: 2, INFO: 1 };
    var alertsByPlan = {};
    alertList.forEach(function (a) {
      if (a.planId && compactWorking.some(function (p) { return p.id === a.planId; })) {
        (alertsByPlan[a.planId] = alertsByPlan[a.planId] || []).push(a);
      }
    });
    var looseAlerts = alertList.filter(function (a) {
      return !(a.planId && alertsByPlan[a.planId]);
    });
    function planAlertRank(plan) {
      var rows = alertsByPlan[plan.id] || [];
      return rows.reduce(function (best, a) { return Math.max(best, SEV_RANK[a.severity] || 0); }, 0);
    }
    var compactOrdered = compact ? compactWorking.slice().sort(function (a, b) {
      var alertGap = planAlertRank(b) - planAlertRank(a);
      if (alertGap) return alertGap;
      var aHere = PlanStore.marketKey(a) === currentKey ? 1 : 0;
      var bHere = PlanStore.marketKey(b) === currentKey ? 1 : 0;
      if (aHere !== bHere) return bHere - aHere;
      return String(b.updatedAt || b.createdAt || '').localeCompare(String(a.updatedAt || a.createdAt || ''));
    }) : [];
    var compactShown = compactOrdered.slice(0, 3);
    if ((compact && !compactWorking.length && !looseAlerts.length)
        || (!compact && !working.length && !archived.length && !closedTabs.length)) {
      if (options.removeWhenEmpty) { if (isCurrentRender()) owner.remove(); return; }
      host.appendChild(UI.emptyState('No working Plans yet',
        'Choose a stock in Research, then carry one Plan through evidence, strategy, outcomes, and a decision.',
        'Open Research', function () { App.navigate('#/research'); }));
      commit();
      return;
    }

    var quoteBySymbol = {};
    var portfolioByPlan = {};
    var sessionById = {};
    try {
      var marketRows = compact ? compactShown : current;
      var symbols = Array.from(new Set(marketRows.filter(function (p) {
        return PlanStore.marketKey(p) === currentKey;
      }).map(function (p) { return p.symbol; })));
      var fills = await Promise.all([
        symbols.length ? API.get('/api/quotes?symbols=' + symbols.join(',')) : Promise.resolve({ quotes: [] }),
        compact ? Promise.resolve({ plans: [] }) : API.get('/api/plans/portfolio'),
        marketRows.some(function (p) { return p.marketKind === 'SIMULATED'; })
          ? API.get('/api/sim/market') : Promise.resolve({ sessions: [] })
      ]);
      (fills[0].quotes || []).forEach(function (q) { quoteBySymbol[q.symbol] = q; });
      (fills[1].plans || []).forEach(function (row) { portfolioByPlan[row.plan.id] = row; });
      (fills[2].sessions || []).forEach(function (session) { sessionById[session.id] = session; });
    } catch (e2) { /* Navigation remains available without decorative marks. */ }
    if (!isCurrentRender()) return;

  function stageName(plan) {
      var stage = PLAN_STAGES.find(function (item) { return item.key === (plan.furthestStage || 'UNDERSTAND'); });
      return stage ? stage.label : 'Understand';
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
        onclick: function () { confirmArchivePlan(plan, false, repaintOwner); } }, icon('archive', 15), ' Archive'));
      if (plan.assumptionsEditable === true) actions.appendChild(el('button', { type: 'button',
        class: 'btn btn-sm btn-secondary', 'aria-label': 'Delete draft ' + plan.title,
        onclick: function () { confirmDeletePlan(plan, false, repaintOwner); } }, icon('trash', 15), ' Delete'));
      var actionLabel = decision.action === 'CASH' ? 'Cash decision'
        : row.tradeId ? 'Position open' : plan.status === 'CLOSED' ? 'Reviewed' : 'Working';
      return el('article', { class: 'home-plan-tile' + (plan.id === App.state.activePlanId ? ' active' : ''),
        'data-plan-id': plan.id },
        el('div', { class: 'home-plan-tile-head' },
          el('div', {}, el('div', { class: 'eyebrow' }, plan.symbol + ' · ' + planIntentLabel(plan.intent)),
            el('h3', {}, planIdentity.title)),
          el('div', { class: 'home-plan-badges' },
            planIdentity.duplicate && /^Plan /.test(planIdentity.duplicate)
              ? el('span', { class: 'badge badge-info plan-duplicate-badge' }, planIdentity.duplicate) : null,
            full ? el('span', { class: 'badge ' + (row.tradeId ? 'badge-ok' : decision.action === 'CASH' ? 'badge-caution' : 'badge-dim') }, actionLabel) : null,
            el('span', { class: 'badge ' + (sameMarket ? 'badge-info' : 'badge-dim') }, planMarketLabel(plan)))),
        el('div', { class: 'home-plan-meta' },
          chip('Stage', stageName(plan)),
          plan.context && plan.context.horizonDays ? chip(UI.term('horizon', 'Horizon'), plan.context.horizonDays + ' sessions') : null,
          plan.context && plan.context.thesis ? chip('View', plan.context.thesis) : null,
          (!plan.context || !plan.context.thesis) && planIdentity.duplicate === 'View not set'
            ? chip('View', 'not set') : null,
          plan.context && plan.context.targetCents ? chip('Target', fmtMoney(plan.context.targetCents)) : null,
          full && decision.economicVerdict ? chip('Decision', UI.economicVerdictLabel(decision.economicVerdict)) : null,
          full && decision.pop != null ? chip(UI.term('pop', 'POP at decision'), fmtPct(decision.pop)) : null,
          planIdentity.updated ? el('span', { class: 'muted small plan-updated-at' }, planIdentity.updated) : null,
          live), actions);
    }

    if (compact) {
      countLabel.textContent = (alertList.length
        ? alertList.length + (alertList.length === 1 ? ' attention item · ' : ' attention items · ') : '')
        + compactOrdered.length + (options.excludePlanId ? ' other ' : ' working ')
        + (compactOrdered.length === 1 ? 'Plan' : 'Plans');
      var alertBadgeClass = function (severity) {
        return severity === 'URGENT' ? 'badge-danger' : severity === 'ATTENTION' ? 'badge-warn' : 'badge-dim';
      };
      var openAlert = function (alert) {
        if (options.onAlertOpen) { options.onAlertOpen(alert); return; }
        if (alert.deepLink) App.navigate(alert.deepLink);
      };
      var alertLine = function (alert, extraCount) {
        return el('div', { class: 'home-alert-line' },
          el('span', { class: 'badge ' + alertBadgeClass(alert.severity) },
            String(alert.severityLabel || '').toUpperCase()),
          el('button', { type: 'button', class: 'home-alert-headline', 'data-alert-id': alert.id,
            onclick: function () { openAlert(alert); } }, alert.headline),
          extraCount > 0 ? el('span', { class: 'muted small' },
            '+' + extraCount + ' more on this position') : null);
      };
      // An alert with no Plan row of its own becomes its own attention row; alerts that map
      // to a shown Plan enrich that row instead — never two rows saying the same thing.
      var alertRow = function (alert) {
        var laneLabel = alert.lane === 'TRACKED' ? 'Tracked account'
          : alert.lane === 'SIMULATED' ? 'Simulated session'
            : alert.lane === 'DEMO' ? 'Demo' : alert.lane ? 'Practice' : null;
        var metaLine = [alert.symbol, alert.accountName, laneLabel].filter(Boolean).join(' · ');
        return el('article', { class: 'home-plan-compact-row home-alert-row',
          'data-alert-severity': String(alert.severity || '').toLowerCase(),
          'data-alert-kind': alert.kind, 'data-alert-id': alert.id },
          el('div', { class: 'home-plan-compact-main' },
            alertLine(alert, 0),
            metaLine ? el('div', { class: 'muted small home-plan-compact-meta' }, metaLine) : null,
            el('p', { class: 'muted small home-alert-detail' }, alert.detail || '')),
          el('div', { class: 'btn-row home-plan-compact-actions' },
            el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
              onclick: function () { openAlert(alert); } },
            alert.kind === 'ASSIGNMENT' ? 'Preview' : 'Open')));
      };
      var compactPlanRow = function (plan) {
        var sameMarket = PlanStore.marketKey(plan) === currentKey;
        var terminalSession = plan.marketKind === 'SIMULATED' && sessionById[plan.worldId]
          && sessionById[plan.worldId].status === 'FINISHED';
        var quote = sameMarket ? quoteBySymbol[plan.symbol] : null;
        var identity = PlanStore.identity(plan, working);
        var meta = [stageName(plan)];
        if (plan.context && plan.context.horizonDays) meta.push(plan.context.horizonDays + ' sessions');
        if (plan.context && plan.context.thesis) meta.push('View ' + plan.context.thesis);
        else if (identity.duplicate === 'View not set') meta.push('View not set');
        if (plan.context && plan.context.targetCents) meta.push('Target ' + fmtMoney(plan.context.targetCents));
        var planAlerts = (alertsByPlan[plan.id] || []).slice().sort(function (a, b) {
          return (SEV_RANK[b.severity] || 0) - (SEV_RANK[a.severity] || 0);
        });
        var rowAttrs = { class: 'home-plan-compact-row', 'data-plan-id': plan.id };
        if (planAlerts.length) rowAttrs['data-alert-severity'] = String(planAlerts[0].severity).toLowerCase();
        return el('article', rowAttrs,
          el('div', { class: 'home-plan-compact-main' },
            el('div', { class: 'home-plan-compact-title' },
              el('b', {}, plan.symbol), el('span', {}, identity.title)),
            el('div', { class: 'muted small home-plan-compact-meta' }, meta.join(' · ')),
            planAlerts.length ? alertLine(planAlerts[0], planAlerts.length - 1) : null),
          el('div', { class: 'home-plan-compact-state' },
            identity.duplicate && /^Plan /.test(identity.duplicate)
              ? el('span', { class: 'badge badge-info' }, identity.duplicate) : null,
            el('span', { class: 'badge ' + (sameMarket ? 'badge-info' : 'badge-dim') }, planMarketLabel(plan)),
            quote ? el('span', { class: 'home-plan-live' }, fmtNum(quote.last), ' ', UI.delta(quote.last, quote.prevClose)) : null),
          el('div', { class: 'btn-row home-plan-compact-actions' },
            el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
              if (terminalSession) {
                App.state.focusSimControlRoom = plan.worldId;
                App.navigate('#/data/simulation');
                return;
              }
              focusPlanFrom(this, plan, plan.furthestStage);
            } }, terminalSession ? 'Review session' : sameMarket ? 'Open' : 'Switch & open'),
            // The one chip-bar value the resume rows lacked: putting a journey away without
            // archiving it (it stays one click away in the library).
            el('button', { type: 'button', class: 'btn btn-sm btn-secondary home-plan-compact-close',
              title: 'Put this Plan away — it stays in the library', 'aria-label': 'Close ' + plan.symbol + ' tab',
              onclick: function (event) {
                event.stopPropagation();
                PlanStore.closeChip(plan).catch(function (e) { UI.toast(e.message, 'error'); });
              } }, icon('x', 14))));
      };
      // Severity first, then recency: the alert center owns the needs-attention ordering.
      var LOOSE_LIMIT = 8;
      var shownLoose = looseAlerts.slice(0, LOOSE_LIMIT);
      var entries = shownLoose.map(function (alert) {
        return { rank: SEV_RANK[alert.severity] || 0, at: String(alert.at || ''), node: alertRow(alert) };
      }).concat(compactShown.map(function (plan) {
        var planAlerts = alertsByPlan[plan.id] || [];
        var top = planAlerts.length ? planAlerts[0] : null;
        return { rank: planAlertRank(plan), at: top ? String(top.at || '')
          : String(plan.updatedAt || plan.createdAt || ''), node: compactPlanRow(plan) };
      }));
      entries.sort(function (a, b) { return (b.rank - a.rank) || b.at.localeCompare(a.at); });
      host.appendChild(el('div', { class: 'home-plan-compact-list' },
        entries.map(function (entry) { return entry.node; })));
      var moreAlerts = looseAlerts.length - shownLoose.length;
      if (compactOrdered.length > compactShown.length || moreAlerts > 0) {
        host.appendChild(el('p', { class: 'muted small home-plan-compact-note' },
          moreAlerts > 0 ? moreAlerts + ' more attention item' + (moreAlerts === 1 ? '' : 's') + ' \u00b7 ' : '',
          (compactOrdered.length - compactShown.length) + ' more in ',
          el('a', { href: '#/home', onclick: function () { App.state.openPlanDrawer = true; } }, 'the Plan library'), '.'));
      }
      commit();
      return;
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
    }, { stateKey: 'plans-archived' }));
    if (closedTabs.length) host.appendChild(UI.expandable('Closed Plan tabs (' + closedTabs.length + ')', function () {
      return el('div', { class: 'home-plan-archive home-plan-closed-tabs' }, closedTabs.map(function (plan) {
        var actions = el('div', { class: 'btn-row' },
          el('button', { type: 'button', class: 'btn btn-sm', onclick: function () {
            focusPlanFrom(this, plan, plan.furthestStage);
          } }, PlanStore.marketKey(plan) === currentKey ? 'Reopen' : 'Switch market & reopen'));
        if (plan.assumptionsEditable === true) actions.appendChild(el('button', { type: 'button',
          class: 'btn btn-sm btn-secondary', onclick: function () { confirmDeletePlan(plan, false, repaintOwner); } },
        icon('trash', 14), ' Delete draft'));
        else if (plan.status !== 'POSITION_OPEN') actions.appendChild(el('button', { type: 'button',
          class: 'btn btn-sm btn-secondary', onclick: function () { confirmArchivePlan(plan, false, repaintOwner); } },
        icon('archive', 14), ' Archive'));
        return el('div', { class: 'status-item' }, el('b', {}, plan.symbol), el('span', {}, plan.title),
          el('span', { class: 'badge badge-dim' }, planMarketLabel(plan)), actions);
      }));
    }, { stateKey: 'plans-closed-tabs' }));
    commit();
  }


  window.ViewPlan = Object.freeze({
    stages: PLAN_STAGES,
    planWorkspace: planWorkspace,
    
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
    economicAssessmentBlock: economicAssessmentBlock,
    decisionMetricsReceipt: decisionMetricsReceipt,
    ideaPresentation: ideaPresentation,
    candidatesNearTie: candidatesNearTie,
    nearTieScorePoints: NEAR_TIE_SCORE_POINTS,
    openCandidateAsPlan: openCandidateAsPlan,
    candidateFromEvaluation: candidateFromEvaluation,
    candidateViable: candidateViable
  });
})();
