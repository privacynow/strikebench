'use strict';
/*
 * One entry point for the three browser lanes the audit (§16.2) names:
 *
 *   contracts — fast, deterministic, source-served with mocked APIs. No database, no jar.
 *   journeys  — the packaged jar, a fresh database/server/browser per shard, retried once.
 *   visual    — the viewport/geometry matrix (§16.4).
 *
 * CI calls the lanes, never individual files. Lane membership is resolved here from a suffix
 * convention instead of a list kept in .github, because a hand-kept list is precisely what
 * rotted: 8654824 deleted the SPA suites and the workflows kept invoking the npm scripts that
 * went with them. A suite added or moved tomorrow lands in a lane with no workflow edit.
 */

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const HERE = __dirname;
const ROOT = path.resolve(HERE, '..');
const TARGET = path.join(ROOT, 'target');
const JAR = process.env.JAR ? path.resolve(process.env.JAR) : path.join(TARGET, 'strikebench.jar');
const APP_SOURCES = path.join(ROOT, 'src', 'main');
const LANES = ['contracts', 'journeys', 'visual'];

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
function trackedTestFiles() {
  try {
    const listed = require('node:child_process')
      .execFileSync('git', ['ls-files', '--cached', '--', '*.test.js'], { cwd: HERE, encoding: 'utf8' });
    return new Set(listed.split('\n').map(line => line.trim()).filter(Boolean));
  } catch (error) {
    // Not a git checkout (a release tarball, say). Fall back to running everything found.
    return null;
  }
}
const TRACKED = trackedTestFiles();
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
  const groups = { contracts: [], journeys: [], visual: [], defaulted: [] };
  for (const name of testFiles()) {
    const base = path.basename(name);
    if (name.endsWith('.visual.test.js')) {
      groups.visual.push(name);
    } else if (name.endsWith('.journey.test.js') || base === 'dom-auth.test.js') {
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

function tapCount(tap, name) {
  const match = tap.match(new RegExp(`(?:^|\\n)# ${name} (\\d+)`));
  return match ? Number(match[1]) : null;
}

/** Run one file/pattern in its own process; completion is announced and full TAP is preserved. */
function runShard(shard, env) {
  return new Promise(resolve => {
    const args = ['--test', '--test-reporter=tap', ...(shard.nodeArgs || []), shard.file];
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
  const patternMismatches = shard.expectedTests == null
    ? 0
    : (shard.tap.match(/# SKIP test name does not match pattern/g) || []).length;
  const tests = rawTests == null ? null : rawTests - patternMismatches;
  const assignmentMismatch = shard.expectedTests != null
    && tests !== shard.expectedTests;
  const summaryMissing = rawTests == null
    || reportedPass == null
    || reportedFail == null
    || rawSkipped == null
    || reportedCancelled == null
    || reportedTodo == null;
  const summaryMismatch = !summaryMissing
    && reportedPass + reportedFail + rawSkipped + reportedCancelled + reportedTodo !== rawTests;
  const incomplete = tests === 0
    || (reportedCancelled ?? 0) > 0
    || (reportedTodo ?? 0) > 0;
  const infrastructureFailure = shard.code !== 0
    || summaryMissing
    || summaryMismatch
    || assignmentMismatch
    || incomplete;
  return {
    ...shard,
    tests: tests ?? 0,
    pass: reportedPass ?? 0,
    // A shard that died before printing a summary, or exited non-zero despite claiming zero test
    // failures, counts as one infrastructure failure. The lane report is release evidence; it
    // must not publish a green aggregate for a process that did not complete successfully.
    fail: reportedFail == null ? 1 : Math.max(reportedFail, infrastructureFailure ? 1 : 0),
    skipped: Math.max(0, (rawSkipped ?? 0) - patternMismatches),
    ok: !infrastructureFailure && reportedFail === 0
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

function hasDynamicTestRegistration(file) {
  const source = fs.readFileSync(path.join(HERE, file), 'utf8');
  return source.split(/\r?\n/).some(line =>
    /^\s*test(?:\.|\s*\()/.test(line) && !/^test\(\s*'((?:\\'|[^'])*)'/.test(line));
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
    const names = staticTestNames(file);
    if (names.length < 2) die(`${file} no longer exposes static top-level tests; refusing to publish partial coverage`);
    if (new Set(names).size !== names.length) {
      die(`${file} contains duplicate test names; name-pattern sharding would execute them more than once`);
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
    // Template-generated or otherwise dynamic tests cannot be enumerated from source without
    // executing the module. A negative-pattern shard is exhaustive by construction: every
    // registered name not assigned above runs here, so the viewport loop at the end of the Desk
    // suite (and future dynamic registrations) cannot disappear between the static buckets.
    if (hasDynamicTestRegistration(file)) {
      const known = names.map(regexLiteral).join('|');
      result.push({
        file,
        label: `${file} [dynamic registrations]`,
        nodeArgs: ['--test-name-pattern', `^(?!(?:${known})$).+$`]
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
  const jar = fs.statSync(JAR);
  // The browser drives the packaged application, not just its static files. A backend-only edit
  // can change every receipt the desk consumes, so Java, migrations and public resources all
  // participate in the freshness check.
  const newest = newestUnder(APP_SOURCES);
  if (newest.time > jar.mtimeMs) {
    die(`${rel(newest.file)} is newer than ${rel(JAR)}. The journey lane would report on a jar that\n`
      + `      predates the desk it is verifying. Rebuild: ${build}`);
  }
  process.stdout.write(`# packaged artifact ${rel(JAR)} (${jar.size} bytes, built ${jar.mtime.toISOString()})\n`);
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
      .execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim();
    const trackedStatus = childProcess.execFileSync('git', [
      'status', '--porcelain=v1', '--untracked-files=no', '--',
      'src/main', 'pom.xml', 'dom-tests', '.github/workflows', 'scripts'
    ], { cwd: ROOT, encoding: 'utf8' }).trim();
    // Untracked browser probes are intentionally excluded from discovery above, and screenshot
    // evidence is not source. Untracked application files are different: Maven can compile/package
    // them, so a jar containing one cannot truthfully be called the committed branch tip.
    const untrackedApplication = childProcess.execFileSync('git', [
      'ls-files', '--others', '--exclude-standard', '--', 'src/main'
    ], { cwd: ROOT, encoding: 'utf8' }).trim();
    return { sha, dirty: trackedStatus.length > 0 || untrackedApplication.length > 0 };
  } catch (error) {
    return { sha: null, dirty: true };
  }
}
const SOURCE = sourceIdentity();

function publish(lane, shards, note) {
  const totals = shards.reduce((sum, shard) => ({
    tests: sum.tests + shard.tests,
    pass: sum.pass + shard.pass,
    fail: sum.fail + shard.fail,
    skipped: sum.skipped + shard.skipped
  }), { tests: 0, pass: 0, fail: 0, skipped: 0 });

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
    + `# lane-skipped ${totals.skipped}\n`
    + `# lane-retried ${shards.filter(shard => shard.retried).length}\n`;
  const retried = shards.filter(shard => shard.retried);
  const retryNote = retried.length
    ? `# retried ${retried.length} shard(s): ${retried.map(shard =>
        `${shard.file} (${shard.firstAttemptFail} failed on the first attempt)`).join(', ')}\n`
    : '';
  fs.writeFileSync(report, header + retryNote
    + (shards.map(shard => `# shard ${shard.label || shard.file}\n${shard.tap}`).join('\n')
      || `1..0\n# tests 0\n# pass 0\n# fail 0\n# skipped 0\n# ${note}\n`));

  const headline = `${lane}: ${totals.tests} tests, ${totals.fail} failing`
    + `${totals.skipped ? `, ${totals.skipped} skipped` : ''}${note ? ` — ${note}` : ''}`;
  process.stdout.write(`\n# ${headline}\n# report ${rel(report)}\n`);

  if (process.env.GITHUB_STEP_SUMMARY) {
    const rows = shards.length
      ? shards.map(shard => `| \`${shard.label || shard.file}\`${shard.retried
        ? ` (retried — ${shard.firstAttemptFail} failed first)` : ''} | ${shard.tests} `
        + `| ${shard.pass} | ${shard.fail} | ${shard.skipped} |`)
      : [`| _none_ | 0 | 0 | 0 | 0 |`];
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, [
      `### Browser lane: ${lane}`,
      note ? `${note}\n` : '',
      '| Shard | Tests | Pass | Fail | Skipped |',
      '|---|---:|---:|---:|---:|',
      ...rows,
      `\n**${headline}**\n`
    ].filter(Boolean).join('\n') + '\n');
  }
  return totals;
}

async function main() {
  const lane = process.argv[2];
  if (!LANES.includes(lane)) {
    process.stderr.write(`usage: node lane.js <${LANES.join('|')}> [--list]\n`);
    process.exit(2);
  }

  const groups = discover();
  const files = groups[lane];

  // --list answers "what does this lane actually run?" without running it, so a claim about
  // coverage can be checked against the filesystem instead of a workflow's step name.
  if (process.argv.includes('--list')) {
    process.stdout.write(files.length ? `${files.join('\n')}\n` : `(no files in the ${lane} lane)\n`);
    return;
  }

  // Every lane is required. The visual lane used to publish a friendly "NOT IMPLEMENTED" row and
  // exit 0, which meant a required lane could report green having run nothing at all — the most
  // expensive kind of false evidence, because it looks like coverage in the release matrix.
  if (!files.length) die(`no test files for the ${lane} lane; a required lane cannot report green on nothing`);

  for (const name of scratched) {
    process.stdout.write(`# skipped ${name} — not committed, so it does not gate the build\n`);
  }
  for (const name of groups.defaulted) {
    process.stdout.write(`# notice: ${name} has no lane suffix and ran as a fast contract; rename it\n`
      + `#         *.journey.test.js or *.visual.test.js if it needs the jar or the viewport matrix\n`);
  }

  const env = { ...process.env };
  let shards;
  if (lane === 'journeys') {
    requirePackagedJar();
    env.JAR = JAR; // pin the artifact so no shard can quietly fall back to serving raw source
    shards = [];
    // Sequential shards: each one boots its own jar on a fixed port with its own fresh database,
    // so overlapping them would collide. Retry once — journeys only, per §16.2.
    for (const file of files) {
      const spec = { file, label: file };
      const first = score(await runShard(spec, env));
      if (first.ok) { shards.push(first); continue; }
      process.stdout.write(`# retrying ${file} once (journey lane only)\n`);
      const second = score(await runShard(spec, env));
      // The retry decides pass/fail, but the FIRST attempt's TAP is kept in the report. Replacing
      // it lost the only record of what actually failed, so a journey that fails then passes read
      // as a clean run and its diagnostics were gone — the opposite of what a retry is for.
      shards.push({
        ...second,
        retried: true,
        tap: `# attempt 1 of ${file} FAILED — kept as evidence; the retry below decided this shard\n`
          + `${first.tap}# attempt 2 (retry) of ${file}\n${second.tap}`,
        firstAttemptFail: first.fail
      });
    }
  } else {
    // One process per file here too, so a lane's report names the file that failed and a
    // crashed suite cannot take its neighbours' results down with it. No retry: these lanes
    // mock every API and must be deterministic — a rerun that changes the answer is a defect.
    const specs = lane === 'contracts' ? expandContractShards(files)
      : files.map(file => ({ file, label: file }));
    // Two browsers fit the two-core shared runner without turning 8-second product waits into CPU
    // roulette. Local machines may opt higher; isolation, not maximum fan-out, is the contract.
    const concurrency = Number.parseInt(process.env.BROWSER_LANE_CONCURRENCY || '2', 10) || 2;
    shards = await runPool(specs, env, concurrency);
  }

  const totals = publish(lane, shards);
  if (totals.fail || shards.some(shard => !shard.ok)) process.exit(1);
}

if (require.main === module) {
  main().catch(error => die(error.stack || String(error)));
}

module.exports = {
  expandContractShards,
  score,
  staticTestNames
};
