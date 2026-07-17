/* One visual contract for choosing how StrikeBench tests a view or position. */
(function () {
  'use strict';

  var el = UI.el;

  function workspace(opts) {
    var state = opts.state || {};
    var modes = opts.modes || [];
    if (!modes.length) throw new Error('Outcomes workspace requires at least one basis');
    if (!modes.some(function (m) { return m.key === state.mode; })) state.mode = modes[0].key;

    var shell = el('section', { class: 'outcomes-workspace', id: opts.id });
    if (opts.heading || opts.intro) {
      shell.appendChild(el('div', { class: 'outcomes-head' },
        opts.heading ? el('h3', {}, opts.heading) : null,
        opts.intro ? el('p', { class: 'muted' }, opts.intro) : null));
    }
    var nav = el('div', { class: 'outcome-basis-nav', id: opts.id + '-nav', role: 'tablist',
      'aria-label': opts.label || 'Outcome evidence basis', 'data-count': String(modes.length) });
    var note = el('div', { class: 'outcome-basis-note muted', id: opts.id + '-note' });
    var host = el('div', { class: 'outcome-basis-panel', id: opts.id + '-panel', role: 'tabpanel' });
    shell.appendChild(nav);
    shell.appendChild(note);
    shell.appendChild(host);

    var generation = 0;
    function selected() {
      return modes.find(function (m) { return m.key === state.mode; }) || modes[0];
    }
    function paintNav() {
      nav.innerHTML = '';
      modes.forEach(function (mode) {
        var active = mode.key === state.mode;
        var tab = el('button', { type: 'button', role: 'tab', id: opts.id + '-basis-' + mode.key,
          class: 'outcome-basis' + (active ? ' active' : ''),
          'data-basis': mode.key, 'aria-selected': active ? 'true' : 'false',
          'aria-controls': opts.id + '-panel', tabindex: active ? '0' : '-1',
          onclick: function () { select(mode.key, true); },
          onkeydown: function (e) {
            if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
            e.preventDefault();
            var index = modes.indexOf(mode) + (e.key === 'ArrowRight' ? 1 : -1);
            var next = modes[(index + modes.length) % modes.length];
            select(next.key, true);
          }
        },
        el('span', { class: 'outcome-basis-title' }, mode.label),
        el('span', { class: 'outcome-basis-sub' }, mode.description || ''),
        mode.available === false ? el('span', { class: 'outcome-basis-state' }, 'Setup needed') : null);
        nav.appendChild(el('div', { class: 'outcome-basis-wrap', role: 'presentation',
          'data-vocabulary': mode.info || null }, tab,
        mode.info ? el('span', { class: 'outcome-basis-help' }, UI.info(mode.info)) : null));
      });
    }
    function render() {
      var mode = selected();
      var token = ++generation;
      paintNav();
      host.setAttribute('aria-labelledby', opts.id + '-basis-' + mode.key);
      note.textContent = mode.note || mode.description || '';
      host.innerHTML = '';
      if (mode.available === false) {
        host.appendChild(UI.emptyState(mode.label + ' needs setup', mode.unavailableReason || mode.note || '',
          mode.setupLabel || 'Set it up', function () {
            if (typeof mode.setup === 'function') mode.setup();
          }));
      } else if (typeof mode.render === 'function') {
        try {
          var result = mode.render(host);
          if (result && typeof result.then === 'function') {
            result.catch(function (error) {
              if (token !== generation) return;
              host.innerHTML = '';
              host.appendChild(UI.alertBox('danger', 'Could not load this outcome view',
                [String(error && error.message || error)]));
            });
          }
        } catch (error) {
          host.appendChild(UI.alertBox('danger', 'Could not load this outcome view',
            [String(error && error.message || error)]));
        }
      }
      if (typeof opts.onChange === 'function') opts.onChange(mode.key);
    }
    function select(key, focus) {
      if (!modes.some(function (m) { return m.key === key; })) return;
      state.mode = key;
      render();
      if (focus) {
        var button = nav.querySelector('[data-basis="' + key + '"]');
        if (button) button.focus();
      }
    }

    render();
    return { el: shell, host: host, select: select, refresh: render, mode: function () { return state.mode; } };
  }

  window.Outcomes = { workspace: workspace };
})();
