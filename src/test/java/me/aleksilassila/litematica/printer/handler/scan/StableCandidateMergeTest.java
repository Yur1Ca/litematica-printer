package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StableCandidateMergeTest {
    private static final Comparator<BlockPos> ORDER = Comparator.comparingInt(BlockPos::getX);
    private static BlockPos pos(int x) { return new BlockPos(x, 0, 0); }

    @Test void ordinaryRetriesInterleaveWithFreshCandidatesAtTheirOriginalRank() {
        var source = new Source(List.of(pos(0), pos(2), pos(4)), ScanAvailability.COMPLETE);
        var merged = StableCandidateMerge.merge(List.of(pos(3), pos(1)), source, ORDER);
        assertEquals(List.of(pos(0), pos(1), pos(2), pos(3), pos(4)), collect(merged));
    }

    @Test void oneLookaheadDoesNotDrainAnUnboundedSourceBeforeTheFirstTarget() {
        var source = new Source(List.of(pos(0), pos(2), pos(4)), ScanAvailability.PAUSED);
        var merged = StableCandidateMerge.merge(List.of(pos(-1)), source, ORDER);
        assertEquals(0, source.consumed);
        var iterator = merged.iterator();
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasNext());
        assertEquals(pos(-1), iterator.next());
        assertEquals(1, source.consumed);
        assertEquals(pos(0), iterator.next());
        assertEquals(1, source.consumed);
    }

    @Test void retriesAddedDuringThePassWaitForTheNextPassAndDuplicatesAreSuppressed() {
        var retries = new ArrayList<>(List.of(pos(1), pos(1)));
        var source = new Source(List.of(pos(0), pos(1), pos(2)), ScanAvailability.COMPLETE);
        var merged = StableCandidateMerge.merge(retries, source, ORDER);
        var iterator = merged.iterator();
        assertEquals(pos(0), iterator.next());
        retries.add(pos(3));
        assertEquals(List.of(pos(1), pos(2)), collect(() -> iterator));
    }

    @Test void pausedTailStaysPausedAfterReadyRetriesAreConsumed() {
        var source = new Source(List.of(), ScanAvailability.PAUSED);
        var merged = StableCandidateMerge.merge(List.of(pos(1)), source, ORDER);
        assertEquals(ScanAvailability.READY, merged.availability());
        assertEquals(List.of(pos(1)), collect(merged));
        assertEquals(ScanAvailability.PAUSED, merged.availability());
        assertFalse(merged.isBuffered());
    }

    @Test void sourceReplacementDropsBothPrefetchedFreshWorkAndTheOldRetrySnapshot() {
        var source = new Source(List.of(pos(0), pos(2)), ScanAvailability.COMPLETE);
        var merged = StableCandidateMerge.merge(List.of(pos(-1), pos(1)), source, ORDER);
        var iterator = merged.iterator();
        assertTrue(iterator.hasNext());
        source.valid = false;
        assertFalse(iterator.hasNext());
        assertThrows(java.util.NoSuchElementException.class, iterator::next);
        assertEquals(ScanAvailability.COMPLETE, merged.availability());
    }

    @Test void anInterruptedEarlierRetryKeepsTheUnconsumedFreshLookaheadForTheNextPass() {
        var retries = new java.util.LinkedHashSet<>(List.of(pos(-1)));
        var source = new Source(List.of(pos(0), pos(2)), ScanAvailability.COMPLETE);
        var firstPass = StableCandidateMerge.merge(retries, source, ORDER, retries::add).iterator();
        assertEquals(pos(-1), firstPass.next());
        retries.remove(pos(-1));
        assertTrue(retries.contains(pos(0)), "The advancing source's lookahead belongs to its owner until selected");
        var remainingSource = List.of(pos(2));
        assertEquals(List.of(pos(0), pos(2)), collect(
                StableCandidateMerge.merge(retries, remainingSource, ORDER, retries::add)));
    }

    private static List<BlockPos> collect(Iterable<BlockPos> iterable) {
        var result = new ArrayList<BlockPos>();
        iterable.forEach(result::add);
        return result;
    }

    private static final class Source implements ScanCandidateIterable {
        private final List<BlockPos> positions;
        private final ScanAvailability terminal;
        private int consumed;
        private boolean valid = true;
        Source(List<BlockPos> positions, ScanAvailability terminal) {
            this.positions = positions;
            this.terminal = terminal;
        }
        @Override public boolean isValid() { return this.valid; }
        @Override public ScanAvailability availability() {
            return this.consumed < this.positions.size() ? ScanAvailability.READY : this.terminal;
        }
        @Override public Iterator<BlockPos> iterator() {
            var iterator = this.positions.iterator();
            return new Iterator<>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public BlockPos next() { consumed++; return iterator.next(); }
            };
        }
    }
}
