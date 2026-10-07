import net.ornithemc.ploceus.api.PloceusGradleExtensionApi

plugins {
    id("dev.kikugie.loom-back-compat")
    id("net.fabricmc.fabric-loom-remap") version "1.17-SNAPSHOT" apply false
    id("ploceus") version "1.17-SNAPSHOT" apply false
}

// 1.8.9 runs on Ornithe with MCP names and keeps its own code in src/legacy; every other
// version is Fabric with Mojang names, sharing src/main's `modern` package.
val isLegacy = sc.current.version == "1.8.9"
val ploceus = if (isLegacy) {
    pluginManager.apply("net.fabricmc.fabric-loom-remap")
    pluginManager.apply("ploceus")

    extensions.getByType<PloceusGradleExtensionApi>().apply {
        setIntermediaryGeneration(2)
    }
} else {
    null
}

val modId: String = sc.properties["mod.id"]
val modName: String = sc.properties["mod.name"]
val modVersion: String = sc.properties["mod.version"]
val modDescription: String = sc.properties["mod.description"]
val baseGroup: String = sc.properties["mod.group"]
val mcVersion: String = sc.current.version
val mcCompat: String = sc.properties["mod.mc_compat"]
val loaderVersion: String = sc.properties["deps.fabric_loader"]
val oneconfigVersion: String = sc.properties["deps.oneconfig"]
val loader = if (isLegacy) "ornithe" else "fabric"

val requiredJava: JavaVersion = when {
    isLegacy -> JavaVersion.VERSION_25
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

group = baseGroup
version = "$modVersion+$mcVersion"
base.archivesName = "$modName-$modVersion-${mcVersion}_$loader"

repositories {
    mavenCentral()
    google()
    maven("https://repo.polyfrost.org/releases")
    // OneConfig's Compose/androidx dependency tree (lenis, etc.) lives here.
    maven("https://maven.cloverclient.com/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    exclusiveContent {
        forRepository { maven("https://maven.fabricmc.net/") }
        filter { includeGroup("net.fabricmc") }
    }
    // OneConfig's own dependencies (e.g. the Hypixel mod API on modern versions).
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
    exclusiveContent {
        forRepository { maven("https://maven.terraformersmc.com/") }
        filter { includeGroup("com.terraformersmc") }
    }
    exclusiveContent {
        forRepository { maven("https://maven.deftu.dev/releases") }
        filter { includeGroup("dev.deftu") }
    }
}

sourceSets.main {
    val platform = if (isLegacy) "legacy" else "modern"
    resources.srcDir(rootProject.file("src/$platform/resources"))
    if (isLegacy) {
        java.srcDir(rootProject.file("src/legacy/java"))
        java.exclude("com/ootruffle/clienttag/modern/**")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    if (isLegacy) {
        mappings(ploceus!!.mcpMappings("stable", mcVersion, sc.properties["deps.mcp_mappings"]))
        ploceus.dependOsl(sc.properties["deps.osl"])
    } else {
        loomx.applyMojangMappings()
    }

    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    // Fabric Loader alone doesn't load mods' assets - Fabric API's resource loader does. Bundle
    // just that module so the icon font loads without Fabric API installed (Loader dedupes it
    // when Fabric API is there). Some versions of it need fabric-api-base too, which is tiny.
    sc.properties.getOrNull<String>("deps.fabric_api")?.let { fabricApiVersion ->
        val resourceLoader = if (sc.current.parsed >= "1.21.10") "fabric-resource-loader-v1" else "fabric-resource-loader-v0"
        for (module in arrayOf("fabric-api-base", resourceLoader)) {
            include(fabricApi.module(module, fabricApiVersion))
        }
    }

    // Optional at runtime - only touched when OneConfig is loaded (see ClientTagSettings).
    // modImplementation so dev runs get the settings page; it isn't bundled into the jar.
    modImplementation("org.polyfrost.oneconfig:$mcVersion-$loader:$oneconfigVersion")
    for (module in arrayOf("commands", "config", "config-impl", "poly-compose", "utils")) {
        implementation("org.polyfrost.oneconfig:$module:$oneconfigVersion")
    }
}

loom {
    fabricModJsonPath = rootProject.file("src/${if (isLegacy) "legacy" else "modern"}/resources/fabric.mod.json")

    runConfigs.all {
        runDirectory = rootProject.file("run")
    }
    runConfigs.remove(runConfigs["server"])
}

// Modern versions draw the icons as glyphs of a bitmap font; the glyph textures are painted by
// the same IconArt code 1.8.9 uses at runtime, so the shapes only live in one place.
val generateIconFont = tasks.register<JavaExec>("generateIconFont") {
    val output = layout.buildDirectory.dir("generated/iconFont")
    // Not runtimeClasspath: that includes the processed resources this task feeds into.
    classpath = sourceSets.main.get().output.classesDirs + sourceSets.main.get().compileClasspath
    mainClass = "$baseGroup.render.IconArt"
    jvmArgs("-Djava.awt.headless=true")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(output.get().dir("assets/$modId/textures/font").asFile.absolutePath)
    })
    outputs.dir(output)
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        val props = mapOf(
            "mod_id" to modId,
            "mod_name" to modName,
            "mod_version" to modVersion,
            "mod_description" to modDescription,
            "base_group" to baseGroup,
            "java_version" to requiredJava.majorVersion,
            "minecraft_version_range" to mcCompat,
            "fabric_loader_version" to loaderVersion,
            "oneconfig_version" to oneconfigVersion
        )

        inputs.properties(props)

        filesMatching(listOf("fabric.mod.json", "mixins.$modId.json")) {
            expand(props)
        }

        if (!isLegacy) {
            from(generateIconFont)
        }
    }

    withType<JavaCompile>().configureEach {
        options.release = requiredJava.majorVersion.toInt()
    }

    jar {
        inputs.property("archivesName", base.archivesName)
    }

    // The archives name already carries the versions: ClientTag-1.0.0-1.21.11_fabric.jar
    withType<AbstractArchiveTask>().configureEach {
        archiveVersion = ""
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jar and copies it to the root build/libs/<mod version>/."

        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    }
}

java {
    withSourcesJar()
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava

    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}
