import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { template, unset } from 'lodash-es';
import katex from 'katex';
import { nanoid as nanoid3, customAlphabet as custom3 } from './node_modules/nanoid/index.browser.js';
import { nanoid as nanoid5, customAlphabet as custom5 } from './node_modules/@excalidraw/mermaid-to-excalidraw/node_modules/nanoid/index.browser.js';
import { nanoid as insecure3 } from './node_modules/nanoid/non-secure/index.js';
import { nanoid as insecure5 } from './node_modules/@excalidraw/mermaid-to-excalidraw/node_modules/nanoid/non-secure/index.js';

test('patched Lodash refuses executable template import keys', () => {
  globalThis.__diagramSecurityProbe = false;
  try {
    assert.throws(() => template('Synthetic', { imports: { 'x = (globalThis.__diagramSecurityProbe = true)': undefined } }));
    assert.equal(globalThis.__diagramSecurityProbe, false);
  } finally { delete globalThis.__diagramSecurityProbe; }
});
test('patched Lodash prevents prototype access through array path segments', () => {
  Object.prototype.__diagramSecurityProbe = 'synthetic';
  try {
    unset({}, [['__proto__'], '__diagramSecurityProbe']);
    assert.equal(Object.prototype.__diagramSecurityProbe, 'synthetic');
  } finally { delete Object.prototype.__diagramSecurityProbe; }
});
test('both browser NanoID generations preserve caller API and bounded edge cases', () => {
  for (const create of [nanoid3, nanoid5]) {
    const value = create(); assert.equal(value.length, 21); assert.match(value, /^[A-Za-z0-9_-]+$/);
    assert.equal(create(0), '');
  }
  for (const custom of [custom3, custom5]) assert.equal(custom('abc', 0)(), '');
  for (const insecure of [insecure3, insecure5]) assert.equal(insecure(-1), '');
});
test('patched KaTeX ignores inherited trust while preserving formula rendering', () => {
  Object.prototype.trust = true;
  try {
    const result = katex.renderToString('\\href{https://synthetic.invalid/}{text}', { throwOnError: false });
    assert.doesNotMatch(result, /href=["']https:\/\/synthetic\.invalid/);
  } finally { delete Object.prototype.trust; }
  const normal = katex.renderToString('x^2 + y^2', { throwOnError: true });
  assert.match(normal, /katex/); assert.match(normal, /math/);
});
test('vulnerable Lodash entry points are absent from shipped bundle output', async () => {
  for (const kind of ['mermaid', 'excalidraw']) {
    const metadata = JSON.parse(await readFile(new URL(`maps/${kind}.meta.json`, import.meta.url), 'utf8'));
    const output = Object.entries(metadata.outputs).find(([name]) => name.endsWith('.js'))[1];
    for (const [source, info] of Object.entries(output.inputs)) {
      if (/node_modules\/lodash-es\/(?:template|unset|omit|_baseUnset)\.js$/.test(source)) assert.equal(info.bytesInOutput, 0, source);
    }
  }
});
