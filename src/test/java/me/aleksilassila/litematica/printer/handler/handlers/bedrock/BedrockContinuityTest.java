//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.ArrayList;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real production handoffs and native replies; no Minecraft physics or throughput model. */
class BedrockContinuityTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final BlockPos NEXT = TARGET.east(4);
    private static final BlockPos OLD = TARGET.west(4);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void readyGearAcknowledgementCannotBePreemptedByAnotherCyclesProvider(boolean dirtyBudget) throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            f.machine(Direction.UP);
            var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
            var sync = (BedrockNetworkSync) get(f.engine, BedrockEngine.class, "networkSync");
            var ready = new BedrockTarget(TARGET, f.client.level, f.target(TARGET, Direction.UP, f.enginePlacer).layout(),
                    new BedrockTorchPlacement(TARGET.east(), Direction.UP), null, critical, f.enginePlacer, sync);
            f.level.blocks.put(ready.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            ready.setStatus(BedrockTarget.Status.EXTENDED);
            f.targets.add(ready);
            f.targets.add(new BedrockTarget(NEXT, f.client.level, f.target(NEXT, Direction.UP, f.enginePlacer).layout(),
                    new BedrockTorchPlacement(NEXT.east(), Direction.UP), null, critical, f.enginePlacer, sync));
            f.put(40, Items.PISTON, 1); // Enough for the ready attempt; the other owner still needs two.
            var inventory = (BedrockInventorySession) get(f.enginePlacer, BedrockPlacer.class, "inventory");
            var tool = (BedrockToolSync) get(inventory, BedrockInventorySession.class, "toolSync");
            tool.invalidateTool();
            f.transport.packets.clear();
            assertFalse(f.enginePlacer.prepareCriticalToolTick());
            assertTrue(f.enginePlacer.isToolSettling());
            if (dirtyBudget) f.enginePlacer.recordToolUse();
            long inventoryMarkers = f.transport.packets.stream().filter(ServerboundClientCommandPacket.class::isInstance).count();
            f.tick(8);

            f.engine.observeSupplies(f.requests);
            f.engine.beginTick(true);
            assertFalse(ready.hasTried(), "The sole outstanding dependency is its already-sent native tool boundary");
            assertEquals(BedrockTarget.Status.EXTENDED, ready.getStatus());
            f.engine.prepareMaterials(f.requests);

            assertAll(
                    () -> assertTrue(f.sent.isEmpty(), "Another cycle's refill acquired the provider before this ready gear boundary completed"),
                    () -> assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY), "The ready attempt's inventory lane was preempted"),
                    () -> assertFalse(f.enginePlacer.isInventorySettling(), "The existing gear wait cannot gain a construction snapshot dependency"),
                    () -> assertEquals(inventoryMarkers,
                            f.transport.packets.stream().filter(ServerboundClientCommandPacket.class::isInstance).count(),
                            "A ready attempt must not gain another construction reconciliation dependency"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"no_ready", "unpowered", "unfunded", "recovering", "weak_hand"})
    void aGearWaitWithoutACompleteFundedAttemptCannotHoldBackSupplies(String incomplete) throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            f.machine(Direction.UP);
            var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
            var sync = (BedrockNetworkSync) get(f.engine, BedrockEngine.class, "networkSync");
            var ready = new BedrockTarget(TARGET, f.client.level, f.target(TARGET, Direction.UP, f.enginePlacer).layout(),
                    new BedrockTorchPlacement(TARGET.east(), Direction.UP), null, critical, f.enginePlacer, sync);
            f.level.blocks.put(ready.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            ready.setStatus(incomplete.equals("recovering") ? BedrockTarget.Status.RECOVERING : BedrockTarget.Status.EXTENDED);
            if (!incomplete.equals("no_ready")) f.targets.add(ready);
            if (incomplete.equals("unpowered")) f.level.blocks.remove(ready.getTorchPos());
            f.targets.add(new BedrockTarget(NEXT, f.client.level, f.target(NEXT, Direction.UP, f.enginePlacer).layout(),
                    new BedrockTorchPlacement(NEXT.east(), Direction.UP), null, critical, f.enginePlacer, sync));
            f.put(40, Items.PISTON, incomplete.equals("unfunded") ? 0 : 1);
            if (incomplete.equals("weak_hand")) f.put(0, Items.STONE, 1);
            var inventory = (BedrockInventorySession) get(f.enginePlacer, BedrockPlacer.class, "inventory");
            var tool = (BedrockToolSync) get(inventory, BedrockInventorySession.class, "toolSync");
            tool.invalidateTool();
            f.transport.packets.clear();
            assertFalse(f.enginePlacer.prepareCriticalToolTick());
            f.tick(8);

            f.engine.observeSupplies(f.requests);
            f.engine.beginTick(true);
            f.engine.prepareMaterials(f.requests);

            assertFalse(f.sent.isEmpty(), "The incomplete machine must not turn a gear wait into a supply deadlock: " + incomplete);
            assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void historicalPreparedPowerCannotWarmThePickForAMovingRecoveryOwner() throws Exception {
        PhaseResult withoutHistory = materialPhase(false);
        PhaseResult withHistory = materialPhase(true);
        assertEquals(0, withoutHistory.toolMarkers(), "The existing single-Slime R11/R12 control must remain marker-free");
        assertEquals(1, withHistory.primingUses(), "The independent priming request really reached production transport");
        assertEquals(0, withHistory.hardActions(), "MOVING_PISTON cannot require a hard cleanup handoff in this frame");
        assertTrue(withHistory.deferredTorch());
        assertAll(
                () -> assertEquals(0, withHistory.toolMarkers(), "A historical power milestone is not a current critical or cleanup demand"),
                () -> assertEquals(2, withHistory.selectedSlot(), "The still-incomplete material phase lost MAIN to a needless Pick warmup"));
    }

    @Test void aFreshPoweredInitialBatchStillOverlapsItsNativeToolBoundaryWithMachineConfirmation() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var world = f.allocate(BedrockInitialBatchPlacementTest.BatchLevel.class);
            world.blocks = f.level.blocks;
            world.blocked = new HashSet<>();
            world.collisionChecks = new HashSet<>();
            set(world, Level.class, "dimension", Level.OVERWORLD);
            f.client.level = world;
            world.blocks.remove(TARGET.above());
            var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
            var inventory = (BedrockInventorySession) get(f.enginePlacer, BedrockPlacer.class, "inventory");
            assertTrue(inventory.prepareMaterial(Items.SLIME_BLOCK));
            var target = new BedrockTarget(TARGET, world, f.target(TARGET, Direction.UP, f.enginePlacer).layout(),
                    new BedrockTorchPlacement(TARGET.east(), Direction.UP), null, critical, f.enginePlacer, null);
            f.targets.add(target);
            f.transport.packets.clear();
            f.tick(1);

            f.engine.beginTick(true);

            assertTrue(target.hasPreparedPower());
            assertEquals(2, f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
            assertEquals(1, f.transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance).count(),
                    "Fresh initial power must retain its early native equipment boundary");
            assertFalse(world.blocks.containsKey(target.getPistonPos()));
            int sequence = f.transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance)
                    .map(ServerboundUseItemPacket.class::cast).mapToInt(ServerboundUseItemPacket::getSequence).max().orElseThrow();
            f.enginePlacer.acknowledgeToolTick(f.transport, sequence);
            assertTrue(inventory.prepareMaterial(Items.SLIME_BLOCK));
            f.transport.packets.clear();
            f.tick(2);

            f.engine.beginTick(true);

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, target.getStatus());
            assertEquals(1, f.transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance).count(),
                    "A genuine fresh initialization waiting for world replies can still warm the changed hand");
            assertTrue(f.transport.packets.stream().noneMatch(ServerboundUseItemOnPacket.class::isInstance));
        }
    }

    @Test void aRebuildCooldownCannotRefreshInitializationAfterRecoveryReceivesNativeAir() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var target = agedPoweredTarget(f);
            int actualInitialization = target.initializeTick();
            target.recordRebuild();
            target.setStatus(BedrockTarget.Status.RECOVERING);
            f.level.blocks.remove(target.getPistonPos());
            f.level.blocks.remove(target.getHeadPos());
            f.level.blocks.remove(target.getTorchPos());
            assertEquals(BedrockTarget.Status.UNINITIALIZED, target.refreshStatusOnly());

            var afterPreparation = target.tickPreparation();

            assertAll(
                    () -> assertEquals(actualInitialization, target.initializeTick(),
                            "A retry cooldown is not a new piston or power placement receipt"),
                    () -> assertEquals(BedrockTarget.Status.UNINITIALIZED, afterPreparation,
                            "Native AIR completed recovery; a cooldown cannot manufacture another initialization window"));
            assertTrue(f.mode.hits.isEmpty(), "The preparation-only stage did not send a new initialization USE");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void onlyASuccessfulTorchRepowerRefreshesTheActualInitializationWindow(boolean accepted) throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var target = agedPoweredTarget(f);
            f.level.blocks.remove(target.getTorchPos());
            int before = target.initializeTick();
            var mode = f.allocate(RepowerMode.class);
            mode.accepted = accepted;
            mode.hits = new ArrayList<>();
            mode.predictions = new ArrayList<>();
            f.client.gameMode = mode;

            assertEquals(accepted, target.tryRepowerTorch());

            assertEquals(1, mode.hits.size(), "The real Torch preparation path reached the interaction port");
            assertEquals(accepted ? target.tickTimes() : before, target.initializeTick());
            assertEquals(accepted ? BedrockTarget.Status.NEEDS_WAITING : BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE,
                    target.refreshStatusOnly(), "Only an accepted power placement starts another native confirmation window");
        }
    }

    private static BedrockTarget agedPoweredTarget(BedrockAdmissionPipelineTest.Fixture f) throws Exception {
        var power = new BedrockTorchPlacement(TARGET.east(), Direction.UP);
        f.level.blocks.put(power.getSupportPos(), Blocks.BEDROCK.defaultBlockState());
        f.level.blocks.put(power.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
        f.level.blocks.put(TARGET.above(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
        f.level.blocks.remove(TARGET.above(2));
        var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
        var target = new BedrockTarget(TARGET, f.client.level, f.target(TARGET, Direction.UP, f.enginePlacer).layout(),
                power, null, critical, f.enginePlacer, null);
        target.onInitialBatchDispatched(power);
        var state = (BedrockTargetState) get(target, BedrockTarget.class, "state");
        state.beginTick(-40);
        state.beginTick(1);
        f.tick(1);
        assertEquals(0, target.initializeTick());
        assertTrue(target.tickTimes() > target.postExecuteSyncTimeoutTicks());
        return target;
    }

    private static final class RepowerMode extends BedrockPlacementRegressionTest.TestGameMode {
        boolean accepted;
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            assertTrue(prediction);
            assertEquals(InteractionHand.MAIN_HAND, hand);
            hits.add(hit);
            return this.accepted ? InteractionResult.PASS : InteractionResult.FAIL;
        }
    }

    private PhaseResult materialPhase(boolean historicalOwner) throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var world = f.allocate(BedrockInitialBatchPlacementTest.BatchLevel.class);
            world.blocks = f.level.blocks;
            world.blocked = new HashSet<>();
            world.collisionChecks = new HashSet<>();
            set(world, Level.class, "dimension", Level.OVERWORLD);
            f.client.level = world;
            world.blocks.remove(TARGET.above());
            world.blocks.put(TARGET.east(), Blocks.SLIME_BLOCK.defaultBlockState());
            var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
            var inventory = (BedrockInventorySession) get(f.enginePlacer, BedrockPlacer.class, "inventory");
            assertTrue(inventory.prepareMaterial(Items.SLIME_BLOCK));
            if (historicalOwner) {
                world.blocks.put(OLD, Blocks.BEDROCK.defaultBlockState());
                world.blocks.put(OLD.east(), Blocks.BEDROCK.defaultBlockState());
                var power = new BedrockTorchPlacement(OLD.east(), Direction.UP);
                world.blocks.put(power.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
                var old = new BedrockTarget(OLD, world, f.target(OLD, Direction.UP, f.enginePlacer).layout(),
                        power, null, critical, f.enginePlacer, null);
                old.onInitialBatchDispatched(power);
                assertTrue(old.hasPreparedPower());
                // Native replies after the previous powered-stall recovery removed its Torch and began retraction.
                world.blocks.remove(power.getTorchPos());
                world.blocks.put(old.getPistonPos(), Blocks.MOVING_PISTON.defaultBlockState());
                old.setStatus(BedrockTarget.Status.RECOVERING);
                f.targets.add(old);
            }
            var target = new BedrockTarget(TARGET, world, f.target(TARGET, Direction.UP, f.enginePlacer).layout(),
                    new BedrockTorchPlacement(TARGET.east(), Direction.UP), TARGET.east(), critical, f.enginePlacer, null);
            f.targets.add(target);
            f.transport.packets.clear();

            f.tick(1);
            f.engine.beginTick(true);
            f.engine.finishTick(true);

            return new PhaseResult(
                    f.transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance).count(),
                    f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count(),
                    f.transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance).count(),
                    InventoryUtils.getSelectedSlot(f.client.player.getInventory()), target.hasDeferredInitialTorch());
        }
    }

    private record PhaseResult(long toolMarkers, long primingUses, long hardActions, int selectedSlot,
                               boolean deferredTorch) { }
}
//#endif
