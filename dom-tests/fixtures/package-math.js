'use strict';

/**
 * The ONE arithmetic owner for every fixture package.
 *
 * Program §3.1 forbids the browser originating financial facts; the engine does. A test fixture is
 * the one place that rule cannot apply, because the fixture IS standing in for the engine. What
 * still applies is §3.8 — one component per concept. Every fixture package's net premium, terminal
 * payoff, max profit and max loss come from this file, so a roster of twelve positions cannot
 * contain one whose max loss disagrees with its own legs, and a cross-surface identity test is not
 * quietly asserting against a fixture that never added up.
 *
 * Everything is in CENTS and follows the ledger's sign convention: money received is positive.
 */

/**
 * A leg's signed size in DELIVERABLE UNITS (shares, for a standard 100-multiplier option). Long is
 * positive. Ratio, quantity and multiplier are folded in here so no caller multiplies them again.
 */
function legUnits(leg, quantity) {
  const direction = String(leg.action).toUpperCase() === 'BUY' ? 1 : -1;
  return direction * Number(leg.ratio) * Number(leg.multiplier) * Number(quantity);
}

function priceCents(decimalString) {
  if (decimalString === null || decimalString === undefined) return 0;
  return Math.round(Number(decimalString) * 100);
}

function isStock(leg) {
  return String(leg.type).toUpperCase() === 'STOCK';
}

/** Cash the package moves on open: buys pay, sells receive. */
function entryCashCents(legs, quantity) {
  return legs.reduce((total, leg) =>
    total - legUnits(leg, quantity) * priceCents(leg.entryPrice), 0);
}

/** The OPTION legs' share of that cash — `ProtocolEvaluator.optionEntryBasisCents`' quantity. */
function optionNetPremiumCents(legs, quantity) {
  return entryCashCents(legs.filter(leg => !isStock(leg)), quantity);
}

/** The STOCK legs' share — exactly 0 for an option-only package, never an omitted field. */
function stockCashFlowCents(legs, quantity) {
  return entryCashCents(legs.filter(isStock), quantity);
}

/** What one leg is worth per deliverable unit at an expiration price, in cents. */
function intrinsicCents(leg, priceDollars) {
  const spot = Math.round(Number(priceDollars) * 100);
  if (isStock(leg)) return spot;
  const strike = priceCents(leg.strike);
  return String(leg.type).toUpperCase() === 'CALL'
    ? Math.max(0, spot - strike)
    : Math.max(0, strike - spot);
}

/**
 * Terminal P/L of the whole package at an underlying price, in cents.
 *
 * Single-expiration only. A mixed-expiry package has no terminal price — which is precisely why
 * `RiskProfile.TerminalPayoff` reports itself unavailable for one rather than drawing a curve.
 */
function terminalPnlCents(legs, quantity, priceDollars) {
  const expirations = new Set(legs.filter(leg => !isStock(leg)).map(leg => leg.expiration));
  if (expirations.size > 1) {
    throw new Error('a mixed-expiration package has no single terminal payoff; it is unavailable');
  }
  return legs.reduce((total, leg) =>
    total + legUnits(leg, quantity) * intrinsicCents(leg, priceDollars),
  entryCashCents(legs, quantity));
}

/**
 * The prices where the payoff bends: zero, every strike, and a price far enough above the highest
 * strike to expose an uncapped upside. A piecewise-linear payoff attains its extrema at a knot, so
 * scanning these is exact rather than a sampled approximation.
 */
function knotPrices(legs) {
  const strikes = legs.filter(leg => !isStock(leg)).map(leg => Number(leg.strike));
  const highest = strikes.length ? Math.max(...strikes) : 100;
  return [0].concat(strikes.slice().sort((a, b) => a - b)).concat([highest * 3]);
}

/**
 * Max profit and max loss, both derived from the same payoff.
 *
 * `maxProfitCents` is null when the structure is uncapped — the payoff is still climbing at the
 * far knot — because "uncapped" is a different fact from "a large number", and the record says so
 * with a null. `maxLossCents` is reported as a POSITIVE magnitude, matching `Candidate.maxLossCents`
 * and `TradeView.maxLossCents`.
 */
function extremes(legs, quantity) {
  const knots = knotPrices(legs);
  const values = knots.map(price => terminalPnlCents(legs, quantity, price));
  const highest = Math.max(...values);
  const lowest = Math.min(...values);
  // The payoff is still climbing at the far knot, so no finite maximum exists (a naked short put
  // has no cap on the way up either — its profit IS capped, at the credit; a long call is not).
  const profitUnbounded = values[values.length - 1] > values[values.length - 2];
  return {
    maxProfitCents: profitUnbounded ? null : highest,
    maxLossCents: Math.abs(lowest),   // reported as a positive magnitude, as both records do
    profitUnbounded: profitUnbounded
  };
}

/**
 * The underlying prices at which the package breaks even, as canonical decimal strings — the form
 * `Candidate.breakevens` and `TradeView.breakevens` carry (never numbers).
 */
function breakevens(legs, quantity) {
  const knots = knotPrices(legs);
  const found = [];
  for (let index = 1; index < knots.length; index += 1) {
    const low = knots[index - 1];
    const high = knots[index];
    const lowValue = terminalPnlCents(legs, quantity, low);
    const highValue = terminalPnlCents(legs, quantity, high);
    if (lowValue === 0) found.push(low);
    if ((lowValue < 0 && highValue > 0) || (lowValue > 0 && highValue < 0)) {
      const crossing = low + (high - low) * (0 - lowValue) / (highValue - lowValue);
      found.push(Math.round(crossing * 100) / 100);
    }
  }
  const lastKnot = knots[knots.length - 1];
  if (terminalPnlCents(legs, quantity, lastKnot) === 0) found.push(lastKnot);
  return Array.from(new Set(found)).sort((a, b) => a - b).map(price => price.toFixed(2));
}

module.exports = {
  legUnits, priceCents, isStock, entryCashCents, optionNetPremiumCents, stockCashFlowCents,
  intrinsicCents, terminalPnlCents, knotPrices, extremes, breakevens
};
