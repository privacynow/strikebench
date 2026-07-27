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

function normalizeCss(value) {
  return value.replace(/\s+/g, ' ').trim();
}

function splitCssTopLevel(value, delimiter) {
  const parts = [];
  let start = 0;
  let quote = '';
  let round = 0;
  let square = 0;
  for (let i = 0; i < value.length; i += 1) {
    const char = value[i];
    if (quote) {
      if (char === '\\') i += 1;
      else if (char === quote) quote = '';
      continue;
    }
    if (char === '"' || char === "'") quote = char;
    else if (char === '(') round += 1;
    else if (char === ')') round = Math.max(0, round - 1);
    else if (char === '[') square += 1;
    else if (char === ']') square = Math.max(0, square - 1);
    else if (char === delimiter && round === 0 && square === 0) {
      parts.push(value.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(value.slice(start));
  return parts;
}

function cssDuplicateProperties(source) {
  const clean = source.replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '));
  const owners = new Map();

  function matchingBrace(open, end) {
    let depth = 1;
    let quote = '';
    for (let i = open + 1; i < end; i += 1) {
      const char = clean[i];
      if (quote) {
        if (char === '\\') i += 1;
        else if (char === quote) quote = '';
      } else if (char === '"' || char === "'") quote = char;
      else if (char === '{') depth += 1;
      else if (char === '}' && --depth === 0) return i;
    }
    throw new Error(`unclosed CSS block at offset ${open}`);
  }

  function nextBoundary(start, end) {
    let quote = '';
    let round = 0;
    let square = 0;
    for (let i = start; i < end; i += 1) {
      const char = clean[i];
      if (quote) {
        if (char === '\\') i += 1;
        else if (char === quote) quote = '';
        continue;
      }
      if (char === '"' || char === "'") quote = char;
      else if (char === '(') round += 1;
      else if (char === ')') round = Math.max(0, round - 1);
      else if (char === '[') square += 1;
      else if (char === ']') square = Math.max(0, square - 1);
      else if ((char === '{' || char === ';') && round === 0 && square === 0) {
        return { index: i, char };
      }
    }
    return null;
  }

  function declarations(body) {
    const result = [];
    for (const raw of splitCssTopLevel(body, ';')) {
      const declaration = raw.trim();
      if (!declaration) continue;
      const colon = declaration.indexOf(':');
      if (colon <= 0) continue;
      const property = declaration.slice(0, colon).trim().toLowerCase();
      if (/^(?:--[-\w]+|[-\w]+)$/.test(property)) result.push(property);
    }
    return result;
  }

  function visit(start, end, context) {
    let cursor = start;
    while (cursor < end) {
      while (cursor < end && /\s/.test(clean[cursor])) cursor += 1;
      if (cursor >= end) break;
      const boundary = nextBoundary(cursor, end);
      if (!boundary) break;
      if (boundary.char === ';') {
        cursor = boundary.index + 1;
        continue;
      }
      const prelude = normalizeCss(clean.slice(cursor, boundary.index));
      const close = matchingBrace(boundary.index, end);
      const bodyStart = boundary.index + 1;
      if (prelude.startsWith('@')) {
        if (/^@(?:layer|media|container|supports|scope|document)\b/i.test(prelude)) {
          visit(bodyStart, close, context.concat(normalizeCss(prelude)));
        }
      } else if (prelude) {
        const line = clean.slice(0, cursor).split('\n').length;
        const properties = declarations(clean.slice(bodyStart, close));
        for (const rawSelector of splitCssTopLevel(prelude, ',')) {
          const selector = normalizeCss(rawSelector);
          if (!selector) continue;
          const owner = `${context.join(' > ') || '<root>'} :: ${selector}`;
          if (!owners.has(owner)) owners.set(owner, new Map());
          const propertyOwners = owners.get(owner);
          for (const property of properties) {
            if (!propertyOwners.has(property)) propertyOwners.set(property, []);
            propertyOwners.get(property).push(line);
          }
        }
      }
      cursor = close + 1;
    }
  }

  visit(0, clean.length, []);
  const duplicates = [];
  for (const [owner, properties] of owners) {
    for (const [property, lines] of properties) {
      if (lines.length > 1) duplicates.push(`${owner} :: ${property} @ ${lines.join(',')}`);
    }
  }
  return duplicates.sort();
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
  assert.doesNotMatch(css, /(?:^|[,{]\s*)\.stepper(?:[\s.{:#>])/m,
    'the retired parallel .stepper family cannot return');
  assert.doesNotMatch(index, /class=["'][^"']*\bstepper\b/,
    'served markup uses the canonical .mstp component');
  assert.equal(occurrences(/\.mstp>button\s*\{/g), 1,
    'all stepper buttons use the canonical primitive');
  assert.equal(occurrences(/\.iconbtn,.playb,.microcta\s*\{[^}]*inline-size:/gs), 1,
    'icon, play/pause, and micro-action dimensions share the canonical primitive');
  assert.doesNotMatch(css, /(?:^|\n)\.microcta\s*\{[^}]*(?:\bwidth|\bheight|inline-size|block-size)\s*:/s,
    'the micro-action variant cannot redefine the shared hit box');
  assert.doesNotMatch(css, /\.srx-read\s+\.microcta[^{]*\{[^}]*--control-hit\s*:/s,
    'a scenario surface cannot shrink the shared hit box');
  assert.match(css,
    /@media\s*\(pointer:coarse\)\s*\{[^}]*:root\{--control-hit:var\(--control-hit-touch\)\}[^}]*#decideStage\{--control-hit:var\(--control-hit-touch\)\}/s,
    'coarse pointers retain the 44px token inside the more-specific Decide scope');

  const overrides = [
    /\.declegs\s+\.mstp\s+button\s*\{[^}]*(?:\b(?:min-)?width|\b(?:min-)?height|inline-size|block-size)\s*:/s,
    /\.smstps\s+\.mstp\s+button\s*\{[^}]*(?:\b(?:min-)?width|\b(?:min-)?height|inline-size|block-size)\s*:/s,
    /\.scenpanel[^{]*\.playb\s*\{[^}]*(?:\b(?:min-)?width|\b(?:min-)?height|inline-size|block-size)\s*:/s,
    /\.dccenter[^{]*\.mstp\s+button\s*\{[^}]*(?:\b(?:min-)?width|\b(?:min-)?height|inline-size|block-size)\s*:/s
  ];
  for (const override of overrides) {
    assert.doesNotMatch(css, override,
      'a surface may compose a control but may not redefine its hit box');
  }
});

test('a selector and property have one owner in each cascade context', () => {
  assert.deepEqual(cssDuplicateProperties(css), [],
    'merge repeated selector/property declarations inside the same layer/media/container context');
});

test('runtime styles cannot become a parallel semantic stylesheet', () => {
  const documentMarkup = index.slice(0, index.indexOf('<script>'));
  assert.doesNotMatch(documentMarkup, /\bstyle\s*=\s*["']/i,
    'the served document shell uses classes or hidden rather than style attributes');

  const generatedStyleAttributes = [...index.matchAll(
    /\bstyle\s*=\s*(["'])([^\n]*?)\1/g
  )].map(match => match[2]);
  const customOnly = generatedStyleAttributes.filter(value =>
    /^\s*--[-\w]+\s*:/.test(value));
  const dataInkDebt = generatedStyleAttributes.filter(value =>
    !/^\s*--[-\w]+\s*:/.test(value));
  assert.ok(customOnly.length >= 2,
    'generated geometry uses CSS custom properties where already migrated');
  assert.ok(dataInkDebt.length <= 9,
    'legacy generated width/color style attributes are a shrinking, reviewed debt');
  for (const value of generatedStyleAttributes) {
    assert.doesNotMatch(value,
      /(?:^|;)\s*(?:display|position|z-index|transition|opacity|transform-origin|padding|margin)\s*:/i,
      'generated style attributes may carry data ink, never semantic layout or visibility');
  }

  const customPropertyCalls = [...index.matchAll(
    /\.style\.setProperty\(\s*(['"])([^'"]+)\1/g
  )].map(match => match[2]);
  assert.ok(customPropertyCalls.length > 0,
    'measured/data-driven presentation uses explicit custom properties');
  assert.deepEqual(customPropertyCalls.filter(name => !name.startsWith('--')), [],
    'setProperty is restricted to CSS custom properties');

  const directAssignments = [...index.matchAll(
    /\.style\.([A-Za-z_$][\w$]*)\s*=/g
  )].map(match => match[1]);
  const allowedMeasuredProperties = new Set(['left', 'right', 'top', 'transform']);
  const semanticDebt = new Map([
    ['background', 2],
    ['display', 11],
    ['maskImage', 1],
    ['opacity', 10],
    ['position', 2],
    ['transformOrigin', 6],
    ['transition', 14],
    ['width', 3],
    ['zIndex', 2]
  ]);
  const counts = new Map();
  for (const property of directAssignments) {
    if (allowedMeasuredProperties.has(property)) continue;
    counts.set(property, (counts.get(property) || 0) + 1);
  }
  for (const [property, count] of counts) {
    assert.ok(semanticDebt.has(property),
      `style.${property} is semantic presentation and has no reviewed migration debt`);
    assert.ok(count <= semanticDebt.get(property),
      `style.${property} debt grew from ${semanticDebt.get(property)} to ${count}`);
  }
  for (const [property, ceiling] of semanticDebt) {
    assert.ok((counts.get(property) || 0) <= ceiling,
      `style.${property} must only decrease from its reviewed ceiling of ${ceiling}`);
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
