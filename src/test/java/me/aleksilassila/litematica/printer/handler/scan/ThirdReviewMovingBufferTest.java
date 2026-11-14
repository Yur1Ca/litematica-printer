//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static me.aleksilassila.litematica.printer.handler.scan.RawPrinterBoxFixture.rawBox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdReviewMovingBufferTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSmallScopeChangeKeepsEarlierPrefetchedTargetsAndInvalidatesTheOldView(boolean shrink) throws Exception {
        try (var fixture = new ScanIntegrationFixture(16)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            fixture.tick(1);
            var original = (ScanCandidateIterable) queue.iterable(
                    List.of(rawBox(0, 0, 0, 15, 0, 0)), fixture.level, fixture.schematic,
                    fixture.player, 8, false, pos -> true);
            assertEquals(8, fixture.cache.metricsFor("print").acceptedTargets(),
                    "The first source slice must already have prefetched the complete first row");
            var oldIterator = original.iterator();
            assertTrue(oldIterator.hasNext());
            assertEquals(BlockPos.ZERO, oldIterator.next());

            fixture.tick(2);
            var moved = (ScanCandidateIterable) queue.iterable(
                    List.of(shrink ? rawBox(1, 0, 0, 15, 0, 0) : rawBox(0, 0, 0, 16, 0, 0)),
                    fixture.level, fixture.schematic, fixture.player, 8, false, pos -> true);

            assertFalse(original.isValid(), "A changed scope must invalidate an already-created buffer view");
            assertFalse(oldIterator.hasNext(), "The old iterator must not consume entries from the new view");
            assertEquals(List.of(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0),
                            new BlockPos(4, 0, 0), new BlockPos(5, 0, 0), new BlockPos(6, 0, 0), new BlockPos(7, 0, 0)),
                    ScanIntegrationFixture.drain(moved),
                    "Still-in-scope prefetched coordinates must remain ahead of the cursor's next row starting at X=8");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aScopeChangeRetainsTheSchedulersUnconsumedWarmLookahead(boolean shrink) throws Exception {
        try (var fixture = new ScanIntegrationFixture(16)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var targets = new PrintTargetScheduler(queue, () -> {});
            targets.setSortedMode(true);
            fixture.tick(1);
            queue.iterable(List.of(rawBox(0, 0, 0, 15, 0, 0)), fixture.level,
                    fixture.schematic, fixture.player, 8, false, targets::acceptScanCandidate);
            assertEquals(8, queue.pendingTargetCount());

            var ready = targets.readyOrdinaryTargets(1L);
            assertEquals(ScanAvailability.READY, ready.availability());
            assertEquals(BlockPos.ZERO, ready.iterator().next());
            targets.onResult(BlockPos.ZERO,
                    new PrintPlacementResult(true, false, PrintPlacementResult.TaskEvent.SUCCESS, -1), false);
            // The runner asks availability again after its effective-action limit. This owns X=1
            // in the scheduler, even though the sorted queue has already removed that offer.
            assertEquals(ScanAvailability.READY, ready.availability());
            assertEquals(6, queue.pendingTargetCount());
            assertEquals(7, targets.pendingTargetCount());

            fixture.tick(2);
            var movedScope = List.of(shrink ? rawBox(1, 0, 0, 15, 0, 0) : rawBox(0, 0, 0, 16, 0, 0));
            targets.retainWithin(movedScope, pos -> true);
            queue.iterable(movedScope, fixture.level, fixture.schematic, fixture.player, 8, false,
                    targets::acceptScanCandidate);

            assertEquals(IntStream.range(1, 8).mapToObj(x -> new BlockPos(x, 0, 0)).toList(),
                    ScanIntegrationFixture.drain(targets.readyOrdinaryTargets(2L)),
                    "The still-in-scope scheduler lookahead must precede the retained buffer after movement");
        }
    }

    @Test void aScopeChangeDiscardsTheSchedulersNowOutOfScopeWarmLookahead() throws Exception {
        try (var fixture = new ScanIntegrationFixture(16)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var targets = new PrintTargetScheduler(queue, () -> {});
            targets.setSortedMode(true);
            fixture.tick(1);
            queue.iterable(List.of(rawBox(0, 0, 0, 15, 0, 0)), fixture.level,
                    fixture.schematic, fixture.player, 8, false, targets::acceptScanCandidate);
            var ready = targets.readyOrdinaryTargets(1L);
            assertEquals(BlockPos.ZERO, ready.iterator().next());
            assertEquals(ScanAvailability.READY, ready.availability());
            assertEquals(6, queue.pendingTargetCount());

            fixture.tick(2);
            var movedScope = List.of(rawBox(2, 0, 0, 15, 0, 0));
            targets.retainWithin(movedScope, pos -> true);
            queue.iterable(movedScope, fixture.level, fixture.schematic, fixture.player, 8, false,
                    targets::acceptScanCandidate);

            assertEquals(IntStream.range(2, 8).mapToObj(x -> new BlockPos(x, 0, 0)).toList(),
                    ScanIntegrationFixture.drain(targets.readyOrdinaryTargets(2L)),
                    "Retaining a lookahead must still respect the latest source scope");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "epoch", "clear", "mode"})
    void aReplacedSourceDoesNotResurrectTheSchedulersWarmLookahead(String replacement) throws Exception {
        try (var fixture = new ScanIntegrationFixture(16)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var targets = new PrintTargetScheduler(queue, () -> {});
            targets.setSortedMode(true);
            var scope = List.of(rawBox(0, 0, 0, 15, 0, 0));
            targets.retainWithin(scope, pos -> true);
            fixture.tick(1);
            queue.iterable(scope, fixture.level, fixture.schematic, fixture.player, 8, false,
                    targets::acceptScanCandidate);
            var ready = targets.readyOrdinaryTargets(1L);
            assertEquals(BlockPos.ZERO, ready.iterator().next());
            assertEquals(ScanAvailability.READY, ready.availability());
            assertEquals(6, queue.pendingTargetCount());

            switch (replacement) {
                case "source" -> {
                    fixture.replaceTarget(Blocks.AIR.defaultBlockState());
                    fixture.tick(2);
                    queue.iterable(scope, fixture.level, fixture.schematic, fixture.player, 8, false,
                            targets::acceptScanCandidate);
                }
                case "epoch" -> {
                    for (BlockPos pos : ScanIntegrationFixture.positions(16)) {
                        fixture.level.world.put(pos, fixture.schematic.getBlockState(pos));
                    }
                    fixture.epoch = fixture.epoch.next();
                    fixture.tick(2);
                    queue.iterable(scope, fixture.level, fixture.schematic, fixture.player, 8, false,
                            targets::acceptScanCandidate);
                }
                case "clear" -> targets.clearSorted();
                case "mode" -> assertTrue(targets.setSortedMode(false));
                default -> throw new AssertionError(replacement);
            }

            assertEquals(List.of(), ScanIntegrationFixture.drain(targets.readyOrdinaryTargets(2L)),
                    "A source, epoch or explicit clear must discard the old held coordinate instead of reoffering it");
            assertEquals(0, queue.pendingTargetCount());
            assertEquals(0, targets.pendingTargetCount());
        }
    }

    @Test void aNewEarlierDirtySourceOfferStillPrecedesTheRetainedBuffer() throws Exception {
        try (var fixture = new ScanIntegrationFixture(16)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            var scope = List.of(rawBox(0, 0, 0, 15, 0, 0));
            fixture.tick(1);
            var initial = queue.iterable(scope, fixture.level, fixture.schematic, fixture.player, 8).iterator();
            assertEquals(BlockPos.ZERO, initial.next());

            fixture.cache.invalidate(BlockPos.ZERO);
            fixture.tick(2);
            var repaired = queue.iterable(scope, fixture.level, fixture.schematic, fixture.player, 8).iterator();
            assertEquals(BlockPos.ZERO, repaired.next(), "An earlier dirty offer must not remain behind the old buffer tail");
            assertEquals(new BlockPos(1, 0, 0), repaired.next());
        }
    }

    @Test void aWarm4096TargetBufferDoesNotSortAgainForEveryShortIteration() {
        var timing = new RuntimeTiming(true, new AtomicLong()::incrementAndGet);
        var queue = timedQueue(timing);
        for (int x = 0; x < 4096; x++) queue.requeue(new BlockPos(x, 0, 0));
        List<BlockPos> actual = new ArrayList<>();
        for (int slice = 0; slice < 32; slice++) actual.add(queue.iterator().next());

        assertEquals(IntStream.range(0, 32).mapToObj(x -> new BlockPos(x, 0, 0)).toList(), actual);
        assertEquals(0, sortCalls(timing),
                "Already-monotone retained work must not perform a full-buffer sort for every one-target slice");
    }

    @Test void anInverseInsertionSortsOnceWithoutJoiningAnAlreadyCreatedIterator() {
        var timing = new RuntimeTiming(true, new AtomicLong()::incrementAndGet);
        var queue = timedQueue(timing);
        queue.requeue(new BlockPos(1, 0, 0));
        queue.requeue(new BlockPos(2, 0, 0));
        var oldIterator = queue.iterator();
        assertEquals(new BlockPos(1, 0, 0), oldIterator.next());
        queue.requeue(BlockPos.ZERO);

        var nextIterator = queue.iterator();
        assertEquals(BlockPos.ZERO, nextIterator.next());
        assertEquals(new BlockPos(2, 0, 0), oldIterator.next(),
                "Reordering live storage must not add the new coordinate to an older finite snapshot");
        assertFalse(nextIterator.hasNext());
        assertFalse(queue.iterator().hasNext());
        assertEquals(1, sortCalls(timing), "One inverse insertion requires one repair sort, not a sort per iterator");
    }

    private static SortedSchematicTargetQueue timedQueue(RuntimeTiming timing) {
        return new SortedSchematicTargetQueue(new ScanEngine(() -> RuntimeEpoch.INITIAL, () -> null, new ScanCache(timing)));
    }

    private static long sortCalls(RuntimeTiming timing) {
        return timing.snapshot().samples().stream().filter(sample -> sample.stage() == RuntimeTiming.Stage.PRINT_SORT)
                .mapToLong(RuntimeTiming.Sample::calls).sum();
    }
}
//#endif
