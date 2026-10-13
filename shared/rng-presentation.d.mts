export type RngPresentation = { embeds: { title: string; color: number; description: string; fields: { name: string; value: string }[];
  timestamp: string; footer: { text: string } }[]; allowedMentions: { parse: string[]; repliedUser: boolean } };
export function rngPresentation(event: unknown, now?: number, testing?: boolean): RngPresentation | null;
