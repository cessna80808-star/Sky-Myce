# Sky-Myce Player Guide

Sky-Myce adds optional quality-of-life features to Hypixel SkyBlock. Configure the mod in-game with `/sm` or `/skymyce`; local features do not require a Discord connection.

## Install

1. Install Fabric for Minecraft **26.1.2** and use **Java 25**.
2. Download the [latest Sky-Myce release](https://github.com/cessna80808-star/Sky-Myce/releases/latest).
3. Close Minecraft and put the standalone JAR in your Fabric instance's `mods` folder. Keep the Fabric dependencies installed.
4. If migrating from the old Loader setup, remove the old Sky-Myce Loader and mod JAR. Keep unrelated mods and settings.
5. Launch Minecraft and open `/sm`.

Common `mods` folder locations:

- Windows: `%appdata%\.minecraft\mods`
- macOS: `~/Library/Application Support/minecraft/mods`
- Linux: `~/.minecraft/mods`

## Find your way around

The in-game menu has six areas: **Dungeons**, **Combat**, **Economy**, **Cosmetics**, **Utils**, and **Settings**. Use Search to find a setting; Quick Settings, Favorites, and Recent provide shortcuts. Enable only what you want.

## Features to try

- **Dungeons:** Party Finder Matcher, missing-player alerts, requeue, chest protection and profit estimates, routing, and Healer Crush Camera controls.
- **Tracker:** review dungeon runs, loot, and personal RNG history; add the tracker widget to keep an overview visible.
- **Daily and inventory tools:** Daily Digest, Auto Refill, stash assistance, and wardrobe/loadout keybinds.
- **Cosmetics:** save local name, size, and cape choices. Sharing an appearance with the community is a separate optional action.
- **Community:** connect Discord only if you want community features such as Global Chat, shared rankings, cosmetics sharing, or signed updates.

The Healer Crush Camera has saved viewpoints and a preview for setup; configure it in `/sm crush-camera`. Test and calibrate it before relying on it in a live run.

## Common commands

| Command | What it does |
| --- | --- |
| `/sm` or `/skymyce` | Open the mod menu |
| `/sm dungeon` | Open the dungeon tracker |
| `/sm pf` | Open dungeon friends / LFG |
| `/sm ah` | Open the auction browser |
| `/sm digest` | Open Daily Digest |
| `/sm journal` | View your personal RNG history |
| `/sm leaderboard` | View community rankings and their coverage |
| `/sm cosmetics` | Open Cosmetics Studio |
| `/sm crush-camera` | Configure and preview camera viewpoints |
| `/sm community` | Manage optional Discord connection, sharing, and updates |
| `/sm irc` | Open Global Chat |
| `/sm irc <message>` | Send one Global Chat message |
| `/sm msg <ign> <message>` | Send a private relay message |
| `/sm reply` or `/sm r` | Reply to the last private message |
| `/sm chat <ign>` | Start routing normal chat to a friend |
| `/sm chat` | Stop friend-chat routing and return to normal chat |
| `/sm m7pb [player]` | Look up a selected-profile M7 personal best |

`/sm pc` is unavailable because current Hypixel party membership cannot be verified by the community service. Party Finder tools are separate and remain available.

## Chat, sharing, and updates

Community features are optional. To use them, open **Community** from the menu or run `/sm community`, then follow the Discord connection flow. Sharing controls let you opt out of community data categories without turning off chat or cosmetics.

Global Chat opens with `/sm irc`. The chat screen supports replies and reactions; use its message controls for actions on your own messages. `/sm chat <ign>` temporarily routes normal chat to that friend. Run `/sm chat` or switch Hypixel chat channels to stop routing. Check the selected mode before sending a message.

Automatic updates are **Off** by default. You can check for and stage a signed update with **Update Now** in Community; restart Minecraft to apply it. If community services are unavailable, your installed mod and local features continue to work.

## If something goes wrong

- Confirm your Minecraft, Fabric, and Java versions match the requirements above.
- Check the feature's setting in `/sm` and make sure you installed the JAR in the correct game instance.
- If a community feature is unavailable, confirm your connection in `/sm community`; local features do not depend on it.
- If a feature is unclear or broken, include the mod version, Minecraft version, and relevant `logs/latest.log` details when reporting it. Remove account names, tokens, or other private information first.

For programming and build instructions, see the [Developer Manual](DEV_MANUAL.md).
