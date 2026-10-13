# Sky-Myce Player Guide

Sky-Myce is a player-focused Fabric mod for Hypixel SkyBlock. It helps you spend less time managing menus and more time playing.

This guide covers the essentials: installation, the most useful features, and the commands you will use most often.

## 1) Install and open

1. Install Fabric for Minecraft 26.1.2 and use Java 25.
2. Close Minecraft, remove an old Sky-Myce Loader/mod if migrating, and put the standalone Sky-Myce 2.5.1 JAR in the selected instance's `mods` folder alongside its Fabric dependencies. Keep unrelated mods and settings.
3. Launch the game with your Fabric profile.
4. Open the config with `/sm` or `/skymyce`.

Local features need no Discord connection, approval, license or activation key.
For community services choose Connect Discord in Minecraft, complete the browser
flow, or submit its one-time fallback code to the existing User Bot's `/connect`.
Community Sharing settings offer global/category/name opt-outs without disabling
chat or cosmetics. Optional signed updates are staged for a restart; canceling
them or losing service access does not stop the installed mod.
Auto-update starts **Off** and saves your On/Off choice across restarts. Use
**Update Now** for an explicit update; turning auto-update Off cancels automatic
staging.

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
- Healer Crush Camera: a separate live view for the F7/M7 purple-to-yellow sequence, with saved viewpoints and a singleplayer preview. Open `/sm crush-camera` to calibrate the saved viewpoints. Automatic dungeon behavior still needs live validation.

### Dungeon tracking

- Dungeon Tracker: keeps your dungeon runs, loot, RNG, and progression in one place.
- Tracker Widget: keeps a quick overview visible while in the hub or after a run.

### Social and party coordination

- Dungeon Friends / LFG: see who is online, available, and eligible for a run.
- Relay-backed private messages: use `/sm msg`, `/sm reply`, and `/sm chat` for friend coordination.
- Cosmetic names: optional styled names and visual size choices for supported setups.

### Global Chat

Open Global Chat from Home or `/sm irc`. The existing Minecraft chat composer keeps your draft. Click **Reply** on a message to focus it immediately; the parent preview shows your reply target, and **Cancel** removes the target while keeping your draft. Click **React** for a small anchored picker with six common reactions and the current counts; choose a reaction you have already added to remove yours. Owned messages retain their edit/delete controls within the backend's management window.

Unicode joined sequences and custom emoji IDs remain intact when relayed. Native chat shows readable fallback names when it cannot display the emoji. GIF text, attachments and delayed embeds update one canonical message instead of creating duplicate chat entries. Use the existing media viewer or its browser fallback.

### Local Cosmetics Studio

Open `/sm cosmetics` to adjust your name, nametag size, body axes and cape with the actual-player preview. **Equip and save locally** works without Discord and persists for the selected Minecraft account. Packaged presets and validated 64×32 PNG imports are reusable; imports are copied into the local library. **None / Remove cape** unequips the cape without deleting its PNG. **Delete selected PNG from library** is a separate confirmed action.

The name JSON editor accepts bounded inert Minecraft text components, including arrays and per-character colors/styles. **Publish appearance to community** is a separate action requiring the owner's current authenticated community session.

### Administrator RNG tester

Administrators can open `/sm rng-test` for **Run dry-run** or **Test mock delivery**. Every test requires a fresh authoritative check of the selected account's current community session and persisted administrator status; opening the screen or being offline grants no access. Fixtures are marked TEST and do not change the personal journal, tracker, shared rankings or production Discord feed.

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
- `/sm journal` — Personal RNG history and observed trends
- `/sm rng-test` — Administrator-only RNG dry-run and mock tester
- `/sm leaderboard` — Community rankings and metric coverage
- `/sm community` — Discord connection, sharing and signed updates
- `/sm msg <ign> <message>` — Send a relay-backed DM
- `/sm reply` or `/sm r` — Reply to the last message
- `/sm chat <ign>` — Route normal typing to that friend until you switch channels
- `/sm irc [message]` — Open Global Chat or send one message
- `/sm chat` — Return to normal chat
- `/sm cosmetics` — Cosmetic settings
- `/sm crush-camera` — Camera calibration, preview and HUD controls
- `/sm m7pb [player]` — Local selected-profile M7 PB lookup

## 4) Recommended setup

A good default setup is:

- enable dungeon safety and profit tools
- turn on Party Finder Matcher and Missing Player Alerts
- keep the tracker widget visible
- enable Daily Digest and Auto Refill
- configure any HUD widgets and theme settings you like

## 5) Settings and customization

Open `/sm` and browse Dungeons, Combat, Economy, Cosmetics, Utils and Settings. Each feature has one canonical destination; Search, Quick Settings, Favorites and Recent point to the same controls. Home also opens Community and the existing Global Chat composer directly. Press Escape to return from a feature/settings page to its category, then Home, then the game. An open anchored dropdown closes before Escape leaves the page.

Settings remain searchable for dungeon behavior, widgets, themes and party tools. The Community page contains the persisted auto-update On/Off choice and the separate Update Now action.

See also:

- [README.md](README.md)

## 6) Tips

- Keep the dungeon tracker widget on if you want quick run feedback.
- Use the Party Finder Matcher before joining lower-effort runs to save time.
- Turn on protection for chest rerolls if you want fewer accidental losses.
- Use relay messaging for coordination rather than relying on normal chat when a run is in progress.

## 7) Support and troubleshooting

If a feature does not work as expected:

- confirm you are on the correct Minecraft/Fabric version
- check the mod config in `/sm`
- check for an optional signed mod update in-game; local features remain available if the update/community service is offline
- review the project docs for the relevant feature area

If a feature feels noisy, disable it in the config and keep the rest of the toolset active. Local fixtures and fullscreen observations do not establish live Discord delivery, server deployment or automatic dungeon-camera activation.
