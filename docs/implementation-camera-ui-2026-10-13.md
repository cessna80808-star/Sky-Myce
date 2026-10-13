# Sky-Myce 2.5.1 implementation and verification — 2026-10-13

This records the combined camera, cosmetic, native chat, RNG tester and menu work requested in `message(10).txt`, the supplied menu screenshot and `message(37).txt`. It supplements the feature guides and the [main-machine rollout instructions](main-machine-community-rollout.md). Runtime account data, credentials, worlds and verification-instance files are not release source.

## Root causes and resulting behavior

- **Local cosmetics:** the Studio previously relied on community state for equip/save. A UUID-scoped, atomic local appearance and validated PNG library now drives both preview and the actual player renderer without a Discord session. Preset/PNG selection equips immediately; `None / Remove cape` persists an unequipped cape; deleting an imported file is a separate confirmed action. Local selection wins over stale remote refreshes. An explicit Publish operation retains the captured account, Minecraft identity, session generation and server revision through upload and save.
- **Persistence and draft loss:** Gson omitted explicit null profile keys, making the strict reader reject saved local appearance. Only this store opts into null serialization, with narrow recovery for its early omitted nullable keys. Missing required fields still fail. Rebuilding the Studio also previously adopted saved state over unsaved edits; initialization is now scoped to the Minecraft owner and preserves same-owner drafts.
- **Names:** names use Minecraft 26.1.2's native component codec after bounded, inert-tree validation. Arrays, nesting and individual colors survive save/load. A component already decorated with a cosmetic name is marked at the shared render boundary so the ordinary name cannot be substituted again. Minecraft UUID/account authorization remains independent. WebSocket capability version 5 permits the expanded inert trees; older clients receive a safe plain-text fallback.
- **Chat:** Reply and React operate in the existing native chat/composer, with a cancelable reply preview and a compact anchored picker. Edit/Delete retain existing ownership and time limits. Unicode clusters and custom emoji IDs remain intact through transport; unsupported game glyphs get readable labels and original-content hover text.
- **Media and ordering:** create/update/continuation, attachments, URL previews and retries previously allowed duplicate or stale representations. Stable source and attachment identities now converge on one logical message. Body/media and reactions track independent progress; a newer reaction cannot suppress an earlier failed edit or a different emoji's retry. Durable deletion stays final. Pending create-time reply lookups retain edits/deletes instead of publishing a stale create, and accepted replies prefer the canonical edited/deleted parent over a cached Gateway excerpt. Supported GIFs use the existing animated viewer; unsupported media retains its link.
- **Independent camera:** a dedicated camera, matrices, render state, section renderer and framebuffer render loaded world geometry. The main camera/player are never swapped or moved by this feature. Vanilla world clears and sky colors carry zero alpha, which exposed the main view through the secondary sky; only the secondary clear/sky are made opaque. At the current eye position the local avatar is hidden from this camera to avoid looking through its head. Configurable explicit opacity still works.
- **Sequence evidence:** automatic purple → yellow → close requires the living Healer F7/M7 boss context, a correlated real Berserker leap, calibrated hanging diorite pillars, measured lowering and observed Storm collision/crush confirmation. Ambiguity, timeout, death and world changes reset it. No production dungeon viewpoint is invented. The singleplayer Preview controls are manual presentation tests and do not simulate dungeon mechanics.
- **RNG tester:** `/sm rng-test` uses the existing parsing, normalization, acquisition merge and presentation in disposable state. Fresh backend authorization requires the persisted administrator, guild and current session. Mock delivery reuses the production formatter/retry path through a closed isolated transport, labels output TEST and never writes genuine drops, statistics, leaderboards or history. No production-channel test mode is exposed.
- **Menus and updates:** six categories, direct Home actions, feature-scoped configuration, search and shortcuts retain existing settings. Escape follows feature → containing category → Home → game, with no arbitrary previous-screen chain. Connection, local cosmetics, chat and optional sharing are explained independently. Primary, secondary, selected, disabled, destructive and On/Off states use labels and geometry as well as color. Auto-update is a separate real persisted switch, defaults Off, and retains verified after-exit installation; Off/Later persist before a blocked artifact worker can delay them. The chat shortcut closes the menu before opening the native composer.

The new menu request does not redesign the HUD. The camera's own move/resize handle and integration with the existing editor belong to the separately requested camera work.

## Automated verification

Java 25 was used for Gradle. Focused executable checks passed before the complete `gradlew.bat build check`, which passed all 37 tasks after the final client source changes. This includes native component/persistence/rebuild fixtures, independent camera math/compositing, measured sequence transitions, HUD integration, chat ordering/reactions/media/GIF, menu navigation and geometry, RNG isolation, update cancellation/persistence and the existing loader/security checks.

The current Node User Bot/Gateway suite and shared Backend Bot tests passed **75/75**, including mocked Discord REST failures/retries across receipt restart, separate-emoji ordering and pending create/delete/update races. `discord-user-bot: npm run check` also passed. The complete relay `npm test` passed, including the Wrangler dry-run build, local route/model/security fixtures and release signing checks; relay `npm run check` passed type generation and TypeScript checking.

These are fixture/local checks, not evidence of a production Discord deployment, multiplayer cosmetic propagation or a live dungeon run. Headless performance figures are not a live-client benchmark.

## Fullscreen client verification

Computer Use operated the existing Prism `Sky-Myce Verification` instance at **1920 × 1080 fullscreen**, GUI scale 3, Minecraft 26.1.2, Fabric 0.19.5 and Java 25. The dedicated `Sky-Myce Visual QA` singleplayer world was used with Discord disconnected.

Observed before the final cold restart:

- The six-category Home, direct Community screen and selected/disabled/switch controls fit without overlapping content. Community accurately displayed Disconnected, with separate local cosmetics and update controls. Its Escape action returned to Settings; Cosmetics → category → Home → game required one Escape per level.
- A segmented JSON name containing the real Minecraft name displayed once, with its purple/pink segment colors, in the Studio and native Minecraft chat. Styling an empty custom name also colored the real Minecraft name.
- Selecting Forest rendered the green cape in the Studio and on the actual third-person player. Importing a second, visually different valid PNG replaced it with the pink/orange texture through the local asset library; the Studio identified its asset hash rather than a preset.
- Purple and yellow manual previews displayed different captured viewpoints. Turning the main view left the purple foreground anchored. The camera's lower-right handle resized its 16:9 window, and dragging moved it away from Minecraft's tutorial overlay. The alpha issue caught during this check was fixed before the final restart.
- Auto-update was switched On while no newer release was available. No update artifact was installed by that test.

Final installed verification artifact: `skymyce-2.5.1.jar`, SHA-256 `66CC8FF390B65D856CB2FFFCB6D0315A99094F1409650E6327D7C18A54C9922F`. The prior verification jar was backed up. The original imported sunset PNG was moved aside before restarting so the saved local library must supply its texture.

Observed after cold restarts of that artifact:

- The imported pink/orange cape still rendered on the actual third-person player with its original source PNG absent. Reopening the Studio recovered the same local asset hash and segmented colored name, with one name prefix.
- Sky-Myce, Forest, Sunset and Void each displayed their distinct actual preset texture in the Studio. A non-PNG local import showed a validation error while retaining the working appearance.
- Selecting `None / Remove cape` immediately removed the cape from the back-facing preview and actual player. After a second full process restart, the actual third-person player still had no cape; the imported assets remained available independently.
- The saved purple calibration and resized/moved camera window returned. The secondary sky stayed opaque over the main-view mountain, without exposing the main view or the local avatar's head. These were manual singleplayer previews; both viewpoint persistence paths also passed executable checks.
- Auto-update On survived the first restart. It was then restored to Off through the UI; Off survived the second restart. Current-version actions showed their appropriate enabled/disabled states.
- Community's Open Global Chat action opened the existing native bottom composer. The administrator RNG tester displayed its two modes and rejected an offline invocation with a clear requirement to connect the selected Minecraft identity; offline access did not grant tester authority.

The final client loaded without a new camera or mixin error. Existing resource/audio warnings were not treated as evidence of these features failing. No live Discord message was posted, remote cosmetic published, production RNG event inserted or update artifact installed during these checks.

## Remaining live/operator verification

- The user has not supplied the actual purple/yellow dungeon camera coordinates. Capture and verify viewpoints/pillars in the intended F7/M7 arena, then observe the real leap and both crushes. Automatic sequence behavior is fixture-verified only.
- This machine did not have an authorized live backend-admin session in the verification instance. Offline RNG UI must fail closed. Authorized tester execution and any production server command registration require the main-machine operator.
- Live Discord reply/reaction/edit/delete/GIF delivery, remote cape publication and propagation to another compatible client need the matched existing service deployment and authenticated accounts. The repository Gateway was changed and tested; the historically documented external `D:\Code\Cessna fork\relay` owner was unavailable here. Update the actual authoritative owner using the handoff; do not start a competing Gateway.
- Production services, bot registrations and release manifests were not deployed. Linux, Sodium/Iris and live dungeon rendering remain unverified. Cloud/transparency-compositor/outline/hand post-processing intentionally stays outside the bounded secondary camera path, as described in [the camera guide](healer-crush-camera.md).

Feature references: [Global Chat](global-chat.md), [Cosmetics Studio](COSMETICS_STUDIO.md), [cosmetic compatibility](cosmetics-migration.md), [RNG tester](rng-tester.md), [menus](menu-organization.md), [community/update architecture](community-architecture.md).
