package me.mycellium.skymyce.loader

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Opt-in native probes use an isolated test identity, never the user's installation. */
object HardwareIdentityCheck {
    private const val MAGIC = 0x534D4849
    private const val MAX_OUTPUT = 4096
    private const val MARKER = "Sky-Myce native identity check v1\n"
    private const val PROOF = "Sky-Myce native identity check proof v1"
    private val noFollow = LinkOption.NOFOLLOW_LINKS

    private class CheckFailure(val reason: String) : IllegalStateException()

    private data class Snapshot(
        val id: String, val deviceHash: String, val hardwareHash: String,
        val publicKey: String, val signature: String, val sessionId: String,
        val platform: String, val source: String, val authorized: Boolean
    ) {
        fun proofHash() = SignedFiles.sha256(listOf(id, deviceHash, hardwareHash, publicKey, signature).joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            requireCheck(Runtime.version().feature() >= 25, "Java 25 or newer is required")
            requireCheck(args.size == 2 && args[0] == "--directory", "Use --directory with an absolute isolated test directory")
            val directory = testDirectory(args[1])
            val first = child(directory)
            val second = child(directory)
            requireCheck(first.proofHash() == second.proofHash(), "Identity or signature changed between fresh JVMs")
            requireCheck(first.sessionId != second.sessionId && !first.authorized && !second.authorized,
                "Fresh JVMs did not start with distinct unauthorized sessions")
            requireCheck(first.platform == second.platform && first.source == second.source, "Native platform or source changed between fresh JVMs")

            val baseline = directory.resolve("native-check-baseline")
            if (Files.exists(baseline, noFollow)) {
                val saved = String(boundedFile(baseline, 65), Charsets.US_ASCII).trim()
                requireCheck(saved.matches(Regex("[a-f0-9]{64}")) && saved == first.proofHash(),
                    "Persistent test identity or original hardware baseline changed; test identity retained")
            } else {
                SignedFiles.atomicWrite(baseline, (first.proofHash() + "\n").toByteArray(Charsets.US_ASCII))
                restrictIdentityPath(baseline)
            }
            checkPermissions(directory, listOf(baseline))
            println("Native identity check passed: platform=${first.platform}, source=${first.source}; persistent identity/signature and original hardware baseline matched; fresh sessions were distinct and unauthorized.")
            println("Run the same command with the same isolated directory after reboot to verify reboot stability.")
        } catch (failure: Throwable) {
            // Neither private child output nor exception messages from native providers reach logs.
            val reason = if (failure is CheckFailure) failure.reason else "Native identity check failed; test identity retained"
            System.err.println(reason)
            exitProcess(1)
        }
    }

    private fun testDirectory(argument: String): Path {
        val directory = Path.of(argument).normalize()
        requireCheck(directory.isAbsolute, "An absolute isolated test directory is required")
        // Reject both literal defaults and filesystem aliases without reading any production record.
        for (home in listOfNotNull(System.getProperty("user.home"), System.getenv("USERPROFILE"), System.getenv("HOME"))) {
            val production = runCatching { Path.of(home).resolve(".sky-myce/device-v1").toAbsolutePath().normalize() }.getOrNull() ?: continue
            requireCheck(directory != production && !(Files.exists(directory) && Files.exists(production) && Files.isSameFile(directory, production)),
                "Production identity directories are forbidden; choose an isolated test directory")
        }
        val marker = directory.resolve("native-check.marker")
        val identity = directory.resolve("identity.properties")
        if (Files.exists(marker, noFollow)) {
            requireCheck(String(boundedFile(marker, 64), Charsets.US_ASCII) == MARKER, "Test directory marker is invalid")
            requireCheck(Files.isRegularFile(identity, noFollow), "Persistent test identity is missing; it was not regenerated")
        } else {
            requireCheck(!Files.exists(identity, noFollow), "Existing unmarked identities are forbidden; choose an isolated test directory")
            Files.createDirectories(directory)
            restrictIdentityPath(directory)
            Files.write(marker, MARKER.toByteArray(Charsets.US_ASCII), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            restrictIdentityPath(marker)
        }
        return directory
    }

    private fun boundedFile(path: Path, limit: Int): ByteArray {
        requireCheck(Files.isRegularFile(path, noFollow), "Test record is not a regular file")
        val bytes = Files.newInputStream(path, noFollow).use { it.readNBytes(limit + 1) }
        requireCheck(bytes.size <= limit, "Test record exceeds its size limit")
        return bytes
    }

    private fun child(directory: Path): Snapshot {
        val java = Path.of(System.getProperty("java.home"), "bin", if (LaunchSession.platform == "windows") "java.exe" else "java")
        val process = ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), Child::class.java.name, directory.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(MAX_OUTPUT + 1) } }
        try {
            process.outputStream.close()
            requireCheck(process.waitFor(90, TimeUnit.SECONDS), "Native child JVM timed out; test identity retained")
            val bytes = output.get(1, TimeUnit.SECONDS)
            requireCheck(bytes.size <= MAX_OUTPUT, "Native child output exceeds its size limit")
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                requireCheck(input.readInt() == MAGIC, "Native child returned an invalid result")
                if (!input.readBoolean()) {
                    val reason = when (input.readUTF()) {
                        "hardware_unavailable" -> "Native hardware source is unavailable; test identity retained"
                        "baseline_changed" -> "Live hardware differs from the original saved baseline; test identity retained"
                        "unsupported_platform" -> "Native identity checks require Windows, Linux or macOS"
                        else -> "Native child identity check failed; test identity retained"
                    }
                    throw CheckFailure(reason)
                }
                requireCheck(process.exitValue() == 0, "Native child JVM failed; test identity retained")
                val snapshot = Snapshot(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(), input.readUTF(), input.readBoolean())
                requireCheck(input.available() == 0 && snapshot.platform in listOf("windows", "linux", "macos") &&
                    snapshot.source in hardwareSources() && snapshot.source != "software-installation", "Native child returned an invalid result")
                return snapshot
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            output.cancel(true)
        }
    }

    private fun requireCheck(condition: Boolean, reason: String) {
        if (!condition) throw CheckFailure(reason)
    }

    private fun checkPermissions(directory: Path, files: List<Path>) {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) == null) return
        requireCheck(Files.getPosixFilePermissions(directory) == PosixFilePermissions.fromString("rwx------"), "Native test directory permissions are not private")
        for (file in files) requireCheck(Files.getPosixFilePermissions(file) == PosixFilePermissions.fromString("rw-------"),
            "Native test identity file permissions are not private")
    }

    object Child {
        @JvmStatic
        fun main(args: Array<String>) {
            val output = DataOutputStream(System.out)
            try {
                requireCheck(args.size == 1, "invalid_arguments")
                requireCheck(LaunchSession.platform in listOf("windows", "linux", "macos"), "unsupported_platform")
                val directory = Path.of(args[0])
                requireCheck(directory.isAbsolute && String(boundedFile(directory.resolve("native-check.marker"), 64), Charsets.US_ASCII) == MARKER,
                    "invalid_directory")
                val installation = Installation(directory, diagnostic = {})
                val record = Properties().apply { load(boundedFile(directory.resolve("identity.properties"), 16384).inputStream()) }
                requireCheck(installation.hardwareHash == record.getProperty("hardwareReference"), "baseline_changed")
                checkPermissions(directory, listOf(directory.resolve("identity.properties"), directory.resolve("native-check.marker")))
                val signature = installation.sign(PROOF)
                val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(installation.publicKey)))
                val verified = Signature.getInstance("Ed25519").run {
                    initVerify(publicKey)
                    update(PROOF.toByteArray(Charsets.UTF_8))
                    verify(Base64.getDecoder().decode(signature))
                }
                requireCheck(verified && !LaunchSession.authorized, "proof_failed")
                output.writeInt(MAGIC)
                output.writeBoolean(true)
                for (value in listOf(installation.id, installation.deviceHash, installation.hardwareHash, installation.publicKey, signature,
                    LaunchSession.id, LaunchSession.platform, record.getProperty("hardwareSource"))) output.writeUTF(value)
                output.writeBoolean(LaunchSession.authorized)
                output.flush()
            } catch (failure: Throwable) {
                output.writeInt(MAGIC)
                output.writeBoolean(false)
                output.writeUTF(when {
                    failure is HardwareIdentityUnavailable -> "hardware_unavailable"
                    failure is CheckFailure && failure.reason in listOf("baseline_changed", "unsupported_platform") -> failure.reason
                    else -> "native_check_failed"
                })
                output.flush()
                exitProcess(1)
            }
        }
    }
}
