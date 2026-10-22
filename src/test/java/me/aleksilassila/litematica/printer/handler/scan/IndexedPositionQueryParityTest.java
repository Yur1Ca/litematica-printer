package me.aleksilassila.litematica.printer.handler.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IndexedPositionQueryParityTest {
    @Test
    void completeOrderMatchesFlatReferenceAcrossSelectionsCentersAndRadii() {
        Random random = new Random(0x23C0DE);
        for (int trial = 0; trial < 80; trial++) {
            int cx = trial % 2 == 0 ? -17 : 31;
            int cy = trial % 3 == 0 ? -64 : 319;
            int cz = trial % 4 == 0 ? -33 : 0;
            List<BlockPos> input = new ArrayList<>();
            for (int i = 0; i < 1200; i++) {
                input.add(new BlockPos(cx + random.nextInt(97) - 48,
                        cy + random.nextInt(65) - 32, cz + random.nextInt(97) - 48));
            }
            input.addAll(input.subList(0, 30)); // The index must not duplicate overlapping input.
            List<PrinterBox> boxes = trial % 10 == 0 ? List.of() : List.of(
                    new PrinterBox(cx - 40, cy - 32, cz - 40, cx + 4, cy + 32, cz + 40),
                    new PrinterBox(cx - 4, cy - 20, cz - 24, cx + 40, cy + 20, cz + 24));
            ScanRegion region = region(cx, cy, cz, trial % 49);
            assertCursorEquals(reference(input, boxes, region), cursor(input, boxes, region), region);
        }
    }

    @Test
    void radiusBoundaryAndEqualDistanceUseYThenXThenZ() {
        List<BlockPos> input = List.of(new BlockPos(1, 0, 0), new BlockPos(0, 0, 1),
                new BlockPos(0, 1, 0), new BlockPos(0, 0, -1), new BlockPos(-1, 0, 0),
                new BlockPos(0, -1, 0), BlockPos.ZERO, new BlockPos(1, 1, 0));
        List<PrinterBox> boxes = List.of(new PrinterBox(-2, -2, -2, 2, 2, 2));
        assertCursorEquals(List.of(BlockPos.ZERO, new BlockPos(0, -1, 0),
                new BlockPos(-1, 0, 0), new BlockPos(0, 0, -1), new BlockPos(0, 0, 1),
                new BlockPos(1, 0, 0), new BlockPos(0, 1, 0)), cursor(input, boxes, region(0, 0, 0, 1)));
        assertCursorEquals(List.of(BlockPos.ZERO), cursor(input, boxes, region(0, 0, 0, 0)));
    }

    @Test
    void packedCoordinateEdgesAndHugeSparseSelectionsKeepAllCandidates() {
        List<BlockPos> input = List.of(new BlockPos(-33_554_432, -2048, -33_554_432),
                new BlockPos(33_554_431, 2047, 33_554_431), new BlockPos(-17, -17, -17),
                new BlockPos(-16, -16, -16), new BlockPos(-1, -1, -1), BlockPos.ZERO,
                new BlockPos(15, 15, 15), new BlockPos(16, 16, 16));
        List<PrinterBox> boxes = List.of(new PrinterBox(-33_554_432, -2048, -33_554_432,
                33_554_431, 2047, 33_554_431));
        for (ScanRegion region : List.of(region(0, 0, 0, Integer.MAX_VALUE),
                region(-33_554_432, -2048, -33_554_432, 64),
                region(33_554_431, 2047, 33_554_431, 64))) {
            assertCursorEquals(reference(input, boxes, region), cursor(input, boxes, region));
        }
    }

    @Test
    void independentOwnersAndCreatedCursorsDoNotShareProgressOrMutableStorage() {
        SectionPositionIndex index = new SectionPositionIndex();
        List<BlockPos> input = List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(2, 0, 0));
        input.forEach(pos -> index.add(pos.asLong()));
        List<PrinterBox> boxes = List.of(new PrinterBox(-3, -3, -3, 3, 3, 3));
        var first = new IndexedPositionCursor(index, boxes, region(0, 0, 0, 3));
        var second = new IndexedPositionCursor(index, boxes, region(0, 0, 0, 3));
        var target = new BlockPos.MutableBlockPos();
        assertEquals(PositionCursor.PollResult.AVAILABLE, first.poll(target));
        assertEquals(BlockPos.ZERO, target);
        index.clear();
        assertCursorEquals(input, second);
        assertCursorEquals(input.subList(1, 3), first);
        assertCursorEquals(List.of(), new IndexedPositionCursor(index, boxes, region(0, 0, 0, 3)));
    }

    static ScanRegion region(int x, int y, int z, int radius) {
        // The old sparse cursor filters source boxes and distance, not these section-window fields.
        return new ScanRegion(x, y, z, 0, 0, 0, 0, 0, 0, radius);
    }

    private static IndexedPositionCursor cursor(List<BlockPos> input, List<PrinterBox> boxes, ScanRegion region) {
        SectionPositionIndex index = new SectionPositionIndex();
        input.forEach(pos -> index.add(pos.asLong()));
        return new IndexedPositionCursor(index, boxes, region);
    }

    static List<BlockPos> reference(List<BlockPos> input, List<PrinterBox> boxes, ScanRegion region) {
        TreeSet<BlockPos> result = new TreeSet<>(Comparator
                .comparingLong((BlockPos pos) -> distance(pos, region))
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        for (BlockPos pos : input) {
            if (boxes.stream().anyMatch(box -> box.contains(pos))
                    && distance(pos, region) <= (long) region.maxDistanceBand() * region.maxDistanceBand()) {
                result.add(pos);
            }
        }
        return List.copyOf(result);
    }

    static void assertCursorEquals(List<BlockPos> expected, PositionCursor cursor) {
        assertCursorEquals(expected, cursor, null);
    }

    private static void assertCursorEquals(List<BlockPos> expected, PositionCursor cursor, ScanRegion region) {
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        for (BlockPos pos : expected) {
            assertFalse(cursor.isComplete());
            long before = cursor.peekDistanceSqr();
            if (region != null) assertEquals(distance(pos, region), before);
            assertEquals(before, cursor.peekDistanceSqr(), "peeking must not consume a candidate");
            assertEquals(PositionCursor.PollResult.AVAILABLE, cursor.poll(target));
            assertEquals(pos, target);
        }
        assertTrue(cursor.isComplete());
        assertEquals(Long.MAX_VALUE, cursor.peekDistanceSqr());
        assertEquals(PositionCursor.PollResult.COMPLETE, cursor.poll(target));
        assertEquals(PositionCursor.PollResult.COMPLETE, cursor.poll(target));
    }

    private static long distance(BlockPos pos, ScanRegion region) {
        long x = (long) pos.getX() - region.centerX();
        long y = (long) pos.getY() - region.centerY();
        long z = (long) pos.getZ() - region.centerZ();
        return x * x + y * y + z * z;
    }
}
