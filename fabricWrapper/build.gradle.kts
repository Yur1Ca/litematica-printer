import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream

plugins {
    id("java-library")
    id("maven-publish")
    id("mod-plugin")
}

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
}

group = modMavenGroup
version = fullProjectVersion

base {
    archivesName.set(modArchivesBaseName)
}

// 获取所有子项目（排除当前项目）
val fabricSubprojects = rootProject.subprojects.filter { it.name != "fabricWrapper" }

// 确保先评估所有子项目
fabricSubprojects.forEach {
    evaluationDependsOn(":${it.name}")
}

fun rewriteJar(jarFile: File, transformer: (String, Boolean, ByteArray) -> ByteArray?) {
    val tmpFile = File(jarFile.parentFile, "${jarFile.name}.tmp")
    val seenEntries = HashSet<String>()

    JarInputStream(jarFile.inputStream().buffered()).use { input ->
        JarOutputStream(tmpFile.outputStream().buffered()).use { output ->
            var entry = input.nextJarEntry
            while (entry != null) {
                val entryName = entry.name
                if (seenEntries.add(entryName)) {
                    val bytes = if (entry.isDirectory) {
                        ByteArray(0)
                    } else {
                        val buffer = ByteArrayOutputStream()
                        input.copyTo(buffer)
                        buffer.toByteArray()
                    }

                    val transformed = transformer(entryName, entry.isDirectory, bytes)
                    if (transformed != null) {
                        val newEntry = JarEntry(entryName)
                        if (entry.time >= 0) {
                            newEntry.time = entry.time
                        }
                        output.putNextEntry(newEntry)
                        if (!entry.isDirectory) {
                            output.write(transformed)
                        }
                        output.closeEntry()
                    }
                }
                input.closeEntry()
                entry = input.nextJarEntry
            }
        }
    }

    tmpFile.copyTo(jarFile, overwrite = true)
    tmpFile.delete()
}

fun stripJarResources(jarFile: File, prefixes: Set<String>) {
    if (prefixes.isEmpty()) {
        return
    }

    rewriteJar(jarFile) { entryName, _, bytes ->
        val shouldDrop = prefixes.any { prefix ->
            entryName == prefix || entryName.startsWith(prefix)
        }
        if (shouldDrop) null else bytes
    }
}

fun collectNestedLibs(jarFile: File): Set<String> {
    val libs = LinkedHashSet<String>()
    JarInputStream(jarFile.inputStream().buffered()).use { input ->
        var entry = input.nextJarEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.startsWith("META-INF/jars/") && entry.name.endsWith(".jar")) {
                libs.add(File(entry.name).name)
            }
            input.closeEntry()
            entry = input.nextJarEntry
        }
    }
    return libs
}

fun extractNestedLib(jarFile: File, libName: String, targetFile: File): Boolean {
    JarInputStream(jarFile.inputStream().buffered()).use { input ->
        var entry = input.nextJarEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.startsWith("META-INF/jars/") && File(entry.name).name == libName) {
                targetFile.parentFile.mkdirs()
                targetFile.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
                input.closeEntry()
                return true
            }
            input.closeEntry()
            entry = input.nextJarEntry
        }
    }
    return false
}

fun removeNestedLibsFromJar(jarFile: File, libNamesToRemove: Set<String>) {
    if (libNamesToRemove.isEmpty()) {
        return
    }

    rewriteJar(jarFile) { entryName, _, bytes ->
        if (entryName.startsWith("META-INF/jars/") && entryName.endsWith(".jar") && File(entryName).name in libNamesToRemove) {
            null
        } else if (entryName == "fabric.mod.json") {
            @Suppress("UNCHECKED_CAST")
            val jsonContent = JsonSlurper().parseText(String(bytes, Charsets.UTF_8)) as MutableMap<String, Any>
            @Suppress("UNCHECKED_CAST")
            val jars = jsonContent["jars"] as? List<Map<String, String>>

            if (jars != null) {
                val filteredJars = jars.filterNot { jar ->
                    val file = jar["file"]
                    file != null && File(file).name in libNamesToRemove
                }

                if (filteredJars.isEmpty()) {
                    jsonContent.remove("jars")
                } else {
                    jsonContent["jars"] = filteredJars
                }

                JsonBuilder(jsonContent).toPrettyString().toByteArray(Charsets.UTF_8)
            } else {
                bytes
            }
        } else {
            bytes
        }
    }
}

fun optimizeWrapperSubmoduleJars(jarsDir: File, sharedResourcePrefixes: Set<String>) {
    val jarFiles = jarsDir.listFiles { file ->
        file.isFile && file.name.endsWith(".jar") &&
                !file.name.contains("-dev.jar") &&
                !file.name.contains("-sources.jar") &&
                !file.name.contains("-shadow.jar")
    }?.toList().orEmpty().sortedBy { it.name }

    if (jarFiles.isEmpty()) {
        return
    }

    jarFiles.forEach { jarFile ->
        stripJarResources(jarFile, sharedResourcePrefixes)
    }

    val commonNestedLibs = jarFiles
        .map { collectNestedLibs(it) }
        .reduceOrNull { acc, libs -> acc.intersect(libs).toCollection(LinkedHashSet()) }
        .orEmpty()

    commonNestedLibs.forEach { libName ->
        val targetFile = File(jarsDir, libName)
        if (!targetFile.exists()) {
            extractNestedLib(jarFiles.first(), libName, targetFile)
        }
    }

    jarFiles.forEach { jarFile ->
        removeNestedLibsFromJar(jarFile, commonNestedLibs)
    }
}

tasks {
    // 收集子模块 JAR 文件任务
    register("collectSubModules") {
        description = "收集所有子模块的 JAR 文件"
        outputs.upToDateWhen { false }

        val embeddedJarsDir = layout.buildDirectory.dir("tmp/submods/META-INF/jars")
        val standaloneJarsDir = layout.buildDirectory.dir("libs/jars")

        // 构建并收集所有 Minecraft 子项目的独立 JAR。
        dependsOn(fabricSubprojects.map { it.tasks.named("buildAndCollect") })

        doFirst {
            delete(embeddedJarsDir)
            delete(standaloneJarsDir)

            // wrapper 内嵌 JAR 会做瘦身，独立版本 JAR 保持原始产物。
            copy {
                from(fabricSubprojects.map { sub ->
                    sub.tasks.named("buildAndCollect").get().outputs.files
                })
                into(embeddedJarsDir)
            }

            copy {
                from(fabricSubprojects.map { sub ->
                    sub.tasks.named("buildAndCollect").get().outputs.files
                })
                into(standaloneJarsDir)
            }

            optimizeWrapperSubmoduleJars(
                embeddedJarsDir.get().asFile,
                setOf(
                    "assets/$modId/icon.png",
                    "assets/$modId/lang/"
                )
            )
        }
    }

    // JAR 打包任务
    named<Jar>("jar") {
        outputs.upToDateWhen { false }

        from(rootProject.file("LICENSE"))
        from(layout.buildDirectory.dir("tmp/submods"))
    }

    // 资源处理任务
    named<ProcessResources>("processResources") {
        outputs.upToDateWhen { false }

        dependsOn("collectSubModules")

        from(rootProject.file("src/main/resources/assets/$modId/lang")) {
            into("assets/$modId/lang")
        }

        val rootIcon = rootProject.file("src/main/resources/assets/$modId/icon.png")
        val resourcesFile = layout.projectDirectory.file("src/main/resources/assets/$wrapperModId/icon.png").asFile
        val buildIconFile = layout.buildDirectory.file("resources/main/assets/$wrapperModId/icon.png").get().asFile

        doLast {
            if (rootIcon.exists()) {
                if (!resourcesFile.exists()) {
                    println("⚠ 子项目未找到图标文件，准备从根项目中复制图标")
                    buildIconFile.parentFile.mkdirs()
                    rootIcon.copyTo(buildIconFile, overwrite = true)
                    println("✓ 图标复制成功: ${rootIcon.name} -> ${buildIconFile.name}")
                }
            } else {
                println("⚠ 根项目中未找到图标文件，跳过图标复制")
            }
        }

        doLast {
            val jars = ArrayList<Map<String, String>>()
            val jarsDir = layout.buildDirectory.dir("tmp/submods/META-INF/jars").get().asFile

            if (jarsDir.exists() && jarsDir.isDirectory) {
                val jarFiles = jarsDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".jar") &&
                            !file.name.contains("-dev.jar") &&
                            !file.name.contains("-sources.jar") &&
                            !file.name.contains("-shadow.jar")
                }

                jarFiles?.sortedBy { it.name }?.forEach { jarFile ->
                    jars.add(mapOf("file" to "META-INF/jars/${jarFile.name}"))
                }
            }

            val minecraftVersions = mutableListOf<String>()
            fabricSubprojects.forEach { subproject ->
                try {
                    val minecraftVersion = subproject.property("minecraft_dependency") as String
                    if (minecraftVersion.isNotBlank()) {
                        minecraftVersions.add(minecraftVersion)
                        println("收集到 Minecraft 版本: $minecraftVersion")
                    }
                } catch (e: Exception) {
                    println("⚠ 无法从子项目 ${subproject.name} 获取 Minecraft 版本")
                }
            }

            // 更新 fabric.mod.json 文件
            val jsonFile = layout.buildDirectory.file("resources/main/fabric.mod.json").get().asFile
            if (jsonFile.exists()) {
                val slurper = JsonSlurper()

                @Suppress("UNCHECKED_CAST")
                val jsonContent = slurper.parse(jsonFile) as MutableMap<String, Any>

                // 设置 jars 数组
                jsonContent["jars"] = jars

                // 更新 Minecraft 依赖
                @Suppress("UNCHECKED_CAST")
                val depends = jsonContent["depends"] as? MutableMap<String, Any>
                depends?.put("minecraft", minecraftVersions)

                // 写回文件
                val builder = JsonBuilder(jsonContent)
                jsonFile.bufferedWriter().use { writer ->
                    writer.write(builder.toPrettyString())
                }

                println("- JAR 文件数量: ${jars.size}")
                jars.forEach { jar ->
                    println("  - ${jar["file"]}")
                }
                println("✅ Minecraft 依赖已更新为: $minecraftVersions")
            } else {
                println("警告: 找不到生成的 fabric.mod.json 文件: ${jsonFile.absolutePath}")
            }
        }
    }
}
