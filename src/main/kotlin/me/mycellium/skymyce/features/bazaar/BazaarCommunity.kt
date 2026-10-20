package me.mycellium.skymyce.features.bazaar

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.CommunitySharing
import me.mycellium.skymyce.community.SharingCategory
import me.mycellium.skymyce.config.economy.BazaarConfig
import me.mycellium.skymyce.config.misc.CommunitySharingConfig
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.StateWriter
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal data class BazaarContribution(val player: String, val id: String, val fingerprint: String, val profit: Double,
    val cost: Double, val proceeds: Double, val fees: Double, val durationMs: Long, val completed: Boolean, val observedAt: Long, val quality: String,
    val promotion: Boolean = false)
internal data class BazaarPublication(val player: String, val account: String, val id: String, val sessionId: String, val url: String, val visibility: String)
internal data class BazaarPrivateState(val version: Int = 1, val webhook: String = "", val webhookEnabled: Boolean = false,
    val publications: List<BazaarPublication> = emptyList(), val queue: List<BazaarContribution> = emptyList(), val ignored: Set<String> = emptySet(),
    val removals: List<BazaarRemoval> = emptyList(), val preferenceDeleteAccount: String? = null, val preferenceSyncAccount: String? = null)
internal data class BazaarRemoval(val player: String, val id: String)

/** Separate private delivery metadata: deleting a local journal never silently revokes a published replay. */
internal class BazaarCommunity(private val changed: () -> Unit) {
    @Volatile var state = BazaarPrivateState(); private set
    var loaded = false; private set
    var status = "Local history works without Discord"; private set
    private val file = AtomicJsonFile(SkyMyce.configPath.resolve("bazaar-private.json"), 2 * 1024 * 1024)
    private val writer = StateWriter<BazaarPrivateState>({ file.write(Gson().toJsonTree(it)) },
        { job -> Scheduling.schedule(500.milliseconds) { job() } }, { SkyMyce.logger.warn("Bazaar delivery preferences could not be saved") })
    private var busy = false
    private var retryAt = 0L
    private var publicationsGeneration = -1L
    private var publicationsBusy = false
    private var publicationsRetryAt = 0L
    fun initialize() {
        Scheduling.schedule(0.seconds) {
            val saved = runCatching {
                val json = file.read()
                require(!file.damaged)
                if (json == null) BazaarPrivateState() else Gson().fromJson(json, BazaarPrivateState::class.java).let { it.copy(removals = it.removals.orEmpty()) }.also {
                    require(it.version == 1 && it.queue.size <= 512 && it.publications.size <= 2000 && it.ignored.size <= 10000 && it.removals.size <= 512)
                    require(it.webhook.isEmpty() || validBazaarWebhook(it.webhook))
                    require(validBazaarId(it.preferenceDeleteAccount) && validBazaarId(it.preferenceSyncAccount))
                }
            }
            MC.instance.execute {
                saved.onSuccess { state = it; loaded = true }.onFailure { status = "Private delivery settings unreadable; delivery paused, original retained" }
                changed()
            }
        }
    }
    fun flush() = writer.flush()
    fun pendingPreferenceDeletion(account: String?) { require(validBazaarId(account)); update(state.copy(preferenceDeleteAccount = account)) }
    fun preferenceSyncAccount(account: String?) { require(validBazaarId(account)); update(state.copy(preferenceSyncAccount = account)) }
    private fun update(next: BazaarPrivateState) { state = next; writer.submit(next); changed() }
    fun webhook(url: String) {
        check(loaded); require(url.isEmpty() || validBazaarWebhook(url))
        update(state.copy(webhook = url, webhookEnabled = state.webhookEnabled && url.isNotEmpty()))
    }
    fun enableWebhook(enabled: Boolean) { check(loaded); require(!enabled || state.webhook.isNotEmpty()); update(state.copy(webhookEnabled = enabled)) }
    fun ignore(id: String) { require(id.length <= 128 && state.ignored.size < 10000); update(state.copy(ignored = state.ignored + id)) }
    fun clearQueue(player: String) { if (state.queue.any { it.player == player }) update(state.copy(queue = state.queue.filterNot { it.player == player })) }
    fun remove(context: BazaarContext, ids: Set<String>) {
        if (ids.isEmpty() || !loaded) return
        val removals = (state.removals + ids.map { BazaarRemoval(context.account, bazaarFingerprint(it).take(32)) }).distinct()
        require(removals.size <= 512) { "Reconnect to finish pending public retractions before correcting more records" }
        val invalid = ids.mapTo(hashSetOf()) { bazaarFingerprint(it).take(32) }
        update(state.copy(queue = state.queue.filterNot { it.player == context.account && it.id in invalid }, removals = removals))
    }
    fun enqueue(context: BazaarContext, ledger: BazaarLedger, records: List<BazaarRealization>, promotions: Set<String> = emptySet()) {
        if (!loaded || context.server != "main" || !localConsent()) return
        val queued = state.queue.toMutableList()
        val durations = bazaarDurationContributions(ledger)
        for (record in records) {
            if (!record.eligible || record.netProfit == null || record.cost == null || record.proceeds == null || record.fees == null) continue
            val id = bazaarFingerprint(record.id).take(32)
            val previous = queued.indexOfFirst { it.id == id && it.player == context.account }
            if (previous >= 0) { if (bazaarFinalRealization(ledger, record)) queued[previous] = queued[previous].copy(completed = true); continue }
            if (queued.size >= 512) { status = "Public delivery queue full; private history retained, new public deliveries paused"; break }
            queued += BazaarContribution(context.account, id, record.fingerprint,
                record.netProfit, record.cost, record.proceeds, record.fees, durations[record.id] ?: 0,
                bazaarFinalRealization(ledger, record), record.at, record.evidence.name.lowercase(), record.id in promotions)
        }
        if (queued != state.queue) update(state.copy(queue = queued))
    }
    private fun localConsent() = CommunitySharingConfig.mode != me.mycellium.skymyce.community.SharingMode.OFF
    private data class Lease(val account: String, val player: String, val token: String, val generation: Long) {
        fun current() = CommunityClient.connected && token == CommunityClient.sessionToken && generation == CommunityClient.sessionGeneration &&
            account == CommunityClient.identity?.get("accountId")?.asString && player == CommunityClient.identity?.get("uuid")?.asString &&
            player == MC.instance.user.profileId.toString().replace("-", "")
        fun body() = JsonObject().apply { addProperty("version", 1); addProperty("expectedAccountId", account); addProperty("expectedUuid", player) }
    }
    private fun lease(): Lease {
        check(CommunityClient.connected)
        return Lease(requireNotNull(CommunityClient.identity?.get("accountId")?.asString), requireNotNull(CommunityClient.identity?.get("uuid")?.asString),
            requireNotNull(CommunityClient.sessionToken), CommunityClient.sessionGeneration)
    }
    fun tick(context: BazaarContext?, now: Long) {
        if (!loaded) return
        val retained = state.queue.filter { it.promotion || it.observedAt >= now - 7L * 86400000 }
        if (retained.size != state.queue.size) { update(state.copy(queue = retained)); status = "Expired public deliveries skipped under the seven-day admission policy; private history retained" }
        if (CommunityClient.connected && !publicationsBusy && now >= publicationsRetryAt && publicationsGeneration != CommunityClient.sessionGeneration) refreshPublications()
        if (context == null) return
        if (!busy && now >= retryAt && CommunityClient.connected && state.removals.any { it.player == context.account }) {
            val batch = state.removals.filter { it.player == context.account }.take(32)
            busy = true
            request("/community/bazaar/contributions/remove", { body -> body.add("ids", Gson().toJsonTree(batch.map { it.id })) }).whenComplete { _, failure ->
                busy = false
                if (failure == null) update(state.copy(removals = state.removals.filterNot { it in batch }))
                else retryAt = System.currentTimeMillis() + 60000
            }
            return
        }
        if (!localConsent()) { clearQueue(context.account); return }
        if (busy || now < retryAt || !CommunitySharing.canShare(SharingCategory.ECONOMY) || !CommunitySharing.canShare(SharingCategory.LEADERBOARDS)) return
        val batch = state.queue.filter { it.player == context.account }.take(32)
        if (batch.isEmpty()) return
        val lease = runCatching(::lease).getOrNull() ?: return
        if (lease.player != context.account.replace("-", "")) return
        val body = lease.body().apply {
            addProperty("timezone", BazaarConfig.timezone)
            add("events", JsonArray().apply { batch.forEach { row -> add(Gson().toJsonTree(row).asJsonObject.apply { remove("player"); remove("promotion") }) } })
        }
        busy = true
        Scheduling.schedule(0.seconds) {
            val response = runCatching {
                check(lease.current() && localConsent() && CommunitySharing.canShare(SharingCategory.ECONOMY))
                CommunityClient.exchange("/community/bazaar/contributions", body, "POST", lease.token)
            }
            MC.instance.execute {
                busy = false
                if (!lease.current()) return@execute
                response.onSuccess { json ->
                    val accepted = (json.getAsJsonArray("accepted")?.map { it.asString }.orEmpty() + json.getAsJsonArray("duplicates")?.map { it.asString }.orEmpty()).toSet()
                    update(state.copy(queue = state.queue.filterNot { it.player == context.account && it.id in accepted }))
                    retryAt = now + 5000; status = "Eligible realized profit synchronized · server receipt timezone periods"
                }.onFailure { error ->
                    if (error is me.mycellium.skymyce.community.CommunityFailure && error.code == "invalid_bazaar_contribution") {
                        val expired = batch.filter { it.observedAt < now - 7L * 86400000 }.toSet()
                        if (expired.isNotEmpty()) update(state.copy(queue = state.queue.filterNot { it in expired }))
                    }
                    retryAt = System.currentTimeMillis() + 60000; status = "Community delivery unavailable; bounded local queue retained"
                }
                changed()
            }
        }
    }
    fun request(path: String, build: (JsonObject) -> Unit = {}, method: String = "POST"): CompletableFuture<JsonObject> {
        val result = CompletableFuture<JsonObject>()
        val lease = runCatching(::lease).getOrElse { result.completeExceptionally(it); return result }
        val body = lease.body().apply(build)
        Scheduling.schedule(0.seconds) {
            val value = runCatching { check(lease.current()); CommunityClient.exchange(path, if (method == "GET") null else body, method, lease.token) }
            MC.instance.execute { if (!lease.current()) result.completeExceptionally(IllegalStateException("Community account changed"))
                else value.fold(result::complete, result::completeExceptionally) }
        }
        return result
    }
    fun remember(publication: BazaarPublication) { check(loaded && state.publications.size < 2000); update(state.copy(publications = state.publications.filterNot { it.id == publication.id } + publication)) }
    fun revoked(id: String) = update(state.copy(publications = state.publications.filterNot { it.id == id }))
    fun refreshPublications() {
        if (!loaded || publicationsBusy || !CommunityClient.connected) return
        val account = CommunityClient.identity?.get("accountId")?.asString ?: return
        val player = MC.instance.user.profileId.toString()
        val generation = CommunityClient.sessionGeneration
        publicationsBusy = true
        request("/community/bazaar/stats", method = "GET").whenComplete { json, failure ->
            publicationsBusy = false; publicationsRetryAt = System.currentTimeMillis() + 60000
            if (failure == null) runCatching {
                val list = json.getAsJsonArray("publications") ?: error("Publication list unavailable")
                require(list.size() <= 10)
                val active = list.map { value ->
                    val row = value.asJsonObject
                    val id = requireNotNull(row.text("id", 32)).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
                    val token = requireNotNull(row.text("token", 43)).also { require(it.matches(Regex("[A-Za-z0-9_-]{43}"))) }
                    val existing = state.publications.find { it.id == id && it.account == account }
                    val url = java.net.URI(CommunityClient.configuration.getProperty("backend")).resolve("/community/bazaar/replays/$token").toString()
                    existing?.copy(url = url) ?: BazaarPublication(player, account, id, "recovered", url, "Published · account management")
                }
                update(state.copy(publications = state.publications.filterNot { it.account == account } + active))
                publicationsGeneration = generation
            }.onFailure { status = "Could not validate published replay metadata; existing local references retained" }
        }
    }
}
