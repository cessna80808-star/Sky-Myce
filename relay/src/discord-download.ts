/** Private loader downloads use a short-lived bearer in the URL fragment, never a query string or request log. */
export type DiscordDownloadConfig = {
  PRIVATE_ARTIFACTS?: R2Bucket;
  SKYMYCE_LOADER_R2_KEY?: string;
  SKYMYCE_PUBLIC_URL?: string;
  DISCORD_LOADER_DOWNLOAD_SECRET?: string;
};
const encoder = new TextEncoder();
const lifetime = 15 * 60_000;
export const loaderObjectKey = (env: DiscordDownloadConfig) => env.SKYMYCE_LOADER_R2_KEY ?? "loaders/Sky-Myce-Loader.jar";
const encode = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
function decode(value: string): Uint8Array {
  return Uint8Array.from(atob(value.replaceAll("-", "+").replaceAll("_", "/")), char => char.charCodeAt(0));
}
async function signingKey(env: DiscordDownloadConfig): Promise<CryptoKey> {
  if (!env.DISCORD_LOADER_DOWNLOAD_SECRET || env.DISCORD_LOADER_DOWNLOAD_SECRET.length < 32) throw new Error("Loader download secret unavailable");
  return crypto.subtle.importKey("raw", encoder.encode(env.DISCORD_LOADER_DOWNLOAD_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}
export async function createLoaderDownload(env: DiscordDownloadConfig, discordId: string, now = Date.now()): Promise<string> {
  const url = new URL("/discord/loader", env.SKYMYCE_PUBLIC_URL);
  if (url.protocol !== "https:" || !/^\d{17,20}$/.test(discordId)) throw new Error("Loader download unavailable");
  const payload = encode(encoder.encode(JSON.stringify({ scope: "sky-myce-loader", sub: discordId,
    issued: now, expires: now + lifetime, nonce: crypto.randomUUID() })));
  const signature = encode(new Uint8Array(await crypto.subtle.sign("HMAC", await signingKey(env), encoder.encode(payload))));
  url.hash = `${payload}.${signature}`;
  return url.toString();
}
export async function validLoaderDownload(env: DiscordDownloadConfig, token: string, now = Date.now()): Promise<boolean> {
  if (token.length > 2048 || !/^[\w-]+\.[\w-]+$/.test(token)) return false;
  try {
    const [payload, signature] = token.split(".");
    if (!await crypto.subtle.verify("HMAC", await signingKey(env), decode(signature), encoder.encode(payload))) return false;
    const data = JSON.parse(new TextDecoder().decode(decode(payload)));
    return data.scope === "sky-myce-loader" && /^\d{17,20}$/.test(data.sub) && Number.isSafeInteger(data.issued) &&
      Number.isSafeInteger(data.expires) && data.issued <= now + 30_000 && data.expires > now && data.expires - data.issued === lifetime;
  } catch { return false; }
}

export async function handleLoaderDownload(request: Request, env: DiscordDownloadConfig): Promise<Response> {
  const headers = { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff" };
  if (request.method === "GET") {
    const nonce = crypto.randomUUID();
    return new Response(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Sky-Myce Loader</title>
<h1>Sky-Myce Loader</h1><p id="status">Download your private Sky-Myce Loader. This link expires after 15 minutes.</p><button id="download">Download Sky-Myce-Loader.jar</button>
<script nonce="${nonce}">
const token=location.hash.slice(1); history.replaceState(null,'',location.pathname);
document.getElementById('download').onclick=async()=>{const button=document.getElementById('download'),status=document.getElementById('status');button.disabled=true;
try{const response=await fetch(location.pathname,{method:'POST',headers:{Authorization:'Bearer '+token},credentials:'omit',cache:'no-store',redirect:'error'});
if(!response.ok)throw Error();const url=URL.createObjectURL(await response.blob()),link=document.createElement('a');link.href=url;link.download='Sky-Myce-Loader.jar';link.click();setTimeout(()=>URL.revokeObjectURL(url),60000);status.textContent='Your Sky-Myce Loader download has started.';
}catch{status.textContent='This private download is unavailable or expired. Contact a Sky-Myce developer.';}finally{button.disabled=false;}};
</script></html>`, { headers: { ...headers, "Content-Type": "text/html; charset=utf-8",
      "Content-Security-Policy": `default-src 'none'; script-src 'nonce-${nonce}'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'` } });
  }
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405, headers });
  if (!env.PRIVATE_ARTIFACTS || !await validLoaderDownload(env, request.headers.get("Authorization")?.replace(/^Bearer /, "") ?? "")) {
    return new Response("Sky-Myce download unavailable or expired", { status: 403, headers });
  }
  const object = await env.PRIVATE_ARTIFACTS.get(loaderObjectKey(env));
  if (!object) return new Response("Sky-Myce Loader unavailable", { status: 503, headers });
  return new Response(object.body, { headers: { ...headers, "Content-Type": "application/java-archive",
    "Content-Disposition": 'attachment; filename="Sky-Myce-Loader.jar"', "Content-Length": String(object.size) } });
}
