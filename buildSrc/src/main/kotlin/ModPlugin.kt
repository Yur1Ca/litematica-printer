import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.kotlin.dsl.*
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.nio.file.Files

@Suppress("unused")
abstract class ModPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("java")
        pluginManager.apply("jacoco")

        configureArchives()
        configureJava()
        configureLombok()
        configureJavaCompile()
        configureTestWorkers()
        configureResources()
        configureJar()
        configureCoreCoverage()
        configureArchitectureVerification()
    }

    private fun Project.configureCoreCoverage() {
        val coreClasses = { source: org.gradle.api.file.FileCollection ->
            files(source.files.map { directory ->
                fileTree(directory) {
                    include("me/aleksilassila/litematica/printer/core/**/*.class")
                    exclude("**/*\$*")
                }
            })
        }
        tasks.named<JacocoReport>("jacocoTestReport").configure {
            dependsOn(tasks.named("test"))
            classDirectories.setFrom(coreClasses(classDirectories))
            reports {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }
        }
        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification").configure {
            dependsOn(tasks.named("test"))
            classDirectories.setFrom(coreClasses(classDirectories))
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = "0.85".toBigDecimal()
                    }
                    limit {
                        counter = "BRANCH"
                        value = "COVEREDRATIO"
                        minimum = "0.75".toBigDecimal()
                    }
                }
            }
        }
        tasks.named("check").configure {
            dependsOn(tasks.named("jacocoTestCoverageVerification"))
        }
    }

    private fun Project.configureArchives() {
        extensions.configure<BasePluginExtension> {
            archivesName.set(modArchivesBaseName)
        }
    }

    private fun Project.configureJava() {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
            // withSourcesJar()
        }
    }

    private fun Project.configureLombok() {
        pluginManager.withPlugin("java") {
            dependencies.add(
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                "org.projectlombok:lombok:$lombokVersion"
            )
            dependencies.add(
                JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME,
                "org.projectlombok:lombok:$lombokVersion"
            )
        }
    }

    private fun Project.configureJavaCompile() {
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(
                listOf(
                    "-Xlint:deprecation",
                    "-Xlint:unchecked"
                )
            )
            if (javaVersion <= JavaVersion.VERSION_1_8) {
                options.compilerArgs.add("-Xlint:-options")
            }
        }
    }

    private fun Project.configureTestWorkers() {
        tasks.withType<Test>().configureEach {
            // Java 25 warns when legacy Guava/JOML code uses Unsafe. Keep the
            // compatibility access enabled only on runtimes that support this
            // switch; older Java versions must not receive an unknown option.
            if (Runtime.version().feature() >= 23) {
                jvmArgs("--sun-misc-unsafe-memory-access=allow")
            }
        }
    }

    private fun Project.configureResources() {
        tasks.withType<ProcessResources>().configureEach {
            inputs.properties(placeholderProps)
            filesMatching(listOf("*.mixins.json", "*.mod.json", "META-INF/*mods.toml")) {
                expand(placeholderProps)
            }
        }
    }

    private fun Project.configureJar() {
        tasks.withType<Jar>().configureEach {
            from(rootProject.file("LICENSE")) {
                rename { originalName ->
                    "${originalName}_${modArchivesBaseName}"
                }
            }
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            manifest {
                attributes(
                    mapOf(
                        "Implementation-Title" to project.name,
                        "Implementation-Version" to project.version
                    )
                )
            }
        }
    }

    private fun Project.configureArchitectureVerification() {
        val taskName = "verifyArchitecture"
        val verification = rootProject.tasks.findByName(taskName)?.let { rootProject.tasks.named(taskName) }
            ?: rootProject.tasks.register(taskName) {
                group = "verification"
                description = "Checks architectural dependency boundaries in shared production sources"
                val sourceRoot = rootProject.file("src/main/java")
                inputs.dir(sourceRoot)
                doLast {
                    val violations = mutableListOf<String>()
                    if (!sourceRoot.exists()) return@doLast

                    Files.walk(sourceRoot.toPath()).use { paths ->
                        paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                            .forEach { path ->
                                val relative = sourceRoot.toPath().relativize(path).toString().replace('\\', '/')
                                val text = Files.readString(path)

                                if (relative.contains("/core/")) {
                                    val forbidden = listOf(
                                        ".handler.",
                                        ".mixin.",
                                        ".printer.zxy.",
                                        ".utils.mods.",
                                        "fi.dy.masa.",
                                        "net.fabricmc."
                                    )
                                    forbidden.filter(text::contains).forEach { dependency ->
                                        violations += "$relative: core source depends on forbidden boundary '$dependency'"
                                    }
                                }

                                // The runtime bridge is the only permitted process-wide mutable
                                // reference. Core/runtime services keep mutable collections and
                                // lifecycle state on instances so epoch reset remains explicit.
                                if ((relative.contains("/core/") || relative.contains("/runtime/"))
                                    && !relative.endsWith("/runtime/RuntimeAccess.java")
                                    && Regex("\\bstatic\\s+(?:volatile\\s+)?(?:Map|Set|List|Deque|Queue|Collection)\\b")
                                        .containsMatchIn(text)) {
                                    violations += "$relative: core/runtime must not own mutable static collections"
                                }

                                if (relative.contains("/feature/")) {
                                    val forbidden = listOf(".mixin.", ".printer.zxy.", ".utils.mods.")
                                    forbidden.filter(text::contains).forEach { dependency ->
                                        violations += "$relative: feature source depends on forbidden boundary '$dependency'"
                                    }
                                }

                                if (relative.contains("/feature/") && relative.endsWith("Planner.java")) {
                                    val forbidden = listOf(
                                        ".handler.", ".runtime.", ".config.", ".integration.",
                                        "net.minecraft.client.", ".network.", "ActionPort", "ActionManager",
                                        "InventoryUtils", "MaterialSelector", "InteractionUtils", "sendPacket(",
                                        "queueClick(", "setBlock(", "destroyBlock(", "RuntimeAccess"
                                    )
                                    forbidden.filter(text::contains).forEach { dependency ->
                                        violations += "$relative: planner must only consume observations, forbidden '$dependency'"
                                    }
                                }
                                if (relative.endsWith("/printer/ActionManager.java") && text.contains("Configs.Print")) {
                                    violations += "$relative: feature reserve policy belongs at the composition boundary"
                                }
                                if (listOf("/handler/handlers/FillHandler.java", "/handler/handlers/FluidHandler.java")
                                        .any(relative::endsWith) && text.contains("Configs.Print")) {
                                    violations += "$relative: shared placement settings must use SharedPlacementSettings"
                                }
                                if (listOf("/handler/FeatureModuleBase.java", "/handler/scan/ScanEngine.java")
                                        .any(relative::endsWith)
                                    && Regex("(?:private|protected|public)\\s+(?:final\\s+)?PrinterRuntime\\s+\\w+\\s*;")
                                        .containsMatchIn(text)) {
                                    violations += "$relative: retain only the runtime capabilities this service consumes"
                                }
                                if (relative.endsWith("/handler/TickScheduler.java") && text.contains("runtime.reset(")) {
                                    violations += "$relative: feature toggles must not reset session transports"
                                }

                                if (relative.contains("/handler/handlers/")
                                    && listOf(".mixin.", ".printer.zxy.", ".utils.mods.")
                                        .any(text::contains)) {
                                    violations += "$relative: feature handler must use an integration/interaction port"
                                }

                                // Blank lines are formatting, not orchestration complexity. Count
                                // actual source lines so the size gate measures responsibilities
                                // rather than whether a class uses generous spacing.
                                val lineCount = text.lineSequence().count { it.isNotBlank() }
                                if (relative.contains("/mixin/") && lineCount > 150) {
                                    violations += "$relative: mixin has $lineCount lines (maximum 150)"
                                }
                                if (relative.endsWith("/handler/FeatureModuleBase.java") && lineCount > 400) {
                                    violations += "$relative: orchestration base has $lineCount lines (maximum 400)"
                                }
                                if (relative.endsWith("/integration/quickshulker/OrderedStorageController.java")
                                    && lineCount > 400) {
                                    violations += "$relative: ordered-storage orchestrator has $lineCount lines (maximum 400)"
                                }
                                if (relative.endsWith("/utils/InventoryUtils.java") && lineCount > 400) {
                                    violations += "$relative: inventory facade has $lineCount lines (maximum 400)"
                                }
                                if (relative.contains("/handler/handlers/bedrock/")
                                    && listOf(
                                        "BedrockEngine.java",
                                        "BedrockAdmissionController.java",
                                        "BedrockCleanupCoordinator.java",
                                        "BedrockTargetRegistry.java",
                                        "BedrockTargetExecutor.java",
                                        "BedrockTarget.java"
                                    ).any(relative::endsWith)
                                    && lineCount > 400) {
                                    violations += "$relative: bedrock component has $lineCount lines (maximum 400)"
                                }

                                if (relative.contains("/printer/zxy/")
                                    || text.contains(".printer.zxy.")) {
                                    violations += "$relative: legacy zxy production dependency is forbidden"
                                }

                                if (text.contains("PrinterRuntime.get()")) {
                                    violations += "$relative: business code must use an injected runtime or RuntimeAccess bridge"
                                }

                                if (relative.endsWith("MixinConfigBase.java")
                                    || relative.endsWith("MixinIConfigBase.java")) {
                                    violations += "$relative: global MaLiLib config mixins are forbidden"
                                }

                                val tweakerooCompatibilityAdapter = relative.endsWith("/utils/mods/TweakerooUtils.java")
                                if (!tweakerooCompatibilityAdapter && listOf(
                                        "switchToSafeTool",
                                        "getCurrentToolSafeBreakBudget",
                                        "protectCurrentToolBeforeBreak",
                                        "isToolAllowedByDurabilityProtection",
                                        "ITEM_SWAP_DURABILITY_THRESHOLD"
                                    ).any(text::contains)) {
                                    violations += "$relative: local durability policy is forbidden; delegate to Tweakeroo"
                                }

                                if (relative.endsWith("/guide/Guides.java")
                                    && (text.contains("java.lang.reflect") || text.contains("newInstance("))) {
                                    violations += "$relative: guide hot path must use cached factories, not reflection"
                                }

                                if (relative.contains("/handler/handlers/")
                                    && (text.contains("QuickShulker") || text.contains("TakeItOut"))) {
                                    violations += "$relative: feature handler depends on a concrete inventory integration"
                                }

                                if (relative.contains("/handler/handlers/")
                                    && (text.contains("ActionBroker") || text.contains("ActionManager"))) {
                                    violations += "$relative: feature handler must depend on ActionPort, not the queue implementation"
                                }

                                if (relative.contains("/handler/handlers/")
                                    && text.contains("ScanCache")) {
                                    violations += "$relative: feature handler must depend on ScanEngine, not ScanCache internals"
                                }

                                if (relative.contains("/handler/handlers/")
                                    && text.contains("PrinterRuntime.get()")
                                    && !relative.endsWith("/bedrock/BedrockController.java")
                                    && !relative.endsWith("/bedrock/BedrockPlacer.java")) {
                                    violations += "$relative: feature handler must use injected runtime services"
                                }

                                if ((relative.contains("/handler/scan/")
                                        || relative.contains("/printer/action/")
                                        || relative.contains("/integration/"))
                                    && text.contains("ClientPlayerTickManager")) {
                                    violations += "$relative: boundary must use RuntimeScope clock, not legacy tick facade"
                                }

                                if (relative.endsWith("/mixin/printer/litematica/MixinInventoryUtils.java")
                                    && text.contains("getPickBlockTargetSlot")) {
                                    violations += "$relative: must not replace Litematica's global pick-slot policy"
                                }

                                if (relative.contains("/mixin/")
                                    && Regex("priority\\s*=\\s*(?!1000\\b)\\d+").containsMatchIn(text)
                                    && !text.contains("Priority rationale:")) {
                                    violations += "$relative: non-default Mixin priority has no documented rationale"
                                }

                            }
                    }

                    if (violations.isNotEmpty()) {
                        throw org.gradle.api.GradleException(
                            "Architecture verification failed:\n" + violations.sorted().joinToString("\n")
                        )
                    }
                }
            }

        tasks.named("check").configure {
            dependsOn(verification)
        }
    }
}
