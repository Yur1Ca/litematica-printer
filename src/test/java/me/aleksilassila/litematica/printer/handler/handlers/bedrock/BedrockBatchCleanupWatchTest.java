package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static org.junit.jupiter.api.Assertions.*;

/** Observation-only retirement must retain ownership of late reverse-piston updates. */
class BedrockBatchCleanupWatchTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void anAirWatchDoesNotCountAsQueuedCleanupPressureButALateResidueReturns() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            f.level.blocks.remove(TARGET);
            cleanup.enqueueAuthoritative(TARGET);
            assertFalse(cleanup.contains(TARGET), "An empty watched slot must not stall the cleanup drain");
            assertEquals(0, cleanup.size());
            f.level.blocks.put(TARGET, Blocks.PISTON.defaultBlockState());
            cleanup.onServerBlockUpdate(TARGET);
            assertTrue(cleanup.contains(TARGET), "A late reverse piston retains cleanup ownership");
            f.level.blocks.remove(TARGET);
            cleanup.onServerBlockUpdate(TARGET);
            assertTrue(cleanup.isEmpty());
            f.level.blocks.put(TARGET, Blocks.PISTON.defaultBlockState());
            cleanup.onServerBlockUpdate(TARGET);
            assertTrue(cleanup.contains(TARGET), "Air closes work, not the late-update watch");
        }
    }

    @Test void retiringDuringAnInventoryFenceKeepsWatchingAfterItsAirSlotIsReleased() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            var target = f.addTarget();
            target.recordNetworkAttempt();
            target.setHasTried(true);
            target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            f.level.blocks.remove(TARGET);
            var fence = InventorySyncPackets.prepareFence(f.transport);
            f.connection.send(fence.packet());
            me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.recordFence(f.enginePlacer, fence);
            f.mode.rejectMutations = true;
            int sentBefore = f.transport.packets.size();
            var executor = (BedrockTargetExecutor) get(f.engine, BedrockEngine.class, "targetExecutor");
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");

            executor.observeAndRetire(f.level);

            assertTrue(f.targets.isEmpty());
            assertFalse(cleanup.contains(TARGET), "Air belongs to the late-update watch, not the work queue");
            // Admission may release the currently empty cell before another
            // server block update reveals a reverse piston from the same attempt.
            cleanup.remove(TARGET);
            assertFalse(cleanup.contains(TARGET));
            f.level.blocks.put(TARGET, Blocks.PISTON.defaultBlockState());
            cleanup.onServerBlockUpdate(TARGET);

            assertTrue(cleanup.contains(TARGET),
                    "Observation-only retirement must register the late-update watch even while inventory is fenced");
            assertTrue(f.enginePlacer.isInventorySettling());
            assertEquals(sentBefore, f.transport.packets.size(),
                    "Registering or observing cleanup cannot send mining, placement or inventory packets");
            assertTrue(f.mode.hits.isEmpty());
        }
    }
}
