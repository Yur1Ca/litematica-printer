//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.ChunkSchematic;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecondReviewScanRecoveryTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void continuousDirtyZeroClassificationsDoNotStarveRepairInAnotherSection() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            f.level.allChunksLoaded = true;
            List<PrinterBox> scope = List.of(new PrinterBox(0, 0, 0, 31, 0, 0));
            BlockPos noisy = new BlockPos(1, 0, 0);
            BlockPos missed = new BlockPos(17, 0, 0);
            f.tick(1);
            assertTrue(ScanIntegrationFixture.drain(f.scan("mine", scope, 0, ScanIntent.MINE)).isEmpty());
            f.level.world.put(missed, Blocks.STONE.defaultBlockState());

            boolean recovered = false;
            for (int tick = 2; tick <= 61; tick++) {
                f.tick(tick);
                // Repeated air/fluid notifications classify to zero for MINE. They must not
                // keep the already-completed traversal from scheduling its due repair.
                f.cache.invalidate(noisy);
                if (ScanIntegrationFixture.drain(f.scan("mine", scope, 0, ScanIntent.MINE)).contains(missed)) {
                    recovered = true;
                    break;
                }
            }
            assertTrue(recovered, "A continuously dirty clean section must not suppress repair of the rest of the scope");
        }
    }

    @Test void anEntirelyEmptySchematicChunkSkipsSectionsInsteadOfWalkingEveryCell() throws Exception {
        try (var f = new ScanIntegrationFixture(0)) {
            // The shared Unsafe shell bypasses ChunkSchematic's constructor, whose isEmpty
            // field starts true even before any block is written. Restore that real state.
            Field empty = ChunkSchematic.class.getDeclaredField("isEmpty");
            empty.setAccessible(true);
            empty.setBoolean(f.chunks.get(0L), true);
            assertTrue(f.chunks.get(0L).isEmpty());
            List<PrinterBox> scope = List.of(new PrinterBox(0, 0, 0, 15, 15, 15));
            AtomicInteger inspected = new AtomicInteger();
            f.tick(1);
            assertTrue(ScanIntegrationFixture.drain(f.cache.iterable("print", scope, f.level, f.schematic,
                    null, 0, ScanIntent.PRINT, pos -> true, pos -> {
                        inspected.incrementAndGet();
                        return true;
                    })).isEmpty());
            assertEquals(1, inspected.get(), "One whole-section head must skip the empty schematic chunk");
            assertEquals(0, f.cache.metricsFor("print").scannedBlocks());
        }
    }
}
//#endif
