//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.scan.ScanIntegrationFixture.drain;
import static org.junit.jupiter.api.Assertions.*;

class SortedSchematicTargetQueueIntegrationTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 255, 256, 257, 300, 512, 513})
    void fullBuffersPreserveEveryCandidateAndEachBatchOrder(int count) throws Exception {
        try (var fixture = new ScanIntegrationFixture(count)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            List<BlockPos> source = orderedSource(count);
            List<BlockPos> all = new ArrayList<>();
            int consumed = 0;
            long tick = 10;
            do {
                fixture.tick(tick++);
                List<BlockPos> actual = drain(queue.iterable(
                        fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0));
                int end = Math.min(count, consumed + 256);
                assertEquals(sortedBatch(source.subList(consumed, end)), actual,
                        "The boundary candidate must remain in the next batch, with the entire batch ordered correctly");
                assertEquals(actual.size(), fixture.cache.metricsFor("print").acceptedTargets(),
                        "Checking whether the buffer is full must not prefetch or consume another source candidate");
                all.addAll(actual);
                consumed = end;
                assertTrue(tick < 20, "The source must eventually report completion");
            } while (consumed < count || queue.hasPendingWork());
            assertEquals(count, all.size());
            assertEquals(new HashSet<>(source), new HashSet<>(all));
            assertEquals(all.size(), new HashSet<>(all).size(), "Clean batches must not duplicate candidates");
            assertEquals(ScanAvailability.COMPLETE, queue.availability());

            fixture.tick(tick);
            assertTrue(drain(queue.iterable(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)).isEmpty());
            assertEquals(0, fixture.cache.metricsFor("print").acceptedTargets(),
                    "A clean completed queue must not restart the scan");
        }
    }

    @Test void boundedPausedSourceContinuesAcrossTicksWithoutMissingOrRepeatingCandidates() throws Exception {
        try (var fixture = new ScanIntegrationFixture(30)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            List<BlockPos> source = orderedSource(30);
            List<BlockPos> all = new ArrayList<>();
            for (int offset = 0; offset < 30; offset += 7) {
                fixture.tick(10 + offset);
                List<BlockPos> actual = drain(queue.iterable(
                        fixture.boxes, fixture.level, fixture.schematic, fixture.player, 7));
                assertEquals(sortedBatch(source.subList(offset, Math.min(30, offset + 7))), actual);
                all.addAll(actual);
                if (offset + 7 < 30) assertEquals(ScanAvailability.PAUSED, queue.availability());
            }
            assertEquals(30, new HashSet<>(all).size());
            fixture.tick(50);
            assertTrue(drain(queue.iterable(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 7)).isEmpty());
            assertEquals(ScanAvailability.COMPLETE, queue.availability());
        }
    }

    @Test void duplicateRetryIsRetainedOnceAlongsideTheNextUnconsumedBatch() throws Exception {
        try (var fixture = new ScanIntegrationFixture(300)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            fixture.tick(10);
            List<BlockPos> first = drain(queue.iterable(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0));
            BlockPos retry = first.getFirst();
            queue.requeue(retry);
            queue.requeue(retry);
            queue.requeue(null);
            fixture.tick(11);
            List<BlockPos> expected = new ArrayList<>(List.of(retry));
            expected.addAll(sortedBatch(orderedSource(300).subList(256, 300)));
            assertEquals(expected, drain(queue.iterable(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)));
        }
    }

    @Test void slowConsumptionAcrossLowWaterRefillsPreservesEveryCandidate() throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            List<BlockPos> actual = new ArrayList<>();
            long tick = 10;
            do {
                fixture.tick(tick++);
                var iterator = queue.iterable(
                        fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0).iterator();
                if (iterator.hasNext()) actual.add(iterator.next());
                assertTrue(tick < 600, "Low-water refills must eventually complete");
            } while (queue.hasPendingWork());
            assertEquals(513, actual.size());
            assertEquals(new HashSet<>(orderedSource(513)), new HashSet<>(actual));
        }
    }

    @Test void observingAFullBufferDoesNotConsumeTheSourceAgainWithinOrAcrossTicks() throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            fixture.tick(10);
            queue.iterable(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);
            assertEquals(256, fixture.cache.metricsFor("print").acceptedTargets());
            for (int i = 0; i < 10; i++) {
                assertTrue(queue.iterable(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)
                        .iterator().hasNext());
            }
            assertEquals(256, fixture.cache.metricsFor("print").acceptedTargets());
            fixture.tick(11);
            queue.iterable(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);
            assertEquals(0, fixture.cache.metricsFor("print").acceptedTargets());
            assertEquals(sortedBatch(orderedSource(513).subList(0, 256)), drain(queue));
        }
    }

    private static List<BlockPos> orderedSource(int count) {
        List<BlockPos> result = ScanIntegrationFixture.positions(count);
        result.sort(Comparator.comparingLong((BlockPos pos) ->
                        (long) pos.getX() * pos.getX() + (long) pos.getY() * pos.getY() + (long) pos.getZ() * pos.getZ())
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return result;
    }

    private static List<BlockPos> sortedBatch(List<BlockPos> source) {
        // All targets are ordinary stone/cobblestone and the held item is air. The eye is at zero
        // and looks along +Z, so the public ordering reduces to these independent geometric keys.
        List<BlockPos> expected = new ArrayList<>(source);
        expected.sort(Comparator.comparingDouble(SortedSchematicTargetQueueIntegrationTest::eyeDistance)
                .thenComparingDouble(pos -> -(pos.getZ() + .5) / Math.sqrt(eyeDistance(pos)))
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return expected;
    }

    private static double eyeDistance(BlockPos pos) {
        double x = pos.getX() + .5, y = pos.getY() + .5, z = pos.getZ() + .5;
        return x * x + y * y + z * z;
    }
}
//#endif
