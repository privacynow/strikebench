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

  async function refreshTradeDetailNear(node, tradeId, title, detail) {
    var host = node && node.closest ? node.closest('.trade-detail-content') : null;
    if (!host) { await App.render(); return; }
    host.setAttribute('aria-busy', 'true');
    try {
      API.flushCache();
      var data = await API.getFresh('/api/trades/' + tradeId);
      host.replaceChildren();
      await tradeDetail(host, [tradeId], { data: data, insideContent: true });
      var feedback = UI.actionFeedback('ok', title, detail);
      host.prepend(feedback);
      feedback.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      feedback.classList.add('arrival-highlight');
    } finally {
      host.removeAttribute('aria-busy');
    }
  }

  function practiceTransformationRequest(trade, plan, action, previewToken) {
    var request = {
      source: 'PRACTICE_TRADE', sourceId: trade.id, action: action
    };
    if (plan) {
      request.planId = plan.id;
      request.expectedPlanVersion = plan.version;
    }
    if (previewToken) request.previewToken = previewToken;
    return request;
  }

  function transformationState(label, identity, state) {
    return el('div', { class: 'position-transformation-state' },
      el('span', { class: 'eyebrow' }, label),
      el('strong', {}, identity && identity.label ? identity.label : state),
      identity && identity.summary ? el('span', { class: 'muted small' }, identity.summary) : null);
  }

  function transformationFact(label, value, valueClass) {
    return el('div', { class: 'position-transformation-fact' },
      el('span', { class: 'muted small' }, label),
      el('strong', { class: valueClass || '' }, value));
  }

  function closeTransformationBody(preview) {
    var change = preview.transformation;
    var beforeRisk = change.beforeRisk || {};
    var afterRisk = change.afterRisk || { maxLossCents: 0, reserveCents: 0 };
    return el('div', { id: 'close-transformation-preview' },
      el('div', { class: 'position-transformation-route', 'aria-label': 'Position before and after closing' },
        transformationState('Before', change.beforeIdentity, 'Open position'),
        el('span', { class: 'position-transformation-arrow', 'aria-hidden': 'true' }, '→'),
        transformationState('After', change.afterIdentity, 'Cash / no position')),
      el('div', { class: 'position-transformation-facts' },
        transformationFact('Closing cash flow', fmtMoney(preview.closingCashCents, { plus: true })),
        transformationFact('Close fees', fmtMoney(preview.closingFeesCents)),
        transformationFact('Final position P/L', fmtMoney(change.realizedClosingCents, { plus: true }),
          Number(change.realizedClosingCents) >= 0 ? 'gain' : 'loss'),
        transformationFact('Broker reserve released', fmtMoney(beforeRisk.reserveCents || 0)),
        transformationFact('Theoretical max loss open now', fmtMoney(beforeRisk.maxLossCents)),
        transformationFact('Theoretical max loss after', fmtMoney(afterRisk.maxLossCents || 0))),
      change.warnings && change.warnings.length
        ? alertBox('warn', 'Review what changes', change.warnings) : null,
      el('p', { class: 'muted small position-transformation-note' },
        'Closing cash flow is not profit. Final position P/L includes the opening premium, opening fees, executable closing prices, and close fees.'));
  }

  async function openCloseTransformation(trade, managedPlan, button) {
    var request = practiceTransformationRequest(trade, managedPlan, 'CLOSE');
    var preview = await visibleCommand(button, function () {
      return API.post('/api/position-transformations/preview', request);
    }, 'The current position could not be priced for closing.');
    if (!preview) return;
    UI.confirmModal('Close this position?', closeTransformationBody(preview), 'Close position', async function () {
      var applied = await API.post('/api/position-transformations/apply',
        practiceTransformationRequest(trade, managedPlan, 'CLOSE', preview.previewToken));
      if (managedPlan && applied.plan) {
        await PlanStore.focus(applied.plan, 'MANAGE_REVIEW');
      } else {
        await refreshTradeDetailNear(button, trade.id, 'Position closed',
          'The realized result, released reserve, and closing cash are now reflected below.');
      }
    });
  }

  async function openVoidTransformation(trade, managedPlan, button) {
    var request = practiceTransformationRequest(trade, managedPlan, 'VOID');
    var preview = await visibleCommand(button, function () {
      return API.post('/api/position-transformations/preview', request);
    }, 'The practice entry could not be prepared for voiding.');
    if (!preview) return;
    var change = preview.transformation;
    UI.confirmModal('Void this practice entry?', el('div', {},
      el('div', { class: 'position-transformation-route', 'aria-label': 'Practice record before and after voiding' },
        transformationState('Before', change.beforeIdentity, 'Open practice position'),
        el('span', { class: 'position-transformation-arrow', 'aria-hidden': 'true' }, '→'),
        transformationState('After', change.afterIdentity, 'Voided practice record')),
      el('div', { class: 'position-transformation-facts' },
        transformationFact('Broker reserve released', fmtMoney(change.beforeRisk && change.beforeRisk.reserveCents || 0)),
        transformationFact('Recorded position result', 'Removed from Practice P/L')),
      UI.alertBox('warn', 'Practice correction, not an exit', [
        'Entry cash and fees are reversed instead of realizing a market close.',
        'The frozen receipt and audit history remain visible. Real broker activity cannot be voided.'
      ])), 'Void practice entry', async function () {
        var applied = await API.post('/api/position-transformations/apply',
          practiceTransformationRequest(trade, managedPlan, 'VOID', preview.previewToken));
        if (managedPlan && applied.plan) await PlanStore.focus(applied.plan, 'MANAGE_REVIEW');
        else await refreshTradeDetailNear(button, trade.id, 'Practice entry voided',
          'Entry cash, fees, and reserve were reversed; the signed transformation receipt remains in history.');
      }, true);
  }

  function lifecycleActionLabel(action) {
    return ({ ASSIGNMENT: 'Assignment', EXERCISE: 'Exercise', EXPIRATION: 'Expiration' })[action] || action;
  }

  function cashSettledIndexOption(symbol) {
    var normalized = String(symbol || '').trim().toUpperCase().replace(/^_/, '');
    var known = App.config && Array.isArray(App.config.broadBasedIndexOptionSymbols)
      ? App.config.broadBasedIndexOptionSymbols : [];
    return known.some(function (item) { return String(item).toUpperCase() === normalized; });
  }

  function lifecycleShareResult(lifecycle) {
    var shares = Number(lifecycle.sharesDelta || 0);
    if (shares > 0) return shares + ' shares acquired at the strike';
    if (shares < 0) return Math.abs(shares) + ' shares delivered at the strike';
    return 'No share delivery';
  }

  function lifecycleAccountingFacts(preview) {
    var lifecycle = preview.lifecycle;
    var stockCash = Number(lifecycle.stockCashCents || 0);
    return el('div', { class: 'position-transformation-facts position-lifecycle-accounting' },
      Number(lifecycle.optionSettlementCashCents || 0) !== 0
        ? transformationFact('Option settlement cash', fmtMoney(lifecycle.optionSettlementCashCents, { plus: true })) : null,
      transformationFact(stockCash < 0 ? 'Strike cash used' : stockCash > 0 ? 'Strike cash received' : 'Strike cash',
        fmtMoney(Math.abs(stockCash))),
      transformationFact('Practice cash after', fmtMoney(lifecycle.projectedCashAfterCents)),
      transformationFact('Broker reserve before', fmtMoney(lifecycle.reserveBeforeCents)),
      transformationFact('Broker reserve after', fmtMoney(lifecycle.reserveAfterCents)),
      transformationFact('Selected-leg entry basis', fmtMoney(preview.allocatedEntryBasisCents, { plus: true })),
      transformationFact('Selected opening fees', fmtMoney(preview.allocatedOpenFeesCents)));
  }

  function renderLifecycleReview(host, preview, trade, managedPlan, action, legIndex) {
    host.innerHTML = '';
    var lifecycle = preview.lifecycle;
    var change = preview.transformation;
    var realizedFacts = el('div', { class: 'position-transformation-facts position-lifecycle-result' },
      transformationFact('Option result realized', fmtMoney(preview.actionRealizedPnlCents, { plus: true }),
        Number(preview.actionRealizedPnlCents) >= 0 ? 'gain' : 'loss'),
      transformationFact('Realized on position to date', fmtMoney(preview.realizedPnlToDateCents, { plus: true }),
        Number(preview.realizedPnlToDateCents) >= 0 ? 'gain' : 'loss'));
    var primaryFacts = el('div', { class: 'position-transformation-facts' },
      transformationFact('Event', lifecycleActionLabel(action)),
      transformationFact('Selected contract', lifecycle.contract),
      transformationFact('Underlying reference', fmtMoney(lifecycle.settlementUnderlyingCents)),
      transformationFact('Share result', lifecycleShareResult(lifecycle)));
    var accounting = lifecycleAccountingFacts(preview);
    var review = el('div', { class: 'position-lifecycle-review', id: 'option-lifecycle-preview' },
      UI.actionFeedback('ok', lifecycleActionLabel(action) + ' reviewed',
        'Nothing has changed yet. Apply only after the contract, shares, and cash below match the event you are recording.'),
      el('div', { class: 'position-transformation-route', 'aria-label': 'Position before and after option lifecycle event' },
        transformationState('Before', change.beforeIdentity, 'Current position'),
        el('span', { class: 'position-transformation-arrow', 'aria-hidden': 'true' }, '→'),
        transformationState('After', change.afterIdentity, 'Cash / no remaining position')),
      primaryFacts,
      realizedFacts,
      Learn.currentLevel() === 'expert' ? accounting
        : UI.expandable('See exact option accounting and reserve changes', function () { return accounting; }),
      el('div', { class: 'position-lifecycle-basis' },
        el('span', { class: 'eyebrow' }, 'SETTLEMENT BASIS'),
        el('strong', {}, lifecycle.settlementPriceBasis),
        el('span', { class: 'muted small' }, 'Expiration ', lifecycle.expiration,
          ' · option conversion and stock strike cash stay separate.')),
      change.warnings && change.warnings.length
        ? alertBox('warn', 'Risk identity after this event', change.warnings) : null,
      preview.basisNotes && preview.basisNotes.length
        ? alertBox('info', 'How basis is carried', preview.basisNotes) : null,
      change.applicable ? el('button', { type: 'button', class: 'btn', id: 'apply-option-lifecycle-btn',
        onclick: async function (event) {
          var request = practiceTransformationRequest(trade, managedPlan, action, preview.previewToken);
          request.legIndex = legIndex;
          var applied = await visibleCommand(event.currentTarget, function () {
            return API.post('/api/position-transformations/apply', request);
          }, 'The reviewed option event could not be applied.');
          if (!applied) return;
          if (managedPlan && applied.plan) await PlanStore.focus(applied.plan, 'MANAGE_REVIEW');
          else await refreshTradeDetailNear(host, trade.id, lifecycleActionLabel(action) + ' recorded',
            lifecycleShareResult(lifecycle) + '. Cash, reserve, and surviving contracts are refreshed below.');
        }
      }, 'Apply reviewed ' + lifecycleActionLabel(action).toLowerCase())
        : alertBox('danger', 'This option event cannot be applied',
          change.afterRisk && change.afterRisk.blockReasons || ['Review the resulting position and account constraints.']));
    host.appendChild(review);
    host.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  async function openOptionLifecycleTransformation(trade, managedPlan, button, host) {
    host.hidden = false;
    host.innerHTML = '';
    if (managedPlan) managedPlan = await PlanStore.get(managedPlan.id, true);
    var optionLegs = (trade.legs || []).map(function (leg, index) { return { leg: leg, index: index }; })
      .filter(function (row) { return String(row.leg.type).toUpperCase() !== 'STOCK'; });
    var cashSettled = cashSettledIndexOption(trade.symbol);
    var reviewHost = el('div', { class: 'position-lifecycle-result', 'aria-live': 'polite' });
    var legGrid = el('div', { class: 'position-lifecycle-grid' });
    function reviewEvent(event, row, action) {
      var actionButton = event.currentTarget;
      legGrid.querySelectorAll('.position-lifecycle-leg').forEach(function (node) {
        node.classList.toggle('is-selected', node === actionButton.closest('.position-lifecycle-leg'));
      });
      legGrid.querySelectorAll('button').forEach(function (node) {
        node.setAttribute('aria-pressed', node === actionButton ? 'true' : 'false');
      });
      reviewHost.innerHTML = '';
      reviewHost.appendChild(UI.spinner('Rechecking the lane clock, contract, shares, and account…'));
      var request = practiceTransformationRequest(trade, managedPlan, action);
      request.legIndex = row.index;
      var failure = null;
      visibleCommand(actionButton, function () {
        return API.post('/api/position-transformations/preview', request).catch(function (error) {
          failure = error;
          throw error;
        });
      }, 'This option event could not be reviewed.').then(function (preview) {
        if (preview) {
          renderLifecycleReview(reviewHost, preview, trade, managedPlan, action, row.index);
          return;
        }
        reviewHost.innerHTML = '';
        reviewHost.appendChild(UI.actionFeedback('danger', 'This event is not ready to record',
          failure && failure.message ? failure.message : 'The lane clock or current account facts do not support this event.'));
        reviewHost.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      });
    }
    optionLegs.forEach(function (row) {
      var physicalAction = String(row.leg.action).toUpperCase() === 'SELL' ? 'ASSIGNMENT' : 'EXERCISE';
      var card = el('article', { class: 'position-lifecycle-leg' },
        el('div', { class: 'position-lifecycle-leg-copy' },
          el('span', { class: 'eyebrow' }, 'LEG ' + (row.index + 1)),
          el('strong', {}, legLabel(row.leg)),
          el('span', { class: 'muted small' }, cashSettled
            ? 'This broad-based index option settles in cash and never delivers shares.'
            : physicalAction === 'ASSIGNMENT'
              ? 'A short option may be assigned when it has intrinsic value.'
              : 'A long option may be exercised when it has intrinsic value.')),
        el('div', { class: 'position-lifecycle-actions' },
          cashSettled ? null : el('button', { type: 'button', class: 'btn btn-secondary btn-sm', 'aria-pressed': 'false',
            onclick: function (event) { reviewEvent(event, row, physicalAction); } },
          'Review ' + lifecycleActionLabel(physicalAction).toLowerCase()),
          el('button', { type: 'button', class: 'btn btn-secondary btn-sm', 'aria-pressed': 'false',
            onclick: function (event) { reviewEvent(event, row, 'EXPIRATION'); } },
          'Review expiration')));
      legGrid.appendChild(card);
    });
    host.append(
      el('div', { class: 'position-lifecycle-intro' },
        el('span', { class: 'eyebrow' }, 'OPTION EVENT'),
        el('h3', {}, 'Record what happened to one exact contract'),
        el('p', { class: 'muted small' },
          cashSettled
            ? 'Choose the exact contract to review its cash settlement at expiration. StrikeBench uses this market’s clock and settlement evidence before anything changes.'
            : 'Choose the contract and event. StrikeBench uses this market’s clock and price evidence, then shows the resulting shares, cash, reserve, and surviving risk before anything changes.')),
      optionLegs.length ? legGrid : UI.emptyState('No option legs remain', 'This position has no option contract to assign, exercise, or expire.'),
      reviewHost);
    host.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function renderPartialCloseReview(host, preview, trade, managedPlan, closeQuantity) {
    host.innerHTML = '';
    var change = preview.transformation;
    var remaining = Number(trade.qty) - closeQuantity;
    var beforeRisk = change.beforeRisk || {};
    var afterRisk = change.afterRisk || {};
    host.appendChild(el('div', { class: 'position-partial-review' },
      el('div', { class: 'position-transformation-route', 'aria-label': 'Position before and after partial close' },
        transformationState('Before', change.beforeIdentity, 'x' + trade.qty + ' open'),
        el('span', { class: 'position-transformation-arrow', 'aria-hidden': 'true' }, '→'),
        transformationState('After', change.afterIdentity, 'x' + remaining + ' remain open')),
      el('div', { class: 'position-transformation-facts' },
        transformationFact('Closing now', closeQuantity + ' of ' + trade.qty),
        transformationFact('Still open', remaining + ' package' + (remaining === 1 ? '' : 's')),
        transformationFact('Closing cash flow', fmtMoney(preview.closingCashCents, { plus: true })),
        transformationFact('Close fees', fmtMoney(preview.closingFeesCents)),
        transformationFact('Realized by this close', fmtMoney(preview.actionRealizedPnlCents, { plus: true }),
          Number(preview.actionRealizedPnlCents) >= 0 ? 'gain' : 'loss'),
        transformationFact('Realized on this position to date', fmtMoney(preview.realizedPnlToDateCents, { plus: true }),
          Number(preview.realizedPnlToDateCents) >= 0 ? 'gain' : 'loss'),
        transformationFact('Broker reserve before', fmtMoney(beforeRisk.reserveCents || 0)),
        transformationFact('Broker reserve after', fmtMoney(afterRisk.reserveCents || 0)),
        transformationFact('Theoretical max loss before', fmtMoney(beforeRisk.maxLossCents)),
        transformationFact('Theoretical max loss after', fmtMoney(afterRisk.maxLossCents))),
      change.warnings && change.warnings.length
        ? alertBox('warn', 'Review what changes', change.warnings) : null,
      el('p', { class: 'muted small position-transformation-note' },
        'The surviving packages keep their original fills, opening-fee basis, and trade identity. Only the selected quantity closes; ledger, reserve, Plan action, and receipt commit together.'),
      change.applicable ? el('button', {
        type: 'button', class: 'btn', id: 'apply-partial-close-btn', onclick: async function (event) {
          var request = practiceTransformationRequest(trade, managedPlan, 'PARTIAL_CLOSE', preview.previewToken);
          request.closeQuantity = closeQuantity;
          var applied = await visibleCommand(event.currentTarget, function () {
            return API.post('/api/position-transformations/apply', request);
          }, 'The reviewed partial close could not be applied.');
          if (!applied) return;
          if (managedPlan && applied.plan) await PlanStore.focus(applied.plan, 'MANAGE_REVIEW');
          else await refreshTradeDetailNear(host, trade.id, 'Partial close recorded',
            'Closed ' + closeQuantity + '; ' + applied.trade.qty + ' remain open with their original history.');
        }
      }, 'Apply partial close') : alertBox('danger', 'This partial close cannot be applied',
        change.afterRisk && change.afterRisk.blockReasons || ['Review the resulting position.'])));
    host.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  async function openPartialCloseTransformation(trade, managedPlan, button, host) {
    host.hidden = false;
    host.innerHTML = '';
    if (managedPlan) managedPlan = await PlanStore.get(managedPlan.id, true);
    var quantity = el('input', { type: 'number', id: 'partial-close-quantity', min: '1',
      max: String(Number(trade.qty) - 1), step: '1', value: '1', inputmode: 'numeric' });
    var summary = el('strong', { id: 'partial-close-summary' });
    var review = el('div', { class: 'position-partial-result', 'aria-live': 'polite' });
    function sync() {
      var closeQuantity = Number(quantity.value);
      var valid = Number.isInteger(closeQuantity) && closeQuantity > 0 && closeQuantity < Number(trade.qty);
      summary.textContent = valid
        ? 'Close ' + closeQuantity + ' · ' + (Number(trade.qty) - closeQuantity) + ' remain'
        : 'Choose 1 to ' + (Number(trade.qty) - 1);
      analyze.disabled = !valid;
      review.innerHTML = '';
    }
    var analyze = el('button', { type: 'button', class: 'btn', id: 'review-partial-close-btn',
      onclick: async function (event) {
        var closeQuantity = Number(quantity.value);
        var request = practiceTransformationRequest(trade, managedPlan, 'PARTIAL_CLOSE');
        request.closeQuantity = closeQuantity;
        var preview = await visibleCommand(event.currentTarget, function () {
          return API.post('/api/position-transformations/preview', request);
        }, 'The selected quantity could not be priced for a partial close.');
        if (preview) renderPartialCloseReview(review, preview, trade, managedPlan, closeQuantity);
      } }, 'Review partial close');
    quantity.addEventListener('input', sync);
    host.append(
      el('div', { class: 'position-partial-controls' },
        el('div', {}, el('span', { class: 'eyebrow' }, 'Partial close'),
          el('h3', {}, 'Reduce this position without resetting its history'),
          el('p', { class: 'muted small' }, 'Close whole packages; the remainder keeps the same entry basis and trade identity.')),
        el('div', { class: 'position-partial-command' },
          UI.field('Packages to close', quantity), summary, analyze)),
      review);
    sync();
    host.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function nextRollExpiration(current, expirations) {
    if (!current) return '';
    var date = new Date(current + 'T00:00:00Z');
    date.setUTCDate(date.getUTCDate() + 28);
    var target = date.toISOString().slice(0, 10);
    return expirations.find(function (value) { return value >= target; })
      || expirations.find(function (value) { return value > current; }) || current;
  }

  function rollDraftFromTrade(trade, expirations) {
    var legs = (trade.legs || []).map(function (leg) {
      var stock = String(leg.type).toUpperCase() === 'STOCK';
      return { instrumentType: stock ? 'STOCK' : 'OPTION', action: leg.action,
        positionEffect: 'OPEN', symbol: trade.symbol,
        optionType: stock ? null : leg.type, strike: stock ? '' : String(leg.strike),
        expiration: stock ? '' : nextRollExpiration(leg.expiration, expirations),
        quantity: Number(leg.ratio || 1) * Number(trade.qty || 1),
        multiplier: stock ? 1 : Number(leg.multiplier || 100), price: '', section1256: null };
    });
    return { symbol: trade.symbol, legs: legs, packageNet: '', fillNature: 'PROPOSED',
      feeMode: 'DEFAULT', fees: '', chainExpiration: legs.find(function (leg) {
        return leg.instrumentType === 'OPTION';
      })?.expiration || '' };
  }

  function reviewedRollPayload(payload, trade) {
    return Object.assign({}, payload, { thesis: trade.thesis, horizon: trade.horizon,
      riskMode: trade.riskMode, intent: trade.intent, useHeldShares: trade.sharesLocked > 0,
      source: 'POSITION_TRANSFORMATION', fillNature: 'PROPOSED' });
  }

  function renderRollReview(host, preview, afterPayload, trade, managedPlan, editor, stateKey) {
    host.innerHTML = '';
    var change = preview.transformation;
    var afterReview = preview.after || {};
    var after = afterReview.preview || {};
    var required = afterReview.requiredAcks || [];
    var guardrails = afterReview.guardrails || {};
    var warnings = [].concat(change.warnings || [], guardrails.warnings || [], after.warnings || []);
    host.appendChild(el('div', { class: 'position-roll-review' },
      el('div', { class: 'position-transformation-route', 'aria-label': 'Position before and after rolling' },
        transformationState('Before', change.beforeIdentity, 'Current position'),
        el('span', { class: 'position-transformation-arrow', 'aria-hidden': 'true' }, '→'),
        transformationState('After', change.afterIdentity, 'Replacement position')),
      el('div', { class: 'position-transformation-facts' },
        transformationFact('Closing cash flow', fmtMoney(preview.closingCashCents, { plus: true })),
        transformationFact('Realized on closing leg', fmtMoney(change.realizedClosingCents, { plus: true }),
          Number(change.realizedClosingCents) >= 0 ? 'gain' : 'loss'),
        transformationFact(after.entryNetPremiumCents >= 0 ? 'Replacement credit' : 'Replacement cost',
          fmtMoney(Math.abs(after.entryNetPremiumCents || 0))),
        transformationFact('Total close + open fees', fmtMoney(Number(preview.closingFeesCents || 0)
          + Number(after.feesOpenCents || 0))),
        transformationFact('Theoretical max loss before', fmtMoney(change.beforeRisk.maxLossCents)),
        transformationFact('Theoretical max loss after', fmtMoney(change.afterRisk.maxLossCents)),
        transformationFact('Broker reserve before', fmtMoney(change.beforeRisk.reserveCents || 0)),
        transformationFact('Broker reserve after', fmtMoney(change.afterRisk.reserveCents || 0))),
      warnings.length ? alertBox('warn', 'Review what changes', Array.from(new Set(warnings))) : null,
      required.length ? alertBox('caution', 'Material risks to acknowledge', required.map(function (item) {
        return item.label;
      })) : null,
      change.applicable ? null : alertBox('danger', 'This replacement cannot be applied',
        (change.afterRisk && change.afterRisk.blockReasons || guardrails.blockReasons || ['Review the replacement constraints above.'])),
      el('p', { class: 'muted small position-transformation-note' },
        'The old result stays realized. The replacement starts fresh at executable prices. Close, open, Plan link, ledger, and receipt commit together or nothing changes.'),
      change.applicable ? el('button', { type: 'button', class: 'btn', id: 'apply-roll-btn', onclick: async function (event) {
        var button = event.currentTarget;
        var current = reviewedRollPayload(editor.readAnalysis(), trade);
        if (JSON.stringify(current) !== JSON.stringify(afterPayload)) {
          host.innerHTML = '';
          host.appendChild(UI.actionFeedback('caution', 'Analyze the edited replacement again',
            'The contracts changed after this receipt was created. No position was changed.'));
          return;
        }
        var applyAfter = JSON.parse(JSON.stringify(afterPayload));
        if (required.length) {
          applyAfter.acknowledgedRisks = required.map(function (item) { return item.id; });
          applyAfter.ackToken = afterReview.ackToken;
        }
        var request = practiceTransformationRequest(trade, managedPlan, 'ROLL', preview.previewToken);
        request.after = applyAfter;
        var applied = await visibleCommand(button, function () {
          return API.post('/api/position-transformations/apply', request);
        }, 'The reviewed roll could not be applied.');
        if (!applied) {
          host.appendChild(UI.actionFeedback('danger', 'The position was not changed',
            'Review current prices and analyze the replacement again.'));
          return;
        }
        if (App.state.positionDrafts) delete App.state.positionDrafts[stateKey];
        UI.toast('Roll applied · prior result realized · replacement open', 'ok');
        if (managedPlan && applied.plan) await PlanStore.focus(applied.plan, 'MANAGE_REVIEW');
        else App.navigate('#/portfolio/trade/' + applied.trade.id);
      } }, required.length ? 'Apply roll & acknowledge risks' : 'Apply reviewed roll') : null));
  }

  async function openRollTransformation(trade, managedPlan, button, host) {
    host.hidden = false;
    host.innerHTML = '';
    host.appendChild(UI.spinner('Loading valid expirations…'));
    var market = await visibleCommand(button, function () {
      return API.getFresh('/api/research/' + encodeURIComponent(trade.symbol) + '/expirations');
    }, 'The replacement expirations could not be loaded.');
    if (!market) {
      host.innerHTML = '';
      host.appendChild(UI.actionFeedback('danger', 'Could not start the roll',
        'Current expirations are unavailable in this market.'));
      return;
    }
    if (managedPlan) managedPlan = await PlanStore.get(managedPlan.id, true);
    var expirations = market.expirations || [];
    var stateKey = 'practice-roll:' + trade.id;
    var editorHost = el('div', { class: 'position-roll-editor' });
    var reviewHost = el('div', { class: 'position-roll-result', 'aria-live': 'polite' });
    host.innerHTML = '';
    host.append(editorHost, reviewHost);
    var reviewed = null;
    var editor = PositionEditor.render(editorHost, {
      stateKey: stateKey, lockedSymbol: trade.symbol, chain: true,
      title: 'Build the replacement',
      description: 'The current position stays open while you edit. Move expirations, strikes, ratios, or quantity; blank fills price at executable sides. Review shows both positions before one atomic Apply.',
      analyzeLabel: 'Review this exact roll', initial: rollDraftFromTrade(trade, expirations),
      onStateChange: function (draft) {
        if (!reviewed) return;
        try {
          var current = reviewedRollPayload(PositionEditor.analysisPayload(draft), trade);
          if (JSON.stringify(current) === JSON.stringify(reviewed.after)) return;
        } catch (ignored) {
          // An incomplete edit invalidates the reviewed receipt until it is analyzable again.
        }
        reviewed = null;
        reviewHost.innerHTML = '';
        reviewHost.appendChild(UI.actionFeedback('caution', 'Replacement changed',
          'Analyze again before Apply. The current position remains untouched.'));
      },
      onAnalyze: async function (payload) {
        var after = reviewedRollPayload(payload, trade);
        var request = practiceTransformationRequest(trade, managedPlan, 'ROLL');
        request.after = after;
        var out = await API.post('/api/position-transformations/preview', request);
        reviewed = { preview: out, after: after };
        renderRollReview(reviewHost, out, after, trade, managedPlan, editor, stateKey);
        return { preview: out.after.preview, evaluation: out.after.evaluation, identity: out.after.identity };
      }
    });
    host.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function adjustmentDraftFromTrade(trade) {
    var legs = (trade.legs || []).map(function (leg) {
      var stock = String(leg.type).toUpperCase() === 'STOCK';
      return { instrumentType: stock ? 'STOCK' : 'OPTION', action: leg.action,
        positionEffect: 'OPEN', symbol: trade.symbol,
        optionType: stock ? null : leg.type, strike: stock ? '' : String(leg.strike),
        expiration: stock ? '' : leg.expiration,
        quantity: Number(leg.ratio || 1) * Number(trade.qty || 1),
        multiplier: stock ? 1 : Number(leg.multiplier || 100), price: '', section1256: null };
    });
    return { symbol: trade.symbol, legs: legs, packageNet: '', fillNature: 'PROPOSED',
      feeMode: 'DEFAULT', fees: '', chainExpiration: legs.find(function (leg) {
        return leg.instrumentType === 'OPTION';
      })?.expiration || '' };
  }

  function reviewedAdjustmentPayload(payload, trade) {
    return Object.assign({}, payload, {
      legs: (payload.legs || []).map(function (leg) {
        return Object.assign({}, leg, { entryPrice: null, positionEffect: 'OPEN' });
      }),
      proposedNetCents: null, feesOverrideCents: null,
      thesis: trade.thesis, horizon: trade.horizon, riskMode: trade.riskMode,
      intent: trade.intent, useHeldShares: Number(trade.sharesLocked || 0) > 0,
      source: 'POSITION_TRANSFORMATION', fillNature: 'PROPOSED'
    });
  }

  function compositionKey(leg) {
    var rawType = String(leg.type || leg.instrumentType || '').toUpperCase();
    var stock = rawType === 'STOCK';
    var optionType = stock ? 'STOCK' : String(leg.type || leg.optionType || '').toUpperCase();
    var numericStrike = Number(leg.strike);
    var strike = stock || leg.strike == null || leg.strike === '' ? ''
      : Number.isFinite(numericStrike) ? String(numericStrike) : String(leg.strike).trim();
    return [String(leg.action || '').toUpperCase(), optionType,
      strike,
      stock ? '' : String(leg.expiration || ''), String(stock ? 1 : Number(leg.multiplier || 100))].join('|');
  }

  function compositionAmounts(legs, packageQuantity, payloadShape) {
    var out = {};
    (legs || []).forEach(function (leg) {
      var key = compositionKey(leg);
      var rawType = String(leg.type || leg.instrumentType || '').toUpperCase();
      var amount = payloadShape ? Number(packageQuantity) * Number(leg.ratio || 1)
        : Number(leg.ratio || 1) * Number(packageQuantity);
      if (!Number.isInteger(amount) || amount <= 0) throw new Error('Every surviving leg needs a positive whole quantity.');
      if (!out[key]) out[key] = { amount: 0, stock: rawType === 'STOCK' };
      out[key].amount += amount;
    });
    return out;
  }

  function deriveAdjustmentAction(trade, after) {
    var before = compositionAmounts(trade.legs, trade.qty, false);
    var next = compositionAmounts(after.legs, after.qty, true);
    var keys = Array.from(new Set(Object.keys(before).concat(Object.keys(next))));
    var addedOptions = [], removedOptions = [], addedStock = [], removedStock = [];
    keys.forEach(function (key) {
      var oldAmount = before[key] ? before[key].amount : 0;
      var newAmount = next[key] ? next[key].amount : 0;
      if (newAmount > oldAmount) (next[key].stock ? addedStock : addedOptions)
        .push({ key: key, before: oldAmount, after: newAmount });
      if (newAmount < oldAmount) (before[key].stock ? removedStock : removedOptions)
        .push({ key: key, before: oldAmount, after: newAmount });
    });
    var optionChanged = addedOptions.length || removedOptions.length;
    var stockChanged = addedStock.length || removedStock.length;
    if (!optionChanged && !stockChanged) throw new Error('Change a contract quantity, add or remove a leg, or change the share quantity before reviewing.');
    if (optionChanged && stockChanged) {
      throw new Error('Review option and share changes separately so each cash flow and risk change stays explicit.');
    }
    if (addedOptions.length && removedOptions.length) {
      throw new Error('Replacing one contract with another is a roll. Use Roll so the close result and replacement stay explicit.');
    }
    if (addedStock.length && removedStock.length) {
      throw new Error('Change the share position in one direction at a time.');
    }
    if (Number(trade.qty) > 1 && Number(after.qty) < Number(trade.qty)
        && Object.keys(before).length === Object.keys(next).length
        && Object.keys(before).every(function (key) { return next[key] && next[key].amount > 0; })) {
      var firstKey = Object.keys(before)[0];
      var proportional = Object.keys(before).every(function (key) {
        return next[key].amount * before[firstKey].amount === before[key].amount * next[firstKey].amount;
      });
      if (proportional) throw new Error('Reducing every leg together is a partial close. Use Partial close so package history stays explicit.');
    }
    if (addedOptions.length) return 'ADD_LEG';
    if (removedOptions.length) return removedOptions.some(function (change) { return change.after > 0; })
      ? 'LEG_CLOSE' : 'REMOVE_LEG';
    if (addedStock.length) return 'ADD_STOCK';
    return 'REMOVE_STOCK';
  }

  function adjustmentLabel(action) {
    return ({ ADD_LEG: 'Add option quantity', LEG_CLOSE: 'Reduce option quantity',
      REMOVE_LEG: 'Remove option leg', ADD_STOCK: 'Add shares',
      REMOVE_STOCK: 'Remove shares' })[action] || 'Adjust position';
  }

  function renderAdjustmentReview(host, preview, afterPayload, action, trade, managedPlan, editor, stateKey) {
    host.innerHTML = '';
    var change = preview.transformation;
    var afterReview = preview.after || {};
    var after = afterReview.preview || {};
    var required = afterReview.requiredAcks || [];
    var guardrails = afterReview.guardrails || {};
    var warnings = Array.from(new Set([].concat(change.warnings || [], guardrails.warnings || [], after.warnings || [])));
    var hasRemoval = action === 'LEG_CLOSE' || action === 'REMOVE_LEG' || action === 'REMOVE_STOCK';
    host.appendChild(el('div', { class: 'position-adjust-review' },
      el('div', { class: 'position-transformation-route', 'aria-label': 'Position before and after adjustment' },
        transformationState('Before', change.beforeIdentity, 'Current position'),
        el('span', { class: 'position-transformation-arrow', 'aria-hidden': 'true' }, '→'),
        transformationState('After', change.afterIdentity, 'Adjusted position')),
      el('div', { class: 'position-transformation-facts' },
        transformationFact('Action', adjustmentLabel(action)),
        Number(preview.closingCashCents || 0) !== 0
          ? transformationFact('Cash from removed quantity', fmtMoney(preview.closingCashCents, { plus: true })) : null,
        Number(preview.openingCashCents || 0) !== 0
          ? transformationFact('Cash from added quantity', fmtMoney(preview.openingCashCents, { plus: true })) : null,
        transformationFact('Action fees', fmtMoney(Number(preview.closingFeesCents || 0) + Number(preview.openingFeesCents || 0))),
        hasRemoval ? transformationFact('Realized by this action', fmtMoney(preview.actionRealizedPnlCents, { plus: true }),
          Number(preview.actionRealizedPnlCents) >= 0 ? 'gain' : 'loss') : null,
        hasRemoval ? transformationFact('Realized on position to date', fmtMoney(preview.realizedPnlToDateCents, { plus: true }),
          Number(preview.realizedPnlToDateCents) >= 0 ? 'gain' : 'loss') : null,
        transformationFact('Theoretical max loss before', fmtMoney(change.beforeRisk.maxLossCents)),
        transformationFact('Theoretical max loss after', fmtMoney(change.afterRisk.maxLossCents)),
        transformationFact('Broker reserve before', fmtMoney(change.beforeRisk.reserveCents || 0)),
        transformationFact('Broker reserve after', fmtMoney(change.afterRisk.reserveCents || 0)),
        transformationFact('Assignment cash after', fmtMoney(change.afterObligations.putAssignmentCashCents || 0)),
        transformationFact('Shares deliverable after', String(change.afterObligations.callDeliveryShares || 0))),
      warnings.length ? alertBox('warn', 'Review what changes', warnings) : null,
      preview.basisNotes && preview.basisNotes.length ? alertBox('info', 'Basis treatment', preview.basisNotes) : null,
      required.length ? alertBox('caution', 'Material risks to acknowledge', required.map(function (item) { return item.label; })) : null,
      change.applicable ? null : alertBox('danger', 'This change is a teaching case, not an executable adjustment',
        (change.afterRisk && change.afterRisk.blockReasons || guardrails.blockReasons || ['Review the resulting risk above.'])),
      el('p', { class: 'muted small position-transformation-note' },
        'Unchanged quantities keep their stored fills. Changed quantities use current executable sides; cash, reserve, Plan action, and transformation receipt commit together or nothing changes.'),
      change.applicable ? el('button', { type: 'button', class: 'btn', id: 'apply-position-adjustment-btn',
        onclick: async function (event) {
          var current = reviewedAdjustmentPayload(editor.readAnalysis(), trade);
          var currentAction = deriveAdjustmentAction(trade, current);
          if (currentAction !== action || JSON.stringify(current) !== JSON.stringify(afterPayload)) {
            host.innerHTML = '';
            host.appendChild(UI.actionFeedback('caution', 'Review the edited position again',
              'The surviving contracts changed after this receipt was created. No position was changed.'));
            return;
          }
          var applyAfter = JSON.parse(JSON.stringify(afterPayload));
          if (required.length) {
            applyAfter.acknowledgedRisks = required.map(function (item) { return item.id; });
            applyAfter.ackToken = afterReview.ackToken;
          }
          var request = practiceTransformationRequest(trade, managedPlan, action, preview.previewToken);
          request.after = applyAfter;
          var applied = await visibleCommand(event.currentTarget, function () {
            return API.post('/api/position-transformations/apply', request);
          }, 'The reviewed position adjustment could not be applied.');
          if (!applied) return;
          if (App.state.positionDrafts) delete App.state.positionDrafts[stateKey];
          if (managedPlan && applied.plan) await PlanStore.focus(applied.plan, 'MANAGE_REVIEW');
          else await refreshTradeDetailNear(host, trade.id, adjustmentLabel(action) + ' applied',
            'The position history is preserved and the resulting exposure is refreshed below.');
        } }, required.length ? 'Apply change & acknowledge risks' : 'Apply reviewed change') : null));
    host.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  async function openAdjustmentTransformation(trade, managedPlan, host) {
    host.hidden = false;
    host.innerHTML = '';
    if (managedPlan) managedPlan = await PlanStore.get(managedPlan.id, true);
    var stateKey = 'practice-adjust:' + trade.id + ':' + String(trade.updatedAt || trade.entryNetPremiumCents);
    var editorHost = el('div', { class: 'position-adjust-editor' });
    var reviewHost = el('div', { class: 'position-adjust-result', 'aria-live': 'polite' });
    host.append(editorHost, reviewHost);
    var reviewed = null;
    var editor = PositionEditor.render(editorHost, {
      stateKey: stateKey, lockedSymbol: trade.symbol, chain: true, compositionOnly: true,
      title: 'Edit the position that will remain open',
      description: 'Add or remove one kind of exposure at a time. Current quantities stay untouched while you edit; the review prices only the difference and shows the complete resulting risk.',
      analyzeLabel: 'Review this exact change', initial: adjustmentDraftFromTrade(trade),
      onStateChange: function (draft) {
        if (!reviewed) return;
        try {
          var current = reviewedAdjustmentPayload(PositionEditor.analysisPayload(draft), trade);
          if (JSON.stringify(current) === JSON.stringify(reviewed.after)) return;
        } catch (ignored) {
          // An incomplete composition also invalidates the reviewed receipt.
        }
        reviewed = null;
        reviewHost.innerHTML = '';
        reviewHost.appendChild(UI.actionFeedback('caution', 'Position changed',
          'Review again before Apply. The current position remains untouched.'));
      },
      onAnalyze: async function (payload) {
        var after = reviewedAdjustmentPayload(payload, trade);
        var action = deriveAdjustmentAction(trade, after);
        var request = practiceTransformationRequest(trade, managedPlan, action);
        request.after = after;
        var out = await API.post('/api/position-transformations/preview', request);
        reviewed = { preview: out, after: after, action: action };
        renderAdjustmentReview(reviewHost, out, after, action, trade, managedPlan, editor, stateKey);
        return { preview: out.after.preview, evaluation: out.after.evaluation, identity: out.after.identity };
      }
    });
    host.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

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
        intent: intent, source: 'PORTFOLIO_CONSTRUCT', fillNature: 'PROPOSED' });
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
        var economics = evaluation.assessment && evaluation.assessment.economics || {};
        var button = el('button', { type: 'button', class: 'btn btn-sm' }, 'Review in Plan');
        button.onclick = function () {
          visibleCommand(button, function () { return openOptimizationPlan(allocation, form); },
            'This allocation could not be opened in a Plan.');
        };
        return el('tr', {}, el('td', {}, symbol),
          el('td', {}, candidate.displayName || evaluation.family || '\u2014'),
          el('td', {}, economics.label || economics.verdict || 'Unavailable'), el('td', {}, String(allocation.units)),
          el('td', {}, fmtMoney(allocation.capitalCents)), el('td', {}, UI.scoreBar(evaluation.decisionScore || 0, 'Decision score')),
          el('td', {}, economics.marketEvAfterCostsCents != null ? pnlSpan(economics.marketEvAfterCostsCents) : '\u2014'),
          el('td', {}, economics.realizedVolEvAfterCostsCents != null ? pnlSpan(economics.realizedVolEvAfterCostsCents) : '\u2014'),
          el('td', {}, button));
      })));
    } else {
      host.appendChild(el('div', { class: 'portfolio-allocation-grid' }, allocations.map(function (allocation) {
        var evaluation = allocation.eval || {}, candidate = evaluation.candidate || {};
        var symbol = evaluation.symbol || (evaluation.spec && evaluation.spec.symbol) || candidate.symbol || '\u2014';
        var economics = evaluation.assessment && evaluation.assessment.economics || {};
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
            chip('Market EV', economics.marketEvAfterCostsCents != null
              ? pnlSpan(economics.marketEvAfterCostsCents) : '\u2014'),
            chip('History EV', economics.realizedVolEvAfterCostsCents != null
              ? pnlSpan(economics.realizedVolEvAfterCostsCents) : '\u2014')), button);
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
      UI.expandable('More construction controls', function () { return advancedBody; },
        { open: 'desktop', stateKey: 'portfolio-construction-controls' }),
      el('div', { class: 'btn-row' }, run), output);
    if (state.result) renderPortfolioConstructionResult(output, state.result, form);
    return card;
  }

  function portfolioModeNav(tracked) {
    var list = el('div', { class: 'portfolio-mode seg', role: 'tablist', 'aria-label': 'Portfolio workspace' },
      el('button', { type: 'button', role: 'tab', id: 'portfolio-mode-paper', 'aria-controls': 'portfolio-mode-panel',
        class: tracked ? '' : 'active', 'aria-selected': tracked ? 'false' : 'true',
        tabindex: tracked ? '-1' : '0',
        onclick: function () { App.navigate('#/portfolio/positions'); } }, UI.vocabularyText('practice') + ' account'),
      el('button', { type: 'button', role: 'tab', id: 'portfolio-mode-tracked', 'aria-controls': 'portfolio-mode-panel',
        class: tracked ? 'active' : '', 'aria-selected': tracked ? 'true' : 'false',
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

  function accountStatusLabel(value) {
    return String(value || '').toUpperCase() === 'ARCHIVED' ? 'Archived' : 'Open';
  }

  function transactionTypeLabel(value) {
    return ({ TRADE: 'Trade', ROLL: 'Roll', ASSIGNMENT: 'Assignment', EXERCISE: 'Exercise',
      EXPIRATION: 'Expiration', DIVIDEND: 'Dividend', INTEREST: 'Interest', FEE: 'Fee',
      CASH_FLOW: 'Cash transfer', MARK_TO_MARKET: 'Year-end mark', CAPITAL_GAIN_DISTRIBUTION: 'Capital-gain distribution' })[
      String(value || '').toUpperCase()] || 'Recorded activity';
  }

  function transactionSourceLabel(value) {
    return String(value || '').toUpperCase() === 'BROKER' ? 'Broker record' : 'Entered here';
  }

  function positionSideLabel(value) {
    return String(value || '').toUpperCase() === 'SHORT' ? 'Short' : 'Long';
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
    var accountDetails = el('div', { class: 'form-grid portfolio-account-details' },
      UI.field('Broker', broker), UI.field('Tax-lot method', method,
        { hint: 'HIFO uses the highest remaining basis for long lots and the lowest remaining opening proceeds for short lots. Future closes use this method; realized matches never change.' }));
    var taxScenario = Learn.currentLevel() === 'expert' ? UI.expandable('Tax scenario (optional)', function () {
      return el('div', { class: 'portfolio-tax-scenario' }, taxFields, taxNote);
    }, { stateKey: 'portfolio-account-tax-' + (existing.id || 'new') }) : null;
    return el('div', { class: 'portfolio-account-form' },
      el('div', { class: 'form-grid portfolio-account-primary' }, UI.field('Account name', name), UI.field('Account type', type,
        existing.id ? { hint: 'The tax wrapper is fixed for this book. Use a separate tracked account for another wrapper.' } : null),
        existing.id ? null : UI.field('Opening cash $', opening, { hint: 'Establishes this tracked book’s opening balance; it is not treated as an in-period contribution, and practice cash is untouched.' })),
      UI.expandable('Account details', function () { return accountDetails; }, {
        open: !!existing.id && Learn.currentLevel() === 'expert', stateKey: 'portfolio-account-details-' + (existing.id || 'new')
      }),
      taxScenario,
      el('div', { class: 'btn-row' }, save), msg);
  }

  function portfolioBookTabs(section) {
    var tabs = [['overview', 'Overview'], ['risk', 'Book risk'], ['activity', 'Activity'], ['performance', 'Performance'],
      ['tax', Learn.currentLevel() === 'beginner' ? 'Records & export' : 'Taxes & export'], ['settings', 'Settings']];
    var list = el('div', { class: 'tabs portfolio-book-tabs', role: 'tablist', 'aria-label': 'Tracked account sections' },
      tabs.map(function (tab) { return el('button', { type: 'button', role: 'tab',
        id: 'portfolio-book-tab-' + tab[0], 'aria-controls': 'portfolio-book-panel',
        class: section === tab[0] ? 'active' : '',
        'aria-selected': section === tab[0] ? 'true' : 'false', tabindex: section === tab[0] ? '0' : '-1',
        onclick: function () { App.navigate('#/portfolio/book/' + tab[0]); } }, tab[1]); }));
    return UI.bindTabList(list, function (tab) { tab.click(); });
  }

  /** Adopts an as-is book position into a Plan: the live band is real immediately; the
   *  deliberate next step is declaring a view on it (decision moment: "now that I see it,
   *  what do I believe about it?"). */
  async function portfolioAdoptPosition(account, p, button) {
    button.disabled = true; button.setAttribute('aria-busy', 'true');
    try {
      var lots = await portfolioOpenLotsFor(account, p);
      if (!lots.length) throw new Error('No open lots remain for this position.');
      var out = await PlanStore.adoptPosition({
        clientRequestId: 'adopt-' + account.id + '-' + p.symbol + '-' + Date.now().toString(36),
        portfolioAccountId: account.id, symbol: p.symbol,
        label: portfolioPositionLabel(p),
        allocations: lots.map(function (lot) { return { lotId: lot.id }; })
      });
      UI.toast(p.symbol + ' adopted into a Plan — declare your view on it');
      await PlanStore.focus(out.plan, 'UNDERSTAND');
    } catch (e) {
      UI.toast(e.message, 'error');
      button.disabled = false; button.removeAttribute('aria-busy');
    }
  }

  function portfolioPositionLabel(p) {
    return p.instrumentType === 'STOCK' ? p.symbol + ' shares'
      : p.symbol + ' ' + p.expiration + ' · ' + fmtNum(Number(p.strike), 2) + ' ' + p.optionType.toLowerCase();
  }

  function portfolioRollPosition(account, position) {
    App.state.portfolioBookRollTarget = { accountId: account.id, position: position };
    App.navigate('#/portfolio/book/activity');
  }

  async function portfolioOpenLotsFor(account, p) {
    var data = await API.getFresh('/api/portfolio/accounts/' + account.id + '/lots');
    return (data.lots || []).filter(function (lot) {
      if (lot.status !== 'OPEN' || !(lot.remainingQuantity > 0)) return false;
      if (lot.symbol !== p.symbol || lot.instrumentType !== p.instrumentType || lot.side !== p.side) return false;
      if (p.instrumentType !== 'OPTION') return true;
      return lot.optionType === p.optionType && Number(lot.strike) === Number(p.strike)
        && String(lot.expiration) === String(p.expiration);
    });
  }

  /** Starts a campaign seeded by this position's open lots. The seed transactions attach as
   *  user-confirmed members; everything else stays a suggestion until confirmed. */
  async function portfolioStartCampaign(account, p, button) {
    button.disabled = true; button.setAttribute('aria-busy', 'true');
    try {
      var lots = await portfolioOpenLotsFor(account, p);
      if (!lots.length) throw new Error('No open lots remain for this position.');
      var campaign = await API.post('/api/campaigns', {
        title: p.symbol + ' campaign', symbol: p.symbol,
        seedLotIds: lots.map(function (lot) { return lot.id; })
      });
      App.state.portfolioCampaignFocus = campaign.id;
      UI.toast('Campaign started from ' + p.symbol + ' — review its suggested linked activity');
      await App.render();
    } catch (e) {
      UI.toast(e.message, 'error');
      button.disabled = false; button.removeAttribute('aria-busy');
    }
  }

  function campaignYieldPct(value) {
    return value == null ? '—' : Number(value).toFixed(2) + '%';
  }

  async function campaignAttachMember(campaignId, proposal, button) {
    button.disabled = true; button.setAttribute('aria-busy', 'true');
    try {
      var isInterest = proposal.type === 'TRANSACTION' && /^interest\b/.test(proposal.label || '');
      await API.post('/api/campaigns/' + campaignId + '/members', {
        type: proposal.type, id: proposal.id, explicitInterest: isInterest ? true : null
      });
      UI.toast(isInterest ? 'Interest tagged to this campaign' : 'Attached to campaign');
      App.state.portfolioCampaignFocus = campaignId;
      await App.render();
    } catch (e) {
      UI.toast(e.message, 'error');
      button.disabled = false; button.removeAttribute('aria-busy');
    }
  }

  async function campaignDetachMember(campaignId, member, button) {
    button.disabled = true; button.setAttribute('aria-busy', 'true');
    try {
      await API.del('/api/campaigns/' + campaignId + '/members/' + member.type + '/'
        + encodeURIComponent(member.id));
      UI.toast('Removed from campaign — the recorded book is untouched');
      App.state.portfolioCampaignFocus = campaignId;
      await App.render();
    } catch (e) {
      UI.toast(e.message, 'error');
      button.disabled = false; button.removeAttribute('aria-busy');
    }
  }

  function campaignProposalsBlock(campaign) {
    var beginner = Learn.currentLevel() === 'beginner';
    var host = el('div', { class: 'campaign-proposals' });
    var message = el('div', { class: 'small', 'aria-live': 'polite' });
    var load = el('button', { type: 'button', class: 'btn btn-secondary btn-sm', onclick: async function () {
      load.disabled = true; load.setAttribute('aria-busy', 'true'); message.textContent = '';
      try {
        var data = await API.getFresh('/api/campaigns/' + campaign.id + '/proposals');
        var proposals = data.proposals || [];
        var list = el('div', { class: 'campaign-proposal-list' });
        if (!proposals.length) {
          list.appendChild(el('p', { class: 'muted small' },
            'No linked activity found beyond what is already attached.'));
        }
        proposals.forEach(function (p) {
          var attach = el('button', { type: 'button', class: 'btn btn-sm' }, 'Attach');
          attach.addEventListener('click', function () { campaignAttachMember(campaign.id, p, attach); });
          list.appendChild(el('div', { class: 'campaign-proposal-row' },
            el('div', {}, el('b', {}, p.label),
              el('span', { class: 'badge badge-dim', style: 'margin-left:6px' }, 'suggestion'),
              el('p', { class: 'muted small' }, p.reason
                + (p.cashEffectCents == null ? '' : ' · ' + fmtMoney(p.cashEffectCents)))),
            attach));
        });
        var existing = host.querySelector('.campaign-proposal-list');
        if (existing) existing.replaceWith(list); else host.appendChild(list);
      } catch (e) { message.textContent = e.message || String(e); message.className = 'small loss'; }
      finally { load.disabled = false; load.removeAttribute('aria-busy'); }
    } }, 'Suggest linked activity');
    host.appendChild(el('p', { class: 'muted small' }, beginner
      ? 'StrikeBench follows your rolls, assignments, Plans, and same-symbol activity to suggest what belongs here. Nothing joins until you attach it.'
      : 'Auto-link follows lot/roll chains and Plan lineage; suggestions are never members until confirmed.'));
    host.appendChild(el('div', { class: 'btn-row' }, load));
    host.appendChild(message);
    if (App.state.portfolioCampaignFocus === campaign.id) {
      App.state.portfolioCampaignFocus = null;
      setTimeout(function () { load.click(); }, 0);
    }
    return host;
  }

  function campaignMembersBlock(campaign) {
    var host = el('div', { class: 'campaign-members' });
    (campaign.members || []).forEach(function (m) {
      var remove = el('button', { type: 'button', class: 'btn btn-secondary btn-sm' }, 'Remove');
      remove.addEventListener('click', function () { campaignDetachMember(campaign.id, m, remove); });
      host.appendChild(el('div', { class: 'campaign-member-row' },
        el('div', {}, el('b', {}, m.label),
          m.explicitInterest ? el('span', { class: 'badge badge-dim', style: 'margin-left:6px' }, 'tagged interest') : null,
          m.countsInMath ? null : el('span', { class: 'badge badge-dim', style: 'margin-left:6px' }, 'listed, never netted'),
          el('p', { class: 'muted small' }, (m.detail || m.type.toLowerCase().replace(/_/g, ' '))
            + (m.occurredAt ? ' · ' + portfolioOccurredLabel(m.occurredAt) : '')
            + (m.cashEffectCents == null ? '' : ' · ' + fmtMoney(m.cashEffectCents)))),
        remove));
    });
    if (!(campaign.members || []).length) {
      host.appendChild(el('p', { class: 'muted small' }, 'No members yet — attach recorded activity below.'));
    }
    host.appendChild(campaignProposalsBlock(campaign));
    return host;
  }

  function campaignLedgerBlock(campaign) {
    var beginner = Learn.currentLevel() === 'beginner';
    var ledger = campaign.ledger || [];
    if (!ledger.length) return el('p', { class: 'muted small' }, 'No member events yet.');
    if (beginner) {
      var list = el('div', { class: 'campaign-ledger-list' });
      ledger.forEach(function (row) {
        list.appendChild(el('div', { class: 'campaign-ledger-row' },
          el('div', {}, el('b', {}, row.label), el('span', { class: 'muted small' },
            ' · ' + portfolioOccurredLabel(row.occurredAt))),
          el('div', { class: 'chip-row' },
            chip('Cash', fmtMoney(row.cashEffectCents)),
            chip('Shares now', String(row.runningShares)),
            row.runningBasisPerShareCents == null ? null
              : chip('Each share really costs', fmtMoney(row.runningBasisPerShareCents)))));
      });
      return list;
    }
    return table(['When', 'Event', 'Cash', 'Δ shares', 'Shares', 'Net option cash', 'Basis / share', 'Committed'],
      ledger.map(function (row) {
        return el('tr', {}, el('td', {}, portfolioOccurredLabel(row.occurredAt)),
          el('td', {}, el('b', {}, row.label)), el('td', {}, fmtMoney(row.cashEffectCents)),
          el('td', {}, String(row.sharesDelta)), el('td', {}, String(row.runningShares)),
          el('td', {}, fmtMoney(row.runningNetOptionCashCents)),
          el('td', {}, row.runningBasisPerShareCents == null ? '—' : fmtMoney(row.runningBasisPerShareCents)),
          el('td', {}, fmtMoney(row.runningCommittedCapitalCents)));
      }));
  }

  function campaignRow(campaign) {
    var beginner = Learn.currentLevel() === 'beginner';
    var yieldView = campaign.yield || {};
    var basis = campaign.economicBasis || {};
    var counter = campaign.counterfactuals || {};
    var statusToggle = el('button', { type: 'button', class: 'btn btn-secondary btn-sm' },
      campaign.status === 'ACTIVE' ? 'Close campaign' : 'Reopen');
    statusToggle.addEventListener('click', async function () {
      statusToggle.disabled = true; statusToggle.setAttribute('aria-busy', 'true');
      try {
        await API.put('/api/campaigns/' + campaign.id, {
          status: campaign.status === 'ACTIVE' ? 'CLOSED' : 'ACTIVE' });
        await App.render();
      } catch (e) { UI.toast(e.message, 'error'); statusToggle.disabled = false; statusToggle.removeAttribute('aria-busy'); }
    });
    var row = el('article', { class: 'campaign-row' },
      el('div', { class: 'campaign-row-heading' },
        el('div', {}, el('h3', {}, campaign.title),
          el('span', { class: 'badge ' + (campaign.status === 'ACTIVE' ? 'badge-ok' : 'badge-dim'),
            style: 'margin-left:8px' }, campaign.status.toLowerCase())),
        statusToggle));
    var headline = el('div', { class: 'chip-row campaign-headline' },
      chip(UI.vocabulary('campaignEconomicBasis'), basis.available ? fmtMoney(basis.perShareCents)
        : el('span', { class: 'muted' }, 'No shares held')),
      chip(UI.vocabulary('realizedVsHeadline'), yieldView.available
        ? el('span', {}, campaignYieldPct(yieldView.realizedPeriodPct) + ' vs '
            + campaignYieldPct(yieldView.headlinePeriodPct) + ' headline')
        : el('span', { class: 'muted' }, 'No committed capital yet')),
      chip(el('span', {}, UI.vocabulary('counterfactualBenchmark', 'Vs cash')),
        counter.cash && counter.cash.available && counter.cash.deltaCents != null
          ? pnlSpan(counter.cash.deltaCents) : el('span', { class: 'muted' }, 'Unavailable')),
      chip(el('span', {}, UI.vocabulary('counterfactualBenchmark', 'Vs buy & hold')),
        counter.buyAndHold && counter.buyAndHold.available && counter.buyAndHold.deltaCents != null
          ? pnlSpan(counter.buyAndHold.deltaCents) : el('span', { class: 'muted' }, 'Unavailable')));
    row.appendChild(headline);
    if (beginner) {
      row.appendChild(el('p', { class: 'muted small' }, basis.available
        ? el('span', {}, 'After every premium, buyback, dividend, and fee, each of the '
            + basis.sharesHeld + ' shares this campaign holds has really cost ',
            el('b', {}, fmtMoney(basis.perShareCents)),
            '. Your broker’s tax number is tracked separately and can differ.')
        : el('span', {}, 'This campaign holds no shares right now, so it has no per-share figure; '
            + 'its option cash so far is ', el('b', {}, fmtMoney(campaign.netOptionCashCents)), '.')));
      if (yieldView.available) {
        row.appendChild(el('p', { class: 'muted small' },
          'Annualized ' + campaignYieldPct(yieldView.realizedAnnualizedPct)
          + ' realized vs ' + campaignYieldPct(yieldView.headlineAnnualizedPct)
          + ' headline — only if this result repeats. Both rates divide by the same '
          + 'peak committed capital of ' + fmtMoney(yieldView.peakCommittedCapitalCents) + '.'));
      }
    } else {
      row.appendChild(el('div', { class: 'chip-row campaign-expert-facts' },
        chip(UI.vocabulary('campaignNetCredit'), fmtMoney(campaign.netOptionCashCents)),
        chip(UI.vocabulary('peakCommittedCapital'), fmtMoney(yieldView.peakCommittedCapitalCents || 0)),
        yieldView.available && yieldView.realizedTimeWeightedPct != null
          ? chip('Realized / time-weighted capital', campaignYieldPct(yieldView.realizedTimeWeightedPct)) : null,
        yieldView.available
          ? chip('Annualized if repeatable', campaignYieldPct(yieldView.realizedAnnualizedPct) + ' vs '
              + campaignYieldPct(yieldView.headlineAnnualizedPct)) : null,
        chip('Dividends attributed', fmtMoney(campaign.attributedDividendsCents)),
        chip('Tagged interest', fmtMoney(campaign.explicitInterestCents)),
        campaign.churn && campaign.churn.roundTrips.length
          ? chip(UI.vocabulary('churnCost'), fmtMoney(campaign.churn.totalCostCents)) : null));
      if ((campaign.accounts || []).length > 1) {
        row.appendChild(el('div', { class: 'chip-row' }, campaign.accounts.map(function (a) {
          return chip(a.name + ' · ' + (a.shareOfActivityBps / 100).toFixed(2) + '%', fmtMoney(a.netCashCents));
        })));
      }
    }
    (campaign.receipts || []).forEach(function (receipt) {
      if (receipt.indexOf('tax figures withheld') >= 0 || receipt.indexOf('not members') >= 0) {
        row.appendChild(el('p', { class: 'small campaign-receipt' },
          el('span', { class: 'badge badge-warn', style: 'margin-right:6px' }, 'receipt'), receipt));
      }
    });
    row.appendChild(UI.expandable(el('span', {}, UI.vocabulary('accumulationLedger'), ' · '
        + (campaign.ledger || []).length + ' event' + ((campaign.ledger || []).length === 1 ? '' : 's')),
      function () { return campaignLedgerBlock(campaign); },
      { stateKey: 'campaign-ledger-' + campaign.id }));
    row.appendChild(UI.expandable('Members & suggestions · ' + (campaign.members || []).length,
      function () { return campaignMembersBlock(campaign); },
      { stateKey: 'campaign-members-' + campaign.id,
        open: App.state.portfolioCampaignFocus === campaign.id }));
    return row;
  }

  function campaignCreateForm() {
    var title = el('input', { type: 'text', maxlength: '120', placeholder: 'MU accumulation' });
    var symbol = el('input', { type: 'text', maxlength: '20', placeholder: 'MU', list: 'universe-symbols' });
    var message = el('div', { class: 'small', 'aria-live': 'polite' });
    var create = el('button', { type: 'button', class: 'btn', onclick: async function () {
      create.disabled = true; create.setAttribute('aria-busy', 'true'); message.textContent = '';
      try {
        var campaign = await API.post('/api/campaigns', {
          title: title.value, symbol: symbol.value.trim() ? symbol.value.trim().toUpperCase() : null });
        App.state.portfolioCampaignFocus = campaign.id;
        UI.toast('Campaign created — attach its recorded activity');
        await App.render();
      } catch (e) { message.textContent = e.message || String(e); message.className = 'small loss'; }
      finally { create.disabled = false; create.removeAttribute('aria-busy'); }
    } }, 'Create campaign');
    return el('div', { class: 'campaign-create-form' },
      el('div', { class: 'form-grid' }, UI.field('Campaign name', title),
        UI.field('Primary symbol (optional)', symbol,
          { hint: 'Names the buy-and-hold counterfactual and same-symbol suggestions.' })),
      el('div', { class: 'btn-row' }, create), message);
  }

  async function portfolioCampaignsCard() {
    var beginner = Learn.currentLevel() === 'beginner';
    var card = el('section', { class: 'card book-campaigns' });
    var list = [];
    try {
      var data = await API.getFresh('/api/campaigns');
      list = data.campaigns || [];
    } catch (e) {
      card.appendChild(UI.cardHeader('Campaigns'));
      card.appendChild(el('p', { class: 'muted' }, 'Campaigns are unavailable right now: '
        + (e.message || e) + '. The recorded book above is unaffected.'));
      return card;
    }
    card.appendChild(UI.cardHeader('Campaigns',
      el('span', { class: 'badge badge-dim' }, list.length + ' campaign' + (list.length === 1 ? '' : 's'))));
    card.appendChild(el('p', { class: 'muted small' }, beginner
      ? el('span', {}, 'A campaign reads one whole story — premiums, rolls, assignment, dividends — '
          + 'as a single position, so you can see what it really earns. It never changes your recorded lots or ',
          UI.vocabulary('trackedTaxBasis'), '.')
      : el('span', {}, 'Interpretation layer over recorded activity: typed members, ',
          UI.vocabulary('campaignEconomicBasis'), ', ', UI.vocabulary('realizedVsHeadline'),
          ' on identical denominators, and live ', UI.vocabulary('counterfactualBenchmark'),
          ' deltas. Never the accounting source.')));
    if (!list.length) {
      card.appendChild(UI.emptyState('No campaigns yet', beginner
        ? 'Use “Start a campaign from this position” on any open position, or create one here and attach its activity.'
        : 'Seed one from a position row or create one here; auto-link proposes the roll chain and Plan lineage.'));
    }
    list.forEach(function (campaign) { card.appendChild(campaignRow(campaign)); });
    card.appendChild(UI.expandable('Start a campaign', function () { return campaignCreateForm(); },
      { stateKey: 'campaign-create' }));
    return card;
  }

  // ---- Account objective (Program ONE R4.4): what is this account FOR? ----
  // The declaration is an immutable revision series (backend AccountObjectiveService):
  // declaring again writes revision N+1 and applies prospectively; history is never rewritten.

  var OBJECTIVE_META = {
    INCOME: { label: 'Income', sub: 'Collect premium', goal: 'collect premium',
      consequence: 'Income — premium collection is the goal; short option cycles that keep collecting are coherent.' },
    ACCUMULATE: { label: 'Accumulate', sub: 'Build the share position', goal: 'build the share position',
      consequence: 'Accumulate — building the share position matters more than premium; entries that add shares cheaply count in favor.' },
    HEDGE: { label: 'Hedge', sub: 'Protect other holdings', goal: 'protect other holdings',
      consequence: 'Hedge — this account offsets risk held elsewhere; protection that pays when other holdings fall is coherent.' },
    DIRECTIONAL: { label: 'Directional', sub: 'Express a market view', goal: 'express a market view',
      consequence: 'Directional — this account expresses a market view; declare the direction below so every idea is checked against it.' },
    CAPITAL_PRESERVATION: { label: 'Capital preservation', sub: 'Keep the money safe', goal: 'keep the money safe',
      consequence: 'Capital preservation — keeping the money outranks growing it; defined-risk structures with small worst cases are coherent.' }
  };
  var OBJECTIVE_DIRECTION_META = {
    BULLISH: { label: 'Bullish', sub: 'Prices rise', phrase: 'bullish view',
      consequence: 'Bullish — ideas that profit when prices rise fit this account.' },
    BEARISH: { label: 'Bearish', sub: 'Prices fall', phrase: 'bearish view',
      consequence: 'Bearish — ideas that profit when prices fall fit this account.' },
    NEUTRAL: { label: 'Neutral', sub: 'Prices go nowhere', phrase: 'neutral view',
      consequence: 'Neutral — ideas that profit when prices go nowhere fit this account.' },
    NON_DIRECTIONAL: { label: 'Non-directional', sub: 'No view, on purpose', phrase: 'deliberately non-directional',
      consequence: 'Non-directional — you deliberately take no view; direction never counts for or against an idea.' }
  };
  var OBJECTIVE_ASSIGNMENT_META = {
    AVOID: { label: 'Avoid', sub: 'Keep assignment away', phrase: 'assignment avoided',
      consequence: 'Avoid — high assignment odds count against a structure.' },
    ACCEPT: { label: 'Accept', sub: 'Take it as it comes', phrase: 'assignment accepted',
      consequence: 'Accept — indifferent; assignment odds neither help nor hurt (no reweighting).' },
    PREFER_BELOW_BASIS: { label: 'Prefer below basis', sub: 'Cheap shares welcome', phrase: 'assignment below basis welcome',
      consequence: 'Prefer below basis — assignment that ADDS shares below your basis counts in favor.' },
    SEEK: { label: 'Seek', sub: 'Assignment is the point', phrase: 'assignment sought (the wheel)',
      consequence: 'Seek — assignment is the point (the wheel: get assigned the shares, then sell calls on them); high assignment odds count in favor.' }
  };

  /** Plain-language one-line summary of a revision — the declared card's headline. */
  function objectiveSummary(revision) {
    var meta = OBJECTIVE_META[revision.objective] || { label: revision.objective, goal: '' };
    var line = meta.label + (meta.goal ? ' — ' + meta.goal : '');
    var assignment = OBJECTIVE_ASSIGNMENT_META[revision.assignmentPreference];
    if (assignment) line += '; ' + assignment.phrase;
    var direction = OBJECTIVE_DIRECTION_META[revision.direction];
    if (direction) line += ' · ' + direction.phrase;
    if (revision.targetExposureCents != null) line += ' · target exposure ' + fmtMoney(revision.targetExposureCents);
    return line;
  }

  function objectiveRawValues(revision) {
    return revision.objective + (revision.direction ? ' · ' + revision.direction : '')
      + ' · ' + revision.assignmentPreference;
  }

  /** Every revision, oldest first. Beginner reads a plain list; Expert reads the raw series. */
  function objectiveHistoryBlock(history, beginner) {
    if (beginner) {
      return el('div', { class: 'book-objective-history', 'data-objective-history': '' },
        history.map(function (r) {
          return el('div', { class: 'book-objective-history-row', 'data-objective-history-row': String(r.revisionNo) },
            el('b', {}, 'Revision ' + r.revisionNo),
            el('span', { class: 'muted small' }, ' · ' + UI.fmtDate(r.createdAt) + ' · ' + objectiveSummary(r)));
        }));
    }
    return el('div', { class: 'book-objective-history', 'data-objective-history': '' },
      table(['Rev', 'Declared', 'Objective', 'Direction', 'Assignment', 'Target'],
        history.map(function (r) {
          return el('tr', { 'data-objective-history-row': String(r.revisionNo) },
            el('td', {}, String(r.revisionNo)), el('td', {}, UI.fmtDate(r.createdAt)),
            el('td', {}, r.objective), el('td', {}, r.direction || '—'),
            el('td', {}, r.assignmentPreference),
            el('td', {}, r.targetExposureCents == null ? '—' : fmtMoney(r.targetExposureCents)));
        })));
  }

  function portfolioObjectiveForm(account, latest, onSaved, onCancel) {
    var beginner = Learn.currentLevel() === 'beginner';
    var message = el('div', { class: 'small', 'data-objective-error': '', 'aria-live': 'polite' });
    var objectiveCtl = UI.segmented({
      id: 'account-objective-choice', label: 'Objective', info: 'accountobjective',
      options: Object.keys(OBJECTIVE_META).map(function (value) {
        var meta = OBJECTIVE_META[value];
        return { value: value, label: meta.label, sub: meta.sub, detail: value };
      }),
      value: latest ? latest.objective : 'INCOME', revealDetails: 'expert',
      consequence: function (value) { return OBJECTIVE_META[value].consequence; },
      onChange: syncDirection
    });
    var directionCtl = UI.chipSet({
      id: 'account-objective-direction', label: 'Direction (optional)', info: 'objectivedirection',
      options: [{ value: '', label: 'No declared direction', sub: 'Skip for now' }].concat(
        Object.keys(OBJECTIVE_DIRECTION_META).map(function (value) {
          var meta = OBJECTIVE_DIRECTION_META[value];
          return { value: value, label: meta.label, sub: meta.sub, detail: value };
        })),
      value: latest && latest.direction ? latest.direction : '', revealDetails: 'expert',
      consequence: function (value) {
        return value ? OBJECTIVE_DIRECTION_META[value].consequence
          : 'No declared direction — direction is checked only when you declare one.';
      }
    });
    var assignmentCtl = UI.segmented({
      id: 'account-objective-assignment', label: 'Assignment preference', info: 'assignmentfit',
      options: Object.keys(OBJECTIVE_ASSIGNMENT_META).map(function (value) {
        var meta = OBJECTIVE_ASSIGNMENT_META[value];
        return { value: value, label: meta.label, sub: meta.sub, detail: value };
      }),
      value: latest ? latest.assignmentPreference : 'ACCEPT', revealDetails: 'expert',
      consequence: function (value) { return OBJECTIVE_ASSIGNMENT_META[value].consequence; }
    });
    var target = el('input', { type: 'number', min: '0', step: '0.01', id: 'account-objective-target',
      inputmode: 'decimal', placeholder: 'Optional',
      value: latest && latest.targetExposureCents != null ? String(latest.targetExposureCents / 100) : '' });
    var targetField = UI.field(['Target exposure $ (optional)', UI.info('targetexposure')], target,
      { className: 'book-objective-target',
        hint: 'A dollar statement of intent recorded with the declaration. It never blocks or resizes an analysis.' });
    function syncDirection() {
      var objective = objectiveCtl.value();
      directionCtl.hidden = objective !== 'DIRECTIONAL' && objective !== 'HEDGE';
    }
    var save = el('button', { type: 'button', class: 'btn', id: 'account-objective-save',
      onclick: async function () {
        save.disabled = true; save.setAttribute('aria-busy', 'true');
        message.textContent = ''; message.className = 'small';
        try {
          var payload = { objective: objectiveCtl.value(), assignmentPreference: assignmentCtl.value() };
          var directional = payload.objective === 'DIRECTIONAL' || payload.objective === 'HEDGE';
          if (directional && directionCtl.value()) payload.direction = directionCtl.value();
          if (String(target.value).trim() !== '') {
            var dollars = Number(target.value);
            if (!Number.isFinite(dollars) || dollars < 0) {
              throw new Error('Target exposure must be zero or a positive dollar amount.');
            }
            payload.targetExposureCents = Math.round(dollars * 100);
          }
          var revision = await API.post('/api/portfolio/accounts/' + account.id + '/objective', payload);
          await onSaved(revision);
        } catch (e) {
          message.textContent = e.message || String(e);
          message.className = 'small loss';
          save.disabled = false; save.removeAttribute('aria-busy');
        }
      } }, latest ? 'Save as revision ' + (latest.revisionNo + 1) : 'Declare objective');
    var cancel = el('button', { type: 'button', class: 'btn btn-secondary', id: 'account-objective-cancel',
      onclick: onCancel }, 'Cancel');
    syncDirection();
    return el('div', { class: 'book-objective-form' },
      beginner ? el('p', { class: 'muted small book-objective-what' },
        'What does this change? Every analysis on this account gains a ', UI.vocabulary('coherenceVerdict'),
        ' — does the idea match this purpose? — and an ', UI.vocabulary('assignmentFit'),
        ' read. It explains and reweights; it never blocks or places anything.') : null,
      objectiveCtl, directionCtl, assignmentCtl, targetField,
      el('p', { class: 'muted small' }, latest
        ? 'Saving writes revision ' + (latest.revisionNo + 1) + ' and applies from now on. Revision '
          + latest.revisionNo + ' stays on record; receipts that quoted it keep quoting it.'
        : 'Declarations are a revision series: changing your mind later writes revision 2 — what you said, and when, is never rewritten.'),
      el('div', { class: 'btn-row' }, save, cancel), message);
  }

  async function renderObjectiveCardContent(card, account) {
    card.replaceChildren();
    var beginner = Learn.currentLevel() === 'beginner';
    var archived = account.status === 'ARCHIVED';
    var data;
    try {
      data = await API.getFresh('/api/portfolio/accounts/' + account.id + '/objective');
    } catch (e) {
      card.setAttribute('data-objective-state', 'unavailable');
      card.append(UI.cardHeader(el('span', {}, 'What is this account for?', UI.info('accountobjective'))),
        el('p', { class: 'muted small' }, 'The declared objective could not be loaded: '
          + (e.message || e) + '. The rest of this book is unaffected.'));
      return;
    }
    var latest = data.latest;
    var history = data.history || [];
    card.setAttribute('data-objective-state', latest ? 'declared' : 'undeclared');
    card.setAttribute('data-objective-revision', latest ? String(latest.revisionNo) : '0');
    var controlsHost = el('div', { class: 'book-objective-controls', 'data-objective-controls': '', hidden: 'hidden' });
    var declareBtn = el('button', { type: 'button', class: latest ? 'btn btn-secondary btn-sm' : 'btn',
      id: 'account-objective-declare', disabled: archived ? 'disabled' : null },
      latest ? 'Change' : 'Declare this account’s objective');
    declareBtn.addEventListener('click', function () {
      controlsHost.replaceChildren(portfolioObjectiveForm(account, latest, async function (revision) {
        await renderObjectiveCardContent(card, account);
        card.prepend(UI.actionFeedback('ok', 'Objective declared — revision ' + revision.revisionNo,
          'Analyses on this account are judged against this declaration from now on; earlier revisions stay on record.'));
      }, function () {
        controlsHost.hidden = true;
        controlsHost.replaceChildren();
        declareBtn.hidden = false;
        declareBtn.focus();
      }));
      controlsHost.hidden = false;
      declareBtn.hidden = true;
    });
    card.append(UI.cardHeader(el('span', {}, 'What is this account for?', UI.info('accountobjective')),
      latest ? el('span', { class: 'badge badge-dim' }, 'revision ' + latest.revisionNo) : null));
    if (!latest) {
      card.append(el('p', { class: 'book-objective-invite' },
        'StrikeBench judges every idea against what you say this account is FOR — declare it and every analysis here gains a ',
        UI.vocabulary('coherenceVerdict'), ' and an ', UI.vocabulary('assignmentFit'), ' read.'));
    } else {
      card.append(
        el('p', { class: 'book-objective-headline', 'data-objective-headline': '' },
          el('b', {}, objectiveSummary(latest))),
        el('p', { class: 'muted small book-objective-provenance' },
          'Revision ' + latest.revisionNo + ' · declared ' + UI.fmtDate(latest.createdAt)
          + (beginner ? '' : ' · ' + objectiveRawValues(latest))),
        el('p', { class: 'muted small book-objective-judged' },
          'Every analysis on this account is now judged against this ',
          UI.vocabulary('accountObjective', 'objective'), '.'));
    }
    card.append(el('div', { class: 'btn-row' }, declareBtn));
    if (archived) {
      card.append(el('p', { class: 'muted small' },
        'This tracked account is archived. Restore it in Settings before declaring a new objective; the record above stays readable.'));
    }
    card.append(controlsHost);
    if (history.length) {
      card.append(beginner
        ? UI.expandable('History · ' + history.length + ' declaration' + (history.length === 1 ? '' : 's'),
          function () { return objectiveHistoryBlock(history, true); },
          { stateKey: 'account-objective-history-' + account.id })
        : el('div', {},
          el('div', { class: 'field-label' }, 'Revision series'),
          objectiveHistoryBlock(history, false)));
    }
  }

  async function portfolioObjectiveCard(account) {
    var card = el('section', { class: 'card card-slim book-objective', id: 'account-objective-card' });
    await renderObjectiveCardContent(card, account);
    return card;
  }

  async function renderPortfolioBookOverview(root, account, summary) {
    var stats = el('div', { class: 'grid grid-4 book-summary-stats' },
      stat('Total value', summary.totalValueCents == null
        ? el('span', { class: 'muted' }, 'Unavailable') : fmtMoney(summary.totalValueCents),
        summary.complete ? 'Cash plus executable liquidation value.' : 'A total is withheld because one or more positions cannot be marked.'),
      stat('Cash in this book', fmtMoney(summary.bookCashCents), 'Recorded cash effects only; this never includes practice cash.'),
      stat('Realized P/L', pnlSpan(summary.realizedPnlCents), 'Exact matched-lot gains and losses after recorded opening and closing fees.'),
      stat('Unrealized P/L', summary.unrealizedPnlCents == null ? el('span', { class: 'muted' }, 'Unavailable') : pnlSpan(summary.unrealizedPnlCents),
        'What closing the recorded open lots at executable sides would produce, before new close fees.'));
    if (!summary.complete) root.appendChild(alertBox('caution', 'Current value is partial', [
      'Missing observed executable marks: ' + (summary.missingMarks || []).join(', ') + '. The book keeps basis and activity intact and does not turn missing prices into zero or use Demo, simulated, or modeled prices for an external account.'
    ]));

    var col = summary.collateral;
    var capital = el('section', { class: 'card card-slim book-capital' },
      UI.cardHeader('Cash and obligations'),
      el('div', { class: 'chip-row' }, chip('Known cash blocked', fmtMoney(col.knownBlockedCashCents)),
        chip('Available cash', col.availableCashCents == null ? 'Not estimated' : fmtMoney(col.availableCashCents)),
        col.cashSecuredPutContracts ? chip('Cash-secured puts', String(col.cashSecuredPutContracts)) : null,
        col.definedRiskPutContracts ? chip('Put spreads', String(col.definedRiskPutContracts)) : null,
        col.coveredCallContracts ? chip('Covered calls', String(col.coveredCallContracts)) : null,
        col.uncoveredShortCallShares ? el('span', { class: 'badge badge-danger' }, col.uncoveredShortCallShares + ' uncovered call shares') : null),
      el('p', { class: 'muted small' }, (col.notes || []).join(' ')));

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
          p.complete
            ? el('div', { class: 'book-position-evidence' }, UI.evidenceBadge({
                provenance: p.provenance, age: p.age
              }, { compact: true }))
            : el('div', { class: 'muted small' }, 'No executable closing price is available.'),
          el('div', { class: 'btn-row' },
            el('button', { type: 'button', class: 'btn btn-sm book-adopt-position',
              title: 'Open this position as a Plan and decide its future deliberately',
              onclick: function () { portfolioAdoptPosition(account, p, this); } }, 'Plan this position'),
            el('button', { type: 'button', class: 'btn btn-secondary btn-sm book-start-campaign',
              title: 'Group this position with its rolls, dividends, and linked activity',
              onclick: function () { portfolioStartCampaign(account, p, this); } }, 'Start a campaign from this position'),
            p.instrumentType === 'OPTION' ? el('button', { type: 'button', class: 'btn btn-secondary btn-sm',
              disabled: account.status === 'ARCHIVED' ? 'disabled' : null,
              onclick: function () { portfolioRollPosition(account, p); } }, 'Roll position') : null)));
      });
      posCard.appendChild(list);
    } else {
      posCard.appendChild(table(['Position', 'Side', 'Qty', 'Basis / proceeds', 'Close price', 'Liquidation value', 'Unrealized', 'Evidence', 'Action'],
        positions.map(function (p) { return el('tr', {}, el('td', {}, el('b', {}, portfolioPositionLabel(p))),
          el('td', {}, positionSideLabel(p.side)), el('td', {}, String(p.quantity) + (p.multiplier !== 1 ? ' ×' + p.multiplier : '')),
          el('td', {}, fmtMoney(p.openAmountCents)), el('td', {}, p.liquidationPrice == null ? '—' : '$' + Number(p.liquidationPrice).toFixed(4)),
          el('td', {}, p.liquidationValueCents == null ? '—' : fmtMoney(p.liquidationValueCents)),
          el('td', {}, p.unrealizedPnlCents == null ? '—' : pnlSpan(p.unrealizedPnlCents)),
        el('td', {}, UI.evidenceBadge({ provenance: p.provenance, age: p.age }, { compact: true })),
          el('td', {}, el('div', { class: 'btn-row' },
            el('button', { type: 'button', class: 'btn btn-sm book-adopt-position',
              title: 'Open this position as a Plan and decide its future deliberately',
              onclick: function () { portfolioAdoptPosition(account, p, this); } }, 'Plan'),
            el('button', { type: 'button', class: 'btn btn-secondary btn-sm book-start-campaign',
              title: 'Start a campaign from this position: group it with its rolls, dividends, and linked activity',
              onclick: function () { portfolioStartCampaign(account, p, this); } }, 'Campaign'),
            p.instrumentType === 'OPTION' ? el('button', { type: 'button', class: 'btn btn-secondary btn-sm',
              disabled: account.status === 'ARCHIVED' ? 'disabled' : null, onclick: function () { portfolioRollPosition(account, p); } }, 'Roll') : null))); })));
    }

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
      function () { return allocationRows(allocation.bySymbol, 'of market gross'); },
      { stateKey: 'portfolio-allocation-by-symbol' }));
    allocCard.appendChild(UI.expandable('How exposure is calculated', function () {
      return el('p', { class: 'muted small' }, 'Cash stays in capital allocation but is not market exposure. Security long and short are positive magnitudes; gross adds them and net subtracts short from long. Missing marks are excluded, never treated as zero.');
    }, { stateKey: 'portfolio-allocation-method' }));
    // The aggregates (summary, cash, allocation) read beside their parts (positions) on wide
    // desktops; campaigns — the Book's center — read directly under the positions they interpret.
    // The declared objective sits at the top of the account rail: what the account is FOR is the
    // first fact an owner deciding what to do with it should meet — never buried in Settings.
    var railCards = await Promise.all([portfolioObjectiveCard(account), portfolioCampaignsCard()]);
    root.appendChild(el('div', { class: 'band-cols book-overview-cols' },
      el('div', { class: 'band-col-controls' }, stats, railCards[0], capital, allocCard),
      el('div', { class: 'band-col-results' }, posCard, railCards[1])));
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
    var section1256Field = Learn.currentLevel() === 'expert'
      ? el('label', { class: 'check-row book-1256-flag' }, section1256,
        el('span', {}, 'Other Section 1256 contract', el('small', {}, 'Automatic: ' + automatic1256
          + '. Check this only for another eligible contract confirmed by your broker.')))
      : null;
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
      if (section1256Field) section1256Field.hidden = !isOption;
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

  function trackedRollDraft(position) {
    var longSide = position.side === 'LONG';
    var carried1256 = position.section1256 === true ? true : null;
    return { symbol: position.symbol, occurredAt: portfolioNowLocal(), fees: '0.00',
      fillNature: 'EXECUTED', feeMode: 'EXACT', packageNet: '',
      chainExpiration: position.expiration, legs: [
        { instrumentType: 'OPTION', action: longSide ? 'SELL' : 'BUY', positionEffect: 'CLOSE',
          symbol: position.symbol, optionType: position.optionType, strike: String(position.strike),
          expiration: position.expiration, quantity: position.quantity, multiplier: position.multiplier,
          price: '', section1256: carried1256 },
        { instrumentType: 'OPTION', action: longSide ? 'BUY' : 'SELL', positionEffect: 'OPEN',
          symbol: position.symbol, optionType: position.optionType, strike: String(position.strike),
          expiration: position.expiration, quantity: position.quantity, multiplier: position.multiplier,
          price: '', section1256: carried1256 }
      ] };
  }

  function portfolioTransactionForm(account) {
    var pendingRoll = App.state.portfolioBookRollTarget;
    var initialEvent = pendingRoll && pendingRoll.accountId === account.id ? 'ROLL' : 'TRADE';
    var cashEvents = ['DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT', 'INTEREST', 'DIVIDEND', 'FEE', 'ADJUSTMENT'];
    var event = portfolioSelect([
      ['TRADE', 'Buy, sell, open, or close securities'], ['ROLL', 'Roll an option position'], ['ASSIGNMENT', 'Record an option assignment'],
      ['EXERCISE', 'Record an option exercise'], ['EXPIRATION', 'Record an option expiration'],
      ['DEPOSIT', 'Deposit'], ['WITHDRAWAL', 'Withdrawal'], ['TRANSFER_IN', 'Transfer in'],
      ['TRANSFER_OUT', 'Transfer out'], ['INTEREST', 'Interest received'], ['DIVIDEND', 'Dividend received'],
      ['FEE', 'Account fee'], ['ADJUSTMENT', 'Cash adjustment']
    ], initialEvent, { id: 'portfolio-book-event' });
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
    var taxOverride = Learn.currentLevel() === 'expert'
      ? el('div', { class: 'book-dividend-tax-override', hidden: 'hidden' },
        UI.expandable('Dividend tax category override', function () { return taxField; }, {
          stateKey: 'portfolio-dividend-tax-' + account.id
        })) : null;
    var legs = el('div', { class: 'book-legs', id: 'portfolio-book-legs' });
    var addLeg = el('button', { type: 'button', class: 'btn btn-secondary btn-sm', onclick: function () {
      legs.appendChild(portfolioLegEditor({}, true));
    } }, '+ Security leg');
    var guidance = el('div', { class: 'muted small book-event-guidance' });
    var sharedTradeHost = el('div', { class: 'book-shared-position-editor', hidden: 'hidden' });
    var sharedTradeEditor = null, sharedEditorKind = null;
    var eventMeta = null, notesField = null, saveRow = null, addLegRow = null;
    var activeEvent = event.value, savedLegSets = {};

    function ensureSharedTradeEditor() {
      if (sharedTradeEditor && sharedEditorKind === 'TRADE') return sharedTradeEditor;
      sharedTradeHost.replaceChildren(); sharedTradeEditor = null; sharedEditorKind = 'TRADE';
      sharedTradeEditor = PositionEditor.render(sharedTradeHost, {
        stateKey: 'account:' + account.id + ':trade',
        title: 'Enter the exact broker trade',
        description: Learn.currentLevel() === 'beginner'
          ? 'Build the position once. Analyze can explain it with blank fills; Record requires the facts that actually happened.'
          : 'One exact package can be inspected first and then recorded. Similar contracts remain separate; a stable broker reference prevents recording the same fill twice.',
        allowRecord: true,
        recordPrimary: true,
        recordEffects: true,
        recordDisabled: account.status === 'ARCHIVED',
        recordDisabledReason: 'This tracked account is archived. Restore it before recording activity; Analyze remains available.',
        accountName: account.name,
        onAnalyze: function (payload) {
          return API.post('/api/portfolio/accounts/' + account.id + '/analyze', payload);
        },
        onRecord: async function (payload) {
          var out = await API.post('/api/portfolio/accounts/' + account.id + '/transactions', payload);
          API.flushCache();
          return out;
        },
        onRecorded: async function (out) {
          App.state.portfolioBookFocusTransaction = out && out.id || null;
          API.flushCache();
          var summary = await API.getFresh('/api/portfolio/accounts/' + account.id + '/summary');
          return el('section', { class: 'position-record-profit' },
            el('div', {}, el('span', { class: 'eyebrow' }, 'BOOK AFTER THIS RECORD'),
              el('h3', {}, 'Profit and cash, refreshed')),
            el('div', { class: 'grid grid-3' },
              stat('Realized P/L', pnlSpan(summary.realizedPnlCents)),
              stat('Unrealized P/L', summary.unrealizedPnlCents == null ? 'Unavailable' : pnlSpan(summary.unrealizedPnlCents)),
              stat('Cash in this book', fmtMoney(summary.bookCashCents))),
            el('p', { class: 'muted small' },
              'This transaction is now in Activity history. Open History to review its exact legs and cash effect.'));
        },
        findSimilar: async function (payload) {
          if (payload.externalRef) {
            var identity = await API.getFresh('/api/portfolio/accounts/' + account.id + '/transactions?source='
              + encodeURIComponent(payload.source) + '&externalRef=' + encodeURIComponent(payload.externalRef));
            if ((identity.transactions || []).length) {
              throw new Error('That ' + String(payload.source).toLowerCase()
                + ' reference is already recorded. Stable references identify the same broker fact; contract similarity is a separate advisory.');
            }
          }
          var summary = await API.getFresh('/api/portfolio/accounts/' + account.id + '/summary');
          return (summary.positions || []).filter(function (position) {
            return (payload.legs || []).some(function (leg) {
              if (leg.positionEffect !== 'OPEN' || position.symbol !== leg.symbol
                  || position.instrumentType !== leg.instrumentType) return false;
              if (leg.instrumentType === 'STOCK') return position.side === (leg.action === 'BUY' ? 'LONG' : 'SHORT');
              return position.optionType === leg.optionType
                && Number(position.strike) === Number(leg.strike)
                && position.expiration === leg.expiration
                && position.side === (leg.action === 'BUY' ? 'LONG' : 'SHORT');
            });
          }).map(function (position) { return Object.assign({ label: portfolioPositionLabel(position) }, position); });
        },
        onLinkSimilar: function (position) {
          UI.toast('Opening the existing ' + portfolioPositionLabel(position), 'ok');
          App.navigate('#/portfolio/book/overview');
        }
      });
      return sharedTradeEditor;
    }

    function mountTrackedRollEditor(position) {
      var kind = 'ROLL:' + position.instrumentKey;
      if (sharedTradeEditor && sharedEditorKind === kind) return sharedTradeEditor;
      sharedTradeHost.replaceChildren(); sharedTradeEditor = null; sharedEditorKind = kind;
      sharedTradeEditor = PositionEditor.render(sharedTradeHost, {
        stateKey: 'account:' + account.id + ':roll:' + position.instrumentKey,
        lockedSymbol: position.symbol, chain: true, allowRecord: true, recordPrimary: true,
        recordEffects: true, recordEventType: 'ROLL', recordLabel: 'Record exact roll',
        rollRecord: true, fixedLegs: true, accountName: account.name,
        title: 'Roll ' + portfolioPositionLabel(position),
        description: 'The first leg closes the recorded option; the second opens its replacement. Choose the listed replacement contract, enter both exact fills and fees, then record one linked roll.',
        initial: trackedRollDraft(position),
        recordDisabled: account.status === 'ARCHIVED',
        recordDisabledReason: 'This tracked account is archived. Restore it before recording a roll.',
        onRecord: async function (payload) {
          var out = await API.post('/api/portfolio/accounts/' + account.id + '/transactions', payload);
          API.flushCache(); return out;
        },
        onRecorded: async function (out) {
          App.state.portfolioBookRollTarget = null;
          App.state.portfolioBookFocusTransaction = out && out.id || null;
          API.flushCache();
          var summary = await API.getFresh('/api/portfolio/accounts/' + account.id + '/summary');
          return el('section', { class: 'position-record-profit' },
            el('div', {}, el('span', { class: 'eyebrow' }, 'ROLL RECORDED'),
              el('h3', {}, 'Old result realized; replacement open')),
            out && out.roll ? el('div', { class: 'chip-row' },
              chip('Net premium before fees', fmtMoney(out.roll.premiumCarryoverCents, { plus: true }))) : null,
            el('div', { class: 'grid grid-3' },
              stat('Realized P/L', pnlSpan(summary.realizedPnlCents)),
              stat('Unrealized P/L', summary.unrealizedPnlCents == null ? 'Unavailable' : pnlSpan(summary.unrealizedPnlCents)),
              stat('Cash in this book', fmtMoney(summary.bookCashCents))));
        }
      });
      return sharedTradeEditor;
    }

    async function showTrackedRollPicker() {
      var token = Date.now(); sharedTradeHost.dataset.rollPickerToken = String(token);
      sharedTradeHost.replaceChildren(UI.spinner('Loading open options…'));
      sharedTradeEditor = null; sharedEditorKind = 'ROLL_PICKER';
      try {
        var summary = await API.getFresh('/api/portfolio/accounts/' + account.id + '/summary');
        if (activeEvent !== 'ROLL' || sharedTradeHost.dataset.rollPickerToken !== String(token)) return;
        var positions = (summary.positions || []).filter(function (position) {
          return position.instrumentType === 'OPTION' && Number(position.quantity) > 0;
        });
        var picker = el('section', { class: 'position-roll-picker' },
          UI.cardHeader('Choose the option to roll'),
          el('p', { class: 'muted' }, 'A roll closes one recorded option and opens its replacement together. Select the open lot first so the close cannot target the wrong contract.'));
        if (!positions.length) picker.appendChild(UI.emptyState('No open option is available to roll',
          'Record the opening option first, or choose another activity type.'));
        else picker.appendChild(el('div', { class: 'position-roll-picker-list' }, positions.map(function (position) {
          return el('button', { type: 'button', class: 'choice', onclick: function () {
            App.state.portfolioBookRollTarget = { accountId: account.id, position: position };
            mountTrackedRollEditor(position);
          } }, el('b', {}, portfolioPositionLabel(position)),
          el('span', {}, positionSideLabel(position.side) + ' · ' + position.quantity + ' open'));
        })));
        sharedTradeHost.replaceChildren(picker);
      } catch (error) {
        if (activeEvent !== 'ROLL') return;
        sharedTradeHost.replaceChildren(UI.actionFeedback('danger', 'Open options could not be loaded', error.message || String(error)));
      }
    }

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

    function syncEvent() {
      var nextEvent = event.value;
      if (activeEvent && activeEvent !== nextEvent && legs.childNodes.length) {
        var saved = document.createDocumentFragment();
        while (legs.firstChild) saved.appendChild(legs.firstChild);
        savedLegSets[activeEvent] = saved;
      } else if (activeEvent !== nextEvent) {
        legs.replaceChildren();
      }
      activeEvent = nextEvent;
      var isCash = cashEvents.indexOf(event.value) >= 0;
      var isTrade = event.value === 'TRADE';
      var isRoll = event.value === 'ROLL';
      var usesSharedEditor = isTrade || isRoll;
      cashField.hidden = !isCash;
      feeField.hidden = isCash || usesSharedEditor;
      if (taxOverride) taxOverride.hidden = event.value !== 'DIVIDEND';
      taxCategory.innerHTML = '';
      [['', 'Ordinary dividend'], ['QUALIFIED_DIVIDEND', 'Qualified dividend'],
        ['CAPITAL_GAIN_DISTRIBUTION', 'Capital-gain distribution']].forEach(function (row) {
        taxCategory.appendChild(el('option', { value: row[0] }, row[1]));
      });
      legs.hidden = isCash || usesSharedEditor;
      sharedTradeHost.hidden = !usesSharedEditor;
      addLeg.hidden = isCash || usesSharedEditor || ['ASSIGNMENT', 'EXERCISE'].indexOf(event.value) >= 0;
      if (eventMeta) eventMeta.hidden = usesSharedEditor;
      if (notesField) notesField.hidden = usesSharedEditor;
      if (saveRow) saveRow.hidden = usesSharedEditor;
      if (addLegRow) addLegRow.hidden = isCash || usesSharedEditor || ['ASSIGNMENT', 'EXERCISE'].indexOf(event.value) >= 0;
      if (isTrade) {
        ensureSharedTradeEditor();
        guidance.textContent = 'The shared editor keeps ANALYZE and RECORD separate: missing fills may be modeled for learning, but the tracked ledger accepts factual fills only.';
        return;
      }
      if (isRoll) {
        guidance.textContent = 'Choose the recorded option first. One exact workbench owns the close, replacement, fees, linked premium, and resulting profit.';
        var target = App.state.portfolioBookRollTarget;
        if (target && target.accountId === account.id && target.position) mountTrackedRollEditor(target.position);
        else showTrackedRollPicker();
        return;
      }
      if (isCash) {
        guidance.textContent = event.value === 'ADJUSTMENT'
          ? 'Use a signed adjustment only when reconciling to a statement; keep the reason in Notes.'
          : 'This records cash in the tracked account only. It never changes the practice account.';
        return;
      }
      var savedLegs = savedLegSets[event.value];
      var restored = !!(savedLegs && savedLegs.childNodes.length);
      if (restored) legs.appendChild(savedLegs);
      if (event.value === 'ASSIGNMENT' || event.value === 'EXERCISE') {
        if (!restored) addConversionLegs(event.value);
        guidance.textContent = 'Record exactly one closing equity-option leg and its resulting stock delivery. Broad-based Section 1256 index options are cash-settled: record their exact settlement as a closing option transaction instead. Share quantity follows contracts × the contract multiplier, including adjusted contracts. The put/call sets buy versus sell; choose Open or Close to match whether the delivery created a new share position or offset one you already held.';
      } else {
        if (!restored) {
          var first = portfolioLegEditor({ instrumentType: 'OPTION', positionEffect: event.value === 'EXPIRATION' ? 'CLOSE' : 'OPEN',
            action: event.value === 'EXPIRATION' ? 'SELL' : 'BUY', price: event.value === 'EXPIRATION' ? '0.00' : '' }, false);
          if (event.value === 'EXPIRATION') { first.controls.effect.disabled = true; first.controls.price.disabled = true; }
          legs.appendChild(first);
        }
        guidance.textContent = event.value === 'EXPIRATION'
          ? 'Add each expired contract as a closing leg at $0. The lot matcher verifies that the exact position was open.'
          : 'One transaction may contain every stock and option leg in an exact package. Use Open/Close explicitly so basis cannot be inferred incorrectly.';
      }
    }
    event.addEventListener('change', syncEvent);

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
          var recorded = await API.post('/api/portfolio/accounts/' + account.id + '/transactions', {
            occurredAt: portfolioInstant(occurred, 'Activity date and time'), eventType: event.value,
            fillNature: isCash ? 'NOT_APPLICABLE' : 'EXECUTED',
            cashAmountCents: isCash ? Math.round(cashAmount * 100) : null,
            feesCents: Math.round(feeAmount * 100), taxCategory: taxCategory.value || null,
            source: source.value, externalRef: reference.value || null, notes: notes.value || null, legs: legInputs
          });
          API.flushCache();
          var summary = await API.getFresh('/api/portfolio/accounts/' + account.id + '/summary');
          message.className = 'book-record-result';
          message.replaceChildren(
            UI.actionFeedback('ok', 'Activity recorded in ' + account.name,
              'Transaction ' + ((recorded && recorded.id) || 'recorded') + ' is now part of this tracked book.'),
            el('div', { class: 'grid grid-3' },
              stat('Realized P/L', pnlSpan(summary.realizedPnlCents)),
              stat('Unrealized P/L', summary.unrealizedPnlCents == null ? 'Unavailable' : pnlSpan(summary.unrealizedPnlCents)),
              stat('Cash in this book', fmtMoney(summary.bookCashCents))));
          message.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        } catch (e) { message.textContent = e.message || String(e); message.className = 'small loss'; }
        finally { save.disabled = account.status === 'ARCHIVED'; save.removeAttribute('aria-busy'); }
      } }, 'Record activity');
    eventMeta = el('div', { class: 'form-grid book-transaction-meta' }, UI.field('Date and time', occurred),
      UI.field('Source', source), UI.field('Broker reference', reference,
        { hint: 'Required for broker-sourced activity so a repeated fill cannot be recorded twice.' }), cashField, feeField);
    notesField = UI.field('Notes', notes);
    addLegRow = el('div', { class: 'btn-row' }, addLeg);
    saveRow = el('div', { class: 'btn-row' }, save);
    var section = el('section', { class: 'card book-record-card' },
      UI.cardHeader('Record activity', el('span', { class: 'badge badge-dim' }, 'APPEND-ONLY')),
      el('p', { class: 'muted' }, Learn.currentLevel() === 'beginner'
        ? 'Copy what actually happened at your broker. StrikeBench keeps exact share and option lots, cash, fees, and basis; it never guesses whether a leg opened or closed.'
        : 'Post normalized cash or market activity to the owner-scoped accounting book. Corrections use offsetting entries; recorded history is not rewritten.'),
      el('p', { class: 'muted small' }, 'Enter market activity oldest to newest so every close can match the lots that existed at that time. CSV imports sort transaction groups and keep every multi-leg package atomic.'),
      el('div', { class: 'form-grid book-event-picker' }, UI.field('What happened?', event)),
      eventMeta, taxOverride, guidance, sharedTradeHost, legs,
      addLegRow, notesField, saveRow, message);
    syncEvent();
    return section;
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
      if (out.bookSummary) {
        result.appendChild(el('div', { class: 'grid grid-3 book-import-profit' },
          stat('Realized P/L', pnlSpan(out.bookSummary.realizedPnlCents)),
          stat('Unrealized P/L', out.bookSummary.unrealizedPnlCents == null
            ? 'Unavailable' : pnlSpan(out.bookSummary.unrealizedPnlCents)),
          stat('Cash in this book', fmtMoney(out.bookSummary.bookCashCents))));
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
          out.bookSummary = await API.getFresh('/api/portfolio/accounts/' + account.id + '/summary');
          App.state.portfolioImportResults[account.id] = out;
          API.flushCache();
          renderImportResult(out);
          result.prepend(UI.actionFeedback(out.rejectedRows ? 'caution' : 'ok',
            'Import finished beside the file you chose', out.transactionsWritten + ' transaction'
              + (out.transactionsWritten === 1 ? '' : 's') + ' recorded'
              + (out.rejectedRows ? '; ' + out.rejectedRows + ' row' + (out.rejectedRows === 1 ? '' : 's') + ' need review.' : '.')));
          result.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        } catch (e) { result.textContent = e.message || String(e); result.className = 'small loss'; }
        finally { upload.disabled = account.status === 'ARCHIVED'; upload.removeAttribute('aria-busy'); }
      } }, 'Import CSV');
    return el('section', { class: 'card card-slim book-import-card' },
      UI.cardHeader('Import a broker history'),
      el('p', { class: 'muted small' }, 'Valid transaction groups are recorded; rows that need attention come back with a reason.'),
      el('div', { class: 'book-import-row' }, file, upload,
        el('a', { class: 'btn btn-secondary', href: '/api/portfolio/import-template.csv', download: 'StrikeBench-portfolio-import-template.csv' }, 'Download template')),
      UI.expandable('How imports stay safe', function () {
        return el('p', { class: 'muted small' }, 'Transactions are sorted chronologically. Every reference is atomic: all legs land together or every row in that package is quarantined with a reason. Other valid references still import, and stable references prevent duplicates. To add history older than activity already recorded here, use a new tracked account.');
      }, { stateKey: 'portfolio-import-safety' }),
      result);
  }

  function portfolioTransactionRow(tx) {
    var cashClass = tx.cashEffectCents > 0 ? 'gain' : tx.cashEffectCents < 0 ? 'loss' : '';
    var inlineLegs = (tx.legs || []).map(portfolioLegLabel).join(' · ');
    var summary = el('div', { class: 'book-transaction-summary' },
      el('div', { class: 'book-transaction-summary-main' }, el('b', {}, transactionTypeLabel(tx.eventType)),
        el('span', { class: 'muted small' }, portfolioOccurredLabel(tx.occurredAt)),
        inlineLegs ? el('span', { class: 'book-transaction-inline-legs', title: inlineLegs }, inlineLegs) : null),
      el('div', { class: cashClass }, fmtMoney(tx.cashEffectCents, { plus: true })));
    var row = UI.expandable(summary, function () {
      return el('div', { class: 'book-transaction-detail' },
        el('div', { class: 'chip-row' }, chip('Source', transactionSourceLabel(tx.source)), chip('Fees', fmtMoney(tx.feesCents)),
          (tx.legs || []).some(function (leg) { return leg.section1256 === true; })
            ? chip('Tax character', 'Automatic · Section 1256') : null,
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
            + ' matched lot' + (tx.roll.realizedMatchIds.length === 1 ? '' : 's')
            + '; the replacement keeps its own exact basis.')) : null,
        el('div', { class: 'muted small' }, 'Recorded history is append-only.'));
    }, { stateKey: 'portfolio-transaction-' + tx.id });
    row.dataset.transactionId = tx.id;
    return row;
  }

  async function renderPortfolioBookActivity(root, account) {
    var recordForm = portfolioTransactionForm(account);
    var importCard = portfolioImportCard(account);
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
    App.state.portfolioActivityViews = App.state.portfolioActivityViews || {};
    var rollTarget = App.state.portfolioBookRollTarget;
    var activeView = rollTarget && rollTarget.accountId === account.id
      ? 'record' : (App.state.portfolioActivityViews[account.id] || 'history');
    if (App.state.portfolioBookFocusTransaction) activeView = 'history';
    var views = { history: journal, record: recordForm, import: importCard };
    if (!views[activeView]) activeView = 'history';
    var panel = el('section', { class: 'book-activity-panel', id: 'book-activity-panel', role: 'tabpanel' });
    var definitions = [['history', 'History'], ['record', 'Record activity'], ['import', 'Import history']];
    var tabs = el('div', { class: 'seg book-activity-tabs', role: 'tablist', 'aria-label': 'Tracked activity workspace' },
      definitions.map(function (definition) {
        return el('button', { type: 'button', role: 'tab', id: 'book-activity-tab-' + definition[0],
          'aria-controls': 'book-activity-panel', 'aria-selected': activeView === definition[0] ? 'true' : 'false',
          tabindex: activeView === definition[0] ? '0' : '-1', class: activeView === definition[0] ? 'active' : '',
          'data-activity-view': definition[0] }, definition[1]);
      }));
    function show(view) {
      activeView = view;
      App.state.portfolioActivityViews[account.id] = view;
      tabs.querySelectorAll('[role="tab"]').forEach(function (tab) {
        var selected = tab.dataset.activityView === view;
        tab.classList.toggle('active', selected);
        tab.setAttribute('aria-selected', String(selected));
        tab.tabIndex = selected ? 0 : -1;
      });
      panel.setAttribute('aria-labelledby', 'book-activity-tab-' + view);
      panel.replaceChildren(views[view]);
    }
    tabs.querySelectorAll('[role="tab"]').forEach(function (tab) {
      tab.addEventListener('click', function () { show(tab.dataset.activityView); });
    });
    UI.bindTabList(tabs, function (tab) { show(tab.dataset.activityView); });
    root.appendChild(tabs);
    root.appendChild(panel);
    show(activeView);
    if (App.state.portfolioBookFocusTransaction) {
      var focused = journal.querySelector('[data-transaction-id="'
        + CSS.escape(App.state.portfolioBookFocusTransaction) + '"]');
      delete App.state.portfolioBookFocusTransaction;
      if (focused) {
        focused.classList.add('plan-return-focus');
        setTimeout(function () {
          if (focused.isConnected) focused.scrollIntoView({ block: 'center', behavior: 'smooth' });
        }, 80);
      }
    }
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

  function portfolioTaxSummary(report) {
    var rulesReviewed = report.rules && report.rules.status === 'REVIEWED';
    var reconciliation = report.reconciliation && report.reconciliation.status
      ? String(report.reconciliation.status).replaceAll('_', ' ').toLowerCase() : 'not started';
    return el('section', { class: 'card book-tax-summary' },
      UI.cardHeader('Tax summary', el('span', {
        class: 'badge ' + (rulesReviewed ? 'badge-ok' : 'badge-caution')
      }, rulesReviewed ? 'REVIEWED YEAR' : 'PROVISIONAL YEAR')),
      el('div', { class: 'grid grid-3' },
        stat('Short-term gain / loss', pnlSpan(report.shortTermGainCents)),
        stat('Long-term gain / loss', pnlSpan(report.longTermGainCents)),
        stat('Interest + dividends', fmtMoney((report.ordinaryInterestCents || 0)
          + (report.ordinaryDividendCents || 0) + (report.qualifiedDividendCents || 0)
          + (report.capitalGainDistributionCents || 0)))),
      el('div', { class: 'chip-row' }, chip('Broker-form check', reconciliation)),
      el('p', { class: 'muted small' }, 'Not tax advice. These are recorded-book totals for reconciliation, not a return or an amount owed.'));
  }

  function portfolioExportCard(account, year) {
    return el('section', { class: 'card card-slim book-exports' }, UI.cardHeader('Export exact records'),
      el('p', { class: 'muted small' }, 'CSV exports every normalized transaction leg. Excel includes Summary, Transactions, Lots, Realized, Performance, and Tax sheets with numeric cells and no executable formulas.'),
      el('div', { class: 'btn-row' },
        el('a', { class: 'btn btn-secondary', href: '/api/portfolio/accounts/' + account.id + '/export.csv', download: '' }, 'Download transactions CSV'),
        el('a', { class: 'btn', href: '/api/portfolio/accounts/' + account.id + '/export.xlsx?year=' + encodeURIComponent(year), download: '' }, 'Download Excel workbook')));
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
      el('div', {}, el('h2', {}, Learn.currentLevel() === 'beginner' ? 'Records and tax summary'
        : UI.vocabulary('trackedTaxBasis') + ' and reconciliation'),
        el('p', { class: 'muted' }, account.accountType === 'TAXABLE'
          ? 'Not tax advice. Recorded facts and a bounded user-rate scenario for reconciliation, not a tax filing, tax owed, or broker 1099.'
          : 'Not tax advice. Basis and performance remain tracked; current capital-gains tax is not assigned inside this retirement wrapper.'),
        el('p', { class: 'muted small' }, 'This stays separate from ',
          UI.vocabulary('campaignEconomicBasis'), ', which interprets a multi-action campaign rather than changing tracked lots.')),
      UI.field('Tax year', year)));
    var report = null, markError = null;
    var lotsData = await API.getFresh('/api/portfolio/accounts/' + account.id + '/lots?includeClosed=false');
    try {
      report = await API.getFresh('/api/portfolio/accounts/' + account.id + '/tax?year=' + encodeURIComponent(yearValue));
    } catch (e) {
      if (!String(e.message || e).includes('year-end mark')) throw e;
      markError = e;
      var markMessage = el('div', { class: 'small', 'aria-live': 'polite' });
      var markButton = el('button', { type: 'button', class: 'btn', onclick: async function () {
        markButton.disabled = true; markButton.setAttribute('aria-busy', 'true'); markMessage.textContent = '';
        try {
          await API.post('/api/portfolio/accounts/' + account.id + '/tax/' + yearValue + '/mark-1256', {});
          UI.toast('Observed year-end Section 1256 marks applied', 'ok'); await App.render();
        } catch (markError) { markMessage.textContent = markError.message || String(markError); markMessage.className = 'small loss'; }
        finally { markButton.disabled = false; markButton.removeAttribute('aria-busy'); }
      } }, 'Apply observed year-end marks');
      var markBanner = alertBox('caution', 'Complete the year-end mark', [e.message || String(e)]);
      markBanner.classList.add('book-tax-mark-action');
      markBanner.appendChild(el('p', { class: 'muted small' },
        'StrikeBench can do this from stored observed marks. Other records and exports remain available below.'));
      markBanner.appendChild(el('div', { class: 'btn-row' }, markButton));
      markBanner.appendChild(markMessage);
      root.appendChild(markBanner);
      root.appendChild(el('section', { class: 'card card-slim book-tax-summary' }, UI.cardHeader('Tax summary'),
        el('p', { class: 'muted' }, 'The year totals will appear after the required observed year-end mark is recorded.'),
        el('p', { class: 'muted' }, 'This command uses only stored observed option marks, recognizes the year-end gain or loss at 60/40 character, and resets the open lot basis. It refuses missing or generated marks.'),
        el('p', { class: 'muted small' }, 'Not tax advice. No modeled mark is substituted.')));
    }
    var openLots = lotsData.lots || [];
    if (markError) {
      root.appendChild(portfolioExportCard(account, yearValue));
      return;
    }
    function washReview(row) {
      if (row.section1256) return 'Not applied · Section 1256 uses its own character rules';
      if ((row.realizedGainCents || 0) >= 0) return 'Not applicable · no realized loss';
      if (report.accountType !== 'TAXABLE') return 'Not applied in this retirement wrapper';
      if (!report.rules || report.rules.status !== 'REVIEWED') {
        return 'Wash rule not applied — ' + report.year + ' rules unreviewed';
      }
      return (row.washSaleAdjustmentCents || 0) !== 0
        ? fmtMoney(row.washSaleAdjustmentCents) + ' modeled adjustment'
        : 'No modeled same-instrument candidate recorded';
    }
    var beginnerTax = Learn.currentLevel() === 'beginner';
    if (beginnerTax) root.appendChild(portfolioTaxSummary(report));
    else root.appendChild(portfolioTaxFacts(report));
    var taxDetail = el('div', { class: 'book-tax-detail' });
    var rulesNotice = alertBox(report.rules.status === 'REVIEWED' ? 'caution' : 'danger',
      report.rules.status === 'REVIEWED' ? 'Reviewed common-case worksheet' : 'Tax rules not reviewed for ' + yearValue,
      [report.note]);
    rulesNotice.appendChild(el('p', { class: 'small book-tax-sources' }, 'Primary references: ',
      (report.rules.sources || []).map(function (source, index) {
        return el('span', {}, index ? ' · ' : '', el('a', { href: source.url, target: '_blank', rel: 'noopener noreferrer' }, source.title));
      })));
    taxDetail.appendChild(rulesNotice);
    if (account.accountType === 'TAXABLE') taxDetail.appendChild(portfolioTaxReconciliation(report, account, yearValue));
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
    taxDetail.appendChild(openCard);
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
            chip('Worksheet gain / loss', pnlSpan((r.realizedGainCents || 0) + (r.washSaleAdjustmentCents || 0))),
            chip('Character', character)),
          el('p', { class: 'small book-wash-review' }, el('b', {}, 'Wash review: '), washReview(r)),
          el('p', { class: 'muted small book-realized-lot-detail' }, 'Opening basis / proceeds ',
            el('b', {}, fmtMoney(r.openAmountCents)), ' · closing proceeds / cost ',
            el('b', {}, fmtMoney(r.closeAmountCents)), ' · raw gain / loss ', pnlSpan(r.realizedGainCents))));
      });
    } else realizedCard.appendChild(table(['Closed (ET)', 'Symbol', 'Instrument', 'Side', 'Qty', 'Opening basis / proceeds', 'Closing proceeds / cost', 'Raw realized', 'Wash review', 'Worksheet realized', 'Character'],
      realized.map(function (r) { return el('tr', {}, el('td', {}, portfolioTaxDate(r.closedAt)), el('td', {}, el('b', {}, r.symbol)),
        el('td', {}, r.instrumentType.toLowerCase()), el('td', {}, r.side.toLowerCase()), el('td', {}, String(r.quantity)),
        el('td', {}, fmtMoney(r.openAmountCents)), el('td', {}, fmtMoney(r.closeAmountCents)),
        el('td', {}, pnlSpan(r.realizedGainCents)), el('td', {}, washReview(r)),
        el('td', {}, pnlSpan((r.realizedGainCents || 0) + (r.washSaleAdjustmentCents || 0))),
        el('td', {}, r.section1256 ? 'Section 1256 · 60 / 40' : r.holdingTerm.replaceAll('_', ' ').toLowerCase())); })));
    taxDetail.appendChild(realizedCard);
    if (beginnerTax) {
      root.appendChild(UI.expandable('Tax detail and lot reconciliation', function () { return taxDetail; }, {
        stateKey: 'portfolio-tax-detail-' + account.id + '-' + yearValue
      }));
    } else root.appendChild(taxDetail);
    root.appendChild(portfolioExportCard(account, yearValue));
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
      UI.cardHeader('Record status', el('span', { class: 'badge ' + (account.status === 'ARCHIVED' ? 'badge-caution' : 'badge-ok') }, accountStatusLabel(account.status))),
      el('p', { class: 'muted' }, account.status === 'ARCHIVED'
        ? 'The full transaction, lot, valuation, performance, and tax history remains readable. Restore the account to add records.'
        : 'Archiving makes this account read-only without erasing its accounting history or exports.'),
      el('div', { class: 'btn-row' }, toggle), statusMessage));
    root.appendChild(el('section', { class: 'card card-slim book-boundary' }, UI.cardHeader('Accounting boundary'),
      el('p', {}, 'Tracked accounts are an owner-scoped record of external activity. They never place an order, reserve practice buying power, or mutate the practice ledger.'),
      el('p', { class: 'muted small' }, 'Adjusted contracts are supported when the recorded multiplier is the complete share deliverable. Record cash or other non-share deliverables as separate statement adjustments so the book reconciles exactly.'),
        el('p', { class: 'muted small' }, 'Recorded same-instrument wash-sale deferrals and identified Section 1256 60/40 treatment are included. Qualified-covered-call rules, straddles, loss limits and carryovers, state-specific rules, return-of-capital basis allocation, and filing elections still require reconciliation against broker tax forms and a qualified tax professional.')));
  }

  // ---- Book risk: the aggregate-risk destination (§10.2). Computed from lots directly ----

  function bookRiskUnitValue(cents, unit) {
    if (cents === null || cents === undefined) return el('span', { class: 'muted' }, 'Unavailable');
    return el('span', {}, fmtMoney(cents, { plus: true }),
      unit ? el('span', { class: 'muted small book-risk-unit' }, ' ' + unit) : null);
  }

  function bookRiskGreeksBlock(greeks, beginner) {
    var body = el('div', { class: 'book-risk-greeks' });
    body.appendChild(el('div', { class: 'grid grid-4 book-risk-greek-stats' },
      stat(UI.vocabulary('betaWeightedDelta'), bookRiskUnitValue(greeks.betaWeightedDollarDeltaCents),
        beginner ? 'How many market-adjusted dollars this options book gains if its names rise 1-for-1 with a $1 move. Positive leans long, negative leans short.' : greeks.betaCoverage),
      stat('Net dollar delta (unweighted)', bookRiskUnitValue(greeks.netDollarDeltaCents),
        beginner ? 'The same lean before market-sensitivity weighting. Raw per-share delta is never summed across different stocks.' : 'Σ Δ × units × observed spot, unweighted. Raw share delta is not additive across names and is not shown.'),
      stat('Vega', bookRiskUnitValue(greeks.vegaPerPointCents, '/ vol pt'),
        beginner ? 'Dollars gained or lost if implied volatility moves one point across the book.' : 'Dollars per implied-volatility point at current observed marks.'),
      stat('Gamma', bookRiskUnitValue(greeks.gammaPer1PctCents, '/ 1% move'),
        beginner ? 'How many dollars the lean itself changes when the stocks move 1%. Big gamma means the book’s direction can flip fast.' : 'Dollar delta change for a 1% underlying move at current observed marks.')));
    body.appendChild(el('p', { class: (greeks.unmarkedOptionLots > 0 ? 'small loss' : 'muted small') + ' book-risk-coverage' },
      greeks.greekCoverage + ' ' + greeks.betaCoverage));
    if ((greeks.betas || []).length) {
      body.appendChild(UI.expandable('Betas per symbol', function () {
        return table(['Symbol', 'Beta vs SPY', 'Observed sessions', 'Weighting'],
          greeks.betas.map(function (b) {
            return el('tr', {}, el('td', {}, el('b', {}, b.symbol)),
              el('td', {}, b.beta === null || b.beta === undefined ? '—' : Number(b.beta).toFixed(2)),
              el('td', {}, b.sessions + ' sessions'),
              el('td', {}, b.weighted ? 'beta-weighted' : el('span', { class: 'badge badge-caution' }, 'unweighted — no history')));
          }));
      }, { stateKey: 'book-risk-betas' }));
    }
    if (!beginner) body.appendChild(el('p', { class: 'muted small' }, greeks.basis));
    return body;
  }

  function bookRiskStressBlock(stress, beginner) {
    var block = el('section', { class: 'book-risk-stress' },
      el('h3', {}, UI.vocabulary('stressedAssignment')),
      el('p', { class: 'book-risk-stress-sentence' }, stress.sentence),
      el('div', { class: 'chip-row' },
        chip('Obligation under −' + stress.shockPct + '%', fmtMoney(stress.obligationCents)),
        chip('Short-put contracts obligated', String(stress.contracts) + ' contracts'),
        chip('Recorded cash', fmtMoney(stress.cashCents)),
        stress.unmarkedLots > 0 ? chip('Unstressable (no observed mark)', fmtMoney(stress.unmarkedObligationCents)) : null));
    block.appendChild(el('p', { class: 'muted small' }, beginner
      ? 'A what-if with one downside shock applied to every stock at once — a labeled heuristic, not a forecast or a broker margin number.'
      : stress.basis));
    return block;
  }

  function bookRiskExpiryBlock(expiries, beginner) {
    var rows = expiries.rows || [];
    var block = el('section', { class: 'book-risk-expiries' },
      el('h3', {}, UI.vocabulary('expiryCluster', 'Expiry calendar')));
    if (!rows.length) {
      block.appendChild(el('p', { class: 'muted small' }, 'No option expirations on the calendar — nothing expires.'));
      return block;
    }
    if (expiries.clusterNote) block.appendChild(alertBox('caution', 'Expiry cluster', [expiries.clusterNote]));
    var max = rows.reduce(function (m, r) { return Math.max(m, r.notionalCents); }, 1);
    var list = el('div', { class: 'book-risk-expiry-rows' });
    rows.forEach(function (r) {
      list.appendChild(el('div', { class: 'book-risk-expiry-row' + (r.flagged ? ' flagged' : '') },
        el('div', { class: 'book-risk-expiry-date' }, r.date,
          r.flagged ? el('span', { class: 'badge badge-caution' }, 'CLUSTER') : null),
        el('div', { class: 'allocation-track book-risk-expiry-track', role: 'img',
          'aria-label': fmtMoney(r.notionalCents) + ' notional expires ' + r.date },
          el('span', { style: 'width:' + Math.max(2, Math.round(r.notionalCents * 100 / max)) + '%' })),
        el('div', { class: 'book-risk-expiry-money' },
          el('b', {}, fmtMoney(r.notionalCents)), ' notional · ' + r.lots + ' lot' + (r.lots === 1 ? '' : 's'),
          r.shortPutObligationCents ? el('span', { class: 'muted' }, ' · ' + fmtMoney(r.shortPutObligationCents) + ' short-put obligation') : null)));
    });
    block.appendChild(list);
    block.appendChild(el('p', { class: 'muted small' }, beginner
      ? 'Dollars of strike value landing on each expiration date. Many obligations on one date settle together — one bad session decides all of them at once.'
      : expiries.basis));
    return block;
  }

  function bookRiskThemeBlock(themes, beginner) {
    var block = el('section', { class: 'book-risk-themes' },
      el('h3', {}, UI.vocabulary('themeConcentration')));
    var rows = themes.rows || [];
    if (themes.concentrationCallout) block.appendChild(alertBox('caution', 'Concentration', [themes.concentrationCallout]));
    if (!rows.length) block.appendChild(el('p', { class: 'muted small' }, 'No open lots to classify.'));
    else {
      var list = el('div', { class: 'book-risk-theme-rows' });
      rows.forEach(function (t) {
        list.appendChild(el('div', { class: 'allocation-row book-risk-theme-row' },
          el('div', { class: 'allocation-label' }, el('b', {}, t.label),
            el('span', { class: 'muted small' }, (t.share === null || t.share === undefined ? '' : fmtPct(t.share) + ' of recorded exposure · ') + t.positions + ' position' + (t.positions === 1 ? '' : 's') + ' · ' + t.symbols.join(', '))),
          el('div', { class: 'allocation-track', role: 'img',
            'aria-label': t.label + ' ' + (t.share === null || t.share === undefined ? '' : fmtPct(t.share)) },
            el('span', { style: 'width:' + Math.max(1, Math.round((t.share || 0) * 100)) + '%' })),
          el('div', { class: 'book-risk-theme-money' },
            el('b', {}, fmtMoney(t.notionalCents)), ' notional',
            t.netDollarDeltaCents === null || t.netDollarDeltaCents === undefined
              ? el('span', { class: 'muted' }, ' · net $ delta unavailable (missing marks)')
              : el('span', {}, ' · net $ delta ', pnlSpan(t.netDollarDeltaCents)),
            t.bothSides ? el('span', { class: 'badge badge-caution', style: 'margin-left:6px' }, 'BOTH DIRECTIONS') : null,
            t.basisValuedPositions ? el('span', { class: 'muted small' }, ' · ' + t.basisValuedPositions + ' valued at recorded basis (no observed mark)') : null)));
      });
      block.appendChild(list);
    }
    block.appendChild(el('p', { class: 'muted small book-risk-classification' }, themes.classificationLabel));
    return block;
  }

  function bookRiskContradictions(contradictions, collisions) {
    var host = el('div', { class: 'book-risk-contradictions' });
    (contradictions || []).forEach(function (c) {
      var detail = el('div', { class: 'chip-row' },
        (c.longVia || []).map(function (v) { return chip('Long via', v); }),
        (c.shortVia || []).map(function (v) { return chip('Short via', v); }));
      var box = alertBox('caution', 'Intra-theme contradiction — ' + c.theme, [c.message]);
      box.appendChild(detail);
      host.appendChild(box);
    });
    (collisions || []).forEach(function (message) {
      host.appendChild(alertBox('caution', 'Strategy collision', [message]));
    });
    return host;
  }

  function bookRiskChurnBlock(churn, beginner) {
    var block = el('section', { class: 'book-risk-churn' },
      el('h3', {}, UI.vocabulary('churnCost', 'Churn / whipsaw')));
    var pairs = churn.pairs || [];
    if (!pairs.length) {
      block.appendChild(el('p', { class: 'muted small' }, 'No same-symbol sell-then-rebuy round trips inside the pairing window.'));
      return block;
    }
    pairs.forEach(function (p) {
      block.appendChild(el('div', { class: 'book-risk-churn-row' },
        el('span', { class: p.costCents > 0 ? 'loss' : 'gain' }, p.message),
        el('span', { class: 'muted small' }, ' (' + p.exitAt + ' → ' + p.reentryAt + ')')));
    });
    block.appendChild(el('div', { class: 'chip-row' },
      chip('Total round-trip cost', pnlSpan(-churn.totalCostCents))));
    block.appendChild(el('p', { class: 'muted small' }, beginner
      ? 'Selling shares and buying them back higher is a real cost even though no statement line says so. Pairs are matched within 30 days of the exit — a labeled heuristic window.'
      : churn.basis));
    return block;
  }

  function bookRiskAccountCard(a, beginner, isSelected) {
    var objectiveBadge = a.objective
      ? el('span', { class: 'badge badge-dim' },
          'Objective: ' + a.objective.objective.toLowerCase().replaceAll('_', ' ')
          + (a.objective.direction ? ' · ' + a.objective.direction.toLowerCase().replaceAll('_', ' ') : '')
          + ' · rev ' + a.objective.revisionNo)
      : el('span', { class: 'badge badge-dim' }, 'No declared objective');
    var card = el('section', { class: 'card book-risk-account' + (isSelected ? ' book-risk-selected' : '') },
      UI.cardHeader(a.name + ' · ' + portfolioAccountTypeLabel(a.accountType), objectiveBadge));
    if (!a.greeks.optionLots && !(a.themes.rows || []).length && !(a.churn.pairs || []).length) {
      card.appendChild(UI.emptyState('No open lots recorded',
        'Record activity on this account and its aggregate risk appears here, computed from the lots themselves.'));
      card.appendChild(el('p', { class: 'muted small' }, 'Recorded cash: ' + fmtMoney(a.cashCents)));
      return card;
    }
    card.appendChild(bookRiskGreeksBlock(a.greeks, beginner));
    card.appendChild(bookRiskContradictions(a.contradictions, a.collisions));
    card.appendChild(el('div', { class: 'book-risk-blocks' },
      bookRiskStressBlock(a.stress, beginner),
      bookRiskExpiryBlock(a.expiries, beginner)));
    card.appendChild(el('div', { class: 'book-risk-blocks' },
      bookRiskThemeBlock(a.themes, beginner),
      bookRiskChurnBlock(a.churn, beginner)));
    return card;
  }

  function bookRiskCrossCard(cross, beginner) {
    var card = el('section', { class: 'card book-risk-cross' },
      UI.cardHeader('All tracked accounts', el('span', { class: 'badge badge-dim' }, cross.accounts + ' accounts')));
    card.appendChild(bookRiskGreeksBlock(cross.greeks, beginner));
    card.appendChild(el('section', { class: 'book-risk-stress' },
      el('h3', {}, 'Stressed obligations across accounts'),
      el('p', {}, cross.stressNote)));
    card.appendChild(bookRiskExpiryBlock(cross.expiries, beginner));
    card.appendChild(bookRiskThemeBlock(cross.themes, beginner));
    card.appendChild(el('p', { class: 'muted small' }, cross.basis));
    return card;
  }

  function bookRiskPracticeCard(practice) {
    var card = el('section', { class: 'card book-risk-practice' },
      UI.cardHeader(UI.vocabularyText('practice') + ' account — side by side',
        el('span', { class: 'badge badge-dim' }, 'never netted')));
    card.appendChild(el('div', { class: 'grid grid-4' },
      stat('Dollar delta (net)', bookRiskUnitValue(practice.dollarDeltaNetCents),
        'Practice marks in the practice lane; never combined with tracked accounts.'),
      stat('Delta', practice.deltaShares === null || practice.deltaShares === undefined
        ? el('span', { class: 'muted' }, 'Unavailable')
        : el('span', {}, fmtNum(practice.deltaShares, 2), el('span', { class: 'muted small book-risk-unit' }, ' share-equiv')),
        'Share-equivalent delta of the practice book.'),
      stat('Vega', practice.vegaPerPoint === null || practice.vegaPerPoint === undefined
        ? el('span', { class: 'muted' }, 'Unavailable')
        : el('span', {}, '$' + fmtNum(practice.vegaPerPoint, 2), el('span', { class: 'muted small book-risk-unit' }, ' / vol pt')),
        'Dollars per implied-volatility point.'),
      stat('Theta', practice.thetaPerDay === null || practice.thetaPerDay === undefined
        ? el('span', { class: 'muted' }, 'Unavailable')
        : el('span', {}, '$' + fmtNum(practice.thetaPerDay, 2), el('span', { class: 'muted small book-risk-unit' }, ' / day')),
        'Dollars collected or bled per day from time passing.')));
    if (!practice.complete) card.appendChild(el('p', { class: 'small loss' }, 'PARTIAL — at least one practice position lacks a defensible mark and was excluded, never fabricated.'));
    card.appendChild(el('p', { class: 'muted small' }, practice.basis));
    return card;
  }

  async function renderPortfolioBookRisk(root, account) {
    var beginner = Learn.currentLevel() === 'beginner';
    var data = await API.getFresh('/api/portfolio/book-risk');
    root.appendChild(el('div', { class: 'card card-slim book-risk-intro' },
      el('p', {}, UI.vocabulary('bookRisk'), beginner
        ? ' — every account added up from its recorded lots: the direction it actually leans, what a drop would force you to buy, which dates its obligations land on, and where many positions are secretly one bet.'
        : ' — aggregate exposure computed from open lots directly (never structure groupings), per account first, cross-account subtotals after, Practice side-by-side and never netted.'),
      beginner ? null : el('p', { class: 'muted small' }, data.basis)));
    var accounts = (data.accounts || []).slice().sort(function (x, y) {
      return (y.accountId === account.id ? 1 : 0) - (x.accountId === account.id ? 1 : 0);
    });
    if (!accounts.length) {
      root.appendChild(UI.emptyState('No active tracked accounts', 'Create a tracked account and record activity; its aggregate risk renders here.'));
      return;
    }
    accounts.forEach(function (a) {
      root.appendChild(bookRiskAccountCard(a, beginner, a.accountId === account.id));
    });
    var side = el('div', { class: 'book-risk-lanes' });
    if (data.crossAccount) side.appendChild(bookRiskCrossCard(data.crossAccount, beginner));
    if (data.practice) side.appendChild(bookRiskPracticeCard(data.practice));
    if (side.childNodes.length) root.appendChild(side);
  }

  async function portfolioBook(root, params) {
    var section = ['overview', 'risk', 'activity', 'performance', 'tax', 'settings'].includes(params[0]) ? params[0] : 'overview';
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
    var bookPanel = el('section', { id: 'portfolio-book-panel', role: 'tabpanel',
      'aria-labelledby': 'portfolio-book-tab-' + section });
    root.appendChild(bookPanel);
    root = bookPanel;
    if (account.status === 'ARCHIVED') root.appendChild(alertBox('caution', 'This tracked account is archived and read-only.', ['Restore it in Settings before recording new activity.']));
    var loading = el('div', { class: 'book-section-loading', 'aria-live': 'polite' },
      UI.spinner('Loading ' + section.replaceAll('_', ' ') + '…'));
    root.appendChild(loading);
    try {
      if (section === 'overview') {
        var summary = await API.getFresh('/api/portfolio/accounts/' + account.id + '/summary');
        await renderPortfolioBookOverview(root, account, summary);
        return;
      }
      if (section === 'risk') return await renderPortfolioBookRisk(root, account);
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
    root.appendChild(el('h1', {}, 'Book'));
    root.appendChild(portfolioModeNav(trackedBook));
    var modePanel = el('section', { id: 'portfolio-mode-panel', role: 'tabpanel',
      'aria-labelledby': trackedBook ? 'portfolio-mode-tracked' : 'portfolio-mode-paper' });
    root.appendChild(modePanel);
    root = modePanel;
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
        fmtMoney(sum.reservedCents) + ' is held as broker reserve for open obligations.'));
      statsRow.appendChild(stat('Buying power', fmtMoney(sum.buyingPowerCents),
        'Cash minus broker reserve — what you can still put at risk.'));
    } catch (e) {
      statsRow.appendChild(stat('Cash', fmtMoney(acct.cashCents)));
      statsRow.appendChild(stat(UI.vocabulary('brokerReserve'), fmtMoney(acct.reservedCents)));
      statsRow.appendChild(stat('Buying power', fmtMoney(acct.buyingPowerCents)));
      statsRow.appendChild(stat('Started with', fmtMoney(acct.startingCashCents)));
    }
    var paperTabs = el('div', { class: 'tabs portfolio-paper-tabs', role: 'tablist', 'aria-label': 'Practice account sections' },
      el('button', { type: 'button', role: 'tab', 'aria-controls': 'portfolio-paper-panel', 'aria-selected': section === 'construct' ? 'true' : 'false', tabindex: section === 'construct' ? '0' : '-1', class: section === 'construct' ? 'active' : '', id: 'pf-sec-construct',
        onclick: function () { App.navigate('#/portfolio/construct'); } }, 'Construct'),
      el('button', { type: 'button', role: 'tab', 'aria-controls': 'portfolio-paper-panel', 'aria-selected': section === 'positions' ? 'true' : 'false', tabindex: section === 'positions' ? '0' : '-1', class: section === 'positions' ? 'active' : '', id: 'pf-sec-positions',
        onclick: function () { App.navigate('#/portfolio/positions'); } }, 'Positions'),
      el('button', { type: 'button', role: 'tab', 'aria-controls': 'portfolio-paper-panel', 'aria-selected': section === 'activity' ? 'true' : 'false', tabindex: section === 'activity' ? '0' : '-1', class: section === 'activity' ? 'active' : '', id: 'pf-sec-activity',
        onclick: function () { App.navigate('#/portfolio/activity'); } }, 'Activity'),
      el('button', { type: 'button', role: 'tab', 'aria-controls': 'portfolio-paper-panel', 'aria-selected': section === 'record' ? 'true' : 'false', tabindex: section === 'record' ? '0' : '-1', class: section === 'record' ? 'active' : '', id: 'pf-sec-record',
        onclick: function () { App.navigate('#/portfolio/record'); } }, 'Your record'),
      el('button', { type: 'button', role: 'tab', 'aria-controls': 'portfolio-paper-panel', 'aria-selected': section === 'account' ? 'true' : 'false', tabindex: section === 'account' ? '0' : '-1', class: section === 'account' ? 'active' : '', id: 'pf-sec-account',
        onclick: function () { App.navigate('#/portfolio/account'); } }, 'Account'));
    root.appendChild(UI.bindTabList(paperTabs, function (tabButton) { tabButton.click(); }));
    var paperPanel = el('section', { id: 'portfolio-paper-panel', role: 'tabpanel',
      'aria-labelledby': 'pf-sec-' + section });
    root.appendChild(paperPanel);
    root = paperPanel;

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
        explain('Every cash or broker-reserve movement, newest first. The ledger is append-only: nothing is ever erased.'),
        table(['Date', 'Type', 'Amount', 'Cash after', 'Broker reserve after', 'Memo'],
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
              UI.confirmModal('Reset practice account?',
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
              chip(UI.vocabulary('theoreticalMaxLoss'), fmtMoney(heat.totalMaxLossCents)),
              chip('Short-vol trades', String(heat.shortVolTrades)),
              chip('Top-symbol share', heat.concentrationPct + '%',
                'How much of the book’s theoretical max loss sits in one symbol.'),
              heat.earlyAssignmentLiquidityCents > 0 ? chip(UI.vocabulary('assignmentCapital'),
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
              el('b', { style: 'margin-right:4px' }, 'Book greeks', UI.info('bookGreeks')),
              chip('Net delta', fmtNum(pg.deltaShares, 0) + ' sh'),
              chip('Gamma', fmtNum(pg.gammaShares, 2) + ' sh/$'),
              chip('Theta / day', pnlSpan(pg.thetaPerDay * 100)),
              chip('Vega / vol pt', pnlSpan(pg.vegaPerPoint * 100)),
              pg.complete ? null : el('span', { class: 'badge badge-caution' }, 'PARTIAL'));
          }
          if (Learn.currentLevel() === 'expert') {
            if (heat && heat.activeTrades > 0) {
              root.appendChild(el('div', { class: 'card card-slim', id: 'portfolio-heat' }, heatChips()));
            }
            root.appendChild(el('div', { class: 'card card-slim', id: 'portfolio-greeks' }, greekChips()));
          } else {
            root.appendChild(el('div', { class: 'card card-slim', id: 'portfolio-greeks' },
              UI.cardHeader('How your open positions react'), greekChips(),
              UI.expandable('How to read these sensitivities', function () {
                return el('div', { class: 'portfolio-risk-detail' },
                  el('p', { class: 'muted small' }, 'These estimates show how the whole practice book responds to price, time, and volatility. They use the same current marks and math shown in Expert.'),
                  heatChips());
              }, { stateKey: 'portfolio-sensitivity-guide' })));
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
          t.origin === 'EXTERNAL' ? el('span', { class: 'badge badge-warn', style: 'margin-left:6px' }, UI.vocabulary('recordedAtBroker')) : null,
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
        el('td', {}, el('span', { class: 'badge ' + (t.status === 'ACTIVE' ? 'badge-ok' : t.status === 'DELETED' ? 'badge-danger' : 'badge-dim') }, UI.positionStatusLabel(t.status)))),
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
    if (!options.insideContent) {
      var contentHost = el('div', { class: 'trade-detail-content' });
      root.appendChild(contentHost);
      return tradeDetail(contentHost, params, Object.assign({}, options, { insideContent: true }));
    }
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
    var partiallyRealized = active && t.realizedPnlCents !== null && t.realizedPnlCents !== undefined;

    root.appendChild(el('div', { class: 'card' },
      el('div', { class: 'quote-hero' },
        el('span', { class: 'sym' }, t.symbol),
        el('span', { class: 'nm' }, prettyStrategy(t.strategy) + ' · x' + t.qty),
        t.origin === 'EXTERNAL' ? el('span', { class: 'badge badge-warn' }, UI.vocabulary('recordedAtBroker')) : null,
        intentBadge(t.intent),
        el('span', { class: 'badge ' + (active ? 'badge-ok' : t.status === 'DELETED' ? 'badge-danger' : 'badge-dim') }, UI.positionStatusLabel(t.status)),
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
        chip(partiallyRealized ? 'Remaining entry basis' : 'Entry', fmtMoney(t.entryNetPremiumCents, { plus: true })),
        partiallyRealized ? chip('Realized from partial closes', pnlSpan(t.realizedPnlCents)) : null,
        t.sharesLocked > 0 && t.maxLossCents === 0
          ? chip('New cash at risk', '$0 (covered)')
          : chip(UI.vocabulary('theoreticalMaxLoss'), el('span', { class: 'loss' }, fmtMoney(t.maxLossCents))),
        chip(UI.vocabulary('theoreticalMaxProfit'), UI.maxProfitLabel(t.strategy, null, t.maxProfitCents,
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

    var detailGuide = Learn.currentLevel() !== 'expert' ? guideBlock(t.strategy) : null;
    if (detailGuide && !(d.current && d.current.greeks)) {
      root.appendChild(el('div', { class: 'card' }, detailGuide));
    }

    if (d.current && d.current.greeks) {
      var gk = d.current.greeks;
      var greekCard = el('div', { class: 'card', id: 'greeks-card' },
        UI.cardHeader(Learn.currentLevel() === 'expert' ? 'Position greeks' : 'How this position reacts',
          gk.complete ? null : el('span', { class: 'badge badge-caution' }, 'PARTIAL')));
      function positionGreekDetail() {
        var detail = el('div', { class: 'position-greek-detail' },
          el('div', { class: 'chip-row' },
          chip(el('span', {}, 'Net delta', UI.info('bookGreeks')), fmtNum(gk.deltaShares, 0) + ' sh'),
          chip('Gamma', fmtNum(gk.gammaShares, 2) + ' sh/$'),
          chip('Theta / day', pnlSpan(gk.thetaPerDay * 100)),
          chip('Vega / vol pt', pnlSpan(gk.vegaPerPoint * 100))));
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
        greekCard.appendChild(el('div', { class: 'chip-row position-greek-headline' },
          chip(el('span', {}, 'Net delta', UI.info('bookGreeks')), fmtNum(gk.deltaShares, 0) + ' sh'),
          chip('Theta / day', pnlSpan(gk.thetaPerDay * 100)),
          chip('Vega / vol pt', pnlSpan(gk.vegaPerPoint * 100))));
        if (detailGuide) greekCard.appendChild(detailGuide);
        greekCard.appendChild(UI.expandable('Exact sensitivities by leg', positionGreekDetail,
          { stateKey: 'position-greek-detail-' + id }));
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
      var rollHost = el('div', { class: 'position-roll-workbench', hidden: 'hidden' });
      var partialHost = el('div', { class: 'position-partial-workbench', hidden: 'hidden' });
      var adjustHost = el('div', { class: 'position-adjust-workbench', hidden: 'hidden' });
      var lifecycleHost = el('div', { class: 'position-lifecycle-workbench', hidden: 'hidden' });
      var refreshButton = el('button', {
        class: 'btn btn-secondary', id: 'refresh-btn', onclick: async function (ev) {
          var btn = ev.currentTarget;
          btn.disabled = true;
          try {
            if (managedPlan) await PlanStore.manage(managedPlan, 'refresh', {});
            else await API.post('/api/trades/' + id + '/refresh');
            if (managedPlan) await App.render();
            else await refreshTradeDetailNear(btn, id, 'Marks refreshed',
              'Current executable marks, P/L, odds, and sensitivities are shown below.');
          } catch (e) {
            btn.disabled = false;
            var old = document.getElementById('refresh-error');
            if (old) old.remove();
            var err = alertBox('danger', 'Refresh failed', [e.message]);
            err.id = 'refresh-error';
            btn.closest('.card').appendChild(err);
          }
        }
      }, 'Refresh marks');
      var unwindButton = el('button', {
        class: 'btn', id: 'unwind-btn', onclick: function (ev) {
          openCloseTransformation(t, managedPlan, ev.currentTarget);
        }
      }, 'Unwind…', el('span', { class: 'btn-sub' }, 'close now at market'));
      var partialButton = Number(t.qty) > 1 ? el('button', {
        class: 'btn btn-secondary', id: 'partial-close-btn', onclick: function (event) {
          rollHost.hidden = true; adjustHost.hidden = true; lifecycleHost.hidden = true;
          openPartialCloseTransformation(t, managedPlan, event.currentTarget, partialHost)
            .catch(function (error) {
              partialHost.hidden = false; partialHost.innerHTML = '';
              partialHost.appendChild(UI.actionFeedback('danger', 'Could not start the partial close', error.message || String(error)));
            });
        }
      }, 'Partial close…', el('span', { class: 'btn-sub' }, 'reduce quantity, keep history')) : null;
      var adjustButton = el('button', {
        class: 'btn btn-secondary', id: 'adjust-position-btn', onclick: async function (event) {
          partialHost.hidden = true; rollHost.hidden = true; lifecycleHost.hidden = true;
          var button = event.currentTarget; button.disabled = true;
          try { await openAdjustmentTransformation(t, managedPlan, adjustHost); }
          catch (error) {
            adjustHost.hidden = false; adjustHost.innerHTML = '';
            adjustHost.appendChild(UI.actionFeedback('danger', 'Could not start the position editor', error.message || String(error)));
          } finally { button.disabled = false; }
        }
      }, 'Adjust position…', el('span', { class: 'btn-sub' }, 'add or remove legs / shares'));
      var lifecycleButton = el('button', {
        class: 'btn btn-secondary', id: 'option-lifecycle-btn', onclick: async function (event) {
          partialHost.hidden = true; adjustHost.hidden = true; rollHost.hidden = true;
          var actionButton = event.currentTarget; actionButton.disabled = true;
          try { await openOptionLifecycleTransformation(t, managedPlan, actionButton, lifecycleHost); }
          catch (error) {
            lifecycleHost.hidden = false; lifecycleHost.innerHTML = '';
            lifecycleHost.appendChild(UI.actionFeedback('danger', 'Could not open the option event review', error.message || String(error)));
          } finally { actionButton.disabled = false; }
        }
      }, 'Option event…', el('span', { class: 'btn-sub' }, 'assignment / exercise / expiration'));
      var rollButton = el('button', {
        class: 'btn btn-secondary', id: 'roll-btn', onclick: function (event) {
          partialHost.hidden = true; adjustHost.hidden = true; lifecycleHost.hidden = true;
          openRollTransformation(t, managedPlan, event.currentTarget, rollHost)
            .catch(function (error) {
              rollHost.hidden = false; rollHost.innerHTML = '';
              rollHost.appendChild(UI.actionFeedback('danger', 'Could not start the roll', error.message || String(error)));
            });
        }
      }, 'Roll…', el('span', { class: 'btn-sub' }, 'edit + review + apply together'));
      var voidButton = el('button', {
        class: 'btn btn-danger', id: 'delete-btn', onclick: function () {
          openVoidTransformation(t, managedPlan, this);
        }
      }, 'Void…', el('span', { class: 'btn-sub' }, 'erase — practice only'));
      root.appendChild(el('div', { class: 'card position-action-card' },
        UI.cardHeader('Manage this position'),
        el('div', { class: 'position-action-layout' },
          el('section', { class: 'position-action-group position-action-manage' },
            el('span', { class: 'eyebrow' }, 'CHANGE THE POSITION'),
            el('p', { class: 'muted small' }, 'Every action previews the resulting cash, risk, and surviving contracts before it applies.'),
            el('div', { class: 'btn-row' }, unwindButton, partialButton, rollButton, adjustButton, lifecycleButton)),
          el('section', { class: 'position-action-group position-action-update' },
            el('span', { class: 'eyebrow' }, 'KEEP IT CURRENT'),
            el('p', { class: 'muted small' }, 'Reprice the open position without changing it.'), refreshButton)),
        el('section', { class: 'position-action-danger' },
          el('div', {}, el('span', { class: 'eyebrow' }, 'PRACTICE RECORD'),
            el('p', { class: 'muted small' }, 'Void reverses a practice entry. It is not a trading decision or a substitute for closing.')),
          voidButton),
        partialHost,
        adjustHost,
        lifecycleHost,
        rollHost));
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
