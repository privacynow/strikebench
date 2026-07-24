# Desk review & product-direction handoff (reviewer lane)

Date: 2026-07-21 · Author: the review/direction session (separate from the implementing lane)
Companion to: `DESK_MARKET_CONVERGENCE_HANDOFF.md` (implementation handoff, commit `af40c9a`)

This note hands off the **reviewer/product-direction** thread: what has been verified, what the owner
has decided, the agreed target architecture, and the precise open defects — so the next session can
continue without re-deriving any of it. Method notes at the end describe how to verify without
touching shared state.

## 1. Where the product stands (verified against `af40c9a`, isolated clone, 2026-07-21 evening)

Verified working, in the running packaged app:

- **Recommendation economics are fixed.** Market-implied EV is now a cost benchmark only; the verdict
  runs on realized-vol EV after costs with a materiality threshold. AMD 45-session Income shows
  `FAVORABLE ECONOMICS · MIXED FIT` on the cash-secured put (~+$334 realistic EV) and covered
  strangle (~+$441). The "favorable economics vs. exact fit" split is honest and correct — the desk
  no longer manufactures a pick, and no longer structurally refuses one.
- **One-fan contract is live**: candidate-invariant 500-path ensemble, stable source rows, Price/P&L
  views, chart-wide hit layer, click-drives-whole-desk playback. These are regression contracts now.
- **Position Bloom** opens on a default ensemble-derived P/L story at t=0 (no more instruction-void),
  full checkpoint grid, 2×2 leg containment, dedicated research/news areas, metrics rail off the hero.
- **Home** is a product surface again: five-position roster, interactive risk map, candles, chain
  slice, watch, working **Ideas** (user-facing word; `Plan` stays backend), research/news, and the
  goal-aware memory/storage Scout (honest when nothing clears the bar).
- **Data layer**: 105-symbol Yahoo contract (STX/WDC/SNDK pending next UTC budget window), durable
  politeness budgets, snapshot capture/verify/hydrate, seeded five-position Practice book. Treat the
  book + snapshot as the daily acceptance corpus — never reset them for a "clean" database.
- **Language sweep** held on audited surfaces (no `authoritative`/`source-owned`/`stored coverage`/
  `conditioned`/receipt-hash lines in default copy).

## 2. Open defects found in the post-`af40c9a` review (owner-reported, now diagnosed)

1. **Pager page-size measures the wrong box → clipped recommendations.** At a ~963px-tall viewport
   the pager renders "← 1–5 of 9 whole rows →" but only ~2.9 rows actually fit: rows render at
   y=214/333/452/571/690 (row height ≈115px + gap) while the Leg Workbench header begins ≈560px, so
   rows 3–5 run underneath it. Root cause: the whole-row page size is computed against the left
   column's total height (≈786px) instead of the candidate list's *flex share after* Leg Workbench
   and the Risk/Reward map take theirs. Fix: measure the list's own content box after layout settles
   (or subtract sibling heights), re-measure on resize, and assert `pageSize × rowH ≤ listBox` in the
   geometry regression. The whole-row/pager grammar itself is right — keep it.
2. **Risk/Reward map: reference line strikes through the "ideal ↗ likely & positive realistic EV"
   label.** The map's new axes (chance-of-profit × after-cost EV) put a full-width y=0 reference line
   through the ideal-region caption. Fix with the same discipline used for map dot labels: clip the
   refline short of the label's box, or place the label with collision avoidance (it may sit below
   the region border). While there: the ideal-region shading and the zero-line currently compete —
   one quiet treatment each.
3. **"New Idea seems worse somehow" — it's crowding, not regression.** Diagnosis from the live
   screen at owner-scale viewport (~2000×963):
   - The left column now stacks three sections (comparisons / workbench / map); below ~1080px tall
     the *primary* content — the ranked ideas — collapses to two visible rows while secondary panes
     keep fixed heights. The hierarchy inverted. Give the list flex priority and let workbench/map
     compress or fold first at short heights.
   - Verdict rows grew to ~115px because each carries a full sentence of EV detail
     ("Realistic after-cost EV +$334; tested range −$228 to +$889. Entry-cost benchmark −$66.").
     Move the sentence to the row's hover/expanded state; the resting row needs verdict chip + one
     number (realistic EV), in the established chip grammar.
   - Two stacked banner messages at the top of the list plus a persistent bottom status bar
     ("Comparison in view · nothing selected …") plus the right-rail explainer all say overlapping
     things. One statement of state, once; the bottom bar should exist only when it carries an
     action (Analyze/Review), not as standing prose.

## 3. Owner decisions on record (do not re-litigate; implement toward them)

- **One Workspace, three states, one grammar** — the agreed target architecture:
  *Book state* (nothing focused — replaces Home as a page): left = positions roster + New idea/Import;
  center = **book-level fan** (per-position P/L bands in the shared position colors; Phase 1 =
  client overlay of each position's stored ensemble, honestly labeled independent projections, never
  summed; Phase 2 = joint correlated multi-symbol ensemble in `PathEnsembleService` — the flagship
  engine increment), equity/account band, book risk, triage;
  right = understand-the-book + **Market tab**.
  *Position state* — click a roster row: same center/right, position-focused; dock = manage.
  *Idea state* — ＋New idea: candidates left, execute dock. The separate position-bloom renderer and
  the Decide layout converge into this one surface; the FLIP morph is the transition between states.
  Sequencing agreed: fan P/L work (done in `af40c9a`) → book-overlay fan → Market tab in the
  `inspectrail` (Paths·Fit·Greeks·Book·**Market**: candles/history/quote/news, ambient one-liner
  stays in the hero header) → position-lens merge → fold Home in (mostly deletion by then).
- **The fan always shows the P/L of the thing in focus**; price-space is a toggle. For positions the
  conditioning contract already exists (`focusPositionKey`).
- **Market-data direction is standing product policy**: Yahoo enabled until explicitly revoked,
  politeness envelope inviolable (budget 160/day is *durable* — never cleared, never rebased),
  data persists and enriches across runs, continuous polite feeds, news persistence. The 2026-07-21
  allowance is exhausted; the exact next Yahoo action is scripted in the implementation handoff §
  "Exact next Yahoo action" — follow it verbatim after the UTC reset.
- **Consistency laws** (owner has repeated these; violations are regressions): one component per
  concept (no second map/fan/list implementations); interaction-drives-the-screen with a
  flow-preserving return (hover previews, click focuses, Esc/back restores exactly); whole-item
  pagination, never free-scrolling card interiors; provenance stated once per surface, exceptions
  only; no internal vocabulary in product copy; level-of-density earned by focus.
- **Backtesting honesty**: forward-test = the ensemble machinery; true package backtests are not
  honest until option snapshots accumulate depth (began 2026-07-17). Offer underlying-driven replays
  clearly labeled, and recommendation-quality history via the existing calibration/evaluations owners.

## 4. Known remaining runway (beyond §2)

From the implementation handoff's ordered continuation, plus review items not yet landed:
responsive acceptance at 2560/1920/1440/1366/1280/tablet/mobile; cold/warm New-Idea timing with named
progress >2s; MARKET/LIMIT review exercise; focus-lens grammar extended to Book/Home objects; Home
market pulse + sector lenses from canonical owners; the New-Idea composer's next iteration (goal →
visual strategy picker from the Learn grid → optional exact legs; "re-scout" affordance — owner
vetoed "roulette" naming); storage-trio first fill + snapshot recapture; the Plans→Ideas language
sweep completion anywhere it still leaks.

## 5. Method notes for the next reviewer session

- **Never review against the shared 7070 instance or `strikebench_dev`.** Protocol: clone the dev DB
  (`pg_dump` → `strikebench_review` via the `strikebench-db` Docker container), copy
  `target/strikebench.jar` + `strikebench.properties` to a scratch dir, run with
  `PORT=7171 DB_URL=…strikebench_review` and **`YAHOO_ENABLED=false`** (protects the shared request
  budget), verify, then kill the process, drop the DB, remove the dir. Leave no residue.
- The in-app browser pane's compositor can stick at a stale scale after resize+reload (DOM rects are
  authoritative; a resize to a slightly different height forces repaint), and coordinate clicks are
  unreliable on this app — drive via DOM (`element.click()`) and verify geometry numerically.
- Review at the owner's effective viewport (~2000×963 CSS), not only 1920×1080/2560×1440 — two of the
  three §2 defects only manifest below ~1080px of height.
- The reviewer-lane worktree branch `claude/trader-own-spec-review-0b4737` holds only the obsolete
  prototype lineage; implementation work must branch from `feature/journey_refactor`.

## 6. Standing context worth knowing

The full review record (four adversarial reviews on 2026-07-21: wiring quality → root-cause
diagnosis of never-recommends/missing-bloom/latency → MC-sameness/label-leakage/space →
post-`af40c9a` verification) and the owner's design philosophy (breathing desk: one canvas, zoom
states, no tabs/rail; graphical-not-text; honest numbers that *teach* rather than refuse) are the
grounding for every direction above. The implementing lane's handoff is the operational source of
truth for data/budget/snapshot procedures; this note is the source of truth for product direction
and verification method.
