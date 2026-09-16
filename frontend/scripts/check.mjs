import assert from 'node:assert/strict';
import {readFile, readdir, access} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const sourceRoot = path.join(root, 'src');
const css = await readFile(path.join(sourceRoot, 'styles.css'), 'utf8');
const design = await readFile(path.join(root, '../DESIGN.md'), 'utf8');
for (const match of design.split('typography:')[0].matchAll(/^  ([\w-]+): "(#[0-9a-f]+)"/gm)) {
  assert.ok(css.includes('--color-' + match[1] + ': ' + match[2]), 'Token drift: ' + match[1]);
}
async function scan(dir) {
  for (const entry of await readdir(dir, {withFileTypes: true})) {
    const name = path.join(dir, entry.name);
    if (entry.isDirectory()) { await scan(name); continue; }
    if (!/\.(jsx|js|mjs)$/.test(entry.name) || entry.name.endsWith('.test.mjs')) continue;
    const text = await readFile(name, 'utf8');
    assert.doesNotMatch(text, /(?:window\.)?(?:alert|confirm|prompt)\s*\(/, name);
    assert.doesNotMatch(text, /<div[^>]*onClick|href=["']#["']/, name);
  }
}
await scan(sourceRoot);
const login = await readFile(path.join(sourceRoot, 'pages/Login.jsx'), 'utf8');
assert.match(login, /<form noValidate/);
assert.match(login, /aria-invalid/);
assert.match(login, /aria-describedby/);
assert.match(css, /prefers-reduced-motion/);
assert.match(css, /forced-colors/);
assert.match(css, /scrollbar-color/);
for (const image of ['afternoon-tea-generated.png', 'lunch-generated.png', 'product-placeholder.svg']) await access(path.join(root, 'public/imgs', image));
console.log('PASS: React source controls, login semantics, image assets, responsive accessibility rules and design tokens.');
