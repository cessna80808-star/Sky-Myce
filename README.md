# Sky-Myce

Sky-Myce now uses a signed Fabric loader, Discord-linked accounts, installation-bound licenses, and a live Discord IRC bridge. See the [operator and security guide](docs/secure-system.md), [loader lifecycle](docs/LOADER.md), and [Discord bot setup](discord-bots/README.md). The protected mod requires the loader; first activation installs the verified payload for the next Minecraft launch.

Sky-Hawk is a Fabric mod for Hypixel SkyBlock focused on smoother dungeon runs, cleaner party coordination, and less menu friction.

Open the config in-game with `/sm` or `/skymyce`.

New to the mod? Read the [player guide](MANUAL.md).

The menu has six destinations: Dungeons, Combat, Economy, Cosmetics, Utils and Settings. Search finds controls across them; Quick Settings, Favorites and Recent retain useful shortcuts. See the [menu and measured performance changes](docs/menu-optimization-2026-10.md) and [settings and Party Finder matcher guide](docs/settings-party-finder.md).

**2.4.28** restores visible historical dungeon totals and direct access to saved loot, XP and reports, fixes Master Mode run completion and dungeon profile continuity, and observes direct inventory reward packets. New measurements remain separate from preserved history. It retains 2.4.27's menu, performance and chat changes. `/sm irc` selects global chat, `/sm irc <message>` sends once, and `/sm chat` returns to normal chat. `/sm pc` fails closed until the backend can verify current party membership; see [party chat availability](docs/party-chat.md) and [tracker cutover](docs/dungeon-tracker-cutover.md).

The persistent User Bot Gateway runs on Cloudflare. A real Minecraft ↔ Discord test passed, including reply context and mention presentation. The [implementation and deployment report](docs/implementation-overhaul-2026-10.md) records exact production versions, measured improvements, checks and remaining live-test limits.

## Highlights

- Dungeon tracker and widget
- Party Finder matcher and missing-player alerts
- Auto requeue, chest protection, and chest-profit hints
- Daily digest, auto refill, stash helper, and wardrobe/loadout keybinds
- Relay-backed friend messages and optional cosmetic names

## Quick start

1. Install Fabric for Minecraft 26.1.2 and use Java 25.
2. Place the authorized `Sky-Myce-Loader.jar` in your `mods` folder.
3. Launch Minecraft, follow the loader's activation/update steps, then open `/sm`.
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
- `/sm msg <ign> <message>` — private relay message
- `/sm chat <ign>` — chat relay
- `/sm irc [message]` — select global chat or send once
- `/sm pc [message]` — party mode, unavailable until membership is verified
- `/sm chat` — return to normal chat
- `/sm cosmetics` — cosmetic settings
- `/sm m7pb [player]` — local M7 PB lookup

## Current updates

- 2.4.5 adds the local M7 PB lookup and updated loader flow.
- The protected mod requires the loader to install and verify signed updates. See [docs/LOADER.md](docs/LOADER.md).
- Settings are organized into searchable categories with shared theme controls. See [docs/settings-party-finder.md](docs/settings-party-finder.md).

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
