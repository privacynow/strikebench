# Program ONE owner desktop audit — 2026-07-16

Transient local fixture/simulated session captured at **1920×1080** before the next rebuild.
These are product-verdict inputs, not release evidence.

The owner’s fully expanded 5K display presents a **2560×1440 logical viewport**. That exact
viewport is captured below and is the primary wide-desktop acceptance case. A separate 4096px
logical capture is retained only as an ultra-wide stress case.

## Captures

- `program-one-owner-20260716-research-overview-1920.png`
- `program-one-owner-20260716-research-overview-full-1920.png`
- `program-one-owner-20260716-evidence-chooser-1920.png`
- `program-one-owner-20260716-evidence-chooser-full-1920.png`
- `program-one-owner-20260716-strategy-viewport-1920.png`
- `program-one-owner-20260716-strategy-full-1920.png`
- `program-one-owner-20260716-strategy-viewport-2560.png`
- `program-one-owner-20260716-strategy-full-2560.png`
- `program-one-owner-20260716-strategy-viewport-4096-stress.png` (4096px logical stress capture)
- `program-one-owner-20260716-strategy-full-4096-stress.png` (4096px logical stress capture)

## Measured failures

### Research overview — `#/research/AAPL`

- The quote card and “Ready to compare” action are forced into one equal-height **415px** row.
- Both Ready-to-compare child groups are only ~103–106px high but begin **155–156px** below the
  card top because the card uses vertical centering.
- The action is therefore visually detached from the decision and most of its bordered surface
  is empty.

Acceptance: the action is a compact, top-aligned handoff owned by the same Workspace journey;
it never stretches to the quote card's height and never duplicates view declaration.

### Evidence chooser — `#/research/AAPL?view=evidence`

- The app uses a 1760px route canvas; the chooser card is **1728×363px**.
- The three controls become three ~555px tracks even though their content needs a fraction of
  that width. An empty-state sentence then floats in the middle of the remaining card.
- The temporal order reads “Thesis → historical condition → horizon,” making the trigger look
  like something that happens after the forward view.
- Expert labels name distinctions but do not consistently expose the registry explanation
  affordance that helps a person separate similar triggers.

Acceptance: “What just happened? → What do you expect next? → Over what horizon?” reads as one
sentence-scale decision composer, with trigger distinctions adjacent and the evidence result in
the reclaimed desktop track.

### Strategy — `#/plan/plan_7svj31fa107bmdfa/strategy`

- The document is **4774px** tall at a 1080px desktop viewport.
- The active Strategy band alone is **4042px** tall.
- The ranked field is **2448px** tall, while the filter row stretches five inputs across
  **1778px**.
- The route publishes ready before its async Strategy producer finishes; the DOM can momentarily
  expose only “Compose your own” while the visible content is still arriving.

At the correct 5K logical viewport, 2560×1440:

- The app is capped at 2200px with 180px gutters, but the active Strategy band still reaches
  **4693px** and the document **5471px**.
- The ranked field is **3099px** tall. Its hero occupies a **1345×2711px** left track, while the
  right track contains only headings until the full-width comparison table lands far below.
- The first 1440px viewport spends most of its height on context, budget, and five stretched
  filter inputs; the actual ranked decision begins at the bottom edge.

Acceptance: route readiness waits for the active producer; controls form a compact decision rail,
the ranked instrument gets the primary canvas, secondary evidence folds in place, and the first
viewport contains the question, consequence, and primary action without hiding capability.
