pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net") { name = "Fabric" }
        maven("https://jitpack.io") { name = "Jitpack" }
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.replaymod.preprocess" -> {
                    useModule("com.github.Fallen-Breath:preprocessor:${requested.version}")
                }
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val versions = listOf(
    "26.1", "26.2", "26.3"
)
val mainProjectVersion = file("versions/mainProject").readText().trim()
require(mainProjectVersion in versions) {
    "Main project '$mainProjectVersion' is not a supported Minecraft version"
}
val preprocessParentChain = mapOf(
    "26.2" to "26.1",
    "26.3" to "26.2"
)

fun expandWithPreprocessParents(requested: List<String>): List<String> {
    val expanded = linkedSetOf<String>()
    requested.forEach { version ->
        var current: String? = version
        while (current != null && expanded.add(current)) {
            current = preprocessParentChain[current]
        }
    }
    return expanded.toList()
}

val requestedVersions = System.getenv("TARGET_MC_VERSIONS")
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.distinct()

val selectedVersions = when {
    requestedVersions.isNullOrEmpty() -> versions
    else -> {
        val unknownVersions = requestedVersions - versions.toSet()
        require(unknownVersions.isEmpty()) {
            "Unknown TARGET_MC_VERSIONS entries: ${unknownVersions.joinToString(", ")}"
        }
        expandWithPreprocessParents(requestedVersions).let { expanded ->
            if (mainProjectVersion in expanded) expanded else expanded + mainProjectVersion
        }
    }
}

for (version in selectedVersions) {
    include(":$version")
    project(":$version").apply {
        projectDir = file("versions/$version")
        buildFileName = "../../build.fabric.gradle.kts"
    }
}

include(":fabricWrapper")
