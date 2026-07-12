/* StrikeBench Scenario Studio — the shared "imagine a future" surface.
 * One module, two altitudes:
 *   Beginner: story cards ("Drops, then recovers"), plain magnitude chips with live ±% previews,
 *             one button, sentence verdicts in dollars. No Greek letters, no model names.
 *   Expert:   the quant terminal — model select (GBM/bridge/bootstrap/Student-t/jump/Heston),
 *             numeric drift/vol/jump/tail/Heston params, the IV path (start/drift/revert/event
 *             crush), seed + paths, expandable "The math" cards with the actual equations.
 * Used by Research ("What could happen next?"), Trade→Verify ("Imagine a future"), and the
 * Data Center (generate a synthetic dataset). Everything is honestly labeled MODELED.
 */
(function () {
  'use strict';
  var el = UI.el;

  // ---- Scenario stories (Beginner cards ⇄ spec presets) ----
  var SHAPES = [
    { key: 'GRIND_UP', label: 'Climbs steadily', pts: '0,26 50,15 100,5', drift: 0.4, model: 'GBM',
      blurb: 'A patient uptrend with normal day-to-day wiggle.' },
    { key: 'GRIND_DOWN', label: 'Drifts lower', pts: '0,6 50,16 100,27', drift: -0.4, model: 'GBM',
      blurb: 'A persistent decline with normal day-to-day wiggle.' },
    { key: 'SELLOFF_REBOUND', label: 'Drops, then recovers', pts: '0,10 45,28 100,7', drift: 0.0, model: 'GBM',
      blurb: 'A slide, a bottom, and a climb back — the round trip.' },
    { key: 'RALLY_FADE', label: 'Rises, then fades', pts: '0,25 45,6 100,23', drift: 0.0, model: 'GBM',
      blurb: 'An early pop that gives most of it back.' },
    { key: 'CHOP', label: 'Sideways chop', pts: '0,17 15,11 30,21 45,11 62,21 78,12 100,17', drift: 0.0, model: 'GBM',
      blurb: 'No real direction — just noise around today’s price.' },
    { key: 'GAP_DOWN', label: 'Gaps down', pts: '0,10 44,10 48,24 100,20', drift: -0.25, model: 'JUMP_DIFFUSION',
      blurb: 'A sudden drop (bad news), then drifting.' },
    { key: 'GAP_UP', label: 'Gaps up', pts: '0,22 44,22 48,8 100,10', drift: 0.25, model: 'JUMP_DIFFUSION',
      blurb: 'A sudden jump (good news), then drifting.' },
    { key: 'EVENT_JUMP', label: 'Big news shock', pts: '0,16 46,16 50,5 54,27 100,15', drift: 0.0, model: 'JUMP_DIFFUSION',
      blurb: 'Earnings-style: a violent move either way, then IV deflates.' }
  ];
  var HORIZONS = [{ d: 5, label: '1 week' }, { d: 10, label: '2 weeks' }, { d: 21, label: '1 month' }, { d: 63, label: '3 months' }];
  // Wildness is a MULTIPLIER of the symbol's own recent volatility (calibrated per ticker at
  // render time) — 'Typical' means typical FOR THIS STOCK, not a canned 30%.
  var MAGS = [{ key: 'calm', label: 'Calm', mult: 0.6 }, { key: 'typical', label: 'Typical', mult: 1.0 }, { key: 'wild', label: 'Wild', mult: 2.0 }];
  var MODELS = [
    { v: 'GBM', label: 'GBM (lognormal)' }, { v: 'BROWNIAN_BRIDGE', label: 'Brownian bridge (pinned end)' },
    { v: 'BLOCK_BOOTSTRAP', label: 'Block bootstrap (real returns)' }, { v: 'STUDENT_T', label: 'Student-t (bounded fat tails)' },
    { v: 'JUMP_DIFFUSION', label: 'Merton jump-diffusion' }, { v: 'HESTON', label: 'Heston (stochastic vol)' }
  ];

  function sketch(pts) {
    return el('span', { class: 'sc-sketch', html:
      '<svg viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true">'
      + '<line x1="0" y1="16" x2="100" y2="16" stroke="var(--border)" stroke-width="1" stroke-dasharray="3 3"/>'
      + '<polyline points="' + pts + '" fill="none" stroke="var(--accent)" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>' });
  }

  function shapeOf(key) { return SHAPES.find(function (s) { return s.key === key; }) || SHAPES[4]; }

  /** The symbol's own recent realized volatility, shared by every scenario surface. */
  function historicalVol(symbol) {
    if (!symbol) return Promise.resolve(null);
    return API.get('/api/research/' + symbol + '/history?range=6m').then(function (h) {
      var cs = h.candles || h.series || [];
      var closes = cs.map(function (c) { return parseFloat(c.close != null ? c.close : c.value); })
        .filter(function (v) { return isFinite(v) && v > 0; });
      if (closes.length < 30) return null;
      var sum = 0, sum2 = 0, n = 0;
      for (var i = 1; i < closes.length; i++) {
        var r = Math.log(closes[i] / closes[i - 1]);
        sum += r; sum2 += r * r; n++;
      }
      var sd = Math.sqrt(Math.max(0, sum2 / n - (sum / n) * (sum / n)));
      var hv = sd * Math.sqrt(252);
      return isFinite(hv) && hv > 0.03 && hv < 3 ? hv : null;
    }).catch(function () { return null; });
  }

  /* ---------------------------------------------------------------------------------------------
   * The scenario form. opts: {compact} → returns {el, getSpec(), getIv(), describe(), onchange}
   * State persists in App.state.scenarioForm so level flips / navigation never lose the setup.
   * ------------------------------------------------------------------------------------------- */
  function form(level, symbol, seedContext) {
    seedContext = seedContext || {};
    var f = App.state.scenarioForm = App.state.scenarioForm || {};
    if (f.seed == null) f.seed = Math.floor(Math.random() * 1000000);
    // A fresh studio opens on the user's WORKING VIEW — the thesis they picked in Ideas seeds
    // the story (bullish → climbs, bearish → fades, volatile → news shock). Pure default: the
    // user can pick any other shape, and a persisted choice always wins.
    if (!f.shape) {
      var thesis = seedContext.thesis || (App.context && App.context.thesis()) || '';
      f.shape = { bullish: 'GRIND_UP', bearish: 'RALLY_FADE', neutral: 'CHOP', volatile: 'EVENT_JUMP' }[thesis]
             || 'SELLOFF_REBOUND';
    }
    f.horizon = f.horizon || seedContext.horizonDays || 10;
    f.mag = f.mag || 'typical';
    var box = el('div', { class: 'scenario-form' });
    var onchange = function () {};
    var expertInputs = {};

    function magPct(vol, days) { return Math.round(vol * Math.sqrt(days / 252) * 100); }

    // Per-ticker calibration: annualized HV from the symbol's (usually prefetched) history.
    // While unknown, magVolFor returns 0 — the server-side sentinel for "use market vol"
    // (the chain's ATM IV) — so no symbol ever gets a hardcoded 30%.
    var hvBase = { v: null };
    function magVolFor(key) {
      var m2 = MAGS.find(function (x) { return x.key === key; }) || MAGS[1];
      return hvBase.v ? m2.mult * hvBase.v : 0;
    }
    function magVolForDisplay(key) {
      var m2 = MAGS.find(function (x) { return x.key === key; }) || MAGS[1];
      return m2.mult * (hvBase.v || 0.30); // display estimate only, until HV lands
    }
    if (symbol) historicalVol(symbol).then(function (hv) {
      if (hv) { hvBase.v = hv; if (box._sync) box._sync(); onchange(); }
    });

    if (level === 'beginner') {
      // 1) The story — what do you think happens?
      box.appendChild(el('div', { class: 'field-label' }, 'What do you think ' + (symbol || 'it') + ' does?'));
      var grid = el('div', { class: 'q-grid sc-grid', id: 'sc-shapes' });
      SHAPES.forEach(function (s) {
        grid.appendChild(el('button', { type: 'button', 'data-shape': s.key,
          class: 'q-card sc-card' + (f.shape === s.key ? ' active' : ''),
          onclick: function () { f.shape = s.key; grid.querySelectorAll('.sc-card').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-shape') === s.key); }); sync(); } },
          sketch(s.pts), el('b', {}, s.label), el('div', { class: 'muted small' }, s.blurb)));
      });
      box.appendChild(grid);
      // 2) Over how long, and how wild?
      var row = el('div', { class: 'form-grid' });
      var hzWrap = el('div', { class: 'chip-row', id: 'sc-horizon' });
      HORIZONS.forEach(function (h) {
        hzWrap.appendChild(el('button', { type: 'button', class: 'sym-chip' + (f.horizon === h.d ? ' active' : ''), 'data-days': h.d,
          onclick: function () { f.horizon = h.d; hzWrap.querySelectorAll('.sym-chip').forEach(function (b) { b.classList.toggle('active', +b.getAttribute('data-days') === h.d); }); sync(); } }, h.label));
      });
      var magWrap = el('div', { class: 'chip-row', id: 'sc-mag' });
      var magNote = el('div', { class: 'muted small', id: 'sc-mag-note' });
      MAGS.forEach(function (m) {
        magWrap.appendChild(el('button', { type: 'button', class: 'sym-chip' + (f.mag === m.key ? ' active' : ''), 'data-mag': m.key,
          onclick: function () { f.mag = m.key; magWrap.querySelectorAll('.sym-chip').forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-mag') === m.key); }); sync(); } }, m.label));
      });
      row.appendChild(el('div', { class: 'field' }, el('label', {}, 'Over the next'), hzWrap));
      row.appendChild(el('div', { class: 'field' }, el('label', {}, 'How wild could it get?'), magWrap, magNote));
      box.appendChild(row);
      box.appendChild(UI.expandable('How this works', function () {
        return el('div', {},
          el('p', {}, 'We draw hundreds of made-up-but-realistic price paths that follow the story you picked, sized by the wildness you chose. It is a model of what COULD happen — never a forecast.'),
          el('p', {}, 'Option prices along each path come from a standard pricing model, so time decay and volatility changes are included. Every run is labeled as simulated.'));
      }));
      function sync() {
        magNote.textContent = 'Typically within ±' + magPct(magVolForDisplay(f.mag), f.horizon) + '% by the end'
          + (hvBase.v ? ' — scaled to ' + (symbol || 'this stock') + '\u2019s own recent volatility.'
                      : ' — calibrating to ' + (symbol || 'this stock') + '\u2019s real volatility\u2026');
        onchange();
      }
      box._sync = sync;
      sync();
    } else {
      // Expert terminal — everything inline, math on demand.
      var model = sel('sc-model', MODELS, f.model || shapeOf(f.shape).model, function (v) { f.model = v; onchange(); });
      var shapeSel = sel('sc-shape', SHAPES.map(function (s) { return { v: s.key, label: s.label }; }), f.shape, function (v) { f.shape = v; onchange(); });
      var horizon = num('sc-days', f.horizon, 1, 756, function (v) { f.horizon = v; onchange(); });
      var vol = num('sc-vol', f.vol != null ? f.vol : 30, 1, 500, function (v) { f.vol = v; onchange(); });
      var drift = num('sc-drift', f.drift != null ? f.drift : Math.round(shapeOf(f.shape).drift * 100), -200, 200, function (v) { f.drift = v; onchange(); });
      var jumps = num('sc-jumps', f.jumps != null ? f.jumps : 0, 0, 260, function (v) { f.jumps = v; onchange(); });
      var jumpSize = num('sc-jumpsize', f.jumpSize != null ? f.jumpSize : -4, -50, 50, function (v) { f.jumpSize = v; onchange(); });
      var tailNu = num('sc-nu', f.nu != null ? f.nu : 6, 2.5, 200, function (v) { f.nu = v; onchange(); });
      var seed = num('sc-seed', f.seed, 0, 99999999, function (v) { f.seed = v; onchange(); });
      var paths = num('sc-paths', f.paths || 300, 20, 2000, function (v) { f.paths = v; onchange(); });
      var ivStart = num('sc-iv', f.ivStart != null ? f.ivStart : 30, 3, 400, function (v) { f.ivStart = v; onchange(); });
      var ivEvent = num('sc-ivday', f.ivEventDay != null ? f.ivEventDay : -1, -1, 756, function (v) { f.ivEventDay = v; onchange(); });
      var ivShock = num('sc-ivshock', f.ivShock != null ? f.ivShock : -35, -90, 300, function (v) { f.ivShock = v; onchange(); });
      var spd = num('sc-spd', f.spd || 4, 1, 16, function (v) { f.spd = v; onchange(); });
      var hKappa = num('sc-hkappa', f.hKappa != null ? f.hKappa : 3, 0.1, 20, function (v) { f.hKappa = v; onchange(); });
      var hXi = num('sc-hxi', f.hXi != null ? f.hXi : 0.5, 0.01, 3, function (v) { f.hXi = v; onchange(); });
      var hRho = num('sc-hrho', f.hRho != null ? f.hRho : -0.6, -0.99, 0.99, function (v) { f.hRho = v; onchange(); });
      expertInputs = { model: model, seed: seed };
      box.appendChild(el('div', { class: 'form-grid compact-filters' },
        fld('Model', model), fld('Shape guide', shapeSel), fld('Days', horizon), fld('Steps/day', spd),
        fld('Vol σ %/yr', vol), fld('Drift μ %/yr', drift),
        fld('Jumps /yr', jumps), fld('Jump size %', jumpSize), fld('Tail ν', tailNu)));
      box.appendChild(el('div', { class: 'form-grid compact-filters' },
        fld('Heston κ', hKappa), fld('Heston ξ', hXi), fld('Heston ρ', hRho),
        fld('IV start %', ivStart), fld('Event day', ivEvent), fld('IV change %', ivShock),
        fld('Seed', seed), fld('Paths', paths)));
      ivEvent.title = 'Trading day of the IV event (earnings-style shock). \u22121 = no event.';
      ivShock.title = 'One-off IV change applied at the event day\u2019s close (e.g. \u221235 = a 35% crush).';
      // Only the selected model's knobs are live — a control that silently does nothing teaches
      // the wrong lesson. Irrelevant fields disable with an honest tooltip.
      function updateRelevance() {
        var m = model.value;
        var offNote = 'Not used by ' + (MODELS.find(function (x) { return x.v === m; }) || { label: m }).label;
        [[jumps, m === 'JUMP_DIFFUSION'], [jumpSize, m === 'JUMP_DIFFUSION'],
         [tailNu, m === 'STUDENT_T'],
         [hKappa, m === 'HESTON'], [hXi, m === 'HESTON'], [hRho, m === 'HESTON'],
         [vol, m !== 'HESTON']].forEach(function (pair) {
          pair[0].disabled = !pair[1];
          pair[0].title = pair[1] ? '' : offNote;
        });
      }
      model.addEventListener('change', updateRelevance);
      updateRelevance();
      box.appendChild(UI.expandable('The math', function () {
        return el('div', { class: 'sc-math' },
          el('p', {}, el('b', {}, 'GBM / Student-t: '), 'Gaussian GBM uses the usual σ²/2 correction. Student-t shocks use the selected non-integer ν, are capped at ±8 standardized deviations, re-scaled, and use that bounded law’s exponential compensator so the guide remains the expected price path.'),
          el('p', {}, el('b', {}, 'Jump-diffusion (Merton): '), 'adds Σ Jᵢ per step, N ~ Poisson(λdt), J ~ N(m, s²).'),
          el('p', {}, el('b', {}, 'Heston: '), 'dv = κ(θ − v)dt + ξ√v·dWᵥ, corr(dWₛ, dWᵥ) = ρ; full-truncation Euler.'),
          el('p', {}, el('b', {}, 'Bootstrap: '), 'resamples blocks of the symbol’s own observed daily returns (mean-removed), preserving fat tails and autocorrelation; an empirical block-prefix compensator keeps the guide mean-honest.'),
          el('p', {}, el('b', {}, 'Shape guide: '), 'a deterministic log-drift curve (valley/mountain/linear) the noise rides on; the bridge pins the endpoint.'),
          el('p', {}, el('b', {}, 'IV path: '), 'deterministic: dIV = drift·dt + κ(long-run − IV)dt, with a one-off shock at the event day’s close. Option values are BSM on this path — always labeled MODELED.'),
          el('p', { class: 'muted small' }, 'Same seed ⇒ byte-identical paths. Each result names whether entry is quoted or modeled and includes configured round-trip commissions; future exit spread and early assignment are not modeled.'));
      }));
    }

    function sel(id, items, val, on) {
      var s = el('select', { id: id }, items.map(function (i) { return el('option', { value: i.v }, i.label); }));
      s.value = val; s.addEventListener('change', function () { on(s.value); });
      return s;
    }
    function num(id, val, min, max, on) {
      var i = el('input', { type: 'number', id: id, value: val, min: String(min), max: String(max), step: 'any' });
      i.addEventListener('change', function () { on(+i.value); });
      return i;
    }
    function fld(label, input) {
      // Greek glyphs must survive the app-wide uppercase label transform (σ became Σ — the
      // summation sign — which a quant reads as a bug). Wrap them in a no-transform span.
      var labelEl = el('label', {});
      label.split(/([σμκξρν])/).forEach(function (part) {
        if (/^[σμκξρν]$/.test(part)) labelEl.appendChild(el('span', { class: 'glyph' }, part));
        else if (part) labelEl.appendChild(document.createTextNode(part));
      });
      return el('div', { class: 'field' }, labelEl, input);
    }

    function getSpec() {
      var s = shapeOf(f.shape);
      if (level === 'beginner') {
        var jumpy = s.model === 'JUMP_DIFFUSION';
        // 4 steps/day: real Brownian squiggle instead of connect-the-dots line segments.
      return { model: s.model, shape: f.shape, horizonDays: f.horizon, stepsPerDay: 4,
          driftAnnual: s.drift, volAnnual: magVolFor(f.mag), jumpsPerYear: jumpy ? 8 : 0,
          jumpMean: f.shape === 'GAP_DOWN' ? -0.05 : (f.shape === 'GAP_UP' ? 0.05 : 0),
          jumpVol: jumpy ? 0.04 : 0, tailNu: 6, heston: null, seed: f.seed, paths: 200 };
      }
      var vol = (f.vol != null ? f.vol : 30) / 100;
      var wantHeston = (f.model || s.model) === 'HESTON';
      return { model: f.model || s.model, shape: f.shape, horizonDays: f.horizon,
        stepsPerDay: f.spd || 4,
        driftAnnual: (f.drift != null ? f.drift : s.drift * 100) / 100, volAnnual: vol,
        jumpsPerYear: f.jumps || 0, jumpMean: (f.jumpSize || 0) / 100, jumpVol: Math.abs((f.jumpSize || 0) / 100) * 0.5,
        tailNu: f.nu || 6,
        heston: wantHeston ? { kappa: f.hKappa != null ? f.hKappa : 3, theta: vol * vol,
          xi: f.hXi != null ? f.hXi : Math.max(0.05, vol * 0.5), rho: f.hRho != null ? f.hRho : -0.6, v0: vol * vol } : null,
        seed: f.seed, paths: f.paths || 300 };
    }

    function getIv() {
      if (level === 'beginner') {
        if (f.shape === 'EVENT_JUMP') { // earnings-style: IV rich into the event, crushed after
          var mv = magVolForDisplay(f.mag); // IV SHAPE needs a level; the crush profile matters more than its exact base
          return { startIv: mv * 1.4, driftPerYear: 0, meanRevertSpeed: 1.5, longRunIv: mv,
            eventDay: Math.max(1, Math.round(f.horizon / 3)), eventShockPct: -0.35, minIv: 0.03, maxIv: 4 };
        }
        // null on purpose: the server prices the IV path off the REAL chain's ATM IV, so the
        // verdict measures the user's scenario against the market's actual option prices.
        return null;
      }
      return { startIv: (f.ivStart != null ? f.ivStart : 30) / 100, driftPerYear: 0, meanRevertSpeed: 0.5,
        longRunIv: (f.ivStart != null ? f.ivStart : 30) / 100,
        eventDay: f.ivEventDay != null ? f.ivEventDay : -1, eventShockPct: (f.ivShock != null ? f.ivShock : -35) / 100,
        minIv: 0.03, maxIv: 4 };
    }

    function describe() {
      var s = shapeOf(f.shape);
      var hz = HORIZONS.find(function (h) { return h.d === f.horizon; });
      return s.label + ' over ' + (hz ? hz.label : f.horizon + ' days');
    }

    function reroll() { f.seed = Math.floor(Math.random() * 1000000); if (expertInputs.seed) expertInputs.seed.value = f.seed; }

    return { el: box, getSpec: getSpec, getIv: getIv, describe: describe, reroll: reroll,
      setOnChange: function (fn) { onchange = fn; } };
  }

  /* ---- The fan chart: p10–p90 band + median + sample futures, in price space ---- */
  function fanChart(p, opts) {
    opts = opts || {};
    var W = 640, H = 220, padL = 48, padR = 12, padT = 12, padB = 22;
    var days = p.bands.length - 1;
    var lo = Infinity, hi = -Infinity;
    p.bands.forEach(function (b) { lo = Math.min(lo, b.p10); hi = Math.max(hi, b.p90); });
    (p.samples || []).forEach(function (s) { s.forEach(function (v) { lo = Math.min(lo, v); hi = Math.max(hi, v); }); });
    var span = Math.max(hi - lo, 0.01); lo -= span * 0.06; hi += span * 0.06;
    function x(day) { return padL + (W - padL - padR) * day / Math.max(1, days); }
    function y(v) { return padT + (H - padT - padB) * (1 - (v - lo) / (hi - lo)); }
    var band = 'M' + p.bands.map(function (b) { return x(b.day).toFixed(1) + ',' + y(b.p90).toFixed(1); }).join(' L')
      + ' L' + p.bands.slice().reverse().map(function (b) { return x(b.day).toFixed(1) + ',' + y(b.p10).toFixed(1); }).join(' L') + ' Z';
    var median = p.bands.map(function (b, i) { return (i ? 'L' : 'M') + x(b.day).toFixed(1) + ',' + y(b.p50).toFixed(1); }).join(' ');
    // Sample paths arrive at FULL step resolution (intraday) — map each by its own length so
    // the squiggle spans the same time axis as the daily bands.
    var samples = (p.samples || []).map(function (s, si) {
      var n = Math.max(1, s.length - 1);
      var d = s.map(function (v, i) {
        var px = padL + (W - padL - padR) * i / n;
        return (i ? 'L' : 'M') + px.toFixed(1) + ',' + y(v).toFixed(1);
      }).join(' ');
      // Two strokes per sample: a wide invisible hit target (a 1px squiggle is unclickable) and
      // the visible line — click a path to inspect that ONE future.
      return '<g class="fan-sample" data-sample="' + si + '">'
        + '<path d="' + d + '" fill="none" stroke="transparent" stroke-width="10" pointer-events="stroke"/>'
        + '<path class="fan-sample-line" d="' + d + '" fill="none" stroke="var(--text-faint)" stroke-width="1" opacity="0.55"/>'
        + '</g>';
    }).join('');
    var gridY = [0.25, 0.5, 0.75].map(function (f2) {
      var v = lo + (hi - lo) * f2;
      return '<line x1="' + padL + '" y1="' + y(v).toFixed(1) + '" x2="' + (W - padR) + '" y2="' + y(v).toFixed(1) + '" stroke="var(--border)" stroke-width="0.6"/>'
        + '<text x="' + (padL - 6) + '" y="' + (y(v) + 3.5).toFixed(1) + '" text-anchor="end" font-size="10" fill="var(--text-dim)">' + v.toFixed(v > 100 ? 0 : 2) + '</text>';
    }).join('');
    var spotLine = '<line x1="' + padL + '" y1="' + y(p.spot).toFixed(1) + '" x2="' + (W - padR) + '" y2="' + y(p.spot).toFixed(1)
      + '" stroke="var(--text)" stroke-width="0.8" stroke-dasharray="4 4" opacity="0.55"/>'
      + '<text x="' + (W - padR) + '" y="' + (y(p.spot) - 4).toFixed(1) + '" text-anchor="end" font-size="10" fill="var(--text-dim)">now ' + p.spot + '</text>';
    var svg = '<svg viewBox="0 0 ' + W + ' ' + H + '" width="100%" role="img" aria-label="possible futures">'
      + gridY
      + '<path d="' + band + '" fill="var(--accent)" opacity="0.14"/>'
      + samples
      + '<path d="' + median + '" fill="none" stroke="var(--accent)" stroke-width="2.2"/>'
      + spotLine
      + '<text x="' + padL + '" y="' + (H - 6) + '" font-size="10" fill="var(--text-dim)">today</text>'
      + '<text x="' + (W - padR) + '" y="' + (H - 6) + '" text-anchor="end" font-size="10" fill="var(--text-dim)">+' + days + 'd</text>'
      + '</svg>';
    var wrap = el('div', { class: 'fan-chart' }, el('div', { html: svg }));
    // Hover readout: day + the p10/median/p90 at that day (the chart must explain itself).
    (function wireHover() {
      var host = wrap.firstChild;
      var tip = el('div', { class: 'sc-tip', style: 'display:none' });
      wrap.style.position = 'relative';
      wrap.appendChild(tip);
      host.addEventListener('pointermove', function (ev) {
        var svgEl = host.querySelector('svg');
        if (!svgEl) return;
        var r = svgEl.getBoundingClientRect();
        var fx = (ev.clientX - r.left) / Math.max(1, r.width) * W;
        var day = Math.round((fx - padL) / Math.max(1, W - padL - padR) * days);
        if (day < 0 || day > days) { tip.style.display = 'none'; return; }
        var b = p.bands[day];
        tip.innerHTML = '<b>day ' + b.day + '</b> · low ' + b.p10 + ' · mid ' + b.p50 + ' · high ' + b.p90;
        tip.style.display = '';
        var left = Math.min(Math.max(0, ev.clientX - r.left + 10), r.width - 170);
        tip.style.left = left + 'px';
        tip.style.top = Math.max(0, ev.clientY - r.top - 34) + 'px';
      });
      host.addEventListener('pointerleave', function () { tip.style.display = 'none'; });
    })();
    // Click a sample squiggle to inspect that single future: highlight + where it ended/travelled.
    (function wireSampleInspect() {
      var readout = null;
      wrap.firstChild.addEventListener('click', function (ev) {
        var g = ev.target && ev.target.closest ? ev.target.closest('g.fan-sample') : null;
        wrap.firstChild.querySelectorAll('g.fan-sample.selected').forEach(function (x) { x.classList.remove('selected'); });
        if (readout) { readout.remove(); readout = null; }
        if (!g) return;
        g.classList.add('selected');
        var si = parseInt(g.getAttribute('data-sample'), 10);
        var s = (p.samples || [])[si];
        if (!s || !s.length) return;
        var end = s[s.length - 1], mn = Math.min.apply(null, s), mx = Math.max.apply(null, s);
        var pctNum = (end / p.spot - 1) * 100;
        readout = el('div', { class: 'muted small fan-path-readout' },
          'This future: ends at ' + end.toFixed(2) + ' (' + (pctNum >= 0 ? '+' : '') + pctNum.toFixed(1)
          + '%), travelled ' + mn.toFixed(2) + ' \u2013 ' + mx.toFixed(2) + ' along the way. Click the chart background to clear.');
        wrap.appendChild(readout);
      });
    })();
    var pctDown = Math.round((p.endP10 / p.spot - 1) * 100), pctUp = Math.round((p.endP90 / p.spot - 1) * 100);
    wrap.appendChild(el('div', { class: 'chip-row chart-summary' },
      UI.chip('Futures drawn', String(p.paths)),
      UI.chip('Middle path ends', String(p.endP50)),
      UI.chip('8 in 10 end between', p.endP10 + ' and ' + p.endP90),
      UI.chip('That’s', (pctDown >= 0 ? '+' : '') + pctDown + '% to ' + (pctUp >= 0 ? '+' : '') + pctUp + '%')));
    if (!opts.noNotes) (p.notes || []).forEach(function (n) { wrap.appendChild(el('div', { class: 'muted small' }, n)); });
    return wrap;
  }

  /* ---- The strategy verdict: distribution → plain sentences (beginner) or stats (expert) ---- */
  function pnlView(r, level) {
    var out = el('div', {});
    var winPct = Math.round(r.winRatePct);
    var kind = winPct >= 55 && r.expectedPnlCents > 0 ? 'ok' : (winPct <= 45 || r.expectedPnlCents < 0 ? 'danger' : 'caution');
    var verdict = level === 'beginner'
      ? 'In ' + winPct + ' of 100 futures like this, the trade made money. Typical outcome ' + UI.fmtMoneyCompact(r.p50Cents)
        + '; a bad run ' + UI.fmtMoneyCompact(r.p5Cents) + '; a great one ' + UI.fmtMoneyCompact(r.p95Cents) + '.'
      : 'Win rate ' + winPct + '% · E[P&L] ' + UI.fmtMoneyCompact(r.expectedPnlCents)
        + ' · p5 ' + UI.fmtMoneyCompact(r.p5Cents) + ' · p50 ' + UI.fmtMoneyCompact(r.p50Cents)
        + ' · p95 ' + UI.fmtMoneyCompact(r.p95Cents) + ' over ' + r.paths + ' paths.';
    out.appendChild(UI.alertBox(kind, verdict));
    out.appendChild(el('div', { class: 'grid grid-4' },
      UI.stat('Chance of profit', winPct + '%', level === 'beginner' ? 'Share of simulated futures that ended green.' : null),
      UI.stat('Typical outcome', UI.pnlSpan(r.p50Cents)),
      UI.stat('Bad run (1 in 20)', UI.pnlSpan(r.p5Cents)),
      UI.stat('Great run (1 in 20)', UI.pnlSpan(r.p95Cents))));

    // P&L fan over time (p10–p90 band + median), in dollars.
    if (r.bands && r.bands.length > 1) {
      var W = 640, H = 170, padL = 56, padR = 12, padT = 10, padB = 20;
      var days = r.bands.length - 1, lo = Infinity, hi = -Infinity;
      r.bands.forEach(function (b) { lo = Math.min(lo, b.p10Cents); hi = Math.max(hi, b.p90Cents); });
      var span = Math.max(hi - lo, 100); lo -= span * 0.08; hi += span * 0.08;
      var x = function (d) { return padL + (W - padL - padR) * d / days; };
      var y = function (v) { return padT + (H - padT - padB) * (1 - (v - lo) / (hi - lo)); };
      var band = 'M' + r.bands.map(function (b) { return x(b.day).toFixed(1) + ',' + y(b.p90Cents).toFixed(1); }).join(' L')
        + ' L' + r.bands.slice().reverse().map(function (b) { return x(b.day).toFixed(1) + ',' + y(b.p10Cents).toFixed(1); }).join(' L') + ' Z';
      var med = r.bands.map(function (b, i) { return (i ? 'L' : 'M') + x(b.day).toFixed(1) + ',' + y(b.p50Cents).toFixed(1); }).join(' ');
      var zero = '<line x1="' + padL + '" y1="' + y(0).toFixed(1) + '" x2="' + (W - padR) + '" y2="' + y(0).toFixed(1) + '" stroke="var(--text)" stroke-width="0.8" stroke-dasharray="4 4" opacity="0.5"/>';
      var labels = [lo + (hi - lo) * 0.15, 0, hi - (hi - lo) * 0.15].map(function (v) {
        return '<text x="' + (padL - 6) + '" y="' + (y(v) + 3.5).toFixed(1) + '" text-anchor="end" font-size="10" fill="var(--text-dim)">' + UI.fmtMoneyCompact(Math.round(v)) + '</text>';
      }).join('');
      out.appendChild(el('div', { class: 'fan-chart', html:
        '<svg viewBox="0 0 ' + W + ' ' + H + '" width="100%" role="img" aria-label="P&L over time">'
        + labels + '<path d="' + band + '" fill="var(--accent)" opacity="0.14"/>' + zero
        + '<path d="' + med + '" fill="none" stroke="var(--accent)" stroke-width="2.2"/>'
        + '<text x="' + padL + '" y="' + (H - 5) + '" font-size="10" fill="var(--text-dim)">entry</text>'
        + '<text x="' + (W - padR) + '" y="' + (H - 5) + '" text-anchor="end" font-size="10" fill="var(--text-dim)">+' + days + 'd</text></svg>' }));
      out.appendChild(el('div', { class: 'muted small' },
        level === 'beginner' ? 'The shaded band is where 8 of 10 futures sat, day by day; the line is the middle path.'
          : 'p10–p90 band with the median path; day granularity.'));
    }

    // Terminal distribution histogram — with a REAL dollar axis (edges + a $0 marker), because
    // "green made money, red lost" cannot carry magnitude on its own.
    if (r.distribution && r.distribution.length) {
      var max = 1; r.distribution.forEach(function (b) { max = Math.max(max, b.count); });
      var n = r.distribution.length;
      var HW = 640, HH = 96, hPadL = 8, hPadR = 8, base = 68;
      var bw = (HW - hPadL - hPadR) / n;
      var loC = r.distribution[0].fromCents, hiC = r.distribution[n - 1].toCents;
      var bars = r.distribution.map(function (b, i) {
        var h = b.count / max * 54;
        var mid = (b.fromCents + b.toCents) / 2;
        var col = mid >= 0 ? 'var(--risk-ok-solid,#3aa76d)' : 'var(--risk-danger-solid,#d64545)';
        return '<rect x="' + (hPadL + i * bw + bw * 0.1).toFixed(1) + '" y="' + (base - h).toFixed(1) + '" width="' + (bw * 0.8).toFixed(1)
          + '" height="' + h.toFixed(1) + '" rx="2" fill="' + col + '"><title>' + UI.fmtMoneyCompact(b.fromCents) + ' to ' + UI.fmtMoneyCompact(b.toCents) + ': ' + b.count + ' futures</title></rect>';
      }).join('');
      var zeroX = hiC > loC ? hPadL + (0 - loC) / (hiC - loC) * (HW - hPadL - hPadR) : null;
      var zeroMark = zeroX != null && zeroX >= hPadL && zeroX <= HW - hPadR
        ? '<line x1="' + zeroX.toFixed(1) + '" y1="8" x2="' + zeroX.toFixed(1) + '" y2="' + base + '" stroke="var(--text)" stroke-width="0.9" stroke-dasharray="4 3" opacity="0.55"/>'
          + '<text x="' + zeroX.toFixed(1) + '" y="' + (base + 12) + '" text-anchor="middle" font-size="10" fill="var(--text-dim)">$0</text>'
        : '';
      out.appendChild(el('div', { class: 'tool-chart', html:
        '<svg viewBox="0 0 ' + HW + ' ' + HH + '" width="100%" role="img" aria-label="P&L distribution">'
        + '<line x1="' + hPadL + '" y1="' + base + '" x2="' + (HW - hPadR) + '" y2="' + base + '" stroke="var(--border)" stroke-width="1"/>'
        + bars + zeroMark
        + '<text x="' + hPadL + '" y="' + (base + 12) + '" font-size="10" fill="var(--text-dim)">' + UI.fmtMoneyCompact(loC) + '</text>'
        + '<text x="' + (HW - hPadR) + '" y="' + (base + 12) + '" text-anchor="end" font-size="10" fill="var(--text-dim)">' + UI.fmtMoneyCompact(hiC) + '</text>'
        + '</svg>' }));
      out.appendChild(el('div', { class: 'muted small' },
        level === 'beginner' ? 'Where the futures landed, in dollars: green bars made money, red lost; the dashed line is break-even.'
          : 'Terminal P&L distribution ($ axis; dashed = break-even).'));
    }
    (r.notes || []).forEach(function (n) { out.appendChild(el('div', { class: 'muted small' }, n)); });
    return out;
  }

  /* ---- The FULL position catalog for scenario testing — the same breadth as the builder's
   * "All strategies", each with its payoff-shape sketch (profit at expiry vs price: the visual
   * identity of the STRATEGY, distinct from the story cards' PRICE-PATH sketches). Strikes are
   * derived from spot on the exchange step grid; Expert can fine-tune via the working idea. */
  function L(action, type, strike, expiryDay) { return { action: action, type: type, strike: strike, expiryDay: expiryDay, ratio: 1 }; }
  function step(s) { return s < 25 ? 0.5 : s < 100 ? 1 : s < 300 ? 2.5 : 5; }
  function grid(v, s) { var st = step(s); return Math.round(v / st) * st; }
  // The server catalog owns every label, category, eligibility flag and payoff glyph.
  // This module retains only the scenario-specific strike/expiry construction mechanics.
  var SCENARIO_LEGS = {
    LONG_CALL: function (s, d) { return [L('BUY', 'CALL', grid(s, s), d)]; },
    DEBIT_CALL_SPREAD: function (s, d) { return [L('BUY', 'CALL', grid(s, s), d), L('SELL', 'CALL', grid(s * 1.05, s), d)]; },
    CREDIT_PUT_SPREAD: function (s, d) { return [L('SELL', 'PUT', grid(s * 0.97, s), d), L('BUY', 'PUT', grid(s * 0.92, s), d)]; },
    CASH_SECURED_PUT: function (s, d) { return [L('SELL', 'PUT', grid(s * 0.95, s), d)]; },
    LONG_PUT: function (s, d) { return [L('BUY', 'PUT', grid(s, s), d)]; },
    DEBIT_PUT_SPREAD: function (s, d) { return [L('BUY', 'PUT', grid(s, s), d), L('SELL', 'PUT', grid(s * 0.95, s), d)]; },
    CREDIT_CALL_SPREAD: function (s, d) { return [L('SELL', 'CALL', grid(s * 1.03, s), d), L('BUY', 'CALL', grid(s * 1.08, s), d)]; },
    IRON_CONDOR: function (s, d) { return [L('SELL', 'PUT', grid(s * 0.95, s), d), L('BUY', 'PUT', grid(s * 0.90, s), d), L('SELL', 'CALL', grid(s * 1.05, s), d), L('BUY', 'CALL', grid(s * 1.10, s), d)]; },
    IRON_BUTTERFLY: function (s, d) { return [L('SELL', 'PUT', grid(s, s), d), L('BUY', 'PUT', grid(s * 0.94, s), d), L('SELL', 'CALL', grid(s, s), d), L('BUY', 'CALL', grid(s * 1.06, s), d)]; },
    COVERED_CALL: function (s, d) { return [L('BUY', 'STOCK', 0, 0), L('SELL', 'CALL', grid(s * 1.05, s), d)]; },
    CALENDAR_CALL: function (s, d) { return [L('SELL', 'CALL', grid(s, s), Math.max(3, Math.round(d * 0.5))), L('BUY', 'CALL', grid(s, s), d + 21)]; },
    DIAGONAL_CALL: function (s, d) { return [L('SELL', 'CALL', grid(s * 1.04, s), Math.max(3, Math.round(d * 0.5))), L('BUY', 'CALL', grid(s, s), d + 21)]; },
    LONG_STRADDLE: function (s, d) { return [L('BUY', 'CALL', grid(s, s), d), L('BUY', 'PUT', grid(s, s), d)]; },
    LONG_STRANGLE: function (s, d) { return [L('BUY', 'CALL', grid(s * 1.04, s), d), L('BUY', 'PUT', grid(s * 0.96, s), d)]; },
    LONG_CALL_BUTTERFLY: function (s, d) { return [L('BUY', 'CALL', grid(s * 0.95, s), d), L('SELL', 'CALL', grid(s, s), d), L('SELL', 'CALL', grid(s, s), d), L('BUY', 'CALL', grid(s * 1.05, s), d)]; },
    LONG_PUT_BUTTERFLY: function (s, d) { return [L('BUY', 'PUT', grid(s * 1.05, s), d), L('SELL', 'PUT', grid(s, s), d), L('SELL', 'PUT', grid(s, s), d), L('BUY', 'PUT', grid(s * 0.95, s), d)]; },
    PROTECTIVE_PUT: function (s, d) { return [L('BUY', 'STOCK', 0, 0), L('BUY', 'PUT', grid(s * 0.95, s), d)]; },
    PROTECTIVE_COLLAR: function (s, d) { return [L('BUY', 'STOCK', 0, 0), L('BUY', 'PUT', grid(s * 0.95, s), d), L('SELL', 'CALL', grid(s * 1.05, s), d)]; }
  };
  var CATALOG = [];

  function payoffPoints(shape) {
    return String(shape || '').split(/\s+/).filter(Boolean).map(function (point) {
      var xy = point.split(',');
      return ((+xy[0]) * 100 / 64).toFixed(2) + ',' + xy[1];
    }).join(' ');
  }

  function applyCatalog(doc) {
    var entries = (doc && doc.catalog || []).filter(function (m) { return m.scenarioEnabled; });
    var byName = {};
    entries.forEach(function (m) { byName[m.name] = m; });
    var missingServer = Object.keys(SCENARIO_LEGS).filter(function (key) { return !byName[key]; });
    var missingMechanics = entries.filter(function (m) { return !SCENARIO_LEGS[m.name]; }).map(function (m) { return m.name; });
    if (missingServer.length || missingMechanics.length) {
      throw new Error('Strategy catalog/scenario mechanics mismatch: server missing [' + missingServer.join(', ')
        + ']; client missing [' + missingMechanics.join(', ') + ']');
    }
    CATALOG.splice(0, CATALOG.length);
    entries.forEach(function (m) {
      CATALOG.push({ key: m.name, group: m.category, label: m.display,
        pay: payoffPoints(m.payoffShape), summary: m.summary, legs: SCENARIO_LEGS[m.name] });
    });
  }
  /** Legs from the working idea (ticket candidate/custom), mapped to sim legs. Null if none. */
  function workingLegs() {
    var t = App.state.ticket;
    var legs = t && t.candidate ? t.candidate.legs : (t && t.custom ? t.custom.legs : null);
    if (!legs || !legs.length) return null;
    var out = [];
    for (var i = 0; i < legs.length; i++) {
      var lg = legs[i];
      if (lg.stock || lg.type === 'STOCK') { out.push({ action: lg.action, type: 'STOCK', strike: 0, expiryDay: 0, ratio: lg.ratio || 1 }); continue; }
      var days = 30;
      if (lg.expiration) {
        var ms = new Date(lg.expiration) - new Date();
        days = Math.max(1, Math.round(ms / 86400000 * 5 / 7)); // calendar → ~trading days
      }
      out.push({ action: lg.action, type: lg.type, strike: parseFloat(lg.strike), expiryDay: days, ratio: lg.ratio || 1 });
    }
    return out;
  }

  function candidateLegs(candidate) {
    var base = App.Market && App.Market.simTime ? new Date(App.Market.simTime) : new Date();
    return (candidate.legs || []).map(function (lg) {
      if (lg.stock || lg.type === 'STOCK') {
        return { action: lg.action, type: 'STOCK', strike: 0, expiryDay: 0, ratio: lg.ratio || 1 };
      }
      var calDays = lg.expiration ? Math.max(1, Math.ceil((new Date(lg.expiration + 'T16:00:00-04:00') - base) / 86400000)) : 30;
      return { action: lg.action, type: lg.type, strike: parseFloat(lg.strike),
        expiryDay: Math.max(1, Math.round(calDays * 5 / 7)), ratio: lg.ratio || 1 };
    });
  }

  /**
   * Theoretical payoff limits stay visible, but these four distributions answer the trader's
   * practical question: what does this exact package tend to do in plausible market shapes?
   * It calls the existing strategy simulator; there is no parallel risk or pricing engine.
   */
  function realisticOutcomes(symbol, candidate) {
    symbol = String(symbol || '').toUpperCase();
    var beginner = Learn.currentLevel() === 'beginner';
    return UI.expandable(beginner ? 'What this trade could do in realistic markets'
      : 'Scenario distributions — calm / up / down / choppy', function () {
      var holder = el('div', { class: 'realistic-outcomes', 'data-realistic-outcomes': candidate.strategy },
        UI.spinner('Running the exact contracts through shared scenarios…'));
      if (!symbol || !(candidate.legs || []).length) {
        holder.innerHTML = '';
        holder.appendChild(UI.alertBox('warn', 'A symbol and exact contracts are needed for scenario outcomes.'));
        return holder;
      }
      var sourceLegs = candidate.legs || [];
      var legs = candidateLegs(candidate);
      var exactExpirations = sourceLegs.map(function (leg) {
        return leg.stock || String(leg.type || '').toUpperCase() === 'STOCK' ? '' : String(leg.expiration || '');
      });
      var hasExactContracts = exactExpirations.every(function (exp, idx) {
        var leg = sourceLegs[idx] || {};
        return leg.stock || String(leg.type || '').toUpperCase() === 'STOCK' || !!exp;
      });
      var optionDays = legs.filter(function (l) { return l.type !== 'STOCK'; }).map(function (l) { return l.expiryDay; });
      var days = optionDays.length ? Math.max(1, Math.min.apply(Math, optionDays)) : 21;
      var cases = [
        { key: 'calm', label: 'Calm / narrow', shape: 'CHOP', drift: 0, mult: 0.6, seed: 41101 },
        { key: 'up', label: 'Steady rise', shape: 'GRIND_UP', drift: 0.15, mult: 1.0, seed: 41102 },
        { key: 'down', label: 'Steady decline', shape: 'GRIND_DOWN', drift: -0.15, mult: 1.0, seed: 41103 },
        { key: 'chop', label: 'Wide / choppy', shape: 'CHOP', drift: 0, mult: 1.35, seed: 41104 }
      ];
      historicalVol(symbol).then(async function (hv) {
        var rows = [];
        for (var i = 0; i < cases.length; i++) {
          // Expandables are lazy, but their async work can outlive the card after navigation
          // or a level switch. Do not keep feeding the shared simulation budget for a result
          // nobody can see; an in-flight request may finish, then the remaining cases stop.
          if (!holder.isConnected) return;
          var c = cases[i];
          var vol = hv ? hv * c.mult : 0; // 0 asks the server to use the chain's ATM IV
          var spec = { model: 'GBM', shape: c.shape, horizonDays: days, stepsPerDay: 4,
            driftAnnual: c.drift, volAnnual: vol, jumpsPerYear: 0, jumpMean: 0, jumpVol: 0,
            tailNu: 6, heston: null, seed: c.seed, paths: 180 };
          try {
            var position = App.outcomePosition(candidate.strategy, legs, candidate.qty || 1,
              typeof candidate.entryNetPremiumCents === 'number' ? -candidate.entryNetPremiumCents : null,
              hasExactContracts ? exactExpirations : null);
            var result = await App.evaluateOutcome('POSITION', 'PARAMETRIC', symbol,
              { position: position, over: spec, iv: null });
            if (!holder.isConnected) return;
            rows.push({ def: c, result: result });
          } catch (e) {
            if (!holder.isConnected) return;
            rows.push({ def: c, error: e.message || 'Unavailable' });
          }
        }
        if (!holder.isConnected) return;
        holder.innerHTML = '';
        holder.appendChild(el('p', { class: 'muted small' },
          'The theoretical payoff limits above remain the structural truth. These distributions model the exact listed package and its displayed opening price. '
          + (hv ? 'Volatility is scaled from ' + symbol + '\'s own recent moves.'
                : 'No usable candle history was available, so the server calibrated from the option market.')
          + ' They complement those limits; they never replace them.'));
        var grid = el('div', { class: 'realistic-grid' });
        rows.forEach(function (row) {
          if (row.error) {
            grid.appendChild(el('div', { class: 'realistic-case' }, el('b', {}, row.def.label),
              el('div', { class: 'muted small' }, row.error)));
            return;
          }
          var r = row.result;
          grid.appendChild(el('div', { class: 'realistic-case' },
            el('b', {}, row.def.label),
            el('div', { class: 'realistic-typical' }, UI.pnlSpan(r.p50Cents)),
            el('div', { class: 'muted small' }, Math.round(r.winRatePct) + '% profitable'),
            el('div', { class: 'muted small' }, '1-in-20 range ' + UI.fmtMoneyCompact(r.p5Cents)
              + ' to ' + UI.fmtMoneyCompact(r.p95Cents))));
        });
        holder.appendChild(grid);
      });
      return holder;
    });
  }

  window.Scenario = {
    SHAPES: SHAPES, CATALOG: CATALOG,
    form: form, fanChart: fanChart, pnlView: pnlView, workingLegs: workingLegs,
    realisticOutcomes: realisticOutcomes, sketch: sketch, applyCatalog: applyCatalog
  };
})();
