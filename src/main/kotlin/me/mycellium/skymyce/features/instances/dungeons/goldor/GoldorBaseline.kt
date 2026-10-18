package me.mycellium.skymyce.features.instances.dungeons.goldor

import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.ARCHER
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.BERSERKER
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.HEALER
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.MAGE
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorRole.TANK
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.S1
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.S2
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.S3
import me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorSection.S4
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.CONDITIONAL
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.OPTIONAL
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.PREP
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.RECOVERY
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.REMINDER
import me.mycellium.skymyce.features.instances.dungeons.goldor.StepKind.REQUIRED

/**
 * The supplied gold-standard F7/M7 terminal strategy (baseline v1, received 2026-10-18), as data: the role plans and
 * leap rules the engine follows, and the class-by-class guide the HUD and `/sm goldor guide` show. Routes, terminal
 * numbers, lever sides, leap targets and conditions follow the supplied document; nothing here is inferred from
 * other guides. Changing any step changes the guide fingerprint, which the executable check pins to the version.
 *
 * S4 lever sides use the map's convention (right = top, y 128; left = bottom, y 121), so Healer's "lever on the
 * right" is the top lever and Berserker's bottom-then-top order starts on the left one.
 */
object GoldorBaseline {
    const val ID = "gold-standard"
    const val VERSION = 1
    /** [guideFingerprint] of the Mage-EE2 baseline guide. Update only together with [VERSION] and a [GuideChange]. */
    const val FINGERPRINT = "d7963b72212f200f"

    private fun t(section: GoldorSection, n: Int) = ObjectiveId.terminal(section, n)
    private fun dev(section: GoldorSection) = ObjectiveId.device(section)
    private fun ll(section: GoldorSection) = ObjectiveId.lever(section, LeverSide.LEFT)
    private fun rl(section: GoldorSection) = ObjectiveId.lever(section, LeverSide.RIGHT)

    private fun ref(title: String, url: String, note: String? = null) = GuideRef(title, url, note)
    private val tankSafespot = ref("Tank pre-leap safespot", "https://www.youtube.com/watch?v=bRY-iNbZY88")
    private val tankPov = ref("Full Tank POV by EngineerAndrew", "https://youtu.be/re2FH_y9wNU")
    private val mageEe2 = ref("Mage EE2 (mee2) example", "https://www.youtube.com/watch?v=xSBzOGSAYxA")
    private val ee3Enter = ref("EE3 gate entry", "https://media.discordapp.net/attachments/1556841354003480667/1557148449105911838/ee3_enter.png")
    private val ee3Wait = ref("EE3 waiting position", "https://media.discordapp.net/attachments/1556841354003480667/1557148659286544486/ee3_wait_moved_back.png")
    private val bersShowcase = ref("Berserker terminal movement showcase", "https://youtu.be/g6mpRuUQB-k")
    private val bersI4 = ref("Example i4", "https://youtu.be/sMa1KOic9dE")
    private val bersAndesite = ref("S3 superbounce off the andesite", "https://media.discordapp.net/attachments/1556841354003480667/1557144340608852032/hit_head_on_andesite.png")
    private val archerS1 = ref("Archer S1 POV", "https://www.youtube.com/watch?v=0DKzAuNOKNY&t=171s")
    private val archerS4 = ref("Archer S4 example", "https://youtu.be/xORRDf-tCqY", "Ignore the early entry in this video; it is not part of the Archer S4 route")

    private fun step(id: String, kind: StepKind, text: String, detail: String = text, objectives: List<ObjectiveId> = emptyList(),
                     leapTo: GoldorRole? = null, condition: GuideCondition? = null, refs: List<GuideRef> = emptyList()) =
        GuideStep(id, kind, text, detail, objectives, leapTo, condition, refs)

    /** The guide, with [ee2] as the player the party leaps to after S1 (Mage in the baseline; Archer for AEE2). */
    private fun guide(ee2: GoldorRole): Map<GoldorRole, Map<GoldorSection, SectionGuide>> {
        val ee2Name = ee2.title
        fun sections(role: GoldorRole, vararg guides: Pair<GoldorSection, List<GuideStep>>, refs: Map<GoldorSection, List<GuideRef>> = emptyMap()) =
            role to guides.associate { (section, steps) -> section to SectionGuide(role, section, steps, refs[section].orEmpty()) }
        return mapOf(
            sections(TANK,
                S1 to listOf(
                    step("tank.s1.loadout", PREP, "Equip the P3 loadout"),
                    step("tank.s1.t2", REQUIRED, "T2, left of SS: mine the platform, run off as you open it",
                        "Go to the second terminal, to the left of SS. Mine out the platform using the demonstrated route. Run off the platform as you open the second terminal, then complete it.", listOf(t(S1, 2))),
                    step("tank.s1.t1", REQUIRED, "Then T1", "Complete the first terminal afterward.", listOf(t(S1, 1))),
                    step("tank.s1.lastbreath", REQUIRED, "Last Breath Goldor once", "Last Breath Goldor once, following the demonstrated route."),
                    step("tank.s1.leap", REQUIRED, "Pre-leap to $ee2Name", "Preleap to $ee2Name when your terminals are complete.", leapTo = ee2),
                    step("tank.s1.safespot", OPTIONAL, "Safespot: mine one block into the outer wall",
                        "If needed, mine one block into the side of the outer wall to reach a safe position and avoid Goldor's damage ticks.", refs = listOf(tankSafespot)),
                ),
                S2 to listOf(
                    step("tank.s2.t1", REQUIRED, "T1: turn right, run off without jumping", "Turn right and run off toward terminal 1 without jumping.", listOf(t(S2, 1))),
                    step("tank.s2.leap-bers", REQUIRED, "Leap to Berserker", "Leap to Berserker to stack terminal 3.", leapTo = BERSERKER),
                    step("tank.s2.t3", REQUIRED, "Stack T3 with Berserker", "Stack terminal 3 with Berserker.", listOf(t(S2, 3))),
                    step("tank.s2.right-lever", CONDITIONAL, "stack the right lever instead",
                        "If somebody is at terminal 3 and terminal 3 is not Melody, stack the right lever instead.", listOf(rl(S2)), condition = GuideCondition.T3_TAKEN_NOT_MELODY),
                    step("tank.s2.leap", REQUIRED, "Leap to Healer", "Leap to Healer afterward.", leapTo = HEALER),
                ),
                S3 to listOf(
                    step("tank.s3.t1", REQUIRED, "Run back to T1 without jumping", "Once at Healer and S2 is complete, run back toward terminal 1 without jumping.", listOf(t(S3, 1))),
                    step("tank.s3.leap-archer", REQUIRED, "Leap to Archer", "Leap to Archer.", leapTo = ARCHER),
                    step("tank.s3.levers", REQUIRED, "Stack the levers, blow up the gate", "Stack the levers and blow up the gate.", listOf(ll(S3), rl(S3))),
                    step("tank.s3.leap", REQUIRED, "Leap to Mage", "Leap to Mage.", leapTo = MAGE),
                ),
                S4 to listOf(
                    step("tank.s4.t1", REQUIRED, "Run to T1 without jumping", "Run to terminal 1 without jumping.", listOf(t(S4, 1))),
                    step("tank.s4.t3", CONDITIONAL, "also do T3", "If Berserker fails i4, also complete terminal 3.", listOf(t(S4, 3)), condition = GuideCondition.I4_FAILED),
                    step("tank.s4.leap", REQUIRED, "Leap to Mage in core", "Afterwards, leap to Mage, who should be in core.", leapTo = MAGE),
                ),
                refs = mapOf(S1 to listOf(tankSafespot), S4 to listOf(tankPov)),
            ),
            sections(MAGE,
                S1 to listOf(
                    step("mage.s1.loadout", PREP, "Equip the P3 loadout", "Right after leaping to Healer as Storm dies, equip the P3 loadout."),
                    step("mage.s1.ll", REQUIRED, "Left lever", "Complete the left lever.", listOf(ll(S1))),
                    step("mage.s1.rl", REQUIRED, "Right lever",
                        "Complete the right lever. More consistent: stand on the quartz and Bonzo toward the left. Slightly faster: run and jump over to the left-lever platform at 600 speed.", listOf(rl(S1))),
                    step("mage.s1.gate", REQUIRED, "Blow up the gate and dig through now", "Blow up the gate and dig through immediately. Do not wait for any death ticks."),
                    step("mage.s1.lights", REQUIRED, "Turn right, lava superbounce up, flick one lights lever",
                        "After passing through, turn right. Bonzo off the ledge, or drop down and jump into the lava. Superbounce in the lava while looking up to reach the lights device. Flick one lever to complete it; this can be done before S1 finishes.", listOf(dev(S2))),
                    step("mage.s1.wait", REQUIRED, "Face the S2 left lever; wait for the team", "Look straight ahead toward the left lever in S2 and wait for the team to leap to you."),
                    step("mage.s1.defence", REMINDER, "On Goldor ticks: Spirit, then Phoenix, then Bonzo", "Use invincibility items in this order, in accordance with Goldor's tick timers: Spirit, Phoenix, Bonzo."),
                    step("mage.s1.safespot", RECOVERY, "Step back, dig into the left wall to bedrock",
                        "If Healer is slow at SS or fails SS late, SS reset or SSRS appears in chat, or there is exceptionally bad Melody, move slightly backward and dig into the left wall until reaching bedrock.", condition = GuideCondition.SS_TROUBLE),
                ),
                S2 to listOf(
                    step("mage.s2.wait", REQUIRED, "Wait for the team to leap to you", "Once S1 is complete, wait for the remainder of the team to leap to you."),
                    step("mage.s2.t2", REQUIRED, "Run down to T2", "Run down and complete the second terminal.", listOf(t(S2, 2))),
                    step("mage.s2.core", REQUIRED, "Dig into core; stand on the back half of the gray carpet",
                        "Dig into core. Dig out from the middle and stand on the gray carpet. The back half of the gray carpet is safe; the front half is not."),
                ),
                S3 to listOf(
                    step("mage.s3.flare", REQUIRED, "SOS flare just outside core", "Look directly in front of you and cast the SOS flare just outside core to give Archer and Berserker ferocity for the DPS phase."),
                    step("mage.s3.rag", REQUIRED, "LCM loadout; keep casting Ragnarock", "While waiting at core, equip the LCM loadout and repeatedly cast Ragnarock."),
                ),
                S4 to listOf(
                    step("mage.s4.wait", REQUIRED, "Wait for the team to leap to you", "Wait for the entire team to leap to Mage."),
                    step("mage.s4.enter", REQUIRED, "Re-enter core at the door's corner two blocks", "Re-enter core through the corner two blocks of the door, following the demonstrated position."),
                    step("mage.s4.dig", REQUIRED, "Dig the two stone rows beside that block", "Dig the two rows of stone next to the block used to re-enter."),
                    step("mage.s4.rag", REQUIRED, "Ragnarock beside the door", "Cast Ragnarock while standing next to the door. This skips waiting for the barriers to fall."),
                    step("mage.s4.after", REQUIRED, "Wait for the team; Ragnarock while terminals run",
                        "Afterward, wait for the team to leap back. If Melody takes a long time, keep casting Ragnarock (very short cooldown) until all terminals are complete."),
                ),
                refs = mapOf(S1 to listOf(mageEe2)),
            ),
            sections(HEALER,
                S1 to listOf(
                    step("healer.s1.ss", REQUIRED, "SS: Start ×3 in ~0.5 s, then the last two buttons",
                        "Complete SS as soon as the terminals phase begins. Quickly press the Start Device button three times in approximately 0.5 seconds, then click the last two buttons the device showed (if it showed only two, click those two). This skips the initial one-button sequence.", listOf(dev(S1))),
                    step("healer.s1.retry", RECOVERY, "SS failed: retry it", "If SS fails, retry it.", condition = GuideCondition.SS_TROUBLE),
                    step("healer.s1.callout", OPTIONAL, "Bind a key to send \"SS reset\" in party chat",
                        "Optionally bind a hotkey to send SS reset in chat so teammates know they may need to dig into safespots. Sky-Myce never sends it for you."),
                    step("healer.s1.leap", REQUIRED, "Leap to the EE2 player ($ee2Name)", "Once finished, leap to the EE2 player. That is $ee2Name in this strategy.", leapTo = ee2),
                ),
                S2 to listOf(
                    step("healer.s2.high", REQUIRED, "High lever: jump off, Bonzo off the next quartz pillar",
                        "Run directly forward and jump off the device platform. Look down at the next quartz pillar and Bonzo off it midair. Activate the high lever.", listOf(ll(S2))),
                    step("healer.s2.right", CONDITIONAL, "also the right lever (never in a fast party)",
                        "Only if the party is slow at terminals, also run off to the right lever and activate it. Not in a fast party: it can stop you reaching EE3 in time.", listOf(rl(S2)), condition = GuideCondition.SLOW_PARTY),
                    step("healer.s2.ee2-failed", RECOVERY, "Superbounce back to the device, do the lever, then EE3",
                        "If EE2 fails, superbounce back to the device and complete the lever. Then proceed to EE3 as normal.", condition = GuideCondition.EE2_FAILED),
                    step("healer.s2.gate", REQUIRED, "Superboom the gate, dig through", "Once the required lever or levers are complete, use Superboom TNT to blow up the gate and dig through the door from the demonstrated entry locations.", refs = listOf(ee3Enter)),
                    step("healer.s2.wait", REQUIRED, "Drop to the EE3 spot, face the hole in the wall", "Fall down, move to the demonstrated waiting position and face the hole in the wall directly in front of you.", refs = listOf(ee3Wait)),
                    step("healer.s2.defence", REMINDER, "As needed: Spirit Mask, then Phoenix, then Bonzo's Mask", "Use these defensive items in order, as necessary: Spirit Mask, Phoenix, Bonzo's Mask."),
                    step("healer.s2.safespot", RECOVERY, "Dig to the left and stand at bedrock", "If you are about to die, dig to the left and stand at bedrock to reach a safespot.", condition = GuideCondition.LOW_HEALTH),
                ),
                S3 to listOf(
                    step("healer.s3.t2", REQUIRED, "T2", "Go to the second terminal and complete it.", listOf(t(S3, 2))),
                    step("healer.s3.device", REQUIRED, "Device, unless Berserker has done it", "Go to the device and complete it if Berserker has not already done so.", listOf(dev(S3))),
                    step("healer.s3.levers", CONDITIONAL, "the levers too, right lever first", "If both Archer and Tank are slow, complete the levers as well, starting with the right lever.",
                        listOf(rl(S3), ll(S3)), condition = GuideCondition.TANK_ARCHER_SLOW),
                    step("healer.s3.leap", REQUIRED, "Leap to Mage for core", "Leap to Mage for core.", leapTo = MAGE),
                    step("healer.s3.mask", OPTIONAL, "Mask left: equip it on the way to your terminal", "If a Mask remains, you may equip it while running toward your terminal to open it faster."),
                    step("healer.s3.safespot", OPTIONAL, "S3 slow and you run out early: leap back to Mage", "If S3 is completed slowly and you run out early, you may leap back to Mage to safespot."),
                ),
                S4 to listOf(
                    step("healer.s4.t4", REQUIRED, "T4", "Run to the fourth terminal and complete it.", listOf(t(S4, 4))),
                    step("healer.s4.right", REQUIRED, "Fall into the lava on your left, flick the right lever", "Fall into the lava on your left and flick the lever on the right.", listOf(rl(S4))),
                    step("healer.s4.left", REQUIRED, "Left lever, unless Berserker has it or it is done",
                        "Look toward the left lever. If Berserker is already doing it, or it is already complete, skip it and leap to Mage. Otherwise flick it first.", listOf(ll(S4))),
                    step("healer.s4.leap", REQUIRED, "Leap to Mage", "After the required lever actions are finished, leap to Mage.", leapTo = MAGE),
                ),
                refs = mapOf(S2 to listOf(ee3Enter, ee3Wait)),
            ),
            sections(BERSERKER,
                S1 to listOf(
                    step("bers.s1.hotbar", PREP, "Keybound slots: Duplex Terminator, Last Breath, Infinileap, fishing rod",
                        "During lightning or while falling to the bottom of P3, put a Duplex Terminator, Last Breath, Infinileap and a fishing rod (for pet rules) in individually keybound hotbar slots. Do not rely on the scroll wheel for these switches."),
                    step("bers.s1.loadout", PREP, "Terminal loadout: a Mask, 100 attack speed, Spider pet", "Equip the terminal loadout, including a Mask and 100 attack speed. Use a Spider pet, which slows Goldor down."),
                    step("bers.s1.stats", OPTIONAL, "/stats hotkey for a quick Bonzo's Mask swap", "Optionally configure a /stats hotkey to swap to Bonzo's Mask quickly if more time is needed; Bonzo's Mask in the top-centre slot makes it faster."),
                    step("bers.s1.lastbreath", REQUIRED, "Last Breath Goldor as he spawns, below the quartz platform",
                        "While Storm is dying, pull back Last Breath and shoot Goldor as soon as he spawns, aiming below the quartz platform as in the i4 video. With a P3 start timer (Odin or Devonian) release about 200 ms + your ping; if the timer is broken, shoot as soon as Goldor is visible. The shot debuffs Goldor and activates Lust for Blood through flame ticks. Optionally stand near the centre of the pressure plate, or slightly forward: the device activates only when the centre of your model is on the plate."),
                    step("bers.s1.i4", REQUIRED, "i4: shoot the lit targets, prefire the next pane",
                        "Shoot all nine targets while they are lit. Shoot a glass pane next to the lit target, preferring one with another uncompleted target beside it; Terminator's spread can hit two at once. Immediately prefire a different pane with uncompleted targets (Odin can show the ideal pane). Do not wait for the first arrow to land. At higher ping, prefire several. If a shot missed, return to that target.", listOf(dev(S4))),
                    step("bers.s1.leap-tank", REQUIRED, "Leap to Tank (the default after i4)",
                        "After completing i4, prioritise leaping to Tank. Most parties are too slow for a direct leap to Mage. Equip Bonzo's Mask before continuing if needed.", leapTo = TANK),
                    step("bers.s1.mage", CONDITIONAL, "a direct leap to Mage instead", "Only when the party's timing and the current situation make it appropriate and reliable, leap directly to Mage instead of Tank.",
                        leapTo = ee2, condition = GuideCondition.MAGE_ROUTE_SAFE),
                    step("bers.s1.melody", RECOVERY, "Tank or Archer has a bad Melody: do any unopened terminals", "If Tank or Archer gets a bad Melody, complete any unopened terminals."),
                    step("bers.s1.preleap", REQUIRED, "Nothing left: Bonzo's Mask + Black Cat, pre-leap to $ee2Name",
                        "If no unopened terminals need completing, equip Bonzo's Mask and Black Cat and preleap to the early-entering $ee2Name.", leapTo = ee2),
                    step("bers.s1.missed", RECOVERY, "Last Breath missed: shoot Goldor with it now", "If Last Breath missed at the start of P3, shoot Goldor with Last Breath afterward; Lust for Blood stacks matter for damage."),
                ),
                S2 to listOf(
                    step("bers.s2.t5", REQUIRED, "Bonzo to T5 without jumping; finish it as you jump off",
                        "After leaping to Mage, Bonzo without jumping to the fifth terminal. Complete it while jumping off so that you land on the platform below.", listOf(t(S2, 5))),
                    step("bers.s2.t3", REQUIRED, "T3", "Complete terminal 3.", listOf(t(S2, 3))),
                    step("bers.s2.lever", CONDITIONAL, "stack the right lever before leaping", "If Archer has Melody, stack the right lever before leaping to Healer.", listOf(rl(S2)), condition = GuideCondition.ARCHER_MELODY),
                    step("bers.s2.leap", REQUIRED, "Leap to Healer", "Leap to Healer.", leapTo = HEALER),
                ),
                S3 to listOf(
                    step("bers.s3.t3", REQUIRED, "Run off the side, lava-bounce and Bonzo to T3",
                        "Immediately run off to the side after leaping, then lava-bounce and Bonzo to the third terminal. Alternative: look up to superbounce and hit your head on the low-hanging polished andesite.", listOf(t(S3, 3)), refs = listOf(bersAndesite)),
                    step("bers.s3.device", REQUIRED, "Bonzo to the device and complete it", "Bonzo toward the device and complete it.", listOf(dev(S3))),
                    step("bers.s3.frames", RECOVERY, "Device stuck after a same-tick click: rotate 8 more times",
                        "If Berserker and Healer click an item frame in the same tick and the device does not register, rotate it eight additional times to complete it."),
                    step("bers.s3.levers", CONDITIONAL, "the levers too, right lever first", "If both Archer and Tank are slow, complete the levers as well, starting with the right lever.",
                        listOf(rl(S3), ll(S3)), condition = GuideCondition.TANK_ARCHER_SLOW),
                    step("bers.s3.leap", REQUIRED, "Leap to Mage for core", "Leap to Mage for core.", leapTo = MAGE),
                    step("bers.s3.mask", OPTIONAL, "Mask left: equip it on the way to your terminal", "If any Masks remain, you may equip one while running toward your terminal to open it faster."),
                    step("bers.s3.safespot", OPTIONAL, "S3 slow and you run out early: leap back to Mage", "If S3 is completed slowly and you run out early, you may leap back to Mage to safespot."),
                ),
                S4 to listOf(
                    step("bers.s4.t3", REQUIRED, "Forward and right to T3", "Run forward and right to the third terminal.", listOf(t(S4, 3))),
                    step("bers.s4.bottom", REQUIRED, "Lava-bounce Bonzo to the levers, bottom lever", "After terminal 3, Bonzo toward the levers using the lava bounce and flick the bottom lever.", listOf(ll(S4))),
                    step("bers.s4.top", REQUIRED, "Bonzo off the yellow glass, top lever", "Bonzo off the yellow glass above that lever and flick the top lever.", listOf(rl(S4))),
                    step("bers.s4.leap", REQUIRED, "Leap to Mage", "After terminal 3 and the levers are complete, leap to Mage.", leapTo = MAGE),
                ),
                refs = mapOf(S1 to listOf(bersShowcase, bersI4), S2 to listOf(bersShowcase), S3 to listOf(bersShowcase, bersAndesite), S4 to listOf(bersShowcase)),
            ),
            sections(ARCHER,
                S1 to listOf(
                    step("archer.s1.loadout", PREP, "Equip the P3 loadout", "After leaping to Healer, equip the P3 loadout."),
                    step("archer.s1.t4", REQUIRED, "Turn around, jump to T4, open it as the phase starts",
                        "Turn around and jump to the fourth terminal. Open it as soon as the terminal phase begins. Optionally fall off the platform to the right.", listOf(t(S1, 4))),
                    step("archer.s1.t3", REQUIRED, "Then T3", "Complete the third terminal afterward.", listOf(t(S1, 3))),
                    step("archer.s1.leap", REQUIRED, "Leap to $ee2Name", "Leap to $ee2Name as soon as your terminals are complete.", leapTo = ee2),
                ),
                S2 to listOf(
                    step("archer.s2.t4", REQUIRED, "Hold W, don't jump, to T4; hold S ~1 s while falling",
                        "After leaping to Mage, hold W and do not jump: this takes you straight to the fourth terminal. Once falling, hold S for about one second to line up. Complete it.", listOf(t(S2, 4))),
                    step("archer.s2.lever", REQUIRED, "Lava on your right, Bonzo off the wall to the bottom lever", "Walk into the lava on your right and Bonzo off the wall to reach the bottom lever.", listOf(rl(S2))),
                    step("archer.s2.preleap", CONDITIONAL, "pre-leap to Healer now, then the S3 movement",
                        "If a Mask remains, leap to Healer immediately and perform the S3 route movement.", leapTo = HEALER, condition = GuideCondition.MASK_LEFT),
                    step("archer.s2.leap", REQUIRED, "Leap to Healer: Mask left, now; none, after S2",
                        "If no Masks remain, wait until S2 is complete, then leap to Healer. With a Mask left, pre-leap immediately.", leapTo = HEALER),
                    step("archer.s2.safespot", RECOVERY, "Dig into the terminal's wall to bedrock", "After the pre-leap, if a safespot is needed, dig into the wall where the terminal is located until reaching bedrock.", condition = GuideCondition.LOW_HEALTH),
                ),
                S3 to listOf(
                    step("archer.s3.t4", REQUIRED, "550+ speed: into the hole, jump again to T4",
                        "Run forward with 550+ speed. Jump into the hole shown in the reference video, then jump again to reach the fourth terminal. (The baseline names a reference video here but gives no link.)", listOf(t(S3, 4))),
                    step("archer.s3.left", REQUIRED, "Bonzo to the left lever; Explosive Shot the gate on the way",
                        "Bonzo from the fourth terminal toward the left lever. While flying, use Explosive Shot to blow up the gate.", listOf(ll(S3))),
                    step("archer.s3.right", REQUIRED, "Bonzo down off the glass to the right lever", "Bonzo downward off the glass from the left lever to reach the right lever as quickly as possible.", listOf(rl(S3))),
                    step("archer.s3.leap", REQUIRED, "Leap to Mage at core", "Leap immediately to Mage, who should be at core.", leapTo = MAGE),
                ),
                S4 to listOf(
                    step("archer.s4.t2", REQUIRED, "Forward-left lava-bounce to T2", "Run forward and left to lava-bounce toward the second terminal, then complete it.", listOf(t(S4, 2))),
                    step("archer.s4.early", OPTIONAL, "No Masks: run out early, leap back to Mage",
                        "Mage on the back half of the carpet in core is a safespot from the kill area, so with no Masks left you can run out early and leap back to Mage."),
                    step("archer.s4.leap", REQUIRED, "Leap to Mage", "Leap to Mage.", leapTo = MAGE),
                    step("archer.s4.rag", REQUIRED, "Ragnarock now, then the boss set", "Cast Ragnarock immediately, then swap to the boss set."),
                ),
                refs = mapOf(S1 to listOf(archerS1), S4 to listOf(archerS4)),
            ),
        )
    }

    private val baselineChange = GuideChange(1, null, null, "Initial gold-standard baseline for all five classes, S1–S4, as supplied", "Supplied gold standard document (2026-10-18)", ChangeStatus.BASELINE)

    private fun profile(ee2: GoldorRole, id: String, title: String, description: String, changes: List<GuideChange>): StrategyProfile {
        val sections = guide(ee2)
        val ee2Route = if (ee2 == MAGE) "MEE2" else "AEE2"
        return StrategyProfile(
            id = id, version = VERSION, title = title, floors = setOf("F7", "M7"), description = description,
            sections = mapOf(
                S1 to mapOf(
                    TANK to RolePlan(listOf(t(S1, 2), t(S1, 1)), note = "T2 first, then T1; Last Breath Goldor once"),
                    BERSERKER to RolePlan(listOf(dev(S4)), stackWith = TANK, note = "Last Breath, i4, then Tank"),
                    MAGE to RolePlan(listOf(ll(S1), rl(S1), dev(S2)), note = "Levers, gate, then the lights"),
                    ARCHER to RolePlan(listOf(t(S1, 4), t(S1, 3)), note = "T4 first, then T3"),
                    HEALER to RolePlan(listOf(dev(S1)), note = "SS straight away"),
                ),
                S2 to mapOf(
                    TANK to RolePlan(listOf(t(S2, 1), t(S2, 3)), stackWith = BERSERKER, note = "T1, then stack T3",
                        branches = listOf(PlanBranch(GuideCondition.T3_TAKEN_NOT_MELODY, replace = t(S2, 3), add = listOf(rl(S2)), reason = "T3 is taken and not Melody — stack the right lever"))),
                    MAGE to RolePlan(listOf(t(S2, 2)), note = "T2, then into core"),
                    BERSERKER to RolePlan(listOf(t(S2, 5), t(S2, 3)), note = "T5, then T3",
                        branches = listOf(PlanBranch(GuideCondition.ARCHER_MELODY, add = listOf(rl(S2)), reason = "Archer has Melody — stack the right lever before leaping"))),
                    ARCHER to RolePlan(listOf(t(S2, 4), rl(S2)), note = "T4, then the bottom lever"),
                    HEALER to RolePlan(listOf(ll(S2)), note = "High lever, then HEE3",
                        branches = listOf(PlanBranch(GuideCondition.SLOW_PARTY, add = listOf(rl(S2)), reason = "Slow party — the right lever too"))),
                ),
                S3 to mapOf(
                    TANK to RolePlan(listOf(t(S3, 1), ll(S3), rl(S3)), stackWith = ARCHER, note = "T1, then stack the levers with Archer"),
                    HEALER to RolePlan(listOf(t(S3, 2), dev(S3)), backup = setOf(dev(S3)), note = "T2, then the device if Berserker has not done it",
                        branches = listOf(PlanBranch(GuideCondition.TANK_ARCHER_SLOW, add = listOf(rl(S3), ll(S3)), reason = "Archer and Tank are slow — levers, right first"))),
                    BERSERKER to RolePlan(listOf(t(S3, 3), dev(S3)), note = "T3, then the device",
                        branches = listOf(PlanBranch(GuideCondition.TANK_ARCHER_SLOW, add = listOf(rl(S3), ll(S3)), reason = "Archer and Tank are slow — levers, right first"))),
                    ARCHER to RolePlan(listOf(t(S3, 4), ll(S3), rl(S3)), note = "T4, left lever and gate, right lever"),
                    MAGE to RolePlan(emptyList(), note = "In core: SOS flare, LCM loadout, Ragnarock"),
                ),
                S4 to mapOf(
                    TANK to RolePlan(listOf(t(S4, 1)), note = "T1",
                        branches = listOf(PlanBranch(GuideCondition.I4_FAILED, add = listOf(t(S4, 3)), reason = "Berserker failed i4 — T3 as well"))),
                    ARCHER to RolePlan(listOf(t(S4, 2)), note = "T2, then Ragnarock"),
                    BERSERKER to RolePlan(listOf(t(S4, 3), ll(S4), rl(S4)), backup = setOf(rl(S4)), note = "T3, bottom lever, top lever"),
                    HEALER to RolePlan(listOf(t(S4, 4), rl(S4), ll(S4)), backup = setOf(ll(S4)), note = "T4, right lever, left lever unless Berserker has it"),
                    MAGE to RolePlan(emptyList(), note = "Re-enter core at the door, Ragnarock"),
                ),
            ),
            routes = mapOf(EarlyEnter.MEE2 to ee2, EarlyEnter.HEE3 to HEALER, EarlyEnter.MEE4 to MAGE),
            leaps = mapOf(
                S1 to mapOf(
                    BERSERKER to LeapRule(TANK, "i4 done — leap Tank (default)", mandatory = true, afterObjective = dev(S4), unless = GuideCondition.MAGE_ROUTE_SAFE,
                        alternative = "Mage only if a direct leap is safe this run"),
                    null to LeapRule(ee2, "$ee2Route — leap ${ee2.title}", mandatory = true, route = EarlyEnter.MEE2),
                ),
                S2 to mapOf(
                    TANK to LeapRule(BERSERKER, "T1 done — leap Berserker to stack T3", afterObjective = t(S2, 1), unless = GuideCondition.T3_TAKEN_NOT_MELODY),
                    // Mage goes into core during S2 and stays; the party leaps to Healer.
                    MAGE to LeapRule(MAGE, "Into core", mandatory = true, route = EarlyEnter.MEE4),
                    ARCHER to LeapRule(HEALER, "HEE3 — leap Healer", mandatory = true, route = EarlyEnter.HEE3, fallback = listOf(HEALER), fallbackGoal = t(S3, 1),
                        preLeapIf = GuideCondition.MASK_LEFT, otherwise = "No Mask: wait for S2 to finish, then leap"),
                    null to LeapRule(HEALER, "HEE3 — leap Healer", mandatory = true, route = EarlyEnter.HEE3, fallback = listOf(HEALER, ARCHER), fallbackGoal = t(S3, 1)),
                ),
                S3 to mapOf(
                    TANK to LeapRule(ARCHER, "T1 done — leap Archer to stack the levers", afterObjective = t(S3, 1)),
                    null to LeapRule(MAGE, "Core — leap Mage", mandatory = true, route = EarlyEnter.MEE4),
                ),
                S4 to mapOf(null to LeapRule(MAGE, "Core — leap Mage", mandatory = true, route = EarlyEnter.MEE4)),
            ),
            berserkerS2Order = listOf(t(S2, 5), t(S2, 3)),
            // The baseline has no puzzle-speed table for Tank: T3 occupancy and Melody decide (a plan branch).
            tankS2Table = emptyMap(),
            s3DeviceStack = null,
            s4LeverPriority = mapOf(HEALER to rl(S4), BERSERKER to ll(S4)),
            puzzles = GoldorProfiles.SKY_MYCE.puzzles,
            leap = GoldorProfiles.SKY_MYCE.leap,
            tick = GoldorProfiles.SKY_MYCE.tick,
            objectives = GoldorProfiles.SKY_MYCE.objectives,
            guide = StrategyGuide(id, VERSION, title, "Supplied gold standard F7/M7 terminal strategy", sections, changes,
                if (ee2 == MAGE) FINGERPRINT else guideFingerprint(sections)),
        )
    }

    /** The baseline: Mage early-enters S2 (mee2). */
    val GOLD_STANDARD: StrategyProfile = profile(MAGE, ID, "Gold standard v$VERSION",
        "The supplied baseline for all five classes: Tank T2 then T1, Mage levers, gate and lights (MEE2), Healer SS then HEE3, Berserker i4 then Tank, Archer T4 then T3; Mage holds core from S2.",
        listOf(baselineChange))

    /** The same baseline for parties that choose Archer EE2: only the EE2 runner and the leap target change. */
    val GOLD_STANDARD_AEE2: StrategyProfile = profile(ARCHER, "$ID-aee2", "Gold standard v$VERSION (AEE2)",
        "The baseline with Archer early-entering S2. The baseline does not define how Mage and Archer split S1 in AEE2; agree it with your party.",
        listOf(baselineChange, GuideChange(1, null, S1, "Archer runs EE2: the S1 leap target and EE2 runner are Archer", "Party configuration allowed by the baseline", ChangeStatus.PROPOSED)))
}
