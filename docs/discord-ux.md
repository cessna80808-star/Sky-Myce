# Private Discord account management

Sky-Myce account commands use the existing signed Discord HTTP interaction
endpoint and the existing account backend. The Gateway process remains an IRC
bridge; it is not another licensing service. Command responses, activation
credentials, device information and audit details stay private.

## User commands

| Command | What it does |
| --- | --- |
| `/status` | Shows your license, linked Minecraft accounts, registered device and access. |
| `/key` | Privately delivers an eligible activation key; backend eligibility and rate limits apply. |
| `/loader` | Provides the authorized current loader and supported Minecraft/version details. |
| `/help` | Explains setup, device transfer and the next step when access needs attention. |

Account/status messages provide the corresponding private actions where useful.
Keep activation codes private. If DMs are blocked, enable messages from the bot
and retry; failed delivery must not be reported as a successful delivery.
Revoked builds must not be distributed through the loader flow.
Private download links recheck the user's current eligibility and the release's
authorization when redeemed. Legacy private loader objects must have a versioned
filename or trusted R2 `customMetadata.version`; an unknown version is unavailable.
Older cached authorizations refresh once to include the verified loader version.
This preserves the existing device binding and hardware-transfer allowance.

Existing `/account`, `/hwid` and `/cosmetics` subcommands retain their syntax.
`/account status` remains an alias for the normal status view. Linking and
approval still use the loader/game's verified private challenge rather than
accepting another person's Minecraft UUID. `/hwid reset confirm:true` prepares
an intentional device transfer under the seven-day policy; normal launches,
reconnects and updates never require it.

## Administrator commands

Select `user:@Person` from Discord's native user picker. Names and avatars make
the selection readable; the backend continues to identify accounts by stable
Discord ID. All administrator operations require a persisted administrator
account and the configured guild, independently of Discord's command visibility.

| Command | What it does |
| --- | --- |
| `/license whitelist user:@Person` | Approves the account and privately delivers onboarding. |
| `/license key user:@Person` | Privately delivers a fresh eligible activation key and replaces earlier unused keys. Use `reissue:false` to refuse replacement. |
| `/license info user:@Person` | Shows license status, Minecraft names, registered device, access and human-readable dates. |
| `/license debug user:@Person` | Shows private technical references for an investigation. |
| `/license revoke user:@Person` | Reviews and confirms revoking the license and active authorizations. |
| `/license restore user:@Person` | Restores the license. |
| `/license extend user:@Person days:30` | Extends a time-limited license by the chosen number of days. |
| `/license reset-hardware user:@Person` | Reviews and confirms an explicit administrator device reset. An optional private reason is audited. |
| `/license force-logout user:@Person` | Reviews and confirms ending current authorizations. |
| `/license uuids user:@Person` | Shows linked Minecraft accounts by name. |
| `/license uuid-remove user:@Person account:PlayerName` | Autocompletes this user's linked accounts, then reviews removal of the selected account. |
| `/license audit user:@Person` | Shows paginated private events; optional event, severity, days and page filters narrow the view. |
| `/license sessions user:@Person` | Shows current authorization, build and recent session activity. |

Destructive changes require a private confirmation. Cancel leaves the account
unchanged. The backend checks the requesting administrator, target, requested
action and current permissions again when a confirmation is submitted. A read-only
view requires no confirmation.
Confirmations expire after two minutes. A changed account, Minecraft unlink set
or previous device owner requires a fresh review before the change can proceed.

For a user who is unavailable in the picker, use `/skyhawk search query:Name`.
Autocomplete results show registered Discord/Minecraft names and resolve to the
same stable account. `/skyhawk lookup discord-id:...` is an explicit advanced
administrator fallback. `/skyhawk overview` gives private licensing totals and
recent security activity.

For an investigation, `/skyhawk event id:123` opens one private audit event
using the reference in an administrator alert. This advanced view remains
administrator-only.

Use `/build list` to inspect known releases. `/build revoke build:2.4.12` selects
a known version and asks for confirmation; `/build restore build:2.4.12` restores
it. The optional `kind:loader` selects loader releases; the default is a mod
build. Autocomplete never invents an unregistered release.

Existing `/authorise`, `/license status`, `/account`, `/hwid`, `/session`,
`/warning`, `/audit`, `/admin add` and prompt commands retain their supported
syntax. Prompt access remains separate from account administration and keeps
its existing hardcoded caller restrictions. Ordinary account management uses
the new grouped commands; technical references belong in debug views.

## Registration and permissions

Definitions live in `discord-bots/src/backend-bot.mjs`. Registration previews
inventory and differences before applying individual command POST/PATCH requests.
It preserves unrelated commands and unknown subcommands; it never bulk-overwrites
an application's command set or implicitly deletes commands.

Run the existing registration preview commands from `discord-user-bot`, using
the separate ignored user/operator configuration files:

```powershell
npm run register:user
npm run register:backend
```

After reviewing the concrete inventory/diff, operators can deliberately apply
that selected registration with `-- --apply`. This implementation task performs
no live registration, messages, provisioning or deployment.

Discord's native USER options, bounded descriptions, autocomplete and individual
command updates follow the [application command documentation](https://docs.discord.com/developers/interactions/application-commands).
Private replies and confirmations use the existing signed interaction endpoint
and [Discord interaction responses](https://docs.discord.com/developers/interactions/receiving-and-responding).

Backend checks remain authoritative even if someone can see an administrative
command. A missing Worker/HTTP capability returns a private unavailable response;
the Gateway process does not substitute an unsigned account API or fabricated
license state.
