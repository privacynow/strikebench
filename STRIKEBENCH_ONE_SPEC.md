# Program ONE — unified product specification

*Owner-approved 2026-07-16. Supersedes: the UI/UX portions of `TRADER_OWN_SPEC.md` (its §3 domain contracts,
§4 formulas, and delivered phases 1–5 stand untouched), all of `TRADER_OWN_COURSE_CORRECTION.md`
except the invariant shortlist junior is finishing now, and every prior IA. One spec to run
everything remaining: the UX re-architecture AND TRADER/OWN phases 6–10, folded. Grounded in
the 2026-07-16 surveys: full control inventory, level-flip census, stage-content truth table.*

---

## 1. Why this program

The product failed its own mission test: **it became more complex than the options it exists to
simplify.** The mechanism is named and evidenced: the UI is the org chart. Each program built an
engine and every engine got a *place* — five top tabs plus a plan chip bar (three lenses on one
PlanStore), six stage-rail items, five Strategy tool tabs with tabs inside them, and default
listboxes for every parameter. Retain-and-improve was misread as retain-every-*surface*; the
level ladder forked controls instead of deepening one set; and simulation — the product's crown
jewel — became two apparent *destinations* instead of the fabric.

The truth table proves the fix is cheap on the engine side: the ten destinations perform **four
real jobs** (inspect a market · test a view and a package · commit · review), the worst
"duplications" are literally one function rendering five places, and Evidence/Outcomes already
share one fingerprint-pinned path ensemble byte-for-byte. The complexity is presentational. This
program re-homes surfaces; it deletes no engine and no capability.

**Standing rules carried forward unchanged:** everything in TRADER_OWN_SPEC §1 (deterministic
only, exact cents, typed columns, honesty-first, thin-data never weakens engines, one Real
ledger, no manufactured tax basis, retain-and-improve *of capability*), the §3 domain contracts,
the §4 formula appendix, profit-first tracked book, schema-reset policy. New standing rule:
**every phase ships with a product verdict — walk the primary journey as a novice human and
grade simplicity — separate from and equal to contract compliance.**

## 2. The architecture

### 2.1 Three destinations (plus back-of-house)

- **Desk** — a true home: what needs attention, what to resume, today's argued ideas. Nothing
  else. (Plan chip bar retired; Desk resume cards do that job. Plans-library becomes Desk's
  archive drawer.)
- **Workspace** — THE journey (§3). Public Research dissolves into its open beginning: exploring
  a symbol IS the journey before commitment (the truth table shows Understand already *is* the
  Research renderer — the merge is chrome removal, not surgery).
- **Book** — the portfolio: practice + tracked, profit-first, campaigns' home.
- *Data* stays back-of-house (admin).

### 2.2 The Workspace flow — a document, not tabs

One progressively revealing flow. Sections activate as their inputs exist; earlier sections
stay visible (collapsed to their *conclusions*, not to chrome). No stage rail, no tool tabs,
no tabs-inside-tabs. The six stages and five tools map to five bands:

1. **Your view** (was: scattered across intent chooser / hidden context editor / nothing).
   The first move: symbol, direction chips (up / down / sideways / income — the objective and
   direction vocabulary from TRADER_OWN §3.7), horizon control, conviction, optional target.
   **No silent defaults, ever** — kill the `thesis:'neutral'` fallback (`views.js:127-128`) and
   the Home-card global-context write (`views-plan.js:430-431`). Engine-carried context (scout
   thesis) arrives *labeled as the scout's view, asking for adoption*. Objective revisions
   version per TRADER_OWN §3.7; the coherence engine (folded Phase 9) lives here for the plan's
   whole life: "your positions no longer express this view."
2. **The evidence band** (was: "Evidence" — which the survey measured as ~45% genuine event-study
   evidence and ~45% simulation, mislabeled as one thing). Two honest sub-bands, both framed as
   *interrogating the declared view*:
   - **"Does history agree with you?"** — the event-study/analog machinery with the §4 control
     language (below). Regime lens (folded Phase 10.3) conditions it visibly.
   - **"What could happen from here?"** — the simulation fan, born HERE, at the hypothesis
     (owner directive). This is the ONE ensemble (§2.3). Beginner keeps the story cards +
     horizon chips + Calm/Typical/Wild wildness with the live ±N% preview (the survey found
     this design already good); Expert gets the SAME cards with the parameters revealed and
     editable beneath (level-as-lens, §5) — not a separate 16-field parallel form.
3. **Strategy** (was: five tabs). The **argued-idea hero**: rank-1 rendered fully (payoff at
   size, participation, stance, both EV lanes, POP, capital words, "why this ranked first" from
   the score breakdown, what-could-go-wrong, protocol preview), two runner-up cards, near-tie
   honesty line, and the full ranked field one tap away (Expert: comparison table inline below).
   "Your trade" (the unified editor) and Builder become the hero band's *alternate composers* —
   one shared entry core (course-correction P1.3 direction A lands here), surfaced as "compose
   your own" beside the proposals, feeding the same ranked comparison ("yours vs ours",
   scenario 14). Chain/scout are pickers inside composition, not sibling tabs. Selection is the
   hero's primary action and **advances the flow** — no wholesale panel wipe (`views-plan.js:1028-1083`
   converts to a targeted update).
4. **Outcomes on your structure** (was: Outcomes stage). **The same fan, re-priced** — the UI
   finally says what the server already does ("repriced on the same stored model ensemble shown
   in Evidence", `PlanOutcomeController.java:139`): one visible thread — "your view's futures
   from band 2, now carrying your structure." Market-implied odds, analogs, and rule replay
   render as *lenses on the same artifact*, disabled-with-reason when inputs are missing (never
   hidden). The scenario canvas (folded Phase 8) is this band's deep-dive: "author the path" opens
   the SAME fan in the studio — waypoints, IV paths, symbol-scope, comparison — per TRADER_OWN
   Phase 8's full content, including its model-honesty contracts.
5. **The commitment card** (was: the Decide tab). The survey verdict: Decide's display is ~80%
   re-render; its substance is a *transaction* — fresh executable reprice, qty/limit/fee, guardrail
   blocks, acknowledgments, freeze. So it renders as an inline card at the flow's end with
   exactly that transaction and nothing else. Three outcomes: practice, cash, **"I placed this
   with my broker"** (folded Phase 6 promotion: destination account, atomic write, DECISION
   receipt, plan↔book link). Acknowledgment state persists across level flips and re-renders
   (today it's wiped — flip-census loss point).
6. **The live strip** (was: Manage & Review, the perpetually disabled rail item — which even a
   rehearsal fails to unlock despite its tooltip claiming otherwise; that bug dies with the rail).
   Appears only when something is alive: open position, protocol chips, expiry/assignment
   helpers, campaign accounting line, review-on-close. Contextual, never disabled navigation.

### 2.3 The simulation spine

One ensemble per plan-context, fingerprint-pinned (already true in the engine): born at the view
declaration, quoted by every band, re-priced for structures, authored in the studio, replayed in
review (authored-vs-realized). UI contract: the fan has ONE visual identity threading the flow —
same chart language, a visible "this is the same simulation" lineage chip, and per-claim "how do
you know?" disclosure (novice: a sentence; expert: the distribution). Every number on any band
that derives from the spine can trace to it in one click. Rehearsals and backtests are spine
artifacts, not separate worlds.

### 2.4 The Book

Profit-first per the standing directive: P/L answers first, campaigns (folded Phase 7 — full
TRADER_OWN content: auto-linking, campaign-adjusted economic basis, realized-vs-headline with
identical denominators, counterfactuals live, accumulation ledger) as the Book's center; tax as
quiet derived background (course-correction P1.2 items 1–7 carry forward). Book-risk (folded
Phase 10.2 in full: dollar/beta-weighted aggregation from lots, stressed assignment, expiry
calendar, theme concentration, churn) is the Book's risk lane. Imports + pending-import
resolution (folded Phases 6/10.4) are Book flows. Adoption (Phase 6) enters here or from the
Workspace: any as-is position spawns a flow entering mid-journey with an ADOPTION receipt.

### 2.5 The Desk

Attention (alert center — folded Phase 10.1: protocol breaches, expiries, earnings proximity,
pin risk, unresolved imports — drives ordering), resume (the flows with their next step named),
and today's argued ideas (the teaser density of the one argued-idea component; guardrails:
read-only, one CTA, one destination, one data source). Beginner-tuned first-steps strip; the
tour becomes a guided first run OF the flow (its two load-bearing jobs — level selection and
the rename glossary — move into it).

## 3. Control language contract

Every parameter: its **natural control**, a **plain label that survives "what does that even
mean?"**, and a **live consequence preview**. The survey's conversions (complete inventory in
the survey artifact; these are normative):

- "How selective?" (a client-side macro over per-question thresholds — the engine never sees it)
  → a 3-anchor segmented control ("More examples / Balanced / Only the strongest") that VISIBLY
  moves the threshold numbers beneath it, with a live "would find ~N past examples" count.
- "Market conditions" (real regime conditioning, both levels) → chips, with a live "today:
  ABOVE its 200-day average" indicator and a one-tap "only conditions like today."
- "History to check" → segmented 1y / 3y / All, date range appearing on Custom.
- "Signals" → the per-question trigger inputs, renamed to what they are ("What counts as a
  pullback: __% below the __-day high") — the word "signal" never labels a control.
- Possible-futures: Beginner's story cards / horizon chips / wildness chips stay (they're
  right); Expert = same cards with parameters revealed beneath (model family, drift, vol,
  jumps, paths — the 12 silent beginner defaults become *visible carried values*).
- Product-wide: a listbox is never the default control; every label ships with both-level ⓘ
  coverage; **the explanation audit inventories rendered visible labels against the registry**
  (not just wired terms — closing the `__usedInfoTerms` blindness), and fails the build on
  uncovered labels. "Model-dependent" ceiling and its six render sites get the E.3 treatment.

## 4. Level-as-lens contract

Level is a **pure view function of one state owner per surface**. Mandates from the flip census:
(1) one state owner per surface — no closure-local results (the 11 loss points route onto the
four existing rails: keyed expandables, planUi, server re-hydration, workspace persistence);
(2) level-invariant persistence keys (never summary text, never occurrence index);
(3) every silent beginner default becomes a visible, carried value (12 today: 9 scenario
parameters, 3 tax inputs — beginner sees them as facts with ⓘ, expert edits them);
(4) flipping levels NEVER changes what a button computes, what a fill records, or what state
exists — pinned by test: run each band, flip twice, assert results/selections/acks/drafts
identical. Expert = deeper lens on the same objects: revealed parameters, distributions,
tables below heroes — never different controls, never different outcomes.

## 5. Interaction grammar (carried forward, now product-wide)

Course-correction P0.2 rules 1–6 are permanent law: targeted updates (no wholesale repaints —
including the five residual toast+render sites and the selection handler), consequence at the
point of action, persistent previews with stale-dimming, one-viewport-first by layout (Appendix
B's keep-list discipline; the roll surface's density regression gets fixed in its re-homed
form), no internals in chrome, expandables only for secondary detail.

## 6. What is explicitly retained (the anti-loss inventory)

Every engine and capability, re-homed: event-study/analogs + containment (evidence band), full
Monte Carlo ensemble + Scenario Studio + rehearsals + authored canvas (spine + its studio),
proposals + DecisionPolicy ranking + ranked field + compensation ranking (hero band + Scout
folded into Desk ideas and composition pickers), Builder pedagogy (composer skin), unified
editor two-command core (composer + Book recording), backtests/rule replay (outcomes lens),
calibration "Your record" (Book + review band; BROKER-provenance re-pointing per Phase 6),
tracked book in full (Book), sim worlds/lanes (Data + lane chips), INFO/vocabulary registry
(everywhere), all TRADER_OWN §3 receipts/structures/pending-import machinery (unchanged
underneath). The old six-stage deep-link routes die (pre-release, clean break); the Workspace
flow gets stable section anchors.

## 7. Sequence

- **R0 — Contracts as code.** State-owner/level-lens rails (§4), control-language primitives
  (segmented/chips/slider + consequence-preview components), the label audit rework (§3), flow
  scaffolding (section model, activation, conclusions-collapse), spine lineage chip. Pinned
  tests: flip-without-loss, no-silent-defaults, label coverage.
- **R1 — The Workspace flow, bands 1+2.** View declaration (kills the neutral default), evidence
  band with converted controls, the fan born at the hypothesis. Research index/symbol pages
  become the Workspace's open mode (one renderer already — chrome unification); nav collapses
  to Desk/Workspace/Book(+Data).
- **R2 — Bands 3–6.** Strategy hero + composer consolidation (one entry core), outcomes-as-same-fan
  with lenses + studio deep-dive, commitment card with the third outcome (Phase 6 promotion +
  destination accounts + adoption), live strip replacing M&R.
- **R3 — Desk + Book.** Desk (attention/resume/ideas + tour-as-first-run), Book profit-first
  re-home, plan bar/library retirement into Desk.
- **R4 — Folded depth.** Campaigns (Phase 7) + canvas full content (Phase 8) + coherence/objective
  lenses + composite catalog (Phase 9) — all born into their bands.
- **R5 — Folded breadth.** Alert center driving Desk (10.1), book-risk lane (10.2), regime
  conditioning everywhere (10.3), broker imports + batch adoption + pending-import UI (10.4).
- **R6 — Learn + close-out.** Learn from the registry, sentiment chips (10.5), full battery.

Every phase ends: full matrix + fresh screenshots + **the novice product-verdict walk** + the
Definition of Done (evidence-cited closure per item; no self-certification — course-correction
E.5 is permanent).

## 8. Scenario battery

TRADER_OWN scenarios 1–16 carry forward unchanged (they test engines and honesty, which
survive). New UX scenarios, from the owner's own walk:

17. **Flip-without-loss** — run every band, flip Beginner↔Expert↔Beginner: zero lost results,
    selections, acknowledgments, drafts; identical computations.
18. **The label test** — a novice reads every visible label on the primary journey and can
    answer "what does that mean?" from the screen or one ⓘ; the audit proves coverage.
19. **Trade a view, two interactions, view declared** — search symbol → declare view → hero,
    with the declared view visibly driving the ranking; no imposed defaults anywhere.
20. **One-fan continuity** — the fan seen at the hypothesis is visibly the same object at
    outcomes and in review; the lineage chip traces it; no surface presents a second
    unexplained simulation.
21. **The novice walk** — cold start to committed practice trade in one flow with no dead ends,
    no tab-inside-tab, nothing that requires documentation; graded by a human, not a test.
22. **The expert walk** — same flow, deeper everywhere: parameters revealed, field table, studio
    authoring — and not one control that differs in kind from the novice's.

---

**Through-line, restated for this program:** the engines were always the product; the UI's only
job is to let a person declare what they believe, see it tested honestly, choose a structure
with full attention, commit deliberately, and watch reality answer — in one place, in one flow,
in language they already speak. Simplicity is not a feature of this program; it is the program.
