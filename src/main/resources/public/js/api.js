/* StrikeBench API client. Plain script, attaches to window (jsdom-friendly, no ESM). */
(function () {
  'use strict';

  async function request(method, path, body) {
    var opts = { method: method, headers: { 'Accept': 'application/json' } };
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    var res = await fetch(path, opts);
    var text = await res.text();
    var json = null;
    try { json = text ? JSON.parse(text) : null; } catch (e) { /* non-JSON */ }
    if (!res.ok) {
      var err = new Error((json && (json.detail || json.error)) || ('HTTP ' + res.status));
      err.status = res.status;
      err.payload = json;
      throw err;
    }
    return json;
  }

  // Read-through GET cache: the middle ground between "memory hog" and "every level
  // switch refetches the world". Tiny (LRU-capped), short-lived (TTL), and flushed by
  // ANY successful mutation so nothing stale survives a trade, reset, or universe change.
  // A level switch or tab hop re-renders from warm data in one frame; the 45s tape tick
  // and fresh visits still hit the server because the TTL has lapsed by then.
  var CACHE_TTL_MS = 20 * 1000;
  var CACHE_MAX = 40;
  var NEVER_CACHE = /^\/api\/(health|status)\b/; // staleness/diagnostics must never be stale
  var cache = new Map(); // path -> {at, promise}

  function cachedGet(path) {
    if (NEVER_CACHE.test(path)) return request('GET', path);
    var hit = cache.get(path);
    var now = Date.now();
    if (hit && now - hit.at < CACHE_TTL_MS) {
      cache.delete(path); cache.set(path, hit); // LRU bump
      return hit.promise;
    }
    var p = request('GET', path).catch(function (e) {
      cache.delete(path); // never cache failures
      throw e;
    });
    cache.set(path, { at: now, promise: p });
    while (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value);
    return p;
  }

  function flushCache() { cache.clear(); }

  /** Drop only cache keys under the given path prefixes (targeted invalidation). */
  function invalidate(prefixes) {
    Array.from(cache.keys()).forEach(function (k) {
      for (var i = 0; i < prefixes.length; i++) {
        if (k.indexOf(prefixes[i]) === 0) { cache.delete(k); return; }
      }
    });
  }

  // POSTs that change NO server state — never touch the cache (the builder previews on every
  // keystroke; the lab hypothesis/replicate tools are pure math).
  var PURE_COMPUTE = /^\/api\/(recommend($|\/)|trades\/preview$|lab\/(hypothesis|replicate|question)$|sim\/(scenario|strategy)$)/;
  // POSTs that ONLY write evaluation/recommendation history — read back solely by /api/evaluations
  // and /api/calibration. Invalidate JUST those views so market/account/quote caches stay warm.
  var HISTORY_WRITER = /^\/api\/(evaluate$|opportunities$|optimize$)/;
  var HISTORY_KEYS = ['/api/evaluations', '/api/calibration'];

  function mutate(method) {
    return function (path, body) {
      return request(method, path, body === undefined ? {} : body).then(function (out) {
        if (method === 'POST' && PURE_COMPUTE.test(path)) {
          /* no server state changed — leave the cache warm */
        } else if (method === 'POST' && HISTORY_WRITER.test(path)) {
          invalidate(HISTORY_KEYS); // targeted, not blanket
        } else {
          flushCache(); // genuine mutation — no GET answer given before is trustworthy
        }
        return out;
      });
    };
  }

  function del(path) { return request('DELETE', path).then(function (out) { flushCache(); return out; }); }

  window.API = {
    get: cachedGet,
    getFresh: function (path) { cache.delete(path); return cachedGet(path); },
    post: mutate('POST'),
    put: mutate('PUT'),
    del: del,
    'delete': del,          // alias so an API.delete(...) typo can't silently no-op
    invalidate: invalidate,
    flushCache: flushCache
  };
})();
