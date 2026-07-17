'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const publicJs = path.join(__dirname, '..', 'src', 'main', 'resources', 'public', 'js');

function harness({ matches = [], headerRiskExplicit = false, riskValue = 'conservative' } = {}) {
  const created = [];
  const focused = [];
  const sandbox = {
    console,
    Date,
    Map,
    Set,
    Promise,
    Object,
    Number,
    String,
    Math,
    Array,
    RegExp,
    isFinite,
    parseFloat,
    parseInt,
    UI: {},
    Learn: {},
    Workspace: {},
    API: {},
    App: {
      state: { headerRiskExplicit },
      context: {
        symbol: () => '', goal: () => null, horizon: () => null, thesis: () => null
      },
      navigate: () => { throw new Error('unexpected navigation'); }
    },
    document: {
      getElementById: id => id === 'risk-mode' ? { value: riskValue } : null
    },
    PlanStore: {
      matching: async () => matches,
      create: async body => {
        created.push(body);
        return { id: `plan_${created.length}`, symbol: body.symbol, intent: body.intent,
          assumptionsEditable: true,
          context: { thesis: body.thesis, horizonDays: body.horizonDays,
            targetCents: body.targetCents, riskMode: body.riskMode } };
      },
      focus: async (plan, stage) => { focused.push({ plan, stage }); return plan; },
      path: (plan, stage) => `#/plan/${plan.id}/${String(stage).toLowerCase()}`
    }
  };
  sandbox.window = sandbox;
  sandbox.window.location = { hash: '#/research/AAPL' };
  sandbox.window.requestAnimationFrame = () => 0;
  sandbox.window.ViewPlan = { intentLabel: intent => intent };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(publicJs, 'contracts.js'), 'utf8'), sandbox);
  vm.runInContext(fs.readFileSync(path.join(publicJs, 'views.js'), 'utf8'), sandbox);
  return { startPlan: sandbox.ViewShared.startPlan,
    planContextDeclared: sandbox.ViewShared.planContextDeclared,
    Horizon: sandbox.Product.Horizon, created, focused };
}

test('horizon helpers distinguish an explicit month from an absent declaration', () => {
  const h = harness();

  assert.equal(h.Horizon.sessions('month'), 21);
  assert.equal(h.Horizon.sessions(null), null);
  assert.equal(h.Horizon.expiryDays(null), null);
  assert.equal(h.Horizon.keyForSessions(null), null);
});

test('client declaration readiness includes risk posture', () => {
  const h = harness();
  const plan = { intent: 'DIRECTIONAL', context: {
    thesis: 'bullish', horizonDays: 21, riskMode: null
  } };

  assert.equal(h.planContextDeclared(plan), false);
  plan.context.riskMode = 'balanced';
  assert.equal(h.planContextDeclared(plan), true);
  plan.intent = 'INCOME';
  plan.context.thesis = null;
  assert.equal(h.planContextDeclared(plan), false,
    'a goal never manufactures a neutral forward view');
});

test('canonical discovery controls begin blank and retired defaults stay absent', () => {
  const research = fs.readFileSync(path.join(publicJs, 'views-research.js'), 'utf8');
  const views = fs.readFileSync(path.join(publicJs, 'views.js'), 'utf8');
  const builder = fs.readFileSync(path.join(publicJs, 'builder.js'), 'utf8');
  const portfolio = fs.readFileSync(path.join(publicJs, 'views-portfolio.js'), 'utf8');
  const index = fs.readFileSync(path.join(publicJs, '..', 'index.html'), 'utf8');
  const scout = research.slice(research.indexOf('function universePlanScout()'),
    research.indexOf('function researchEvidenceOwnerKey'));
  const home = views.slice(views.indexOf('function homeIdeasSection()'),
    views.indexOf('// ---------- 0. Home dashboard'));
  const construct = portfolio.slice(portfolio.indexOf('async function openOptimizationPlan'),
    portfolio.indexOf('function portfolioModeNav'));

  assert.match(index, /<button id="risk-mode"[^>]*value=""/);
  assert.doesNotMatch(index, /<select id="risk-mode"/);
  assert.equal((scout.match(/value: null/g) || []).length, 3,
    'goal, horizon, and risk posture all start undeclared');
  assert.match(scout, /UI\.chipSet[\s\S]*UI\.segmented[\s\S]*UI\.chipSet/);
  assert.doesNotMatch(scout, /el\('select'/);
  assert.doesNotMatch(home, /\/api\/research\/scout|Scout current market|Refresh/);
  assert.match(home, /\/api\/welcome\/teaching-example/);
  assert.doesNotMatch(builder, /tradeGoal\(App\.context\.goal\(\), 'DIRECTIONAL'\)/);
  assert.match(builder, /Choose the Plan goal before preparing this Builder package/);
  assert.match(construct, /state\.explicit = state\.explicit \|\| \{\}/,
    'Portfolio Construct persists only choices explicitly owned by the user');
  assert.match(construct, /UI\.chipSet[\s\S]*UI\.segmented[\s\S]*UI\.segmented[\s\S]*UI\.segmented/,
    'Portfolio Construct uses the shared choice language for goal, view, horizon, and objective');
  assert.doesNotMatch(construct, /el\('select'|Any goal|form\.goal \|\| 'DIRECTIONAL'|form\.thesis \|\| 'neutral'|form\.horizon \|\| 'month'|form\.objective \|\| 'DECISION'/);
  assert.doesNotMatch(construct, /document\.getElementById\('risk-mode'\)[\s\S]{0,100}\|\| 'conservative'/,
    'Construct never converts an undeclared header posture into Cautious');
  assert.match(construct, /This allocation no longer carries the exact construction declaration and package/,
    'Plan handoff refuses a package that lost its exact declaration');
  assert.match(construct, /marketLane:[\s\S]*worldId:[\s\S]*datasetId:[\s\S]*symbols:[\s\S]*accountId:[\s\S]*buyingPowerCents:/,
    'a construction result freezes its market, source field, Practice account, and buying power');
  assert.match(construct, /savedForm\.sourceFingerprint === sourceFingerprint\(scope\.value\(\)\)/,
    'a remount refuses a result whose mechanical source context changed while detached');
  assert.match(construct, /chip\('Your decision'[\s\S]*Learn\.currentLevel\(\) === 'expert'[\s\S]*chip\('Declaration keys'/,
    'the primary receipt is human language; raw declaration keys are Expert-only provenance');
  assert.match(construct, /chip\('Market', source\.marketLabel\)[\s\S]*Learn\.currentLevel\(\) === 'expert'[\s\S]*chip\('Source keys'/,
    'market/world/dataset/account IDs stay out of Beginner chrome and remain Expert provenance');
});

test('Ready without staged assumptions creates an honest incomplete Plan and opens Your view', async () => {
  const h = harness();

  await h.startPlan({ symbol: 'AAPL', intent: 'DIRECTIONAL' }, 'STRATEGY');

  assert.equal(h.created.length, 1);
  assert.equal(h.created[0].thesis, null);
  assert.equal(h.created[0].horizonDays, null);
  assert.equal(h.created[0].riskMode, null);
  assert.equal(h.focused[0].stage, 'UNDERSTAND');
});

test('an old same-symbol Plan cannot donate assumptions to a fresh incomplete inquiry', async () => {
  const old = { id: 'plan_old', symbol: 'AAPL', intent: 'DIRECTIONAL', assumptionsEditable: true,
    context: { thesis: 'bullish', horizonDays: 21, targetCents: 30000, riskMode: 'conservative' } };
  const h = harness({ matches: [old] });

  await h.startPlan({ symbol: 'AAPL', intent: 'DIRECTIONAL' }, 'STRATEGY');

  assert.equal(h.created.length, 1);
  assert.equal(h.created[0].originPlanId, old.id);
  assert.equal(h.created[0].thesis, null);
  assert.equal(h.created[0].horizonDays, null);
  assert.equal(h.created[0].targetCents, null);
  assert.equal(h.focused[0].stage, 'UNDERSTAND');
});

test('staged Evidence and a genuinely selected header risk carry their exact declaration', async () => {
  const h = harness({ headerRiskExplicit: true, riskValue: 'balanced' });

  await h.startPlan({ symbol: 'TSLA', intent: 'DIRECTIONAL', thesis: 'bearish',
    horizon: '23d', target: 250 }, 'STRATEGY');

  assert.equal(h.created[0].thesis, 'bearish');
  assert.equal(h.created[0].horizonDays, 23);
  assert.equal(h.created[0].targetCents, 25000);
  assert.equal(h.created[0].riskMode, 'balanced');
  assert.equal(h.focused[0].stage, 'STRATEGY');
});

test('a frozen Plan branches only the exact assumptions explicitly owned by the caller', async () => {
  const frozen = { id: 'plan_frozen', symbol: 'TSLA', intent: 'DIRECTIONAL', assumptionsEditable: false,
    context: { thesis: 'bearish', horizonDays: 23, targetCents: 25000, riskMode: 'balanced' } };
  const h = harness({ matches: [frozen], headerRiskExplicit: true, riskValue: 'balanced' });

  await h.startPlan({ symbol: 'TSLA', intent: 'DIRECTIONAL', thesis: 'bearish',
    horizon: '23d', target: 250 }, 'STRATEGY');

  assert.equal(h.created[0].originPlanId, frozen.id);
  assert.deepEqual({ thesis: h.created[0].thesis, horizonDays: h.created[0].horizonDays,
    targetCents: h.created[0].targetCents, riskMode: h.created[0].riskMode },
  { thesis: 'bearish', horizonDays: 23, targetCents: 25000, riskMode: 'balanced' });
  assert.equal(h.focused[0].stage, 'STRATEGY');
});

test('ordinary Research cannot receive an old risk posture even when other facts match', async () => {
  const old = { id: 'plan_old_risk', symbol: 'TSLA', intent: 'DIRECTIONAL', assumptionsEditable: true,
    context: { thesis: 'bearish', horizonDays: 23, targetCents: null, riskMode: 'conservative' } };
  const h = harness({ matches: [old] });

  await h.startPlan({ symbol: 'TSLA', intent: 'DIRECTIONAL', thesis: 'bearish', horizon: '23d' }, 'STRATEGY');

  assert.equal(h.created.length, 1);
  assert.equal(h.created[0].originPlanId, old.id);
  assert.equal(h.created[0].riskMode, null);
  assert.equal(h.focused[0].stage, 'UNDERSTAND');
});
