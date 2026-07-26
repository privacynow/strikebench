'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const matrixModule = pathToFileURL(
  path.resolve(__dirname, '..', 'scripts', 'release-matrix.mjs')
).href;

test('release evidence consumes one lane aggregate and does not double-count preserved retry TAP', async () => {
  const { parseLaneReport } = await import(matrixModule);
  const report = [
    '# lane journeys',
    '# source abc1234',
    '# lane-shards 1',
    '# lane-tests 7',
    '# lane-pass 7',
    '# lane-fail 0',
    '# lane-skipped 0',
    '# lane-retried 1',
    '# retried 1 shard(s): example.journey.test.js (1 failed on the first attempt)',
    '# attempt 1 FAILED — preserved evidence',
    '1..7',
    '# tests 7',
    '# pass 6',
    '# fail 1',
    '# skipped 0',
    '# attempt 2 passed',
    '1..7',
    '# tests 7',
    '# pass 7',
    '# fail 0',
    '# skipped 0'
  ].join('\n');

  assert.deepEqual(parseLaneReport(report, 'abc1234', { file: 'fixture.tap' }), {
    tests: 7,
    failures: 0,
    skipped: 0,
    shards: 1,
    sha: 'abc1234',
    retried: 1
  });
});

test('release evidence rejects missing, duplicated, stale, and empty lane aggregates', async () => {
  const { parseLaneReport } = await import(matrixModule);
  const base = [
    '# source abc1234',
    '# lane-shards 1',
    '# lane-tests 2',
    '# lane-fail 0',
    '# lane-skipped 0',
    '# lane-retried 0'
  ].join('\n');

  assert.throws(() => parseLaneReport(base.replace('# lane-tests 2\n', ''), 'abc1234'),
    /exactly one lane-tests/);
  assert.throws(() => parseLaneReport(`${base}\n# lane-tests 2`, 'abc1234'),
    /exactly one lane-tests/);
  assert.throws(() => parseLaneReport(base, 'def5678'), /source abc1234.*HEAD is def5678/);
  assert.throws(() => parseLaneReport(base.replace('# lane-tests 2', '# lane-tests 0'), 'abc1234'),
    /reports zero tests/);
});
