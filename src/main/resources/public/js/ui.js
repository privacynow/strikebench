/* StrikeBench UI helpers: DOM building, formatting, badges, charts, modals. */
(function () {
  'use strict';

  /** el('div', {class:'x', onclick:fn}, child1, 'text', ...) */
  function el(tag, attrs) {
    var node = document.createElement(tag);
    attrs = attrs || {};
    Object.keys(attrs).forEach(function (k) {
      var v = attrs[k];
      if (v === null || v === undefined) return;
      if (k === 'class') node.className = v;
      else if (k === 'html') node.innerHTML = v;
      else if (k.indexOf('on') === 0 && typeof v === 'function') node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v);
    });
    for (var i = 2; i < arguments.length; i++) append(node, arguments[i]);
    return node;
  }

  function append(node, child) {
    if (child === null || child === undefined || child === false) return;
    if (Array.isArray(child)) { child.forEach(function (c) { append(node, c); }); return; }
    node.appendChild(typeof child === 'string' || typeof child === 'number'
      ? document.createTextNode(String(child)) : child);
  }

  // ---- formatting ----

  function fmtMoney(cents, opts) {
    if (cents === null || cents === undefined) return '—';
    var sign = cents < 0 ? '−' : (opts && opts.plus && cents > 0 ? '+' : '');
    var abs = Math.abs(Math.round(cents));
    var dollars = Math.floor(abs / 100).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return sign + '$' + dollars + '.' + String(abs % 100).padStart(2, '0');
  }

  /** Compact for chart axes: $1.2k / $85k / $1.3M */
  function fmtMoneyCompact(cents) {
    if (cents === null || cents === undefined) return '—';
    var v = cents / 100;
    var sign = v < 0 ? '−' : '';
    var a = Math.abs(v);
    if (a >= 1e6) return sign + '$' + (a / 1e6).toFixed(1) + 'M';
    if (a >= 10000) return sign + '$' + Math.round(a / 1000) + 'k';
    if (a >= 1000) return sign + '$' + (a / 1000).toFixed(1) + 'k';
    return sign + '$' + Math.round(a);
  }

  function pnlSpan(cents, cls) {
    if (cents === null || cents === undefined) return el('span', {}, '—');
    return el('span', { class: (cents >= 0 ? 'gain' : 'loss') + (cls ? ' ' + cls : '') },
      fmtMoney(cents, { plus: true }));
  }

  function fmtPct(x, digits) {
    if (x === null || x === undefined || isNaN(x)) return '—';
    return (x * 100).toFixed(digits === undefined ? 0 : digits) + '%';
  }

  function fmtNum(x, digits) {
    if (x === null || x === undefined || isNaN(x)) return '—';
    return Number(x).toLocaleString('en-US', {
      minimumFractionDigits: digits === undefined ? 2 : digits,
      maximumFractionDigits: digits === undefined ? 2 : digits
    });
  }

  /** Colored change vs a reference: "▲ 2.31 (+0.9%)" */
  function delta(now, ref) {
    now = parseFloat(now); ref = parseFloat(ref);
    if (isNaN(now) || isNaN(ref) || ref === 0) return el('span', { class: 'muted' }, '');
    var d = now - ref, pct = d / ref * 100;
    var up = d >= 0;
    return el('span', { class: 'delta ' + (up ? 'gain' : 'loss') },
      (up ? '▲ ' : '▼ ') + Math.abs(d).toFixed(2) + ' (' + (up ? '+' : '−') + Math.abs(pct).toFixed(2) + '%)');
  }

  // ---- small components ----

  var FRESH_CLASS = {
    REALTIME: 'badge-ok', FIXTURE: 'badge-dim', DELAYED: 'badge-warn',
    EOD: 'badge-caution', MODELED: 'badge-caution', STALE: 'badge-danger', MISSING: 'badge-danger'
  };
  function freshnessBadge(freshness) {
    if (!freshness) return null;
    var label = freshness === 'FIXTURE' ? 'DEMO DATA' : freshness;
    return el('span', { class: 'badge ' + (FRESH_CLASS[freshness] || 'badge-dim'), title: 'Data freshness' }, label);
  }

  function explain(text) {
    return el('span', { class: 'explain' }, text);
  }

  function alertBox(kind, title, items) {
    var box = el('div', { class: 'alert alert-' + kind }, title);
    if (items && items.length) {
      box.appendChild(el('ul', {}, items.map(function (r) { return el('li', {}, r); })));
    }
    return box;
  }

  function stat(label, valueNode, explainText) {
    return el('div', { class: 'stat' },
      el('div', { class: 'label' }, label),
      el('div', { class: 'value' }, valueNode),
      explainText ? explain(explainText) : null);
  }

  function chip(label, valueNode) {
    return el('span', { class: 'chip' }, el('span', { class: 'chip-label' }, label), el('b', {}, valueNode));
  }

  function table(headers, rows) {
    return el('div', { class: 'tbl-wrap' },
      el('table', { class: 'tbl' },
        el('thead', {}, el('tr', {}, headers.map(function (h) { return el('th', {}, h); }))),
        el('tbody', {}, rows)));
  }

  function spinner(label) {
    return el('div', { class: 'loading' }, el('span', { class: 'spin' }), ' ', label || 'Loading…');
  }

  function emptyState(title, hint, actionLabel, onAction) {
    return el('div', { class: 'empty' },
      el('div', { class: 'empty-title' }, title),
      hint ? el('div', { class: 'empty-hint' }, hint) : null,
      actionLabel ? el('button', { class: 'btn', onclick: onAction }, actionLabel) : null);
  }

  /** 0–100 score as a small colored meter. */
  function scoreBar(score) {
    var pct = Math.max(0, Math.min(100, score));
    var cls = pct >= 70 ? 'ok' : pct >= 45 ? 'caution' : 'danger';
    return el('span', { class: 'score-wrap', title: 'Composite rank: risk validity, freshness, liquidity, risk:reward, POP, capital efficiency' },
      el('span', { class: 'score-num' }, Math.round(pct)),
      el('span', { class: 'score-bar' }, el('span', { class: 'score-fill score-' + cls, style: 'width:' + pct + '%' })));
  }

  /**
   * Progressive-disclosure primitive: a summary row with a chevron that expands
   * a detail block. detail can be a node or a lazy function () => node.
   */
  function expandable(summaryContent, detail, opts) {
    opts = opts || {};
    var body = el('div', { class: 'xp-body' });
    var built = false;
    var chevron = el('span', { class: 'xp-chevron' }, '\u203A');
    var head = el('button', { class: 'xp-head', 'aria-expanded': 'false' }, chevron, summaryContent);
    var wrap = el('div', { class: 'xp' + (opts.open ? ' open' : '') }, head, body);
    function toggle(force) {
      var open = force !== undefined ? force : !wrap.classList.contains('open');
      if (open && !built) {
        append(body, typeof detail === 'function' ? detail() : detail);
        built = true;
      }
      wrap.classList.toggle('open', open);
      head.setAttribute('aria-expanded', String(open));
    }
    head.addEventListener('click', function () { toggle(); });
    if (opts.open) toggle(true);
    return wrap;
  }

  /** A term of art with tap-to-define glossary popover (Learning/Confident levels). */
  function term(word, display) {
    var def = window.Learn && Learn.GLOSSARY[word.toLowerCase()];
    if (!def || (window.Learn && Learn.currentLevel() === 'pro')) {
      return el('span', {}, display || word);
    }
    var node = el('button', { class: 'term', type: 'button' }, display || word);
    node.addEventListener('click', function (e) {
      e.stopPropagation();
      showPopover(node, word, def);
    });
    return node;
  }

  function showPopover(anchor, title, text) {
    dismissPopover();
    var pop = el('div', { class: 'popover', id: 'glossary-popover' },
      el('div', { class: 'popover-title' }, title),
      el('div', { class: 'popover-body' }, text));
    document.body.appendChild(pop);
    var r = anchor.getBoundingClientRect();
    var top = r.bottom + window.scrollY + 6;
    var left = Math.min(Math.max(8, r.left + window.scrollX), window.innerWidth - 300);
    pop.style.top = top + 'px';
    pop.style.left = left + 'px';
    setTimeout(function () {
      document.addEventListener('click', dismissPopover, { once: true });
    }, 0);
  }

  function dismissPopover() {
    var existing = document.getElementById('glossary-popover');
    if (existing) existing.remove();
  }

  function cardHeader(title, right) {
    return el('div', { class: 'card-head' }, el('h2', { class: 'mt0' }, title), el('span', { class: 'spacer' }), right || null);
  }

  /** Confirmation modal. body may be a node; onConfirm is async. */
  function confirmModal(title, bodyNode, confirmLabel, onConfirm, danger) {
    var root = document.getElementById('modal-root');
    root.innerHTML = '';
    var msg = el('div', { class: 'alert alert-danger', style: 'display:none' });
    function showError(text) {
      msg.textContent = text;
      msg.style.display = '';
    }
    var confirmBtn = el('button', {
      class: 'btn ' + (danger ? 'btn-danger' : ''), id: 'modal-confirm',
      onclick: function () {
        confirmBtn.disabled = true;
        Promise.resolve(onConfirm()).then(close).catch(function (e) {
          confirmBtn.disabled = false;
          showError(e.message || 'Failed');
        });
      }
    }, confirmLabel);
    var backdrop = el('div', { class: 'modal-backdrop' },
      el('div', { class: 'modal' },
        el('h3', {}, title),
        bodyNode,
        msg,
        el('div', { class: 'btn-row' },
          confirmBtn,
          el('button', { class: 'btn btn-secondary', onclick: close }, 'Cancel'))));
    function close() { root.innerHTML = ''; }
    root.appendChild(backdrop);
    return { close: close, showError: showError };
  }

  // ---- charts (inline SVG, no libraries) ----

  function svgEl(tag, attrs) {
    var n = document.createElementNS('http://www.w3.org/2000/svg', tag);
    Object.keys(attrs).forEach(function (k) { n.setAttribute(k, attrs[k]); });
    return n;
  }

  function niceTicks(min, max, count) {
    var span = max - min;
    if (span <= 0) return [min];
    var step = Math.pow(10, Math.floor(Math.log10(span / count)));
    var err = span / count / step;
    if (err >= 7.5) step *= 10; else if (err >= 3.5) step *= 5; else if (err >= 1.5) step *= 2;
    var ticks = [];
    for (var v = Math.ceil(min / step) * step; v <= max + 1e-9; v += step) ticks.push(v);
    return ticks;
  }

  /** Payoff chart from [{price, profitCents}] with zero line, gridlines, breakeven + spot markers. */
  /**
   * Wraps a chart SVG with an HTML tooltip layer and a crosshair. `probe(fracX)` maps a
   * 0..1 horizontal position inside the plot area to {x, y, lines[]} in viewBox coords
   * (or null when out of range); the tooltip shows `lines` and a dot rides the curve.
   * Pointer events cover mouse AND touch — the finance-app "slide to read values" feel.
   */
  function interactiveChart(svg, geom, probe) {
    var wrap = el('div', { class: 'chart-wrap' });
    wrap.appendChild(svg);
    var tip = el('div', { class: 'chart-tip', style: 'display:none' });
    wrap.appendChild(tip);
    var vline = svgEl('line', { class: 'xhair', x1: 0, y1: geom.padT, x2: 0, y2: geom.H - geom.padB, style: 'display:none' });
    var dot = svgEl('circle', { class: 'xhair-dot', r: 4, style: 'display:none' });
    svg.appendChild(vline);
    svg.appendChild(dot);

    function hide() {
      tip.style.display = 'none';
      vline.style.display = 'none';
      dot.style.display = 'none';
    }
    svg.addEventListener('pointerleave', hide);
    svg.addEventListener('pointermove', function (ev) {
      var box = svg.getBoundingClientRect();
      if (!box.width) return;
      var plotX0 = geom.padL / geom.W * box.width;
      var plotX1 = (geom.W - geom.padR) / geom.W * box.width;
      var frac = (ev.clientX - box.left - plotX0) / Math.max(1, plotX1 - plotX0);
      if (frac < 0 || frac > 1) { hide(); return; }
      var hit = probe(frac);
      if (!hit) { hide(); return; }
      vline.setAttribute('x1', hit.x); vline.setAttribute('x2', hit.x);
      dot.setAttribute('cx', hit.x); dot.setAttribute('cy', hit.y);
      vline.style.display = ''; dot.style.display = '';
      tip.innerHTML = '';
      hit.lines.forEach(function (l, i) {
        tip.appendChild(el('div', { class: i === 0 ? 'tt-title' : 'tt-line' + (l.cls ? ' ' + l.cls : '') },
          typeof l === 'string' ? l : l.text));
      });
      tip.style.display = '';
      // Position in wrapper pixels; flip sides near the right edge
      var px = hit.x / geom.W * box.width;
      var flip = px > box.width * 0.62;
      tip.style.left = flip ? '' : (px + 12) + 'px';
      tip.style.right = flip ? (box.width - px + 12) + 'px' : '';
      tip.style.top = Math.max(0, hit.y / geom.H * box.height - 14) + 'px';
    });
    return wrap;
  }

  /**
   * Range-pill chart: [1M 3M 6M YTD 1Y ...] buttons + an interactive line chart + a window
   * change chip. `fetch(rangeKey)` resolves {series:[{date,value}], note?, badge?}. The kind
   * of control every finance site has and this app was missing.
   */
  function rangeChart(opts) {
    var ranges = opts.ranges || [
      { key: '1m', label: '1M' }, { key: '3m', label: '3M' }, { key: '6m', label: '6M' },
      { key: 'ytd', label: 'YTD' }, { key: '1y', label: '1Y' }, { key: '2y', label: '2Y' },
      { key: '5y', label: '5Y' }, { key: 'max', label: 'MAX' }
    ];
    var selected = opts.initial || '1y';
    var pills = el('div', { class: 'range-pills', role: 'group' });
    var summary = el('div', { class: 'chip-row chart-summary' });
    var host = el('div', { class: 'chart-host' });
    var node = el('div', {}, pills, summary, host);

    function renderPills() {
      pills.innerHTML = '';
      ranges.forEach(function (r) {
        pills.appendChild(el('button', {
          class: 'pill' + (r.key === selected ? ' active' : ''), 'data-range': r.key, type: 'button',
          onclick: function () { if (r.key !== selected) { selected = r.key; load(); } }
        }, r.label));
      });
    }

    async function load() {
      renderPills();
      host.innerHTML = '';
      summary.innerHTML = '';
      host.appendChild(spinner('Loading ' + selected.toUpperCase() + '…'));
      try {
        var data = await opts.fetch(selected);
        if (selected !== (data.range || selected)) return; // superseded
        host.innerHTML = '';
        summary.innerHTML = '';
        var series = data.series || [];
        if (series.length < 2) {
          host.appendChild(el('p', { class: 'muted' }, 'Not enough data for this window.'));
          return;
        }
        var first = series[0].value, last = series[series.length - 1].value;
        var chg = last - first;
        var chgPct = first ? (chg / Math.abs(first)) * 100 : 0;
        var values = series.map(function (p) { return p.value; });
        var hi = Math.max.apply(null, values), lo = Math.min.apply(null, values);
        var fmtV = opts.money ? fmtMoneyCompact : function (v) { return fmtNum(v, 2); };
        summary.appendChild(el('span', { class: 'chip' }, el('b', {}, series[0].date + ' → ' + series[series.length - 1].date)));
        summary.appendChild(el('span', { class: 'chip' }, 'Change ',
          el('b', { class: chg >= 0 ? 'gain' : 'loss' },
            (chg >= 0 ? '+' : '') + (opts.money ? fmtMoneyCompact(chg) : fmtNum(chg, 2))
            + ' (' + (chgPct >= 0 ? '+' : '') + chgPct.toFixed(1) + '%)')));
        summary.appendChild(el('span', { class: 'chip' }, 'High ', el('b', {}, fmtV(hi)), ' · Low ', el('b', {}, fmtV(lo))));
        if (data.badge) summary.appendChild(data.badge);
        host.appendChild(lineChart(series, { money: opts.money, baseline: first }));
        if (data.note) host.appendChild(data.note);
      } catch (e) {
        host.innerHTML = '';
        host.appendChild(el('div', { class: 'alert alert-warn' }, 'Could not load this window: ' + e.message));
      }
    }
    load();
    return node;
  }

  function payoffChart(points, opts) {
    opts = opts || {};
    var W = 680, H = 300, padL = 62, padR = 14, padT = 16, padB = 32;
    var svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, class: 'chart' });
    if (!points || points.length < 2) return svg;

    var xs = points.map(function (p) { return parseFloat(p.price); });
    var ys = points.map(function (p) { return p.profitCents; });
    var xMin = Math.min.apply(null, xs), xMax = Math.max.apply(null, xs);
    var yMin = Math.min.apply(null, ys), yMax = Math.max.apply(null, ys);
    if (yMin === yMax) { yMin -= 100; yMax += 100; }
    var yPad = (yMax - yMin) * 0.12;
    yMin -= yPad; yMax += yPad;

    function X(x) { return padL + (x - xMin) / (xMax - xMin) * (W - padL - padR); }
    function Y(y) { return padT + (yMax - y) / (yMax - yMin) * (H - padT - padB); }

    // gridlines + y labels
    niceTicks(yMin, yMax, 5).forEach(function (t) {
      svg.appendChild(svgEl('line', { class: 'grid', x1: padL, y1: Y(t), x2: W - padR, y2: Y(t) }));
      var txt = svgEl('text', { x: padL - 6, y: Y(t) + 3, 'text-anchor': 'end' });
      txt.textContent = fmtMoneyCompact(t);
      svg.appendChild(txt);
    });
    niceTicks(xMin, xMax, 7).forEach(function (t) {
      var txt = svgEl('text', { x: X(t), y: H - padB + 16, 'text-anchor': 'middle' });
      txt.textContent = t >= 1000 ? Math.round(t) : t.toFixed(0);
      svg.appendChild(txt);
    });

    var zeroY = Y(0);
    var line = points.map(function (p, i) { return (i ? 'L' : 'M') + X(xs[i]).toFixed(1) + ' ' + Y(ys[i]).toFixed(1); }).join(' ');
    // gain/loss shading against the zero line
    var closed = line + ' L' + X(xMax).toFixed(1) + ' ' + zeroY.toFixed(1) + ' L' + X(xMin).toFixed(1) + ' ' + zeroY.toFixed(1) + ' Z';
    var clipId = 'clip' + Math.floor(Math.random() * 1e9);
    var defs = svgEl('defs', {});
    var clipG = svgEl('clipPath', { id: clipId + 'g' });
    clipG.appendChild(svgEl('rect', { x: 0, y: 0, width: W, height: Math.max(0, zeroY) }));
    var clipL = svgEl('clipPath', { id: clipId + 'l' });
    clipL.appendChild(svgEl('rect', { x: 0, y: zeroY, width: W, height: Math.max(0, H - zeroY) }));
    defs.appendChild(clipG); defs.appendChild(clipL);
    svg.appendChild(defs);
    svg.appendChild(svgEl('path', { class: 'area-gain', d: closed, 'clip-path': 'url(#' + clipId + 'g)' }));
    svg.appendChild(svgEl('path', { class: 'area-loss', d: closed, 'clip-path': 'url(#' + clipId + 'l)' }));

    if (zeroY > padT && zeroY < H - padB) {
      svg.appendChild(svgEl('line', { class: 'zero', x1: padL, y1: zeroY, x2: W - padR, y2: zeroY }));
    }
    svg.appendChild(svgEl('path', { class: 'line', d: line }));

    (opts.breakevens || []).forEach(function (b) {
      var bx = parseFloat(b);
      if (isNaN(bx) || bx < xMin || bx > xMax) return;
      svg.appendChild(svgEl('circle', { class: 'be-dot', cx: X(bx), cy: zeroY, r: 4 }));
      var t = svgEl('text', { x: X(bx), y: zeroY - 8, 'text-anchor': 'middle', class: 'be-label' });
      t.textContent = 'BE ' + bx;
      svg.appendChild(t);
    });

    if (opts.spot !== undefined && opts.spot !== null) {
      var sx = X(opts.spot);
      if (sx >= padL && sx <= W - padR) {
        svg.appendChild(svgEl('line', { class: 'marker', x1: sx, y1: padT, x2: sx, y2: H - padB }));
        var st = svgEl('text', { x: sx + 5, y: padT + 11 });
        st.textContent = 'now ' + opts.spot.toFixed(2);
        svg.appendChild(st);
      }
    }

    // Slide anywhere on the curve: "at $price -> P/L" (exact — the payoff is piecewise linear)
    return interactiveChart(svg, { W: W, H: H, padL: padL, padR: padR, padT: padT, padB: padB }, function (frac) {
      var px = xMin + frac * (xMax - xMin);
      var i = 1;
      while (i < xs.length - 1 && xs[i] < px) i++;
      var x0 = xs[i - 1], x1 = xs[i], y0 = ys[i - 1], y1 = ys[i];
      var t = x1 === x0 ? 0 : (px - x0) / (x1 - x0);
      var pl = y0 + t * (y1 - y0);
      var vsSpot = opts.spot !== undefined && opts.spot !== null
        ? ' (' + ((px - opts.spot) / opts.spot * 100).toFixed(1) + '% vs now)' : '';
      return {
        x: X(px), y: Y(pl),
        lines: [
          'At $' + px.toFixed(2) + vsSpot,
          { text: 'P/L ' + fmtMoney(pl, { plus: true }), cls: pl >= 0 ? 'gain' : 'loss' }
        ]
      };
    });
  }

  /** Line/area chart for [{date, value}] series. opts: {money} */
  function lineChart(series, opts) {
    opts = opts || {};
    var W = 680, H = 240, padL = 62, padR = 14, padT = 12, padB = 30;
    var svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, class: 'chart' });
    if (!series || series.length < 2) return svg;
    // Window performance colors the line/area like every finance chart: green up, red down
    if (opts.baseline !== undefined && series[series.length - 1].value < opts.baseline) {
      svg.classList.add('chart-down');
    }
    var ys = series.map(function (p) { return p.value; });
    var yMin = Math.min.apply(null, ys), yMax = Math.max.apply(null, ys);
    if (yMin === yMax) { yMin -= 1; yMax += 1; }
    var yPad = (yMax - yMin) * 0.08;
    yMin -= yPad; yMax += yPad;
    function X(i) { return padL + i / (series.length - 1) * (W - padL - padR); }
    function Y(y) { return padT + (yMax - y) / (yMax - yMin) * (H - padT - padB); }

    var span = yMax - yMin;
    var narrow = span < Math.max(Math.abs(yMax), Math.abs(yMin)) * 0.05;
    var moneyNoCents = function (c) { return (c < 0 ? '−$' : '$') + Math.round(Math.abs(c) / 100).toLocaleString('en-US'); };
    var fmt = opts.money
      ? (narrow ? moneyNoCents : fmtMoneyCompact)
      : function (v) { return fmtNum(v, Math.abs(yMax) > 100 ? 0 : 2); };
    niceTicks(yMin, yMax, 4).forEach(function (t) {
      svg.appendChild(svgEl('line', { class: 'grid', x1: padL, y1: Y(t), x2: W - padR, y2: Y(t) }));
      var txt = svgEl('text', { x: padL - 6, y: Y(t) + 3, 'text-anchor': 'end' });
      txt.textContent = fmt(opts.money ? t : t);
      svg.appendChild(txt);
    });

    var line = series.map(function (p, i) { return (i ? 'L' : 'M') + X(i).toFixed(1) + ' ' + Y(p.value).toFixed(1); }).join(' ');
    svg.appendChild(svgEl('path', {
      class: 'area-line',
      d: line + ' L' + X(series.length - 1).toFixed(1) + ' ' + (H - padB) + ' L' + padL + ' ' + (H - padB) + ' Z'
    }));
    svg.appendChild(svgEl('path', { class: 'line', d: line }));

    [0, Math.floor(series.length / 2), series.length - 1].forEach(function (i, idx) {
      var anchor = idx === 0 ? 'start' : idx === 1 ? 'middle' : 'end';
      var t = svgEl('text', { x: X(i), y: H - padB + 16, 'text-anchor': anchor });
      t.textContent = series[i].date;
      svg.appendChild(t);
    });

    // Slide to read: date, value, and change vs the window start under the pointer
    var first = series[0].value;
    return interactiveChart(svg, { W: W, H: H, padL: padL, padR: padR, padT: padT, padB: padB }, function (frac) {
      var i = Math.round(frac * (series.length - 1));
      i = Math.max(0, Math.min(series.length - 1, i));
      var v = series[i].value;
      var pct = first ? ((v - first) / Math.abs(first)) * 100 : 0;
      return {
        x: X(i), y: Y(v),
        lines: [
          series[i].date,
          // Full precision under the cursor even when the axis rounds (prices deserve cents)
          { text: opts.money ? fmtMoney(v) : fmtNum(v, 2), cls: '' },
          { text: (pct >= 0 ? '+' : '') + pct.toFixed(1) + '% in window', cls: pct >= 0 ? 'gain' : 'loss' }
        ]
      };
    });
  }

  window.UI = {
    el: el,
    rangeChart: rangeChart,
    fmtMoney: fmtMoney,
    fmtMoneyCompact: fmtMoneyCompact,
    pnlSpan: pnlSpan,
    fmtPct: fmtPct,
    fmtNum: fmtNum,
    delta: delta,
    freshnessBadge: freshnessBadge,
    explain: explain,
    alertBox: alertBox,
    stat: stat,
    chip: chip,
    table: table,
    spinner: spinner,
    emptyState: emptyState,
    scoreBar: scoreBar,
    cardHeader: cardHeader,
    confirmModal: confirmModal,
    payoffChart: payoffChart,
    lineChart: lineChart,
    expandable: expandable,
    term: term
  };
})();
