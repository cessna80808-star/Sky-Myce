/** Operator-run only. Default is a local dry run; --apply updates this one command, never the command registry. */
const command = { name: "cosmetics", type: 1, description: "Manage your Sky-Hawk visual cosmetics", options: [
  { type: 1, name: "link", description: "Link using the one-time code from /sm cosmetics link", options: [
    { type: 3, name: "code", description: "Your private 12-character code; hyphens are optional", required: true, min_length: 12, max_length: 14 }] },
  { type: 1, name: "set", description: "Change only the specified cosmetics on your linked account", options: [
    { type: 3, name: "name", description: "Name (32 visible characters), or safe Minecraft text-component JSON (8 KiB)", required: false, max_length: 6000 },
    ...["x", "y", "z"].map(name => ({ type: 10, name, description: `Visual ${name.toUpperCase()} size, 0.1–3.0; other axes stay unchanged`, required: false, min_value: 0.1, max_value: 3 }))] },
  { type: 1, name: "show", description: "Show your linked account's active cosmetics" },
  { type: 1, name: "reset", description: "Restore real name and size 1/1/1" },
  { type: 1, name: "unlink", description: "Remove your account link and reset cosmetics", options: [
    { type: 5, name: "confirm", description: "Confirm removing the link and resetting cosmetics", required: true }] },
] };
if (!process.argv.includes("--apply")) {
  console.log(JSON.stringify(command, null, 2));
  console.log("Dry run only. Review this definition; --apply requires operator environment credentials.");
} else {
  const app = process.env.DISCORD_APPLICATION_ID, token = process.env.DISCORD_BOT_TOKEN, guild = process.env.DISCORD_COMMAND_GUILD;
  if (!/^\d{17,20}$/.test(app ?? "") || !token || guild && !/^\d{17,20}$/.test(guild)) throw new Error("Set DISCORD_APPLICATION_ID and DISCORD_BOT_TOKEN, and optionally DISCORD_COMMAND_GUILD, in the operator environment.");
  const url = `https://discord.com/api/v10/applications/${app}${guild ? `/guilds/${guild}` : ""}/commands`;
  async function request(url, method, body) {
    const response = await fetch(url, { method, headers: { Authorization: `Bot ${token}`, "Content-Type": "application/json" },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10000), redirect: "error" });
    if (!response.ok) throw new Error(`Discord command registration failed (HTTP ${response.status}). No other commands were modified.`);
    return response.json();
  }
  const existing = await request(url, "GET");
  const own = existing.find(it => it.name === "cosmetics" && it.type === 1);
  await request(own ? `${url}/${own.id}` : url, own ? "PATCH" : "POST", command);
  console.log("Cosmetics command registered. All unrelated commands were preserved.");
}
