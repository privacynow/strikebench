'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const test = require('node:test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { expandContractShards, score, staticTestNames } = require('./lane');

const matrixModule = pathToFileURL(
  path.resolve(__dirname, '..', 'scripts', 'release-matrix.mjs')
).href;
const SHA = 'abc1234abc1234abc1234abc1234abc1234abc12';
const OTHER_SHA = 'def5678def5678def5678def5678def5678def56';

test('release evidence consumes one lane aggregate and does not double-count preserved retry TAP', async () => {
  const { parseLaneReport } = await import(matrixModule);
  const report = [
    '# lane journeys',
    `# source ${SHA}`,
    '# lane-source-dirty 0',
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

  assert.deepEqual(parseLaneReport(report, SHA, {
    file: 'fixture.tap',
    expectedLane: 'journeys'
  }), {
    lane: 'journeys',
    tests: 7,
    pass: 7,
    failures: 0,
    skipped: 0,
    shards: 1,
    sha: SHA,
    sourceDirty: 0,
    retried: 1
  });
});

test('release evidence rejects missing, duplicated, stale, and empty lane aggregates', async () => {
  const { parseLaneReport } = await import(matrixModule);
  const base = [
    '# lane contracts',
    `# source ${SHA}`,
    '# lane-source-dirty 0',
    '# lane-shards 1',
    '# lane-tests 2',
    '# lane-pass 2',
    '# lane-fail 0',
    '# lane-skipped 0',
    '# lane-retried 0'
  ].join('\n');

  assert.throws(() => parseLaneReport(base.replace('# lane-tests 2\n', ''), SHA),
    /exactly one lane-tests/);
  assert.throws(() => parseLaneReport(`${base}\n# lane-tests 2`, SHA),
    /exactly one lane-tests/);
  assert.throws(() => parseLaneReport(base, OTHER_SHA), /produced from source.*HEAD is/);
  assert.throws(() => parseLaneReport(base.replace('# lane-tests 2', '# lane-tests 0'), SHA),
    /reports 0 tests/);
  assert.throws(() => parseLaneReport(base.replace('# lane-shards 1', '# lane-shards 0'), SHA),
    /across 0 shards/);
  assert.throws(() => parseLaneReport(
    base.replace('# lane-source-dirty 0', '# lane-source-dirty 1'), SHA),
    /dirty source tree/);
  assert.throws(() => parseLaneReport(base.replace('# lane-pass 2', '# lane-pass 1'), SHA),
    /claims green but accounts for only/);
  assert.throws(() => parseLaneReport(base, SHA, { expectedLane: 'visual' }),
    /claims lane contracts/);
  assert.throws(() => parseLaneReport(base.replace('# lane-retried 0', '# lane-retried 1'), SHA, {
    expectedLane: 'contracts'
  }), /retry in deterministic contracts evidence/);
  assert.throws(() => parseLaneReport(`${base}\n# source ${SHA}`, SHA),
    /exactly one full source SHA/);
});

test('Surefire evidence requires the exact full source SHA', async () => {
  const { junitResult } = await import(matrixModule);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'strikebench-surefire-evidence-'));
  try {
    fs.writeFileSync(path.join(dir, 'TEST-example.xml'),
      '<testsuite tests="4" failures="1" errors="1" skipped="1"></testsuite>');
    fs.writeFileSync(path.join(dir, 'source.sha'), `${SHA}\n`);
    assert.deepEqual(junitResult(SHA, dir), { tests: 4, failures: 2, skipped: 1 });
    assert.throws(() => junitResult(OTHER_SHA, dir), /produced from source.*HEAD is/);
    fs.writeFileSync(path.join(dir, 'source.sha'), 'abc1234\n');
    assert.throws(() => junitResult(SHA, dir), /not a full commit SHA/);
    fs.unlinkSync(path.join(dir, 'source.sha'));
    assert.throws(() => junitResult(SHA, dir), /carries no source SHA/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('browser shard scoring fails closed on empty, incomplete, and partial registration', () => {
  const summary = ({ tests, pass, fail = 0, skipped = 0, cancelled = 0, todo = 0 }) => [
    `# tests ${tests}`,
    `# pass ${pass}`,
    `# fail ${fail}`,
    `# cancelled ${cancelled}`,
    `# skipped ${skipped}`,
    `# todo ${todo}`
  ].join('\n');

  assert.equal(score({ file: 'empty.test.js', code: 0, tap: summary({ tests: 0, pass: 0 }) }).ok,
    false);
  assert.equal(score({
    file: 'cancelled.test.js',
    code: 0,
    tap: summary({ tests: 1, pass: 0, cancelled: 1 })
  }).ok, false);
  assert.equal(score({
    file: 'truncated.test.js',
    code: 0,
    tap: '# tests 1\n# pass 1\n# fail 0'
  }).ok, false);

  const patterned = score({
    file: 'desk-backend.test.js',
    code: 0,
    expectedTests: 1,
    tap: [
      'ok 1 - selected',
      'ok 2 - not selected # SKIP test name does not match pattern',
      'ok 3 - not selected # SKIP test name does not match pattern',
      summary({ tests: 3, pass: 1, skipped: 2 })
    ].join('\n')
  });
  assert.equal(patterned.ok, true);
  assert.equal(patterned.tests, 1);
  assert.equal(patterned.skipped, 0);

  assert.equal(score({
    ...patterned,
    code: 0,
    expectedTests: 2,
    tap: patterned.tap
  }).ok, false);
});

test('Desk shard expansion assigns every statically registered contract exactly once', () => {
  const names = staticTestNames('desk-backend.test.js');
  const shards = expandContractShards(['desk-backend.test.js']);
  const staticShards = shards.filter(shard => shard.expectedTests != null);
  const dynamicShards = shards.filter(shard => shard.expectedTests == null);
  assert.ok(names.length > 1);
  assert.equal(staticShards.reduce((sum, shard) => sum + shard.expectedTests, 0), names.length);
  assert.equal(new Set(names).size, names.length, 'duplicate test names make name-pattern sharding ambiguous');
  assert.equal(dynamicShards.length, 1, 'dynamic test registrations require one exhaustive residual shard');
});
