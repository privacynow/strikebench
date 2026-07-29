/* StrikeBench Desk backend bridge.
 *
 * This module owns no prices, paths, probabilities, payoff math, or recommendations. It only
 * sequences the canonical HTTP APIs and adapts their typed receipts into the Desk's presentation
 * model. The Desk is a served application: if its backend is unavailable, the presentation
 * reports that absence instead of invoking a second browser-side financial model.
 */
(function () {
  'use strict';

  var httpRuntime = window.location.protocol === 'http:' || window.location.protocol === 'https:';
  var WORKSPACE_VERSION = 1;
  var WORKSPACE_FIELDS = [
    'scopeType', 'sectorKey', 'focusedSubject', 'focusedSymbol',
    'focusedPositionId', 'focusedIdeaId', 'focusedEvaluationId',
    'goal', 'view', 'horizonDays', 'riskPosture', 'targetCents',
    'shareQuantity', 'assignmentPreference', 'routeState', 'returnFocus'
  ];
  // Keep this list identical to WorkspaceContext.MARKET_OWNED. These values name artifacts in
  // one exact world/dataset/lane/account and cannot be replayed across a market identity change.
  // The remaining workspace fields are declarations and are deliberately safe to carry across.
  var WORKSPACE_MARKET_OWNED_FIELDS = [
    'scopeType', 'sectorKey', 'focusedSubject', 'focusedSymbol',
    'focusedPositionId', 'focusedIdeaId', 'focusedEvaluationId',
    'targetCents', 'shareQuantity', 'routeState', 'returnFocus'
  ];
  function emptyWorkspaceContext() {
    return {
      version: WORKSPACE_VERSION, generation: 0, world: null, datasetId: null,
      marketLane: null, accountId: null, scopeType: null, sectorKey: null,
      focusedSubject: null, focusedSymbol: null, focusedPositionId: null,
      focusedIdeaId: null, focusedEvaluationId: null, goal: null, view: null,
      horizonDays: null, riskPosture: null, targetCents: null, shareQuantity: null,
      assignmentPreference: null, routeState: null, returnFocus: null,
      query: '', marketMode: 'observed'
    };
  }
  // One ambient workspace object is retained for the lifetime of the document. Presentation
  // accessors hold this exact reference; adopting a server receipt mutates it in place rather
  // than creating a second UI store that can drift from the persisted context.
  var workspaceContext = emptyWorkspaceContext();
  var state = {
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
    strategyControls: {
      values: { risk: null, minPop: null, maxAsn: null, bp: null, gapLoss: null },
      explicit: {},
      revision: 0,
      appliedRevision: 0,
      refreshPending: false,
      supported: { risk: true, minPop: true, maxAsn: true, bp: true, gapLoss: true },
      unavailable: {}
    },
    mutationPending: false,
    animation: null,
    rehearsal: null,
    rehearsals: [],
    rehearsalRestoreError: null,
    context: null,
    workspace: {
      phase: 'idle', receipt: null, context: workspaceContext, error: null
    },
    rejections: [],
    strategyNotes: [],
    adoptedEvaluationId: null,
    adoptionError: null,
    book: null,
    position: null,
    positionScenario: null,
    presentationError: null,
    error: null
  };
  // The baseline world is a server-owned installation fact (`observed` for an observed
  // installation, `demo` for an explicit provider-isolated build). Retain the typed value across
  // a simulated-world visit so the return control never guesses or silently relabels the target.
  var baselineWorld = null;
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
        openIdea(queuedIdea.context, queuedIdea.options)
          .catch(function () { /* openIdea publishes its typed failure */ });
      }, 0);
      return;
    }
    if (pendingGovernorRefresh && !governorTimer) {
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
    var quote = research && research.quote || {};
    var value = number(quote.displayPrice);
    if (value != null && value > 0) {
      return {
        value: value,
        basis: String(quote.markBasis || 'DISPLAY_PRICE').toUpperCase()
      };
    }
    // Research owns the public display mark and its fallback basis. Rebuilding a midpoint here
    // would silently create a second price authority when the canonical mark is unavailable.
    return { value: null, basis: 'UNAVAILABLE' };
  }

  function horizonDays(context) {
    var ownsDays = context && Object.prototype.hasOwnProperty.call(context, 'horizonDays');
    var raw = ownsDays ? context.horizonDays : null;
    if (context && ownsDays && (raw == null || String(raw).trim() === '')) return null;
    var parsed = raw == null ? null : Number(String(raw).match(/\d+/) && String(raw).match(/\d+/)[0]);
    // Absence is a declaration fact (like intentOf/thesisOf): an undeclared horizon stays null and
    // is surfaced as "horizon undeclared" — the adapter never fabricates a 45-session default.
    return parsed && parsed > 0 ? Math.min(756, parsed) : null;
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
    // Absence is a declaration fact. Never infer a posture from a global POSTURE/governor default the
    // user did not declare AS a risk posture — an undeclared risk posture stays null (surfaced as
    // "risk undeclared"), which is what prevents state loss masquerading as a silent "Balanced".
    return null;
  }

  /* One declaration dialect crosses the presentation/bridge boundary. */
  function normalizeIdeaDeclaration(raw) {
    raw = raw || {};
    var symbol = String(raw.symbol || '').trim().toUpperCase();
    var planId = raw.planId == null ? null : String(raw.planId).trim();
    return {
      symbol: symbol || null,
      planId: planId || null,
      goal: intentOf(raw.goal),
      view: thesisOf(raw.view),
      horizonDays: horizonDays(raw),
      riskMode: riskModeOf(raw),
      targetCents: optionalInteger(raw.targetCents),
      holdingsShares: optionalInteger(raw.holdingsShares),
      costBasisCents: optionalInteger(raw.costBasisCents),
      priceAssumptionCents: optionalInteger(raw.priceAssumptionCents),
      assignmentPreference: raw.assignmentPreference == null
        || String(raw.assignmentPreference).trim() === ''
        ? null : String(raw.assignmentPreference).trim(),
      originPlanId: raw.originPlanId == null || String(raw.originPlanId).trim() === ''
        ? null : String(raw.originPlanId).trim()
    };
  }

  function expirationDate(row) { return row && typeof row === 'object' ? row.date : row; }
  function expirationPath(encodedSymbol, horizonSessions) {
    var path = '/api/research/' + encodedSymbol + '/expirations';
    return horizonSessions == null ? path
      : path + '?horizonSessions=' + encodeURIComponent(horizonSessions);
  }
  function selectedExpiration(document) {
    var selection = document && document.selection || {};
    return selection.date ? String(selection.date) : null;
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
      message: error && error.message ? String(error.message) : 'The requested data could not be read.',
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
      if (world.baselineWorld) baselineWorld = String(world.baselineWorld);
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

  /**
   * Validate the versioned Practice Book wire document before any screen sees it. This is a
   * structural contract only: signed money, Greeks, heat, and risk stay exactly as the server
   * supplied them; the browser neither calculates nor normalizes a financial fact.
   */
  function practiceBookSlot(slot) {
    slot = objectSlot(slot, 'The Practice Book');
    if (!slot || !slot.available) return slot;
    var book = slot.value, snapshot = book.snapshot;
    if (book.schemaVersion !== 'practice-book-read-v1') {
      return unavailableSlot(slot.key, slot.path,
        'The Practice Book returned an unsupported schema version.');
    }
    if (!book.snapshotId || !book.account || !book.summary || !snapshot
        || !book.bookRisk || !book.liquidity || !book.selectedBook) {
      return unavailableSlot(slot.key, slot.path,
        'The Practice Book did not return every required typed receipt.');
    }
    if (snapshot.schemaVersion !== 'practice-book-snapshot-v1'
        || String(snapshot.snapshotId || '') !== String(book.snapshotId)
        || String(snapshot.accountId || '') !== String(book.account.accountId || '')) {
      return unavailableSlot(slot.key, slot.path,
        'The Practice Book snapshot identity does not match its account receipt.');
    }
    if (!Array.isArray(snapshot.activeTrades) || !Array.isArray(book.sharePositions)
        || !snapshot.heat || !snapshot.greeks || !snapshot.openPositions
        || !book.bookRisk.shareRoster) {
      return unavailableSlot(slot.key, slot.path,
        'The Practice Book is missing its typed roster, heat, Greeks, value, or risk receipt.');
    }
    var measured = book.bookRisk.measuredBook;
    if (measured && measured.available === true) {
      var scenario = measured.scenario;
      if (!scenario || !scenario.jointFingerprint
          || !Array.isArray(scenario.stepBands) || !Array.isArray(scenario.displayPaths)
          || !Array.isArray(scenario.positions) || !Array.isArray(scenario.markets)) {
        return unavailableSlot(slot.key, slot.path,
          'The measured Book omitted its batched total, position, or market fan receipt.');
      }
      var projectedTradeIds = new Set(scenario.positions.map(function (row) {
        return String(row && row.key || '');
      }));
      var marketSymbols = new Set(scenario.markets.map(function (row) {
        return String(row && row.symbol || '').toUpperCase();
      }));
      var missingProjection = snapshot.activeTrades.find(function (trade) {
        return !projectedTradeIds.has(String(trade && trade.id || ''))
          || !marketSymbols.has(String(trade && trade.symbol || '').toUpperCase());
      });
      if (missingProjection) {
        return unavailableSlot(slot.key, slot.path,
          'The measured Book did not project every active package and its market.');
      }
    }
    return slot;
  }

  function practiceBookTrades(data) {
    var snapshot = data && data.practiceBook && data.practiceBook.snapshot;
    return snapshot && Array.isArray(snapshot.activeTrades) ? snapshot.activeTrades : [];
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
    return Object.assign({}, research.quote || {});
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
      api.get(expirationPath(encoded, targetDays)),
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
    var expiration = selectedExpiration(base[4]);
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
      expirations: (base[4].expirations || []).map(expirationDate), expiration: expiration, chain: chain,
      expirationSelection: base[4].selection||null,
      expirationBasis: base[4].selection&&base[4].selection.basis||null,
      expirationAsOf: base[4].asOfDate||null,
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
    context = normalizeIdeaDeclaration(context);
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
    return normalizeIdeaDeclaration({
      symbol: plan && plan.symbol || context && context.symbol,
      planId: plan && plan.id || null,
      goal: !plan || plan.intent == null ? null : String(plan.intent),
      view: exact.thesis == null ? null : String(exact.thesis),
      horizonDays: exact.horizonDays == null ? null : exact.horizonDays,
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

  function clearSessionRequestId(identity) {
    // E (#6): drop the persisted create key so a subsequent Retry cannot reissue a key that the
    // server has already bound to a different create input (a permanent 409).
    try { window.sessionStorage.removeItem(sessionRequestKey(identity)); } catch (ignored) { /* storage can be disabled */ }
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
    /* originPlanId is immutable lineage, not ownership. WorkspaceContext intentionally stores
       only the focused Plan id; requiring it to repeat lineage on reload rejects a perfectly
       valid position-derived Plan even though symbol, account, and market all still match. */
    return true;
  }

  function acceptPlan(plan) {
    if (!plan) return state.plan;
    if (state.plan && state.plan.id !== plan.id) {
      throw new Error('A response attempted to replace the active Desk Plan with another Plan.');
    }
    if (state.planIdentity && !samePlan(plan, state.planIdentity)) {
      throw new Error('The active Plan no longer matches this Desk idea and market identity.');
    }
    if (!state.plan || Number(plan.version || 0) >= Number(state.plan.version || 0)) {
      state.plan = plan;
      state.context = contextFromPlan(null, plan);
      projectAcceptedPlanToWorkspace(state.context);
    }
    return state.plan;
  }

  /*
   * Workspace is the ambient return context, not a second accepted-Plan authority. Project the
   * server-accepted declaration downstream once, at the bridge seam, so Home/reload can resume
   * the question without any presentation renderer writing Plan facts back into the store.
   */
  function projectAcceptedPlanToWorkspace(declaration) {
    if (!declaration) return;
    var projection = {
      goal: declaration.goal,
      view: declaration.view,
      horizonDays: declaration.horizonDays,
      riskPosture: declaration.riskMode,
      targetCents: declaration.targetCents,
      shareQuantity: declaration.holdingsShares,
      assignmentPreference: declaration.assignmentPreference
    };
    var changed = {};
    Object.keys(projection).forEach(function (field) {
      var next = projection[field], prior = workspaceContext[field];
      if (String(prior == null ? '' : prior) === String(next == null ? '' : next)) return;
      workspaceContext[field] = next;
      changed[field] = next;
    });
    if (Object.keys(changed).length) {
      patchWorkspace(changed).catch(function () {
        /* patchWorkspace publishes the typed workspace error; accepted Plan truth remains intact. */
      });
    }
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
      var listed = candidates[i];
      var exact = await requireApi().getFresh('/api/plans/' + encodeURIComponent(listed.id));
      if (seq !== state.requestSeq) return null;
      if (samePlan(exact, identity)) return exact;
    }
    return null;
  }

  function readPlan(planId) {
    var id = planId == null ? '' : String(planId).trim();
    if (!id) return Promise.reject(new Error('Choose a saved Plan before opening it.'));
    return requireApi().getFresh('/api/plans/' + encodeURIComponent(id));
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
      context = contextFromPlan(null, plan);
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
      // The session-scoped create key can legitimately outlive the Plan it originally created:
      // that Plan may no longer match this idea's declarations, OR it may have been frozen since
      // (a trade was placed, so it is now a Position, not a mutable working inquiry). The
      // idempotent create then either returns the stale Plan (200) or, when the create INPUT drifted
      // under the same key, rejects with a hard 409 ("clientRequestId was already used for a
      // different plan request"). Both need the SAME recovery: re-list so a concurrent *mutable*
      // Plan wins, otherwise rotate the create key exactly once and retry once. Reusing the stale
      // key would make Retry a permanent loop.
      async function recoverFromStaleCreateKey() {
        var relisted = await api.getFresh('/api/plans');
        if (seq !== state.requestSeq) return { superseded: true };
        var match = await freshestMatchingPlan(relisted && relisted.plans, identity, seq);
        if (match) return { plan: match };
        if (seq !== state.requestSeq) return { superseded: true };
        return { plan: await createPlan(true) };
      }
      try {
        plan = await createPlan(false);
      } catch (createError) {
        // Only the clientRequestId-conflict 409 is ours. A PlanOutcomeController expectedVersion
        // optimistic-lock 409 is a different mechanism and must still surface unchanged.
        if (!createError || createError.status !== 409
            || !/clientRequestId/i.test(String(createError.message || ''))) throw createError;
        // CLEAR the failing key up front so any user Retry can never reissue it, THEN run the
        // single rotated recovery. If it still 409s, surface a DISTINCT terminal error — no loop.
        clearSessionRequestId(identity);
        var recovered;
        try {
          recovered = await recoverFromStaleCreateKey();
        } catch (retryError) {
          if (retryError && retryError.status === 409) {
            throw new Error('This idea could not be started: the plan request keeps conflicting on the server. Reload the desk before trying again.');
          }
          throw retryError;
        }
        if (recovered.superseded) return null;
        plan = recovered.plan;
      }
      if (seq !== state.requestSeq) return null;
      if (!samePlan(plan, identity) || !mutableWorkingPlan(plan)) {
        var recoveredStale = await recoverFromStaleCreateKey();
        if (recoveredStale.superseded) return null;
        plan = recoveredStale.plan;
      }
    }
    if (seq !== state.requestSeq) return null;
    if (!mutableWorkingPlan(plan)) {
      throw new Error('A new editable Plan could not be created for this idea.');
    }
    if (!samePlan(plan, identity)) {
      throw new Error('The returned Plan does not match this Desk idea, account, and market identity.');
    }
    state.planIdentity = identity;
    acceptPlan(plan);
    // A resumed Plan owns its persisted mutable declarations. Hydrate all of them from that one
    // accepted receipt rather than retaining caller drafts or presentation labels beside it.
    state.context = contextFromPlan(null, state.plan);
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
      entryPrice: leg.entryPrice,
      quoteBid: leg.quoteBid,
      quoteAsk: leg.quoteAsk,
      quoteIv: leg.quoteIv,
      quoteDelta: leg.quoteDelta,
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
    if (!state.market) return null;
    return {
      expiration: state.market.expiration,
      // The Desk loads one exact chain at a time. Do not advertise expirations whose strike
      // catalog has not been loaded and verified in this market identity.
      expirations: state.market.expiration ? [state.market.expiration] : [],
      calls: availableDraftStrikes('c'),
      puts: availableDraftStrikes('p')
    };
  }

  function canonicalDraftPosition(legs, sourceCandidate, options) {
    options = options || {};
    var exactFork = options.exactFork === true;
    if (!state.plan || !state.market) throw new Error('Load the active Plan and option chain before editing a package.');
    if (!exactFork && (!sourceCandidate || !state.selected
        || String(sourceCandidate.id) !== String(state.selected.id))) {
      throw new Error('The draft source is no longer the selected strategy. Start the edit again.');
    }
    // The Plan declares trading sessions and the backend Horizon grammar owns the named buckets.
    // Re-deriving "week"/"month" here published a second, divergent set of thresholds (8-10
    // sessions bucketed differently in the browser than on the server), and the `|| 30` fallback
    // turned an undeclared horizon into a month-long package the user never asked for.
    var declaredSessions = Number(state.plan.context && state.plan.context.horizonDays);
    if (!Number.isInteger(declaredSessions) || declaredSessions < 1 || declaredSessions > 756) {
      throw new Error('Declare the Plan horizon in trading sessions before previewing an exact package.');
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
      if (!stock && !expiration) {
        throw new Error('Every option leg needs its exact expiration.');
      }
      // A held package is already a server-owned contract identity. It can legitimately contain
      // another expiration or a strike outside the currently displayed five-row chain. Preserve
      // it byte-for-byte and let the canonical preview service verify the quote; never snap an
      // untouched held leg to whatever happens to be visible in this panel.
      if (!exactFork && !stock && expirations.indexOf(expiration) < 0) {
        throw new Error('Choose an expiration from the active option chain.');
      }
      var strike = stock ? null : number(leg.k);
      if (!stock && !(strike > 0)) throw new Error('Each option leg needs a listed strike.');
      if (!exactFork && !stock && expiration === currentExpiration
          && availableDraftStrikes(kind).indexOf(strike) < 0) {
        throw new Error('Choose a strike from the active option chain.');
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
      horizon: declaredSessions + 'd',
      riskMode: state.plan.context && state.plan.context.riskMode,
      intent: state.plan.intent,
      useHeldShares: exactFork ? options.useHeldShares === true
        : sourceCandidate.usesHeldShares === true,
      recommendationId: exactFork ? null : sourceCandidate.recommendationId || null,
      feesOverrideCents: null,
      source: 'BUILDER',
      fillNature: 'PROPOSED',
      orderInstruction: { type: 'MARKET', timeInForce: 'DAY' }
    };
  }

  function previewLegs(position, preview) {
    var marked = preview && Array.isArray(preview.legs) ? preview.legs : [];
    return position.legs.map(function (leg, index) {
      var out = Object.assign({}, leg);
      var exact = marked[index] || {};
      var fill = exact.entryPrice == null ? exact.fill : exact.entryPrice;
      if (fill != null) out.entryPrice = fill;
      /* Preserve the exact marked-leg receipt through a held-package fork. Dropping these fields
         forced index.html to look up the ambient chain and silently substitute a different
         observation for the package the user actually selected. */
      ['quoteBid', 'quoteAsk', 'quoteIv', 'quoteDelta', 'quoteAsOfEpochMs',
        'quoteSource', 'quoteFreshness'].forEach(function (key) {
        if (exact[key] != null) out[key] = exact[key];
      });
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
      price: preview.price || null,
      maxLossCents: preview.maxLossCents,
      maxProfitCents: preview.maxProfitCents,
      combinedMaxLossCents: preview.analytics && preview.analytics.combinedMaxLossCents,
      breakevens: preview.breakevens || [],
      shortSideExpirationItmProb: preview.shortSideExpirationItmProb,
      marketImpliedRisk: preview.marketImpliedRisk
        || preview.analytics && preview.analytics.marketImpliedRisk || null,
      freshness: preview.freshness,
      sourceKind: 'EXACT_BACKEND_PREVIEW',
      whyConsidered: 'Exact package valued by the active StrikeBench preview service.',
      usesHeldShares: position.useHeldShares === true,
      evaluation: response.evaluation || null,
      identity: identity
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
    if (preview.analytics && preview.analytics.time) desk.time = preview.analytics.time;
    applyGreeks(desk, greeksView(preview.analytics && preview.analytics.greeks));
    return desk;
  }

  var draftPreviewSeq = 0;

  async function previewDraft(legs, sourceCandidateId, options) {
    options = options || {};
    var exactFork = options.exactFork === true;
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
      position = canonicalDraftPosition(legs, sourceCandidate, options);
    } catch (error) {
      if (token !== draftPreviewSeq) return null;
      state.draft = {
        sourceCandidateId: sourceCandidateId,
        exactFork: exactFork,
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
      exactFork: exactFork,
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
          || (!exactFork && (!state.selected
            || String(state.selected.id) !== String(sourceCandidateId)))) return null;
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
        exactFork: exactFork,
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
        exactFork: exactFork,
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
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var draft = state.draft;
    if (!draft || draft.pending || !draft.valid || !draft.preview || !draft.position) {
      throw new Error('Wait for a valid exact-package preview before using this structure.');
    }
    if (!state.plan || (!draft.exactFork && (!state.selected
        || String(state.selected.id) !== String(draft.sourceCandidateId)))) {
      throw new Error('The selected strategy changed after this draft was priced. Preview it again.');
    }
    var ensembleIdentity = state.ensemble && state.ensemble.ensemble && {
      id: state.ensemble.ensemble.id,
      fingerprint: state.ensemble.ensemble.fingerprint
    };
    if (!draft.exactFork
        && (!ensembleIdentity || !ensembleIdentity.id || !ensembleIdentity.fingerprint)) {
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
      custom.identity = response.identity || custom.identity;
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
      var customAnalytics = response.preview && response.preview.analytics;
      if (customAnalytics && customAnalytics.time) presentation.time = customAnalytics.time;
      applyGreeks(presentation, greeksView(customAnalytics && customAnalytics.greeks));
      var outcome;
      if (ensembleIdentity) {
        if (!state.ensemble || !state.ensemble.ensemble
            || state.ensemble.ensemble.id !== ensembleIdentity.id
            || state.ensemble.ensemble.fingerprint !== ensembleIdentity.fingerprint) {
          throw new Error('The exact package lost the active outcome ensemble identity.');
        }
        outcome = await runOutcome(seq, {
          expectedCandidateId: custom.id,
          expectedEnsemble: ensembleIdentity,
          silent: true
        });
      } else {
        // Position→Idea may begin from a field with no endorsed or even viable generated
        // comparison. Saving the exact held package makes it the Plan selection first; then the
        // existing ensemble owner creates/reuses one fan and values that selected package.
        var evaluated = await loadOrRunEnsembleAndOutcome(seq);
        outcome = evaluated && evaluated.outcome;
      }
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
      await previewDecision(candidateLimitOrder(custom), seq);
      if (seq !== state.requestSeq) return null;
      state.rehearsals = await readRehearsals(state.plan.id, {
        notify: false, optional: true
      });
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

  function strategyRequestControls(context) {
    var governors = state.strategyControls.values;
    var explicit = state.strategyControls.explicit;
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
    var minPop = number(governors.minPop), maxAssignment = number(governors.maxAsn),
      maxCapital = number(governors.bp), maxCrashLoss = number(governors.gapLoss);
    if (explicit.minPop === true && minPop != null && minPop > 0) {
      filters.minPop = Math.max(0, Math.min(1, minPop / 100));
    }
    if (explicit.maxAsn === true && maxAssignment != null && maxAssignment >= 0) {
      filters.maxShortSideExpirationItmProb = Math.max(0, Math.min(1, maxAssignment / 100));
    }
    if (explicit.bp === true && maxCapital != null && maxCapital >= 0
        && Number.isFinite(maxCapital)) {
      filters.maxCapitalRequiredCents = Math.round(maxCapital * 100);
    }
    if (explicit.gapLoss === true && maxCrashLoss != null && maxCrashLoss >= 0
        && Number.isFinite(maxCrashLoss)) {
      filters.maxMarketCrashLossCents = Math.round(maxCrashLoss * 100);
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

  /**
   * A named-story request is a declaration: null controls mean "use the server policy." Its
   * receipt therefore carries resolved values where the request carried nulls. Explicit user
   * overrides and exact source-path identity still have to round-trip exactly.
   */
  function scenarioInteractionMatches(declared, resolved) {
    if (!declared || !resolved) return declared === resolved;
    var declaredStory = declared.story == null ? null : String(declared.story);
    var resolvedStory = resolved.story == null ? null : String(resolved.story);
    if (declaredStory !== resolvedStory) return false;
    var declaredSource = declared.sourcePathIndex == null
      ? null : Number(declared.sourcePathIndex);
    var resolvedSource = resolved.sourcePathIndex == null
      ? null : Number(resolved.sourcePathIndex);
    if (declaredSource !== resolvedSource) return false;
    var fields = ['movePct', 'ivShiftPoints', 'elapsedSessions'];
    for (var i = 0; i < fields.length; i++) {
      var field = fields[i], expected = declared[field], actual = resolved[field];
      if (expected != null && Number(expected) !== Number(actual)) return false;
      if (declaredStory != null && actual == null) return false;
    }
    return declaredStory != null || declaredSource != null;
  }

  function candidateIdentity(candidate) {
    var identity = candidate && candidate.identity;
    return identity && typeof identity.definedRisk === 'boolean' ? identity : null;
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

  /* THE one canonical greeks contract the desk reads: deltaShares, gammaSharesPerDollar,
     thetaCentsPerDay, vegaCentsPerPoint. Preserve the typed backend object itself. A partial,
     string-coerced, or retired dialect is not "normalized" into a new financial receipt. */
  function greeksView(source) {
    if (!source || typeof source !== 'object') return null;
    var fields = ['deltaShares', 'gammaSharesPerDollar',
      'thetaCentsPerDay', 'vegaCentsPerPoint'];
    if (!fields.every(function (field) {
      return typeof source[field] === 'number' && Number.isFinite(source[field]);
    })) return null;
    return source;
  }

  /* Preserve the typed receipt on the presentation object. The rendered Desk owns its one
     field-name-to-visual-key projection; the transport bridge does not mint delta/gamma/theta/vega
     aliases that can survive after the receipt itself changes. */
  function applyGreeks(target, greeks) {
    target.greeks = greeks || null;
    return target;
  }

  function candidateToDesk(candidate, market) {
    var qty = Math.max(1, Number(candidate.qty || 1));
    var riskProfile = candidate.evaluation && candidate.evaluation.risk || {};
    var marketImpliedRisk = candidate.marketImpliedRisk || null;
    var marketProbability = marketImpliedRisk && marketImpliedRisk.probabilityMap
      ? marketImpliedRisk.probabilityMap.pAnyProfit : null;
    var marketPopUnavailableReason = marketProbability == null
      ? marketImpliedRisk && marketImpliedRisk.unavailableReason
        || (marketImpliedRisk
          ? 'The market-implied receipt did not include a package chance-of-profit result.'
          : 'No market-implied risk receipt accompanied this package.')
      : null;
    // Candidate.java carries no Greeks. They arrive only on the separately priced exact preview;
    // accepting candidate.greeks here let fixtures and stale clients invent a field the server
    // cannot emit. applyGreeks attaches the preview's typed GreeksView when that receipt arrives.
    var candidateGreeks = null;
    var terminalPayoff = riskProfile.terminalPayoff || {};
    var payoffPoints = terminalPayoff.available === true && Array.isArray(terminalPayoff.points)
      ? terminalPayoff.points.map(function (point) {
          return { price: Number(point.price), profit: Number(point.profitCents) / 100 };
        }).filter(function (point) {
          return Number.isFinite(point.price) && Number.isFinite(point.profit);
        }) : [];
    var economics = candidate.evaluation && candidate.evaluation.assessment
      && candidate.evaluation.assessment.economics || {};
    var mechanics = candidate.evaluation && candidate.evaluation.assessment
      && candidate.evaluation.assessment.mechanics || {};
    var capital = candidate.evaluation && candidate.evaluation.capital || {};
    var capitalRequirement = capital.requirement || {};
    var optionLeg = (candidate.legs || []).find(function (leg) {
      return String(leg.type || '').toUpperCase() !== 'STOCK';
    });
    // §7.2: one package-price receipt per package. entryNetPremiumCents/optionNetPremiumCents are
    // gone from the wire — both were views of the same receipt, and keeping two names is how the
    // rail and the dock came to state different prices for one package.
    var price = candidate.price || null;
    var identity = candidateIdentity(candidate);
    var explicitDefinedRisk = identity ? identity.definedRisk : null;
    var packageCapital = candidate.capital || {};
    var requiredCapital = packageCapital.economicExposureCents == null
      ? null : Number(packageCapital.economicExposureCents);
    var incremental = capital.incrementalCents == null ? null : Number(capital.incrementalCents);
    var economic = capital.economicCents == null ? null : Number(capital.economicCents);
    var incrementalMaxLossCents = candidate.maxLossCents == null
      ? null : Number(candidate.maxLossCents);
    var combinedMaxLossCents = candidate.combinedMaxLossCents == null
      ? null : Number(candidate.combinedMaxLossCents);
    // Held-share candidates publish combined maximum profit. Their headline maximum loss must
    // use the same stock-plus-option scope; the incremental reserve remains a separately named
    // capital fact below.
    var maxLossCents = combinedMaxLossCents == null
      ? incrementalMaxLossCents : combinedMaxLossCents;
    var maxLossBasis = combinedMaxLossCents == null
      ? candidate.maxLossBasis || riskProfile.maxLossBasis || null
      : 'COMBINED_STOCK_AND_OPTION';
    var maxLossUnavailableReason = maxLossCents != null ? null
      : candidate.maxLossUnavailableReason
        || riskProfile.maxLossUnavailableReason
        || riskProfile.unavailableReason
        || (terminalPayoff.available === false ? terminalPayoff.unavailableReason : null)
        || candidate.unavailableReason
        || candidate.blockReason
        || candidate.rejectionReason
        || (Array.isArray(mechanics.reasons) && mechanics.reasons.length ? mechanics.reasons[0] : null)
        || 'Maximum loss is unavailable because no bounded-loss receipt accompanied this package.';
    // §3.1/§3.2: capital has THREE possible authorities on the wire and the bridge used to pick one
    // silently, so a rail cell labelled "Capital" could actually be carrying the package's max loss
    // — a different financial fact — with nothing on the model saying which. Name the authority, and
    // when there is none, publish the REASON beside the null instead of an unexplained absence that
    // the next renderer coerces to $0.
    /* Capital and maximum loss are different financial facts. A missing capital receipt used to
       fall through to maxLossCents, after which every surface labelled the substituted value
       "Capital." Keep capital absent instead; max loss remains available on its own field. */
    var displayCapital = requiredCapital != null ? requiredCapital
      : incremental != null ? incremental : economic != null ? economic : null;
    var capBasis = requiredCapital != null ? String(packageCapital.capitalBasis || 'CAPITAL_ECONOMIC_EXPOSURE')
      : incremental != null ? 'CAPITAL_INCREMENTAL'
      : economic != null ? 'CAPITAL_ECONOMIC' : null;
    var capUnavailableReason = displayCapital != null ? null
      : candidate.evaluation && candidate.evaluation.capital
        ? 'This package’s capital receipt states neither incremental nor economic capital, so the '
          + 'capital it would tie up is not available.'
        : 'No capital receipt accompanied this package, so the capital it would tie up is not available.'
          + (maxLossCents == null ? '' : ' Maximum loss remains available separately and is not substituted for capital.');
    var realisticEv = economics.realizedVolEvAfterCostsCents == null
      ? null : Number(economics.realizedVolEvAfterCostsCents);
    var realisticLow = economics.realisticEvLowAfterCostsCents == null
      ? null : Number(economics.realisticEvLowAfterCostsCents);
    var realisticHigh = economics.realisticEvHighAfterCostsCents == null
      ? null : Number(economics.realisticEvHighAfterCostsCents);
    return {
      id: candidate.id,
      short: candidate.displayName || candidate.strategy || candidate.label,
      exactPackage: candidate.label || null,
      name: candidate.displayName || candidate.strategy,
      sym: candidate.symbol || market.quote.symbol,
      spot: market.spot,
      em: null,
      time: candidate.time || null,
      terminalTime: candidate.terminalTime || candidate.time || null,
      event: candidate.event || null,
      settlement: candidate.settlement || null,
      exp: optionLeg && optionLeg.expiration || market.expiration,
      lean: null,
      risk: explicitDefinedRisk == null ? 'unknown' : explicitDefinedRisk ? 'defined' : 'undefined',
      undef: explicitDefinedRisk === false,
      positionIdentity: identity,
      legs: (candidate.legs || []).map(function (leg) { return legToDesk(leg, qty); }),
      price: price,
      /* Keep the typed receipt and its exact unavailable reason together. The map may project
         pAnyProfit onto an axis, but the bridge must not turn a named absence into an anonymous
         null—or substitute the distinct short-side expiration-ITM probability below. */
      marketImpliedRisk: marketImpliedRisk,
      pop: marketProbability == null ? null : Math.round(Number(marketProbability) * 100),
      marketPopUnavailableReason: marketPopUnavailableReason,
      maxLoss: maxLossCents == null ? null : maxLossCents / 100,
      incrementalMaxLoss: incrementalMaxLossCents == null
        ? null : incrementalMaxLossCents / 100,
      maxLossBasis: maxLossBasis,
      maxLossUnavailableReason: maxLossUnavailableReason,
      combinedMaxLoss: combinedMaxLossCents == null ? null : combinedMaxLossCents / 100,
      maxProfit: candidate.maxProfitCents == null ? null : Number(candidate.maxProfitCents) / 100,
      bestUpside: candidate.bestUpside || null,
      biggestRisk: candidate.biggestRisk || null,
      cap: displayCapital == null ? null : displayCapital / 100,
      // Which receipt `cap` actually came from, and — when it came from none — why. A surface that
      // prints `cap` must print this reason instead when `cap` is null; it may never print $0 (§3.2).
      capAuthority: capBasis,
      capUnavailableReason: capUnavailableReason,
      capitalBasis: capital.basis || null,
      fundingClass: capitalRequirement.fundingClass || null,
      reserveCents: capitalRequirement.reserveCents == null
        ? null : Number(capitalRequirement.reserveCents),
      buyingPowerRequiredCents: capitalRequirement.buyingPowerRequiredCents == null
        ? null : Number(capitalRequirement.buyingPowerRequiredCents),
      riskProfile: candidate.evaluation && candidate.evaluation.risk || null,
      terminalPayoff: terminalPayoff,
      payoffPoints: payoffPoints,
      usesHeldShares: candidate.usesHeldShares === true,
      sharesNeeded: candidate.sharesNeeded == null ? null : Number(candidate.sharesNeeded),
      annualizedOpeningPremiumRatePct: candidate.annualizedOpeningPremiumRatePct == null
        ? null : Number(candidate.annualizedOpeningPremiumRatePct),
      effectivePrice: candidate.effectivePrice == null ? null : String(candidate.effectivePrice),
      intentNote: candidate.intentNote || null,
      edge: realisticEv == null ? null : realisticEv / 100,
      edgeLow: realisticLow == null ? null : realisticLow / 100,
      edgeHigh: realisticHigh == null ? null : realisticHigh / 100,
      edgeBasis: realisticEv == null ? null : 'REALIZED_VOL_AFTER_COSTS',
      edgeRangeBasis: economics.realisticEvBasis || null,
      marketEvRole: economics.marketEvRole || null,
      assign: candidate.shortSideExpirationItmProb == null ? null : Math.round(Number(candidate.shortSideExpirationItmProb) * 100),
      why: candidate.whyConsidered || candidate.beginnerExplanation || '',
      analog: candidate.sourceKind ? 'Ranked comparison · ' + candidate.sourceKind : 'Ranked comparison',
      ivnote: candidate.freshness ? String(candidate.freshness) + ' market inputs' : 'Market input receipt attached',
      breakevens: candidate.breakevens || [],
      evaluation: candidate.evaluation || null,
      jumpTail: riskProfile.jumpTail || null,
      greeks: candidateGreeks,
      backend: candidate
    };
  }

  /**
   * Selecting an options package must not silently create a MARKET order. The package's canonical
   * captured-book receipt owns the signed natural net used as the initial LIMIT instruction.
   * MARKET remains available only through an explicit user choice in the execution controls.
   */
  function candidateLimitOrder(candidate) {
    var price = candidate && candidate.price || {};
    var cents = price.restingLimitNetCents == null
      ? (price.executableNetCents == null ? price.grossPackageNetCents
        : price.executableNetCents)
      : price.restingLimitNetCents;
    cents = number(cents);
    if (cents == null || !Number.isInteger(cents)) {
      throw new Error('The captured package book has no signed whole-cent limit anchor.');
    }
    return { type: 'LIMIT', limitNetCents: cents, qty: Math.max(1, Number(candidate.qty || 1)) };
  }

  function mergeSelectedCandidate(candidates, selected) {
    var visible = candidates.slice();
    if (!selected) return visible;
    var selectedIndex = visible.findIndex(function (candidate) {
      return String(candidate.id) === String(selected.id);
    });
    /* The ranked competition has the live read-time receipts (event, terminal time, settlement,
       current price authority). The separate selected record carries selection identity and any
       custom fields. Merge them; never replace the refreshed row with an older persisted copy. */
    if (selectedIndex >= 0) visible[selectedIndex] = Object.assign({}, selected, visible[selectedIndex]);
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
    if (seq !== state.requestSeq) return null;
    state.strategy = strategy;
    state.candidates = visible;
    state.selected = selected
      ? visible.find(function (candidate) { return String(candidate.id) === String(selected.id); }) || selected
      : null;
    state.rejections = rejected.slice();
    state.strategyNotes = notes.slice();
    var deskPickId = result && result.deskPickCandidateId;
    var deskPick = deskPickId == null ? null : ranked.find(function (candidate) {
      return String(candidate.id) === String(deskPickId);
    });
    if (deskPickId != null && !deskPick) {
      throw new Error('The Desk Pick does not identify a candidate in this ranked field.');
    }
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
    var controls = strategyRequestControls(context);
    var path = '/api/plans/' + encodeURIComponent(plan.id) + '/strategy';
    var out = await api.post(path + '/run', controls);
    if (seq !== state.requestSeq) return null;
    acceptPlan(out.plan || plan);
    var posted = out && out.strategy;
    if (!posted || String(posted.state || '').toUpperCase() !== 'CURRENT'
        || !posted.runId || !posted.inputHash) {
      throw new Error('The current ranked field could not be retained.');
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
    return publishStrategy(strategy, latest && latest.selected, market, seq, {
      refreshed: true,
      selectionRestored: !!(latest && latest.selected)
    });
  }

  async function loadOrRunStrategy(plan, market, context, seq, forceRefresh) {
    if (forceRefresh === true) return runStrategy(plan, market, context, seq);
    var latest = await optionalFresh('/api/plans/' + encodeURIComponent(plan.id) + '/strategy/latest');
    if (seq !== state.requestSeq) return null;
    var strategy = latest && latest.strategy;
    var restored = latest && latest.selected;
    var reusable = strategy && String(strategy.state || '').toUpperCase() === 'CURRENT'
      && latest && latest.currency && latest.currency.current === true
      && strategy.result && Array.isArray(strategy.result.candidates);
    if (!reusable) return runStrategy(plan, market, context, seq);
    return publishStrategy(strategy, restored, market, seq, { restored: true });
  }

  async function selectCandidate(candidateId, seq) {
    var api = requireApi(), plan = state.plan;
    if (!plan) throw new Error('A Plan is required before selecting a strategy.');
    var local = state.candidates.find(function (candidate) {
      return String(candidate.id) === String(candidateId);
    });
    if (!local) throw new Error('The requested package is not part of the active ranked field.');
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
      throw new Error('The requested strategy selection could not be retained.');
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
        || !ensemble.currency || ensemble.currency.current !== true) {
      var rollover = new Error(ensemble && ensemble.currency && ensemble.currency.reason
        || 'The server could not bind this outcome fan to the current Plan and market receipts.');
      rollover.code = 'DESK_MARKET_ROLLOVER';
      throw rollover;
    }
    validateInitialEnsembleAnimation(ensemble, state.selected && state.selected.id);
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

  async function loadOrRunEnsembleAndOutcome(seq) {
    var planId = encodeURIComponent(state.plan.id);
    var ensemble;
    try {
      ensemble = await optionalFresh('/api/plans/' + planId + '/outcomes/ensemble/latest');
    } catch (error) {
      throw error;
    }
    if (seq !== state.requestSeq) return null;
    if (!ensemble || !ensemble.ensemble || !ensemble.preview
        || !ensemble.currency || ensemble.currency.current !== true) {
      return runEnsembleAndOutcome(seq);
    }
    if (!ensemble.plan || ensemble.plan.id !== state.plan.id) {
      throw new Error('The stored ensemble is not owned by the active Desk Plan.');
    }
    validateInitialEnsembleAnimation(ensemble, state.selected && state.selected.id);
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

  function rehearsalBasis(ensembleEnvelope) {
    var basis = String(ensembleEnvelope && ensembleEnvelope.ensemble
      && ensembleEnvelope.ensemble.basis || ensembleEnvelope && ensembleEnvelope.preview
      && ensembleEnvelope.preview.receipt && ensembleEnvelope.preview.receipt.basis || 'PARAMETRIC')
      .toUpperCase();
    return basis === 'HISTORICAL_ANALOGS' || basis === 'CONDITIONAL_BOOTSTRAP'
      ? 'APPROXIMATE_HISTORICAL' : 'PROJECTED_FORWARD';
  }

  async function readRehearsals(planId, options) {
    options = options || {};
    planId = String(planId || '').trim();
    if (!planId) throw new Error('A Plan is required before loading its rehearsals.');
    var rows;
    try {
      var response = await requireApi().getFresh('/api/plans/' + encodeURIComponent(planId) + '/rehearsals');
      rows = response && Array.isArray(response.rehearsals) ? response.rehearsals : [];
      rows.forEach(function (row) {
        if (!row || !row.worldId || !row.ensembleId || !row.fingerprint || !row.selection) {
          throw new Error('A stored rehearsal omitted its durable Plan, ensemble, or path identity.');
        }
      });
      state.rehearsalRestoreError = null;
    } catch (error) {
      if (options.optional !== true) throw error;
      rows = [];
      state.rehearsalRestoreError = error && error.message
        || 'Stored rehearsals could not be restored.';
    }
    if (state.plan && String(state.plan.id) === planId) {
      state.rehearsals = rows.slice();
      if (options.notify !== false) notify('rehearsals', {
        operation: 'rehearsals', plan: state.plan, rehearsals: state.rehearsals
      });
    }
    return rows;
  }

  async function createRehearsal(options) {
    options = options || {};
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var plan = state.plan, envelope = state.ensemble, ensemble = envelope && envelope.ensemble;
    if (!plan || !mutableWorkingPlan(plan)) {
      throw new Error('Create a fresh linked working Plan before rehearsing this position.');
    }
    if (!ensemble || !ensemble.id || !ensemble.fingerprint) {
      throw new Error('The exact package needs one stored outcome ensemble before it can be rehearsed.');
    }
    var selection = String(options.selection || 'TYPICAL').toUpperCase();
    if (['RANDOM','TYPICAL','FAVORABLE','ADVERSE','STRESS','SAMPLE'].indexOf(selection) < 0) {
      throw new Error('Choose a supported stored-path rehearsal.');
    }
    var pathIndex = options.pathIndex == null ? null : number(options.pathIndex);
    if (pathIndex != null && (!Number.isInteger(pathIndex) || pathIndex < 0)) {
      throw new Error('A sampled rehearsal path needs a non-negative whole index.');
    }
    var seq = ++state.requestSeq, mutationOwner = beginMutation('rehearsal');
    var basis = rehearsalBasis(envelope);
    state.rehearsal = {
      phase: 'creating', planId: plan.id, ensembleId: ensemble.id,
      fingerprint: ensemble.fingerprint, selection: selection, basis: basis, error: null
    };
    notify('rehearsal-loading', {
      operation: 'rehearsal', plan: plan, rehearsal: state.rehearsal
    });
    try {
      var response = await requireApi().post('/api/plans/' + encodeURIComponent(plan.id) + '/rehearsals', {
        expectedVersion: plan.version,
        ensembleId: ensemble.id,
        selection: selection,
        pathIndex: pathIndex,
        speed: options.speed == null ? 26 : Number(options.speed)
      });
      if (seq !== state.requestSeq) return null;
      var created = response && response.rehearsal, returnedPlan = response && response.plan;
      if (!created || String(created.planId || '') !== String(plan.id)
          || String(created.ensembleId || '') !== String(ensemble.id)
          || String(created.fingerprint || '') !== String(ensemble.fingerprint)
          || String(created.selection || '').toUpperCase() !== selection
          || !created.worldId) {
        throw new Error('The rehearsal response did not retain this Plan, ensemble, and selected path.');
      }
      acceptPlan(returnedPlan || plan);
      state.rehearsal = Object.assign({ phase: 'ready', basis: basis, error: null }, created);
      state.rehearsals = await readRehearsals(plan.id, { notify: false });
      if (seq !== state.requestSeq) return null;
      notify('rehearsal-ready', {
        operation: 'rehearsal', plan: state.plan, rehearsal: state.rehearsal,
        rehearsals: state.rehearsals
      });
      return state.rehearsal;
    } catch (error) {
      if (seq === state.requestSeq) {
        state.rehearsal = Object.assign({}, state.rehearsal, {
          phase: 'error', error: error && error.message || 'The rehearsal could not be created.'
        });
        notify('rehearsal-error', {
          operation: 'rehearsal', plan: state.plan, rehearsal: state.rehearsal, error: error
        });
      }
      throw error;
    } finally {
      endMutation(mutationOwner);
    }
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
      throw new Error('The order preview is not bound to the selected strategy.');
    }
    assertOrderEcho(preview, body);
    acceptPlan(preview.plan);
    if (state.deskPickId != null
        && String(state.deskPickId) === String(preview.selected.id)
        && !(preview.endorsement && preview.endorsement.endorsed === true
          && String(preview.endorsement.candidateId || '') === String(preview.selected.id))) {
      // The exact selected MARKET package owns the final backend promotion receipt. A missing or
      // negative receipt fails closed; the browser does not reinterpret price or guardrail fields.
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

  function assertOrderEcho(envelope, body) {
    var order = envelope && envelope.order, price = envelope && envelope.preview && envelope.preview.price;
    if (!order) throw new Error('The order preview omitted its execution receipt.');
    var expected = body.orderInstruction || {}, actual = order.orderInstruction || {};
    // Quantity is a component of the package-price receipt now — the order node no longer carries
    // a second copy of it.
    if (Number(price && price.quantity) !== Number(body.qty)
        || String(actual.type || '').toUpperCase() !== String(expected.type || '').toUpperCase()
        || String(actual.timeInForce || '').toUpperCase() !== String(expected.timeInForce || '').toUpperCase()
        || (expected.type === 'LIMIT' && Number(actual.limitNetCents) !== Number(expected.limitNetCents))) {
      throw new Error('The order preview does not match the current quantity and execution instruction.');
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
    var instruction = String(order.instruction || order.type || '').toUpperCase();
    var timeInForce = String(order.timeInForce || 'DAY').toUpperCase();
    var qty = Number(order.qty || 1);
    if (!instruction) throw new Error('Choose an execution instruction; options packages do not default to MARKET.');
    if (instruction !== 'MARKET' && instruction !== 'LIMIT') throw new Error('Execution instruction must be MARKET or LIMIT.');
    if (timeInForce !== 'DAY') throw new Error('The current execution workflow supports DAY instructions.');
    if (!Number.isInteger(qty) || qty < 1 || qty > 100) throw new Error('Order quantity must be a whole number from 1 to 100.');
    var body = {
      expectedVersion: plan.version,
      qty: qty,
      proceedWithoutEndorsement: order.proceedWithoutEndorsement === true,
      refreshEvidence: order.refreshEvidence === true,
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

  var workspaceLoadPromise = null;
  var workspacePatchTimer = null;
  var workspacePatchPending = {};
  var workspacePatchWaiters = [];
  var workspacePatchInFlight = null;
  var workspacePatchActive = null;
  var workspaceEvents = null;
  var workspaceEventRefresh = null;
  var lastWorldClearKey = null;

  function workspaceRev(receipt) {
    var value = Number(receipt && receipt.rev);
    return Number.isFinite(value) && value >= 0 ? value : 0;
  }

  function workspaceWorld(receipt) {
    var context = receipt && receipt.context;
    return String(context && context.world || receipt && receipt.world || '').trim();
  }

  function workspaceLane(receipt) {
    var context = receipt && receipt.context;
    return String(context && context.marketLane || receipt && receipt.marketLane || '')
      .trim().toUpperCase();
  }

  function workspaceDataset(receipt) {
    var context = receipt && receipt.context;
    return String(context && context.datasetId || receipt && receipt.datasetId || '').trim();
  }

  function workspaceAccount(receipt) {
    var context = receipt && receipt.context;
    return String(context && context.accountId || receipt && receipt.accountId || '').trim();
  }

  function sameWorkspaceReceipt(left, right) {
    if (!left || !right) return false;
    return workspaceRev(left) === workspaceRev(right)
      && workspaceMarketIdentity(left) === workspaceMarketIdentity(right);
  }

  function validateWorkspaceReceipt(receipt) {
    if (!receipt || typeof receipt !== 'object') {
      throw new Error('The workspace endpoint did not return its typed receipt.');
    }
    if (Number(receipt.supportedVersion || WORKSPACE_VERSION) !== WORKSPACE_VERSION) {
      throw new Error('This Desk cannot read workspace context version '
        + String(receipt.supportedVersion) + '.');
    }
    if (receipt.context && Number(receipt.context.version) !== WORKSPACE_VERSION) {
      throw new Error('The workspace context version did not match this Desk.');
    }
    return receipt;
  }

  function overwriteWorkspaceContext(receipt) {
    var context = receipt.context;
    var reset = emptyWorkspaceContext();
    var query = workspaceContext.query || '';
    Object.keys(reset).forEach(function (field) {
      workspaceContext[field] = reset[field];
    });
    workspaceContext.query = query;
    if (context) {
      Object.keys(context).forEach(function (field) {
        workspaceContext[field] = context[field];
      });
    }
    workspaceContext.version = WORKSPACE_VERSION;
    workspaceContext.world = context && context.world != null
      ? context.world : (receipt.world == null ? null : receipt.world);
    workspaceContext.datasetId = context && context.datasetId != null
      ? context.datasetId : (receipt.datasetId == null ? null : receipt.datasetId);
    workspaceContext.marketLane = context && context.marketLane != null
      ? context.marketLane : (receipt.marketLane == null ? null : receipt.marketLane);
    workspaceContext.accountId = context && context.accountId != null
      ? context.accountId : (receipt.accountId == null ? null : receipt.accountId);
    var lane = String(workspaceContext.marketLane || '').toUpperCase();
    var world = String(workspaceContext.world || '').toLowerCase();
    if (lane === 'OBSERVED' || lane === 'DEMO') baselineWorld = world;
    workspaceContext.marketMode = lane === 'SIMULATED' ? 'sim' : 'observed';
    workspaceContext.rev = workspaceRev(receipt);
  }

  function applyWorkspacePatchLocally(patch) {
    Object.keys(patch || {}).forEach(function (field) {
      if (WORKSPACE_FIELDS.indexOf(field) < 0) return;
      workspaceContext[field] = patch[field] == null ? null : patch[field];
    });
  }

  function preserveQueuedWorkspaceIntent() {
    // An ambient receipt can arrive while the current batch is on the wire. Reapply BOTH that
    // in-flight intent and the next queued intent, in order, so neither an SSE refresh nor a
    // conflict re-read can visibly roll the user's latest declarations backwards.
    applyWorkspacePatchLocally(workspacePatchActive);
    applyWorkspacePatchLocally(workspacePatchPending);
  }

  function discardQueuedMarketOwnedWorkspaceIntent() {
    // An external/cross-tab world event can arrive while a local PATCH is in flight. Retain the
    // user's declarations, but remove focus that was authored against the market being left from
    // both the active retry object and the next queued batch. Mutating the active object is
    // intentional: sendWorkspacePatch's one conflict retry then cannot persist old-market focus
    // into the newly accepted identity.
    [workspacePatchActive, workspacePatchPending].forEach(function (patch) {
      if (!patch) return;
      WORKSPACE_MARKET_OWNED_FIELDS.forEach(function (field) {
        delete patch[field];
      });
    });
  }

  function worldClearKey(receipt) {
    // Revisions change for ordinary declarations too. Financial artifacts belong to the market
    // identity, not a workspace revision; one actual market transition clears them exactly once.
    return workspaceMarketIdentity(receipt);
  }

  function workspaceMarketIdentity(receipt) {
    // Revision/generation metadata can advance without selecting different market facts, so it
    // cannot own market artifacts by itself. The canonical identity is the complete set of
    // selectors that chooses the market/account data.
    return [
      workspaceWorld(receipt),
      workspaceDataset(receipt),
      workspaceLane(receipt),
      workspaceAccount(receipt)
    ].join('|');
  }

  function workspaceReceiptDisposition(current, incoming) {
    if (!current) return 'newer';
    var currentRev = workspaceRev(current);
    var incomingRev = workspaceRev(incoming);
    if (incomingRev < currentRev) return 'stale';
    if (incomingRev > currentRev) return 'newer';
    return sameWorkspaceReceipt(current, incoming) ? 'duplicate' : 'inconsistent';
  }

  function clearAuthoritativeArtifacts() {
    bookRequestSeq++;
    bookContextRequestSeq++;
    positionRequestSeq++;
    positionScenarioRequestSeq++;
    state.requestSeq++;
    state.animationSeq++;
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
    state.adoptedEvaluationId = null;
    state.adoptionError = null;
    state.animation = null;
    state.rehearsal = null;
    state.rehearsals = [];
    state.rehearsalRestoreError = null;
    state.context = null;
    state.book = null;
    state.position = null;
    state.positionScenario = null;
    // These maps coalesce requests; they are not a second freshness cache. Even so, an old-world
    // in-flight promise must never be handed to the first consumer in a newly accepted market.
    bookContextLoads = {};
    bookPositionDetailLoads = {};
  }

  function cancelSlowMarketWork() {
    cancelScout();
    if (window.API && typeof window.API.beginNavigation === 'function') {
      window.API.beginNavigation();
    }
  }

  function adoptWorkspaceReceipt(raw, options) {
    options = options || {};
    var receipt = validateWorkspaceReceipt(raw);
    var prior = state.workspace.receipt;
    var disposition = workspaceReceiptDisposition(prior, receipt);
    // An undeclared (or deliberately unreadable) workspace has no stored context row the server
    // can revise during a world switch. Its authoritative top-level market identity therefore
    // moves while the workspace revision legitimately stays unchanged. Accept that one explicit
    // transition shape; ordinary same-revision disagreements remain unsafe and are refused.
    var contextlessMarketMove = disposition === 'inconsistent'
      && options.worldTransition === true
      && prior && !prior.context && !receipt.context
      && workspaceMarketIdentity(prior) !== workspaceMarketIdentity(receipt);
    // Revision is the server's serialization order. A delayed event/GET must never roll the
    // retained workspace back, and two identities at one revision are not safe to guess between.
    if (disposition === 'stale' || (disposition === 'inconsistent' && !contextlessMarketMove)) {
      preserveQueuedWorkspaceIntent();
      return prior;
    }
    if (disposition === 'duplicate') {
      if (window.API && typeof window.API.acceptMarketIdentity === 'function') {
        window.API.acceptMarketIdentity(workspaceMarketIdentity(receipt));
      }
      state.workspace.phase = 'ready';
      state.workspace.error = null;
      preserveQueuedWorkspaceIntent();
      return prior;
    }
    var priorWorld = workspaceWorld(prior);
    var nextWorld = workspaceWorld(receipt);
    var worldChanged = !!prior && !!priorWorld && priorWorld !== nextWorld;
    var marketIdentityChanged = !!prior
      && workspaceMarketIdentity(prior) !== workspaceMarketIdentity(receipt);
    var artifactsCleared = false;
    if (marketIdentityChanged || options.worldTransition === true) {
      discardQueuedMarketOwnedWorkspaceIntent();
    }
    // Adopt the cache namespace before publishing or starting any new-market read. This is the
    // single invalidation boundary used by HTTP loads, world PUTs, and workspace/world/dataset SSE.
    // The API client also clears the prior namespace, so returning to a recently visited identity
    // cannot replay its still-live TTL entry.
    if (window.API && typeof window.API.acceptMarketIdentity === 'function') {
      window.API.acceptMarketIdentity(workspaceMarketIdentity(receipt));
    }
    if (marketIdentityChanged || options.worldTransition === true) {
      var key = worldClearKey(receipt);
      if (lastWorldClearKey !== key) {
        lastWorldClearKey = key;
        cancelSlowMarketWork();
        clearAuthoritativeArtifacts();
        artifactsCleared = true;
      }
    }
    var changed = !sameWorkspaceReceipt(prior, receipt)
      || JSON.stringify(prior && prior.context || null) !== JSON.stringify(receipt.context || null)
      || JSON.stringify(prior && prior.transition || null) !== JSON.stringify(receipt.transition || null)
      || JSON.stringify(prior && prior.unreadable || null) !== JSON.stringify(receipt.unreadable || null);
    overwriteWorkspaceContext(receipt);
    if (options.optimisticPatch) applyWorkspacePatchLocally(options.optimisticPatch);
    if (options.preserveQueued === true) preserveQueuedWorkspaceIntent();
    state.workspace.phase = 'ready';
    state.workspace.receipt = receipt;
    state.workspace.context = workspaceContext;
    state.workspace.error = null;
    if (options.notify !== false && (changed || options.forceNotify)) {
      var phase = options.phase || (marketIdentityChanged || options.worldTransition
        ? 'world-transition' : 'workspace-ready');
      notify(phase, {
        operation: options.operation || 'workspace',
        workspace: receipt,
        artifactsCleared: artifactsCleared,
        target: nextWorld,
        world: options.world || null,
        config: {
          world: nextWorld,
          marketLane: workspaceLane(receipt)
        },
        transition: options.transition || receipt.transition || null,
        source: options.source || 'http'
      });
    }
    return receipt;
  }

  function startWorkspaceEvents() {
    if (workspaceEvents || !httpRuntime || typeof window.EventSource !== 'function') return;
    try {
      workspaceEvents = new window.EventSource('/api/events');
      workspaceEvents.addEventListener('workspace.updated', function (event) {
        var hint;
        try { hint = JSON.parse(event.data || '{}'); } catch (ignored) { return; }
        if (Number(hint.rev || 0) <= workspaceRev(state.workspace.receipt)) return;
        if (workspaceEventRefresh) return;
        workspaceEventRefresh = window.setTimeout(function () {
          workspaceEventRefresh = null;
          loadWorkspace({ source: 'sse' }).catch(function () {
            /* SSE is a hint. The owning surface keeps its last typed receipt on a failed refresh. */
          });
        }, 0);
      });
      workspaceEvents.addEventListener('world.selected', function (event) {
        var hint;
        try { hint = JSON.parse(event.data || '{}'); } catch (ignored) { return; }
        if (!hint.workspace) return;
        var changedMarket = workspaceMarketIdentity(state.workspace.receipt)
          !== workspaceMarketIdentity(hint.workspace);
        adoptWorkspaceReceipt(hint.workspace, {
          source: 'sse', phase: changedMarket ? 'world-transition' : 'workspace-ready',
          operation: changedMarket ? 'market-transition' : 'workspace',
          worldTransition: changedMarket, preserveQueued: true,
          world: hint, transition: hint
        });
      });
      workspaceEvents.addEventListener('dataset.selected', function (event) {
        var hint;
        try { hint = JSON.parse(event.data || '{}'); } catch (ignored) { return; }
        if (hint.workspace) {
          var changedMarket = workspaceMarketIdentity(state.workspace.receipt)
            !== workspaceMarketIdentity(hint.workspace);
          adoptWorkspaceReceipt(hint.workspace, {
            source: 'sse', phase: changedMarket ? 'world-transition' : 'workspace-ready',
            operation: changedMarket ? 'market-transition' : 'workspace',
            worldTransition: changedMarket, preserveQueued: true,
            world: hint, transition: hint
          });
          return;
        }
        // Legacy dataset hints carry only an id. Re-read the typed Workspace receipt instead of
        // constructing the rest of its market identity in the browser.
        loadWorkspace({ source: 'dataset-sse' }).catch(function () {
          /* The last typed receipt remains visible when this additive refresh is unavailable. */
        });
      });
    } catch (ignored) {
      workspaceEvents = null;
    }
  }

  async function transitionWorld(mode, context) {
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var mutationOwner = beginMutation('world');
    ++state.requestSeq;
    state.animationSeq++;
    var transitionRequest = Object.assign({}, context || state.context || {});
    var reopen = transitionRequest.reopen !== false;
    var requestedWorldId = transitionRequest.targetWorldId == null
      ? '' : String(transitionRequest.targetWorldId).trim();
    var requestedContext = normalizeIdeaDeclaration(transitionRequest);
    // Plan, lineage, and one-shot evaluation ids belong to the old market. Only the user's
    // declarations cross a world boundary; the new world receives a fresh Plan identity.
    requestedContext.planId = null;
    requestedContext.originPlanId = null;
    var symbol = String(requestedContext.symbol || state.context && state.context.symbol || '').trim().toUpperCase();
    var target = null, verification = null;
    var returningToBase = String(mode || '').toLowerCase() === 'observed';
    function stillOwnsTransition() {
      return activeMutationOwner === mutationOwner && !activeMutationCancelled;
    }
    try {
      state.error = null;
      notify('loading', { operation: 'market-transition' });
      // Preserve declarations the user changed immediately before the market switch. The
      // workspace queue is the only writer, so draining it here is a serialization boundary—not
      // a second save path. Abort long reads (especially Scout) as soon as the user's intent is
      // known; old-world artifacts remain visible until the server commits the new world.
      await drainWorkspacePatches();
      cancelSlowMarketWork();
      if (returningToBase) {
        // A session may boot directly inside a persisted simulation, so do not rely solely on a
        // previously visited baseline receipt. The current-world endpoint names the installation's
        // baseline explicitly; it is the same WorldTransitionService authority that validates PUT.
        var currentWorld = await requireApi().getFresh('/api/world');
        if (currentWorld && currentWorld.baselineWorld) {
          baselineWorld = String(currentWorld.baselineWorld).trim();
        }
        if (!baselineWorld) {
          throw new Error('The server did not identify the baseline market world.');
        }
        target = baselineWorld;
      } else {
        if (!symbol) throw new Error('Choose an underlying before creating a simulated market.');
        var session = requestedWorldId
          ? await waitForPreparedWorld(requestedWorldId, symbol)
          : await simulatedWorldFor(symbol);
        if (!stillOwnsTransition()) return null;
        target = session.id;
        if (String(session.status || '').toUpperCase() !== 'RUNNING') {
          await requireApi().post('/api/sim/market/' + encodeURIComponent(target) + '/start', {});
          if (!stillOwnsTransition()) return null;
        }
      }
      var transitioned = await requireApi().put('/api/world', { world: target });
      /*
       * The real server publishes world.selected over SSE before the PUT response can arrive.
       * Accepting that event correctly clears the old idea and advances state.requestSeq. The
       * transition used to interpret its own accepted SSE as a competing navigation and return
       * here, leaving the Desk permanently at "world-transition". Mutation ownership—not the
       * invalidated old-idea sequence—is the serialization boundary for this PUT.
       */
      if (!stillOwnsTransition()) return null;
      var embedded = transitioned && transitioned.workspace;
      var acceptedLane = workspaceLane(embedded);
      /*
       * "Observed" is the product's return-to-base command, not a promise that every
       * installation's base lane is literally named OBSERVED. Provider-isolated builds
       * correctly return the canonical DEMO lane here. The server-owned Workspace receipt
       * is the authority; validate the coherent lane/world pair it returned instead of
       * relabelling DEMO evidence as observed in the browser.
       */
      var acceptedBaseLane = returningToBase
        && (acceptedLane === 'OBSERVED' || acceptedLane === 'DEMO');
      var acceptedSimulatedLane = !returningToBase && acceptedLane === 'SIMULATED';
      if (!embedded || String(transitioned.world || '') !== String(target)
          || workspaceWorld(embedded) !== String(target)
          || (!acceptedBaseLane && !acceptedSimulatedLane)) {
        throw new Error('The server did not confirm one coherent market transition.');
      }
      var priorWorld = workspaceWorld(state.workspace.receipt);
      var changedMarket = !!state.workspace.receipt
        && workspaceMarketIdentity(state.workspace.receipt) !== workspaceMarketIdentity(embedded);
      adoptWorkspaceReceipt(embedded, {
        source: 'world-put', phase: changedMarket ? 'world-transition' : 'workspace-ready',
        operation: changedMarket ? 'market-transition' : 'workspace',
        worldTransition: changedMarket || priorWorld !== String(target), world: transitioned,
        transition: transitioned
      });
      verification = {
        target: target,
        world: transitioned,
        config: { world: target, marketLane: acceptedLane },
        transition: transitioned,
        workspace: embedded
      };
    } catch (error) {
      if (!stillOwnsTransition()) return null;
      state.error = error;
      notify('error', { operation: 'market-transition', error: error });
      throw error;
    } finally {
      endMutation(mutationOwner);
    }
    if (!verification) return null;
    if (!symbol || !reopen) return verification;
    return openIdea(requestedContext);
  }

  async function openIdea(context, options) {
    options = options || {};
    if (state.mutationPending) {
      if (!activeMutationCancelled) {
        throw new Error('Wait for the current Plan change to finish.');
      }
      pendingIdeaContext = {
        context: normalizeIdeaDeclaration(context),
        options: Object.assign({}, options)
      };
      notify('loading', { operation: 'idea-queued' });
      return null;
    }
    var mutationOwner = beginMutation('idea');
    var seq = ++state.requestSeq;
    state.animationSeq++;
    var rawContext = Object.assign({}, context || {});
    var marketRolloverRetries = Math.max(0, Number(
      options.marketRolloverRetries == null
        ? rawContext.__marketRolloverRetries || 0 : options.marketRolloverRetries));
    var evaluationId = options.evaluationId == null
      ? rawContext.evaluationId : options.evaluationId;
    var governorRefresh = options.strategyRefresh === true;
    context = normalizeIdeaDeclaration(rawContext);
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
    // Adoption belongs to one exact Scout command. A later ordinary New Idea must never inherit
    // either the prior evaluation id or its refusal message.
    state.adoptedEvaluationId = null;
    state.adoptionError = null;
    state.animation = null;
    state.rehearsal = null;
    state.rehearsals = [];
    state.rehearsalRestoreError = null;
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
      // The traded expiration is chosen against the DECLARED horizon. Substituting 45 sessions
      // for an undeclared one silently anchored the whole Desk — chain, greeks, payoff, every
      // candidate — to an expiration nobody asked for. Without a declaration there is nothing
      // to choose against, and the ranking below already withholds on the same grounds.
      // The traded expiration is chosen against the DECLARED horizon. Substituting 45 sessions for
      // an undeclared one anchored the whole surface — chain, greeks, payoff, every candidate — to
      // an expiration nobody asked for, and said nothing about it. Undeclared now means the
      // NEAREST listed expiration, and the market receipt states which basis was used, so the
      // chain on screen is never mistaken for one the user's horizon selected.
      var market = await loadMarket(symbol, declaredHorizon, seq);
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
      // A Scout row names the exact evaluation it displayed. Adopt THAT package as the Plan's
      // structure before any competition is run, so the row the user clicked is the row that
      // opens (audit §8.2). The server reloads it from its own persisted receipt and refuses if
      // the declared brief differs; the reason is reported, never papered over with a substitute.
      var adoptedRun = null;
      var adoptedCandidate = null;
      if (evaluationId) {
        try {
          var adopted = await requireApi().post(
            '/api/plans/' + encodeURIComponent(plan.id) + '/strategy/adopt',
            { expectedVersion: plan.version, evaluationId: String(evaluationId) });
          if (seq !== state.requestSeq) return null;
          if (adopted && adopted.plan) { plan = adopted.plan; acceptPlan(plan); }
          adoptedRun = adopted && adopted.strategy;
          adoptedCandidate = adoptedRun && adoptedRun.result
            && (adoptedRun.result.candidate || (adoptedRun.result.candidates || [])[0]);
          if (!adoptedRun || String(adopted && adopted.evaluationId || '') !== String(evaluationId)
              || !adoptedCandidate
              || String(adoptedCandidate.sourceEvaluationId || '') !== String(evaluationId)) {
            throw new Error(
              'The server did not return the exact scanned package under its evaluation identity.');
          }
          state.adoptedEvaluationId = String(evaluationId);
          state.adoptionError = null;
        } catch (adoptionFailure) {
          state.adoptedEvaluationId = null;
          state.adoptionError = adoptionFailure && adoptionFailure.message
            || 'That scanned package could not be adopted.';
          notify('adoption-unavailable', {
            operation: 'strategy-adoption',
            evaluationId: String(evaluationId),
            error: state.adoptionError,
            plan: plan
          });
          // The clicked Scout row is an exact-package command, not a request to rerun the
          // declaration. If adoption fails, stop here. Falling into loadOrRunStrategy would
          // replace the requested package with a newly ranked field that merely shares its
          // ticker and declarations.
          return copyState();
        }
      }
      // Running a competition on a Plan that just adopted a package is what LOSES the package —
      // the ranked field would replace the exact row the user clicked. So an adopted run is
      // published as-is, and the competition is only run when nothing was adopted.
      var candidates = adoptedRun
        ? await publishStrategy(adoptedRun,
            adoptedCandidate,
            market, seq, { adopted: true })
        : await loadOrRunStrategy(plan, market, state.context, seq, governorRefresh);
      if (!candidates || seq !== state.requestSeq) return null;
      // A current, fingerprinted competition with no candidates is a valid backend result. The
      // Desk keeps the declaration, evidence, and screening receipts visible and waits for an
      // explicit assumption change instead of fabricating a package or entering outcome/preview.
      if (!candidates.length) return copyState();
      var candidate = state.selected || (state.deskPickId == null ? null : candidates.find(function (row) {
        return String(row.id) === String(state.deskPickId);
      })) || (options.autoSelect === false ? null : candidates[0]);
      /*
       * A ranking is not an endorsement, but an adverse field is still an analysis result.
       * Requiring a second click left the largest two columns empty after the user had already
       * opened an exact idea. Select the first backend-ranked comparison as the active subject so
       * payoff, paths, legs, market evidence and the non-endorsement receipt are immediately
       * available. `deskPickId` deliberately remains null, so no recommendation badge or favorable
       * language is invented.
      */
      if (!candidate) {
        notify('comparison-required', {
          operation: 'comparison-selection',
          plan: state.plan,
          candidates: state.candidates,
          notes: state.strategyNotes,
          message: 'The exact package workflow is preparing its selected structure.'
        });
        return copyState();
      }
      if (!state.selected) {
        try {
          await selectCandidate(candidate.id, seq);
        } catch (error) {
          if (seq !== state.requestSeq) return null;
          notify('comparison-required', {
            operation: 'comparison-selection',
            plan: state.plan,
            candidates: state.candidates,
            notes: state.strategyNotes,
            error: error,
            message: 'The first ranked comparison could not be opened automatically. Choose a comparison to retry.'
          });
          return copyState();
        }
        if (seq !== state.requestSeq) return null;
      }
      var result = await loadOrRunEnsembleAndOutcome(seq);
      if (!result || seq !== state.requestSeq) return null;
      await loadDecisionState(seq);
      if (seq !== state.requestSeq) return null;
      await previewDecision(candidateLimitOrder(candidate), seq);
      if (seq !== state.requestSeq) return null;
      state.rehearsals = await readRehearsals(state.plan.id, {
        notify: false, optional: true
      });
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
        return openIdea(state.context || context, {
          marketRolloverRetries: marketRolloverRetries + 1
        });
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
    if (!state.plan || !state.market) return openIdea(context || {});
    if (state.mutationPending) throw new Error('Wait for the current Plan change to finish.');
    var mutationOwner = beginMutation('declaration');
    var seq = ++state.requestSeq;
    state.animationSeq++;
    state.error = null;
    var next = normalizeIdeaDeclaration(Object.assign(
      {}, state.context || {}, context || {}, { planId: state.plan.id }));
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
      state.planIdentity = identity;
      state.plan = updated;
      state.context = contextFromPlan(null, updated);
      projectAcceptedPlanToWorkspace(state.context);
      notify('declaration', {
        operation: 'declaration', plan: updated, context: Object.assign({}, state.context)
      });
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
          state.context = contextFromPlan(null, currentPlan);
          projectAcceptedPlanToWorkspace(state.context);
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
    return openIdea(state.context);
  }

  async function chooseCandidate(candidateId) {
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
      /* Candidate/package changes are child valuations over one immutable price ensemble, but
         the preview canvas is candidate-specific. Re-read the canonical stored envelope so its
         PROPOSED:<candidate> valuation moves with the selection; reusing only state.ensemble and
         rerunning Outcome left the prior package's canvas attached to the new row. The existing
         restore-or-run owner preserves the stored ensemble identity when it is current and builds
         one only for the first explicit comparison (or when the server declares it stale). */
      var evaluation = await loadOrRunEnsembleAndOutcome(seq);
      var outcome = evaluation && evaluation.outcome;
      if (!outcome || seq !== state.requestSeq) return null;
      await loadDecisionState(seq);
      if (seq !== state.requestSeq) return null;
      await previewDecision(candidateLimitOrder(state.selected), seq);
      notify('ready', { operation: 'candidate' });
      return copyState();
    } catch (error) {
      return fail(seq, 'candidate', error);
    } finally {
      endMutation(mutationOwner);
    }
  }

  async function repreviewOrder(order) {
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
      assertOrderEcho(state.decisionPreview, body);
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
            var found = practiceBookTrades(data).some(function (trade) {
              return String(trade && trade.id) === committedTradeId;
            });
            if (!found) {
              await new Promise(function (resolve) { window.setTimeout(resolve, 180); });
              data = await loadBook();
              found = practiceBookTrades(data).some(function (trade) {
                return String(trade && trade.id) === committedTradeId;
              });
            }
            if (!found) throw new Error('The committed trade has not appeared in the authoritative Book roster yet.');
            notify('commit-reconciled', {
              operation: 'order-commit', response: response,
              tradeId: committedTradeId, detached: detached
            });
            /* Keep the confirmed id on the current Book receipt. The roster owns the
               "opened just now" acknowledgement; clearing it immediately after notifying the
               UI let a concurrent Book read erase the highlight before the user ever saw it.
               A later commitment replaces this single session-local id. */
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

  /* PositionAnimation v2 is the only lifecycle contract consumed by the desk. Validate the
     array join and named terminal boundary at the transport edge so no surface can quietly fall
     back to browser date arithmetic, an earliest leg, or an inferred last frame. */
  function assertPositionAnimationV2(checkpoints, position, label, projection) {
    checkpoints = checkpoints || {};
    position = position || {};
    projection = projection || {};
    var track = checkpoints.animation || {};
    var animation = position.animation || {};
    var underlying = Array.isArray(checkpoints.underlyingSteps)
      ? checkpoints.underlyingSteps : [];
    var steps = Array.isArray(position.steps) ? position.steps : [];
    var stepBands = Array.isArray(position.stepBands) ? position.stepBands : [];
    var displayPaths = Array.isArray(position.displayPaths) ? position.displayPaths : [];
    var projectionBands = Array.isArray(projection.bands) ? projection.bands : [];
    var projectionPaths = Array.isArray(projection.paths) ? projection.paths : [];
    var projectionReceipt = projection.receipt || {};
    var frameCount = number(animation.frameCount);
    var terminal = number(animation.terminalFrameIndex);
    var terminalSession = number(animation.terminalSessionProgress);
    var trackFrameCount = number(track.frameCount);
    var terminalUnderlying = Number.isInteger(terminal) ? underlying[terminal] : null;
    var terminalPosition = Number.isInteger(terminal) ? steps[terminal] : null;
    var underlyingSession = number(terminalUnderlying && terminalUnderlying.sessionProgress);
    var positionSession = number(terminalPosition && terminalPosition.sessionProgress);
    var reason = String(animation.boundaryReason || '');
    var resolved = animation.exposureResolvedAtBoundary;
    var finalExpiration = animation.finalOptionExpiration;
    var lifecycleConsistent = reason === 'FINAL_CASH_SETTLEMENT'
      ? resolved === true && typeof finalExpiration === 'string' && finalExpiration.length > 0
      : reason === 'HORIZON_END_STOCK_EXPOSURE'
        ? resolved === false
        : (reason === 'HORIZON_END_PHYSICAL_EXPOSURE'
            || reason === 'HORIZON_END_OPTION_OUTLIVES_TRACK')
          ? resolved === false
          : false;
    function sameGridPoint(row, expected) {
      var rowStep = number(row && row.step);
      var expectedStep = number(expected && expected.step);
      var rowSession = number(row && row.sessionProgress);
      var expectedSession = number(expected && expected.sessionProgress);
      return rowStep != null && expectedStep != null && rowStep === expectedStep
        && rowSession != null && expectedSession != null
        && Math.abs(rowSession - expectedSession) < 1e-7;
    }
    var positionGridAligned = underlying.length > 0 && underlying.every(function (row, index) {
      return sameGridPoint(steps[index], row);
    });
    var bandGridAligned = stepBands.length === underlying.length
      && underlying.every(function (row, index) {
        return sameGridPoint(stepBands[index], row);
      });
    var displayGridAligned = displayPaths.length > 0
      && displayPaths.length === projectionPaths.length
      && displayPaths.every(function (path, pathIndex) {
      var pathSteps = path && Array.isArray(path.steps) ? path.steps : [];
      var projectedPath = projectionPaths[pathIndex] || {};
      return number(path && path.sourcePathIndex) === number(projectedPath.sourcePathIndex)
        && String(path && path.role || '') === String(projectedPath.role || '')
        && pathSteps.length === underlying.length && underlying.every(function (row, index) {
        return sameGridPoint(pathSteps[index], row);
      });
    });
    var projectionGridAligned = projectionBands.length === underlying.length
      && underlying.every(function (row, index) {
        return sameGridPoint(projectionBands[index], row);
      })
      && projectionPaths.length > 0
      && projectionPaths.every(function (path) {
        return path && Array.isArray(path.prices) && path.prices.length === underlying.length;
      })
      && number(projectionReceipt.returnedPointCount) === underlying.length;
    var valid = track.frameRule === 'SELECT_NEAREST_FRAME_NO_INTERPOLATION'
      && track.frameSource === 'underlyingSteps'
      && track.positionFrameSource === 'positions[].steps'
      && Number.isInteger(trackFrameCount) && trackFrameCount > 0
      && Number.isInteger(frameCount) && frameCount === trackFrameCount
      && frameCount === underlying.length && frameCount === steps.length
      && positionGridAligned && bandGridAligned && displayGridAligned
      && projectionGridAligned
      && Number.isInteger(terminal) && terminal >= 0 && terminal < frameCount
      && terminalSession != null
      && underlyingSession != null && Math.abs(underlyingSession - terminalSession) < 1e-7
      && positionSession != null && Math.abs(positionSession - terminalSession) < 1e-7
      && lifecycleConsistent
      && typeof animation.exposureResolvedAtBoundary === 'boolean'
      && animation.unavailableReason == null;
    if (!valid) {
      throw new Error((label || 'Scenario') + ' omitted the exact PositionAnimation v2 lifecycle and frame-selection contract.');
    }
    return {
      available: true,
      frameCount: frameCount,
      terminalFrameIndex: terminal,
      terminalSessionProgress: terminalSession,
      finalOptionExpiration: finalExpiration || null,
      boundaryReason: reason,
      exposureResolvedAtBoundary: resolved,
      unavailableReason: null
    };
  }

  /*
   * The initial, unconditioned fan and a subsequently pinned fan carry the same lifecycle facts
   * in two wire envelopes. Normalize the initial ensemble at this transport boundary, then run the
   * ONE validator above. Renderers consume only `validatedAnimationBoundary`; they never infer a
   * terminal frame merely because the initial response used preview.samples instead of paths[].
   */
  function validateInitialEnsembleAnimation(envelope, candidateId) {
    var preview = envelope && envelope.preview || {};
    var checkpoints = preview.canvas || {};
    var positionKey = 'PROPOSED:' + String(candidateId || '');
    var position = Array.isArray(checkpoints.positions)
      ? checkpoints.positions.find(function (row) {
        return row && String(row.key || '') === positionKey;
      }) : null;
    var samples = Array.isArray(preview.samples) ? preview.samples : [];
    var sourceIndices = Array.isArray(preview.sampleSourcePathIndices)
      ? preview.sampleSourcePathIndices : [];
    var focusIndex = number(preview.sampleFocusIndex);
    if (!candidateId || !position || !samples.length
        || samples.length !== sourceIndices.length || !Number.isInteger(focusIndex)
        || focusIndex < 0 || focusIndex >= samples.length) {
      throw new Error('The stored idea fan omitted its selected package or representative-path identity.');
    }
    var projection = {
      /*
       * `preview.stepBands` are the underlying market-price bands. PositionAnimation validates
       * the selected package's P/L grid, whose authoritative band receipt lives on that exact
       * position. Comparing these different financial domains happened to match in point count
       * but not in frame identity, so every otherwise valid saved fan was rejected.
       */
      bands: position.stepBands,
      paths: samples.map(function (prices, index) {
        return {
          sourcePathIndex: sourceIndices[index],
          role: index === focusIndex ? 'FOCUS' : 'CONTEXT',
          prices: prices
        };
      }),
      receipt: {
        returnedPointCount: Array.isArray(checkpoints.underlyingSteps)
          ? checkpoints.underlyingSteps.length : null
      }
    };
    checkpoints.validatedAnimationBoundary = assertPositionAnimationV2(
      checkpoints, position, 'The selected idea fan', projection);
    return envelope;
  }

  /**
   * Select and value paths from the already-stored fan. This call is pure: it does not save a
   * scenario or create a new ensemble, and its response is the only financial source used by
   * the animation frames.
   */
  async function scenarioAnimation(scenario) {
    if (!state.plan || !state.ensemble) return null;
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
    var body = { ensembleId: state.ensemble.ensemble.id, limit: 48 };
    var interaction = scenario && scenario.interaction || null;
    var waypoints = scenario && Array.isArray(scenario.waypoints) ? scenario.waypoints : [];
    var pathWaypoints = scenario && Array.isArray(scenario.pathWaypoints)
      ? scenario.pathWaypoints : [];
    if (interaction) body.interaction = interaction;
    else if (pathWaypoints.length) body.pathWaypoints = pathWaypoints;
    else if (waypoints.length) body.waypoints = waypoints;
    else throw new Error('A scenario interaction or explicit stored-fan waypoint is required.');
    requestIdentity.interaction = interaction ? canonicalJson(interaction) : null;
    requestIdentity.waypoints = interaction || pathWaypoints.length ? [] : waypoints;
    requestIdentity.pathWaypoints = interaction ? [] : pathWaypoints;
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
      var proposedPosition = Array.isArray(checkpoints.positions)
        ? checkpoints.positions.find(function (row) {
          return row && String(row.key || '') === 'PROPOSED:' + requestIdentity.candidateId;
        }) : null;
      var returnedWaypoints = receipt.conditioningAssumptions
        && receipt.conditioningAssumptions.waypoints || [];
      var returnedPathWaypoints = receipt.conditioningPathWaypoints || [];
      var scenarioIdentityMismatch = requestIdentity.interaction
        ? !scenarioInteractionMatches(requestIdentity.interaction, receipt.interaction)
        : JSON.stringify(canonicalJson(returnedWaypoints))
            !== JSON.stringify(canonicalJson(requestIdentity.waypoints))
          || requestIdentity.pathWaypoints.length
            && JSON.stringify(canonicalJson(returnedPathWaypoints))
              !== JSON.stringify(canonicalJson(requestIdentity.pathWaypoints));
      if (!response || !response.plan || response.plan.id !== requestIdentity.planId
          || response.ensemble.id !== requestIdentity.ensembleId
          || response.ensemble.fingerprint !== requestIdentity.ensembleFingerprint
          || receipt.ensembleId !== requestIdentity.ensembleId
          || receipt.ensembleFingerprint !== requestIdentity.ensembleFingerprint
          || receipt.selectedCandidateId !== requestIdentity.candidateId
          || requestIdentity.contextRev != null && Number(receipt.contextRev) !== Number(requestIdentity.contextRev)
          || scenarioIdentityMismatch
          || requestIdentity.worldId && receipt.worldId !== requestIdentity.worldId
          || requestIdentity.datasetId && receipt.datasetId !== requestIdentity.datasetId
          || Number(checkpoints.focusSourcePathIndex) !== Number(selection.focusSourcePathIndex)
          || !validConditionedFocus(response.paths, selection, requestIdentity)
          || !receipt.valuationFingerprint
          || modelReceipt.valuationFingerprint !== receipt.valuationFingerprint
          || !proposedPosition) {
        throw new Error('The scenario response did not retain the active Plan, candidate, ensemble, and valuation identity.');
      }
      checkpoints.validatedAnimationBoundary = assertPositionAnimationV2(
        checkpoints, proposedPosition, 'The selected idea scenario', response.paths);
      if (!state.selected || state.selected.id !== requestIdentity.candidateId
          || !state.ensemble || state.ensemble.ensemble.id !== requestIdentity.ensembleId) return null;
      state.animation = response;
      state.error = null;
      notify('animation', { animation: response, scenario: scenario || {} });
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

  var HOME_MARKET_CAPACITY = 12;

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
      }).slice(0, HOME_MARKET_CAPACITY);
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

  function loadBookSymbolContext(symbol, marketSeed, options) {
    var forceFresh = options && options.forceFresh === true;
    var exactExpiration = options && options.expiration
      ? String(options.expiration).trim() : null;
    var declaredHorizon = number(workspaceContext.horizonDays);
    if (!(declaredHorizon > 0)) declaredHorizon = null;
    var loadKey = workspaceMarketIdentity(state.workspace.receipt)
      + '|' + symbol + '|h:' + (declaredHorizon == null ? 'nearest' : declaredHorizon)
      + '|exp:' + (exactExpiration || 'selected')
      + (forceFresh ? ':fresh' : '');
    if (bookContextLoads[loadKey]) return bookContextLoads[loadKey];
    var encoded = encodeURIComponent(symbol);
    var seeded = !forceFresh && marketSeed && marketSeed.research && marketSeed.chain
      && String(marketSeed.research.symbol || marketSeed.quote && marketSeed.quote.symbol || '')
        .toUpperCase() === String(symbol).toUpperCase();
    var marketSlot = forceFresh ? readSlot : readCachedSlot;
    function present(key, path, value) {
      return Promise.resolve({ key: key, path: path, available: true, value: value, error: null });
    }
    var load = Promise.all([
      seeded ? present('research:' + symbol, '/api/research/' + encoded, marketSeed.research)
        : marketSlot('research:' + symbol, '/api/research/' + encoded),
      marketSlot('news:' + symbol, '/api/research/' + encoded + '/news'),
      // One complete stored artifact owns every chart range; viewport ranges are client-side
      // windows, never distinct provider or API reads.
      marketSlot('history:max:' + symbol,
        '/api/research/' + encoded + '/history?range=max'),
      seeded ? present('expirations:' + symbol, expirationPath(encoded, declaredHorizon), {
        symbol: symbol, expirations: marketSeed.expirations || [],
        asOfDate: marketSeed.expirationAsOf || null,
        selection: marketSeed.expirationSelection||{
          date:marketSeed.expiration||null,
          requestedHorizonSessions:declaredHorizon,
          tradingSessions:null,calendarDays:null,
          basis:marketSeed.expirationBasis||null
        }
      }) : marketSlot('expirations:' + symbol,
        expirationPath(encoded, declaredHorizon))
    ]).then(async function (base) {
      var expirationSlot = objectSlot(base[3], symbol + ' option expirations');
      var envelope = expirationSlot && expirationSlot.available ? expirationSlot.value : {};
      var expiration = exactExpiration || selectedExpiration(envelope);
      var chainSlot = seeded && String(marketSeed.expiration || '') === String(expiration)
        ? await present('chain:' + symbol, '/api/research/' + encoded + '/chain', marketSeed.chain)
        : expiration
        ? await marketSlot('chain:' + symbol, '/api/research/' + encoded
          + '/chain?expiration=' + encodeURIComponent(expiration))
        : unavailableSlot('chain:' + symbol, '/api/research/' + encoded + '/chain',
          'No current option expiration was available for the focused market pulse.');
      return base.concat([chainSlot]);
    });
    bookContextLoads[loadKey] = load;
    // This map coalesces concurrent consumers only. The bounded API cache owns freshness and
    // invalidation after the read settles; retaining a second indefinite cache here would create
    // a competing market-data owner.
    load.then(function () {
      if (bookContextLoads[loadKey] === load) delete bookContextLoads[loadKey];
    }, function () {
      if (bookContextLoads[loadKey] === load) delete bookContextLoads[loadKey];
    });
    return load;
  }

  /* §5.5: the browser does not decide a display price. This used to pick `last`, else the previous
     close, and label the basis itself — while the backend's Quote.markBasis() prefers the MID on
     any sane two-sided book, so Home showed `last` for every symbol that had a book while
     /api/quotes and /api/research both published the mid. One symbol, two prices. The batch row IS
     a typed QuoteView now; it is passed through verbatim, absence and stated reason included. */
  function quoteContextRow(symbol, quoteView, lane, unavailable) {
    if (!quoteView) return {
      symbol: symbol, research: null, news: null,
      /* A failed batch quote is still a per-symbol missing receipt. Keeping it on the row means
         a focused Research/history/chain read can recover independently while every untouched
         watch row retains the exact reason its price is absent instead of degrading to an
         unexplained em dash. */
      missing: unavailable ? [{
        key: 'research:' + symbol,
        path: unavailable.path || '/api/quotes',
        error: unavailable.error || { message: 'The market quote is unavailable.' }
      }] : []
    };
    return {
      symbol: symbol,
      research: { symbol: symbol, marketLane: lane || null, quote: quoteView },
      news: null,
      missing: []
    };
  }

  async function hydrateBookFocusedContext(seq, contextSeq, before, data, symbol, options) {
    try {
      var group = await loadBookSymbolContext(symbol, null, options);
      if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return;
      var after = await readIdentitySnapshot();
      if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return;
      assertSameReadIdentity(before, after);
      var researchSlot = objectSlot(group[0], symbol + ' Research');
      var newsSlot = objectSlot(group[1], symbol + ' News');
      var historySlot = objectSlot(group[2], symbol + ' observed history');
      var expirationsSlot = objectSlot(group[3], symbol + ' option expirations');
      var chainSlot = objectSlot(group[4], symbol + ' option chain');
      // G2 (#10): validate each slot INDEPENDENTLY. A lane/symbol failure on one slot demotes only
      // that slot, leaving the others at whatever they resolved to — never one blanket demotion.
      if (researchSlot.available) {
        try {
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
        } catch (error) {
          researchSlot = unavailableSlot('research:' + symbol, researchSlot.path, error.message);
        }
      }
      if (newsSlot.available) {
        try { assertDocumentSymbol(newsSlot.value, symbol, 'Home News'); }
        catch (error) { newsSlot = unavailableSlot('news:' + symbol, newsSlot.path, error.message); }
      }
      if (historySlot.available) {
        try { assertDocumentSymbol(historySlot.value, symbol, 'Home History'); }
        catch (error) { historySlot = unavailableSlot('history:' + symbol, historySlot.path, error.message); }
      }
      if (chainSlot.available) {
        try {
          assertDocumentSymbol({ symbol: chainSlot.value.underlying }, symbol, 'Home option chain');
          assertEvidenceLane(chainSlot.value.evidence, before.identity.marketLane, 'Home option chain');
        } catch (error) { chainSlot = unavailableSlot('chain:' + symbol, chainSlot.path, error.message); }
      }
      var current = data.homeContext || {}, rows = (current.rows || []).slice();
      var index = rows.findIndex(function (row) { return row.symbol === symbol; });
      var prior = index >= 0 ? rows[index] : { symbol: symbol };
      var row = {
        symbol: symbol,
        // Each slot retains its prior resolved value on a transient miss, each carrying its own
        // reason via `missing` — a demoted research slot never blanks stored news/history/chain.
        research: researchSlot.available ? researchSlot.value : prior.research || null,
        news: newsSlot.available ? newsSlot.value : prior.news || null,
        history: historySlot.available ? historySlot.value : prior.history || null,
        expirations: expirationsSlot.available ? expirationsSlot.value : prior.expirations || null,
        chain: chainSlot.available ? chainSlot.value : prior.chain || null,
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
        return quoteContextRow(symbol, quote, before.identity.marketLane,
          quoteSlot.available ? null : quoteSlot);
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
      if (api.prefetch) api.prefetch(expirationPath(encoded, horizonDays(workspaceContext)));
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

  async function focusBookSymbol(rawSymbol, options) {
    var symbol = String(rawSymbol || '').trim().toUpperCase();
    var book = state.book, data = book && book.data, context = data && data.homeContext;
    if (!symbol || !book || !data || !context
        || describedUniverseSymbols(data.universe).indexOf(symbol) < 0) return null;
    var seq = book.requestId, contextSeq = ++bookContextRequestSeq;
    var symbols = (context.symbols || []).slice();
    /* Focusing one market changes the detailed receipt, not the breadth of Home's watch.
       The former four-name slice made a command-search or staged ticker silently discard eight
       cross-sector lenses. Move the subject to the front and retain the same bounded owner used
       by the initial Home hydration. */
    if (symbols.indexOf(symbol) < 0) {
      symbols = [symbol].concat(symbols).filter(function (value, index, all) {
        return all.indexOf(value) === index;
      }).slice(0, HOME_MARKET_CAPACITY);
    }
    publishBookContext(seq, contextSeq, data, Object.assign({}, context, {
      phase: 'loading', symbols: symbols, detailSymbol: symbol, detailLoading: symbol,
      /* A symbol focus is a detail selection inside the current market scope. Keep the
         selected sector receipt so Home does not silently jump back to Broad market. */
      sectorLens: context.sectorLens || null
    }));
    var before = await readIdentitySnapshot();
    if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return null;
    var api = requireApi(), encoded = encodeURIComponent(symbol);
    if (api.prefetch) api.prefetch(expirationPath(encoded, horizonDays(workspaceContext)));
    await hydrateBookFocusedContext(seq, contextSeq, before, data, symbol, options);
    return contextSeq === bookContextRequestSeq ? data.homeContext : null;
  }

  async function focusBookSector(rawSector) {
    var book = state.book, data = book && book.data, context = data && data.homeContext;
    if (!book || !data || !context) return null;
    var seq = book.requestId, requested = String(rawSector || '').trim();
    var contextSeq = ++bookContextRequestSeq;
    if (!requested) {
      var defaults = (context.defaultSymbols || context.symbols || []).slice(0, HOME_MARKET_CAPACITY);
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
          message: 'That sector is not present in the active market universe.'
        }
      }));
      return data.homeContext;
    }
    var symbols = (sector.symbols || []).map(function (symbol) {
      return String(symbol || '').trim().toUpperCase();
    }).filter(Boolean).slice(0, HOME_MARKET_CAPACITY);
    publishBookContext(seq, contextSeq, data, Object.assign({}, context, {
      phase: symbols.length ? 'loading' : 'ready', symbols: symbols,
      rows: (context.rows || []).filter(function (row) { return symbols.indexOf(row.symbol) >= 0; }),
      detailSymbol: symbols[0] || null, detailLoading: symbols[0] || null,
      sectorLens: {
        available: symbols.length > 0, key: sector.key, label: sector.label,
        symbols: (sector.symbols || []).slice(),
        message: symbols.length ? null : 'That sector has no symbols in the active market universe.'
      }
    }));
    if (!symbols.length) return data.homeContext;
    var before = await readIdentitySnapshot();
    if (seq !== bookRequestSeq || contextSeq !== bookContextRequestSeq) return null;
    await hydrateBookContext(seq, contextSeq, before, data, symbols);
    return contextSeq === bookContextRequestSeq ? data.homeContext : null;
  }

  /**
   * Read the current Practice Book without borrowing the New Idea request sequence. Empty arrays
   * are authoritative empty states. An unavailable auxiliary risk lens is retained as a named
   * missing receipt; it is never replaced with zeroes or fixture positions.
   */
  async function loadBook() {
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
        readSlot('practiceBook', '/api/portfolio/book'),
        readSlot('planPortfolio', '/api/plans/portfolio'),
        readSlot('universe', '/api/universe')
      ]);
      if (seq !== bookRequestSeq) return null;
      var after = await readIdentitySnapshot();
      if (seq !== bookRequestSeq) return null;
      assertSameReadIdentity(before, after);

      var bookLabels = {
        practiceBook: 'The Practice Book',
        planPortfolio: 'The Plan portfolio',
        universe: 'The active market universe'
      };
      slots = slots.map(function (slot) {
        return slot.key === 'practiceBook'
          ? practiceBookSlot(slot) : objectSlot(slot, bookLabels[slot.key]);
      });
      var values = slotsByKey(slots);
      var practiceBook = requireSlot(values.practiceBook, 'The Practice Book');
      var snapshot = practiceBook.snapshot;
      var activeTrades = snapshot.activeTrades;
      var sharePositions = practiceBook.sharePositions;

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
      var homeSymbols = homeBookSymbols(accountPlans || [], activeTrades, sharePositions,
        values.universe.available ? values.universe.value : null);
      var data = {
        identity: before.identity,
        account: before.account,
        practiceBook: practiceBook,
        planPortfolio: values.planPortfolio.available ? values.planPortfolio.value : null,
        plans: planRows,
        accountPlans: accountPlans,
        universe: values.universe.available ? values.universe.value : null,
        strategyCatalog: state.strategyCatalog,
        positionAnalyses: {},
        positionDetails: {},
        lifecycle: { phase: activeTrades.length ? 'loading' : 'empty',
          available: 0, unavailable: 0 },
        recentTradeId: recentCommittedTradeId && activeTrades.some(function (trade) {
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
      hydrateBookLifecycle(seq, before, data, activeTrades);
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
    var rows = practiceBookTrades(state.book && state.book.data);
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

  function managementPlanHintFromBook(originPlanId) {
    if (!originPlanId) return null;
    var rows = state.book && state.book.data
      && (state.book.data.accountPlans || state.book.data.plans);
    if (!Array.isArray(rows)) return null;
    return rows.filter(function (row) {
      return row && row.plan
        && String(row.plan.originPlanId || '') === String(originPlanId)
        && row.plan.open !== false && String(row.plan.status || '').toUpperCase() !== 'ARCHIVED';
    }).sort(function (a, b) {
      return String(b.plan.updatedAt || '').localeCompare(String(a.plan.updatedAt || ''))
        || Number(b.plan.version || 0) - Number(a.plan.version || 0);
    })[0] || null;
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
    var managementPlanHint = managementPlanHintFromBook(planId);
    var managementPlanId = managementPlanHint && managementPlanHint.plan
      && managementPlanHint.plan.id || null;
    var range = String(options.historyRange || '6m').toLowerCase();
    if (['1m', '3m', '6m', 'ytd', '1y', '2y', '5y', 'max'].indexOf(range) < 0) range = '6m';
    return {
      id: id,
      symbol: symbol,
      planId: planId == null ? null : String(planId),
      managementPlanId: managementPlanId == null ? null : String(managementPlanId),
      historyRange: range,
      planHint: planHint,
      managementPlanHint: managementPlanHint
    };
  }

  function positionMarketSlots(symbol) {
    return loadBookSymbolContext(symbol).then(function (market) {
      return [
        Object.assign({}, market[0], { key: 'research' }),
        Object.assign({}, market[2], { key: 'history' }),
        Object.assign({}, market[1], { key: 'news' }),
        Object.assign({}, market[3], { key: 'expirations' }),
        Object.assign({}, market[4], { key: 'chain' })
      ];
    });
  }

  /* The owning Plan and stored ensemble are the Position's possible-futures lane. They are
     intentionally separate from Research/history/news/chain: a slow market-data receipt must
     never delay a locally stored path artifact or make it appear unavailable. */
  function positionProjectionSlots(descriptor) {
    var requests = [];
    if (descriptor.planId) {
      requests.push(readSlot('planWorkspace', '/api/plans/'
        + encodeURIComponent(descriptor.planId) + '/manage'));
      requests.push(readSlot('positionEnsemble', '/api/plans/'
        + encodeURIComponent(descriptor.planId) + '/outcomes/ensemble/latest'));
    }
    return Promise.all(requests);
  }

  function positionRehearsalSlots(descriptor) {
    var requests = [];
    if (descriptor.managementPlanId) {
      requests.push(readSlot('positionRehearsals', '/api/plans/'
        + encodeURIComponent(descriptor.managementPlanId) + '/rehearsals'));
    }
    return Promise.all(requests);
  }

  function validatedPositionProjection(slots, descriptor, identity, tradeId) {
    var normalized = slots.map(function (slot) {
      return objectSlot(slot, slot.key === 'planWorkspace'
        ? 'The linked Plan workspace' : 'The stored Position outcome ensemble');
    });
    var values = slotsByKey(normalized);
    if (values.planWorkspace) {
      values.planWorkspace = optionalValidatedSlot(values.planWorkspace,
        'The linked Plan workspace', function (workspace) {
          assertPlanWorkspaceIdentity(workspace, descriptor, identity, tradeId);
        });
    }
    var workspace = values.planWorkspace && values.planWorkspace.available
      ? values.planWorkspace.value : null;
    var ensemblePlan = values.positionEnsemble && values.positionEnsemble.available
      && values.positionEnsemble.value && values.positionEnsemble.value.plan || null;
    var linkedPlan = workspace ? workspace.plan
      : ensemblePlan || descriptor.planHint && descriptor.planHint.plan || null;
    if (values.positionEnsemble) {
      values.positionEnsemble = optionalValidatedSlot(values.positionEnsemble,
        'The stored Position outcome ensemble', function (positionEnsemble) {
          assertPositionEnsembleIdentity(positionEnsemble, descriptor, identity, linkedPlan);
        });
    }
    normalized = normalized.map(function (slot) { return values[slot.key] || slot; });
    return {
      slots: normalized,
      workspace: workspace,
      linkedPlan: linkedPlan,
      positionEnsemble: values.positionEnsemble && values.positionEnsemble.available
        ? values.positionEnsemble.value : null
    };
  }

  function validatedPositionMarket(slots, symbol, identity) {
    var labels = {
      research: 'Research', history: 'History', news: 'News',
      expirations: 'Option expirations', chain: 'The option chain'
    };
    var normalized = slots.map(function (slot) {
      return objectSlot(slot, labels[slot.key]);
    });
    var values = slotsByKey(normalized);
    values.research = optionalValidatedSlot(values.research, 'Research', function (research) {
      assertDocumentSymbol(research, symbol, 'Research');
      if (research.marketLane && String(research.marketLane).toUpperCase()
          !== String(identity.marketLane).toUpperCase()) {
        throw new Error('Research belongs to another market lane.');
      }
      assertEvidenceLane(research.quote && research.quote.evidence,
        identity.marketLane, 'Research quote');
    });
    values.history = optionalValidatedSlot(values.history, 'History', function (history) {
      assertDocumentSymbol(history, symbol, 'History');
      if (missingEvidence(history.evidence)) {
        throw new Error('Observed daily history is not stored for ' + symbol
          + '. Open Data Sources to acquire eligible bars.');
      }
      assertEvidenceLane(history.evidence, identity.marketLane, 'History');
    });
    values.news = optionalValidatedSlot(values.news, 'News', function (news) {
      assertDocumentSymbol(news, symbol, 'News');
    });
    values.expirations = optionalValidatedSlot(values.expirations,
      'Option expirations', function (expirations) {
        assertDocumentSymbol(expirations, symbol, 'Option expirations');
        if (!Array.isArray(expirations.expirations)) {
          throw new Error('Option expirations omitted their listed dates.');
        }
      });
    values.chain = optionalValidatedSlot(values.chain, 'The option chain', function (chain) {
      assertDocumentSymbol({ symbol: chain.underlying }, symbol, 'The option chain');
      assertEvidenceLane(chain.evidence, identity.marketLane, 'The option chain');
      if (!Array.isArray(chain.calls) || !Array.isArray(chain.puts)) {
        throw new Error('The option chain omitted its call or put book.');
      }
    });
    normalized = normalized.map(function (slot) { return values[slot.key] || slot; });
    return { slots: normalized, values: values };
  }

  function validatedPositionRehearsals(slots, symbol) {
    var normalized = slots.map(function (slot) {
      return objectSlot(slot, 'The linked Position rehearsals');
    });
    var values = slotsByKey(normalized);
    if (values.positionRehearsals) {
      values.positionRehearsals = optionalValidatedSlot(values.positionRehearsals,
        'The linked Position rehearsals', function (document) {
          var rows = document && document.rehearsals;
          if (!Array.isArray(rows)) throw new Error('The linked rehearsal list omitted its rows.');
          rows.forEach(function (row) {
            if (!row || !row.worldId || !row.ensembleId || !row.fingerprint
                || String(row.symbol || '').toUpperCase() !== symbol) {
              throw new Error('A linked rehearsal omitted its world, ensemble, or position symbol.');
            }
          });
        });
    }
    normalized = normalized.map(function (slot) { return values[slot.key] || slot; });
    return { slots: normalized, values: values };
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
   * One symbol's canonical market context (research, news, history, expirations, chain) through
   * the same cached slot reads Home uses, so the Decide Market lens never grows a second data path.
   */
  async function symbolContext(symbol, options) {
    symbol = String(symbol || '').trim().toUpperCase();
    if (!symbol) throw new Error('Choose a symbol before loading its market context.');
    options = options || {};
    var only = String(options.only || '').trim().toLowerCase();
    if (only === 'news') {
      var newsPath = '/api/research/' + encodeURIComponent(symbol) + '/news';
      var newsSlot = objectSlot(await (options.forceFresh === true ? readSlot : readCachedSlot)(
        'news:' + symbol, newsPath), symbol + ' News');
      if (newsSlot.available) assertDocumentSymbol(newsSlot.value, symbol, 'Market lens News');
      return {
        symbol: symbol, research: null,
        news: newsSlot.available ? newsSlot.value : null,
        history: null, expirations: null, chain: null, expiration: null,
        missing: missingSlots([newsSlot])
      };
    }
    if (only === 'chain') {
      var exactExpiration = String(options.expiration || '').trim();
      if (!exactExpiration) throw new Error('Choose an expiration before loading its option chain.');
      var chainPath = '/api/research/' + encodeURIComponent(symbol)
        + '/chain?expiration=' + encodeURIComponent(exactExpiration);
      var chainSlot = objectSlot(await (options.forceFresh === true ? readSlot : readCachedSlot)(
        'chain:' + symbol, chainPath), symbol + ' option chain');
      if (chainSlot.available) {
        assertDocumentSymbol({ symbol: chainSlot.value.underlying }, symbol,
          'Market lens option chain');
      }
      return {
        symbol: symbol, research: null, news: null, history: null, expirations: null,
        chain: chainSlot.available ? chainSlot.value : null,
        expiration: exactExpiration, missing: missingSlots([chainSlot])
      };
    }
    var marketSeed = state.market && state.market.research
      && String(state.market.research.symbol || '').toUpperCase() === symbol
      ? state.market : null;
    var group = await loadBookSymbolContext(symbol, marketSeed, options);
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
      expiration: options && options.expiration
        ? String(options.expiration) : selectedExpiration(expirationsSlot.value || {}),
      missing: missingSlots([researchSlot, newsSlot, historySlot, expirationsSlot, chainSlot])
    };
  }

  /**
   * The COMPLETE stored daily history for one symbol (range=max), read once and cached. Stored
   * ranges are zero-upstream-call reads under the politeness engine, so every chart range is a
   * client-side window over this one artifact — no per-range refetching, no provider spend.
   */
  async function symbolHistory(symbol) {
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
   * The expiry-level expected-move receipt is another view of the same symbol market owner. It is
   * returned verbatim: the Desk may draw p16/p50/p84, but never derive a range from IV or time.
   */
  async function symbolExpectedMove(symbol, expiration) {
    symbol = String(symbol || '').trim().toUpperCase();
    expiration = String(expiration || '').trim();
    if (!symbol || !expiration) {
      throw new Error('Choose a symbol and expiration before loading its expected move.');
    }
    var doc = await requireApi().get('/api/research/' + encodeURIComponent(symbol)
      + '/expected-move?expiry=' + encodeURIComponent(expiration));
    assertDocumentSymbol(doc, symbol, 'Expected move');
    return doc;
  }

  /**
   * Explicit market refresh through the same mutation/cache boundary as every other Desk action.
   * This only starts the server job; it does not claim that stored daily history was filled.
   */
  async function refreshSymbolData(symbol) {
    symbol = String(symbol || '').trim().toUpperCase();
    if (!symbol) throw new Error('Choose a symbol before requesting a market refresh.');
    return requireApi().post('/api/data/jobs', {
      kind: 'refresh_now',
      params: { symbols: [symbol] }
    });
  }

  /**
   * The Position surface does not own another management API. It sends the user's exact selected
   * Book-action projection through the existing signed PositionTransformation preview/apply
   * boundary. Financial values remain server-owned; this adapter only validates transport
   * identity and removes undefined optional fields from the request.
   */
  function exactPositionActionRequest(options, requirePreviewToken) {
    options = options || {};
    var sourceId = String(options.sourceId || '').trim();
    var action = String(options.action || '').trim().toUpperCase();
    if (!sourceId) throw new Error('Choose an exact Practice position before reviewing an action.');
    if (!action) throw new Error('Choose an exact position action before reviewing it.');
    var request = {
      source: 'PRACTICE_TRADE',
      sourceId: sourceId,
      action: action
    };
    if (options.planId != null && String(options.planId).trim()) {
      request.planId = String(options.planId);
      var version = number(options.expectedPlanVersion);
      if (!Number.isSafeInteger(version) || version < 0) {
        throw new Error('The linked Plan version is required for this position action.');
      }
      request.expectedPlanVersion = version;
    }
    if (options.closeQuantity != null) {
      var quantity = number(options.closeQuantity);
      if (!Number.isSafeInteger(quantity) || quantity < 1) {
        throw new Error('A partial close needs a positive whole quantity.');
      }
      request.closeQuantity = quantity;
    }
    if (options.legIndex != null) {
      var legIndex = number(options.legIndex);
      if (!Number.isSafeInteger(legIndex) || legIndex < 0) {
        throw new Error('A lifecycle action needs an exact option-leg index.');
      }
      request.legIndex = legIndex;
    }
    if (requirePreviewToken) {
      var previewToken = String(options.previewToken || '').trim();
      if (!previewToken) {
        throw new Error('Review this exact position action before applying it.');
      }
      request.previewToken = previewToken;
    }
    return request;
  }

  function previewPositionAction(options) {
    return requireApi().post('/api/position-transformations/preview',
      exactPositionActionRequest(options, false));
  }

  function applyPositionAction(options) {
    return requireApi().post('/api/position-transformations/apply',
      exactPositionActionRequest(options, true));
  }

  /**
   * Read one held package's unconditioned valuation on its owning Plan's stored ensemble.
   * Book uses its batched joint receipt; Position earns this exact per-package projection when
   * focused. Both consume the same canonical outcome endpoint and the same PositionAnimation
   * contract, and neither computes or substitutes paths in the browser.
   */
  async function positionFutures(options) {
    var planId = options && options.planId != null ? String(options.planId).trim() : '';
    var tradeId = options && options.tradeId != null ? String(options.tradeId).trim() : '';
    if (!planId || !tradeId) {
      throw new Error('Position futures need the owning Plan and exact trade identity.');
    }
    var limit = options && options.limit == null ? 24 : number(options.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 60) {
      throw new Error('Position futures path limit must be a whole number from 1 through 60.');
    }
    var positionState = state.position;
    var data = positionState && positionState.data;
    if (!data || !data.trade || String(data.trade.id || '') !== tradeId
        || !data.plan || String(data.plan.id || '') !== planId) {
      throw new Error('Load this exact Position and its owning Plan before reading its possible futures.');
    }
    var stored = data.positionEnsemble;
    if ((options && options.refreshEnsemble === true)
        || !stored || !stored.ensemble || !stored.ensemble.id || !stored.ensemble.fingerprint) {
      stored = await requireApi().getFresh('/api/plans/' + encodeURIComponent(planId)
        + '/outcomes/ensemble/latest');
      var descriptor = {
        id: tradeId, planId: planId,
        symbol: String(data.trade.symbol || '').trim().toUpperCase()
      };
      assertPositionEnsembleIdentity(stored, descriptor, data.identity || {}, data.plan);
      var current = state.position && state.position.data;
      if (!current || String(current.trade && current.trade.id || '') !== tradeId
          || String(current.plan && current.plan.id || '') !== planId) return null;
      current.positionEnsemble = stored;
      data = current;
    }
    var ensembleId = String(stored.ensemble.id);
    var ensembleFingerprint = String(stored.ensemble.fingerprint);
    if (options && options.ensembleId != null
        && String(options.ensembleId) !== ensembleId) {
      throw new Error('The Position futures request names another stored ensemble.');
    }
    if (options && options.ensembleFingerprint != null
        && String(options.ensembleFingerprint) !== ensembleFingerprint) {
      throw new Error('The Position futures request names another stored ensemble fingerprint.');
    }
    var response = await requireApi().post('/api/plans/' + encodeURIComponent(planId)
      + '/outcomes/ensemble/paths', {
        ensembleId: ensembleId, limit: limit, focusPositionKey: tradeId
      });
    var requestIdentity = exactPositionProjectionIdentity(
      data, stored, tradeId, planId, 'TERMINAL_QUANTILES');
    assertPositionScenarioResponse(response, requestIdentity);
    var accepted = state.position && state.position.data;
    var acceptedEnsemble = accepted && accepted.positionEnsemble
      && accepted.positionEnsemble.ensemble;
    if (!accepted || String(accepted.trade && accepted.trade.id || '') !== tradeId
        || String(accepted.plan && accepted.plan.id || '') !== planId
        || !acceptedEnsemble || String(acceptedEnsemble.id || '') !== ensembleId
        || String(acceptedEnsemble.fingerprint || '') !== ensembleFingerprint) return null;
    return response;
  }

  /**
   * Load one server-owned position receipt plus its same-world Research context. Supplying the
   * trade row (or options.symbol) lets detail, Research, history, news, and Plan/manage read in
   * parallel; a bare id first discovers its server-owned symbol, then performs the same reads.
   */
  async function loadPosition(tradeOrId, options) {
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

      /* Start independent lanes together, but publish them independently. Plan/ensemble is a
         local stored artifact; market support may involve a much slower provider/cache path. */
      var projectionPromise = positionProjectionSlots(descriptor);
      var rehearsalPromise = positionRehearsalSlots(descriptor);
      var marketPromise = descriptor.symbol
        ? positionMarketSlots(descriptor.symbol) : null;
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
      if (!marketPromise) marketPromise = positionMarketSlots(symbol);
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
        expirations: null,
        chain: null,
        plan: hintedPlan,
        management: null,
        managementPlan: descriptor.managementPlanHint
          && descriptor.managementPlanHint.plan || null,
        positionRehearsals: [],
        planWorkspace: null,
        positionEnsemble: null,
        projectionPending: descriptor.planId != null,
        marketSupportPending: true,
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

      /* Publish the stored projection lane before waiting for Research/history/news/chain. This
         is the point at which Position may request its exact P/L transform of the saved fan. */
      var projectionSlots = await projectionPromise;
      if (seq !== positionRequestSeq) return null;
      var afterProjection = await readIdentitySnapshot();
      if (seq !== positionRequestSeq) return null;
      assertSameReadIdentity(before, afterProjection);
      var projection = validatedPositionProjection(
        projectionSlots, descriptor, before.identity, descriptor.id);
      coreData.plan = projection.linkedPlan;
      coreData.management = projection.workspace ? projection.workspace.management : null;
      coreData.planWorkspace = projection.workspace;
      coreData.positionEnsemble = projection.positionEnsemble;
      coreData.projectionPending = false;
      coreData.missing = missingSlots(projection.slots);
      state.position = {
        phase: 'partial', requestId: seq, identity: before.identity,
        data: coreData, missing: coreData.missing, error: null
      };
      notify('position-partial', {
        operation: 'position-projection', requestId: seq,
        position: state.position, data: coreData
      });

      var supportGroups = await Promise.all([marketPromise, rehearsalPromise]);
      if (seq !== positionRequestSeq) return null;
      var afterSupport = await readIdentitySnapshot();
      if (seq !== positionRequestSeq) return null;
      assertSameReadIdentity(before, afterSupport);
      var market = validatedPositionMarket(supportGroups[0], symbol, before.identity);
      var rehearsals = validatedPositionRehearsals(supportGroups[1], symbol);
      var values = market.values;
      /* A user may explicitly refresh a missing/stale stored ensemble while the independent
         market-support lane is still pending. positionFutures writes that accepted receipt into
         the live partial Position. Final support enriches that object; it must never replace the
         newer ensemble with the projection snapshot captured before the refresh. */
      var livePositionData = state.position && state.position.requestId === seq
        && state.position.data && String(state.position.data.trade && state.position.data.trade.id || '')
          === String(descriptor.id)
        ? state.position.data : coreData;
      var missing = missingSlots(
        projection.slots.concat(market.slots, rehearsals.slots));
      if (livePositionData.positionEnsemble) {
        missing = missing.filter(function (slot) { return slot.key !== 'positionEnsemble'; });
      }
      var data = {
        identity: before.identity,
        account: before.account,
        trade: detail.trade,
        tradeDetail: detail,
        research: values.research.available ? values.research.value : null,
        history: values.history.available ? values.history.value : null,
        news: values.news.available ? values.news.value : null,
        expirations: values.expirations.available ? values.expirations.value : null,
        chain: values.chain.available ? values.chain.value : null,
        plan: projection.linkedPlan,
        management: projection.workspace ? projection.workspace.management : null,
        managementPlan: descriptor.managementPlanHint
          && descriptor.managementPlanHint.plan || null,
        positionRehearsals: rehearsals.values.positionRehearsals
          && rehearsals.values.positionRehearsals.available
          ? rehearsals.values.positionRehearsals.value.rehearsals : [],
        planWorkspace: projection.workspace,
        positionEnsemble: livePositionData.positionEnsemble || projection.positionEnsemble,
        projectionPending: false,
        marketSupportPending: false,
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
        operation: 'position-support', requestId: seq, position: state.position, data: data
      });
      return data;
    } catch (error) {
      if (seq !== positionRequestSeq) return null;
      /* Once the exact held trade has loaded, a projection/Research/provider failure is partial
         support loss—not a failed Position. Preserve entry payoff, legs, and every stored lane
         already adopted; only management facts that require the missing current evidence remain
         unavailable. */
      if (coreData && coreData.trade && String(coreData.trade.id || '') === String(descriptor.id)) {
        var supportFailure = errorReceipt(error);
        coreData.projectionPending = false;
        coreData.marketSupportPending = false;
        coreData.auxiliaryPending = false;
        coreData.missing = (coreData.missing || []).concat([{
          key: 'positionSupport', available: false, reason: supportFailure.message
        }]);
        state.position = {
          phase: 'partial', requestId: seq, identity: before && before.identity || null,
          data: coreData, missing: coreData.missing, error: supportFailure
        };
        notify('position-partial', {
          operation: 'position-support-error', requestId: seq,
          position: state.position, data: coreData, error: error
        });
        return coreData;
      }
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

  /**
   * Refresh one named Position input without tearing down the durable entry payoff, stored
   * ensemble, history, or the other market-support lanes. Mark refresh uses the canonical held
   * trade receipt; chain and news refresh use the same symbol-context owner as Home and New Idea.
   */
  async function refreshPositionEvidence(kind, options) {
    kind = String(kind || '').trim().toLowerCase();
    if (['mark', 'chain', 'news'].indexOf(kind) < 0) {
      throw new Error('Choose current mark, option chain, or headlines to refresh.');
    }
    options = options || {};
    var current = state.position, data = current && current.data,
        trade = data && (data.tradeDetail && data.tradeDetail.trade || data.trade),
        tradeId = String(options.tradeId || trade && trade.id || '').trim(),
        symbol = String(options.symbol || trade && trade.symbol || '').trim().toUpperCase();
    if (!current || !data || !tradeId || !symbol
        || String(trade && trade.id || '') !== tradeId
        || String(trade && trade.symbol || '').toUpperCase() !== symbol) {
      throw new Error('Load this exact Position before refreshing its evidence.');
    }
    var before = await readIdentitySnapshot();
    if (state.position !== current || state.position.data !== data) return null;
    if (current.identity) {
      assertSameMarket(current.identity, before.identity);
      if (current.identity.accountId != null
          && String(current.identity.accountId) !== String(before.identity.accountId)) {
        throw new Error('The active account changed before the Position evidence refresh.');
      }
    }
    var slot, value;
    if (kind === 'mark') {
      slot = await readBookPositionDetailSlot(tradeId, true);
      value = requireSlot(slot, 'The current position mark');
      if (!value || !value.trade || String(value.trade.id || '') !== tradeId
          || String(value.trade.symbol || '').toUpperCase() !== symbol) {
        throw new Error('The refreshed current mark belongs to another Position.');
      }
    } else {
      /* Market evidence has one acquisition/cache/identity owner. A Position retry used to call
         raw Research endpoints here, creating a surface-specific path that could disagree with
         Home and New Idea. Force the canonical symbol context once, then adopt only the lane the
         user asked to recover. */
      var expiration = kind === 'chain' ? String(options.expiration
        || data.chain && data.chain.expiration
        || selectedExpiration(data.expirations || {}) || '').trim() : null;
      if (kind === 'chain' && !expiration) {
        throw new Error('No exact expiration is available for the Position chain refresh.');
      }
      var context = await symbolContext(symbol, {
        forceFresh: true,
        expiration: expiration,
        only: kind
      });
      value = context[kind];
      if (!value) {
        var gap = (context.missing || []).find(function (row) {
          return String(row && row.key || '').indexOf(kind + ':') === 0
            || String(row && row.key || '') === kind;
        });
        throw new Error(gap && gap.error && gap.error.message
          || (kind === 'news'
            ? 'The Position headlines are unavailable.'
            : 'The Position option chain is unavailable.'));
      }
      if (kind === 'chain' && options.expiration
          && String(value.expiration || '') !== String(options.expiration)) {
        throw new Error('The refreshed option chain returned another expiration.');
      }
    }
    var after = await readIdentitySnapshot();
    if (state.position !== current || state.position.data !== data) return null;
    assertSameReadIdentity(before, after);
    if (kind === 'mark') {
      data.tradeDetail = value;
      data.trade = value.trade;
    } else {
      data[kind] = value;
    }
    data.missing = (data.missing || []).filter(function (row) {
      var key = String(row && row.key || '');
      return key !== kind && key.indexOf(kind + ':') !== 0;
    });
    var phase = data.missing.length ? 'partial' : 'ready';
    state.position = {
      phase: phase, requestId: current.requestId, identity: current.identity,
      data: data, missing: data.missing, error: null
    };
    notify('position-' + phase, {
      operation: 'position-evidence-' + kind, requestId: current.requestId,
      position: state.position, data: data
    });
    return data;
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

  function positionPackageFingerprint(trade) {
    trade = trade || {};
    return JSON.stringify(canonicalJson({
      id: trade.id, symbol: trade.symbol, strategy: trade.strategy, intent: trade.intent,
      qty: trade.qty,
      entryPriceFingerprint: trade.entryPrice && trade.entryPrice.fingerprint,
      entryUnderlyingCents: trade.entryUnderlyingCents, openedAt: trade.openedAt,
      updatedAt: trade.updatedAt, status: trade.status, legs: trade.legs || []
    }));
  }

  /* Resting and conditioned Position paths share one identity/validation contract. Keeping a
     weaker "resting fan" checker let a stale package or old projection render successfully and
     then fail only after the user clicked a story. */
  function exactPositionProjectionIdentity(data, stored, tradeId, planId, selectionRule) {
    var storedReceipt = stored && stored.preview && stored.preview.receipt || {};
    return {
      positionRequestId: state.position && state.position.requestId,
      planId: String(planId || ''),
      accountId: data && data.identity && data.identity.accountId,
      contextRev: data && data.plan && data.plan.context && data.plan.context.rev,
      symbol: String(data && data.trade && data.trade.symbol || '').toUpperCase(),
      tradeId: String(tradeId || ''),
      ensembleId: String(stored && stored.ensemble && stored.ensemble.id || ''),
      ensembleFingerprint: String(
        stored && stored.ensemble && stored.ensemble.fingerprint || ''),
      worldId: storedReceipt.worldId || data && data.identity && data.identity.world || null,
      datasetId: storedReceipt.datasetId == null
        ? data && data.identity && data.identity.datasetId : storedReceipt.datasetId,
      positionPackageFingerprint: positionPackageFingerprint(data && data.trade),
      waypoints: [],
      pathWaypoints: [],
      pathSelectionRule: selectionRule,
      anchorSource: storedReceipt.anchorSource || null,
      anchorFreshness: storedReceipt.anchorFreshness || null
    };
  }

  function validScenarioProjection(projection, requestIdentity, paths, receipt) {
    projection = projection || {};
    var basis = String(projection.basis || '');
    var priced = projection.anchorQuote && projection.anchorQuote.priced === true
      && number(projection.anchorQuote.displayPrice) > 0;
    var stored = basis === 'STORED_ENSEMBLE';
    var rebased = /^(CURRENT|LAST_OBSERVED)_QUOTE_REBASED_SOURCE_RETURNS$/.test(basis);
    var anchor = number(projection.anchorSpot);
    var horizon = number(projection.horizonSessions);
    var first = paths && Array.isArray(paths.paths) && paths.paths[0]
      && Array.isArray(paths.paths[0].prices) ? number(paths.paths[0].prices[0]) : null;
    var outerAnchor = number(receipt && receipt.anchorSpot);
    var outerMatches = outerAnchor != null
      && Math.abs(outerAnchor - anchor) <= Math.max(1e-7, anchor * 1e-9)
      && (!rebased || String(receipt.anchorSource || '') === String(
          projection.anchorQuote.source || ''))
      && (!rebased || String(receipt.anchorFreshness || '') === String(
          projection.anchorQuote.freshness || ''));
    return String(projection.contractVersion || '') === 'scenario-projection-1'
      && String(projection.sourceEnsembleId || '') === requestIdentity.ensembleId
      && String(projection.sourceEnsembleFingerprint || '')
        === requestIdentity.ensembleFingerprint
      && /^[0-9a-f]{64}$/i.test(String(projection.fingerprint || ''))
      && anchor > 0 && Number.isInteger(horizon) && horizon > 0
      && ((stored && !projection.anchorQuote) || (rebased && priced))
      && outerMatches
      && first != null && Math.abs(first - anchor) <= Math.max(1e-7, anchor * 1e-9);
  }

  /*
   * Authored stories and tolerance-bearing waypoints are requests for an actual member of the
   * immutable stored fan, not permission to relabel the merely-nearest path. The service records
   * the tolerance population and marks each selected row; validate that receipt at the transport
   * seam so a malformed response cannot become a visually convincing but false story.
   */
  function validConditionedFocus(paths, selection, requestIdentity) {
    paths = paths || {};
    selection = selection || {};
    requestIdentity = requestIdentity || {};
    var interaction = requestIdentity.interaction || null;
    var requestedWaypoints = []
      .concat(requestIdentity.waypoints || [], requestIdentity.pathWaypoints || []);
    var requestRequiresMatch = !!(interaction && interaction.sourcePathIndex == null)
      || requestedWaypoints.some(function (waypoint) {
        return waypoint && waypoint.tolerance != null;
      });
    var explicitCount = number(selection.explicitToleranceCount);
    if (!requestRequiresMatch && !(explicitCount > 0)) return true;
    var focus = Array.isArray(paths.paths) && paths.paths.find(function (row) {
      return row && String(row.role || '').toUpperCase() === 'FOCUS'
        && Number(row.sourcePathIndex) === Number(selection.focusSourcePathIndex);
    });
    return explicitCount > 0
      && number(selection.withinToleranceCount) > 0
      && number(selection.selectedWithinToleranceCount) > 0
      && !!focus && focus.withinExplicitTolerance === true;
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
    var focusedPackageFingerprint = String(receipt.focusedPackageFingerprint || '');
    var focusedPackageProvenance = receipt.focusedPackageProvenance || {};
    var scenarioProjection = receipt.projection || {};
    if (!response || String(plan.id || '') !== requestIdentity.planId
        || plan.accountId != null && String(plan.accountId) !== String(requestIdentity.accountId)
        || plan.context && plan.context.rev != null && expectedContextRev != null
          && Number(plan.context.rev) !== Number(expectedContextRev)
        || String(ensemble.id || '') !== requestIdentity.ensembleId
        || String(ensemble.fingerprint || '') !== requestIdentity.ensembleFingerprint
        || String(receipt.contractVersion || '') !== 'scenario-animation-2'
        || String(receipt.ensembleId || '') !== requestIdentity.ensembleId
        || String(receipt.ensembleFingerprint || '') !== requestIdentity.ensembleFingerprint
        || !validScenarioProjection(scenarioProjection, requestIdentity, paths, receipt)
        || String(modelReceipt.ensembleFingerprint || '') !== requestIdentity.ensembleFingerprint
        || String(receipt.focusPositionKey || '') !== requestIdentity.tradeId
        || String(modelReceipt.focusPositionKey || '') !== requestIdentity.tradeId
        || receipt.symbol && String(receipt.symbol).toUpperCase() !== requestIdentity.symbol
        || expectedContextRev != null && Number(receipt.contextRev) !== Number(expectedContextRev)
        || expectedWorld && String(receipt.worldId || '') !== String(expectedWorld)
        || expectedDataset != null && String(receipt.datasetId || '') !== String(expectedDataset)
        || Number(checkpoints.focusSourcePathIndex) !== Number(selection.focusSourcePathIndex)
        || Number(modelReceipt.focusSourcePathIndex) !== Number(selection.focusSourcePathIndex)
        || !validConditionedFocus(paths, selection, requestIdentity)
        || String(returnedRule || '') !== requestIdentity.pathSelectionRule
        || (requestIdentity.interaction
          ? JSON.stringify(canonicalJson(receipt.requestedInteraction || {}))
              !== JSON.stringify(canonicalJson(requestIdentity.interaction))
            || !scenarioInteractionMatches(
              requestIdentity.interaction, receipt.interaction)
          : JSON.stringify(canonicalJson(returnedWaypoints))
              !== JSON.stringify(canonicalJson(requestIdentity.waypoints))
            || requestIdentity.pathWaypoints && requestIdentity.pathWaypoints.length
              && JSON.stringify(canonicalJson(returnedPathWaypoints))
                !== JSON.stringify(canonicalJson(requestIdentity.pathWaypoints)))
        || requestIdentity.interaction
          && requestIdentity.tradeId
          && requestIdentity.interaction.sourcePathIndex == null
          && (!(Number(receipt.interactionTargetSpotCents) > 0)
              || !receipt.interaction)
        || !/^[0-9a-f]{64}$/i.test(focusedPackageFingerprint)
        || String(modelReceipt.focusedPackageFingerprint || '') !== focusedPackageFingerprint
        || JSON.stringify(canonicalJson(modelReceipt.scenarioProjection || {}))
          !== JSON.stringify(canonicalJson(scenarioProjection))
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
    checkpoints.validatedAnimationBoundary = assertPositionAnimationV2(
      checkpoints, focused, 'The focused Position scenario', response.paths);
    return focused;
  }

  /**
   * Reprice the authoritative Position on a conditioned projection of its owning Plan's latest
   * stored fan. This never creates an ensemble and never shares cancellation state with New Idea,
   * Book, Position loading, or New Idea animation.
   */
  async function positionScenario(options) {
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
      var interaction = options && options.interaction || null;
      var waypoints = interaction ? [] : exactPositionScenarioWaypoints(options || {});
      var pathWaypoints = interaction ? [] : exactPositionScenarioPathWaypoints(options || {},
        Math.max(1, Number(stored.preview && stored.preview.horizonDays || 1)));
      var requestIdentity = exactPositionProjectionIdentity(
        data, stored, tradeId, planId, 'NEAREST_AUTHORED_WAYPOINTS');
      requestIdentity.positionRequestId = positionRequestId;
      requestIdentity.waypoints = pathWaypoints.length ? [] : waypoints;
      requestIdentity.pathWaypoints = pathWaypoints;
      var body = {
        ensembleId: requestIdentity.ensembleId,
        limit: limit,
        focusPositionKey: tradeId
      };
      if (interaction) {
        body.interaction = interaction;
        requestIdentity.interaction = canonicalJson(interaction);
        requestIdentity.pathSelectionRule = interaction.sourcePathIndex == null
          ? 'NEAREST_AUTHORED_WAYPOINTS' : 'EXACT_SOURCE_PATH';
      } else if (pathWaypoints.length) body.pathWaypoints = pathWaypoints;
      else body.waypoints = waypoints;
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
    options = options || {};
    var scope = String(options.scope || '').trim().toLowerCase();
    var universe = Array.isArray(options.universe) ? options.universe.map(function (symbol) {
      return String(symbol || '').trim().toUpperCase();
    }).filter(Boolean) : [];
    var described = state.book && state.book.data && state.book.data.universe;
    if (!universe.length && scope === 'broad') {
      var broad = described && described.scout && described.scout.symbols;
      if (Array.isArray(broad)) universe = broad.map(function (symbol) {
        return String(symbol || '').trim().toUpperCase();
      }).filter(Boolean);
    }
    if (!universe.length && scope === 'active') {
      var active = described && described.active && described.active.symbols;
      if (Array.isArray(active)) universe = active.map(function (symbol) {
        return String(symbol || '').trim().toUpperCase();
      }).filter(Boolean);
    }
    var horizons = (Array.isArray(options.horizons) ? options.horizons : []).map(function (horizon) {
      return String(horizon == null ? '' : horizon).trim();
    }).filter(Boolean);
    var intents = (Array.isArray(options.intents) ? options.intents : []).map(function (intent) {
      return String(intent == null ? '' : intent).trim();
    }).filter(Boolean);
    var riskMode = String(options.riskMode == null ? '' : options.riskMode).trim().toLowerCase();
    // Scan is withheld until the user declares goal, horizon and risk posture, so a blank here is
    // caller state loss. Substituting Income/45d/Balanced would spend a whole universe of provider
    // reads on a brief nobody chose and then present the result as the user's own idea.
    var missingDeclarations = [];
    if (['broad', 'active', 'sector'].indexOf(scope) < 0) {
      missingDeclarations.push('market scope');
    } else if (scope === 'sector' && !universe.length) {
      missingDeclarations.push('sector');
    }
    if (!intents.length) missingDeclarations.push('goal');
    if (!String(options.thesisOverride || '').trim()) missingDeclarations.push('market view');
    if (!horizons.length) missingDeclarations.push('horizon');
    if (!riskMode) missingDeclarations.push('risk posture');
    if (missingDeclarations.length) {
      var undeclared = new Error('The opportunity scan requires an explicit '
        + missingDeclarations.join(', ') + '; no decision default was substituted.');
      undeclared.code = 'DESK_DECLARATION_REQUIRED';
      undeclared.missingDeclarations = missingDeclarations;
      throw undeclared;
    }
    var body = {
      horizons: horizons,
      maxPicks: Math.max(1, Math.min(universe.length || 12,
        Number(options.maxPicks || Math.min(universe.length || 5, 5)))),
      riskMode: riskMode,
      allow0dte: false,
      intents: intents
    };
    if (universe.length) body.universe = universe;
    if (options.maxLossCents != null) body.maxLossCents = Number(options.maxLossCents);
    if (options.filters) body.filters = options.filters;
    if (options.thesisOverride) body.thesisOverride = String(options.thesisOverride);
    if (options.destinationAccountId) body.destinationAccountId = String(options.destinationAccountId);
    if (options.redeployment) body.redeployment = options.redeployment;
    if (typeof onProgress === 'function') {
      // A scan streams a whole universe through the market provider. If the user pivots to
      // analyzing one ticker (or starts a fresh scan), abort this one so it stops holding the
      // provider — otherwise the churning scan rate-limits the exact idea the user asked for.
      cancelScout();
      var controller = typeof AbortController === 'function' ? new AbortController() : null;
      scoutAbortController = controller;
      var result = null, streamError = null;
      function acceptFrame(frame) {
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
      try {
        await requireApi().streamNdjson('/api/research/scout', body, {
          signal: controller ? controller.signal : undefined,
          onFrame: acceptFrame
        });
      } finally {
        if (scoutAbortController === controller) scoutAbortController = null;
      }
      if (streamError) throw streamError;
      if (!result) throw new Error('The opportunity scan ended without a complete receipt.');
      return result;
    }
    return requireApi().post('/api/research/scout', body);
  }

  var governorTimer = null;
  var pendingGovernorRefresh = false;

  function clearPendingGovernorRefresh() {
    if (governorTimer) window.clearTimeout(governorTimer);
    governorTimer = null;
    pendingGovernorRefresh = false;
    state.strategyControls.refreshPending = false;
  }

  function flushGovernorRefresh() {
    governorTimer = null;
    if (!pendingGovernorRefresh || state.mutationPending) return;
    pendingGovernorRefresh = false;
    var revision = state.strategyControls.revision;
    openIdea(state.context, { strategyRefresh: true }).then(function () {
      if (revision !== state.strategyControls.revision) return;
      state.strategyControls.refreshPending = false;
      if (!state.error) state.strategyControls.appliedRevision = revision;
      notify('strategy-controls', {
        operation: 'strategy-controls-applied', controls: strategyControlsView()
      });
    }).catch(function () {
      state.strategyControls.refreshPending = false;
      notify('strategy-controls', {
        operation: 'strategy-controls-failed', controls: strategyControlsView()
      });
    });
  }

  function strategyControlsView() {
    return {
      values: Object.assign({}, state.strategyControls.values),
      explicit: Object.assign({}, state.strategyControls.explicit),
      revision: state.strategyControls.revision,
      appliedRevision: state.strategyControls.appliedRevision,
      refreshPending: state.strategyControls.refreshPending,
      supported: Object.assign({}, state.strategyControls.supported),
      unavailable: Object.assign({}, state.strategyControls.unavailable)
    };
  }

  function resetStrategyControls() {
    clearPendingGovernorRefresh();
    state.strategyControls.values = {
      risk: null, minPop: null, maxAsn: null, bp: null, gapLoss: null
    };
    state.strategyControls.explicit = {};
    state.strategyControls.revision++;
    state.strategyControls.appliedRevision = state.strategyControls.revision;
    state.strategyControls.refreshPending = false;
    notify('strategy-controls', {
      operation: 'strategy-controls', controls: strategyControlsView()
    });
    return strategyControlsView();
  }

  /*
   * Strategy screens are not Plan declarations. The bridge owns this one typed operation and
   * maps only controls the backend ranking contract actually enforces. Capital and crash-loss
   * caps retain their own named cent fields; neither is translated into debit cost, maximum loss,
   * or another nearby-but-different financial fact.
   */
  function updateStrategyControls(patch) {
    patch = patch || {};
    var values = patch.values || patch;
    var explicit = patch.explicit || {};
    ['risk', 'minPop', 'maxAsn', 'bp', 'gapLoss'].forEach(function (key) {
      if (!Object.prototype.hasOwnProperty.call(values, key)) return;
      var value = number(values[key]);
      state.strategyControls.values[key] = value;
      state.strategyControls.explicit[key] = Object.prototype.hasOwnProperty.call(explicit, key)
        ? explicit[key] === true : value != null;
    });
    state.strategyControls.revision++;
    notify('strategy-controls', {
      operation: 'strategy-controls', controls: strategyControlsView()
    });
    if (state.context && state.plan) {
      state.strategyControls.refreshPending = true;
      pendingGovernorRefresh = true;
      if (governorTimer) window.clearTimeout(governorTimer);
      governorTimer = window.setTimeout(flushGovernorRefresh, 180);
    }
    return strategyControlsView();
  }

  /* ---------------------------------------------------------------------------------------
     BROKER IMPORT — the desk's only path for bringing an outside position in.

     Every number the surface shows comes from these three reads. The browser parses nothing,
     prices nothing and decides nothing: the server parses the pasted statement, reports which
     groups carry exact fills and which carry only a package net, and writes only what the user
     verified (program §3.1).
     --------------------------------------------------------------------------------------- */
  var BROKER_IMPORT_PARSER = 'broker-import-1';

  /* ---------------------------------------------------------------------------------------
     THE workspace context (program §8.1). One persisted, versioned record; the desk reads it at
     boot and writes back only the fields that changed. A PATCH is used deliberately: omitted
     fields RETAIN their stored value, so a surface that touches one thing cannot destroy the
     declaration — the failure that made Import Trade wipe goal, view, horizon and risk.
     --------------------------------------------------------------------------------------- */
  function loadWorkspace(options) {
    options = options || {};
    if (workspaceLoadPromise) return workspaceLoadPromise;
    state.workspace.phase = state.workspace.receipt ? 'refreshing' : 'loading';
    state.workspace.error = null;
    if (!state.workspace.receipt) {
      notify('workspace-loading', { operation: 'workspace', workspace: state.workspace });
    }
    workspaceLoadPromise = requireApi().getFresh('/api/workspace').then(function (receipt) {
      var priorReceipt = state.workspace.receipt;
      var changedMarket = !!priorReceipt
        && workspaceMarketIdentity(priorReceipt) !== workspaceMarketIdentity(receipt);
      var adopted = adoptWorkspaceReceipt(receipt, {
        source: options.source || 'http',
        phase: changedMarket ? 'world-transition' : 'workspace-ready',
        operation: changedMarket ? 'market-transition' : 'workspace',
        worldTransition: changedMarket,
        preserveQueued: true
      });
      startWorkspaceEvents();
      return adopted;
    }).catch(function (error) {
      state.workspace.phase = 'error';
      state.workspace.error = errorReceipt(error);
      notify('workspace-error', {
        operation: 'workspace', workspace: state.workspace, error: error
      });
      throw error;
    }).finally(function () {
      workspaceLoadPromise = null;
    });
    return workspaceLoadPromise;
  }

  function workspacePatchBody(patch) {
    var body = {
      version: WORKSPACE_VERSION,
      expectedRev: workspaceRev(state.workspace.receipt)
    };
    if (workspaceContext.world != null) body.world = workspaceContext.world;
    if (workspaceContext.datasetId != null) body.expectedDatasetId = workspaceContext.datasetId;
    if (workspaceContext.marketLane != null) body.expectedMarketLane = workspaceContext.marketLane;
    if (workspaceContext.accountId != null) body.expectedAccountId = workspaceContext.accountId;
    if (workspaceContext.generation != null) {
      body.expectedGeneration = Number(workspaceContext.generation);
    }
    var clear = [];
    Object.keys(patch || {}).forEach(function (field) {
      if (WORKSPACE_FIELDS.indexOf(field) < 0) return;
      if (patch[field] == null) clear.push(field);
      else body[field] = patch[field];
    });
    if (clear.length) body.clear = clear;
    return body;
  }

  function settleWorkspaceWaiters(waiters, method, value) {
    waiters.forEach(function (waiter) {
      try { waiter[method](value); } catch (ignored) { /* one consumer cannot strand the queue */ }
    });
  }

  async function sendWorkspacePatch(batch) {
    var body = workspacePatchBody(batch);
    try {
      var saved = await requireApi().patch('/api/workspace', body);
      var adopted = adoptWorkspaceReceipt(saved, {
        source: 'patch', phase: 'workspace-updated', operation: 'workspace',
        preserveQueued: true
      });
      return adopted;
    } catch (error) {
      if (Number(error && error.status) !== 409) throw error;
      // The server rejected the revision rather than merging stale state. Re-read, rebase only
      // the fields this batch owns, and retry once against that exact revision. If another writer
      // wins again, surface the conflict; an unbounded retry loop would be silent last-write-wins.
      var fresh = await requireApi().getFresh('/api/workspace');
      adoptWorkspaceReceipt(fresh, {
        source: 'conflict-rebase', phase: 'workspace-rebased', operation: 'workspace',
        optimisticPatch: batch, preserveQueued: true
      });
      var retried = await requireApi().patch('/api/workspace', workspacePatchBody(batch));
      var adoptedRetry = adoptWorkspaceReceipt(retried, {
        source: 'patch-retry', phase: 'workspace-updated', operation: 'workspace',
        preserveQueued: true
      });
      return adoptedRetry;
    }
  }

  function flushWorkspacePatches() {
    if (workspacePatchTimer) {
      window.clearTimeout(workspacePatchTimer);
      workspacePatchTimer = null;
    }
    if (workspacePatchInFlight) return workspacePatchInFlight;
    var fields = Object.keys(workspacePatchPending);
    if (!fields.length) return Promise.resolve(state.workspace.receipt);
    var batch = workspacePatchPending;
    var waiters = workspacePatchWaiters;
    workspacePatchPending = {};
    workspacePatchWaiters = [];
    workspacePatchActive = batch;
    workspacePatchInFlight = sendWorkspacePatch(batch).then(function (saved) {
      settleWorkspaceWaiters(waiters, 'resolve', saved);
      return saved;
    }).catch(function (error) {
      state.workspace.error = errorReceipt(error);
      settleWorkspaceWaiters(waiters, 'reject', error);
      notify('workspace-error', {
        operation: 'workspace-patch', workspace: state.workspace, error: error
      });
      throw error;
    }).finally(function () {
      workspacePatchActive = null;
      workspacePatchInFlight = null;
      if (Object.keys(workspacePatchPending).length) {
        workspacePatchTimer = window.setTimeout(flushWorkspacePatches, 0);
      }
    });
    // Most presentation calls intentionally fire-and-forget. Attach a sink to the shared queue
    // promise so their own returned waiter controls error handling without an unhandled rejection.
    workspacePatchInFlight.catch(function () {});
    return workspacePatchInFlight;
  }

  async function drainWorkspacePatches() {
    if (workspacePatchTimer) {
      window.clearTimeout(workspacePatchTimer);
      workspacePatchTimer = null;
    }
    if (Object.keys(workspacePatchPending).length && !workspacePatchInFlight) {
      await flushWorkspacePatches();
    } else if (workspacePatchInFlight) {
      await workspacePatchInFlight;
    }
    if (Object.keys(workspacePatchPending).length || workspacePatchInFlight) {
      return drainWorkspacePatches();
    }
    return state.workspace.receipt;
  }

  /* `expectedRev` and the one serialized queue make optimistic persistence a property of the
     bridge rather than a convention every surface must remember. Null means an explicit clear;
     the wire contract receives it through `clear`, never as a no-op JSON null. */
  function patchWorkspace(patch) {
    var local = {};
    Object.keys(patch || {}).forEach(function (field) {
      if (WORKSPACE_FIELDS.indexOf(field) >= 0) local[field] = patch[field];
    });
    if (!Object.keys(local).length) return Promise.resolve(state.workspace.receipt);
    applyWorkspacePatchLocally(local);
    Object.keys(local).forEach(function (field) {
      workspacePatchPending[field] = local[field];
    });
    var promise = new Promise(function (resolve, reject) {
      workspacePatchWaiters.push({ resolve: resolve, reject: reject });
    });
    if (!workspacePatchTimer && !workspacePatchInFlight) {
      workspacePatchTimer = window.setTimeout(flushWorkspacePatches, 40);
    }
    return promise;
  }

  async function bootstrapWorkspace() {
    try {
      await loadWorkspace({ source: 'boot' });
    } catch (error) {
      // Workspace restoration is an additive boot barrier, not permission to strand the Book.
      // Its typed error remains in state.workspace while the account/positions load against an
      // explicitly undeclared context.
    }
    try {
      return await loadBook();
    } catch (error) {
      // loadBook already published its typed book-error receipt. A Book failure must not be
      // reported as though the workspace barrier failed.
      return null;
    }
  }

  function importAccounts() {
    return requireApi().getFresh('/api/portfolio/accounts').then(function (accounts) {
      return Array.isArray(accounts) ? accounts : (accounts && accounts.accounts) || [];
    });
  }

  function previewBrokerImport(request) {
    return requireApi().post('/api/portfolio/broker-imports/preview', {
      parserVersion: BROKER_IMPORT_PARSER,
      sourceSystem: request && request.sourceSystem,
      sourceAccount: request && request.sourceAccount,
      text: request && request.text
    });
  }

  function confirmBrokerImport(request) {
    return requireApi().post('/api/portfolio/broker-imports/confirm', {
      parserVersion: BROKER_IMPORT_PARSER,
      previewFingerprint: request && request.previewFingerprint,
      sourceSystem: request && request.sourceSystem,
      sourceAccount: request && request.sourceAccount,
      text: request && request.text,
      destinationAccountByFingerprint: (request && request.destinationAccountByFingerprint) || {},
      groups: (request && request.groups) || [],
      planId: (request && request.planId) || null
    });
  }

  window.DeskBackend = {
    importParserVersion: function () { return BROKER_IMPORT_PARSER; },
    workspaceContext: function () { return workspaceContext; },
    marketIdentityKey: function () {
      return workspaceMarketIdentity(state.workspace.receipt);
    },
    loadWorkspace: loadWorkspace,
    patchWorkspace: patchWorkspace,
    flushWorkspace: drainWorkspacePatches,
    bootstrapWorkspace: bootstrapWorkspace,
    receiveWorkspaceEvent: function (type, data) {
      if ((type === 'world.selected' || type === 'dataset.selected') && data && data.workspace) {
        var changedMarket = workspaceMarketIdentity(state.workspace.receipt)
          !== workspaceMarketIdentity(data.workspace);
        return adoptWorkspaceReceipt(data.workspace, {
          source: 'event', phase: changedMarket ? 'world-transition' : 'workspace-ready',
          operation: changedMarket ? 'market-transition' : 'workspace',
          worldTransition: changedMarket, preserveQueued: true,
          world: data, transition: data
        });
      }
      if (type === 'dataset.selected' && data) {
        return loadWorkspace({ source: 'dataset-event' });
      }
      if (type === 'workspace.updated'
          && Number(data && data.rev || 0) > workspaceRev(state.workspace.receipt)) {
        return loadWorkspace({ source: 'event' });
      }
      return Promise.resolve(state.workspace.receipt);
    },
    importAccounts: importAccounts,
    previewBrokerImport: previewBrokerImport,
    confirmBrokerImport: confirmBrokerImport,
    state: copyState,
    strategyCatalog: requestStrategyCatalog,
    ideaDeclaration: function () {
      return Object.assign({}, state.plan
        ? contextFromPlan(null, state.plan)
        : normalizeIdeaDeclaration(state.context));
    },
    strategyControls: strategyControlsView,
    updateStrategyControls: updateStrategyControls,
    resetStrategyControls: resetStrategyControls,
    readPlan: readPlan,
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
    rehearsals: readRehearsals,
    createRehearsal: createRehearsal,
    loadBook: loadBook,
    focusBookSymbol: focusBookSymbol,
    focusBookSector: focusBookSector,
    loadPosition: loadPosition,
    refreshPositionEvidence: refreshPositionEvidence,
    previewPositionAction: previewPositionAction,
    applyPositionAction: applyPositionAction,
    positionScenario: positionScenario,
    positionFutures: positionFutures,
    validatePositionAnimation: assertPositionAnimationV2,
    symbolContext: symbolContext,
    symbolHistory: symbolHistory,
    symbolExpectedMove: symbolExpectedMove,
    refreshSymbolData: refreshSymbolData,
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
