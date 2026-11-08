//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static org.junit.jupiter.api.Assertions.*;

class BedrockCleanupPriorityTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final BlockPos RESIDUE = new BlockPos(-4, 65, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void aQueuedFastBreakPrecedesTheNextCriticalBundle() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            // The fixture stores block state directly; it never simulates breaking success.
            f.level.blocks.put(RESIDUE, Blocks.REDSTONE_TORCH.defaultBlockState());
            cleanup.enqueueCycle(RESIDUE.below(), Set.of(RESIDUE), false);
            f.machine(Direction.UP);
            var layout = f.target(TARGET, Direction.UP, f.enginePlacer).layout();
            var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
            var sync = (BedrockNetworkSync) get(f.engine, BedrockEngine.class, "networkSync");
            var target = new BedrockTarget(TARGET, f.client.level, layout,
                    new BedrockTorchPlacement(TARGET.east(), Direction.UP), null, critical, f.enginePlacer, sync);
            f.level.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            target.setStatus(BedrockTarget.Status.EXTENDED);
            f.targets.add(target);
            f.transport.packets.clear();

            f.advance(0);

            var digs = f.transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).toList();
            assertFalse(digs.isEmpty());
            assertEquals(RESIDUE, digs.getFirst().getPos(), "Cleanup gets the hand before a fresh strict bundle can monopolize it");
            assertTrue(target.hasTried(), "Priority must not turn into a tick-wide ban on the next independent critical");
            assertTrue(f.level.blocks.get(RESIDUE).is(Blocks.REDSTONE_TORCH), "Only a real server update may remove the residue");
        }
    }

    @Test void aToolTickAckDoesNotBlockZeroHardnessCleanupOrCountAsItsAirConfirmation() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var inventory = (BedrockInventorySession) get(f.enginePlacer, BedrockPlacer.class, "inventory");
            var toolSync = (BedrockToolSync) get(inventory, BedrockInventorySession.class, "toolSync");
            toolSync.invalidateTool();
            assertFalse(f.enginePlacer.prepareCriticalToolTick());
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            f.level.blocks.put(RESIDUE, Blocks.REDSTONE_TORCH.defaultBlockState());
            cleanup.enqueueCycle(RESIDUE.below(), Set.of(RESIDUE), false);
            f.level.blocks.remove(TARGET.above());
            var layout = f.target(TARGET, Direction.UP, f.enginePlacer).layout();
            var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
            var sync = (BedrockNetworkSync) get(f.engine, BedrockEngine.class, "networkSync");
            f.targets.add(new BedrockTarget(TARGET, f.client.level, layout,
                    new BedrockTorchPlacement(TARGET.east(), Direction.UP), null, critical, f.enginePlacer, sync));
            f.transport.packets.clear();

            f.advance(0);

            var digs = f.transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).toList();
            assertEquals(1, digs.size());
            assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, digs.getFirst().getAction());
            assertEquals(RESIDUE, digs.getFirst().getPos());
            assertTrue(cleanup.contains(RESIDUE));
            assertTrue(f.enginePlacer.isCleanupPendingAt(RESIDUE));
            assertTrue(f.transport.packets.stream().noneMatch(
                    net.minecraft.network.protocol.game.ServerboundUseItemOnPacket.class::isInstance),
                    "New machine preparation cannot switch MAIN while cleanup's tool equipment tick is pending");
            assertFalse(f.enginePlacer.prepareCriticalToolTick(), "Hardness-zero work cannot acknowledge a pending tool change");
            assertTrue(f.level.blocks.get(RESIDUE).is(Blocks.REDSTONE_TORCH));
        }
    }
}
//#endif
