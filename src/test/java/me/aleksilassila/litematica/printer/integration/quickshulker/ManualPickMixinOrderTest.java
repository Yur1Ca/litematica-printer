package me.aleksilassila.litematica.printer.integration.quickshulker;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.util.CheckClassAdapter;
import org.spongepowered.asm.launch.MixinBootstrap;

import static org.junit.jupiter.api.Assertions.*;

/** Runs real Mixin transformation in a separate JVM, using the production hook's ordering metadata. */
class ManualPickMixinOrderTest {
    @TempDir static Path directory;
    private static String classpath;

    @BeforeAll static void compileProbe() throws Exception {
        var loader = ManualPickMixinOrderTest.class.getClassLoader();
        var mixin = new ClassNode();
        try (var input = loader.getResourceAsStream("me/aleksilassila/litematica/printer/mixin/printer/litematica/MixinInventoryUtils.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(mixin, 0);
        }
        var annotation = mixin.invisibleAnnotations.stream().filter(a -> a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
        int priority = annotation.values.contains("priority")
                ? (int) annotation.values.get(annotation.values.indexOf("priority") + 1) : 1000;
        var hook = mixin.methods.stream().filter(m -> m.name.equals("easyPlace")).findFirst().orElseThrow();
        var inject = hook.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        int order = inject.values.contains("order") ? (int) inject.values.get(inject.values.indexOf("order") + 1) : 1000;
        var paths = new LinkedHashSet<String>();
        paths.add(directory.toString());
        for (Class<?> type : List.of(MixinBootstrap.class, ClassReader.class, ClassNode.class,
                Analyzer.class, Remapper.class, CheckClassAdapter.class, Gson.class)) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        classpath = String.join(java.io.File.pathSeparator, paths);
        var options = new ArrayList<>(List.of("-proc:none", "--release", "25", "-cp", classpath, "-d", directory.toString()));
        for (String file : List.of("probe/Service.java", "probe/Properties.java", "probe/Main.java",
                "probe/mixins/Printer.java", "probe/mixins/TakeItOut.java", "game/Target.java", "probe.mixins.json",
                "META-INF/services/org.spongepowered.asm.service.IMixinService",
                "META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService")) {
            try (var input = loader.getResourceAsStream("fixtures/manual-pick-mixin-order/" + file)) {
                assertNotNull(input, file);
                Path target = directory.resolve(file);
                Files.createDirectories(target.getParent());
                String source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("PRINTER_PRIORITY", Integer.toString(priority)).replace("PRINTER_ORDER", Integer.toString(order));
                Files.writeString(target, source);
                if (file.endsWith(".java")) options.add(target.toString());
            }
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new)));
    }

    @Test void pendingManualRestockMustRunBeforeForeignMissingItemCancellation() throws Exception {
        assertTrue(runProbe(true, true).contains("RESULT=1"),
                "The foreign missing-item hook must not return before Printer can request warehouse material");
    }

    @Test void unavailableMaterialStillAllowsTheForeignFallback() throws Exception {
        assertTrue(runProbe(false, true).contains("RESULT=2"));
    }

    @Test void availableMaterialStillReachesNativePlacement() throws Exception {
        assertTrue(runProbe(false, false).contains("RESULT=0"));
    }

    private static String runProbe(boolean printerCancels, boolean foreignCancels) throws Exception {
        Path output = directory.resolve("result-" + printerCancels + "-" + foreignCancels + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dprobe.printerCancels=" + printerCancels, "-Dprobe.foreignCancels=" + foreignCancels,
                "-cp", classpath, "probe.Main").redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Mixin order probe timed out");
        }
        String text = Files.readString(output);
        assertEquals(0, process.exitValue(), text);
        return text;
    }
}
