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

function tapMetric(text, name) {
  const matches = [...text.matchAll(new RegExp(`(?:^|\\n)(?:#|ℹ)\\s*${name}\\s+(\\d+)`, 'g'))];
  if (!matches.length) throw new Error(`TAP report has no ${name} summary`);
  return Number(matches.at(-1)[1]);
}

function browserResult(file) {
  const report = path.join(target, file);
  if (!fs.existsSync(report)) throw new Error(`Missing ${file}; run that browser suite through tee first.`);
  const text = fs.readFileSync(report, 'utf8');
  return {
    tests: tapMetric(text, 'tests'),
    failures: tapMetric(text, 'fail'),
    skipped: tapMetric(text, 'skipped')
  };
}

const rows = liveOnly
  ? [['Live-provider browser', browserResult('dom-live.tap')]]
  : [
      ['JUnit', junitResult()],
      ['No-silent-defaults contract', browserResult('dom-defaults.tap')],
      ['Scenario level-lens contract', browserResult('dom-scenario.tap')],
      ['Mounted SPA identity contract', browserResult('dom-spa.tap')],
      ['Fixture browser', browserResult('dom-fixture.tap')],
      ['Responsive widths', browserResult('dom-audit.tap')],
      ['Grown-state browser', browserResult('dom-seeded.tap')],
      ['Auth-on browser (signed-out + signed-in)', browserResult('dom-auth.tap')],
      ['Book Risk browser', browserResult('dom-bookrisk.tap')],
      ['Adopted-position two-lens browser', browserResult('dom-adoption.tap')],
      ['Learn explanation coverage', browserResult('dom-learn-coverage.tap')],
      ['Learn SPA browser', browserResult('dom-learn.tap')]
    ];
if (!liveOnly && fs.existsSync(path.join(target, 'dom-live.tap'))) {
  rows.push(['Live-provider browser', browserResult('dom-live.tap')]);
}
const failed = rows.reduce((sum, [, result]) => sum + result.failures, 0);
const sha = execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
const lines = [
  `## StrikeBench release matrix (${sha})`,
  '',
  '| Suite | Tests | Skipped | Failures |',
  '|---|---:|---:|---:|',
  ...rows.map(([name, result]) => `| ${name} | ${result.tests} | ${result.skipped} | ${result.failures} |`),
  '',
  failed === 0 ? '**Result: green.**' : `**Result: failed (${failed} failure${failed === 1 ? '' : 's'}).**`,
  '',
  `_Generated from executable reports at ${new Date().toISOString()}; never transcribed into documentation._`
];
const output = lines.join('\n') + '\n';
fs.mkdirSync(target, { recursive: true });
fs.writeFileSync(path.join(target, liveOnly ? 'live-release-matrix.md' : 'release-matrix.md'), output);
process.stdout.write(output);
if (failed) process.exitCode = 1;
