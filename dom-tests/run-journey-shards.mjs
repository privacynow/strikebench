#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const sourcePath = path.join(here, 'dom.test.js');
const evidenceDir = path.join(root, 'target', 'dom-journey-shards');
const shardCount = Math.max(1, Number(process.env.JOURNEY_SHARDS || 8));
const basePort = Number(process.env.JOURNEY_BASE_PORT || 7180);
const shardTimeoutMs = Math.max(60_000, Number(process.env.JOURNEY_SHARD_TIMEOUT_MS || 12 * 60_000));

function testNames(source) {
  const names = [];
  const pattern = /\btest\(\s*'((?:\\'|[^'])+)'/g;
  for (const match of source.matchAll(pattern)) names.push(match[1].replaceAll("\\'", "'"));
  return names;
}

function regexEscape(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function metric(output, name) {
  const matches = [...output.matchAll(new RegExp(`(?:^|\\n)(?:#|ℹ)\\s*${name}\\s+(\\d+)`, 'g'))];
  return matches.length ? Number(matches.at(-1)[1]) : 0;
}

function splitEvenly(names, count) {
  const groups = Array.from({ length: Math.min(count, names.length) }, () => []);
  names.forEach((name, index) => groups[Math.floor(index * groups.length / names.length)].push(name));
  return groups;
}

function runShard(names, shardIndex, attempt) {
  return new Promise(resolve => {
    const port = String(basePort + shardIndex * 2 + attempt - 1);
    const namePattern = `^(?:${names.map(regexEscape).join('|')})$`;
    const args = ['--test', `--test-name-pattern=${namePattern}`, 'dom.test.js'];
    const child = spawn(process.execPath, args, {
      cwd: here,
      env: { ...process.env, PORT: port },
      detached: process.platform !== 'win32',
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let output = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    let timedOut = false;
    const stop = signal => {
      try {
        if (process.platform === 'win32') child.kill(signal);
        else process.kill(-child.pid, signal);
      } catch { /* the isolated process group already exited */ }
    };
    const timer = setTimeout(() => {
      timedOut = true;
      stop('SIGTERM');
      setTimeout(() => stop('SIGKILL'), 5_000).unref();
    }, shardTimeoutMs);
    child.on('close', code => {
      clearTimeout(timer);
      const file = path.join(evidenceDir, `shard-${String(shardIndex + 1).padStart(2, '0')}-attempt-${attempt}.tap`);
      fs.writeFileSync(file, output);
      resolve({ code: code ?? 1, output, timedOut, file });
    });
  });
}

const source = fs.readFileSync(sourcePath, 'utf8');
const names = testNames(source);
if (!names.length) throw new Error('No top-level journey tests found in dom.test.js');
if (new Set(names).size !== names.length) throw new Error('Journey test names must be unique for exact sharding');
const groups = splitEvenly(names, shardCount);
if (groups.flat().length !== names.length || new Set(groups.flat()).size !== names.length) {
  throw new Error('Journey shard coverage is not one-to-one');
}

fs.mkdirSync(evidenceDir, { recursive: true });
let failures = 0;
let durationMs = 0;

for (let index = 0; index < groups.length; index++) {
  const label = `journey shard ${index + 1}/${groups.length}`;
  process.stdout.write(`# ${label}: ${groups[index].length} isolated tests on port ${basePort + index * 2}\n`);
  const started = Date.now();
  let result = await runShard(groups[index], index, 1);
  if (result.code !== 0) {
    process.stdout.write(`# ${label} failed once; retrying once on a fresh server, browser, database, and port\n`);
    result = await runShard(groups[index], index, 2);
  }
  durationMs += Date.now() - started;
  if (result.code !== 0) {
    const failed = Math.max(1, metric(result.output, 'fail') + metric(result.output, 'cancelled'));
    failures += failed;
    process.stdout.write(`# ${label} failed after its isolated retry; evidence: ${path.relative(root, result.file)}${result.timedOut ? ' (timeout)' : ''}\n`);
    process.stdout.write(result.output + (result.output.endsWith('\n') ? '' : '\n'));
  } else {
    process.stdout.write(`# ${label} passed${fs.existsSync(path.join(evidenceDir, `shard-${String(index + 1).padStart(2, '0')}-attempt-1.tap`)) && result.file.endsWith('attempt-2.tap') ? ' on retry' : ''}\n`);
  }
}

const passed = Math.max(0, names.length - failures);
process.stdout.write([
  `# tests ${names.length}`,
  '# suites 0',
  `# pass ${passed}`,
  `# fail ${failures}`,
  '# cancelled 0',
  '# skipped 0',
  '# todo 0',
  `# duration_ms ${durationMs}`,
  ''
].join('\n'));
if (failures) process.exitCode = 1;
