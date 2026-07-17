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
    return String(intent || '').toUpperCase() === 'DIRECTIONAL' ? 'EVIDENCE' : 'STRATEGY';
  }

  /** A Plan can be explored while incomplete, but nothing may rank until these facts are owned. */
  function planContextDeclared(plan) {
    var intent = String(plan && plan.intent || '').toUpperCase();
    var context = plan && plan.context || {};
    var horizon = Number(context.horizonDays);
    var risk = String(context.riskMode || '').toLowerCase();
    return !!intent && Number.isInteger(horizon) && horizon > 0
      && ['conservative', 'balanced', 'aggressive'].indexOf(risk) >= 0
      && !!String(context.thesis || '').trim();
  }

  function explicitHorizonSessions(raw) {
    if (Number.isInteger(Number(raw)) && Number(raw) > 0) return Number(raw);
    var value = String(raw == null ? '' : raw).trim();
    if (!value) return null;
    if (/^\d+d$/i.test(value)) return Math.max(1, parseInt(value, 10));
    var key = value.toLowerCase() === '0dte' ? '0DTE' : value.toLowerCase();
    return Product.Horizon.table[key] ? Product.Horizon.table[key].sessions : null;
  }

  function explicitHeaderRiskMode() {
    var control = document.getElementById('risk-mode');
    return App.state.headerRiskExplicit && control
      && ['conservative', 'balanced', 'aggressive'].indexOf(control.value) >= 0
      ? control.value : null;
  }

  function discoveryContext() {
    return {
      world: App.state.world || 'observed',
      scenario: (App.config && App.config.scenarioMode && App.config.activeDataset) || 'observed',
      riskMode: 'conservative'
    };
  }

  function screenedCandidateReady(candidate) {
    return candidate && Number(candidate.maxLossCents) > 0
      && candidate.maxProfitCents !== null && candidate.maxProfitCents !== undefined
      && typeof candidate.pop === 'number';
  }

  function chooseScreenedCandidate(candidates) {
    var ready = (candidates || []).filter(screenedCandidateReady);
    return ready.find(function (candidate) { return window.ViewPlan.economicVerdict(candidate) === 'FAVORABLE'; })
      || ready.find(function (candidate) { return window.ViewPlan.economicVerdict(candidate) === 'MIXED'; })
      || ready[0] || (candidates || [])[0];
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
    var originHash = window.location.hash;
    prefill = Object.assign({}, prefill || {});
    var symbol = String(prefill.symbol || App.context.symbol() || '').trim().toUpperCase();
    if (!symbol) { App.navigate('#/research'); return Promise.resolve(null); }
    var owns = function (key) { return Object.prototype.hasOwnProperty.call(prefill, key); };
    var requestedHorizon = owns('horizon') && String(prefill.horizon == null ? '' : prefill.horizon).trim()
      ? prefill.horizon : null;
    var days = explicitHorizonSessions(requestedHorizon);
    if (requestedHorizon !== null && days === null) {
      UI.toast('Choose a valid Plan horizon before continuing.', 'error');
      return null;
    }
    var requestedRisk = owns('riskMode')
      ? String(prefill.riskMode == null ? '' : prefill.riskMode).trim().toLowerCase() || null
      : explicitHeaderRiskMode();
    if (requestedRisk && ['conservative', 'balanced', 'aggressive'].indexOf(requestedRisk) < 0) {
      UI.toast('Choose Cautious, Standard, or High for this Plan\'s risk posture.', 'error');
      return null;
    }
    var intent = owns('intent') ? prefill.intent : App.context.goal(null);
    var requestedDestination = stage || planIntentDestination(intent);
    var requestedThesis = owns('thesis')
      ? (String(prefill.thesis || '').trim() ? String(prefill.thesis).trim().toLowerCase() : null)
      : null;
    var requestedTarget = owns('target') && isFinite(parseFloat(prefill.target))
      ? Math.round(parseFloat(prefill.target) * 100) : null;
    function sameContext(plan) {
      var context = plan && plan.context || {};
      var thesis = String(context.thesis || '').trim().toLowerCase() || null;
      var planHorizon = context.horizonDays == null ? null : Number(context.horizonDays);
      var planTarget = context.targetCents == null ? null : Number(context.targetCents);
      var planRisk = String(context.riskMode || '').trim().toLowerCase() || null;
      // Missing facts define an honest incomplete inquiry too. Do not reopen a populated
      // same-symbol Plan and make its older assumptions look like part of this handoff.
      return thesis === requestedThesis && planHorizon === days && planTarget === requestedTarget
        && planRisk === requestedRisk;
    }
    async function createPlan(originPlan) {
      return PlanStore.create({
        symbol: symbol, intent: intent, originPlanId: originPlan ? originPlan.id : null,
        thesis: requestedThesis, horizonDays: days, targetCents: requestedTarget,
        riskMode: requestedRisk
      });
    }
    function destinationFor(plan) {
      return planContextDeclared(plan) ? requestedDestination : 'UNDERSTAND';
    }
    try {
      var matches = await PlanStore.matching(symbol, intent);
      var exact = matches.find(sameContext);
      if (exact && exact.assumptionsEditable !== false) {
        var existing = exact;
        if (prepare) existing = await prepare(existing) || existing;
        if (window.location.hash === originHash) {
          var existingDestination = destinationFor(existing);
          await PlanStore.focus(existing, existingDestination);
          toastWhenRouteReady(PlanStore.path(existing, existingDestination),
            'Plan opened — ' + symbol + ' · ' + window.ViewPlan.intentLabel(intent));
        }
        return existing;
      }
      // A completed Plan or a same-symbol inquiry with different explicit assumptions is
      // historical evidence, not a mutable scratchpad. Preserve it and create one linked
      // revision instead of silently replacing its context or discarding the new handoff.
      var origin = exact || (matches.length ? matches[0] : null);
      var plan = await createPlan(origin);
      if (prepare) plan = await prepare(plan) || plan;
      if (window.location.hash === originHash) {
        var planDestination = destinationFor(plan);
        await PlanStore.focus(plan, planDestination);
        toastWhenRouteReady(PlanStore.path(plan, planDestination),
          (origin ? 'Linked Plan created — ' : 'Plan created — ')
            + symbol + ' · ' + window.ViewPlan.intentLabel(intent));
      }
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
          el('span', { class: 'eyebrow' }, 'PRACTICE TRADING \u00B7 LOCAL-FIRST \u00B7 FREE')),
        title: el('h1', { class: 'hero-title' }, 'Learn options by ', el('span', { class: 'grad' }, 'doing'), ',', el('br', {}),
          'with honest numbers.'),
        sub: el('p', { class: 'hero-sub' },
          'Screen strategies against your goal, practice with \$100,000 in an isolated learning account, and verify honestly.'),
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
        UI.fact(UI.vocabulary('theoreticalMaxLoss'), fmtMoney(candidate.maxLossCents), 'f-danger'),
        UI.fact('Chance of any profit', fmtPct(candidate.pop)),
        UI.fact(UI.vocabulary('theoreticalMaxProfit'), UI.maxProfitLabel(
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
      var proofCtx = discoveryContext();
      var cached = null;
      try {
        var raw = JSON.parse(localStorage.getItem('strikebench.welcomeProof') || 'null');
        if (raw && screenedCandidateReady(raw.candidate)
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
          var proofCandidate = chooseScreenedCandidate(r.candidates);
          liveHost.innerHTML = '';
          liveHost.appendChild(welcomeProofCard(proofCandidate));
          frameWelcomeProof(proofCandidate);
          try {
            localStorage.setItem('strikebench.welcomeProof', JSON.stringify(
              Object.assign({ candidate: proofCandidate, asOf: Date.now(), basis: {
                thesis: r.thesis, horizon: r.horizon, riskMode: r.riskMode
              } }, proofCtx)));
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
        toast: 'Step 1: pick a stock, then use Ready to compare to carry it into the Plan.' },
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
      ['Practice the trade', 'with the isolated $100k practice account'],
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

    root.appendChild(section('WHAT MOVED WHERE', 'Familiar tools, one Plan-centered journey',
      el('dl', { class: 'moved-terms', id: 'moved-terms' }, (Learn.MOVED_TERMS || []).map(function (item) {
        return el('div', { class: 'moved-term' },
          el('dt', {}, item.infoKey ? UI.term(item.infoKey, item.current) : item.current),
          el('dd', {}, el('span', { class: 'muted' }, 'Formerly ' + item.former + '. '), item.detail));
      }))));

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

  /**
   * The assignment-acceptance helper (spec 10.1): a TEACHING PREVIEW for an early-assignment
   * alert — campaign-adjusted economic basis when a campaign exists, the concentration effect
   * on the owning account, and the next-step preview. "Accept assignment" records NOTHING;
   * assignment happens at the broker (or the practice ledger) when it actually occurs.
   */
  async function assignmentAcceptanceHelper(alert) {
    var beginner = Learn.currentLevel() === 'beginner';
    var meta = alert.meta || {};
    var isPut = String(meta.optionType || 'PUT').toUpperCase() !== 'CALL';
    var contracts = Number(meta.quantity || 1);
    var multiplier = Number(meta.multiplier || 100);
    var shares = contracts * multiplier;
    var strikeCents = Number(meta.strikeCents || 0);
    var obligationCents = strikeCents * shares;
    var body = el('div', { class: 'assignment-helper' },
      el('p', {}, beginner
        ? 'Assignment means the option is used against you. Nothing here places an order — this is a rehearsal of what would happen.'
        : 'Preview of the assignment economics. No order, ledger entry, or receipt is created.'),
      el('div', { class: 'fact-row' }, el('span', {},
        el('b', {}, 'What happens: '),
        isPut
          ? 'you buy ' + shares + ' shares of ' + alert.symbol + ' at ' + fmtMoney(strikeCents)
            + ' per share — ' + fmtMoney(obligationCents) + ' total.'
          : 'you deliver ' + shares + ' shares of ' + alert.symbol + ' at ' + fmtMoney(strikeCents)
            + ' per share — you receive ' + fmtMoney(obligationCents) + '.')));
    var basisSlot = el('div', { class: 'fact-row' }, el('span', { class: 'muted' },
      'Checking for a linked campaign…'));
    var concentrationSlot = el('div', { class: 'fact-row' }, el('span', { class: 'muted' },
      'Computing the concentration effect…'));
    body.appendChild(basisSlot);
    body.appendChild(concentrationSlot);
    body.appendChild(el('div', { class: 'fact-row' }, el('span', {},
      el('b', {}, 'Next step: '),
      isPut
        ? 'once you own the shares, the usual continuations are: hold them, sell covered calls against them (the wheel), or sell them. Each shows up as its own decision — nothing is automatic.'
        : 'once the shares are delivered you keep the premium plus the sale proceeds. The usual continuations are: stay out, re-enter with shares, or sell a cash-secured put to re-acquire lower.')));
    body.appendChild(el('p', { class: 'muted small' },
      (alert.detail && alert.detail.indexOf('Ex-dividend') >= 0
        ? 'Ex-dividend dates: unavailable — no sourced dividend calendar is connected. '
        : '') + 'The trigger is a labeled heuristic, not a prediction.'));

    var modal = UI.confirmModal(
      beginner ? 'If you accept assignment' : 'Assignment acceptance preview',
      body,
      'Accept assignment (preview only)',
      function () {
        UI.toast('Nothing was recorded. Assignment happens at settlement; this preview only rehearsed the economics.');
      });

    // Progressive fills: campaign-adjusted basis (campaigns own that math) and concentration.
    (async function fillBasis() {
      try {
        var campaigns = (await API.get('/api/campaigns')).campaigns || [];
        var match = (campaigns || []).filter(function (c) {
          return c.symbol === alert.symbol && c.economicBasis && c.economicBasis.available;
        })[0];
        basisSlot.replaceChildren(el('span', {},
          UI.vocabulary('campaignEconomicBasis'), ': ',
          match
            ? el('span', {}, el('b', {}, fmtMoney(match.economicBasis.perShareCents)),
                ' per share across the "' + (match.title || alert.symbol) + '" campaign ('
                  + match.economicBasis.sharesHeld + ' shares held). Assignment at '
                  + fmtMoney(strikeCents) + ' continues that campaign.')
            : el('span', { class: 'muted' }, 'No campaign is linked to ' + alert.symbol
                + ' yet, so there is no campaign-adjusted number. The raw arithmetic: the strike '
                + (isPut ? 'minus' : 'plus') + ' the premium you collected is your effective '
                + (isPut ? 'purchase' : 'sale') + ' price per share.')));
      } catch (e) {
        basisSlot.replaceChildren(el('span', { class: 'muted' },
          'Campaign figures are unavailable right now; the assignment mechanics above still hold.'));
      }
    })();
    (async function fillConcentration() {
      try {
        var totalCents = null, denominatorLabel = 'account value';
        if (alert.lane === 'TRACKED' && alert.accountId) {
          var tracked = await API.get('/api/portfolio/accounts/' + encodeURIComponent(alert.accountId) + '/summary');
          if (tracked.totalValueCents != null) totalCents = tracked.totalValueCents;
          else { totalCents = tracked.bookCashCents; denominatorLabel = 'account cash (marks incomplete)'; }
        } else {
          var practice = await API.get('/api/portfolio/summary');
          totalCents = practice.totalValueCents;
          denominatorLabel = 'practice account value';
        }
        var pct = totalCents > 0 ? (obligationCents / totalCents) * 100 : null;
        concentrationSlot.replaceChildren(el('span', {},
          el('b', {}, 'Concentration: '),
          pct == null
            ? 'the owning account’s value is unavailable, so no honest percentage can be shown.'
            : 'the ' + fmtMoney(obligationCents) + (isPut ? ' purchase' : ' delivery')
              + ' is ≈' + fmtNum(pct, 1) + '% of ' + denominatorLabel + ' ('
              + fmtMoney(totalCents) + ').'
              + (isPut && pct != null && pct >= 25
                ? ' That is a concentrated single-name position — worth deciding deliberately.' : '')));
      } catch (e) {
        concentrationSlot.replaceChildren(el('span', { class: 'muted' },
          'The concentration effect could not be computed right now.'));
      }
    })();
    return modal;
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

  function homeDiscoveryContext() {
    var context = discoveryContext();
    var active = App.state.universe && App.state.universe.active || {};
    context.universe = [active.sectorKey || '', (active.symbols || []).join(',')].join(':');
    return context;
  }

  function homeIdeasSection() {
    var candidate = null;
    var basis = null;
    var ctx = homeDiscoveryContext();
    var body = el('div', { class: 'home-ideas-body' });
    var basisLine = el('p', { class: 'muted small home-ideas-basis', id: 'home-ideas-basis' });
    var section = el('section', { class: 'card home-screened-ideas', id: 'home-screened-ideas' },
      UI.cardHeader(el('span', {}, 'Today\u2019s screened idea', UI.info('proposedtrades'))),
      el('p', { class: 'muted home-ideas-intro' },
        'A read-only engine teaser from one governed source. Open its ticker in Workspace to inspect the market and use the one canonical Plan handoff.'),
      basisLine, body);

    function sameContext(value) {
      return value && value.world === ctx.world && value.scenario === ctx.scenario
        && (!value.universe || value.universe === ctx.universe);
    }

    function setIdea(next, symbol, nextBasis) {
      if (!next || !(next.legs || []).length || next.maxLossCents == null) return;
      symbol = String(symbol || next.symbol || '').toUpperCase();
      if (!symbol) return;
      candidate = Object.assign({}, next, { symbol: symbol });
      basis = nextBasis;
    }

    function addTeaching(response) {
      if (!response || !(response.candidates || []).length || !response.thesis
          || !response.horizon || !response.riskMode) return;
      setIdea(chooseScreenedCandidate(response.candidates), response.symbol || 'AAPL', {
        thesis: response.thesis, horizon: response.horizon, riskMode: response.riskMode
      });
    }

    function ideaRow(item) {
      var verdict = window.ViewPlan.economicVerdict(item) || 'UNAVAILABLE';
      var open = el('button', { type: 'button', class: 'btn btn-sm', onclick: function () {
        App.navigate('#/research/' + encodeURIComponent(item.symbol));
      } }, 'Open in Workspace');
      var row = window.ViewPlan.ideaPresentation(item, { density: 'row', rank: 1,
        kicker: item.symbol + ' · System-authored screen', className: 'home-idea-row',
        action: open });
      row.dataset.symbol = item.symbol;
      row.dataset.economicVerdict = verdict;
      return row;
    }

    function paint() {
      body.replaceChildren();
      if (!candidate) {
        basisLine.textContent = 'System-authored basis will appear with the result; it never becomes your Plan declaration.';
        body.appendChild(el('div', { class: 'home-ideas-empty' },
          el('b', {}, 'No governed screen is available in this market right now.'),
          el('span', { class: 'muted' }, 'Open Workspace to research any ticker or run the one Universe Scout there.'),
          el('button', { type: 'button', class: 'btn btn-sm', id: 'home-open-workspace',
            onclick: function () { App.navigate('#/research'); } }, 'Open Workspace')));
        return;
      }
      var riskLabel = ({ conservative: 'Cautious', balanced: 'Standard', aggressive: 'High' })[basis.riskMode]
        || basis.riskMode || 'not reported';
      var horizonLabel = ({ week: '1 week', month: '1 month', quarter: '3 months', '0DTE': 'today' })[basis.horizon]
        || basis.horizon || 'not reported';
      basisLine.textContent = 'System-authored screening basis · ' + (basis.thesis || 'view not reported')
        + ' · ' + horizonLabel + ' · ' + riskLabel
        + '. These explain this teaser; they are not silently copied into your Plan.';
      body.appendChild(ideaRow(candidate));
    }

    var fill = (async function () {
      try {
        var proof = JSON.parse(localStorage.getItem('strikebench.welcomeProof') || 'null');
        if (sameContext(proof) && proof.basis && proof.basis.thesis && proof.basis.horizon
            && proof.basis.riskMode && proof.asOf
            && Date.now() - proof.asOf < 30 * 60 * 1000) {
          setIdea(proof.candidate, proof.candidate && proof.candidate.symbol || 'AAPL', proof.basis);
        }
      } catch (e) { /* no prior governed-source cache */ }
      paint();
      var warm = await API.prefetch('/api/welcome/teaching-example');
      if (warm) { addTeaching(warm); paint(); }
    })();
    return { node: section, fill: fill };
  }

  // ---------- 0. Home dashboard ----------

  async function home(root, params) {
    if (params && params[0] === 'tour') return welcome(root);
    var needsWelcomeDecision = !welcomed();
    var accountPromise = API.get('/api/account').then(function (response) { return response.account; })
      .catch(function () { return null; });
    // Only first contact must wait before painting: we need the durable account fact to
    // choose Welcome vs Home without flashing the wrong page. Once onboarding is known,
    // account data is an independent progressive fill and must not hold the route shell.
    var acct = needsWelcomeDecision ? await accountPromise : null;
    // Decide first contact before any dashboard node is mounted. The previous implementation
    // painted Home and then replaced it with Welcome after /api/account answered, producing a
    // visible dashboard flash and briefly exposing controls the user had not chosen yet.
    if (needsWelcomeDecision) {
      if (!acct || !acct.hasTraded) return welcome(root);
      try { window.localStorage.setItem('strikebench.welcomed', '1'); } catch (e2) { /* ignore */ }
    }
    // Reconcile durable Plan truth before deriving Continue, but keep a stable Home frame on
    // screen while that independent service answers. First contact never waits on Plans unless
    // the account decision says this user already belongs on Home.
    var restoreShell = null;
    if (window.PlanStore) {
      restoreShell = heroBlock('dashboard', {
        eyebrow: ['RESTORING YOUR DESK'],
        title: el('h1', { class: 'home-hero-title' }, 'Your ', el('span', { class: 'grad' }, 'desk'), '.'),
        sub: el('p', { class: 'home-hero-sub' },
          'Reconciling your current Plans before showing a continuation action.'),
        aside: UI.skeleton(), ctas: []
      });
      restoreShell.id = 'home-restore-shell';
      root.appendChild(restoreShell);
      try { await PlanStore.load(true); }
      catch (e3) { /* the independent Plan-library card reports the unavailable service below */ }
      restoreShell.remove();
    }
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
    var discovery = homeIdeasSection();
    function homePlanActions(plan, identity) {
      return plan
        ? [el('button', { class: 'btn', onclick: function () { focusPlanFrom(this, plan); } },
          'Continue ' + plan.symbol + (identity.duplicate ? ' · ' + identity.duplicate : '') + ' \u2192')]
        : [];
    }
    var heroCtas = homePlanActions(activePlan, activeIdentity);
    // The hero owns THE single primary next action (review P5): the tour demoted to a
    // quiet entry at the bottom of the page — onboarding is not a permanent command.
    root.appendChild(heroBlock('dashboard', {
      eyebrow: ['YOUR PRACTICE DESK ', heroMode],
      title: el('h1', { class: 'home-hero-title' }, 'Your ', el('span', { class: 'grad' }, 'desk'), '.'),
      sub: heroSub,
      aside: statsAnchor,
      ctas: heroCtas
    }));
    // Discovery is the first desk content after the hero. It is a governed front door
    // into the existing ranking and Plan machinery, never a second recommendation engine.
    root.appendChild(discovery.node);
    if (Learn.currentLevel() === 'beginner') {
      root.appendChild(el('section', { class: 'home-first-steps', id: 'home-first-steps',
        'aria-label': 'Your first three steps' },
        [['1', 'Choose one screened idea', 'The economic label and exact package travel with it.'],
          ['2', 'Compare in Strategy', 'See alternatives, change contracts, or start from every strategy.'],
          ['3', 'Test, decide, review', 'Market odds, possible futures, past analogs, and replay stay separate.']]
          .map(function (step) {
            return el('div', { class: 'home-first-step' }, el('span', { class: 'home-first-number' }, step[0]),
              el('span', {}, el('b', {}, step[1]), el('small', {}, step[2])));
          })));
    }

    var rankedStatusFill = activePlan ? PlanStore.latestStrategy(activePlan.id, false).then(function (latest) {
      var candidates = latest && latest.strategy && latest.strategy.result
        && latest.strategy.result.candidates || [];
      if (candidates.length && heroSub.isConnected) {
        heroSub.textContent = activePlan.symbol + ' · ' + activeIdentity.full
          + ' has ranked trades ready in Strategy.';
      }
    }).catch(function () { /* the Plan stage remains the honest fallback */ }) : Promise.resolve();

    var accountFill = Promise.resolve(acct || accountPromise).then(function (account) {
      if (account) {
        statsAnchor.appendChild(stat('Cash', fmtMoney(account.cashCents), 'Practice money available. Nothing here is real dollars.'));
        statsAnchor.appendChild(stat(UI.vocabulary('brokerReserve'), fmtMoney(account.reservedCents), 'Held to cover open obligations.'));
        statsAnchor.appendChild(stat(UI.vocabulary('buyingPower'), fmtMoney(account.buyingPowerCents),
          'Cash minus broker reserve — what you can still put at risk.'));
        statsAnchor.appendChild(stat('Started with', fmtMoney(account.startingCashCents)));
      } else {
        statsAnchor.appendChild(alertBox('warn', 'Account unavailable right now', ['Retry from the Portfolio screen.']));
      }
    });

    // The Desk owns the plan journeys: a compact resume lens (the hero already owns the
    // active Plan) plus the full library as an archive drawer — no separate route. The alert
    // center drives its needs-attention ordering: alert rows merge into the same rail —
    // enriching a Plan's row where one exists, standing alone where none does.
    var planLibrary = el('section', { class: 'card home-plan-library', id: 'home-plan-library' });
    root.appendChild(planLibrary);
    var railToken = App.navToken;
    async function fillAttentionRail() {
      var alertData = App.state.alertData || null;
      try { alertData = await App.refreshAlerts(); }
      catch (e) { /* the last honest fetch (or none) still renders */ }
      if (!App.alive(railToken) || !planLibrary.isConnected) return;
      var alertRows = alertData && alertData.alerts || [];
      await window.ViewPlan.renderLibrary(planLibrary, {
        title: alertRows.length
          ? el('span', {}, 'Needs attention', UI.info('alertcenter'))
          : (activePlan ? 'Other Plans' : 'Plans'),
        removeWhenEmpty: !alertRows.length,
        compact: true, excludePlanId: activePlan && activePlan.id,
        alerts: alertRows,
        onAlertOpen: function (alert) {
          if (alert.kind === 'ASSIGNMENT') { assignmentAcceptanceHelper(alert); return; }
          if (alert.deepLink) App.navigate(alert.deepLink);
        }
      });
    }
    var planLibraryFill = fillAttentionRail();
    App.onEvent('alerts.updated', function () {
      fillAttentionRail().catch(function () { /* the rail keeps its last honest state */ });
    }, railToken);
    var drawerHost = el('section', { class: 'card home-plan-drawer', id: 'home-plan-drawer' });
    var drawer = UI.expandable('Plan library — every market, archive included', function () {
      var full = el('div', { id: 'plans-library' });
      window.ViewPlan.renderLibrary(full, { full: true, title: 'Plans' });
      return full;
    }, { stateKey: 'home-plan-drawer' });
    drawerHost.appendChild(drawer);
    root.appendChild(drawerHost);
    if (App.state.openPlanDrawer) {
      delete App.state.openPlanDrawer;
      var drawerHead = drawer.querySelector('.xp-head');
      if (drawerHead && drawerHead.getAttribute('aria-expanded') !== 'true') drawerHead.click();
      drawerHost.scrollIntoView({ block: 'start' });
    }
    // PlanStore reconciliation is a data event, not a page navigation. Refresh only the
    // Plan-owned Home components; the discovery cards, their drafts, scroll and the durable
    // route mount remain untouched.
    App.onEvent('plans.refreshed', function () {
      if (!planLibrary.isConnected) return;
      activePlan = PlanStore.active();
      activeIdentity = activePlan && PlanStore.identity(activePlan, PlanStore.all());
      heroSub.textContent = activePlan
        ? activePlan.symbol + ' · ' + activeIdentity.full + ' is at '
          + String(activePlan.furthestStage || 'UNDERSTAND').replace('_', ' ').toLowerCase() + '.'
        : 'Screened ideas, honest odds, worst case always known before you commit.';
      var actionHost = root.querySelector('.home-hero-ctas');
      if (actionHost) actionHost.replaceChildren.apply(actionHost, homePlanActions(activePlan, activeIdentity));
      fillAttentionRail().catch(function () { /* keep the last honest rail */ });
      var full = drawerHost.querySelector('#plans-library');
      if (full) window.ViewPlan.renderLibrary(full, { full: true, title: 'Plans' });
    }, railToken);

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

    // Open positions: with no trades the whole card stays off the page; the hero already owns
    // the next action. With trades, three useful rows show live P/L, max loss, and opened date.
    var tradesFill = (async function fillTrades() {
      try {
        var open = await API.get('/api/trades?status=ACTIVE&size=3');
        if (!open.trades.length) { posAnchor.remove(); return; }
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

    // The hero owns the only Continue command. This supporting card communicates progress
    // without repeating Resume or turning every stage label into another navigation path.
    (function planProgress() {
      if (!activePlan) return;
      var activeIndex = window.ViewPlan.stages.findIndex(function (stage) {
        return stage.key === activePlan.furthestStage;
      });
      var identity = PlanStore.identity(activePlan, PlanStore.all());
      var card = el('div', { class: 'card card-slim', id: 'next-up' },
        UI.cardHeader('Plan progress'),
        el('p', { class: 'muted small plan-progress-context' }, activePlan.symbol + ' · ' + identity.full),
        el('div', { class: 'plan-progress', id: 'journey-card', 'aria-label': 'Plan progress' },
          window.ViewPlan.stages.map(function (stage, index) {
            var state = index < activeIndex ? ' done' : index === activeIndex ? ' current' : '';
            return el('div', { class: 'plan-progress-step' + state },
              el('span', { class: 'ck-mark', 'aria-hidden': 'true' }, index < activeIndex ? '' : String(index + 1)),
              el('span', {}, stage.label));
          })));
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
    await Promise.all([accountFill, discovery.fill, rankedStatusFill, planLibraryFill, marketsFill, tradesFill]);
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
    planContextDeclared: planContextDeclared,
    stripZeros: stripZeros,
    fmtBreakeven: fmtBreakeven, isYoungTrade: isYoungTrade, intentBadge: intentBadge
  });

  window.Views = {
    home: home,
    learn: function (root) { return window.ViewLearn.route(root); },
    research: function (root, params) { return window.ViewResearch.route(root, params); },
    plans: function (root) { return window.ViewPlan.plansHome(root); },
    plan: function (root, params) { return window.ViewPlan.planWorkspace(root, params); },
    portfolio: function (root, params) { return window.ViewPortfolio.portfolio(root, params); },
    data: function (root, params) { return window.ViewData.data(root, params); }
  };
})();
