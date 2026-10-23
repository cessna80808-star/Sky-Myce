import minecraftKeys from "./minecraft-keys.json";
// Public trust roots from https://api.minecraftservices.com/publickeys (2026-09-22).
// ponytail: bundled roots avoid blocked Cloudflare egress; refresh/redeploy when Mojang rotates keys.

const encoder = new TextEncoder();
function decode(value: unknown, max: number): Uint8Array<ArrayBuffer> {
  if (typeof value !== "string" || value.length === 0 || value.length > max || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) throw new Error();
  return Uint8Array.from(atob(value), c => c.charCodeAt(0));
}

const trustRoots = new Map<string, Promise<CryptoKey>>(); // Bounded by the bundled key set.
async function signedBy(keys: { publicKey: string }[], signature: Uint8Array, data: Uint8Array): Promise<boolean> {
  for (const entry of keys) {
    let key = trustRoots.get(entry.publicKey);
    if (!key) trustRoots.set(entry.publicKey, key = crypto.subtle.importKey("spki", decode(entry.publicKey, 4096),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"]));
    if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", await key, signature, data)) return true;
  }
  return false;
}

/** Verify Mojang's UUID-bound certificate, ownership of its key, and signed current profile name. */
export async function verifyAccount(data: Record<string, unknown>, challenge: string): Promise<
  { name: string; id: string } | { error: string; retryable: boolean }
> {
  let stage = "fields";
  try {
    if (typeof data.name !== "string" || !/^[A-Za-z0-9_]{1,16}$/.test(data.name) ||
        typeof data.uuid !== "string" || !/^[a-f0-9]{32}$/.test(data.uuid) ||
        typeof data.expires !== "number" || !Number.isSafeInteger(data.expires)) return { error: "invalid_fields", retryable: false };
    if (data.expires <= Date.now()) return { error: "certificate_expired", retryable: true };
    stage = "certificate";
    const publicKey = decode(data.publicKey, 1024);
    // Matches Minecraft ProfilePublicKey.Data.signedPayload: UUID + expiry (big endian) + DER key.
    const certificate = new Uint8Array(24 + publicKey.length);
    certificate.set(Uint8Array.from(data.uuid.match(/../g)!, byte => parseInt(byte, 16)));
    new DataView(certificate.buffer).setBigInt64(16, BigInt(data.expires));
    certificate.set(publicKey, 24);
    if (!await signedBy(minecraftKeys.playerCertificateKeys, decode(data.keySignature, 1024), certificate)) return { error: "certificate_signature", retryable: false };

    stage = "challenge";
    const key = await crypto.subtle.importKey("spki", publicKey, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    const proof = encoder.encode(`SkyMyce relay v2\n${challenge}\n${data.uuid}\n${data.name}`);
    if (!await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, decode(data.proof, 1024), proof)) return { error: "challenge_signature", retryable: false };

    stage = "profile";
    const profileBytes = decode(data.profile, 4096);
    if (!await signedBy(minecraftKeys.profilePropertyKeys, decode(data.profileSignature, 1024), encoder.encode(data.profile as string))) return { error: "profile_signature", retryable: false };
    const profile = JSON.parse(new TextDecoder().decode(profileBytes));
    if (profile.profileId !== data.uuid || typeof profile.profileName !== "string" ||
        !/^[A-Za-z0-9_]{1,16}$/.test(profile.profileName) || profile.profileName.toLowerCase() !== data.name.toLowerCase()) return { error: "profile_identity", retryable: false };
    if (typeof profile.timestamp !== "number" || !Number.isSafeInteger(profile.timestamp)) return { error: "profile_timestamp", retryable: false };
    if (profile.timestamp > Date.now() + 300000 || Date.now() - profile.timestamp > 86400000) return { error: "profile_expired", retryable: true };
    return { id: data.uuid, name: profile.profileName };
  } catch {
    // Encoding/runtime failures alone do not prove the account needs user intervention.
    return { error: `${stage}_unavailable`, retryable: true };
  }
}
