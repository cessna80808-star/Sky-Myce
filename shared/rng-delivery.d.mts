export function deliverRngPublication(delivery: unknown, hooks: { channelId: string; now: () => number;
  present: (event: unknown, now: number) => unknown; receipts: { get(id: string): string | undefined; record(id: string, messageId: string): Promise<void> };
  send(card: unknown): Promise<{ id: string }>; ack(raw: Record<string, unknown>): Promise<{ deleteRequired?: boolean }>; remove(id: string): Promise<void> }): Promise<void>;
