# Sky-Hawk

Sky-Hawk is a Fabric mod for Hypixel SkyBlock focused on smoother dungeon runs, cleaner party coordination, and less menu friction.

Open the config in-game with `/sm` or `/skymyce`.

New to the mod? Read the [player guide](MANUAL.md).

## Highlights

- Dungeon tracker and widget
- Party Finder matcher and missing-player alerts
- Auto requeue, chest protection, and chest-profit hints
- Daily digest, auto refill, stash helper, and wardrobe/loadout keybinds
- Relay-backed friend messages and optional cosmetic names

## Quick start

1. Install Fabric for your Minecraft version.
2. Place the Sky-Hawk jar in your `mods` folder.
3. Launch the game and open `/sm`.
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
- `/sm cosmetics` — cosmetic settings
- `/sm m7pb [player]` — local M7 PB lookup

## Current updates

- 2.4.5 adds the local M7 PB lookup and updated loader flow.
- The loader remains the recommended way to install and verify signed updates. See [docs/LOADER.md](docs/LOADER.md).
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
