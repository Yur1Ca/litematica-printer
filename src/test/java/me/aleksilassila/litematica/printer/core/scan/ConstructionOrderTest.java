package me.aleksilassila.litematica.printer.core.scan;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionOrderTest {
    @Test void fixedThreeDimensionalStartRotatesTheWholeSequenceExactlyOnce() {
        for (boolean descending : List.of(false, true)) {
            List<BlockPos> expected = List.of(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0),
                    new BlockPos(2, 0, 1), new BlockPos(0, 0, 1),
                    new BlockPos(0, descending ? -1 : 1, 0), new BlockPos(2, descending ? -1 : 1, 0),
                    new BlockPos(0, descending ? 1 : -1, 0), new BlockPos(2, descending ? 1 : -1, 0),
                    new BlockPos(0, 0, 0));
            ConstructionOrder order = new ConstructionOrder(1, 0, 0, descending);
            assertOrdered(expected, order);
            for (int first = 0; first < expected.size(); first++) {
                for (int second = first; second < expected.size(); second++) {
                    assertTrue(order.compare(expected.get(first).asLong(), expected.get(second).asLong()) <= 0);
                    assertEquals(-Integer.signum(order.compare(expected.get(second).asLong(), expected.get(first).asLong())),
                            Integer.signum(order.compare(expected.get(first).asLong(), expected.get(second).asLong())));
                }
            }
        }
    }

    @Test void anOddNegativeStartingRowStillRunsTowardsDecreasingX() {
        assertOrdered(List.of(new BlockPos(-2, -1, -1), new BlockPos(-3, -1, -1),
                        new BlockPos(-3, -1, 0), new BlockPos(-1, -1, 0),
                        new BlockPos(-1, -1, -1)),
                new ConstructionOrder(-2, -1, -1, false));
    }

    @Test void rotatedPackedCoordinateEdgesKeepTheirSignedCoordinatesAndDistinctRanks() {
        BlockPos start = new BlockPos(33_554_431, 0, -33_554_432);
        assertOrdered(List.of(start, new BlockPos(-33_554_432, 0, 33_554_431),
                        new BlockPos(33_554_431, 2047, 33_554_431),
                        new BlockPos(33_554_431, -2048, 33_554_431),
                        new BlockPos(-33_554_432, 0, -33_554_432)),
                new ConstructionOrder(start.getX(), start.getY(), start.getZ(), false));
    }

    @Test void finishesLayersAndAlternatesWholeHorizontalRows() {
        List<BlockPos> expected = List.of(
                new BlockPos(0, -1, 0), new BlockPos(1, -1, 0),
                new BlockPos(1, -1, 1), new BlockPos(0, -1, 1),
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(1, 0, 1), new BlockPos(0, 0, 1));
        assertOrdered(expected, new ConstructionOrder(0, 0, false));
        assertOrdered(List.of(
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(1, 0, 1), new BlockPos(0, 0, 1),
                new BlockPos(0, -1, 0), new BlockPos(1, -1, 0),
                new BlockPos(1, -1, 1), new BlockPos(0, -1, 1)),
                new ConstructionOrder(0, 0, true));
    }

    @Test void worldRowsIgnoreFormerAnchorAndTileBoundaries() {
        List<BlockPos> expected = List.of(
                new BlockPos(-8, 0, -8), new BlockPos(0, 0, -8), new BlockPos(8, 0, -8),
                new BlockPos(-8, 0, 0), new BlockPos(0, 0, 0), new BlockPos(8, 0, 0),
                new BlockPos(-8, 0, 8), new BlockPos(0, 0, 8), new BlockPos(8, 0, 8));
        assertOrdered(expected, new ConstructionOrder(7, 7, false));
        var order = new ConstructionOrder(-1, -9, false);
        assertOrdered(expected, order);
        // Legacy metadata remains readable, but never determines construction rank.
        assertEquals(-1, order.anchorTileX());
        assertEquals(-2, order.anchorTileZ());
        assertTrue(order.compareTiles(-1, -2, -1, -1) < 0);
        assertTrue(order.compareTiles(-2, -2, 0, -2) < 0);
    }

    @Test void negativeRowsUseFloorCoordinatesInsteadOfTruncation() {
        assertOrdered(List.of(
                new BlockPos(-8, 0, -8), new BlockPos(-7, 0, -8),
                new BlockPos(-7, 0, -7), new BlockPos(-8, 0, -7)),
                new ConstructionOrder(-1, -1, false));
        var order = new ConstructionOrder(0, 0, false);
        assertTrue(order.compare(BlockPos.asLong(-1, 0, -1), BlockPos.asLong(-8, 0, -1)) < 0,
                "An odd world Z row runs towards decreasing X, including negative rows");
    }

    @Test void packedCoordinateEdgesRemainDistinctAndComparisonIsTransitive() {
        List<BlockPos> expected = List.of(
                new BlockPos(33_554_431, -2048, 33_554_431),
                new BlockPos(-33_554_432, 0, -33_554_432),
                new BlockPos(33_554_431, 0, -33_554_432),
                new BlockPos(-33_554_432, 0, 33_554_431),
                new BlockPos(33_554_431, 2047, 33_554_431));
        var order = new ConstructionOrder(33_554_431, 33_554_431, false);
        assertOrdered(expected, order);
        for (BlockPos first : expected) for (BlockPos second : expected) {
            assertEquals(-Integer.signum(order.compare(second.asLong(), first.asLong())),
                    Integer.signum(order.compare(first.asLong(), second.asLong())));
        }
        for (int first = 0; first < expected.size(); first++) {
            for (int second = first; second < expected.size(); second++) {
                for (int third = second; third < expected.size(); third++) {
                    assertTrue(order.compare(expected.get(first).asLong(), expected.get(third).asLong()) <= 0);
                }
            }
        }
    }

    private static void assertOrdered(List<BlockPos> expected, ConstructionOrder order) {
        List<BlockPos> actual = new ArrayList<>(expected);
        Collections.shuffle(actual, new Random(0xC01157));
        actual.sort((first, second) -> order.compare(first.asLong(), second.asLong()));
        assertEquals(expected, actual);
    }
}
