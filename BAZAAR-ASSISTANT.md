# Bazaar Assistant

A guided, step-by-step flip on top of Sky-Myce's existing Bazaar flipping. It reads menus, signs and the existing order records, then shows what to do next; the player clicks, types, confirms and submits everything. Player-facing help is in `BAZAAR-QUICKSTART.md`.

## Architecture audit

### What already existed

| Area | Where | Role for the assistant |
| --- | --- | --- |
| Session, records and recommendations | `features/bazaar/BazaarTrading.kt` | Owns the ledger (positions, orders, realized sales) built from Bazaar chat receipts and the Manage Orders menu, the market feed and the ranked flips. The assistant only reads it. |
| Accounting | `BazaarLedger.kt`, `BazaarModel.kt` | FIFO realized profit after tax; the only source of profit numbers shown by the assistant. |
| Market data | `BazaarMarket*.kt` | Top of book and freshness (`FRESH`/`AGING`/`STALE`/`UNAVAILABLE`). |
| Terminal | `BazaarScreen.kt` (owo-ui `MenuScreen` on the Skija canvas) | Flips, Positions, History, Replays and More tabs. The assistant adds a card to More. |
| Active Flip HUD | `BazaarHud.kt` | Unchanged; the assistant uses separate widgets. |
| Alerts, Discord, leaderboards | `BazaarAlerts.kt`, `BazaarCommunity.kt`, relay | Unchanged and not called by the assistant. |
| HUD system | `hud/widget/Widget.kt`, `WidgetManager.kt`, `WidgetEditorScreen.kt` | Retained owo layouts, persisted to `widgets.json`, with an editor for drag, scale, anchor and reset. |
| Settings | Resourceful Config `CategoryKt` + `config/SettingsScreen.kt` + `ui/MenuCatalog.kt` | Persistence and the searchable settings page. |

### What the assistant adds

| File | Role |
| --- | --- |
| `features/bazaar/assist/BazaarAssistModel.kt` | Pure logic: screen recognition, phase and step selection, slot matching that refuses ambiguity, chat guidance, sign checks, pop merging, run summaries, placement. Covered by `BazaarAssistCheck`. |
| `features/bazaar/assist/BazaarAssist.kt` | Runtime module: mission state, keys, screen reads (on screen change and every 10 ticks, never per frame), chat ghost text and Tab fill, sign helper, opt-in Auto Command and Auto Sign, profit pops. Reads Bazaar data only through `AssistSource`. |
| `features/bazaar/assist/BazaarAssistHud.kt` | Panel and profit-pop widgets, drawn in the HUD layer and again over menus and signs; beside-the-menu placement and dragging inside menus. |
| `features/bazaar/assist/BazaarAssistHighlight.kt` | Slot highlight (drawn after slots in container space), its styles and the settings preview. |
| `config/economy/BazaarAssistConfig.kt` | "Bazaar Assistant" settings category. Auto Command and Auto Sign are hidden there and only enabled through the terminal's confirmation. |
| `mixin/AbstractContainerScreenMixin.java` (one injection), `BazaarAssistSignAccessor`, `BazaarAssistContainerAccessor` | Read-only access to slots, sign text and menu bounds, plus the highlight draw hook. |

Start points: the terminal's **Guide this flip** (`BazaarTrading.guide` → `BazaarAssist.begin`), **More → Guide the best flip**, `/sm bazaar assist [start|stop]` and the Start/Stop keys.

## Safety boundaries

- Nothing clicks slots, moves the mouse, confirms orders or submits signs.
- Auto Command (off by default, confirmed before enabling): one press of its key sends the one command the current step shows, only with no menu open, only on SkyBlock, never the same command twice within 3 s. It never runs commands from chat, lore or server messages.
- Auto Sign (off by default, confirmed before enabling): types the expected value into an empty first line once per sign. Verified in game that Enter on a sign moves to the next line rather than submitting; the player clicks Done.
- A highlight appears only when exactly one slot matches, and only while that slot still shows the item it was matched from.
- No new backend or network traffic.

## Parity with the reference (FlipperX Assist)

The reference repository was surveyed for behaviour only; its licence forbids reusing its code. Its client sends screen state to the FlipperX server, which decides every step. Sky-Myce computes steps locally from its own records, so behaviours that depend on that server are adapted or out of scope. The reference recording mentioned in the brief was not available; parity was checked against the reference source.

| Reference behaviour | Sky-Myce | Status |
| --- | --- | --- |
| Steps: command, click, sign, close, wait, done | Same kinds, computed locally from the ledger and the open screen | Matched (local) |
| NPC, unreachable and gated steps | Cookie Buff error and hint to use the Hub Bazaar NPC | Partial |
| Step only applies to the screen it was computed for; click target must still show the same item | Screen identity plus a live slot-name check before every highlight | Matched |
| Prefetched steps for the next screen | Not needed: steps are computed instantly on screen change | Not applicable |
| Highlight shapes: outline and fill, outline, fill, corners | Same four | Matched |
| Highlight colour swatches and hex | Hex (empty = theme accent) | Adapted (no swatch row) |
| Line Thin/Thick | Thickness 1–4 and opacity | Extended |
| Pulse: always, when it moves, off | Same three | Matched |
| Glide to a new slot | 160 ms ease-out glide | Matched |
| Live settings preview | Three-slot preview in the terminal card | Matched |
| Panel: icon, headline, item, hint, notice, cookie warning | Same, with wrapped lines | Matched |
| Panel beside the open container, drag inside containers, saved, reset | Beside the menu (shrinks to stay readable rather than cover slots), drag inside menus, saved, reset in terminal and HUD editor | Matched; scaling added |
| Panel dims when the step is not current | Steps are always current locally | Not applicable |
| Headline cross-fade | 180 ms fade-in of a new step | Matched |
| Profit count-up and per-hour | From realized records of guided positions | Matched |
| Finish, daily-limit and goal bars; goal banner | Server features | Not implemented |
| Profit pops at the clicked slot within 4 s, merge within 1.2 s, colour by size, 1M+ larger | Same rules | Matched |
| Run summary screen with chart | Chat summary on stop and "Last run" in the terminal; charts stay in History | Partial |
| Chat ghost text, owned only while on track | Ghost text while the box is empty, "/" or the command's first word | Matched |
| Wrong characters marked red | Same | Matched |
| Tab hint for the first three uses | Same | Matched |
| Tab fills without sending; vanilla untouched elsewhere | Same; vanilla's suggestion list stays open while the assistant owns the inline ghost | Matched |
| Sign helper: expected value, wrong value, wrong line, done | Same; "click Done" | Matched |
| Gray ghost of the rest of the value on the sign | Expected value shown above the sign | Adapted |
| Autocommand on B, confirmation, "Close the menu first", 3 s dedupe | Same; key unbound by default because B is a Sky-Myce wardrobe/loadout key | Matched |
| Autosign for amounts | Amounts, search terms and typed prices | Extended |
| Keys `]` start, `[` stop, also inside menus, not while a text field has focus | Same | Matched |
| `/flipperx` login, goal, finish, update, discord | Backend features | Not implemented |
| Websocket, chat-line upload, icon downloads, self-updater, Cofl muting | Deliberately absent | Not applicable |

## Testing

| Command | What it covers |
| --- | --- |
| `./gradlew bazaarAssistCheck` (part of `check`) | Pure logic: every Bazaar screen, phases, the full step sequence, ambiguity and missing targets, chat guidance and mismatch marking, sign checks, colours, pop merging and tones, run summaries, placement, wrapping. |
| `./gradlew runBazaarAssistGameTest` | The real assistant in a running client (Fabric client gametest): a local flat world gives a player, chat, HUD, key mappings and a server connection; Bazaar menus and signs are opened client-side with Hypixel's titles and button names, and `FakeAssistSource` supplies order records. 73 checks; report and screenshots in `build/bazaar-assist-game-test/client/`. |
| `./gradlew runGuiVisualCheck` | The terminal's Bazaar Assistant card at two sizes and the default-off state of Auto Command and Auto Sign. |

The game test covers: start and stop keys (also inside menus); highlights in search results, product, amount, price, confirm and orders menus; price-mode switching and glide; ambiguous, missing, renamed and moved buttons; unrelated chests, another product's page and the player inventory; ghost text, Tab fill, vanilla Tab on other commands and mismatch correction; Auto Command off, on, dedupe, cooldown, menus and steps without a command; Auto Sign off and on, amounts, search terms, prices, cleared signs, reopened signs and unrelated signs, with the sign left open; Enter on a sign; step changes while chat, a menu or a sign is open; stale market data; slot-anchored and HUD profit pops, merging and duplicate prevention; run profit and the stop summary; panel placement at GUI scales 1–3, dragging, persistence to `widgets.json`, reopening, reset, a small window and the HUD editor; animations off and reduced menu motion; screen reads limited to one per 10 ticks and none while stopped.

### Not covered without Hypixel

- Real Hypixel menus: item names, lore and slot layouts are reproduced from Hypixel's known titles and button names, not captured live.
- Real order receipts: phase changes are driven by `FakeAssistSource`; the ledger that produces them in game is covered by `bazaarCheck`.
- Frame-time cost: client gametests run at a fixed tick rate, so performance is checked by counting screen reads rather than timing frames.
