package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Inventory snapshots must settle before another world or inventory mutation can begin. */
class BedrockInventoryFenceGateTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void aPendingMarkerKeepsWorkActiveWithAnEmptyTargetRegistry() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            beginFence(f);
            f.tick(5);

            assertTrue(f.engine.hasActiveWork(), "Auto-return cannot take inventory while a bedrock snapshot is pending");

            InventoryRecoveryFence.received(f.transport);
            assertFalse(f.engine.hasActiveWork());
        }
    }

    @Test void oldStatsReplyCannotAllowRestockingAcrossThePendingSnapshot() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            f.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
            beginFence(f);
            f.tick(5);

            assertTrue(f.prepare(), "Pending inventory settlement continues observation without starting a provider");
            assertTrue(f.sent.isEmpty());
            InventoryRecoveryFence.received(f.transport);
            f.tick(6);
            assertTrue(f.prepare(), "The reply to an older Stats-screen request cannot release this fence");
            assertTrue(f.sent.isEmpty());
            assertTrue(f.enginePlacer.isInventorySettling());

            InventoryRecoveryFence.received(f.transport);

            assertFalse(f.enginePlacer.isInventorySettling());
            assertFalse(f.prepare());
            assertEquals(1, f.sent.size());
        }
    }

    @Test void aPendingMarkerKeepsTheReadOnlyAdmissionWindowOpen() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            beginFence(f);
            var admission = BedrockPlacementRegressionTest.get(f.engine, BedrockEngine.class, "admission");
            BedrockPlacementRegressionTest.set(admission, BedrockAdmissionController.class, "submissionBudget", 3);
            f.tick(5);
            int sent = f.transport.packets.size();

            assertTrue(f.engine.canSubmitInCurrentWindow(), "Reservation does not own inventory or world mutation");
            assertEquals(sent, f.transport.packets.size());
            assertTrue(f.enginePlacer.isInventorySettling());

            InventoryRecoveryFence.received(f.transport);
            assertTrue(f.engine.canSubmitInCurrentWindow());
        }
    }

    @Test void aPendingMarkerAllowsStatusObservationButDefersRecoveryCleanup() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = new RecoveryProbe(f);
            target.setStatus(BedrockTarget.Status.RECOVERING);
            f.level.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.DOWN));
            f.targets.add(target);
            target.refreshStatusOnly();
            beginFence(f);
            var executor = (BedrockTargetExecutor) BedrockPlacementRegressionTest.get(
                    f.engine, BedrockEngine.class, "targetExecutor");
            f.tick(5);

            executor.observeAndRetire(f.level);
            executor.advance(f.level, true);

            assertEquals(5, target.tickTimes());
            assertEquals(0, target.cleanupCalls);
            InventoryRecoveryFence.received(f.transport);
            f.tick(6);
            executor.observeAndRetire(f.level);
            executor.advance(f.level, true);
            assertEquals(1, target.cleanupCalls);
        }
    }

    @Test void actualInventoryCalibrationAfterRawPlacementStillDefersTorchPreparation() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            BedrockPlacementRegressionTest.set(f.client.player, Player.class, "abilities", new Abilities());
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            f.client.player.getInventory().setItem(1, new ItemStack(Items.REDSTONE_TORCH));
            f.synchronizeInventory();
            var target = f.target();
            assertTrue(f.place(target.getPistonPos(), Direction.UP));
            assertEquals(1, f.mode.hits.size());
            assertFalse(f.placer.isInventorySettling(), "Raw USE alone no longer forces inventory calibration");
            var fence = InventorySyncPackets.prepareFence(f.transport);
            f.connection.send(InventorySyncPackets.createPlayerInventoryFullSync(f.client.player));
            f.connection.send(fence.packet());
            me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.recordFence(f.placer, fence);
            assertTrue(f.placer.isInventorySettling());
            f.tick(2);
            target.tick(false, true);
            assertEquals(1, f.mode.hits.size(), "No power-source operation may overtake an actual inventory snapshot");

            InventoryRecoveryFence.received(f.transport);
            f.level.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP));
            // The ordinary torch path owns item switching; this test starts at its ready-hand boundary.
            me.aleksilassila.litematica.printer.utils.InventoryUtils.setSelectedSlot(f.client.player.getInventory(), 1);
            f.tick(3);
            target.tick(false, true);

            assertEquals(2, f.mode.hits.size(), "Confirmed inventory and priming state permit power on the next tick");
            assertEquals(target.getTorchSupportPos(), f.mode.hits.get(1).getBlockPos());
            assertTrue(f.mode.predictions.get(1));
        }
    }

    @Test void engineResetDropsItsPendingInventoryFence() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            beginFence(f);

            f.engine.reset();

            assertFalse(f.enginePlacer.isInventorySettling());
            assertFalse(f.engine.hasActiveWork());
        }
    }

    private static void beginFence(BedrockRestockTest.Fixture f) {
        var fence = InventorySyncPackets.prepareFence(f.transport);
        f.connection.send(fence.packet());
        me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.recordFence(f.enginePlacer, fence);
        assertTrue(f.enginePlacer.isInventorySettling());
    }

    private static final class RecoveryProbe extends BedrockTarget {
        int cleanupCalls;
        RecoveryProbe(BedrockRestockTest.Fixture fixture) throws Exception {
            super(TARGET, fixture.level, fixture.target().layout(),
                    new BedrockTorchPlacement(TARGET.east(), Direction.UP), null,
                    new BedrockCriticalExecutor(fixture.enginePlacer), fixture.enginePlacer, null);
        }
        @Override public void cleanupRecoveryResidue() { this.cleanupCalls++; }
    }
}
