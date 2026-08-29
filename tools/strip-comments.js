#!/usr/bin/env node

'use strict';
const fs = require('fs');

function regexAllowedAfter(prev) {
  if (prev === null) return true;
  if (/[)\]}]/.test(prev)) return false;
  if (/[\w$]/.test(prev)) return false;
  return true;
}

function stripJs(src) {
  let out = '';
  let i = 0;
  let prev = null;
  const n = src.length;

  while (i < n) {
    const c = src[i], c2 = src[i + 1];

    if (c === '/' && c2 === '/') {
      while (i < n && src[i] !== '\n') i++;
      continue;
    }
    if (c === '/' && c2 === '*') {
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] === '\n') out += '\n';
        i++;
      }
      i += 2;
      continue;
    }
    if (c === '"' || c === "'") {
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
    if (c === '`') {
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
    if (c === '/' && regexAllowedAfter(prev)) {
      let j = i + 1, inClass = false, ok = false;
      while (j < n) {
        const d = src[j];
        if (d === '\\') { j += 2; continue; }
        if (d === '\n') break;
        if (d === '[') inClass = true;
        else if (d === ']') inClass = false;
        else if (d === '/' && !inClass) { ok = true; break; }
        j++;
      }
      if (ok) {
        j++;
        while (j < n && /[a-z]/.test(src[j])) j++;
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
    if (src[i] === '"' || src[i] === "'") {
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
