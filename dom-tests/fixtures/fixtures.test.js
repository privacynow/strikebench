'use strict';

/**
 * The fixtures' own self-check.
 *
 * Two questions, asked of every builder:
 *
 *   1. Does it emit the field set the Java record DECLARES? The declaration is read out of
 *      `src/main/java` on every run, so this fails the moment a record gains, loses or renames a
 *      component — which is the only way to keep a browser suite from confidently asserting
 *      against a payload the server stopped sending. This repo has already shipped a fixture that
 *      asserted a field on the wrong record and stayed green for it.
 *
 *   2. Does it add up? A fixture whose max loss disagrees with its own legs, or whose Book totals
 *      disagree with the roster they summarize, will pass any test written against it and prove
 *      nothing. The arithmetic is re-derived here from the closed form, independently of the
 *      shared package-math module the builders use.
 *
 * Run: `node --test dom-tests/fixtures/fixtures.test.js`
 */

const { test } = require('node:test');
const assert = require('node:assert/strict');

const fixtures = require('./index');
const { recordComponents, mapPutKeys } = require('./java-records');
const scenarioFixtures = require('./scenarios');

const { wire, legs, price, golden, book, ideas, market, scout, newIdea, packageMath } = fixtures;

// ---------------------------------------------------------------------------------------------
// Shape: every emitted key is a declared component of the record it claims to be
// ---------------------------------------------------------------------------------------------

/**
 * A payload must not carry a key its record does not declare, and — for every component whose
 * value the fixture actually knows — the key must be present.
 *
 * Absence is checked in the direction the mapper allows: `Json.MAPPER` uses NON_NULL inclusion, so
 * a key may legitimately be missing when the server's value is null. `exact: true` demands the
 * full component set, for the one record annotated `@JsonInclude(ALWAYS)`.
 */
function assertShape(payload, file, recordName, options) {
  const settings = Object.assign({ exact: false, extraKeys: [] }, options || {});
  const declared = recordComponents(file, recordName);
  const allowed = new Set(declared.concat(settings.extraKeys));
  const emitted = Object.keys(payload);
  const invented = emitted.filter(key => !allowed.has(key));
  assert.deepEqual(invented, [],
    `${recordName} fixture emits keys the record does not declare: ${invented.join(', ')}`);
  if (settings.exact) {
    const absent = declared.filter(key => !Object.prototype.hasOwnProperty.call(payload, key));
    assert.deepEqual(absent, [],
      `${recordName} is @JsonInclude(ALWAYS); every component must ship, missing: ${absent.join(', ')}`);
  }
  return declared;
}

/**
 * Under NON_NULL inclusion an unknown value is an ABSENT key, not a null one. Asserting `=== null`
 * would pass against a fixture that emits nulls the server never sends, so absence is asserted as
 * absence.
 */
function assertAbsent(payload, key, why) {
  assert.ok(!Object.prototype.hasOwnProperty.call(payload, key),
    `${key} must be absent under NON_NULL inclusion${why ? ` — ${why}` : ''}`);
}

test('PackagePriceReceipt fixtures carry every declared component, nulls included', () => {
  const declared = assertShape(golden.goldenPrice(),
    'paper/PackagePriceReceipt.java', 'PackagePriceReceipt', { exact: true });
  assert.equal(declared.length, 17,
    'the package receipt includes the captured round-trip fee beside feeSide and reason');
  // The unpriced receipt is the state §3.2 exists for: same shape, every amount an explicit null.
  const unavailable = price.unavailablePackagePrice({ reason: 'no two-sided book' });
  assertShape(unavailable, 'paper/PackagePriceReceipt.java', 'PackagePriceReceipt', { exact: true });
  for (const key of ['optionNetPremiumCents', 'stockCashFlowCents', 'grossPackageNetCents',
    'openingFeesCents', 'estimatedRoundTripFeesCents', 'afterFeeNetCents',
    'executableNetCents']) {
    assert.equal(unavailable[key], null, `${key} must be an explicit null, never a substituted 0`);
  }
  assert.equal(unavailable.valuationBasis, 'UNAVAILABLE');
  assert.match(unavailable.unavailableReason, /two-sided/);
});

test('LegView fixtures match the leg wire contract at every leg count', () => {
  for (const count of [1, 2, 4, 6]) {
    for (const spanning of count === 1 ? ['same'] : ['same', 'multiple']) {
      const package_ = legs.legs(count, { expirations: spanning });
      assert.equal(package_.length, count);
      package_.forEach(leg => assertShape(leg, 'recommend/LegView.java', 'LegView'));
      const expirations = legs.expirationsOf(package_);
      assert.equal(expirations.length, spanning === 'multiple' ? 2 : 1,
        `${count} legs / ${spanning} expirations`);
    }
  }
  assert.throws(() => legs.legs(1, { expirations: 'multiple' }), /cannot span two expirations/);
  assert.throws(() => legs.legs(3), /must be one of/);
});

test('leg prices are canonical decimal strings, not numbers', () => {
  const leg = legs.legView({ entryPrice: '2.50', strike: '250.00' });
  assert.equal(leg.entryPrice, '2.5', 'Money.canonicalPrice strips trailing zeros');
  assert.equal(leg.strike, '250');
  assert.equal(typeof leg.strike, 'string');
  const stock = legs.stockLeg();
  assert.equal(stock.strike, null, 'a stock leg has no strike');
  assert.equal(stock.expiration, null, 'a stock leg has no expiration');
});

test('TradeView fixtures match the held-line wire contract at every roster size', () => {
  for (const count of [0, 1, 4, 12]) {
    const rows = book.positions(count);
    assert.equal(rows.length, count);
    rows.forEach(trade => {
      assertShape(trade, 'api/TradeView.java', 'TradeView');
      trade.legs.forEach(leg => assertShape(leg, 'recommend/LegView.java', 'LegView'));
      if (trade.greeks) {
        assertShape(trade.greeks, 'model/GreeksView.java', 'GreeksView');
      }
      if (trade.terminalPayoff) {
        assertShape(trade.terminalPayoff, 'eval/RiskProfile.java', 'TerminalPayoff');
      }
      (trade.scenarios || []).forEach(row =>
        assertShape(row, 'eval/RiskProfile.java', 'Scenario'));
    });
  }
});

test('Book action projections match the canonical read-only lifecycle contract', () => {
  const trade = book.goldenHeldTrade();
  const projection = book.bookActionProjectionSet(trade);
  assertShape(projection, 'paper/BookActionProjectionService.java', 'ProjectionSet');
  assert.equal(projection.actions.length, trade.qty + 3,
    'hold, every close quantity, assignment, and roll are explicit alternatives');
  projection.actions.forEach(action => {
    assertShape(action, 'paper/BookActionProjectionService.java', 'ActionProjection');
    assertShape(action.basisEffect, 'paper/BookActionProjectionService.java', 'BasisEffect');
    assertShape(action.executableCost, 'paper/BookActionProjectionService.java', 'ExecutableCost');
    action.steps.forEach(step =>
      assertShape(step, 'paper/BookActionProjectionService.java', 'ActionStep'));
    if (action.snapshot) {
      assertShape(action.snapshot, 'paper/BookActionProjectionService.java', 'BookSnapshot');
      assertShape(action.snapshot.cash, 'position/AuthorityFacts.java', 'SignedMoneyFact');
      assertShape(action.snapshot.encumbrance, 'position/AuthorityFacts.java', 'MoneyFact');
      assertShape(action.snapshot.shortPutObligation,
        'position/AuthorityFacts.java', 'MoneyFact');
    }
  });
  const hold = projection.actions.find(action => action.action === 'HOLD');
  assert.equal(hold.snapshot.cash.cents, 9752000);
  assert.equal(hold.executableCost.signedNetCashCents, 0,
    'a deliberate hold remains an explicit no-transaction receipt');
  const close = projection.actions.find(action => action.action === 'CLOSE_ALL');
  assert.ok(close.executableCost.signedNetCashCents < 0,
    'a buyback remains a signed cash outflow, never an unsigned cost or zero');
  const assignment = projection.actions.find(action => action.action === 'ASSIGNMENT');
  assert.equal(assignment.available, true);
  assert.equal(assignment.snapshot.sharesBySymbol[trade.symbol], trade.qty * 100,
    'assignment states the exact projected share inventory');

  const callTrade = book.tradeView({
    qty: 2,
    legs: [
      legs.legView({ action: 'BUY', type: 'CALL', strike: 250, entryPrice: 4 }),
      legs.legView({ action: 'SELL', type: 'CALL', strike: 260, entryPrice: 2.5 })
    ]
  });
  const callAway = book.bookActionProjectionSet(callTrade).actions
    .find(action => action.action === 'CALL_AWAY');
  assert.equal(callAway.available, true);
  assert.equal(callAway.snapshot.sharesBySymbol[callTrade.symbol], -200,
    'call-away states the exact projected share delivery');
  const closeK = book.bookActionProjectionSet(book.tradeView({ qty: 3 })).actions
    .find(action => action.action === 'CLOSE_K');
  assert.equal(closeK.quantityAffected, 2);
  assert.equal(closeK.quantityRemaining, 1,
    'the fixture carries the existing service’s explicit close-K alternative');
  const roll = projection.actions.find(action => action.action === 'ROLL');
  assert.equal(roll.available, false);
  assert.match(roll.unavailableReason, /replacement contracts/i);
});

test('Plan.View fixtures match the plan wire contract at every working-idea count', () => {
  for (const count of [0, 5, 20]) {
    const rows = ideas.workingIdeas(count);
    assert.equal(rows.length, count);
    assert.equal(rows.filter(ideas.isWorking).length, count,
      'every row in a working roster must satisfy the Desk\'s own working filter');
    rows.forEach(plan => {
      assertShape(plan, 'plan/Plan.java', 'View');
      assertShape(plan.context, 'plan/Plan.java', 'ContextRevision');
    });
  }
  const mixed = ideas.workingIdeas(5, { mixed: true });
  assert.equal(mixed.length, 7);
  assert.equal(mixed.filter(ideas.isWorking).length, 5,
    'a decided or position-open Plan is not a working idea');
});

test('market lane fixtures match their wire contracts in every state', () => {
  for (const state of market.LANE_STATES) {
    const research = market.researchDetail(state);
    if (research.status === 200) {
      assertShape(research.body, 'api/ApiResponses.java', 'ResearchDetail');
      if (research.body.quote) assertShape(research.body.quote, 'api/ApiResponses.java', 'QuoteView');
      if (research.body.regime) assertShape(research.body.regime, 'api/ApiResponses.java', 'Regime');
      if (research.body.earningsEstimate) {
        assertShape(research.body.earningsEstimate, 'market/EventService.java', 'EventEvidence');
      }
    } else {
      assertShape(research.body, 'api/ApiResponses.java', 'ErrorBody');
    }

    const history = market.history(state);
    if (history.status === 200) {
      assertShape(history.body, 'api/ApiResponses.java', 'History');
      history.body.candles.forEach(row => assertShape(row, 'model/Candle.java', 'Candle'));
      if (history.body.overlays) {
        assertShape(history.body.overlays, 'api/ApiResponses.java', 'HistoryOverlays');
      }
    } else {
      assertShape(history.body, 'api/ApiResponses.java', 'ErrorBody');
    }

    const expectedMove = market.expectedMove(state);
    if (expectedMove.status === 200) {
      assertShape(expectedMove.body, 'api/ResearchController.java', 'ExpectedMove');
      if (state === 'missing') {
        assert.equal(expectedMove.body.available, false);
        assert.match(expectedMove.body.reason, /implied volatility/);
        assertAbsent(expectedMove.body, 'p16', 'an unavailable range carries no invented price');
      }
    } else {
      assertShape(expectedMove.body, 'api/ApiResponses.java', 'ErrorBody');
    }

    const expirations = market.expirations(state);
    if (expirations.status === 200) {
      assertShape(expirations.body, 'api/ApiResponses.java', 'Expirations');
      expirations.body.expirations.forEach(row =>
        assertShape(row, 'api/ApiResponses.java', 'ExpirationDistance'));
    }

    const chain = market.chain(state);
    if (chain.status === 200) {
      assertShape(chain.body, 'model/OptionChain.java', 'OptionChain');
      chain.body.calls.concat(chain.body.puts).forEach(row =>
        assertShape(row, 'model/OptionQuote.java', 'OptionQuote'));
    } else {
      assertShape(chain.body, 'api/ApiResponses.java', 'ErrorBody');
    }

    const news = market.news(state);
    if (news.status === 200) {
      assertShape(news.body, 'api/ApiResponses.java', 'ResearchNews');
      assertShape(news.body.aggregate, 'recommend/NewsSentimentScorer.java', 'Aggregate');
      news.body.items.forEach(row =>
        assertShape(row, 'recommend/NewsSentimentScorer.java', 'HeadlineSentiment'));
    } else {
      assertShape(news.body, 'api/ApiResponses.java', 'ErrorBody');
    }
  }
});

test('the four market lanes vary independently of each other', () => {
  const mixed = market.marketDocuments({ quote: 'stale', history: 'ready', chain: 'error',
    news: 'missing' });
  assert.equal(mixed.research.body.quote.freshness, 'STALE');
  assert.equal(mixed.history.body.freshness, 'EOD');
  assert.equal(mixed.expectedMove.status, 502);
  assert.equal(mixed.chain.status, 502);
  assert.equal(mixed.news.body.aggregate.available, false);
  // Expirations follow the chain by default and can be broken apart on purpose: listed
  // expirations with no chain behind them is the state that strands a strike picker.
  assert.equal(market.marketDocuments({ chain: 'missing' }).expirations.body.expirations.length, 0);
  assert.equal(market.marketDocuments({ chain: 'missing' }).expectedMove.body.available, false);
  assert.equal(market.marketDocuments({ chain: 'missing', expirations: 'ready' })
    .expirations.body.expirations.length, 2);
});

test('news fixtures cover 0, 5 and 20 headlines and never fabricate a neutral reading', () => {
  for (const count of [0, 5, 20]) {
    const body = market.news('ready', { count: count }).body;
    assert.equal(body.items.length, count);
    assert.equal(body.aggregate.totalHeadlines, count);
    assert.equal(body.aggregate.available, count > 0);
    if (count === 0) {
      assert.equal(body.aggregate.trend, 'UNAVAILABLE',
        'no headlines is unavailable, never silently NEUTRAL');
      assertAbsent(body.aggregate, 'score', 'an unscored lane states no score');
      assert.equal(body.evidence, 'UNAVAILABLE');
      assert.ok(body.aggregate.note, 'and it says why');
    }
  }
});

test('Scout fixtures match the scan wire contract in every state', () => {
  for (const state of scout.SCOUT_STATES) {
    const scan = scout.scoutState(state);
    if (state === 'idle') {
      assert.equal(scan.requested, false, 'an idle Scout has made no request at all');
      assert.deepEqual(scan.frames, []);
      assert.equal(scan.declaration.expectedErrorCode, 'DESK_DECLARATION_REQUIRED');
      assert.deepEqual(scan.declaration.missingDeclarations,
        ['goal', 'horizon', 'risk posture']);
      continue;
    }
    scan.frames.forEach(frame => {
      assert.ok(['progress', 'complete', 'error'].includes(frame.type));
      if (frame.type === 'progress') {
        assertShape(frame.progress, 'recommend/AutoRecommender.java', 'Progress');
        assertShape(frame.progress.counts, 'recommend/AutoRecommender.java', 'ScanCounts');
        // Each count is its own monotonic quantity; none is a fraction of another.
        Object.values(frame.progress.counts).forEach(value =>
          assert.ok(Number.isInteger(value) && value >= 0, 'every scan count is a whole number'));
        if (frame.progress.pick) {
          assertShape(frame.progress.pick, 'recommend/AutoRecommender.java', 'Pick');
        }
      }
      if (frame.type === 'complete') {
        assertShape(frame.result, 'recommend/AutoRecommender.java', 'AutoResult');
        frame.result.picks.forEach(row => {
          assertShape(row, 'recommend/AutoRecommender.java', 'Pick');
          assertShape(row.signals, 'recommend/SignalEngine.java', 'Signals');
          assertShape(row.opportunity, 'recommend/AutoRecommender.java', 'OpportunityContext');
          assertShape(row.bestIdea, 'recommend/AutoRecommender.java', 'BestIdea');
          row.horizons.forEach(horizon => {
            assertShape(horizon, 'recommend/AutoRecommender.java', 'HorizonIdeas');
            horizon.candidates.forEach(scored => {
              assert.equal(scored.evaluation.candidate.symbol, row.symbol,
                'the exact package identity belongs to the symbol its Scout row names');
            });
          });
        });
      }
    });
    // The NDJSON body must parse back to exactly the frames it claims to carry.
    const parsed = scan.ndjson.split('\n').filter(line => line.trim())
      .map(line => JSON.parse(line));
    assert.deepEqual(parsed, scan.frames);
  }
  const complete = scout.scoutState('complete');
  assert.ok(complete.body.picks.length > 0);
  const empty = scout.scoutState('complete', { pickCount: 0 });
  assert.equal(empty.body.picks.length, 0);
  assert.ok(empty.body.skipped.length > 0, 'an empty scan states what it skipped and why');
});

test('candidate fixtures carry Candidate components plus only the attached identity keys', () => {
  const candidate = golden.goldenCandidate();
  assertShape(candidate, 'recommend/Candidate.java', 'Candidate',
    { extraKeys: golden.CANDIDATE_ATTACHED_KEYS });
  // Those attached keys are not Candidate components; they are written onto the serialized node by
  // PlanStrategyService (`id`), PlanStrategyController (`symbol`) and EvaluationReceipt.attachTo
  // (`evaluation`). Assert they really are extra, so the allowance can never quietly cover a
  // renamed component.
  const declared = recordComponents('recommend/Candidate.java', 'Candidate');
  for (const key of golden.CANDIDATE_ATTACHED_KEYS) {
    assert.ok(!declared.includes(key),
      `${key} is now a Candidate component; drop it from CANDIDATE_ATTACHED_KEYS`);
  }
  assertShape(candidate.price, 'paper/PackagePriceReceipt.java', 'PackagePriceReceipt',
    { exact: true });
  assertShape(candidate.evaluation, 'api/ApiResponses.java', 'EvaluationReceipt');
  assertShape(candidate.evaluation.capital, 'eval/CapitalProfile.java', 'CapitalProfile');
  assertShape(candidate.evaluation.risk, 'eval/RiskProfile.java', 'RiskProfile');
  assertShape(candidate.evaluation.assessment, 'eval/FourOutputAssessment.java',
    'FourOutputAssessment');
  assertShape(candidate.evaluation.assessment.economics, 'eval/EconomicAssessment.java',
    'EconomicAssessment');
  assertShape(candidate.evaluation.risk.terminalPayoff, 'eval/RiskProfile.java', 'TerminalPayoff');
  assertShape(candidate.evaluation.risk.jumpTail, 'pricing/JumpMixtureTerminal.java', 'Tail');
  assertShape(candidate.evaluation.risk.jumpTail.base, 'pricing/JumpMixtureTerminal.java',
    'Receipt');
  candidate.evaluation.risk.terminalPayoff.points.forEach(point =>
    assertShape(point, 'eval/RiskProfile.java', 'PayoffPoint'));
});

test('New Idea candidates do not invent preview-only Greeks', () => {
  for (const legCount of [1, 4]) {
    const candidate = newIdea.candidate(legCount);
    assertShape(candidate, 'recommend/Candidate.java', 'Candidate',
      { extraKeys: golden.CANDIDATE_ATTACHED_KEYS.concat('identity') });
    assert.ok(!Object.prototype.hasOwnProperty.call(candidate, 'greeks'),
      'Candidate.java has no greeks component; exact Greeks arrive on TradePreview');
    const preview = newIdea.decisionPreview(candidate, {
      qty: candidate.qty,
      orderInstruction: { type: 'MARKET', timeInForce: 'DAY' }
    }, 32);
    assertShape(preview.preview.analytics.greeks, 'model/GreeksView.java', 'GreeksView',
      { exact: true });
  }
});

test('the order dock carries only the instruction; preview owns the price receipt', () => {
  const dock = golden.goldenOrderDock();
  assertShape(dock, 'api/ApiResponses.java', 'OrderDock');
  assertShape(dock.orderInstruction, 'paper/OrderInstruction.java', 'OrderInstruction');
  assert.deepEqual(Object.keys(dock), ['orderInstruction'],
    'OrderDock cannot publish price or scalar aliases beside preview.price');
  assert.throws(() => golden.goldenOrderDock({ type: 'MARKET', limitNetCents: 100 }),
    /MARKET orders cannot carry/);
  assert.throws(() => golden.goldenOrderDock({ type: 'LIMIT' }), /LIMIT orders require/);
});

test('portfolio documents match their wire contracts', () => {
  const documents = book.bookDocuments({ positions: 4, shares: 2 });
  assertShape(documents.tradePage, 'api/ApiResponses.java', 'TradePage');
  assertShape(documents.positionBook, 'api/ApiResponses.java', 'PositionBook');
  assertShape(documents.summary, 'api/ApiResponses.java', 'PortfolioSummary');
  assertShape(documents.greeks, 'paper/TradeService.java', 'BookGreeks');
  documents.greeks.positions.forEach(row =>
    assertShape(row, 'paper/TradeService.java', 'PositionGreekRow'));
  documents.greeks.positions.forEach(row =>
    assertShape(row.greeks, 'model/GreeksView.java', 'GreeksView'));
  documents.sharePositions.forEach(row =>
    assertShape(row, 'paper/PositionsService.java', 'PositionView'));
  assertShape(documents.heat, 'paper/TradeService.java', 'PortfolioHeat');
  assertShape(documents.bookRisk.practice.shareRoster,
    'paper/BookRiskService.java', 'BookShareRoster');
  documents.bookRisk.practice.shareRoster.rows.forEach(row =>
    assertShape(row, 'paper/BookRiskService.java', 'BookShareRow'));
  const read = book.practiceBookRead(documents);
  assertShape(read, 'api/PracticeBookRead.java', 'PracticeBookRead');
  assertShape(read.account, 'api/PracticeBookRead.java', 'AccountFacts');
  assertShape(read.snapshot, 'paper/TradeService.java', 'PracticeBookSnapshot');
});

test('the plan portfolio envelope matches PlanDecisionController.plansPortfolio', () => {
  const plans = ideas.workingIdeas(5);
  const bare = ideas.planPortfolio(plans);
  assertShape(bare, 'api/ApiResponses.java', 'PlanRows');
  bare.plans.forEach(row => assert.deepEqual(Object.keys(row), ['plan'],
    'a Plan with no decision carries only its plan, never an empty decision or a null mark'));
  const decided = ideas.planPortfolio(plans, {
    decidedTradeIdByPlanId: { [plans[0].id]: 'trade_golden_gldn', [plans[1].id]: 'trade_fixture_1' },
    markUnavailableFor: [plans[1].id]
  });
  assert.deepEqual(Object.keys(decided.plans[0]), ['plan', 'decision', 'tradeId', 'mark']);
  assert.deepEqual(Object.keys(decided.plans[1]),
    ['plan', 'decision', 'tradeId', 'markUnavailable']);
});

// ---------------------------------------------------------------------------------------------
// Arithmetic: the fixtures add up, and the golden package's numbers are the ones claimed
// ---------------------------------------------------------------------------------------------

/**
 * The golden package's payoff from FIRST PRINCIPLES, independent of `package-math`: the credit
 * collected, less the short put's intrinsic value capped at the spread width, across 300 shares.
 */
function goldenClosedForm(priceDollars) {
  const priceCents = Math.round(priceDollars * 100);
  const intrinsic = Math.min(Math.max(25000 - priceCents, 0), 500);
  return 45000 - 300 * intrinsic;
}

test('the golden package states the numbers it claims, derived two independent ways', () => {
  const facts = golden.FACTS;
  assert.equal(facts.optionNetPremiumCents, 45000);
  assert.equal(facts.stockCashFlowCents, 0);
  assert.equal(facts.grossPackageNetCents, 45000);
  assert.equal(facts.openingFeesCents, 300);
  assert.equal(facts.afterFeeNetCents, 44700);
  assert.equal(facts.maxProfitCents, 45000);
  assert.equal(facts.maxLossCents, 105000);
  assert.equal(facts.breakeven, '248.50');

  const legList = golden.goldenLegs();
  assert.equal(packageMath.optionNetPremiumCents(legList, golden.QUANTITY),
    facts.optionNetPremiumCents);
  assert.equal(packageMath.stockCashFlowCents(legList, golden.QUANTITY), 0);
  const extremes = packageMath.extremes(legList, golden.QUANTITY);
  assert.equal(extremes.maxProfitCents, facts.maxProfitCents);
  assert.equal(extremes.maxLossCents, facts.maxLossCents);
  assert.deepEqual(packageMath.breakevens(legList, golden.QUANTITY), [facts.breakeven]);

  for (const priceDollars of [200, 240, 245, 247.5, 248.5, 249, 250, 260, 300]) {
    assert.equal(golden.profitCentsAt(priceDollars), goldenClosedForm(priceDollars),
      `payoff disagreement at ${priceDollars}`);
  }
});

test('the §7.2 receipt reconciles, and the builder refuses one that does not', () => {
  const receipt = golden.goldenPrice();
  assert.equal(receipt.grossPackageNetCents,
    receipt.optionNetPremiumCents + receipt.stockCashFlowCents);
  assert.equal(receipt.afterFeeNetCents,
    receipt.grossPackageNetCents - receipt.openingFeesCents);
  assert.equal(price.priced(receipt), true);

  assert.throws(() => price.packagePrice({ openingFeesCents: -1 }), /fees cannot be negative/);
  assert.throws(() => price.packagePrice({ quantity: 0 }), /quantity >= 1/);
  assert.throws(() => price.packagePrice({ valuationBasis: 'UNAVAILABLE' }),
    /use unavailablePackagePrice/);
  assert.throws(() => price.unavailablePackagePrice({ reason: '  ' }),
    /must state why it is unavailable/);

  // The stock-bearing package is the case §3.3 requires a screen to explain: the option-only net
  // and the whole-package net are genuinely different numbers for one trade.
  const buyWrite = price.stockPackagePrice();
  assert.notEqual(buyWrite.optionNetPremiumCents, buyWrite.grossPackageNetCents);
  assert.equal(buyWrite.grossPackageNetCents,
    buyWrite.optionNetPremiumCents + buyWrite.stockCashFlowCents);
});

test('an exact zero is a stated amount and is spelled differently from an absent one', () => {
  const zero = price.zeroPackagePrice();
  assert.equal(zero.grossPackageNetCents, 0);
  assert.equal(zero.afterFeeNetCents, 0);
  assert.equal(zero.openingFeesCents, 0);
  assert.equal(price.priced(zero), true, 'priced at zero is still priced');

  const absent = price.unavailablePackagePrice({ reason: 'no book' });
  assert.equal(price.priced(absent), false);

  assert.equal(wire.expectedMoney(0), '$0');
  assert.equal(wire.expectedSigned(0), '$0');
  assert.notEqual(wire.expectedSigned(0), '+$0');
  assert.equal(wire.UNAVAILABLE_TEXT, '—');
  assert.notEqual(wire.expectedSigned(0), wire.UNAVAILABLE_TEXT,
    'a real zero and an absent value must not render the same');
});

test('rendered money strings are locale-independent and use the sign the Desk draws', () => {
  assert.equal(wire.expectedMoney(105000), '$1,050');
  assert.equal(wire.expectedMoney(-105000), '−$1,050');
  assert.equal(wire.expectedSigned(45000), '+$450');
  assert.equal(wire.expectedSigned(-300), '−$3');
  [
    [-50, '−$0.50'], [-49, '−$0.49'], [-47, '−$0.47'], [-1, '−$0.01'],
    [0, '$0'],
    [1, '+$0.01'], [47, '+$0.47'], [49, '+$0.49'], [50, '+$0.50']
  ].forEach(([cents, rendered]) => assert.equal(wire.expectedSigned(cents), rendered,
    `${cents} cents keeps its exact signed P/L spelling`));
  assert.equal(wire.expectedMoney(105047), '$1,050.47');
  assert.equal(wire.expectedLoss(-47), '$0.47');
  assert.equal(wire.expectedLoss(47), '$0.47');
  assert.equal(wire.expectedLoss(0), '$0');
  assert.equal(wire.expectedPrice(47), '$0.47');
  assert.equal(wire.expectedPrice(0), '$0.00');
  assert.equal(wire.expectedFee(47), '−$0.47');
  assert.equal(wire.expectedFee(-47), '−$0.47');
  assert.equal(wire.expectedFee(0), '$0.00');
  assert.equal(wire.expectedSigned(1.5), wire.UNAVAILABLE_TEXT,
    'fractional cents are malformed, not rounded');
  assert.equal(wire.expectedSigned('47'), wire.UNAVAILABLE_TEXT,
    'numeric strings do not silently change the cents wire type');
  assert.equal(golden.FACTS.rendered.maxLoss, '$1,050');
  assert.equal(golden.FACTS.rendered.afterFeeNet, '+$447');
  assert.equal(golden.FACTS.rendered.openingFees, '−$3');
});

test('observedAt is epoch milliseconds and its human form is not the raw digits', () => {
  assert.equal(typeof golden.goldenPrice().observedAt, 'number');
  assert.equal(golden.goldenPrice().observedAt, wire.OBSERVED_AT_MS);
  assert.equal(new Date(wire.OBSERVED_AT_MS).toISOString(), wire.OBSERVED_AT_ISO);
  assert.equal(String(wire.OBSERVED_AT_MS), wire.OBSERVED_AT_RAW_TEXT);
  assert.ok(!wire.OBSERVED_AT_ISO.includes(wire.OBSERVED_AT_RAW_TEXT),
    'the human timestamp must not contain the raw epoch');
  // Every leg quote and every chain row is stamped from the same instant, so a surface showing a
  // different time is showing a different observation, not a formatting difference.
  golden.goldenLegs().forEach(leg =>
    assert.equal(leg.quoteAsOfEpochMs, wire.OBSERVED_AT_MS));
  assert.equal(market.chain('ready').body.asOfEpochMs, wire.OBSERVED_AT_MS);
});

test('the served payoff curve spans the story grid, and the narrow variant deliberately does not', () => {
  const wide = golden.goldenTerminalPayoff();
  const prices = wide.points.map(point => point.price);
  const anchor = golden.FACTS.anchorSpotCents / 100;
  golden.STORY_MOVES.forEach(movePct => {
    const price_ = golden.priceAt(movePct);
    assert.ok(price_ >= prices[0] && price_ <= prices[prices.length - 1],
      `the ±20% curve must cover the ${movePct * 100}% story move`);
  });
  assert.equal(prices[0], anchor * 0.8);
  assert.equal(prices[prices.length - 1], anchor * 1.2);
  // The exact-zero checkpoint: the breakeven really is a priced point worth exactly nothing.
  const breakeven = wide.points.find(point => point.price === Number(golden.FACTS.breakeven));
  assert.equal(breakeven.profitCents, 0);

  const narrow = golden.goldenTerminalPayoff({ spanPct: 0.10 });
  const narrowPrices = narrow.points.map(point => point.price);
  const uncovered = golden.STORY_MOVES.filter(movePct => {
    const price_ = golden.priceAt(movePct);
    return price_ < narrowPrices[0] || price_ > narrowPrices[narrowPrices.length - 1];
  });
  assert.deepEqual(uncovered, [-0.20, 0.13, 0.20],
    'the narrow curve must leave story moves off-domain — that is the state it exists to produce');

  const unavailable = golden.goldenTerminalPayoff({ available: false });
  assert.equal(unavailable.available, false);
  assert.deepEqual(unavailable.points, []);
  assert.ok(unavailable.unavailableReason);
});

test('story checkpoints are the engine\'s own move set and are priced off the same curve', () => {
  assert.deepEqual(golden.STORY_MOVES, [-0.20, -0.09, -0.06, -0.01, 0.0, 0.06, 0.13, 0.20],
    'RiskProfiler.MOVES is the story set; a fixture with its own grid would never line up');
  const scenarios = golden.goldenScenarios();
  scenarios.forEach(row => {
    assert.equal(row.pnlCents, golden.profitCentsAt(golden.priceAt(row.underlyingMovePct)));
  });
  const mass = scenarios.reduce((total, row) => total + row.prob, 0);
  assert.equal(Math.round(mass * 1e6) / 1e6, 1, 'the risk-neutral bins must sum to one');
  // No ATM IV means no probability, not an invented one.
  golden.goldenScenarios({ withProb: false }).forEach(row => assert.equal(row.prob, null));
});

test('a named-story request leaves numeric policy blank and the mocked server resolves its receipt', () => {
  const declaration = {
    story: 'MARKET_CRASH',
    movePct: null,
    ivShiftPoints: null,
    elapsedSessions: null,
    sourcePathIndex: null
  };
  assert.deepEqual(scenarioFixtures.resolveInteraction(declaration), {
    story: 'MARKET_CRASH',
    movePct: -20,
    ivShiftPoints: 14,
    elapsedSessions: 5,
    sourcePathIndex: null
  });
  assert.deepEqual(scenarioFixtures.resolveInteraction({
    story: 'GRIND_HIGHER',
    movePct: 4,
    ivShiftPoints: -1,
    elapsedSessions: 6,
    sourcePathIndex: null
  }), {
    story: 'GRIND_HIGHER',
    movePct: 4,
    ivShiftPoints: -1,
    elapsedSessions: 6,
    sourcePathIndex: null
  }, 'explicit user overrides survive the server policy resolver unchanged');
});

test('the unpriced candidate keeps its mechanics and drops every priced consequence', () => {
  const candidate = golden.unpricedCandidate();
  assertShape(candidate, 'recommend/Candidate.java', 'Candidate',
    { extraKeys: golden.CANDIDATE_ATTACHED_KEYS });
  assertShape(candidate.price, 'paper/PackagePriceReceipt.java', 'PackagePriceReceipt',
    { exact: true });
  assert.equal(candidate.price.valuationBasis, 'UNAVAILABLE');
  assert.ok(candidate.price.unavailableReason);
  assertAbsent(candidate, 'pop', 'no priced package, no probability of profit');
  assertAbsent(candidate, 'maxProfitCents');
  assertAbsent(candidate, 'expectedValueCents');
  assert.deepEqual(candidate.breakevens, []);
  assert.equal(candidate.maxLossCents, golden.FACTS.maxLossCents,
    'the spread width is mechanical and survives an absent price');
  assert.equal(candidate.evaluation.available, false);
  assert.equal(candidate.evaluation.assessment.economics.verdict, 'UNAVAILABLE');
  assert.equal(candidate.evaluation.assessment.coherence.verdict, 'UNAVAILABLE');
});

test('Book aggregates are derived from the roster they summarize', () => {
  for (const count of [0, 1, 4, 12]) {
    const documents = book.bookDocuments({ positions: count, shares: count ? 2 : 0 });
    const trades = documents.activeTrades;
    assert.equal(documents.summary.openTradesCount, count);
    assert.equal(documents.summary.reservedCents,
      trades.reduce((total, trade) => total + trade.maxLossCents, 0));
    assert.equal(documents.summary.openTradesUnrealizedCents,
      trades.reduce((total, trade) => total + (trade.unrealizedPnlCents || 0), 0));
    assert.equal(documents.heat.activeTrades, count);
    assert.equal(documents.heat.totalMaxLossCents, documents.summary.reservedCents);
    assert.equal(documents.greeks.activeTrades, count);
    assert.equal(documents.greeks.positions.length, count);
    assert.equal(documents.tradePage.total, count);
    if (count === 0) {
      assert.equal(documents.heat.concentrationPct, 0);
      assert.equal(documents.summary.totalPnlCents, 0);
    } else {
      assert.ok(documents.heat.concentrationPct > 0 && documents.heat.concentrationPct <= 100);
    }
  }
});

test('every held line\'s stated economics follow from its own legs', () => {
  book.positions(12).forEach(trade => {
    const expirations = new Set(trade.legs.map(leg => leg.expiration).filter(Boolean));
    assert.equal(trade.entryPrice.grossPackageNetCents,
      packageMath.entryCashCents(trade.legs, trade.qty),
      `${trade.id} states a net premium its legs do not produce`);
    if (expirations.size > 1) {
      // A mixed-expiry package has no terminal price: the payoff receipt says so and carries no
      // curve, and no story checkpoints are published for it.
      assert.equal(trade.terminalPayoff.available, false);
      assert.ok(trade.terminalPayoff.unavailableReason);
      assert.equal(trade.scenarios, undefined,
        'NON_EMPTY inclusion drops an empty scenario list from the payload');
      assert.deepEqual(trade.breakevens, []);
      return;
    }
    const extremes = packageMath.extremes(trade.legs, trade.qty);
    assert.equal(trade.maxLossCents, extremes.maxLossCents, `${trade.id} max loss`);
    assert.equal(trade.maxProfitCents === undefined ? null : trade.maxProfitCents,
      extremes.maxProfitCents, `${trade.id} max profit`);
    assert.deepEqual(trade.breakevens, packageMath.breakevens(trade.legs, trade.qty));
    trade.terminalPayoff.points.forEach(point =>
      assert.equal(point.profitCents,
        packageMath.terminalPnlCents(trade.legs, trade.qty, point.price)));
  });
});

test('the golden package is the same package as an idea and as a position', () => {
  const candidate = golden.goldenCandidate();
  const held = book.goldenHeldTrade();
  assert.equal(held.symbol, candidate.symbol);
  assert.equal(held.qty, candidate.qty);
  assert.equal(held.entryPrice.grossPackageNetCents, candidate.price.grossPackageNetCents);
  assert.equal(held.maxLossCents, candidate.maxLossCents);
  assert.equal(held.maxProfitCents, candidate.maxProfitCents);
  assert.deepEqual(held.breakevens, candidate.breakevens);
  assert.equal(held.popEntry, candidate.marketImpliedRisk.probabilityMap.pAnyProfit);
  assert.deepEqual(held.greeks, golden.goldenGreeks(),
    'both surfaces report Greeks in the ONE canonical unit set');
  assert.equal(held.entryPrice.openingFeesCents, candidate.price.openingFeesCents);
  // The story checkpoints are the same eight moves priced off the same curve on both surfaces.
  const heldMoves = held.scenarios.map(row => row.underlyingMovePct);
  const ideaMoves = candidate.evaluation.risk.scenarios.map(row => row.underlyingMovePct);
  assert.deepEqual(heldMoves, ideaMoves);
  held.scenarios.forEach((row, index) => assert.equal(row.pnlCents,
    candidate.evaluation.risk.scenarios[index].pnlCents,
    `the same move must price the same on both surfaces at ${row.underlyingMovePct}`));
});

test('the held payoff has ONE shape, and the detail envelope no longer carries a second list', () => {
  /* ApiResponses.PayoffPoint (a String price) was deleted with TradeDetail.payoff. There is one
     held curve — RiskProfile.TerminalPayoff, whose price is a JSON number — and the detail
     envelope must not reintroduce a second, because the browser consumed one and then let the
     other overwrite it (audit §5.4). */
  assert.equal(typeof golden.goldenTerminalPayoff().points[0].price, 'number',
    'RiskProfile.TerminalPayoff.price is a BigDecimal, serialized as a number');
  const detail = book.tradeDetail(book.goldenHeldTrade());
  assert.equal(Object.hasOwn(detail, 'payoff'), false,
    'TradeDetail has five components; a second payoff list here is the deleted duplicate');
  assert.equal(typeof detail.trade.terminalPayoff.points[0].price, 'number');
});

// ---------------------------------------------------------------------------------------------
// Determinism and non-personal content
// ---------------------------------------------------------------------------------------------

test('builders are deterministic and return independent objects', () => {
  assert.deepEqual(fixtures.desk(), fixtures.desk());
  const first = book.positions(4);
  first[0].symbol = 'MUTATED';
  assert.notEqual(book.positions(4)[0].symbol, 'MUTATED',
    'a caller mutating one axis must not disturb the next caller');
  const price_ = golden.goldenPrice();
  price_.grossPackageNetCents = 1;
  assert.equal(golden.goldenPrice().grossPackageNetCents, 45000);
});

test('nothing in the fixture set is personal, and no clock is read at build time', () => {
  const world = fixtures.desk({ positions: 12, workingIdeas: 20, newsCount: 20,
    scout: 'complete' });
  const payload = JSON.stringify(world);
  assert.ok(!/[\w.+-]+@[\w-]+\.[\w.]+/.test(payload), 'no email-shaped text');
  assert.ok(!/\b\d{3}-\d{2}-\d{4}\b/.test(payload), 'no government-identifier-shaped text');
  // Every link is on a reserved domain (RFC 2606), so no fixture can point a reader — or a
  // crawler following a CI artifact — at a real site.
  (payload.match(/https?:\/\/[^"]+/g) || []).forEach(url =>
    assert.match(url, /^https:\/\/example\.invalid\//, url));
  // Every symbol is invented, so a capture or CI artifact cannot be read as someone's real book.
  const allowed = new Set([wire.GOLDEN_SYMBOL, 'ZBM'].concat(wire.ROSTER_SYMBOLS));
  world.book.activeTrades.forEach(trade => assert.ok(allowed.has(trade.symbol), trade.symbol));
  world.plans.plans.forEach(plan => assert.ok(allowed.has(plan.symbol), plan.symbol));
  // Every timestamp derives from the frozen instant or a stated session date; none is "now".
  assert.ok(payload.includes(String(wire.OBSERVED_AT_MS)), 'the frozen instant is what is stamped');
  assert.ok(!payload.includes(String(Date.now()).slice(0, 8)),
    'no fixture value is derived from the wall clock');
});

/**
 * The three regressions §5.2 confirms are LIVE. This test asserts the fixtures REACH each of
 * them — it does not assert the product is correct, which is M1's work and must stay red until
 * then. Its job is to fail if a later edit quietly makes these fixtures stop reproducing the
 * states the M0 tests are written against.
 */
test('the fixtures reproduce the preconditions of all three live rendering regressions', () => {
  // 1. `candCollect(c)` reads `c.optionNet`, which desk-backend.js maps from
  //    `price.optionNetPremiumCents`. An unpriced package supplies null there, callers then
  //    compare it numerically (`null >= 0` is true) and print `signed(null)` as `+$0`.
  const unpriced = golden.unpricedCandidate();
  assert.equal(unpriced.price.optionNetPremiumCents, null,
    'the unpriced receipt must state a null option net — that is the input candCollect sees');
  assert.ok(Object.prototype.hasOwnProperty.call(unpriced.price, 'optionNetPremiumCents'),
    'and it must be PRESENT and null, because the receipt is @JsonInclude(ALWAYS)');
  assert.equal(null >= 0, true, 'the coercion that turns an absent premium into a credit');

  // 2. `payFor()` returns null OFF the served curve, and the scenario loop in `drawPayoff()`
  //    returns out of the whole renderer on the first null. A curve narrower than the ±20% story
  //    grid is what puts a story move off-domain.
  const narrow = golden.goldenTerminalPayoff({ spanPct: 0.10 });
  const low = narrow.points[0].price;
  const high = narrow.points[narrow.points.length - 1].price;
  const offDomain = golden.STORY_MOVES
    .map(golden.priceAt)
    .filter(price_ => price_ < low || price_ > high);
  assert.equal(offDomain.length, 3,
    'the narrow curve must leave story moves unpriced, or regression 2 cannot fire');
  const wide = golden.goldenTerminalPayoff();
  const wideLow = wide.points[0].price;
  const wideHigh = wide.points[wide.points.length - 1].price;
  assert.equal(golden.STORY_MOVES.map(golden.priceAt)
    .filter(price_ => price_ < wideLow || price_ > wideHigh).length, 0,
  'and the healthy curve must cover every move, so a passing case exists to compare against');

  // 3. `observedAt` is epoch milliseconds; the receipt renderer treats it as an ISO string
  //    (`String(observedAt).replace('T', ' ')`), so the raw digits reach the screen.
  const observedAt = golden.goldenPrice().observedAt;
  assert.equal(typeof observedAt, 'number');
  assert.equal(String(observedAt).replace('T', ' ').replace(/\..*$/, ''),
    wire.OBSERVED_AT_RAW_TEXT,
    'the current renderer produces the raw epoch from this value — that is the defect');
  assert.ok(wire.OBSERVED_AT_ISO.includes('T'),
    'while the human form of the same instant is an ISO timestamp');
});

test('the NON_NULL inclusion policy is applied, and ALWAYS is applied where the record says so', () => {
  const trade = book.positions(1)[0];
  assert.ok(!Object.prototype.hasOwnProperty.call(trade, 'closedAt'),
    'a null field is ABSENT under Json.MAPPER\'s NON_NULL inclusion');
  assert.ok(!Object.prototype.hasOwnProperty.call(trade, 'closeReason'));
  assert.ok(Object.prototype.hasOwnProperty.call(trade, 'entryPrice'));
  assert.ok(!Object.prototype.hasOwnProperty.call(trade, 'entryNetPremiumCents'),
    'TradeView carries only the typed recorded entry-price receipt');
  // PackagePriceReceipt is @JsonInclude(ALWAYS): the nulls are the message.
  const receipt = price.unavailablePackagePrice({ reason: 'no book' });
  assert.ok(Object.prototype.hasOwnProperty.call(receipt, 'grossPackageNetCents'));
  assert.equal(receipt.grossPackageNetCents, null);
});

test('the Java record reader itself is honest', () => {
  // A record it cannot find must fail loudly rather than silently allowing every key.
  assert.throws(() => recordComponents('api/TradeView.java', 'NoSuchRecord'), /no record/);
  // Generic component types must not be split on their internal commas.
  const components = recordComponents('api/TradeView.java', 'TradeView');
  assert.ok(components.includes('entrySnapshot'), 'Map<String, Object> entrySnapshot');
  assert.ok(!components.includes('Object'), 'a generic argument is not a component');
  // A commented-out field is not a declaration.
  const chain = recordComponents('model/OptionChain.java', 'OptionChain');
  assert.deepEqual(chain, ['underlying', 'expiration', 'underlyingPrice', 'calls', 'puts',
    'asOfEpochMs', 'source', 'freshness']);
});
