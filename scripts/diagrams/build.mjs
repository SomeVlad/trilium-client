import { build } from 'esbuild';
import { readFile, writeFile, mkdir, cp, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
const base = path.dirname(fileURLToPath(import.meta.url));
const output = path.resolve(base, '../../app/src/main/assets/diagrams/vendor');
await mkdir(output, { recursive: true });
const metas = [];
for (const name of ['mermaid', 'excalidraw', 'viewer']) {
  const result = await build({absWorkingDir: base, entryPoints: [name === 'viewer' ? 'viewer.js' : `${name}-entry.js`], bundle: true,
    outfile: path.join(output, `${name}.js`), format: 'iife', target: 'chrome71', minify: true,
    sourcemap: 'external', sourcesContent: true, metafile: true, legalComments: 'eof',
    define: { 'process.env.NODE_ENV': '"production"', 'import.meta.env.DEV': 'false' },
    loader: { '.woff2': 'file', '.wasm': 'file' }, assetNames: '[name]-[hash]'});
  metas.push(result.metafile);
  await mkdir(path.join(base,'maps'),{recursive:true});
  const sourceMap = JSON.parse(await readFile(path.join(output,`${name}.js.map`),'utf8'));
  for (let i = 0; i < sourceMap.sources.length; i++) {
    const original = sourceMap.sources[i];
    const marker = original.lastIndexOf('scripts/diagrams/');
    const source = marker >= 0 ? original.slice(marker + 'scripts/diagrams/'.length) : original;
    if (sourceMap.sourcesContent[i] === null) {
      const packageSource = source.slice(source.lastIndexOf('node_modules/') + 'node_modules/'.length);
      sourceMap.sourcesContent[i] = await readFile(path.join(base,'preferred-sources',packageSource),'utf8');
      sourceMap.sources[i] = '../preferred-sources/' + packageSource;
    } else sourceMap.sources[i] = '../' + source;
  }
  await writeFile(path.join(base,'maps',`${name}.js.map`),JSON.stringify(sourceMap));
  const {unlink} = await import('node:fs/promises'); await unlink(path.join(output,`${name}.js.map`));
  await writeFile(path.join(base, 'maps', `${name}.meta.json`), JSON.stringify(result.metafile, null, 2) + '\n');
}
await cp(path.join(base, 'node_modules/@excalidraw/excalidraw/dist/prod/fonts'), path.join(output, 'fonts'), {recursive: true});
await cp(path.join(base,'UPSTREAM_LICENSES.txt'),path.join(output,'UPSTREAM_LICENSES.txt'));
const lock = JSON.parse(await readFile(path.join(base, 'package-lock.json'), 'utf8'));
const packages = Object.entries(lock.packages).filter(([name, item]) => name && item.resolved)
  .map(([name, item]) => ({path: name, name: name.slice(name.lastIndexOf('node_modules/') + 13), version: item.version,
    url: item.resolved, integrity: item.integrity, dev: item.dev === true, license: item.license || ''}));
const notices = [];
for (const pkg of packages) {
  if (pkg.dev) continue;
  const directory = path.join(base, pkg.path);
  const files = await readdir(directory).catch(() => []);
  const licenses = files.filter(name => /^(licen[sc]e|copying|notice)(\.|$)/i.test(name)).sort();
  notices.push(`\n${'='.repeat(72)}\n${pkg.name} ${pkg.version} (${pkg.license})\n${pkg.url}\n`);
  for (const file of licenses) {
    if (!(await stat(path.join(directory, file))).isFile()) continue;
    notices.push(`${file}\n${await readFile(path.join(directory, file), 'utf8')}\n`);
  }
}
await writeFile(path.join(output, 'THIRD_PARTY_NOTICES.txt'), notices.join('\n'));
const bundledFiles = {};
async function hashFiles(directory) {
  for (const item of (await readdir(directory, {withFileTypes: true})).sort((a,b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)) {
    const target = path.join(directory, item.name);
    if (item.isDirectory()) await hashFiles(target);
    else bundledFiles[path.relative(output, target)] = crypto.createHash('sha256').update(await readFile(target)).digest('hex');
  }
}
await hashFiles(output);
const preferredSources = {};
async function hashPreferred(directory) {
  for(const item of (await readdir(directory,{withFileTypes:true})).sort((a,b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)) {
    const target = path.join(directory,item.name);
    if(item.isDirectory()) await hashPreferred(target);
    else preferredSources[path.relative(base,target)] = crypto.createHash('sha256').update(await readFile(target)).digest('hex');
  }
}
await hashPreferred(path.join(base,'preferred-sources'));
await writeFile(path.join(base, 'sources.lock.json'), JSON.stringify({schemaVersion: 1,
  description: 'Official pinned npm archives. Build: npm ci --ignore-scripts && npm run build (Node 22). No runtime CDN.',
  preferredSources, upstreamSources: JSON.parse(await readFile(path.join(base,'upstream-sources.json'),'utf8')), packages, bundledFiles}, null, 2) + '\n');
console.log('Built',Object.keys(bundledFiles).length,'offline viewer assets');
