package me.aleksilassila.litematica.printer.core.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderedPositionsTest {
    @Test void keepsEveryCandidateAndUsesDistanceThenYThenXThenZ() {
        long[] input = {pack(1, 0, 0), pack(0, 0, 1), pack(0, 1, 0), pack(0, 0, -1),
                pack(-1, 0, 0), pack(0, -1, 0), pack(0, 0, 0), pack(1, 1, 0)};
        var order = new OrderedPositions(input, 0, 0, 0);
        assertArrayEquals(new long[]{pack(0, 0, 0), pack(0, -1, 0), pack(-1, 0, 0),
                pack(0, 0, -1), pack(0, 0, 1), pack(1, 0, 0), pack(0, 1, 0), pack(1, 1, 0)},
                order.copyPositions());
        assertEquals(8, order.size());
        assertEquals(2, order.distanceAt(7));
    }

    @Test void doesNotRetainOrExposeMutableInputEvenWhenEmptyOrDuplicate() {
        long[] input = {pack(5, -64, -9), pack(5, -64, -9), pack(7, -64, -9)};
        long[] original = input.clone();
        var order = new OrderedPositions(input, 5, -64, -9);
        assertArrayEquals(original, input);
        input[0] = pack(99, 0, 0);
        order.copyPositions()[0] = pack(88, 0, 0);
        assertArrayEquals(original, order.copyPositions());
        assertEquals(0, order.distanceAt(0));
        assertEquals(4, order.distanceAt(2));
        assertEquals(0, new OrderedPositions(new long[0], 0, 0, 0).size());
    }

    @Test void completeOrderAndDistancesMatchIndependentReferenceAtCoordinateEdges() {
        Random random = new Random(0x24C0DE);
        for (int trial = 0; trial < 24; trial++) {
            int cx = trial % 2 == 0 ? -33_554_432 : 33_554_431;
            int cy = trial % 3 == 0 ? -2048 : 2047;
            int cz = trial % 4 == 0 ? -33_554_432 : 33_554_431;
            List<BlockPos> reference = new ArrayList<>();
            reference.add(new BlockPos(-33_554_432, -2048, -33_554_432));
            reference.add(new BlockPos(33_554_431, 2047, 33_554_431));
            for (int i = 0; i < 2048; i++) reference.add(new BlockPos(
                    random.nextInt(67_108_864) - 33_554_432,
                    random.nextInt(4096) - 2048, random.nextInt(67_108_864) - 33_554_432));
            long[] input = reference.stream().mapToLong(BlockPos::asLong).toArray();
            reference.sort(Comparator.comparingLong((BlockPos p) -> distance(p, cx, cy, cz))
                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ));
            var order = new OrderedPositions(input, cx, cy, cz);
            assertEquals(reference.size(), order.size());
            for (int i = 0; i < order.size(); i++) {
                assertEquals(reference.get(i).asLong(), order.positionAt(i));
                assertEquals(distance(reference.get(i), cx, cy, cz), order.distanceAt(i));
            }
        }
    }

    private static long pack(int x, int y, int z) { return BlockPos.asLong(x, y, z); }

    private static long distance(BlockPos pos, int x, int y, int z) {
        long dx = (long) pos.getX() - x, dy = (long) pos.getY() - y, dz = (long) pos.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
