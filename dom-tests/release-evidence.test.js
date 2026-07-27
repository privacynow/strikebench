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
  REQUIRED_CAPABILITIES,
  REQUIRED_FILES,
  REQUIRED_SUPPORT_FILES,
  registeredTestNames,
  requiredCapabilityStatus,
  score,
  staticTestNames,
  successfulTestNames,
  validateCapabilityInventory,
  validateLaneInventory
} = require('./lane');
const {
  ISOLATED_PRODUCT_ENV,
  packagedEnvironment,
  resolvePackagedPort
} = require('./packaged-app');
const {
  createArtifactManifest,
  verifyArtifactManifest
} = require('../scripts/artifact-manifest.cjs');

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
    '# lane-required-capabilities 3',
    '# lane-required-capabilities-passed 3',
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
    retried: 1,
    requiredCapabilities: 3,
    passedRequiredCapabilities: 3
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
    '# lane-retried 0',
    '# lane-required-capabilities 2',
    '# lane-required-capabilities-passed 2'
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
  assert.throws(() => parseLaneReport(
    base.replace('# lane-required-capabilities 2', '# lane-required-capabilities 0'), SHA),
  /zero required product capabilities/);
  assert.throws(() => parseLaneReport(
    base.replace('# lane-required-capabilities-passed 2',
      '# lane-required-capabilities-passed 1'), SHA),
  /only 1 of 2 required capabilities completed/);
  assert.throws(() => parseLaneReport(
    base.replace('# lane-required-capabilities-passed 2', ''), SHA),
  /exactly one lane-required-capabilities-passed/);
  assert.throws(() => parseLaneReport(`${base}\n# source ${SHA}`, SHA),
    /exactly one full source SHA/);
  const infrastructureRed = parseLaneReport(
    base.replace('# lane-infrastructure-fail 0', '# lane-infrastructure-fail 1'), SHA);
  assert.equal(infrastructureRed.infrastructureFailures, 1);
  assert.equal(infrastructureRed.failures, 1,
    'a crashed shard stays red without inventing a third failed test');
  const partialSkip = parseLaneReport(base
    .replace('# lane-pass 2', '# lane-pass 1')
    .replace('# lane-skipped 0', '# lane-skipped 1'), SHA);
  assert.equal(partialSkip.failures, 1,
    'a registered contract that did not execute keeps otherwise passing TAP evidence red');
});

test('Surefire evidence requires the exact full source SHA', async () => {
  const { junitResult } = await import(matrixModule);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'strikebench-surefire-evidence-'));
  try {
    fs.writeFileSync(path.join(dir, 'run-start.epoch'), `${Date.now()}\n`);
    fs.writeFileSync(path.join(dir, 'TEST-example.xml'),
      '<testsuite tests="4" failures="1" errors="1" skipped="1"></testsuite>');
    fs.writeFileSync(path.join(dir, 'source.sha'), `${SHA}\n`);
    assert.deepEqual(junitResult(SHA, dir), {
      tests: 4,
      testFailures: 2,
      skipped: 1,
      failures: 3
    });
    fs.writeFileSync(path.join(dir, 'TEST-example.xml'),
      '<testsuite tests="0" failures="0" errors="0" skipped="0"></testsuite>');
    assert.throws(() => junitResult(SHA, dir), /contain zero tests/);
    fs.writeFileSync(path.join(dir, 'TEST-example.xml'),
      '<testsuite tests="1" failures="1" errors="1" skipped="0"></testsuite>');
    assert.throws(() => junitResult(SHA, dir), /impossible totals/);
    fs.writeFileSync(path.join(dir, 'TEST-example.xml'),
      '<testsuite tests="4" failures="1" errors="1" skipped="1"></testsuite>');
    assert.throws(() => junitResult(OTHER_SHA, dir), /produced from source.*HEAD is/);
    fs.writeFileSync(path.join(dir, 'source.sha'), 'abc1234\n');
    assert.throws(() => junitResult(SHA, dir), /not a full commit SHA/);
    fs.unlinkSync(path.join(dir, 'source.sha'));
    assert.throws(() => junitResult(SHA, dir), /carries no source SHA/);
    fs.writeFileSync(path.join(dir, 'source.sha'), `${SHA}\n`);
    fs.unlinkSync(path.join(dir, 'run-start.epoch'));
    assert.throws(() => junitResult(SHA, dir), /no run-start marker/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('one artifact manifest binds source identity and jar bytes', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'strikebench-artifact-receipt-'));
  const jar = path.join(dir, 'strikebench.jar');
  const manifest = path.join(dir, 'strikebench-artifact.json');
  try {
    fs.writeFileSync(jar, 'the exact packaged application');
    const written = createArtifactManifest(jar, manifest, {
      sourceSha: SHA,
      sourceDirty: false
    });
    assert.equal(written.sourceSha, SHA);
    assert.equal(written.sourceDirty, false);
    assert.equal(verifyArtifactManifest(jar, manifest, {
      expectedSha: SHA,
      requireClean: true
    }).jarSha256, written.jarSha256);
    assert.throws(() => verifyArtifactManifest(jar, manifest, {
      expectedSha: OTHER_SHA
    }), /built from.*expected/);
    fs.appendFileSync(jar, '\nmodified after the receipt');
    assert.throws(() => verifyArtifactManifest(jar, manifest, {
      expectedSha: SHA
    }), /size is|SHA-256 is/);
    fs.writeFileSync(jar, 'the exact packaged application');
    createArtifactManifest(jar, manifest, {
      sourceSha: SHA,
      sourceDirty: true
    });
    assert.throws(() => verifyArtifactManifest(jar, manifest, {
      expectedSha: SHA,
      requireClean: true
    }), /dirty application source tree/);
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
  assert.equal(score({
    file: 'skip-only.test.js',
    code: 0,
    tap: [
      'ok 1 - skipped capability # SKIP unavailable',
      summary({ tests: 1, pass: 0, skipped: 1 })
    ].join('\n')
  }).ok, false, 'a skip-only shard executed no product assertion');
  assert.equal(score({
    file: 'partial.test.js',
    code: 0,
    tap: [
      'ok 1 - completed capability',
      'ok 2 - skipped capability # SKIP unavailable',
      summary({ tests: 2, pass: 1, skipped: 1 })
    ].join('\n')
  }).ok, false, 'a partially skipped shard is incomplete release evidence');

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
  assert.deepEqual([...patterned.successfulTestNames], ['selected'],
    'only the unskipped test is execution evidence');
  assert.deepEqual([...successfulTestNames([
    'ok 1 - completed capability',
    'ok 2 - skipped capability # SKIP deliberate',
    'ok 3 - todo capability # TODO incomplete',
    'not ok 4 - failed capability'
  ].join('\n'))], ['completed capability']);

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
    'browser.js',
    'fixtures/new-idea.js',
    'fixtures/scenarios.js',
    'packaged-app.js'
  ], 'clean-checkout browser lanes name every newly shared fixture and process owner they require');
  const missingHelper = new Set(REQUIRED_SUPPORT_FILES
    .filter(file => file !== 'fixtures/new-idea.js'));
  assert.throws(() => validateLaneInventory(complete, missingHelper, () => true),
    /required browser support module.*fixtures\/new-idea\.js/,
    'a helper present only in a developer working tree cannot satisfy clean-checkout coverage');
});

test('committed browser inventory can load before npm installs Playwright', () => {
  const probe = [
    "const Module=require('node:module');",
    'const original=Module._load;',
    "Module._load=function(request){if(request==='playwright')throw new Error('eager playwright load');",
    'return original.apply(this,arguments);};',
    "require('./browser');"
  ].join('');
  const result = spawnSync(process.execPath, ['-e', probe], {
    cwd: __dirname,
    encoding: 'utf8'
  });
  assert.equal(result.status, 0, result.stderr || result.stdout,
    'lane --list runs before npm ci, so the browser package may be required only at launch time');
});

test('required capabilities must be registered and must execute without skip or todo', () => {
  const name = REQUIRED_CAPABILITIES.contracts[0].name;
  const file = REQUIRED_CAPABILITIES.contracts[0].file;
  assert.equal(validateCapabilityInventory('contracts', REQUIRED_FILES.contracts),
    REQUIRED_FILES.contracts);
  assert.throws(() => validateCapabilityInventory('contracts',
    REQUIRED_FILES.contracts.filter(candidate => candidate !== file)),
  /required contracts capability test.*not registered/);

  const shards = [...new Set(REQUIRED_CAPABILITIES.contracts.map(entry => entry.file))]
    .map(shardFile => ({
      file: shardFile,
      successfulTestNames: new Set(REQUIRED_CAPABILITIES.contracts
        .filter(entry => entry.file === shardFile).map(entry => entry.name))
    }));
  const passed = requiredCapabilityStatus('contracts', shards);
  assert.equal(passed.required, REQUIRED_CAPABILITIES.contracts.length);
  assert.equal(passed.passed, REQUIRED_CAPABILITIES.contracts.length);
  assert.deepEqual(passed.missing, []);

  const skipped = requiredCapabilityStatus('contracts', shards.map(shard => ({
    ...shard,
    successfulTestNames: new Set([...shard.successfulTestNames]
      .filter(candidate => candidate !== name))
  })));
  assert.equal(skipped.passed, REQUIRED_CAPABILITIES.contracts.length - 1);
  assert.deepEqual(skipped.missing, [{ file, name }],
    'a registered but skipped required test is not release evidence');
});

test('visual lane registers the real Home viewport by content-state cross-product', () => {
  const names = new Set(registeredTestNames('desk.visual.test.js'));
  const required = new Set(REQUIRED_CAPABILITIES.visual.map(entry => entry.name));
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
      if (!names.has(name) || !required.has(name)) missing.push(name);
    }
  }
  assert.deepEqual(missing, [],
    'the visual workflow must execute the viewport × content-state matrix it claims');
});

test('release lanes retain the named correctness, product-journey, and viewport capabilities', () => {
  const contracts = new Set(registeredTestNames('desk-backend.test.js'));
  const apiContracts = new Set(registeredTestNames('api-contract.test.js'));
  const contractCapabilities = [
    'streamNdjson publishes complete frames as their split chunks arrive',
    'streamNdjson rejects a malformed frame instead of silently losing evidence',
    'an unpriced package renders as unavailable in the candidate rail, never a fabricated +$0',
    'payoff renderer draws the server polyline and only in-domain backend scenario receipts',
    'the order receipt renders its epoch-millisecond observation as a human timestamp',
    'financial receipts preserve exact integer cents under one semantic money grammar',
    'Home, New Idea, and Position render one golden receipt identically',
    'Position keeps recorded payoff and saved futures when the current executable mark is unavailable',
    'Scout lifecycle streams exact rows, cancels and fails without losing work, then retries once',
    'a clicked Scout row opens the exact package it displayed, and a refusal says so',
    'a scanned package that can no longer be produced stops in adoption-unavailable and is never substituted',
    'an adverse-only competition opens its first ranked comparison without pretending it is endorsed',
    'a failed automatic comparison selection restores an actionable neutral comparison field'
  ];
  assert.deepEqual(contractCapabilities.filter(name =>
    !contracts.has(name) && !apiContracts.has(name)), [],
    'the hard-blocking contract lane must retain every named rendered-truth regression');
  assert.deepEqual(REQUIRED_CAPABILITIES.contracts.map(entry => entry.name), contractCapabilities,
    'every named contract must be execution-audited, not merely registered in source');

  const journeys = new Set(registeredTestNames('desk.journey.test.js'));
  const journeyCapabilities = [
    'the shipped jar completes Home to canonical New Idea without a source-server substitute',
    'the shipped Position forks its exact held package and declarations into canonical New Idea',
    'the shipped world switch clears old analysis before publishing coherent Simulated and provider-isolated base receipts'
  ];
  assert.deepEqual(journeyCapabilities.filter(name => !journeys.has(name)), [],
    'a placeholder journey file cannot satisfy the three packaged product journeys');
  assert.deepEqual(REQUIRED_CAPABILITIES.journeys.map(entry => entry.name), journeyCapabilities,
    'every packaged journey must be execution-audited');

  const visual = new Set(registeredTestNames('desk.visual.test.js'));
  const viewports = [
    '2560x1440', '2048x1152', '2000x963', '1920x1080', '1440x900',
    '1280x800', '1000x800', '390x844', '375x812', '320x700'
  ];
  const positionNames = viewports
    .map(viewport => `the Position bloom composes without clipping or sideways scroll at ${viewport}`);
  const ideaNames = viewports
    .map(viewport => `canonical New Idea remains complete through package, scenario, and review states at ${viewport}`);
  const missingPosition = positionNames.filter(name => !visual.has(name));
  const missingIdea = ideaNames.filter(name => !visual.has(name));
  assert.deepEqual(missingPosition, [],
    'Position must remain in the full release viewport matrix');
  assert.deepEqual(missingIdea, [],
    'New Idea must remain in the full release viewport and interaction matrix');
  const requiredVisual = new Set(REQUIRED_CAPABILITIES.visual.map(entry => entry.name));
  assert.deepEqual(positionNames.filter(name => !requiredVisual.has(name)), [],
    'every Position viewport must be execution-audited');
  assert.deepEqual(ideaNames.filter(name => !requiredVisual.has(name)), [],
    'every New Idea viewport must be execution-audited');
  assert.deepEqual([...requiredVisual].filter(name => !visual.has(name)), [],
    'every required viewport/content-state contract must be registered');
  assert.deepEqual([...requiredVisual].filter(name =>
    !name.startsWith('Home composes with ')
      && !name.startsWith('the Position bloom composes ')
      && !name.startsWith('canonical New Idea remains ')), [],
  'the required visual inventory contains only explicit release viewport contracts');
});

test('packaged journeys cannot override provider, database, background-job, or port isolation', () => {
  const inherited = {
    FIXTURES_ONLY: 'false',
    YAHOO_ENABLED: 'true',
    SNAPSHOT_ENABLED: 'true',
    DB_URL: 'jdbc:postgresql://shared/unsafe',
    PORT: '7070'
  };
  const requested = {
    FIXTURES_ONLY: 'false',
    YAHOO_ENABLED: 'true',
    YAHOO_HISTORY_SYNC_ENABLED: 'true',
    SNAPSHOT_ENABLED: 'true',
    PORTFOLIO_NAV_ENABLED: 'true',
    ARTIFACT_RETENTION_ENABLED: 'true',
    ENGINE_WARM_FULL_UNIVERSE: 'true',
    DB_URL: 'jdbc:postgresql://requested/unsafe',
    PORT: '7070',
    AUTH_ENABLED: 'true'
  };
  const database = {
    DB_URL: 'jdbc:postgresql://isolated/journey',
    DB_USER: 'journey',
    DB_PASSWORD: 'private'
  };
  const env = packagedEnvironment(inherited, requested, database, 7199);
  assert.equal(env.AUTH_ENABLED, 'true',
    'a journey may still opt into the local authentication fixture');
  assert.equal(env.DB_URL, database.DB_URL);
  assert.equal(env.DB_USER, database.DB_USER);
  assert.equal(env.PORT, '7199');
  for (const [name, value] of Object.entries(ISOLATED_PRODUCT_ENV)) {
    assert.equal(env[name], value, `${name} is harness-owned`);
  }
});

test('packaged journeys ignore ambient PORT and validate only an explicit private port', async () => {
  const previous = process.env.PORT;
  process.env.PORT = '7070';
  try {
    let allocations = 0;
    const allocated = await resolvePackagedPort({}, async () => {
      allocations += 1;
      return 7199;
    });
    assert.equal(allocated, 7199);
    assert.equal(allocations, 1,
      'ambient PORT must not bypass private-port allocation');
    assert.equal(await resolvePackagedPort({ port: 7201 }, async () => {
      throw new Error('an explicit port must not allocate another one');
    }), 7201);
    await assert.rejects(resolvePackagedPort({ port: 0 }), /integer from 1 to 65535/);
    await assert.rejects(resolvePackagedPort({ port: 65_536 }), /integer from 1 to 65535/);
    await assert.rejects(resolvePackagedPort({ port: '12.5' }), /integer from 1 to 65535/);
  } finally {
    if (previous == null) delete process.env.PORT;
    else process.env.PORT = previous;
  }
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
  for (const report of [
    'target/surefire-reports/source.sha',
    'target/dom-contracts.tap',
    'target/dom-journeys.tap',
    'target/dom-visual.tap'
  ]) {
    assert.match(ci, new RegExp(`test -f ${report.replace(/[./-]/g, '\\$&')}`),
      `${report} must exist at the exact post-download path before aggregation`);
  }
  assert.match(ci, /test -e target\/target/,
    'release aggregation must reject the nested artifact layout that previously lost evidence');
  const backendBlock = ci.match(/\n  backend:\n([\s\S]*?)\n  # Lane \(a\):/)?.[1] || '';
  const journeyBlock = ci.match(/\n  browser-journeys:\n([\s\S]*?)\n  # Lane \(c\):/)?.[1] || '';
  assert.match(backendBlock, /node scripts\/artifact-manifest\.cjs write/);
  assert.match(backendBlock, /name: release-jar/);
  assert.match(journeyBlock, /needs: backend/);
  assert.match(journeyBlock, /uses: actions\/download-artifact@v4[\s\S]*name: release-jar/);
  assert.match(journeyBlock,
    /node scripts\/artifact-manifest\.cjs verify target\/strikebench\.jar target\/strikebench-artifact\.json/);
  assert.doesNotMatch(journeyBlock, /\bmvn\b/,
    'packaged journeys must drive the exact backend-tested artifact, not rebuild another jar');
  assert.match(live, /scripts\/live-market-probe\.sh/);
  assert.match(live, /typed semantic contract/i);

  const packageJson = JSON.parse(fs.readFileSync(
    path.resolve(__dirname, 'package.json'), 'utf8'));
  for (const match of ci.matchAll(/\bnpm run ([A-Za-z0-9:_-]+)/g)) {
    assert.equal(typeof packageJson.scripts?.[match[1]], 'string',
      `ci.yml invokes missing dom-tests npm script ${match[1]}`);
  }
  const invokedFiles = [
    ['ci.yml', 'dom-tests/lane.js'],
    ['ci.yml', 'scripts/release-matrix.mjs'],
    ['ci.yml', 'scripts/artifact-manifest.cjs'],
    ['live-providers.yml', 'scripts/live-market-probe.sh']
  ];
  for (const [workflow, relative] of invokedFiles) {
    assert.equal(fs.existsSync(path.resolve(__dirname, '..', relative)), true,
      `${workflow} invokes missing committed path ${relative}`);
  }
});

test('live provider probe validates typed JSON, selects an expiration date, and fails closed', () => {
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
printf '%s\\n' "$url" >> "\${OUT_DIR}/fake-requests.log"
if [[ -n "\${FAKE_MALFORMED_PATH:-}" && "$url" == *"\${FAKE_MALFORMED_PATH}"* ]]; then
  printf '{"broken":' > "$out"
elif [[ -n "\${FAKE_BAD_PATH:-}" && "$url" == *"\${FAKE_BAD_PATH}"* ]]; then
  printf '{"ok":true}' > "$out"
else
  case "$url" in
    */api/health)
      printf '{"ok":true,"startedAt":"2026-07-27T00:00:00Z","jarChangedSinceBoot":false}' > "$out" ;;
    */api/config)
      printf '{"fixturesOnly":false,"world":"observed","marketLane":"OBSERVED"}' > "$out" ;;
    */api/status)
      printf '{"ok":true,"asOf":"2026-07-27T00:00:00Z","domains":{}}' > "$out" ;;
    */api/world)
      printf '{"world":"observed","revision":7,"workspace":{}}' > "$out" ;;
    */api/market/engine)
      printf '{"enabled":true,"running":true,"symbols":[]}' > "$out" ;;
    *"/api/quotes?symbols=NVDA")
      printf '{"marketLane":"OBSERVED","quotes":[{"symbol":"NVDA","priced":true,"displayPrice":190.25,"source":"CBOE","freshness":"DELAYED"}]}' > "$out" ;;
    */api/research/NVDA/history*)
      printf '{"symbol":"NVDA","range":"6m","candles":[{"date":"2026-07-24","close":190.25}],"source":"YAHOO","freshness":"EOD","coverage":{}}' > "$out" ;;
    */api/research/NVDA/news)
      printf '{"symbol":"NVDA","items":[],"aggregate":{},"evidence":"UNAVAILABLE"}' > "$out" ;;
    */api/research/NVDA/expirations)
      printf '{"symbol":"NVDA","asOfDate":"2026-07-27","expirations":[{"date":"2026-09-18","tradingSessions":38,"calendarDays":53}]}' > "$out" ;;
    *"/api/research/NVDA/chain?expiration=2026-09-18")
      printf '{"underlying":"NVDA","expiration":"2026-09-18","underlyingPrice":190.25,"calls":[{"strike":190}],"puts":[{"strike":190}],"source":"CBOE","freshness":"DELAYED"}' > "$out" ;;
    */api/research/NVDA)
      printf '{"symbol":"NVDA","marketLane":"OBSERVED","quote":{"symbol":"NVDA","priced":true,"displayPrice":190.25},"evidence":{},"expirations":["2026-09-18"]}' > "$out" ;;
    *)
      printf '{"unexpected":"%s"}' "$url" > "$out" ;;
  esac
fi
printf '%s' "\${FAKE_CURL_STATUS:-200}"
`);
  fs.chmodSync(fakeCurl, 0o755);
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
    assert.match(fs.readFileSync(path.join(dir, 'healthy', 'manifest.txt'), 'utf8'),
      /selected_expiration=2026-09-18/);
    assert.match(fs.readFileSync(path.join(dir, 'healthy', 'fake-requests.log'), 'utf8'),
      /\/api\/research\/NVDA\/chain\?expiration=2026-09-18/,
      'the typed expiration object must lead to an actual chain read');

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

    const semanticallyInvalid = run('semantic-invalid', { FAKE_BAD_PATH: '/api/quotes' });
    assert.equal(semanticallyInvalid.status, 1);
    assert.match(semanticallyInvalid.stderr,
      /quote: HTTP 200 but response violates priced observed QuoteView/);
    assert.equal(fs.readFileSync(path.join(dir, 'semantic-invalid', 'quote.json'), 'utf8'),
      '{"ok":true}',
      'the invalid 200 body remains preserved for diagnosis');

    const malformed = run('malformed', { FAKE_MALFORMED_PATH: '/api/research/NVDA/news' });
    assert.equal(malformed.status, 1);
    assert.match(malformed.stderr,
      /news: HTTP 200 but response violates typed ResearchNews/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
