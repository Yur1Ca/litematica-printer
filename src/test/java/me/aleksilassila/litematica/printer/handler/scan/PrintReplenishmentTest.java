//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static me.aleksilassila.litematica.printer.handler.scan.ScanIntegrationFixture.drain;
import static org.junit.jupiter.api.Assertions.*;

class PrintReplenishmentTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void obtainingAWarmQueueDoesNotScanBeforeItsExistingTargetsExecute() throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var targets = scheduler(queue);
            fixture.tick(10);
            var first = targets.sortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0).iterator();
            assertEquals(256, fixture.cache.metricsFor("print").acceptedTargets());
            for (int index = 0; index < 241; index++) first.next();

            fixture.tick(11);
            Iterable<BlockPos> existing = targets.sortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);

            assertEquals(0, fixture.cache.metricsFor("print").acceptedTargets(),
                    "A warm low-water queue must execute its old targets before doing more source lookup");
            assertEquals(15, drain(existing).size(), "Obtaining an iteration must preserve the unconsumed old targets");
        }
    }

    @Test void aFullDeferredBatchDoesNotBlockDiscoveryOfTheNextSourceTargets() throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var targets = scheduler(queue);
            fixture.tick(10);
            List<BlockPos> first = drain(targets.sortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0));
            assertEquals(256, first.size());
            for (BlockPos pos : first) targets.onResult(pos, PrintPlacementResult.deferred(false), false);

            fixture.tick(11);
            List<BlockPos> next = drain(targets.sortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0));

            assertEquals(256, fixture.cache.metricsFor("print").acceptedTargets(),
                    "Retry ownership must not keep the fresh buffer full and freeze the source cursor");
            assertEquals(256, next.size());
            assertTrue(next.stream().noneMatch(new HashSet<>(first)::contains));
            assertEquals(first, targets.retainedTargets(), "Waiting targets remain retained while discovery continues");
        }
    }

    @Test void executionCanReplenishBeforeTheOldBatchHasDrained() throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var targets = scheduler(queue);
            List<BlockPos> consumed = new ArrayList<>();
            fixture.tick(10);
            var initial = targets.sortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0).iterator();
            for (int index = 0; index < 242; index++) consumed.add(initial.next());

            fixture.tick(11);
            var existing = targets.sortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0).iterator();
            consumed.add(existing.next());
            assertEquals(0, fixture.cache.metricsFor("print").acceptedTargets());
            targets.replenishSortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);

            assertEquals(16, fixture.cache.metricsFor("print").acceptedTargets(),
                    "Post-execution refill must advance the source while thirteen old targets remain");
            List<BlockPos> remaining = drain(queue);
            assertEquals(29, remaining.size(), "Keep the thirteen old targets alongside sixteen new targets");
            consumed.addAll(remaining);
            assertEquals(272, new HashSet<>(consumed).size(), "Refill must not duplicate an already consumed target");
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 16", "10, 20", "0, 256"})
    void warmReplenishmentUsesABoundedSlice(int throughput, int expectedNewTargets) throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(throughput);
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var targets = scheduler(queue);
            fixture.tick(10);
            targets.sortedPositions(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 8);
            assertEquals(8, fixture.cache.metricsFor("print").acceptedTargets());

            fixture.tick(11);
            targets.sortedPositions(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)
                    .iterator().next();
            targets.replenishSortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);

            assertEquals(expectedNewTargets, fixture.cache.metricsFor("print").acceptedTargets(),
                    "A warm refill must not turn spare buffer capacity into another full collection pass");
            assertEquals(7 + expectedNewTargets, drain(queue).size());
        }
    }

    @Test void replenishingTwiceInOneTickPreservesTheNextSourceBoundary() throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var targets = scheduler(queue);
            List<BlockPos> all = new ArrayList<>();
            fixture.tick(10);
            all.add(targets.sortedPositions(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 7)
                    .iterator().next());
            targets.replenishSortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);
            assertEquals(7, fixture.cache.metricsFor("print").acceptedTargets(),
                    "Bootstrap and a later stop hook share one tick's source production allowance");

            fixture.tick(11);
            all.add(targets.sortedPositions(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)
                    .iterator().next());
            targets.replenishSortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);
            assertEquals(16, fixture.cache.metricsFor("print").acceptedTargets());
            targets.replenishSortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);
            assertEquals(16, fixture.cache.metricsFor("print").acceptedTargets(),
                    "A second stop hook must not probe or consume the following source candidate");
            all.addAll(drain(queue));

            long tick = 12;
            while (queue.hasPendingWork()) {
                fixture.tick(tick++);
                all.addAll(drain(targets.sortedPositions(
                        fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)));
                targets.replenishSortedPositions(
                        fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);
                assertTrue(tick < 30, "The remaining source must eventually finish");
            }
            assertEquals(513, all.size());
            assertEquals(513, new HashSet<>(all).size(), "Every source candidate must be emitted exactly once");
            assertEquals(new HashSet<>(ScanIntegrationFixture.positions(513)), new HashSet<>(all));
            assertEquals(ScanAvailability.COMPLETE, queue.availability());
        }
    }

    @Test void dirtyUpdatesCannotCopyARetainedRetryBackIntoTheFreshBuffer() throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var targets = scheduler(queue);
            fixture.tick(10);
            BlockPos retry = targets.sortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 8).iterator().next();
            targets.onResult(retry, PrintPlacementResult.deferred(false), false);

            fixture.tick(11);
            fixture.cache.invalidate(retry);
            targets.sortedPositions(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);
            assertEquals(0, fixture.cache.metricsFor("print").acceptedTargets());
            targets.replenishSortedPositions(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);

            List<BlockPos> fresh = drain(queue);
            assertEquals(23, fresh.size(), "Keep seven old targets and use all sixteen new-target slots");
            assertFalse(fresh.contains(retry), "Dirty rescans must respect the scheduler's retained-target ownership");
            assertEquals(List.of(retry), targets.retainedTargets());
            assertFalse(targets.acceptScanCandidate(retry));
        }
    }

    private static PrintTargetScheduler scheduler(SortedSchematicTargetQueue queue) {
        var targets = new PrintTargetScheduler(queue, () -> {});
        targets.setSortedMode(true);
        return targets;
    }
}
//#endif
