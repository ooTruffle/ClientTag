plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.3" /* [SC] DO NOT EDIT */

@Suppress("UNCHECKED_CAST")
val selectedVersions = gradle.extra["clienttag.selectedVersions"] as List<String>

// Builds every selected version and copies the jars to build/libs/<mod version>/.
tasks.register("buildAll") {
    group = "build"
    description = "Builds the mod for every selected Minecraft version (see clienttag.versions)."
    dependsOn(selectedVersions.map { ":$it:buildAndCollect" })
}
