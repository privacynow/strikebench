/* StrikeBench views: one render function per screen. Attached to window, jsdom-friendly. */
(function () {
  'use strict';

  var el = UI.el, fmtMoney = UI.fmtMoney, pnlSpan = UI.pnlSpan, fmtPct = UI.fmtPct,
      fmtNum = UI.fmtNum, badge = UI.freshnessBadge, explain = UI.explain,
      alertBox = UI.alertBox, stat = UI.stat, table = UI.table, chip = UI.chip;

  var CORE_SYMBOLS = ['AAPL', 'SPY', 'QQQ', 'TSLA'];

  function reported(value, formatter) {
    var n = Number(value);
    return value === null || value === undefined || !Number.isFinite(n) ? 'not reported' : formatter(value);
  }

  function activeDecisionPnl(t) {
    return t && t.decisionUnrealizedPnlCents !== undefined && t.decisionUnrealizedPnlCents !== null
      ? t.decisionUnrealizedPnlCents : (t ? t.unrealizedPnlCents : null);
  }

  function closedDecisionPnl(t) {
    return t && t.decisionPnlCents !== undefined && t.decisionPnlCents !== null
      ? t.decisionPnlCents : (t ? t.realizedPnlCents : null);
  }

  function positiveInteger(value, label, max) {
    var n = Number(value);
    if (!Number.isInteger(n) || n < 1 || (max && n > max)) {
      throw new Error((label || 'Value') + ' must be a whole number from 1' + (max ? ' to ' + max : '') + '.');
    }
    return n;
  }

  var icon = UI.icon;

  async function visibleCommand(button, action, fallbackMessage) {
    if (button) {
      button.disabled = true;
      button.setAttribute('aria-busy', 'true');
    }
    try {
      return await action();
    } catch (e) {
      UI.toast((e && e.message) || fallbackMessage || 'This action could not be completed.', 'error');
      return null;
    } finally {
      if (button && button.isConnected) {
        button.disabled = false;
        button.removeAttribute('aria-busy');
      }
    }
  }

  function focusPlanFrom(button, plan, stage) {
    return visibleCommand(button, function () { return PlanStore.focus(plan, stage); },
      'This Plan could not be opened.');
  }

  function planIntentDestination(intent) {
    return intent == null ? 'EVIDENCE' : 'STRATEGY';
  }

  async function startPlan(prefill, stage) {
    prefill = Object.assign({}, prefill || {});
    var symbol = String(prefill.symbol || App.context.symbol() || '').trim().toUpperCase();
    if (!symbol) { App.navigate('#/research'); return Promise.resolve(null); }
    var horizon = prefill.horizon || App.context.horizon('month');
    var days = Product.Horizon.sessions(horizon);
    var risk = document.getElementById('risk-mode');
    var intent = prefill.intent || App.context.goal('DIRECTIONAL');
    var destination = stage || planIntentDestination(intent);
    try {
      var matches = await PlanStore.matching(symbol, intent);
      if (matches.length) {
        var existing = matches[0];
        await PlanStore.focus(existing, destination);
        UI.toast('Plan opened — ' + symbol + ' · ' + window.ViewPlan.intentLabel(intent));
        return existing;
      }
      var plan = await PlanStore.create({
        symbol: symbol, intent: intent,
        thesis: prefill.thesis || App.context.thesis('neutral'), horizonDays: days,
        targetCents: prefill.target ? Math.round(parseFloat(prefill.target) * 100) : null,
        riskMode: risk ? risk.value : 'conservative'
      });
      await PlanStore.focus(plan, destination);
      UI.toast('Plan created — ' + symbol + ' · ' + window.ViewPlan.intentLabel(intent));
      return plan;
    } catch (e) {
      UI.toast(e.message, 'error');
      return null;
    }
  }

  /**
   * The opening page: what this is, who it's for, and three one-click ways in — shown to
   * fresh accounts (and always at #/home/tour). The competition leads with a landing page;
   * ours leads with a landing page wired to LIVE local data (the tape above it already ticks).
   */
  async function welcome(root) {
    var stepsAnchorId = 'how-it-works';
    var outer = root;
    root = el('div', { class: 'welcome-page' });
    outer.appendChild(root);

    // ---- Top fold: the pitch on the left, LIVE proof on the right — promise, evidence,
    // and both primary actions land above the fold on a desktop ----
    var liveHost = el('div', { class: 'showcase-frame', id: 'welcome-live' },
      el('div', { class: 'showcase-loading' }, UI.spinner('Asking the engine for a live example\u2026')));
    var liveEyebrow = el('div', { class: 'eyebrow', id: 'welcome-proof-role' }, 'FROM THE ENGINE');
    var deco = (function () {
      var svgNS = 'http://www.w3.org/2000/svg';
      var d = document.createElementNS(svgNS, 'svg');
      d.setAttribute('class', 'hero-deco');
      d.setAttribute('viewBox', '0 0 340 150');
      d.setAttribute('aria-hidden', 'true');
      var zero = document.createElementNS(svgNS, 'path');
      zero.setAttribute('class', 'deco-zero');
      zero.setAttribute('d', 'M0 96 H340');
      var tent = document.createElementNS(svgNS, 'path');
      tent.setAttribute('d', 'M0 128 H70 L128 34 H208 L266 128 H340'); // an iron condor, quietly
      d.appendChild(zero); d.appendChild(tent);
      return d;
    })();
    root.appendChild(el('div', { class: 'welcome-top' },
      heroBlock('welcome', {
        id: 'welcome-hero',
        deco: deco,
        top: el('div', { class: 'hero-brandline' }, UI.brandMark(30),
          el('span', { class: 'eyebrow' }, 'PAPER TRADING \u00B7 LOCAL-FIRST \u00B7 FREE')),
        title: el('h1', { class: 'hero-title' }, 'Learn options by ', el('span', { class: 'grad' }, 'doing'), ',', el('br', {}),
          'with honest numbers.'),
        sub: el('p', { class: 'hero-sub' },
          'Screen strategies against your goal, practice with \$100,000 in paper money, and verify honestly.'),
        ctas: [
          el('button', {
            class: 'btn btn-lg', onclick: function () {
              try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
              App.navigate('#/research');
            }
          }, 'Find my first idea'),
          el('button', {
            class: 'btn btn-lg btn-ghost', onclick: function () {
              var t = document.getElementById(stepsAnchorId);
              if (t) t.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
          }, 'See how it works')],
        extras: el('div', { class: 'trust-row' },
          ['No signup', 'No cloud', 'Uses bid/ask, not midpoint', 'Undefined risk blocked']
            .map(function (t) { return el('span', {}, t); }))
      }),
      el('section', { class: 'welcome-section welcome-live-col' },
        el('div', { class: 'live-head' },
          liveEyebrow,
          el('button', { class: 'btn-link', id: 'welcome-skip', onclick: function () {
            try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
            App.navigate('#/home');
          } }, 'Skip \u2192 dashboard')),
        el('div', { class: 'showcase' }, liveHost,
          el('p', { class: 'showcase-caption', id: 'welcome-proof-caption' }, welcomeProofCaption())))));
    function welcomeProofCaption() {
      if (App.config && App.config.scenarioMode) return 'A screened scenario result \u2014 modeled, not observed.';
      if (App.state.world === 'demo') return 'A screened teaching idea \u2014 generated from fabricated Demo data.';
      if (App.state.world && App.state.world !== 'observed') return 'A screened simulated-market idea \u2014 generated, not observed.';
      return 'A screened idea from observed market inputs \u2014 generated this second.';
    }
    function welcomeProofReady(c) {
      return c && c.maxLossCents > 0 && c.maxProfitCents !== null
        && c.maxProfitCents !== undefined && typeof c.pop === 'number';
    }
    function welcomeProofChoice(candidates) {
      var ready = (candidates || []).filter(welcomeProofReady);
      return ready.find(function (c) { return window.ViewPlan.economicVerdict(c) === 'FAVORABLE'; })
        || ready.find(function (c) { return window.ViewPlan.economicVerdict(c) === 'MIXED'; })
        || ready[0] || (candidates || [])[0];
    }
    function welcomeProofCard(candidate) {
      var story = candidate.beginnerExplanation || candidate.whyConsidered;
      var card = el('div', { class: 'candidate welcome-proof-card',
        'data-strategy': candidate.strategy,
        'data-economic-verdict': window.ViewPlan.economicVerdict(candidate) || 'UNKNOWN' },
        el('div', { class: 'head' },
          el('h3', {}, candidate.displayName),
          intentBadge(candidate.intent),
          badge(candidate.freshness)),
        story ? el('p', { class: 'welcome-proof-story muted' }, story) : null);
      var economics = window.ViewPlan.economicAssessmentBlock(candidate);
      if (economics) card.appendChild(economics);
      card.appendChild(el('div', { class: 'fact-grid welcome-proof-facts' },
        UI.fact('Theoretical max loss', fmtMoney(candidate.maxLossCents), 'f-danger'),
        UI.fact('Chance of any profit', fmtPct(candidate.pop)),
        UI.fact('Theoretical max profit', UI.maxProfitLabel(
          candidate.strategy, candidate.structureGroup, candidate.maxProfitCents, true, candidate.legs))));
      card.appendChild(el('div', { class: 'welcome-proof-action' },
        el('button', { class: 'btn btn-sm btn-secondary', onclick: function () {
          App.navigate('#/research/' + encodeURIComponent(candidate.symbol || 'AAPL'));
        } }, 'Open full analysis')));
      return card;
    }
    function frameWelcomeProof(candidate) {
      var verdict = window.ViewPlan.economicVerdict(candidate);
      var caption = welcomeProofCaption();
      if (verdict === 'UNFAVORABLE' || verdict === 'UNAVAILABLE') {
        liveEyebrow.textContent = 'HOW STRIKEBENCH SAYS NO';
        caption += ' This is a counterexample, not an endorsement.';
      } else if (verdict === 'MIXED') {
        liveEyebrow.textContent = 'COMPARE CAREFULLY';
        caption += ' It is worth comparing, but no robust edge is claimed.';
      } else {
        liveEyebrow.textContent = 'WORTH INVESTIGATING';
        caption += App.state.world === 'observed'
          ? ' The after-cost evidence passed the favorable screen.'
          : ' Favorable here means inside this explicit teaching market, not a live-market edge.';
      }
      var proofCaption = document.getElementById('welcome-proof-caption');
      if (proofCaption) proofCaption.textContent = caption;
    }
    (async function () {
      // LAST-KNOWN FIRST (review #15): the opening composition must never be half spinner on a
      // slow provider. Render the cached proof instantly, then refresh it live and re-store.
      // The proof cache carries its CONTEXT (review P2 #7): a candidate produced in a
      // simulated world, under a scenario dataset, or a week ago must not flash as
      // "LIVE FROM THE ENGINE" on an observed page.
      // BOOT-LANE RACE FIX (review P1 #7): wait for the boot world determination before
      // building the context — a reload inside a simulated world must not save a simulated
      // candidate under the observed cache key. Keyed by the immutable dataset ID, not name.
      var proofToken = App.navToken;
      if (App.worldReady) { try { await App.worldReady; } catch (e) { /* lane decided anyway */ } }
      if (!App.alive(proofToken)) return; // navigated away while the lane was deciding
      var proofCaption = document.getElementById('welcome-proof-caption');
      if (proofCaption) proofCaption.textContent = welcomeProofCaption();
      var proofCtx = {
        world: App.state.world || 'observed',
        scenario: (App.config && App.config.scenarioMode && App.config.activeDataset) || 'observed',
        riskMode: 'conservative'
      };
      var cached = null;
      try {
        var raw = JSON.parse(localStorage.getItem('strikebench.welcomeProof') || 'null');
        if (raw && welcomeProofReady(raw.candidate)
            && raw.world === proofCtx.world && raw.scenario === proofCtx.scenario
            && raw.riskMode === proofCtx.riskMode && raw.asOf && (Date.now() - raw.asOf) < 24 * 3600 * 1000) {
          cached = raw.candidate;
        }
      } catch (e) { /* fresh */ }
      if (cached) {
        liveHost.innerHTML = '';
        liveHost.appendChild(welcomeProofCard(cached));
        frameWelcomeProof(cached);
        liveHost.appendChild(el('p', { class: 'muted small', style: 'margin:4px 0 0' }, 'Refreshing with a live run\u2026'));
      }
      try {
        var refresh = API.getFresh('/api/welcome/teaching-example');
        var r = await Promise.race([refresh, new Promise(function (_, reject) {
          setTimeout(function () { reject(new Error('The current market check is still running.')); }, 6000);
        })]);
        if (r.candidates && r.candidates.length) {
          var proofCandidate = welcomeProofChoice(r.candidates);
          liveHost.innerHTML = '';
          liveHost.appendChild(welcomeProofCard(proofCandidate));
          frameWelcomeProof(proofCandidate);
          try {
            localStorage.setItem('strikebench.welcomeProof', JSON.stringify(
              Object.assign({ candidate: proofCandidate, asOf: Date.now() }, proofCtx)));
          } catch (e) { /* private mode */ }
        } else if (!cached) {
          liveHost.innerHTML = '';
          liveHost.appendChild(el('div', { class: 'empty-state', id: 'welcome-proof-unavailable' },
            el('b', {}, 'No AAPL idea passes right now'),
            el('p', { class: 'muted small' },
              'That is a valid engine result, not a missing demo. Open Research to inspect the evidence or choose another stock.'),
            el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research/AAPL'); } }, 'Research AAPL')));
        }
      } catch (e) {
        if (!cached) {
          liveHost.innerHTML = '';
          liveHost.appendChild(el('div', { class: 'empty-state', id: 'welcome-proof-unavailable' },
            el('b', {}, 'The current market check is still running'),
            el('p', { class: 'muted small' }, 'The rest of StrikeBench is ready. Research opens immediately while the feed catches up.'),
            el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research'); } }, 'Open Research')));
        }
      }
    })();

    // ---- Two doors, by experience ----
    // The beginner door enters the GUIDED first workflow (review IC-3): step 1 of the
    // first-week journey is researching a stock — not an unexplained empty form.
    var LEVEL_CARDS = [
      { level: 'beginner', title: 'Teach me', ic: 'sprout',
        blurb: 'Question-driven flows, plain language \u2014 every idea explains itself.',
        cta: 'Start as a beginner', go: '#/research',
        toast: 'Step 1: pick a stock that interests you \u2014 the Next-up list on Home walks the rest.' },
      { level: 'expert', title: 'Give me the terminal', ic: 'bolt',
        blurb: 'Dense tables, greeks, per-leg impact, inline filters.',
        cta: 'Research AAPL', go: '#/research/AAPL' }
    ];
    root.appendChild(section('CHOOSE YOUR ALTITUDE', 'How do you want to start?',
      el('div', { class: 'welcome-grid', id: 'welcome-levels' }, LEVEL_CARDS.map(function (c) {
        function start() {
          Learn.setLevel(c.level);
          try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
          if (c.toast && UI.toast) UI.toast(c.toast);
          App.navigate(c.go);
        }
        var card = el('div', { class: 'card welcome-card clickable', onclick: start },
          el('div', { class: 'icon-tile' }, icon(c.ic)),
          el('h3', {}, c.title),
          el('p', {}, c.blurb),
          el('button', {
            class: 'btn', 'data-welcome-level': c.level, onclick: function (e) { e.stopPropagation(); start(); }
          }, c.cta));
        return pressable(card, c.title + ': ' + c.blurb, 'link');
      }))));

    // ---- How it works: the four-step promise, full width, original ghost numerals ----
    var STEPS = [
      ['Pick a goal', 'or a ticker you care about'],
      ['Screen ideas', 'refusals explained, risk capped'],
      ['Practice the trade', 'with the $100k paper account'],
      ['Review honestly', 'marks, P/L, what changed']
    ];
    var stepper = el('div', { class: 'stepper' }, STEPS.map(function (st, i) {
      return el('div', { class: 'step-block' },
        el('div', { class: 'step-num' }, '0' + (i + 1)),
        el('div', { class: 'step-text' }, el('b', {}, st[0]), el('span', {}, st[1])));
    }));
    var how = section('HOW IT WORKS', 'Four steps, no account required', stepper);
    how.id = stepsAnchorId;
    root.appendChild(how);

    // ---- One slim footer row: the sector hook + the exit ----
    root.appendChild(el('div', { class: 'welcome-footer' },
      el('div', { class: 'cta-banner' },
        el('div', {},
          el('b', {}, 'Thirteen sector universes, one flick away.'),
          el('p', { class: 'muted' }, 'Tech, semiconductors, healthcare, defense and more \u2014 feeding the ticker, the scout, and every symbol box.')),
        el('button', { class: 'btn', onclick: function () { App.navigate('#/research'); } }, 'Open the sector explorer'))));

    function section(eyebrow, title, contentNode) {
      return el('section', { class: 'welcome-section' },
        el('div', { class: 'eyebrow' }, eyebrow),
        el('h2', { class: 'welcome-h2' }, title),
        contentNode);
    }
  }

  function welcomed() {
    try { return window.localStorage.getItem('strikebench.welcomed') === '1'; } catch (e) { return true; }
  }

  function legLabel(leg) {
    if (leg.type === 'STOCK') return leg.action + ' ' + (leg.ratio * 100) + ' shares';
    return leg.action + ' ' + leg.ratio + 'x ' + stripZeros(leg.strike) + leg.type.charAt(0) + ' ' + leg.expiration;
  }

  function stripZeros(s) {
    return String(parseFloat(s));
  }

  function fmtBreakeven(b) {
    var v = parseFloat(b);
    return isNaN(v) ? String(b) : (Math.round(v * 100) / 100).toString();
  }

  function prettyStrategy(s) {
    return (s || '').replace(/_/g, ' ').toLowerCase().replace(/^./, function (c) { return c.toUpperCase(); });
  }

  var INTENT_BADGE = { DIRECTIONAL: 'badge-dim', INCOME: 'badge-ok', ACQUIRE: 'badge-ok', EXIT: 'badge-warn', HEDGE: 'badge-caution' };
  var THESIS_BADGE = { BULLISH: 'badge-ok', BEARISH: 'badge-danger', NEUTRAL: 'badge-dim', VOLATILE: 'badge-warn' };

  function intentBadge(intent) {
    if (!intent || intent === 'DIRECTIONAL') return null;
    var meta = (Learn.INTENTS || []).find(function (i) { return i.key === intent; });
    return el('span', { class: 'badge ' + (INTENT_BADGE[intent] || 'badge-dim') },
      meta ? meta.label.toUpperCase() : intent);
  }

  function riskMode() { return document.getElementById('risk-mode').value; }

  // ---------- 0. Home dashboard ----------

  async function home(root, params) {
    if (params && params[0] === 'tour') return welcome(root);
    // Reconcile durable Plan truth before deriving the hero/Continue state. A browser tab
    // can survive a server restart; its in-memory collection must not outlive server state.
    try { if (window.PlanStore) await PlanStore.load(true); }
    catch (e) { /* the independent Plan-library card reports the unavailable service below */ }
    // Paint the frame FIRST, fill every card as its data arrives: the screen must never
    // be a blank void while accounts and quotes load. Each section fills independently
    // and fails visibly (empty state), never silently.
    // The opening page's DNA is folded INTO the dashboard — a welcoming hero band with
    // the account numbers in it, and the full tour one visible click away. Streamlined,
    // not discarded: fresh accounts still open on the full hero page.
    // OPERATIONAL HERO (review #10): Home is the user's DESK — market mode, account numbers,
    // and one contextual next action. The product positioning ("Learn options by doing") lives
    // on the Welcome/tour page only; repeating it here made Home a compressed landing page.
    var statsAnchor = el('div', { class: 'grid grid-4', id: 'home-stats' });
    var heroMode = el('span', { class: 'badge badge-ok', id: 'home-mode-chip' }, 'OBSERVED MARKET');
    if (App.config && App.config.scenarioMode) {
      heroMode = el('span', { class: 'badge badge-warn', id: 'home-mode-chip' },
        'SCENARIO: ' + (App.config.activeDatasetName || 'generated data'));
    } else if (App.state.world === 'demo') {
      heroMode = el('span', { class: 'badge badge-warn', id: 'home-mode-chip' }, 'DEMO MARKET');
    } else if (App.state.world && App.state.world !== 'observed') {
      heroMode = el('span', { class: 'badge badge-sim', id: 'home-mode-chip' }, 'SIMULATED MARKET SESSION');
    }
    var activePlan = window.PlanStore && PlanStore.active();
    var activeIdentity = activePlan && PlanStore.identity(activePlan, PlanStore.all());
    var heroSub = el('p', { class: 'home-hero-sub', id: 'home-context-line' },
      activePlan
        ? activePlan.symbol + ' · ' + activeIdentity.full + ' is at '
          + String(activePlan.activeStage || 'UNDERSTAND').replace('_', ' ').toLowerCase() + '.'
        : 'Screened ideas, honest odds, worst case always known before you commit.');
    var heroCta = activePlan
      ? el('button', { class: 'btn', onclick: function () { focusPlanFrom(this, activePlan); } },
          'Continue ' + activePlan.symbol + (activeIdentity.duplicate ? ' · ' + activeIdentity.duplicate : '') + ' \u2192')
      : el('button', { class: 'btn', onclick: function () { App.navigate('#/research'); } }, 'Start a Plan');
    // The hero owns THE single primary next action (review P5): the tour demoted to a
    // quiet entry at the bottom of the page — onboarding is not a permanent command.
    root.appendChild(heroBlock('dashboard', {
      eyebrow: ['YOUR PRACTICE DESK ', heroMode],
      title: el('h1', { class: 'home-hero-title' }, 'Your ', el('span', { class: 'grad' }, 'desk'), '.'),
      sub: heroSub,
      aside: statsAnchor,
      ctas: [heroCta]
    }));

    // First contact: a fresh account that hasn't dismissed the welcome gets the opening
    // page instead — decided by the same account fetch that fills the stat row.
    var acct = null;
    try {
      acct = (await API.get('/api/account')).account;
    } catch (e) { /* dashboard still renders; stats show an honest note below */ }
    if (!welcomed()) {
      if (acct && !acct.hasTraded) { root.innerHTML = ''; return welcome(root); }
      try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e) { /* ignore */ }
    }
    if (acct) {
      statsAnchor.appendChild(stat('Cash', fmtMoney(acct.cashCents), 'Practice money available. Nothing here is real dollars.'));
      statsAnchor.appendChild(stat('Reserved', fmtMoney(acct.reservedCents), 'Held to cover the worst case of your open trades.'));
      statsAnchor.appendChild(stat('Buying power', fmtMoney(acct.buyingPowerCents), 'Cash minus reserves — what you can still put at risk.'));
      statsAnchor.appendChild(stat('Started with', fmtMoney(acct.startingCashCents)));
    } else {
      statsAnchor.appendChild(alertBox('warn', 'Account unavailable right now', ['Retry from the Account screen.']));
    }

    // The desk owns the complete Plan library. This is deliberately a LIGHTWEIGHT lens:
    // Plan metadata plus one batched quote request and already-composed portfolio marks for
    // the active market. It never runs evidence, strategy ranking, outcomes, or backtests for
    // background Plans.
    var planLibrary = el('section', { class: 'card home-plan-library', id: 'home-plan-library' },
      UI.cardHeader('Plans', el('button', { type: 'button', class: 'btn btn-sm',
        onclick: function () { App.navigate('#/research'); } }, '+ New Plan')),
      UI.spinner('Loading your Plans…'));
    root.appendChild(planLibrary);
    var planLibraryFill = (async function fillPlanLibrary() {
      var plans;
      try { plans = await PlanStore.library(true); }
      catch (e) {
        planLibrary.innerHTML = '';
        planLibrary.appendChild(UI.cardHeader('Plans'));
        planLibrary.appendChild(alertBox('warn', 'Plan library unavailable', [e.message]));
        return;
      }
      var currentKey = PlanStore.currentMarketKey();
      var working = plans.filter(function (p) { return p.status !== 'ARCHIVED' && p.open !== false; });
      var closedTabs = plans.filter(function (p) { return p.status !== 'ARCHIVED' && p.open === false; });
      var archived = plans.filter(function (p) { return p.status === 'ARCHIVED'; });
      var current = working.filter(function (p) { return PlanStore.marketKey(p) === currentKey; });
      var elsewhere = working.filter(function (p) { return PlanStore.marketKey(p) !== currentKey; });
      if (!working.length && !archived.length && !closedTabs.length) {
        // The hero and Continue card already own the first-Plan action. A second, full-width
        // empty library made the opening desk look unfinished and repeated the same command.
        planLibrary.remove();
        return;
      }
      var quoteBySymbol = {};
      var portfolioByPlan = {};
      var sessionById = {};
      try {
        var symbols = Array.from(new Set(current.map(function (p) { return p.symbol; })));
        var fills = await Promise.all([
          symbols.length ? API.get('/api/quotes?symbols=' + symbols.join(',')) : Promise.resolve({ quotes: [] }),
          API.getFresh('/api/plans/portfolio'),
          plans.some(function (p) { return p.marketKind === 'SIMULATED'; })
            ? API.get('/api/sim/market') : Promise.resolve({ sessions: [] })
        ]);
        (fills[0].quotes || []).forEach(function (q) { quoteBySymbol[q.symbol] = q; });
        (fills[1].plans || []).forEach(function (row) { portfolioByPlan[row.plan.id] = row; });
        (fills[2].sessions || []).forEach(function (session) { sessionById[session.id] = session; });
      } catch (e2) { /* Plan navigation remains useful without decorative marks */ }

      function stageName(plan) {
        return String(plan.activeStage || 'UNDERSTAND').replaceAll('_', ' ').toLowerCase();
      }
      function planTile(plan) {
        var sameMarket = PlanStore.marketKey(plan) === currentKey;
        var terminalSession = plan.marketKind === 'SIMULATED' && sessionById[plan.worldId]
          && sessionById[plan.worldId].status === 'FINISHED';
        var row = portfolioByPlan[plan.id] || {};
        var mark = row.mark;
        var quote = sameMarket ? quoteBySymbol[plan.symbol] : null;
        var live = mark && mark.decisionUnrealizedCents != null
          ? el('span', { class: 'home-plan-live' }, 'Now ', pnlSpan(mark.decisionUnrealizedCents))
          : quote ? el('span', { class: 'home-plan-live' }, fmtNum(quote.last), ' ', UI.delta(quote.last, quote.prevClose))
          : el('span', { class: 'muted small' }, terminalSession ? 'Session finished · review its report'
            : sameMarket ? 'Market mark unavailable' : 'Switches market when opened');
        var planIdentity = PlanStore.identity(plan, working);
        var actions = el('div', { class: 'home-plan-actions' });
        actions.appendChild(el('button', { type: 'button', class: 'btn btn-sm', onclick: function () {
          if (terminalSession) {
            App.state.focusSimControlRoom = plan.worldId;
            App.navigate('#/data/simulation');
            return;
          }
          focusPlanFrom(this, plan, plan.activeStage);
        } }, terminalSession ? 'Review session' : sameMarket
          ? (plan.open === false ? 'Open Plan' : 'Resume Plan') : 'Switch market & open'));
        if (plan.status !== 'POSITION_OPEN') actions.appendChild(el('button', { type: 'button',
          class: 'btn btn-sm btn-secondary', 'aria-label': 'Archive ' + plan.title,
          onclick: function () { window.ViewPlan.confirmArchive(plan, false); } }, icon('archive', 15), ' Archive'));
        if (plan.assumptionsEditable === true) actions.appendChild(el('button', { type: 'button',
          class: 'btn btn-sm btn-secondary', 'aria-label': 'Delete draft ' + plan.title,
          onclick: function () { window.ViewPlan.confirmDelete(plan, false); } }, icon('trash', 15), ' Delete'));
        return el('article', { class: 'home-plan-tile' + (plan.id === App.state.activePlanId ? ' active' : ''),
          'data-plan-id': plan.id },
          el('div', { class: 'home-plan-tile-head' },
            el('div', {}, el('div', { class: 'eyebrow' }, plan.symbol + ' · ' + window.ViewPlan.intentLabel(plan.intent)),
              el('h3', {}, planIdentity.title)),
            el('div', { class: 'home-plan-badges' },
              planIdentity.duplicate ? el('span', { class: 'badge badge-info plan-duplicate-badge' }, planIdentity.duplicate) : null,
              el('span', { class: 'badge ' + (sameMarket ? 'badge-info' : 'badge-dim') }, window.ViewPlan.marketLabel(plan)))),
          el('div', { class: 'home-plan-meta' },
            chip('Stage', stageName(plan)),
            plan.context && plan.context.horizonDays ? chip('Horizon', plan.context.horizonDays + ' sessions') : null,
            plan.context && plan.context.thesis ? chip('View', plan.context.thesis) : null,
            planIdentity.updated ? el('span', { class: 'muted small plan-updated-at' }, planIdentity.updated) : null,
            live), actions);
      }
      function group(title, rows, note) {
        if (!rows.length) return null;
        var limit = 4;
        var ordered = rows.slice().sort(function (a, b) {
          if (a.id === App.state.activePlanId) return -1;
          if (b.id === App.state.activePlanId) return 1;
          return String(b.updatedAt || b.createdAt || '').localeCompare(String(a.updatedAt || a.createdAt || ''));
        });
        var expanded = false;
        var grid = el('div', { class: 'home-plan-grid' });
        var count = el('span', { class: 'muted small home-plan-group-count' });
        var toggle = ordered.length > limit ? el('button', { type: 'button',
          class: 'btn btn-sm btn-secondary home-plan-group-toggle', 'aria-expanded': 'false' }) : null;
        function paint() {
          var shown = expanded ? ordered : ordered.slice(0, limit);
          grid.replaceChildren.apply(grid, shown.map(planTile));
          count.textContent = expanded || ordered.length <= limit
            ? ordered.length + (ordered.length === 1 ? ' Plan' : ' Plans')
            : limit + ' of ' + ordered.length + ' Plans';
          if (toggle) {
            toggle.textContent = expanded ? 'Show fewer' : 'Show all ' + ordered.length;
            toggle.setAttribute('aria-expanded', String(expanded));
          }
        }
        if (toggle) toggle.onclick = function () { expanded = !expanded; paint(); };
        var heading = el('div', { class: 'home-plan-group-title' },
          el('div', {}, el('h3', {}, title), note ? el('span', { class: 'muted small' }, note) : null), count);
        paint();
        return el('section', { class: 'home-plan-group', 'data-plan-group': title.toLowerCase().replaceAll(' ', '-') },
          el('div', { class: 'home-plan-group-head' }, heading, toggle), grid);
      }

      planLibrary.innerHTML = '';
      planLibrary.appendChild(UI.cardHeader('Plans',
        el('span', { class: 'muted small' }, working.length + ' working ' + (working.length === 1 ? 'Plan' : 'Plans'))));
      if (!working.length) {
        planLibrary.appendChild(UI.emptyState('No working Plans yet',
          'Choose a stock in Research, then carry one Plan through evidence, strategy, outcomes, and a decision.',
          'Open Research', function () { App.navigate('#/research'); }));
      } else {
        var here = group('In this market', current, 'These Plans use the prices and account on screen now.');
        var there = group('Other markets', elsewhere, 'Opening one switches the full market first.');
        if (here) planLibrary.appendChild(here);
        if (there) planLibrary.appendChild(there);
      }
      if (archived.length) planLibrary.appendChild(UI.expandable('Archived Plans (' + archived.length + ')', function () {
        return el('div', { class: 'home-plan-archive' }, archived.map(function (plan) {
          return el('div', { class: 'status-item' }, el('b', {}, plan.symbol),
            el('span', {}, plan.title), el('span', { class: 'badge badge-dim' }, window.ViewPlan.marketLabel(plan)),
            el('span', { class: 'muted small' }, 'Retained as a read-only record'));
        }));
      }));
      if (closedTabs.length) planLibrary.appendChild(UI.expandable('Closed Plan tabs (' + closedTabs.length + ')', function () {
        return el('div', { class: 'home-plan-archive home-plan-closed-tabs' }, closedTabs.map(function (plan) {
          var actions = el('div', { class: 'btn-row' },
            el('button', { type: 'button', class: 'btn btn-sm', onclick: function () {
              focusPlanFrom(this, plan, plan.activeStage);
            } }, PlanStore.marketKey(plan) === currentKey ? 'Reopen' : 'Switch market & reopen'));
          if (plan.assumptionsEditable === true) actions.appendChild(el('button', { type: 'button',
            class: 'btn btn-sm btn-secondary', onclick: function () { window.ViewPlan.confirmDelete(plan, false); } },
          icon('trash', 14), ' Delete draft'));
          else if (plan.status !== 'POSITION_OPEN') actions.appendChild(el('button', { type: 'button',
            class: 'btn btn-sm btn-secondary', onclick: function () { window.ViewPlan.confirmArchive(plan, false); } },
          icon('archive', 14), ' Archive'));
          return el('div', { class: 'status-item' }, el('b', {}, plan.symbol),
            el('span', {}, plan.title), el('span', { class: 'badge badge-dim' }, window.ViewPlan.marketLabel(plan)), actions);
        }));
      }));
    })();

    var colL = el('section', { class: 'home-col home-market-slot', 'aria-label': 'Market watch' });
    var colR = el('div', { class: 'home-col home-col-side' });
    var posAnchor = el('div', { id: 'open-trades-anchor', class: 'home-wide' });
    root.appendChild(el('div', { class: 'home-cols home-layout' }, colL, colR, posAnchor));
    var pulseAnchor = el('div', { id: 'sector-pulse-anchor' });
    // Sector pulse: the shared sector rail — same affordance as Research and Trade.
    // Renders instantly (usable before quotes answer); day moves fill in async.
    (function sectorPulse() {
      if (!App.state.universe) return;
      var pulse = el('div', { class: 'card', id: 'sector-pulse' },
        UI.cardHeader('Sector pulse',
          el('a', { href: '#/research', class: 'muted', style: 'font-size:12.5px' }, 'Explore sectors \u2192')),
        sectorRail({
          onPick: function (sec) {
            App.state.explorerSector = sec.key;
            App.navigate('#/research');
          }
        }),
        App.state.world === 'demo'
          ? el('p', { class: 'muted small', style: 'margin:10px 4px 0' },
              'One fixed teaching universe is active. Open Research for its five stocks, or start a moving session from Simulate.')
          : null,
        explain('Day change of each sector\u2019s ETF proxy. Tap a sector to open its symbols in the explorer.'));
      if (pulseAnchor.isConnected) pulseAnchor.replaceWith(pulse);
      else pulseAnchor.appendChild(pulse); // not yet mounted: fill in place, mount later
    })();


    // Markets: shell now, tiles when the ONE batch-quotes call answers (this used to be
    // eight sequential full-research calls that blocked the whole dashboard).
    var uni = App.state.universe && App.state.universe.active;
    // Eight symbols use desktop real estate without turning Home into the full explorer.
    // Smaller viewports progressively show the first six/four; the full universe stays one tap away.
    var marketSymbols = uni && uni.symbols && uni.symbols.length ? uni.symbols.slice(0, 8) : CORE_SYMBOLS;
    var tiles = el('div', { class: 'home-market-grid' });
    var homeHistoryNotice = el('div', { class: 'home-history-notice muted small',
      id: 'home-history-notice', hidden: '' });
    colL.appendChild(el('div', { class: 'card home-market-card', id: 'home-market-watch' },
      UI.cardHeader('Market watch' + (uni && App.state.world !== 'demo' ? ' — ' + uni.label : ''),
        el('a', { href: '#/research', class: 'muted home-view-all' },
          'View all ' + ((uni && uni.symbols && uni.symbols.length) || marketSymbols.length) + ' symbols →')),
      tiles,
      homeHistoryNotice,
      el('p', { class: 'muted small market-watch-caption' },
        'Select a stock for its chart, options, volatility, events and historical evidence.')));
    var marketsFill = (async function fillMarkets() {
      var rows = [];
      try {
        rows = (await API.get('/api/quotes?symbols=' + marketSymbols.join(','))).quotes || [];
      } catch (e) { /* handled below as the empty state */ }
      if (!rows.length) {
        tiles.appendChild(UI.emptyState('Market data unavailable right now',
          'The quote providers did not answer. See the Data screen for per-source health.',
          'Check data status', function () { App.navigate('#/data/overview'); }));
        return;
      }
      var bySymbol = {};
      rows.forEach(function (q) { bySymbol[q.symbol] = q; });
      tiles.setAttribute('data-count', String(Math.min(marketSymbols.length, 8)));
      marketSymbols.forEach(function (symbol) {
        var q = bySymbol[symbol];
        var sparkSlot = el('div', { class: 'spark-slot' });
        tiles.appendChild(el('a', {
          class: 'tile sym-card' + (q ? '' : ' tile-nodata'), 'data-sym': symbol,
          href: '#/research/' + encodeURIComponent(symbol),
          'aria-label': 'Open ' + symbol + ' full analysis'
        },
          el('div', { class: 't-sym' }, symbol, ' ',
            q && App.state.world !== 'demo' ? badge(q.freshness) : null,
            q ? null : badge('NO QUOTE')),
          el('div', { class: 't-px' }, q ? fmtNum(q.last) : '\u2014'),
          q ? UI.delta(q.last, q.prevClose) : el('div', { class: 'muted t-move' }, 'quote unavailable'),
          el('div', { class: 't-nm' }, q && q.description || ''),
          sparkSlot,
          el('span', { class: 'destination-cue', 'aria-hidden': 'true' }, icon('chevron-right', 16))));
      });
      // The SAME sparkline card as Research, ONE batch call; Home cards go straight to the
      // full analysis (no preview layer here — review P5.8).
      var homeMissingHistory = new Set();
      function updateHomeHistoryNotice() {
        homeHistoryNotice.innerHTML = '';
        if (!homeMissingHistory.size) { homeHistoryNotice.hidden = true; return; }
        homeHistoryNotice.hidden = false;
        homeHistoryNotice.appendChild(el('span', {}, 'Charts unavailable for ' + homeMissingHistory.size
          + ' symbol' + (homeMissingHistory.size === 1 ? '' : 's') + '; current quotes remain usable.'));
        homeHistoryNotice.appendChild(el('a', { href: '#/data/sources' }, 'Update history'));
      }
      function paintHomeSpark(row) {
        var slot = tiles.querySelector('.sym-card[data-sym="' + row.symbol + '"] .spark-slot');
        if (!slot) return;
        slot.innerHTML = '';
        var missing = row.available === false || !row.closes || !row.closes.length;
        slot.classList.toggle('spark-slot-missing', missing);
        if (missing) homeMissingHistory.add(row.symbol); else homeMissingHistory.delete(row.symbol);
        slot.appendChild(UI.sparkline(row, { height: 30, quietMissing: true,
          missingText: missing ? missingSparkCopy(row) : 'No chart' }));
        // In Demo, the global band and each quote already say Demo; a second identical history
        // chip on every card adds noise, not honesty. Other lanes retain separate history evidence.
        if (!missing && row.evidence && !(App.state.world === 'demo' && row.evidence.provenance === 'DEMO')) {
          slot.appendChild(UI.evidenceBadge(row.evidence, { className: 'spark-ev', compact: true,
            missingLabel: 'HISTORY UNAVAILABLE' }));
        }
        updateHomeHistoryNotice();
      }
      lazySparklines(tiles, marketSymbols, {
        range: function () { return '3m'; },
        valid: function () { return tiles.isConnected; },
        paint: paintHomeSpark
      });
    })();

    // Open positions (review #8): a 256px empty card was wasted real estate — with no trades
    // the whole card stays OFF the page (Next up carries the one-line state + action instead);
    // with trades, three USEFUL rows: live P/L, max loss, opened.
    var hasOpenTrades = false;
    var tradesFill = (async function fillTrades() {
      try {
        var open = await API.get('/api/trades?status=ACTIVE&size=3');
        if (!open.trades.length) { posAnchor.remove(); return; }
        hasOpenTrades = true;
        var posCard = el('div', { class: 'card home-wide', id: 'open-trades-card' }, UI.cardHeader('Open practice trades',
          el('a', { href: '#/portfolio' }, 'All trades \u2192')));
        posCard.appendChild(table(['Symbol', 'Strategy', 'Now', 'Theor. max loss', 'Opened'],
          open.trades.map(function (t) {
            return pressable(el('tr', { class: 'clickable', onclick: function () { App.navigate('#/portfolio/trade/' + t.id); } },
              el('td', {}, el('b', {}, t.symbol)),
              el('td', {}, prettyStrategy(t.strategy) + ' x' + t.qty),
              el('td', { title: t.sharesLocked > 0 ? 'Decision P/L: held-share move plus the option package' : '' },
                activeDecisionPnl(t) !== undefined && activeDecisionPnl(t) !== null
                  ? pnlSpan(activeDecisionPnl(t)) : el('span', { class: 'muted' }, '\u2014')),
              el('td', { class: 'loss' }, fmtMoney(t.maxLossCents)),
              el('td', { class: 'muted' }, UI.fmtDate(t.createdAt))), 'Open ' + t.symbol + ' trade');
          })));
        posAnchor.replaceWith(posCard);
      } catch (e) {
        posAnchor.replaceWith(el('div', { class: 'card' }, alertBox('warn', 'Could not load open trades', [e.message])));
      }
    })();

    // NEXT UP (review #9): ONE contextual card replaces the journey card + continuity row +
    // empty-trades CTA — the single most useful next action first, everything else quiet.
    (function nextUp() {
      // With no Plan there is nothing to continue. The hero already owns the one start
      // command; rendering five stage buttons that all opened Research was duplicate
      // navigation disguised as progress.
      if (!activePlan) return;
      var card = el('div', { class: 'card card-slim', id: 'next-up' }, UI.cardHeader('Continue'));
      var any = false;
      // The beginner first-week path, folded in (retires itself after the first trade).
      if (Learn.currentLevel() === 'beginner' && acct && !acct.hasTraded) {
        var activeIndex = activePlan ? window.ViewPlan.stages.findIndex(function (stage) { return stage.key === activePlan.activeStage; }) : -1;
        var steps = window.ViewPlan.stages.slice(0, 5).map(function (stage, index) {
          return { label: stage.label, done: activeIndex > index, stage: stage.key };
        });
        card.appendChild(el('div', { class: 'journey', id: 'journey-card' }, steps.map(function (st2, i) {
          return el('button', { class: 'journey-step' + (st2.done ? ' done' : ''), type: 'button',
            onclick: function () { if (activePlan) focusPlanFrom(this, activePlan, st2.stage); else App.navigate('#/research'); } },
            el('span', { class: 'ck-mark' }, st2.done ? '' : String(i + 1)),
            el('span', {}, st2.label));
        })));
        any = true;
      }
      var chips = [];
      if (activePlan) {
        chips.push(el('button', { class: 'sym-chip', 'data-continue': 'plan',
          onclick: function () { focusPlanFrom(this, activePlan); } }, 'Resume: ' + activePlan.title + ' →'));
      }
      if (chips.length) {
        card.appendChild(el('div', { class: 'chip-row', id: 'continue-row' }, chips));
        any = true;
      }
      colR.appendChild(card);
    })();

    // Command bar (review P5): ONLY shortcuts the primary nav does not already carry —
    // Research and Ideas live in the top nav and the hero; repeating them here was noise.
    colR.appendChild(el('div', { class: 'card card-slim', id: 'command-bar' },
      UI.cardHeader('Tools'),
      el('div', { class: 'btn-row', style: 'flex-wrap:wrap' },
        [['New Plan', '#/research', 'target'], ['Construct portfolio', '#/portfolio/construct', 'grid'],
          ['Simulated market', '#/data/simulation', 'flask']].map(function (a) {
          return el('button', { class: 'btn btn-sm btn-secondary',
            onclick: function () { App.navigate(a[1]); } }, icon(a[2], 14), ' ', a[0]);
        }))));
    colR.appendChild(pulseAnchor); // sector pulse LAST: on phones the next action leads
    // The tour's quiet home: a muted line at the very bottom (review P5 — onboarding is
    // reachable, never a standing primary action). Same id so muscle memory + tests hold.
    root.appendChild(el('p', { class: 'muted small home-about-row' },
      el('a', { href: '#/home/tour', id: 'home-tour-link',
        title: 'The opening page: what StrikeBench is, how it works, the live engine demo' },
        'About StrikeBench \u00b7 take the tour')));

    // Wait for the fills so data-ready means READY (tests and users agree on that).
    await Promise.all([planLibraryFill, marketsFill, tradesFill]);
  }

  /** Under ~a day old — the window where the spread-cost note earns its keep. */
  /** Raw pricing-tier enums, in words a person reads. */
  function prettyPricingMode(mode) {
    return {
      HISTORICAL_CHAIN: 'real option history (observed chains)',
      OBSERVED_FROM_HISTORY: 'real option history (observed marks)',
      MODELED_FROM_UNDERLYING: 'modeled from the stock\u2019s own moves (no real option history)',
      PAYOFF_ONLY: 'payoff math only (no pricing data)'
    }[mode] || mode;
  }

  function isYoungTrade(createdAt) {
    if (!createdAt) return false;
    var t = new Date(createdAt).getTime();
    return isFinite(t) && (Date.now() - t) < 26 * 3600 * 1000;
  }

  /**
   * ProductHero primitive (review addendum D / sequence #7): ONE hero composition — frame,
   * eyebrow, title, sub, CTA row, optional aside — with a variant class. Welcome (landing)
   * and Home (operational desk) stay DIFFERENT pages sharing identical bones.
   */
  /**
   * ONE structural class system (`.product-hero` / `.ph-inner` / `.ph-text` / `.ph-ctas`) with
   * variant modifiers. Welcome and Home share the frame without sharing their product job.
   */
  function heroBlock(variant, opts) {
    if (variant === 'welcome') {
      return el('section', { class: 'product-hero ph-welcome hero', id: opts.id || null },
        opts.deco || null,
        el('div', { class: 'ph-inner hero-inner' },
          el('div', { class: 'ph-text' },
            opts.top || (opts.eyebrow ? el('div', { class: 'eyebrow' }, opts.eyebrow) : null),
            opts.title,
            opts.sub || null),
          opts.ctas ? el('div', { class: 'ph-ctas hero-ctas' }, opts.ctas) : null,
          opts.extras || null));
    }
    return el('section', { class: 'product-hero ph-dashboard home-hero' },
      el('div', { class: 'ph-inner home-hero-top' },
        el('div', { class: 'ph-text home-hero-text' },
          el('div', { class: 'eyebrow' }, opts.eyebrow),
          opts.title,
          opts.sub || null),
        opts.aside || null,
        opts.ctas ? el('div', { class: 'ph-ctas home-hero-ctas' }, opts.ctas) : null));
  }

  /** Keyboard + AT semantics for clickable tiles/rows (review #12: no mouse-only navigation). */
  function pressable(node, label, role) {
    node.setAttribute('role', role || node.getAttribute('role') || 'link');
    node.setAttribute('tabindex', '0');
    if (label) node.setAttribute('aria-label', label);
    node.addEventListener('keydown', function (e) {
      if (e.target !== node) return; // nested buttons own their own keyboard activation
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); node.click(); }
    });
    return node;
  }

  // ---------- 2. Research ----------

  async function research(root, params, embedded) {
    var inPlan = !!(embedded && embedded.plan);
    var provisionalResearch = !!(embedded && embedded.provisional);
    var symbol = inPlan ? embedded.plan.symbol
      : params && params[0] ? decodeURIComponent(params[0]).toUpperCase() : '';
    if (symbol) App.context.selectSymbol(symbol);
    var input = el('input', { type: 'text', id: 'symbol-input', placeholder: 'Ticker, e.g. AAPL', value: symbol });
    var go = function () {
      if (input.value.trim()) App.navigate('#/research/' + encodeURIComponent(input.value.trim().toUpperCase()));
    };

    if (!inPlan || provisionalResearch) root.appendChild(el('h1', {}, 'Research'));
    var recentBox = el('div', { class: 'research-recents', id: 'recent-symbols' });
    var researchContext = UI.symbolContext({
      mode: 'editable', id: 'research-symbol-context', input: input,
      label: symbol ? 'Change stock' : Learn.currentLevel() === 'beginner'
        ? 'Which stock do you want to understand?' : 'Research symbol',
      compact: !!symbol, showStatus: !symbol, hideLabel: true, class: 'symbol-context-purpose',
      commitLabel: symbol ? 'Go' : 'Open analysis', commitId: 'symbol-go', onCommit: go
    });
    var contextExtras = el('div', { class: 'symbol-context-extras' },
      symbol ? el('a', { href: '#/research', class: 'context-extras-action', id: 'back-to-sectors' }, 'All sectors') : null,
      recentBox);
    if (!inPlan || provisionalResearch) root.appendChild(el('div', { class: 'research-symbol-shell' }, researchContext, contextExtras));
    function renderRecents() {
      recentBox.innerHTML = '';
      var list = recentSymbols();
      if (!list.length) return;
      recentBox.appendChild(el('span', { class: 'context-extras-label' }, 'Recent'));
      list.forEach(function (s) {
        recentBox.appendChild(el('button', { class: 'sym-chip' + (s === symbol ? ' active' : ''),
          onclick: function () { App.navigate('#/research/' + encodeURIComponent(s)); } }, s));
      });
    }
    renderRecents();

    if (!symbol) {
      // Non-blocking: paint into a captured child and fill it detached, so the route returns
      // immediately (navigating away is never trapped behind the sector-quote batch).
      var idxBody = el('div', { id: 'research-index-body' });
      root.appendChild(idxBody);
      var _it = App.navToken;
      (async function fillIndex() {
        await sectorExplorer(idxBody, 'research');
        if (!App.alive(_it)) return;
        idxBody.appendChild(universePlanScout());
        idxBody.appendChild(await studyToolsSection());
        if (App.state.explorerScroll != null) {
          var restoreY = App.state.explorerScroll;
          App.state.explorerScroll = null;
          App.restoreScroll(restoreY);
        }
      })();
      return;
    }

    var section = embedded && embedded.stage || 'understand';
    if (section === 'evidence') {
      root.appendChild(planAnalysisSourceControl(embedded.plan));
      root.appendChild(testYourViewSection(symbol, embedded.plan));
      return;
    }

    // Understand and Strategy consume the same research producer without recreating a local
    // symbol workspace. Understand owns quote/history/events/news; Strategy temporarily owns
    // the existing option-chain/action surface until the full strategy move in P3.
    var heroCard = el('div', { class: 'card', id: 'research-hero' }, UI.spinner('Loading ' + symbol + '…'));
    if (section === 'understand') root.appendChild(heroCard);
    var researchP = API.get('/api/research/' + symbol);
    var researchPanels = {
      overview: el('section', { class: 'plan-understand-grid', id: 'plan-understand-content' }),
      options: el('section', { class: 'plan-strategy-market', id: 'plan-strategy-market' })
    };
    root.appendChild(section === 'strategy' ? researchPanels.options : researchPanels.overview);

    if (section === 'understand') researchPanels.overview.appendChild(comingUp(symbol, false));
    var newsOverviewCard = el('div', { class: 'card', id: 'news-overview-card' },
      UI.cardHeader('Latest news & filings'), UI.spinner('Loading headlines…'));
    if (section === 'understand') researchPanels.overview.appendChild(newsOverviewCard);
    var actionsAnchor = el('div', { id: 'symbol-actions-anchor' }); // filled after r: the action cards, or the no-options warning
    if (section === 'strategy') researchPanels.options.appendChild(actionsAnchor);

    // Price history — its own endpoint, independent of /api/research.
    if (section === 'understand') researchPanels.overview.appendChild(el('div', { class: 'card', id: 'history-card' },
      UI.cardHeader('Price history'),
      UI.rangeChart({ initial: '1y', fetch: historyFetch(symbol) }),
      explain('When observed OHLC is available, candles show open/high/low/close and volume. Close-only imports render as a line instead of inventing ranges. The evidence badge names whether history is observed, simulated, modeled, or Demo.')));

    var chainAnchor = el('div', { id: 'chain-anchor' });
    if (section === 'strategy') researchPanels.options.appendChild(chainAnchor);

    // News & filings ALWAYS render (independent endpoint) — a card that silently vanishes reads as
    // "where has news gone?", not as an empty feed.
    var newsCard = el('div', { class: 'card', id: 'news-card' }, UI.cardHeader('News & filings'), UI.spinner('Loading news…'));
    if (section === 'understand') {
      newsCard.hidden = true;
      researchPanels.overview.appendChild(newsCard);
    }
    if (section === 'understand') (async function fillNews() {
      var newsItems = [];
      try { newsItems = ((await API.get('/api/research/' + symbol + '/news')).items || []); }
      catch (e) { /* empty state below */ }
      var researchMeta = null;
      try { researchMeta = await researchP; } catch (e) { /* the hero owns the visible research error */ }
      var demoNews = App.state.world === 'demo'
        || (newsItems.length > 0 && newsItems.every(function (n) { return /fixture|demo/i.test(n.source || ''); }));
      var demoFundLike = !!(researchMeta && researchMeta.quote
        && /\b(fund|etf|index)\b/i.test(researchMeta.quote.description || ''));
      var demoPrompts = demoFundLike
        ? ['Macro conditions shift inside this horizon', 'Fund flows or index composition change',
          'Broad-market volatility changes the path']
        : ['An earnings-like jump occurs inside this horizon', 'Expectations change before the horizon',
          'Volatility reprices around a catalyst'];
      var isFiling = function (n) { return !demoNews
        && (/edgar|sec/i.test(n.source || '') || /filing$/i.test(n.headline || '')); };
      function newsContext(n) {
        var h = String(n.headline || '').toLowerCase();
        if (isFiling(n)) return 'Official company disclosure. Open the source to inspect the filing type, dates and material changes.';
        if (/earnings|results|revenue|guidance|outlook/.test(h)) return 'Results or guidance can reset price and implied volatility. Check the actual release and whether it falls inside your horizon.';
        if (/regulat|probe|lawsuit|antitrust|recall/.test(h)) return 'Regulatory or legal news can create gap risk. Verify the scope and source before changing a thesis.';
        if (/analyst|upgrade|downgrade|price target/.test(h)) return 'Analyst opinion, not a company disclosure. Inspect the changed assumptions rather than relying on the headline.';
        if (/launch|product|approval|contract|deal/.test(h)) return 'A business catalyst may affect growth expectations. Open the source to judge timing, scale and whether it is already priced in.';
        return 'Headline-based context only. Open the source before treating it as evidence or a catalyst.';
      }
      function newsTile(n, index) {
        var when = n.publishedEpochMs ? UI.fmtDate(n.publishedEpochMs) : '';
        var headline = demoNews ? demoPrompts[index % demoPrompts.length] : n.headline;
        return el('article', { class: 'news-tile' },
          el('div', { class: 'news-meta' },
            el('span', { class: 'badge ' + (demoNews ? 'badge-warn' : 'badge-dim') },
              demoNews ? 'FABRICATED CATALYST' : isFiling(n) ? 'FILING' : 'NEWS'),
            el('span', { class: 'muted' }, demoNews ? 'Demo teaching market' : [when, n.source].filter(Boolean).join(' · '))),
          n.url && !demoNews ? el('a', { class: 'news-headline', href: n.url, target: '_blank', rel: 'noopener' }, headline)
            : el('b', { class: 'news-headline' }, headline),
          el('p', { class: 'muted news-context' }, demoNews
            ? 'A hypothetical stress prompt for this Plan. It is not a claim about ' + symbol + ' and never becomes evidence.'
            : newsContext(n)));
      }
      newsOverviewCard.innerHTML = '';
      newsOverviewCard.appendChild(UI.cardHeader(demoNews ? 'Teaching catalysts — fabricated' : 'Latest news & filings',
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', id: 'open-news-tab',
          onclick: function () {
            newsOverviewCard.hidden = true;
            newsCard.hidden = false;
            newsCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
          } }, 'View all')));
      newsCard.innerHTML = '';
      newsCard.appendChild(UI.cardHeader(demoNews ? 'Teaching catalysts — fabricated' : 'News & filings',
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
          onclick: function () { newsCard.hidden = true; newsOverviewCard.hidden = false; } }, 'Show latest')));
      if (!newsItems.length) {
        newsOverviewCard.appendChild(el('p', { class: 'muted' },
          'No headline or filing source answered for ' + symbol + '. Price research remains available.'));
        newsCard.appendChild(UI.emptyState('No headlines right now',
          'No news source answered for ' + symbol + '. See the Data screen for per-source health.',
          'Check data status', function () { App.navigate('#/data/overview'); }));
        return;
      }
      var headlines = newsItems.filter(function (n) { return !isFiling(n); }).slice(0, 8);
      var filings = newsItems.filter(isFiling).slice(0, 5);
      newsOverviewCard.appendChild(el('div', { class: 'news-grid news-grid-overview' },
        newsItems.slice(0, 3).map(newsTile)));
      newsOverviewCard.appendChild(el('p', { class: 'muted small news-honesty' },
        demoNews
          ? 'These are generated scenario prompts, not news about ' + symbol + '. They never become observed evidence or link to a story.'
          : 'Context is classified from the headline and source; it is not an article summary. Open the source for the facts.'));
      if (headlines.length) {
        newsCard.appendChild(el('h3', { class: 'news-section-title' }, 'Headlines'));
        newsCard.appendChild(el('div', { class: 'news-grid' }, headlines.map(newsTile)));
      }
      if (filings.length) {
        newsCard.appendChild(el('h3', { class: 'news-section-title' }, 'Corporate filings (SEC)'));
        if (Learn.currentLevel() === 'beginner') {
          newsCard.appendChild(explain('Official documents companies must file: 10-K (annual report), 10-Q (quarterly), 8-K (something important just happened). Big moves often start here.'));
        }
        newsCard.appendChild(el('div', { class: 'news-grid' }, filings.map(newsTile)));
      }
      newsCard.appendChild(el('p', { class: 'muted small news-honesty' }, demoNews
        ? 'Fabricated teaching catalysts remain inside Demo and are never stored or presented as observed news.'
        : 'StrikeBench does not summarize article bodies here. Headline context is a navigation aid, not evidence by itself.'));
    })();

    // Hero + option-dependent sections fill WITHOUT blocking the route: research() returns after
    // painting the shell above, so a new navigation is never trapped behind this fetch. A stale
    // fill (the user already navigated away) bails on the render token.
    var _rt = App.navToken;
    researchP.then(function (r) {
      if (!App.alive(_rt)) return;
      rememberRecent(symbol);
      renderRecents();
      var q = r.quote;

      // Live hero price: the MarketStore feeds it straight from the SSE stream — zero extra
      // GETs per tick (holistic review Phase 3). The light quote fetch survives only as a
      // fallback for the first frame after entering a world.
      if (App.Market && App.state.world && App.state.world !== 'observed') {
        function paintHero(row) {
          var px = document.getElementById('research-px');
          if (px) px.textContent = fmtNum(row.last);
          var d = px && px.nextElementSibling;
          if (d && d.classList.contains('delta')) d.replaceWith(UI.delta(row.last, row.prevClose));
        }
        App.Market.subscribe(function () {
          if (!App.alive(_rt)) return;
          var row = App.Market.get(symbol);
          if (row) paintHero(row);
        }, _rt);
        App.onEvent && App.onEvent('world.tick', async function () {
          if (!App.alive(_rt) || App.Market.get(symbol)) return; // store covers it — no fetch
          try {
            var fresh = await API.getFresh('/api/quotes?symbols=' + encodeURIComponent(symbol));
            var row = fresh && fresh.quotes && fresh.quotes[0];
            if (App.alive(_rt) && row) paintHero(row);
          } catch (e) { /* next tick retries */ }
        }, _rt);
      }

    var evProfile = r.evidence || {};
    var evInputs = evProfile.inputs || {};
    var evSummary = evProfile.summary || q.evidence;
    var inputKeys = Object.keys(evInputs);
    var sig = function (e) { return e ? [e.provenance, e.age, e.source].join('|') : ''; };
    var allSame = inputKeys.length > 1 && inputKeys.every(function (key) { return sig(evInputs[key]) === sig(evSummary); });
    var evidenceDetail = inputKeys.length && !allSame ? UI.expandable('Input evidence detail', function () {
      return el('div', { class: 'chip-row evidence-detail-row' }, inputKeys.map(function (key) {
        return el('span', { class: 'evidence-input' }, key + ' ', UI.evidenceBadge(evInputs[key], { compact: true }));
      }));
    }) : null;
    var hasAvailableInput = inputKeys.some(function (key) {
      return evInputs[key] && String(evInputs[key].provenance || '').toUpperCase() !== 'MISSING';
    });
    var evidenceLine = el('div', { class: 'page-evidence', id: 'research-evidence' },
      el('b', {}, 'Evidence for this analysis'), UI.evidenceBadge(evSummary, {
        missingLabel: hasAvailableInput ? 'PARTIAL EVIDENCE' : 'DATA UNAVAILABLE'
      }),
      evSummary && evSummary.provenance === 'MISSING'
        ? el('span', { class: 'muted' }, hasAvailableInput
          ? 'Available inputs remain usable; each unavailable calculation below names the evidence it still needs.'
          : 'Calculations are unavailable rather than filled with demo data.')
        : evSummary && evSummary.provenance === 'MIXED'
          ? el('span', { class: 'muted' }, 'Inputs come from different evidence types; each is named below.') : null,
      allSame ? el('span', { class: 'muted small' }, 'All inputs use this same evidence basis.') : null,
      el('span', { class: 'spacer' }),
      evidenceDetail);

    var secondaryMarketDetail = UI.expandable(
      Learn.currentLevel() === 'beginner' ? 'More market detail' : 'Quote, volatility & benchmark detail',
      function () {
        var detailChips = [chip('Prev close', reported(q.prevClose, fmtNum))];
        if (r.ivRankPct !== undefined && r.ivRankPct !== null) {
          detailChips.push(chip(el('span', {}, 'IV rank', UI.info('ivrank')), fmtNum(r.ivRankPct, 0) + '%'));
        } else {
          var ivNeed = r.marketLane === 'OBSERVED'
            ? el('a', { href: '#/data/sources' }, String(r.ivHistoryDays || 0) + '/'
              + String(r.ivRankRequiredDays || 10) + ' observed snapshot days')
            : 'not available in a generated market';
          detailChips.push(chip(el('span', {}, 'IV rank', UI.info('ivrank')), ivNeed));
        }
        detailChips.push(chip('Options', r.optionable ? 'yes' : 'no'));
        return el('div', {},
          el('div', { class: 'chip-row' }, detailChips),
          explain('IV is the move the options market is pricing in; HV is what the stock actually did lately. IV far above HV means options are relatively expensive to buy and richer to sell.'),
          (r.benchmarks && r.benchmarks.length) ? el('div', { class: 'benchmark-strip' },
            el('span', { class: 'muted small' }, 'Market context'),
            el('div', { class: 'chip-row' }, r.benchmarks.map(function (b) {
              return chip(b.symbol, fmtNum(b.last));
            }))) : null);
      }, { open: !!(window.matchMedia && window.matchMedia('(min-width: 900px)').matches) });
    var displayPrice = r.displayPrice !== undefined && r.displayPrice !== null ? r.displayPrice : q.last;
    var hero = el('div', { class: 'card research-hero-card', id: 'research-hero' },
      el('div', { class: 'quote-hero' },
        el('span', { class: 'sym', id: 'research-symbol' }, symbol),
        el('span', { class: 'px', id: 'research-px' }, fmtNum(displayPrice)),
        r.priceIsPreviousClose ? el('span', { class: 'muted small' }, 'previous close') : UI.delta(q.last, q.prevClose),
        evSummary && evSummary.provenance === 'DEMO' ? null : badge(r.freshness),
        el('span', { class: 'spacer' }),
        el('button', { class: 'btn btn-sm', id: 'plan-understand-next', onclick: async function () {
          try {
            if (embedded && embedded.onBeginEvidence) { await embedded.onBeginEvidence(); return; }
            var moved = await PlanStore.setStage(embedded.plan, 'EVIDENCE');
            App.navigate(PlanStore.path(moved, 'EVIDENCE'));
          } catch (e) { UI.toast(e.message, 'error'); }
        } }, embedded && embedded.plan && embedded.plan.context && embedded.plan.context.thesis
          ? 'Test ' + embedded.plan.context.thesis + ' view' : 'Set and test a market view')),
      el('div', { class: 'nm', style: 'margin-top:2px' }, q.description || ''),
      evidenceLine,
      el('div', { class: 'chip-row market-facts' },
        chip(el('span', {}, Learn.currentLevel() === 'beginner' ? 'Buy / sell quote' : 'Bid / ask', UI.info('bidask')), fmtNum(q.bid) + ' / ' + fmtNum(q.ask)),
        chip(el('span', {}, Learn.currentLevel() === 'beginner' ? 'Today\'s range' : 'Day range', UI.info('dayrange')),
          q.dayLow === null || q.dayLow === undefined || q.dayHigh === null || q.dayHigh === undefined
            ? 'not reported' : fmtNum(q.dayLow) + ' \u2013 ' + fmtNum(q.dayHigh)),
        chip(el('span', {}, Learn.currentLevel() === 'beginner' ? 'Options-implied move' : 'ATM IV', UI.info('atmiv')),
          reported(r.ivAtm, fmtPct)),
        chip(el('span', {}, Learn.currentLevel() === 'beginner' ? 'Recent movement' : 'HV 30d', UI.info('hv30')),
          r.hv30 !== undefined && r.hv30 !== null ? fmtPct(r.hv30)
            : r.marketLane === 'OBSERVED'
              ? el('a', { href: '#/data/sources' }, String(r.hvHistoryDays || 0) + '/'
                + String(r.hvRequiredDays || 20) + ' daily closes · add history')
              : 'needs 20 daily closes in this market')),
      secondaryMarketDetail);
    if (section === 'understand') heroCard.replaceWith(hero);

    if (section === 'strategy' && !r.optionable) {
      actionsAnchor.replaceWith(alertBox('warn', symbol + ' has no listed options (mutual funds and some securities cannot be option-traded). You can still study its price history below.'));
    }

    // Option chain (fills the anchor placed above the news card, so page order is preserved).
    if (section === 'strategy' && r.optionable && r.expirations && r.expirations.length) {
      var showAll = false;
      var chainBody = el('div', {});
      var select = el('select', { id: 'expiration-select' }, r.expirations.map(function (d) {
        return el('option', { value: d }, d + '  (' + daysUntil(d) + 'd)');
      }));
      select.addEventListener('change', function () { loadChain(select.value); });
      var toggle = el('button', { class: 'btn btn-secondary btn-sm', id: 'chain-toggle', onclick: function () {
        showAll = !showAll;
        toggle.textContent = showAll ? 'Focus near the money' : 'Show all strikes';
        loadChain(select.value);
      } }, 'Show all strikes');
      var chainCard = el('div', { class: 'card' },
        UI.cardHeader('Option chain', toggle),
        explain('Each row is one strike: calls on the left, puts on the right. Green-tinted cells are in the money; the highlighted row is closest to the current price.'),
        el('div', { class: 'btn-row', style: 'margin-top:0' },
          el('label', { class: 'muted', for: 'expiration-select' }, 'Expiration '), select),
        chainBody);
      chainAnchor.replaceWith(chainCard);

      var chainSeq = 0;
      var loadChain = async function (exp) {
        // Sequence token: a slow response for a previously-selected expiration must never
        // overwrite the chain the user is currently looking at.
        var seq = ++chainSeq;
        chainBody.innerHTML = '';
        chainBody.appendChild(UI.spinner('Loading chain…'));
        var chain;
        try { chain = await API.get('/api/research/' + symbol + '/chain?expiration=' + exp); }
        catch (e) {
          if (seq !== chainSeq) return;
          chainBody.innerHTML = '';
          chainBody.appendChild(alertBox('warn', 'Chain unavailable for ' + exp));
          return;
        }
        if (seq !== chainSeq) return; // a newer expiration was selected while this one loaded
        chainBody.innerHTML = '';
        var byStrike = {};
        (chain.calls || []).forEach(function (c) { byStrike[c.strike] = { call: c }; });
        (chain.puts || []).forEach(function (p) { (byStrike[p.strike] = byStrike[p.strike] || {}).put = p; });
        var strikes = Object.keys(byStrike).map(parseFloat).sort(function (a, b) { return a - b; });
        var spot = parseFloat(chain.underlyingPrice);
        var atmIdx = 0, best = Infinity;
        strikes.forEach(function (k, i) { var d = Math.abs(k - spot); if (d < best) { best = d; atmIdx = i; } });
        var visible = showAll ? strikes : strikes.slice(Math.max(0, atmIdx - 6), atmIdx + 7);

        var learning = Learn.currentLevel() === 'beginner';
        var rows = visible.map(function (k) {
          var pair = byStrike[k] || {};
          var c = pair.call || {}, p = pair.put || {};
          var isAtm = k === strikes[atmIdx];
          if (learning) {
            return el('tr', { class: isAtm ? 'atm' : '' },
              el('td', {}, buildBtn('CALL')),
              el('td', { class: k < spot ? 'itm' : '' }, c.bid !== undefined ? fmtNum(c.bid) + ' / ' + fmtNum(c.ask) : '—'),
              el('td', {}, el('b', {}, stripZeros(k))),
              el('td', { class: k > spot ? 'itm' : '' }, p.bid !== undefined ? fmtNum(p.bid) + ' / ' + fmtNum(p.ask) : '—'),
              el('td', {}, buildBtn('PUT')));
          }
          function buildBtn(type) {
            return el('button', { class: 'btn btn-sm btn-secondary chain-build', title: 'Start a build with this ' + type.toLowerCase() + ' leg in the strategy builder',
              onclick: function () {
                var button = this;
                var seed = { symbol: symbol, qty: 1,
                  goal: embedded.plan && embedded.plan.intent || 'BROWSE', templateKey: null,
                  step: 4, legIdx: 0,
                  legs: [{ action: 'BUY', type: type, strike: String(k), expiration: select.value, ratio: 1 }], excluded: {} };
                if (typeof embedded.onBuildLeg === 'function') embedded.onBuildLeg(seed);
                else {
                  visibleCommand(button, function () {
                    return startPlan({ symbol: symbol, intent: seed.goal }, 'STRATEGY').then(function (plan) {
                      if (!plan) return;
                      var planUi = PlanStore.ui(plan.id);
                      planUi.strategyView = 'builder';
                      planUi.buildState = planUi.buildState || {};
                      planUi.buildState.builderForm = seed;
                      App.render();
                    });
                  }, 'This contract could not be added to a Plan.');
                }
              }, 'aria-label': 'Start a custom package with this ' + type.toLowerCase() }, 'Add');
          }
          return el('tr', { class: isAtm ? 'atm' : '' },
            el('td', {}, buildBtn('CALL')),
            el('td', { class: k < spot ? 'itm' : '' }, c.bid !== undefined ? fmtNum(c.bid) + ' / ' + fmtNum(c.ask) : '—'),
            el('td', { class: k < spot ? 'itm' : '' }, c.delta !== undefined && c.delta !== null ? fmtNum(c.delta) : '—'),
            el('td', { class: k < spot ? 'itm' : '' }, c.iv ? fmtPct(c.iv) : '—'),
            el('td', { class: k < spot ? 'itm' : '', title: 'open interest / volume' },
              (c.openInterest != null ? fmtNum(c.openInterest, 0) : '—') + ' / ' + (c.volume != null ? fmtNum(c.volume, 0) : '—')),
            el('td', {}, el('b', {}, stripZeros(k))),
            el('td', { class: k > spot ? 'itm' : '', title: 'open interest / volume' },
              (p.openInterest != null ? fmtNum(p.openInterest, 0) : '—') + ' / ' + (p.volume != null ? fmtNum(p.volume, 0) : '—')),
            el('td', { class: k > spot ? 'itm' : '' }, p.iv ? fmtPct(p.iv) : '—'),
            el('td', { class: k > spot ? 'itm' : '' }, p.delta !== undefined && p.delta !== null ? fmtNum(p.delta) : '—'),
            el('td', { class: k > spot ? 'itm' : '' }, p.bid !== undefined ? fmtNum(p.bid) + ' / ' + fmtNum(p.ask) : '—'),
            el('td', {}, buildBtn('PUT')));
        });
        chainBody.appendChild(el('div', { class: 'chip-row' },
          chip('Underlying', fmtNum(spot)), chip('Strikes', visible.length + ' of ' + strikes.length), badge(chain.freshness)));
        if (learning) {
          chainBody.appendChild(UI.expandable('How to read this table', function () {
            return el('div', {},
              el('p', {}, 'Each row is one ', UI.term('strike'), '. The left price is the ', UI.term('call'),
                ' (bets on rising), the right is the ', UI.term('put'), ' (bets on falling). Each shows ',
                UI.term('bid/ask', 'bid / ask'), ' — you buy at the ask and sell at the bid.'),
              el('p', {}, 'Green-tinted cells are in the money; the highlighted row is closest to the current price.'));
          }));
        }
        chainBody.appendChild(table(
          learning ? ['Build', 'Call price', 'Strike', 'Put price', 'Build']
                   : ['', 'Call bid/ask', 'Call Δ', 'Call IV', 'C OI/Vol', 'Strike', 'P OI/Vol', 'Put IV', 'Put Δ', 'Put bid/ask', ''], rows));
      };
      loadChain(r.expirations[0]);
      // MATERIAL-MOVE refresh (the admitted chain-cadence leftover): when the streamed spot
      // moves >0.5% from the loaded chain's underlying, refetch the visible expiration —
      // throttled to one reload per 5s, and only while this chain is on screen.
      (function chainCadence() {
        if (!App.Market) return;
        var lastReload = 0, loadedSpot = null;
        var origLoad = loadChain;
        loadChain = async function (exp) { await origLoad(exp); loadedSpot = null; };
        App.Market.subscribe(function () {
          if (!App.alive(_rt) || !chainBody.isConnected) return;
          var q = App.Market.get(symbol);
          if (!q || !isFinite(parseFloat(q.last))) return;
          var live = parseFloat(q.last);
          if (loadedSpot === null) { loadedSpot = live; return; }
          var nowT = Date.now();
          if (Math.abs(live - loadedSpot) / loadedSpot > 0.005 && nowT - lastReload > 5000) {
            lastReload = nowT;
            loadedSpot = live;
            API.invalidate(['/api/research/' + symbol + '/chain']);
            var sel = document.getElementById('expiration-select');
            origLoad(sel ? sel.value : r.expirations[0]);
          }
        }, _rt);
      })();
    }
    }, function (e) {
      if (!App.alive(_rt)) return;
      if (section === 'understand') {
        heroCard.replaceWith(alertBox('danger', 'No data for ' + symbol + '. Check the ticker.'));
      } else {
        actionsAnchor.replaceWith(alertBox('danger', 'No option market for ' + symbol + '. Check the ticker or data status.'));
        chainAnchor.remove();
      }
      // The scenario studio must not offer to simulate futures for a symbol the app just said
      // it knows nothing about — swap it for the same honest empty state its siblings show.
      var wi = document.getElementById('whatif-card');
      if (wi) {
        wi.innerHTML = '';
        wi.appendChild(UI.cardHeader('What COULD happen next?'));
        wi.appendChild(UI.emptyState('Needs a price anchor first',
          (App.config && App.config.fixturesOnly)
            ? 'The Demo market covers AAPL, SPY, QQQ, TSLA and VTSAX — try one of those.'
            : 'Simulations start from the active market lane\'s disclosed price. Check the ticker, or Data for source health.'));
      }
    });
  }

  function universePlanScout() {
    var intent = el('select', { id: 'universe-scout-intent' }, (Learn.INTENTS || []).map(function (meta) {
      return el('option', { value: meta.key }, meta.label);
    }));
    var horizon = el('select', { id: 'universe-scout-horizon' },
      el('option', { value: 'week' }, 'About 1 week'),
      el('option', { value: 'month', selected: '' }, 'About 1 month'),
      el('option', { value: 'quarter' }, 'About 3 months'),
      el('option', { value: '0DTE' }, 'Today (0DTE)'));
    var results = el('div', { id: 'universe-scout-results' });
    var run = el('button', { type: 'button', class: 'btn', id: 'universe-scout-run', onclick: async function () {
      run.disabled = true; run.setAttribute('aria-busy', 'true');
      results.innerHTML = ''; results.appendChild(UI.spinner('Scanning this universe…'));
      try {
        var universe = App.state.universe && App.state.universe.active && App.state.universe.active.symbols || [];
        var risk = document.getElementById('risk-mode');
        var response = await API.post('/api/research/scout', {
          universe: universe, horizons: [horizon.value], maxPicks: 5,
          riskMode: risk ? risk.value : 'conservative', allow0dte: horizon.value === '0DTE',
          intents: [intent.value]
        });
        results.innerHTML = '';
        if (!response.picks || !response.picks.length) {
          results.appendChild(UI.emptyState('No symbol passed this Scout',
            (response.notes || []).join(' ') || 'Try another goal, horizon, or sector.'));
          return;
        }
        var grid = el('div', { class: 'universe-scout-grid' });
        response.picks.forEach(function (pick) {
          var h = (pick.horizons || []).find(function (item) { return item.horizon === horizon.value; })
            || (pick.horizons || [])[0];
          var top = h && h.candidates && h.candidates[0];
          var candidate = top && top.candidate;
          var card = el('article', { class: 'card universe-scout-pick' },
            el('div', { class: 'head' }, el('h3', {}, pick.symbol),
              el('span', { class: 'badge ' + (THESIS_BADGE[pick.signals.thesis] || 'badge-dim') }, pick.signals.thesis)),
            el('div', { class: 'chip-row' },
              chip('Signal confidence', fmtPct(pick.signals.confidence)),
              pick.signals.ivHvRatio !== null && pick.signals.ivHvRatio !== undefined
                ? chip('IV / recent movement', fmtNum(pick.signals.ivHvRatio, 2) + '×') : null,
              candidate ? chip('Leading expression', candidate.displayName) : null),
            el('p', { class: 'muted' }, (pick.signals.rationale || []).slice(0, 2).join(' ')),
            h && h.notes && h.notes.length ? alertBox('caution', h.notes[0], h.notes.slice(1)) : null,
            candidate && top.economics ? window.ViewPlan.economicAssessmentBlock(Object.assign({}, candidate, { economics: top.economics })) : null,
            el('div', { class: 'btn-row' }, el('button', { type: 'button', class: 'btn', onclick: async function () {
              this.disabled = true; this.setAttribute('aria-busy', 'true');
              try {
                var days = Product.Horizon.sessions(horizon.value);
                var plan = await PlanStore.create({ symbol: pick.symbol, intent: pick.intent,
                  thesis: String(pick.signals.thesis || '').toLowerCase(), horizonDays: days,
                  riskMode: risk ? risk.value : 'conservative' });
                await PlanStore.focus(plan, 'UNDERSTAND');
              } catch (e) { UI.toast(e.message, 'error'); this.disabled = false; this.removeAttribute('aria-busy'); }
            } }, 'Open ' + pick.symbol + ' Plan')));
          grid.appendChild(card);
        });
        results.appendChild(grid);
        if (response.notes && response.notes.length) results.appendChild(UI.expandable('Scout notes', function () {
          return el('ul', { class: 'rationale' }, response.notes.map(function (note) { return el('li', {}, note); }));
        }));
      } catch (e) {
        results.innerHTML = ''; results.appendChild(alertBox('danger', 'Scout could not finish', [e.message]));
      } finally { run.disabled = false; run.removeAttribute('aria-busy'); }
    } }, 'Scout this universe');
    return el('section', { class: 'research-plan-scout', id: 'research-plan-scout' },
      el('div', { class: 'section-heading' }, el('div', {}, el('h2', {}, 'Scout for a new Plan'),
        el('p', { class: 'muted' }, 'Scan the current sector when you do not already have a ticker. A pick starts the same six-stage Plan journey.'))),
      el('div', { class: 'card' },
        el('div', { class: 'form-grid grid-3' },
          el('div', { class: 'field' }, el('label', { for: 'universe-scout-intent' }, 'Goal'), intent),
          el('div', { class: 'field' }, el('label', { for: 'universe-scout-horizon' }, 'Horizon'), horizon),
          el('div', { class: 'field field-end' }, run))), results);
  }

  var EARNINGS_RE = /earnings|quarterly results|guidance|earnings call/i;

  function laneDateString() {
    var d = App.Market && App.Market.simTime ? new Date(App.Market.simTime) : new Date();
    try {
      var parts = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York',
        year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(d);
      var byType = {};
      parts.forEach(function (p) { byType[p.type] = p.value; });
      return byType.year + '-' + byType.month + '-' + byType.day;
    } catch (e) { return d.toISOString().slice(0, 10); }
  }

  function calendarDayDistance(fromIso, toIso) {
    return Math.round((Date.parse(toIso + 'T00:00:00Z') - Date.parse(fromIso + 'T00:00:00Z')) / 86400000);
  }

  /**
   * "Coming up" — the dated events that can move a symbol: option expirations, earnings
   * signals from headlines, and the latest SEC filing. One helper, two shapes: a full card
   * on Research, a slim strip above single-symbol trade ideas. Fills itself; never blank —
   * the card form shows an honest note when nothing dated is known.
   */
  function comingUp(symbol, asStrip, preExpirations) {
    var body = el('div', { class: asStrip ? 'chip-row' : 'event-timeline' });
    var host = asStrip
      ? el('div', { class: 'events-strip', id: 'events-strip' }, body)
      : el('div', { class: 'card', id: 'events-card' }, UI.cardHeader('Coming up for ' + symbol), body,
          Learn.currentLevel() === 'beginner'
            ? explain('Dates that can move the stock and its options: expiration days (contracts die at 4pm ET), earnings mentions from headlines, and official SEC filings.')
            : null);
    (async function fill() {
      var had = false;
      function addEvent(kind, title, detail, accent, href, tooltip) {
        if (asStrip) {
          var item = chip(kind, detail, tooltip);
          body.appendChild(href ? el('a', { href: href, target: '_blank', rel: 'noopener', class: 'chip event-chip' },
            el('span', { class: 'chip-label' }, kind), el('b', {}, detail)) : item);
          return;
        }
        body.appendChild(el('article', { class: 'event-timeline-item event-' + (accent || 'dated') },
          el('span', { class: 'event-marker', 'aria-hidden': 'true' }),
          el('div', { class: 'event-copy' },
            el('span', { class: 'eyebrow' }, kind),
            href ? el('a', { href: href, target: '_blank', rel: 'noopener' }, title) : el('b', {}, title),
            el('span', { class: 'muted' }, detail),
            tooltip ? el('span', { class: 'muted small' }, tooltip) : null)));
      }
      // Expirations + news are independent \u2014 fetch them together. If the caller already
      // has the expirations (the research page loads them with the quote), skip that call.
      var exP = (preExpirations && preExpirations.length)
        ? Promise.resolve({ expirations: preExpirations, asOfDate: laneDateString() })
        : API.get('/api/research/' + symbol + '/expirations').catch(function () { return { expirations: [] }; });
      var newsP = API.get('/api/research/' + symbol + '/news').catch(function () { return { items: [] }; });
      try {
        var exDoc = await exP;
        var today = exDoc.asOfDate || laneDateString();
        var ex = (exDoc.expirations || []).filter(function (d) { return calendarDayDistance(today, d) >= 0; });
        ex.slice(0, 3).forEach(function (d) {
          var dte = calendarDayDistance(today, d);
          addEvent('OPTION EXPIRY', d, dte === 0 ? 'Today at the closing bell'
            : dte + ' calendar day' + (dte === 1 ? '' : 's') + ' away',
            dte <= 5 ? 'near' : 'dated');
          had = true;
        });
      } catch (e) { /* chips below may still land */ }
      // The ESTIMATED earnings window from filing cadence — a real (labeled) date, not a keyword.
      try {
        var rsch = await API.get('/api/research/' + symbol).catch(function () { return null; });
        var ee = rsch && rsch.earningsEstimate;
        if (ee && ee.date) {
          addEvent(ee.confirmed ? 'EARNINGS' : 'EARNINGS ESTIMATE', ee.date,
            ee.confirmed ? 'Confirmed event date' : '\u00b1' + ee.windowDays + ' days', 'event', null,
            ee.confirmed ? null : 'Estimated from ' + ee.basis + '; not a confirmed calendar date.');
          had = true;
        }
      } catch (e) { /* estimate is additive */ }
      if (App.state.world !== 'demo') {
        try {
          var items = (await newsP).items || [];
          var earn = items.find(function (n) { return EARNINGS_RE.test(n.headline || ''); });
          if (earn) {
            addEvent('EARNINGS NEWS', earn.headline, 'Headline signal; verify the source', 'event', earn.url);
            had = true;
          }
          var filings = items.filter(function (n) { return /edgar|sec/i.test(n.source || '') || /filing$/i.test(n.headline || ''); })
            .sort(function (a, b) { return (b.publishedEpochMs || 0) - (a.publishedEpochMs || 0); });
          if (filings.length) {
            var f = filings[0];
            var when = f.publishedEpochMs ? UI.fmtDate(f.publishedEpochMs) : '';
            addEvent('LATEST FILING', f.headline.replace(/ filing$/i, ''), when || 'Date unavailable', 'filing', f.url);
            had = true;
          }
        } catch (e) { /* fine */ }
      }
      if (!asStrip && App.state.world && App.state.world !== 'observed' && App.state.world !== 'demo') {
        body.appendChild(el('div', { class: 'event-simulation-note' },
          el('b', {}, 'No invented headlines'),
          el('span', { class: 'muted' }, 'This simulated market generates prices and volatility, not fake news. Use the session event controls to inject a disclosed shock.')));
      }
      if (!had) {
        if (asStrip) { host.remove(); return; } // decoration in Trade — vanish quietly
        body.appendChild(el('span', { class: 'muted' }, 'No dated events are known from the active market sources.'));
      }
    })();
    return host;
  }

  /**
   * History fetcher shared by Research and every symbolPanel: same ranges, same badges,
   * and an HONEST reason when a symbol has no candles — "not enough data for this window"
   * blamed the window when the truth is there is no daily-candle source without a key
   * (Cboe covers quotes/chains only; Stooq blocks non-browser clients).
   */
  function historyFetch(symbol) {
    return async function (range) {
      var hist = await API.get('/api/research/' + symbol + '/history?range=' + range);
      var candles = hist.candles || [];
      var closeOnly = hist.barBasis === 'CLOSE_ONLY' || hist.barBasis === 'MIXED';
      var coverage = hist.coverage || {};
      var partial = candles.length && coverage.complete === false;
      var coverageText = partial
        ? 'Partial coverage: ' + String(coverage.availableSessions || candles.length) + ' observed sessions from '
          + UI.fmtDate(String(coverage.availableFrom) + 'T12:00:00') + ' through '
          + UI.fmtDate(String(coverage.availableTo) + 'T12:00:00')
          + ' for the requested ' + String(hist.range || range).toUpperCase() + ' range.'
        : '';
      var storedAvailability = candles.length
        ? partial
          ? 'These stored observations remain available when the market is closed; closed means no new session updates, not that past observations disappear.'
          : 'Stored observed history through '
            + UI.fmtDate(String(candles[candles.length - 1].date) + 'T12:00:00')
            + ' remains available when the market is closed; closed means no new session updates, not that past observations disappear.'
        : '';
      var provenance = hist.evidence && hist.evidence.provenance;
      var disclosure = provenance === 'DEMO'
        ? 'Fabricated Demo history for teaching; not observed market history.'
        : provenance === 'MODELED'
          ? 'Generated Scenario history; not observed market history.'
          : provenance === 'OBSERVED' && candles.length
            ? [coverageText, storedAvailability,
              closeOnly ? 'Observed closes only; daily highs and lows were not provided.' : '',
              hist.priceBasis === 'ADJUSTED' ? 'Prices are adjusted for corporate actions.' : '']
              .filter(Boolean).join(' ')
            : '';
      return {
        range: hist.range,
        candles: closeOnly ? null : candles,
        series: closeOnly ? candles.map(function (c) { return { date: String(c.date), value: parseFloat(c.close) }; }) : null,
        badge: UI.evidenceBadge(hist.evidence),
        note: disclosure ? el('p', { class: 'muted small history-evidence-note' }, disclosure) : null,
        emptyText: candles.length ? null
          : (App.config && App.config.fixturesOnly)
            ? 'Demo mode ships price history for AAPL, SPY, QQQ and TSLA only \u2014 try one of those.'
            : Learn.currentLevel() === 'beginner'
              ? 'Observed daily history is unavailable for ' + symbol + '. Quotes and option chains may still work; Data explains what each source provides.'
              : 'No observed daily history for ' + symbol + '. Connect or backfill an eligible candle source from Data → Sources & jobs.'
      };
    };
  }

  function missingSparkRow(symbol, note) {
    return { symbol: symbol, available: false, closes: [], note: note,
      evidence: { provenance: 'MISSING', age: 'MISSING', source: 'daily history unavailable' } };
  }

  function missingSparkCopy(row) {
    var note = String(row && row.note || '').toLowerCase();
    if (/deferred|yielded/.test(note)) return 'Chart deferred · open analysis';
    if (/not returned|daily history|no observed/.test(note)) return 'Needs daily history';
    if (/unavailable right now|temporar/.test(note)) return 'Chart temporarily unavailable';
    return 'No daily history';
  }

  /**
   * Viewport-governed sparkline loader. One API batch is capped at 16 so optional chart
   * decoration cannot fan a large universe into a provider burst. Cards enqueue only when
   * visible or near-visible; scrolling therefore completes large simulated worlds instead
   * of leaving every card after number 16 blank.
   */
  function lazySparklines(container, symbols, opts) {
    opts = opts || {};
    symbols = (symbols || []).slice();
    var pending = new Set(), generation = 0, pumping = false, pumpScheduled = false, observer = null;

    function slotFor(sym) {
      return container.querySelector('.sym-card[data-sym="' + sym + '"] .spark-slot');
    }
    function markQueued(sym) {
      var slot = slotFor(sym);
      if (!slot) return;
      slot.innerHTML = '';
      slot.appendChild(el('div', { class: 'spark spark-loading' },
        el('span', { class: 'muted' }, 'chart queued')));
    }
    function enqueue(sym) {
      if (!sym || !container.isConnected || (opts.valid && !opts.valid())) return;
      pending.add(sym);
      if (!pumpScheduled) {
        pumpScheduled = true;
        Promise.resolve().then(function () { pumpScheduled = false; pump(); });
      }
    }
    async function pump() {
      if (pumping) return;
      pumping = true;
      var activeBatch = [];
      try {
        while (pending.size && container.isConnected && (!opts.valid || opts.valid())) {
          var mine = generation;
          var batch = Array.from(pending).slice(0, 16);
          activeBatch = batch;
          batch.forEach(function (sym) { pending.delete(sym); });
          var data = await API.prefetch('/api/sparklines?symbols='
            + encodeURIComponent(batch.join(',')) + '&range=' + encodeURIComponent(opts.range()));
          if (mine !== generation || !container.isConnected || (opts.valid && !opts.valid())) continue;
          if (!data) {
            batch.forEach(function (sym) {
              if (opts.paint) opts.paint(missingSparkRow(sym,
                'Chart deferred to protect interactive market requests. Open analysis to try on demand.'));
              var card = container.querySelector('.sym-card[data-sym="' + sym + '"]');
              if (observer && card) observer.unobserve(card);
            });
            activeBatch = [];
            break;
          }
          var returned = {};
          (data.sparklines || []).forEach(function (row) {
            returned[row.symbol] = true;
            if (opts.paint) opts.paint(row);
            var card = container.querySelector('.sym-card[data-sym="' + row.symbol + '"]');
            if (observer && card) observer.unobserve(card);
          });
          batch.forEach(function (sym) {
            if (returned[sym]) return;
            if (opts.paint) opts.paint(missingSparkRow(sym, 'Daily history was not returned for this symbol.'));
          });
          activeBatch = [];
        }
      } catch (e) {
        activeBatch.forEach(function (sym) {
          if (opts.paint) opts.paint(missingSparkRow(sym, 'Chart unavailable right now; the quote remains usable.'));
          var card = container.querySelector('.sym-card[data-sym="' + sym + '"]');
          if (observer && card) observer.unobserve(card);
        });
      } finally {
        pumping = false;
        if (pending.size && container.isConnected && (!opts.valid || opts.valid())) pump();
      }
    }
    function startObserving() {
      if (observer) observer.disconnect();
      if (window.IntersectionObserver) {
        observer = new IntersectionObserver(function (entries) {
          entries.forEach(function (entry) {
            if (entry.isIntersecting) enqueue(entry.target.getAttribute('data-sym'));
          });
        }, { rootMargin: '240px 0px' });
        symbols.forEach(function (sym) {
          var card = container.querySelector('.sym-card[data-sym="' + sym + '"]');
          if (card) observer.observe(card);
        });
      } else {
        symbols.forEach(enqueue);
      }
    }
    function reload() {
      generation++;
      pending.clear();
      symbols.forEach(markQueued);
      startObserving();
    }
    reload();
    return {
      reload: reload,
      disconnect: function () { generation++; pending.clear(); if (observer) observer.disconnect(); }
    };
  }

  // One ETF proxy per sector — used anywhere a sector shows its day move
  var SECTOR_ETF_MAP = { TECH: 'XLK', SEMICONDUCTORS: 'SMH', HEALTHCARE: 'XLV', DEFENSE: 'ITA',
    STAPLES: 'XLP', DISCRETIONARY: 'XLY', ENERGY: 'XLE', FINANCIALS: 'XLF', INDUSTRIALS: 'XLI',
    COMMUNICATIONS: 'XLC', UTILITIES: 'XLU', CORE: 'SPY', ETFS: 'QQQ', DEMO: 'SPY' };

  /**
   * THE sector affordance, everywhere: one horizontally-scrolling rail of pills — sector
   * name + its ETF-proxy day move — with a single active state. Home's pulse, Research's
   * explorer, and Trade's scan scope all render THIS, so sectors look and behave the same
   * on every screen. Renders instantly; the day moves fill in when quotes answer.
   * opts: { id, active, onPick(sector) }
   */
  /** Honest word for study events: only observed history may be called REAL (P0 blocker). */
  function occurrenceWord(r) {
    if (r.observed) return 'real past occurrences';
    return r.evidence === 'DEMO_FIXTURE'
      ? 'DEMO-data occurrences (not real history)'
      : 'generated-scenario occurrences (not real history)';
  }

  /** Page-level history wording follows the active lane; labels never promote Demo/Scenario data. */
  function activeHistoryBasis() {
    if (App.config && App.config.scenarioMode) return {
      plain: 'the active generated Scenario history', expert: 'generated Scenario event study', observed: false
    };
    if (App.state.world === 'demo') return {
      plain: 'fabricated Demo history (not the real past)',
      expert: 'fabricated Demo history (not the real past)', observed: false
    };
    if (App.state.world && App.state.world !== 'observed') return {
      plain: 'this simulated market\'s generated history', expert: 'simulated-world event study', observed: false
    };
    return { plain: 'the stock\'s observed past', expert: 'observed-history event study', observed: true };
  }

  function sectorRail(opts) {
    opts = opts || {};
    var u = App.state.universe;
    var rail = el('div', { class: 'sector-rail', role: 'group', 'aria-label': 'Market sector' });
    var wrap = el('div', { class: 'sector-rail-wrap', id: opts.id || null });
    if (!u) return wrap;
    // Overflow must be VISIBLE: arrows appear whenever there is more rail off-screen
    // (a fade with no affordance reads as "the list just ends here").
    function arrow(dir) {
      return el('button', {
        class: 'rail-arrow rail-arrow-' + dir, type: 'button',
        'aria-label': dir === 'left' ? 'Scroll sectors left' : 'Scroll sectors right',
        onclick: function () {
          rail.scrollBy({ left: (dir === 'left' ? -1 : 1) * Math.max(160, rail.clientWidth * 0.7), behavior: 'smooth' });
        }
      }, dir === 'left' ? '\u2039' : '\u203A');
    }
    function syncOverflow() {
      var canLeft = rail.scrollLeft > 4;
      var canRight = rail.scrollLeft + rail.clientWidth < rail.scrollWidth - 4;
      wrap.classList.toggle('can-left', canLeft);
      wrap.classList.toggle('can-right', canRight);
    }
    rail.addEventListener('scroll', syncOverflow, { passive: true });
    if (window.ResizeObserver) new ResizeObserver(syncOverflow).observe(rail);
    setTimeout(syncOverflow, 0);
    wrap.appendChild(arrow('left'));
    wrap.appendChild(rail);
    wrap.appendChild(arrow('right'));
    var sectors = u.sectors.filter(function (sec) { return sec.key !== 'DEMO' || u.note; });
    var deltas = {};
    sectors.forEach(function (sec) {
      var d = el('span', { class: 'sr-delta' });
      deltas[sec.key] = d;
      rail.appendChild(el('button', {
        class: 'sector-chip' + (opts.active === sec.key ? ' active' : ''),
        'data-sector': sec.key, type: 'button',
        'aria-pressed': opts.active === sec.key ? 'true' : 'false',
        title: sec.symbols.length + ' symbols',
        onclick: function () {
          var me = this;
          rail.querySelectorAll('.sector-chip').forEach(function (x) {
            x.classList.toggle('active', x === me);
            x.setAttribute('aria-pressed', x === me ? 'true' : 'false');
          });
          opts.onPick(sec);
        }
      }, el('span', {}, sec.label), d));
    });
    (async function fillDeltas() {
      try {
        var pairs = sectors.filter(function (sec) { return SECTOR_ETF_MAP[sec.key]; });
        var etfs = pairs.map(function (sec) { return SECTOR_ETF_MAP[sec.key]; })
          .filter(function (v, i, a) { return a.indexOf(v) === i; });
        var data = await API.get('/api/quotes?symbols=' + etfs.join(','));
        var byEtf = {};
        (data.quotes || []).forEach(function (q) { byEtf[q.symbol] = q; });
        pairs.forEach(function (sec) {
          var q = byEtf[SECTOR_ETF_MAP[sec.key]];
          if (!q || !q.prevClose) return;
          var pct = (parseFloat(q.last) - parseFloat(q.prevClose)) / parseFloat(q.prevClose) * 100;
          var d = deltas[sec.key];
          d.className = 'sr-delta ' + (pct >= 0 ? 'gain' : 'loss');
          d.textContent = (pct >= 0 ? '\u25B2' : '\u25BC') + Math.abs(pct).toFixed(2) + '%';
        });
      } catch (e) { /* the rail works without the decoration */ }
      syncOverflow(); // deltas widen the chips — re-measure
    })();
    return wrap;
  }

  /**
   * Rich symbol context in one reusable block: live quote row, the SAME interactive
   * range-pill candle chart Research uses, the coming-up events strip, and top headlines.
   * Expands from sector-explorer tiles and sits behind a quiet expandable in Trade —
   * one chart-and-news language everywhere.
   */
  /**
   * ONE SYMBOL CONTEXT (interaction contract / review IC-4). symbolStatus attaches a live
   * quote/freshness/lane chip to any EDITABLE symbol input: same look, same data, every
   * surface. Debounced, supersede-guarded; failures show honestly as 'no live quote'.
   */
  function symbolPanel(symbol, opts) {
    opts = opts || {};
    symbol = symbol.toUpperCase();
    var panel = el('div', { class: 'symbol-panel', 'data-symbol': symbol });
    var qrow = el('div', { class: 'sp-quote' }, el('b', { class: 'sp-sym' }, symbol));
    panel.appendChild(qrow);
    (async function fillQuote() {
      try {
        var q = ((await API.get('/api/quotes?symbols=' + symbol)).quotes || [])[0];
        if (!q) return;
        qrow.appendChild(el('span', { class: 'sp-px' }, fmtNum(q.last)));
        qrow.appendChild(UI.delta(q.last, q.prevClose));
        if (q.freshness) qrow.appendChild(badge(q.freshness));
        if (q.description) qrow.appendChild(el('span', { class: 'muted sp-nm' }, q.description));
      } catch (e) { /* chart + news still render */ }
    })();
    panel.appendChild(UI.rangeChart({ initial: opts.range || '6m', fetch: historyFetch(symbol) }));
    panel.appendChild(comingUp(symbol, true));
    var newsBox = el('div', { class: 'sp-news' });
    panel.appendChild(newsBox);
    (async function fillNews() {
      try {
        var items = ((await API.get('/api/research/' + symbol + '/news')).items || []).slice(0, opts.newsCount || 3);
        if (!items.length) {
          newsBox.appendChild(el('p', { class: 'muted' }, 'No headlines right now.'));
          return;
        }
        items.forEach(function (n) {
          var demoItem = App.state.world === 'demo' || /fixture|demo/i.test(n.source || '');
          newsBox.appendChild(el('div', { class: 'status-item' },
            demoItem ? el('b', {}, n.headline)
              : el('a', { href: n.url, target: '_blank', rel: 'noopener' }, n.headline),
            el('span', { class: 'spacer' }),
            el('span', { class: demoItem ? 'badge badge-warn' : 'muted' },
              demoItem ? 'FABRICATED CATALYST' : n.source)));
        });
      } catch (e) {
        newsBox.appendChild(el('p', { class: 'muted' }, 'Headlines unavailable right now.'));
      }
    })();
    if (!opts.noActions) {
      // The panel lives where leaving the screen loses work (a Trade form); its one action
      // is the deliberate exit to the destination page — never a duplicate of the host form.
      panel.appendChild(el('div', { class: 'btn-row' },
        el('button', { class: 'btn btn-sm', onclick: function () { App.navigate('#/research/' + encodeURIComponent(symbol)); } }, 'Open full analysis')));
    }
    return panel;
  }

  /**
   * Sector explorer: dig into any sector from any tab — live quote tiles per symbol with
   * one-tap actions (Research / Ideas / Trade), and a one-click "make this my universe".
   * `context` seeds which action leads (research vs ideas).
   */
  async function sectorExplorer(root, context) {
    var u = App.state.universe;
    if (!u) { try { await App.refreshUniverse(); u = App.state.universe; } catch (e) { /* offline */ } }
    if (!u) { root.appendChild(UI.emptyState('Universe unavailable', 'Check the Data screen.')); return; }
    var selectedKey = App.state.explorerSector || u.active.sectorKey || u.sectors[0].key;

    var rail = sectorRail({
      id: 'sector-chips', active: selectedKey,
      onPick: function (sec) { selectedKey = sec.key; App.state.explorerSector = sec.key; load(); }
    });
    var grid = el('div', { class: 'tile-row', id: 'sector-grid' });
    var head = el('div', { class: 'btn-row', style: 'margin-top:0' });
    // ONE shared range selector for every card's sparkline (never per-card controls) + one
    // plain instruction instead of dozens of repeated buttons (review).
    App.state.explorerRange = App.state.explorerRange || '3m';
    var RANGES = [{ k: '1m', l: '1M' }, { k: '3m', l: '3M' }, { k: 'ytd', l: 'YTD' }];
    var rangeRow = el('div', { class: 'range-pills', id: 'spark-range', role: 'group', 'aria-label': 'Sparkline window' },
      RANGES.map(function (r) {
        return el('button', {
          class: 'pill' + (App.state.explorerRange === r.k ? ' active' : ''), type: 'button', 'data-range': r.k,
          onclick: function () {
            App.state.explorerRange = r.k;
            rangeRow.querySelectorAll('.pill').forEach(function (b) {
              b.classList.toggle('active', b.getAttribute('data-range') === r.k);
            });
            if (sparkLoader) sparkLoader.reload(); // same sector, new window; visible cards update in place
          }
        }, r.l);
      }));
    var instruction = el('p', { class: 'muted explorer-hint', id: 'explorer-hint' },
      'Choose a stock to open its full analysis. From there, set a goal when you are ready to make a Plan.');
    var quoteBasis = App.state.world === 'demo'
      ? 'Fabricated Demo quotes for this teaching universe.'
      : App.state.world && App.state.world !== 'observed'
        ? 'Generated quotes from the active simulated market.'
        : 'Observed-source quotes for the selected sector.';
    var historyNotice = el('div', { class: 'alert alert-warn sector-history-notice', id: 'sector-history-notice', hidden: '' });
    var card = el('div', { class: 'card', id: 'sector-explorer' },
      UI.cardHeader('Explore by sector'), rail, head, instruction, rangeRow, historyNotice, grid,
      explain(quoteBasis + ' Open a stock for its full analysis, or point the Scout at a whole sector. '
        + (u.note ? u.note : '')));
    root.appendChild(card);

    var currentSector = null;
    var sparkLoader = null;

    var loadSeq = 0; // supersede token: a slow quote batch for a PREVIOUS sector must not paint over the current one
    async function load() {
      var seq = ++loadSeq;
      if (sparkLoader) { sparkLoader.disconnect(); sparkLoader = null; }
      head.innerHTML = '';
      grid.innerHTML = '';
      historyNotice.hidden = true;
      historyNotice.innerHTML = '';
      grid.appendChild(UI.spinner('Loading sector quotes…'));
      var sector = u.sectors.find(function (s) { return s.key === selectedKey; }) || u.sectors[0];
      currentSector = sector;
      head.appendChild(el('b', {}, sector.label));
      head.appendChild(el('span', { class: 'muted' }, sector.symbols.length + ' symbols'));
      head.appendChild(el('span', { class: 'spacer' }));
      if (u.active.sectorKey !== sector.key) {
        head.appendChild(el('button', {
          class: 'btn btn-sm btn-secondary', id: 'set-universe-btn', onclick: async function () {
            try {
              if (App.state.world && App.state.world !== 'observed') {
                if (UI.toast) UI.toast('You are in a simulated session \u2014 its symbols ARE the market here.');
                return;
              }
              await API.put('/api/universe', { sector: sector.key });
              await App.refreshUniverse();
              App.render();
            } catch (e) { UI.toast(e.message || 'Could not change the active universe', 'error'); }
          }
        }, 'Make this my universe'));
      } else {
        head.appendChild(el('span', { class: 'badge badge-ok' }, 'YOUR UNIVERSE'));
      }
      head.appendChild(el('button', {
        class: 'btn btn-sm', onclick: function () {
          var target = document.getElementById('research-plan-scout');
          if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      }, 'Scout this sector'));
      try {
        var data = await API.get('/api/quotes?symbols=' + sector.symbols.join(','));
        if (seq !== loadSeq) return; // a newer sector was picked while this loaded
        grid.innerHTML = '';
        grid.setAttribute('data-count', String(sector.symbols.length));
        // EVERY symbol gets an actionable tile \u2014 a sector click must always land somewhere
        // clickable. Symbols without a live quote say so honestly but still link onward.
        var bySym = {};
        (data.quotes || []).forEach(function (q) { bySym[q.symbol] = q; });
        // Destination cards open full analysis. Sector, range and scroll stay here so returning
        // to Research restores the market-entry context before a user chooses to create a Plan.
        sector.symbols.forEach(function (s) {
          var q = bySym[s];
          var last = q ? parseFloat(q.last) : null;
          var prev = q ? parseFloat(q.prevClose) : null;
          var pct = q && prev ? (last - prev) / prev * 100 : null;
          var sparkSlot = el('div', { class: 'spark-slot' });
          var tile = el('a', { class: 'tile sector-tile sym-card clickable' + (q ? '' : ' tile-nodata'),
            'data-sym': s,
            href: '#/research/' + encodeURIComponent(s),
            'aria-label': 'Open full analysis for ' + s },
            el('div', { class: 't-sym' }, s,
              q && App.state.world !== 'demo' ? badge(q.freshness) : null,
              q && !q.optionable ? el('span', { class: 'badge badge-dim', style: 'margin-left:6px' }, 'NO OPTIONS') : null,
              q ? null : el('span', { class: 'badge badge-dim', style: 'margin-left:6px' }, 'NO QUOTE')),
            el('div', { class: 't-px' }, q ? last.toFixed(2) : '\u2014'),
            pct === null
              ? el('div', { class: 'muted t-move' }, 'no quote right now')
              : el('div', { class: 't-move ' + (pct >= 0 ? 'gain' : 'loss') }, (pct >= 0 ? '\u25B2 ' : '\u25BC ') + Math.abs(pct).toFixed(2) + '%'),
            q && q.description ? el('div', { class: 't-nm muted' }, q.description) : null,
            sparkSlot,
            el('span', { class: 'destination-cue', 'aria-hidden': 'true' }, icon('chevron-right', 16)));
          tile.addEventListener('click', function (e) {
            // Pointer movement and arrow keys explore the chart; a click still honors the
            // destination-card contract and opens the full analysis from anywhere on the card.
            App.state.explorerScroll = window.scrollY; // Back restores the exact spot
          });
          tile.addEventListener('keydown', function (e) {
            var spark = sparkSlot.firstChild;
            if (spark && spark.exploreKey && spark.exploreKey(e.key)) { e.preventDefault(); }
          });
          grid.appendChild(tile);
        });
        // Return continuity: coming Back from a symbol page lands on the same scroll spot.
        var missingHistory = new Set();
        sparkLoader = lazySparklines(grid, sector.symbols, {
          range: function () { return App.state.explorerRange; },
          valid: function () { return seq === loadSeq && grid.isConnected; },
          paint: function (row) {
            var t2 = grid.querySelector('.sym-card[data-sym="' + row.symbol + '"] .spark-slot');
            if (!t2) return;
            t2.innerHTML = '';
            var missing = row.available === false || !row.closes || !row.closes.length;
            t2.classList.toggle('spark-slot-missing', missing);
            t2.appendChild(UI.sparkline(row, { height: 36, quietMissing: true,
              missingText: missing ? missingSparkCopy(row) : 'No chart' }));
            if (missing) missingHistory.add(row.symbol);
            else missingHistory.delete(row.symbol);
            if (missingHistory.size) {
              historyNotice.hidden = false;
              historyNotice.innerHTML = '';
              historyNotice.appendChild(el('div', {},
                el('b', {}, 'Price charts are unavailable for ' + missingHistory.size + ' symbol' + (missingHistory.size === 1 ? '' : 's')),
                el('p', { class: 'muted small' }, 'Current quotes can still be valid because quotes and daily history use separate sources. StrikeBench does not draw a made-up chart.')));
              historyNotice.appendChild(el('a', { class: 'btn btn-sm btn-secondary', href: '#/data/sources' }, 'Update history in Data'));
            } else historyNotice.hidden = true;
            // Evidence is server-computed and rendered verbatim; availability never implies Observed.
            if (!missing && row.evidence && !(App.state.world === 'demo' && row.evidence.provenance === 'DEMO')) {
              t2.appendChild(UI.evidenceBadge(row.evidence, { className: 'spark-ev', compact: true,
                missingLabel: 'HISTORY UNAVAILABLE' }));
            }
          }
        });
        // LIVE CARDS (review gap): prices follow the market stream in place — a simulated
        // session's ticks repaint the grid without rebuilding it.
        App.Market.subscribe(function (frame) {
          (frame.quotes ? Object.keys(frame.quotes) : []).forEach(function (symKey) {
            var t2 = grid.querySelector('.sym-card[data-sym="' + symKey + '"]');
            if (!t2) return;
            var fq = frame.quotes[symKey];
            var lastF = parseFloat(fq.last), prevF = parseFloat(fq.prevClose);
            var px = t2.querySelector('.t-px');
            if (px && isFinite(lastF)) {
              if (px.textContent !== lastF.toFixed(2)) {
                px.textContent = lastF.toFixed(2);
                t2.classList.remove('live-tick-up', 'live-tick-down');
                void t2.offsetWidth; // restart the tint animation
                t2.classList.add(lastF >= prevF ? 'live-tick-up' : 'live-tick-down');
              }
            }
            var mv = t2.querySelector('.t-move');
            if (mv && isFinite(lastF) && isFinite(prevF) && prevF) {
              var p2 = (lastF - prevF) / prevF * 100;
              mv.className = 't-move ' + (p2 >= 0 ? 'gain' : 'loss');
              mv.textContent = (p2 >= 0 ? '\u25B2 ' : '\u25BC ') + Math.abs(p2).toFixed(2) + '%';
            }
          });
        }, App.navToken);
        if (!(data.quotes || []).length && u.note) {
          grid.appendChild(explain('Demo mode carries data only for the built-in symbols \u2014 each ticker still links to research and ideas so you can explore the flow.'));
        }
      } catch (e) {
        if (seq !== loadSeq) return;
        grid.innerHTML = '';
        grid.appendChild(alertBox('warn', 'Could not load sector quotes', [e.message]));
      }
    }
    await load();
  }

  /** Recently researched symbols — the lookup shortcuts that actually mean something. */
  function recentSymbols() {
    try { return JSON.parse(window.localStorage.getItem('strikebench.recent') || '[]'); }
    catch (e) { return []; }
  }
  function rememberRecent(sym) {
    try {
      var list = recentSymbols().filter(function (s2) { return s2 !== sym; });
      list.unshift(sym);
      window.localStorage.setItem('strikebench.recent', JSON.stringify(list.slice(0, 6)));
    } catch (e) { /* cosmetic */ }
  }

  function daysUntil(iso) {
    return Math.max(0, calendarDayDistance(laneDateString(), iso));
  }

  // The Plan journey and ranked strategy field live in views-plan.js.

  // ---------- 7b. Scenario surfaces (the thesis workbench + "imagine a future") ----------

  /** Research: state a future → convert its distribution into a target/strike/trade decision. */
  function whatIfCard(symbol, seedContext) {
    var level = Learn.currentLevel();
    var historyBasis = activeHistoryBasis();
    seedContext = seedContext || {};
    var ownedState = seedContext.state || null;
    var plan = seedContext.plan || null;
    if (!plan || !ownedState) throw new Error('Possible futures must be owned by a Plan.');
    var card = el('div', { class: 'card', id: 'whatif-card' });
    card.appendChild(UI.cardHeader('Possible futures — turn a view into a decision'));
    card.appendChild(explain(level === 'beginner'
      ? 'Choose a story and horizon, then ask a concrete price question. StrikeBench turns many model-generated futures into a likely range, odds of reaching your level, and — when you have a working trade — its profit and loss on those exact same futures. Past evidence remains a separate check using ' + historyBasis.plain + '.'
      : 'The shared market view seeds this model terminal; its drift, volatility, jumps and IV path remain explicit scenario assumptions. The paired historical lens is a separate ' + historyBasis.expert + ', never a blended probability.'));
    var scenarioFormState = ownedState.scenarioForm = ownedState.scenarioForm || {};
    var f = Scenario.form(level, symbol, seedContext, scenarioFormState);
    card.appendChild(f.el);
    var scenarioTargets = ownedState.scenarioTargets = ownedState.scenarioTargets || {};
    var target = el('input', { type: 'number', id: 'whatif-target', step: 'any', min: '0.01',
      value: scenarioTargets[symbol] || '', placeholder: 'Optional' });
    target.addEventListener('input', function () { scenarioTargets[symbol] = target.value; });
    card.appendChild(el('div', { class: 'scenario-question' },
      el('div', {}, el('label', { for: 'whatif-target' }, 'Price you care about'),
        el('p', { class: 'muted small' }, level === 'beginner'
          ? 'Optional — enter a buy price, sell target, hedge floor, strike, or any level you want the model to answer.'
          : 'Optional decision level. The result separates probability of ending beyond it from probability of touching it first.')),
      target));
    var out = el('div', { id: 'whatif-out' });
    var previewSeq = 0;
    var latest = null;
    var selectedSampleIndex = null;
    var run = el('button', { class: 'btn', id: 'whatif-run' }, 'Analyze this scenario');
    var reroll = el('button', { class: 'btn btn-sm btn-secondary', id: 'whatif-reroll', style: 'display:none' }, 'Run a sampling check');
    function scenarioPrice(value) {
      return '$' + fmtNum(Number(value) || 0, 2);
    }

    function decisionLevels() {
      var levels = [], seen = {};
      function add(key, raw) {
        var value = Number(raw);
        if (!(value > 0) || !isFinite(value) || seen[value.toFixed(6)]) return;
        seen[value.toFixed(6)] = true; levels.push({ key: key, price: value });
      }
      add('target', target.value);
      var candidate = PlanStore.ui(plan.id).selectedCandidate;
      (candidate && candidate.breakevens || []).forEach(function (x, i) { add('breakeven-' + (i + 1), x); });
      var putN = 0, callN = 0;
      (candidate && candidate.legs || []).forEach(function (leg) {
        if (leg.action !== 'SELL' || leg.stock || !leg.strike) return;
        var isPut = String(leg.type).toUpperCase() === 'PUT';
        add((isPut ? 'short-put-' + (++putN) : 'short-call-' + (++callN)), leg.strike);
      });
      return levels;
    }

    function goalAction(p, spec) {
      var t = p.decisionMap.terminal;
      var goal = plan.intent || 'DIRECTIONAL';
      var chosen = Number(target.value);
      if (!(chosen > 0)) chosen = goal === 'EXIT' ? t.p84 : (goal === 'ACQUIRE' || goal === 'HEDGE') ? t.p16 : null;
      var labels = {
        ACQUIRE: 'Find ways to buy near ' + scenarioPrice(chosen),
        EXIT: 'Find ways to sell near ' + scenarioPrice(chosen),
        HEDGE: 'Find protection below ' + scenarioPrice(chosen),
        INCOME: 'Find income strategies for this range',
        DIRECTIONAL: 'Find strategies for this view'
      };
      var action = el('button', { class: 'btn', id: 'whatif-act' }, labels[goal] || labels.DIRECTIONAL);
      action.addEventListener('click', async function () {
        try {
          var moved = await PlanStore.setStage(plan, 'STRATEGY');
          App.navigate(PlanStore.path(moved, 'STRATEGY'));
        } catch (e) { UI.toast(e.message, 'error'); }
      });
      return el('div', { class: 'scenario-next' },
        el('div', {}, el('b', {}, 'Use the result'),
          el('p', { class: 'muted small' },
            'Keep the same symbol, goal, horizon and decision level as this Plan moves into Strategy.')),
        el('div', { class: 'btn-row' }, action));
    }

    function practiceAction(p, spec) {
        if (plan.marketKind === 'SIMULATED') return el('div', { class: 'scenario-practice' },
          el('div', {}, el('b', {}, 'This Plan is already inside one simulated path'),
            el('p', { class: 'muted small' },
              'Use this session’s control room to practice management. Creating a rehearsal of a rehearsal would add no independent evidence.')),
          el('button', { type: 'button', class: 'btn btn-secondary', onclick: function () { App.navigate('#/data/simulation'); } },
            'Open control room'));
        var ensembleId = ownedState && ownedState.planEnsembleId;
        var note = el('div', { class: 'muted small rehearsal-selection-note' },
          selectedSampleIndex == null
            ? 'Choose a representative path below, or click one sample line in the chart.'
            : 'Selected chart sample ' + (selectedSampleIndex + 1) + ' will replay exactly.');
        var selectedButton;
        async function createExact(selection, pathIndex, button) {
          if (!ensembleId) { UI.toast('Run the scenario before creating a rehearsal.', 'error'); return; }
          button.disabled = true;
          try {
            var live = await PlanStore.get(plan.id, true);
            var created = await PlanStore.createRehearsal(live, {
              ensembleId: ensembleId, selection: selection,
              pathIndex: pathIndex == null ? null : pathIndex, speed: 26
            });
            var rehearsal = created.rehearsal;
            App.state.focusSimControlRoom = rehearsal.worldId;
            var switched = await App.switchWorld(rehearsal.worldId, button);
            if (!switched) throw new Error('The exact rehearsal was created but its market could not be entered.');
            App.navigate('#/data/simulation');
          } catch (e) { button.disabled = false; UI.toast(e.message || 'Could not create the rehearsal', 'error'); }
        }
        function choice(label, selection, hint) {
          var button = el('button', { type: 'button', class: 'btn btn-secondary btn-sm',
            title: hint, onclick: function () { createExact(selection, null, button); } }, label);
          return button;
        }
        selectedButton = el('button', { type: 'button', class: 'btn btn-sm', id: 'whatif-rehearse-selected',
          disabled: selectedSampleIndex == null ? 'disabled' : null, onclick: function () {
            createExact('SAMPLE', selectedSampleIndex, selectedButton);
          } }, 'Rehearse selected sample');
        selectedButton.refreshSelection = function () {
          selectedButton.disabled = selectedSampleIndex == null;
          note.textContent = selectedSampleIndex == null
            ? 'Choose a representative path below, or click one sample line in the chart.'
            : 'Selected chart sample ' + (selectedSampleIndex + 1) + ' will replay exactly.';
        };
        return el('div', { class: 'scenario-practice plan-rehearsal-actions' },
          el('div', {}, el('b', {}, 'Rehearse one exact path'),
            el('p', { class: 'muted small' },
              'The simulated exchange will replay the selected spot and IV trajectory from this receipt. It is one management drill, not additional probability evidence.'), note),
          el('div', { class: 'btn-row' },
            choice('Typical path', 'TYPICAL', 'Terminal result nearest this ensemble’s median'),
            choice('Favorable to view', 'FAVORABLE', 'Best path for the Plan thesis'),
            choice('Adverse to view', 'ADVERSE', 'Worst path for the Plan thesis'),
            choice('Drawdown stress', 'STRESS', 'Path with the deepest peak-to-trough fall'),
            selectedButton));
    }

    function scenarioFailure(error) {
      var payload = error && error.payload || {};
      if (payload.error === 'plan_market_mismatch') {
        var market = window.ViewPlan.marketLabel(plan);
        var empty = UI.emptyState('Open this Plan’s ' + market.toLowerCase(),
          'This scenario is bound to the Plan’s ' + market.toLowerCase()
            + '. StrikeBench refused to mix prices from the market currently on screen.',
          'Open ' + market, async function () {
            this.disabled = true;
            try { await PlanStore.focus(plan, 'EVIDENCE'); }
            catch (e) { this.disabled = false; UI.toast(e.message || 'The Plan market could not open.', 'error'); }
          });
        empty.id = 'plan-ensemble-market-mismatch';
        return empty;
      }
      if (error && (error.status === 404 || error.status === 422)) {
        var unavailable = UI.emptyState('Scenario inputs are unavailable',
          error.message || ('The active market cannot price ' + plan.symbol + ' right now.'),
          'Check market data', function () { App.navigate('#/data/overview'); });
        unavailable.id = 'plan-ensemble-input-unavailable';
        return unavailable;
      }
      return alertBox('danger', 'Could not analyze this scenario', [
        error && error.message ? error.message : 'Retry the run, then check Data if the market inputs remain unavailable.'
      ]);
    }

    async function analyze(isSamplingCheck) {
      var seq = ++previewSeq;
      selectedSampleIndex = null;
      run.disabled = true; reroll.disabled = true; out.innerHTML = '';
      out.appendChild(UI.spinner(isSamplingCheck ? 'Checking sampling stability…' : 'Analyzing the scenario…'));
      try {
        var previous = latest;
        if (isSamplingCheck) f.reroll();
        var spec = f.getSpec();
        var payload = { over: spec, iv: f.getIv(), levels: decisionLevels() };
        var planRun = await PlanStore.runEnsemble(plan, payload);
        var p = planRun.preview;
        ownedState.planEnsembleId = planRun.ensemble && planRun.ensemble.id;
        ownedState.planEnsembleFingerprint = planRun.ensemble && planRun.ensemble.fingerprint;
        if (seq !== previewSeq) return;
        latest = p;
        out.innerHTML = '';
        if (isSamplingCheck && previous) {
          var oldT = previous.decisionMap.terminal, newT = p.decisionMap.terminal;
          var move = Math.max(Math.abs(oldT.p16 - newT.p16), Math.abs(oldT.p50 - newT.p50), Math.abs(oldT.p84 - newT.p84));
          out.appendChild(alertBox(move <= p.spot * 0.01 ? 'ok' : 'caution',
            move <= p.spot * 0.01 ? 'Sampling check is stable' : 'This result is seed-sensitive',
            ['The largest change across the central range was ' + scenarioPrice(move)
              + '. Treat the distribution, not any one sample path, as the result.']));
        }
        out.appendChild(Scenario.decisionView(p, level));
        out.appendChild(el('div', { class: 'scenario-chart-head' },
          el('div', {}, el('h3', {}, 'How the range unfolds'),
            el('p', { class: 'muted small' }, 'Shaded range and median by day; sample lines are illustrative paths, not forecasts to choose from.'))));
        var chart = Scenario.fanChart(p, { onSelectSample: function (index) {
          selectedSampleIndex = index;
          var button = out.querySelector('#whatif-rehearse-selected');
          if (button && button.refreshSelection) button.refreshSelection();
          var note = out.querySelector('.rehearsal-selection-note');
          if (button) button.disabled = index == null;
          if (note) note.textContent = index == null
            ? 'Choose a representative path below, or click one sample line in the chart.'
            : 'Selected chart sample ' + (index + 1) + ' will replay exactly.';
        } });
        out.appendChild(chart);
        out.appendChild(goalAction(p, spec));
        var rehearsalActions = practiceAction(p, spec);
        out.appendChild(rehearsalActions);
        reroll.style.display = '';
        if (typeof App.refreshWorkflowContext === 'function') App.refreshWorkflowContext();
      } catch (e) {
        if (seq === previewSeq) { out.innerHTML = ''; out.appendChild(scenarioFailure(e)); }
      }
      finally { if (seq === previewSeq) { run.disabled = false; reroll.disabled = false; } }
    }
    run.addEventListener('click', function () { analyze(false); });
    reroll.addEventListener('click', function () { analyze(true); });
    card.appendChild(el('div', { class: 'btn-row' }, run, reroll));
    card.appendChild(out);
    return card;
  }


  // Data Center lives in views-data.js.

  // ---- Shared Plan research workbench components ----

  /** A card header with an icon-tile accent, like the recommendation surfaces. */
  function toolHeader(iconName, title, right) {
    return UI.cardHeader(
      el('span', { class: 'tool-title' }, el('span', { class: 'icon-tile icon-tile-sm' }, icon(iconName)), el('b', {}, title)),
      right || null);
  }

  /** A 0..1 bar with a baseline marker; fill greens above baseline, reds below (SVG, hover). */
  function gaugeChart(value01, baseline01, caption) {
    var v = Math.max(0, Math.min(1, value01)) * 100, b = Math.max(0, Math.min(1, baseline01)) * 100;
    var col = value01 >= baseline01 ? 'var(--risk-ok-solid,#3aa76d)' : 'var(--risk-danger-solid,#d64545)';
    var svg = '<svg viewBox="0 0 100 16" preserveAspectRatio="none" width="100%" height="16" role="img">'
      + '<rect x="0" y="5" width="100" height="6" rx="3" fill="var(--border)"/>'
      + '<rect x="0" y="5" width="' + v.toFixed(2) + '" height="6" rx="3" fill="' + col + '"><title>' + Math.round(v) + '%</title></rect>'
      + '<line x1="' + b.toFixed(2) + '" y1="1.5" x2="' + b.toFixed(2) + '" y2="14.5" stroke="var(--text)" stroke-width="0.8"/>'
      + '<circle cx="' + v.toFixed(2) + '" cy="8" r="2.4" fill="' + col + '" stroke="var(--surface)" stroke-width="0.8"/></svg>';
    return el('div', {}, el('div', { class: 'tool-chart', html: svg }),
      caption ? el('div', { class: 'muted small' }, caption) : null);
  }

  // A distribution histogram of forward returns — green bars for gaining outcomes, red for losing.
  function distChart(buckets) {
    if (!buckets || !buckets.length) return null;
    var max = 1; buckets.forEach(function (b) { max = Math.max(max, b.count); });
    var n = buckets.length, bw = 100 / n;
    var bars = buckets.map(function (b, i) {
      var h = b.count / max * 30;
      var mid = (b.fromPct + b.toPct) / 2;
      var col = mid >= 0 ? 'var(--risk-ok-solid,#3aa76d)' : 'var(--risk-danger-solid,#d64545)';
      var x = i * bw + bw * 0.12, w = bw * 0.76;
      return '<rect x="' + x.toFixed(2) + '" y="' + (34 - h).toFixed(2) + '" width="' + w.toFixed(2)
        + '" height="' + h.toFixed(2) + '" rx="0.6" fill="' + col + '"><title>' + b.fromPct + '% to ' + b.toPct + '%: ' + b.count + '</title></rect>';
    }).join('');
    var svg = '<svg viewBox="0 0 100 40" preserveAspectRatio="none" width="100%" height="70" role="img">'
      + '<line x1="0" y1="34" x2="100" y2="34" stroke="var(--border)" stroke-width="0.5"/>' + bars + '</svg>';
    return el('div', {}, el('div', { class: 'tool-chart', html: svg }),
      el('div', { class: 'muted small' }, 'Forward-return distribution after the signal (each bar = a return range)'));
  }

  // "Test an idea" — a real research-question workbench: pick a question, and we compare what happened
  // AFTER the signal against the stock's own normal behavior (baseline), with sample size, a win-rate
  // edge, a significance test, a bootstrap confidence interval, a distribution, and example dates.
  /**
   * TEST YOUR VIEW — one canonical market view, two explicitly different interpretations:
   * Past evidence asks what followed a named historical condition; Possible futures applies the
   * user's view as a model seed. They share symbol and horizon, but never blend probabilities.
   */
  function planAnalysisSourceControl(plan) {
    var level = Learn.currentLevel();
    var body = el('div', { class: 'plan-analysis-source-body' }, UI.spinner('Loading analysis sources…'));
    var host = el('section', { class: 'card plan-analysis-source', id: 'plan-analysis-source' },
      UI.cardHeader(level === 'beginner' ? 'History used in this Plan' : 'Analysis dataset'), body);

    function baselineLabel() {
      if (plan.marketKind === 'SIMULATED') return 'This simulated session’s generated history';
      if (plan.marketKind === 'DEMO') return 'Demo baseline · fabricated teaching history';
      return 'Observed market history';
    }

    function resetRenderedAnalysis() {
      var ui = PlanStore.ui(plan.id);
      var evidenceMode = ui.evidence && ui.evidence.mode;
      var outcomeMode = ui.outcomes && ui.outcomes.mode;
      ui.evidence = { mode: evidenceMode || 'past' };
      ui.outcomes = { mode: outcomeMode || 'market' };
      ui.outcomeRuns = {};
      delete ui.planEnsembleId;
      delete ui.planEnsembleFingerprint;
      if (window.Workspace) Workspace.save();
    }

    (async function load() {
      try {
        var response = await API.getFresh('/api/datasets');
        if (!host.isConnected) return;
        var rows = response.datasets || [];
        var active = response.active || 'observed';
        var activeRow = rows.find(function (row) { return row.id === active; });
        var matching = rows.filter(function (row) {
          return row.id === 'observed' || String(row.symbol || '').toUpperCase() === plan.symbol;
        });
        if (active !== 'observed' && !matching.some(function (row) { return row.id === active; }) && activeRow) {
          matching.push(activeRow);
        }
        var select = el('select', { id: 'plan-analysis-dataset', 'aria-label': level === 'beginner'
          ? 'History used in this Plan' : 'Analysis dataset' });
        matching.forEach(function (row) {
          var matchingSymbol = row.id === 'observed' || String(row.symbol || '').toUpperCase() === plan.symbol;
          var unavailableInWorld = plan.marketKind === 'SIMULATED' && row.id !== 'observed';
          var label = row.id === 'observed' ? baselineLabel()
            : row.name + (matchingSymbol ? '' : ' · for ' + (row.symbol || 'another symbol'));
          select.appendChild(el('option', { value: row.id, selected: row.id === active ? 'selected' : null,
            disabled: (!matchingSymbol || unavailableInWorld) ? 'disabled' : null }, label));
        });
        var detail = el('div', { class: 'plan-analysis-source-detail muted small' });
        var error = el('div', { class: 'plan-analysis-source-error', 'aria-live': 'polite' });
        function paintDetail() {
          var row = rows.find(function (item) { return item.id === select.value; });
          if (!row || row.id === 'observed') {
            detail.textContent = level === 'beginner'
              ? 'Changes charts, Past evidence and historical replays — never the market or account used to price this Plan.'
              : 'Execution market unchanged · dataset observed · current Plan context retained.';
          } else {
            detail.textContent = level === 'beginner'
              ? (row.bars || 0) + ' generated daily bars for ' + row.symbol
                + '. The Plan and its execution market do not change.'
              : row.kind + ' · ' + (row.bars || 0) + ' bars · ' + row.symbol + ' · dataset ' + row.id
                + ' · execution market unchanged.';
          }
        }
        paintDetail();
        select.addEventListener('change', async function () {
          var previous = active;
          var next = select.value;
          if (next === previous) return;
          select.disabled = true; error.innerHTML = '';
          try {
            await API.put('/api/datasets/active', { id: next });
            active = next;
            resetRenderedAnalysis();
            await App.refreshScenarioBanner();
            await App.render();
          } catch (e) {
            select.value = previous; paintDetail(); select.disabled = false;
            error.appendChild(alertBox('caution', 'Analysis source did not change', [e.message]));
          }
        });
        body.replaceChildren(el('div', { class: 'plan-analysis-source-controls' },
          el('div', { class: 'field' }, el('label', { for: 'plan-analysis-dataset' },
            level === 'beginner' ? 'Test the Plan against' : 'Dataset'), select),
          el('div', { class: 'plan-analysis-source-state' },
            el('span', { class: 'badge ' + (active === 'observed' ? 'badge-info' : 'badge-caution') },
              active === 'observed' ? (plan.marketKind === 'DEMO' ? 'DEMO HISTORY' : plan.marketKind === 'SIMULATED'
                ? 'SESSION HISTORY' : 'OBSERVED') : 'GENERATED SCENARIO'), detail,
            el('a', { href: '#/data/datasets', class: 'muted small' }, 'Manage saved datasets'))), error);
      } catch (e) {
        if (!host.isConnected) return;
        body.replaceChildren(alertBox('warn', 'Analysis sources are unavailable', [e.message]));
      }
    })();
    return host;
  }

  function testYourViewSection(symbol, plan) {
    if (!plan) throw new Error('Evidence must be owned by a Plan.');
    var level = Learn.currentLevel();
    var assumptionsEditable = plan.assumptionsEditable === true;
    var historyBasis = activeHistoryBasis();
    var planUi = PlanStore.ui(plan.id);
    var th = planUi.thesis = planUi.thesis || {};
    // Persisted-workspace migration only: the old field called a historical condition a
    // "direction", even though it was not the user's market direction.
    th.setup = th.setup || THESIS_QUESTION[th.direction] || 'pullback_rebound';
    delete th.direction;
    // Durable Plan context is authoritative, including explicit absence. A lane-level
    // or prior-render value must never invent a thesis/horizon for this Plan.
    th.horizonDays = plan.context.horizonDays || Product.Horizon.sessions('month');
    th.thesis = plan.context.thesis || '';
    var wrap = el('div', { class: 'card', id: 'test-your-view' });
    wrap.appendChild(UI.cardHeader('Test your view \u2014 ' + symbol));
    wrap.appendChild(explain(level === 'beginner'
      ? 'State your view and horizon once. Past evidence asks what followed a named setup in ' + historyBasis.plain + '; possible futures turns your view into explicit model scenarios. They inform each other but never become one blended probability.'
      : 'One Plan context seeds two labeled interpretations: a ' + historyBasis.expert + ' (conditional history, bootstrap CI) and a parametric Monte Carlo fan. The analog windows remain reusable as a distinct path source in this Plan’s Outcomes stage.'));

    var viewSel = el('select', { id: 'tv-view' },
      el('option', { value: '', disabled: 'disabled' }, level === 'beginner' ? 'Choose your market view' : 'Select thesis'),
      el('option', { value: 'bullish' }, level === 'beginner' ? 'I expect it to rise' : 'Bullish'),
      el('option', { value: 'bearish' }, level === 'beginner' ? 'I expect it to fall' : 'Bearish'),
      el('option', { value: 'neutral' }, level === 'beginner' ? 'I expect it to stay in a range' : 'Neutral / range'),
      el('option', { value: 'volatile' }, level === 'beginner' ? 'I expect a large move either way' : 'Volatile / direction unknown'));
    viewSel.value = th.thesis;
    var setupSel = el('select', { id: 'tv-setup' },
      el('option', { value: 'pullback_rebound' }, level === 'beginner' ? 'After a pullback, what followed?' : 'Pullback: rebound or continue lower?'),
      el('option', { value: 'oversold_bounce' }, level === 'beginner' ? 'After a sharp down day, what followed?' : 'Sharp down day: bounce or follow-through?'),
      el('option', { value: 'breakout_followthrough' }, level === 'beginner' ? 'After a new high, what followed?' : 'Breakout: continue or fade?'),
      el('option', { value: 'momentum' }, level === 'beginner' ? 'After strong momentum, what followed?' : 'Momentum: persist or reverse?'),
      el('option', { value: 'up_streak' }, level === 'beginner' ? 'After an up streak, what followed?' : 'Up streak: continue or reverse?'));
    setupSel.value = th.setup;
    var horIn = el('input', { type: 'number', id: 'tv-horizon', value: String(th.horizonDays), min: '1', max: '63' });
    if (!assumptionsEditable) {
      viewSel.disabled = true;
      setupSel.disabled = true;
      horIn.disabled = true;
    }
    var thesisRow = el('div', { class: 'form-grid' },
      el('div', { class: 'field' }, el('label', { class: 'field-label', for: 'tv-view' }, 'Your market view'), viewSel),
      el('div', { class: 'field' }, el('label', { class: 'field-label', for: 'tv-setup' }, 'Historical condition to examine'), setupSel),
      el('div', { class: 'field' }, el('label', { class: 'field-label', for: 'tv-horizon' }, 'Over how many trading days?'), horIn));
    wrap.appendChild(thesisRow);

    if (!assumptionsEditable) {
      wrap.appendChild(el('div', { class: 'alert alert-info plan-frozen-context' },
        el('div', {}, el('b', {}, 'Decision inputs are frozen'),
          el('p', {}, 'This view, historical condition and horizon belong to the recorded decision. Create a linked revision to test different assumptions without rewriting what happened.')),
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
          var edit = document.getElementById('plan-edit-context');
          if (edit) edit.click();
        } }, 'Revise this Plan')));
    }

    var outcomeWorkspace = null;
    if (assumptionsEditable) viewSel.addEventListener('change', async function () {
      th.thesis = viewSel.value;
      try {
        plan = await PlanStore.updateContext(plan, { thesis: th.thesis });
        App.render();
      } catch (e) { UI.toast(e.message, 'error'); App.render(); }
    });
    if (assumptionsEditable) setupSel.addEventListener('change', function () {
      th.setup = setupSel.value;
      if (outcomeWorkspace) outcomeWorkspace.refresh();
    });
    if (assumptionsEditable) horIn.addEventListener('change', async function () {
      th.horizonDays = Math.max(1, Math.min(63, parseInt(horIn.value, 10) || 10));
      try {
        plan = await PlanStore.updateContext(plan, { horizonDays: th.horizonDays });
        App.render();
      } catch (e) { UI.toast(e.message, 'error'); App.render(); }
    });
    if (!th.thesis) {
      wrap.appendChild(UI.emptyState('Choose the view you want to test',
        'Past evidence and possible futures stay unavailable until this Plan records a direction or range view.'));
      return wrap;
    }

    // ---- Two connected evidence bases through the shared Outcomes component ----
    if (planUi && planUi.contextRev !== plan.context.rev) {
      var carriedMode = planUi.evidence && planUi.evidence.mode;
      planUi.contextRev = plan.context.rev;
      planUi.evidence = carriedMode ? { mode: carriedMode } : {};
    }
    var tvState = planUi.evidence = planUi.evidence || {};
    tvState.mode = tvState.mode || 'past';
    outcomeWorkspace = window.ViewPlan.outcomeWorkspace({
      id: 'research-outcomes', state: tvState, label: symbol + ' evidence basis',
      onChange: function () { if (window.Workspace) Workspace.save(); },
      modes: [
        { key: 'past', label: 'Past evidence',
          description: 'What followed similar conditions?',
          note: 'Conditional history: independent signal episodes compared with the stock\u2019s normal behavior.',
          render: function (host) { host.appendChild(evidenceStage(symbol, th, level, tvState, plan)); } },
        { key: 'futures', label: 'Possible futures',
          description: 'What could happen from here?',
          note: 'Parametric scenarios: generated paths from an explicit model, never a forecast.',
          render: function (host) { host.appendChild(futuresStage(symbol, th, level, tvState, plan)); } }
      ]
    });
    wrap.appendChild(outcomeWorkspace.el);

    return wrap;
  }

  /** Persisted-workspace migration for the old condition labels. */
  var THESIS_QUESTION = { rebound: 'pullback_rebound', bounce: 'oversold_bounce',
    breakout: 'breakout_followthrough', momentum: 'momentum', streak: 'up_streak' };

  /** The study's identity: results may only render when the visible controls match this key. */
  function studyClientKey(symbol, qKey, params, from, to) {
    var ordered = {};
    Object.keys(params || {}).sort().forEach(function (k) { ordered[k] = params[k]; });
    var dataset = App.config && App.config.activeDataset || 'observed';
    return dataset + '|' + symbol + '|' + qKey + '|' + (from || '') + '..' + (to || '') + '|' + JSON.stringify(ordered);
  }

  /** PAST EVIDENCE: the event study, inheriting page symbol and shared horizon. */
  function evidenceStage(symbol, thesis, level, ownedState, plan) {
    if (!ownedState || !plan) throw new Error('Historical evidence must be owned by a Plan.');
    var f = ownedState;
    f.results = f.results || {};
    f.protocolBySymbol = f.protocolBySymbol || {};
    var protocol = f.protocolBySymbol[symbol] = Object.assign({
      window: '3y', from: '', to: '', strictness: 'balanced', regime: 'ALL',
      eventSpacing: thesis.horizonDays, minSample: 15, confidencePct: 95,
      bootstrapSamples: 800, multiplicity: 'CATALOG_BONFERRONI', splitHalf: true
    }, f.protocolBySymbol[symbol] || {});
    var stage = el('div', { id: 'what-has-happened' });
    var qKey = thesis.setup || 'pullback_rebound';
    var picker = el('div', { id: 'study-question-picker' });
    stage.appendChild(picker);
    var out = el('div', { id: 'study-results', class: 'tool-output' });
    var run = el('button', { class: 'btn', id: 'study-run', disabled: 'disabled' }, 'Check the past');
    stage.appendChild(el('div', { class: 'btn-row' }, run));
    stage.appendChild(out);

    var paramInputs = {}, protocolInputs = {};
    function isoYearsAgo(years) {
      var d = new Date(); d.setUTCFullYear(d.getUTCFullYear() - years);
      return d.toISOString().slice(0, 10);
    }
    function today() { return new Date().toISOString().slice(0, 10); }
    function windowFrom(value) { return value === '1y' ? isoYearsAgo(1) : value === 'max' ? '2000-01-01' : isoYearsAgo(3); }
    if (!protocol.from) protocol.from = windowFrom(protocol.window);
    if (!protocol.to) protocol.to = today();

    function currentParams(q) {
      var params = {};
      (q.params || []).forEach(function (pr) {
        params[pr.key] = paramInputs[pr.key] ? +paramInputs[pr.key].value
          : (f.params && f.params[q.key] && f.params[q.key][pr.key]) != null
            ? f.params[q.key][pr.key] : pr.def;
      });
      params.forward = thesis.horizonDays; // the THESIS horizon is the study horizon — one truth
      params.eventSpacing = +(protocolInputs.eventSpacing ? protocolInputs.eventSpacing.value : protocol.eventSpacing || thesis.horizonDays);
      params.minSample = +(protocolInputs.minSample ? protocolInputs.minSample.value : protocol.minSample);
      params.confidencePct = +(protocolInputs.confidencePct ? protocolInputs.confidencePct.value : protocol.confidencePct);
      params.bootstrapSamples = +(protocolInputs.bootstrapSamples ? protocolInputs.bootstrapSamples.value : protocol.bootstrapSamples);
      params.regime = protocolInputs.regime ? protocolInputs.regime.value : protocol.regime;
      params.multiplicity = protocolInputs.multiplicity ? protocolInputs.multiplicity.value : protocol.multiplicity;
      params.splitHalf = protocolInputs.splitHalf ? protocolInputs.splitHalf.checked : protocol.splitHalf;
      return params;
    }

    function rememberProtocol() {
      if (protocolInputs.window) protocol.window = protocolInputs.window.value;
      if (protocolInputs.from) protocol.from = protocolInputs.from.value;
      if (protocolInputs.to) protocol.to = protocolInputs.to.value;
      if (protocolInputs.strictness) protocol.strictness = protocolInputs.strictness.value;
      if (protocolInputs.regime) protocol.regime = protocolInputs.regime.value;
      if (protocolInputs.eventSpacing) protocol.eventSpacing = +protocolInputs.eventSpacing.value;
      if (protocolInputs.minSample) protocol.minSample = +protocolInputs.minSample.value;
      if (protocolInputs.confidencePct) protocol.confidencePct = +protocolInputs.confidencePct.value;
      if (protocolInputs.bootstrapSamples) protocol.bootstrapSamples = +protocolInputs.bootstrapSamples.value;
      if (protocolInputs.multiplicity) protocol.multiplicity = protocolInputs.multiplicity.value;
      if (protocolInputs.splitHalf) protocol.splitHalf = protocolInputs.splitHalf.checked;
    }

    function invalidateVisible() {
      rememberProtocol();
      if (out.children.length) {
        out.innerHTML = '';
        out.appendChild(el('p', { class: 'muted study-rerun-note' }, 'Study settings changed — run the study again to refresh the evidence.'));
      }
    }

    function strictValue(qKey0, param, mode) {
      if (mode === 'balanced') return param.def;
      var loose = mode === 'more';
      if (param.key === 'dropPct') return qKey0 === 'pullback_rebound'
        ? (loose ? 3 : 8) : (loose ? 2 : 5);
      if (param.key === 'thresholdPct') return loose ? 3 : 10;
      if (param.key === 'streak') return loose ? 2 : 5;
      if (param.key === 'lookback' && qKey0 === 'breakout_followthrough') return loose ? 10 : 60;
      return param.def;
    }

    function buildPicker(cat) {
      picker.innerHTML = '';
      paramInputs = {}; protocolInputs = {};
      var q = cat.find(function (x) { return x.key === qKey; }) || cat[0];
      picker.appendChild(el('div', { class: 'study-question-line', id: 'tv-question-line' },
        el('b', {}, q.title), el('span', { class: 'muted' }, q.plain + ' · ' + thesis.horizonDays + '-trading-day outcome')));

      var signalFields = [];
      (q.params || []).forEach(function (pr) {
        if (pr.key === 'forward') return;
        var saved = (f.params && f.params[q.key] && f.params[q.key][pr.key]);
        var inp = el('input', { type: 'number', id: 'study-param-' + pr.key,
          value: saved == null ? strictValue(q.key, pr, protocol.strictness) : saved,
          min: String(pr.min), max: String(pr.max), step: '1' });
        paramInputs[pr.key] = inp;
        signalFields.push(UI.field((level === 'beginner' ? pr.label : pr.label + ' (' + pr.unit + ')'), inp));
      });

      protocolInputs.window = el('select', { id: 'study-window' },
        el('option', { value: '1y' }, 'Past year'), el('option', { value: '3y' }, 'Past 3 years'),
        el('option', { value: 'max' }, 'All available history'),
        el('option', { value: 'custom' }, 'Custom dates'));
      protocolInputs.window.value = protocol.window;
      protocolInputs.strictness = el('select', { id: 'study-strictness' },
        el('option', { value: 'more' }, level === 'beginner' ? 'More examples (looser signal)' : 'Loose signal / more events'),
        el('option', { value: 'balanced' }, 'Balanced signal'),
        el('option', { value: 'stronger' }, level === 'beginner' ? 'Fewer, stronger examples' : 'Strict signal / fewer events'),
        el('option', { value: 'custom' }, 'Custom thresholds'));
      protocolInputs.strictness.value = protocol.strictness;
      protocolInputs.regime = el('select', { id: 'study-regime' },
        el('option', { value: 'ALL' }, 'All market conditions'),
        el('option', { value: 'ABOVE_200DMA' }, 'Above 200-day average'),
        el('option', { value: 'BELOW_200DMA' }, 'Below 200-day average'),
        el('option', { value: 'HIGH_VOL' }, 'Higher-volatility regimes'),
        el('option', { value: 'LOW_VOL' }, 'Lower-volatility regimes'));
      protocolInputs.regime.value = protocol.regime;

      var beginnerControls = el('div', { class: 'form-grid study-beginner-controls' },
        UI.field('History to check', protocolInputs.window),
        UI.field('How selective?', protocolInputs.strictness),
        UI.field('Market conditions', protocolInputs.regime));
      picker.appendChild(beginnerControls);

      protocolInputs.from = el('input', { type: 'date', id: 'study-from', value: protocol.from });
      protocolInputs.to = el('input', { type: 'date', id: 'study-to', value: protocol.to, max: today() });
      protocolInputs.eventSpacing = el('input', { type: 'number', id: 'study-spacing', min: '1',
        max: String(thesis.horizonDays), value: String(Math.min(thesis.horizonDays, protocol.eventSpacing || thesis.horizonDays)) });
      protocolInputs.minSample = el('input', { type: 'number', id: 'study-min-sample', min: '5', max: '100', value: String(protocol.minSample) });
      protocolInputs.confidencePct = el('select', { id: 'study-confidence' },
        el('option', { value: '90' }, '90%'), el('option', { value: '95' }, '95%'), el('option', { value: '99' }, '99%'));
      protocolInputs.confidencePct.value = String(protocol.confidencePct);
      protocolInputs.bootstrapSamples = el('input', { type: 'number', id: 'study-bootstrap', min: '200', max: '10000', step: '100', value: String(protocol.bootstrapSamples) });
      protocolInputs.multiplicity = el('select', { id: 'study-multiplicity' },
        el('option', { value: 'CATALOG_BONFERRONI' }, level === 'beginner' ? 'Protect against lucky findings' : 'Bonferroni across question catalog'),
        el('option', { value: 'UNADJUSTED_EXPLORATORY' }, level === 'beginner' ? 'Exploratory (more false alarms)' : 'Unadjusted exploratory'));
      protocolInputs.multiplicity.value = protocol.multiplicity;
      protocolInputs.splitHalf = el('input', { type: 'checkbox', id: 'study-split-half', checked: protocol.splitHalf ? '' : null });

      var exactFields = el('div', { class: 'form-grid study-protocol-grid' }, signalFields,
        UI.field(level === 'beginner' ? 'Start date' : 'Sample start', protocolInputs.from),
        UI.field(level === 'beginner' ? 'End date' : 'Sample end', protocolInputs.to),
        UI.field(level === 'beginner' ? 'Days between examples' : 'Minimum event separation (days)', protocolInputs.eventSpacing),
        UI.field(level === 'beginner' ? 'Minimum examples' : 'Minimum event sample', protocolInputs.minSample),
        UI.field('Confidence', protocolInputs.confidencePct),
        UI.field(level === 'beginner' ? 'Resamples' : 'Bootstrap draws', protocolInputs.bootstrapSamples),
        UI.field(level === 'beginner' ? 'Lucky-finding protection' : 'Multiple-testing policy', protocolInputs.multiplicity),
        el('div', { class: 'field inline-check study-split-check' }, protocolInputs.splitHalf,
          el('label', { for: 'study-split-half' }, level === 'beginner' ? 'Check both halves of history' : 'Split-half consistency check')));
      var protocolDetails = el('details', { class: 'study-protocol', open: level === 'expert' ? '' : null },
        el('summary', {}, level === 'beginner' ? 'Fine-tune the evidence check' : 'Study protocol · full controls'),
        el('div', { class: 'study-protocol-body' }, exactFields,
          el('p', { class: 'muted small' }, level === 'beginner'
            ? 'The default keeps examples independent, compares them with non-signal days, resamples the events, and protects against finding a pattern by luck.'
            : 'Signal detection uses no future data. The baseline is the disjoint non-signal complement. Event overlap reduces effective sample size; moving-block bootstrap, an overlap-adjusted two-sided z screen, pooled Cohen’s d, split-half consistency and catalog-wide multiplicity are reported separately.')));
      picker.appendChild(protocolDetails);
      picker.appendChild(el('p', { class: 'muted small study-description' }, q.description));

      protocolInputs.window.addEventListener('change', function () {
        if (protocolInputs.window.value !== 'custom') protocolInputs.from.value = windowFrom(protocolInputs.window.value);
        invalidateVisible();
      });
      protocolInputs.strictness.addEventListener('change', function () {
        if (protocolInputs.strictness.value === 'custom') { invalidateVisible(); return; }
        (q.params || []).forEach(function (pr) {
          if (pr.key !== 'forward' && paramInputs[pr.key]) paramInputs[pr.key].value = strictValue(q.key, pr, protocolInputs.strictness.value);
        });
        invalidateVisible();
      });
      [protocolInputs.regime, protocolInputs.to, protocolInputs.eventSpacing,
        protocolInputs.minSample, protocolInputs.confidencePct, protocolInputs.bootstrapSamples,
        protocolInputs.multiplicity, protocolInputs.splitHalf].forEach(function (node) {
          node.addEventListener('change', invalidateVisible);
        });
      protocolInputs.from.addEventListener('change', function () {
        protocolInputs.window.value = 'custom'; invalidateVisible();
      });
      Object.keys(paramInputs).forEach(function (key) {
        paramInputs[key].addEventListener('change', function () {
          protocolInputs.strictness.value = 'custom'; invalidateVisible();
        });
      });
      run.disabled = false;
      // KEYED RESTORE: show a stored result ONLY when its key matches the visible controls —
      // an AAPL result may never flash on a QQQ page (adversarial review P1).
      rememberProtocol();
      var key = studyClientKey(symbol, q.key, currentParams(q), protocol.from, protocol.to);
      out.innerHTML = '';
      if (f.results[key]) renderQuestion(out, f.results[key], level, symbol, thesis, plan, f);
    }

    run.addEventListener('click', async function () {
      var cat = f.questions || [];
      var q = cat.find(function (x) { return x.key === qKey; }) || cat[0];
      if (!q) return;
      var params = currentParams(q);
      f.params = f.params || {}; f.params[q.key] = params;
      rememberProtocol();
      run.disabled = true; out.innerHTML = ''; out.appendChild(UI.spinner('Replaying ' + symbol + '\u2019s history\u2026'));
      var body = { key: q.key, symbol: symbol, from: protocol.from, to: protocol.to, params: params };
      try {
        var saved = await API.post(plan ? '/api/plans/' + plan.id + '/evidence/study'
          : '/api/research/event-studies', body);
        var _d = plan ? saved.result : saved;
        var key = _d.studyKey || studyClientKey(symbol, q.key, params, protocol.from, protocol.to);
        f.results[key] = _d;
        // Keep a deterministic local lookup key alongside the server identity so changing a
        // visible control can never restore a result from a different study configuration.
        f.results[studyClientKey(symbol, q.key, params, protocol.from, protocol.to)] = _d;
        var keys = Object.keys(f.results);
        if (keys.length > 16) delete f.results[keys[0]]; // bounded memory
        renderQuestion(out, _d, level, symbol, thesis, plan, f);
      }
      catch (e) { out.innerHTML = ''; out.appendChild(alertBox('danger', 'Study failed', [String((e && e.message) || e)])); }
      finally { run.disabled = false; }
    });

    (async function loadCatalog() {
      var cat = f.questions;
      if (!cat) {
        try { cat = (await API.get('/api/research/questions')).questions || []; f.questions = cat; }
        catch (e) { picker.appendChild(alertBox('warn', 'Could not load the question catalog.')); return; }
      }
      if (!cat.length) { picker.appendChild(alertBox('warn', 'No questions available.')); return; }
      if (plan && !f.restoredCurrentEvidence) {
        f.restoredCurrentEvidence = true;
        try {
          var savedDoc = await API.getFresh('/api/plans/' + plan.id + '/evidence/latest');
          var savedStudy = savedDoc && savedDoc.evidence;
          if (savedStudy && savedStudy.result && savedStudy.request) {
            var sr = savedStudy.request;
            var sp = sr.params || {};
            thesis.setup = sr.key || thesis.setup;
            qKey = thesis.setup;
            var setupControl = document.getElementById('tv-setup');
            if (setupControl) setupControl.value = qKey;
            f.params = f.params || {};
            f.params[qKey] = sp;
            protocol.from = sr.from || protocol.from;
            protocol.to = sr.to || protocol.to;
            ['eventSpacing','minSample','confidencePct','bootstrapSamples','regime','multiplicity'].forEach(function (key) {
              if (sp[key] !== undefined && sp[key] !== null) protocol[key] = sp[key];
            });
            if (sp.splitHalf !== undefined) protocol.splitHalf = !!sp.splitHalf;
            var restoreKey = studyClientKey(symbol, qKey, sp, protocol.from, protocol.to);
            f.results[restoreKey] = savedStudy.result;
            if (savedStudy.result.studyKey) f.results[savedStudy.result.studyKey] = savedStudy.result;
          }
        } catch (e) { /* no saved result is a normal first-run state */ }
      }
      buildPicker(cat);
    })();
    return stage;
  }

  /** POSSIBLE FUTURES: the Monte-Carlo fan, inheriting symbol + thesis horizon. */
  function futuresStage(symbol, thesis, level, ownedState, plan) {
    var host = el('div', { id: 'tv-futures' });
    ownedState = ownedState || {};
    var inner = whatIfCard(symbol, { thesis: thesis.thesis, horizonDays: thesis.horizonDays,
      state: ownedState, plan: plan });
    // Composed stage: the card chrome is the section's — strip the duplicate card skin.
    inner.classList.remove('card');
    host.appendChild(inner);
    return host;
  }

  /**
   * The assembled pre-trade judgment (CP-5): one conclusion-first banner + the probability map +
   * execution cost + the plan — same truth at both levels, beginner gets sentences, expert density.
   * Returns {node, requiredAcks: [{id, label}]} so the caller can gate the Continue button.
   */
  function verdictPanel(p, beginner, hasEconomicClassification) {
    var a = p.analytics || {};
    var prob = a.probabilityMap || {};
    var exec = a.executionQuality || {};
    var plan = a.managementPlan || {};
    var wrap = el('div', { id: 'verdict-panel' });
    var kind = a.verdict === 'favorable' ? 'ok' : a.verdict === 'unfavorable' ? 'danger' : 'caution';
    if (a.verdict && !hasEconomicClassification) {
      wrap.appendChild(alertBox(kind, (a.verdict === 'favorable' ? 'Looks reasonable'
        : a.verdict === 'unfavorable' ? 'The odds are against this trade' : 'Mixed picture'),
        [a.verdictReason || '']));
    }
    if (prob.pAnyProfit !== undefined) {
      wrap.appendChild(el('div', { class: 'grid grid-4', id: 'prob-map' },
        stat(el('span', {}, beginner ? 'Chance of making anything' : 'P(any profit)', UI.info('pop')), fmtPct(prob.pAnyProfit)),
        stat(el('span', {}, beginner ? 'Chance of the FULL theoretical win' : 'P(theoretical max profit)', UI.info('pmaxprofit')), fmtPct(prob.pMaxProfit)),
        stat(el('span', {}, beginner ? 'Chance of the theoretical WORST case' : 'P(theoretical max loss)', UI.info('pmaxloss')),
          el('span', { class: prob.pMaxLoss > 0.4 ? 'loss' : '' }, fmtPct(prob.pMaxLoss))),
        stat(el('span', {}, beginner ? 'A very bad run costs' : 'CVaR 95%', UI.info('cvar')),
          el('span', { class: 'loss' }, fmtMoney(prob.cvar95Cents)))));
      wrap.appendChild(el('div', { class: 'muted small' },
        (prob.basis || '') + (prob.timeBasis ? ' \u00b7 time: ' + prob.timeBasis : '')));
    }
    if (a.rate && a.rate.annual !== undefined && a.rate.annual !== null) {
      var rateEvidence = a.rate.evidence || {};
      var modeledRate = rateEvidence.provenance === 'MODELED' || rateEvidence.provenance === 'MISSING';
      wrap.appendChild(el('div', { class: 'model-input-line muted small', id: 'rate-input' },
        el('span', {}, beginner ? 'Pricing assumes a ' : 'Risk-free rate input '),
        el('b', {}, (Number(a.rate.annual) * 100).toFixed(beginner ? 1 : 3) + '% annual'),
        ' ', UI.evidenceBadge(rateEvidence), UI.info('riskfreerate'),
        modeledRate ? el('span', {}, ' Default assumption; no eligible rate source answered.') : null));
    }
    if (exec.midNetCents !== undefined && exec.midNetCents !== null) {
      var ladder = el('div', { class: 'chip-row', id: 'exec-ladder' },
        chip('Midpoint', fmtMoney(exec.midNetCents, { plus: true }), 'The package priced at every leg\u2019s midpoint — rarely fully fillable, but the honest reference.'),
        chip('Executable now', fmtMoney(exec.executableNetCents, { plus: true }), 'Crossing every book: buys at the ask, sells at the bid.'),
        exec.proposedNetCents !== undefined ? chip('Your price', fmtMoney(exec.proposedNetCents, { plus: true })) : null,
        exec.concessionVsMidCents !== undefined ? chip(
          el('span', {}, beginner ? 'Cost of entering' : 'Concession vs mid', UI.info('concession')),
          el('span', { class: 'loss' }, fmtMoney(exec.concessionVsMidCents)
            + (exec.concessionPctOfMid !== undefined ? ' (' + Math.round(Math.abs(exec.concessionPctOfMid) * 100) + '% of mid)' : ''))) : null,
        exec.exitSpreadEstimateCents !== undefined ? chip('Exit will cost ~', fmtMoney(exec.exitSpreadEstimateCents),
          'Half the package spread again, on the way out.') : null);
      wrap.appendChild(el('div', { class: 'card card-slim', style: 'margin:8px 0' },
        el('h3', { class: 'mt0' }, beginner ? 'What entering actually costs' : 'Execution quality'), ladder));
    }
    if (plan.rules && plan.rules.length) {
      var planBody = el('ul', { class: 'plan-rules' }, plan.rules.map(function (r) { return el('li', {}, r); }));
      wrap.appendChild(beginner
        ? el('div', { class: 'card card-slim', style: 'margin:8px 0' },
            el('h3', { class: 'mt0' }, 'Your plan for this trade (' + (plan.regime || '') + ')'), planBody)
        : UI.expandable('Management plan \u00b7 ' + (plan.regime || ''), planBody, false));
    }
    if (a.evaluatedAtEpochMs) {
      var evalMs = a.evaluatedAtEpochMs;
      var ageMin = Math.max(0, Math.round((Date.now() - evalMs) / 60000));
      var srcTxt = '';
      if (a.sourceAsOfEpochMs) {
        var srcAge = Math.max(0, Math.round((Date.now() - a.sourceAsOfEpochMs) / 60000));
        srcTxt = 'Data stamped ' + (srcAge <= 1 ? 'just now' : srcAge + ' min ago') + ' \u00b7 ';
      }
      wrap.appendChild(el('div', { class: 'muted small', id: 'quote-age' },
        srcTxt + 'evaluated ' + (ageMin <= 1 ? 'just now' : ageMin + ' min ago')
        + ' on ' + (a.freshness || p.freshness) + ' quotes.'));
    }
    // Material-risk acknowledgments: the trade stays PLACEABLE, but never silently.
    var acks = [];
    var marketEvAfterCosts = p.expectedValueCents !== null && p.expectedValueCents !== undefined
      ? p.expectedValueCents - 2 * (p.feesOpenCents || 0) : null;
    if (marketEvAfterCosts !== null && marketEvAfterCosts < 0) {
      acks.push({ id: 'ack-ev', label: 'I understand the model expects this trade to LOSE '
        + fmtMoney(-marketEvAfterCosts) + ' on average at the market\u2019s own volatility.' });
    }
    if (exec.concessionPctOfMid !== undefined && Math.abs(exec.concessionPctOfMid) > 0.10) {
      acks.push({ id: 'ack-exec', label: 'I understand entering surrenders '
        + Math.round(Math.abs(exec.concessionPctOfMid) * 100) + '% of the package midpoint to the bid/ask spread.' });
    }
    if ((plan.regime || '').indexOf('near-expiry') >= 0) {
      acks.push({ id: 'ack-dte', label: 'I understand only ' + plan.sessions + ' trading session'
        + (plan.sessions === 1 ? '' : 's') + ' remain\u2014gamma, weekend gaps and pin risk dominate.' });
    }
    return { node: wrap, requiredAcks: acks };
  }

  function renderQuestion(out, r, level, symbol, thesis, plan, ownedState) {
    out.innerHTML = '';
    var protocol = r.protocol || { minSample: 15, confidencePct: 95, bootstrapSamples: 800,
      eventSpacingDays: r.forwardDays, effectiveEventBlock: 1, regime: 'ALL',
      multiplicity: 'CATALOG_BONFERRONI', splitHalfCheck: true,
      baseline: 'NON_SIGNAL_COMPLEMENT', criticalZ: 2.576 };
    var few = r.conditioned.sample < protocol.minSample;
    var kind = few ? 'warn' : (r.significant ? (r.winRateEdgePct > 0 ? 'ok' : 'danger') : 'caution');
    out.appendChild(alertBox(kind, r.verdict));
    out.appendChild(el('p', { class: 'muted small' }, r.question));
    // EVIDENCE STRENGTH in one word (beginner-first), never z-score-first: sample size,
    // overlap-adjusted z screen, effect size and split-half consistency roll into weak/moderate/strong.
    var strength = few ? 'weak'
      : (r.significant && Math.abs(r.effectSize || 0) >= 0.2 && r.holdout === 'held') ? 'strong'
      : r.significant ? 'moderate' : 'weak';
    out.appendChild(el('div', { class: 'chip-row', style: 'margin-top:2px' },
      UI.evidenceBadge(r.evidence),
      chip('Evidence', strength,
        'Rolls up sample size, the overlap-adjusted statistical screen, effect size and split-half consistency. Even strong evidence describes the PAST — use it to raise or lower your confidence, never as a prediction.'),
      chip('Signal episodes', String(r.conditioned.sample),
        'Signals are separated by the protocol spacing. If outcome windows still overlap, the effective sample and bootstrap use an explicit overlap approximation.')));
    out.appendChild(el('div', { class: 'muted small' },
      'Use this to raise or lower your confidence in the view \u2014 it does not predict the next occurrence.'));
    var regimeText = protocol.regime === 'ABOVE_200DMA' ? 'above the 200-day average'
      : protocol.regime === 'BELOW_200DMA' ? 'below the 200-day average'
      : protocol.regime === 'HIGH_VOL' ? 'higher-volatility regimes'
      : protocol.regime === 'LOW_VOL' ? 'lower-volatility regimes' : 'all market conditions';
    out.appendChild(el('details', { class: 'study-result-protocol' },
      el('summary', {}, level === 'beginner' ? 'How this evidence was checked' : 'Protocol and assumptions'),
      el('div', { class: 'study-result-protocol-body' },
        el('div', { class: 'chip-row' },
          chip('Window', r.from + ' \u2192 ' + r.to),
          chip('Regime', regimeText),
          chip('Event spacing', protocol.eventSpacingDays + ' days'),
          chip('Minimum sample', String(protocol.minSample)),
          chip('Confidence', protocol.confidencePct + '%'),
          chip('Bootstrap', protocol.bootstrapSamples + ' draws'),
          chip('Multiple tests', protocol.multiplicity === 'CATALOG_BONFERRONI' ? 'catalog-adjusted' : 'unadjusted exploratory'),
          chip('Split-half', protocol.splitHalfCheck ? 'on' : 'off')),
        el('p', { class: 'muted small' }, level === 'beginner'
          ? 'Each signal uses only information known on that date. Similar dates are separated before counting; ordinary non-signal dates form the comparison group. Resampling estimates uncertainty.'
          : 'No-look-ahead signal; disjoint non-signal baseline; effective event block ' + protocol.effectiveEventBlock
            + '; overlap-adjusted two-sided z threshold |z| ' + protocol.criticalZ + '; moving-block bootstrap; pooled Cohen\u2019s d; optional split-half consistency.'))));
    // Conditioned win rate vs the baseline (the baseline bar marker) — the honest comparison.
    out.appendChild(gaugeChart(r.conditioned.winRatePct / 100, r.baseline.winRatePct / 100,
      'Positive ' + Math.round(r.conditioned.winRatePct) + '% of the time after the signal vs ' + Math.round(r.baseline.winRatePct) + '% normally — over ' + r.conditioned.sample + ' signals'));
    var facts = el('div', { class: 'fact-grid' },
      UI.fact('After the signal', Math.round(r.conditioned.winRatePct) + '% up', r.winRateEdgePct >= 0 ? 'f-ok' : 'f-danger'),
      UI.fact('Normally', Math.round(r.baseline.winRatePct) + '% up'),
      UI.fact('Edge', (r.winRateEdgePct >= 0 ? '+' : '') + r.winRateEdgePct + ' pts', r.winRateEdgePct >= 0 ? 'f-ok' : 'f-danger'));
    out.appendChild(facts);
    var mean = el('div', { class: 'fact-grid' },
      UI.fact('Avg return', (r.conditioned.meanReturnPct >= 0 ? '+' : '') + r.conditioned.meanReturnPct + '%', r.meanEdgePct >= 0 ? 'f-ok' : 'f-danger'),
      UI.fact('Worst case', r.conditioned.worstPct + '%', 'f-danger'),
      UI.fact('Best case', '+' + r.conditioned.bestPct + '%', 'f-ok'));
    out.appendChild(mean);
    var dc = distChart(r.distribution);
    if (dc) out.appendChild(dc);
    if (r.holdout) {
      out.appendChild(el('div', { class: 'muted small' },
        r.holdout === 'held' ? 'Split-half consistency: the edge pointed the same way in both halves of the window.'
          : 'Split-half consistency: the edge lived in only ONE half of the window \u2014 possibly one regime\u2019s story.'));
    }
    if (level === 'expert') {
      out.appendChild(el('div', { class: 'chip-row' },
        chip('Overlap z screen', String(r.zScore),
          'A conservative screening approximation: overlapping outcome windows reduce the effective sample. It is not an independent-observation test.'),
        chip(protocol.confidencePct + '% CI (avg)', r.ciLowPct + '% … ' + r.ciHighPct + '%'),
        r.effectSize !== null && r.effectSize !== undefined
          ? chip('Effect size', String(r.effectSize), 'Pooled Cohen\u2019s d: the edge measured in units of the two samples\u2019 shared noise. Under ~0.2 is negligible even when the z screen clears.') : null,
        r.holdout ? chip('Consistency', r.holdout === 'held' ? 'held' : 'faded',
          'Split-half check on the SAME window \u2014 in-sample consistency, not genuine out-of-sample validation.') : null,
        chip('Baseline avg', (r.baseline.meanReturnPct >= 0 ? '+' : '') + r.baseline.meanReturnPct + '%'),
        chip('Clears z screen', r.significant ? 'yes' : 'no',
          protocol.multiplicity === 'CATALOG_BONFERRONI'
            ? 'Uses the catalog-adjusted critical z shown in the protocol.'
            : 'Exploratory and unadjusted; repeated searching raises false-positive risk.')));
    }
    if (r.exampleDates && r.exampleDates.length) {
      out.appendChild(el('div', { class: 'muted small', style: 'margin-top:4px' }, 'Example signal dates: ' + r.exampleDates.join(', ')));
    }
    (r.notes || []).forEach(function (n) { out.appendChild(el('div', { class: 'muted small' }, n)); });
    // The exact analog windows become a Plan-owned strategy path source.
    if (symbol && r.analogPaths && r.analogPaths.length >= 5) {
      out.appendChild(el('div', { class: 'btn-row', style: 'margin-top:8px' },
        el('button', { class: 'btn', id: 'tv-test-analogs', onclick: async function () {
          var selection = {
            symbol: symbol,
            source: 'HISTORICAL_ANALOGS',
            study: { key: r.key, from: r.from, to: r.to,
              params: (ownedState.params || {})[r.key] || {} },
            studyKey: r.studyKey,
            events: r.conditioned.sample,
            horizonDays: r.forwardDays,
            label: r.conditioned.sample + ' ' + occurrenceWord(r) + ' (' + r.from + ' to ' + r.to + ')'
          };
          ownedState.analogSelection = selection;
          try {
            var moved = await PlanStore.setStage(plan, 'STRATEGY');
            App.navigate(PlanStore.path(moved, 'STRATEGY'));
          } catch (e) { UI.toast(e.message, 'error'); }
        } }, 'Use these ' + r.conditioned.sample + ' ' + occurrenceWord(r)
          + ' when testing a strategy \u2192')));
    }
  }

  // ---- Notebook ----
  async function notebookCard() {
    var card = el('div', { class: 'card tool-card research-notebook' });
    var newBtn = el('button', { class: 'btn btn-sm', id: 'research-note-new' }, 'New note');
    card.appendChild(toolHeader('pen', 'Your research notes', newBtn));
    var list = el('div', { id: 'research-notes', class: 'tool-output' });
    card.appendChild(list);

    async function reload() {
      list.innerHTML = ''; list.appendChild(UI.spinner('Loading notes…'));
      try {
        var notes = (await API.get('/api/research/notes')).notes || [];
        list.innerHTML = '';
        if (!notes.length) list.appendChild(el('div', { class: 'muted small' }, 'No notes yet — save your hypotheses and conclusions here.'));
        notes.forEach(function (n) { list.appendChild(noteRow(n, reload)); });
      } catch (e) { list.innerHTML = ''; list.appendChild(alertBox('danger', 'Could not load notes', [String((e && e.message) || e)])); }
    }
    newBtn.addEventListener('click', function () {
      list.insertBefore(noteEditor(null, reload), list.firstChild);
    });
    await reload();
    return card;
  }

  function noteRow(n, reload) {
    return UI.expandable(
      el('span', {}, el('b', {}, n.title), '  ', el('span', { class: 'muted small' }, (n.updatedAt || '').slice(0, 10) + (n.tags ? '  · ' + n.tags : ''))),
      function () { return noteEditor(n, reload); });
  }

  function noteEditor(n, reload) {
    var title = el('input', { type: 'text', placeholder: 'Title', value: (n && n.title) || '' });
    var body = el('textarea', { rows: '4', placeholder: 'Your analysis…' }, (n && n.body) || '');
    var tags = el('input', { type: 'text', placeholder: 'tags (comma-separated)', value: (n && n.tags) || '' });
    var wrap = el('div', { class: 'note-editor' },
      UI.field('Title', title), UI.field('Notes', body), UI.field('Tags', tags));
    var save = el('button', { class: 'btn btn-sm' }, 'Save');
    save.addEventListener('click', async function () {
      save.disabled = true;
      try {
        if (n && n.id) await API.put('/api/research/notes/' + n.id, { title: title.value, body: body.value, tags: tags.value });
        else await API.post('/api/research/notes', { title: title.value, body: body.value, tags: tags.value });
        await reload();
      } catch (e) { save.disabled = false; wrap.appendChild(alertBox('danger', String((e && e.message) || e))); }
    });
    var row = el('div', { class: 'btn-row' }, save);
    if (n && n.id) {
      var del = el('button', { class: 'btn btn-sm btn-danger' }, 'Delete');
      del.addEventListener('click', async function () {
        del.disabled = true;
        try { await API.del('/api/research/notes/' + n.id); await reload(); }
        catch (e) { del.disabled = false; wrap.appendChild(alertBox('danger', 'Could not delete', [String((e && e.message) || e)])); }
      });
      row.appendChild(del);
    }
    wrap.appendChild(row);
    return wrap;
  }



  /** Saved notes, rendered inside Research where studying lives. */
  async function studyToolsSection() {
    // The landing page is a MARKET-ENTRY surface: sectors, symbols, recents, saved work.
    // The event-study workbench lives on the SYMBOL page, inside the thesis workflow —
    // the full event study belongs on a symbol page, not on the market index.
    var wrap = el('div', { id: 'research-study-tools' });
    var grid = el('div', { class: 'tool-grid' });
    grid.appendChild(await notebookCard());
    wrap.appendChild(grid);
    return wrap;
  }

  async function researchRoute(root, params) {
    var symbol = params && params[0] ? decodeURIComponent(params[0]).toUpperCase() : null;
    if (symbol) {
      await window.ViewPlan.provisionalStage(root, symbol);
      return;
    }
    await research(root, params || []);
  }

  window.ViewShared = Object.freeze({
    el: el, fmtMoney: fmtMoney, pnlSpan: pnlSpan, fmtPct: fmtPct, fmtNum: fmtNum,
    badge: badge, explain: explain, alertBox: alertBox, table: table, chip: chip,
    visibleCommand: visibleCommand, focusPlanFrom: focusPlanFrom,
    historyFetch: historyFetch, lazySparklines: lazySparklines,
    pressable: pressable, prettyStrategy: prettyStrategy, stat: stat,
    planIntentDestination: planIntentDestination, riskMode: riskMode,
    prettyPricingMode: prettyPricingMode, research: research, verdictPanel: verdictPanel,
    reported: reported, activeDecisionPnl: activeDecisionPnl, closedDecisionPnl: closedDecisionPnl,
    positiveInteger: positiveInteger, startPlan: startPlan, legLabel: legLabel,
    fmtBreakeven: fmtBreakeven, isYoungTrade: isYoungTrade, intentBadge: intentBadge
  });

  window.Views = {
    home: home,
    research: researchRoute,
    plan: function (root, params) { return window.ViewPlan.planWorkspace(root, params); },
    portfolio: function (root, params) { return window.ViewPortfolio.portfolio(root, params); },
    data: function (root, params) { return window.ViewData.data(root, params); }
  };
})();
