'use strict';
/*
 * One entry point for the three browser lanes the audit (§16.2) names:
 *
 *   contracts — fast, deterministic, source-served with mocked APIs. No database, no jar.
 *   journeys  — the packaged jar, a fresh database/server/browser per shard, retried once.
 *   visual    — the viewport/geometry matrix (§16.4). Scaffolded: no test file exists yet.
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
const WEB_SOURCES = path.join(ROOT, 'src', 'main', 'resources', 'public');
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
 * Every *.test.js under dom-tests, wherever it sits, grouped into exactly one lane.
 *
 * Auto-discovery is deliberate — a hand-kept list is what rotted — but it has one hazard: anything
 * dropped in here named *.test.js joins the gate, so a throwaway probe written while diagnosing a
 * surface decides whether the build is green. The line is COMMITTED versus not: a suite the team
 * relies on is in git; a probe someone is writing right now is not. Untracked suites are skipped
 * and announced by name, so nothing is silently ignored and nothing uncommitted can gate a build.
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

/** Run one file in its own process, streaming TAP so a hung suite is still visible in CI logs. */
function runShard(file, env) {
  return new Promise(resolve => {
    const child = spawn(process.execPath, ['--test', '--test-reporter=tap', file], {
      cwd: HERE, env, stdio: ['ignore', 'pipe', 'inherit']
    });
    let tap = '';
    child.stdout.on('data', chunk => { tap += chunk; process.stdout.write(chunk); });
    child.on('error', error => resolve({ file, code: 1, tap: `# ${error.message}\n` }));
    child.on('close', code => resolve({ file, code, tap }));
  });
}

function score(shard) {
  const tests = tapCount(shard.tap, 'tests');
  const fail = tapCount(shard.tap, 'fail');
  return {
    ...shard,
    tests: tests ?? 0,
    pass: tapCount(shard.tap, 'pass') ?? 0,
    // A shard that died before printing a summary counts as one failure: silence is not a pass.
    fail: fail ?? 1,
    skipped: tapCount(shard.tap, 'skipped') ?? 0,
    ok: shard.code === 0 && tests !== null && fail === 0
  };
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
  const newest = newestUnder(WEB_SOURCES);
  if (newest.time > jar.mtimeMs) {
    die(`${rel(newest.file)} is newer than ${rel(JAR)}. The journey lane would report on a jar that\n`
      + `      predates the desk it is verifying. Rebuild: ${build}`);
  }
  process.stdout.write(`# packaged artifact ${rel(JAR)} (${jar.size} bytes, built ${jar.mtime.toISOString()})\n`);
}

function publish(lane, shards, note) {
  const totals = shards.reduce((sum, shard) => ({
    tests: sum.tests + shard.tests,
    pass: sum.pass + shard.pass,
    fail: sum.fail + shard.fail,
    skipped: sum.skipped + shard.skipped
  }), { tests: 0, pass: 0, fail: 0, skipped: 0 });

  fs.mkdirSync(TARGET, { recursive: true });
  const report = path.join(TARGET, `dom-${lane}.tap`);
  fs.writeFileSync(report, shards.map(shard => `# shard ${shard.file}\n${shard.tap}`).join('\n')
    || `1..0\n# tests 0\n# pass 0\n# fail 0\n# skipped 0\n# ${note}\n`);

  const headline = `${lane}: ${totals.tests} tests, ${totals.fail} failing`
    + `${totals.skipped ? `, ${totals.skipped} skipped` : ''}${note ? ` — ${note}` : ''}`;
  process.stdout.write(`\n# ${headline}\n# report ${rel(report)}\n`);

  if (process.env.GITHUB_STEP_SUMMARY) {
    const rows = shards.length
      ? shards.map(shard => `| \`${shard.file}\`${shard.retried
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

  if (lane === 'visual' && !files.length) {
    // Honest scaffold: the lane exists so the split is real and the first *.visual.test.js runs
    // with no workflow change, but it must never be mistaken for viewport coverage we have.
    publish(lane, [], 'NOT IMPLEMENTED — no *.visual.test.js exists; the §16.4 viewport matrix is unwritten');
    return;
  }
  if (!files.length) die(`no test files for the ${lane} lane; the lane cannot report green on nothing`);

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
      const first = score(await runShard(file, env));
      if (first.ok) { shards.push(first); continue; }
      process.stdout.write(`# retrying ${file} once (journey lane only)\n`);
      const second = score(await runShard(file, env));
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
    shards = [];
    for (const file of files) shards.push(score(await runShard(file, env)));
  }

  const totals = publish(lane, shards);
  if (totals.fail || shards.some(shard => !shard.ok)) process.exit(1);
}

main().catch(error => die(error.stack || String(error)));
