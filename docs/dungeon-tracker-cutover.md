# Dungeon chest tracking and the version 2 cutover

The target remains Minecraft 26.1.2 with SkyBlock API 4.2.19. This change repairs the chest observation pipeline and starts a separate generation of confirmed dungeon measurements. It preserves previous data instead of turning old aggregates into verified runs.

## Evidence and failure points

The authorized current Prism session's previous `latest.log` contained a personal opening at **2026-10-10 10:31:06**, with these normalized game messages:

```text
RNG DROP! [rank] <local player> just found a Necron's Handle!
  BEDROCK CHEST REWARDS
    Necron's Handle
    Wither Essence x102
    Undead Essence x130
```

The old RNG parser accepted only `RARE REWARD! ... found ... in their ... Chest!`. The actual current `RNG DROP!` announcement therefore stopped at the first parsing boundary. The new parser accepts this exact additional form, still checks the local player's identity and dungeon location, and still requires an inventory increase. Other players' announcements and party chat quotations cannot create personal acquisitions.

The pinned API source confirms that container titles are stripped, click events carry the originating screen and inventory identity, and a container can be initialized repeatedly after full-content packets. The locally inspected Skyblocker 26.1.2 reward implementation supports both `Bedrock` and `Bedrock Chest` (and the equivalent five other tiers). Sky-Myce previously supported only the short titles, read only `customName` for controls, and parsed unstripped lore/name strings. These are source-confirmed compatibility gaps; the recorded log does **not** contain the failed GUI's exact title or control lore.

Other concrete faults were independent of the GUI: unknown costs prevented tracking entirely; a single matching inventory item confirmed all preview items; repeated clicks replaced the original inventory baseline; the shared inventory observer did no tracker work when Daily Digest was disabled; and statistics consumers continued reading historical aggregate totals.

## Chest confirmation

Titles and control names use bounded formatting/space normalization and explicit aliases. Unrelated title substrings are rejected. Controls use the actual displayed name, are excluded from rewards, and clicks must belong to the observed screen and container. Rewards are refreshed at click time, with bounded counts and item IDs. Known essence names supply explicit essence IDs; formatted essence/shard counts are parsed separately from ordinary stack counts.

Coin costs accept complete normalized coin lines and explicit `FREE`, reject conflicting amounts, malformed grouping and overflow, and remain unknown when no valid cost is available. Missing prices and costs remain nullable; profit is shown as unknown rather than treating missing data as zero. Displayed values remain market estimates.

A preview identity remains stable for repeated packets/clicks and changes with a new screen, changed rewards or a successful reroll. The tracker captures account/profile, world and run context at the click. It requires the matching server reward header and every expected physical item's aggregate inventory increase before publishing a chest receipt. Essence-only previews require the same server header. First item observations keep their identity while later items arrive, so the Digest and tracker enrich one acquisition instead of duplicating it. Repeated headers and repeated observations cannot add another receipt.

Pending observations expire after ten seconds of monotonic elapsed time. Closing the GUI permits a bounded two-second grace for the existing 200 ms inventory coalescer. Profile changes, disconnects, island/world changes, run changes and disabled tracking invalidate the pending observation. Rerolls require exactly one confirmed Kismet inventory decrease after the observed control click; unavailable Kismet prices remain unknown.

Development diagnostics describe the detection/confirmation stage, tier, number of reward kinds and whether a cost was recognized. They do not collect or log player identifiers, item lists, chat bodies, credentials or server addresses. No new polling loop, timer per feature or network connection was added.

## Persistence and historical profit

`acquisitions.json` is now version 2. On its first successful cutover:

- Every existing individual run is preserved with `trackingVersion: 1`.
- Existing confirmed acquisition events become explicitly `legacy-confirmed`; previous import provenance remains intact.
- The net profit already present in `dungeon_tracker.json` is frozen by floor in `legacyProfit`. A `cutoverAt` timestamp makes this operation idempotent.
- Old aggregate profit already includes old receipt activity. Old archive receipts are therefore not added to the inherited aggregate again, including when a previous import marker is already present.
- If no historical aggregate file exists, only the old receipts' known values/costs provide the inherited fallback. This preserves available estimates; it cannot reconstruct unknown historical values.
- Post-cutover measurements go only to version 2 run/receipt records. The old totals remain a frozen historical reference.

The exact pre-cutover acquisition JSON is retained in `acquisitions.json.before-v2-cutover`, independently of any older `before-migration` import backup; historical tracker/digest originals retain the existing `before-acquisitions` backups. Atomic JSON writes preserve previous files if validation/write fails. Unreadable/future acquisition archives and failed legacy cutovers pause recording instead of overwriting or silently resetting history. The existing backed-up historical timing repair remains unchanged.

Overview, loot samples, XP, timing, HUD and party statistics default to version 2 records. The timeline and collection default to confirmed acquisitions, with explicit access to labeled legacy reports. Historical aggregate statistics remain available as a reference; they cannot enter the new verified sample. Saved pre-cutover view preferences switch to confirmed tracking on cutover.

Headline lifetime estimated profit has an inherited/new breakdown. It includes all local profiles for the selected floors, independent of date/session/chest filters. The selected sample's measurements retain their actual profile/filter scope. If a new receipt lacks a price or cost, new/lifetime profit stays unknown while the inherited amount remains visible.

## Historical display and recording repair in 2.4.28

The 2.4.27 confirmed-only defaults hid old run, chest, loot and XP totals, and the HUD had no historical snapshot or useful view outside a current floor. Read-only inspection found all 132 old individual run records and four saved acquisition reports intact. This follow-up repairs their display without restoring, rewriting or inventing history.

The default overview and HUD show inherited profit and separate historical run/chest totals alongside the new measurements. Outside a current dungeon floor the HUD uses all floors, and historical revisions invalidate its cached view. A visible source button opens historical loot and XP; the timeline and museum offer all saved reports, including unscoped legacy entries. Historical aggregate counts and individual archive records remain separate to avoid double counting. Missing new prices or costs keep the combined estimate unknown while the inherited amount stays visible.

Run completion now accepts the actual `Master Mode Catacombs` header and validates it against the active floor. Starts use the event's floor. If the API misses a start event before its floor becomes available, its existing tick handler recovers only an active, verified dungeon run; missed start time and duration remain unknown. Hourly rates require complete timing. Entrance `E` is accepted by persisted floor validation.

During Catacombs profile-tab gaps, tracking may retain an already verified context only when account, domain, profile name and available profile ID still match. Profile changes and disconnects clear it. A cold or mismatched context still cannot record acquisitions.

The pinned inventory handler writes direct player-inventory packets without the slot hook that feeds the API's inventory-change event. The shared observer now also handles that packet and reward headers through its existing 200 ms coalescer. Confirmation still requires the complete physical inventory increase; physical attribute shards have no broad exemption. Fixtures cover partial and complete mixed ordinary-item/shard deliveries.

Java 25 focused tracker, timing, chest and shared checks passed, followed by the complete `--offline check build` including `loaderCheck` (30 tasks, 26 executed). Relay type generation/TypeScript checks and strict backend/staging packaging dry-runs passed; the backend code bundle remained unchanged.

The signed 2.4.28 release was deployed and installed through the real loader in the existing Prism session. Native private-world UI verification showed historical profit and run/chest totals in the default overview, historical XP and 36 loot rows, and all four saved reports through the visible source control. Original view preferences were restored. Acquisition/tracker files and their history backups remained byte-identical to the pre-repair snapshots. Private screenshots and redacted verification evidence are in `C:/Users/krister/.codex/visualizations/2026/10/09/01a11fbc-365f-72a0-9cb1-06fb1c068891/tracker-recovery-20261010/`; raw runtime data is not added to the repository. A fresh Hypixel reward chest and the HUD during an actual dungeon remain unverified.

## Verification and remaining evidence

The Java 25 `dungeonFriendsCheck` task includes the new `ChestTrackingCheck` fixtures and the existing tracker/timing/Digest checks. The new fixtures cover exact titles/controls, formatting, current personal RNG messages, bounded cost/quantity parsing, partial/unrelated inventory changes, shared receipt identities, expiry/GUI-close grace, context/run changes, reroll deltas, unknown prices/costs, version 1 and partially imported archives, negative historical net profit, repeated cutover/restart, corrupt files, original backups and interrupted/failed writes. `dungeonChestProfitCheck` verifies the existing protection/estimation safeguards.

Both focused tasks passed during implementation. Release **2.4.27** subsequently passed the complete Java 25 `--offline check build`, including `loaderCheck`. The signed release was deployed to the production Cloudflare `--env backend` environment (Worker version `ee1f16b0-ebd5-4489-8790-a3835183f507`) and loaded through the real loader in the authorized current PrismLauncher session.

The actual version 2 migration was verified read-only against exact prelaunch copies. All **132 previous runs and 4 acquisition events** remained present without duplication; only their intended legacy version/provenance changed. The cutover backup matched the original archive byte-for-byte and by SHA-256. Frozen historical profit matched the original aggregate exactly, pins/import markers were preserved, and the original tracker and Daily Digest files remained byte-identical. The confirmed view default persisted, and the new run, receipt, timing, XP and confirmed-acquisition samples were all empty, proving the old records did not enter the new sample. Redacted boolean/count evidence is in [the live cutover verification](../build/release-2.4.27-live-cutover-verified.json).

No fresh Hypixel reward chest was opened during this verification. The private-world test verifies the real loader, persistence and migration; it cannot reproduce Hypixel's chest GUI. A controlled newly opened chest with captured GUI title/control lore and resulting inventory/persisted receipt is still required to verify the complete live GUI path. The existing unsaved Handle is not retroactively invented from chat or auction messages.
