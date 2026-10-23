//#if MC >= 12111
package me.aleksilassila.litematica.printer.handler.scan;

import com.sun.management.ThreadMXBean;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Explicit standalone measurement entry point, never executed by the normal test suite. */
public final class SchematicIndexBenchmark {
    private static final java.io.PrintStream OUTPUT = System.out;
    private static volatile long observed;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (args.length == 1 && args[0].equals("verify")) {
            var tests = new SchematicIndexLifecycleTest();
            tests.completeBuildReadsActualSectionPalettesAndStableChecksKeepResults();
            tests.replacingAChunkAtTheSameCoordinatesDropsItsOldContents();
            tests.loadingChunkClearsReadyDataUntilEveryChunkIsFilled();
            tests.anotherWorldWithIdenticalChunkObjectsStillStartsANewSnapshot();
            tests.chunkRemovalAndAdditionCannotKeepPositionsFromThePreviousSnapshot();
            OUTPUT.println("FIXTURE_VERIFIED");
            return;
        }
        String version = args[0];
        String scenario = args[1];
        int fork = Integer.parseInt(args[2]);
        int warmups = Integer.parseInt(args[3]);
        int samples = Integer.parseInt(args[4]);
        int chunks = switch (scenario) {
            case "small" -> 16;
            case "million" -> 256;
            case "many-sparse" -> 4096;
            default -> throw new IllegalArgumentException(scenario);
        };
        int perChunk = scenario.equals("many-sparse") ? 1 : 4096;
        var fixture = new SchematicIndexFixture(chunks, perChunk);
        var otherWorld = SchematicIndexFixture.world(new ConcurrentHashMap<>(fixture.chunks));
        var original = fixture.chunks.get(SchematicIndexFixture.key(0));
        var replacement = SchematicIndexFixture.chunk(0, perChunk);
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation accounting unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (String stage : new String[]{"initial-build", "stable-check", "replace-chunk", "waiting-check", "resume-filled", "world-switch"}) {
            fixture.chunks.put(SchematicIndexFixture.key(0), original);
            original.setState(ChunkSchematicState.FILLED);
            replacement.setState(ChunkSchematicState.FILLED);
            var index = new SchematicBlockIndex();
            index.ensureBuilt(fixture.world);
            if (stage.equals("waiting-check")) {
                original.setState(ChunkSchematicState.LOADED);
                index.ensureBuilt(fixture.world);
            }
            for (int i = -warmups; i < samples; i++) {
                var selectedWorld = fixture.world;
                switch (stage) {
                    case "initial-build" -> index = new SchematicBlockIndex();
                    case "replace-chunk" -> fixture.chunks.compute(SchematicIndexFixture.key(0),
                            (key, value) -> value == original ? replacement : original);
                    case "resume-filled" -> {
                        original.setState(ChunkSchematicState.LOADED);
                        index.ensureBuilt(fixture.world);
                        original.setState(ChunkSchematicState.FILLED);
                    }
                    case "world-switch" -> selectedWorld = ((i + warmups) & 1) == 0 ? otherWorld : fixture.world;
                }
                // Fixture construction, invalidation trigger and result checking are outside timing.
                long allocated = bean.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                boolean changed = index.ensureBuilt(selectedWorld);
                long elapsed = System.nanoTime() - start;
                long bytes = bean.getThreadAllocatedBytes(thread) - allocated;
                boolean expectedChange = !stage.equals("stable-check") && !stage.equals("waiting-check");
                if (changed != expectedChange || index.isReady() == stage.equals("waiting-check")) {
                    throw new AssertionError("Wrong lifecycle transition: " + stage);
                }
                observed += changed ? 1 : 0;
                if (i >= 0) OUTPUT.printf(Locale.ROOT, "SAMPLE,%s,%d,%s,%s,%d,%d,%d,%d,%d%n",
                        version, fork, scenario, stage, chunks, chunks * perChunk, i, elapsed, bytes);
            }
            long count = SchematicIndexFixture.positionCount(index);
            long expected = stage.equals("waiting-check") ? 0 : (long) chunks * perChunk;
            if (count != expected) throw new AssertionError(stage + ": " + count + " != " + expected);
            observed += count;
        }
        OUTPUT.println("MEASUREMENT_VERIFIED," + observed);
    }
}
//#endif
