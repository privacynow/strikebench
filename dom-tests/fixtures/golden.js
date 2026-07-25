'use strict';

/**
 * THE golden package: one priced package whose every derived number is known in advance, so the
 * same receipt can be asserted on Home, on New Idea and on Position and any disagreement is a
 * product defect rather than a fixture difference (§16.3: "Home/New Idea/Position show identical
 * POP, P/L, max loss, Greeks, price/change, and expiry for the same receipt").
 *
 * The structure — GLDN, 3 lots, SELL 250 PUT / BUY 245 PUT, expiring 2026-08-21 — is chosen so
 * every consequence is exact in cents with no rounding anywhere:
 *
 *   per-spread credit  4.00 − 2.50            = 1.50
 *   option net premium 1.50 × 100 × 3         = +45,000c   (a credit, so positive)
 *   stock cash flow    option-only package    =       0c
 *   gross package net  45,000 + 0             = +45,000c
 *   opening fees       3 lots × 2 legs × $0.50=     300c
 *   after-fee net      45,000 − 300           = +44,700c
 *   max profit         the credit                +45,000c
 *   max loss           (250−245) × 100 × 3 − 45,000 = 105,000c
 *   breakeven          250.00 − 1.50          =   248.50
 *
 * ONE function owns the payoff (`profitCentsAt`), and the terminal-payoff receipt, the scenario
 * checkpoints and the tail all read from it. That is deliberate: if the fixture stated those three
 * separately they could disagree, and a cross-surface identity test built on a self-inconsistent
 * receipt proves nothing. (Program §3.1 forbids the BROWSER originating financial facts; a test
 * fixture standing in for the engine is the one place the arithmetic has to live.)
 */

const wire = require('./wire');
const { legView } = require('./legs');
const { packagePrice, unavailablePackagePrice } = require('./price');
const math = require('./package-math');

const SYMBOL = wire.GOLDEN_SYMBOL;
const QUANTITY = 3;
const SHARES = QUANTITY * 100;
const SHORT_STRIKE_CENTS = 25000;
const LONG_STRIKE_CENTS = 24500;
const WIDTH_CENTS = SHORT_STRIKE_CENTS - LONG_STRIKE_CENTS;
const ANCHOR_SPOT_CENTS = 25000;
const PREV_CLOSE_CENTS = 24750;

const OPTION_NET_CENTS = 45000;
const STOCK_CASH_CENTS = 0;
const GROSS_NET_CENTS = OPTION_NET_CENTS + STOCK_CASH_CENTS;
const FEES_CENTS = 300;
const AFTER_FEE_NET_CENTS = GROSS_NET_CENTS - FEES_CENTS;
const MAX_PROFIT_CENTS = GROSS_NET_CENTS;
const MAX_LOSS_CENTS = SHARES * WIDTH_CENTS - GROSS_NET_CENTS;   // 150,000 − 45,000
const BREAKEVEN = '248.50';
const POP = 0.7312;
const TAIL_POP = 0.6914;
const FINGERPRINT = 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90';

/** The canonical story-move set (`RiskProfiler.MOVES`) — fractions, not percents. */
const STORY_MOVES = [-0.20, -0.09, -0.06, -0.01, 0.0, 0.06, 0.13, 0.20];
/** Risk-neutral mass per checkpoint, summing to exactly 1. */
const STORY_PROBS = [0.02, 0.05, 0.07, 0.19, 0.22, 0.24, 0.14, 0.07];

/**
 * Terminal P/L of the whole package at an underlying price, in cents — delegated to the one
 * package-math owner so this package's curve, its checkpoints and its extremes cannot disagree.
 * `fixtures.test.js` re-derives the same values from the closed form (credit less the short put's
 * capped intrinsic) as an independent check on the shared engine.
 */
function profitCentsAt(priceDollars) {
  return math.terminalPnlCents(goldenLegs(), QUANTITY, priceDollars);
}

/**
 * The underlying price this far from the anchor, in dollars — rounded to whole cents so no float
 * residue (25000 × 1.1 = 27500.000000000004) ever reaches a fixture. Every story checkpoint, every
 * payoff endpoint and every scenario price comes through here.
 */
function priceAt(movePct) {
  return Math.round(ANCHOR_SPOT_CENTS * (1 + movePct)) / 100;
}

/** The package's legs, exactly as `LegView.of` would emit them. */
function goldenLegs() {
  return [
    legView({ action: 'SELL', type: 'PUT', strike: SHORT_STRIKE_CENTS / 100,
      expiration: wire.NEAR_EXPIRATION, entryPrice: 4, quoteBid: 3.95, quoteAsk: 4.05 }),
    legView({ action: 'BUY', type: 'PUT', strike: LONG_STRIKE_CENTS / 100,
      expiration: wire.NEAR_EXPIRATION, entryPrice: 2.5, quoteBid: 2.45, quoteAsk: 2.55 })
  ];
}

/** The §7.2 receipt for this package. */
function goldenPrice(overrides) {
  return packagePrice(Object.assign({
    quantity: QUANTITY,
    optionNetPremiumCents: OPTION_NET_CENTS,
    stockCashFlowCents: STOCK_CASH_CENTS,
    openingFeesCents: FEES_CENTS,
    executableNetCents: GROSS_NET_CENTS,
    valuationBasis: 'EXECUTABLE_BOOK',
    executability: 'IMMEDIATE',
    source: 'FIXTURE_EXECUTABLE_BOOK',
    freshness: 'REALTIME',
    observedAt: wire.OBSERVED_AT_MS,
    fingerprint: FINGERPRINT,
    feeSide: 'OPENING'
  }, overrides || {}));
}

/**
 * The exact payoff polyline, as `RiskProfile.TerminalPayoff`. Points carry the price in DOLLARS
 * and the profit in CENTS — the two units sit side by side on this record, and reading either as
 * the other is a whole class of live defect.
 *
 * `spanPct` is the fraction of the anchor the served curve covers. It is a parameter, not a
 * constant, because `payFor()` returns null OFF the served curve and the renderer's handling of
 * that null is a P0 defect (§5.2 regression 2): a curve narrower than the ±20% story grid is the
 * state that reproduces it. `spanPct: 0.20` is the healthy default; `spanPct: 0.10` is the trap.
 */
function goldenTerminalPayoff(options) {
  const settings = Object.assign({ spanPct: 0.20, available: true, unavailableReason: null },
    options || {});
  if (!settings.available) {
    return {
      schemaVersion: 'risk-terminal-payoff-1',
      modelVersion: 'payoff-curve-1',
      available: false,
      anchorSpotCents: ANCHOR_SPOT_CENTS,
      expiration: wire.NEAR_EXPIRATION,
      basis: null,
      entryBasis: null,
      feesIncluded: false,
      points: [],
      unavailableReason: settings.unavailableReason
        || 'A mixed-expiration package requires supplied-path valuation.'
    };
  }
  const low = priceAt(-settings.spanPct);
  const high = priceAt(settings.spanPct);
  // The interior knots are the only prices where the curve bends: the long strike, the breakeven,
  // and the short strike. Between them linear interpolation is exact, which is what lets the
  // browser interpolate without originating a value.
  const knots = [low, LONG_STRIKE_CENTS / 100, Number(BREAKEVEN), SHORT_STRIKE_CENTS / 100, high]
    .filter(price => price >= low && price <= high)
    .sort((a, b) => a - b);
  // `RiskProfile.PayoffPoint.price` is a BigDecimal and reaches the browser as a JSON NUMBER.
  // (`ApiResponses.PayoffPoint`, on the held-trade detail, is a String — see heldPayoff().)
  const points = Array.from(new Set(knots)).map(price => ({
    price: price, profitCents: profitCentsAt(price)
  }));
  return {
    schemaVersion: 'risk-terminal-payoff-1',
    modelVersion: 'payoff-curve-1',
    available: true,
    anchorSpotCents: ANCHOR_SPOT_CENTS,
    expiration: wire.NEAR_EXPIRATION,
    basis: 'Terminal value at expiration, priced from the recorded package entry.',
    entryBasis: 'AFTER_FEE_NET',
    feesIncluded: false,
    points: points,
    unavailableReason: null
  };
}

/**
 * The eight named story checkpoints (`RiskProfile.Scenario`). `underlyingMovePct` is a FRACTION;
 * the Desk multiplies by 100 to reach its percent grid.
 *
 * `withProb: false` produces the honest no-ATM-IV state: the checkpoint keeps its priced P/L and
 * states no probability, rather than inventing one.
 */
function goldenScenarios(options) {
  const settings = Object.assign({ withProb: true }, options || {});
  return STORY_MOVES.map((movePct, index) => ({
    underlyingMovePct: movePct,
    pnlCents: profitCentsAt(priceAt(movePct)),
    prob: settings.withProb ? STORY_PROBS[index] : null
  }));
}

/** Canonical Greeks (`ScenarioCanvasValuator.Greeks`) — the ONE unit set every surface reports. */
function goldenGreeks() {
  return {
    deltaShares: 42.6,
    gammaSharesPerDollar: -1.85,
    thetaCentsPerDay: 860,
    vegaCentsPerPoint: -1240
  };
}

/** One stance of the Merton jump-mixture tail (`JumpMixtureTerminal.Receipt`). */
function tailReceipt(stance, dial, pop, shortfallCents) {
  return {
    stance: stance,
    dial: dial,
    pop: pop,
    expectedShortfallCents: shortfallCents,
    gapPct: 9,
    gapLossCents: -MAX_LOSS_CENTS,
    gapDir: '-',
    atMaxLoss: true,
    undefinedRisk: false,
    sector: 'default',
    intensityPct: 24,
    eventSoon: false,
    eventName: null,
    intensity: 0.24,
    jumpMeanLog: -0.0912,
    jumpSd: 0.0645,
    bodySd: 0.2814,
    drift: 0.0,
    gap: 0.09
  };
}

const TAIL_BASIS = 'Merton jump-mixture real-world (physical) tail.';

/** The tail lane (`JumpMixtureTerminal.Tail`); `available: false` states its reason instead. */
function goldenJumpTail(options) {
  const settings = Object.assign({ available: true, unavailableReason: null }, options || {});
  if (!settings.available) {
    return {
      schemaVersion: 'risk-jump-tail-1',
      modelVersion: 'merton-jump-mixture-1',
      available: false,
      headlineStance: 'BASE',
      base: null, calm: null, tense: null,
      basis: TAIL_BASIS,
      unavailableReason: settings.unavailableReason
        || 'No single-expiration curve / positive underlying anchor for the jump-mixture tail.'
    };
  }
  return {
    schemaVersion: 'risk-jump-tail-1',
    modelVersion: 'merton-jump-mixture-1',
    available: true,
    headlineStance: 'BASE',
    base: tailReceipt('BASE', 1.0, TAIL_POP, -68400),
    calm: tailReceipt('CALM', 0.5, 0.7186, -52100),
    tense: tailReceipt('TENSE', 1.9, 0.6428, -84900),
    basis: TAIL_BASIS,
    unavailableReason: null
  };
}

/** The full `RiskProfile` the evaluation receipt carries. */
function goldenRiskProfile(options) {
  const settings = Object.assign({ payoffSpanPct: 0.20, withProb: true }, options || {});
  return {
    maxLossCents: MAX_LOSS_CENTS,
    maxProfitCents: MAX_PROFIT_CENTS,
    pop: POP,
    expectedValueCents: 6200,
    tailLossCents: MAX_LOSS_CENTS,
    tailMovePct: 0.20,
    scenarios: goldenScenarios({ withProb: settings.withProb }),
    terminalPayoff: goldenTerminalPayoff({ spanPct: settings.payoffSpanPct }),
    evHistVolCents: 7400,
    evBasisNote: 'Market-implied EV +$62 · realized-vol EV +$74, both after round-trip costs.',
    jumpTail: goldenJumpTail()
  };
}

/**
 * The evaluation receipt (`ApiResponses.EvaluationReceipt`).
 *
 * Only `capital`, `risk` and `assessment` are populated. The other profiles are legitimately null
 * on this record and the mapper's NON_NULL inclusion drops them from the payload, so their absence
 * here is the wire's own shape, not an omission — a surface that needs one of them must state it
 * as unavailable rather than reading a shape that never arrives.
 */
function goldenEvaluation(options) {
  const settings = Object.assign({ payoffSpanPct: 0.20, withProb: true }, options || {});
  return wire.nonNull({
    available: true,
    unavailableReason: null,
    decisionScore: 71.4,
    viable: true,
    capital: {
      incrementalCents: MAX_LOSS_CENTS,
      economicCents: MAX_LOSS_CENTS,
      returnOnCapitalPct: 42.86,
      annualizedRocPct: 546.4,
      daysToExpiry: 28,
      basis: 'Defined-risk width less the credit collected.',
      annualizationNote: 'Annualized from a 28-day holding; it is not a repeatable yearly return.'
    },
    risk: goldenRiskProfile(settings),
    assessment: {
      mechanics: { eligible: true, reasons: [] },
      economics: {
        verdict: 'FAVORABLE',
        placement: 'BELOW_SPOT',
        label: 'Positive after costs on both lanes',
        summary: 'Market-implied and realized-vol EV agree after round-trip costs.',
        marketEvAfterCostsCents: 6200,
        realizedVolEvAfterCostsCents: 7400,
        estimatedRoundTripFeesCents: 600,
        marketEvPctOfRisk: 0.059,
        realisticEvLowAfterCostsCents: 3100,
        realisticEvHighAfterCostsCents: 11600,
        realisticEvMaterialityCents: 1050,
        marketEvRole: 'BENCHMARK',
        realisticEvBasis: 'REALIZED_VOL_AFTER_COSTS',
        observedEvidence: true,
        reasons: []
      },
      coherence: {
        verdict: 'COHERENT',
        directionAssessment: 'A neutral-to-higher view matches a put credit spread.',
        durationAssessment: '28 days to expiration matches the declared month horizon.',
        reasons: []
      },
      portfolioImpacts: { practice: null, real: null, notes: [] }
    }
  });
}

/**
 * The candidate as the rail receives it: the `Candidate` record plus the identity keys the
 * controllers attach to the serialized node (`PlanStrategyService` writes `id`;
 * `PlanStrategyController` writes `symbol` and `EvaluationReceipt.attachTo` writes `evaluation`).
 * Those three are not `Candidate` components, and a fixture that quietly invents a fourth is how a
 * suite comes to test a payload the server never sends.
 */
const CANDIDATE_ATTACHED_KEYS = ['id', 'symbol', 'evaluation', 'selected'];

function goldenCandidate(overrides) {
  const settings = Object.assign({
    id: 'candidate_golden_gldn',
    payoffSpanPct: 0.20,
    withProb: true,
    price: null,
    selected: undefined
  }, overrides || {});
  const candidate = {
    id: settings.id,
    symbol: SYMBOL,
    strategy: 'PUT_CREDIT_SPREAD',
    displayName: 'Put credit spread',
    structureGroup: 'VERTICAL',
    label: 'SELL 250P / BUY 245P Aug 21',
    legs: goldenLegs(),
    qty: QUANTITY,
    price: settings.price || goldenPrice(),
    maxProfitCents: MAX_PROFIT_CENTS,
    maxLossCents: MAX_LOSS_CENTS,
    breakevens: [BREAKEVEN],
    pop: POP,
    expectedValueCents: 6200,
    liquidityScore: 0.86,
    freshness: 'REALTIME',
    warnings: [],
    confidence: 0.74,
    whyConsidered: 'Sells the 250 strike below the anchor while the 245 wing caps the loss.',
    bestUpside: 'Expires worthless above 250.00 and keeps the full credit.',
    biggestRisk: 'A close below 245.00 realizes the full defined loss.',
    wouldInvalidate: 'A close below the 248.50 breakeven at expiration.',
    beginnerExplanation: 'You collect a credit now and keep it if the price stays above 250.',
    intent: 'INCOME',
    intents: ['INCOME'],
    assignmentProb: 0.21,
    annualizedYieldPct: 24.6,
    effectivePrice: '248.50',
    intentNote: 'Income against no held shares; the loss is defined by the 245 wing.',
    usesHeldShares: false,
    sharesNeeded: null,
    combinedMaxLossCents: null,
    evaluation: goldenEvaluation({ payoffSpanPct: settings.payoffSpanPct,
      withProb: settings.withProb })
  };
  if (settings.selected !== undefined) candidate.selected = settings.selected;
  return wire.nonNull(candidate);
}

/**
 * The same package with NO price at all — the §3.2 state. Every economic consequence that depends
 * on a price disappears with it; only the mechanical facts (legs, quantity, structure) survive.
 * This is the fixture behind "`unavailable` never renders `+$0`".
 */
function unpricedCandidate(overrides) {
  const settings = Object.assign({
    id: 'candidate_golden_gldn_unpriced',
    reason: 'The option book was one-sided for both legs at the last observation.'
  }, overrides || {});
  return wire.nonNull({
    id: settings.id,
    symbol: SYMBOL,
    strategy: 'PUT_CREDIT_SPREAD',
    displayName: 'Put credit spread',
    structureGroup: 'VERTICAL',
    label: 'SELL 250P / BUY 245P Aug 21',
    legs: goldenLegs().map(leg => Object.assign({}, leg, {
      quoteBid: null, quoteAsk: null, quoteAsOfEpochMs: null,
      quoteSource: null, quoteFreshness: null
    })),
    qty: QUANTITY,
    price: unavailablePackagePrice({ quantity: QUANTITY, reason: settings.reason }),
    maxProfitCents: null,
    maxLossCents: MAX_LOSS_CENTS,      // the width is mechanical and survives an absent price
    breakevens: [],
    pop: null,
    expectedValueCents: null,
    liquidityScore: 0,
    freshness: 'MISSING',
    warnings: ['No executable book priced this package.'],
    confidence: 0,
    whyConsidered: 'Mechanically eligible; economics could not be assessed.',
    bestUpside: null,
    biggestRisk: null,
    wouldInvalidate: null,
    beginnerExplanation: null,
    intent: 'INCOME',
    intents: ['INCOME'],
    assignmentProb: null,
    annualizedYieldPct: null,
    effectivePrice: null,
    intentNote: null,
    usesHeldShares: false,
    sharesNeeded: null,
    combinedMaxLossCents: null,
    evaluation: {
      available: false,
      unavailableReason: settings.reason,
      assessment: {
        mechanics: { eligible: true, reasons: [] },
        economics: {
          verdict: 'UNAVAILABLE',
          placement: 'MECHANICS_ONLY',
          label: 'Economics unavailable',
          summary: settings.reason,
          marketEvAfterCostsCents: null,
          realizedVolEvAfterCostsCents: null,
          estimatedRoundTripFeesCents: 600,
          marketEvPctOfRisk: null,
          realisticEvLowAfterCostsCents: null,
          realisticEvHighAfterCostsCents: null,
          realisticEvMaterialityCents: 0,
          marketEvRole: null,
          realisticEvBasis: null,
          observedEvidence: false,
          reasons: [settings.reason]
        },
        coherence: {
          verdict: 'UNAVAILABLE',
          directionAssessment: 'Direction assessment unavailable',
          durationAssessment: 'Duration assessment unavailable',
          reasons: ['Objective fit was not inferred while the decision assessment was unavailable.']
        },
        portfolioImpacts: { practice: null, real: null,
          notes: ['Portfolio impact was not inferred from incomplete assessment data.'] }
      }
    }
  });
}

/** The order dock (`ApiResponses.OrderDock`): one instruction beside the one price receipt. */
function goldenOrderDock(overrides) {
  const settings = Object.assign({ type: 'MARKET', limitNetCents: null, price: null },
    overrides || {});
  if (settings.type === 'MARKET' && settings.limitNetCents != null) {
    throw new Error('MARKET orders cannot carry a limitNetCents value');
  }
  if (settings.type === 'LIMIT' && settings.limitNetCents == null) {
    throw new Error('LIMIT orders require a signed limitNetCents value');
  }
  return {
    orderInstruction: wire.nonNull({
      type: settings.type,
      limitNetCents: settings.limitNetCents,
      timeInForce: 'DAY'
    }),
    price: settings.price || goldenPrice(settings.type === 'LIMIT'
      ? { restingLimitNetCents: settings.limitNetCents, valuationBasis: 'RESTING_LIMIT',
        executability: settings.limitNetCents <= GROSS_NET_CENTS ? 'IMMEDIATE' : 'RESTING' }
      : {})
  };
}

/**
 * Every number this package publishes, in one place, so a cross-surface test asserts one set of
 * facts rather than re-deriving them per surface.
 */
const FACTS = {
  symbol: SYMBOL,
  quantity: QUANTITY,
  expiration: wire.NEAR_EXPIRATION,
  anchorSpotCents: ANCHOR_SPOT_CENTS,
  previousCloseCents: PREV_CLOSE_CENTS,
  /* (250.00 / 247.50 − 1) × 100, the change the backend owns. */
  displayChangePct: 1.0101010101010102,
  optionNetPremiumCents: OPTION_NET_CENTS,
  stockCashFlowCents: STOCK_CASH_CENTS,
  grossPackageNetCents: GROSS_NET_CENTS,
  openingFeesCents: FEES_CENTS,
  afterFeeNetCents: AFTER_FEE_NET_CENTS,
  executableNetCents: GROSS_NET_CENTS,
  maxProfitCents: MAX_PROFIT_CENTS,
  maxLossCents: MAX_LOSS_CENTS,
  breakeven: BREAKEVEN,
  pop: POP,
  tailPop: TAIL_POP,
  fingerprint: FINGERPRINT,
  observedAtMs: wire.OBSERVED_AT_MS,
  observedAtIso: wire.OBSERVED_AT_ISO,
  storyMoves: STORY_MOVES.slice(),
  greeks: goldenGreeks(),
  /* The exact strings the Desk must print for these amounts, spelled once. */
  rendered: {
    optionNetPremium: wire.expectedSigned(OPTION_NET_CENTS),      // +$450
    stockCashFlow: wire.expectedSigned(STOCK_CASH_CENTS),         // $0
    grossPackageNet: wire.expectedSigned(GROSS_NET_CENTS),        // +$450
    openingFees: wire.expectedSigned(-FEES_CENTS),                // −$3
    afterFeeNet: wire.expectedSigned(AFTER_FEE_NET_CENTS),        // +$447
    maxProfit: wire.expectedMoney(MAX_PROFIT_CENTS),              // $450
    maxLoss: wire.expectedMoney(MAX_LOSS_CENTS),                  // $1,050
    unavailable: wire.UNAVAILABLE_TEXT                            // —
  }
};

/**
 * The held-trade payoff (`ApiResponses.PayoffPoint`) — the SAME curve, with its price as a plain
 * decimal STRING. The two payoff records differ in exactly this, and a fixture that emits one
 * shape for both hides a real divergence.
 */
function goldenHeldPayoff(options) {
  return goldenTerminalPayoff(options).points
    .map(point => ({ price: String(point.price), profitCents: point.profitCents }));
}

module.exports = {
  SYMBOL, QUANTITY, FACTS, CANDIDATE_ATTACHED_KEYS, STORY_MOVES,
  profitCentsAt, priceAt, goldenHeldPayoff,
  goldenLegs, goldenPrice, goldenTerminalPayoff, goldenScenarios, goldenGreeks,
  goldenJumpTail, goldenRiskProfile, goldenEvaluation, goldenCandidate, unpricedCandidate,
  goldenOrderDock
};
