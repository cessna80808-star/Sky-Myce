import type { AccountStore } from "./accounts";
import { boundedJson } from "./community";
import { currentIrcSession, type IrcSession } from "./irc";
import { sha256 } from "./drm";
import { BAZAAR_FRESH_MILLIS } from "./bazaar-market";

export type BazaarSession = { epoch: string; active: boolean; dmEnabled: boolean; revision: number };
type Session = IrcSession & { bazaar?: BazaarSession };
const id = /^[a-f0-9]{32}$/;
export function validBazaarAlert(raw: Record<string, unknown>, now = Date.now()) {
  return Object.keys(raw).every(key => ["type", "id", "epoch", "product", "netProfit", "roi", "confidence", "capital", "availableCapital", "observedAt", "reason"].includes(key)) &&
    typeof raw.id === "string" && id.test(raw.id) && typeof raw.epoch === "string" && id.test(raw.epoch) &&
    typeof raw.product === "string" && /^[A-Z0-9_:;.\-]{1,100}$/.test(raw.product) &&
    [raw.netProfit, raw.roi, raw.confidence, raw.capital, raw.availableCapital].every(value => typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1e12) &&
    Number(raw.netProfit) > 0 && Number(raw.roi) >= .01 && Number(raw.roi) <= 10 && Number(raw.confidence) >= .65 && Number(raw.confidence) <= 1 &&
    Number(raw.capital) > 0 && Number(raw.capital) <= Number(raw.availableCapital) &&
    Math.abs(Number(raw.roi) - Number(raw.netProfit) / Number(raw.capital)) <= .000001 &&
    Number.isSafeInteger(raw.observedAt) && Number(raw.observedAt) <= now + 60000 && now - Number(raw.observedAt) <= BAZAAR_FRESH_MILLIS &&
    typeof raw.reason === "string" && raw.reason.length > 0 && raw.reason.length <= 300 && !/[\p{Cc}\p{Cf}]/u.test(raw.reason);
}
/** The live socket owns activity/consent; no personal heartbeat or opportunity log is stored. */
export class BazaarNotifications {
  constructor(private env: { ACCOUNT_STORE: DurableObjectNamespace<AccountStore>; USER_BOT_TOKEN?: string },
    private authorized: (ws: WebSocket, session: IrcSession) => Promise<boolean>) { }
  reserve(ws: WebSocket, session: Session): number | undefined {
    const current = currentIrcSession(ws, session); if (!current) return;
    const revision = (current.bazaar?.revision ?? 0) + 1;
    current.bazaar = { epoch: "", active: false, dmEnabled: false, revision }; ws.serializeAttachment(current); return revision;
  }
  update(ws: WebSocket, session: Session, raw: Record<string, unknown>, revision?: number) {
    const current = currentIrcSession(ws, session); if (!current || revision === undefined || current.bazaar?.revision !== revision) return;
    if (!Object.keys(raw).every(key => ["type", "id", "epoch", "active", "dmEnabled"].includes(key)) ||
      typeof raw.id !== "string" || !id.test(raw.id) || typeof raw.epoch !== "string" || !id.test(raw.epoch) ||
      typeof raw.active !== "boolean" || typeof raw.dmEnabled !== "boolean") return;
    current.bazaar = { epoch: raw.epoch, active: raw.active, dmEnabled: raw.dmEnabled, revision }; ws.serializeAttachment(current);
    ws.send(JSON.stringify({ type: "bazaar_session_result", id: raw.id, active: raw.active, dmEnabled: raw.dmEnabled }));
  }
  async alert(ws: WebSocket, session: Session, raw: Record<string, unknown>) {
    const token = this.env.USER_BOT_TOKEN; if (!token || !validBazaarAlert(raw)) return;
    const live = () => { const current = currentIrcSession(ws, session); return current && !current.bridge && current.bazaar?.active &&
      current.bazaar.dmEnabled && current.bazaar.epoch === raw.epoch ? current : null; };
    let current = live(); if (!current || !await this.authorized(ws, current) || !(current = live())) return;
    const store = this.env.ACCOUNT_STORE.getByName("global"), market = await store.communityBazaarMarket(true);
    if (!(current = live()) || market.freshness !== "fresh" || market.observedAt !== raw.observedAt ||
      !market.products.some(product => product.id === raw.product && product.buyMovingWeek >= 10000 && product.sellMovingWeek >= 10000)) return;
    const recipient = await store.claimCommunityBazaarAlert({ sessionId: current.communitySessionId!, accountId: current.accountId!, uuid: current.uuid!,
      generation: current.generation!, connectionId: current.connectionId! }, String(raw.id), String(raw.product));
    if (!recipient || !(current = live())) return;
    const post = (path: string, body: unknown) => fetch(`https://discord.com/api/v10${path}`, { method: "POST", redirect: "manual", signal: AbortSignal.timeout(8000),
      headers: { Authorization: `Bot ${token}`, "Content-Type": "application/json" }, body: JSON.stringify(body) });
    try {
      const response = await post("/users/@me/channels", { recipient_id: recipient });
      if (!response.ok) { await response.body?.cancel(); return; }
      const channel = await boundedJson(new Request("https://discord.com", { method: "POST", body: response.body }), 16384);
      if (typeof channel.id !== "string" || !/^\d{17,20}$/.test(channel.id) || !(current = live()) || !await this.authorized(ws, current) || !live() || !validBazaarAlert(raw)) return;
      const nonce = (await sha256(`Sky-Myce Bazaar\n${recipient}\n${raw.id}`)).slice(0, 24);
      if (!live()) return;
      const sent = await post(`/channels/${channel.id}/messages`, { content: `**Exceptional Bazaar forecast · ${raw.product}**\nEstimated net profit: ${Number(raw.netProfit).toLocaleString()} coins · ROI ${(Number(raw.roi) * 100).toFixed(1)}%\n${String(raw.reason).replace(/[<@`*_~|]/g, "")}\nEstimate only. All trading remains manual.`, allowed_mentions: { parse: [] }, nonce, enforce_nonce: true });
      // Ephemeral alerts deliberately do not enqueue offline retries or a public opportunity feed, including on 429.
      await sent.body?.cancel();
    } catch { /* Closed DMs/provider failure are quiet; the in-game opportunity remains available. */ }
  }
}
