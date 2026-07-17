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
   * Returns { el, ready, refresh(), refreshBand(key), scrollTo(key), reopen(key), fold(key), posture(key) }.
   */
  function render(opts) {
    var sections = opts.sections || [];
    var ctx = opts.ctx || {};
    var openOverrides = Rails.surface(opts.stateKey || 'flow:' + (opts.id || 'main'));
    var root = el('div', { class: 'flow', id: opts.id || null });
    var handles = {};
    var pendingRenders = new Set();
    var focusKey = opts.focus || null;
    // A deep-linked arrival is an attention move like any other: the target opens, bands
    // left open by earlier attention fold back to their conclusions or invitations.
    if (focusKey) {
      sections.forEach(function (s) { if (s.key !== focusKey) delete openOverrides[s.key]; });
      var focusIndex = sections.findIndex(function (s) { return s.key === focusKey; });
      var focusSection = focusIndex >= 0 ? sections[focusIndex] : null;
      if (focusSection && !focusSection.complete(ctx) && !readyOf(focusSection, focusIndex)) {
        // Arriving at a locked band must not strand the user with nothing open: the lock
        // stays visible with its reason, and attention falls to the first actionable band.
        focusKey = null;
      } else {
        openOverrides[focusKey] = 'open';
      }
    }

    function readyOf(section, index) {
      var priorComplete = sections.slice(0, index).every(function (s) { return !!s.complete(ctx); });
      return section.ready ? !!section.ready(ctx) : priorComplete;
    }

    // A band may own a stable route/anchor. The default opens locally; routed bands first
    // move browser history, then the mounted destination seam applies the same attention
    // change in place. This keeps deep links and Back/Forward without turning bands into pages.
    function openBand(section) {
      if (section.onOpen) return section.onOpen(api);
      return api.reopen(section.key);
    }

    // Default attention: the first ready-but-incomplete band on the REQUIRED path. Optional
    // bands (e.g. evidence interrogation) never hold attention by default — they never
    // complete, so they would pin the journey forever; they open on deep-link or invitation.
    function firstOpenIndex() {
      var optionalFallback = -1;
      for (var i = 0; i < sections.length; i++) {
        if (sections[i].complete(ctx) || !readyOf(sections[i], i)) continue;
        if (!sections[i].optional) return i;
        if (optionalFallback < 0) optionalFallback = i;
      }
      return optionalFallback;
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

    function trackRender(section, handle, host, value) {
      if (!value || typeof value.then !== 'function') return Promise.resolve();
      var generation = handle.generation;
      var pending = Promise.resolve(value).catch(function (error) {
        // A band owns its own async boundary. A superseded render may finish after a
        // targeted refresh; it no longer owns visible UI and must not overwrite it.
        if (handle.generation !== generation || !host.isConnected) return;
        host.replaceChildren(UI.alertBox('danger', section.title + ' could not load', [
          error && error.message ? error.message : 'The step did not finish.'
        ]));
        host.setAttribute('data-render-error', 'true');
      }).finally(function () { pendingRenders.delete(pending); });
      pendingRenders.add(pending);
      return pending;
    }

    function paintBand(section, index, force) {
      var posture = postureOf(section, index);
      var band = handles[section.key].el;
      var handle = handles[section.key];
      handle.generation += 1;
      if (force) {
        handle.aperture = null;
        handle.invitationRow = null;
      }

      // retainOnFold: the band's body stays mounted through fold/unfold — expensive
      // instruments (the fan canvas) survive by construction instead of re-rendering.
      // Folded = the aperture collapses (CSS) and the content goes inert; the invitation
      // row paints in front of it exactly like a non-retained band's.
      if (!force && section.retainOnFold && handle.aperture) {
        if (posture === 'active' || posture === 'revisit') {
          band.dataset.posture = 'active';
          band.classList.remove('is-folded');
          handle.aperture.firstChild.inert = false;
          if (handle.invitationRow) { handle.invitationRow.remove(); handle.invitationRow = null; }
          openOverrides[section.key] = 'open';
          return Promise.resolve();
        }
        if (posture === 'ready') {
          band.dataset.posture = 'ready';
          band.classList.add('is-folded');
          handle.aperture.firstChild.inert = true;
          if (handle.invitationRow) handle.invitationRow.remove();
          var retainedInvitation = (section.invitation && section.invitation(ctx)) || 'Ready — open this step.';
          handle.invitationRow = el('button', { type: 'button', class: 'flow-band-invitation',
            'aria-expanded': 'false',
            onclick: function () { openBand(section); } },
            el('span', { class: 'flow-band-invitation-body' }, retainedInvitation),
            el('span', { class: 'flow-band-open muted' }, 'Open'));
          band.insertBefore(handle.invitationRow, handle.aperture);
          return Promise.resolve();
        }
        // Any other posture (a real content change) falls through to the rebuild below.
        handle.aperture = null;
        handle.invitationRow = null;
      }

      band.innerHTML = '';
      band.dataset.posture = posture === 'revisit' ? 'active' : posture;
      band.classList.remove('is-folded');
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
        return Promise.resolve();
      }
      if (posture === 'ready') {
        var invitation = (section.invitation && section.invitation(ctx)) || 'Ready — open this step.';
        band.appendChild(el('button', { type: 'button', class: 'flow-band-invitation',
          'aria-expanded': 'false',
          onclick: function () { openBand(section); } },
          el('span', { class: 'flow-band-invitation-body' }, invitation),
          el('span', { class: 'flow-band-open muted' }, 'Open')));
        return Promise.resolve();
      }
      if (posture === 'done') {
        var conclusion = section.conclusion ? section.conclusion(ctx) : null;
        var bar = el('button', { type: 'button', class: 'flow-band-conclusion',
          'aria-expanded': 'false',
          onclick: function () {
            if (section.onOpen) { openBand(section); return; }
            openOverrides[section.key] = 'open'; paintBand(section, index);
          } },
          el('span', { class: 'flow-band-conclusion-body' }, conclusion || (section.title + ' — complete')),
          el('span', { class: 'flow-band-revisit muted' }, 'Revisit'));
        band.appendChild(bar);
        return Promise.resolve();
      }
      // active (or an explicitly revisited done band)
      openOverrides[section.key] = 'open';
      if (posture === 'revisit') {
        band.appendChild(el('button', { type: 'button', class: 'flow-band-fold muted',
          onclick: function () { delete openOverrides[section.key]; paintBand(section, index); } },
          'Done revising — fold to the conclusion'));
      }
      var host = el('div', { class: 'flow-band-body' });
      if (section.retainOnFold) {
        host.classList.add('flow-aperture-inner');
        var aperture = el('div', { class: 'flow-aperture' }, host);
        handles[section.key].aperture = aperture;
        band.appendChild(aperture);
      } else {
        band.appendChild(host);
      }
      return trackRender(section, handle, host, section.render(host, ctx, api));
    }

    var api = {
      el: root,
      refresh: function (force) {
        return Promise.all(sections.map(function (section, index) { return paintBand(section, index, force === true); }));
      },
      refreshBand: function (key, force) {
        var index = sections.findIndex(function (s) { return s.key === key; });
        if (index >= 0) {
          var paints = [paintBand(sections[index], index, force === true)];
          // A band's completion can unlock or re-lock its successors — repaint those postures.
          sections.slice(index + 1).forEach(function (s, i) {
            paints.push(paintBand(s, index + 1 + i, force === true));
          });
          return Promise.all(paints);
        }
        return Promise.resolve();
      },
      /** Enrich a completed band's visible conclusion in place. This is deliberately narrower
       *  than refreshBand(): it preserves the focused conclusion button and every later band,
       *  and refuses to paint after attention has reopened the band. */
      refreshConclusion: function (key) {
        var index = sections.findIndex(function (s) { return s.key === key; });
        if (index < 0 || postureOf(sections[index], index) !== 'done') return false;
        var body = handles[key].el.querySelector('.flow-band-conclusion-body');
        if (!body) return false;
        var conclusion = sections[index].conclusion ? sections[index].conclusion(ctx) : null;
        body.replaceChildren(conclusion || (sections[index].title + ' — complete'));
        return true;
      },
      scrollTo: function (key) {
        var target = root.querySelector('#band-' + key);
        if (!target) return;
        var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        target.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
        // A same-document SPA move is still a navigation event for keyboard and screen-reader
        // users. Put attention on the band heading without triggering another scroll.
        target.setAttribute('tabindex', '-1');
        try { target.focus({ preventScroll: true }); } catch (focusError) { target.focus(); }
        // Bands fill asynchronously, and content inflating ABOVE the target shoves it away
        // from the viewport — the arrival must hold its alignment until layout settles.
        // The user's own scroll always wins: any scroll intent cancels the hold.
        if (!('ResizeObserver' in window)) return;
        var cancelled = false;
        function cancel() { cancelled = true; cleanup(); }
        function cleanup() {
          observer.disconnect();
          ['wheel', 'touchstart', 'keydown', 'pointerdown', 'focusin'].forEach(function (kind) {
            window.removeEventListener(kind, cancel);
          });
        }
        var observer = new ResizeObserver(function () {
          if (cancelled) return;
          var top = target.getBoundingClientRect().top;
          if (Math.abs(top) > 24) target.scrollIntoView({ behavior: 'auto', block: 'start' });
        });
        observer.observe(root);
        // Any interaction means attention has landed: scroll intent, a click (whose handler
        // may own its own scroll, e.g. the editor's add-leg), or focus movement all win.
        ['wheel', 'touchstart', 'keydown', 'pointerdown', 'focusin'].forEach(function (kind) {
          window.addEventListener(kind, cancel, { passive: true, once: true });
        });
        setTimeout(cleanup, 2500);
      },
      /** Move attention without scrolling: the new focus opens, overrides attention left
       *  behind fold, postures repaint. Folding on attention-move is not a yank — the user
       *  is the one who moved on. A locked target falls back to default attention. */
      setFocus: function (key) {
        focusKey = key || null;
        if (focusKey) {
          sections.forEach(function (s) { if (s.key !== focusKey) delete openOverrides[s.key]; });
          var index = sections.findIndex(function (s) { return s.key === focusKey; });
          if (index >= 0 && !sections[index].complete(ctx) && !readyOf(sections[index], index)) {
            focusKey = null;
          } else {
            openOverrides[focusKey] = 'open';
          }
        }
        return api.refresh();
      },
      /** Move attention to a band and bring it into view. */
      reopen: function (key) {
        return api.setFocus(key).then(function () { api.scrollTo(key); });
      },
      /** Deliberately conclude a band (an explicit save-and-advance action). */
      fold: function (key) {
        if (focusKey === key) focusKey = null;
        delete openOverrides[key];
        return api.refreshBand(key);
      },
      posture: function (key) {
        var index = sections.findIndex(function (s) { return s.key === key; });
        return index >= 0 ? postureOf(sections[index], index) : null;
      }
    };

    sections.forEach(function (section) {
      handles[section.key] = { el: el('section', { class: 'flow-band', 'data-band': section.key }), generation: 0 };
      root.appendChild(handles[section.key].el);
    });
    api.ready = api.refresh();
    api.whenReady = function () {
      return api.ready.then(function settle() {
        if (!pendingRenders.size) return;
        return Promise.all(Array.from(pendingRenders)).then(settle);
      });
    };
    return api;
  }

  window.Flow = { render: render };
})();
