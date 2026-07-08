/*
 * StrikeBench on-device assist layer: transformers.js (WASM, WebGPU when available) over
 * quantized ONNX models served from /models (a DISK directory — optional, never in the jar).
 *
 * HARD RULES (the browser-LLM contract):
 *   - Models never produce a number the engine relies on. They classify text the user typed
 *     or headlines already on screen; every extracted figure comes from the user's own words
 *     via explicit regex, and the user confirms the filled form before anything is submitted.
 *   - Absent models = the feature simply does not appear. Nothing else changes.
 *   - Everything model-derived is labeled "on-device" in the UI.
 */
(function () {
  'use strict';

  var el = UI.el;
  var state = { status: 'unknown', device: null, manifest: null };
  var runtimeP = null;
  var pipes = {};

  /**
   * Installer-aware detection: 'available' (installed), 'installable' (server can fetch the
   * Apache/MIT assets on the user's say-so), or 'absent' (no server support). The app ships
   * ZERO model bytes — availability always traces back to a user-initiated install.
   */
  async function detect() {
    if (state.status !== 'unknown') return state.status;
    try {
      var st = await API.get('/api/assist/status');
      state.install = st;
      state.status = st.installed ? 'available' : 'installable';
    } catch (e) {
      state.status = 'absent';
    }
    return state.status;
  }

  function runtime() {
    if (!runtimeP) {
      runtimeP = import('/models/runtime/transformers.min.js').then(function (T) {
        T.env.allowRemoteModels = false;   // correctness: nothing ever fetched from a CDN
        T.env.allowLocalModels = true;
        T.env.localModelPath = '/models/';
        T.env.backends.onnx.wasm.wasmPaths = '/models/runtime/';
        return T;
      });
    }
    return runtimeP;
  }

  /** Lazy, memoized pipeline. WebGPU when the browser offers it, WASM otherwise. */
  function pipe(task, model) {
    var key = task + ':' + model;
    if (!pipes[key]) {
      pipes[key] = (async function () {
        var T = await runtime();
        var device = 'wasm';
        try {
          if (navigator.gpu && await navigator.gpu.requestAdapter()) device = 'webgpu';
        } catch (e) { /* wasm it is */ }
        try {
          var p = await T.pipeline(task, model, { device: device, dtype: 'q8' });
          state.device = device;
          return p;
        } catch (e) {
          // WebGPU init can fail on some drivers — the WASM path is the correctness anchor
          var p2 = await T.pipeline(task, model, { device: 'wasm', dtype: 'q8' });
          state.device = 'wasm';
          return p2;
        }
      })();
    }
    return pipes[key];
  }

  // ---------- Feature 1: on-device headline sentiment (display layer ONLY) ----------

  /**
   * Badges each rendered headline positive/negative/neutral with the model's confidence.
   * Display-only by design: the engine's deterministic keyword signals are NOT changed —
   * the scout and backtester see exactly what they saw before this file existed.
   */
  async function enhanceNews(card, items) {
    if (await detect() !== 'available') return;
    var note = el('p', { class: 'muted assist-note' },
      'Scoring headlines with the on-device FinBERT model…');
    card.appendChild(note);
    try {
      var zs = await pipe('zero-shot-classification', 'nli-deberta-v3-xsmall');
      var LABELS = ['good news for the stock price', 'bad news for the stock price', 'neutral news with little price impact'];
      var NAME = { 'good news for the stock price': 'POSITIVE', 'bad news for the stock price': 'NEGATIVE',
                   'neutral news with little price impact': 'NEUTRAL' };
      var rows = card.querySelectorAll('.status-item');
      for (var i = 0; i < items.length; i++) {
        if (!rows[i]) continue;
        var r = await zs(items[i].headline, LABELS, { hypothesis_template: 'This financial headline is {}.' });
        var label = NAME[r.labels[0]] || 'NEUTRAL';
        var score = Math.round(r.scores[0] * 100);
        var cls = label === 'POSITIVE' ? 'badge-ok' : label === 'NEGATIVE' ? 'badge-danger' : 'badge-dim';
        rows[i].insertBefore(
          el('span', { class: 'badge ' + cls + ' assist-sent', title: 'On-device zero-shot model, ' + score + '% confident — display only, the engine’s signals are unchanged' },
            label + ' ' + score + '%'),
          rows[i].querySelector('.spacer'));
      }
      note.textContent = 'Sentiment scored on your device (' + (state.device || 'wasm')
        + ') — display only; the engine’s deterministic signals are unchanged.';
    } catch (e) {
      note.textContent = 'On-device sentiment unavailable: ' + e.message;
    }
  }

  // ---------- Feature 2: free-text idea intake (fills the form; human confirms) ----------

  var GOAL_LABELS = [
    { label: 'bet on the stock price going up or down', intent: 'DIRECTIONAL' },
    { label: 'collect regular income from option premium', intent: 'INCOME' },
    { label: 'get to buy the stock cheaper than today', intent: 'ACQUIRE' },
    { label: 'sell shares they already own at a target price', intent: 'EXIT' },
    { label: 'insure shares they already own against losses', intent: 'HEDGE' }
  ];
  var THESIS_LABELS = [
    { label: 'the stock price will go up', thesis: 'bullish' },
    { label: 'the stock price will go down', thesis: 'bearish' },
    { label: 'the stock price will stay about the same', thesis: 'neutral' },
    { label: 'the stock price will move a lot in either direction', thesis: 'volatile' }
  ];
  var NOT_TICKERS = { A: 1, I: 1, MAX: 1, MIN: 1, CALL: 1, PUT: 1, ETF: 1, USD: 1, DTE: 1, POP: 1, OK: 1, VS: 1, ON: 1, THE: 1, AND: 1, BUY: 1, SELL: 1, HOLD: 1, LOSE: 1, RISK: 1 };

  /**
   * Deterministic goal rules run BEFORE the model: when the user's own words name the goal
   * ("I want to buy some more"), no classifier gets to overrule them — and the chip shows
   * exactly which words matched, not a fake confidence. First match wins.
   */
  var GOAL_RULES = [
    { intent: 'ACQUIRE', re: /\b(?:buy|add|pick\s+up|accumulate|grab)(?:\s+\w+){0,3}?\s+(?:more|shares|stock|the\s+dip|cheaper|at\s+a\s+discount|lower)\b|\bbuy\s+(?:some|it|a\s+bit)?\s*more\b|\bget\s+in\s+(?:cheaper|lower)\b|\baverage\s+(?:down|in)\b|\bown\s+more\b/i },
    { intent: 'EXIT', re: /\b(?:sell|trim|unload|offload|lighten)(?:\s+\w+){0,3}?\s+(?:shares|stock|position|holdings?)\b|\btake\s+profits?\b|\bget\s+out\s+at\b/i },
    { intent: 'HEDGE', re: /\b(?:protect|hedge|insure|insurance)\b|\bdownside\s+protection\b|\b(?:floor|cap)\s+(?:my\s+)?loss(?:es)?\b/i },
    { intent: 'INCOME', re: /\b(?:income|premium|get\s+paid|rent\s+(?:out\s+)?(?:my\s+)?shares|yield)\b/i }
  ];

  /**
   * Parses free text into a structured idea. Numbers come ONLY from the user's own words
   * (regex extraction); the model contributes classification (goal, thesis) — never figures.
   */
  async function parseIdea(text) {
    var out = { symbol: null, goal: null, goalScore: null, thesis: null, thesisScore: null,
                maxLoss: null, horizon: null };
    // --- deterministic extraction first ---
    var dollar = text.match(/(?:max(?:imum)?\s*(?:loss|risk)?|risk|lose|losing|down)\D{0,12}\$?\s*([\d][\d,]*(?:\.\d+)?)/i)
              || text.match(/\$\s*([\d][\d,]*(?:\.\d+)?)/);
    if (dollar) out.maxLoss = parseFloat(dollar[1].replace(/,/g, ''));
    if (/0\s*dte|same[- ]day|today/i.test(text)) out.horizon = '0DTE';
    else if (/\bweek|weekly|next few days\b/i.test(text)) out.horizon = 'week';
    else if (/\bquarter|3 months|90 days\b/i.test(text)) out.horizon = 'quarter';
    else if (/\bmonth|monthly|earnings|30 days\b/i.test(text)) out.horizon = 'month';
    var known = {};
    document.querySelectorAll('#universe-symbols option').forEach(function (o) { known[o.value] = true; });
    var dollarSym = text.match(/\$([A-Za-z]{1,5})\b/);
    if (dollarSym && !/^\d/.test(dollarSym[1])) out.symbol = dollarSym[1].toUpperCase();
    if (!out.symbol) {
      // Known tickers match case-insensitively ("qqq" is QQQ); unknown ones must be
      // written in caps — lowercase words are too easily ordinary English.
      var anyCase = (text.match(/\b[A-Za-z]{2,5}\b/g) || []).map(function (t) { return t.toUpperCase(); });
      var caps = text.match(/\b[A-Z]{2,5}\b/g) || [];
      out.symbol = anyCase.find(function (t) { return known[t]; })
        || caps.find(function (t) { return !NOT_TICKERS[t]; }) || null;
    }
    // Goal keywords beat the model — the user's own verbs are the ground truth.
    for (var ri = 0; ri < GOAL_RULES.length; ri++) {
      var hit = text.match(GOAL_RULES[ri].re);
      if (hit) { out.goal = GOAL_RULES[ri].intent; out.goalRule = hit[0].trim(); break; }
    }
    // --- model classification (labels only, never numbers) ---
    var zs = await pipe('zero-shot-classification', 'nli-deberta-v3-xsmall');
    var thesisRes = await zs(text, THESIS_LABELS.map(function (t) { return t.label; }),
      { hypothesis_template: 'The person believes {}.' });
    var t = THESIS_LABELS.find(function (x) { return x.label === thesisRes.labels[0]; });
    out.thesis = t ? t.thesis : null;
    out.thesisScore = thesisRes.scores[0];
    if (!out.goal) {
      var goalRes = await zs(text, GOAL_LABELS.map(function (g) { return g.label; }),
        { hypothesis_template: 'This person primarily wants to {}.' });
      var g = GOAL_LABELS.find(function (x) { return x.label === goalRes.labels[0]; });
      out.goal = g ? g.intent : 'DIRECTIONAL';
      out.goalScore = goalRes.scores[0];
      // Explainable tie-break: a WEAK goal verdict beside a STRONG directional view means the
      // sentence is a price call, not a holdings plan ("TSLA drops hard, max $400" ≠ hedging).
      if (out.goalScore < 0.35 && out.thesis && out.thesis !== 'neutral' && out.thesisScore >= 0.5
          && out.goal !== 'DIRECTIONAL') {
        out.goal = 'DIRECTIONAL';
        out.goalAdjusted = true;
      }
      // Still murky? Say so — a 21% "confidence" applied silently is worse than asking.
      if (!out.goalAdjusted && out.goalScore < 0.45) out.goalUncertain = true;
    }
    return out;
  }

  /** Enable card: the user explicitly triggers the download; progress is honest and polled. */
  function enableCard() {
    var mb = Math.round((state.install && state.install.bytesTotal || 112000000) / 1e6);
    var status = el('div', { id: 'assist-progress' });
    var btn = el('button', { class: 'btn btn-sm', id: 'assist-install', onclick: async function () {
      btn.disabled = true;
      try {
        await API.post('/api/assist/install');
        poll();
      } catch (e) {
        status.textContent = 'Could not start: ' + e.message;
        btn.disabled = false;
      }
    } }, 'Download & enable (~' + mb + ' MB)');
    async function poll() {
      try {
        var st = await API.getFresh('/api/assist/status');
        if (st.installed) {
          state.status = 'unknown'; // re-detect on next render
          status.textContent = 'Installed. Reloading this screen…';
          App.render();
          return;
        }
        if (st.phase === 'error') {
          status.textContent = 'Install failed: ' + (st.error || 'unknown') + ' — nothing was enabled.';
          btn.disabled = false;
          return;
        }
        status.textContent = 'Downloading ' + (st.currentFile || '…') + '  ('
          + Math.round(st.bytesDone / 1e6) + ' / ' + Math.round(st.bytesTotal / 1e6) + ' MB)';
        setTimeout(poll, 800);
      } catch (e) {
        setTimeout(poll, 1500);
      }
    }
    return el('div', { class: 'card assist-card', id: 'assist-enable' },
      el('div', { class: 'assist-head' },
        el('b', {}, 'On-device AI is available'),
        el('span', { class: 'badge badge-dim' }, 'OPTIONAL')),
      el('p', { class: 'muted', style: 'margin:0 0 8px' },
        'One download enables “say it in your own words” idea intake and on-device headline sentiment. '
        + 'Everything runs in YOUR browser (WebGPU or WASM); nothing you type ever leaves this machine, and the engine’s numbers are never model-made. '
        + 'Assets: transformers.js (Apache-2.0), ONNX Runtime (MIT), nli-deberta-v3-xsmall (Apache-2.0) — checksum-verified.'),
      el('div', { class: 'btn-row', style: 'margin-top:0' }, btn),
      status);
  }

  /** The intake card on the Ideas screen. Fills the form; NEVER submits anything itself. */
  async function intakeCard(applyFn) {
    var st = await detect();
    if (st === 'installable') return enableCard();
    if (st !== 'available') return null;
    var input = el('textarea', { id: 'assist-text', rows: '2',
      placeholder: 'e.g. “I think TSLA drops after earnings but I don’t want to lose more than $400”' });
    var chips = el('div', { class: 'chip-row', id: 'assist-chips' });
    var status = el('span', { class: 'muted assist-note' },
      'Understands your words on your device — nothing leaves this machine. You confirm before anything runs.');
    var parsed = null;
    var applyBtn = el('button', { class: 'btn btn-sm', id: 'assist-apply', disabled: '' , onclick: function () {
      if (parsed) applyFn(parsed);
    } }, 'Fill the form with this');
    var parseBtn = el('button', { class: 'btn btn-sm btn-secondary', id: 'assist-parse', onclick: async function () {
      var text = input.value.trim();
      if (!text) return;
      parseBtn.disabled = true;
      chips.innerHTML = '';
      chips.appendChild(UI.spinner('Reading (on-device)…'));
      try {
        parsed = await parseIdea(text);
        chips.innerHTML = '';
        var g = (window.Learn && Learn.INTENTS.find(function (i) { return i.key === parsed.goal; })) || null;
        var gLabel = g ? g.label : parsed.goal;
        if (parsed.goalRule) {
          chips.appendChild(UI.chip('Goal', gLabel + ' — your words: “' + parsed.goalRule + '”'));
        } else if (parsed.goalUncertain) {
          chips.appendChild(UI.chip('Goal', 'not sure — best guess ' + gLabel + ' ('
            + Math.round(parsed.goalScore * 100) + '%), pick the goal yourself'));
        } else {
          chips.appendChild(UI.chip('Goal', gLabel
            + ' · ' + Math.round(parsed.goalScore * 100) + '%'
            + (parsed.goalAdjusted ? ' (leaned directional — check me)' : '')));
        }
        if (parsed.thesis && (parsed.goal === 'DIRECTIONAL' || parsed.thesisScore >= 0.5)) {
          chips.appendChild(UI.chip('View', parsed.thesis + ' · ' + Math.round(parsed.thesisScore * 100) + '%'));
        }
        if (parsed.symbol) chips.appendChild(UI.chip('Symbol', parsed.symbol));
        if (parsed.maxLoss) chips.appendChild(UI.chip('Max loss (your words)', UI.fmtMoney(Math.round(parsed.maxLoss * 100))));
        if (parsed.horizon) chips.appendChild(UI.chip('Horizon', parsed.horizon));
        applyBtn.disabled = false;
      } catch (e) {
        chips.innerHTML = '';
        chips.appendChild(UI.alertBox('warn', 'Could not read that on-device', [e.message]));
      } finally {
        parseBtn.disabled = false;
      }
    } }, 'Understand it');
    return el('div', { class: 'card assist-card', id: 'assist-intake' },
      el('div', { class: 'assist-head' },
        el('b', {}, 'Say it in your own words'),
        el('span', { class: 'badge badge-dim' }, 'ON-DEVICE AI')),
      input,
      el('div', { class: 'btn-row', style: 'margin-top:8px' }, parseBtn, applyBtn),
      chips, status);
  }

  window.Assist = { detect: detect, enhanceNews: enhanceNews, intakeCard: intakeCard, parseIdea: parseIdea, state: state };
})();
