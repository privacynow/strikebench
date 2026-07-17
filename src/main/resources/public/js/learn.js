/* StrikeBench learning layer: experience ladder, glossary, per-strategy education.
 * Pure content + level helpers, attached to window (no ESM). */
(function () {
  'use strict';

  // ---- The experience ladder: exactly TWO shapes, never a middle tier ----
  var LEVELS = ['beginner', 'expert'];
  var LEVEL_META = {
    beginner: { label: 'Beginner', hint: 'Question-driven flows, plain language, extra safety context' },
    expert: { label: 'Expert', hint: 'Dense, data-first screens with deeper analytics' }
  };

  // Product vocabulary is a contract, not screen copy. A numeric concept keeps the
  // same label everywhere; the level changes only the depth of its explanation.
  var VOCABULARY = Object.freeze({
    assignmentCapital: { label: 'Assignment capital', infoKey: 'assignmentcapital',
      short: 'Cash or shares needed if an option is assigned; this is not the same as economic loss.',
      beginner: 'This is what the broker may require to complete the stock purchase or delivery after assignment. A hedge can limit the eventual loss without eliminating this temporary funding need.',
      expert: 'Gross settlement funding before protective-leg proceeds or reserve release. Report separately from max loss, scenario loss, and broker reserve.' },
    brokerReserve: { label: 'Broker reserve', infoKey: 'brokerreserve',
      short: 'Capital the account sets aside for an open obligation; it is not a loss forecast. Formerly labeled Reserved.',
      beginner: 'The account holds this amount so the position can meet its obligations. It reduces buying power while the trade is open and is released or reconciled when the position changes.',
      expert: 'Collateral encumbrance under the account model. Keep distinct from economic exposure and theoretical max loss, especially for credit structures.' },
    buyingPower: { label: 'Buying power', infoKey: 'buyingpower',
      short: 'Cash still available for a new obligation after the account’s current broker reserves.',
      beginner: 'Start with cash, then subtract the money being held for open positions. What remains is buying power. It can be much larger or smaller than the amount you should risk on one idea.',
      expert: 'Practice-account cash less active reserve encumbrances under the canonical reserve policy. It is an account-capacity field, not NLV, economic exposure, theoretical max loss, or a broker-margin forecast.' },
    netDelta: { label: 'Net delta', infoKey: 'netdelta',
      short: 'The position’s current share-like directional sensitivity after all legs are added together.',
      beginner: 'A net delta of +40 moves roughly like 40 long shares for the next small stock-price move; −40 leans the other way. It changes as price, time, and volatility change.',
      expert: 'Signed sum of leg delta × ratio × quantity × contract multiplier, expressed in share equivalents for one underlying. Cross-symbol Book aggregation uses dollar and beta weighting instead of summing raw share deltas.' },
    gamma: { label: 'Gamma', infoKey: 'gamma',
      short: 'How quickly delta changes when the underlying price moves.',
      beginner: 'Gamma tells you whether today’s share-like lean can change quickly. Large gamma near a strike or expiry means a small price move can make the position act very different.',
      expert: 'Second derivative of option value with respect to spot. Position views report signed share-delta change per $1 underlying move; cross-symbol Book risk reports dollar-delta change for a 1% move.' },
    thetaPerDay: { label: 'Theta / day', infoKey: 'thetaperday',
      short: 'The position’s modeled value change from one calendar day passing, with price and volatility held fixed.',
      beginner: 'Positive theta means the position tends to collect value as time passes; negative theta means it tends to lose value. Real price and volatility moves can easily outweigh it.',
      expert: 'Signed first-order time sensitivity aggregated across marked legs and expressed in dollars per calendar day. It is a local model statistic, not a promised daily P/L.' },
    vegaPerVolPoint: { label: 'Vega / vol pt', infoKey: 'vegapervolpoint',
      short: 'The position’s modeled dollar change when implied volatility moves by one percentage point.',
      beginner: 'Positive vega tends to benefit when option uncertainty rises; negative vega tends to benefit when it falls. This describes a small move around today’s marks, not a full scenario.',
      expert: 'Signed first-order IV sensitivity aggregated across marked legs, reported in dollars per one absolute volatility point. Surface skew, term movement, and large shocks require the Scenario Canvas rather than linear vega.' },
    economicExposure: { label: 'Economic exposure', infoKey: 'economicexposure',
      short: 'The capital or asset value whose movement economically affects this position.',
      beginner: 'This includes value already tied up in shares or cash, not only the new premium paid today. It answers how much of your book participates in the result.',
      expert: 'Position-level exposure denominator including held assets and funded obligations; not a broker margin field and not a cross-lane net.' },
    theoreticalMaxLoss: { label: 'Theoretical max loss', infoKey: 'theoreticalmaxloss',
      short: 'The structural loss boundary across the full payoff curve, including extreme terminal prices.',
      beginner: 'This is the mathematical boundary, even when reaching it would require an extreme move. Realistic modeled paths are shown separately as scenario loss.',
      expert: 'Terminal payoff infimum for the exact package and quantity. Undefined-risk structures have no finite value; do not substitute a stress percentile.' },
    theoreticalMaxProfit: { label: 'Theoretical max profit', infoKey: 'maxprofit',
      short: 'A fixed dollar ceiling exists only when the exact package has one payoff date and a finite best case.',
      beginner: 'For a calendar or diagonal, one option expires while another still has time value. The best result depends on the stock price and option volatility at that first expiry, so StrikeBench does not invent one dollar ceiling.',
      expert: 'Single-expiry structures report the terminal payoff supremum. Multi-expiry structures with a surviving leg report no fixed scalar because front-expiry spot, IV surface, skew, and remaining tenor determine continuation value.' },
    scenarioLoss: { label: 'Scenario loss', infoKey: 'scenarioloss',
      short: 'Loss on a named modeled or historical scenario; it never replaces the theoretical boundary.',
      beginner: 'This answers what the position loses in a particular calm, up, down, choppy, or tail path. The scenario and its evidence stay attached to the number.',
      expert: 'Path- or shock-conditioned P/L with basis, horizon, and provenance. Keep separate from CVaR and theoretical max loss.' },
    hypothetical: { label: 'Hypothetical', infoKey: 'hypotheticalprovenance',
      short: 'An analysis artifact only; it changes neither the practice account nor a tracked brokerage account.',
      beginner: 'You can edit, compare, and test this position without pretending it was placed.',
      expert: 'Execution lane NONE. Marks may be proposed or modeled and the coverage receipt must identify them.' },
    practice: { label: 'Practice', infoKey: 'practiceprovenance',
      short: 'A fill in StrikeBench’s isolated learning account, never a broker transaction. Formerly labeled Paper.',
      beginner: 'Practice trades use separate cash so you can rehearse decisions without changing a tracked real-money book.',
      expert: 'Execution lane PRACTICE. Never numerically netted with tracked-account cash, exposure, or performance.' },
    recordedAtBroker: { label: 'Recorded at broker', infoKey: 'brokerprovenance',
      short: 'A factual tracked-book transaction entered from a broker record; StrikeBench did not place it.',
      beginner: 'The fill belongs to a tracked account and must use the actual broker facts. Modeled prices cannot become its basis.',
      expert: 'Execution lane REAL with factual source, account, time, quantity, multiplier, and fill provenance.' },
    campaignEconomicBasis: { label: 'Campaign-adjusted economic basis', infoKey: 'campaignbasis',
      short: 'Share cost adjusted by the campaign’s exact option cash, dividends, and fees; it is not tax basis.',
      beginner: 'This helps judge the economics of the whole campaign after premiums, buybacks, dividends, and fees. Your broker’s tax basis can differ.',
      expert: '(Share cash paid - net option cash - attributed dividends + fees) / shares currently held. Interpretation layer only.' },
    trackedTaxBasis: { label: 'Tracked tax basis', infoKey: 'trackedtaxbasis',
      short: 'The lot basis used by the tracked accounting book, including applicable recorded adjustments.',
      beginner: 'This follows the lots and adjustments recorded in the tracked account. It is separate from campaign economics and must be reconciled with broker tax forms.',
      expert: 'Authoritative tracked-lot basis for the common-case worksheet, including recorded wash adjustments where reviewed; never inferred from campaign allocation.' },
    campaignNetCredit: { label: 'Campaign net credit', infoKey: 'campaignnetcredit',
      short: 'The signed sum of every option dollar this campaign has taken in or paid out, fees included.',
      beginner: 'Premiums you collected count in, buybacks and option fees count out. It is exact recorded cash, not a projection.',
      expert: 'Signed sum of campaign option cash: opening premiums, buybacks, and option-leg fees signed correctly. Stock cash, dividends, and interest are attributed separately.' },
    realizedVsHeadline: { label: 'Realized vs headline yield', infoKey: 'realizedvsheadline',
      short: 'One line, two honest rates on the SAME capital: the premium as quoted vs what the campaign actually kept.',
      beginner: 'The headline is the rent the campaign collected. Realized subtracts buybacks and fees and adds dividends and tagged interest. Both divide by the same committed capital, so the gap is real, never a denominator trick.',
      expert: 'Headline = gross premium collected ÷ peak committed capital; realized = campaign net P/L (buybacks, dividends, explicitly tagged interest) ÷ the identical peak committed capital over the identical window. Annualized only with the if-repeatable label; Expert adds the time-weighted-capital variant.' },
    peakCommittedCapital: { label: 'Peak committed capital', infoKey: 'peakcommittedcapital',
      short: 'The most outside money this campaign ever had at work at once — the honest yield denominator.',
      beginner: 'Cash tied up in shares plus cash set aside to secure short puts, at its highest point. Yields quoted on anything smaller would flatter the result.',
      expert: 'max over time of (short-put strike obligation − cumulative campaign cash). Both the headline and realized yields divide by this same figure.' },
    accumulationLedger: { label: 'Accumulation ledger', infoKey: 'accumulationledger',
      short: 'Every campaign event in order, with the running share count and running campaign-adjusted economic basis.',
      beginner: 'Read it top to bottom to see how each premium, roll, assignment, and dividend moved what the position really costs you per share.',
      expert: 'Chronological member events (recorded transactions plus pending package cash) with running shares, running net option cash, running campaign-adjusted economic basis, and running committed capital.' },
    counterfactualBenchmark: { label: 'Counterfactual benchmark', infoKey: 'counterfactualbenchmark',
      short: 'What the same money, moved on the same dates, would be worth in cash or in plain buy-and-hold.',
      beginner: 'It answers "was the campaign worth the effort?" while the campaign is still running — not after the fact. Deltas are the campaign minus each alternative.',
      expert: 'Cash lane compounds each external flow at a stated modeled 4% annual rate; buy-and-hold matches each flow into shares at that date’s eligible observed close with a stated dividend treatment. Unavailable is reported honestly, never fabricated.' },
    churnCost: { label: 'Churn round-trip cost', infoKey: 'churncost',
      short: 'What exiting and re-entering the same shares actually cost: the price gap times the shares, plus fees.',
      beginner: 'Selling at one price and buying back higher is a real cost even though no line item says so. This adds those round trips up.',
      expert: '(re-entry price − exit price) × paired shares + both legs’ fees, paired chronologically. The wash-sale consequence lands on the tracked tax basis, which is reported separately.' },
    campaignReview: { label: 'Close review', infoKey: 'campaignreview',
      short: 'The campaign’s frozen close record: what you authored, what happened, how you managed it, what it earned, and what you learned.',
      beginner: 'When a campaign closes, this puts the whole story in one place. It does not change any trade, lot, tax number, or recorded result; your lesson is the only editable part.',
      expert: 'Journey-E read model over immutable Plan/scenario lineage, recorded management marks/actions, and canonical campaign accounting. Review facts are recomputed from those owners; lessonNote is the sole mutable review field.' },
    authoredVsRealized: { label: 'Authored vs realized path', infoKey: 'authoredvsrealized',
      short: 'Your exact saved scenario pins over the observed closes that later arrived — your hypothesis beside reality.',
      beginner: 'The authored line connects only the prices you pinned in the Scenario Canvas. The realized line uses one coherent observed price source. If either is missing, StrikeBench says unavailable instead of drawing a substitute.',
      expert: 'Latest saved authored_scenario among confirmed campaign Plan members, chained by scenario fingerprint to its base ensemble fingerprint/context revision. Day 0 uses the ensemble anchor; waypoint dates use the NYSE session clock; realized bars must be coherent dataset=observed, observed=1, and non-generated.' },
    protocolAdherence: { label: 'Protocol adherence', infoKey: 'protocoladherence',
      short: 'Whether you answered the target, stop, or time line frozen with the decision — and the signed cost when an override is exactly priceable.',
      beginner: 'A respected rule was answered by the next recorded position action. An override needs evidence that the line was allowed to pass or answered with a different action. A negative signed cost means waiting hurt; a positive one means it helped.',
      expert: 'All thresholds come from ProtocolEvaluator.rules/evaluate. Override cost is response result minus trigger-mark P/L only when both prove the same whole-package trade and quantity; partial/changed-package bases withhold the number explicitly.' },
    campaignPattern: { label: 'Cross-campaign pattern', infoKey: 'campaignpattern',
      short: 'A repeated participation lesson supported by at least two closed campaigns in the same execution lane.',
      beginner: 'One example is a story, not a pattern. StrikeBench only names cash-secured-put regret or the covered-call melt-up mirror after two closed campaigns show the same structure during a material observed rise.',
      expert: 'Deterministic structural signature + final lane result + one coherent observed window with at least a 5% rise. REAL and PRACTICE support counts remain separate; fewer than two supports renders insufficient/unavailable, never inferred.' },
    lessonNote: { label: 'Lesson note', infoKey: 'lessonnote',
      short: 'Your own editable takeaway from the campaign; it never rewrites the evidence or accounting beside it.',
      beginner: 'Write the one thing you want your future self to remember before making this kind of decision again. You can edit it later.',
      expert: 'Owner-scoped campaign.lesson_note (max 4,000 characters), updated through the canonical campaign PUT. closed_at and every review fact remain immutable under lesson-only edits.' },
    accountObjective: { label: 'Account objective', infoKey: 'accountobjective',
      short: 'Your declaration of what this tracked account is FOR; every analysis on it is judged against the latest revision.',
      beginner: 'Income, accumulating shares, hedging, a directional view, or preserving capital — once you declare it, StrikeBench checks every idea on this account against that purpose and says whether they match. Declaring again adds a new revision; what you said before stays on record.',
      expert: 'An immutable revision series scoped to the tracked account. The latest revision feeds the analyze path as the DECLARED side of the coherence diagnostic and drives assignment-preference reweighting. Redeclaring writes revision N+1 prospectively; history is never rewritten and receipts keep the revision they quoted.' },
    coherenceVerdict: { label: 'Coherence verdict', infoKey: 'coherenceverdict',
      short: 'Whether an analyzed idea matches what this account is declared to be for: coherent, mixed, or incoherent.',
      beginner: 'After you declare the account objective, every analysis answers a plain question: does this idea serve that purpose? Coherent means yes, mixed means partly, incoherent means it works against what you said the account is for. It is a judgment on the receipt, never a block.',
      expert: 'assessment.coherence on the evaluation receipt: COHERENT / MIXED / INCOHERENT with direction and duration reasons, computed against the account’s latest objective revision and quoting its number. Advisory — it explains and reweights; it never gates an analysis.' },
    assignmentFit: { label: 'Assignment fit', infoKey: 'assignmentfit',
      short: 'How your declared assignment preference reweights an idea: assignment odds can count for or against a structure.',
      beginner: 'Being made to buy or sell the shares (assignment) is welcome in some accounts and unwelcome in others. Declare which kind this account is, and ideas with high assignment odds are scored accordingly — Avoid counts them against, Seek counts them in favor.',
      expert: 'Score component quoting the account’s declared objective revision. AVOID penalizes high assignment probability; ACCEPT applies no reweighting; PREFER_BELOW_BASIS favors assignment that adds shares below current basis; SEEK favors assignment likelihood (wheel-style operation).' },
    alertCenter: { label: 'Needs attention', infoKey: 'alertcenter',
      short: 'One list of everything your open positions are asking of you right now, worst first.',
      beginner: 'StrikeBench watches your protocols, expiries, earnings estimates, assignment risk, and unresolved imports, and puts them here in order of urgency. "Act today" items come first, then "Needs a look", then "Worth knowing". Each row opens the screen where you can decide.',
      expert: 'Computed per user across lanes from live marks and persisted state: protocol triggers (one evaluator, the ManagementPlanner thresholds), expiries with strike notional, estimate-labeled earnings windows, pin/extrinsic heuristics, and pending imports. Severity URGENT > ATTENTION > INFO drives the Desk ordering and the nav badge; changes stream as alerts.updated hints.' },
    protocolBreach: { label: 'Protocol trigger', infoKey: 'protocolbreach',
      short: 'A mechanical management rule you saw at Decide has fired against live prices.',
      beginner: 'When you entered the trade, it came with a plan: where to take profit, where to cut the loss, when to decide about rolling. This alert means one of those lines was crossed at current prices. It is a rule firing, not a prediction.',
      expert: 'ProtocolEvaluator applies the ManagementPlanner thresholds to executable closing marks: credit take-profit at ~50% capture, credit stop at ~2x credit, debit take-profit/stop at ~50% of the debit, roll/time review at ~21 days to expiry. One evaluator serves practice trades and tracked structures.' },
    pinRisk: { label: 'Pin risk', infoKey: 'pinrisk',
      short: 'The stock is sitting almost exactly on a short strike right before expiry, so assignment is close to a coin flip.',
      beginner: 'When the stock price hovers on your short strike in the last sessions, nobody knows whether the option will finish in the money. Decide ahead of time whether you want the shares — otherwise chance decides for you.',
      expert: 'Heuristic flag: spot within 1% of a short strike with 3 or fewer trading sessions to expiry. Not a probability model — a prompt to choose certainty (close or roll) over settlement randomness.' },
    extrinsicValue: { label: 'Time value left', infoKey: 'extrinsicvalue',
      short: 'The part of an option’s price above what exercising it now would collect; when it is near zero, early assignment gets likely.',
      beginner: 'An option’s price has two parts: what exercising is worth right now, and extra “time value”. The owner of an in-the-money short option you sold usually waits while time value remains — once it is worth less than the fees, exercising early starts making sense for them.',
      expert: 'Extrinsic = mark − intrinsic. The early-assignment heuristic flags short ITM options whose per-contract extrinsic is at or below the per-contract fee. Dividend-capture assignment needs an ex-div calendar, which is honestly unavailable — the surface says so instead of guessing.' },
    targetExposure: { label: 'Target exposure', infoKey: 'targetexposure',
      short: 'An optional declared dollar ceiling for how much of this account you intend to have at work.',
      beginner: 'A statement of intent recorded with the objective, in dollars. It does not block or resize anything; it gives reviews a number to compare actual exposure against, and the revision history shows when you changed it.',
      expert: 'Optional targetExposureCents (≥ 0) on the objective revision. Declarative context in this release: persisted with the revision series and quoted by surfaces; no gating or automatic sizing derives from it.' },
    bookRisk: { label: 'Book risk', infoKey: 'bookrisk',
      short: 'The risk of everything this account holds, added up honestly — thirty tickets can be one bet.',
      beginner: 'Each position can look fine alone while the account as a whole leans hard one way, owes cash on one date, or bets one theme repeatedly. This lane adds it up from the recorded lots themselves, so no grouping mistake can hide or double-count anything.',
      expert: 'Aggregates computed from open portfolio_lot rows directly, never from structure projections. Per-account first, then cross-account exposure subtotals; the Practice lane renders side-by-side and is never numerically netted (§3.13). Every aggregate carries provenance and coverage disclosures.' },
    betaWeightedDelta: { label: 'Beta-weighted dollar delta', infoKey: 'betaweighteddelta',
      short: 'Directional exposure in dollars, scaled by how hard each name historically moves with the market.',
      beginner: 'A dollar of NVDA exposure moves you more than a dollar of a utility, so raw exposure across different stocks does not add up fairly. Each name’s dollar exposure is multiplied by its observed sensitivity to the market (its beta) before summing. Names without enough price history are included unweighted, and the note says how many.',
      expert: 'Σ(option Δ × signed units × observed spot × β) with β regressed from observed underlying_bar closes vs SPY over matched sessions. The coverage line states the session count and how many symbols lack history (those enter at β=1, disclosed). Raw share delta is not additive across names and never appears in cross-symbol aggregates.' },
    symbolConcentration: { label: 'Symbol concentration', infoKey: 'symbolconcentration',
      short: 'The share of this lane’s gross directional exposure carried by the focused symbol.',
      beginner: 'This shows whether one ticker dominates the account after the proposed package is added. A high percentage can mean several positions are really one directional bet.',
      expert: 'Absolute focused-symbol dollar delta divided by the lane’s gross absolute dollar delta, before and after the exact package. Empty books stay named rather than receiving a fabricated zero denominator.' },
    stressedAssignment: { label: 'Stressed assignment capital', infoKey: 'stressedassignment',
      short: 'If the market drops, what your short puts would make you buy — versus the cash you actually have.',
      beginner: 'Every short put is a promise to buy shares at its strike. This applies one downside shock to every stock at once and totals the purchases those promises would force, next to the account’s recorded cash. It is a what-if, not a forecast.',
      expert: 'Uniform −10% spot shock; obligation = strike × contracts × multiplier summed over short puts ITM under the shock, compared per account against recorded book cash (never cross-account — cash is not fungible, §3.13). A labeled heuristic stress, not a correlation model; unmarked underlyings are totaled separately, never guessed.' },
    expiryCluster: { label: 'Expiry cluster', infoKey: 'expirycluster',
      short: 'Many obligations landing on one expiration date — one bad session can hit all of them at once.',
      beginner: 'Options that expire on the same day settle on the same day. When most of your book’s obligations share one date, a single bad session decides all of them together — there is no averaging over time. The calendar shows the dollars landing on each date.',
      expert: 'Strike-value notional (strike × contracts × multiplier) bucketed per expiration; a session is flagged at ≥40% of expiring notional or ≥3 option lots (a labeled heuristic threshold). Cross-account calendars sum per date after per-account rendering.' },
    themeConcentration: { label: 'Theme concentration', infoKey: 'themeconcentration',
      short: 'How much of the book sits in one theme — a label-based grouping, not a measured correlation.',
      beginner: 'Ten positions across five semiconductor names can be one bet wearing ten costumes. This groups your positions by StrikeBench’s curated theme labels and shows how much sits together. It is a classification, not a claim about how the stocks actually move together.',
      expert: 'Grouping by the curated 13-sector map (a theme CLASSIFICATION). True correlation claims would require observed return series and a stated coverage; none is asserted. Intra-theme contradiction detection compares structurally bullish exposure (short puts, long calls, long shares) against bearish (covered/short calls, long puts) within one theme and quotes the account’s declared objective revision when one exists.' },
    pendingImport: { label: 'Pending import', infoKey: 'pendingimport',
      short: 'Exact package cash from a broker record whose per-leg fills are still unresolved.',
      beginner: 'The total debit or credit is a real broker fact, but the price assigned to each leg is missing. It stays outside every tracked account until you resolve every leg to the same exact total.',
      expert: 'Owner-scoped portfolio_import_pending fact keyed by source system + non-reversible account fingerprint + external reference. It may contribute package economics to a campaign but cannot create lots or authoritative tax basis.' },
    resolutionAuthority: { label: 'Resolution authority', infoKey: 'resolutionauthority',
      short: 'Whether resolved per-leg prices came from the broker or from your own allocation.',
      beginner: 'Broker-confirmed leg fills can become tracked tax basis. Your own allocation keeps the package economics exact but remains provisional for taxes.',
      expert: 'BROKER_REPORTED creates AUTHORITATIVE basis provenance; USER_ALLOCATED creates PROVISIONAL basis provenance and withholds dependent tax output. Both reconcile the signed package cash to the cent and freeze a RESOLUTION receipt.' },
    accountFingerprint: { label: 'Source account fingerprint', infoKey: 'accountfingerprint',
      short: 'A one-way account identifier used to keep the same broker order reference distinct across accounts.',
      beginner: 'Two accounts can reuse the same order number. StrikeBench turns your account label or last four into a one-way fingerprint, then discards the raw value.',
      expert: 'Versioned SHA-256 derivation over source system + normalized source account, truncated for identity. Raw account identifiers are neither persisted nor returned to the UI.' }
  });

  var MOVED_TERMS = Object.freeze([
    { former: 'Trade', current: 'Plans',
      detail: 'Each ticker, goal, exact structure, outcome test, decision, and review now stays together.' },
    { former: 'the four-step trade flow', current: 'six Plan stages',
      detail: 'Understand, Evidence, Strategy, Outcomes, Decide, and Manage & Review make the handoffs explicit.' },
    { former: 'Find ideas', current: 'Find an idea / Start a Plan',
      detail: 'Screen first from Home or Research, then carry the result into the same Plan journey.' },
    { former: 'Ideas', current: 'Proposed trades / Ranked field',
      detail: 'Beginner and Expert see the same ranked packages with presentation suited to each level.' },
    { former: 'Scan', current: 'Scout',
      detail: 'Universe Scout finds tickers; in-Plan Scout finds similar setups, better fits, and offsets.' },
    { former: 'Paper', current: 'Practice',
      detail: 'The isolated learning account remains separate from tracked brokerage records.' },
    { former: 'Reserved', current: 'Broker reserve', infoKey: 'brokerreserve',
      detail: 'Capital held for obligations remains distinct from worst-case economic loss.' }
  ]);

  // ---- Glossary: tap-to-define for terms of art ----

  /**
   * THE definitions catalog (Block E): every technical label's explanation lives HERE, once —
   * screens reference keys, so a term can never mean different things on different pages.
   * short = the one-liner every bubble opens with; beginner/expert = the expanded detail.
   */
  var INFO = {
    breakeven: { short: 'The underlying price where the exact package has neither a gain nor a loss before costs.',
      beginner: 'A breakeven is one boundary, not a safety promise. The stock can cross it along the way, and fees or a multi-expiration option’s remaining time value can change the actual result.',
      expert: 'Zero of the exact selected package payoff on its stated valuation date before commissions. Single-expiry terminal roots are reported directly; multi-expiry structures do not receive a fabricated one-date scalar.' },
    thesis: { short: 'Your declared view — the product judges everything against it, and never invents one for you.',
      beginner: 'Up, down, sideways, or a big move either way: what you believe this stock does over your horizon. Proposals are ranked for it, evidence tests it, and later StrikeBench warns you when your positions stop expressing it. If you have no view yet, that is fine — explore the evidence first; nothing ranks until you declare.',
      expert: 'The context revision’s thesis field. It conditions DecisionPolicy ranking, seeds scenario drift defaults, anchors the coherence check (declared view vs the book’s stance vector), and is frozen into every decision receipt at its revision. Null is a legal state: ranking is refused, never defaulted.' },
    historicalsetup: { short: 'The event that happens first; the view says what you expect after it.',
      beginner: 'Choose one observable starting condition. A pullback measures distance below a recent high; a sharp down day is one large daily loss; a new high is a breakout; momentum is a gain across several sessions; an up streak counts consecutive rising closes. This trigger is separate from what you expect next.',
      expert: 'The ResearchQuestionEngine catalog key and its visible threshold parameters. The signal is evaluated at session t using information available through t only; the Plan thesis is the distinct forward-outcome claim over horizonDays. Pullback and momentum are multi-session measurements, oversold_bounce is a single-session return, breakout is a rolling-high test, and up_streak counts consecutive positive closes.' },
    plangoal: { short: 'The job this one Plan should accomplish; it decides which structures belong in the comparison.',
      beginner: 'Choose whether the Plan should trade a view, collect income, protect shares, buy shares lower, or sell shares at a target. StrikeBench then uses the same Plan journey and ranks structures for that job.',
      expert: 'StrategyIntent is Plan-owned and feeds the existing catalog filter, account-fit checks, ranking, assignment interpretation, and Decision policy. Changing it does not create a parallel recommendation path or a second Plan entry point.' },
    pendingimport: { short: 'Exact broker package cash awaiting per-leg prices before it can enter a tracked account.',
      beginner: 'A pending import is not a position yet. Resolve every leg from a broker confirmation or a clearly labeled personal allocation, or reject it. Nothing is auto-submitted.',
      expert: 'Separate owner-scoped quarantine keyed by source system, account fingerprint, and external reference. No portfolio_transaction or lot exists until an exact-cent resolution commits.' },
    resolutionauthority: { short: 'The provenance of the per-leg values used to resolve package cash.',
      beginner: 'Broker-reported means the broker supplied every leg fill. User-allocated means you divided the exact total yourself; the economics work, but tax truth stays provisional.',
      expert: 'BROKER_REPORTED/AUTHORITATIVE versus USER_ALLOCATED/PROVISIONAL. The distinction is frozen on the RESOLUTION receipt and enforced in tax output.' },
    accountfingerprint: { short: 'A non-reversible identity for one source account — never the raw account number.',
      beginner: 'It prevents the same order number in two accounts from being mistaken for a duplicate. You cannot recover the account number from it.',
      expert: 'SHA-256(source-system namespace + normalized source account), versioned and truncated. It participates in the four-part pending-import uniqueness key.' },
    horizon: { short: 'How long the view gets to play out — studies and expirations focus around it.',
      beginner: 'The number of trading sessions you give this idea. Shorter horizons favor nearer expirations and tighter evidence windows; longer horizons widen both.',
      expert: 'Context horizonDays. It is THE study hold-forward horizon (one truth across evidence and strategy), filters candidate expirations, and scales scenario path length. Revisions version it; receipts pin it.' },
    riskbudget: { short: 'How much of your account one idea may put at risk — sizing derives from this, not from income wishes.',
      beginner: 'Cautious, Standard, or High sets the ceiling for what this Plan may lose if everything goes wrong. Quantity suggestions come from this budget.',
      expert: 'riskMode drives RiskBudgetPolicy’s per-trade risk-capital ceiling (percent of NLV by mode). Over-budget structures stay visible for learning and require an explicit acknowledgment at commit; placement never silently resizes.' },
    bookGreeks: { short: 'The four sensitivities: how the whole book moves with price, price-of-price, time, and volatility.',
      beginner: 'Net delta: this book currently gains or loses like holding that many shares. Gamma: how fast that share-like exposure itself changes as price moves. Theta/day: the dollars the book bleeds or collects each day from time passing. Vega/vol pt: the dollars gained or lost if implied volatility moves one point. All are model statistics from current marks — descriptions, not promises.',
      expert: 'Share-equivalent Δ and Γ summed across legs (per-underlying share equivalence — cross-symbol aggregation is dollar-weighted where marked); Θ and vega in dollars per calendar day / per vol point at current marks. PARTIAL badges mean at least one leg lacked a defensible mark and was excluded rather than fabricated.' },
    studySelectivity: { short: 'One dial that rewrites the trigger numbers below — the engine only ever sees those numbers.',
      beginner: 'Looser triggers count more past days, so you get more examples but weaker ones; stricter triggers count fewer, clearer examples. Picking an anchor rewrites the trigger numbers underneath it, and typing your own numbers flips this to Custom. More examples smooth out luck; stronger examples match your setup better.',
      expert: 'A client-side macro over the per-question threshold parameters (dropPct, lookback, thresholdPct, streak). Anchors write preset values into the visible inputs; the study request carries only those raw parameters — never this setting. Hand-editing any threshold detaches the macro (Custom). Looser thresholds raise event count and overlap; stricter thresholds cut sample size and widen intervals.' },
    studyTrigger: { short: 'The exact past-day condition the study counts as one example.',
      beginner: 'These numbers define what must have happened on a past day for it to count — for example, how far the stock fell from its recent high. Every counted day is then compared with the stock’s normal days over your horizon.',
      expert: 'Signal definition evaluated on daily closes with no look-ahead: the condition uses only data up to day t. Counted events respect the minimum-separation rule; the baseline is the disjoint non-signal complement of the same sample.' },
    studyRegime: { short: 'Restricts the study to past days with a chosen market backdrop, compared against normal days from that same backdrop.',
      beginner: 'Markets behave differently in uptrends, downtrends, more-volatile stretches and quieter stretches. Choosing a backdrop asks: did my setup work under conditions like these? Both the counted examples and the normal days they are compared with come from the same backdrop, so the comparison stays fair. Volatility measures the size of swings; it does not claim those swings were directionless or choppy.',
      expert: 'Engine-side conditioning (regime parameter): ABOVE/BELOW_200DMA classify by close vs the trailing 200-session mean; HIGH/LOW_VOL by 20-session realized σ vs its 60-session baseline. Both events AND baseline days are restricted, so the comparison is within-regime. The on-screen today-indicator mirrors the same math on the same candle series and is omitted when history is insufficient.' },
    studyWindow: { short: 'Which stretch of past sessions the study is allowed to search.',
      beginner: 'A short window reflects the stock as it trades now but holds fewer examples; all history holds more examples that may come from a very different company or market. The dates shown under the control are exactly what will be searched.',
      expert: 'Sets the sample’s from/to dates (anchors rewrite the from date; Custom exposes both). Longer windows raise event counts and power but mix regimes and secular drift; the split-half check reports whether the effect lived in both halves of whatever window you choose.' },
    studyConfidence: { short: 'How sure the study must be before it calls a result real.',
      beginner: 'At 95%, the study only says history agrees when a pattern this strong would be rare by pure chance. Raising it to 99% means fewer false alarms, but more real-but-modest edges get called inconclusive.',
      expert: 'Confidence level for the overlap-adjusted two-sided z screen and the bootstrap interval quantiles (90/95/99 map to the critical z). Higher confidence widens reported intervals and raises the pass bar; combine with the multiplicity policy for the effective threshold.' },
    studyMultiplicity: { short: 'Whether the pass bar accounts for every question you could have asked.',
      beginner: 'If you check enough patterns, one will look good by luck. Protected mode raises the bar to cover every question in the catalog, so a pass means more. Exploratory takes each result at face value — fine for browsing, not for deciding.',
      expert: 'CATALOG_BONFERRONI divides alpha by the question-catalog size before the z screen (family-wise error control across the catalog); UNADJUSTED_EXPLORATORY screens at the raw alpha. The result banner reports the policy alongside the screen it applied.' },
    waypointFill: { short: 'How your pinned levels were honored: exactly (the model’s own conditioned randomness) or by bending paths toward them (an approximation).',
      beginner: 'When you pin “the price touches this level around this session,” StrikeBench must make the simulated futures pass through your pins. For smooth models it can do that exactly — the futures are still the model’s own randomness, just the ones that pass through your pins. For models with sudden jumps or fat tails there is no exact way, so each path is gently bent toward your pins instead. The label always tells you which one happened, because odds from bent paths are a guide, not a guarantee.',
      expert: 'EXACT_CONDITIONAL: Gaussian models (GBM, Brownian bridge) are conditioned through the pins by bridge sampling — the pinned ensemble is a true draw from the conditional law. GUIDED_INTERPOLATION: Student-t, jump-diffusion, Heston, and block-bootstrap paths are deterministically warped through the pins; jump timing/fat-tail mass near pinned sessions is approximated, not resampled. The label derives from the spec server-side and is persisted with every fan and authored scenario — it can never drift from the paths it describes.' },
    authoredScenario: { short: 'A path you drew on the fan — your pins plus the exact model settings, frozen with a receipt naming the fan it was authored on.',
      beginner: 'Click the chart to pin where you believe the price goes; the futures re-run through your pins immediately. Saving names that belief and keeps it — loading it later re-applies the same pins so you can re-judge the same story as the market moves. If the Plan’s assumptions change, the saved scenario says so and offers to re-anchor instead of silently pretending nothing moved.',
      expert: 'An authored scenario freezes the full sane spec (waypoints included), the waypoint-fill label, and a SHA-256 receipt chained to the base fan’s fingerprint — the same lineage family as every other stored simulation. Saving requires the base fan to belong to the current context revision; loading a stale-revision scenario surfaces the drift and re-anchors by re-running the pins on the current fan (out-of-horizon pins are dropped visibly, never silently).' },
    cashbaseline: { short: 'The explicit do-nothing alternative: no position, no cost, and $0 modeled P/L on every shared path.',
      beginner: 'Every proposal must earn its place beside simply keeping the cash. Cash is not another prediction; it is the unchanged baseline that exposes trades whose costs and bad outcomes are not worth taking.',
      expert: 'A zero-exposure, zero-fee comparison row evaluated on the identical stored ensemble and fingerprint. It participates in ranking without creating a second path fan or pricing API.' },
    entrycashflow: { short: 'The signed package cash at entry: a debit costs cash and a credit brings cash in.',
      beginner: 'This is only the opening cash movement for the exact contracts and quantity. It is not the same as the most you can lose, the broker reserve, or the amount needed if assigned.',
      expert: 'Executable-side net option premium for the captured package and quantity, with the sign displayed consistently across proposals, Builder, Outcomes, and the frozen decision receipt. Fees and risk limits remain separate fields.' },
    marketodds: { short: 'Odds and expected value implied by the captured option prices, not a forecast of what the stock will do.',
      beginner: 'This lens asks what the option market’s own prices imply for this exact package. Compare it with possible futures and history, but never average those different questions into one confidence number.',
      expert: 'Risk-neutral terminal valuation from the captured executable package, chain IV, rate, and disclosed dividend assumption. It is an economic pricing lens and remains separate from physical path and historical evidence.' },
    possiblefutures: { short: 'Many modeled price and volatility paths drawn from one stored, fingerprinted ensemble for this Plan.',
      beginner: 'The fan shows a range of ways the market could travel, including ordinary and bad outcomes. It is a what-if distribution, not a promise, and every structure in the comparison uses these same futures.',
      expert: 'The Plan-owned PathEnsembleService artifact with explicit model, seed, horizon, path count, calibration, and fingerprint. ScenarioSimulator and the Canvas revalue packages on this artifact; consumers never generate a second fan.' },
    pastanalogs: { short: 'Observed historical episodes selected because their starting conditions resemble this Plan’s named setup.',
      beginner: 'This lens asks what followed similar past situations. A small or incomplete sample stays visible as a limitation, and history is not treated as model odds.',
      expert: 'Condition-matched observed episodes or conditional block resamples using the declared setup and horizon with no look-ahead. Provenance, sample size, overlap, and coverage remain attached.' },
    rulereplay: { short: 'A named entry and management rule repeated through history without using future information.',
      beginner: 'Replay tests whether a repeatable process held up across many past entries. It does not pretend today’s exact listed contracts existed on every date.',
      expert: 'No-look-ahead historical strategy replay with explicit entry, exit, pricing tier, costs, capital gate, and window-end treatment. It is a rule-level experiment, distinct from exact-package analog valuation.' },
    dividendyield: { short: 'The annualized dividend assumption used while repricing options on the scenario path.',
      beginner: 'Dividends can change call and put values. Leave this blank when you do not have a defensible source; pricing then uses 0% and keeps that limitation visible.',
      expert: 'Continuous annual dividend yield q supplied to the canonical path valuation kernel. Null retains unavailable provenance and invokes the explicitly disclosed 0% pricing fallback; it never becomes observed data.' },
    volatilityskew: { short: 'How implied volatility changes across lower and higher strikes.',
      beginner: 'A negative strike tilt makes lower-strike options use more volatility than higher strikes. This changes option values even when the stock path stays the same.',
      expert: 'Scenario surface skew in volatility points per log-moneyness, applied by the shared PathValuationKernel around each day’s ATM-IV node.' },
    volatilityterm: { short: 'How implied volatility changes between nearer and later expirations.',
      beginner: 'A positive time tilt makes later-expiring options use more volatility than nearer ones. It matters when a package has different expiration dates.',
      expert: 'Scenario term slope in volatility points per square-root year, applied consistently to every surviving leg on each valuation session.' },
    surfacedynamics: { short: 'Whether the volatility tilt stays attached to strikes or moves with the stock.',
      beginner: 'Sticky strike leaves the tilt at the original strike prices; sticky moneyness lets the tilt travel as the stock moves. The choice can materially change multi-day option values.',
      expert: 'Typed STICKY_STRIKE versus STICKY_MONEYNESS transformation policy for the scenario IV surface, frozen in the authored-scenario receipt.' },
    settlementpolicy: { short: 'What the scenario valuation does when an option reaches expiration.',
      beginner: 'Cash settlement turns the expiring option’s intrinsic value into cash. Physical transformation can create or deliver shares while later legs keep running.',
      expert: 'Typed CASH_INTRINSIC versus PHYSICAL_IF_ITM expiry transformation in PathValuationKernel. Each leg uses its own expiry session; surviving legs retain time value.' },
    exercisepolicy: { short: 'When the scenario is allowed to transform an in-the-money option before or at expiration.',
      beginner: 'Expiration only waits until the contract ends. The low-time-value rule can exercise at a modeled daily close when very little extra option value remains.',
      expert: 'Typed EXPIRATION_ONLY versus EXTRINSIC_THRESHOLD exercise policy. Threshold checks occur only on modeled daily closes and remain distinct from broker-specific assignment prediction.' },
    scenariocomparison: { short: 'The baseline question used to choose which exact packages appear side by side on the same paths.',
      beginner: 'Compare your position with the Plan idea, compare two structures, or compare one structure with shares. Only the question changes; the saved futures and pricing assumptions do not.',
      expert: 'Presentation-only selection among canonical same-symbol PositionViews plus the stock baseline. It changes neither ensemble identity nor package valuation and creates no parallel comparison API.' },
    simulationLineage: { short: 'One simulation per Plan: every band quotes this same stored run, named by this fingerprint.',
      beginner: 'When you declared your view, StrikeBench generated one set of possible futures for it. Every later step — evidence, outcomes on your exact trade, review — re-uses that same stored set, so numbers agree everywhere. The # code names that one simulation.',
      expert: 'The plan-context ensemble is generated once, persisted, and fingerprint-pinned (SHA-256 over spec + path bytes). Evidence fans, structure repricing, comparisons, and rehearsals all consume the identical stored path matrix; the chip exposes EnsembleRef{id, fingerprint, basis}. A context revision that invalidates inputs mints a NEW ensemble and fingerprint — never a silent regeneration.' },
    pop: { short: 'This counts even a $1 gain; compare it with full-win, full-loss, costs and EV before judging quality.',
      beginner: 'Out of 100 futures the options market itself prices, roughly this many end with you making something — even $1. It is a model number, before commissions, not a promise.',
      expert: 'Risk-neutral P(profit) under a lognormal terminal distribution at the chain\u2019s own IV and the disclosed risk-free rate, with dividend yield q assumed zero. Calendar-time IV basis; not a physical forecast.' },
    ev: { short: 'Use this as the price-of-entry baseline: below zero after costs means the package starts behind.',
      beginner: 'Imagine running this exact trade in every future the market thinks is possible, and averaging the results. Negative means the market charges more than the position is worth by its own odds.',
      expert: 'Present-value risk-neutral expectation at chain IV and the disclosed rate, with q=0 assumed. Entry cash stays at time zero; terminal payoff is discounted. The Decision score judges it net of round-trip commissions.' },
    evhistvol: { short: 'Use this second lane to test whether the result changes when recent stock movement replaces option-implied movement.',
      beginner: 'If the stock keeps moving the way it recently has (rather than the move options are pricing), this is the average outcome. The gap can illustrate a volatility-premium thesis; it does not prove the edge will persist.',
      expert: 'Historical-vol SCENARIO EV: the identical integral at 30-day realized \u03c3, zero drift. NOT the physical measure (no drift estimate); the spread vs risk-neutral EV visualizes the volatility risk premium.' },
    cvar: { short: 'The average result of your worst 1-in-20 outcomes — the \u201cbad night\u201d number.',
      beginner: 'Ignore the typical case: if you only look at the worst 5% of ways this can go, this is what an average one of THOSE costs you.',
      expert: 'CVaR(95%): E[P&L | P&L \u2264 5th percentile] under the risk-neutral terminal distribution. Bounded by max loss for defined-risk structures; for undefined risk see the stress figure instead.' },
    pmaxloss: { short: 'Only the full-loss plateau counts here; smaller losses are excluded from this number.',
      beginner: 'Not just \u201closing something\u201d — this is the chance you lose the entire stated maximum. If it is the biggest number on the card, the most likely single outcome is the worst one.',
      expert: 'Probability mass on the max-loss plateau (within 0.5% of the extreme) under the risk-neutral terminal distribution. Undefined-risk structures report 0 here — no plateau exists; see stress/CVaR.' },
    pmaxprofit: { short: 'Only the exact best-case plateau counts here; ordinary profitable outcomes are excluded.',
      beginner: 'The best case, exactly: for a credit trade this is the chance every short option expires worthless and you keep it all.',
      expert: 'Probability mass on the max-profit plateau. Uncapped structures report 0 by construction.' },
    touch: { short: 'The chance the stock TOUCHES this strike before expiration, not just finishes there.',
      beginner: 'Prices wander: even a trade that ends fine often visits the scary strike first. Touching usually forces a decision.',
      expert: '\u2248 2\u00d7 the expire-beyond probability (reflection heuristic), capped at 1. A rough planning number, not a barrier-exact price.' },
    concession: { short: 'Dollars surrendered to the bid/ask spread just to ENTER.',
      beginner: 'The books have a gap between buyers and sellers; crossing it costs real money before the trade even starts — and you pay again on the way out.',
      expert: 'Package midpoint minus your (proposed or executable) net, in $ and as % of mid / max profit / max risk. Exit estimate = half the summed package spread again.' },
    decisionscore: { short: 'The 0\u2013100 composite that exactly ORDERS every decision-ranked list.',
      beginner: 'First come the safety checks, then the economic read, then odds, costs, evidence and tail risk. Higher is always earlier in the list; zero means a hard check failed.',
      expert: 'Gate \u2192 economic band (unfavorable 1\u201325, unavailable 26\u201350, mixed 51\u201375, favorable 76\u2013100) \u2192 within-band risk score. The latter combines POP .10, R:R .08, after-cost EV .35, liquidity .12, capital .05, evidence .15 and thesis .15, then applies evidence/tail/DTE haircuts.' },
    evidence: { short: 'How REAL the data behind this number is.',
      beginner: 'Observed = a market quote or history. Modeled = a formula\u2019s output. Demo = built-in practice data. Demo never substitutes for Observed; within one result, the weakest permitted input labels the answer.',
      expert: 'Per-dimension provenance (quotes/IV/greeks/history) rolled up worst-of. DEMO_FIXTURE never masquerades; UNKNOWN receives no evidence credit and a larger uncertainty haircut, but remains visible for comparison unless a genuine mechanical or account gate fails.' },
    ivrank: { short: 'Where today\u2019s option prices sit vs their own past year.',
      beginner: 'High rank = options are expensive by this stock\u2019s own standards (good for sellers); low = cheap (good for buyers).',
      expert: '(IV \u2212 52w min)/(52w max \u2212 min) over OUR observed snapshot history; null until \u226510 observations — never fabricated.' },
    vrp: { short: 'The gap between what options charge and how much the stock actually moves.',
      beginner: 'Options usually price MORE movement than happens — that overcharge is what disciplined sellers harvest, and it is also what buyers pay for protection.',
      expert: 'Implied vs realized vol (30d). Positive VRP favors short-premium IF realized stays put; the evHistVol lane shows the same trade under realized \u03c3.' },
    nlv: { short: 'Use this account-wide value as the denominator for risk percentages; it is not the cash available for the next trade.',
      beginner: 'Cash plus everything you hold, marked at current prices. The truest \u201chow much money do I have\u201d number.',
      expert: 'Self-declared here (never inferred from one broker field); used as a sizing denominator for account-fit percentages.' },
    riskcapital: { short: 'This personal ceiling can be tighter than the header budget; crossing it requires an explicit acknowledgment.',
      beginner: 'You set it once for your real account; every review then shows the worst case against it and makes you acknowledge crossing it.',
      expert: 'Caps the engine\u2019s per-trade budget when declared and adds a required server-side acknowledgment above it.' },
    filterloss: { short: 'This is an extra hard ceiling below the header budget; leave it blank to use the header setting.',
      beginner: 'Use it when this particular idea deserves a tighter dollar limit than your normal risk setting. Ideas above it are refused with the exact amount shown.',
      expert: 'Absolute candidate max-loss cap. It composes with the Plan’s saved risk mode and declared-capital budget by taking the tighter eligible limit.' },
    filterpop: { short: 'Raising this rejects more trades, but a high modeled chance alone does not make a trade good.',
      beginner: 'This is a minimum for making anything at expiration, even $1. Keep checking costs, full-loss odds and expected value; a high chance can hide a poor payoff.',
      expert: 'Hard minimum risk-neutral P(profit). Applied after executable-side construction; it does not supersede EV, tail risk, evidence or the Decision score.' },
    filterassignment: { short: 'Assignment can be the goal when buying lower or selling at a target; do not screen it as danger automatically.',
      beginner: 'For covered calls and cash-secured puts, ending up buying or selling shares may be exactly what you asked for. Leave this blank in those flows unless you truly want a cap.',
      expert: 'Hard cap on aggregate short-strike assignment probability. Interpret by intent: adverse for many option trades, success for ACQUIRE/EXIT.' },
    filteryield: { short: 'This applies only to share-backed income; it does not compare ordinary option spreads.',
      beginner: 'It turns the premium left after opening fees into a yearly rate so different expirations can be compared. A high annualized rate can still come with assignment or downside risk.',
      expert: 'Minimum annualized net-opening-premium yield for covered calls, cash-secured puts and collars, using share value or the full strike-cash obligation as collateral.' },
    filtercost: { short: 'This limits debit paid to enter; it does not cap the loss of a credit trade.',
      beginner: 'Use it for positions where cash leaves your account up front. For a credit trade, use the worst-case limit instead.',
      expert: 'Hard cap on negative entry net premium at executable sides. Credit structures bypass it and remain governed by max loss and buying power.' },
    expectedmove: { short: 'The range the options market expects the stock to stay inside (\u00b11\u03c3).',
      beginner: 'About 2 out of 3 futures end inside this band. Selling strikes INSIDE it means betting the market has overpriced its own move.',
      expert: 'spot \u00b7 exp(\u00b1\u03c3\u221aT) at the position\u2019s vol/time basis; drawn on the payoff chart and included in the domain window.' },
    sessions: { short: 'Trading days left — weekends and holidays don\u2019t move prices the same way.',
      beginner: 'A Friday-to-Monday trade has ONE day of real trading, not three. Less time to be right, and a weekend gap you cannot react to.',
      expert: 'NYSE-calendar sessions to nearest expiry; drives the near-expiry regime (\u22645 sessions) and plans. Model T stays calendar-basis to match chain-IV annualization.' },
    riskfreerate: { short: 'The annual interest-rate input used when translating option prices into modeled odds and value.',
      beginner: 'StrikeBench uses an eligible Treasury or FRED rate when one answers. If neither does, it uses a clearly labeled 4% teaching assumption instead of pretending that assumption was observed.',
      expert: 'Annualized risk-free input r used in BSM and the risk-neutral drift r−σ²/2. Provenance is separate from quote and chain evidence; the modeled default is 4.0% and is disclosed in every pre-trade verdict.' },
    dte: { short: 'Shorter expirations accelerate risk and leave fewer trading sessions to recover; calendar days alone can mislead.',
      beginner: 'A 30-day choice asks the backtest to find contracts about one month from expiry. Shorter trades move faster and carry more last-minute risk.',
      expert: 'Target calendar DTE used to select the nearest historically listed expiration; near-expiry risk separately uses exchange trading sessions.' },
    marketengine: { short: 'Ready snapshots make screens open quickly; a waiting or stale state tells you the next result may need a refresh.',
      beginner: 'It remembers the latest eligible quote for each ticker and refreshes politely in the background, so opening a screen does not trigger a burst of downloads.',
      expert: 'Separate cached snapshots per market, with one coordinated refresh per symbol, governed source priority, immutable source timestamps and lightweight server-to-screen update hints.' },
    historycoverage: { short: 'A current quote can exist even when daily chart history is unavailable.',
      beginner: 'Quotes and price history can come from different sources. When this market has no eligible daily history for a stock, StrikeBench leaves the chart blank instead of drawing a made-up line.',
      expert: 'Quote/chain and candle provenance are independent. This card has no lane-eligible CandleSeries for the selected range; connect or backfill a candle source in Data to populate it.' },
    bidask: { short: 'The live buying and selling quotes; the gap is an immediate trading cost.',
      beginner: 'The bid is what a buyer will pay and the ask is what a seller wants. You normally sell near the bid and buy near the ask.',
      expert: 'Current two-sided underlying book. Executable option packages use each leg\'s corresponding bid or ask; midpoint marks are display estimates, not guaranteed fills.' },
    dayrange: { short: 'Use today\'s low-to-high span as context for the current move, not as a forecast of where the session will end.',
      beginner: 'This shows how far the stock has already traveled today. A dash means the active source did not provide an honest session range.',
      expert: 'Session low/high from the active market lane. Simulated markets accumulate both from generated ticks; missing observed values remain missing.' },
    atmiv: { short: 'The annualized move currently priced into options nearest the stock price.',
      beginner: 'Higher implied volatility means the options market expects a wider range and options usually cost more.',
      expert: 'At-the-money chain IV at the matched horizon. It is a market-implied pricing input, not a directional forecast.' },
    hv30: { short: 'Compare this realized movement with option-implied volatility; a gap can guide investigation but does not prove an edge.',
      beginner: 'Compare this with implied volatility: IV above recent movement can mean richer option prices, but it does not guarantee a selling edge.',
      expert: 'Annualized close-to-close realized volatility over 30 eligible observations. It is unavailable when lane-eligible daily history is insufficient.' },
    requestbudget: { short: 'When this allowance runs out, StrikeBench pauses provider work instead of retrying until the source blocks you.',
      beginner: 'Some services limit daily calls. StrikeBench remembers each call across restarts and stops at the cap instead of repeatedly retrying or risking a ban.',
      expert: 'A durable provider-wide UTC-day counter consumed before each HTTP request. Zero means the user’s plan governs and StrikeBench does not invent a limit.' },
    adjustedprices: { short: 'Prices restated for splits and distributions so long-term returns remain comparable.',
      beginner: 'A stock split should not look like a 50% crash. Adjusted history rewrites older prices so charts and volatility calculations follow the economic move.',
      expert: 'OHLC is scaled by adjusted-close/raw-close per session. StrikeBench never combines an adjusted close with raw O/H/L.' },
    closeonly: { short: 'Observed closing prices are present, but observed daily highs and lows are not.',
      beginner: 'This is enough for return charts and close-to-close volatility. Any intraday shape built from it must be labeled synthetic.',
      expert: 'CLOSE_ONLY bars preserve observed endpoints. O/H/L substitution is not treated as observed range evidence; the series basis is carried separately.' },
    synccursor: { short: 'A retry starts from this durable checkpoint, so already stored sessions are not downloaded again.',
      beginner: 'StrikeBench remembers what dates each source already supplied, so Retry asks only for missing days.',
      expert: 'Per-owner/source/symbol/domain/interval state with requested range, last successful date, failures, and cumulative rows; durable across restarts.' },
    seed: { short: 'Reuse it to reproduce a review; change it to test whether a conclusion survives another path.',
      beginner: 'Two people using the same seed see the identical market — useful for comparing decisions on the same tape.',
      expert: 'Deterministic random seed for path generation; the same seed, model and settings regenerate the same ticks.' },
    world: { short: 'Which market lane these numbers come from: Observed, Demo, Simulated, or Scenario.',
      beginner: 'Observed uses market-source data. Demo is fixed fabricated teaching data. Simulated is a moving generated market. Scenario is a saved generated path. The banner tells you which one is active, always.',
      expert: 'The lane identity keys every quote/chain/candle cache and stream. Provenance and age stay separate; only Observed inputs are eligible for observed-market analysis and trading.' },
    scenario: { short: 'This sets the market\u2019s directional bias; the seed controls the exact random path you see.',
      beginner: 'A scenario biases WHERE the market drifts (steady climb, sell-off then rebound...) while randomness stays real \u2014 the same scenario plays out differently every session unless you reuse the seed.',
      expert: 'Scenario = a deterministic annualized drift schedule keyed to sim elapsed time; diffusion (factor + idiosyncratic) is unchanged. VOL_EVENT carries its story in the IV level instead.' },
    eventday: { short: 'Set −1 for no volatility event; otherwise the IV shock lands after this simulated trading day closes.',
      beginner: 'Use this to place an earnings-style volatility event on the timeline. The price path keeps moving; only the option-volatility assumption jumps on that day.',
      expert: 'Zero-based simulated trading-day index for the one-off IV shock. −1 disables it; the shock is applied at that day\'s close before subsequent mean reversion.' },
    ivchange: { short: 'This is a one-time relative IV shock, not a stock-price move; −35 means volatility falls by 35%.',
      beginner: 'A negative value models an earnings crush; a positive value models a volatility spike. It changes option values even if the stock price barely moves.',
      expert: 'Multiplicative one-off change to the IV state at event close, followed by the configured deterministic drift and mean reversion. It does not alter spot directly.' },
    beta: { short: 'How hard this symbol leans on the market factor.',
      beginner: 'A beta of 1.5 moves harder with the whole market than a beta of 0.5. It is a real sensitivity: higher beta = genuinely wilder swings, not just more correlation.',
      expert: 'r_i = (\u03bc\u2212\u03c3\u00b2/2)dt + \u03b2\u03c3_m dW_m + \u03c3_idio dW_i; total vol \u221a(\u03b2\u00b2\u03c3_m\u00b2+\u03c3_idio\u00b2). Betas validated to \u00b13.' },
    marketanchor: { short: 'A manual anchor creates a hypothetical starting point for this world; it never rewrites the observed price.',
      beginner: 'Leave it blank to use the current lane\'s last available price and disclose its source. Enter a value only when you deliberately want a hypothetical starting point.',
      expert: 'Blank resolves through the current market engine snapshot with immutable source/as-of provenance. An explicit value is stored as a user-specified anchor; it never overwrites observed data.' },
    speed: { short: 'Playback only: it changes how fast you watch, never what the generated path does.',
      beginner: 'At 26\u00d7, twenty-six simulated seconds pass every real second \u2014 a full 6.5-hour trading session in about 15 minutes. Faster or slower playback never changes WHAT happens \u2014 only how quickly you watch it.',
      expert: 'Speed = sim-seconds per real second (a session holds 23,400). The path lives on fixed 30-sim-second quanta; ticks accumulate fractional quanta and step whole ones. Same seed + same sim-time \u21d2 identical prices at any speed (pinned).' },
    assignment: { short: 'For buy-at-discount or sell-at-target, assignment can be success; elsewhere it may create unwanted shares.',
      beginner: 'For covered calls and cash-secured puts this is often the GOAL (your shares sell / you buy at your price). For other trades it is the chance of ending up with a stock position.',
      expert: 'Expire-in-the-money probability from N(d2)/N(\u2212d2) at per-leg smile IV. Same-expiry short puts/calls are combined as exact disjoint tails; different expirations are conservatively summed and capped at 1.' }
  };

  INFO.plans = {
    short: 'Plans are the home for the workflow formerly spread across Trade and its four-step flow.',
    beginner: 'A Plan keeps one ticker and goal together through six stages: understand it, inspect evidence, choose a strategy, test outcomes, decide, then manage and review.',
    expert: 'Canonical durable aggregate for context revisions, ranked and custom structures, separate outcome lenses, frozen decisions, and management receipts. Formerly the Trade surface.'
  };
  INFO.proposedtrades = {
    short: 'Proposed trades are the ranked packages formerly shown as Ideas; Expert calls the complete comparison the Ranked field.',
    beginner: 'Find an idea screens packages for your goal, account, costs, evidence, and economics. Opening one preserves the exact contracts and starts or reuses a Plan.',
    expert: 'The server-owned candidate field ordered by Decision score within economic verdict bands. Formerly Ideas / Find ideas; it remains the same ranking spine.'
  };
  INFO.scout = {
    short: 'Scout is the capability formerly labeled Scan.',
    beginner: 'Universe Scout finds tickers. Scout inside a Plan compares similar setups, better fits, and offsets without replacing your chosen Plan.',
    expert: 'Universe-level discovery and Plan-relative relationship searches share the existing recommendation evaluation path; Scout does not create a second scorer.'
  };
  INFO.ranktie = {
    short: 'Two ideas are called a close call only when they share an economic verdict and differ by no more than 2 points on the 100-point Decision scale.',
    beginner: 'A tiny score gap is not meaningful proof that one trade will work better. StrikeBench keeps both visible and asks you to compare the risks that matter to you.',
    expert: 'Threshold: same EconomicAssessment verdict band and absolute Decision-score spread <= 2.0. The 2-point tolerance is smaller than one economic band (25 points) and avoids overstating rounded within-band differences.'
  };
  INFO.decisionscore = {
    short: 'A 0–100 ordering aid that combines economics, risk, evidence, costs, and Plan fit; it is not a return forecast.',
    beginner: 'Use this to understand why one package appears above another, then compare the actual worst case and trade-offs. A higher score never promises a profit.',
    expert: 'Monotonic economic-verdict bands ordered internally by the risk-adjusted ScoreComposer result. Mechanical failures score zero; rounded values are ranking aids, not calibrated probabilities.'
  };

  INFO.objectivedirection = {
    short: 'The market view this account expresses — optional, and only meaningful for directional or hedging objectives.',
    beginner: 'If this account exists to bet on a direction, or to protect against one, say which. Ideas are then checked against that view. Income and accumulation accounts can skip this entirely.',
    expert: 'Optional revision field: BULLISH | BEARISH | NEUTRAL | NON_DIRECTIONAL. Maps to the coherence engine’s thesis side; NON_DIRECTIONAL declares that no direction should ever count for or against an idea, which differs from leaving it undeclared.'
  };

  // Program ONE product concepts that are not canonical numeric vocabulary still
  // own a first-class explanation key. Keep these distinct: consumers and the
  // release audit use the keys to prove that materially different contracts are
  // not collapsed into one generic tooltip.
  Object.assign(INFO, {
    participation: {
      label: 'Participation',
      short: 'How strongly the exact package participates in an underlying move, now and across a named price interval.',
      beginner: 'Participation compares the package with owning the equivalent shares. It shows how much of a move you participate in now, then how that participation changes as the stock rises through the interval; caps, reversals, and regime changes stay visible.',
      expert: 'Reports current dollar delta relative to equivalent-share dollar delta plus terminal upside capture over a named rising-price interval and terminal date. Regime points identify prices where the participation interpretation changes; it is not a single strategy-label heuristic.'
    },
    impliedstance: {
      label: 'Implied stance',
      short: 'The plain-language market posture measured from the package’s exposures, not inferred from its catalog name.',
      beginner: 'StrikeBench reads what the exact position actually does: whether it leans up, down, or neutral; likes or dislikes large moves; benefits or suffers from volatility and time; and which tail hurts most. A strategy name never substitutes for those measurements.',
      expert: 'Classification derived from the stance vector: direction BULLISH/BEARISH/NEUTRAL, convexity LONG/SHORT/FLAT, volatility LONG/SHORT/FLAT, carry POSITIVE/NEGATIVE/FLAT, and primary tail. The receipt retains the measured vector and the resulting label/summary.'
    },
    stancevector: {
      label: 'Stance vector',
      short: 'One unit-consistent fingerprint for direction, curvature, volatility, time decay, tail loss, and duration.',
      beginner: 'This is the measured shape behind implied stance. It lets StrikeBench compare a proposed structure, an existing book, and your stated objective using the same ingredients instead of relying on strategy names.',
      expert: 'Fixed-unit vector: dollarDeltaCents; gamma as dollar-delta change per 1% underlying move; vega cents per volatility point; theta cents per day; downside/upside loss at ±1σ and ±2σ; and calendar-day duration. Participation, coherence, and book aggregation consume this shared contract.'
    },
    campaign: {
      label: 'Campaign',
      short: 'The economic story joining every action in one ongoing idea without rewriting broker, accounting, or tax facts.',
      beginner: 'A campaign keeps the shares, options, rolls, assignments, dividends, fees, pending imports, and related Plan together so you can judge the whole journey. It is an organizing and interpretation layer; the original records remain unchanged.',
      expert: 'Typed interpretation aggregate spanning recorded transactions, pending-import packages, Plans, practice activity, and structures. Membership and attribution drive campaign economics while normalized ledger entries, realized matches, and tracked tax lots remain authoritative and immutable.'
    },
    coveragereceipt: {
      label: 'Coverage receipt',
      short: 'An auditable list of which inputs were used, missing, modeled, or limited for this result.',
      beginner: 'Open this receipt to see what evidence StrikeBench had and what it did not. Missing prices, history, volatility, or other inputs stay named, and any model or limitation stays attached to the conclusion.',
      expert: 'DataCoverageReceipt maps each required input to an EvidenceLevel plus detail, records the pricing model, and enumerates limitations. Missing coverage remains explicit and cannot be silently replaced by a lower-provenance lane.'
    },
    fresheyes: {
      label: 'Fresh-eyes review',
      short: 'Would you open the exact remaining package today at current prices if sunk costs did not exist?',
      beginner: 'Fresh-eyes review judges the position you still own as if today were the first decision. It uses today’s executable prices and asks whether that exact risk is attractive now, separately from whether the campaign has made or lost money so far.',
      expert: 'Re-evaluates the as-is package through the existing DecisionPolicy at current executable marks, excluding sunk campaign cash from the forward choice. Its receipt remains separate from the campaign-anchored economic review so neither frame overwrites the other.'
    },
    adoption: {
      label: 'Adoption',
      short: 'Bring an existing tracked position into a Plan without pretending a new trade or market event occurred.',
      beginner: 'Adoption starts managing shares or options you already hold. StrikeBench records the exact position and current baseline, then opens the Plan at Manage & Review; it does not invent an opening order or change the broker record.',
      expert: 'An ADOPTION receipt freezes the as-is lots, quantities, eligible marks, and baseline provenance, then initializes the Plan mid-journey at Manage & Review. It creates neither execution cash flow nor a claim that StrikeBench originated the position.'
    },
    waypoint: {
      label: 'Waypoint',
      short: 'A user-authored price pin on a future trading session inside a scenario, not a forecast.',
      beginner: 'A waypoint says, “for this what-if, make the stock pass through this price on this future session.” It is an explicit scenario assumption, so every chart and outcome that uses it keeps the pin visible.',
      expert: 'Scenario-spec constraint with an ordered in-horizon trading-day index and price ratio, preserved with its tolerance and authorship context. Generators must honor the pin; the fill method determines whether the surrounding path has an exact conditional law or guided approximation.'
    },
    bridgefill: {
      label: 'Bridge fill',
      short: 'An exact conditional path fill between waypoints for models with a compatible Gaussian Brownian motion.',
      beginner: 'For a smooth Gaussian model, StrikeBench samples the movement between pins from the model’s real conditional distribution. The path is still random, but it is random given that it must pass through the waypoints.',
      expert: 'EXACT_CONDITIONAL fill uses segmented Brownian-bridge sampling under the Gaussian GBM-compatible process, conditioning each segment on its pinned endpoints. It preserves the model’s conditional law; this claim does not extend to fat-tail, jump, stochastic-volatility, or bootstrap generators.'
    },
    guidedinterpolation: {
      label: 'Guided interpolation',
      short: 'An approximate path correction that honors waypoints when an exact conditional bridge is unavailable.',
      beginner: 'For jump, fat-tail, stochastic-volatility, or historical-block models, StrikeBench smoothly guides sampled paths through your pins. The pins are exact scenario instructions, but the odds between them are approximate and should not be read as the model’s true conditional probability.',
      expert: 'GUIDED_INTERPOLATION applies a smooth multiplicative/log-path correction to Student-t, jump-diffusion, Heston, and block-bootstrap paths. Waypoints are honored, but the transformed ensemble is not sampled from the generator’s conditional law; probabilities retain a guide/approximation limitation.'
    },
    headlinesentiment: {
      label: 'Keyword-derived headline sentiment',
      short: 'A deterministic keyword classification of observed headline text, with its scorer version attached.',
      beginner: 'StrikeBench marks a headline positive, negative, mixed, or neutral by showing which configured words matched. It does not understand the full article or context, so open the source before treating the label as evidence. Demo headlines never become sentiment evidence.',
      expert: 'NewsSentimentScorer applies the versioned sentiment-keyword-v1 positive/negative stem sets to headline text, reports matched terms and per-headline basis, then aggregates (positive − negative) / directional headlines with coverage and event flags. The scorerVersion is receipt identity; unavailable or Demo inputs remain UNAVAILABLE rather than neutral.'
    }
  });

  Object.keys(VOCABULARY).forEach(function (key) {
    var item = VOCABULARY[key];
    INFO[item.infoKey] = { short: item.short, beginner: item.beginner, expert: item.expert };
  });

  var GLOSSARY = {
    'premium': 'The price of an option, per share. One contract covers 100 shares, so a $1.50 premium costs $150.',
    'strike': 'The price at which an option lets you buy (call) or sell (put) the stock.',
    'expiration': 'The date the option stops existing. US options die at 4:00pm ET that day.',
    'call': 'An option that gains value when the stock rises above its strike.',
    'put': 'An option that gains value when the stock falls below its strike.',
    'breakeven': 'The stock price at expiration where the trade neither makes nor loses money (before fees).',
    'max loss': 'The theoretical structural limit: the most this position can lose across the full payoff curve. Realistic-scenario distributions show what plausible paths tend to do without replacing this boundary.',
    'max profit': 'The theoretical structural ceiling. “Uncapped” means the payoff has no fixed ceiling, not that an extreme outcome is likely; scenario distributions show plausible ranges.',
    'pop': 'Probability of profit: a model’s estimate of the chance this trade ends with ANY profit at expiration. A guess with math, not a promise.',
    'credit': 'Money you collect up front when opening (you sold more premium than you bought). You keep it if the trade works out.',
    'debit': 'Money you pay up front when opening. It is also the most you can lose on defined-risk debit trades.',
    'spread (strategy)': 'Buying one option and selling another to cap both cost and risk.',
    'bid/ask': 'The bid is what buyers offer; the ask is what sellers want. You buy at the ask and sell at the bid — the gap is a real cost.',
    'iv': 'Implied volatility: how big a move the options market is pricing in. High IV = expensive options.',
    'hv': 'Historical (realized) volatility: how much the stock actually moved recently. Comparing IV to HV shows if options look rich or cheap.',
    'delta': 'Roughly, how much the option price moves when the stock moves $1 — and a rough market estimate of the odds it finishes in the money.',
    'theta': 'Time decay: how much value an option loses per day, all else equal.',
    'assignment': 'Being required to fulfil a short option: selling shares at the strike (short call) or buying them (short put). When you WANT to buy or sell at that price, assignment is the goal, not a risk.',
    'cost basis': 'What you paid per share on average. Gains and losses on shares are measured against it.',
    'annualized yield': 'The option premium left after opening fees, divided by the shares or full strike cash backing the trade, scaled to a full year.',
    'effective price': 'The price per share if assigned after opening fees: strike plus net premium for a covered call, or strike minus net premium for a cash-secured put.',
    'covered': 'A short call is covered when you own 100 shares per contract — worst case you deliver shares you already have, so no unlimited risk.',
    'open interest': 'How many contracts exist at that strike. Low numbers mean it may be hard to exit at a fair price.',
    '0dte': 'Zero days to expiration: an option expiring TODAY. Values can go to zero in hours — expert territory.',
    'buying power': 'Cash minus what is reserved to cover your open trades’ worst cases.',
    'reserve': 'Money set aside so your account can always survive a trade’s worst case.'
  };

  /**
   * Per-strategy education. Every recommendable family gets:
   *  story    — one-sentence plain-English framing (an analogy where it helps)
   *  how      — 2-4 mechanical steps in plain words
   *  win      — when it makes money
   *  lose     — when and how it loses
   *  watch    — the thing beginners miss
   */
  var STRATEGY_GUIDE = {
    LONG_CALL: {
      story: 'A paid bet that the stock climbs — like a refundable deposit on buying it cheaper than the future price.',
      how: ['Pay a premium for the right to buy 100 shares at the strike until expiration.',
            'If the stock rises past strike + premium, you profit on the difference.',
            'If it does not, the option simply expires and you lose only what you paid.'],
      win: 'The stock rises well above the strike before time runs out.',
      lose: 'The stock stays flat or falls: the premium melts away day by day (theta) and can go to zero.',
      watch: 'Being right about direction is not enough — you also need the move to happen before expiration.'
    },
    LONG_PUT: {
      story: 'Insurance that pays if the stock falls — you can profit from a decline without ever owning shares.',
      how: ['Pay a premium for the right to sell 100 shares at the strike.',
            'The further the stock falls below the strike, the more the put is worth.',
            'Worst case: the stock holds up and you lose the premium.'],
      win: 'The stock drops meaningfully below the strike before expiration.',
      lose: 'The stock rises or drifts sideways; the premium decays to nothing.',
      watch: 'Puts get expensive when everyone is scared — check IV before buying protection.'
    },
    DEBIT_CALL_SPREAD: {
      story: 'A discounted bullish bet: you give up the moonshot to pay less and risk less.',
      how: ['Buy a call near the money.', 'Sell a higher-strike call to collect some premium back.',
            'The sold call caps your profit but cuts your cost — often by a third or more.'],
      win: 'The stock finishes at or above the higher strike: you collect the full spread width minus what you paid.',
      lose: 'The stock stays below the lower strike: you lose the (reduced) amount you paid, never more.',
      watch: 'Profit is capped — a huge rally pays the same as a modest one.'
    },
    DEBIT_PUT_SPREAD: {
      story: 'A discounted bearish bet: cheaper than a lone put, with a known ceiling and floor.',
      how: ['Buy a put near the money.', 'Sell a lower-strike put to offset part of the cost.'],
      win: 'The stock finishes at or below the lower strike.',
      lose: 'The stock holds above the upper strike; you lose only the net premium paid.',
      watch: 'Same trade-off as the call version: capped payoff in exchange for a smaller, defined risk.'
    },
    CREDIT_CALL_SPREAD: {
      story: 'You get paid to bet the stock will NOT climb past a level — selling a promise, with insurance above it.',
      how: ['Sell a call above the current price and collect premium.',
            'Buy an even higher call as insurance so your risk is capped.',
            'If the stock stays below your short strike, both expire and you keep the credit.'],
      win: 'The stock stays below the short strike — you keep the credit without needing the stock to move at all.',
      lose: 'The stock rallies through both strikes: you lose the spread width minus the credit — capped, but usually larger than the credit.',
      watch: 'You typically risk more than you can make; the edge is that time and "no move" work for you.'
    },
    CREDIT_PUT_SPREAD: {
      story: 'You get paid to bet the stock will NOT fall past a level — like selling insurance with your own re-insurance below.',
      how: ['Sell a put below the current price and collect premium.',
            'Buy a lower put so a crash cannot hurt you beyond a known amount.'],
      win: 'The stock stays above the short strike; both puts expire worthless and the credit is yours.',
      lose: 'The stock drops through both strikes: you pay the width minus the credit.',
      watch: 'Feels like free money in calm markets — the loss, when it comes, is several times the typical win.'
    },
    IRON_CONDOR: {
      story: 'Two insurance sales at once: you profit if the stock stays inside a range — boring is beautiful.',
      how: ['Sell a put spread below the market and a call spread above it.',
            'Collect both credits; risk is capped by the bought wings on each side.'],
      win: 'The stock expires between the two short strikes: keep the entire credit.',
      lose: 'A big move in EITHER direction pushes through one side; loss is capped at one wing width minus the credit.',
      watch: 'Only one side can lose, but a violent move can blow through it fast — earnings and news are the enemy.'
    },
    IRON_BUTTERFLY: {
      story: 'An iron condor squeezed to a point: bigger credit for betting the stock pins near today’s price.',
      how: ['Sell a call and a put at the money.', 'Buy protective wings on both sides.'],
      win: 'The stock closes very near the middle strike.',
      lose: 'Any decent move toward a wing; capped by the wings.',
      watch: 'The sweet spot is narrow — this is a precision bet, not a "roughly sideways" bet.'
    },
    LONG_CALL_BUTTERFLY: {
      story: 'A cheap lottery ticket on the stock landing NEAR a specific price — small cost, capped prize.',
      how: ['Buy one call below the target, sell two at the target, buy one above.',
            'The structure costs little because the sold middle pair finances the wings.'],
      win: 'The stock expires close to the middle strike — payoff peaks exactly there.',
      lose: 'The stock finishes far from the target on either side; you lose the small debit.',
      watch: 'Maximum payoff needs an almost-exact landing; most of the time you lose the small debit.'
    },
    LONG_PUT_BUTTERFLY: {
      story: 'The same pin-the-price bet as a call butterfly, built with puts.',
      how: ['Buy one put above the target, sell two at the target, buy one below.'],
      win: 'The stock expires near the middle strike.',
      lose: 'A finish far from the target costs you the small debit.',
      watch: 'Same as the call fly: precise target, small stake, rare max payoff.'
    },
    CALENDAR_CALL: {
      story: 'Sell fast-melting time, own slow-melting time: profit if the stock marks time while the near option decays.',
      how: ['Sell a near-expiration call at a strike.', 'Buy a later-expiration call at the same strike.',
            'The near option loses value faster — that difference is your edge.'],
      win: 'The stock hovers near the strike through the near expiration.',
      lose: 'A big move in either direction, or a volatility drop, hurts; risk is capped at the debit paid.',
      watch: 'Its profit depends on future volatility — that is why we do not print a max profit or POP for it.'
    },
    CALENDAR_PUT: {
      story: 'The put version of selling fast time and owning slow time.',
      how: ['Sell a near-dated put, buy a longer-dated put at the same strike.'],
      win: 'The stock sits near the strike as the near put decays.',
      lose: 'Sharp moves either way; capped at the debit.',
      watch: 'Model-dependent payoff — treat projections with extra skepticism.'
    },
    DIAGONAL_CALL: {
      story: 'A calendar with a lean: own a longer, lower call; rent out a nearer, higher one.',
      how: ['Buy a longer-dated call at a lower strike (your engine).',
            'Sell a nearer-dated call at a higher strike (your income).'],
      win: 'A gentle drift up: the short call expires, the long call gains.',
      lose: 'A crash (long call loses) — or a moonshot where the short cap bites early; risk capped at the debit.',
      watch: 'Two expirations means two clocks; plan what you will do when the short leg dies.'
    },
    DIAGONAL_PUT: {
      story: 'The bearish mirror: own a longer put, sell a nearer one against it.',
      how: ['Buy a longer-dated put at a higher strike.', 'Sell a nearer-dated put at a lower strike.'],
      win: 'A gentle drift down through the near expiration.',
      lose: 'A rally; capped at the net debit.',
      watch: 'Same two-clock complexity as the call diagonal.'
    },
    LONG_STRADDLE: {
      story: 'A bet on turbulence itself: you own both directions, so a big move either way pays — a quiet market is the enemy.',
      how: ['Buy the at-the-money call AND the at-the-money put, same strike, same expiration.',
            'A large move in either direction makes one side worth more than both cost.',
            'Worst case: the stock pins the strike and both premiums decay to nothing.'],
      win: 'The stock moves far from the strike — in either direction — before expiration.',
      lose: 'The stock sits still: both options bleed value every day (double theta).',
      watch: 'Straddles are priciest right before known events (earnings) — the move you expect may already be paid for.'
    },
    LONG_STRANGLE: {
      story: 'The straddle\u2019s cheaper cousin: both directions again, but with out-of-the-money strikes — less cost, needs a bigger move.',
      how: ['Buy an out-of-the-money call and an out-of-the-money put, same expiration.',
            'Costs less than a straddle because both options start worthless.',
            'The stock must clear one of the strikes by more than the total premium to profit.'],
      win: 'A violent move beyond either strike before time runs out.',
      lose: 'The stock stays between the strikes: both premiums expire worthless.',
      watch: 'The wider the strikes, the cheaper the ticket — and the rarer the move that pays it off.'
    },
    COVERED_CALL: {
      story: 'Own the shares, rent out the upside: steady income in exchange for a capped rally.',
      how: ['Own (or buy) 100 shares.', 'Sell one call above the current price and pocket the premium.',
            'Repeat as calls expire — this is the classic income strategy.'],
      win: 'The stock drifts up, sideways, or slightly down: the premium is yours either way.',
      lose: 'A big crash: the premium barely dents the share losses. The other "loss" is regret — a huge rally is sold away at the strike.',
      watch: 'This is stock risk with a small cushion, not a low-risk trade. The floor is the stock going to zero.'
    },
    CASH_SECURED_PUT: {
      story: 'Get paid to promise you’ll buy the stock cheaper: a standing limit order that pays you premium.',
      how: ['Set aside the cash to buy 100 shares at the strike.', 'Sell a put at that strike and collect premium.',
            'If assigned, you buy shares at an effective discount; if not, you keep the premium.'],
      win: 'The stock stays above the strike (keep premium) — or dips just below it (buy shares you wanted, cheaper).',
      lose: 'A collapse far below the strike: you must still buy at the strike; the premium is small comfort.',
      watch: 'Only sell puts on stocks you would genuinely be happy to own at that price.'
    },
    PROTECTIVE_COLLAR: {
      story: 'A seatbelt for shares you own: a put as the floor, paid for by selling away some ceiling.',
      how: ['Own 100 shares.', 'Buy a put below the price (your floor).', 'Sell a call above the price (pays for the put).'],
      win: 'You sleep well: losses stop at the put strike; often costs little or nothing net.',
      lose: '"Loses" mostly by capping a rally at the call strike; small net cost possible.',
      watch: 'The tighter the floor, the lower the ceiling — safety is paid for with upside.'
    },
    PROTECTIVE_PUT: {
      story: 'Insurance for shares you own: pay a premium, get a guaranteed minimum sale price until expiration.',
      how: ['Own 100 shares.', 'Buy a put at (or a bit below) the price you refuse to fall under.', 'Sleep; renew or drop it at expiration.'],
      win: 'The stock falls hard and your loss stops at the put strike — the insurance paid out.',
      lose: 'The stock does fine and the premium is gone, like unused car insurance.',
      watch: 'Insurance re-bought every month adds up — hedge what you actually fear, not everything always.'
    },
    RISK_REVERSAL: {
      story: 'Use a sold put to help pay for a bullish call: little cash up front, but a real obligation to buy after a fall.',
      how: ['Buy a call for upside.', 'Sell a put at a lower strike to finance it.', 'Reserve enough capital for the short put obligation.'],
      win: 'The stock rises beyond the call strike.',
      lose: 'The stock falls below the put strike and the short put behaves like a leveraged share purchase.',
      watch: 'Low entry cost is not low risk. The short put creates substantial downside and capital requirements.'
    },
    CALL_BACKSPREAD: {
      story: 'Sell one call and own two higher calls: a violent rally can pay, but a modest rise can land in the valley between them.',
      how: ['Sell one nearer call.', 'Buy two farther calls in the same expiration.', 'Compare the middle loss pocket with the open-ended rally payoff.'],
      win: 'The stock rallies far beyond the long calls.',
      lose: 'The stock rises only into the gap between the short and long strikes.',
      watch: 'This is not simply bullish; the worst region can be a moderate move in the expected direction.'
    },
    PUT_BACKSPREAD: {
      story: 'Sell one put and own two lower puts: built for a crash, with a loss pocket on a merely modest decline.',
      how: ['Sell one nearer put.', 'Buy two lower puts in the same expiration.', 'Inspect the middle loss pocket before focusing on the crash payoff.'],
      win: 'The stock falls far beyond the long puts.',
      lose: 'The stock falls only into the gap between the strikes.',
      watch: 'A small bearish move can be worse than no move or a very large move.'
    },
    SYNTHETIC_LONG: {
      story: 'A long call plus a short put at one strike behaves much like 100 shares, without buying the shares outright.',
      how: ['Buy a call.', 'Sell a put with the same strike and expiration.', 'Keep capital available for the put assignment obligation.'],
      win: 'The stock rises, much like a share position.',
      lose: 'The stock falls; downside resembles owning shares from the strike.',
      watch: 'Small option premium does not mean small exposure. This is stock-like risk with option assignment mechanics.'
    },
    SYNTHETIC_SHORT: {
      story: 'A long put plus an uncovered short call behaves like short stock and can lose without limit in a rally.',
      how: ['Buy a put.', 'Sell a call with the same strike and expiration.', 'Observe that the call has no protective ceiling.'],
      win: 'The stock falls, much like a short-share position.',
      lose: 'The stock rallies; the uncovered short call can keep losing as price rises.',
      watch: 'StrikeBench shows this to teach the shape and refuses it because theoretical loss is unlimited.'
    },
    SHORT_STRADDLE: {
      story: 'Sell an at-the-money call and put for a large credit, accepting uncovered risk on both sides.',
      how: ['Sell the call and put at the same strike.', 'Collect both premiums.', 'Notice there is no protective wing above or below.'],
      win: 'The stock finishes close to the shared strike.',
      lose: 'A large move either way; the upside loss is unlimited.',
      watch: 'The rich credit is payment for tail risk, not free income. StrikeBench refuses the uncovered structure.'
    },
    SHORT_STRANGLE: {
      story: 'Sell an out-of-the-money call and put for a wider profit range, still with uncovered tail risk.',
      how: ['Sell a call above the market.', 'Sell a put below the market.', 'Notice that neither side has a protective wing.'],
      win: 'The stock stays between both short strikes.',
      lose: 'A large move through either strike; the call side remains unlimited.',
      watch: 'Wider breakevens reduce frequency of loss, not the severity of an uncovered tail.'
    },
    NAKED_CALL: {
      story: 'Sell a call without shares or a higher call behind it: collect a small premium while accepting unlimited rally risk.',
      how: ['Sell one call.', 'Observe that no stock or long call caps delivery cost.', 'Compare the fixed credit with an unbounded loss line.'],
      win: 'The stock stays below the strike.',
      lose: 'The stock rallies; every additional dollar adds roughly $100 of loss per contract.',
      watch: 'StrikeBench keeps the lesson visible but refuses the trade because no finite worst case exists.'
    },
    NAKED_PUT: {
      story: 'Sell a put without reserving the cash to buy shares: premium now, an unfunded purchase obligation after a fall.',
      how: ['Sell one put.', 'Calculate strike × 100 cash needed if assigned.', 'Compare that obligation with the account’s available capital.'],
      win: 'The stock stays above the strike.',
      lose: 'The stock falls and you owe the difference down toward zero.',
      watch: 'A cash-secured put has the same option shape but reserves the full purchase cash; the unsecured version is refused.'
    }
  };

  /** Why-are-you-trading choices, in display order. Keys match StrategyIntent names.
   *  `icon` is a UI.icon() name from the shared SVG set - never an emoji. */
  var INTENTS = [
    { key: 'DIRECTIONAL', label: 'Trade a view', icon: 'compass',
      blurb: 'I expect a move (up, down, calm, or wild) and want defined-risk exposure to it.' },
    { key: 'INCOME', label: 'Earn income', icon: 'coins',
      blurb: 'Collect option premium against cash or shares I hold. Assignment is the trade-off.' },
    { key: 'ACQUIRE', label: 'Buy at a discount', icon: 'tag',
      blurb: 'Get paid to wait for my price: sell a put at the level I would happily buy.' },
    { key: 'EXIT', label: 'Sell at a target', icon: 'flag',
      blurb: 'I hold shares that have run up — get paid to be patient about selling them at my price.' },
    { key: 'HEDGE', label: 'Protect my shares', icon: 'shield',
      blurb: 'Cap the downside of shares I hold, for a premium or by giving up some upside.' }
  ];

  // Level helpers -----------------------------------------------------------
  function currentLevel() {
    try {
      var v = window.localStorage.getItem('strikebench.level');
      return LEVELS.indexOf(v) >= 0 ? v : 'beginner';
    } catch (e) { return 'beginner'; }
  }

  function setLevel(level) {
    if (LEVELS.indexOf(level) < 0) return;
    try { window.localStorage.setItem('strikebench.level', level); } catch (e) { /* ignore */ }
    applyLevel(level);
  }

  function applyLevel(level) {
    LEVELS.forEach(function (l) { document.body.classList.toggle('lvl-' + l, l === level); });
    document.querySelectorAll('#level-switch button').forEach(function (b) {
      var active = b.getAttribute('data-level') === level;
      b.classList.toggle('active', active);
      b.setAttribute('aria-pressed', String(active));
    });
  }

  function isAtLeast(level) {
    return LEVELS.indexOf(currentLevel()) >= LEVELS.indexOf(level);
  }

  window.Learn = {
    INFO: INFO,
    LEVELS: LEVELS,
    LEVEL_META: LEVEL_META,
    VOCABULARY: VOCABULARY,
    MOVED_TERMS: MOVED_TERMS,
    INTENTS: INTENTS,
    GLOSSARY: GLOSSARY,
    STRATEGY_GUIDE: STRATEGY_GUIDE,
    currentLevel: currentLevel,
    setLevel: setLevel,
    applyLevel: applyLevel,
    isAtLeast: isAtLeast
  };
})();
