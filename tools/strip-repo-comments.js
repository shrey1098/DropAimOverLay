#!/usr/bin/env node

'use strict';
const fs = require('fs');
const path = require('path');

const REPO = path.join(__dirname, '..');
const DRY = process.argv.includes('--dry');

const SKIP_DIRS = new Set(['.git', 'node_modules', 'build', '.gradle', '.idea', 'data']);
const SKIP_FILES = new Set(['package-lock.json']);

function walk(dir, out = []) {
  for (const name of fs.readdirSync(dir)) {
    if (SKIP_DIRS.has(name)) continue;
    const p = path.join(dir, name);
    const st = fs.statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (!SKIP_FILES.has(name)) out.push(p);
  }
  return out;
}

function stripCLike(src, opts = {}) {
  let out = '';
  let i = 0, prev = null;
  const n = src.length;

  const regexAllowed = () => {
    if (prev === null) return true;
    if (/[)\]}]/.test(prev)) return false;
    if (/[\w$]/.test(prev)) return false;
    return true;
  };

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

    if (opts.tripleQ && (src.startsWith('"""', i) || src.startsWith("'''", i))) {
      const q = src.substr(i, 3);
      const end = src.indexOf(q, i + 3);
      const stop = end < 0 ? n : end + 3;
      out += src.slice(i, stop);
      i = stop;
      prev = '"';
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
    if (c === '"' || c === "'") {
      const q = c; out += c; i++;
      while (i < n) {
        out += src[i];
        if (src[i] === '\\') { i++; if (i < n) out += src[i]; i++; continue; }
        if (src[i] === q) { i++; break; }
        if (src[i] === '\n') { i++; break; }
        i++;
      }
      prev = q;
      continue;
    }
    if (opts.slashy && c === '/' && regexAllowed()) {
      let j = i + 1, cls = false, ok = false;
      while (j < n) {
        const d = src[j];
        if (d === '\\') { j += 2; continue; }
        if (d === '\n') break;
        if (d === '[') cls = true;
        else if (d === ']') cls = false;
        else if (d === '/' && !cls) { ok = true; break; }
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
  return out;
}

function stripHash(src, opts = {}) {
  return src.split('\n').map(line => {
    let inS = null;
    for (let i = 0; i < line.length; i++) {
      const c = line[i];
      if (inS) { if (c === '\\') i++; else if (c === inS) inS = null; continue; }
      if (c === '"' || c === "'") { inS = c; continue; }

      if (c === '#') {

        if (i === 0 && line[1] === '!') return line;
        return line.slice(0, i).replace(/\s+$/, '');
      }
    }
    return line;
  }).join('\n');
}

function stripXml(src) {
  let out = '', i = 0;
  while (i < src.length) {
    if (src.startsWith('<!--', i)) {
      const end = src.indexOf('-->', i + 4);
      const stop = end < 0 ? src.length : end + 3;
      out += src.slice(i, stop).replace(/[^\n]/g, '');
      i = stop;
      continue;
    }
    out += src[i]; i++;
  }
  return out;
}

function stripHtml(src) {
  let out = '', i = 0;
  const n = src.length;
  while (i < n) {
    if (src.startsWith('<!--', i)) {
      const end = src.indexOf('-->', i + 4);
      const stop = end < 0 ? n : end + 3;
      out += src.slice(i, stop).replace(/[^\n]/g, '');
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
           + (name === 'script' ? stripCLike(body, { slashy: true }) : stripCLike(body))
           + close;
      i = openEnd + 1 + body.length + close.length;
      continue;
    }
    out += src[i]; i++;
  }
  return out;
}

function stripperFor(file) {
  const base = path.basename(file);
  if (/\.(kt|java)$/.test(file))  return s => stripCLike(s, { tripleQ: true });
  if (/\.gradle$/.test(file))     return s => stripCLike(s, { tripleQ: true, slashy: true });
  if (/\.js$/.test(file))         return s => stripCLike(s, { slashy: true });
  if (/\.html?$/.test(file))      return stripHtml;
  if (/\.css$/.test(file))        return s => stripCLike(s);
  if (/\.xml$/.test(file))        return stripXml;
  if (/\.(pro|sh|ps1|properties)$/.test(file) || base === '.gitignore') return stripHash;
  return null;
}

function isSubsequence(out, src) {
  const a = out.replace(/\s+/g, '');
  const b = src.replace(/\s+/g, '');
  let i = 0;
  for (let j = 0; j < b.length && i < a.length; j++) if (a[i] === b[j]) i++;
  return i === a.length;
}

function tidy(s) {
  return s.split('\n').map(l => l.replace(/\s+$/, '')).join('\n').replace(/\n{3,}/g, '\n\n');
}

let changed = 0, skipped = 0, bytes = 0;
const problems = [];
for (const file of walk(REPO)) {
  const fn = stripperFor(file);
  if (!fn) { skipped++; continue; }
  const src = fs.readFileSync(file, 'utf8');
  let out;
  try { out = tidy(fn(src)); }
  catch (e) { problems.push(`${file}: scanner threw ${e.message}`); continue; }
  if (out === tidy(src)) continue;

  if (!isSubsequence(out, src)) {
    problems.push(`${file}: output is not a subsequence of the source — NOT written`);
    continue;
  }
  const saved = src.length - out.length;

  if (saved / src.length > 0.7)
    problems.push(`${file}: ${(100 * saved / src.length).toFixed(0)}% removed — check this one by eye`);

  bytes += saved;
  changed++;
  console.log(`  ${path.relative(REPO, file).padEnd(56)} -${saved}`);
  if (!DRY) fs.writeFileSync(file, out);
}

console.log(`\n${DRY ? '[dry run] ' : ''}${changed} file(s), ${bytes} bytes of comments, ${skipped} skipped.`);
if (problems.length) {
  console.error('\nPROBLEMS:');
  problems.forEach(p => console.error('  ' + p));
  process.exit(1);
}
