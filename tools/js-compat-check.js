#!/usr/bin/env node
/**
 * Refuse JavaScript the ground station's browser cannot parse.
 *
 * WHY THIS EXISTS
 * The SIYI handheld runs Android 9 with a stock System WebView. Two optional
 * chains (`geo.results?.length`, `telemetry.windDir?.toFixed(0)`) require
 * Chrome 80; that WebView is older. A syntax error does not fail one feature —
 * it takes the ENTIRE <script> block out at parse time, so every handler in it
 * silently never attaches. On the device that looked like "the settings button
 * does nothing and the simulator won't draw", with nothing on screen to say
 * why, while the same file worked perfectly in desktop Chrome and in every
 * headless test. Nothing else in this repo would have caught it.
 *
 * So: every browser-side file must parse at the ES level below, which is well
 * under the oldest WebView we ship to. Raise TARGET only when every fielded
 * ground station is known to be newer — the whole point is that the floor is
 * set by the worst device, not by the developer's laptop.
 *
 * Usage:  node tools/js-compat-check.js        (exit 1 on failure)
 */
'use strict';
const fs = require('fs');
const path = require('path');

// ES2017 == Chrome/WebView 58. Android 9 ships WebView 66, so this leaves
// margin. async/await is ES2017 and is used; object spread is ES2018 and is
// NOT — use Object.assign instead.
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

/**
 * Every inline <script> in an HTML file, with the line it starts on.
 *
 * HTML comments are blanked first — keeping their newlines so reported line
 * numbers stay true. A comment that mentions a script tag (this repo has one,
 * explaining this very problem) would otherwise be matched as the start of a
 * script and reported as a syntax error in prose.
 */
function inlineScripts(html) {
  const blanked = html.replace(/<!--[\s\S]*?-->/g, c => c.replace(/[^\n]/g, ' '));
  const out = [];
  const re = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(blanked)) !== null) {
    // Index into the ORIGINAL text: blanking preserves length and newlines.
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
