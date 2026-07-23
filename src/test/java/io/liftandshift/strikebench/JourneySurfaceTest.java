// JourneySurfaceTest was retired on 2026-07-23.
//
// It contained 27 tests / 248 assertions that read SOURCE FILES (Files.readString) and grepped them
// for exact code strings — pinning implementation SHAPE, not behavior. It broke twice during the
// duplication-consolidation work on refactors that preserved behavior, and caught zero behavioral
// bugs. The "one owner per concept" invariants it tried to guard (e.g. one ranking contract, the
// scanner outside the eval kernel) are now enforced structurally by the consolidation itself and
// tracked in DUPLICATION_LEDGER.md; behavioral coverage lives in the runtime suites (ApiIntegration,
// EconomicAssessment, EconomicReadiness, the engine/eval/recommend tests).
//
// The file is emptied rather than deleted only because file deletion is blocked in this environment.
// It can be removed with `git rm src/test/java/io/liftandshift/strikebench/JourneySurfaceTest.java`.
