package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintTargetSchedulerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryRetrySurvivesACompletedScanUntilSuccess(boolean sorted) {
        SortedSchematicTargetQueue freshTargets = new SortedSchematicTargetQueue(null);
        PrintTargetScheduler targets = new PrintTargetScheduler(freshTargets, () -> {});
        BlockPos pos = new BlockPos(1, 2, 3);
        targets.setSortedMode(sorted);

        targets.onResult(pos, PrintPlacementResult.deferred(false), false);
        assertTrue(targets.hasRunnableWork());
        assertEquals(List.of(pos), targets.retainedTargets());
        assertFalse(freshTargets.iterator().hasNext(), "A retry must not occupy the fresh target buffer");
        assertFalse(targets.acceptScanCandidate(pos));

        targets.onResult(pos, new PrintPlacementResult(true, false, PrintPlacementResult.TaskEvent.SUCCESS, -1), false);
        assertFalse(targets.hasRunnableWork());
        assertTrue(targets.acceptScanCandidate(pos));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transientRetriesAreRetainedOnceOutsideTheFreshBuffer(boolean sorted) {
        SortedSchematicTargetQueue freshTargets = new SortedSchematicTargetQueue(null);
        PrintTargetScheduler targets = new PrintTargetScheduler(freshTargets, () -> {});
        BlockPos pos = new BlockPos(1, 2, 3);
        targets.setSortedMode(sorted);

        for (PrintPlacementResult result : List.of(
                PrintPlacementResult.deferred(false),
                PrintPlacementResult.failure(false, false),
                PrintPlacementResult.cancelled(false))) {
            targets.onResult(pos, result, false);
            targets.onResult(pos, result, false);

            assertEquals(List.of(pos), targets.retainedTargets(), result.taskEvent().name());
            assertFalse(freshTargets.iterator().hasNext(), "Deferred work must leave capacity for new targets");
            assertFalse(targets.acceptScanCandidate(pos));

            targets.removeRetry(pos);
            assertTrue(targets.retainedTargets().isEmpty());
            assertTrue(targets.acceptScanCandidate(pos));
            assertFalse(targets.hasRunnableWork());
        }
    }

    @Test
    void sortedModeChangeAndMaterialRefreshDiscardOldCandidates() {
        AtomicInteger sourceResets = new AtomicInteger();
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), sourceResets::incrementAndGet);
        BlockPos pos = new BlockPos(4, 5, 6);
        targets.setSortedMode(false);
        targets.onResult(pos, PrintPlacementResult.failure(false, false), false);

        assertTrue(targets.setSortedMode(true));
        assertFalse(targets.hasRunnableWork());
        assertEquals(1, sourceResets.get());

        targets.onResult(pos, PrintPlacementResult.failure(false, false), false);
        assertTrue(targets.hasRunnableWork());
        targets.invalidateCandidates();
        assertFalse(targets.hasRunnableWork());
        assertEquals(2, sourceResets.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryOutsideCurrentScanScopeIsDiscarded(boolean sorted) {
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        BlockPos pos = new BlockPos(20, 2, 3);
        targets.setSortedMode(sorted);
        targets.onResult(pos, PrintPlacementResult.deferred(false), false);

        targets.retainWithin(List.of(new PrinterBox(0, 0, 0, 5, 5, 5)), posInRange -> true);

        assertFalse(targets.hasRunnableWork());
        assertTrue(targets.acceptScanCandidate(pos));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryRejectedByCurrentReachIsDiscardedWithoutRemovingInScopeWork(boolean sorted) {
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        BlockPos reachable = new BlockPos(1, 2, 3);
        BlockPos unreachable = new BlockPos(4, 2, 3);
        targets.setSortedMode(sorted);
        targets.onResult(reachable, PrintPlacementResult.deferred(false), false);
        targets.onResult(unreachable, PrintPlacementResult.deferred(false), false);

        targets.retainWithin(List.of(new PrinterBox(0, 0, 0, 5, 5, 5)), reachable::equals);

        assertEquals(List.of(reachable), targets.retainedTargets());
        assertFalse(targets.acceptScanCandidate(reachable));
        assertTrue(targets.acceptScanCandidate(unreachable));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void materialUnavailableStopsAnEarlierTransientRetry(boolean sorted) {
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        BlockPos pos = new BlockPos(1, 2, 3);
        targets.setSortedMode(sorted);
        targets.onResult(pos, PrintPlacementResult.deferred(false), false);

        targets.onResult(pos, PrintPlacementResult.materialUnavailable(false), false);

        assertFalse(targets.hasRunnableWork(), "Missing material must wait for an inventory wake-up");
        assertTrue(targets.retainedTargets().isEmpty());
        assertTrue(targets.acceptScanCandidate(pos));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void worldBlockedStopsAnEarlierTransientRetry(boolean sorted) {
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        BlockPos pos = new BlockPos(1, 2, 3);
        targets.setSortedMode(sorted);
        targets.onResult(pos, PrintPlacementResult.deferred(false), false);

        targets.onResult(pos, PrintPlacementResult.worldBlocked(), false);

        assertFalse(targets.hasRunnableWork(), "An unsupported falling target must wait for a world update");
        assertTrue(targets.retainedTargets().isEmpty());
        assertTrue(targets.acceptScanCandidate(pos));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void submittedPlacementStopsAnEarlierTransientRetry(boolean sorted) {
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        BlockPos pos = new BlockPos(1, 2, 3);
        targets.setSortedMode(sorted);
        targets.onResult(pos, PrintPlacementResult.deferred(false), false);

        targets.onResult(pos, new PrintPlacementResult(
                true, false, PrintPlacementResult.TaskEvent.QUEUED, 8), false);

        assertFalse(targets.hasRunnableWork(), "A submitted placement must wait for its existing confirmation path");
        assertTrue(targets.retainedTargets().isEmpty());
        assertTrue(targets.acceptScanCandidate(pos));
    }
}
