import { sha256 } from "./drm";
import { discordDeleteMessage, discordPost, openDirectMessage } from "./discord-notifications";

/**
 * Discord DMs for Greenhouse mutations, opt-in from the mod. One row per Discord account holds the whole schedule; the mod
 * uploads it only when it changes, and an unchanged upload writes nothing.
 */
export const GREENHOUSE_LIMITS = {
  /** Events due this close together go out as one DM, sent when the last of them is due (never early). */
  coalesceMs: 30 * 60_000,
  gapMs: 2 * 3_600_000,
  perDay: 8,
  dayMs: 86_400_000,
  horizonMs: 14 * 86_400_000,
  /** A client cannot rewrite its row more often than this. */
  writeGapMs: 60_000,
  /** A re-upload of an event already sent (same plot and kind, about the same time) is not sent again. */
  firedWindowMs: 3 * 3_600_000,
  retryMs: 5 * 60_000,
  /** A DM deletes itself after this; a failed delete is retried until it is [messageKeepMs] old. */
  messageTtlMs: 86_400_000,
  messageKeepMs: 2 * 86_400_000,
  maxMessages: 20,
};

/** ready: the chosen share of a plot is ready; water: a growing one runs dry; wake: one stops until the player acts. */
export type GreenhouseEvent = { plot: string; kind: "ready" | "water" | "wake"; at: number; count: number; total: number };
/** cell, size, index into the plot's plants, stage (-1: not grown yet), stages, readyAt, dryAt, waitAt; times are 0 when unknown or not expected. */
export type GreenhouseTarget = [number, number, number, number, number, number, number, number];
export type GreenhousePlotView = { name: string; tickAt: number; cycleMs: number; plants: string[]; targets: GreenhouseTarget[] };
/** Quiet hours as UTC minutes of the day [start, end); a DM due inside them waits for their end. */
export type QuietHours = [number, number];
export type GreenhouseUpload = { enabled: false } | { enabled: true; events: GreenhouseEvent[]; plots: GreenhousePlotView[]; quiet?: QuietHours };
type Fired = { plot: string; kind: string; at: number };
/** A DM the relay sent, so it can be deleted after a day. */
type SentMessage = { channel: string; id: string; at: number };
type Row = { discord_id: string; schedule: string; sent: string; fired: string; attempts: number; updated_at: number; messages: string; welcome: number };

const snowflake = (value: unknown): value is string => typeof value === "string" && /^\d{17,20}$/.test(value);
const plotName = /^[A-Za-z0-9_-]{1,16}$/, plantId = /^[A-Z0-9_]{1,32}$/, hex = /^[a-f0-9]{32}$/;
const int = (value: unknown, min: number, max: number): value is number => Number.isSafeInteger(value) && (value as number) >= min && (value as number) <= max;
const time = (value: unknown, now: number) => value === 0 || int(value, now - 30 * 86_400_000, now + 30 * 86_400_000);

/** Strict shape check at the trust boundary; anything unexpected rejects the whole upload. */
export function validGreenhouseUpload(raw: Record<string, unknown>, now = Date.now()): GreenhouseUpload | null {
  if (!Object.keys(raw).every(key => ["type", "id", "enabled", "events", "plots", "quiet"].includes(key)) || typeof raw.id !== "string" || !hex.test(raw.id)) return null;
  if (raw.enabled === false) return { enabled: false };
  if (raw.enabled !== true || !Array.isArray(raw.events) || raw.events.length > 9 || !Array.isArray(raw.plots) || raw.plots.length > 3) return null;
  const quiet = raw.quiet;
  if (quiet !== undefined && !(Array.isArray(quiet) && quiet.length === 2 && int(quiet[0], 0, 1439) && int(quiet[1], 0, 1439) && quiet[0] !== quiet[1])) return null;
  const events: GreenhouseEvent[] = [];
  for (const value of raw.events) {
    const event = value as Record<string, unknown>;
    if (!event || typeof event !== "object" || Object.keys(event).length !== 5 || typeof event.plot !== "string" || !plotName.test(event.plot) ||
      (event.kind !== "ready" && event.kind !== "water" && event.kind !== "wake") || !int(event.at, now - 5 * 60_000, now + GREENHOUSE_LIMITS.horizonMs) ||
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
        !int(t[4], 0, 1000) || !(t[3] === -1 || int(t[3], 0, t[4] as number)) || !time(t[5], now) || !time(t[6], now) || !time(t[7], now)) return null;
      targets.push(t as GreenhouseTarget);
    }
    plots.push({ name: plot.name, tickAt: plot.tickAt as number, cycleMs: plot.cycleMs as number, plants, targets });
  }
  return { enabled: true, events, plots, ...(quiet ? { quiet: quiet as QuietHours } : {}) };
}

/** [at], or the end of the quiet hours it falls in. */
export function afterQuietHours(at: number, quiet?: QuietHours): number {
  if (!quiet) return at;
  const [start, end] = quiet, minute = Math.floor(at / 60_000), ofDay = ((minute % 1440) + 1440) % 1440;
  const inside = start < end ? ofDay >= start && ofDay < end : ofDay >= start || ofDay < end;
  return inside ? (minute + (end - ofDay + 1440) % 1440) * 60_000 : at;
}

/** The next DM: when it may go out and the events it carries, honouring the 30 minute window, 2 hour gap, 8 a day and quiet hours. */
export function nextGreenhouseDm(events: GreenhouseEvent[], sent: number[], now: number, quiet?: QuietHours): { at: number; events: GreenhouseEvent[] } | null {
  if (!events.length) return null;
  const sorted = [...events].sort((a, b) => a.at - b.at), first = sorted[0].at;
  let at = Math.max(...sorted.filter(e => e.at <= first + GREENHOUSE_LIMITS.coalesceMs).map(e => e.at));
  const recent = sent.filter(t => t > now - GREENHOUSE_LIMITS.dayMs).sort((a, b) => a - b);
  if (recent.length) at = Math.max(at, recent[recent.length - 1] + GREENHOUSE_LIMITS.gapMs);
  if (recent.length >= GREENHOUSE_LIMITS.perDay) at = Math.max(at, recent[recent.length - GREENHOUSE_LIMITS.perDay] + GREENHOUSE_LIMITS.dayMs);
  at = afterQuietHours(at, quiet);
  // Events falling due while the limits or quiet hours hold the DM back ride along with it.
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
    if (event.kind === "ready") return `🌱 Plot ${event.plot}: ${event.count} of ${event.total} ${what}mutations ready (${discordTime(event.at)})`;
    if (event.kind === "water") return `💧 Plot ${event.plot}: ${event.count === 1 ? "a growing mutation runs" : `${event.count} growing mutations run`} out of water (${discordTime(event.at)})`;
    // The mutations that stop then (a Snoozling falling asleep, a Cheesebite's rat), named from the plot's view.
    const view = plots.find(p => p.name === event.plot);
    const names = [...new Set(view?.targets.filter(t => t[7] && Math.abs(t[7] - event.at) <= GREENHOUSE_LIMITS.coalesceMs).map(t => title(view.plants[t[2]])) ?? [])];
    return `💤 Plot ${event.plot}: ${event.count === 1 ? "a mutation stops" : `${event.count} mutations stop`} growing until you act on ${event.count === 1 ? "it" : "them"}` +
      `${names.length ? ` (${names.join(", ")})` : ""} (${discordTime(event.at)})`;
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
    const tally = new Map<string, { ready: number; growing: number; dry: number; waiting: number; unknown: number; empty: number; stage: number; stages: number }>();
    for (const [cell, size, index, stage, stages, readyAt, dryAt, waitAt] of plot.targets) {
      const plant = plot.plants[index];
      const state = stage < 0 ? "empty" : readyAt && now >= readyAt ? "ready" : dryAt && now >= dryAt ? "dry" : waitAt && now >= waitAt ? "waiting" : !readyAt && !waitAt ? "unknown" : "growing";
      const mark = { ready: "🟩", dry: "🟥", waiting: "🟪", unknown: "⬜", growing: "🟨", empty: "🟫" }[state];
      for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) {
        const row = Math.floor(cell / 10) + r, col = cell % 10 + c;
        if (row < 10 && col < 10) grid[row * 10 + col] = mark;
      }
      const entry = tally.get(plant) ?? { ready: 0, growing: 0, dry: 0, waiting: 0, unknown: 0, empty: 0, stage: 0, stages };
      entry[state]++;
      if (state === "growing") entry.stage += Math.min(stages, stage + ticks(plot, uploadedAt, now));
      tally.set(plant, entry);
    }
    const lines = [...tally.entries()].map(([plant, t]) => `**${title(plant)}**: ` + [
      t.ready && `${t.ready} ready`, t.growing && `${t.growing} growing (stage ~${Math.floor(t.stage / t.growing)}/${t.stages})`,
      t.dry && `${t.dry} out of water`, t.waiting && `${t.waiting} need you`, t.unknown && `${t.unknown} not timed`, t.empty && `${t.empty} not grown yet`].filter(Boolean).join(" · "));
    const rows = Array.from({ length: 10 }, (_, r) => grid.slice(r * 10, r * 10 + 10).join("")).join("\n");
    return { title: `Plot ${plot.name}`, description: `${rows}\n\n${lines.join("\n") || "No mutations"}`, color: 0x4ca36e };
  });
  return { content: `**Greenhouse** · as of ${discordTime(now)}\n-# 🟩 ready · 🟨 growing · 🟥 out of water · 🟪 needs you · ⬜ not timed · 🟫 not grown yet. Estimated from your last readings.`, embeds };
}

/** The view button; a DM also gets Dismiss, which deletes it. */
export const greenhouseButton = (label = "Show greenhouse", dismissible = false) => ({ type: 1, components: [{ type: 2, style: 2, label, custom_id: "greenhouse:view" },
  ...(dismissible ? [{ type: 2, style: 2, label: "Dismiss", custom_id: "greenhouse:dismiss" }] : [])] });

export const GREENHOUSE_WELCOME = "**Greenhouse** reminders are on ✅\nYou will get a DM here when your plots are ready, run out of water or need you.\n" +
  "-# Reminders delete themselves after 24 hours; Dismiss deletes one now.";

/** When a row next needs the alarm: a pending welcome, its next DM, or the first DM due for deletion. */
function rowDue(dmAt: number | undefined, messages: SentMessage[], welcome: boolean, now: number): number | null {
  const times = [...(dmAt === undefined ? [] : [dmAt]), ...messages.map(m => m.at + GREENHOUSE_LIMITS.messageTtlMs), ...(welcome ? [now] : [])];
  return times.length ? Math.min(...times) : null;
}

const json = <T>(text: string, fallback: T): T => { try { return JSON.parse(text) as T; } catch { return fallback; } };

export class GreenhouseReminders {
  constructor(private sql: SqlStorage, private token: () => string | undefined) {
    sql.exec(`CREATE TABLE IF NOT EXISTS greenhouse_reminders (discord_id TEXT PRIMARY KEY, schedule TEXT NOT NULL, next_at INTEGER,
      sent TEXT NOT NULL DEFAULT '[]', fired TEXT NOT NULL DEFAULT '[]', attempts INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS greenhouse_reminder_due ON greenhouse_reminders(next_at);`);
    // Rows from before the welcome DM get it once, with their next upload.
    const columns = new Set(sql.exec<{ name: string }>("PRAGMA table_info(greenhouse_reminders)").toArray().map(row => row.name));
    for (const [column, definition] of Object.entries({ messages: "TEXT NOT NULL DEFAULT '[]'", welcome: "INTEGER NOT NULL DEFAULT 1" })) {
      if (!columns.has(column)) sql.exec(`ALTER TABLE greenhouse_reminders ADD COLUMN ${column} ${definition}`);
    }
  }

  /** Stores [upload] for [discordId]; writes only when it changed. [next] is the row's next due time, if any. */
  store(discordId: string, upload: GreenhouseUpload, now = Date.now()): { result: "stored" | "unchanged" | "deleted" | "throttled"; next: number | null } {
    if (!upload.enabled) { this.sql.exec("DELETE FROM greenhouse_reminders WHERE discord_id=?", discordId); return { result: "deleted", next: null }; }
    const row = this.sql.exec<Row>("SELECT * FROM greenhouse_reminders WHERE discord_id=?", discordId).toArray()[0];
    const fired = json<Fired[]>(row?.fired ?? "[]", []).filter(f => f.at > now - GREENHOUSE_LIMITS.dayMs);
    const schedule = JSON.stringify({ events: unsentEvents(upload.events, fired, now), plots: upload.plots, quiet: upload.quiet });
    if (row?.schedule === schedule) return { result: "unchanged", next: null };
    if (row && now - row.updated_at < GREENHOUSE_LIMITS.writeGapMs) return { result: "throttled", next: null };
    // A new row (reminders just turned on) first gets a DM showing that they work.
    const next = rowDue(nextGreenhouseDm(json<{ events: GreenhouseEvent[] }>(schedule, { events: [] }).events, json<number[]>(row?.sent ?? "[]", []), now, upload.quiet)?.at,
      json<SentMessage[]>(row?.messages ?? "[]", []), !row || row.welcome === 1, now);
    this.sql.exec(`INSERT INTO greenhouse_reminders(discord_id,schedule,next_at,sent,fired,attempts,updated_at,welcome) VALUES(?,?,?,?,?,0,?,1)
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
      const schedule = json<{ events: GreenhouseEvent[]; plots: GreenhousePlotView[]; quiet?: QuietHours }>(row.schedule, { events: [], plots: [] });
      const sent = json<number[]>(row.sent, []).filter(t => t > now - GREENHOUSE_LIMITS.dayMs);
      let fired = json<Fired[]>(row.fired, []).filter(f => f.at > now - GREENHOUSE_LIMITS.dayMs), events = schedule.events, attempts = 0, retryAt: number | undefined;
      // DMs older than a day are deleted; one that fails is tried again until it is two days old.
      const messages: SentMessage[] = [];
      for (const message of json<SentMessage[]>(row.messages, [])) {
        if (message.at > now - GREENHOUSE_LIMITS.messageTtlMs) messages.push(message);
        else if (!(token && await discordDeleteMessage(token, message.channel, message.id).catch(() => false)) && message.at > now - GREENHOUSE_LIMITS.messageKeepMs) messages.push(message);
      }
      if (row.welcome && token) {
        const welcome = await this.send(token, row.discord_id, GREENHOUSE_WELCOME, `welcome:${row.updated_at}`, now);
        if (welcome.message) messages.push(welcome.message);
      }
      const due = nextGreenhouseDm(events, sent, now, schedule.quiet);
      if (due && due.at <= now) {
        const result = token ? await this.send(token, row.discord_id, greenhouseDmText(due.events, schedule.plots), due.events.map(e => `${e.plot}:${e.kind}:${e.at}`).join(","), now)
          : { delivered: false, message: undefined };
        if (!result.delivered && row.attempts < 1) { attempts = 1; retryAt = now + GREENHOUSE_LIMITS.retryMs; }
        else {
          events = events.filter(e => !due.events.includes(e));
          fired = [...fired, ...due.events.map(e => ({ plot: e.plot, kind: e.kind, at: e.at }))];
          if (result.delivered) sent.push(now);
          if (result.message) messages.push(result.message);
        }
      }
      const kept = messages.slice(-GREENHOUSE_LIMITS.maxMessages);
      this.sql.exec("UPDATE greenhouse_reminders SET schedule=?,next_at=?,sent=?,fired=?,attempts=?,messages=?,welcome=0 WHERE discord_id=?",
        JSON.stringify({ events, plots: schedule.plots, quiet: schedule.quiet }), rowDue(retryAt ?? nextGreenhouseDm(events, sent, now, schedule.quiet)?.at, kept, false, now),
        JSON.stringify(sent), JSON.stringify(fired), attempts, JSON.stringify(kept), row.discord_id);
    }
  }

  /** The button view for [discordId]: one row read, nothing written. */
  view(discordId: string, now = Date.now()) {
    const row = this.sql.exec<Row>("SELECT schedule,updated_at FROM greenhouse_reminders WHERE discord_id=?", discordId).toArray()[0];
    if (!row) return null;
    return greenhouseView(json<{ plots: GreenhousePlotView[] }>(row.schedule, { plots: [] }).plots, row.updated_at, now);
  }

  /** One DM with the view and Dismiss buttons; its [message] is kept so it can be deleted later. */
  private async send(token: string, recipient: string, content: string, key: string, now: number): Promise<{ delivered: boolean; message?: SentMessage }> {
    try {
      const channel = await openDirectMessage(token, recipient);
      if (!channel) return { delivered: false };
      const nonce = (await sha256(`Sky-Myce greenhouse\n${recipient}\n${key}`)).slice(0, 24);
      const response = await discordPost(`/channels/${channel}/messages`, token, { content, components: [greenhouseButton("Show greenhouse", true)],
        allowed_mentions: { parse: [] }, nonce, enforce_nonce: true });
      if (!response.ok) { await response.body?.cancel(); return { delivered: false }; }
      const id = (await response.json().catch(() => null) as { id?: unknown } | null)?.id;
      return { delivered: true, message: snowflake(id) ? { channel, id, at: now } : undefined };
    } catch { return { delivered: false }; }
  }
}

const object = (value: unknown): Record<string, unknown> | null =>
  value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;

/** The "Show greenhouse" and "Dismiss" buttons on a reminder DM. Called only after User Bot signature verification. */
export async function handleGreenhouseInteraction(data: Record<string, unknown>, env: { DISCORD_USER_APPLICATION_ID?: string; DISCORD_APPLICATION_ID?: string;
  COMMUNITY_SERVICES_ENABLED?: string; USER_BOT_TOKEN?: string; ACCOUNT_STORE?: DurableObjectNamespace<import("./accounts").AccountStore> }, backend: boolean): Promise<Response | null> {
  const command = object(data.data);
  if (command?.custom_id !== "greenhouse:view" && command?.custom_id !== "greenhouse:dismiss") return null;
  const message = object(data.message), author = object(message?.author), caller = object(data.user) ?? object(object(data.member)?.user);
  const reply = (body: object) => Response.json({ type: typeof message?.flags === "number" && (message.flags & 64) !== 0 ? 7 : 4,
    data: { ...body, flags: 64, allowed_mentions: { parse: [] } } });
  const unavailable = () => reply({ content: "Your Greenhouse is unavailable right now. Try again later.", embeds: [], components: [] });
  if (backend || env.COMMUNITY_SERVICES_ENABLED === "false" || data.type !== 3 || command.component_type !== 2 || !env.ACCOUNT_STORE ||
    author?.id !== (env.DISCORD_USER_APPLICATION_ID ?? env.DISCORD_APPLICATION_ID) || typeof caller?.id !== "string" || !/^\d{17,20}$/.test(caller.id)) return unavailable();
  if (command.custom_id === "greenhouse:dismiss") {
    // Only the DM's recipient can press it, and the bot deletes nothing but its own DM.
    if (data.guild_id || !env.USER_BOT_TOKEN || typeof data.channel_id !== "string" || typeof message?.id !== "string") return unavailable();
    const deleted = await discordDeleteMessage(env.USER_BOT_TOKEN, data.channel_id, message.id).catch(() => false);
    return deleted ? Response.json({ type: 6 }) : reply({ content: "That reminder could not be deleted right now. Try again later.", embeds: [], components: [] });
  }
  try {
    const view = await env.ACCOUNT_STORE.getByName("global").greenhouseView(caller.id);
    return view ? reply({ ...view, components: [greenhouseButton("Refresh")] }) : reply({ content: "No Greenhouse reminders are on for your account. Turn them on in the Sky-Myce Greenhouse settings.", embeds: [], components: [] });
  } catch { return unavailable(); }
}
