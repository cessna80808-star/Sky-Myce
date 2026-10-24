import assert from 'node:assert/strict';
import { generateKeyPairSync, webcrypto } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// Exercise the real pure key-import cache with Node's WebCrypto; community release verification depends on it.
const source = (await readFile(new URL('../src/drm.ts', import.meta.url), 'utf8')).replace(/^import .*;\r?\n/gm, '');
const javascript = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const counts = { imports: 0 };
const subtle = new Proxy(webcrypto.subtle, { get(target, name) {
  const value = Reflect.get(target, name, target);
  if (typeof value !== 'function') return value;
  return (...args) => {
    if (name === 'importKey') counts.imports++;
    return value.apply(target, args);
  };
} });
Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { subtle,
  getRandomValues: webcrypto.getRandomValues.bind(webcrypto), randomUUID: webcrypto.randomUUID.bind(webcrypto) } });
const drm = await import(`data:text/javascript;base64,${Buffer.from(javascript).toString('base64')}`);
const pair = () => generateKeyPairSync('ed25519');
const encoded = key => key.export({ type: 'spki', format: 'der' }).toString('base64');
const failedImports = counts.imports;
for (let i = 0; i < 2; i++) await assert.rejects(drm.installationKey('invalid'), /invalid_installation_key/);
assert.equal(counts.imports, failedImports + 2, 'An invalid import never sticks in the cache');
// A flood of distinct public keys cannot create an unbounded isolate cache.
const first = encoded(pair().publicKey);
await drm.installationKey(first);
for (let i = 0; i < 256; i++) await drm.installationKey(encoded(pair().publicKey));
const evicted = counts.imports;
await drm.installationKey(first);
assert.equal(counts.imports, evicted + 1, 'Oldest public key is evicted after the bounded cache fills');
const concurrent = encoded(pair().publicKey), before = counts.imports;
await Promise.all(Array.from({ length: 12 }, () => drm.installationKey(concurrent)));
assert.equal(counts.imports, before + 1, 'Concurrent imports coalesce');
console.log('Key import cache reuse, failure recovery, coalescing and bounded eviction passed');
