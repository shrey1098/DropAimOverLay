#!/usr/bin/env node
/**
 * Remove comments from every source file in the repository.
 *
 * This branch (17jakli) is the commented copy and should NOT be stripped. The
 * tool lives here only so both branches hold the same files; it is run on
 * rollout, which is kept comment-free.
 *
 * Every pass is a left-to-right character scan, never a regex, because the
 * things that look like comments and are not are exactly what a regex gets
 * wrong: "https://…" inside a Kotlin string, a block-comment opener inside a
 * raw string, // inside a Groovy slashy literal, <script> written inside an
 * HTML comment. A stripper that eats one of those does not fail loudly — it
 * produces a file that still looks plausible and no longer works.
 *
 * Safety: the output must be a subsequence of the input once whitespace is
 * ignored, which is a true invariant for something that only deletes. It is NOT
 * sufficient on its own — deleting too much is still deleting, and that is
 * exactly the bug the first run shipped (template literals were unhandled, so
 * `camera '${id}' …` desynced the scanner). Only the compilers caught it. Run
 * tools/kotlin-typecheck.sh and npm test after any strip.
 *
 * Usage:  node tools/strip-repo-comments.js [--dry]
 */
'use strict';
const fs = require('fs');
const path = require('path');

const REPO = path.join(__dirname, '..');
const DRY = process.argv.includes('--dry');

// Vendored dependencies are not ours to edit, documentation is not comments,
// and data formats have none.
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

// ── C-family scanner: Kotlin, Java, JavaScript, Groovy ───────────────────────
/**
 * @param opts.slashy   Groovy /.../ literals and JS regex literals
 * @param opts.tripleQ  Kotlin/Groovy triple-quoted strings
 */
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

    if (c === '/' && c2 === '/') {                       // line comment
      while (i < n && src[i] !== '\n') i++;
      continue;
    }
    if (c === '/' && c2 === '*') {                       // block comment / KDoc
      i += 2;
      while (i < n && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] === '\n') out += '\n';                // keep line numbering
        i++;
      }
      i += 2;
      continue;
    }
    // Triple-quoted first: it contains " and would otherwise end early. Every
    // branch here MUST advance i — an earlier version could reach `continue`
    // without moving and hung on the first raw string it met.
    if (opts.tripleQ && (src.startsWith('"""', i) || src.startsWith("'''", i))) {
      const q = src.substr(i, 3);
      const end = src.indexOf(q, i + 3);
      const stop = end < 0 ? n : end + 3;
      out += src.slice(i, stop);
      i = stop;
      prev = '"';
      continue;
    }
    // Template literal. Omitting this is what broke the first run: inside
    // `camera '${id}' is not valid` the scanner saw a lone apostrophe, took it
    // for a string opener, and swallowed everything to the next quote. The
    // result was still a subsequence of the source, so only the compilers
    // caught it.
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
    if (c === '"' || c === "'") {                        // string / char literal
      const q = c; out += c; i++;
      while (i < n) {
        out += src[i];
        if (src[i] === '\\') { i++; if (i < n) out += src[i]; i++; continue; }
        if (src[i] === q) { i++; break; }
        if (src[i] === '\n') { i++; break; }             // unterminated: bail
        i++;
      }
      prev = q;
      continue;
    }
    if (opts.slashy && c === '/' && regexAllowed()) {    // /regex/ or Groovy /slashy/
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

// ── hash-comment scanner: .pro, .sh, .ps1, .properties, .gitignore ───────────
function stripHash(src, opts = {}) {
  return src.split('\n').map(line => {
    let inS = null;
    for (let i = 0; i < line.length; i++) {
      const c = line[i];
      if (inS) { if (c === '\\') i++; else if (c === inS) inS = null; continue; }
      if (c === '"' || c === "'") { inS = c; continue; }
      if (c === '#') {
        // A shebang is not a comment, it is how the file runs.
        if (i === 0 && line[1] === '!') return line;
        return line.slice(0, i).replace(/\s+$/, '');
      }
    }
    return line;
  }).join('\n');
}

// ── XML ──────────────────────────────────────────────────────────────────────
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

// ── HTML: comments, plus the script and style bodies ─────────────────────────
// Comments are recognised in the SAME pass as the tags. Protecting script
// bodies with a regex first matched the <script> written INSIDE an HTML
// comment, which left that comment unterminated and deleted the error trap and
// the layout container with it.
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

// ── dispatch ─────────────────────────────────────────────────────────────────
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

/**
 * Stripping only ever DELETES, so the result must be a subsequence of the
 * source once whitespace is ignored. That is a true invariant with no false
 * positives — unlike counting braces, which a comment containing `rk4Drop(…)`
 * legitimately changes. It catches a scanner that inserted, reordered or
 * duplicated; the compilers catch what it cannot.
 */
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
  // A file that is more than 70% comment is either documentation or a sign the
  // scanner ran away. Worth a look either way.
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
