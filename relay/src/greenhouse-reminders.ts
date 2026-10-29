import { sha256 } from "./drm";
import { discordPost, openDirectMessage } from "./discord-notifications";

/**
 * Discord DMs for Greenhouse mutations, opt-in from the mod. One row per Discord account holds the whole schedule; the mod
 * uploads it only when it changes, and an unchanged upload writes nothing.
 */
export const GREENHOUSE_LIMITS = {
  /** Events due this close together go out as one DM, sent when the last of them is due (never early). */
  coalesceMs: 30 * 60_000,
  gapMs: 2 * 3_600_000,
  perDay: 4,
  dayMs: 86_400_000,
  horizonMs: 14 * 86_400_000,
  /** A client cannot rewrite its row more often than this. */
  writeGapMs: 60_000,
  /** A re-upload of an event already sent (same plot and kind, about the same time) is not sent again. */
  firedWindowMs: 3 * 3_600_000,
  retryMs: 5 * 60_000,
};

export type GreenhouseEvent = { plot: string; kind: "ready" | "water"; at: number; count: number; total: number };
/** cell, size, index into the plot's plants, stage, stages, readyAt, dryAt, waitAt; times are 0 when unknown or not expected. */
export type GreenhouseTarget = [number, number, number, number, number, number, number, number];
export type GreenhousePlotView = { name: string; tickAt: number; cycleMs: number; plants: string[]; targets: GreenhouseTarget[] };
export type GreenhouseUpload = { enabled: false } | { enabled: true; events: GreenhouseEvent[]; plots: GreenhousePlotView[] };
type Fired = { plot: string; kind: string; at: number };
type Row = { discord_id: string; schedule: string; sent: string; fired: string; attempts: number; updated_at: number };

const plotName = /^[A-Za-z0-9_-]{1,16}$/, plantId = /^[A-Z0-9_]{1,32}$/, hex = /^[a-f0-9]{32}$/;
const int = (value: unknown, min: number, max: number): value is number => Number.isSafeInteger(value) && (value as number) >= min && (value as number) <= max;
const time = (value: unknown, now: number) => value === 0 || int(value, now - 30 * 86_400_000, now + 30 * 86_400_000);

/** Strict shape check at the trust boundary; anything unexpected rejects the whole upload. */
export function validGreenhouseUpload(raw: Record<string, unknown>, now = Date.now()): GreenhouseUpload | null {
  if (!Object.keys(raw).every(key => ["type", "id", "enabled", "events", "plots"].includes(key)) || typeof raw.id !== "string" || !hex.test(raw.id)) return null;
  if (raw.enabled === false) return { enabled: false };
  if (raw.enabled !== true || !Array.isArray(raw.events) || raw.events.length > 6 || !Array.isArray(raw.plots) || raw.plots.length > 3) return null;
  const events: GreenhouseEvent[] = [];
  for (const value of raw.events) {
    const event = value as Record<string, unknown>;
    if (!event || typeof event !== "object" || Object.keys(event).length !== 5 || typeof event.plot !== "string" || !plotName.test(event.plot) ||
      (event.kind !== "ready" && event.kind !== "water") || !int(event.at, now - 5 * 60_000, now + GREENHOUSE_LIMITS.horizonMs) ||
      !int(event.total, 1, 100) || !int(event.count, 1, event.total as number) || events.some(e => e.plot === event.plot && e.kind === event.kind)) return null;
    events.push({ plot: event.plot, kind: event.kind, at: event.at as number, count: event.count as number, total: event.total as number });
  }
  const plots: GreenhousePlotView[] = [];
  for (const value of raw.plots) {
    const plot = value as Record<string, unknown>;
    if (!plot || typeof plot !== "object" || Object.keys(plot).length !== 5 || typeof plot.name !== "string" || !plotName.test(plot.name) ||
      !time(plot.tickAt, now) || plot.tickAt === 0 || !int(plot.cycleMs, 30 * 60_000, 8 * 3_600_000) || !Array.isArray(plot.targets) || plot.targets.length > 40 ||
      !Array.isArray(plot.plants) || plot.plants.length > 8 || !plot.plants.every(id => typeof id === "string" && plantId.test(id)) ||
      plots.some(p => p.name === plot.name)) return null;
    const plants = plot.plants as string[];
    const targets: GreenhouseTarget[] = [];
    for (const entry of plot.targets) {
      const t = entry as unknown[];
      if (!Array.isArray(t) || t.length !== 8 || !int(t[0], 0, 99) || !int(t[1], 1, 3) || !int(t[2], 0, plants.length - 1) ||
        !int(t[4], 0, 1000) || !int(t[3], 0, t[4] as number) || !time(t[5], now) || !time(t[6], now) || !time(t[7], now)) return null;
      targets.push(t as GreenhouseTarget);
    }
    plots.push({ name: plot.name, tickAt: plot.tickAt as number, cycleMs: plot.cycleMs as number, plants, targets });
  }
  return { enabled: true, events, plots };
}

/** The next DM: when it may go out and the events it carries, honouring the 30 minute window, 2 hour gap and 4 a day. */
export function nextGreenhouseDm(events: GreenhouseEvent[], sent: number[], now: number): { at: number; events: GreenhouseEvent[] } | null {
  if (!events.length) return null;
  const sorted = [...events].sort((a, b) => a.at - b.at), first = sorted[0].at;
  let at = Math.max(...sorted.filter(e => e.at <= first + GREENHOUSE_LIMITS.coalesceMs).map(e => e.at));
  const recent = sent.filter(t => t > now - GREENHOUSE_LIMITS.dayMs).sort((a, b) => a - b);
  if (recent.length) at = Math.max(at, recent[recent.length - 1] + GREENHOUSE_LIMITS.gapMs);
  if (recent.length >= GREENHOUSE_LIMITS.perDay) at = Math.max(at, recent[recent.length - GREENHOUSE_LIMITS.perDay] + GREENHOUSE_LIMITS.dayMs);
  // Events falling due while the limits hold the DM back ride along with it.
  return { at, events: sorted.filter(e => e.at <= at) };
}

/** Upload events not already sent: the same plot and kind within a few hours of a DM is that DM's event again. */
export function unsentEvents(events: GreenhouseEvent[], fired: Fired[], now: number): GreenhouseEvent[] {
  return events.filter(e => e.at >= now - 5 * 60_000 && !fired.some(f => f.plot === e.plot && f.kind === e.kind && Math.abs(f.at - e.at) < GREENHOUSE_LIMITS.firedWindowMs));
}

const title = (id: string) => id.toLowerCase().split("_").map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(" ").replace("Plantboy", "PlantBoy").replace("All In", "All-in");
const mainPlant = (plot?: GreenhousePlotView) => {
  const counts = new Map<string, number>();
  plot?.targets.forEach(t => counts.set(plot.plants[t[2]], (counts.get(plot.plants[t[2]]) ?? 0) + 1));
  return [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
};
const discordTime = (at: number) => `<t:${Math.floor(at / 1000)}:R>`;

export function greenhouseDmText(events: GreenhouseEvent[], plots: GreenhousePlotView[]): string {
  const lines = events.map(event => {
    const plant = mainPlant(plots.find(p => p.name === event.plot)), what = plant ? `${title(plant)} ` : "";
    return event.kind === "ready"
      ? `🌱 Plot ${event.plot}: ${event.count} of ${event.total} ${what}mutations ready (${discordTime(event.at)})`
      : `💧 Plot ${event.plot}: ${event.count === 1 ? "a growing mutation runs" : `${event.count} growing mutations run`} out of water (${discordTime(event.at)})`;
  });
  return `**Greenhouse**\n${lines.join("\n")}\n-# Estimated from your last Plant Diagnostics readings.`;
}

const ticks = (plot: GreenhousePlotView, from: number, to: number) =>
  to <= from ? 0 : Math.floor((to - plot.tickAt) / plot.cycleMs) - Math.floor((from - plot.tickAt) / plot.cycleMs);

/** The plots as 10×10 emoji grids, each mutation projected from its upload to [now]. */
export function greenhouseView(plots: GreenhousePlotView[], uploadedAt: number, now: number): { content: string; embeds: object[] } {
  if (!plots.length) return { content: "No Greenhouse plots have been shared yet. Analyze your mutations with the Plant Diagnostics Tool in game.", embeds: [] };
  const embeds = plots.map(plot => {
    const grid = Array<string>(100).fill("⬛");
    const tally = new Map<string, { ready: number; growing: number; dry: number; waiting: number; unknown: number; stage: number; stages: number }>();
    for (const [cell, size, index, stage, stages, readyAt, dryAt, waitAt] of plot.targets) {
      const plant = plot.plants[index];
      const state = readyAt && now >= readyAt ? "ready" : dryAt && now >= dryAt ? "dry" : waitAt && now >= waitAt ? "waiting" : !readyAt && !waitAt ? "unknown" : "growing";
      const mark = { ready: "🟩", dry: "🟥", waiting: "🟪", unknown: "⬜", growing: "🟨" }[state];
      for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) {
        const row = Math.floor(cell / 10) + r, col = cell % 10 + c;
        if (row < 10 && col < 10) grid[row * 10 + col] = mark;
      }
      const entry = tally.get(plant) ?? { ready: 0, growing: 0, dry: 0, waiting: 0, unknown: 0, stage: 0, stages };
      entry[state]++;
      if (state === "growing") entry.stage += Math.min(stages, stage + ticks(plot, uploadedAt, now));
      tally.set(plant, entry);
    }
    const lines = [...tally.entries()].map(([plant, t]) => `**${title(plant)}**: ` + [
      t.ready && `${t.ready} ready`, t.growing && `${t.growing} growing (stage ~${Math.floor(t.stage / t.growing)}/${t.stages})`,
      t.dry && `${t.dry} out of water`, t.waiting && `${t.waiting} need you`, t.unknown && `${t.unknown} not timed`].filter(Boolean).join(" · "));
    const rows = Array.from({ length: 10 }, (_, r) => grid.slice(r * 10, r * 10 + 10).join("")).join("\n");
    return { title: `Plot ${plot.name}`, description: `${rows}\n\n${lines.join("\n") || "No mutations"}`, color: 0x4ca36e };
  });
  return { content: `**Greenhouse** · as of ${discordTime(now)}\n-# 🟩 ready · 🟨 growing · 🟥 out of water · 🟪 needs you · ⬜ not timed. Estimated from your last readings.`, embeds };
}

export const greenhouseButton = (label = "Show greenhouse") => ({ type: 1, components: [{ type: 2, style: 2, label, custom_id: "greenhouse:view" }] });

const json = <T>(text: string, fallback: T): T => { try { return JSON.parse(text) as T; } catch { return fallback; } };

export class GreenhouseReminders {
  constructor(private sql: SqlStorage, private token: () => string | undefined) {
    sql.exec(`CREATE TABLE IF NOT EXISTS greenhouse_reminders (discord_id TEXT PRIMARY KEY, schedule TEXT NOT NULL, next_at INTEGER,
      sent TEXT NOT NULL DEFAULT '[]', fired TEXT NOT NULL DEFAULT '[]', attempts INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS greenhouse_reminder_due ON greenhouse_reminders(next_at);`);
  }

  /** Stores [upload] for [discordId]; writes only when it changed. [next] is the row's next due time, if any. */
  store(discordId: string, upload: GreenhouseUpload, now = Date.now()): { result: "stored" | "unchanged" | "deleted" | "throttled"; next: number | null } {
    if (!upload.enabled) { this.sql.exec("DELETE FROM greenhouse_reminders WHERE discord_id=?", discordId); return { result: "deleted", next: null }; }
    const row = this.sql.exec<Row>("SELECT * FROM greenhouse_reminders WHERE discord_id=?", discordId).toArray()[0];
    const fired = json<Fired[]>(row?.fired ?? "[]", []).filter(f => f.at > now - GREENHOUSE_LIMITS.dayMs);
    const schedule = JSON.stringify({ events: unsentEvents(upload.events, fired, now), plots: upload.plots });
    if (row?.schedule === schedule) return { result: "unchanged", next: null };
    if (row && now - row.updated_at < GREENHOUSE_LIMITS.writeGapMs) return { result: "throttled", next: null };
    const next = nextGreenhouseDm(json<{ events: GreenhouseEvent[] }>(schedule, { events: [] }).events, json<number[]>(row?.sent ?? "[]", []), now)?.at ?? null;
    this.sql.exec(`INSERT INTO greenhouse_reminders(discord_id,schedule,next_at,sent,fired,attempts,updated_at) VALUES(?,?,?,?,?,0,?)
      ON CONFLICT(discord_id) DO UPDATE SET schedule=excluded.schedule,next_at=excluded.next_at,fired=excluded.fired,attempts=0,updated_at=excluded.updated_at`,
      discordId, schedule, next, row?.sent ?? "[]", JSON.stringify(fired), now);
    return { result: "stored", next };
  }

  nextDue(): number | null {
    return this.sql.exec<{ due: number | null }>("SELECT MIN(next_at) AS due FROM greenhouse_reminders").one().due;
  }

  /** Sends due DMs; each row is read once and written once. A failed DM is retried once, then dropped. */
  async deliver(now = Date.now()): Promise<void> {
    const token = this.token();
    const rows = this.sql.exec<Row>("SELECT * FROM greenhouse_reminders WHERE next_at<=? ORDER BY next_at LIMIT 10", now).toArray();
    for (const row of rows) {
      const schedule = json<{ events: GreenhouseEvent[]; plots: GreenhousePlotView[] }>(row.schedule, { events: [], plots: [] });
      const sent = json<number[]>(row.sent, []).filter(t => t > now - GREENHOUSE_LIMITS.dayMs);
      const due = nextGreenhouseDm(schedule.events, sent, now);
      if (!due || due.at > now) {
        this.sql.exec("UPDATE greenhouse_reminders SET next_at=? WHERE discord_id=?", due?.at ?? null, row.discord_id); continue;
      }
      const delivered = !!token && await this.send(token, row.discord_id, due, schedule.plots);
      if (!delivered && row.attempts < 1) {
        this.sql.exec("UPDATE greenhouse_reminders SET attempts=1,next_at=? WHERE discord_id=?", now + GREENHOUSE_LIMITS.retryMs, row.discord_id); continue;
      }
      const events = schedule.events.filter(e => !due.events.includes(e));
      const fired = [...json<Fired[]>(row.fired, []).filter(f => f.at > now - GREENHOUSE_LIMITS.dayMs), ...due.events.map(e => ({ plot: e.plot, kind: e.kind, at: e.at }))];
      if (delivered) sent.push(now);
      this.sql.exec("UPDATE greenhouse_reminders SET schedule=?,next_at=?,sent=?,fired=?,attempts=0 WHERE discord_id=?",
        JSON.stringify({ events, plots: schedule.plots }), nextGreenhouseDm(events, sent, now)?.at ?? null, JSON.stringify(sent), JSON.stringify(fired), row.discord_id);
    }
  }

  /** The button view for [discordId]: one row read, nothing written. */
  view(discordId: string, now = Date.now()) {
    const row = this.sql.exec<Row>("SELECT schedule,updated_at FROM greenhouse_reminders WHERE discord_id=?", discordId).toArray()[0];
    if (!row) return null;
    return greenhouseView(json<{ plots: GreenhousePlotView[] }>(row.schedule, { plots: [] }).plots, row.updated_at, now);
  }

  private async send(token: string, recipient: string, due: { at: number; events: GreenhouseEvent[] }, plots: GreenhousePlotView[]): Promise<boolean> {
    try {
      const channel = await openDirectMessage(token, recipient);
      if (!channel) return false;
      const nonce = (await sha256(`Sky-Myce greenhouse\n${recipient}\n${due.events.map(e => `${e.plot}:${e.kind}:${e.at}`).join(",")}`)).slice(0, 24);
      const message = await discordPost(`/channels/${channel}/messages`, token, { content: greenhouseDmText(due.events, plots),
        components: [greenhouseButton()], allowed_mentions: { parse: [] }, nonce, enforce_nonce: true });
      await message.body?.cancel();
      return message.ok;
    } catch { return false; }
  }
}

const object = (value: unknown): Record<string, unknown> | null =>
  value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;

/** The "Show greenhouse" button on a reminder DM. Called only after User Bot signature verification. */
export async function handleGreenhouseInteraction(data: Record<string, unknown>, env: { DISCORD_USER_APPLICATION_ID?: string; DISCORD_APPLICATION_ID?: string;
  COMMUNITY_SERVICES_ENABLED?: string; ACCOUNT_STORE?: DurableObjectNamespace<import("./accounts").AccountStore> }, backend: boolean): Promise<Response | null> {
  const command = object(data.data);
  if (command?.custom_id !== "greenhouse:view") return null;
  const message = object(data.message), author = object(message?.author), caller = object(data.user) ?? object(object(data.member)?.user);
  const reply = (body: object) => Response.json({ type: typeof message?.flags === "number" && (message.flags & 64) !== 0 ? 7 : 4,
    data: { ...body, flags: 64, allowed_mentions: { parse: [] } } });
  const unavailable = () => reply({ content: "Your Greenhouse is unavailable right now. Try again later.", embeds: [], components: [] });
  if (backend || env.COMMUNITY_SERVICES_ENABLED === "false" || data.type !== 3 || command.component_type !== 2 || !env.ACCOUNT_STORE ||
    author?.id !== (env.DISCORD_USER_APPLICATION_ID ?? env.DISCORD_APPLICATION_ID) || typeof caller?.id !== "string" || !/^\d{17,20}$/.test(caller.id)) return unavailable();
  try {
    const view = await env.ACCOUNT_STORE.getByName("global").greenhouseView(caller.id);
    return view ? reply({ ...view, components: [greenhouseButton("Refresh")] }) : reply({ content: "No Greenhouse reminders are on for your account. Turn them on in the Sky-Myce Greenhouse settings.", embeds: [], components: [] });
  } catch { return unavailable(); }
}
