# Sky-Myce Player Guide

Sky-Myce adds optional quality-of-life features to Hypixel SkyBlock. Configure the mod in-game with `/sm` or `/skymyce`; local features do not require a Discord connection.

## Install

1. Install Fabric for Minecraft **26.1.2** and use **Java 25**.
2. Download the [latest Sky-Myce release](https://github.com/cessna80808-star/Sky-Myce/releases/latest).
3. Close Minecraft and put the standalone JAR in your Fabric instance's `mods` folder. Keep the Fabric dependencies installed.
4. Launch Minecraft and open `/sm`.

Common `mods` folder locations:

- Windows: `%appdata%\.minecraft\mods`
- macOS: `~/Library/Application Support/minecraft/mods`
- Linux: `~/.minecraft/mods`

## Find your way around

The in-game menu has six areas: **Dungeons**, **Combat**, **Economy**, **Cosmetics**, **Utils**, and **Settings**. Use Search to find a setting; Quick Settings, Favorites, and Recent provide shortcuts. Enable only what you want.

## Features to try

- **Dungeons:** Party Finder Matcher, missing-player alerts, requeue, chest protection and profit estimates, routing (Blood routing only for Mage, Archer and Healer by default), and Healer Crush Camera controls.
- **Goldor (F7/M7 phase 3):** a terminal map with each class's job, step-by-step guidance from the gold-standard strategy, and timed pre-leaps. Sky-Myce users share short `smt1:` updates in party chat. Your next action is shown big at the top of the HUD (red to leap now, orange counting down to a leap, yellow to stack), with a title when a leap is due or a stack starts. Leap calls come 1 second early by default so you leap on time after reading them (Reaction Time in Dungeons → Goldor Coordination). Melody calls from other mods ("Melody 33%", "I ❤ Melody 1/3") count as that player's Melody progress. See [Goldor coordination](GOLDOR-COORDINATION.md).
- **Tracker:** review dungeon runs, loot, and personal RNG history; add the tracker widget to keep an overview visible. The widget can show your progress to the next Catacombs level. The **Levels** tab shows Catacombs and class progress toward a target, as runs, XP or a percentage.
- **Status bars (off by default):** separate health, mana, defense, speed, vitality and respiration bars. Arrange them with `/sm bars`: anchors around the hotbar, snapping, sizes, colours and per-bar options.
- **Overlays:** an NPC dialogue box with numbered options, a progress bar on the skill XP widget, and Enter to confirm Auction House, Bazaar and bank signs.
- **Daily and inventory tools:** Daily Digest, Auto Refill, stash assistance, and loadout keybinds.
- **Greenhouse Planner (off by default):** ranks mutations by estimated Bazaar profit, solves a full 10×10 Greenhouse layout and shows translucent ghost blocks on every slot with the wrong soil and, while you hold a crop, where it belongs, plus a HUD with progress and what is left to place. It never places anything for you. The ranking estimates how many of each mutation fit on your plot and ranks by that whole-plot profit. A layout stays pinned until you run `plan`, `auto`, `recompute` or `import`; price updates only refresh the estimate. When you anchor a plot that is already planted, the planner reads the crops there and pins them as the layout instead of solving a new one (`import` does this on demand). Mutations are recognised by their head texture; crops it still does not recognise are named from the recipe they complete, learned when you plant them while it watches, or named by looking at one and running `identify` (it offers the plot's recipe crops to click, then reads the plot again). Names for vanilla-looking crops, such as a young mutation that looks like a melon stem, only apply to the plot they were given on. Look at the soil of the corner slot and run `/sm greenhouse anchor` once per plot; rows run the way you face, columns to your right. Each anchored plot keeps its own layout, and the overlay follows the plot you are closest to. `/sm greenhouse missing` lists everything still needed, grouped by where it comes from: click a sack crop or soil to take it with `/gfs`, or a Bazaar crop to open `/bz`; the missing amount is typed into the Bazaar amount sign for you (and copied), so you only check it and click Done. Melon and Pumpkin Seeds are not sold, so the list takes Melon Slices (one per seed) and Pumpkins (four seeds each) from your sacks for you to craft, and the sacks' "Moved …" replies to these clicks are hidden so the list stays in view. Clicked buttons turn green with a tick, so you can see what you already fetched. With Block Wrong Clicks (on by default) a crop or soil is not planted in a slot the layout plans for something else; the action bar says what belongs there. Mutations whose base crops your Garden level has not unlocked yet (Sunflower and Moonflower at 11, Wild Rose at 12) are left out of the ranking and layouts; the level is read from the tab list, or set it under Garden Level. The Greenhouses screen has a Teleport button per plot (`/tptoplot`), and the unique-crop growth bonus is counted from your other saved plots. Typing `/bz` or `/gfs` also suggests the missing items. `/sm greenhouse gui` (or Greenhouse Planner in the menu) shows every plot: its layout, what is missing, growth timers from when each mutation appeared, and estimated and harvested profit per day, week, month and in total, with a 14-day history. Analyze a few mutations with the Plant Diagnostics Tool and the planner reads their growth stage, then estimates how far the whole plot has grown and when it will be fully grown. Grown mutations glow green in the Greenhouse and ones waiting on you (an asleep Snoozling: right-click it) glow yellow. The estimate follows each mutation's own rules: Glasscorn is ready at stage 7 and starts over after 8, and Fleshtrap, Thunderling, Timestalk and All-in Aloe can stop or reset, so they are only shown as grown once an analysis says so. Water and day/night are not modelled. Harvests are recorded while you are at the plot and valued at the price of the moment, so they are estimates too. Profit uses [skymutations.eu](https://skymutations.eu)'s formula (by palmaner); crop drops, copper and growth effects are not modelled, so treat the numbers as estimates.
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
| `/sm greenhouse` | Mutation profit ranking; click **[Plan]** to solve a layout. Also `help`, `gui`, `plan <mutation>`, `auto`, `recompute`, `import [mutation]`, `identify [crop]`, `anchor [name]`, `remove <name>`, `lock <row> <column>`, `missing` (or `shopping`) |
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

For programming and build instructions, see the [developer guide](AGENTS.md).
