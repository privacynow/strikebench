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

  window.API = {
    get: function (path) { return request('GET', path); },
    post: function (path, body) { return request('POST', path, body === undefined ? {} : body); },
    put: function (path, body) { return request('PUT', path, body === undefined ? {} : body); },
    del: function (path) { return request('DELETE', path); }
  };
})();
