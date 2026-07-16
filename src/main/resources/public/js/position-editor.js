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
      packageNet: raw.packageNet == null ? '' : String(raw.packageNet),
      fillNature: raw.fillNature || (options.allowRecord ? 'EXECUTED' : 'PROPOSED'),
      feeMode: raw.feeMode || (options.allowRecord ? 'EXACT' : 'DEFAULT'),
      fees: raw.fees == null ? '' : String(raw.fees),
      occurredAt: raw.occurredAt == null ? '' : String(raw.occurredAt),
      source: raw.source || 'MANUAL',
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
    var identified = identify(draft.legs);
    return { symbol: draft.symbol || draft.legs[0].symbol,
      strategy: identified.family || identified.key || 'CUSTOM', qty: packageQty, legs: legs,
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
    if (draft.fillNature !== 'EXECUTED') {
      throw new Error('RECORD accepts executed broker facts only. Change Price meaning to Executed fill, or use ANALYZE for a proposal.');
    }
    if (!draft.occurredAt || !Number.isFinite(new Date(draft.occurredAt).getTime())) {
      throw new Error('RECORD requires the date and time the broker activity occurred.');
    }
    if (draft.legs.some(function (leg) { return !validLeg(leg, true); })) {
      throw new Error('RECORD requires every factual symbol, quantity, multiplier, contract, and positive fill price. Use ANALYZE when a fill is unknown.');
    }
    if (draft.source === 'BROKER' && !draft.externalRef.trim()) {
      throw new Error('Broker-sourced activity requires a stable order or statement reference.');
    }
    if (draft.feeMode !== 'EXACT' || draft.fees === '') {
      throw new Error('RECORD requires the exact total fees from the broker, including an explicit 0.00 when no fee was charged. Analyze may use the StrikeBench estimate.');
    }
    var fees = Number(draft.fees);
    if (!Number.isFinite(fees) || fees < 0) throw new Error('Exact recorded fees must be zero or more.');
    var net = draft.packageNet === '' ? null : Number(draft.packageNet);
    if (net !== null && !Number.isFinite(net)) throw new Error('Package net must be a number.');
    return { occurredAt: new Date(draft.occurredAt).toISOString(), eventType: 'TRADE', fillNature: 'EXECUTED',
      cashAmountCents: net === null ? null : Math.round((net - fees) * 100),
      feesCents: Math.round(fees * 100), taxCategory: null, source: draft.source,
      externalRef: draft.externalRef.trim() || null, notes: draft.notes.trim() || null,
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
      throw new Error('Line ' + (index + 1) + ' is not understood. Try "-1 MU 13Jul26 980P @20.00 CLOSE 1256" or "+100 MU @979.30 OPEN".');
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

  function identify(rawLegs) {
    var legs = (rawLegs || []).filter(function (leg) { return validLeg(leg, false); });
    if (!legs.length || legs.some(function (leg) { return leg.positionEffect === 'CLOSE'; })) {
      return catalogResult(null, null, 'Custom transaction', 'Closing and mixed-effect activity is identified from the resulting position, not the transaction by itself.');
    }
    var stock = legs.filter(function (leg) { return leg.instrumentType === 'STOCK'; });
    var opt = legs.filter(function (leg) { return leg.instrumentType === 'OPTION'; });
    function one(action, type) { return opt.length === 1 && opt[0].action === action && opt[0].optionType === type; }
    if (stock.length === 1 && stock[0].action === 'BUY') {
      var stockShares = Number(stock[0].quantity);
      if (one('SELL', 'CALL') && stockShares >= Number(opt[0].quantity) * Number(opt[0].multiplier)) return catalogResult('COVERED_CALL', 'BUY_WRITE');
      if (one('BUY', 'PUT') && stockShares >= Number(opt[0].quantity) * Number(opt[0].multiplier)) return catalogResult('PROTECTIVE_PUT', 'MARRIED_PUT');
      if (opt.length === 2 && oneEach(opt, 'BUY', 'PUT', 'SELL', 'CALL')
          && stockShares >= Math.max.apply(null, opt.map(function (leg) { return Number(leg.quantity) * Number(leg.multiplier); }))) {
        return catalogResult('PROTECTIVE_COLLAR', 'COLLAR');
      }
    }
    if (stock.length) return catalogResult(null, null, 'Custom stock-and-option package');
    if (opt.length === 1) {
      if (one('BUY', 'CALL')) return catalogResult('LONG_CALL');
      if (one('BUY', 'PUT')) return catalogResult('LONG_PUT');
      if (one('SELL', 'CALL')) return catalogResult('NAKED_CALL');
      return catalogResult(null, null, 'Short put', 'Cash and protective context determine whether this is cash-secured or naked; the analysis names that distinction.');
    }
    if (opt.length === 2) {
      var a = opt[0], b = opt[1], sameExp = a.expiration === b.expiration;
      if (a.optionType !== b.optionType && sameExp) {
        var call = a.optionType === 'CALL' ? a : b, put = a.optionType === 'PUT' ? a : b;
        if (call.action === 'BUY' && put.action === 'BUY') return catalogResult(
          Number(call.strike) === Number(put.strike) ? 'LONG_STRADDLE' : 'LONG_STRANGLE');
        if (call.action === 'SELL' && put.action === 'SELL') return catalogResult(
          Number(call.strike) === Number(put.strike) ? 'SHORT_STRADDLE' : 'SHORT_STRANGLE');
        if (call.action === 'BUY' && put.action === 'SELL' && Number(call.strike) === Number(put.strike)) {
          return catalogResult(null, 'SYNTHETIC_LONG', 'Synthetic long');
        }
        if (call.action === 'SELL' && put.action === 'BUY' && Number(call.strike) === Number(put.strike)) {
          return catalogResult(null, 'SYNTHETIC_SHORT', 'Synthetic short');
        }
        if (call.action === 'BUY' && put.action === 'SELL') {
          return catalogResult(null, 'RISK_REVERSAL', 'Risk reversal');
        }
      }
      if (a.optionType === b.optionType && !sameExp) {
        var near = a.expiration < b.expiration ? a : b, far = near === a ? b : a;
        if (near.action === 'SELL' && far.action === 'BUY') {
          var calendar = Number(near.strike) === Number(far.strike);
          return catalogResult((calendar ? 'CALENDAR_' : 'DIAGONAL_') + a.optionType);
        }
      }
      if (a.optionType === b.optionType && sameExp) {
        var low = Number(a.strike) < Number(b.strike) ? a : b, high = low === a ? b : a;
        if (a.action !== b.action && Number(a.quantity) !== Number(b.quantity)) {
          var sold = a.action === 'SELL' ? a : b, bought = sold === a ? b : a;
          if (Number(bought.quantity) === Number(sold.quantity) * 2) {
            if (a.optionType === 'CALL' && Number(bought.strike) > Number(sold.strike)) {
              return catalogResult(null, 'CALL_BACKSPREAD', 'Call ratio backspread');
            }
            if (a.optionType === 'PUT' && Number(bought.strike) < Number(sold.strike)) {
              return catalogResult(null, 'PUT_BACKSPREAD', 'Put ratio backspread');
            }
          }
        }
        if (a.optionType === 'CALL') {
          if (low.action === 'BUY' && high.action === 'SELL') return catalogResult('DEBIT_CALL_SPREAD');
          if (low.action === 'SELL' && high.action === 'BUY') return catalogResult('CREDIT_CALL_SPREAD');
        } else {
          if (low.action === 'SELL' && high.action === 'BUY') return catalogResult('DEBIT_PUT_SPREAD');
          if (low.action === 'BUY' && high.action === 'SELL') return catalogResult('CREDIT_PUT_SPREAD');
        }
      }
    }
    if (opt.length === 3 && sameKindAndExpiration(opt)) {
      var sorted3 = opt.slice().sort(byStrike), q0 = Number(sorted3[0].quantity), q1 = Number(sorted3[1].quantity), q2 = Number(sorted3[2].quantity);
      if (sorted3[0].action === 'BUY' && sorted3[1].action === 'SELL' && sorted3[2].action === 'BUY'
          && q1 === q0 * 2 && q2 === q0) {
        return catalogResult(opt[0].optionType === 'CALL' ? 'LONG_CALL_BUTTERFLY' : 'LONG_PUT_BUTTERFLY');
      }
    }
    if (opt.length === 4 && allSameExpiration(opt)) {
      var puts = opt.filter(function (leg) { return leg.optionType === 'PUT'; }).sort(byStrike);
      var calls = opt.filter(function (leg) { return leg.optionType === 'CALL'; }).sort(byStrike);
      if (puts.length === 2 && calls.length === 2 && puts[0].action === 'BUY' && puts[1].action === 'SELL'
          && calls[0].action === 'SELL' && calls[1].action === 'BUY') {
        return catalogResult(Number(puts[1].strike) === Number(calls[0].strike) ? 'IRON_BUTTERFLY' : 'IRON_CONDOR');
      }
    }
    return catalogResult(null, null, 'Custom structure', 'The exact legs still receive the same payoff, risk, and outcomes analysis; no catalog name is being invented.');
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

  function oneEach(legs, a1, t1, a2, t2) {
    return legs.some(function (l) { return l.action === a1 && l.optionType === t1; })
      && legs.some(function (l) { return l.action === a2 && l.optionType === t2; });
  }
  function byStrike(a, b) { return Number(a.strike) - Number(b.strike); }
  function allSameExpiration(legs) { return legs.every(function (l) { return l.expiration === legs[0].expiration; }); }
  function sameKindAndExpiration(legs) {
    return allSameExpiration(legs) && legs.every(function (l) { return l.optionType === legs[0].optionType; });
  }
  function catalogResult(family, key, fallback, note) {
    var templates = window.Builder && Builder.TEMPLATES || [];
    var meta = templates.find(function (t) { return key ? t.key === key : t.family === family; });
    if (!meta && (family || key)) {
      return { family: null, key: null, label: fallback || 'Custom structure', blocked: false,
        note: 'No current server-catalog identity is claimed. The exact legs still receive the same analysis.' };
    }
    return { family: family || null, key: key || family || null,
      label: meta ? meta.name : fallback || String(family || 'Custom structure').replaceAll('_', ' ').toLowerCase(),
      blocked: meta ? !!meta.risky : ['NAKED_CALL', 'NAKED_PUT', 'SHORT_STRADDLE', 'SHORT_STRANGLE'].includes(family),
      note: note || (meta && meta.blurb) || '' };
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
    var copy = JSON.parse(JSON.stringify(draft)), modeled = false;
    for (var i = 0; i < copy.legs.length; i++) {
      var leg = copy.legs[i];
      if (leg.price !== '') continue;
      if (leg.instrumentType === 'STOCK') {
        if (!market || !Number.isFinite(Number(market.spot))) return null;
        leg.price = String(market.spot); modeled = true; continue;
      }
      if (!market) return null;
      var chain = chainData[market.symbol + '|' + leg.expiration];
      var rows = chain && (leg.optionType === 'CALL' ? chain.calls : chain.puts) || [];
      var quote = rows.find(function (row) { return Number(row.strike) === Number(leg.strike); });
      if (!quote) return null;
      var bid = Number(quote.bid), ask = Number(quote.ask), mark = Number(quote.mid);
      if (!Number.isFinite(mark)) mark = Number.isFinite(bid) && Number.isFinite(ask) ? (bid + ask) / 2 : NaN;
      if (!Number.isFinite(mark) || mark < 0) return null;
      leg.price = String(mark); modeled = true;
    }
    return { draft: copy, modeled: modeled };
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

    function remember() {
      var currentFingerprint = draftFingerprint(draft);
      if (lastAnalysis && lastAnalysisFingerprint !== currentFingerprint) {
        lastAnalysis = null; lastAnalysisFingerprint = null;
        if (resultHost) resultHost.innerHTML = '';
      }
      if (resultHost && draft.selectedFingerprint && draft.selectedFingerprint !== currentFingerprint) {
        resultHost.innerHTML = '';
      }
      stores[key] = JSON.parse(JSON.stringify(draft));
      if (window.Workspace) Workspace.save();
      if (typeof options.onStateChange === 'function') options.onStateChange(draft);
    }

    function showInputError(error) {
      if (!resultHost) return;
      resultHost.innerHTML = '';
      resultHost.appendChild(UI.actionFeedback('danger', 'Terminal input needs attention', error.message || String(error)));
    }

    function syncTerminal() {
      if (draft.mode !== 'terminal' || !draft.terminalDirty) return;
      draft.legs = parseTerminal(draft.terminal, options.lockedSymbol && String(options.lockedSymbol).toUpperCase());
      draft.symbol = options.lockedSymbol || draft.legs[0].symbol;
      draft.terminalDirty = false;
      remember();
    }

    function setMode(mode) {
      if (mode === draft.mode) return;
      if (draft.mode === 'terminal') {
        try { syncTerminal(); } catch (error) { showInputError(error); return; }
      }
      draft.mode = mode;
      if (mode === 'terminal') { draft.terminal = terminalText(draft.legs); draft.terminalDirty = false; }
      remember(); paint();
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
        remember(); paint();
      }).catch(function (error) {
        if (token !== loadToken) return;
        market = { symbol: symbol, loading: false, error: error.message || String(error), expirations: [] };
        paint();
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

    function bind(input, target, keyName, rerender) {
      function save() { target[keyName] = input.type === 'number' ? (input.value === '' ? '' : Number(input.value)) : input.value; remember(); }
      input.addEventListener('input', save);
      input.addEventListener('change', function () { save(); if (rerender) paint(); else refreshVisual(); });
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
        paint();
      });
      var action = bind(el('select', {}, el('option', { value: 'BUY', selected: leg.action === 'BUY' ? 'selected' : null }, 'Buy'),
        el('option', { value: 'SELL', selected: leg.action === 'SELL' ? 'selected' : null }, 'Sell')), leg, 'action', true);
      var effect = bind(el('select', {}, el('option', { value: 'OPEN', selected: leg.positionEffect === 'OPEN' ? 'selected' : null }, 'Open'),
        el('option', { value: 'CLOSE', selected: leg.positionEffect === 'CLOSE' ? 'selected' : null }, 'Close')), leg, 'positionEffect', true);
      var symbol = bind(el('input', { type: 'text', maxlength: '20', value: options.lockedSymbol || leg.symbol,
        disabled: options.lockedSymbol ? 'disabled' : null, placeholder: 'MU' }), leg, 'symbol', false);
      var optionType = bind(el('select', {}, el('option', { value: 'CALL', selected: leg.optionType === 'CALL' ? 'selected' : null }, 'Call'),
        el('option', { value: 'PUT', selected: leg.optionType === 'PUT' ? 'selected' : null }, 'Put')), leg, 'optionType', true);
      var strike = bind(el('input', { type: 'number', min: '0.0001', step: '0.01', value: leg.strike, placeholder: '980' }), leg, 'strike', false);
      var expiration = bind(el('input', { type: 'date', value: leg.expiration }), leg, 'expiration', false);
      var quantity = bind(el('input', { type: 'number', min: '1', max: '10000000', step: '1', value: leg.quantity }), leg, 'quantity', false);
      var multiplier = bind(el('input', { type: 'number', min: '1', max: '10000', step: '1', value: leg.multiplier }), leg, 'multiplier', false);
      var price = bind(el('input', { type: 'number', min: '0', step: '0.0001', value: leg.price,
        placeholder: 'blank = price for analysis' }), leg, 'price', false);
      var section1256 = el('input', { type: 'checkbox', checked: leg.section1256 === true ? 'checked' : null });
      section1256.onchange = function () { leg.section1256 = section1256.checked ? true : null; remember(); };
      var automatic1256 = App.config && Array.isArray(App.config.broadBasedIndexOptionSymbols)
        ? App.config.broadBasedIndexOptionSymbols.join(', ') : 'known broad-based index roots and listed series';
      var section1256Field = options.allowRecord && leg.instrumentType === 'OPTION'
        ? el('label', { class: 'check-row position-1256-flag' }, section1256,
          el('span', {}, 'Other Section 1256 contract', el('small', {}, 'Automatic: ' + automatic1256
            + '. Check this only for another eligible contract confirmed by your broker.'))) : null;
      var taxDisclosure = section1256Field ? UI.expandable('Tax contract classification', function () {
        return section1256Field;
      }, { stateKey: 'position-tax-' + key + '-' + index }) : null;
      var grid = el('div', { class: 'position-leg-grid' }, field('Instrument', instrument), field('Action', action),
        field('Position', effect, 'Open adds exposure; Close reduces an existing position.'), field('Symbol', symbol),
        leg.instrumentType === 'OPTION' ? field('Type', optionType) : null,
        leg.instrumentType === 'OPTION' ? field('Strike $', strike) : null,
        leg.instrumentType === 'OPTION' ? field('Expiration', expiration) : null,
        field(leg.instrumentType === 'STOCK' ? 'Shares' : 'Contracts', quantity),
        leg.instrumentType === 'OPTION' ? field('Multiplier', multiplier, '100 standard; adjusted contracts may differ.') : null,
        field(options.allowRecord ? 'Exact fill $' : 'Fill or proposed price $', price,
          options.allowRecord ? 'Required by RECORD. ANALYZE may price a blank fill from available evidence.' : 'Optional; blank uses the available market/model evidence.'));
      return el('fieldset', { class: 'position-leg', 'data-leg-index': String(index) },
        el('legend', {}, 'Leg ' + (index + 1) + ' · ' + leg.action.toLowerCase() + ' ' + (leg.instrumentType === 'STOCK' ? 'shares' : String(leg.optionType || '').toLowerCase())),
        grid, taxDisclosure, el('button', { type: 'button', class: 'btn btn-secondary btn-sm position-leg-remove',
          disabled: draft.legs.length === 1 ? 'disabled' : null, 'aria-label': 'Remove leg ' + (index + 1),
          onclick: function () { draft.legs.splice(index, 1); remember(); paint(); } }, 'Remove'));
    }

    var visualHost = null, resultHost = null;
    function refreshVisual() {
      if (!visualHost || !visualHost.isConnected) return;
      visualHost.innerHTML = '';
      var identified = identify(draft.legs), payoffUnavailable = localPayoffUnavailableReason(draft);
      visualHost.appendChild(el('div', { class: 'position-identity' },
        el('span', { class: 'eyebrow' }, 'CATALOG MATCH'),
        el('h3', {}, identified.label),
        identified.note ? el('p', { class: 'muted small' }, identified.note) : null,
        identified.blocked ? UI.alertBox('caution', 'Shown for learning, blocked by default', [
          'The exact structure remains analyzable. Practice placement keeps its existing safety block; recording an actual broker fact is never refused for being risky.'
        ]) : null));
      var response = lastAnalysis && (lastAnalysis.preview || lastAnalysis);
      var points = response && response.payoff;
      var provisional = null;
      if ((!points || points.length < 2) && !payoffUnavailable) {
        provisional = provisionalDraft(draft, market, chainData);
        points = provisional ? localPayoff(provisional.draft, market && market.spot) : null;
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
          onChange: function (next) { draft.legs[index].strike = String(next); remember(); paint(); } });
      });
      var exactEnteredFills = draft.legs.length > 0 && draft.legs.every(function (leg) {
        return leg.price !== '' && Number.isFinite(Number(leg.price));
      });
      visualHost.appendChild(el('div', { class: 'position-editor-chart-head' },
        el('b', {}, lastAnalysis ? 'Analyzed payoff' : 'Payoff shape while editing'),
        el('span', { class: 'muted small' }, lastAnalysis ? 'Exact analyzed package'
          : provisional && provisional.modeled ? 'Blank fills use current listed midpoints for this preview'
            : exactEnteredFills ? 'Entered leg prices drive this payoff preview'
              : 'Enter fills or load matching listed contracts')));
      if (points && points.length > 1) {
        visualHost.appendChild(UI.payoffChart(points, { spot: market && market.spot || null,
          handles: handles.length ? handles : null }));
      } else if (payoffUnavailable) {
        visualHost.appendChild(UI.emptyState('A single expiry payoff would be misleading', payoffUnavailable));
      } else {
        visualHost.appendChild(UI.emptyState('Payoff waiting for a defensible price',
          'Enter a proposed fill, or choose a listed contract so the preview can use its current midpoint. Blank never means zero.'));
      }
    }

    function renderChainPicker(parent) {
      var box = el('section', { class: 'position-chain-picker' },
        el('div', { class: 'plan-section-head' }, el('div', {}, el('h3', {}, 'Add from the chain'),
          el('p', { class: 'muted small' }, 'Choose a contract listed in this market, then edit every field below.'))));
      parent.appendChild(box);
      if (!market || market.loading) { box.appendChild(el('p', { class: 'muted' }, 'Loading listed contracts...')); return; }
      if (market.error || !market.expirations.length) {
        box.appendChild(el('p', { class: 'muted' }, market.error || 'No listed option chain is available; manual entry stays available below.')); return;
      }
      var exp = el('select', {}, market.expirations.map(function (value) {
        return el('option', { value: value, selected: value === draft.chainExpiration ? 'selected' : null }, value);
      }));
      exp.onchange = function () { draft.chainExpiration = exp.value; remember(); paint(); };
      var type = el('select', {}, ['PUT', 'CALL'].map(function (value) {
        return el('option', { value: value, selected: value === draft.chainType ? 'selected' : null }, value === 'PUT' ? 'Puts' : 'Calls');
      }));
      type.onchange = function () { draft.chainType = type.value; remember(); paint(); };
      var action = el('select', {}, ['BUY', 'SELL'].map(function (value) {
        return el('option', { value: value, selected: value === draft.chainAction ? 'selected' : null }, value === 'BUY' ? 'Buy' : 'Sell');
      }));
      action.onchange = function () { draft.chainAction = action.value; remember(); };
      box.appendChild(el('div', { class: 'position-chain-controls' }, field('Expiration', exp), field('Contract', type), field('Action', action)));
      var rail = el('div', { class: 'position-chain-rail', 'aria-live': 'polite' }, el('span', { class: 'muted' }, 'Loading strikes...'));
      box.appendChild(rail);
      loadChain(draft.chainExpiration).then(function (chain) {
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
              delete chainCache[cacheKey]; delete chainErrors[cacheKey]; paint();
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
            if (replace) draft.legs[0] = selectedLeg; else draft.legs.push(selectedLeg);
            remember(); paint();
          } }, el('b', {}, '$' + Number(quote.strike).toFixed(2)),
          el('span', { class: 'muted small' }, (quote.bid == null ? '-' : Number(quote.bid).toFixed(2))
            + ' / ' + (quote.ask == null ? '-' : Number(quote.ask).toFixed(2)))));
        });
      });
    }

    function renderAnalysis(out) {
      if (!resultHost || !resultHost.isConnected) return;
      resultHost.innerHTML = '';
      var response = out && (out.preview || out);
      var selected = out && out.strategy && out.strategy.result && out.strategy.result.candidate;
      var economics = out && out.evaluation && out.evaluation.assessment
          ? out.evaluation.assessment.economics
          : selected && selected.evaluation && selected.evaluation.assessment
            ? selected.evaluation.assessment.economics : null;
      if (!response) return;
      resultHost.appendChild(UI.actionFeedback(response.ok === false ? 'caution' : 'ok',
        response.ok === false ? 'Analyzed with constraints' : 'Analysis complete',
        response.ok === false ? (response.blockReasons || []).join(' ') : 'The exact edited package now drives these facts.'));
      if (isPast(draft.occurredAt)) {
        resultHost.appendChild(UI.actionFeedback('caution', 'Current fresh-eyes analysis',
          'The entry date is preserved, but this result uses the market evidence available now. Retrospective replay will use the dated historical path and label each modeled or observed leg-day separately.'));
      }
      var analysis = out && out.evaluation
        ? out.evaluation : selected && selected.evaluation ? selected.evaluation : null;
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
      if (evidence) resultHost.appendChild(el('p', { class: 'muted small position-coverage' },
        'Coverage receipt: ' + [evidence.provenance, evidence.age, evidence.source].filter(Boolean).join(' · ') + '.'));
      if (economics) resultHost.appendChild(el('div', { class: 'chip-row' }, chip('Economic verdict', economics.verdict),
        economics.marketEvAfterCostsCents == null ? null : chip('Market-implied EV after costs', fmtMoney(economics.marketEvAfterCostsCents, { plus: true }))));
      if (analysis && window.ViewPlan && window.ViewPlan.decisionMetricsReceipt) {
        resultHost.appendChild(window.ViewPlan.decisionMetricsReceipt(analysis, Learn.currentLevel() === 'beginner'));
      }
      if (out && out.marketLane) resultHost.appendChild(el('p', { class: 'muted small position-account-basis' },
        (out.accountName ? out.accountName + ' · ' : '') + out.marketLane + ' market · ' + (out.note || 'Account-specific read-only analysis.')));
      var fillBases = Array.from(new Set((response.legs || []).map(function (leg) { return leg.fillBasis; }).filter(Boolean)));
      if (fillBases.length) resultHost.appendChild(el('p', { class: 'muted small position-fill-basis' },
        'Entry-price receipt: ' + fillBases.map(function (basis) { return String(basis).replaceAll('_', ' ').toLowerCase(); }).join(' + ') + '.'));
      if (options.planId && response.ok !== false) resultHost.appendChild(el('button', { type: 'button', class: 'btn',
        onclick: function () { App.navigate('#/plan/' + options.planId + '/outcomes'); } }, 'Continue to Outcomes'));
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
        chip('Economic verdict', savedEconomics.verdict),
        savedEconomics.marketEvAfterCostsCents == null ? null
          : chip('Market-implied EV after costs', fmtMoney(savedEconomics.marketEvAfterCostsCents, { plus: true }))));
      if (savedAnalysis && window.ViewPlan && window.ViewPlan.decisionMetricsReceipt) {
        resultHost.appendChild(window.ViewPlan.decisionMetricsReceipt(
          savedAnalysis, Learn.currentLevel() === 'beginner'));
      }
      resultHost.appendChild(el('button', { type: 'button', class: 'btn',
        onclick: function () { App.navigate('#/plan/' + options.planId + '/outcomes'); } }, 'Continue to Outcomes'));
    }

    async function runAnalyze() {
      if (commandBusy) return null;
      try { syncTerminal(); } catch (error) { showInputError(error); throw error; }
      commandBusy = true; paint(false);
      try {
        var payload = normalizedForAnalysis(draft);
        var out = await options.onAnalyze(payload, draft);
        lastAnalysis = out; lastAnalysisFingerprint = draftFingerprint(draft);
        var response = out && (out.preview || out);
        var selected = out && out.strategy && out.strategy.result && out.strategy.result.candidate;
        if (response && response.ok !== false && selected && selected.id && selected.selected === true) {
          draft.selectedCandidateId = selected.id;
          draft.selectedFingerprint = lastAnalysisFingerprint;
          remember();
        }
        renderAnalysis(out);
        UI.toast('Trade analyzed', 'ok');
        return out;
      } catch (error) {
        if (resultHost) { resultHost.innerHTML = ''; resultHost.appendChild(UI.actionFeedback('danger', 'Could not analyze', error.message || String(error))); }
        return Promise.reject(error);
      } finally { commandBusy = false; paint(false); }
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
      if (resultHost) {
        resultHost.innerHTML = '';
        resultHost.appendChild(UI.actionFeedback('ok', 'Recorded in ' + (options.accountName || 'tracked account'),
          'The factual activity is now part of the append-only tracked ledger.'));
        if (typeof options.onRecorded === 'function') {
          resultHost.appendChild(el('button', { type: 'button', class: 'btn btn-secondary', onclick: async function () {
            var button = this;
            button.disabled = true; button.setAttribute('aria-busy', 'true');
            try { await options.onRecorded(out, draft); }
            catch (error) {
              button.disabled = false; button.removeAttribute('aria-busy');
              resultHost.appendChild(UI.actionFeedback('danger', 'Could not refresh the account view', error.message || String(error)));
            }
          } }, options.recordedActionLabel || 'View account activity'));
        }
      }
      UI.toast('Broker activity recorded', 'ok');
      return out;
    }

    function showSimilarity(similar) {
      resultHost.innerHTML = '';
      var first = similar[0];
      resultHost.appendChild(UI.actionFeedback('caution', 'A similar position already exists',
        'Similarity is advisory, never idempotency. A second position may be an intentional scale-in.'));
      resultHost.appendChild(el('p', {}, first.label || first.symbol || 'Existing position'));
      resultHost.appendChild(el('div', { class: 'btn-row' },
        el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () {
          if (options.onLinkSimilar) options.onLinkSimilar(first); else App.navigate('#/portfolio/book/overview');
        } }, 'Open existing'),
        el('button', { type: 'button', class: 'btn', onclick: async function () {
          draft.similarityDecision = 'ADD'; remember();
          try { commandBusy = true; paint(false); await recordNow(); }
          catch (error) { resultHost.appendChild(UI.actionFeedback('danger', 'Could not record', error.message || String(error))); }
          finally { commandBusy = false; paint(false); }
        } }, 'Add as a new lot'),
        el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () {
          draft.similarityDecision = 'CANCEL'; remember(); resultHost.innerHTML = '';
        } }, 'Cancel')));
    }

    async function runRecord() {
      if (commandBusy) return null;
      try { syncTerminal(); } catch (error) { showInputError(error); throw error; }
      commandBusy = true; paint(false);
      try { return await recordNow(); }
      catch (error) {
        if (resultHost) { resultHost.innerHTML = ''; resultHost.appendChild(UI.actionFeedback('danger', 'Could not record', error.message || String(error))); }
        return Promise.reject(error);
      } finally { commandBusy = false; paint(false); }
    }

    async function runBoth() {
      if (commandBusy) return;
      try { syncTerminal(); } catch (error) { showInputError(error); throw error; }
      // Validate both contracts before either command mutates anything.
      normalizedForAnalysis(draft); recordPayload(draft);
      commandBusy = true; paint(false);
      var analyzed = null, recorded = null, analysisError = null;
      try { analyzed = await options.onAnalyze(normalizedForAnalysis(draft), draft); lastAnalysis = analyzed; }
      catch (error) { analysisError = error; }
      try { recorded = await recordNow(); }
      finally { commandBusy = false; paint(false); }
      if (!recorded && resultHost && resultHost.querySelector('.inline-action-feedback')) return { analysis: analyzed, record: null };
      if (recorded && analysisError && resultHost) resultHost.appendChild(UI.actionFeedback('caution', 'Recorded; analysis unavailable', analysisError.message || String(analysisError)));
      else if (analyzed && recorded && resultHost) {
        var response = analyzed.preview || analyzed;
        resultHost.appendChild(UI.actionFeedback('ok', 'Analysis also completed', response.ok === false
          ? (response.blockReasons || []).join(' ') : 'The same exact package was analyzed before the factual activity was recorded.'));
      } else if (analyzed) renderAnalysis(analyzed);
      return { analysis: analyzed, record: recorded };
    }

    function paint(rebuild) {
      if (rebuild === false) {
        root.classList.toggle('is-busy', commandBusy);
        if (commandBusy) root.setAttribute('aria-busy', 'true'); else root.removeAttribute('aria-busy');
        Array.from(root.querySelectorAll('input,select,textarea,button')).forEach(function (control) {
          if (commandBusy && !control.disabled) {
            control.dataset.positionBusyDisabled = 'true'; control.disabled = true;
          } else if (!commandBusy && control.dataset.positionBusyDisabled === 'true') {
            delete control.dataset.positionBusyDisabled; control.disabled = false;
          }
          if (control.dataset.positionCommand) {
            var recordCommand = control.dataset.positionCommand === 'record' || control.dataset.positionCommand === 'both';
            control.disabled = commandBusy || recordCommand && options.recordDisabled;
            if (commandBusy) control.setAttribute('aria-busy', 'true'); else control.removeAttribute('aria-busy');
          }
        });
        refreshVisual(); return;
      }
      root.innerHTML = '';
      root.className = 'position-editor';
      root.onkeydown = function (event) {
        if (event.altKey && event.key.toLowerCase() === 'v') { event.preventDefault(); setMode(draft.mode === 'visual' ? 'terminal' : 'visual'); }
      };
      var mode = el('div', { class: 'segmented position-editor-mode', role: 'group', 'aria-label': 'Trade entry mode' },
        el('button', { type: 'button', class: draft.mode === 'visual' ? 'active' : '', 'aria-pressed': draft.mode === 'visual' ? 'true' : 'false', onclick: function () { setMode('visual'); } }, 'Visual'),
        el('button', { type: 'button', class: draft.mode === 'terminal' ? 'active' : '', 'aria-pressed': draft.mode === 'terminal' ? 'true' : 'false', onclick: function () { setMode('terminal'); } }, 'Terminal'));
      root.appendChild(el('div', { class: 'plan-tool-intro position-editor-head' },
        el('div', {}, el('span', { class: 'eyebrow' }, 'YOUR TRADE'),
          el('h3', {}, options.title || 'Build, analyze, or record the exact package'),
          el('p', { class: 'muted' }, options.description || 'The edited legs are shared. Analyze may fill evidence gaps; Record accepts factual broker fills only.')),
        el('div', {}, mode, el('span', { class: 'muted small position-mode-key' }, 'Alt+V switches views'))));
      if (rejectedDraftReason) {
        root.appendChild(UI.actionFeedback('caution', 'Incomplete local draft was rejected',
          rejectedDraftReason + ' A new current-format draft is shown instead.'));
        rejectedDraftReason = null;
      }

      var editor = el('div', { class: 'position-editor-inputs' });
      if (draft.mode === 'terminal') {
        var terminal = el('textarea', { class: 'position-terminal', rows: '8', spellcheck: 'false', 'aria-label': 'Terminal position lines',
          value: draft.terminal || terminalText(draft.legs),
          placeholder: '-1 MU 13Jul26 980P @20.00\n+100 MU @979.30' });
        terminal.value = draft.terminal || terminalText(draft.legs);
        terminal.oninput = function () { draft.terminal = terminal.value; draft.terminalDirty = true; remember(); };
        editor.append(el('p', { class: 'muted small' }, 'Signed quantity sets buy (+) or sell (-). Add x10 for an adjusted contract, CLOSE for a closing leg, and 1256 for a broker-confirmed eligible option. Fill after @ is optional for Analyze.'),
          terminal, el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () {
            try {
              draft.terminal = terminal.value; draft.terminalDirty = true; syncTerminal(); paint();
            } catch (error) { showInputError(error); }
          } }, 'Apply lines')));
      } else {
        if (options.chain !== false) renderChainPicker(editor);
        var legList = el('div', { class: 'position-leg-list' });
        draft.legs.forEach(function (leg, index) { legList.appendChild(legRow(leg, index)); });
        editor.append(legList, el('button', { type: 'button', class: 'btn btn-secondary btn-sm', onclick: function () {
          draft.legs.push(blankLeg(options.lockedSymbol || draft.symbol)); remember(); paint();
        } }, '+ Add leg'));
      }

      var packageNet = bind(el('input', { type: 'number', step: '0.01', value: draft.packageNet,
        placeholder: '+ credit / - debit' }), draft, 'packageNet', false);
      var feeMode = el('select', {}, el('option', { value: 'DEFAULT', selected: draft.feeMode === 'DEFAULT' ? 'selected' : null }, 'Use StrikeBench estimate for Analyze'),
        el('option', { value: 'EXACT', selected: draft.feeMode === 'EXACT' ? 'selected' : null }, 'Enter exact total fees'));
      feeMode.onchange = function () { draft.feeMode = feeMode.value; remember(); paint(); };
      var fees = bind(el('input', { type: 'number', min: '0', step: '0.01', value: draft.fees,
        placeholder: draft.feeMode === 'EXACT' ? '0.00' : 'platform default' }), draft, 'fees', false);
      var meta = el('div', { class: 'form-grid position-package-meta' },
        field('Package net $', packageNet, 'Optional signed total before fees: positive credit, negative debit.'),
        field('Fee treatment', feeMode), draft.feeMode === 'EXACT' || options.allowRecord ? field('Total fees $', fees,
          options.allowRecord ? 'RECORD requires this factual total. Enter 0.00 explicitly when the broker charged no fee.' : null) : null);
      if (options.onAnalyze) {
        var fillNature = bind(el('select', {},
          el('option', { value: 'PROPOSED', selected: draft.fillNature === 'PROPOSED' ? 'selected' : null }, 'Proposed / hypothetical'),
          el('option', { value: 'EXECUTED', selected: draft.fillNature === 'EXECUTED' ? 'selected' : null }, 'Executed fill')), draft, 'fillNature', true);
        var analysisAt = bind(el('input', { type: 'datetime-local', step: '1', value: draft.occurredAt }), draft, 'occurredAt', false);
        meta.append(field('Price meaning', fillNature,
          'A proposed price is tested as an order. An executed price is treated as a fact, while blank legs still use labeled evidence.'),
          field(options.allowRecord ? 'Executed / analyzed at' : 'Entry / analysis date', analysisAt,
            'Past dates stay attached to the draft. Analyze is fresh-eyes until a retrospective replay is run.'));
      }
      if (options.allowRecord) {
        var source = bind(el('select', {}, el('option', { value: 'MANUAL', selected: draft.source === 'MANUAL' ? 'selected' : null }, 'Entered manually'),
          el('option', { value: 'BROKER', selected: draft.source === 'BROKER' ? 'selected' : null }, 'Copied from broker')), draft, 'source', true);
        var ref = bind(el('input', { type: 'text', maxlength: '160', value: draft.externalRef, placeholder: 'Order or statement reference' }), draft, 'externalRef', false);
        meta.append(field('Record source', source),
          field('Stable broker reference', ref, 'Required only for broker-sourced activity; it is the idempotency key.'));
      }
      var notes = bind(el('textarea', { rows: '2', maxlength: '1000', value: draft.notes, placeholder: 'Optional context' }), draft, 'notes', false);
      notes.value = draft.notes;
      editor.append(meta, field('Notes', notes));

      visualHost = el('aside', { class: 'position-editor-visual' });
      root.appendChild(UI.positionWorkbench(editor, visualHost));
      resultHost = el('div', { class: 'position-editor-result', 'aria-live': 'polite' });
      var commands = el('div', { class: 'btn-row position-editor-commands' });
      var analyzeCommand = options.onAnalyze ? el('button', { type: 'button',
        class: options.recordPrimary ? 'btn btn-secondary' : 'btn', 'data-position-command': 'analyze',
        disabled: commandBusy ? 'disabled' : null, onclick: function () { runAnalyze().catch(function () {}); }
      }, options.analyzeLabel || 'Analyze this trade') : null;
      var recordCommand = null, bothCommand = null;
      if (options.allowRecord && options.onRecord) {
        recordCommand = el('button', { type: 'button', class: options.recordPrimary ? 'btn' : 'btn btn-secondary', 'data-position-command': 'record',
          disabled: commandBusy || options.recordDisabled ? 'disabled' : null,
          'aria-describedby': options.recordDisabled ? 'position-record-disabled-' + key.replace(/[^a-zA-Z0-9_-]/g, '-') : null,
          onclick: function () { runRecord().catch(function () {}); } }, 'Record factual activity');
        if (options.onAnalyze) bothCommand = el('button', { type: 'button', class: 'btn btn-secondary', 'data-position-command': 'both',
          disabled: commandBusy || options.recordDisabled ? 'disabled' : null, onclick: function () { runBoth().catch(function (error) {
            resultHost.innerHTML = ''; resultHost.appendChild(UI.actionFeedback('danger', 'Could not complete both commands', error.message || String(error)));
          }); } }, 'Analyze and record');
      }
      [options.recordPrimary && recordCommand, analyzeCommand,
        !options.recordPrimary && recordCommand, bothCommand].filter(Boolean).forEach(function (button) {
        commands.appendChild(button);
      });
      if (options.recordDisabled) commands.appendChild(el('span', {
        id: 'position-record-disabled-' + key.replace(/[^a-zA-Z0-9_-]/g, '-'), class: 'muted small'
      }, options.recordDisabledReason || 'Recording is unavailable here.'));
      root.append(commands, resultHost);
      refreshVisual();
      if (lastAnalysis && lastAnalysisFingerprint === draftFingerprint(draft)) renderAnalysis(lastAnalysis);
      else renderDurableSelection();
      ensureMarket();
    }

    paint();
    return { draft: function () { return draft; }, analyze: runAnalyze, record: runRecord,
      readAnalysis: function () { syncTerminal(); return normalizedForAnalysis(draft); },
      readRecord: function () { syncTerminal(); return recordPayload(draft); } };
  }

  window.PositionEditor = { render: render, cleanDraft: cleanDraft, parseTerminal: parseTerminal,
    terminalText: terminalText, identify: identify, localPayoffUnavailableReason: localPayoffUnavailableReason,
    analysisPayload: normalizedForAnalysis, recordPayload: recordPayload, draftFromCandidate: draftFromCandidate };
})();
