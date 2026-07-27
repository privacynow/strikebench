#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const target = path.join(root, 'target');

function attribute(tag, name) {
  const match = tag.match(new RegExp(`\\b${name}="(\\d+)"`));
  return match ? Number(match[1]) : 0;
}

export function junitResult(expectedSha, dir = path.join(target, 'surefire-reports')) {
  const files = fs.existsSync(dir)
    ? fs.readdirSync(dir).filter(name => /^TEST-.*\.xml$/.test(name)) : [];
  if (!files.length) throw new Error('No Surefire XML reports found; run mvn test first.');
  const sourceFile = path.join(dir, 'source.sha');
  if (!fs.existsSync(sourceFile)) {
    throw new Error('Surefire evidence carries no source SHA. Run a clean full suite and write '
      + '`git rev-parse HEAD` to target/surefire-reports/source.sha before publishing it.');
  }
  const source = fs.readFileSync(sourceFile, 'utf8').trim();
  if (!/^[0-9a-f]{40}$/.test(source)) {
    throw new Error(`Surefire source marker is not a full commit SHA: ${JSON.stringify(source)}`);
  }
  if (source !== expectedSha) {
    throw new Error(`Surefire evidence was produced from source ${source}, but HEAD is ${expectedSha}.`);
  }
  /*
   * Surefire writes one XML per test class and never removes an old one. A directory holding
   * reports from two different runs — a focused `-Dtest=…` followed by a full run, or a class that
   * was renamed — sums into a total that never existed: this published 1,220 for a suite that had
   * just run 1,216. Reports must come from ONE run, which means their timestamps cluster.
   */
  const stamped = files.map(name => ({ name, at: fs.statSync(path.join(dir, name)).mtimeMs }));
  const newest = Math.max(...stamped.map(entry => entry.at));
  const stale = stamped.filter(entry => newest - entry.at > 10 * 60 * 1000);
  if (stale.length) {
    throw new Error(`${stale.length} Surefire report(s) predate the newest by more than ten `
      + `minutes (${stale.slice(0, 4).map(entry => entry.name).join(', ')}`
      + `${stale.length > 4 ? ', …' : ''}). They are from an earlier run and would be summed into a `
      + 'total that never executed. Delete target/surefire-reports and run the full suite.');
  }
  const result = files.reduce((total, name) => {
    const xml = fs.readFileSync(path.join(dir, name), 'utf8');
    const tag = xml.match(/<testsuite\b[^>]*>/)?.[0];
    if (!tag) throw new Error(`No testsuite result in ${name}`);
    total.tests += attribute(tag, 'tests');
    total.failures += attribute(tag, 'failures') + attribute(tag, 'errors');
    total.skipped += attribute(tag, 'skipped');
    return total;
  }, { tests: 0, failures: 0, skipped: 0 });
  if (result.tests === 0) {
    throw new Error('Surefire reports contain zero tests. A required backend lane cannot be '
      + 'published as green without executing a test.');
  }
  if (result.failures + result.skipped > result.tests) {
    throw new Error(`Surefire reports carry impossible totals: ${result.tests} tests but `
      + `${result.failures} failures/errors + ${result.skipped} skipped exceeds that total.`);
  }
  return result;
}

function laneMetric(text, name) {
  const matches = [...text.matchAll(new RegExp(`(?:^|\\n)#\\s*lane-${name}\\s+(\\d+)\\s*$`, 'gm'))];
  if (matches.length !== 1) {
    throw new Error(`Lane report must carry exactly one lane-${name} aggregate; found ${matches.length}`);
  }
  return Number(matches[0][1]);
}

/**
 * A report has to describe the source it was produced from. Without this, a TAP file left in
 * target/ from a previous checkout is stamped with the current HEAD and published as this
 * commit's evidence.
 */
function laneName(text) {
  const matches = [...text.matchAll(/(?:^|\n)#\s*lane\s+([a-z-]+)\s*$/gm)];
  if (matches.length !== 1) {
    throw new Error(`Lane report must carry exactly one lane identity; found ${matches.length}`);
  }
  return matches[0][1];
}

function tapSha(text) {
  const matches = [...text.matchAll(/(?:^|\n)#\s*source\s+([0-9a-f]{40})\s*$/gm)];
  if (matches.length !== 1) {
    throw new Error(`Lane report must carry exactly one full source SHA; found ${matches.length}`);
  }
  return matches[0][1];
}

export function parseLaneReport(text, expectedSha, {
  file = 'browser lane',
  expectedLane = null
} = {}) {
  const result = {
    lane: laneName(text),
    tests: laneMetric(text, 'tests'),
    pass: laneMetric(text, 'pass'),
    testFailures: laneMetric(text, 'fail'),
    infrastructureFailures: laneMetric(text, 'infrastructure-fail'),
    skipped: laneMetric(text, 'skipped'),
    cancelled: laneMetric(text, 'cancelled'),
    todo: laneMetric(text, 'todo'),
    shards: laneMetric(text, 'shards'),
    sha: tapSha(text),
    sourceDirty: laneMetric(text, 'source-dirty'),
    retried: laneMetric(text, 'retried'),
    requiredCapabilities: laneMetric(text, 'required-capabilities'),
    passedRequiredCapabilities: laneMetric(text, 'required-capabilities-passed')
  };
  // Pattern-mismatch skips never enter a lane aggregate. A remaining skip is therefore a
  // registered product contract that did not execute and must keep the release red, just like
  // cancelled and TODO work. This rejects both skip-only and partially skipped TAP evidence.
  result.failures = result.testFailures + result.infrastructureFailures
    + result.skipped + result.cancelled + result.todo;
  if (expectedLane && result.lane !== expectedLane) {
    throw new Error(`${file} claims lane ${result.lane}, but ${expectedLane} evidence was required.`);
  }
  // A lane that ran nothing is not a passing lane. It used to publish "0 tests, 0
  // failures" and count as green, which is the most expensive kind of false evidence.
  if (result.tests === 0 || result.shards === 0) {
    throw new Error(`${file} reports ${result.tests} tests across ${result.shards} shards. `
      + 'A browser lane cannot be green on nothing.');
  }
  if (result.sha !== expectedSha) {
    throw new Error(`${file} was produced from source ${result.sha}, but HEAD is ${expectedSha}. `
      + 'Re-run that lane against this commit rather than publishing a stale report.');
  }
  if (result.sourceDirty !== 0) {
    throw new Error(`${file} was produced from a dirty source tree. Commit or restore the source, `
      + 'then rerun the lane so the evidence describes the exact branch tip.');
  }
  if (result.retried > result.shards) {
    throw new Error(`${file} reports ${result.retried} retries for only ${result.shards} shards.`);
  }
  if (expectedLane && expectedLane !== 'journeys' && result.retried !== 0) {
    throw new Error(`${file} reports a retry in deterministic ${expectedLane} evidence.`);
  }
  if (result.requiredCapabilities === 0) {
    throw new Error(`${file} reports zero required product capabilities. A suite filename and `
      + 'aggregate test count do not prove the release-critical behaviors executed.');
  }
  if (result.passedRequiredCapabilities !== result.requiredCapabilities) {
    throw new Error(`${file} proves only ${result.passedRequiredCapabilities} of `
      + `${result.requiredCapabilities} required capabilities completed without SKIP/TODO.`);
  }
  const accounted = result.pass + result.testFailures + result.skipped
    + result.cancelled + result.todo;
  if (accounted !== result.tests) {
    throw new Error(`${file} carries impossible test totals: ${result.tests} tests but `
      + `${result.pass} pass + ${result.testFailures} assertion failures + ${result.skipped} skipped `
      + `+ ${result.cancelled} cancelled + ${result.todo} todo = ${accounted}.`);
  }
  return result;
}

function browserResult(file, expectedSha, expectedLane) {
  const report = path.join(target, file);
  if (!fs.existsSync(report)) throw new Error(`Missing ${file}; run its browser lane first.`);
  return parseLaneReport(fs.readFileSync(report, 'utf8'), expectedSha, { expectedLane, file });
}

function main() {
  /* One row per lane that actually runs. The retired SPA lanes (dom-defaults/scenario/spa/fixture/
     audit/seeded/bookrisk/adoption/learn) went with workspace.html in 8654824; demanding their TAPs
     made this report unproducible, which is why CI stopped generating release evidence at all. The
     three lanes below are the ones dom-tests/lane.js writes. */
  const sha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  const rows = [
    ['JUnit', junitResult(sha)],
    ['Browser contracts (deterministic, mocked APIs)',
      browserResult('dom-contracts.tap', sha, 'contracts')],
    ['Browser journeys (packaged jar, fresh database)',
      browserResult('dom-journeys.tap', sha, 'journeys')],
    ['Visual/geometry matrix', browserResult('dom-visual.tap', sha, 'visual')]
  ];
  const failed = rows.reduce((sum, [, result]) => sum + result.failures, 0);
  const retried = rows.reduce((sum, [, result]) => sum + (result.retried || 0), 0);
  const lines = [
    `## StrikeBench release matrix (${sha.slice(0, 12)})`,
    '',
    '| Suite | Shards | Tests | Required capabilities | Skipped | Assertion failures '
      + '| Infrastructure failures | Incomplete | Retried shards |',
    '|---|---:|---:|---:|---:|---:|---:|---:|---:|',
    ...rows.map(([name, result]) => `| ${name} | ${result.shards ?? 1} | ${result.tests} `
      + `| ${result.requiredCapabilities == null ? '—'
        : `${result.passedRequiredCapabilities}/${result.requiredCapabilities}`} `
      + `| ${result.skipped} | ${result.testFailures ?? result.failures} `
      + `| ${result.infrastructureFailures ?? 0} | ${(result.cancelled ?? 0) + (result.todo ?? 0)} `
      + `| ${result.retried ?? 0} |`),
    '',
    failed === 0 ? '**Result: green.**' : `**Result: failed (${failed} failure${failed === 1 ? '' : 's'}).**`,
    retried ? `_${retried} shard${retried === 1 ? '' : 's'} passed only on a retry; a retried shard is `
      + 'not the same evidence as a shard that passed first time._' : '',
    '',
    `_Generated from executable reports at ${new Date().toISOString()}; never transcribed into documentation._`
  ];
  const output = lines.join('\n') + '\n';
  fs.mkdirSync(target, { recursive: true });
  fs.writeFileSync(path.join(target, 'release-matrix.md'), output);
  process.stdout.write(output);
  if (failed) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main();
}
