'use strict';
// Per-suite isolated Postgres databases for the browser suites. Each spawned jar gets a fresh,
// empty database in the running dev Postgres (the docker-compose 'db' container) and migrates
// it on boot via Flyway. Requires `docker compose up -d db`. Override with TEST_DB_* env.
const { spawnSync } = require('node:child_process');

const CONTAINER = process.env.TEST_DB_CONTAINER || 'strikebench-db';
const HOST = process.env.TEST_DB_HOST || 'localhost';
const PGPORT = process.env.TEST_DB_PORT || '5432';
const USER = process.env.TEST_DB_USER || 'strikebench';
const PASS = process.env.TEST_DB_PASSWORD || 'strikebench';
let seq = 0;

function psqlOn(dbName, sql) {
  const r = spawnSync('docker',
    ['exec', CONTAINER, 'psql', '-U', USER, '-d', dbName, '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error('psql failed (is `docker compose up -d db` running?): ' + (r.stderr || r.stdout || r.error));
  }
  return (r.stdout || '').trim();
}

/** A fresh empty Postgres database; returns its jar env, a raw sql(), and drop(). */
function freshDb() {
  const name = 'domtest_' + process.pid + '_' + (++seq) + '_' + Date.now().toString(36);
  psqlOn('strikebench_dev', 'CREATE DATABASE ' + name);
  return {
    name,
    env: {
      DB_URL: `jdbc:postgresql://${HOST}:${PGPORT}/${name}`,
      DB_USER: USER,
      DB_PASSWORD: PASS,
    },
    sql: (statement) => psqlOn(name, statement),
    drop() { try { psqlOn('strikebench_dev', `DROP DATABASE IF EXISTS ${name} WITH (FORCE)`); } catch (e) { /* best-effort */ } },
  };
}

module.exports = { freshDb };
