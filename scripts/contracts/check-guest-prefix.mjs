#!/usr/bin/env node
// ORISO-UserService#1697: production code must not decide "is anonymous guest" from the User-ID
// prefix (anon_N, Anonymous-<timestamp>). Every earlier animal-handle rollout broke on such a rule.
// Recognise a guest by stored facts instead: AnonymousSessionRegistration (registration type
// ANONYMOUS or postcode 00000) or the session's stored conversation type.
//
// Usage: node scripts/contracts/check-guest-prefix.mjs [repository root]
// Exit 0 when clean, 1 with one line per finding otherwise. Comments are ignored, so docs may still
// mention the old names. A file that truly must keep a prefix rule goes into
// scripts/contracts/guest-prefix-allowlist.txt with a reason.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const root = process.argv[2] ?? process.cwd();
const scanRoot = join(root, 'src', 'main');
const allowlistPath = join(root, 'scripts', 'contracts', 'guest-prefix-allowlist.txt');
const EXTENSIONS = /\.(java|kt|sql|xml|ya?ml|properties)$/;
const PREFIX = String.raw`(?:anon_|Anonymous-)`;

// Each rule names one way of telling a guest by its name.
const RULES = [
  { name: 'startsWith on a guest prefix', re: new RegExp(String.raw`startsWith\s*\([^;]*["']\^?${PREFIX}`, 'i') },
  { name: 'SQL LIKE on a guest prefix', re: new RegExp(String.raw`\bLIKE\s+["']?\s*'?${PREFIX}`, 'i') },
  { name: 'regex on a guest prefix', re: new RegExp(String.raw`["']\^${PREFIX}|${PREFIX}(?:\\\\d|\\d|\[0-9\]|\.\*|\.\+)`, 'i') },
  { name: 'guest prefix constant', re: new RegExp(String.raw`["']${PREFIX}["']`, 'i') },
  { name: 'number parsed after a guest prefix', re: new RegExp(String.raw`(?:parseInt|parseLong|valueOf)\s*\([^;]*${PREFIX}`, 'i') },
];

function readAllowlist() {
  const allowed = new Map();
  let text = '';
  try {
    text = readFileSync(allowlistPath, 'utf8');
  } catch {
    return allowed;
  }
  for (const [index, raw] of text.split('\n').entries()) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const [path, reason] = line.split('|').map((part) => part?.trim());
    if (!path || !reason) {
      console.error(`${relative(root, allowlistPath)}:${index + 1}: entry needs "<path> | <reason>"`);
      process.exit(1);
    }
    allowed.set(path, reason);
  }
  return allowed;
}

function* files(dir) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) yield* files(path);
    else if (EXTENSIONS.test(entry)) yield path;
  }
}

/** Blanks comments but keeps line numbers and string literals. */
function withoutComments(source, path) {
  if (/\.(ya?ml|properties)$/.test(path)) return source.replace(/^\s*#.*$/gm, '');
  if (path.endsWith('.xml')) return source.replace(/<!--[\s\S]*?-->/g, (c) => c.replace(/[^\n]/g, ' '));
  let out = '';
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    const next = source[i + 1];
    if (c === '"' || c === "'") {
      const quote = c;
      out += c;
      for (i++; i < source.length && source[i] !== quote; i++) {
        if (source[i] === '\\') out += source[i++];
        out += source[i];
      }
      if (i < source.length) out += source[i];
    } else if (c === '/' && next === '/') {
      while (i < source.length && source[i] !== '\n') i++;
      out += '\n';
    } else if (c === '/' && next === '*') {
      for (i += 2; i < source.length && !(source[i] === '*' && source[i + 1] === '/'); i++) {
        out += source[i] === '\n' ? '\n' : ' ';
      }
      i++;
    } else if (c === '-' && next === '-' && path.endsWith('.sql')) {
      while (i < source.length && source[i] !== '\n') i++;
      out += '\n';
    } else {
      out += c;
    }
  }
  return out;
}

const allowlist = readAllowlist();
const findings = [];
for (const file of files(scanRoot)) {
  const path = relative(root, file).split(sep).join('/');
  if (allowlist.has(path)) continue;
  const lines = withoutComments(readFileSync(file, 'utf8'), file).split('\n');
  lines.forEach((line, index) => {
    for (const rule of RULES) {
      if (rule.re.test(line)) findings.push(`${path}:${index + 1}: ${rule.name}: ${line.trim()}`);
    }
  });
}

for (const path of allowlist.keys()) {
  try {
    statSync(join(root, path));
  } catch {
    findings.push(`${relative(root, allowlistPath)}: allowlisted file no longer exists: ${path}`);
  }
}

if (findings.length > 0) {
  console.error('Guest recognised by its User-ID prefix (ORISO-UserService#1697):');
  for (const finding of findings) console.error(`  ${finding}`);
  console.error(
    '\nUse AnonymousSessionRegistration or the stored conversation type instead. Only a file that\n' +
      'truly must keep the rule belongs in scripts/contracts/guest-prefix-allowlist.txt, with a reason.',
  );
  process.exit(1);
}
console.log(`Guest prefix guard: no prefix-based guest detection in src/main (${allowlist.size} allowlisted).`);
