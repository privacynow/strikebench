/*
 * The Workspace flow: a document of bands, not a tab set (Program ONE §2.2).
 *
 * Each band declares how to tell whether it is COMPLETE (it has produced its output) and
 * READY (its inputs exist). The flow derives each band's posture from that plus ATTENTION —
 * one band is open by default (the deep-linked focus, else the first ready-but-incomplete):
 *   done   — collapsed to its CONCLUSION: the result it produced, never bare chrome;
 *            one tap re-opens the full band for revision.
 *   active — the open band, rendered in full.
 *   ready  — inputs exist but attention is elsewhere: a one-line invitation naming what
 *            the band holds (never a second open toolset stacked under the first).
 *   locked — not ready; rendered as its title plus the REASON it is locked and what
 *            unlocks it. Locked bands are visible and honest, never hidden.
 * No wholesale repaints: bands re-render individually via their returned handles.
 */
(function () {
  'use strict';

  var el = UI.el;

  /**
   * sections: [{ key, title, info?, complete(ctx)->bool, ready?(ctx)->bool,
   *   lockedReason?(ctx)->string, invitation?(ctx)->Node|string,
   *   render(host, ctx, api), conclusion?(ctx)->Node|string }]
   * ctx: caller-owned context (plan, market, Rails surface).
   * opts.focus: initial attention target (a deep-linked stage's band key).
   * Returns { el, refresh(), refreshBand(key), scrollTo(key), reopen(key), fold(key), posture(key) }.
   */
  function render(opts) {
    var sections = opts.sections || [];
    var ctx = opts.ctx || {};
    var openOverrides = Rails.surface(opts.stateKey || 'flow:' + (opts.id || 'main'));
    var root = el('div', { class: 'flow', id: opts.id || null });
    var handles = {};
    var focusKey = opts.focus || null;
    // A deep-linked arrival is an attention move like any other: the target opens, bands
    // left open by earlier attention fold back to their conclusions or invitations.
    if (focusKey) {
      sections.forEach(function (s) { if (s.key !== focusKey) delete openOverrides[s.key]; });
      openOverrides[focusKey] = 'open';
    }

    function readyOf(section, index) {
      var priorComplete = sections.slice(0, index).every(function (s) { return !!s.complete(ctx); });
      return section.ready ? !!section.ready(ctx) : priorComplete;
    }

    function firstOpenIndex() {
      for (var i = 0; i < sections.length; i++) {
        if (!sections[i].complete(ctx) && readyOf(sections[i], i)) return i;
      }
      return -1;
    }

    // Completing a band by acting INSIDE it never yanks its content away: a band that was
    // active this session stays open through its completion (posture 'revisit'), and the
    // conclusion-collapse applies when attention moves on — or immediately via the fold
    // control. The override map lives on the Rails surface, so it survives route re-renders
    // and level flips but not a fresh session.
    function postureOf(section, index) {
      if (section.complete(ctx)) {
        return openOverrides[section.key] === 'open' ? 'revisit' : 'done';
      }
      if (!readyOf(section, index)) return 'locked';
      if (section.key === focusKey || openOverrides[section.key] === 'open') return 'active';
      // Only the attention target renders open. Without an explicit focus, that is the
      // first ready-but-incomplete band; later ready bands are honest invitations — the
      // wall of simultaneously open toolsets dies here.
      return focusKey == null && index === firstOpenIndex() ? 'active' : 'ready';
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
        delete openOverrides[section.key];
        return;
      }
      if (posture === 'ready') {
        var invitation = (section.invitation && section.invitation(ctx)) || 'Ready — open this step.';
        band.appendChild(el('button', { type: 'button', class: 'flow-band-invitation',
          'aria-expanded': 'false',
          onclick: function () { api.reopen(section.key); } },
          el('span', { class: 'flow-band-invitation-body' }, invitation),
          el('span', { class: 'flow-band-open muted' }, 'Open')));
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
      openOverrides[section.key] = 'open';
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
        if (!target) return;
        var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        target.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
        // Bands fill asynchronously, and content inflating ABOVE the target shoves it away
        // from the viewport — the arrival must hold its alignment until layout settles.
        // The user's own scroll always wins: any scroll intent cancels the hold.
        if (!('ResizeObserver' in window)) return;
        var cancelled = false;
        function cancel() { cancelled = true; cleanup(); }
        function cleanup() {
          observer.disconnect();
          ['wheel', 'touchstart', 'keydown'].forEach(function (kind) {
            window.removeEventListener(kind, cancel);
          });
        }
        var observer = new ResizeObserver(function () {
          if (cancelled) return;
          var top = target.getBoundingClientRect().top;
          if (Math.abs(top) > 24) target.scrollIntoView({ behavior: 'auto', block: 'start' });
        });
        observer.observe(root);
        ['wheel', 'touchstart', 'keydown'].forEach(function (kind) {
          window.addEventListener(kind, cancel, { passive: true, once: true });
        });
        setTimeout(cleanup, 2500);
      },
      /** Move attention to a band: open it, fold the bands attention leaves behind, scroll.
       *  Folding on attention-move is not a yank — the user is the one who moved on. */
      reopen: function (key) {
        focusKey = key;
        sections.forEach(function (s) { if (s.key !== key) delete openOverrides[s.key]; });
        openOverrides[key] = 'open';
        api.refresh();
        api.scrollTo(key);
      },
      /** Deliberately conclude a band (an explicit save-and-advance action). */
      fold: function (key) {
        if (focusKey === key) focusKey = null;
        delete openOverrides[key];
        api.refreshBand(key);
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
