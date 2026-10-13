# Administrator RNG tester

The tester exercises the existing RNG path using explicit **TEST** fixtures. It never records a genuine drop, changes your personal journal or tracker, adds a contribution or leaderboard score, queues a shared feed event, or sends to the production RNG channel. Mock delivery is the only delivery mode; there is no production destination option.

## Minecraft

Open `/sm rng-test` or the RNG Tester entry in the menu. Connect the currently selected Minecraft account to Discord first. Each click requires the current community session and a fresh backend check of persisted administrator authority. Opening the screen, Discord roles, cached client flags and offline access grant no authority. If the backend is unavailable or rejects your identity, the screen does not run its local fixture.

Choose Dungeon handle, Slayer heart, Duplicate acquisition or Malformed input. **Run dry-run** runs the production relay validator and acquisition duplicate decision in disposable memory. After the authorized response is accepted for the same current account/session, the client also exercises `DigestRngParser`, Minecraft text normalization, the inventory confirmation gate, `mergeAcquisition` and `rngJournal` on an in-memory archive. Dungeon announcements require a synthetic inventory increase; exact receipt retry leaves one temporary record. No repository, module callback or persistence writer is used.

The optional local chat text override is bounded to 600 characters. Use the synthetic player `TestPlayer`; dungeon fixtures use `THE_CATACOMBS`, and the slayer fixture uses `HUB`. This text remains local. The displayed server fixture is separately labeled, so an override does not masquerade as a shared report. Sender-prefixed chat, the wrong player, unsupported drops and malformed text should be rejected. Clear the override to restore the selected fixture.

**Test mock delivery** additionally renders the normal Discord embed presentation with TEST labeling and runs the normal publication/receipt/ACK helper against a closed in-memory transport. It deliberately fails the first ACK after recording its mock receipt. Retrying produces one mock post and two ACK attempts, rather than a duplicate post. The mock embed and transport counts appear in the results. The production User Bot uses the same presentation and retry helper, with its existing persistent receipts and configured transport.

Closing the screen, changing the selected Minecraft account, disconnecting or changing the community session invalidates outstanding requests and their results. Each subsequent test proves authority again. Completed output describes a test performed at that time; it is never a reusable administrator grant.

## Existing Backend Bot

The same configured Backend Bot supports private native commands in its configured guild:

```text
/rng-test dry-run fixture:handle
/rng-test dry-run fixture:warden-heart
/rng-test dry-run fixture:duplicate
/rng-test dry-run fixture:malformed
/rng-test mock-delivery fixture:duplicate
```

Use exactly one of `fixture` or `input`. The optional `input` is normalized report JSON, at most 2000 characters. Its event must use a supported catalog item/activity, a 32-character hexadecimal acquisition ID, a fresh millisecond observation time, and valid optional observed rarity/display name/floor. For example, replace `occurredAt` with the current millisecond timestamp:

```json
{"type":"rng_publish","showName":false,"event":{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","item":"NECRON_HANDLE","activity":"dungeon","occurredAt":1790000000000,"rarity":"LEGENDARY","displayName":"Necron's Handle","floor":"F7"}}
```

The old timestamp above intentionally becomes invalid when it is not fresh. Do not use a genuine acquisition ID: all inputs are administrator test reports, not historical backfill. The result is private and clearly marked TEST. A private command reply is the only Discord request; mock delivery never calls a channel endpoint. The User Bot cannot expose this Backend command. Exact-body Discord signatures/application checks, guild validation, persisted administrator and identity-blacklist checks, interaction replay protection and a 12-per-minute tester limit remain enforced.

After deploying the reviewed backend on the main machine, register the additive Backend command with the existing targeted command registration workflow. No new bot, Gateway process, token, channel or service is required. This implementation does not provision, register or deploy anything itself. See [main-machine handoff](main-machine-community-rollout.md) for the existing operator process.

## HTTP contract and isolation

`POST /community/admin/rng-test` accepts a current Bearer community access token and JSON:

```json
{"expectedAccountId":"captured-owner","expectedUuid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","mode":"dry-run","fixture":"handle"}
```

`mode` is `dry-run` or `mock-delivery`. Replace `fixture` with `input` for the bounded normalized report described above. No query arguments, channel IDs, URLs, production flags or caller-selected identity are supported. The captured expected account and Minecraft UUID must match the authenticated session. The backend rechecks session revocation, account generation, UUID ownership, blacklist and persisted administrator state after asynchronous mock execution before returning an authorized result. Responses use `Cache-Control: no-store`.

Success is version 1 with `test:true`, `authorized:true`, the selected `mode`, bounded `cases` (`accepted`, `duplicate`, `invalid`, or `rate_limited`), described `downstream` steps, and `writes` containing exactly `personal:false`, `acquisitions:false`, `shared:false`, `leaderboards:false`, `productionDiscord:false`. Mock success also includes `mock.posts`, `mock.acknowledgements`, `mock.retrySuppressed` and the TEST presentation. A malformed fixture may produce only rejected cases, with no mock publication. Errors include `admin_required`, `session_expired`, `identity_blacklisted`, `session_identity_changed`, `invalid_rng_test` and `rate_limited`. No authority is inferred from a failed response.

Focused checks cover production parser/journal deduplication and strict client response validation, real Durable Object SQLite authority/isolation, signed native interactions, replay, invalid input, blackout/revocation/ownership changes, normal DigestRng validation parity, mock ACK failure/retry and the existing production feed receipt/restart/retraction behavior. These checks use synthetic identities and a closed transport; they do not prove live deployment or actual Discord channel delivery.
