package me.mycellium.skymyce.community

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.mycellium.skymyce.community.update.UpdateInstaller
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    cancellationChecks()
    connectionViewChecks()
    updateSelectionChecks()
    leaderboardChecks()
    bodyDeadlineChecks()
    val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val config = Properties().apply { setProperty("minecraft", "26.1.2"); setProperty("key.fixture", Base64.getEncoder().encodeToString(key.public.encoded)) }
    val now = System.currentTimeMillis()
    val manifest = JsonParser.parseString("""{"product":"sky-myce","protocol":4,"installation":"fabric","minecraft":"26.1.2","version":"2.5.1","artifact":"releases/Sky-Myce-2.5.1.jar","size":1024,"sha256":"${"a".repeat(64)}","issuedAt":$now,"expiresAt":${now + 86400000},"notes":"Fixture notes"}""").asJsonObject
    fun signed(data: JsonObject) = JsonObject().apply {
        val bytes = data.toString().toByteArray()
        addProperty("kid", "fixture"); addProperty("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        addProperty("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(Signature.getInstance("Ed25519").run { initSign(key.private); update(bytes); sign() }))
    }
    check(verifyCommunityRelease(signed(manifest), config, now).version == "2.5.1")
    check(newerCommunityVersion("2.10.0", "2.9.9") && !newerCommunityVersion("2.5.0", "2.5.0") && !newerCommunityVersion("2.4.9", "2.5.0"))
    check(newerCommunityVersion("2.8.0-beta-001", "2.7.42") && newerCommunityVersion("2.8.0-beta-010", "2.8.0-beta-9") &&
        newerCommunityVersion("2.8.0", "2.8.0-beta-999") && !newerCommunityVersion("2.8.0-beta-002", "2.8.0"))
    check(runCatching { communityVersion("2.8.0-beta") }.isFailure && runCatching { communityVersion("2.8.0-rc-1") }.isFailure)
    for ((field, value) in listOf("product" to "sky-myce-loader", "installation" to "loader", "artifact" to "../../mods/other.jar", "minecraft" to "1.21", "version" to "latest")) {
        val changed = manifest.deepCopy().apply { addProperty(field, value) }
        check(runCatching { verifyCommunityRelease(signed(changed), config, now) }.isFailure)
    }
    for (changed in listOf(manifest.deepCopy().apply { addProperty("expiresAt", now - 1) }, manifest.deepCopy().apply { addProperty("protocol", 3) }, manifest.deepCopy().apply { addProperty("size", MAX_COMMUNITY_JAR + 1) }, manifest.deepCopy().apply { addProperty("minimumLoaderVersion", "1.0.14") }))
        check(runCatching { verifyCommunityRelease(signed(changed), config, now) }.isFailure)
    val tampered = signed(manifest).apply { addProperty("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(manifest.deepCopy().apply { addProperty("size", 1000) }.toString().toByteArray())) }
    check(runCatching { verifyCommunityRelease(tampered, config, now) }.isFailure)
    val raw = "12345678-1234-4234-9234-123456789abc"
    check(observationHash("windows-smbios", raw.uppercase()) == observationHash("linux-dmi", "{$raw}"))
    check(observationHash("linux-machine-id", raw) != observationHash("linux-dmi", raw))
    check(runCatching { observationHash("windows-smbios", "00000000-0000-0000-0000-000000000000") }.isFailure)
    val root = Files.createTempDirectory("sky-myce-community-check-")
    val store = CommunityStorage(root.resolve("sessions"), "a".repeat(32))
    val saved = JsonObject().apply { addProperty("refreshToken", "b".repeat(43)); addProperty("refreshExpiresAt", now + 900000) }
    store.write(saved)
    check(CommunityStorage(root.resolve("sessions"), "a".repeat(32)).read() == saved)
    store.clear(); check(store.read() == null)
    store.write(saved.deepCopy().apply { addProperty("refreshExpiresAt", now - 1) }); check(store.read() == null)
    val mods = Files.createDirectory(root.resolve("mods")); val staging = Files.createDirectory(root.resolve(".sky-myce-updates"))
    val target = mods.resolve("Sky-Myce.jar"); val next = staging.resolve("next.jar"); val backup = staging.resolve("working.backup")
    Files.writeString(target, "working version"); Files.writeString(next, "new verified version")
    val plan = Properties().apply {
        setProperty("parent", ProcessHandle.current().pid().toString()); setProperty("mods", mods.toString()); setProperty("staging", staging.toString())
        setProperty("target", target.toString()); setProperty("next", next.toString()); setProperty("backup", backup.toString())
        setProperty("oldHash", UpdateInstaller.sha256(target)); setProperty("newHash", UpdateInstaller.sha256(next)); setProperty("size", Files.size(next).toString())
    }
    check(runCatching { UpdateInstaller.install(plan) }.isFailure); check(Files.readString(target) == "working version" && !Files.exists(backup))
    plan.setProperty("parent", Long.MAX_VALUE.toString())
    val goodHash = plan.getProperty("newHash"); plan.setProperty("newHash", "0".repeat(64))
    check(runCatching { UpdateInstaller.install(plan) }.isFailure); check(Files.readString(target) == "working version")
    plan.setProperty("newHash", goodHash)
    val realBackup = plan.getProperty("backup"); plan.setProperty("backup", root.resolve("escaped.backup").toString())
    check(runCatching { UpdateInstaller.install(plan) }.isFailure); plan.setProperty("backup", realBackup)
    // Run the exact isolated JDK-only helper in a fresh JVM, proving it does not depend on Minecraft/Gson or lock the target JAR.
    val helper = staging.resolve("helper.jar")
    UpdateInstaller.writeHelper(helper)
    val planFile = staging.resolve("plan.properties"); Files.newOutputStream(planFile).use { plan.store(it, "isolated fixture") }
    val executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    val process = ProcessBuilder(executable.toString(), "-cp", helper.toString(), UpdateInstaller::class.java.name, planFile.toString()).redirectErrorStream(true).start()
    process.outputStream.close()
    check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0)
    check(Files.readString(target) == "new verified version" && Files.readString(backup) == "working version" && !Files.exists(next))
    communityRestartChecks(root.resolve("restart"), helper)
    println("Community checks passed: authenticated releases, persistent private sessions, bounded HTTP body deadlines, device normalization and after-exit atomic update refusal/backup safety")
}

private fun bodyDeadlineChecks() {
    val timeout = Duration.ofMillis(150)
    val closed = AtomicInteger()
    val complete = object : ByteArrayInputStream("complete".toByteArray()) {
        override fun close() { closed.incrementAndGet(); super.close() }
    }
    check(readCommunityBody(complete, 64, timeout).toString(Charsets.UTF_8) == "complete")
    TimeUnit.MILLISECONDS.sleep(250)
    check(closed.get() == 1) // Cancelled deadline cannot close a completed read a second time.
    check(runCatching { readCommunityBody(ByteArrayInputStream(ByteArray(65)), 64, timeout) }.isFailure)
    val root = Files.createTempDirectory("sky-myce-http-check-")
    val candidate = root.resolve("candidate.jar")
    writeCommunityArtifactBody(ByteArrayInputStream("verified".toByteArray()), candidate, 8, timeout)
    check(Files.readString(candidate) == "verified")
    check(runCatching { writeCommunityArtifactBody(ByteArrayInputStream("replacement".toByteArray()), candidate, 11, timeout) }.isFailure)
    check(Files.readString(candidate) == "verified") // CREATE_NEW refusal preserves an existing file.
    for (raw in listOf(byteArrayOf(1), byteArrayOf(1, 2, 3))) {
        val partial = root.resolve("partial.jar")
        check(runCatching { writeCommunityArtifactBody(ByteArrayInputStream(raw), partial, 2, timeout) }.isFailure)
        check(!Files.exists(partial))
    }

    // A real HTTP response sends headers and one byte, then leaves the announced body incomplete.
    val release = CountDownLatch(1)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val serverWorker = Executors.newCachedThreadPool { runnable -> Thread(runnable, "test stalled body").apply { isDaemon = true } }
    val reader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test body consumer").apply { isDaemon = true } }
    server.executor = serverWorker
    server.createContext("/stall") { exchange ->
        runCatching { exchange.use {
            exchange.sendResponseHeaders(200, 1024)
            exchange.responseBody.write(1); exchange.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
        } }
    }
    server.createContext("/complete") { exchange -> exchange.use {
        val bytes = "recovered".toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
    } }
    server.start()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun response(path: String) = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}$path")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    try {
        val stalledMetadata = response("/stall"); check(stalledMetadata.statusCode() == 200)
        val metadata = reader.submit<Result<ByteArray>> { runCatching { readCommunityBody(stalledMetadata.body(), 65536, timeout) } }.get(3, TimeUnit.SECONDS)
        check(metadata.exceptionOrNull() is HttpTimeoutException) // Request timeout alone already ended at the headers.
        val stalledArtifact = response("/stall"); check(stalledArtifact.statusCode() == 200)
        val next = root.resolve("stalled-update.jar"); val plan = root.resolve("update-plan.properties")
        val current = root.resolve("working.jar"); Files.writeString(current, "working version")
        val download = reader.submit<Result<Unit>> { runCatching {
            writeCommunityArtifactBody(stalledArtifact.body(), next, 1024, timeout)
            Files.writeString(plan, "ready to install")
            Unit
        } }.get(3, TimeUnit.SECONDS)
        check(download.exceptionOrNull() is HttpTimeoutException)
        check(!Files.exists(next) && !Files.exists(plan) && Files.readString(current) == "working version")
        val recovered = response("/complete")
        check(readCommunityBody(recovered.body(), 65536, Duration.ofSeconds(2)).toString(Charsets.UTF_8) == "recovered")
    } finally {
        release.countDown(); client.shutdownNow(); server.stop(0); reader.shutdownNow(); serverWorker.shutdownNow()
    }
}
