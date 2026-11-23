package me.aleksilassila.litematica.printer.render;

import me.aleksilassila.litematica.printer.enums.HighlightType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OperationHighlightsTest {
    @Test void clearingEitherLayerPreservesTheOtherAndMovingThePreviewLeavesNoTrail() {
        var trace = new OperationHighlights(32, () -> 0L);
        var first = new OperationHighlights.Preview(BlockPos.ZERO, true);
        var second = new OperationHighlights.Preview(BlockPos.ZERO.above(), true);
        trace.record(first.pos(), HighlightType.BREAK);
        trace.setPreviews(java.util.List.of(first));
        trace.setPreviews(java.util.List.of(second));
        trace.clearHistory();
        assertEquals(java.util.List.of(second), trace.previews());
        assertTrue(trace.snapshot(500).isEmpty());
        trace.record(second.pos(), HighlightType.BREAK);
        trace.setPreviews(java.util.List.of());
        assertTrue(trace.previews().isEmpty());
        assertEquals(second.pos(), trace.snapshot(500).getFirst().pos());
    }

    @Test void previewSnapshotsAreBoundedImmutableAndResetWithTheWorldEpoch() {
        var trace = new OperationHighlights(4, () -> 0L);
        var mutable = new BlockPos.MutableBlockPos(1, 2, 3);
        trace.setPreviews(java.util.stream.IntStream.range(0, 32)
                .mapToObj(i -> new OperationHighlights.Preview(mutable, true)).toList());
        mutable.set(8, 9, 10);
        var previews = trace.previews();
        assertEquals(8, previews.size());
        assertEquals(new BlockPos(1, 2, 3), previews.getFirst().pos());
        assertThrows(UnsupportedOperationException.class, previews::clear);
        var epoch = me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch.INITIAL;
        trace.onEpochChanged(new me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent.EpochChanged(epoch, epoch.next(), "test"));
        assertTrue(trace.previews().isEmpty());
    }
    @Test void highThroughputKeepsOnlyTheLatestBoundedHistory() {
        var clock = new AtomicLong();
        var trace = new OperationHighlights(32, clock::get);
        for (int i = 0; i < 20_000; i++) {
            clock.incrementAndGet();
            trace.record(new BlockPos(i, 0, 0), HighlightType.PLACE);
        }
        var entries = trace.snapshot(100_000);
        assertEquals(32, entries.size());
        assertEquals(19_968, entries.getFirst().pos().getX());
        assertEquals(19_999, entries.getLast().pos().getX());
    }

    @Test void revisitingAPositionReplacesItsTypeAndFadeOrigin() {
        var clock = new AtomicLong(100);
        var trace = new OperationHighlights(4, clock::get);
        trace.record(BlockPos.ZERO, HighlightType.PLACE);
        clock.set(200);
        trace.record(BlockPos.ZERO, HighlightType.ADJUST);
        var entries = trace.snapshot(150);
        assertEquals(1, entries.size());
        assertEquals(HighlightType.ADJUST, entries.getFirst().type());
        assertEquals(200, entries.getFirst().time());
        clock.set(350);
        assertTrue(trace.snapshot(150).isEmpty());
    }

    @Test void publishedPositionsAndSnapshotsDoNotFollowMutableInputs() {
        var trace = new OperationHighlights(4, () -> 0L);
        var pos = new BlockPos.MutableBlockPos(1, 2, 3);
        trace.record(pos, HighlightType.BREAK);
        var entries = trace.snapshot(100);
        pos.set(8, 9, 10);
        assertEquals(new BlockPos(1, 2, 3), entries.getFirst().pos());
        assertThrows(UnsupportedOperationException.class, entries::clear);
        trace.clear();
        assertTrue(trace.snapshot(100).isEmpty());
        assertEquals(1, entries.size());
    }

    @Test void zeroFadeAndResetLeaveNoHistoricalEntries() {
        var trace = new OperationHighlights(4, () -> 0L);
        trace.record(BlockPos.ZERO, HighlightType.FAILED);
        assertTrue(trace.snapshot(0).isEmpty());
        trace.record(BlockPos.ZERO, HighlightType.PLACE);
        trace.clear();
        assertTrue(trace.snapshot(100).isEmpty());
    }
}
