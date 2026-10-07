pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()

        maven("https://maven.fabricmc.net")
        maven("https://maven.ornithemc.net/releases")
        maven("https://maven.ornithemc.net/snapshots")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")

        maven("https://maven.deftu.dev/releases")
        maven("https://maven.deftu.dev/snapshots")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

/** Every Minecraft version OneConfig ships for: 1.8.9 on Ornithe, the rest on Fabric. */
val supportedVersions = listOf("1.8.9", "1.21.1", "1.21.4", "1.21.5", "1.21.8", "1.21.10", "1.21.11", "26.1", "26.2", "26.3")

/** The version the sources are checked in as - must match `stonecutter active` in stonecutter.gradle.kts. */
val vcs = "26.3"

/**
 * Which versions to build: all of them by default, or a comma-separated subset passed as
 * `-Pclienttag.versions=1.8.9,1.21.11` (or set in gradle.properties). Versions left out aren't
 * configured at all, so their Minecraft is never downloaded.
 */
val selectedVersions = providers.gradleProperty("clienttag.versions").orNull
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
    ?.takeUnless { it.isEmpty() || it == listOf("all") }
    ?.also { requested ->
        val unknown = requested - supportedVersions
        require(unknown.isEmpty()) {
            "clienttag.versions: unsupported version(s) $unknown - pick from $supportedVersions"
        }
    }
    ?: supportedVersions

stonecutter {
    create(rootProject) {
        // Stonecutter requires the checked-in version to be registered; it is configured but not built unless selected.
        versions(*supportedVersions.filter { it in selectedVersions || it == vcs }.toTypedArray())
        vcsVersion = vcs
    }
}

gradle.extra["clienttag.selectedVersions"] = selectedVersions

rootProject.name = "ClientTag"
