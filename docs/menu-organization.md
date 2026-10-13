# Menu organization

The main menu and compact navigation use six systems: **Dungeons, Combat, Economy, Cosmetics, Utils, Settings**. Home cards show each category's purpose and, when space permits, its subgroups. Those same cards transform into the compact icon rail used by category pages, tools and Settings editors. The current category stays selected; Home, category tooltips, Search and Quick Settings remain available from the rail. Features have one home; search and shortcuts point back to it.

As of 2.5.1, Home also has direct **Community** and **Global Chat** actions beside Search and Quick Settings. Narrow windows combine secondary shortcuts in one compact anchored dropdown. Community remains a single canonical Settings feature; Global Chat remains a Utils feature and opens the existing native composer. Common online actions have a one-click path without another top-level category or competing chat screen.

Every category, including Settings, presents its feature inventory first. Feature settings display only their owner's controls, while typed search still spans all settings. Tool cards distinguish Open and Configure and expose the actual feature On/Off entry inline when available. Saved config paths, values, shortcut identities and pending save-on-close behavior remain intact.

Back navigation follows structure: feature/settings → containing category → Home → game. Search, Quick Settings, Favorites and Recent return directly to Home. Switching categories replaces the current destination; it cannot accumulate prior categories as parents. Anchored dropdowns consume Escape before closing the page; bounded draft dialogs and the existing HUD integration retain their actual caller. `/sm settings` opens the Settings directory.

This follows [Microsoft's game UI navigation guidance](https://learn.microsoft.com/en-us/xbox/accessibility/xbox-accessibility-guidelines/112): consistent focus order, repeated controls in consistent places, persistent Home access and more than one way to find a feature. The existing inventory, search and icon rail keep unrelated advanced controls off Home. Switches show On/Off text, selected buttons add a geometric check, and primary/destructive/disabled states share restrained styling with readable action labels. No HUD redesign is part of this menu work.

`ui/MenuCatalog.kt` owns category descriptions, stable feature IDs, subgroups, aliases and the setting-owner resolver. Its inventory is built from the visible Resourceful Config entries, including their existing titles and descriptions. Category browsing, settings scopes and search use this same inventory. Twelve additional search entries describe real controls already owned by the dungeon preference and theme editors; they do not introduce new configuration fields.

## Audited inventory

The audit covers the registered feature modules, existing menu screens, the full config tree and its callers. The canonical menu entries include:

| Category / subgroup | Feature ID | Existing functionality and canonical destination |
| --- | --- | --- |
| Dungeons / Parties | `party-finder` | Online dungeon friends, LFG availability, invites, class/PB filters and friend preferences. The old dungeon friend and Party Finder settings share this home. |
| Dungeons / Routes | `starred-routes` | Starred target routing, Blood/Wither exit display and recording diagnostics; feature settings. |
| Dungeons / Runs | `dungeon-tracker` | Run totals, recorded acquisitions, loot/history filters and tracker settings; Dungeon Tracker. |
| Dungeons / Boss | `healer-crush-camera` | Fixed secondary view calibration, preview and measured purple/yellow pillar setup; Healer Crush Camera. |
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
| Utils / Progress | `rng-journal` | Preserved personal acquisition history and measured comparisons; RNG Journal. |
| Utils / Progress | `leaderboards` | Consenting community comparisons with source/UTC/freshness labels; Community Leaderboards. |
| Utils / Administration | `rng-test` | Authoritative administrator check, disposable parser fixtures and isolated mock feed delivery; RNG Drop Tester. |
| Utils / Social | `global-chat` | Global IRC client/Discord chat opt-in and mention sound; feature settings. `/sm irc` selects the channel; `/sm irc <message>` sends one message. Older search aliases remain discoverable. |
| Utils / Social | `party-commands` | Allowed party commands, cooldown and optional stats API fallback; feature settings. |
| Utils / Social | `player-utilities` | Cached friend activity, location and profile information; Player Utilities. |
| Utils / Mining | `mining` | Crystal Hollows lobby information and nucleus warp timing; feature settings. Existing powder configuration is retained without inventing a new working tracker. |
| Utils / Inventory | `auto-refill` | Consumable refill selection; feature settings. |
| Utils / Information | `information` | Client information and skill XP overlays; feature settings. |
| Settings / General | `settings-general` | Client data collection and developer preferences. |
| Settings / Community | `community` | Optional Discord connection, independent sharing controls, signed updates and the persisted Auto-update switch. |
| Settings / Appearance | `settings-appearance` | Animation personality and the shared theme editor: palette, opacity, spacing and visual flags. |
| Settings / HUD | `settings-hud` | Existing HUD editor for widget layout, scaling and per-widget themes. Feature-specific visibility controls stay with their features. |
| Settings / Performance | `settings-performance` | GUI graphics quality and rendering effects. |
| Settings / Controls | `settings-controls` | The menu-opening key. Feature-specific loadout/wardrobe keys stay with their gear features. |

There is no standalone Friends destination. Its three useful views are separated by purpose: dungeon parties under Dungeons, loans/wealth under Economy, cached player information under Utils. The existing shared screen implementation is scoped to the selected view, without tabs that recreate cross-category destinations.

No separate Bazaar browser, Slayer system or global UI-scale setting was found, so the menu does not advertise them. Bazaar/auction price metrics remain available where they already affect dungeon tracking. Scale search finds actual cosmetic scaling and HUD widget scaling. The commented-out Waypoints event implementation does not become a new menu feature.

`WardrobeKeybinds` exists but is not currently registered by `SkyMyce.initializeModules`. This navigation change preserves its saved controls and does not activate that module. `MiningConfig.powderTracker` likewise has no active consumer in the audited implementation. These existing limitations are not silently presented as new functionality.

## Settings and search

Settings contains five useful global sections: General, Appearance, HUD, Performance and Controls. Its editors use the shared icon rail and keep the existing grouped controls, all-settings search, direct-control focus and atomic friend drafts in the content area. Reduced/Off motion is the existing animation choice under Appearance; accessibility aliases find it without duplicating the control in an empty Accessibility section. No empty Gameplay section is added.

Every visible saved setting has exactly one owner. For example, `Dungeons/Starred Clear Routing/opacity` remains a routing control; `Theme & Appearance/graphicsQuality` belongs to Settings → Performance; and `Theme & Appearance/animationMode` belongs to Settings → Appearance. Config category names and keys are unchanged.

Search matches feature names, descriptions, categories, subgroups, real setting names/comments and small metadata aliases. All query words must match; existing prefix and small-typo matching are retained. The catalog snapshots and tokenizes its immutable metadata once, then filters the already sorted index for each query. Results sort by category and show their canonical breadcrumb, including the feature name for ambiguous settings such as “Enabled.” Examples:

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

Category pages group related feature cards and offer local search and state filters. Tool cards distinguish **Open** from **Configure**. Global Search opens with a short prompt and suggestions for Dungeon routes, Chest profit and Animations; typing reveals feature and setting-level results in bounded batches. Search, Quick Settings, Favorites and Recent show their own title and purpose. Selecting an already active view preserves its content instead of restarting a page transition.

## Quick Settings and shortcuts

Quick Settings holds up to eight canonical Resourceful Config paths and has its own rail button. Add/remove controls from search or an eligible original editor, reorder them with the arrows, restore the defaults, or use **Full settings** to open the control in its canonical location. Defaults are animation mode, graphics quality and ability cooldown visibility.

Boolean and enum quick controls change the original config entry. Numeric and keybind shortcuts open the original editor. Values are never copied into a second configuration. The retained Quick page compares at most eight live values without building a comparison list on each idle tick; pin/value changes still refresh it. Array/string controls and the API key are excluded from direct quick controls; compound dungeon preferences and hidden theme controls remain searchable in their original editors.

Favorites and Recent are shortcut views, not top-level categories. Favorites can reference features or settings and are bounded to 32; recent features are bounded to 12. Setting favorites use their canonical paths and open the original editor. `menu-shortcuts.json` stores identities and ordering only, using the existing bounded atomic/coalesced persistence helpers. Existing feature configuration, dungeon preferences, HUD layouts and the saved introductory-animation state keep their previous storage identities.

## Verification

`menuCatalogCheck` builds the real config inventory and verifies six categories, one home per visible setting, merged party functionality, aliases, category sorting, extra editor search entries and direct setting metadata. It compares indexed search results against the previous matching/order behavior. `menuShortcutsCheck` verifies identity-only persistence, bounds, reordering, defaults, restart behavior and corrupt-file preservation. HUD/settings checks cover the shared Settings rail, editor scopes and focus routes; navigation and motion checks cover stable card-edge hover, painted child input and settled rendering. See [the menu optimization measurements](menu-optimization-2026-10.md) for the before/after evidence and runtime limits.

Run the focused tasks while iterating, then the mod-wide Java 25 build:

```powershell
.\gradlew.bat menuCatalogCheck menuShortcutsCheck hudCheck menuNavigationCheck
.\gradlew.bat check build
```

The signed 2.4.27 release also passed a native private-world smoke test of Home, Settings, Search for Blood controls, Quick Settings and animation changes in the user's existing Prism session. [The menu report](menu-optimization-2026-10.md) separates that pass from the broader headless matrix. Remote data refresh and server-owned cosmetic changes were not exercised by that smoke test.
