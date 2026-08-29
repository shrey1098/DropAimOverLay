#!/usr/bin/env node

'use strict';
const fs = require('fs');
const path = require('path');

const TARGET = 2017;
const TARGET_BROWSER = 'Chrome/WebView 58';

const REPO = path.join(__dirname, '..');
const PUBLIC = path.join(REPO, 'public');

let acorn;
try { acorn = require('acorn'); }
catch (e) {
  console.error('js-compat-check: acorn is not installed. Run `npm install` in the repo root.');
  process.exit(2);
}

function inlineScripts(html) {
  const blanked = html.replace(/<!--[\s\S]*?-->/g, c => c.replace(/[^\n]/g, ' '));
  const out = [];
  const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(blanked)) !== null) {

    out.push({ code: html.slice(m.index + m[0].indexOf('>') + 1, m.index + m[0].length - '</script>'.length),
               startLine: html.slice(0, m.index).split('\n').length });
  }
  return out;
}

function check(label, code, lineOffset) {
  try {
    acorn.parse(code, { ecmaVersion: TARGET });
    return null;
  } catch (e) {
    const line = e.loc ? e.loc.line + lineOffset : '?';
    const col = e.loc ? e.loc.column : '?';
    return `${label}:${line}:${col} — ${e.message.replace(/ \(\d+:\d+\)$/, '')}`;
  }
}

const failures = [];
for (const name of fs.readdirSync(PUBLIC)) {
  const file = path.join(PUBLIC, name);
  if (name.endsWith('.js')) {
    const f = check(`public/${name}`, fs.readFileSync(file, 'utf8'), 0);
    if (f) failures.push(f);
  } else if (name.endsWith('.html')) {
    const html = fs.readFileSync(file, 'utf8');
    inlineScripts(html).forEach((s, i) => {
      const f = check(`public/${name} (inline script ${i + 1})`, s.code, s.startLine - 1);
      if (f) failures.push(f);
    });
  }
}

if (failures.length) {
  console.error(`\n✕ Browser JS must parse as ES${TARGET} (${TARGET_BROWSER}). ${failures.length} problem(s):\n`);
  failures.forEach(f => console.error('  ' + f));
  console.error(`
Common offenders and their replacements:
  a?.b            ->  a && a.b
  a ?? b          ->  (a === null || a === undefined) ? b : a
  {...x, ...y}    ->  Object.assign({}, x, y)
  a ||= b         ->  a = a || b
  s.replaceAll(…) ->  s.split(…).join(…)

This is not a style rule. A parse error disables the whole <script> block on
the device, and every handler in it stops existing.
`);
  process.exit(1);
}

console.log(`✓ Browser JS parses as ES${TARGET} (${TARGET_BROWSER}).`);
