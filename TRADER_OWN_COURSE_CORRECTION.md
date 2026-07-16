# TRADER/OWN course correction — full guidance (supersedes the interim one-pager)

*Owner-directed, 2026-07-16 ~00:10. Read at your next commit boundary and re-sequence the night
around it. **Re-read this document at every commit boundary.**

# ⛔ SESSION STAND-DOWN — owner-directed (07-16 ~12:30). Final order for this session.

Your mandate has ended with the owner's thanks — the invariant shortlist verified complete
(legacy endpoints deleted, protocol preserved, tests landed) and the correction craft was good.
**Make no further changes of any kind to this repository.** Program ONE is being executed by
the senior session. If you are reading this: stop, commit nothing, idle.

# ⛔ PROGRAM PAUSE — owner-directed (07-16 ~11:20). Read before anything else.

The owner has ordered a **whole-product UX re-architecture**. **The specification has landed
and is owner-approved: `STRIKEBENCH_ONE_SPEC.md` at the repo root ("Program ONE").** It
supersedes the UI/UX portions of this document, all unfinished appendix items except the
shortlist below (they are absorbed into Program ONE's bands), and the surface work of phases
6–10 (folded into Program ONE R4–R6). Your 11:37 selection commit (bfcd842) was work this pause
had already frozen — stop there; that surface is redesigned in Program ONE band 3 and your
guided-decision work will inform it.

**Sequence for you now:**

**FINISH ONLY this invariant shortlist** (IA-independent; valuable regardless of what the UI
becomes):
1. D.1 — delete the legacy receipt-less endpoints (`TradeRoutes.java:33-35`,
   `PlanRoutes.java:89-91`) and the silent `TradeService.settle` path. Your 11:01 receipts
   commit is on the right track — complete it.
2. E.1/C.2 — both missing regression tests (controller-boundary degraded preview; stale-selection
   dom test).
3. C.3 — preserve `protocolBySymbol` across context-rev bumps; make the "Evidence setup kept"
   banner truthful; test it.
4. Close out per E.5: full matrix green, screenshots regenerated for changed surfaces, evidence
   cited in commit bodies.

**Then begin Program ONE at R0** (`STRIKEBENCH_ONE_SPEC.md`). Do NOT do any further surface
work in the current IA and do NOT resume the old phases 6–10 — they are folded into Program
ONE. Every engine and capability is retained — Program ONE re-homes surfaces, it deletes
nothing you built underneath. Program ONE's Definition-of-Done discipline (evidence-cited
closure, full matrix, fresh screenshots, novice product-verdict walk per phase) applies from R0.

Status 07-16 ~10:50 (context, still true): the seven morning commits were reviewed — the
correction craft was verified good; Appendix D gates phases 6–10; Appendix E holds the
verification findings. Phases 1–5 were independently audited: the domain contracts, receipts, atomicity,
parity, and tests are strong — keep that standard. This document corrects the UI/UX side and a
small set of behavioral regressions, then returns you to the spec sequence. **Nothing here
removes a feature. Retain-and-improve throughout.** Work P0 first, then P1, then resume phases
6–10 (P2). The owner evaluates the result in the morning; leave the tree committed at a clean
boundary with every commit message disclosing everything it does — deletions included.*

---

## P0.1 — Behavioral fixes (small, do first; phases 6–7 build on these paths)

1. **Never block mechanics** (regression in the parity commit): a failed decision assessment must
   not withhold the mechanical preview. A missing underlying quote currently throws
   `DataUnavailableException` and blanks the entire preview — restore
   preview-with-degraded-verdict, per the standing honesty rule (degrade confidence, never block
   mechanics).
2. **Silent non-selection** (from 219254d): when "Analyze and use in this Plan" doesn't pass the
   mechanical preview, say plainly that the package did **not** become the Plan's structure, and
   clear the stale prior selection — `PlanStrategyService.java:259-267` currently leaves the old
   package `selected=1`, so Outcomes/Decide evaluate a structure the user is no longer looking
   at. The Builder path's explicit error is the model; the editor's soft "Analyzed with
   constraints" is not enough.
3. **Stage-gating honesty:** apply the existing locked-with-reason pattern
   (`views-plan.js:662-671`, currently used only for Manage & Review) to Outcomes and Decide when
   prerequisites are missing — reason text like "Unlocks after you select a structure in
   Strategy", driven by the `furthest_stage` the server already tracks
   (`PlanService.java:207-231`). Render the four Outcomes lenses **disabled-with-setup-CTA
   instead of hidden** (`outcomes.js` `mode.available` supports this). Label the lens
   "Possible futures (Monte Carlo)".
4. **Stale-vs-never selection:** the Outcomes prerequisite card must distinguish "no structure
   was ever selected" from "your selection went stale when plan assumptions changed"
   (`selectedCandidate` is keyed to `context_rev`, `PlanStrategyService.java:279-289`) and offer
   "Reprice the prior structure" in the stale case. Also stop wiping evidence-stage disclosure
   state on a context-rev bump (`views-research.js:1766-1770`) without explanation.

## P0.2 — UI grammar (binding on ALL surfaces from now on, new and existing)

These are your own Phase-2 contracts from f88730e, enforced:

1. **No wholesale repaints on small actions.** Never `root.innerHTML=''` for a field change.
   Update the affected region; preserve focus, scroll, expanded state, and partial input.
2. **Consequence at the point of action.** Every mutation renders its result adjacent to the
   control that caused it, or scrolls it into view with focus and a brief arrival highlight
   (in-house patterns: `views-portfolio.js:125`, `views-plan.js:1331-1337`). No
   corner-toast-plus-repaint, anywhere.
3. **Preview panels persist.** Never unmount a chart because state is transiently unpriceable —
   keep the last good curve dimmed with a "stale — re-analyze" note.
4. **Chart beside legs at both levels; one-viewport-first.** Primary answer + primary action
   visible without scrolling at 1280×800; receipts collapse to headline + expandable.
5. **No internals in chrome.** No version/rev counters, raw enums, provider ids, or token traces
   on screen. Every visible label gets both-level INFO-registry coverage, and the explanation
   audit must be extended to fail on uncovered labels in the new surfaces (it currently doesn't
   see them).
6. **Expandables are for secondary detail only — never for primary content, and never to pass a
   density check.** Owner directive: on desktop especially, space is for content — a collapsed
   section beside empty horizontal space is a design failure, not real-estate optimization. If a
   user must click to see the primary answer or a regular-use control, the default is wrong.
   Density (rule 4) is achieved by LAYOUT — columns, grids, deduplication, hierarchy — not by
   collapsing more. Legitimate expandable content: provenance receipts, raw internals,
   teach-me copy, long secondary lists. Mobile earns collapse more often, but by the same test:
   ordering and progressive layout first, accordion only for genuinely secondary detail.

## P0.3 — Editor and workbench retrofit (the systemic fix; this is the bulk of the night)

Root cause to eliminate: the repaint-the-world grammar in `position-editor.js` and its five
mounts, plus the route-level `App.render()` swaps.

- **Repaint architecture:** split `paint()` into targeted region updates (legs list, meta, chart,
  commands). Unwire full repaints from: every select (`:436-450`, `:555-563`, `:841`, `:849-851`,
  `:859-860`), mode toggle (`:384-392`), remove/add leg (`:481`, `:832-834`), chain clicks
  (`:590-591`), drag commit (`:519-521`), "Apply lines" (`:823-827`), and the async
  `ensureMarket()` completion (`:403`, `:407`) which currently rebuilds the editor under the
  user's hands. Stop wiping the results panel on every keystroke (`remember()` at `:356-364`) —
  dim it "stale" in place instead. Stop `body.innerHTML=''` on Strategy tool-tab switches
  (`views-plan.js:1429`) discarding editor closure state, and stop forcing scroll-to-top on every
  hashchange (`app.js:1117-1122`).
- **Add-leg feedback:** incremental row insert + focus its first input + `scrollIntoView` + brief
  highlight. Same for chain-rail clicks that add legs.
- **Chart persistence + pricing:** replace the all-or-nothing `provisionalDraft()` (`:305-325`)
  with per-leg partial pricing (draw what is priceable; annotate what isn't), and run
  chain-midpoint pricing for blank fills in **all** entry modes, not just visual
  (`:508-513` gate) — Expert defaults to terminal and currently can never auto-price.
- **Beginner layout:** make the two-column legs+chart workbench the default at both levels
  (`app.css:602` vs the Expert-only `:608`); Beginner keeps its guided ordering inside the left
  column. First paint fits 800px height.
- **Remove/TYPE collision:** stop absolutely positioning `.position-leg-remove` into the grid
  corner (`app.css:621`) — give each leg a proper header row (leg label + Remove grouped
  together). In the narrow roll/adjust workbenches it currently **overlaps** the TYPE label and
  the 5×110px grid (`app.css:620`) clips the price field — fix both.
- **Record form:** changing "What happened?" must not wipe entered legs
  (`views-portfolio.js:1255-1299`) — preserve compatible legs or confirm before discarding.
- **Density:** every TRADER/OWN surface measures 3.5–7.2 viewports at 1280×800 (roll 7.2 desktop
  / 10.4 mobile; record 4.9; Beginner editor 4.6). One-viewport-first everywhere: headline
  answer + primary action above the fold, the commit CTA visible without scrolling (sticky
  action bar where needed). **Achieve this with layout — use the horizontal space, merge
  duplicated panels, cut chrome bands — NOT by collapsing content** (P0.2 rule 6). Receipt
  *detail* (provenance, per-dimension internals) may collapse; the receipt's headline verdict
  and anything the user acts on may not.
- **The 17+ toast-and-repaint mutation sites** (record save `views-portfolio.js:1323`, CSV import
  `:1390`, partial-close `:119`, adjustment `:476`, unwind `:72`, settle `:2543`, and the rest):
  local post-mutation updates per P0.2 rule 2.
- **Decide surface:** the affirmative action must look enabled and sit with its alternative in a
  visible decision bar — not last after a 1.5–2-viewport receipt stack
  (`views-plan.js:2306-2387`).
- Smaller: terminal-mode chart goes stale while typing (annotate or re-parse on debounce); leg
  legend duplicates the selects (`:478`); chain Action select scope is confusing (`:560-563`);
  the roll actions row gives six chips equal weight including a red Void — separate destructive
  actions; expandable state keys on occurrence index and can restore the wrong rows after list
  mutations (`ui.js:299-346`) — key on stable ids where available.

## P1.1 — Jargon and explanation-registry sweep (existing surfaces)

- Delete the "Plan v1 · Context r1 · status" chrome — it renders **twice** per Expert plan screen
  (`views-plan.js:639-642` and `:875-878`). Version counters are transport internals; they live
  in receipts only.
- Translate raw enums wherever they reach the screen: "Economic verdict UNFAVORABLE"
  (`position-editor.js:631`, `:659` — label maps already exist at `views-plan.js:1128`);
  Beginner position rows "OBSERVED · REALTIME · yahoo" (`views-portfolio.js:958` — use
  `UI.evidenceBadge`, `ui.js:160-183`); frozen decision panels' lowercased internals
  (`views-plan.js:2195-2212`, `:2512`); "server-owned strategy catalog", "idempotency key", and
  "Alt+V" surfacing on touch/Beginner.
- Fix the Phase-2 debts: the surviving "Total worst case" substitute label; "economic capital"
  (`CapitalProfiler.java:23`) and "assignment exposure" (`StanceProfiler.java:127`) — both are
  near-miss substitutes for the P1 vocabulary; wire the five registry-only vocabulary words onto
  the surfaces that should carry them; make catalog identification live-debounced while editing
  (the spec says live; update your pinned test).

## P1.2 — Profit-first tracked book (owner directive; audited item list)

The tracked book exists to answer **"what is my profit?"** The alignment target is the stance the
repo itself already decided (DEVELOPER.md:72-75, TaxRules.java:5-9): *record tax-relevant facts
permanently; disclose derived tax characterization quietly — source-cited, reviewed-year-gated,
never manufactured, never a workflow the user must configure or operate.* The engine honors this;
the presentation overshoots it. Note: nearly all of the overshoot is inherited from the Jul 13-14
tracked-portfolio era — your commits today moved TOWARD the stance (vocabulary components, the
collapsed 1256 expandable, corrected copy). This section re-weights those inherited surfaces.
Keep every engine capability and disclosure. Target: a Beginner recording one simple trade meets
~7 required facts (what/when/legs/qty/price/fees/notes), not the current ~20 tax/ledger terms.

1. **Editor record-meta block** (`position-editor.js:837-864`, validation `:145-168`): stop
   asking for the five derivable facts. RECORD silently forces `feeMode=EXACT` and
   `fillNature=EXECUTED` (today the controls exist mostly so validation can scold users into the
   only accepted values — errors should set state, not instruct); derive `Record source` from
   whether a broker reference is present; compute `Package net $` from leg fills
   (`enteredPackageNetCents` at `:128` already does it). Survivors (fees, date, reference, notes)
   join the main grid; source/package-net/price-meaning become an Expert-only "Recording
   details" expandable. Replace the "it is the idempotency key" hint (`:863`) with the plain
   wording the record form already uses ("stops the same fill being recorded twice",
   `views-portfolio.js:1329`).
2. **Per-leg §1256**: remove the "Tax contract classification" control at Beginner entirely
   (`position-editor.js:457-467`; duplicate at `views-portfolio.js:1071-1075`) — the automatic
   broad-based-index classification is authoritative; show the derived result as a quiet receipt
   chip on the recorded transaction ("Tax character: automatic · Section 1256"). Keep the manual
   override Expert-only. Drop the `1256` token from the Beginner-visible paste example
   (`position-editor.js:209`, `:822`).
3. **"Taxes & export" tab** (`views-portfolio.js:845-846`, `:1585-1807`): Beginner rendering
   becomes one plain "Tax summary" receipt (short/long-term + income totals, reconciliation
   status chip, the export buttons) — rename it "Records & export" at Beginner. The 8-field
   broker-form reconciliation worksheet, per-row wash review, lot tables, and rules provenance
   move behind Expert/expandables. The tab-blocking "Section 1256 year-end mark required" error
   gate (`:1713-1728`) becomes a one-click banner action inside the rendered tab — the year-end
   mark is engine work offered as a button, not a prerequisite the user must understand.
4. **Account creation** (`views-portfolio.js:793-803`): the four tax-rate inputs leave the
   critical path — an optional "Tax scenario (optional)" expandable, Expert-level. A Beginner
   creating their first account sets: name, type, starting cash.
5. **Dividend recording** (`views-portfolio.js:1129`): default the tax category (engine-derived)
   with an Expert-expandable override instead of a required-looking dropdown.
6. MARK_TO_MARKET stays out of the user event picker (it already is — keep it that way); CSV
   template's `tax_category`/`section_1256` columns stay for import fidelity but are optional.
7. The post-record / post-import landing view answers with P/L, not a ledger confirmation; the
   full profit surfaces are your upcoming Phase 7 (campaigns: realized-vs-headline,
   counterfactuals) — the ledger mechanics serve them quietly.

## P1.3 — Builder interaction-shell adoption (direction B only tonight)

Port Builder's proven interaction patterns into the editor's visual mode: chain-snapped per-leg
strike/expiration selects (`builder.js:934-957`) instead of free-number inputs
(`position-editor.js:451`), the sticky side analytics panel (`builder.js:1274` pattern), compact
grid rows. Both tabs stay; both capabilities stay. Also unify the THREE distinct roll UIs the
audit found down to the receipted PositionEditor roll workbench as the canonical flow (the other
two become routes into it), and note the trade-detail action row grew from 5 to 7
workbench-spawning buttons today — group destructive vs constructive actions there. The deeper consolidation (one shared
position-package core with Builder and Your-trade as skins over one leg model, one chain service,
one payoff service — currently two leg models bridged by adapters and three chain caches:
`builder.js:330`, `position-editor.js:411`, `views-research.js:402`) is **post-program work with
the owner — do not start it tonight.**

## P1.4 — De-collapse refactor (desktop especially; mobile too)

Owner: "sections are collapsed unnecessarily all the time... wasting space without any payoff.
Don't just expand the collapsed tabs, actually fix the issues." Apply P0.2 rule 6 product-wide:

- Sweep every `UI.expandable` / collapse call site. Classify: WRONG-COLLAPSED (primary or
  regular-use content hidden by default) → promote into the visible layout, using the empty
  horizontal space desktop already has (two/three-column placement, merged stat rows) — do NOT
  just flip the default open if that worsens the page (that's the toggle-flip non-fix);
  RIGHT-COLLAPSED (genuinely secondary: provenance, internals, teach-me) → keep;
  WRONG-OPEN (secondary detail bloating pages) → collapse those instead.
- Add viewport-aware defaults where the same content is primary on desktop and secondary on
  mobile — one forced default for both is part of the problem.
- A precise call-site inventory with per-item fixes is being generated and will be appended to
  this document as Appendix B; start the sweep from the surfaces the owner walks daily (plan
  stages, editor, tracked book) and reconcile with the appendix when it lands.

## P1.5 — Discovery restoration (additive only; owner's "recommendations stay the center" rule)

Audit finding: the single ranked-recommendations surface now sits behind a 4-prerequisite chain
(symbol → create Plan → choose intent → manually press "Find proposed trades" — it never
auto-runs), the cold path is ~5-6 interactions detouring through the Builder, and NO nav noun or
Home card is named anything like trades/ideas/recommendations. Home has zero discovery
affordance and renders identically at both levels. Attribution for fairness: this burial is the
Jul 12–14 journey-refactor era (944d6f4, eb944ee, dfa7ad7, a755372), NOT your commits — today
only added a fifth tab and vocabulary to the already-buried screens. Restore discovery
**additively** — the Plan-centered journey stays; these give it front doors:

1. **Home "Today's screened ideas" card** — hero-adjacent, FIRST in reading order on a cold-start
   desk (it also replaces the self-removing empty Plans lens). Power it with the already-built
   surfaces: `POST /api/research/scout` and the `GET /api/welcome/teaching-example` candidate
   pipeline. Each idea's one CTA calls the existing `startPlan({symbol,intent},'STRATEGY')` so a
   tapped idea lands inside the Plan journey at Strategy with the candidate pre-selected.
   Respect ProviderPoliteness: cached/warm data first, no provider hammering on Home loads.
2. **Cold-start hero CTA** becomes "Find an idea" (the owner's anchor vocabulary) alongside
   "Start a Plan"; add a Beginner-tuned first-steps strip (Home currently has zero
   `Learn.currentLevel()` branches — the most prominent two-level-ladder violation in the app);
   fix the stale Welcome toast pointing at the deleted "Next-up list on Home" (`views.js:340`).
3. **Research symbol page**: fill the amputated `#symbol-actions-anchor`
   (`views-research.js:141` — its comment still promises "the action cards" that were removed)
   with one card: "Find proposed trades for <SYM>" → creates/reuses the Plan, lands on the
   Strategy compare tab, and runs the field. Header-search → ranked trades must be ≤2
   interactions.
4. **Universe Scout**: it already computes a "Leading expression" per pick — its CTA must land
   at STRATEGY with that context, not drop the user two stages earlier at Understand
   (`views-research.js:586`); and it belongs above the fold of the Research index, not at the
   bottom.
5. **Signage on the buried warm path**: Home's "Continue <SYM>" already resumes a saved ranked
   field in one click — say so ("ranked trades ready") instead of "is at strategy".
6. **Anchor the renames.** The audit's rename table confirms every owner-familiar concept still
   exists under a new name — twice renamed in two programs (Trade→Plans; 4-step→6 stages;
   Find ideas→Start a Plan; ideas→Proposed trades/Ranked field; Scan→Scout; and today's silent
   Paper→Practice, Reserved→Broker reserve, EXTERNAL→Recorded at broker) — with zero in-app
   mapping. Every renamed term's INFO-registry entry must name its former label ("formerly
   'Reserved'"), and Learn gains a short "What moved where" section generated from that table.
   No renaming things back tonight — anchor them.

**Owner-gated — do NOT do tonight (morning decisions):** renaming nav nouns, consolidating the
seven parallel Plan entry points / the plan tab-bar second nav level, and any auto-run policy
beyond restoring saved results. Reassurance for the morning: "winners" never moved — Portfolio →
"Your record" and book → performance are where they always were.

## P2 — Resume the spec sequence (phases 6–10), corrected

Only after P0 and P1 are green, continue exactly where the spec left you, applying the P0.2
grammar to every new surface. Fold these known completions into their proper phases (most are
already your named scope):

- Settlement family: expiry/assignment previews + receipts (your "lane-clock settlement
  projection" stubs) — the legacy settle path must stop transforming positions silently.
- **Delete** the legacy receipt-less close/roll endpoints and the receipt-less practice
  void/delete path once the receipted routes cover them (pre-release, no compat shims).
- Phase 6: coherence producer + objective persistence (pin "coherent but economically
  unfavorable"); the portfolio-only writer (atomicity's third cardinality case); production
  wiring for pending imports (import boundary, resolution service, alert item); callers for the
  real-lane writer and ADOPTION/RESOLUTION receipts; Decide's third outcome. **Also: removing
  the external-trade card severed the calibration feed** (its outcomes fed "Your record") —
  Phase 6's BROKER-provenance calibration re-pointing to the tracked book is now the only path;
  verify "Your record" works end-to-end for real fills at Phase 6 acceptance.
- Small contract drifts: stance vector's family-max-loss-direction tail; realized-yield type
  carries the time-weighted variant and the "if repeatable" label.
- Spec amendment (owner-approved): §3.2's legacy-record data migration is moot under the
  schema-reset policy — Phase 6 deletes the external persistence path outright, no data
  migration.

## Process rules for the night

- Commit messages disclose **everything** a commit does — deletions especially (the ETL deletion
  in 219254d was undisclosed; that must not repeat).
- Targeted tests while iterating; full JUnit + DOM matrix once at the end of the night.
- Regenerate the DOM screenshots last so the morning review sees current pixels.
- Document in DEVELOPER.md that every `schema.sql` edit invalidates existing dev databases
  (reset required) — it's declared policy; state it where the reset instructions live.

## Appendix B — Collapse-audit call-site inventory (P1.4's concrete work list)

Frame first: of the 56 `UI.expandable` call sites, **roughly 80% are correct** (secondary detail
— provenance, teach-me, internals, archives) and must stay collapsed; the explicit keep-list is
at B.9. The offenders concentrate in the patterns below. Fix these; do not blanket-expand.

**B.1 Systemic primitive fix (do this first — several items below depend on it).**
`UI.expandable` (`ui.js:299-346`) takes a static boolean; there is no viewport-aware default and
CSS can't override (`.xp-body{display:none}` is JS-toggled). Exactly one site hand-rolls the
right pattern — `views-research.js:330` `{open: matchMedia('(min-width:900px)').matches}` —
generalize it into the primitive (`opts.open: true|false|'desktop'`). Two caveats: (a) persisted
state currently beats `opts.open` (`ui.js:319`) — re-evaluate the viewport default on route
render for sections the user hasn't explicitly touched this session; (b) persistence keys are
the summary textContent (`ui.js:313`), so labels embedding counts ("Archived Plans (3)") silently
reset state when counts change — key on a stable id, not the label.

**B.2 WRONG-COLLAPSED at decision-critical points — fix by layout promotion, NOT by flipping
open.** The Plan journey hides the exact contracts (the product's primary answer) four times:
beginner candidate card "The exact contracts — N lots" (`views-plan.js:392`; expert shows it
inline at `:414`), Outcomes "Show the exact contracts and structural risk" (`:1952`), Decide
"Show the selected contracts and prior economics" (`:2262`), and the frozen-decision receipt's
"Frozen listed contracts" (`:2212` — the alert says the package "is frozen beside the linked
practice trade," then hides it). **Warning:** the Outcomes/Decide bodies are full candidateCards
whose paint triggers Monte-Carlo runs (`scenario.js:758-761` budget guard) — flipping them open
wrecks density and compute. Correct fix: promote a compact always-visible facts block (per-leg
lines, max loss/profit, breakevens, POP) into the card — two-column beside the chips on desktop,
stacked on mobile — and keep the deep receipts as a "Full analysis" expandable. The frozen
receipt's 1–4-row legs table simply renders open. Also fix the collapse-inside-collapse nesting
those two sites create, and `managementReceipt`'s open-flag (`views-plan.js:272`): `beginner &&
selected` → `selected` (the selected candidate's management rules open at both levels).

**B.3 Level used as a proxy for viewport — inline on desktop at both levels, collapse mobile
only** (the expert branch already proves the inline layout fits): the 5-field "fit my limits"
filter grid (`views-plan.js:1017-1023`), "Keep it current automatically" + "Update history &
rejected rows" (`views-data.js:1765-1782`), Portfolio→Construct "More construction controls"
(`views-portfolio.js:754`). Inverted case: the research verdict panel renders the management
plan inline for Beginners but COLLAPSED for Experts (`views-research.js:2122-2127`) — expert
must never get less visible content; render the same inline card.

**B.4 The two 100%-collapsed cards.** "Other market-data feeds" hides the entire feed list
(name, ON/OFF, coverage) at both levels (`views-data.js:1810`) — render the compact rows
visible, tuck only license lines. Manage & Review Beginner has two consecutive full-width cards
whose ONLY content is a collapsed row (`views-portfolio.js:2418-2459` — "How this trade works"
and "See exact position sensitivities") — surface the headline facts (net delta, theta/day,
vega) inline and keep the teach-me copy as the expandable; a desktop card whose payload is a
chevron is chrome pointing at hidden content.

**B.5 Tracked Activity ledger (desktop).** Every transaction row renders type+date+amount with
~700px of dead middle width while the legs hide behind a per-row click (`views-portfolio.js:1404`)
— inline the one-line-per-leg detail into the row on desktop; keep row collapse on mobile. On
mobile the fix is ORDERING, not accordions: the journal starts ~85% down a 6,200px page because
form → import card → journal is appended unconditionally (`:1433-1436`) — history first or
segmented tabs.

**B.6 Scenario outcomes at the moment of decision.** "Realistic calm/up/down/choppy outcomes" is
collapsed on the Decide step, on candidate cards, and on the Builder result (`scenario.js:724-729`
wraps it closed at every render site; `views-plan.js:389`, `:436`, `:2342`). This is a primary
decision input. Fix: an always-visible one-line outcome strip (four labeled numbers from the
LATEST STORED run) with the full distribution behind the expandable — recompute only on
selection/context change within the existing budget guard, never per paint.

**B.7 The galling Decide layout.** Five stacked full-width collapse bars (~220px of label-only
chrome: `views-plan.js:2262`, `:2290`, `:104`, `:156`, `:2342`) sit stacked while a half-column
next to Portfolio impact is empty — promote per B.2/B.6 into a two-column layout; the remaining
true-secondary bars move into the right column.

**B.8 WRONG-OPEN inversions (this is where density comes from).** Permanently-open teach-me
prose bloats dense surfaces while real content collapses. Move into the ⓘ registry or one
teach-me expandable: the BOOK-badge paragraph + fairness footnote sandwiching the comparison
(`views-plan.js:1907`, `:1912-1914`), the budget-receipt second paragraph (`:1046-1050`), the
allocation methodology sentences (`views-portfolio.js:997`), the import-card prose (`:1398`).

**B.9 Misused show-more + keep-list.** "Show all N compared choices" nests peer cards in an
indented accordion body (`views-plan.js:1879-1881`) — append into the same grid instead
(in-house pattern: `views-plan.js:2738-2751`, `:1213-1221`). KEEP COLLAPSED (correct, do not
open): data-coverage receipts (`views-plan.js:156`), participation definitions (`:250`), "Why
this classification" (`:104`), score internals (`:282`), assumptions/failure modes (`:299`),
"Why N structures were refused" (`:1093`), guide blocks, Scout notes, archived plans, tax
detail expandables, Builder step-4 fine-tune (borderline — acceptable with autopilot defaults).

**B.10 Dead CSS from the old `<details>` implementation** — selectors match nothing and their
intended borders are silently missing: `app.css:2424`, `:2594`, `:2616-2617` (update to `> .xp`
or delete), plus the orphaned `.economic-learning-group > summary` (`:2067`).

## Appendix C — Morning review addendum (07-16 ~08:30; read at your next commit boundary)

The overnight commits were independently reviewed. 6c22ad1's settlement engineering and
4567ee4's P0.1 implementation both verified well — all four P0.1 items PASS, full matrix
799/799 green at 4567ee4. Punch list from the review, in priority order:

1. **CRITICAL — the legacy silent settle path is still live.** Only the UI button was removed:
   `POST /api/trades/{id}/settle`, `/unwind`, `DELETE /api/trades/{id}`, and
   `/api/plans/{id}/manage/{settle,unwind,roll}` all remain registered, and `TradeService.settle`
   still physically assigns shares with **no preview and no receipt**. The pinned invariant ("no
   action anywhere changes risk identity silently") is still violable via API. Delete the legacy
   receipt-less endpoints now that the receipted lifecycle workbench covers them (P2 item;
   pre-release, no compat shims).
2. **Two missing regression tests from 4567ee4:** (a) a controller-boundary test forcing
   `assessExact` to throw inside a real preview request and asserting 200 + mechanics +
   `available:false` (the factory-shape test alone lets someone reinstate the throw with a green
   suite); (b) a dom test driving the stale-selection card and the "Reprice the prior structure"
   seeding.
3. **B4 second half:** the context-rev banner says "Evidence setup kept" but `planUi.evidence`
   is still replaced wholesale — the user's configured study protocol (window, strictness,
   regime, minSample, bootstrap, etc.) is lost; keep `protocolBySymbol` across the bump, drop
   only stale results, and make the banner accurate. Add a test.
4. **New lifecycle UI grammar (from 6c22ad1, written pre-guidance — bring it up to P0.2):**
   apply uses corner-toast + wholesale repaint; Beginner hides the realized-P/L headline behind
   an expandable (B-class violation — the headline is primary content); a raw `ASSIGNMENT` enum
   reaches the Plan card.
5. **Commit messages: still one-liners.** 6c22ad1 silently deleted the dead-collar test fixture
   and the Settle button and changed a schema constraint — none disclosed. This is the second
   occurrence of the disclosure failure. Every commit body lists what it adds, changes, AND
   deletes.
6. **Regenerate screenshots for the surfaces 4567ee4 changed** (Outcomes/Decide prerequisite
   cards) — their committed pixels are a day stale; the review could not visually verify them.

Reminder: Appendix B (collapse-audit inventory) landed at 01:15, after you stopped — it
completes P1.4 and you have not yet seen it.

## Appendix D — Second correction pass (07-16 ~10:50; complete BEFORE resuming phases 6–10)

**D.0 Status.** Your "done" declaration is premature. Item D.1 is independently confirmed
outstanding; D.2–D.4 are owner-reported defects from walking the current build; D.5 is an
owner-directed design change. Appendix E adds verification findings when it lands — check for
it at your next boundary. Do not self-certify completion: every item's commit body cites the
evidence (file:line, test name) that closes it.

**D.1 CONFIRMED NOT DONE — Appendix C #1, third statement of the critical item.** The legacy
receipt-less endpoints are still registered at HEAD: `TradeRoutes.java:34`
(`POST /api/trades/{id}/settle`), `PlanRoutes.java:89-90` (`/api/plans/{id}/manage/unwind`,
`/manage/settle`) — and per the earlier audit also `/unwind`, `DELETE /api/trades/{id}`, and
`/manage/roll`. `TradeService.settle` still transforms positions with no preview and no receipt.
The pinned invariant is violable via API until these are deleted. The receipted lifecycle and
transformation workbenches cover every flow — delete the legacy routes and their dead handlers.
While here: re-verify Appendix C items 2–6 and close any that remain; cite evidence per item.

**D.2 Owner-reported defect: "Trade a view" silently imposes a NEUTRAL view.** This violates
the spec's ground-truth principle — the user's view/objective is **user-declared, never
imposed**; the product must never invent a stance on the user's behalf (TRADER_OWN_SPEC.md §3.7;
the income-vs-accumulate lesson is the whole reason). Required behavior for EVERY idea/trade
CTA that creates or focuses a Plan: either (a) carry a **disclosed** context — a Scout pick may
carry the scout's thesis because the thesis is shown to the user at the point of click and
labeled on arrival; or (b) **ask inline** — a one-tap view picker (bullish / bearish / neutral /
income, plus horizon) before landing at Strategy; or (c) land ON the intent chooser rather than
pre-answering it. A silent default is prohibited in all paths. Pin it: a test asserting no
plan-create path writes an intent the user neither chose nor was shown.

**D.3 Owner-reported label: "model-dependent theoretical ceiling."** Meaningless to the reader
— if the owner has to ask what it means, it fails, whatever it technically denotes (presumably
unbounded max profit under model assumptions). Replace with plain language at both levels
(e.g. "No fixed cap — profit grows the further the stock moves" with the model caveat in the ⓘ),
wire INFO coverage, and — the systemic half — **extend the explanation audit so any new visible
label lacking both-level registry coverage fails the build.** That extension was P0.2 rule 5
and Appendix C-adjacent; a label like this reaching the owner proves it still isn't enforced.

**D.4 Owner-reported: "Select this structure" has no visible consequence.** The confirmation
renders at the bottom of the screen; the unchosen structures remain stacked on top; nothing
appears to happen. P0.2 rule 2 applies. Minimum interim fix: selection **promotes the chosen
card to the top, marked SELECTED, with "Continue to Outcomes" adjacent to it**, demotes/collapses
the unchosen field, and moves focus to the confirmation. The full fix is structural and arrives
with D.5.

**D.5 Owner-directed redesign: the ranked-trades presentation becomes hero + runners-up +
field.** The two-column collapsed-card grid is the worst of both models — too bulky to scan,
too hidden to persuade. Replace it:

- **Beginner default:** the rank-1 idea as a full-attention hero — payoff chart at real size,
  participation headline, implied stance, both EV lanes, POP, max loss in the capital words,
  "why this ranked first" as narrative from the existing score breakdown, what-could-go-wrong,
  and the protocol preview — plus two compact runner-up cards, plus "See all N ranked" one tap
  away. **Expert default:** a denser hero plus the existing comparison table below it (the
  table is the scan surface; expert gets more, not fewer words). The Beginner card grid
  retires — rendering only; no data or capability is removed.
- **Near-tie honesty line, mandatory:** when the decision-score gap between #1 and #2 is small,
  the hero says so plainly ("#1 by a nose — #2 is statistically equivalent"). Choose a
  principled threshold, document it in the ⓘ, and pin it with a test. The hero layout
  overstates ranker confidence without this; honesty-first outranks presentation.
- **Selection on the hero advances the journey** — select → confirmed transition → Outcomes —
  which resolves D.4 structurally.
- **One argued-idea presentation component, three densities:** Home screened-ideas teaser,
  Scout pick summary, Strategy hero — same fields, same order, same vocabulary, same visual
  grammar, so the Home teaser is visibly the Strategy hero's compact form. Structural
  guardrails (these prevent duplicate journeys and are non-negotiable): every instance is
  **read-only with exactly one CTA**, every CTA routes to the **same destination** (Plan
  Strategy, candidate carried), all instances render from the **same evaluation receipt**, and
  acting on an idea remains **exclusive to the Strategy stage** — Home and Scout never gain
  inline flows.
- "Yours vs ours" survives at both levels: the user's entered trade still appears in the ranked
  comparison beside proposals.

**D.6 Process.** Keep the improved commit bodies (adds/changes/deletes disclosed). End this pass
with the full matrix green, fresh screenshots for every changed surface, and the D.3 audit
extension in place.

**D.7 Gate.** Phases 6–10 resume only when D.1–D.6 and Appendix E are complete with cited
evidence. The morning-checkpoint criteria below apply to this pass as well.

## Appendix E — Verification findings on the seven morning commits (07-16 ~11:10)

Independent audit of d71c7e1..7a4382f. First the credit, because it's earned: **P0.2, P0.3,
P1.1–P1.5, and Appendix B verified as genuinely landed** — the repaint architecture split, add-leg
feedback, chart persistence with stale-dimming, ungated two-column workbench, jargon chrome
removal, collapse discipline, discovery restoration, and profit-first tax derivation all PASS
with evidence, and the commit disclosure bodies are exactly what was asked. That is ~85% of the
correction program proper, done well. What follows is the remainder and the corrections to
Appendix D's assumptions.

**E.1 Appendix C remainder — confirmed with evidence (~1.5 of 6 items were done):**
- C.1 (CRITICAL, unchanged): `TradeRoutes.java:33-35` still registers `unwind`/`settle`/`DELETE`;
  `PlanRoutes.java:89-91` still registers `manage/{unwind,settle,roll}`. `git log` shows **no
  morning commit touched either file**. See D.1.
- C.3: `views-research.js:1824-1828` still replaces `planUi.evidence` wholesale — the user's
  `protocolBySymbol` (window, strictness, regime, minSample, bootstrap samples) is lost while
  the banner at `:1832` still says "Evidence setup kept". Preserve the protocol, make the banner
  truthful, add the test.
- C.2: both regression tests still missing — (a) controller-boundary: force `assessExact` to
  throw inside a real preview request, assert 200 + mechanics + `available:false` (today the
  throw could be reinstated with a green suite); (b) dom test driving the stale-selection card
  and "Reprice the prior structure" seeding.
- C.4 (half-done): enum translation landed, but Beginner still hides the realized-P/L headline —
  `views-portfolio.js:157-158` puts "Option result realized" behind the accounting expandable.
  The headline result is primary content; render it inline.
- Residual grammar: five toast+`App.render` mutation sites remain (`views-portfolio.js:1828`,
  `:1992`, `:2000`, `:2063`, `:2184`) plus the roll-apply supplementary toast (`:432`).
- **Roll surface density regressed:** fresh captures measure 7.6 desktop / 11.3 mobile viewports
  (baseline was 7.2 / 10.4) — the receipt stack grew and there is still no sticky commit bar.

**E.2 Corrections and precision for D.2 (the neutral-view defect):**
- The silent default is `views.js:127-128` (`App.context.thesis('neutral')` inside `startPlan`).
  It predates the program — but 7a4382f built all three new front doors on it. Fix at the
  source: **never substitute 'neutral' in createPlan** — ask, or create with `thesis=null` and
  surface "view not set" prominently at Strategy.
- The Home ideas card is worse than D.2 assumed: `openCandidateAsPlan` defaults the thesis to
  neutral AND **writes it into global context** (`views-plan.js:430-431`), leaking the imposed
  view into later unrelated flows. Remove the global write.
- Universe Scout passes `pick.signals.thesis` — engine-derived. Acceptable under D.2(a) ONLY if
  the thesis is visible at the point of click and labeled as the scout's view on arrival.
- Your own dom tests for the new discovery card only exercise INCOME (`dom.test.js:451,:483`) —
  the DIRECTIONAL default path the owner hit is untested. Add the test for the default path.
- Note the Research page already has the proper declared-view flow ("Set and test a market
  view" → Evidence, carrying `seedContext.thesis`) — the shortcut should compose with it, not
  bypass it.

**E.3 Corrections and precision for D.3 ("model-dependent theoretical ceiling"):**
- Meaning matters: `profitCeilingKind` (`ui.js:1148-1166`) returns "model-dependent" when max
  profit is null AND the package spans multiple expirations — the back leg retains extrinsic
  value at front expiry, so no single terminal payoff line exists. **This is NOT "uncapped"**
  (a separate kind) — do not replace it with "no fixed cap." Plain-language direction:
  "Ceiling isn't fixed — it depends on what the longer-dated leg is worth when the near one
  expires (model estimate)," with the full teaching in the ⓘ.
- Render sites to fix together: `views-plan.js:490-491` (the pairing the owner read), `:633`,
  `:2549`, `builder.js:1394-1396`, `position-editor.js:975-978`, and 0a0f887's new
  always-visible facts blocks (`views-plan.js:107-116` rendered at `:2138` and `:2468`).
- Attribution for fairness: the label predates the program (377ef2a, Jul 11); your 8aa4187
  jargon sweep didn't create it — but missed it, and 0a0f887 spread it.
- The systemic gap is now precisely located: the explanation audit only checks terms wired
  through `UI.info`/`UI.term` (`__usedInfoTerms`, `ui.js:1192`) — it is structurally blind to
  any label rendered as plain text. The D.3 audit extension must inventory **rendered visible
  labels against the registry**, not just wired terms.

**E.4 Precision for D.4 (selection consequence):** the select handler is itself a P0.2
violation the retrofit missed — `planCandidateActions.choose` (`views-plan.js:1028-1083`) POSTs
then `repaint()` → `paint(true)` → `panel.innerHTML=''`: a **full compare-panel wipe on a
card-level action**, destroying the clicked button; the confirmation lands inside the selected
card *below* the rebuilt button row, routinely below the fold now that 0a0f887 made cards
taller; in Expert it renders below the entire comparison table. No reorder, no pinning, no
collapse of the unchosen. The D.4/D.5 fix therefore includes converting this handler to a
targeted card update — do not carry the wipe into the hero layout.

**E.5 Definition of Done (this replaces self-certification).** "Done" is declared only when,
in one final commit: (1) every D and E item is closed with file:line + test-name evidence in
the commit body; (2) the full JUnit + DOM matrix is green; (3) screenshots are regenerated for
every changed surface; (4) the D.3/E.3 audit extension is in place and green. For the record:
declaring done and then committing a test fix and screenshot regeneration 20 minutes later
(baa29ab, 7e9a003) is exactly the pattern this definition exists to end.

## Morning checkpoint (what the owner will evaluate)

1. The five original editor complaints resolved on screen: add-leg feedback, chart persists and
   sits beside the legs at both levels, Remove grouped with its leg, no version jargon, no
   surprise view resets.
2. Outcomes/Decide guide instead of dead-ending; the Monte Carlo lens is discoverable by name.
3. A Beginner records one trade meeting zero tax vocabulary; post-record view answers with P/L.
4. Every surface: primary answer + primary action inside one 1280×800 viewport.
5. Full test matrix green; screenshots current; commit trail readable with honest messages.
