package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.core.scan.ConstructionOrder;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
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
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void ownerStartsAtTheNearestThreeDimensionalPointAndKeepsTheOriginalLayerDirection() throws Exception {
        List<PrinterBox> boxes = List.of(rawBox(0, 0, 0, 4, 2, 0));
        for (boolean descending : List.of(false, true)) {
            ConstructionOrder order = ownerOrder(boxes, boxes.getFirst(), descending);
            var cursor = new ConstructionPositionCursor(boxes, region(2, 1, 0, 100), order);
            List<BlockPos> actual = drain(cursor);
            assertEquals(List.of(new BlockPos(2, 1, 0), new BlockPos(3, 1, 0), new BlockPos(4, 1, 0),
                    new BlockPos(0, descending ? 0 : 2, 0), new BlockPos(1, descending ? 0 : 2, 0)),
                    actual.subList(0, 5));
            assertEquals(15, actual.size());
            assertEquals(15, new HashSet<>(actual).size());
            assertEquals(List.of(new BlockPos(0, 1, 0), new BlockPos(1, 1, 0)), actual.subList(13, 15));
        }
    }

    @Test void negativeOverlappingSphereClipsMatchAnIndependentlyRotatedReference() {
        List<PrinterBox> boxes = List.of(rawBox(-7, -2, -7, 5, 3, 5), rawBox(-2, -1, -3, 7, 1, 3));
        ScanRegion region = region(-1, 0, -2, 5);
        for (boolean descending : List.of(false, true)) {
            ConstructionOrder order = ConstructionPositionCursor.nearestOrder(boxes, region, descending);
            var cursor = new ConstructionPositionCursor(boxes, region, order);
            List<BlockPos> expected = rotateAt(reference(boxes, region, descending), new BlockPos(-1, 0, -2));
            assertEquals(expected.getFirst(), cursor.peekPosition());
            assertEquals(0, cursor.peekDistanceSqr());
            assertEquals(expected, drain(cursor));
        }
    }

    @Test void nearestStartClampsEachBoxAndBreaksDistanceTiesWithTheOriginalOrder() {
        List<PrinterBox> boxes = List.of(rawBox(3, 2, 0, 8, 4, 2), rawBox(-8, -4, -2, -3, -2, 0),
                rawBox(4, 4, 4, 4, 4, 4));
        ScanRegion region = region(0, 0, 0, 4);
        for (boolean descending : List.of(false, true)) {
            ConstructionOrder order = ConstructionPositionCursor.nearestOrder(boxes, region, descending);
            BlockPos expectedStart = new BlockPos(descending ? 3 : -3, descending ? 2 : -2, 0);
            var cursor = new ConstructionPositionCursor(boxes, region, order);
            assertEquals(expectedStart, cursor.peekPosition());
            assertEquals(13, cursor.peekDistanceSqr());
            assertEquals(rotateAt(reference(boxes, region, descending), expectedStart), drain(cursor));
        }
    }

    @Test void aFixedStartSurvivesOwnerRetryAndRegionMovement() throws Exception {
        List<PrinterBox> boxes = List.of(rawBox(-4, -2, -2, 4, 2, 2));
        try (var player = new PlayerScope(BlockPos.ZERO);
             var store = new ScanSessionStore((SchematicBlockIndex) null)) {
            store.getOrCreate("print", store.metrics("print"), ScanIntent.PRINT, boxes.getFirst(), boxes,
                    player.player, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
            ConstructionOrder original = store.orderFor("print");
            store.invalidate(new BlockPos(2, 1, 1));
            store.reoffer("print", new BlockPos(1, 1, 1));
            store.resetOwner("print");
            player.move(new BlockPos(2, 1, -1));
            store.getOrCreate("print", store.metrics("print"), ScanIntent.PRINT, rawBox(0, 0, 0, 4, 2, 2), boxes,
                    player.player, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
            assertSame(original, store.orderFor("print_sorted"));
            List<BlockPos> first = drain(new ConstructionPositionCursor(boxes, region(0, 0, 0, 100), original));
            List<BlockPos> moved = drain(new ConstructionPositionCursor(boxes, region(2, 1, -1, 100), original));
            assertEquals(first, moved);
            assertEquals(BlockPos.ZERO, first.getFirst());
        }
    }

    @Test void aMissingPlayerKeepsTheCanonicalOrderUntilARealPlayerEstablishesTheStart() throws Exception {
        List<PrinterBox> boxes = List.of(rawBox(0, 0, 0, 4, 2, 0));
        try (var player = new PlayerScope(new BlockPos(2, 1, 0));
             var store = new ScanSessionStore((SchematicBlockIndex) null)) {
            SectionScanSession fallback = store.getOrCreate("print", store.metrics("print"), ScanIntent.PRINT,
                    boxes.getFirst(), boxes, null, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
            ConstructionOrder canonical = store.orderFor("print");
            assertFalse(canonical.hasStartPosition());
            assertEquals(new BlockPos(0, 0, 0),
                    new ConstructionPositionCursor(boxes, region(2, 1, 0, 100), canonical).peekPosition());
            store.getOrCreate("print", store.metrics("print"), ScanIntent.PRINT, boxes.getFirst(), boxes,
                    player.player, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
            assertFalse(fallback.belongsTo(RuntimeEpoch.INITIAL));
            assertEquals(new BlockPos(2, 1, 0), BlockPos.of(store.orderFor("print").startPosition()));
        }
    }

    @Test void anEmptyInitialSphereCapturesItsFirstValidStartAndReplacesEveryOwnerSession() throws Exception {
        List<PrinterBox> boxes = List.of(rawBox(100, 0, 0, 104, 2, 0));
        try (var player = new PlayerScope(BlockPos.ZERO);
             var store = new ScanSessionStore((SchematicBlockIndex) null)) {
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            SectionScanSession direct = store.getOrCreate("print", store.metrics("print"), ScanIntent.PRINT,
                    boxes.getFirst(), boxes, player.player, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
            SectionScanSession sorted = store.getOrCreate("print_sorted", store.metrics("print"), ScanIntent.PRINT,
                    boxes.getFirst(), boxes, player.player, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
            assertFalse(store.orderFor("print").hasStartPosition());
            player.move(new BlockPos(102, 1, 0));
            store.getOrCreate("print", store.metrics("print"), ScanIntent.PRINT, boxes.getFirst(), boxes,
                    player.player, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
            assertFalse(direct.belongsTo(RuntimeEpoch.INITIAL));
            assertFalse(sorted.belongsTo(RuntimeEpoch.INITIAL));
            assertEquals(new BlockPos(102, 1, 0), BlockPos.of(store.orderFor("print_sorted").startPosition()));
        }
    }

    @Test void skippingTheStartingSectionAlsoRemovesItsWrappedPrefixAcrossOverlaps() {
        for (int startY : List.of(-2, 14, 16)) {
            List<PrinterBox> boxes = List.of(rawBox(0, startY - 20, 0, 32, startY + 20, 1),
                    rawBox(4, startY - 19, 0, 30, startY + 19, 1));
            ScanRegion region = region(8, startY, 0, 100);
            for (boolean descending : List.of(false, true)) {
                BlockPos start = new BlockPos(8, startY, 0);
                var cursor = new ConstructionPositionCursor(boxes, region,
                        new ConstructionOrder(start.getX(), start.getY(), start.getZ(), descending));
                List<BlockPos> expected = rotateAt(reference(boxes, region, descending), start);
                var target = new BlockPos.MutableBlockPos();
                assertEquals(PositionCursor.PollResult.AVAILABLE, cursor.poll(target));
                assertEquals(expected.removeFirst(), target);
                cursor.skipSection();
                expected.removeIf(pos -> pos.getX() >> 4 == start.getX() >> 4
                        && pos.getY() >> 4 == startY >> 4 && pos.getZ() >> 4 == 0);
                assertEquals(expected, drain(cursor), "startY=" + startY + ", descending=" + descending);
            }
        }
    }

    @Test void aHugeRotatedRowFindsTheMiddleWithoutExpandingThePrefixOrColumns() {
        assertTimeout(Duration.ofSeconds(3), () -> {
            List<PrinterBox> boxes = List.of(rawBox(-33_554_432, 0, 0, 33_554_431, 0, 0));
            ScanRegion region = region(0, 0, 0, Integer.MAX_VALUE);
            var cursor = new ConstructionPositionCursor(boxes, region,
                    ConstructionPositionCursor.nearestOrder(boxes, region, false));
            var target = new BlockPos.MutableBlockPos();
            for (int x = 0; x < 32; x++) {
                BlockPos expected = new BlockPos(x, 0, 0);
                for (int preview = 0; preview < 20; preview++) assertEquals(expected, cursor.peekPosition());
                assertEquals((long) x * x, cursor.peekDistanceSqr());
                assertEquals(PositionCursor.PollResult.AVAILABLE, cursor.poll(target));
                assertEquals(expected, target);
            }
            cursor.close();
        });
    }

    @Test void aHugeRotatedVolumeKeepsConstructionAndItsFirstPollBounded() {
        assertTimeout(Duration.ofSeconds(3), () -> {
            List<PrinterBox> boxes = List.of(rawBox(-33_554_432, -2048, -33_554_432,
                    33_554_431, 2047, 33_554_431));
            ScanRegion region = region(0, 0, 0, Integer.MAX_VALUE);
            var cursor = new ConstructionPositionCursor(boxes, region,
                    ConstructionPositionCursor.nearestOrder(boxes, region, true));
            assertEquals(BlockPos.ZERO, cursor.peekPosition());
            var target = new BlockPos.MutableBlockPos();
            assertEquals(PositionCursor.PollResult.AVAILABLE, cursor.poll(target));
            assertEquals(BlockPos.ZERO, target);
            assertEquals(new BlockPos(1, 0, 0), cursor.peekPosition());
            cursor.close();
        });
    }

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

    private static ConstructionOrder ownerOrder(List<PrinterBox> boxes, PrinterBox envelope, boolean descending) throws Exception {
        BlockPos center = new BlockPos((envelope.minX + envelope.maxX) >> 1,
                (envelope.minY + envelope.maxY) >> 1, (envelope.minZ + envelope.maxZ) >> 1);
        try (var player = new PlayerScope(center);
             var store = new ScanSessionStore((SchematicBlockIndex) null)) {
            String owner = descending ? "mine" : "print";
            store.getOrCreate(owner, store.metrics(owner), descending ? ScanIntent.MINE : ScanIntent.PRINT,
                    envelope, boxes, player.player, RuntimeEpoch.INITIAL, () -> 0L, () -> 0L);
            return store.orderFor(owner);
        }
    }

    private static final class PlayerScope implements AutoCloseable {
        final LocalPlayer player;
        private final boolean previousRange = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue();
        private final Object previousShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        private final int previousWorkRange = Configs.Core.WORK_RANGE.getIntegerValue();

        PlayerScope(BlockPos position) throws Exception {
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            this.player = (LocalPlayer) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(LocalPlayer.class);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(false);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.CUBE);
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            this.move(position);
        }

        void move(BlockPos position) throws Exception {
            var vector = Entity.class.getDeclaredField("position");
            vector.setAccessible(true);
            vector.set(this.player, new Vec3(position.getX(), position.getY(), position.getZ()));
            var block = Entity.class.getDeclaredField("blockPosition");
            block.setAccessible(true);
            block.set(this.player, position);
            var eyeHeight = Entity.class.getDeclaredField("eyeHeight");
            eyeHeight.setAccessible(true);
            eyeHeight.setFloat(this.player, 0.0F);
        }

        @Override public void close() {
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(this.previousRange);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue((RadiusShapeType) this.previousShape);
            Configs.Core.WORK_RANGE.setIntegerValue(this.previousWorkRange);
        }
    }

    private static List<BlockPos> rotateAt(List<BlockPos> reference, BlockPos start) {
        int index = reference.indexOf(start);
        assertTrue(index >= 0, "The literal starting point must belong to the independent reference");
        List<BlockPos> rotated = new ArrayList<>(reference.subList(index, reference.size()));
        rotated.addAll(reference.subList(0, index));
        return rotated;
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
