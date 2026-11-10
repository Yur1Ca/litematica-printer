package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintTargetSchedulerTest {
    @Test
    void ordinaryRetrySurvivesACompletedScanUntilSuccess() {
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        BlockPos pos = new BlockPos(1, 2, 3);
        targets.setSortedMode(false);

        targets.onResult(pos, PrintPlacementResult.deferred(false), false);
        assertTrue(targets.hasRunnableWork());
        assertEquals(pos, targets.retainedTargets().getFirst());
        assertFalse(targets.acceptScanCandidate(pos));

        targets.onResult(pos, new PrintPlacementResult(true, false, PrintPlacementResult.TaskEvent.SUCCESS, -1), false);
        assertFalse(targets.hasRunnableWork());
        assertTrue(targets.acceptScanCandidate(pos));
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
        assertTrue(targets.hasPendingWork());
        targets.invalidateCandidates();
        assertFalse(targets.hasPendingWork());
        assertEquals(2, sourceResets.get());
    }

    @Test
    void retryOutsideCurrentScanScopeIsDiscarded() {
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        BlockPos pos = new BlockPos(20, 2, 3);
        targets.setSortedMode(false);
        targets.onResult(pos, PrintPlacementResult.deferred(false), false);

        targets.retainWithin(List.of(new PrinterBox(0, 0, 0, 5, 5, 5)), posInRange -> true);

        assertFalse(targets.hasRunnableWork());
        assertTrue(targets.acceptScanCandidate(pos));
    }

    @Test
    void materialUnavailableStopsAnEarlierTransientRetry() {
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        BlockPos pos = new BlockPos(1, 2, 3);
        targets.setSortedMode(false);
        targets.onResult(pos, PrintPlacementResult.deferred(false), false);

        targets.onResult(pos, PrintPlacementResult.materialUnavailable(false), false);

        assertFalse(targets.hasRunnableWork(), "Missing material must wait for an inventory wake-up");
        assertTrue(targets.retainedTargets().isEmpty());
        assertTrue(targets.acceptScanCandidate(pos));
    }

    @Test
    void worldBlockedStopsAnEarlierTransientRetry() {
        PrintTargetScheduler targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(null), () -> {});
        BlockPos pos = new BlockPos(1, 2, 3);
        targets.setSortedMode(false);
        targets.onResult(pos, PrintPlacementResult.deferred(false), false);

        targets.onResult(pos, PrintPlacementResult.worldBlocked(), false);

        assertFalse(targets.hasRunnableWork(), "An unsupported falling target must wait for a world update");
        assertTrue(targets.retainedTargets().isEmpty());
        assertTrue(targets.acceptScanCandidate(pos));
    }
}
