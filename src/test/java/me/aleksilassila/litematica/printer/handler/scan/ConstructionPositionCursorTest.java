package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.core.scan.ConstructionOrder;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.scan.RawPrinterBoxFixture.rawBox;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionPositionCursorTest {
    @Test void overlappingNegativeBoxesMatchAnIndependentFullReferenceWithoutDuplicates() {
        List<PrinterBox> boxes = List.of(
                rawBox(-9, -2, -9, 10, 2, 10),
                rawBox(-3, -1, -2, 12, 1, 3));
        ScanRegion region = region(1, 0, -1, 12);
        for (boolean descending : List.of(false, true)) {
            var cursor = new ConstructionPositionCursor(boxes, region,
                    new ConstructionOrder(-1, 8, descending));
            List<BlockPos> expected = reference(boxes, region, descending);
            assertEquals(expected, drain(cursor));
            assertTrue(cursor.isComplete());
            assertEquals(Long.MAX_VALUE, cursor.peekDistanceSqr());
            assertEquals(PositionCursor.PollResult.COMPLETE, cursor.poll(new BlockPos.MutableBlockPos()));
        }
    }

    @Test void distancePeeksDescribeTheNextConstructionPositionRatherThanTheNearestPosition() {
        var cursor = new ConstructionPositionCursor(List.of(rawBox(0, 0, 0, 2, 0, 0)),
                region(2, 0, 0, 2), new ConstructionOrder(0, 0, false));
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        assertEquals(4, cursor.peekDistanceSqr());
        assertEquals(4, cursor.peekDistanceSqr(), "Peeking must not advance the cursor");
        assertEquals(PositionCursor.PollResult.AVAILABLE, cursor.poll(target));
        assertEquals(new BlockPos(0, 0, 0), target);
        assertEquals(1, cursor.peekDistanceSqr());
        assertEquals(List.of(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0)), drain(cursor));
    }

    @Test void changingTheRegionCenterDoesNotReorderTheSameEligibleSet() {
        List<PrinterBox> boxes = List.of(rawBox(-9, -1, -3, 9, 1, 4));
        var order = new ConstructionOrder(0, 0, false);
        List<BlockPos> first = drain(new ConstructionPositionCursor(boxes, region(0, 0, 0, 100), order));
        List<BlockPos> moved = drain(new ConstructionPositionCursor(boxes, region(5, 4, -6, 100), order));
        assertEquals(first, moved);
    }

    @Test void aHugeThinWindowMakesImmediateProgressWithoutMaterializingItsAxis() {
        assertTimeout(Duration.ofSeconds(3), () -> {
            var cursor = new ConstructionPositionCursor(
                    List.of(rawBox(-33_554_432, 0, 0, 33_554_431, 0, 0)),
                    region(0, 0, 0, Integer.MAX_VALUE), new ConstructionOrder(0, 0, false));
            BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
            List<BlockPos> first = new ArrayList<>();
            for (int count = 0; count < 16; count++) {
                assertEquals(PositionCursor.PollResult.AVAILABLE, cursor.poll(target));
                first.add(target.immutable());
            }
            assertEquals(List.of(
                    new BlockPos(-33_554_432, 0, 0), new BlockPos(-33_554_431, 0, 0),
                    new BlockPos(-33_554_430, 0, 0), new BlockPos(-33_554_429, 0, 0),
                    new BlockPos(-33_554_428, 0, 0), new BlockPos(-33_554_427, 0, 0),
                    new BlockPos(-33_554_426, 0, 0), new BlockPos(-33_554_425, 0, 0),
                    new BlockPos(-33_554_424, 0, 0), new BlockPos(-33_554_423, 0, 0),
                    new BlockPos(-33_554_422, 0, 0), new BlockPos(-33_554_421, 0, 0),
                    new BlockPos(-33_554_420, 0, 0), new BlockPos(-33_554_419, 0, 0),
                    new BlockPos(-33_554_418, 0, 0), new BlockPos(-33_554_417, 0, 0)), first);
        });
    }

    @Test void disjointPackedEdgesDoNotExpandTheGapAndRemainInRange() {
        List<PrinterBox> boxes = List.of(
                rawBox(-33_554_432, -2048, -33_554_432, -33_554_431, -2048, -33_554_432),
                rawBox(33_554_430, 2047, 33_554_431, 33_554_431, 2047, 33_554_431));
        assertTimeout(Duration.ofSeconds(3), () -> {
            var cursor = new ConstructionPositionCursor(boxes, region(0, 0, 0, Integer.MAX_VALUE),
                    new ConstructionOrder(0, 0, false));
            assertEquals(List.of(
                    new BlockPos(-33_554_432, -2048, -33_554_432),
                    new BlockPos(-33_554_431, -2048, -33_554_432),
                    new BlockPos(33_554_431, 2047, 33_554_431),
                    new BlockPos(33_554_430, 2047, 33_554_431)), drain(cursor));
        });
    }

    @Test void outOfBandHugeThinWindowsAndEmptySelectionsCompleteImmediately() {
        assertTimeout(Duration.ofSeconds(3), () -> {
            var outside = new ConstructionPositionCursor(
                    List.of(rawBox(-33_554_432, 0, 2000, 33_554_431, 0, 2000)),
                    region(0, 0, 0, 3), new ConstructionOrder(0, 0, false));
            assertTrue(drain(outside).isEmpty());
            assertTrue(outside.isComplete());
            assertTrue(new ConstructionPositionCursor(List.of(), region(0, 0, 0, 3),
                    new ConstructionOrder(0, 0, false)).isComplete());
        });
    }

    @Test void aSphereTangentColumnSkipsAllEmptyLayersInBothDirections() {
        List<PrinterBox> boxes = List.of(rawBox(1000, -1000, 0, 1000, 1000, 0));
        for (boolean descending : List.of(false, true)) {
            var cursor = new ConstructionPositionCursor(boxes, region(0, 0, 0, 1000),
                    new ConstructionOrder(1000, 0, descending));
            assertEquals(List.of(new BlockPos(1000, 0, 0)), drain(cursor),
                    "A tangent column has exactly one eligible layer, irrespective of scan direction");
        }
    }

    @Test void sphereClippedRowsKeepTheirOriginalSerpentineDirection() {
        var cursor = new ConstructionPositionCursor(List.of(rawBox(0, 0, 0, 7, 0, 2)),
                region(3, 0, 1, 2), new ConstructionOrder(0, 0, false));
        assertEquals(List.of(
                new BlockPos(2, 0, 0), new BlockPos(3, 0, 0), new BlockPos(4, 0, 0),
                new BlockPos(5, 0, 1), new BlockPos(4, 0, 1), new BlockPos(3, 0, 1),
                new BlockPos(2, 0, 1), new BlockPos(1, 0, 1),
                new BlockPos(2, 0, 2), new BlockPos(3, 0, 2), new BlockPos(4, 0, 2)), drain(cursor));
    }

    @Test void aHugeTangentStripFindsItsOnlyPointWithoutWalkingMillionsOfExcludedTiles() {
        int radius = 33_554_424;
        assertTimeout(Duration.ofSeconds(10), () -> {
            var cursor = new ConstructionPositionCursor(
                    List.of(rawBox(0, 0, 0, 0, 0, radius)),
                    region(radius, 0, radius, radius), new ConstructionOrder(0, 0, false));
            assertEquals(List.of(new BlockPos(0, 0, radius)), drain(cursor),
                    "The sphere intersects the selected strip only at its far endpoint");
        });
    }

    @Test void skipSectionSkipsOnlyTheCurrentStorageSectionAcrossOverlaps() {
        List<PrinterBox> boxes = List.of(rawBox(0, 14, 0, 0, 18, 0),
                rawBox(0, 14, 0, 0, 18, 0), rawBox(8, 14, 0, 8, 14, 0),
                rawBox(16, 14, 0, 16, 14, 0));
        for (boolean descending : List.of(false, true)) {
            var cursor = new ConstructionPositionCursor(boxes, region(0, 16, 0, 20),
                    new ConstructionOrder(0, 0, descending));
            cursor.skipSection();
            BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
            assertEquals(PositionCursor.PollResult.AVAILABLE, cursor.poll(target));
            assertEquals(new BlockPos(0, descending ? 18 : 14, 0), target);
            cursor.skipSection();
            assertEquals(descending
                    ? List.of(new BlockPos(0, 15, 0), new BlockPos(0, 14, 0), new BlockPos(8, 14, 0), new BlockPos(16, 14, 0))
                    : List.of(new BlockPos(16, 14, 0), new BlockPos(0, 16, 0), new BlockPos(0, 17, 0), new BlockPos(0, 18, 0)),
                    drain(cursor));
        }
    }

    private static ScanRegion region(int x, int y, int z, int band) {
        return new ScanRegion(x, y, z, -2_097_152, -128, -2_097_152,
                2_097_151, 127, 2_097_151, band);
    }

    private static List<BlockPos> drain(PositionCursor cursor) {
        List<BlockPos> actual = new ArrayList<>();
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        while (cursor.poll(target) == PositionCursor.PollResult.AVAILABLE) actual.add(target.immutable());
        return actual;
    }

    private static List<BlockPos> reference(List<PrinterBox> boxes, ScanRegion region, boolean descending) {
        Set<BlockPos> selected = new HashSet<>();
        for (PrinterBox box : boxes) {
            for (int y = box.minY; y <= box.maxY; y++) {
                for (int z = box.minZ; z <= box.maxZ; z++) {
                    for (int x = box.minX; x <= box.maxX; x++) {
                        long dx = (long) x - region.centerX(), dy = (long) y - region.centerY();
                        long dz = (long) z - region.centerZ();
                        if (dx * dx + dy * dy + dz * dz <= (long) region.maxDistanceBand() * region.maxDistanceBand()) {
                            selected.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
        }
        List<BlockPos> result = new ArrayList<>(selected);
        result.sort(Comparator.comparingInt((BlockPos pos) -> descending ? -pos.getY() : pos.getY())
                .thenComparingInt(BlockPos::getZ)
                .thenComparingInt(pos -> (pos.getZ() & 1) == 0 ? pos.getX() : -pos.getX()));
        return result;
    }
}
