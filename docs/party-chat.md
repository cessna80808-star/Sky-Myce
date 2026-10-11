# Party chat availability and protocol

Party chat delivery is **unavailable** because the current backend cannot verify
a player's current Hypixel party. `/sm pc` can select a locked party mode or
attempt a one-off send with clear feedback; text must stay out of Hypixel chat,
global IRC, legacy shared relay rooms and Discord. Selecting global IRC remains
an explicit separate action through `/sm irc`.

## Evidence and unresolved dependency

The installed SkyBlockAPI `4.2.19` Minecraft `26.1` sources provide
`PartyAPI.inParty`, `leader` and `members`. `PartyAPI.onPartyInfo` consumes
`PartyInfoEvent(inParty, members)`, posted by `HypixelEventHandler` from the
Hypixel Mod API packet. Other membership changes parse server chat text.

The official
[ClientboundPartyInfoPacket source](https://raw.githubusercontent.com/HypixelDev/ModAPI/master/src/main/java/net/hypixel/modapi/packet/impl/clientbound/ClientboundPartyInfoPacket.java)
encodes party presence and UUID/role membership. It supplies no signed assertion,
stable party identifier, membership revision or expiry that the relay could
verify. The [Hypixel Public API](https://api.hypixel.net/) does not document a
current-party membership endpoint. A client forwarding this packet or signing
its own roster cannot prove the roster came from Hypixel.

The relay's Mojang certificate and signed profile establish Minecraft identity.
The installation-bound lease and `AccountStore.checkSession` establish current
account/device/license/build authorization. Neither proves party membership.
Existing `party_set`, `party_get` and `party_update` frames share client-supplied
Party Finder preferences; they are not a membership authority. The `friends`
room is shared by unrelated authenticated users.

Enabling delivery requires a backend-verifiable, fresh party-membership signal
from a trusted authority, bound to the authenticated Minecraft UUID and a party
identity/revision. It must identify creation, member/leader changes, leaving and
disbanding so stale sessions lose access. No such authority exists in the current
protocol or configuration. This remains an unresolved feature dependency, not a
completed party-delivery implementation.

## Fail-closed protocol

Authenticated readiness includes:

```json
{"type":"ready","partyChat":false,"partyChatError":"party_membership_unverified"}
```

For a valid request ID, `irc_subscribe` and `irc_send` with `scope: "party"`
return:

```json
{"type":"irc_result","scope":"party","id":"<request-id>","error":"party_membership_unverified"}
```

Normal authentication, authorization expiry/revocation and frame bounds still
apply. Claimed room names, leaders, rosters and context changes grant no access.
No party subscription, route, deduplication record, quota credit, message outbox
or history is created. Repeated requests, reconnects and Durable Object
hibernation cannot restore a party subscription because none was granted.

Explicit unknown chat scopes are rejected before legacy or global routing. Old
unscoped room IRC remains compatible. Discord bridge frames with party scope
are rejected by the same dispatch guard; Discord never becomes a party authority.

Local relay runtime checks cover forged room/roster claims, repeated IDs and
changed contexts, global/legacy recipients and bridges, and hibernation. They
check that rejection creates no global chat state and leaves existing authorized
global and legacy delivery intact. No live party delivery is claimed.

## Release 2.4.27 verification

Release **2.4.27** passed the complete Java 25 `--offline check build`, including
the command/mode isolation checks and `loaderCheck`. The signed mod was deployed
and loaded through the real loader in the authorized current PrismLauncher
private world. The production Cloudflare `--env backend` Worker version is
`ee1f16b0-ebd5-4489-8790-a3835183f507`.

The native Minecraft UI test confirmed that `/sm pc` selected locked party mode
and blocked a normal typed message. An explicit global one-off message then
reached Discord exactly once without changing that party mode; a second normal
typed party message was also blocked. Neither blocked party marker appeared in
Discord or the integrated server log, and the global one-off did not enter the
integrated server's ordinary chat. The result is recorded in
[the live isolation evidence](../build/global-chat-live-party-isolation.json)
and the approved visual `minecraft-party-final-isolation-verified.png`.
Earlier automation inputs whose party selection was not confirmed are excluded
from this evidence.

This verifies failure confinement and one-off mode isolation in the actual
deployed client/backend. It does not establish Hypixel party delivery: the
backend-verifiable party identity and membership authority described above is
still unavailable. Party delivery remains explicitly disabled rather than
using global IRC, Discord or a shared friends room as a substitute.
