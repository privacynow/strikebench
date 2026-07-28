'use strict';
/*
 * One entry point for the two browser lanes that prove user-visible behavior:
 *
 *   contracts — fast, deterministic, source-served with mocked APIs. No database, no jar.
 *   journeys  — the packaged jar, a fresh database/server/browser per shard, retried once.
 *
 * CI calls the lanes, never individual files. Lane membership is resolved here from a suffix
 * convention instead of a list kept in .github, because a hand-kept list is precisely what
 * rotted: 8654824 deleted the SPA suites and the workflows kept invoking the npm scripts that
 * went with them. A suite added or moved tomorrow lands in a lane with no workflow edit.
 *
 * There is intentionally no automated "visual" lane. The former mocked geometry matrix stayed
 * green while the shipped desk visibly clipped, contradicted itself, and offered dead actions.
 * Screenshots and visual review remain release work, but they are evidence reviewed by a person,
 * not a synthetic pass/fail claim.
 */

const { execFileSync, spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { verifyArtifactManifest } = require('../scripts/artifact-manifest.cjs');

const HERE = __dirname;
const ROOT = path.resolve(HERE, '..');
const TARGET = path.join(ROOT, 'target');
const JAR = process.env.JAR ? path.resolve(process.env.JAR) : path.join(TARGET, 'strikebench.jar');
const JAR_MANIFEST = process.env.JAR_MANIFEST
  ? path.resolve(process.env.JAR_MANIFEST)
  : path.join(path.dirname(JAR), 'strikebench-artifact.json');
const APP_SOURCES = path.join(ROOT, 'src', 'main');
const LANES = ['contracts', 'journeys'];
/*
 * Discovery prevents a workflow/package.json list from drifting, but "at least one test exists"
 * is not enough evidence: deleting the actual Desk suite while leaving this runner's self-test
 * would otherwise keep a lane non-empty and green. These are capability sentinels, not the
 * complete inventory; every other tracked *.test.js remains auto-discovered.
 *
 * Required suites and their support modules must be present in the git index. Merely existing in
 * one developer's working tree is not coverage: CI receives a clean checkout, so accepting an
 * untracked sentinel makes a local lane green on code the runner can never see.
 */
const REQUIRED_FILES = Object.freeze({
  contracts: Object.freeze([
    'api-contract.test.js',
    'desk-backend.test.js',
    'fixtures/fixtures.test.js'
  ]),
  journeys: Object.freeze([
    'desk.journey.test.js',
    'dom-auth.test.js'
  ])
});
const REQUIRED_FILE_SET = new Set(Object.values(REQUIRED_FILES).flat());
const REQUIRED_SUPPORT_FILES = Object.freeze([
  'browser.js',
  'fixtures/new-idea.js',
  'fixtures/scenarios.js',
  'packaged-app.js'
]);

/*
 * A required suite filename proves only that some code ran. These named product contracts prove
 * that the behaviors on which a release claim depends were registered and then completed without
 * SKIP/TODO. Keep the inventory beside the runner: lane.js can compare it with actual TAP rather
 * than letting a source-level name check masquerade as execution evidence.
 */
function capability(file, name) {
  return Object.freeze({ file, name });
}
const REQUIRED_CAPABILITIES = Object.freeze({
  contracts: Object.freeze([
    capability('api-contract.test.js',
      'streamNdjson publishes complete frames as their split chunks arrive'),
    capability('api-contract.test.js',
      'streamNdjson rejects a malformed frame instead of silently losing evidence'),
    capability('desk-backend.test.js',
      'an unpriced package renders as unavailable in the candidate rail, never a fabricated +$0'),
    capability('desk-backend.test.js',
      'payoff renderer draws the server polyline and only in-domain backend scenario receipts'),
    capability('desk-backend.test.js',
      'the order receipt renders its epoch-millisecond observation as a human timestamp'),
    capability('desk-backend.test.js',
      'financial receipts preserve exact integer cents under one semantic money grammar'),
    capability('desk-backend.test.js',
      'Home, New Idea, and Position render one golden receipt identically'),
    capability('desk-backend.test.js',
      'Position keeps recorded payoff and saved futures when the current executable mark is unavailable'),
    capability('desk-backend.test.js',
      'Scout lifecycle streams exact rows, cancels and fails without losing work, then retries once'),
    capability('desk-backend.test.js',
      'a clicked Scout row opens the exact package it displayed, and a refusal says so'),
    capability('desk-backend.test.js',
      'a scanned package that can no longer be produced stops in adoption-unavailable and is never substituted'),
    capability('desk-backend.test.js',
      'an adverse-only competition opens its first ranked comparison without pretending it is endorsed'),
    capability('desk-backend.test.js',
      'a failed automatic comparison selection restores an actionable neutral comparison field')
  ]),
  journeys: Object.freeze([
    capability('desk.journey.test.js',
      'the shipped jar completes Home to canonical New Idea without a source-server substitute'),
    capability('desk.journey.test.js',
      'the shipped Position forks its exact held package and declarations into canonical New Idea'),
    capability('desk.journey.test.js',
      'the shipped world switch clears old analysis before publishing coherent Simulated and provider-isolated base receipts')
  ])
});

function rel(file) {
  const inside = path.relative(ROOT, file);
  // An artifact outside the repo (JAR=… pointing elsewhere) is named in full, never as ../../..
  return inside && !inside.startsWith('..') ? inside : file;
}

function die(message) {
  process.stderr.write(`lane: ${message}\n`);
  process.exit(1);
}

/**
 * Every tracked *.test.js under dom-tests, wherever it sits, grouped into exactly one lane.
 *
 * Auto-discovery is deliberate — a hand-kept list is what rotted — but it has one hazard: anything
 * dropped in here named *.test.js joins the gate, so a throwaway probe written while diagnosing a
 * surface decides whether the build is green. The line is TRACKED versus not: a suite the team
 * relies on is in git; a probe someone is writing right now is not. Untracked suites are skipped
 * and announced by name, so nothing is silently ignored and nothing untracked can gate a build.
 */
function trackedDomFiles() {
  try {
    const listed = require('node:child_process')
      .execFileSync('git', ['ls-files', '--cached', '--'], {
        cwd: HERE, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']
      });
    return new Set(listed.split('\n').map(line => line.trim()).filter(Boolean));
  } catch (error) {
    // A source archive has no index. Existence checks below still fail closed on an incomplete
    // archive, while a git checkout additionally proves each capability is committed.
    return null;
  }
}
const TRACKED = trackedDomFiles();
const scratched = [];
function testFiles(dir = HERE, prefix = '') {
  // Only dependencies and screenshot evidence are skipped; every other directory is searched,
  // so a suite cannot fall out of CI simply by moving into a subfolder.
  const skip = new Set(['node_modules', 'shots']);
  const found = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    if (entry.name.startsWith('.') || skip.has(entry.name)) continue;
    const name = prefix + entry.name;
    if (entry.isDirectory()) {
      found.push(...testFiles(path.join(dir, entry.name), `${name}/`));
    } else if (entry.name.endsWith('.test.js')) {
      if (TRACKED && !TRACKED.has(name)) scratched.push(name);
      else found.push(name);
    }
  }
  return found;
}

function discover() {
  const groups = { contracts: [], journeys: [], defaulted: [] };
  for (const name of testFiles()) {
    const base = path.basename(name);
    if (name.endsWith('.journey.test.js') || base === 'dom-auth.test.js') {
      // dom-auth boots the packaged jar against a real OIDC issuer and a fresh database. It is
      // the private instance's security contract and the only jar-driven suite 8654824 kept.
      groups.journeys.push(name);
    } else {
      // An unclaimed file runs in the fast lane rather than going unrun anywhere; the notice
      // printed below is how its author learns to rename it into the lane it actually needs.
      groups.contracts.push(name);
      if (base !== 'desk-backend.test.js') groups.defaulted.push(name);
    }
  }
  return groups;
}

function validateLaneInventory(groups, tracked = TRACKED,
  exists = file => fs.existsSync(path.join(HERE, file))) {
  const unavailable = [];
  for (const lane of LANES) {
    const actual = new Set(groups[lane] || []);
    const missing = REQUIRED_FILES[lane].filter(file => !actual.has(file));
    if (missing.length) {
      unavailable.push(`${lane}: ${missing.join(', ')}`);
    }
  }
  if (unavailable.length) {
    throw new Error(`required capability suite(s) are absent from the committed lane inventory: `
      + unavailable.join('; '));
  }

  const missingSupport = REQUIRED_SUPPORT_FILES.filter(file =>
    !exists(file) || (tracked && !tracked.has(file)));
  if (missingSupport.length) {
    throw new Error('required browser support module(s) are absent from the committed checkout: '
      + missingSupport.join(', '));
  }
  return groups;
}

function tapCount(tap, name) {
  const match = tap.match(new RegExp(`(?:^|\\n)# ${name} (\\d+)`));
  return match ? Number(match[1]) : null;
}

/** Run one file/pattern in its own process; completion is announced and full TAP is preserved. */
function runShard(shard, env) {
  return new Promise(resolve => {
    const args = [
      '--test',
      '--test-reporter=tap',
      `--test-timeout=${shard.timeoutMs || 120_000}`,
      ...(shard.nodeArgs || []),
      shard.file
    ];
    process.stdout.write(`# starting ${shard.label || shard.file}\n`);
    const child = spawn(process.execPath, args, {
      cwd: HERE, env, stdio: ['ignore', 'pipe', 'inherit']
    });
    let tap = '';
    child.stdout.on('data', chunk => { tap += chunk; });
    child.on('error', error => resolve({ ...shard, code: 1, tap: `# ${error.message}\n` }));
    child.on('close', code => {
      process.stdout.write(`# completed ${shard.label || shard.file} (exit ${code})\n`);
      resolve({ ...shard, code, tap });
    });
  });
}

function score(shard) {
  const rawTests = tapCount(shard.tap, 'tests');
  const reportedPass = tapCount(shard.tap, 'pass');
  const reportedFail = tapCount(shard.tap, 'fail');
  const rawSkipped = tapCount(shard.tap, 'skipped');
  const reportedCancelled = tapCount(shard.tap, 'cancelled');
  const reportedTodo = tapCount(shard.tap, 'todo');
  // Node releases differ here: some omit name-pattern nonmatches from their summary, while others
  // report them as skipped. Removing only the explicit pattern-mismatch skips makes both behaviors
  // describe tests actually assigned to this shard while preserving intentional product skips.
  const patternMismatches =
    (shard.tap.match(/# SKIP test name does not match pattern/g) || []).length;
  /*
   * Node marks every test that depended on a failed before/beforeEach hook as a test failure.
   * That is useful raw TAP, but it is not an assertion failure: no product assertion ran.  The
   * distinction matters most when Chromium, PostgreSQL, or the packaged app cannot start—the old
   * scorer turned one setup failure into 15 red product assertions and made the lane's diagnosis
   * actively misleading.
   *
   * Keep the complete raw TAP below the aggregate, but remove hook-failed registrations from the
   * executed-test totals and report one infrastructure failure for the shard.  A hook may fail
   * after earlier tests passed, so subtract the exact count rather than discarding the shard.
   */
  const hookFailures = (shard.tap.match(/failureType:\s*['"]hookFailed['"]/g) || []).length;
  const tests = rawTests == null ? null : Math.max(0,
    rawTests - patternMismatches - hookFailures);
  const assignmentMismatch = shard.expectedTests != null
    && tests + hookFailures !== shard.expectedTests;
  const summaryMissing = rawTests == null
    || reportedPass == null
    || reportedFail == null
    || rawSkipped == null
    || reportedCancelled == null
    || reportedTodo == null;
  const summaryMismatch = !summaryMissing
    && reportedPass + reportedFail + rawSkipped + reportedCancelled + reportedTodo !== rawTests;
  const testFail = Math.max(0, (reportedFail ?? 0) - hookFailures);
  const skipped = Math.max(0, (rawSkipped ?? 0) - patternMismatches);
  const cancelled = reportedCancelled ?? 0;
  const todo = reportedTodo ?? 0;
  const infrastructureFailure = summaryMissing
    || summaryMismatch
    || assignmentMismatch
    || hookFailures > 0
    || tests === 0
    || (shard.code !== 0 && testFail === 0 && cancelled === 0 && todo === 0);
  return {
    ...shard,
    tests: tests ?? 0,
    pass: reportedPass ?? 0,
    fail: testFail,
    infrastructureFail: infrastructureFailure ? 1 : 0,
    skipped,
    cancelled,
    todo,
    successfulTestNames: successfulTestNames(shard.tap),
    // Name-pattern exclusions are removed above. Any residual skip means a registered product
    // contract did not execute, so neither a partially skipped nor a skip-only shard is green.
    ok: !infrastructureFailure && testFail === 0 && skipped === 0
      && cancelled === 0 && todo === 0
  };
}

function regexLiteral(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Extracts the static top-level Node test names from a source suite. The Desk contract deliberately
 * uses this plain form for every test. If that contract changes, refusing to shard is safer than
 * silently omitting a dynamically registered test.
 */
function staticTestNames(file) {
  const source = fs.readFileSync(path.join(HERE, file), 'utf8');
  const names = [];
  for (const line of source.split(/\r?\n/)) {
    const match = line.match(/^test\(\s*'((?:\\'|[^'])*)'/);
    if (match) names.push(match[1].replace(/\\'/g, "'"));
  }
  return names;
}

/*
 * Ask the suite what it registers without running a hook or test body. Parsing source finds the
 * simple static declarations used for stable buckets; executing the registration layer with a
 * no-op node:test shim also resolves generated names such as one contract per viewport. This is
 * deliberately a child process: requiring a suite in lane.js itself would register its tests in
 * the runner rather than merely inventorying them.
 */
const REGISTERED_TEST_NAMES_SCRIPT = String.raw`
const Module = require('node:module');
const path = require('node:path');
const original = Module._load;
const names = [];
function capture(name) { if (typeof name === 'string') names.push(name); }
capture.skip = capture.only = capture.todo = capture;
function noop() {}
Module._load = function(request, parent, isMain) {
  if (request === 'node:test') {
    return { test: capture, describe: noop, suite: noop, before: noop, after: noop,
      beforeEach: noop, afterEach: noop };
  }
  return original.apply(this, arguments);
};
require(path.resolve(process.argv[1]));
process.stdout.write(JSON.stringify(names));
`;

function registeredTestNames(file) {
  const output = execFileSync(process.execPath, [
    '-e', REGISTERED_TEST_NAMES_SCRIPT, path.join(HERE, file)
  ], { cwd: HERE, encoding: 'utf8' });
  const names = JSON.parse(output);
  if (!Array.isArray(names) || names.some(name => typeof name !== 'string')) {
    throw new Error(`${file} did not expose a string test-name inventory`);
  }
  return names;
}

function validateCapabilityInventory(lane, files) {
  const presentFiles = new Set(files);
  const byFile = new Map();
  const unavailable = [];
  for (const required of REQUIRED_CAPABILITIES[lane] || []) {
    if (!presentFiles.has(required.file)) {
      unavailable.push(`${required.file} :: ${required.name}`);
      continue;
    }
    if (!byFile.has(required.file)) {
      byFile.set(required.file, new Set(registeredTestNames(required.file)));
    }
    if (!byFile.get(required.file).has(required.name)) {
      unavailable.push(`${required.file} :: ${required.name}`);
    }
  }
  if (unavailable.length) {
    throw new Error(`required ${lane} capability test(s) are not registered: `
      + unavailable.join('; '));
  }
  return files;
}

/*
 * Extract only top-level tests that actually completed successfully. Name-pattern exclusions,
 * intentional skips and TODOs all use an `ok ... # SKIP/TODO` TAP line; none is execution
 * evidence. `not ok` is likewise excluded even though the lane's ordinary failure totals already
 * keep the run red.
 */
function successfulTestNames(tap) {
  const names = new Set();
  for (const line of String(tap || '').split(/\r?\n/)) {
    const match = line.match(/^ok\s+\d+\s+-\s+(.+?)(?:\s+#\s+(SKIP|TODO)\b.*)?$/);
    if (match && !match[2]) names.add(match[1].trim());
  }
  return names;
}

function requiredCapabilityStatus(lane, shards) {
  const required = REQUIRED_CAPABILITIES[lane] || [];
  const missing = required.filter(entry => !shards.some(shard =>
    shard.file === entry.file
      && (shard.successfulTestNames || successfulTestNames(shard.tap)).has(entry.name)));
  return {
    required: required.length,
    passed: required.length - missing.length,
    missing
  };
}

/**
 * The Desk contract had 100+ contracts in one process. They opened independent pages, but inherited
 * one browser/server and made a neighbouring timeout look like product failure. Partitioning the
 * registered tests gives every shard a fresh process, browser and source server without copying the
 * 2,600-line canonical fixture/router into parallel test files.
 */
function expandContractShards(files) {
  const result = [];
  const desired = Math.max(1, Number.parseInt(process.env.DESK_CONTRACT_SHARDS || '8', 10) || 8);
  for (const file of files) {
    if (path.basename(file) !== 'desk-backend.test.js') {
      result.push({ file, label: file });
      continue;
    }
    const registered = registeredTestNames(file);
    const names = staticTestNames(file);
    if (names.length < 2) die(`${file} no longer exposes static top-level tests; refusing to publish partial coverage`);
    if (new Set(registered).size !== registered.length) {
      die(`${file} contains duplicate registered test names; name-pattern sharding would execute them more than once`);
    }
    const registeredSet = new Set(registered);
    const missingStatic = names.filter(name => !registeredSet.has(name));
    if (missingStatic.length) {
      die(`${file} source inventory names tests the module did not register: ${missingStatic.join(', ')}`);
    }
    const count = Math.min(desired, names.length);
    const buckets = Array.from({ length: count }, () => []);
    // Round-robin is stable and balances long late-file tests with the older short contracts.
    names.forEach((name, index) => buckets[index % count].push(name));
    buckets.forEach((bucket, index) => {
      const pattern = `^(?:${bucket.map(regexLiteral).join('|')})$`;
      result.push({
        file,
        label: `${file} [${index + 1}/${count}]`,
        nodeArgs: ['--test-name-pattern', pattern],
        expectedTests: bucket.length
      });
    });
    // Generated names are assigned positively. The former 9KB negative-regex residual silently
    // matched the whole file on Node 22, executing 112 static contracts twice and turning 154 real
    // lane tests into a reported 273.
    const staticSet = new Set(names);
    const generated = registered.filter(name => !staticSet.has(name));
    if (generated.length) {
      result.push({
        file,
        label: `${file} [dynamic registrations]`,
        nodeArgs: ['--test-name-pattern',
          `^(?:${generated.map(regexLiteral).join('|')})$`],
        expectedTests: generated.length
      });
    }
  }
  return result;
}

async function runPool(specs, env, concurrency) {
  const results = new Array(specs.length);
  let cursor = 0;
  async function worker() {
    while (true) {
      const index = cursor++;
      if (index >= specs.length) return;
      results[index] = score(await runShard(specs[index], env));
    }
  }
  await Promise.all(Array.from(
    { length: Math.min(Math.max(1, concurrency), specs.length) },
    () => worker()
  ));
  return results;
}

function newestUnder(dir) {
  let newest = { file: null, time: 0 };
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    const candidate = entry.isDirectory()
      ? newestUnder(full)
      : { file: full, time: fs.statSync(full).mtimeMs };
    if (candidate.time > newest.time) newest = candidate;
  }
  return newest;
}

/** The journey lane tests the artifact we ship, so it refuses to run against a stale one. */
function requirePackagedJar() {
  const build = 'mvn -T 1C package -Dmaven.test.skip=true';
  if (!fs.existsSync(JAR)) {
    die(`the journey lane runs the browser against the packaged jar, and ${rel(JAR)} does not exist.\n`
      + `      build it first: ${build}`);
  }
  let receipt;
  try {
    receipt = verifyArtifactManifest(JAR, JAR_MANIFEST, {
      expectedSha: SOURCE.sha || undefined,
      requireClean: false
    });
  } catch (error) {
    die(`the packaged journey artifact is not bound to this checkout: ${error.message}\n`
      + `      rebuild and write its receipt: ${build} && `
      + `node scripts/artifact-manifest.cjs write`);
  }
  const jar = fs.statSync(JAR);
  // The browser drives the packaged application, not just its static files. A backend-only edit
  // can change every receipt the desk consumes, so Java, migrations and public resources all
  // participate in the freshness check.
  const newest = newestUnder(APP_SOURCES);
  if (newest.time > jar.mtimeMs) {
    die(`${rel(newest.file)} is newer than ${rel(JAR)}. The journey lane would report on a jar that\n`
      + `      predates the desk it is verifying. Rebuild: ${build}`);
  }
  process.stdout.write(`# packaged artifact ${rel(JAR)} (${jar.size} bytes, `
    + `sha256 ${receipt.jarSha256}, source ${receipt.sourceSha})\n`);
}

/**
 * The exact source these results describe. Tests are useful in a dirty checkout, but such a run
 * must say that it did not exercise the committed tree; the release matrix will refuse to publish
 * it as branch-tip evidence.
 */
function sourceIdentity() {
  try {
    const childProcess = require('node:child_process');
    const sha = childProcess
      .execFileSync('git', ['rev-parse', 'HEAD'], {
        cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']
      }).trim();
    const trackedStatus = childProcess.execFileSync('git', [
      'status', '--porcelain=v1', '--untracked-files=no', '--',
      'src/main', 'pom.xml', 'dom-tests', '.github/workflows', 'scripts'
    ], {
      cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']
    }).trim();
    // Scratch test files and generated screenshots do not define the application or its gates.
    // Every other untracked source beneath these roots does: a tracked test can import an
    // untracked fixture/helper, so checking only required test filenames would falsely call that
    // evidence clean.
    const untracked = childProcess.execFileSync('git', [
      'ls-files', '--others', '--exclude-standard', '--',
      'src/main', 'pom.xml', 'dom-tests', '.github/workflows', 'scripts'
    ], {
      cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore']
    }).split('\n').map(line => line.trim()).filter(Boolean);
    const untrackedEvidenceSource = untracked.filter(file => {
      if (file.startsWith('dom-tests/shots/')) return false;
      if (file.endsWith('.test.js')) {
        const name = file.slice('dom-tests/'.length);
        return REQUIRED_FILE_SET.has(name);
      }
      return true;
    });
    return { sha, dirty: trackedStatus.length > 0 || untrackedEvidenceSource.length > 0 };
  } catch (error) {
    return { sha: null, dirty: true };
  }
}
const SOURCE = sourceIdentity();

function publish(lane, shards, note) {
  const capabilities = requiredCapabilityStatus(lane, shards);
  const totals = shards.reduce((sum, shard) => ({
    tests: sum.tests + shard.tests,
    pass: sum.pass + shard.pass,
    fail: sum.fail + shard.fail,
    infrastructureFail: sum.infrastructureFail + shard.infrastructureFail,
    skipped: sum.skipped + shard.skipped,
    cancelled: sum.cancelled + shard.cancelled,
    todo: sum.todo + shard.todo
  }), {
    tests: 0, pass: 0, fail: 0, infrastructureFail: 0, skipped: 0,
    cancelled: 0, todo: 0
  });

  fs.mkdirSync(TARGET, { recursive: true });
  const report = path.join(TARGET, `dom-${lane}.tap`);
  const header = `# lane ${lane}\n${SOURCE.sha ? `# source ${SOURCE.sha}\n` : ''}`
    + `# lane-source-dirty ${SOURCE.dirty ? 1 : 0}\n`
    + `# generated ${new Date().toISOString()}\n`
    // These are the ONE machine-readable lane totals. Raw TAP for every attempt remains below
    // for diagnosis, but the release matrix consumes only this aggregate. Otherwise a journey
    // that fails once and passes its permitted retry is counted twice—and the preserved failed
    // first attempt incorrectly makes the final matrix red.
    + `# lane-shards ${shards.length}\n`
    + `# lane-tests ${totals.tests}\n`
    + `# lane-pass ${totals.pass}\n`
    + `# lane-fail ${totals.fail}\n`
    + `# lane-infrastructure-fail ${totals.infrastructureFail}\n`
    + `# lane-skipped ${totals.skipped}\n`
    + `# lane-cancelled ${totals.cancelled}\n`
    + `# lane-todo ${totals.todo}\n`
    + `# lane-retried ${shards.filter(shard => shard.retried).length}\n`
    + `# lane-required-capabilities ${capabilities.required}\n`
    + `# lane-required-capabilities-passed ${capabilities.passed}\n`;
  const capabilityEvidence = (REQUIRED_CAPABILITIES[lane] || []).map(entry => {
    const passed = !capabilities.missing.some(missing =>
      missing.file === entry.file && missing.name === entry.name);
    return `# required-capability ${passed ? 'PASS' : 'MISSING'} `
      + JSON.stringify(`${entry.file} :: ${entry.name}`);
  }).join('\n');
  const retried = shards.filter(shard => shard.retried);
  const retryNote = retried.length
    ? `# retried ${retried.length} shard(s): ${retried.map(shard =>
        `${shard.file} (${shard.firstAttemptFail} failed on the first attempt)`).join(', ')}\n`
    : '';
  fs.writeFileSync(report, header + `${capabilityEvidence}\n` + retryNote
    + (shards.map(shard => `# shard ${shard.label || shard.file}\n${shard.tap}`).join('\n')
      || `1..0\n# tests 0\n# pass 0\n# fail 0\n# skipped 0\n# ${note}\n`));

  const headline = `${lane}: ${totals.tests} tests, ${totals.fail} assertion failure(s), `
    + `${totals.infrastructureFail} infrastructure failure(s)`
    + `${totals.cancelled ? `, ${totals.cancelled} cancelled` : ''}`
    + `${totals.todo ? `, ${totals.todo} todo` : ''}`
    + `${capabilities.missing.length
      ? `, ${capabilities.missing.length} required capability missing`
      : `, ${capabilities.passed}/${capabilities.required} required capabilities passed`}`
    + `${totals.skipped ? `, ${totals.skipped} skipped` : ''}${note ? ` — ${note}` : ''}`;
  process.stdout.write(`\n# ${headline}\n# report ${rel(report)}\n`);

  if (process.env.GITHUB_STEP_SUMMARY) {
    const rows = shards.length
      ? shards.map(shard => `| \`${shard.label || shard.file}\`${shard.retried
        ? ` (retried — ${shard.firstAttemptFail} failed first)` : ''} | ${shard.tests} `
        + `| ${shard.pass} | ${shard.fail} | ${shard.infrastructureFail} `
        + `| ${shard.cancelled + shard.todo} | ${shard.skipped} |`)
      : [`| _none_ | 0 | 0 | 0 | 0 | 0 | 0 |`];
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, [
      `### Browser lane: ${lane}`,
      note ? `${note}\n` : '',
      '| Shard | Tests | Pass | Assertion fail | Infrastructure fail | Incomplete | Skipped |',
      '|---|---:|---:|---:|---:|---:|---:|',
      ...rows,
      `\n**${headline}**\n`
    ].filter(Boolean).join('\n') + '\n');
  }
  return {
    ...totals,
    requiredCapabilities: capabilities.required,
    passedRequiredCapabilities: capabilities.passed,
    missingRequiredCapabilities: capabilities.missing
  };
}

async function main() {
  const lane = process.argv[2];
  if (!LANES.includes(lane)) {
    process.stderr.write(`usage: node lane.js <${LANES.join('|')}> [--list]\n`);
    process.exit(2);
  }

  let groups;
  try {
    groups = validateLaneInventory(discover());
    validateCapabilityInventory(lane, groups[lane]);
  } catch (error) {
    die(error.message);
  }
  const files = groups[lane];

  // --list answers "what does this lane actually run?" without running it, so a claim about
  // coverage can be checked against the filesystem instead of a workflow's step name.
  if (process.argv.includes('--list')) {
    process.stdout.write(files.length ? `${files.join('\n')}\n` : `(no files in the ${lane} lane)\n`);
    return;
  }

  // Every declared lane is required; a required lane cannot publish green without executing a
  // meaningful product contract.
  if (!files.length) die(`no test files for the ${lane} lane; a required lane cannot report green on nothing`);

  for (const name of scratched) {
    process.stdout.write(`# skipped ${name} — not committed, so it does not gate the build\n`);
  }
  for (const name of groups.defaulted) {
    process.stdout.write(`# notice: ${name} has no lane suffix and ran as a fast contract; rename it\n`
      + `#         *.journey.test.js if it needs the packaged jar\n`);
  }

  const env = { ...process.env };
  let shards;
  if (lane === 'journeys') {
    requirePackagedJar();
    env.JAR = JAR; // pin the artifact so no shard can quietly fall back to serving raw source
    env.JAR_MANIFEST = JAR_MANIFEST;
    shards = [];
    // Sequential shards: each suite owns a fresh database/server/browser. Product journeys may
    // retry once; deterministic packaged security contracts may not.
    for (const file of files) {
      const spec = { file, label: file, timeoutMs: 180_000 };
      const first = score(await runShard(spec, {
        ...env,
        STRIKEBENCH_JOURNEY_ATTEMPT: '1'
      }));
      if (first.ok) { shards.push(first); continue; }
      /*
       * dom-auth needs a jar and database, but it is a deterministic security contract. A rerun
       * changing its answer is a defect, not a journey flake. Only explicitly named product
       * journeys receive the one permitted retry.
       */
      if (!file.endsWith('.journey.test.js')) {
        shards.push(first);
        continue;
      }
      process.stdout.write(`# retrying ${file} once (packaged journey only)\n`);
      const second = score(await runShard(spec, {
        ...env,
        STRIKEBENCH_JOURNEY_ATTEMPT: '2'
      }));
      // The retry decides pass/fail, but the FIRST attempt's TAP is kept in the report. Replacing
      // it lost the only record of what actually failed, so a journey that fails then passes read
      // as a clean run and its diagnostics were gone — the opposite of what a retry is for.
      shards.push({
        ...second,
        retried: true,
        tap: `# attempt 1 of ${file} FAILED — kept as evidence; the retry below decided this shard\n`
          + `${first.tap}# attempt 2 (retry) of ${file}\n${second.tap}`,
        firstAttemptFail: first.fail + first.infrastructureFail + first.skipped
          + first.cancelled + first.todo
      });
    }
  } else {
    // One process per file here too, so a lane's report names the file that failed and a crashed
    // suite cannot take its neighbours' results down with it. No retry: contracts mock every API
    // and must be deterministic — a rerun that changes the answer is a defect.
    const specs = expandContractShards(files);
    // Two browsers fit the two-core shared runner without turning 8-second product waits into CPU
    // roulette. Local machines may opt higher; isolation, not maximum fan-out, is the contract.
    const concurrency = Number.parseInt(process.env.BROWSER_LANE_CONCURRENCY || '2', 10) || 2;
    shards = await runPool(specs, env, concurrency);
  }

  const totals = publish(lane, shards);
  if (totals.fail || totals.infrastructureFail || totals.skipped
      || totals.cancelled || totals.todo
      || totals.missingRequiredCapabilities.length
      || shards.some(shard => !shard.ok)) process.exit(1);
}

if (require.main === module) {
  main().catch(error => die(error.stack || String(error)));
}

module.exports = {
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
};
