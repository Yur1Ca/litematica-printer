//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import java.util.ArrayList;
import java.util.List;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnresolvedPrintScanTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void hotPassEnumeratesOnlyRemainingCandidatesInOriginalOrder() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            complete(f, 1024);
            List<BlockPos> remaining = List.of(new BlockPos(7, 1, 7), new BlockPos(8, 1, 7), new BlockPos(0, 0, 0));
            remaining.forEach(p -> f.level.world.put(p, Blocks.AIR.defaultBlockState()));
            f.tick(1);
            List<BlockPos> first = f.print("print");
            assertEquals(3, first.size());
            f.tick(2);
            assertEquals(first, f.print("print"));
            assertEquals(3, f.cache.metricsFor("print").scannedBlocks(),
                    "Known zero-classification positions should not enter the next cursor");
        }
    }

    @Test void unknownAndInvalidatedPositionsAreNeverMistakenForCompleted() throws Exception {
        try (var f = new ScanIntegrationFixture(512)) {
            complete(f, 512);
            f.tick(1);
            assertEquals(List.of(), f.print("print"));
            f.tick(2);
            assertEquals(List.of(), f.print("print"));
            assertEquals(0, f.cache.metricsFor("print").scannedBlocks());
            BlockPos changed = new BlockPos(15, 0, 15);
            f.level.world.put(changed, Blocks.AIR.defaultBlockState());
            f.cache.invalidate(changed);
            assertEquals(List.of(changed), f.print("print"));
            f.tick(3);
            assertEquals(List.of(changed), f.print("print"));
        }
    }

    @Test void dirtyNegativeCanReenterAnAlreadyConstructedCursor() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            complete(f, 1024);
            BlockPos first = new BlockPos(7, 1, 7), second = new BlockPos(8, 1, 7), dirty = new BlockPos(15, 3, 15);
            f.level.world.put(first, Blocks.AIR.defaultBlockState());
            f.level.world.put(second, Blocks.AIR.defaultBlockState());
            f.tick(1);
            assertEquals(List.of(first, second), f.print("print"));
            f.tick(2);
            var iterator = f.scan("print", f.boxes, 0, ScanIntent.PRINT).iterator();
            assertEquals(first, iterator.next());
            f.level.world.put(dirty, Blocks.AIR.defaultBlockState());
            f.cache.invalidate(dirty);
            List<BlockPos> tail = new ArrayList<>();
            iterator.forEachRemaining(tail::add);
            assertEquals(List.of(second, dirty), tail);
        }
    }

    @Test void ownerResetSourceReplacementAndWorldSwitchCannotKeepNegativeKnowledge() throws Exception {
        try (var f = new ScanIntegrationFixture(1)) {
            complete(f, 1);
            f.tick(1);
            assertTrue(f.print("print").isEmpty());
            f.cache.clear();
            f.level.world.clear();
            f.tick(2);
            assertEquals(List.of(BlockPos.ZERO), f.print("print"));
            complete(f, 1);
            f.cache.invalidate(BlockPos.ZERO);
            f.tick(3);
            assertTrue(f.print("print").isEmpty());
            f.replaceTarget(Blocks.DIRT.defaultBlockState());
            assertEquals(List.of(BlockPos.ZERO), f.print("print"));
            f.level = ScanIntegrationFixture.newLevel();
            f.epoch = new RuntimeEpoch(10L);
            f.tick(4);
            assertEquals(List.of(BlockPos.ZERO), f.print("print"));
        }
    }

    private static void complete(ScanIntegrationFixture f, int count) {
        for (BlockPos pos : ScanIntegrationFixture.positions(count)) {
            f.level.world.put(pos, f.schematic.getBlockState(pos));
        }
    }
}
//#endif
