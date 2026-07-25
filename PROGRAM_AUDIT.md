# Program audit — 2026-07-24 (six lanes, file:line verified)

Source: six independent audit agents over `program.md` §3.1/§3.5/§7.1–§7.9/§8.2 against
`feature/journey_refactor` at commit 8654824. Each finding was required to carry evidence.
This file is the working ledger; strike items as they land.

## canonical time receipt (program.md §7.3)

### [P0] The weekday approximation was not deleted, it was relocated: sessionDistance() still counts Mon–Fri in the browser and it decides which expiration the ENTIRE desk trades.

**Evidence** — src/main/resources/public/js/desk-backend.js:208-217 `function sessionDistance(asOfDate, expiration){ ... var weekday = new Date(day*86400000).getUTCDay(); if (weekday!==0 && weekday!==6) sessions++; }` with `dateOrdinal` at :203-206 (`Math.floor(Date.UTC(...)/86400000)`). Consumed by `chooseExpiration` :231-243, which is the sole expiry selector at :498 (`loadMarket` — Decide/New Idea) and :2723 (Home/Book symbol pulse). It counts NO holidays: MarketHours.java:53-58 excludes `HOLIDAYS` (buildHolidays 2020–2035, MarketHours.java:85-100: New Year's, MLK, Presidents', Good Friday, Memorial, Juneteenth, July 4, Labor, Thanksgiving, Christmas + observation shifts) and `SPECIAL_CLOSURES` (:31-33). Any window spanning Thanksgiving/Christmas/Good Friday makes the browser over-count sessions and pick a different expiry than the backend would.

**Fix** — Not served yet. `ApiResponses.Expirations` (ApiResponses.java:195) is `{symbol, asOfDate, expirations:[String]}` — flat dates. Add per-expiry time to `ResearchController.expirations()` (ResearchController.java:347-355): for each active expiration emit `OptionTime.toExpiry(laneToday, expiry)` (OptionTime.java:21-30 → `{sessions, calendarDays, years, basis}`), and accept a `?horizonSessions=` param so the BACKEND names the chosen expiration. Then delete desk-backend.js:197-243 entirely.

### [P0] Book fan expiry-cut is dead code: it gates on a field the backend record does not have, so book fans are NEVER truncated at expiry and draw settled afterlife.

**Evidence** — index.html:1962 `if(expiration&&lifeSteps.some(function(step){return step&&step.sessionDate;}))` where `lifeSteps=display[0].steps` and `display=row.displayPaths` (index.html:1950). That row comes from POST /api/plans/{id}/outcomes/ensemble/paths → `checkpoints.positions` (desk-backend.js:3380-3387), serialized from `ScenarioCanvasValuator.Report` (PlanOutcomeController.java:914-921 `Json.MAPPER.valueToTree(report)`). The record is `ScenarioCanvasValuator.java:82  public record DisplayPositionStep(int step, double sessionProgress, long pnlCents) {}` — no sessionDate. Guard is permanently false → `lifeS` stays null (index.html:1961) → the truncation block at index.html:1969-1974 never runs. Same hole in `BookPathStep` (ScenarioCanvasValuator.java:139). The position-level fan explicitly refuses to draw this afterlife (index.html:1062-1065), so the two surfaces disagree.

**Fix** — Smallest correct fix: read `response.checkpoints.underlyingSteps[].sessionDate` — `UnderlyingStep` (ScenarioCanvasValuator.java:51) DOES carry sessionDate and travels in the SAME response — and map to sessionProgress. Proper §7.3 fix: add `expiryCutIndex` + `lifeSessions` to `PositionPath` (ScenarioCanvasValuator.java:95-106) so no client computes it.

### [P1] The browser fabricates the session-date anchor from its own UTC clock when /expirations omits asOfDate.

**Evidence** — desk-backend.js:219-229 `quoteAsOfDate(quote)` → `new Date(raw).toISOString().slice(0,10)` (:221, :226) — UTC, not America/New_York. Used as the fallback anchor at desk-backend.js:497 `var expirationAsOf = base[4] && base[4].asOfDate || quoteAsOfDate(quote);`. For any quote instant at/after 20:00 ET the UTC date is already tomorrow, shifting every sessionDistance by one and potentially selecting the wrong expiry.

**Fix** — Already served: `Expirations.asOfDate` = `LocalDate.ofInstant(now, MarketHours.EASTERN)` (ResearchController.java:350-352, ApiResponses.java:195). Delete `quoteAsOfDate` and the `||` fallback at :497; a missing asOfDate must be a hard error, never a browser-derived date.

### [P1] Two independent browser implementations of the expiry cut plus a browser-side front-expiry election over leg-expiration strings.

**Evidence** — Implementation A: index.html:1074-1085 `authLifeIndex(p)` (walks `underlyingSteps[].sessionDate` vs expiration). Implementation B: index.html:1959-1971 (book fan, inline duplicate). Both call index.html:1066-1068 `authPackageExpiration(p)` = `(p.legs||[]).map(l=>l.expiration).filter(Boolean).sort()[0]` — the browser electing the front expiry by string sort. index.html:1086 `authLifeSessions` and index.html:1088-1105 `authFanForLife` then re-derive the truncation index a third time from `pathProgress`.

**Fix** — Backend already elects the front expiry once: `OptionTime.nearest(legs, laneToday)` (OptionTime.java:15-19) returns front expiry + sessions + calendarDays. §7.3 asks for `expiry date` + `expiry cut index` on the receipt — add both to `PositionPath`/`BookPositionReceipt` and delete index.html:1066-1105 and 1959-1971.

### [P1] Silent 45-session horizon default contradicts the adapter's own stated contract and silently drives expiry selection.

**Evidence** — desk-backend.js:163-171 `horizonDays()` comment: "the adapter never fabricates a 45-session default" — then desk-backend.js:2147 `var market = await loadMarket(symbol, declaredHorizon == null ? 45 : declaredHorizon, seq);` feeds 45 straight into `chooseExpiration`. index.html:904 `authoritativeHorizon(p)` ends `: 45;`. index.html:3011 `horizon:'1 day'`; index.html:3364-3365 `'45 days'` / `'1 day'`. No `45` exists anywhere in Horizon.java (ZERO_DTE 1, WEEK 5, MONTH 21, QUARTER 63).

**Fix** — desk-backend.js already has the machinery: `missingDeclarations` at :2154-2160. Move the horizon check BEFORE `loadMarket` and refuse to acquire a chain without a declared horizon; delete the `?45` at :2147 and the `:45` at index.html:904.

### [P1] Browser re-buckets an exact declared horizon into a named one using thresholds that diverge from the backend's, and the result is sent as the order's horizon.

**Evidence** — desk-backend.js:964-966 `horizonDays<=1?'0dte': <=7?'week': <=45?'month':'quarter'` inside `canonicalDraftPosition` (:908, used at :1045). Backend `Horizon.fromTradingSessions` (Horizon.java:64-69) uses `<=1 / <=10 / <=45`. 8, 9, 10 sessions → browser 'month' (21 sessions) vs backend WEEK (5). Both also destroy exactness: a declared 30 becomes MONTH=21. The value lands on `TradeOpenRequest.horizon` (TradeOpenRequest.java:15) and is consumed at TradeController.java:565.

**Fix** — Backend already has the exact encoder: `Horizon.exactTradingSessions(days)` → "30d" (Horizon.java:76-83, used at PlanController.java:365 and PlanStrategyService.java:827). Send `state.plan.context.horizonDays + 'd'`, or better: have TradeController read the owning Plan's `horizonDays` and drop the client-supplied string.

### [P1] The same backend integer horizonDays is labeled 'days' on one surface and 'sessions' on another — the exact conflation Horizon.java was written to prevent.

**Evidence** — index.html:2633 `decide.horizon=...String(context.horizonDays)+' days'` (also :2638, :3364-3365, :3370). index.html:1889 renders the same field `+(ctxp.horizonDays?' sessions':'')`. Horizon.java:7-10 states the two units are deliberately different (QUARTER = 63 sessions vs 90 calendar days).

**Fix** — §7.3 wants `calendar days` and `trading sessions` as separate served fields. Emit both on the Plan context (PlanController) and render each with its own label; never re-label one number.

### [P1] SERVED BUT UNUSED: the canonical OptionTime receipt already rides every candidate and is dropped by the renderer — the desk shows an expiry date with no sessions or DTE anywhere.

**Evidence** — Produced: DiscoveryController.java:263-276 `attachCandidateTime` sets `candidate.time = OptionTime.Measure{sessions, calendarDays, years, basis}` (also :161-171 for fresh ranks, :252-260 `attachCandidateTimes` for restored ones). Carried into the client model: desk-backend.js:1453 `time: candidate.time || null`, :1023 `desk.time = preview.analytics.time`, :1197. Consumed by index.html: nowhere — grep for `.time`/`time.`/`'time'` in index.html returns only the prose comment at index.html:883. The desk instead prints bare dates: index.html:3800, :3727, :3767 (`'exp '+c.exp`).

**Fix** — Render `c.time.sessions` / `c.time.calendarDays` / `c.time.basis` beside every `exp <date>` (index.html:3727, 3767, 3800, 684, 3265) instead of adding any new client count.

### [P1] SERVED BUT UNUSED: real NYSE session dates for every ensemble step are already on the paths payload and the client never reads them — this is exactly the data the dead book-fan cut needs.

**Evidence** — PlanOutcomeController.java:786-807 `decorateScenarioCanvas` emits `preview.sessionDates[]` (from `ScenarioSpec.sessionDates(anchor, horizonDays)`, ScenarioSpec.java:119) and `preview.anchorSessionDate`; the comment states "the studio shows 'session 12 — Tue Aug 4', never a bare index". `grep -n sessionDates` across index.html, js/desk-backend.js, js/api.js returns zero hits. Meanwhile index.html:684 and :3265 label the fan axis with a bare `Math.round(horizonDay)+' sessions'`.

**Fix** — Bind fan axis labels and the expiry cut to `preview.sessionDates` / `anchorSessionDate`, and use them to close the book-fan hole without changing any Java record.

### [P2] CONFIRMED GONE: histSessionsUntil() no longer exists anywhere in src/ — only two prose mentions in program.md:294/1045 and one stale row in DUPLICATION_LEDGER.md:143 survive.

**Evidence** — `grep -rn histSessionsUntil src/` returns nothing. Residual references: program.md:294, program.md:1045, DUPLICATION_LEDGER.md:143 (`index.html.histSessionsUntil:4961` — that line no longer exists; index.html is 5296 lines and line 4961 is unrelated). Removal commit: 86c2c92 "M3/§7.3: delete the browser session approximation".

**Fix** — Delete/annotate DUPLICATION_LEDGER.md:143 row U6 — its `index.html` half is dead, but its `desk-backend.js.sessionDistance:209` half is still live (see next finding), so rewrite it rather than closing it.

### [P2] Path progress silently falls back to the array index at 17+ sites, substituting a uniform grid for the real session grid whenever sessionProgress is absent.

**Evidence** — index.html:216 `fanPathDay` (`horizon*i/(path.length-1)`), :3242 `frameDay`, and `step.sessionProgress==null?i:...` at :548, :663, :664, :665, :1076, :1113, :1922, :1924, :1929, :1952, :1966, :2546, :2565, :2568, :2586, :2589, :2591. Every backend producer already emits it: PositionStepBand/DisplayPositionStep/PositionStep (ScenarioCanvasValuator.java:78,82,71), BookStepBand/BookPathStep (:135,139), PreviewStepBand (SimulationEngine.java:121), DisplayBand (PathEnsembleService.java:67).

**Fix** — §7.3 names path progress as served. Delete every `==null?i:` fallback; a step without sessionProgress is a malformed receipt and must throw, not silently become a uniform ray.

### [P2] Hardcoded calendar→session table in the browser for chart windows — a third, divergent session dialect.

**Evidence** — index.html:1351-1355 `histPresetSpan`: `1w=5, 1m=21, 3m=63, 6m=126, 1y=250, 5y=1250`, plus a client YTD scan over bar date strings (`String(bars[i-1].date).slice(0,4)`, :1354). 250 sessions/yr conflicts with Horizon.java (21/63) and with SimulatedWorld.java:58 (`YEAR_SECONDS = 252.0 * SESSION_SECONDS`).

**Fix** — Not served yet. `ApiResponses.History` (ApiResponses.java:231) already carries `range` and `coverage` — add a per-preset `{preset, sessions, firstSessionDate, lastSessionDate}` window receipt from MarketHours and have the client slice by served index.

### [P2] The intraday time lever converts sessions to hours/minutes assuming a 24-hour session; the backend session is 6.5 hours, so a '+6h' step sends ~3.7x more time than displayed.

**Evidence** — index.html:896 `tDisp(days,u){ return u==='h'?Math.round(days*24)+'h':u==='m'?Math.round(days*1440)+'m':...}`; index.html:895 `tStep(u){ return u==='h'?1/24:u==='m'?5/1440:1; }`; index.html:1009-1010 seeds `6/24` and `30/1440`. Backend: SimulatedWorld.java:57 `SESSION_SECONDS = 6.5 * 3600.0`. `daysOf(p)` is sent verbatim to the backend at index.html:937 and :953 (`days:daysOf(p)`). Currently confined to the fixture lane — the h/m unit buttons are suppressed for authoritative positions (index.html:791 renders only the `d` button when `p.authoritative`).

**Fix** — Serve the session length (or an explicit intraday `sessionProgress` control) rather than converting in the browser; if the h/m lever is kept, divide by 6.5, not 24 — and gate it on a served session-length fact.

**Lane order** — Implement in this order.\n\n1. P0 expiry selection (unblocks everything downstream). Extend `ResearchController.expirations()` (ResearchController.java:347-355) + `ApiResponses.Expirations` (ApiResponses.java:195) so each expiration is an object `{date, tradingSessions, calendarDays}` built from `OptionTime.toExpiry(laneToday, expiry)`, and add `?horizonSessions=N` so the backend returns the chosen expiration. Then delete js/desk-backend.js:197-243 (`dateParts`/`dateOrdinal`/`sessionDistance`/`quoteAsOfDate`/`chooseExpiration`) and rewrite the two call sites (:497-498, :2723) to read the served choice. This kills findings 2, 4 and half of 1's ledger row in one change.\n\n2. P0 book-fan cut. Fastest correct move with zero Java change: at index.html:1959-1971 read `response.checkpoints.underlyingSteps[].sessionDate` (present on the same payload) instead of `displayPaths[].steps[].sessionDate` (absent). Then do it properly: add `expiryCutIndex` + `lifeSessions` to `ScenarioCanvasValuator.PositionPath` (ScenarioCanvasValuator.java:95) and delete BOTH browser implementations (index.html:1074-1105 and 1959-1971) plus `authPackageExpiration` (index.html:1066-1068).\n\n3. P1 declarations. Remove the `?45` at desk-backend.js:2147 and the `:45` at index.html:904 by moving the existing `missingDeclarations` check (desk-backend.js:2154-2160) ahead of `loadMarket`. Then fix the order horizon: replace desk-backend.js:964-966 with `Horizon.exactTradingSessions`-equivalent (`horizonDays + 'd'`), or have TradeController.java:565 read the Plan's horizon and ignore the body field.\n\n4. P1 render the receipts you already pay for. Wire `candidate.time` (already in the model at desk-backend.js:1453) into index.html:3727/3767/3800/684/3265, and bind fan axis labels + the expiry marker to `preview.sessionDates` / `preview.anchorSessionDate`. Split the `horizonDays` label into served `tradingSessions` and `calendarDays` so index.html:1889 and :2633 stop disagreeing.\n\n5. P2 sweep. Delete the 17 `sessionProgress==null?i:` fallbacks and make a missing progress an error; serve the history window as a range receipt and delete `histPresetSpan` (index.html:1351-1355); fix or remove the 24h-per-session conversion at index.html:895-896/1009-1010. Finally rewrite DUPLICATION_LEDGER.md:143 — its `index.html` half is dead, its `desk-backend.js` half is not.

## canonical Greeks wire contract (program.md §7.1, §8.2)

### [P0] The Greeks inspect-tab badge renders a cents value as dollars — a live 100× overstatement of theta on the New Idea / Decide rail.

**Evidence** — src/main/resources/public/index.html:4332 `greekLabel=af&&af.greeks&&af.greeks.theta!=null?'Θ '+signed(af.greeks.theta):c.authoritative?'checkpoint':'Θ '+signed(c.theta||0)`. `af.greeks.theta` is set from `thetaCentsPerDay` at index.html:1122 and 1126; `c.theta` is set from `thetaCentsPerDay` at index.html:435 and js/desk-backend.js:1397. `signed()` (index.html:398) calls `money()` (index.html:397), which formats its argument as whole dollars. Every other consumer of the same value divides by 100: index.html:594 `money(Number(g.thetaCentsPerDay)/100)`, index.html:750 GSPEC theta `disp:function(v){return money(v/100);}`, index.html:4135 via `gfmt(GSPEC[1],frame.greeks.theta)`. A −1400 cents/day (−$14/day) theta prints as “−$1,400”.

**Fix** — Delete the raw `signed(...)` call: format through `gfmt(GSPEC[1], af.greeks.theta)` like index.html:4135 already does. Under a GreeksView this whole line becomes one `money(view.thetaCentsPerDay/100)` call in a single formatter.

### [P0] /api/portfolio/greeks sums raw share delta across different underlyings and Home prints the sum as “delta sh” — the exact aggregation BookRiskService's own contract declares invalid.

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/TradeService.java:1902-1905 adds `view.greeks().deltaShares()`, `.gammaShares()`, `.thetaPerDay()`, `.vegaPerPoint()` over every active trade with no symbol partition, emitted at :1912-1915 as top-level `deltaShares`/`gammaShares`. BookRiskService.java:100-103 states the opposite for the same fact: “raw share delta is not additive across names, so the aggregate rides net/betaWeightedDollarDeltaCents”. The sum is rendered on Home at src/main/resources/public/index.html:2155 (`oneGreeks.deltaShares` … `'delta sh'`) and re-published as `PracticeLane.deltaShares` at BookRiskService.java:911. Client-side twin of the same error: index.html:5052 `nd+=(+p.delta||0)` printed as “Net Δ” at :5054, and the exposure map plots per-symbol deltaShares on one shared axis at index.html:4976 and :4996.

**Fix** — At ACCOUNT/BOOK scope emit `deltaShares`/`gammaSharesPerDollar` as null with "deltaShares" in the GreeksView `unavailable` list, and publish `netDollarDeltaCents`/`grossDollarDeltaCents` instead — the sum that IS additive and which TradeService.portfolioDollarDeltaBook (:1937-1965) already computes. Home and the pooled readout then read the dollar field.

### [P1] PositionGreeks.canonical() silently drops the completeness flag, and `complete` itself only tracks a missing delta — a leg with no vega contributes zero vega with no disclosure anywhere.

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/TradeService.java:117 `record PositionGreeks(… boolean complete)`; the canonical adapter at :126-133 returns `ScenarioCanvasValuator.Greeks` (src/main/java/io/liftandshift/strikebench/sim/ScenarioCanvasValuator.java:53-54) which has no completeness field. That lossy value is what ships on GET /api/trades (TradeController.java:183) and GET /api/trades/{id} (TradeController.java:487). Upstream, TradeService.java:1775-1777 sets `greeksComplete=false` only when `mark.delta()==null`; :1779-1781 folds null gamma/theta/vega in as 0. TradeService.packageGreeks repeats it at :2764 (returns null only on a null delta) and :2770-2772 (`(gamma==null?0:gamma)*mult`). No frontend file reads `.complete` on any greeks object (grep over index.html and js/desk-backend.js).

**Fix** — GreeksView carries `complete`, `unavailable: List<String>` (per dimension) and `markedLegs`/`totalLegs`; producers push the leg-level null checks into `unavailable` instead of substituting 0, and every strip renders an absent dimension as “—”.

### [P1] One trade-detail payload ships two different Greeks shapes under the same word “greeks”, in different units, and two panels six lines apart on the position screen each read a different one.

**Evidence** — GET /api/trades/{id} returns `ApiResponses.TradeDetail(trade, current, …)` (ApiResponses.java:377-379): `trade.greeks` is the canonical cents shape (TradeView.java:53, populated TradeController.java:487) while `current.greeks` is `TradeService.MarkView.greeks` = `PositionGreeks` in dollars (TradeService.java:162, 1806-1807). Frontend: index.html:609 reads `cur.greeks.thetaPerDay` and prints `money(Number(greeks.thetaPerDay))` (no ÷100); index.html:594 reads `…greeks.thetaCentsPerDay` and prints `money(Number(g.thetaCentsPerDay)/100)`. Both are labelled “Theta / day” and both render inside `renderAuthoritativePosition` (index.html:600-615) plus `authPositionScenarioStageHTML`.

**Fix** — One GreeksView on both nodes; `current.greeks` becomes the same cents-typed view (or is removed in favour of `trade.greeks`), and index.html:609 and :594 collapse to one shared money-from-cents formatter.

### [P1] BookRiskService.PracticeLane mixes dollar-denominated and cent-denominated fields inside a single record, with no name signalling the split.

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/BookRiskService.java:176-178 `record PracticeLane(Double deltaShares, Long dollarDeltaNetCents, Long dollarDeltaGrossCents, Double gammaShares, Double thetaPerDay, Double vegaPerPoint, …)`. Construction at :908-914 pulls `deltaShares`/`gammaShares`/`thetaPerDay`/`vegaPerPoint` out of the string-keyed dollar Map from `trades.portfolioGreeks(...)` (TradeService.java:1912-1915) while `dollarDeltaNetCents`/`dollarDeltaGrossCents` come from `trades.portfolioDollarDelta(...)` in cents (TradeService.java:1953-1961).

**Fix** — Replace the six greek fields with one embedded GreeksView produced by a typed `TradeService.portfolioGreeksView(accountId)`; delete the `(Double) greeks.get("deltaShares")` string lookups at BookRiskService.java:911-913.

### [P1] Four live call sites invoke `finishCand`, a function defined nowhere in the served bundle — every path through adjust mode, the build tray, and the import flow throws ReferenceError, and these are the sole producers of the fixture `c.delta`/`c.theta` that are then printed as dollars.

**Evidence** — Calls at src/main/resources/public/index.html:839 (adjustCand), :843 (baseCand), :3055 (importedPosition), :3107 (buildCandidate). `grep -rn finishCand` over src/main/resources/public/ returns only those four lines — no definition in index.html, strategies.js, learn-content.js, js/api.js or js/desk-backend.js (the only scripts loaded, index.html:62-65). Reachable from index.html:859 and :1196 (adjust active), :3125 (build tray), :4937-4938 (selectImport/setImpReal). The dead fixture greeks are consumed at index.html:3067 `delta:c.delta,theta:c.theta,vega:c.vega,gamma:c.gamma` and :3082 `'time costs ~$'+Math.abs(c.theta)+'/day'`.

**Fix** — Delete adjustCand/baseCand/importedPosition/buildCandidate and their callers (program §8.2 explicitly lists “finishCand() callers and dead import/build paths”); the adjust ghost at index.html:859 must come from a backend preview receipt instead.

### [P2] Four names for dollar delta, two for gamma-as-dollar-delta, four for vega — the same fact renamed at every producer, which is what forces per-call-site frontend normalizers.

**Evidence** — dollar delta: `netDollarDeltaCents` (BookRiskService.java:110), `dollarDeltaCents` (StanceVector.java:7), `grossCents`/`netCents` (TradeService.java:136), `dollarDeltaNetCents`/`dollarDeltaGrossCents` (BookRiskService.java:176). gamma-as-dollar-delta-per-1%: `gammaPer1PctCents` (BookRiskService.java:111) vs `gammaDollarDeltaCentsPerOnePercentMove` (StanceVector.java:8) — verified numerically identical (BookRiskService.java:328 `gamma*units*0.01*spotDollars*spot` vs StanceProfiler.java:63 `Money.toCents(gammaSharesPerDollar*(spot*0.01)*spot)`). vega: `vegaPerPointCents` (BookRiskService.java:111), `vegaCentsPerVolPoint` (StanceVector.java:9), `vegaCentsPerPoint` (ScenarioCanvasValuator.java:54), `vegaPerPoint` in dollars (TradeService.java:117).

**Fix** — Adopt §7.1's names once (`dollarDeltaCents`→`netDollarDeltaCents`, `gammaDollarDeltaCentsPerOnePct`, `vegaCentsPerVolPoint`, `thetaCentsPerDay`) and let every producer project GreeksView.

### [P2] Two independent greek engines value the same package and ride the same response, so `analytics.greeks` and `evaluation.stance` can disagree with no disclosure of why.

**Evidence** — src/main/java/io/liftandshift/strikebench/eval/StanceProfiler.java:33-66 re-derives delta/gamma/theta/vega with its own BlackScholes calls at `t = dte/365.0` (:47) and `sigma = modelVol(ctx)` (:32), while src/main/java/io/liftandshift/strikebench/paper/TradeService.java:2753-2777 (`packageGreeks`) aggregates the OBSERVED provider marks from the same preview's leg snaps. Both surface on one candidate: `analytics.greeks` (TradeService.java:2685, read at index.html:2615 and js/desk-backend.js:1024) and `stance` inside the EvaluationReceipt (ApiResponses.java:283, attached to every ranked candidate).

**Fix** — GreeksView carries `valuationBasis` (OBSERVED_MARKS | MODELED_BLACK_SCHOLES | CANVAS_PATH); StanceProfiler emits a GreeksView instead of four bare longs (it already computes deltaShares at :33/:51 and gammaSharesPerDollar at :53 and discards both), so a surface can show both and say which is which.

### [P2] Theta is per CALENDAR day at every producer but the field name and every label say only “per day”, while the §7.3 time receipt is session-based.

**Evidence** — src/main/java/io/liftandshift/strikebench/sim/PathValuationKernel.java:121 `BlackScholes.theta(...)/365.0`; src/main/java/io/liftandshift/strikebench/eval/StanceProfiler.java:65 `Money.toCents(thetaDollarsPerYear/365.0)`; provider theta passed through unchanged (src/main/java/io/liftandshift/strikebench/market/MarketDataMarks.java:137-139 ← CboeProvider.java:203). Field is `thetaCentsPerDay` (ScenarioCanvasValuator.java:54); labels are “Θ Theta / day” (index.html:750) and “Theta / day” (index.html:594, :609, :2155).

**Fix** — Add `thetaBasis` (CALENDAR_DAY | TRADING_SESSION) to GreeksView and let the label read it, so the greek strip and the §7.3 time receipt agree on which clock they use.

### [P2] `MarkView.legGreeks` ships a per-share raw greek row for every leg on the position-detail endpoint and no frontend surface reads it.

**Evidence** — Built at src/main/java/io/liftandshift/strikebench/paper/TradeService.java:1782-1791 (plus the synthetic held-shares row at :1798-1804), attached at :1851, typed at :162, published as `current.legGreeks` on GET /api/trades/{id}. `grep -rn legGreeks src/main/resources/public` returns nothing; the only readers are src/test/java/io/liftandshift/strikebench/paper/PaperCoreTest.java:1363-1364, :2038 and src/test/java/io/liftandshift/strikebench/api/ApiIntegrationTest.java:504.

**Fix** — Either type it as `List<GreeksView>` at scope=LEG with quantity=1/multiplier=leg.multiplier (useful for the §6.3 leg workbench) or delete it with its tests.

**Lane order** — GREEKSVIEW FIELD SET (covers all 9 producers found). §7.1's eight fields are necessary but not sufficient — GreekBlock's beta weighting, DollarDeltaBook's gross-vs-net, the LEG/PACKAGE/ACCOUNT/BOOK scope split, and StanceProfiler's separate valuation basis have no home in them. Proposed record: scope (LEG|PACKAGE|ACCOUNT|BOOK); quantity, multiplier (already applied — disclosure only, so nothing re-scales); deltaShares; gammaSharesPerDollar; thetaCentsPerDay; vegaCentsPerVolPoint; netDollarDeltaCents; grossDollarDeltaCents; betaWeightedDollarDeltaCents; gammaDollarDeltaCentsPerOnePct; complete; unavailable (List<String>, per dimension); markedLegs, totalLegs; thetaBasis (CALENDAR_DAY|TRADING_SESSION); valuationBasis (OBSERVED_MARKS|MODELED_BLACK_SCHOLES|CANVAS_PATH); source, freshness, observedAt, basis.

PER-CALL-SITE CONVERSION, in this order.
(1) Land the record + a `GreeksViewTest` asserting units per scope. (2) TradeService.computeMark:1806-1807 — thetaPerDay×100 and vegaPerPoint×100 → cents (the ×100 currently hiding in PositionGreeks.canonical():132), keep deltaShares/gammaShares, scope=PACKAGE, valuationBasis=OBSERVED_MARKS, populate `unavailable` from the per-leg null checks now thrown away at :1775-1781, and attach netDollarDeltaCents by reusing the deltaShares×underlyingCents math already in portfolioDollarDeltaBook:1953. Then delete PositionGreeks and canonical(). (3) TradeService.packageGreeks:2753-2777 — numbers already correct (×100 at :2776); replace the `(x==null?0:x)` substitutions at :2770-2772 with `unavailable` entries. (4) ScenarioCanvasValuator:546/554/594/602 — no numeric change (Money.toCents already yields cents); set scope=LEG for LegDay/LegStep and PACKAGE for PositionDay/PositionStep, valuationBasis=CANVAS_PATH, thetaBasis=CALENDAR_DAY. (5) TradeService.portfolioGreeks:1912-1915 — stop summing share delta across symbols (finding F-P0#2): null those two with `unavailable`, publish net/gross dollar delta cents, ×100 the theta/vega. (6) BookRiskService.greekBlock:349 — rename vegaPerPointCents→vegaCentsPerVolPoint and gammaPer1PctCents→gammaDollarDeltaCentsPerOnePct, map optionLots/markedOptionLots→totalLegs/markedLegs, keep betaWeightedDollarDeltaCents. (7) BookRiskService.practiceLane:908-914 — delete the four `(Double) greeks.get("...")` string lookups, embed the GreeksView from step 5 plus the DollarDeltaBook cents. (8) StanceProfiler:62-79 — emit GreeksView (it already has deltaShares at :33/:51 and gammaSharesPerDollar at :53 and discards them), valuationBasis=MODELED_BLACK_SCHOLES; StanceVector embeds it rather than restating four longs. (9) MarkView.legGreeks:1782-1791 → List<GreeksView> at scope=LEG, or delete.

FRONTEND DELETIONS THIS ENABLES (nothing in the browser scales by quantity or by 100-per-contract today — verified against every `.delta`/`.gamma`/`.theta`/`.vega` read; the only client math is cents→dollars): index.html:431-435 (authTradePosition flattening), :594 (÷100 on theta and vega), :609 (money() with no ÷100 — the dollar-dialect twin), :750-751 (GSPEC `money(v/100)` and the `/100` bar widths), :1119-1127 (authoritativeFrame field rename), :2155 (Home dollar dialect), :2615-2617 (third copy of the flattening), :4332 (the 100× bug); js/desk-backend.js:1379-1398 (canonicalGreeks/applyGreeks) and :1405-1500 (candidateToDesk's duplicate flattening). After the cutover every surface reads `view.thetaCentsPerDay` and formats through one `money(cents/100)` helper, and GSPEC keeps only label/colour/width — no unit knowledge.

FIX THE 100× BADGE (index.html:4332) IMMEDIATELY, ahead of the record work — it is a wrong number on screen today and the one-line fix (`gfmt(GSPEC[1], …)`) is already the pattern at index.html:4135.

## Mechanical management policy ownership (program.md §7.5) — the 50% profit rule, the 2× loss rule, the 21-DTE rule

### [P0] Five independent owners express the same three rules with different values, denominators, and conditions; none is authoritative. ProtocolEvaluator: 50% of credit / 2.0× credit / 50% of debit both ways / 21 days. ManagementPlanner: prose only, and its debit take-profit is a RANGE "~50–100% of the debit" (disagrees with the 50% point) plus untyped state conditions ("with the trade untested", "without follow-through") that no evaluator checks. TradeService.dtePlan: tiers by trading sessions, take-profit at "50% of the MAXIMUM" (max profit, not credit), and in the 6–15-session tier the credit stop is "the credit received (1x)" — half of ProtocolEvaluator's 2×. Backtester: 50% of maxProfitCents, stop 80% of maxLossCents, time exit at 7 DTE. AccountObjectiveService.LifecyclePolicy STANDARD_V1: harvest at 75% captured premium with ≤$100 residual, cheap-risk-removal at 35% of assignment dollars, assignment decision at 5 DTE.

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/ProtocolEvaluator.java:19-27 (CREDIT_TAKE_PROFIT_FRACTION=0.50, CREDIT_STOP_MULTIPLE=2.0, DEBIT_TAKE_PROFIT_FRACTION=0.50, DEBIT_STOP_FRACTION=0.50, TIME_RULE_DAYS=21) and :55-71; src/main/java/io/liftandshift/strikebench/eval/ManagementPlanner.java:22-39 ("credit decays to ~50% of what you collected", "loss reaches ~2x the credit received", "~21 days to expiry with the trade untested", "gain reaches ~50–100% of the debit paid"); src/main/java/io/liftandshift/strikebench/paper/TradeService.java:2793-2812 (:2804 "Take profit at 50% of the maximum", :2805 "Stop if the loss reaches the credit received (1x)", :2811 "Stop at 2x the credit received", :2812 "Manage or roll around 21 days"); src/main/java/io/liftandshift/strikebench/backtest/Backtester.java:367-369 + 416-420; src/main/java/io/liftandshift/strikebench/paper/AccountObjectiveService.java:91-121 (standard() = 75.0, 10_000L, 0.35, 5)

**Fix** — Create one named record ProtocolEvaluator.Policy(policyId, creditTakeProfitFraction, creditStopMultiple, debitTakeProfitFraction, debitStopFraction, timeRuleDays, nearExpirySessions, assignmentDecisionDte, harvestCapturedPremiumPct, harvestRemainingPremiumMaxCents, cheapRiskRemovalMaxPctOfAssignment, expectedShortfallDefendCents, defendConfirmedEvents) absorbing AccountObjectiveService.LifecyclePolicy's fields; make rules()/evaluate() take it; delete TradeService.dtePlan(), make ManagementPlanner render rules() output, and make Backtester call evaluate() per replay day with a named ad-hoc policy built from the request overrides.

### [P0] ProtocolEvaluator's rules reach no user-visible surface, and the one policy the served UI does show (LifecyclePolicy) has no stop-loss and no expiry/time rule at all — so §6.4's named DEFEND triggers "stop-loss rule" and "expiry/time rule" are unimplemented. The served frontend is now only index.html + app.css + js/api.js + js/desk-backend.js; no file under public/ references managementPlan, protocolAdherence, /api/alerts, TAKE_PROFIT or STOP_LOSS. The Position surface consumes only the lifecycle receipt.

**Evidence** — grep over src/main/resources/public/ for managementPlan|protocolAdherence|api/alerts|TAKE_PROFIT|STOP_LOSS returns nothing (all views-*.js, learn.js consumers are staged deletions in `git status`); src/main/resources/public/js/desk-backend.js:3052 + 3175-3222 (Book hydrates only trade-detail `analysis`); src/main/java/io/liftandshift/strikebench/api/TrackedPackageAnalysisService.java:70-97 (analysis = lifecycle receipt + PositionLifecycleDecisionService.analyze, no ManagementPlan, no ProtocolEvaluator); src/main/java/io/liftandshift/strikebench/position/PositionLifecycleDecisionService.java:142-159 (dimensions: mechanics/capacity/limits/economics/tail-event/carry/history) and :332-340 (mechanics = close executability only)

**Fix** — Have PositionLifecycleDecisionService take ProtocolEvaluator triggers as inputs and add STOP_LOSS/TIME_EXIT/TAKE_PROFIT to the verdict lane (DEFEND names the fired trigger, HARVEST cites TAKE_PROFIT or the harvest triggers), then render the typed trigger list on the Position surface in index.html (rule id, threshold value + unit, fired/not, policyId) instead of any prose threshold.

### [P1] Four different clocks for "near expiry" on the same package: ProtocolEvaluator's 21 is calendar days, dtePlan tiers on trading sessions, AlertCenterService's dedupe window is 5 trading sessions, and the lifecycle assignment gate is 5 calendar days.

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/ProtocolEvaluator.java:27 + :93-94 with calendar days supplied at src/main/java/io/liftandshift/strikebench/paper/AlertCenterService.java:289-291 (ChronoUnit.DAYS); src/main/java/io/liftandshift/strikebench/paper/TradeService.java:2793 + 2802 (tte.sessions()); src/main/java/io/liftandshift/strikebench/paper/AlertCenterService.java:54 (EXPIRY_SESSIONS=5) + :298 + :327; src/main/java/io/liftandshift/strikebench/position/PositionLifecycleDecisionService.java:392-394 (carryCollateral().calendarDaysRemaining() <= policy.assignmentDecisionDte()=5)

**Fix** — Put both units in the Policy record (timeRuleDays as calendar, nearExpirySessions as trading sessions), have ProtocolEvaluator accept an OptionTime.Measure instead of a bare Integer so every caller passes the one measured receipt, and delete AlertCenterService.EXPIRY_SESSIONS in favour of the policy field.

### [P1] The time rule fires at entry for any package opened at ≤21 DTE, and the campaign review seeds that entry-time trigger, so a 14-DTE credit spread can never be adherent — with no recorded ROLL/CLOSE it is finally scored OVERRIDDEN. The same rule also behaves differently after expiry: CampaignService clamps negative DTE to 0 (so an expired leg still triggers) while ProtocolEvaluator's own guard requires daysToNearestExpiry >= 0.

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/CampaignService.java:974-978 (evaluate at decision time with pnl 0 and entryDte, seen.putIfAbsent → actionIndex -1), :1024-1026 (response == null && finalReview → "OVERRIDDEN"), :1057-1061 (daysToExpiry clamps with Math.max(0, ...)); src/main/java/io/liftandshift/strikebench/paper/ProtocolEvaluator.java:93-94

**Fix** — Make the time rule state-aware inside ProtocolEvaluator: return a NOT_APPLICABLE rule when the package was already inside the time window at entry (entry DTE <= timeRuleDays), and represent post-expiry as a distinct EXPIRED status rather than clamping to 0 in the caller.

### [P1] The three owners disagree on what makes a package a credit trade. ProtocolEvaluator treats a flat 0 net as a debit and skips price rules entirely at basis 0; TradeService treats flat 0 as a credit; ManagementPlanner classifies on the STOCK-INCLUSIVE net, so every buy-write/covered call is labelled "Debit/directional" and told to stop "when the loss reaches ~50% of the debit paid" — a 50% drawdown of the share purchase. Candidate.optionNetPremiumCents exists precisely for this and is unused.

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/ProtocolEvaluator.java:56 + :79 + :86 (basis > 0 guard); src/main/java/io/liftandshift/strikebench/paper/TradeService.java:2678 (dtePlan(tte, entryNet >= 0)); src/main/java/io/liftandshift/strikebench/eval/ManagementPlanner.java:15 + :32-39; src/main/java/io/liftandshift/strikebench/recommend/Candidate.java:24-25 ("INCLUDES any stock leg (a buy-write is net-negative)" vs optionNetPremiumCents); src/main/java/io/liftandshift/strikebench/api/TradeController.java:770-774

**Fix** — Add ProtocolEvaluator.side(long optionNetPremiumCents) returning CREDIT/DEBIT/FLAT (0 is FLAT, never credit) and require every caller — ManagementPlanner, TradeService, AlertCenterService — to classify on option-only net through it.

### [P1] The identical 50%/2× thresholds are applied to two different bases depending on lane: practice trades pass the stock-inclusive trade entry net, tracked structures pass an option-legs-only sum of each lot's economic remaining open amount.

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/AlertCenterService.java:213 (t.entryNetPremiumCents()) vs :232-249 (entryNet accumulates only when lot.option(); passes `hasOption ? entryNet : 0`)

**Fix** — Compute one canonical option-only signed entry basis in a single helper on ProtocolEvaluator (or a Basis value type it owns) and have both lanes call it.

### [P1] dtePlan's near-expiry regime is consumed by string sniffing rather than a typed value, so renaming the prose silently drops a required risk acknowledgment.

**Evidence** — src/main/java/io/liftandshift/strikebench/api/TradeController.java:805-809 (String.valueOf(plan.get("regime")).contains("near-expiry")); produced at src/main/java/io/liftandshift/strikebench/paper/TradeService.java:2794; pinned by src/test/java/io/liftandshift/strikebench/paper/PaperCoreTest.java:688

**Fix** — Emit a typed enum regime (NEAR_EXPIRY/SHORT_DATED/STANDARD) from ProtocolEvaluator and switch on it in requiredAcksFor and the test.

### [P1] The designated policy owner is the only one that is neither named nor configurable: ProtocolEvaluator's thresholds are static finals with no policyId and no per-account override, while LifecyclePolicy is validated, named, fingerprinted, and persisted per objective revision — violating §7.5's "policy selection is configurable and named".

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/ProtocolEvaluator.java:19-27 (public static final, no id); src/main/java/io/liftandshift/strikebench/paper/AccountObjectiveService.java:91-121 + :245 (capacity_policy jsonb) ; src/main/java/io/liftandshift/strikebench/position/PositionLifecycleDecisionService.java:128-131 + :196-201 (policy_id, policy_fingerprint persisted); src/main/resources/db/migrations/V5__position_lifecycle_decisions.sql:7

**Fix** — Resolve the ProtocolEvaluator.Policy from the same account objective revision LifecyclePolicy comes from, carry policyId + fingerprint on every Rule/Trigger, and keep LifecyclePolicy as a view over that one record rather than a second threshold set.

### [P2] ManagementPlan.Rule carries untyped kebab-case kinds that do not match ProtocolEvaluator's rule constants, and carries no trigger values at all (prose only), so no consumer can compare a rule to a live mark or join the two vocabularies.

**Evidence** — src/main/java/io/liftandshift/strikebench/eval/ManagementPlanner.java:22-42 ("take-profit","stop","roll","time","assignment","invalidation") + src/main/java/io/liftandshift/strikebench/eval/ManagementPlan.java:16 (Rule(kind,trigger,action), no cents/DTE) vs src/main/java/io/liftandshift/strikebench/paper/ProtocolEvaluator.java:44-50 (Rule(rule, triggerPnlCents, triggerDaysToExpiry, summary), TAKE_PROFIT/STOP_LOSS/ROLL/TIME_EXIT). The now-deleted js/views-plan.js:117-121 mapped ManagementPlan kinds through the ProtocolEvaluator vocabulary, so "take-profit", "stop" and "time" all fell through to the generic "Management rule" label (evidence in git HEAD only — file is a staged deletion).

**Fix** — Delete ManagementPlan.Rule's free-text kind; have ManagementPlanner return ProtocolEvaluator.Rule plus display copy, adding ASSIGNMENT and INVALIDATION as rule constants owned by ProtocolEvaluator.

### [P2] In the tracked lane the entry basis for the 50%/2× lines is the whole lot's economic remaining open amount while the close value is priced on the structure's allocated quantity, so a partially allocated lot compares mismatched quantities and the rules fire at the wrong P/L.

**Evidence** — src/main/java/io/liftandshift/strikebench/paper/AlertCenterService.java:241-242 (entryNet += lot.economicRemainingCents()) vs :245 (closeCents(..., lot.quantity(), ...)) with the SQL at :493-500 selecting psm.allocated_quantity as quantity but pl.economic_remaining_open_amount_cents as the basis

**Fix** — Prorate the lot's economic remaining amount by allocated_quantity / remaining_quantity before summing, inside the shared basis helper described above.

### [P2] Backtester exits are a fourth policy dialect with different denominators and defaults, so a backtest never tests the protocol the product tells the user to follow.

**Evidence** — src/main/java/io/liftandshift/strikebench/backtest/Backtester.java:367-369 (profitTarget 0.50, stopFraction 0.80, rollDte 7) and :416-420 (0.50 × maxProfitCents; 0.80 × maxLossCents; ChronoUnit.DAYS <= rollDte)

**Fix** — Replace the three inline comparisons with ProtocolEvaluator.evaluate() per replay day, constructing a named ad-hoc policy from the request's profitTargetPct/stopFraction/rollDte overrides so the deviation is declared rather than implicit.

**Lane order** — Order: (1) Introduce ProtocolEvaluator.Policy — the named/versioned record that absorbs AccountObjectiveService.LifecyclePolicy's fields (harvestCapturedPremiumPct, harvestRemainingPremiumMaxCents, cheapRiskRemovalMaxPctOfAssignment, assignmentDecisionDte, expectedShortfallDefendCents, defendConfirmedEvents) alongside the 50%/2×/21 thresholds, resolved from the account objective revision and fingerprinted on every Rule and Trigger; LifecyclePolicy becomes a view over it, not a second threshold set. (2) Add to ProtocolEvaluator: side() credit/debit/FLAT classification on option-only net, a canonical option-only entry-basis helper (prorated by allocated quantity for tracked lots), an OptionTime.Measure-based time input carrying both calendar days and sessions, a typed Regime enum with the NEAR_EXPIRY \"rolling is not a plan\" rule, ASSIGNMENT and INVALIDATION rule constants, HARVEST triggers (captured-premium %, residual ceiling, cheap-risk-removal), and NOT_APPLICABLE/EXPIRED rule statuses. (3) Convert producers: delete TradeService.dtePlan() (buildAnalytics:2678 emits {policyId, regime, typed rules}); ManagementPlanner returns ProtocolEvaluator.rules() plus display copy and drops its own thresholds; Backtester's three inline exits call evaluate(). (4) Convert consumers to typed triggers: TradeController.requiredAcksFor switches on the regime enum instead of contains(\"near-expiry\"); AlertCenterService drops EXPIRY_SESSIONS and both lanes pass the canonical basis; CampaignService.protocolAdherence stops clamping DTE and honours NOT_APPLICABLE at entry; PositionLifecycleDecisionService consumes TAKE_PROFIT/STOP_LOSS/TIME_EXIT/HARVEST/ASSIGNMENT triggers so DEFEND can name a real fired trigger per §6.4. (5) Only then wire the served surface: index.html renders the typed trigger list (rule id, threshold value + unit, fired/not, policyId) — today it renders none of these, which is why the drift has been invisible. Tests to update in the same pass: PaperCoreTest.java:678-692 (regime string), AlertCenterServiceTest.java:365-380, CampaignServiceTest.java:368-394.

## Remaining browser-originated financial facts (program.md §3.1, §3.5, M1 leftovers) in src/main/resources/public/index.html

### [P0] The Home exposure-map box-select prints browser-summed Book totals — Capital, Net Δ, Book risk %, and "Defined max loss" — and Capital and Defined max loss are literally the same sum printed twice with opposite signs.

**Evidence** — index.html:5056 `var cap=0,nd=0,risk=0,defined=0; lines.forEach(function(p){cap+=p.cap;nd+=(+p.delta||0);risk+=p.riskPct;defined+=Math.max(0,Number(p.cap)||0);});` → index.html:5057-5058 `'<span><i>Capital</i>'+money(cap)+'</span><span><i>Net Δ</i>'+(nd>=0?'+':'')+nd.toFixed(2)+'</span>' + '<span><i>Book risk</i>'+Math.round(risk)+'%</span><span ...><i>Defined max loss</i>'+money(-defined)+'</span>'`. Live surface: drawExposureMap is wired on Home at index.html:2501 (`{svg:'homeRiskMap', info:'homeRiskInfo', hover:true, zoom:true}`) and wireExpMapSelect at index.html:5025. A measured joint-Book receipt that owns exactly this fact already exists and is read at index.html:1922-1927 (`authJointBookFan` → `measured.scenario.stepBands`, `chanceOfGainPct`).

**Fix** — Delete the four accumulators and the `.rkselstats` block; call the Book-risk endpoint with the selected trade ids and render the returned pooled receipt, or state `Pooled Book receipt unavailable for this selection` when none is served. Never sum per-position maxLoss as a Book figure — correlation is the server's job (BookRiskService).

### [P0] The Decide "Screens & caps" drawer invents every number it shows: the caps come from a hardcoded browser POSTURE, the screens (min POP 55%, max assignment 60%, max gap loss $25k) are hardcoded, the pass-counts are computed in the browser, and the panel header contradicts its own rows because candGated() returns null for every authoritative candidate.

**Evidence** — index.html:3386 `govs:{risk:POSTURE.risk,bp:POSTURE.bp,minPop:55,maxAsn:60,gapLoss:POSTURE.gapLoss||25000}` (POSTURE.gapLoss is never set — index.html:2990 `var POSTURE={goal:'Income', view:'Neutral', risk:5000, bp:50000, lane:'paper'}`). index.html:4013-4016 `passPop=cs.filter(function(c){return c.pop>=g.minPop;}).length; passAsn=cs.filter(function(c){return (c.assign||0)<=g.maxAsn;}).length; passGap=...; shown=cs.filter(function(c){return !candGated(c);}).length;` while index.html:3154 `function candGated(c,ctx){if(c&&c.authoritative)return null;...}` — so `shown` is always N. index.html:4027-4028 prints `money(used)`, `money(free)`, `'of your '+money(rc)+' cap'`. Reachable: the rail button at index.html:3695 (`data-tool="caps"`) opens it via index.html:3699.

**Fix** — Delete governorsPanel and decide.govs. Screens and caps belong to the account/Plan risk policy the backend already applies (AccountFit / Guardrails on the preview response); render those returned constraints and their pass/fail verdicts, or show `Screens unavailable` — do not let the browser define a threshold no order path enforces.

### [P0] The Fit tab prints a capital-headroom sentence computed entirely in the browser against the same invented caps.

**Evidence** — index.html:4121 `var collateral=isCollat(c)||c.undef, cap=collateral?decide.govs.bp:decide.govs.risk, used=c.cap||c.maxLoss, pct=Math.min(100,used/Math.max(1,cap)*100), room=cap-used;` → index.html:4122-4124 `money(used)+' / '+money(cap)` and `(room>=0?money(room)+' remains after this package.':'Over the stated cap by '+money(-room)+'.')`. Rendered at index.html:4237 (`decide.inspect==='fit'`).

**Fix** — Replace with the preview's AccountFit/reserveCents receipt (already on the wire — index.html:4404 reads `preview.reserveCents`); if the fit receipt is absent, show the cap as unavailable rather than deriving `cap - used`.

### [P0] M1 item (b) — the package-price reconciliation receipt (§3.3 / §7.2) does not exist anywhere: not in Java, not on the wire as a unified object, not on screen. The frontend still picks among competing price fields itself, and the same package shows option-only net premium in one place and whole-package executable net in another with no explanation.

**Evidence** — No `PackagePrice`/`priceReceipt` type exists — `grep -rn "PackagePrice|packagePrice|PriceReceipt|priceReceipt" src/main/java src/main/resources/public` returns nothing. Fees are on the wire but never displayed: ApiResponses.java:346 `record OrderSummary(int qty, long proposedNetCents, long feesOverrideCents, ...)` and the only frontend mention is desk-backend.js:971-972 sending `proposedNetCents: null, feesOverrideCents: null`. The frontend chooses fields: index.html:4346-4355 `authoritativeOrderValue` falls `valuedNetCents` → `executableNetCents` → `orderInstruction.limitNetCents`, and index.html:4340-4345 `estPrice` falls back to `c.credit` (the analyzed candidate net) when no valuation exists — then feeds that into the signed limit control (index.html:4567). Divergent amounts on one screen: KPI row prints option-only net (index.html:3202 via `candCollect` at index.html:3975 → `c.optionNet`), fan row the same (index.html:3993), map card the same (index.html:4553), but the Execute dock and Review print `eNet = authoritativeOrderValue(order)` (index.html:4406, 4430, 4457). index.html:1278 `sb('Reconciliation', liq.reconciliationStatus...)` is the account cash tie-out, not a package price receipt.

**Fix** — Add the §7.2 record (quantity, optionNetPremiumCents, stockCashFlowCents, grossPackageNetCents, openingFeesCents, afterFeeNetCents, executableNetCents, restingLimitNetCents, valuationBasis, executability, source, freshness, observedAt, fingerprint) on candidate/preview/order/review/close; render it once as a receipt block under the Execute dock; delete authoritativeOrderValue and estPrice's `c.credit` fallback so the browser stops choosing.

### [P0] M1 §3.5 — all four defaults the spec names by name (Income, Neutral, 45 days, Balanced) are still silently applied and then treated as declarations.

**Evidence** — index.html:2990 `var POSTURE={goal:'Income', view:'Neutral', risk:5000, bp:50000, lane:'paper'}`; index.html:2424 `postureName()` maps risk 5000 → 'Balanced' (RISK_PRESETS at index.html:2423). index.html:3362-3366 enterDecide seeds `goal:...POSTURE.goal`, `view:...POSTURE.view`, `horizon: ...(kind==='idea'&&String(POSTURE.goal||'').toLowerCase()==='income'?'45 days':'1 day')`, `riskMode: ...postureName()`. index.html:903 `authoritativeHorizon` ends `: 45;` and is printed as `'+'+Math.round(authLifeSessions(p))+'d'` at index.html:799. index.html:1692 `intent=String(HOME_SCOUT.goal||'INCOME').toUpperCase()` prints "N structures compete for income" even though HOME_SCOUT.goal starts null (index.html:1240). index.html:1764 `horizon=/^7\b/.test(homeIdea.horizon)?'week':'month'` and index.html:1765 `thesisOverride:String(homeIdea.view||'Neutral').toLowerCase()` are sent to the backend scout. index.html:3153 cohCtx falls back to `{view:POSTURE.view, goal:POSTURE.goal, ...}`.

**Fix** — Delete POSTURE/RISK_PRESETS/postureName. enterDecide must start every undeclared field as null and render `name what remains to be chosen`; authoritativeHorizon must return null (surface "horizon unavailable") instead of 45; the Home scout must refuse to send an undeclared intent/thesis/horizon rather than substituting INCOME/Neutral/month.

### [P1] Each position's share-of-book risk is computed in the browser from two backend cents fields and printed on every roster card, in the position's management list, and in the risk-map hover card — and when the book's total max loss is zero/absent it silently prints 0% instead of unavailable.

**Evidence** — index.html:433 `riskPct:total>0?Math.max(0,Math.min(100,loss/total*100)):0` (from `heat.totalMaxLossCents` and `trade.maxLossCents`, index.html:425). Printed at index.html:452 `'<span class="csharev">'+(share?share.toFixed(0)+'%':'—')+'</span>'` on every card face; index.html:529 `rows.push(['Book share',Math.round(p.riskPct)+'% of book risk'+(rank?' · '+ordinal(rank)+' of '+POS.length:'')])` (rank is also browser-derived, index.html:528) rendered at index.html:621; index.html:5087 `'<span class="kk">Book risk</span><span class="vv">'+p.riskPct+'%</span>'`. It also drives the exposure-map heat channel and dot radius (index.html:4988, 5001).

**Fix** — Have the book payload serve a per-trade `shareOfBookMaxLossPct` (BookRiskService already owns totalMaxLossCents); render it verbatim and show `—` when absent. Remove the `:0` fallback in either case.

### [P1] The payoff hover tooltip prints an interpolated P/L figure and a browser-computed percentage move as if they were engine outputs — this is the most-touched originator on the desk (Home peek, Position hero, Decide hero all share it).

**Evidence** — index.html:1196-1198 `var pl=payFor(cur, price), pct=(price/p.spot-1)*100; ... tip.innerHTML='<b>'+Math.round(price)+'</b><span class="tpct">'+(pct>=0?'+':'')+pct.toFixed(1)+'%</span><span class="tpl '+(pl>=0?'pos':'neg')+'">'+money(pl)+'</span>';` where payFor (index.html:140-153) linearly interpolates between the served payoffPoints. Same class of print at index.html:596 `var unchanged=payFor(p,p.spot)` → `cell('If price holds',unchanged==null?'unavailable':authSigned(unchanged),...)` on the Position payoff key.

**Fix** — §3.1 permits interpolating supplied points to draw a line, not to print a number. Snap the readout to the nearest served checkpoint and label it (e.g. `nearest priced checkpoint $X → $Y`), or request a valuation at the hovered price. For "If price holds", read the at-spot checkpoint the payoff receipt already contains rather than interpolating.

### [P1] The scenario ribbon prints a browser-computed probability ("N% of paths fit this story") and, before the authoritative frame arrives, a browser-fabricated underlying price.

**Evidence** — index.html:785 `var pinProb=(ar&&ar.sourcePathCount?Math.round(ar.withinToleranceCount/ar.sourcePathCount*100):null);` and index.html:786 `var af=authoritativeFrame(p,t),curPx=af?af.price:p.spot*(1+mag*t/100),curM=(curPx/p.spot-1)*100`; index.html:787-789 appends `scenReadoutHTML(p,curM,curPx,ivsh,dfwd,pv)` unconditionally, which prints `'<b>'+p.sym+'</b> → <b>'+Math.round(curPx)+'</b>'` (index.html:1180). The same pinProb ratio is recomputed at index.html:1146-1147 inside renderAtPrice.

**Fix** — Serve the match fraction on the animation receipt (the backend already sends withinToleranceCount/sourcePathCount, so it can send the ratio) and read it. Guard the readout on `af` the way renderAtPrice does at index.html:1147 — emit `valuing this path…` instead of `p.spot*(1+mag*t/100)`.

### [P1] The market chart derives the expected-move percentage bands in the browser from three backend prices and prints them beside the σ+/σ− labels.

**Evidence** — index.html:1462 `var eAnchor=authNumber(em.anchorSpot),eUp=(eAnchor&&eAnchor>0)?(emP84-eAnchor)/eAnchor*100:null,eDn=(eAnchor&&eAnchor>0)?(emP16-eAnchor)/eAnchor*100:null;` printed at index.html:1466 `'σ+ '+emMoney(emP84)+(eUp!=null?' ('+(eUp>=0?'+':'')+eUp.toFixed(1)+'%)':'')` and index.html:1468 for σ−.

**Fix** — Add `upPct`/`downPct` to the /api/research/{symbol}/expected-move receipt and print them; §3.1 lists expected moves explicitly.

### [P1] Two hardcoded product judgments still originate risk classifications in the browser: the stress column's severity colours and an implied-vol unit guess.

**Evidence** — index.html:3990 `gcls=gl==null?'':gl<=-15000?'bad':gl<=-6000?'warn':'ok'` colours the ranked fan's stress cell at fixed −$15,000 / −$6,000 with no relation to account size or the backend's shock. index.html:3772 `if(iv!=null)part('legdetail-wide','IV '+(Math.abs(iv)<=2?iv*100:iv).toFixed(1)+'%');` guesses whether the served IV is a fraction or a percentage — the frontend choosing among competing interpretations of one field.

**Fix** — Take the stress severity band from the backend risk receipt (evaluation.risk already carries tailLossCents/maxLossCents). Pin the chain IV unit in the wire contract (fraction) and drop the magnitude heuristic.

### [P2] `finishCand` is called in four places but is not defined anywhere in the repository — the leg-engine it belonged to was deleted without removing its callers.

**Evidence** — `grep -rn finishCand src/` returns only call sites: index.html:839 (adjustCand), index.html:843 (baseCand), index.html:3055 (importedPosition), index.html:3107 (buildCandidate). Reachability today: `adjust` is only ever assigned null (declared index.html:817, cleared index.html:2213) so 839/843 are dead; importedPosition is gated off on the served desk by index.html:4874-4877 (`if(deskBackendEnabled()){window.location.href='/index.html#/portfolio/book/activity'; return;}`); buildCandidate is reached from activeCand at index.html:3121-3122 only when `!deskBackendEnabled() || decide.kind!=='idea'`, and every enterDecide caller passes 'idea' (index.html:1793, 1796, 1813, 2473, 3016).

**Fix** — Delete adjustCand, baseCand, buildCandidate, importedPosition, IMPORTS, renderImport/renderImportPreview/selectImport/setImpReal/confirmImport/wireImport, `adjust`, DEC_BOOKCAP, and the now-unreachable `decide.kind==='roll'|'close'` branches (renderClose index.html:3868, the 'Roll ' labels at index.html:4429/4455). Verify-then-delete per M11.

### [P2] A seeded pseudo-random price sparkline generator survives in the served file; it fabricates a 30-point price series from the ticker's character codes and colours it green/red.

**Evidence** — index.html:381-392 `function priceSpark(svg,p){ ... var seed=0; for(var i=0;i<p.sym.length;i++) seed+=p.sym.charCodeAt(i)*(i+7); ... seed=(seed*9301+49297)%233280; v+=((seed/233280)-0.47)*2; ... var up=a[n-1]>=a[0]; svg.innerHTML='<path ... stroke="'+(up?'var(--profit)':'var(--loss)')+'" .../>';}`. Callers index.html:2204 and index.html:2506 query `.plspark`, which the authoritative card never renders — authFaceHTML emits `class="cplspark"` (index.html:452) — so it no-ops today. One class-name change re-arms it.

**Fix** — Delete priceSpark and both call sites; the real mark history already renders through drawPlSpark (index.html:2425-2427) off `p.marksHistory`.

### [P2] Several silent zero/absent substitutions turn missing evidence into a stated figure rather than `unavailable`.

**Evidence** — index.html:4014 `(c.assign||0)<=g.maxAsn` — a candidate with no assignment probability counts as passing at 0%. index.html:113 `var medianMove=median?((Number(median)/p.spot-1)*100):0;` — with no ensemble median the 0% tile is selected and labelled "Median terminal neighborhood" at index.html:3861. index.html:1719 `Math.round(authNumber(row.score)||0)+' decision'` prints 0 for an absent score. index.html:433 `:0` for riskPct (above).

**Fix** — Propagate null and render the `—`/`unavailable` path already used elsewhere on those surfaces; for the median tile, show no "median" marker when the ensemble median is absent.

### [P2] The Learn lens still carries a generic client leg-payoff engine.

**Evidence** — index.html:5125-5127 `function learnPay(d,S){ ... var v=d.net||0; (d.legs||[]).forEach(function(l){ var inn=l.t==='c'?Math.max(S-l.k,0):l.t==='p'?Math.max(l.k-S,0):(S-l.k); v+=l.q*inn; }); return v; }` used by drawLearnPay (index.html:5128-5143) for the strategy explainer shape.

**Fix** — Acceptable as-is only because it draws a shape with no printed money and no user position — but label the SVG as an illustration of a generic example. If any number is ever printed from it, it becomes a §3.1 violation.

**Lane order** — State of the four M1 items, then the order to work.\n\n(a) STABLE PLAN-ID 409 RECOVERY — IMPLEMENTED, UNTESTED. index.html contains zero 409 handling (correct; it delegates). The recovery lives in js/desk-backend.js:783-812: on a create 409 it clears the persisted session key (clearSessionRequestId, js/desk-backend.js:625-628), re-lists /api/plans for a concurrent mutable match, then rotates the key once (sessionRequestId(identity, rotate) js/desk-backend.js:613-622) and retries exactly once, surfacing a distinct terminal error on a second 409 — no loop. It also re-runs that recovery when the returned plan is stale/frozen (js/desk-backend.js:814-818), and a separate 409 path re-reads the exact plan after a declaration version conflict (js/desk-backend.js:2282-2296). The chain is sound end to end: PlanService.java:538-542 throws `IllegalStateException("clientRequestId was already used for a different plan request")`, ApiServer.java:551-553 maps IllegalStateException → 409 `{error:"conflict", detail:msg}`, api.js:38-41 attaches `err.status`. Two real gaps: the trigger is a message regex (`!/clientRequestId/i.test(createError.message)`, js/desk-backend.js:796) against a Java string literal, so a copy edit silently disables recovery; and nothing tests it — PlanApiIntegrationTest.java's 409s are expectedVersion/market-mismatch/archived (lines 108, 113, 133, 158, 244, 435) and all four stubs in dom-tests/desk-backend.test.js (1507, 1597, 1845, 1862) are expectedVersion conflicts. Fix: give the conflict a typed code on ErrorBody.error (e.g. "plan_request_conflict") and switch the guard to that; add one JUnit test posting the same clientRequestId with a changed body, and one DOM test for the rotate-once path.\n\n(b) PACKAGE-PRICE RECONCILIATION RECEIPT — NOT DONE. No §7.2 object exists in Java or on screen; fees ride the wire (ApiResponses.java:346 feesOverrideCents) and are never displayed; the frontend still chooses among competing price fields (index.html:4340-4355) and shows option-only net in the KPI/fan/map-card while the dock and review show whole-package valued net, with nothing explaining the difference.\n\n(c) DYNAMIC RISK-MAP DOMAIN — DONE for both maps. Decide risk/reward map derives both axes from the drawn set (index.html:4480-4483: popPad/popLo/popHi from actual POPs, cPad/cLo/cHi from edge + edgeLow/edgeHigh), with the EV=0 reference used only when the axis straddles zero (index.html:4489). Home exposure map derives cHi from max capital and dMax from max |delta| (index.html:5001-5003) and its colour domain from max riskPct (index.html:4988-4989). Residual hardcodes are pad floors (Math.max(3,…), Math.max(100,…), Math.max(0.8,…)) and the ±800 / Math.max(300,credit) domain in the non-authoritative branch, which is dead. Only real caveat: the Home map's heat and radius channels are driven by the browser-derived riskPct (finding above).\n\n(d) EXPLICIT DUAL-EV RECEIPT — DONE. evReceiptHTML (index.html:4085-4095) renders realized-volatility EV after costs as the primary lane and market-implied EV as an explicitly captioned cost benchmark ("Risk-neutral price/cost benchmark; discloses spread and fees, not an independent edge test"), plus a sensitivity band from realisticEvLow/HighAfterCostsCents; the coherence prose names both (index.html:3188-3190) and the brief keeps the benchmark framing (index.html:4107-4109). Backend fields confirmed (AutoRecommender.java:112-114, PortfolioOptimizer.java:36-37). One cosmetic gap: the always-visible brief shows only the market-EV benchmark, while the primary realized-vol EV appears only in the fan row's EV column and inside the Fit tab — put the primary lane in the brief too.\n\nORDER OF WORK (highest damage first):\n1. Kill the invented risk economy: delete decide.govs, governorsPanel, fitBudgetPanel and the box-select pooled totals, replacing them with the backend AccountFit/Guardrails/BookRisk receipts. These are the only places on the served desk that show the user a dollar cap, a pass/fail screen, and a Book max-loss the server never stated — and the pooled block contradicts itself.\n2. Build the §7.2 package-price receipt (b), then delete authoritativeOrderValue and estPrice's c.credit fallback. Until this lands, §3.3 is open on the highest-stakes screen in the product.\n3. Remove the §3.5 silent defaults (POSTURE, postureName, the 45-day horizon fallback, HOME_SCOUT INCOME, scout 'month'/'Neutral'). Do this before the WorkspaceContext work in task #34 so that work does not have to preserve them.\n4. Route the remaining printed derivations to receipts: riskPct/share-of-book, the payoff hover P/L and \"If price holds\", the scenario pinProb and fallback price, the expected-move percentages.\n5. Verify-then-delete the dead engine remnants: the four finishCand callers, priceSpark, importedPosition/IMPORTS and the whole import overlay, and the unreachable decide.kind 'roll'/'close' branches. Add one DOM assertion that `window.finishCand === undefined` and that no `.plspark` node exists, so these cannot come back.

## canonical package-price receipt (program.md §7.2, §3.3)

### [P0] No canonical package-price receipt object exists anywhere in the backend. Of the 14 §7.2 field names, only 4 appear at all, and never together on one object: `optionNetPremiumCents` (Candidate only), `executableNetCents`/`executability` (preview analytics + OrderSummary), `valuationBasis` (OrderSummary). `stockCashFlowCents`, `grossPackageNetCents`, `afterFeeNetCents`, `restingLimitNetCents` do not exist in src/main/java at all (0 hits each).

**Evidence** — GAP MATRIX (surface x §7.2 field; name in repo, or '—' if absent).

A) CANDIDATE — src/main/java/io/liftandshift/strikebench/recommend/Candidate.java:17-49, wire projection src/main/java/io/liftandshift/strikebench/plan/PlanStrategyService.java:525-555:
quantity=`qty` (:532) | optionNetPremiumCents=`optionNetPremiumCents` (:533) | stockCashFlowCents=— | grossPackageNetCents=`entryNetPremiumCents` (:533, misnamed; INCLUDES stock leg per Candidate.java:24) | openingFeesCents=— (computed at RecommendationEngine.java:813 then discarded) | afterFeeNetCents=— | executableNetCents=— | restingLimitNetCents=— | valuationBasis=— | executability=— | source=— at package level (per-leg only: `quoteSource` PlanStrategyService.java:626) | freshness=`freshness` (:534) | observedAt=— at package level (per-leg `quoteAsOfEpochMs` :625) | fingerprint=—

B) PREVIEW (/api/trades/preview) — src/main/java/io/liftandshift/strikebench/paper/TradePreview.java:7-32 + analytics built at TradeService.java:2660-2676, 2595-2620:
quantity=— (echoes request only) | optionNetPremiumCents=— | stockCashFlowCents=— | grossPackageNetCents=`entryNetPremiumCents` (TradePreview.java:11) | openingFeesCents=`feesOpenCents` (:12) | afterFeeNetCents=— | executableNetCents=`analytics.executionQuality.executableNetCents` (TradeService.java:2662, 2617) | restingLimitNetCents=`analytics.executionQuality.limitNetCents` (:2612) | valuationBasis=— | executability=`analytics.executionQuality.executability` (:2615) | source=`evidence.source` (DataEvidence.java:9) | freshness=`freshness` (TradePreview.java:25) | observedAt=`analytics.sourceAsOfEpochMs` (TradeService.java:2701) | fingerprint=—

C) ORDER / REVIEW (/api/plans/{id}/decision/preview) — ApiResponses.OrderSummary, src/main/java/io/liftandshift/strikebench/api/ApiResponses.java:346-350, built at PlanDecisionController.java:271-300:
quantity=`qty` (:346) | optionNetPremiumCents=— | stockCashFlowCents=— | grossPackageNetCents=`proposedNetCents` (:346 — actually preview.entryNetPremiumCents, PlanDecisionController.java:297; the name lies) | openingFeesCents=— (`feesOverrideCents` is an override, default 0, :299) | afterFeeNetCents=— | executableNetCents=`executableNetCents` (:349) | restingLimitNetCents=`orderInstruction.limitNetCents` (OrderInstruction.java:13) | valuationBasis=`valuationBasis` (:350) | executability=`executability` (:348) | source/freshness/observedAt=— on OrderSummary (only on the sibling `preview`) | fingerprint=—

D) PLACED ORDER (/api/plans/{id}/decision/trade -> trade + decision) — TradeView src/main/java/io/liftandshift/strikebench/api/TradeView.java:11-45; decision node PlanDecisionService.java:96-114:
quantity=`qty` | optionNetPremiumCents=— | stockCashFlowCents=— | grossPackageNetCents=`entryNetPremiumCents` (TradeView.java:21) | openingFeesCents=`feesOpenCents` (:27) | afterFeeNetCents=— | executableNetCents=— | restingLimitNetCents=`proposedNetCents` (:39, legacy alias) | valuationBasis=— | executability=— (persisted as plan_decision_metric `orderExecutability` at PlanDecisionService.java:270 but NOT projected by the latest() SELECT, :85-95) | source=`dataSource` (:35) | freshness=`dataAge` (:34) | observedAt=`quoteAsOf` (PlanDecisionService.java:103) | fingerprint=—

E) HELD-POSITION CLOSE — two competing objects. (E1) TradeService.MarkView, TradeService.java:160-162: closeCostCents (signed, executable side, EXCLUDES closing fees — computed TradeService.java:1770 + 1809), ts, freshness. No quantity/fees/basis/executability/fingerprint. (E2) PositionLifecycleReceipt.CloseQuote, src/main/java/io/liftandshift/strikebench/position/PositionLifecycleReceipt.java:104-133: executable, signedMidCloseCashCents, signedExecutableCloseCashCents, signedOptionExecutableCloseCashCents, closingFeesCents, signedNetCloseCashCents, priceAuthority, basis, unavailableReason. Plus PositionLifecycleReceipt.History:44-58 (signedOpeningCashCents / signedOptionOpeningCashCents / openingFeesCents / grossOpeningCreditCents / netOpeningCreditCents) and Evidence:255-262 (observedAt, marketSnapshotFingerprint, modelFingerprint, policyFingerprint). This is the ONLY place in the repo where the §7.2 shape substantially exists — under entirely different names, and shared with nothing else.

Surfaces where NOTHING carries the field: stockCashFlowCents (0/5), grossPackageNetCents by that name (0/5), afterFeeNetCents (0/5), fingerprint (0/5), package-level valuationBasis (1/5 — order only).

**Fix** — Introduce one `PackagePriceReceipt` record with the 14 §7.2 names and have RecommendationEngine, TradeService.analyze/preview, PlanDecisionController.orderSummary, TradeView, and PositionLifecycleReceipt.CloseQuote all project it. The math already exists: Candidate.optionNetPremiumCents (option-only), entryNet − optionNet (stock cash flow), Fees.openingCents (RecommendationEngine.java:813) for openingFees, and CloseQuote's reconciliation invariant (PositionLifecycleReceipt.java:127) is the model for afterFeeNet.

### [P0] index.html chooses the ORDER PRICE among three competing sources and, on the LIMIT path, posts the browser-chosen number to the backend as `limitNetCents` — the browser originates a price that gets committed.

**Evidence** — Chain 1 (3 deep) — src/main/resources/public/index.html:4343-4348:
```
function estPrice(c){
  if(decide.order.price!=null)return decide.order.price;      // (1) local UI state
  var order=decide.orderPreview&&decide.orderPreview.order;
  var valued=authoritativeOrderValue(order);                  // (2) backend receipt
  return valued==null?c.credit:valued;                        // (3) SCAN-TIME candidate net
}
```
`c.credit` is candidate.entryNetPremiumCents (js/desk-backend.js:1424, 1464) — mid-basis, stock-inclusive, frozen at scan time.
Commit path — index.html:4358-4366: `currentOrderRequest()` calls `estPrice(activeCand())` (:4361) and rounds it into `request.limitNetCents` (:4363). That request is sent to /decision/preview (queueOrderPreview :4380) and to /decision/trade (`commitOrder(commitRequest,...)` :4744).
Trigger — index.html:4801: `decide.order.price = decide.order.type==='limit' ? estPrice(activeCand()) : null` fires the moment the user toggles the Limit segment, before any limit preview exists.

**Fix** — Delete the `c.credit` arm of estPrice. When no backend valuation exists, return null, disable the Limit segment, and show `Execution valuation unavailable`. currentOrderRequest already returns null on a null price (:4362) — the only change needed is removing the third fallback.

### [P0] When no price at all is available, the limit stepper anchors at $0.00 and steps ±$0.25, handing the user a fabricated $0.25 signed package limit that is then committable.

**Evidence** — src/main/resources/public/index.html:4570: `else if(kind==='price'&&decide.order.type==='limit'){ var base=estPrice(activeCand()); if(base==null||!Number.isFinite(Number(base)))base=0; decide.order.price=Math.round((Number(base)+d*.25)*100)/100; }` — the `base=0` substitution turns 'unavailable' into a real number that flows into currentOrderRequest (:4361-4363) and the commit (:4744).

**Fix** — If `estPrice` returns null, return from decStep without setting a price and leave the stepper disabled; never substitute 0.

### [P0] The position surface prints TWO different, contradictory close prices for the same package, side by side, with no explanation of the difference (fees + sign convention).

**Evidence** — index.html:610 — `Close cost` = `authMoney(authDollarsFromCents(cur.closeCostCents))`, i.e. TradeService.MarkView.closeCostCents: SIGNED (BUY legs +1 / SELL legs −1, TradeService.java:3455-3457), executable side, EXCLUDING closing fees (TradeService.java:1770, 1809; the fee omission is documented at :1810-1811).
index.html:548 — `... + ' executable close'` = `r.closeCost` from authLifecycleRead, index.html:410: `closeCost: authDollarsFromCents(close.signedNetCloseCashCents==null?null:Math.max(0,-Number(close.signedNetCloseCashCents)))` — sign-FLIPPED, clamped at 0, and INCLUDING closing fees (PositionLifecycleReceipt.java:127 asserts signedNetCloseCashCents = signedExecutableCloseCashCents − closingFeesCents).
Both strings are emitted into the same `<aside class="authposside">` block (index.html:609-610 renders authLifecycleReceiptHTML immediately above the metric grid). For a short put the user sees e.g. `Close cost −$1,200` and `$1,207 executable close` simultaneously.
The browser also performs both sign transforms itself (index.html:410 `Math.max(0,-x)`; index.html:3879 `money(-Math.abs(closeCost))`) — §3.1 as well as §3.3.

**Fix** — Render one close price from CloseQuote only, and print its components: signedExecutableCloseCashCents, closingFeesCents, signedNetCloseCashCents, priceAuthority, basis. Drop the MarkView closeCostCents metric or relabel it explicitly as 'executable close, before fees' with the fee delta shown.

### [P0] The candidate rail / payoff hero and the order dock / review screen display different package nets for the same package, on different bases (option-only vs stock-inclusive, mid vs executable, scan-time vs live), with no quantity or basis shown to reconcile them — the exact scenario §3.3 names as untrustworthy.

**Evidence** — Rail row — index.html:3997 `signed(candCollect(c))`; hero — index.html:3199 `var credit=(candCollect(c)!=null?candCollect(c):c.net)`; map card — index.html:4553. `candCollect` (index.html:3978): `return c&&c.optionNet!=null?c.optionNet:(c?c.credit:null);` — OPTION-ONLY net when present, otherwise the stock-inclusive package net.
Dock/review — index.html:4409 `eNet = ... authoritativeOrderValue(order) ...` (executable book value, includes stock cash flow), rendered at :4425 (`collect/pay`), :4427 (`natural/limit`), :4433/:4443, and again inside reviewBlock at :4485.
The candidate's own price fields are NEVER refreshed from the preview: applyAuthoritativePreview (index.html:2597-2625) sets `c.executionPreview`, `c.order`, `c.greeks`, but not `c.credit`/`c.optionNet`.
No surface prints quantity beside a price except the dock/review (`qf+'× '`, :4433/:4483); the rail, hero and map card show a qty-scaled net (RecommendationEngine.java:811 `PayoffCurve.of(optionLegs, qty)`) with no qty label.
No surface anywhere prints opening fees: `grep -n 'feesOpen' src/main/resources/public/index.html` returns 0 hits.

**Fix** — Bind every price cell to the same PackagePriceReceipt and render its parts explicitly (quantity, option net, stock cash flow, gross package net, opening fees, after-fee net, executable vs resting). Delete candCollect and the four call sites' independent basis choice.

### [P1] `authoritativeOrderValue` is itself a 3-deep fallback chain that silently re-derives the valuation the backend already declared, including a self-described 'migration fallback'.

**Evidence** — src/main/resources/public/index.html:4349-4357:
```
function authoritativeOrderValue(order){
  if(!order)return null;
  var state=String(order.executability||'UNAVAILABLE').toUpperCase();
  if(order.valuedNetCents!=null&&String(order.valuationBasis||'').toUpperCase()!=='UNAVAILABLE')return Number(order.valuedNetCents)/100;
  /* Migration fallback for servers predating the explicit valuation receipt. */
  if(state==='IMMEDIATE'&&order.executableNetCents!=null)return Number(order.executableNetCents)/100;
  if(state==='RESTING'&&order.orderInstruction&&order.orderInstruction.limitNetCents!=null)return Number(order.orderInstruction.limitNetCents)/100;
  return null;
}
```
Arms 2 and 3 reimplement PlanDecisionController.orderSummary (src/main/java/io/liftandshift/strikebench/api/PlanDecisionController.java:288-295) in the browser. There is no pre-receipt server in this repo — OrderSummary has always carried valuedNetCents/valuationBasis (ApiResponses.java:346-350).

**Fix** — Keep only the `valuedNetCents` + `valuationBasis` arm; return null otherwise. Per the no-backcompat-pre-release standing rule, delete the migration arms outright.

### [P1] Two more competing-price choices in the same order dock: the 'Natural executable package' readout and the buying-power reserve each fall back from a backend field to a scan-time candidate field.

**Evidence** — src/main/resources/public/index.html:4406: `mid = usablePreview&&order&&order.executableNetCents!=null ? Number(order.executableNetCents)/100 : (c.authoritative?null:c.credit)` — rendered as the 'Natural executable package' value at :4417 and as `· natural ...` at :4427.
index.html:4409: `eNet = c.authoritative?(usablePreview?authoritativeOrderValue(order):null):c.credit*qf` — the non-authoritative arm multiplies the candidate net by qty in the browser.
index.html:4413: `eBP = hasExecutionValuation&&preview&&preview.reserveCents!=null ? Number(preview.reserveCents)/100 : (c.authoritative?c.cap:c.cap*qf)` — falls back from the backend reserve to the candidate's capital, rendered as `reserves $X` at :4425.

**Fix** — Drop the non-authoritative arms entirely (the display-only desk has no non-authoritative candidates — see the comment at index.html:3191) and render 'unavailable' when the preview field is null.

### [P1] The leg workbench silently substitutes the ambient option chain for a leg's own captured book, and then prints bid/ask with NO source, freshness, or timestamp because the provenance line is gated on the captured flag.

**Evidence** — src/main/resources/public/index.html:3758-3763:
```
if(capturedBid!=null||capturedAsk!=null)return {
  bid:capturedBid,ask:capturedAsk,asOfEpochMs:l.quoteAsOfEpochMs,
  source:l.quoteSource,freshness:l.quoteFreshness,captured:true,
  iv:matching&&matching.iv,delta:matching&&matching.delta };
return matching||{};        // <- ambient chain row, no provenance at all
```
index.html:3777 `if(quote.captured){ ... }` — the source/freshness receipt is printed ONLY on the captured branch, so the ambient-chain branch renders `bid / ask X / Y` (:3772) with no receipt.
Two surfaces always hit the ambient branch:
(a) HELD positions — TradeView legs are built with `LegView::of(leg)` (src/main/java/io/liftandshift/strikebench/api/TradeView.java:70), and that overload passes null for quoteBid/quoteAsk/quoteAsOfEpochMs/quoteSource/quoteFreshness (src/main/java/io/liftandshift/strikebench/recommend/LegView.java:28-31, 71). authLegs (index.html:421-424) therefore never sets quoteBid/quoteAsk, and the held leg workbench (index.html:520) shows an unattributed book.
(b) DRAFT legs — previewLegs (src/main/resources/public/js/desk-backend.js:978-986) copies the REQUEST legs and only overrides `entryPrice`; it never maps the preview's per-leg `bid`/`ask`/`source`/`freshness`/`asOfEpochMs` (produced at src/main/java/io/liftandshift/strikebench/paper/TradeService.java:2260-2272) into quoteBid/quoteAsk/quoteSource/quoteFreshness.
Also note :3761 — even on the captured branch, iv and delta are taken from the ambient chain row, mixing two observations in one row.

**Fix** — Delete the `return matching||{}` fallback: a leg with no captured book shows 'book unavailable'. Map the preview leg receipt in previewLegs, and populate LegView quote fields for held positions from the CloseQuote's per-leg marks.

### [P1] The freshness label above the position's price metrics is a 4-deep fallback chain that ends in the hardcoded string 'current' — absent evidence is rendered as fresh evidence (§3.2 as well as §3.3).

**Evidence** — src/main/resources/public/index.html:606: `quoteStatus=authExceptionStatus(cur.freshness||p.provenance.freshness||ev.quote&&ev.quote.age)||'current'`. Sources raced: MarkView.freshness (TradeService.java:1850), TradeView.dataAge (TradeView.java:34), research evidence inputs.quote.age. Rendered at index.html:609 (`Position now · <quoteStatus>`) directly above the Close cost / POP now metrics (:610-611) and again at :617 (`Market data`).

**Fix** — Read freshness from the one close-price receipt. If it is null, render 'unavailable' — never 'current'.

### [P1] The leg-workbench DRAFT and the APPLIED custom package show different package nets for identical legs, because /api/trades/preview omits optionNetPremiumCents while /strategy/custom supplies it.

**Evidence** — Draft: src/main/resources/public/js/desk-backend.js:988-1013 builds a synthetic candidate from the preview and sets `entryNetPremiumCents: preview.entryNetPremiumCents` (:999) but NO `optionNetPremiumCents`. candidateToDesk (js/desk-backend.js:1427) therefore yields `optionNet: null`, so candCollect (index.html:3978) falls through to `c.credit` = the stock-INCLUSIVE package net.
Applied: POST /api/plans/{id}/strategy/custom (js/desk-backend.js:1151-1153) -> src/main/java/io/liftandshift/strikebench/api/PlanStrategyController.java:202 -> TradeController.exactPreviewCandidate, which DOES compute the option-only net (src/main/java/io/liftandshift/strikebench/api/TradeController.java:769-773).
For any covered/buy-write structure the hero number therefore flips from a debit (draft) to a credit (applied) on Apply, with no explanation. TradePreview.java:7-32 confirms the field is simply absent from the preview contract.

**Fix** — Add optionNetPremiumCents (and stockCashFlowCents) to TradePreview and populate it in TradeService.analyze using the same PayoffCurve-over-option-legs computation already at TradeController.java:770-772.

### [P2] The review screen's capital/collateral figure is a 3-deep fallback chain whose last arm double-scales an already-quantity-scaled value.

**Evidence** — src/main/resources/public/index.html:4448: `used = c.authoritative&&decide.orderPreview ? Number((decide.orderPreview.preview.reserveCents!=null?decide.orderPreview.preview.reserveCents:decide.orderPreview.preview.maxLossCents)||0)/100 : (c.cap||c.maxLoss)*o.qty`.
`c.cap` is already quantity-scaled: js/desk-backend.js:1435 `displayCapital = incremental ?? economic ?? maxLossCents`, incremental = CapitalProfile.incrementalCents = candidate.maxLossCents (src/main/java/io/liftandshift/strikebench/eval/CapitalProfiler.java:9), which the engine computes as PayoffCurve.of(legs, qty) (RecommendationEngine.java:811 pattern). Multiplying by o.qty again over-reports collateral by a factor of qty. Reachable whenever `decide.orderPreview` is nulled (invalidateDecisionPreview) while `o.review` is true — index.html:4431 returns reviewBlock before any usablePreview guard.
`||` also treats a legitimate 0 cap as absent. Same `c.cap||c.maxLoss` pattern at index.html:4124 (fitBudgetPanel, rendered as `money(used) / money(cap)` at :4125).

**Fix** — Use preview.reserveCents only; render 'unavailable' when it is null and disable Confirm. Remove the *o.qty re-scale and replace `||` with `!=null` checks.

### [P2] Book single-position summary hard-labels the package net 'Entry credit' even when the value is negative (a debit), and three surfaces use three different labels for the same TradeView.entryNetPremiumCents field.

**Evidence** — index.html:1999 `authMetric('Entry credit',authMoney(p.net))` — authMoney/money (index.html:394) renders `−$1,200` under the label 'Entry credit'.
index.html:534-535 labels the same field 'Entry net' with 'collected '/'paid ' prose.
index.html:3199/3978 labels it 'Net credit'/'Net debit' via candCollectLabel (:3979) — and may be showing the option-only net instead.
Source field for all three: `net: authDollarsFromCents(trade.entryNetPremiumCents)` (index.html:433) / TradeView.java:21.

**Fix** — One label helper driven by the receipt's sign, used by all three sites.

### [P2] renderClose() — the only screen that would present a close price receipt — is unreachable dead code, and its confirm path would fake a close without any backend call.

**Evidence** — index.html:3870 `function renderClose()` is reached only from index.html:3517 `if(decide.kind==='close'){ renderClose(); ... }`; `decide.kind` is set at index.html:3363 inside `enterDecide` (index.html:3354), and all five call sites pass 'idea' (index.html:1793, 1796, 1813, 2473, 3016). `grep -n "openDecide("` returns 0 hits. `closePosition` (index.html:829) likewise has no caller.
If it were reachable: the confirm handler (index.html:4732-4750) only calls `DeskBackend.commitOrder` when `activeCand()` is authoritative (:4734); a close has no candidate, so control falls to index.html:4747-4748 `decide.order.placed=true; toast('Position closed — '+decide.sym)` with no request. index.html:3884 also labels `p.pnl` (TradeView.unrealizedPnlCents, index.html:426) as 'Realized P/L', and index.html:3879 applies a third browser sign transform `money(-Math.abs(closeCost))`.

**Fix** — Delete renderClose/closePosition and the kind==='close' branch, or wire it to a real close endpoint driven by CloseQuote. Do not leave a screen that prints a close price and then fabricates the close.

### [P2] Three price-bearing functions call `finishCand`, which is not defined anywhere in the served frontend — they throw ReferenceError if reached.

**Evidence** — Call sites: index.html:839 (adjustCand), :843 (baseCand), :3055 (importedPosition), :3107. `grep -rn 'finishCand' src/main/resources/public/{index.html,strategies.js,learn-content.js,js/*.js}` finds only these call sites and no definition. importedPosition additionally fabricates prices from the candidate net — index.html:3059 `var pnl=trade.pnl!=null?trade.pnl:-Math.round(Math.abs(c.credit)*0.12);` and :3071 `day:-Math.round(Math.abs(c.credit)*0.02)`. Entry points selectImport/setImpReal (index.html:4934-4935).

**Fix** — Delete importedPosition/adjustCand/baseCand and their entry points, or route them through the backend preview.

**Lane order** — Order of work for this lane:

1. (P0, backend, unblocks everything) Add `PackagePriceReceipt` with the 14 §7.2 names as a shared record and project it from all five producers. Cheapest starting point: TradePreview (src/main/java/io/liftandshift/strikebench/paper/TradePreview.java) already owns entryNetPremiumCents + feesOpenCents + executionQuality + sourceAsOfEpochMs; add optionNetPremiumCents/stockCashFlowCents using the computation that already exists at src/main/java/io/liftandshift/strikebench/api/TradeController.java:769-773, derive afterFeeNetCents, and add a package fingerprint. Then make PlanDecisionController.orderSummary (:271-300) return the receipt instead of the ad-hoc OrderSummary, have Candidate carry it, and have PositionLifecycleReceipt.CloseQuote/History adopt the same names (that domain already has the correct shape and the reconciliation invariant at PositionLifecycleReceipt.java:127 — copy it as the receipt's own invariant).

2. (P0, frontend, do NOT wait for step 1 to finish) Cut the two commit-path fabrications first, since they are self-contained: index.html:4347 (delete the `c.credit` arm of estPrice) and index.html:4570 (delete `base=0`). With those gone, an unavailable valuation disables Limit instead of inventing $0.25.

3. (P0, frontend) Collapse the close price to one source: delete the MarkView `closeCostCents` metric at index.html:610 and the browser sign transforms at index.html:410 and :3879; render CloseQuote's components directly.

4. (P0/P1, frontend) Replace candCollect (index.html:3978) and every price cell — rail :3997, hero :3199, map card :4553, dock :4406/:4409/:4413/:4425/:4427/:4433, review :4485 — with one receipt-bound price component that always shows quantity, gross package net, opening fees, after-fee net, and executable-vs-resting basis. This is the §3.3 acceptance test: the four screens must print the same numbers or name the difference.

5. (P1) Delete the fallback arms in authoritativeOrderValue (index.html:4353-4355), the ambient-chain leg fallback (index.html:3763), and the 'current' freshness default (index.html:606); fix previewLegs (js/desk-backend.js:978-986) and TradeView leg quotes (TradeView.java:70) so leg receipts survive.

6. (P2) Fix the capital chains (index.html:4448, :4124), the 'Entry credit' mislabel (:1999), and delete the dead close/import code (renderClose :3870, closePosition :829, importedPosition :3052, adjustCand :836, baseCand :841) rather than repairing their undefined `finishCand` dependency.

## Symbol and Horizon value types (program.md §7.6)

### [P0] Unit mismatch on a persisted decision receipt: plan_decision.review_horizon_days is WRITTEN in trading sessions and READ as calendar days, mis-dating the cash-decision review gate and corrupting the plan_review benchmark record.

**Evidence** — Write: src/main/java/io/liftandshift/strikebench/plan/PlanDecisionService.java:227 (column list) and :238-240 `input.plan().context().horizonDays() == null ? Horizon.MONTH.tradingSessions() : input.plan().context().horizonDays()` — plan context horizonDays is trading sessions (PlanOutcomeController.java:757-759 feeds it straight into ScenarioSpec, whose horizonDays is documented "trading days" at sim/ScenarioSpec.java:23). Read: src/main/java/io/liftandshift/strikebench/api/PlanDecisionController.java:391-393 `int horizon = decision.path("reviewHorizonDays").asInt(30); ... dueAt = decidedAt.plus(java.time.Duration.ofDays(horizon))`. The same value is copied verbatim into plan_review.horizon_days at plan/PlanManagementService.java:342-352, :382-392, :484-494. Schema: src/main/resources/db/migrations/V1__baseline.sql:979 and :1559.

**Fix** — Pick one authority and name it. Smallest correct fix: at PlanDecisionService.java:238-240 store `Horizon.of(sessions).expiryCalendarDays()` and rename the column/wire field to review_horizon_calendar_days / reviewHorizonCalendarDays; leave PlanDecisionController:391-393 unchanged. Long-term this is the H1 step of the Horizon migration — the value type must make `Duration.ofDays(sessions)` unexpressible.

### [P1] The same user horizon declaration takes two different values depending on which button is pressed: Scout collapses "45 days" to the MONTH bucket (21 sessions / 35-calendar-day expiry anchor) while Open-idea sends 45 sessions.

**Evidence** — src/main/resources/public/index.html:1650 `ideaButtons('Horizon','horizon',['7 days','30 days','45 days'],homeIdea.horizon)`; index.html:1763 `horizon=/^7\b/.test(homeIdea.horizon)?'week':'month'` then index.html:1765 `scoutOpportunities({... horizons:[horizon] ...})`. The other path: index.html:1814 `horizon:homeIdea.horizon` → src/main/resources/public/js/desk-backend.js:163-172 `horizonDays(context)` scrapes the first integer out of the label → 45. Backend consumes them differently: model/Horizon.java:35-38 maps "month"→MONTH(21 sessions, 35 cal days) vs Horizon.java:55-61 which reads "45d" as 45 trading sessions.

**Fix** — Emit the canonical wire value from the buttons (`7d`/`30d`/`45d`) at index.html:1650, delete the `/^7\b/` collapse at index.html:1763, and delete the label-scraping `horizonDays()` at desk-backend.js:163-172. One declaration, one wire token.

### [P1] Silent product defaults in the Scout adapter (horizon '45d', risk 'balanced', intent 'INCOME') — directly contradicts §3.5 and the file's own no-defaults contract comment.

**Evidence** — src/main/resources/public/js/desk-backend.js:3892-3893 `horizons: Array.isArray(options.horizons) && options.horizons.length ? options.horizons : ['45d']`, :3896 `riskMode: String(options.riskMode || 'balanced').toLowerCase()`, :3898-3899 `intents: ... : ['INCOME']`. The contract these violate is stated at src/main/resources/public/index.html:1238-1241 ("NO SILENT PRODUCT DEFAULTS (program §3.5). The desk used to ship goal=INCOME, view=Neutral, horizon=45 days and risk=Balanced pre-selected"). Adjacent: index.html:1765 also defaults `thesisOverride:String(homeIdea.view||'Neutral')`.

**Fix** — Replace all four fallbacks with a thrown error / no-call: if `options.horizons`, `options.riskMode`, `options.intents` or the view are absent, the adapter must refuse the request the way desk-backend.js:2155 already refuses (`if (!(Number(planContext.horizonDays) > 0)) missingDeclarations.push('horizon')`).

### [P1] Seven mutually incompatible symbol-validation grammars, and the market hot path validates nothing at all — the same ticker is accepted on one route and rejected on another.

**Evidence** — `[A-Z0-9.^_-]{1,20}` api/DataController.java:398, db/UnderlyingCsvIngest.java:199, market/EventService.java:468 · `[A-Z0-9.\-]{1,10}` market/UniverseService.java:94 · `[A-Z0-9^=._\-]{1,20}` market/providers/YahooFinanceProvider.java:193 · `[A-Z0-9][A-Z0-9.\-]{0,19}` paper/AccountObjectiveService.java:36 · `[A-Z0-9._/-]{1,24}` paper/BrokerStatementParser.java:641 · `[A-Z][A-Z0-9._-]{0,19}` paper/PortfolioAccountingService.java:2442 · `[A-Z0-9._-]{1,20}` plan/PlanService.java:579. Divergence proof: `^VIX` passes DataController/Yahoo, fails AccountObjective/BrokerStatement/PortfolioAccounting/PlanService; `BRK/B` passes only BrokerStatementParser; `1SP` passes five, fails PortfolioAccountingService (must start [A-Z]); anything >10 chars fails UniverseService only. Meanwhile market/MarketDataService.java:998-1000 `norm()` and market/MarketDataEngine.java:524 `norm()` — the functions that build every provider request and cache key — only trim+upper, with zero validation.

**Fix** — One `io.liftandshift.strikebench.model.Symbol` record with a single grammar chosen as the union actually needed (accepts `^VIX`, `BRK.B`, `SPXW`, `BRK/B`, ≤24 chars), validated in the compact constructor. Delete all seven regexes.

### [P1] EvaluationService caches and SQL-queries IV history on the raw caller-supplied symbol with no normalization; an unnormalized symbol silently returns an empty history, so IV rank/percentile go null with no receipt (missing evidence becomes a quiet degradation, §3.2).

**Evidence** — src/main/java/io/liftandshift/strikebench/eval/EvaluationService.java:48 `Cache<String, List<Double>> ivHistoryCache`, :386-387 `return ivHistoryCache.get(symbol, this::queryIvHistory)`, :389-397 `... WHERE symbol = ? AND dataset_id = 'observed' ...` with the same raw string. Nothing in EvaluationService normalizes: the public entry points (:113-118, :123-128, :143-150, :152-157, :171-176) pass `symbol` straight through to `buildContext` and on to `market.quote(symbol, worldId)` / `ivHistory(symbol)`. Same pattern in recommend/CompensationView.java:47-48 (`gapCache.computeIfAbsent(evaluation.spec().symbol(), ...)`) and :67-68 (`String eventKey = symbol + "|" + packageEnd + "|" + worldId`).

**Fix** — Change `EvaluationService.evaluate/evaluateBestPerFamily/assessExact` and `CompensationView` to take `Symbol` instead of `String`, so `ivHistoryCache` and the `WHERE symbol = ?` bind are keyed on the canonical value by construction.

### [P1] Locale-sensitive `toUpperCase()` (no Locale.ROOT) on a symbol inside a recorded position leg — on a Turkish-locale JVM "i" becomes "İ" and the leg no longer matches any other record of the same symbol.

**Evidence** — src/main/java/io/liftandshift/strikebench/position/RecordingPolicy.java:89-92 `instrumentType = ... .trim().toUpperCase(); positionEffect = ... .trim().toUpperCase(); symbol = symbol == null ? null : symbol.trim().toUpperCase();` — the only symbol normalizer in the backend missing `Locale.ROOT`. Every other site passes it (e.g. market/MarketDataService.java:999, paper/PositionsService.java:385).

**Fix** — `Symbol.of(symbol)` (which pins Locale.ROOT); for the two enum-ish strings add `Locale.ROOT` explicitly.

### [P1] The JS reimplements the sessions→named-bucket mapping with different thresholds and a silent `|| 30` default, so 8-10 declared sessions produce a different bucket in the browser than in Java.

**Evidence** — src/main/resources/public/js/desk-backend.js:964-966 `Number(state.plan.context && state.plan.context.horizonDays || 30) <= 1 ? '0dte' : ... <= 7 ? 'week' : ... <= 45 ? 'month' : 'quarter'` versus src/main/java/io/liftandshift/strikebench/model/Horizon.java:64-70 `if (sessions <= 1) ZERO_DTE; if (sessions <= 10) WEEK; if (sessions <= 45) MONTH; QUARTER`. 8 declared sessions → JS 'month' (21 sessions / 35 calendar-day anchor), Java WEEK (5 / 7). `Horizon.fromTradingSessions` has zero production call sites (only src/test/java/io/liftandshift/strikebench/model/HorizonTest.java:29-32), so the JS copy is the de-facto owner.

**Fix** — Delete desk-backend.js:964-966 and send the exact declared sessions (`String(horizonDays)+'d'`) — the backend already accepts that grammar (Horizon.java:55-61, DecisionDeclarationPolicy.java:103-106).

### [P1] `Symbols`, the one canonical normalizer, is used at exactly two call sites; 106 hand-rolled copies live across 54 files, including 28 private `norm`/`normalize`/`normalizeSymbol`/`symbol`/`upper` helpers.

**Evidence** — Canonical: src/main/java/io/liftandshift/strikebench/util/Symbols.java:12-19, called only from recommend/OpportunityScanner.java:77 and recommend/AutoRecommender.java:225. Count: `grep -rn toUpperCase --include=*.java` filtered to symbol-bearing lines = 106 hits / 54 files. Private helpers: MarketDataService.java:998, MarketDataEngine.java:524, StoredHistoricalOptionsProvider.java:68, StoredOptionChainStore.java:46, EtfLookThroughService.java:297, FixtureProvider.java:305, PositionsService.java:383, Universes.java:68, CboeProvider.java:285, PolygonProvider.java:187, MissingRangePlanner.java:110, ObservedCandleWriter.java:100, UnderlyingCsvIngest.java:197, EventService.java:466, CampaignService.java:1675, PlanService.java:577, PortfolioAccountingService.java:2440, BrokerStatementParser.java:639, PositionArtifactStore.java:250, ResearchController.java:513, BroadBasedIndexOptions.java:50, AccountObjectiveService.java:36+52, YahooFinanceProvider.java:190-196, StrategyCatalog.java:286, PositionPackageFingerprint.java:103, PositionTransformation.java:369, DataJobService.java:463+465. Frontend adds 108 `toUpperCase()` in index.html and 102 in js/desk-backend.js.

**Fix** — `model/Symbol` replaces `util/Symbols` (keep the list form as `Symbol.list(List<String>)`), then delete the 28 helpers as each layer converts. Add a build-time grep/ArchUnit guard forbidding `toUpperCase` on any identifier containing "symbol" outside `model/Symbol.java`.

### [P2] Every market cache key is a raw String or a `"|"`-concatenated composite of raw Strings — the exact pattern §7.8 says produced the NUL-separator bug; the one already-typed key still holds a raw String symbol.

**Evidence** — src/main/java/io/liftandshift/strikebench/market/MarketDataService.java:64 quoteCache / :67 chainCache / :70 expirationsCache / :76 candlesCache / :90 newsCache are all `Cache<String, …>`; keys built at :457-459 (`norm(symbol)`), :474-475, :489 (`norm(symbol) + "|" + expiration`), :528-531 (`dataset + "|" + norm(symbol) + "|" + from + "|" + to`), :691; the singleflight key at :207 re-concatenates (`domain + "|" + generation + "|" + key`). Typed but still stringly: :116 and :948-953 `record HistoricalAbsenceKey(String provider, String symbol)`. MarketDataEngine.java:77-79 (`snapshots`, `lastAccess`, `inFlight`) and :102 (`queuedTask`) are all `Map<String, …>`. EvaluationService.java:48/387; EventService.java:179/213/290/304; CboeProvider.java:235-236/243; CompensationView.java:48/67-68.

**Fix** — `Symbol` plus three typed key records — `QuoteKey(Symbol)`, `ChainKey(Symbol, LocalDate)`, `CandleKey(String dataset, Symbol, LocalDate, LocalDate)` — and `HistoricalAbsenceKey(String provider, Symbol symbol)`. That deletes every `"|"` concatenation in the market plane.

### [P2] The Yahoo pre-history boundary is stored twice under two spellings: the provider keys it by its own alias while MarketDataService keys the identical fact by the canonical symbol, so a dotted class ticker (BRK.B / BRK-B) learns the boundary in one map and looks it up in the other.

**Evidence** — Provider-side: src/main/java/io/liftandshift/strikebench/market/providers/YahooFinanceProvider.java:87-88 (`yahooSymbol(requestedSymbol)`), :92 `poisonSymbols.contains(yahooSymbol)`, :95 `earliestAvailable.get(yahooSymbol)`, :124-126 `earliestAvailable.merge(yahooSymbol, …)`, alias rule at :190-196 (`if (normalized.matches("[A-Z]{1,6}\\.[A-Z]")) normalized = normalized.replace('.', '-')`). Service-side: market/MarketDataService.java:625 `recordPreHistory(p.name(), norm(symbol), rue)` and :931 `new HistoricalAbsenceKey(provider, symbol)` with the canonical (dotted) spelling; lookup at :957-960.

**Fix** — Give `Symbol` a boundary-only `providerAlias(String provider)` accessor, and key both `earliestAvailable`/`poisonSymbols` and `preHistoryBoundaries` on the canonical `Symbol` — the alias applies only when building the URL at YahooFinanceProvider.java:98-102.

### [P2] `MarketDataEngine.refreshBlocking` tracks and refreshes under the raw string but reads the result back under the normalized one — an unnormalized caller gets a false "refresh failed" plus an orphan snapshot entry.

**Evidence** — src/main/java/io/liftandshift/strikebench/market/MarketDataEngine.java:366-370 `track(symbol); refreshFuture(symbol, P_JOB).get(...); MarketSnapshot snap = snapshots.get(norm(symbol));` while the write path stores under the raw key (:427-428 `commit`: `if (lastAccess.containsKey(symbol)) snapshots.put(symbol, snap)`). Latent only because the sole caller pre-normalizes: db/DataJobService.java:406 with labels built at :463 and :465. Same asymmetry at :135 (`snapshots.put(s.symbol(), s); track(s.symbol())` from the DB, un-normalized) against db/MarketSnapshotStore.java:34-48, which persists `s.symbol()` verbatim while reading back through `symbol.trim().toUpperCase(...)` at :63.

**Fix** — `Map<Symbol, MarketSnapshot> snapshots` / `Map<Symbol, Long> lastAccess` / `Map<Symbol, CompletableFuture<Void>> inFlight` / `Map<Symbol, PriorityTask> queuedTask` — the asymmetry becomes unexpressible. Normalize on write in MarketSnapshotStore.save too.

### [P2] `DecisionDeclarationPolicy` is a second, independent horizon grammar owner: it re-lists the enum keys, re-spells the `Nd` regex and re-hardcodes the 756 bound, and it diverges from `Horizon.parse`, which additionally accepts "day"/"1d" and silently falls back to MONTH for any unrecognized input.

**Evidence** — src/main/java/io/liftandshift/strikebench/recommend/DecisionDeclarationPolicy.java:19 `HORIZONS = List.of("0dte","week","month","quarter")` and :100-108 `if (normalized.matches("[1-9]\\d{0,2}d")) { int sessions = ...; if (sessions <= 756) return; }` duplicating model/Horizon.java:32-40 (`case "0dte","day","1d" -> ZERO_DTE; ... default -> MONTH`) and :55-61 (same regex, same 756). Concrete divergence: "1d" → 1 calendar day via Horizon.expiryCalendarDays (:49-53, exact branch) but ZERO_DTE / 0 calendar days via Horizon.parse (:35), so RecommendationEngine.pickExpiration:1348-1356 never emits its "0DTE horizon requested but same-day expiration is disabled" note for a "1d" declaration.

**Fix** — Delete DecisionDeclarationPolicy.java:19 and :100-108; call `Horizon.parse(raw)` and let it throw. Make `Horizon.parse` throw on unknown input instead of defaulting to MONTH (Horizon.java:38).

### [P2] The JSON field name `horizonDays` carries calendar days on one receipt and trading sessions on another, and both reach the same browser.

**Evidence** — Calendar days: src/main/java/io/liftandshift/strikebench/api/ApiResponses.java:259 `Integer horizonDays` in `DecisionBaseline`, populated at api/DiscoveryController.java:297-298 (`(int) ChronoUnit.DAYS.between(laneToday, frontExpiration)`) and used as `horizonDays / 365.0` at :307 and :310. Trading sessions: plan/PlanOutcomeService.java:632 `put(n, "horizonDays", r.horizon())` where `r.horizon()` is `ScenarioSpec.horizonDays` ("trading days in the scenario", sim/ScenarioSpec.java:23); api/PlanOutcomeController.java:638 `out.put("horizonDays", authored.spec().horizonDays())`; plan/PlanService.java:159/440/476 for the Plan context. Browser readers treat them interchangeably: index.html:907, :2636, :3311, :3335 and desk-backend.js:538/567/583/2457/3644.

**Fix** — Split the wire name: `horizonSessions` for the declared/simulated horizon (PlanOutcomeService:632, PlanOutcomeController:638, Plan context) and `horizonCalendarDays` for ApiResponses.DecisionBaseline:259; update the index.html / desk-backend.js readers in the same commit.

### [P2] `EvaluationService.assessExact` stamps a CALENDAR days-to-expiry into the `Nd` grammar, which means trading sessions everywhere else; the record's own doc comment still advertises the old named-bucket grammar.

**Evidence** — src/main/java/io/liftandshift/strikebench/eval/EvaluationService.java:178-181 `new StrategySpec(symbol, candidate.strategy(), candidate.intent(), ctx.daysToExpiry() + "d", null, null, "exact-position")`. `EvalContext.daysToExpiry` is calendar-denominated — it is divided by 365.0 at eval/RiskProfiler.java:70/106/138, eval/EconomicAssessment.java:291 and eval/VolatilityProfiler.java:22. The `Nd` grammar means sessions: model/Horizon.java:55-61 and EvaluationService.java:229-232 (`horizonSessions(raw) -> Horizon.tradingSessions(value)`). Doc drift: eval/StrategySpec.java:11 `String horizon,     // 0DTE / week / month / quarter` and recommend/RecommendationEngine.java:85 `String horizon,              // 0DTE | week | month | quarter`, while RecommendationEngine.effectiveHorizon:1193-1196 actually returns `"30d"`.

**Fix** — Carry the exact package lifetime as an `OptionTime.Measure` (market/OptionTime.java:13 already gives sessions + calendarDays + years + basis) instead of re-encoding it as a horizon string; delete the stale comments at StrategySpec.java:11 and RecommendationEngine.java:85.

### [P2] `Horizon` exposes two public entry points with no production callers, so the frontend reimplemented them — dead API on one side, a divergent copy on the other.

**Evidence** — src/main/java/io/liftandshift/strikebench/model/Horizon.java:64-70 `fromTradingSessions` — referenced only by src/test/java/io/liftandshift/strikebench/model/HorizonTest.java:29-32. Horizon.java:28 `key()` — no callers anywhere in src/main. `Horizon.parse` (:32-40) is likewise never called directly; only `tradingSessions(String)` (:43-46) at eval/EvaluationService.java:232, `expiryCalendarDays(String)` (:49-53) at recommend/RecommendationEngine.java:1211 and :1348, `exactTradingSessions` (:77-83) at api/PlanController.java:365 and plan/PlanStrategyService.java:827, and `MONTH.tradingSessions()` at api/PlanDecisionController.java:249, api/PlanOutcomeController.java:758, plan/PlanDecisionService.java:239.

**Fix** — Once the frontend is display-only, have the backend ship the bucket label on the receipt and delete `fromTradingSessions` + `key()` — or promote them to the single owner and route desk-backend.js:964-966 through the receipt.

### [P2] Frontend fabricates a 45-session horizon as a display fallback when no receipt supplies one.

**Evidence** — src/main/resources/public/index.html:907 `function authoritativeHorizon(p){ ... return p&&p.authoritative&&decide&&decide.ensemble?Math.max(1,Number(decide.ensemble.preview.horizonDays||1)):45; }` — the trailing `:45` is a browser-originated number, consumed at index.html:927, :3311 and :3335. desk-backend.js:169-171 explicitly documents the opposite rule ("the adapter never fabricates a 45-session default").

**Fix** — Return null and render "horizon undeclared", matching desk-backend.js:163-172.

### [P2] Symbol-bearing routes and query parameters all take a raw String and each re-normalizes locally, so validation coverage is per-route rather than per-type.

**Evidence** — Path routes: api/ResearchRoutes.java:41-46 (`/api/research/{symbol}` plus /expirations, /chain, /expected-move, /history, /news) normalized at api/ResearchController.java:513-515; api/DiscoveryRoutes.java:20 (`/api/research/{symbol}/intent-ladder`) normalized at api/DiscoveryController.java:410. Query params: `symbols` at api/CoreController.java:162+269, api/MarketStreamController.java:62+66, api/SparklineController.java:58+78; `symbol` at api/TradeController.java:167 → paper/TradeService.java:1994-1999 (`params.add(symbol.trim().toUpperCase(Locale.ROOT))`). Body symbols: api/OutcomeController.java:108/262/856, api/PlanController.java:193, api/ResearchController.java:129, api/TradeController.java:561/564, api/WorldController.java:162/181/209, api/DataController.java:396-400. None of these validate against the same grammar (see the seven-regex finding).

**Fix** — Register a Javalin/Jackson converter that materialises `Symbol` from a path param, query param or body field, so every route validates identically and the per-controller normalization at those 20 sites is deleted.

**Lane order** — Two classes: `io.liftandshift.strikebench.model.Symbol` (record; canonical trim + Locale.ROOT upper + ONE grammar; `Symbol.of`, `Symbol.optional`, `Symbol.list` replacing util/Symbols; boundary-only `providerAlias(String provider)` so YahooFinanceProvider.java:190-196 keeps its dot→dash rule without forking identity) and `io.liftandshift.strikebench.model.Horizon` (promote the enum at model/Horizon.java:12 to a value record carrying `int tradingSessions`, with `Horizon.Named` for the four buckets, `ofTradingSessions`/`ofCalendarDays` factories, `tradingSessions()`/`expiryCalendarDays()` accessors, `wire()` = "Nd", and a `parse` that THROWS instead of falling back to MONTH). Do them in this order.

HORIZON FIRST — H1 is a live data-corruption fix and is independent of the type.
H1. Fix plan_decision.review_horizon_days: PlanDecisionService.java:238-240 (write) vs PlanDecisionController.java:391-393 (read) vs PlanManagementService.java:342-352/382-392/484-494 (copy into plan_review). Rename the column to state its unit. Ship alone.
H2. Add model/Horizon value record + HorizonTest; no call-site change. `parse` throws.
H3. Fold DecisionDeclarationPolicy.java:19 + :100-108 into `Horizon.parse` — one grammar, one 756 bound.
H4. Convert the three string-grammar consumers: EvaluationService.java:229-232, RecommendationEngine.java:1210-1211 and :1346-1348. Then the five `MONTH.tradingSessions()` fallbacks (PlanDecisionController:249, PlanOutcomeController:758, PlanDecisionService:239) and the two `exactTradingSessions` producers (PlanController:365, PlanStrategyService:827).
H5. Split the wire name: `horizonSessions` (PlanOutcomeService:632, PlanOutcomeController:638, PlanService:159/440/476) vs `horizonCalendarDays` (ApiResponses.java:259 ← DiscoveryController:297-298/313). Update index.html:907/2636/3311/3335 and desk-backend.js:538/567/583/2457/3644 in the same commit.
H6. Frontend collapse: index.html:1650 emits `7d|30d|45d`; delete index.html:1763 (`/^7\b/`), index.html:2636 and desk-backend.js:583 (`+' days'`), desk-backend.js:163-172 (label scraping), desk-backend.js:964-966 (bucket copy), desk-backend.js:3892-3899 (four silent defaults), index.html:907 (`:45`).
H7. Fix EvaluationService.java:178-181 to carry an `OptionTime.Measure` (market/OptionTime.java:13) instead of `daysToExpiry + "d"`; clear the stale comments at StrategySpec.java:11 and RecommendationEngine.java:85.
H8. Delete Horizon.fromTradingSessions (:64-70) and key() (:28) or make them the single owner behind a receipt-supplied label.

SYMBOL SECOND — strictly edge-inward so each step is behaviour-preserving.
S1. Add model/Symbol + SymbolTest. Grammar = the union the seven existing regexes collectively need (`^VIX`, `BRK.B`, `SPXW`, `BRK/B`, ≤24). No call sites touched. Zero risk.
S2. Move util/Symbols.java:12-19 to `Symbol.list`; update the only two callers, OpportunityScanner.java:77 and AutoRecommender.java:225.
S3. HTTP edge (behaviour-identical, they already normalize): register the param converter, then CoreController:269, DataController:396-400, DiscoveryController:410/612/641, MarketStreamController:66, OutcomeController:108/262/856, PlanController:193, ResearchController:129/513-515, SparklineController:78, TradeController:167/561/564, WorldController:162/181/209.
S4. Market plane cache keys — the §7.6 payoff: MarketDataService.java:998 `norm` deleted; caches at :64/:67/:70/:76/:90 re-keyed on `QuoteKey(Symbol)`, `ChainKey(Symbol,LocalDate)`, `CandleKey(String,Symbol,LocalDate,LocalDate)`; the `"|"` concatenations at :207/:489/:529 deleted; `HistoricalAbsenceKey(String provider, Symbol symbol)` at :948.
S5. MarketDataEngine.java:77-79 + :102 become `Map<Symbol,…>` (this alone fixes refreshBlocking :366-370 by construction); then MarketSnapshotStore.java:34-48 normalizes on write.
S6. Provider port `MarketDataProvider` takes `Symbol`; delete CboeProvider:285-286 (+cacheKey :235), PolygonProvider:187, AlphaVantageProvider:69, EdgarProvider:60, NewsRssProvider:44, FixtureProvider:305; YahooFinanceProvider:87-88 keeps only the alias, and :95/:124-126 re-key on `Symbol`.
S7. Stores: StoredCandleStore:41, StoredOptionChainStore:46, StoredHistoricalOptionsProvider:68, MissingRangePlanner:110, ObservedCandleWriter:100, UnderlyingBackfill:48, UnderlyingCsvIngest:197-199, DataSyncState:123/148/154/177, DataJobService:463/465.
S8. Engines: EvaluationService (add the boundary, fixing ivHistory:386-397) + CompensationView:47-48/67-68, RecommendationEngine:174/462, AutoRecommender:292/317/340, RedeploymentFrontier:100/467-468/480, SignalEngine:114, ExposureSizer:29, ResearchQuestionEngine:118, PathEnsembleService:121/165/233/682, SimulationEngine:62/212, ScenarioCanvasValuator:125, Backtester:139/356, EtfLookThroughService:297, EventService:466-468, Universes:68, UniverseService:94, BroadBasedIndexOptions:50.
S9. Book/paper/plan: PositionsService:385, TradeService:155/313-350/900/1961/1999, PortfolioAccountingService:256/1317/2440-2442, CampaignService:1677, BookActionProjectionService:517/560/566, BrokerStatementParser:639-641, BrokerImportService, PortfolioCsvImport, AccountObjectiveService:36/52, PlanService:577-580, PlanAdoptionService:196/226/242, HeldPositionEconomicsService:142, PositionArtifactStore:250, PositionLifecycleDecisionService:550, ScenarioPositionScopeService:48/75, RecordingPolicy:89-92 (fixes the locale bug), PositionPackageFingerprint:103, PositionTransformation:369, StrategyCatalog:286, ETradeProvider:285/317, SimulatedWorld:69/182/373/378/452/456/491/514/526/572/584/595/607.
S10. Delete the seven regexes and the twenty-eight helpers; add a build guard forbidding `toUpperCase` on any identifier containing "symbol" outside model/Symbol.java, and a contract test that every horizon-accepting route rejects an undeclared horizon and that `Nd` round-trips sessions → wire → sessions.

