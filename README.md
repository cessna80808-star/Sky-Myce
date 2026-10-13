# Sky-Myce

Sky-Myce 2.5.0 is a normal Fabric mod for Minecraft 26.1.2 and Java 25. Local features work without Discord or a custom Loader. Connect Discord in Minecraft for community chat, shared cosmetics and optional shared rankings; signed Fabric updates are optional.

Sky-Myce is a Fabric mod for Hypixel SkyBlock focused on smoother dungeon runs, cleaner party coordination, and less menu friction.

Open the config in-game with `/sm` or `/skymyce`.

New to the mod? Read the [player guide](MANUAL.md).

The menu has six destinations: Dungeons, Combat, Economy, Cosmetics, Utils and Settings. Search finds controls across them; Quick Settings, Favorites and Recent retain useful shortcuts.

**2.5.0** removes license/activation/HWID requirements, adds self-service Discord connection and privacy controls, and keeps local features usable when the community service is unavailable. Cosmetics Studio includes independent name/body controls and preset/uploaded capes. Shared leaderboards and the dedicated Discord RNG feed use real newly collected records with honest estimate and coverage labels. Existing dungeon history remains separate from new measurements. `/sm irc` selects global chat, `/sm irc <message>` sends once, and `/sm chat` returns to normal chat. `/sm pc` fails closed until the backend can verify current party membership.

## Highlights

- Dungeon tracker and widget
- Party Finder matcher and missing-player alerts
- Auto requeue, chest protection, and chest-profit hints
- Daily digest, auto refill, stash helper, and wardrobe/loadout keybinds
- Relay-backed friend messages and optional cosmetic names

## Quick start

1. Install Fabric for Minecraft 26.1.2 and use Java 25.
2. Close Minecraft and place the standalone Sky-Myce 2.5.0 JAR in the selected instance's `mods` folder alongside its Fabric dependencies. Remove the old Sky-Myce Loader and old Sky-Myce JAR if migrating; retain unrelated mods and settings.
3. Launch Minecraft with Fabric, then open `/sm`. Choose Connect Discord only when you want community services.
4. Enable the features you want in the config.

### Mods folder

- Windows: `%appdata%\.minecraft\mods`
- macOS: `~/Library/Application Support/minecraft/mods`
- Linux: `~/.minecraft/mods`

## Common commands

- `/sm ah` — auction browser
- `/sm dungeon` — dungeon tracker
- `/sm pf` — dungeon friends / LFG
- `/sm digest` — daily digest
- `/sm journal` — persistent personal RNG history and observed trends
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

- Install the standalone mod once; updates are opt-in, signature/hash checked and staged for a safe restart. Expired/unavailable update metadata does not stop the installed mod.
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
