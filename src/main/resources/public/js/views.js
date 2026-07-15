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

  function toastWhenRouteReady(hash, message) {
    var expected = String(hash || '').split('?')[0];
    var frames = 0;
    function show() {
      var root = document.getElementById('app');
      var settled = window.location.hash.split('?')[0] === expected
        && App._lastRenderedRoute === expected
        && root && root.getAttribute('data-ready') === 'true' && !App._rendering;
      if (!settled && frames++ < 600) {
        window.requestAnimationFrame(show);
        return;
      }
      UI.toast(message);
    }
    window.requestAnimationFrame(show);
  }

  async function startPlan(prefill, stage, prepare) {
    prefill = Object.assign({}, prefill || {});
    var symbol = String(prefill.symbol || App.context.symbol() || '').trim().toUpperCase();
    if (!symbol) { App.navigate('#/research'); return Promise.resolve(null); }
    var owns = function (key) { return Object.prototype.hasOwnProperty.call(prefill, key); };
    var horizon = prefill.horizon || App.context.horizon('month');
    var days = Product.Horizon.sessions(horizon);
    var risk = document.getElementById('risk-mode');
    var intent = prefill.intent || App.context.goal('DIRECTIONAL');
    var destination = stage || planIntentDestination(intent);
    var explicitContext = owns('thesis') || owns('horizon') || owns('target');
    var requestedThesis = owns('thesis') && String(prefill.thesis || '').trim()
      ? String(prefill.thesis).trim().toLowerCase() : null;
    var requestedTarget = owns('target') && isFinite(parseFloat(prefill.target))
      ? Math.round(parseFloat(prefill.target) * 100) : null;
    function sameContext(plan) {
      var context = plan && plan.context || {};
      var thesis = String(context.thesis || '').trim().toLowerCase() || null;
      return (!owns('thesis') || thesis === requestedThesis)
        && (!owns('horizon') || Number(context.horizonDays || 0) === days)
        && (!owns('target') || Number(context.targetCents || 0) === Number(requestedTarget || 0));
    }
    async function createPlan(originPlan) {
      var originContext = originPlan && originPlan.context || {};
      return PlanStore.create({
        symbol: symbol, intent: intent, originPlanId: originPlan ? originPlan.id : null,
        thesis: owns('thesis') ? requestedThesis
          : originPlan ? originContext.thesis : App.context.thesis('neutral'),
        horizonDays: owns('horizon') ? days
          : originPlan && originContext.horizonDays ? originContext.horizonDays : days,
        targetCents: owns('target') ? requestedTarget
          : originPlan ? originContext.targetCents : null,
        riskMode: risk ? risk.value : 'conservative'
      });
    }
    try {
      var matches = await PlanStore.matching(symbol, intent);
      var exact = explicitContext ? matches.find(sameContext) : matches[0];
      if (exact && exact.assumptionsEditable !== false) {
        var existing = exact;
        if (prepare) await prepare(existing);
        await PlanStore.focus(existing, destination);
        toastWhenRouteReady(PlanStore.path(existing, destination),
          'Plan opened — ' + symbol + ' · ' + window.ViewPlan.intentLabel(intent));
        return existing;
      }
      // A completed Plan or a same-symbol inquiry with different explicit assumptions is
      // historical evidence, not a mutable scratchpad. Preserve it and create one linked
      // revision instead of silently replacing its context or discarding the new handoff.
      var origin = exact || (explicitContext && matches.length ? matches[0] : null);
      var plan = await createPlan(origin);
      if (prepare) await prepare(plan);
      await PlanStore.focus(plan, destination);
      toastWhenRouteReady(PlanStore.path(plan, destination),
        (origin ? 'Linked Plan created — ' : 'Plan created — ')
          + symbol + ' · ' + window.ViewPlan.intentLabel(intent));
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
          + String(activePlan.furthestStage || 'UNDERSTAND').replace('_', ' ').toLowerCase() + '.'
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

    // Home is a compact lens on the canonical Plans library. The shared renderer owns
    // grouping, market transitions, archive/delete actions, and decision marks.
    var planLibrary = el('section', { class: 'card home-plan-library', id: 'home-plan-library' });
    root.appendChild(planLibrary);
    var planLibraryFill = window.ViewPlan.renderLibrary(planLibrary, {
      title: 'Plans', removeWhenEmpty: true
    });

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
        window.ViewResearch.sectorRail({
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
          missingText: missing ? window.ViewResearch.missingSparkCopy(row) : 'No chart' }));
        // In Demo, the global band and each quote already say Demo; a second identical history
        // chip on every card adds noise, not honesty. Other lanes retain separate history evidence.
        if (!missing && row.evidence && !(App.state.world === 'demo' && row.evidence.provenance === 'DEMO')) {
          slot.appendChild(UI.evidenceBadge(row.evidence, { className: 'spark-ev', compact: true,
            missingLabel: 'HISTORY UNAVAILABLE' }));
        }
        updateHomeHistoryNotice();
      }
      window.ViewResearch.lazySparklines(tiles, marketSymbols, {
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
        var activeIndex = activePlan ? window.ViewPlan.stages.findIndex(function (stage) { return stage.key === activePlan.furthestStage; }) : -1;
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

  window.ViewShared = Object.freeze({
    el: el, fmtMoney: fmtMoney, pnlSpan: pnlSpan, fmtPct: fmtPct, fmtNum: fmtNum,
    badge: badge, explain: explain, alertBox: alertBox, table: table, chip: chip,
    visibleCommand: visibleCommand, focusPlanFrom: focusPlanFrom,
    pressable: pressable, prettyStrategy: prettyStrategy, stat: stat,
    planIntentDestination: planIntentDestination, riskMode: riskMode,
    prettyPricingMode: prettyPricingMode,
    reported: reported, activeDecisionPnl: activeDecisionPnl, closedDecisionPnl: closedDecisionPnl,
    positiveInteger: positiveInteger, startPlan: startPlan, legLabel: legLabel,
    stripZeros: stripZeros,
    fmtBreakeven: fmtBreakeven, isYoungTrade: isYoungTrade, intentBadge: intentBadge
  });

  window.Views = {
    home: home,
    research: function (root, params) { return window.ViewResearch.route(root, params); },
    plans: function (root) { return window.ViewPlan.plansHome(root); },
    plan: function (root, params) { return window.ViewPlan.planWorkspace(root, params); },
    portfolio: function (root, params) { return window.ViewPortfolio.portfolio(root, params); },
    data: function (root, params) { return window.ViewData.data(root, params); }
  };
})();
