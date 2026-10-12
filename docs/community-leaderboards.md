# Shared leaderboards and RNG delivery

Minecraft and the existing Discord User Bot query the same persistent SQLite
contributions. Rankings cover consenting Sky-Myce participants and keep every
Minecraft identity separate; alternate UUIDs are not combined into a Discord
account score. They are not a leaderboard of all Hypixel players. New records
are client observations, with estimated valuations where stated.

| Category | Metric | Calculation and limits |
| --- | --- | --- |
| Economy | `networth` | Latest complete shared estimated valuation observed in the period; not wealth gain. |
| Economy | `purse`, `bank` | Latest complete balance snapshot observed in the period; not income. |
| Dungeons | `dungeon_runs` | Sum of new confirmed completed-run records. Imported aggregate totals do not become timestamped runs. |
| Dungeons | `dungeon_profit` | Sum of fully priced chest gross value minus chest fees and separately confirmed priced reroll costs. Losses remain negative; missing values/costs exclude incomplete estimates. |
| RNG | `rng_drops` | Count of newly accepted supported community acquisition reports. The same source acquisition is deduplicated globally across rooms. This is neither official drop probability nor a complete drop catalog. |
| Other | `completed_tasks` | Explicitly unavailable: no reliable completed-task publisher exists. No fabricated zero/score is substituted. |

Wealth uses an optional public-profile client provider for the player's own verified
UUID. API-derived observations remain unverified and estimated. Their timestamp
is the client's observation time, not independent proof of upstream API freshness.
Neither saved tracker aggregates nor historical reports are silently imported as
new leaderboard events. Sources and coverage are returned/displayed with results.

All time uses the complete retained contribution archive. Weekly starts Monday
00:00 UTC; monthly starts the first calendar day at 00:00 UTC. Snapshot metrics
select the latest observation within those bounds. Event metrics sum records
within the bounds. Equal values receive competition ranks (`1, 1, 3`) with stable
internal ordering; filtering preserves the global rank. Pagination supports pages
1–100 and 1–25 rows per page (default ten). Empty complete datasets stay empty.

Current global/category/leaderboard consent is checked before ranking and again
before projecting rows. Category opt-outs remove relevant contributions and
prevent republishing old events after a later opt-in. Public names are independently
optional; anonymous rows are labeled `Anonymous player`, and searches cannot find
their private Minecraft names. Public responses contain no account IDs, raw
Minecraft UUIDs, Discord IDs or hardware identifiers. Explicit identity blacklists
exclude online records. Shared hardware never affects rank eligibility.

## Query contract

`GET /community/leaderboard` requires `Authorization: Bearer <current community
access token>` and returns `Cache-Control: no-store`. Allowed query fields are
`category`, `metric`, `period` (`all-time`, `weekly`, `monthly`), `page`, `limit` and
`search` (at most 100 characters). A metric must belong to its chosen category.
Unknown query fields and malformed selections fail. `/leaderboard` uses the same
query through a signed User interaction in the configured guild, exposing only
its public projection in a private response. It does not grant administrator
access or require a license. Unfiltered Previous/Next buttons retain category,
metric and period. With a search filter, repeat the command with the same search
and desired page rather than losing the filter through a navigation button.

Successful response fields are:

```text
ok, version=1, category, metric, period, startAt, endAt,
metricLabel, description, unit, aggregation, source, coverage,
available, reason?, updatedAt, total, page, limit, search, hasNext,
entries[{rank,player,value,sampleSize,updatedAt,source,verification}]
```

Times are Unix milliseconds; `startAt` is null for all time. `aggregation` is
`latest-snapshot` or `event-sum`. `updatedAt` is the newest included observation,
or zero when none exists. Unavailable metrics return `available:false`, an honest
`reason`, and empty entries. Invalid selections return `invalid_leaderboard`;
expired/missing HTTP authority requires reconnect/refresh. Backend unavailability
does not disable local gameplay or saved tracker history.

## Personal journal comparison

`GET /community/journal-comparison?period=all-time|weekly|monthly` requires a
current community access bearer token and returns `Cache-Control: no-store`.
Omitting `period` selects all time. It aggregates the exact persisted `rng_drops`
dataset used by the rankings, with the same inclusive UTC start/end bounds and
current and historical sharing cutoffs. It does not upload, fetch or reconstruct
personal history. Its own count is bound to the authenticated Minecraft UUID;
account/UUID/search/filter arguments, duplicate periods and unknown periods fail.

```text
ok, version=1, metric=rng_drops, period, startAt, endAt,
participantCount, totalDrops, meanPerIdentity,
ownSharedCount, ownAvailable, ownUnavailableReason?, updatedAt, source, coverage
```

`participantCount` counts Minecraft identities with eligible retained records,
including anonymous participants; it is not the number of connected users.
`totalDrops` sums supported accepted acquisition reports and `meanPerIdentity`
divides that sum by participant count. One acquisition report counts once even
when the acquired stack contains multiple items. With no participants, the count
and total are zero, the mean is null and `updatedAt` is zero. An eligible identity
without reports has `ownSharedCount:0`. If its global, RNG or leaderboard sharing
is disabled, its count is null, `ownAvailable:false` and the reason is
`sharing_disabled`; it can still read the consenting community aggregate.
Names, identities and individual community scores are absent from this response.
Unsafe numeric aggregates fail with `comparison_unavailable` rather than being
rounded or clamped. Expired/revoked sessions and explicit identity blacklists
deny access; normal reconnect/refresh is required.

The personal comparator is compatible only when selecting all activities, all
floors, no search and all profiles of the current Minecraft identity over the
server's returned period. It counts confirmed, supported acquisition records,
excludes legacy imports, alternate accounts and observations after `endAt`, and
keeps personal stack-quantity graphs separate from this report-count metric.
Neither shared records nor the RNG publishing payload identify SkyBlock profiles
or distinguish main from alpha observations: available observations combine per
Minecraft identity across those contexts. No main-only or profile-specific
community comparison is claimed. Local-only history, sharing gaps, outages and
retention limits can make personal and shared counts differ; historical local
records are never backfilled on opening the comparison or enabling sharing.

## Dedicated RNG pipeline

The existing RNG validator accepts only supported new acquisition reports from a
verified community session. Its authoritative UUID, supported item/activity and
source acquisition ID feed the global ledger/outbox. Client-chosen authors do not
grant authority. The accepted ID prevents duplicate counts/posts across rooms.
RNG global/category/name consent is checked again before posting and after a
publication acknowledgement. A late opt-out creates a deletion task for the
specific acknowledged bot-owned message, including when consent changes while
Discord is processing the send. The ledger also retains anonymized consent-safe
metrics separately from Discord delivery.

New live reports may include observed `rarity` (the existing SkyBlock rarity
enum), plain trimmed `displayName` (1–120 characters), and dungeon `floor`
(`E`, `F1`–`F7`, `M1`–`M7`). Unknown details are omitted; null is accepted for
compatibility. Non-dungeon floor values, unsupported tiers, control/formatting
characters and extra event fields fail validation. These details survive the
persisted forwarding queue and Discord outbox without changing source dedup or
the report-count metric. They never infer rarity/floor from a catalog. Client
authentication negotiates `rngMetadataVersion:1` to receive the optional fields
in history/live events; unnegotiated clients receive the original exact six-field
event shape. Attribution and later consent removal remain independently enforced.

The existing User Bot claims at most three deliveries from the signed
`POST /discord/community-feed` bridge. Exact raw-body HMAC uses a distinct protocol
domain, sixty-second timestamps and persisted nonce replay rejection. Claims have
sixty-second leases; retries back off to sixty seconds. Posts carry no mentions
and use structured embeds labeled as unverified client reports. Durable local
message receipts plus a stable Discord nonce prevent publication retries after
lost acknowledgements/restarts. Only IDs are persisted in these bot receipts.
Failed receipt writes retract the post before withholding success.

Each claim also checks at most 100 delivered, unflagged receipts in a rotating
ID order with a persisted per-channel cursor. Blacklisting, relinking or changed
global/name consent can therefore retract older posts even when hundreds of newer
allowed receipts exist. Newly detected removals are flagged durably; explicit
suppression and ready deletion tasks remain ahead of pending publications. This
fair scan does not promise immediate removal: discovering an older receipt can
take multiple bot poll cycles, followed by Discord retry/permission availability.
Cursor progress survives restarts and failed deletion retries do not pin the scan.

Before that global handoff, each room atomically persists a forwarding intent
alongside a newly accepted Digest event before its local storage acknowledgement.
This queue holds at most 256 entries, expires within four minutes/before event
freshness, and shares the room's existing alarm. Three claims per drain, five-second
call deadlines and persisted capped retry delays keep it bounded. A lost global
acknowledgement is safe because the source acquisition ID is globally deduplicated.
Retries pin the captured account owner and recheck current consent/blacklists;
room opt-outs delete pending entries. A full queue returns an explicit sharing
error while preserving the player's local journal. A local queued acknowledgement
is not a completed Discord-delivery receipt.

Pending entries expire after ten minutes and are limited to 500. They do not
backfill the seven-day Digest feed. Delivered references remain available for
later consent removal; a failed delete stays retryable. Explicit 100,000-record
archives reject new entries at capacity rather than silently losing all-time
history. This bounded implementation does not promise unlimited archival storage.
Configuration/permissions and operator capacity checks are in the
[main-machine rollout guide](main-machine-community-rollout.md).

`node test/leaderboards.mjs` exercises production helpers on actual local SQLite:
UTC boundaries, latest snapshots, sums/ties/search/pages, incomplete exclusion,
comparison/ranking parity, empty/opted-out personal counts, malformed records,
unsafe sums, current and historical consent, real session/blacklist HTTP
authority, reconstruction persistence, global dedup, exact-body signed feed
replay rejection and in-flight consent removal. User Bot tests cover failed
acknowledgements/restarts, receipt failures, delete ownership and bounded malformed
responses. These synthetic local tests establish source behavior; they do not
claim a live Discord post, operator deployment or real multiplayer verification.
`node test/rng-forwarding.mjs` separately verifies real local SQLite/runtime
restart, pre-commit outage, commit with lost acknowledgement, exact-once global
retry, busy outbox recovery, ownership/blacklist/consent changes, suppression,
claim leases, queue capacity and freshness expiry.
