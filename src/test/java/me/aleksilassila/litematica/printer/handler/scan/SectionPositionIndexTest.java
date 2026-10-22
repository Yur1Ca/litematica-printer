package me.aleksilassila.litematica.printer.handler.scan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.scan.IndexedPositionQueryParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SectionPositionIndexTest {
    @Test
    void millionPositionLocalQueryOnlyVisitsNearbySections() {
        var index = new SectionPositionIndex();
        var nearby = new ArrayList<BlockPos>();
        for (int y = -4; y <= 4; y++) for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) {
            var pos = new BlockPos(x, 64 + y, z);
            nearby.add(pos);
            index.add(pos.asLong());
        }
        for (int i = 0; i < 1_048_576 - nearby.size(); i++) {
            index.add(BlockPos.asLong(1024 + (i & 1023), 64 + ((i >>> 10) & 255), 1024 + (i >>> 18)));
        }
        var boxes = List.of(new PrinterBox(-32, 32, -32, 2047, 319, 1028));
        var region = region(0, 64, 0, 4);
        var visited = new AtomicLong();
        assertEquals(729, index.forEachInRange(boxes, region, packed -> visited.incrementAndGet()));
        assertEquals(729, visited.get());
        var expected = reference(nearby, boxes, region);
        assertEquals(257, expected.size());
        assertCursorEquals(expected, new IndexedPositionCursor(index, boxes, region));
    }

    @Test
    void wideSparseQueryDoesNotWalkBillionsOfEmptySectionsOrDuplicateOverlap() {
        var index = new SectionPositionIndex();
        var left = new BlockPos(-30_000_000, -64, -30_000_000);
        var right = new BlockPos(30_000_000, 319, 30_000_000);
        index.add(left.asLong());
        index.add(right.asLong());
        index.add(BlockPos.ZERO.asLong());
        index.add(left.asLong());
        var boxes = List.of(new PrinterBox(left), new PrinterBox(right), new PrinterBox(right));
        var region = region(0, 64, 0, 50_000_000);
        assertTimeout(Duration.ofSeconds(2), () -> {
            var visited = new ArrayList<Long>();
            assertEquals(2, index.forEachInRange(boxes, region, visited::add));
            assertEquals(2, visited.size());
            assertCursorEquals(reference(List.of(left, right), boxes, region),
                    new IndexedPositionCursor(index, boxes, region));
        });
    }

    @Test
    void clearAndRepopulationCannotLeakOldPositions() {
        var index = new SectionPositionIndex();
        var box = new PrinterBox(-100, -100, -100, 100, 100, 100);
        var region = region(0, 0, 0, 200);
        index.add(BlockPos.asLong(-17, -17, -17));
        index.clear();
        index.add(BlockPos.asLong(16, 16, 16));
        assertCursorEquals(List.of(new BlockPos(16, 16, 16)), new IndexedPositionCursor(index, List.of(box), region));
        index.clear();
        assertEquals(0, index.forEachInRange(List.of(box), region, pos -> fail("cleared position")));
    }

    @Test
    void emptySelectionAndNonIntersectingSelectionVisitNothing() {
        var index = new SectionPositionIndex();
        index.add(0L);
        assertEquals(0, index.forEachInRange(List.of(), region(0, 0, 0, 8), pos -> fail("empty query")));
        assertEquals(0, index.forEachInRange(List.of(new PrinterBox(100, 100, 100, 200, 200, 200)),
                region(0, 0, 0, 8), pos -> fail("outside radius")));
    }
}
