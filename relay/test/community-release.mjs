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
console.log('Community release checks passed: authenticated public metadata, selected artifact only, private bucket isolation, expired/loader/traversal/tamper rejection.');
