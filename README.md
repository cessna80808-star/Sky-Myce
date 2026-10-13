# Sky-Myce

Sky-Myce 2.5.1 is a normal Fabric mod for Minecraft 26.1.2 and Java 25. Local features work without Discord or a custom Loader. Connect Discord in Minecraft for community chat, shared cosmetics and optional shared rankings; signed Fabric updates are optional.

Sky-Myce is a Fabric mod for Hypixel SkyBlock focused on smoother dungeon runs, cleaner party coordination, and less menu friction.

Open the config in-game with `/sm` or `/skymyce`.

New to the mod? Read the [player guide](MANUAL.md).

The menu has six destinations: Dungeons, Combat, Economy, Cosmetics, Utils and Settings. Search finds controls across them; Quick Settings, Favorites and Recent retain useful shortcuts.

**2.5.0** removes license/activation/HWID requirements, adds self-service Discord connection and privacy controls, and keeps local features usable when the community service is unavailable. Cosmetics Studio includes independent name/body controls and preset/uploaded capes. Shared leaderboards and the dedicated Discord RNG feed use real newly collected records with honest estimate and coverage labels. Existing dungeon history remains separate from new measurements. `/sm irc` selects global chat, `/sm irc <message>` sends once, and `/sm chat` returns to normal chat. `/sm pc` fails closed until the backend can verify current party membership.

**2.5.1** adds the Healer Crush Camera, with independently rendered saved viewpoints and a singleplayer HUD preview. It also fixes custom name colors and cape previews, and simplifies Discord connection controls. Dungeon viewpoints require calibration; live F7/M7 activation remains unverified.

The same release adds:

- Native Global Chat controls: Reply focuses the existing composer with a parent preview and Cancel; the anchored reaction picker shows authoritative counts. Unicode sequences and custom emoji IDs survive transport, with readable native-chat fallbacks. GIF create/update/retry events retain one canonical message and the existing viewer/browser fallback.
- Offline Cosmetics Studio: equip and save names, body sizes and validated local PNG capes for the selected Minecraft account. None unequips independently of deleting a stored PNG. Bounded inert Minecraft name JSON preserves colors and styles; publishing to the community remains an explicit authenticated action.
- Administrator RNG tester: `/sm rng-test` runs dry-run or mock delivery fixtures only after a fresh authoritative check of the current community session and administrator status. It does not write personal drops, rankings or production Discord posts.
- Menu navigation: Home shortcuts open Community and the existing Global Chat composer; Escape returns through the containing category and Home, closing an open dropdown first. Auto-update defaults to **Off** and saves the choice across restarts; **Update Now** remains available.

## Highlights

- Dungeon tracker and widget
- Party Finder matcher and missing-player alerts
- Auto requeue, chest protection, and chest-profit hints
- Daily digest, auto refill, stash helper, and wardrobe/loadout keybinds
- Relay-backed friend messages and optional cosmetic names

## Quick start

1. Install Fabric for Minecraft 26.1.2 and use Java 25.
2. Close Minecraft and place the standalone Sky-Myce 2.5.1 JAR in the selected instance's `mods` folder alongside its Fabric dependencies. Remove the old Sky-Myce Loader and old Sky-Myce JAR if migrating; retain unrelated mods and settings.
3. Launch Minecraft with Fabric, then open `/sm`. Choose Connect Discord only when you want community services.
4. Enable the features you want in the config.

### Mods folder

- Windows: `%appdata%\.minecraft\mods`
- macOS: `~/Library/Application Support/minecraft/mods`
- Linux: `~/.minecraft/mods`

## Common commands

- `/sm ah` — auction browser
- `/sm dungeon` — dungeon tracker
- `/sm crush-camera` — Healer Crush Camera viewpoints, preview and HUD controls
- `/sm pf` — dungeon friends / LFG
- `/sm digest` — daily digest
- `/sm journal` — persistent personal RNG history and observed trends
- `/sm rng-test` — administrator-only RNG dry-run and mock tester
- `/sm leaderboard` — community rankings, metric sources and UTC periods
- `/sm community` — optional Discord connection, sharing and signed updates
- `/sm msg <ign> <message>` — private relay message
- `/sm chat <ign>` — chat relay
- `/sm irc [message]` — select global chat or send once
- `/sm pc [message]` — party mode, unavailable until membership is verified
- `/sm chat` — return to normal chat
- `/sm cosmetics` — Cosmetics Studio with actual-player preview
- `/sm m7pb [player]` — local M7 PB lookup

## Current updates

- Install the standalone mod once; auto-update defaults to **Off** and its On/Off choice persists. **Update Now** explicitly stages a signature/hash checked update for a safe restart. Turning auto-update Off cancels automatic staging; expired/unavailable update metadata does not stop the installed mod.
- Community sharing starts enabled unless a saved explicit preference says otherwise. Configure global/category/name opt-outs independently of chat and cosmetics.
- Settings are organized into searchable categories with shared theme controls.

## Project notes

This project is a fork of SkyMyce and continues the focus on practical, low-noise SkyBlock quality-of-life improvements.

## Build

```bash
./gradlew build
```

On Windows:

```powershell
gradlew.bat build
```

## License

This project is licensed under the GNU GPLv3 license. See `LICENSE` for details.
