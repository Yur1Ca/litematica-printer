package me.aleksilassila.litematica.printer.handler.handlers.print;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortedSchematicTargetQueueTest {
    @Test
    void exhaustedCleanSourceDoesNotRefillAfterQueueDrains() {
        assertFalse(SortedSchematicTargetQueue.shouldRefill(
                20L, 19L, false, false, 0, 256, 16));
    }

    @Test
    void liveSourceRefillsBelowLowWater() {
        assertTrue(SortedSchematicTargetQueue.shouldRefill(
                20L, 19L, false, true, 15, 256, 16));
    }

    @Test
    void dirtyRevisionRefillsAnExhaustedSource() {
        assertTrue(SortedSchematicTargetQueue.shouldRefill(
                20L, 19L, true, false, 0, 256, 16));
    }

    @Test
    void requeuedTargetIsDeferredToTheNextIterationPass() {
        SortedSchematicTargetQueue queue = new SortedSchematicTargetQueue(null);
        BlockPos missingTarget = new BlockPos(1, 2, 3);
        queue.requeue(missingTarget);

        var currentPass = queue.iterator();
        assertEquals(missingTarget, currentPass.next());
        queue.requeue(missingTarget);

        assertFalse(currentPass.hasNext());
        assertEquals(missingTarget, queue.iterator().next());
    }

    @Test
    void bufferedCandidatesUseStableRowsAndLayersAcrossRefillBatches() {
        ScanEngine engine = new ScanEngine(() -> null, () -> null, new ScanCache());
        SortedSchematicTargetQueue queue = new SortedSchematicTargetQueue(engine);
        BlockPos firstRow = new BlockPos(0, 10, 0);
        BlockPos secondRowStart = new BlockPos(7, 10, 1);
        BlockPos secondRowEnd = new BlockPos(0, 10, 1);
        BlockPos upperLayer = new BlockPos(0, 11, 0);
        queue.requeue(upperLayer);
        queue.requeue(secondRowEnd);
        queue.requeue(firstRow);
        queue.requeue(secondRowStart);

        List<BlockPos> result = new ArrayList<>();
        queue.forEach(result::add);

        assertEquals(List.of(firstRow, secondRowStart, secondRowEnd, upperLayer), result);
    }

    @Test
    void insertionsCannotReplaceRemovedSnapshotTargetsWithinTheSamePass() {
        ScanEngine engine = new ScanEngine(() -> null, () -> null, new ScanCache());
        SortedSchematicTargetQueue queue = new SortedSchematicTargetQueue(engine);
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(1, 0, 0);
        BlockPos later = new BlockPos(2, 0, 0);
        queue.requeue(first);
        queue.requeue(second);
        var iterator = queue.iterator();
        assertEquals(first, iterator.next());
        queue.remove(second);
        queue.requeue(later);
        assertFalse(iterator.hasNext());
        assertEquals(later, queue.iterator().next());
    }
}
