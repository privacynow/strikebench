#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const target = path.join(root, 'target');
const liveOnly = process.argv.includes('--live-only');

function attribute(tag, name) {
  const match = tag.match(new RegExp(`\\b${name}="(\\d+)"`));
  return match ? Number(match[1]) : 0;
}

function junitResult() {
  const dir = path.join(target, 'surefire-reports');
  const files = fs.existsSync(dir)
    ? fs.readdirSync(dir).filter(name => /^TEST-.*\.xml$/.test(name)) : [];
  if (!files.length) throw new Error('No Surefire XML reports found; run mvn test first.');
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
  return files.reduce((total, name) => {
    const xml = fs.readFileSync(path.join(dir, name), 'utf8');
    const tag = xml.match(/<testsuite\b[^>]*>/)?.[0];
    if (!tag) throw new Error(`No testsuite result in ${name}`);
    total.tests += attribute(tag, 'tests');
    total.failures += attribute(tag, 'failures') + attribute(tag, 'errors');
    total.skipped += attribute(tag, 'skipped');
    return total;
  }, { tests: 0, failures: 0, skipped: 0 });
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
function tapSha(text) {
  const match = text.match(/(?:^|\n)#\s*source\s+([0-9a-f]{7,40})/);
  return match ? match[1] : null;
}

export function parseLaneReport(text, expectedSha, { required = true, file = 'browser lane' } = {}) {
  const result = {
    tests: laneMetric(text, 'tests'),
    failures: laneMetric(text, 'fail'),
    skipped: laneMetric(text, 'skipped'),
    shards: laneMetric(text, 'shards'),
    sha: tapSha(text),
    retried: laneMetric(text, 'retried')
  };
  // A required lane that ran nothing is not a passing lane. It used to publish "0 tests, 0
  // failures" and count as green, which is the most expensive kind of false evidence.
  if (required && result.tests === 0) {
    throw new Error(`${file} reports zero tests. A required lane cannot be green on nothing.`);
  }
  if (result.sha && result.sha !== expectedSha) {
    throw new Error(`${file} was produced from source ${result.sha}, but HEAD is ${expectedSha}. `
      + 'Re-run that lane against this commit rather than publishing a stale report.');
  }
  if (required && !result.sha) {
    throw new Error(`${file} carries no source SHA. Re-run it with a lane runner that stamps one, `
      + 'so a report from another checkout cannot be published as this commit\'s evidence.');
  }
  return result;
}

function browserResult(file, expectedSha, { required = true } = {}) {
  const report = path.join(target, file);
  if (!fs.existsSync(report)) throw new Error(`Missing ${file}; run its browser lane first.`);
  return parseLaneReport(fs.readFileSync(report, 'utf8'), expectedSha, { required, file });
}

function main() {
  /* One row per lane that actually runs. The retired SPA lanes (dom-defaults/scenario/spa/fixture/
     audit/seeded/bookrisk/adoption/learn) went with workspace.html in 8654824; demanding their TAPs
     made this report unproducible, which is why CI stopped generating release evidence at all. The
     three lanes below are the ones dom-tests/lane.js writes. */
  const sha = execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  const rows = liveOnly
    ? [['Live-provider capture', browserResult('dom-live.tap', sha)]]
    : [
        ['JUnit', junitResult()],
        ['Browser contracts (deterministic, mocked APIs)', browserResult('dom-contracts.tap', sha)],
        ['Browser journeys (packaged jar, fresh database)', browserResult('dom-journeys.tap', sha)],
        ['Visual/geometry matrix', browserResult('dom-visual.tap', sha)]
      ];
  if (!liveOnly && fs.existsSync(path.join(target, 'dom-live.tap'))) {
    rows.push(['Live-provider browser', browserResult('dom-live.tap', sha, { required: false })]);
  }
  const failed = rows.reduce((sum, [, result]) => sum + result.failures, 0);
  const retried = rows.reduce((sum, [, result]) => sum + (result.retried || 0), 0);
  const lines = [
    `## StrikeBench release matrix (${sha})`,
    '',
    '| Suite | Shards | Tests | Skipped | Failures | Retried shards |',
    '|---|---:|---:|---:|---:|---:|',
    ...rows.map(([name, result]) => `| ${name} | ${result.shards ?? 1} | ${result.tests} `
      + `| ${result.skipped} | ${result.failures} | ${result.retried ?? 0} |`),
    '',
    failed === 0 ? '**Result: green.**' : `**Result: failed (${failed} failure${failed === 1 ? '' : 's'}).**`,
    retried ? `_${retried} shard${retried === 1 ? '' : 's'} passed only on a retry; a retried shard is `
      + 'not the same evidence as a shard that passed first time._' : '',
    '',
    `_Generated from executable reports at ${new Date().toISOString()}; never transcribed into documentation._`
  ];
  const output = lines.join('\n') + '\n';
  fs.mkdirSync(target, { recursive: true });
  fs.writeFileSync(path.join(target, liveOnly ? 'live-release-matrix.md' : 'release-matrix.md'), output);
  process.stdout.write(output);
  if (failed) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main();
}
