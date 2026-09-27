import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

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

dependencies {
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
	include("maven.modrinth:skyblock-profile-viewer:${providers.gradleProperty("skyblockpv_version").get()}")

	// Resourceful Config
	implementation("com.teamresourceful.resourcefulconfig:resourcefulconfig-fabric-26.1:${providers.gradleProperty("resourceful_config_version").get()}")
	include("com.teamresourceful.resourcefulconfig:resourcefulconfig-fabric-26.1:${providers.gradleProperty("resourceful_config_version").get()}")

	implementation("com.teamresourceful.resourcefulconfigkt:resourcefulconfigkt-26.1-rc-1:${providers.gradleProperty("resourceful_config_kt_version").get()}")
	include("com.teamresourceful.resourcefulconfigkt:resourcefulconfigkt-26.1-rc-1:${providers.gradleProperty("resourceful_config_kt_version").get()}")

	// owo lib
	implementation("io.wispforest:owo-lib:${providers.gradleProperty("owo_version").get()}")
	include("io.wispforest:owo-sentinel:${providers.gradleProperty("owo_version").get()}")
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

tasks.jar {
	exclude("me/mycellium/skymyce/loader/**", "me/mycellium/mixin/MinecraftAccountKeyAccessor.class", "sky-myce-loader.properties")
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

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
    from(sourceSets.main.get().output) { include("me/mycellium/skymyce/loader/**", "me/mycellium/mixin/MinecraftAccountKeyAccessor.class") }
    from(loaderResources)
    // First activation has no protected mod (or its sentinel); owo includes its own nested dependencies.
    from({ configurations.runtimeClasspath.get().single { it.name == "owo-lib-${providers.gradleProperty("owo_version").get()}.jar" } }) {
        into("META-INF/jars"); rename { "owo-lib.jar" }
    }
    // Gson already ships with Minecraft; bundling it lets java -jar apply an update outside Minecraft.
    from({ configurations.runtimeClasspath.get().filter { it.name.startsWith("gson-") }.map { zipTree(it) } }) { exclude("META-INF/**") }
    manifest { attributes["Main-Class"] = "me.mycellium.skymyce.loader.LoaderInstaller" }
}
tasks.assemble { dependsOn(loaderJar) }

val dungeonFriendsCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks dungeon friend scanning, profile parsing, filters, sorting and messages"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendsCheckKt"
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
tasks.check { dependsOn(dungeonFriendsCheck, hudCheck, loaderCheck) }
// Assertions run via the check tasks; this project does not use a test framework.
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
