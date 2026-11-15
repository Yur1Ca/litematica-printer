//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.stream.IntStream;

import static me.aleksilassila.litematica.printer.handler.scan.RawPrinterBoxFixture.rawBox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondReviewOrderTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void aFiniteDirtyBurstKeepsItsOriginalRankBeforeTheRemainingCursor() throws Exception {
        try (var fixture = new ScanIntegrationFixture(0)) {
            fixture.level.defaultState = Blocks.STONE.defaultBlockState();
            var scope = List.of(rawBox(0, 0, 0, 15, 0, 0));
            fixture.tick(1);
            assertEquals(row(9), ScanIntegrationFixture.drain(fixture.scan("mine", scope, 9, ScanIntent.MINE)));

            for (int x = 0; x < 9; x++) fixture.cache.invalidate(new BlockPos(x, 0, 0));
            fixture.tick(2);

            assertEquals(row(16), ScanIntegrationFixture.drain(fixture.scan("mine", scope, 0, ScanIntent.MINE)),
                    "A finite queued burst must finish its earlier ranks before the remaining fresh row");
        }
    }

    @Test void recurringEarlyInvalidationsCannotStarveAnyFreshCoordinateOrLoseTheirFollowup() throws Exception {
        try (var fixture = new ScanIntegrationFixture(0)) {
            fixture.level.defaultState = Blocks.STONE.defaultBlockState();
            var scope = List.of(rawBox(0, 0, 0, 7, 0, 0));
            fixture.tick(1);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(fixture.scan("mine", scope, 1, ScanIntent.MINE)));

            Set<Integer> fresh = new HashSet<>();
            int lastTick = 1;
            for (int tick = 2; tick <= 20 && fresh.size() < 7; tick++) {
                fixture.cache.invalidate(BlockPos.ZERO);
                fixture.tick(tick);
                for (BlockPos pos : ScanIntegrationFixture.drain(fixture.scan("mine", scope, 1, ScanIntent.MINE))) {
                    if (pos.getX() > 0) fresh.add(pos.getX());
                }
                lastTick = tick;
            }
            assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7), fresh,
                    "Repeated invalidation of an earlier coordinate must not keep any fresh coordinate behind it forever");

            fixture.tick(lastTick + 1);
            assertTrue(ScanIntegrationFixture.drain(fixture.scan("mine", scope, 1, ScanIntent.MINE)).contains(BlockPos.ZERO),
                    "The deferred early update must receive its follow-up without another event or a repair delay");
        }
    }

    @Test void aDeferredDirtyUpdateDuringRepairReopensTheWholeScopeInsteadOfOnlyThatRepairSection() throws Exception {
        try (var fixture = new ScanIntegrationFixture(0)) {
            fixture.level.allChunksLoaded = true;
            fixture.level.defaultState = Blocks.STONE.defaultBlockState();
            var scope = List.of(rawBox(0, 0, 0, 17, 0, 0));
            fixture.tick(1);
            assertEquals(18, ScanIntegrationFixture.drain(fixture.scan("mine", scope, 0, ScanIntent.MINE)).size());

            BlockPos early = BlockPos.ZERO;
            fixture.tick(21);
            assertEquals(List.of(early), ScanIntegrationFixture.drain(fixture.scan("mine", scope, 1, ScanIntent.MINE)));
            fixture.cache.invalidate(early);
            fixture.tick(22);
            assertEquals(List.of(early), ScanIntegrationFixture.drain(fixture.scan("mine", scope, 1, ScanIntent.MINE)));
            fixture.cache.invalidate(early);
            fixture.tick(23);

            List<BlockPos> resumed = ScanIntegrationFixture.drain(fixture.scan("mine", scope, 0, ScanIntent.MINE));
            assertTrue(resumed.contains(early), "The update deferred during this repair must remain discoverable");
            assertTrue(resumed.contains(new BlockPos(16, 0, 0)) && resumed.contains(new BlockPos(17, 0, 0)),
                    "The deferred follow-up must leave the first section's repair-only cursor and cover the full scope");
        }
    }

    @Test void dirtyDeferralsBeforeTheFirstCleanFinishStillEstablishRepairCoverage() throws Exception {
        try (var fixture = new ScanIntegrationFixture(0)) {
            BlockPos missed = new BlockPos(8, 0, 0), changing = new BlockPos(10, 0, 0);
            fixture.level.world.put(changing, Blocks.STONE.defaultBlockState());
            var scope = List.of(rawBox(0, 0, 0, 15, 0, 0));
            fixture.tick(1);
            assertEquals(List.of(changing), ScanIntegrationFixture.drain(fixture.scan("mine", scope, 1, ScanIntent.MINE)));
            assertTrue(fixture.level.readsAt(missed) > 0, "The future missed update must already be cached as zero");

            // Distance two keeps the missed cell outside the changing cell's dependency invalidation.
            fixture.level.world.put(missed, Blocks.STONE.defaultBlockState());
            boolean recovered = false;
            for (int tick = 2; tick <= 80; tick++) {
                fixture.cache.invalidate(changing);
                fixture.tick(tick);
                if (ScanIntegrationFixture.drain(fixture.scan("mine", scope, 1, ScanIntent.MINE)).contains(missed)) {
                    recovered = true;
                    break;
                }
            }
            assertTrue(recovered,
                    "An unfinished initial traversal followed by dirty deferrals must still start repair and recover a missed event");
        }
    }

    @Test void nearestPrintStartSurvivesMovementResetsAndPrefetchUntilAnEpochRecapturesIt() throws Exception {
        int previousWorkRange = Configs.Core.WORK_RANGE.getIntegerValue();
        boolean previousPrefetch = Configs.Print.PRINT_SORT_TARGETS.getBooleanValue();
        try (var fixture = new ScanIntegrationFixture(0)) {
            fixture.level.allChunksLoaded = true;
            fixture.chunks.clear();
            putSchematicChunk(fixture, 9, 0);
            putSchematicChunk(fixture, 10, 0, 8);
            movePlayer(fixture, 168, 0, 0);
            var scope = List.of(rawBox(144, 0, 0, 175, 0, 0));
            var targets = new PrintTargetScheduler(new SortedSchematicTargetQueue(fixture.engine()), () -> {
                fixture.cache.resetOwner("print");
                fixture.cache.resetOwner("print_sorted");
            });
            List<BlockPos> originalOrder = List.of(new BlockPos(168, 0, 0),
                    new BlockPos(144, 0, 0), new BlockPos(160, 0, 0));
            fixture.tick(1);
            assertEquals(originalOrder, printTargets(fixture, targets, scope, true),
                    "The first prefetched batch starts nearest the player and wraps the original row once");

            movePlayer(fixture, 152.25, 0, 0);
            Configs.Core.WORK_RANGE.setIntegerValue(64);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            targets.invalidateCandidates();
            fixture.tick(2);
            assertEquals(originalOrder, printTargets(fixture, targets, scope, true),
                    "Movement, range changes and an inventory-style owner reset retain the fixed start");

            fixture.tick(3);
            assertEquals(originalOrder, printTargets(fixture, targets, scope, false),
                    "Disabling prefetch changes buffering, not the print owner's construction order");
            fixture.tick(4);
            assertEquals(originalOrder, printTargets(fixture, targets, scope, true));

            targets.clear();
            fixture.cache.resetOwner("print");
            fixture.cache.resetOwner("print_sorted");
            fixture.tick(5);
            assertEquals(originalOrder, printTargets(fixture, targets, scope, true),
                    "A feature's candidate/source reset retains the same fixed start");

            targets.clear();
            fixture.epoch = fixture.epoch.next();
            fixture.tick(6);
            assertEquals(List.of(new BlockPos(160, 0, 0), new BlockPos(168, 0, 0), new BlockPos(144, 0, 0)),
                    printTargets(fixture, targets, scope, true),
                    "A new epoch starts at the player's new geometric coordinate, then wraps the remaining row");
        } finally {
            Configs.Core.WORK_RANGE.setIntegerValue(previousWorkRange);
            Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(previousPrefetch);
        }
    }

    private static void putSchematicChunk(ScanIntegrationFixture fixture, int chunkX, int... localXs) throws Exception {
        var sections = fixture.newChunk(0).getSections();
        for (int localX : localXs) sections[4].setBlockState(localX, 0, 0, Blocks.STONE.defaultBlockState());
        fixture.chunks.put(chunkX & 0xFFFFFFFFL, SchematicIndexFixture.chunk(
                ScanIntegrationFixture.TestChunk.class, new ChunkPos(chunkX, 0), sections));
    }

    private static void movePlayer(ScanIntegrationFixture fixture, double x, double y, double z) throws Exception {
        for (Class<?> type = fixture.player.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("position");
                field.setAccessible(true);
                field.set(fixture.player, new Vec3(x, y, z));
                return;
            } catch (NoSuchFieldException ignored) {
                // The position field belongs to Entity rather than the fixture's player shell.
            }
        }
        throw new NoSuchFieldException("position");
    }

    private static List<BlockPos> printTargets(ScanIntegrationFixture fixture, PrintTargetScheduler targets,
                                               List<PrinterBox> scope, boolean prefetch) {
        Configs.Print.PRINT_SORT_TARGETS.setBooleanValue(prefetch);
        targets.setSortedMode(prefetch);
        Iterable<BlockPos> source = prefetch
                ? targets.sortedPositions(scope, fixture.level, fixture.schematic, fixture.player, 0)
                : fixture.cache.iterable("print", scope, fixture.level, fixture.schematic, fixture.player,
                        0, ScanIntent.PRINT, pos -> true, targets::acceptScanCandidate);
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : targets.mergeOrdinaryTargets(source)) {
            targets.removeRetry(pos);
            result.add(pos);
        }
        return result;
    }

    private static List<BlockPos> row(int count) {
        return IntStream.range(0, count).mapToObj(x -> new BlockPos(x, 0, 0)).toList();
    }
}
//#endif
