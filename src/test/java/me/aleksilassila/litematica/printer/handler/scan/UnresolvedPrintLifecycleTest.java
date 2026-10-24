//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkSchematicState;
import java.util.List;
import java.util.Random;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnresolvedPrintLifecycleTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void delayedServerCorrectionWakesExcludedTargetAfterManyEmptyPasses() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            complete(f, 1024);
            f.tick(1);
            assertTrue(f.print("print").isEmpty());
            for (long tick = 2; tick <= 100; tick++) {
                f.tick(tick);
                assertTrue(f.print("print").isEmpty());
                assertEquals(0, f.cache.metricsFor("print").scannedBlocks());
            }
            // Model a late authoritative rollback after the predicted placement looked complete.
            var rejected = new BlockPos(15, 3, 15);
            f.level.world.put(rejected, Blocks.AIR.defaultBlockState());
            f.cache.invalidate(rejected);
            assertEquals(List.of(rejected), f.print("print"), "Wake in the packet's tick, not the next pass");
            f.tick(101);
            assertEquals(List.of(rejected), f.print("print"));
        }
    }

    @Test void chunkReloadClosesAnActiveMaskedCursorAndReintroducesExcludedPositions() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            complete(f, 1024);
            var near = new BlockPos(7, 1, 7);
            var far = new BlockPos(15, 3, 15);
            f.level.world.put(near, Blocks.AIR.defaultBlockState());
            f.level.world.put(far, Blocks.AIR.defaultBlockState());
            f.tick(1);
            assertEquals(List.of(near, far), f.print("print"));
            f.tick(2);
            var old = f.scan("print", f.boxes, 0, ScanIntent.PRINT).iterator();
            assertEquals(near, old.next());
            f.level.world.clear();
            f.cache.invalidateChunk(0, 0, -64, 319);
            assertFalse(old.hasNext());
            assertEquals(ordered(f, 1024), f.print("print"));
        }
    }

    @Test void ownerDisableAndWorldSwitchCannotReuseMaskedWorkFromThePreviousLifetime() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            complete(f, 1024);
            var near = new BlockPos(7, 1, 7);
            var far = new BlockPos(15, 3, 15);
            f.level.world.put(near, Blocks.AIR.defaultBlockState());
            f.level.world.put(far, Blocks.AIR.defaultBlockState());
            f.tick(1);
            assertEquals(List.of(near, far), f.print("print"));
            f.tick(2);
            var old = f.scan("print", f.boxes, 0, ScanIntent.PRINT).iterator();
            assertEquals(near, old.next());
            f.cache.resetOwner("print");
            assertFalse(old.hasNext());
            // Block packets continue invalidating observations while the feature is disabled.
            var changed = new BlockPos(0, 0, 0);
            f.level.world.put(changed, Blocks.AIR.defaultBlockState());
            f.cache.invalidate(changed);
            f.tick(50);
            assertEquals(ordered(f, 1024), f.print("print"));
            f.tick(51);
            var oldWorld = f.scan("print", f.boxes, 0, ScanIntent.PRINT).iterator();
            assertEquals(near, oldWorld.next());
            f.level = ScanIntegrationFixture.newLevel();
            f.epoch = new RuntimeEpoch(20);
            f.tick(1);
            assertFalse(oldWorld.hasNext());
            assertEquals(ordered(f, 1024), f.print("print"));
        }
    }

    @Test void waitingToFilledRecoveryDropsMasksAndRejectsThePreviousCursor() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            complete(f, 1024);
            f.tick(1);
            assertTrue(f.print("print").isEmpty());
            f.tick(2);
            var old = f.scan("print", f.boxes, 0, ScanIntent.PRINT).iterator();
            var chunk = f.chunks.get(0L);
            chunk.setState(ChunkSchematicState.LOADED);
            assertTrue(f.print("print").isEmpty());
            chunk.getSections()[4].setBlockState(15, 3, 15, Blocks.DIRT.defaultBlockState());
            chunk.setState(ChunkSchematicState.FILLED);
            assertEquals(List.of(new BlockPos(15, 3, 15)), f.print("print"));
            assertFalse(old.hasNext());
        }
    }

    @Test void printExclusionsNeverRemoveWorldIntentsOrBreakExtraCandidates() throws Exception {
        try (var f = new ScanIntegrationFixture(4)) {
            var section = f.chunks.get(0L).getSections()[4];
            section.setBlockState(1, 0, 0, Blocks.WATER.defaultBlockState());
            section.setBlockState(2, 0, 0, Blocks.AIR.defaultBlockState());
            complete(f, 4);
            var boxes = List.of(new PrinterBox(0, 0, 0, 3, 0, 0));
            f.tick(1);
            assertTrue(f.print("print").isEmpty());
            f.tick(2);
            assertTrue(f.print("print").isEmpty());
            assertEquals(0, f.cache.metricsFor("print").scannedBlocks());
            for (ScanIntent intent : List.of(ScanIntent.MINE, ScanIntent.BEDROCK)) {
                assertEquals(List.of(BlockPos.ZERO, new BlockPos(3, 0, 0)),
                        ScanIntegrationFixture.drain(f.scan(intent.name(), boxes, 0, intent)));
            }
            assertEquals(List.of(new BlockPos(1, 0, 0)),
                    ScanIntegrationFixture.drain(f.scan("fluid", boxes, 0, ScanIntent.FLUID)));
            assertEquals(List.of(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0)),
                    ScanIntegrationFixture.drain(f.scan("fill", boxes, 0, ScanIntent.FILL)));
            var extra = new BlockPos(2, 0, 0);
            f.level.world.put(extra, Blocks.STONE.defaultBlockState());
            f.cache.invalidate(extra);
            Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(true);
            assertEquals(List.of(extra), ScanIntegrationFixture.drain(f.scan("print", boxes, 0, ScanIntent.PRINT)));
            Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
            assertTrue(f.print("print").isEmpty());
        }
    }

    @Test void repeatedMixedInvalidationsPreserveTheFullOrderedCandidateSet() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            complete(f, 1024);
            var random = new Random(0x74A2D);
            f.tick(1);
            assertTrue(f.print("print").isEmpty());
            for (int step = 1; step <= 80; step++) {
                for (int change = 0; change < 25; change++) {
                    int i = random.nextInt(1024);
                    var p = new BlockPos(i & 15, i >>> 8, (i >>> 4) & 15);
                    var previous = f.level.world.getOrDefault(p, Blocks.AIR.defaultBlockState());
                    f.level.world.put(p, previous.isAir() ? f.schematic.getBlockState(p) : Blocks.AIR.defaultBlockState());
                    f.cache.invalidate(p);
                }
                if (step % 9 == 0) f.cache.invalidateChunk(0, 0, -64, 319);
                else f.cache.resetOwner("print");
                f.tick(step + 1);
                var source = f.scan("print", f.boxes, 0, ScanIntent.PRINT);
                assertEquals(ordered(f, 1024), ScanIntegrationFixture.drain(source), "step=" + step);
                assertEquals(ScanAvailability.COMPLETE, ((ScanCandidateIterable) source).availability());
            }
        }
    }

    private static List<BlockPos> ordered(ScanIntegrationFixture f, int count) {
        var candidates = ScanIntegrationFixture.positions(count).stream().filter(p ->
                !f.schematic.getBlockState(p).equals(f.level.world.getOrDefault(p, Blocks.AIR.defaultBlockState()))).toList();
        return IndexedPositionQueryParityTest.reference(candidates, f.boxes, ScanRegion.from(f.boxes.get(0), null));
    }

    private static void complete(ScanIntegrationFixture f, int count) {
        for (BlockPos p : ScanIntegrationFixture.positions(count)) f.level.world.put(p, f.schematic.getBlockState(p));
    }
}
//#endif
