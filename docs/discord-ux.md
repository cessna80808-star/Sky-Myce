# Discord community accounts and private administration

Install the Sky-Myce Fabric mod normally. Local gameplay does not require the
custom Sky-Myce Loader, Discord approval, an activation key or hardware enrollment.
Discord connects the mod to community chat, cosmetics, statistics and shared feeds.

## Connect and manage your own account

Choose **Connect Discord** in the mod. The primary flow opens the existing Discord
application's browser authorization using only the `identify` scope. A server-side
callback checks the short-lived flow, OAuth state and browser cookie, then identifies
the Discord user. Minecraft identity comes from a verified in-game account proof,
not a typed username or UUID. OAuth secrets and tokens are never distributed in the
mod JAR. Community session credentials remain private and can refresh across normal
restarts without requiring the browser again.

When browser connection is unavailable, submit the displayed ten-minute, one-time
code to the existing User Bot. The code belongs to the Minecraft account that
started Connect Discord. It is consumed once; expired or replayed codes require a
fresh request. Never post a connection code in public chat.

**Cancel pending connection** hides the launch/code immediately and asynchronously
calls `/community/connect/cancel` with that exact flow's private polling secret.
Only a confirmed server cancellation is reported as successful; a timeout retains
a bounded retry and warns that an already opened browser or fallback code may
still complete until expiry. A new connection is deferred while cancellation is
unconfirmed. Late poll/start responses cannot connect a different selected account;
an in-flight start returned after Cancel is revoked before its browser is opened.
Cancellation prevents OAuth, fallback and polling from completing and revokes any
session issued by that flow. It retains already completed Minecraft associations.
Disconnecting with both a saved session and a newer pending connection revokes
both authorities separately; failure of one does not skip the other. Repeated
Cancel presses during revocation enqueue nothing and cannot invalidate its result.
No additional operator credential or bot permission is needed for this endpoint.

| User command | Purpose |
| --- | --- |
| `/connect code:...` | Completes the mod's fallback connection request for your Discord identity. |
| `/status` | Shows your Discord identity, associated Minecraft accounts and online restriction status. |
| `/account link code:...` | Connects an additional verified Minecraft account using the same fallback. |
| `/account unlink uuid:...` | Selects one of your associations through autocomplete, then asks for private confirmation before removing it. |
| `/disconnect` | Asks for confirmation, then ends current community sessions. Minecraft associations, cosmetics and sharing preferences are retained. |
| `/help` | Explains linking, sharing and online-service recovery. |

`/account status` and `/account disconnect` are aliases. Reconnect through the mod's
Connect Discord screen. Multiple Minecraft accounts and multiple computers are
supported; installing another operating system or sharing a computer does not
require a hardware reset. A verified reconnect can move a Minecraft association to
its chosen Discord identity; affected old community sessions are ended rather than
requiring staff approval. Existing cosmetics/profile data are migrated with the
verified Minecraft account rather than silently discarded.

Cosmetics commands remain on the User Bot: `/cosmetics link`, `set`, `show`, `reset`
and `unlink`. Connecting or changing sharing preferences does not erase appearance
settings. Statistics sharing is enabled by default unless an existing explicit
preference says otherwise. Use the mod's **Community Sharing** settings to disable
all contributions or individual categories. Opting out leaves chat, cosmetics and
local features available. The backend applies the sharing preference to new
contributions and suppresses/deletes existing contributions according to its
category policy; it must not redistribute suppressed history.

## Administrator reports

The Backend Bot uses the existing Discord-signed HTTP interaction endpoint.
Administrator replies, reports and confirmations are private. Persisted backend
administrator status and the configured guild are required independently of
Discord's command visibility, roles or generic Administrator permission.

| Administrator command | Purpose |
| --- | --- |
| `/account overview user:@Person` | Shows recognizable Discord name and stable ID, Minecraft names/UUIDs, first/last association dates and Discord blacklist status. |
| `/account devices user:@Person page:1` | Shows private device observations, identifier source/platform, first/last dates, associated Minecraft accounts and other observed Discord accounts. |
| `/account sessions user:@Person page:1` | Shows current and past community sessions across Minecraft accounts/computers. |
| `/account audit user:@Person page:1` | Shows private account, connection and blacklist activity. |
| `/skyhawk search query:Name` | Finds connected accounts through readable Discord/Minecraft autocomplete. |
| `/skyhawk lookup discord-id:...` | Provides an explicit stable-ID fallback when the native user picker is unavailable. |
| `/skyhawk overview` | Shows actual account, session and blacklist totals. |
| `/session kick user:@Person` | Reviews ending current community sessions; it grants no hardware restriction and the user may reconnect. |
| `/admin add user:@Person` | Reviews granting backend administrator permission; this does not control permission to run the mod. |

Device identifiers are hashed observations, not an ownership claim or access key.
Their source describes what was available: platform hardware metadata where
supported, or a persisted software installation identity when hardware metadata
was unavailable. A hash cannot perfectly identify every physical computer or stay
unchanged after every hardware/OS change. The source/platform labels prevent
mistaking a fallback identifier for a universal physical-device fingerprint.
No extra raw serial numbers or hardware details are displayed. Full hashes remain
restricted to authorized administrator device reports; ordinary status, public
feeds and leaderboards never include them. Shared or changed identifiers and
multiple devices never automatically authorize, suspend, blacklist or lock anyone.

## Identity blacklist

| Administrator command | Purpose |
| --- | --- |
| `/blacklist add reason:... user:@Person` | Reviews restricting online services for this Discord identity. |
| `/blacklist add reason:... uuid:...` | Reviews restricting online services for one Minecraft identity. |
| `/blacklist remove reason:... user:@Person` or `uuid:...` | Reviews lifting the selected identity restriction. |
| `/blacklist info user:@Person` or `uuid:...` | Inspects one selected identity's blacklist status. |
| `/blacklist list page:1` | Shows private blacklist entries and reasons. |

Choose exactly one Discord user or Minecraft UUID. A device/HWID is never an
accepted blacklist target. Changes require the existing private two-minute
Confirm/Cancel review, tied to the requesting actor, guild, action and exact
identity/options. Changed identity or blacklist state requires a fresh review.
Changes are audited and relevant active community sessions are revoked. A known
Discord restriction applies to its associated Minecraft accounts; an unrelated
user sharing the same hardware is unaffected. Blacklists restrict backend online
services, not local mod gameplay. Removing a blacklist allows a normal reconnect.

The one-time migration preserves existing identities, Minecraft associations and
informational device history. Existing deliberate account suspensions migrate to
Discord blacklist entries; licensing expiry/revocation, enrollment locks and
hardware-transfer cooldowns do not become hidden online requirements.

## Operator configuration and command migration

`/leaderboard` is the single new User command for shared economy, dungeon, RNG
and other metrics. Category/metric/UTC period/search/page choices use the same
persistent backend query as Minecraft. Unfiltered results have Previous/Next
controls; filtered results instruct repeating the command with the same search
and desired page so a button cannot silently drop the filter. Replies contain
only consenting public names or anonymous labels, values and honest source/coverage
notes. They never expose account IDs, raw UUIDs, Discord IDs or device hashes.
Completed tasks are explicitly unavailable until genuine records can be published.
See [metric definitions and privacy](community-leaderboards.md).

The User Gateway process handles chat. Account commands continue through signed
HTTP requests; there is no second Backend Gateway or unsigned administrator API.
Retain both existing applications, public keys, bot tokens and endpoint routing.
Keep bot/OAuth secrets in Worker secret storage, never in a distributed mod or
committed operator file.

To enable browser linking on the existing User application, register the deployed
HTTPS callback `/community/oauth/callback` in its Developer Portal and configure
`DISCORD_OAUTH_CLIENT_ID`, `DISCORD_OAUTH_CLIENT_SECRET`,
`DISCORD_OAUTH_REDIRECT_URI` and `SKYMYCE_PUBLIC_URL` on the account Worker. The
redirect URI must match exactly. Request only `identify`; bot installation scopes
are separate. If browser credentials are absent, the one-time bot fallback remains
available and the mod reports that browser linking is unavailable.

Definitions and the exact retirement manifest live in
`discord-bots/src/backend-bot.mjs`. Registration inventories the existing guild
commands and prints targeted changes, including every deliberate removal:

```powershell
npm run register:user
npm run register:backend
```

After reviewing the concrete inventory/diff, operators apply that selected
migration using `-- --apply`. The script uses individual POST/PATCH/DELETE calls;
it never bulk-overwrites the application. It removes only explicitly named legacy
activation/approval/license/HWID workflows and preserves unknown commands,
unknown extensions, cosmetics and prompt commands. Existing root commands that
served only legacy licensing or build authorization are deliberately retired.
Historical audit controls still open private account/audit reports.

Publish the matching Worker and mod changes together before registering the new
commands. Preserve the live Worker's existing bindings, secrets, chat Gateway
configuration and release pointers; repository baseline variables can lag live
releases. Registration/deployment need an authenticated operator environment.
Source definitions and local mocked tests do not constitute a live deployment,
Discord connection or successful account migration.

Current APIs: [Discord OAuth2](https://docs.discord.com/developers/topics/oauth2),
[application commands](https://docs.discord.com/developers/interactions/application-commands)
and [signed interaction responses](https://docs.discord.com/developers/interactions/receiving-and-responding).
