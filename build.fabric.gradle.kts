@file:Suppress("UnstableApiUsage")

import java.text.SimpleDateFormat
import java.util.*

plugins {
    id("mod-plugin")
    id("maven-publish")
    id("net.fabricmc.fabric-loom")
    id("com.replaymod.preprocess")
}

val time = SimpleDateFormat("yyMMdd")
    .apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }
    .format(Date())
    .toString()

version = artifactVersion
group = modMavenGroup

repositories {
    mavenCentral()
    fun strictMaven(url: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) }
        filter {
            groups.forEach {
                includeGroupAndSubgroups(it)
                includeGroupAndSubgroups("$it.*")
            }
        }
    }
    strictMaven("https://maven.fabricmc.net")
    strictMaven("https://maven.fallenbreath.me/releases")
    strictMaven("https://masa.dy.fi/maven/sakura-ryoko", "fi.dy.masa")

    strictMaven("https://www.cursemaven.com", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "maven.modrinth")

    strictMaven("https://maven.terraformersmc.com/releases", "com.terraformersmc")  // ModMenu
    strictMaven("https://maven.nucleoid.xyz", "eu.pb4") // ModMenu依赖TextPlaceholderAPI
    strictMaven("https://jitpack.io")
}

fun masaDependency(mod: String): String {
    val artifact = propStrOrNull("${mod}_artifact")?.takeIf { it.isNotBlank() }
    return artifact?.let { "fi.dy.masa.$mod:$it:${prop(mod)}" }
        ?: "maven.modrinth:$mod:${prop(mod)}"
}

val malilibDependency = masaDependency("malilib")
val litematicaDependency = masaDependency("litematica")
val tweakerooDependency = masaDependency("tweakeroo")
val modMenuDependency = "maven.modrinth:modmenu:${prop("modmenu")}"

// https://github.com/FabricMC/fabric-loader/issues/783
configurations.all {
    resolutionStrategy {
        dependencySubstitution {
            substitute(module("com.terraformersmc:modmenu"))
                .using(module(modMenuDependency))
                .because("Use one Mod Menu coordinate when dependencies request the official Maven module")
            substitute(module("com.github.sakura-ryoko:malilib"))
                .using(module(malilibDependency))
                .because("Use the configured MaLiLib artifact instead of a legacy Sakura-Ryoko coordinate")
            substitute(module("com.github.sakura-ryoko:litematica"))
                .using(module(litematicaDependency))
                .because("Use the configured Litematica artifact instead of a legacy Sakura-Ryoko coordinate")
            substitute(module("com.github.sakura-ryoko:tweakeroo"))
                .using(module(tweakerooDependency))
                .because("Use the configured Tweakeroo artifact instead of a legacy Sakura-Ryoko coordinate")

            if (propStrOrNull("malilib_artifact")?.isNotBlank() == true) {
                substitute(module("maven.modrinth:malilib")).using(module(malilibDependency))
                substitute(module("maven.modrinth:litematica")).using(module(litematicaDependency))
                substitute(module("maven.modrinth:tweakeroo")).using(module(tweakerooDependency))
            }
        }
        force("net.fabricmc:fabric-loader:$fabricLoaderVersion")
        force(malilibDependency)
        force(litematicaDependency)
        force(tweakerooDependency)
        force(modMenuDependency)
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")

    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")

    implementation("com.belerweb:pinyin4j:${prop("pinyin_version")}")?.let { include(it) }

    implementation(modMenuDependency)

    // masa
    implementation(malilibDependency)
    implementation(litematicaDependency)
    implementation(tweakerooDependency) {
        exclude(group = "com.github.sakura-ryoko", module = "malilib")
        exclude(group = "maven.modrinth", module = "malilib")
        exclude(group = "fi.dy.masa.malilib")
    }

    // 快捷潜影盒
    val quickshulkerUrl = prop("quickshulker").toString()
    if (quickshulkerUrl.isNotEmpty()) {
        val quickshulkerFile = downloadDependencyMod(quickshulkerUrl)
        if (quickshulkerFile != null && quickshulkerFile.exists()) {
            implementation(files(quickshulkerFile))
        }
    }

    val chestTrackerUrl = propStrOrNull("chesttracker")
    if (chestTrackerUrl?.startsWith("http") == true) {
        val chestTrackerFile = downloadDependencyMod(chestTrackerUrl)
        if (chestTrackerFile != null && chestTrackerFile.exists()) {
            implementation(files(chestTrackerFile))
        }
    }

    implementation("me.fallenbreath:conditional-mixin-fabric:0.6.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

loom {
    val commonVmArgs = listOf("-Dmixin.debug.export=true", "-Dmixin.debug.verbose=true", "-Dmixin.env.remapRefMap=true")
    val programArgs = listOf("--width", "1280", "--height", "720", "--username", "PrinterTest")
    runs {
        named("client") {
            ideConfigGenerated(true)
            vmArgs(commonVmArgs)
            programArgs(programArgs)
            runDir = "../../run/client"
        }
    }
}

tasks {
    register<Copy>("buildAndCollect") {
        group = "build"
        val collectedJarDir = rootProject.layout.buildDirectory.dir("libs/$modVersion/${project.name}")
        from(jar.map { it.archiveFile })
        into(collectedJarDir)
        doFirst {
            delete(collectedJarDir)
        }
        dependsOn("build")
    }

}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = modId
            version = modVersion
        }
    }
    repositories {
        mavenLocal()
        maven {
            url = uri("$rootDir/publish")
        }
    }
}
