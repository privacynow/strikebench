'use strict';

/**
 * Canonical New Idea documents for the strict visual lane.
 *
 * This module deliberately composes the existing fixture owners:
 *   - `legs` owns wire-correct contract legs and executable bid/ask receipts;
 *   - `package-math` owns every package payoff, extreme, and breakeven;
 *   - `price` owns the one PackagePriceReceipt shape;
 *   - `ideas` owns Plan wire records;
 *   - `market` owns quote/history/chain/news.
 *
 * The visual lane may therefore vary density (one leg versus four legs, 20 headlines, 48 paths)
 * without inventing a second financial calculator or a second market fixture.
 */

const wire = require('./wire');
const legFixtures = require('./legs');
const math = require('./package-math');
const priceFixtures = require('./price');
const goldenFixtures = require('./golden');
const ideaFixtures = require('./ideas');
const marketFixtures = require('./market');
const scenarioFixtures = require('./scenarios');

const PLAN_ID = 'plan_fixture_new_idea';
const RUN_ID = 'strategy_run_fixture_new_idea';
const INPUT_HASH = '7'.repeat(64);
const ENSEMBLE_ID = 'ensemble_fixture_new_idea';
const ENSEMBLE_FINGERPRINT = '8'.repeat(64);
const DATASET_ID = 'dataset_fixture_observed';
const QUANTITY = 2;
const ANCHOR_SPOT = 250;
const STORY_MOVES = scenarioFixtures.MOVES;
const STORY_PROBABILITIES = [0.02, 0.06, 0.08, 0.19, 0.22, 0.22, 0.14, 0.07];

function identity(legCount) {
  if (legCount === 1) {
    return {
      family: 'CASH_SECURED_PUT',
      template: null,
      label: 'Cash-secured put',
      summary: 'Collect premium while reserving cash to acquire shares at the strike.',
      definedRisk: true,
      blockedByDefault: false,
      custom: false
    };
  }
  return {
    family: 'IRON_CONDOR',
    template: null,
    label: 'Iron condor',
    summary: 'A defined-risk range package with a put spread and a call spread.',
    definedRisk: true,
    blockedByDefault: false,
    custom: false
  };
}

function packageName(legCount) {
  return legCount === 1 ? 'Cash-secured put' : 'Iron condor';
}

function packageStrategy(legCount) {
  return legCount === 1 ? 'CASH_SECURED_PUT' : 'IRON_CONDOR';
}

function packageId(legCount) {
  return `candidate_fixture_${legCount}_leg`;
}

/**
 * The exact preview/canvas GreeksView. Candidate.java has no `greeks` component: Greeks exist only
 * after the exact package is priced, so the fixture must not make them appear on a scan candidate.
 */
function packageGreeks(candidate_) {
  const singleLeg = candidate_ && candidate_.legs && candidate_.legs.length === 1;
  return {
    deltaShares: singleLeg ? 56.4 : 4.8,
    gammaSharesPerDollar: singleLeg ? -1.72 : -0.84,
    thetaCentsPerDay: singleLeg ? 1820 : 1260,
    vegaCentsPerPoint: singleLeg ? -2840 : -1980
  };
}

function payoffPoints(legList, quantity) {
  const strikes = legList.map(leg => Number(leg.strike)).filter(Number.isFinite);
  const anchors = [ANCHOR_SPOT * 0.8].concat(strikes, [ANCHOR_SPOT * 1.2]);
  return Array.from(new Set(anchors)).sort((a, b) => a - b).map(underlying => ({
    price: underlying,
    profitCents: math.terminalPnlCents(legList, quantity, underlying)
  }));
}

function candidate(legCount, overrides) {
  if (legCount !== 1 && legCount !== 4) {
    throw new Error('New Idea visual fixtures support the required 1-leg and 4-leg packages.');
  }
  const settings = Object.assign({
    id: packageId(legCount),
    selected: undefined,
    favorable: true
  }, overrides || {});
  const packageLegs = legFixtures.legs(legCount).map(leg =>
    Object.assign({}, leg, { expiration: wire.FAR_EXPIRATION }));
  const optionNetPremiumCents = math.optionNetPremiumCents(packageLegs, QUANTITY);
  const extremes = math.extremes(packageLegs, QUANTITY);
  const breakevens = math.breakevens(packageLegs, QUANTITY);
  const points = payoffPoints(packageLegs, QUANTITY);
  const scenarios = scenarioFixtures.STORIES.map((story, index) => ({
    story: story.story,
    underlyingMovePct: story.underlyingMovePct,
    pnlCents: math.terminalPnlCents(packageLegs, QUANTITY,
      Math.round(ANCHOR_SPOT * (1 + story.underlyingMovePct) * 100) / 100),
    prob: STORY_PROBABILITIES[index]
  }));
  const entryPrice = priceFixtures.packagePrice({
    quantity: QUANTITY,
    optionNetPremiumCents,
    stockCashFlowCents: 0,
    openingFeesCents: 0,
    estimatedRoundTripFeesCents: 0,
    executableNetCents: optionNetPremiumCents,
    source: 'FIXTURE_EXECUTABLE_BOOK',
    freshness: 'REALTIME',
    observedAt: wire.OBSERVED_AT_MS,
    fingerprint: `${legCount}`.repeat(64)
  });
  const realizedEv = legCount === 1 ? 9500 : 5800;
  const marketEv = legCount === 1 ? 3200 : 2100;
  const row = {
    id: settings.id,
    symbol: wire.GOLDEN_SYMBOL,
    strategy: packageStrategy(legCount),
    displayName: packageName(legCount),
    structureGroup: legCount === 1 ? 'SINGLE_LEG' : 'IRON',
    label: legCount === 1 ? 'SELL 250 PUT Sep 18'
      : 'BUY 240P / SELL 245P / SELL 265C / BUY 270C Sep 18',
    identity: identity(legCount),
    legs: packageLegs,
    qty: QUANTITY,
    price: entryPrice,
    maxProfitCents: extremes.maxProfitCents,
    maxLossCents: extremes.maxLossCents,
    breakevens,
    liquidityScore: legCount === 1 ? 0.83 : 0.78,
    freshness: 'REALTIME',
    warnings: [],
    confidence: legCount === 1 ? 0.76 : 0.71,
    whyConsidered: legCount === 1
      ? 'Income with an explicit acquisition strike and cash-defined downside.'
      : 'Income inside a defined range with protective wings on both sides.',
    bestUpside: legCount === 1
      ? 'The put expires worthless and the full credit is retained.'
      : 'The underlying finishes between the two short strikes.',
    biggestRisk: legCount === 1
      ? 'Assignment at the strike after a severe decline.'
      : 'A finish beyond either protective wing realizes the defined loss.',
    wouldInvalidate: legCount === 1
      ? 'The acquisition thesis no longer supports buying at the strike.'
      : 'The expected range no longer contains the short strikes.',
    beginnerExplanation: legCount === 1
      ? 'Reserve cash to buy shares at 250 while collecting premium now.'
      : 'Collect a credit while two protective wings define the loss.',
    intent: 'INCOME',
    intents: legCount === 1 ? ['INCOME', 'ACQUIRE'] : ['INCOME'],
    assignmentProb: legCount === 1 ? 0.28 : 0.12,
    annualizedYieldPct: legCount === 1 ? 18.6 : null,
    effectivePrice: legCount === 1 ? breakevens[0] : null,
    usesHeldShares: false,
    sharesNeeded: null,
    combinedMaxLossCents: null,
    marketImpliedRisk: goldenFixtures.goldenMarketImpliedRisk({
      priceFingerprint: entryPrice.fingerprint,
      pop: legCount === 1 ? 0.72 : 0.66,
      expectedValueCents: marketEv,
      scenarioProbabilities: STORY_PROBABILITIES,
      underlyingCents: ANCHOR_SPOT * 100,
      cvar95Cents: -extremes.maxLossCents,
      stressLossCents: -extremes.maxLossCents,
      expiration: wire.FAR_EXPIRATION,
      sessions: 45,
      calendarDays: 56
    }),
    evaluation: {
      available: true,
      decisionScore: legCount === 1 ? 76.2 : 71.8,
      viable: true,
      capital: {
        incrementalCents: extremes.maxLossCents,
        economicCents: extremes.maxLossCents,
        returnOnCapitalPct: legCount === 1 ? 1.63 : 78.57,
        annualizedRocPct: legCount === 1 ? 21.2 : 1023.6,
        daysToExpiry: 28,
        basis: legCount === 1
          ? 'Cash reserved at the assignment strike less premium.'
          : 'Defined-risk wing width less the package credit.',
        annualizationNote: 'Annualized from one 28-day holding; not a repeatable yearly return.'
      },
      risk: {
        maxLossCents: extremes.maxLossCents,
        maxProfitCents: extremes.maxProfitCents,
        tailLossCents: extremes.maxLossCents,
        tailMovePct: 0.20,
        scenarios,
        terminalPayoff: {
          schemaVersion: 'risk-terminal-payoff-1',
          modelVersion: 'payoff-curve-1',
          available: true,
          anchorSpotCents: ANCHOR_SPOT * 100,
          anchorPnlCents: math.terminalPnlCents(packageLegs, QUANTITY, ANCHOR_SPOT),
          expiration: wire.FAR_EXPIRATION,
          basis: 'Terminal value at expiration from the exact package entry.',
          entryBasis: 'AFTER_FEE_NET',
          feesIncluded: true,
          points,
          unavailableReason: null
        },
        evHistVolCents: realizedEv,
        evBasisNote: 'Market-implied and realized-volatility lanes are shown separately.',
        jumpTail: {
          schemaVersion: 'risk-jump-tail-1',
          modelVersion: 'merton-jump-mixture-1',
          available: true,
          headlineStance: 'BASE',
          base: {
            stance: 'BASE', dial: 1, pop: legCount === 1 ? 0.68 : 0.62,
            expectedShortfallCents: -Math.round(extremes.maxLossCents * 0.62),
            gapPct: 9, gapLossCents: -extremes.maxLossCents, gapDir: '-',
            atMaxLoss: true, undefinedRisk: false, sector: 'default',
            intensityPct: 24, eventSoon: false, intensity: 0.24,
            jumpMeanLog: -0.0912, jumpSd: 0.0645, bodySd: 0.2814, drift: 0, gap: 0.09
          },
          calm: null,
          tense: null,
          basis: 'Merton jump-mixture real-world (physical) tail.',
          unavailableReason: null
        },
        marketImpliedRisk: goldenFixtures.goldenMarketImpliedRisk({
          priceFingerprint: entryPrice.fingerprint,
          pop: legCount === 1 ? 0.72 : 0.66,
          expectedValueCents: marketEv,
          scenarioProbabilities: STORY_PROBABILITIES,
          underlyingCents: ANCHOR_SPOT * 100,
          cvar95Cents: -extremes.maxLossCents,
          stressLossCents: -extremes.maxLossCents,
          expiration: wire.FAR_EXPIRATION,
          sessions: 45,
          calendarDays: 56
        })
      },
      assessment: {
        mechanics: { eligible: true, reasons: [] },
        economics: {
          verdict: settings.favorable ? 'FAVORABLE' : 'MIXED',
          placement: settings.favorable ? 'WORTH_INVESTIGATING' : 'COMPARE_CAREFULLY',
          label: settings.favorable ? 'Worth investigating' : 'Compare carefully',
          summary: 'Positive realized-volatility economics after executable costs.',
          marketEvAfterCostsCents: marketEv,
          realizedVolEvAfterCostsCents: realizedEv,
          estimatedRoundTripFeesCents: 0,
          marketEvPctOfRisk: marketEv / extremes.maxLossCents,
          realisticEvLowAfterCostsCents: Math.round(realizedEv * 0.45),
          realisticEvHighAfterCostsCents: Math.round(realizedEv * 1.55),
          realisticEvMaterialityCents: Math.round(extremes.maxLossCents * 0.01),
          marketEvRole: 'BENCHMARK',
          realisticEvBasis: 'REALIZED_VOL_AFTER_COSTS',
          observedEvidence: true,
          reasons: []
        },
        coherence: {
          verdict: 'COHERENT',
          directionAssessment: 'The package matches the declared neutral income view.',
          durationAssessment: 'The listed expiration matches the declared horizon.',
          reasons: []
        },
        portfolioImpacts: { practice: null, real: null, notes: [] }
      }
    }
  };
  if (settings.selected !== undefined) row.selected = settings.selected;
  return wire.nonNull(row);
}

function plan(version) {
  return ideaFixtures.planView({
    id: PLAN_ID,
    version: version == null ? 31 : version,
    accountId: wire.ACCOUNT_ID,
    furthestStage: 'DECIDE',
    symbol: wire.GOLDEN_SYMBOL,
    intent: 'INCOME',
    context: ideaFixtures.contextRevision({
      id: 'ctxrev_fixture_new_idea',
      rev: 7,
      thesis: 'neutral',
      horizonDays: 45,
      riskMode: 'balanced'
    })
  });
}

function catalog() {
  return {
    families: ['CASH_SECURED_PUT', 'IRON_CONDOR'],
    catalog: [
      Object.assign({
        name: 'CASH_SECURED_PUT',
        display: 'Cash-secured put',
        category: 'Income',
        summary: identity(1).summary,
        payoffShape: '2,26 26,26 45,8 62,8',
        foundationalRank: 1,
        recommendationEnabled: true,
        scenarioEnabled: true,
        backtestEnabled: true,
        primaryIntent: 'INCOME',
        intents: ['INCOME', 'ACQUIRE']
      }, identity(1)),
      Object.assign({
        name: 'IRON_CONDOR',
        display: 'Iron condor',
        category: 'Income',
        summary: identity(4).summary,
        payoffShape: '2,24 18,24 26,8 42,8 50,24 62,24',
        foundationalRank: 3,
        recommendationEnabled: true,
        scenarioEnabled: true,
        backtestEnabled: true,
        primaryIntent: 'INCOME',
        intents: ['INCOME']
      }, identity(4))
    ],
    templates: []
  };
}

function strategy(candidates, selected) {
  return {
    strategy: {
      runId: RUN_ID,
      state: 'CURRENT',
      inputHash: INPUT_HASH,
      createdAt: wire.OBSERVED_AT_ISO,
      result: {
        candidates,
        rejected: [],
        notes: [],
        strategyRunId: RUN_ID,
        strategyRunState: 'CURRENT'
      }
    },
    selected: selected || undefined
  };
}

function animationTrack(frameCount) {
  return {
    frameRule: 'SELECT_NEAREST_FRAME_NO_INTERPOLATION',
    frameSource: 'underlyingSteps',
    positionFrameSource: 'positions[].steps',
    frameCount,
    sourceStepCount: 211,
    stepsPerDay: 10,
    anchorSpot: ANCHOR_SPOT,
    horizonSessions: 45,
    baselineAtmIv: 0.2814,
    baselineAtmIvSource: 'TRACK_FRAME_0'
  };
}

function positionAnimation(frameCount) {
  return {
    frameCount,
    terminalFrameIndex: frameCount - 1,
    terminalSessionProgress: 45,
    finalOptionExpiration: wire.FAR_EXPIRATION,
    boundaryReason: 'HORIZON_END_OPTION_OUTLIVES_TRACK',
    exposureResolvedAtBoundary: false,
    unavailableReason: null
  };
}

function ensemble(selected, version) {
  const greeks = packageGreeks(selected);
  const frameCount = 11;
  const steps = Array.from({ length: frameCount }, (_, index) => ({
    step: index * 45,
    sessionProgress: index * 4.5,
    sessionDate: `2026-08-${String(3 + index).padStart(2, '0')}`
  }));
  const samples = Array.from({ length: 48 }, (_, pathIndex) => steps.map((step, index) => {
    const trend = (pathIndex - 23.5) * index / 85;
    const wave = Math.sin((pathIndex + 2) * (index + 1) * 0.57) * (0.7 + index * 0.28);
    return Math.round((ANCHOR_SPOT + trend + wave) * 100) / 100;
  }));
  const sourceIndices = samples.map((unused, index) => 100 + index);
  const focusIndex = 24;
  const stepBands = steps.map((step, index) => ({
    step: step.step,
    sessionProgress: step.sessionProgress,
    sessionDate: step.sessionDate,
    p10: ANCHOR_SPOT - index * 3.2,
    p25: ANCHOR_SPOT - index * 1.5,
    p50: ANCHOR_SPOT + index * 0.4,
    p75: ANCHOR_SPOT + index * 1.8,
    p90: ANCHOR_SPOT + index * 3.5
  }));
  const pnlDisplayPaths = samples.map((path, pathIndex) => ({
    sourcePathIndex: sourceIndices[pathIndex],
    role: pathIndex === focusIndex ? 'FOCUS' : 'CONTEXT',
    steps: path.map((underlying, index) => ({
      step: steps[index].step,
      sessionProgress: steps[index].sessionProgress,
      sessionDate: steps[index].sessionDate,
      pnlCents: math.terminalPnlCents(selected.legs, selected.qty, underlying)
    }))
  }));
  const pnlStepBands = steps.map((step, index) => {
    const priceBand = stepBands[index];
    return {
      step: step.step,
      sessionProgress: step.sessionProgress,
      sessionDate: step.sessionDate,
      pnlP10Cents: math.terminalPnlCents(selected.legs, selected.qty, priceBand.p10),
      pnlP25Cents: math.terminalPnlCents(selected.legs, selected.qty, priceBand.p25),
      pnlP50Cents: math.terminalPnlCents(selected.legs, selected.qty, priceBand.p50),
      pnlP75Cents: math.terminalPnlCents(selected.legs, selected.qty, priceBand.p75),
      pnlP90Cents: math.terminalPnlCents(selected.legs, selected.qty, priceBand.p90)
    };
  });
  const underlyingSteps = steps.map((step, index) => {
    const focusPrice = samples[focusIndex][index];
    const atmIv = 0.2814 - index * 0.001;
    return {
      step: step.step,
      sessionProgress: step.sessionProgress,
      sessionDate: step.sessionDate,
      focusPrice,
      atmIv,
      moveFromSpotPct: (focusPrice / ANCHOR_SPOT - 1) * 100,
      ivShiftPoints: (atmIv - 0.2814) * 100
    };
  });
  const positionSteps = pnlStepBands.map(row => ({
    step: row.step,
    sessionProgress: row.sessionProgress,
    sessionDate: row.sessionDate,
    focusValueCents: selected.price.grossPackageNetCents + row.pnlP50Cents,
    focusPnlCents: row.pnlP50Cents,
    greeks
  }));
  return {
    plan: plan(version),
    ensemble: {
      id: ENSEMBLE_ID,
      fingerprint: ENSEMBLE_FINGERPRINT,
      basis: 'PARAMETRIC'
    },
    currency: {
      current: true,
      status: 'CURRENT',
      reason: 'The canonical visual fixture confirms this stored fan is current.'
    },
    preview: {
      paths: 500,
      horizonDays: 45,
      endP50: stepBands[stepBands.length - 1].p50,
      pathModelVersion: 'fixture-path-model-1',
      samples,
      sampleSourcePathIndices: sourceIndices,
      sampleFocusIndex: focusIndex,
      stepBands,
      bands: stepBands,
      receipt: {
        symbol: wire.GOLDEN_SYMBOL,
        worldId: 'observed',
        datasetId: DATASET_ID,
        asOf: wire.OBSERVED_AT_ISO,
        anchorSpot: ANCHOR_SPOT,
        anchorSource: 'FIXTURE_EXECUTABLE_BOOK',
        anchorFreshness: 'REALTIME',
        modelVersion: 'fixture-path-model-1',
        spec: { volAnnual: 0.2814 }
      },
      marketImplied: { atmIv: 0.2814, expiration: wire.FAR_EXPIRATION },
      canvasModel: { valuationStepDays: 7 },
      canvas: {
        displayPathRule: 'TERMINAL_QUANTILES',
        displayPathCount: samples.length,
        displayPathSourceIndices: sourceIndices,
        animation: animationTrack(frameCount),
        underlying: [
          { day: 0, p10: ANCHOR_SPOT, p50: ANCHOR_SPOT, p90: ANCHOR_SPOT,
            focusPrice: ANCHOR_SPOT, atmIv: 0.2814 },
          { day: 45, p10: stepBands[frameCount - 1].p10,
            p50: stepBands[frameCount - 1].p50, p90: stepBands[frameCount - 1].p90,
            focusPrice: samples[focusIndex][frameCount - 1], atmIv: 0.2714 }
        ],
        underlyingSteps,
        positions: [{
          key: `PROPOSED:${selected.id}`,
          proposed: true,
          stepBands: pnlStepBands,
          displayPaths: pnlDisplayPaths,
          days: [
            { focusValueCents: selected.price.grossPackageNetCents,
              focusPnlCents: 0, greeks },
            { focusValueCents: selected.price.grossPackageNetCents
                + pnlStepBands[frameCount - 1].pnlP50Cents,
              focusPnlCents: pnlStepBands[frameCount - 1].pnlP50Cents,
              greeks }
          ],
          steps: positionSteps,
          animation: positionAnimation(frameCount)
        }]
      }
    }
  };
}

function outcome(selected, version) {
  return {
    plan: plan(version),
    ensemble: {
      id: ENSEMBLE_ID,
      fingerprint: ENSEMBLE_FINGERPRINT,
      basis: 'PARAMETRIC'
    },
    outcome: {
      candidateId: selected.id,
      ensembleId: ENSEMBLE_ID,
      ensembleFingerprint: ENSEMBLE_FINGERPRINT,
      basis: 'PARAMETRIC',
      result: {
        paths: 500,
        horizonDays: 45,
        winRatePct: Math.round(selected.pop * 1000) / 10,
        p50Cents: selected.expectedValueCents,
        p5Cents: -selected.maxLossCents,
        bands: [{ p10Cents: -Math.round(selected.maxLossCents * 0.7) }]
      }
    }
  };
}

function decisionPreview(selected, request, version) {
  const greeks = packageGreeks(selected);
  const instruction = request && request.orderInstruction || { type: 'MARKET', timeInForce: 'DAY' };
  const quoted = priceFixtures.packagePrice(Object.assign({}, selected.price, {
    quantity: request && request.qty || selected.qty
  }));
  const fundingClass = selected.identity?.fundingClass || 'DEFINED_RISK';
  const capitalBasis = selected.identity?.capitalBasis || 'MAXIMUM_LOSS';
  const capitalUsedCents = selected.maxLossCents;
  const capitalCapCents = fundingClass === 'CASH_COLLATERAL' ? 5_000_000 : 1_000_000;
  const selectedCapital = capitalUsedCents == null ? null : {
    fundingClass,
    capitalBasis,
    capCents: capitalCapCents,
    usedCents: capitalUsedCents,
    remainingCents: Math.max(0, capitalCapCents - capitalUsedCents),
    overageCents: Math.max(0, capitalUsedCents - capitalCapCents),
    withinCap: capitalUsedCents <= capitalCapCents,
    basis: 'Exact package capital measured by the fixture backend.',
    unavailableReason: null
  };
  return {
    plan: plan(version),
    selected: Object.assign({}, selected, { selected: true }),
    preview: {
      ok: true,
      price: quoted,
      maxLossCents: selected.maxLossCents,
      maxProfitCents: selected.maxProfitCents,
      reserveCents: selected.maxLossCents,
      popEntry: selected.pop,
      breakevens: selected.breakevens,
      blockReasons: [],
      freshness: 'REALTIME',
      evidence: { source: 'FIXTURE_EXECUTABLE_BOOK', lane: 'OBSERVED' },
      payoff: selected.evaluation.risk.terminalPayoff.points,
      analytics: {
        greeks,
        sessionsToExpiry: 20
      }
    },
    evaluation: selected.evaluation,
    guardrails: { level: 'PASS', blockReasons: [], warnings: [] },
    accountFit: selectedCapital ? {
      pctOfNlv: null, pctOfCashBp: null, pctOfMarginBp: null,
      pctOfRiskCapital: null, overRiskCapital: selectedCapital.withinCap ? null : true,
      selectedCapital
    } : null,
    requiredAcks: [],
    ackToken: 'ack_fixture_new_idea',
    order: {
      orderInstruction: instruction
    },
    endorsement: selected.evaluation.endorsement,
    execution: priceFixtures.executionDecision()
  };
}

function scenario(selected, request, version) {
  const base = ensemble(selected, version);
  const pins = Array.isArray(request && request.pathWaypoints) && request.pathWaypoints.length
    ? [] : (request && request.waypoints || []);
  const pathPins = Array.isArray(request && request.pathWaypoints) ? request.pathWaypoints : [];
  const allPaths = base.preview.canvas.positions[0].displayPaths.slice(0, 3);
  const focusSourcePathIndex = allPaths[1].sourcePathIndex;
  allPaths.forEach((path, index) => { path.role = index === 1 ? 'FOCUS' : 'CONTEXT'; });
  const underlyingPaths = allPaths.map((path, index) => ({
    sourcePathIndex: path.sourcePathIndex,
    role: path.role,
    prices: base.preview.samples[index + 1]
  }));
  const position = Object.assign({}, base.preview.canvas.positions[0], {
    displayPaths: allPaths
  });
  const valuationFingerprint = 'valuation-fixture-new-idea';
  return {
    plan: plan(version),
    ensemble: base.ensemble,
    receipt: {
      ensembleId: ENSEMBLE_ID,
      ensembleFingerprint: ENSEMBLE_FINGERPRINT,
      selectedCandidateId: selected.id,
      contextRev: 7,
      worldId: 'observed',
      datasetId: DATASET_ID,
      valuationFingerprint,
      pathModelVersion: 'fixture-path-model-1',
      anchorSource: 'FIXTURE_EXECUTABLE_BOOK',
      anchorFreshness: 'REALTIME',
      interaction: scenarioFixtures.resolveInteraction(request && request.interaction),
      conditioningAssumptions: { horizonDays: 45, waypoints: pins },
      conditioningPathWaypoints: pathPins
    },
    paths: {
      totalPathCount: 500,
      paths: underlyingPaths,
      bands: base.preview.stepBands,
      receipt: {
        sourcePathCount: 500,
        returnedPathCount: underlyingPaths.length,
        sourcePointCount: 211,
        returnedPointCount: base.preview.canvas.underlyingSteps.length,
        withinToleranceCount: 18,
        focusSourcePathIndex
      }
    },
    checkpoints: Object.assign({}, base.preview.canvas, {
      displayPathCount: underlyingPaths.length,
      displayPathSourceIndices: underlyingPaths.map(path => path.sourcePathIndex),
      focusSourcePathIndex,
      modelReceipt: { valuationFingerprint },
      positions: [position]
    })
  };
}

function documents(options) {
  const settings = Object.assign({ primaryLegCount: 4, expectedMove: 'ready' }, options || {});
  const primary = candidate(settings.primaryLegCount);
  const alternate = candidate(settings.primaryLegCount === 4 ? 1 : 4, { favorable: false });
  const candidates = [primary, alternate];
  const market = marketFixtures.marketDocuments({
    symbol: wire.GOLDEN_SYMBOL,
    quote: 'ready',
    history: 'ready',
    chain: 'ready',
    expectedMove: settings.expectedMove,
    news: 'ready',
    newsCount: 20,
    historySessions: 60
  });
  market.chain = marketFixtures.chain('ready', {
    symbol: wire.GOLDEN_SYMBOL,
    expiration: wire.FAR_EXPIRATION,
    strikes: [235, 240, 245, 250, 255, 260, 265, 270, 275]
  });
  market.expectedMove = marketFixtures.expectedMove(settings.expectedMove, {
    symbol: wire.GOLDEN_SYMBOL,
    expiration: wire.FAR_EXPIRATION
  });
  return {
    plan: plan(31),
    candidates,
    primary,
    alternate,
    strategy: strategy(candidates),
    catalog: catalog(),
    market
  };
}

module.exports = {
  PLAN_ID, RUN_ID, INPUT_HASH, ENSEMBLE_ID, ENSEMBLE_FINGERPRINT, DATASET_ID,
  QUANTITY, ANCHOR_SPOT, STORY_MOVES,
  identity, candidate, plan, catalog, strategy, ensemble, outcome, decisionPreview, scenario,
  documents
};
