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
const { packagePrice } = require('./price');

/** Per-contract commission, both sides, so fees scale with the package like the engine's do. */
const FEE_PER_CONTRACT_CENTS = 50;

function feesFor(legList, quantity) {
  return legList.filter(leg => !math.isStock(leg)).length * quantity * FEE_PER_CONTRACT_CENTS;
}

function grossShortPutObligationCents(trades) {
  return (trades || []).reduce((total, trade) => total + trade.legs
    .filter(leg => leg.type === 'PUT' && leg.action === 'SELL')
    .reduce((legTotal, leg) => legTotal
      + Math.round(Number(leg.strike) * 100) * leg.ratio * leg.multiplier * trade.qty, 0), 0);
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
  const entryPrice = packagePrice({
    quantity: o.qty,
    optionNetPremiumCents: math.optionNetPremiumCents(legList, o.qty),
    stockCashFlowCents: math.stockCashFlowCents(legList, o.qty),
    openingFeesCents: fees,
    estimatedRoundTripFeesCents: fees * 2,
    executableNetCents: entryNet,
    valuationBasis: 'RECORDED_FILL',
    executability: 'IMMEDIATE',
    source: o.dataSource,
    freshness: o.dataAge,
    observedAt: null,
    fingerprint: `recorded-${o.id}-${entryNet}`,
    feeSide: 'OPENING'
  });

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
    entryPrice: entryPrice,
    maxLossCents: extremes.maxLossCents,
    maxProfitCents: extremes.maxProfitCents,
    breakevens: mixedExpiry ? [] : math.breakevens(legList, o.qty),
    popEntry: o.popEntry,
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
    dataProvenance: o.dataProvenance,
    dataAge: o.dataAge,
    dataSource: o.dataSource,
    // The held-line display receipts. A mixed-expiry line carries an explicitly UNAVAILABLE
    // terminal payoff and no story checkpoints — the state that must render as "unavailable" with
    // a reason rather than falling back to a browser-drawn curve (§3.2).
    terminalPayoff: !o.withReceipts ? null
      : mixedExpiry
        ? golden.goldenTerminalPayoff({ available: false })
        : heldTerminalPayoff(legList, o.qty, o.entryUnderlyingCents, o.payoffSpanPct),
    scenarios: o.withReceipts && !mixedExpiry
      ? {
        available: true,
        values: heldScenarios(legList, o.qty, o.entryUnderlyingCents),
        anchorSpotCents: o.entryUnderlyingCents,
        anchorBasis: 'LAST',
        freshness: o.dataAge,
        source: o.dataSource,
        observedAt: wire.OBSERVED_AT_MS,
        unavailableReason: null
      } : {
        available: false,
        values: [],
        unavailableReason: mixedExpiry
          ? 'A mixed-expiration package requires supplied-path valuation.'
          : 'Named held-position scenarios were not requested.'
      }
  };
  const row = wire.nonNull(trade);
  const currentClosePrice = packagePrice({
    quantity: o.qty,
    optionNetPremiumCents: math.optionNetPremiumCents(legList, o.qty),
    stockCashFlowCents: math.stockCashFlowCents(legList, o.qty),
    openingFeesCents: fees,
    estimatedRoundTripFeesCents: null,
    executableNetCents: entryNet,
    valuationBasis: 'EXECUTABLE_BOOK',
    executability: 'IMMEDIATE',
    source: o.dataSource,
    freshness: o.dataAge,
    observedAt: wire.OBSERVED_AT_MS,
    fingerprint: `closing-${o.id}-${entryNet}`,
    feeSide: 'CLOSING'
  });
  const availability = currentMarketAvailability({
    quoteAvailable: true,
    closeAvailable: true,
    decisionPnlAvailable: o.unrealizedPnlCents != null,
    popAvailable: o.popEntry != null,
    greeksAvailable: o.withReceipts
  });
  // These are fixture-source facts used to compose the canonical sibling MarkView. They are
  // deliberately non-enumerable: TradeView no longer carries current-market aliases, so neither
  // Object.keys nor JSON serialization can publish them on the roster wire.
  Object.defineProperties(row, {
    unrealizedPnlCents: { value: o.unrealizedPnlCents, enumerable: false },
    decisionUnrealizedPnlCents: { value: o.unrealizedPnlCents, enumerable: false },
    currentUnderlyingCents: { value: o.entryUnderlyingCents, enumerable: false },
    currentClosePrice: { value: currentClosePrice, enumerable: false },
    indicativeUnrealizedPnlCents: { value: o.unrealizedPnlCents, enumerable: false },
    indicativeDecisionUnrealizedPnlCents: { value: o.unrealizedPnlCents, enumerable: false },
    currentMarketAvailability: { value: availability, enumerable: false },
    greeks: { value: o.withReceipts ? golden.goldenGreeks() : null, enumerable: false }
  });
  return row;
}

/** `TradeService.CurrentMarketAvailability`, with one reason for every unavailable component. */
function currentMarketAvailability(available) {
  const a = Object.assign({
    quoteAvailable: false,
    closeAvailable: false,
    decisionPnlAvailable: false,
    popAvailable: false,
    greeksAvailable: false
  }, available || {});
  function reason(ok, fact) {
    return ok ? null : `No current ${fact} receipt is available in this fixture.`;
  }
  return {
    quoteAvailable: a.quoteAvailable,
    quoteUnavailableReason: reason(a.quoteAvailable, 'quote'),
    closeAvailable: a.closeAvailable,
    closeUnavailableReason: reason(a.closeAvailable, 'close'),
    decisionPnlAvailable: a.decisionPnlAvailable,
    decisionPnlUnavailableReason: reason(a.decisionPnlAvailable, 'position P/L'),
    popAvailable: a.popAvailable,
    popUnavailableReason: reason(a.popAvailable, 'probability'),
    greeksAvailable: a.greeksAvailable,
    greeksUnavailableReason: reason(a.greeksAvailable, 'Greeks')
  };
}

function entryGross(trade) {
  return trade && trade.entryPrice ? trade.entryPrice.grossPackageNetCents : null;
}

function entryFees(trade) {
  return trade && trade.entryPrice ? trade.entryPrice.openingFeesCents : null;
}

/**
 * The exact static `TradeRecord` shape embedded in `PracticeBookSnapshot.activeTrades`.
 *
 * Current price, P/L, POP, Greeks, availability and provenance do NOT live on this row. They
 * belong exclusively to the sibling `marksByTrade[tradeId]` MarkView. Keeping that separation in
 * fixtures is essential: the previous enriched rows let the browser read fields production never
 * sends and therefore hid a real "current mark unavailable" regression.
 */
function staticTradeRecord(trade, options) {
  const settings = Object.assign({}, options || {});
  const entry = trade && trade.entryPrice || {};
  return wire.nonNull({
    id: trade.id,
    accountId: settings.accountId || trade.accountId || wire.ACCOUNT_ID,
    symbol: trade.symbol,
    strategy: trade.strategy,
    status: trade.status,
    qty: trade.qty,
    legs: JSON.parse(JSON.stringify(trade.legs || [])),
    thesis: trade.thesis,
    horizon: trade.horizon,
    riskMode: trade.riskMode,
    entryUnderlyingCents: trade.entryUnderlyingCents,
    entryNetPremiumCents: entry.grossPackageNetCents,
    maxLossCents: trade.maxLossCents,
    maxProfitCents: trade.maxProfitCents,
    breakevens: (trade.breakevens || []).map(String),
    popEntry: trade.popEntry,
    feesOpenCents: entry.openingFeesCents || 0,
    feesCloseCents: 0,
    realizedPnlCents: trade.realizedPnlCents,
    decisionPnlCents: trade.decisionPnlCents,
    closeReason: trade.closeReason,
    entrySnapshotJson: trade.entrySnapshot ? JSON.stringify(trade.entrySnapshot) : null,
    isLive: trade.isLive,
    createdAt: trade.createdAt,
    closedAt: trade.closedAt,
    updatedAt: trade.updatedAt,
    intent: trade.intent,
    sharesLocked: trade.sharesLocked || 0,
    orderLimitNetCents: trade.orderLimitNetCents,
    dataProvenance: trade.dataProvenance,
    dataAge: trade.dataAge,
    dataSource: trade.dataSource
  });
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
    anchorPnlCents: math.terminalPnlCents(legList, quantity, anchorCents / 100),
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
  const stories = require('./scenarios').STORIES;
  return stories.map(story => ({
    story: story.story,
    underlyingMovePct: story.underlyingMovePct,
    targetUnderlyingCents: Math.round(anchorCents * (1 + story.underlyingMovePct)),
    pnlCents: math.terminalPnlCents(legList, quantity,
      Math.round(anchorCents * (1 + story.underlyingMovePct)) / 100),
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
function tradeViewContract(trade) {
  const row = Object.assign({}, trade);
  [
    'unrealizedPnlCents', 'decisionUnrealizedPnlCents', 'currentUnderlyingCents',
    'currentClosePrice', 'indicativeUnrealizedPnlCents',
    'indicativeDecisionUnrealizedPnlCents', 'currentMarketAvailability', 'greeks'
  ].forEach(field => delete row[field]);
  return row;
}

function tradePage(trades, overrides) {
  const rows = (trades || []).map(tradeViewContract);
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
    (total, trade) => total + entryGross(trade) + (trade.unrealizedPnlCents || 0), 0);
  const sharesValueCents = shareRows.reduce((total, row) => total + row.marketValueCents, 0);
  const cashCents = startingCashCents
    + rows.reduce((total, trade) => total + entryGross(trade) - entryFees(trade), 0)
    - shareRows.reduce((total, row) => total + row.shares * row.avgCostCents, 0);
  const totalValueCents = cashCents + sharesValueCents + openTradesUnrealizedCents;
  const buyingPowerCents = cashCents - reservedCents;
  return {
    cashCents: cashCents,
    reservedCents: reservedCents,
    buyingPowerCents: buyingPowerCents,
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
      : 'Authoritative empty Practice account receipt.',
    liquidity: {
      schemaVersion: 'account-liquidity-v1',
      accountId: wire.ACCOUNT_ID,
      lane: 'PRACTICE',
      settlementBalance: {
        cents: cashCents, authority: 'SYSTEM_CALCULATED',
        basis: 'Exact Practice cash ledger balance; reserve remains inside cash.'
      },
      pendingActivity: {
        cents: 0, authority: 'SYSTEM_CALCULATED',
        basis: 'Practice entries settle synchronously, so there is no pending broker activity.'
      },
      recordedOrReportedReserve: {
        cents: reservedCents, authority: 'SYSTEM_CALCULATED',
        basis: 'Exact reserve held by the canonical Practice ledger.'
      },
      theoreticalShortPutObligation: {
        cents: grossShortPutObligationCents(rows), authority: 'SYSTEM_CALCULATED',
        basis: 'Gross strike obligation across active short puts from canonical trade geometry.'
      },
      genuinelyFreeBuyingPower: {
        cents: buyingPowerCents, authority: 'SYSTEM_CALCULATED',
        basis: 'Exact Practice cash less exact recorded reserve.'
      },
      concurrentCollateralIncome: {
        authority: 'UNAVAILABLE',
        basis: 'Practice cash has no broker-reported settlement-fund income receipt.'
      },
      reconciliationDifference: {
        cents: 0, authority: 'SYSTEM_CALCULATED',
        basis: 'Settlement less pending activity, reserve, and genuinely free buying power.'
      },
      reconciliationStatus: 'RECONCILED',
      reconciliationReason: 'Practice settlement, reserve, and buying power reconcile exactly.',
      evidenceAsOf: wire.OBSERVED_AT_ISO,
      sourceRefs: ['accounts.cash_cents', 'accounts.reserved_cents', 'ledger', 'trades']
    }
  };
}

const SHARE_DENOMINATOR_BASIS = 'Book defined risk: the sum of every ACTIVE position\'s maximum '
  + 'loss in this account, read from the canonical portfolio-heat receipt (totalMaxLossCents).';

/**
 * The canonical share/rank fixture mirrors `BookRiskService.shareRoster` — the ONE backend owner
 * of the fact. Largest defined risk
 * first (symbol then id breaking ties); positions carrying identical risk SHARE one rank (1, 2, 2, 4);
 * with no book total both share and rank are withheld with a reason, never stated as 0%.
 */
function bookShareRoster(trades, denominatorCents) {
  const ranked = (trades || []).slice().sort((a, b) => (b.maxLossCents - a.maxLossCents)
    || String(a.symbol).localeCompare(String(b.symbol)) || String(a.id).localeCompare(String(b.id)));
  let unavailableReason = null;
  if (!ranked.length) {
    unavailableReason = 'This account holds no open positions, so there is no book risk to take a '
      + 'share of.';
  } else if (!(denominatorCents > 0)) {
    unavailableReason = 'This book\'s defined risk totals $0.00 across ' + ranked.length
      + ' open position' + (ranked.length === 1 ? '' : 's')
      + ', so no position has a measurable share of it.';
  }
  let rank = 0;
  let previousRisk = 0;
  const positions = ranked.map((trade, index) => {
    if (index === 0 || trade.maxLossCents !== previousRisk) rank = index + 1;
    previousRisk = trade.maxLossCents;
    return {
      tradeId: trade.id,
      symbol: trade.symbol,
      strategy: trade.strategy,
      riskCents: trade.maxLossCents,
      sharePct: unavailableReason ? null : 100 * trade.maxLossCents / denominatorCents,
      rank: unavailableReason ? null : rank,
      rankOf: unavailableReason ? null : ranked.length,
      denominatorCents: denominatorCents,
      denominatorBasis: SHARE_DENOMINATOR_BASIS,
      unavailableReason: unavailableReason
    };
  });
  return { available: unavailableReason === null, unavailableReason: unavailableReason, positions };
}

/**
 * Portfolio heat HTTP document. TradeService supplies the raw heat prefix; PortfolioController
 * attaches BookRiskService's one canonical share roster. The fixture mirrors that single wire
 * receipt and deliberately carries no flat compatibility projection.
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
  const shortPutObligationCents = grossShortPutObligationCents(rows);
  return {
    activeTrades: rows.length,
    totalMaxLossCents: totalMaxLossCents,
    reservedCents: summary ? summary.reservedCents : totalMaxLossCents,
    shortVolTrades: rows.filter(trade => entryGross(trade) > 0).length,
    bySymbolMaxLossCents: bySymbol,
    concentrationPct: totalMaxLossCents > 0
      ? Math.round(100 * worstSymbol / totalMaxLossCents) : 0,
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
function bookRisk(greeks, trades, heat, accountId) {
  const totalMaxLossCents = heat ? heat.totalMaxLossCents : 0;
  const shares = bookShareRoster(trades || [], totalMaxLossCents);
  const shareRoster = {
    available: shares.available,
    unavailableReason: shares.unavailableReason,
    accountId: accountId || wire.ACCOUNT_ID,
    positions: shares.positions.length,
    denominatorCents: shares.available ? totalMaxLossCents : null,
    denominatorBasis: SHARE_DENOMINATOR_BASIS,
    rows: shares.positions.map(row => ({
      tradeId: row.tradeId,
      symbol: row.symbol,
      strategy: row.strategy,
      riskCents: row.riskCents,
      denominatorCents: row.denominatorCents,
      denominatorBasis: row.denominatorBasis,
      sharePct: row.sharePct,
      rank: row.rank,
      rankOf: row.rankOf,
      unavailableReason: row.unavailableReason
    })),
    basis: 'Each open position’s share of this account’s defined book risk.'
  };
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
      shareRoster,
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
    unrealizedCents: trade.unrealizedPnlCents || 0,
    decisionUnrealizedCents: trade.unrealizedPnlCents || 0,
    currentClosePrice: trade.currentClosePrice,
    indicativeUnrealizedCents: trade.indicativeUnrealizedPnlCents,
    indicativeDecisionUnrealizedCents: trade.indicativeDecisionUnrealizedPnlCents,
    popNow: trade.popEntry,
    freshness: 'REALTIME',
    greeks: {
      deltaShares: canonical.deltaShares,
      gammaSharesPerDollar: canonical.gammaSharesPerDollar,
      thetaCentsPerDay: canonical.thetaCentsPerDay,
      vegaCentsPerPoint: canonical.vegaCentsPerPoint
    },
    legGreeks: [],
    availability: trade.currentMarketAvailability,
    underlyingQuote: null,
    marketImpliedRisk: golden.goldenMarketImpliedRisk()
  };
  const payoff = (trade.terminalPayoff && trade.terminalPayoff.available)
    ? trade.terminalPayoff.points.map(point => ({
      price: String(point.price), profitCents: point.profitCents
    })) : [];
  return {
    trade: tradeViewContract(trade),
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
  const sourceTrades = positions(settings.positions);
  const trades = sourceTrades.map(tradeViewContract);
  const shares = sharePositions(settings.shares);
  const summary = portfolioSummary(sourceTrades, shares);
  const greeks = portfolioGreeks(sourceTrades);
  const heat = portfolioHeat(sourceTrades, summary);
  return {
    activeTrades: trades,
    tradePage: tradePage(sourceTrades),
    sharePositions: shares,
    positionBook: { positions: shares, note: shares.length ? null : 'No share positions.' },
    summary: summary,
    heat: heat,
    greeks: greeks,
    bookRisk: bookRisk(greeks, sourceTrades, heat, wire.ACCOUNT_ID),
    tradeDetails: sourceTrades.reduce((byId, trade) => {
      byId[trade.id] = tradeDetail(trade);
      return byId;
    }, {})
  };
}

/**
 * Exact `PracticeBookRead` v1 envelope for a fixture Book document.
 *
 * Keep this adapter beside the one Book fixture owner. Browser lanes must not independently
 * reconstruct the canonical Book response (that is how the retired summary/heat/greeks routes
 * survived in visual mocks after production stopped calling them).
 */
function practiceBookRead(documents, options) {
  const settings = Object.assign({
    accountId: wire.ACCOUNT_ID,
    accountName: 'Synthetic Practice account',
    snapshotId: 'pbs_fixture_book',
    selectedTradeIds: []
  }, options || {});
  const summary = JSON.parse(JSON.stringify(documents.summary));
  const heat = Object.assign({
    reservedCents: summary.reservedCents,
    assignmentReserveReleasedCents: 0
  }, JSON.parse(JSON.stringify(documents.heat)));
  const greeks = JSON.parse(JSON.stringify(documents.greeks));
  const shareRoster = Object.assign({},
    JSON.parse(JSON.stringify(documents.bookRisk.practice.shareRoster || {})), {
      accountId: settings.accountId
    });
  const practiceRisk = Object.assign({},
    JSON.parse(JSON.stringify(documents.bookRisk.practice)), {
      shareRoster
    });
  const liquidity = JSON.parse(JSON.stringify(summary.liquidity));
  liquidity.accountId = settings.accountId;
  summary.liquidity = liquidity;

  const marksByTrade = {};
  Object.entries(documents.tradeDetails || {}).forEach(([tradeId, detail]) => {
    if (detail && detail.current) {
      marksByTrade[tradeId] = JSON.parse(JSON.stringify(detail.current));
    }
  });
  const selectedIds = new Set(settings.selectedTradeIds || []);
  const selectedPositions = (documents.activeTrades || [])
    .filter(trade => selectedIds.has(trade.id))
    .map(trade => ({
      tradeId: trade.id,
      symbol: trade.symbol,
      strategy: trade.strategy,
      riskCents: trade.maxLossCents,
      netDollarDeltaCents: (greeks.positions || [])
        .find(row => row.id === trade.id)?.netDollarDeltaCents || 0
    }));

  return {
    schemaVersion: 'practice-book-read-v1',
    snapshotId: settings.snapshotId,
    account: {
      accountId: settings.accountId,
      name: settings.accountName,
      type: 'PAPER',
      startingBalanceCents: summary.startingCashCents,
      settlementBalanceCents: summary.cashCents,
      recordedReserveCents: summary.reservedCents,
      genuinelyFreeBuyingPowerCents: summary.buyingPowerCents
    },
    summary,
    snapshot: {
      schemaVersion: 'practice-book-snapshot-v1',
      snapshotId: settings.snapshotId,
      accountId: settings.accountId,
      activeTrades: (documents.activeTrades || [])
        .map(trade => staticTradeRecord(trade, { accountId: settings.accountId })),
      marksByTrade,
      heat,
      openPositions: {
        openTradesCount: summary.openTradesCount,
        markedTradesCount: summary.complete === false ? 0 : summary.openTradesCount,
        valueCents: summary.openTradesValueCents,
        unrealizedCents: summary.openTradesUnrealizedCents,
        complete: summary.complete !== false,
        freshness: summary.freshness
      },
      dollarDelta: {
        grossCents: greeks.grossDollarDeltaCents,
        netCents: greeks.netDollarDeltaCents,
        symbolGrossCents: greeks.grossDollarDeltaBySymbolCents,
        tradeNetCents: Object.fromEntries((greeks.positions || [])
          .map(row => [row.id, row.netDollarDeltaCents])),
        complete: greeks.dollarDeltaComplete,
        basis: greeks.basis
      },
      greeks,
      asOf: wire.OBSERVED_AT_ISO
    },
    sharePositions: JSON.parse(JSON.stringify(documents.sharePositions || [])),
    bookRisk: practiceRisk,
    liquidity,
    declaredRiskContext: {
      riskCapitalCents: null,
      accountObjective: null,
      assignmentPreference: null
    },
    selectedBook: {
      accountId: settings.accountId,
      tradeIds: Array.from(selectedIds),
      positions: selectedPositions,
      grossMaxLossCents: selectedPositions
        .reduce((total, row) => total + row.riskCents, 0),
      netDollarDeltaCents: selectedPositions
        .reduce((total, row) => total + row.netDollarDeltaCents, 0),
      complete: true,
      selectedPositions: selectedPositions.length,
      bookRiskDenominatorCents: heat.totalMaxLossCents,
      bookRiskDenominatorBasis: shareRoster.denominatorBasis || shareRoster.basis,
      basis: selectedPositions.length
        ? 'Selected Practice positions from the same Book snapshot.'
        : 'No Practice positions selected.'
    },
    basis: documents.bookRisk.basis
  };
}

/**
 * The existing BookActionProjectionService wire contract used by Position management. These are
 * read-only, authority-bearing after-action snapshots—not a second implementation of the action
 * math. The numbers are fixed sentinels so the browser tests can prove exact sign/unit routing.
 */
function bookActionProjectionSet(trade, overrides) {
  const o = Object.assign({
    cashCents: 9752000,
    reserveCents: 43210,
    shortPutObligationCents: 0,
    sharesBySymbol: {},
    closeOneNetCashCents: -19050,
    closeOneFeesCents: 260,
    conversionCashCents: 9123400,
    conversionReserveCents: 0,
    conversionNetCashCents: -64800
  }, overrides || {});
  const quantity = Math.max(1, Number(trade && trade.qty || 1));
  const fact = (cents, basis) => ({
    cents, authority: 'SYSTEM_CALCULATED', basis
  });
  const snapshot = (cash, reserve, obligation, shares) => ({
    cash: fact(cash, 'Canonical Practice cash after the read-only action projection.'),
    encumbrance: fact(Math.max(0, reserve),
      'Canonical Practice reserve after the read-only action projection.'),
    shortPutObligation: fact(Math.max(0, obligation),
      'Canonical gross short-put strike obligation after the action.'),
    sharesBySymbol: Object.assign({}, shares || {})
  });
  const noBasisChange = {
    optionTaxBasisRemovedCents: 0,
    optionEconomicBasisRemovedCents: 0,
    stockTaxBasisAddedCents: 0,
    stockTaxBasisRemovedCents: 0,
    basis: 'Practice opening basis remains in the append-only trade record.'
  };
  const cost = (net, fees, basis) => ({
    signedCashCents: net + fees,
    signedOptionCashCents: net + fees,
    feesCents: fees,
    signedNetCashCents: net,
    authority: 'OBSERVED',
    basis
  });
  const action = (name, affected, remaining, after, executableCost, fingerprint) => ({
    action: name,
    quantityAffected: affected,
    quantityRemaining: remaining,
    available: true,
    snapshot: after,
    basisEffect: noBasisChange,
    executableCost,
    steps: [{
      action: name === 'HOLD' ? 'HOLD' : 'CLOSE_EXISTING',
      status: 'AVAILABLE',
      basis: name === 'HOLD'
        ? 'The Practice ledger, reserve, and share inventory remain unchanged.'
        : 'TradeService reprices and recomputes the exact surviving Practice package.'
    }],
    fingerprint
  });
  const actions = [
    action('HOLD', 0, quantity,
      snapshot(o.cashCents, o.reserveCents, o.shortPutObligationCents, o.sharesBySymbol),
      {
        signedCashCents: 0, signedOptionCashCents: 0, feesCents: 0,
        signedNetCashCents: 0, authority: 'MODELED',
        basis: 'No transaction; no executable cost.'
      }, 'fixture-action-hold')
  ];
  for (let closeQuantity = 1; closeQuantity <= quantity; closeQuantity += 1) {
    const name = closeQuantity === quantity ? 'CLOSE_ALL'
      : closeQuantity === 1 ? 'CLOSE_ONE' : 'CLOSE_K';
    const net = o.closeOneNetCashCents * closeQuantity;
    const fees = o.closeOneFeesCents * closeQuantity;
    actions.push(action(name, closeQuantity, quantity - closeQuantity,
      snapshot(o.cashCents + net,
        Math.round(o.reserveCents * (quantity - closeQuantity) / quantity),
        Math.round(o.shortPutObligationCents * (quantity - closeQuantity) / quantity),
        o.sharesBySymbol),
      cost(net, fees,
        'Canonical Practice executable close sides and configured closing fees; no order is placed.'),
      `fixture-action-close-${closeQuantity}`));
  }
  const closeAll = actions.find(row => row.action === 'CLOSE_ALL');
  ['PUT', 'CALL'].forEach(type => {
    const shorts = (trade && trade.legs || []).filter(leg =>
      String(leg.action).toUpperCase() === 'SELL'
      && String(leg.type).toUpperCase() === type);
    if (shorts.length !== 1) return;
    const name = type === 'PUT' ? 'ASSIGNMENT' : 'CALL_AWAY';
    const units = quantity * Number(shorts[0].ratio || 1) * Number(shorts[0].multiplier || 100);
    const shares = Object.assign({}, o.sharesBySymbol);
    shares[trade.symbol] = Number(shares[trade.symbol] || 0) + (type === 'PUT' ? units : -units);
    if (shares[trade.symbol] === 0) delete shares[trade.symbol];
    const projected = action(name, quantity, 0,
      snapshot(o.conversionCashCents, o.conversionReserveCents,
        type === 'PUT' ? 0 : o.shortPutObligationCents, shares),
      cost(o.conversionNetCashCents, 0,
        'Contractual lifecycle conversion from the canonical Practice transformation owner.'),
      `fixture-action-${name.toLowerCase()}`);
    projected.steps = [{
      action: name,
      status: 'AVAILABLE',
      basis: 'The canonical Practice transformation projected the exact short option leg.'
    }];
    actions.push(projected);
  });
  actions.push({
    action: 'ROLL',
    quantityAffected: quantity,
    quantityRemaining: 0,
    available: false,
    unavailableReason: 'Exact replacement contracts are required before the open can be projected.',
    snapshot: closeAll.snapshot,
    basisEffect: noBasisChange,
    executableCost: closeAll.executableCost,
    steps: [
      { action: 'CLOSE_EXISTING', status: 'AVAILABLE',
        basis: 'Uses the canonical Practice close preview.' },
      { action: 'OPEN_REPLACEMENT', status: 'UNAVAILABLE',
        basis: 'No exact replacement package was supplied.' }
    ],
    fingerprint: 'fixture-action-roll'
  });
  return {
    schemaVersion: 'book-action-projection-v1',
    accountId: wire.ACCOUNT_ID,
    positionFingerprint: `fixture-position-${trade && trade.id || 'unknown'}`,
    observedAt: wire.OBSERVED_AT_ISO,
    actions,
    basis: 'Read-only Practice projections reuse existing TradeService transformations.'
  };
}

module.exports = {
  tradeView, staticTradeRecord, goldenHeldTrade, positions, tradePage, sharePositions,
  portfolioSummary, portfolioHeat, portfolioGreeks, bookRisk, tradeDetail, bookDocuments,
  practiceBookRead, bookActionProjectionSet, FEE_PER_CONTRACT_CENTS
};
