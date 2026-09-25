# Settings, theme and Party Finder matcher

Open `/sm` or `/skymyce`, the settings key (Right Shift by default), or the mod's configuration entry in Mod Menu. Settings use the existing owo UI and Resourceful Config values; existing configuration paths remain compatible.

![Sky-Hawk settings in the development client](images/settings.png)

Actual offline development-client render at 1920×1080, GUI scale 3. The gold accent demonstrates customization; the default accent remains cyan.

## Navigation and appearance

The five tabs are **General**, **Party Finder**, **Dungeons & Combat**, **Social & Sharing**, and **Theme & Appearance**. Common settings appear first; expand **More options** for the rest. Search filters names and descriptions within the selected tab. Every setting has a tooltip. Tab/Shift-Tab navigate controls; Enter/Space activates buttons and accordions. Sliders support keyboard input. Lists scroll and tab rows wrap at smaller GUI sizes.

**Dev Mode** remains under **General → Basics → More options**. Searching `Dev Mode` in General reveals it directly. It enables Sky-Hawk's developer diagnostic chat messages; it is separate from Minecraft's F3+H advanced item tooltips. Its existing `devMode` configuration value is unchanged and saves when settings close.

The config-saving wrapper forwards root entries as well as categories. This restores **Dev Mode**, **Gather Data**, and **Open Config Key** in builds where the entire Basics group was missing; existing saved values are retained. Verification covers the wrapped config used by the live menu.

**Open…** groups feature shortcuts in one menu. RNG history entries have one **Share…** menu: Party chat, Guild chat, or Copy report. Chat actions prepare a bounded draft; sending it requires the player's normal confirmation. Community reports retain their **unverified** label even when long text is shortened. There is no public RNG permalink service, so Copy report copies text.

**Theme & Appearance → Shared theme** now uses Dark glass, Minimal, Light and High contrast presets, with nullable global and per-widget overrides. Colors accept six-digit RGB hex, a picker or RGB sliders; panel opacity ranges from 20–100%. Panel/card/text/border colors, padding, corners and text shadow use the same shared theme. Status colors keep their meaning. Right-click a widget in `/sm hud` to edit its overrides without resetting position, anchoring or scale. Reset restores inheritance; versioned clipboard theme import/export is bounded and data-only. Previous Digest accent/opacity settings migrate once, with original configuration preserved. Changes save when the editor closes. See the [theme and migration guide](auction-tracker-themes.md).

## Party Finder matching

The **Party Finder** tab controls the chest matcher independently of the `/sm pf` friend menu:

- **Dim incompatible parties** enables the visual overlay.
- **Match open classes** uses explicit open/available/needed class lore when present. Otherwise it treats classes absent from listed members as open. **Classes I play** accepts any selected class; an empty selection uses the current detected class. Unknown class data leaves the class check unrestricted.
- **Match note PB requirements** compares the note requirement to the local player's cached API completion time for that specific floor. Normal and Master Mode floors remain separate. Missing, loading, or zero PBs fail a stated time requirement. Notes without a valid time have no PB restriction.

Opening the chest requests the local player's stats through the existing bounded, asynchronous stats cache. Rendering does not perform HTTP requests or scan the whole friends list. Lore is parsed on container/inventory events; matching is recalculated only after listing, settings, or cache changes. Closing the chest clears the overlay state. Changing profiles follows the existing stats-cache invalidation.

The overlay keeps every item visible, hoverable and clickable. It neither joins parties nor intercepts clicks. English lore and the existing supported Party Finder layout are required; unavailable data is handled as described above. Inferring classes from occupied slots assumes one of each class; disable class matching when deliberately joining duplicate-class parties.

## Kotlin implementation

The complete parser and matching code is in [`PartyFinderMatcher.kt`](../src/main/kotlin/me/mycellium/skymyce/features/instances/dungeons/PartyFinderMatcher.kt). Its whole-token pattern is:

```kotlin
val PARTY_PB_PATTERN = Regex(
    """(?<![\w:./+\-])(?:([0-9]{1,2}):([0-5][0-9])|([0-9]{1,2})([0-5][0-9])|([0-9]{1,2})\s*(?:min|m)|([0-9]{1,2}))(?![\w:./+\-])""",
    RegexOption.IGNORE_CASE
)
```

`parsePartyPbSeconds(note: String?): Int?` strips Minecraft formatting, accepts touching `sub`/`pb` prefixes, rejects obvious floor/class/level/player counts, and returns the strictest valid limit if multiple times occur. Input is bounded to 500 characters. Free-form notes remain a heuristic: an unexplained standalone number is interpreted as minutes, as requested.

| Note | Seconds |
| --- | ---: |
| `5` | 300 |
| `510` | 310 |
| `5:10` | 310 |
| `5min` / `5m` | 300 |
| `sub5 tank` | 300 |
| `5:99`, `560`, `cata 40`, `need 1 tank`, no time | `null` |

PBs remain milliseconds until comparison: `310001 ms` fails a `5:10` requirement; `310000 ms` passes.

The full tab/accordion editor is [`SettingsScreen.kt`](../src/main/kotlin/me/mycellium/skymyce/config/SettingsScreen.kt). It groups existing configuration metadata into tabbed, scrollable cards with native owo collapsibles:

```kotlin
UIContainers.collapsible(
    Sizing.fill(), Sizing.content(),
    Component.literal("More options (${advanced.size})"), id in expanded
).apply {
    onToggled().subscribe { if (it) expanded.add(id) else expanded.remove(id) }
    advanced.forEach { child(editor(it)) }
}
```

The unified RNG action lives in [`DailyDigestScreen.kt`](../src/main/kotlin/me/mycellium/skymyce/features/digest/DailyDigestScreen.kt), using the already-installed owo `DropdownComponent`. Shared rounded surfaces and slider/button rendering live in [`HudTheme.kt`](../src/main/kotlin/me/mycellium/skymyce/hud/HudTheme.kt).

[`PartyFinder.kt`](../src/main/kotlin/me/mycellium/skymyce/config/instances/dungeons/PartyFinder.kt) subscribes to the existing post-slot-render event, with screen and slot identity guards:

```kotlin
@Subscription fun onRender(event: RenderSlotEvent.After) {
    if (!PartyFinderConfig.enabled || !LocationAPI.isOnSkyBlock || screen == null || MC.screen !== screen) return
    if (statsRevision != DungeonFriendStatsCache.version) updateMatcher()
    if (event.slot.index !in dimmed || screen?.menu?.slots?.getOrNull(event.slot.index) !== event.slot) return
    event.graphics.fill(event.slot.x, event.slot.y, event.slot.x + 16, event.slot.y + 16, 0xA0181B20.toInt())
}
```

No packet manipulation, new dependency or new API client is needed. The existing container mixin supplies `RenderSlotEvent.After`. A narrow Resourceful Config builder mixin routes only Sky-Hawk's configuration to the same settings screen, including Mod Menu's existing factory.

## Friends and wealth refresh

SkyBlockPv 1.8.9 is a required, bundled dependency (the [official Minecraft 26.1.x release](https://modrinth.com/mod/skyblock-profile-viewer/version/iVpdy3W0)). Release JARs and development clients include it, so no separate installation is needed. Fabric can use a compatible newer installed version. The existing provider integration and API backoff remain in place.

Relay lookups are paced one second apart. A missing estimate or API failure delays retries for that player for one minute, without making the remaining friends wait a minute each. SBPV's separate request cooldown still applies. Logs report relay wealth support on connection and successful cache hits with the original estimate's age, without balances or player names.

`/sm friends` uses the saved roster for this Minecraft account. Opening Friends, Lending or Wealth never requests the friends list. **Refresh** on Friends and Lending redraws the local data; Wealth Refresh checks only players already in that roster. Friend-add/remove notifications maintain the cache without rescanning.

Only the Party Finder status checker issues friend-list commands. It populates a missing complete cache once, including offline friends; after that, status checks stop at offline entries and preserve the rest of the cached roster. Opening `/sm pf` refreshes status at most once per minute. With background scanning disabled, open Party Finder to populate a missing cache. Offline roster changes made while the mod is closed may remain undetected until a later friend notification or status check sees them; social refreshes do not perform a full rescan.

**Refresh** on Friend Wealth reuses that roster and queues missing, incomplete or expired estimates, including offline players. Complete estimates updated less than 24 hours ago are skipped. Missing Networth, Purse, Bank or Wardrobe values can be retried manually; zero is a valid value. Confirmed non-SkyBlock friends stay cached; newly added friends also reuse recent estimates. Repeated clicks cannot restart an active pass. The status line shows the active lookup or the remaining queue, and each estimate shows its original update time. Failed profiles do not block the rest of a manual pass; successful public balances remain visible when inventory or networth calculations fail.

Lookups check the shared relay cache first. Wealth estimates are cached locally and on Cloudflare for 24 hours from the original fetch, and other players with the same friend receive that estimate and its unchanged `fetchedAt` timestamp. The server protects complete estimates for the full 24 hours even when a client requests a forced refresh. Manual retries can fill incomplete estimates after a shared one-minute minimum age, using the same single-client upload permission. API-disabled fields can remain unavailable. SkyBlockPv remains the provider; local lookups stay at least ten seconds apart, with backoff on API failures and saved estimates retained. Refreshing an expired estimate can wait up to SkyBlockPv's five-minute profile cache lifetime; it does not clear that mod's cache or relabel an old profile as a fresh response. Reopening the menu does not start another wealth refresh.

Wardrobe uses SkyBlockPv's existing Loadout breakdown (stored armor and equipment), already included in total networth. It does not recalculate each item or discard the entire estimate because one item lacks a market price. Values are estimates based on priced items; missing/invalid breakdowns show unavailable. A disabled bank API does not hide an available wardrobe estimate. Old cached estimates retain their original update time until a successful lookup. Use Refresh to retry missing wardrobe or other values; known totals are not removed by an incomplete relay upload.

In `/sm pf`, **Party** and **Invite** require a confirmed party with space and invitation permission (leader, moderator or All Invite). **Join** requires being solo and the other player's party to be available. The menu displays the invitation restriction above the friend rows and gives the specific reason on each disabled button's tooltip. Pending background stat requests do not lock the roster Refresh button.

Join PB checks can use the shared relay cache even without a local profile-provider mod. Detecting or changing a SkyBlock profile rejects reports fetched before that detection; newer shared reports restore local PB data without requiring a local API lookup. If the joining client has no fresh PB yet, Join can send a request for the host to check. The host still requires fresh, qualifying stats before issuing an invitation; a known failing PB remains disabled locally. Disabled Party Finder controls retain hover explanations.

## Validation

`gradlew.bat --offline build` runs the existing `dungeonFriendsCheck` and `hudCheck` tasks, including new parser/class/PB boundary checks, normal/Master floor isolation, configuration coverage and tooltips, preserved configuration paths, hex validation, horizontal layout and bounded sharing text. Previous Digest, friends, lending and HUD checks also run.

An isolated, muted development client rendered all five tabs at 1920×1080 and GUI scales 2–4, including expanded class choices, the RGB picker, live accent changes, scrolling and the Resourceful Config factory path. The temporary preview launcher is excluded from production source. Live Hypixel chest rendering still needs an authenticated in-game check; automated lore and matching tests do not establish that the server has not changed its menu format.
