import { currentIrcSession, type IrcSession } from "./irc";
import { takeTrafficCredit, type TrafficCredit } from "./traffic";

type Policy = { epoch: string; revision: number; server: string; floor: string; alpha: boolean; party: string;
  members: string[]; watching: string[]; expiresAt: number };
export type LeapPovSession = IrcSession & { leapPovRevision?: number; leapPov?: Policy;
  leapPovSequence?: { epoch: string; value: number; receivedAt: number }; leapPovTraffic?: TrafficCredit };
type Intent = { revision: number };
const id = /^[a-f0-9]{32}$/, name = /^[A-Za-z0-9_]{1,16}$/;
const subscribeKeys = new Set(["type", "id", "epoch", "enabled", "server", "floor", "alpha", "party", "members", "watching"]);
const poseKeys = new Set(["type", "epoch", "seq", "x", "y", "z", "yaw", "pitch"]);
const finite = (value: unknown, maximum: number): value is number => typeof value === "number" && Number.isFinite(value) && Math.abs(value) <= maximum;
const identity = (session: LeapPovSession, now: number): boolean => !session.bridge && !!session.accountId &&
  !!session.communitySessionId && !!session.connectionId && typeof session.uuid === "string" && id.test(session.uuid) &&
  typeof session.name === "string" && name.test(session.name) && Number.isSafeInteger(session.generation) &&
  Number.isSafeInteger(session.leaseExpiresAt) && session.leaseExpiresAt! > now;
const sameContext = (first: Policy, second: Policy): boolean => first.server === second.server && first.floor === second.floor &&
  first.alpha === second.alpha && first.party === second.party;
const audience = (sender: LeapPovSession, source: Policy, recipient: LeapPovSession, target: Policy): boolean =>
  recipient.uuid !== sender.uuid && sameContext(source, target) && source.members.includes(recipient.uuid!) &&
  target.members.includes(sender.uuid!) && target.watching.includes(sender.uuid!);

/** Consent and sequencing survive hibernation; coordinates exist only in the current awaited handler. */
export class LeapPov {
  constructor(private ctx: Pick<DurableObjectState, "getWebSockets">,
    private authorized: (ws: WebSocket, session: IrcSession) => Promise<boolean>, private now = Date.now) { }

  /** Other feature handlers may resume with an old attachment; only this channel can replace its consent and sequencing. */
  preserve(ws: WebSocket, snapshot: LeapPovSession): void {
    const current = ws.deserializeAttachment() as LeapPovSession | null;
    ws.serializeAttachment({ ...snapshot, bazaar: current?.bazaar, leapPov: current?.leapPov, leapPovRevision: current?.leapPovRevision,
      leapPovSequence: current?.leapPovSequence, leapPovTraffic: current?.leapPovTraffic,
      // IRC owns its receipts: a delayed party/stats snapshot must not silently unsubscribe a confirmed chat channel.
      ircSubscribed: current?.ircSubscribed, globalChatSubscribed: current?.globalChatSubscribed, richChatVersion: current?.richChatVersion,
      partySubscribed: current?.partySubscribed, partyId: current?.partyId, partySubscribeRevision: current?.partySubscribeRevision,
      partyConsentExpiresAt: current?.partyConsentExpiresAt });
  }

  /** Called before authorization can yield, including for malformed controls: old consent stops immediately. */
  reserve(ws: WebSocket, session: LeapPovSession, data: Record<string, unknown>): Intent | undefined {
    const current = currentIrcSession(ws, session);
    if (!current) return;
    const previous = current.leapPov;
    current.leapPovRevision = (current.leapPovRevision ?? 0) + 1;
    delete current.leapPov;
    ws.serializeAttachment(current);
    const replacement = this.policy(current, data, this.now());
    if (previous && (!replacement || previous.epoch !== replacement.epoch || !sameContext(previous, replacement) ||
      previous.members.length !== replacement.members.length || previous.members.some(member => !replacement.members.includes(member))))
      this.remove(current, previous);
    return { revision: current.leapPovRevision };
  }

  subscribe(ws: WebSocket, session: LeapPovSession, data: Record<string, unknown>, intent?: Intent): void {
    const current = currentIrcSession(ws, session), now = this.now();
    if (!current || !intent || current.leapPovRevision !== intent.revision) return;
    if (typeof data.id !== "string" || !id.test(data.id) || typeof data.epoch !== "string" || !id.test(data.epoch)) {
      ws.close(1008, "Invalid POV subscription"); return;
    }
    let error: string | undefined;
    const policy = this.policy(current, data, now);
    if (!identity(current, now)) error = "not_authorized";
    else if (Object.keys(data).some(key => !subscribeKeys.has(key)) || typeof data.enabled !== "boolean" || data.enabled && !policy) error = "invalid_subscription";
    if (!error && policy) current.leapPov = { ...policy, revision: intent.revision };
    ws.serializeAttachment(current);
    ws.send(JSON.stringify({ type: "leap_pov_result", id: data.id, epoch: data.epoch, enabled: !!current.leapPov,
      serverTime: now, expiresAt: current.leapPov?.expiresAt ?? now, ...(error ? { error } : {}) }));
  }

  async pose(ws: WebSocket, session: LeapPovSession, data: Record<string, unknown>, receivedAt: number): Promise<void> {
    let current = currentIrcSession(ws, session);
    const now = this.now();
    if (!current || !identity(current, now) || now < receivedAt || now - receivedAt >= 1500 ||
      Object.keys(data).some(key => !poseKeys.has(key)) || typeof data.epoch !== "string" || !id.test(data.epoch) ||
      !Number.isSafeInteger(data.seq) || (data.seq as number) < 0 ||
      !finite(data.x, 30_000_000) || !finite(data.y, 4096) || !finite(data.z, 30_000_000) || !finite(data.yaw, 180) || !finite(data.pitch, 90)) return;
    const policy = current.leapPov, sequence = data.seq as number;
    if (!policy || policy.epoch !== data.epoch || policy.expiresAt <= now ||
      current.leapPovSequence?.epoch === data.epoch && current.leapPovSequence.value >= sequence) return;
    // Even a throttled newer sample prevents an older in-flight sample from being sent afterward.
    current.leapPovSequence = { epoch: data.epoch, value: sequence, receivedAt };
    current.leapPovTraffic ??= { credits: 2, updated: receivedAt };
    const accepted = takeTrafficCredit(current.leapPovTraffic, 2, 500, receivedAt);
    ws.serializeAttachment(current);
    if (!accepted) return;
    const checked = current;
    for (const peer of this.ctx.getWebSockets()) {
      if (peer === ws || peer.readyState !== WebSocket.OPEN) continue;
      const recipient = peer.deserializeAttachment() as LeapPovSession, target = recipient?.leapPov;
      if (!target || !identity(recipient, this.now()) || target.expiresAt <= this.now() || !audience(checked, policy, recipient, target)) continue;
      if (!await this.authorized(peer, recipient)) continue;
      if (!await this.authorized(ws, checked)) return;
      current = currentIrcSession(ws, checked);
      const destination = currentIrcSession(peer, recipient), at = this.now();
      if (!current || !identity(current, at) || current.leapPov?.revision !== policy.revision ||
        current.leapPov.expiresAt <= at || current.leapPovSequence?.epoch !== data.epoch ||
        current.leapPovSequence.value !== sequence || at - receivedAt >= 1500) return;
      if (!destination || !identity(destination, at) || destination.leapPov?.revision !== target.revision ||
        destination.leapPov.expiresAt <= at || !audience(current, current.leapPov, destination, destination.leapPov)) continue;
      try { peer.send(JSON.stringify({ type: "leap_pov_pose", epoch: target.epoch, sourceEpoch: policy.epoch,
        uuid: current.uuid, name: current.name, seq: sequence, server: policy.server, floor: policy.floor,
        alpha: policy.alpha, party: policy.party, x: data.x, y: data.y, z: data.z, yaw: data.yaw, pitch: data.pitch,
        timestamp: receivedAt, expiresAt: receivedAt + 1500 })); }
      catch { /* A missed pose expires; it is never queued or persisted. */ }
    }
  }

  disconnect(ws: WebSocket): void {
    const session = ws.deserializeAttachment() as LeapPovSession;
    if (session?.leapPov) this.remove(session, session.leapPov);
  }

  private policy(session: LeapPovSession, data: Record<string, unknown>, now: number): Policy | undefined {
    const members = data.members, watching = data.watching;
    if (Object.keys(data).some(key => !subscribeKeys.has(key)) || typeof data.id !== "string" || !id.test(data.id) ||
      data.enabled !== true || typeof data.epoch !== "string" || !id.test(data.epoch) ||
      typeof data.server !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(data.server) ||
      typeof data.floor !== "string" || !/^(?:[FM][1-7]|E|ENTRANCE)$/.test(data.floor) || typeof data.alpha !== "boolean" ||
      typeof data.party !== "string" || !id.test(data.party) || !Array.isArray(members) || members.length < 2 || members.length > 5 ||
      members.some(member => typeof member !== "string" || !id.test(member)) || new Set(members).size !== members.length ||
      !members.includes(session.uuid) || !Array.isArray(watching) || watching.length > 4 ||
      watching.some(member => typeof member !== "string" || member === session.uuid || !members.includes(member)) ||
      new Set(watching).size !== watching.length) return;
    return { epoch: data.epoch, revision: session.leapPovRevision ?? 0, server: data.server, floor: data.floor, alpha: data.alpha,
      party: data.party, members, watching, expiresAt: Math.min(session.leaseExpiresAt ?? now, now + 15_000) };
  }

  private remove(sender: LeapPovSession, source: Policy): void {
    for (const peer of this.ctx.getWebSockets()) {
      const recipient = peer.deserializeAttachment() as LeapPovSession, target = recipient?.leapPov;
      if (peer.readyState !== WebSocket.OPEN || !target || !identity(recipient, this.now()) || target.expiresAt <= this.now() ||
        !audience(sender, source, recipient, target)) continue;
      try { peer.send(JSON.stringify({ type: "leap_pov_remove", epoch: target.epoch, uuid: sender.uuid, sourceEpoch: source.epoch })); }
      catch { /* TTL still removes disconnected or delayed peers. */ }
    }
  }
}
