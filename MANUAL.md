# Sky-Myce Player Guide

Sky-Myce is a player-focused Fabric mod for Hypixel SkyBlock. It helps you spend less time managing menus and more time playing.

Use `/chat irc` (or `/sm chat irc`) for Sky-Myce IRC. `/chat p` and `/chat a` leave IRC and keep their normal server behavior. An unavailable IRC connection shows an error and never sends the message publicly. Discord's `/account status`, `/account link`, `/account unlink`, and `/account disconnect` manage your own community account. Multiple Minecraft accounts and computers are supported; hardware resets and administrator approval are not required.

This is the practical, player-facing guide to Sky-Hawk. Follow the steps in order the first time. You do **not** need to configure every feature: turn on only what you want.

Sky-Hawk is a client-side Fabric mod for Hypixel SkyBlock. It adds menus, HUD information, safeguards, convenience actions and optional social tools. It does not make Hypixel approve those features, and it does not guarantee that every feature will work if Hypixel changes its menus or chat.

## 1. Install and open the mod

1. Check the download/release page for the Minecraft version, Fabric Loader version and dependencies required by that release. Do not mix jars made for different Minecraft versions.
2. Install Fabric Loader for Minecraft 26.1.2 and use Java 25.
3. Close Minecraft, remove an old Sky-Myce Loader/mod if migrating, and put the standalone Sky-Myce 2.5.0 JAR in the selected instance's `mods` folder. Keep unrelated mods and settings:
   - Windows: `%appdata%\.minecraft\mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`
4. Install any dependencies the release page marks as required. Some libraries, including `owo-lib`, are bundled in the Sky-Myce JAR.
5. Start Minecraft using the Fabric profile. Join Hypixel SkyBlock.
6. Open chat and run **`/sm`** or **`/skymyce`**. Both command roots do the same thing. You can also press **Right Shift** (the default settings key).
7. If a feature is not working, first check that it is enabled in settings and that you are on SkyBlock.

Local features need no Discord connection, approval, license or activation key.
For community services choose Connect Discord in Minecraft, complete the browser
flow, or submit its one-time fallback code to the existing User Bot's `/connect`.
Community Sharing settings offer global/category/name opt-outs without disabling
chat or cosmetics. Optional signed updates are staged for a restart; canceling
them or losing service access does not stop the installed mod.

The in-game settings have five tabs: **General**, **Party Finder**, **Dungeons & Combat**, **Social & Sharing**, and **Theme & Appearance**. Use the search box in the current tab to find a setting. Expand **More options** to show less commonly used settings. Hover a setting for its explanation. You can change the settings key inside the config.

## 2. The short list: commands you will actually use

Type these in Minecraft chat. Do not type the angle brackets shown around placeholders; replace the placeholder with your own value.

| Type this | What happens |
| --- | --- |
| `/sm` | Open settings. |
| `/sm digest` | Open the Daily Digest. |
| `/sm ah` | Open the auction browser. |
| `/sm dungeon` | Open your dungeon tracker. |
| `/sm pf` | Open the dungeon-friends / LFG screen. |
| `/sm friends` | Open the friends, wealth and gear-lending menu. |
| `/sm loans` | Open the lending tab directly. |
| `/sm hud` | Move, resize and configure HUD widgets. |
| `/sm cosmetics` | Open your cosmetics screen and see your linked settings. |
| `/sm cosmetics link` | Start linking your Minecraft account to Discord. |
| `/sm msg <friend> <message>` | Send a private message using the Sky-Hawk relay, if available. |
| `/sm reply <message>` or `/sm r <message>` | Reply to the last valid relay conversation. |
| `/sm chat <friend>` | Send normal typed chat to that friend through the relay until you leave relay chat. |
| `/sm chat` | Leave relay chat. |
| `/sm modules` | Show the mod's internal module status; mostly useful for troubleshooting. |

`/skymyce` works in place of `/sm` for these commands. For example, `/skymyce ah` opens the same auction browser.

**Important:** `/sm msg`, `/sm reply` and `/sm chat` are relay features, not Hypixel `/msg` or `/r`. They need an available relay and a permitted friend identity. A failed private send does not fall back to public Hypixel chat. Use `/sm chat` or a Hypixel `/chat <channel>` command to leave relay chat.

## 3. Recommended first-time setup

1. Open **`/sm`** and browse each settings tab once.
2. In **General**, enable only the HUD and quality-of-life features you want. Configure keybinds before using loadout shortcuts.
3. In **Party Finder**, pick your classes. You can leave the class list empty to use your currently detected class.
4. In **Dungeons & Combat**, check the safety options and choose the message filter you prefer.
5. In **Theme & Appearance**, select a preset if you want a different look.
6. Type **`/sm hud`** and move any widgets that overlap your normal HUD.
7. Join a dungeon or mining area and check that the relevant feature appears. If a feature depends on a third-party relay or API, it may show unavailable until that service is set up.

## 4. General features

Settings are under **General** (some have their own named subsection).

| Feature | How to use it | Notes |
| --- | --- | --- |
| **Client Info widget** | Enable **Display Client Info**. It shows FPS, current/average ping, TPS and your coordinates. | Move it with `/sm hud`. |
| **Skill XP widget** | Leave **Display Skill XP** enabled. On XP gain, the mod shows the skill XP text in a widget instead of the usual action-bar XP display. | The notification is brief; it appears when XP is gained. |
| **Ability cooldown widget** | Leave **Display Ability Cooldown** enabled and use an item with a recognized ability. | Tracks cooldowns the mod can read from item data; it cannot know every custom ability. |
| **Ability-ready notification** | Enable **Display Ability Notification** if you want a brief notice when a tracked cooldown finishes. | Separate from the cooldown widget. |
| **Auto Refill** | Choose the item types in the **Auto Refill** setting. When one gets low, the mod requests more from your sack with `/gfs`. | Supported items: Ender Pearls, Superboom TNT and Spirit Leaps. You need the item in your sack and the server must accept the command. |
| **Stash Helper** | Open your stash. Items below the configured value threshold are shaded red. | With **Block Useless Clicks** on, clicking a shaded item is blocked. Hold **Ctrl** to override. Pricing/unknown item data can affect classification. |
| **Loadout shortcuts** | Enable **Loadout Keybinds**. While the loadout menu is open, press the configured key for a slot. **A** and **D** navigate pages. | Default slot keys: `4, 5, 6, R, T, Y, F, G, H, V, B, N`. You can change them. The currently equipped loadout is highlighted and auto-close is available. |
| **Wardrobe shortcuts** | With the keybind feature enabled, press the slot's configured key while in the wardrobe. | The slot keys select wardrobe slots too. Use the normal menu if a shortcut is unclear. |
| **Party commands** | Enable the individual `!` commands you want under **Party Commands**. A party member types the command in party chat. | See the dedicated command list below. Commands have a configurable cooldown; some actions only work for the party leader. |
| **Dev Mode** | Find it by searching `Dev Mode` in General. Leave it off unless troubleshooting. | Adds diagnostic messages; it is not required for normal use. |

### Party `!` commands

These are **party-chat messages**, not slash commands. For example, send `!warp` in party chat. The host must have the matching command enabled under **General → Party Commands → Enabled Commands**. The command runs through Hypixel's normal command system, so Hypixel permissions still apply.

| Party message | Purpose and limits |
| --- | --- |
| `!invite <name>` (`!inv`, `!i`) | Invite a player. Leader-only. |
| `!kick <name>` (`!k`) | Kick a player. Leader-only. |
| `!allinvite` (`!allinv`) | Toggle All Invite. Leader-only. |
| `!allinvite on` or `!allinvite off` | Explicitly set All Invite on/off. Leader-only. |
| `!disband` | Disband the party. Leader-only; use carefully. |
| `!warp` (`!w`) | Warp the party. Leader-only. |
| `!transfer [name]` (`!pt`, `!ptme`) | Transfer party leadership. With no name, targets the person who sent the command. Leader-only. |
| `!promote [name]` / `!demote [name]` | Change a member's party role. With no name, targets the sender. Leader-only. |
| `!kickoffline` (`!ko`) | Kick offline members. Leader-only. |
| `!downtime [reason]` (`!dt`) | Ask the party to stop before auto-requeue. The reason is optional. |
| `!f1`–`!f7`, `!m1`–`!m7`, `!fe` | Join the corresponding Catacombs instance. Leader-only. |
| `!t1`–`!t5` | Join the corresponding Kuudra tier. Leader-only. |
| `!coords` (also `!coord`, `!cords`, etc.) | Share your coordinates in party chat. |
| `!fps`, `!tps`, `!ping` | Share your FPS, server TPS or ping in party chat. |
| `!dungeon <floor>` | Share tracked run/profit summary for a floor, such as `!dungeon F7`. |
| `!dungeon <floor> xp` | Share tracked Catacombs/class XP totals. Add `avg` for XP per hour, e.g. `!dungeon F7 xp avg`. |
| `!pb [F1-F7 or M1-M7]` | Share your S+ personal best. If no floor is given, uses the current dungeon floor. Stats may need a moment to load. |

Only enable commands you trust your party to invoke. A configured cooldown reduces repeated command handling; it is not a general confirmation prompt.

## 5. Dungeons and instances

### Party Finder matcher

1. Open the in-game config and choose **Party Finder**.
2. Leave **Dim incompatible parties** on to shade listings that do not match.
3. Choose **Match open classes** and one or more **Classes I play** if desired. If no classes are selected, the matcher uses your detected current class.
4. Leave **Match note PB requirements** on if you want party notes with a time requirement checked against your cached PB.
5. Open the normal Party Finder chest.

Incompatible entries stay visible, hoverable and clickable. The mod **does not** click, join or message a party for you. Unknown class information is not treated as a mismatch; a stated PB requirement will not match while your PB is missing/loading.

### Dungeon Friends / LFG

Open **`/sm pf`**. Set your availability (floor, classes and optional PB requirement), review eligible online friends and use the screen's invite/message controls. Availability, message templates, saved party and per-friend class preferences are saved locally. A relay must be available for relay-mediated features. Opening this menu does not automatically join a party. Join/invite actions can be disabled when the party state, player eligibility or Hypixel permissions do not allow them.

### Dungeon tracker

Open **`/sm dungeon`**. The four views are:

- **Overview:** run counts, known duration, XP, chest costs, profit and other totals.
- **Loot:** search/sort recorded items and filter by chest or recorded value.
- **RNG Timeline:** inspect recorded rare drops by date and run.
- **RNG Museum:** view the supported rare-item catalog, your recorded drops, history and pins. This is a personal tracker, not Hypixel's Museum.

Use the filter panel to select floor/mode, profile scope, dates and sessions. **All-time** legacy totals may be unscoped because older records did not store profile ownership. For verified acquisition tracking, the mod needs to observe a matching inventory receipt; seeing a chat announcement or clicking a chest alone is not enough. Prices are estimates, not guaranteed sale proceeds. Unknown historical values/times remain unknown rather than being guessed.

The optional dungeon tracker HUD summary appears in the dungeon hub or after a run when enabled. See [the tracker guide](docs/auction-tracker-themes.md#dungeon-dashboard) for filters, migrations, archive behavior and precise metric definitions.

### Dungeon run helpers

- **Notify Missing Players:** warns when an instance is missing players.
- **Auto Requeue:** attempts to requeue after a dungeon or Kuudra run. It requires you to be the instance creator/leader. To let party members request a pause, enable the `downtime` party command; a downtime request cancels the next automatic requeue.
- **Dungeon Win Screen:** shows a run summary at the end.
- **Dungeon Message Filter:** choose which noisy dungeon messages to suppress.
- **Safe Kismet Reroll:** blocks rerolling a chest containing an item above the configured threshold. Hold **Ctrl** to override the block.
- **Dungeon Chest Profit:** shows estimated chest item values/profit while opening a dungeon chest.

Thresholds and toggles are in **Dungeons & Combat**. These safeguards are local UI checks: read the chest and confirm the action yourself.

## 6. Daily Digest and RNG tracking

Open **`/sm digest`**. The digest has four cards: **Daily News**, **Global Status**, **Personal Dailies** and **RNG Activity**. Select a card to open its detail view.

- You can enable the automatic opening in **General → Daily Digest**. It waits until SkyBlock and your profile load, then opens at most once per local computer day/account. Opening it manually also counts as today's opening. Disable automatic opening there if you do not want it.
- News displays cached game/Alpha announcements when the external news relay is configured. If it is unavailable, other digest cards remain usable.
- Mayor and event information comes from data the mod observes/caches. Unknown schedules remain unknown.
- Forge, experiments, Puzzler, Fetchur and dungeon activity are based on observed game events. Open the relevant game menu when the guide says it is needed to initialize/refresh observations.
- Some daily activities support a manual completion note. A note is not proof from Hypixel; it is your own reminder.
- Personal RNG history is stored locally. The community RNG feed is explicitly labelled **unverified**. Check **Social & Sharing → Daily Digest** to control sharing, receiving and whether reports include your public Minecraft name. Review these privacy switches before using the feature.
- Sharing does not send your chat or inventory. Community reports are not official drop-rate data. Kuudra and fishing drops are not currently claimed as supported by the detector.

News setup, RNG privacy details and exact data behavior are in the [Daily Digest guide](docs/daily-digest.md).

## 7. Auctions

Open **`/sm ah`**.

1. Type search words. All supplied words are searched against the available item name/category/lore.
2. Choose optional category, rarity, BIN/auction type and minimum/maximum price filters.
3. Choose a sort order. Save a search as a favorite if you want to reuse it later.
4. Select a result to inspect seller/item details and the normal Minecraft item tooltip.
5. **Open in Hypixel** uses Hypixel's normal `viewauction` flow. Check the auction and price in-game before deciding to bid or buy.

The browser indexes pages progressively, so a partial result is not the whole market. It is not an auto-buyer: no bids or purchases happen automatically. Refresh is rate-limited; reopening during cooldown uses the cached snapshot. See the [auction guide](docs/auction-tracker-themes.md#auction-browser) for coverage and stale-data details.

## 8. Mining tools

Find these options under **Mining**.

| Option | What it does |
| --- | --- |
| **Powder Tracker** | Tracks observed powder gains while mining. |
| **Crystal Hollows Info** | Shows Crystal Hollows day, player count, lobby/session time and lock/closing status in a HUD widget. Move it with `/sm hud`. |
| **Bal Notifier** | When enabled and you are in a party, sends party messages for Bal spawn, 75%, 50%, 25% and defeat events. |
| **Auto Nucleus Warp** | When enabled, types `/warp nuc` a random configured number of seconds after a crystal-found message. Set the minimum and maximum delay first. |

Auto Nucleus Warp sends a server command for you. If you do not want automatic chat commands, leave it off.

## 9. Friends, wealth and lending

Open **`/sm friends`** and use the tabs. The saved friend roster is local to your Minecraft account. Use the screen's refresh controls when you want updated information.

- Dungeon friend screens show online/eligibility and available dungeon stats. They do not promise that a friend is available or that their data is current.
- Wealth values are estimates, and missing sections may be unavailable because of provider/API limits. A manual refresh queues eligible saved friends; it does not magically bypass provider cooldowns.
- Lending/loans tools help track gear lending between friends. Check item/person details before recording or acting on a loan.
- Relay-backed messaging and LFG need a reachable relay and compatible clients. They are separate from Hypixel's own chat features.

For scanner behavior, freshness limits, profile handling and joining restrictions, use the [settings and Party Finder guide](docs/settings-party-finder.md#friends-and-wealth-refresh).

## 10. Cosmetics and relay messaging

These features are optional and require a configured Sky-Hawk relay. Cosmetics also require the existing Sky-Hawk Discord application to be configured by its operator. If you are just a player and linking is unavailable, you cannot enable it from the client alone.

To link cosmetics:

1. Join Hypixel with the updated mod and wait for the authenticated relay connection.
2. Run **`/sm cosmetics link`**. The mod shows a short code that expires after five minutes.
3. In the configured Discord **bot-commands** channel, run the bot's **`/cosmetics link`** command with that code. Use the Discord command, not a regular chat message. Keep the code private.
4. Run **`/sm cosmetics`** to view/refresh your settings.
5. Use the Discord `/cosmetics` commands to set a name, set individual size axes, reset or unlink. Those commands only edit your own linked Minecraft account.

Your own client has independent display switches for cosmetic names, original tooltip names and player scaling. Cosmetics are display-only for supporting clients: they do not change identity, hitboxes, reach, movement or packets. Other players need a supporting, enabled client to see the effect. Read the [cosmetics and messaging guide](docs/cosmetics-messaging.md) for safe name formatting, exact commands, privacy/identity behavior and operator setup.

Relay messages:

- `/sm msg <friend> <message>` sends a one-off relay private message.
- `/sm chat <friend>` selects a relay conversation; ordinary typed chat goes only to that friend until you run `/sm chat` or switch to a Hypixel channel with `/chat <channel>`.
- `/sm reply <message>` or `/sm r <message>` replies to the most recent valid conversation.
- LFG buttons/actions are separate from ordinary messages. A plain `yes` is not treated as an invitation acceptance.

Messages can fail if the recipient is offline, unsupported or not permitted, or if the relay is unavailable/rate-limited. The mod does not silently post a failed private message to public chat. Relay messages are not end-to-end encrypted.

## 11. Themes and HUD layout

1. Choose **Theme & Appearance → Shared theme**.
2. Pick **Dark glass**, **Minimal**, **Light** or **High contrast**.
3. Change colors/opacity and other shared appearance settings. Use **Inherit** to remove a one-off override.
4. Run **`/sm hud`**. Drag widgets to move them and use the editor's resize/anchor controls.
5. Right-click a widget to change only that widget's theme. Resetting its theme returns it to inherited appearance; it does not reset the widget's position.
6. Use theme **Export**/**Import** only with clipboard JSON you trust. Invalid data is rejected; it does not run scripts.

Settings and widget layouts save locally. See [the shared-theme guide](docs/auction-tracker-themes.md#shared-theme-and-hud) for inheritance and import/export rules.

## 12. What is automatic, and what is not

Sky-Hawk is primarily a reader, display and convenience layer. Here is the safest mental model:

- **No automatic party joining:** Party Finder dims listings; you choose and click.
- **No automatic auction buying:** the auction browser searches/inspects; Hypixel handles purchase confirmation.
- **No guaranteed historical data:** old or unavailable stats are labelled unknown/unscoped rather than fabricated.
- **Some features do send normal commands when you enable them:** Auto Refill, Auto Requeue, Auto Nucleus Warp, shortcuts and enabled party `!` actions. Turn those options off if you want every action to be manual.
- **Not every feature is standalone:** News, relay messages, LFG and cosmetics require a working external relay; Discord linking requires operator setup. API-backed player/price data depends on the available provider and cache.
- **Game updates can break parsers:** features that read English game chat/menu text may become unavailable after Hypixel changes it. Unknown/unavailable is preferable to a made-up result.

### Legacy joke behavior

The old `/sm calc` command is not a calculator: it prints a joke and flips an internal flag. A separate legacy prank behavior can disconnect your client if a nearby player has certain special head items while that flag is off. There is no normal settings toggle for this behavior; avoid experimenting with `/sm calc` during a live session.

There is also a creator easter egg: the first time the account `Cessna808` becomes ready in a Catacombs run, the client briefly shows an image and plays a sound. It is local-only and remembered on that installation.

## 13. Quick troubleshooting

If a feature does not work as expected:

- Confirm you are on the correct Minecraft/Fabric version.
- Check the mod config in `/sm`.
- Check for an optional signed mod update in-game; local features remain available if the update/community service is offline.
- Review the project docs for the relevant feature area.

**`/sm` is unknown**

- Check that Minecraft started with the Fabric profile and Sky-Hawk jar is in the active instance's `mods` folder.
- Confirm the mod jar matches your exact Minecraft version.
- Check the Minecraft log for a missing required dependency or a mod-loading error.

**A menu opens but shows no data**

- Join Hypixel SkyBlock and wait for the player/profile to finish loading.
- Check whether that feature needs an external relay or API.
- Use the feature's own refresh button if it has one. Avoid rapid repeated refreshes; requests may be cached or rate-limited.

**A HUD widget is missing or in the wrong place**

- Enable its setting, then run `/sm hud`.
- Check whether the widget only appears in a specific area (for example, the Crystal Hollows or dungeon hub).
- Move it away from screen edges/other HUD elements and check Minecraft GUI scale.

**Party Finder matching seems wrong**

- Confirm you are in the normal supported Party Finder chest and that matching is enabled.
- Check your selected class and whether your PB has loaded.
- Disable class/PB matching if you want unfiltered listings. No party is ever joined automatically by the matcher.

**A relay feature says unavailable**

- The relay is an external service; retry later and check the feature-specific guide.
- For cosmetics, verify Discord linking and operator setup. Players cannot repair server configuration from the client.
- For a private message, confirm the actual friend name and do not assume a failed message was delivered.

## 14. Feature-specific guides

This manual is the friendly starting point. These pages contain the complete technical details and edge cases:

- [README.md](README.md)
- [Community connection and privacy](docs/discord-ux.md)
- [Shared metric definitions](docs/community-leaderboards.md)
- [Settings and Party Finder matcher](docs/settings-party-finder.md)
- [Auction browser, dungeon tracker and shared themes](docs/auction-tracker-themes.md)
- [Daily Digest](docs/daily-digest.md)
- [Cosmetics and relay messages](docs/cosmetics-messaging.md)
- [Discord cosmetics linking and operator setup](docs/cosmetics-discord.md)

If the manual and the in-game setting tooltip differ, use the tooltip from your installed version and report the mismatch with your Minecraft/mod version.
