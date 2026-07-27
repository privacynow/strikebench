'use strict';

/**
 * `PackagePriceReceipt` builders — THE canonical package-price receipt (program §7.2), the one
 * object a candidate row, an order dock, a review screen and a held close all carry.
 *
 * The builders enforce the record's own compact-constructor invariants. That is the whole value:
 * a fixture is only useful if it cannot express a payload the server would refuse to construct.
 * Without the checks it is trivially easy to write a receipt whose `afterFeeNetCents` does not
 * reconcile, and then to "prove" a screen renders it — a green test for a payload that can never
 * arrive.
 *
 * Sign convention, everywhere: money received is positive, money paid is negative.
 */

const { OBSERVED_AT_MS, always, oneOf } = require('./wire');

const VALUATION_BASES = ['EXECUTABLE_BOOK', 'RESTING_LIMIT', 'RECORDED_FILL',
  'MID_MARKET', 'MODELED', 'UNAVAILABLE'];
const EXECUTABILITY = ['IMMEDIATE', 'RESTING', 'UNAVAILABLE'];
const FEE_SIDES = ['OPENING', 'CLOSING'];

/**
 * A priced receipt. Both SIDES of the package are supplied — the option-only net and the stock
 * cash flow — because the record checks that the three amounts reconcile as three independently
 * measured numbers. Deriving one from the other two here would make the identity true by
 * construction and the check worthless, which is the exact mistake `PackagePriceReceipt.of`
 * documents itself as having made.
 */
function packagePrice(overrides) {
  const o = Object.assign({
    quantity: 1,
    optionNetPremiumCents: 45000,
    stockCashFlowCents: 0,
    openingFeesCents: 300,
    estimatedRoundTripFeesCents: 600,
    executableNetCents: 45000,
    restingLimitNetCents: null,
    valuationBasis: 'EXECUTABLE_BOOK',
    executability: 'IMMEDIATE',
    source: 'FIXTURE_EXECUTABLE_BOOK',
    freshness: 'REALTIME',
    observedAt: OBSERVED_AT_MS,
    fingerprint: null,
    feeSide: 'OPENING'
  }, overrides || {});

  oneOf('valuationBasis', o.valuationBasis, VALUATION_BASES);
  oneOf('executability', o.executability, EXECUTABILITY);
  oneOf('feeSide', o.feeSide, FEE_SIDES);
  if (o.valuationBasis === 'UNAVAILABLE') {
    throw new Error('use unavailablePackagePrice() for an unpriced package');
  }
  if (!(o.quantity >= 1)) throw new Error('package price receipt requires quantity >= 1');
  if (o.optionNetPremiumCents == null || o.stockCashFlowCents == null) {
    throw new Error('a priced package must state both its option net premium and its stock cash flow');
  }
  if (o.openingFeesCents != null && o.openingFeesCents < 0) throw new Error('fees cannot be negative');
  if (o.estimatedRoundTripFeesCents != null && o.estimatedRoundTripFeesCents < 0) {
    throw new Error('round-trip fees cannot be negative');
  }
  if ((o.openingFeesCents == null) !== (o.estimatedRoundTripFeesCents == null)) {
    throw new Error('an opening price must state both opening and estimated round-trip fees');
  }
  if (o.estimatedRoundTripFeesCents != null
      && o.estimatedRoundTripFeesCents < o.openingFeesCents) {
    throw new Error('round-trip fees cannot be less than opening fees');
  }

  const gross = o.optionNetPremiumCents + o.stockCashFlowCents;
  const afterFee = o.openingFeesCents == null ? null : gross - o.openingFeesCents;
  return always({
    quantity: o.quantity,
    optionNetPremiumCents: o.optionNetPremiumCents,
    stockCashFlowCents: o.stockCashFlowCents,
    grossPackageNetCents: gross,
    openingFeesCents: o.openingFeesCents,
    estimatedRoundTripFeesCents: o.estimatedRoundTripFeesCents,
    afterFeeNetCents: afterFee,
    executableNetCents: o.executableNetCents,
    restingLimitNetCents: o.restingLimitNetCents,
    valuationBasis: o.valuationBasis,
    executability: o.executability,
    source: o.source,
    freshness: o.freshness,
    observedAt: o.observedAt,
    fingerprint: o.fingerprint,
    feeSide: o.feeSide,
    unavailableReason: null
  });
}

/**
 * §3.2's fallthrough guard: no price at all, with the reason attached and every amount an explicit
 * null. Quantity survives because the reader still needs to know what size was being priced.
 *
 * This is the state §16.3 names first — "`unavailable` never renders `+$0`" — so the builder
 * refuses to produce a reasonless one, exactly as the record does.
 */
function unavailablePackagePrice(overrides) {
  const o = Object.assign({
    quantity: 1,
    feeSide: 'OPENING',
    reason: 'No executable book priced this package.'
  }, overrides || {});
  oneOf('feeSide', o.feeSide, FEE_SIDES);
  if (!o.reason || !String(o.reason).trim()) {
    throw new Error('an unpriced package must state why it is unavailable');
  }
  return always({
    quantity: Math.max(1, o.quantity),
    optionNetPremiumCents: null,
    stockCashFlowCents: null,
    grossPackageNetCents: null,
    openingFeesCents: null,
    estimatedRoundTripFeesCents: null,
    afterFeeNetCents: null,
    executableNetCents: null,
    restingLimitNetCents: null,
    valuationBasis: 'UNAVAILABLE',
    executability: 'UNAVAILABLE',
    source: null,
    freshness: null,
    observedAt: null,
    fingerprint: null,
    feeSide: o.feeSide,
    unavailableReason: String(o.reason)
  });
}

/**
 * A package priced at exactly even money, with exactly zero fees: every amount is a real, measured
 * 0. It exists to separate two states the Desk currently spells the same way — "the engine priced
 * this at zero" and "the engine could not price this" — which is the substance of §16.3's exact
 * zero requirement.
 */
function zeroPackagePrice(overrides) {
  return packagePrice(Object.assign({
    optionNetPremiumCents: 0,
    stockCashFlowCents: 0,
    openingFeesCents: 0,
    estimatedRoundTripFeesCents: 0,
    executableNetCents: 0,
    valuationBasis: 'EXECUTABLE_BOOK',
    source: 'FIXTURE_EVEN_MONEY_ROLL',
    fingerprint: 'e0'.repeat(32)
  }, overrides || {}));
}

/**
 * A one-sided book: the package has a stated MODELED price but no executable net, so
 * `executableNetCents` stays null rather than being backfilled from the recorded net. A field
 * named "executable" carrying a non-executable number is the failure this null prevents.
 */
function oneSidedPackagePrice(overrides) {
  return packagePrice(Object.assign({
    valuationBasis: 'MODELED',
    executability: 'UNAVAILABLE',
    executableNetCents: null,
    source: 'FIXTURE_LAST_TRADE',
    freshness: 'STALE',
    fingerprint: '15'.repeat(32)
  }, overrides || {}));
}

/**
 * A package that moves share cash as well as premium, so the option-only net and the whole-package
 * net are genuinely different numbers for one trade — the case §3.3 requires a screen to explain
 * rather than leave the reader to reconcile. A buy-write: a small call credit against a large
 * share debit.
 */
function stockPackagePrice(overrides) {
  return packagePrice(Object.assign({
    optionNetPremiumCents: 45000,
    stockCashFlowCents: -7500000,
    openingFeesCents: 300,
    executableNetCents: -7455000,
    source: 'FIXTURE_EXECUTABLE_BOOK',
    fingerprint: 'b7'.repeat(32)
  }, overrides || {}));
}

/** `PackagePriceReceipt.priced()`. */
function priced(price) {
  return !!price && price.valuationBasis !== 'UNAVAILABLE';
}

/**
 * `ExecutionDecision` is the server's final answer for an exact instruction. Keeping this beside
 * the price receipt prevents visual fixtures from inventing browser-side eligibility policy.
 */
function executionDecision(overrides) {
  const o = Object.assign({
    reviewAllowed: true,
    confirmAllowed: true,
    reasons: []
  }, overrides || {});
  if (o.confirmAllowed && !o.reviewAllowed) {
    throw new Error('confirmation requires a reviewable instruction');
  }
  return always({
    reviewAllowed: !!o.reviewAllowed,
    confirmAllowed: !!o.confirmAllowed,
    reasons: Array.from(new Set((o.reasons || []).filter(Boolean).map(String)))
  });
}

module.exports = {
  packagePrice, unavailablePackagePrice, zeroPackagePrice, oneSidedPackagePrice,
  stockPackagePrice, priced, executionDecision,
  VALUATION_BASES, EXECUTABILITY, FEE_SIDES
};
