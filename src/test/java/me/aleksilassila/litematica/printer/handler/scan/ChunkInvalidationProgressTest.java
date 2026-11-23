//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.IntStream;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static me.aleksilassila.litematica.printer.handler.scan.ScanIntegrationFixture.drain;
import static org.junit.jupiter.api.Assertions.*;

class ChunkInvalidationProgressTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void repeatedChunkUpdatesCannotRestartTheFiniteScanPrefix(int changedChunk) throws Exception {
        try (var f = new ScanIntegrationFixture(16)) {
            var boxes = List.of(new PrinterBox(0, 0, 0, 15, 0, 0));
            var seen = new LinkedHashSet<BlockPos>();
            for (int tick = 1; tick <= 32; tick++) {
                f.tick(tick);
                f.cache.invalidateChunk(changedChunk, 0, -64, 319);
                seen.addAll(drain(f.scan("print", boxes, 1, ScanIntent.PRINT)));
            }
            assertEquals(ScanIntegrationFixture.positions(16), List.copyOf(seen));
        }
    }

    @Test void chunkRefreshKeepsBufferedTargetsInTheOtherChunksInterior() throws Exception {
        try (var f = new ScanIntegrationFixture(16)) {
            f.level.allChunksLoaded = true;
            f.chunks.put(1L, f.newChunk(16));
            var boxes = List.of(new PrinterBox(0, 0, 0, 31, 0, 0));
            var queue = new SortedSchematicTargetQueue(f.engine());
            f.tick(10);
            var old = queue.iterable(boxes, f.level, f.schematic, null, 0).iterator();
            assertEquals(32, queue.pendingTargetCount());
            var completed = new BlockPos(20, 0, 0);
            f.level.world.put(completed, Blocks.STONE.defaultBlockState());
            f.cache.invalidateChunk(1, 0, -64, 319);
            assertFalse(old.hasNext());
            f.tick(11);
            var retained = drain(queue.iterable(boxes, f.level, f.schematic, null, 0, false, pos -> true));
            assertEquals(ScanIntegrationFixture.positions(15), retained);
            f.tick(12);
            List<BlockPos> expected = IntStream.rangeClosed(15, 31)
                    .mapToObj(x -> new BlockPos(x, 0, 0)).filter(pos -> !pos.equals(completed)).toList();
            assertEquals(expected, drain(queue.iterable(boxes, f.level, f.schematic, null, 0)));
        }
    }

    @Test void expiredViewCannotStealTheNewIteratorOrAnUnaffectedPrefetch() throws Exception {
        try (var f = new ScanIntegrationFixture(16)) {
            var boxes = List.of(new PrinterBox(0, 0, 0, 15, 0, 0));
            f.tick(1);
            var previous = f.scan("print", boxes, 0, ScanIntent.PRINT);
            var oldIterator = previous.iterator();
            assertEquals(BlockPos.ZERO, oldIterator.next());
            assertTrue(oldIterator.hasNext()); // x=1 is now owned by this iterator.
            f.cache.invalidateChunk(1, 0, -64, 319);
            var current = f.scan("print", boxes, 0, ScanIntent.PRINT).iterator();
            assertFalse(oldIterator.hasNext());
            assertFalse(previous.iterator().hasNext());
            var remaining = new ArrayList<BlockPos>();
            current.forEachRemaining(remaining::add);
            assertEquals(ScanIntegrationFixture.positions(16).subList(1, 16), remaining);
        }
    }

    @Test void repeatedChunkUpdatesPreserveAnAlreadyBudgetedCursorHeadBehindDirtyWork() throws Exception {
        try (var f = new ScanIntegrationFixture(16)) {
            var boxes = List.of(new PrinterBox(0, 0, 0, 15, 0, 0));
            f.tick(1);
            assertEquals(List.of(BlockPos.ZERO), drain(f.scan("print", boxes, 1, ScanIntent.PRINT)));
            f.cache.invalidate(BlockPos.ZERO);
            var field = ScanCache.class.getDeclaredField("budget");
            field.setAccessible(true);
            var budget = (ScanBudget) field.get(f.cache);
            var seen = new LinkedHashSet<BlockPos>();
            for (int tick = 2; tick < 28; tick++) {
                f.tick(tick);
                for (int i = 1; i < Configs.Core.WORK_PER_TICK.getIntegerValue(); i++) budget.tryAdvance();
                f.cache.invalidateChunk(1, 0, -64, 319);
                seen.addAll(drain(f.scan("print", boxes, 1, ScanIntent.PRINT)));
            }
            assertEquals(ScanIntegrationFixture.positions(16), List.copyOf(seen));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void schedulerLookaheadIsRecheckedOnlyWhenItsChunkChanged(boolean changed) throws Exception {
        try (var f = new ScanIntegrationFixture(16)) {
            var boxes = List.of(new PrinterBox(0, 0, 0, 15, 0, 0));
            var queue = new SortedSchematicTargetQueue(f.engine());
            var targets = new PrintTargetScheduler(queue, () -> {});
            f.tick(1);
            queue.iterable(boxes, f.level, f.schematic, null, 8);
            var ready = targets.readyOrdinaryTargets(1);
            assertEquals(BlockPos.ZERO, ready.iterator().next());
            assertEquals(ScanAvailability.READY, ready.availability()); // Holds x=1 outside the queue.
            assertEquals(6, queue.pendingTargetCount());
            if (changed) {
                for (BlockPos pos : ScanIntegrationFixture.positions(2)) {
                    f.level.world.put(pos, f.schematic.getBlockState(pos));
                }
            }
            f.cache.invalidateChunk(changed ? 0 : 1, 0, -64, 319);
            // The scheduler may observe the event before the next call to fill.
            assertFalse(ready.iterator().hasNext());
            f.tick(2);
            queue.iterable(boxes, f.level, f.schematic, null, 8, false, targets::acceptScanCandidate);
            assertEquals(IntStream.range(changed ? 2 : 1, changed ? 10 : 8).mapToObj(x -> new BlockPos(x, 0, 0)).toList(),
                    drain(targets.readyOrdinaryTargets(2)));
        }
    }
}
//#endif
