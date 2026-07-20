/* StrikeBench Desk backend bridge.
 *
 * This module owns no prices, paths, probabilities, payoff math, or recommendations. It only
 * sequences the canonical HTTP APIs and adapts their typed receipts into the Desk's presentation
 * model. The bundled file:// regression deliberately remains available as an offline visual
 * fixture; production mode is enabled only when the Desk is served by StrikeBench over HTTP.
 */
(function () {
  'use strict';

  var httpRuntime = window.location.protocol === 'http:' || window.location.protocol === 'https:';
  var state = {
    enabled: httpRuntime,
    requestSeq: 0,
    animationSeq: 0,
    market: null,
    plan: null,
    planIdentity: null,
    strategy: null,
    candidates: [],
    deskPickId: null,
    selected: null,
    ensemble: null,
    outcome: null,
    decision: null,
    decisionPreview: null,
    decisionPreviewKey: null,
    draft: null,
    mutationPending: false,
    animation: null,
    context: null,
    rejections: [],
    error: null
  };
  var mutationOwnerSequence = 0;
  var activeMutationOwner = null;

  function beginMutation() {
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var owner = ++mutationOwnerSequence;
    activeMutationOwner = owner;
    state.mutationPending = true;
    return owner;
  }

  function endMutation(owner) {
    if (activeMutationOwner !== owner) return;
    activeMutationOwner = null;
    state.mutationPending = false;
  }

  function copyState() {
    return Object.assign({}, state);
  }

  function bridge() {
    return window.StrikeBenchDesk || null;
  }

  function notify(phase, detail) {
    var payload = Object.assign({ phase: phase, state: copyState() }, detail || {});
    var owner = bridge();
    if (owner && typeof owner.backendChanged === 'function') owner.backendChanged(payload);
    document.dispatchEvent(new CustomEvent('strikebench:desk-backend', { detail: payload }));
  }

  function fail(seq, phase, error) {
    if (seq !== state.requestSeq) return null;
    state.error = error;
    notify('error', { operation: phase, error: error });
    throw error;
  }

  function requireApi() {
    if (!window.API) throw new Error('The StrikeBench API client is unavailable.');
    return window.API;
  }

  function number(value) {
    if (value == null || value === '') return null;
    var parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  function quoteMark(quote) {
    var bid = number(quote && quote.bid), ask = number(quote && quote.ask), last = number(quote && quote.last);
    var previousClose = number(quote && quote.prevClose);
    if (bid != null && ask != null && bid > 0 && ask > 0 && ask >= bid) {
      return { value: (bid + ask) / 2, basis: 'MID' };
    }
    if (last != null && last > 0) return { value: last, basis: 'LAST' };
    if (previousClose != null && previousClose > 0) {
      return { value: previousClose, basis: 'PREVIOUS_CLOSE' };
    }
    return { value: null, basis: 'UNAVAILABLE' };
  }

  function horizonDays(context) {
    var raw = context && context.horizon;
    var parsed = raw == null ? null : Number(String(raw).match(/\d+/) && String(raw).match(/\d+/)[0]);
    return parsed && parsed > 0 ? Math.min(756, parsed) : 45;
  }

  function intentOf(goal) {
    var key = String(goal || '').trim().toUpperCase();
    if (key === 'HEDGE') return 'HEDGE';
    if (key === 'DIRECTIONAL') return 'DIRECTIONAL';
    return 'INCOME';
  }

  function thesisOf(view) {
    var key = String(view || '').trim().toLowerCase();
    return key || 'neutral';
  }

  function riskModeOf(context) {
    var explicit = String(context && context.riskMode || '').trim().toLowerCase();
    if (explicit === 'conservative' || explicit === 'balanced' || explicit === 'aggressive') return explicit;
    var risk = number(context && context.governors && context.governors.risk);
    if (risk == null && window.decide && window.decide.govs) risk = number(window.decide.govs.risk);
    if (risk == null && window.POSTURE) risk = number(window.POSTURE.risk);
    if (risk != null && risk <= 2500) return 'conservative';
    if (risk != null && risk >= 10000) return 'aggressive';
    return 'balanced';
  }

  function dateParts(raw) {
    var match = String(raw || '').match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!match) return null;
    return { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) };
  }

  function dateOrdinal(raw) {
    var parts = dateParts(raw);
    return parts ? Math.floor(Date.UTC(parts.year, parts.month - 1, parts.day) / 86400000) : null;
  }

  function sessionDistance(asOfDate, expiration) {
    var start = dateOrdinal(asOfDate), end = dateOrdinal(expiration);
    if (start == null || end == null || end < start) return Number.MAX_VALUE;
    var sessions = 0;
    for (var day = start + 1; day <= end; day++) {
      var weekday = new Date(day * 86400000).getUTCDay();
      if (weekday !== 0 && weekday !== 6) sessions++;
    }
    return sessions;
  }

  function quoteAsOfDate(quote) {
    var raw = quote && quote.asOf;
    if (typeof raw === 'number' && Number.isFinite(raw)) return new Date(raw).toISOString().slice(0, 10);
    if (typeof raw === 'string' && raw) {
      var direct = dateParts(raw);
      if (direct) return String(raw).slice(0, 10);
      var parsed = Date.parse(raw);
      if (Number.isFinite(parsed)) return new Date(parsed).toISOString().slice(0, 10);
    }
    return null;
  }

  function chooseExpiration(expirations, targetDays, asOfDate) {
    var rows = Array.isArray(expirations) ? expirations.slice() : [];
    if (!rows.length) return null;
    if (asOfDate) rows = rows.filter(function (row) {
      var distance = sessionDistance(asOfDate, row);
      return Number.isFinite(distance) && distance !== Number.MAX_VALUE;
    });
    rows.sort(function (a, b) {
      return Math.abs(sessionDistance(asOfDate, a) - targetDays)
        - Math.abs(sessionDistance(asOfDate, b) - targetDays);
    });
    return rows[0];
  }

  var FRESHNESS_RANK = {
    REALTIME: 0, DELAYED: 1, EOD: 2, STALE: 3,
    SIMULATED: 4, MODELED: 5, FIXTURE: 6, MISSING: 7
  };

  function worseFreshness(a, b) {
    var left = String(a || 'MISSING').toUpperCase(), right = String(b || 'MISSING').toUpperCase();
    var li = Object.prototype.hasOwnProperty.call(FRESHNESS_RANK, left) ? FRESHNESS_RANK[left] : 7;
    var ri = Object.prototype.hasOwnProperty.call(FRESHNESS_RANK, right) ? FRESHNESS_RANK[right] : 7;
    return li >= ri ? left : right;
  }

  function marketEvidence(identity, quote, chain, expirationDoc, mark) {
    var quoteEvidence = quote && quote.evidence || null;
    var quoteFreshness = quote && quote.freshness || null;
    if (mark.basis === 'PREVIOUS_CLOSE') {
      var provenance = String(quoteEvidence && quoteEvidence.provenance || '').toUpperCase();
      quoteFreshness = provenance === 'OBSERVED' || provenance === 'BROKER' ? 'EOD' : 'STALE';
    }
    var chainEvidence = chain && chain.evidence || {
      source: chain && chain.source || null,
      age: chain && chain.freshness || null,
      provenance: null
    };
    return {
      lane: identity.marketLane,
      world: identity.world,
      datasetId: identity.datasetId,
      accountId: identity.accountId,
      revision: identity.revision,
      epoch: identity.epoch,
      source: quote && (quote.source || quoteEvidence && quoteEvidence.source) || null,
      freshness: worseFreshness(quoteFreshness, chain && chain.freshness),
      evidence: quoteEvidence,
      quote: {
        source: quote && (quote.source || quoteEvidence && quoteEvidence.source) || null,
        freshness: quoteFreshness,
        evidence: quoteEvidence,
        asOf: quote && quote.asOf || null,
        markBasis: mark.basis
      },
      chain: {
        source: chain && chain.source || null,
        freshness: chain && chain.freshness || null,
        evidence: chainEvidence,
        asOf: chain && chain.asOfEpochMs || null,
        expiration: chain && chain.expiration || null
      },
      expirationAsOfDate: expirationDoc && expirationDoc.asOfDate || null,
      asOf: quote && quote.asOf || chain && chain.asOfEpochMs || null
    };
  }

  function optionalFresh(path) {
    return requireApi().getFresh(path).catch(function (error) {
      if (error && error.status === 404) return null;
      throw error;
    });
  }

  function marketIdentity(config, world, quoteEnvelope, account) {
    var activeWorld = world && world.world || config && config.world || quoteEnvelope && quoteEnvelope.world || null;
    return {
      world: activeWorld,
      revision: world && world.revision == null ? null : Number(world.revision),
      epoch: world && world.epoch || null,
      datasetId: config && config.activeDataset || null,
      marketLane: config && config.marketLane || quoteEnvelope && quoteEnvelope.marketLane || null,
      accountId: account && account.account && account.account.id || null
    };
  }

  function assertSameMarket(before, after) {
    ['world', 'epoch', 'datasetId', 'marketLane'].forEach(function (key) {
      if (before[key] != null && after[key] != null && String(before[key]) !== String(after[key])) {
        throw new Error('The active market ' + key + ' changed while the Desk was loading. Reload this idea in the current market.');
      }
    });
    if (before.revision != null && after.revision != null && before.revision !== after.revision) {
      throw new Error('The active market changed while the Desk was loading. Reload this idea in the current market.');
    }
  }

  function assertEvidenceLane(evidence, lane, label) {
    var provenance = String(evidence && evidence.provenance || '').toUpperCase();
    var marketLane = String(lane || '').toUpperCase();
    if (!provenance || !marketLane) return;
    var allowed = marketLane === 'OBSERVED' ? ['OBSERVED', 'BROKER']
      : marketLane === 'DEMO' ? ['DEMO']
      : marketLane === 'SIMULATED' ? ['SIMULATED']
      : marketLane === 'SCENARIO' ? ['MODELED', 'OBSERVED'] : [];
    if (allowed.length && allowed.indexOf(provenance) < 0) {
      throw new Error(label + ' provenance ' + provenance + ' cannot be used in the ' + marketLane + ' market lane.');
    }
  }

  async function loadMarket(symbol, targetDays, seq) {
    var api = requireApi();
    var encoded = encodeURIComponent(symbol);
    var base = await Promise.all([
      api.getFresh('/api/config'),
      api.getFresh('/api/status'),
      api.getFresh('/api/world'),
      api.getFresh('/api/quotes?symbols=' + encoded),
      api.getFresh('/api/research/' + encoded + '/expirations'),
      optionalFresh('/api/account')
    ]);
    if (seq !== state.requestSeq) return null;
    var identity = marketIdentity(base[0], base[2], base[3], base[5]);
    if (base[0] && base[0].world && base[2] && base[2].world
        && String(base[0].world) !== String(base[2].world)) {
      throw new Error('Configuration and the active market world do not agree. Reload after the market transition completes.');
    }
    if (base[3] && base[3].world && identity.world
        && String(base[3].world) !== String(identity.world)) {
      throw new Error('The quote belongs to a different market world than the active Desk.');
    }
    if (base[3] && base[3].marketLane && identity.marketLane
        && String(base[3].marketLane).toUpperCase() !== String(identity.marketLane).toUpperCase()) {
      throw new Error('The quote provenance lane does not match the active market lane.');
    }
    var quotes = base[3] && base[3].quotes || [];
    var quote = quotes.find(function (row) { return String(row.symbol).toUpperCase() === symbol; });
    if (!quote) throw new Error(symbol + ' has no quote in the active StrikeBench market.');
    assertEvidenceLane(quote.evidence, identity.marketLane, 'Quote');
    var mark = quoteMark(quote), spot = mark.value;
    if (!(spot > 0)) throw new Error(symbol + ' has no usable market-owned mark.');
    var expirationAsOf = base[4] && base[4].asOfDate || quoteAsOfDate(quote);
    var expiration = chooseExpiration(base[4] && base[4].expirations, targetDays, expirationAsOf);
    if (!expiration) throw new Error(symbol + ' has no option expiration in the active market.');
    var chain = await api.getFresh('/api/research/' + encoded + '/chain?expiration=' + encodeURIComponent(expiration));
    if (seq !== state.requestSeq) return null;
    var optionCount = (chain && Array.isArray(chain.calls) ? chain.calls.length : 0)
      + (chain && Array.isArray(chain.puts) ? chain.puts.length : 0);
    if (!chain || chain.empty || !optionCount) {
      throw new Error(symbol + ' has no usable option chain for ' + expiration + '.');
    }
    if (chain.underlying && String(chain.underlying).toUpperCase() !== symbol) {
      throw new Error('The option chain belongs to ' + chain.underlying + ', not ' + symbol + '.');
    }
    if (chain.expiration && String(chain.expiration) !== String(expiration)) {
      throw new Error('The option chain expiration changed while the Desk was loading.');
    }
    assertEvidenceLane(chain.evidence, identity.marketLane, 'Option-chain');
    var stable = await Promise.all([api.getFresh('/api/config'), api.getFresh('/api/world')]);
    if (seq !== state.requestSeq) return null;
    assertSameMarket(identity, marketIdentity(stable[0], stable[1], base[3], base[5]));
    var market = {
      config: base[0], status: base[1], world: base[2], quote: quote,
      expirations: base[4].expirations, expiration: expiration, chain: chain,
      account: base[5] && base[5].account || null,
      identity: identity,
      spot: spot, provenance: marketEvidence(identity, quote, chain, base[4], mark)
    };
    state.market = market;
    notify('market', { market: market });
    return market;
  }

  function optionalInteger(value) {
    var parsed = number(value);
    return parsed == null ? null : Math.round(parsed);
  }

  function requestedPlanContext(context) {
    return {
      thesis: thesisOf(context.view),
      horizonDays: horizonDays(context),
      riskMode: riskModeOf(context),
      targetCents: optionalInteger(context.targetCents),
      holdingsShares: optionalInteger(context.holdingsShares),
      costBasisCents: optionalInteger(context.costBasisCents),
      priceAssumptionCents: optionalInteger(context.priceAssumptionCents),
      assignmentPreference: context.assignmentPreference == null
        ? null : String(context.assignmentPreference).trim(),
      originPlanId: context.originPlanId == null ? null : String(context.originPlanId)
    };
  }

  function expectedMarketKind(identity) {
    if (identity && identity.world === 'demo') return 'DEMO';
    if (identity && identity.world && identity.world !== 'observed') return 'SIMULATED';
    return 'OBSERVED';
  }

  function planIdentity(symbol, intent, context, market) {
    var requested = requestedPlanContext(context);
    return {
      symbol: symbol,
      intent: intent,
      marketKind: expectedMarketKind(market && market.identity),
      world: market && market.identity && market.identity.world || null,
      datasetId: market && market.identity && market.identity.datasetId || null,
      accountId: market && market.identity && market.identity.accountId || null,
      originPlanId: requested.originPlanId,
      thesis: requested.thesis,
      horizonDays: requested.horizonDays,
      riskMode: requested.riskMode,
      targetCents: requested.targetCents,
      holdingsShares: requested.holdingsShares,
      costBasisCents: requested.costBasisCents,
      priceAssumptionCents: requested.priceAssumptionCents,
      assignmentPreference: requested.assignmentPreference
    };
  }

  function stringHash(value) {
    var hash = 2166136261;
    for (var i = 0; i < value.length; i++) {
      hash ^= value.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
  }

  function sessionRequestId(identity) {
    var material = JSON.stringify(identity);
    var key = 'strikebench.desk.planRequest.v2.' + stringHash(material);
    var existing = null;
    try { existing = window.sessionStorage.getItem(key); } catch (ignored) { /* storage can be disabled */ }
    if (existing) return existing;
    var suffix = window.crypto && window.crypto.randomUUID
      ? window.crypto.randomUUID() : Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
    var value = 'desk-' + suffix;
    try { window.sessionStorage.setItem(key, value); } catch (ignored2) { /* idempotency still holds in-memory */ }
    return value;
  }

  function sameNullable(left, right) {
    if (left == null || left === '') return right == null || right === '';
    if (typeof left === 'number') return Number(right) === left;
    return String(right || '').toLowerCase() === String(left).toLowerCase();
  }

  function samePlan(plan, identity) {
    if (!plan || plan.open === false || plan.status === 'ARCHIVED') return false;
    if (plan.symbol !== identity.symbol || String(plan.intent || '').toUpperCase() !== identity.intent) return false;
    if (String(plan.marketKind || '').toUpperCase() !== identity.marketKind) return false;
    if (identity.marketKind === 'SIMULATED' && String(plan.worldId || '') !== String(identity.world || '')) return false;
    if (identity.marketKind !== 'SIMULATED' && plan.worldId != null
        && String(plan.worldId) !== String(identity.world || '').toLowerCase()) return false;
    if (identity.accountId && String(plan.accountId || '') !== String(identity.accountId)) return false;
    if (!sameNullable(identity.originPlanId, plan.originPlanId)) return false;
    var ctx = plan.context || {};
    if (String(ctx.thesis || '').toLowerCase() !== identity.thesis
        || Number(ctx.horizonDays || 0) !== identity.horizonDays
        || String(ctx.riskMode || '').toLowerCase() !== identity.riskMode) return false;
    // Optional declarations are compared whenever the Desk supplied them. Server-snapshotted
    // holdings may legitimately populate an otherwise absent field for INCOME/HEDGE/EXIT.
    return sameNullable(identity.targetCents, ctx.targetCents)
      && (identity.holdingsShares == null || Number(ctx.holdingsShares) === identity.holdingsShares)
      && (identity.costBasisCents == null || Number(ctx.costBasisCents) === identity.costBasisCents)
      && sameNullable(identity.priceAssumptionCents, ctx.priceAssumptionCents)
      && sameNullable(identity.assignmentPreference, ctx.assignmentPreference);
  }

  function acceptPlan(plan) {
    if (!plan) return state.plan;
    if (state.plan && state.plan.id !== plan.id) {
      throw new Error('A response attempted to replace the active Desk Plan with another Plan.');
    }
    if (state.planIdentity && !samePlan(plan, state.planIdentity)) {
      throw new Error('The active Plan no longer matches this Desk idea and market identity.');
    }
    if (!state.plan || Number(plan.version || 0) >= Number(state.plan.version || 0)) state.plan = plan;
    return state.plan;
  }

  async function freshestMatchingPlan(rows, identity, seq) {
    var candidates = (rows || []).filter(function (row) { return samePlan(row, identity); })
      .sort(function (a, b) {
        var time = String(b.updatedAt || '').localeCompare(String(a.updatedAt || ''));
        return time || Number(b.version || 0) - Number(a.version || 0);
      });
    for (var i = 0; i < candidates.length; i++) {
      var listed = candidates[i], exact;
      try {
        exact = await requireApi().getFresh('/api/plans/' + encodeURIComponent(listed.id));
      } catch (error) {
        // Older deterministic Desk fixtures did not expose the detail read. Production does.
        if (!error || error.status !== 404) throw error;
        exact = listed;
      }
      if (seq !== state.requestSeq) return null;
      if (samePlan(exact, identity)) return exact;
    }
    return null;
  }

  async function ensurePlan(context, market, seq) {
    var api = requireApi(), symbol = String(context.symbol || '').trim().toUpperCase();
    var intent = intentOf(context.goal);
    var identity = planIdentity(symbol, intent, context, market);
    var listed = await api.getFresh('/api/plans');
    if (seq !== state.requestSeq) return null;
    if (listed && listed.world && market.identity.world
        && String(listed.world) !== String(market.identity.world)) {
      throw new Error('The Plan list belongs to another market world.');
    }
    if (listed && listed.market && String(listed.market).toUpperCase() !== identity.marketKind) {
      throw new Error('The Plan list belongs to another market lane.');
    }
    var plan = await freshestMatchingPlan(listed && listed.plans, identity, seq);
    if (!plan) {
      var requested = requestedPlanContext(context);
      plan = await api.post('/api/plans', {
        clientRequestId: sessionRequestId(identity),
        symbol: symbol,
        intent: intent,
        originPlanId: requested.originPlanId,
        title: 'Desk ' + String(context.goal || 'idea'),
        thesis: requested.thesis,
        horizonDays: requested.horizonDays,
        riskMode: requested.riskMode,
        targetCents: requested.targetCents,
        holdingsShares: requested.holdingsShares,
        costBasisCents: requested.costBasisCents,
        priceAssumptionCents: requested.priceAssumptionCents,
        assignmentPreference: requested.assignmentPreference
      });
    }
    if (seq !== state.requestSeq) return null;
    if (!samePlan(plan, identity)) {
      throw new Error('The returned Plan does not match this Desk idea, account, and market identity.');
    }
    state.planIdentity = identity;
    state.plan = plan;
    notify('plan', { plan: plan });
    return plan;
  }

  function legToDesk(leg, qty) {
    var type = String(leg.type || '').toUpperCase();
    var direction = String(leg.action || '').toUpperCase() === 'SELL' ? -1 : 1;
    var multiplier = Number(leg.multiplier || (type === 'STOCK' ? 1 : 100));
    return {
      t: type === 'CALL' ? 'c' : type === 'PUT' ? 'p' : 's',
      k: type === 'STOCK' ? number(leg.entryPrice) : number(leg.strike),
      q: direction * Math.max(1, Number(leg.ratio || 1)) * Math.max(1, Number(qty || 1))
        * (type === 'STOCK' ? multiplier : 1),
      expiration: leg.expiration || null,
      multiplier: multiplier,
      ratio: Math.max(1, Number(leg.ratio || 1)),
      type: type,
      strike: leg.strike == null ? null : number(leg.strike),
      action: leg.action,
      positionEffect: leg.positionEffect,
      entryPrice: leg.entryPrice
    };
  }

  function greatestCommonDivisor(left, right) {
    left = Math.abs(Math.round(Number(left) || 0));
    right = Math.abs(Math.round(Number(right) || 0));
    while (right) {
      var next = left % right;
      left = right;
      right = next;
    }
    return left;
  }

  function draftSourceCandidate(candidateId) {
    return state.candidates.find(function (candidate) {
      return String(candidate.id) === String(candidateId);
    }) || null;
  }

  function availableDraftStrikes(type) {
    var chain = state.market && state.market.chain || {};
    var rows = String(type || '').toLowerCase() === 'p' ? chain.puts : chain.calls;
    return Array.from(new Set((rows || []).map(function (quote) {
      return number(quote && quote.strike);
    }).filter(function (strike) { return strike != null && strike > 0; })))
      .sort(function (a, b) { return a - b; });
  }

  function draftCatalog() {
    if (!state.enabled || !state.market) return null;
    return {
      expiration: state.market.expiration,
      // The Desk loads one exact chain at a time. Do not advertise expirations whose strike
      // catalog has not been loaded and verified in this market identity.
      expirations: state.market.expiration ? [state.market.expiration] : [],
      calls: availableDraftStrikes('c'),
      puts: availableDraftStrikes('p')
    };
  }

  function canonicalDraftPosition(legs, sourceCandidate) {
    if (!state.plan || !state.market) throw new Error('Load the active Plan and option chain before editing a package.');
    if (!sourceCandidate || !state.selected
        || String(sourceCandidate.id) !== String(state.selected.id)) {
      throw new Error('The draft source is no longer the selected backend strategy. Start the edit again.');
    }
    var entered = (Array.isArray(legs) ? legs : []).filter(function (leg) {
      return leg && Number(leg.q) !== 0;
    });
    if (!entered.length) throw new Error('An exact package needs at least one non-zero leg.');
    entered.forEach(function (leg) {
      if (!Number.isInteger(Number(leg.q))) throw new Error('Every leg quantity must be a whole number.');
    });
    var packageQty = entered.reduce(function (common, leg) {
      return greatestCommonDivisor(common, Math.abs(Number(leg.q)));
    }, 0);
    if (!packageQty) throw new Error('The package quantity could not be derived from its legs.');
    var expirations = state.market.expirations || [];
    var currentExpiration = String(state.market.expiration || '');
    var canonicalLegs = entered.map(function (leg) {
      var kind = String(leg.t || '').toLowerCase();
      var stock = kind === 's';
      if (!stock && kind !== 'c' && kind !== 'p') throw new Error('Each option leg must be a call or put.');
      var expiration = stock ? null : String(leg.expiration || currentExpiration);
      if (!stock && expirations.indexOf(expiration) < 0) {
        throw new Error('Choose an expiration supplied by the active backend option chain.');
      }
      var strike = stock ? null : number(leg.k);
      if (!stock && !(strike > 0)) throw new Error('Each option leg needs a backend-owned strike.');
      if (!stock && expiration === currentExpiration
          && availableDraftStrikes(kind).indexOf(strike) < 0) {
        throw new Error('Choose a strike supplied by the active backend option chain.');
      }
      var multiplier = stock ? 1 : Math.max(1, Math.round(Number(leg.multiplier || 100)));
      var total = Math.abs(Number(leg.q));
      var ratio = stock ? total / packageQty : total / packageQty;
      if (!Number.isInteger(ratio) || ratio < 1) {
        throw new Error('Leg quantities must reduce to whole package ratios.');
      }
      return {
        action: Number(leg.q) < 0 ? 'SELL' : 'BUY',
        type: stock ? 'STOCK' : kind === 'c' ? 'CALL' : 'PUT',
        strike: strike,
        expiration: expiration,
        ratio: ratio,
        multiplier: multiplier,
        positionEffect: 'OPEN',
        entryPrice: null
      };
    });
    return {
      symbol: state.plan.symbol,
      strategy: 'CUSTOM',
      qty: packageQty,
      legs: canonicalLegs,
      thesis: state.plan.context && state.plan.context.thesis,
      horizon: Number(state.plan.context && state.plan.context.horizonDays || 30) <= 1 ? '0dte'
        : Number(state.plan.context && state.plan.context.horizonDays || 30) <= 7 ? 'week'
          : Number(state.plan.context && state.plan.context.horizonDays || 30) <= 45 ? 'month' : 'quarter',
      riskMode: state.plan.context && state.plan.context.riskMode,
      intent: state.plan.intent,
      useHeldShares: sourceCandidate.usesHeldShares === true,
      recommendationId: sourceCandidate.recommendationId || null,
      proposedNetCents: null,
      feesOverrideCents: null,
      source: 'BUILDER',
      fillNature: 'PROPOSED'
    };
  }

  function previewLegs(position, preview) {
    var marked = preview && Array.isArray(preview.legs) ? preview.legs : [];
    return position.legs.map(function (leg, index) {
      var out = Object.assign({}, leg);
      var fill = marked[index] && marked[index].fill;
      if (fill != null) out.entryPrice = fill;
      return out;
    });
  }

  function draftCandidateFromPreview(position, response) {
    var preview = response.preview || {};
    var identity = response.identity || null;
    var candidate = {
      id: 'desk-exact-package-draft',
      label: identity && identity.label || 'Exact package draft',
      displayName: identity && identity.label || 'Exact package draft',
      strategy: identity && identity.family || 'CUSTOM',
      symbol: position.symbol,
      qty: position.qty,
      legs: previewLegs(position, preview),
      entryNetPremiumCents: preview.entryNetPremiumCents,
      maxLossCents: preview.maxLossCents,
      maxProfitCents: preview.maxProfitCents,
      combinedMaxLossCents: preview.analytics && preview.analytics.combinedMaxLossCents,
      breakevens: preview.breakevens || [],
      pop: preview.popEntry,
      assignmentProb: preview.assignmentProb,
      expectedValueCents: preview.expectedValueCents,
      freshness: preview.freshness,
      sourceKind: 'EXACT_BACKEND_PREVIEW',
      whyConsidered: 'Exact package valued by the active StrikeBench preview service.',
      usesHeldShares: position.useHeldShares === true,
      evaluation: response.evaluation || null,
      positionIdentity: identity
    };
    var desk = candidateToDesk(candidate, state.market);
    desk.build = true;
    desk.draft = true;
    desk.preview = preview;
    desk.payoffPoints = (preview.payoff || []).map(function (point) {
      return { price: Number(point.price), profit: Number(point.profitCents) / 100 };
    }).filter(function (point) {
      return Number.isFinite(point.price) && Number.isFinite(point.profit);
    });
    return desk;
  }

  var draftPreviewSeq = 0;

  async function previewDraft(legs, sourceCandidateId) {
    if (!state.enabled) return null;
    var token = ++draftPreviewSeq;
    var baseRequestSeq = state.requestSeq;
    var basePlanId = state.plan && state.plan.id;
    var basePlanVersion = state.plan && state.plan.version;
    var baseMarketIdentity = Object.assign({}, state.market && state.market.identity || {});
    var sourceCandidate = draftSourceCandidate(sourceCandidateId);
    var position;
    state.animationSeq++;
    state.animation = null;
    invalidateDecisionPreview('draft-changed');
    try {
      position = canonicalDraftPosition(legs, sourceCandidate);
    } catch (error) {
      if (token !== draftPreviewSeq) return null;
      state.draft = {
        sourceCandidateId: sourceCandidateId,
        position: null,
        preview: null,
        candidate: null,
        valid: false,
        pending: false,
        error: error.message
      };
      notify('draft-error', { operation: 'draft-preview', error: error, draft: state.draft });
      return null;
    }
    state.draft = {
      sourceCandidateId: sourceCandidateId,
      position: position,
      preview: null,
      candidate: null,
      valid: false,
      pending: true,
      error: null
    };
    notify('draft-pending', { operation: 'draft-preview', draft: state.draft });
    try {
      var response = await requireApi().post('/api/trades/preview', position);
      if (token !== draftPreviewSeq || baseRequestSeq !== state.requestSeq
          || !state.selected || String(state.selected.id) !== String(sourceCandidateId)) return null;
      var stable = await Promise.all([
        requireApi().getFresh('/api/config'),
        requireApi().getFresh('/api/world'),
        optionalFresh('/api/account')
      ]);
      if (token !== draftPreviewSeq || baseRequestSeq !== state.requestSeq) return null;
      assertSameMarket(baseMarketIdentity, marketIdentity(stable[0], stable[1], null, stable[2]));
      var stableAccountId = stable[2] && stable[2].account && stable[2].account.id || null;
      if (baseMarketIdentity.accountId != null && stableAccountId != null
          && String(baseMarketIdentity.accountId) !== String(stableAccountId)) {
        throw new Error('The active account changed while this exact package was being priced. Preview it again.');
      }
      if (!state.plan || String(state.plan.id) !== String(basePlanId)
          || Number(state.plan.version) !== Number(basePlanVersion)) {
        throw new Error('The Plan changed while this exact package was being priced. Preview it again.');
      }
      var guardrailLevel = String(response && response.guardrails && response.guardrails.level || '').toUpperCase();
      var valid = !!(response && response.preview && response.preview.ok === true && guardrailLevel !== 'BLOCK');
      var presentation = draftCandidateFromPreview(position, response);
      state.draft = {
        sourceCandidateId: sourceCandidateId,
        position: position,
        preview: response,
        candidate: presentation,
        valid: valid,
        pending: false,
        error: valid ? null : (response.preview.blockReasons || response.guardrails && response.guardrails.blockReasons || []).join('; ')
      };
      notify('draft-preview', { operation: 'draft-preview', draft: state.draft, candidate: presentation });
      return state.draft;
    } catch (error) {
      if (token !== draftPreviewSeq || baseRequestSeq !== state.requestSeq) return null;
      state.draft = {
        sourceCandidateId: sourceCandidateId,
        position: position,
        preview: null,
        candidate: null,
        valid: false,
        pending: false,
        error: error.message
      };
      notify('draft-error', { operation: 'draft-preview', error: error, draft: state.draft });
      return null;
    }
  }

  function cancelDraft() {
    draftPreviewSeq++;
    state.draft = null;
    notify('draft-cancelled', { operation: 'draft-cancel' });
  }

  async function useDraft() {
    if (!state.enabled) return null;
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var draft = state.draft;
    if (!draft || draft.pending || !draft.valid || !draft.preview || !draft.position) {
      throw new Error('Wait for a valid exact-package preview before using this structure.');
    }
    if (!state.plan || !state.selected
        || String(state.selected.id) !== String(draft.sourceCandidateId)) {
      throw new Error('The selected strategy changed after this draft was priced. Preview it again.');
    }
    var ensembleIdentity = state.ensemble && state.ensemble.ensemble && {
      id: state.ensemble.ensemble.id,
      fingerprint: state.ensemble.ensemble.fingerprint
    };
    if (!ensembleIdentity || !ensembleIdentity.id || !ensembleIdentity.fingerprint) {
      throw new Error('Load the active outcome ensemble before selecting an exact package.');
    }
    var seq = ++state.requestSeq;
    draftPreviewSeq++;
    state.mutationPending = true;
    state.draft = Object.assign({}, draft, { pending: true, applying: true });
    notify('draft-applying', { operation: 'draft-select', draft: state.draft });
    var presentation = null;
    try {
      var response = await requireApi().post('/api/plans/' + encodeURIComponent(state.plan.id)
        + '/strategy/custom', {
        expectedVersion: state.plan.version,
        position: draft.position
      });
      var custom = response && response.strategy && response.strategy.result
        && response.strategy.result.candidate;
      if (!custom || custom.selected !== true) {
        acceptPlan(response && response.plan || state.plan);
        state.strategy = response && response.strategy || state.strategy;
        state.selected = null;
        state.candidates = [];
        state.deskPickId = null;
        state.outcome = null;
        state.decision = null;
        invalidateDecisionPreview('custom-package-blocked');
        var blocked = response && response.preview && response.preview.blockReasons || [];
        var blockedError = new Error('StrikeBench blocked the exact package during its final reprice.'
          + (blocked.length ? ' ' + blocked.join('; ') : ' Reload the current strategy competition.'));
        state.draft = Object.assign({}, draft, { pending: false, applying: false, valid: false,
          preview: response, candidate: null, error: blockedError.message });
        notify('draft-rejected', { operation: 'draft-select', response: response,
          draft: state.draft, error: blockedError });
        throw blockedError;
      }
      acceptPlan(response.plan || state.plan);
      custom.positionIdentity = response.identity || custom.positionIdentity;
      state.strategy = response.strategy;
      state.selected = custom;
      state.candidates = [custom].concat(state.candidates.filter(function (candidate) {
        return String(candidate.id) !== String(custom.id);
      }));
      state.draft = null;
      state.decision = null;
      state.outcome = null;
      state.animation = null;
      invalidateDecisionPreview('custom-package-selected');
      presentation = candidateToDesk(custom, state.market);
      presentation.preview = response.preview;
      presentation.payoffPoints = (response.preview && response.preview.payoff || []).map(function (point) {
        return { price: Number(point.price), profit: Number(point.profitCents) / 100 };
      }).filter(function (point) {
        return Number.isFinite(point.price) && Number.isFinite(point.profit);
      });
      if (!state.ensemble || !state.ensemble.ensemble
          || !ensembleIdentity
          || state.ensemble.ensemble.id !== ensembleIdentity.id
          || state.ensemble.ensemble.fingerprint !== ensembleIdentity.fingerprint) {
        throw new Error('The exact package lost the active outcome ensemble identity.');
      }
      var outcome = await runOutcome(seq, {
        expectedCandidateId: custom.id,
        expectedEnsemble: ensembleIdentity,
        silent: true
      });
      notify('draft-selected', {
        operation: 'draft-select',
        plan: state.plan,
        selected: custom,
        candidate: presentation,
        candidates: state.candidates.map(function (candidate) { return candidateToDesk(candidate, state.market); }),
        outcome: outcome,
        response: response
      });
      await loadDecisionState(seq);
      await previewDecision({ type: 'MARKET', qty: custom.qty || 1 }, seq);
      state.mutationPending = false;
      notify('ready', { operation: 'draft-select' });
      return copyState();
    } catch (error) {
      state.mutationPending = false;
      if (state.draft) state.draft = Object.assign({}, state.draft, { pending: false, applying: false, error: error.message });
      if (presentation && state.selected) {
        notify('draft-selected', {
          operation: 'draft-select', plan: state.plan, selected: state.selected,
          candidate: presentation,
          candidates: state.candidates.map(function (candidate) { return candidateToDesk(candidate, state.market); }),
          outcome: state.outcome
        });
      }
      return fail(seq, 'draft-select', error);
    }
  }

  function strategyControls(context) {
    var governors = Object.assign({}, window.decide && window.decide.govs || {},
      context && (context.governors || context.govs) || {});
    var controls = { allow0dte: horizonDays(context) <= 1 };
    var maxLoss = number(governors.risk);
    if (maxLoss != null && maxLoss > 0 && Number.isFinite(maxLoss)) {
      controls.maxLossCents = Math.round(maxLoss * 100);
    }
    var filters = {};
    var minPop = number(governors.minPop), maxAssignment = number(governors.maxAsn);
    var maxCost = number(governors.maxCost);
    if (minPop != null && minPop > 0) filters.minPop = Math.max(0, Math.min(1, minPop / 100));
    if (maxAssignment != null && maxAssignment >= 0) {
      filters.maxAssignmentProb = Math.max(0, Math.min(1, maxAssignment / 100));
    }
    if (maxCost != null && maxCost > 0) filters.maxCostCents = Math.round(maxCost * 100);
    if (Object.keys(filters).length) controls.filters = filters;
    return controls;
  }

  function canonicalJson(value) {
    if (Array.isArray(value)) return value.map(canonicalJson);
    if (!value || typeof value !== 'object') return value;
    return Object.keys(value).sort().reduce(function (out, key) {
      if (value[key] !== undefined) out[key] = canonicalJson(value[key]);
      return out;
    }, {});
  }

  function chainSurfaceIdentity(chain) {
    chain = chain || {};
    function rows(values) {
      return (Array.isArray(values) ? values : []).map(function (row) {
        return JSON.stringify(canonicalJson(row));
      }).sort();
    }
    return JSON.stringify({
      underlying: chain.underlying || null,
      expiration: chain.expiration || null,
      underlyingPrice: chain.underlyingPrice == null ? null : chain.underlyingPrice,
      spot: chain.spot == null ? null : chain.spot,
      calls: rows(chain.calls),
      puts: rows(chain.puts)
    });
  }

  function strategyFingerprint(plan, controls, market) {
    market = market || {};
    var identity = market.identity || {}, quote = market.quote || {}, chain = market.chain || {};
    return stringHash(JSON.stringify({
      planId: plan.id,
      contextRev: plan.context && plan.context.rev,
      controls: controls,
      market: {
        world: identity.world,
        revision: identity.revision,
        epoch: identity.epoch,
        datasetId: identity.datasetId,
        marketLane: identity.marketLane,
        spot: market.spot,
        quoteSource: quote.source,
        quoteFreshness: quote.freshness,
        quoteAsOf: quote.asOf,
        expiration: market.expiration,
        chainSource: chain.source,
        chainFreshness: chain.freshness,
        chainAsOf: chain.asOfEpochMs == null ? chain.asOf : chain.asOfEpochMs,
        chainSurface: chainSurfaceIdentity(chain)
      }
    }));
  }

  function storedStrategyFingerprint(plan) {
    try {
      var raw = window.sessionStorage.getItem('strikebench.desk.strategy.v1.' + plan.id);
      if (!raw) return null;
      try {
        var parsed = JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
      } catch (ignored) {
        // A legacy tab-local fingerprint has no server receipt and is intentionally not reusable.
        return null;
      }
    }
    catch (ignored) { return null; }
  }

  function rememberStrategyFingerprint(plan, fingerprint, inputHash, runId) {
    try { window.sessionStorage.setItem('strikebench.desk.strategy.v1.' + plan.id,
      JSON.stringify({ declarationFingerprint: fingerprint, inputHash: inputHash || null,
        runId: runId || null })); }
    catch (ignored) { /* a disabled session store only costs a future recomputation */ }
  }

  async function classifyCandidates(candidates) {
    await Promise.all((candidates || []).map(async function (candidate) {
      if (candidate.positionIdentity || candidate.identity) return;
      try {
        candidate.positionIdentity = await requireApi().post('/api/strategies/identify', {
          symbol: candidate.symbol || state.plan && state.plan.symbol,
          qty: Math.max(1, Number(candidate.qty || 1)),
          legs: candidate.legs || []
        });
      } catch (ignored) {
        // Classification is additive metadata. The Desk must not invent a risk class if the
        // canonical StrategyCatalog is temporarily unavailable.
        candidate.positionIdentity = null;
      }
    }));
    return candidates;
  }

  function rejectionText(rejection) {
    if (!rejection) return null;
    if (rejection.reason) return String(rejection.reason);
    if (Array.isArray(rejection.blockReasons) && rejection.blockReasons.length) {
      return rejection.blockReasons.map(String).join('; ');
    }
    if (rejection.detail) return String(rejection.detail);
    return null;
  }

  function candidateToDesk(candidate, market) {
    var qty = Math.max(1, Number(candidate.qty || 1));
    var economics = candidate.evaluation && candidate.evaluation.assessment
      && candidate.evaluation.assessment.economics || {};
    var capital = candidate.evaluation && candidate.evaluation.capital || {};
    var optionLeg = (candidate.legs || []).find(function (leg) {
      return String(leg.type || '').toUpperCase() !== 'STOCK';
    });
    var entry = candidate.entryNetPremiumCents == null ? null : Number(candidate.entryNetPremiumCents) / 100;
    var identity = candidate.positionIdentity || candidate.identity || null;
    var explicitDefinedRisk = identity && typeof identity.definedRisk === 'boolean'
      ? identity.definedRisk : typeof candidate.definedRisk === 'boolean' ? candidate.definedRisk : null;
    var incremental = capital.incrementalCents == null ? null : Number(capital.incrementalCents);
    var economic = capital.economicCents == null ? null : Number(capital.economicCents);
    var maxLossCents = candidate.maxLossCents == null ? null : Number(candidate.maxLossCents);
    var combinedMaxLossCents = candidate.combinedMaxLossCents == null
      ? null : Number(candidate.combinedMaxLossCents);
    var displayCapital = incremental != null ? incremental : economic != null ? economic : maxLossCents;
    var afterCostEv = economics.marketEvAfterCostsCents == null
      ? candidate.expectedValueCents == null ? null : Number(candidate.expectedValueCents)
      : Number(economics.marketEvAfterCostsCents);
    return {
      id: candidate.id,
      short: candidate.label || candidate.displayName || candidate.strategy,
      name: candidate.displayName || candidate.strategy,
      sym: candidate.symbol || market.quote.symbol,
      spot: market.spot,
      em: null,
      exp: optionLeg && optionLeg.expiration || market.expiration,
      lean: null,
      risk: explicitDefinedRisk == null ? 'unknown' : explicitDefinedRisk ? 'defined' : 'undefined',
      undef: explicitDefinedRisk === false,
      positionIdentity: identity,
      legs: (candidate.legs || []).map(function (leg) { return legToDesk(leg, qty); }),
      net: entry,
      credit: entry,
      creditAmount: entry == null ? null : entry > 0 ? entry : 0,
      debitAmount: entry == null ? null : entry < 0 ? -entry : 0,
      entryEconomics: entry == null ? 'UNAVAILABLE' : entry > 0 ? 'CREDIT' : entry < 0 ? 'DEBIT' : 'EVEN',
      pop: candidate.pop == null ? null : Math.round(Number(candidate.pop) * 100),
      maxLoss: maxLossCents == null ? null : maxLossCents / 100,
      combinedMaxLoss: combinedMaxLossCents == null ? null : combinedMaxLossCents / 100,
      maxProfit: candidate.maxProfitCents == null ? null : Number(candidate.maxProfitCents) / 100,
      bestUpside: candidate.bestUpside || null,
      biggestRisk: candidate.biggestRisk || null,
      cap: displayCapital == null ? null : displayCapital / 100,
      capitalIncremental: incremental == null ? null : incremental / 100,
      capitalEconomic: economic == null ? null : economic / 100,
      capitalBasis: capital.basis || null,
      riskProfile: candidate.evaluation && candidate.evaluation.risk || null,
      usesHeldShares: candidate.usesHeldShares === true,
      sharesNeeded: candidate.sharesNeeded == null ? null : Number(candidate.sharesNeeded),
      edge: afterCostEv == null ? null : afterCostEv / 100,
      assign: candidate.assignmentProb == null ? null : Math.round(Number(candidate.assignmentProb) * 100),
      why: candidate.whyConsidered || candidate.beginnerExplanation || '',
      analog: candidate.sourceKind ? 'Backend recommendation · ' + candidate.sourceKind : 'Backend recommendation',
      ivnote: candidate.freshness ? String(candidate.freshness) + ' market inputs' : 'Market input receipt attached',
      breakevens: candidate.breakevens || [],
      evaluation: candidate.evaluation || null,
      authoritative: true,
      backend: candidate
    };
  }

  function mergeSelectedCandidate(candidates, selected) {
    var visible = candidates.slice();
    if (!selected) return visible;
    var selectedIndex = visible.findIndex(function (candidate) {
      return String(candidate.id) === String(selected.id);
    });
    if (selectedIndex >= 0) visible[selectedIndex] = selected;
    else visible.unshift(selected);
    return visible;
  }

  async function publishStrategy(strategy, selected, market, seq, detail) {
    var result = strategy && strategy.result;
    var ranked = result && Array.isArray(result.candidates) ? result.candidates : [];
    var rejected = result && Array.isArray(result.rejected) ? result.rejected : [];
    var visible = mergeSelectedCandidate(ranked, selected);
    if (!ranked.length && !selected) {
      var reasons = rejected.map(rejectionText).filter(Boolean).slice(0, 3);
      state.strategy = strategy;
      state.candidates = [];
      state.selected = null;
      state.rejections = rejected.slice();
      state.deskPickId = null;
      notify('strategy-rejected', { plan: state.plan, strategy: strategy, rejected: rejected });
      throw new Error('StrikeBench produced no eligible strategy for this declared idea.'
        + (reasons.length ? ' ' + reasons.join(' ') : ''));
    }
    await classifyCandidates(visible);
    if (seq !== state.requestSeq) return null;
    state.strategy = strategy;
    state.candidates = visible;
    state.selected = selected || null;
    state.rejections = rejected.slice();
    var deskPick = deskPickCandidate(ranked);
    state.deskPickId = deskPick && deskPick.id || null;
    notify('strategy', Object.assign({
      plan: state.plan,
      strategy: strategy,
      candidates: visible.map(function (candidate) { return candidateToDesk(candidate, market); }),
      deskPickId: state.deskPickId
    }, detail || {}));
    if (state.selected) notify('selection', {
      plan: state.plan, selected: state.selected, restored: true
    });
    return visible;
  }

  async function runStrategy(plan, market, context, seq) {
    var api = requireApi();
    var controls = strategyControls(context);
    var path = '/api/plans/' + encodeURIComponent(plan.id) + '/strategy';
    var out = await api.post(path + '/run', controls);
    if (seq !== state.requestSeq) return null;
    acceptPlan(out.plan || plan);
    var posted = out && out.strategy;
    if (!posted || String(posted.state || '').toUpperCase() !== 'CURRENT'
        || !posted.runId || !posted.inputHash) {
      throw new Error('The backend did not retain a current, fingerprinted strategy competition.');
    }
    // A competition refresh deliberately does not displace an independently selected custom or
    // Scout package. Read the canonical state back after the write so that exact selection is not
    // lost merely because the ranked field needed fresher market inputs.
    var latest = await api.getFresh(path + '/latest');
    if (seq !== state.requestSeq) return null;
    var strategy = latest && latest.strategy;
    if (!strategy || String(strategy.state || '').toUpperCase() !== 'CURRENT'
        || String(strategy.runId || '') !== String(posted.runId)
        || String(strategy.inputHash || '') !== String(posted.inputHash)) {
      throw new Error('Another strategy refresh superseded this Desk request. Reload the current idea.');
    }
    rememberStrategyFingerprint(state.plan, strategyFingerprint(state.plan, controls, market),
      strategy.inputHash, strategy.runId);
    return publishStrategy(strategy, latest && latest.selected, market, seq, {
      refreshed: true,
      selectionRestored: !!(latest && latest.selected)
    });
  }

  async function loadOrRunStrategy(plan, market, context, seq) {
    var controls = strategyControls(context);
    var fingerprint = strategyFingerprint(plan, controls, market);
    var latest = await optionalFresh('/api/plans/' + encodeURIComponent(plan.id) + '/strategy/latest');
    if (seq !== state.requestSeq) return null;
    var strategy = latest && latest.strategy;
    var result = strategy && strategy.result;
    var candidates = result && Array.isArray(result.candidates) ? result.candidates : [];
    var restored = latest && latest.selected;
    var receipt = storedStrategyFingerprint(plan);
    var reusable = strategy && String(strategy.state || 'CURRENT').toUpperCase() === 'CURRENT'
      && result && Array.isArray(result.candidates) && (candidates.length || restored) && !!(receipt
        && receipt.declarationFingerprint === fingerprint
        && receipt.inputHash
        && strategy.inputHash
        && String(receipt.inputHash) === String(strategy.inputHash)
        && receipt.runId
        && strategy.runId
        && String(receipt.runId) === String(strategy.runId));
    if (!reusable) return runStrategy(plan, market, context, seq);
    return publishStrategy(strategy, restored, market, seq, { restored: true });
  }

  function mechanicallyUsable(candidate) {
    var expirations = new Set((candidate.legs || []).filter(function (leg) {
      return String(leg.type || '').toUpperCase() !== 'STOCK';
    }).map(function (leg) { return leg.expiration; }));
    var mechanics = candidate.evaluation && candidate.evaluation.assessment
      && candidate.evaluation.assessment.mechanics;
    return expirations.size === 1 && (!mechanics || mechanics.eligible !== false)
      && (!candidate.evaluation || candidate.evaluation.viable !== false);
  }

  function candidateCoherence(candidate) {
    return String(candidate.evaluation && candidate.evaluation.assessment
      && candidate.evaluation.assessment.coherence
      && candidate.evaluation.assessment.coherence.verdict || 'UNAVAILABLE').toUpperCase();
  }

  function deskPickCandidate(candidates) {
    return candidates.find(function (candidate) {
      return mechanicallyUsable(candidate) && candidateCoherence(candidate) === 'COHERENT';
    }) || null;
  }

  function preferredCandidate(candidates) {
    return deskPickCandidate(candidates) || candidates.find(function (candidate) {
      var verdict = candidateCoherence(candidate);
      return mechanicallyUsable(candidate) && (verdict === 'MIXED' || verdict === 'UNAVAILABLE');
    }) || candidates.find(mechanicallyUsable) || candidates[0];
  }

  async function selectCandidate(candidateId, seq) {
    var api = requireApi(), plan = state.plan;
    if (!plan) throw new Error('A Plan is required before selecting a strategy.');
    var local = state.candidates.find(function (candidate) {
      return String(candidate.id) === String(candidateId);
    });
    if (!local) throw new Error('The requested package is not part of the active backend strategy set.');
    state.animationSeq++;
    state.animation = null;
    state.decision = null;
    invalidateDecisionPreview('candidate-changed');
    var out = await api.put('/api/plans/' + encodeURIComponent(plan.id) + '/strategy/select', {
      expectedVersion: plan.version,
      candidateId: candidateId
    });
    if (seq !== state.requestSeq) return null;
    var receipt = out && (out.selection || out.selected);
    var echoedId = receipt && (receipt.candidateId || receipt.id);
    if (!receipt || String(echoedId || '') !== String(candidateId)) {
      throw new Error('The backend did not retain the requested strategy selection identity.');
    }
    if (out.plan && receipt.planVersion != null
        && Number(receipt.planVersion) !== Number(out.plan.version)) {
      throw new Error('The strategy selection receipt and Plan version do not agree.');
    }
    acceptPlan(out.plan || plan);
    // PUT /strategy/select returns a mutation receipt, not the candidate. Keep the exact full
    // candidate loaded from the canonical strategy state for outcome, preview, and rendering.
    state.selected = Object.assign({}, local, { selected: true });
    notify('selection', { plan: state.plan, selected: state.selected, receipt: receipt, response: out });
    return out;
  }

  async function runEnsembleAndOutcome(seq) {
    var api = requireApi(), plan = state.plan;
    var ensemble = await api.post('/api/plans/' + encodeURIComponent(plan.id) + '/outcomes/ensemble', {
      expectedVersion: plan.version
    });
    if (seq !== state.requestSeq) return null;
    var capturedChain = state.market && state.market.chain;
    var stableChain = await api.getFresh('/api/research/' + encodeURIComponent(plan.symbol)
      + '/chain?expiration=' + encodeURIComponent(state.market && state.market.expiration || ''));
    if (seq !== state.requestSeq) return null;
    if (!ensemble || !ensemble.ensemble || !ensemble.preview
        || !ensembleMatchesCurrentMarket(ensemble, false)
        || !sameChainReceipt(capturedChain, stableChain)) {
      throw new Error('Market inputs changed while the outcome fan was being built. Reload this idea on the current quote and option surface.');
    }
    acceptPlan(ensemble.plan || plan);
    state.ensemble = ensemble;
    notify('ensemble', { plan: state.plan, ensemble: ensemble });
    var outcome = await runOutcome(seq);
    if (!outcome || seq !== state.requestSeq) return null;
    return { ensemble: ensemble, outcome: outcome };
  }

  async function runOutcome(seq, options) {
    options = options || {};
    var api = requireApi(), ensemble = state.ensemble;
    var outcome = await api.post('/api/plans/' + encodeURIComponent(state.plan.id) + '/outcomes/run', {
      expectedVersion: state.plan.version,
      basis: 'PARAMETRIC',
      ensembleId: ensemble.ensemble.id
    });
    if (seq !== state.requestSeq) return null;
    var expectedCandidateId = options.expectedCandidateId || state.selected && state.selected.id;
    var expectedEnsemble = options.expectedEnsemble || ensemble && ensemble.ensemble;
    var saved = outcome && outcome.outcome || {}, returnedEnsemble = outcome && outcome.ensemble || {};
    if (!expectedCandidateId || String(saved.candidateId || '') !== String(expectedCandidateId)
        || !expectedEnsemble || String(saved.ensembleId || '') !== String(expectedEnsemble.id)
        || String(returnedEnsemble.id || '') !== String(expectedEnsemble.id)
        || String(returnedEnsemble.fingerprint || '') !== String(expectedEnsemble.fingerprint)
        || saved.ensembleFingerprint && String(saved.ensembleFingerprint) !== String(expectedEnsemble.fingerprint)) {
      throw new Error('The outcome response did not retain the selected package and stored ensemble identity.');
    }
    acceptPlan(outcome.plan || state.plan);
    state.outcome = outcome;
    if (!options.silent) notify('outcome', { plan: state.plan, ensemble: ensemble, outcome: outcome });
    return outcome;
  }

  function currentStoredOutcome(latest, ensembleId, candidateId) {
    var rows = latest && Array.isArray(latest.outcomes) ? latest.outcomes : [];
    return rows.find(function (row) {
      return String(row.basis || '').toUpperCase() === 'PARAMETRIC'
        && String(row.ensembleId || '') === String(ensembleId || '')
        && String(row.candidateId || '') === String(candidateId || '');
    }) || null;
  }

  function ensembleHasDisplayResolution(envelope) {
    var preview = envelope && envelope.preview || {};
    var horizon = Number(preview.horizonDays || 0), samples = preview.samples || [];
    if (horizon > 2 || !samples.length) return true;
    // A one-session fan with only open/end points can only render straight rays. Rebuild it
    // through the canonical engine so the stored artifact owns the intraday stochastic journey.
    return samples.every(function (path) { return Array.isArray(path) && path.length >= 5; });
  }

  function normalizedInstant(value) {
    if (typeof value === 'number' && Number.isFinite(value)) return Math.round(value);
    if (typeof value === 'string' && /^\d+$/.test(value.trim())) return Math.round(Number(value));
    var parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  function sameChainReceipt(captured, current) {
    captured = captured || {};
    current = current || {};
    var capturedSource = String(captured.source || captured.evidence && captured.evidence.source || '')
      .trim().toLowerCase();
    var currentSource = String(current.source || current.evidence && current.evidence.source || '')
      .trim().toLowerCase();
    var capturedFreshness = String(captured.freshness || captured.evidence && captured.evidence.age || '')
      .trim().toUpperCase();
    var currentFreshness = String(current.freshness || current.evidence && current.evidence.age || '')
      .trim().toUpperCase();
    var capturedAsOf = normalizedInstant(captured.asOfEpochMs == null ? captured.asOf : captured.asOfEpochMs);
    var currentAsOf = normalizedInstant(current.asOfEpochMs == null ? current.asOf : current.asOfEpochMs);
    if (!capturedSource || !currentSource || !capturedFreshness || !currentFreshness
        || capturedAsOf == null || currentAsOf == null) return false;
    return String(captured.underlying || '').toUpperCase() === String(current.underlying || '').toUpperCase()
      && String(captured.expiration || '') === String(current.expiration || '')
      && capturedSource === currentSource
      && capturedFreshness === currentFreshness
      && capturedAsOf === currentAsOf
      && chainSurfaceIdentity(captured) === chainSurfaceIdentity(current);
  }

  function ensembleMatchesCurrentMarket(envelope, requireCalibrationReceipt) {
    var preview = envelope && envelope.preview || {};
    var receipt = preview.receipt || {};
    var market = state.market || {}, identity = market.identity || {};
    var quote = market.quote || {}, quoteReceipt = market.provenance && market.provenance.quote || {};
    var anchorSpot = number(receipt.anchorSpot), currentSpot = number(market.spot);
    var anchorAsOf = normalizedInstant(receipt.asOf), quoteAsOf = normalizedInstant(quote.asOf);
    var anchorSource = String(receipt.anchorSource || '').trim().toLowerCase();
    var quoteSource = String(quoteReceipt.source || quote.source || '').trim().toLowerCase();
    var anchorFreshness = String(receipt.anchorFreshness || '').trim().toUpperCase();
    var quoteFreshness = String(quoteReceipt.freshness || quote.freshness || '').trim().toUpperCase();
    var storedVol = number(receipt.spec && receipt.spec.volAnnual);
    var currentMarketIv = number(preview.marketImplied && preview.marketImplied.atmIv);
    if (!state.plan || !receipt.symbol || !receipt.worldId
        || anchorSpot == null || currentSpot == null
        || !anchorSource || !quoteSource || !anchorFreshness || !quoteFreshness
        || anchorAsOf == null || quoteAsOf == null) return false;
    if (requireCalibrationReceipt && (storedVol == null || currentMarketIv == null)) return false;
    if (String(receipt.symbol).toUpperCase() !== String(state.plan.symbol).toUpperCase()
        || String(receipt.worldId) !== String(identity.world || '')
        || ((receipt.datasetId != null || identity.datasetId != null)
          && String(receipt.datasetId || '') !== String(identity.datasetId || ''))
        || Math.round(anchorSpot * 100) !== Math.round(currentSpot * 100)
        || anchorSource !== quoteSource
        || anchorFreshness !== quoteFreshness
        || anchorAsOf !== quoteAsOf
        || (storedVol != null && currentMarketIv != null
          && Math.abs(storedVol - currentMarketIv) > 0.000001)) return false;
    return true;
  }

  async function loadOrRunEnsembleAndOutcome(seq) {
    var planId = encodeURIComponent(state.plan.id);
    var ensemble;
    try {
      ensemble = await optionalFresh('/api/plans/' + planId + '/outcomes/ensemble/latest');
    } catch (error) {
      throw error;
    }
    if (seq !== state.requestSeq) return null;
    if (!ensemble || !ensemble.ensemble || !ensemble.preview || !ensembleHasDisplayResolution(ensemble)
        || !ensembleMatchesCurrentMarket(ensemble, true)) {
      return runEnsembleAndOutcome(seq);
    }
    if (!ensemble.plan || ensemble.plan.id !== state.plan.id) {
      throw new Error('The stored ensemble is not owned by the active Desk Plan.');
    }
    acceptPlan(ensemble.plan);
    state.ensemble = ensemble;
    notify('ensemble', { plan: state.plan, ensemble: ensemble, restored: true });

    var latest = await optionalFresh('/api/plans/' + planId + '/outcomes/latest');
    if (seq !== state.requestSeq) return null;
    var stored = currentStoredOutcome(latest, ensemble.ensemble.id, state.selected && state.selected.id);
    if (!stored) {
      var fresh = await runOutcome(seq);
      return fresh ? { ensemble: ensemble, outcome: fresh } : null;
    }
    state.outcome = { plan: state.plan, outcome: stored, ensemble: ensemble.ensemble, restored: true };
    notify('outcome', { plan: state.plan, ensemble: ensemble, outcome: state.outcome, restored: true });
    return { ensemble: ensemble, outcome: state.outcome };
  }

  async function previewDecision(order, seq) {
    var api = requireApi(), plan = state.plan;
    if (!plan) throw new Error('A Plan is required before previewing an order.');
    var body = decisionBody(order);
    var requestKey = decisionRequestKey(body);
    invalidateDecisionPreview('instruction-changed');
    var preview = await api.post('/api/plans/' + encodeURIComponent(plan.id) + '/decision/preview', body);
    if (seq !== state.requestSeq) return null;
    if (!preview || !preview.plan || preview.plan.id !== plan.id) {
      throw new Error('The order preview is not owned by the active Desk Plan.');
    }
    if (!preview.selected || !state.selected
        || String(preview.selected.id) !== String(state.selected.id)) {
      throw new Error('The order preview is not bound to the selected backend strategy.');
    }
    assertOrderEcho(preview.order, body);
    acceptPlan(preview.plan);
    preview.deskRequestKey = requestKey;
    state.decisionPreview = preview;
    state.decisionPreviewKey = requestKey;
    notify('decision-preview', { plan: state.plan, preview: preview });
    return preview;
  }

  function invalidateDecisionPreview(reason) {
    var hadPreview = !!state.decisionPreview || !!state.decisionPreviewKey;
    state.decisionPreview = null;
    state.decisionPreviewKey = null;
    if (hadPreview) notify('decision-preview-invalidated', { operation: reason || 'order-change' });
  }

  function decisionRequestKey(body) {
    return JSON.stringify({
      planId: state.plan && state.plan.id,
      planVersion: body && body.expectedVersion,
      candidateId: state.selected && state.selected.id,
      qty: body && body.qty,
      orderInstruction: body && body.orderInstruction
    });
  }

  function assertOrderEcho(order, body) {
    if (!order) throw new Error('The backend order preview omitted its execution receipt.');
    var expected = body.orderInstruction || {}, actual = order.orderInstruction || {};
    if (Number(order.qty) !== Number(body.qty)
        || String(actual.type || '').toUpperCase() !== String(expected.type || '').toUpperCase()
        || String(actual.timeInForce || '').toUpperCase() !== String(expected.timeInForce || '').toUpperCase()
        || (expected.type === 'LIMIT' && Number(actual.limitNetCents) !== Number(expected.limitNetCents))) {
      throw new Error('The backend order preview does not match the current quantity and execution instruction.');
    }
  }

  async function loadDecisionState(seq) {
    var latest = await optionalFresh('/api/plans/' + encodeURIComponent(state.plan.id) + '/decision/latest');
    if (seq !== state.requestSeq) return null;
    if (!latest) return null;
    if (!latest.plan || latest.plan.id !== state.plan.id) {
      throw new Error('The stored decision state is not owned by the active Desk Plan.');
    }
    acceptPlan(latest.plan);
    if (String(latest.selectionState || '').toUpperCase() === 'CURRENT') {
      if (!latest.selected || !state.selected
          || String(latest.selected.id) !== String(state.selected.id)) {
        throw new Error('The stored decision belongs to another strategy selection.');
      }
    }
    state.decision = latest;
    notify('decision-state', { plan: state.plan, decision: latest });
    return latest;
  }

  function decisionBody(order) {
    order = order || {};
    var plan = state.plan;
    var instruction = String(order.instruction || order.type || 'MARKET').toUpperCase();
    var timeInForce = String(order.timeInForce || 'DAY').toUpperCase();
    var qty = Number(order.qty || 1);
    if (instruction !== 'MARKET' && instruction !== 'LIMIT') throw new Error('Execution instruction must be MARKET or LIMIT.');
    if (timeInForce !== 'DAY') throw new Error('The current execution workflow supports DAY instructions.');
    if (!Number.isInteger(qty) || qty < 1 || qty > 100) throw new Error('Order quantity must be a whole number from 1 to 100.');
    var body = {
      expectedVersion: plan.version,
      qty: qty,
      orderInstruction: {
        type: instruction,
        timeInForce: timeInForce
      }
    };
    if (instruction === 'LIMIT') {
      var limit = number(order.limitNetCents);
      if (limit == null || !Number.isInteger(limit)) throw new Error('A limit order needs a signed whole-cent package price.');
      body.orderInstruction.limitNetCents = limit;
    }
    return body;
  }

  async function openIdea(context) {
    if (!state.enabled) return null;
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var seq = ++state.requestSeq;
    state.animationSeq++;
    context = Object.assign({}, context || {});
    context.governors = Object.assign({}, window.decide && window.decide.govs || {},
      context.governors || context.govs || {});
    state.context = context;
    state.error = null;
    state.plan = null;
    state.planIdentity = null;
    state.strategy = null;
    state.candidates = [];
    state.deskPickId = null;
    state.selected = null;
    state.ensemble = null;
    state.outcome = null;
    state.decision = null;
    state.draft = null;
    draftPreviewSeq++;
    state.rejections = [];
    state.animation = null;
    invalidateDecisionPreview('idea-changed');
    notify('loading', { operation: 'idea' });
    try {
      var symbol = String(context && context.symbol || '').trim().toUpperCase();
      if (!symbol) throw new Error('Choose an underlying before loading a Desk idea.');
      var market = await loadMarket(symbol, horizonDays(context), seq);
      if (!market || seq !== state.requestSeq) return null;
      var plan = await ensurePlan(context, market, seq);
      if (!plan || seq !== state.requestSeq) return null;
      var candidates = await loadOrRunStrategy(plan, market, context, seq);
      if (!candidates || seq !== state.requestSeq) return null;
      var candidate = state.selected || preferredCandidate(candidates);
      if (!state.selected) {
        var selectionMutation = beginMutation();
        try {
          await selectCandidate(candidate.id, seq);
        } finally {
          endMutation(selectionMutation);
        }
        if (seq !== state.requestSeq) return null;
      }
      var result = await loadOrRunEnsembleAndOutcome(seq);
      if (!result || seq !== state.requestSeq) return null;
      await loadDecisionState(seq);
      if (seq !== state.requestSeq) return null;
      await previewDecision({ type: 'MARKET', qty: candidate.qty || 1 }, seq);
      if (seq !== state.requestSeq) return null;
      notify('ready', { operation: 'idea' });
      return copyState();
    } catch (error) {
      return fail(seq, 'idea', error);
    }
  }

  async function chooseCandidate(candidateId) {
    if (!state.enabled) return null;
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var seq = ++state.requestSeq;
    var mutationOwner = beginMutation();
    state.draft = null;
    draftPreviewSeq++;
    state.error = null;
    notify('loading', { operation: 'candidate' });
    try {
      await selectCandidate(candidateId, seq);
      if (seq !== state.requestSeq) return null;
      // Candidate/package changes are child valuations over the same stored market ensemble.
      // Do not regenerate the fan; rerun the exact outcome against its immutable id.
      var outcome = await runOutcome(seq, {
        expectedCandidateId: candidateId,
        expectedEnsemble: state.ensemble && state.ensemble.ensemble
      });
      if (!outcome || seq !== state.requestSeq) return null;
      await loadDecisionState(seq);
      if (seq !== state.requestSeq) return null;
      await previewDecision({ type: 'MARKET', qty: state.selected && state.selected.qty || 1 }, seq);
      endMutation(mutationOwner);
      notify('ready', { operation: 'candidate' });
      return copyState();
    } catch (error) {
      endMutation(mutationOwner);
      return fail(seq, 'candidate', error);
    } finally {
      endMutation(mutationOwner);
    }
  }

  async function repreviewOrder(order) {
    if (!state.enabled) return null;
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var seq = ++state.requestSeq;
    state.error = null;
    invalidateDecisionPreview('instruction-changed');
    notify('loading', { operation: 'order-preview' });
    try {
      var preview = await previewDecision(order, seq);
      if (seq !== state.requestSeq) return null;
      notify('ready', { operation: 'order-preview' });
      return preview;
    } catch (error) {
      return fail(seq, 'order-preview', error);
    }
  }

  async function commitOrder(order, acknowledgedRisks) {
    if (!state.enabled) return null;
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    if (!state.plan) throw new Error('A Plan is required before committing an order.');
    if (!state.decisionPreview || !state.decisionPreviewKey) {
      throw new Error('Preview the current execution instruction before committing it.');
    }
    var seq = ++state.requestSeq;
    state.mutationPending = true;
    state.error = null;
    notify('loading', { operation: 'order-commit' });
    try {
      var body = decisionBody(order);
      var requestKey = decisionRequestKey(body);
      if (requestKey !== state.decisionPreviewKey
          || state.decisionPreview.deskRequestKey !== requestKey) {
        invalidateDecisionPreview('instruction-changed');
        throw new Error('The order instruction changed after preview. Preview this exact order again before committing.');
      }
      assertOrderEcho(state.decisionPreview.order, body);
      body.ackToken = state.decisionPreview.ackToken;
      body.acknowledgedRisks = Array.isArray(acknowledgedRisks) ? acknowledgedRisks.slice() : [];
      var response = await requireApi().post('/api/plans/' + encodeURIComponent(state.plan.id)
        + '/decision/trade', body);
      if (seq !== state.requestSeq) return null;
      acceptPlan(response.plan || state.plan);
      state.decisionPreview = null;
      state.decisionPreviewKey = null;
      state.mutationPending = false;
      notify('committed', { operation: 'order-commit', response: response });
      return response;
    } catch (error) {
      state.mutationPending = false;
      return fail(seq, 'order-commit', error);
    }
  }

  function transientCanvas(scenario) {
    var ensemble = state.ensemble;
    var shift = Number(scenario && scenario.ivShiftPoints || 0);
    if (!ensemble || !shift) return null;
    var preview = ensemble.preview || {};
    var base = number(preview.canvas && preview.canvas.underlying
      && preview.canvas.underlying[0] && preview.canvas.underlying[0].atmIv);
    if (!(base > 0)) return null;
    var horizon = Math.max(1, Number(preview.horizonDays || 1));
    var day = Math.max(1, Math.min(horizon, Math.round(Number(scenario.days || horizon))));
    var canvas = Object.assign({}, preview.canvasModel || {});
    canvas.ivNodes = [
      { dayIndex: 0, atmIv: base },
      { dayIndex: day, atmIv: Math.max(0.01, Math.min(4, base + shift / 100)) }
    ];
    return canvas;
  }

  /**
   * Select and value paths from the already-stored fan. This call is pure: it does not save a
   * scenario or create a new ensemble, and its response is the only financial source used by
   * the animation frames.
   */
  async function scenarioAnimation(scenario) {
    if (!state.enabled || !state.plan || !state.ensemble) return null;
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var token = ++state.animationSeq;
    var requestIdentity = {
      planId: state.plan.id,
      planVersion: state.plan.version,
      contextRev: state.plan.context && state.plan.context.rev,
      candidateId: state.selected && state.selected.id,
      ensembleId: state.ensemble.ensemble.id,
      ensembleFingerprint: state.ensemble.ensemble.fingerprint,
      worldId: state.ensemble.preview && state.ensemble.preview.receipt && state.ensemble.preview.receipt.worldId,
      datasetId: state.ensemble.preview && state.ensemble.preview.receipt && state.ensemble.preview.receipt.datasetId
    };
    var preview = state.ensemble.preview || {};
    var horizon = Math.max(1, Number(preview.horizonDays || 1));
    var day = Math.max(1, Math.min(horizon, Math.round(Number(scenario && scenario.days || horizon))));
    var move = Math.max(-0.95, Number(scenario && scenario.movePct || 0) / 100);
    var supplied = scenario && Array.isArray(scenario.waypoints) ? scenario.waypoints : null;
    var waypoints = supplied && supplied.length ? supplied.map(function (pin) {
      return {
        dayIndex: Math.max(1, Math.min(horizon, Math.round(Number(pin.dayIndex)))),
        priceRatio: Math.max(0.01, Number(pin.priceRatio)),
        tolerance: pin.tolerance == null ? 0.03 : Math.max(0.001, Number(pin.tolerance))
      };
    }) : [{ dayIndex: day, priceRatio: Math.max(0.01, 1 + move), tolerance: 0.02 }];
    var byDay = {};
    waypoints.forEach(function (pin) {
      if (Number.isFinite(pin.dayIndex) && Number.isFinite(pin.priceRatio) && Number.isFinite(pin.tolerance)) byDay[pin.dayIndex] = pin;
    });
    waypoints = Object.keys(byDay).map(function (key) { return byDay[key]; })
      .sort(function (a, b) { return a.dayIndex - b.dayIndex; });
    if (!waypoints.length) throw new Error('A scenario needs at least one valid stored-fan waypoint.');
    var body = { ensembleId: state.ensemble.ensemble.id, waypoints: waypoints, limit: 9 };
    var canvas = transientCanvas(scenario);
    if (canvas) body.canvas = canvas;
    notify('loading', { operation: 'scenario-animation' });
    try {
      var response = window.PlanStore && typeof window.PlanStore.scenarioAnimation === 'function'
        ? await window.PlanStore.scenarioAnimation(state.plan, body)
        : await requireApi().post('/api/plans/' + encodeURIComponent(state.plan.id)
          + '/outcomes/ensemble/paths', body);
      if (token !== state.animationSeq) return null;
      var receipt = response && response.receipt || {}, selection = response && response.paths && response.paths.receipt || {};
      var checkpoints = response && response.checkpoints || {}, modelReceipt = checkpoints.modelReceipt || {};
      if (!response || !response.plan || response.plan.id !== requestIdentity.planId
          || response.ensemble.id !== requestIdentity.ensembleId
          || response.ensemble.fingerprint !== requestIdentity.ensembleFingerprint
          || receipt.ensembleId !== requestIdentity.ensembleId
          || receipt.ensembleFingerprint !== requestIdentity.ensembleFingerprint
          || receipt.selectedCandidateId !== requestIdentity.candidateId
          || requestIdentity.contextRev != null && Number(receipt.contextRev) !== Number(requestIdentity.contextRev)
          || requestIdentity.worldId && receipt.worldId !== requestIdentity.worldId
          || requestIdentity.datasetId && receipt.datasetId !== requestIdentity.datasetId
          || Number(checkpoints.focusSourcePathIndex) !== Number(selection.focusSourcePathIndex)
          || !receipt.valuationFingerprint
          || modelReceipt.valuationFingerprint !== receipt.valuationFingerprint) {
        throw new Error('The scenario response did not retain the active Plan, candidate, ensemble, and valuation identity.');
      }
      if (!state.selected || state.selected.id !== requestIdentity.candidateId
          || !state.ensemble || state.ensemble.ensemble.id !== requestIdentity.ensembleId) return null;
      state.animation = response;
      notify('animation', { animation: response, scenario: Object.assign({ day: day }, scenario || {}) });
      return response;
    } catch (error) {
      if (token !== state.animationSeq) return null;
      state.error = error;
      notify('error', { operation: 'scenario-animation', error: error });
      throw error;
    }
  }

  var governorTimer = null;
  document.addEventListener('change', function (event) {
    var control = event.target && event.target.closest && event.target.closest('[data-gov]');
    if (!control || !state.enabled || !state.context || !window.decide) return;
    var key = control.getAttribute('data-gov');
    if (key !== 'risk' && key !== 'minPop' && key !== 'maxAsn') return;
    if (governorTimer) window.clearTimeout(governorTimer);
    governorTimer = window.setTimeout(function () {
      governorTimer = null;
      if (!window.decide || !state.context) return;
      var next = Object.assign({}, state.context, {
        symbol: window.decide.sym,
        goal: window.decide.goal,
        view: window.decide.view,
        horizon: window.decide.horizon,
        governors: Object.assign({}, window.decide.govs || {})
      });
      openIdea(next).catch(function () { /* openIdea publishes its typed failure */ });
    }, 180);
  });

  window.DeskBackend = {
    enabled: function () { return state.enabled; },
    state: copyState,
    openIdea: openIdea,
    chooseCandidate: chooseCandidate,
    draftCatalog: draftCatalog,
    previewDraft: previewDraft,
    cancelDraft: cancelDraft,
    useDraft: useDraft,
    repreviewOrder: repreviewOrder,
    commitOrder: commitOrder,
    scenarioAnimation: scenarioAnimation,
    cancel: function () {
      if (!state.mutationPending) state.requestSeq++;
      state.animationSeq++;
      draftPreviewSeq++;
      if (!state.mutationPending) state.draft = null;
      if (governorTimer) window.clearTimeout(governorTimer);
      governorTimer = null;
      invalidateDecisionPreview('cancelled');
    }
  };
})();
