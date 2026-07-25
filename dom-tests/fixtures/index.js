'use strict';

/**
 * Synthetic, deterministic, non-personal fixtures for the Desk browser suite — the complete state
 * matrix M0 and §16.3 call for, as builders other test lanes import.
 *
 *   const fixtures = require('./fixtures');
 *   const book = fixtures.book.bookDocuments({ positions: 4 });
 *   const lanes = fixtures.market.marketDocuments({ quote: 'stale', news: 'error' });
 *   const scan = fixtures.scout.scoutState('partial');
 *
 * Three properties hold across everything here, and each is load-bearing:
 *
 *   DETERMINISTIC — one frozen instant (`wire.OBSERVED_AT_MS`), no `Date.now()`, no randomness, no
 *   locale-dependent formatting. A suite whose fixtures move cannot distinguish a regression from
 *   a Tuesday.
 *
 *   NON-PERSONAL — invented tickers, invented account ids, invented headlines. Nothing here can be
 *   read as a real book if it lands in a CI artifact or a screenshot.
 *
 *   TRUE TO THE WIRE — every payload mirrors the Java record that produces it, including the
 *   mapper's NON_NULL inclusion and the places where a null is deliberately carried anyway.
 *   `fixtures.test.js` re-reads those records out of `src/main/java` on every run, so a contract
 *   change breaks the fixtures instead of silently invalidating the suite built on them.
 *
 * BUILDERS, NOT BLOBS. Every entry point takes overrides and returns fresh objects, so a test can
 * vary exactly one axis — twelve positions instead of four, a stale quote beside a ready chain,
 * a payoff curve narrower than the story grid — without copying a payload and editing it by hand.
 */

const wire = require('./wire');
const javaRecords = require('./java-records');
const packageMath = require('./package-math');
const legs = require('./legs');
const price = require('./price');
const golden = require('./golden');
const book = require('./book');
const ideas = require('./ideas');
const market = require('./market');
const scout = require('./scout');

/**
 * One complete world: a Book, a working-idea roster, the four market lanes and a Scout state,
 * every axis of the §16.3 matrix in one argument object. Composed of the same builders, so a test
 * that needs a single odd combination can drop to the individual builder for that lane.
 */
function desk(options) {
  const settings = Object.assign({
    positions: 1,
    shares: 0,
    workingIdeas: 5,
    mixedIdeas: false,
    quote: 'ready',
    history: 'ready',
    chain: 'ready',
    news: 'ready',
    newsCount: 5,
    scout: 'idle'
  }, options || {});
  const plans = ideas.workingIdeas(settings.workingIdeas, { mixed: settings.mixedIdeas });
  return {
    book: book.bookDocuments({ positions: settings.positions, shares: settings.shares }),
    plans: ideas.plansResponse(plans),
    planPortfolio: ideas.planPortfolio(plans),
    market: market.marketDocuments({
      quote: settings.quote,
      history: settings.history,
      chain: settings.chain,
      news: settings.news,
      newsCount: settings.newsCount
    }),
    scout: scout.scoutState(settings.scout),
    golden: {
      candidate: golden.goldenCandidate(),
      unpriced: golden.unpricedCandidate(),
      order: golden.goldenOrderDock(),
      facts: golden.FACTS
    }
  };
}

module.exports = {
  wire, javaRecords, packageMath, legs, price, golden, book, ideas, market, scout, desk
};
