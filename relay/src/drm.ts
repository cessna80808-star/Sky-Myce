const encoder = new TextEncoder();
// Immutable crypto setup only: signatures and mutable account authority are always checked afresh.
const publicKeys = new Map<string, Promise<CryptoKey>>();
function setup<T>(cache: Map<string, Promise<T>>, identity: string, maximum: number, load: () => Promise<T>): Promise<T> {
  const existing = cache.get(identity);
  if (existing) { cache.delete(identity); cache.set(identity, existing); return existing; }
  const pending = load();
  cache.set(identity, pending);
  if (cache.size > maximum) cache.delete(cache.keys().next().value!);
  void pending.catch(() => { if (cache.get(identity) === pending) cache.delete(identity); });
  return pending;
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
export async function installationKey(publicKey: string): Promise<CryptoKey> {
  try { return await setup(publicKeys, publicKey, 256, () => crypto.subtle.importKey("spki", decode64(publicKey, 256), "Ed25519", false, ["verify"])); }
  catch { return deny("invalid_installation_key"); }
}
