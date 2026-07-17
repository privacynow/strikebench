/* Build contract for TRADER_OWN §10.5: every required concept has both learning depths. */
'use strict';

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const LEARN_PATH = path.resolve(__dirname, '../src/main/resources/public/js/learn.js');

function loadLearn() {
  const sandbox = { window: {} };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(LEARN_PATH, 'utf8'), sandbox, { filename: LEARN_PATH });
  return sandbox.window.Learn;
}

// Concept names match the specification; keys are the one auditable INFO owner for that term.
// Bridge fill and guided interpolation intentionally have separate entries: a generic waypoint
// paragraph cannot prove that both approximation contracts are explained at both levels.
const REQUIRED = Object.freeze({
  participation: 'participation',
  'implied stance': 'impliedstance',
  'stance vector': 'stancevector',
  'realized-vs-headline yield': 'realizedvsheadline',
  'campaign-adjusted economic basis': 'campaignbasis',
  'tracked tax basis': 'trackedtaxbasis',
  campaign: 'campaign',
  'coverage receipt': 'coveragereceipt',
  'fresh-eyes': 'fresheyes',
  adoption: 'adoption',
  thesis: 'thesis',
  objective: 'accountobjective',
  coherence: 'coherenceverdict',
  'book-risk': 'bookrisk',
  churn: 'churncost',
  waypoint: 'waypoint',
  'bridge fill': 'bridgefill',
  'guided interpolation': 'guidedinterpolation',
  'pending import': 'pendingimport',
  resolution: 'resolutionauthority',
  'alert center': 'alertcenter',
  'keyword-derived headline sentiment': 'headlinesentiment',
  'Hypothetical provenance': 'hypotheticalprovenance',
  'Practice provenance': 'practiceprovenance',
  'Recorded-at-broker provenance': 'brokerprovenance'
});

function missingDepths(info, requirements) {
  const failures = [];
  Object.entries(requirements).forEach(([concept, key]) => {
    const entry = info[key];
    if (!entry) {
      failures.push(`${concept}: Learn.INFO.${key} is missing`);
      return;
    }
    ['short', 'beginner', 'expert'].forEach(field => {
      if (typeof entry[field] !== 'string' || entry[field].trim().length < 12) {
        failures.push(`${concept}: Learn.INFO.${key}.${field} is blank or too thin`);
      }
    });
    if (entry.beginner && entry.expert && entry.beginner.trim() === entry.expert.trim()) {
      failures.push(`${concept}: Beginner and Expert repeat the same depth`);
    }
  });
  return failures;
}

test('every Program ONE concept has nonblank Beginner and Expert INFO coverage', () => {
  const learn = loadLearn();
  const failures = missingDepths(learn.INFO || {}, REQUIRED);
  assert.deepEqual(failures, [], `TRADER_OWN §10.5 explanation gaps:\n${failures.join('\n')}`);
});

test('every registry-backed explanation has useful Beginner and Expert depth', () => {
  const learn = loadLearn();
  const requirements = Object.fromEntries(Object.keys(learn.INFO || {}).map(key => [key, key]));
  const failures = missingDepths(learn.INFO || {}, requirements);
  assert.deepEqual(failures, [], `INFO registry depth gaps:\n${failures.join('\n')}`);
});

test('every canonical product-vocabulary label resolves to the same both-level INFO registry', () => {
  const learn = loadLearn();
  const requirements = {};
  Object.entries(learn.VOCABULARY || {}).forEach(([key, entry]) => {
    requirements[entry.label || key] = entry.infoKey;
  });
  const failures = missingDepths(learn.INFO || {}, requirements);
  assert.deepEqual(failures, [], `Product vocabulary explanation gaps:\n${failures.join('\n')}`);
});

test('every strategy guide owns a complete mechanics and risk story', () => {
  const learn = loadLearn();
  const failures = [];
  Object.entries(learn.STRATEGY_GUIDE || {}).forEach(([key, guide]) => {
    ['story', 'win', 'lose', 'watch'].forEach(field => {
      if (typeof guide[field] !== 'string' || guide[field].trim().length < 12) {
        failures.push(`${key}.${field} is blank or too thin`);
      }
    });
    if (!Array.isArray(guide.how) || guide.how.length < 1
        || guide.how.some(step => typeof step !== 'string' || step.trim().length < 8)) {
      failures.push(`${key}.how needs at least one meaningful step`);
    }
  });
  assert.deepEqual(failures, [], `Strategy-guide coverage gaps:\n${failures.join('\n')}`);
});
