'use strict';

/**
 * Wire policy and the fixed clock every Desk fixture is struck from.
 *
 * Two rules travel together here because they are the two ways a fixture lies:
 *
 *  1. SHAPE. `Json.MAPPER` sets `NON_NULL` inclusion, so a null field is ABSENT from the payload
 *     the browser receives — except on records annotated `@JsonInclude(ALWAYS)`, where an explicit
 *     null is the point (`PackagePriceReceipt` carries its unknowns as stated nulls beside a
 *     reason, program §3.2). A fixture that emits `null` where the server omits the key, or omits
 *     a key the server always sends, is testing a payload the product never receives.
 *
 *  2. TIME. Every timestamp in these fixtures derives from one frozen instant. A fixture with a
 *     drifting clock produces a suite that passes on Tuesday.
 */

/**
 * The one instant these fixtures are observed at: 2026-07-24T17:26:00Z.
 *
 * This exact epoch is the audit's own evidence (§5.2 regression 3): the order controls render
 * `1784913960000` verbatim because `observedAt` is epoch milliseconds and the renderer treats it
 * as an ISO string. Keeping the audit's number means a test asserting "no raw epoch reaches the
 * screen" is asserting against the digits that were actually on screen.
 */
const OBSERVED_AT_MS = 1784913960000;
const OBSERVED_AT_ISO = '2026-07-24T17:26:00.000Z';
/** The literal digit string that must never appear in rendered text. */
const OBSERVED_AT_RAW_TEXT = '1784913960000';

/** Session dates around the frozen instant. 2026-07-24 is a Friday; 2026-07-27 the next Monday. */
const TODAY = '2026-07-24';
const PRIOR_SESSION = '2026-07-23';
const NEAR_EXPIRATION = '2026-08-21';
const FAR_EXPIRATION = '2026-09-18';

/**
 * Synthetic tickers. Nothing in these fixtures refers to a real holder, account or portfolio; the
 * symbols are invented so no capture, screenshot or CI artifact can be read as someone's book.
 */
const GOLDEN_SYMBOL = 'GLDN';
const ROSTER_SYMBOLS = ['ZAA', 'ZAB', 'ZAC', 'ZAD', 'ZAE', 'ZAF',
  'ZAG', 'ZAH', 'ZAI', 'ZAJ', 'ZAK', 'ZAL'];

const ACCOUNT_ID = 'account_fixture_desk';
const OWNER_ID = 'owner_fixture_desk';

/**
 * Marks an object as carrying its own inclusion policy, so a `nonNull` sweep over a payload that
 * CONTAINS one does not strip the nulls that object exists to state. Non-enumerable, so it never
 * reaches `Object.keys`, `JSON.stringify` or an assertion.
 */
const INCLUSION = Symbol('jsonInclude');

/**
 * The mapper's default inclusion: drop null-valued keys, recursively, exactly as the server does.
 * `undefined` is dropped too — a builder that forgot a field must not smuggle it through as a key.
 *
 * A nested `@JsonInclude(ALWAYS)` object is copied through untouched. Without that carve-out a
 * candidate's price receipt would lose exactly the nulls that say "this amount is unknown", and
 * the fixture would publish the ambiguity §7.2 was written to end.
 */
function nonNull(object) {
  if (Array.isArray(object)) return object.map(nonNull);
  if (object === null || typeof object !== 'object') return object;
  if (object[INCLUSION] === 'ALWAYS') return always(object);
  const out = {};
  for (const key of Object.keys(object)) {
    const value = object[key];
    if (value === null || value === undefined) continue;
    out[key] = typeof value === 'object' ? nonNull(value) : value;
  }
  return out;
}

/**
 * `@JsonInclude(ALWAYS)`: every declared key ships, nulls included. Used only by
 * PackagePriceReceipt, where "this price is unknown" is a fact the payload must carry — a missing
 * key and a null key look identical in a browser, which is how "which number is real?" came back.
 */
function always(object) {
  const copy = Object.assign({}, object);
  Object.defineProperty(copy, INCLUSION, { value: 'ALWAYS', enumerable: false });
  return copy;
}

/** `@JsonInclude(NON_EMPTY)`: an empty list is absent (TradeView.scenarios). */
function nonEmpty(list) {
  return Array.isArray(list) && list.length ? list : undefined;
}

/** Assert a builder was handed a state the matrix actually defines, and say so when it was not. */
function oneOf(name, value, allowed) {
  if (!allowed.includes(value)) {
    throw new Error(`${name} must be one of ${allowed.join(' | ')}; received ${JSON.stringify(value)}`);
  }
  return value;
}

const MINUS = '−';   // U+2212, the sign the Desk renders; not a hyphen

/** Thousands grouping without ICU, so a CI box with a trimmed locale still asserts the same text. */
function group(digits) {
  return String(digits).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** Exact integer-cent input: fixture expectations reject the same malformed fact as the Desk. */
function integerCents(value) {
  return typeof value === 'number' && Number.isSafeInteger(value) ? value : null;
}

function centBody(cents, alwaysFraction) {
  const whole = Math.floor(Math.abs(cents) / 100);
  const fraction = Math.abs(cents) % 100;
  return group(whole) + (alwaysFraction || fraction ? `.${String(fraction).padStart(2, '0')}` : '');
}

/**
 * An unsigned label such as Max loss uses a magnitude; a general balance preserves a negative
 * sign. Both preserve every cent the receipt states.
 */
function expectedMoney(cents) {
  const value = integerCents(cents);
  return value == null ? UNAVAILABLE_TEXT
    : (value < 0 ? MINUS + '$' : '$') + centBody(value, false);
}

/**
 * The signed form. An exact zero is rendered WITHOUT a sign on purpose: `+$0` is the string the
 * audit found standing in for a value the product did not have (§5.2 regression 1), so a real
 * zero that also renders `+$0` is indistinguishable from a fabricated one. Whichever way M1
 * resolves the sign, a zero and an unavailable must not share a spelling.
 */
function expectedSigned(cents) {
  const value = integerCents(cents);
  if (value == null) return UNAVAILABLE_TEXT;
  if (value === 0) return '$0';
  return (value > 0 ? '+$' : MINUS + '$') + centBody(value, false);
}

function expectedLoss(cents) {
  const value = integerCents(cents);
  return value == null ? UNAVAILABLE_TEXT : '$' + centBody(value, false);
}

function expectedPrice(cents) {
  const value = integerCents(cents);
  return value == null ? UNAVAILABLE_TEXT
    : (value < 0 ? MINUS + '$' : '$') + centBody(value, true);
}

function expectedFee(cents) {
  const value = integerCents(cents);
  if (value == null) return UNAVAILABLE_TEXT;
  return value === 0 ? '$0.00' : MINUS + '$' + centBody(value, true);
}

/** What an absent financial fact reads as; never a substituted number (§3.2). */
const UNAVAILABLE_TEXT = '—';   // em dash, `authMoney(null)`

module.exports = {
  INCLUSION,
  OBSERVED_AT_MS, OBSERVED_AT_ISO, OBSERVED_AT_RAW_TEXT,
  TODAY, PRIOR_SESSION, NEAR_EXPIRATION, FAR_EXPIRATION,
  GOLDEN_SYMBOL, ROSTER_SYMBOLS, ACCOUNT_ID, OWNER_ID,
  nonNull, always, nonEmpty, oneOf,
  MINUS, group, integerCents, expectedMoney, expectedSigned, expectedLoss, expectedPrice,
  expectedFee, UNAVAILABLE_TEXT
};
