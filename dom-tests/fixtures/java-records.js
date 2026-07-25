'use strict';

/**
 * Reads the wire contract out of the Java sources so a fixture cannot quietly drift from the
 * record it claims to mirror.
 *
 * The reason this parser exists rather than a hand-maintained list of field names: this repo has
 * already shipped a fixture that lied about the wire shape (the position fan's `sessionDate` was
 * asserted on a record that does not declare it), and the browser suite stayed green because the
 * only authority it consulted was the fixture itself. A hand-written expectation list would have
 * the same failure mode — it is written by the same person, at the same moment, from the same
 * misreading. Parsing `src/main/java` makes the compiler's own declaration the authority.
 *
 * Scope is deliberately narrow: enough Java to read a record header. It does not type-check, does
 * not resolve imports, and does not understand anything outside a record's component list.
 */

const fs = require('node:fs');
const path = require('node:path');

const JAVA_ROOT = path.resolve(__dirname, '../../src/main/java/io/liftandshift/strikebench');

/** Absolute path of a Java source below the StrikeBench package root. */
function javaSource(relativePath) {
  return path.resolve(JAVA_ROOT, relativePath);
}

const sourceCache = new Map();

/** File text with comments blanked out, so a commented-out field never reads as declared. */
function readSource(relativePath) {
  const file = javaSource(relativePath);
  if (!sourceCache.has(file)) sourceCache.set(file, stripComments(fs.readFileSync(file, 'utf8')));
  return sourceCache.get(file);
}

/**
 * Replace comment bodies with spaces, preserving every offset so error messages still point at
 * the real line. String and char literals are tracked because Java sources here contain both
 * `"//"`-looking text inside messages and escaped quotes.
 */
function stripComments(source) {
  let out = '';
  let index = 0;
  let state = 'code';
  while (index < source.length) {
    const ch = source[index];
    const next = source[index + 1];
    if (state === 'code') {
      if (ch === '/' && next === '*') { state = 'block'; out += '  '; index += 2; continue; }
      if (ch === '/' && next === '/') { state = 'line'; out += '  '; index += 2; continue; }
      if (ch === '"') { state = 'string'; out += ch; index += 1; continue; }
      if (ch === "'") { state = 'char'; out += ch; index += 1; continue; }
      out += ch; index += 1; continue;
    }
    if (state === 'block') {
      if (ch === '*' && next === '/') { state = 'code'; out += '  '; index += 2; continue; }
      out += ch === '\n' ? '\n' : ' '; index += 1; continue;
    }
    if (state === 'line') {
      if (ch === '\n') { state = 'code'; out += '\n'; index += 1; continue; }
      out += ' '; index += 1; continue;
    }
    // string / char literal: copy verbatim, honouring backslash escapes
    if (ch === '\\') { out += source.slice(index, index + 2); index += 2; continue; }
    if ((state === 'string' && ch === '"') || (state === 'char' && ch === "'")) state = 'code';
    out += ch; index += 1;
  }
  return out;
}

/**
 * The declared component NAMES of `record <name>(...)`, in declaration order.
 *
 * Nested records are found the same way as top-level ones because the search is for the `record`
 * keyword, not for a file-level type. Pass the simple name (`Greeks`), not the qualified one.
 */
function recordComponents(relativePath, recordName) {
  const source = readSource(relativePath);
  const declaration = new RegExp(`\\brecord\\s+${recordName}\\b`).exec(source);
  if (!declaration) {
    throw new Error(`no record ${recordName} declared in ${relativePath}`);
  }
  let cursor = declaration.index + declaration[0].length;
  // Skip a generic parameter list (`<T, U>`); it never contains parentheses.
  while (cursor < source.length && /\s/.test(source[cursor])) cursor += 1;
  if (source[cursor] === '<') {
    let angle = 0;
    while (cursor < source.length) {
      if (source[cursor] === '<') angle += 1;
      else if (source[cursor] === '>') { angle -= 1; if (angle === 0) { cursor += 1; break; } }
      cursor += 1;
    }
    while (cursor < source.length && /\s/.test(source[cursor])) cursor += 1;
  }
  if (source[cursor] !== '(') {
    throw new Error(`record ${recordName} in ${relativePath} has no component list`);
  }
  const components = splitTopLevel(balanced(source, cursor));
  return components.map(component => {
    // A component is `[annotations] Type name`; the name is always the final identifier, so the
    // type — however deeply generic or annotated — needs no interpretation here.
    const name = /([A-Za-z_$][A-Za-z0-9_$]*)\s*$/.exec(component);
    if (!name) throw new Error(`unreadable component "${component}" of ${recordName}`);
    return name[1];
  });
}

/** The text inside the parentheses that start at `open`. */
function balanced(source, open) {
  let depth = 0;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === '(') depth += 1;
    else if (source[index] === ')') {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, index);
    }
  }
  throw new Error('unbalanced parentheses while reading a record component list');
}

/** Split on commas that sit outside every generic, parenthesis and array bracket. */
function splitTopLevel(text) {
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let index = 0; index < text.length; index += 1) {
    const ch = text[index];
    if (ch === '<' || ch === '(' || ch === '[') depth += 1;
    else if (ch === '>' || ch === ')' || ch === ']') depth -= 1;
    else if (ch === ',' && depth === 0) { parts.push(text.slice(start, index)); start = index + 1; }
  }
  parts.push(text.slice(start));
  return parts.map(part => part.trim()).filter(Boolean);
}

/**
 * The literal keys a method writes into a `Map<String, Object>` response, in write order.
 *
 * Some payloads (portfolio heat) are assembled as a map rather than a record, so the method body
 * IS their contract. Reading the keys out of it keeps those fixtures under the same authority as
 * the record-backed ones instead of exempting them.
 */
function mapPutKeys(relativePath, methodName, mapVariable) {
  const source = readSource(relativePath);
  const signature = new RegExp(`\\b${methodName}\\s*\\(`).exec(source);
  if (!signature) throw new Error(`no method ${methodName} in ${relativePath}`);
  const bodyStart = source.indexOf('{', signature.index + signature[0].length);
  if (bodyStart < 0) throw new Error(`method ${methodName} in ${relativePath} has no body`);
  let depth = 0;
  let bodyEnd = -1;
  for (let index = bodyStart; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}') { depth -= 1; if (depth === 0) { bodyEnd = index; break; } }
  }
  if (bodyEnd < 0) throw new Error(`method ${methodName} in ${relativePath} is unbalanced`);
  const body = source.slice(bodyStart, bodyEnd);
  const keys = [];
  const put = new RegExp(`\\b${mapVariable}\\.put\\(\\s*"([^"]+)"`, 'g');
  let match;
  while ((match = put.exec(body)) !== null) keys.push(match[1]);
  if (!keys.length) throw new Error(`${methodName} in ${relativePath} writes no literal keys`);
  return keys;
}

module.exports = { javaSource, recordComponents, mapPutKeys, stripComments };
