import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.Base64
import java.net.URI
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import net.fabricmc.loom.build.nesting.NestableJarGenerationTask

private fun checkLoaderConfiguration(configuration: Properties) {
    val backend = URI(configuration.getProperty("backend"))
    check(backend.scheme == "https" && !backend.host.isNullOrBlank() &&
        !backend.host.endsWith(".invalid", ignoreCase = true) && (backend.port == -1 || backend.port in 1..65535) &&
        backend.userInfo == null && backend.rawQuery == null &&
        backend.fragment == null && backend.path in setOf("", "/")) { "Configure a valid HTTPS loader backend before distribution" }
    val keys = configuration.stringPropertyNames().filter { it.startsWith("key.") }
    check(keys.isNotEmpty()) { "Configure a pinned loader signing public key" }
    for (key in keys) {
        check(key.removePrefix("key.").matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val bytes = Base64.getDecoder().decode(configuration.getProperty(key))
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes))
    }
}

plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.0"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

repositories {
	// Add repositories to retrieve artifacts from in here.
	// You should only use this when depending on other mods because
	// Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
	// See https://docs.gradle.org/current/userguide/declaring_repositories.html
	// for more information about repositories.

	// Hypixel API
	maven("https://repo.hypixel.net/repository/Hypixel/")
	maven("https://api.modrinth.com/maven")

	// Resourceful Config
	maven("https://maven.teamresourceful.com/repository/maven-public/")

	// Devauth
	maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")

	// owo lib
	maven("https://maven.wispforest.io")
	maven("https://jitpack.io")
}

// Fabric already selects our newer SkyBlock API and identical config mods. Keep the
// viewer, its entrypoints and every other library; omit only those shadowed copies.
val profileViewerRuntime = configurations.create("profileViewerRuntime") { isTransitive = false }
val compactProfileViewer by tasks.registering {
    val original = providers.provider { profileViewerRuntime.singleFile }
    val output = layout.buildDirectory.file("shared-runtime/skyblock-profile-viewer.jar")
    inputs.file(original)
    outputs.file(output)
    doLast {
        val shared = setOf("skyblock-api", "resourcefulconfig", "resourcefulconfigkt", "tech_thatgravyboat_repo-lib_repo-lib")
        ZipFile(original.get()).use { source ->
            @Suppress("UNCHECKED_CAST")
            val metadata = source.getInputStream(source.getEntry("fabric.mod.json")).reader().use {
                JsonSlurper().parse(it) as MutableMap<String, Any>
            }
            @Suppress("UNCHECKED_CAST")
            val nested = metadata["jars"] as List<Map<String, String>>
            val omitted = nested.mapNotNull { entry ->
                val path = entry.getValue("file")
                val bytes = source.getInputStream(source.getEntry(path)).use { it.readBytes() }
                ZipInputStream(bytes.inputStream()).use { jar ->
                    var item = jar.nextEntry
                    while (item != null && item.name != "fabric.mod.json") item = jar.nextEntry
                    if (item == null) null else {
                        val mod = JsonSlurper().parseText(jar.readBytes().toString(Charsets.UTF_8)) as Map<*, *>
                        path.takeIf { mod["id"] in shared }
                    }
                }
            }.toSet()
            check(omitted.size == shared.size) { "Profile viewer shared runtime changed; review its nested mods" }
            metadata["jars"] = nested.filterNot { it["file"] in omitted }
            val destination = output.get().asFile.apply { parentFile.mkdirs() }
            ZipOutputStream(destination.outputStream()).use { target ->
                source.entries().asSequence().filterNot { it.name in omitted }.forEach { entry ->
                    target.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    if (entry.name == "fabric.mod.json") target.write(JsonOutput.toJson(metadata).toByteArray())
                    else source.getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        }
    }
}

val sharedRuntimeCheck by tasks.registering {
    group = "verification"
    description = "Checks compact viewer retains every class/resource and resolves every nested mod"
    dependsOn(compactProfileViewer)
    doLast {
        ZipFile(profileViewerRuntime.singleFile).use { original ->
            ZipFile(compactProfileViewer.get().outputs.files.singleFile).use { compact ->
                fun metadata(zip: ZipFile): Map<*, *> = zip.getInputStream(zip.getEntry("fabric.mod.json")).reader().use {
                    JsonSlurper().parse(it) as Map<*, *>
                }
                val before = metadata(original)
                val after = metadata(compact)
                check(before.filterKeys { it != "jars" } == after.filterKeys { it != "jars" })
                val retained = compact.entries().asSequence().map { it.name }.toSet()
                val removed = original.entries().asSequence().map { it.name }.filterNot { it in retained }.toList()
                check(removed.size == 4 && removed.all { it.startsWith("META-INF/jars/") })
                @Suppress("UNCHECKED_CAST")
                val nested = after["jars"] as List<Map<String, String>>
                check(nested.all { it["file"] in retained }) { "Dangling nested mod declaration" }
                for (name in retained - "fabric.mod.json") {
                    val expected = original.getInputStream(original.getEntry(name)).use { it.readBytes() }
                    val actual = compact.getInputStream(compact.getEntry(name)).use { it.readBytes() }
                    check(expected.contentEquals(actual)) { "Viewer class/resource changed: $name" }
                }
                // The two omitted config jars must be exactly the ones we include directly.
                for (name in removed.filter { it.contains("resourcefulconfig") }) {
                    val replacement = configurations.runtimeClasspath.get().single { it.name == name.substringAfterLast('/') }
                    check(original.getInputStream(original.getEntry(name)).use { it.readBytes() }.contentEquals(replacement.readBytes()))
                }
                logger.lifecycle("Shared runtime checks passed: viewer classes/assets/metadata intact; 4 shadowed libraries omitted")
            }
        }
    }
}

dependencies {
    val skijaVersion = providers.gradleProperty("skija_version").get()
    implementation("io.github.humbleui:skija-shared:$skijaVersion")
    include("io.github.humbleui:skija-shared:$skijaVersion")
    include("io.github.humbleui:types:0.2.0")
    for (platform in listOf("windows-x64", "linux-x64", "linux-arm64", "macos-x64", "macos-arm64")) {
        runtimeOnly("io.github.humbleui:skija-$platform:$skijaVersion") { isTransitive = false }
        include("io.github.humbleui:skija-$platform:$skijaVersion") { isTransitive = false }
    }
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	// Fabric API. This is technically optional, but you probably want it anyway.
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    implementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")

	// Dev Auth
	runtimeOnly("me.djtheredstoner:DevAuth-fabric:${providers.gradleProperty("devauth_version").get()}")

	// Hypixel API
	implementation("net.hypixel:mod-api:${providers.gradleProperty("hypixel_mod_api_version").get()}")
	include("net.hypixel:mod-api:${providers.gradleProperty("hypixel_mod_api_version").get()}")

	// Skyblock API
	api("tech.thatgravyboat:skyblock-api:${providers.gradleProperty("skyblock_api_version").get()}") {
		capabilities { requireCapability("tech.thatgravyboat:skyblock-api-26.1") }
	}
	include("tech.thatgravyboat:skyblock-api:${providers.gradleProperty("skyblock_api_version").get()}") {
		capabilities { requireCapability("tech.thatgravyboat:skyblock-api-26.1") }
	}

	// Pin the 26.1 release by Modrinth version ID; it also bundles its own runtime libraries.
	runtimeOnly("maven.modrinth:skyblock-profile-viewer:${providers.gradleProperty("skyblockpv_version").get()}")
    add(profileViewerRuntime.name, "maven.modrinth:skyblock-profile-viewer:${providers.gradleProperty("skyblockpv_version").get()}")
	include("maven.modrinth:skyblock-profile-viewer:${providers.gradleProperty("skyblockpv_version").get()}")

	// Resourceful Config
	implementation("com.teamresourceful.resourcefulconfig:resourcefulconfig-fabric-26.1:${providers.gradleProperty("resourceful_config_version").get()}")
	include("com.teamresourceful.resourcefulconfig:resourcefulconfig-fabric-26.1:${providers.gradleProperty("resourceful_config_version").get()}")

	implementation("com.teamresourceful.resourcefulconfigkt:resourcefulconfigkt-26.1-rc-1:${providers.gradleProperty("resourceful_config_kt_version").get()}")
	include("com.teamresourceful.resourcefulconfigkt:resourcefulconfigkt-26.1-rc-1:${providers.gradleProperty("resourceful_config_kt_version").get()}")

	// owo lib
	implementation("io.wispforest:owo-lib:${providers.gradleProperty("owo_version").get()}")
	include("io.wispforest:owo-lib:${providers.gradleProperty("owo_version").get()}")
}

// Keep Loom's module coordinates/capabilities and normal nesting/metadata machinery.
// Replace only this task's generated viewer copy with the verified shared-runtime copy.
tasks.named<NestableJarGenerationTask>("processIncludeJars") {
    dependsOn(compactProfileViewer)
    inputs.files(compactProfileViewer)
    doLast {
        val name = "skyblock-profile-viewer-${providers.gradleProperty("skyblockpv_version").get()}.jar"
        compactProfileViewer.get().outputs.files.singleFile.copyTo(outputDirectory.file(name).get().asFile, overwrite = true)
    }
}

tasks.processResources {
	val version = version
	inputs.property("version", version)
	inputs.property("minimumBootstrapVersion", providers.gradleProperty("minimum_bootstrap_version"))

	filesMatching("fabric.mod.json") {
		expand("version" to version, "minecraft_version" to providers.gradleProperty("minecraft_version").get(),
            "loader_version" to providers.gradleProperty("loader_version").get(), "bootstrap_version" to providers.gradleProperty("minimum_bootstrap_version").get())
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_25
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

val standaloneRestartHelpers = setOf("LoaderRestart.class", "LoaderRestart\$Plan.class", "RestartNatives.class",
    "RestartNatives\$1.class", "RestartNatives\$2.class")
tasks.jar {
	exclude("sky-myce-loader.properties")
	exclude { it.path.startsWith("me/mycellium/skymyce/loader/") &&
        it.path.substringAfterLast('/') !in standaloneRestartHelpers }
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

val communityResources = layout.buildDirectory.dir("generated/community")
val generateCommunityResources by tasks.registering {
    val values = mapOf(
        "backend" to providers.gradleProperty("skyMyceBackend").get(),
        "minecraft" to providers.gradleProperty("minecraft_version").get(),
        "key." + providers.gradleProperty("skyMyceSigningKeyId").get() to providers.gradleProperty("skyMyceSigningPublicKey").get()
    )
    inputs.properties(values)
    outputs.dir(communityResources)
    doLast {
        checkLoaderConfiguration(Properties().apply { putAll(values) })
        val output = communityResources.get().asFile.apply { mkdirs() }
        output.resolve("sky-myce-community.properties").writer().use { writer ->
            Properties().apply { putAll(values) }.store(writer, "Public endpoint and update trust roots; no credentials")
        }
    }
}
tasks.processResources { dependsOn(generateCommunityResources); from(communityResources) }

val communityPackagingCheck by tasks.registering {
    group = "verification"
    dependsOn(tasks.jar)
    doLast {
        ZipFile(tasks.jar.get().archiveFile.get().asFile).use { jar ->
            val mod = jar.getInputStream(jar.getEntry("fabric.mod.json")).reader().use { JsonSlurper().parse(it) as Map<*, *> }
            check(mod["id"] == "skymyce" && !(mod["depends"] as Map<*, *>).containsKey("skymyce-loader"))
            check(jar.getEntry("me/mycellium/mixin/MinecraftAccountKeyAccessor.class") != null)
            check(standaloneRestartHelpers.all { jar.getEntry("me/mycellium/skymyce/loader/$it") != null })
            check(jar.entries().asSequence().none { (it.name.startsWith("me/mycellium/skymyce/loader/") && it.name.substringAfterLast('/') !in standaloneRestartHelpers && !it.isDirectory) || it.name == "sky-myce-loader.properties" })
            val config = Properties().apply { jar.getInputStream(jar.getEntry("sky-myce-community.properties")).use { load(it) } }
            checkLoaderConfiguration(config)
            check(config.stringPropertyNames().all { it in setOf("backend", "minecraft") || it.startsWith("key.") })
            check(jar.entries().asSequence().any { it.name.startsWith("META-INF/jars/owo-lib-") })
            logger.lifecycle("Standalone Fabric packaging passed: full UI runtime, Minecraft proof mixin, public configuration, no custom loader")
        }
    }
}
val communityCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.community.CommunityCheckKt"
}
tasks.check { dependsOn(communityPackagingCheck, communityCheck) }

val loaderResources = layout.buildDirectory.dir("generated/loader")
val generateLoaderResources by tasks.registering {
    val values = mapOf(
        "backend" to providers.gradleProperty("skyMyceBackend").orElse("https://configure-before-distribution.invalid").get(),
        "minecraft" to providers.gradleProperty("minecraft_version").get(),
        "loaderVersion" to providers.gradleProperty("bootstrap_version").get(),
        "modVersion" to version.toString(),
        "owoVersion" to providers.gradleProperty("owo_version").get(),
        "key." + providers.gradleProperty("skyMyceSigningKeyId").orElse("release-1").get() to providers.gradleProperty("skyMyceSigningPublicKey").orElse("").get()
    )
    inputs.properties(values)
    outputs.dir(loaderResources)
    doLast {
        checkLoaderConfiguration(Properties().apply { putAll(values) })
        val output = loaderResources.get().asFile.apply { mkdirs() }
        output.resolve("sky-myce-loader.properties").writer().use { writer -> Properties().apply { putAll(values) }.store(writer, "Sky-Myce public deployment configuration") }
        output.resolve("fabric.mod.json").writeText("""{
          "schemaVersion":1,"id":"skymyce-loader","version":"${values["loaderVersion"]}","name":"Sky-Myce Loader","environment":"client",
          "entrypoints":{"preLaunch":["me.mycellium.skymyce.loader.PayloadVerifier"],"client":[{"adapter":"kotlin","value":"me.mycellium.skymyce.loader.LoaderEntrypoint"}]},
          "mixins":["sky-myce-loader.mixins.json"],
          "jars":[{"file":"META-INF/jars/owo-lib.jar"}],
          "depends":{"fabricloader":">=${providers.gradleProperty("loader_version").get()}","minecraft":"~${values["minecraft"]}","java":">=25","fabric-api":"*","fabric-language-kotlin":"*","owo":">=${values["owoVersion"]}"}
        }""")
        output.resolve("sky-myce-loader.mixins.json").writeText("""{"required":true,"package":"me.mycellium.mixin","compatibilityLevel":"JAVA_25","client":["MinecraftAccountKeyAccessor"],"injectors":{"defaultRequire":1}}""")
    }
}

val loaderJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Build the Fabric bootstrap and standalone signed update installer"
    dependsOn(tasks.classes, generateLoaderResources)
    archiveFileName = "Sky-Myce-Loader.jar"
    from(sourceSets.main.get().output) {
        include("me/mycellium/skymyce/loader/**", "me/mycellium/mixin/MinecraftAccountKeyAccessor.class")
        include("me/mycellium/skymyce/ui/MotionValue*.class", "me/mycellium/skymyce/ui/MotionProfile*.class", "me/mycellium/skymyce/ui/MotionEasing*.class",
            "me/mycellium/skymyce/ui/GuiMotion*.class", "me/mycellium/skymyce/ui/GuiQuality*.class", "me/mycellium/skymyce/ui/MotionPose*.class",
            "me/mycellium/skymyce/ui/MenuMotionKt*.class")
    }
    from(loaderResources)
    // First activation has no protected mod (or its sentinel); owo includes its own nested dependencies.
    from({ configurations.runtimeClasspath.get().single { it.name == "owo-lib-${providers.gradleProperty("owo_version").get()}.jar" } }) {
        into("META-INF/jars"); rename { "owo-lib.jar" }
    }
    // Gson already ships with Minecraft; bundling it lets java -jar apply an update outside Minecraft.
    from({ configurations.runtimeClasspath.get().filter { it.name.startsWith("gson-") }.map { zipTree(it) } }) { exclude("META-INF/**") }
    manifest { attributes["Main-Class"] = "me.mycellium.skymyce.loader.LoaderInstaller" }
}
// Legacy bootstrap artifacts are available only through the explicit loaderJar task.
// A normal build distributes the standalone Fabric mod.

val loaderConfigurationCheck by tasks.registering {
    group = "verification"
    description = "Rejects unusable backend/trust configuration in the built loader"
    dependsOn(loaderJar)
    doLast {
        ZipFile(loaderJar.get().archiveFile.get().asFile).use { jar ->
            val configuration = Properties().apply {
                jar.getInputStream(jar.getEntry("sky-myce-loader.properties")).use { load(it) }
            }
            checkLoaderConfiguration(configuration)
            val publicKey = configuration.stringPropertyNames().first { it.startsWith("key.") }
            for ((key, invalid) in listOf(
                "backend" to "https://CONFIGURE-BEFORE-DISTRIBUTION.INVALID",
                "backend" to "http://example.org", "backend" to "https://example.org:99999",
                "backend" to "https://user:fixture@example.org", "backend" to "https://example.org/?query=fixture",
                publicKey to "", publicKey to "not-a-public-key")) {
                val rejected = (configuration.clone() as Properties).apply { setProperty(key, invalid) }
                check(runCatching { checkLoaderConfiguration(rejected) }.isFailure) { "Invalid loader configuration was accepted: $key" }
            }
            logger.lifecycle("Loader configuration checks passed: HTTPS backend and pinned Ed25519 trust root")
        }
    }
}

val dungeonFriendsCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks dungeon friend scanning, profile parsing, filters, sorting and messages"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendsCheckKt"
}

val dungeonChestProfitCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks dungeon chest reward estimates and loss thresholds"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.DungeonChestProfitCheckKt"
}

val hudCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks dynamic owo HUD layout and saved widget configuration"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.hud.HudCheckKt"
    workingDir = layout.buildDirectory.dir("hud-check").get().asFile
    doFirst { workingDir.mkdirs() }
}

val loaderCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks loader signatures, installation binding, offline grace and private IRC routing"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.loader.LoaderCheckKt"
}

// Deliberately opt-in: native probes must use an explicit isolated directory and never the real installation.
val hardwareIdentityCheckJar by tasks.registering(Jar::class) {
    group = "verification"
    description = "Build a portable Java 25 native hardware identity diagnostic with no Minecraft dependencies"
    dependsOn(tasks.testClasses)
    archiveFileName = "Sky-Myce-Hardware-Identity-Check.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output) {
        include("me/mycellium/skymyce/loader/Installation*.class", "me/mycellium/skymyce/loader/LaunchSession*.class",
            "me/mycellium/skymyce/loader/HardwareIdentityUnavailable.class", "me/mycellium/skymyce/loader/SignedFiles.class")
    }
    from(sourceSets.test.get().output) { include("me/mycellium/skymyce/loader/HardwareIdentityCheck*.class") }
    from({ configurations.runtimeClasspath.get().filter { it.name.startsWith("kotlin-stdlib") || it.name.startsWith("gson-") }.map { zipTree(it) } }) {
        exclude("META-INF/**")
    }
    manifest { attributes["Main-Class"] = "me.mycellium.skymyce.loader.HardwareIdentityCheck" }
}
val hardwareIdentityCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Check live hardware and saved identity in fresh JVMs; requires -PhardwareIdentityCheckDirectory=<isolated absolute path>"
    dependsOn(hardwareIdentityCheckJar)
    classpath = files(hardwareIdentityCheckJar.flatMap { it.archiveFile })
    mainClass = "me.mycellium.skymyce.loader.HardwareIdentityCheck"
    doFirst {
        val directory = providers.gradleProperty("hardwareIdentityCheckDirectory").orNull
        check(!directory.isNullOrBlank()) { "Set -PhardwareIdentityCheckDirectory to an isolated absolute test directory" }
        setArgs(listOf("--directory", directory))
    }
}
val menuMotionCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks GUI motion timing, interruption, accessibility and input transforms"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.ui.MenuMotionCheckKt"
    workingDir = layout.buildDirectory.dir("menu-motion-check").get().asFile
    doFirst { workingDir.mkdirs() }
}
val runtimeProfilerCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks opt-in developer profiling counters, JFR lifecycle and cleanup"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.dev.RuntimeProfilerCheckKt"
}
val menuNavigationCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks living card navigation geometry, input transforms and rendered previews"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.ui.MenuNavigationCheckKt"
    workingDir = layout.buildDirectory.dir("menu-navigation-check").get().asFile
    doFirst { workingDir.mkdirs() }
}
val menuCatalogCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks canonical feature ownership, config compatibility and search deep links"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.ui.MenuCatalogCheckKt"
}
val menuShortcutsCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks bounded shortcut identities, ordering and atomic persistence"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.ui.MenuShortcutsCheckKt"
}
val featureWorkCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks feature work invalidation, bounded identities and hot-path allocation"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.performance.FeatureWorkCheckKt"
}
val menuCanvasBenchmark by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Measures warmed 60-card canvas work without enforcing noisy timing thresholds"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.hud.MenuCanvasBenchmark"
    workingDir = layout.buildDirectory.dir("menu-canvas-benchmark").get().asFile
    systemProperty("java.awt.headless", "true")
    args("current", ".", "bulk")
    doFirst { workingDir.mkdirs() }
}
val skijaRendererCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks native menu primitives, transitions, typography and bounded resources"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.ui.SkijaRendererCheckKt"
    systemProperty("java.awt.headless", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
val skijaGpuCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Explicit desktop GPU check of native composition and OpenGL state restoration"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.ui.SkijaGpuCheck"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = layout.buildDirectory.dir("skija-gpu-check").get().asFile
    doFirst { workingDir.mkdirs() }
}
tasks.check { dependsOn(skijaRendererCheck) }

// Test-only Fabric entrypoint captures real screens in an isolated, offline client.
val guiVisualCheckMod by tasks.registering(Jar::class) {
    dependsOn(tasks.testClasses)
    archiveFileName = "gui-visual-check.jar"
    destinationDirectory = layout.buildDirectory.dir("gui-visual-check")
    from(sourceSets.test.get().output) { include("me/mycellium/skymyce/ui/GuiVisual*.class") }
    from("src/test/resources/gui-visual-check")
}
loom.runs.register("guiVisualCheck") {
    client()
    runDirectory = layout.buildDirectory.dir("gui-visual-check/client")
    generateRunConfig = false
    systemProperties.put("fabric.addMods", layout.buildDirectory.file("gui-visual-check/gui-visual-check.jar").get().asFile.absolutePath)
    systemProperties.put("devauth.enabled", "false")
    jvmArguments.add("--enable-native-access=ALL-UNNAMED")
    programArguments.addAll("--width", "1280", "--height", "800", "--username", "GuiReview")
}
tasks.named("runGuiVisualCheck") { dependsOn(guiVisualCheckMod) }
val dungeonRoutingCheck by tasks.registering {
    group = "verification"
    description = "Checks room recognition, wall-aware routes, collision safety and lifecycle stability"
}
for (checkName in listOf("RoomNavigation", "RoomTemplates", "RoomWorldGeometry", "RoomRoutingUpdates", "DungeonRouteContinuation")) {
    val routingCheck = tasks.register<JavaExec>(checkName.replaceFirstChar { it.lowercase() } + "Check") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = "me.mycellium.skymyce.features.instances.dungeons.routing.${checkName}CheckKt"
    }
    dungeonRoutingCheck { dependsOn(routingCheck) }
}
val globalChatCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks global chat payloads, mentions, replies, safe links and deduplication"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.friends.GlobalChatCheckKt"
}
val ircChecks = listOf("IrcChatModel", "IrcGif", "IrcMedia").map { checkName ->
    tasks.register<JavaExec>(checkName.replaceFirstChar { it.lowercase() } + "Check") {
        group = "verification"
        description = "Checks bounded IRC message and media behavior"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = "me.mycellium.skymyce.features.instances.dungeons.friends.${checkName}CheckKt"
    }
}
tasks.check { dependsOn(dungeonFriendsCheck, dungeonChestProfitCheck, hudCheck, loaderCheck, menuMotionCheck, menuNavigationCheck, menuCatalogCheck, menuShortcutsCheck, runtimeProfilerCheck, sharedRuntimeCheck, featureWorkCheck, dungeonRoutingCheck, globalChatCheck, ircChecks) }
// Assertions run via the check tasks; this project does not use a test framework.
val cameraRenderCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks independent secondary camera projection, target bounds and strict viewpoints"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.camera.render.CameraRenderCheck"
}
val healerCrushCameraCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks Crush Camera timing, class selection and encounter cleanup"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.camera.HealerCrushCameraCheckKt"
}
val cameraViewpointCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks saved world-space camera views and HUD sizing/resize bounds"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.camera.CameraViewpointCheckKt"
}
tasks.check { dependsOn(cameraRenderCheck, healerCrushCameraCheck, cameraViewpointCheck) }
val rngTesterCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks isolated RNG parser, confirmation, journal and duplicate fixtures"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.digest.testing.RngTesterCheckKt"
}
tasks.check { dependsOn(rngTesterCheck) }
tasks.test { failOnNoDiscoveredTests = false }

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
