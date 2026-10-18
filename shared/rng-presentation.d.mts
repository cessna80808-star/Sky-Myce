export type RngPresentation = { embeds: { title: string; color: number; description: string; fields: { name: string; value: string; inline?: boolean }[];
  thumbnail: { url: string }; timestamp: string; footer: { text: string } }[];
  components?: { type: number; components: { type: number; style: number; label: string; custom_id: string }[] }[];
  allowedMentions: { parse: string[]; repliedUser: boolean } };
export function rngPresentation(event: unknown, now?: number, testing?: boolean): RngPresentation | null;
export function rngRecentPresentation(event: unknown, now?: number): RngPresentation | null;
