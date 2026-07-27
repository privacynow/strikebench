'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const test = require('node:test');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { spawnSync } = require('node:child_process');
const {
  expandContractShards,
  REQUIRED_FILES,
  REQUIRED_SUPPORT_FILES,
  registeredTestNames,
  score,
  staticTestNames,
  validateLaneInventory
} = require('./lane');

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
    '# lane-infrastructure-fail 0',
    '# lane-skipped 0',
    '# lane-cancelled 0',
    '# lane-todo 0',
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
    testFailures: 0,
    infrastructureFailures: 0,
    failures: 0,
    skipped: 0,
    cancelled: 0,
    todo: 0,
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
    '# lane-infrastructure-fail 0',
    '# lane-skipped 0',
    '# lane-cancelled 0',
    '# lane-todo 0',
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
    /impossible test totals/);
  assert.throws(() => parseLaneReport(base, SHA, { expectedLane: 'visual' }),
    /claims lane contracts/);
  assert.throws(() => parseLaneReport(base.replace('# lane-retried 0', '# lane-retried 1'), SHA, {
    expectedLane: 'contracts'
  }), /retry in deterministic contracts evidence/);
  assert.throws(() => parseLaneReport(`${base}\n# source ${SHA}`, SHA),
    /exactly one full source SHA/);
  const infrastructureRed = parseLaneReport(
    base.replace('# lane-infrastructure-fail 0', '# lane-infrastructure-fail 1'), SHA);
  assert.equal(infrastructureRed.infrastructureFailures, 1);
  assert.equal(infrastructureRed.failures, 1,
    'a crashed shard stays red without inventing a third failed test');
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

  const residual = score({
    file: 'desk-backend.test.js',
    code: 0,
    tap: [
      'ok 1 - known contract # SKIP test name does not match pattern',
      'ok 2 - dynamically registered contract',
      summary({ tests: 2, pass: 1, skipped: 1 })
    ].join('\n')
  });
  assert.equal(residual.ok, true);
  assert.equal(residual.tests, 1,
    'the dynamic residual must not count every excluded static contract a second time');
  assert.equal(residual.pass, 1);
  assert.equal(residual.skipped, 0);

  assert.equal(score({
    ...patterned,
    code: 0,
    expectedTests: 2,
    tap: patterned.tap
  }).ok, false);

  const hookFailed = score({
    file: 'browser.test.js',
    code: 1,
    expectedTests: 2,
    tap: [
      'not ok 1 - first browser contract',
      "  failureType: 'hookFailed'",
      'not ok 2 - second browser contract',
      "  failureType: 'hookFailed'",
      summary({ tests: 2, pass: 0, fail: 2 })
    ].join('\n')
  });
  assert.equal(hookFailed.ok, false);
  assert.equal(hookFailed.tests, 0, 'a failed setup did not execute product assertions');
  assert.equal(hookFailed.fail, 0, 'hook failures are not mislabeled as assertion failures');
  assert.equal(hookFailed.infrastructureFail, 1);

  const lateHookFailure = score({
    file: 'browser.test.js',
    code: 1,
    expectedTests: 2,
    tap: [
      'ok 1 - assertion completed before setup failed',
      'not ok 2 - browser-dependent assertion',
      "  failureType: 'hookFailed'",
      summary({ tests: 2, pass: 1, fail: 1 })
    ].join('\n')
  });
  assert.equal(lateHookFailure.tests, 1);
  assert.equal(lateHookFailure.pass, 1);
  assert.equal(lateHookFailure.fail, 0);
  assert.equal(lateHookFailure.infrastructureFail, 1);
});

test('Desk shard expansion assigns every registered contract exactly once', () => {
  const names = staticTestNames('desk-backend.test.js');
  const registered = registeredTestNames('desk-backend.test.js');
  const shards = expandContractShards(['desk-backend.test.js']);
  const dynamicShards = shards.filter(shard => shard.label.includes('dynamic registrations'));
  const staticShards = shards.filter(shard => !shard.label.includes('dynamic registrations'));
  assert.ok(names.length > 1);
  assert.equal(staticShards.reduce((sum, shard) => sum + shard.expectedTests, 0), names.length);
  assert.equal(new Set(names).size, names.length, 'duplicate test names make name-pattern sharding ambiguous');
  assert.equal(dynamicShards.length, 1, 'dynamic test registrations require one exhaustive residual shard');
  assert.equal(shards.reduce((sum, shard) => sum + shard.expectedTests, 0), registered.length,
    'static and generated buckets must account for every name the module actually registered');
  assert.equal(dynamicShards[0].expectedTests, registered.length - names.length,
    'the generated shard carries only names that source parsing cannot enumerate');
});

test('browser lanes fail when a required capability suite disappears', () => {
  const trackedSupport = new Set(REQUIRED_SUPPORT_FILES);
  const complete = {
    contracts: [...REQUIRED_FILES.contracts],
    journeys: [...REQUIRED_FILES.journeys],
    visual: [...REQUIRED_FILES.visual]
  };
  assert.equal(validateLaneInventory(complete, trackedSupport, () => true), complete);
  for (const lane of ['contracts', 'journeys', 'visual']) {
    const incomplete = {
      contracts: [...complete.contracts],
      journeys: [...complete.journeys],
      visual: [...complete.visual]
    };
    incomplete[lane].shift();
    assert.throws(() => validateLaneInventory(incomplete, trackedSupport, () => true),
      new RegExp(`required capability suite.*${lane}`));
  }
  assert.deepEqual(REQUIRED_FILES.contracts.includes('api-contract.test.js'), true,
    'the streaming/API boundary is a named contract capability, not an optional untracked probe');
  assert.deepEqual(REQUIRED_SUPPORT_FILES, [
    'fixtures/new-idea.js',
    'fixtures/scenarios.js',
    'packaged-app.js'
  ], 'clean-checkout browser lanes name every newly shared fixture and process owner they require');
  const missingHelper = new Set(REQUIRED_SUPPORT_FILES.slice(1));
  assert.throws(() => validateLaneInventory(complete, missingHelper, () => true),
    /required browser support module.*fixtures\/new-idea\.js/,
    'a helper present only in a developer working tree cannot satisfy clean-checkout coverage');
});

test('visual lane registers the real Home viewport by content-state cross-product', () => {
  const names = new Set(registeredTestNames('desk.visual.test.js'));
  const viewports = [
    '2560x1440', '2048x1152', '2000x963', '1920x1080', '1440x900',
    '1280x800', '1000x800', '390x844', '375x812', '320x700'
  ];
  const states = [
    'empty book',
    'one position',
    'populated book',
    'twelve positions, twenty ideas',
    'degraded market lanes'
  ];
  const missing = [];
  for (const state of states) {
    for (const viewport of viewports) {
      const name = `Home composes with ${state} at ${viewport}`;
      if (!names.has(name)) missing.push(name);
    }
  }
  assert.deepEqual(missing, [],
    'the visual workflow must execute the viewport × content-state matrix it claims');
});

test('push CI invokes only committed deterministic lanes and the scheduled provider gate is fail closed', () => {
  const ci = fs.readFileSync(path.resolve(__dirname, '..', '.github', 'workflows', 'ci.yml'), 'utf8');
  const live = fs.readFileSync(
    path.resolve(__dirname, '..', '.github', 'workflows', 'live-providers.yml'), 'utf8');
  for (const lane of ['contracts', 'journeys', 'visual']) {
    assert.match(ci, new RegExp(`node lane\\.js ${lane} --list`),
      `${lane} inventory must be verified from a clean checkout before its job runs`);
    assert.match(ci, new RegExp(`npm run test:${lane}`),
      `${lane} workflow must invoke the canonical lane rather than a stale individual script`);
  }
  assert.doesNotMatch(ci, /live-market-probe|query1\.finance\.yahoo|cdn\.cboe/i,
    'push and pull-request CI must never contact a real provider');
  assert.match(live, /scripts\/live-market-probe\.sh/);
  assert.match(live, /non-2xx responses fail this gate/i);
});

test('live provider probe preserves evidence and fails on transport or non-2xx responses', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'strikebench-live-probe-contract-'));
  const bin = path.join(dir, 'bin');
  fs.mkdirSync(bin);
  const fakeCurl = path.join(bin, 'curl');
  fs.writeFileSync(fakeCurl, `#!/usr/bin/env bash
set -u
out=''
url=''
while (($#)); do
  case "$1" in
    --output) out="$2"; shift 2 ;;
    --write-out) shift 2 ;;
    --connect-timeout|--max-time|--header) shift 2 ;;
    --silent|--show-error|--location) shift ;;
    *) url="$1"; shift ;;
  esac
done
if [[ "\${FAKE_CURL_MODE:-ok}" == transport ]]; then
  exit 7
fi
mkdir -p "$(dirname "$out")"
if [[ "$url" == *"/expirations"* ]]; then
  printf '{"expirations":[]}' > "$out"
else
  printf '{"ok":true}' > "$out"
fi
printf '%s' "\${FAKE_CURL_STATUS:-200}"
`);
  fs.chmodSync(fakeCurl, 0o755);
  const fakeJq = path.join(bin, 'jq');
  fs.writeFileSync(fakeJq, '#!/usr/bin/env bash\nexit 0\n');
  fs.chmodSync(fakeJq, 0o755);
  const script = path.resolve(__dirname, '..', 'scripts', 'live-market-probe.sh');
  const run = (name, extra) => spawnSync('bash', [script], {
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      BASE_URL: 'http://provider-probe.invalid',
      OUT_DIR: path.join(dir, name),
      SYMBOL: 'NVDA',
      ...extra
    }
  });
  try {
    const healthy = run('healthy', {});
    assert.equal(healthy.status, 0, healthy.stderr);
    assert.match(fs.readFileSync(path.join(dir, 'healthy', 'http-status.txt'), 'utf8'),
      /^200 \/api\/health/m);

    const non2xx = run('non2xx', { FAKE_CURL_STATUS: '503' });
    assert.equal(non2xx.status, 1);
    assert.match(non2xx.stderr, /HTTP 503 \(probe failure\)/);
    assert.match(non2xx.stderr, /failed closed/);
    assert.match(fs.readFileSync(path.join(dir, 'non2xx', 'http-status.txt'), 'utf8'),
      /^503 \/api\/health/m);

    const transport = run('transport', { FAKE_CURL_MODE: 'transport' });
    assert.equal(transport.status, 1);
    assert.match(transport.stderr, /transport failure \(curl exit 7; HTTP 000\)/);
    assert.match(transport.stderr, /failed closed/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
