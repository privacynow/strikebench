'use strict';

/*
 * The Desk stylesheet is executable product policy. These contracts keep the visual system from
 * returning to the override forest that made one viewport fix break another: one cascade order,
 * one palette authority, one hidden-state owner, one control geometry, and no desktop-height
 * query that can accidentally match a phone.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const PUBLIC = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'public');
const CSS_FILE = path.join(PUBLIC, 'app.css');
const INDEX_FILE = path.join(PUBLIC, 'index.html');
const css = fs.readFileSync(CSS_FILE, 'utf8');
const index = fs.readFileSync(INDEX_FILE, 'utf8');

function occurrences(pattern) {
  return [...css.matchAll(pattern)].length;
}

function mediaQueries() {
  return [...css.matchAll(/@media\s*([^{]+)\{/g)].map(match =>
    match[1].replace(/\s+/g, ' ').trim());
}

test('app.css has one explicit cascade and one semantic-state authority', () => {
  const physicalStylesheets = fs.readdirSync(PUBLIC).filter(file => file.endsWith('.css'));
  assert.deepEqual(physicalStylesheets, ['app.css'],
    'the served product has one physical stylesheet');
  assert.doesNotMatch(index, /<style\b/i,
    'served markup cannot grow a second inline stylesheet');
  assert.equal([...index.matchAll(/<link\b[^>]*\brel=["']stylesheet["'][^>]*>/gi)].length, 1,
    'the served document links exactly one stylesheet');
  assert.match(css, /@layer reset,tokens,primitives,components,surfaces,responsive,utilities;/);
  assert.equal(occurrences(/:root\s*\{/g), 2,
    'one token root plus the coarse-pointer responsive override are the only :root blocks');
  assert.equal(occurrences(/\[hidden\]\s*\{\s*display:none!important\s*\}/g), 1,
    '[hidden] must have exactly one highest-priority owner');
});

test('Scout has one stable result owner and no orphaned wrapper CSS', () => {
  const orphaned = ['scoutactivegrid', 'scoutresultcard'].filter(className => {
    const generated = new RegExp(`class=["'][^"']*\\b${className}\\b`).test(index);
    return css.includes(`.${className}`) && !generated;
  });
  assert.deepEqual(orphaned, [],
    'the permanent .scoutresults region is the sole result owner; obsolete wrapper CSS cannot return');
});

test('the visual lane retains the required desktop and phone geometry gates', () => {
  const visual = fs.readFileSync(path.join(__dirname, 'desk.visual.test.js'), 'utf8');
  for (const viewport of ['2560x1440', '2000x963', '1920x1080', '390x844', '320x700']) {
    assert.match(visual, new RegExp(viewport.replace('x', 'x')),
      `${viewport} must remain in the geometry matrix`);
  }
  assert.match(visual, /Home gives each job one visible owner and no default board scroll/);
  assert.match(visual, /bands of the board occupy the same pixels/);
  assert.match(visual, /every offered action has a hit target/);
  assert.match(visual, /no media rule deletes a fact/);
});

test('surface rules consume palette tokens instead of inventing near-black themes', () => {
  const tokenEnd = css.indexOf('\n}\n\n@layer reset');
  assert.ok(tokenEnd > 0, 'token layer boundary is readable');
  const surfaces = css.slice(tokenEnd);
  const forbidden = [
    '#0d131c', '#0e141d', '#131a24', '#0f1c22', '#18212e', '#101821',
    '#132030', '#0a0f16', '#0b1017', '#0b0f16', '#33465c', '#39465b'
  ];
  for (const literal of forbidden) {
    assert.doesNotMatch(surfaces.toLowerCase(), new RegExp(literal),
      `${literal} belongs in the token layer, not a surface selector`);
  }
});

test('stepper and playback geometry each have one owner', () => {
  assert.equal(occurrences(/\.mstp>button\s*\{/g), 1,
    'all stepper buttons use the canonical primitive');
  assert.equal(occurrences(/\.playb\s*\{[^}]*inline-size:/gs), 1,
    'play/pause dimensions are owned by the canonical primitive');

  const overrides = [
    /\.declegs\s+\.mstp\s+button\s*\{[^}]*\b(?:width|height)\s*:/s,
    /\.smstps\s+\.mstp\s+button\s*\{[^}]*\b(?:width|height)\s*:/s,
    /\.scenpanel[^{]*\.playb\s*\{[^}]*\b(?:width|height)\s*:/s,
    /\.dccenter[^{]*\.mstp\s+button\s*\{[^}]*\b(?:width|height)\s*:/s
  ];
  for (const override of overrides) {
    assert.doesNotMatch(css, override,
      'a surface may compose a control but may not redefine its hit box');
  }
});

test('height-sensitive responsive rules are width-bounded', () => {
  const unsafe = mediaQueries().filter(query =>
    /height/.test(query)
    && !/width/.test(query)
    && !/prefers-reduced-motion/.test(query));
  assert.deepEqual(unsafe, [],
    'a height-only desktop rule also matches tall phones and is forbidden');
});

test('Learn keeps qualitative payoff pictures without a browser financial engine', () => {
  const strategies = fs.readFileSync(path.join(PUBLIC, 'strategies.js'), 'utf8');
  const shapes = fs.readFileSync(path.join(PUBLIC, 'learn-shapes.js'), 'utf8');

  assert.doesNotMatch(strategies, /\b(?:def|engine|gate)\s*:/,
    'strategy education cannot ship pricing inputs or dormant engine policy');
  assert.doesNotMatch(index, /function\s+learnPay\b/,
    'the browser cannot reconstruct a strategy payoff from legs');
  assert.match(index,
    /<script src="strategies\.js[^>]*><\/script>\s*<script src="learn-shapes\.js[^>]*><\/script>/,
    'the one static qualitative-shape asset loads beside the strategy library');

  const context = { window: {} };
  vm.createContext(context);
  vm.runInContext(shapes, context);
  vm.runInContext(`${strategies}\nthis.__strategies = STRATS;`, context);
  const catalog = context.__strategies;
  const diagrams = context.window.LEARN_PAYOFF_SHAPES;
  assert.equal(catalog.length, 60);
  assert.equal(Object.keys(diagrams).length, catalog.length);
  for (const strategy of catalog) {
    const diagram = diagrams[strategy.nm];
    assert.ok(diagram, `${strategy.nm} has a qualitative diagram`);
    assert.match(diagram.path, /^M\d+(?:\.\d+)?,\d+(?:\.\d+)?L/,
      `${strategy.nm} supplies literal normalized SVG geometry`);
    assert.ok(Number.isFinite(diagram.zero));
    assert.ok(diagram.strikes.every(Number.isFinite));
    assert.deepEqual(Object.keys(diagram).sort(), ['path', 'strikes', 'zero'],
      'a teaching diagram carries geometry only—no price, premium, probability, or P/L');
  }
});
