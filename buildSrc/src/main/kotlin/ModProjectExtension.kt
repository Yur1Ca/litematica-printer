import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import java.io.File

fun Project.propOrNull(key: String) = findProperty(key)
fun Project.prop(key: String) = propOrNull(key) ?: throw GradleException("buildSrc: 属性 $key 未配置/值为空")

fun Project.propStrOrNull(key: String): String? = propOrNull(key)?.toString()
fun Project.propStr(key: String): String = propStrOrNull(key)
    ?: throw GradleException("buildSrc: 属性 $key 未配置/值为空，或无法转换为字符串")

fun Project.downloadDependencyMod(downloadUrl: String, fileName: String? = null): File? {
    return rootProject.downloadFile(
        downloadUrl = downloadUrl,
        outputDirPath = "${rootProject.projectDir}/libs",
        fileName = fileName
    )
}

val Project.modId get() = propStr("mod_id")
val Project.wrapperModId get() = "$modId-wrapper"
val Project.modName get() = propStr("mod_name")
val Project.modVersion get() = propStr("mod_version")
val Project.modMavenGroup get() = propStr("mod_maven_group")
val Project.modArchivesBaseName get() = propStr("mod_archives_base_name")

<<<<<<< HEAD
=======
// Some Modrinth Maven coordinates are published under internal artifact IDs
// instead of the mod slug. This helper keeps module properties using the
// human-readable slug+version form while translating to the real Maven artifact.
private val modrinthArtifactIds = mapOf(
    "litematica" to "bEpr0Arc",
    "malilib" to "GcWjdA9I",
    "tweakeroo" to "t5wuYk45",
    "modmenu" to "mOgUt4GM"
)

fun Project.modrinthArtifactId(slug: String): String = modrinthArtifactIds[slug] ?: slug

fun Project.modrinthDependency(slug: String, version: String?): String {
    val artifactVersion = version ?: throw GradleException("Missing Modrinth dependency version for '$slug'")
    return "maven.modrinth:${modrinthArtifactId(slug)}:$artifactVersion"
}

>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
val Project.modDescription get() = propStrOrNull("mod_description")
val Project.modHomepage get() = propStrOrNull("mod_homepage")
val Project.modLicense get() = propStrOrNull("mod_license")
val Project.modSources get() = propStrOrNull("mod_sources")

val Project.mcDependency get() = propStrOrNull("minecraft_dependency")
val Project.mcVersion get() = propStrOrNull("minecraft_version")
val Project.mcVersionInt get() = propStrOrNull("mcVersion")?.toIntOrNull() ?: -1
val Project.fabricLoaderVersion get() = propStrOrNull("loader_version")
val Project.fabricApiVersion get() = propStrOrNull("fabric_version")

val Project.malilib get() = propStrOrNull("malilib")
val Project.litematica get() = propStrOrNull("litematica")

val Project.lombokVersion get() = propStr("lombok_version")
val Project.githubRunNumber get() = System.getenv("GITHUB_RUN_NUMBER")?.takeIf { it.isNotBlank() }
val Project.isReleaseWorkflow get() = System.getenv("IS_THIS_RELEASE")?.equals("true", ignoreCase = true) == true

val Project.javaVersion
    get() = when {
        mcVersionInt >= 260000 -> JavaVersion.VERSION_25
        mcVersionInt >= 12005 -> JavaVersion.VERSION_21
        mcVersionInt >= 11800 -> JavaVersion.VERSION_17
        mcVersionInt >= 11700 -> JavaVersion.VERSION_16
        else -> JavaVersion.VERSION_1_8
    }
val Project.mixinJavaVersion get() = "JAVA_${javaVersion}"

val Project.fullProjectVersion: String get() {
    val buildNumber = githubRunNumber
    return when {
        buildNumber != null && isReleaseWorkflow -> "$modVersion-beta$buildNumber"
        buildNumber != null -> "$modVersion-dev$buildNumber"
        else -> "$modVersion-local"
    }
}

private val Project.modVersionFlavorSuffix: String
    get() = modVersion.substringAfter('-', "").let { if (it.isEmpty()) "" else "-$it" }

val Project.artifactVersion: String get() {
    val buildNumber = githubRunNumber
    val baseVersion = mcVersion ?: modVersion.substringBefore('-')
    val flavorSuffix = modVersionFlavorSuffix
    return when {
        buildNumber != null && isReleaseWorkflow -> "$baseVersion$flavorSuffix-beta$buildNumber"
        buildNumber != null -> "$baseVersion$flavorSuffix-dev$buildNumber"
        else -> "$baseVersion$flavorSuffix-local"
    }
}

val Project.placeholderProps: Map<String, Any?>
    get() = mapOf(
        "mod_id" to modId,
        "mod_wrapper_id" to wrapperModId,
        "mod_name" to modName,
        "mod_version" to fullProjectVersion,
        "mod_description" to modDescription,
        "mod_homepage" to modHomepage,
        "mod_license" to modLicense,
        "mod_sources" to modSources,
        "loader_version" to fabricLoaderVersion,
        "fabric_api_version" to fabricApiVersion,
        "minecraft_dependency" to mcDependency,
        "compatibility_level" to mixinJavaVersion,
        "malilib" to malilib,
        "litematica" to litematica
    ).filterValues { it != null }.mapValues { it.value!! }
