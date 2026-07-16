/*
 * The Workspace flow: a document of bands, not a tab set (Program ONE §2.2).
 *
 * Each band declares how to tell whether it is COMPLETE (it has produced its output) and
 * READY (its inputs exist). The flow derives each band's posture from that alone:
 *   done   — collapsed to its CONCLUSION: the result it produced, never bare chrome;
 *            one tap re-opens the full band for revision.
 *   active — the first ready-but-incomplete band, rendered in full.
 *   locked — not ready; rendered as its title plus the REASON it is locked and what
 *            unlocks it. Locked bands are visible and honest, never hidden.
 * No wholesale repaints: bands re-render individually via their returned handles.
 */
(function () {
  'use strict';

  var el = UI.el;

  /**
   * sections: [{ key, title, info?, complete(ctx)->bool, ready?(ctx)->bool,
   *   lockedReason?(ctx)->string, render(host, ctx, api), conclusion?(ctx)->Node|string }]
   * ctx: caller-owned context (plan, market, Rails surface).
   * Returns { el, refresh(), refreshBand(key), scrollTo(key), posture(key) }.
   */
  function render(opts) {
    var sections = opts.sections || [];
    var ctx = opts.ctx || {};
    var openOverrides = Rails.surface(opts.stateKey || 'flow:' + (opts.id || 'main'));
    var root = el('div', { class: 'flow', id: opts.id || null });
    var handles = {};

    function postureOf(section, index) {
      var priorComplete = sections.slice(0, index).every(function (s) { return !!s.complete(ctx); });
      var ready = section.ready ? !!section.ready(ctx) : priorComplete;
      if (section.complete(ctx)) return openOverrides[section.key] === 'open' ? 'revisit' : 'done';
      return ready ? 'active' : 'locked';
    }

    function paintBand(section, index) {
      var posture = postureOf(section, index);
      var band = handles[section.key].el;
      band.innerHTML = '';
      band.dataset.posture = posture === 'revisit' ? 'active' : posture;
      var head = el('div', { class: 'flow-band-head' },
        el('h2', { class: 'flow-band-title', id: 'band-' + section.key },
          section.title, section.info ? UI.info(section.info) : null));
      band.appendChild(head);

      if (posture === 'locked') {
        var reason = (section.lockedReason && section.lockedReason(ctx))
          || 'Unlocks after the step above is complete.';
        band.appendChild(el('p', { class: 'flow-band-locked muted' }, reason));
        head.setAttribute('aria-disabled', 'true');
        return;
      }
      if (posture === 'done') {
        var conclusion = section.conclusion ? section.conclusion(ctx) : null;
        var bar = el('button', { type: 'button', class: 'flow-band-conclusion',
          'aria-expanded': 'false',
          onclick: function () { openOverrides[section.key] = 'open'; paintBand(section, index); } },
          el('span', { class: 'flow-band-conclusion-body' }, conclusion || (section.title + ' — complete')),
          el('span', { class: 'flow-band-revisit muted' }, 'Revisit'));
        band.appendChild(bar);
        return;
      }
      // active (or an explicitly revisited done band)
      if (posture === 'revisit') {
        band.appendChild(el('button', { type: 'button', class: 'flow-band-fold muted',
          onclick: function () { delete openOverrides[section.key]; paintBand(section, index); } },
          'Done revising — fold to the conclusion'));
      }
      var host = el('div', { class: 'flow-band-body' });
      band.appendChild(host);
      section.render(host, ctx, api);
    }

    var api = {
      el: root,
      refresh: function () { sections.forEach(paintBand); },
      refreshBand: function (key) {
        var index = sections.findIndex(function (s) { return s.key === key; });
        if (index >= 0) {
          paintBand(sections[index], index);
          // A band's completion can unlock or re-lock its successors — repaint those postures.
          sections.slice(index + 1).forEach(function (s, i) { paintBand(s, index + 1 + i); });
        }
      },
      scrollTo: function (key) {
        var target = root.querySelector('#band-' + key);
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      },
      /** Reopen a concluded band for revision and bring it into view. */
      reopen: function (key) {
        openOverrides[key] = 'open';
        api.refreshBand(key);
        api.scrollTo(key);
      },
      posture: function (key) {
        var index = sections.findIndex(function (s) { return s.key === key; });
        return index >= 0 ? postureOf(sections[index], index) : null;
      }
    };

    sections.forEach(function (section) {
      handles[section.key] = { el: el('section', { class: 'flow-band', 'data-band': section.key }) };
      root.appendChild(handles[section.key].el);
    });
    api.refresh();
    return api;
  }

  window.Flow = { render: render };
})();
