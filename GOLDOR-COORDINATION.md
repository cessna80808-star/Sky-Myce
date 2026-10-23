# Goldor coordination (F7/M7 phase 3)

Sky-Myce tracks the Goldor phase's terminals, devices, levers and early-enter routes, and recommends the next action for your class: what to do, who to stack with, and when to pre-leap. It combines three things:

- what your client sees: chat lines, your own terminal windows, loaded player positions;
- short updates from other Sky-Myce users in **party chat**;
- the selected strategy profile.

It never clicks, moves or leaps for you. Party chat is its only transport. The Sky-Myce backend and relay are not used.

Settings: **Dungeons → Goldor Coordination** (the strategy is Gold standard by default). The map is the *Goldor Map* widget in the HUD editor. Command: `/sm goldor` (see below).

## Research note (accessed 2026-10-17)

| Topic | Finding | Confidence | Sources |
| --- | --- | --- | --- |
| ADG roles | "ADG" is the Advanced Dungeon Guides Discord. Its role set was published with patch 0.27.2 (6 Oct 2026) in the "ADG M7 Roles" video. No written per-section table is public; the video and its pinned comment could not be read. | high (that it exists), low (on exact content) | [ADG M7 Roles video](https://www.youtube.com/watch?v=8iMu3bLhdyg), [forum "adg bs"](https://hypixel.net/threads/adg-bs.6160642/) |
| Closest written ADG table | DungeonTerminalMap's preset. S1: Tank 1–2, Mage 3–4 (or levers), Archer levers (or 3–4), Healer SS, Berserker i4. S2: Tank 1, Mage 2, Berserker 3 and 5, Archer 4 and right lever, Healer left lever and device (Mage takes the device with MEE2). S3: Tank 1, Healer 2 and device, Berserker 3, Archer 4 and levers. S4: Tank 1, Archer 2, Berserker 3 and left lever, Healer 4 and right lever. | medium | [DungeonTerminalMap](https://github.com/MisterKrister/DungeonTerminalMap) |
| Older guides | Sep 2025: S2 Berserker 4 and 3, Archer 5. Jun 2025: Tank 1, Mage 2, Berserker 3, Archer 4 everywhere. The two guides disagree with each other and with ADG. | high (that they are outdated) | [F7 Guide (Advanced)](https://hypixel.net/threads/floor-7-guide-advanced.5984063/), [Ultimate F7 Guide](https://hypixel.net/threads/ultimate-floor-7-guide.5929821/) |
| F7 vs M7 | Party Finder applies M7 roles in F7 too. No different phase-3 assignment for F7 was found. | medium | forum threads above |
| Early enters | EE = getting into the next section before its gate opens and waiting in a safespot so the team can leap in. Traditional: Archer EE2 after S1 levers, Healer EE3 after the S2 lever, Mage Core. MEE2 and HEE3 are not defined on the web; Mage EE2 (with the S1 levers and the S2 device) and Healer EE3 are inferred from the abbreviations and DungeonTerminalMap. | medium (EE), low (MEE2/HEE3 wording) | [glossary thread](https://hypixel.net/threads/near-exhaustive-list-of-dungeon-abbreviations-12-gazillionth-time-this-has-been-posted-but-its-different-this-time.6004028/), [How to Goldor](https://hypixel.net/threads/how-to-goldor.6006626/) |
| Goldor tick | Every 60 server ticks (3 s) from "[BOSS] Goldor: Who dares trespass into my domain?" until "The Core entrance is opening!". Each tick fires the death barrier, which a revive item can absorb once. A "tick kill" is dying on a barrier tick. | high (timer), medium (meaning) | [Odin TickTimers](https://github.com/odtheking/Odin), [How to Goldor](https://hypixel.net/threads/how-to-goldor.6006626/) |
| Gate | "The gate has been destroyed!" opens the next section; without TNT the gate opens 5 s after the section is complete. In a 2026-10-18 M7 recording that line never appeared. Goldor spoke as each gate went instead: S1 "I will replace that gate with a stronger one!", S2 "The little ants have a brain it seems.", S3 "YOUR END IS NEAR!!". The map switches on any of these once the section is n/n. A gate line up to 3 s before the last completion still counts. Without any line, the map switches 6 s after n/n. | high (recording) | Odin TerminalTimes, forum guides, user recording |
| Terminal titles | Panes "Correct all the panes!", Rubik's "Change all to same color!", Numbers "Click in order!", Starts with "What starts with: 'X'?", Select all "Select all the X items!", Melody "Click the button on time!". | high | Odin TerminalTypes |
| Recent changes | 0.27 (Jul 2026): Simon Says 50% faster; Goldor barrier blocks removed. 0.27.2 (Oct 2026): faster phase transitions; Melody has 3 rows, Numbers 10 numbers. | high | [0.27.2 notes](https://hypixel.net/threads/hypixel-skyblock-0-27-2-the-minister-update-greenhouse-qol-and-more.6159904/), [0.27 notes](https://hypixel.net/threads/0-27-dungeon-update-dungeon-questline-master-mode-changes-room-improvements-more.6129695/) |

Sky-Myce's profile and ADG differ in S2. Sky-Myce has Berserker do **T4 then T3** with Archer on T5; ADG (DTM preset) has Berserker on 3 and 5 with Archer on 4. The research does not override the specified rules. Both are kept as separate profiles.

## Strategy profiles

Profiles are data (`GoldorStrategy.kt`); `GoldorEngine` interprets them. A profile holds:

- **Per-section role plans:** ordered objectives (`s2.t4`, `s3.dev`, `s4.lr` …) and a stack partner.
- **Early-enter routes:** route to runner role.
- **Leap rules:** per section and role. Each has a target role, a reason, whether it is mandatory (an early-enter priority), an "after objective" trigger, and fallback roles with a goal.
- **Plan branches and backups:** a conditional change to a plan (replace or add objectives while a condition holds), and objectives a role does only if nobody else has.
- **Berserker's S2 order** and the **Tank S2 decision table** (empty in the gold standard).
- **S3 device stack pair** (none in the gold standard) and **S4 lever priorities.**
- **The step guide** (gold standard only), described below.
- **Puzzle timing:** estimates and slow/fast class, plus device, lever, gate, leap and travel timing, and the Goldor tick rule.

To change the meta, edit or add a profile. Event handling does not need to change. Profile ids and versions are stable; existing ids keep their meaning.

### `gold-standard` v1 (default): the supplied gold standard, F7 and M7

The supplied class-by-class baseline (received 2026-10-18) lives in `GoldorBaseline.kt`. It is the only profile with a **step guide**:
- every class has its own S1–S4 steps;
- each step is marked as prep, required, conditional (with its condition), optional, recovery or reminder;
- the supplied reference videos and images are attached to their class and section.

The engine follows the profile's role plans and leap rules. Under the recommendation, the HUD adds the current step, the next decision and any recovery that has been triggered.

| Section | Tank | Berserker | Healer | Mage | Archer |
| --- | --- | --- | --- | --- | --- |
| S1 | **T2, then T1**, Last Breath once → pre-leap Mage | Last Breath, i4 → **leap Tank** → stack Tank → pre-leap Mage | SS → leap the EE2 player | left lever, right lever, gate, lights (S2 device) → hold EE2 | **T4, then T3** → leap Mage |
| S2 | T1 → leap Berserker → stack T3 (*if T3 is taken and not Melody: right lever instead*) → leap Healer | **T5, then T3** (*if Archer has Melody: right lever too*) → leap Healer | high (left) lever (*slow party: right lever too*) → gate → HEE3 spot | T2 → into core, back half of the gray carpet | T4, bottom (right) lever → leap Healer (*Mask left: pre-leap now; none: after S2*) |
| S3 | T1 → leap Archer → stack the levers, gate → leap Mage | T3, device (*Archer and Tank slow: levers*) → leap Mage | T2, device **only if Berserker has not done it** (*Archer and Tank slow: levers*) → leap Mage | core: SOS flare, LCM, Ragnarock | T4, left lever + Explosive Shot gate, right lever → leap Mage |
| S4 | T1 (*Berserker failed i4: T3 too*) → leap Mage | T3, bottom lever, top lever → leap Mage | T4, right (top) lever, left (bottom) lever **unless Berserker has it or it is done** → leap Mage | re-enter core at the door corner, dig, Ragnarock | T2 → leap Mage → Ragnarock |

`gold-standard-aee2` is the same baseline for parties that choose Archer EE2. Only the EE2 runner and the S1 leap target change (to Archer). The baseline does not say how Mage and Archer split S1 in that case, and the guide says so.

**Post-i4 (Berserker):** Tank is always the default.
- The reason line shows the i4 time from Goldor's line and whether S1 is still open, and says that Mage is only an alternative.
- A direct leap to Mage is recommended only after the player confirms it is safe (`/sm goldor if mage-direct yes`).
- The 7-second threshold and S1 completion never switch the target on their own.

**S4 lever sides** use the map's convention: right is the top lever (y 128), left is the bottom one (y 121). So Healer's "lever on the right" is the top lever, and Berserker's bottom-then-top order starts on the left lever.

#### Conditions: what is detected and what you confirm

| Condition (`/sm goldor if <id>`) | Used by | How it is known |
| --- | --- | --- |
| `t3-taken`: someone is at S2 T3 and it is not Melody | Tank S2 | Seen when a Sky-Myce user shares the T3 puzzle or the player at T3 sends another mod's Melody call ("Melody 33%"), or when T3 is known to be complete. Otherwise it is unknown, and Tank leaps to Berserker by default. |
| `archer-melody`: Archer has Melody | Berserker S2 | Seen from Archer's shared S2 puzzle or Archer's Melody call in party chat; otherwise unknown. |
| `i4-failed`: Berserker failed i4 | Tank S4 | Never assumed. A known i4 completion rules it out; otherwise only your confirmation sets it. |
| `ss-reset`: SS failed or is late, or Melody is very slow | Mage S1 safespot, Healer S1 retry | Seen when a party member writes "SS reset", "SSRS" or "SS failed" in party chat. Sky-Myce never sends it. |
| `slow-party` | Healer S2 right lever | You confirm. |
| `ee2-failed` | Healer S2 recovery | You confirm. |
| `slow-levers`: Archer and Tank are both slow | Healer and Berserker S3 levers | You confirm; ruled out once both levers are done. No timer decides it. |
| `mask`: you have a Mask left | Archer S2 pre-leap | You confirm. While it is unknown, both branches are shown without a countdown. |
| `mage-direct`: a direct leap to Mage is safe | Berserker S1 | You confirm. |
| `dying`: you are about to die | Healer S2, Archer S2 safespots | You confirm. |

Confirmations last for the run. `/sm goldor if` lists every condition with its current value and how it is known. An unknown condition is never shown as true or false: the HUD writes "If …" until the condition is seen or confirmed.

#### Versioning

The guide carries three safeguards:
- **a version;**
- **a change log:** each `GuideChange` records the version, class, section, summary, source, and whether it is baseline, proposed, tested or accepted;
- **a fingerprint of every step and reference.**

`goldorCheck` fails if a step changes without the fingerprint being updated, so the baseline cannot be edited by accident. A deliberate change bumps the version and records the change. Proposed routes belong in a new change entry or a separate profile, never as a silent edit of v1.

#### Discrepancies with the earlier profiles

- **The Sky-Myce ADG profile differs from the baseline:**
  - S1 is reversed: Sky-Myce ADG has Tank on T1 then T2 and Archer on T3 then T4; the baseline has T2 then T1, and T4 then T3.
  - S2: Berserker goes T4 then T3 there; the baseline has T5 then T3.
  - Mage does the S2 device in S2 there; the baseline has Mage do the lights during S1 and enter core in S2.
  - S3: Healer and Berserker stack the device there; the baseline makes Healer the device backup, not a stack partner.
- **No Tank S2 puzzle-speed table:** the baseline decides Tank's S2 by T3 occupancy and Melody, so the gold-standard profile has no table.
- **Berserker's post-i4 leap:** the baseline's coordination table still lists "slow i4 or S1 complete: leap Mage", but its own Post-i4 section says Tank-first supersedes that. The implementation follows Tank-first.
- **Missing link:** Archer S3 refers to "the reference video" for the hole jump but no link is supplied. The guide says so rather than inventing one.
- **SS reset hotkey:** Healer's optional "SS reset" hotkey is left to the player. Sky-Myce has no permitted feature that sends that message.

### `skymyce-adg` v1: Sky-Myce ADG, F7 and M7 (the default before 2.7.26)

| Section | Tank | Berserker | Healer | Mage | Archer |
| --- | --- | --- | --- | --- | --- |
| S1 | T1, T2 | i4 pre-dev → leap Tank → stack Tank | SS device | both levers, then MEE2 | T3, T4 |
| S2 | T1, then the decision table | **T4, then T3** | left lever, then HEE3 | S2 device (from EE2), T2 | T5, right lever |
| S3 | T1 | T3, then device stack with Healer | T2, then device stack with Berserker | — | T4, both levers |
| S4 | T1 | T3, **bottom** lever | T4, **top** lever | MEE4 Core | T2 |

Leaps:
- **After S1:** everyone leaps to Mage (MEE2). Berserker has already leapt to Tank after the pre-dev.
- **After S2:** everyone leaps to Healer (HEE3).
- **After S4:** everyone leaps to Mage (MEE4/Core).

Early-enter targets are mandatory. If the runner's position is unknown, the target stays and only the timing becomes uncertain. Only an abandoned route or a dead runner opens the proximity fallback; for the S2 leap that is the closest of Healer and Archer to S3 T1.

S4 lever priorities never swap because of position. In S4 the right lever is the top one (y 128) and the left lever the bottom one (y 121).

### `adg-2026` v1: ADG 2026 (DTM preset)

As above, except in S2: Berserker does T3 then T5, and Archer T4 and the right lever. The Tank table then reads Berserker's T3 (first) and T5 (second). The preset is unverified against the ADG video.

### Tank S2 decision table

Berserker's first terminal decides; the second adjusts timing. A finished terminal counts as fast.

| First (T4) | Second (T3) | Tank | Reason shown | Extra margin |
| --- | --- | --- | --- | --- |
| slow | slow | stack T3 with Berserker | T4+T3 slow — stack T3, leap late | +1.0 s |
| slow | fast | stack T3 | T4 slow — stack T3 | — |
| slow | unknown | stack T3 (medium confidence) | T4 slow — stack T3 | — |
| fast | slow | pre-leap, later | T3 slow — delay | +0.5 s |
| fast | fast | pre-leap | T4/T3 fast — pre-leap | — |
| fast | unknown | pre-leap (medium confidence) | T4 fast, T3 unknown | — |
| unknown | any | hold and help T4 (low confidence) | T4 unknown | — |

Slow puzzles are Melody (heaviest), Numbers and Rubik's; Starts with, Select all and Panes are fast. The planning estimates (Melody 6.5 s, Numbers 3.5 s, Rubik's 3.5 s, fast ones 1.5–2 s, unknown 4 s) are not measurements. They reflect 0.27.2's shorter Melody and Numbers and can be tuned in the profile.

### Pre-leap timing

`planPreLeap` treats the leap as one sequence:

1. The section is predicted to open at `now + remaining work + gate delay (5 s)`.
2. The latest leap that still arrives in time is `opens − leap cast − travel − safety margin`.
3. The intended tick kill is the last Goldor tick before that latest leap, less the post-tick delay.
4. Leap just after that tick, inside a short window, then travel from the target's position to your next objective.

The planner shows these phases: waiting for tick, prepare, LEAP, travelling, ready, missed, uncertain.
- **Uncertain:** shown without an exact countdown when the work, the tick or the target's position is unknown.
- **Missed:** when there is no longer time to leap and travel, or when the last fitting tick has passed and another lands before the opening.
- **Recomputed on every change:** an earlier or later tick, faster or slower work, or a target moving all move the window.
- **Approximate when unobserved:** the tick is anchored to Goldor's opening line. Until a tick is observed directly, its timing is medium confidence.

For Tank in S2 the remaining work is Berserker's chain: the first terminal, the walk to the second, then the second.

## Party-chat protocol (`smt1`)

`smt1:<seq>:<event>[/<event>]…` is at most 40 characters and 5 events, sent with `/pc`. `<seq>` is one base-36 character, used only to drop exact repeats.

| Event | Meaning | Example |
| --- | --- | --- |
| `r<role>` | class h m b a t | `rb` |
| `p<n>` | section 1–4, 5 = Core | `p2` |
| `t<s><n><puzzle><q>` | working on terminal n of section s; puzzle m n r s c p or u; progress in quarters | `t24m0` |
| `c<s><n>` | completed terminal n (Hypixel's line does not say which) | `c24` |
| `l<s><l\|r>` | flipped the left/right lever (S4 right = top) | `l4r` |
| `d<s><a\|c>` | at / finished the device | `d3a` |
| `e<2\|3\|4><g\|i\|x>` | MEE2 / HEE3 / MEE4 route going / in / abandoned | `e3i` |
| `q` | ask SM clients for their state once | `q` |

Rules:
- **Strict parsing:** the whole message must match. Every event code is validated and duplicate events are rejected. Senders must be in the run, and messages carry state, never commands.
- **Duplicates and self-echo:** exact repeats from the same sender within 30 s are dropped, and so are your own lines.
- **Sending:** event-driven, never per tick.
  - A newer state replaces an unsent one.
  - An unchanged state is never resent.
  - At most one message per 1.5 s and 12 per minute.
  - Nothing is sent outside a party.
- **Sync:** a client that joins mid-phase asks once with `q`. Others reply with their own role, section and current terminal, at most once per requester per 10 s. Replies are never answered, so clients cannot loop.
- **Hiding:** "Hide Coordination Lines" removes `smt1` lines from your own chat. Players without Sky-Myce see them.
- **When chat is unavailable or off:** only locally observed information is used, and predictions show lower confidence. There is no other transport.

## Tracking without Sky-Myce teammates

Hypixel's `<player> activated a terminal! (n/N)` names the player but not the terminal. The objective is resolved in this order:

1. the terminal that player's SM client said it was working on;
2. a station within 5 blocks of the player's loaded position;
3. the next open objective of that kind in their role's plan, marked *guessed*;
4. the first open objective of that kind, also *guessed*.

A later exact report moves a guess. Device lines during S1 are attributed by role: Berserker's to i4, Mage's to the S2 device once S1's device is done. A missed gate line is recovered from the next section's counter. Puzzle types of players without Sky-Myce stay unknown.

## Every class, every section

Once a player's own objectives are done, the recommendation always names something:
- **Early-enter runners** (Mage after the S1 levers, Healer after the S2 lever, Mage in S4) are told to *Go MEE2/HEE3/MEE4 Core*, then *Hold* once in, instead of being told to leap to themselves.
- **Unclaimed objectives** come first: if no living player of the assigned class is in the party, the nearest such objective is recommended before any leap, so a four-player party still completes the section.
- **Otherwise**, with no leap planned (S3), the open objective whose owner will take longest is suggested as help (low confidence).

## Learned roles

With **Learn Roles** on, every finished section records which class completed which objective. Only exact attributions count: assignment guesses and completions inferred from the counter are excluded. These are **observations**. They never change the strategy unless **Apply Learned Roles** (experimental, off by default) is turned on.

With it on:
- A class that has done the same objective in at least 60% of three or more sections takes its learned sequence instead of the strategy's for that section.
- When two classes claim one objective outside a stack, the class that does it more often keeps it.
- Leap rules, early-enters and the step guide never change.

The data is a small local file (`goldor-learned.json`). `/sm goldor learned` shows it and `/sm goldor learned reset` clears it.

Until 2.7.25, learned roles were applied by default. The gold standard is the trusted baseline, so from 2.7.26 they are applied only on request.

## Map

The widget shows the current section's map (Core uses S4) with a marker per objective:
- **Border:** the class of whoever completed or is working the objective; before anyone starts, the assigned class colour, dimmed. Green when done by an unknown player.
- **Fill:** amber while active, showing the puzzle letter; green when done; `~` when the completion was guessed.
- **Rings:** a white ring on your recommended objective, a faint ring on your role's others.
- **Worker:** their class letter in its colour and their initials, under active markers; grey when stale.
- **Class pips:** a small square per player standing at a station, in their class colour.
- **Party strip:** pinned under the text column, one row per player: class, name, and what they are on (the objective they work, the station they stand at, `→` their next planned objective, or `dead`).

Beside it are the section with its counter and next tick, your recommendation, its reason and timing line, the S3 device arrival order, and route status. Free-floating player dots are not drawn, because the map images are schematic rather than to scale; positions are shown by station instead. The custom leap menu marks the recommended target.

## Commands

- `/sm goldor`: status (roster, objectives, recommendation).
- `/sm goldor role <player> <class>` and `/sm goldor unrole <player>`: manual class overrides.
- `/sm goldor section <s1|s2|s3|s4|core>`: recovery.
- `/sm goldor test <sender> <smt1 message>`: applies a protocol message locally; never sends.
- `/sm goldor learned` and `/sm goldor learned reset`: show or clear the learned roles.
- `/sm goldor guide [class] [s1-s4]`: the step guide. With no argument it lists the classes; with a class it shows that class's required steps per section; with a section it shows that section in full, with clickable references.
- `/sm goldor if [<condition> yes|no|clear]`: lists the conditions, or confirms one for this run.

## Tested vs. needs a real run

`./gradlew goldorCheck` covers:
- the role rules, all S2 table rows, S3 arrival and S4 levers;
- the early-enter priorities, including unknown and stale positions, abandoned routes and dead runners;
- the tick/leap/travel sequence: earlier and later ticks, sooner and later openings, uncertain inputs, low-confidence display;
- protocol validation, lookalikes, dedupe, self-echo, rate limits and coalescing;
- completion matching, guesses corrected by exact reports, section advance and recovery, and the EE device carry-over.

Still to verify in F7/M7:
- whether the first tick is exactly 60 ticks after Goldor's line under 0.27.2's faster transitions;
- whether a tick can be observed directly to re-anchor it (the hook is `GoldorRunState.observeTick`);
- the 5 s gate delay;
- how the i4 pre-dev and EE2 device lines count;
- the puzzle time estimates;
- walking speed (14 blocks/s) and the leap cast time;
- the EE2/EE3/Core stand points;
- the ADG preset against the ADG video.
