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

- **Dungeons:** Party Finder Matcher, missing-player alerts, requeue, chest protection and profit estimates, routing (Blood routing only for Mage, Archer and Healer by default), and Healer Crush Camera controls.
- **Goldor (F7/M7 phase 3):** a terminal map with each class's job, step-by-step guidance from the gold-standard strategy, and timed pre-leaps. Sky-Myce users share short `smt1:` updates in party chat. See [Goldor coordination](GOLDOR-COORDINATION.md).
- **Tracker:** review dungeon runs, loot, and personal RNG history; add the tracker widget to keep an overview visible. The widget can show your progress to the next Catacombs level. The **Levels** tab shows Catacombs and class progress toward a target, as runs, XP or a percentage.
- **Status bars (off by default):** separate health, mana, defense, speed, vitality and respiration bars. Arrange them with `/sm bars`: anchors around the hotbar, snapping, sizes, colours and per-bar options.
- **Overlays:** an NPC dialogue box with numbered options, a progress bar on the skill XP widget, and Enter to confirm Auction House, Bazaar and bank signs.
- **Daily and inventory tools:** Daily Digest, Auto Refill, stash assistance, and loadout keybinds.
- **Greenhouse Planner (off by default):** ranks mutations by estimated Bazaar profit, solves a full 10×10 Greenhouse layout and shows translucent ghost blocks where the crop or soil you are holding belongs, plus a HUD with progress and what is left to place. It never places anything for you. Look at the soil of the corner slot and run `/sm greenhouse anchor` once; rows run the way you face, columns to your right. Profit uses [skymutations.eu](https://skymutations.eu)'s formula (by palmaner); crop drops, copper and growth effects are not modelled, so treat the numbers as estimates.
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
| `/sm bars` | Arrange the status bars |
| `/sm greenhouse` | Mutation profit ranking; click **[Plan]** to solve a layout. Also `plan <mutation>`, `auto`, `recompute`, `anchor`, `lock <row> <column>` |
| `/sm goldor` | Goldor status; `/sm goldor guide <class> <section>` for the full steps |
| `/sm graphics` | Show what the Auto graphics mode chose and why |
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

Community features are optional. To use them, open **Community** from the menu or run `/sm community`, then follow the Discord connection flow. One setting, **Community data sharing**, controls what you share: **On** shares dungeon stats, RNG drops, activity and leaderboard statistics with your Minecraft name on public reports; **On (anon)**, the default, shares the same anonymously; **Off** shares nothing and removes or suppresses your earlier contributions. Chat and cosmetics work in every state.

Global Chat opens with `/sm irc`. The chat screen supports replies and reactions; use its message controls for actions on your own messages. `/sm chat <ign>` temporarily routes normal chat to that friend. Run `/sm chat` or switch Hypixel chat channels to stop routing. Check the selected mode before sending a message.

Automatic updates are **Off** by default. You can check for and stage a signed update with **Update Now** in Community; restart Minecraft to apply it. If community services are unavailable, your installed mod and local features continue to work.

## Graphics and performance

**Settings → Appearance** has two controls: **Theme** (a preset palette for menus and HUD widgets) and **Graphics mode**:
- **Auto** (the default) lowers Sky-Myce's own menu effects while your frame rate stays under 60 FPS, and restores them once frames are steady again.
- **Extremely Light**, **Light**, **Medium** and **High** are fixed. GUI animations are off in Extremely Light and Light; Auto keeps Medium animations.
- HUD information, dungeon tracking, routes and the Crush Camera are never reduced, and Minecraft's own settings are not changed.

## If something goes wrong

- Confirm your Minecraft, Fabric, and Java versions match the requirements above.
- Check the feature's setting in `/sm` and make sure you installed the JAR in the correct game instance.
- If a community feature is unavailable, confirm your connection in `/sm community`; local features do not depend on it.
- If a feature is unclear or broken, include the mod version, Minecraft version, and relevant `logs/latest.log` details when reporting it. Remove account names, tokens, or other private information first.

For programming and build instructions, see the [Developer Manual](DEV_MANUAL.md).
