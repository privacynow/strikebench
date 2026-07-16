/*
 * StrikeBench strategy builder — two genuinely different tools, one honest engine:
 *   BEGINNER: a question-driven wizard (goal -> shape -> leg-BY-leg walkthrough). Every
 *   leg arrives with a plain-language story and its measured impact on cost, worst case,
 *   and odds — the payoff chart morphs as the position grows. A browsable catalog of every
 *   structure (with payoff-shape sketches and guides) is one tap away.
 *   EXPERT: a naked terminal — structure quick-pick, editable leg grid with inline market
 *   data, per-leg include/exclude toggles for instant impact analysis, net greeks, and a
 *   sticky analytics panel.
 * Mounted by a Plan's Strategy stage. The owner contract keeps in-progress state
 * inside that Plan; pricing and fit requests are supplied by the Plan workspace.
 */
(function () {
  'use strict';

  var el = UI.el, fmtMoney = UI.fmtMoney, fmtPct = UI.fmtPct, explain = UI.explain,
      chip = UI.chip, stat = UI.stat, alertBox = UI.alertBox, icon = UI.icon;
  var TRADE_GOALS = ['DIRECTIONAL', 'INCOME', 'HEDGE', 'ACQUIRE', 'EXIT'];
  function tradeGoal(value, fallback) {
    var goal = String(value || '').toUpperCase();
    return TRADE_GOALS.indexOf(goal) >= 0 ? goal : (fallback || 'DIRECTIONAL');
  }

  function leg(action, type, strike, expiration, ratio) {
    return { action: action, type: type, strike: strike, expiration: expiration, ratio: ratio || 1 };
  }
  function stockLeg(action, ratio) {
    return { action: action, type: 'STOCK', strike: null, expiration: null, ratio: ratio || 1 };
  }

  // The server owns identity, names, grouping, wording and payoff glyphs. This module owns
  // only the mechanics that snap a named construction to the currently loaded chain.
  var TEMPLATE_BUILDERS = {
    LONG_CALL: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near)]; },
    DEBIT_CALL_SPREAD: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; },
    CREDIT_PUT_SPREAD: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near), leg('BUY', 'PUT', c.pick(c.near, -4), c.near)]; },
    CASH_SECURED_PUT: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; },
    BUY_WRITE: function (c) { return [stockLeg('BUY', 1), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; },
    RISK_REVERSAL: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -3), c.near), leg('BUY', 'CALL', c.pick(c.near, 3), c.near)]; },
    LONG_PUT: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 0), c.near)]; },
    DEBIT_PUT_SPREAD: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 0), c.near), leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; },
    CREDIT_CALL_SPREAD: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 2), c.near), leg('BUY', 'CALL', c.pick(c.near, 4), c.near)]; },
    IRON_CONDOR: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near), leg('BUY', 'PUT', c.pick(c.near, -4), c.near), leg('SELL', 'CALL', c.pick(c.near, 2), c.near), leg('BUY', 'CALL', c.pick(c.near, 4), c.near)]; },
    IRON_BUTTERFLY: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, 0), c.near), leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.near, -3), c.near), leg('BUY', 'CALL', c.pick(c.near, 3), c.near)]; },
    CALENDAR_CALL: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'CALL', c.pick(c.far, 0), c.far)]; },
    CALENDAR_PUT: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.far, 0), c.far)]; },
    DIAGONAL_CALL: function (c) { return [leg('BUY', 'CALL', c.pick(c.far, 0), c.far), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; },
    DIAGONAL_PUT: function (c) { return [leg('BUY', 'PUT', c.pick(c.far, 0), c.far), leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; },
    LONG_STRADDLE: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.near, 0), c.near)]; },
    LONG_STRANGLE: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 2), c.near), leg('BUY', 'PUT', c.pick(c.near, -2), c.near)]; },
    CALL_BACKSPREAD: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('BUY', 'CALL', c.pick(c.near, 2), c.near, 2)]; },
    PUT_BACKSPREAD: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, 0), c.near), leg('BUY', 'PUT', c.pick(c.near, -2), c.near, 2)]; },
    LONG_CALL_BUTTERFLY: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, -2), c.near), leg('SELL', 'CALL', c.pick(c.near, 0), c.near, 2), leg('BUY', 'CALL', c.pick(c.near, 2), c.near)]; },
    LONG_PUT_BUTTERFLY: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 2), c.near), leg('SELL', 'PUT', c.pick(c.near, 0), c.near, 2), leg('BUY', 'PUT', c.pick(c.near, -2), c.near)]; },
    MARRIED_PUT: function (c) { return [stockLeg('BUY', 1), leg('BUY', 'PUT', c.pick(c.near, -2), c.near)]; },
    COLLAR: function (c) { return [stockLeg('BUY', 1), leg('BUY', 'PUT', c.pick(c.near, -2), c.near), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; },
    PMCC: function (c) { return [leg('BUY', 'CALL', c.pick(c.far, -4), c.far), leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; },
    SYNTHETIC_LONG: function (c) { return [leg('BUY', 'CALL', c.pick(c.near, 0), c.near), leg('SELL', 'PUT', c.pick(c.near, 0), c.near)]; },
    SYNTHETIC_SHORT: function (c) { return [leg('BUY', 'PUT', c.pick(c.near, 0), c.near), leg('SELL', 'CALL', c.pick(c.near, 0), c.near)]; },
    SHORT_STRADDLE: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 0), c.near), leg('SELL', 'PUT', c.pick(c.near, 0), c.near)]; },
    SHORT_STRANGLE: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 2), c.near), leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; },
    NAKED_CALL: function (c) { return [leg('SELL', 'CALL', c.pick(c.near, 2), c.near)]; },
    NAKED_PUT: function (c) { return [leg('SELL', 'PUT', c.pick(c.near, -2), c.near)]; }
  };
  var TEMPLATES = [];

  function wireLegs(legs) {
    return (legs || [])
      .filter(function (l) { return l.type === 'STOCK' || (l.strike && l.expiration); })
      .map(function (l) {
        return { action: l.action, type: l.type, strike: l.type === 'STOCK' ? null : String(l.strike),
                 expiration: l.type === 'STOCK' ? null : l.expiration, ratio: l.ratio || 1,
                 entryPrice: l.entryPrice || null, multiplier: Number(l.multiplier || 100),
                 positionEffect: 'OPEN' };
      });
  }

  /** Normalize the live Builder state into the one position contract consumed by Outcomes/Decide. */
  function prepareTicket(owner, context) {
    if (!owner) throw new Error('Builder state must be owned by a Plan.');
    context = context || {};
    var st = owner.builderForm;
    if (!st || !st.symbol || !st.legs || !st.legs.length) return null;
    var active = st.legs.filter(function (_, i) { return !(st.excluded || {})[i]; });
    var legs = wireLegs(active);
    if (!legs.length) return null;
    var tpl = st.templateKey ? TEMPLATES.find(function (t) { return t.key === st.templateKey; }) : null;
    var contextGoal = tradeGoal(context.goal || st.goal,
      tradeGoal(tpl && tpl.primaryIntent, tradeGoal(App.context.goal(), 'DIRECTIONAL')));
    owner.ticket = {
      world: App.state.world || 'observed', symbol: st.symbol, custom: true, customFor: st.symbol,
      customFamily: tpl && tpl.family ? tpl.family : null,
      legs: legs, qty: st.qty || 1, step: 6, intent: contextGoal,
      thesis: context.thesis || App.context.thesis('neutral'),
      horizon: context.horizon || App.context.horizon('month'),
      outcomeBasisHint: tpl ? 'history' : 'scenario'
    };
    return owner.ticket;
  }

  /** Bring a screened/reviewed package back into Structure without changing its contracts. */
  function adoptTicket(ticket, owner, context) {
    if (!ticket || !ticket.symbol) return null;
    if (!owner) throw new Error('Builder state must be owned by a Plan.');
    context = context || {};
    var family = ticket.candidate && ticket.candidate.strategy || ticket.customFamily || null;
    var tpl = family && TEMPLATES.find(function (t) { return t.family === family; });
    var sourceLegs = ticket.legs && ticket.legs.length ? ticket.legs
      : ticket.candidate && ticket.candidate.legs || [];
    var adoptedGoal = tradeGoal(context.goal || ticket.candidate && ticket.candidate.intent || ticket.intent,
      tradeGoal(App.context.goal(), 'DIRECTIONAL'));
    owner.builderForm = {
      symbol: ticket.symbol, qty: ticket.qty || ticket.candidate && ticket.candidate.qty || 1,
      goal: adoptedGoal, templateKey: tpl ? tpl.key : null, step: 4, legIdx: 0, excluded: {},
      legs: sourceLegs.map(function (l) {
        return { action: l.action, type: l.stock ? 'STOCK' : l.type,
          strike: l.stock || l.type === 'STOCK' ? null : String(l.strike),
          expiration: l.stock || l.type === 'STOCK' ? null : l.expiration, ratio: l.ratio || 1 };
      }), limits: {}, exposureResult: null
    };
    return owner.builderForm;
  }

  function applyCatalog(doc) {
    var entries = doc && doc.templates || [];
    var families = doc && doc.catalog || [];
    var byKey = {};
    var byFamily = {};
    entries.forEach(function (m) { byKey[m.key] = m; });
    families.forEach(function (m) { byFamily[m.name] = m; });
    var missingServer = Object.keys(TEMPLATE_BUILDERS).filter(function (key) { return !byKey[key]; });
    var missingMechanics = entries.filter(function (m) { return !TEMPLATE_BUILDERS[m.key]; }).map(function (m) { return m.key; });
    if (missingServer.length || missingMechanics.length) {
      throw new Error('Strategy catalog/build mechanics mismatch: server missing [' + missingServer.join(', ')
        + ']; client missing [' + missingMechanics.join(', ') + ']');
    }
    TEMPLATES.splice(0, TEMPLATES.length);
    entries.forEach(function (m) {
      TEMPLATES.push({
        key: m.key, family: m.family, group: m.category, name: m.display,
        shape: m.payoffShape, blurb: m.summary, risky: !!m.blockedByDefault,
        composite: !!m.composite,
        primaryIntent: byFamily[m.family] && byFamily[m.family].primaryIntent,
        build: TEMPLATE_BUILDERS[m.key]
      });
    });
  }

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
            { label: 'Boldly — a put pays for the call', blurb: 'Risk reversal: substantial broker reserve if wrong', tpl: 'RISK_REVERSAL' },
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

  async function render(root, options) {
    options = options || {};
    if (!options.state) throw new Error('Builder must be mounted by a Plan.');
    var owner = options.state;
    var lockedSymbol = options.lockedSymbol ? String(options.lockedSymbol).toUpperCase() : null;
    var lockedGoal = options.lockedGoal ? tradeGoal(options.lockedGoal) : null;
    var level = Learn.currentLevel();
    var saved = owner.builderForm || {};
    var st = {
      // An in-progress build owns its symbol; an EMPTY builder follows the working
      // symbol from Ideas/Research ("I chose another stock" must not reload the old one)
      symbol: (lockedSymbol || (saved.legs && saved.legs.length ? saved.symbol : null)
        || App.context.symbol() || saved.symbol || 'AAPL').toUpperCase(),
      qty: saved.qty || 1,
      goal: lockedGoal || (saved.goal === 'BROWSE' ? 'BROWSE'
        : ((saved.legs && saved.legs.length ? saved.goal : App.context.goal()) || saved.goal || null)),
      templateKey: saved.templateKey || null,
      step: saved.step || 1,
      legIdx: saved.legIdx || 0,
      wizNode: saved.wizNode || null,   // rehydrate the mid-question wizard node across re-renders/level flips
      legs: saved.legs ? saved.legs.map(function (l) { return Object.assign({}, l); }) : [],
      excluded: saved.excluded || {},
      limits: saved.limits || {},
      exposureTargetDollars: saved.exposureTargetDollars || 50000,
      exposureResult: saved.exposureResult || null
    };
    // Context is the first stage of the shared workflow. When it is already complete,
    // Structure should begin with the shaping question instead of asking for the same
    // goal a second time. A direct Builder visit with no goal still starts at step 1,
    // and the Back control remains the explicit way to revise a carried goal.
    if (!saved.step && !st.legs.length && TRADE_GOALS.indexOf(st.goal) >= 0) st.step = 2;
    // A Plan's Beginner Strategy entry can open the visual catalog directly. The locked Plan goal
    // still owns the finished package; BROWSE changes only how the user finds a structure.
    if (options.startInCatalog && !st.legs.length) { st.goal = 'BROWSE'; st.step = 2; }
    // Cross-level coherence: a position built in the Expert terminal (or handed off) must not vanish
    // into Beginner's step-1 goal chooser and get overwritten — land it on Beginner's recap instead.
    if (level === 'beginner' && st.legs.length && st.step < 3) { st.step = 4; }
    function remember() {
      owner.builderForm = st;
      if (typeof options.onStateChange === 'function') options.onStateChange(st);
    }

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

    function familyLabel() {
      var t = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
      return t ? t.family : 'CUSTOM';
    }
    async function previewLegs(legs) {
      var wired = wireLegs(legs);
      if (!wired.length) return null;
      return API.post('/api/trades/preview', {
        symbol: st.symbol, strategy: familyLabel(), qty: st.qty, legs: wired,
        source: 'BUILDER', fillNature: 'PROPOSED'
      });
    }
    function activeLegs() {
      return st.legs.filter(function (_, i) { return !st.excluded[i]; });
    }
    function syntheticDirection() {
      return st.templateKey === 'SYNTHETIC_LONG' ? 'long'
        : st.templateKey === 'SYNTHETIC_SHORT' ? 'short' : null;
    }
    function renderExposureResult(host, result) {
      host.innerHTML = '';
      if (!result || !result.contracts) {
        host.appendChild(alertBox('warn', result && result.notes && result.notes[0]
          || 'This exposure could not be sized in the active market.'));
        return;
      }
      host.appendChild(el('div', { class: 'chip-row' },
        UI.evidenceBadge(result.evidence),
        chip('Contracts', String(result.contracts)),
        chip('Delta exposure', UI.pnlSpan(result.deltaExposureCents)),
        chip('Equivalent shares would cost', fmtMoney(result.shareCostCents))));
      (result.notes || []).forEach(function (note) {
        host.appendChild(el('div', { class: 'muted small' }, note));
      });
    }
    /** Synthetic exposure sizing belongs to the selected Builder structure, not a separate tool. */
    function exposureSizer(onApplied) {
      var direction = syntheticDirection();
      if (!direction) return null;
      var target = el('input', { type: 'number', id: 'builder-exposure-target', min: '1',
        max: '100000000', step: '1000',
        value: String(st.exposureTargetDollars) });
      var output = el('div', { class: 'builder-exposure-output', id: 'builder-exposure-output' });
      var run = el('button', { class: 'btn btn-sm btn-secondary', id: 'builder-size-exposure' },
        'Size contracts');
      var savedResult = st.exposureResult && st.exposureResult.templateKey === st.templateKey
        ? st.exposureResult.data : null;
      if (savedResult) renderExposureResult(output, savedResult);
      run.addEventListener('click', async function () {
        st.exposureTargetDollars = Math.max(1, parseFloat(target.value) || 1);
        target.value = String(st.exposureTargetDollars);
        run.disabled = true;
        output.innerHTML = '';
        output.appendChild(UI.spinner('Sizing the selected synthetic…'));
        try {
          var result = await API.post('/api/builder/exposure', {
            symbol: st.symbol,
            targetExposureCents: Math.round(st.exposureTargetDollars * 100),
            bullish: direction === 'long'
          });
          st.exposureResult = { templateKey: st.templateKey, data: result };
          if (result.contracts) st.qty = result.contracts;
          remember();
          renderExposureResult(output, result);
          if (result.contracts && typeof onApplied === 'function') onApplied(result);
        } catch (error) {
          output.innerHTML = '';
          output.appendChild(alertBox('danger', 'Could not size this exposure',
            [String(error && error.message || error)]));
        } finally {
          run.disabled = false;
        }
      });
      return el('section', { class: 'builder-exposure-sizer', id: 'builder-exposure-sizer' },
        el('div', { class: 'field-label' },
          direction === 'long' ? 'Size this synthetic long by exposure' : 'Size this synthetic short by exposure'),
        explain(direction === 'long'
          ? 'Name the dollar exposure you would otherwise hold in shares. StrikeBench converts it to roughly 100-delta option lots; the exact position still goes through the same payoff and risk review.'
          : 'Name the bearish dollar exposure. This structure includes an uncovered short call, so sizing is educational and placement remains blocked as undefined risk.'),
        el('div', { class: 'builder-exposure-controls' },
          el('div', { class: 'field' }, el('label', { for: 'builder-exposure-target' }, 'Target exposure ($)'), target),
          run),
        output);
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
      return UI.field(label, input);
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
        judge(UI.vocabularyText('theoreticalMaxLoss') + ' ≤ ' + fmtMoney(cap), !unbounded && p.maxLossCents > 0 && p.maxLossCents <= cap,
          unbounded ? 'UNLIMITED' : fmtMoney(p.maxLossCents));
      }
      if (expertLimits && v.target) {
        var tgt = Math.round(parseFloat(v.target) * 100);
        var profitKind = UI.profitCeilingKind(familyLabel(), null, p.maxProfitCents, p.legs);
        judge('Profit target ≥ ' + fmtMoney(tgt),
          profitKind === 'uncapped' || (profitKind === 'finite' && p.maxProfitCents >= tgt),
          UI.maxProfitLabel(familyLabel(), null, p.maxProfitCents, false, p.legs));
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

    function assignmentLabel(beginnerWording) {
      if (st.goal === 'ACQUIRE') return beginnerWording ? 'Chance you buy' : 'Assignment: buy';
      if (st.goal === 'EXIT') return beginnerWording ? 'Chance you sell' : 'Assignment: sell';
      return beginnerWording ? 'Assignment odds' : 'Assign';
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
        if (typeof options.fitToLimits !== 'function') {
          throw new Error('This Builder is not attached to a Plan.');
        }
        var body = { strategy: t.family, allow0dte: false };
        if (st.limits.maxLoss) body.maxLossCents = Math.round(parseFloat(st.limits.maxLoss) * 100);
        var f = {};
        if (st.limits.minPop) f.minPop = parseFloat(st.limits.minPop) / 100;
        if (st.limits.maxAssign) f.maxAssignmentProb = parseFloat(st.limits.maxAssign) / 100;
        if (Object.keys(f).length) body.filters = f;
        var fitSeq = ++buildSeq;                 // the fitted legs replace the structure — same supersede rule
        var r = await options.fitToLimits(body);
        if (fitSeq !== buildSeq) return;         // a newer pick/fit superseded this
        statusHost.innerHTML = '';
        var c = r && r.candidate;
        if (!c) {
          var reasons = (r && r.result && r.result.rejected || []).slice(0, 3)
            .map(function (x) { return x.reason || x.blockReasons && x.blockReasons[0]; }).filter(Boolean);
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
      var ticket = prepareTicket(owner, {
        goal: lockedGoal || st.goal,
        thesis: options.thesis,
        horizon: options.horizon
      });
      if (typeof options.onComplete === 'function') options.onComplete(ticket, st);
      else {
        var active = window.PlanStore && PlanStore.active();
        if (active) PlanStore.focus(active, 'STRATEGY');
        else App.navigate('#/research');
      }
    }

    try {
      await loadSymbol();
    } catch (e) {
      // Never a dead end: say what failed, offer the universe one tap away, and retry
      if (lockedSymbol) {
        root.appendChild(el('div', { class: 'card', id: 'builder-load-error' },
          alertBox('danger', 'Could not load ' + st.symbol, [e.message,
            'This Plan keeps its symbol fixed. Restore its quote and option-chain data, then retry.']),
          el('div', { class: 'btn-row' },
            el('button', { class: 'btn', type: 'button', onclick: function () { App.render(); } }, 'Retry'),
            el('a', { class: 'btn btn-secondary', href: '#/data/overview' }, 'Check Data'))));
        return;
      }
      var failInput = el('input', { type: 'text', id: 'builder-symbol', list: 'universe-symbols', value: st.symbol });
      var retryWith = function (sym) {
        st.symbol = (sym || failInput.value.trim() || st.symbol).toUpperCase();
        App.context.selectSymbol(st.symbol);
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
        var names = [lockedGoal ? 'Plan goal' : 'Goal', 'Structure', 'Build it', 'Where you stand'];
        var reachable = [true, !!st.goal, st.legs.length > 0, st.legs.length > 0];
        var why = [null, 'Pick a goal first', 'Choose a structure first', 'Choose a structure first'];
        return el('div', { class: 'wizard-steps builder-wizard-steps' }, names.map(function (n, i) {
          var here = i + 1 === st.step;
          var cls = here ? ' active' : (i + 1 < st.step ? ' done' : '');
          var can = reachable[i] && !here && !(lockedGoal && i === 0);
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
          st.symbol = sym; App.context.selectSymbol(sym);
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
        st.exposureResult = null;
        st.legIdx = 0; st.step = 3;
        repaint();
      }

      function paint() {
        if (!lockedSymbol) {
          var contextInput = symbolInput();
          host.appendChild(UI.symbolContext({
            mode: 'editable', id: 'builder-symbol-context', input: contextInput,
            label: 'Which stock are you building around?', commitLabel: 'Load',
            onCommit: function () { contextInput.dispatchEvent(new Event('change')); }
          }));
        }
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
              lockedGoal ? null : el('div', { class: 'btn-row' },
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
                if (st.wizNode) { st.wizNode = null; }
                else if (!lockedGoal) { st.step = 1; }
                repaint();
              }, disabled: lockedGoal && !st.wizNode ? '' : null }, '← Back'),
              el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { st.wizNode = null; st.goal = 'BROWSE'; repaint(); } }, 'Browse all structures'))));
          return;
        }

        if (st.step === 3) {
          if (!st.legs.length) { st.step = 2; repaint(); return; }
          var n = st.legs.length;
          var i = Math.min(st.legIdx, n - 1);
          var soFar = st.legs.slice(0, i + 1);
          var tSel = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
          var g = tSel && (Learn.STRATEGY_GUIDE[tSel.key] || Learn.STRATEGY_GUIDE[tSel.family]);
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
                impactChip(UI.vocabularyText('theoreticalMaxLoss'), b, p, function (x) {
                  if (!x.ok && (x.blockReasons || []).some(function (r) { return /undefined|unlimited/i.test(r); })) return 'UNLIMITED';
                  return fmtMoney(x.maxLossCents);
                }, true),
                impactChip('Theoretical best case', b, p, function (x) {
                  return UI.maxProfitLabel(familyLabel(), null, x.maxProfitCents, level === 'beginner', x.legs);
                }, false),
                p.popEntry !== null && p.popEntry !== undefined
                  ? impactChip('Chance of any profit', b, p, function (x) {
                      return x.popEntry === null || x.popEntry === undefined ? '—' : fmtPct(x.popEntry);
                    }, false) : null,
                (st.goal === 'ACQUIRE' || st.goal === 'EXIT')
                    && p.assignmentProb !== null && p.assignmentProb !== undefined
                  ? impactChip(assignmentLabel(true), b, p, function (x) {
                      return x.assignmentProb === null || x.assignmentProb === undefined
                        ? '—' : fmtPct(x.assignmentProb);
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
              var expSel = el('select', { class: 'tune-exp', id: 'builder-tune-exp-' + idx }, expirations.map(function (d) {
                return el('option', { value: d, selected: d === l.expiration ? '' : null }, d);
              }));
              var strikeSel = el('select', { class: 'tune-strike', id: 'builder-tune-strike-' + idx });
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
                el('label', { class: 'muted', for: strikeSel.id }, 'strike'), strikeSel,
                el('label', { class: 'muted', for: expSel.id }, 'expires'), expSel));
            });
            return box;
          }),
          el('div', { class: 'form-grid', style: 'margin-top:8px' },
            [UI.field('Contracts (qty)', qtyInput())].concat(limitsFields(true))),
          exposureSizer(function () { repaint(); }),
          el('div', { class: 'btn-row', style: 'margin-top:2px' },
            el('button', { class: 'btn btn-sm btn-secondary', id: 'builder-fit', onclick: function () { fitToLimits(fitStatus); } },
              'Fit to my limits'),
            el('span', { class: 'muted' }, 'The engine re-picks strikes and size so your limits hold — same math as Ideas.')),
          fitStatus,
          el('div', { id: 'bw-panel' }, UI.spinner('Pricing the whole position…')),
          el('div', { class: 'btn-row' },
            el('button', { class: 'btn btn-secondary btn-sm', onclick: function () { st.legIdx = 0; st.step = 3; repaint(); } }, '← Walk the legs again'),
            el('button', { class: 'btn btn-secondary btn-sm', onclick: function () {
              st.step = lockedGoal ? 2 : 1; st.legs = []; st.templateKey = null;
              st.goal = lockedGoal || null; repaint();
            } }, 'Start over'),
            el('button', { class: 'btn', id: 'builder-review', onclick: handoff },
              options.completeLabel || 'Review & place (' + UI.vocabularyText('practice').toLowerCase() + ') →'))));
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
        st.exposureResult = null;
        remember(); renderLegs(); renderExposureSizer(); schedulePreview();
      } }, 'Clear');
      var panel = el('div', { class: 'card builder-panel', id: 'builder-panel' });
      var exposureHost = el('div', { id: 'builder-exposure-host' });

      var fitStatus = el('div', { id: 'builder-fit-status' });
      if (!lockedSymbol) {
        root.appendChild(UI.symbolContext({
          mode: 'editable', id: 'builder-symbol-context', input: symInput,
          label: 'Builder symbol', commitLabel: 'Load',
          onCommit: function () { symInput.dispatchEvent(new Event('change')); }
        }));
      }
      root.appendChild(el('div', { class: 'card' },
        el('div', { class: 'builder-bar' },
          UI.field('Qty', qtyIn),
          UI.field('Structure', tplSel, { className: 'builder-bar-grow' }),
          el('div', { class: 'field builder-bar-end' }, el('div', { class: 'btn-row', style: 'margin:0' }, addBtn, clearBtn))),
        el('div', { class: 'builder-bar', style: 'margin-top:8px' },
          limitsFields(false),
          el('div', { class: 'field builder-bar-end' },
            el('button', { class: 'btn btn-sm btn-secondary', id: 'builder-fit', title: 'Engine re-picks strikes/size so the limits hold',
              onclick: function () { fitToLimits(fitStatus); } }, 'Fit to my limits'))),
        fitStatus,
        exposureHost));
      function renderExposureSizer() {
        exposureHost.innerHTML = '';
        var sizer = exposureSizer(function () {
          qtyIn.value = String(st.qty);
          schedulePreview();
        });
        if (sizer) exposureHost.appendChild(sizer);
      }
      // Depth on demand, even at Expert: what the structure does and what each leg is FOR —
      // collapsed by default so the terminal stays naked until asked.
      var eduHost = el('div', { id: 'builder-edu' });
      function renderEducation() {
        eduHost.innerHTML = '';
        var t = TEMPLATES.find(function (x) { return x.key === st.templateKey; });
        var g = t && (Learn.STRATEGY_GUIDE[t.key] || Learn.STRATEGY_GUIDE[t.family]);
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
        var onToggle = el('input', { type: 'checkbox', class: 'leg-on', 'aria-label': 'Include leg ' + (idx + 1),
          title: 'Include this leg — untick to see the position without it',
          checked: st.excluded[idx] ? null : '' });
        onToggle.addEventListener('change', function () {
          if (onToggle.checked) delete st.excluded[idx]; else st.excluded[idx] = true;
          row.classList.toggle('leg-off', !onToggle.checked);
          remember(); schedulePreview();
        });
        var action = el('select', { class: 'leg-action', 'aria-label': 'Leg ' + (idx + 1) + ' action' },
          el('option', { value: 'BUY', selected: l.action === 'BUY' ? '' : null }, 'Buy'),
          el('option', { value: 'SELL', selected: l.action === 'SELL' ? '' : null }, 'Sell'));
        var type = el('select', { class: 'leg-type', 'aria-label': 'Leg ' + (idx + 1) + ' instrument type' },
          ['CALL', 'PUT', 'STOCK'].map(function (t) {
            return el('option', { value: t, selected: l.type === t ? '' : null }, t === 'STOCK' ? '100 sh' : t.toLowerCase());
          }));
        var exp = el('select', { class: 'leg-exp', 'aria-label': 'Leg ' + (idx + 1) + ' expiration' },
          expirations.map(function (d) { return el('option', { value: d, selected: l.expiration === d ? '' : null }, d); }));
        var strike = el('select', { class: 'leg-strike', 'aria-label': 'Leg ' + (idx + 1) + ' strike' });
        var ratio = el('input', { class: 'leg-ratio', type: 'number', min: '1', max: '10',
          'aria-label': 'Leg ' + (idx + 1) + ' ratio', value: String(l.ratio || 1) });
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
        function markCustom() {
          st.templateKey = null;
          st.exposureResult = null;
          tplSel.value = '';
          renderExposureSizer();
        }
        action.addEventListener('change', function () {
          l.action = action.value; markCustom(); remember(); schedulePreview();
        });
        type.addEventListener('change', async function () {
          l.type = type.value; syncStockMode();
          if (l.type !== 'STOCK') { l.strike = null; await fillStrikes(); } else { mkt.textContent = ''; }
          markCustom(); remember(); schedulePreview();
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
          ratio.value = String(l.ratio); markCustom(); remember(); schedulePreview();
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
            st.exposureResult = null;
            remember(); renderLegs(); renderExposureSizer(); schedulePreview();
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
          }, options.completeLabel || 'Review & place →')));
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
        st.exposureResult = null;
        remember();
        await renderLegs();
        renderExposureSizer();
        schedulePreview();
      });
      addBtn.addEventListener('click', async function () {
        st.legs.push(leg('BUY', 'CALL', null, expirations[Math.min(2, expirations.length - 1)] || null));
        st.templateKey = null; tplSel.value = '';
        st.exposureResult = null;
        remember(); await renderLegs(); schedulePreview();
      });
      symInput.addEventListener('change', async function () {
        var sym = symInput.value.trim().toUpperCase();
        if (!sym || sym === st.symbol) return;
        st.symbol = sym; App.context.selectSymbol(sym);
        st.legs = []; st.excluded = {};
        remember();
        try {
          await loadSymbol();
          if (st.templateKey) st.legs = await buildFromTemplate(st.templateKey);
          await renderLegs();
          st.exposureResult = null;
          renderExposureSizer();
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
      renderExposureSizer();
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
        stat(UI.vocabulary('theoreticalMaxLoss'),
          unlimited ? el('span', { class: 'loss' }, 'UNLIMITED')
            : p.ok || p.maxLossCents > 0 ? el('span', { class: 'loss' }, fmtMoney(p.maxLossCents))
            : el('span', { class: 'muted' }, '—'),
          beginnerWording ? 'The worst case at expiration, including every leg.' : null),
        stat(beginnerWording ? 'Theoretical ceiling' : 'Theoretical max profit', el('span', {
          class: UI.profitCeilingKind(familyLabel(), null, p.maxProfitCents, p.legs) === 'model-dependent' ? 'muted' : 'gain'
        }, UI.maxProfitLabel(familyLabel(), null, p.maxProfitCents, beginnerWording, p.legs)),
          beginnerWording ? 'The best case at expiration.' : null),
        stat(beginnerWording ? 'Chance of any profit' : el('span', {}, 'POP', UI.info('pop')),
          p.popEntry !== null && p.popEntry !== undefined ? fmtPct(p.popEntry) : '—',
          beginnerWording ? 'Model estimate under current volatility. Not a promise.' : null),
        p.assignmentProb !== null && p.assignmentProb !== undefined
          ? stat(beginnerWording ? assignmentLabel(true)
              : el('span', {}, assignmentLabel(false), UI.info('assignment')), fmtPct(p.assignmentProb),
              beginnerWording ? 'Chance any short strike finishes in the money and shares change hands.' : null) : null,
        stat('Fees', fmtMoney(p.feesOpenCents), null)));
      if (p.breakevens && p.breakevens.length) {
        hostEl.appendChild(el('div', { class: 'chip-row' },
          chip(UI.term('breakeven', 'Breakevens'), p.breakevens.map(function (b) { return '$' + parseFloat(b).toFixed(2); }).join(' / '))));
      }
      hostEl.appendChild(el('div', { class: 'chip-row' },
        chip('Buying power after', fmtMoney(p.buyingPowerAfterCents)),
        p.reserveCents ? chip(UI.vocabulary('brokerReserve'), fmtMoney(p.reserveCents)) : null));
      // One line kills the "three different risk numbers" confusion: everything here is a TOTAL.
      hostEl.appendChild(el('div', { class: 'muted small' },
        'All figures are totals for this exact position and quantity. Broker reserve and theoretical max loss differ on credit trades because the reserve is the gross width — the loss boundary lives inside it.'));
      var prob = an.probabilityMap;
      if (prob && prob.pAnyProfit !== undefined && p.ok) {
        hostEl.appendChild(el('div', { class: 'chip-row', id: 'builder-prob-chips' },
          chip(beginnerWording ? 'Any profit' : 'P(profit)', Math.round(prob.pAnyProfit * 100) + '%'),
          chip(beginnerWording ? 'Full win' : 'P(max profit)', Math.round(prob.pMaxProfit * 100) + '%'),
          chip(beginnerWording ? 'Chance of theoretical max loss' : 'P(theoretical max loss)', Math.round(prob.pMaxLoss * 100) + '%'),
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
        hostEl.appendChild(explain('Mixed expirations have no single at-expiry payoff line: the position\u2019s value depends on volatility after the near leg expires. Use the shared scenario distributions below to inspect that model-dependent range.'));
      }
      // One lazy scenario component for every structure. It sits BESIDE the structural payoff
      // truth and uses this preview's exact package price; no parallel pricing implementation.
      if (p.ok && window.Scenario && p.legs && p.legs.length) {
        hostEl.appendChild(Scenario.realisticOutcomes(st.symbol, {
          strategy: familyLabel(), legs: p.legs, qty: st.qty || 1,
          entryNetPremiumCents: p.entryNetPremiumCents
        }));
      }
      if (p.ok && p.warnings && p.warnings.length) {
        hostEl.appendChild(alertBox('warn', 'Heads up', p.warnings));
      }
    }
  }

  window.Builder = { render: render, TEMPLATES: TEMPLATES, WIZARD: WIZARD,
    applyCatalog: applyCatalog, prepareTicket: prepareTicket, adoptTicket: adoptTicket };
})();
