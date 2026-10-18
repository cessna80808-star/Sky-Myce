package me.mycellium.skymyce.features.digest.testing

import com.google.gson.JsonObject
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.community.CommunityClient
import me.mycellium.skymyce.community.CommunityFailure
import me.mycellium.skymyce.community.CommunityScreen
import me.mycellium.skymyce.community.LatestCommunityRequestWorker
import me.mycellium.skymyce.ui.*
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import java.util.concurrent.Executors

/** Each click requires fresh backend authority. Displaying this screen never grants administrator access. */
class RngTestScreen : MenuScreen(Component.literal("RNG Tester")) {
    override val canonicalFeatureId get() = "rng-test"
    override val menuCategory get() = MenuCategory.UTILS
    private var fixture = "handle"
    private var customChat = ""
    @Volatile private var epoch = 0L
    @Volatile private var closed = false
    private var generation = -1L
    private var busy = false
    private var status = "Each test requires a connected, server-verified Sky-Myce administrator."
    private var output = emptyList<String>()
    private lateinit var rows: FlowLayout
    private lateinit var fixtureButton: MenuButton
    private companion object {
        val worker = LatestCommunityRequestWorker(Executors.newSingleThreadExecutor { Thread(it, "Sky-Myce RNG tests").apply { isDaemon = true } })
        val fixtures = linkedMapOf("handle" to "Dungeon handle", "warden-heart" to "Slayer heart", "duplicate" to "Duplicate acquisition", "malformed" to "Malformed input")
    }
    override fun init() { closed = false; super.init() }
    override fun buildContent(root: FlowLayout) {
        root.padding(Insets.of(10)).surface(Surface.BLANK); root.gap(8)
        root.child(label("ADMINISTRATOR RNG TESTER", MenuTheme.ACCENT))
        root.child(label("TEST fixtures only • disposable local archive • isolated mock Discord transport", MenuTheme.MUTED))
        fixtureButton = button(fixtures.getValue(fixture)) { openMenuDropdown(fixtureButton) { popup -> fixtures.forEach { (id, title) ->
            popup.button(Component.literal(title)) { fixture = id; fixtureButton.message = Component.literal(title); discard(); paint() }
        } } }
        root.child(fixtureButton)
        root.child(MenuTextBox(Sizing.fill()).apply {
            setMaxLength(600); setHint(Component.literal("Optional local chat text override (TestPlayer; chosen fixture location)")); text(customChat)
            onChanged().subscribe { customChat = it; discard(); paint() }
        })
        root.child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            child(button("Run dry-run") { run("dry-run") }.horizontalSizing(Sizing.fill(48)))
            child(button("Test mock delivery") { run("mock-delivery") }.horizontalSizing(Sizing.fill(48)))
        })
        rows = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply { gap(7); padding(Insets.of(8)) }
        root.child(menuScroll(Sizing.fill(), Sizing.expand(), rows))
        root.child(button("Connect Discord") { navigateMenu(CommunityScreen(this@RngTestScreen)) })
        paint()
    }
    private fun discard() { epoch++; worker.cancel(this); busy = false; output = emptyList() }
    override fun tick() {
        super.tick(); if (menuClosing) return
        if (generation != CommunityClient.sessionGeneration) {
            generation = CommunityClient.sessionGeneration; discard()
            status = "Connection changed. Run a new test to prove current administrator access."; paint()
        }
    }
    private fun run(mode: String) {
        if (busy || closed || menuClosing) return
        val token = CommunityClient.sessionToken; val identity = CommunityClient.identity?.deepCopy()
        val minecraft = MC.instance.user.profileId.toString().replace("-", "")
        if (!CommunityClient.connected || token == null || identity == null || identity["uuid"]?.asString != minecraft) {
            discard(); status = "Connect the selected Minecraft account to Discord first. Offline access grants no tester authority."; paint(); return
        }
        val selected = fixture; val chat = customChat; val requestEpoch = ++epoch; val ownerGeneration = CommunityClient.sessionGeneration
        fun current() = !closed && epoch == requestEpoch && CommunityClient.connected && ownerGeneration == CommunityClient.sessionGeneration &&
            token == CommunityClient.sessionToken && identity == CommunityClient.identity && minecraft == MC.instance.user.profileId.toString().replace("-", "")
        val body = JsonObject().apply { addProperty("mode", mode); addProperty("fixture", selected)
            addProperty("expectedAccountId", identity["accountId"].asString); addProperty("expectedUuid", minecraft) }
        busy = true; output = emptyList(); status = "Verifying administrator access and running $mode…"; paint()
        worker.submit(this, ::current, {
            val reply = parseRngTestReply(CommunityClient.exchange("/community/admin/rng-test", body, token = token), mode)
            // Authorization is fresh and bound to this exact session before any local fixture runs.
            check(current())
            reply to localRngTest(selected, System.currentTimeMillis(), chat)
        }) { result -> MC.instance.execute {
            if (!current() || menuClosing) return@execute
            busy = false
            result.fold({ (server, local) ->
                status = "TEST completed • current backend administrator verified"
                output = listOf("LOCAL PRODUCTION PARSER", "Normalized: ${local.normalized ?: "invalid text"}",
                    "Detected: ${local.item ?: "none"} • activity: ${local.activity ?: "none"} • confirmed: ${local.confirmed}",
                    "Temporary journal: ${local.journalRecords} records • exact acquisition retry suppressed: ${local.duplicateSuppressed}") + local.notes +
                    listOf("SERVER VALIDATION / PRESENTATION") + server.cases + listOfNotNull(server.mock, server.embedTitle) +
                    "Real personal records, shared metrics, leaderboards and production Discord were untouched."
            }, { failure ->
                status = when ((failure as? CommunityFailure)?.code) {
                    "admin_required" -> "Denied: your connected Discord identity is not a current Sky-Myce administrator."
                    "identity_blacklisted" -> "Denied: this online identity is restricted."
                    "rate_limited" -> "Tester rate limit reached. Retry later."
                    else -> "Test unavailable or authority changed. No local fixture or production report was accepted."
                }
                output = emptyList()
            }); paint()
        } }
    }
    private fun paint() {
        if (!::rows.isInitialized) return
        rows.clearChildren(); rows.child(label(status, MenuTheme.SECONDARY)); output.forEach { rows.child(label(it)) }
    }
    override fun removed() { closed = true; discard(); super.removed() }
    private fun label(text: String, color: Int = MenuTheme.TEXT) = MenuLabel(Component.literal(text).withColor(color)).apply { horizontalSizing(Sizing.fill()) }
    private fun button(text: String, action: () -> Unit) = MenuButton(Component.literal(text)) { action() }.apply { sizing(Sizing.fill(), Sizing.fixed(22)) }
}
