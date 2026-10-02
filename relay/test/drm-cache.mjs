import assert from 'node:assert/strict';
import { generateKeyPairSync, webcrypto } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import ts from 'typescript';

// Exercise the real pure DRM functions with Node's WebCrypto. The complete Worker
// suite separately covers authentication, durable revocation and replay checks.
const source = (await readFile(new URL('../src/drm.ts', import.meta.url), 'utf8')).replace(/^import .*;\r?\n/gm, '');
const javascript = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const counts = { imports: 0, signs: 0, verifies: 0 };
const subtle = new Proxy(webcrypto.subtle, { get(target, name) {
  const value = Reflect.get(target, name, target);
  if (typeof value !== 'function') return value;
  return (...args) => {
    if (name === 'importKey') counts.imports++;
    if (name === 'sign') counts.signs++;
    if (name === 'verify') counts.verifies++;
    return value.apply(target, args);
  };
} });
Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { subtle,
  getRandomValues: webcrypto.getRandomValues.bind(webcrypto), randomUUID: webcrypto.randomUUID.bind(webcrypto) } });
const drm = await import(`data:text/javascript;base64,${Buffer.from(javascript).toString('base64')}`);
const pair = () => generateKeyPairSync('ed25519');
const encoded = key => key.export({ type: 'spki', format: 'der' }).toString('base64');
const configuration = (keys, id = 'test') => ({ DRM_KEY_ID: id, DRM_PUBLIC_KEYS: JSON.stringify({ [id]: encoded(keys.publicKey) }),
  DRM_SIGNING_PRIVATE_KEY: keys.privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64') });
const claims = () => ({ product: 'sky-myce', protocol: 3, accountId: 'account', discordId: '44444444444444444',
  uuid: 'a'.repeat(32), installationId: 'installation', deviceHash: 'b'.repeat(64), keyFingerprint: 'c'.repeat(64),
  version: '2.4.12', loaderVersion: '1.0.10', minecraft: '26.1.2', iat: Date.now(), exp: Date.now() + 300_000,
  offlineUntil: Date.now() + 900_000, generation: 1, jti: 'lease', challengeId: '11111111-1111-4111-8111-111111111111',
  clientNonce: '22222222-2222-4222-8222-222222222222', features: [] });
const env = configuration(pair());
async function cycle() {
  await drm.ensureSigningConfigured(env);
  const token = await drm.createLease(env, claims());
  await drm.validateLease(env, token);
}
if (process.argv.includes('--benchmark')) {
  await cycle();
  const results = [];
  for (let sample = 0; sample < 5; sample++) {
    const before = { ...counts }, start = performance.now();
    for (let i = 0; i < 300; i++) await cycle();
    results.push({ milliseconds: performance.now() - start, imports: counts.imports - before.imports,
      signs: counts.signs - before.signs, verifies: counts.verifies - before.verifies });
  }
  results.sort((a, b) => a.milliseconds - b.milliseconds);
  console.log(JSON.stringify({ benchmark: '300 warm configuration-check + lease-sign + lease-verify cycles', runtime: process.version,
    median: results[2], samples: results }, null, 2));
} else {
  await cycle();
  const imported = counts.imports;
  await Promise.all(Array.from({ length: 12 }, cycle));
  assert.equal(counts.imports, imported, 'Warm, concurrent authorization must reuse immutable imported keys');
  const token = await drm.createLease(env, claims());
  const parts = token.split('.');
  parts[1] = Buffer.from(JSON.stringify({ ...claims(), generation: 999 })).toString('base64url');
  await assert.rejects(drm.validateLease(env, parts.join('.')), /invalid_lease/, 'Import reuse never reuses message verification');
  const rotated = configuration(pair());
  await drm.ensureSigningConfigured(rotated);
  await assert.rejects(drm.validateLease(rotated, token), /invalid_lease/);
  const mismatched = { ...env, DRM_PUBLIC_KEYS: rotated.DRM_PUBLIC_KEYS }, failedSigns = counts.signs;
  for (let i = 0; i < 2; i++) await assert.rejects(drm.ensureSigningConfigured(mismatched), /authorization_unavailable/);
  assert.equal(counts.signs, failedSigns + 2, 'A failed configuration is evicted and genuinely retried');
  await drm.ensureSigningConfigured(env);
  const mutable = configuration(pair()), original = { ...mutable }, checking = drm.ensureSigningConfigured(mutable);
  mutable.DRM_PUBLIC_KEYS = rotated.DRM_PUBLIC_KEYS;
  await checking;
  const checkedSigns = counts.signs;
  await drm.ensureSigningConfigured(original);
  assert.equal(counts.signs, checkedSigns, 'The cached check belongs to the exact bytes captured before await');
  await assert.rejects(drm.ensureSigningConfigured(mutable), /authorization_unavailable/);
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
  console.log('DRM setup reuse, rotation, forged tokens, failure recovery and bounded eviction passed');
}
