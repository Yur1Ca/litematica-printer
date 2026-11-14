package me.aleksilassila.litematica.printer.handler.scan;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkCandidateCacheTest {
    @Test
    void unchangedChunkIsObservedOnceAcrossRepeatedPasses() {
        ChunkCandidateCache cache = new ChunkCandidateCache();
        AtomicInteger reads = new AtomicInteger();

        assertTrue(cache.getOrCompute(10, -4, () -> {
            reads.incrementAndGet();
            return true;
        }));
        assertTrue(cache.getOrCompute(10, -4, () -> {
            reads.incrementAndGet();
            return false;
        }));
        assertTrue(reads.get() == 1);

        cache.invalidate(10, -4);
        assertFalse(cache.getOrCompute(10, -4, () -> {
            reads.incrementAndGet();
            return false;
        }));
        assertTrue(reads.get() == 2);
    }

    @Test
    void movingThroughManyChunksRecomputesEvictedHistoricalNegatives() {
        var cache = new ChunkCandidateCache();
        for (int chunk = 0; chunk < 4096; chunk++) {
            assertFalse(cache.getOrCompute(chunk, -chunk, () -> false));
        }
        assertEquals(1024, retainedDecisions(cache, 4096));
        var reads = new AtomicInteger();
        assertTrue(cache.getOrCompute(0, 0, () -> {
            reads.incrementAndGet();
            return true;
        }), "A persistent session must not retain all historical negative chunk decisions");
        assertEquals(1, reads.get());
        assertFalse(cache.getOrCompute(4095, -4095, () -> {
            throw new AssertionError("The most recent chunk should still be cached");
        }));
    }

    @Test
    void aCacheHitKeepsTheChunkAheadOfTheLeastRecentlyUsedEntry() {
        var cache = new ChunkCandidateCache(2);
        assertTrue(cache.getOrCompute(-1, -2, () -> true));
        assertFalse(cache.getOrCompute(-2, -1, () -> false));
        assertTrue(cache.getOrCompute(-1, -2, () -> {
            throw new AssertionError("A cached hit must not recompute");
        }));
        assertTrue(cache.getOrCompute(3, 5, () -> true));
        assertTrue(cache.getOrCompute(-2, -1, () -> true), "The cold negative must have been evicted");
        assertTrue(cache.getOrCompute(3, 5, () -> {
            throw new AssertionError("The recently added chunk must remain resident");
        }));
    }

    @Test
    void customCapacityBoundsTheNumberOfRetainedDecisionsAfterLongTravel() {
        var cache = new ChunkCandidateCache(3);
        for (int chunk = 0; chunk < 100; chunk++) cache.getOrCompute(chunk, -chunk, () -> true);
        assertEquals(3, retainedDecisions(cache, 100));
        assertFalse(cache.getOrCompute(0, 0, () -> false));
        assertEquals(3, retainedDecisions(cache, 100));
    }

    @Test
    void nonpositiveCapacityAlwaysRecomputesWithoutRetainingAnEntry() {
        for (int capacity : new int[]{0, -1}) {
            var cache = new ChunkCandidateCache(capacity);
            assertFalse(cache.getOrCompute(0, 0, () -> false));
            assertTrue(cache.getOrCompute(0, 0, () -> true));
            assertEquals(0, retainedDecisions(cache, 1));
        }
    }

    @Test
    void invalidateAndClearDiscardBothPositiveAndNegativeDecisions() {
        var cache = new ChunkCandidateCache(2);
        assertTrue(cache.getOrCompute(0, 0, () -> true));
        assertFalse(cache.getOrCompute(1, -1, () -> false));
        cache.invalidate(0, 0);
        cache.invalidate(0, 0);
        assertEquals(1, retainedDecisions(cache, 2));
        assertFalse(cache.getOrCompute(0, 0, () -> false));
        cache.clear();
        assertEquals(0, retainedDecisions(cache, 2));
        assertTrue(cache.getOrCompute(0, 0, () -> true));
        assertTrue(cache.getOrCompute(1, -1, () -> true));
        assertEquals(2, retainedDecisions(cache, 2));
    }

    @Test
    void failedRecomputationDoesNotEvictAValidCachedDecision() {
        var cache = new ChunkCandidateCache(1);
        var failure = new IllegalStateException("chunk observation");
        assertFalse(cache.getOrCompute(0, 0, () -> false));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> cache.getOrCompute(1, -1, () -> { throw failure; })));
        assertFalse(cache.getOrCompute(0, 0, () -> {
            throw new AssertionError("A failed observation must not evict previous facts");
        }));
        assertEquals(1, retainedDecisions(cache, 2));
        assertTrue(cache.getOrCompute(1, -1, () -> true));
    }

    private static int retainedDecisions(ChunkCandidateCache cache, int visitedChunks) {
        // Misses throw before insertion so probing the historical keys does not repopulate or
        // evict entries. Count real reusable decisions without reflecting into the backing map.
        var miss = new CacheMiss();
        int retained = 0;
        for (int chunk = 0; chunk < visitedChunks; chunk++) {
            try {
                cache.getOrCompute(chunk, -chunk, () -> { throw miss; });
                retained++;
            } catch (CacheMiss expected) {
                assertSame(miss, expected);
            }
        }
        return retained;
    }

    private static final class CacheMiss extends RuntimeException {}
}
