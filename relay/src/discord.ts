import type { CosmeticsConfig, DiscordCommand } from "./cosmetics";

const MAX_INTERACTION_BYTES = 96 * 1024;
/** Discord signs the exact bytes. No bot token is accepted from a client or needed for verification. */
export async function verifiedInteraction(request: Request, env: CosmeticsConfig, now = Date.now()): Promise<Record<string, unknown> | null> {
  const signature = request.headers.get("X-Signature-Ed25519") ?? "";
  const timestamp = request.headers.get("X-Signature-Timestamp") ?? "";
  const publicKey = env.DISCORD_PUBLIC_KEY ?? "";
  if (request.method !== "POST" || !/^[a-f0-9]{128}$/i.test(signature) || !/^[a-f0-9]{64}$/i.test(publicKey) ||
      !/^\d{10}$/.test(timestamp) || now - Number(timestamp) * 1000 > 300000 || Number(timestamp) * 1000 - now > 60000) return null;
  const reader = request.body?.getReader();
  if (!reader || Number(request.headers.get("Content-Length") ?? 0) > MAX_INTERACTION_BYTES) return null;
  const chunks: Uint8Array[] = []; let size = 0;
  let timedOut = false;
  const timeout = setTimeout(() => { timedOut = true; void reader.cancel().catch(() => {}); }, 3000);
  try {
    while (true) { const { done, value } = await reader.read(); if (done) break; size += value.length; if (size > MAX_INTERACTION_BYTES) return null; chunks.push(value); }
    if (timedOut) return null;
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    const prefix = new TextEncoder().encode(timestamp), signed = new Uint8Array(prefix.length + bytes.length);
    signed.set(prefix); signed.set(bytes, prefix.length);
    const hex = (value: string) => Uint8Array.from(value.match(/../g)!, it => parseInt(it, 16));
    const key = await crypto.subtle.importKey("raw", hex(publicKey), "Ed25519", false, ["verify"]);
    if (!await crypto.subtle.verify("Ed25519", key, hex(signature), signed)) return null;
    const data = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes));
    if (!data || Array.isArray(data) || typeof data !== "object" || data.application_id !== env.DISCORD_APPLICATION_ID) return null;
    return data;
  } catch { return null; } finally { clearTimeout(timeout); await reader.cancel().catch(() => {}); }
}

export function discordCommand(data: Record<string, unknown>): DiscordCommand | null {
  const object = (value: unknown): Record<string, unknown> | null => value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
  const command = object(data.data), member = object(data.member), user = object(member?.user) ?? object(data.user);
  if (data.type !== 2 || command?.name !== "cosmetics" || typeof data.id !== "string" || !/^\d{17,20}$/.test(data.id) ||
      typeof user?.id !== "string" || !/^\d{17,20}$/.test(user.id) || !Array.isArray(command.options) || command.options.length !== 1) return null;
  const sub = object(command.options[0]);
  if (!sub || sub.type !== 1 || !["link", "set", "show", "reset", "unlink"].includes(sub.name as string)) return null;
  const options: Record<string, unknown> = {}; // RPC structured cloning requires a plain object; keys are allowlisted below.
  if (sub.options !== undefined && (!Array.isArray(sub.options) || sub.options.length > 4)) return null;
  for (const option of (sub.options ?? []) as unknown[]) {
    const value = object(option);
    if (!value || typeof value.name !== "string" || !["code", "name", "x", "y", "z", "confirm"].includes(value.name) || Object.hasOwn(options, value.name) ||
        value.type !== (["x", "y", "z"].includes(value.name) ? 10 : value.name === "confirm" ? 5 : 3)) return null;
    options[value.name] = value.value;
  }
  return { id: data.id, actor: user.id, channel: typeof data.channel_id === "string" ? data.channel_id : "",
    action: sub.name as DiscordCommand["action"], options };
}
