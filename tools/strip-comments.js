#!/usr/bin/env node
/**
 * Strip comments from the web assets for a release build.
 *
 * WHY
 * The aim solver, the drag model, the wind profile and the payload constants
 * ship as plain JavaScript inside the APK — `unzip` and read. The code alone is
 * work to follow; the 250-odd comment lines explaining WHY each constant is what
 * it is are the part worth stealing. Removing them does not make the app secure,
 * it removes the free explanation.
 *
 * NOT a minifier. It deletes comments and trailing whitespace, and nothing else:
 * no renaming, no reordering, no semicolon games. A minifier that mangles this
 * file and ships a subtly broken aim solution to an aircraft is a far worse
 * outcome than a readable one, and the reward for the extra risk is a few KB.
 *
 * Both passes are single left-to-right scans, not regexes, because both have
 * already been caught out by the alternative:
 *   - in JS, `//` inside a string or a regex literal is not a comment, and a
 *     regex literal cannot be told from division without tracking what precedes
 *     it;
 *   - in HTML, protecting script bodies with a regex matched the `<script>`
 *     written inside a COMMENT, leaving that comment unterminated and deleting
 *     the error trap and the layout container along with it.
 *
 * Usage:  node tools/strip-comments.js <in> <out>
 */
'use strict';
const fs = require('fs');

/** True when a `/` at this point starts a regex literal rather than division. */
function regexAllowedAfter(prev) {
  if (prev === null) return true;
  if (/[)\]}]/.test(prev)) return false;      // end of an expression -> division
  if (/[\w$]/.test(prev)) return false;       // identifier or number -> division
  return true;                                // operator, comma, brace, etc.
}

function stripJs(src) {
  let out = '';
  let i = 0;
  let prev = null;            // last significant char emitted; regex vs division
  const n = src.length;

  while (i < n) {
    const c = src[i], c2 = src[i + 1];

    if (c === '/' && c2 === '/') {            // line comment
      while (i < n && src[i] !== '\n') i++;
      continue;                                // keep the newline itself
    }
    if (c === '/' && c2 === '*') {            // block comment
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] === '\n') out += '\n';      // preserve line numbering
        i++;
      }
      i += 2;
      continue;
    }
    if (c === '"' || c === "'") {             // string literal
      const q = c; out += c; i++;
      while (i < n) {
        out += src[i];
        if (src[i] === '\\') { i++; if (i < n) out += src[i]; i++; continue; }
        if (src[i] === q) { i++; break; }
        i++;
      }
      prev = q;
      continue;
    }
    if (c === '`') {                          // template literal, may nest ${ }
      out += c; i++;
      let depth = 0;
      while (i < n) {
        if (src[i] === '\\') { out += src[i]; i++; if (i < n) { out += src[i]; i++; } continue; }
        if (src[i] === '$' && src[i + 1] === '{') { depth++; out += '${'; i += 2; continue; }
        if (src[i] === '}' && depth > 0) { depth--; out += '}'; i++; continue; }
        if (src[i] === '`' && depth === 0) { out += '`'; i++; break; }
        out += src[i]; i++;
      }
      prev = '`';
      continue;
    }
    if (c === '/' && regexAllowedAfter(prev)) {   // regex literal
      let j = i + 1, inClass = false, ok = false;
      while (j < n) {
        const d = src[j];
        if (d === '\\') { j += 2; continue; }
        if (d === '\n') break;                    // unterminated: not a regex
        if (d === '[') inClass = true;
        else if (d === ']') inClass = false;
        else if (d === '/' && !inClass) { ok = true; break; }
        j++;
      }
      if (ok) {
        j++;
        while (j < n && /[a-z]/.test(src[j])) j++;   // flags
        out += src.slice(i, j); i = j; prev = '/';
        continue;
      }
    }

    out += c;
    if (!/\s/.test(c)) prev = c;
    i++;
  }

  return tidy(out);
}

function stripCss(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  while (i < n) {
    if (src[i] === '/' && src[i + 1] === '*') {
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] === '\n') out += '\n';
        i++;
      }
      i += 2;
      continue;
    }
    if (src[i] === '"' || src[i] === "'") {      // url('...') etc.
      const q = src[i]; out += src[i]; i++;
      while (i < n) {
        out += src[i];
        if (src[i] === '\\') { i++; if (i < n) out += src[i]; i++; continue; }
        if (src[i] === q) { i++; break; }
        i++;
      }
      continue;
    }
    out += src[i]; i++;
  }
  return out;
}

/**
 * HTML: drop comments, and strip the bodies of script and style elements.
 * Comments are recognised in the SAME pass as the tags — see the header.
 * Newlines inside removed comments are preserved so line numbers still match
 * the original: the in-page error trap reports them, and a report that does not
 * line up with the source is worse than none.
 */
function stripHtml(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  const newlinesOnly = t => t.replace(/[^\n]/g, '');

  while (i < n) {
    if (src.startsWith('<!--', i)) {
      const end = src.indexOf('-->', i + 4);
      const stop = end < 0 ? n : end + 3;
      out += newlinesOnly(src.slice(i, stop));
      i = stop;
      continue;
    }
    const tag = /^<(script|style)\b/i.exec(src.slice(i, i + 8));
    if (tag) {
      const name = tag[1].toLowerCase();
      const openEnd = src.indexOf('>', i);
      if (openEnd < 0) { out += src.slice(i); break; }
      const closeRe = new RegExp('</' + name + '\\s*>', 'i');
      const rest = src.slice(openEnd + 1);
      const m = closeRe.exec(rest);
      const body = m ? rest.slice(0, m.index) : rest;
      const close = m ? m[0] : '';
      out += src.slice(i, openEnd + 1)
           + (name === 'script' ? stripJs(body) : stripCss(body))
           + close;
      i = openEnd + 1 + body.length + close.length;
      continue;
    }
    out += src[i];
    i++;
  }
  return tidy(out);
}

/** Trailing whitespace, and the runs of blank lines the comments left behind. */
function tidy(s) {
  return s.split('\n').map(l => l.replace(/\s+$/, '')).join('\n').replace(/\n{3,}/g, '\n\n');
}

const [, , inPath, outPath] = process.argv;
if (!inPath || !outPath) { console.error('usage: strip-comments.js <in> <out>'); process.exit(2); }
const src = fs.readFileSync(inPath, 'utf8');
const out = inPath.endsWith('.html') ? stripHtml(src) : stripJs(src);
fs.writeFileSync(outPath, out);
const saved = src.length - out.length;
console.log(`strip-comments: ${inPath} ${src.length} -> ${out.length} bytes (-${saved}, ` +
            `${(100 * saved / src.length).toFixed(1)}%)`);
