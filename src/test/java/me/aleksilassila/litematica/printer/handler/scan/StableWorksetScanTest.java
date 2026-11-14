//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Field;
import java.util.Map;
import fi.dy.masa.litematica.world.ChunkSchematic;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StableWorksetScanTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void printStartsWithAContinuousSerpentineRowInsteadOfIndividualDistanceRings() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            f.tick(1);
            List<BlockPos> actual = f.print("print");
            assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                    new BlockPos(2, 0, 0), new BlockPos(3, 0, 0), new BlockPos(4, 0, 0),
                    new BlockPos(5, 0, 0), new BlockPos(6, 0, 0), new BlockPos(7, 0, 0),
                    new BlockPos(8, 0, 0), new BlockPos(9, 0, 0)), actual.subList(0, 10));
            assertEquals(1024, actual.size());
            assertEquals(actual.size(), actual.stream().distinct().count());
        }
    }

    @Test void unchangedEmptyMiningWindowDoesNotInspectEveryPositionAgainNextTick() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            List<PrinterBox> scope = List.of(new PrinterBox(0, 0, 0, 15, 3, 15));
            f.tick(1);
            assertTrue(ScanIntegrationFixture.drain(f.scan("mine", scope, 0, ScanIntent.MINE)).isEmpty());
            f.tick(2);
            assertTrue(ScanIntegrationFixture.drain(f.scan("mine", scope, 0, ScanIntent.MINE)).isEmpty());
            assertEquals(0, f.cache.metricsFor("mine").scannedBlocks(),
                    "A clean completed workset should sleep rather than replay the geometric volume");
        }
    }

    @Test void budgetSlicesPreserveTheSameCompleteConstructionSequence() throws Exception {
        try (var f = new ScanIntegrationFixture(128)) {
            f.tick(1);
            List<BlockPos> expected = f.print("print");
            f.cache.resetOwner("print");
            List<BlockPos> sliced = new ArrayList<>();
            for (int tick = 2; tick < 100; tick++) {
                f.tick(tick);
                var source = f.scan("print", f.boxes, 7, ScanIntent.PRINT);
                sliced.addAll(ScanIntegrationFixture.drain(source));
                if (((ScanCandidateIterable) source).availability() == ScanAvailability.COMPLETE) break;
            }
            assertEquals(expected, sliced);
        }
    }

    @Test void aSingleDirtyBlockWakesTheSleepingWorksetWithoutRecheckingAnUnrelatedPosition() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            f.tick(1);
            assertTrue(ScanIntegrationFixture.drain(f.scan("mine", f.boxes, 0, ScanIntent.MINE)).isEmpty());
            BlockPos changed = new BlockPos(3, 1, 4), far = new BlockPos(14, 1, 14);
            int farReads = f.level.readsAt(far);
            f.level.world.put(changed, Blocks.STONE.defaultBlockState());
            f.cache.invalidate(changed);
            f.tick(2);
            assertEquals(List.of(changed), ScanIntegrationFixture.drain(f.scan("mine", f.boxes, 0, ScanIntent.MINE)));
            assertEquals(farReads, f.level.readsAt(far));
            assertEquals(1, f.cache.metricsFor("mine").scannedBlocks());
        }
    }

    @Test void anExplicitEligibilityWakeReusesFactsThatWereRejectedByADynamicPredicate() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            f.level.world.put(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
            AtomicBoolean ready = new AtomicBoolean();
            List<PrinterBox> scope = List.of(new PrinterBox(BlockPos.ZERO));
            f.tick(1);
            assertTrue(ScanIntegrationFixture.drain(f.cache.iterable("mine", scope, f.level, null, null,
                    0, ScanIntent.MINE, pos -> ready.get(), pos -> true)).isEmpty());
            int reads = f.level.readsAt(BlockPos.ZERO);
            ready.set(true);
            f.cache.resetOwner("mine");
            f.tick(2);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(f.cache.iterable("mine", scope,
                    f.level, null, null, 0, ScanIntent.MINE, pos -> ready.get(), pos -> true)));
            assertEquals(reads, f.level.readsAt(BlockPos.ZERO));
        }
    }

    @Test void expandingTheScopeDiscoversUnknownPositionsWithoutAWorldUpdate() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            BlockPos target = new BlockPos(2, 0, 0);
            f.level.world.put(target, Blocks.STONE.defaultBlockState());
            f.tick(1);
            assertTrue(ScanIntegrationFixture.drain(f.scan("mine", List.of(new PrinterBox(BlockPos.ZERO)),
                    0, ScanIntent.MINE)).isEmpty());
            f.tick(2);
            assertEquals(List.of(target), ScanIntegrationFixture.drain(f.scan("mine",
                    List.of(new PrinterBox(0, 0, 0, 2, 0, 0)), 0, ScanIntent.MINE)));
        }
    }

    @Test void aStaggeredSectionRepairRecoversAMissingWorldNotification() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            f.tick(1);
            assertTrue(ScanIntegrationFixture.drain(f.scan("mine", f.boxes, 0, ScanIntent.MINE)).isEmpty());
            BlockPos target = new BlockPos(3, 1, 4);
            f.level.world.put(target, Blocks.STONE.defaultBlockState());
            f.tick(21);
            assertEquals(List.of(target), ScanIntegrationFixture.drain(f.scan("mine", f.boxes, 0, ScanIntent.MINE)));
            assertTrue(f.cache.metricsFor("mine").scannedBlocks() <= 4096);
        }
    }

    @Test void anOwnerResetDiscardsAnAlreadyPrefetchedCandidate() throws Exception {
        try (var f = new ScanIntegrationFixture(1)) {
            f.tick(1);
            var source = (ScanCandidateIterable) f.scan("print", f.boxes, 0, ScanIntent.PRINT);
            var iterator = source.iterator();
            assertTrue(iterator.hasNext());
            f.cache.resetOwner("print");
            assertFalse(source.isValid());
            assertFalse(iterator.hasNext());
        }
    }

    @Test void startingLocalPrintingNeverEnumeratesTheWholeLoadedBlueprint() throws Exception {
        try (var f = new ScanIntegrationFixture(1)) {
            var chunks = new ConcurrentHashMap<Long, ChunkSchematic>() {
                @Override public Collection<ChunkSchematic> values() {
                    throw new AssertionError("Local work must not enumerate all loaded blueprint chunks");
                }
            };
            chunks.put(0L, f.chunks.get(0L));
            f.schematic = SchematicIndexFixture.world(chunks);
            f.tick(1);
            assertEquals(List.of(BlockPos.ZERO), f.print("print"));
        }
    }

    @Test void aContinuouslyDirtyEarlyTargetCannotStarveUndiscoveredPositions() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            f.level.defaultState = Blocks.STONE.defaultBlockState();
            List<PrinterBox> scope = List.of(new PrinterBox(0, 0, 0, 7, 0, 0));
            f.tick(1);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(f.scan("mine", scope, 1, ScanIntent.MINE)));
            boolean progressed = false;
            for (int tick = 2; tick <= 11; tick++) {
                f.cache.invalidate(BlockPos.ZERO);
                f.tick(tick);
                List<BlockPos> positions = ScanIntegrationFixture.drain(f.scan("mine", scope, 1, ScanIntent.MINE));
                if (positions.stream().anyMatch(pos -> pos.getX() > 0)) {
                    progressed = true;
                    break;
                }
            }
            assertTrue(progressed, "Discovery must receive a slice even while an earlier cell changes every tick");
        }
    }

    @Test void expandingTheScopeDuringARepairStillDiscoversTheNewArea() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            BlockPos added = new BlockPos(2, 0, 0);
            f.level.world.put(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
            f.level.world.put(added, Blocks.STONE.defaultBlockState());
            List<PrinterBox> initial = List.of(new PrinterBox(0, 0, 0, 1, 0, 0));
            f.tick(1);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(f.scan("mine", initial, 0, ScanIntent.MINE)));
            f.tick(21);
            var repair = (ScanCandidateIterable) f.scan("mine", initial, 1, ScanIntent.MINE);
            assertEquals(List.of(BlockPos.ZERO), ScanIntegrationFixture.drain(repair));
            assertEquals(ScanAvailability.PAUSED, repair.availability());
            List<BlockPos> expanded = ScanIntegrationFixture.drain(f.scan("mine",
                    List.of(new PrinterBox(0, 0, 0, 2, 0, 0)), 0, ScanIntent.MINE));
            assertTrue(expanded.contains(added), "A scope revision must leave repair-only traversal and discover new cells");
        }
    }

    @Test void replacingTheScopeInvalidatesAnOldPrefetchedOfferInTheSameSession() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            f.level.defaultState = Blocks.STONE.defaultBlockState();
            f.tick(1);
            var oldSource = (ScanCandidateIterable) f.scan("mine", List.of(new PrinterBox(BlockPos.ZERO)), 0, ScanIntent.MINE);
            var oldIterator = oldSource.iterator();
            assertTrue(oldIterator.hasNext());
            f.scan("mine", List.of(new PrinterBox(new BlockPos(2, 0, 0))), 0, ScanIntent.MINE);
            assertFalse(oldSource.isValid(), "An old offer belongs to its original scope revision");
            assertFalse(oldIterator.hasNext());
        }
    }

    @Test void repairCoverageSurvivesMoreThan4096VisitedSectionsAndFactEviction() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            f.level.allChunksLoaded = true;
            List<PrinterBox> scope = List.of(new PrinterBox(0, 0, 0, 4097 * 16 - 1, 0, 0));
            long completedTick = completeEmptyInitialPass(f, scope);
            // This is in the first visited tile near the fixed initial anchor, not the last 4096 visited sections.
            BlockPos target = new BlockPos(2048 * 16, 0, 0);
            f.level.world.put(target, Blocks.STONE.defaultBlockState());
            boolean repaired = false;
            for (int step = 1; step <= 4097; step++) {
                f.tick(completedTick + step * 20L);
                if (ScanIntegrationFixture.drain(f.scan("mine", scope, 0, ScanIntent.MINE)).contains(target)) {
                    repaired = true;
                    break;
                }
            }
            assertTrue(repaired, "Bounded retention must not discard the coverage of earlier sections");
        }
    }

    @Test void emptyBlueprintSectionsAreRejectedWithoutClassifyingTheirIndividualCells() throws Exception {
        try (var f = new ScanIntegrationFixture(1)) {
            f.tick(1);
            List<PrinterBox> emptyLayers = List.of(new PrinterBox(0, 16, 0, 15, 63, 15));
            assertTrue(ScanIntegrationFixture.drain(f.scan("print", emptyLayers, 0, ScanIntent.PRINT)).isEmpty());
            var chunk = (ScanIntegrationFixture.TestChunk) f.chunks.get(0L);
            assertEquals(0, chunk.schematicReads, "Empty section metadata is sufficient to reject its entire volume");
            assertEquals(0, f.cache.metricsFor("print").scannedBlocks());
        }
    }

    @Test void aLargeDirtyBurstRetainsBoundedIndividualOffersAndStillFindsEveryChangedTarget() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            f.level.allChunksLoaded = true;
            List<PrinterBox> scope = List.of(new PrinterBox(0, 0, 0, 20_000, 0, 0));
            long completedTick = completeEmptyInitialPass(f, scope);
            BlockPos target = new BlockPos(17_000, 0, 0);
            BlockPos beforeOverflow = new BlockPos(100, 0, 0);
            f.level.world.put(target, Blocks.STONE.defaultBlockState());
            f.level.world.put(beforeOverflow, Blocks.STONE.defaultBlockState());
            for (int x = 0; x < 20_000; x++) f.cache.invalidate(new BlockPos(x, 0, 0));
            Field storeField = ScanCache.class.getDeclaredField("sessions");
            storeField.setAccessible(true);
            Object store = storeField.get(f.cache);
            Field sessionsField = store.getClass().getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            Object session = ((Map<?, ?>) sessionsField.get(store)).get("mine:MINE");
            Field dirtyField = session.getClass().getDeclaredField("dirtyPositions");
            dirtyField.setAccessible(true);
            assertTrue(((Collection<?>) dirtyField.get(session)).size() <= 16_384,
                    "A burst must coalesce into resumable coverage rather than retain an object for every update");
            java.util.Set<BlockPos> actual = new java.util.HashSet<>();
            boolean completed = false;
            for (int slice = 1; slice <= 16; slice++) {
                f.tick(completedTick + slice);
                var source = (ScanCandidateIterable) f.scan("mine", scope, 0, ScanIntent.MINE);
                List<BlockPos> batch = ScanIntegrationFixture.drain(source);
                assertEquals(new java.util.HashSet<>(batch).size(), batch.size(), "Each offered batch is deduplicated");
                actual.addAll(batch);
                if (source.availability() == ScanAvailability.COMPLETE) {
                    completed = true;
                    break;
                }
                assertEquals(ScanAvailability.PAUSED, source.availability());
            }
            assertTrue(completed, "Dirty overflow must finish its resumable full follow-up");
            // Unchanged positive facts may be offered again by a later full follow-up slice.
            assertEquals(java.util.Set.of(target, beforeOverflow), actual);
        }
    }

    private static long completeEmptyInitialPass(ScanIntegrationFixture fixture, List<PrinterBox> scope) {
        for (long tick = 1; tick <= 32; tick++) {
            fixture.tick(tick);
            var source = (ScanCandidateIterable) fixture.scan("mine", scope, 0, ScanIntent.MINE);
            assertTrue(ScanIntegrationFixture.drain(source).isEmpty());
            if (source.availability() == ScanAvailability.COMPLETE) return tick;
            assertEquals(ScanAvailability.PAUSED, source.availability());
        }
        fail("The initial traversal must finish under the unchanged default work quota");
        return -1;
    }
}
//#endif
