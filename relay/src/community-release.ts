import { decode64, installationKey } from "./drm";

export type CommunityReleaseConfig = { PRIVATE_ARTIFACTS?: R2Bucket; DRM_PUBLIC_KEYS?: string;
  COMMUNITY_RELEASE_MANIFEST_KEY?: string; ARTIFACT_MANIFEST_KEY?: string; MINECRAFT_VERSION?: string };
type Manifest = { product: string; protocol: number; installation: string; minecraft: string; version: string;
  artifact: string; size: number; sha256: string; issuedAt: number; expiresAt: number; notes?: string };
export async function verifyCommunityManifest(envelope: any, env: CommunityReleaseConfig, now = Date.now()): Promise<Manifest> {
  if (!envelope || !/^[A-Za-z0-9_-]{1,64}$/.test(envelope.kid ?? "")) throw Error("invalid_manifest");
  const keys = JSON.parse(env.DRM_PUBLIC_KEYS ?? "{}");
  if (!Object.hasOwn(keys, envelope.kid)) throw Error("invalid_signature");
  const payload = decode64(envelope.payload, 32768), signature = decode64(envelope.signature, 128);
  if (signature.length !== 64 || !await crypto.subtle.verify("Ed25519", await installationKey(keys[envelope.kid]), signature, payload)) throw Error("invalid_signature");
  const manifest = JSON.parse(new TextDecoder().decode(payload));
  if (manifest.product !== "sky-myce" || manifest.protocol !== 4 || manifest.installation !== "fabric" ||
    manifest.minecraft !== (env.MINECRAFT_VERSION ?? "26.1.2") || !/^\d{1,5}\.\d{1,5}\.\d{1,5}$/.test(manifest.version) ||
    manifest.artifact !== `releases/Sky-Myce-${manifest.version}.jar` || Object.hasOwn(manifest, "minimumLoaderVersion") ||
    !Number.isSafeInteger(manifest.size) || manifest.size < 4 || manifest.size > 128 * 1024 * 1024 || !/^[a-f0-9]{64}$/.test(manifest.sha256) ||
    !Number.isSafeInteger(manifest.issuedAt) || !Number.isSafeInteger(manifest.expiresAt) || manifest.issuedAt > now + 300000 ||
    manifest.expiresAt <= now || manifest.expiresAt <= manifest.issuedAt || manifest.expiresAt - manifest.issuedAt > 14 * 86400000 ||
    (manifest.notes !== undefined && (typeof manifest.notes !== "string" || manifest.notes.length > 8000))) throw Error("invalid_manifest");
  return manifest;
}
/** Publish only the selected signed normal Fabric release. The R2 bucket remains private. */
export async function handleCommunityRelease(request: Request, env: CommunityReleaseConfig): Promise<Response | null> {
  const url = new URL(request.url);
  if (!["/community/release", "/community/artifact"].includes(url.pathname)) return null;
  if (request.method !== "GET") return Response.json({ ok: false, error: "method_not_allowed" }, { status: 405 });
  try {
    if (!env.PRIVATE_ARTIFACTS) throw Error("release_unavailable");
    const object = await env.PRIVATE_ARTIFACTS.get(env.COMMUNITY_RELEASE_MANIFEST_KEY ?? "releases/community-current.json");
    if (!object || object.size > 65536) throw Error("release_unavailable");
    const envelope = JSON.parse(await object.text()); const manifest = await verifyCommunityManifest(envelope, env);
    if (url.pathname === "/community/release") return Response.json(envelope, { headers: { "Cache-Control": "public, max-age=60" } });
    if (url.searchParams.get("version") !== manifest.version) return Response.json({ ok: false, error: "release_changed" }, { status: 409 });
    const artifact = await env.PRIVATE_ARTIFACTS.get(manifest.artifact);
    if (!artifact || artifact.size !== manifest.size) throw Error("release_unavailable");
    return new Response(artifact.body, { headers: { "Content-Type": "application/java-archive", "Content-Length": String(artifact.size),
      "Content-Disposition": `attachment; filename="Sky-Myce-${manifest.version}.jar"`, "Cache-Control": "public, max-age=60", "X-Content-Type-Options": "nosniff" } });
  } catch { return Response.json({ ok: false, error: "release_unavailable" }, { status: 503, headers: { "Cache-Control": "no-store" } }); }
}
