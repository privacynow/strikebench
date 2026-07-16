# Program TRADER/OWN — definitive feature specification (consolidated handoff)

*Self-contained. Supersedes every prior draft, including the pre-review draft and all three review rounds. This version integrates the full adversarial review: the one-Real-ledger architecture decision, the domain-contract layer, the formula appendix, the resequenced phases, the 16-scenario battery, and the fixtures policy. Every contract in §3 was verified against the current codebase; file anchors are given where an existing engine or precedent grounds the rule.*

---

## 1. What StrikeBench is, and the ground it stands on

Local-first options education, paper-trading, and research platform. Vanilla-JS SPA + Javalin + Postgres, exact-cents money, strict market lanes. Already delivered: the Plan-centered journey (one durable workspace per inquiry — Understand → Evidence → Strategy → Outcomes → Decide → Manage & Review), the two-level ladder (Beginner guided / Expert deeper — never "fewer words"), the tracked real-money book (lots, wash-sale, §1256, TWR/XIRR, exports), and the consolidation program (views split per screen, per-domain controllers, one DecisionPolicy ranker, one executable-price rule, typed schema, generated release evidence, real auth). This program is **assembly on proven engines plus one deliberately scoped rebuild** (the Real-lifecycle consolidation, Phase 6) — justify any fresh build against this inventory first:

| Need | Existing engine (verified) |
|---|---|
| Evaluate an exact user-specified trade | `assessExact` (`EvaluationService` → `StrategyEvaluator` → `EconomicAssessment`) + trade preview; parity gap defined in §6 Phase 4 |
| Legs, stock+option, executed fills, past dates | `Leg`/`LegView`; the package-net precedent: "no fabricated per-leg fill ever persists" (`paper/TradeService.java:1143-1148` — YOUR net price is a curve-level adjustment, legs keep real quotes) |
| Name what legs form | catalog classifier (+ blocked-family teaching) |
| Random & bridged price paths | `PathGenerator` (endpoint-pinned Brownian bridge), `PathEnsembleService`, deterministic `ScenarioSpec` (counter-based `RandomStreams`, seed-addressed) |
| Value a position along a path, per day | `PathValuationKernel` / `ScenarioSimulator`; per-step IV via deterministic `IvSpec` (drift, mean reversion, one event shock) |
| Compare structures on identical paths, w/ cash & buy-hold baselines | identical-paths comparison + baseline machinery (UXR-C) |
| Event dates | `EventService` earnings estimates (SEC filing cadence, ±7-day window, `confirmed=false`, honest `Optional.empty()` on thin history). **Ex-dividend: no source exists** — `Guardrails.exDividendSoon` plumbing is present but hardcoded `false` at both call sites |
| Analogs, sentiment | event-study analog ensembles; keyword sentiment scorer (`SignalEngine` — deterministic, **not yet version-stamped**) |
| Verdicts, budgets, guardrails | DecisionPolicy (sole ranker, `ScoreComposer`), RiskBudgetPolicy (warn + acknowledge, never blocks), Guardrails (hard-blocks undefined risk, blocked families, insufficient buying power at placement) |
| Real-book truth | tracked portfolio (`portfolio_*`, `PortfolioAccountingService`): 5 account types, transactions/lots/matches, wash-sale (correctly taxable-only), §1256, TWR/XIRR, per-account DIVIDEND/INTEREST, CSV import, **idempotency `UNIQUE(account, source, external_ref)`**, stock legs already recordable in its UI |
| Plan receipts | `plan_decision` frozen receipts pinning `context_rev` (`PlanDecisionService`, "receipts stay immutable"); `Plan.ContextRevision` already versions thesis, horizon, target, holdings, risk assumptions with immutable revisions |
| Plan↔trade links | `plan_link` (V22) roles ENTRY/ADJUST/ROLL/PARTIAL_CLOSE/CLOSE/EXTERNAL — **points only at `trades`; no portfolio linkage exists yet** |
| IV history | `option_bar` (typed columns incl. `iv`, provenance `source`/`bid_ask_observed`/`iv_source`/`greeks_source`) fed by `SnapshotService` EOD chain snapshots + `HistoricalOptionsIngest` vendor CSV; `VolatilityProfiler` IV rank/percentile with honest nulls below minimum history |
| Market calendar | `MarketHours` NYSE holiday table 2020–2035 + `sessionsBetween` — exists, **not yet wired into path valuation** (scenario engine counts raw 252-day steps) |
| Sector map | `Universes.SECTORS` — 13 hardcoded sectors + DEMO. Theme classification only; **no correlation, beta, or beta-weighted delta exists anywhere**; cross-symbol portfolio delta today is raw share-equivalent summed |
| Post-trade honesty | calibration ("Your record"), plan_review baselines (cash / whole-share) |
| Lifecycle plumbing | ManagementPlanner, marks/SSE, MarketFrameBroadcaster; `PortfolioAccountingService` already takes a `MarksSource` and tracks `missingMarks` |

**Standing rules, non-negotiable:** retain-and-improve (nothing removed or crippled — proposals, builder, scout, scenario studio, rehearsals, tracked book all keep working and gain); honesty first (evidence badges, "not a forecast," degrade confidence on thin data — never fabricate, never block mechanics); thin data is transient and fixable, never an argument to weaken proposal engines; both levels equally capable; **deterministic only — no LLM features anywhere in this program**; money is exact cents; our data is typed columns, no jsonb; no backwards-compat/URL/alias shims or translated internal records pre-release; the one current schema changes in place and development databases are recreated. New in this version: **no manufactured tax basis, ever** (§3.6); **one authoritative Real ledger** (§3.2); **no numeric netting of practice and real** (§3.13).

## 2. Why this program — the lessons, from real money

I traded my own accounts this month. The product must encode what those trades taught, generically — my trades are fixtures, not the scope.

**MU put campaign / the leg-out.** Sold a $980 put (+$2,000), bought the $970 (−$1,790) making a defined-risk spread, then sold the $970 for +$2,002 *because it was profitable* — not seeing its profit existed because it was hedging. Left an exposed short put I never consciously chose. Then the $980 put, sold at $20, had to be **bought back at $46.50** as MU fell. Campaign truth: **+$2,212 net credit, campaign-adjusted economic basis $957.88** — a number no broker screen showed me. Lessons: *no action may change a position's risk identity silently*; *the campaign, not the fill, is the analytical unit* (the ledger stays authoritative — §3.12); *assignment capital ($98,000) ≠ economic loss*; and *the premium is the mode, the buyback is the tail.*

**NVDA condor.** 20 contracts, ~$1,770 credit, $8,230 max loss — sized by premium dollars, into a probable earnings window. Lessons: *quantity derives from risk budget, not income wishes*; *event exposure is part of the structure*; *defined-risk ≠ appropriately sized.*

**Covered-strangle series.** Long stock + short call (happy-sell) + short put (happy-buy), iterated to a defined-risk variant. Lessons: *composite stock+option structures are their own philosophy with three acceptable outcomes*; *their exposure is piecewise* (100-share / 200-share / capped zones — invisible on standard payoff charts); *leg-in order matters (temporary uncovered exposure).*

**The Vanguard IRA month (the one that reframed everything).** A full statement: a ~$596k semi/tech basket bought and dumped the next day; a $1.18M JEPQ position round-tripped for a loss; INTC sold ~$118 and rebought ~$140 a week later; then a two-day program of **~40 short puts + ~19 short calls across AMD/AVGO/NVDA/MU/INTC/MSFT/GOOGL/AMZN/AAPL/JEPQ, most expiring the same day (8/07)** — ~$1M+ aggregate assignment obligation for ~$25–35k premium. Lessons the *aggregate* teaches that no single ticket does: *this is one leveraged long-semis / short-vol bet, not thirty diversified trades*; *risk lives in concentration × correlation × expiry-clustering*; *the quiet leak was the share-level churn, not the options*; *JEPQ was a strategy collision — paying JPMorgan to write calls, then writing the same calls yourself.*

**The income-vs-accumulation clarification (decisive for the design).** I first framed the short puts as bullish accumulation; on reflection the objective is **income, shares-agnostic**. That single change of stated objective **flips the coherence verdict on the identical book** — under "accumulate," the near-dated far-OTM puts are a low-participation contradiction; under "income," the same book is a coherent short-vol machine. What it does **not** flip: the economics, the implied stance, or the realized-yield honesty — negative EV, excessive concentration, and unaffordable assignment stay exactly what they are under any declared objective (§3.8). Two truths I had to be held to, and the product must enforce: **short premium is not upside participation — it's short volatility** (a short put that expires keeps a fixed fee and misses the run; a short call caps and goes short above the strike); and **"2–3% per 2–3 weeks" is the modal rent (~50%+ annualized if you're naïve about it), not the expected return** — the distribution is rent, rent, rent, fat tail, and the MU $46.50 buyback *is* the tail. The clarification itself happened **mid-life, on reflection** — objective re-declaration is a first-class, prospective event (§3.7, scenario 16).

**The frame:** Portfolio is one bookend (position truth). Research + scenario analysis is the other (market truth). Plans is the workspace between. This program connects the bookends both directions — and adds a *forward* input the product never had: the user's declared thesis and objective.

## 3. Domain contracts

These are the invariants everything else quotes. They land first (Phase 1), as code — enums, tables, and pinned tests — before any surface is built.

### 3.1 Three-axis position model

`HYPOTHETICAL → PAPER → REAL` is **not** one lifecycle — the paper twin proves the states coexist, and the existing `Plan.Status` already conflates a decision outcome (`DECIDED_CASH`) with position states (`POSITION_OPEN`, `CLOSED`) in one enum. Model three independent axes:

- **Analysis artifact:** draft → frozen → retired.
- **Execution lane:** none / practice / real.
- **Position state:** pending → open → partially-closed / assigned / exercised / expired → closed.

"Hypothetical," "practice," and "recorded at broker" are the **user-facing provenance labels** (P1 vocabulary), never a mutually exclusive status enum. A hypothetical is a persistent, deep-linkable, comparable, promotable artifact that touches no book. The existing `Plan.Status` conflation is refactored under this model during Phase 6.

### 3.2 One Real ledger

The tracked portfolio (`portfolio_*`) is the **only authoritative REAL system**. `trades` remains the practice/paper execution ledger — nothing else.

- Plan promotion writes **atomically** to the tracked portfolio and creates a typed Plan link (§3.3). Manage & Review reads the linked tracked structure, never a duplicate `trades.origin='EXTERNAL'` record.
- Broker-owned CSV/text/paste formats enter through a forgiving import boundary. Confirmed exact per-leg fills write current tracked transactions and lots; package-net-only input enters **pending imports** (§3.6), where the package total is fact and missing per-leg prices remain unresolved until the user completes them. No per-leg price or destination account is manufactured.
- The old external persistence path and its records are **deleted** as part of the owning phase. Nothing is translated, rewritten, dual-written, or adapted. The single current schema changes in place and development databases are recreated.

### 3.3 Structure projection — first-class and quantity-aware

The tracked book has instrument-level positions (`PositionView` aggregates lots per contract); it has **no managed-structure concept** — a condor is four unrelated rows. The missing read model is explicit new work:

- **`portfolio_structure`** — stable managed-position identity. Campaigns and Plans reference the structure, not a revision, so a roll never orphans history.
- **`portfolio_structure_revision`** — immutable composition after every entry, partial close, roll, assignment, exercise, expiry, or repair.
- **`portfolio_structure_member`** — lot + allocated quantity + leg role. **Invariant:** the sum of a lot's active allocations across all structures never exceeds the lot's remaining quantity (one 500-share lot may cover a covered call and a covered strangle simultaneously — split allocation is the normal case). Unallocated lots remain valid book positions.
- **`plan_portfolio_action`** — Plan, structure revision, transaction, position receipt, role, timestamp.
- **Book-risk computes from lots directly, never from structures** — incomplete or wrong grouping can neither hide exposure nor double-count it.

A generic pricing input, **`PositionPackage`**, is built from any of **three sources — hypothetical draft (the Phase-3 editor's state), paper trade, or tracked structure revision** — and is the single shape that transformation preview, payoff, marks, greeks, scenarios, participation, and coherence consume. Nothing downstream depends on `trades` rows.

### 3.4 Receipt family

Every position-altering Plan action references a required frozen **`position_receipt`**:

- Kinds: **`DECISION`** (the existing `plan_decision` remains this subtype — no rewrite), **`ADOPTION`** (mid-life entry: freezes the as-is structure and marks at adoption, giving fresh-eyes/campaign lenses and journey-E reviews their baseline), **`TRANSFORMATION`** (every P-preview action applied), **`RESOLUTION`** (§3.6).
- Each receipt freezes: Plan context revision (including the objective revision in force, §3.7), structure composition, marks, evidence, account, timestamp.
- **Symmetric across lanes:** paper-lane transformations write receipts too, lane-recorded — calibration and reviews run on paper, and authored-vs-realized overlays break if the paper lane has receipt-less history.
- No nullable decision semantics, no manufactured decisions for adoption, no rewritten history.

### 3.5 Atomicity cardinality

The invariant is transactional, not rigidly 1:1:

- **Plan-managed single-structure action:** one ledger transaction + one structure revision + one receipt + one `plan_portfolio_action`, committed atomically.
- **Batch/import transaction:** one ledger transaction may produce multiple structure revisions and receipts.
- **Portfolio-only action** (user records activity with no Plan): no `plan_portfolio_action` required.
- **Any failure rolls back the complete artifact set.**

This is what makes "*no action anywhere changes risk identity silently*" **testable**: the pinned test asserts the artifact set exists and agrees, not that a dialog was shown.

### 3.6 Pending imports and resolution authority

Package-net-only facts from Phase-10 broker imports — brokers report spread fills as nets — are quarantined in **`portfolio_import_pending`**, a separate table: `portfolio_transaction` requires an account and complete legs, and everything inside the real ledger stays user-confirmed fact. A pending record holds exact package cash, legs as quoted, fees, timestamp, provenance, and Plan lineage.

- **Idempotency key:** `owner + source system + source-account fingerprint + external reference`. Broker order references collide across accounts; the fingerprint is stable and non-reversible — **raw account numbers never appear in the identifier or the UI**.
- **What pending permits:** campaign-level economic analysis from the exact package net. **What it withholds:** per-leg tax basis, realized tax results, and tax estimates, until resolution. Tax reports state the exclusion explicitly rather than silently omitting; the alert center carries an "unresolved imports" attention item.
- **Resolution has two authorities:**
  - **`BROKER_REPORTED`** — entered from a broker confirmation: eligible to create authoritative per-leg tracked lots.
  - **`USER_ALLOCATED`** — preserves exact package cash and supports economic analysis, but per-leg basis remains **estimated**; tax reporting stays withheld or explicitly provisional until the user attests the entered leg prices are actual fills. A user allocation never silently becomes broker-reported tax truth.
- Resolution creates the real transaction and a **`RESOLUTION` receipt** recording authority, source, resolver, timestamp, package-total reconciliation, and exact-cent equality (allocated legs must sum exactly to the package net).

### 3.7 Objective model (the forward-thesis input)

Everything today measures positions against the *past* or the *market*; nothing represents what the user is trying to achieve going forward. Two scopes, both versioned, neither a new parallel object:

- **Plan objective** — typed extension of the existing immutable `Plan.ContextRevision` (which already versions thesis, `horizonDays`, `targetCents`, `riskMode`, holdings): add **objective** (income / accumulate / hedge / directional / capital-preservation — extensible), **direction**, **target exposure**, and **assignment preference** (avoid / accept / prefer-below-basis / seek). Edits create new revisions via the existing `rev` machinery; decision receipts already pin `context_rev`.
- **Account/book objective** — its own immutable revision series. A book **never infers** its objective from whichever Plan was opened most recently; undeclared means book-level coherence honestly says "no declared book objective," per the `EventService` precedent.
- Campaign, decision, and adoption receipts pin the objective revision in force. **Objective changes are prospective:** new coherence results use the new revision; prior decisions and reviews retain the revision frozen when they were made (scenario 16).
- This is **ground truth** — the product assesses against the user's stated objective, never an imposed one (the income-vs-accumulate flip is why it must be user-declared).

### 3.8 Four-output assessment contract

Every assessment produces four **independent** outputs; objective fit may reorder mechanically and economically comparable ideas but never overwrites economics or guardrails:

1. **Mechanical eligibility** — can this structure exist / be placed in this lane.
2. **Economic assessment** — EV both lanes, fees, risk (the existing `EconomicAssessment` verdict).
3. **Objective coherence** — declared objective vs implied stance (§3.9), direction **and duration** axes.
4. **Portfolio impact** — what this adds to existing exposure, lane-labeled, never netted across lanes.

"**Income strategy: coherent but economically unfavorable**" must be representable. DecisionPolicy remains the sole ranker; the objective lens is a reweighting input to it, not a second ranker.

### 3.9 Stance vector — the shared primitive

Participation, coherence, and book aggregation all consume one derived vector per structure, so separate rule systems cannot drift apart: **delta direction (dollar delta), convexity/gamma, volatility exposure (vega), carry (theta per day, cents), upside tail, downside tail, duration**. Units are fixed once in the formula appendix; the vector is computed from the same pricing kernel as the payoff chart, so no surface can disagree with another. Catalog labels describe; the vector decides. (The corrected teaching: a lone short put's terminal enemy is the downside tail — upside merely forgoes; the *Vanguard book's* enemy is a large move in either direction because the aggregate vector says so.)

### 3.10 Participation, defined

Three distinct numbers, never conflated:

- **Local participation** — position dollar delta relative to an equivalent-share holding, now.
- **Terminal upside capture** — over a named price interval and date ("of a move from $X to $Y by <date>, you keep Z%").
- **Cap/floor points** — where participation changes regime.

Beginner sentences map to these ("you keep ~15¢ of every $1 this stock rises, and nothing above $230" = local + cap). "A short put has ~0% terminal upside capture above spot" is true; "a short put has no directional exposure" is false — the definitions keep both honest.

### 3.11 Recording contracts (the editor's two commands)

One shared leg-entry component, one edited state, **two separately validated commands**:

- **ANALYZE** — permissive: proposed or executed fills, past dates, **missing fills allowed** (analysis proceeds at model marks; the coverage receipt says so).
- **RECORD** — strict: observed price, quantity, multiplier, timestamp, destination account, source; `source=BROKER` requires a stable external reference (the book's existing rule). **The zero-price default is removed for market transactions** — zero is valid only under event-specific validation (valid for `EXPIRATION`; `ASSIGNMENT`/`EXERCISE` validate the stock leg at strike and the option leg at zero; `MARK_TO_MARKET` is model-priced by definition and says so). **Model marks are structurally impossible to write into the real ledger.** "Analyze and record" executes both commands, each validated on its own terms.
- **Duplicate handling is two-tier:** stable broker/import references are the only *idempotent* duplicates (the book's `UNIQUE(account, source, external_ref)` + pending-import key); contract similarity ("matches your existing MU 980P") is *advisory* — "similar position exists: link, add, or cancel," never silent deduplication. Two MU 980 puts may be an intentional scale-in.

### 3.12 Campaign layer contract

A campaign is an **interpretation layer, never the accounting source**. Transactions, lots, and matches remain authoritative; campaign membership groups them analytically and never rewrites basis, tax character, or account ledgers.

- **Typed membership tables** — one for resolved transactions, one for pending imports — with enforceable foreign keys; no polymorphic IDs. Pending members contribute exact package cash only, and any campaign containing them carries a "contains unresolved imports — tax figures withheld" receipt line.
- **Cross-account campaigns require per-account subtotals** (IRA and taxable cash are not fungible); unassigned pending imports render in an explicit "unassigned" bucket.
- **Vocabulary (P1-enforced):** "**campaign-adjusted economic basis**" and "**tracked tax basis**" — never the unqualified "effective basis" where both exist. Wash-sale adjustment shifts tax basis exactly in the re-entry-after-exit pattern campaigns celebrate; the two numbers *will* diverge and must be visibly separate.
- Campaigns reference `portfolio_structure` identities and span lanes (a hypothetical gone real keeps one campaign).

### 3.13 Lane separation

Practice and Real are **never numerically netted**. "Across both books" everywhere in this program means side-by-side, or an explicitly labeled hypothetical overlay. Real obligations, broker cash, and practice exposure cannot share one net-risk total; multiple tracked accounts get account-level stressed obligations before any non-fungible aggregate.

### 3.14 Risk policy by destination

- **Analysis:** nothing blocked, everything labeled — over-budget structures remain visible for learning.
- **Practice placement:** the existing Guardrails hard blocks stay exactly as they are (undefined risk, blocked families, insufficient buying power, data-integrity failures); RiskBudgetPolicy stays warn-plus-acknowledge. "Warn and teach, never block" scopes to budget-derived *quantity*, not to placement guardrails.
- **Real recording:** a historical broker fact is never rejected because StrikeBench would not recommend it. Validation is factual completeness (§3.11), not advisability.

### 3.15 Data honesty contracts

- **Events:** earnings estimates carry their window, basis, and `confirmed=false` — usable for proximity alerts as estimates. Ex-dividend decisions require a sourced calendar provider or remain honestly "**unavailable**" — the warning plumbing exists (`Guardrails.exDividendSoon`); the data is the gap; **never a fabricated date**.
- **Assignment warnings are heuristics, not predictions.** Equity options are American-style; assignment can arrive any business day and can hit one leg independently of its hedge (a first-class Phase-5 transformation case). Extrinsic value and ex-div proximity are warning inputs, not a model.
- **Replay provenance reaches leg-day granularity:** every replayed day distinguishes *observed underlying + vendor-observed option mark* from *observed underlying + model-repriced option*. One overall "historical" badge is insufficient. This largely surfaces `option_bar`'s existing provenance columns.
- **Annualization honesty:** "2.7% over 24 days, ~30% annualized *if repeatable*" — never the bare annualized number.
- **Pricing model disclosure:** valuation is European BSM plus an intrinsic floor standing in for early exercise (`SimulatedWorld.java:27-29`; the `BlackScholes` kernel accepts a dividend yield but every caller passes 0). The canvas and assessments never imply exact American-option paths; the model receipt says what was used.

## 4. Formula appendix (lands with Phase 1, exact cents throughout)

Every formula below is defined, tested, and frozen before any surface quotes it:

1. **Campaign net credit** — signed sum of all campaign option cash (premiums, buybacks, fees signed correctly).
2. **Campaign-adjusted economic basis** — general form through scale-ins, partial assignments, and changing share quantities: (total cash paid for shares − net campaign option cash − dividends received + fees) ÷ shares currently held; the assigned-put special case (strike − net premium per share, the MU $957.88) falls out of it. Always labeled per §3.12, never presented as tax basis.
3. **Realized-vs-headline yield** — one line, mode vs mean, **identical denominators on both sides**. Headline rent = period premium ÷ committed capital for that period. Realized = campaign net P/L (buybacks — the $46.50 — dividends, and *explicitly tagged* interest included) ÷ **peak committed capital** over the campaign window; Expert adds the time-weighted-capital variant. Annualized only with the "if repeatable" label.
4. **Counterfactual benchmarks** — buy-and-hold same capital: cash flows matched to the campaign's external flows, dividends included with a stated reinvestment timing rule; cash benchmark: stated rate, same flow timing. Rendered *during* the campaign's life (reuses plan-review baseline machinery).
5. **Dividend attribution** — dividends on instruments held within the campaign, by holding period.
6. **Interest attribution** — **explicit-tag only**; account-level sweep interest is never auto-assigned to a campaign.
7. **Stance-vector units** — dollar delta; gamma as dollar-delta change per 1% underlying move; dollar vega per vol point; theta in cents/day; tails as scenario loss at named moves (±1σ, ±2σ, and the family's max-loss direction); duration in calendar days to dominant expiry.
8. **Participation** — the three definitions of §3.10, with the named-interval convention for terminal capture.
9. **Scenario ladder** — price → net campaign outcome including all campaign cash.
10. **Churn/whipsaw round-trip cost** — realized exit-to-re-entry cost vs having held, the counterfactual applied to the underlying (the INTC $118→$140 line).
11. **Zero-price validation matrix** — per ledger event type (§3.11).
12. **Package-total reconciliation** — allocated legs sum exactly to package net, largest-remainder in cents, recorded in the RESOLUTION receipt.

## 5. The journeys

- **A. Idea → proposal → paper trade.** Exists; gains the third Decide outcome and the new metrics.
- **B. Proposal → real execution → managed reality.** Today dead-ends at Decide. After: promote with real fills → atomic write to the tracked book + DECISION receipt + typed link → real marks in Manage → protocol/alerts → assignment/expiry handled → campaign closes → review with calibration. Optional **paper twin** to later compare model vs broker.
- **C. Existing broker position → adoption → full analytics** (the biggest gap). Enter or paste an as-is position (20 lots, at a loss, mid-campaign) → adopt → ADOPTION receipt freezes the baseline → **two lenses side by side**: *fresh-eyes* ("would you open this today?" — DecisionPolicy at current prices, sunk-cost-free) and *campaign-anchored* (campaign-adjusted economic basis, scenario ladder) → canvas, protocol, real actions recorded. Mid-life Plans are first-class, not a forced six-stage march.
- **D. What-if → comparison → maybe promotion.** Entered variants compared on the same authored path and in the same ranked table as StrikeBench's own proposals ("yours vs ours"); includes **retrospective** ("enter as-of a past date, replay actual history," leg-day provenance labeled per §3.15).
- **E. Close → review → lesson.** Authored-vs-realized path overlay; protocol adherence (target respected or overridden, and the override's cost); campaign final accounting against counterfactuals; **pattern recognition** across campaigns (CSP-regret and its covered-call-melt-up mirror); a lesson note. Works identically on paper campaigns (receipt symmetry, §3.4).

## 6. Phases

**Phase 1 — Domain contracts + formula appendix.** §3 and §4 as code: the three-axis enums, the four-output assessment shape, the stance-vector type with units, the receipt-family and structure-projection schemas, the pending-import table and keys, the campaign membership tables, the zero-price matrix, and pinned invariant tests (atomicity cardinality, allocation-≤-lot, no-model-marks-in-ledger, no-netting). Nothing user-visible; everything downstream quotes it.

**Phase 2 — Interaction + vocabulary contracts.** Expandable state survives level switches (Record-a-real-trade is one instance — audit the whole class). Small actions produce local updates with the consequence at the point of action (proposed-trade selection is one instance — sweep the class; no corner-toast-plus-mystery-repaint). Beginner keeps leg editing in the visual journey; Expert gets the payoff chart beside the legs. One shared vocabulary component, product-wide sweep, enforced by the explanation audit: the five capital words (**assignment capital / broker reserve / economic exposure / theoretical max loss / scenario loss**), the three provenance words (**hypothetical / practice / recorded at broker**), and the two basis terms (**campaign-adjusted economic basis / tracked tax basis**). Never substituted.

**Phase 3 — The unified leg-entry component ("Your trade").** Fifth Strategy-stage tool and the tracked book's recording form — one component, one edited state, the two commands of §3.11. Beginner: visual entry off the rendered chain/ladder, draggable strikes, manual value fields always available. Expert: **terminal entry** (`-1 MU 13Jul26 980P @20.00`, `+100 MU @979.30`) with one-keystroke visual toggle. Stock + option legs — **a unification task, not a new capability**: the tracked book's UI already records stock; this brings that into the shared editor and replaces the options-only external form. Proposed *or* executed fills with dates; missing fills allowed on the ANALYZE side only; quantity first-class (20-lot); non-100 multipliers; default fee model with override; live catalog identification including honest **"custom structure"** and blocked-family teaching; draft persistence across restart/tabs; two-tier duplicate handling.

**Phase 4 — Assessment parity + the decision metrics.** Entered trades get everything proposals get, via `assessExact` extended to parity (today it returns only the economic verdict/EV/fees + an evidence boolean; the payoff grid, POP, capital profile, volatility profile, full evidence badge, management plan, and decision score are computed in the full pipeline and dropped). Plus, structured by the four-output contract:

- **Participation as a headline metric** (§3.10) — Beginner sentence + Expert profile across the range. On a short put it prints ~0% terminal upside capture; on a short call, capped at strike — quietly refuting "premium = upside participation."
- **Implied-stance label derived from the stance vector** (§3.9), independent of the declared objective.
- **IV-context on debit structures** — buying into 90th-percentile vol gets said out loud, with crush framing from history (the `option_bar`/`VolatilityProfiler` substrate exists; honest null below minimum history).
- **Portfolio impact** — lane-labeled, side-by-side per §3.13, concentration before/after.
- **The two lenses** for as-is positions (fresh-eyes vs campaign-anchored, anchored to the ADOPTION receipt).
- **Data-coverage receipt** on every analysis (observed / delayed / modeled / missing, per input).
- **Annualization honesty** per §3.15.

**Phase 5 — The position-contract layer (transformation preview).** Before any position-altering action on any structure in either lane — leg close, roll, add/remove legs, add/remove stock, **partial close (5 of 20 shows the surviving 15-lot identity)**, assignment (including single-leg assignment against a surviving hedge), exercise, expiry (incl. weekend settlement) — show identity now → identity after, delta in max loss / reserve / obligations, hedge-removal warnings ("this $970 put protects your short $980 put; selling it alone leaves ~$100/point below $960"), blocked-family transitions as teaching cases. **Roll honesty explicit:** every roll preview states "this realizes −$X on the closing leg" and runs fresh-eyes on the new leg — a roll never hides a loss. Built entirely on `PositionPackage` (§3.3) so Phase 6 re-points it at tracked structures with no rework. Applying a preview writes the TRANSFORMATION receipt atomically with the action (§3.5). Invariant, pinned by test: *no action anywhere changes risk identity silently.*

**Phase 6 — Real lifecycle consolidation.** The one deliberately scoped rebuild:

- The structure projection (§3.3) and receipt family (§3.4), with the atomicity contract (§3.5).
- **Promote at Decide** — a third outcome, "I placed this with my broker": ticket pre-fills, user corrects to actual fills/fees/time, **chooses the destination tracked account** (IRA vs taxable — tax lane differs), atomic write + DECISION receipt + `plan_portfolio_action`, and the Plan keeps managing the tracked structure (real marks, protocol, expiry helpers, campaign accounting, BROKER-provenance calibration). Promotion from Demo/Sim market lanes requires complete user-entered broker fills and an observed re-mark; generated prices remain analysis provenance.
- **Adopt into a Plan** — any as-is position (entered, pasted, or already in the book) spawns a Plan entering mid-journey with an ADOPTION receipt; Understand/Evidence backfilled from current data, Strategy holds the as-is structure, Outcomes opens forward, Manage runs the protocol.
- **Objective persistence, both scopes** (§3.7) — lands here, *before campaigns*, so campaign and adoption receipts pin objective revisions from day one.
- **Pending imports + the resolution flow** (§3.6) as permanent product machinery with its queue, per-leg confirm UI, and both authorities.
- **Hard deletion of the external-trade persistence path and its records** (§3.2).

**Phase 7 — Campaign accounting.** A campaign is any linked sequence serving one thesis — rolls, repairs, hedges added/removed, scale-ins, partial closes, **re-entry after exit** (the melt-up mirror), assignment chains; the wheel is one pattern. Auto-link by roll/assignment/plan lineage; manual attach (incl. cross-account, with per-account subtotals). Campaign cash includes dividends and explicitly tagged interest. Every campaign computes net credit, campaign-adjusted economic basis, cumulative P/L, the scenario ladder, and **continuous counterfactual benchmarks** rendered during the campaign's life — all per the §4 formulas. **Realized-vs-headline yield** lives here and is the highest-value income feature: the modal rent beside the campaign's actual realized return net of the losers, mode vs mean, one line, identical denominators. Campaigns span lanes and statuses. Where the objective is accumulation, the **accumulation-progress ledger**: "Target 500 NVDA; acquired via assignment 0; premium collected in lieu $X; underlying moved +Y% while waiting; net: further from target than at start."

**Phase 8 — The scenario canvas (Outcomes upgrade; deepest build).** Author a day-by-day price path to expiration — drag waypoints (builder's learn-by-touching language) or edit the day table; per-day IV at Expert, guided defaults at Beginner. **Fill:** pin any days; for Brownian/GBM models the engine fills between pins with **piecewise bridges — exact conditional sampling** (a mechanical extension of the existing endpoint-pinned bridge); for Heston, jump, Student-t, or block-bootstrap models, pinned fills are **authored guidance plus residual simulation, explicitly labeled "guided interpolation" — never presented as exact conditional sampling**. Pin nothing → existing full Monte Carlo. **Seed** from templates on real inputs: earnings gap ± with IV crush (estimator dates, analog distributions), sector drawdown, drift-to-target, **replay of an actual historical window** (leg-day provenance per §3.15; contemporaneously available data only — no hindsight inputs). **See:** position value per day, each leg's option-price path, greeks evolution, bands when stochastic, **multi-expiry transformation mid-path** (a calendar's front leg settles on the canvas, wired to Phase 5's settlement policy). **Canvas model spec (new work, stated honestly):** trading calendar wired in (`MarketHours` exists; the path engine's raw 252-step counting is replaced with real session dates), rate/dividend/skew/term-structure assumptions declared, **per-expiration/per-strike IV surface evolution along the path** (beyond today's single `IvSpec` process — this is the genuinely new quantitative work), front-expiry settlement and exercise policy, and an **immutable fingerprint + model receipt** on every authored scenario. **Symbol-scope mode:** author one MU path and watch every MU position — shares, condor, short put, both lanes side-by-side — reprice together. **Comparison mode:** two structures / yours-vs-proposed / structure-vs-buy-and-hold on one path. **Retrofit:** existing single-scenario and ensemble UX render inside the canvas — one outcomes design language. Authored paths labeled the user's hypothesis, never a forecast.

**Phase 9 — Objective lenses + coherence + composite catalog.** Assignment preference (now a typed `ContextRevision` attribute) reweighting every assignment-bearing strategy. Composite objectives via generic lens machinery (next objective = data, not code), starting with **income-while-accumulating** (deep-discount puts, income as annualized % of capital deployed with the honesty label, redeploy toggle, NAV-erosion + concentration warnings — backed by Phase 7's measured counterfactuals). Catalog gains **covered strangle**, **stock+covered-call+put-spread**, and **covered-call-with-long-call-overlay** (upside re-entry above the cap) as allowed, named, evaluated structures with **piecewise exposure charts**. And the headline diagnostic — **coherence checking**: the declared objective revision (§3.7) against the stance vector (§3.9), on both the direction and **duration** axes. "Your positions express a **neutral, ~3-week** view; your stated goal is **bullish accumulation over years** — near-dated short puts capture ~15% of upside and won't build the exposure you're targeting." Under a declared *income* objective the same book is coherent and the tool says so — coherence is one of four outputs and never overwrites the other three.

**Phase 10 — the tail, in dependency order:**

- **10.1 Protocols, alert center, actionable dated events.** ManagementPlanner → a user-visible protocol at Decide for every family (profit target with standing close order, loss trigger, delta threshold, event rule, expiration-week rules) — status chips on Manage, trigger lines on the canvas. **Early-assignment risk generalized** beyond ex-div: extrinsic-value-based warnings on any short ITM option (heuristics, labeled per §3.15). **Alert center** — protocol breaches, expiries today, earnings proximity (estimate-labeled), pin risk, assignment events, **unresolved imports** — one SSE-driven surface with a nav badge, and **Home's "needs attention" ordering is driven by it**. Every expiry/earnings row opens the family-appropriate decision helper (close-cheap vs residual; assignment acceptance with campaign-adjusted economic basis, concentration, next-step preview; pin-risk certainty choice). Budget-derived quantity wherever quantity is manual — warn and teach per §3.14.
- **10.2 The book-risk lane** (elevated from a metric to a destination — the Vanguard month proved the risk is in the aggregate). Computed **from lots directly** (§3.3), side-by-side across lanes (§3.13), per-account first: net **dollar** delta / vega / gamma of the whole options book (**beta-weighted delta preferred** — betas computed from `underlying_bar` return series with coverage disclosure; raw share delta is not additive across names and is retired from cross-symbol aggregates); **aggregate stressed assignment capital** ("if the sector falls 10%, your short puts obligate $N vs your $M cash"); an **expiry-concentration calendar** ("$X notional expires 8/07"); **sector/theme concentration** honestly labeled as classification (the 13-sector map is a theme map; true correlation claims require observed return series and say their coverage); **intra-theme contradiction detection** ("you are both long (short puts) and short (covered calls) NVDA/semis — net thematic delta ≈ neutral despite a bullish stated goal"); and **churn/whipsaw accounting** per §4 ("you sold INTC at $118 and rebought at $140 — the round-trip cost $Z").
- **10.3 Regime + history evidence.** Regime lens (trend state, vol richness via VRP/IV-rank on the existing IV-history substrate, drawdown, event proximity) conditioning how every family is framed on every discovery surface — down markets widen discounts and fatten call premium; up markets thin premium, earn chase warnings, and **flag low-participation income structures against the trend** (CSP-regret pre-empted at discovery). Compensation ranking in Scout (premium per unit of realized risk: VRP, gap frequency, earnings proximity, liquidity, capital efficiency) beside the existing ranking. Historical structure-fit: containment scoring for any defined-range structure, percentile strikes, expected-move coverage, evidence badges saying history ≠ forecast. Ex-div early-assignment warnings ship **only** with a sourced calendar provider, else the surface says "unavailable" (§3.15).
- **10.4 Broker imports + batch adoption.** Deterministic per-broker parsers — **structured CSV/text exports preferred before PDF-text paste** (E*TRADE/Power E*TRADE, Vanguard, ThinkOrSwim, Fidelity, Schwab, Robinhood) — filling the Phase-3 editor with per-field verify highlights; **whole-portfolio paste** → multiple structures → batch-adoption wizard (adopt / link / skip per position; batch atomicity per §3.5); package nets route into pending imports (§3.6); **stale-paste handling** (pasted marks never treated as current — re-mark with receipt); mangled input degrades gracefully; nothing auto-submits. **Versioned one-way parser fixtures with quarantine and idempotency tests** — parsers are one-way; "round-trip" is not the contract. No LLM, no network.
- **10.5 Sentiment + Learn.** Per-headline sentiment chips ("keyword-derived," colored, honest), aggregate trend, event-risk feed; **the scorer gains a version stamp** (it is deterministic today but unversioned — persisted sentiment-derived conclusions record scorer version). Learn surface aggregated from the INFO registry + strategy guides with "use this in a Plan" deep links; the explanation audit **fails the build** on any visible label lacking both-level coverage — including every concept this program introduces (participation, implied stance, stance vector, realized-vs-headline yield, campaign-adjusted economic basis, tracked tax basis, campaign, coverage receipt, fresh-eyes, adoption, thesis, objective, coherence, book-risk, churn, waypoint, bridge fill, guided interpolation, pending import, resolution, alert center, the provenance words).

## 7. Scenario battery — the plan is *tested against these by design*

Each is a named acceptance scenario the program must handle end-to-end; the standard for adding any feature is that it survives all of these.

1. **CSP-regret (rising market)** — participation warns at entry (Ph4), counterfactual measures during (Ph7), pattern review surfaces it (E), coherence flags it under an *accumulate* objective (Ph9), canvas proves it (Ph8).
2. **Income framing of the same book** — declared *income* objective flips the **coherence** output to coherent; the other three outputs are untouched — realized-vs-headline yield still shows mode vs mean; the stance vector still says short vol.
3. **Covered-call melt-up (the mirror)** — same machinery, opposite direction.
4. **Leg-out disaster (MU)** — transformation preview + hedge warning (Ph5); campaign truth $2,212 / $957.88 labeled as economic basis (Ph7); the $46.50 buyback in realized yield.
5. **Oversize into earnings (NVDA 20-lot)** — budget-derived quantity (Ph10.1), event rule, canvas gap template.
6. **The Vanguard concentrated book** — book-risk lane (Ph10.2): "one semiconductor bet" (theme-labeled), stressed aggregate assignment per account, 8/07 expiry cluster, intra-theme contradiction, JEPQ strategy collision, INTC churn.
7. **Roll-as-loss-denial** — roll preview realizes the loss + fresh-eyes (Ph5); TRANSFORMATION receipt records it.
8. **Early assignment** — extrinsic/ex-div warnings as heuristics (Ph10.1/10.3), single-leg assignment against a surviving hedge (Ph5), weekend settlement (Ph5).
9. **Adopted loser** — ADOPTION receipt baseline; fresh-eyes vs campaign lenses (Ph4).
10. **IV-crush buyer** — IV-context (Ph4), crush template (Ph8).
11. **Retrospective "what if I had"** — historical entry + replay with leg-day provenance (Ph3/Ph8).
12. **Data-thin symbol** — coverage receipt, honest degradation, mechanics intact (Ph4).
13. **Multi-account reality** — destination account at promote (Ph6); cross-account campaign with per-account subtotals (Ph7); IRA = no tax-character work (correctly suppressed).
14. **Yours vs ours** — entered trade in the ranked comparison beside proposals (Ph4/D).
15. **Thesis-instrument mismatch** — bullish-multi-year objective vs neutral-3-week book → coherence + duration flag (Ph9).
16. **Mid-life objective re-declaration** — change an active campaign from accumulation to income: new coherence results use the new objective revision; prior decisions, reviews, and receipts retain the revision frozen when they were made. Prospective, never rewritten history (§3.7).

## 8. Fixtures

**Fixture policy:** all broker fixtures are **sanitized synthetic statements derived from real formats — the actual Vanguard PDF and any personal identifiers are never committed**. Structured CSV/text export fixtures preferred; statement-paste fixtures second. Parser fixtures are versioned, one-way, with quarantine and idempotency tests.

**Journey narratives (the gate spine):** (B) propose covered call → promote to a chosen account (atomic write + receipt) → real marks → weekend assignment → follow-on covered call → called away → review with overlay, counterfactuals, calibration. (C) paste a ToS-format 20-lot condor mid-life at a loss → batch-adopt (ADOPTION receipts) → two-lens verdict → partial close 5 (surviving-15 preview) → roll remainder on canvas (loss realized visibly, TRANSFORMATION receipt) → protocol breach in alert center → close → lesson. (D) two covered-strangle variants + buy-and-hold baseline on one authored earnings path → one promoted. (E) MU campaign reviewed with overlay + realized-vs-headline yield + lesson. **(V) the synthetic Vanguard-format statement:** parse → package nets land as pending imports → batch-adopt the semi/tech short-premium book → book-risk lane renders "one semiconductor bet," stressed assignment per account, 8/07 cluster, intra-theme contradiction, INTC churn line → both the *income* and *accumulate* objective verdicts on the identical book → resolve one pending import each way (BROKER_REPORTED and USER_ALLOCATED) and observe the tax-withholding difference.

**Edge fixtures:** scale-in with dividends in campaign cash; calendar front-leg expiry mid-canvas; custom-structure honest identity; uncovered short call teaching case; deep-ITM covered call across ex-div (renders "unavailable" without a dividend source); extrinsic-based early-assignment on a deep short put; single-leg assignment leaving a hedge orphan; data-thin adoption; non-100 multiplier end-to-end; duplicate broker order-ref colliding across two accounts (fingerprint disambiguates); contract-similarity advisory vs external-ref idempotency; mangled paste; stale-paste re-mark; package-net-only broker import → pending import → both resolution authorities incl. provisional-tax display and exact-cent package reconciliation; split lot allocation (one 500-share lot covering two structures) incl. the over-allocation rejection; batch import producing multiple structure revisions atomically, with rollback on partial failure; paper-lane transformation receipt; campaign containing a pending member ("tax figures withheld" line + unassigned bucket); MARK_TO_MARKET present in campaign history but never as a plan action; 0–1 DTE at 3pm; symbol-scope canvas with three MU positions; ETF condor containment; debit spread under DIRECTIONAL vs INCOME; missing-fills entry at model marks (ANALYZE) structurally rejected by RECORD; objective re-declaration mid-campaign (scenario 16); non-Gaussian canvas fill carrying the "guided interpolation" label. All at both levels, observed + sim, both lanes; **full matrix green at the end of every phase.**

## 9. Standing decisions (the record of the review)

1. Real promotion from Demo/Sim lanes requires complete user-entered broker fills and an observed re-mark; generated prices remain analysis provenance.
2. Objective fit may reorder mechanically/economically comparable ideas; it never overwrites economics or guardrails.
3. Campaign interest is explicit-tag only; no automatic allocation.
4. Practice and Real are never numerically netted by default.
5. Sanitized synthetic broker fixtures only; no real statements or personal identifiers in the repo.
6. Structured broker exports before PDF/paste parsing; parser fixtures are versioned and one-way, with quarantine and idempotency tests.
7. The tracked portfolio is the sole authoritative Real ledger; the external-trade persistence path and its records are deleted; no translation, dual writes, or compatibility adapters.
8. Package-net facts are never allocated into per-leg tax lots without user resolution; USER_ALLOCATED resolutions never silently become tax truth.
9. Non-Gaussian waypoint fills are labeled guided interpolation, never exact conditional sampling.
10. MARK_TO_MARKET (and DIVIDEND/INTEREST) are ledger events, not plan actions; plan-action roles are ENTRY, ADJUST, ROLL, PARTIAL_CLOSE, CLOSE, ASSIGNMENT, EXERCISE, EXPIRATION.

## 10. The through-line, stated plainly

The product's job is not to hold a market opinion. It is to reflect the user's own declared intent back at them accurately enough — participation, implied stance, realized-vs-headline yield, campaign counterfactuals, book-level concentration, thesis coherence — that they catch the contradiction between what they *say* and what they *hold* before the market catches it for them. And it does this on one honest substrate: one Real ledger, no manufactured facts, every action leaving a frozen receipt. Every capability above serves that one job.
