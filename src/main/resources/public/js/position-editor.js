/* StrikeBench shared position editor.
 *
 * One edited package can be analyzed as a hypothetical or recorded as broker activity.
 * ANALYZE tolerates missing fills and lets the pricing engine state what it could observe/model.
 * RECORD emits only complete factual legs to the tracked-account ledger.
 */
(function () {
  'use strict';

  var el = UI.el, chip = UI.chip, fmtMoney = UI.fmtMoney;
  var MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6,
    jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12 };

  function blankLeg(symbol) {
    return { instrumentType: 'OPTION', action: 'BUY', positionEffect: 'OPEN',
      symbol: String(symbol || '').toUpperCase(), optionType: 'CALL', strike: '', expiration: '',
      quantity: 1, multiplier: 100, price: '', section1256: null };
  }

  function cleanDraft(raw, options) {
    raw = raw || {};
    var symbol = String(options.lockedSymbol || raw.symbol || options.symbol || '').toUpperCase();
    var legs = Array.isArray(raw.legs) && raw.legs.length ? raw.legs : [blankLeg(symbol)];
    return {
      version: 2,
      mode: raw.mode || (Learn.currentLevel() === 'expert' ? 'terminal' : 'visual'),
      symbol: symbol,
      legs: legs.map(function (leg) {
        if (!leg || !leg.instrumentType || !leg.action || !leg.positionEffect
            || leg.quantity == null || leg.multiplier == null) {
          throw new Error('Trade drafts require instrumentType, action, positionEffect, quantity, and multiplier on every leg.');
        }
        var stock = String(leg.instrumentType).toUpperCase() === 'STOCK';
        return {
          instrumentType: stock ? 'STOCK' : 'OPTION',
          action: String(leg.action).toUpperCase(),
          positionEffect: String(leg.positionEffect).toUpperCase(),
          symbol: String(options.lockedSymbol || leg.symbol || symbol).toUpperCase(),
          optionType: stock ? null : String(leg.optionType || '').toUpperCase(),
          strike: stock ? '' : String(leg.strike == null ? '' : leg.strike),
          expiration: stock ? '' : String(leg.expiration || ''),
          quantity: leg.quantity,
          multiplier: leg.multiplier,
          price: leg.price == null ? '' : String(leg.price),
          section1256: stock ? null : leg.section1256 === true ? true : null
        };
      }),
      packageNet: options.allowRecord ? '' : raw.packageNet == null ? '' : String(raw.packageNet),
      fillNature: options.allowRecord ? 'EXECUTED' : raw.fillNature || 'PROPOSED',
      feeMode: options.allowRecord ? 'EXACT' : raw.feeMode || 'DEFAULT',
      fees: raw.fees == null ? '' : String(raw.fees),
      occurredAt: raw.occurredAt == null ? '' : String(raw.occurredAt),
      source: options.allowRecord && raw.externalRef ? 'BROKER' : 'MANUAL',
      externalRef: raw.externalRef || '',
      notes: raw.notes || '',
      terminal: raw.terminal || '',
      terminalDirty: raw.terminalDirty === true,
      chainExpiration: raw.chainExpiration || '',
      chainType: raw.chainType || 'PUT',
      chainAction: raw.chainAction || 'SELL',
      selectedCandidateId: raw.selectedCandidateId || null,
      selectedFingerprint: raw.selectedFingerprint || null,
      similarityDecision: null
    };
  }

  function draftFingerprint(draft) {
    return JSON.stringify({
      legs: (draft.legs || []).map(function (leg) {
        return [leg.instrumentType, leg.action, leg.positionEffect, leg.symbol, leg.optionType,
          String(leg.strike || ''), leg.expiration || '', String(leg.quantity), String(leg.multiplier),
          String(leg.price), leg.section1256 === true];
      }),
      packageNet: String(draft.packageNet), feeMode: draft.feeMode, fees: String(draft.fees),
      fillNature: draft.fillNature, occurredAt: draft.occurredAt
    });
  }

  function gcd(a, b) {
    a = Math.abs(a); b = Math.abs(b);
    while (b) { var t = b; b = a % b; a = t; }
    return a || 1;
  }

  function validLeg(leg, fillRequired) {
    if (!['STOCK', 'OPTION'].includes(leg.instrumentType)
        || !['BUY', 'SELL'].includes(leg.action)
        || !['OPEN', 'CLOSE'].includes(leg.positionEffect)) return false;
    if (!leg.symbol || !Number.isInteger(Number(leg.quantity)) || Number(leg.quantity) <= 0) return false;
    if (leg.instrumentType === 'STOCK' && Number(leg.multiplier) !== 1) return false;
    if (leg.instrumentType === 'OPTION') {
      if (!leg.strike || !leg.expiration || !['CALL', 'PUT'].includes(leg.optionType)) return false;
      if (!Number.isInteger(Number(leg.multiplier)) || Number(leg.multiplier) <= 0 || Number(leg.multiplier) > 10000) return false;
    }
    if (fillRequired && (leg.price === '' || !Number.isFinite(Number(leg.price)) || Number(leg.price) <= 0)) return false;
    return leg.price === '' || Number.isFinite(Number(leg.price)) && Number(leg.price) >= 0;
  }

  function normalizedForAnalysis(draft) {
    if (!draft.legs.length || draft.legs.some(function (leg) { return !validLeg(leg, false); })) {
      throw new Error('Complete the symbol, quantity, and every option strike and expiration before analyzing. Fill prices may stay blank.');
    }
    var symbols = Array.from(new Set(draft.legs.map(function (leg) { return String(leg.symbol).trim().toUpperCase(); })));
    if (symbols.length !== 1) {
      throw new Error('Analyze accepts one underlying per position package. Split unrelated symbols into separate analyses; factual activity can still be recorded separately.');
    }
    var units = draft.legs.map(function (leg) { return Number(leg.quantity); });
    var packageQty = units.reduce(gcd);
    var legs = draft.legs.map(function (leg, i) {
      return { action: leg.action, type: leg.instrumentType === 'STOCK' ? 'STOCK' : leg.optionType,
        strike: leg.instrumentType === 'STOCK' ? null : String(leg.strike),
        expiration: leg.instrumentType === 'STOCK' ? null : leg.expiration,
        ratio: units[i] / packageQty, entryPrice: leg.price === '' ? null : String(leg.price),
        multiplier: leg.instrumentType === 'STOCK' ? 1 : Number(leg.multiplier),
        positionEffect: leg.positionEffect };
    });
    var packageNet = draft.packageNet === '' ? enteredPackageNetCents(draft) : Math.round(Number(draft.packageNet) * 100);
    if (draft.packageNet !== '' && !Number.isFinite(Number(draft.packageNet))) throw new Error('Package net must be a number.');
    var feeValue = draft.feeMode === 'EXACT' && draft.fees !== '' ? Number(draft.fees) : null;
    if (feeValue !== null && (!Number.isFinite(feeValue) || feeValue < 0)) throw new Error('Fees must be zero or more.');
    return { symbol: draft.symbol || draft.legs[0].symbol,
      strategy: 'CUSTOM', qty: packageQty, legs: legs,
      proposedNetCents: packageNet, feesOverrideCents: feeValue === null ? null : Math.round(feeValue * 100),
      source: draft.fillNature === 'EXECUTED' ? 'BROKER' : 'ANALYZE',
      fillNature: draft.fillNature };
  }

  function enteredPackageNetCents(draft) {
    if (draft.legs.some(function (leg) { return leg.price === ''; })) return null;
    var total = 0;
    draft.legs.forEach(function (leg) {
      var units = Number(leg.quantity) * (leg.instrumentType === 'OPTION' ? Number(leg.multiplier) : 1);
      var cents = Math.round(Number(leg.price) * units * 100);
      total += leg.action === 'SELL' ? cents : -cents;
    });
    return total;
  }

  function isPast(value) {
    if (!value) return false;
    var parsed = new Date(value);
    return Number.isFinite(parsed.getTime()) && parsed.getTime() < Date.now() - 24 * 60 * 60 * 1000;
  }

  function recordPayload(draft) {
    if (!draft.occurredAt || !Number.isFinite(new Date(draft.occurredAt).getTime())) {
      throw new Error('RECORD requires the date and time the broker activity occurred.');
    }
    if (draft.legs.some(function (leg) { return !validLeg(leg, true); })) {
      throw new Error('RECORD requires every factual symbol, quantity, multiplier, contract, and positive fill price. Use ANALYZE when a fill is unknown.');
    }
    if (draft.fees === '') {
      throw new Error('RECORD requires the exact total fees from the broker, including an explicit 0.00 when no fee was charged. Analyze may use the StrikeBench estimate.');
    }
    var fees = Number(draft.fees);
    if (!Number.isFinite(fees) || fees < 0) throw new Error('Exact recorded fees must be zero or more.');
    var netCents = enteredPackageNetCents(draft);
    if (netCents == null) throw new Error('Every recorded leg needs its exact fill price.');
    var externalRef = draft.externalRef.trim();
    return { occurredAt: new Date(draft.occurredAt).toISOString(), eventType: 'TRADE', fillNature: 'EXECUTED',
      cashAmountCents: netCents - Math.round(fees * 100),
      feesCents: Math.round(fees * 100), taxCategory: null, source: externalRef ? 'BROKER' : 'MANUAL',
      externalRef: externalRef || null, notes: draft.notes.trim() || null,
      legs: draft.legs.map(function (leg) {
        return { instrumentType: leg.instrumentType, action: leg.action,
          positionEffect: leg.positionEffect, symbol: leg.symbol,
          optionType: leg.instrumentType === 'OPTION' ? leg.optionType : null,
          strike: leg.instrumentType === 'OPTION' ? leg.strike : null,
          expiration: leg.instrumentType === 'OPTION' ? leg.expiration : null,
          quantity: Number(leg.quantity), multiplier: leg.instrumentType === 'OPTION' ? Number(leg.multiplier) : 1,
          price: Number(leg.price), section1256: leg.instrumentType === 'OPTION' && leg.section1256 === true ? true : null };
      }) };
  }

  function parseDateToken(token) {
    if (/^\d{4}-\d{2}-\d{2}$/.test(token)) return token;
    var m = /^(\d{1,2})([A-Za-z]{3})(\d{2}|\d{4})$/.exec(token);
    if (!m || !MONTHS[m[2].toLowerCase()]) throw new Error('Use an expiration like 13Jul26 or 2026-07-13.');
    var year = Number(m[3]); if (year < 100) year += 2000;
    return year + '-' + String(MONTHS[m[2].toLowerCase()]).padStart(2, '0') + '-' + String(Number(m[1])).padStart(2, '0');
  }

  function parseTerminal(text, lockedSymbol) {
    var lines = String(text || '').split(/[\n;]/).map(function (line) { return line.trim(); }).filter(Boolean);
    if (!lines.length) throw new Error('Enter at least one stock or option line.');
    return lines.map(function (line, index) {
      var option = /^([+-]\d+)\s+([A-Za-z0-9._-]+)\s+(\d{4}-\d{2}-\d{2}|\d{1,2}[A-Za-z]{3}\d{2,4})\s+(\d+(?:\.\d+)?)([CP])(?:\s+[xX](\d+))?(?:\s+@\s*(\d+(?:\.\d+)?))?(?:\s+(OPEN|CLOSE))?(?:\s+(1256))?$/i.exec(line);
      if (option) {
        var oq = Number(option[1]), os = option[2].toUpperCase();
        if (lockedSymbol && os !== lockedSymbol) throw new Error('Line ' + (index + 1) + ' must use ' + lockedSymbol + '.');
        return { instrumentType: 'OPTION', action: oq > 0 ? 'BUY' : 'SELL', positionEffect: (option[8] || 'OPEN').toUpperCase(),
          symbol: os, expiration: parseDateToken(option[3]), strike: option[4],
          optionType: option[5].toUpperCase() === 'C' ? 'CALL' : 'PUT', quantity: Math.abs(oq),
          multiplier: option[6] ? Number(option[6]) : 100, price: option[7] || '', section1256: option[9] ? true : null };
      }
      var stock = /^([+-]\d+)\s+([A-Za-z0-9._-]+)(?:\s+@\s*(\d+(?:\.\d+)?))?(?:\s+(OPEN|CLOSE))?$/i.exec(line);
      if (stock) {
        var sq = Number(stock[1]), ss = stock[2].toUpperCase();
        if (lockedSymbol && ss !== lockedSymbol) throw new Error('Line ' + (index + 1) + ' must use ' + lockedSymbol + '.');
        return { instrumentType: 'STOCK', action: sq > 0 ? 'BUY' : 'SELL', positionEffect: (stock[4] || 'OPEN').toUpperCase(),
          symbol: ss, optionType: null, strike: '', expiration: '', quantity: Math.abs(sq), multiplier: 1,
          price: stock[3] || '', section1256: null };
      }
      throw new Error('Line ' + (index + 1) + ' is not understood. Try "-1 MU 13Jul26 980P @20.00 CLOSE" or "+100 MU @979.30 OPEN".');
    });
  }

  function terminalText(legs) {
    return (legs || []).filter(function (leg) {
      return leg.symbol && (leg.instrumentType === 'STOCK' || leg.expiration && leg.strike);
    }).map(function (leg) {
      var signed = (leg.action === 'BUY' ? '+' : '-') + leg.quantity;
      var effect = leg.positionEffect === 'CLOSE' ? ' CLOSE' : '';
      if (leg.instrumentType === 'STOCK') return signed + ' ' + leg.symbol + (leg.price === '' ? '' : ' @' + leg.price) + effect;
      return signed + ' ' + leg.symbol + ' ' + leg.expiration + ' ' + leg.strike
        + (leg.optionType === 'CALL' ? 'C' : 'P') + (Number(leg.multiplier) === 100 ? '' : ' x' + leg.multiplier)
        + (leg.price === '' ? '' : ' @' + leg.price) + effect + (leg.section1256 === true ? ' 1256' : '');
    }).join('\n');
  }

  function draftFromCandidate(candidate, options) {
    if (!candidate || !Array.isArray(candidate.legs) || !candidate.legs.length) return null;
    if (!Number.isInteger(Number(candidate.qty)) || Number(candidate.qty) < 1) {
      throw new Error('Selected position is missing its required package quantity.');
    }
    var packageQty = Number(candidate.qty);
    var symbol = String(options.lockedSymbol || candidate.symbol || options.symbol || '').toUpperCase();
    var raw = {
      symbol: symbol,
      fillNature: 'PROPOSED',
      packageNet: candidate.entryNetPremiumCents == null ? '' : String(Number(candidate.entryNetPremiumCents) / 100),
      legs: candidate.legs.map(function (leg) {
        if (!leg || !leg.type || !leg.action || !leg.positionEffect
            || !Number.isInteger(Number(leg.ratio)) || Number(leg.ratio) < 1
            || !Number.isInteger(Number(leg.multiplier)) || Number(leg.multiplier) < 1) {
          throw new Error('Selected position does not satisfy the current exact-leg contract.');
        }
        var stock = String(leg.type).toUpperCase() === 'STOCK';
        var ratio = Number(leg.ratio), multiplier = Number(leg.multiplier);
        return {
          instrumentType: stock ? 'STOCK' : 'OPTION',
          action: String(leg.action).toUpperCase(),
          positionEffect: String(leg.positionEffect).toUpperCase(),
          symbol: symbol,
          optionType: stock ? null : String(leg.type).toUpperCase(),
          strike: stock ? '' : String(leg.strike == null ? '' : leg.strike),
          expiration: stock ? '' : String(leg.expiration || ''),
          quantity: stock ? packageQty * ratio * multiplier : packageQty * ratio,
          multiplier: stock ? 1 : multiplier,
          price: leg.entryPrice == null ? '' : String(leg.entryPrice),
          section1256: leg.section1256 === true ? true : null
        };
      })
    };
    return raw;
  }

  function localPayoff(draft, spot) {
    if (localPayoffUnavailableReason(draft)) return null;
    spot = Number(spot) || draft.legs.filter(function (l) { return l.instrumentType === 'STOCK' && l.price !== ''; })
      .map(function (l) { return Number(l.price); })[0] || 100;
    var strikes = draft.legs.filter(function (l) { return l.instrumentType === 'OPTION' && l.strike; })
      .map(function (l) { return Number(l.strike); }).filter(Number.isFinite);
    var low = Math.max(0.01, Math.min.apply(null, [spot * 0.55].concat(strikes.map(function (k) { return k * 0.75; }))));
    var high = Math.max.apply(null, [spot * 1.45].concat(strikes.map(function (k) { return k * 1.25; })));
    var enteredNet = enteredPackageNetCents(draft);
    var statedNet = draft.packageNet === '' ? enteredNet : Math.round(Number(draft.packageNet) * 100);
    var packageAdjustment = enteredNet == null || statedNet == null || !Number.isFinite(statedNet)
      ? 0 : statedNet - enteredNet;
    var feeAdjustment = draft.feeMode === 'EXACT' && Number.isFinite(Number(draft.fees))
      ? Math.round(Number(draft.fees) * 100) : 0;
    var points = [];
    for (var i = 0; i <= 60; i++) {
      var price = low + (high - low) * i / 60, pnl = 0;
      draft.legs.forEach(function (leg) {
        var sign = leg.action === 'BUY' ? 1 : -1, fill = leg.price === '' ? 0 : Number(leg.price);
        if (leg.instrumentType === 'STOCK') pnl += sign * Number(leg.quantity) * (price - fill) * 100;
        else {
          var intrinsic = leg.optionType === 'CALL' ? Math.max(0, price - Number(leg.strike)) : Math.max(0, Number(leg.strike) - price);
          pnl += sign * Number(leg.quantity) * Number(leg.multiplier) * (intrinsic - fill) * 100;
        }
      });
      points.push({ price: price, profitCents: Math.round(pnl + packageAdjustment - feeAdjustment) });
    }
    return points;
  }

  function localPayoffUnavailableReason(draft) {
    if ((draft.legs || []).some(function (leg) { return leg.positionEffect === 'CLOSE'; })) {
      return 'A close changes an existing position. Analyze the resulting before-and-after position; a standalone expiry curve would be misleading.';
    }
    var expirations = (draft.legs || []).filter(function (leg) { return leg.instrumentType === 'OPTION' && leg.expiration; })
      .map(function (leg) { return leg.expiration; });
    if (new Set(expirations).size > 1) {
      return 'Calendars and diagonals change when the front leg expires. Their value needs a dated path, not one terminal payoff line.';
    }
    return null;
  }

  function provisionalDraft(draft, market, chainData) {
    var copy = JSON.parse(JSON.stringify(draft)), modeled = false, missing = [], usable = [];
    for (var i = 0; i < copy.legs.length; i++) {
      var leg = copy.legs[i];
      if (!validLeg(leg, false)) {
        missing.push({ index: i, reason: 'complete this leg' });
        continue;
      }
      if (leg.price !== '') { usable.push(leg); continue; }
      if (leg.instrumentType === 'STOCK') {
        if (!market || !Number.isFinite(Number(market.spot))) {
          missing.push({ index: i, reason: 'share price unavailable' });
          continue;
        }
        leg.price = String(market.spot); modeled = true; usable.push(leg); continue;
      }
      if (!market) {
        missing.push({ index: i, reason: 'market data loading' });
        continue;
      }
      var chain = chainData[market.symbol + '|' + leg.expiration];
      var rows = chain && (leg.optionType === 'CALL' ? chain.calls : chain.puts) || [];
      var quote = rows.find(function (row) { return Number(row.strike) === Number(leg.strike); });
      if (!quote) {
        missing.push({ index: i, reason: chain ? 'listed quote unavailable' : 'option chain loading' });
        continue;
      }
      var bid = Number(quote.bid), ask = Number(quote.ask), mark = Number(quote.mid);
      if (!Number.isFinite(mark)) mark = Number.isFinite(bid) && Number.isFinite(ask) ? (bid + ask) / 2 : NaN;
      if (!Number.isFinite(mark) || mark < 0) {
        missing.push({ index: i, reason: 'listed midpoint unavailable' });
        continue;
      }
      leg.price = String(mark); modeled = true; usable.push(leg);
    }
    copy.legs = usable;
    return { draft: copy, modeled: modeled, missing: missing,
      complete: missing.length === 0 && usable.length === draft.legs.length };
  }

  function render(root, options) {
    options = options || {};
    var mount = root;
    root = el('div', { class: 'position-editor' });
    mount.innerHTML = '';
    mount.appendChild(root);
    var stores = App.state.positionDrafts = App.state.positionDrafts || {};
    var key = options.stateKey || 'position-editor';
    var storedDraft = stores[key];
    var rejectedDraftReason = null;
    var seed = storedDraft || options.initial || draftFromCandidate(options.initialSelection, options);
    var draft;
    try {
      draft = cleanDraft(seed, options);
    } catch (error) {
      if (!storedDraft) throw error;
      delete stores[key];
      rejectedDraftReason = error.message || String(error);
      seed = options.initial || draftFromCandidate(options.initialSelection, options);
      draft = cleanDraft(seed, options);
    }
    if (!storedDraft && !options.initial && options.initialSelection && seed) {
      draft.selectedCandidateId = options.initialSelection.id || null;
      draft.selectedFingerprint = draft.selectedCandidateId ? draftFingerprint(draft) : null;
    }
    stores[key] = draft;
    var lastAnalysis = null, lastAnalysisFingerprint = null;
    var market = null, chainCache = {}, chainData = {}, chainErrors = {}, loadToken = 0, commandBusy = false;
    var headerHost = null, editorHost = null, chainPickerHost = null, visualHost = null, visualIdentityHost = null;
    var visualStatusHost = null, chartHost = null, commandHost = null, resultHost = null;
    var recordingDetailsHost = null;
    var modeButtons = {}, visualTimer = null, terminalTimer = null, identityTimer = null;
    var liveIdentity = null, liveIdentityFingerprint = null, identityToken = 0;

    function markResultStale(reason) {
      if (!resultHost || !resultHost.hasChildNodes()) return;
      resultHost.classList.add('is-stale');
      Array.from(resultHost.querySelectorAll('button:not(.xp-head)')).forEach(function (button) {
        if (!button.disabled) {
          button.dataset.positionStaleDisabled = 'true';
          button.disabled = true;
        }
      });
      var notice = resultHost.querySelector(':scope > .position-result-stale');
      if (!notice) {
        notice = UI.actionFeedback('caution', 'Preview needs another analysis', reason
          || 'The package changed. The last result stays visible for comparison, but it no longer describes the fields on screen.');
        notice.classList.add('position-result-stale');
        resultHost.prepend(notice);
      }
    }

    function clearResultStale() {
      if (!resultHost) return;
      resultHost.classList.remove('is-stale');
      var notice = resultHost.querySelector(':scope > .position-result-stale');
      if (notice) notice.remove();
      Array.from(resultHost.querySelectorAll('button[data-position-stale-disabled="true"]')).forEach(function (button) {
        delete button.dataset.positionStaleDisabled;
        button.disabled = false;
      });
    }

    function remember(forceStale) {
      if (options.allowRecord) {
        draft.packageNet = '';
        draft.fillNature = 'EXECUTED';
        draft.feeMode = 'EXACT';
        draft.source = draft.externalRef.trim() ? 'BROKER' : 'MANUAL';
      }
      var currentFingerprint = draftFingerprint(draft);
      var matchesRenderedResult = lastAnalysis && lastAnalysisFingerprint === currentFingerprint
        || draft.selectedFingerprint && draft.selectedFingerprint === currentFingerprint;
      if (matchesRenderedResult && !forceStale) clearResultStale();
      else if (lastAnalysis && lastAnalysisFingerprint !== currentFingerprint) {
        markResultStale();
      }
      if (resultHost && draft.selectedFingerprint && draft.selectedFingerprint !== currentFingerprint) {
        markResultStale('The edited package no longer matches the structure selected in this Plan. Analyze again before continuing.');
      }
      if (forceStale) markResultStale();
      stores[key] = JSON.parse(JSON.stringify(draft));
      if (window.Workspace) Workspace.save();
      if (typeof options.onStateChange === 'function') options.onStateChange(draft);
      scheduleIdentity();
      refreshRecordingDetails();
    }

    function showInputError(error) {
      if (!resultHost) return;
      var old = resultHost.querySelector(':scope > .position-input-error');
      if (old) old.remove();
      var next = UI.actionFeedback('danger', 'Terminal input needs attention', error.message || String(error));
      next.classList.add('position-input-error');
      resultHost.prepend(next);
    }

    function syncTerminal() {
      if (draft.mode !== 'terminal' || !draft.terminalDirty) return;
      draft.legs = parseTerminal(draft.terminal, options.lockedSymbol && String(options.lockedSymbol).toUpperCase());
      draft.symbol = options.lockedSymbol || draft.legs[0].symbol;
      draft.terminalDirty = false;
      remember(true);
    }

    function setMode(mode) {
      if (mode === draft.mode) return;
      if (draft.mode === 'terminal') {
        try { syncTerminal(); } catch (error) { showInputError(error); return; }
      }
      draft.mode = mode;
      if (mode === 'terminal') { draft.terminal = terminalText(draft.legs); draft.terminalDirty = false; }
      remember(); renderHeader(); renderInputs(); refreshVisual();
    }

    function ensureMarket() {
      var symbol = String(options.lockedSymbol || draft.symbol || draft.legs[0] && draft.legs[0].symbol || '').toUpperCase();
      if (!symbol || market && market.symbol === symbol || market && market.loading) return;
      var token = ++loadToken; market = { symbol: symbol, loading: true };
      API.get('/api/research/' + encodeURIComponent(symbol)).then(function (data) {
        if (token !== loadToken) return;
        market = { symbol: symbol, loading: false, data: data, spot: data.quote && Number(data.quote.last),
          expirations: data.expirations || [] };
        if (!draft.chainExpiration && market.expirations.length) draft.chainExpiration = market.expirations[Math.min(2, market.expirations.length - 1)];
        remember(); refreshChainPicker(); refreshVisual();
      }).catch(function (error) {
        if (token !== loadToken) return;
        market = { symbol: symbol, loading: false, error: error.message || String(error), expirations: [] };
        refreshChainPicker(); refreshVisual();
      });
    }

    function loadChain(expiration) {
      if (!expiration || !market) return Promise.resolve(null);
      var cacheKey = market.symbol + '|' + expiration;
      if (!chainCache[cacheKey]) chainCache[cacheKey] = API.get('/api/research/' + encodeURIComponent(market.symbol)
        + '/chain?expiration=' + encodeURIComponent(expiration)).then(function (value) {
          delete chainErrors[cacheKey]; chainData[cacheKey] = value; return value;
        }).catch(function (error) {
          chainErrors[cacheKey] = error && (error.message || String(error)) || 'The option chain could not be loaded.';
          return null;
        });
      return chainCache[cacheKey];
    }

    function scheduleVisual() {
      if (visualTimer) clearTimeout(visualTimer);
      visualTimer = setTimeout(refreshVisual, 120);
    }

    function identityRequest() {
      if (draft.terminalDirty) return null;
      try {
        var analyzed = normalizedForAnalysis(draft);
        var payload = { symbol: analyzed.symbol, qty: analyzed.qty, legs: analyzed.legs };
        return { payload: payload, fingerprint: JSON.stringify(payload) };
      } catch (ignored) { return null; }
    }

    function renderIdentity(currentFingerprint, analysisCurrent) {
      if (!visualIdentityHost || !visualIdentityHost.isConnected) return;
      var request = identityRequest();
      var liveCurrent = request && liveIdentity && liveIdentityFingerprint === request.fingerprint;
      var identified = analysisCurrent && lastAnalysis.identity || liveCurrent && liveIdentity || {
        family: null, label: 'Your exact package', blockedByDefault: false,
        summary: request
          ? 'Matching the exact legs to the strategy catalog…'
          : 'Complete each option strike and expiration to identify the structure. The payoff keeps using every defensible leg price already on screen.'
      };
      visualIdentityHost.innerHTML = '';
      visualIdentityHost.appendChild(el('div', { class: 'position-identity' },
        el('span', { class: 'eyebrow' }, UI.vocabulary('hypothetical'),
          analysisCurrent || liveCurrent ? ' · STRUCTURE IDENTIFIED' : ' POSITION'),
        el('h3', {}, identified.label),
        identified.summary ? el('p', { class: 'muted small' }, identified.summary) : null,
        identified.blockedByDefault ? UI.alertBox('caution', 'Shown for learning, blocked by default', [
          'The exact structure remains analyzable. Practice placement keeps its existing safety block; recording an actual broker fact is never refused for being risky.'
        ]) : null));
    }

    function scheduleIdentity() {
      if (identityTimer) clearTimeout(identityTimer);
      var request = identityRequest();
      if (!request) {
        identityToken++;
        liveIdentity = null; liveIdentityFingerprint = null;
        renderIdentity(draftFingerprint(draft), false);
        return;
      }
      if (liveIdentity && liveIdentityFingerprint === request.fingerprint) return;
      identityTimer = setTimeout(function () {
        var token = ++identityToken;
        API.post('/api/strategies/identify', request.payload).then(function (identity) {
          if (token !== identityToken) return;
          var current = identityRequest();
          if (!current || current.fingerprint !== request.fingerprint) return;
          liveIdentity = identity; liveIdentityFingerprint = request.fingerprint;
          renderIdentity(draftFingerprint(draft), false);
        }).catch(function () {
          if (token !== identityToken) return;
          liveIdentity = null; liveIdentityFingerprint = null;
          renderIdentity(draftFingerprint(draft), false);
        });
      }, 250);
    }

    function bind(input, target, keyName, onChange) {
      function save() {
        target[keyName] = input.type === 'number' ? (input.value === '' ? '' : Number(input.value)) : input.value;
        remember(); scheduleVisual();
      }
      input.addEventListener('input', save);
      input.addEventListener('change', function () { save(); if (typeof onChange === 'function') onChange(); });
      return input;
    }

    function field(label, input, hint) { return UI.field(label, input, hint ? { hint: hint } : null); }

    function legRow(leg, index) {
      var instrument = el('select', {}, el('option', { value: 'OPTION', selected: leg.instrumentType === 'OPTION' ? 'selected' : null }, 'Option'),
        el('option', { value: 'STOCK', selected: leg.instrumentType === 'STOCK' ? 'selected' : null }, 'Shares'));
      instrument.addEventListener('change', function () {
        leg.instrumentType = instrument.value;
        leg.multiplier = leg.instrumentType === 'STOCK' ? 1
          : Number(leg.multiplier) === 1 ? 100 : Number(leg.multiplier);
        remember();
        replaceLegRow(index);
        refreshVisual();
      });
      var action = bind(el('select', {}, el('option', { value: 'BUY', selected: leg.action === 'BUY' ? 'selected' : null }, 'Buy'),
        el('option', { value: 'SELL', selected: leg.action === 'SELL' ? 'selected' : null }, 'Sell')), leg, 'action', function () { syncLegRows(); });
      var effect = bind(el('select', {}, el('option', { value: 'OPEN', selected: leg.positionEffect === 'OPEN' ? 'selected' : null }, 'Open'),
        el('option', { value: 'CLOSE', selected: leg.positionEffect === 'CLOSE' ? 'selected' : null }, 'Close')), leg, 'positionEffect');
      var symbol = bind(el('input', { type: 'text', maxlength: '20', value: options.lockedSymbol || leg.symbol,
        disabled: options.lockedSymbol ? 'disabled' : null, placeholder: 'MU' }), leg, 'symbol');
      var optionType = bind(el('select', {}, el('option', { value: 'CALL', selected: leg.optionType === 'CALL' ? 'selected' : null }, 'Call'),
        el('option', { value: 'PUT', selected: leg.optionType === 'PUT' ? 'selected' : null }, 'Put')), leg, 'optionType', function () { syncLegRows(); });
      var strike = bind(el('input', { type: 'number', min: '0.0001', step: '0.01', value: leg.strike, placeholder: '980' }), leg, 'strike');
      var expiration = bind(el('input', { type: 'date', value: leg.expiration }), leg, 'expiration');
      var quantity = bind(el('input', { type: 'number', min: '1', max: '10000000', step: '1', value: leg.quantity }), leg, 'quantity');
      var multiplier = bind(el('input', { type: 'number', min: '1', max: '10000', step: '1', value: leg.multiplier }), leg, 'multiplier');
      var price = bind(el('input', { type: 'number', min: '0', step: '0.0001', value: leg.price,
        placeholder: 'blank = price for analysis' }), leg, 'price');
      var section1256 = el('input', { type: 'checkbox', checked: leg.section1256 === true ? 'checked' : null });
      section1256.onchange = function () { leg.section1256 = section1256.checked ? true : null; remember(); };
      var automatic1256 = App.config && Array.isArray(App.config.broadBasedIndexOptionSymbols)
        ? App.config.broadBasedIndexOptionSymbols.join(', ') : 'known broad-based index roots and listed series';
      var section1256Field = options.allowRecord && Learn.currentLevel() === 'expert'
        && leg.instrumentType === 'OPTION'
        ? el('label', { class: 'check-row position-1256-flag' }, section1256,
          el('span', {}, 'Other Section 1256 contract', el('small', {}, 'Automatic: ' + automatic1256
            + '. Check this only for another eligible contract confirmed by your broker.'))) : null;
      var taxDisclosure = section1256Field ? UI.expandable('Tax contract classification', function () {
        return section1256Field;
      }, { stateKey: 'position-tax-' + key + '-' + index }) : null;
      var grid = el('div', { class: 'position-leg-grid' }, field('Instrument', instrument), field('Action', action),
        options.compositionOnly ? null : field('Position', effect, 'Open adds exposure; Close reduces an existing position.'), field('Symbol', symbol),
        leg.instrumentType === 'OPTION' ? field('Type', optionType) : null,
        leg.instrumentType === 'OPTION' ? field('Strike $', strike) : null,
        leg.instrumentType === 'OPTION' ? field('Expiration', expiration) : null,
        field(leg.instrumentType === 'STOCK' ? 'Shares' : 'Contracts', quantity),
        leg.instrumentType === 'OPTION' ? field('Multiplier', multiplier, '100 standard; adjusted contracts may differ.') : null,
        options.compositionOnly ? null : field(options.allowRecord ? 'Exact fill $' : 'Fill or proposed price $', price,
          options.allowRecord ? 'Required by RECORD. ANALYZE may price a blank fill from available evidence.' : 'Optional; blank uses the available market/model evidence.'));
      var remove = el('button', { type: 'button', class: 'btn btn-secondary btn-sm position-leg-remove',
        disabled: draft.legs.length === 1 ? 'disabled' : null, 'aria-label': 'Remove leg ' + (index + 1),
        onclick: function () {
          var row = this.closest('.position-leg');
          draft.legs.splice(Number(row.dataset.legIndex), 1);
          remember(); row.remove(); syncLegRows(); refreshVisual();
        } }, 'Remove');
      return el('fieldset', { class: 'position-leg', 'data-leg-index': String(index),
        'aria-label': 'Leg ' + (index + 1) },
        el('div', { class: 'position-leg-head' },
          el('div', {}, el('span', { class: 'eyebrow position-leg-number' }, 'LEG ' + (index + 1)),
            el('b', { class: 'position-leg-summary' }, leg.action.toLowerCase() + ' '
              + (leg.instrumentType === 'STOCK' ? 'shares' : String(leg.optionType || 'option').toLowerCase())),
            el('span', { class: 'muted small' }, leg.positionEffect === 'CLOSE' ? 'reduces the open position' : 'adds exposure')),
          remove),
        grid, taxDisclosure);
    }

    function syncLegRows() {
      if (!editorHost) return;
      var rows = Array.from(editorHost.querySelectorAll('.position-leg'));
      rows.forEach(function (row, index) {
        row.dataset.legIndex = String(index);
        row.setAttribute('aria-label', 'Leg ' + (index + 1));
        var number = row.querySelector('.position-leg-number');
        var summary = row.querySelector('.position-leg-summary');
        var remove = row.querySelector('.position-leg-remove');
        var leg = draft.legs[index];
        if (number) number.textContent = 'LEG ' + (index + 1);
        if (summary) summary.textContent = leg.action.toLowerCase() + ' '
          + (leg.instrumentType === 'STOCK' ? 'shares' : String(leg.optionType || 'option').toLowerCase());
        if (remove) {
          remove.disabled = rows.length === 1;
          remove.setAttribute('aria-label', 'Remove leg ' + (index + 1));
        }
      });
    }

    function replaceLegRow(index) {
      if (!editorHost) return;
      var old = editorHost.querySelector('.position-leg[data-leg-index="' + index + '"]');
      if (!old) return;
      var next = legRow(draft.legs[index], index);
      old.replaceWith(next);
      var focus = next.querySelector('select:not([disabled]),input:not([disabled])');
      if (focus) focus.focus({ preventScroll: true });
    }

    function addLeg(nextLeg, replaceBlank) {
      var legList = editorHost && editorHost.querySelector('.position-leg-list');
      if (!legList) return;
      var index;
      if (replaceBlank) {
        draft.legs[0] = nextLeg; index = 0;
        var existing = legList.querySelector('.position-leg[data-leg-index="0"]');
        var replacement = legRow(nextLeg, 0);
        if (existing) existing.replaceWith(replacement); else legList.appendChild(replacement);
      } else {
        draft.legs.push(nextLeg); index = draft.legs.length - 1;
        legList.appendChild(legRow(nextLeg, index));
      }
      remember(); syncLegRows(); refreshVisual();
      var row = legList.querySelector('.position-leg[data-leg-index="' + index + '"]');
      if (!row) return;
      row.classList.add('arrival-highlight');
      row.scrollIntoView({ behavior: 'smooth', block: 'center' });
      var first = row.querySelector('select:not([disabled]),input:not([disabled])');
      if (first) setTimeout(function () { if (first.isConnected) first.focus({ preventScroll: true }); }, 220);
      setTimeout(function () { if (row.isConnected) row.classList.remove('arrival-highlight'); }, 1400);
    }
    function refreshVisual() {
      if (!visualHost || !visualHost.isConnected || !visualIdentityHost || !chartHost) return;
      var currentFingerprint = draftFingerprint(draft);
      var analysisCurrent = !!lastAnalysis && lastAnalysisFingerprint === currentFingerprint && !draft.terminalDirty;
      var payoffUnavailable = localPayoffUnavailableReason(draft);
      renderIdentity(currentFingerprint, analysisCurrent);
      draft.legs.forEach(function (leg) {
        if (!market || leg.instrumentType !== 'OPTION' || !leg.expiration) return;
        var cacheKey = market.symbol + '|' + leg.expiration;
        if (!chainData[cacheKey] && !chainCache[cacheKey]) {
          loadChain(leg.expiration).then(function () { refreshVisual(); });
        }
      });
      var response = analysisCurrent && (lastAnalysis.preview || lastAnalysis);
      var points = response && response.payoff;
      var provisional = null;
      if ((!points || points.length < 2) && !payoffUnavailable && !draft.terminalDirty) {
        provisional = provisionalDraft(draft, market, chainData);
        if (provisional && provisional.draft.legs.length) {
          if (!provisional.complete) {
            provisional.draft.packageNet = '';
            provisional.draft.fees = '';
            provisional.draft.feeMode = 'DEFAULT';
          }
          points = localPayoff(provisional.draft, market && market.spot);
        }
      }
      var handles = [];
      if (draft.mode === 'visual' && market) draft.legs.forEach(function (leg, index) {
        if (leg.instrumentType !== 'OPTION' || !leg.expiration || !leg.strike) return;
        var cacheKey = market.symbol + '|' + leg.expiration;
        if (!chainData[cacheKey] && !chainCache[cacheKey]) {
          loadChain(leg.expiration).then(function () { refreshVisual(); });
        }
        var loaded = chainData[cacheKey];
        var quotes = loaded && (leg.optionType === 'CALL' ? loaded.calls : loaded.puts) || [];
        var strikes = quotes.map(function (quote) { return Number(quote.strike); }).filter(Number.isFinite)
          .sort(function (a, b) { return a - b; });
        if (strikes.length < 2) return;
        handles.push({ id: 'position-leg-' + index, strike: Number(leg.strike),
          label: leg.action + ' ' + leg.optionType.charAt(0) + ' ' + leg.strike, strikes: strikes,
          onChange: function (next) {
            draft.legs[index].strike = String(next); remember();
            var row = editorHost && editorHost.querySelector('.position-leg[data-leg-index="' + index + '"]');
            var strikeInput = row && Array.from(row.querySelectorAll('input[type="number"]')).find(function (input) {
              return input.step === '0.01' && input.min === '0.0001';
            });
            if (strikeInput) strikeInput.value = String(next);
            refreshVisual();
          } });
      });
      var exactEnteredFills = draft.legs.length > 0 && draft.legs.every(function (leg) {
        return leg.price !== '' && Number.isFinite(Number(leg.price));
      });
      var missing = provisional && provisional.missing || [];
      var chartStale = draft.terminalDirty || payoffUnavailable || !points || points.length < 2;
      visualStatusHost.innerHTML = '';
      visualStatusHost.appendChild(el('div', { class: 'position-editor-chart-head' },
        el('b', {}, analysisCurrent ? 'Analyzed payoff' : 'Payoff shape while editing'),
        el('span', { class: 'muted small' }, draft.terminalDirty ? 'Typing in progress · preview held'
          : analysisCurrent ? 'Exact analyzed package'
            : provisional && !provisional.complete ? 'Partial preview · ' + provisional.draft.legs.length + ' of ' + draft.legs.length + ' legs priced'
              : provisional && provisional.modeled ? 'Blank fills use current listed midpoints for this preview'
                : exactEnteredFills ? 'Entered leg prices drive this payoff preview'
                  : 'Choose listed contracts or enter fills')));
      if (points && points.length > 1) {
        chartHost.innerHTML = '';
        chartHost.appendChild(UI.payoffChart(points, { spot: market && market.spot || null,
          handles: handles.length ? handles : null }));
        chartHost.classList.toggle('is-partial', missing.length > 0);
        chartHost.classList.remove('is-stale');
        if (missing.length) visualStatusHost.appendChild(UI.actionFeedback('caution', 'Partial payoff only',
          missing.map(function (item) { return 'Leg ' + (item.index + 1) + ': ' + item.reason; }).join(' · ')
            + '. The curve excludes those legs; it is not the full position result.'));
      } else if (payoffUnavailable) {
        chartHost.classList.add('is-stale');
        visualStatusHost.appendChild(UI.actionFeedback('caution', 'A single-expiry curve does not fit this position', payoffUnavailable));
      } else if (chartHost.hasChildNodes()) {
        chartHost.classList.add('is-stale');
        visualStatusHost.appendChild(UI.actionFeedback('caution', 'Last defensible curve kept on screen',
          draft.terminalDirty ? 'Finish the terminal line or pause typing; the chart will update automatically.'
            : 'Complete the unfinished leg or load its listed quote. The dimmed curve describes the last priceable edit, not the current package.'));
      } else {
        chartHost.appendChild(UI.emptyState('Payoff waiting for a defensible price',
          'Enter a proposed fill, or choose a listed contract so the preview can use its current midpoint. Blank never means zero.'));
      }
      visualHost.classList.toggle('is-stale', chartStale && chartHost.hasChildNodes());
    }

    function renderChainPicker(parent) {
      var box = el('section', { class: 'position-chain-picker' },
        el('div', { class: 'plan-section-head' }, el('div', {}, el('h3', {}, 'Add from the chain'),
          el('p', { class: 'muted small' }, 'Each strike button adds one listed contract to Your trade. The leg appears and receives focus immediately.'))));
      parent.appendChild(box);
      if (!market || market.loading) { box.appendChild(el('p', { class: 'muted' }, 'Loading listed contracts...')); return; }
      if (market.error || !market.expirations.length) {
        box.appendChild(el('p', { class: 'muted' }, market.error || 'No listed option chain is available; manual entry stays available below.')); return;
      }
      var exp = el('select', {}, market.expirations.map(function (value) {
        return el('option', { value: value, selected: value === draft.chainExpiration ? 'selected' : null }, value);
      }));
      var type = el('select', {}, ['PUT', 'CALL'].map(function (value) {
        return el('option', { value: value, selected: value === draft.chainType ? 'selected' : null }, value === 'PUT' ? 'Puts' : 'Calls');
      }));
      var action = el('select', {}, ['BUY', 'SELL'].map(function (value) {
        return el('option', { value: value, selected: value === draft.chainAction ? 'selected' : null }, value === 'BUY' ? 'Buy' : 'Sell');
      }));
      action.onchange = function () { draft.chainAction = action.value; remember(); };
      box.appendChild(el('div', { class: 'position-chain-controls' }, field('Expiration', exp), field('Contract', type),
        field('Add as', action, 'This side applies only to the next strike you add. Every leg remains editable below.')));
      var rail = el('div', { class: 'position-chain-rail', 'aria-live': 'polite' }, el('span', { class: 'muted' }, 'Loading strikes...'));
      box.appendChild(rail);
      var railToken = 0;
      function paintRail() {
        var token = ++railToken;
        rail.innerHTML = '';
        rail.appendChild(el('span', { class: 'muted' }, 'Loading strikes...'));
        loadChain(draft.chainExpiration).then(function (chain) {
        if (token !== railToken) return;
        if (!rail.isConnected) return;
        rail.innerHTML = '';
        var rows = chain && (draft.chainType === 'CALL' ? chain.calls : chain.puts) || [];
        var spot = market.spot || 0;
        rows = rows.slice().sort(function (a, b) { return Math.abs(Number(a.strike) - spot) - Math.abs(Number(b.strike) - spot); }).slice(0, 11)
          .sort(function (a, b) { return Number(a.strike) - Number(b.strike); });
        var cacheKey = market.symbol + '|' + draft.chainExpiration;
        if (!rows.length && chainErrors[cacheKey]) {
          rail.append(el('span', { class: 'muted' }, 'Could not load this chain. Manual entry remains available. ' + chainErrors[cacheKey]),
            el('button', { type: 'button', class: 'btn btn-secondary btn-sm', onclick: function () {
              delete chainCache[cacheKey]; delete chainErrors[cacheKey]; paintRail();
            } }, 'Retry'));
          return;
        }
        if (!rows.length) { rail.appendChild(el('span', { class: 'muted' }, 'No strikes available for this expiration.')); return; }
        rows.forEach(function (quote) {
          rail.appendChild(el('button', { type: 'button', class: 'position-chain-contract', onclick: function () {
            var selectedLeg = { instrumentType: 'OPTION', action: draft.chainAction, positionEffect: 'OPEN',
              symbol: market.symbol, optionType: draft.chainType, strike: String(quote.strike),
              expiration: draft.chainExpiration, quantity: 1, multiplier: 100, price: '', section1256: null };
            var replace = draft.legs.length === 1 && !draft.legs[0].strike && !draft.legs[0].expiration
              && draft.legs[0].price === '';
            addLeg(selectedLeg, replace);
          } }, el('b', {}, '$' + Number(quote.strike).toFixed(2)),
          el('span', { class: 'muted small' }, (quote.bid == null ? '-' : Number(quote.bid).toFixed(2))
            + ' / ' + (quote.ask == null ? '-' : Number(quote.ask).toFixed(2)))));
        });
        refreshVisual();
      });
      }
      exp.onchange = function () { draft.chainExpiration = exp.value; remember(); paintRail(); refreshVisual(); };
      type.onchange = function () { draft.chainType = type.value; remember(); paintRail(); };
      paintRail();
    }

    function renderAnalysis(out) {
      if (!resultHost || !resultHost.isConnected) return;
      resultHost.innerHTML = '';
      var response = out && (out.preview || out);
      var selected = out && out.strategy && out.strategy.result && out.strategy.result.candidate;
      var analysis = out && out.evaluation
        ? out.evaluation : selected && selected.evaluation ? selected.evaluation : null;
      var assessmentUnavailable = analysis && analysis.available === false;
      var economics = out && out.evaluation && out.evaluation.assessment
          ? out.evaluation.assessment.economics
          : selected && selected.evaluation && selected.evaluation.assessment
            ? selected.evaluation.assessment.economics : null;
      if (!response) return;
      var notSelected = options.planId && (!selected || selected.selected !== true);
      resultHost.appendChild(UI.actionFeedback(response.ok === false || assessmentUnavailable ? 'caution' : 'ok',
        response.ok === false && options.planId ? 'Not selected in this Plan'
          : response.ok === false ? 'Analyzed with constraints'
            : assessmentUnavailable && selected && selected.selected === true ? 'Selected with limited assessment'
              : assessmentUnavailable ? 'Mechanical preview ready' : 'Analysis complete',
        response.ok === false
          ? ((response.blockReasons || []).join(' ') + (notSelected
            ? ' This package did not become the Plan structure.'
              + (selected && selected.selectionCleared ? ' The prior selection was cleared.' : '') : '')).trim()
          : assessmentUnavailable
            ? ((selected && selected.selected === true
              ? 'This mechanically valid package is now the Plan structure. ' : '') + analysis.unavailableReason)
            : 'The exact edited package now drives these facts.'));
      if (isPast(draft.occurredAt)) {
        resultHost.appendChild(UI.actionFeedback('caution', 'Current fresh-eyes analysis',
          'The entry date is preserved, but this result uses the market evidence available now. Retrospective replay will use the dated historical path and label each modeled or observed leg-day separately.'));
      }
      var canonicalPop = analysis && analysis.risk ? analysis.risk.pop : null;
      resultHost.appendChild(el('div', { class: 'grid grid-4 position-analysis-stats' },
        response.entryNetPremiumCents == null ? null : UI.stat(response.entryNetPremiumCents >= 0 ? 'Credit' : 'Cost', fmtMoney(Math.abs(response.entryNetPremiumCents))),
        response.maxLossCents == null ? null : UI.stat(UI.vocabulary('theoreticalMaxLoss'), fmtMoney(response.maxLossCents)),
        response.maxProfitCents == null ? UI.stat('Theoretical max profit', localPayoffUnavailableReason(draft)
          ? (new Set(draft.legs.filter(function (leg) { return leg.instrumentType === 'OPTION'; }).map(function (leg) { return leg.expiration; })).size > 1
            ? 'Model-dependent' : 'Requires resulting position')
          : 'Unlimited in theory') : null,
        response.maxProfitCents != null ? UI.stat('Theoretical max profit', fmtMoney(response.maxProfitCents)) : null,
        canonicalPop == null ? null : UI.stat('Chance of profit', UI.fmtPct(canonicalPop))));
      var evidence = response.evidence;
      if (evidence) resultHost.appendChild(el('div', { class: 'position-coverage' },
        el('span', { class: 'muted small' }, 'Pricing evidence '), UI.evidenceBadge(evidence)));
      if (economics) resultHost.appendChild(el('div', { class: 'chip-row' }, chip('Economic view',
        economics.label || UI.economicVerdictLabel(economics.verdict)),
        economics.marketEvAfterCostsCents == null ? null : chip('Market-implied EV after costs', fmtMoney(economics.marketEvAfterCostsCents, { plus: true }))));
      if (analysis && window.ViewPlan && window.ViewPlan.decisionMetricsReceipt) {
        resultHost.appendChild(window.ViewPlan.decisionMetricsReceipt(analysis, Learn.currentLevel() === 'beginner'));
      }
      if (out && out.marketLane) resultHost.appendChild(el('div', { class: 'position-account-basis' },
        out.accountName ? el('span', { class: 'muted small' }, out.accountName + ' · ') : null,
        UI.evidenceBadge({ provenance: out.marketLane }, { compact: true }),
        el('span', { class: 'muted small' }, ' · ' + (out.note || 'Account-specific read-only analysis.'))));
      var fillBases = Array.from(new Set((response.legs || []).map(function (leg) { return leg.fillBasis; }).filter(Boolean)));
      if (fillBases.length) resultHost.appendChild(el('p', { class: 'muted small position-fill-basis' },
        'Entry-price receipt: ' + fillBases.map(function (basis) { return ({
          USER_EXECUTED: 'entered broker fill', USER_PROPOSED: 'entered proposed price',
          EXECUTABLE_BOOK: 'current executable book', LABELED_MODEL_OR_MID: 'labeled model or midpoint'
        })[String(basis).toUpperCase()] || 'labeled price input'; }).join(' + ') + '.'));
      if (options.planId && response.ok !== false && selected && selected.selected === true) resultHost.appendChild(el('button', { type: 'button', class: 'btn',
        onclick: function () { App.navigate('#/plan/' + options.planId + '/outcomes'); } }, 'Continue to Outcomes'));
      revealResult();
    }

    function renderDurableSelection() {
      var selected = options.initialSelection;
      if (!selected || !draft.selectedCandidateId || selected.id !== draft.selectedCandidateId
          || draft.selectedFingerprint !== draftFingerprint(draft)) return;
      resultHost.appendChild(UI.actionFeedback('ok', 'Selected in this Plan',
        'This exact edited package is the Plan structure. Change any leg or price and this confirmation clears until you analyze again.'));
      resultHost.appendChild(el('div', { class: 'grid grid-4 position-analysis-stats' },
        selected.entryNetPremiumCents == null ? null : UI.stat(selected.entryNetPremiumCents >= 0 ? 'Credit' : 'Cost', fmtMoney(Math.abs(selected.entryNetPremiumCents))),
        selected.maxLossCents == null ? null : UI.stat(UI.vocabulary('theoreticalMaxLoss'), fmtMoney(selected.maxLossCents)),
        selected.evaluation && selected.evaluation.risk && selected.evaluation.risk.pop != null
          ? UI.stat('Chance of profit', UI.fmtPct(selected.evaluation.risk.pop)) : null));
      var savedAnalysis = selected.evaluation;
      var savedEconomics = savedAnalysis && savedAnalysis.assessment && savedAnalysis.assessment.economics;
      if (savedEconomics) resultHost.appendChild(el('div', { class: 'chip-row' },
        chip('Economic view', savedEconomics.label || UI.economicVerdictLabel(savedEconomics.verdict)),
        savedEconomics.marketEvAfterCostsCents == null ? null
          : chip('Market-implied EV after costs', fmtMoney(savedEconomics.marketEvAfterCostsCents, { plus: true }))));
      if (savedAnalysis && window.ViewPlan && window.ViewPlan.decisionMetricsReceipt) {
        resultHost.appendChild(window.ViewPlan.decisionMetricsReceipt(
          savedAnalysis, Learn.currentLevel() === 'beginner'));
      }
      resultHost.appendChild(el('button', { type: 'button', class: 'btn',
        onclick: function () { App.navigate('#/plan/' + options.planId + '/outcomes'); } }, 'Continue to Outcomes'));
    }

    function revealResult() {
      if (!resultHost) return;
      clearResultStale();
      resultHost.classList.add('arrival-highlight');
      resultHost.setAttribute('tabindex', '-1');
      resultHost.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      resultHost.focus({ preventScroll: true });
      setTimeout(function () {
        if (resultHost && resultHost.isConnected) resultHost.classList.remove('arrival-highlight');
      }, 1400);
    }

    function showCommandError(title, error) {
      if (!resultHost) return;
      var old = resultHost.querySelector(':scope > .position-command-error');
      if (old) old.remove();
      var feedback = UI.actionFeedback('danger', title, error.message || String(error));
      feedback.classList.add('position-command-error');
      resultHost.prepend(feedback);
      revealResult();
    }

    async function runAnalyze() {
      if (commandBusy) return null;
      try { syncTerminal(); } catch (error) { showInputError(error); throw error; }
      commandBusy = true; syncBusy();
      try {
        var payload = normalizedForAnalysis(draft);
        var out = await options.onAnalyze(payload, draft);
        lastAnalysis = out; lastAnalysisFingerprint = draftFingerprint(draft);
        var response = out && (out.preview || out);
        var selected = out && out.strategy && out.strategy.result && out.strategy.result.candidate;
        if (response && response.ok !== false && selected && selected.id && selected.selected === true) {
          draft.selectedCandidateId = selected.id;
          draft.selectedFingerprint = lastAnalysisFingerprint;
          options.initialSelection = selected;
          remember();
        } else if (options.planId && response) {
          draft.selectedCandidateId = null;
          draft.selectedFingerprint = null;
          options.initialSelection = null;
          remember();
        }
        renderAnalysis(out);
        return out;
      } catch (error) {
        showCommandError('Could not analyze', error);
        return Promise.reject(error);
      } finally { commandBusy = false; syncBusy(); refreshVisual(); }
    }

    async function recordNow() {
      if (options.recordDisabled) throw new Error(options.recordDisabledReason || 'Recording is unavailable here.');
      var payload = recordPayload(draft);
      if (typeof options.findSimilar === 'function' && draft.similarityDecision !== 'ADD') {
        var similar = await options.findSimilar(payload, draft) || [];
        if (similar.length) {
          showSimilarity(similar); return null;
        }
      }
      var out = await options.onRecord(payload, draft);
      draft.similarityDecision = null;
      var recordedOutcome = null;
      if (typeof options.onRecorded === 'function') {
        try { recordedOutcome = await options.onRecorded(out, draft); }
        catch (error) {
          recordedOutcome = UI.actionFeedback('caution', 'Recorded; account totals could not refresh',
            error.message || String(error));
        }
      }
      if (resultHost) {
        resultHost.innerHTML = '';
        resultHost.appendChild(UI.actionFeedback('ok', 'Recorded in ' + (options.accountName || 'tracked account'),
          'The factual activity is now part of the append-only tracked ledger.'));
        if (out && Array.isArray(out.legs) && out.legs.some(function (leg) { return leg.section1256 === true; })) {
          resultHost.appendChild(el('div', { class: 'chip-row position-record-tax-character' },
            chip('Tax character', 'Automatic · Section 1256')));
        }
        if (recordedOutcome) resultHost.appendChild(recordedOutcome);
        revealResult();
      }
      return out;
    }

    function showSimilarity(similar) {
      resultHost.innerHTML = '';
      var first = similar[0];
      resultHost.appendChild(UI.actionFeedback('caution', 'A similar position already exists',
        'These records look alike, but StrikeBench will not merge them. A second position may be an intentional scale-in.'));
      resultHost.appendChild(el('p', {}, first.label || first.symbol || 'Existing position'));
      resultHost.appendChild(el('div', { class: 'btn-row' },
        el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () {
          if (options.onLinkSimilar) options.onLinkSimilar(first); else App.navigate('#/portfolio/book/overview');
        } }, 'Open existing'),
        el('button', { type: 'button', class: 'btn', onclick: async function () {
          draft.similarityDecision = 'ADD'; remember();
          try { commandBusy = true; syncBusy(); await recordNow(); }
          catch (error) { resultHost.appendChild(UI.actionFeedback('danger', 'Could not record', error.message || String(error))); }
          finally { commandBusy = false; syncBusy(); }
        } }, 'Add as a new lot'),
        el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () {
          draft.similarityDecision = 'CANCEL'; remember(); resultHost.innerHTML = '';
        } }, 'Cancel')));
    }

    async function runRecord() {
      if (commandBusy) return null;
      try { syncTerminal(); } catch (error) { showInputError(error); throw error; }
      commandBusy = true; syncBusy();
      try { return await recordNow(); }
      catch (error) {
        showCommandError('Could not record', error);
        return Promise.reject(error);
      } finally { commandBusy = false; syncBusy(); }
    }

    async function runBoth() {
      if (commandBusy) return;
      try { syncTerminal(); } catch (error) { showInputError(error); throw error; }
      // Validate both contracts before either command mutates anything.
      normalizedForAnalysis(draft); recordPayload(draft);
      commandBusy = true; syncBusy();
      var analyzed = null, recorded = null, analysisError = null;
      try { analyzed = await options.onAnalyze(normalizedForAnalysis(draft), draft); lastAnalysis = analyzed; }
      catch (error) { analysisError = error; }
      try { recorded = await recordNow(); }
      finally { commandBusy = false; syncBusy(); }
      if (!recorded && resultHost && resultHost.querySelector('.inline-action-feedback')) return { analysis: analyzed, record: null };
      if (recorded && analysisError && resultHost) resultHost.appendChild(UI.actionFeedback('caution', 'Recorded; analysis unavailable', analysisError.message || String(analysisError)));
      else if (analyzed && recorded && resultHost) {
        var response = analyzed.preview || analyzed;
        resultHost.appendChild(UI.actionFeedback('ok', 'Analysis also completed', response.ok === false
          ? (response.blockReasons || []).join(' ') : 'The same exact package was analyzed before the factual activity was recorded.'));
      } else if (analyzed) renderAnalysis(analyzed);
      return { analysis: analyzed, record: recorded };
    }

    function syncBusy() {
      root.classList.toggle('is-busy', commandBusy);
      if (commandBusy) root.setAttribute('aria-busy', 'true'); else root.removeAttribute('aria-busy');
      Array.from(root.querySelectorAll('input,select,textarea,button')).forEach(function (control) {
        if (commandBusy && !control.disabled) {
          control.dataset.positionBusyDisabled = 'true'; control.disabled = true;
        } else if (!commandBusy && control.dataset.positionBusyDisabled === 'true') {
          delete control.dataset.positionBusyDisabled; control.disabled = false;
        }
        if (control.dataset.positionCommand) {
          var recording = control.dataset.positionCommand === 'record' || control.dataset.positionCommand === 'both';
          control.disabled = commandBusy || recording && options.recordDisabled;
          if (commandBusy) control.setAttribute('aria-busy', 'true'); else control.removeAttribute('aria-busy');
        }
      });
    }

    function renderHeader() {
      headerHost.innerHTML = '';
      modeButtons = {};
      var mode = el('div', { class: 'segmented position-editor-mode', role: 'group', 'aria-label': 'Trade entry mode' });
      ['visual', 'terminal'].forEach(function (value) {
        var button = el('button', { type: 'button', class: draft.mode === value ? 'active' : '',
          'aria-pressed': draft.mode === value ? 'true' : 'false', onclick: function () { setMode(value); }
        }, value === 'visual' ? 'Visual' : 'Terminal');
        modeButtons[value] = button; mode.appendChild(button);
      });
      headerHost.appendChild(el('div', { class: 'plan-tool-intro position-editor-head' },
        el('div', {}, el('span', { class: 'eyebrow' }, 'YOUR TRADE'),
          el('h3', {}, options.title || 'Build, analyze, or record the exact package'),
          el('p', { class: 'muted' }, options.description || 'The edited legs are shared. Analyze may fill evidence gaps; Record accepts factual broker fills only.')),
        el('div', {}, mode)));
    }

    function refreshChainPicker() {
      if (!chainPickerHost || !chainPickerHost.isConnected) return;
      chainPickerHost.innerHTML = '';
      renderChainPicker(chainPickerHost);
    }

    function refreshRecordingDetails() {
      if (!recordingDetailsHost) return;
      var netCents = enteredPackageNetCents(draft);
      recordingDetailsHost.replaceChildren(
        el('div', { class: 'chip-row' },
          chip('Package net', netCents == null ? 'Needs every leg fill' : fmtMoney(netCents)),
          chip('Record source', draft.externalRef.trim() ? 'Broker record' : 'Entered here'),
          chip('Price meaning', 'Executed fills')),
        el('p', { class: 'muted small' },
          'Package cash is calculated from the exact leg fills above. Adding a broker reference stops the same fill being recorded twice.'));
    }

    function renderPackageMeta(host) {
      host.innerHTML = '';
      recordingDetailsHost = null;
      if (options.allowRecord) {
        draft.packageNet = '';
        draft.fillNature = 'EXECUTED';
        draft.feeMode = 'EXACT';
        draft.source = draft.externalRef.trim() ? 'BROKER' : 'MANUAL';
        var recordedAt = bind(el('input', { type: 'datetime-local', step: '1', value: draft.occurredAt }), draft, 'occurredAt');
        var exactFees = bind(el('input', { type: 'number', min: '0', step: '0.01', value: draft.fees,
          placeholder: '0.00' }), draft, 'fees');
        var brokerRef = bind(el('input', { type: 'text', maxlength: '160', value: draft.externalRef,
          placeholder: 'Optional order or statement reference' }), draft, 'externalRef');
        var recordNotes = bind(el('textarea', { rows: '2', maxlength: '1000', value: draft.notes,
          placeholder: 'Optional context' }), draft, 'notes');
        var recordMeta = el('div', { class: 'form-grid position-package-meta position-record-meta' },
          field('When it happened', recordedAt,
            'The exact broker date and time. Past activity remains a fact even when current analysis uses fresh market evidence.'),
          field('Total fees $', exactFees, 'Enter 0.00 when the broker charged no fee.'),
          field('Broker reference', brokerRef, 'Optional. When present, it stops the same fill being recorded twice.'),
          field('Notes', recordNotes));
        host.appendChild(recordMeta);
        if (Learn.currentLevel() === 'expert') {
          recordingDetailsHost = el('div', { class: 'position-recording-details' });
          host.appendChild(UI.expandable('Recording details', function () { return recordingDetailsHost; }, {
            stateKey: 'position-recording-' + key
          }));
          refreshRecordingDetails();
        }
        return;
      }
      var packageNet = bind(el('input', { type: 'number', step: '0.01', value: draft.packageNet,
        placeholder: '+ credit / - debit' }), draft, 'packageNet');
      var feeMode = el('select', {}, el('option', { value: 'DEFAULT', selected: draft.feeMode === 'DEFAULT' ? 'selected' : null }, 'Use StrikeBench estimate for Analyze'),
        el('option', { value: 'EXACT', selected: draft.feeMode === 'EXACT' ? 'selected' : null }, 'Enter exact total fees'));
      feeMode.onchange = function () { draft.feeMode = feeMode.value; remember(); renderPackageMeta(host); refreshVisual(); };
      var fees = bind(el('input', { type: 'number', min: '0', step: '0.01', value: draft.fees,
        placeholder: draft.feeMode === 'EXACT' ? '0.00' : 'platform default' }), draft, 'fees');
      var meta = el('div', { class: 'form-grid position-package-meta' },
        field('Package net $', packageNet, 'Optional signed total before fees: positive credit, negative debit.'),
        field('Fee treatment', feeMode), draft.feeMode === 'EXACT' || options.allowRecord ? field('Total fees $', fees,
          options.allowRecord ? 'RECORD requires this factual total. Enter 0.00 explicitly when the broker charged no fee.' : null) : null);
      if (options.onAnalyze) {
        var fillNature = bind(el('select', {},
          el('option', { value: 'PROPOSED', selected: draft.fillNature === 'PROPOSED' ? 'selected' : null }, 'Proposed / hypothetical'),
          el('option', { value: 'EXECUTED', selected: draft.fillNature === 'EXECUTED' ? 'selected' : null }, 'Executed fill')), draft, 'fillNature');
        var analysisAt = bind(el('input', { type: 'datetime-local', step: '1', value: draft.occurredAt }), draft, 'occurredAt');
        meta.append(field('Price meaning', fillNature,
          'A proposed price is tested as an order. An executed price is treated as a fact, while blank legs still use labeled evidence.'),
          field(options.allowRecord ? 'Executed / analyzed at' : 'Entry / analysis date', analysisAt,
            'Past dates stay attached to the draft. Analyze is fresh-eyes until a retrospective replay is run.'));
      }
      var notes = bind(el('textarea', { rows: '2', maxlength: '1000', value: draft.notes,
        placeholder: 'Optional context' }), draft, 'notes');
      notes.value = draft.notes;
      host.append(meta, field('Notes', notes));
    }

    function renderInputs() {
      editorHost.innerHTML = '';
      chainPickerHost = null;
      if (draft.mode === 'terminal') {
        var terminal = el('textarea', { class: 'position-terminal', rows: '7', spellcheck: 'false',
          'aria-label': 'Terminal position lines', value: draft.terminal || terminalText(draft.legs),
          placeholder: '-1 MU 13Jul26 980P @20.00\n+100 MU @979.30' });
        terminal.value = draft.terminal || terminalText(draft.legs);
        terminal.oninput = function () {
          draft.terminal = terminal.value; draft.terminalDirty = true; remember(true); refreshVisual();
          if (terminalTimer) clearTimeout(terminalTimer);
          terminalTimer = setTimeout(function () {
            try {
              var parsed = parseTerminal(draft.terminal, options.lockedSymbol && String(options.lockedSymbol).toUpperCase());
              draft.legs = parsed; draft.symbol = options.lockedSymbol || parsed[0].symbol;
              draft.terminalDirty = false; remember(true); refreshVisual();
            } catch (ignored) { /* incomplete terminal text keeps the prior curve visibly stale */ }
          }, 350);
        };
        editorHost.append(el('p', { class: 'muted small' },
          'Signed quantity sets buy (+) or sell (-). Fill after @ is optional for analysis; use Apply when you want immediate validation.'),
          terminal, el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () {
            try { draft.terminal = terminal.value; draft.terminalDirty = true; syncTerminal(); refreshVisual(); }
            catch (error) { showInputError(error); }
          } }, 'Apply lines')));
      } else {
        if (options.chain !== false) {
          chainPickerHost = el('div', { class: 'position-chain-picker-host' });
          editorHost.appendChild(chainPickerHost); refreshChainPicker();
        }
        var legList = el('div', { class: 'position-leg-list' });
        draft.legs.forEach(function (leg, index) { legList.appendChild(legRow(leg, index)); });
        editorHost.append(legList, el('button', { type: 'button', class: 'btn btn-secondary btn-sm position-add-leg',
          onclick: function () { addLeg(blankLeg(options.lockedSymbol || draft.symbol), false); } }, '+ Add leg'));
      }
      if (options.compositionOnly) {
        editorHost.appendChild(el('p', { class: 'muted small position-composition-note' },
          'This describes what remains open. Unchanged quantities keep stored fills; changed quantities use current executable prices.'));
      } else {
        var metaHost = el('div', { class: 'position-package-meta-host' });
        editorHost.appendChild(metaHost); renderPackageMeta(metaHost);
      }
      syncBusy();
    }

    function renderCommands() {
      commandHost.innerHTML = '';
      commandHost.appendChild(el('div', { class: 'position-command-heading' },
        el('b', {}, options.allowRecord && options.recordPrimary ? 'Record or inspect' : 'Use this exact package'),
        el('span', { class: 'muted small' }, 'The result appears directly below this workbench.')));
      var commands = el('div', { class: 'btn-row position-editor-commands' });
      var analyzeCommand = options.onAnalyze ? el('button', { type: 'button',
        class: options.recordPrimary ? 'btn btn-secondary' : 'btn', 'data-position-command': 'analyze',
        disabled: commandBusy ? 'disabled' : null, onclick: function () { runAnalyze().catch(function () {}); }
      }, options.analyzeLabel || 'Analyze this trade') : null;
      var recordCommand = null, bothCommand = null;
      if (options.allowRecord && options.onRecord) {
        recordCommand = el('button', { type: 'button', class: options.recordPrimary ? 'btn' : 'btn btn-secondary',
          'data-position-command': 'record', disabled: commandBusy || options.recordDisabled ? 'disabled' : null,
          'aria-describedby': options.recordDisabled ? 'position-record-disabled-' + key.replace(/[^a-zA-Z0-9_-]/g, '-') : null,
          onclick: function () { runRecord().catch(function () {}); } }, 'Record factual activity');
        if (options.onAnalyze) bothCommand = el('button', { type: 'button', class: 'btn btn-secondary',
          'data-position-command': 'both', disabled: commandBusy || options.recordDisabled ? 'disabled' : null,
          onclick: function () { runBoth().catch(function (error) { showCommandError('Could not complete both commands', error); }); }
        }, 'Analyze and record');
      }
      [options.recordPrimary && recordCommand, analyzeCommand,
        !options.recordPrimary && recordCommand, bothCommand].filter(Boolean).forEach(function (button) {
        commands.appendChild(button);
      });
      if (options.recordDisabled) commands.appendChild(el('span', {
        id: 'position-record-disabled-' + key.replace(/[^a-zA-Z0-9_-]/g, '-'), class: 'muted small'
      }, options.recordDisabledReason || 'Recording is unavailable here.'));
      commandHost.appendChild(commands);
      syncBusy();
    }

    function renderShell() {
      root.innerHTML = '';
      root.className = 'position-editor';
      root.onkeydown = function (event) {
        if (event.altKey && event.key.toLowerCase() === 'v') {
          event.preventDefault(); setMode(draft.mode === 'visual' ? 'terminal' : 'visual');
        }
      };
      headerHost = el('div', { class: 'position-editor-header-region' });
      editorHost = el('div', { class: 'position-editor-inputs' });
      visualHost = el('div', { class: 'position-editor-visual' });
      visualIdentityHost = el('div', { class: 'position-visual-identity-region' });
      visualStatusHost = el('div', { class: 'position-visual-status-region', 'aria-live': 'polite' });
      chartHost = el('div', { class: 'position-chart-region' });
      commandHost = el('div', { class: 'position-command-region' });
      visualHost.append(visualIdentityHost, visualStatusHost, chartHost, commandHost);
      resultHost = el('div', { class: 'position-editor-result', 'aria-live': 'polite' });
      root.append(headerHost, UI.positionWorkbench(editorHost, visualHost), resultHost);
      renderHeader(); renderInputs(); renderCommands();
      if (rejectedDraftReason) {
        var rejected = UI.actionFeedback('caution', 'Incomplete local draft was rejected',
          rejectedDraftReason + ' A new current-format draft is shown instead.');
        root.insertBefore(rejected, root.children[1]); rejectedDraftReason = null;
      }
      refreshVisual();
      scheduleIdentity();
      if (lastAnalysis && lastAnalysisFingerprint === draftFingerprint(draft)) renderAnalysis(lastAnalysis);
      else renderDurableSelection();
      ensureMarket();
    }

    renderShell();
    return { draft: function () { return draft; }, analyze: runAnalyze, record: runRecord,
      readAnalysis: function () { syncTerminal(); return normalizedForAnalysis(draft); },
      readRecord: function () { syncTerminal(); return recordPayload(draft); } };
  }

  window.PositionEditor = { render: render, cleanDraft: cleanDraft, parseTerminal: parseTerminal,
    terminalText: terminalText, localPayoffUnavailableReason: localPayoffUnavailableReason,
    analysisPayload: normalizedForAnalysis, recordPayload: recordPayload, draftFromCandidate: draftFromCandidate };
})();
