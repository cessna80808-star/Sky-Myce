/** A deliberately small, inert subset of Minecraft text components. No events, fonts, selectors or translations. */
export type CosmeticText = { text: string; color?: string; bold?: boolean; italic?: boolean; underlined?: boolean; strikethrough?: boolean; extra?: CosmeticText[] };
const colors = new Set(["black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"]);
function generatorText(source: string): unknown {
  // Some editors copy SNBT-like arrays with bare component keys and Discord-escaped underscores.
  const json = source.replace(/"(?:\\.|[^"\\])*"|([,{]\s*)(text|color|bold|italic|underlined|strikethrough|extra)\s*:/g,
    (token, prefix: string | undefined, key: string | undefined) => prefix ? `${prefix}"${key}":` : token.replace(/\\_/g, "_"));
  return JSON.parse(json);
}
export function cosmeticText(value: unknown): { plain: string | null; component: CosmeticText | null } {
  if (typeof value !== "string" || new TextEncoder().encode(value).length > 8192) throw new Error("Name must be plain text or text-component JSON, at most 8 KiB.");
  const source = value.trim();
  if (!source) return { plain: null, component: null };
  let nodes = 0, plain = "";
  function parse(input: unknown, depth: number): CosmeticText {
    if (depth > 8 || ++nodes > 64) throw new Error("Name component is too complex.");
    if (Array.isArray(input)) {
      if (!input.length || input.length > 64) throw new Error("Name arrays must contain text components.");
      const [first, ...rest] = input.map(child => parse(child, depth + 1));
      // Native Minecraft arrays use the first component as the parent. Preserve its style inheritance.
      return rest.length ? { ...first, extra: [...(first.extra ?? []), ...rest] } : first;
    }
    if (typeof input === "string") input = { text: input };
    if (!input || typeof input !== "object") throw new Error("Use text strings or inert text components.");
    const data = input as Record<string, unknown>;
    if (Object.keys(data).some(key => !["text", "color", "bold", "italic", "underlined", "strikethrough", "extra"].includes(key)) || typeof data.text !== "string") throw new Error("Use text, color, bold, italic, underlined, strikethrough and extra only.");
    const text = data.text.normalize("NFKC");
    if (!/^[\p{L}\p{N}\p{S} _.'•·»«-]*$/u.test(text) || /[\p{C}§@]/u.test(data.text)) throw new Error("Use letters, numbers, supported symbols and simple separators in names.");
    plain += text;
    if (plain.length > 32) throw new Error("Display names may contain at most 32 characters.");
    const result: CosmeticText = { text };
    if (data.color !== undefined) {
      if (typeof data.color !== "string" || !colors.has(data.color) && !/^#[a-f0-9]{6}$/i.test(data.color)) throw new Error("Use a Minecraft color name or #RRGGBB.");
      result.color = data.color.toLowerCase();
    }
    for (const key of ["bold", "italic", "underlined", "strikethrough"] as const) if (data[key] !== undefined) {
      if (typeof data[key] !== "boolean") throw new Error("Text style flags must be true or false."); result[key] = data[key];
    }
    if (data.extra !== undefined) {
      if (!Array.isArray(data.extra) || data.extra.length > 64) throw new Error("Invalid extra text components.");
      result.extra = data.extra.map(child => parse(child, depth + 1));
    }
    return result;
  }
  let component: CosmeticText;
  try { component = parse(source.startsWith("{") || source.startsWith("[") || source.startsWith('"') ? generatorText(source) : { text: source }, 0); }
  catch (error) { throw new Error(error instanceof SyntaxError ? "Invalid name JSON." : (error as Error).message); }
  if (!plain.trim() || plain !== plain.trim()) throw new Error("Names must not be blank or start/end with spaces.");
  return { plain, component };
}
