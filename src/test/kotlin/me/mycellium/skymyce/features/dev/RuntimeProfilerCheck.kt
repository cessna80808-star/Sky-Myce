package me.mycellium.skymyce.features.dev

import java.nio.file.Files
import java.util.concurrent.Executors
import jdk.jfr.consumer.RecordingFile

fun main() {
    check(!RuntimeProfiler.active)
    check(RuntimeProfiler.measure(RuntimeProfiler.Area.GUI) { 42 } == 42)
    check(RuntimeProfiler.report() == "Profiler is stopped")
    check(RuntimeProfiler.start(recordJfr = false))
    check(!RuntimeProfiler.start(recordJfr = false))
    repeat(50) { RuntimeProfiler.measure(RuntimeProfiler.Area.GUI) { ByteArray(1024).also { it[0] = 1 } } }
    runCatching { RuntimeProfiler.measure(RuntimeProfiler.Area.CHAT) { error("expected") } }
    val executor = Executors.newFixedThreadPool(2)
    try {
        val jobs = List(2) { executor.submit { repeat(100) {
            RuntimeProfiler.measure(RuntimeProfiler.Area.RELAY_MESSAGE) { RuntimeProfiler.network(RuntimeProfiler.Area.RELAY_MESSAGE, 7) }
        } } }
        jobs.forEach { it.get() }
    } finally { executor.shutdownNow() }
    val report = RuntimeProfiler.report(mapOf("test.cache" to 9))
    check("GUI" in report && "calls=50" in report && "CHAT" in report && "calls=1" in report)
    check("RELAY_MESSAGE 1400 calls=200" in report && "test.cache=9" in report)
    check("JVM platform threads=" in report && "Global coroutine count unavailable" in report)
    val directory = Files.createTempDirectory("skymyce-profile-check")
    try {
        val saved = RuntimeProfiler.stop(directory)!!
        check(Files.readString(saved).contains("calls=200"))
        check(!RuntimeProfiler.active && RuntimeProfiler.stop(directory) == null)
        Files.delete(saved)
        // Exercise native JFR lifecycle and a bounded on-disk artifact, not a mock recording.
        check(RuntimeProfiler.start())
        RuntimeProfiler.measure(RuntimeProfiler.Area.COMMAND_TICK) { ByteArray(4096) }
        val jfrReport = RuntimeProfiler.stop(directory)!!
        val recording = jfrReport.resolveSibling(jfrReport.fileName.toString().removeSuffix(".txt") + ".jfr")
        check(Files.size(recording) > 0)
        val sensitive = setOf("jdk.JVMInformation", "jdk.InitialSystemProperty", "jdk.InitialEnvironmentVariable",
            "jdk.SystemProcess", "jdk.ProcessStart", "jdk.JavaExceptionThrow", "jdk.JavaErrorThrow")
        check(RecordingFile.readAllEvents(recording).none { it.eventType.name in sensitive }) { "Profiler recorded private launch/environment/exception data" }
        Files.delete(recording); Files.delete(jfrReport)
    } finally { RuntimeProfiler.close(); Files.delete(directory) }
    RuntimeProfiler.shutdown()
    check(!RuntimeProfiler.start(recordJfr = false)) { "A queued start revived profiling after shutdown" }
    println("Developer profiler checks passed: dormant scope, concurrent counters, native JFR, private launch/environment events excluded, bounded recording and shutdown cleanup")
}
