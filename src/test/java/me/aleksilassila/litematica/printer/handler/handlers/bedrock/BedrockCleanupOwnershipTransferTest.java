//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static org.junit.jupiter.api.Assertions.*;

class BedrockCleanupOwnershipTransferTest {
    private static final BlockPos NEXT = new BlockPos(0, 64, 0);
    private static final BlockPos PREVIOUS = NEXT.east(2);
    private static final BlockPos SHARED_TORCH = NEXT.east().above();

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void aNewMachineOwnsItsTorchUpdateWithoutRevivingThePreviousCleanupSlot() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.beginFence(); // Keep this regression about ownership, with no placement or mining side effects.
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            cleanup.enqueueCycle(PREVIOUS, Set.of(SHARED_TORCH), false);
            cleanup.onServerBlockUpdate(SHARED_TORCH); // The previous machine's removal is authoritative AIR.
            assertEquals(0, cleanup.reachableCycleCount());
            f.advance(0);
            f.plan(NEXT, Direction.UP);
            assertTrue(f.engine.submit(NEXT));
            assertTrue(f.targets.isReserved(SHARED_TORCH));
            assertEquals(1, f.engine.getHudSnapshot().activeTargets());

            f.level.blocks.put(SHARED_TORCH, Blocks.REDSTONE_TORCH.defaultBlockState());
            cleanup.onServerBlockUpdate(SHARED_TORCH);

            assertEquals(1, f.engine.getHudSnapshot().activeTargets(),
                    "A real update for the new reservation cannot also occupy the retired machine's old slot");
            assertEquals(0, cleanup.reachableCycleCount());
            assertEquals(0, f.engine.getHudSnapshot().confirmedSuccesses(),
                    "Transferring ownership is neither a break nor an AIR confirmation");
        }
    }

    @Test void anUnacknowledgedNativeBreakCannotBeTransferredToANewOwner() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var pos = NEXT.above();
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            cleanup.enqueueCycle(NEXT, Set.of(pos), false);
            assertTrue(f.enginePlacer.breakCleanupBlock(pos, Direction.DOWN));
            assertTrue(f.enginePlacer.isCleanupPendingAt(pos));

            cleanup.transferOwnership(Set.of(pos));

            assertTrue(cleanup.contains(pos));
            assertEquals(1, cleanup.reachableCycleCount());
            assertTrue(f.enginePlacer.isCleanupPendingAt(pos), "Only actual AIR can finish the native mining owner");
        }
    }
}
//#endif
