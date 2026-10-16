# Bazaar backend protocol and operating limits

This feature reuses `AccountStore`, its existing SQLite database, community sessions,
sharing policy, Worker rate-limit bindings, and the User Bot's signed HTTP interactions.
It adds no Worker, Durable Object namespace, bucket, cron, Gateway connection, or bot.
Do not deploy this feature or register its Discord commands without separate authorization.

## Market source review, 16 October 2026

- [Hypixel API policy](https://developer.hypixel.net/policies/) expressly permits more
  continuous polling of keyless endpoints and requires caching. It prohibits general API
  proxies for third-party developers. This implementation infers that a fixed, normalized
  market-analysis service within Sky-Myce is permitted: it is demand-driven, keyless,
  shared, bounded, and offers no arbitrary upstream URL, API method, player endpoint,
  credentials, or raw API forwarding. Recheck the policy if that scope changes.
- [Official API reference](https://api.hypixel.net/) documents keyless
  `/v2/skyblock/bazaar` and `/v2/resources/skyblock/election`. The Bazaar example has
  descending `sell_summary` bids and ascending `buy_summary` asks. Our names are order
  sides: **buyPrice/buyDepth are bids; sellPrice/sellDepth are asks**. Volumes and moving
  weekly amounts are mapped to the corresponding side, not copied by field name.
- [SkyCofl API access terms](https://sky.coflnet.com/wiki/api-access) limit free access
  to personal use without redistribution. It is excluded from shared collection and
  history. Premium access is not a fallback.
- [SkyBlock rules](https://support.hypixel.net/hc/en-us/articles/4508088842898-Hypixel-SkyBlock-Rules)
  remain applicable. These routes analyze observations and publish explicitly selected
  representations; none execute, cancel, collect, reprice, or click a trade.

One shared demand-driven refresh runs at most once per 30 seconds during active use or
five minutes for idle requests. Simultaneous refreshes coalesce. There is no timer or
background collector. A canonical 15-second edge cache reduces AccountStore calls;
query strings cannot create additional cache keys. Provider `Retry-After`, including
HTTP dates, and bounded exponential backoff suppress repeated failures.

`observedAt` is the provider timestamp, `retrievedAt` the server retrieval time.
Fresh is at most 2 minutes, aging at most 5 minutes, otherwise stale; the mod uses the
same windows and polls about every 45 seconds while flipping, so a healthy pipeline stays
fresh. Private alerts must carry a fresh observation that matches the shared snapshot. Missing,
malformed, disabled, or nonpermitted sources are unavailable. Cached timestamps never
become newly observed timestamps. Stale data cannot generate a private live alert.

Mayor/perk/minister context is fetched on demand at most hourly. Missing or older-than-
two-hour context is unknown. Alignment with market observations does not prove causality.

## Routes

All paths below start with `/community/bazaar`. Public routes require no Discord connection:

| Route | Result |
| --- | --- |
| `GET /market?active=true` | Version 1 normalized snapshot, products, source timestamps, freshness, retry time, mayor context |
| `GET /history?product=WHEAT&daily=false` | Up to 72 hourly or 366 daily closing observations with their actual observation timestamps |
| `GET /gallery` | Up to 25 explicitly listed active publications, respecting actual redactions |
| `GET /replays/{43-character-token}` | Original animated HTML viewer; `Accept: application/json` returns only the published representation |

Market rows contain `id`, `buyPrice`, `sellPrice`, side volumes, moving-week quantities,
order counts, and up to five depth levels `{price,amount,orders}` per side. Response JSON
is capped at 1.8 MB before persistence; upstream bodies are bounded at 8 MiB.

Personal routes use the existing `Authorization: Bearer <community-access-token>`.
Every POST includes `version:1`, `expectedAccountId`, and `expectedUuid`. The current
token session, generation, canonical owner, and bound Minecraft UUID are rechecked in
the synchronous transaction after authentication. POST bodies are bounded at 64 KiB.

| Route | Additional request fields |
| --- | --- |
| `POST /contributions` | `timezone` IANA identifier, `events` (1–32) |
| `POST /contributions/remove` | `ids` (1–32 source IDs), restricted to the current owner **and** UUID |
| `GET /stats?period=weekly` | Own linked identity's eligible shared totals and explicitly shared summary/publications |
| `POST /session-summary` | `confirm:true`, `summary:{durationMs,realizedProfit,completedFlips}`; latest private summary only; `summary:null` removes it |
| `GET /settings` | Explicitly synced preferences and revision; absent by default |
| `POST /settings` | `syncEnabled:true`, `revision`, allowlisted typed `preferences`; `syncEnabled:false` erases values and retains only an opt-out revision fence |
| `POST /replays/publish` | `confirm:true`, selected `replay` representation |
| `POST /replays/revoke` | Publication `id`; revocation immediately removes the token and gallery access |
| `POST /replays/delete` | Publication `id`; separate from local history deletion |

Each compact realization contains only:

```json
{"id":"32 lowercase hex characters","fingerprint":"64 lowercase hex characters","profit":200,"cost":1000,"proceeds":1200,"fees":20,"durationMs":60000,"completed":true,"observedAt":1792100000000,"quality":"observed"}
```

`cost` includes the allocated actual acquisition cost and acquisition-side fees;
`proceeds` is actual **net** sale proceeds. Sale `fees` are supporting disclosure and
are not subtracted again. `profit=proceeds-cost` is checked within 0.02 coins.
Uncertain, reconstructed, manual, forecast, or simulated records are rejected.
The completed marker belongs only to the final realization of a completed position;
partial realizations otherwise use `completed:false`. Client reports remain unverified.
If a later cancellation closes the position, an identical own ID/fingerprint/evidence
can promote only `completed:false` to `true`. Financial values and original period
attribution stay immutable. An ordinary duplicate or repeated promotion writes nothing.

Source IDs persist across retries, and the server enforces UUID/source-ID and global
exact-fingerprint uniqueness without receiving raw journals. This deduplicates retried
batches and unchanged records copied between devices. It is **best-effort client-receipt
deduplication, not a guarantee that independent observations of a real trade are identical**.

The current native observer receives no verified stable Hypixel order/transaction UUID.
Its logical setup IDs include client observation time and a local sequence; realization
fingerprints also include those order/acquisition IDs and matched quantities. Two
independent clients, different partial-fill boundaries, or a transaction incorrectly
assigned to another account can therefore produce different fingerprints for the same
real-world activity. Both could be accepted if independently classified as eligible.
Ordinary PC handoff is mitigated because a new client that missed the original setup
keeps native-only reconstructed orders estimated and ineligible, but this is not proof
of cross-device or cross-account transaction identity.

Do not replace distinguishing evidence with item/quantity/price/time-bucket hashes:
those would merge genuinely separate identical sales and still miss clock skew and
different fill segmentation. A strict semantic deduplication guarantee would require
portable authoritative transaction IDs and canonical filled-quantity ranges, or
withholding nonportable observations from public ranking. Neither is claimed here.

## Attribution and privacy

Bazaar extends `/community/leaderboard?category=economy&metric=bazaar_profit`, with
`identity=uuid|linked`, the existing pagination/search, and `all-time|weekly|monthly`.
Other leaderboard metrics retain their previous UTC behavior.

Each accepted record stores immutable server receipt time, the submitted valid IANA
timezone, and local calendar week/month boundaries. Weeks start Sunday at local midnight;
DST weeks need not be 168 hours. Late/offline reports up to seven days old enter the
receipt period, never an arbitrarily backdated period. Timezone changes affect future
records only; transition periods can span two timezone settings.

The ranking score is realized net profit, including losses. ROI is summed profit divided
by supported allocated cost. Profit per hour divides by the sum of supported realized-
portion durations, not wall-clock session hours; overlapping positions can overlap time.
Sample sizes and completed counts remain separate. Canonical current account mappings
produce linked totals without returning account IDs, UUIDs, or fingerprints publicly.
Names require the existing public-name consent; hidden names cannot be searched.

Economy/global/leaderboard opt-out and corrective retractions delete the complete
financial/identity row. Only an unassociated fingerprint and eight-day expiry remain
in a separate compact deduplication-token table. Tokens are excluded from ledger capacity
and cleaned on demand; after expiry, genuine original timestamps are already outside
the seven-day new-upload window. These integrity tokens are not described as anonymous
market statistics. Unlinking uses
the established consent snapshot; reassignment cannot carry old profit into another owner.

Replay publication, private session summaries, preference sync, and local history are
independent choices. Replay JSON is projected through an allowlist. Summary mode removes
individual transactions; optional `progress:[{at,profit}]` contains at most 32 coarse
aggregate profit-delta points; the viewer accumulates them for the progressive chart.
`redact:{items,transactions,values,identity}` changes stored JSON,
not only CSS. Exact-value redaction also removes progress values. Replay duration is
explicitly limited to 24 hours; larger sessions are rejected, never silently truncated.
Unlisted is the client's default; gallery listing is a separate selected visibility.
Tokens have 256 bits of entropy; every read checks server revocation and sends no-store.

## Discord

The existing User Bot definitions add `/bazaar stats|summary|replays|privacy` and
`/leaderboard metric:bazaar_profit identity:linked`. Private commands use only the signed
caller and never accept a target username or Discord user. They do not upload a journal.
Registration source is `../discord-bots/src/backend-bot.mjs`; registration is a separate
authorized operation. The production Gateway checkout needs no new bot or connection.

The existing live relay socket receives:

- `bazaar_session`: `id`, `epoch` (32 hex), `active`, `dmEnabled`.
- `bazaar_alert`: `id`, `epoch`, `product`, `netProfit`, `roi`, `confidence`, `capital`,
  `availableCapital`, `observedAt`, `reason`, generated only by the common exceptional-
  opportunity engine after its configured thresholds and relative ranking checks pass.

Activity/DM consent lives in the socket attachment, with no new personal heartbeat or
database writes. Replacement/stop invalidates activity before asynchronous authorization.
The delivery path checks activity again after market lookup and before posting. The
backend also checks source freshness, matching observed time, real market liquidity,
affordability, ROI consistency, quality, existing connection authority, and per-account/
product cooldowns. Stable Discord nonce/enforce_nonce reduces delivery duplication.
There are no offline retries, digests, public opportunity messages, or persistent alert
records. User Bot token availability in the production Worker was not inspected.

## Retention, migration, and measured costs

Schema creation is additive `CREATE TABLE/INDEX IF NOT EXISTS`; existing account,
leaderboard, and relay rows are preserved. No bindings or deployed migrations change.
Rolling back code can leave the additional tables safely unused; do not delete them as
part of an application rollback without a separate data-retention decision.

- One compact financial integrity row per supported realization. Cap: 100,000.
  Retractions replace it with a short-lived separate fingerprint/expiry token.
  At capacity, new contributions fail explicitly; old all-time
  history is never silently rolled away. Account aggregate values are bounded for safe
  numerical reporting.
- Up to ten active replay publications per linked owner. Revocation disables access and
  erases the published payload immediately; at most ten compact revoked management rows
  remain, with older rows pruned. Explicit deletion removes its management row as well.
- At most one explicitly synced preferences row and one explicitly shared latest private
  session summary per linked owner. Disabling preference sync keeps only the owner's
  monotonic revision and a null value, so a delayed older CAS upload cannot resurrect an
  opted-out copy. Ordinary HUD placement and per-channel thresholds/sounds can sync;
  webhook URLs, webhook enablement and private records cannot. No wizard-completion record.
- Two shared current-cache rows (market/election), at most 72 hourly and 366 daily compact
  closing observations. These are sampled closings, not invented OHLC, fills, or volumes.
- Product history uses SQLite scalar JSON projection and a canonical 60-second edge
  cache; hundreds of full-market blobs are never materialized in Worker JavaScript.
- Rankings use bounded indexed joins over compact integrity records. They currently
  calculate sums on demand; there is no stale cached ranking permission decision.

`node test/bazaar.mjs` measured a one-event authenticated JSON upload of **400 bytes**
for the synthetic fixture. Within realization storage only, a cold fresh event in the
emptied fixture ledger used **5 SQL queries, 1 row read, 6 rows written including indexes**;
a warm retry used **1 query, 2 rows read, 0 writes**. A full 32-event duplicate batch also
uses one bounded lookup with zero writes. The cold case includes on-demand
expired-token pruning. These figures exclude existing session, consent, traffic-limit, and
identity checks and are not an end-to-end throughput benchmark.

Verification performed: `npm run check`, full `npm test`, focused Bazaar/Discord
regressions, and the existing 82-test User Bot suite. External providers and Discord
were mocked; no production data was used, no command was registered, no bot started,
and nothing was deployed.
