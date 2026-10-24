package me.aleksilassila.litematica.printer.handler.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionCandidateMaskTest {
    private static final WorldObservationPort ZERO = observation((byte) 0);
    private static final WorldObservationPort POSITIVE = observation((byte) 1);

    @Test void zeroNonzeroAndUnknownTransitionsRemainIndependentForEveryIntent() {
        var snapshots = new SectionSnapshotStore();
        var pos = new BlockPos(15, 31, -1);
        for (ScanIntent intent : ScanIntent.values()) {
            for (boolean extra : new boolean[]{false, true}) {
                snapshots.clear();
                assertEquals(0, snapshots.classify(pos, intent, extra, ZERO));
                assertEquals(0, snapshots.classify(pos, intent, extra, POSITIVE), "Known zero is cached");
                snapshots.invalidateWorld(pos);
                assertEquals(1, snapshots.classify(pos, intent, extra, POSITIVE));
                assertEquals(1, snapshots.classify(pos, intent, extra, ZERO), "Nonzero flags prove observation");
                snapshots.invalidateWorld(pos.above());
                assertEquals(0, snapshots.classify(pos, intent, extra, ZERO));
            }
        }
    }

    @Test void onlyKnownZeroNormalPrintFlagsCanExcludePositions() {
        var snapshots = new SectionSnapshotStore();
        var pos = new BlockPos(-1, -1, -1);
        assertFalse(excluded(snapshots, pos));
        snapshots.classify(pos, ScanIntent.MINE, false, ZERO);
        snapshots.classify(pos, ScanIntent.PRINT, true, ZERO);
        assertFalse(excluded(snapshots, pos), "Other intents and break-extra are separate observations");
        snapshots.classify(pos, ScanIntent.PRINT, false, ZERO);
        assertTrue(excluded(snapshots, pos));
        snapshots.invalidateWorld(pos.east());
        assertFalse(excluded(snapshots, pos), "Cross-section neighbors must become unknown");
        snapshots.classify(pos, ScanIntent.PRINT, false, POSITIVE);
        assertFalse(excluded(snapshots, pos));
        snapshots.invalidateWorld(pos);
        snapshots.classify(pos, ScanIntent.PRINT, false, ZERO);
        assertTrue(excluded(snapshots, pos));
        snapshots.invalidatePrint();
        assertFalse(excluded(snapshots, pos));
    }

    @Test void chunkReloadInvalidatesOnlyObservedChunkAndAdjacentFaces() {
        var snapshots = new SectionSnapshotStore();
        var positions = List.of(new BlockPos(15, 10, 0), new BlockPos(14, 10, 0),
                new BlockPos(16, 10, 0), new BlockPos(32, 10, 0));
        positions.forEach(p -> snapshots.classify(p, ScanIntent.PRINT, false, ZERO));
        snapshots.invalidateChunk(1, 0, 0, 15);
        assertFalse(excluded(snapshots, positions.get(0)));
        assertTrue(excluded(snapshots, positions.get(1)));
        assertFalse(excluded(snapshots, positions.get(2)));
        assertFalse(excluded(snapshots, positions.get(3)));
        snapshots.clear();
        assertFalse(excluded(snapshots, positions.get(1)));
    }

    @Test void maskedSpatialQueryMatchesFilteringReferenceAcrossSparseDenseAndUnknownSections() {
        var random = new Random(0x0B5E12);
        for (int count : new int[]{1, 63, 64, 65, 66, 512, 4096}) {
            var index = new SectionPositionIndex();
            var snapshots = new SectionSnapshotStore();
            var all = new ArrayList<BlockPos>();
            var expected = new ArrayList<BlockPos>();
            for (int i = 0; i < count; i++) {
                var p = new BlockPos(-16 + (i & 15), -16 + (i >>> 8), -16 + ((i >>> 4) & 15));
                all.add(p);
                index.add(p.asLong());
                index.add(p.asLong());
                int state = random.nextInt(3);
                if (state == 0) snapshots.classify(p, ScanIntent.PRINT, false, ZERO);
                else {
                    if (state == 1) snapshots.classify(p, ScanIntent.PRINT, false, POSITIVE);
                    expected.add(p);
                }
            }
            // An entirely unobserved section must survive any masks in the previous section.
            index.add(0L); all.add(BlockPos.ZERO); expected.add(BlockPos.ZERO);
            var boxes = List.of(new PrinterBox(-32, -32, -32, 16, 16, 16));
            var region = IndexedPositionQueryParityTest.region(-3, -5, -7, 64);
            SchematicPositionQuery masked = (b, r, c) -> index.forEachInRange(b, r, snapshots::excludedPrintPositions, c);
            IndexedPositionQueryParityTest.assertCursorEquals(
                    IndexedPositionQueryParityTest.reference(expected, boxes, region), new IndexedPositionCursor(masked, boxes, region));
            IndexedPositionQueryParityTest.assertCursorEquals(
                    IndexedPositionQueryParityTest.reference(all, boxes, region), new IndexedPositionCursor(index, boxes, region));
            snapshots.clear();
            IndexedPositionQueryParityTest.assertCursorEquals(
                    IndexedPositionQueryParityTest.reference(all, boxes, region), new IndexedPositionCursor(masked, boxes, region));
        }
    }

    private static boolean excluded(SectionSnapshotStore snapshots, BlockPos p) {
        long[] bits = snapshots.excludedPrintPositions(ScanGeometry.sectionKey(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4));
        int index = (p.getY() & 15) << 8 | (p.getZ() & 15) << 4 | p.getX() & 15;
        return bits != null && (bits[index >>> 6] & 1L << (index & 63)) != 0;
    }

    private static WorldObservationPort observation(byte flags) {
        return new WorldObservationPort() {
            public boolean hasChunk(int x, int z) { return true; }
            public byte classify(ScanIntent intent, BlockPos p, boolean extra) { return flags; }
            public BlockState worldState(BlockPos p) { throw new AssertionError(); }
            public BlockState schematicState(BlockPos p) { throw new AssertionError(); }
            public boolean hasFillSupport(BlockPos p) { throw new AssertionError(); }
        };
    }
}
