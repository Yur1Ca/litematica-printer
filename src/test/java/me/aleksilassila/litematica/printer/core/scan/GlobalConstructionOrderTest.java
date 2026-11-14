package me.aleksilassila.litematica.printer.core.scan;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalConstructionOrderTest {
    @Test void globalLayersAndRowsRemainContinuousAcrossFormerTileAndSectionBoundaries() {
        var expected = java.util.List.of(
                new BlockPos(0, 0, 0), new BlockPos(8, 0, 0), new BlockPos(16, 0, 0),
                new BlockPos(17, 0, 0), new BlockPos(17, 0, 1), new BlockPos(8, 0, 1),
                new BlockPos(0, 0, 1), new BlockPos(0, 1, 0));
        for (ConstructionOrder order : java.util.List.of(new ConstructionOrder(0, 0, false),
                new ConstructionOrder(1000, -999, false))) {
            var actual = new ArrayList<>(expected);
            Collections.reverse(actual);
            actual.sort((first, second) -> order.compare(first.asLong(), second.asLong()));
            assertEquals(expected, actual,
                    "X finishes the whole Z row, then Z finishes the whole Y layer, independently of an old anchor");
        }
    }

    @Test void negativeRowsUseWorldParityAndMiningOnlyReversesTheLayerOrder() {
        var expected = java.util.List.of(
                new BlockPos(-17, 1, -2), new BlockPos(16, 1, -2),
                new BlockPos(16, 1, -1), new BlockPos(0, 1, -1), new BlockPos(-17, 1, -1),
                new BlockPos(-17, 0, -2), new BlockPos(16, 0, -2));
        var actual = new ArrayList<>(expected);
        Collections.reverse(actual);
        var order = new ConstructionOrder(-100, 100, true);
        actual.sort((first, second) -> order.compare(first.asLong(), second.asLong()));
        assertEquals(expected, actual);
    }
}
