//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Captures real preparation packets without simulating the server's placement or piston events. */
class BedrockPowerPreparationTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final BlockPos SLIME = TARGET.east();

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void aSlimeMachineKeepsItsMaterialHandUntilItsDeferredTorchHasBeenSent() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var world = f.allocate(BedrockInitialBatchPlacementTest.BatchLevel.class);
            world.blocks = f.level.blocks;
            world.blocked = new HashSet<>();
            world.collisionChecks = new HashSet<>();
            set(world, Level.class, "dimension", Level.OVERWORLD);
            f.client.level = world;
            world.blocks.remove(TARGET.above());
            world.blocks.put(SLIME, Blocks.SLIME_BLOCK.defaultBlockState());
            var inventory = (BedrockInventorySession) get(f.enginePlacer, BedrockPlacer.class, "inventory");
            assertTrue(inventory.prepareMaterial(Blocks.SLIME_BLOCK.asItem()));
            assertEquals(2, InventoryUtils.getSelectedSlot(f.client.player.getInventory()));
            var layout = f.target(TARGET, Direction.UP, f.enginePlacer).layout();
            var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
            var target = new BedrockTarget(TARGET, world, layout,
                    new BedrockTorchPlacement(SLIME, Direction.UP), SLIME, critical, f.enginePlacer, null);
            f.targets.add(target);
            f.transport.packets.clear();

            f.tick(1);
            f.engine.beginTick(true);
            f.engine.finishTick(true);

            assertTrue(target.hasDeferredInitialTorch());
            var uses = f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).toList();
            assertEquals(1, uses.size(), "The initial OFF_HAND piston can be sent before its deferred Torch");
            assertEquals(InteractionHand.OFF_HAND, uses.getFirst().getHand());
            assertEquals(2, InventoryUtils.getSelectedSlot(f.client.player.getInventory()),
                    "Selecting Pick here only forces another equipment boundary after the still-pending Torch");
            assertTrue(f.transport.packets.stream().noneMatch(ServerboundUseItemPacket.class::isInstance),
                    "Material preparation must finish before starting a critical-tool equipment acknowledgement");
            assertFalse(world.blocks.containsKey(target.getPistonPos()), "Only a server update may confirm the piston");
        }
    }
}
//#endif
