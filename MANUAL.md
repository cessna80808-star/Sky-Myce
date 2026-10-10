# Sky-Hawk Player Guide

Sky-Hawk is a lightweight, player-focused Fabric mod for Hypixel SkyBlock. It helps you spend less time managing menus and more time playing.

This guide covers the essentials: installation, the most useful features, and the commands you will use most often.

## 1) Install and open

1. Install Fabric for your Minecraft version.
2. Put the Sky-Hawk mod jar in your Minecraft `mods` folder.
3. Launch the game with your Fabric profile.
4. Open the config with `/sm` or `/skymyce`.

### Mods folder

- Windows: `%appdata%\.minecraft\mods`
- macOS: `~/Library/Application Support/minecraft/mods`
- Linux: `~/.minecraft/mods`

## 2) Best features to start with

### Dungeon tools

- Party Finder Matcher: highlights party listings that do not match your class or floor/PB requirements.
- Missing Player Alerts: warns you before a dungeon run is missing a needed player.
- Auto Requeue: jumps you back into the queue with less friction.
- Safe Chest Reroll: blocks risky rerolls on valuable chest drops.
- Dungeon Chest Profit: helps estimate the value of chest rewards while opening them.

### Dungeon tracking

- Dungeon Tracker: keeps your dungeon runs, loot, RNG, and progression in one place.
- Tracker Widget: keeps a quick overview visible while in the hub or after a run.

### Social and party coordination

- Dungeon Friends / LFG: see who is online, available, and eligible for a run.
- Relay-backed private messages: use `/sm msg`, `/sm reply`, and `/sm chat` for friend coordination.
- Cosmetic names: optional styled names and visual size choices for supported setups.

### Quality of life

- Daily Digest: a quick daily summary of relevant info and personal activity.
- Auto Refill: refills key items from your sack automatically.
- Stash Helper: helps avoid junking useful items by accident.
- Wardrobe / Loadout Keybinds: lets you swap gear faster.

## 3) Useful commands

- `/sm ah` — Auction browser
- `/sm dungeon` — Dungeon tracker
- `/sm pf` — Dungeon friends / LFG
- `/sm digest` — Daily digest
- `/sm msg <ign> <message>` — Send a relay-backed DM
- `/sm reply` or `/sm r` — Reply to the last message
- `/sm chat <ign>` — Route normal typing to that friend until you switch channels
- `/sm cosmetics` — Cosmetic settings
- `/sm m7pb [player]` — Local selected-profile M7 PB lookup

## 4) Recommended setup

A good default setup is:

- enable dungeon safety and profit tools
- turn on Party Finder Matcher and Missing Player Alerts
- keep the tracker widget visible
- enable Daily Digest and Auto Refill
- configure any HUD widgets and theme settings you like

## 5) Settings and customization

Open `/sm` and browse the categories. Sky-Hawk keeps settings organized and searchable so you can quickly find items such as dungeon behavior, widgets, themes, and party tools.

See also:

- [README.md](README.md)
- [docs/settings-party-finder.md](docs/settings-party-finder.md)
- [docs/LOADER.md](docs/LOADER.md)

## 6) Tips

- Keep the dungeon tracker widget on if you want quick run feedback.
- Use the Party Finder Matcher before joining lower-effort runs to save time.
- Turn on protection for chest rerolls if you want fewer accidental losses.
- Use relay messaging for coordination rather than relying on normal chat when a run is in progress.

## 7) Support and troubleshooting

If a feature does not work as expected:

- confirm you are on the correct Minecraft/Fabric version
- check the mod config in `/sm`
- verify you have the latest signed loader update if applicable
- review the project docs for the relevant feature area

Sky-Hawk is meant to be practical, low-noise, and helpful in real gameplay, not a cluttered overlay. If a feature feels noisy, disable it in the config and keep the rest of the toolset active.
