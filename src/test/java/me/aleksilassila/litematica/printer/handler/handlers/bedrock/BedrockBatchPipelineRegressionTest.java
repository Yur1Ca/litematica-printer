package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static org.junit.jupiter.api.Assertions.*;

/** Preparation and observation must not accidentally enter a destructive batch. */
class BedrockBatchPipelineRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void preparationDoesNotPlaceAnIndividualRawPistonOrStartItsOwnFence() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            f.client.player.getInventory().setItem(1, new ItemStack(Items.REDSTONE_TORCH, 16));
            var target = f.target();

            prepare(target);

            assertEquals(BedrockTarget.Status.UNINITIALIZED, target.getStatus());
            assertTrue(f.mode.hits.isEmpty(), "Raw initial pistons belong to the batch stage");
            assertTrue(f.transport.packets.isEmpty());
            assertFalse(f.placer.isInventorySettling());
            assertEquals(16, f.client.player.getInventory().getItem(40).getCount());
        }
    }

    @Test void preparationStillPowersAnAuthoritativelyObservedPrimingPiston() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = f.target();
            target.deferInitialTorch();
            f.level.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP));
            f.client.player.getInventory().setItem(40, new ItemStack(Items.REDSTONE_TORCH, 16));

            prepare(target);

            assertEquals(1, f.mode.hits.size());
            assertEquals(target.getTorchSupportPos(), f.mode.hits.get(0).getBlockPos());
            assertTrue(f.mode.predictions.get(0));
            assertFalse(target.hasTried());
        }
    }

    @Test void confirmedCompletionCanRetireDuringAnInventoryFenceWithoutSendingAnotherMutation() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var engine = f.engine();
            var placer = (BedrockPlacer) get(engine, BedrockEngine.class, "placer");
            var registry = (BedrockTargetRegistry) get(engine, BedrockEngine.class, "targets");
            var executor = (BedrockTargetExecutor) get(engine, BedrockEngine.class, "targetExecutor");
            var target = f.target();
            registry.add(target);
            f.level.blocks.put(TARGET, Blocks.AIR.defaultBlockState());
            var fence = InventorySyncPackets.prepareFence(f.transport);
            f.connection.send(fence.packet());
            me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.recordFence(placer, fence);
            f.mode.rejectMutations = true;
            int sent = f.transport.packets.size();

            var observe = assertDoesNotThrow(() -> BedrockTargetExecutor.class.getDeclaredMethod(
                    "observeAndRetire", net.minecraft.client.multiplayer.ClientLevel.class));
            observe.setAccessible(true);
            observe.invoke(executor, f.level);

            assertTrue(registry.isEmpty(), "A read-only completion must not occupy concurrency until the marker arrives");
            assertEquals(1, engine.getHudSnapshot().confirmedSuccesses());
            assertEquals(sent, f.transport.packets.size());
            assertTrue(placer.isInventorySettling(), "Retirement cannot release the physical inventory barrier");
        }
    }

    private static void prepare(BedrockTarget target) throws Exception {
        var method = assertDoesNotThrow(() -> BedrockTarget.class.getDeclaredMethod("tickPreparation"),
                "Preparation needs an entry point that cannot fire a raw or critical placement");
        method.setAccessible(true);
        method.invoke(target);
    }
}
