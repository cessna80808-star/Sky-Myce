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
export async function handleCommunityRelease(request: Request, env: CommunityReleaseConfig, ctx?: ExecutionContext): Promise<Response | null> {
  const url = new URL(request.url);
  if (!["/community/release", "/community/artifact"].includes(url.pathname)) return null;
  if (request.method !== "GET") return Response.json({ ok: false, error: "method_not_allowed" }, { status: 405 });
  try {
    if (!env.PRIVATE_ARTIFACTS) throw Error("release_unavailable");
    const cache = ctx ? caches.default : undefined;
    const pointer = env.COMMUNITY_RELEASE_MANIFEST_KEY ?? "releases/community-current.json";
    // Canonical keys prevent arbitrary query strings from forcing additional private R2 reads.
    const selectionKey = new URL("/community/cache/selection", url.origin);
    selectionKey.searchParams.set("pointer", pointer);
    const selected = await cache?.match(selectionKey.toString());
    let envelope: unknown;
    if (selected) envelope = await selected.json();
    else {
      const object = await env.PRIVATE_ARTIFACTS.get(pointer);
      if (!object || object.size > 65536) throw Error("release_unavailable");
      envelope = JSON.parse(await object.text());
    }
    // Recheck the signature, key policy and expiry even when metadata came from our cache.
    const manifest = await verifyCommunityManifest(envelope, env);
    const remaining = Math.max(0, Math.floor((manifest.expiresAt - Date.now()) / 1000));
    const metadata = Response.json(envelope, { headers: { "Cache-Control": `public, max-age=${Math.min(60, remaining)}` } });
    if (!selected && cache) ctx!.waitUntil(cache.put(selectionKey.toString(), metadata.clone()).catch(() => {}));
    if (url.pathname === "/community/release") return metadata;
    if (url.searchParams.get("version") !== manifest.version) return Response.json({ ok: false, error: "release_changed" }, { status: 409 });
    const artifactKey = new URL("/community/cache/artifact", url.origin);
    artifactKey.searchParams.set("version", manifest.version);
    artifactKey.searchParams.set("sha256", manifest.sha256);
    const cached = await cache?.match(artifactKey.toString());
    if (cached) return cached;
    const artifact = await env.PRIVATE_ARTIFACTS.get(manifest.artifact);
    if (!artifact || artifact.size !== manifest.size) throw Error("release_unavailable");
    const response = new Response(artifact.body, { headers: { "Content-Type": "application/java-archive", "Content-Length": String(artifact.size),
      "Content-Disposition": `attachment; filename="Sky-Myce-${manifest.version}.jar"`, "Cache-Control": `public, max-age=${Math.min(3600, remaining)}`,
      "ETag": `"${manifest.sha256}"`, "X-Content-Type-Options": "nosniff" } });
    if (cache) ctx!.waitUntil(cache.put(artifactKey.toString(), response.clone()).catch(() => {}));
    return response;
  } catch { return Response.json({ ok: false, error: "release_unavailable" }, { status: 503, headers: { "Cache-Control": "no-store" } }); }
}
