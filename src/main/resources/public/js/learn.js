/* StrikeBench learning layer: experience ladder, glossary, per-strategy education.
 * Pure content + level helpers, attached to window (no ESM). */
(function () {
  'use strict';

  // One-time migration of pre-rebrand storage keys (optionslab.* -> strikebench.*) so a
  // returning user keeps their level, risk mode, and theme. Safe to run every load.
  try {
    ['level', 'riskMode', 'theme'].forEach(function (k) {
      var oldKey = 'optionslab.' + k, newKey = 'strikebench.' + k;
      var legacy = window.localStorage.getItem(oldKey);
      if (legacy !== null && window.localStorage.getItem(newKey) === null) {
        window.localStorage.setItem(newKey, legacy);
      }
      if (legacy !== null) window.localStorage.removeItem(oldKey);
    });
    // 2026-07: the three-tier ladder collapsed to two. Returning users map onto the new
    // names; 'confident' lands on beginner (education-first default — expert is one click).
    var LEVEL_RENAME = { learning: 'beginner', confident: 'beginner', pro: 'expert' };
    var storedLevel = window.localStorage.getItem('strikebench.level');
    if (LEVEL_RENAME[storedLevel]) window.localStorage.setItem('strikebench.level', LEVEL_RENAME[storedLevel]);
  } catch (e) { /* storage unavailable — defaults apply */ }

  // ---- The experience ladder: exactly TWO shapes, never a middle tier ----
  var LEVELS = ['beginner', 'expert'];
  var LEVEL_META = {
    beginner: { label: 'Beginner', hint: 'Question-driven flows, plain language, extra safety context' },
    expert: { label: 'Expert', hint: 'Dense, data-first screens with deeper analytics' }
  };

  // ---- Glossary: tap-to-define for terms of art ----

  /**
   * THE definitions catalog (Block E): every technical label's explanation lives HERE, once —
   * screens reference keys, so a term can never mean different things on different pages.
   * short = the one-liner every bubble opens with; beginner/expert = the expanded detail.
   */
  var INFO = {
    pop: { short: 'The modeled chance this position ends with ANY profit at expiration.',
      beginner: 'Out of 100 futures the options market itself prices, roughly this many end with you making something — even $1. It is a model number, before commissions, not a promise.',
      expert: 'Risk-neutral P(profit) under a lognormal terminal distribution at the chain\u2019s own IV, calendar-time de-annualization, pre-commission, zero drift. Not a physical-measure forecast.' },
    ev: { short: 'What this trade earns ON AVERAGE across the market\u2019s own priced futures.',
      beginner: 'Imagine running this exact trade in every future the market thinks is possible, and averaging the results. Negative means the market charges more than the position is worth by its own odds.',
      expert: 'Risk-neutral expectation of the expiration payoff at chain IV, zero drift, integrated numerically over the terminal distribution. The Decision score judges it NET of round-trip commissions.' },
    evhistvol: { short: 'The same average, but at the stock\u2019s RECENT realized volatility instead of the option-implied one.',
      beginner: 'If the stock keeps moving the way it recently has (rather than the bigger move options are pricing), this is the average outcome. The gap between the two numbers is what option sellers get paid for.',
      expert: 'Historical-vol SCENARIO EV: the identical integral at 30-day realized \u03c3, zero drift. NOT the physical measure (no drift estimate); the spread vs risk-neutral EV visualizes the volatility risk premium.' },
    cvar: { short: 'The average result of your worst 1-in-20 outcomes — the \u201cbad night\u201d number.',
      beginner: 'Ignore the typical case: if you only look at the worst 5% of ways this can go, this is what an average one of THOSE costs you.',
      expert: 'CVaR(95%): E[P&L | P&L \u2264 5th percentile] under the risk-neutral terminal distribution. Bounded by max loss for defined-risk structures; for undefined risk see the stress figure instead.' },
    pmaxloss: { short: 'The chance this position ends at its FULL worst case.',
      beginner: 'Not just \u201closing something\u201d — this is the chance you lose the entire stated maximum. If it is the biggest number on the card, the most likely single outcome is the worst one.',
      expert: 'Probability mass on the max-loss plateau (within 0.5% of the extreme) under the risk-neutral terminal distribution. Undefined-risk structures report 0 here — no plateau exists; see stress/CVaR.' },
    pmaxprofit: { short: 'The chance you keep the FULL maximum profit.',
      beginner: 'The best case, exactly: for a credit trade this is the chance every short option expires worthless and you keep it all.',
      expert: 'Probability mass on the max-profit plateau. Uncapped structures report 0 by construction.' },
    touch: { short: 'The chance the stock TOUCHES this strike before expiration, not just finishes there.',
      beginner: 'Prices wander: even a trade that ends fine often visits the scary strike first. Touching usually forces a decision.',
      expert: '\u2248 2\u00d7 the expire-beyond probability (reflection heuristic), capped at 1. A rough planning number, not a barrier-exact price.' },
    concession: { short: 'Dollars surrendered to the bid/ask spread just to ENTER.',
      beginner: 'The books have a gap between buyers and sellers; crossing it costs real money before the trade even starts — and you pay again on the way out.',
      expert: 'Package midpoint minus your (proposed or executable) net, in $ and as % of mid / max profit / max risk. Exit estimate = half the summed package spread again.' },
    decisionscore: { short: 'The risk-adjusted composite that ORDERS every list in the product.',
      beginner: 'One number combining the odds, the payoff, the costs, the evidence quality and the tail risk. Higher is better; a zero means a hard gate failed.',
      expert: 'Gates (finite risk, executable market, evidence, buying power) \u2192 weighted components (POP .15, R:R .15, EV-net-of-fees .20, liquidity .10, capital .10, evidence .15, thesis .15) \u2192 haircuts (evidence \u00d7 tail \u00d7 gamma/DTE). Never travels without its breakdown.' },
    screenscore: { short: 'The quick idea-screen ranking — a component, not the judge.',
      beginner: 'A fast first-pass score used while scanning. The Decision score re-judges with costs, evidence and tail risk — when both are shown, the Decision score ordered the list.',
      expert: 'Engine composite (freshness/liquidity/R:R/POP/thesis-fit weights) computed per candidate at screen time; disclosed alongside the DecisionPolicy score that actually ranks.' },
    evidence: { short: 'How REAL the data behind this number is.',
      beginner: 'Observed = a real market quote. Modeled = a formula\u2019s output. Demo = built-in practice data. Weakest link wins: one demo input marks the whole answer.',
      expert: 'Per-dimension provenance (quotes/IV/greeks/history) rolled up worst-of. DEMO_FIXTURE never masquerades; UNKNOWN gates the decision score to non-viable.' },
    ivrank: { short: 'Where today\u2019s option prices sit vs their own past year.',
      beginner: 'High rank = options are expensive by this stock\u2019s own standards (good for sellers); low = cheap (good for buyers).',
      expert: '(IV \u2212 52w min)/(52w max \u2212 min) over OUR observed snapshot history; null until \u226510 observations — never fabricated.' },
    vrp: { short: 'The gap between what options charge and how much the stock actually moves.',
      beginner: 'Options usually price MORE movement than happens — that overcharge is what disciplined sellers harvest, and it is also what buyers pay for protection.',
      expert: 'Implied vs realized vol (30d). Positive VRP favors short-premium IF realized stays put; the evHistVol lane shows the same trade under realized \u03c3.' },
    nlv: { short: 'Net liquidation value — what your whole account is worth if closed right now.',
      beginner: 'Cash plus everything you hold, marked at current prices. The truest \u201chow much money do I have\u201d number.',
      expert: 'Self-declared here (never inferred from one broker field); used as a sizing denominator for account-fit percentages.' },
    riskcapital: { short: 'YOUR line: the most you allow one trade to lose.',
      beginner: 'You set it once for your real account; every review then shows the worst case against it and makes you acknowledge crossing it.',
      expert: 'Caps the engine\u2019s per-trade budget when declared and adds a required server-side acknowledgment above it.' },
    expectedmove: { short: 'The range the options market expects the stock to stay inside (\u00b11\u03c3).',
      beginner: 'About 2 out of 3 futures end inside this band. Selling strikes INSIDE it means betting the market has overpriced its own move.',
      expert: 'spot \u00b7 exp(\u00b1\u03c3\u221aT) at the position\u2019s vol/time basis; drawn on the payoff chart and included in the domain window.' },
    sessions: { short: 'Trading days left — weekends and holidays don\u2019t move prices the same way.',
      beginner: 'A Friday-to-Monday trade has ONE day of real trading, not three. Less time to be right, and a weekend gap you cannot react to.',
      expert: 'NYSE-calendar sessions to nearest expiry; drives the near-expiry regime (\u22645 sessions) and plans. Model T stays calendar-basis to match chain-IV annualization.' },
    seed: { short: 'The number that makes a simulated market exactly reproducible.',
      beginner: 'Two people using the same seed see the identical market — useful for comparing decisions on the same tape.',
      expert: 'Deterministic RNG seed for path generation; identical (seed, model, config) regenerates identical ticks.' },
    world: { short: 'Which market these numbers come from: the REAL one, or a simulated session.',
      beginner: 'In a simulated market everything moves and trades like the real thing, but every price is generated. The banner tells you which world you are in, always.',
      expert: 'worldId keys every quote/chain/candle cache and stream; observed is the default and the fail-safe; simulation is a separate account lane.' },
    scenario: { short: 'The story shape the simulated market follows: drift, not destiny.',
      beginner: 'A scenario biases WHERE the market drifts (steady climb, sell-off then rebound...) while randomness stays real \u2014 the same scenario plays out differently every session unless you reuse the seed.',
      expert: 'Scenario = a deterministic annualized drift schedule keyed to sim elapsed time; diffusion (factor + idiosyncratic) is unchanged. VOL_EVENT carries its story in the IV level instead.' },
    beta: { short: 'How hard this symbol leans on the market factor.',
      beginner: 'A beta of 1.5 moves harder with the whole market than a beta of 0.5. It is a real sensitivity: higher beta = genuinely wilder swings, not just more correlation.',
      expert: 'r_i = (\u03bc\u2212\u03c3\u00b2/2)dt + \u03b2\u03c3_m dW_m + \u03c3_idio dW_i; total vol \u221a(\u03b2\u00b2\u03c3_m\u00b2+\u03c3_idio\u00b2). Betas validated to \u00b13.' },
    speed: { short: 'How fast simulated time passes \u2014 without changing the path.',
      beginner: 'At 10\u00d7, ten simulated minutes pass every real minute. Faster or slower playback never changes WHAT happens \u2014 only how quickly you watch it.',
      expert: 'The path lives on fixed 30-sim-second quanta; speed = quanta per real tick. Same seed + same sim-time \u21d2 identical prices at any speed (pinned).' },
    assignment: { short: 'The chance a short option finishes in-the-money and you are assigned.',
      beginner: 'For covered calls and cash-secured puts this is often the GOAL (your shares sell / you buy at your price). For other trades it is the chance of ending up with a stock position.',
      expert: 'N(d2)/N(\u2212d2) per distinct short strike, summed and capped at 1, at per-leg smile IVs.' }
  };

  var GLOSSARY = {
    'premium': 'The price of an option, per share. One contract covers 100 shares, so a $1.50 premium costs $150.',
    'strike': 'The price at which an option lets you buy (call) or sell (put) the stock.',
    'expiration': 'The date the option stops existing. US options die at 4:00pm ET that day.',
    'call': 'An option that gains value when the stock rises above its strike.',
    'put': 'An option that gains value when the stock falls below its strike.',
    'breakeven': 'The stock price at expiration where the trade neither makes nor loses money (before fees).',
    'max loss': 'The most this position can lose, no matter what the stock does. We only suggest trades where this is known up front.',
    'max profit': 'The most this position can make. "Uncapped" means there is no ceiling — but no guarantee either.',
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
    'annualized yield': 'The premium collected, divided by the capital backing the trade, scaled to a full year. Lets a 30-day covered call be compared with a savings rate.',
    'effective price': 'The real price per share if assigned: strike plus premium for a covered call (you sell higher than the strike suggests), strike minus premium for a cash-secured put (you buy lower).',
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
      b.classList.toggle('active', b.getAttribute('data-level') === level);
    });
  }

  function isAtLeast(level) {
    return LEVELS.indexOf(currentLevel()) >= LEVELS.indexOf(level);
  }

  window.Learn = {
    INFO: INFO,
    LEVELS: LEVELS,
    LEVEL_META: LEVEL_META,
    INTENTS: INTENTS,
    GLOSSARY: GLOSSARY,
    STRATEGY_GUIDE: STRATEGY_GUIDE,
    currentLevel: currentLevel,
    setLevel: setLevel,
    applyLevel: applyLevel,
    isAtLeast: isAtLeast
  };
})();
