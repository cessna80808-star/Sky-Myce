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

private fun checkCommunityConfiguration(configuration: Properties) {
    val backend = URI(configuration.getProperty("backend"))
    check(backend.scheme == "https" && !backend.host.isNullOrBlank() &&
        !backend.host.endsWith(".invalid", ignoreCase = true) && (backend.port == -1 || backend.port in 1..65535) &&
        backend.userInfo == null && backend.rawQuery == null &&
        backend.fragment == null && backend.path in setOf("", "/")) { "Configure a valid HTTPS backend before distribution" }
    val keys = configuration.stringPropertyNames().filter { it.startsWith("key.") }
    check(keys.isNotEmpty()) { "Configure a pinned release signing public key" }
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
    maven("https://keksuccino.github.io/maven/")
}

val browserRuntime = configurations.create("browserRuntime") { isTransitive = false }
val prepareBrowserRuntime by tasks.registering {
    val original = providers.provider { browserRuntime.singleFile }
    val output = layout.buildDirectory.file("browser-runtime/rinku.jar")
    inputs.file(original)
    outputs.file(output)
    doLast {
        ZipFile(original.get()).use { source ->
            val config = JsonSlurper().parseText(source.getInputStream(source.getEntry("rinku.mixins.json")).reader().use { it.readText() }) as MutableMap<String, Any>
            val startup = setOf("MixinClientPackSource", "MixinMinecraft")
            val mixins = config["client"] as List<*>
            check(mixins.containsAll(startup)) { "Review Rinku startup hooks after upgrading the browser runtime" }
            // Download and initialize Chromium only when a browser is opened, so local features stay usable offline.
            config["client"] = mixins.filterNot { it in startup }
            val destination = output.get().asFile.apply { parentFile.mkdirs() }
            ZipOutputStream(destination.outputStream()).use { target ->
                source.entries().asSequence().forEach { entry ->
                    target.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    if (entry.name == "rinku.mixins.json") target.write(JsonOutput.toJson(config).toByteArray())
                    else source.getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        }
    }
}

dependencies {
    add(browserRuntime.name, "de.keksuccino:rinku-fabric:${providers.gradleProperty("rinku_version").get()}")
    implementation(files(prepareBrowserRuntime))
    include("de.keksuccino:rinku-fabric:${providers.gradleProperty("rinku_version").get()}") { isTransitive = false }
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

	// Optional at runtime (players install it themselves); pinned by Modrinth version ID so the dev client has it.
	runtimeOnly("maven.modrinth:skyblock-profile-viewer:${providers.gradleProperty("skyblockpv_version").get()}")

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
// Replace only this task's generated browser copy with the prepared runtime copy.
tasks.named<NestableJarGenerationTask>("processIncludeJars") {
    dependsOn(prepareBrowserRuntime)
    inputs.files(prepareBrowserRuntime)
    doLast {
        val browserName = "rinku-fabric-${providers.gradleProperty("rinku_version").get()}.jar"
        prepareBrowserRuntime.get().outputs.files.singleFile.copyTo(outputDirectory.file(browserName).get().asFile, overwrite = true)
    }
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version, "minecraft_version" to providers.gradleProperty("minecraft_version").get(),
            "fabric_api_version" to providers.gradleProperty("fabric_api_version").get(),
            "loader_version" to providers.gradleProperty("loader_version").get())
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

tasks.jar {
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
        checkCommunityConfiguration(Properties().apply { putAll(values) })
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
            check(mod["id"] == "skymyce")
            check(jar.getEntry("me/mycellium/mixin/MinecraftAccountKeyAccessor.class") != null)
            val config = Properties().apply { jar.getInputStream(jar.getEntry("sky-myce-community.properties")).use { load(it) } }
            checkCommunityConfiguration(config)
            check(config.stringPropertyNames().all { it in setOf("backend", "minecraft") || it.startsWith("key.") })
            check(jar.entries().asSequence().any { it.name.startsWith("META-INF/jars/owo-lib-") })
            // Fabric API is a declared dependency every Fabric client already installs; bundling it only grew the jar.
            check(jar.entries().asSequence().none { it.name.startsWith("META-INF/jars/fabric-api-") })
            val browser = jar.entries().asSequence().single { it.name.startsWith("META-INF/jars/rinku-fabric-") }
            ZipInputStream(jar.getInputStream(browser)).use { nested ->
                var entry = nested.nextEntry
                while (entry != null && entry.name != "rinku.mixins.json") entry = nested.nextEntry
                check(entry != null) { "Embedded browser mixin configuration is missing" }
                val mixins = JsonSlurper().parseText(nested.readBytes().toString(Charsets.UTF_8)) as Map<*, *>
                check(mixins["client"] == listOf("MixinGameRenderer")) { "The browser must not initialize or download at game startup" }
            }
            check(jar.getEntry("browser/ubol-2026.1012.1545.zip") != null)
            logger.lifecycle("Standalone Fabric packaging passed: full UI runtime, Minecraft proof mixin, public configuration")
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

val dungeonFriendsCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks dungeon friend scanning, profile parsing, filters, sorting and messages"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendsCheckKt"
    // The cosmetic checks start Minecraft logging, which writes logs/ into the working directory.
    workingDir = layout.buildDirectory.dir("dungeon-friends-check").get().asFile
    doFirst { workingDir.mkdirs() }
}

val dungeonChestProfitCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks dungeon chest reward estimates and loss thresholds"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.DungeonChestProfitCheckKt"
}

val dungeonSplitsCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks dungeon split triggers, ordering, personal bests, theoretical best, averages, storage and 300 score"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitsCheckKt"
}

val dungeonWinSummaryCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks the dungeon win screen summary, splits comparison and target level progress"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.DungeonWinSummaryCheckKt"
}

val goldorCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks Goldor coordination: role routes, the S2 decision table, pre-leap timing, chat protocol and run state"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.goldor.GoldorCheckKt"
}

val priceHistoryCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks price history parsing, endpoints, downsampling and stats"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.general.pricehistory.PriceHistoryCheckKt"
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
    from(sourceSets.test.get().output) {
        include("me/mycellium/skymyce/ui/GuiVisual*.class",
            "me/mycellium/skymyce/features/instances/dungeons/camera/render/CameraRenderCheck*.class")
    }
    from("src/test/resources/gui-visual-check")
}
loom.runs.register("guiVisualCheck") {
    client()
    runDirectory = layout.buildDirectory.dir("gui-visual-check/client")
    generateRunConfig = false
    systemProperties.put("fabric.addMods", layout.buildDirectory.file("gui-visual-check/gui-visual-check.jar").get().asFile.absolutePath)
    systemProperties.put("devauth.enabled", "false")
    systemProperties.put("skymyce.browserMuted", "true")
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
tasks.check { dependsOn(dungeonFriendsCheck, dungeonChestProfitCheck, dungeonSplitsCheck, dungeonWinSummaryCheck, priceHistoryCheck, goldorCheck, hudCheck, menuMotionCheck, menuNavigationCheck, menuCatalogCheck, menuShortcutsCheck, runtimeProfilerCheck, featureWorkCheck, dungeonRoutingCheck, globalChatCheck, ircChecks) }
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
val leapMenuCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks Spirit Leap identities, stable quadrants, camera transforms and bounded preview scheduling"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.leap.LeapMenuCheckKt"
}
tasks.check { dependsOn(leapMenuCheck) }
val greenhouseCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks the Greenhouse catalog, skymutations.eu profit formula, layout solver parity and layout persistence"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.garden.GreenhouseCheckKt"
}
tasks.check { dependsOn(greenhouseCheck) }
for (browserCheckName in listOf("BrowserLinks", "BrowserExtension")) {
    val browserCheck = tasks.register<JavaExec>(browserCheckName.replaceFirstChar { it.lowercase() } + "Check") {
        group = "verification"
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = "me.mycellium.skymyce.features.browser.${browserCheckName}CheckKt"
    }
    tasks.check { dependsOn(browserCheck) }
}
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
