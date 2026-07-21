/* StrikeBench Scenario Studio — the shared "imagine a future" surface.
 * One question and one parameter state, presented at two altitudes:
 *   Beginner: story, horizon, and wildness controls plus a plain-language read-only receipt of
 *             every carried model parameter.
 *   Expert:   those SAME controls and values, with the complete parameter editor revealed below.
 * A level switch never changes the spec, IV path, seed, or result. Presentation is the only delta.
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
  var HORIZONS = [
    { d: Product.Horizon.sessions('week'), label: '1 week' },
    { d: 10, label: '2 weeks' },
    { d: Product.Horizon.sessions('month'), label: '1 month' },
    { d: Product.Horizon.sessions('quarter'), label: '3 months' }
  ];
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
      if (closes.length < 30) return { vol: null, evidence: h.evidence || null };
      var sum = 0, sum2 = 0, n = 0;
      for (var i = 1; i < closes.length; i++) {
        var r = Math.log(closes[i] / closes[i - 1]);
        sum += r; sum2 += r * r; n++;
      }
      var sd = Math.sqrt(Math.max(0, sum2 / n - (sum / n) * (sum / n)));
      var hv = sd * Math.sqrt(252);
      return { vol: isFinite(hv) && hv > 0.03 && hv < 3 ? hv : null,
        evidence: h.evidence || null };
    }).catch(function () { return { vol: null, evidence: null }; });
  }

  /* ---------------------------------------------------------------------------------------------
   * The scenario form. opts: {compact} → returns {el, getSpec(), getIv(), describe(), onchange}
   * State is supplied by its Plan or Data owner; there is no global scenario workflow.
   * ------------------------------------------------------------------------------------------- */
  function form(level, symbol, seedContext, ownedState) {
    seedContext = seedContext || {};
    if (!ownedState) throw new Error('Scenario form state must have an explicit owner.');
    var f = ownedState;
    if (f.seed == null) f.seed = Math.floor(Math.random() * 1000000);
    // A fresh studio opens on the user's declared working view — the thesis they already own
    // visibly selects a story (bullish → climbs, bearish → fades, volatile → news shock).
    // With no declared view, nothing is selected; a persisted choice always wins.
    if (!f.shape) {
      var thesis = seedContext.thesis || '';
      f.shape = { bullish: 'GRIND_UP', bearish: 'RALLY_FADE', neutral: 'CHOP', volatile: 'EVENT_JUMP' }[thesis]
             || null;
    }
    var seededHorizon = Number(f.horizon || seedContext.horizonDays);
    f.horizon = seededHorizon > 0 ? seededHorizon : null;
    f.mag = MAGS.some(function (m) { return m.key === f.mag; }) ? f.mag : 'typical';

    // Canonical parameters live in the owner, not in a level-specific rendering. Existing
    // sessions that already contain an Expert edit retain it; new sessions inherit the story
    // preset and lane calibration without manufacturing a 30% input.
    var initialShape = f.shape ? shapeOf(f.shape) : null;
    if (!f.model && initialShape) f.model = initialShape.model;
    if (f.spd == null) f.spd = 4;
    if (f.drift == null) f.drift = initialShape ? initialShape.drift * 100 : 0;
    if (f.jumps == null) f.jumps = initialShape && initialShape.model === 'JUMP_DIFFUSION' ? 8 : 0;
    if (f.jumpSize == null) f.jumpSize = f.shape === 'GAP_DOWN' ? -5 : f.shape === 'GAP_UP' ? 5 : 0;
    if (f.jumpVol == null) f.jumpVol = initialShape && initialShape.model === 'JUMP_DIFFUSION' ? 4 : 0;
    if (f.nu == null) f.nu = 6;
    if (f.paths == null) f.paths = 2000;
    if (f.hKappa == null) f.hKappa = 3;
    if (f.hXi == null) f.hXi = 0.5;
    if (f.hRho == null) f.hRho = -0.6;
    if (!f.volMode) f.volMode = Number(f.vol) > 0 ? 'custom' : 'lane';
    if (!f.ivMode) f.ivMode = Number(f.ivStart) > 0 ? 'custom' : 'lane';
    if (f.ivDrift == null) f.ivDrift = 0;
    if (f.ivMeanRevert == null) f.ivMeanRevert = 0.5;
    if (f.ivEventDay == null) f.ivEventDay = -1;
    if (f.ivShock == null) f.ivShock = -35;
    if (f.ivMin == null) f.ivMin = 3;
    if (f.ivMax == null) f.ivMax = 400;
    f.formVersion = 2;

    var box = el('div', { class: 'scenario-form' });
    var onchange = function () {};
    var controls = {};
    var factHost = null;
    var syncing = false;

    function magPct(vol, days) { return Math.round(vol * Math.sqrt(days / 252) * 100); }

    function finite(value) { return value !== null && value !== '' && Number.isFinite(Number(value)); }
    function pct(value, digits) {
      if (!finite(value)) return 'not set';
      return Number(value).toFixed(digits == null ? 1 : digits).replace(/\.0+$/, '') + '%';
    }
    function modelLabel(value) {
      var item = MODELS.find(function (m) { return m.v === value; });
      return item ? item.label : 'Choose a story or engine';
    }

    function calibration() {
      return f.calibration && f.calibration.loaded ? f.calibration : { loaded: false, vol: null, evidence: null };
    }

    function laneVolAnnual() {
      var c = calibration();
      if (!(Number(c.vol) > 0)) return 0;
      var magnitude = MAGS.find(function (m) { return m.key === f.mag; }) || MAGS[1];
      return Number(c.vol) * magnitude.mult;
    }

    function effectiveVolAnnual() {
      return f.volMode === 'custom' && Number(f.vol) > 0 ? Number(f.vol) / 100 : laneVolAnnual();
    }

    function provenanceText() {
      var c = calibration();
      var provenance = String(c.evidence && c.evidence.provenance || '').toUpperCase();
      return provenance === 'DEMO' ? 'fabricated Demo history'
        : provenance === 'SIMULATED' ? 'this simulated session\u2019s generated history'
        : provenance === 'MODELED' ? 'modeled history'
        : provenance === 'OBSERVED' || provenance === 'BROKER' ? 'observed recent history'
        : 'the eligible recent history in this lane';
    }

    function applyShapePreset(key) {
      var shape = shapeOf(key);
      f.shape = shape.key;
      f.model = shape.model;
      f.drift = shape.drift * 100;
      f.jumps = shape.model === 'JUMP_DIFFUSION' ? 8 : 0;
      f.jumpSize = key === 'GAP_DOWN' ? -5 : key === 'GAP_UP' ? 5 : 0;
      f.jumpVol = shape.model === 'JUMP_DIFFUSION' ? 4 : 0;
      sync();
    }

    function applyMagnitude(key) {
      f.mag = key;
      f.volMode = 'lane';
      delete f.vol;
      // Heston's long-run and initial variance follow the same wildness choice unless the
      // Expert subsequently authors explicit values.
      delete f.hLongVol;
      delete f.hInitialVol;
      sync();
    }

    // Per-ticker calibration: annualized HV from the symbol's (usually prefetched) history.
    // While unknown, volAnnual remains 0: the explicit server sentinel for this lane's
    // nearest-horizon option volatility. Persisting calibration in the owner makes a level flip
    // byte-stable; a newly mounted form never starts over with another default.
    if (symbol && !calibration().loaded) historicalVol(symbol).then(function (result) {
      f.calibration = { loaded: true, vol: result && result.vol || null,
        evidence: result && result.evidence || null };
      if (box._sync) box._sync();
    });

    // The primary controls are identical at both altitudes.
    box.appendChild(el('div', { class: 'field-label' },
      'What do you think ' + (symbol || 'it') + ' does?', UI.info('scenario')));
    var grid = el('div', { class: 'q-grid sc-grid', id: 'sc-shapes', role: 'group',
      'aria-label': 'Possible market story' });
    SHAPES.forEach(function (shape) {
      grid.appendChild(el('button', { type: 'button', 'data-shape': shape.key,
        class: 'q-card sc-card', onclick: function () { applyShapePreset(shape.key); } },
        sketch(shape.pts), el('b', {}, shape.label), el('div', { class: 'muted small' }, shape.blurb)));
    });
    box.appendChild(grid);

    var row = el('div', { class: 'form-grid sc-primary-controls' });
    var hzWrap = el('div', { class: 'chip-row', id: 'sc-horizon', role: 'group', 'aria-label': 'Scenario horizon' });
    var magWrap = el('div', { class: 'chip-row', id: 'sc-mag', role: 'group', 'aria-label': 'Scenario volatility' });
    var magNote = el('div', { class: 'muted small', id: 'sc-mag-note', 'aria-live': 'polite' });
    MAGS.forEach(function (magnitude) {
      magWrap.appendChild(el('button', { type: 'button', class: 'sym-chip', 'data-mag': magnitude.key,
        onclick: function () { applyMagnitude(magnitude.key); } }, magnitude.label));
    });
    row.appendChild(el('div', { class: 'field' }, el('div', { class: 'field-label' }, 'Over the next'), hzWrap));
    row.appendChild(el('div', { class: 'field' },
      el('div', { class: 'field-label' }, 'How wild could it get?', UI.info('hv30')), magWrap, magNote));
    box.appendChild(row);

    box.appendChild(UI.expandable('How this works', function () {
      return el('div', {},
        el('p', {}, 'We draw hundreds of made-up-but-realistic price paths that follow the story you picked, sized by the wildness you chose. It is a model of what COULD happen — never a forecast.'),
        el('p', {}, 'Option prices along each path use the IV assumption named below, so time decay and volatility changes are included. Every run is labeled as simulated.'));
    }, { persist: false }));

    function num(id, val, min, max, on, placeholder) {
      var i = el('input', { type: 'number', id: id, value: val == null ? '' : val,
        min: String(min), max: String(max), step: 'any', placeholder: placeholder || null });
      i.addEventListener('change', function () { on(i.value === '' ? null : Number(i.value)); });
      return i;
    }
    function fld(label, input, infoKey) {
      // Greek glyphs must survive the app-wide uppercase label transform (σ became Σ — the
      // summation sign — which a quant reads as a bug). Wrap them in a no-transform span.
      var labelEl = el('label', { for: input.id });
      label.split(/([σμκξρν])/).forEach(function (part) {
        if (/^[σμκξρν]$/.test(part)) labelEl.appendChild(el('span', { class: 'glyph' }, part));
        else if (part) labelEl.appendChild(document.createTextNode(part));
      });
      if (infoKey) labelEl.appendChild(UI.info(infoKey));
      return el('div', { class: 'field' }, labelEl, input);
    }

    function advancedEditor() {
      var host = el('div', { class: 'sc-advanced', id: 'sc-advanced' });
      controls.model = UI.segmented({ id: 'sc-model', label: 'Path engine',
        options: MODELS.map(function (item) { return { value: item.v, label: item.label }; }),
        value: f.model, onChange: function (value) {
          if (syncing) return;
          f.model = value; sync();
        } });
      host.appendChild(controls.model);

      controls.days = num('sc-days', f.horizon, 1, 756, function (v) { if (v != null) f.horizon = v; sync(); });
      controls.spd = num('sc-spd', f.spd, 1, 16, function (v) { if (v != null) f.spd = v; sync(); });
      controls.vol = num('sc-vol', f.volMode === 'custom' ? f.vol : null, 1, 500, function (v) {
        if (v == null) { f.volMode = 'lane'; delete f.vol; }
        else { f.volMode = 'custom'; f.vol = v; }
        sync();
      }, 'Lane calibrated');
      controls.drift = num('sc-drift', f.drift, -200, 200, function (v) { if (v != null) f.drift = v; sync(); });
      controls.jumps = num('sc-jumps', f.jumps, 0, 260, function (v) { if (v != null) f.jumps = v; sync(); });
      controls.jumpSize = num('sc-jumpsize', f.jumpSize, -100, 100, function (v) { if (v != null) f.jumpSize = v; sync(); });
      controls.jumpVol = num('sc-jumpvol', f.jumpVol, 0, 100, function (v) { if (v != null) f.jumpVol = v; sync(); });
      controls.tailNu = num('sc-nu', f.nu, 2.5, 200, function (v) { if (v != null) f.nu = v; sync(); });
      controls.seed = num('sc-seed', f.seed, 0, 99999999, function (v) { if (v != null) f.seed = v; sync(); });
      controls.paths = num('sc-paths', f.paths, 20, 2000, function (v) { if (v != null) f.paths = v; sync(); });
      host.appendChild(el('div', { class: 'form-grid compact-filters sc-param-grid' },
        fld('Horizon (trading days)', controls.days), fld('Steps/day', controls.spd),
        fld('Volatility σ (%/yr)', controls.vol), fld('Drift μ (%/yr)', controls.drift),
        fld('Jump frequency (/yr)', controls.jumps), fld('Jump mean (%)', controls.jumpSize),
        fld('Jump dispersion (%)', controls.jumpVol), fld('Tail ν', controls.tailNu),
        fld('Seed', controls.seed), fld('Paths', controls.paths)));

      controls.hKappa = num('sc-hkappa', f.hKappa, 0.1, 20, function (v) { if (v != null) f.hKappa = v; sync(); });
      controls.hLongVol = num('sc-htheta', f.hLongVol, 1, 500, function (v) { f.hLongVol = v; sync(); }, 'Same as σ');
      controls.hXi = num('sc-hxi', f.hXi, 0.01, 3, function (v) { if (v != null) f.hXi = v; sync(); });
      controls.hRho = num('sc-hrho', f.hRho, -0.99, 0.99, function (v) { if (v != null) f.hRho = v; sync(); });
      controls.hInitialVol = num('sc-hv0', f.hInitialVol, 1, 500, function (v) { f.hInitialVol = v; sync(); }, 'Same as σ');
      host.appendChild(el('div', { class: 'sc-param-section' },
        el('div', { class: 'field-label' }, 'Heston variance path'),
        el('div', { class: 'form-grid compact-filters sc-param-grid' },
          fld('Mean reversion κ', controls.hKappa), fld('Long-run vol (θ½) %', controls.hLongVol),
          fld('Vol of variance ξ', controls.hXi), fld('Spot/vol correlation ρ', controls.hRho),
          fld('Initial vol (v₀½) %', controls.hInitialVol))));

      controls.ivMode = UI.segmented({ id: 'sc-iv-mode', label: 'Option-volatility path',
        options: [
          { value: 'lane', label: 'Use lane option IV', detail: 'No invented starting value' },
          { value: 'custom', label: 'Custom IV path', detail: 'Author every value below' }
        ], value: f.ivMode, revealDetails: 'expert', onChange: function (value) {
          if (syncing) return;
          f.ivMode = value;
          if (value === 'custom' && !(Number(f.ivStart) > 0)) {
            var effective = effectiveVolAnnual();
            f.ivStart = effective > 0 ? effective * 100 : null;
            f.ivLongRun = effective > 0 ? effective * 100 : null;
          }
          sync();
        } });
      host.appendChild(controls.ivMode);
      controls.ivStart = num('sc-iv', f.ivStart, 3, 400, function (v) { f.ivStart = v; sync(); }, 'Required for custom');
      controls.ivDrift = num('sc-ivdrift', f.ivDrift, -300, 300, function (v) { if (v != null) f.ivDrift = v; sync(); });
      controls.ivMeanRevert = num('sc-ivrevert', f.ivMeanRevert, 0, 20, function (v) { if (v != null) f.ivMeanRevert = v; sync(); });
      controls.ivLongRun = num('sc-ivlong', f.ivLongRun, 3, 400, function (v) { f.ivLongRun = v; sync(); }, 'Required for custom');
      controls.ivEvent = num('sc-ivday', f.ivEventDay, -1, 756, function (v) { if (v != null) f.ivEventDay = v; sync(); });
      controls.ivShock = num('sc-ivshock', f.ivShock, -90, 300, function (v) { if (v != null) f.ivShock = v; sync(); });
      controls.ivMin = num('sc-ivmin', f.ivMin, 1, 400, function (v) { if (v != null) f.ivMin = v; sync(); });
      controls.ivMax = num('sc-ivmax', f.ivMax, 3, 500, function (v) { if (v != null) f.ivMax = v; sync(); });
      controls.ivFields = [controls.ivStart, controls.ivDrift, controls.ivMeanRevert, controls.ivLongRun,
        controls.ivEvent, controls.ivShock, controls.ivMin, controls.ivMax];
      controls.ivNote = el('p', { class: 'muted small', id: 'sc-iv-basis-note' });
      host.appendChild(controls.ivNote);
      host.appendChild(el('div', { class: 'form-grid compact-filters sc-param-grid' },
        fld('IV start %', controls.ivStart), fld('IV drift (points/yr)', controls.ivDrift),
        fld('IV mean reversion', controls.ivMeanRevert), fld('IV long-run %', controls.ivLongRun),
        fld('Event on trading day #', controls.ivEvent, 'eventday'), fld('IV change %', controls.ivShock, 'ivchange'),
        fld('IV floor %', controls.ivMin), fld('IV ceiling %', controls.ivMax)));
      syncAdvanced();
      return host;
    }

    function laneIvText() {
      if (f.shape === 'EVENT_JUMP') {
        return 'Lane ATM IV × 1.4 at the start; mean-reverts to lane ATM at 1.5/yr; '
          + '−35% event shock after day ' + Math.max(1, Math.round(Number(f.horizon) / 3))
          + '; 3% floor; 400% ceiling.';
      }
      return 'Flat at this lane\u2019s nearest-horizon ATM option IV; no event; 3% floor; 400% ceiling.';
    }

    function assumptionRows() {
      var vol = effectiveVolAnnual();
      var hLong = Number(f.hLongVol) > 0 ? Number(f.hLongVol) / 100 : vol;
      var hInitial = Number(f.hInitialVol) > 0 ? Number(f.hInitialVol) / 100 : vol;
      var volText = f.volMode === 'custom'
        ? pct(f.vol) + ' annualized (carried Expert override)'
        : vol > 0
          ? pct(vol * 100) + ' annualized (' + f.mag + ' × ' + provenanceText() + ')'
          : calibration().loaded
            ? 'Resolved by the server from this lane\u2019s nearest-horizon option IV'
            : 'Lane calibration is loading; the server market-IV sentinel is carried until it resolves';
      var ivText = f.ivMode !== 'custom' ? laneIvText()
        : 'Start ' + pct(f.ivStart) + '; drift ' + pct(f.ivDrift) + '/yr; mean reversion '
          + f.ivMeanRevert + '/yr; long-run ' + pct(f.ivLongRun) + '; event day '
          + f.ivEventDay + '; shock ' + pct(f.ivShock) + '; bounds ' + pct(f.ivMin) + '–' + pct(f.ivMax) + '.';
      return [
        ['shape', 'Story guide', f.shape ? shapeOf(f.shape).label + ' (' + f.shape + ')' : 'Not chosen'],
        ['model', 'Path engine', modelLabel(f.model)],
        ['horizon', 'Clock', Number(f.horizon) > 0
          ? f.horizon + ' trading days × ' + f.spd + ' steps/day' : 'Not chosen'],
        ['drift', 'Annual drift μ', pct(f.drift)],
        ['vol', 'Annual realized volatility σ', volText],
        ['jumps', 'Jump process', f.jumps + '/yr; mean ' + pct(f.jumpSize) + '; dispersion ' + pct(f.jumpVol)],
        ['tail', 'Student-t tail ν', String(f.nu)],
        ['heston', 'Heston parameters', 'κ ' + f.hKappa + '; θ½ ' + (hLong > 0 ? pct(hLong * 100) : 'lane σ')
          + '; ξ ' + f.hXi + '; ρ ' + f.hRho + '; v₀½ ' + (hInitial > 0 ? pct(hInitial * 100) : 'lane σ')],
        ['sampling', 'Reproducible sample', Number(f.paths).toLocaleString() + ' paths; seed ' + f.seed],
        ['iv', 'Option-volatility path', ivText]
      ];
    }

    function readOnlyFacts() {
      factHost = el('div', { class: 'sc-assumption-list', id: 'sc-assumption-facts' });
      renderFacts();
      return el('div', {},
        el('p', { class: 'muted small' }, 'These are the exact inputs carried into the run. Switch to Expert only if you want to edit them; switching by itself changes nothing.'),
        factHost);
    }

    function renderFacts() {
      if (!factHost) return;
      factHost.innerHTML = '';
      assumptionRows().forEach(function (row2) {
        factHost.appendChild(el('div', { class: 'sc-assumption-row', 'data-param': row2[0] },
          el('span', { class: 'sc-assumption-label' }, row2[1]),
          el('span', { class: 'sc-assumption-value' }, row2[2])));
      });
    }

    box.appendChild(UI.expandable(level === 'expert' ? 'Edit the carried model parameters' : 'See every model assumption',
      level === 'expert' ? advancedEditor : readOnlyFacts,
      { open: level === 'expert', persist: false, stateKey: 'scenario-canonical-parameters' }));

    if (level === 'expert') box.appendChild(UI.expandable('The math', function () {
      return el('div', { class: 'sc-math' },
        el('p', {}, el('b', {}, 'GBM / Student-t: '), 'Gaussian GBM uses the usual σ²/2 correction. Student-t shocks use the selected non-integer ν, are capped at ±8 standardized deviations, re-scaled, and use that bounded law’s exponential compensator so the guide remains the expected price path.'),
        el('p', {}, el('b', {}, 'Jump-diffusion (Merton): '), 'adds Σ Jᵢ per step, N ~ Poisson(λdt), J ~ N(m, s²).'),
        el('p', {}, el('b', {}, 'Heston: '), 'dv = κ(θ − v)dt + ξ√v·dWᵥ, corr(dWₛ, dWᵥ) = ρ; full-truncation Euler.'),
        el('p', {}, el('b', {}, 'Bootstrap: '), 'resamples blocks of the symbol’s own observed daily returns (mean-removed), preserving fat tails and autocorrelation; an empirical block-prefix compensator keeps the guide mean-honest.'),
        el('p', {}, el('b', {}, 'Shape guide: '), 'a deterministic log-drift curve (valley/mountain/linear) the noise rides on; the bridge pins the endpoint.'),
        el('p', {}, el('b', {}, 'IV path: '), 'deterministic: dIV = drift·dt + κ(long-run − IV)dt, with a one-off shock at the event day’s close. Option values are BSM on this path — always labeled MODELED.'),
        el('p', { class: 'muted small' }, 'Same seed ⇒ byte-identical paths. Each result names whether entry is quoted or modeled and includes configured round-trip commissions; future exit spread and early assignment are not modeled.'));
    }, { persist: false }));

    function syncAdvanced() {
      if (!controls.model) return;
      syncing = true;
      controls.model.set(f.model);
      if (controls.ivMode) controls.ivMode.set(f.ivMode);
      syncing = false;
      controls.days.value = f.horizon;
      controls.spd.value = f.spd;
      controls.drift.value = f.drift;
      controls.jumps.value = f.jumps;
      controls.jumpSize.value = f.jumpSize;
      controls.jumpVol.value = f.jumpVol;
      controls.tailNu.value = f.nu;
      controls.seed.value = f.seed;
      controls.paths.value = f.paths;
      controls.vol.value = f.volMode === 'custom' ? f.vol : (effectiveVolAnnual() > 0
        ? +(effectiveVolAnnual() * 100).toFixed(4) : '');
      controls.vol.dataset.basis = f.volMode;
      if (controls.hLongVol && f.hLongVol == null) controls.hLongVol.value = effectiveVolAnnual() > 0
        ? +(effectiveVolAnnual() * 100).toFixed(4) : '';
      if (controls.hInitialVol && f.hInitialVol == null) controls.hInitialVol.value = effectiveVolAnnual() > 0
        ? +(effectiveVolAnnual() * 100).toFixed(4) : '';
      var model = f.model;
      var offNote = 'Not used by ' + modelLabel(model);
      [[controls.jumps, model === 'JUMP_DIFFUSION'], [controls.jumpSize, model === 'JUMP_DIFFUSION'],
       [controls.jumpVol, model === 'JUMP_DIFFUSION'], [controls.tailNu, model === 'STUDENT_T'],
       [controls.hKappa, model === 'HESTON'], [controls.hLongVol, model === 'HESTON'],
       [controls.hXi, model === 'HESTON'], [controls.hRho, model === 'HESTON'],
       [controls.hInitialVol, model === 'HESTON']].forEach(function (pair) {
        pair[0].disabled = !pair[1];
        pair[0].title = pair[1] ? '' : offNote;
      });
      (controls.ivFields || []).forEach(function (input) {
        input.disabled = f.ivMode !== 'custom';
        input.title = f.ivMode === 'custom' ? '' : 'The server uses this lane\u2019s option IV; choose Custom IV path to edit.';
      });
      var ivNote = controls.ivNote;
      if (ivNote) ivNote.textContent = f.ivMode === 'custom'
        ? 'These eight values are sent exactly. A missing start or long-run value stops the run instead of inventing one.'
        : laneIvText() + ' No IV object is sent, so there is no hidden starting percentage.';
    }

    function renderHorizonChips() {
      hzWrap.innerHTML = '';
      var choices = HORIZONS.slice();
      if (Number(f.horizon) > 0
          && !choices.some(function (h) { return Number(h.d) === Number(f.horizon); })) {
        choices.push({ d: Number(f.horizon), label: Number(f.horizon) + ' days' });
      }
      choices.forEach(function (horizon) {
        var active = Number(f.horizon) === Number(horizon.d);
        hzWrap.appendChild(el('button', { type: 'button', class: 'sym-chip' + (active ? ' active' : ''),
          'data-days': horizon.d, 'aria-pressed': String(active), onclick: function () {
            f.horizon = Number(horizon.d); sync();
          } }, horizon.label));
      });
    }

    function sync() {
      grid.querySelectorAll('.sc-card').forEach(function (button) {
        var active = button.getAttribute('data-shape') === f.shape;
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', String(active));
      });
      magWrap.querySelectorAll('.sym-chip').forEach(function (button) {
        var active = button.getAttribute('data-mag') === f.mag;
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', String(active));
      });
      renderHorizonChips();
      var vol = effectiveVolAnnual();
      if (!(Number(f.horizon) > 0)) {
        magNote.textContent = 'Choose a horizon above; StrikeBench will not silently assume one.';
      } else if (f.volMode === 'custom' && Number(f.vol) > 0) {
        magNote.textContent = 'Custom carried volatility: ' + pct(f.vol) + ' annualized (about ±'
          + magPct(vol, f.horizon) + '% over this horizon). Choose a wildness preset to return to lane calibration.';
      } else if (!calibration().loaded) {
        magNote.textContent = 'Loading volatility calibration for this market lane\u2026';
      } else if (!(vol > 0)) {
        magNote.textContent = 'No eligible daily history here; the run will use this lane\u2019s nearest-horizon option volatility.';
      } else {
        magNote.textContent = 'Typically within ±' + magPct(vol, f.horizon)
          + '% by the end — scaled to ' + provenanceText() + ' for ' + (symbol || 'this stock') + '.';
      }
      syncAdvanced();
      renderFacts();
      onchange();
    }

    box._sync = sync;
    sync();

    function getSpec() {
      if (!f.shape) throw new Error('Choose a market story; StrikeBench will not silently assume one.');
      if (!(Number(f.horizon) > 0)) throw new Error('Choose a scenario horizon; StrikeBench will not silently assume one.');
      var s = shapeOf(f.shape);
      var vol = effectiveVolAnnual();
      if (f.volMode === 'custom' && !(vol > 0)) throw new Error('Enter a positive custom volatility or choose a wildness preset.');
      var wantHeston = (f.model || s.model) === 'HESTON';
      var hLong = Number(f.hLongVol) > 0 ? Number(f.hLongVol) / 100 : vol;
      var hInitial = Number(f.hInitialVol) > 0 ? Number(f.hInitialVol) / 100 : vol;
      var heston = wantHeston && hLong > 0 && hInitial > 0 ? {
        kappa: Number(f.hKappa), theta: hLong * hLong, xi: Number(f.hXi),
        rho: Number(f.hRho), v0: hInitial * hInitial
      } : null;
      return { model: f.model || s.model, shape: f.shape, horizonDays: Number(f.horizon),
        stepsPerDay: Number(f.spd), driftAnnual: Number(f.drift) / 100, volAnnual: vol,
        jumpsPerYear: Number(f.jumps), jumpMean: Number(f.jumpSize) / 100,
        jumpVol: Number(f.jumpVol) / 100, tailNu: Number(f.nu), heston: heston,
        seed: Number(f.seed), paths: Number(f.paths) };
    }

    function getIv() {
      // Lane mode is deliberately null at BOTH levels. The server resolves the exact ATM option
      // IV and writes it into the receipt; the browser never substitutes an invisible 30%.
      if (f.ivMode !== 'custom') return null;
      if (!(Number(f.ivStart) > 0) || !(Number(f.ivLongRun) > 0)) {
        throw new Error('Custom IV needs both a starting and long-run percentage.');
      }
      return { startIv: Number(f.ivStart) / 100, driftPerYear: Number(f.ivDrift) / 100,
        meanRevertSpeed: Number(f.ivMeanRevert), longRunIv: Number(f.ivLongRun) / 100,
        eventDay: Number(f.ivEventDay), eventShockPct: Number(f.ivShock) / 100,
        minIv: Number(f.ivMin) / 100, maxIv: Number(f.ivMax) / 100 };
    }

    function describe() {
      if (!f.shape || !(Number(f.horizon) > 0)) return 'Scenario not fully specified';
      var s = shapeOf(f.shape);
      var hz = HORIZONS.find(function (h) { return h.d === f.horizon; });
      return s.label + ' over ' + (hz ? hz.label : f.horizon + ' days');
    }

    function reroll() { f.seed = Math.floor(Math.random() * 1000000); sync(); }

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
    ((p.decisionMap && p.decisionMap.levels) || []).forEach(function (lv) {
      lo = Math.min(lo, lv.price); hi = Math.max(hi, lv.price);
    });
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
    function levelName(key) {
      if (key === 'target') return 'your level';
      if (key.indexOf('breakeven') === 0) return 'breakeven';
      if (key.indexOf('short-put') === 0) return 'short put';
      if (key.indexOf('short-call') === 0) return 'short call';
      return key.replace(/[-_]/g, ' ');
    }
    var levelLines = ((p.decisionMap && p.decisionMap.levels) || []).map(function (lv, i) {
      var yy = y(lv.price).toFixed(1), labelY = Math.max(10, y(lv.price) - 4 - (i % 2) * 9).toFixed(1);
      return '<line x1="' + padL + '" y1="' + yy + '" x2="' + (W - padR) + '" y2="' + yy
        + '" stroke="var(--risk-caution-solid,#b98a00)" stroke-width="1.1" stroke-dasharray="6 4" opacity="0.9"/>'
        + '<text x="' + (padL + 4) + '" y="' + labelY + '" font-size="10" fill="var(--risk-caution-solid,#b98a00)">'
        + levelName(lv.key) + ' ' + Number(lv.price).toFixed(2) + '</text>';
    }).join('');
    // A pinned fan is never presented as plain Monte Carlo: outside the authoring canvas the
    // stored waypoints still render as read-only markers (the honesty note follows the chart).
    var readOnlyPins = !opts.author && (p.waypoints || []).length ? p.waypoints.map(function (w) {
      return '<circle cx="' + x(w.dayIndex).toFixed(1) + '" cy="' + y(w.priceRatio * p.spot).toFixed(1)
        + '" r="4" class="fan-pin-dot"><title>authored waypoint · $' + (w.priceRatio * p.spot).toFixed(2)
        + ' · session ' + w.dayIndex + '</title></circle>';
    }).join('') : '';
    var svg = '<svg viewBox="0 0 ' + W + ' ' + H + '" width="100%" role="img" aria-label="possible futures">'
      + gridY
      + '<path d="' + band + '" fill="var(--accent)" opacity="0.14"/>'
      + samples
      + '<path d="' + median + '" fill="none" stroke="var(--accent)" stroke-width="2.2"/>'
      + spotLine
      + levelLines
      + readOnlyPins
      + (opts.author ? '<g class="fan-pins" data-canvas-pins="true"></g>' : '')
      + '<text x="' + padL + '" y="' + (H - 6) + '" font-size="10" fill="var(--text-dim)">today</text>'
      + '<text x="' + (W - padR) + '" y="' + (H - 6) + '" text-anchor="end" font-size="10" fill="var(--text-dim)">+' + days + 'd</text>'
      + '</svg>';
    var wrap = el('div', { class: 'fan-chart', 'data-vocabulary': 'possiblefutures' },
      el('div', { html: svg }));
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
    var sampleChooser = null;
    (function wireSampleInspect() {
      var readout = null;
      wrap.firstChild.addEventListener('click', function (ev) {
        var g = ev.target && ev.target.closest ? ev.target.closest('g.fan-sample') : null;
        wrap.firstChild.querySelectorAll('g.fan-sample.selected').forEach(function (x) { x.classList.remove('selected'); });
        if (readout) { readout.remove(); readout = null; }
        if (!g) { if (opts.onSelectSample) opts.onSelectSample(null, null); return; }
        g.classList.add('selected');
        var si = parseInt(g.getAttribute('data-sample'), 10);
        if (sampleChooser) sampleChooser.querySelectorAll('button').forEach(function (button, index) {
          button.classList.toggle('active', index === si);
          button.setAttribute('aria-pressed', String(index === si));
        });
        var s = (p.samples || [])[si];
        if (!s || !s.length) return;
        if (opts.onSelectSample) opts.onSelectSample(si, s.slice());
        var end = s[s.length - 1], mn = Math.min.apply(null, s), mx = Math.max.apply(null, s);
        var pctNum = (end / p.spot - 1) * 100;
        readout = el('div', { class: 'muted small fan-path-readout' },
          'This future: ends at ' + end.toFixed(2) + ' (' + (pctNum >= 0 ? '+' : '') + pctNum.toFixed(1)
          + '%), travelled ' + mn.toFixed(2) + ' \u2013 ' + mx.toFixed(2) + ' along the way. Click the chart background to clear.');
        wrap.appendChild(readout);
      });
    })();
    // The scenario canvas layer: click the fan to pin "the price touches this level around this
    // session", drag a pin to adjust, double-click it to remove. Nothing navigates — the studio
    // that owns opts.author re-runs the fan through the outcomes API and repaints in place.
    if (opts.author) (function wireAuthor() {
      var author = opts.author;
      var svgEl = wrap.querySelector('svg');
      var layer = svgEl.querySelector('.fan-pins');
      wrap.classList.add('fan-chart-authorable');
      wrap.firstChild.setAttribute('data-canvas-fan', 'true');
      function pinPrice(pin) { return pin.priceRatio * p.spot; }
      function paintPins() {
        layer.innerHTML = (author.pins || []).map(function (pin) {
          var px = x(pin.dayIndex), py = y(pinPrice(pin));
          var tol = '';
          if (pin.tolerance) {
            var yHi = y((pin.priceRatio + pin.tolerance) * p.spot);
            var yLo = y((pin.priceRatio - pin.tolerance) * p.spot);
            tol = '<line x1="' + px.toFixed(1) + '" y1="' + yHi.toFixed(1) + '" x2="' + px.toFixed(1) + '" y2="' + yLo.toFixed(1) + '" class="fan-pin-tolerance"/>'
              + '<line x1="' + (px - 4).toFixed(1) + '" y1="' + yHi.toFixed(1) + '" x2="' + (px + 4).toFixed(1) + '" y2="' + yHi.toFixed(1) + '" class="fan-pin-tolerance"/>'
              + '<line x1="' + (px - 4).toFixed(1) + '" y1="' + yLo.toFixed(1) + '" x2="' + (px + 4).toFixed(1) + '" y2="' + yLo.toFixed(1) + '" class="fan-pin-tolerance"/>';
          }
          var labelY = py < 26 ? py + 18 : py - 9;
          return '<g class="fan-pin" data-day="' + pin.dayIndex + '">' + tol
            + '<circle cx="' + px.toFixed(1) + '" cy="' + py.toFixed(1) + '" r="11" class="fan-pin-hit"/>'
            + '<circle cx="' + px.toFixed(1) + '" cy="' + py.toFixed(1) + '" r="4.5" class="fan-pin-dot"/>'
            + '<text x="' + Math.min(W - 62, Math.max(padL + 46, px)).toFixed(1) + '" y="' + labelY.toFixed(1)
            + '" text-anchor="middle" class="fan-pin-label">$' + pinPrice(pin).toFixed(2) + ' · session ' + pin.dayIndex + '</text>'
            + '</g>';
        }).join('');
      }
      function point(ev) {
        var r = svgEl.getBoundingClientRect();
        return { fx: (ev.clientX - r.left) / Math.max(1, r.width) * W,
          fy: (ev.clientY - r.top) / Math.max(1, r.height) * H };
      }
      function dayAt(fx) { return Math.round((fx - padL) / Math.max(1, W - padL - padR) * Math.max(1, days)); }
      function priceAt(fy) { return lo + (hi - lo) * (1 - (fy - padT) / Math.max(1, H - padT - padB)); }
      var suppressClickUntil = 0;
      svgEl.addEventListener('click', function (ev) {
        if (Date.now() < suppressClickUntil) return;
        if (ev.target.closest && (ev.target.closest('.fan-pin') || ev.target.closest('g.fan-sample'))) return;
        var pt = point(ev);
        if (pt.fx < padL - 4 || pt.fx > W - padR + 4 || pt.fy < padT || pt.fy > H - padB) return;
        var day = Math.max(1, Math.min(Math.max(1, days), dayAt(pt.fx)));
        var ratio = priceAt(pt.fy) / p.spot;
        if (!(ratio > 0) || !isFinite(ratio)) return;
        ratio = Math.round(ratio * 10000) / 10000;
        var existing = (author.pins || []).find(function (other) { return other.dayIndex === day; });
        if (existing) existing.priceRatio = ratio;
        else {
          author.pins.push({ dayIndex: day, priceRatio: ratio, tolerance: null });
          author.pins.sort(function (a, b) { return a.dayIndex - b.dayIndex; });
        }
        paintPins();
        author.onChange(author.pins, existing ? 'move' : 'add');
      });
      svgEl.addEventListener('dblclick', function (ev) {
        var g = ev.target.closest && ev.target.closest('.fan-pin');
        if (!g) return;
        ev.preventDefault();
        var day = parseInt(g.getAttribute('data-day'), 10);
        var index = (author.pins || []).findIndex(function (other) { return other.dayIndex === day; });
        if (index < 0) return;
        author.pins.splice(index, 1);
        paintPins();
        author.onChange(author.pins, 'remove');
      });
      var drag = null;
      svgEl.addEventListener('pointerdown', function (ev) {
        var g = ev.target.closest && ev.target.closest('.fan-pin');
        if (!g) return;
        var day = parseInt(g.getAttribute('data-day'), 10);
        var pin = (author.pins || []).find(function (other) { return other.dayIndex === day; });
        if (!pin) return;
        drag = { pin: pin, moved: false };
        try { svgEl.setPointerCapture(ev.pointerId); } catch (e) { /* older engines */ }
        ev.preventDefault();
      });
      svgEl.addEventListener('pointermove', function (ev) {
        if (!drag) return;
        var pt = point(ev);
        var ratio = Math.max(0.01, priceAt(pt.fy) / p.spot);
        var day = Math.max(1, Math.min(Math.max(1, days), dayAt(pt.fx)));
        var taken = (author.pins || []).some(function (other) { return other !== drag.pin && other.dayIndex === day; });
        drag.pin.priceRatio = Math.round(ratio * 10000) / 10000;
        if (!taken) drag.pin.dayIndex = day;
        drag.moved = true;
        author.pins.sort(function (a, b) { return a.dayIndex - b.dayIndex; });
        paintPins();
      });
      function endDrag() {
        if (!drag) return;
        var moved = drag.moved;
        drag = null;
        if (moved) {
          suppressClickUntil = Date.now() + 250;
          author.onChange(author.pins, 'move');
        }
      }
      svgEl.addEventListener('pointerup', endDrag);
      svgEl.addEventListener('pointercancel', endDrag);
      paintPins();
    })();
    if ((p.samples || []).length) {
      sampleChooser = el('div', { class: 'fan-sample-chooser', role: 'group', 'aria-label': 'Inspect one sample future' },
        el('span', { class: 'muted small' }, 'Inspect a sample:'),
        (p.samples || []).map(function (_sample, index) {
          return el('button', { type: 'button', class: 'btn btn-sm btn-secondary', 'aria-pressed': 'false',
            onclick: function () {
              var path = wrap.querySelector('g.fan-sample[data-sample="' + index + '"] path');
              if (path) path.dispatchEvent(new MouseEvent('click', { bubbles: true }));
            } }, String(index + 1));
        }));
      wrap.appendChild(sampleChooser);
    }
    var terminal = p.decisionMap && p.decisionMap.terminal;
    var likelyLo = terminal ? terminal.p16 : p.endP10;
    var likelyHi = terminal ? terminal.p84 : p.endP90;
    var pctDown = Math.round((likelyLo / p.spot - 1) * 100), pctUp = Math.round((likelyHi / p.spot - 1) * 100);
    wrap.appendChild(el('div', { class: 'chip-row chart-summary' },
      UI.chip(UI.term('possiblefutures', 'Futures drawn'), String(p.paths)),
      UI.chip('Median ending price', String(terminal ? terminal.p50 : p.endP50)),
      UI.chip('Middle 68% end between', likelyLo + ' and ' + likelyHi),
      UI.chip('Range vs today', (pctDown >= 0 ? '+' : '') + pctDown + '% to ' + (pctUp >= 0 ? '+' : '') + pctUp + '%')));
    if (!opts.noNotes) (p.notes || []).forEach(function (n) { wrap.appendChild(el('div', { class: 'muted small' }, n)); });
    return wrap;
  }

  function pct0(v) { return Math.round((Number(v) || 0) * 100) + '%'; }
  function price(v) { return '$' + Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }); }

  /**
   * Collapse a path cloud into decisions. Scenario probabilities and option-market-implied
   * ranges are adjacent but never blended; an optional working position is priced on the exact
   * path receipt behind the chart.
   */
  function decisionView(p, level) {
    var d = p.decisionMap || {}, t = d.terminal || {};
    var beginner = level === 'beginner';
    var root = el('section', { class: 'scenario-decision', 'aria-label': 'Scenario decision summary' });
    root.appendChild(el('div', { class: 'scenario-decision-head' },
      el('div', {}, el('h3', {}, 'What this scenario says'),
        el('p', { class: 'muted small' }, beginner
          ? 'A range and direct odds you can use for a target, strike, or trade — not a prediction.'
          : 'Empirical counts on this exact seeded ensemble; terminal and first-touch questions stay distinct.')),
      el('span', { class: 'badge badge-modeled' }, 'SCENARIO MODEL')));
    if (p.receipt && p.receipt.anchorExecutable === false) {
      root.appendChild(UI.alertBox('caution', 'Analysis only — the anchor is not executable', [
        p.receipt.anchorLimitation || 'Refresh an executable quote before treating these modeled odds as a trade input.'
      ]));
    }
    root.appendChild(el('div', { class: 'scenario-facts' },
      el('div', { class: 'scenario-fact' }, el('span', {}, 'Likely ending range'),
        el('b', {}, price(t.p16) + ' – ' + price(t.p84)),
        el('small', {}, 'middle 68% of these futures')),
      el('div', { class: 'scenario-fact' }, el('span', {}, 'Median ending price'),
        el('b', {}, price(t.p50)), el('small', {}, 'half finished above, half below')),
      el('div', { class: 'scenario-fact' }, el('span', {}, 'Outer range'),
        el('b', {}, price(t.p5) + ' – ' + price(t.p95)),
        el('small', {}, 'middle 90%; tails still exist'))));

    var levels = d.levels || [];
    if (levels.length) {
      var levelWrap = el('div', { class: 'scenario-levels' });
      levels.forEach(function (lv) {
        var name = lv.key === 'target' ? 'Your price level'
          : lv.key.indexOf('breakeven') === 0 ? 'Position breakeven'
          : lv.key.indexOf('short-put') === 0 ? 'Short put strike'
          : lv.key.indexOf('short-call') === 0 ? 'Short call strike'
          : lv.key.replace(/[-_]/g, ' ');
        var dir = lv.direction === 'ABOVE' ? 'above' : 'below';
        levelWrap.appendChild(el('div', { class: 'scenario-level' },
          el('div', { class: 'scenario-level-name' }, el('b', {}, name), el('span', {}, price(lv.price))),
          el('div', { class: 'scenario-level-odds' },
            el('span', {}, el('b', {}, pct0(lv.endBeyondProbability)), ' end ' + dir),
            el('span', {}, el('b', {}, pct0(lv.touchProbability)), ' touch it')),
          el('div', { class: 'muted small' },
            'Touch estimate 95% interval ' + pct0(lv.touchCiLow) + '–' + pct0(lv.touchCiHigh)
            + (lv.medianFirstTouchDay == null ? '' : ' · median first touch day ' + lv.medianFirstTouchDay))));
      });
      root.appendChild(levelWrap);
    }

    if (p.marketImplied) {
      var m = p.marketImplied;
      var sw = Math.max(0.01, t.p84 - t.p16), mw = Math.max(0.01, m.p84 - m.p16);
      var rel = Math.round((sw / mw - 1) * 100);
      root.appendChild(el('div', { class: 'scenario-lens-compare' },
        el('div', { class: 'scenario-lens' }, el('span', { class: 'eyebrow' }, 'UNDER YOUR SCENARIO'),
          el('b', {}, price(t.p16) + ' – ' + price(t.p84)),
          el('small', {}, 'generated from your story, volatility and model settings')),
        el('div', { class: 'scenario-lens' }, el('span', { class: 'eyebrow' }, 'OPTIONS MARKET IMPLIED'),
          el('b', {}, price(m.p16) + ' – ' + price(m.p84)),
          el('small', {}, (m.expiration ? 'ATM IV from ' + m.expiration + ' · ' : '')
            + 'risk-neutral pricing, not a forecast')),
        el('p', { class: 'scenario-lens-takeaway' },
          rel === 0 ? 'Your scenario and the option market imply about the same central width.'
            : 'Your scenario is about ' + Math.abs(rel) + '% ' + (rel > 0 ? 'wider' : 'narrower')
              + ' than the option market’s central range.')));
      if (!beginner) root.appendChild(el('p', { class: 'muted small scenario-basis' }, m.basis));
    }

    root.appendChild(el('div', { class: 'scenario-sampling muted small' },
      'Sampling check: ' + p.paths.toLocaleString() + ' paths · worst-case 95% probability margin about ±'
      + (Number(d.maxProbabilityMargin95 || 0) * 100).toFixed(1) + ' percentage points · reproducible run saved.'));

    if (p.positionOutcome) {
      root.appendChild(el('div', { class: 'scenario-position-outcome' },
        el('div', { class: 'scenario-decision-head' },
          el('div', {}, el('h3', {}, 'Your working position on these exact futures'),
            el('p', { class: 'muted small' }, 'Same anchor, same path matrix, same receipt — the fan is now tied to a trade.'))),
        pnlView(p.positionOutcome, level)));
    }
    return root;
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
      UI.stat('Chance of profit', winPct + '%'),
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
        level === 'beginner' ? 'The shaded band holds the middle 8 of 10 outcomes, day by day; the line is the median P&L at each day.'
          : 'Pointwise p10–p90 P&L band with the pointwise median; day granularity.'));
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
    COVERED_STRANGLE: function (s, d) { return [L('BUY', 'STOCK', 0, 0), L('SELL', 'CALL', grid(s * 1.05, s), d), L('SELL', 'PUT', grid(s * 0.95, s), d)]; },
    COVERED_CALL_PUT_SPREAD: function (s, d) { return [L('BUY', 'STOCK', 0, 0), L('SELL', 'CALL', grid(s * 1.05, s), d), L('BUY', 'PUT', grid(s * 0.95, s), d), L('SELL', 'PUT', grid(s * 0.88, s), d)]; },
    COVERED_CALL_CALL_OVERLAY: function (s, d) { return [L('BUY', 'STOCK', 0, 0), L('SELL', 'CALL', grid(s * 1.04, s), d), L('BUY', 'CALL', grid(s * 1.10, s), d)]; },
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
    var plan = window.PlanStore && PlanStore.active();
    var candidate = plan && PlanStore.ui(plan.id).selectedCandidate;
    var legs = candidate && candidate.legs;
    if (!legs || !legs.length) return null;
    var out = [];
    var base = App.Market && App.Market.simTime ? new Date(App.Market.simTime) : new Date();
    for (var i = 0; i < legs.length; i++) {
      var lg = legs[i];
      if (lg.stock || lg.type === 'STOCK') { out.push({ action: lg.action, type: 'STOCK', strike: 0, expiryDay: 0,
        ratio: lg.ratio, multiplier: lg.multiplier }); continue; }
      var days = 30;
      if (lg.expiration) {
        var ms = new Date(lg.expiration + 'T16:00:00-04:00') - base;
        days = Math.max(1, Math.round(ms / 86400000 * 5 / 7)); // calendar → ~trading days
      }
      out.push({ action: lg.action, type: lg.type, strike: parseFloat(lg.strike), expiryDay: days,
        ratio: lg.ratio, multiplier: lg.multiplier });
    }
    return out;
  }

  /** Exact listed package and displayed entry, when Research and the ticket share a symbol. */
  function workingPosition(symbol) {
    var plan = window.PlanStore && PlanStore.active();
    var candidate = plan && PlanStore.ui(plan.id).selectedCandidate;
    if (!plan || String(plan.symbol || '').toUpperCase() !== String(symbol || '').toUpperCase()) return null;
    var sourceLegs = candidate && candidate.legs && candidate.legs.length ? candidate.legs : null;
    if (!sourceLegs || !sourceLegs.length) return null;
    var entryNet = candidate && typeof candidate.entryNetPremiumCents === 'number'
      ? candidate.entryNetPremiumCents : null;
    return App.outcomePosition(candidate.strategy, sourceLegs,
      candidate.qty, entryNet == null ? null : -entryNet);
  }

  function candidateLegs(candidate) {
    var base = App.Market && App.Market.simTime ? new Date(App.Market.simTime) : new Date();
    return (candidate.legs || []).map(function (lg) {
      if (lg.stock || lg.type === 'STOCK') {
        return { action: lg.action, type: 'STOCK', strike: 0, expiryDay: 0,
          ratio: lg.ratio, multiplier: lg.multiplier };
      }
      var calDays = lg.expiration ? Math.max(1, Math.ceil((new Date(lg.expiration + 'T16:00:00-04:00') - base) / 86400000)) : 30;
      return { action: lg.action, type: lg.type, strike: parseFloat(lg.strike),
        expiryDay: Math.max(1, Math.round(calDays * 5 / 7)), ratio: lg.ratio, multiplier: lg.multiplier };
    });
  }

  /** Resolve the horizon only from an exact option expiry or an explicitly carried stock-only
   * horizon. A missing contract date is not permission to manufacture a month. */
  function realisticHorizon(candidate, legs, opts) {
    var source = candidate && candidate.legs || [];
    var optionIndexes = [];
    source.forEach(function (leg, index) {
      if (!(leg.stock || String(leg.type || '').toUpperCase() === 'STOCK')) optionIndexes.push(index);
    });
    if (!optionIndexes.length) {
      var explicit = Number(opts && opts.horizonDays != null ? opts.horizonDays : candidate && candidate.horizonDays);
      if (Number.isFinite(explicit) && explicit >= 1 && explicit <= 756) {
        return { days: Math.round(explicit), source: 'declared horizon', error: null };
      }
      return { days: null, source: null,
        error: 'A stock-only scenario needs an explicit declared horizon; no 21-session month was assumed.' };
    }
    var days = [];
    for (var i = 0; i < optionIndexes.length; i++) {
      var index = optionIndexes[i];
      var original = source[index] || {};
      var expiry = String(original.expiration || '').trim();
      var derived = legs && legs[index] && Number(legs[index].expiryDay);
      if (!expiry || !Number.isFinite(Date.parse(expiry + 'T16:00:00-04:00'))
          || !Number.isFinite(derived) || derived < 1) {
        return { days: null, source: null,
          error: 'Every option needs an exact valid expiration before scenario outcomes can choose a horizon.' };
      }
      days.push(derived);
    }
    return { days: Math.max(1, Math.min.apply(Math, days)), source: 'earliest exact option expiration', error: null };
  }

  /** One IV value from server-priced Canvas output, an explicit authored node path, or the exact
   * stored fan receipt. Null means unavailable; callers must never turn it into a hidden 30%. */
  function canvasIvValue(preview, model, day) {
    model = model || {};
    var nodes = (model.ivNodes || []).slice().filter(function (node) {
      return node && Number.isFinite(Number(node.dayIndex)) && Number(node.dayIndex) >= 0
        && Number.isFinite(Number(node.atmIv)) && Number(node.atmIv) >= .01 && Number(node.atmIv) <= 4;
    }).sort(function (a, b) { return Number(a.dayIndex) - Number(b.dayIndex); });
    var canvas = preview && preview.canvas;
    var row = canvas && (canvas.underlying || []).find(function (x) { return Number(x.day) === Number(day); });
    if (row && Number.isFinite(Number(row.atmIv)) && Number(row.atmIv) >= .01) return Number(row.atmIv);
    if (nodes.length) {
      var d = Number(day) || 0;
      if (d <= Number(nodes[0].dayIndex)) return Number(nodes[0].atmIv);
      if (d >= Number(nodes[nodes.length - 1].dayIndex)) return Number(nodes[nodes.length - 1].atmIv);
      for (var i = 1; i < nodes.length; i++) {
        if (d > Number(nodes[i].dayIndex)) continue;
        var left = nodes[i - 1], right = nodes[i];
        var w = (d - Number(left.dayIndex)) / (Number(right.dayIndex) - Number(left.dayIndex));
        return Number(left.atmIv) + w * (Number(right.atmIv) - Number(left.atmIv));
      }
    }
    var receiptIv = Number(preview && preview.receipt && preview.receipt.spec
      && preview.receipt.spec.volAnnual);
    return Number.isFinite(receiptIv) && receiptIv >= .01 ? receiptIv : null;
  }

  /**
   * Theoretical payoff limits stay visible, but these four distributions answer the trader's
   * practical question: what does this exact package tend to do in plausible market shapes?
   * It calls the existing strategy simulator; there is no parallel risk or pricing engine.
   */
  var realisticOutcomeCache = new Map();

  function realisticOutcomeKey(symbol, candidate, opts) {
    var market = [App.state && App.state.world || '', App.state && App.state.dataset || ''];
    var legs = (candidate.legs || []).map(function (leg) {
      return [leg.action, leg.type, leg.stock === true, leg.strike, leg.expiration, leg.ratio, leg.multiplier];
    });
    return JSON.stringify([market, symbol, candidate.strategy, candidate.qty || 1,
      candidate.entryNetPremiumCents, legs,
      opts && opts.horizonDays != null ? Number(opts.horizonDays) : null]);
  }

  function realisticOutcomeStateKey(key) {
    var hash = 2166136261;
    for (var i = 0; i < key.length; i++) {
      hash ^= key.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return 'realistic-outcomes-' + (hash >>> 0).toString(36);
  }

  function realisticOutcomes(symbol, candidate, opts) {
    opts = opts || {};
    symbol = String(symbol || '').toUpperCase();
    candidate = candidate || {};
    var beginner = Learn.currentLevel() === 'beginner';
    var key = realisticOutcomeKey(symbol, candidate, opts);
    var entry = realisticOutcomeCache.get(key);
    if (!entry) {
      entry = { status: 'idle', rows: [], evidence: null, usedHistory: false, views: new Set() };
      realisticOutcomeCache.set(key, entry);
      if (realisticOutcomeCache.size > 48) realisticOutcomeCache.delete(realisticOutcomeCache.keys().next().value);
    }

    var strip = el('div', { class: 'realistic-outcome-strip', 'aria-live': 'polite' });
    var detail = null;
    var wrap = el('section', { class: 'realistic-outcome-summary', 'data-realistic-outcomes': candidate.strategy || 'position' },
      el('div', { class: 'realistic-outcome-heading' },
        el('div', {}, el('span', { class: 'eyebrow' }, 'SCENARIO CHECK'),
          el('h4', {}, beginner ? 'What this trade could do' : 'Calm / up / down / choppy'))),
      strip);
    var view = { wrap: wrap, strip: strip, detail: null, connected: false };
    entry.views.add(view);

    function activeViews() {
      var active = false;
      entry.views.forEach(function (item) {
        if (item.wrap.isConnected) { item.connected = true; active = true; }
        else if (item.connected) entry.views.delete(item);
      });
      return active;
    }

    function detailContent() {
      var holder = el('div', { class: 'realistic-outcomes' });
      view.detail = holder;
      paintView(view);
      if (entry.status === 'idle' || entry.status === 'error') startRun();
      return holder;
    }

    wrap.appendChild(UI.expandable('Full scenario distribution and assumptions', detailContent,
      { stateKey: realisticOutcomeStateKey(key) }));

    function paintStrip(host) {
      host.replaceChildren();
      if (entry.status === 'running') {
        host.appendChild(UI.spinner('Running the exact package once…'));
        return;
      }
      if (entry.status === 'done') {
        entry.rows.forEach(function (row) {
          host.appendChild(el('div', { class: 'realistic-strip-case', title: row.error
            ? row.error : row.def.label + ': ' + Math.round(row.result.winRatePct) + '% profitable' },
          el('span', { class: 'muted small' }, row.def.label),
          row.error ? el('strong', { class: 'muted' }, 'Unavailable') : UI.pnlSpan(row.result.p50Cents)));
        });
        return;
      }
      var message = entry.status === 'error' ? entry.error : 'Run once for this exact package; repaints reuse the stored result.';
      host.appendChild(el('span', { class: entry.status === 'error' ? 'loss small' : 'muted small' }, message));
      host.appendChild(el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: startRun },
        entry.status === 'error' ? 'Try scenarios again' : 'Run scenarios'));
    }

    function paintDetail(host) {
      host.replaceChildren();
      if (entry.status === 'running') { host.appendChild(UI.spinner('Running the exact contracts through shared scenarios…')); return; }
      if (entry.status !== 'done') {
        host.appendChild(el('p', { class: entry.status === 'error' ? 'loss' : 'muted' },
          entry.status === 'error' ? entry.error : 'Run the scenario check to fill this distribution.'));
        return;
      }
      var provenance = String(entry.evidence && entry.evidence.provenance || '').toUpperCase();
      var historyBasis = provenance === 'DEMO' ? 'fabricated Demo history'
        : provenance === 'SIMULATED' ? 'the simulated session’s generated history'
        : provenance === 'MODELED' ? 'modeled history'
        : provenance === 'OBSERVED' || provenance === 'BROKER' ? 'observed recent history'
        : 'eligible recent history from this lane';
      host.appendChild(el('p', { class: 'muted small' },
        'The theoretical payoff limits remain the structural truth. These distributions model the exact listed package and its displayed opening price. '
        + (entry.usedHistory ? 'Volatility is scaled from ' + historyBasis + ' for ' + symbol + '.'
          : 'No usable candle history was available, so the server calibrated from the option market.')
        + ' They complement those limits; they never replace them.'));
      var grid = el('div', { class: 'realistic-grid' });
      entry.rows.forEach(function (row) {
        if (row.error) {
          grid.appendChild(el('div', { class: 'realistic-case' }, el('b', {}, row.def.label),
            el('div', { class: 'muted small' }, row.error)));
          return;
        }
        var result = row.result;
        grid.appendChild(el('div', { class: 'realistic-case' }, el('b', {}, row.def.label),
          el('div', { class: 'realistic-typical' }, UI.pnlSpan(result.p50Cents)),
          el('div', { class: 'muted small' }, Math.round(result.winRatePct) + '% profitable'),
          el('div', { class: 'muted small' }, '1-in-20 range ' + UI.fmtMoneyCompact(result.p5Cents)
            + ' to ' + UI.fmtMoneyCompact(result.p95Cents))));
      });
      host.appendChild(grid);
    }

    function paintView(item) {
      paintStrip(item.strip);
      if (item.detail) paintDetail(item.detail);
    }

    function paintAll() {
      entry.views.forEach(function (item) { paintView(item); });
    }

    function startRun() {
      if (entry.status === 'running' || entry.status === 'done') return;
      if (!symbol || !(candidate.legs || []).length) {
        entry.status = 'error'; entry.error = 'A symbol and exact contracts are needed for scenario outcomes.';
        paintAll(); return;
      }
      entry.status = 'running'; entry.error = null; paintAll();
      var sourceLegs = candidate.legs || [];
      var legs = candidateLegs(candidate);
      var exactExpirations = sourceLegs.map(function (leg) {
        return leg.stock || String(leg.type || '').toUpperCase() === 'STOCK' ? '' : String(leg.expiration || '');
      });
      var hasExactContracts = exactExpirations.every(function (exp, idx) {
        var leg = sourceLegs[idx] || {};
        return leg.stock || String(leg.type || '').toUpperCase() === 'STOCK' || !!exp;
      });
      var resolvedHorizon = realisticHorizon(candidate, legs, opts);
      if (resolvedHorizon.error) {
        entry.status = 'error'; entry.error = resolvedHorizon.error; paintAll(); return;
      }
      var days = resolvedHorizon.days;
      var cases = [
        { key: 'calm', label: 'Calm / narrow', shape: 'CHOP', drift: 0, mult: 0.6, seed: 41101 },
        { key: 'up', label: 'Steady rise', shape: 'GRIND_UP', drift: 0.15, mult: 1.0, seed: 41102 },
        { key: 'down', label: 'Steady decline', shape: 'GRIND_DOWN', drift: -0.15, mult: 1.0, seed: 41103 },
        { key: 'chop', label: 'Wide / choppy', shape: 'CHOP', drift: 0, mult: 1.35, seed: 41104 }
      ];
      entry.promise = historicalVol(symbol).then(async function (calibration) {
        var hv = calibration && calibration.vol;
        entry.evidence = calibration && calibration.evidence;
        entry.usedHistory = !!hv;
        var rows = [];
        for (var i = 0; i < cases.length; i++) {
          if (!activeViews()) { entry.status = 'idle'; entry.rows = []; return; }
          var scenario = cases[i];
          var vol = hv ? hv * scenario.mult : 0;
          var spec = { model: 'GBM', shape: scenario.shape, horizonDays: days, stepsPerDay: 4,
            driftAnnual: scenario.drift, volAnnual: vol, jumpsPerYear: 0, jumpMean: 0, jumpVol: 0,
            tailNu: 6, heston: null, seed: scenario.seed, paths: 180 };
          try {
            var position = App.outcomePosition(candidate.strategy, legs, candidate.qty,
              typeof candidate.entryNetPremiumCents === 'number' ? -candidate.entryNetPremiumCents : null,
              hasExactContracts ? exactExpirations : null);
            var result = await App.evaluateOutcome('POSITION', 'PARAMETRIC', symbol,
              { position: position, over: spec, iv: null });
            rows.push({ def: scenario, result: result });
          } catch (e) {
            rows.push({ def: scenario, error: e.message || 'Unavailable' });
          }
          paintAll();
        }
        entry.rows = rows; entry.status = 'done'; paintAll();
      }).catch(function (error) {
        entry.status = 'error'; entry.error = error.message || 'Scenario outcomes are unavailable.'; paintAll();
      });
    }

    paintView(view);
    if (opts.autoRun === true || candidate.selected === true) setTimeout(function () {
      if (wrap.isConnected) startRun();
    }, 0);
    return wrap;
  }

  /* ---------------------------------------------------------------------------------------------
   * The scenario canvas studio (plan workspace · outcomes band). Program ONE R4.5:
   * draw a believed path onto the Plan's stored fan — "the price touches $X around session N" —
   * re-run the SAME simulation spine in place through the outcomes API, read the waypoint-fill
   * honesty label, and freeze named authored scenarios with lineage to the exact base fan.
   * Beginner/Expert is a lens, never a fork: the same canvas, plainer words at Beginner;
   * tolerance editing and the fingerprint lineage line at Expert.
   * ------------------------------------------------------------------------------------------- */
  function canvasStudio(cfg) {
    var planRef = cfg.planRef;
    var beginner = Learn.currentLevel() === 'beginner';
    var ui = PlanStore.ui(planRef.plan.id);
    var state = ui.canvas = ui.canvas || {};
    state.pins = state.pins || [];
    state.compareMode = state.compareMode || 'YOURS_VS_PROPOSED';
    state.selectedPositions = state.selectedPositions || [];
    var runSeq = 0, runTimer = null;
    var railHost = null, savedHost = null;

    var status = el('div', { class: 'plan-outcome-action-status scenario-canvas-status', 'aria-live': 'polite' });
    var body = el('div', { class: 'scenario-canvas-body' });
    var root = el('section', { class: 'card plan-scenario-canvas', id: 'plan-scenario-canvas' },
      UI.cardHeader(el('span', {}, 'Scenario Canvas', UI.info('authoredScenario')),
        el('span', { class: 'badge badge-modeled' }, 'SCENARIO MODEL')),
      el('p', { class: 'muted' }, beginner
        ? 'Draw the price story, decide how uncertainty changes, and compare your real or practice positions on the same possible futures.'
        : 'Author price×session and ATM-IV paths, declare the surface and settlement rules, then reprice canonical same-symbol packages on one stored ensemble.'),
      status, body);

    function setStatus(node) { status.replaceChildren(); if (node) status.appendChild(node); }
    function spot() { return state.preview ? state.preview.spot : 0; }
    function horizon() {
      var spec = state.preview && state.preview.receipt && state.preview.receipt.spec;
      if (spec && spec.horizonDays) return spec.horizonDays;
      return state.preview && state.preview.bands ? state.preview.bands.length - 1 : null;
    }
    function sessionLabel(day) {
      var iso = state.preview && state.preview.sessionDates && state.preview.sessionDates[day - 1];
      if (!iso) return 'session ' + day + (horizon() ? ' of ' + horizon() : '');
      var parts = iso.split('-');
      var d = new Date(+parts[0], +parts[1] - 1, +parts[2]);
      var wd = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][d.getDay()];
      var mo = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getMonth()];
      return 'session ' + day + ' — ' + wd + ' ' + mo + ' ' + d.getDate();
    }
    function adoptSpec(spec) {
      if (!spec) return null;
      return { model: spec.model, shape: spec.shape, horizonDays: spec.horizonDays,
        stepsPerDay: spec.stepsPerDay, driftAnnual: spec.driftAnnual, volAnnual: spec.volAnnual,
        jumpsPerYear: spec.jumpsPerYear, jumpMean: spec.jumpMean, jumpVol: spec.jumpVol,
        tailNu: spec.tailNu, heston: spec.heston || null, seed: spec.seed, paths: spec.paths };
    }
    function specWithPins() {
      var base = state.loadedSpec || adoptSpec(state.preview && state.preview.receipt && state.preview.receipt.spec);
      if (!base) throw new Error('Run the possible-futures fan before authoring on it.');
      var spec = adoptSpec(base);
      spec.waypoints = state.pins.map(function (pin) {
        return { dayIndex: pin.dayIndex, priceRatio: pin.priceRatio,
          tolerance: pin.tolerance != null && pin.tolerance > 0 ? pin.tolerance : null };
      });
      return spec;
    }
    function defaultCanvasModel() {
      return { calendar: 'NYSE', dividendYieldAnnual: null,
        dividendBasis: 'Dividend source unavailable; pricing uses 0% and discloses that limitation.',
        skewVolPerLogMoneyness: 0, termVolPerSqrtYear: 0,
        surfaceDynamics: 'STICKY_MONEYNESS', settlementPolicy: 'CASH_INTRINSIC',
        exercisePolicy: 'EXPIRATION_ONLY', ivNodes: [], template: null };
    }
    function canvasModel() {
      var model = state.canvasModel || defaultCanvasModel();
      model.ivNodes = (model.ivNodes || []).slice().sort(function (a, b) { return a.dayIndex - b.dayIndex; });
      return model;
    }
    function cloneCanvasModel() {
      return JSON.parse(JSON.stringify(canvasModel()));
    }
    function setIvNode(day, iv) {
      var model = cloneCanvasModel();
      model.ivNodes = (model.ivNodes || []).filter(function (node) { return node.dayIndex !== day; });
      if (isFinite(iv) && iv >= 0.01 && iv <= 4) model.ivNodes.push({ dayIndex: day, atmIv: iv });
      model.ivNodes.sort(function (a, b) { return a.dayIndex - b.dayIndex; });
      // Keep the template receipt: it identifies the real input that seeded the price path.
      // The exact edited IV nodes are separately fingerprinted in this canvas model.
      state.canvasModel = model;
    }
    function ivAt(day) {
      return canvasIvValue(state.preview, canvasModel(), day);
    }
    function updateCanvasModel(mutator) {
      var model = cloneCanvasModel();
      mutator(model);
      state.canvasModel = model;
      scheduleRun(0);
    }
    function applyIvPreset(kind) {
      var model = cloneCanvasModel();
      var hz = Math.max(1, horizon() || 1);
      var available = ivAt(0);
      if (!(Number(available) >= .01)) {
        setStatus(UI.alertBox('warn', 'Starting IV is unavailable', [
          'Enter an explicit ATM IV for a session or run a fan with a server-owned volatility input before applying a guide.'
        ]));
        return;
      }
      var start = Math.max(.01, Math.min(4, available));
      var eventDay = Math.max(1, Math.min(hz - 1, Math.round(hz / 3)));
      if (kind === 'STABLE') model.ivNodes = [{ dayIndex: 0, atmIv: start }, { dayIndex: hz, atmIv: start }];
      else if (kind === 'FADES') model.ivNodes = [{ dayIndex: 0, atmIv: start }, { dayIndex: hz, atmIv: Math.max(.01, start * .80) }];
      else if (kind === 'RISES') model.ivNodes = [{ dayIndex: 0, atmIv: start }, { dayIndex: hz, atmIv: Math.min(4, start * 1.25) }];
      else {
        model.ivNodes = [{ dayIndex: 0, atmIv: Math.min(4, start * 1.20) },
          { dayIndex: eventDay, atmIv: Math.min(4, start * 1.20) }];
        if (eventDay < hz) model.ivNodes.push({ dayIndex: eventDay + 1, atmIv: Math.max(.01, start * .72) });
        else model.ivNodes[eventDay === 0 ? 0 : 1].atmIv = Math.max(.01, start * .72);
      }
      state.ivPreset = kind;
      state.canvasModel = model;
      scheduleRun(0);
    }
    async function applyTemplate(request, button) {
      if (button) { button.disabled = true; button.setAttribute('aria-busy', 'true'); }
      setStatus(UI.spinner('Seeding the same canvas from dated inputs…'));
      try {
        var spec = specWithPins();
        var live = await PlanStore.get(planRef.plan.id, true); planRef.plan = live;
        var out = await PlanStore.runEnsemble(live, {
          over: spec, canvas: cloneCanvasModel(), template: request
        });
        adoptRun(out, false);
        state.pins = (out.preview.waypoints || []).map(function (w) {
          return { dayIndex: w.dayIndex, priceRatio: w.priceRatio,
            tolerance: w.tolerance != null ? w.tolerance : null };
        });
        state.loadedSpec = adoptSpec(out.preview && out.preview.receipt && out.preview.receipt.spec);
        syncEvidencePin(out, specWithPins());
        setStatus(UI.alertBox('ok', 'Template applied to this canvas', [
          'Its dated source and no-hindsight boundary are in the immutable model receipt below.'
        ]));
        paint();
      } catch (e) {
        setStatus(UI.alertBox('danger', 'This template could not be applied honestly', [
          (e && e.message) || 'Its required dated inputs are unavailable in this market lane.'
        ]));
      } finally {
        if (button) { button.disabled = false; button.removeAttribute('aria-busy'); }
      }
    }
    function adoptRun(out, restored) {
      if (!out || !out.preview) return;
      state.preview = out.preview;
      state.ensembleRef = out.ensemble || null;
      state.canvasModel = out.preview.canvasModel || state.canvasModel || defaultCanvasModel();
      var pins = (out.preview.waypoints || []).map(function (w) {
        return { dayIndex: w.dayIndex, priceRatio: w.priceRatio, tolerance: w.tolerance != null ? w.tolerance : null };
      });
      if (restored && pins.length && !state.pins.length) state.pins = pins;
      // An unpinned fan is the natural authoring base; keep the first base once pins exist so
      // the saved receipt names the fan the path was actually drawn on.
      if (out.ensemble && (!pins.length || !state.baseEnsembleId)) {
        state.baseEnsembleId = out.ensemble.id;
        state.baseFingerprint = out.ensemble.fingerprint;
      }
    }
    function syncEvidencePin(out, spec) {
      // One simulation spine (Program ONE §2.3): the authored fan IS the Plan's current stored
      // simulation, so Evidence and the position lenses quote it instead of a stale pin.
      var evidence = PlanStore.ui(planRef.plan.id).evidence = PlanStore.ui(planRef.plan.id).evidence || {};
      evidence.planEnsembleId = out.ensemble && out.ensemble.id;
      evidence.planEnsembleFingerprint = out.ensemble && out.ensemble.fingerprint;
      evidence.whatifResults = evidence.whatifResults || {};
      evidence.whatifResults[planRef.plan.symbol] = { preview: out.preview, ensemble: out.ensemble || null,
        spec: spec, canvas: cloneCanvasModel() };
      if (window.Workspace && Workspace.save) Workspace.save();
    }

    function scheduleRun(delay) {
      if (runTimer) clearTimeout(runTimer);
      // Invalidate an already-running request at the moment newer authoring input arrives,
      // not only when the debounce timer eventually starts its replacement. Otherwise the old
      // response can repaint the Canvas with its captured model and erase a just-typed node.
      var scheduledSeq = ++runSeq;
      runTimer = setTimeout(function () { runTimer = null; rerun(scheduledSeq); }, delay == null ? 350 : delay);
    }
    async function rerun(seq) {
      if (seq == null) seq = ++runSeq;
      setStatus(UI.spinner(state.pins.length
        ? 'Re-running the stored fan through your ' + state.pins.length + ' waypoint' + (state.pins.length === 1 ? '' : 's') + '…'
        : 'Re-running the plain fan…'));
      try {
        var spec = specWithPins();
        var live = await PlanStore.get(planRef.plan.id, true); planRef.plan = live;
        var out = await PlanStore.runEnsemble(live, { over: spec, canvas: cloneCanvasModel() });
        if (seq !== runSeq || !root.isConnected) return;
        adoptRun(out, false);
        syncEvidencePin(out, spec);
        if (state.pendingNote) { setStatus(UI.alertBox('caution', state.pendingNote.title, state.pendingNote.items)); state.pendingNote = null; }
        else setStatus(null);
        paint();
      } catch (e) {
        if (seq !== runSeq) return;
        var detail = (e && e.message) || 'Retry the run, then check Data if market inputs remain unavailable.';
        var box = UI.alertBox('danger', 'Could not re-run the fan with these waypoints', [detail]);
        var hz = horizon();
        if (hz && /beyond the scenario horizon/.test(detail)) {
          var kept = state.pins.filter(function (pin) { return pin.dayIndex <= hz; });
          box.appendChild(el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
            state.pins = kept; paint(); scheduleRun(0);
          } }, 'Keep the ' + kept.length + ' waypoint' + (kept.length === 1 ? '' : 's') + ' inside the ' + hz + '-session horizon and re-run'));
        }
        setStatus(box);
      }
    }

    async function runFirstFan(button) {
      if (button) button.disabled = true;
      setStatus(UI.spinner('Generating this Plan’s possible futures…'));
      try {
        var live = await PlanStore.get(planRef.plan.id, true); planRef.plan = live;
        var out = await PlanStore.runEnsemble(live, { canvas: cloneCanvasModel() });
        adoptRun(out, false);
        syncEvidencePin(out, out.preview && out.preview.receipt && out.preview.receipt.spec);
        setStatus(null);
        paint();
      } catch (e) {
        if (button) button.disabled = false;
        setStatus(UI.alertBox('danger', 'Could not generate the fan', [(e && e.message) || 'Check the market data lane, then retry.']));
      }
    }

    async function loadSaved() {
      var out = await API.getFresh('/api/plans/' + planRef.plan.id + '/scenarios');
      state.saved = out.scenarios || [];
    }

    async function saveAuthored(button) {
      if (!state.pins.length) return;
      button.disabled = true; button.setAttribute('aria-busy', 'true');
      try {
        var live = await PlanStore.get(planRef.plan.id, true); planRef.plan = live;
        var out = await API.post('/api/plans/' + live.id + '/scenarios', {
          expectedVersion: live.version, title: state.title || null,
          // The current pinned ensemble carries the exact IV surface, rate, calendar and
          // settlement receipt. Freeze THAT artifact, not only the earlier unpinned fan.
          baseEnsembleId: state.ensembleRef && state.ensembleRef.id || state.baseEnsembleId || null,
          over: specWithPins() });
        UI.toast('Scenario saved' + (out.scenario && out.scenario.title ? ': ' + out.scenario.title : ''), 'info');
        await loadSaved();
        paintSaved();
      } catch (e) {
        setStatus(UI.alertBox('danger', 'Could not save this scenario',
          [(e && e.message) || 'Re-run the fan, then save again.']));
      } finally { button.disabled = false; button.removeAttribute('aria-busy'); }
    }

    function applyScenario(saved) {
      var hz = horizon();
      var pins = (saved.waypoints || []).map(function (w) {
        return { dayIndex: w.dayIndex, priceRatio: w.priceRatio, tolerance: w.tolerance != null ? w.tolerance : null };
      });
      var kept = hz ? pins.filter(function (pin) { return pin.dayIndex <= hz; }) : pins;
      if (!kept.length) {
        setStatus(UI.alertBox('caution', 'No waypoint fits the current horizon',
          [(saved.staleness ? saved.staleness + ' ' : '') + 'All ' + pins.length + ' waypoints sit beyond the current '
            + hz + '-session horizon. Extend the Plan horizon, or author new pins here.']));
        return;
      }
      state.loadedSpec = adoptSpec(saved.spec);
      if (saved.canvas) state.canvasModel = JSON.parse(JSON.stringify(saved.canvas));
      state.pins = kept;
      var notes = [];
      if (saved.staleness) notes.push(saved.staleness);
      if (kept.length < pins.length) {
        notes.push((pins.length - kept.length) + ' of ' + pins.length + ' waypoints sat beyond the current '
          + hz + '-session horizon and were left off.');
      }
      if (notes.length) state.pendingNote = { title: 'Re-anchored to the current fan', items: notes };
      paint();
      scheduleRun(0);
    }

    function fillBanner(fill) {
      var host = el('div', { class: 'canvas-fill-label', 'data-fill': fill });
      if (fill === 'EXACT_CONDITIONAL') {
        var okBox = UI.alertBox('ok', 'Exact conditional paths — ' + (beginner
          ? 'these futures are the model’s own randomness, guaranteed to pass through your waypoints. Nothing was bent to fake it.'
          : 'the model’s own randomness, pinned through your waypoints (Gaussian conditioning, not resampling).'));
        okBox.appendChild(UI.info('waypointFill'));
        host.appendChild(okBox);
      } else if (fill === 'GUIDED_INTERPOLATION') {
        var cautionBox = UI.alertBox('caution', 'Guided interpolation — ' + (beginner
          ? 'to pass through your waypoints, each path was bent toward them. This model’s sudden jumps and fat tails are approximated near the pins — treat these odds as a guide, not exact.'
          : 'paths are bent through your waypoints; this model’s fat tails/jumps are approximated near the pins, not exact. Odds near pinned sessions inherit that approximation.'));
        cautionBox.appendChild(UI.info('waypointFill'));
        host.appendChild(cautionBox);
      } else {
        host.appendChild(el('p', { class: 'muted small' },
          (beginner ? 'No waypoints yet — this is the plain fan. ' : 'Plain Monte Carlo — no authored waypoints. ')
          + 'Click the chart to pin “the price touches this level around this session.”',
          UI.info('waypointFill')));
      }
      return host;
    }

    function removePin(pin) {
      var index = state.pins.indexOf(pin);
      if (index < 0) return;
      state.pins.splice(index, 1);
      paint();
      scheduleRun();
    }

    function paintRail() {
      if (!railHost) return;
      railHost.replaceChildren();
      railHost.appendChild(el('div', { class: 'field-label' },
        UI.term('waypoint', 'Your waypoints (' + state.pins.length + ')')));
      if (!state.pins.length) {
        railHost.appendChild(el('p', { class: 'muted small' }, 'None yet — click the chart to place the first one.'));
      }
      state.pins.forEach(function (pin) {
        var price = pin.priceRatio * spot();
        var pct = (pin.priceRatio - 1) * 100;
        var tolControl = null;
        if (!beginner) {
          var tolId = 'plan-canvas-tol-' + pin.dayIndex;
          var tol = el('input', { type: 'number', id: tolId, min: '0', max: '50', step: '0.5',
            value: pin.tolerance != null ? String(Math.round(pin.tolerance * 1000) / 10) : '',
            'aria-label': 'Acceptance band around the ' + sessionLabel(pin.dayIndex) + ' level, as ± percent of today’s price' });
          tol.addEventListener('change', function () {
            var v = parseFloat(tol.value);
            pin.tolerance = isFinite(v) && v > 0 ? v / 100 : null;
            paint();
            scheduleRun();
          });
          tolControl = el('span', { class: 'scenario-pin-tol' }, tol, el('span', { class: 'muted small' }, '±%'));
        }
        railHost.appendChild(el('div', { class: 'scenario-pin-row', 'data-pin-day': String(pin.dayIndex) },
          el('div', { class: 'scenario-pin-desc' },
            el('b', {}, '$' + price.toFixed(2)),
            el('span', { class: 'muted small' }, ' (' + (pct >= 0 ? '+' : '') + pct.toFixed(1) + '% vs today) · '
              + sessionLabel(pin.dayIndex))),
          tolControl,
          el('button', { type: 'button', class: 'btn btn-sm btn-ghost scenario-pin-remove',
            'aria-label': 'Remove the waypoint at ' + sessionLabel(pin.dayIndex),
            onclick: function () { removePin(pin); } }, '×')));
      });
      var nameId = 'plan-canvas-scenario-name';
      var name = el('input', { type: 'text', id: nameId, value: state.title || '', maxlength: '120',
        placeholder: beginner ? 'e.g. Dips early, back by expiry' : 'Scenario name' });
      name.addEventListener('input', function () { state.title = name.value; });
      var save = el('button', { type: 'button', class: 'btn', id: 'plan-canvas-save',
        disabled: state.pins.length ? null : 'disabled',
        title: state.pins.length ? null : 'Place at least one waypoint first.',
        onclick: function () { saveAuthored(this); } }, 'Save this scenario');
      railHost.appendChild(el('div', { class: 'field scenario-canvas-save' },
        el('label', { for: nameId }, 'Name this scenario'), name, save));
      if (!beginner && state.baseFingerprint) {
        railHost.appendChild(el('p', { class: 'muted small scenario-canvas-lineage' },
          'Authored on stored fan #' + String(state.baseFingerprint).slice(0, 6)
          + (state.ensembleRef && state.ensembleRef.fingerprint
            ? ' · now viewing #' + String(state.ensembleRef.fingerprint).slice(0, 6) : '')
          + ' — the receipt of the exact simulation your pins condition.'));
      }
    }

    function canvasSection(kicker, title, copy) {
      return el('div', { class: 'scenario-canvas-section-head' },
        el('div', {}, el('span', { class: 'eyebrow' }, kicker), el('h4', {}, title)),
        copy ? el('p', { class: 'muted small' }, copy) : null);
    }

    function paintTemplates() {
      var section = el('section', { class: 'scenario-canvas-section scenario-canvas-templates',
        'aria-labelledby': 'scenario-template-heading' });
      var head = canvasSection('START FROM DATED INPUTS', 'Seed the path, then edit it',
        'These controls place assumptions onto this same canvas. A template refuses to run when its required lane-owned input is unavailable.');
      head.querySelector('h4').id = 'scenario-template-heading';
      section.appendChild(head);
      var grid = el('div', { class: 'scenario-template-grid' });

      var earnings = el('article', { class: 'scenario-template-card' },
        el('b', {}, 'Earnings gap + IV crush'),
        el('p', { class: 'muted small' }, 'Uses the Observed lane’s SEC filing cadence and dated close-to-open gaps. The event date remains explicitly estimated.'));
      earnings.appendChild(el('div', { class: 'btn-row' },
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
          applyTemplate({ kind: 'EARNINGS_GAP_UP' }, this);
        } }, 'Gap up'),
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
          applyTemplate({ kind: 'EARNINGS_GAP_DOWN' }, this);
        } }, 'Gap down')));
      grid.appendChild(earnings);

      var targetId = 'scenario-template-target-' + planRef.plan.id;
      var target = el('input', { id: targetId, type: 'number', min: '0.01', step: '0.01',
        inputmode: 'decimal', value: state.templateTarget || '', placeholder: spot() ? spot().toFixed(2) : 'Target price' });
      target.addEventListener('input', function () { state.templateTarget = target.value; });
      var targetCard = el('article', { class: 'scenario-template-card' },
        el('b', {}, 'Drift to your target'),
        el('p', { class: 'muted small' }, 'Pins the final session to your declared price with an exact bridge. This is your hypothesis, never market evidence.'),
        el('label', { for: targetId }, 'Final price ($)'), target,
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
          var value = parseFloat(target.value);
          applyTemplate({ kind: 'DRIFT_TO_TARGET', targetPriceCents: isFinite(value) ? Math.round(value * 100) : null }, this);
        } }, 'Use target'));
      grid.appendChild(targetCard);

      var sectorId = 'scenario-template-sector-' + planRef.plan.id;
      var sector = el('input', { id: sectorId, type: 'text', maxlength: '12', value: state.templateSector || '',
        autocapitalize: 'characters', placeholder: 'e.g. XLK' });
      sector.addEventListener('input', function () { state.templateSector = sector.value.toUpperCase(); });
      var sectorCard = el('article', { class: 'scenario-template-card' },
        el('b', {}, 'Sector drawdown'),
        el('p', { class: 'muted small' }, 'Transfers a selected ETF’s worst dated 10-session drawdown. It does not claim the stock has the same correlation.'),
        el('label', { for: sectorId }, 'Sector ETF'), sector,
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
          applyTemplate({ kind: 'SECTOR_DRAWDOWN', sectorSymbol: sector.value.trim().toUpperCase() }, this);
        } }, 'Use drawdown'));
      grid.appendChild(sectorCard);

      var fromId = 'scenario-template-from-' + planRef.plan.id;
      var toId = 'scenario-template-to-' + planRef.plan.id;
      var from = el('input', { id: fromId, type: 'date', value: state.templateFrom || '' });
      var to = el('input', { id: toId, type: 'date', value: state.templateTo || '' });
      from.addEventListener('input', function () { state.templateFrom = from.value; });
      to.addEventListener('input', function () { state.templateTo = to.value; });
      var replay = el('article', { class: 'scenario-template-card' },
        el('b', {}, 'Actual historical replay'),
        el('p', { class: 'muted small' }, 'Replays only closes available in a completed window. Future option values remain modeled and are never described as historical chain observations.'),
        el('div', { class: 'scenario-date-pair' },
          el('div', { class: 'field' }, el('label', { for: fromId }, 'From'), from),
          el('div', { class: 'field' }, el('label', { for: toId }, 'To'), to)),
        el('button', { type: 'button', class: 'btn btn-sm btn-secondary', onclick: function () {
          applyTemplate({ kind: 'HISTORICAL_REPLAY', historicalFrom: from.value || null,
            historicalTo: to.value || null }, this);
        } }, 'Replay window'));
      grid.appendChild(replay);
      section.appendChild(grid);
      return section;
    }

    function assumptionSelect(id, value, options, onChange) {
      var select = el('select', { id: id }, options.map(function (option) {
        return el('option', { value: option.value }, option.label);
      }));
      select.value = value;
      select.addEventListener('change', function () { onChange(select.value); });
      return select;
    }

    function paintAssumptions() {
      var model = canvasModel();
      var section = el('section', { class: 'scenario-canvas-section scenario-canvas-assumptions' });
      section.appendChild(canvasSection('PRICE THE STRUCTURES', 'Calendar, surface, and settlement',
        beginner
          ? 'These assumptions decide how each option changes while your price story unfolds. Every value is kept in the receipt.'
          : 'The same PathValuationKernel reprices every selected package with this typed surface and transformation policy.'));
      var grid = el('div', { class: 'scenario-assumption-grid' });
      var dividendId = 'scenario-dividend-' + planRef.plan.id;
      var dividend = el('input', { id: dividendId, type: 'number', min: '-25', max: '100', step: '0.01',
        value: model.dividendYieldAnnual == null ? '' : String(Math.round(model.dividendYieldAnnual * 10000) / 100),
        placeholder: 'Unavailable → 0' });
      dividend.addEventListener('change', function () {
        var value = dividend.value.trim() === '' ? null : parseFloat(dividend.value) / 100;
        updateCanvasModel(function (next) {
          next.dividendYieldAnnual = isFinite(value) ? value : null;
          next.dividendBasis = next.dividendYieldAnnual == null
            ? 'Dividend source unavailable; pricing uses 0% and discloses that limitation.'
            : 'User-authored annualized continuous dividend yield.';
        });
      });
      grid.appendChild(el('div', { class: 'field' },
        el('label', { for: dividendId }, UI.term('dividendyield', 'Annual dividend yield (%)')), dividend,
        el('span', { class: 'muted small' }, 'Blank stays unavailable; pricing then uses 0% and says so.')));

      var skewId = 'scenario-skew-' + planRef.plan.id;
      var skew = el('input', { id: skewId, type: 'number', min: '-300', max: '300', step: '0.1',
        value: String(Math.round(model.skewVolPerLogMoneyness * 1000) / 10) });
      skew.addEventListener('change', function () {
        var value = parseFloat(skew.value);
        updateCanvasModel(function (next) { next.skewVolPerLogMoneyness = isFinite(value) ? value / 100 : 0; });
      });
      grid.appendChild(el('div', { class: 'field' },
        el('label', { for: skewId }, UI.term('volatilityskew', beginner ? 'Strike tilt (IV points)' : 'Skew (IV pts / log-moneyness)')), skew,
        el('span', { class: 'muted small' }, 'Negative means lower strikes carry more volatility.')));

      var termId = 'scenario-term-' + planRef.plan.id;
      var term = el('input', { id: termId, type: 'number', min: '-300', max: '300', step: '0.1',
        value: String(Math.round(model.termVolPerSqrtYear * 1000) / 10) });
      term.addEventListener('change', function () {
        var value = parseFloat(term.value);
        updateCanvasModel(function (next) { next.termVolPerSqrtYear = isFinite(value) ? value / 100 : 0; });
      });
      grid.appendChild(el('div', { class: 'field' },
        el('label', { for: termId }, UI.term('volatilityterm', beginner ? 'Time tilt (IV points)' : 'Term slope (IV pts / √year)')), term,
        el('span', { class: 'muted small' }, 'Positive means later expirations use higher volatility.')));

      var surfaceId = 'scenario-surface-' + planRef.plan.id;
      var surface = assumptionSelect(surfaceId, model.surfaceDynamics, [
        { value: 'STICKY_MONEYNESS', label: beginner ? 'Tilt moves with price' : 'Sticky moneyness' },
        { value: 'STICKY_STRIKE', label: beginner ? 'Tilt stays at strikes' : 'Sticky strike' }
      ], function (value) { updateCanvasModel(function (next) { next.surfaceDynamics = value; }); });
      grid.appendChild(el('div', { class: 'field' },
        el('label', { for: surfaceId }, UI.term('surfacedynamics', 'Surface movement')), surface,
        el('span', { class: 'muted small' }, 'Controls whether the strike tilt travels with the stock.')));

      var settleId = 'scenario-settle-' + planRef.plan.id;
      var settlement = assumptionSelect(settleId, model.settlementPolicy, [
        { value: 'CASH_INTRINSIC', label: beginner ? 'Turn expiry value into cash' : 'Cash intrinsic' },
        { value: 'PHYSICAL_IF_ITM', label: beginner ? 'Transform in-the-money legs' : 'Physical if ITM' }
      ], function (value) { updateCanvasModel(function (next) { next.settlementPolicy = value; }); });
      grid.appendChild(el('div', { class: 'field' },
        el('label', { for: settleId }, UI.term('settlementpolicy', 'At expiration')), settlement,
        el('span', { class: 'muted small' }, 'Later-expiring legs continue after the front leg settles or transforms.')));

      var exerciseId = 'scenario-exercise-' + planRef.plan.id;
      var exercise = assumptionSelect(exerciseId, model.exercisePolicy, [
        { value: 'EXPIRATION_ONLY', label: 'Expiration only' },
        { value: 'EXTRINSIC_THRESHOLD', label: beginner ? 'Allow low-time-value exercise' : 'Extrinsic threshold' }
      ], function (value) { updateCanvasModel(function (next) { next.exercisePolicy = value; }); });
      grid.appendChild(el('div', { class: 'field' },
        el('label', { for: exerciseId }, UI.term('exercisepolicy', 'Exercise rule')), exercise,
        el('span', { class: 'muted small' }, 'The threshold rule is checked only at modeled daily closes.')));
      section.appendChild(grid);
      return section;
    }

    function paintIvControls() {
      var section = el('section', { class: 'scenario-canvas-section scenario-canvas-iv' });
      section.appendChild(canvasSection('DAY-BY-DAY VOLATILITY', beginner ? 'What happens to option uncertainty?' : 'ATM IV path',
        beginner ? 'Choose a guide, then edit any session. The exact values—not the guide name—are priced and saved.'
          : 'Each session node is annualized ATM IV. Unset days interpolate between the exact stored nodes.'));
      var presets = el('div', { class: 'chip-row scenario-iv-presets', role: 'group', 'aria-label': 'Volatility path guide' });
      [{ key: 'STABLE', label: 'Stays steady' }, { key: 'FADES', label: 'Fades slowly' },
       { key: 'CRUSH', label: 'Drops after an event' }, { key: 'RISES', label: 'Builds higher' }].forEach(function (preset) {
        presets.appendChild(el('button', { type: 'button', class: 'sym-chip' + (state.ivPreset === preset.key ? ' active' : ''),
          'aria-pressed': String(state.ivPreset === preset.key), onclick: function () { applyIvPreset(preset.key); } }, preset.label));
      });
      section.appendChild(presets);
      if (!(Number(ivAt(0)) >= .01)) {
        section.appendChild(UI.alertBox('warn', 'No starting IV is available', [
          'The Canvas will not assume 30%. Enter an explicit session IV or run a fan with a named volatility input.'
        ]));
      }
      var table = paintDayInputs();
      if (beginner) section.appendChild(UI.expandable('Edit the exact price and volatility for every session', function () { return table; },
        { stateKey: 'scenario-day-inputs-' + planRef.plan.id }));
      else section.appendChild(table);
      return section;
    }

    function paintDayInputs() {
      var rows = state.preview && state.preview.canvas && state.preview.canvas.underlying || [];
      var table = el('table', { class: 'tbl scenario-day-table' },
        el('thead', {}, el('tr', {}, el('th', {}, 'Session'), el('th', {}, 'Date'),
          el('th', {}, beginner ? 'Your price ($)' : 'Price waypoint ($)'),
          el('th', {}, beginner ? 'Uncertainty (%)' : UI.term('atmiv', 'ATM IV (%)')),
          el('th', {}, 'Stored path range'))));
      var tbody = el('tbody');
      rows.forEach(function (row) {
        var pin = state.pins.find(function (candidate) { return candidate.dayIndex === row.day; });
        var priceId = 'scenario-day-price-' + planRef.plan.id + '-' + row.day;
        var ivId = 'scenario-day-iv-' + planRef.plan.id + '-' + row.day;
        var price = row.day === 0
          ? el('span', { class: 'scenario-day-anchor' }, '$' + Number(row.p50).toFixed(2))
          : el('input', { id: priceId, type: 'number', min: '0.01', step: '0.01', inputmode: 'decimal',
              value: Number(pin ? pin.priceRatio * spot() : row.p50).toFixed(2),
              'aria-label': 'Price at ' + sessionLabel(row.day) });
        if (row.day > 0) price.addEventListener('change', function () {
          var value = parseFloat(price.value);
          if (!isFinite(value) || value <= 0 || !spot()) return;
          var existing = state.pins.find(function (candidate) { return candidate.dayIndex === row.day; });
          if (existing) existing.priceRatio = value / spot();
          else state.pins.push({ dayIndex: row.day, priceRatio: value / spot(), tolerance: null });
          state.pins.sort(function (a, b) { return a.dayIndex - b.dayIndex; });
          scheduleRun(0);
        });
        var currentIv = ivAt(row.day);
        var iv = el('input', { id: ivId, type: 'number', min: '1', max: '400', step: '0.1', inputmode: 'decimal',
          value: Number(currentIv) >= .01 ? (currentIv * 100).toFixed(1) : '',
          placeholder: Number(currentIv) >= .01 ? null : 'Enter IV',
          'aria-label': 'ATM implied volatility at ' + sessionLabel(row.day) });
        function commitIvNode(delay) {
          var value = parseFloat(iv.value);
          if (!isFinite(value)) return;
          setIvNode(row.day, value / 100); state.ivPreset = null; scheduleRun(delay);
        }
        // Input owns the authoring state immediately. Change remains as a keyboard/browser
        // compatibility commit, while the sequenced debounce prevents duplicate calculations.
        iv.addEventListener('input', function () { commitIvNode(); });
        iv.addEventListener('change', function () { commitIvNode(0); });
        tbody.appendChild(el('tr', { 'data-canvas-day': String(row.day) },
          el('td', {}, row.day === 0 ? 'Now' : String(row.day)),
          el('td', { class: 'nowrap' }, row.sessionDate || '—'),
          el('td', {}, price), el('td', {}, iv),
          el('td', { class: 'nowrap muted small' }, '$' + Number(row.p10).toFixed(2) + ' — $' + Number(row.p90).toFixed(2))));
      });
      table.appendChild(tbody);
      return el('div', { class: 'tbl-wrap scenario-day-table-wrap' }, table);
    }

    function positionByKey(key) {
      var positions = state.preview && state.preview.canvas && state.preview.canvas.positions || [];
      return positions.find(function (position) { return position.key === key; });
    }

    function ensurePositionSelection() {
      var positions = state.preview && state.preview.canvas && state.preview.canvas.positions || [];
      var liveKeys = positions.map(function (position) { return position.key; });
      state.selectedPositions = (state.selectedPositions || []).filter(function (key) { return liveKeys.indexOf(key) >= 0; });
      if (state.positionSelectionInitialized) return;
      var actual = positions.find(function (position) { return position.lane === 'PRACTICE' || position.lane === 'REAL'; });
      var proposed = positions.find(function (position) { return position.proposed; });
      if (actual) state.selectedPositions.push(actual.key);
      if (proposed && state.selectedPositions.indexOf(proposed.key) < 0) state.selectedPositions.push(proposed.key);
      if (!state.selectedPositions.length && positions.length) state.selectedPositions.push(positions[0].key);
      state.positionSelectionInitialized = true;
    }

    function comparisonRows() {
      var report = state.preview && state.preview.canvas || {};
      var rows = report.comparison || [];
      var selected = rows.filter(function (row) { return state.selectedPositions.indexOf(row.key) >= 0; });
      var stock = rows.find(function (row) {
        var position = positionByKey(row.key); return position && position.source === 'STOCK_BASELINE';
      });
      var proposed = rows.find(function (row) { return row.proposed; });
      var actual = rows.find(function (row) { return row.lane === 'PRACTICE' || row.lane === 'REAL'; });
      var structures = selected.filter(function (row) {
        var position = positionByKey(row.key); return !position || position.source !== 'STOCK_BASELINE';
      });
      var out = [];
      function add(row) { if (row && !out.some(function (x) { return x.key === row.key; })) out.push(row); }
      if (state.compareMode === 'STRUCTURE_VS_STOCK') {
        add(structures[0] || proposed || actual || rows.find(function (row) { return row !== stock; })); add(stock);
      } else if (state.compareMode === 'TWO_STRUCTURES') {
        (structures.length >= 2 ? structures : rows.filter(function (row) {
          var position = positionByKey(row.key); return !position || position.source !== 'STOCK_BASELINE';
        })).slice(0, 2).forEach(add);
      } else {
        add(structures.find(function (row) { return row.lane === 'PRACTICE' || row.lane === 'REAL'; }) || actual);
        add(structures.find(function (row) { return row.proposed; }) || proposed);
      }
      return out;
    }

    function valueBandChart(position) {
      var days = position.days || [];
      if (!days.length) return el('div');
      var values = [];
      days.forEach(function (day) { values.push(day.pnlP10Cents, day.pnlP50Cents, day.pnlP90Cents); });
      var lo = Math.min.apply(Math, values), hi = Math.max.apply(Math, values);
      if (lo === hi) { lo -= 100; hi += 100; }
      var w = 720, h = 180, pad = 12;
      function x(i) { return pad + i * (w - pad * 2) / Math.max(1, days.length - 1); }
      function y(value) { return pad + (hi - value) * (h - pad * 2) / (hi - lo); }
      function points(field, reverse) {
        var source = reverse ? days.slice().reverse() : days;
        return source.map(function (day, index) {
          var original = reverse ? days.length - 1 - index : index;
          return x(original).toFixed(1) + ',' + y(day[field]).toFixed(1);
        }).join(' ');
      }
      var zero = lo <= 0 && hi >= 0 ? '<line x1="12" y1="' + y(0).toFixed(1)
        + '" x2="708" y2="' + y(0).toFixed(1) + '" class="scenario-position-zero"/>' : '';
      return el('div', { class: 'scenario-position-band', role: 'img',
        'aria-label': position.label + ' modeled profit and loss band by session', html:
        '<svg viewBox="0 0 720 180" preserveAspectRatio="none" aria-hidden="true">'
        + zero + '<polygon class="scenario-position-band-fill" points="' + points('pnlP90Cents', false)
        + ' ' + points('pnlP10Cents', true) + '"/>'
        + '<polyline class="scenario-position-median" points="' + points('pnlP50Cents', false) + '"/>'
        + '</svg>' });
    }

    function greekText(greeks, key, money) {
      if (!greeks || greeks[key] == null) return '—';
      return money ? UI.fmtMoneyCompact(greeks[key]) : Number(greeks[key]).toFixed(2);
    }

    function positionDayTable(position) {
      var table = el('table', { class: 'tbl scenario-position-day-table' },
        el('thead', {}, el('tr', {}, el('th', {}, 'Day'), el('th', {}, 'Date'),
          el('th', {}, 'Modeled value p10 / p50 / p90'), el('th', {}, 'Median P&L'),
          el('th', {}, UI.vocabulary('netDelta', 'Δ sh')),
          el('th', {}, UI.vocabulary('gamma', 'Γ sh/$')),
          el('th', {}, UI.vocabulary('thetaPerDay', 'Θ /day')),
          el('th', {}, UI.vocabulary('vegaPerVolPoint', 'Vega /pt')))));
      var tbody = el('tbody');
      (position.days || []).forEach(function (day) {
        tbody.appendChild(el('tr', {}, el('td', {}, String(day.day)), el('td', { class: 'nowrap' }, day.sessionDate),
          el('td', { class: 'nowrap' }, UI.fmtMoneyCompact(day.valueP10Cents) + ' / '
            + UI.fmtMoneyCompact(day.valueP50Cents) + ' / ' + UI.fmtMoneyCompact(day.valueP90Cents)),
          el('td', {}, UI.pnlSpan(day.pnlP50Cents)),
          el('td', {}, greekText(day.greeks, 'deltaShares', false)),
          el('td', {}, greekText(day.greeks, 'gammaSharesPerDollar', false)),
          el('td', {}, greekText(day.greeks, 'thetaCentsPerDay', true)),
          el('td', {}, greekText(day.greeks, 'vegaCentsPerPoint', true))));
      });
      table.appendChild(tbody);
      return el('div', { class: 'tbl-wrap scenario-position-table-wrap' }, table);
    }

    function legDayTable(leg) {
      var table = el('table', { class: 'tbl scenario-leg-day-table' },
        el('thead', {}, el('tr', {}, el('th', {}, 'Day'), el('th', {}, 'Modeled unit price'),
          el('th', {}, 'Leg value'), el('th', {}, 'State'),
          el('th', {}, UI.vocabulary('netDelta', 'Δ sh')),
          el('th', {}, UI.vocabulary('gamma', 'Γ sh/$')),
          el('th', {}, UI.vocabulary('thetaPerDay', 'Θ /day')),
          el('th', {}, UI.vocabulary('vegaPerVolPoint', 'Vega /pt')))));
      var tbody = el('tbody');
      (leg.days || []).forEach(function (day) {
        tbody.appendChild(el('tr', {}, el('td', {}, String(day.day)),
          el('td', {}, UI.fmtMoneyCompact(day.optionPriceCents)),
          el('td', {}, UI.fmtMoneyCompact(day.valueCents)), el('td', {}, day.state || 'OPEN'),
          el('td', {}, greekText(day.greeks, 'deltaShares', false)),
          el('td', {}, greekText(day.greeks, 'gammaSharesPerDollar', false)),
          el('td', {}, greekText(day.greeks, 'thetaCentsPerDay', true)),
          el('td', {}, greekText(day.greeks, 'vegaCentsPerPoint', true))));
      });
      table.appendChild(tbody);
      return el('div', { class: 'tbl-wrap scenario-leg-table-wrap' }, table);
    }

    function paintPosition(position) {
      var finalDay = (position.days || [])[Math.max(0, (position.days || []).length - 1)] || {};
      var card = el('article', { class: 'scenario-position-detail', 'data-position-key': position.key },
        el('div', { class: 'scenario-position-title' },
          el('div', {}, el('span', { class: 'badge ' + (position.proposed ? 'badge-modeled' : 'badge-neutral') },
            position.proposed ? 'PLAN PROPOSAL' : position.lane), el('h4', {}, position.label)),
          el('div', { class: 'scenario-position-final' }, el('span', { class: 'muted small' }, 'Horizon median P&L'),
            UI.pnlSpan(finalDay.pnlP50Cents))),
        valueBandChart(position),
        el('div', { class: 'scenario-band-legend muted small' },
          el('span', { class: 'scenario-band-key range' }, '10–90% modeled band'),
          el('span', { class: 'scenario-band-key median' }, 'Median')),
        positionDayTable(position));
      if ((position.transformations || []).length) {
        var transformations = el('div', { class: 'scenario-transformations' },
          el('b', {}, 'Expiry transformations'));
        position.transformations.forEach(function (event) {
          transformations.appendChild(el('div', { class: 'scenario-transformation-row' },
            el('span', { class: 'badge badge-warn' }, 'DAY ' + event.day),
            el('span', {}, event.leg + ' · ' + event.note)));
        });
        card.appendChild(transformations);
      }
      var legs = el('div', { class: 'scenario-leg-details' });
      (position.legs || []).forEach(function (leg) {
        legs.appendChild(UI.expandable('Leg ' + (leg.legNo + 1) + ': ' + leg.label
          + (leg.expiration ? ' · expires ' + leg.expiration : ''), function () { return legDayTable(leg); },
          { stateKey: 'scenario-leg-' + planRef.plan.id + '-' + position.key + '-' + leg.legNo }));
      });
      card.appendChild(legs);
      return card;
    }

    function paintComparisons() {
      ensurePositionSelection();
      var report = state.preview && state.preview.canvas || {};
      var positions = report.positions || [];
      var section = el('section', { class: 'scenario-canvas-section scenario-canvas-comparisons' });
      section.appendChild(canvasSection('ONE PATH SET · EVERY PACKAGE', 'Compare same-symbol positions side by side',
        beginner ? 'Pick what you want to inspect. Every card sees the identical future prices and volatility assumptions.'
          : 'Practice, Tracked, proposal, and stock baseline are canonical package views valued on the same stored ensemble.'));
      if (!positions.length) {
        var unavailable = report.refused || [];
        section.appendChild(UI.alertBox('caution', 'No position package could be priced',
          unavailable.length
            ? unavailable.map(function (row) { return row.label + ': ' + row.reason; })
            : ['The underlying canvas still works. Add or select a same-symbol position to unlock structure comparisons.']));
        return section;
      }
      var modes = UI.segmented({ id: 'scenario-compare-mode-' + planRef.plan.id, label: 'Comparison question',
        info: 'scenariocomparison',
        options: [
          { value: 'YOURS_VS_PROPOSED', label: beginner ? 'Mine vs the idea' : 'Yours vs proposal' },
          { value: 'TWO_STRUCTURES', label: 'Two structures' },
          { value: 'STRUCTURE_VS_STOCK', label: 'Structure vs stock' }
        ], value: state.compareMode, onChange: function (value) { state.compareMode = value; paint(); } });
      section.appendChild(modes);
      var chooser = el('div', { class: 'scenario-position-chooser', role: 'group', 'aria-label': 'Positions shown in detail' });
      positions.forEach(function (position, index) {
        var id = 'scenario-position-' + planRef.plan.id + '-' + index;
        var checked = state.selectedPositions.indexOf(position.key) >= 0;
        var checkbox = el('input', { id: id, type: 'checkbox', checked: checked ? 'checked' : null });
        checkbox.addEventListener('change', function () {
          if (checkbox.checked && state.selectedPositions.indexOf(position.key) < 0) state.selectedPositions.push(position.key);
          if (!checkbox.checked) state.selectedPositions = state.selectedPositions.filter(function (key) { return key !== position.key; });
          paint();
        });
        chooser.appendChild(el('label', { class: 'scenario-position-choice' + (checked ? ' active' : ''), for: id },
          checkbox, el('span', {}, el('b', {}, position.label),
            el('small', { class: 'muted' }, position.proposed ? 'Plan proposal' : position.lane + ' · ' + position.source))));
      });
      section.appendChild(chooser);
      var compare = comparisonRows();
      var compareGrid = el('div', { class: 'scenario-compare-grid', 'data-compare-mode': state.compareMode });
      compare.forEach(function (row) {
        compareGrid.appendChild(el('article', { class: 'scenario-compare-card' },
          el('div', { class: 'scenario-compare-card-head' }, el('b', {}, row.label),
            el('span', { class: 'badge badge-neutral' }, row.proposed ? 'PROPOSAL' : row.lane)),
          el('div', { class: 'scenario-compare-hero' },
            el('span', { class: 'muted small' }, 'Horizon median'), UI.pnlSpan(row.horizonP50Cents)),
          el('dl', { class: 'scenario-metric-grid' },
            el('div', {}, el('dt', {}, '1-in-20 low'), el('dd', {}, UI.fmtMoneyCompact(row.horizonP5Cents))),
            el('div', {}, el('dt', {}, '1-in-20 high'), el('dd', {}, UI.fmtMoneyCompact(row.horizonP95Cents))),
            el('div', {}, el('dt', {}, 'Average'), el('dd', {}, UI.fmtMoneyCompact(row.expectedHorizonCents))),
            el('div', {}, el('dt', {}, 'Chance of gain'), el('dd', {}, Number(row.chanceOfGainPct).toFixed(1) + '%')),
            row.versusStockP50Cents == null ? null
              : el('div', {}, el('dt', {}, 'Vs 100 shares'), el('dd', {}, UI.fmtMoneyCompact(row.versusStockP50Cents))))));
      });
      if (!compare.length) compareGrid.appendChild(el('p', { class: 'muted' }, 'Select structures above to answer this comparison.'));
      section.appendChild(compareGrid);
      var refused = report.refused || [];
      if (refused.length) {
        section.appendChild(UI.alertBox('caution', 'Some same-symbol packages were not comparable',
          refused.map(function (row) { return row.label + ': ' + row.reason; })));
      }
      var details = el('div', { class: 'scenario-position-details' });
      positions.filter(function (position) { return state.selectedPositions.indexOf(position.key) >= 0; })
        .forEach(function (position) { details.appendChild(paintPosition(position)); });
      if (!details.childNodes.length) details.appendChild(el('p', { class: 'muted' }, 'Select at least one position to see its daily value, Greeks, legs, and expirations.'));
      section.appendChild(details);
      return section;
    }

    function paintModelReceipt() {
      var report = state.preview && state.preview.canvas || {};
      var receipt = report.modelReceipt || {};
      var template = receipt.template || null;
      var section = el('section', { class: 'scenario-canvas-section scenario-model-receipt' });
      section.appendChild(canvasSection('IMMUTABLE RECEIPT', beginner ? 'What this canvas assumes' : 'Model and provenance receipt',
        'Your path is a user hypothesis, never a forecast. Restoring this stored fan restores these exact assumptions.'));
      if (template) {
        section.appendChild(el('article', { class: 'scenario-template-receipt' },
          el('div', {}, el('span', { class: 'badge ' + (template.observed ? 'badge-ok' : 'badge-modeled') }, template.provenance),
            el('b', {}, String(template.kind || '').replaceAll('_', ' '))),
          el('p', {}, template.note),
          el('p', { class: 'muted small' }, template.source + ' · input as of ' + template.inputAsOf
            + (template.windowFrom ? ' · window ' + template.windowFrom + ' — ' + template.windowTo : '')
            + ' · ' + template.observations + ' dated observation' + (template.observations === 1 ? '' : 's')
            + (template.noHindsight ? ' · no-hindsight boundary enforced' : '')),
          el('p', { class: 'muted small' }, template.legDayProvenance)));
      }
      var facts = el('dl', { class: 'scenario-receipt-grid' },
        el('div', {}, el('dt', {}, 'Calendar'), el('dd', {}, receipt.calendar || 'NYSE')),
        el('div', {}, el('dt', {}, 'Risk-free rate'), el('dd', {}, receipt.rateAnnual == null ? '—' : (receipt.rateAnnual * 100).toFixed(2) + '%')),
        el('div', {}, el('dt', {}, 'Dividend'), el('dd', {}, receipt.dividendYieldAnnual == null ? 'Unavailable → 0% in pricing' : (receipt.dividendYieldAnnual * 100).toFixed(2) + '%')),
        el('div', {}, el('dt', {}, 'Surface'), el('dd', {}, String(receipt.surfaceDynamics || '').replaceAll('_', ' ').toLowerCase())),
        el('div', {}, el('dt', {}, UI.term('settlementpolicy', 'Settlement')), el('dd', {}, String(receipt.settlementPolicy || '').replaceAll('_', ' ').toLowerCase())),
        el('div', {}, el('dt', {}, UI.term('exercisepolicy', 'Exercise')), el('dd', {}, String(receipt.exercisePolicy || '').replaceAll('_', ' ').toLowerCase())),
        el('div', {}, el('dt', {}, UI.term('volatilityskew', 'Skew / term')), el('dd', {}, Number(receipt.skewVolPerLogMoneyness || 0).toFixed(3)
          + ' / ' + Number(receipt.termVolPerSqrtYear || 0).toFixed(3))),
        el('div', {}, el('dt', {}, 'Positions priced'), el('dd', {}, String(receipt.positionScopeCount == null ? 0 : receipt.positionScopeCount))));
      section.appendChild(facts);
      var notes = report.notes || [];
      if (notes.length) {
        section.appendChild(el('div', { class: 'scenario-receipt-notes', 'aria-label': 'How to read these modeled results' },
          el('b', {}, beginner ? 'How to read this' : 'Valuation methodology'),
          el('ul', {}, notes.map(function (note) { return el('li', {}, note); }))));
      }
      section.appendChild(UI.expandable(beginner ? 'Show the technical identity' : 'Exact model identity and dividend basis', function () {
        return el('div', { class: 'scenario-receipt-technical' },
          el('p', {}, receipt.dividendBasis || canvasModel().dividendBasis),
          el('p', { class: 'muted small' }, 'Ensemble fingerprint ' + (receipt.fingerprint || '—')),
          el('p', { class: 'muted small' }, 'Path model ' + (receipt.pathModelVersion || '—')
            + ' · canvas model ' + (receipt.canvasModelVersion || '—') + ' · anchor ' + (receipt.anchorDate || '—')),
          el('p', { class: 'muted small' }, 'Exact ATM IV nodes: ' + JSON.stringify(receipt.ivNodes || [])));
      }, { stateKey: 'scenario-receipt-' + planRef.plan.id }));
      return section;
    }

    function paintSaved() {
      if (!savedHost) return;
      savedHost.replaceChildren();
      var rows = state.saved || [];
      savedHost.appendChild(el('div', { class: 'scenario-saved-head' },
        el('h4', {}, 'Saved scenarios'),
        el('p', { class: 'muted small' }, beginner
          ? 'Each card is a path you drew, kept with how honestly it was honored. Loading one re-applies its pins to the chart above.'
          : 'Frozen authored specs (pins + model + seed) with lineage to their base fan. Loading re-applies the pins here.')));
      if (!rows.length) {
        savedHost.appendChild(el('p', { class: 'muted small' }, 'None saved yet.'));
        return;
      }
      var grid = el('div', { class: 'authored-scenario-grid' });
      rows.forEach(function (saved) {
        var stale = saved.currentContext === false;
        var fillName = saved.waypointFill === 'EXACT_CONDITIONAL' ? 'Exact conditional'
          : saved.waypointFill === 'GUIDED_INTERPOLATION' ? 'Guided interpolation' : 'No pins';
        grid.appendChild(el('article', { class: 'authored-scenario-card' + (stale ? ' stale' : ''),
          'data-fill': saved.waypointFill, 'data-waypoints': String(saved.waypointCount || 0) },
          el('b', {}, saved.title || 'Authored scenario'),
          el('div', { class: 'chip-row' },
            el('span', { class: 'badge ' + (saved.waypointFill === 'GUIDED_INTERPOLATION' ? 'badge-warn' : 'badge-ok') }, fillName),
            el('span', { class: 'muted small' }, (saved.waypointCount || 0) + ' waypoint'
              + (saved.waypointCount === 1 ? '' : 's') + ' · saved ' + UI.fmtDate(saved.createdAt))),
          stale ? el('p', { class: 'muted small scenario-stale-note' },
            saved.staleness || 'Authored under earlier Plan assumptions.') : null,
          el('div', { class: 'btn-row' },
            el('button', { type: 'button', class: 'btn btn-sm' + (stale ? ' btn-secondary' : ''),
              onclick: function () { applyScenario(saved); } },
              stale ? 'Re-anchor to the current fan' : 'Load onto the fan'))));
      });
      savedHost.appendChild(grid);
    }

    function paint() {
      body.replaceChildren();
      if (!state.preview) {
        body.appendChild(UI.emptyState('No stored fan to draw on yet',
          'The canvas authors ON this Plan’s possible-futures fan; the same stored simulation then feeds every outcome lens.',
          'Run the possible futures', function () { runFirstFan(this); }));
        return;
      }
      var fill = state.preview.waypointFill || 'NONE';
      root.setAttribute('data-waypoint-fill', fill);
      body.appendChild(el('div', { class: 'scenario-hypothesis-banner' },
        el('b', {}, 'Your hypothesis, never a forecast.'),
        el('span', {}, ' Every price pin and volatility node is an assumption you can inspect, edit, and restore from its receipt.')));
      body.appendChild(fillBanner(fill));
      body.appendChild(paintTemplates());
      var main = el('div', { class: 'scenario-canvas-main' });
      main.appendChild(fanChart(state.preview, {
        noNotes: true,
        author: {
          pins: state.pins,
          sessionDates: state.preview.sessionDates || [],
          onChange: function () { paintRail(); scheduleRun(); }
        }
      }));
      main.appendChild(el('p', { class: 'muted small' }, beginner
        ? 'Click where you believe the price goes; drag a pin to adjust; double-click a pin (or use × in the list) to remove it. The chart re-runs right here — no page change.'
        : 'Click to pin, drag to adjust, double-click to remove. Same model, seed, and calibration — re-run conditioned on your pins, in place.'));
      railHost = el('div', { class: 'scenario-canvas-rail' });
      paintRail();
      body.appendChild(el('div', { class: 'scenario-canvas-cols' }, main, railHost));
      body.appendChild(el('div', { class: 'scenario-control-deck' }, paintIvControls(), paintAssumptions()));
      body.appendChild(paintComparisons());
      body.appendChild(paintModelReceipt());
      savedHost = el('div', { class: 'authored-scenario-saved' });
      paintSaved();
      body.appendChild(savedHost);
    }

    (async function init() {
      body.appendChild(UI.spinner('Opening the scenario canvas…'));
      if (!state.preview) {
        try {
          var restored = await API.getFresh('/api/plans/' + planRef.plan.id + '/outcomes/ensemble/latest');
          adoptRun(restored, true);
        } catch (e) { /* no stored fan yet is the normal first-visit state */ }
      }
      try { await loadSaved(); } catch (e) { state.saved = state.saved || []; }
      if (!root.isConnected && !body.isConnected) { /* detached before load — still paint for reattach */ }
      paint();
    })();

    return root;
  }

  window.Scenario = {
    SHAPES: SHAPES, CATALOG: CATALOG,
    form: form, fanChart: fanChart, decisionView: decisionView, pnlView: pnlView,
    canvasStudio: canvasStudio,
    workingLegs: workingLegs, workingPosition: workingPosition,
    realisticOutcomes: realisticOutcomes, sketch: sketch, applyCatalog: applyCatalog,
    contracts: Object.freeze({ realisticHorizon: realisticHorizon, canvasIvValue: canvasIvValue })
  };
})();
