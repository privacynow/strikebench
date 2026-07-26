'use strict';

/**
 * The Book: `TradeView` rosters at the §16.3 position counts (0, 1, 4 and 12) and the portfolio
 * documents the Desk reads beside them.
 *
 * The aggregates are COMPUTED FROM THE ROSTER, never stated independently. A summary whose totals
 * disagree with the positions they claim to summarize is the same defect the audit is chasing on
 * screen (§5.6, "stop deriving Book share and rank in JavaScript"), and a fixture that contains it
 * would let a Home total test pass against numbers no roster could produce.
 */

const wire = require('./wire');
const math = require('./package-math');
const { legs: legShape } = require('./legs');
const golden = require('./golden');

/** Per-contract commission, both sides, so fees scale with the package like the engine's do. */
const FEE_PER_CONTRACT_CENTS = 50;

function feesFor(legList, quantity) {
  return legList.filter(leg => !math.isStock(leg)).length * quantity * FEE_PER_CONTRACT_CENTS;
}

/**
 * One held line. Everything economic is derived from the legs through the shared package math;
 * only the MARK — what the position is worth right now — is stated, because a mark is an
 * observation the engine makes against a live book, not something a payoff implies.
 */
function tradeView(overrides) {
  const o = Object.assign({
    id: 'trade_fixture_0',
    symbol: wire.GOLDEN_SYMBOL,
    strategy: 'PUT_CREDIT_SPREAD',
    status: 'ACTIVE',
    qty: 1,
    legs: null,
    thesis: 'neutral',
    horizon: 'month',
    riskMode: 'balanced',
    entryUnderlyingCents: 25000,
    popEntry: 0.7312,
    unrealizedPnlCents: 0,
    intent: 'INCOME',
    sharesLocked: 0,
    createdAt: '2026-07-20T15:00:00Z',
    updatedAt: wire.OBSERVED_AT_ISO,
    closedAt: null,
    closeReason: null,
    realizedPnlCents: null,
    isLive: false,
    dataProvenance: 'OBSERVED',
    dataAge: 'REALTIME',
    dataSource: 'FIXTURE_EXECUTABLE_BOOK',
    withReceipts: true,
    payoffSpanPct: 0.20
  }, overrides || {});

  const legList = o.legs || legShape(2);
  const expirations = Array.from(new Set(legList.map(leg => leg.expiration).filter(Boolean)));
  const mixedExpiry = expirations.length > 1;
  const entryNet = math.entryCashCents(legList, o.qty);
  // A mixed-expiration package has no terminal price, so its extremes are not derivable from a
  // terminal payoff. The engine states them from the bounded envelope; the fixture states them
  // explicitly rather than pretending a curve exists.
  const extremes = mixedExpiry
    ? { maxProfitCents: o.maxProfitCents == null ? null : o.maxProfitCents,
      maxLossCents: o.maxLossCents == null ? Math.abs(entryNet) + 100000 : o.maxLossCents }
    : math.extremes(legList, o.qty);
  const fees = feesFor(legList, o.qty);

  const trade = {
    id: o.id,
    symbol: o.symbol,
    strategy: o.strategy,
    status: o.status,
    qty: o.qty,
    legs: legList,
    thesis: o.thesis,
    horizon: o.horizon,
    riskMode: o.riskMode,
    entryUnderlyingCents: o.entryUnderlyingCents,
    entryNetPremiumCents: entryNet,
    maxLossCents: extremes.maxLossCents,
    maxProfitCents: extremes.maxProfitCents,
    breakevens: mixedExpiry ? [] : math.breakevens(legList, o.qty),
    popEntry: o.popEntry,
    feesOpenCents: fees,
    feesCloseCents: fees,
    realizedPnlCents: o.realizedPnlCents,
    decisionPnlCents: o.realizedPnlCents,
    closeReason: o.closeReason,
    entrySnapshot: { source: o.dataSource, freshness: 'REALTIME', asOfEpochMs: wire.OBSERVED_AT_MS },
    isLive: o.isLive,
    createdAt: o.createdAt,
    closedAt: o.closedAt,
    updatedAt: o.updatedAt,
    intent: o.intent,
    sharesLocked: o.sharesLocked,
    proposedNetCents: entryNet,
    dataProvenance: o.dataProvenance,
    dataAge: o.dataAge,
    dataSource: o.dataSource,
    unrealizedPnlCents: o.unrealizedPnlCents,
    decisionUnrealizedPnlCents: o.unrealizedPnlCents,
    // The held-line display receipts. A mixed-expiry line carries an explicitly UNAVAILABLE
    // terminal payoff and no story checkpoints — the state that must render as "unavailable" with
    // a reason rather than falling back to a browser-drawn curve (§3.2).
    terminalPayoff: !o.withReceipts ? null
      : mixedExpiry
        ? golden.goldenTerminalPayoff({ available: false })
        : heldTerminalPayoff(legList, o.qty, o.entryUnderlyingCents, o.payoffSpanPct),
    greeks: o.withReceipts ? golden.goldenGreeks() : null,
    jumpTail: !o.withReceipts ? null
      : mixedExpiry
        ? golden.goldenJumpTail({ available: false })
        : golden.goldenJumpTail(),
    scenarios: wire.nonEmpty(!o.withReceipts || mixedExpiry ? []
      : heldScenarios(legList, o.qty, o.entryUnderlyingCents))
  };
  return wire.nonNull(trade);
}

/** The held line's terminal payoff, anchored at its OWN entry spot rather than the golden one. */
function heldTerminalPayoff(legList, quantity, anchorCents, spanPct) {
  const low = Math.round(anchorCents * (1 - spanPct)) / 100;
  const high = Math.round(anchorCents * (1 + spanPct)) / 100;
  const knots = math.knotPrices(legList)
    .filter(price => price > low && price < high)
    .concat(math.breakevens(legList, quantity).map(Number).filter(p => p > low && p < high));
  const prices = Array.from(new Set([low].concat(knots).concat([high]))).sort((a, b) => a - b);
  return {
    schemaVersion: 'risk-terminal-payoff-1',
    modelVersion: 'payoff-curve-1',
    available: true,
    anchorSpotCents: anchorCents,
    expiration: legList.find(leg => leg.expiration).expiration,
    basis: 'Terminal value at expiration, priced from the recorded package entry.',
    entryBasis: 'AFTER_FEE_NET',
    feesIncluded: false,
    points: prices.map(price => ({
      price: price, profitCents: math.terminalPnlCents(legList, quantity, price)
    })),
    unavailableReason: null
  };
}

/** The eight named story checkpoints for a held line, priced through the same curve. */
function heldScenarios(legList, quantity, anchorCents) {
  return golden.STORY_MOVES.map(movePct => ({
    underlyingMovePct: movePct,
    pnlCents: math.terminalPnlCents(legList, quantity,
      Math.round(anchorCents * (1 + movePct)) / 100),
    prob: null   // a held roster row carries no ATM IV, so it states no probability
  }));
}

/** The golden package as a HELD line, so Position asserts the same facts New Idea does. */
function goldenHeldTrade(overrides) {
  return tradeView(Object.assign({
    id: 'trade_golden_gldn',
    symbol: golden.SYMBOL,
    qty: golden.QUANTITY,
    legs: golden.goldenLegs(),
    entryUnderlyingCents: golden.FACTS.anchorSpotCents,
    popEntry: golden.FACTS.pop,
    unrealizedPnlCents: 12000
  }, overrides || {}));
}

/**
 * The roster at a given size. Position 0 is always the golden package, so every count includes the
 * one line whose numbers are known independently.
 *
 * The shapes deliberately vary across the §16.3 leg-count axis: a one-leg cash-secured put, the
 * two-leg golden vertical, a four-leg condor, and a six-leg package spanning two expirations.
 *
 * `reverse: true` returns the same rows in the opposite order. Roster ORDER is a product decision
 * the server does not make for the Desk, so a test that asserts on-screen ordering needs to see
 * the same roster arrive both ways.
 */
function positions(count, options) {
  const settings = Object.assign({}, options || {});
  wire.oneOf('positions(count)', count, [0, 1, 4, 12]);
  const rows = [];
  const marks = [12000, -4500, 0, 23100, -18800, 6400, -900, 31500,
    -2200, 14700, -55600, 8300];
  for (let index = 0; index < count; index += 1) {
    if (index === 0) { rows.push(goldenHeldTrade({ unrealizedPnlCents: marks[0] })); continue; }
    const shape = [2, 1, 4, 6][index % 4];
    const spanning = shape === 6 ? 'multiple' : 'same';
    rows.push(tradeView({
      id: `trade_fixture_${index}`,
      symbol: wire.ROSTER_SYMBOLS[index % wire.ROSTER_SYMBOLS.length],
      strategy: { 1: 'CASH_SECURED_PUT', 2: 'PUT_CREDIT_SPREAD',
        4: 'IRON_CONDOR', 6: 'DOUBLE_DIAGONAL' }[shape],
      qty: 1 + (index % 3),
      legs: legShape(shape, { expirations: spanning }),
      unrealizedPnlCents: marks[index % marks.length],
      intent: shape === 1 ? 'ACQUIRE' : 'INCOME',
      sharesLocked: 0,
      createdAt: `2026-07-${String(6 + index).padStart(2, '0')}T15:00:00Z`
    }));
  }
  return settings.reverse ? rows.reverse() : rows;
}

/** `ApiResponses.TradePage` — the envelope `/api/trades` returns. */
function tradePage(trades, overrides) {
  const rows = trades || [];
  return Object.assign({ trades: rows, total: rows.length, page: 0, size: 100 }, overrides || {});
}

/** `PositionsService.PositionView` rows — the share book beside the option roster. */
function sharePositions(count) {
  const rows = [];
  for (let index = 0; index < (count || 0); index += 1) {
    const shares = 100 * (index + 1);
    const avgCostCents = 24000 + index * 500;
    const lastCents = 25000 + index * 400;
    rows.push({
      symbol: wire.ROSTER_SYMBOLS[index % wire.ROSTER_SYMBOLS.length],
      shares: shares,
      freeShares: shares,
      lockedShares: 0,
      avgCostCents: avgCostCents,
      lastCents: lastCents,
      marketValueCents: shares * lastCents,
      unrealizedCents: shares * (lastCents - avgCostCents),
      gainPct: (lastCents - avgCostCents) / avgCostCents * 100
    });
  }
  return rows;
}

/** `ApiResponses.PortfolioSummary`, aggregated from the roster it summarizes. */
function portfolioSummary(trades, shares) {
  const rows = trades || [];
  const shareRows = shares || [];
  const startingCashCents = 10000000;
  const reservedCents = rows.reduce((total, trade) => total + trade.maxLossCents, 0);
  const openTradesUnrealizedCents = rows.reduce(
    (total, trade) => total + (trade.unrealizedPnlCents || 0), 0);
  const openTradesValueCents = rows.reduce(
    (total, trade) => total + trade.entryNetPremiumCents + (trade.unrealizedPnlCents || 0), 0);
  const sharesValueCents = shareRows.reduce((total, row) => total + row.marketValueCents, 0);
  const cashCents = startingCashCents
    + rows.reduce((total, trade) => total + trade.entryNetPremiumCents - trade.feesOpenCents, 0)
    - shareRows.reduce((total, row) => total + row.shares * row.avgCostCents, 0);
  const totalValueCents = cashCents + sharesValueCents + openTradesUnrealizedCents;
  return {
    cashCents: cashCents,
    reservedCents: reservedCents,
    buyingPowerCents: cashCents - reservedCents,
    startingCashCents: startingCashCents,
    sharesValueCents: sharesValueCents,
    sharesPositions: shareRows.length,
    openTradesCount: rows.length,
    openTradesValueCents: openTradesValueCents,
    openTradesUnrealizedCents: openTradesUnrealizedCents,
    totalValueCents: totalValueCents,
    totalPnlCents: totalValueCents - startingCashCents,
    freshness: 'REALTIME',
    note: rows.length
      ? 'Fixture Practice account receipt; reserve remains inside cash, before close fees.'
      : 'Authoritative empty Practice account receipt.'
  };
}

/**
 * Portfolio heat. This payload is assembled as a `Map<String, Object>` in
 * `TradeService.portfolioHeat`, not as a record, so the method body is its contract — every key it
 * writes is written here, in the same order.
 */
function portfolioHeat(trades, summary) {
  const rows = trades || [];
  const bySymbol = {};
  rows.forEach(trade => {
    bySymbol[trade.symbol] = (bySymbol[trade.symbol] || 0) + trade.maxLossCents;
  });
  const totalMaxLossCents = rows.reduce((total, trade) => total + trade.maxLossCents, 0);
  const worstSymbol = Object.values(bySymbol).reduce((worst, value) =>
    Math.max(worst, value), 0);
  const shortPutObligationCents = rows.reduce((total, trade) => total + trade.legs
    .filter(leg => leg.type === 'PUT' && leg.action === 'SELL')
    .reduce((legTotal, leg) => legTotal
      + Math.round(Number(leg.strike) * 100) * leg.ratio * leg.multiplier * trade.qty, 0), 0);
  return {
    activeTrades: rows.length,
    totalMaxLossCents: totalMaxLossCents,
    reservedCents: summary ? summary.reservedCents : totalMaxLossCents,
    shortVolTrades: rows.filter(trade => trade.entryNetPremiumCents > 0).length,
    bySymbolMaxLossCents: bySymbol,
    concentrationPct: totalMaxLossCents > 0
      ? Math.round(100 * worstSymbol / totalMaxLossCents) : 0,
    /* Each trade's share of defined book risk and its rank — book facts, because both depend on
       every other open trade (audit §15.5). Ranked by defined loss, id breaking ties, exactly as
       TradeService.portfolioHeat does, so a surface reading this fixture reads the real ordering. */
    positions: rows.slice()
      .sort((a, b) => (b.maxLossCents - a.maxLossCents) || a.id.localeCompare(b.id))
      .map((trade, index) => ({
        tradeId: trade.id,
        symbol: trade.symbol,
        maxLossCents: trade.maxLossCents,
        riskSharePct: totalMaxLossCents > 0 ? 100 * trade.maxLossCents / totalMaxLossCents : null,
        riskRank: index + 1
      })),
    rankedPositions: rows.length,
    earlyAssignmentLiquidityCents: shortPutObligationCents,
    physicalAssignmentCashCents: shortPutObligationCents,
    assignmentReserveReleasedCents: totalMaxLossCents,
    postPhysicalAssignmentBuyingPowerCents: summary
      ? summary.buyingPowerCents - shortPutObligationCents : 0
  };
}

/**
 * `TradeService.BookGreeks`.
 *
 * The per-position rows carry the ONE canonical greeks view (ScenarioCanvasValuator.Greeks:
 * deltaShares, gammaSharesPerDollar, thetaCentsPerDay, vegaCentsPerPoint). The old
 * TradeService.PositionGreeks dialect was deleted from the wire; the divergence it documented is
 * live; §5.3 of the audit is the work to end it. The fixture mirrors today's wire, so this file
 * must be revisited in the same change that canonicalizes the contract — a fixture that jumped
 * ahead to the intended shape would make the migration look already-done.
 */
function portfolioGreeks(trades) {
  const rows = trades || [];
  const canonical = golden.goldenGreeks();
  const perPosition = rows.map(trade => ({
    id: trade.id,
    symbol: trade.symbol,
    strategy: trade.strategy,
    qty: trade.qty,
    greeks: {
      deltaShares: canonical.deltaShares,
      gammaSharesPerDollar: canonical.gammaSharesPerDollar,
      thetaCentsPerDay: canonical.thetaCentsPerDay,
      vegaCentsPerPoint: canonical.vegaCentsPerPoint
    },
    netDollarDeltaCents: Math.round(canonical.deltaShares * trade.entryUnderlyingCents),
    unrealizedCents: trade.unrealizedPnlCents || 0
  }));
  const netDollarDeltaCents = perPosition.reduce(
    (total, row) => total + row.netDollarDeltaCents, 0);
  const bySymbol = {};
  perPosition.forEach(row => {
    bySymbol[row.symbol] = (bySymbol[row.symbol] || 0) + Math.abs(row.netDollarDeltaCents);
  });
  return {
    netDollarDeltaCents: netDollarDeltaCents,
    grossDollarDeltaCents: perPosition.reduce(
      (total, row) => total + Math.abs(row.netDollarDeltaCents), 0),
    grossDollarDeltaBySymbolCents: bySymbol,
    dollarDeltaComplete: true,
    thetaCentsPerDay: canonical.thetaCentsPerDay * rows.length,
    vegaCentsPerPoint: canonical.vegaCentsPerPoint * rows.length,
    perShareAvailable: false,
    perShareUnavailableReason: 'Share delta is not additive across underlyings.',
    activeTrades: rows.length,
    measuredTrades: rows.length,
    positions: perPosition,
    basis: rows.length ? 'PRACTICE_EXECUTABLE_MARKS' : 'No active Practice positions.'
  };
}

/** The Book risk lens, in the same units the greeks receipt publishes. */
function bookRisk(greeks) {
  return {
    accounts: [],
    crossAccount: null,
    practice: {
      dollarDeltaNetCents: greeks.netDollarDeltaCents,
      dollarDeltaGrossCents: greeks.grossDollarDeltaCents,
      thetaCentsPerDay: greeks.thetaCentsPerDay,
      vegaCentsPerPoint: greeks.vegaCentsPerPoint,
      perShareAvailable: false,
      perShareUnavailableReason: 'Share delta is not additive across underlyings.',
      basis: 'PRACTICE_EXECUTABLE_MARKS'
    },
    basis: 'PRACTICE_EXECUTABLE_MARKS'
  };
}

/**
 * `ApiResponses.TradeDetail` for one held line. `current.greeks` is the legacy unit shape (see
 * portfolioGreeks above) and `payoff` uses `ApiResponses.PayoffPoint`, whose price is a STRING —
 * both faithful to today's wire.
 */
function tradeDetail(trade) {
  const canonical = golden.goldenGreeks();
  const current = {
    tradeId: trade.id,
    ts: wire.OBSERVED_AT_ISO,
    underlyingCents: trade.entryUnderlyingCents,
    closeCostCents: Math.abs(trade.entryNetPremiumCents) - (trade.unrealizedPnlCents || 0),
    unrealizedCents: trade.unrealizedPnlCents || 0,
    decisionUnrealizedCents: trade.unrealizedPnlCents || 0,
    popNow: trade.popEntry,
    freshness: 'REALTIME',
    greeks: {
      deltaShares: canonical.deltaShares,
      gammaSharesPerDollar: canonical.gammaSharesPerDollar,
      thetaCentsPerDay: canonical.thetaCentsPerDay,
      vegaCentsPerPoint: canonical.vegaCentsPerPoint
    },
    legGreeks: []
  };
  const payoff = (trade.terminalPayoff && trade.terminalPayoff.available)
    ? trade.terminalPayoff.points.map(point => ({
      price: String(point.price), profitCents: point.profitCents
    })) : [];
  return {
    trade: trade,
    current: current,
    marksHistory: [
      Object.assign({}, current, { ts: '2026-07-23T20:00:00Z',
        unrealizedCents: Math.round((trade.unrealizedPnlCents || 0) / 2) }),
      current
    ],
    audit: []
    /* No `payoff` here on purpose: ApiResponses.TradeDetail is five components, and the held curve
       has one owner — trade.terminalPayoff. A second list on this envelope is exactly what let a
       detail event overwrite the good curve with an empty array. */
  };
}

/**
 * Every Book document for one roster size, all derived from the same trades. This is the object a
 * state-matrix test drives its backend mock from.
 */
function bookDocuments(options) {
  const settings = Object.assign({ positions: 1, shares: 0 }, options || {});
  const trades = positions(settings.positions);
  const shares = sharePositions(settings.shares);
  const summary = portfolioSummary(trades, shares);
  const greeks = portfolioGreeks(trades);
  return {
    activeTrades: trades,
    tradePage: tradePage(trades),
    sharePositions: shares,
    positionBook: { positions: shares, note: shares.length ? null : 'No share positions.' },
    summary: summary,
    heat: portfolioHeat(trades, summary),
    greeks: greeks,
    bookRisk: bookRisk(greeks),
    tradeDetails: trades.reduce((byId, trade) => {
      byId[trade.id] = tradeDetail(trade);
      return byId;
    }, {})
  };
}

module.exports = {
  tradeView, goldenHeldTrade, positions, tradePage, sharePositions,
  portfolioSummary, portfolioHeat, portfolioGreeks, bookRisk, tradeDetail, bookDocuments,
  FEE_PER_CONTRACT_CENTS
};
