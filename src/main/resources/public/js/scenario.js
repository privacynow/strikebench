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
  var MAGS = [{ key: 'calm', label: 'Calm', vol: 0.15 }, { key: 'typical', label: 'Typical', vol: 0.30 }, { key: 'wild', label: 'Wild', vol: 0.55 }];
  var MODELS = [
    { v: 'GBM', label: 'GBM (lognormal)' }, { v: 'BROWNIAN_BRIDGE', label: 'Brownian bridge (pinned end)' },
    { v: 'BLOCK_BOOTSTRAP', label: 'Block bootstrap (real returns)' }, { v: 'STUDENT_T', label: 'Student-t (fat tails)' },
    { v: 'JUMP_DIFFUSION', label: 'Merton jump-diffusion' }, { v: 'HESTON', label: 'Heston (stochastic vol)' }
  ];

  function sketch(pts) {
    return el('span', { class: 'sc-sketch', html:
      '<svg viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true">'
      + '<line x1="0" y1="16" x2="100" y2="16" stroke="var(--border)" stroke-width="1" stroke-dasharray="3 3"/>'
      + '<polyline points="' + pts + '" fill="none" stroke="var(--accent)" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>' });
  }

  function shapeOf(key) { return SHAPES.find(function (s) { return s.key === key; }) || SHAPES[3]; }

  /* ---------------------------------------------------------------------------------------------
   * The scenario form. opts: {compact} → returns {el, getSpec(), getIv(), describe(), onchange}
   * State persists in App.state.scenarioForm so level flips / navigation never lose the setup.
   * ------------------------------------------------------------------------------------------- */
  function form(level, symbol) {
    var f = App.state.scenarioForm = App.state.scenarioForm || {};
    if (f.seed == null) f.seed = Math.floor(Math.random() * 1000000);
    // A fresh studio opens on the user's WORKING VIEW — the thesis they picked in Ideas seeds
    // the story (bullish → climbs, bearish → fades, volatile → news shock). Pure default: the
    // user can pick any other shape, and a persisted choice always wins.
    if (!f.shape) {
      var thesis = (App.state.discoverForm && App.state.discoverForm.thesis) || '';
      f.shape = { bullish: 'GRIND_UP', bearish: 'RALLY_FADE', neutral: 'CHOP', volatile: 'EVENT_JUMP' }[thesis]
             || 'SELLOFF_REBOUND';
    }
    f.horizon = f.horizon || 10;
    f.mag = f.mag || 'typical';
    var box = el('div', { class: 'scenario-form' });
    var onchange = function () {};
    var expertInputs = {};

    function magPct(vol, days) { return Math.round(vol * Math.sqrt(days / 252) * 100); }

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
        var m = MAGS.find(function (x) { return x.key === f.mag; });
        magNote.textContent = 'Typically within ±' + magPct(m.vol, f.horizon) + '% by the end — with occasional bigger surprises.';
        onchange();
      }
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
      expertInputs = { model: model, seed: seed };
      box.appendChild(el('div', { class: 'form-grid compact-filters' },
        fld('Model', model), fld('Shape guide', shapeSel), fld('Days', horizon),
        fld('Vol σ %/yr', vol), fld('Drift μ %/yr', drift),
        fld('Jumps /yr', jumps), fld('Jump size %', jumpSize), fld('Tail ν (t only)', tailNu)));
      box.appendChild(el('div', { class: 'form-grid compact-filters' },
        fld('IV start %', ivStart), fld('IV event day (−1 none)', ivEvent), fld('IV shock % at event', ivShock),
        fld('Seed', seed), fld('Paths', paths)));
      box.appendChild(UI.expandable('The math', function () {
        return el('div', { class: 'sc-math' },
          el('p', {}, el('b', {}, 'GBM / Student-t: '), 'd ln S = (μ − σ²/2)dt + σ√dt·ε, ε ~ N(0,1) or standardized t(ν).'),
          el('p', {}, el('b', {}, 'Jump-diffusion (Merton): '), 'adds Σ Jᵢ per step, N ~ Poisson(λdt), J ~ N(m, s²).'),
          el('p', {}, el('b', {}, 'Heston: '), 'dv = κ(θ − v)dt + ξ√v·dWᵥ, corr(dWₛ, dWᵥ) = ρ; full-truncation Euler.'),
          el('p', {}, el('b', {}, 'Bootstrap: '), 'resamples blocks of the symbol’s own observed daily returns (mean-removed) — preserves fat tails and autocorrelation.'),
          el('p', {}, el('b', {}, 'Shape guide: '), 'a deterministic log-drift curve (valley/mountain/linear) the noise rides on; the bridge pins the endpoint.'),
          el('p', {}, el('b', {}, 'IV path: '), 'deterministic: dIV = drift·dt + κ(long-run − IV)dt, with a one-off shock at the event day’s close. Option values are BSM on this path — always labeled MODELED.'),
          el('p', { class: 'muted small' }, 'Same seed ⇒ byte-identical paths. Fills, commissions, and early assignment are not modeled.'));
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
    function fld(label, input) { return el('div', { class: 'field' }, el('label', {}, label), input); }

    function getSpec() {
      var s = shapeOf(f.shape);
      if (level === 'beginner') {
        var m = MAGS.find(function (x) { return x.key === f.mag; });
        var jumpy = s.model === 'JUMP_DIFFUSION';
        return { model: s.model, shape: f.shape, horizonDays: f.horizon, stepsPerDay: 1,
          driftAnnual: s.drift, volAnnual: m.vol, jumpsPerYear: jumpy ? 8 : 0,
          jumpMean: f.shape === 'GAP_DOWN' ? -0.05 : (f.shape === 'GAP_UP' ? 0.05 : 0),
          jumpVol: jumpy ? 0.04 : 0, tailNu: 6, heston: null, seed: f.seed, paths: 200 };
      }
      return { model: f.model || s.model, shape: f.shape, horizonDays: f.horizon, stepsPerDay: 1,
        driftAnnual: (f.drift != null ? f.drift : s.drift * 100) / 100, volAnnual: (f.vol != null ? f.vol : 30) / 100,
        jumpsPerYear: f.jumps || 0, jumpMean: (f.jumpSize || 0) / 100, jumpVol: Math.abs((f.jumpSize || 0) / 100) * 0.5,
        tailNu: f.nu || 6, heston: null, seed: f.seed, paths: f.paths || 300 };
    }

    function getIv() {
      if (level === 'beginner') {
        var m = MAGS.find(function (x) { return x.key === f.mag; });
        if (f.shape === 'EVENT_JUMP') { // earnings-style: IV rich into the event, crushed after
          return { startIv: m.vol * 1.4, driftPerYear: 0, meanRevertSpeed: 1.5, longRunIv: m.vol,
            eventDay: Math.max(1, Math.round(f.horizon / 3)), eventShockPct: -0.35, minIv: 0.03, maxIv: 4 };
        }
        return { startIv: m.vol * 1.1, driftPerYear: 0, meanRevertSpeed: 0, longRunIv: m.vol * 1.1,
          eventDay: -1, eventShockPct: 0, minIv: 0.03, maxIv: 4 };
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
    var samples = (p.samples || []).map(function (s) {
      return '<path d="' + s.map(function (v, i) { return (i ? 'L' : 'M') + x(i).toFixed(1) + ',' + y(v).toFixed(1); }).join(' ')
        + '" fill="none" stroke="var(--text-faint)" stroke-width="1" opacity="0.55"/>';
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

    // Terminal distribution histogram.
    if (r.distribution && r.distribution.length) {
      var max = 1; r.distribution.forEach(function (b) { max = Math.max(max, b.count); });
      var n = r.distribution.length, bw = 100 / n;
      var bars = r.distribution.map(function (b, i) {
        var h = b.count / max * 30;
        var mid = (b.fromCents + b.toCents) / 2;
        var col = mid >= 0 ? 'var(--risk-ok-solid,#3aa76d)' : 'var(--risk-danger-solid,#d64545)';
        return '<rect x="' + (i * bw + bw * 0.12).toFixed(2) + '" y="' + (34 - h).toFixed(2) + '" width="' + (bw * 0.76).toFixed(2)
          + '" height="' + h.toFixed(2) + '" rx="0.6" fill="' + col + '"><title>' + UI.fmtMoneyCompact(b.fromCents) + ' to ' + UI.fmtMoneyCompact(b.toCents) + ': ' + b.count + '</title></rect>';
      }).join('');
      out.appendChild(el('div', { class: 'lab-chart', html:
        '<svg viewBox="0 0 100 40" preserveAspectRatio="none" width="100%" height="72" role="img">'
        + '<line x1="0" y1="34" x2="100" y2="34" stroke="var(--border)" stroke-width="0.5"/>' + bars + '</svg>' }));
      out.appendChild(el('div', { class: 'muted small' },
        level === 'beginner' ? 'Where the futures landed: green bars made money, red lost.' : 'Terminal P&L distribution.'));
    }
    (r.notes || []).forEach(function (n) { out.appendChild(el('div', { class: 'muted small' }, n)); });
    return out;
  }

  /* ---- Position quick-picks for Verify (plain names; strikes auto from spot, editable at Expert) */
  var QUICKS = [
    { key: 'LONG_CALL', label: 'Bet on a rise (long call)', legs: function (s, d) { return [L('BUY', 'CALL', rnd(s), d)]; } },
    { key: 'LONG_PUT', label: 'Bet on a fall (long put)', legs: function (s, d) { return [L('BUY', 'PUT', rnd(s), d)]; } },
    { key: 'CALL_SPREAD', label: 'Defined-risk rise (call spread)', legs: function (s, d) { return [L('BUY', 'CALL', rnd(s), d), L('SELL', 'CALL', rnd(s * 1.05), d)]; } },
    { key: 'PUT_SPREAD', label: 'Defined-risk fall (put spread)', legs: function (s, d) { return [L('BUY', 'PUT', rnd(s), d), L('SELL', 'PUT', rnd(s * 0.95), d)]; } },
    { key: 'CSP', label: 'Get paid to wait (short put)', legs: function (s, d) { return [L('SELL', 'PUT', rnd(s * 0.95), d)]; } },
    { key: 'STRADDLE', label: 'Bet on a big move (straddle)', legs: function (s, d) { return [L('BUY', 'CALL', rnd(s), d), L('BUY', 'PUT', rnd(s), d)]; } }
  ];
  function L(action, type, strike, expiryDay) { return { action: action, type: type, strike: strike, expiryDay: expiryDay, ratio: 1 }; }
  function rnd(v) { return Math.round(v); }

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

  window.Scenario = {
    SHAPES: SHAPES, QUICKS: QUICKS,
    form: form, fanChart: fanChart, pnlView: pnlView, workingLegs: workingLegs, sketch: sketch
  };
})();
