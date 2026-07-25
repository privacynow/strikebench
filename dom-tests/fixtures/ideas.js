'use strict';

/**
 * Working ideas — `Plan.View` rows at the §16.3 counts (0, 5 and 20) plus the two envelopes that
 * carry them: `/api/plans` (`ApiResponses.Plans`) and `/api/plans/portfolio`
 * (`ApiResponses.PlanRows`, whose rows are `{plan, decision?, tradeId?, mark?}` maps built in
 * `PlanDecisionController.plansPortfolio`).
 *
 * "Working" is not a status the server publishes; it is `authWorkingPlanRows`' filter in the Desk:
 * open, assumptions still editable, and DRAFT or ACTIVE. The builders can produce rows on either
 * side of that line, because a Home that counts a decided Plan as a working idea and a Home that
 * drops a genuinely working one are different defects and both need a fixture.
 */

const wire = require('./wire');

const STATUSES = ['DRAFT', 'ACTIVE', 'DECIDED_CASH', 'POSITION_OPEN', 'CLOSED', 'ABANDONED',
  'ARCHIVED'];
const STAGES = ['UNDERSTAND', 'EVIDENCE', 'STRATEGY', 'OUTCOMES', 'DECIDE', 'MANAGE_REVIEW'];
const MARKET_KINDS = ['OBSERVED', 'DEMO', 'SIMULATED'];

/**
 * `Plan.ContextRevision`. Every declaration the user made, and nothing they did not: §3.5 forbids
 * the system silently choosing Income / Neutral / 45 days and then behaving as if it were declared,
 * so an undeclared field here is null rather than a plausible-looking default.
 */
function contextRevision(overrides) {
  const o = Object.assign({
    id: 'ctxrev_fixture_1',
    rev: 7,
    thesis: 'neutral',
    horizonDays: 28,
    targetCents: null,
    riskMode: 'balanced',
    holdingsShares: null,
    costBasisCents: null,
    priceAssumptionCents: null,
    assignmentPreference: null,
    inputHash: 'c'.repeat(64),
    engineVersion: 'fixture-engine-1',
    createdAt: '2026-07-20T15:00:00Z'
  }, overrides || {});
  return wire.nonNull(o);
}

/** One `Plan.View`. */
function planView(overrides) {
  const o = Object.assign({
    id: 'plan_fixture_1',
    originPlanId: null,
    symbol: wire.GOLDEN_SYMBOL,
    intent: 'INCOME',
    marketKind: 'OBSERVED',
    worldId: null,
    accountId: wire.ACCOUNT_ID,
    title: null,
    status: 'ACTIVE',
    furthestStage: 'STRATEGY',
    version: 31,
    open: true,
    assumptionsEditable: true,
    context: null,
    createdAt: '2026-07-20T15:00:00Z',
    updatedAt: wire.OBSERVED_AT_ISO
  }, overrides || {});
  wire.oneOf('plan.status', o.status, STATUSES);
  wire.oneOf('plan.furthestStage', o.furthestStage, STAGES);
  wire.oneOf('plan.marketKind', o.marketKind, MARKET_KINDS);
  // A SIMULATED plan without a world is not a state the server can produce; it names the world it
  // was generated in or it is OBSERVED.
  if (o.marketKind === 'SIMULATED' && !o.worldId) {
    throw new Error('a SIMULATED plan must name its worldId');
  }
  return wire.nonNull(Object.assign({}, o, {
    context: o.context || contextRevision({ id: `ctxrev_${o.id}` })
  }));
}

/** The Desk's own definition of a WORKING idea (`authPlanMutable`), stated once. */
function isWorking(plan) {
  return !!plan && plan.open !== false && plan.assumptionsEditable !== false
    && (plan.status === 'DRAFT' || plan.status === 'ACTIVE');
}

/**
 * A roster of working ideas at one of the §16.3 counts.
 *
 * `mixed: true` adds one decided and one archived Plan ON TOP of the requested working count, so a
 * test can prove the count on screen is the working count and not simply "every row the server
 * returned". Archived rows never reach the wire at all — `plansPortfolio` filters them — so the
 * mixed roster carries a DECIDED_CASH and a POSITION_OPEN row instead.
 */
function workingIdeas(count, options) {
  const settings = Object.assign({ mixed: false }, options || {});
  wire.oneOf('workingIdeas(count)', count, [0, 5, 20]);
  const rows = [];
  for (let index = 0; index < count; index += 1) {
    rows.push(planView({
      id: `plan_fixture_${index}`,
      symbol: index === 0 ? wire.GOLDEN_SYMBOL
        : wire.ROSTER_SYMBOLS[index % wire.ROSTER_SYMBOLS.length],
      intent: ['INCOME', 'ACQUIRE', 'HEDGE', 'DIRECTIONAL'][index % 4],
      status: index % 5 === 0 ? 'DRAFT' : 'ACTIVE',
      furthestStage: STAGES[index % 4],
      version: 3 + index,
      createdAt: `2026-07-${String(1 + (index % 24)).padStart(2, '0')}T15:00:00Z`,
      context: contextRevision({
        id: `ctxrev_plan_fixture_${index}`,
        rev: 1 + (index % 9),
        thesis: ['neutral', 'bullish', 'bearish'][index % 3],
        horizonDays: [14, 28, 45, 90][index % 4]
      })
    }));
  }
  if (settings.mixed) {
    rows.push(planView({ id: 'plan_fixture_decided', status: 'DECIDED_CASH',
      furthestStage: 'DECIDE', open: true, assumptionsEditable: false }));
    rows.push(planView({ id: 'plan_fixture_open', status: 'POSITION_OPEN',
      furthestStage: 'MANAGE_REVIEW', open: true, assumptionsEditable: false }));
  }
  return rows;
}

/** `ApiResponses.Plans` — what `/api/plans` returns. */
function plansResponse(plans, overrides) {
  return Object.assign({ plans: plans || [], market: 'OBSERVED', world: null }, overrides || {});
}

/**
 * `ApiResponses.PlanRows` — what `/api/plans/portfolio` returns. Rows are maps, and the optional
 * keys are genuinely optional: `decision`, `tradeId` and `mark` appear only when the Plan has a
 * decision, and `markUnavailable: true` replaces `mark` when the account snapshot has no mark for
 * that trade. Emitting all of them unconditionally would hide the branch that has to say so.
 */
function planPortfolio(plans, options) {
  const settings = Object.assign({ decidedTradeIdByPlanId: {}, markUnavailableFor: [] },
    options || {});
  const rows = (plans || []).map(plan => {
    const row = { plan: plan };
    const tradeId = settings.decidedTradeIdByPlanId[plan.id];
    if (tradeId) {
      row.decision = { action: 'TRADE', tradeId: tradeId };
      row.tradeId = tradeId;
      if (settings.markUnavailableFor.includes(plan.id)) row.markUnavailable = true;
      else {
        row.mark = {
          tradeId: tradeId,
          ts: wire.OBSERVED_AT_ISO,
          unrealizedCents: 12000,
          decisionUnrealizedCents: 12000,
          freshness: 'REALTIME'
        };
      }
    }
    return row;
  });
  return { plans: rows, market: 'OBSERVED' };
}

module.exports = {
  contextRevision, planView, workingIdeas, plansResponse, planPortfolio, isWorking,
  STATUSES, STAGES, MARKET_KINDS
};
