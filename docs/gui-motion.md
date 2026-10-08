# GUI motion and graphics

The client keeps owo's retained layout, focus, keyboard input, narration and scrolling. `MenuScreen` supplies the shared shell; `MenuButton`, `MenuLabel`, `MenuTextBox`, `MenuSlider`, `MenuDropdown`, `MenuCollapsible` and `menuScroll` supply the controls. Menus paint through `MenuGraphics` into the existing `MenuCanvas`, then `MenuTexture` uploads changed pixels. No new runtime dependency is required.

The default menu and HUD palette uses dark purple surfaces and lavender accents. Existing saved color overrides and other presets still apply. Titles, body text, captions and values use the existing bundled proportional font and Unicode fallback. Widget placement remains in Minecraft GUI coordinates.

The menu key and `/sm` open six category cards: Dungeons, Combat, Economy, Cosmetics, Utils and Settings. The same surfaces move into a 52-pixel icon rail when entering a category or feature, and expand back when clicking Home. Hover tooltips identify compact icons. Clicking the current category returns to its feature list. Search and Quick Settings are accessible from the home footer and the rail's Search button. Global Settings sections are selected in the content header. Direct feature commands still open their existing destinations. See [menu organization](menu-organization.md) for canonical feature homes and shortcuts.

Navigation transfers one interruptible layout timeline and one sliding selection indicator; it never queues intermediate destinations. Content reserves its final width throughout the transformation. Card text keeps its original typography and fades before compression, so it does not rewrap during motion. Painted card bounds and elevation map back to the retained native buttons for input.

## Motion

`MenuMotion.kt` contains pure, monotonic-clock primitives. `MotionValue` interpolates elapsed nanoseconds, rather than counting frames. Controls request their logical target immediately; sampling only changes presentation. Repeated requests for the same target preserve the deadline. Reversal starts from the current displayed value, cancellation preserves that value, and zero-duration/Off requests snap to the destination.

Use `QUICK` for response and page content, `SMOOTH` for switches and fades, and `SETTLE` for the shell's restrained spring. Sliders follow the pointer immediately; their emphasis animates independently. Typical durations are 110–180 ms for controls, 220–245 ms for pages and entry, and 90–145 ms for exit. Profiles control depth, scale, stagger and spring strength as well as timing.

`MenuIntroduction` claims the introduction once per application runtime and saves hidden `Theme & Appearance/menuIntroPlayed` only after the shell/navigation/cards settle. Ordinary page changes, configuration changes, data refreshes and window resize retain their current motion; they do not claim or restart the introduction. The flag persists across launches. Settings feature cards and Digest cards use `MenuRevealCard` with stable logical keys; first viewport visibility triggers their reveal, while scrolling back, filtering and rebuilding retain the original timeline. Reveal state is capped at 4,096 keys per runtime, with excess items appearing immediately rather than replaying older items.

```kotlin
private val emphasis = MotionValue(0.0)

// Call when painting or when the state changes. Identical targets do not restart.
emphasis.animateTo(if (focused) 1.0 else 0.0, 120,
    MotionEasing.QUICK, ThemeConfig.animationMode)
val amount = emphasis.sample().coerceIn(0.0, 1.0)
graphics.layer(alpha = amount) { /* paint */ }
```

`MenuGraphics.layer` composes opacity and transforms and restores them in `finally`. `MotionPose` supplies the inverse transform for pointer input. The screen uses the last painted shell/content poses for clicking, dragging, scrolling and hovering; the empty strip left by entering content rejects hits. Popups settle their anchor's pose before mounting. Closing screens reject clicks, keyboard input and text composition.

## Graphics and accessibility settings

Open **Settings → Theme & Appearance**. The graphics and animation selectors are saved in the existing Theme category.

| Graphics | Raster scale limit | Shadows | Glow | Ambient corner |
| --- | --- | --- | --- | --- |
| Extremely Light | 1× | None | None | None |
| Light | 1.25× | One layer | None | None |
| Medium (default) | 1.5× | Two layers | Yes | Heavy motion: three subtle points, at most 10 updates/s |
| High | 2× | Three layers | Yes | Heavy motion: five subtle points, at most 20 updates/s |

Raster dimensions remain capped at 2560×1440. Light and higher add cached gradients. Shadows use bounded silhouettes; effects do not allocate blur surfaces or introduce a post-processing pass. Ambient animation changes a small decorative corner, preserving the rest of the dirty-region cache.

| Animations | Behavior |
| --- | --- |
| Light | Faster deadlines, 1.25-pixel card lift, tiny scale changes, subdued shadow, no glow/spring/ambient animation |
| Medium (default) | 3.5-pixel card lift, restrained scale and spring, deeper hover shadow, layered entrances and responsive controls |
| Heavy | 6-pixel card lift, stronger bounded spring/depth/glow, richer stagger and subtle page focus; ambient detail only when graphics quality allows |
| Reduced | Fades and immediate interaction feedback; timelines capped at 90 ms without stagger delays, large page/shell movement, spring overshoot or ambient motion |
| Off | Logical state and final presentation update immediately; decorative transitions and ambient motion stop |

Graphics quality and motion are independent. Essential feedback remains available in every graphics mode. Existing saved `Full` values remain compatible and use the Medium personality. Changing either setting updates its button and config in place without rebuilding the settings body or replaying its entrance.

Graphics changes alter the canvas raster dimensions and therefore its native texture. Minecraft GUI extraction retains the current GPU texture view until rendering. `MenuTexture` now creates the replacement first and retires the old texture after the next client tick and a GPU fence; closing/switching screens uses that same path. Shutdown also releases resources awaiting a fence. This fixes the concrete premature-disposal hazard instead of suppressing the control or its errors. The original reported crash has no supplied stack trace, and its live GPU reproduction remains unverified.

## Adding a screen

Extend `MenuScreen` and implement `buildContent(FlowLayout)`. Use the shared controls and `menuScroll` for scrollable content. The shell owns entry/exit, tooltips, viewport conversion and rendering.

```kotlin
class ExampleScreen(private val parent: Screen?) : MenuScreen(Component.literal("Example")) {
    override fun buildContent(root: FlowLayout) {
        root.child(MenuLabel(Component.literal("Example")))
        root.child(MenuButton(Component.literal("Done")) { onClose() })
    }
    override fun onClose() = closeMenuTo(parent)
}
```

Use `navigateMenu(destination)` when opening another menu. It switches the live screen immediately and captures outgoing content for the visual transition. For a tab change, call `rebuildMenu(direction)`; it retains the shell and navigation. Guard already selected tabs so clicking them preserves search, focus, drafts and scroll. Use `MenuButton.navigation` and its `selected` state for the shared animated selection treatment.

For a user-driven results or detail change, call `contentChanged(direction)` **before** modifying children. Search typing, background cache arrivals, countdowns and unchanged periodic labels should update retained content without restarting the entire page animation. Existing async generation/revision checks still decide whether results may mutate the screen; closing screens reject late results. `MenuScroll` exposes `scrollPosition` and `scrollTarget`, and replacing its child preserves the valid offset. When rebuilding a whole scroll container, capture its target and restore with `scrollBy(savedOffset - scrollTarget, true, false)` after mounting.

Settings search leaves an open matching drawer's controls and draft intact. Theme editing returns to the original settings screen, and color-section expansion and scroll survive Reset, Inherit, preset and import actions. Cosmetics preferences use retained animated switches rather than rebuilding the page after each toggle.

User actions can call `showMenuToast(message, MenuToastKind.SUCCESS/INFO/WARNING/ERROR)`. The stack retains at most three messages, deduplicates adjacent identical messages, and animates entry, expiry/progress, stack position and exit. Keep existing chat/status feedback where it conveys feature state.

## Ownership and cleanup

Outgoing content is an inert raster of the isolated content paint commands, captured once per interaction. The backdrop and shell are excluded so the incoming page cannot replay a baked background over them. Page transitions blend isolated premultiplied pixels once: translucent surfaces keep their opacity instead of darkening when two separately faded pages overlap. Three bounded blend buffers are reused during a transition and released afterward. Interrupted transitions flatten the currently visible composition into one raster, so rapid A→B→C navigation does not accumulate a chain of old screens or paint commands. Closing mid-transition freezes that displayed composition and fades it out, avoiding a reversal back into an older page. Resize invalidates captures whose absolute viewport coordinates no longer match.

Neither snapshots nor timelines retain adapters, textures, world references or callbacks. Regular page navigation transfers the canvas, texture and shell/sidebar timelines to the destination; leaving the menu hierarchy releases them. Done/Back to a parent menu keeps the shell visible and transitions its content. A confirmation keeps an inert parent composition behind its own modal surface. If closing completes during frame extraction, the destination paints in that frame too.

The canvas reuses its image and gradient rasters; pose, clip and opacity changes reuse gradients. Unchanged frames perform no rasterization or texture upload. Glyph/font/gradient caches are bounded. There is no background animation manager or coroutine after a screen closes. The shared texture retirement hook runs only to safely release pending resources.

The HUD editor fades its controls, help, tooltips and outlines while preserving saved HUD positions, scales and anchors. Resizing keeps its current appearance and hidden-control preference. Live widget previews use their existing native renderer. Ability-ready notifications use a shared motion envelope for entry and exit within their existing five-second lifetime; widget previews stay readable. Dungeon-win presentation shares the motion settings and cancels immediately on an island change. The standalone loader packages only the pure motion classes: its authorization screen uses a brief Reduced fade and the same default color family without importing protected mod UI/configuration. It clears activation input immediately on closing.

## Verification

Use Java 25:

```powershell
.\gradlew.bat menuNavigationCheck menuMotionCheck hudCheck
.\gradlew.bat check build
```

`menuMotionCheck` exercises fake-clock timelines at 30/60/120/240 FPS, repeated targets, rapid reversals, stagger, cancellation, accessibility changes, coordinate inverses, opacity, clipping, isolated/flattened snapshots, constant-opacity interrupted blending, shared scroll retarget/clamp logic, toast lifetime, cache/idle behavior and cleanup. It writes quality/transition PNG fixtures and prints warmed raster CPU/wall time, allocated bytes and dirty-pixel area in `build/menu-motion-check`.

`menuNavigationCheck` covers responsive card/rail geometry, reversible transformation, lifted hit coordinates, rapid final intent and settled raster reuse. It writes production tile renders for home, intermediate transformation and submenu in Light/Medium/Heavy to `build/menu-navigation-check`. `hudCheck` exercises every raster quality/resize, full/partial upload parity, next-tick/fence texture lifetime and shutdown cleanup, alongside its existing retained layout, text, themes and HUD checks.

These fixtures use the real Java2D rasterizer but are not in-game screenshots or GPU/FPS measurements. Full native screen event dispatch and rendering need Fabric's launcher; pure timelines, ancestry and supported retained-layout checks run through JavaExec. A live performance and interaction pass needs a supported Minecraft 26.1.2 instance with the signed protected release; the raw Loom class-directory client does not satisfy release verification.

For live acceptance, compare all four graphics qualities and Light/Medium/Heavy/Reduced/Off at 30/60/120/240 FPS. Verify a fresh profile's introduction, subsequent launches without replay, Home/card/rail reversal, every section, compact tooltips, rapid page/tab changes, first/repeated scroll reveals, popup replacement, close/reopen, resize and GUI-scale changes, dragging and scrollbar use, Unicode/long text, delayed/missing results, and disconnect/reconnect or island changes during a transition. Change graphics quality repeatedly while menus are transitioning and verify saved values after reopening. Inspect F3 frame time/GPU behavior and Java allocation/resource profiles alongside visual and input alignment. Do not report headless raster timings as Minecraft performance.
