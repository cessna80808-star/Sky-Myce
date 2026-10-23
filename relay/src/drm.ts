import type { AccountStore, AccountsConfig } from "./accounts";
import { versionAllowed, versionDenial } from "./licensing";

const encoder = new TextEncoder();
// Immutable crypto setup only: signatures and mutable account authority are always checked afresh.
const publicKeys = new Map<string, Promise<CryptoKey>>();
const privateKeys = new Map<string, Promise<CryptoKey>>();
const signingConfigurations = new Map<string, Promise<void>>();
function setup<T>(cache: Map<string, Promise<T>>, identity: string, maximum: number, load: () => Promise<T>): Promise<T> {
  const existing = cache.get(identity);
  if (existing) { cache.delete(identity); cache.set(identity, existing); return existing; }
  const pending = load();
  cache.set(identity, pending);
  if (cache.size > maximum) cache.delete(cache.keys().next().value!);
  void pending.catch(() => { if (cache.get(identity) === pending) cache.delete(identity); });
  return pending;
}
export type DrmConfig = { DRM_SIGNING_PRIVATE_KEY?: string; DRM_PUBLIC_KEYS?: string; DRM_KEY_ID?: string;
  LEASE_TTL_SECONDS?: string; OFFLINE_GRACE_SECONDS?: string; PRIVATE_ARTIFACTS?: R2Bucket; ARTIFACT_MANIFEST_KEY?: string; LOADER_MANIFEST_KEY?: string };
export type AuthenticationPurpose = "activate" | "renew" | "bootstrap-activate" | "bootstrap-renew";
export type ClientPlatform = "linux" | "windows" | "macos" | "other";
export type InstallationInput = { purpose: AuthenticationPurpose | "manifest" | "artifact"; installationId: string;
  publicKey: string; deviceHash: string; hardwareHash?: string; minecraftVersion: string; loaderVersion: string; modVersion: string; clientNonce: string;
  platform?: ClientPlatform; launchSessionId?: string; startSession?: boolean };
export type LeaseClaims = { product: "sky-myce"; protocol: 3; accountId: string; discordId: string; uuid: string;
  installationId: string; deviceHash: string; hardwareHash?: string; keyFingerprint: string; version: string; minecraft: string;
  iat: number; exp: number; offlineUntil: number; generation: number; jti: string; challengeId: string; clientNonce: string; features: string[];
  platform?: ClientPlatform; launchSessionId?: string; loaderVersion?: string };
export type SignedManifest = { payload: string; signature: string; kid: string };
export type ReleaseManifest = { product: string; protocol: number; minecraft: string; version: string;
  minimumLoaderVersion: string; artifact: string; sha256: string; issuedAt: number; expiresAt: number };
export const bootstrapPurpose = (purpose: string) => purpose === "bootstrap-activate" || purpose === "bootstrap-renew";
const launchId = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const platformValid = (platform: unknown) => typeof platform === "string" && ["linux", "windows", "macos", "other"].includes(platform);
export function validateLaunchInput(input: InstallationInput): void {
  if (input.hardwareHash !== undefined && (typeof input.hardwareHash !== "string" || !/^[a-f0-9]{64}$/.test(input.hardwareHash))) deny("invalid_request");
  const fields = [input.platform, input.launchSessionId, input.startSession];
  if (fields.every(field => field === undefined)) return; // Existing installations retain their identity across loader upgrades.
  if (!platformValid(input.platform) || typeof input.launchSessionId !== "string" || !launchId.test(input.launchSessionId) ||
    typeof input.startSession !== "boolean" || (input.startSession && !["activate", "renew"].includes(input.purpose))) deny("invalid_request");
}
export function installationVersionDenial(input: InstallationInput, env: AccountsConfig): string | null {
  // Outdated clients may fetch only the bootstrap. They must renew normally for any protected feature.
  if (!bootstrapPurpose(input.purpose)) return versionDenial(input, env);
  return input.minecraftVersion === (env.MINECRAFT_VERSION ?? "26.1.2") &&
    /^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(input.loaderVersion) && /^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(input.modVersion) ? null : "version_denied";
}

export class AuthorizationError extends Error { constructor(public readonly code: string) { super(code); } }
export function deny(code: string): never { throw new AuthorizationError(code); }
export const encode64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
export function decode64(value: unknown, max = 8192): Uint8Array<ArrayBuffer> {
  if (typeof value !== "string" || !value || value.length > max || !/^[A-Za-z0-9+/_-]+={0,2}$/.test(value)) deny("invalid_encoding");
  try { return Uint8Array.from(atob(value.replaceAll("-", "+").replaceAll("_", "/")), c => c.charCodeAt(0)); }
  catch { return deny("invalid_encoding"); }
}
export async function sha256(value: string | Uint8Array<ArrayBuffer>): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", typeof value === "string" ? encoder.encode(value) : value))]
    .map(byte => byte.toString(16).padStart(2, "0")).join("");
}
export const randomToken = () => encode64(crypto.getRandomValues(new Uint8Array(32)));
export function installationMessage(input: InstallationInput, nonce: string): string {
  const fields = `${input.purpose}\n${nonce}\n${input.installationId}\n${input.deviceHash}\n${input.minecraftVersion}\n${input.loaderVersion}\n${input.modVersion}\n${input.clientNonce}`;
  const launch = input.platform === undefined ? "" : `\n${input.platform}\n${input.launchSessionId}\n${input.startSession}`;
  return input.hardwareHash !== undefined ? `Sky-Myce installation v3\n${fields}\n${input.hardwareHash}${launch}` :
    `Sky-Myce installation v${input.platform === undefined ? 1 : 2}\n${fields}${launch}`;
}
export async function installationKey(publicKey: string): Promise<CryptoKey> {
  try { return await setup(publicKeys, publicKey, 256, () => crypto.subtle.importKey("spki", decode64(publicKey, 256), "Ed25519", false, ["verify"])); }
  catch { return deny("invalid_installation_key"); }
}
export async function verifyInstallation(publicKey: string, signature: unknown, message: string): Promise<boolean> {
  try { return await crypto.subtle.verify("Ed25519", await installationKey(publicKey), decode64(signature, 128), encoder.encode(message)); }
  catch { return false; }
}
async function signingKey(env: DrmConfig): Promise<CryptoKey> {
  if (!env.DRM_SIGNING_PRIVATE_KEY || !env.DRM_KEY_ID) deny("authorization_unavailable");
  const encoded = env.DRM_SIGNING_PRIVATE_KEY;
  try { return await setup(privateKeys, encoded, 4, () => crypto.subtle.importKey("pkcs8", decode64(encoded, 512), "Ed25519", false, ["sign"])); }
  catch { return deny("authorization_unavailable"); }
}
async function verificationKey(env: DrmConfig, kid: unknown): Promise<CryptoKey> {
  if (typeof kid !== "string" || !/^[A-Za-z0-9_-]{1,48}$/.test(kid)) deny("invalid_signature");
  try {
    const keys = JSON.parse(env.DRM_PUBLIC_KEYS ?? "{}");
    if (!Object.hasOwn(keys, kid)) deny("invalid_signature");
    return await installationKey(keys[kid]);
  } catch { return deny("invalid_signature"); }
}
export async function ensureSigningConfigured(env: DrmConfig): Promise<void> {
  const config = { DRM_SIGNING_PRIVATE_KEY: env.DRM_SIGNING_PRIVATE_KEY, DRM_PUBLIC_KEYS: env.DRM_PUBLIC_KEYS, DRM_KEY_ID: env.DRM_KEY_ID };
  const identity = JSON.stringify([config.DRM_SIGNING_PRIVATE_KEY, config.DRM_PUBLIC_KEYS, config.DRM_KEY_ID]);
  return setup(signingConfigurations, identity, 4, async () => {
    const key = await signingKey(config), proof = encoder.encode("Sky-Myce signing configuration");
    if (!await crypto.subtle.verify("Ed25519", await verificationKey(config, config.DRM_KEY_ID), await crypto.subtle.sign("Ed25519", key, proof), proof)) deny("authorization_unavailable");
  });
}
export async function createLease(env: DrmConfig, claims: LeaseClaims): Promise<string> {
  const header = encode64(encoder.encode(JSON.stringify({ alg: "EdDSA", typ: "JWT", kid: env.DRM_KEY_ID })));
  const body = `${header}.${encode64(encoder.encode(JSON.stringify(claims)))}`;
  return `${body}.${encode64(new Uint8Array(await crypto.subtle.sign("Ed25519", await signingKey(env), encoder.encode(body))))}`;
}
export async function validateLease(env: DrmConfig, token: unknown): Promise<LeaseClaims> {
  if (typeof token !== "string" || token.length > 8192) deny("invalid_lease");
  try {
    const parts = token.split("."); if (parts.length !== 3) deny("invalid_lease");
    const header = JSON.parse(new TextDecoder().decode(decode64(parts[0], 512)));
    if (header.alg !== "EdDSA" || header.typ !== "JWT") deny("invalid_lease");
    if (!await crypto.subtle.verify("Ed25519", await verificationKey(env, header.kid), decode64(parts[2], 128), encoder.encode(`${parts[0]}.${parts[1]}`))) deny("invalid_lease");
    const claims = JSON.parse(new TextDecoder().decode(decode64(parts[1])));
    if (claims.product !== "sky-myce" || claims.protocol !== 3 || !Number.isSafeInteger(claims.iat) ||
      !Number.isSafeInteger(claims.exp) || claims.iat > Date.now() + 30000 || claims.exp <= Date.now() || claims.exp <= claims.iat ||
      !Number.isSafeInteger(claims.offlineUntil) || claims.offlineUntil < claims.exp || !Number.isSafeInteger(claims.generation) ||
      !/^[a-f0-9]{32}$/.test(claims.uuid) || !/^[a-f0-9]{64}$/.test(claims.deviceHash) ||
      !/^[a-f0-9]{64}$/.test(claims.keyFingerprint) || typeof claims.accountId !== "string" || typeof claims.installationId !== "string" ||
      typeof claims.discordId !== "string" || typeof claims.jti !== "string" || typeof claims.challengeId !== "string" ||
      !/^[a-f0-9-]{36}$/.test(claims.challengeId) || typeof claims.clientNonce !== "string" ||
      !launchId.test(claims.clientNonce)) deny("invalid_lease");
    if ((claims.platform !== undefined || claims.launchSessionId !== undefined) &&
      (!platformValid(claims.platform) || typeof claims.launchSessionId !== "string" || !launchId.test(claims.launchSessionId))) deny("invalid_lease");
    if (claims.hardwareHash !== undefined && (typeof claims.hardwareHash !== "string" || !/^[a-f0-9]{64}$/.test(claims.hardwareHash))) deny("invalid_lease");
    if (claims.loaderVersion !== undefined && (typeof claims.loaderVersion !== "string" || !/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(claims.loaderVersion))) deny("invalid_lease");
    return claims as LeaseClaims;
  } catch (error) { if (error instanceof AuthorizationError) throw error; return deny("invalid_lease"); }
}
export function seconds(value: string | undefined, fallback: number, min: number, max: number): number {
  const number = value === undefined ? fallback : Number(value);
  if (!Number.isSafeInteger(number) || number < min || number > max) deny("authorization_unavailable");
  return number * 1000;
}
export async function releaseManifest(env: AccountsConfig): Promise<{ signed: SignedManifest; manifest: ReleaseManifest }> {
  if (!env.PRIVATE_ARTIFACTS) deny("artifact_unavailable");
  const object = await env.PRIVATE_ARTIFACTS.get(env.ARTIFACT_MANIFEST_KEY ?? "releases/current.json");
  if (!object || object.size > 16384) deny("artifact_unavailable");
  try {
    const signed = await object.json<SignedManifest>(), bytes = decode64(signed.payload, 8192);
    if (!await crypto.subtle.verify("Ed25519", await verificationKey(env, signed.kid), decode64(signed.signature, 128), bytes)) deny("artifact_unavailable");
    const manifest: ReleaseManifest = JSON.parse(new TextDecoder().decode(bytes));
    if (manifest.product !== "sky-myce" || manifest.protocol !== 3 || typeof manifest.artifact !== "string" ||
      !/^releases\/[A-Za-z0-9._-]+\.jar$/.test(manifest.artifact) || !/^[a-f0-9]{64}$/.test(manifest.sha256) ||
      !Number.isSafeInteger(manifest.issuedAt) || !Number.isSafeInteger(manifest.expiresAt) ||
      manifest.issuedAt > Date.now() + 30000 || manifest.expiresAt <= Date.now() ||
      versionDenial({ minecraftVersion: manifest.minecraft, modVersion: manifest.version, loaderVersion: manifest.minimumLoaderVersion }, env)) deny("artifact_unavailable");
    if (env.ACCOUNT_STORE) {
      const allowed = await env.ACCOUNT_STORE.getByName("global").buildAllowed("mod", manifest.version);
      if (!allowed.ok) deny(allowed.error ?? "BUILD_REVOKED");
    }
    return { signed, manifest };
  } catch (error) { if (error instanceof AuthorizationError && error.code === "BUILD_REVOKED") throw error; return deny("artifact_unavailable"); }
}
export async function loaderRelease(env: AccountsConfig) {
  if (!env.PRIVATE_ARTIFACTS) deny("artifact_unavailable");
  const object = await env.PRIVATE_ARTIFACTS.get(env.LOADER_MANIFEST_KEY ?? "loaders/current.json");
  if (!object || object.size > 16384) deny("artifact_unavailable");
  try {
    const signed = await object.json<SignedManifest>(), bytes = decode64(signed.payload, 8192);
    if (!await crypto.subtle.verify("Ed25519", await verificationKey(env, signed.kid), decode64(signed.signature, 128), bytes)) deny("artifact_unavailable");
    const manifest = JSON.parse(new TextDecoder().decode(bytes)) as ReleaseManifest & { size: number };
    if (manifest.product !== "sky-myce-loader" || manifest.protocol !== 1 || manifest.minecraft !== (env.MINECRAFT_VERSION ?? "26.1.2") ||
      !/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(manifest.version) || manifest.artifact !== `loaders/Sky-Myce-Loader-${manifest.version}.jar` ||
      !/^[a-f0-9]{64}$/.test(manifest.sha256) || !Number.isSafeInteger(manifest.size) || manifest.size < 1 || manifest.size > 64 * 1024 * 1024 ||
      !Number.isSafeInteger(manifest.issuedAt) || !Number.isSafeInteger(manifest.expiresAt) || manifest.issuedAt > Date.now() + 30000 ||
      manifest.expiresAt <= Date.now() || manifest.expiresAt <= manifest.issuedAt || manifest.expiresAt - manifest.issuedAt > 7 * 86400000) deny("artifact_unavailable");
    if (env.ACCOUNT_STORE) {
      const allowed = await env.ACCOUNT_STORE.getByName("global").buildAllowed("loader", manifest.version);
      if (!allowed.ok) deny(allowed.error ?? "BUILD_REVOKED");
    }
    return { signed, manifest };
  } catch (error) { if (error instanceof AuthorizationError && error.code === "BUILD_REVOKED") throw error; return deny("artifact_unavailable"); }
}
/** Public HTTP boundary: bounded JSON, fixed routes, one global identity store. */
export async function handleDrmRequest(request: Request, env: AccountsConfig): Promise<Response | null> {
  const path = new URL(request.url).pathname;
  if (!path.startsWith("/auth/") && !path.startsWith("/loader/")) return null;
  const headers = { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };
  if (path === "/loader/bootstrap-release" && request.method === "GET") {
    try { return Response.json((await loaderRelease(env)).signed, { headers }); }
    catch { return Response.json({ error: "artifact_unavailable" }, { status: 503, headers }); }
  }
  if (path === "/loader/release" && request.method === "GET") {
    try { return Response.json((await releaseManifest(env)).signed, { headers }); }
    catch { return Response.json({ ok: false, error: "artifact_unavailable" }, { status: 503, headers }); }
  }
  if (!["/auth/challenge", "/auth/activate", "/auth/renew", "/loader/manifest", "/loader/artifact", "/loader/bootstrap/renew", "/loader/bootstrap/activate"].includes(path)) return new Response("Not found", { status: 404, headers });
  if (request.method !== "POST") return new Response("POST required", { status: 405, headers });
  if (!env.ACCOUNT_STORE) return Response.json({ error: "authorization_unavailable" }, { status: 503, headers });
  try {
    if (!request.headers.get("Content-Type")?.startsWith("application/json")) deny("invalid_request");
    const contentLength = request.headers.get("Content-Length");
    if (contentLength !== null && (!/^\d+$/.test(contentLength) || Number(contentLength) > 16384)) deny("invalid_request");
    const reader = request.body?.getReader(); if (!reader) deny("invalid_request");
    let total = 0; const chunks: Uint8Array[] = [];
    const timeout = setTimeout(() => { void reader.cancel(); }, 10000);
    try { while (true) { const chunk = await reader.read(); if (chunk.done) break; total += chunk.value.length;
      if (total > 16384) { await reader.cancel(); deny("invalid_request"); } chunks.push(chunk.value); }
    } finally { clearTimeout(timeout); }
    const bytes = new Uint8Array(total); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    const body = JSON.parse(new TextDecoder().decode(bytes));
    if (!body || typeof body !== "object" || Array.isArray(body)) deny("invalid_request");
    const store: DurableObjectStub<AccountStore> = env.ACCOUNT_STORE.getByName("global");
    if (path === "/auth/challenge") {
      const result = await store.challenge(body, request.headers.get("CF-Connecting-IP") ?? "local");
      return Response.json(result, { status: "error" in result ? result.error === "rate_limited" ? 429 : 400 : 200, headers });
    }
    if (path === "/auth/activate" || path === "/auth/renew") {
      const result = await store.authenticateInstallation(body, path === "/auth/activate" ? "activate" : "renew");
      return Response.json(result, { status: result.ok ? 200 : result.error?.endsWith("unavailable") ? 503 :
        result.error === "rate_limited" ? 429 : result.error === "approval_required" ? 409 : 403, headers });
    }
    if (path === "/loader/bootstrap/renew" || path === "/loader/bootstrap/activate") {
      const { manifest } = await loaderRelease(env);
      if (body.version !== manifest.version) deny("release_changed");
      const object = await env.PRIVATE_ARTIFACTS!.get(manifest.artifact);
      if (!object || object.size !== manifest.size) deny("artifact_unavailable");
      const result = await store.authenticateInstallation(body, path.endsWith("/activate") ? "bootstrap-activate" : "bootstrap-renew");
      if (!result.ok) {
        await object.body.cancel();
        return Response.json(result, { status: result.error?.endsWith("unavailable") ? 503 : result.error === "rate_limited" ? 429 : 403, headers });
      }
      return new Response(object.body, { headers: { ...headers, "Content-Type": "application/java-archive", "Content-Length": String(object.size) } });
    }
    const result = await store.authorizeArtifact(body, path === "/loader/manifest" ? "manifest" : "artifact");
    if (!result.ok) return Response.json(result, { status: result.error?.endsWith("unavailable") ? 503 : 403, headers });
    const { signed, manifest } = await releaseManifest(env);
    if (!versionAllowed(result.loaderVersion ?? "", manifest.minimumLoaderVersion)) deny("version_denied");
    if (path === "/loader/manifest") return Response.json(signed, { headers });
    const object = await env.PRIVATE_ARTIFACTS!.get(manifest.artifact);
    if (!object) deny("artifact_unavailable");
    return new Response(object.body, { headers: { ...headers, "Content-Type": "application/java-archive", "Content-Length": String(object.size),
      "Content-Disposition": 'attachment; filename="Sky-Myce.jar"' } });
  } catch (error) {
    const code = error instanceof AuthorizationError ? error.code : "authorization_unavailable";
    return Response.json({ ok: false, error: code }, { status: code.endsWith("unavailable") ? 503 : code === "rate_limited" ? 429 : 400, headers });
  }
}
