//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.scan.ScanIntegrationFixture.drain;
import static org.junit.jupiter.api.Assertions.*;

class SchematicReplacementScanTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @EnumSource(PrintEntry.class)
    void sameCoordinateReplacementRefreshesCachedClassificationWithoutAWorldUpdate(PrintEntry entry) throws Exception {
        try (var fixture = new ScanIntegrationFixture(1)) {
            fixture.level.world.put(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());
            Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(entry.breakExtra);
            fixture.tick(10);
            assertTrue(drain(entry.scan(fixture, "print")).isEmpty());
            int before = fixture.level.readsAt(BlockPos.ZERO);

            fixture.replaceTarget(Blocks.STONE.defaultBlockState());
            fixture.tick(11);

            assertEquals(List.of(BlockPos.ZERO), drain(entry.scan(fixture, "print")),
                    "Schematic replacement alone must invalidate an observed matching classification");
            assertTrue(fixture.level.readsAt(BlockPos.ZERO) > before);

            fixture.replaceTarget(Blocks.COBBLESTONE.defaultBlockState());
            fixture.tick(12);
            assertTrue(drain(entry.scan(fixture, "print")).isEmpty(),
                    "Replacing the target with its matching state must also discard positive cached flags");
        }
    }

    @Test void oneOwnerDetectingReplacementReopensEveryCompletedPrintOwnerInTheSameTick() throws Exception {
        try (var fixture = new ScanIntegrationFixture(1)) {
            fixture.level.defaultState = Blocks.COBBLESTONE.defaultBlockState();
            fixture.tick(10);
            List<String> owners = List.of("print_primary", "print_secondary", "otherOwner");
            for (String owner : owners) assertTrue(fixture.print(owner).isEmpty());

            fixture.replaceTarget(Blocks.STONE.defaultBlockState());

            assertAll(owners.stream().map(owner -> () ->
                    assertEquals(List.of(BlockPos.ZERO), fixture.print(owner),
                            owner + " must observe the new schematic even after another owner detected it")));
        }
    }

    @Test void exhaustedSortedQueueDetectsReplacementBeforeItsCleanRefillGate() throws Exception {
        try (var fixture = new ScanIntegrationFixture(1)) {
            fixture.level.defaultState = Blocks.COBBLESTONE.defaultBlockState();
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            fixture.tick(10);
            assertTrue(drain(queue.iterable(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)).isEmpty());
            assertEquals(ScanAvailability.COMPLETE, queue.availability());

            fixture.replaceTarget(Blocks.STONE.defaultBlockState());
            fixture.tick(11);

            assertEquals(List.of(BlockPos.ZERO), drain(queue.iterable(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)),
                    "A clean queue must still discover a new schematic snapshot without a real-world block event");
        }
    }

    @Test void replacementPreservesMineFillAndFluidProgressAndTheirWorldClassifications() throws Exception {
        try (var fixture = new ScanIntegrationFixture(1)) {
            fixture.level.world.put(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());
            Map<ScanIntent, PrinterBox> boxes = Map.of(
                    ScanIntent.MINE, new PrinterBox(0, 4, 0, 2, 4, 0),
                    ScanIntent.FILL, new PrinterBox(0, 6, 0, 2, 6, 0),
                    ScanIntent.FLUID, new PrinterBox(0, 8, 0, 2, 8, 0));
            for (int x = 0; x < 3; x++) {
                fixture.level.world.put(new BlockPos(x, 4, 0), Blocks.STONE.defaultBlockState());
                fixture.level.world.put(new BlockPos(x, 5, 0), Blocks.STONE.defaultBlockState());
                fixture.level.world.put(new BlockPos(x, 8, 0), Blocks.WATER.defaultBlockState());
            }
            fixture.tick(10);
            assertTrue(fixture.print("print").isEmpty());
            Map<ScanIntent, BlockPos> consumed = new HashMap<>();
            Map<ScanIntent, Integer> reads = new HashMap<>();
            for (ScanIntent intent : List.of(ScanIntent.MINE, ScanIntent.FILL, ScanIntent.FLUID)) {
                var source = fixture.scan(intent.name(), List.of(boxes.get(intent)), 1, intent);
                List<BlockPos> first = drain(source);
                assertEquals(1, first.size());
                assertEquals(ScanAvailability.PAUSED, ((ScanCandidateIterable) source).availability());
                consumed.put(intent, first.getFirst());
                reads.put(intent, fixture.level.readsAt(first.getFirst()));
            }

            fixture.replaceTarget(Blocks.STONE.defaultBlockState());
            fixture.print("print");

            for (ScanIntent intent : List.of(ScanIntent.MINE, ScanIntent.FILL, ScanIntent.FLUID)) {
                PrinterBox box = boxes.get(intent);
                List<BlockPos> remaining = drain(fixture.scan(intent.name(), List.of(box), 0, intent));
                List<BlockPos> expected = new ArrayList<>();
                for (int x = 0; x < 3; x++) {
                    BlockPos pos = new BlockPos(x, box.minY, 0);
                    if (!pos.equals(consumed.get(intent))) expected.add(pos);
                }
                assertEquals(expected, remaining, intent + " must continue after its already-consumed prefix");
                assertFalse(remaining.contains(consumed.get(intent)), intent + " must not be restarted by PRINT invalidation");

                BlockPos first = consumed.get(intent);
                PrinterBox firstOnly = new PrinterBox(first.getX(), first.getY(), first.getZ(),
                        first.getX(), first.getY(), first.getZ());
                assertEquals(List.of(first), drain(fixture.scan(intent.name() + "_cached", List.of(firstOnly), 0, intent)));
                assertEquals(reads.get(intent).intValue(), fixture.level.readsAt(first),
                        intent + " classification does not depend on the schematic and must remain cached");
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void replacementDiscardsAFullOldBufferEvenWhenAnotherOwnerDetectsItFirst(boolean otherOwnerFirst) throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            fixture.tick(10);
            queue.iterable(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);
            assertEquals(256, fixture.cache.metricsFor("print").acceptedTargets());
            var replacement = fixture.newChunk(1);
            replacement.getSections()[4].setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState());
            BlockPos target = new BlockPos(15, 3, 15);
            replacement.getSections()[4].setBlockState(15, 3, 15, Blocks.STONE.defaultBlockState());
            fixture.chunks.put(0L, replacement);
            if (otherOwnerFirst) assertEquals(List.of(target), fixture.print("other_owner"));
            assertEquals(List.of(target), drain(queue.iterable(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)),
                    "A same-tick replacement must discard buffered old coordinates before the full-buffer gate");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"level", "epoch", "clear"})
    void resetDiscardsBufferedTargetsEvenWhenTheNewWorldClockIsLower(String reset) throws Exception {
        try (var fixture = new ScanIntegrationFixture(513)) {
            var queue = new SortedSchematicTargetQueue(fixture.engine());
            fixture.tick(1_000);
            queue.iterable(fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0);
            switch (reset) {
                case "level" -> fixture.level = ScanIntegrationFixture.newLevel();
                case "epoch" -> fixture.epoch = fixture.epoch.next();
                case "clear" -> fixture.cache.clear();
                default -> throw new AssertionError(reset);
            }
            for (BlockPos pos : ScanIntegrationFixture.positions(513)) {
                fixture.level.world.put(pos, fixture.schematic.getBlockState(pos));
            }
            fixture.level.world.put(BlockPos.ZERO, Blocks.AIR.defaultBlockState());
            fixture.tick(10);
            assertEquals(List.of(BlockPos.ZERO), drain(queue.iterable(
                    fixture.boxes, fixture.level, fixture.schematic, fixture.player, 0)),
                    "Neither the previous buffer nor its larger fill tick may survive a runtime reset");
        }
    }

    @ParameterizedTest
    @EnumSource(Reset.class)
    void worldSchematicAndEpochResetsDiscardOldCursorsAndCachedFlags(Reset reset) throws Exception {
        try (var fixture = new ScanIntegrationFixture(3)) {
            fixture.level.world.put(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState());
            fixture.tick(10);
            assertTrue(drain(fixture.scan("print_cached", List.of(new PrinterBox(BlockPos.ZERO)),
                    0, ScanIntent.PRINT)).isEmpty());
            assertEquals(1, fixture.level.readsAt(BlockPos.ZERO),
                    "The matching target must have an observed zero classification before the reset");
            var old = fixture.scan("print", fixture.boxes, 0, ScanIntent.PRINT).iterator();
            assertTrue(old.hasNext());
            old.next();

            switch (reset) {
                case LEVEL -> fixture.level = ScanIntegrationFixture.newLevel();
                case SCHEMATIC -> {
                    fixture.level.world.clear();
                    fixture.schematic = SchematicIndexFixture.world(new ConcurrentHashMap<>(fixture.chunks));
                }
                case EPOCH -> {
                    fixture.level.world.clear();
                    fixture.epoch = fixture.epoch.next();
                }
            }
            fixture.tick(11);

            assertFalse(old.hasNext(), "An iterator from the old runtime identity must not emit more work");
            List<BlockPos> current = fixture.print("print");
            assertEquals(3, current.size());
            assertTrue(current.contains(BlockPos.ZERO), "A zero classification from the old identity must be discarded");
        }
    }

    private enum Reset { LEVEL, SCHEMATIC, EPOCH }

    private enum PrintEntry {
        LIST(false), SINGLE(false), UNBOUNDED(false), BREAK_EXTRA_LIST(true), BREAK_EXTRA_SINGLE(true);

        private final boolean breakExtra;

        PrintEntry(boolean breakExtra) { this.breakExtra = breakExtra; }

        Iterable<BlockPos> scan(ScanIntegrationFixture fixture, String owner) {
            return switch (this) {
                case LIST, BREAK_EXTRA_LIST -> fixture.scan(owner, fixture.boxes, 0, ScanIntent.PRINT);
                case SINGLE, BREAK_EXTRA_SINGLE -> fixture.cache.iterable(owner, fixture.boxes.getFirst(),
                        fixture.level, fixture.schematic, null, 0, ScanIntent.PRINT, pos -> true);
                case UNBOUNDED -> fixture.cache.unboundedIterable(owner, fixture.boxes.getFirst(),
                        fixture.level, fixture.schematic, null, ScanIntent.PRINT, pos -> true, pos -> true);
            };
        }
    }
}
//#endif
