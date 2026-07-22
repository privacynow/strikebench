# Position Lifecycle & Carry-Honest Engine — Spec

Date: 2026-07-22 · Branch: `feature/journey_refactor` · Status: agreed direction, build not started.
Origin: three-way review of a real $1.57M wheel account (owner + two independent analyses, all
corrections reconciled). This document is the synthesis of record; it supersedes the individual
reviews where they disagree.

## 0. Principles (settled by the review)

1. **The engine only answers "what to open." The daily question is "what to close, reduce, or
   defend."** Position lifecycle management is the missing half of the product.
2. **Unrealized P/L is history, never a hold signal.** "Red therefore keep" must be impossible to
   read out of any surface. Every management view answers the zero-based question:
   *"If this position did not already exist, would its remaining reward, exact quantity, event
   exposure and effect on the whole book justify opening it today?"*
3. **Carry is a decomposition beside EV — never a competing truth.** Gross carry explains the
   P/L experience of a premium seller; after-cost forward EV remains the sole endorsement
   authority. Show both; let neither excuse the other.
4. **Cash truth.** Pledged CSP collateral (at Vanguard-style brokers) stays in the settlement fund
   earning its yield. Three quantities must never be conflated:
   - collateral income (earned regardless of the option),
   - incremental option carry (the premium),
   - liquidity/buying-power encumbrance (what the pledge locks up).
   Closing a put releases encumbrance and removes tail risk; it does not "create" the cash yield.
5. **The redeployment hurdle.** The opportunity cost of a held short option is not cash yield but
   the best carry-per-unit-risk available elsewhere (the universe opportunity scan supplies this).
6. **Carry rates are labeled honestly**: `remaining mark ÷ collateral × 365 ÷ DTE` is
   *gross annualized remaining premium if the option expires worthless* — not a yield, not an
   expected return. It excludes assignment loss, events, spreads, fees, and correlation.
7. **Book risk can override single-position economics.** Attractive residual carry in isolation
   may still warrant REDUCE when concentration, expiry clustering, or event crossings dominate.
   The override must be visible ("carry favorable · book risk overrides"), never silent.
8. **Receipts over verdicts.** Two competent analysts agreed on structure and diverged on metrics.
   Every verdict ships with the inputs that produced it.

## 1. The management receipt (per held short option / package)

| Field | Source |
| --- | --- |
| Captured premium ($ and % of max) | trade basis vs current mark |
| Remaining gross carry, annualized (labeled per §0.6) | mark, collateral, DTE |
| Concurrent collateral income | rates feed (Treasury/MMF proxy) — display only, never added to carry |
| Executable close cost | bid/ask from chain, not mid — mark-vs-executable disclosed |
| After-cost forward EV (from today, on the remaining position) | existing `EconomicAssessment` machinery re-anchored to now |
| Tail loss / expected shortfall | existing jump-mixture tail model |
| Collateral released on close | strike × 100 × qty (puts); n/a for covered calls (upside restored instead) |
| Assignment outcome in shares AND dollars | "900 AMD for $410,000", never just the ticker |
| Event crossings | earnings calendar (§3) inside DTE window |
| Post-assignment factor concentration | book look-through (§4) recomputed as-if-assigned |
| Basis check (covered calls) | strike + collected premium vs tax-lot basis AND wheel-campaign effective basis (from imported transactions); below-basis ⇒ warning, not error |

## 2. Verdict states

`HARVEST · KEEP · REDUCE · DEFEND · ACCEPT_ASSIGNMENT`

- **REDUCE is first-class** (close k of n): multi-contract positions get partial-close proposals
  with the book (cash truth, concentration, scenario loss) recomputed at each k.
- Covered-call language differs from put language: put close = collateral release + tail removal;
  call close = upside restoration. Rolls are always shown as two separate economic decisions.
- Profit-% targets (50–75% rule) are a configurable *policy input*, never doctrine; post-event
  re-evaluation (IV crush, new strikes, executable spreads) precedes any "rewrite" suggestion.
- Willing-to-own (the Plan's `assignmentPreference`) reweights verdicts — but willingness is
  confirmed in quantity and dollars, not symbol-level.

## 3. Event calendar (new data domain)

Earnings dates per symbol, ingested politely (IR pages verified fetchable; goes through
`ProviderPoliteness`, persisted and enriched across runs like all market data). Flyway `V2__…`
migration for the table. Consumed by: management receipts (event crossings), open-side ranking
penalty (contract crosses earnings), expiry-cluster warning (many contracts on one date), and the
Home surface.

## 4. Book gates (wired into BOTH open ranking and lifecycle verdicts)

- **Cash truth line** on the account summary: settlement balance − assignment commitment −
  pending = genuinely free.
- **Look-through concentration**: ETF sleeves (VOO/VTI/VGT/QQQ/JEPQ-style) overlap the same
  mega-cap names; v1 = sector/theme weights per ETF, feeding the existing theme-concentration and
  correlated-shock machinery; the true joint multi-symbol ensemble remains the Phase-2 engine
  milestone.
- Same-theme demotion for new recommendations; expiry-date laddering encouragement.
- Stacked-overlay detection (e.g. writing calls on a covered-call ETF = second overwrite).

## 5. Open-side presentation (the "back to square one" fix)

Every candidate shows the decomposition: *"collects X%/yr gross while the story holds ·
after-cost EV −$Y · you are being paid $Z to insure $W of gap risk"*. FAVORABLE still requires
positive after-cost EV with observed evidence (unchanged, honest); the carry line explains what a
premium seller actually experiences, and the scan's redeployment hurdle (§0.5) gives residual
carry its true comparison.

## 6. Build order (impact-sequenced)

1. **Lifecycle verdicts + receipts** on held positions (backend `eval/` extension re-anchoring
   `EconomicAssessment` to the remaining position; bloom management panel + Home roster chips;
   zero-based banner question). Includes REDUCE with partial-close recompute.
2. **Event calendar** domain + its three consumers (§3).
3. **Cash truth + collateral decomposition** (BookRiskService + account summary line).
4. **Book gates into ranking** (§4) — look-through v1, demotions, cluster penalties.
5. **Carry-beside-EV presentation** (§5) across candidates and held positions.
6. Converges with the parked Home/opportunity-scan build: the scan ranks the universe by the same
   carry-vs-EV decomposition and supplies the redeployment hurdle. (Home architecture decision —
   one-SPA merge vs unified seams — is still the owner's open call.)

## 7. Acceptance sketch (against the real account that motivated this)

Fed the reviewed book, the engine must produce: NVDA P180 → HARVEST (cheap risk removal: ~$47
buys back $18k of tail; encumbrance released); AVGO P330 → HARVEST; QQQ P670×3 → capital-release
candidate (REDUCE/HARVEST with $201k released, presented as a scenario, not "optimal"); AMD ×9 and
INTC ×21 → REDUCE proposals with earnings-crossing penalties and concentration overrides visible;
MU puts / far-OTM calls → KEEP conditional on quantity-confirmed assignment intent; NVDA C220,
JEPQ C60 → DEFEND/ACCEPT_ASSIGNMENT intent decisions (never "keep because red"); INTC C130 →
below-basis warning showing lot basis, campaign-effective basis, and the +24% headroom fact.
