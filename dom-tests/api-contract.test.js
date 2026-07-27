'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const API_SOURCE = fs.readFileSync(path.join(__dirname,
  '../src/main/resources/public/js/api.js'), 'utf8');
const DESK_BACKEND_SOURCE = fs.readFileSync(path.join(__dirname,
  '../src/main/resources/public/js/desk-backend.js'), 'utf8');

function response(status, chunks, bodyText) {
  let index = 0;
  return {
    ok: status >= 200 && status < 300,
    status,
    body: chunks ? {
      getReader() {
        return {
          async read() {
            if (index >= chunks.length) return { done: true, value: undefined };
            return { done: false, value: new TextEncoder().encode(chunks[index++]) };
          }
        };
      }
    } : null,
    async text() { return bodyText || ''; }
  };
}

function loadApi(fetchImpl) {
  const events = [];
  const window = {
    dispatchEvent(event) { events.push(event); }
  };
  const context = {
    window,
    fetch: fetchImpl,
    navigator: { connection: { saveData: false } },
    AbortController,
    TextDecoder,
    Uint8Array,
    CustomEvent: class CustomEvent {
      constructor(type, options) {
        this.type = type;
        this.detail = options && options.detail;
      }
    },
    Map,
    Date,
    Error,
    JSON,
    Promise,
    Array,
    Object,
    RegExp
  };
  vm.runInNewContext(API_SOURCE, context, { filename: 'api.js' });
  return { api: window.API, events };
}

const ERROR_TRANSPORTS = [
  {
    name: 'request',
    invoke(api) { return api.getFresh('/api/error-contract'); }
  },
  {
    name: 'upload',
    invoke(api) { return api.upload('/api/error-contract', { fixture: 'form-data' }); }
  },
  {
    name: 'stream',
    invoke(api) { return api.streamNdjson('/api/error-contract', { fixture: true }); }
  }
];

async function rejected(call) {
  let error = null;
  try {
    await call();
  } catch (caught) {
    error = caught;
  }
  assert.ok(error, 'the transport should reject');
  return error;
}

test('streamNdjson publishes complete frames as their split chunks arrive', async () => {
  let request = null;
  const loaded = loadApi(async (path_, options) => {
    request = { path: path_, options };
    return response(200, [
      '{"type":"progress","progress":{"completed":1}}\n{"type":"pro',
      'gress","progress":{"completed":2}}\n',
      '{"type":"complete","result":{"searched":105}}\n'
    ]);
  });
  const seen = [];
  const frames = await loaded.api.streamNdjson('/api/research/scout',
    { intents: ['INCOME'] }, { onFrame(frame) { seen.push(frame); } });

  assert.equal(request.path, '/api/research/scout');
  assert.equal(request.options.headers.Accept, 'application/x-ndjson');
  assert.deepEqual(JSON.parse(request.options.body), { intents: ['INCOME'] });
  assert.deepEqual(seen.map(frame => frame.type), ['progress', 'progress', 'complete']);
  assert.equal(seen[0].progress.completed, 1,
    'the first partial result is delivered before the completion receipt');
  assert.equal(frames.at(-1).result.searched, 105);
});

test('streamNdjson rejects a malformed frame instead of silently losing evidence', async () => {
  const loaded = loadApi(async () => response(200, [
    '{"type":"progress","progress":{"completed":1}}\n',
    '{not-json}\n{"type":"complete","result":{}}\n'
  ]));
  const seen = [];
  await assert.rejects(
    loaded.api.streamNdjson('/api/research/scout', {}, {
      onFrame(frame) { seen.push(frame); }
    }),
    /Malformed NDJSON frame 2/
  );
  assert.equal(seen.length, 1);
});

for (const transport of ERROR_TRANSPORTS) {
  test(`${transport.name} shares the canonical typed 401 boundary`, async () => {
    const loaded = loadApi(async () => response(401, null,
      JSON.stringify({
        error: 'unauthorized',
        detail: 'Sign in again.',
        loginUrl: '/auth/login?return=%2F'
      })));

    const error = await rejected(() => transport.invoke(loaded.api));
    assert.ok(error instanceof loaded.api.HttpError);
    assert.equal(error.name, 'ApiHttpError');
    assert.equal(error.status, 401);
    assert.equal(error.message, 'Sign in again.');
    assert.equal(error.payload.error, 'unauthorized');
    assert.equal(error.malformedResponse, false);
    assert.equal(loaded.events.length, 1);
    assert.equal(loaded.events[0].type, 'strikebench:auth-required');
    assert.equal(loaded.events[0].detail.loginUrl, '/auth/login?return=%2F');
  });

  test(`${transport.name} preserves a structured 422 payload on its typed error`, async () => {
    const loaded = loadApi(async () => response(422, null,
      JSON.stringify({
        error: 'guardrail_rejected',
        detail: 'Maximum loss exceeds the declared limit.',
        fields: { maxLossCents: 'exceeded' }
      })));

    const error = await rejected(() => transport.invoke(loaded.api));
    assert.ok(error instanceof loaded.api.HttpError);
    assert.equal(error.name, 'ApiHttpError');
    assert.equal(error.status, 422);
    assert.equal(error.message, 'Maximum loss exceeds the declared limit.');
    assert.equal(error.payload.error, 'guardrail_rejected');
    assert.equal(error.payload.fields.maxLossCents, 'exceeded');
    assert.equal(error.malformedResponse, false);
    assert.equal(loaded.events.length, 0);
  });

  test(`${transport.name} reports a malformed error body without losing HTTP identity`, async () => {
    const loaded = loadApi(async () => response(502, null, '<html>not JSON</html>'));

    const error = await rejected(() => transport.invoke(loaded.api));
    assert.ok(error instanceof loaded.api.HttpError);
    assert.equal(error.name, 'ApiHttpError');
    assert.equal(error.status, 502);
    assert.equal(error.message, 'HTTP 502');
    assert.equal(error.payload, null);
    assert.equal(error.malformedResponse, true);
    assert.equal(loaded.events.length, 0);
  });
}

test('Desk transport preserves the typed GreeksView and never accepts candidate-only aliases', () => {
  assert.doesNotMatch(DESK_BACKEND_SOURCE, /canonicalGreeks\s*\(/,
    'the bridge must not maintain a second Greeks normalizer');
  assert.doesNotMatch(DESK_BACKEND_SOURCE, /canonicalGreeks\s*:\s*/,
    'the bridge must not publish a second Greeks shape');
  assert.doesNotMatch(DESK_BACKEND_SOURCE, /canonicalGreeks\(candidate\.greeks\)|candidate\.greeks\s*\)/,
    'Candidate.java has no greeks component');
  assert.doesNotMatch(DESK_BACKEND_SOURCE,
    /target\.(?:delta|gamma|theta|vega)\s*=\s*greeks/,
    'the transport must not mint flat, unitless Greeks aliases');
  for (const field of ['deltaShares', 'gammaSharesPerDollar',
    'thetaCentsPerDay', 'vegaCentsPerPoint']) {
    assert.match(DESK_BACKEND_SOURCE, new RegExp(`['"]${field}['"]`),
      `the typed GreeksView field ${field} is validated by name`);
  }
});
