package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.core.scan.ConstructionOrder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.scan.RawPrinterBoxFixture.rawBox;
import static org.junit.jupiter.api.Assertions.*;

class GlobalConstructionPositionCursorTest {
    @Test void finishesTheWholeRowAndLayerAcrossEightAndSixteenBlockBoundaries() {
        var cursor = new ConstructionPositionCursor(List.of(rawBox(0, 0, 0, 17, 1, 1)),
                region(0, 0, 0, 100), new ConstructionOrder(0, 0, false));
        List<BlockPos> expected = new ArrayList<>();
        for (int y = 0; y <= 1; y++) {
            for (int x = 0; x <= 17; x++) expected.add(new BlockPos(x, y, 0));
            for (int x = 17; x >= 0; x--) expected.add(new BlockPos(x, y, 1));
        }
        List<BlockPos> actual = drain(cursor);
        assertEquals(expected, actual);
        assertEquals(72, new HashSet<>(actual).size());
        assertTrue(actual.indexOf(new BlockPos(8, 0, 0)) < actual.indexOf(new BlockPos(0, 1, 0)));
    }

    @Test void negativeRowsAndDescendingLayersKeepEveryOverlappingCoordinateExactlyOnce() {
        var cursor = new ConstructionPositionCursor(List.of(rawBox(-17, -1, -1, 17, 0, 0),
                        rawBox(-3, -1, -1, 10, 0, 0)),
                region(0, 0, 0, 100), new ConstructionOrder(900, -900, true));
        List<BlockPos> expected = new ArrayList<>();
        for (int y = 0; y >= -1; y--) {
            for (int x = 17; x >= -17; x--) expected.add(new BlockPos(x, y, -1));
            for (int x = -17; x <= 17; x++) expected.add(new BlockPos(x, y, 0));
        }
        List<BlockPos> actual = drain(cursor);
        assertEquals(expected, actual);
        assertEquals(140, new HashSet<>(actual).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skippingASectionPreservesOtherColumnsAndEveryRemainingGlobalRow(boolean descending) {
        var cursor = new ConstructionPositionCursor(List.of(rawBox(0, 14, 0, 31, 17, 1),
                        rawBox(8, 15, 0, 23, 17, 1)),
                region(0, 16, 0, 100), new ConstructionOrder(0, 0, descending));
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        assertEquals(PositionCursor.PollResult.AVAILABLE, cursor.poll(target));
        assertEquals(new BlockPos(0, descending ? 17 : 14, 0), target);
        cursor.skipSection();

        List<BlockPos> expected = new ArrayList<>();
        for (int layer = 0; layer < 4; layer++) {
            int y = descending ? 17 - layer : 14 + layer;
            for (int z = 0; z <= 1; z++) {
                for (int offset = 0; offset < 32; offset++) {
                    int x = z == 0 ? offset : 31 - offset;
                    if (x < 16 && (descending ? y >= 16 : y < 16)) continue;
                    expected.add(new BlockPos(x, y, z));
                }
            }
        }
        List<BlockPos> actual = drain(cursor);
        assertEquals(expected, actual,
                "A zero section must not skip another X/Z column or reappear through an overlapping box");
        assertEquals(expected.size(), new HashSet<>(actual).size());
    }

    @Test void hugeRowsAndTangentStripsMakeProgressWithoutMaterializingSectionColumns() {
        assertTimeout(Duration.ofSeconds(10), () -> {
            var row = new ConstructionPositionCursor(List.of(rawBox(-33_554_432, 0, 0, 33_554_431, 0, 0)),
                    region(0, 0, 0, Integer.MAX_VALUE), new ConstructionOrder(0, 0, false));
            BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
            for (int offset = 0; offset < 32; offset++) {
                assertEquals(PositionCursor.PollResult.AVAILABLE, row.poll(target));
                assertEquals(new BlockPos(-33_554_432 + offset, 0, 0), target);
            }
            int radius = 33_554_424;
            var tangent = new ConstructionPositionCursor(List.of(rawBox(0, 0, 0, 0, 0, radius)),
                    region(radius, 0, radius, radius), new ConstructionOrder(0, 0, false));
            assertEquals(List.of(new BlockPos(0, 0, radius)), drain(tangent));
        });
    }

    private static ScanRegion region(int x, int y, int z, int band) {
        return new ScanRegion(x, y, z, -2_097_152, -128, -2_097_152,
                2_097_151, 127, 2_097_151, band);
    }

    private static List<BlockPos> drain(PositionCursor cursor) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        while (cursor.poll(target) == PositionCursor.PollResult.AVAILABLE) result.add(target.immutable());
        return result;
    }
}
