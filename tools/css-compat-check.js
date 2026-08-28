#!/usr/bin/env node
/**
 * Refuse CSS the ground station's browser cannot render.
 *
 * WHY THIS EXISTS
 * `grid-template-columns: 1fr clamp(170px,26vw,320px)` needs Chrome 79. On a
 * ground station with an older WebView the browser could not parse the value,
 * so it DROPPED the whole declaration — the grid fell back to one implicit
 * column, and the video pane, whose children are all absolutely positioned,
 * collapsed to zero height. The operator got a full screen of parameters and no
 * video, on a build that looked perfect on every other machine.
 *
 * That is the shape of every CSS compatibility failure: not a warning, not a
 * fallback, but one declaration silently vanishing and taking the layout with
 * it. The JS checker next door exists for the same reason.
 *
 * Usage:  node tools/css-compat-check.js        (exit 1 on failure)
 */
'use strict';
const fs = require('fs');
const path = require('path');

// Chrome/WebView 66 — Android 9's stock WebView, the oldest fielded.
const FLOOR = 66;

/**
 * Each rule: what to look for, the Chrome version it needs, and what to write
 * instead. Kept deliberately small — only things actually seen in this file or
 * likely to be reached for next.
 */
const RULES = [
  { re: /\bclamp\s*\(/,                 since: 79, use: 'stepped @media queries with fixed px' },
  { re: /:\s*min\s*\(/,                 since: 79, use: 'a fixed value plus @media queries' },
  { re: /:\s*max\s*\(/,                 since: 79, use: 'a fixed value plus @media queries' },
  { re: /(^|[;{"\s])inset\s*:/,         since: 87, use: 'top/right/bottom/left longhand' },
  { re: /\baspect-ratio\s*:/,           since: 88, use: 'a padding-top percentage box' },
  { re: /:is\s*\(/,                     since: 88, use: 'the selectors written out' },
  { re: /:where\s*\(/,                  since: 88, use: 'the selectors written out' },
  { re: /\bgap\s*:/,                    since: 84, use: "margins ('> * + *'), or grid-gap for a GRID container",
    // Grid gap is fine at the floor; only FLEX gap needs 84. Checked below.
    flexOnly: true },
];

const PUBLIC = path.join(__dirname, '..', 'public');

/** Strip comments and JS so we only inspect real CSS. */
function cssOf(name, text) {
  if (name.endsWith('.css')) return [{ css: text, offset: 0 }];
  const out = [];
  const re = /<style[^>]*>([\s\S]*?)<\/style>/gi;
  let m;
  while ((m = re.exec(text)) !== null) {
    out.push({ css: m[1], offset: text.slice(0, m.index).split('\n').length - 1 });
  }
  // Inline style="..." attributes are CSS too, and are where a gap most often
  // slips back in.
  const attr = /style\s*=\s*"([^"]*)"/gi;
  while ((m = attr.exec(text)) !== null) {
    out.push({ css: m[1], offset: text.slice(0, m.index).split('\n').length - 1, inline: true });
  }
  return out;
}

const failures = [];
for (const name of fs.readdirSync(PUBLIC)) {
  if (!/\.(html|css)$/.test(name)) continue;
  const text = fs.readFileSync(path.join(PUBLIC, name), 'utf8');
  for (const block of cssOf(name, text)) {
    // Drop /* comments */ so prose about clamp() is not reported as clamp().
    const clean = block.css.replace(/\/\*[\s\S]*?\*\//g, c => c.replace(/[^\n]/g, ' '));
    clean.split('\n').forEach((line, i) => {
      for (const r of RULES) {
        if (r.since <= FLOOR || !r.re.test(line)) continue;
        // gap on a grid container is supported at the floor; only flex is not.
        if (r.flexOnly && /display\s*:\s*(inline-)?grid|grid-template|^\s*\.tg\b/.test(line)) continue;
        failures.push({
          file: `public/${name}`, line: block.offset + i + 1,
          text: line.trim().slice(0, 100),
          need: r.since, use: r.use, inline: block.inline,
        });
      }
    });
  }
}

if (failures.length) {
  console.error(`\n✕ Browser CSS must render on Chrome/WebView ${FLOOR}. ${failures.length} problem(s):\n`);
  for (const f of failures) {
    console.error(`  ${f.file}:${f.line}${f.inline ? ' (inline style)' : ''} — needs Chrome ${f.need}`);
    console.error(`    ${f.text}`);
    console.error(`    use: ${f.use}\n`);
  }
  console.error(
`An unsupported VALUE does not degrade — the browser drops the whole
declaration. That is how the layout collapsed to a full screen of parameters
with no video pane on a build that looked correct everywhere else.
`);
  process.exit(1);
}

console.log(`✓ Browser CSS renders on Chrome/WebView ${FLOOR}.`);
