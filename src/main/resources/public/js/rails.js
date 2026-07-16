/*
 * One state owner per surface (Program ONE, level-as-lens contract).
 *
 * A level flip is a full re-render; anything held in a view closure dies with it. This rail
 * gives surface state a sanctioned home that outlives re-renders and level flips: one owned
 * object per surface, under a LEVEL-INVARIANT key. Level is a lens over this state — flipping
 * Beginner/Expert must never change what exists, only how much of it is revealed.
 *
 * Scope note: this rail is in-memory continuity (results, selections, acknowledgments,
 * transient run artifacts). Durable drafts stay on Workspace persistence; plan-stage results
 * stay server-re-hydrated; disclosure open-state stays on UI.expandable's keyed map. Those
 * three rails already exist — this one replaces only the closure-local state class.
 */
(function () {
  'use strict';

  function store() {
    if (!window.App || !App.state) throw new Error('Rails requires App.state (load order)');
    return (App.state.surfaces = App.state.surfaces || {});
  }

  /**
   * The durable state object for a surface. Keys must be stable and level-invariant —
   * they name the surface and its subject ("plan:12:outcomes"), never the level, never
   * display text. `seed` initializes on first access only.
   */
  function surface(key, seed) {
    if (!key || typeof key !== 'string') throw new Error('Rails.surface requires a stable string key');
    if (/beginner|expert/i.test(key)) {
      throw new Error('Rails keys are level-invariant; level must never appear in a key: ' + key);
    }
    var s = store();
    if (!Object.prototype.hasOwnProperty.call(s, key)) {
      s[key] = seed ? Object.assign({}, seed) : {};
    }
    return s[key];
  }

  /** Drop a surface's state (e.g. its subject was deleted). */
  function reset(key) {
    var s = store();
    delete s[key];
  }

  /**
   * Serialized snapshot of every owned surface — the flip-without-loss test contract:
   * snapshot, flip levels twice, snapshot again, assert byte-identical.
   */
  function snapshot() {
    try {
      return JSON.stringify(store(), function (k, v) {
        return v && v.nodeType ? undefined : v; // DOM nodes are never owned state
      });
    } catch (e) {
      throw new Error('Rails surfaces must stay JSON-serializable (no cycles, no DOM): ' + e.message);
    }
  }

  window.Rails = { surface: surface, reset: reset, snapshot: snapshot };
})();
