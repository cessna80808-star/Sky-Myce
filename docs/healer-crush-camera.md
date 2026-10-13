# Healer Crush Camera

Healer Crush Camera renders a fixed, live secondary world view inside the HUD for the F7/M7 Healer purple-to-yellow Storm sequence. The player remains in control of the normal view and movement. The camera does not send packets, move the player, or request additional server chunks.

There is **no supplied dungeon viewpoint or pillar coordinate**. Camera positions and pillar geometry must be measured in the actual environment. A saved viewpoint alone does not enable automatic activation.

## Open and configure

Run `/sm crush-camera` or `/skymyce crush-camera`, or open **Dungeons → Healer Crush Camera** from the Sky-Myce menu. `/sm` opens the menu; `/sm settings` opens settings. The feature settings are under **Dungeons → Healer Crush Camera**.

1. Join a world and select **Purple** or **Yellow**.
2. Position yourself where the secondary camera should stay, face the relevant pillar from above, and choose **Use my current view**. This records the player's eye position, yaw and pitch once. Subsequent movement or turning does not move the saved camera.
3. Alternatively, enter measured **X, Y, Z, Yaw and Pitch**, then choose **Save entered viewpoint**. Y is the camera position, not necessarily the player's feet height. Angles use Minecraft's convention; positive pitch looks downward. Values must be finite, X/Z within the world bounds, Y between −2048 and 2048, and pitch between −90 and 90. Yaw is normalized.
4. Choose **Preview purple on HUD** or **Preview yellow on HUD** to inspect that saved view. If no view has been saved, starting manual preview captures the current eye position and direction. Save each stage separately in the actual world/server.
5. Use **Size, angle & appearance** to adjust the window, FOV, yaw/pitch adjustments and X/Y/Z offsets. Offsets apply relative to the saved world position; they do not follow the player. Lower FOV zooms in.

The default view area is 160×90 GUI pixels at 16:9, with 65° FOV, a stage label and border. Width is adjustable from 96 to 400 pixels; free height from 64 to 300. **16:9** and **4:3** derive height from width; **Free** allows independent dimensions. Opacity and background opacity are separate. The secondary frame limit is 5–30 FPS, default 15; the loaded-chunk render radius is 2–4 chunks, default 3.

Choose **Move / resize HUD** to use the existing HUD editor: drag the window to move it, drag its lower-right square to resize it, scroll to scale it, and press **V** to toggle its visibility. A hidden window does not keep the secondary world pass running. **Reset presentation** restores size, layout, scale, angle adjustments, offsets and appearance while retaining saved viewpoints and pillar calibration.

## Manual preview and practice

Manual preview is an explicit rendering tool, separate from encounter detection. It lasts at most five minutes and stops when the player dies, the world changes, or the client disconnects. **Stop preview** closes it immediately. Select and preview the other stage through the camera screen; preview does not simulate a leap, crush or automatic stage transition.

Singleplayer supports **preview only**. It cannot authorize pillar capture or automatic dungeon activation. The requested p3sim practice environment has no crush mechanics, so use it for camera/HUD customization only. Measurements made there are scoped to that server and dimension and are not automatically applied to Hypixel. A simulation preview cannot establish that the real F7/M7 sequence works.

When a camera position lies outside the client's loaded chunks, the viewport reports **waiting chunks**. Bring the relevant area within the normal client's loaded world, or select a loaded position. Increasing the secondary radius cannot fetch missing chunks. **Preparing** means the independent renderer has not yet produced terrain; **unavailable** indicates no usable world/pass or a render failure with a bounded retry delay.

## Calibrate automatic activation

Camera viewpoints and pillar calibration serve different purposes: the viewpoints choose what to display; the pillars provide encounter evidence.

1. In a real Hypixel F7 or M7 Storm phase, play as a living Healer. Both stage viewpoints must be saved for the current server/dimension.
2. Identify the actual purple pillar yourself. Aim directly at its **hanging diorite/polished-diorite column**, open the camera screen, and choose **Capture purple pillar**.
3. Repeat for the distinct yellow pillar with **Capture yellow pillar**. The selected stage is an explicit label you assign; the mod does not infer purple/yellow from diorite or import another mod's absolute coordinates.
4. Read the capture result. A flat control pad, unsupported material, incomplete/unloaded geometry, a column resting on the floor, or overlapping purple/yellow calibration is rejected. Capture also requires one uniquely identified, loaded Storm Wither.

Saved geometry is checked again in each encounter. Missing viewpoints, missing chunks, ambiguous Storm identity, changed geometry or unavailable evidence keep automatic activation closed or stop the active sequence. To correct a stage, capture its actual pillar again; do not edit the persisted files to manufacture encounter evidence.

Automatic mode requires the F7/M7 boss context, Healer role and living player, an observed Storm phase, both calibrated pillars and both viewpoints. A leap item click is insufficient: the exact successful-leap server message must name the live Berserker teammate, and an actual server position correction must place the player near that teammate after a real displacement. These observations must agree within the bounded verification window.

After a verified leap, observed server block updates lowering the calibrated purple column near Storm open the purple view. Confirmation requires the same identified Storm to intersect the lowering measured footprint, together with a fresh explicit Storm crush message. Purple confirmation switches to yellow; equivalent yellow confirmation closes the window and consumes the sequence. Notices are bounded and debounced; ordinary damage, stale/duplicate messages, another pillar, missing evidence and elapsed time alone do not confirm a crush. Timeouts only discard stale evidence. Leaving Storm, dying, disabling the feature, changing worlds/profiles or disconnecting resets the encounter state.

## Renderer design and limits

The implementation targets **Minecraft 26.1.2, Fabric and Java 25**, using the game's unobfuscated names and extraction/render architecture. Fabric's version-specific [world rendering documentation](https://docs.fabricmc.net/26.1.2/develop/rendering/world) and [26.1 porting announcement](https://www.fabricmc.net/2026/03/14/261.html) explain the relevant render-state and version changes.

`SecondaryWorldCamera` owns a separate fixed `Camera`, view/projection matrices, `GameRenderState`, `LevelRenderer`, visibility graph, section meshes, entity/block-entity dispatchers, submission state, draw buffers, fog state and color/depth `TextureTarget`. It shares the loaded `ClientLevel` and immutable asset resources. Section compilation borrows the existing bounded asynchronous scratch-buffer pool; it does not reuse the main view's mutable render states or close the shared pool.

HUD extraction submits the independent view. Its world pass runs after the main world render and before GUI drawing, using the current lightmap and vanilla terrain/entity rendering. Scoped output routing applies only during that secondary pass. The normal camera is never swapped, repositioned or restored. Projection, fog, lighting/global uniforms, output overrides, scissor state and the model-view stack are restored in cleanup, including interrupted passes. Native GUI extraction handles the viewport's pose, clipping, scaling and texture orientation. Retired targets and pipeline resources use fenced cleanup; world/resource changes and hidden/removed windows release the pass.

The secondary frame has its own opaque fog clear and sky colors. Minecraft 26.1.2 normally clears world alpha to zero and provides RGB-only sky colors; retaining those alpha values in a GUI texture would expose the main camera's scene behind the sky. Only the secondary renderer's clear/extracted sky receives opaque alpha, preserving their vanilla RGB and the normal main-renderer behavior. At 100% camera opacity the whole frame is independent, including empty sky/fog pixels; reducing the explicit HUD opacity intentionally blends that completed frame with the background.

The extra world pass has a real GPU/CPU cost. Render targets are capped at 1024×768 pixels while retaining aspect ratio, and the independent frame/radius limits bound work. Only client-loaded geometry/entities are available. Vanilla depth, terrain lighting, entities, block entities, particles, sky and camera-local fog are used; clouds, entity outlines, hand/crosshair, main-view post-processing and the improved-transparency compositor are omitted. Translucent terrain therefore uses the ordinary depth path rather than the improved compositor.

**Linux GPU rendering, Sodium, Iris/shader packs, and live F7/M7 mechanic transitions have not been validated.** Windows singleplayer preview and any other completed QA belong in the separate implementation/QA report; this guide does not claim those results. Synthetic checks do not establish actual map geometry, server packet ordering, frame-time impact or visual compatibility.

## Persistence and developer checks

Presentation settings use the existing Resourceful Config category and HUD layout storage. Measured viewpoints and pillar calibrations use bounded, atomic local files in the mod's config directory: `crush-camera-viewpoints.json` and `crush-camera-pillars.json`. Entries are scoped to server/world and dimension, with separate purple/yellow stages. Do not commit these runtime files.

Use Java 25 for the focused executable checks:

```powershell
.\gradlew.bat cameraRenderCheck cameraViewpointCheck healerCrushCameraCheck
.\gradlew.bat check build
```

The checks cover independent camera math and clipping, target/aspect bounds, persisted viewpoint validation, measured pillar geometry, leap provenance, sequencing and stale/duplicate/unavailable evidence. They complement in-game testing; they do not reproduce the real server encounter. Validate both actual F7 and M7 transitions, world/death/disconnect cleanup, main-view stability, GUI scaling and restart persistence before claiming live support.

## Primary mechanic evidence

Noamm's official **26.1.2** source at commit `1f421b4eff542131c31bcb1be53df5a03b9f891f` identifies the Storm P2 pillar material as diorite/polished diorite and assigns color through pillar identity. Its [IHateDiorite implementation](https://github.com/Noamm9/NoammAddons/blob/1f421b4eff542131c31bcb1be53df5a03b9f891f/src/main/kotlin/com/github/noamm9/features/impl/floor7/IHateDiorite.kt) replaces those blocks locally with colored glass; Sky-Myce does not adopt that replacement or its unvalidated absolute coordinates. The primary code is evidence for material, not proof that any specific live/p3sim geometry matches it.

At the same pinned commit, [F7Titles](https://github.com/Noamm9/NoammAddons/blob/1f421b4eff542131c31bcb1be53df5a03b9f891f/src/main/kotlin/com/github/noamm9/features/impl/floor7/F7Titles.kt) classifies the exact server lines `[BOSS] Storm: Oof` and `[BOSS] Storm: Ouch, that hurt!` as successful crushes. These messages do not identify a pillar color; Sky-Myce requires matching observed geometry to associate a stage.

SkyHanni's [DungeonBossApi](https://github.com/hannibal002/SkyHanni/blob/1de28627933a701662fd1f04cadd6a8fa649321d/src/main/java/at/hannibal2/skyhanni/features/dungeon/DungeonBossApi.kt), pinned at `1de28627933a701662fd1f04cadd6a8fa649321d`, corroborates Storm and Goldor server-chat phase boundaries for F7/M7. Skytils' [DungeonTimer](https://github.com/Skytils/SkytilsMod/blob/276c07edf0f1e64956424016f438a5059c63a863/src/main/kotlin/gg/skytils/skytilsmod/features/impl/dungeons/DungeonTimer.kt), pinned to its `1.x` release source at `276c07edf0f1e64956424016f438a5059c63a863`, also uses Goldor's entry message as Storm-phase completion. Neither inspected implementation establishes a color-labelled damage packet or replaces live validation.
