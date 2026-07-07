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
