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
    strategyCatalog: null,
    strategyCatalogError: null,
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
    strategyNotes: [],
    book: null,
    position: null,
    positionScenario: null,
    presentationError: null,
    error: null
  };
  // Book and Position are independent read surfaces. Their requests must never supersede a
  // New Idea calculation (or one another), so neither lifecycle borrows state.requestSeq.
  var bookRequestSeq = 0;
  // Home market context has a second, finer-grained owner. A user can retarget its symbol or
  // sector while the initial ambient hydration (or an earlier focus) is still in flight, without
  // starting a new Book read. Only the newest context generation may publish into that Book.
  var bookContextRequestSeq = 0;
  var bookContextLoads = {};
  // The additive Book lifecycle receipt and focused Position state consume the same canonical
  // trade-detail document. Share it within one Book generation so they never create duplicate
  // reads or competing presentation owners for a held package.
  var bookPositionDetailLoads = {};
  var recentCommittedTradeId = null;
  var positionRequestSeq = 0;
  var positionScenarioRequestSeq = 0;
  var mutationOwnerSequence = 0;
  var activeMutationOwner = null;
  var activeMutationKind = null;
  var activeMutationCancelled = false;
  var pendingIdeaContext = null;

  function beginMutation(kind) {
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var owner = ++mutationOwnerSequence;
    activeMutationOwner = owner;
    activeMutationKind = kind || 'plan';
    activeMutationCancelled = false;
    state.mutationPending = true;
    return owner;
  }

  function endMutation(owner) {
    if (activeMutationOwner !== owner) return;
    activeMutationOwner = null;
    activeMutationKind = null;
    activeMutationCancelled = false;
    state.mutationPending = false;
    if (pendingIdeaContext) {
      var queuedIdea = pendingIdeaContext;
      pendingIdeaContext = null;
      clearPendingGovernorRefresh();
      window.setTimeout(function () {
        openIdea(queuedIdea).catch(function () { /* openIdea publishes its typed failure */ });
      }, 0);
      return;
    }
    if (pendingGovernorContext && !governorTimer) {
      governorTimer = window.setTimeout(flushGovernorRefresh, 0);
    }
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
    var observerErrors = [];
    // Presentation is a consumer of the financial workflow, never its transaction boundary. A
    // bad chart/layout render must not prevent the already-stored ensemble from reaching the
    // canonical outcome and decision services (or mask the original API failure in fail()).
    try {
      if (owner && typeof owner.backendChanged === 'function') owner.backendChanged(payload);
    } catch (error) {
      observerErrors.push(error);
    }
    try {
      document.dispatchEvent(new CustomEvent('strikebench:desk-backend', { detail: payload }));
    } catch (error) {
      observerErrors.push(error);
    }
    if (observerErrors.length) {
      var first = observerErrors[0];
      state.presentationError = {
        phase: phase,
        message: first && first.message ? String(first.message) : 'The Desk presentation observer failed.',
        stack: first && first.stack ? String(first.stack) : null
      };
      if (window.console && typeof window.console.error === 'function') {
        window.console.error('StrikeBench Desk presentation observer failed during ' + phase + '.', first);
      }
    }
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

  function researchMark(research) {
    var value = number(research && research.displayPrice);
    if (value != null && value > 0) {
      return {
        value: value,
        basis: research.priceIsPreviousClose === true
          ? 'PREVIOUS_CLOSE' : String(research.markBasis || 'DISPLAY_PRICE').toUpperCase()
      };
    }
    // Research owns the public display mark and its fallback basis. Rebuilding a midpoint here
    // would silently create a second price authority when the canonical mark is unavailable.
    return { value: null, basis: 'UNAVAILABLE' };
  }

  function horizonDays(context) {
    var raw = context && context.horizon;
    if (context && Object.prototype.hasOwnProperty.call(context, 'horizon')
        && (raw == null || String(raw).trim() === '')) return null;
    var parsed = raw == null ? null : Number(String(raw).match(/\d+/) && String(raw).match(/\d+/)[0]);
    return parsed && parsed > 0 ? Math.min(756, parsed) : 45;
  }

  function intentOf(goal) {
    var key = String(goal || '').trim().toUpperCase();
    if (key === 'INCOME' || key === 'HEDGE' || key === 'DIRECTIONAL'
        || key === 'ACQUIRE' || key === 'EXIT') return key;
    // Absence is a declaration fact. A draft Plan with no goal must never silently become
    // Income merely because this adapter needs a string for presentation.
    return null;
  }

  function thesisOf(view) {
    if (view == null) return null;
    var key = String(view || '').trim().toLowerCase();
    return key || null;
  }

  function riskModeOf(context) {
    var explicit = String(context && context.riskMode || '').trim().toLowerCase();
    if (explicit === 'conservative' || explicit === 'balanced' || explicit === 'aggressive') return explicit;
    if (context && Object.prototype.hasOwnProperty.call(context, 'riskMode')) return null;
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

  function errorReceipt(error, path) {
    return {
      path: path || error && error.path || null,
      message: error && error.message ? String(error.message) : 'The backend read failed.',
      status: error && error.status == null ? null : Number(error.status),
      code: error && error.code == null ? null : String(error.code)
    };
  }

  function readIdentitySnapshot() {
    var api = requireApi();
    return Promise.all([
      api.getFresh('/api/config'),
      api.getFresh('/api/world'),
      api.getFresh('/api/account')
    ]).then(function (docs) {
      var config = docs[0] || {}, world = docs[1] || {}, accountEnvelope = docs[2] || {};
      var identity = marketIdentity(config, world, null, accountEnvelope);
      if (!identity.world || !identity.marketLane) {
        throw new Error('The active market identity is unavailable for this Desk read.');
      }
      if (config.world && world.world && String(config.world) !== String(world.world)) {
        throw new Error('Configuration and the active market world do not agree. Reload after the market transition completes.');
      }
      if (!identity.accountId) {
        throw new Error('The active account identity is unavailable for this Desk read.');
      }
      return {
        identity: identity,
        config: config,
        world: world,
        account: accountEnvelope.account,
        accountEnvelope: accountEnvelope
      };
    });
  }

  function assertSameReadIdentity(before, after) {
    assertSameMarket(before.identity, after.identity);
    if (String(before.identity.accountId) !== String(after.identity.accountId)) {
      throw new Error('The active account changed while the Desk was loading. Reload this view in the current account.');
    }
  }

  function readSlot(key, path) {
    return requireApi().getFresh(path).then(function (value) {
      return { key: key, path: path, available: true, value: value, error: null };
    }).catch(function (error) {
      return { key: key, path: path, available: false, value: null, error: errorReceipt(error, path) };
    });
  }

  function readCachedSlot(key, path) {
    return requireApi().get(path).then(function (value) {
      return { key: key, path: path, available: true, value: value, error: null };
    }).catch(function (error) {
      return { key: key, path: path, available: false, value: null, error: errorReceipt(error, path) };
    });
  }

  function readBookPositionDetailSlot(tradeId, forceFresh) {
    var id = String(tradeId == null ? '' : tradeId).trim();
    var path = '/api/trades/' + encodeURIComponent(id);
    if (!id) return Promise.resolve(unavailableSlot('tradeDetail', path,
      'Choose an authoritative trade before reading its position detail.'));
    if (!tradeHintFromBook(id)) return readSlot('tradeDetail', path);
    if (forceFresh) delete bookPositionDetailLoads[id];
    if (bookPositionDetailLoads[id]) return bookPositionDetailLoads[id];
    // Keep both success and failure shared. Only an explicit retry passes forceFresh; otherwise
    // a lifecycle miss followed by a row click would issue the same failed read twice.
    var load = readSlot('tradeDetail', path);
    bookPositionDetailLoads[id] = load;
    return load;
  }

  function unavailableSlot(key, path, message) {
    return {
      key: key,
      path: path,
      available: false,
      value: null,
      error: errorReceipt(new Error(message), path)
    };
  }

  function objectSlot(slot, label) {
    if (!slot || !slot.available) return slot;
    if (slot.value && typeof slot.value === 'object' && !Array.isArray(slot.value)) return slot;
    return unavailableSlot(slot.key, slot.path, label + ' did not return its typed object.');
  }

  function optionalValidatedSlot(slot, label, validator) {
    slot = objectSlot(slot, label);
    if (!slot || !slot.available) return slot;
    try {
      validator(slot.value);
      return slot;
    } catch (error) {
      return unavailableSlot(slot.key, slot.path, error && error.message
        ? error.message : label + ' could not be validated.');
    }
  }

  function missingEvidence(evidence) {
    var provenance = String(evidence && evidence.provenance || '').toUpperCase();
    var age = String(evidence && (evidence.age || evidence.freshness) || '').toUpperCase();
    return provenance === 'MISSING' || age === 'MISSING';
  }

  function assertDocumentSymbol(documentValue, symbol, label) {
    if (!documentValue || !documentValue.symbol) return;
    if (String(documentValue.symbol).toUpperCase() !== String(symbol).toUpperCase()) {
      throw new Error(label + ' belongs to ' + documentValue.symbol + ', not ' + symbol + '.');
    }
  }

  function quoteFromResearch(research) {
    research = research || {};
    var quote = Object.assign({}, research.quote || {});
    if (quote.asOf == null && quote.asOfEpochMs != null) quote.asOf = quote.asOfEpochMs;
    if (!quote.freshness && research.freshness) quote.freshness = research.freshness;
    if (!quote.evidence && research.evidence && research.evidence.inputs) {
      quote.evidence = research.evidence.inputs.quote || null;
    }
    return quote;
  }

  async function loadMarket(symbol, targetDays, seq) {
    var api = requireApi();
    var encoded = encodeURIComponent(symbol);
    notify('loading', { operation: 'quote-expirations', symbol: symbol });
    var base = await Promise.all([
      api.getFresh('/api/config'),
      api.getFresh('/api/status'),
      api.getFresh('/api/world'),
      api.get('/api/research/' + encoded),
      api.get('/api/research/' + encoded + '/expirations'),
      optionalFresh('/api/account')
    ]);
    if (seq !== state.requestSeq) return null;
    var identity = marketIdentity(base[0], base[2], base[3], base[5]);
    if (base[0] && base[0].world && base[2] && base[2].world
        && String(base[0].world) !== String(base[2].world)) {
      throw new Error('Configuration and the active market world do not agree. Reload after the market transition completes.');
    }
    if (base[3] && base[3].marketLane && identity.marketLane
        && String(base[3].marketLane).toUpperCase() !== String(identity.marketLane).toUpperCase()) {
      throw new Error('The quote provenance lane does not match the active market lane.');
    }
    var research = base[3], quote = quoteFromResearch(research);
    if (!quote || String(quote.symbol || '').toUpperCase() !== symbol) {
      throw new Error(symbol + ' has no research-owned quote in the active StrikeBench market.');
    }
    assertEvidenceLane(quote.evidence, identity.marketLane, 'Quote');
    var mark = researchMark(research), spot = mark.value;
    if (!(spot > 0)) throw new Error(symbol + ' has no canonical market-owned display price.');
    var expirationAsOf = base[4] && base[4].asOfDate || quoteAsOfDate(quote);
    var expiration = chooseExpiration(base[4] && base[4].expirations, targetDays, expirationAsOf);
    if (!expiration) throw new Error(symbol + ' has no option expiration in the active market.');
    notify('loading', { operation: 'option-chain', symbol: symbol, expiration: expiration });
    var chain = await api.get('/api/research/' + encoded + '/chain?expiration=' + encodeURIComponent(expiration));
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
      config: base[0], status: base[1], world: base[2], research: research, quote: quote,
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

  function contextFromPlan(context, plan) {
    var exact = plan && plan.context || {};
    return Object.assign({}, context || {}, {
      symbol: plan && plan.symbol || context && context.symbol,
      goal: !plan || plan.intent == null ? null : String(plan.intent),
      view: exact.thesis == null ? null : String(exact.thesis),
      horizon: exact.horizonDays == null ? null : String(exact.horizonDays) + ' days',
      riskMode: exact.riskMode == null ? null : String(exact.riskMode).toLowerCase(),
      targetCents: exact.targetCents == null ? null : exact.targetCents,
      holdingsShares: exact.holdingsShares == null ? null : exact.holdingsShares,
      costBasisCents: exact.costBasisCents == null ? null : exact.costBasisCents,
      priceAssumptionCents: exact.priceAssumptionCents == null ? null : exact.priceAssumptionCents,
      assignmentPreference: exact.assignmentPreference == null ? null : exact.assignmentPreference,
      originPlanId: !plan || plan.originPlanId == null ? null : plan.originPlanId
    });
  }

  function stringHash(value) {
    var hash = 2166136261;
    for (var i = 0; i < value.length; i++) {
      hash ^= value.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
  }

  function sessionRequestKey(identity) {
    var material = JSON.stringify(identity);
    return 'strikebench.desk.planRequest.v2.' + stringHash(material);
  }

  function newSessionRequestId() {
    var suffix = window.crypto && window.crypto.randomUUID
      ? window.crypto.randomUUID() : Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
    return 'desk-' + suffix;
  }

  function sessionRequestId(identity, rotate) {
    var key = sessionRequestKey(identity);
    var existing = null;
    try { existing = window.sessionStorage.getItem(key); } catch (ignored) { /* storage can be disabled */ }
    if (existing && !rotate) return existing;
    var value = newSessionRequestId();
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
    var planIntent = plan.intent == null ? null : String(plan.intent).toUpperCase();
    if (plan.symbol !== identity.symbol || !sameNullable(identity.intent, planIntent)) return false;
    if (String(plan.marketKind || '').toUpperCase() !== identity.marketKind) return false;
    if (identity.marketKind === 'SIMULATED' && String(plan.worldId || '') !== String(identity.world || '')) return false;
    if (identity.marketKind !== 'SIMULATED' && plan.worldId != null
        && String(plan.worldId) !== String(identity.world || '').toLowerCase()) return false;
    if (identity.accountId && String(plan.accountId || '') !== String(identity.accountId)) return false;
    if (!sameNullable(identity.originPlanId, plan.originPlanId)) return false;
    var ctx = plan.context || {};
    if (!sameNullable(identity.thesis, ctx.thesis)
        || !sameNullable(identity.horizonDays, ctx.horizonDays)) return false;
    // Risk posture is mutable Plan context, not active Plan identity. The Plan service
    // deliberately resumes the same line of inquiry when a different entry surface supplies a
    // different header/default posture. Keep the persisted context authoritative; changing it is
    // an explicit PUT /context mutation, never a reason to reject or duplicate the returned Plan.
    // Optional declarations are compared whenever the Desk supplied them. Server-snapshotted
    // holdings may legitimately populate an otherwise absent field for INCOME/HEDGE/EXIT.
    return sameNullable(identity.targetCents, ctx.targetCents)
      && (identity.holdingsShares == null || Number(ctx.holdingsShares) === identity.holdingsShares)
      && (identity.costBasisCents == null || Number(ctx.costBasisCents) === identity.costBasisCents)
      && sameNullable(identity.priceAssumptionCents, ctx.priceAssumptionCents)
      && sameNullable(identity.assignmentPreference, ctx.assignmentPreference);
  }

  function mutableWorkingPlan(plan) {
    var status = String(plan && plan.status || '').toUpperCase();
    return !!plan && plan.open !== false && plan.assumptionsEditable !== false
      && (status === 'DRAFT' || status === 'ACTIVE');
  }

  function samePlanOwner(plan, identity) {
    if (!plan || plan.open === false || plan.status === 'ARCHIVED') return false;
    if (plan.symbol !== identity.symbol) return false;
    if (String(plan.marketKind || '').toUpperCase() !== identity.marketKind) return false;
    if (identity.marketKind === 'SIMULATED'
        && String(plan.worldId || '') !== String(identity.world || '')) return false;
    if (identity.marketKind !== 'SIMULATED' && plan.worldId != null
        && String(plan.worldId) !== String(identity.world || '').toLowerCase()) return false;
    if (identity.accountId && String(plan.accountId || '') !== String(identity.accountId)) return false;
    return sameNullable(identity.originPlanId, plan.originPlanId);
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
    // A Position-owned Plan remains open so Manage can retain its exact historical receipt, but
    // its decision and assumptions are frozen. Global New idea may inherit that Position's symbol;
    // it must never interpret the otherwise matching frozen Plan as a mutable working inquiry.
    var candidates = (rows || []).filter(function (row) {
      return mutableWorkingPlan(row) && samePlan(row, identity);
    })
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
    var requestedPlanId = context.planId == null ? null : String(context.planId).trim();
    var plan = null;
    if (requestedPlanId) {
      // Home resumes the exact Plan the user clicked. Its visible declarations still have to
      // match the active account and market; an id is never permission to cross those seams.
      plan = await api.getFresh('/api/plans/' + encodeURIComponent(requestedPlanId));
      if (seq !== state.requestSeq) return null;
      if (!samePlanOwner(plan, identity)) {
        throw new Error('The returned Plan does not belong to this Desk account and market.');
      }
      if (!mutableWorkingPlan(plan)) {
        throw new Error('This saved Plan is no longer a mutable working idea. Start a new idea without rewriting its decision history.');
      }
      // The exact Plan owns its mutable declarations. Another tab may have advanced them since
      // Home rendered; adopt the current version instead of turning a legitimate update into a
      // permanent mismatch/retry loop.
      context = contextFromPlan(context, plan);
      state.context = context;
      intent = intentOf(context.goal);
      identity = planIdentity(symbol, intent, context, market);
    } else {
      var listed = await api.getFresh('/api/plans');
      if (seq !== state.requestSeq) return null;
      if (listed && listed.world && market.identity.world
          && String(listed.world) !== String(market.identity.world)) {
        throw new Error('The Plan list belongs to another market world.');
      }
      if (listed && listed.market && String(listed.market).toUpperCase() !== identity.marketKind) {
        throw new Error('The Plan list belongs to another market lane.');
      }
      plan = await freshestMatchingPlan(listed && listed.plans, identity, seq);
    }
    var requested = null;
    async function createPlan(rotateRequestId) {
      requested = requested || requestedPlanContext(context);
      return api.post('/api/plans', {
        clientRequestId: sessionRequestId(identity, rotateRequestId),
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
    if (!plan) {
      plan = await createPlan(false);
      if (seq !== state.requestSeq) return null;
      if (!samePlan(plan, identity) || !mutableWorkingPlan(plan)) {
        // The session-scoped create key can legitimately outlive the Plan it originally created:
        // that Plan may no longer match this idea's declarations, OR it may have been frozen since
        // (a trade was placed, so it is now a Position, not a mutable working inquiry). In both
        // cases the idempotent create returns the stale Plan. Re-list first so a concurrent
        // *mutable* Plan wins, otherwise rotate the create key exactly once to mint a fresh Plan.
        // Reusing the stale key would make Retry a permanent loop on the frozen Plan.
        var relisted = await api.getFresh('/api/plans');
        if (seq !== state.requestSeq) return null;
        plan = await freshestMatchingPlan(relisted && relisted.plans, identity, seq);
        if (!plan) plan = await createPlan(true);
      }
    }
    if (seq !== state.requestSeq) return null;
    if (!mutableWorkingPlan(plan)) {
      throw new Error('The backend did not return a mutable working Plan for this new idea.');
    }
    if (!samePlan(plan, identity)) {
      throw new Error('The returned Plan does not match this Desk idea, account, and market identity.');
    }
    state.planIdentity = identity;
    state.plan = plan;
    // A resumed Plan owns its persisted mutable declarations. Hydrate all of them into the Desk
    // adapter rather than leaving a header default beside a different backend truth.
    state.context = contextFromPlan(state.context || context, plan);
    notify('plan', { plan: plan });
    // The presentation synchronously maps the qualitative Plan posture onto its visible cap
    // preset. Capture that canonicalized state for an ordinary entry, but retain an explicit
    // Screens & Caps edit: it is a recommendation filter within the same Plan posture.
    if (state.context && window.decide && window.decide.govs) {
      if (context.__deskGovernorOverride === true) {
        window.decide.govs = Object.assign({}, window.decide.govs, state.context.governors || {});
      } else {
        state.context.governors = Object.assign({}, state.context.governors || {}, window.decide.govs);
      }
    }
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
      entryPrice: leg.entryPrice,
      quoteBid: leg.quoteBid,
      quoteAsk: leg.quoteAsk,
      quoteAsOfEpochMs: leg.quoteAsOfEpochMs,
      quoteSource: leg.quoteSource,
      quoteFreshness: leg.quoteFreshness
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
    /* Editing is a pure preview until the user applies the exact package. Preserve the last
       accepted selected-candidate animation while canceling any in-flight projection request;
       otherwise the UI can mix a generic fan with the still-selected conditioned valuation. */
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
    var mutationOwner = beginMutation('draft');
    draftPreviewSeq++;
    state.draft = Object.assign({}, draft, { pending: true, applying: true });
    notify('draft-applying', { operation: 'draft-select', draft: state.draft });
    var presentation = null;
    try {
      var response = await requireApi().post('/api/plans/' + encodeURIComponent(state.plan.id)
        + '/strategy/custom', {
        expectedVersion: state.plan.version,
        position: draft.position
      });
      if (seq !== state.requestSeq) return null;
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
      if (!outcome || seq !== state.requestSeq) return null;
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
      if (seq !== state.requestSeq) return null;
      await previewDecision({ type: 'MARKET', qty: custom.qty || 1 }, seq);
      if (seq !== state.requestSeq) return null;
      notify('ready', { operation: 'draft-select' });
      return copyState();
    } catch (error) {
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
    } finally {
      endMutation(mutationOwner);
    }
  }

  function strategyControls(context) {
    var governors = Object.assign({}, window.decide && window.decide.govs || {},
      context && (context.governors || context.govs) || {});
    var explicit = Object.assign({}, window.decide && window.decide.govExplicit || {},
      context && (context.governorExplicit || context.govExplicit) || {});
    // A one-session decision horizon is not consent to same-day gamma exposure. Keep 0DTE behind
    // an explicit declaration when the Desk adds that control; the backend will otherwise select
    // the nearest live expiration while retaining the Plan's one-session outcome horizon.
    var controls = {};
    if (context && context.allow0dte === true) controls.allow0dte = true;
    var maxLoss = number(governors.risk);
    if (explicit.risk === true && maxLoss != null && maxLoss > 0 && Number.isFinite(maxLoss)) {
      controls.maxLossCents = Math.round(maxLoss * 100);
    }
    var filters = {};
    var minPop = number(governors.minPop), maxAssignment = number(governors.maxAsn);
    var maxCost = number(governors.maxCost);
    if (explicit.minPop === true && minPop != null && minPop > 0) {
      filters.minPop = Math.max(0, Math.min(1, minPop / 100));
    }
    if (explicit.maxAsn === true && maxAssignment != null && maxAssignment >= 0) {
      filters.maxAssignmentProb = Math.max(0, Math.min(1, maxAssignment / 100));
    }
    if (explicit.maxCost === true && maxCost != null && maxCost > 0) {
      filters.maxCostCents = Math.round(maxCost * 100);
    }
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
    if (Array.isArray(rejection.reasons) && rejection.reasons.length) {
      return rejection.reasons.map(String).join('; ');
    }
    if (Array.isArray(rejection.blockReasons) && rejection.blockReasons.length) {
      return rejection.blockReasons.map(String).join('; ');
    }
    if (rejection.detail) return String(rejection.detail);
    return null;
  }

  function candidateToDesk(candidate, market) {
    var qty = Math.max(1, Number(candidate.qty || 1));
    var riskProfile = candidate.evaluation && candidate.evaluation.risk || {};
    var terminalPayoff = riskProfile.terminalPayoff || {};
    var payoffPoints = terminalPayoff.available === true && Array.isArray(terminalPayoff.points)
      ? terminalPayoff.points.map(function (point) {
          return { price: Number(point.price), profit: Number(point.profitCents) / 100 };
        }).filter(function (point) {
          return Number.isFinite(point.price) && Number.isFinite(point.profit);
        }) : [];
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
    var realisticEv = economics.realizedVolEvAfterCostsCents == null
      ? null : Number(economics.realizedVolEvAfterCostsCents);
    var realisticLow = economics.realisticEvLowAfterCostsCents == null
      ? null : Number(economics.realisticEvLowAfterCostsCents);
    var realisticHigh = economics.realisticEvHighAfterCostsCents == null
      ? null : Number(economics.realisticEvHighAfterCostsCents);
    var marketCostBenchmark = economics.marketEvAfterCostsCents == null
      ? null : Number(economics.marketEvAfterCostsCents);
    return {
      id: candidate.id,
      short: candidate.displayName || candidate.strategy || candidate.label,
      exactPackage: candidate.label || null,
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
      pop: (candidate.pop == null ? riskProfile.pop : candidate.pop) == null ? null
        : Math.round(Number(candidate.pop == null ? riskProfile.pop : candidate.pop) * 100),
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
      terminalPayoff: terminalPayoff,
      payoffPoints: payoffPoints,
      usesHeldShares: candidate.usesHeldShares === true,
      sharesNeeded: candidate.sharesNeeded == null ? null : Number(candidate.sharesNeeded),
      edge: realisticEv == null ? null : realisticEv / 100,
      edgeLow: realisticLow == null ? null : realisticLow / 100,
      edgeHigh: realisticHigh == null ? null : realisticHigh / 100,
      edgeBasis: realisticEv == null ? null : 'REALIZED_VOL_AFTER_COSTS',
      edgeRangeBasis: economics.realisticEvBasis || null,
      marketCostBenchmark: marketCostBenchmark == null ? null : marketCostBenchmark / 100,
      marketEvRole: economics.marketEvRole || null,
      assign: candidate.assignmentProb == null ? null : Math.round(Number(candidate.assignmentProb) * 100),
      why: candidate.whyConsidered || candidate.beginnerExplanation || '',
      analog: candidate.sourceKind ? 'Backend-ranked comparison · ' + candidate.sourceKind : 'Backend-ranked comparison',
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
    var notes = result && Array.isArray(result.notes) ? result.notes.map(String) : [];
    var visible = mergeSelectedCandidate(ranked, selected);
    if (!ranked.length && !selected) {
      state.strategy = strategy;
      state.candidates = [];
      state.selected = null;
      state.rejections = rejected.slice();
      state.strategyNotes = notes.slice();
      state.deskPickId = null;
      notify('strategy-empty', {
        plan: state.plan,
        strategy: strategy,
        notes: notes,
        rejected: rejected,
        reasons: rejected.map(rejectionText).filter(Boolean)
      });
      return [];
    }
    await classifyCandidates(visible);
    if (seq !== state.requestSeq) return null;
    state.strategy = strategy;
    state.candidates = visible;
    state.selected = selected || null;
    state.rejections = rejected.slice();
    state.strategyNotes = notes.slice();
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
    var currentEvaluationContract = candidates.every(function (candidate) {
      var evaluation = candidate.evaluation || {};
      if (evaluation.available === false) return true;
      var terminal = evaluation.risk && evaluation.risk.terminalPayoff;
      var expirations = new Set((candidate.legs || []).filter(function (leg) {
        return String(leg.type || '').toUpperCase() !== 'STOCK';
      }).map(function (leg) { return leg.expiration; }));
      if (expirations.size > 1) {
        return !!(terminal && terminal.available === false && terminal.unavailableReason);
      }
      return !!(terminal && terminal.available === true
        && Array.isArray(terminal.points) && terminal.points.length >= 2);
    });
    var reusable = strategy && String(strategy.state || 'CURRENT').toUpperCase() === 'CURRENT'
      && result && Array.isArray(result.candidates) && !!(receipt
        && currentEvaluationContract
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

  function candidateEconomics(candidate) {
    return String(candidate.evaluation && candidate.evaluation.assessment
      && candidate.evaluation.assessment.economics
      && candidate.evaluation.assessment.economics.verdict || 'UNAVAILABLE').toUpperCase();
  }

  function deskPickCandidate(candidates) {
    return candidates.find(function (candidate) {
      // COHERENT answers whether the package expresses the declaration; it is not an
      // endorsement. Only the backend's favorable after-cost economic verdict may promote a
      // mechanically usable, coherent package to Desk Pick. Mixed, unavailable, and adverse
      // packages remain ranked comparisons and may still be selected explicitly.
      return mechanicallyUsable(candidate) && candidateCoherence(candidate) === 'COHERENT'
        && candidateEconomics(candidate) === 'FAVORABLE';
    }) || null;
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
    var receipt = out && out.selection;
    var echoedId = receipt && receipt.candidateId;
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
    // The market ensemble is deliberately reusable across packages, but an outcome is the
    // valuation of one exact package. Clear it only after the new selection is accepted so a
    // rejected mutation retains the prior package and its still-valid result.
    state.outcome = null;
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
    if (!ensemble || !ensemble.ensemble || !ensemble.preview
        || !ensembleMatchesCurrentMarket(ensemble, false, true)) {
      var rollover = new Error('Market inputs changed while the outcome fan was being built. Reloading this idea on the current quote and option surface.');
      rollover.code = 'DESK_MARKET_ROLLOVER';
      throw rollover;
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

  function ensembleMatchesCurrentMarket(envelope, requireCalibrationReceipt, acceptNewerServerObservation) {
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
        || anchorSource !== quoteSource
        || anchorFreshness !== quoteFreshness
        || (storedVol != null && currentMarketIv != null
          && Math.abs(storedVol - currentMarketIv) > 0.000001)) return false;
    // A POST /outcomes/ensemble response is itself the newer authoritative observation. During
    // an open market the provider can advance between the Desk's initial quote read and that
    // server-owned build; accepting a newer receipt preserves one exact stored artifact instead
    // of chasing a moving quote with repeated full-chain reads. Stored artifacts loaded on a
    // later visit still require the exact current observation and are rebuilt when it changes.
    if (acceptNewerServerObservation) return anchorAsOf >= quoteAsOf;
    if (Math.round(anchorSpot * 100) !== Math.round(currentSpot * 100)
        || anchorAsOf !== quoteAsOf) return false;
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
    var previewGuardrails = preview.guardrails || {};
    var previewInstruction = String(preview.order && preview.order.orderInstruction
      && preview.order.orderInstruction.type || body.orderInstruction && body.orderInstruction.type || '')
      .toUpperCase();
    var exactPackageUnavailable = String(preview.order && preview.order.executability || '').toUpperCase()
      === 'UNAVAILABLE';
    var exactPackageBlocked = previewInstruction === 'MARKET'
      && (String(previewGuardrails.level || '').toUpperCase() === 'BLOCK'
        || (Array.isArray(previewGuardrails.blockReasons) && previewGuardrails.blockReasons.length > 0)
        || (Array.isArray(preview.preview && preview.preview.blockReasons)
          && preview.preview.blockReasons.length > 0));
    if (state.deskPickId != null
        && String(state.deskPickId) === String(preview.selected.id)
        && (exactPackageUnavailable || exactPackageBlocked)) {
      // Ranking-time economics can identify an attractive package, but the badge is an
      // endorsement of the exact package the user can act on. Reconcile a missing executable
      // book immediately and final structural guardrails with the MARKET package preview. A
      // merely resting LIMIT is an instruction choice and does not demote the idea.
      state.deskPickId = null;
    }
    preview.deskRequestKey = requestKey;
    state.decisionPreview = preview;
    state.decisionPreviewKey = requestKey;
    notify('decision-preview', { plan: state.plan, preview: preview, deskPickId: state.deskPickId });
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

  function pause(milliseconds) {
    return new Promise(function (resolve) { window.setTimeout(resolve, milliseconds); });
  }

  function sessionSupportsSymbol(session, symbol) {
    var betas = session && session.config && session.config.symbolBetas || {};
    return Object.keys(betas).some(function (key) {
      return String(key).toUpperCase() === String(symbol).toUpperCase();
    });
  }

  async function waitForPreparedWorld(worldId, symbol) {
    var deadline = Date.now() + 120000;
    while (Date.now() < deadline) {
      var rows = (await requireApi().getFresh('/api/sim/market')).sessions || [];
      var session = rows.find(function (row) { return String(row.id) === String(worldId); });
      if (!session) throw new Error('The new simulated market could not be found.');
      var status = String(session.status || '').toUpperCase();
      if (status === 'CREATED' || status === 'PAUSED' || status === 'RUNNING') {
        if (!sessionSupportsSymbol(session, symbol)) {
          var anchors = await requireApi().getFresh('/api/sim/market/' + encodeURIComponent(worldId) + '/anchors');
          var exclusion = (anchors.excluded || []).find(function (row) {
            return String(row.symbol || '').toUpperCase() === String(symbol).toUpperCase();
          });
          throw new Error(symbol + ' could not enter the simulated market'
            + (exclusion && exclusion.reason ? ': ' + exclusion.reason : ' because no server-owned anchor was available.'));
        }
        return session;
      }
      if (status === 'FAILED' || status === 'FINISHED') {
        throw new Error('The simulated market is ' + status.toLowerCase()
          + '. Its durable anchor receipt remains available in Data.');
      }
      await pause(500);
    }
    throw new Error('The simulated market is still preparing. It remains saved in Data; enter it when its status becomes ready.');
  }

  async function simulatedWorldFor(symbol) {
    var api = requireApi();
    var rows = (await api.getFresh('/api/sim/market')).sessions || [];
    var unique = [];
    rows.forEach(function (row) {
      if (!row || row.rehearsal || unique.some(function (saved) { return String(saved.id) === String(row.id); })) return;
      var status = String(row.status || '').toUpperCase();
      if (status !== 'FAILED' && status !== 'FINISHED' && sessionSupportsSymbol(row, symbol)) unique.push(row);
    });
    var running = unique.filter(function (row) { return String(row.status || '').toUpperCase() === 'RUNNING'; });
    if (running.length === 1) return running[0];
    if (unique.length > 1) {
      throw new Error('More than one ' + symbol + ' simulated market is available. Choose the exact world in Data so its provenance is explicit.');
    }
    if (unique.length === 1) return waitForPreparedWorld(unique[0].id, symbol);
    var created = await api.post('/api/sim/market', {
      name: symbol + ' Desk simulation',
      symbols: Object.fromEntries([[symbol, 1.0]]),
      scenario: 'CHOP',
      speed: 26,
      allowFictional: false
    });
    return waitForPreparedWorld(created.worldId, symbol);
  }

  function clearAuthoritativeArtifacts() {
    bookRequestSeq++;
    positionRequestSeq++;
    positionScenarioRequestSeq++;
    state.market = null;
    state.plan = null;
    state.planIdentity = null;
    state.strategy = null;
    state.candidates = [];
    state.deskPickId = null;
    state.selected = null;
    state.ensemble = null;
    state.outcome = null;
    state.decision = null;
    state.decisionPreview = null;
    state.decisionPreviewKey = null;
    state.draft = null;
    state.rejections = [];
    state.strategyNotes = [];
    state.animation = null;
    state.book = null;
    state.position = null;
    state.positionScenario = null;
  }

  async function transitionWorld(mode, context) {
    if (!state.enabled) return null;
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var mutationOwner = beginMutation('world');
    var seq = ++state.requestSeq;
    state.animationSeq++;
    var requestedContext = Object.assign({}, context || state.context || {});
    var symbol = String(requestedContext.symbol || state.context && state.context.symbol || '').trim().toUpperCase();
    var target = null, verification = null;
    try {
      state.error = null;
      notify('loading', { operation: 'market-transition' });
      if (String(mode || '').toLowerCase() === 'observed') {
        target = 'observed';
      } else {
        if (!symbol) throw new Error('Choose an underlying before creating a simulated market.');
        var requestedWorldId = requestedContext.targetWorldId == null
          ? '' : String(requestedContext.targetWorldId).trim();
        var session = requestedWorldId
          ? await waitForPreparedWorld(requestedWorldId, symbol)
          : await simulatedWorldFor(symbol);
        if (seq !== state.requestSeq) return null;
        target = session.id;
        if (String(session.status || '').toUpperCase() !== 'RUNNING') {
          await requireApi().post('/api/sim/market/' + encodeURIComponent(target) + '/start', {});
        }
      }
      var transitioned = await requireApi().put('/api/world', { world: target });
      var checked = await Promise.all([
        requireApi().getFresh('/api/world'), requireApi().getFresh('/api/config')
      ]);
      if (seq !== state.requestSeq) return null;
      var world = checked[0] || {}, config = checked[1] || {};
      var expectedLane = target === 'observed' ? 'OBSERVED' : 'SIMULATED';
      if (String(world.world || '') !== String(target)
          || String(config.world || '') !== String(target)
          || String(config.marketLane || '').toUpperCase() !== expectedLane) {
        throw new Error('The server did not confirm one coherent ' + expectedLane.toLowerCase() + ' market transition.');
      }
      clearAuthoritativeArtifacts();
      verification = { target: target, world: world, config: config, transition: transitioned };
      notify('world-transition', verification);
    } catch (error) {
      return fail(seq, 'market-transition', error);
    } finally {
      endMutation(mutationOwner);
    }
    if (!verification) return null;
    if (!symbol || requestedContext.reopen === false) return verification;
    return openIdea(requestedContext);
  }

  async function openIdea(context) {
    if (!state.enabled) return null;
    if (state.mutationPending) {
      if (!activeMutationCancelled) {
        throw new Error('Wait for the current Plan change to finish.');
      }
      pendingIdeaContext = Object.assign({}, context || {});
      notify('loading', { operation: 'idea-queued' });
      return null;
    }
    var mutationOwner = beginMutation('idea');
    var seq = ++state.requestSeq;
    state.animationSeq++;
    var marketRolloverRetries = Math.max(0, Number(context && context.__marketRolloverRetries || 0));
    context = Object.assign({}, context || {});
    delete context.__marketRolloverRetries;
    context.governors = Object.assign({}, window.decide && window.decide.govs || {},
      context.governors || context.govs || {});
    context.governorExplicit = Object.assign({}, window.decide && window.decide.govExplicit || {},
      context.governorExplicit || context.govExplicit || {});
    state.context = context;
    state.presentationError = null;
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
    state.strategyNotes = [];
    state.animation = null;
    invalidateDecisionPreview('idea-changed');
    try {
      notify('loading', { operation: 'idea' });
      var symbol = String(context && context.symbol || '').trim().toUpperCase();
      if (!symbol) throw new Error('Choose an underlying before loading a Desk idea.');
      var declaredHorizon = horizonDays(context);
      /* Recommendations are a context-specific competition, not the product's capability
         catalog. Load the existing server-owned catalog additively so the Desk can show which
         supported families were compared, screened, blocked, or simply belong to another
         intent without forcing every family into the ranking. */
      requestStrategyCatalog().then(function () {
        // Catalog disclosure cannot gate Plan/recommendation work. Its independent receipt
        // updates the rail when it arrives without changing or restarting the financial phase.
        if (seq === state.requestSeq) notify('strategy-catalog', {
          operation: 'strategy-catalog', strategyCatalog: state.strategyCatalog
        });
      });
      var market = await loadMarket(symbol, declaredHorizon == null ? 45 : declaredHorizon, seq);
      if (!market || seq !== state.requestSeq) return null;
      var plan = await ensurePlan(context, market, seq);
      if (!plan || seq !== state.requestSeq) return null;
      var planContext = plan.context || {};
      var missingDeclarations = [];
      if (plan.intent == null || String(plan.intent).trim() === '') missingDeclarations.push('goal');
      if (planContext.thesis == null || String(planContext.thesis).trim() === '') missingDeclarations.push('view');
      if (!(Number(planContext.horizonDays) > 0)) missingDeclarations.push('horizon');
      if (planContext.riskMode == null || String(planContext.riskMode).trim() === '') missingDeclarations.push('risk posture');
      if (missingDeclarations.length) {
        state.strategyNotes = ['Declare the missing Plan assumptions before ranking: '
          + missingDeclarations.join(', ') + '.'];
        notify('declaration-required', {
          operation: 'declaration', plan: plan,
          missingDeclarations: missingDeclarations.slice(), notes: state.strategyNotes.slice()
        });
        return copyState();
      }
      var candidates = await loadOrRunStrategy(plan, market, state.context, seq);
      if (!candidates || seq !== state.requestSeq) return null;
      // A current, fingerprinted competition with no candidates is a valid backend result. The
      // Desk keeps the declaration, evidence, and screening receipts visible and waits for an
      // explicit assumption change instead of fabricating a package or entering outcome/preview.
      if (!candidates.length) return copyState();
      var candidate = state.selected || deskPickCandidate(candidates);
      // Ranking is not endorsement. If no mechanically usable, coherent package earns a
      // FAVORABLE after-cost verdict, keep the full comparison field visible but do not write a
      // durable Plan selection merely because one row must occupy the center preview. An explicit
      // user click can still select and evaluate any teaching comparison through the same flow.
      if (!candidate) {
        notify('comparison-required', {
          operation: 'comparison-selection', plan: state.plan,
          candidates: state.candidates, notes: state.strategyNotes,
          message: 'No package earned an endorsement. Choose a ranked comparison explicitly to analyze it.'
        });
        return copyState();
      }
      if (!state.selected) {
        await selectCandidate(candidate.id, seq);
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
      // A cold observed-provider refresh can legitimately replace the quote/chain while the first
      // fan is being stored. Never display that mixed attempt, but converge automatically on a
      // newly captured market snapshot instead of stranding the user in a partial error screen.
      if (error && error.code === 'DESK_MARKET_ROLLOVER'
          && seq === state.requestSeq && marketRolloverRetries < 2) {
        notify('loading', { operation: 'market-refresh', retry: marketRolloverRetries + 1 });
        var queuedIdeaSupersedesRetry = !!pendingIdeaContext;
        endMutation(mutationOwner);
        mutationOwner = null;
        if (queuedIdeaSupersedesRetry) return null;
        return openIdea(Object.assign({}, state.context || context, {
          __marketRolloverRetries: marketRolloverRetries + 1
        }));
      }
      return fail(seq, 'idea', error);
    } finally {
      if (mutationOwner != null) endMutation(mutationOwner);
    }
  }

  /**
   * Mutates the declarations of the exact active Plan through its canonical APIs, then rebuilds
   * the recommendation/outcome flow from that returned version. The Desk never forks a hidden
   * replacement Plan merely because the user changed a visible declaration control.
   */
  async function updatePlanDeclaration(context) {
    if (!state.enabled || !state.plan || !state.market) return openIdea(context || {});
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var mutationOwner = beginMutation('declaration');
    var seq = ++state.requestSeq;
    state.animationSeq++;
    state.error = null;
    var next = Object.assign({}, state.context || {}, context || {}, { planId: state.plan.id });
    var updated = state.plan;
    try {
      notify('loading', { operation: 'declaration' });
      var nextIntent = intentOf(next.goal);
      var currentIntent = updated.intent == null ? null : String(updated.intent).toUpperCase();
      if (!sameNullable(nextIntent, currentIntent)) {
        updated = await requireApi().put('/api/plans/' + encodeURIComponent(updated.id) + '/intent', {
          expectedVersion: updated.version, intent: nextIntent
        });
        if (seq !== state.requestSeq) return null;
      }

      var current = updated.context || {};
      var requested = requestedPlanContext(next);
      var desired = {
        thesis: requested.thesis,
        horizonDays: requested.horizonDays,
        targetCents: requested.targetCents,
        riskMode: requested.riskMode,
        holdingsShares: requested.holdingsShares,
        costBasisCents: requested.costBasisCents,
        priceAssumptionCents: requested.priceAssumptionCents,
        assignmentPreference: requested.assignmentPreference
      };
      var patch = { expectedVersion: updated.version };
      var clear = [];
      Object.keys(desired).forEach(function (key) {
        if (sameNullable(desired[key], current[key])) return;
        if (desired[key] == null) clear.push(key);
        else patch[key] = desired[key];
      });
      if (clear.length) patch.clear = clear;
      if (Object.keys(patch).length > 1) {
        updated = await requireApi().put('/api/plans/' + encodeURIComponent(updated.id) + '/context', patch);
        if (seq !== state.requestSeq) return null;
      }

      if (!updated || String(updated.id) !== String(state.plan.id)) {
        throw new Error('The declaration response did not retain the active Plan identity.');
      }
      var identity = planIdentity(String(next.symbol || updated.symbol || '').toUpperCase(),
        intentOf(next.goal), next, state.market);
      if (!samePlan(updated, identity)
          || !sameNullable(riskModeOf(next), updated.context && updated.context.riskMode)) {
        throw new Error('The returned Plan does not match the declarations accepted by the Desk.');
      }
      state.plan = updated;
      state.planIdentity = identity;
      state.context = next;
      notify('declaration', { operation: 'declaration', plan: updated, context: next });
    } catch (error) {
      if (error && error.status === 409 && updated && updated.id) {
        try {
          var currentPlan = await requireApi().getFresh('/api/plans/'
            + encodeURIComponent(updated.id));
          if (seq !== state.requestSeq) return null;
          if (!samePlanOwner(currentPlan, planIdentity(String(updated.symbol || next.symbol || '').toUpperCase(),
              currentPlan.intent == null ? null : String(currentPlan.intent).toUpperCase(), next, state.market))) {
            throw new Error('The current Plan no longer belongs to this Desk account and market.');
          }
          state.plan = currentPlan;
          state.context = contextFromPlan(next, currentPlan);
          state.planIdentity = planIdentity(String(currentPlan.symbol || '').toUpperCase(),
            intentOf(state.context.goal), state.context, state.market);
        } catch (refreshError) {
          // Preserve the original version-conflict receipt. Retry performs a fresh exact-Plan read;
          // no prototype value is substituted if that read is temporarily unavailable.
        }
      }
      return fail(seq, 'declaration', error);
    } finally {
      endMutation(mutationOwner);
    }
    return openIdea(next);
  }

  async function chooseCandidate(candidateId) {
    if (!state.enabled) return null;
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var seq = ++state.requestSeq;
    var mutationOwner = beginMutation('candidate');
    state.draft = null;
    draftPreviewSeq++;
    state.error = null;
    notify('loading', { operation: 'candidate' });
    try {
      await selectCandidate(candidateId, seq);
      if (seq !== state.requestSeq) return null;
      var outcome;
      if (!state.ensemble || !state.ensemble.ensemble || !state.ensemble.ensemble.id) {
        // A comparison field with no endorsed Desk Pick deliberately has no automatic Plan
        // selection or simulation. The first explicit selection now creates/restores the one
        // canonical fan and values this exact package against it.
        var firstEvaluation = await loadOrRunEnsembleAndOutcome(seq);
        outcome = firstEvaluation && firstEvaluation.outcome;
      } else {
        // Candidate/package changes are child valuations over the same stored market ensemble.
        // Do not regenerate the fan; rerun the exact outcome against its immutable id.
        outcome = await runOutcome(seq, {
          expectedCandidateId: candidateId,
          expectedEnsemble: state.ensemble && state.ensemble.ensemble
        });
      }
      if (!outcome || seq !== state.requestSeq) return null;
      await loadDecisionState(seq);
      if (seq !== state.requestSeq) return null;
      await previewDecision({ type: 'MARKET', qty: state.selected && state.selected.qty || 1 }, seq);
      notify('ready', { operation: 'candidate' });
      return copyState();
    } catch (error) {
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
    var mutationOwner = beginMutation('order-commit');
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
      var detached = activeMutationCancelled;
      acceptPlan(response.plan || state.plan);
      state.decisionPreview = null;
      state.decisionPreviewKey = null;
      // A successful commitment leaves this decision journey. A cap change queued while the
      // trade was being recorded belongs to the superseded idea, so it must not replay later
      // against whichever Plan happens to be active next.
      clearPendingGovernorRefresh();
      recentCommittedTradeId = response && response.trade && response.trade.id
        ? String(response.trade.id) : null;
      var committedTradeId = recentCommittedTradeId;
      notify('committed', {
        operation: 'order-commit', response: response, detached: detached,
        reconciling: !!committedTradeId
      });
      // A committed transaction is already final. Reconcile its independent Book read without
      // letting a presentation/read failure turn the successful write into a reported failure.
      // The UI leaves Decide only after the roster proves the new package is visible.
      if (committedTradeId) {
        (async function reconcileCommit() {
          try {
            var data = await loadBook();
            var found = data && (data.activeTrades || []).some(function (trade) {
              return String(trade && trade.id) === committedTradeId;
            });
            if (!found) {
              await new Promise(function (resolve) { window.setTimeout(resolve, 180); });
              data = await loadBook();
              found = data && (data.activeTrades || []).some(function (trade) {
                return String(trade && trade.id) === committedTradeId;
              });
            }
            if (!found) throw new Error('The committed trade has not appeared in the authoritative Book roster yet.');
            notify('commit-reconciled', {
              operation: 'order-commit', response: response,
              tradeId: committedTradeId, detached: detached
            });
            if (recentCommittedTradeId === committedTradeId) recentCommittedTradeId = null;
          } catch (error) {
            notify('commit-reconcile-error', {
              operation: 'order-commit', response: response,
              tradeId: committedTradeId, detached: detached, error: error
            });
            if (recentCommittedTradeId === committedTradeId) recentCommittedTradeId = null;
          }
        })();
      } else {
        loadBook().catch(function () { /* the commit receipt remains authoritative */ });
        notify('commit-reconciled', {
          operation: 'order-commit', response: response, tradeId: null, detached: detached
        });
      }
      return response;
    } catch (error) {
      return fail(seq, 'order-commit', error);
    } finally {
      endMutation(mutationOwner);
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
    var pathWaypoints = scenario && Array.isArray(scenario.pathWaypoints)
      ? scenario.pathWaypoints.map(function (pin) {
        return {
          sessionProgress: number(pin && pin.sessionProgress),
          priceRatio: number(pin && pin.priceRatio),
          tolerance: pin && pin.tolerance == null ? null : number(pin.tolerance)
        };
      }) : [];
    if (pathWaypoints.some(function (pin, index) {
      return !(pin.sessionProgress > (index ? pathWaypoints[index - 1].sessionProgress : 0))
        || pin.sessionProgress > horizon || !(pin.priceRatio > 0)
        || (pin.tolerance != null && !(pin.tolerance >= 0));
    })) throw new Error('Intraday scenario pins must be ordered within the stored session horizon.');
    var body = { ensembleId: state.ensemble.ensemble.id, limit: 48 };
    if (pathWaypoints.length) body.pathWaypoints = pathWaypoints;
    else body.waypoints = waypoints;
    requestIdentity.waypoints = pathWaypoints.length ? [] : waypoints;
    requestIdentity.pathWaypoints = pathWaypoints;
    var canvas = transientCanvas(scenario);
    if (canvas) body.canvas = canvas;
    state.error = null;
    notify('loading', { operation: 'scenario-animation' });
    try {
      var response = window.PlanStore && typeof window.PlanStore.scenarioAnimation === 'function'
        ? await window.PlanStore.scenarioAnimation(state.plan, body)
        : await requireApi().post('/api/plans/' + encodeURIComponent(state.plan.id)
          + '/outcomes/ensemble/paths', body);
      if (token !== state.animationSeq) return null;
      var receipt = response && response.receipt || {}, selection = response && response.paths && response.paths.receipt || {};
      var checkpoints = response && response.checkpoints || {}, modelReceipt = checkpoints.modelReceipt || {};
      var returnedWaypoints = receipt.conditioningAssumptions
        && receipt.conditioningAssumptions.waypoints || [];
      var returnedPathWaypoints = receipt.conditioningPathWaypoints || [];
      if (!response || !response.plan || response.plan.id !== requestIdentity.planId
          || response.ensemble.id !== requestIdentity.ensembleId
          || response.ensemble.fingerprint !== requestIdentity.ensembleFingerprint
          || receipt.ensembleId !== requestIdentity.ensembleId
          || receipt.ensembleFingerprint !== requestIdentity.ensembleFingerprint
          || receipt.selectedCandidateId !== requestIdentity.candidateId
          || requestIdentity.contextRev != null && Number(receipt.contextRev) !== Number(requestIdentity.contextRev)
          || JSON.stringify(canonicalJson(returnedWaypoints))
            !== JSON.stringify(canonicalJson(requestIdentity.waypoints))
          || requestIdentity.pathWaypoints.length
            && JSON.stringify(canonicalJson(returnedPathWaypoints))
              !== JSON.stringify(canonicalJson(requestIdentity.pathWaypoints))
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
      state.error = null;
      notify('animation', { animation: response, scenario: Object.assign({ day: day }, scenario || {}) });
      return response;
    } catch (error) {
      if (token !== state.animationSeq) return null;
      state.error = error;
      notify('error', { operation: 'scenario-animation', error: error });
      throw error;
    }
  }

  function slotsByKey(slots) {
    var result = {};
    (slots || []).forEach(function (slot) { result[slot.key] = slot; });
    return result;
  }

  function missingSlots(slots) {
    return (slots || []).filter(function (slot) { return !slot.available; }).map(function (slot) {
      return { key: slot.key, path: slot.path, error: slot.error };
    });
  }

  function requireSlot(slot, label) {
    if (slot && slot.available) return slot.value;
    var detail = slot && slot.error && slot.error.message;
    var error = new Error(label + ' is unavailable' + (detail ? ': ' + detail : '.'));
    if (slot && slot.error) {
      error.status = slot.error.status;
      error.code = slot.error.code;
    }
    if (slot && slot.path) error.path = slot.path;
    throw error;
  }

  function assertPlanPortfolioIdentity(documentValue, identity) {
    if (!documentValue || !documentValue.market) return;
    var expected = expectedMarketKind(identity);
    if (String(documentValue.market).toUpperCase() !== String(expected).toUpperCase()) {
      throw new Error('The Plan portfolio belongs to ' + documentValue.market
        + ', not the active ' + expected + ' market.');
    }
  }

  function activeUniverseSymbols(universe) {
    var symbols = universe && universe.active && universe.active.symbols;
    return Array.isArray(symbols) ? symbols.map(function (symbol) {
      return String(symbol || '').trim().toUpperCase();
    }).filter(Boolean) : [];
  }

  function describedUniverseSymbols(universe) {
    var seen = {}, rows = activeUniverseSymbols(universe);
    (universe && Array.isArray(universe.sectors) ? universe.sectors : []).forEach(function (sector) {
      (sector && Array.isArray(sector.symbols) ? sector.symbols : []).forEach(function (symbol) {
        rows.push(String(symbol || '').trim().toUpperCase());
      });
    });
    return rows.filter(function (symbol) {
      if (!symbol || seen[symbol]) return false;
      seen[symbol] = true;
      return true;
    });
  }

  function describedSector(universe, raw) {
    var token = String(raw || '').trim().toUpperCase().replace(/[^A-Z0-9]/g, '');
    var aliases = {
      SEMIS: 'SEMICONDUCTORS', CHIPS: 'SEMICONDUCTORS',
      SOFTWARE: 'TECH', TECHNOLOGY: 'TECH',
      HEALTH: 'HEALTHCARE', FINANCE: 'FINANCIALS', BANKS: 'FINANCIALS',
      CONSUMER: 'DISCRETIONARY', MACRO: 'ETFS', INDEX: 'ETFS'
    };
    token = aliases[token] || token;
    return (universe && Array.isArray(universe.sectors) ? universe.sectors : []).find(function (sector) {
      var key = String(sector && sector.key || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
      var label = String(sector && sector.label || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
      return token && (token === key || token === label);
    }) || null;
  }

  function homeBookSymbols(rows, trades, sharePositions, universe) {
    var seen = {};
    var sources = (rows || []).map(function (row) { return row && row.plan || row; })
      .filter(function (plan) {
        var status = String(plan && plan.status || '').toUpperCase();
        return plan && plan.open !== false && plan.assumptionsEditable !== false
          && (status === 'DRAFT' || status === 'ACTIVE');
      })
      .concat(trades || []).concat(sharePositions || []);
    var owned = sources.map(function (source) {
      return String(source && source.symbol || '').trim().toUpperCase();
    });
    var described = describedUniverseSymbols(universe), describedSet = {};
    described.forEach(function (symbol) { describedSet[symbol] = true; });
    var benchmarks = ['SPY', 'IWM', 'TLT', 'GLD', 'DIA', 'QQQ'].filter(function (symbol) {
      return describedSet[symbol];
    });
    var sectorRepresentatives = (universe && Array.isArray(universe.sectors) ? universe.sectors : [])
      .map(function (sector) {
        var symbols = (sector && Array.isArray(sector.symbols) ? sector.symbols : []).map(function (symbol) {
          return String(symbol || '').trim().toUpperCase();
        }).filter(Boolean);
        return symbols.find(function (symbol) {
          return /^(XL[BEFKIPRSTUVY]|SMH|SOXX|ITA|IHI|XBI|KRE)$/.test(symbol);
        }) || symbols[0] || null;
      }).filter(Boolean);
    var active = activeUniverseSymbols(universe);
    /* Home is a market watch, not a duplicate position roster. Keep the current Book represented
       without letting six correlated holdings crowd out rates, commodities, broad markets, and
       cross-sector lenses. The remaining owned names are still present in the Book itself. */
    return owned.slice(0, 2).concat(benchmarks.slice(0, 4), sectorRepresentatives,
      owned.slice(2), active, described).filter(function (symbol) {
        symbol = String(symbol || '').trim().toUpperCase();
        if (!symbol || seen[symbol]) return false;
        seen[symbol] = true;
        return true;
      }).slice(0, 12);
  }

  function publishBookContext(seq, contextSeq, data, context) {
    if (seq !== bookRequestSeq || !state.book || state.book.requestId !== seq
        || contextSeq !== bookContextRequestSeq || state.book.data !== data) return false;
    if (!context.defaultSymbols && data.homeContext
        && Array.isArray(data.homeContext.defaultSymbols)) {
      context.defaultSymbols = data.homeContext.defaultSymbols.slice();
    }
    context = Object.assign({}, context, { requestId: contextSeq });
    data.homeContext = context;
    state.book.data = data;
    notify('book-context', {
      operation: 'book-context', requestId: seq, contextRequestId: contextSeq,
      book: state.book, data: data
    });
    return true;
  }

  function loadBookSymbolContext(symbol, marketSeed) {
    if (bookContextLoads[symbol]) return bookContextLoads[symbol];
    var encoded = encodeURIComponent(symbol);
    var seeded = marketSeed && marketSeed.research && marketSeed.chain
      && String(marketSeed.research.symbol || marketSeed.quote && marketSeed.quote.symbol || '')
        .toUpperCase() === String(symbol).toUpperCase();
    function present(key, path, value) {
      return Promise.resolve({ key: key, path: path, available: true, value: value, error: null });
    }
    var load = Promise.all([
      seeded ? present('research:' + symbol, '/api/research/' + encoded, marketSeed.research)
        : readCachedSlot('research:' + symbol, '/api/research/' + encoded),
      readCachedSlot('news:' + symbol, '/api/research/' + encoded + '/news'),
      // One complete stored artifact owns every chart range; viewport ranges are client-side
      // windows, never distinct provider or API reads.
      readCachedSlot('history:max:' + symbol,
        '/api/research/' + encoded + '/history?range=max'),
      seeded ? present('expirations:' + symbol, '/api/research/' + encoded + '/expirations', {
        symbol: symbol, expirations: marketSeed.expirations || [],
        asOfDate: marketSeed.expirationAsOf || null
      }) : readCachedSlot('expirations:' + symbol,
        '/api/research/' + encoded + '/expirations')
    ]).then(async function (base) {
      var expirationSlot = objectSlot(base[3], symbol + ' option expirations');
      var envelope = expirationSlot && expirationSlot.available ? expirationSlot.value : {};
      var expiration = seeded ? marketSeed.expiration
        : chooseExpiration(envelope.expirations, 30, envelope.asOfDate);
      var chainSlot = seeded && String(marketSeed.expiration || '') === String(expiration)
        ? await present('chain:' + symbol, '/api/research/' + encoded + '/chain', marketSeed.chain)
        : expiration
        ? await readCachedSlot('chain:' + symbol, '/api/research/' + encoded
          + '/chain?expiration=' + encodeURIComponent(expiration))
        : unavailableSlot('chain:' + symbol, '/api/research/' + encoded + '/chain',
          'No current option expiration was available for the focused market pulse.');
      return base.concat([chainSlot]);
    });
    bookContextLoads[symbol] = load;
    // This map coalesces concurrent consumers only. The bounded API cache owns freshness and
    // invalidation after the read settles; retaining a second indefinite cache here would create
    // a competing market-data owner.
    load.then(function () {
      if (bookContextLoads[symbol] === load) delete bookContextLoads[symbol];
    }, function () {
      if (bookContextLoads[symbol] === load) delete bookContextLoads[symbol];
    });
    return load;
  }

  function quoteContextRow(symbol, quote, lane) {
    if (!quote) return { symbol: symbol, research: null, news: null, missing: [] };
    var last = number(quote.last), previousClose = number(quote.prevClose);
    return {
      symbol: symbol,
      research: {
        symbol: symbol,
        marketLane: lane || null,
        // The bounded watch already owns an authoritative Quote receipt. Use its observed last
        // directly (or its explicitly labeled previous close) instead of leaving every non-focused
        // symbol visually blank while the richer Research document remains intentionally unfetched.
        displayPrice: last != null ? last : previousClose,
        priceIsPreviousClose: last == null && previousClose != null,
        markBasis: last != null ? 'LAST' : previousClose != null ? 'PREVIOUS_CLOSE' : null,
        freshness: quote.freshness || null,
        quote: quote,
        evidence: { inputs: { quote: quote.evidence || null } }
      },
      news: null,
      missing: []
    };
  }

  async function hydrateBookFocusedContext(seq, contextSeq, before, data, symbol) {
    try {
      var group = await loadBookSymbolContext(symbol);
      if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return;
      var after = await readIdentitySnapshot();
      if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return;
      assertSameReadIdentity(before, after);
      var researchSlot = objectSlot(group[0], symbol + ' Research');
      var newsSlot = objectSlot(group[1], symbol + ' News');
      var historySlot = objectSlot(group[2], symbol + ' observed history');
      var expirationsSlot = objectSlot(group[3], symbol + ' option expirations');
      var chainSlot = objectSlot(group[4], symbol + ' option chain');
      try {
        if (researchSlot.available) {
          assertDocumentSymbol(researchSlot.value, symbol, 'Home Research');
          if (researchSlot.value.marketLane
              && String(researchSlot.value.marketLane).toUpperCase()
                !== String(before.identity.marketLane).toUpperCase()) {
            throw new Error(symbol + ' Research belongs to another market lane.');
          }
          assertEvidenceLane(researchSlot.value.quote && researchSlot.value.quote.evidence
            || researchSlot.value.evidence && researchSlot.value.evidence.inputs
              && researchSlot.value.evidence.inputs.quote,
          before.identity.marketLane, 'Home Research quote');
        }
        if (newsSlot.available) assertDocumentSymbol(newsSlot.value, symbol, 'Home News');
        if (historySlot.available) assertDocumentSymbol(historySlot.value, symbol, 'Home History');
        if (chainSlot.available) {
          assertDocumentSymbol({ symbol: chainSlot.value.underlying }, symbol, 'Home option chain');
          assertEvidenceLane(chainSlot.value.evidence, before.identity.marketLane, 'Home option chain');
        }
      } catch (error) {
        researchSlot = unavailableSlot('research:' + symbol, researchSlot.path, error.message);
        newsSlot = unavailableSlot('news:' + symbol, newsSlot.path, error.message);
        historySlot = unavailableSlot('history:' + symbol, historySlot.path, error.message);
        expirationsSlot = unavailableSlot('expirations:' + symbol, expirationsSlot.path, error.message);
        chainSlot = unavailableSlot('chain:' + symbol, chainSlot.path, error.message);
      }
      var current = data.homeContext || {}, rows = (current.rows || []).slice();
      var index = rows.findIndex(function (row) { return row.symbol === symbol; });
      var prior = index >= 0 ? rows[index] : { symbol: symbol };
      var row = {
        symbol: symbol,
        research: researchSlot.available ? researchSlot.value : prior.research || null,
        news: newsSlot.available ? newsSlot.value : null,
        history: historySlot.available ? historySlot.value : null,
        expirations: expirationsSlot.available ? expirationsSlot.value : null,
        chain: chainSlot.available ? chainSlot.value : null,
        missing: missingSlots([researchSlot, newsSlot, historySlot, expirationsSlot, chainSlot])
      };
      if (index >= 0) rows[index] = row; else rows.push(row);
      publishBookContext(seq, contextSeq, data, {
        phase: 'ready', symbols: current.symbols || [symbol], rows: rows,
        detailSymbol: symbol, detailLoading: null,
        sectorLens: current.sectorLens || null,
        missing: rows.reduce(function (all, item) { return all.concat(item.missing || []); }, [])
      });
    } catch (error) {
      var fallback = data.homeContext || {};
      publishBookContext(seq, contextSeq, data, {
        phase: 'ready', symbols: fallback.symbols || [symbol], rows: fallback.rows || [],
        detailSymbol: symbol, detailLoading: null,
        sectorLens: fallback.sectorLens || null,
        missing: (fallback.missing || []).concat([{ key: 'context:' + symbol, error: errorReceipt(error) }])
      });
    }
  }

  /**
   * Market and headline context is useful on Home, but it must not hold the position roster or
   * account summary behind provider latency. Hydrate the bounded set of Plan symbols after the
   * core Book receipt has rendered, then publish one same-world additive update.
   */
  function homeDetailSymbol(symbols) {
    /* The default detailed subject is the broad market, never whichever owned
       position happens to sort first. A deliberate focus bypasses this picker. */
    var prefs = ['SPY', 'QQQ', 'DIA', 'IWM'];
    for (var i = 0; i < prefs.length; i++) if (symbols.indexOf(prefs[i]) >= 0) return prefs[i];
    return symbols[0];
  }

  async function hydrateBookContext(seq, contextSeq, before, data, symbols) {
    if (!symbols.length) return;
    var prior = data.homeContext && data.homeContext.priorDetailSymbol;
    var detail = prior && symbols.indexOf(prior) >= 0 ? prior : homeDetailSymbol(symbols);
    try {
      var quotesPath = '/api/quotes?symbols=' + encodeURIComponent(symbols.join(','));
      var quoteSlot = objectSlot(await readCachedSlot('quotes', quotesPath), 'The ambient market watch');
      if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return;
      var after = await readIdentitySnapshot();
      if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return;
      assertSameReadIdentity(before, after);
      var quoteEnvelope = quoteSlot.available ? quoteSlot.value : {}, quoteRows = quoteEnvelope.quotes;
      if (!Array.isArray(quoteRows)) quoteRows = [];
      if (quoteEnvelope.marketLane
          && String(quoteEnvelope.marketLane).toUpperCase()
            !== String(before.identity.marketLane).toUpperCase()) {
        throw new Error('The ambient market watch belongs to another market lane.');
      }
      var rows = symbols.map(function (symbol) {
        var quote = quoteRows.find(function (item) {
          return String(item && item.symbol || '').toUpperCase() === symbol;
        });
        return quoteContextRow(symbol, quote, before.identity.marketLane);
      });
      publishBookContext(seq, contextSeq, data, {
        phase: 'loading', symbols: symbols, rows: rows,
        detailSymbol: detail, detailLoading: detail,
        sectorLens: data.homeContext && data.homeContext.sectorLens || null,
        missing: quoteSlot.available ? [] : missingSlots([quoteSlot])
      });
      // The full option/research document is fetched for one focused symbol only. Its response
      // remains in the shared API cache for New Idea, while the bounded watch uses cheap quotes.
      var api = requireApi(), encoded = encodeURIComponent(detail);
      if (api.prefetch) api.prefetch('/api/research/' + encoded + '/expirations');
      hydrateBookFocusedContext(seq, contextSeq, before, data, detail);
    } catch (error) {
      publishBookContext(seq, contextSeq, data, {
        phase: 'error', symbols: symbols, rows: [],
        sectorLens: data.homeContext && data.homeContext.sectorLens || null,
        missing: [{ key: 'homeContext', error: errorReceipt(error) }]
      });
    }
  }

  var strategyCatalogInFlight = null;
  function requestStrategyCatalog() {
    /* The catalog is additive everywhere: one shared in-flight read serves Home's strategy
       line and the Decide rail, and a transient failure never blanks a financial flow. */
    if (state.strategyCatalog) return Promise.resolve(state.strategyCatalog);
    if (strategyCatalogInFlight) return strategyCatalogInFlight;
    strategyCatalogInFlight = optionalFresh('/api/strategies').then(function (strategyCatalog) {
      if (strategyCatalog && Array.isArray(strategyCatalog.catalog)) {
        state.strategyCatalog = strategyCatalog;
        state.strategyCatalogError = null;
        if (state.book && state.book.data) state.book.data.strategyCatalog = strategyCatalog;
      }
      return strategyCatalog;
    }).catch(function (error) {
      state.strategyCatalogError = error && error.message
        ? String(error.message) : 'The StrategyCatalog receipt is unavailable.';
      return null;
    });
    strategyCatalogInFlight.then(function () { strategyCatalogInFlight = null; },
      function () { strategyCatalogInFlight = null; });
    return strategyCatalogInFlight;
  }

  async function focusBookSymbol(rawSymbol) {
    var symbol = String(rawSymbol || '').trim().toUpperCase();
    var book = state.book, data = book && book.data, context = data && data.homeContext;
    if (!symbol || !book || !data || !context
        || describedUniverseSymbols(data.universe).indexOf(symbol) < 0) return null;
    var seq = book.requestId, contextSeq = ++bookContextRequestSeq;
    var symbols = (context.symbols || []).slice();
    if (symbols.indexOf(symbol) < 0) symbols = [symbol].concat(symbols).slice(0, 4);
    publishBookContext(seq, contextSeq, data, Object.assign({}, context, {
      phase: 'loading', symbols: symbols, detailSymbol: symbol, detailLoading: symbol,
      sectorLens: null
    }));
    var before = await readIdentitySnapshot();
    if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return null;
    var api = requireApi(), encoded = encodeURIComponent(symbol);
    if (api.prefetch) api.prefetch('/api/research/' + encoded + '/expirations');
    await hydrateBookFocusedContext(seq, contextSeq, before, data, symbol);
    return contextSeq === bookContextRequestSeq ? data.homeContext : null;
  }

  async function focusBookSector(rawSector) {
    var book = state.book, data = book && book.data, context = data && data.homeContext;
    if (!book || !data || !context) return null;
    var seq = book.requestId, requested = String(rawSector || '').trim();
    var contextSeq = ++bookContextRequestSeq;
    if (!requested) {
      var defaults = (context.defaultSymbols || context.symbols || []).slice(0, 12);
      var defaultDetail = defaults.length ? homeDetailSymbol(defaults) : null;
      publishBookContext(seq, contextSeq, data, Object.assign({}, context, {
        phase: defaults.length ? 'loading' : 'ready', symbols: defaults, rows: [],
        detailSymbol: defaultDetail, detailLoading: defaultDetail,
        sectorLens: null
      }));
      if (!defaults.length) return data.homeContext;
      var defaultBefore = await readIdentitySnapshot();
      if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return null;
      await hydrateBookContext(seq, contextSeq, defaultBefore, data, defaults);
      return contextSeq === bookContextRequestSeq ? data.homeContext : null;
    }
    var sector = describedSector(data.universe, requested);
    if (!sector) {
      publishBookContext(seq, contextSeq, data, Object.assign({}, context, {
        phase: 'ready', detailLoading: null,
        sectorLens: {
          available: false, requested: requested,
          message: 'That sector is not present in the backend-owned universe catalog.'
        }
      }));
      return data.homeContext;
    }
    var symbols = (sector.symbols || []).map(function (symbol) {
      return String(symbol || '').trim().toUpperCase();
    }).filter(Boolean).slice(0, 4);
    publishBookContext(seq, contextSeq, data, Object.assign({}, context, {
      phase: symbols.length ? 'loading' : 'ready', symbols: symbols,
      rows: (context.rows || []).filter(function (row) { return symbols.indexOf(row.symbol) >= 0; }),
      detailSymbol: symbols[0] || null, detailLoading: symbols[0] || null,
      sectorLens: {
        available: symbols.length > 0, key: sector.key, label: sector.label,
        symbols: (sector.symbols || []).slice(),
        message: symbols.length ? null : 'The backend sector exists but has no symbols.'
      }
    }));
    if (!symbols.length) return data.homeContext;
    var before = await readIdentitySnapshot();
    if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return null;
    await hydrateBookContext(seq, contextSeq, before, data, symbols);
    return contextSeq === bookContextRequestSeq ? data.homeContext : null;
  }

  async function readActiveTradeRoster() {
    var path = '/api/trades?status=ACTIVE&page=0&size=100';
    var first = objectSlot(await readSlot('activeTrades', path), 'The active-trade roster');
    if (!first.available) return first;
    var page = first.value;
    if (!Array.isArray(page.trades)) {
      return unavailableSlot('activeTrades', path,
        'The active-trade roster did not return its typed trades array.');
    }
    var pageSize = Math.max(1, Number(page.size || 100));
    var total = Math.max(page.trades.length, Number(page.total || page.trades.length));
    var pageCount = Math.ceil(total / pageSize);
    if (pageCount <= 1) return first;
    var requests = [];
    for (var index = 1; index < pageCount; index++) {
      requests.push(readSlot('activeTradesPage' + index,
        '/api/trades?status=ACTIVE&page=' + index + '&size=' + pageSize));
    }
    var rest = await Promise.all(requests);
    var rows = page.trades.slice();
    for (var i = 0; i < rest.length; i++) {
      var slot = objectSlot(rest[i], 'Active-trade page ' + (i + 1));
      if (!slot.available || !Array.isArray(slot.value.trades)) {
        return unavailableSlot('activeTrades', path,
          'The complete active-trade roster could not be read without omitting positions.');
      }
      rows = rows.concat(slot.value.trades);
    }
    if (rows.length < total) {
      return unavailableSlot('activeTrades', path,
        'The active-trade roster ended before its declared total was reached.');
    }
    return {
      key: 'activeTrades', path: path, available: true,
      value: { trades: rows.slice(0, total), total: total, page: 0, size: rows.length }, error: null
    };
  }

  /**
   * Read the current Practice Book without borrowing the New Idea request sequence. Empty arrays
   * are authoritative empty states. An unavailable auxiliary risk lens is retained as a named
   * missing receipt; it is never replaced with zeroes or fixture positions.
   */
  async function loadBook() {
    if (!state.enabled) return null;
    var seq = ++bookRequestSeq;
    var contextSeq = ++bookContextRequestSeq;
    bookContextLoads = {};
    bookPositionDetailLoads = {};
    state.book = {
      phase: 'loading', requestId: seq, identity: null, data: null, missing: [], error: null
    };
    notify('book-loading', { operation: 'book', requestId: seq, book: state.book });
    try {
      var before = await readIdentitySnapshot();
      if (seq !== bookRequestSeq) return null;
      var slots = await Promise.all([
        readActiveTradeRoster(),
        readSlot('sharePositions', '/api/positions'),
        readSlot('summary', '/api/portfolio/summary'),
        readSlot('heat', '/api/portfolio/heat'),
        readSlot('greeks', '/api/portfolio/greeks'),
        readSlot('bookRisk', '/api/portfolio/book-risk'),
        readSlot('planPortfolio', '/api/plans/portfolio'),
        readSlot('universe', '/api/universe')
      ]);
      if (seq !== bookRequestSeq) return null;
      var after = await readIdentitySnapshot();
      if (seq !== bookRequestSeq) return null;
      assertSameReadIdentity(before, after);

      var bookLabels = {
        activeTrades: 'The active-trade roster',
        sharePositions: 'The share-position roster',
        summary: 'The portfolio summary',
        heat: 'The portfolio heat receipt',
        greeks: 'The portfolio Greeks receipt',
        bookRisk: 'The Book risk receipt',
        planPortfolio: 'The Plan portfolio',
        universe: 'The active market universe'
      };
      slots = slots.map(function (slot) { return objectSlot(slot, bookLabels[slot.key]); });
      var values = slotsByKey(slots);
      var tradePage = requireSlot(values.activeTrades, 'The active-trade roster');
      var positionBook = requireSlot(values.sharePositions, 'The share-position roster');
      if (!tradePage || !Array.isArray(tradePage.trades)) {
        throw new Error('The active-trade roster did not return its typed trades array.');
      }
      if (!positionBook || !Array.isArray(positionBook.positions)) {
        throw new Error('The share-position roster did not return its typed positions array.');
      }

      if (values.planPortfolio.available) assertPlanPortfolioIdentity(values.planPortfolio.value, before.identity);
      var planRows = values.planPortfolio.available && values.planPortfolio.value
        && Array.isArray(values.planPortfolio.value.plans) ? values.planPortfolio.value.plans : null;
      if (values.planPortfolio.available && planRows == null) {
        values.planPortfolio = unavailableSlot('planPortfolio', '/api/plans/portfolio',
          'The Plan portfolio did not return its typed plans array.');
        slots = slots.map(function (slot) {
          return slot.key === 'planPortfolio' ? values.planPortfolio : slot;
        });
      }
      planRows = values.planPortfolio.available ? planRows : null;
      var accountPlans = planRows == null ? null : planRows.filter(function (row) {
        var plan = row && row.plan;
        return plan && plan.accountId != null
          && String(plan.accountId) === String(before.identity.accountId);
      });
      var missing = missingSlots(slots);
      var homeSymbols = homeBookSymbols(accountPlans || [], tradePage.trades, positionBook.positions,
        values.universe.available ? values.universe.value : null);
      var data = {
        identity: before.identity,
        account: before.account,
        activeTrades: tradePage.trades,
        sharePositions: positionBook.positions,
        portfolio: {
          summary: values.summary.available ? values.summary.value : null,
          heat: values.heat.available ? values.heat.value : null,
          greeks: values.greeks.available ? values.greeks.value : null,
          bookRisk: values.bookRisk.available ? values.bookRisk.value : null
        },
        planPortfolio: values.planPortfolio.available ? values.planPortfolio.value : null,
        plans: planRows,
        accountPlans: accountPlans,
        universe: values.universe.available ? values.universe.value : null,
        strategyCatalog: state.strategyCatalog,
        positionAnalyses: {},
        positionDetails: {},
        lifecycle: { phase: tradePage.trades.length ? 'loading' : 'empty',
          available: 0, unavailable: 0 },
        recentTradeId: recentCommittedTradeId && tradePage.trades.some(function (trade) {
          return String(trade && trade.id) === String(recentCommittedTradeId);
        }) ? recentCommittedTradeId : null,
        homeContext: {
          phase: homeSymbols.length ? 'loading' : 'empty',
          requestId: contextSeq, symbols: homeSymbols, defaultSymbols: homeSymbols.slice(),
          /* a reload keeps hydrating the subject the user was reading, when it survives;
             a fresh session has no prior detail and the benchmark picker decides below */
          priorDetailSymbol: (function () {
            var prior = state.book && state.book.data && state.book.data.homeContext;
            var symbol = prior && prior.detailSymbol;
            return symbol && homeSymbols.indexOf(symbol) >= 0 ? symbol : null;
          })(),
          rows: [], missing: []
        },
        loadedAt: new Date().toISOString()
      };
      var phase = missing.length ? 'partial' : 'ready';
      state.book = {
        phase: phase, requestId: seq, identity: before.identity,
        data: data, missing: missing, error: null
      };
      notify('book-' + phase, {
        operation: 'book', requestId: seq, book: state.book, data: data
      });
      // Do not await optional market/news reads: positions and Book actions remain interactive
      // while a cold observed provider or source cache is warming.
      hydrateBookContext(seq, contextSeq, before, data, data.homeContext.symbols);
      hydrateBookLifecycle(seq, before, data, tradePage.trades);
      requestStrategyCatalog().then(function (strategyCatalog) {
        if (seq !== bookRequestSeq || !strategyCatalog) return;
        notify('strategy-catalog', {
          operation: 'strategy-catalog', strategyCatalog: state.strategyCatalog
        });
      });
      return data;
    } catch (error) {
      if (seq !== bookRequestSeq) return null;
      state.book = {
        phase: 'error', requestId: seq, identity: before && before.identity || null, data: null,
        missing: [], error: errorReceipt(error)
      };
      notify('book-error', {
        operation: 'book', requestId: seq, book: state.book, error: error
      });
      throw error;
    }
  }

  /**
   * Enrich the already-published Book with the canonical lifecycle receipt carried by each
   * existing trade-detail response. Three bounded readers avoid a request burst; failures stay
   * explicit per position and never block the roster, market context, or Book risk.
   */
  async function hydrateBookLifecycle(bookSeq, identitySnapshot, data, trades) {
    var rows = Array.isArray(trades) ? trades.slice() : [];
    if (!rows.length || bookSeq !== bookRequestSeq) return;
    var cursor = 0, analyses = {}, details = {}, unavailable = {};
    async function worker() {
      while (bookSeq === bookRequestSeq) {
        var index = cursor++;
        if (index >= rows.length) return;
        var trade = rows[index] || {}, id = trade.id == null ? '' : String(trade.id);
        if (!id) continue;
        var slot = await readBookPositionDetailSlot(id, false);
        if (bookSeq !== bookRequestSeq) return;
        if (!slot.available || !slot.value || !slot.value.trade
            || String(slot.value.trade.id) !== id) {
          unavailable[id] = slot.error && slot.error.message
            || 'The current position detail is unavailable.';
          continue;
        }
        details[id] = slot.value;
        if (!slot.value.analysis) {
          unavailable[id] = 'The current lifecycle receipt is unavailable.';
          continue;
        }
        analyses[id] = slot.value.analysis;
      }
    }
    await Promise.all(Array.from({ length: Math.min(3, rows.length) }, worker));
    if (bookSeq !== bookRequestSeq) return;
    try {
      var after = await readIdentitySnapshot();
      if (bookSeq !== bookRequestSeq) return;
      assertSameReadIdentity(identitySnapshot, after);
    } catch (error) {
      if (bookSeq !== bookRequestSeq) return;
      data.lifecycle = { phase: 'unavailable', available: 0,
        unavailable: rows.length, reason: errorReceipt(error).message };
      notify('book-lifecycle', { operation: 'book-lifecycle', requestId: bookSeq,
        book: state.book, data: data });
      return;
    }
    data.positionAnalyses = analyses;
    data.positionDetails = details;
    data.positionAnalysisErrors = unavailable;
    data.lifecycle = { phase: Object.keys(unavailable).length ? 'partial' : 'ready',
      available: Object.keys(analyses).length, unavailable: Object.keys(unavailable).length };
    if (state.book && state.book.requestId === bookSeq) {
      state.book.data = data;
    }
    notify('book-lifecycle', { operation: 'book-lifecycle', requestId: bookSeq,
      book: state.book, data: data });
  }

  function tradeHintFromBook(tradeId) {
    var rows = state.book && state.book.data && state.book.data.activeTrades;
    if (!Array.isArray(rows)) return null;
    return rows.find(function (row) { return row && String(row.id) === String(tradeId); }) || null;
  }

  function planHintFromBook(tradeId, explicitPlanId) {
    var rows = state.book && state.book.data
      && (state.book.data.accountPlans || state.book.data.plans);
    if (!Array.isArray(rows)) return null;
    return rows.find(function (row) {
      if (!row || !row.plan) return false;
      if (explicitPlanId) return String(row.plan.id) === String(explicitPlanId);
      return row.tradeId != null && String(row.tradeId) === String(tradeId);
    }) || null;
  }

  function positionDescriptor(tradeOrId, options) {
    var input = tradeOrId && typeof tradeOrId === 'object' ? tradeOrId : {};
    var hintedTrade = input.trade && typeof input.trade === 'object' ? input.trade : input;
    options = Object.assign({}, input.options || {}, options || {});
    var id = typeof tradeOrId === 'string' || typeof tradeOrId === 'number'
      ? tradeOrId : hintedTrade.id || options.tradeId;
    id = id == null ? '' : String(id).trim();
    if (!id) throw new Error('Choose an authoritative trade before loading Position Bloom.');
    var bookTrade = tradeHintFromBook(id);
    var symbol = String(options.symbol || hintedTrade.symbol || bookTrade && bookTrade.symbol || '')
      .trim().toUpperCase();
    var planHint = planHintFromBook(id, options.planId);
    var planId = options.planId || planHint && planHint.plan && planHint.plan.id || null;
    var range = String(options.historyRange || '6m').toLowerCase();
    if (['1m', '3m', '6m', 'ytd', '1y', '2y', '5y', 'max'].indexOf(range) < 0) range = '6m';
    return {
      id: id,
      symbol: symbol,
      planId: planId == null ? null : String(planId),
      historyRange: range,
      planHint: planHint
    };
  }

  function positionAuxiliarySlots(descriptor, symbol) {
    var marketContext = bookContextLoads[symbol] || loadBookSymbolContext(symbol);
    var requests = [];
    if (descriptor.planId) {
      requests.push(readSlot('planWorkspace', '/api/plans/'
        + encodeURIComponent(descriptor.planId) + '/manage'));
      requests.push(readSlot('positionEnsemble', '/api/plans/'
        + encodeURIComponent(descriptor.planId) + '/outcomes/ensemble/latest'));
    }
    return Promise.all([marketContext, Promise.all(requests)]).then(function (groups) {
      var market = groups[0], rest = groups[1];
      return [
        Object.assign({}, market[0], { key: 'research' }),
        Object.assign({}, market[2], { key: 'history' }),
        Object.assign({}, market[1], { key: 'news' })
      ].concat(rest);
    });
  }

  function assertPlanWorkspaceIdentity(workspace, descriptor, identity, tradeId) {
    if (!workspace || !workspace.plan) {
      throw new Error('The linked Plan workspace omitted its Plan identity.');
    }
    var plan = workspace.plan;
    if (descriptor.planId && String(plan.id) !== String(descriptor.planId)) {
      throw new Error('The linked Plan workspace belongs to another Plan.');
    }
    if (plan.accountId != null && String(plan.accountId) !== String(identity.accountId)) {
      throw new Error('The linked Plan belongs to another account.');
    }
    var expected = expectedMarketKind(identity);
    if (plan.marketKind && String(plan.marketKind).toUpperCase() !== String(expected).toUpperCase()) {
      throw new Error('The linked Plan belongs to another market lane.');
    }
    if (expected === 'SIMULATED' && plan.worldId
        && String(plan.worldId) !== String(identity.world)) {
      throw new Error('The linked Plan belongs to another simulated market world.');
    }
    if (workspace.trade && workspace.trade.trade && workspace.trade.trade.id
        && String(workspace.trade.trade.id) !== String(tradeId)) {
      throw new Error('The linked Plan workspace points at another active trade.');
    }
  }

  function assertPositionEnsembleIdentity(envelope, descriptor, identity, linkedPlan) {
    if (!envelope || !envelope.plan || !envelope.ensemble) {
      throw new Error('The stored Position ensemble omitted its Plan or ensemble identity.');
    }
    var plan = envelope.plan;
    var ensemble = envelope.ensemble;
    var receipt = envelope.preview && envelope.preview.receipt || {};
    if (!descriptor.planId || String(plan.id || '') !== String(descriptor.planId)) {
      throw new Error('The stored Position ensemble belongs to another Plan.');
    }
    if (!ensemble.id || !ensemble.fingerprint) {
      throw new Error('The stored Position ensemble omitted its immutable id or fingerprint.');
    }
    if (receipt.ensembleId && String(receipt.ensembleId) !== String(ensemble.id)) {
      throw new Error('The stored Position ensemble receipt names another ensemble id.');
    }
    if ((receipt.ensembleFingerprint || receipt.fingerprint)
        && String(receipt.ensembleFingerprint || receipt.fingerprint) !== String(ensemble.fingerprint)) {
      throw new Error('The stored Position ensemble receipt names another ensemble fingerprint.');
    }
    if (plan.accountId != null && String(plan.accountId) !== String(identity.accountId)) {
      throw new Error('The stored Position ensemble belongs to another account.');
    }
    var expected = expectedMarketKind(identity);
    if (plan.marketKind && String(plan.marketKind).toUpperCase() !== String(expected).toUpperCase()) {
      throw new Error('The stored Position ensemble belongs to another market lane.');
    }
    if (expected === 'SIMULATED' && plan.worldId
        && String(plan.worldId) !== String(identity.world)) {
      throw new Error('The stored Position ensemble belongs to another simulated market world.');
    }
    if (receipt.symbol && String(receipt.symbol).toUpperCase() !== String(descriptor.symbol).toUpperCase()) {
      throw new Error('The stored Position ensemble belongs to another underlying.');
    }
    if (receipt.worldId && identity.world
        && String(receipt.worldId) !== String(identity.world)) {
      throw new Error('The stored Position ensemble belongs to another market world.');
    }
    if (receipt.datasetId != null && identity.datasetId != null
        && String(receipt.datasetId) !== String(identity.datasetId)) {
      throw new Error('The stored Position ensemble belongs to another analysis dataset.');
    }
    var contextRev = linkedPlan && linkedPlan.context && linkedPlan.context.rev;
    if (receipt.contextRev != null && contextRev != null
        && Number(receipt.contextRev) !== Number(contextRev)) {
      throw new Error('The stored Position ensemble belongs to another Plan context revision.');
    }
  }

  /**
   * One position's UNCONDITIONED stored-fan valuation for the book-level overlay: the owning
   * Plan's latest stored ensemble valued for that exact held package (focusPositionKey), with no
   * scenario waypoints. Read-only against stored artifacts. Each position stays an independent
   * projection — callers overlay these fans and never sum them.
   */
  async function positionFutures(options) {
    if (!state.enabled) return null;
    var planId = options && options.planId != null ? String(options.planId).trim() : '';
    var tradeId = options && options.tradeId != null ? String(options.tradeId).trim() : '';
    if (!planId || !tradeId) throw new Error('Book futures need the owning Plan and trade identity.');
    var limit = options && options.limit == null ? 1 : number(options.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 60) {
      throw new Error('Book futures path limit must be a whole number from 1 through 60.');
    }
    var response = await requireApi().post('/api/plans/' + encodeURIComponent(planId)
      + '/outcomes/ensemble/paths', { limit: limit, focusPositionKey: tradeId });
    var receipt = response && response.receipt || {};
    if (String(receipt.focusPositionKey || '') !== tradeId) {
      throw new Error('The stored fan response names another focused position.');
    }
    var rows = response && response.checkpoints && response.checkpoints.positions;
    var focused = Array.isArray(rows) && rows.find(function (row) {
      return row && String(row.key || '') === tradeId;
    });
    if (!focused) throw new Error('The stored fan response omitted the focused position row.');
    return response;
  }

  /**
   * One symbol's canonical market context (research, news, history, expirations, chain) through
   * the same cached slot reads Home uses, so the Decide Market lens never grows a second data path.
   */
  async function symbolContext(symbol) {
    if (!state.enabled) return null;
    symbol = String(symbol || '').trim().toUpperCase();
    if (!symbol) throw new Error('Choose a symbol before loading its market context.');
    var marketSeed = state.market && state.market.research
      && String(state.market.research.symbol || '').toUpperCase() === symbol
      ? state.market : null;
    var group = await loadBookSymbolContext(symbol, marketSeed);
    var researchSlot = objectSlot(group[0], symbol + ' Research');
    var newsSlot = objectSlot(group[1], symbol + ' News');
    var historySlot = objectSlot(group[2], symbol + ' observed history');
    var expirationsSlot = objectSlot(group[3], symbol + ' option expirations');
    var chainSlot = objectSlot(group[4], symbol + ' option chain');
    if (researchSlot.available) assertDocumentSymbol(researchSlot.value, symbol, 'Market lens Research');
    if (newsSlot.available) assertDocumentSymbol(newsSlot.value, symbol, 'Market lens News');
    if (historySlot.available) assertDocumentSymbol(historySlot.value, symbol, 'Market lens History');
    if (chainSlot.available) {
      assertDocumentSymbol({ symbol: chainSlot.value.underlying }, symbol, 'Market lens option chain');
    }
    return {
      symbol: symbol,
      research: researchSlot.available ? researchSlot.value : null,
      news: newsSlot.available ? newsSlot.value : null,
      history: historySlot.available ? historySlot.value : null,
      expirations: expirationsSlot.available ? expirationsSlot.value : null,
      chain: chainSlot.available ? chainSlot.value : null,
      missing: missingSlots([researchSlot, newsSlot, historySlot, expirationsSlot, chainSlot])
    };
  }

  /**
   * The COMPLETE stored daily history for one symbol (range=max), read once and cached. Stored
   * ranges are zero-upstream-call reads under the politeness engine, so every chart range is a
   * client-side window over this one artifact — no per-range refetching, no provider spend.
   */
  async function symbolHistory(symbol) {
    if (!state.enabled) return null;
    symbol = String(symbol || '').trim().toUpperCase();
    if (!symbol) throw new Error('Choose a symbol before loading its stored history.');
    var slot = await readCachedSlot('history:max:' + symbol,
      '/api/research/' + encodeURIComponent(symbol) + '/history?range=max');
    var doc = objectSlot(slot, symbol + ' stored history');
    if (!doc.available) throw new Error(doc.reason || 'Stored history is unavailable for ' + symbol + '.');
    assertDocumentSymbol(doc.value, symbol, 'Stored history');
    return doc.value;
  }

  /**
   * Load one server-owned position receipt plus its same-world Research context. Supplying the
   * trade row (or options.symbol) lets detail, Research, history, news, and Plan/manage read in
   * parallel; a bare id first discovers its server-owned symbol, then performs the same reads.
   */
  async function loadPosition(tradeOrId, options) {
    if (!state.enabled) return null;
    var seq = ++positionRequestSeq;
    // A Position change invalidates only its own focused projection. It does not borrow or
    // advance the Book, New Idea, or New Idea animation request sequences.
    positionScenarioRequestSeq++;
    state.positionScenario = null;
    state.position = {
      phase: 'loading', requestId: seq, identity: null, data: null, missing: [], error: null
    };
    notify('position-loading', { operation: 'position', requestId: seq, position: state.position });
    try {
      var descriptor = positionDescriptor(tradeOrId, options);
      var before = await readIdentitySnapshot();
      if (seq !== positionRequestSeq) return null;

      var auxiliaryPromise = descriptor.symbol
        ? positionAuxiliarySlots(descriptor, descriptor.symbol) : null;
      var detailSlot = await readBookPositionDetailSlot(descriptor.id,
        options && options.forceFresh === true);
      if (seq !== positionRequestSeq) return null;
      var detail = requireSlot(detailSlot, 'The position detail');
      if (!detail || !detail.trade || String(detail.trade.id) !== descriptor.id) {
        throw new Error('The position detail did not return the requested authoritative trade.');
      }
      var symbol = String(detail.trade.symbol || '').trim().toUpperCase();
      if (!symbol) throw new Error('The authoritative trade omitted its underlying symbol.');
      if (descriptor.symbol && descriptor.symbol !== symbol) {
        throw new Error('The requested position symbol does not match the authoritative trade.');
      }
      descriptor.symbol = symbol;
      var hintedPlan = descriptor.planHint && descriptor.planHint.plan || null;
      // Mark, payoff, and exact legs are the structural Position receipt. Publish them as soon as
      // they are available; a cold Research provider or absent daily-history store is decoration
      // and must not hold the Bloom behind an indefinite skeleton.
      var coreData = {
        identity: before.identity,
        account: before.account,
        trade: detail.trade,
        tradeDetail: detail,
        research: null,
        history: null,
        news: null,
        plan: hintedPlan,
        management: null,
        planWorkspace: null,
        positionEnsemble: null,
        auxiliaryPending: true,
        missing: [],
        loadedAt: new Date().toISOString()
      };
      state.position = {
        phase: 'partial', requestId: seq, identity: before.identity,
        data: coreData, missing: [], error: null
      };
      notify('position-partial', {
        operation: 'position-core', requestId: seq, position: state.position, data: coreData
      });

      var auxiliary = await (auxiliaryPromise || positionAuxiliarySlots(descriptor, symbol));
      if (seq !== positionRequestSeq) return null;
      var after = await readIdentitySnapshot();
      if (seq !== positionRequestSeq) return null;
      assertSameReadIdentity(before, after);

      var positionLabels = {
        research: 'Research', history: 'History', news: 'News',
        planWorkspace: 'The linked Plan workspace',
        positionEnsemble: 'The stored Position outcome ensemble'
      };
      auxiliary = auxiliary.map(function (slot) { return objectSlot(slot, positionLabels[slot.key]); });
      var values = slotsByKey(auxiliary);
      values.research = optionalValidatedSlot(values.research, 'Research', function (research) {
        assertDocumentSymbol(research, symbol, 'Research');
        if (research.marketLane && String(research.marketLane).toUpperCase()
            !== String(before.identity.marketLane).toUpperCase()) {
          throw new Error('Research belongs to another market lane.');
        }
        assertEvidenceLane(research.quote && research.quote.evidence,
          before.identity.marketLane, 'Research quote');
      });
      values.history = optionalValidatedSlot(values.history, 'History', function (history) {
        assertDocumentSymbol(history, symbol, 'History');
        if (missingEvidence(history.evidence)) {
          throw new Error('Observed daily history is not stored for ' + symbol
            + '. Open Data Sources to acquire eligible bars.');
        }
        assertEvidenceLane(history.evidence, before.identity.marketLane, 'History');
      });
      values.news = optionalValidatedSlot(values.news, 'News', function (news) {
        assertDocumentSymbol(news, symbol, 'News');
      });
      if (values.planWorkspace) {
        values.planWorkspace = optionalValidatedSlot(values.planWorkspace,
          'The linked Plan workspace', function (workspace) {
            assertPlanWorkspaceIdentity(workspace, descriptor, before.identity, descriptor.id);
          });
      }

      var workspace = values.planWorkspace && values.planWorkspace.available
        ? values.planWorkspace.value : null;
      var linkedPlan = workspace ? workspace.plan : descriptor.planHint && descriptor.planHint.plan || null;
      if (values.positionEnsemble) {
        values.positionEnsemble = optionalValidatedSlot(values.positionEnsemble,
          'The stored Position outcome ensemble', function (positionEnsemble) {
            assertPositionEnsembleIdentity(positionEnsemble, descriptor, before.identity, linkedPlan);
          });
      }
      auxiliary = auxiliary.map(function (slot) { return values[slot.key] || slot; });
      var missing = missingSlots(auxiliary);
      var positionEnsemble = values.positionEnsemble && values.positionEnsemble.available
        ? values.positionEnsemble.value : null;
      var data = {
        identity: before.identity,
        account: before.account,
        trade: detail.trade,
        tradeDetail: detail,
        research: values.research.available ? values.research.value : null,
        history: values.history.available ? values.history.value : null,
        news: values.news.available ? values.news.value : null,
        plan: linkedPlan,
        management: workspace ? workspace.management : null,
        planWorkspace: workspace,
        positionEnsemble: positionEnsemble,
        auxiliaryPending: false,
        missing: missing,
        loadedAt: new Date().toISOString()
      };
      var phase = missing.length ? 'partial' : 'ready';
      state.position = {
        phase: phase, requestId: seq, identity: before.identity,
        data: data, missing: missing, error: null
      };
      notify('position-' + phase, {
        operation: 'position', requestId: seq, position: state.position, data: data
      });
      return data;
    } catch (error) {
      if (seq !== positionRequestSeq) return null;
      state.position = {
        phase: 'error', requestId: seq, identity: before && before.identity || null, data: null,
        missing: [], error: errorReceipt(error)
      };
      notify('position-error', {
        operation: 'position', requestId: seq, position: state.position, error: error
      });
      throw error;
    }
  }

  function exactPositionScenarioWaypoints(options) {
    var supplied = options && options.waypoints;
    if (!Array.isArray(supplied) || !supplied.length) {
      throw new Error('Position scenario animation requires at least one explicit stored-fan waypoint.');
    }
    return supplied.map(function (pin) {
      var dayIndex = number(pin && pin.dayIndex);
      var priceRatio = number(pin && pin.priceRatio);
      var tolerance = pin && pin.tolerance == null ? null : number(pin.tolerance);
      if (!Number.isInteger(dayIndex) || dayIndex < 1 || !(priceRatio > 0)
          || (tolerance != null && !(tolerance > 0))) {
        throw new Error('Each Position scenario waypoint needs a positive whole day, price ratio, and optional tolerance.');
      }
      var result = { dayIndex: dayIndex, priceRatio: priceRatio };
      if (tolerance != null) result.tolerance = tolerance;
      return result;
    });
  }

  function exactPositionScenarioPathWaypoints(options, horizon) {
    var supplied = options && options.pathWaypoints;
    if (!Array.isArray(supplied) || !supplied.length) return [];
    return supplied.map(function (pin, index) {
      var sessionProgress = number(pin && pin.sessionProgress);
      var priceRatio = number(pin && pin.priceRatio);
      var tolerance = pin && pin.tolerance == null ? null : number(pin.tolerance);
      var prior = index ? number(supplied[index - 1] && supplied[index - 1].sessionProgress) : 0;
      if (!(sessionProgress > prior) || sessionProgress > horizon || !(priceRatio > 0)
          || (tolerance != null && !(tolerance >= 0))) {
        throw new Error('Position intraday scenario pins must be ordered within the stored session horizon.');
      }
      var result = { sessionProgress: sessionProgress, priceRatio: priceRatio };
      if (tolerance != null) result.tolerance = tolerance;
      return result;
    });
  }

  function positionTransientCanvas(data, options) {
    var stored = data && data.positionEnsemble, preview = stored && stored.preview || {};
    var shift = number(options && options.ivShiftPoints);
    if (!shift) return null;
    var underlying = preview.canvas && preview.canvas.underlying || [];
    var base = number(underlying.length && underlying[0].atmIv);
    if (!(base > 0)) return null;
    var horizon = Math.max(1, Number(preview.horizonDays || 1));
    var day = Math.max(1, Math.min(horizon,
      Math.round(Number(options && options.days || horizon))));
    var canvas = Object.assign({}, preview.canvasModel || {});
    canvas.ivNodes = [
      { dayIndex: 0, atmIv: base },
      { dayIndex: day, atmIv: Math.max(0.01, Math.min(4, base + shift / 100)) }
    ];
    return canvas;
  }

  function positionPackageFingerprint(trade) {
    trade = trade || {};
    return JSON.stringify(canonicalJson({
      id: trade.id, symbol: trade.symbol, strategy: trade.strategy, intent: trade.intent,
      qty: trade.qty, entryNetPremiumCents: trade.entryNetPremiumCents,
      entryUnderlyingCents: trade.entryUnderlyingCents, openedAt: trade.openedAt,
      updatedAt: trade.updatedAt, status: trade.status, legs: trade.legs || []
    }));
  }

  function assertPositionScenarioResponse(response, requestIdentity) {
    var plan = response && response.plan || {};
    var ensemble = response && response.ensemble || {};
    var receipt = response && response.receipt || {};
    var paths = response && response.paths || {};
    var selection = paths.receipt || {};
    var checkpoints = response && response.checkpoints || {};
    var modelReceipt = checkpoints.modelReceipt || {};
    var focused = Array.isArray(checkpoints.positions) && checkpoints.positions.find(function (row) {
      return row && String(row.key || '') === requestIdentity.tradeId;
    });
    var focusPath = Array.isArray(paths.paths) && paths.paths.find(function (row) {
      return row && String(row.role || '').toUpperCase() === 'FOCUS'
        && Number(row.sourcePathIndex) === Number(selection.focusSourcePathIndex);
    });
    var expectedContextRev = requestIdentity.contextRev;
    var expectedWorld = requestIdentity.worldId;
    var expectedDataset = requestIdentity.datasetId;
    var returnedWaypoints = receipt.conditioningAssumptions
      && receipt.conditioningAssumptions.waypoints || [];
    var returnedPathWaypoints = receipt.conditioningPathWaypoints || [];
    var returnedRule = paths.selection || selection.rule;
    var returnedCanvas = receipt.valuationAssumptions || {};
    var focusedPackageFingerprint = String(receipt.focusedPackageFingerprint || '');
    var focusedPackageProvenance = receipt.focusedPackageProvenance || {};
    if (!response || String(plan.id || '') !== requestIdentity.planId
        || plan.accountId != null && String(plan.accountId) !== String(requestIdentity.accountId)
        || plan.context && plan.context.rev != null && expectedContextRev != null
          && Number(plan.context.rev) !== Number(expectedContextRev)
        || String(ensemble.id || '') !== requestIdentity.ensembleId
        || String(ensemble.fingerprint || '') !== requestIdentity.ensembleFingerprint
        || String(receipt.ensembleId || '') !== requestIdentity.ensembleId
        || String(receipt.ensembleFingerprint || '') !== requestIdentity.ensembleFingerprint
        || String(modelReceipt.ensembleFingerprint || '') !== requestIdentity.ensembleFingerprint
        || String(receipt.focusPositionKey || '') !== requestIdentity.tradeId
        || String(modelReceipt.focusPositionKey || '') !== requestIdentity.tradeId
        || receipt.symbol && String(receipt.symbol).toUpperCase() !== requestIdentity.symbol
        || expectedContextRev != null && Number(receipt.contextRev) !== Number(expectedContextRev)
        || expectedWorld && String(receipt.worldId || '') !== String(expectedWorld)
        || expectedDataset != null && String(receipt.datasetId || '') !== String(expectedDataset)
        || Number(checkpoints.focusSourcePathIndex) !== Number(selection.focusSourcePathIndex)
        || Number(modelReceipt.focusSourcePathIndex) !== Number(selection.focusSourcePathIndex)
        || String(returnedRule || '') !== requestIdentity.pathSelectionRule
        || JSON.stringify(canonicalJson(returnedWaypoints))
          !== JSON.stringify(canonicalJson(requestIdentity.waypoints))
        || requestIdentity.pathWaypoints && requestIdentity.pathWaypoints.length
          && JSON.stringify(canonicalJson(returnedPathWaypoints))
            !== JSON.stringify(canonicalJson(requestIdentity.pathWaypoints))
        || requestIdentity.canvasIvNodes
          && JSON.stringify(canonicalJson(returnedCanvas.ivNodes || []))
            !== JSON.stringify(canonicalJson(requestIdentity.canvasIvNodes))
        || requestIdentity.anchorSource
          && String(receipt.anchorSource || '') !== String(requestIdentity.anchorSource)
        || requestIdentity.anchorFreshness
          && String(receipt.anchorFreshness || '') !== String(requestIdentity.anchorFreshness)
        || !/^[0-9a-f]{64}$/i.test(focusedPackageFingerprint)
        || String(modelReceipt.focusedPackageFingerprint || '') !== focusedPackageFingerprint
        || JSON.stringify(canonicalJson(modelReceipt.focusedPackageProvenance || {}))
          !== JSON.stringify(canonicalJson(focusedPackageProvenance))
        || String(focusedPackageProvenance.contractVersion || '') !== 'focused-position-package-2'
        || String(focusedPackageProvenance.key || '') !== requestIdentity.tradeId
        || String(focusedPackageProvenance.source || '') !== 'PRACTICE_TRADE'
        || String(focusedPackageProvenance.symbol || '').toUpperCase() !== requestIdentity.symbol
        || !(Number(focusedPackageProvenance.packageQuantity) > 0)
        || !(Number(focusedPackageProvenance.legCount) > 0)
        || !Array.isArray(focusedPackageProvenance.priceAuthorities)
          || !focusedPackageProvenance.priceAuthorities.length
        || focusedPackageProvenance.entryBasisCents == null
        || !focusedPackageProvenance.valuationAsOf
        || !focusedPackageProvenance.entryCreatedAt
        || !focusedPackageProvenance.dataProvenance
        || !focusedPackageProvenance.dataSource
        || !/^[0-9a-f]{64}$/i.test(String(
          focusedPackageProvenance.entrySnapshotFingerprint || ''))
        || !focusPath || !focused || !Array.isArray(focused.steps) || !focused.steps.length
        || !receipt.valuationFingerprint
        || String(modelReceipt.valuationFingerprint || '') !== String(receipt.valuationFingerprint)) {
      throw new Error('The Position scenario response did not retain the linked Plan, focused trade, stored ensemble, path, and valuation identity.');
    }
    return focused;
  }

  /**
   * Reprice the authoritative Position on a conditioned projection of its owning Plan's latest
   * stored fan. This never creates an ensemble and never shares cancellation state with New Idea,
   * Book, Position loading, or New Idea animation.
   */
  async function positionScenario(options) {
    if (!state.enabled) return null;
    var token = ++positionScenarioRequestSeq;
    var positionRequestId = state.position && state.position.requestId;
    var data = state.position && state.position.data;
    var tradeId = data && data.trade && data.trade.id == null ? null
      : data && data.trade && String(data.trade.id);
    state.positionScenario = {
      phase: 'loading', requestId: token, positionRequestId: positionRequestId,
      tradeId: tradeId, data: null, error: null
    };
    notify('position-scenario-loading', {
      operation: 'position-scenario', requestId: token,
      positionScenario: state.positionScenario
    });
    try {
      if (!data || !data.trade || !data.plan) {
        throw new Error('Load an authoritative Position and its linked Plan before animating a scenario.');
      }
      if (options && options.tradeId != null
          && String(options.tradeId) !== String(data.trade.id)) {
        throw new Error('The active Position adapter owns another trade; reload this exact position before conditioning it.');
      }
      var stored = data.positionEnsemble;
      if (!stored || !stored.ensemble || !stored.ensemble.id || !stored.ensemble.fingerprint) {
        throw new Error('This Position has no stored outcome ensemble to condition; run its owning Plan outcomes first.');
      }
      var planId = String(data.plan.id || '');
      if (!planId) throw new Error('The authoritative Position omitted its owning Plan id.');
      tradeId = String(data.trade.id || '');
      if (!tradeId) throw new Error('The authoritative Position omitted its trade id.');
      var limit = options && options.limit == null ? 48 : number(options.limit);
      if (!Number.isInteger(limit) || limit < 1 || limit > 60) {
        throw new Error('Position scenario path limit must be a whole number from 1 through 60.');
      }
      var waypoints = exactPositionScenarioWaypoints(options || {});
      var pathWaypoints = exactPositionScenarioPathWaypoints(options || {},
        Math.max(1, Number(stored.preview && stored.preview.horizonDays || 1)));
      var storedReceipt = stored.preview && stored.preview.receipt || {};
      var requestIdentity = {
        positionRequestId: positionRequestId,
        planId: planId,
        accountId: data.identity && data.identity.accountId,
        contextRev: data.plan.context && data.plan.context.rev,
        symbol: String(data.trade.symbol || '').toUpperCase(),
        tradeId: tradeId,
        ensembleId: String(stored.ensemble.id),
        ensembleFingerprint: String(stored.ensemble.fingerprint),
        worldId: storedReceipt.worldId || data.identity && data.identity.world || null,
        datasetId: storedReceipt.datasetId == null
          ? data.identity && data.identity.datasetId : storedReceipt.datasetId,
        positionPackageFingerprint: positionPackageFingerprint(data.trade),
        waypoints: pathWaypoints.length ? [] : waypoints,
        pathWaypoints: pathWaypoints,
        pathSelectionRule: 'NEAREST_AUTHORED_WAYPOINTS',
        anchorSource: storedReceipt.anchorSource || null,
        anchorFreshness: storedReceipt.anchorFreshness || null
      };
      var body = {
        ensembleId: requestIdentity.ensembleId,
        limit: limit,
        focusPositionKey: tradeId
      };
      if (pathWaypoints.length) body.pathWaypoints = pathWaypoints;
      else body.waypoints = waypoints;
      var canvas = positionTransientCanvas(data, options || {});
      if (canvas) {
        body.canvas = canvas;
        requestIdentity.canvasIvNodes = canvas.ivNodes;
      }
      var response = await requireApi().post('/api/plans/' + encodeURIComponent(planId)
        + '/outcomes/ensemble/paths', body);
      if (token !== positionScenarioRequestSeq) return null;
      assertPositionScenarioResponse(response, requestIdentity);
      var confirmedDetail = await requireApi().getFresh('/api/trades/' + encodeURIComponent(tradeId));
      if (token !== positionScenarioRequestSeq) return null;
      if (!confirmedDetail || !confirmedDetail.trade
          || String(confirmedDetail.trade.id || '') !== requestIdentity.tradeId
          || positionPackageFingerprint(confirmedDetail.trade)
            !== requestIdentity.positionPackageFingerprint) {
        throw new Error('The focused Position package changed while its scenario was being valued. Reload the exact position before using these checkpoints.');
      }
      var current = state.position && state.position.data;
      var currentEnsemble = current && current.positionEnsemble && current.positionEnsemble.ensemble;
      if (!state.position || state.position.requestId !== requestIdentity.positionRequestId
          || !current || String(current.trade && current.trade.id || '') !== requestIdentity.tradeId
          || positionPackageFingerprint(current.trade) !== requestIdentity.positionPackageFingerprint
          || !currentEnsemble || String(currentEnsemble.id || '') !== requestIdentity.ensembleId
          || String(currentEnsemble.fingerprint || '') !== requestIdentity.ensembleFingerprint) {
        return null;
      }
      state.positionScenario = {
        phase: 'ready', requestId: token, positionRequestId: positionRequestId,
        tradeId: tradeId, data: response, error: null
      };
      notify('position-scenario-ready', {
        operation: 'position-scenario', requestId: token,
        positionScenario: state.positionScenario, data: response, options: options || {}
      });
      return response;
    } catch (error) {
      if (token !== positionScenarioRequestSeq) return null;
      state.positionScenario = {
        phase: 'error', requestId: token, positionRequestId: positionRequestId,
        tradeId: tradeId, data: null, error: errorReceipt(error)
      };
      notify('position-scenario-error', {
        operation: 'position-scenario', requestId: token,
        positionScenario: state.positionScenario, error: error
      });
      throw error;
    }
  }

  /**
   * Explicit opportunity scan through the canonical Universe Scout. When no watchlist is supplied,
   * the server owns the configured active universe; Home never duplicates universe selection. The
   * scan remains user-triggered so painting Home cannot silently spend a provider allowance.
   */
  var scoutAbortController = null;
  function cancelScout() {
    if (scoutAbortController) {
      try { scoutAbortController.abort(); } catch (ignored) { /* already settled */ }
      scoutAbortController = null;
    }
  }
  async function scoutOpportunities(options, onProgress) {
    if (!state.enabled) return null;
    options = options || {};
    var universe = Array.isArray(options.universe) ? options.universe.map(function (symbol) {
      return String(symbol || '').trim().toUpperCase();
    }).filter(Boolean) : [];
    if (!universe.length && String(options.scope || '').toLowerCase() === 'broad') {
      var described = state.book && state.book.data && state.book.data.universe;
      var broad = described && described.scout && described.scout.symbols;
      if (Array.isArray(broad)) universe = broad.map(function (symbol) {
        return String(symbol || '').trim().toUpperCase();
      }).filter(Boolean);
    }
    var body = {
      horizons: Array.isArray(options.horizons) && options.horizons.length
        ? options.horizons : ['45d'],
      maxPicks: Math.max(1, Math.min(universe.length || 12,
        Number(options.maxPicks || Math.min(universe.length || 5, 5)))),
      riskMode: String(options.riskMode || 'balanced').toLowerCase(),
      allow0dte: false,
      intents: Array.isArray(options.intents) && options.intents.length
        ? options.intents : ['INCOME']
    };
    if (universe.length) body.universe = universe;
    if (options.maxLossCents != null) body.maxLossCents = Number(options.maxLossCents);
    if (options.filters) body.filters = options.filters;
    if (options.thesisOverride) body.thesisOverride = String(options.thesisOverride);
    if (options.destinationAccountId) body.destinationAccountId = String(options.destinationAccountId);
    if (options.redeployment) body.redeployment = options.redeployment;
    if (typeof onProgress === 'function' && typeof fetch === 'function'
        && typeof TextDecoder === 'function') {
      // A scan streams a whole universe through the market provider. If the user pivots to
      // analyzing one ticker (or starts a fresh scan), abort this one so it stops holding the
      // provider — otherwise the churning scan rate-limits the exact idea the user asked for.
      cancelScout();
      var controller = typeof AbortController === 'function' ? new AbortController() : null;
      scoutAbortController = controller;
      var response = await fetch('/api/research/scout', {
        method: 'POST',
        headers: {
          'Accept': 'application/x-ndjson',
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(body),
        signal: controller ? controller.signal : undefined
      });
      if (!response.ok) {
        var failureText = await response.text(), failurePayload = null;
        try { failurePayload = failureText ? JSON.parse(failureText) : null; } catch (ignored) {}
        var failure = new Error(failurePayload && (failurePayload.detail || failurePayload.error)
          || ('HTTP ' + response.status));
        failure.status = response.status;
        failure.payload = failurePayload;
        throw failure;
      }
      var result = null, streamError = null, pending = '';
      function acceptLine(raw) {
        if (!raw || !raw.trim()) return;
        var frame;
        try { frame = JSON.parse(raw); } catch (ignored) { return; }
        if (frame.type === 'progress' && frame.progress) onProgress(frame.progress);
        else if (frame.type === 'complete') result = frame.result || null;
        else if (frame.type === 'error') streamError = new Error(
          frame.error || 'The opportunity scan could not finish.');
        else if (frame && (Array.isArray(frame.picks) || frame.frontier || frame.searched != null)) {
          // A JSON response remains a valid canonical Scout receipt when an intermediary or
          // deterministic browser harness cannot preserve the negotiated NDJSON content type.
          result = frame;
        }
      }
      if (response.body && typeof response.body.getReader === 'function') {
        var reader = response.body.getReader(), decoder = new TextDecoder();
        while (true) {
          var chunk = await reader.read();
          pending += decoder.decode(chunk.value || new Uint8Array(), { stream: !chunk.done });
          var lines = pending.split('\n');
          pending = lines.pop() || '';
          lines.forEach(acceptLine);
          if (chunk.done) break;
        }
        acceptLine(pending);
      } else {
        (await response.text()).split('\n').forEach(acceptLine);
      }
      if (scoutAbortController === controller) scoutAbortController = null;
      if (streamError) throw streamError;
      if (!result) throw new Error('The opportunity scan ended without a complete receipt.');
      return result;
    }
    return requireApi().post('/api/research/scout', body);
  }

  var governorTimer = null;
  var pendingGovernorContext = null;

  function clearPendingGovernorRefresh() {
    if (governorTimer) window.clearTimeout(governorTimer);
    governorTimer = null;
    pendingGovernorContext = null;
  }

  function flushGovernorRefresh() {
    governorTimer = null;
    if (!pendingGovernorContext || state.mutationPending) return;
    var next = pendingGovernorContext;
    pendingGovernorContext = null;
    openIdea(next).catch(function () { /* openIdea publishes its typed failure */ });
  }

  document.addEventListener('change', function (event) {
    var control = event.target && event.target.closest && event.target.closest('[data-gov]');
    if (!control || !state.enabled || !state.context || !window.decide) return;
    var key = control.getAttribute('data-gov');
    if (key !== 'risk' && key !== 'minPop' && key !== 'maxAsn') return;
    if (governorTimer) window.clearTimeout(governorTimer);
    var resumed = window.decide.resumePlanContext || {};
    pendingGovernorContext = Object.assign({}, state.context, {
      __deskGovernorOverride: true,
      symbol: window.decide.sym,
      goal: Object.prototype.hasOwnProperty.call(resumed, 'goal') ? resumed.goal : window.decide.goal,
      view: Object.prototype.hasOwnProperty.call(resumed, 'view') ? resumed.view : window.decide.view,
      horizon: Object.prototype.hasOwnProperty.call(resumed, 'horizon') ? resumed.horizon : window.decide.horizon,
      governors: Object.assign({}, window.decide.govs || {}),
      governorExplicit: Object.assign({}, window.decide.govExplicit || {})
    });
    governorTimer = window.setTimeout(flushGovernorRefresh, 180);
  });

  window.DeskBackend = {
    enabled: function () { return state.enabled; },
    state: copyState,
    openIdea: openIdea,
    updatePlanDeclaration: updatePlanDeclaration,
    chooseCandidate: chooseCandidate,
    draftCatalog: draftCatalog,
    previewDraft: previewDraft,
    cancelDraft: cancelDraft,
    useDraft: useDraft,
    repreviewOrder: repreviewOrder,
    commitOrder: commitOrder,
    transitionWorld: transitionWorld,
    scenarioAnimation: scenarioAnimation,
    loadBook: loadBook,
    focusBookSymbol: focusBookSymbol,
    focusBookSector: focusBookSector,
    loadPosition: loadPosition,
    positionScenario: positionScenario,
    positionFutures: positionFutures,
    symbolContext: symbolContext,
    symbolHistory: symbolHistory,
    scoutOpportunities: scoutOpportunities,
    cancelScout: cancelScout,
    cancel: function () {
      pendingIdeaContext = null;
      if (state.mutationPending) activeMutationCancelled = true;
      if (!state.mutationPending || activeMutationKind !== 'order-commit') state.requestSeq++;
      state.animationSeq++;
      draftPreviewSeq++;
      if (!state.mutationPending) state.draft = null;
      clearPendingGovernorRefresh();
      invalidateDecisionPreview('cancelled');
    }
  };
})();
