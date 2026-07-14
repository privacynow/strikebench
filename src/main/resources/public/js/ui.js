/* StrikeBench UI helpers: DOM building, formatting, badges, charts, modals. */
(function () {
  'use strict';

  /** el('div', {class:'x', onclick:fn}, child1, 'text', ...) */
  function el(tag, attrs) {
    var node = document.createElement(tag);
    attrs = attrs || {};
    Object.keys(attrs).forEach(function (k) {
      var v = attrs[k];
      if (v === null || v === undefined || v === false) return;
      if (k === 'class') node.className = v;
      else if (k === 'html') node.innerHTML = v;
      else if (k.indexOf('on') === 0 && typeof v === 'function') node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v === true ? '' : v);
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

  var fieldSequence = 0;

  /** Keep every visible form label programmatically tied to its control. */
  function field(labelContent, control, opts) {
    opts = opts || {};
    if (!control.id) control.id = 'ui-field-' + (++fieldSequence);
    var labelAttrs = { for: control.id };
    if (opts.labelClass) labelAttrs.class = opts.labelClass;
    var wrap = el('div', { class: 'field' + (opts.className ? ' ' + opts.className : '') },
      el('label', labelAttrs, labelContent), control);
    if (opts.hint) wrap.appendChild(el('span', { class: 'muted small' }, opts.hint));
    return wrap;
  }

  /** Apply one roving-tabstop keyboard contract to locally composed tab lists. */
  function bindTabList(list, onActivate) {
    list._activateTab = onActivate;
    if (!list._tabKeysBound) {
      list._tabKeysBound = true;
      list.addEventListener('keydown', function (event) {
        var current = event.target.closest('[role="tab"]');
        if (!current || !list.contains(current)) return;
        var tabs = Array.from(list.querySelectorAll('[role="tab"]')).filter(function (tab) {
          return !tab.disabled && getComputedStyle(tab).display !== 'none';
        });
        var index = tabs.indexOf(current), next = null;
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = tabs[(index + 1) % tabs.length];
        else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = tabs[(index - 1 + tabs.length) % tabs.length];
        else if (event.key === 'Home') next = tabs[0];
        else if (event.key === 'End') next = tabs[tabs.length - 1];
        if (!next) return;
        event.preventDefault();
        tabs.forEach(function (tab) {
          tab.setAttribute('aria-selected', tab === next ? 'true' : 'false');
          tab.tabIndex = tab === next ? 0 : -1;
        });
        next.focus();
        if (typeof list._activateTab === 'function') list._activateTab(next, tabs.indexOf(next));
      });
    }
    list.syncTabs = function () {
      var tabs = Array.from(list.querySelectorAll('[role="tab"]'));
      var selected = tabs.find(function (tab) { return tab.getAttribute('aria-selected') === 'true'; }) || tabs[0];
      tabs.forEach(function (tab) { tab.tabIndex = tab === selected ? 0 : -1; });
    };
    list.syncTabs();
    return list;
  }

  // ---- formatting ----

  function finiteNumber(value) {
    return value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value));
  }

  function fmtMoney(cents, opts) {
    if (!finiteNumber(cents)) return '—';
    cents = Number(cents);
    var sign = cents < 0 ? '−' : (opts && opts.plus && cents > 0 ? '+' : '');
    var abs = Math.abs(Math.round(cents));
    var dollars = Math.floor(abs / 100).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return sign + '$' + dollars + '.' + String(abs % 100).padStart(2, '0');
  }

  /** Compact for chart axes: $1.2k / $85k / $1.3M */
  function fmtMoneyCompact(cents) {
    if (!finiteNumber(cents)) return '—';
    cents = Number(cents);
    var v = cents / 100;
    var sign = v < 0 ? '−' : '';
    var a = Math.abs(v);
    if (a >= 1e6) return sign + '$' + (a / 1e6).toFixed(1) + 'M';
    if (a >= 10000) return sign + '$' + Math.round(a / 1000) + 'k';
    if (a >= 1000) return sign + '$' + (a / 1000).toFixed(1) + 'k';
    return sign + '$' + Math.round(a);
  }

  function pnlSpan(cents, cls) {
    if (!finiteNumber(cents)) return el('span', { class: 'muted' }, '—');
    cents = Number(cents);
    return el('span', { class: (cents >= 0 ? 'gain' : 'loss') + (cls ? ' ' + cls : '') },
      fmtMoney(cents, { plus: true }));
  }

  function fmtPct(x, digits) {
    if (!finiteNumber(x)) return '—';
    x = Number(x);
    var out = (x * 100).toFixed(digits === undefined ? 0 : digits);
    if (parseFloat(out) === 0) out = out.replace('-', ''); // '−0.0%' is a formatting artifact, not a loss
    return out + '%';
  }

  function fmtNum(x, digits) {
    if (!finiteNumber(x)) return '—';
    x = Number(x);
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

  /** First listed option contract in a mixed stock/options package. */
  function firstOptionLeg(legs) {
    return (legs || []).find(function (leg) {
      return leg && leg.type !== 'STOCK' && finiteNumber(leg.strike) && leg.expiration;
    }) || null;
  }

  // ---- small components ----

  var FRESH_CLASS = {
    REALTIME: 'badge-ok', FIXTURE: 'badge-dim', DELAYED: 'badge-warn', SIMULATED: 'badge-sim',
    EOD: 'badge-caution', MODELED: 'badge-caution', STALE: 'badge-danger', MISSING: 'badge-danger'
  };
  function freshnessBadge(freshness) {
    if (!freshness) return null;
    var label = freshness === 'FIXTURE' ? 'DEMO DATA' : freshness;
    return el('span', { class: 'badge ' + (FRESH_CLASS[freshness] || 'badge-dim'),
      'aria-label': 'Data freshness: ' + label }, label);
  }

  /** Provenance and age are separate facts. Accepts the API DataEvidence object. */
  function evidenceBadge(evidence, opts) {
    if (!evidence) return null;
    if (typeof evidence === 'string') evidence = { provenance: evidence };
    var p = String(evidence.provenance || 'MISSING').toUpperCase();
    var age = String(evidence.age || '').toUpperCase();
    var compact = !!(opts && opts.compact);
    var label = p === 'DEMO' ? (compact ? 'DEMO' : 'DEMO · FABRICATED')
      : p === 'SIMULATED' ? 'SIMULATED'
      : p === 'MODELED' ? 'MODELED'
      : p === 'MIXED' ? 'MIXED SOURCES'
      : p === 'MISSING' ? ((opts && opts.missingLabel) || 'DATA UNAVAILABLE')
      : p;
    if ((p === 'OBSERVED' || p === 'BROKER') && age && age !== 'NOT_APPLICABLE') {
      label += compact && age === 'REALTIME' ? '' : ' · ' + age;
    }
    var cls = p === 'OBSERVED' || p === 'BROKER'
      ? (age === 'STALE' ? 'badge-danger' : age === 'DELAYED' ? 'badge-warn' : 'badge-ok')
      : p === 'DEMO' ? 'badge-warn'
      : p === 'SIMULATED' ? 'badge-sim'
      : p === 'MODELED' || p === 'MIXED' ? 'badge-caution' : 'badge-dim';
    return el('span', { class: 'badge evidence-badge ' + cls + (opts && opts.className ? ' ' + opts.className : ''),
      title: evidence.source ? ('Source: ' + evidence.source) : null }, label);
  }

  function explain(text) {
    var node = el('span', { class: 'explain' }, text);
    var i = icon('info', 13);
    i.className = 'icon explain-ico';
    node.insertBefore(i, node.firstChild);
    return node;
  }

  function alertBox(kind, title, items) {
    var box = el('div', { class: 'alert alert-' + kind }, title);
    if (items && items.length) {
      box.appendChild(el('ul', {}, items.map(function (r) { return el('li', {}, r); })));
    }
    return box;
  }

  /** Nonblocking status for actions that do not own a natural inline result region. */
  function toast(message, kind) {
    var region = document.getElementById('toast-region');
    if (!region) {
      region = el('div', { id: 'toast-region', class: 'toast-region', 'aria-live': 'polite', 'aria-atomic': 'true' });
      document.body.appendChild(region);
    }
    region.innerHTML = '';
    var item = el('div', { class: 'toast-message toast-' + (kind || 'info'), role: kind === 'error' ? 'alert' : 'status' },
      String(message || 'Action could not be completed'));
    region.appendChild(item);
    window.setTimeout(function () { if (item.isConnected) item.remove(); }, kind === 'error' ? 7000 : 4000);
    return item;
  }

  function stat(label, valueNode, explainText) {
    return el('div', { class: 'stat' },
      el('div', { class: 'label' }, label),
      el('div', { class: 'value' }, valueNode),
      explainText ? explain(explainText) : null);
  }

  var _bmSeq = 0;
  /** The StrikeBench mark: a bench that kinks upward at the strike — the payoff story. */
  function brandMark(size) {
    var gid = 'bmk' + (++_bmSeq);
    var svg = svgEl('svg', { viewBox: '0 0 24 24', width: size || 22, height: size || 22,
      class: 'brand-mark', 'aria-hidden': 'true' });
    var defs = svgEl('defs', {});
    var grad = svgEl('linearGradient', { id: gid, x1: '0', y1: '0', x2: '1', y2: '1' });
    grad.appendChild(svgEl('stop', { offset: '0', 'stop-color': '#2f6bde' }));
    grad.appendChild(svgEl('stop', { offset: '1', 'stop-color': '#7c4fe0' }));
    defs.appendChild(grad);
    svg.appendChild(defs);
    svg.appendChild(svgEl('rect', { width: '24', height: '24', rx: '6', fill: 'url(#' + gid + ')' }));
    svg.appendChild(svgEl('path', { d: 'M5 19h14', stroke: 'rgba(255,255,255,.4)',
      'stroke-width': '1.2', 'stroke-linecap': 'round', 'stroke-dasharray': '1.4 2.6' }));
    svg.appendChild(svgEl('path', { d: 'M5 15.5h6l7.5-8.5', stroke: '#fff', 'stroke-width': '2.2',
      fill: 'none', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }));
    svg.appendChild(svgEl('circle', { cx: '11', cy: '15.5', r: '1.9', fill: '#fff' }));
    return svg;
  }

  /** ISO instant/date -> the USER'S local calendar date (UTC slicing showed evening-US users tomorrow). */
  function fmtDate(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso).slice(0, 10);
    var m = String(d.getMonth() + 1).padStart(2, '0'), day = String(d.getDate()).padStart(2, '0');
    return d.getFullYear() + '-' + m + '-' + day;
  }

  function chip(label, valueNode, title) {
    var structuredHelp = label && label.nodeType === 1
      && (label.matches('.info-trigger,.term') || label.querySelector('.info-trigger,.term'));
    return el('span', { class: 'chip', title: structuredHelp ? null : title || null },
      el('span', { class: 'chip-label' }, label), el('b', {}, valueNode));
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
  function scoreBar(score, label) {
    var pct = Math.max(0, Math.min(100, score));
    var cls = pct >= 70 ? 'ok' : pct >= 45 ? 'caution' : 'danger';
    return el('span', { class: 'score-wrap', 'aria-label': label || 'Score' },
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
    var chevron = el('span', { class: 'xp-chevron', 'aria-hidden': 'true' }, '\u203A');
    var head = el('button', { type: 'button', class: 'xp-head', 'aria-expanded': 'false' }, chevron, summaryContent);
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

  /** A term of art with tap-to-define glossary popover (Beginner level). */
  function term(word, display) {
    // ONE explanation system (review P1): a term backed by the INFO registry opens the SAME
    // level-aware info bubble at BOTH levels — the expert plain-span fallback left the riskiest
    // labels with literally nothing. Glossary-only terms keep the beginner popover.
    var key = word.toLowerCase();
    if (window.Learn && Learn.INFO && Learn.INFO[key]) {
      if (infoUsed.indexOf(key) < 0) infoUsed.push(key);
      var t2 = el('button', { class: 'term', type: 'button', 'data-term': key,
        'aria-expanded': 'false' }, display || word);
      t2.addEventListener('click', function (e) { e.stopPropagation(); openInfo(t2, key); });
      t2.addEventListener('focus', function () { if (!infoSuppressFocusOpen) openInfo(t2, key); });
      return t2;
    }
    var def = window.Learn && Learn.GLOSSARY[key];
    if (!def || (window.Learn && Learn.currentLevel() === 'expert')) {
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
  function confirmModal(title, bodyNode, confirmLabel, onConfirm, danger, onCancel) {
    var root = document.getElementById('modal-root');
    var returnFocus = document.activeElement;
    var titleId = 'modal-title-' + Date.now().toString(36);
    var closed = false;
    root.innerHTML = '';
    var msg = el('div', { class: 'alert alert-danger', style: 'display:none', role: 'alert', 'aria-live': 'assertive' });
    function showError(text) {
      msg.textContent = text;
      msg.style.display = '';
    }
    var confirmBtn = el('button', {
      class: 'btn ' + (danger ? 'btn-danger' : ''), id: 'modal-confirm', type: 'button',
      onclick: function () {
        confirmBtn.disabled = true;
        Promise.resolve(onConfirm()).then(function () { close(true); }).catch(function (e) {
          confirmBtn.disabled = false;
          showError(e.message || 'Failed');
        });
      }
    }, confirmLabel);
    var cancelBtn = el('button', { class: 'btn btn-secondary', type: 'button', onclick: function () { close(); } }, 'Cancel');
    var dialog = el('div', { class: 'modal', role: 'dialog', tabindex: '-1', 'aria-modal': 'true', 'aria-labelledby': titleId },
        el('h3', { id: titleId }, title),
        bodyNode,
        msg,
        el('div', { class: 'btn-row' },
          confirmBtn,
          cancelBtn));
    var backdrop = el('div', { class: 'modal-backdrop', onclick: function (e) {
      if (e.target === backdrop) close();
    } }, dialog);
    function focusable() {
      return Array.prototype.slice.call(dialog.querySelectorAll(
        'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'))
        .filter(function (n) { return n.offsetParent !== null; });
    }
    function onKey(e) {
      if (e.key === 'Escape') { e.preventDefault(); close(); return; }
      if (e.key !== 'Tab') return;
      var items = focusable();
      if (!items.length) { e.preventDefault(); dialog.focus(); return; }
      var first = items[0], last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
    function close(force) {
      if (closed) return;
      // Escape/backdrop cannot cancel a request already sent. Keep the modal until the
      // operation resolves so the screen never implies that a committed action was undone.
      if (confirmBtn.disabled && !force) return;
      closed = true;
      document.removeEventListener('keydown', onKey);
      root.innerHTML = '';
      if (!force && typeof onCancel === 'function') onCancel();
      if (returnFocus && returnFocus.isConnected && returnFocus.focus) returnFocus.focus();
    }
    root.appendChild(backdrop);
    document.addEventListener('keydown', onKey);
    // Establish containment before returning from the click handler. The first usable
    // control takes focus on the next task, but an open modal never leaves focus behind it.
    dialog.focus();
    window.setTimeout(function () {
      var items = focusable();
      (items[0] || dialog).focus();
    }, 0);
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
  function interactiveChart(svg, geom, probe, accessibleLabel) {
    var wrap = el('div', { class: 'chart-wrap' });
    interactiveChart._seq = (interactiveChart._seq || 0) + 1;
    var tipId = 'chart-readout-' + interactiveChart._seq;
    svg.setAttribute('tabindex', '0');
    svg.setAttribute('role', 'group');
    svg.setAttribute('aria-label', accessibleLabel || 'Interactive chart. Use left and right arrow keys to inspect values.');
    svg.setAttribute('aria-describedby', tipId);
    wrap.appendChild(svg);
    var tip = el('div', { class: 'chart-tip', id: tipId, style: 'display:none',
      'aria-live': 'polite', 'aria-atomic': 'true' });
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
    function showAt(frac, box) {
      frac = Math.max(0, Math.min(1, frac));
      box = box || svg.getBoundingClientRect();
      if (!box.width) return false;
      var hit = probe(frac);
      if (!hit) { hide(); return false; }
      vline.setAttribute('x1', hit.x); vline.setAttribute('x2', hit.x);
      dot.setAttribute('cx', hit.x); dot.setAttribute('cy', hit.y);
      vline.style.display = ''; dot.style.display = '';
      tip.innerHTML = '';
      hit.lines.forEach(function (l, i) {
        tip.appendChild(el('div', { class: i === 0 ? 'tt-title' : 'tt-line' + (l.cls ? ' ' + l.cls : '') },
          typeof l === 'string' ? l : l.text));
      });
      tip.style.display = '';
      var px = hit.x / geom.W * box.width;
      var flip = px > box.width * 0.62;
      tip.style.left = flip ? '' : (px + 12) + 'px';
      tip.style.right = flip ? (box.width - px + 12) + 'px' : '';
      tip.style.top = Math.max(0, hit.y / geom.H * box.height - 14) + 'px';
      return true;
    }
    var keyboardFrac = 0.5;
    svg.addEventListener('pointerleave', hide);
    svg.addEventListener('pointermove', function (ev) {
      var box = svg.getBoundingClientRect();
      if (!box.width) return;
      var plotX0 = geom.padL / geom.W * box.width;
      var plotX1 = (geom.W - geom.padR) / geom.W * box.width;
      var frac = (ev.clientX - box.left - plotX0) / Math.max(1, plotX1 - plotX0);
      if (frac < 0 || frac > 1) { hide(); return; }
      keyboardFrac = frac;
      showAt(frac, box);
    });
    svg.addEventListener('focus', function (ev) {
      if (ev.target === svg) showAt(keyboardFrac);
    });
    svg.addEventListener('blur', function (ev) {
      if (!svg.contains(ev.relatedTarget)) hide();
    });
    svg.addEventListener('keydown', function (ev) {
      if (ev.target !== svg) return; // strike sliders inside payoff charts own their keys
      if (ev.key === 'Home') keyboardFrac = 0;
      else if (ev.key === 'End') keyboardFrac = 1;
      else if (ev.key === 'ArrowLeft') keyboardFrac = Math.max(0, keyboardFrac - 0.04);
      else if (ev.key === 'ArrowRight') keyboardFrac = Math.min(1, keyboardFrac + 0.04);
      else return;
      ev.preventDefault();
      showAt(keyboardFrac);
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
      var reqKey = selected; // supersede token: guards BOTH success and error paths (a rejected fetch
                             // for an old window must not wipe the window the user is now looking at)
      host.innerHTML = '';
      summary.innerHTML = '';
      host.appendChild(spinner('Loading ' + selected.toUpperCase() + '…'));
      try {
        var data = await opts.fetch(selected);
        if (reqKey !== selected) return; // a newer pill was picked while this loaded
        host.innerHTML = '';
        summary.innerHTML = '';
        var series = data.series || (data.candles || []).map(function (c) {
          return { date: String(c.date), value: parseFloat(c.close) };
        });
        if (series.length < 2) {
          // The caller knows WHY it's empty (no source vs narrow window) — say that, honestly
          host.appendChild(el('p', { class: 'muted' }, data.emptyText || 'Not enough data for this window.'));
          if (data.note) host.appendChild(data.note);
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
        host.appendChild(data.candles
          ? candleChart(data.candles, { baseline: first })
          : lineChart(series, { money: opts.money, baseline: first }));
        if (data.note) host.appendChild(data.note);
      } catch (e) {
        if (reqKey !== selected) return; // don't let an old window's failure wipe the current chart
        host.innerHTML = '';
        host.appendChild(el('div', { class: 'alert alert-warn' }, 'Could not load this window: ' + e.message));
      }
    }
    load();
    return node;
  }

  /**
   * OHLC candlestick chart (D3 scales/ticks; our own crosshair). Falls back to the plain
   * close line when the vendored D3 failed to load or the window is too thin — graceful,
   * never blank. Windows beyond ~160 bars aggregate to weekly candles for readability.
   */
  function candleChart(candles, opts) {
    opts = opts || {};
    var closesOnly = (candles || []).map(function (c) { return { date: String(c.date), value: parseFloat(c.close) }; });
    if (!window.d3 || !candles || candles.length < 2) {
      return lineChart(closesOnly, { baseline: closesOnly.length ? closesOnly[0].value : undefined });
    }
    var bars = candles.map(function (c) {
      return { date: String(c.date), o: parseFloat(c.open), h: parseFloat(c.high),
               l: parseFloat(c.low), c: parseFloat(c.close), v: Number(c.volume) || 0 };
    });
    if (bars.length > 160) {
      var byWeek = [], cur = null, curKey = null;
      bars.forEach(function (b) {
        var dt = new Date(b.date + 'T12:00:00');
        var key = dt.getUTCFullYear() + '-' + Math.floor((dt.getTime() / 86400000 + 4) / 7);
        if (key !== curKey) {
          curKey = key;
          cur = { date: b.date, o: b.o, h: b.h, l: b.l, c: b.c, v: b.v };
          byWeek.push(cur);
        } else {
          cur.h = Math.max(cur.h, b.h); cur.l = Math.min(cur.l, b.l);
          cur.c = b.c; cur.v += b.v; cur.date = b.date; // bar dated at its last session
        }
      });
      bars = byWeek;
    }
    var W = 680, H = 300, padL = 62, padR = 14, padT = 16, padB = 32;
    var x = d3.scaleBand().domain(bars.map(function (b) { return b.date; }))
      .range([padL, W - padR]).paddingInner(0.35).paddingOuter(0.2);
    var y = d3.scaleLinear()
      .domain([d3.min(bars, function (b) { return b.l; }), d3.max(bars, function (b) { return b.h; })])
      .nice().range([H - padB, padT]);
    var svg = d3.create('svg').attr('viewBox', '0 0 ' + W + ' ' + H).attr('class', 'chart candles');

    y.ticks(5).forEach(function (t) {
      svg.append('line').attr('class', 'grid')
        .attr('x1', padL).attr('x2', W - padR).attr('y1', y(t)).attr('y2', y(t));
      svg.append('text').attr('class', 'tick').attr('x', padL - 8).attr('y', y(t) + 4)
        .attr('text-anchor', 'end').text(fmtNum(t, t >= 1000 ? 0 : 2));
    });
    // Five evenly-spaced labels include both endpoints. The old modulo+append approach could
    // create a sixth label only a few sessions before the final date, making those two collide.
    var labelCount = Math.min(5, bars.length);
    var labelIndexes = [];
    for (var li = 0; li < labelCount; li++) {
      var labelIndex = labelCount === 1 ? 0 : Math.round(li * (bars.length - 1) / (labelCount - 1));
      if (labelIndexes.indexOf(labelIndex) < 0) labelIndexes.push(labelIndex);
    }
    labelIndexes.forEach(function (i) {
      var b = bars[i];
      var anchor = i === 0 ? 'start' : i === bars.length - 1 ? 'end' : 'middle';
      svg.append('text').attr('class', 'tick')
        .attr('x', x(b.date) + x.bandwidth() / 2).attr('y', H - 10)
        .attr('text-anchor', anchor).text(b.date);
    });
    bars.forEach(function (b) {
      var cx = x(b.date) + x.bandwidth() / 2;
      var up = b.c >= b.o;
      svg.append('line').attr('class', 'candle-wick')
        .attr('x1', cx).attr('x2', cx).attr('y1', y(b.h)).attr('y2', y(b.l));
      svg.append('rect').attr('class', up ? 'candle candle-up' : 'candle candle-down')
        .attr('x', x(b.date)).attr('width', Math.max(1, x.bandwidth()))
        .attr('y', y(Math.max(b.o, b.c)))
        .attr('height', Math.max(1, Math.abs(y(b.o) - y(b.c))));
    });

    var first = bars[0].c;
    function probe(frac) {
      var px = padL + frac * (W - padL - padR);
      var i = Math.max(0, Math.min(bars.length - 1,
        Math.round((px - padL - x.bandwidth() / 2) / x.step())));
      var b = bars[i];
      var pct = first ? (b.c - first) / Math.abs(first) * 100 : 0;
      return {
        x: x(b.date) + x.bandwidth() / 2, y: y(b.c),
        lines: [
          b.date,
          'O ' + fmtNum(b.o, 2) + '  H ' + fmtNum(b.h, 2),
          'L ' + fmtNum(b.l, 2) + '  C ' + fmtNum(b.c, 2),
          { text: (pct >= 0 ? '+' : '') + pct.toFixed(1) + '% in window', cls: pct >= 0 ? 'gain' : 'loss' },
          b.v ? 'Vol ' + (b.v >= 1e6 ? (b.v / 1e6).toFixed(1) + 'M' : b.v >= 1e3 ? Math.round(b.v / 1e3) + 'k' : b.v) : null
        ].filter(Boolean)
      };
    }
    return interactiveChart(svg.node(), { W: W, H: H, padL: padL, padR: padR, padT: padT, padB: padB }, probe,
      'Price candlestick chart. Use left and right arrow keys to inspect trading sessions.');
  }

  function payoffChart(points, opts) {
    opts = opts || {};
    var W = 680, H = 300, padL = 62, padR = 14, padT = 16, padB = 32;
    var svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, class: 'chart' });
    if (!points || points.length < 2) return svg;

    var xs = points.map(function (p) { return parseFloat(p.price); });
    var ys = points.map(function (p) { return p.profitCents; });
    var xMin = Math.min.apply(null, xs), xMax = Math.max.apply(null, xs);

    // KNOT-BASED X-DOMAIN: the server grid spans spot±30%, but a tight vertical's action lives in
    // a few-dollar slice — untrimmed it renders as a sliver. Window = knots (slope changes) +
    // breakevens + spot + current handle strikes, padded; pure-linear payoffs keep the full span.
    (function trimDomain() {
      var interesting = [];
      for (var k = 1; k < xs.length - 1; k++) {
        var dx0 = xs[k] - xs[k - 1] || 1, dx1 = xs[k + 1] - xs[k] || 1;
        var s0 = (ys[k] - ys[k - 1]) / dx0, s1 = (ys[k + 1] - ys[k]) / dx1;
        if (Math.abs(s1 - s0) > 1e-6 * Math.max(1, Math.abs(s0), Math.abs(s1))) interesting.push(xs[k]);
      }
      if (!interesting.length) return; // linear (stock) — nothing to focus on
      (opts.breakevens || []).forEach(function (b) { var v = parseFloat(b); if (!isNaN(v)) interesting.push(v); });
      if (opts.expectedMove) { interesting.push(opts.expectedMove.low); interesting.push(opts.expectedMove.high); }
      if (opts.spot !== undefined && opts.spot !== null) interesting.push(opts.spot);
      (opts.handles || []).forEach(function (h) { if (h && typeof h.strike === 'number') interesting.push(h.strike); });
      var iLo = Math.min.apply(null, interesting), iHi = Math.max.apply(null, interesting);
      var padX = Math.max((iHi - iLo) * 0.35, (opts.spot || iHi || 1) * 0.03);
      var lo = Math.max(xMin, iLo - padX), hi = Math.min(xMax, iHi + padX);
      if (hi - lo <= 1e-9 || hi - lo >= (xMax - xMin) * 0.98) return; // no meaningful trim
      function yAt(px) {
        var i = 1;
        while (i < xs.length - 1 && xs[i] < px) i++;
        var t = xs[i] === xs[i - 1] ? 0 : (px - xs[i - 1]) / (xs[i] - xs[i - 1]);
        return ys[i - 1] + t * (ys[i] - ys[i - 1]);
      }
      var nxs = [lo], nys = [yAt(lo)];
      for (var j = 0; j < xs.length; j++) {
        if (xs[j] > lo && xs[j] < hi) { nxs.push(xs[j]); nys.push(ys[j]); }
      }
      nxs.push(hi); nys.push(yAt(hi));
      xs = nxs; ys = nys; xMin = lo; xMax = hi;
    })();

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

    // ±1σ expected-move band: the market's own likely range to expiry, drawn UNDER the curve so
    // "your shorts sit inside the expected move" is visible geometry, not prose.
    if (opts.expectedMove && opts.expectedMove.low < opts.expectedMove.high) {
      var emL = Math.max(xMin, opts.expectedMove.low), emH = Math.min(xMax, opts.expectedMove.high);
      if (emH > emL) {
        svg.appendChild(svgEl('rect', { class: 'em-band', x: X(emL), y: padT,
          width: Math.max(0, X(emH) - X(emL)), height: H - padT - padB }));
        var emt = svgEl('text', { x: X(emL) + 4, y: padT + 12, class: 'em-label' });
        emt.textContent = 'expected move';
        svg.appendChild(emt);
      }
    }
    if (opts.spot !== undefined && opts.spot !== null) {
      var sx = X(opts.spot);
      if (sx >= padL && sx <= W - padR) {
        svg.appendChild(svgEl('line', { class: 'marker', x1: sx, y1: padT, x2: sx, y2: H - padB }));
        // bottom of the plot: the top strip belongs to the strike handles
        var st = svgEl('text', { x: sx + 5, y: H - padB - 8 });
        st.textContent = 'now ' + opts.spot.toFixed(2);
        svg.appendChild(st);
      }
    }

    // Draggable strike handles: grab a marker, slide it along real chain strikes, and the
    // owner re-prices the position. THE learning tool — move the strike, watch the worst
    // case, best case and odds change. opts.handles: [{id,strike,label,strikes[],onChange}]
    (opts.handles || []).forEach(function (h, hi) {
      if (!h.strikes || !h.strikes.length) return;
      var cur = h.strike;
      if (cur < xMin || cur > xMax) return; // off the payoff grid — the selects still work
      var hx = X(cur);
      // Every handle gets its OWN row (condor strikes sit close together — shared rows
      // mash the labels), and its label rides BESIDE the grip, flipping near the right edge.
      var gy = padT + 10 + (hi % 4) * 22;
      var lineEl = svgEl('line', { class: 'strike-line', x1: hx, y1: padT, x2: hx, y2: H - padB });
      var grip = svgEl('circle', { class: 'strike-grip', cx: hx, cy: gy, r: 8, 'data-handle': h.id,
        tabindex: '0', role: 'slider', 'aria-label': h.label,
        'aria-valuenow': String(cur), 'aria-valuetext': h.label });
      var lbl = svgEl('text', { class: 'strike-grip-label', y: gy + 4 });
      function placeLabel(nx, text) {
        var flip = nx > W - 130;
        lbl.setAttribute('x', flip ? nx - 13 : nx + 13);
        lbl.setAttribute('text-anchor', flip ? 'end' : 'start');
        lbl.textContent = text;
      }
      placeLabel(hx, h.label);
      svg.appendChild(lineEl); svg.appendChild(grip); svg.appendChild(lbl);

      var snapped = cur;
      function moveTo(k) {
        var nx = X(k);
        lineEl.setAttribute('x1', nx); lineEl.setAttribute('x2', nx);
        grip.setAttribute('cx', nx);
        placeLabel(nx, k === cur ? h.label : h.label.replace(/[\d.]+$/, '') + k);
      }
      grip.addEventListener('pointerdown', function (ev) {
        ev.stopPropagation(); ev.preventDefault();
        grip.setPointerCapture(ev.pointerId);
        grip.classList.add('dragging');
      });
      grip.addEventListener('pointermove', function (ev) {
        if (!grip.classList.contains('dragging')) return;
        ev.stopPropagation();
        var box = svg.getBoundingClientRect();
        if (!box.width) return;
        var vx = (ev.clientX - box.left) / box.width * W;
        var price = xMin + (vx - padL) / (W - padL - padR) * (xMax - xMin);
        var best = snapped, bd = Infinity;
        h.strikes.forEach(function (k) {
          if (k < xMin || k > xMax) return; // outside the focused window — the selects still reach it
          var d = Math.abs(k - price); if (d < bd) { bd = d; best = k; }
        });
        if (best !== snapped) { snapped = best; moveTo(best); }
      });
      grip.addEventListener('pointerup', function (ev) {
        ev.stopPropagation();
        grip.classList.remove('dragging');
        if (snapped !== cur && h.onChange) h.onChange(snapped);
      });
      grip.addEventListener('pointercancel', function () {
        grip.classList.remove('dragging');
        snapped = cur; moveTo(cur);
      });
      // Keyboard operation: arrow keys step to the neighboring listed strike (within the
      // visible domain), Enter/Space commits — the drag interaction, without a pointer.
      grip.addEventListener('keydown', function (ev) {
        if (ev.key !== 'ArrowLeft' && ev.key !== 'ArrowRight' && ev.key !== 'Enter' && ev.key !== ' ') return;
        ev.preventDefault();
        if (ev.key === 'Enter' || ev.key === ' ') {
          if (snapped !== cur && h.onChange) h.onChange(snapped);
          return;
        }
        var inDomain = h.strikes.filter(function (k) { return k >= xMin && k <= xMax; }).sort(function (a, b) { return a - b; });
        var idx = inDomain.indexOf(snapped);
        if (idx < 0) idx = inDomain.indexOf(cur);
        var next = ev.key === 'ArrowLeft' ? inDomain[Math.max(0, idx - 1)] : inDomain[Math.min(inDomain.length - 1, idx + 1)];
        if (next !== undefined && next !== snapped) {
          snapped = next; moveTo(next);
          grip.setAttribute('aria-valuenow', String(next));
        }
      });
    });

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
    }, 'Expiration payoff chart. Use left and right arrow keys to inspect profit or loss by expiration price.');
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
    var compareKey = opts.compareKey;
    var hasComparison = !!compareKey && series.every(function (p) {
      return Number.isFinite(Number(p[compareKey]));
    });
    var ys = series.map(function (p) { return p.value; });
    if (hasComparison) ys = ys.concat(series.map(function (p) { return Number(p[compareKey]); }));
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
    if (hasComparison) {
      var compareLine = series.map(function (p, i) {
        return (i ? 'L' : 'M') + X(i).toFixed(1) + ' ' + Y(Number(p[compareKey])).toFixed(1);
      }).join(' ');
      svg.appendChild(svgEl('path', { class: 'benchmark-line', d: compareLine }));
    }

    [0, Math.floor(series.length / 2), series.length - 1].filter(function (i, idx, all) {
      return all.indexOf(i) === idx;
    }).forEach(function (i) {
      var anchor = i === 0 ? 'start' : i === series.length - 1 ? 'end' : 'middle';
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
      var lines = [
        series[i].date,
        { text: (opts.primaryLabel ? opts.primaryLabel + ' ' : '') + (opts.money ? fmtMoney(v) : fmtNum(v, 2)), cls: '' },
        { text: (pct >= 0 ? '+' : '') + pct.toFixed(1) + '% in window', cls: pct >= 0 ? 'gain' : 'loss' }
      ];
      if (hasComparison) {
        var comparison = Number(series[i][compareKey]);
        lines.push({ text: (opts.compareLabel || 'Comparison') + ' ' + (opts.money ? fmtMoney(comparison) : fmtNum(comparison, 2)), cls: '' });
      }
      return {
        x: X(i), y: Y(v),
        // Full precision under the cursor even when the axis rounds (prices deserve cents).
        lines: lines
      };
    }, hasComparison
      ? 'Account value and benchmark comparison chart. Use left and right arrow keys to inspect dates and values.'
      : 'Price history chart. Use left and right arrow keys to inspect dates and values.');
  }

  // ---- SVG icon system: the ONLY pictographic language in the app (no emoji, ever). ----
  var ICON_PATHS = {
    target: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="4"/><circle cx="12" cy="12" r="0.5" fill="currentColor"/>',
    shield: '<path d="M12 3l7 3v5c0 4.6-3 8.1-7 10-4-1.9-7-5.4-7-10V6z"/><path d="M9 12l2 2 4-4"/>',
    coins: '<ellipse cx="12" cy="6" rx="7" ry="2.6"/><path d="M5 6v6c0 1.5 3.1 2.6 7 2.6s7-1.1 7-2.6V6"/><path d="M5 12v6c0 1.5 3.1 2.6 7 2.6s7-1.1 7-2.6v-6"/>',
    chart: '<path d="M4 19h16"/><path d="M5 15l4-5 3 3 6-8"/><circle cx="18" cy="5" r="1.2"/>',
    flask: '<path d="M10 3h4"/><path d="M11 3v6l-5 9a2 2 0 0 0 1.8 3h8.4a2 2 0 0 0 1.8-3l-5-9V3"/><path d="M8.5 15h7"/>',
    scope: '<circle cx="12" cy="12" r="3.5"/><path d="M12 2.5v4M12 17.5v4M2.5 12h4M17.5 12h4"/>',
    sprout: '<path d="M12 21v-8"/><path d="M12 13c0-4 3-6.5 8-6.5 0 4.7-3 6.5-8 6.5z"/><path d="M12 13c0-3-2.4-4.8-6-4.8 0 3.6 2.4 4.8 6 4.8z"/>',
    compass: '<circle cx="12" cy="12" r="9"/><path d="M15.5 8.5l-2 5-5 2 2-5z"/>',
    bolt: '<path d="M13 2.5L5.5 13H11l-1 8.5L17.5 11H12z"/>',
    tag: '<path d="M3.5 12L12 3.5h6a2 2 0 0 1 2 2v6L11.5 20a2 2 0 0 1-2.8 0l-5.2-5.2a2 2 0 0 1 0-2.8z"/><circle cx="15.5" cy="8.5" r="1.4"/>',
    flag: '<path d="M5.5 21V4"/><path d="M5.5 4.5h12l-2.5 3.7 2.5 3.8h-12"/>',
    home: '<path d="M4 11l8-7 8 7"/><path d="M6.5 9.5V20h11V9.5"/>',
    pen: '<path d="M4 20l1-4L16.5 4.5a2.1 2.1 0 0 1 3 3L8 19z"/><path d="M13.5 7.5l3 3"/>',
    grid: '<rect x="4" y="4" width="7" height="7" rx="1"/><rect x="13" y="4" width="7" height="7" rx="1"/><rect x="4" y="13" width="7" height="7" rx="1"/><rect x="13" y="13" width="7" height="7" rx="1"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M19.1 4.9l-1.8 1.8M6.7 17.3l-1.8 1.8"/>',
    moon: '<path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"/>',
    halftone: '<circle cx="12" cy="12" r="8.5"/><path d="M12 3.5v17A8.5 8.5 0 0 0 12 3.5z" fill="currentColor" stroke="none"/>',
    warn: '<path d="M12 3.5L21.5 20h-19z"/><path d="M12 10v4.5"/><circle cx="12" cy="17.2" r="0.6" fill="currentColor"/>',
    check: '<path d="M5 12.5l4.2 4.2L19 7"/>',
    info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5"/><circle cx="12" cy="8" r="0.6" fill="currentColor"/>',
    magnifier: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.4 15.4L21 21"/>',
    archive: '<rect x="3" y="4" width="18" height="5" rx="1"/><path d="M5 9v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9"/><path d="M10 13h4"/>',
    trash: '<path d="M4 7h16"/><path d="M9 7V4h6v3"/><path d="M7 7l1 13h8l1-13"/><path d="M10 11v5M14 11v5"/>',
    'chevron-right': '<path d="M9 5l7 7-7 7"/>'
  };
  function icon(name, size) {
    var span = el('span', { class: 'icon' });
    span.innerHTML = '<svg viewBox="0 0 24 24" width="' + (size || 22) + '" height="' + (size || 22)
      + '" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
      + (ICON_PATHS[name] || '') + '</svg>';
    return span;
  }

  var TIME_SPREADS = ['CALENDAR_CALL', 'CALENDAR_PUT', 'DIAGONAL_CALL', 'DIAGONAL_PUT'];
  function profitCeilingKind(strategy, structureGroup, value, legs) {
    if (value !== null && value !== undefined) return 'finite';
    var expirations = {};
    (legs || []).forEach(function (leg) {
      if (!leg || leg.stock || String(leg.type || '').toUpperCase() === 'STOCK' || !leg.expiration) return;
      expirations[String(leg.expiration)] = true;
    });
    if (structureGroup === 'time' || Object.keys(expirations).length > 1
        || TIME_SPREADS.indexOf(String(strategy || '').toUpperCase()) >= 0) {
      return 'model-dependent';
    }
    return 'uncapped';
  }
  function maxProfitLabel(strategy, structureGroup, value, beginner, legs) {
    var kind = profitCeilingKind(strategy, structureGroup, value, legs);
    if (kind === 'finite') return fmtMoney(value);
    if (kind === 'model-dependent') return 'model-dependent';
    return beginner ? 'no fixed ceiling' : 'uncapped';
  }

  /**
   * Route skeleton painted synchronously while a screen's data loads: a title bar, a
   * stat row, and two card blocks with a quiet shimmer. Generic on purpose — it exists
   * so no screen is ever a blank void, not to mimic each layout.
   */
  function skeleton() {
    var wrap = el('div', { class: 'skel-screen', 'aria-hidden': 'true' },
      el('div', { class: 'skel skel-title' }),
      el('div', { class: 'skel-row' },
        el('div', { class: 'skel skel-stat' }), el('div', { class: 'skel skel-stat' }),
        el('div', { class: 'skel skel-stat' }), el('div', { class: 'skel skel-stat' })),
      el('div', { class: 'skel skel-card' }),
      el('div', { class: 'skel skel-card skel-card-short' }));
    return wrap;
  }


  /**
   * Block E: the ONE explanation primitive. A small, always-visible, quiet trigger beside a
   * technical label; hover opens the bubble after ~550ms, focus/click/tap opens immediately.
   * One-sentence summary first; a small [+] expands the level-appropriate detail in place.
   * One bubble at a time; Escape/outside-click/blur closes; edge-aware; zero layout shift.
   */
  var infoPop = null;
  var infoUsed = window.__usedInfoTerms = window.__usedInfoTerms || [];
  var infoTrigger = null; // the trigger that owns the open bubble (focus returns to it on close)
  var infoSuppressFocusOpen = false; // Escape's return-focus must not immediately reopen
  function closeInfo(returnFocus) {
    if (infoPop) {
      if (infoPop._detachTriggerKey) infoPop._detachTriggerKey();
      infoPop.remove(); infoPop = null;
    }
    if (infoTrigger) {
      infoTrigger.setAttribute('aria-expanded', 'false');
      infoTrigger.removeAttribute('aria-describedby');
      if (returnFocus && document.contains(infoTrigger)) {
        infoSuppressFocusOpen = true;
        infoTrigger.focus();
        setTimeout(function () { infoSuppressFocusOpen = false; }, 120);
      }
      infoTrigger = null;
    }
    document.removeEventListener('click', onDocClickInfo, true);
    document.removeEventListener('keydown', onDocKeyInfo, true);
    window.removeEventListener('resize', onInfoScroll, true);
  }
  function onDocClickInfo(ev) { if (infoPop && !infoPop.contains(ev.target) && ev.target !== infoTrigger) closeInfo(); }
  function onDocKeyInfo(ev) { if (ev.key === 'Escape') closeInfo(true); }
  // The bubble is positioned ABSOLUTELY in page coordinates, so it scrolls WITH its trigger —
  // structural anchoring, no scroll listeners, no floating over unrelated numbers. Resize
  // still closes (the layout genuinely changed).
  function onInfoScroll() { closeInfo(); }
  function openInfo(trigger, key) {
    if (!trigger.isConnected) return; // a re-render replaced the trigger mid-hover
    closeInfo();
    var def = window.Learn && Learn.INFO && Learn.INFO[key];
    if (!def) return;
    infoTrigger = trigger;
    var beginner = window.Learn && Learn.currentLevel && Learn.currentLevel() === 'beginner';
    var detail = beginner ? def.beginner : def.expert;
    // role=dialog, not tooltip: it CONTAINS an interactive control (the expand button), and the
    // trigger references it via aria-describedby so screen readers announce the content.
    infoPop = el('div', { class: 'info-pop', role: 'dialog', 'aria-label': def.short, id: 'info-pop' });
    trigger.setAttribute('aria-expanded', 'true');
    trigger.setAttribute('aria-describedby', 'info-pop');
    var body = el('div', { class: 'info-short' }, def.short);
    var more = el('div', { class: 'info-detail', style: 'display:none' }, detail);
    var expand = el('button', { class: 'info-expand', type: 'button', 'aria-expanded': 'false',
      'aria-label': 'More detail', onclick: function (ev) {
        ev.stopPropagation();
        var open = more.style.display !== 'none';
        more.style.display = open ? 'none' : '';
        expand.setAttribute('aria-expanded', String(!open));
        expand.textContent = open ? '+' : '\u2212';
        place();
      } }, '+');
    infoPop.appendChild(el('div', { class: 'info-row' }, body, expand));
    infoPop.appendChild(more);
    // Body-append (absolute page coordinates stay correct regardless of positioned ancestors);
    // keyboard reachability comes from EXPLICIT focus management: Tab on the trigger moves into
    // the bubble, Tab/Escape from the [+] returns to the trigger — the standard dialog pattern.
    document.body.appendChild(infoPop);
    function onTriggerKey(ev) {
      if (ev.key === 'Tab' && !ev.shiftKey && infoPop) {
        ev.preventDefault();
        expand.focus();
      }
    }
    trigger.addEventListener('keydown', onTriggerKey);
    expand.addEventListener('keydown', function (ev) {
      if (ev.key === 'Tab') { ev.preventDefault(); closeInfo(true); }
    });
    infoPop._detachTriggerKey = function () { trigger.removeEventListener('keydown', onTriggerKey); };
    function place() {
      var r = trigger.getBoundingClientRect();
      var w = infoPop.offsetWidth, h = infoPop.offsetHeight;
      var left = Math.min(Math.max(8, r.left), window.innerWidth - w - 8) + window.scrollX;
      var top = r.bottom + 6 + window.scrollY;
      if (r.bottom + 6 + h > window.innerHeight - 8) top = Math.max(8, r.top - h - 6) + window.scrollY;
      infoPop.style.left = left + 'px';
      infoPop.style.top = top + 'px';
    }
    place();
    setTimeout(function () {
      document.addEventListener('click', onDocClickInfo, true);
      document.addEventListener('keydown', onDocKeyInfo, true);
      window.addEventListener('resize', onInfoScroll, true);
    }, 0);
  }
  /** A visible, quiet info trigger for a registry term. Never pairs with a native title. */
  function info(termKey) {
    if (infoUsed.indexOf(termKey) < 0) infoUsed.push(termKey);
    var t = el('button', { class: 'info-trigger', type: 'button', 'data-term': termKey,
      'aria-expanded': 'false', 'aria-label': 'What does this mean?' }, 'i');
    var hoverTimer = null;
    t.addEventListener('mouseenter', function () {
      hoverTimer = setTimeout(function () { openInfo(t, termKey); }, 550);
    });
    t.addEventListener('mouseleave', function () {
      if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null; }
      // the bubble persists so the pointer can travel into it; outside-click/Escape closes
    });
    t.addEventListener('click', function (ev) {
      // Info triggers sometimes live inside a destination card. Their one deliberate
      // exception to card navigation must suppress both bubbling and the anchor default.
      ev.preventDefault(); ev.stopPropagation(); openInfo(t, termKey);
    });
    t.addEventListener('focus', function () { if (!infoSuppressFocusOpen) openInfo(t, termKey); });
    t.addEventListener('blur', function () { setTimeout(function () {
      if (infoPop && !infoPop.contains(document.activeElement) && document.activeElement !== t) closeInfo();
    }, 150); });
    return t;
  }

  /**
   * Compact price sparkline for selection cards: one polyline, gain/loss colored by the
   * window's net move, with a pointer/keyboard readout (date · price) sized for reading
   * glasses (>=14px). Data comes from ONE /api/sparklines batch — this component never
   * fetches. Unavailable data renders an honest quiet line, never a fabricated squiggle.
   */
  function sparkline(spark, opts) {
    opts = opts || {};
    var h = opts.height || 40;
    if (!spark || !spark.available || !spark.closes || spark.closes.length < 2) {
      if (opts.quietMissing) {
        return el('div', { class: 'spark spark-empty spark-empty-quiet', style: 'height:' + h + 'px',
          'aria-label': opts.missingText || 'Price history unavailable' },
          el('span', { class: 'muted' }, opts.missingText || 'No chart'));
      }
      return el('div', { class: 'spark spark-empty', style: 'height:' + h + 'px' },
        el('span', { class: 'muted' }, 'history unavailable'), info('historycoverage'));
    }
    var closes = spark.closes.map(Number);
    var dates = spark.dates || [];
    var W = 300, H = 60;
    var min = Math.min.apply(null, closes), max = Math.max.apply(null, closes);
    var span = (max - min) || 1;
    var pts = closes.map(function (c, i) {
      var x = closes.length === 1 ? 0 : (i / (closes.length - 1)) * W;
      var y = H - 6 - ((c - min) / span) * (H - 12);
      return [x, y];
    });
    var up = closes[closes.length - 1] >= closes[0];
    var svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, preserveAspectRatio: 'none',
      class: 'spark-svg ' + (up ? 'spark-up' : 'spark-down'), 'aria-hidden': 'true' });
    svg.style.height = h + 'px';
    var line = svgEl('polyline', { points: pts.map(function (p) { return p[0].toFixed(1) + ',' + p[1].toFixed(1); }).join(' '),
      fill: 'none', 'stroke-width': '2.5', 'vector-effect': 'non-scaling-stroke' });
    var dot = svgEl('circle', { r: '3.5', style: 'display:none', class: 'spark-dot' });
    svg.appendChild(line); svg.appendChild(dot);
    var readout = el('div', { class: 'spark-readout', 'aria-live': 'polite' });
    function fmtDay(d) {
      try { var dt = new Date(d + 'T12:00:00'); return dt.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }); }
      catch (e) { return d; }
    }
    var base = fmtDay(dates[0] || '') + ' \u2192 ' + fmtDay(dates[dates.length - 1] || '') + ' \u00b7 '
      + (up ? '+' : '') + (((closes[closes.length - 1] - closes[0]) / closes[0]) * 100).toFixed(1) + '%';
    readout.textContent = base;
    function showAt(idx) {
      idx = Math.max(0, Math.min(closes.length - 1, idx));
      dot.setAttribute('cx', pts[idx][0].toFixed(1));
      dot.setAttribute('cy', pts[idx][1].toFixed(1));
      dot.style.display = '';
      readout.textContent = fmtDay(dates[idx] || '') + ' \u00b7 $' + closes[idx].toFixed(2);
    }
    function clearReadout() { dot.style.display = 'none'; readout.textContent = base; }
    svg.addEventListener('pointermove', function (ev) {
      var r = svg.getBoundingClientRect();
      if (!r.width) return;
      showAt(Math.round(((ev.clientX - r.left) / r.width) * (closes.length - 1)));
    });
    svg.addEventListener('pointerleave', clearReadout);
    // The readout may wrap on narrow cards. A fixed h+20 wrapper let that second line spill into
    // the evidence badge below; reserve a minimum but let the component grow to its real content.
    var wrap = el('div', { class: 'spark', style: 'min-height:' + (h + 20) + 'px' }, svg, readout);
    // Keyboard exploration when the OWNING card has focus: left/right walk the points.
    wrap.exploreKey = function (key) {
      if (key !== 'ArrowLeft' && key !== 'ArrowRight') return false;
      var cur = wrap._ki === undefined ? closes.length - 1 : wrap._ki;
      wrap._ki = key === 'ArrowLeft' ? Math.max(0, cur - 1) : Math.min(closes.length - 1, cur + 1);
      showAt(wrap._ki);
      return true;
    };
    return wrap;
  }

  /**
   * One symbol-context primitive for every market-facing workflow.
   *
   * editable: one stock can change before work is priced;
   * locked: a priced/placed package names its stock but cannot mutate in place;
   * multi: a portfolio or simulated market exposes an explicit focus selector.
   *
   * Quote status is fetched once per settled edit and always carries the server's source
   * evidence. Callers own the workflow consequence of a change through onCommit/onPick.
   */
  function symbolContext(opts) {
    opts = opts || {};
    var mode = opts.mode || 'editable';
    var symbol = String(opts.symbol || '').toUpperCase();
    var wrap = el('section', {
      class: 'symbol-context-bar symbol-context-' + mode + (opts.compact ? ' symbol-context-compact' : '')
        + (opts.showStatus === false ? ' symbol-context-no-status' : '')
        + (opts.class ? ' ' + opts.class : ''),
      id: opts.id || null,
      'aria-label': opts.ariaLabel || (mode === 'multi' ? 'Market symbols' : 'Stock context')
    });
    var labelText = opts.label || (mode === 'editable' ? 'Symbol' : mode === 'locked' ? 'Position symbol' : 'Market focus');
    var status = el('div', { class: 'symbol-context-status muted', 'aria-live': 'polite' });
    var seq = 0, timer = null;

    function evidenceFor(q) {
      if (!q) return null;
      if (q.evidence) return evidenceBadge(q.evidence);
      if (q.provenance) return evidenceBadge(q.provenance);
      if (q.freshness) return freshnessBadge(q.freshness);
      return null;
    }
    async function loadStatus(next) {
      next = String(next || '').trim().toUpperCase();
      var mySeq = ++seq;
      status.innerHTML = '';
      if (!next || !window.API) return;
      status.appendChild(el('span', { class: 'muted' }, 'Checking market…'));
      try {
        var data = await window.API.get('/api/quotes?symbols=' + encodeURIComponent(next));
        if (mySeq !== seq || !status.isConnected) return;
        status.innerHTML = '';
        var q = (data.quotes || [])[0];
        if (!q) {
          status.appendChild(el('span', { class: 'symbol-context-unavailable' }, 'Market quote unavailable'));
          return;
        }
        status.appendChild(el('b', { class: 'symbol-context-price' }, fmtNum(q.last)));
        status.appendChild(delta(q.last, q.prevClose));
        var ev = evidenceFor(q);
        if (ev) status.appendChild(ev);
        if (q.asOf || q.sourceTimestamp) {
          status.appendChild(el('span', { class: 'muted symbol-context-asof' },
            'as of ' + fmtDate(q.asOf || q.sourceTimestamp)));
        }
      } catch (e) {
        if (mySeq !== seq || !status.isConnected) return;
        status.innerHTML = '';
        status.appendChild(el('span', { class: 'symbol-context-unavailable' }, 'Market quote unavailable'));
      }
    }

    if (mode === 'multi') {
      wrap.appendChild(el('div', { class: 'symbol-context-heading' },
        el('span', { class: 'symbol-context-label' }, labelText),
        opts.hint ? el('span', { class: 'muted symbol-context-hint' }, opts.hint) : null));
      var rail = el('div', { class: 'symbol-context-symbols', role: 'listbox', 'aria-label': labelText });
      (opts.symbols || []).forEach(function (s) {
        s = String(s).toUpperCase();
        var choice = el('button', {
          type: 'button', class: 'sym-chip' + (s === symbol ? ' active' : ''),
          'data-symbol': s,
          role: 'option', 'aria-selected': s === symbol ? 'true' : 'false',
          onclick: function () {
            symbol = s;
            rail.querySelectorAll('[role="option"]').forEach(function (b) {
              var active = b.getAttribute('data-symbol') === s;
              b.classList.toggle('active', active);
              b.setAttribute('aria-selected', active ? 'true' : 'false');
            });
            loadStatus(s);
            if (opts.onPick) opts.onPick(s);
          }
        }, s);
        rail.appendChild(choice);
      });
      wrap.appendChild(rail);
      if (symbol) { wrap.appendChild(status); loadStatus(symbol); }
      return wrap;
    }

    var primary = el('div', { class: 'symbol-context-primary' });
    if (mode === 'locked') {
      primary.appendChild(el('div', { class: 'symbol-context-heading' },
        el('span', { class: 'symbol-context-label' }, labelText),
        el('span', { class: 'symbol-context-symbol' }, symbol),
        el('span', { class: 'badge badge-dim' }, 'LOCKED')));
      if (symbol) loadStatus(symbol);
    } else {
      var input = opts.input || el('input', {
        type: 'text', value: symbol, list: opts.list || 'universe-symbols',
        placeholder: opts.placeholder || 'AAPL'
      });
      if (!input.id) input.id = (opts.id || 'symbol-context') + '-input';
      input.value = input.value || symbol;
      if (opts.hideLabel) input.setAttribute('aria-label', labelText);
      var field = el('div', { class: 'symbol-context-field' },
        opts.hideLabel ? null : el('label', { for: input.id, class: 'symbol-context-label' }, labelText), input);
      primary.appendChild(field);
      function settle() {
        var next = String(input.value || '').trim().toUpperCase();
        input.value = next;
        if (opts.showStatus !== false) loadStatus(next);
        if (opts.onInput) opts.onInput(next, input);
      }
      input.addEventListener('input', function () {
        clearTimeout(timer);
        timer = setTimeout(settle, opts.debounceMs || 350);
      });
      input.addEventListener('change', settle);
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          e.preventDefault(); settle();
          if (opts.onCommit) opts.onCommit(input.value.trim().toUpperCase(), input);
        }
      });
      wrap.input = input;
      if (opts.showStatus !== false) loadStatus(input.value);
    }
    wrap.appendChild(primary);
    if (opts.showStatus !== false) wrap.appendChild(status);
    var actions = el('div', { class: 'symbol-context-actions' });
    if (mode === 'editable' && opts.onCommit) {
      actions.appendChild(el('button', { type: 'button', class: 'btn btn-sm', id: opts.commitId || null, onclick: function () {
        var next = String(wrap.input.value || '').trim().toUpperCase();
        wrap.input.value = next;
        opts.onCommit(next, wrap.input);
      } }, opts.commitLabel || 'Apply'));
    }
    if (opts.onResearch && symbol) {
      actions.appendChild(el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
        onclick: function () { opts.onResearch(symbol); } }, 'Research'));
    }
    if (mode === 'locked' && opts.onChange) {
      actions.appendChild(el('button', { type: 'button', class: 'btn btn-sm btn-secondary',
        onclick: opts.onChange }, 'Change symbol'));
    }
    if (actions.children.length) wrap.appendChild(actions);
    wrap.refresh = function (next) { symbol = String(next || '').toUpperCase(); loadStatus(symbol); };
    return wrap;
  }

  window.UI = {
    info: info,
    sparkline: sparkline,
    symbolContext: symbolContext,
    fmtDate: fmtDate,
    el: el,
    field: field,
    bindTabList: bindTabList,
    icon: icon,
    profitCeilingKind: profitCeilingKind,
    maxProfitLabel: maxProfitLabel,
    skeleton: skeleton,
    rangeChart: rangeChart,
    brandMark: brandMark,
    fmtMoney: fmtMoney,
    fmtMoneyCompact: fmtMoneyCompact,
    pnlSpan: pnlSpan,
    fmtPct: fmtPct,
    fmtNum: fmtNum,
    finiteNumber: finiteNumber,
    firstOptionLeg: firstOptionLeg,
    delta: delta,
    freshnessBadge: freshnessBadge,
    evidenceBadge: evidenceBadge,
    explain: explain,
    alertBox: alertBox,
    toast: toast,
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
    candleChart: candleChart,
    expandable: expandable,
    term: term
  };
})();
