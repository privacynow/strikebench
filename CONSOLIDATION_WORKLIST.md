# Consolidation Worklist — every duplication (backend + frontend)

Authoritative inventory from the 9-agent completeness sweep (wf_6094442f-745), 2026-07-23.
Owner mandate: ALL duplications go, backend AND frontend; no 'different responsibility' escape.
`[x]` done this program · `[ ]` remaining. Front A (backend) first, then Front B (frontend).
**83 findings · 4 already done · 79 remaining.**

## PROGRESS — 2026-07-23 (this session)
Full suite **1073 tests green** after each landing. Committed:
- **backend-math COMPLETE**: `pricing/LognormalTerminal` (risk-neutral μ/sd ×4 + lognormal CDF ×2, incl. the 2nd normal-CDF); `util/Numbers.round2/round4` (~10 sites); `market/RateQuote.DEFAULT_MODELED_RATE` (4% ×5); `util/Quantiles` — ONE interpolated convention replacing floor ×3 / round ×1 / two ad-hoc interpolators, plus `Quantiles.index` for the representative-element selector (ScenarioCanvasValuator.Ranked); dollars→cents ×2 routed to `Money.toCents` (killed the raw-`Math.round` divergence on negative half-cents / NaN).
- **backend-fees COMPLETE**: `util/Fees` now owns `optionContracts(legs,qty)` + `openingCents` + `roundTripCents(=2×opening)`; TradeService/Backtester/RecommendationEngine delegate. Fixed a latent divergence — TradeService/Backtester charged the flat per-order fee on stock-only orders; unified to "stock-only pays nothing". FeesTest extended.
- Earlier this session (pre-compaction): CDF unification, `Fees.roundTripCents` + 2 fee-bypass fixes, `OptionBarWriter` (option_bar SQL), fresh-eyes constant.

REMAINING Front-A is all HIGH/structural: scan-service merge (AutoRecommender/OpportunityScanner share a fan-out primitive — touches live Discovery/Scout/PlanStrategy), greeks unit-contract (couples to API + Front-B renderers), symbol value-object (203 upper-case sites/78 files — needs classification, not blind sweep), marketdata quote/freshness authority, persistence stores (settings/ledger/accounts — DB-clock vs sim-clock timestamp care). Then Front B.



## backend-math
- [x] **HIGH** (DUPLICATE_DIVERGENT, ×2) Standard normal CDF N(x) (Abramowitz-Stegun 7.1.26)
      → BlackScholes.normCdf (pricing/BlackScholes.java)
- [ ] **HIGH** (DUPLICATE_EXACT, ×5) Floor-index sample percentile: sorted[clamp(floor(p*(n-1)),0,n-1)]
      → a shared Quantiles/Percentiles util (new util helper) with long[] and double[] overloads
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×5) Sample-quantile index convention (interpolated vs floor vs round) for terminal/band distributions
      → one canonical quantile convention in the shared Quantiles util
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×4) Risk-neutral lognormal terminal parameterization: mu = ln(S) + (r − σ²/2)·T, sd = σ·√T
      → a shared LognormalTerminal helper (e.g. LognormalTerminal.of(spot,sigma,tYears,rate) → {mu,sd}) alongside Blac
- [ ] **HIGH** (DUPLICATE_EXACT, ×5) 4% modeled risk-free-rate default
      → one named constant, e.g. RateQuote.DEFAULT_MODELED_RATE
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×2) Lognormal CDF P(S_T ≤ s) = normCdf((ln s − mu)/sd)
      → shared LognormalTerminal.cdf helper (using BlackScholes.normCdf)
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×2) Evenly-spaced representative-path selection from a rank-sorted terminal, forcing the median slot
      → shared representative-path selector in the Quantiles util
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×7) round2 helper: Math.round(v*100)/100.0
      → one util helper (e.g. util.Numbers.round2 or util.Money)
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×5) round4 helper: Math.round(v*10000.0)/10000.0
      → one util helper (e.g. util.Numbers.round4)
- [ ] **LOW** (DUPLICATE_EXACT, ×2) Dollars→cents helper: Math.round(dollars*100)
      → util.Money (already the cents-conversion boundary)

## backend-fees
- [x] **HIGH** (DUPLICATE_DIVERGENT, ×5) Round-trip commission derived as feesOpenCents × 2 (open+close doubling) instead of Fees.roundTripCe
      → util/Fees.java:21 Fees.roundTripCents — the doc explicitly says this class 'owns only the open+close doubling'
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×3) Opening single-order commission formula: optionContracts × feePerContractCents + feePerOrderCents
      → util/Fees.java — this half of the round-trip has no canonical home; add a single Fees.openingCents(optionContr
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×6) Option-contract count for fees: sum of non-stock leg ratios × qty
      → A single shared option-contract counter (e.g. util/Fees.optionContracts(legs, qty) or a LegMath util) that eve

## backend-greeks
- [ ] **HIGH** (MULTI_OWNER, ×3) Position-greeks shape: per-share delta/gamma + $/day theta + $/vol-point vega (deltaShares/gammaShar
      → One GreeksView record with named-unit fields; TradeService.PositionGreeks becomes/aliases it, portfolioGreeks 
- [ ] **HIGH** (DUPLICATE_EXACT, ×4) Raw Black-Scholes greek normalization: theta ÷ 365 (per-year → per-day) and vega ÷ 100 (per-1.00 → p
      → BlackScholes helpers thetaPerDay(...) and vegaPerVolPoint(...) (or a single GreeksView.ofRaw(...) normalizer);
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×3) Vega expressed in cents per volatility point — same unit, three different field names
      → GreeksView.vegaCentsPerVolPoint — one name for the cents-per-vol-point unit; all three records project to it.
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×4) Theta per day — same greek carried under divergent unit conventions ($/day vs cents/day) and names
      → GreeksView carries theta once with an explicit unit (e.g. thetaCentsPerDay), plus a dollars accessor derived f
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×5) Gamma — same raw model quantity carried under four names/units (gammaShares vs gammaSharesPerDollar 
      → GreeksView.gammaSharesPerDollar as the one raw field (correctly named), with a derived gammaDollarDeltaCentsPe
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×3) Dollar-delta conversion: share-delta × spot → cents
      → GreeksView.dollarDeltaCents(spotCents) — one derivation of dollar delta from share delta and spot.
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×2) Gamma → dollar-delta change for a 1% underlying move, in cents
      → GreeksView.gammaDollarDeltaCentsPer1Pct(spotCents) — single derivation feeding both StanceVector and GreekBloc

## backend-persistence
- [x] **HIGH** (DUPLICATE_EXACT, ×2) option_bar INSERT ... ON CONFLICT (symbol,asof,expiration,strike,opt_type,source,dataset_id) DO UPDA
      → One OptionBarWriter (analogous to ObservedCandleWriter) exposing upsertOptionBar(Connection, ...); both the sn
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×4) underlying_bar daily-bar upsert on ON CONFLICT (symbol,d,source,dataset_id) — same table + same conf
      → ObservedCandleWriter.upsertObservedBar (ObservedCandleWriter.java:72) — extend it to accept the observed flag 
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×6) settings key/value upsert: INSERT INTO settings(k,v,updated_at) ... ON CONFLICT (k) DO UPDATE SET v=
      → SettingsStore.put (SettingsStore.java:19) — the typed store already exists; the five other call sites must inj
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×5) settings key read: SELECT v FROM settings WHERE k=?
      → SettingsStore.get (SettingsStore.java:13) — the read half of the same store-bypass; the four other readers sho
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×7) ledger append INSERT INTO ledger(account_id,trade_id,ts,type,amount_cents,cash_after_cents,reserved_
      → A single LedgerStore.append(Connection, accountId, tradeId, ts, type, amountCents, cashAfterCents, reservedAft
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×3) funded-account INSERT INTO accounts(id,user_id,name,type,starting_cash_cents,cash_cents,reserved_cen
      → A private AccountService.insertFundedAccount(Connection, id, userId, name, type, cashCents, now) helper — the 
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×2) world-bound account INSERT INTO accounts(...,world_id) SELECT ?,s.user_id,?,?,?,?,?,0,?,?,s.id FROM 
      → AccountService.createForWorldOn (AccountService.java:200) — getOrCreateForWorld (line 216) should call createF
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×2) market_snapshot boot-load SELECT + fixture/simulated/modeled exclusion filter + STALE MarketSnapshot
      → A single private mapper + a shared filter-clause constant in MarketSnapshotStore; loadAll() and load(symbol) s
- [ ] **MEDIUM** (MULTI_OWNER, ×2) typed EAV metric writer: INSERT INTO <metric_table>(<owner_key>,metric_key,value_cents|value_number|
      → A shared typed-metric writer helper parameterized by (table, ownerKeyColumn) — e.g. MetricWriter.writeCents/Nu
- [ ] **LOW** (DUPLICATE_DIVERGENT, ×2) supersede-prior-CURRENT run: UPDATE plan_outcome_run SET state='STALE' WHERE plan_id=? AND context_r
      → A private PlanOutcomeService.supersedeCurrent(Connection, planId, contextRev, basis, datasetId) helper — the p

## backend-symbol-time
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×9) Ticker-symbol normalize + validation regex (trim → uppercase(Locale.ROOT) → matches(...))
      → A new io.liftandshift.strikebench.model.Symbol value object doing the one normalize+validate; every service/in
- [ ] **HIGH** (DUPLICATE_EXACT, ×18) Canonical plain symbol normalization: symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT)
      → Symbol value object (same as the normalize+validate finding) — a single Symbol.of(String).value(); cache keys 
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×3) Horizon grammar: named-horizon set {0dte,week,month,quarter} + exact trading-session token regex [1-
      → io.liftandshift.strikebench.model.Horizon — expose a single validate/parse the two callers delegate to (e.g. H
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×2) Trading-sessions → calendar-days conversion Math.max(1, Math.round(n * 7f / 5f))
      → Horizon (expose the sessions→calendarDays conversion as a shared method; DeclaredObjective.horizonCalendarDays
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×3) Count trading sessions in the half-open interval (from, to]
      → MarketHours.tradingDaysBetween
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×9) Annualized time-to-expiry year fraction = max(calendarDaysToExpiry, floor) / 365
      → OptionTime.toExpiry(today, expiry).years() — its javadoc already declares it 'One disclosed time convention sh
- [ ] **LOW** (DUPLICATE_EXACT, ×2) Minimum calendar days-to-expiry across an idea's option legs
      → OptionTime.nearest(legs, today).calendarDays() (and .sessions()) — already returns exactly this
- [ ] **LOW** (DUPLICATE_DIVERGENT, ×5) Select the listed expiration closest to a target N days out: min/sorted by abs(DAYS.between(today, e
      → A single expiry-selection helper (e.g. MarketHours.nearestExpiry(expirations, today, targetDays) or on OptionT

## backend-orchestration-api
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×2) Cross-symbol scan orchestration (virtual-thread fan-out + per-symbol failure isolation + evaluateBes
      → One scan service in recommend/ with Scout vs Opportunity expressed as policies (concurrency bound, ranking, re
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×2) Economic-readiness projection: EconomicReadiness.tally() -> per-candidate readinessTally.add(economi
      → One shared readiness-projection helper (alongside ApiResponses.EvaluationReceipt.attachTo, which is already th
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×2) Management protocol thresholds (take-profit ~50% of credit, stop ~2x credit, debit take-profit ~50%,
      → ProtocolEvaluator (self-described as 'the ONE mechanical protocol-trigger evaluator'); ManagementPlanner shoul
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×3) Symbol-list normalizer (trim + drop-blank + uppercase(Locale.ROOT) + distinct)
      → One shared static symbol-list normalizer (e.g. market/Universes or a Symbols util) referenced by all three
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×2) BookLayer result trio (compensation, compensationBasis, frontier) flattened into a result record plu
      → Embed the existing RedeploymentFrontier.BookLayer record (the canonical carrier both already build via compose
- [x] **MEDIUM** (DUPLICATE_EXACT, ×2) Fresh-eyes question string ('Would you open the exact position you still own today, ignoring sunk ca
      → HeldPositionEconomicsService.FRESH_EYES_QUESTION (already public static final and the lifecycle owner; PlanAdo
- [ ] **LOW** (DUPLICATE_EXACT, ×2) Fresh-eyes lens basis string ('Current observed executable marks; sunk campaign cash excluded.')
      → One private constant in PlanAdoptionReviewService for the fresh-eyes basis prefix, concatenated where the avai

## backend-marketdata
- [ ] **HIGH** (MULTI_OWNER, ×2) Last-known observed quote store (per-symbol in-memory quote cache)
      → MarketDataService.quoteCache — the sole owner of the last-known observed quote per symbol; MarketDataEngine sh
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×4) Single-flight collapse of concurrent same-key loads (one CompletableFuture per key, callers join)
      → One single-flight primitive owned by MarketDataService (the freshness/materialization authority); MarketDataEn
- [ ] **HIGH** (DUPLICATE_DIVERGENT, ×4) Quote staleness / freshness gate ('is this quote too old to be trusted live?')
      → MarketDataService is the sole freshness/eligibility authority (gateQuote/gateChain + QUOTE_STALE_MS/CHAIN_STAL
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×4) 'Now' time source for staleness/age arithmetic
      → The injected java.time.Clock that MarketDataEngine already holds (:99). MarketDataService should take the same
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×2) MarketSnapshot -> Quote field-for-field mapping
      → One MarketSnapshot->Quote converter (a static on MarketDataEngine.MarketSnapshot, or a single helper). Both ca
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×2) MarketSnapshot record restates the Quote data shape
      → model.Quote is the canonical quote shape. MarketSnapshot should wrap/compose a Quote (or its evidence) plus on
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×2) norm(symbol): s == null ? "" : s.trim().toUpperCase(Locale.ROOT)
      → One shared symbol-normalization util (e.g. Symbols.norm) referenced by both market-data owners (and, more broa
- [ ] **LOW** (DUPLICATE_DIVERGENT, ×3) Sane two-sided book / usable-mark predicate (bid>0 && ask>0 && bid<=ask)
      → model.Quote (hasSaneTwoSidedBook / mark / usesPreviousCloseFallback). MarketDataEngine.hasUsablePrice should h
- [ ] **LOW** (MULTI_OWNER, ×3) Provider-local I/O caches (Cboe option-chain payload, Yahoo rate-limit cooldown, SEC ticker->CIK map
      → Each stays local to its own provider — these are NOT duplications of the MarketDataService/Engine quote stores

## frontend-computation
- [ ] **HIGH** (LIVE_LEAK, ×1) Held-position POP recomputed on the client (gap-dial handler overwrites the backend popEntry)
      → Backend candidate/trade receipt: trade.popEntry (surfaced by authTradePosition at index.html:3731) and candida
- [ ] **MEDIUM** (MULTI_OWNER, ×2) Display price / mark authority re-derived for non-focused book symbols
      → Backend Quote.mark() (model/Quote.java:44: mid-of-bid/ask -> last -> prevClose) surfaced as ResearchDetail.dis
- [ ] **MEDIUM** (MULTI_OWNER, ×2) Day change percent `(last/prevClose-1)*100` originated in the browser
      → A backend changePct receipt on the quote payload (/api/quotes CoreController.java:180, /api/research/{symbol})
- [ ] **MEDIUM** (MULTI_OWNER, ×1) Trailing-window return `(end/start-1)*100` over a client-sliced candle window
      → A backend trailing-return receipt on /api/research/{symbol}/history (ResearchRoutes.java:43); backend serves r
- [ ] **MEDIUM** (LIVE_LEAK, ×2) Package life boundary (trading sessions to expiry) recomputed to truncate the SERVED book fan
      → Backend session/horizon receipt: the ensemble/animation returns sessionProgress + expiration (/api/plans/{id}/
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×2) Trading-sessions-until-a-date computed two different ways
      → desk-backend.js:209 sessionDistance() (exact Mon-Fri weekday count) — or better, the backend session receipt; 
- [ ] **MEDIUM** (MULTI_OWNER, ×6) Black-Scholes option pricing + greeks engine in the browser
      → Backend candidate evaluation greeks/price (candidateToDesk in desk-backend.js:1337 off /api/plans/{id}/strateg
- [ ] **MEDIUM** (MULTI_OWNER, ×1) Candidate finisher — net/credit, greeks, capital, edge, assignment originated locally
      → candidateToDesk (desk-backend.js:1337) maps backend entryNetPremiumCents, evaluation.risk greeks, capital.*, e
- [ ] **MEDIUM** (MULTI_OWNER, ×7) Probability-of-profit + tail/gap-risk + expected-shortfall model in the browser
      → Backend candidate.pop / riskProfile and book tail via /api/portfolio/book-risk (PortfolioRoutes.java:52), /api
- [ ] **MEDIUM** (MULTI_OWNER, ×5) Monte-Carlo path ensemble + valuation (POP, EV, p10/p50/p90, ES) generated in the browser
      → Backend ensemble: /api/plans/{id}/outcomes/ensemble and /api/plans/{id}/outcomes/ensemble/paths (PlanRoutes.ja
- [ ] **LOW** (DUPLICATE_EXACT, ×2) FNV-1a 32-bit string hash implemented twice
      → One shared hash helper (e.g. window.API/util) referenced by both; hashText and stringHash are byte-identical F
- [ ] **LOW** (MULTI_OWNER, ×1) Max-loss / max-profit derived by scanning a client payoff curve
      → Backend candidate.maxLossCents / maxProfitCents (candidateToDesk desk-backend.js:1359,1394) and trade.maxLossC
- [ ] **LOW** (MULTI_OWNER, ×3) Terminal payoff-at-price (leg intrinsic engine) originated for non-authoritative packages
      → Backend terminalPayoff polyline: candidate.evaluation.risk.terminalPayoff.points -> payoffPoints (candidateToD
- [ ] **LOW** (MULTI_OWNER, ×2) Book-wide greeks and scenario P/L recomputed for the position risk map
      → Backend /api/portfolio/greeks (PortfolioRoutes.java:51) for net greeks and the position-scenario receipt for p
- [ ] **LOW** (MULTI_OWNER, ×5) Chart analytics (SMA, realized-vol ±1σ band, IV expected-move cone) originated client-side
      → Derived series belong on a backend analytics receipt off /api/research/{symbol}/history + ivAtm; confirmed no 
- [ ] **LOW** (MULTI_OWNER, ×2) Fabricated P/L history series generated from a PRNG (not derived from any backend data)
      → Real per-position/book P/L history from the backend (trade valuations / portfolio summary /api/portfolio/summa
- [ ] **LOW** (DUPLICATE_DIVERGENT, ×2) Date-to-ordinal conversion implemented twice for different date formats
      → desk-backend.js:204 dateOrdinal() (parses YYYY-MM-DD to epoch-day) — the ISO path; index.html:6766 dateOrd() i

## frontend-format-dead
- [ ] **HIGH** (MULTI_OWNER, ×3) Price-basis / quote normalization decider (displayPrice + priceIsPreviousClose + basis label) implem
      → one shared price-basis decider (desk-backend researchMark) that every layer consumes
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×2) Safe finite-number parse (return the number or null)
      → one shared number-parse helper used by both index.html and desk-backend.js
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×4) Greek metric-row builder (label + formatted delta/theta/gamma/vega cell) hand-rolled instead of usin
      → gfmt() + GSPEC at index.html:4025-4031
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×12) money()/signed() rebuilt inline as '±$'+Math.round|abs(...).toLocaleString()
      → money() / signed() at index.html:3705-3706
- [ ] **MEDIUM** (DUPLICATE_DIVERGENT, ×12) Price 2-decimal formatter '$'+n.toFixed(2)
      → authHomePrice() at index.html:4906 (only 3 call sites today)
- [ ] **MEDIUM** (DUPLICATE_EXACT, ×13) Percent-change computation (cur/prev - 1) * 100 with no shared helper
      → a new pctChange(cur, prev) helper in index.html (none exists; authHomeQuote@4907 and authHomeHistoryRead@4910 
- [ ] **MEDIUM** (MULTI_OWNER, ×5) Duplicated frontend book state — index.html keeps its own BOOK_AUTH/POS/byId mirror of DeskBackend.s
      → DeskBackend.state.book (desk-backend.js) as the single owner; index reads the bridge payload
- [ ] **MEDIUM** (LIVE_LEAK, ×2) DEAD CODE — vol-scan panel builder + its wiring, never called
      → delete both (0 call sites) plus their orphaned CSS (.volscan/.vsrow/.vsym/.vname/.vscell/.vsig/.vsgo/.vshd)
- [ ] **MEDIUM** (LIVE_LEAK, ×1) DEAD CODE — providerStrip fabricates hardcoded 'live' provider status chips, never called
      → delete (0 call sites) plus orphaned CSS .provider/.pvchip/.pvdot
- [ ] **MEDIUM** (LIVE_LEAK, ×2) DEAD CODE — concentration-hero and churn book-risk card builders with hardcoded fixture positions, n
      → delete both (0 call sites) plus orphaned CSS (.conchero/.ch-verdict/.ch-stats/.ch-hedge/.chh-txt/.chs/.chk/.ch
- [ ] **LOW** (DUPLICATE_DIVERGENT, ×7) fmtK thousands-abbreviation rebuilt as inline '$'+(v/1000)+'k' for chart axes and governor readouts
      → fmtK() at index.html:3481 (extend with a $-prefixed variant)
- [ ] **LOW** (DUPLICATE_EXACT, ×2) Leg-chip HTML builder (c.legs.map -> .lsleg span) duplicated byte-for-byte
      → extract a single legChipsHTML(c) helper in index.html
