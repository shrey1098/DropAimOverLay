#!/usr/bin/env node

'use strict';
const fs = require('fs');
const path = require('path');

const FLOOR = 66;

const RULES = [
  { re: /\bclamp\s*\(/,                 since: 79, use: 'stepped @media queries with fixed px' },
  { re: /:\s*min\s*\(/,                 since: 79, use: 'a fixed value plus @media queries' },
  { re: /:\s*max\s*\(/,                 since: 79, use: 'a fixed value plus @media queries' },
  { re: /(^|[;{"\s])inset\s*:/,         since: 87, use: 'top/right/bottom/left longhand' },
  { re: /\baspect-ratio\s*:/,           since: 88, use: 'a padding-top percentage box' },
  { re: /:is\s*\(/,                     since: 88, use: 'the selectors written out' },
  { re: /:where\s*\(/,                  since: 88, use: 'the selectors written out' },
  { re: /\bgap\s*:/,                    since: 84, use: "margins ('> * + *'), or grid-gap for a GRID container",

    flexOnly: true },
];

const PUBLIC = path.join(__dirname, '..', 'public');

function cssOf(name, text) {
  if (name.endsWith('.css')) return [{ css: text, offset: 0 }];
  const out = [];
  const re = /<style[^>]*>([\s\S]*?)<\/style>/gi;
  let m;
  while ((m = re.exec(text)) !== null) {
    out.push({ css: m[1], offset: text.slice(0, m.index).split('\n').length - 1 });
  }

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

    const clean = block.css.replace(/\/\*[\s\S]*?\*\//g, c => c.replace(/[^\n]/g, ' '));
    clean.split('\n').forEach((line, i) => {
      for (const r of RULES) {
        if (r.since <= FLOOR || !r.re.test(line)) continue;

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
