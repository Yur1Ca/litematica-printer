//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Captures real handoffs across client ticks; native block updates are inputs, never simulated outcomes. */
class BedrockHandPhaseTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final BlockPos SLIME = TARGET.east();
    private static final BlockPos OLD = new BlockPos(-4, 64, 0);
    private static final BlockPos RESIDUE = OLD.above();

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void anOlderHardCleanupCannotInsertAPickBoundaryBeforeTheReadyDeferredTorch() throws Exception {
        try (var f = new Fixture(true)) {
            var target = f.addSlimeTarget();
            f.step(1);
            assertTrue(target.hasDeferredInitialTorch());
            assertEquals(0, f.markers());
            f.primingUpdate(target);
            f.world.blocks.put(RESIDUE, Blocks.PISTON.defaultBlockState());
            f.cleanup.enqueueCycle(OLD, Set.of(RESIDUE), false);

            f.step(2);

            assertEquals(List.of(0L), f.capture.markersAtTorch,
                    "The already-runnable Torch phase must precede a cleanup-induced Pick tick boundary");
            assertEquals(1, f.markers(), "Torch completion hands MAIN to Pick exactly once");
            assertFalse(f.enginePlacer.isCleanupPendingAt(RESIDUE), "A hard break still waits for real equipment acknowledgement");
            f.acknowledgeEquipment();
            f.step(3);
            assertTrue(f.enginePlacer.isCleanupPendingAt(RESIDUE), "Finishing materials cannot starve the older cleanup cycle");
            assertEquals(1, f.markers());
            assertTrue(f.world.blocks.get(RESIDUE).is(Blocks.PISTON));
        }
    }

    @Test void anEarlierRecoveringOwnerCannotTakeTheHandBeforeAnotherOwnersReadyTorch() throws Exception {
        try (var f = new Fixture(true)) {
            var target = f.addSlimeTarget();
            f.step(1);
            f.primingUpdate(target);
            var recovering = f.recoveringTarget();
            f.targets.clear();
            f.targets.add(recovering);
            f.targets.add(target);

            f.step(2);

            assertEquals(List.of(0L), f.capture.markersAtTorch,
                    "Recovery reached through the target registry must follow the same material handoff");
            assertEquals(1, f.markers());
            assertFalse(f.enginePlacer.isCleanupPendingAt(RESIDUE));
            f.acknowledgeEquipment();
            f.step(3);
            assertTrue(f.enginePlacer.isCleanupPendingAt(RESIDUE));
            assertEquals(1, f.markers());
        }
    }

    @Test void anUnchangedMainHandCanPrimeAnIndependentOffhandPistonWhileItsGearAckIsPending() throws Exception {
        try (var f = new Fixture(false)) {
            var target = f.addSlimeTarget();
            assertFalse(f.enginePlacer.prepareCriticalToolTick());
            assertEquals(1, f.markers());

            f.step(1);

            var uses = f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).toList();
            assertEquals(1, uses.size(), "This priming needs only ROT and OFF_HAND USE, not another MAIN selection");
            assertEquals(InteractionHand.OFF_HAND, uses.getFirst().getHand());
            assertEquals(0, InventoryUtils.getSelectedSlot(f.client.player.getInventory()));
            assertEquals(1, f.markers());
            assertTrue(target.hasDeferredInitialTorch());
            assertTrue(f.capture.markersAtTorch.isEmpty());
            f.acknowledgeEquipment();
            f.step(2);
            assertTrue(f.enginePlacer.prepareCriticalToolTick(), "The original signature and marker remain valid");
            assertEquals(1, f.markers());
            assertTrue(f.transport.packets.stream().noneMatch(ServerboundPlayerActionPacket.class::isInstance));
        }
    }

    @Test void aPoweredStallCannotRunHardRecoveryBeforeAnotherOwnersSlimePreparation() throws Exception {
        try (var f = new Fixture(true)) {
            f.world.blocks.remove(SLIME);
            var target = f.addSlimeTarget();
            var stalled = f.poweredStallTarget();
            f.targets.clear();
            f.targets.add(stalled);
            f.targets.add(target);

            f.step(1);

            assertEquals(List.of(0L), f.capture.markersAtSlime,
                    "Powered-stall recovery must not select Pick before this tick's runnable Slime stage");
            assertEquals(1, f.markers(), "After material preparation, recovery may take Pick through one real native boundary");
            assertEquals(BedrockTarget.Status.RECOVERING, stalled.getStatus());
            assertFalse(f.enginePlacer.isCleanupPendingAt(RESIDUE));
            assertFalse(f.world.blocks.containsKey(SLIME), "Capturing a placement request cannot invent a native result");
            f.world.blocks.put(SLIME, Blocks.SLIME_BLOCK.defaultBlockState()); // The later native support update arrives first.
            f.acknowledgeEquipment();
            f.step(2);
            assertTrue(f.enginePlacer.isCleanupPendingAt(RESIDUE), "The powered stall still reaches its recovery phase");
        }
    }

    @Test void aPollutedWaitingOwnerCannotHideHardRecoveryInsideTheMaterialPhase() throws Exception {
        try (var f = new Fixture(true)) {
            f.world.blocks.remove(SLIME);
            var target = f.addSlimeTarget();
            var polluted = f.pollutedHeadTarget();
            f.targets.clear();
            f.targets.add(polluted);
            f.targets.add(target);

            f.step(1);

            assertEquals(List.of(0L), f.capture.markersAtSlime,
                    "NEEDS_WAITING pollution cleanup must not hide a Pick handoff before Slime preparation");
            assertEquals(1, f.markers());
            assertFalse(f.enginePlacer.isCleanupPendingAt(OLD.above(2)));
            f.world.blocks.put(SLIME, Blocks.SLIME_BLOCK.defaultBlockState()); // Deliver only the native support reply.
            f.acknowledgeEquipment();
            f.step(2);
            assertTrue(f.enginePlacer.isCleanupPendingAt(OLD.above(2)), "The deferred pollution cleanup still runs after the handoff");
            assertEquals(1, f.markers());
        }
    }

    private static final class CapturingMode extends BedrockPlacementRegressionTest.TestGameMode {
        Fixture owner;
        List<Long> markersAtTorch;
        List<Long> markersAtSlime;

        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            assertTrue(prediction);
            assertEquals(InteractionHand.MAIN_HAND, hand);
            var held = owner.client.player.getMainHandItem();
            assertTrue(held.is(Items.REDSTONE_TORCH) || held.is(Items.SLIME_BLOCK));
            if (held.is(Items.REDSTONE_TORCH)) markersAtTorch.add(owner.markers());
            else markersAtSlime.add(owner.markers());
            hits.add(hit);
            return InteractionResult.PASS;
        }
    }

    private static class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final BedrockInitialBatchPlacementTest.BatchLevel world;
        final CapturingMode capture;
        final BedrockCriticalExecutor critical;
        final BedrockCleanupCoordinator cleanup;

        Fixture(boolean materialHand) throws Exception {
            cleanupWorld();
            world = allocate(BedrockInitialBatchPlacementTest.BatchLevel.class);
            world.blocks = level.blocks;
            world.blocked = new HashSet<>();
            world.collisionChecks = new HashSet<>();
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            capture = allocate(CapturingMode.class);
            capture.owner = this;
            capture.markersAtTorch = new ArrayList<>();
            capture.markersAtSlime = new ArrayList<>();
            capture.hits = new ArrayList<>();
            capture.predictions = new ArrayList<>();
            client.gameMode = capture;
            critical = (BedrockCriticalExecutor) get(engine, BedrockEngine.class, "criticalExecutor");
            cleanup = (BedrockCleanupCoordinator) get(engine, BedrockEngine.class, "cleanup");
            world.blocks.remove(TARGET.above());
            world.blocks.put(SLIME, Blocks.SLIME_BLOCK.defaultBlockState());
            if (materialHand) {
                var inventory = (BedrockInventorySession) get(enginePlacer, BedrockPlacer.class, "inventory");
                assertTrue(inventory.prepareMaterial(Items.SLIME_BLOCK));
                assertEquals(2, InventoryUtils.getSelectedSlot(client.player.getInventory()));
            }
            transport.packets.clear();
        }

        BedrockTarget addSlimeTarget() throws Exception {
            var layout = target(TARGET, Direction.UP, enginePlacer).layout();
            var target = new BedrockTarget(TARGET, world, layout,
                    new BedrockTorchPlacement(SLIME, Direction.UP), SLIME, critical, enginePlacer, null);
            targets.add(target);
            return target;
        }

        BedrockTarget recoveringTarget() throws Exception {
            world.blocks.put(OLD, Blocks.BEDROCK.defaultBlockState());
            world.blocks.put(OLD.east(), Blocks.BEDROCK.defaultBlockState());
            world.blocks.put(RESIDUE, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.DOWN));
            var layout = target(OLD, Direction.UP, enginePlacer).layout();
            var target = new BedrockTarget(OLD, world, layout,
                    new BedrockTorchPlacement(OLD.east(), Direction.UP), null, critical, enginePlacer, null);
            target.setStatus(BedrockTarget.Status.RECOVERING);
            return target;
        }

        BedrockTarget poweredStallTarget() throws Exception {
            world.blocks.put(OLD, Blocks.BEDROCK.defaultBlockState());
            world.blocks.put(OLD.east(), Blocks.BEDROCK.defaultBlockState());
            world.blocks.put(RESIDUE, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
            var power = new BedrockTorchPlacement(OLD.east(), Direction.UP);
            world.blocks.put(power.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            var layout = target(OLD, Direction.UP, enginePlacer).layout();
            var target = new BedrockTarget(OLD, world, layout, power, null, critical, enginePlacer, null);
            target.onInitialBatchDispatched(power);
            var state = (BedrockTargetState) get(target, BedrockTarget.class, "state");
            state.beginTick(-40);
            state.beginTick(1);
            target.setStatus(BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE);
            rememberOldCycle(target);
            return target;
        }

        BedrockTarget pollutedHeadTarget() throws Exception {
            world.blocks.put(OLD, Blocks.BEDROCK.defaultBlockState());
            world.blocks.put(OLD.east(), Blocks.BEDROCK.defaultBlockState());
            world.blocks.put(RESIDUE, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
            world.blocks.put(OLD.above(2), Blocks.PISTON.defaultBlockState());
            var power = new BedrockTorchPlacement(OLD.east(), Direction.UP);
            world.blocks.put(power.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            var layout = target(OLD, Direction.UP, enginePlacer).layout();
            var target = new BedrockTarget(OLD, world, layout, power, null, critical, enginePlacer, null);
            target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            assertTrue(target.hasPollutedMachineState());
            rememberOldCycle(target);
            return target;
        }

        @SuppressWarnings("unchecked")
        private void rememberOldCycle(BedrockTarget target) throws Exception {
            var starts = (BedrockThroughputScheduler<BlockPos>) get(engine, BedrockEngine.class, "throughputScheduler");
            // These installed old machines entered their cycle before the independent material owner.
            starts.beginTick(-40, Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue(),
                    Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue(), true);
            starts.started(target.getBedrockPos());
        }

        void primingUpdate(BedrockTarget target) {
            var pos = target.getPistonPos();
            world.blocks.put(pos, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
        }

        void step(long now) throws Exception {
            tick(now);
            engine.beginTick(true);
            engine.finishTick(true);
        }

        long markers() { return transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance).count(); }

        void acknowledgeEquipment() {
            int sequence = transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance)
                    .map(ServerboundUseItemPacket.class::cast).mapToInt(ServerboundUseItemPacket::getSequence).max().orElseThrow();
            enginePlacer.acknowledgeToolTick(transport, sequence);
        }
    }
}
//#endif
