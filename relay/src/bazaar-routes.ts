import type { AccountStore } from "./accounts";
import { boundedJson } from "./community";
import { bazaarFreshness } from "./bazaar-market";

const prefix = "/community/bazaar";
/** Public market/replay reads need no Discord account; every personal write uses the existing bound session. */
export async function handleBazaarRequest(request: Request, env: { ACCOUNT_STORE: DurableObjectNamespace<AccountStore> }, ctx?: ExecutionContext): Promise<Response | null> {
  const url = new URL(request.url); if (!url.pathname.startsWith(prefix + "/")) return null;
  const store = env.ACCOUNT_STORE.getByName("global"), path = url.pathname.slice(prefix.length);
  const json = (result: { ok: boolean; error?: string }) => Response.json(result, { status: result.ok ? 200 : result.error === "replay_unavailable" ? 404 : result.error === "rate_limited" ? 429 :
    ["session_expired", "not_authorized"].includes(result.error ?? "") ? 401 : result.error === "sharing_disabled" || result.error === "identity_blacklisted" ? 403 : 400,
    headers: { "Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff" } });
  if (request.method === "GET") {
    if (path === "/market") {
      if ([...url.searchParams].some(([key, value]) => key !== "active" || !["true", "false"].includes(value))) return json({ ok: false, error: "invalid_request" });
      const active = url.searchParams.get("active") === "true", cache = ctx ? caches.default : undefined;
      const key = new URL(`${prefix}/cache/market/${active ? "active" : "idle"}`, url.origin).toString();
      const cached = await cache?.match(key);
      if (cached) { const value = await cached.json() as { ok: true; observedAt: number; freshness: string }; value.freshness = bazaarFreshness(value.observedAt); return json(value); }
      const result = await store.communityBazaarMarket(active);
      if (cache) ctx!.waitUntil(cache.put(key, Response.json(result, { headers: { "Cache-Control": "public, max-age=15" } })).catch(() => {}));
      return json(result);
    }
    if (path === "/history") {
      if ([...url.searchParams].some(([key, value]) => !["product", "daily"].includes(key) || key === "daily" && !["true", "false"].includes(value))) return json({ ok: false, error: "invalid_request" });
      const product = url.searchParams.get("product") ?? "", daily = url.searchParams.get("daily") === "true", cache = ctx ? caches.default : undefined;
      if (!/^[A-Z0-9_:;.\-]{1,100}$/.test(product)) return json({ ok: false, error: "invalid_product" });
      const key = new URL(`${prefix}/cache/history/${encodeURIComponent(product)}/${daily ? "day" : "hour"}`, url.origin).toString(), cached = await cache?.match(key);
      if (cached) return cached;
      const result = await store.communityBazaarHistory(product, daily), response = json(result);
      if (cache && result.ok && "points" in result && result.points?.length) ctx!.waitUntil(cache.put(key, Response.json(result, { headers: { "Cache-Control": "public, max-age=60" } })).catch(() => {}));
      return response;
    }
    if (path === "/gallery") return json(await store.communityBazaarGallery());
    const publication = /^\/replays\/([A-Za-z0-9_-]{43})$/.exec(path);
    if (publication) {
      const result = await store.communityBazaarReplay(publication[1]);
      if (!result.ok || request.headers.get("Accept")?.includes("application/json")) return json(result);
      return replayViewer(result);
    }
    const credential = request.headers.get("Authorization")?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1] ?? "";
    if (path === "/stats") return json(await store.communityBazaar(credential, "stats", Object.fromEntries(url.searchParams)));
    if (path === "/settings" && !url.search) return json(await store.communityBazaar(credential, "settings-get", {}));
    return json({ ok: false, error: "invalid_request" });
  }
  const actions: Record<string, string> = { "/contributions": "contributions", "/contributions/remove": "contributions-remove", "/session-summary": "summary", "/settings": "settings", "/replays/publish": "publish", "/replays/revoke": "revoke", "/replays/delete": "delete" };
  if (request.method !== "POST" || !actions[path] || url.search || !/^application\/json(?:\s*;|$)/i.test(request.headers.get("Content-Type") ?? "")) return json({ ok: false, error: "invalid_request" });
  const token = request.headers.get("Authorization")?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
  if (!token) return json({ ok: false, error: "not_authorized" });
  try { return json(await store.communityBazaar(token, actions[path], await boundedJson(request, 65536))); }
  catch { return json({ ok: false, error: "invalid_request" }); }
}
function replayViewer(result: unknown) {
  const nonce = crypto.randomUUID().replaceAll("-", ""), data = JSON.stringify(result).replaceAll("<", "\\u003c");
  return new Response(`<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sky-Myce · Bazaar replay</title>
<style>body{margin:0;background:#0d131b;color:#e6eee9;font:16px system-ui}main{max-width:850px;margin:8vh auto;padding:28px}small{color:#8fa89c}h1{font-size:42px}#profit{font-size:56px;color:#82e5b0}svg{width:100%;height:180px;border-bottom:1px solid #33483f}li{padding:10px 0}button{background:#244d3a;color:#dcffeb;border:0;border-radius:8px;padding:12px;cursor:pointer}@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto}}</style>
<main><small>SKY-MYCE / PUBLISHED REPLAY</small><h1>Trading session replay</h1><p id="identity"></p><div id="profit"></div><p id="summary"></p><svg viewBox="0 0 800 180" role="img" aria-label="Realized-profit timeline"><polyline id="chart" fill="none" stroke="#82e5b0" stroke-width="3"/></svg><ol id="timeline"></ol><button id="play">Replay</button><p><small>Client-observed realized results, not Hypixel-certified. This is a deliberately published representation; private journals are never served. Not affiliated with Hypixel.</small></p></main>
<script nonce="${nonce}">const r=${data}.replay,p=document.querySelector('#profit');document.querySelector('#identity').textContent=r.identity||'Identity hidden';document.querySelector('#summary').textContent=Math.round(r.durationMs/60000)+' minutes · '+r.completedFlips+' completed flips';for(const e of r.timeline){const li=document.createElement('li');li.textContent=Math.round(e.at/1000)+'s'+(e.item?' · '+e.item:'')+(e.profit!==undefined?' · '+e.profit.toLocaleString()+' coins':' · values hidden');document.querySelector('#timeline').append(li)}let runningProfit=0;const progression=(r.progress||r.timeline).filter(e=>e.profit!==undefined).map(e=>({...e,profit:(runningProfit+=e.profit)}));const values=progression.map(x=>x.profit).filter(x=>x!==undefined);const lo=Math.min(0,...values),hi=Math.max(1,...values);document.querySelector('#chart').setAttribute('points',progression.filter(e=>e.profit!==undefined).map(e=>(e.at/r.durationMs*800)+','+(170-(e.profit-lo)/(hi-lo)*160)).join(' '));const line=document.querySelector('#chart');line.setAttribute('pathLength','1');line.style.strokeDasharray='1';function play(){if(r.realizedProfit===undefined){p.textContent='Exact values hidden';return}const start=performance.now(),duration=matchMedia('(prefers-reduced-motion:reduce)').matches?0:1200;function tick(t){const a=duration?Math.min(1,(t-start)/duration):1;line.style.strokeDashoffset=String(1-a);p.textContent=Math.round(r.realizedProfit*(1-Math.pow(1-a,3))).toLocaleString()+' coins';if(a<1)requestAnimationFrame(tick)}requestAnimationFrame(tick)}document.querySelector('#play').onclick=play;play();</script></html>`,
    { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy": `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'` } });
}
