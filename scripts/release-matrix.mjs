#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

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

/**
 * SUM every shard's summary, never just the last one.
 *
 * A browser lane writes one TAP file containing one `# tests/pass/fail/skipped` block per shard.
 * Reading `matches.at(-1)` reported the LAST shard only: a 130-test contracts lane was published
 * as 30, both skips vanished, and — the failure that matters — a shard that failed early followed
 * by a clean one was published as green. Release evidence has to be arithmetic over everything
 * that ran, or it is not evidence.
 */
function tapMetric(text, name) {
  const matches = [...text.matchAll(new RegExp(`(?:^|\\n)(?:#|ℹ)\\s*${name}\\s+(\\d+)`, 'g'))];
  if (!matches.length) throw new Error(`TAP report has no ${name} summary`);
  return matches.reduce((total, match) => total + Number(match[1]), 0);
}

/** How many shard summaries a report carries — one per test file the lane ran. */
function tapShardCount(text) {
  return [...text.matchAll(/(?:^|\n)(?:#|ℹ)\s*tests\s+\d+/g)].length;
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

function browserResult(file, { required = true } = {}) {
  const report = path.join(target, file);
  if (!fs.existsSync(report)) throw new Error(`Missing ${file}; run that browser suite through tee first.`);
  const text = fs.readFileSync(report, 'utf8');
  const result = {
    tests: tapMetric(text, 'tests'),
    failures: tapMetric(text, 'fail'),
    skipped: tapMetric(text, 'skipped'),
    shards: tapShardCount(text),
    sha: tapSha(text),
    retried: [...text.matchAll(/(?:^|\n)#\s*retrying /g)].length
  };
  // A required lane that ran nothing is not a passing lane. It used to publish "0 tests, 0
  // failures" and count as green, which is the most expensive kind of false evidence.
  if (required && result.tests === 0) {
    throw new Error(`${file} reports zero tests. A required lane cannot be green on nothing.`);
  }
  if (result.sha && result.sha !== sha) {
    throw new Error(`${file} was produced from source ${result.sha}, but HEAD is ${sha}. `
      + 'Re-run that lane against this commit rather than publishing a stale report.');
  }
  if (required && !result.sha) {
    throw new Error(`${file} carries no source SHA. Re-run it with a lane runner that stamps one, `
      + 'so a report from another checkout cannot be published as this commit\'s evidence.');
  }
  return result;
}

/* One row per lane that actually runs. The retired SPA lanes (dom-defaults/scenario/spa/fixture/
   audit/seeded/bookrisk/adoption/learn) went with workspace.html in 8654824; demanding their TAPs
   made this report unproducible, which is why CI stopped generating release evidence at all. The
   three lanes below are the ones dom-tests/lane.js writes. */
const sha = execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
const rows = liveOnly
  ? [['Live-provider capture', browserResult('dom-live.tap')]]
  : [
      ['JUnit', junitResult()],
      ['Browser contracts (deterministic, mocked APIs)', browserResult('dom-contracts.tap')],
      ['Browser journeys (packaged jar, fresh database)', browserResult('dom-journeys.tap')],
      ['Visual/geometry matrix', browserResult('dom-visual.tap')]
    ];
if (!liveOnly && fs.existsSync(path.join(target, 'dom-live.tap'))) {
  rows.push(['Live-provider browser', browserResult('dom-live.tap', { required: false })]);
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
