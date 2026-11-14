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

    @Test void explicitReofferKeepsUnconfirmedCandidatesAndReusesWorldFacts() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            complete(f, 1024);
            List<BlockPos> remaining = List.of(new BlockPos(7, 1, 7), new BlockPos(8, 1, 7), new BlockPos(0, 0, 0));
            remaining.forEach(p -> f.level.world.put(p, Blocks.AIR.defaultBlockState()));
            f.tick(1);
            List<BlockPos> first = f.print("print");
            assertEquals(List.of(BlockPos.ZERO, new BlockPos(8, 1, 7), new BlockPos(7, 1, 7)), first);
            int reads = worldReads(f);
            f.tick(2);
            assertTrue(f.print("print").isEmpty(), "A clean completed offer pass sleeps until it is woken");
            assertEquals(0, f.cache.metricsFor("print").scannedBlocks());
            // Reoffer after an eligibility change, keeping the shared world classification facts.
            f.cache.resetOwner("print");
            assertEquals(first, f.print("print"));
            assertEquals(reads, worldReads(f), "Reoffering cached candidates must not reread the world");
            // Offering is not completion: only a matching world update resolves these targets.
            for (BlockPos pos : remaining) {
                f.level.world.put(pos, f.schematic.getBlockState(pos));
                f.cache.invalidate(pos);
            }
            assertTrue(f.print("print").isEmpty());
            f.cache.resetOwner("print");
            assertTrue(f.print("print").isEmpty());
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
            assertTrue(f.print("print").isEmpty());
            int reads = worldReads(f);
            // A new eligibility/reoffer lifetime must not confuse an offered target with completion.
            f.cache.resetOwner("print");
            assertEquals(List.of(changed), f.print("print"));
            assertEquals(reads, worldReads(f));
        }
    }

    @Test void dirtyNegativeCanReenterAnAlreadyConstructedCursor() throws Exception {
        try (var f = new ScanIntegrationFixture(1024)) {
            complete(f, 1024);
            BlockPos first = new BlockPos(8, 1, 7), second = new BlockPos(7, 1, 7), dirty = new BlockPos(15, 3, 15);
            f.level.world.put(first, Blocks.AIR.defaultBlockState());
            f.level.world.put(second, Blocks.AIR.defaultBlockState());
            f.tick(1);
            assertEquals(List.of(first, second), f.print("print"));
            f.tick(2);
            f.cache.resetOwner("print"); // Explicitly start another offer pass without discarding facts.
            var iterator = f.scan("print", f.boxes, 0, ScanIntent.PRINT).iterator();
            assertEquals(first, iterator.next());
            assertTrue(iterator.hasNext(), "Prefetch the already known second candidate before the dirty update");
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

    private static int worldReads(ScanIntegrationFixture f) {
        return f.level.reads.values().stream().mapToInt(Integer::intValue).sum();
    }
}
//#endif
