import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { build } from 'esbuild';
const bundled = await build({ entryPoints: ['src/community-release.ts'], bundle: true, format: 'esm', platform: 'neutral', write: false });
const { handleCommunityRelease, verifyCommunityManifest } = await import(`data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text).toString('base64')}`);
const key = generateKeyPairSync('ed25519');
const base = { product: 'sky-myce', protocol: 4, installation: 'fabric', minecraft: '26.1.2', version: '2.5.1', artifact: 'releases/Sky-Myce-2.5.1.jar', size: 4,
  sha256: 'a'.repeat(64), issuedAt: Date.now(), expiresAt: Date.now() + 86400000, notes: 'Fixture release' };
const signed = manifest => { const payload = Buffer.from(JSON.stringify(manifest)); return { kid: 'fixture', payload: payload.toString('base64url'), signature: sign(null, payload, key.privateKey).toString('base64url') }; };
let envelope = signed(base), fetched = [];
const env = { DRM_PUBLIC_KEYS: JSON.stringify({ fixture: key.publicKey.export({ type: 'spki', format: 'der' }).toString('base64') }), PRIVATE_ARTIFACTS: { get: async path => {
  fetched.push(path);
  if (path === 'releases/community-current.json') return { size: 2048, text: async () => JSON.stringify(envelope) };
  if (path === base.artifact) return { size: 4, body: new Uint8Array([80,75,3,4]) };
  throw Error('Unexpected private object read');
} } };
assert.deepEqual(await verifyCommunityManifest(envelope, env), base);
assert.equal((await handleCommunityRelease(new Request('https://example.test/community/release'), env)).status, 200);
assert.deepEqual(fetched, ['releases/community-current.json']);
assert.equal((await handleCommunityRelease(new Request('https://example.test/community/artifact?version=2.5.1'), env)).status, 200);
assert.equal((await handleCommunityRelease(new Request('https://example.test/community/artifact?version=2.4.0'), env)).status, 409);
for (const change of [{ protocol: 3 }, { installation: 'loader' }, { minimumLoaderVersion: '1.0.14' }, { artifact: '../secret' }, { size: 128 * 1024 * 1024 + 1 }, { expiresAt: 1 }, { minecraft: '1.21' }]) {
  envelope = signed({ ...base, ...change }); await assert.rejects(verifyCommunityManifest(envelope, env));
  assert.equal((await handleCommunityRelease(new Request('https://example.test/community/artifact?version=2.5.1'), env)).status, 503);
}
envelope = signed(base); envelope.payload = Buffer.from(JSON.stringify({ ...base, size: 1024 })).toString('base64url');
await assert.rejects(verifyCommunityManifest(envelope, env));
assert.equal((await handleCommunityRelease(new Request('https://example.test/community/release', { method: 'POST' }), env)).status, 405);
envelope = signed(base); fetched = [];
const cached = new Map(), pending = [], previousCaches = globalThis.caches;
globalThis.caches = { default: {
  match: async key => cached.get(key)?.clone(),
  put: async (key, response) => { await response.clone().arrayBuffer(); cached.set(key, response); },
} };
const ctx = { waitUntil: promise => pending.push(promise) };
const cachedRequest = async path => {
  const response = await handleCommunityRelease(new Request(`https://example.test${path}`), env, ctx);
  await Promise.all(pending.splice(0)); return response;
};
try {
  assert.equal((await cachedRequest('/community/release?random=first')).status, 200);
  assert.equal((await cachedRequest('/community/release?random=second')).status, 200);
  assert.deepEqual(fetched, ['releases/community-current.json'], 'Query strings cannot bypass the metadata cache');
  assert.equal((await cachedRequest('/community/artifact?version=2.5.1&random=first')).status, 200);
  const hit = await cachedRequest('/community/artifact?version=2.5.1&random=second');
  assert.equal(hit.status, 200); assert.deepEqual([...new Uint8Array(await hit.arrayBuffer())], [80, 75, 3, 4]);
  assert.deepEqual(fetched, ['releases/community-current.json', base.artifact], 'Repeated downloads do not read private R2 again');
  assert.equal((await cachedRequest('/community/artifact?version=2.4.0')).status, 409, 'Caching cannot expose an unselected release');
  const pin = env.DRM_PUBLIC_KEYS; env.DRM_PUBLIC_KEYS = '{}';
  assert.equal((await cachedRequest('/community/artifact?version=2.5.1')).status, 503, 'Cached metadata cannot bypass the current signature policy');
  env.DRM_PUBLIC_KEYS = pin;
  const selectedKey = [...cached.keys()].find(key => key.includes('/selection'));
  cached.set(selectedKey, Response.json(signed({ ...base, expiresAt: Date.now() - 1 })));
  assert.equal((await cachedRequest('/community/artifact?version=2.5.1')).status, 503, 'An expired signature cannot expose a cached artifact');
} finally { if (previousCaches === undefined) delete globalThis.caches; else globalThis.caches = previousCaches; }
console.log('Community release checks passed: signature policy, selected artifact only, canonical metadata/artifact cache, private bucket isolation and expired/loader/traversal/tamper rejection.');
