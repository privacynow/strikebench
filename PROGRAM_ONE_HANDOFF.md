# Program ONE — completed-state handoff

Updated 2026-07-17 in `/Users/tinker/output/optin` on `feature/journey_refactor`.
This document records the integrated Program ONE product, its canonical owners, and the
executable evidence that closes the program. The source tree contains the complete Program ONE
implementation. It is not deployed.

The branch tip is the integrated Program ONE state; its frontend, domain services, schema, tests,
documentation, and release evidence must move together. Do not reset individual modules to recover
an earlier milestone. Exact test totals belong to the Surefire/TAP release report generated for the
branch tip; they are intentionally not copied into this handoff.

## 1. Product state

StrikeBench is a local-first options education, practice, research, simulation, and tracked-book
application. Program ONE replaced the old screen collection with four product destinations:

- **Desk** (`#/home`) owns attention, the one active resume action, argued ideas, alerts, and the
  bounded Plan library drawer.
- **Workspace** (`#/research`, `#/research/{symbol}`, `#/plan/{id}/{stage}`) owns the complete
  decision journey. Public Research is its open beginning; a durable Plan becomes one mounted
  document with Your view, Evidence, Strategy, Outcomes, Commitment, and Live bands.
- **Book** (`#/portfolio/...`) owns the Practice book, tracked accounts, construction, imports,
  adoption, campaigns, transformations, aggregate risk, accounting, performance, and tax facts.
- **Data** (`#/data/...`) owns market lanes, sources, datasets, jobs, simulated markets, and
  administration. **Learn** (`#/learn`) is a utility view over the shared explanation and strategy
  registries, not a competing product journey.

The shell and the active Workspace are real SPA owners. Same-Plan stage navigation moves attention
inside the mounted document. Ordinary refreshes update the owning destination or component while
preserving drafts, focus, scroll, disclosures, pending work, and subscriptions. `App.render()` is
reserved for boot, hash navigation, route-error retry, and the explicit no-owner fallback.

The journey begins with explicit user facts. There is no neutral thesis, one-month horizon, risk
posture, objective, account, symbol scope, or scenario model silently substituted for an absent
choice. Research asks in causal order: what just happened, what the user believes happens next,
and over what horizon. Existing Plans quote their frozen declaration instead of asking twice.
Incomplete inquiries remain honest incomplete Plans and open Your view.

Beginner and Expert are presentation lenses over the same state, requests, controls, calculations,
receipts, and capabilities. Beginner leads with plain language and progressive disclosure; Expert
reveals parameters, dense comparison tables, provenance, Greeks, and exact books. A level flip
cannot alter a draft, selection, acknowledgment, simulation input, or result.

The responsive contract covers **2560** CSS pixels on the owner's fully expanded 5K display, plus
2048, 1920, 1440, 1280, 1000, 390, 375, and 320. Wide layouts spend space on useful co-visibility
rather than wider controls or empty tracks. Mobile sequences the same capabilities without hiding
required actions or changing their owners.

## 2. Canonical capability owners

- `StrategyCatalog` is the one server-owned strategy family/template registry. Ranked proposals,
  the exact Builder, custom packages, intent ladders, and Scout selections all enter the same Plan
  strategy competition and decision policy.
- `OutcomeContract` through `POST /api/evaluate` is the forward-outcome kernel contract.
  `PathEnsembleService` is the one path source. Outcomes, proposal comparison, rehearsals, and
  review reuse the stored fingerprinted fan; no second unexplained simulation exists.
- Scenario Canvas extends that same fan with authored waypoints, IV paths, templates, symbol or
  position scope, stored receipts, and honest exact-conditional versus guided-interpolation labels.
  It does not create another simulation engine.
- `TrackedPackageAnalysisService` is the shared exact-package analysis owner used by tracked-book
  analysis and adopted-Plan fresh-eyes review. `PlanAdoptionReviewService` presents current-view
  and campaign-to-date lenses from the same frozen ADOPTION anchor without blending them.
- Broker statement import has one canonical preview/confirm/queue/command API under
  `/api/portfolio/broker-imports`. Deterministic one-way parsers show inferred fields, re-mark
  pasted values, quarantine package-net facts, and distinguish USER_ALLOCATED from
  BROKER_REPORTED authority. Confirmed positions enter the existing batch-adoption owner; they do
  not bypass the tracked ledger or receipt model.
- `CampaignService` owns campaign membership, campaign-adjusted economic basis,
  realized-versus-headline yield, counterfactuals, churn, authored-versus-realized review,
  protocol adherence, pattern evidence, and the owner-scoped lesson.
- `BookRiskService` computes the aggregate risk lane directly from lots: per-account and
  cross-account dollar Greeks, beta-weighted dollar delta with coverage, stressed assignment,
  expiry concentration, theme classification, contradictions, fund collisions, and churn.
- `AlertCenterService` owns attention items and Desk ordering for protocol breaches, expiries,
  event proximity, pin/assignment heuristics, and unresolved imports. It reuses the existing event
  stream and canonical management links.
- `Learn.INFO`, `Learn.VOCABULARY`, `Learn.GLOSSARY`, and `Learn.STRATEGY_GUIDE` are the explanation
  sources. Rendered primary surfaces at both levels are audited against those registries or a
  reviewed plain-language allowlist.

Retired Lab, standalone Decision, ETF-replicator, old Trade-stage, duplicate Plan tools, and
parallel simulation APIs remain absent. New depth belongs in the canonical owners above.

## 3. R0–R6 completion map

| Release | Completed outcome | Primary executable evidence |
|---|---|---|
| R0 | State-owner rails, level-invariant keys, natural choice controls, consequences, flow postures, lineage, rendered-label gate | `dom.test.js`: `Program ONE R0: rails, choice controls, and flow bands honor their contracts`; `Program ONE R0: visible labels are registry-covered or reviewed plain language`; `no-silent-defaults.test.js` |
| R1 | Workspace open mode, explicit view declaration, honest Evidence handoff, fan born at the hypothesis | `dom.test.js`: `Program ONE: public Evidence stages one assumption-safe handoff and creates exactly one Plan`; `PlanDeclarationLifecycleTest`; `PlanStrategyDeclarationTest` |
| R2 | Strategy hero/composers, same-fan Outcomes, Commitment card, live management strip | `PlanApiIntegrationTest#strategyCompetitionIsPlanOwnedNormalizedSelectableAndContextBound`; `#outcomesReuseThePlanEvidenceEnsembleAndPersistSeparateInterpretations`; `#decideFreezesTheServerSelectedPackageAndLinksTradeOrCash`; fixture browser journey |
| R3 | Desk attention/resume/archive ownership, Book re-home, adoption, promotion, one mounted SPA seam | `spa-identity.test.js`; `dom.test.js`: canonical-route and same-hash Workspace tests; `PlanApiIntegrationTest#brokerDecisionPromotesThePlanIntoTheTrackedBookAtomically` |
| R4 | Objective coherence and revisions, composite catalog, objective lenses, campaigns, authored Scenario Canvas | `ObjectiveCoherenceTest`; `CampaignServiceTest`; `ScenarioCanvasTest`; `PlanApiIntegrationTest#unifiedScenarioCanvasPersistsSurfaceTemplateReceiptAndSameEnsemblePositions` |
| R5 | Alerts, Book Risk, regime/history evidence, compensation view, deterministic broker imports, pending resolution, batch adoption | `AlertCenterServiceTest`; `BookRiskServiceTest`; `BrokerStatementParserTest`; `BrokerImportServiceTest`; `PlanAdoptionBatchTest`; `dom-bookrisk.test.js` |
| R6 | Versioned sentiment, searchable Learn surface, full rendered-label audit, adoption two-lens review, responsive/SPA close-out | `NewsSentimentScorerTest`; `SignalEngineTest`; `dom-learn-coverage.test.js`; `dom-learn.test.js`; `adoption-review.test.js`; `dom-audit.test.js`; `scenario-form.test.js` |

## 4. Acceptance scenarios 1–22

The names and outcomes below are the acceptance contract. Each row points to the strongest focused
evidence; the release matrix supplies the cross-suite integration gate.

| # | Scenario and completed behavior | Evidence |
|---:|---|---|
| 1 | **CSP-regret (rising market).** Participation and regime framing warn that fixed premium is not upside ownership; accumulation coherence can disagree; campaign counterfactual/pattern review and the Canvas preserve the truth. | `StrategyEvaluatorTest#shortPremiumParticipationAndRegimePointsDoNotMasqueradeAsUpsideOwnership`; `CampaignServiceTest#crossCampaignPatternsNeedTwoClosedObservedExamplesPerLaneAndNeverPoolRealWithPractice`; Scenario Canvas suites |
| 2 | **Income framing of the same book.** Changing only the declared objective flips only coherence. Stance, economics, and realized-yield truth remain unchanged. | `StrategyEvaluatorTest#theSameShortPremiumBookFlipsOnlyCoherenceWhenItsObjectiveChanges`; campaign denominator tests |
| 3 | **Covered-call melt-up (the mirror).** The same participation and pattern machinery detects capped upside in the opposite direction. | `CampaignServiceTest#crossCampaignPatternsNeedTwoClosedObservedExamplesPerLaneAndNeverPoolRealWithPractice`; participation/cap evaluator coverage |
| 4 | **Leg-out disaster (MU).** Removing protection names the continuing short-put downside; campaign economic basis and realized yield include the buyback rather than hiding it. | `PositionTransformationTest#removingAProtectivePutNamesTheShortPutAndContinuingDownside`; `CampaignServiceTest#campaignAssemblyOverARollChainReportsTheSection4NumbersOnIdenticalDenominators` |
| 5 | **Oversize into earnings (NVDA 20-lot).** Budget-derived quantity blocks unaffordable size, event proximity remains explicit, and the Canvas earnings template uses canonical event windows. | `StrategyEvaluatorTest#gateBlocksInsufficientBuyingPower`; `ScenarioCanvasTest#earningsTemplateUsesCanonicalSecFilingWindowAnalogsNotOrdinaryGaps`; alert/event tests |
| 6 | **The Vanguard concentrated book.** The Book names one semiconductor classification, stressed assignment, the 8/07 cluster, intra-theme contradiction, JEPQ collision, INTC churn, per-account values, and cross-account subtotals. | `BookRiskServiceTest` methods for beta coverage, stress, expiry, concentration, contradiction, collision, churn, and cross-account aggregation; `dom-bookrisk.test.js` |
| 7 | **Roll-as-loss-denial.** Roll preview realizes the loss, requires fresh eyes after risk changes, and records one TRANSFORMATION receipt. | `PositionTransformationTest#rollStatesTheRealizedLossAndRequiresFreshEyesAfterRisk`; `PositionTransformationApiTest#canonicalRollClosesAndReopensAtomicallyWithOneFrozenBeforeAfterReceipt` |
| 8 | **Early assignment.** Extrinsic/fee and ex-dividend availability are labeled heuristics; assignment can leave a surviving hedge; weekend lifecycle facts remain explicit. | `AlertCenterServiceTest#earlyAssignmentWarnsOnShortItmOptionsWithExtrinsicBelowFeesAndSaysExDivUnavailable`; `PositionTransformationApiTest#earlyAssignmentUsesTheSignedTransformationPathAndKeepsTheHedge`; `PositionTransformationTest#assignmentCanLeaveTheOtherHedgeVisibleInsteadOfPretendingTheStructureVanished` |
| 9 | **Adopted loser.** ADOPTION freezes the baseline; fresh-eyes current-view and campaign-to-date lenses remain separate and trace to that anchor. | `PlanAdoptionReviewServiceTest#twoLensesShareTheExactAdoptionAnchorButKeepCurrentAndCampaignTruthSeparate`; `adoption-review.test.js` |
| 10 | **IV-crush buyer.** Debit-IV context carries its evidence limits; Canvas IV nodes and event templates show the modeled crush path without presenting it as observed fact. | `StrategyEvaluatorTest#debitIvWarningAndAnnualizationCarryTheirEvidenceLimits`; `ScenarioCanvasTest#ivNodesInterpolateThenEvolveStrikeAndTermSurface`; earnings-template test |
| 11 | **Retrospective “what if I had”.** Historical entry/mark/exit use one no-look-ahead timeline and disclose where observed option leg-days end and modeled values resume. | `JourneySurfaceTest#everyHistoricalReplayUsesOneNoLookAheadTimeline`; `HistoricalReplayKernelTest`; `ScenarioCanvasTest#shorterHistoricalReplayNamesWhereObservedClosesEndAndModelResumes` |
| 12 | **Data-thin symbol.** Missing daily history lowers evidence confidence and names the limitation; mechanical evaluation remains intact. | `StrategyEvaluatorTest#missingDailyHistoryIsAnEvidenceLimitationNotAMechanicalFailure`; AutoRecommender history-honesty coverage |
| 13 | **Multi-account reality.** Promotion freezes the chosen destination account atomically; campaigns and Book Risk keep per-account/cross-account views; retirement wrappers suppress current tax characterization. | `PlanApiIntegrationTest#brokerDecisionPromotesThePlanIntoTheTrackedBookAtomically`; Campaign and Book Risk cross-account tests; portfolio tax tests |
| 14 | **Yours vs ours.** An exact Builder/custom selection competes beside server proposals and the cash baseline on one stored fan. | `PlanApiIntegrationTest#exactBuilderSelectionCompetesBesideServerProposalsOnOneStoredFan`; Strategy/Canvas browser acceptance |
| 15 | **Thesis-instrument mismatch.** Opposite direction and a structure expiring before the declared horizon produce plain coherence/duration findings without rewriting economics. | `ObjectiveCoherenceTest#oppositeDirectionIsIncoherentAndSaysSoPlainly`; `#structureExpiringBeforeTheDeclaredHorizonIsIncoherentOnDuration` |
| 16 | **Mid-life objective re-declaration.** New evaluations use the newest revision; past decision, adoption, review, and receipt facts retain the revision in force when recorded. | `PositionArtifactStoreTest#decisionAndAdoptionReceiptsFreezeTheObjectiveRevisionInForceProspectively`; objective revision/evaluator tests |
| 17 | **Flip-without-loss.** Beginner → Expert → Beginner retains state, selections, drafts, acknowledgments, path inputs, lineage, and results. | `dom.test.js` R0 rail test; `scenario-form.test.js` level-lens tests; SPA identity suite |
| 18 | **The label test.** Visible labels on real Strategy, Outcomes Canvas, Book Import, and Book Risk surfaces at both levels are either registry-backed or reviewed plain language. | `dom.test.js`: `Program ONE R0: visible labels are registry-covered or reviewed plain language`; `dom-learn-coverage.test.js` |
| 19 | **Trade a view in two interactions.** Symbol search and explicit declaration lead to the argued hero; absent facts stay absent and block ranking with named requirements. | `no-silent-defaults.test.js`; `PlanStrategyDeclarationTest`; `DecisionDeclarationPolicyTest`; public Evidence browser handoff test |
| 20 | **One-fan continuity.** Evidence, Outcomes, comparison, rehearsal, and review share one stored ensemble fingerprint and lineage; folding or navigating does not replace the fan node. | `PlanApiIntegrationTest#outcomesReuseThePlanEvidenceEnsembleAndPersistSeparateInterpretations`; `#storedEnsembleRestoresByteCompatiblyForTheCurrentContext`; `dom.test.js`: `the evidence fan survives attention moves without re-rendering` |
| 21 | **The novice walk.** Cold start reaches a committed Practice trade through one progressively revealed Workspace without nested tool tabs, dead ends, or documentation dependence. | Fixture browser journey and owner review artifacts in `dom-tests/shots/`; flow-density, declaration, strategy, outcomes, and commitment acceptance tests |
| 22 | **The expert walk.** The same journey exposes deeper parameters, comparison field, exact book, and Canvas authoring without changing control kinds or calculations. | `scenario-form.test.js`; Strategy/Builder Expert browser coverage; `dom-audit.test.js`; owner screenshots in `dom-tests/shots/` |

## 5. Journey evidence map

### B — proposal through tracked campaign review

Covered call proposal uses the canonical Strategy owner; broker recording promotes the frozen Plan
into the chosen tracked account atomically; real marks and signed lifecycle transformations handle
assignment, the follow-on covered call, and called-away shares; Campaign review preserves the
scenario overlay, counterfactuals, calibration lane, and lesson. Evidence:
`PlanApiIntegrationTest#brokerDecisionPromotesThePlanIntoTheTrackedBookAtomically`, assignment and
close cases in `PositionTransformationApiTest`, and
`CampaignServiceTest#closedCampaignReviewKeepsExactScenarioLineagePricesProtocolOverrideAndOwnerScopedLesson`.

### C — ToS condor import, adoption, management, and lesson

The deterministic parser previews the 20-lot package, pending facts enter the one import queue,
confirmed lots batch-adopt with ADOPTION receipts, Manage shows fresh-eyes and campaign lenses,
partial close preserves the surviving 15-lot identity, roll records the realized loss and
TRANSFORMATION receipt, alerts surface protocol breach, and Campaign close records the lesson.
Evidence: `BrokerStatementParserTest`, `BrokerImportServiceTest`, `PlanAdoptionBatchTest`,
`PlanAdoptionReviewServiceTest`, `PositionTransformationTest#partialCloseNamesTheSurvivingFifteenLotIdentityAndRiskDelta`,
roll/alert tests, `adoption-review.test.js`, and Campaign review coverage.

### D — authored earnings comparison

Two covered-strangle variants, an exact user composition, proposals, and the cash/buy-and-hold
baseline are evaluated on the same authored earnings fan; the promoted selection keeps that
fingerprint. Evidence:
`PlanApiIntegrationTest#exactBuilderSelectionCompetesBesideServerProposalsOnOneStoredFan`,
`#unifiedScenarioCanvasPersistsSurfaceTemplateReceiptAndSameEnsemblePositions`, and
`ScenarioCanvasTest#earningsTemplateUsesCanonicalSecFilingWindowAnalogsNotOrdinaryGaps`.

### E — MU campaign review

The closed MU campaign keeps authored-versus-realized overlay, realized-versus-headline yield on
one denominator, protocol override cost, counterfactuals, lane separation, pattern evidence, and
the owner-scoped lesson. Evidence:
`CampaignServiceTest#closedCampaignReviewKeepsExactScenarioLineagePricesProtocolOverrideAndOwnerScopedLesson`,
`#campaignAssemblyOverARollChainReportsTheSection4NumbersOnIdenticalDenominators`, the cross-pattern
test, and the campaign close browser acceptance in `dom.test.js`.

### V — synthetic Vanguard statement

The sanitized fixture preserves packages, accounts, and the August cluster without personal facts;
preview and confirm distinguish exact versus pending packages; batch adoption creates the semis/tech
short-premium book; Book Risk renders the aggregate; income and accumulation objectives change only
coherence; USER_ALLOCATED remains provisional while BROKER_REPORTED attestation creates canonical
lots and tax-bearing history. Evidence:
`BrokerStatementParserTest#sanitizedJourneyVPreservesPackagesAccountsAndTheAugustClusterWithoutInventingFacts`,
`BrokerImportServiceTest#userAllocationIsReceiptOnlyThenBrokerAttestationCreatesCanonicalLotsAndHistory`,
`PlanAdoptionBatchTest`, `BookRiskServiceTest`, `StrategyEvaluatorTest#theSameShortPremiumBookFlipsOnlyCoherenceWhenItsObjectiveChanges`,
and `dom-bookrisk.test.js`.

## 6. Verification and release evidence

Start PostgreSQL and generate a clean backend artifact before browser work:

```bash
docker compose up -d db
mvn -q clean package
```

Install and run the complete non-network browser matrix:

```bash
cd dom-tests
npm ci
npx playwright install chromium
npm run test:ci
```

The current browser matrix comprises explicit-declaration/defaults, Scenario form, SPA identity,
fixture journey, grown-state, responsive audit, auth-on, Book Risk, adoption review, and Learn
coverage/route suites. The observed-provider suite is separate because it exercises live Cboe and
EDGAR behavior:

```bash
cd dom-tests
npm run test:live
```

CI writes each suite's TAP output under `target/dom-*.tap`, then runs:

```bash
node scripts/release-matrix.mjs
```

`target/release-matrix.md` and the CI summary are authoritative for the exact branch tip. Run
`mvn -q clean package`, not an incremental test, after test classes are renamed or removed. Do not
rebuild the jar while a browser suite is using it; the running app correctly rejects a changed jar.

## 7. Operational boundary

**Deployment is the one remaining operational action.** The feature branch is not deployed to the
production host. Deployment requires the deliberate production host/data session, backup, atomic
jar installation, restart, and health verification described in `DEVELOPER.md`. Auth-on production
also requires the configured Google OIDC client secret and exact callback registration. Those are
deployment inputs, not missing product implementation.

Before deployment, generate the release matrix from the exact branch tip and retain its screenshots
and reports. After deployment, verify `/api/health`, authentication when enabled, the four primary
destinations, and an observed-data read without mutating tracked records.
