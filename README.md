# SkyMyce

**SkyMyce** is a Modern Minecraft Fabric mod for **Hypixel SkyBlock**. It includes a variety of QOL features made to improve your gameplay experience while being fast and lightweight.

> **Getting Started:** Run `/skymyce` or `/sm` while in game to open the config.

---

# Features
## General
- **Client Info Widget**: Shows client info as a widget
- **Auto Refill**:  Automatically refill items from your sack when you run out of them
- **Skill XP Widget**: Moves the XP gain from your action bar to a widget
- **Ability Cooldown Widget**: Displays a widget showing the cooldown of your currently held item
- **Party Commands**: Adds useful commands while in parties using the "!" prefix
- **Stash Helper**: Highlights and blocks clicks for worthless items when in your stash
- **Wardrobe/Loadout Keybinds**: Allows you to use keybinds to quickly swap slots while in your wardrobe/loadout
## Instances
### Misc
- **Notify Missing Players**: Notifies you of missing players when joining an instance
- **Auto Requeue**: Automatically requeues you at the end of the instance
### Dungeon
- **Dungeon Win Screen**: Displays an animation that shows your run summary when your dungeon ends
- **Dungeon Message Filter**: Filters annoying chat messages while in a dungeon
- **Safe Chest Reroll**: Blocks accidental chest rerolls if they contain a valuable item (configurable)
- **Dungeon Chest Profit**: Shows the value of the items while in a dungeon chest
- **Dungeon Friends & LFG (/skymyce pf)**: Shows online friends with floor eligibility, Catacombs/class levels, PBs, last-played classes, and quick party invites/messages.
- The party finder includes a live username search. Friend rows show green idle, red in-run/limbo, or gray unknown status badges.
### Dungeon Tracker
- **Tracker Screen (/skymyce dungeon)**: Opens a detailed overview of your dungeon runs
- **Widget**: Shows a summary of your dungeon tracker while in the dungeon hub or at the end of your dungeon run

---

# Dungeon Friends & LFG

Open `/skymyce pf` (or `/sm pf`). Stats use installed **SkyBlocker** or **SkyBlock Profile Viewer (SkyBlockPv)** providers, following DungeonProgressHud's integration. No personal API key is required when a provider is working. **API setup** opens the optional Hypixel API-key fallback. The floor defaults to your highest completed tier, checking Master Mode first; selecting a floor manually keeps your choice for that screen.

The player relay is deployed at `wss://skyblock-relay.skyblock-relay.workers.dev/websocket` and included as the default. See the [relay setup and testing walkthrough](relay/README.md) for installation, Cloudflare maintenance, and connection troubleshooting.

The temporary connection-test and auction-data diagnostic commands have been removed.

- Friends refresh silently every minute in SkyBlock, including all friend-list pages. Disable background scans under **Instances > Dungeons > Dungeon Friends**. Opening the assistant also scans. Manually requested friend lists remain visible.
- The centered dark menu uses cyan accents, aligned Catacombs/all five class levels, and the selected floor's **S+ PB**. Click **CATA**, any class heading, or **S+ PB** to sort; click again to reverse. The **Sort** menu also exposes each column for smaller windows. Missing values stay last in either direction. The API's last-played class appears beneath each player's name and is highlighted in the class columns; levels are numbers without bars, including virtual levels above 50. Hover the name for their location.
- Known stats must meet the floor's Catacombs requirement and include a completion time. Hidden or unavailable values appear as **—**, with the reason on hover. S+ PB sorting puts friends without S+ times last; an ordinary completion still qualifies but is never displayed as an S+ time.
- **Missing** has independent checkboxes for the classes you need. It follows open party classes until you make a manual selection; **Use party classes** restores that behavior. Clear all boxes to show everyone. Class matching uses the API's last-played class plus the secondary classes recorded with **Edit**. Unknown classes without recorded alternatives remain visible. Messages choose a matching class for that friend from the checked classes.
- **Refresh** shows completed work as a percentage, including friend-list pages and player stats. It reaches 100% when both finish; discovering another page never moves the displayed percentage backwards. Hover it for the remaining count and scan/API status.
- **Available** lets you select the classes you can play on the menu's current floor and enter an **S+ PB limit**, such as `5:30.000`. While solo in SkyBlock, real Hypixel party invitations are accepted automatically only when the inviter has a recent S+ time at or faster than that limit. Background auto join is off by default; uncheck every class to stop it. Explicitly clicking **Yes** on an LFG request consents to that invitation even with background availability off; a configured PB limit for that floor still applies. Ordinary invitations do not disclose their floor or class, so they use your configured floor and first available class.
- **Join** sends a relay request asking that friend to invite you for one of your available classes. A friend running this version can offer an open class and invite you back automatically after their offer is acknowledged by your mod; your mod then accepts the actual server invitation after checking their PB. Both sides need a connected relay and an S+ limit for the same floor. The host also checks your PB, invite permissions, party capacity, and open classes; only friends' structured relay requests trigger this behavior. Requests expire after a minute, and outstanding offers reserve their class and party slot. The host can leave their own Available classes unchecked to host without auto joining other parties. Automatic Join messages never fall back to Hypixel chat.
- **Settings** edits the message template using `{name}`, `{class}`, and `{floor}` and the relay address. Availability, the PB limit, secondary classes, the template, and relay address are saved in `config/skymyce/dungeon_friends.json`. The existing Hypixel API-key setting is reused for stats.
- **/msg** first tries the relay and silently waits for a receipt from the recipient's mod. If no receipt arrives within five seconds, or the recipient/relay is offline, the LFG text is sent once through Hypixel `/msg`. Incoming LFG requests have Yes/No buttons; arbitrary replies can be sent with `/skymyce relaymsg Username your reply`. Both transports respect command pacing; pending fallback messages are cancelled when leaving SkyBlock.
- Anyone with a verified Minecraft account may connect to the relay, but the mod only handles messages from known friends. Minecraft supplies a signed account certificate and signed profile; the mod signs a unique relay challenge locally. The relay verifies the UUID, name, expiry, and signatures against Mojang's public keys. Account tokens, private keys, passwords, and Hypixel keys never go to Cloudflare. Connection details stay in logs and the Settings Reconnect tooltip; receipt acknowledgements are silent. Reconnects use backoff. Clear the relay address to disable it. The server uses hibernating WebSockets, room isolation, bounded payloads, and connection/message limits without storing chat history.
- Invites and messages require a confirmed party size, are disabled at 5 members, and have a one-second click cooldown. Party Finder lore, party announcements, and dungeon teammate data update the composition; the API's last selected class is used when a current class has not been observed.
- Clicking a Party Finder listing captures its floor and member classes. When you join that leader, **Missing** automatically selects the remaining classes. The last party's leader, roster, floor, and classes are saved with your preferences and restored on reconnect when the complete roster still matches and the snapshot is under 24 hours old. Partial party updates preserve captured classes; confirmed departures remove them.
- After sending LFG through **/msg**, short positive private replies such as `yes` or `invite me` trigger an invitation while the request is still active (one minute). The menu also marks the friend **Accepted** and highlights **/p** in green. Ambiguous replies are marked **Replied**, with the text on hover. Reply labels last up to five minutes and are not saved to disk.
- The server message **You are now friends with [username]** immediately queues that player's stats and refreshes their location. No specific username is hardcoded.
- Badges use the location Hypixel actually reports. `Dungeons`/`Catacombs` means **In Run**, while Hub/Island means **Idle**. Generic `SkyBlock` or private locations show **Unknown**; the mod cannot infer a private location.

Normalized stats, UUIDs, and expiry times are saved atomically in `config/skymyce/dungeon_friend_stats.json` and restored next session. Every new player is checked once; subsequent automatic and manual stat refreshes only update known **Cata > 40** players, using a ten-minute cache. Players at exactly 40 or below, hidden profiles, and absent profiles keep their saved result. Failed initial requests may retry; a new-friend notification or local profile change explicitly rechecks that player. An active Join exchange or incoming invite prioritizes fresh stats, regardless of Cata level. Unknown, hidden, or stale PBs do not qualify under a PB limit, and failed lookups do not extend freshness. Online/location scans still cover all friends.

Before an API lookup, the client checks the relay's persistent shared stats cache. Fresh entries from verified mod users are reused, including for PB checks; these are community-reported values, not independently verified Hypixel responses. Cache misses fall back to the existing providers/API. New-friend notifications still request API data, then upload only when the preceding server check found no fresh record. The server validates fields, stores one record per UUID/name, rejects racing duplicate uploads, and never renews an existing record's age merely because it was uploaded again. Names and public dungeon stats are shared; private messages, credentials, and friend relationships are not uploaded.

LFG requests show the sender, floor, class, and **Yes/No** actions. Yes triggers the sender's invitation and the recipient's acceptance; plain positive replies to an outstanding LFG also trigger an invitation. Unrelated replies never get Yes/No buttons. Expired buttons cannot accept a newer request. Party notices use compact lines such as **Inviting Alice...**, **Alice has been invited**, and **Alice joined**; incoming server invitations retain a clickable Join action.

Requests run off the client thread, one player at a time, with pacing and API backoff. SkyBlockAPI's HTTP helper handles the direct Hypixel fallback. Refresh's tooltip shows the player being loaded and remaining queue. Refresh and network errors preserve previously displayed stats and do not bypass rate limits. An unreadable cache is preserved and reported in the log, with memory-only operation for that session. No extra dependency is bundled.

Build with JDK 25 and `./gradlew build` (`gradlew.bat build` on Windows). `./gradlew dungeonFriendsCheck` runs the standalone checks for scanning, eligibility, hidden stats, party capacity/class rotation, sorting, message rendering, the Join exchange, invitation authenticity, PB limits, and saved party composition without connecting to Minecraft or Hypixel. Live server interaction and the GUI still need an in-game smoke test.

The checkout originally referenced a missing `PersonalBestCommand`; the `!pb F7` / `!pb M7` handler now shares the assistant's cache. If its stats have not loaded yet, retry shortly.

---

# Dependencies

Sky-Myce uses the following libraries:
> Bundled dependencies do not need to be installed separately.

| Dependency | Bundled? |
|------------|--------|
| **SkyblockAPI** | ✅ |
| **Resourceful Config** | ✅ |
| **owo-lib** | ❌ |

---

# Installation

1. Install the **[Fabric Installer](https://fabricmc.net/use/installer/)**.
2. Download the latest compatible SkyMyce version from the **[Releases](https://github.com/mycell1um/Sky-Myce/releases)** page.
3. Locate the downloaded `.jar` file.
4. Move the `.jar` file into your Minecraft `mods` folder.
5. Launch Minecraft using your Fabric profile.

## Minecraft Mods Folder

| Operating System | Location |
|------------------|----------|
| **Windows** | `%appdata%\.minecraft\mods` |
| **macOS** | `~/Library/Application Support/minecraft/mods` |
| **Linux** | `~/.minecraft/mods` |

---
