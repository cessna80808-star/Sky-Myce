import { generateKeyPairSync, createPrivateKey, createPublicKey, createHash, sign, verify } from "node:crypto";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve, dirname, relative, isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { inflateRawSync } from "node:zlib";

const repository = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const args = process.argv.slice(2);
const fail = message => { throw new Error(message); };
const version = value => /^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(value);
const encode = value => Buffer.from(value).toString("base64url");
function envelope(manifest, key, kid) {
  const bytes = Buffer.from(JSON.stringify(manifest));
  const signature = sign(null, bytes, key);
  assert.ok(verify(null, bytes, createPublicKey(key), signature));
  return { payload: encode(bytes), signature: encode(signature), kid };
}
function fabricMetadata(archive) {
  // Read the ZIP central directory, avoiding matches inside arbitrary entry data.
  let end = archive.length - 22;
  while (end >= Math.max(0, archive.length - 65557) && archive.readUInt32LE(end) !== 0x06054b50) end--;
  if (end < 0 || archive.readUInt32LE(end) !== 0x06054b50) fail("Invalid JAR directory");
  let offset = archive.readUInt32LE(end + 16);
  for (let i = 0; i < archive.readUInt16LE(end + 10); i++) {
    if (archive.readUInt32LE(offset) !== 0x02014b50) fail("Invalid JAR entry");
    const length = archive.readUInt16LE(offset + 28), name = archive.subarray(offset + 46, offset + 46 + length).toString("utf8");
    if (name === "fabric.mod.json") {
      if (archive.readUInt16LE(offset + 8) & 1 || archive.readUInt32LE(offset + 24) > 65536) fail("Invalid mod metadata");
      const method = archive.readUInt16LE(offset + 10), size = archive.readUInt32LE(offset + 20), local = archive.readUInt32LE(offset + 42);
      if (archive.readUInt32LE(local) !== 0x04034b50 || size > 65536) fail("Invalid metadata entry");
      const start = local + 30 + archive.readUInt16LE(local + 26) + archive.readUInt16LE(local + 28), bytes = archive.subarray(start, start + size);
      return JSON.parse((method === 0 ? bytes : method === 8 ? inflateRawSync(bytes, { maxOutputLength: 65536 }) : fail("Unsupported ZIP method")).toString("utf8"));
    }
    offset += 46 + length + archive.readUInt16LE(offset + 30) + archive.readUInt16LE(offset + 32);
  }
  fail("Normal Fabric metadata is required");
}
try {
  if (args[0] === "keygen") {
    if (args.length !== 3 || !/^[A-Za-z0-9_-]{1,48}$/.test(args[2])) fail("Usage: node scripts/release.mjs keygen <directory-outside-repository> <key-id>");
    const directory = resolve(args[1]), rel = relative(repository, directory);
    if (!rel || (!rel.startsWith("..") && !isAbsolute(rel))) fail("Keep signing private keys outside the repository.");
    const pair = generateKeyPairSync("ed25519"), kid = args[2];
    await mkdir(directory, { recursive: true });
    await writeFile(resolve(directory, `${kid}.private.pem`), pair.privateKey.export({ type: "pkcs8", format: "pem" }), { flag: "wx", mode: 0o600 });
    await writeFile(resolve(directory, `${kid}.public.json`), JSON.stringify({ [kid]: pair.publicKey.export({ type: "spki", format: "der" }).toString("base64") }) + "\n", { flag: "wx" });
    console.log("Sky-Myce signing key files created. Restrict the private file to the release operator; never publish it.");
  } else if (args[0] === "sign-community") {
    const [, artifactPath, keyPath, kid, minecraft, modVersion, notesPath, output] = args;
    if (args.length !== 8 || !/^[A-Za-z0-9_-]{1,48}$/.test(kid) || !/^\d{1,5}\.\d{1,5}(?:\.\d{1,5})?$/.test(minecraft) || !/^\d{1,5}\.\d{1,5}\.\d{1,5}(?:-beta-\d{1,5})?$/.test(modVersion)) fail("Invalid community signing arguments");
    const key = createPrivateKey(await readFile(keyPath)); if (key.asymmetricKeyType !== "ed25519") fail("Ed25519 required");
    const artifact = await readFile(artifactPath); if (artifact.length < 4 || artifact.length > 128 * 1024 * 1024) fail("Invalid artifact size");
    const metadata = fabricMetadata(artifact);
    if (metadata.id !== "skymyce" || metadata.version !== modVersion || Object.hasOwn(metadata.depends ?? {}, "skymyce-loader")) fail("A matching standalone Fabric mod is required");
    const notes = notesPath === "-" ? "" : await readFile(notesPath, "utf8"); if (notes.length > 8000) fail("Release notes too long");
    const now = Date.now(), manifest = { product: "sky-myce", protocol: 4, installation: "fabric", minecraft, version: modVersion,
      artifact: `releases/Sky-Myce-${modVersion}.jar`, size: artifact.length, sha256: createHash("sha256").update(artifact).digest("hex"),
      issuedAt: now, expiresAt: now + 7 * 86400000, notes };
    await writeFile(output, JSON.stringify(envelope(manifest, key, kid)) + "\n", { flag: "wx" });
    console.log(`Signed normal Fabric manifest written. Upload ${manifest.artifact} first, then releases/community-current.json. No loader or license is required.`);
  } else if (args[0] === "sign" || args[0] === "sign-loader") {
    const loader = args[0] === "sign-loader";
    const [, artifactPath, keyPath, kid, minecraft, modVersion] = args;
    const loaderVersion = loader ? "0.0.0" : args[6], output = args[loader ? 6 : 7];
    if (args.length !== (loader ? 7 : 8) || !/^[A-Za-z0-9_-]{1,48}$/.test(kid) || !/^\d{1,5}\.\d{1,5}(?:\.\d{1,5})?$/.test(minecraft) || !version(modVersion) || !version(loaderVersion)) {
      fail("Usage: node scripts/release.mjs sign <jar> <private.pem> <key-id> <minecraft> <mod-version> <minimum-loader> <manifest.json>");
    }
    const key = createPrivateKey(await readFile(keyPath));
    if (key.asymmetricKeyType !== "ed25519") fail("An Ed25519 signing key is required.");
    const artifact = await readFile(artifactPath), now = Date.now();
    if (artifact.length < 4 || artifact[0] !== 0x50 || artifact[1] !== 0x4b) fail("The artifact must be a built JAR.");
    if (loader && artifact.length > 64 * 1024 * 1024) fail("Loader exceeds the update limit.");
    const manifest = loader ? { product: "sky-myce-loader", protocol: 1, minecraft, version: modVersion,
      artifact: `loaders/Sky-Myce-Loader-${modVersion}.jar`, size: artifact.length, sha256: createHash("sha256").update(artifact).digest("hex"), issuedAt: now, expiresAt: now + 7 * 86400000 } :
      { product: "sky-myce", protocol: 3, minecraft, version: modVersion, minimumLoaderVersion: loaderVersion,
      artifact: `releases/Sky-Myce-${modVersion}.jar`, sha256: createHash("sha256").update(artifact).digest("hex"), issuedAt: now, expiresAt: now + 7 * 86400000 };
    await writeFile(output, JSON.stringify(envelope(manifest, key, kid)) + "\n", { flag: "wx" });
    console.log(`Signed manifest written. Upload the JAR privately as ${manifest.artifact}; publish this manifest only after the artifact.`);
  } else if (args[0] === "check") {
    const key = generateKeyPairSync("ed25519").privateKey, signed = envelope({ product: "sky-myce", sha256: "a".repeat(64) }, key, "test");
    const bytes = Buffer.from(signed.payload, "base64url"), signature = Buffer.from(signed.signature, "base64url");
    assert.ok(verify(null, bytes, createPublicKey(key), signature)); bytes[0] ^= 1;
    assert.equal(verify(null, bytes, createPublicKey(key), signature), false);
    console.log("Sky-Myce release signing check passed.");
  } else fail("Commands: keygen, sign-community, sign (legacy), sign-loader (legacy), check.");
} catch (error) {
  // Crypto/fs exceptions can contain local private-key paths. Never print key material or raw runtime errors.
  console.error(error instanceof assert.AssertionError ? "Release signing self-check failed." : "Sky-Myce release command failed. Check arguments, versions, file permissions, and existing output files.");
  process.exitCode = 1;
}
