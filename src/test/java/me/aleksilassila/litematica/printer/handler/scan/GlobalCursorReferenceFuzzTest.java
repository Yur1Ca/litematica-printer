package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.core.scan.ConstructionOrder;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.scan.RawPrinterBoxFixture.rawBox;
import static org.junit.jupiter.api.Assertions.*;

/** Small exhaustive references, independent of the cursor's clipping and rectangle splitting. */
class GlobalCursorReferenceFuzzTest {
    private static final long SEED = 0x594A585F20261114L;
    private static final int CASES = 32;

    @Test void seededOverlappingBoxesAndSectionSkipsMatchLiteralGlobalRows() {
        Random random = new Random(SEED);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            int boundaryX = (random.nextInt(4) - 2) * 16;
            int boundaryY = (random.nextInt(4) - 2) * 16;
            int boundaryZ = (random.nextInt(4) - 2) * 16;
            PrinterBox first = rawBox(
                    boundaryX - 1 - random.nextInt(6), boundaryY - 1 - random.nextInt(4),
                    boundaryZ - 1 - random.nextInt(6), boundaryX + 9 + random.nextInt(10),
                    boundaryY + 3 + random.nextInt(5), boundaryZ + 9 + random.nextInt(10));
            List<PrinterBox> boxes = new ArrayList<>();
            boxes.add(first);
            boxes.add(shift(first, random.nextInt(11) - 5, random.nextInt(5) - 2, random.nextInt(11) - 5));
            if (caseIndex % 2 == 0) {
                // Include exact duplicates as well as independently shifted overlapping boxes.
                boxes.add(caseIndex % 4 == 0 ? first
                        : shift(first, random.nextInt(11) - 5, random.nextInt(5) - 2, random.nextInt(11) - 5));
            }
            long enumeratedVolume = boxes.stream().mapToLong(box ->
                    (long) (box.maxX - box.minX + 1) * (box.maxY - box.minY + 1)
                            * (box.maxZ - box.minZ + 1)).sum();
            assertTrue(enumeratedVolume <= 30_000, "Keep each exhaustive reference small");
            int band = caseIndex % 31;
            int centerX = boundaryX + (caseIndex == 0 ? 0 : random.nextInt(21) - 10);
            int centerY = boundaryY + (caseIndex == 0 ? 0 : random.nextInt(13) - 6);
            int centerZ = boundaryZ + (caseIndex == 0 ? 0 : random.nextInt(21) - 10);
            ScanRegion region = new ScanRegion(centerX, centerY, centerZ,
                    -128, -128, -128, 127, 127, 127, band);
            Set<BlockPos> eligible = enumerate(boxes, centerX, centerY, centerZ, band);

            for (boolean descending : List.of(false, true)) {
                List<BlockPos> reference = new ArrayList<>(eligible);
                reference.sort((a, b) -> {
                    int byY = descending ? Integer.compare(b.getY(), a.getY())
                            : Integer.compare(a.getY(), b.getY());
                    if (byY != 0) return byY;
                    int byZ = Integer.compare(a.getZ(), b.getZ());
                    if (byZ != 0) return byZ;
                    return Math.floorMod(a.getZ(), 2) == 0 ? Integer.compare(a.getX(), b.getX())
                            : Integer.compare(b.getX(), a.getX());
                });
                for (boolean skipSections : List.of(false, true)) {
                    check(boxes, region, reference, descending, skipSections, caseIndex);
                }
            }
        }
    }

    private static void check(List<PrinterBox> boxes, ScanRegion region, List<BlockPos> reference,
                              boolean descending, boolean skipSections, int caseIndex) {
        String label = "seed=" + SEED + ", case=" + caseIndex + ", descending=" + descending
                + ", skips=" + skipSections + ", center=" + region.centerX() + "/" + region.centerY()
                + "/" + region.centerZ() + ", band=" + region.maxDistanceBand();
        List<BlockPos> expected = new ArrayList<>(reference);
        List<BlockPos> actual = new ArrayList<>();
        Set<BlockPos> emitted = new HashSet<>();
        Set<Section> encountered = new HashSet<>();
        Random skips = new Random(SEED + caseIndex * 37L + (descending ? 1L : 0L));
        var cursor = new ConstructionPositionCursor(boxes, region,
                new ConstructionOrder(caseIndex - 20, 31 - caseIndex, descending));
        var target = new BlockPos.MutableBlockPos();
        try {
            // A broken duplicate/infinite traversal fails after at most the reference size + 1 polls.
            for (int index = 0; index < expected.size(); index++) {
                assertEquals(PositionCursor.PollResult.AVAILABLE, cursor.poll(target), label + ", index=" + index);
                BlockPos position = target.immutable();
                assertEquals(expected.get(index), position, label + ", index=" + index);
                assertTrue(emitted.add(position), label + ": duplicate " + position);
                actual.add(position);
                Section section = section(position);
                if (skipSections && encountered.add(section) && skips.nextInt(3) == 0) {
                    cursor.skipSection();
                    // Keep the returned prefix: skipSection only removes future points in this section.
                    expected.subList(index + 1, expected.size()).removeIf(pos -> section(pos).equals(section));
                }
            }
            assertEquals(PositionCursor.PollResult.COMPLETE, cursor.poll(target), label + ": unexpected tail");
            assertEquals(expected, actual, label);
            assertEquals(expected.size(), emitted.size(), label + ": missing or duplicate positions");
            assertTrue(cursor.isComplete(), label);
        } finally {
            cursor.close();
        }
    }

    private static Set<BlockPos> enumerate(List<PrinterBox> boxes, int centerX, int centerY, int centerZ, int band) {
        Set<BlockPos> result = new HashSet<>();
        for (PrinterBox box : boxes) {
            for (int x = box.minX; x <= box.maxX; x++) {
                for (int y = box.minY; y <= box.maxY; y++) {
                    for (int z = box.minZ; z <= box.maxZ; z++) {
                        long dx = x - (long) centerX, dy = y - (long) centerY, dz = z - (long) centerZ;
                        if (dx * dx + dy * dy + dz * dz <= (long) band * band) result.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return result;
    }

    private static PrinterBox shift(PrinterBox box, int x, int y, int z) {
        return rawBox(box.minX + x, box.minY + y, box.minZ + z,
                box.maxX + x, box.maxY + y, box.maxZ + z);
    }

    private static Section section(BlockPos pos) {
        return new Section(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getY(), 16), Math.floorDiv(pos.getZ(), 16));
    }

    private record Section(int x, int y, int z) { }
}
