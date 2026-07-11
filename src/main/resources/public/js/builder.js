/*
 * StrikeBench strategy builder — two genuinely different tools, one honest engine:
 *   BEGINNER: a question-driven wizard (goal -> shape -> leg-BY-leg walkthrough). Every
 *   leg arrives with a plain-language story and its measured impact on cost, worst case,
 *   and odds — the payoff chart morphs as the position grows. A browsable catalog of every
 *   structure (with payoff-shape sketches and guides) is one tap away.
 *   EXPERT: a naked terminal — structure quick-pick, editable leg grid with inline market
 *   data, per-leg include/exclude toggles for instant impact analysis, net greeks, and a
 *   sticky analytics panel.
 * Lives on the Trade screen (#/ticket/builder). Hands off to the standard ticket review.
 */
(function () {
  'use strict';

  var el = UI.el, fmtMoney = UI.fmtMoney, fmtPct = UI.fmtPct, explain = UI.explain,
      chip = UI.chip, stat = UI.stat, alertBox = UI.alertBox, icon = UI.icon;

  function leg(action, type, strike, expiration, ratio) {
    return { action: action, type: type, strike: strike, expiration: expiration, ratio: ratio || 1 };
  }
  function stockLeg(action, ratio) {
    return { action: action, type: 'STOCK', strike: null, expiration: null, ratio: ratio || 1 };
  }

  /**
   * The catalog: every structure a broker menu carries, each with a payoff-shape sketch
   * (an SVG polyline over a 64x28 box — the SHAPE of profit vs price, not live data),
   * a one-line blurb, and a build(ctx) that snaps legs to live strikes.
   * ctx.pick(exp, offset) = the strike `offset` steps from at-the-money.
   * Undefined-risk structures are listed and labeled — selecting one previews honestly,
   * then blocks with the payoff cliff. That is the lesson, not an error.
   */
  var TEMPLATES = [
    { key: 'LONG_CALL', group: 'Bullish', name: 'Long call', family: 'LONG_CALL',
      shape: '2,20 34,20 62,4',
      blurb: 'Stock up big — pay a premium, uncapped upside, lose at most the premium.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near)]; } },
    { key: 'DEBIT_CALL_SPREAD', group: 'Bullish', name: 'Bull call spread (vertical)', family: 'DEBIT_CALL_SPREAD',
      shape: '2,20 22,20 44,8 62,8',
      blurb: 'Stock up some — cheaper than a call because you sell away the far upside.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'CREDIT_PUT_SPREAD', group: 'Bullish', name: 'Bull put spread (vertical)', family: 'CREDIT_PUT_SPREAD',
      shape: '2,22 20,22 42,8 62,8',
      blurb: 'Stock NOT down — collect a credit, keep it while price stays above your short strike.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near), leg('BUY', 'PUT', c.pick(c.near, -4), c.near)]; } },
    { key: 'CASH_SECURED_PUT', group: 'Bullish', name: 'Cash-secured put', family: 'CASH_SECURED_PUT',
      shape: '2,26 30,8 62,8',
      blurb: 'Get paid to wait for your price — assignment buys the shares at the strike.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'BUY_WRITE', group: 'Bullish', name: 'Covered call (buy-write)', family: 'COVERED_CALL',
      shape: '2,26 34,8 62,8',
      blurb: 'Buy 100 shares and rent them out immediately — premium now, capped upside.',
      build: function (c) { return [stockLeg('BUY', 1), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'RISK_REVERSAL', group: 'Bullish', name: 'Risk reversal', family: 'CUSTOM',
      shape: '2,26 22,14 42,14 62,4',
      blurb: 'Sell a put to pay for a call — strongly bullish, big reserve if wrong.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -3), c.near), leg('BUY', 'CALL', c.pick(c.near, 3), c.near)]; } },

    { key: 'LONG_PUT', group: 'Bearish', name: 'Long put', family: 'LONG_PUT',
      shape: '2,4 30,20 62,20',
      blurb: 'Stock down big — pay a premium, profit as it falls, lose at most the premium.',
      build: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 0), c.near)]; } },
    { key: 'DEBIT_PUT_SPREAD', group: 'Bearish', name: 'Bear put spread (vertical)', family: 'DEBIT_PUT_SPREAD',
      shape: '2,8 20,8 42,20 62,20',
      blurb: 'Stock down some — cheaper than a put by selling away the far downside.',
      build: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 0), c.near), leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'CREDIT_CALL_SPREAD', group: 'Bearish', name: 'Bear call spread (vertical)', family: 'CREDIT_CALL_SPREAD',
      shape: '2,8 22,8 44,22 62,22',
      blurb: 'Stock NOT up — collect a credit, keep it while price stays below your short strike.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 2), c.near), leg('BUY', 'CALL', c.pick(c.near, 4), c.near)]; } },

    { key: 'IRON_CONDOR', group: 'Neutral & income', name: 'Iron condor', family: 'IRON_CONDOR',
      shape: '2,22 14,22 24,8 40,8 50,22 62,22',
      blurb: 'Price stays in a range — sell both sides, wings cap the damage either way.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near), leg('BUY', 'PUT', c.pick(c.near, -4), c.near),
                                    leg('SELL', 'CALL', c.pick(c.near, 2), c.near), leg('BUY', 'CALL', c.pick(c.near, 4), c.near)]; } },
    { key: 'IRON_BUTTERFLY', group: 'Neutral & income', name: 'Iron butterfly', family: 'IRON_BUTTERFLY',
      shape: '2,22 18,22 32,6 46,22 62,22',
      blurb: 'Price pins near today — richer credit than a condor, narrower sweet spot.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, 0), c.near), leg('SELL', 'CALL', c.pick(c.near, 0), c.near),
                                    leg('BUY', 'PUT', c.pick(c.near, -3), c.near), leg('BUY', 'CALL', c.pick(c.near, 3), c.near)]; } },
    { key: 'CALENDAR_CALL', group: 'Neutral & income', name: 'Calendar call', family: 'CALENDAR_CALL',
      shape: '2,22 20,18 32,8 44,18 62,22',
      blurb: 'Sell the near month, own the far month — time decays faster up close.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'CALL', c.pick(c.far, 0), c.far)]; } },
    { key: 'CALENDAR_PUT', group: 'Neutral & income', name: 'Calendar put', family: 'CALENDAR_PUT',
      shape: '2,22 20,18 32,8 44,18 62,22',
      blurb: 'Same idea with puts — profits when price sits still and near IV bleeds.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.far, 0), c.far)]; } },
    { key: 'DIAGONAL_CALL', group: 'Neutral & income', name: 'Diagonal call', family: 'DIAGONAL_CALL',
      shape: '2,24 22,16 36,8 50,16 62,20',
      blurb: 'A covered call built from options — own a far-dated call, rent out near ones.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.far, 0), c.far), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'DIAGONAL_PUT', group: 'Neutral & income', name: 'Diagonal put', family: 'DIAGONAL_PUT',
      shape: '2,20 16,16 30,8 46,16 62,24',
      blurb: 'The put-side twin — far-dated put anchors, near puts collect premium.',
      build: function (c) { return [leg('BUY', 'PUT', c.pick(c.far, 0), c.far), leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; } },

    { key: 'LONG_STRADDLE', group: 'Volatility', name: 'Long straddle', family: 'LONG_STRADDLE',
      shape: '2,4 32,24 62,4',
      blurb: 'Buy both at the money — direction unknown, the size of the move is the bet.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.near, 0), c.near)]; } },
    { key: 'LONG_STRANGLE', group: 'Volatility', name: 'Long strangle', family: 'LONG_STRANGLE',
      shape: '2,6 22,24 42,24 62,6',
      blurb: 'Buy both sides out of the money — cheaper than a straddle, needs a bigger move.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 2), c.near), leg('BUY', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'CALL_BACKSPREAD', group: 'Volatility', name: 'Call ratio backspread', family: 'CUSTOM',
      shape: '2,16 20,16 34,24 62,2',
      blurb: 'Sell one call, buy two further up — loves a violent rally, hates a slow drift.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'CALL', c.pick(c.near, 2), c.near, 2)]; } },
    { key: 'PUT_BACKSPREAD', group: 'Volatility', name: 'Put ratio backspread', family: 'CUSTOM',
      shape: '2,2 30,24 44,16 62,16',
      blurb: 'Sell one put, buy two further down — pays off in a crash, bleeds in a drift.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.near, -2), c.near, 2)]; } },

    { key: 'LONG_CALL_BUTTERFLY', group: 'Pinpoint targets', name: 'Call butterfly', family: 'LONG_CALL_BUTTERFLY',
      shape: '2,22 18,22 32,4 46,22 62,22',
      blurb: 'Bet on a specific landing spot — tiny cost, big payoff exactly there.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, -2), c.near), leg('SELL', 'CALL', c.pick(c.near, 0), c.near, 2),
                                    leg('BUY', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'LONG_PUT_BUTTERFLY', group: 'Pinpoint targets', name: 'Put butterfly', family: 'LONG_PUT_BUTTERFLY',
      shape: '2,22 18,22 32,4 46,22 62,22',
      blurb: 'The same pinpoint bet built from puts — useful when puts price better.',
      build: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 2), c.near), leg('SELL', 'PUT', c.pick(c.near, 0), c.near, 2),
                                    leg('BUY', 'PUT', c.pick(c.near, -2), c.near)]; } },

    { key: 'MARRIED_PUT', group: 'Shares & protection', name: 'Married put (protective put)', family: 'PROTECTIVE_PUT',
      shape: '2,14 26,14 62,2',
      blurb: 'Shares plus an insurance floor — pay a premium, cap the downside.',
      build: function (c) { return [stockLeg('BUY', 1), leg('BUY', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'COLLAR', group: 'Shares & protection', name: 'Collar', family: 'PROTECTIVE_COLLAR',
      shape: '2,18 20,18 44,8 62,8',
      blurb: 'Floor below, ceiling above — the sold call pays for most of the insurance.',
      build: function (c) { return [stockLeg('BUY', 1), leg('BUY', 'PUT', c.pick(c.near, -2), c.near), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'PMCC', group: 'Neutral & income', name: "Poor man's covered call", family: 'DIAGONAL_CALL',
      shape: '2,24 20,14 38,7 52,10 62,12',
      blurb: 'A covered call without buying shares: own a deep, far-dated call (behaves like stock), rent out near-dated calls against it.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.far, -4), c.far), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'SYNTHETIC_LONG', group: 'Shares & protection', name: 'Synthetic long (stock replacement)', family: 'CUSTOM',
      shape: '2,26 62,4',
      blurb: 'Long call + short put at the same strike ≈ owning 100 shares, for a fraction of the cash. Same delta-1 exposure, margin instead of full share cost.',
      build: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near), leg('SELL', 'PUT', c.pick(c.near, 0), c.near)]; } },
    { key: 'SYNTHETIC_SHORT', group: 'Undefined risk (blocked)', name: 'Synthetic short', family: 'CUSTOM', risky: true,
      shape: '2,4 62,26',
      blurb: 'Long put + short call at the same strike ≈ shorting 100 shares. The short call is UNCOVERED — losses grow without limit if the stock rallies, so it is blocked here (build it to see exactly why).',
      build: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 0), c.near), leg('SELL', 'CALL', c.pick(c.near, 0), c.near)]; } },

    { key: 'SHORT_STRADDLE', group: 'Undefined risk (blocked)', name: 'Short straddle', family: 'SHORT_STRADDLE', risky: true,
      shape: '2,24 32,4 62,24',
      blurb: 'Sell both at the money — maximum premium, UNLIMITED risk. Shown so you can see why it is refused.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('SELL', 'PUT', c.pick(c.near, 0), c.near)]; } },
    { key: 'SHORT_STRANGLE', group: 'Undefined risk (blocked)', name: 'Short strangle', family: 'SHORT_STRANGLE', risky: true,
      shape: '2,24 22,6 42,6 62,24',
      blurb: 'Sell both sides wider — same unlimited-risk problem, slightly more room.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 2), c.near), leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; } },
    { key: 'NAKED_CALL', group: 'Undefined risk (blocked)', name: 'Naked call', family: 'NAKED_CALL', risky: true,
      shape: '2,8 34,8 62,26',
      blurb: 'A sold call with nothing behind it — losses grow without limit as the stock rallies.',
      build: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; } },
    { key: 'NAKED_PUT', group: 'Undefined risk (blocked)', name: 'Naked put (no cash set aside)', family: 'NAKED_PUT', risky: true,
      shape: '2,26 30,8 62,8',
      blurb: 'The cash-secured put without the cash — the same promise with nothing backing it.',
      build: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; } }
  ];

  /** Payoff-shape sketch: the SHAPE of profit vs price (educational, not live data). */
  function shapeGlyph(t) {
    var span = el('span', { class: 'tpl-shape', 'aria-hidden': 'true' });
    span.innerHTML = '<svg viewBox="0 0 64 28" width="64" height="28">'
      + '<line x1="0" y1="14" x2="64" y2="14" class="tpl-shape-zero"/>'
      + '<polyline points="' + t.shape + '" class="tpl-shape-line' + (t.risky ? ' tpl-shape-risky' : '') + '"/></svg>';
    return span;
  }

  /**
   * The beginner wizard's question tree: goal -> ONE question of choice cards -> template.
   * Every goal also offers "browse the full catalog" so nothing is locked away.
   */
  var WIZARD = {
    DIRECTIONAL: {
      prompt: 'What do you expect the stock to do?',
      options: [
        { label: 'Rise', blurb: 'Own the upside, four ways to pay for it', next: {
          prompt: 'How do you want to play the rise?',
          options: [
            { label: 'It will rise a lot', blurb: 'Uncapped upside, pay a premium', tpl: 'LONG_CALL' },
            { label: 'It will rise somewhat', blurb: 'Cheaper, capped bullish bet', tpl: 'DEBIT_CALL_SPREAD' },
            { label: 'Paid unless it falls', blurb: 'Collect a credit, keep it above your strike', tpl: 'CREDIT_PUT_SPREAD' },
            { label: 'Boldly — a put pays for the call', blurb: 'Risk reversal: big reserve if wrong', tpl: 'RISK_REVERSAL' },
            { label: 'Paid, with nothing backing it', blurb: 'The naked put — walk through WHY it is refused', tpl: 'NAKED_PUT' }
          ] } },
        { label: 'Fall', blurb: 'Profit from a drop, three shapes', next: {
          prompt: 'How do you want to play the fall?',
          options: [
            { label: 'It will fall a lot', blurb: 'Premium at risk, gains as it drops', tpl: 'LONG_PUT' },
            { label: 'It will fall somewhat', blurb: 'Cheaper, capped bearish bet', tpl: 'DEBIT_PUT_SPREAD' },
            { label: 'Paid unless it rises', blurb: 'Collect a credit, keep it below your strike', tpl: 'CREDIT_CALL_SPREAD' },
            { label: 'Paid, with nothing backing it', blurb: 'The naked call — walk through WHY it is refused', tpl: 'NAKED_CALL' }
          ] } },
        { label: 'Move big — direction unknown', blurb: 'Own both sides or bet on violence', next: {
          prompt: 'How big, and which flavor?',
          options: [
            { label: 'Big move either way', blurb: 'Straddle: both at the money', tpl: 'LONG_STRADDLE' },
            { label: 'HUGE move either way', blurb: 'Strangle: cheaper, needs more', tpl: 'LONG_STRANGLE' },
            { label: 'A violent rally', blurb: 'Call backspread: sell one, own two above', tpl: 'CALL_BACKSPREAD' },
            { label: 'A crash', blurb: 'Put backspread: pays in a plunge', tpl: 'PUT_BACKSPREAD' }
          ] } },
        { label: 'Stay calm in a range', blurb: 'Get paid while nothing happens', next: {
          prompt: 'How do you want to rent out the calm?',
          options: [
            { label: 'A comfortable range', blurb: 'Iron condor: wings cap the damage', tpl: 'IRON_CONDOR' },
            { label: 'Pinned near today', blurb: 'Iron butterfly: richer, narrower', tpl: 'IRON_BUTTERFLY' },
            { label: 'Maximum premium, no wings', blurb: 'Short straddle — see WHY it is refused', tpl: 'SHORT_STRADDLE' },
            { label: 'Wider, still no wings', blurb: 'Short strangle — same refusal, more room', tpl: 'SHORT_STRANGLE' }
          ] } },
        { label: 'Land on a specific price', blurb: 'Pinpoint bets with tiny cost', next: {
          prompt: 'Built from calls or puts?',
          options: [
            { label: 'Call butterfly', blurb: 'Big payoff exactly at the target', tpl: 'LONG_CALL_BUTTERFLY' },
            { label: 'Put butterfly', blurb: 'Same bet when puts price better', tpl: 'LONG_PUT_BUTTERFLY' }
          ] } }
      ]
    },
    INCOME: {
      prompt: 'What should back the income?',
      options: [
        { label: 'Cash — and I would own the stock', blurb: 'Sell a put at a price you would pay', tpl: 'CASH_SECURED_PUT' },
        { label: '100 shares bought alongside', blurb: 'Buy-write: shares plus a rented-out call', tpl: 'BUY_WRITE' },
        { label: 'A defined-risk range bet', blurb: 'Iron condor: paid while price stays inside', tpl: 'IRON_CONDOR' },
        { label: 'A tight pin near today’s price', blurb: 'Iron butterfly: richer credit, narrower window', tpl: 'IRON_BUTTERFLY' },
        { label: 'Time itself — near decays faster', blurb: 'Calendar: sell the near month, own the far', next: {
          prompt: 'Calls or puts?',
          options: [
            { label: 'Calendar with calls', blurb: 'The classic', tpl: 'CALENDAR_CALL' },
            { label: 'Calendar with puts', blurb: 'Same clock, put side', tpl: 'CALENDAR_PUT' }
          ] } },
        { label: 'A long option I rent against', blurb: 'Diagonal: covered call without the shares', next: {
          prompt: 'Calls or puts?',
          options: [
            { label: 'Diagonal with calls', blurb: 'Far-dated call anchors', tpl: 'DIAGONAL_CALL' },
            { label: 'Diagonal with puts', blurb: 'The put-side twin', tpl: 'DIAGONAL_PUT' }
          ] } }
      ]
    },
    ACQUIRE: {
      prompt: 'How eager are you to own it?',
      options: [
        { label: 'Happy to buy just below today', blurb: 'Higher odds of assignment, smaller discount', tpl: 'CASH_SECURED_PUT', offset: 1 },
        { label: 'Only at a real discount', blurb: 'Bigger discount, may never fill', tpl: 'CASH_SECURED_PUT', offset: -1 }
      ]
    },
    EXIT: {
      prompt: 'How would you sell shares at your price?',
      note: 'Already hold the shares? The Ideas screen sizes covered calls to your actual lot — this builds a fresh buy-write.',
      options: [
        { label: 'Buy shares and rent them out', blurb: 'Buy-write at a strike you would sell at', tpl: 'BUY_WRITE' },
        { label: 'With a safety floor too', blurb: 'Collar: floor below, exit ceiling above', tpl: 'COLLAR' }
      ]
    },
    HEDGE: {
      prompt: 'How much protection do you want?',
      note: 'Already hold the shares? The Ideas screen prices floors for your actual lot — this builds shares + protection together.',
      options: [
        { label: 'A hard floor, whatever it costs', blurb: 'Married put: pay for insurance', tpl: 'MARRIED_PUT' },
        { label: 'A cheap floor, capped upside', blurb: 'Collar: the sold call funds the put', tpl: 'COLLAR' },
        { label: 'Partial insurance, cheaper still', blurb: 'Put spread: covers a fall down to a floor, not past it', tpl: 'DEBIT_PUT_SPREAD' }
      ]
    }
  };

  /** Plain-language story for one leg (dollars fill in once the preview knows the fill). */
  function legStory(l, fillDollars) {
    var K = l.strike ? '$' + parseFloat(l.strike) : '';
    var cash = fillDollars ? ' (' + (l.action === 'SELL' ? 'you collect about ' : 'costs about ')
      + fmtMoney(Math.round(fillDollars * 100 * 100 * l.ratio)) + ')' : '';
    if (l.type === 'STOCK') {
      return (l.action === 'BUY' ? 'Buy ' : 'Sell ') + (100 * l.ratio)
        + ' shares — the foundation the option legs work against.';
    }
    if (l.action === 'SELL' && l.type === 'PUT') {
      return 'Sell the ' + K + ' put' + cash + ' — you promise to buy 100 shares at ' + K + ' if asked.';
    }
    if (l.action === 'SELL' && l.type === 'CALL') {
      return 'Sell the ' + K + ' call' + cash + ' — you promise to deliver 100 shares at ' + K + ' if called.';
    }
    if (l.action === 'BUY' && l.type === 'PUT') {
      return 'Buy the ' + K + ' put' + cash + ' — your right to sell at ' + K + ': a floor under the position.';
    }
    return 'Buy the ' + K + ' call' + cash + ' — your right to buy at ' + K + ': the upside engine.';
  }

  // ==================================================================================

  async function render(root) {
    var level = Learn.currentLevel();
    var saved = App.state.builderForm || {};
    var st = {
      // An in-progress build owns its symbol; an EMPTY builder follows the working
      // symbol from Ideas/Research ("I chose another stock" must not reload the old one)
      symbol: ((saved.legs && saved.legs.length ? saved.symbol : null)
        || App.state.lastRecommendSymbol || saved.symbol || 'AAPL').toUpperCase(),
      qty: saved.qty || 1,
      goal: saved.goal || null,
      templateKey: saved.templateKey || null,
      step: saved.step || 1,
      legIdx: saved.legIdx || 0,
      wizNode: saved.wizNode || null,   // rehydrate the mid-question wizard node across re-renders/level flips
      legs: saved.legs ? saved.legs.map(function (l) { return Object.assign({}, l); }) : [],
      excluded: saved.excluded || {},
      limits: saved.limits || {}
    };
    // Cross-level coherence: a position built in the Expert terminal (or handed off) must not vanish
    // into Beginner's step-1 goal chooser and get overwritten — land it on Beginner's recap instead.
    if (level === 'beginner' && st.legs.length && st.step < 3) { st.step = 4; }
    function remember() { App.state.builderForm = st; }

    var research = null, chainCache = {}, expirations = [], spot = null;
    // Monotonic supersede token: whenever the STRUCTURE changes (pick/seed/fit), bump this and drop any
    // in-flight build that resolves late, so st.templateKey and st.legs can never end up disagreeing
    // (legs from structure A paired with the strategy label of structure B).
    var buildSeq = 0;
    async function loadSymbol() {
      research = await API.get('/api/research/' + st.symbol);
      expirations = research.expirations || [];
      spot = research.quote && research.quote.last ? parseFloat(research.quote.last) : null;
      chainCache = {};
    }
    function ensureChain(exp) {
      if (!chainCache[exp]) {
        chainCache[exp] = API.get('/api/research/' + st.symbol + '/chain?expiration=' + exp)
          .catch(function () { return { calls: [], puts: [] }; });
      }
      return chainCache[exp];
    }
    async function tplContext() {
      if (!expirations.length || spot === null) return null;
      var near = expirations[Math.min(2, expirations.length - 1)];
      var far = expirations[Math.min(5, expirations.length - 1)];
      var chains = {};
      chains[near] = await ensureChain(near);
      if (far !== near) chains[far] = await ensureChain(far);
      function strikesOf(exp) {
        return ((chains[exp] || {}).calls || []).map(function (q) { return parseFloat(q.strike); }).sort(function (a, b) { return a - b; });
      }
      return {
        near: near, far: far, spot: spot,
        pick: function (exp, offset) {
          var ks = strikesOf(exp);
          if (!ks.length) return null;
          var atm = 0, best = Infinity;
          ks.forEach(function (k, i) { var d = Math.abs(k - spot); if (d < best) { best = d; atm = i; } });
          return String(ks[Math.max(0, Math.min(ks.length - 1, atm + offset))]);
        }
      };
    }
    async function buildFromTemplate(key, offset) {
      var t = TEMPLATES.find(function (x) { return x.key === key; });
      var ctx = await tplContext();
      if (!t || !ctx) return [];
      if (offset) {
        var base = ctx.pick;
        ctx = Object.assign({}, ctx, { pick: function (exp, o) { return base(exp, o + offset); } });
      }
      return t.build(ctx).filter(function (l) { return l.type === 'STOCK' || l.strike; });
    }

    /**
     * Drag-handles for the payoff chart: one per OPTION leg (indices into st.legs),
     * snapping to that leg's real chain strikes. onMoved = level-appropriate re-price.
     */
    async function strikeHandles(indices, onMoved) {
      var out = [];
      for (var n = 0; n < indices.length; n++) {
        var i = indices[n];
        var l = st.legs[i];
        if (!l || l.type === 'STOCK' || !l.strike || !l.expiration) continue;
        var chain = await ensureChain(l.expiration);
        var ks = ((l.type === 'CALL' ? chain.calls : chain.puts) || [])
          .map(function (q) { return parseFloat(q.strike); })
          .sort(function (a, b) { return a - b; });
        if (ks.length < 2) continue;
        out.push((function (idx, strikes) {
          var leg = st.legs[idx];
          return {
            id: 'leg' + idx,
            strike: parseFloat(leg.strike),
            label: (leg.action === 'SELL' ? 'SELL ' : 'BUY ') + leg.type.charAt(0) + ' ' + parseFloat(leg.strike),
            strikes: strikes,
            onChange: function (ns) {
              st.legs[idx].strike = String(ns);
              remember();
              onMoved();
            }
          };
        })(i, ks));
      }
      return out;
    }

    function wireLegs(legs) {
      return legs
        .filter(function (l) { return l.type === 'STOCK' || (l.strike && l.expiration); })
        .map(function (l) {
          return { action: l.action, type: l.type, strike: l.type === 'STOCK' ? null : String(l.strike),
                   expiration: l.type === 'STOCK' ? null : l.expiration, ratio: l.ratio || 1 };
        });
    }
    function familyLabel() {
      var t = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
      return t ? t.family : 'CUSTOM';
    }
    async function previewLegs(legs) {
      var wired = wireLegs(legs);
      if (!wired.length) return null;
      return API.post('/api/trades/preview', {
        symbol: st.symbol, strategy: familyLabel(), qty: st.qty, legs: wired
      });
    }
    function activeLegs() {
      return st.legs.filter(function (_, i) { return !st.excluded[i]; });
    }
    // ---- Your limits: the same screens Ideas offers, wired into the builder ----
    // Set a number and the panel judges the live position against it; "Fit to my limits"
    // asks the ENGINE to size/strike this structure so the limits hold (same math as Ideas).
    function limitField(key, label, step, placeholder) {
      var input = el('input', { type: 'number', id: 'bl-' + key, min: '0', step: step,
        placeholder: placeholder || 'any', value: st.limits[key] || '' });
      input.addEventListener('change', function () {
        st.limits[key] = input.value;
        remember();
        schedulePreviewIfAny();
      });
      return el('div', { class: 'field' }, el('label', {}, label), input);
    }
    var schedulePreviewHook = null; // set by whichever surface renders (repaint or debounce)
    function schedulePreviewIfAny() { if (schedulePreviewHook) schedulePreviewHook(); }

    function limitsFields(beginnerShape) {
      // ALL FOUR limits exist at both levels (presentation-only levels, review P0) — beginner
      // wording is plain, expert wording is compact; a value set anywhere judges everywhere.
      return beginnerShape
        ? [limitField('maxLoss', UI.term ? 'The most I am willing to lose ($)' : 'Max loss ($)', '50'),
           limitField('minPop', 'Minimum chance of any profit (%)', '5'),
           limitField('target', 'Profit I am aiming for ($)', '50'),
           limitField('maxAssign', 'Chance I end up with shares (max %)', '5')]
        : [limitField('maxLoss', 'Worst case \u2264 $', '50'),
           limitField('target', 'Profit target \u2265 $', '50'),
           limitField('minPop', 'Chance of profit \u2265 %', '5'),
           limitField('maxAssign', 'Assignment risk \u2264 %', '5')];
    }

    /** Pass/fail chips judging the CURRENT preview against each set limit. */
    function limitChips(p) {
      var checks = [];
      function judge(label, ok, actual) {
        checks.push(el('span', { class: 'chip limit-chip ' + (ok ? 'limit-ok' : 'limit-fail') },
          el('span', { class: 'chip-label' }, label), el('b', {}, actual)));
      }
      var v = st.limits;
      // Every limit is VISIBLE (and clearable) at both levels now, so every set value judges —
      // no invisible constraints, no silently-dropped persisted choices (review P0).
      var expertLimits = true;
      if (v.maxLoss) {
        var cap = Math.round(parseFloat(v.maxLoss) * 100);
        var unbounded = !p.ok && (p.blockReasons || []).some(function (r) { return /undefined|unlimited/i.test(r); });
        judge('Max loss ≤ ' + fmtMoney(cap), !unbounded && p.maxLossCents > 0 && p.maxLossCents <= cap,
          unbounded ? 'UNLIMITED' : fmtMoney(p.maxLossCents));
      }
      if (expertLimits && v.target) {
        var tgt = Math.round(parseFloat(v.target) * 100);
        var uncapped = p.maxProfitCents === null || p.maxProfitCents === undefined;
        judge('Profit target ≥ ' + fmtMoney(tgt), uncapped || p.maxProfitCents >= tgt,
          uncapped ? 'Uncapped' : fmtMoney(p.maxProfitCents));
      }
      if (v.minPop) {
        var pop = p.popEntry;
        judge('POP ≥ ' + v.minPop + '%', pop !== null && pop !== undefined && pop * 100 >= parseFloat(v.minPop),
          pop === null || pop === undefined ? '—' : fmtPct(pop));
      }
      if (expertLimits && v.maxAssign) {
        var ap = p.assignmentProb;
        judge('Assign ≤ ' + v.maxAssign + '%', ap === null || ap === undefined || ap * 100 <= parseFloat(v.maxAssign),
          ap === null || ap === undefined ? 'n/a' : fmtPct(ap));
      }
      if (!checks.length) return null;
      return el('div', { class: 'chip-row', id: 'builder-limit-chips' },
        el('span', { class: 'muted' }, 'Against your limits:'), checks);
    }

    /** Asks the engine to size/strike the CURRENT structure so the limits hold. */
    async function fitToLimits(statusHost) {
      var t = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
      if (!t || t.family === 'CUSTOM' || t.risky) {
        statusHost.appendChild(alertBox('warn', 'The engine can fit named structures only',
          [t && t.risky ? 'Undefined-risk structures are never recommended — that is the point.'
                        : 'Pick a structure from the catalog first (custom leg piles have no engine template).']));
        return;
      }
      statusHost.innerHTML = '';
      statusHost.appendChild(UI.spinner('Asking the engine to fit your limits…'));
      try {
        var body = {
          symbol: st.symbol, riskMode: document.getElementById('risk-mode').value,
          horizon: 'month', allowedStrategies: [t.family]
        };
        if (st.limits.maxLoss) body.maxLossCents = Math.round(parseFloat(st.limits.maxLoss) * 100);
        var f = {};
        if (st.limits.minPop) f.minPop = parseFloat(st.limits.minPop) / 100;
        if (st.limits.maxAssign) f.maxAssignmentProb = parseFloat(st.limits.maxAssign) / 100;
        if (Object.keys(f).length) body.filters = f;
        var fitSeq = ++buildSeq;                 // the fitted legs replace the structure — same supersede rule
        var r = await API.post('/api/recommend', body);
        if (fitSeq !== buildSeq) return;         // a newer pick/fit superseded this
        statusHost.innerHTML = '';
        var c = (r.candidates || [])[0];
        if (!c) {
          var reasons = (r.rejected || []).slice(0, 3).map(function (x) { return x.reason || x.blockReasons && x.blockReasons[0]; }).filter(Boolean);
          statusHost.appendChild(alertBox('warn', 'No ' + t.name + ' fits those limits right now',
            reasons.length ? reasons : ['Loosen a limit or pick a different structure.']));
          return;
        }
        st.legs = (c.legs || []).map(function (l) {
          return { action: l.action, type: l.stock ? 'STOCK' : l.type,
                   strike: l.stock ? null : String(l.strike),
                   expiration: l.stock ? null : l.expiration, ratio: l.ratio || 1 };
        });
        st.qty = c.qty || 1;
        st.excluded = {};
        remember();
        if (typeof onLegsReplaced === 'function') onLegsReplaced();
      } catch (e) {
        statusHost.innerHTML = '';
        statusHost.appendChild(alertBox('danger', 'Could not fit', [e.message]));
      }
    }
    var onLegsReplaced = null; // each surface wires its own re-render

    function handoff() {
      // A structure picked from a NAMED template keeps its identity all the way to the
      // portfolio row — an 'Iron condor' must never place as 'Custom' (naming is education).
      var tpl = st.templateKey ? TEMPLATES.find(function (t2) { return t2.key === st.templateKey; }) : null;
      App.state.ticket = {
        world: App.state.world || 'observed',
        symbol: st.symbol, custom: true, customFor: st.symbol,
        customFamily: tpl && tpl.family ? tpl.family : null,
        legs: wireLegs(activeLegs()), qty: st.qty, step: 6,
        thesis: 'neutral', horizon: 'month'
      };
      App.navigate('#/trade/place');
    }

    try {
      await loadSymbol();
    } catch (e) {
      // Never a dead end: say what failed, offer the universe one tap away, and retry
      var failInput = el('input', { type: 'text', id: 'builder-symbol', list: 'universe-symbols', value: st.symbol });
      var retryWith = function (sym) {
        st.symbol = (sym || failInput.value.trim() || st.symbol).toUpperCase();
        App.state.lastRecommendSymbol = st.symbol;
        st.legs = []; st.legIdx = 0; if (st.step > 2) st.step = st.goal ? 2 : 1;
        remember();
        App.render();
      };
      var uniSyms = (App.state.universe && App.state.universe.active.symbols) || [];
      root.appendChild(el('div', { class: 'card', id: 'builder-load-error' },
        alertBox('danger', 'Could not load ' + st.symbol, [e.message,
          'The builder needs an eligible quote and option chain in the active market. Pick another symbol or retry.']),
        UI.symbolContext({ mode: 'editable', id: 'builder-error-symbol-context', input: failInput,
          label: 'Try another symbol', commitLabel: 'Retry', commitId: 'builder-retry', onCommit: retryWith }),
        uniSyms.length ? el('div', { class: 'sym-chips', style: 'margin-top:8px' }, uniSyms.map(function (s2) {
          return el('button', { class: 'sym-chip', type: 'button', onclick: function () { retryWith(s2); } }, s2);
        })) : null));
      return;
    }

    remember(); // the (possibly followed) symbol is now this builder's state

    // A Trade exposure-replication handoff can seed a templateKey with no legs —
    // build it from the live chain now so the user lands ON the structure, not an empty catalog.
    if (st.templateKey && !st.legs.length) {
      var seedSeq = ++buildSeq;
      try {
        var seeded = await buildFromTemplate(st.templateKey);
        if (seedSeq === buildSeq && seeded.length) { st.legs = seeded; st.legIdx = 0; if (st.step < 3) st.step = 3; remember(); }
      } catch (e) { /* fall through to the catalog */ }
    }

    if (level === 'beginner') renderBeginner(root);
    else await renderExpert(root);

    // =============================== BEGINNER =====================================
    function renderBeginner(root) {
      var host = el('div', { id: 'builder' });
      root.appendChild(host);
      var walkSeq = 0; // supersede token for the async leg-walk / position preview (chart+stats)
      function repaint() { host.innerHTML = ''; paint(); remember(); }

      function stepHeader() {
        // Every step you have EARNED is a live link — go back to change your mind, jump
        // forward to where you were. Unreached steps say what unlocks them.
        var names = ['Goal', 'Structure', 'Build it', 'Where you stand'];
        var reachable = [true, !!st.goal, st.legs.length > 0, st.legs.length > 0];
        var why = [null, 'Pick a goal first', 'Choose a structure first', 'Choose a structure first'];
        return el('div', { class: 'wizard-steps' }, names.map(function (n, i) {
          var here = i + 1 === st.step;
          var cls = here ? ' active' : (i + 1 < st.step ? ' done' : '');
          var can = reachable[i] && !here;
          return el('button', {
            class: 'step' + cls + (can ? ' step-link' : ''), type: 'button', 'data-step': i + 1,
            disabled: reachable[i] ? null : '',
            title: reachable[i] ? null : why[i],
            onclick: can ? function () {
              // Jumping to 'Build it' RESUMES where you were (legIdx is clamped in paint) — it must
              // NOT force the last leg, or the same step would show a different payoff than the walk.
              st.step = i + 1;
              repaint();
            } : null
          }, el('b', {}, String(i + 1)), ' ', n);
        }));
      }
      function symbolInput() {
        var input = el('input', { type: 'text', id: 'builder-symbol', list: 'universe-symbols', value: st.symbol });
        input.addEventListener('change', async function () {
          var sym = input.value.trim().toUpperCase();
          if (!sym || sym === st.symbol) return;
          st.symbol = sym; App.state.lastRecommendSymbol = sym;
          st.legs = []; st.step = st.goal ? 2 : 1; st.legIdx = 0;
          try { await loadSymbol(); } catch (e) { /* the next preview reports honestly */ }
          repaint();
        });
        return input;
      }
      function qtyInput() {
        var input = el('input', { type: 'number', id: 'builder-qty', min: '1', max: '100', value: String(st.qty) });
        input.addEventListener('change', function () {
          st.qty = Math.max(1, Math.min(100, parseInt(input.value, 10) || 1));
          input.value = String(st.qty);
          remember(); repaint();
        });
        return input;
      }
      function catalogGrid(onPick) {
        var wrap = el('div', { id: 'builder-catalog' });
        var groups = [];
        TEMPLATES.forEach(function (t) { if (groups.indexOf(t.group) < 0) groups.push(t.group); });
        groups.forEach(function (g) {
          wrap.appendChild(el('div', { class: 'tpl-group-label' }, g));
          wrap.appendChild(el('div', { class: 'tpl-grid' },
            TEMPLATES.filter(function (t) { return t.group === g; }).map(function (t) {
              return el('button', {
                class: 'tpl' + (st.templateKey === t.key ? ' selected' : ''), type: 'button', 'data-tpl': t.key,
                onclick: function () { onPick(t); }
              },
                el('span', { class: 'tpl-head' }, shapeGlyph(t), el('b', {}, t.name),
                  t.risky ? el('span', { class: 'badge badge-danger' }, 'BLOCKED') : null),
                el('span', { class: 'muted tpl-blurb' }, t.blurb));
            })));
        });
        return wrap;
      }
      async function pickTemplate(t, offset) {
        var seq = ++buildSeq;
        var built = await buildFromTemplate(t.key, offset);
        if (seq !== buildSeq) return;           // a newer pick won — drop this stale build
        st.templateKey = t.key; st.legs = built; // assign TOGETHER so label + legs always agree
        st.legIdx = 0; st.step = 3;
        repaint();
      }

      function paint() {
        var contextInput = symbolInput();
        host.appendChild(UI.symbolContext({
          mode: 'editable', id: 'builder-symbol-context', input: contextInput,
          label: 'Which stock are you building around?', commitLabel: 'Load',
          onCommit: function () { contextInput.dispatchEvent(new Event('change')); }
        }));
        host.appendChild(stepHeader());

        if (st.step === 1) {
          host.appendChild(el('div', { class: 'card' },
            el('h3', { class: 'mt0' }, 'What are you trying to do?'),
            explain('The builder walks you from a goal to a full multi-leg position, one leg at a time, showing exactly what each leg adds — cost, worst case, and odds. Every structure a broker menu carries is here, each with its payoff shape and a guide.'),
            el('div', { class: 'choice-row', id: 'bw-goals' }, (Learn.INTENTS || []).map(function (i) {
              return el('button', {
                class: 'choice' + (st.goal === i.key ? ' selected' : ''), 'data-goal': i.key,
                onclick: function () { st.goal = i.key; st.step = 2; repaint(); }
              }, el('span', { class: 'choice-head' }, icon(i.icon, 17), el('b', {}, i.label)),
                 el('span', { class: 'muted' }, i.blurb));
            })),
            el('div', { class: 'btn-row' },
              el('button', {
                class: 'btn btn-sm btn-secondary', id: 'bw-browse',
                onclick: function () { st.goal = 'BROWSE'; st.step = 2; repaint(); }
              }, 'Browse all structures instead'))));
          return;
        }

        if (st.step === 2) {
          if (st.goal === 'BROWSE') {
            host.appendChild(el('div', { class: 'card' },
              el('h3', { class: 'mt0' }, 'Every structure, with its payoff shape'),
              explain('The little chart on each card is the SHAPE of profit (up) vs the stock price (right) at expiration. Red-badged structures carry unlimited risk — you can walk through one to see exactly why it gets refused.'),
              catalogGrid(function (t) { pickTemplate(t); }),
              el('div', { class: 'btn-row' },
                el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { st.step = 1; repaint(); } }, '← Back'))));
            return;
          }
          var w = st.wizNode || WIZARD[st.goal] || WIZARD.DIRECTIONAL;
          host.appendChild(el('div', { class: 'card' },
            el('h3', { class: 'mt0' }, w.prompt),
            w.note ? explain(w.note) : null,
            el('div', { class: 'choice-row', id: 'bw-shape' }, w.options.map(function (o) {
              var t = o.tpl ? TEMPLATES.find(function (x) { return x.key === o.tpl; }) : null;
              return el('button', {
                class: 'choice' + (st.templateKey === o.tpl ? ' selected' : ''),
                'data-tpl': o.tpl || null, 'data-next': o.next ? '' : null,
                onclick: function () {
                  if (o.next) { st.wizNode = o.next; repaint(); }
                  else { st.wizNode = null; pickTemplate(t, o.offset); }
                }
              }, el('span', { class: 'choice-head' }, t ? shapeGlyph(t) : null, el('b', {}, o.label),
                   t && t.risky ? el('span', { class: 'badge badge-danger' }, 'BLOCKED') : null,
                   o.next ? el('span', { class: 'muted choice-more' }, '›') : null),
                 el('span', { class: 'muted' }, o.blurb));
            })),
            el('div', { class: 'btn-row' },
              el('button', { class: 'btn btn-secondary btn-sm', onclick: function () {
                if (st.wizNode) { st.wizNode = null; } else { st.step = 1; }
                repaint();
              } }, '← Back'),
              el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { st.wizNode = null; st.goal = 'BROWSE'; repaint(); } }, 'Browse all structures'))));
          return;
        }

        if (st.step === 3) {
          if (!st.legs.length) { st.step = 2; repaint(); return; }
          var n = st.legs.length;
          var i = Math.min(st.legIdx, n - 1);
          var soFar = st.legs.slice(0, i + 1);
          var tSel = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
          var g = tSel && Learn.STRATEGY_GUIDE[tSel.family];
          host.appendChild(el('div', { class: 'card', id: 'bw-walk' },
            el('div', { class: 'field-label' }, (tSel ? tSel.name + ' — ' : '') + 'leg ' + (i + 1) + ' of ' + n),
            el('h3', { class: 'mt0', id: 'bw-leg-story' }, legStory(st.legs[i])),
            el('div', { id: 'bw-impact' }, UI.spinner('Measuring what this leg changes…')),
            g && i === 0 ? UI.expandable('How this trade works — and what can go wrong', function () {
              return el('div', {},
                el('p', {}, el('i', {}, g.story)),
                el('div', { class: 'fact-row' }, el('span', {}, el('b', { class: 'gain' }, 'You win when: '), g.win)),
                el('div', { class: 'fact-row' }, el('span', {}, el('b', { class: 'loss' }, 'You lose when: '), g.lose)),
                el('p', {}, el('b', {}, 'Easy to miss: '), g.watch));
            }) : null,
            el('div', { class: 'btn-row' },
              el('button', { class: 'btn btn-secondary btn-sm', onclick: function () {
                if (i > 0) { st.legIdx = i - 1; } else { st.step = 2; }
                repaint();
              } }, '← Back'),
              el('button', { class: 'btn', id: 'bw-next', onclick: function () {
                if (i < n - 1) { st.legIdx = i + 1; repaint(); }
                else { st.step = 4; repaint(); }
              } }, i < n - 1 ? 'Add the next leg →' : 'See the whole position →'))));

          (async function measure() {
            var seq = ++walkSeq;
            var impact = document.getElementById('bw-impact');
            try {
              var nowRes = await previewLegs(soFar);
              var beforeRes = i > 0 ? await previewLegs(st.legs.slice(0, i)) : null;
              if (seq !== walkSeq || !impact || !impact.isConnected) return;
              impact.innerHTML = '';
              var p = nowRes.preview;
              var b = beforeRes ? beforeRes.preview : null;
              var storyEl = document.getElementById('bw-leg-story');
              var mine = (p.legs || [])[Math.min(i, (p.legs || []).length - 1)];
              if (storyEl && mine && mine.fill) storyEl.textContent = legStory(st.legs[i], parseFloat(mine.fill));

              if (!p.ok && (p.blockReasons || []).some(function (r) { return /undefined|unlimited/i.test(r); }) && i < n - 1) {
                impact.appendChild(alertBox('warn', 'On its own, this leg has unlimited risk',
                  ['That is exactly why the next leg exists — it caps the loss. Watch the chart change when you add it.']));
              } else if (!p.ok) {
                impact.appendChild(alertBox('warn', 'Not tradable as-is', p.blockReasons));
              }
              impact.appendChild(el('div', { class: 'chip-row' },
                chip(p.entryNetPremiumCents >= 0 ? 'Collected so far' : 'Paid so far', fmtMoney(Math.abs(p.entryNetPremiumCents))),
                impactChip('Worst case', b, p, function (x) {
                  if (!x.ok && (x.blockReasons || []).some(function (r) { return /undefined|unlimited/i.test(r); })) return 'UNLIMITED';
                  return fmtMoney(x.maxLossCents);
                }, true),
                impactChip('Best case', b, p, function (x) {
                  return x.maxProfitCents === null || x.maxProfitCents === undefined ? 'Uncapped' : fmtMoney(x.maxProfitCents);
                }, false),
                p.popEntry !== null && p.popEntry !== undefined
                  ? impactChip('Odds of profit', b, p, function (x) {
                      return x.popEntry === null || x.popEntry === undefined ? '—' : fmtPct(x.popEntry);
                    }, false) : null));
              if (p.payoff && p.payoff.length > 1) {
                var walkIdx = [];
                for (var wi = 0; wi <= i; wi++) walkIdx.push(wi);
                var walkHandles = await strikeHandles(walkIdx, function () { repaint(); });
                if (seq !== walkSeq || !impact.isConnected) return;
                if (walkHandles.length) {
                  impact.appendChild(el('p', { class: 'muted', style: 'margin:6px 0 2px; font-size:12.5px' },
                    'Try it: drag a strike marker and watch this leg\u2019s numbers move.'));
                }
                impact.appendChild(UI.payoffChart(p.payoff, {
                  breakevens: p.breakevens, spot: p.underlyingCents ? p.underlyingCents / 100 : null,
                  handles: walkHandles
                }));
              }
            } catch (e) {
              if (seq !== walkSeq || !impact || !impact.isConnected) return;
              impact.innerHTML = '';
              impact.appendChild(alertBox('danger', 'Could not price this leg', [e.message]));
            }
          })();
          return;
        }

        // Step 4: the whole position
        var fitStatus = el('div', { id: 'builder-fit-status' });
        host.appendChild(el('div', { class: 'card', id: 'bw-final' },
          el('h3', { class: 'mt0' }, 'Where you stand'),
          el('div', { id: 'bw-legs-recap' },
            st.legs.map(function (l, idx) {
              return el('div', { class: 'fact-row' },
                el('span', {}, el('b', {}, 'Leg ' + (idx + 1) + ': '), legStory(l)));
            })),
          UI.expandable('Fine-tune each leg \u2014 exact strikes and dates', function () {
            var box = el('div', { id: 'bw-tune' });
            box.appendChild(el('p', { class: 'muted', style: 'margin:0 0 8px; font-size:12.5px' },
              'Autopilot picked these; every one is yours to change. The chart and the numbers below re-price on every change \u2014 that is the fastest way to FEEL what a strike or an extra week does.'));
            st.legs.forEach(function (l, idx) {
              if (l.type === 'STOCK') {
                box.appendChild(el('div', { class: 'tune-row' },
                  el('b', {}, l.action + ' ' + (100 * (l.ratio || 1)) + ' shares'),
                  el('span', { class: 'muted' }, 'stock legs have no strike or expiry')));
                return;
              }
              var expSel = el('select', { class: 'tune-exp' }, expirations.map(function (d) {
                return el('option', { value: d, selected: d === l.expiration ? '' : null }, d);
              }));
              var strikeSel = el('select', { class: 'tune-strike' });
              async function fillStrikes(keep) {
                var chain = await ensureChain(st.legs[idx].expiration);
                var ks = ((st.legs[idx].type === 'CALL' ? chain.calls : chain.puts) || [])
                  .map(function (q) { return parseFloat(q.strike); })
                  .sort(function (a, b) { return a - b; });
                strikeSel.innerHTML = '';
                var want = parseFloat(keep);
                var best = ks[0], bd = Infinity;
                ks.forEach(function (k) { var d = Math.abs(k - want); if (d < bd) { bd = d; best = k; } });
                ks.forEach(function (k) {
                  strikeSel.appendChild(el('option', { value: String(k), selected: k === best ? '' : null }, String(k)));
                });
                if (String(best) !== String(st.legs[idx].strike)) {
                  st.legs[idx].strike = String(best); // nearest strike on the new expiry
                }
              }
              fillStrikes(l.strike);
              expSel.addEventListener('change', async function () {
                st.legs[idx].expiration = expSel.value;
                await fillStrikes(st.legs[idx].strike);
                remember(); repaint();
              });
              strikeSel.addEventListener('change', function () {
                st.legs[idx].strike = strikeSel.value;
                remember(); repaint();
              });
              box.appendChild(el('div', { class: 'tune-row' },
                el('span', { class: 'badge ' + (l.action === 'SELL' ? 'badge-warn' : 'badge-ok') }, l.action),
                el('b', {}, l.type),
                el('label', { class: 'muted' }, 'strike'), strikeSel,
                el('label', { class: 'muted' }, 'expires'), expSel));
            });
            return box;
          }),
          el('div', { class: 'form-grid', style: 'margin-top:8px' },
            [el('div', { class: 'field' }, el('label', {}, 'Contracts (qty)'), qtyInput())].concat(limitsFields(true))),
          el('div', { class: 'btn-row', style: 'margin-top:2px' },
            el('button', { class: 'btn btn-sm btn-secondary', id: 'builder-fit', onclick: function () { fitToLimits(fitStatus); } },
              'Fit to my limits'),
            el('span', { class: 'muted' }, 'The engine re-picks strikes and size so your limits hold — same math as Ideas.')),
          fitStatus,
          el('div', { id: 'bw-panel' }, UI.spinner('Pricing the whole position…')),
          el('div', { class: 'btn-row' },
            el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { st.legIdx = 0; st.step = 3; repaint(); } }, '← Walk the legs again'),
            el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { st.step = 1; st.legs = []; st.templateKey = null; st.goal = null; repaint(); } }, 'Start over'),
            el('button', { class: 'btn', id: 'builder-review', onclick: handoff }, 'Review & place (paper) →'))));
        (async function price() {
          var seq = ++walkSeq;
          var panel = document.getElementById('bw-panel');
          try {
            var res = await previewLegs(st.legs);
            var allIdx = st.legs.map(function (_, k) { return k; });
            var handles = await strikeHandles(allIdx, function () { repaint(); });
            if (seq !== walkSeq || !panel || !panel.isConnected) return;
            panel.innerHTML = '';
            renderVerdictAndStats(panel, res.preview, res.guardrails, true, handles);
            var lc = limitChips(res.preview);
            if (lc) panel.insertBefore(lc, panel.firstChild);
            var btn = document.getElementById('builder-review');
            if (btn && !res.preview.ok) { btn.disabled = true; btn.title = 'Fix the blocking issues first'; }
          } catch (e) {
            if (seq !== walkSeq || !panel || !panel.isConnected) return;
            panel.innerHTML = '';
            panel.appendChild(alertBox('danger', 'Could not price the position', [e.message]));
          }
        })();
      }

      function impactChip(label, before, now, read, isLoss) {
        var nowV = read(now);
        var val;
        if (before) {
          var beforeV = read(before);
          val = beforeV === nowV ? nowV
            : el('span', {}, el('span', { class: 'muted' }, beforeV + ' → '), el('b', { class: isLoss ? 'loss' : '' }, nowV));
        } else {
          val = el('b', { class: isLoss ? 'loss' : '' }, nowV);
        }
        return chip(label, val);
      }

      schedulePreviewHook = function () { if (st.step === 4) repaint(); };
      onLegsReplaced = function () { st.step = 4; repaint(); };
      paint();
      remember();
    }

    // ================================ EXPERT ======================================
    async function renderExpert(root) {
      var symInput = el('input', { type: 'text', id: 'builder-symbol', list: 'universe-symbols', value: st.symbol });
      var qtyIn = el('input', { type: 'number', id: 'builder-qty', min: '1', max: '100', value: String(st.qty) });
      var tplSel = el('select', { id: 'builder-template' },
        el('option', { value: '' }, 'Structure…'),
        (function () {
          var groups = [];
          TEMPLATES.forEach(function (t) { if (groups.indexOf(t.group) < 0) groups.push(t.group); });
          return groups.map(function (g) {
            return el('optgroup', { label: g }, TEMPLATES.filter(function (t) { return t.group === g; })
              .map(function (t) {
                return el('option', { value: t.key, selected: st.templateKey === t.key ? '' : null }, t.name);
              }));
          });
        })());
      var legsHost = el('div', { id: 'builder-legs' });
      var addBtn = el('button', { class: 'btn btn-sm btn-secondary', id: 'builder-add-leg' }, '+ Leg');
      var clearBtn = el('button', { class: 'btn btn-sm btn-secondary', id: 'builder-clear', onclick: function () {
        st.legs = []; st.excluded = {}; st.templateKey = null; tplSel.value = '';
        remember(); renderLegs(); schedulePreview();
      } }, 'Clear');
      var panel = el('div', { class: 'card builder-panel', id: 'builder-panel' });

      var fitStatus = el('div', { id: 'builder-fit-status' });
      root.appendChild(UI.symbolContext({
        mode: 'editable', id: 'builder-symbol-context', input: symInput,
        label: 'Builder symbol', commitLabel: 'Load',
        onCommit: function () { symInput.dispatchEvent(new Event('change')); }
      }));
      root.appendChild(el('div', { class: 'card' },
        el('div', { class: 'builder-bar' },
          el('div', { class: 'field' }, el('label', {}, 'Qty'), qtyIn),
          el('div', { class: 'field builder-bar-grow' }, el('label', {}, 'Structure'), tplSel),
          el('div', { class: 'field builder-bar-end' }, el('div', { class: 'btn-row', style: 'margin:0' }, addBtn, clearBtn))),
        el('div', { class: 'builder-bar', style: 'margin-top:8px' },
          limitsFields(false),
          el('div', { class: 'field builder-bar-end' },
            el('button', { class: 'btn btn-sm btn-secondary', id: 'builder-fit', title: 'Engine re-picks strikes/size so the limits hold',
              onclick: function () { fitToLimits(fitStatus); } }, 'Fit to my limits'))),
        fitStatus));
      // Depth on demand, even at Expert: what the structure does and what each leg is FOR —
      // collapsed by default so the terminal stays naked until asked.
      var eduHost = el('div', { id: 'builder-edu' });
      function renderEducation() {
        eduHost.innerHTML = '';
        var t = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
        var g = t && Learn.STRATEGY_GUIDE[t.family];
        if (g) {
          eduHost.appendChild(UI.expandable('About this structure — how it wins, loses, and surprises', function () {
            return el('div', {},
              el('p', {}, el('i', {}, g.story)),
              el('ol', {}, (g.how || []).map(function (step) { return el('li', {}, step); })),
              el('div', { class: 'fact-row' }, el('span', {}, el('b', { class: 'gain' }, 'You win when: '), g.win)),
              el('div', { class: 'fact-row' }, el('span', {}, el('b', { class: 'loss' }, 'You lose when: '), g.lose)),
              el('p', {}, el('b', {}, 'Easy to miss: '), g.watch));
          }));
        }
        if (st.legs.length) {
          eduHost.appendChild(UI.expandable('What each leg is for', function () {
            return el('div', {}, st.legs.map(function (l, i) {
              return el('div', { class: 'fact-row' },
                el('span', {}, el('b', {}, 'Leg ' + (i + 1) + ': '), legStory(l)));
            }));
          }));
        }
      }
      var legsCard = el('div', { class: 'card' }, UI.cardHeader('Legs'), eduHost, legsHost);
      root.appendChild(el('div', { class: 'builder-cols' }, legsCard, panel));

      var legPop = null;
      function hideLegPop() { if (legPop) { legPop.remove(); legPop = null; } }
      async function legMarket(l) {
        if (l.type === 'STOCK' || !l.expiration || !l.strike) return null;
        var chain = await ensureChain(l.expiration);
        var rows = l.type === 'CALL' ? (chain.calls || []) : (chain.puts || []);
        return rows.find(function (q) { return parseFloat(q.strike) === parseFloat(l.strike); }) || null;
      }
      function numOr(v, d) { return v === null || v === undefined ? '—' : Number(v).toFixed(d); }
      async function showLegPop(row, l) {
        var q = await legMarket(l);
        hideLegPop();
        if (!q || !row.isConnected) return;
        legPop = el('div', { class: 'leg-pop' },
          el('div', { class: 'tt-title' }, (l.action === 'BUY' ? 'Buy' : 'Sell') + ' ' + l.ratio + '× ' + l.strike + ' ' + l.type.toLowerCase() + ' · ' + l.expiration),
          el('div', { class: 'tt-line' }, 'Bid ' + (q.bid || '—') + ' · Ask ' + (q.ask || '—') + ' · IV ' + (q.iv ? (q.iv * 100).toFixed(0) + '%' : '—')),
          el('div', { class: 'tt-line' }, 'Δ ' + numOr(q.delta, 2) + ' · Γ ' + numOr(q.gamma, 4) + ' · Θ ' + numOr(q.theta, 3) + ' · V ' + numOr(q.vega, 3)),
          el('div', { class: 'tt-line' }, 'OI ' + (q.openInterest !== null && q.openInterest !== undefined ? q.openInterest : '—')
            + ' · Vol ' + (q.volume !== null && q.volume !== undefined ? q.volume : '—')));
        document.body.appendChild(legPop);
        var r = row.getBoundingClientRect();
        legPop.style.top = (r.bottom + window.scrollY + 6) + 'px';
        legPop.style.left = Math.max(8, Math.min(r.left + window.scrollX, window.innerWidth - 300)) + 'px';
      }

      async function legRow(l, idx) {
        var row = el('div', { class: 'leg-row' + (st.excluded[idx] ? ' leg-off' : ''), 'data-leg': String(idx) });
        var onToggle = el('input', { type: 'checkbox', class: 'leg-on',
          title: 'Include this leg — untick to see the position without it',
          checked: st.excluded[idx] ? null : '' });
        onToggle.addEventListener('change', function () {
          if (onToggle.checked) delete st.excluded[idx]; else st.excluded[idx] = true;
          row.classList.toggle('leg-off', !onToggle.checked);
          remember(); schedulePreview();
        });
        var action = el('select', { class: 'leg-action' },
          el('option', { value: 'BUY', selected: l.action === 'BUY' ? '' : null }, 'Buy'),
          el('option', { value: 'SELL', selected: l.action === 'SELL' ? '' : null }, 'Sell'));
        var type = el('select', { class: 'leg-type' },
          ['CALL', 'PUT', 'STOCK'].map(function (t) {
            return el('option', { value: t, selected: l.type === t ? '' : null }, t === 'STOCK' ? '100 sh' : t.toLowerCase());
          }));
        var exp = el('select', { class: 'leg-exp' },
          expirations.map(function (d) { return el('option', { value: d, selected: l.expiration === d ? '' : null }, d); }));
        var strike = el('select', { class: 'leg-strike' });
        var ratio = el('input', { class: 'leg-ratio', type: 'number', min: '1', max: '10', value: String(l.ratio || 1) });
        var mkt = el('span', { class: 'leg-mkt muted mono' }, '…');

        async function refreshMkt() {
          if (l.type === 'STOCK') { mkt.textContent = ''; return; }
          var q = await legMarket(l);
          mkt.textContent = q ? (q.bid || '—') + '/' + (q.ask || '—') + '  Δ' + numOr(q.delta, 2) + '  Θ' + numOr(q.theta, 3) : 'no market';
        }
        async function fillStrikes() {
          if (l.type === 'STOCK') return;
          var chain = await ensureChain(l.expiration);
          var ks = ((l.type === 'CALL' ? chain.calls : chain.puts) || []).map(function (q) { return q.strike; });
          strike.innerHTML = '';
          ks.forEach(function (k) {
            strike.appendChild(el('option', { value: String(k), selected: l.strike && parseFloat(k) === parseFloat(l.strike) ? '' : null }, String(k)));
          });
          if (!l.strike && ks.length) l.strike = String(ks[Math.floor(ks.length / 2)]);
          else if (l.strike && strike.value) l.strike = strike.value;
          await refreshMkt();
        }
        function syncStockMode() {
          var isStock = l.type === 'STOCK';
          exp.style.display = isStock ? 'none' : '';
          strike.style.display = isStock ? 'none' : '';
          if (isStock) { l.strike = null; l.expiration = null; }
          else if (!l.expiration) { l.expiration = expirations[Math.min(2, expirations.length - 1)]; exp.value = l.expiration; }
        }
        action.addEventListener('change', function () { l.action = action.value; remember(); schedulePreview(); });
        type.addEventListener('change', async function () {
          l.type = type.value; syncStockMode();
          if (l.type !== 'STOCK') { l.strike = null; await fillStrikes(); } else { mkt.textContent = ''; }
          remember(); schedulePreview();
        });
        exp.addEventListener('change', async function () {
          l.expiration = exp.value; l.strike = null;
          await fillStrikes(); remember(); schedulePreview();
        });
        strike.addEventListener('change', async function () {
          l.strike = strike.value; await refreshMkt(); remember(); schedulePreview();
        });
        ratio.addEventListener('change', function () {
          l.ratio = Math.max(1, Math.min(10, parseInt(ratio.value, 10) || 1));
          ratio.value = String(l.ratio); remember(); schedulePreview();
        });
        var remove = el('button', {
          class: 'btn btn-sm btn-secondary leg-remove', type: 'button', title: 'Remove this leg',
          onclick: function () {
            hideLegPop();
            st.legs.splice(idx, 1);
            var ex = {};
            Object.keys(st.excluded).forEach(function (k) {
              var ki = parseInt(k, 10);
              if (ki < idx) ex[ki] = true; else if (ki > idx) ex[ki - 1] = true;
            });
            st.excluded = ex;
            st.templateKey = null; tplSel.value = '';
            remember(); renderLegs(); schedulePreview();
          }
        }, '✕');
        row.appendChild(el('div', { class: 'leg-controls' }, onToggle, action, type, exp, strike, ratio, mkt, remove));
        var hoverT = null;
        row.addEventListener('mouseenter', function () { hoverT = setTimeout(function () { showLegPop(row, l); }, 150); });
        row.addEventListener('mouseleave', function () { if (hoverT) clearTimeout(hoverT); hideLegPop(); });
        syncStockMode();
        if (l.type !== 'STOCK') await fillStrikes();
        return row;
      }
      async function renderLegs() {
        hideLegPop();
        renderEducation();
        legsHost.innerHTML = '';
        if (!st.legs.length) {
          legsHost.appendChild(UI.emptyState('No legs', 'Pick a structure or add legs by hand.'));
          return;
        }
        for (var i = 0; i < st.legs.length; i++) legsHost.appendChild(await legRow(st.legs[i], i));
      }

      var previewTimer = null, previewSeq = 0;
      function schedulePreview() {
        if (previewTimer) clearTimeout(previewTimer);
        previewTimer = setTimeout(runPreview, 300);
      }
      function panelEmpty() {
        panel.innerHTML = '';
        panel.appendChild(UI.cardHeader('Position'));
        panel.appendChild(UI.emptyState('No active legs', 'Add or re-include a leg.'));
      }
      async function runPreview() {
        var legsNow = activeLegs();
        if (!wireLegs(legsNow).length) { panelEmpty(); return; }
        var seq = ++previewSeq;
        panel.classList.add('updating');
        try {
          var res = await previewLegs(legsNow);
          if (seq !== previewSeq) return;
          var dragIdx = st.legs.map(function (_, k) { return k; })
            .filter(function (k) { return !st.excluded[k]; });
          var dragHandles = await strikeHandles(dragIdx, function () { onLegsReplaced(); });
          if (seq !== previewSeq) return;
          renderPanelExpert(res.preview, res.guardrails, dragHandles);
        } catch (e) {
          if (seq !== previewSeq) return;
          panel.innerHTML = '';
          panel.appendChild(UI.cardHeader('Position'));
          panel.appendChild(alertBox('danger', 'Could not price', [e.message]));
        } finally {
          panel.classList.remove('updating');
        }
      }
      function renderPanelExpert(p, guard, handles) {
        panel.innerHTML = '';
        var offCount = Object.keys(st.excluded).length;
        panel.appendChild(UI.cardHeader('Position',
          offCount ? el('span', { class: 'badge badge-caution' }, offCount + ' LEG OFF') : null));
        var lc = limitChips(p);
        if (lc) panel.appendChild(lc);
        renderVerdictAndStats(panel, p, guard, false, handles);
        var g = netGreeks(p);
        if (g) {
          panel.appendChild(el('div', { class: 'chip-row' },
            chip('Net Δ sh', g.delta.toFixed(1)),
            chip('Γ sh/$', g.gamma.toFixed(2)),
            chip('Θ $/day', fmtMoney(Math.round(g.theta * 100))),
            chip('Vega $/pt', fmtMoney(Math.round(g.vega * 100)))));
        }
        panel.appendChild(el('div', { class: 'btn-row' },
          el('button', {
            class: 'btn', id: 'builder-review', disabled: p.ok ? null : '',
            title: p.ok ? null : 'Blocked — see reasons above',
            onclick: handoff
          }, 'Review & place →')));
      }
      function netGreeks(p) {
        if (!p.legs || !p.legs.length) return null;
        var out = { delta: 0, gamma: 0, theta: 0, vega: 0 }, any = false;
        p.legs.forEach(function (m) {
          var sign = m.action === 'SELL' ? -1 : 1;
          if (m.type === 'STOCK') { out.delta += sign * 100 * (m.ratio || 1) * st.qty; any = true; return; }
          if (m.delta === null || m.delta === undefined) return;
          any = true;
          var mult = (m.ratio || 1) * st.qty * 100;
          out.delta += sign * m.delta * mult;
          out.gamma += sign * (m.gamma || 0) * mult;
          out.theta += sign * (m.theta || 0) * mult;
          out.vega += sign * (m.vega || 0) * mult;
        });
        return any ? out : null;
      }

      tplSel.addEventListener('change', async function () {
        if (!tplSel.value) return;
        st.templateKey = tplSel.value;
        st.legs = await buildFromTemplate(tplSel.value);
        st.excluded = {};
        remember();
        await renderLegs();
        schedulePreview();
      });
      addBtn.addEventListener('click', async function () {
        st.legs.push(leg('BUY', 'CALL', null, expirations[Math.min(2, expirations.length - 1)] || null));
        st.templateKey = null; tplSel.value = '';
        remember(); await renderLegs(); schedulePreview();
      });
      symInput.addEventListener('change', async function () {
        var sym = symInput.value.trim().toUpperCase();
        if (!sym || sym === st.symbol) return;
        st.symbol = sym; App.state.lastRecommendSymbol = sym;
        st.legs = []; st.excluded = {};
        remember();
        try {
          await loadSymbol();
          if (st.templateKey) st.legs = await buildFromTemplate(st.templateKey);
          await renderLegs();
          schedulePreview();
        } catch (e) {
          legsHost.innerHTML = '';
          legsHost.appendChild(alertBox('danger', 'No data for ' + sym, [e.message]));
          panelEmpty();
        }
      });
      qtyIn.addEventListener('change', function () {
        st.qty = Math.max(1, Math.min(100, parseInt(qtyIn.value, 10) || 1));
        qtyIn.value = String(st.qty);
        remember(); schedulePreview();
      });

      schedulePreviewHook = schedulePreview;
      onLegsReplaced = async function () { await renderLegs(); schedulePreview(); };
      if (!expirations.length) {
        legsHost.appendChild(UI.emptyState(st.symbol + ' has no listed options', 'Pick an optionable symbol (try AAPL or SPY).'));
        panelEmpty();
      } else {
        await renderLegs();
        if (wireLegs(activeLegs()).length) schedulePreview(); else panelEmpty();
      }
      remember();
    }

    // ---- Shared panel body ----
    function renderVerdictAndStats(hostEl, p, guard, beginnerWording, handles) {
      var an = p.analytics || {};
      if (an.verdict && an.verdictReason) {
        hostEl.appendChild(el('div', { class: 'muted small verdict-line verdict-' + an.verdict },
          (an.verdict === 'favorable' ? '\u2713 ' : an.verdict === 'unfavorable' ? '\u2715 ' : '! ') + an.verdictReason));
      }
      if (!p.ok) {
        hostEl.appendChild(alertBox('danger', beginnerWording ? 'This position would be refused' : 'BLOCKED', p.blockReasons));
      } else if (guard && guard.level === 'WARN') {
        hostEl.appendChild(alertBox('warn', beginnerWording ? 'Allowed, with cautions' : 'WARN', guard.warnings));
      } else {
        hostEl.appendChild(alertBox('ok', beginnerWording ? 'Passes the safety screens' : 'ALLOW', null));
      }
      var credit = p.entryNetPremiumCents;
      var unlimited = !p.ok && p.maxLossCents === 0
        && (p.blockReasons || []).some(function (r) { return /undefined|unlimited/i.test(r); });
      hostEl.appendChild(el('div', { class: 'grid grid-2 panel-stats' },
        stat(credit >= 0 ? 'You collect' : 'You pay', fmtMoney(Math.abs(credit)),
          beginnerWording ? (credit >= 0 ? 'Premium received now — yours to keep only if the position works out.' : 'Cash leaving your account now, before fees.') : null),
        stat('Most you can lose',
          unlimited ? el('span', { class: 'loss' }, 'UNLIMITED')
            : p.ok || p.maxLossCents > 0 ? el('span', { class: 'loss' }, fmtMoney(p.maxLossCents))
            : el('span', { class: 'muted' }, '—'),
          beginnerWording ? 'The worst case at expiration, including every leg.' : null),
        stat('Most you can make', p.maxProfitCents === null || p.maxProfitCents === undefined
          ? el('span', { class: 'gain' }, 'Uncapped') : el('span', { class: 'gain' }, fmtMoney(p.maxProfitCents)),
          beginnerWording ? 'The best case at expiration.' : null),
        stat(UI.term('pop', beginnerWording ? 'Chance of any profit' : 'POP'),
          p.popEntry !== null && p.popEntry !== undefined ? fmtPct(p.popEntry) : '—',
          beginnerWording ? 'Model estimate under current volatility. Not a promise.' : null),
        p.assignmentProb !== null && p.assignmentProb !== undefined
          ? stat(UI.term('assignment', beginnerWording ? 'Assignment odds' : 'Assign'), fmtPct(p.assignmentProb),
              beginnerWording ? 'Chance any short strike finishes in the money and shares change hands.' : null) : null,
        stat('Fees', fmtMoney(p.feesOpenCents), null)));
      if (p.breakevens && p.breakevens.length) {
        hostEl.appendChild(el('div', { class: 'chip-row' },
          chip(UI.term('breakeven', 'Breakevens'), p.breakevens.map(function (b) { return '$' + parseFloat(b).toFixed(2); }).join(' / '))));
      }
      hostEl.appendChild(el('div', { class: 'chip-row' },
        chip('Buying power after', fmtMoney(p.buyingPowerAfterCents)),
        p.reserveCents ? chip(UI.term('reserve', 'Set aside'), fmtMoney(p.reserveCents),
          'Cash held while the trade is open. For credit trades this is the full width between strikes (it already contains the worst case); for debit trades the premium has left your account, so nothing extra is held. It is released when you close.') : null));
      // One line kills the "three different risk numbers" confusion: everything here is a TOTAL.
      hostEl.appendChild(el('div', { class: 'muted small' },
        'All figures are totals for this exact position and quantity. "Set aside" and "most you can lose" differ on credit trades because the set-aside is the gross width — the worst case lives inside it.'));
      var prob = an.probabilityMap;
      if (prob && prob.pAnyProfit !== undefined && p.ok) {
        hostEl.appendChild(el('div', { class: 'chip-row', id: 'builder-prob-chips' },
          chip(beginnerWording ? 'Any profit' : 'P(profit)', Math.round(prob.pAnyProfit * 100) + '%'),
          chip(beginnerWording ? 'Full win' : 'P(max profit)', Math.round(prob.pMaxProfit * 100) + '%'),
          chip(beginnerWording ? 'Worst case' : 'P(max loss)', Math.round(prob.pMaxLoss * 100) + '%'),
          prob.cvar95Cents !== undefined && prob.cvar95Cents !== null
            ? chip('CVaR95', UI.fmtMoneyCompact(prob.cvar95Cents)) : null));
      }
      if (p.payoff && p.payoff.length > 1) {
        if (handles && handles.length) {
          hostEl.appendChild(el('p', { class: 'muted', style: 'margin:6px 0 2px; font-size:12.5px' },
            'Drag a strike marker on the chart \u2014 watch the worst case, best case and odds move with it.'));
        }
        var em = an.expectedMove;
        hostEl.appendChild(UI.payoffChart(p.payoff, {
          breakevens: p.breakevens, spot: p.underlyingCents ? p.underlyingCents / 100 : null,
          expectedMove: em ? { low: em.lowCents / 100, high: em.highCents / 100 } : undefined,
          handles: handles || null
        }));
      } else if (p.legs && p.legs.length) {
        hostEl.appendChild(explain('Mixed expirations: a single at-expiry payoff line does not exist — the position\u2019s value depends on volatility after the near leg dies. Below: a SIMULATED P&L range instead (Monte-Carlo, honestly labeled MODELED).'));
        // The sim engine prices calendars/diagonals along paths — point it at the gap the
        // static payoff curve honestly refuses to fill.
        (function simFan() {
          var slot = el('div', { class: 'fan-slot' }, UI.spinner('Simulating the mixed-expiration range\u2026'));
          hostEl.appendChild(slot);
          var today = Date.now();
          var simLegs = [];
          for (var i = 0; i < p.legs.length; i++) {
            var lg = p.legs[i];
            if (!lg.expiration || !lg.strike) { slot.remove(); return; }
            var days = Math.max(1, Math.round((new Date(lg.expiration) - today) / 86400000 * 5 / 7));
            simLegs.push({ action: lg.action, type: lg.type, strike: parseFloat(lg.strike), expiryDay: days, ratio: lg.ratio || 1 });
          }
          var nearDays = Math.min.apply(null, simLegs.map(function (l) { return l.expiryDay; }));
          API.post('/api/sim/strategy', { symbol: st.symbol, legs: simLegs, qty: st.qty || 1,
            spec: { model: 'GBM', shape: 'CHOP', horizonDays: Math.max(2, nearDays), stepsPerDay: 4,
              driftAnnual: 0, volAnnual: 0, jumpsPerYear: 0, jumpMean: 0, jumpVol: 0, tailNu: 6,
              heston: null, seed: 4242, paths: 200 } })
            .then(function (r) {
              slot.innerHTML = '';
              slot.appendChild(window.Scenario ? Scenario.pnlView(r, beginnerWording ? 'beginner' : 'expert')
                : el('div', { class: 'muted small' }, 'Simulated range unavailable.'));
            })
            .catch(function () { slot.remove(); });
        })();
      }
      if (p.ok && p.warnings && p.warnings.length) {
        hostEl.appendChild(alertBox('warn', 'Heads up', p.warnings));
      }
    }
  }

  window.Builder = { render: render, TEMPLATES: TEMPLATES, WIZARD: WIZARD };
})();
