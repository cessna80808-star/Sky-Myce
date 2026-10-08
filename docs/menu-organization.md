# Menu organization

The main menu and compact navigation use six systems: **Dungeons, Combat, Economy, Cosmetics, Utils, Settings**. The existing card-to-sidebar transformation and motion modes remain in place. Features have one home; search and shortcuts point back to it.

`ui/MenuCatalog.kt` owns category descriptions, stable feature IDs, subgroups, aliases and the setting-owner resolver. Its inventory is built from the visible Resourceful Config entries, including their existing titles and descriptions. Category browsing, settings scopes and search use this same inventory. Twelve additional search entries describe real controls already owned by the dungeon preference and theme editors; they do not introduce new configuration fields.

## Audited inventory

The audit covers the registered feature modules, existing menu screens, the full config tree and its callers. These are the 28 canonical menu entries:

| Category / subgroup | Feature ID | Existing functionality and canonical destination |
| --- | --- | --- |
| Dungeons / Parties | `party-finder` | Online dungeon friends, LFG availability, invites, class/PB filters and friend preferences. The old dungeon friend and Party Finder settings share this home. |
| Dungeons / Routes | `starred-routes` | Starred target routing, healer route display and recording diagnostics; feature settings. |
| Dungeons / Runs | `dungeon-tracker` | Run totals, recorded acquisitions, loot/history filters and tracker settings; Dungeon Tracker. |
| Dungeons / Rewards | `dungeon-chests` | Estimated chest profit, valuable-item thresholds, reroll and negative-profit protection; feature settings. |
| Dungeons / Information | `dungeon-chat` | Dungeon message filtering; feature settings. |
| Dungeons / Runs | `dungeon-summary` | Dungeon completion overlay; feature settings. |
| Combat / Timers | `ability-cooldowns` | Held-item ability timers and ready notifications; feature settings. |
| Combat / Gear | `loadouts` | Equipment loadout keys and menu safeguards; feature settings. |
| Combat / Gear | `wardrobe` | Existing wardrobe keys and safeguards; settings preserved, with the initialization limitation below. |
| Combat / Instances | `instance-helpers` | Missing-player alerts and requeue behavior shared by dungeon and Kuudra instances; feature settings. |
| Combat / Enemies | `bal-notifier` | Bal spawn/health milestone party notifications; feature settings. |
| Economy / Auctions | `auction-browser` | Cached auction listings, saved filters, sorting, prices and lore; Auction Browser. |
| Economy / Loans | `loans` | Account-local trade history and outstanding items lent to friends; Lending Log. |
| Economy / Players | `player-economy` | Estimated friend networth, purse, bank and wardrobe values; Player Wealth. |
| Economy / Item Values | `stash-helper` | Low-value stash filtering and pickup protection; feature settings. |
| Cosmetics / Player Appearance | `cosmetics` | Cosmetic name, tooltip and player-scaling preferences, account linking and synchronization; Cosmetics. Server-owned cosmetic selection remains available through the existing Discord flow. |
| Utils / Progress | `daily-digest` | Daily progress, public updates and personal/community RNG reports; Daily Digest and its settings. |
| Utils / Social | `global-chat` | Global client/Discord chat opt-in and mention sound; feature settings. Existing chat commands remain available. |
| Utils / Social | `party-commands` | Allowed party commands, cooldown and optional stats API fallback; feature settings. |
| Utils / Social | `player-utilities` | Cached friend activity, location and profile information; Player Utilities. |
| Utils / Mining | `mining` | Crystal Hollows lobby information and nucleus warp timing; feature settings. Existing powder configuration is retained without inventing a new working tracker. |
| Utils / Inventory | `auto-refill` | Consumable refill selection; feature settings. |
| Utils / Information | `information` | Client information and skill XP overlays; feature settings. |
| Settings / General | `settings-general` | Client data collection and developer preferences. |
| Settings / Appearance | `settings-appearance` | Animation personality and the shared theme editor: palette, opacity, spacing and visual flags. |
| Settings / HUD | `settings-hud` | Existing HUD editor for widget layout, scaling and per-widget themes. Feature-specific visibility controls stay with their features. |
| Settings / Performance | `settings-performance` | GUI graphics quality and rendering effects. |
| Settings / Controls | `settings-controls` | The menu-opening key. Feature-specific loadout/wardrobe keys stay with their gear features. |

There is no standalone Friends destination. Its three useful views are separated by purpose: dungeon parties under Dungeons, loans/wealth under Economy, cached player information under Utils. The existing shared screen implementation is scoped to the selected view, without tabs that recreate cross-category destinations.

No separate Bazaar browser, leaderboard, Slayer system or global UI-scale setting was found, so the menu does not advertise them. Bazaar/auction price metrics remain available where they already affect dungeon tracking. Scale search finds actual cosmetic scaling and HUD widget scaling. The commented-out Waypoints event implementation does not become a new menu feature.

`WardrobeKeybinds` exists but is not currently registered by `SkyMyce.initializeModules`. This navigation change preserves its saved controls and does not activate that module. `MiningConfig.powderTracker` likewise has no active consumer in the audited implementation. These existing limitations are not silently presented as new functionality.

## Settings and search

Settings contains five useful global sections: General, Appearance, HUD, Performance and Controls. Reduced/Off motion is the existing animation choice under Appearance; accessibility aliases find it without duplicating the control in an empty Accessibility section. No empty Gameplay section is added.

Every visible saved setting has exactly one owner. For example, `Dungeons/Starred Clear Routing/opacity` remains a routing control; `Theme & Appearance/graphicsQuality` belongs to Settings → Performance; and `Theme & Appearance/animationMode` belongs to Settings → Appearance. Config category names and keys are unchanged.

Search matches feature names, descriptions, categories, subgroups, real setting names/comments and small metadata aliases. All query words must match. Results sort by category and show their canonical breadcrumb, including the feature name for ambiguous settings such as “Enabled.” Examples:

| Search | Canonical result |
| --- | --- |
| `ah` | Economy → Auctions → Auction Browser |
| `friends` | Dungeons → Parties → Party Finder; Economy → Loans/Players; Utils → Social → Player Utilities |
| `fps` or `graphics` | Settings → Performance → GUI Graphics Quality |
| `animations` or `reduced motion` | Settings → Appearance → GUI Animations |
| `keybind` | Settings → Controls and the actual gear keybind settings |
| `message template` | Dungeons → Parties → Party Finder → LFG Message Template |
| `pb limit` | Dungeons → Parties → Party Finder → Dungeon Availability |
| `text shadow` | Settings → Appearance → Text Shadow |

Feature results open the feature directly. Setting results open and focus the actual control. Cosmetic preferences focus the existing Cosmetics toggles; palette searches focus the shared Theme Editor; dungeon availability/message/title searches open the existing atomic preference editor. These routes do not create a second settings implementation.

Category pages group related feature cards and offer local search and state filters. Global search reveals setting-level results and loads more results in bounded batches.

## Quick Settings and shortcuts

Quick Settings holds up to eight canonical Resourceful Config paths. Add/remove controls from search or an eligible original editor, reorder them with the arrows, restore the defaults, or use **Original** to open the control in its canonical location. Defaults are animation mode, graphics quality and ability cooldown visibility.

Boolean and enum quick controls change the original config entry. Numeric and keybind shortcuts open the original editor. Values are never copied into a second configuration. Array/string controls and the API key are excluded from direct quick controls; compound dungeon preferences and hidden theme controls remain searchable in their original editors.

Favorites and Recent are shortcut views, not top-level categories. Favorites can reference features or settings and are bounded to 32; recent features are bounded to 12. Setting favorites use their canonical paths and open the original editor. `menu-shortcuts.json` stores identities and ordering only, using the existing bounded atomic/coalesced persistence helpers. Existing feature configuration, dungeon preferences, HUD layouts and the saved introductory-animation state keep their previous storage identities.

## Verification

`menuCatalogCheck` builds the real config inventory and verifies six categories, one home per visible setting, merged party functionality, aliases, category sorting, extra editor search entries and direct setting metadata. `menuShortcutsCheck` verifies identity-only persistence, bounds, reordering, defaults, restart behavior and corrupt-file preservation. The existing HUD/settings and navigation checks cover editor scopes, focus routes and card geometry.

Run the focused tasks while iterating, then the mod-wide Java 25 build:

```powershell
.\gradlew.bat menuCatalogCheck menuShortcutsCheck hudCheck menuNavigationCheck
.\gradlew.bat check build
```

Live Minecraft input, remote data refresh and server-owned cosmetic changes still require in-game verification; the inventory and headless checks do not claim those interactions were performed.
