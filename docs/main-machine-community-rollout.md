# Main-machine rollout: Sky-Myce 2.5.0

This is a manual operator handoff for the existing Discord applications and
Cloudflare account. These changes have not been deployed or registered from the
laptop. Its Wrangler session is unauthenticated; production operator credentials,
the release private key and the existing separate Backend Bot checkout were not
available here. Local tests use synthetic identities and mocked Discord services.
Keep those limits separate from the live checks below.

The laptop's saved GitHub login also lacks the `workflow` scope. The existing
Actions workflow is retained; [community-services-ci.patch](community-services-ci.patch)
contains the proposed Node 22 relay/bot test matrix. On the main machine, apply it
with `git apply docs/community-services-ci.patch`, review it, then commit and push
using an operator login permitted to update workflows. Local service checks have
already passed; applying this patch adds them to future GitHub Actions runs.

Sky-Myce is now a normal Fabric mod for Minecraft 26.1.2 and Java 25. Local mod
features work without Discord, a custom Loader, approval, license, activation key
or registered device. Discord connects community services; deliberate Discord or
Minecraft identity blacklists restrict those online services. Device identifiers
are private informational observations and never blacklist targets.

## Existing applications and permissions

Keep the existing User and Backend applications. Do not create another bot or
start a second Gateway process. On the main machine, use the live operator
configuration rather than copying stale release pointers from repository defaults.

| Component | Configuration to retain | Discord permissions / intents |
| --- | --- | --- |
| User Bot, `discord-user-bot/` | Existing `USER_BOT_APPLICATION_ID`, `USER_BOT_TOKEN`, `DISCORD_GUILD_ID`, channel IDs and bridge settings | In each selected chat channel: View Channel, Send Messages, Embed Links, Read Message History, Add Reactions. Permission mask `85056` if an installation URL needs one. Gateway intents: Guilds, Guild Messages, Guild Message Reactions, Message Content (`34305`). Enable **Message Content Intent** in Developer Portal → Bot; obtain Discord approval if the application requires it. Members and Presence intents are not used. |
| Signed User commands | Existing User application public key and account Worker interaction endpoint | Guild installation with `applications.commands`; users need Use Application Commands in the chosen channel. No extra Gateway intent is needed for account connection. |
| Backend commands / private alerts | Existing Backend application/public key/token and signed forwarding route | No Backend Gateway is introduced. Account reports use private interaction replies. Keep existing private staff-channel View Channel, Send Messages, Embed Links and Read Message History permissions where existing prompt tooling uses them. Backend alert DMs require the existing bot token and recipients accepting DMs. |
| Human administrator | Persisted `AccountStore` administrator and configured guild | Allow Backend commands for the intended staff users in Server Settings → Integrations → the Backend app. Command visibility is separate from backend authorization. Generic Discord Administrator permission or a role does not grant `AccountStore` administrator status. |

No new Administrator, Kick Members, Ban Members, Manage Messages, Manage Server,
Manage Roles or Manage Channels permission is required for this migration.
Existing optional infrastructure provisioning uses its own operator permissions;
it is not required just to register commands. Rich chat edits/deletes apply to
messages owned by the bot; native messages remain owned by their Discord authors.
Chat media are links/embeds, so this migration adds no Attach Files requirement.

Restrict private staff/report channels to their existing staff access and exclude
the User Bot. Permission overwrites affect the effective permissions even when
a bot's role grants them. The old provisioning helper does not add Add Reactions;
grant it explicitly in the selected chat channels. This table follows the checked
runtime and current [Discord permissions](https://docs.discord.com/developers/topics/permissions)
and [Gateway intents](https://docs.discord.com/developers/events/gateway) documentation.

## Account Worker configuration

Preserve `ACCOUNT_STORE`, `RELAY_ROOMS`, `CONNECT_LIMIT`, `PRIVATE_ARTIFACTS`, the
existing Durable Object migrations, production routes, room/channel IDs,
`DISCORD_BRIDGE_SECRET`, signing public keys and all unrelated live settings.
Do not rename/delete the Durable Objects or replace production account data with
a local test database. A first use performs the additive community-table and
legacy-identity migration. Existing deliberate account suspensions become Discord
blacklist entries; legacy license/device/Loader restrictions do not gate the new
community flow. Preserve existing associations, cosmetics, preferences and audit
history.

| Worker variable / binding | Value / role |
| --- | --- |
| `DISCORD_GUILD_ID` | Existing allowed Discord guild. |
| `USER_BOT_APPLICATION_ID`, `USER_BOT_PUBLIC_KEY` | Existing User app ID and Ed25519 interaction public key. Aliases `DISCORD_USER_APPLICATION_ID`, `DISCORD_USER_PUBLIC_KEY` remain supported. |
| `BACKEND_BOT_APPLICATION_ID`, `BACKEND_BOT_PUBLIC_KEY` | Existing Backend app ID and interaction public key. Aliases `DISCORD_BACKEND_APPLICATION_ID`, `DISCORD_BACKEND_PUBLIC_KEY` remain supported. |
| `BACKEND_BOT_TOKEN` | Worker secret for existing private audit alert delivery. Alias `DISCORD_BACKEND_BOT_TOKEN` remains supported. Account replies/fallback connection do not need a token or activation DM. |
| `ADMIN_BOOTSTRAP_USER_ID` | Retain the existing initial administrator. Subsequent grants use reviewed `/admin add`. Do not repoint this to elevate an unreviewed account. |
| `DISCORD_OAUTH_CLIENT_ID` | Existing User application's OAuth client ID. |
| `DISCORD_OAUTH_CLIENT_SECRET` | **Worker secret**, read from that application's OAuth2 settings. Never distribute it in the mod, User Bot `.env` or repository. |
| `SKYMYCE_PUBLIC_URL` | The account Worker's canonical HTTPS origin, with no path, for example the existing `https://sky-myce-backend.skyblock-relay.workers.dev`. |
| `DISCORD_OAUTH_REDIRECT_URI` | Exactly `<SKYMYCE_PUBLIC_URL>/community/oauth/callback`, also added verbatim to User application Developer Portal → OAuth2 → Redirects. |
| `DRM_PUBLIC_KEYS` | Preserve the existing release verification public-key JSON. The historical name remains in use for signed normal Fabric updates. No identity-flow signing secret is required. |
| `COMMUNITY_RELEASE_MANIFEST_KEY` | Optional; defaults to `releases/community-current.json`. |
| `DISCORD_RNG_CHANNEL_ID` | Optional dedicated RNG feed channel, distinct from every IRC/global channel; set the same ID in the User Bot service environment. Leaving it unset disables Discord RNG posts without disabling local tracking or leaderboard contributions. |
| `COSMETIC_ASSETS` | Dedicated private R2 binding: production/backend bucket `sky-myce-cosmetic-assets`, staging bucket `sky-myce-staging-cosmetic-assets`. Keep it separate from `PRIVATE_ARTIFACTS`. |
| `MINECRAFT_VERSION` | `26.1.2`. |

Browser connection requests only OAuth `identify`. Bot installation scopes
(`bot`, `applications.commands`) are a separate setup; do not add `guilds`, email
or membership scopes to this connection flow. The Worker exchanges the code;
the mod receives no Discord token/client secret. The state and HTTPS browser
cookie bind the callback to its originating connection request. If OAuth settings
are absent, the mod explicitly offers the existing-bot one-time code fallback
(ten minutes, single use). It requires no administrator approval. See
[Discord OAuth2](https://docs.discord.com/developers/topics/oauth2).

Keep automatic request traces and invocation logs disabled on this account
Worker. OAuth start/authorize URLs contain a one-time flow value, and the callback
contains an authorization code and state. Cloudflare's automatic Fetch Handler
traces record the full request URL, including these query values; disabling
invocation logs alone does not disable traces. The checked configuration retains
sanitized application logs with `observability.logs.invocation_logs: false` and
`observability.traces.enabled: false`. Before rollout, review the live environment
and any Tail Workers, real-time log captures, Logpush jobs or external exporters:
they must omit OAuth query values, cookies, authorization headers and request
bodies before storing or forwarding diagnostics. Do not copy raw OAuth URLs into
support reports. See [Cloudflare trace attributes](https://developers.cloudflare.com/workers/observability/traces/spans-and-attributes/).

Run secret commands only on the authenticated main machine, from `relay/`.
Wrangler prompts for the secret; do not put it in the command/history:

```powershell
npx wrangler whoami
npx wrangler secret list --env backend
npx wrangler secret put DISCORD_OAUTH_CLIENT_SECRET --env backend
```

Add the non-secret variables above to the reviewed **live** backend configuration.
Retain existing secrets instead of re-entering or logging them. Environments have
their own variables/bindings; confirm `--env backend` refers to the intended account
Worker. [Cloudflare secrets](https://developers.cloudflare.com/workers/configuration/secrets/)
and [environments](https://developers.cloudflare.com/workers/wrangler/environments/)
describe the operator setup.

Create the dedicated private cape buckets on the main machine if they do not
already exist. Review the existing bucket inventory first, then run the needed
creation commands from `relay/`:

```powershell
npx wrangler r2 bucket list
npx wrangler r2 bucket create sky-myce-cosmetic-assets
npx wrangler r2 bucket create sky-myce-staging-cosmetic-assets
```

Keep public bucket access disabled. Preserve the new `COSMETIC_ASSETS` binding
for each selected environment and regenerate Worker types before deployment.
The [Cosmetics Studio storage guide](COSMETICS_STUDIO.md) records exact routes,
revision checks, two-minute single-use grants, canonical 64×32 PNG validation,
eight-cape/two-MiB account quotas and asynchronous cleanup. Creating a bucket or
building a JAR does not configure a live Worker binding automatically.

## Deploy and register in this order

1. Check out this completed source on the main machine. Back up the reviewed live
   config and the current selected release metadata privately. Confirm the
   production account/database bindings and routes. Use Java 25 for
   `./gradlew build check`; run `npm ci`, `npm test`, `npm run check` from `relay/`,
   and the tests in `discord-user-bot/` and `discord-bots/` before publishing.
2. Configure the OAuth redirect/secret, private cape buckets/bindings and new variables. From `relay/`, review
   `npx wrangler deploy --env backend --dry-run`. Deploy the matching account Worker
   with `npx wrangler deploy --env backend` only after reviewing its concrete live
   configuration. This replaces the license/Loader-required online flow; older
   Loader clients need the standalone mod migration described below.
3. The existing separate Backend Bot service on the main machine forwards the
   original Discord signed body to `/discord/backend/interactions`. Keep that
   application, public key and exact-body/signature forwarding. If it needs a
   service update, use its existing checkout and deployment procedure; this repo
   provides no new service/Gateway to start. Do not replace it with the User Bot.
   User interactions go to the existing account Worker's `/discord/interactions`.
   Verify Developer Portal interaction URLs use the intended deployed services.
4. From `discord-bots/`, create/retain ignored `.env` with only User registration
   credentials and `.env.backend` with only Backend registration credentials.
   The templates are `.env.example` and `.env.backend.example`. Both need their
   selected app ID/token and `DISCORD_GUILD_ID`; do not load Backend credentials
   into the User Gateway runtime. Inventory and review targeted changes:

   ```powershell
   npm ci
   npm run register:user
   npm run register:backend
   ```

   A preview without a token prints source definitions only, not live inventory.
   With credentials, the script verifies the token belongs to the selected
   application. Review all changed/removed names, then apply:

   ```powershell
   npm run register:user -- --apply
   npm run register:backend -- --apply
   npm run register:user
   npm run register:backend
   ```

   Registration uses targeted POST/PATCH/DELETE and preserves unrelated commands.
   The final previews should report no remaining intended changes. It retires
   User `key`, `loader`, `hwid`, old `/account approve/reset`; Backend `authorise`,
   `authenticate`, `license`, `hwid`, legacy `build/warning/audit` roots and old
   `/account lookup/unlink/suspend/unsuspend`. The replacement `/account audit`
   stays available. Inspect any independently registered global commands too;
   this tool migrates guild registrations only. Remove a known obsolete global
   copy explicitly after reviewing its app/name/ID, preserving unrelated commands.
   [Discord application commands](https://docs.discord.com/developers/interactions/application-commands)
   documents the different global/guild scopes.
5. Update `discord-user-bot/` on the main machine, retain its ignored `.env` from
   `.env.example`, run `npm ci`, `npm test`, `npm run check`, then restart its
   existing single supervised process with `npm start`. Keep receipt files and
   the matching bridge secret: runtime `SKY_MYCE_RELAY_AUTH_SECRET` corresponds
   to Worker `DISCORD_BRIDGE_SECRET`. Keep `SKY_MYCE_RELAY_URL` and selected room/
   channel mapping; do not start the compatibility process in `discord-bots/`
   alongside it. Members/Presence intents remain off.
   This repository contains the Node-hosted runtime. Earlier deployment reports
   describe a Cloudflare-hosted Gateway in the separate `D:\Code\Cessna fork\relay`
   checkout, unavailable on this laptop. If that is still the live sole Gateway,
   do not launch a competing Node process: either deliberately stop the old
   Gateway using its existing operator control and replace that host with this
   single supervised Node runtime, or integrate the `community-feed.mjs` delivery
   and `global-chat.mjs` reply changes into that existing service on the main
   machine. Preserve its authentication, claims/receipts and channel isolation.
   Registering commands or deploying the account Worker alone does not update
   the external Gateway implementation. Verify which one process owns each
   channel before enabling RNG or testing replies.
6. Publish and verify the signed standalone mod release as below. Distribute
   that normal Fabric JAR through the existing release channel. Users remove the
   old Sky-Myce Loader and old Sky-Myce JAR from their selected instance's `mods/`
   after closing Minecraft, then install the single standalone Sky-Myce 2.5.0
   JAR alongside its normal Fabric dependencies. Retain unrelated mods and local
   settings. Do not copy two Sky-Myce JARs into one instance.

## Dedicated RNG feed and shared leaderboards

Use an existing or deliberately chosen dedicated RNG text channel in the allowed
guild. Set `DISCORD_RNG_CHANNEL_ID` to its ID in both the account Worker's reviewed
live variables and the existing User Bot environment. The User Bot validates that
this channel is separate from all configured IRC/global channels. Grant View
Channel, Send Messages, Embed Links and Read Message History there; Add Reactions
is needed only in chat channels. The feed deletes only messages owned by that bot,
so it does not require Manage Messages. No new bot, privileged intent or signing
key is introduced.

Retain `SKY_MYCE_RELAY_URL` pointing at the matching account Worker and the existing
bridge credential (`SKY_MYCE_RELAY_AUTH_SECRET` in the User Bot equals Worker
`DISCORD_BRIDGE_SECRET`). The feed uses exact-body HMAC with a separate protocol
domain, short timestamps and persistent nonce rejection at
`POST /discord/community-feed`. Discord tokens stay in the User Bot process.
Keep `RNG_FEED_RECEIPTS_PATH` on durable storage writable by its existing service
account; its default is `.data/rng-feed-receipts.json`. Receipts contain delivery
and Discord message IDs, expire after fifteen minutes and prevent resend after a
lost acknowledgement or process restart. Keep them when restarting the service.

Only newly accepted supported acquisition reports enter the feed. The global
SQLite outbox deduplicates the same acquisition across rooms, rechecks current
sharing preferences before delivery, and requests removal when RNG/name/global
sharing is disabled after a post. A failed delete stays retryable while the bot
is offline or lacks permission. Posts identify client reports as unverified;
anonymous posts contain no Discord IDs, Minecraft UUIDs or hardware identifiers.
There is no seven-day Digest-history replay. Pending deliveries expire after ten
minutes, at most 500 remain pending, and delivery retries use bounded delays.
Delivered message references remain for later consent removal. Contribution and
dedup archives have explicit 100,000-record limits; reaching them rejects new
records instead of silently deleting all-time history. Review capacity before a
large rollout; no automatic archive/export or unlimited retention is claimed.

A room first commits its accepted Digest event and a bounded forwarding intent
in one SQLite transaction before acknowledging storage. At most 256 pending
forwards survive transient room-to-global failure, lost acknowledgements and
hibernation/restarts. Shared Durable Object alarms retry at most three per drain,
with five-second call deadlines, short persisted claims and delays capped at
sixty seconds. They expire within four minutes and before the accepted-event
freshness window; they do not replay historical Digest data. Global acceptance
pins the captured account owner and rechecks current consent/blacklist authority.
Room opt-outs delete pending forwards. A full room queue reports sharing
unavailable instead of claiming successful forwarding; a stored/queued reply
does not claim the Discord post has already completed.

Register the single new `/leaderboard` User command through the targeted
registration steps above. It uses the same persistent query as the in-game
leaderboard, with category, metric, UTC week/month/all-time, search and page
choices. No new leaderboard Worker secret or permission is required. Browser/
client HTTP queries require a current community session; signed User command
queries expose only consent-filtered public projections in private replies.

Net worth, purse and bank rank the latest complete shared snapshot observed in
the selected period; they do not measure income or wealth gained. Dungeon counts
use newly recorded completed runs, and profit sums fully priced chest estimates
plus separately priced reroll costs, including losses. Incomplete/unpriced
receipts and imported aggregate history are excluded. Public-profile wealth
observations are estimates with client observation timestamps, not independently
verified API freshness. RNG counts cover accepted supported reports, not all
Hypixel drops. Completed tasks explicitly remain unavailable until a reliable
publisher exists. All metrics cover consenting participants, with separate rows
per Minecraft identity and honest empty results. See the
[metric/API guide](community-leaderboards.md).

## Sign and publish the normal Fabric release

Use the **built normal mod JAR**, not a sources/dev artifact. `sign-community`
validates the JAR's Fabric ID/version and absence of a Loader dependency. The
existing private Ed25519 release key is needed only on the release operator's
machine; keep it outside this repo. Public `DRM_PUBLIC_KEYS` and the mod's public
pin must match key ID `production-20260927`. Preserve the current keys; rotating
them requires an explicit matching client/Worker change.

From the repository root, substitute actual private paths and create a fresh
manifest output (the script will not overwrite a previous output):

```powershell
node relay/scripts/release.mjs sign-community "<built-jar>" "<operator-private.pem>" production-20260927 26.1.2 2.5.0 "<release-notes.txt-or->" "<new-manifest.json>"
```

For empty notes, use the literal `-` as the notes argument. From `relay/`, upload
the artifact **first**, verify the remote readback SHA-256 against the built JAR,
then upload the selected manifest **last**. The current production bucket name
is shown below; check it against the preserved live `PRIVATE_ARTIFACTS` binding
before running these commands:

```powershell
npx wrangler r2 object put "sky-myce-private-artifacts/releases/Sky-Myce-2.5.0.jar" --file "<built-jar>" --content-type application/java-archive --remote --env backend
npx wrangler r2 object put "sky-myce-private-artifacts/releases/community-current.json" --file "<new-manifest.json>" --content-type application/json --remote --env backend
```

Keep the bucket private. The Worker exposes only the selected, valid signed Fabric
release at `/community/release` and `/community/artifact?version=2.5.0`, without an
account/Loader requirement. Check the live envelope signature with the pinned
public key, manifest product `sky-myce`, protocol `4`, installation `fabric`,
Minecraft/version, artifact size and SHA-256; download that selected artifact and
compare it with the built JAR. Do not call a mere HTTP 200 a signature/hash check.
See [R2 object uploads](https://developers.cloudflare.com/r2/objects/upload-objects/).

The generated metadata expires after seven days. Renew/re-sign metadata with the
same checked artifact when appropriate; invalid/expired metadata hides update
availability but does not stop an installed mod. Retain previous builds and the
previous valid metadata for recovery. Do not change legacy Loader release pointers
to select a normal Fabric artifact.

## Commands and live verification

Ordinary users get `/connect code:...`, `/status`, `/disconnect`, `/help`, and
`/account status/link/unlink/disconnect`. Cosmetic commands retain their existing
shape. `/leaderboard category:... metric:... period:... page:... search:...`
queries shared rankings. `/disconnect` ends community sessions while keeping the profile,
preferences and Minecraft associations; `/account unlink uuid:...` reviews one
selected association removal. There is no `/approve`, activation key or HWID reset.

Persisted administrators get `/account overview/devices/sessions/audit user:...`,
`/skyhawk overview/search/lookup`, `/blacklist add/remove/info/list`, `/session kick`
and `/admin add`. Blacklist changes require exactly one Discord user or Minecraft
UUID, a reason, and the private confirmation. `/session kick` ends community
sessions and does not kick a Discord guild member. Shared hardware and additional
computers must never cause a restriction. Private device reports show observed
identifier/source/platform/time and Minecraft/Discord associations; matching
hashes provide context, not proof of a person's identity.

Use consenting test accounts and an isolated test channel to verify:

- Full-screen Minecraft starts with the standalone JAR and no Loader/Discord
  connection; local gameplay and settings work while the backend is unavailable.
- Browser Connect Discord returns to Minecraft, shows the intended identity,
  and persists across restart. Test cancel/expiry and the existing-bot fallback
  separately; the code works once and does not appear in logs/public messages.
- Cancel before browser completion and while a completion response is pending;
  the opened browser and fallback must reject the cancelled flow. Repeated Cancel
  clicks must not stall the client. Disconnect during a newer relink must cancel
  that flow and revoke the saved session independently. Simulate unavailable
  cancellation transport: the client must report unconfirmed server cancellation
  and offer retry, rather than claiming that the browser/code is already revoked.
- Review Discord account disconnect and administrator session kick while a
  completed flow has not yet been polled. A later poll must not issue a new
  session. Unlink/blacklist also invalidate affected stale flows; removing a
  blacklist permits a fresh connection without reviving an older request.
- One Discord links two Minecraft accounts; the same account connects from two
  PCs and another Discord can share an observed device without either being denied.
- User status contains no device hashes. A non-administrator cannot access private
  reports or administrator autocomplete. Administrators see accurate per-account
  associations; expired/used/changed confirmations require a fresh review.
- A reviewed Minecraft blacklist revokes only affected online sessions; a Discord
  blacklist revokes that identity's online sessions; another user on the same
  hardware and local gameplay remain usable. Remove the restriction and reconnect.
- Disconnect keeps profile/associations. Unlink removes only the selected UUID.
  Existing cosmetics, global/room/party chat, media/reactions and privacy opt-outs
  continue working with the community session rather than a license lease.
- Select a preset cape, upload a valid 64×32 PNG, save its revisioned selection,
  view it from another consenting client, then delete it. Check quota/invalid-file
  errors and verify staging cannot retrieve a production asset.
  Before public Save, preview a ready inactive upload using only its owner's
  session; another account and an unauthenticated public request must not retrieve
  it. Switch to No cape and verify that a native cape disappears while the skin
  and elytra remain unchanged, then restore the Minecraft cape choice.
- With a physical keyboard, Tab to the Studio sliders and verify Left/Right
  adjust the focused scale only; check Up/Down in a dropdown too. Mouse controls
  and Tab/Enter were checked on Windows. The Computer Use helper's injected
  arrows also failed in Minecraft's native chat input, so an actual arrow-key
  result was not established by that session.
- Record a genuine supported RNG acquisition with sharing enabled: one structured
  post reaches only the dedicated channel. Retry/restart must not duplicate it.
  Disable RNG or name sharing and verify the bot removes the corresponding post;
  unavailable channels must not disrupt gameplay or ordinary IRC.
- Inspect a newly captured reward's actual rarity/name/variant in the local
  journal and confirm a delayed complete receipt enriches that same event.
  Old records without rarity must remain Unknown. Check all-time/week/month
  local counts, linked-run durations/profit coverage and compatible community
  comparison: one acquisition report counts once even when item quantity exceeds
  one, alternate Minecraft accounts stay separate, and community omissions do
  not erase personal history. The current shared schema combines profiles and
  observed main/alpha servers; do not call it a main-server-only measure.
  Filtered/custom/legacy selections must explain
  why their community comparison is unavailable.
- Compare in-game and `/leaderboard` selections against the same new records.
  Test tie ranks, UTC boundaries, empty/unavailable metrics, pagination, anonymous
  names and category opt-outs without publishing synthetic data as real results.
- Reply to an uncached Discord parent after restarting the bot, including a
  Minecraft-origin embed. The reply must retain its original sender/body; deleted
  parents show an unavailable preview without blocking the new message.
- An update is optional, shows signed release notes, verifies signature/hash before
  staging, applies on a safe restart, and leaves the installed mod usable when
  canceled or when the update service is unavailable.

Record operator deployment version/time, command-preview results, private release
readback hashes, and the actual live checks without recording codes, browser state,
tokens, signing keys, raw hardware identifiers or private association reports.
The old `npm run smoke` health/socket probe is only a limited transport rejection
check; it cannot prove OAuth, Discord command permissions, gameplay or migration.
Source tests do not establish that any of these production checks succeeded.

See [community architecture](community-architecture.md) for source/build behavior
and [Discord command behavior](discord-ux.md) for the full command contract.
