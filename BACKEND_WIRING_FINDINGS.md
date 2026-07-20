# Backend wiring findings

Updated 2026-07-20 from the Program ONE/TRADER-OWN specifications, the integrated source tree,
the current prototype review, and an observed-market probe. This is an execution ledger, not a
replacement architecture. New work extends the canonical owners named in `PROGRAM_ONE_HANDOFF.md`.

## Immediate market-hours evidence

The read-only probe against the already-running non-fixture server succeeded at 11:33 America/Phoenix:

- health, config, provider status, world, market engine, quote, expirations, and chain all returned HTTP 200;
- NVDA quote came from Cboe as `OBSERVED` / `DELAYED`, with bid 204.83 and ask 204.84;
- Cboe returned 24 expirations and the selected 2026-07-20 chain contained 61 calls and 61 puts;
- raw receipts are in `/tmp/strikebench-live-probe-20260720-113323`.

The non-trading end-to-end probe then exercised the canonical Plan path before the close:

- 30- and 5-session NVDA declarations stopped honestly at Strategy because no liquid two-sided
  chain matched those horizons; the application did not substitute fixtures or invent candidates;
- a one-session declaration with the existing 0DTE gate explicitly enabled returned 11 backend
  candidates, selected an eligible exact package, stored a 500-path ensemble, evaluated that exact
  package through ensemble fingerprint
  `df0c2240c15743a633f0c2d2690c59ee358b4da5f842d438f6c283e496593ee0`, and returned an executable
  `OBSERVED` / `DELAYED` decision preview;
- no trade or broker order was placed. The successful receipts are in
  `/private/var/folders/fj/6zb38k1n2l73djm42fkdm03c0000gn/T/strikebench-live-desk-flow-20260720-185204`.

This proves the quote/expiration/chain seam while the market is open. It does not prove order
placement, fill state, historical coverage, or that every contract is executable. Those retain
their existing gates.

## Executable wiring order

1. **Observed market frame.** Reuse `MarketDataEngine`, `MarketDataService`, and the existing
   freshness/evidence records. Wire the new surfaces to quote, expiration, and chain APIs without
   synthesizing missing values. Preserve SSE/world-transition ownership instead of adding polling
   state inside individual panels.
2. **Scenario motion.** Reuse the stored `PathEnsembleService` artifact and
   `ScenarioCanvasValuator` / `PathValuationKernel`. A display subset of source paths is necessary
   but not sufficient: the production animation response must also bind its focus path to the exact
   position-value, P/L, IV, Greeks, leg-state, and same-symbol position checkpoints from the same
   valuation. The typed, non-mutating animation request accepts either a stored scenario or transient
   waypoints/IV-canvas assumptions against an already stored fan. It returns full ensemble/model/
   market provenance, a versioned selection receipt, and a deterministic child valuation receipt.
   A selected scenario shows several nearest original paths, never one misleading forecast or a
   visual-only simulator; the focus line drives the journey and surrounding paths retain dispersion.
3. **Model depth.** The model selector continues to use the existing GBM, Student-t, jump, Heston,
   historical-analog, and conditional-bootstrap capabilities. Beginner copy explains the behavior;
   Expert exposes canonical parameters and receipts. Animation timing never changes the simulation
   seed, path, valuation, or probability result.
4. **Order semantics.** Signed package net remains canonical (`+` credit, `-` debit). The existing
   trade and Plan-decision owner now accepts one normalized nested `MARKET` / `LIMIT` instruction,
   with signed limit,
   time-in-force, natural executable net, midpoint, marketability, present eligibility, and actual
   evaluated fill. A marketable limit fills at the natural/better executable package—not blindly at
   its worse bound—and a favorable resting limit must never create a position. The existing broker
   preview identity, TTL, confirmation, and idempotency receipts remain the live adapter boundary.
   Debit/credit is derived economics, never a third order type. Order changes re-preview execution
   and package consequences while preserving the exact ensemble id and fingerprint.
5. **Trade and Book continuity.** Placement, working, partial, filled, cancelled-requested, and
   rejected states flow into the existing Plan decision, position, ledger, campaign, alert, and Book
   owners. Do not create a prototype-only order store.

## UX findings translated to backend contracts

| Surface feedback | UX decision | Backend consequence |
|---|---|---|
| Scenario animation should use the real statistical work | Animate the selected stored fan with one focal path plus contextual neighbors; synchronize price, IV, position P&L, and dated events | Return immutable source-indexed display paths and lineage; value through `ScenarioSimulator` / `PathValuationKernel` |
| Limit, market, and net-credit currently look equivalent | Change price control, fill likelihood/state, natural price, debit/credit, buying-power consequence, warnings, and review language | Introduce a normalized order preview DTO/service over broker and paper execution owners; never infer behavior only in JS |
| New-Idea risk map crowds the first column | Stack Ideas then Legs; give the leg editor full width and a bounded panel scroll when it grows | No new calculation owner; proposal selection and exact leg edits continue through `StrategyCatalog`, Builder, and evaluation APIs |
| Four-plus legs become clipped | Prefer a readable single-row leg at wide widths, horizontal detail overflow only inside the leg panel, and stacked fields at narrow widths | Leg DTO must retain full action, type, ratio, strike, expiration, quote/execution, and provenance; the UI may not truncate the data model |
| Position Bloom risk-map labels differ | Use one risk-map component and labeling policy; suppress underlying text before suppressing structure identity when space is tight | Reuse the same risk/exposure payload for Idea and Position contexts, with presentation-only density changes |
| Every screen should move smoothly | Motion reflects state transitions, data refresh, selection, scenario time, or hierarchy; it does not continuously distract or mutate state | SSE/event owners provide deltas; animation frames remain client presentation over frozen server facts |

## Layout decision for New Idea

Keep the current visual language. Move the exact leg editor adjacent to the Ideas selection in the
reading order and stack them as full-width panels. This remains legible for condors and larger
packages and collapses naturally on mobile. The risk map becomes a supporting panel beside another
compact decision panel when width permits, then stacks below. Panel-local scrolling is acceptable
for a grown leg set or dense Expert columns; whole-page horizontal scrolling is not.

## Implemented convergence increment

- `POST /api/plans/{id}/outcomes/ensemble/paths` is the strict animation contract. It accepts a
  stored scenario or transient waypoints and IV/canvas assumptions, selects a bounded subset of
  original source paths, identifies the focus source index, and returns dated underlying,
  position-value/P&L, Greek, leg-state, and transformation checkpoints from that same source row.
  The response binds Plan context, world, dataset, model, assumptions, ensemble fingerprint,
  selected candidate, path-selection rule, and child valuation fingerprint.
- Conditioning is nearest-path selection over the immutable stored fan. It does not claim to
  sample a new conditional posterior. Full-fan probability bands remain context; the focus trace
  and neighboring paths show the selected hypothesis's journey.
- Plan decision preview accepts
  `orderInstruction: {type: MARKET|LIMIT, limitNetCents?, timeInForce: DAY}`. Preview responses name
  `IMMEDIATE`, `RESTING`, or `UNAVAILABLE`, retain the natural executable signed net, derive
  CREDIT/DEBIT economics, and prevent a resting limit from creating a position. A marketable limit
  receives the better natural executable fill.
- The Desk's HTTP runtime sequences quote, expiration, chain, Plan, recommendation, selection,
  ensemble, outcome, conditioned animation, and decision-preview owners. Its `file://` dataset is
  retained only as a visual regression fixture. Production animation interpolates returned
  checkpoints; it does not run a browser pricing or random-path engine.
- Candidate/leg/risk composition now reads Ideas → exact Legs → supporting map. Four-plus-leg
  packages use a panel-local rail on desktop and full-width stacked legs on narrow layouts; position
  risk marks expose identity through focus/hover instead of printing ticker text over every dot.

## Non-negotiable verification

- Run focused JUnit tests after each backend owner changes, then `mvn -q test` for the integrated module.
- Exercise observed quote/expiration/chain reads before the close when available; preserve the raw probe.
- Add browser assertions that order-type changes produce materially different ticket consequences.
- Add responsive checks at 2560, 2048, 1920, 1440, 1280, 1000, 390, 375, and 320 CSS pixels,
  including a four-leg and a larger custom package.
- Assert the animation endpoint reuses the exact ensemble and authored-scenario fingerprints and
  returns only original source paths.
