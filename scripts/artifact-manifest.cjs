#!/usr/bin/env node
'use strict';

/*
 * One release-artifact identity owner.
 *
 * The backend job writes this manifest beside the jar it built and tested. Packaged-browser
 * journeys download and verify those exact two files; they never rebuild a second jar. Local
 * journey lanes use the same verifier, so a stale, substituted, or post-build-modified jar cannot
 * masquerade as the artifact for the current checkout.
 */

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const SCHEMA_VERSION = 1;
const DEFAULT_ROOT = path.resolve(__dirname, '..');
const DEFAULT_JAR = path.join(DEFAULT_ROOT, 'target', 'strikebench.jar');
const DEFAULT_MANIFEST = path.join(DEFAULT_ROOT, 'target', 'strikebench-artifact.json');

function fullSha(value, label) {
  const normalized = String(value || '').trim().toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(normalized)) {
    throw new Error(`${label} must be a full 40-character commit SHA`);
  }
  return normalized;
}

function fileSha256(file) {
  const hash = crypto.createHash('sha256');
  hash.update(fs.readFileSync(file));
  return hash.digest('hex');
}

function sourceIdentity(root = DEFAULT_ROOT) {
  const sourceSha = fullSha(execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: root,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'inherit']
  }), 'git HEAD');
  const status = execFileSync('git', [
    'status', '--porcelain=v1', '--untracked-files=all', '--',
    'src/main', 'pom.xml'
  ], {
    cwd: root,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'inherit']
  }).trim();
  return { sourceSha, sourceDirty: status.length > 0 };
}

function createArtifactManifest(jarFile = DEFAULT_JAR, manifestFile = DEFAULT_MANIFEST, options = {}) {
  const jar = path.resolve(jarFile);
  const manifest = path.resolve(manifestFile);
  if (!fs.existsSync(jar) || !fs.statSync(jar).isFile()) {
    throw new Error(`release jar does not exist: ${jar}`);
  }
  const identity = options.sourceSha
    ? {
        sourceSha: fullSha(options.sourceSha, 'sourceSha'),
        sourceDirty: Boolean(options.sourceDirty)
      }
    : sourceIdentity(options.root || DEFAULT_ROOT);
  const receipt = {
    schemaVersion: SCHEMA_VERSION,
    sourceSha: identity.sourceSha,
    sourceDirty: identity.sourceDirty,
    jarFile: path.basename(jar),
    jarSize: fs.statSync(jar).size,
    jarSha256: fileSha256(jar)
  };
  fs.mkdirSync(path.dirname(manifest), { recursive: true });
  fs.writeFileSync(manifest, `${JSON.stringify(receipt, null, 2)}\n`);
  return receipt;
}

function readManifest(manifestFile) {
  let receipt;
  try {
    receipt = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
  } catch (error) {
    throw new Error(`artifact manifest is not valid JSON: ${error.message}`);
  }
  if (receipt == null || typeof receipt !== 'object' || Array.isArray(receipt)) {
    throw new Error('artifact manifest must be a JSON object');
  }
  if (receipt.schemaVersion !== SCHEMA_VERSION) {
    throw new Error(`artifact manifest schema ${receipt.schemaVersion} is not supported`);
  }
  receipt.sourceSha = fullSha(receipt.sourceSha, 'manifest sourceSha');
  if (typeof receipt.sourceDirty !== 'boolean') {
    throw new Error('artifact manifest sourceDirty must be boolean');
  }
  if (typeof receipt.jarFile !== 'string' || !receipt.jarFile
      || path.basename(receipt.jarFile) !== receipt.jarFile) {
    throw new Error('artifact manifest jarFile must be one basename');
  }
  if (!Number.isSafeInteger(receipt.jarSize) || receipt.jarSize <= 0) {
    throw new Error('artifact manifest jarSize must be a positive integer');
  }
  if (typeof receipt.jarSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(receipt.jarSha256)) {
    throw new Error('artifact manifest jarSha256 must be a lowercase SHA-256 digest');
  }
  return receipt;
}

function verifyArtifactManifest(jarFile = DEFAULT_JAR, manifestFile = DEFAULT_MANIFEST, options = {}) {
  const jar = path.resolve(jarFile);
  const manifest = path.resolve(manifestFile);
  if (!fs.existsSync(jar) || !fs.statSync(jar).isFile()) {
    throw new Error(`release jar does not exist: ${jar}`);
  }
  if (!fs.existsSync(manifest) || !fs.statSync(manifest).isFile()) {
    throw new Error(`artifact manifest does not exist: ${manifest}`);
  }
  const receipt = readManifest(manifest);
  if (receipt.jarFile !== path.basename(jar)) {
    throw new Error(`artifact manifest names ${receipt.jarFile}, not ${path.basename(jar)}`);
  }
  const actualSize = fs.statSync(jar).size;
  if (receipt.jarSize !== actualSize) {
    throw new Error(`artifact jar size is ${actualSize}, manifest requires ${receipt.jarSize}`);
  }
  const actualDigest = fileSha256(jar);
  if (receipt.jarSha256 !== actualDigest) {
    throw new Error(`artifact jar SHA-256 is ${actualDigest}, manifest requires ${receipt.jarSha256}`);
  }
  if (options.expectedSha != null) {
    const expected = fullSha(options.expectedSha, 'expected source SHA');
    if (receipt.sourceSha !== expected) {
      throw new Error(`artifact was built from ${receipt.sourceSha}, expected ${expected}`);
    }
  }
  if (options.requireClean && receipt.sourceDirty) {
    throw new Error('artifact was built from a dirty application source tree');
  }
  return receipt;
}

function usage() {
  process.stderr.write(
    'usage:\n'
    + '  node scripts/artifact-manifest.cjs write [jar] [manifest]\n'
    + '  node scripts/artifact-manifest.cjs verify [jar] [manifest] [expected-sha] [--require-clean]\n'
  );
}

function main(argv = process.argv.slice(2)) {
  const [command, rawJar, rawManifest, rawSha, ...rest] = argv;
  const jar = rawJar ? path.resolve(rawJar) : DEFAULT_JAR;
  const manifest = rawManifest ? path.resolve(rawManifest) : DEFAULT_MANIFEST;
  if (command === 'write') {
    if (rawSha != null || rest.length) {
      usage();
      process.exitCode = 2;
      return;
    }
    const receipt = createArtifactManifest(jar, manifest);
    process.stdout.write(
      `wrote ${path.relative(DEFAULT_ROOT, manifest)} for ${receipt.sourceSha} `
      + `(${receipt.jarSha256})\n`
    );
    return;
  }
  if (command === 'verify') {
    const flags = [rawSha, ...rest].filter(value => value === '--require-clean');
    const expectedSha = rawSha === '--require-clean' ? null : rawSha;
    const unknown = rest.filter(value => value !== '--require-clean');
    if (unknown.length) {
      usage();
      process.exitCode = 2;
      return;
    }
    const receipt = verifyArtifactManifest(jar, manifest, {
      expectedSha,
      requireClean: flags.length > 0
    });
    process.stdout.write(
      `verified ${path.relative(DEFAULT_ROOT, jar)} from ${receipt.sourceSha} `
      + `(${receipt.jarSha256})\n`
    );
    return;
  }
  usage();
  process.exitCode = 2;
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`artifact-manifest: ${error.message}\n`);
    process.exitCode = 1;
  }
}

module.exports = {
  createArtifactManifest,
  fileSha256,
  readManifest,
  sourceIdentity,
  verifyArtifactManifest
};
