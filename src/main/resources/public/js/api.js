/* StrikeBench API client. Plain script, attaches to window (jsdom-friendly, no ESM). */
(function () {
  'use strict';

  // A private instance can revoke this browser's session at any moment (idle expiry, sign-out in
  // another tab, an allowlist change). Every caller would otherwise invent its own reading of a
  // 401 — an empty Book, a "market unavailable" panel, a silent nothing. One notifier owns it:
  // the desk listens once and shows the sign-in surface, and the answer that provoked it is
  // dropped from the cache so nothing signed-out is ever replayed to a later session.
  var authRequired = false;
  function signalAuthRequired(loginUrl) {
    if (authRequired) return;
    authRequired = true;
    flushCache();
    try {
      window.dispatchEvent(new CustomEvent('strikebench:auth-required',
        { detail: { loginUrl: loginUrl || '/auth/login' } }));
    } catch (e) { /* the desk still shows the failure through its own typed error path */ }
  }

  // Reads that belong to a route render are cancellable as a group. A hash navigation starts
  // a new group synchronously, so a provider request that never answers cannot hold the SPA's
  // serialized renderer hostage. Mutations deliberately do not inherit this signal: leaving a
  // screen must never cancel a write whose server transaction may already have committed.
  var navigationAbort = null;
  function beginNavigation() {
    if (navigationAbort) navigationAbort.abort();
    navigationAbort = typeof AbortController === 'function' ? new AbortController() : null;
  }

  function decodeJsonText(text) {
    if (!text) return { value: null, malformed: false };
    try {
      return { value: JSON.parse(text), malformed: false };
    } catch (cause) {
      return { value: null, malformed: true };
    }
  }

  function errorMessage(payload, status) {
    if (payload && typeof payload.message === 'string' && payload.message.trim()) {
      return payload.message;
    }
    return 'HTTP ' + status;
  }

  function ApiHttpError(status, payload, malformedResponse) {
    Error.call(this, errorMessage(payload, status));
    this.name = 'ApiHttpError';
    this.message = errorMessage(payload, status);
    this.status = status;
    this.payload = payload;
    this.malformedResponse = !!malformedResponse;
    if (Error.captureStackTrace) Error.captureStackTrace(this, ApiHttpError);
  }
  ApiHttpError.prototype = Object.create(Error.prototype);
  ApiHttpError.prototype.constructor = ApiHttpError;

  async function decodeJsonResponse(res) {
    return decodeJsonText(await res.text()).value;
  }

  // Every transport crosses one response/error boundary. Success remains transport-specific:
  // ordinary requests and uploads decode one JSON document, while progressive reads retain the
  // response stream for their NDJSON decoder. Error bodies, authentication signaling, status,
  // payload preservation, and malformed-body semantics must never vary by transport.
  async function parseApiResponse(res, decodeSuccess) {
    if (res.ok) return decodeSuccess(res);
    var decoded = decodeJsonText(await res.text());
    if (res.status === 401) {
      signalAuthRequired(decoded.value && decoded.value.context && decoded.value.context.loginUrl);
    }
    throw new ApiHttpError(res.status, decoded.value, decoded.malformed);
  }

  async function request(method, path, body) {
    var opts = { method: method, headers: { 'Accept': 'application/json' } };
    if (method === 'GET' && navigationAbort) opts.signal = navigationAbort.signal;
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    var res = await fetch(path, opts);
    return parseApiResponse(res, decodeJsonResponse);
  }

  // Read-through GET cache: the middle ground between "memory hog" and "every level
  // switch refetches the world". Tiny (LRU-capped), short-lived (TTL), and flushed by
  // ANY successful mutation so nothing stale survives a trade, reset, or universe change.
  // A level switch or tab hop re-renders from warm data in one frame; the 45s tape tick
  // and fresh visits still hit the server because the TTL has lapsed by then.
  var CACHE_TTL_MS = 20 * 1000;
  var CACHE_MAX = 40;
  // staleness/diagnostics must never be stale, and neither may identity: a cached "signed in"
  // would outlive the session it described.
  var NEVER_CACHE = /^\/api\/(health|status|auth\/me)\b/;
  // GET answers carry market state even when their URL does not name the world or dataset.
  // The accepted Workspace identity therefore participates in every cache key. A transition also
  // advances `cacheGeneration` and clears the old namespace, so observed -> simulated -> observed
  // cannot resurrect the first observed answer merely because its 20-second TTL has not elapsed.
  var marketCacheIdentity = 'unbound';
  var cache = new Map(); // market identity + path -> {at, promise, path, marketIdentity}
  var cacheGeneration = 0;

  function cacheKey(path) {
    return marketCacheIdentity + '\u0000' + path;
  }

  function cachedGet(path) {
    if (NEVER_CACHE.test(path)) return request('GET', path);
    var key = cacheKey(path);
    var hit = cache.get(key);
    var now = Date.now();
    if (hit && now - hit.at < CACHE_TTL_MS) {
      cache.delete(key); cache.set(key, hit); // LRU bump
      return hit.promise;
    }
    var entry;
    var p = request('GET', path).catch(function (e) {
      // A superseded request must not evict a newer answer for the same path.
      if (cache.get(key) === entry) cache.delete(key);
      throw e;
    });
    entry = {
      at: now, promise: p, path: path, marketIdentity: marketCacheIdentity
    };
    cache.set(key, entry);
    while (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value);
    return p;
  }

  function flushCache() { cacheGeneration++; cache.clear(); }

  /**
   * Bind cached reads to the one market identity accepted by the Workspace owner.
   * The bridge supplies its primary world/dataset/mode/account identity string; the API client
   * deliberately does not infer those fields from arbitrary endpoint payloads.
   */
  function acceptMarketIdentity(identity) {
    var next = String(identity == null ? '' : identity).trim();
    if (!next) throw new Error('A market cache identity is required.');
    if (next !== marketCacheIdentity) {
      marketCacheIdentity = next;
      flushCache();
    }
    return marketCacheIdentity;
  }

  /** Drop only cache keys under the given path prefixes (targeted invalidation). */
  function invalidate(prefixes) {
    cacheGeneration++;
    Array.from(cache.entries()).forEach(function (row) {
      var key = row[0], entry = row[1], path = entry && entry.path || '';
      for (var i = 0; i < prefixes.length; i++) {
        if (path.indexOf(prefixes[i]) === 0) { cache.delete(key); return; }
      }
    });
  }

  // POSTs that change NO server state — never touch the cache (the builder previews on every
  // keystroke; Research event studies and Trade shaping tools are pure compute).
  var PURE_COMPUTE = /^\/api\/(trades\/preview$|research\/event-studies$|builder\/exposure$|portfolio\/accounts\/[^/]+\/analyze$|plans\/[^/]+\/(outcomes\/ensemble\/paths|decision\/preview)$)/;
  // Writes that only persist UI state — flushing market/account caches for them would defeat
  // the cache entirely (the workspace autosaves every few seconds).
  var STATE_WRITER = /^\/api\/workspace$/;
  // POSTs that ONLY write evaluation/recommendation history — read back solely by /api/evaluations
  // and /api/calibration. Invalidate JUST those views so market/account/quote caches stay warm.
  var HISTORY_WRITER = /^\/api\/(evaluate$|optimize$|research\/scout$)/;
  var HISTORY_KEYS = ['/api/evaluations', '/api/calibration'];

  function mutate(method) {
    return function (path, body) {
      if (body === undefined) {
        throw new Error(method + ' ' + path + ' requires an explicit request body.');
      }
      return request(method, path, body).then(function (out) {
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
    var res = await fetch(path, { method: 'POST', headers: { 'Accept': 'application/json' }, body: formData });
    var json = await parseApiResponse(res, decodeJsonResponse);
    flushCache();
    return json;
  }

  /**
   * Stream newline-delimited JSON through the same authentication and error boundary as every
   * other API call. Long-running reads (Scout today; other progressive results later) must not
   * each invent their own fetch/auth/decoder stack in the screen that consumes them.
   *
   * `onFrame` runs as soon as each complete JSON line arrives. The returned array is useful to
   * callers that only need the final result, while progressive surfaces normally consume frames
   * through the callback and retain only their own bounded state.
   */
  async function streamNdjson(path, body, options) {
    var settings = options || {};
    if (body === undefined) {
      throw new Error('POST ' + path + ' requires an explicit request body.');
    }
    var headers = {
      'Accept': 'application/x-ndjson',
      'Content-Type': 'application/json'
    };
    var opts = {
      method: 'POST',
      headers: headers,
      body: JSON.stringify(body),
      signal: settings.signal
    };
    var res = await fetch(path, opts);
    res = await parseApiResponse(res, function (streamResponse) { return streamResponse; });

    var frames = [], pending = '', lineNumber = 0;
    function acceptLine(raw) {
      if (!raw || !raw.trim()) return;
      lineNumber++;
      var frame;
      try {
        frame = JSON.parse(raw);
      } catch (cause) {
        var malformed = new Error('Malformed NDJSON frame ' + lineNumber + ' from ' + path + '.');
        malformed.cause = cause;
        throw malformed;
      }
      frames.push(frame);
      if (typeof settings.onFrame === 'function') settings.onFrame(frame);
    }
    function acceptChunk(text, complete) {
      pending += text;
      var lines = pending.split('\n');
      pending = lines.pop() || '';
      lines.forEach(acceptLine);
      if (complete) {
        acceptLine(pending);
        pending = '';
      }
    }

    if (res.body && typeof res.body.getReader === 'function' && typeof TextDecoder === 'function') {
      var reader = res.body.getReader(), decoder = new TextDecoder();
      while (true) {
        var chunk = await reader.read();
        acceptChunk(decoder.decode(chunk.value || new Uint8Array(), { stream: !chunk.done }),
          chunk.done);
        if (chunk.done) break;
      }
    } else {
      acceptChunk(await res.text(), true);
    }
    /* Scout persists the evaluations it emits. A streamed mutation must invalidate the same
       narrow read set as its non-streaming peers once the response has completed successfully. */
    if (HISTORY_WRITER.test(path)) invalidate(HISTORY_KEYS);
    return frames;
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
      var key = cacheKey(path);
      var requestedMarketIdentity = marketCacheIdentity;
      var hit = cache.get(key);
      // A warm prefetch is a network no-op, but callers that use the optional result to paint
      // decoration still need the VALUE. Returning null here made a second Home/Research render
      // erase otherwise-available sparklines after reload or a level switch.
      if (hit && Date.now() - hit.at < CACHE_TTL_MS) return hit.promise;
      var mine = cacheGeneration;
      var priorEntry = hit;
      var p = fetch(path, { headers: { 'Accept': 'application/json', 'X-Priority': 'prefetch' } })
        .then(function (res) {
          if (res.status !== 200) throw new Error('prefetch declined');
          return res.text();
        })
        .then(function (t) { return t ? JSON.parse(t) : null; });
      p.then(function (json) {
        // Seed only if no invalidation or newer real read superseded this speculation.
        if (mine !== cacheGeneration || marketCacheIdentity !== requestedMarketIdentity
            || cache.get(key) !== priorEntry) return;
        cache.set(key, {
          at: Date.now(), promise: Promise.resolve(json), path: path,
          marketIdentity: requestedMarketIdentity
        });
        while (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value);
      }).catch(function () { /* silent by design */ });
      return p.catch(function () { return null; });
    } catch (e) { return Promise.resolve(null); }
  }

  window.API = {
    get: cachedGet,
    getFresh: function (path) {
      cacheGeneration++;
      cache.delete(cacheKey(path));
      return cachedGet(path);
    },
    post: mutate('POST'),
    put: mutate('PUT'),
    patch: mutate('PATCH'),
    del: del,
    invalidate: invalidate,
    flushCache: flushCache,
    acceptMarketIdentity: acceptMarketIdentity,
    marketCacheIdentity: function () { return marketCacheIdentity; },
    upload: upload,
    streamNdjson: streamNdjson,
    HttpError: ApiHttpError,
    prefetch: prefetch,
    beginNavigation: beginNavigation,
    signalAuthRequired: signalAuthRequired
  };
})();
