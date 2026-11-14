package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundedSectionSnapshotStoreTest {
    private static final int SLOT_BYTES = 4096 + 512;

    @Test void onlyACompletelyObservedZeroSectionCanSleep() {
        var store = new SectionSnapshotStore();
        var source = new Observation();
        long key = ScanGeometry.sectionKey(-1, -1, -1);
        assertTrue(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, false));

        for (int index = 0; index < 4095; index++) {
            store.classify(position(index), ScanIntent.PRINT, false, source);
        }
        assertTrue(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, false), "One unknown position remains");
        store.classify(position(4095), ScanIntent.PRINT, false, source);
        assertFalse(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, false));
        assertTrue(store.hasPotentialOrUnknown(key, ScanIntent.MINE, false));
        assertTrue(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, true));

        source.flags = 1;
        store.invalidateWorld(position(0));
        assertTrue(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, false));
        for (int index : new int[]{0, 1, 16, 256}) {
            store.classify(position(index), ScanIntent.PRINT, false, source);
        }
        assertTrue(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, false), "Observed nonzero positions remain work");
        source.flags = 0;
        store.invalidateWorld(position(0));
        store.invalidateWorld(position(0));
        for (int index : new int[]{0, 1, 16, 256}) {
            store.classify(position(index), ScanIntent.PRINT, false, source);
        }
        assertFalse(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, false), "Repeated invalidation must not corrupt counts");
    }

    @Test void knownZeroIsSeparateForEachIntentAndPrintPolicy() {
        var store = new SectionSnapshotStore();
        var source = new Observation();
        var pos = new BlockPos(-1, 15, 16);
        assertFalse(store.isKnownZero(pos, ScanIntent.PRINT, false));
        for (ScanIntent intent : ScanIntent.values()) {
            store.classify(pos, intent, false, source);
            assertTrue(store.isKnownZero(pos, intent, false));
        }
        assertFalse(store.isKnownZero(pos, ScanIntent.PRINT, true));
        source.flags = 1;
        store.classify(pos, ScanIntent.PRINT, true, source);
        assertFalse(store.isKnownZero(pos, ScanIntent.PRINT, true));
        assertTrue(store.isKnownZero(pos, ScanIntent.PRINT, false));

        store.invalidateWorld(pos.east());
        for (ScanIntent intent : ScanIntent.values()) {
            assertFalse(store.isKnownZero(pos, intent, false));
        }
        source.flags = 0;
        store.classify(pos, ScanIntent.PRINT, true, source);
        assertTrue(store.isKnownZero(pos, ScanIntent.PRINT, true));
    }

    @Test void leastRecentlyUsedSectionBecomesUnknownAndIsObservedAgain() {
        var store = new SectionSnapshotStore(2 * SLOT_BYTES);
        var source = new Observation();
        var first = new BlockPos(-16, 0, 0);
        var second = BlockPos.ZERO;
        var third = new BlockPos(16, 0, 0);
        store.classify(first, ScanIntent.PRINT, false, source);
        store.classify(second, ScanIntent.PRINT, false, source);
        store.classify(first, ScanIntent.PRINT, false, source); // Keep the first section hot.
        store.classify(third, ScanIntent.PRINT, false, source);

        assertTrue(store.isKnownZero(first, ScanIntent.PRINT, false));
        assertFalse(store.isKnownZero(second, ScanIntent.PRINT, false));
        assertTrue(store.hasPotentialOrUnknown(ScanGeometry.sectionKey(0, 0, 0), ScanIntent.PRINT, false));
        assertNull(store.excludedPrintPositions(ScanGeometry.sectionKey(0, 0, 0)));
        assertEquals(2 * SLOT_BYTES, store.retainedBytes());
        assertEquals(2, store.retainedSectionCount());
        source.flags = 1;
        assertEquals(1, store.classify(second, ScanIntent.PRINT, false, source));
        assertEquals(4, source.reads, "An evicted zero must be observed again");
    }

    @Test void sectionLimitAlsoEvictsWhenTheByteBudgetHasRoom() {
        var store = new SectionSnapshotStore(16 * SLOT_BYTES, 2);
        var source = new Observation();
        store.classify(BlockPos.ZERO, ScanIntent.MINE, false, source);
        store.classify(new BlockPos(16, 0, 0), ScanIntent.MINE, false, source);
        store.classify(new BlockPos(32, 0, 0), ScanIntent.MINE, false, source);
        assertEquals(2, store.retainedSectionCount());
        assertEquals(2 * SLOT_BYTES, store.retainedBytes());
        assertFalse(store.isKnownZero(BlockPos.ZERO, ScanIntent.MINE, false));
    }

    @Test void allocatingAnotherIntentCanEvictTheSameSectionWithoutKeepingOldKnowledge() {
        var store = new SectionSnapshotStore(SLOT_BYTES);
        var source = new Observation();
        store.classify(BlockPos.ZERO, ScanIntent.PRINT, false, source);
        store.classify(BlockPos.ZERO, ScanIntent.PRINT, true, source);
        assertEquals(SLOT_BYTES, store.retainedBytes());
        assertEquals(1, store.retainedSectionCount());
        assertFalse(store.isKnownZero(BlockPos.ZERO, ScanIntent.PRINT, false));
        assertTrue(store.isKnownZero(BlockPos.ZERO, ScanIntent.PRINT, true));
        assertNull(store.excludedPrintPositions(0L));
    }

    @Test void subSlotAndDisabledBudgetsNeverRetainPartialKnowledge() {
        for (int bytes : new int[]{-1, 0, 1, SLOT_BYTES - 1}) {
            var store = new SectionSnapshotStore(bytes);
            var source = new Observation();
            assertEquals(0, store.classify(BlockPos.ZERO, ScanIntent.PRINT, false, source));
            source.flags = 1;
            assertEquals(1, store.classify(BlockPos.ZERO, ScanIntent.PRINT, false, source));
            assertEquals(2, source.reads);
            assertEquals(0, store.retainedBytes());
            assertEquals(0, store.retainedSectionCount());
            assertFalse(store.isKnownZero(BlockPos.ZERO, ScanIntent.PRINT, false));
            assertTrue(store.hasPotentialOrUnknown(0L, ScanIntent.PRINT, false));
        }
        var disabled = new SectionSnapshotStore(SLOT_BYTES, 0);
        disabled.classify(BlockPos.ZERO, ScanIntent.PRINT, false, new Observation());
        assertEquals(0, disabled.retainedSectionCount());
    }

    @Test void invalidatingPrintReleasesOnlyItsSlotsAndKeepsOtherFacts() {
        var store = new SectionSnapshotStore(4 * SLOT_BYTES);
        var source = new Observation();
        store.classify(BlockPos.ZERO, ScanIntent.PRINT, false, source);
        store.classify(BlockPos.ZERO, ScanIntent.PRINT, true, source);
        store.classify(BlockPos.ZERO, ScanIntent.MINE, false, source);
        store.classify(new BlockPos(16, 0, 0), ScanIntent.PRINT, false, source);
        assertEquals(4 * SLOT_BYTES, store.retainedBytes());
        store.invalidatePrint();
        store.invalidatePrint();
        assertEquals(SLOT_BYTES, store.retainedBytes());
        assertEquals(1, store.retainedSectionCount(), "Empty section headers must also be released");
        assertTrue(store.isKnownZero(BlockPos.ZERO, ScanIntent.MINE, false));
        assertFalse(store.isKnownZero(BlockPos.ZERO, ScanIntent.PRINT, false));
        assertFalse(store.isKnownZero(BlockPos.ZERO, ScanIntent.PRINT, true));
        store.clear();
        assertEquals(0, store.retainedBytes());
        assertEquals(0, store.retainedSectionCount());
    }

    @Test void sectionPolicyInvalidationKeepsOtherIntentSlotsAndResetsCompletionCounts() {
        var store = new SectionSnapshotStore();
        var source = new Observation();
        long key = ScanGeometry.sectionKey(-1, -1, -1);
        for (int index = 0; index < 4096; index++) {
            var pos = position(index);
            store.classify(pos, ScanIntent.PRINT, false, source);
            store.classify(pos, ScanIntent.PRINT, true, source);
            store.classify(pos, ScanIntent.MINE, false, source);
        }
        assertFalse(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, true));
        store.invalidateSection(key, ScanIntent.PRINT, true);
        store.invalidateSection(key, ScanIntent.PRINT, true);
        assertTrue(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, true));
        assertFalse(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, false));
        assertFalse(store.hasPotentialOrUnknown(key, ScanIntent.MINE, false));
        assertEquals(2 * SLOT_BYTES, store.retainedBytes());
        for (int index = 0; index < 4096; index++) {
            store.classify(position(index), ScanIntent.PRINT, true, source);
        }
        assertFalse(store.hasPotentialOrUnknown(key, ScanIntent.PRINT, true));
        store.invalidateSection(key, ScanIntent.PRINT, false);
        store.invalidateSection(key, ScanIntent.PRINT, true);
        store.invalidateSection(key, ScanIntent.MINE, false);
        assertEquals(0, store.retainedBytes());
        assertEquals(0, store.retainedSectionCount());
    }

    @Test void printChunkReplacementOnlyClearsPrintPoliciesInsideItsHeightRange() {
        var store = new SectionSnapshotStore();
        var source = new Observation();
        var upper = new BlockPos(0, 16, 0);
        var neighbor = new BlockPos(16, 0, 0);
        store.classify(BlockPos.ZERO, ScanIntent.PRINT, false, source);
        store.classify(BlockPos.ZERO, ScanIntent.PRINT, true, source);
        store.classify(BlockPos.ZERO, ScanIntent.MINE, false, source);
        store.classify(upper, ScanIntent.PRINT, false, source);
        store.classify(neighbor, ScanIntent.PRINT, false, source);
        store.invalidatePrintChunk(0, 0, 0, 15);
        store.invalidatePrintChunk(0, 0, 0, 15);
        assertFalse(store.isKnownZero(BlockPos.ZERO, ScanIntent.PRINT, false));
        assertFalse(store.isKnownZero(BlockPos.ZERO, ScanIntent.PRINT, true));
        assertTrue(store.isKnownZero(BlockPos.ZERO, ScanIntent.MINE, false));
        assertTrue(store.isKnownZero(upper, ScanIntent.PRINT, false));
        assertTrue(store.isKnownZero(neighbor, ScanIntent.PRINT, false));
        assertEquals(3 * SLOT_BYTES, store.retainedBytes());
        assertEquals(3, store.retainedSectionCount());
        store.invalidatePrintChunk(0, 0, 16, 15);
        assertEquals(3 * SLOT_BYTES, store.retainedBytes());
        store.invalidatePrintChunk(0, 0, 16, 31);
        assertEquals(2 * SLOT_BYTES, store.retainedBytes());
        assertEquals(2, store.retainedSectionCount());
        assertTrue(store.hasPotentialOrUnknown(ScanGeometry.sectionKey(0, 1, 0), ScanIntent.PRINT, false));
    }

    @Test void repeatedAdjacentChunkInvalidationReopensExactlyItsTouchingFace() {
        var store = new SectionSnapshotStore();
        var source = new Observation();
        long key = ScanGeometry.sectionKey(-1, -1, -1);
        for (int index = 0; index < 4096; index++) {
            store.classify(position(index), ScanIntent.FILL, false, source);
        }
        assertFalse(store.hasPotentialOrUnknown(key, ScanIntent.FILL, false));
        store.invalidateChunk(0, -1, -16, -1);
        store.invalidateChunk(0, -1, -16, -1);
        assertTrue(store.hasPotentialOrUnknown(key, ScanIntent.FILL, false));
        assertTrue(store.isKnownZero(new BlockPos(-2, -10, -10), ScanIntent.FILL, false));
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            store.classify(new BlockPos(-1, y - 16, z - 16), ScanIntent.FILL, false, source);
        }
        assertFalse(store.hasPotentialOrUnknown(key, ScanIntent.FILL, false));
        assertEquals(4096 + 256, source.reads);
    }

    @Test void sectionAndChunkInvalidationReleasePayloadWithoutClearingUnrelatedSections() {
        var store = new SectionSnapshotStore(8 * SLOT_BYTES);
        var source = new Observation();
        var removed = new BlockPos(-16, -16, -16);
        var face = new BlockPos(0, -10, -10);
        var interior = new BlockPos(1, -10, -10);
        var far = new BlockPos(48, -10, -10);
        for (BlockPos pos : new BlockPos[]{removed, face, interior, far}) {
            store.classify(pos, ScanIntent.PRINT, false, source);
        }
        assertEquals(3 * SLOT_BYTES, store.retainedBytes());
        store.invalidateChunk(-1, -1, -16, -1);
        assertFalse(store.isKnownZero(removed, ScanIntent.PRINT, false));
        assertFalse(store.isKnownZero(face, ScanIntent.PRINT, false));
        assertTrue(store.isKnownZero(interior, ScanIntent.PRINT, false));
        assertTrue(store.isKnownZero(far, ScanIntent.PRINT, false));
        assertEquals(2 * SLOT_BYTES, store.retainedBytes());
        store.invalidateSection(ScanGeometry.sectionKey(3, -1, -1));
        store.invalidateSection(ScanGeometry.sectionKey(3, -1, -1));
        assertEquals(SLOT_BYTES, store.retainedBytes());
        assertEquals(1, store.retainedSectionCount());
        assertTrue(store.hasPotentialOrUnknown(ScanGeometry.sectionKey(3, -1, -1), ScanIntent.PRINT, false));
    }

    @Test void classificationFailureCannotPublishObservedKnowledgeOrEvictExistingFacts() {
        var store = new SectionSnapshotStore(SLOT_BYTES);
        var source = new Observation();
        store.classify(BlockPos.ZERO, ScanIntent.PRINT, false, source);
        var other = new BlockPos(16, 0, 0);
        var failure = new IllegalStateException("classification");
        source.failure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> store.classify(other, ScanIntent.PRINT, false, source)));
        assertTrue(store.isKnownZero(BlockPos.ZERO, ScanIntent.PRINT, false));
        assertFalse(store.isKnownZero(other, ScanIntent.PRINT, false));
        assertEquals(SLOT_BYTES, store.retainedBytes());
        assertEquals(1, store.retainedSectionCount());
        source.failure = null;
        source.flags = 1;
        assertEquals(1, store.classify(other, ScanIntent.PRINT, false, source));
    }

    private static BlockPos position(int index) {
        return new BlockPos(-16 + (index & 15), -16 + (index >>> 8), -16 + ((index >>> 4) & 15));
    }

    private static final class Observation implements WorldObservationPort {
        private byte flags;
        private int reads;
        private RuntimeException failure;

        @Override public boolean hasChunk(int x, int z) { return true; }
        @Override public byte classify(ScanIntent intent, BlockPos pos, boolean extra) {
            this.reads++;
            if (this.failure != null) throw this.failure;
            return this.flags;
        }
        @Override public BlockState worldState(BlockPos pos) { throw new AssertionError(); }
        @Override public BlockState schematicState(BlockPos pos) { throw new AssertionError(); }
        @Override public boolean hasFillSupport(BlockPos pos) { throw new AssertionError(); }
    }
}
