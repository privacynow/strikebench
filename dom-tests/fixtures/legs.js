'use strict';

/**
 * `LegView` builders — the wire form of a package's legs (§16.3 leg-count matrix: 1, 2, 4 and 6
 * legs, on one expiration and on several).
 *
 * Prices are canonical decimal STRINGS, not numbers, because that is what
 * `Money.canonicalPrice` emits and what the custom-package store round-trips: `13.2`, never
 * `13.20` and never `13.2000`. The repo has already been bitten by the difference — a strike that
 * survived one hop as `255.3200` and the next as `255.32` produced two fingerprints for one
 * package. A fixture that emits numbers here would silently paper over that class of defect.
 */

const { NEAR_EXPIRATION, FAR_EXPIRATION, OBSERVED_AT_MS, oneOf } = require('./wire');

const SOURCE = 'FIXTURE_EXECUTABLE_BOOK';
const FRESHNESS = 'REALTIME';

/** `Money.canonicalPrice`: plain string, trailing zeros stripped. */
function canonicalPrice(value) {
  if (value === null || value === undefined) return null;
  const text = typeof value === 'string' ? value : String(value);
  if (!/^-?\d+(\.\d+)?$/.test(text)) throw new Error(`not a decimal price: ${text}`);
  return String(Number(text));
}

/**
 * One leg. Option legs carry a strike and an expiration; a stock leg carries neither, and saying
 * so with an explicit null is the contract (`LegView.of` nulls both for stock).
 *
 * The quote receipt fields are optional on the wire — `LegView`'s compatibility constructor omits
 * them for requests and custom packages — so `quoted: false` produces the request-shaped leg
 * rather than a leg wearing an invented book.
 */
function legView(overrides) {
  const options = Object.assign({
    action: 'SELL',
    type: 'PUT',
    strike: 250,
    expiration: NEAR_EXPIRATION,
    ratio: 1,
    entryPrice: 4,
    multiplier: 100,
    positionEffect: 'OPEN',
    quoted: true,
    quoteBid: null,
    quoteAsk: null
  }, overrides || {});
  const stock = String(options.type).toUpperCase() === 'STOCK';
  // Mirror LegView's compact constructor rather than discovering the same violation in a browser.
  if (!options.action) throw new Error('leg action required');
  if (!options.type) throw new Error('leg type required');
  if (!(options.ratio >= 1)) throw new Error('leg ratio must be >= 1');
  if (!(options.multiplier >= 1 && options.multiplier <= 10000)) {
    throw new Error('leg multiplier must be 1..10,000');
  }
  if (!['OPEN', 'CLOSE'].includes(String(options.positionEffect).toUpperCase())) {
    throw new Error('leg positionEffect must be OPEN or CLOSE');
  }
  const entry = Number(options.entryPrice);
  // A two-sided book straddling the entry price, so a leg's quote receipt and its fill price are
  // consistent with each other instead of being two unrelated invented numbers.
  const bid = options.quoteBid === null || options.quoteBid === undefined
    ? entry - 0.05 : Number(options.quoteBid);
  const ask = options.quoteAsk === null || options.quoteAsk === undefined
    ? entry + 0.05 : Number(options.quoteAsk);
  return {
    action: String(options.action).toUpperCase(),
    type: String(options.type).toUpperCase(),
    strike: stock ? null : canonicalPrice(options.strike),
    expiration: stock ? null : options.expiration,
    ratio: options.ratio,
    entryPrice: canonicalPrice(options.entryPrice),
    multiplier: options.multiplier,
    positionEffect: String(options.positionEffect).toUpperCase(),
    quoteBid: options.quoted && !stock ? canonicalPrice(bid.toFixed(2)) : null,
    quoteAsk: options.quoted && !stock ? canonicalPrice(ask.toFixed(2)) : null,
    quoteAsOfEpochMs: options.quoted ? OBSERVED_AT_MS : null,
    quoteSource: options.quoted ? SOURCE : null,
    quoteFreshness: options.quoted ? FRESHNESS : null
  };
}

/**
 * The leg-count matrix. Each shape is a structure that actually exists in the catalog, priced so
 * the package's net premium is a real consequence of its legs:
 *
 *   1 — cash-secured put
 *   2 — put credit spread (this is the golden package's structure)
 *   4 — iron condor
 *   6 — iron condor plus an outer put wing pair (a "double" defended structure)
 *
 * `expirations: 'multiple'` moves the later half of the package to the far expiration, which is
 * what makes a package mixed-expiry — and mixed expiry is exactly the state where the terminal
 * payoff receipt is explicitly unavailable (`RiskProfile.TerminalPayoff`), so the two axes have to
 * be varied together rather than independently.
 */
function legs(count, options) {
  const settings = Object.assign({ expirations: 'same' }, options || {});
  oneOf('legs(count)', count, [1, 2, 4, 6]);
  oneOf('legs(expirations)', settings.expirations, ['same', 'multiple']);
  if (count === 1 && settings.expirations === 'multiple') {
    throw new Error('a one-leg package cannot span two expirations');
  }
  const shapes = {
    1: [
      { action: 'SELL', type: 'PUT', strike: 250, entryPrice: 4 }
    ],
    2: [
      { action: 'SELL', type: 'PUT', strike: 250, entryPrice: 4 },
      { action: 'BUY', type: 'PUT', strike: 245, entryPrice: 2.5 }
    ],
    4: [
      { action: 'BUY', type: 'PUT', strike: 240, entryPrice: 1.35 },
      { action: 'SELL', type: 'PUT', strike: 245, entryPrice: 2.5 },
      { action: 'SELL', type: 'CALL', strike: 265, entryPrice: 2.2 },
      { action: 'BUY', type: 'CALL', strike: 270, entryPrice: 1.15 }
    ],
    6: [
      { action: 'BUY', type: 'PUT', strike: 235, entryPrice: 0.8 },
      { action: 'SELL', type: 'PUT', strike: 240, entryPrice: 1.35 },
      { action: 'SELL', type: 'PUT', strike: 245, entryPrice: 2.5 },
      { action: 'BUY', type: 'PUT', strike: 250, entryPrice: 4 },
      { action: 'SELL', type: 'CALL', strike: 265, entryPrice: 2.2 },
      { action: 'BUY', type: 'CALL', strike: 270, entryPrice: 1.15 }
    ]
  };
  const shape = shapes[count];
  const split = settings.expirations === 'multiple' ? Math.ceil(count / 2) : count;
  return shape.map((leg, index) => legView(Object.assign({}, leg, {
    expiration: index < split ? NEAR_EXPIRATION : FAR_EXPIRATION,
    quoted: settings.quoted !== false
  })));
}

/** The expirations a package actually spans, in ascending order. Stock legs contribute none. */
function expirationsOf(legList) {
  return Array.from(new Set((legList || [])
    .map(leg => leg.expiration).filter(Boolean))).sort();
}

/** A share leg, for packages that move stock cash as well as premium (a buy-write, a collar). */
function stockLeg(overrides) {
  return legView(Object.assign({ type: 'STOCK', action: 'BUY', ratio: 100, multiplier: 1,
    strike: null, expiration: null, entryPrice: 250, quoted: false }, overrides || {}));
}

module.exports = { legView, legs, stockLeg, expirationsOf, canonicalPrice };
