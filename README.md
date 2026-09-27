# Sky-Hawk

Sky-Hawk is a modern Fabric mod for Hypixel SkyBlock built to streamline the grind, reduce wasted clicks, and keep important information in view while you play. It is a fork of the SkyMyce ecosystem, tuned toward clean quality-of-life improvements, dungeon utility, and better social coordination.

Open the in-game config with `/skymyce` or `/sm`.

New to the mod? Follow the [player manual](MANUAL.md) for a step-by-step walkthrough of every feature.

Settings now use five searchable categories with expandable advanced options and shared accent/opacity controls. See the [settings and Party Finder matcher guide](docs/settings-party-finder.md).

The integrated **2.4.2** upgrade adds a paged auction index and item browser (`/sm ah`), a four-view dungeon dashboard (`/sm dungeon`), permanent personal RNG collections, inherited global/widget themes, server-owned visual cosmetics, and acknowledged relay private messages. Read the [auction/tracker/theme guide](docs/auction-tracker-themes.md), [cosmetics and messaging setup](docs/cosmetics-messaging.md), and [validation checklist](docs/upgrade-validation.md). Existing saves and command roots remain compatible; cosmetics require an operator-configured relay/Discord application.

**2.4.3** discovers cosmetic names from cached friends, party members and chat senders across lobbies. Apply the signed update through the [Sky-Myce Loader](docs/LOADER.md#updates-and-cached-artifacts).

**2.4.5** adds `/m7pb [player]` (also `/sm m7pb`) for a local, selected-profile M7 S+ PB lookup; omit the name for yourself. It reuses cached stats and existing relay/SkyBlockPv/SkyBlocker/API providers, waits for a result automatically, and reports hidden or unavailable stats without opening a menu. Cosmetic sizes stay stable during lease renewal, background lookups and temporary offline grace. Loader **1.0.4** opens a matching desktop setup window before Minecraft initializes: code → Confirm and start → automatic verified installation and game launch. Startup updates need no manual restart; the owo-lib main-menu Loader screen remains available.

## Why play with Sky-Hawk?

Sky-Hawk is designed for players who want a smoother SkyBlock experience without sacrificing clarity or speed. Instead of forcing you to memorize every timer, state, or action, it quietly surfaces the information you need and automates the repetitive parts of the game.

From dungeon prep and chest safety to party coordination and daily tracking, Sky-Hawk brings together a wide set of tools that feel useful in real gameplay rather than decorative.

---

## Feature Overview

### General Quality of Life

- Daily Digest (`/sm digest`)  
  A cached, once-per-local-day dashboard for news, mayor and event updates, personal dailies, and optional RNG activity.

- Client Info Widget  
  Displays useful client-side information in a compact widget so you can keep track of your session without opening extra menus.

- Auto Refill  
  Automatically refills items from your sack when you run out, saving time and reducing annoying interruptions during combat or progression tasks.

- Skill XP Widget  
  Moves XP gain notifications from the action bar into a cleaner, easier-to-read widget.

- Ability Cooldown Widget  
  Shows the cooldown of your currently held item so you can manage rotations and timing more effectively.

- Party Commands  
  Adds useful party-focused commands using the `!` prefix, making coordination cleaner and faster.

- Stash Helper  
  Highlights and blocks clicks for worthless stash items so you avoid accidental junking and speed up sorting.

- Wardrobe / Loadout Keybinds  
  Lets you bind quick slot swaps while in your wardrobe or loadout, making equipment changes feel immediate.

### Instance & Dungeon Features

- **Party Finder Matcher**
  Dims chest listings that do not match your selected classes or floor-specific PB requirements. Slots remain visible and clickable; matching does not join parties automatically.

- Notify Missing Players  
  Warns you when players are missing when joining an instance, helping prevent disorganized or failed runs.

- Auto Requeue  
  Automatically requeues you at the end of an instance so you can jump back in without the extra menu friction.

- Dungeon Win Screen  
  Displays an animated run summary when a dungeon ends, making post-run results more satisfying and readable.

- Dungeon Message Filter  
  Filters out noisy or distracting dungeon chat messages so you can focus on what matters.

- Safe Chest Reroll  
  Blocks accidental chest rerolls when the chest contains something valuable, with configurable safeguards.

- Dungeon Chest Profit  
  Shows the value of items while opening dungeon chests, helping you make smarter decisions on the fly.

- Dungeon Friends & LFG (`/skymyce pf` or `/sm pf`)  
  Shows online friends with floor eligibility, class and Catacombs levels, personal bests, and quick party invites or messages.

### Dungeon Tracker

- Tracker Screen (`/skymyce dungeon`)  
  Overview, searchable/sortable Loot, dated RNG Timeline and a personal collection Museum, with floor, mode, date and session filters. Legacy all-time totals remain accessible.

- Tracker Widget  
  Shows a summary of your dungeon tracker while in the dungeon hub or at the end of a run so your stats are always visible.

### Social & Party Planning

- Friend-based dungeon coordination  
  Lets you track who is online, who is eligible, when they last played, and what class or floor they can cover.

- LFG flow  
  Send requests, check availability, and coordinate party selection directly from the mod interface without having to manually message everyone.

- Relay-backed communication  
  `/sm msg <ign> <message>` and `/sm reply` (`/sm r`) provide private friend conversations with recipient acknowledgements, separately from LFG controls. `/sm chat <ign>` routes normal typed chat to that friend until `/sm chat` or a Hypixel `/chat <channel>` switch. Messages never silently fall back to Hypixel chat.

- Visual cosmetics (`/sm cosmetics`)
  Authenticated Discord account linking controls styled custom names and independent X/Y/Z visual player sizes. Only enabled, supporting Sky-Hawk clients see them; real identities remain authoritative. See [player commands, channel permissions and operator setup](docs/cosmetics-discord.md).

---

## A Mod Built for SkyBlock Efficiency

Sky-Hawk is focused on the parts of SkyBlock that usually create friction:

- long menu loops
- wasted inventory actions
- missed dungeon setup cues
- low-visibility party coordination
- repetitive cleanup and chest-checking
- information scattered across multiple game screens

Instead of turning the game into a cluttered overlay, the mod aims to make the interface more useful, cleaner, and more responsive while keeping performance lightweight.

---

## Quick Start

1. Install the latest Fabric version for your Minecraft setup.
2. Download the Sky-Hawk mod jar from the releases page.
3. Place the jar in your Minecraft `mods` folder.
4. Launch the game with your Fabric profile.
5. Press `/skymyce` or `/sm` to open configuration.

### Minecraft Mods Folder

- Windows: `%appdata%\.minecraft\mods`
- macOS: `~/Library/Application Support/minecraft/mods`
- Linux: `~/.minecraft/mods`

---

## Dependencies

Sky-Hawk uses the following libraries:

- SkyblockAPI
- SkyBlockPv (bundled)
- Resourceful Config
- owo-lib (not bundled)

---

## Project Notes

This project is a fork of SkyMyce and continues the mod’s focus on practical QOL improvements for Hypixel SkyBlock. It aims to remain modern, fast, and player-friendly without feeling bloated or noisy.

---

## Build

To build the project locally:

```bash
./gradlew build
```

On Windows:

```powershell
gradlew.bat build
```

---

## License

This project is licensed under the GNU GPLv3 license. See the `LICENSE` file for details.
