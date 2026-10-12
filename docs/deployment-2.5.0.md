# Sky-Myce 2.5.0 main-machine rollout

Deployed on 12 October 2026 from the community architecture commit `0f56b6b`,
fast-forwarded into `main`. Sky-Myce is a normal Fabric mod for Minecraft 26.1.2
and Java 25. Local features do not require Discord, a license or the custom Loader.
Community services retain verified Minecraft proofs and explicit identity blacklists.

## Release and deployed services

| Item | Verified value |
| --- | --- |
| Fabric artifact | `releases/Sky-Myce-2.5.0.jar`, 16,589,900 bytes |
| Artifact SHA-256 | `14da53028dbf2648b12d86abf89fceca3791b9f6902752b536d22feb82605ac2` |
| Signed selection | `releases/community-current.json`, protocol 4, installation `fabric` |
| Signing key | Existing pin `production-20260927`, unchanged |
| Metadata expiry | 19 October 2026, 12:27:22 UTC |
| Backend version | `69944696-37c9-483c-af94-5884f331c558` |
| Staging version | `7f56c01c-f9bc-4d4d-a364-3a962467a59d` |
| Backend/staging module SHA-256 | `d1a10b8f32720f16b05b978818cc15fdd3aa81383b6ca8893650704f3196fcde` |
| Existing Gateway version | `9c68abea-8302-4e04-a323-6101849f5340` |
| Gateway module SHA-256 | `4eabcc34296dda4729f803b39b65b7a19fcd5e3e63c08eaac944f5949398b300` |

The artifact was uploaded before the selected manifest. Private R2 readbacks,
the live release envelope's pinned signature, and the downloaded public artifact
were verified against the built JAR. The public selection rejects other versions.
Previous artifacts, signing keys and historical release-pointer variables remain
unchanged. Historical `/loader/` routes now deliberately return `410 loader_retired`;
older clients must migrate to the standalone Fabric JAR.

The production account Worker preserved all 36 existing bindings; staging preserved
all 12; the Gateway preserved all 18. Durable Object namespace IDs and migrations
remain unchanged. The new production and staging cape buckets are dedicated to
`COSMETIC_ASSETS`, separate from private release artifacts, with public access disabled.
Provider-read module bytes matched the tested dry-run packages exactly.
The production upload's URL check returned a Cloudflare API timeout after upload;
independent provider reads confirmed the new version was active and the public
health and authorization checks passed. No duplicate production upload was needed.

Existing User application OAuth uses the verified production callback and existing
client secret, stored only as `DISCORD_OAUTH_CLIENT_SECRET` on the backend Worker.
The browser requests `identify` only. Automatic request traces and invocation logs
are disabled on both account Workers. No Tail Workers or Worker Logpush are enabled.
The connected API did not permit account-wide Logpush inventory; external exporters
could not be independently reviewed. No connection codes, states, cookies or tokens
were logged or published during operator checks.

## Discord and delivery changes

Targeted guild command registration updated the existing User and Backend apps;
no bulk replacement, new application or competing Gateway was introduced. Final
credentialed previews reported no remaining changes. Both applications had no global
commands to retire. User commands include `/connect`, `/status`, `/disconnect`,
`/account`, `/cosmetics`, `/leaderboard` and `/help`. Backend community commands
forward the original body and signatures to the account Worker, which preserves
guild, application, persisted administrator and reviewed-confirmation checks.
Unrelated legacy/prompt handlers remain in the preserved live Gateway base.

The sole Cloudflare Gateway integrates bounded uncached reply lookup and structured
RNG delivery. A restarted Gateway can fetch a same-channel parent and retain a
Minecraft-origin embed's original sender/body; unavailable parents do not block
the new message or heartbeat. Global remains bridged to Discord; Party has no
Discord route and optional room bridges remain disabled.

An RNG post with a lost acknowledgement remains retractable after an outage or
sharing change. The account outbox retains attempted cancellation records under
the existing 500-pending cap. Gateway and Node receipts retain unacknowledged IDs;
acknowledged receipts expire after fifteen minutes. The 1,024-receipt cap blocks
new publications rather than erasing pending mappings. Cancellation claims contain
no event body and can resolve the Discord message from the bot's durable receipt.
No historical Digest backfill or synthetic live drop was published.
Rotating batches of at most 32 ID/message pairs confirm committed publication
acknowledgements whose responses were lost without republishing. Consumers validate
the complete confirmation list before changing receipts. Successful cancellation
clears its local mapping before acknowledging deletion, so a lost deletion response
cannot leave an immortal receipt. The account Worker was updated before consumers.

RNG channel activation is pending: neither existing bot has Manage Channels and
the requested `#rng-feed` does not yet exist. The user was asked to create it under
the existing IRC category. Once available, verify the User Bot's View/Send/Embed/
Read History permissions, set the same `DISCORD_RNG_CHANNEL_ID` on backend and sole
Gateway, redeploy both, and verify feed health. Keep it separate from chat channels.

## Verification and migration

- Java 25 `gradlew --offline check build`: passed, including mod-wide executable checks.
- Backend full `npm test` and `npm run check`: passed after the receipt recovery fixes.
- User Bot suite: 71/71 passed; TypeScript check passed. Registration suite: 10/10 passed.
- Fresh Node 22 CI fixture passed with source-only installs; the registration job
  installs its sibling User runtime dependency before its own tests.
- Companion strict TypeScript, existing Gateway runtime checks, the new community
  feed/runtime suite and five exact-body forwarding checks passed.
- The isolated final Gateway bundle passed preserved legacy route, authentication,
  private-control, audience and forwarding checks without external traffic.
- Live backend/staging checks rejected unsigned, unauthenticated and invalid requests.
  Private cape preview requires a session; missing assets are unavailable.
- Controlled restart of the existing Gateway passed 75 consecutive sampled seconds
  with Discord and backend transports ready, rich chat version 1 and no new messages.

The selected Prism instance was migrated while closed. Only the checked old mod and
Loader JARs moved to private backup; the checked standalone JAR is the sole installed
Sky-Myce mod. All 55 unrelated JARs and 94 protected files matched before launch.
An offline native session entered the existing private world and checked the title,
home/settings, journal, community, native cosmetics preview, leaderboard and dungeon
tracker screens. Existing tracker totals remained visible. After normal exit, every
protected tracker/journal/history file still matched its original hash. Four launcher,
menu and config files were naturally rewritten; none were restored over user data.

Live OAuth completion, real Discord command use, a genuine new drop/retraction,
cross-client cape upload/view, physical arrow-key behavior and updater installation
were not tested. The offline session and rejection probes do not establish those
acceptance results. Private operator logs, source snapshots, old JARs and screenshots
remain outside Git. See the [operator handoff](main-machine-community-rollout.md)
for the remaining end-to-end checks and renewal procedure.
