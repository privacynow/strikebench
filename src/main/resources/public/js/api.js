/* StrikeBench API client. Plain script, attaches to window (jsdom-friendly, no ESM). */
(function () {
  'use strict';

  function assertMutableRuntime() {
    var appStale = window.App && window.App.state && window.App.state.serverStale;
    var blockingBanner = document.getElementById('stale-banner');
    if (appStale || (blockingBanner && blockingBanner.dataset.blocking === 'true')) {
      var error = new Error('StrikeBench was updated while this session was running. Restart the app and reload before making changes.');
      error.code = 'STALE_RUNTIME';
      throw error;
    }
  }

  async function request(method, path, body) {
    if (method !== 'GET') assertMutableRuntime();
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
  // keystroke; Research event studies and Trade shaping tools are pure compute).
  var PURE_COMPUTE = /^\/api\/(trades\/preview$|research\/event-studies$|builder\/exposure$)/;
  // Writes that only persist UI state — flushing market/account caches for them would defeat
  // the cache entirely (the workspace autosaves every few seconds).
  var STATE_WRITER = /^\/api\/workspace$/;
  // POSTs that ONLY write evaluation/recommendation history — read back solely by /api/evaluations
  // and /api/calibration. Invalidate JUST those views so market/account/quote caches stay warm.
  var HISTORY_WRITER = /^\/api\/(evaluate$|opportunities$|optimize$)/;
  var HISTORY_KEYS = ['/api/evaluations', '/api/calibration'];

  function mutate(method) {
    return function (path, body) {
      return request(method, path, body === undefined ? {} : body).then(function (out) {
        if (method === 'POST' && PURE_COMPUTE.test(path)) {
          /* no server state changed — leave the cache warm */
        } else if (STATE_WRITER.test(path)) {
          invalidate(['/api/workspace']); // only its own reads go stale
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

  /** Multipart upload for user-owned local files. The browser sends the file to StrikeBench;
   *  it never calls a market-data provider directly or exposes a server filesystem path. */
  async function upload(path, formData) {
    assertMutableRuntime();
    var res = await fetch(path, { method: 'POST', headers: { 'Accept': 'application/json' }, body: formData });
    var text = await res.text(), json = null;
    try { json = text ? JSON.parse(text) : null; } catch (e) { /* non-JSON */ }
    if (!res.ok) throw new Error((json && (json.detail || json.error)) || ('HTTP ' + res.status));
    flushCache();
    return json;
  }

  /**
   * Speculative warm-up of the GET cache for the LIKELY next step. The request carries
   * X-Priority: prefetch so the server may refuse it (204) when heavy providers are cooling
   * down or contended — a guess must never cost the user anything. Failures and denials are
   * silent; an already-warm path is a no-op. Honors the OS data-saver signal.
   */
  function prefetch(path) {
    try {
      if (NEVER_CACHE.test(path)) return Promise.resolve(null);
      if (navigator.connection && navigator.connection.saveData) return Promise.resolve(null);
      var hit = cache.get(path);
      // A warm prefetch is a network no-op, but callers that use the optional result to paint
      // decoration still need the VALUE. Returning null here made a second Home/Research render
      // erase otherwise-available sparklines after reload or a level switch.
      if (hit && Date.now() - hit.at < CACHE_TTL_MS) return hit.promise;
      var p = fetch(path, { headers: { 'Accept': 'application/json', 'X-Priority': 'prefetch' } })
        .then(function (res) {
          if (res.status !== 200) throw new Error('prefetch declined');
          return res.text();
        })
        .then(function (t) { return t ? JSON.parse(t) : null; });
      p.then(function (json) {
        // Seed only on success — a denied prefetch must never poison a real read.
        cache.set(path, { at: Date.now(), promise: Promise.resolve(json) });
        while (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value);
      }).catch(function () { /* silent by design */ });
      return p.catch(function () { return null; });
    } catch (e) { return Promise.resolve(null); }
  }

  window.API = {
    get: cachedGet,
    getFresh: function (path) { cache.delete(path); return cachedGet(path); },
    post: mutate('POST'),
    put: mutate('PUT'),
    del: del,
    invalidate: invalidate,
    flushCache: flushCache,
    upload: upload,
    prefetch: prefetch
  };
})();
