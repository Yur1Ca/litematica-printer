//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Counts actual initial USE packets after real preparation barriers, rather than counting reservations. */
class BedrockStartCadenceTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final List<BlockPos> TARGETS = List.of(FIRST, FIRST.east(4), FIRST.east(8),
            FIRST.south(4), FIRST.south(4).east(4), FIRST.south(4).east(8));

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void aReleasedInventoryFenceStartsOneRealCycleInsteadOfDumpingSixReservations() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(2);
            f.beginFence();
            for (BlockPos pos : TARGETS) reserve(f, pos);
            f.tick(0);
            f.engine.beginTick(true);
            f.engine.finishTick(true);
            assertEquals(0, pistonUses(f));
            assertEquals(6, f.targets.size());
            InventoryRecoveryFence.received(f.transport);
            assertFalse(f.enginePlacer.isInventorySettling());
            f.tick(10);

            f.engine.beginTick(true);
            f.engine.finishTick(true);

            assertEquals(1, pistonUses(f), "A backlog released by a real inventory reply must obey the first-USE cadence");
            f.engine.beginTick(true);
            f.engine.finishTick(true);
            assertEquals(1, pistonUses(f), "Repeated begin/finish entries in one client tick cannot grant another new start");
            f.tick(11);
            f.engine.beginTick(true);
            f.engine.finishTick(true);
            assertEquals(1, pistonUses(f));
            f.tick(12);
            f.engine.beginTick(true);
            f.engine.finishTick(true);
            assertEquals(2, pistonUses(f));
        }
    }

    @Test void anEquipmentMarkerCannotAccumulateStartsForItsReleaseTick() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(2);
            f.beginFence();
            InventoryRecoveryFence.received(f.transport);
            assertFalse(f.enginePlacer.isInventorySettling());
            assertFalse(f.enginePlacer.prepareCriticalToolTick());
            assertTrue(f.enginePlacer.isToolSettling());
            int sequence = f.transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance)
                    .map(ServerboundUseItemPacket.class::cast).mapToInt(ServerboundUseItemPacket::getSequence).max().orElseThrow();
            for (BlockPos pos : TARGETS) reserve(f, pos);

            for (int tick = 0; tick < 4; tick++) {
                f.tick(tick);
                f.engine.beginTick(true);
                f.engine.finishTick(true);
                assertEquals(tick / 2 + 1, pistonUses(f),
                        "A funded OFF_HAND start can overlap the marker, but only at its actual start cadence");
                assertTrue(f.enginePlacer.isToolSettling());
            }
            f.enginePlacer.acknowledgeToolTick(f.transport, sequence);
            assertTrue(f.enginePlacer.prepareCriticalToolTick());
            f.tick(4);
            f.engine.beginTick(true);
            f.engine.finishTick(true);
            assertEquals(3, pistonUses(f), "The marker reply cannot release accumulated new-cycle credits");
        }
    }

    @Test void anObservedSlimeAndAFailedCandidateDoNotSpendTheNextRealStart() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(2);
            set(f.client, Minecraft.class, "gui", f.allocate(Gui.class));
            f.beginFence();
            InventoryRecoveryFence.received(f.transport);
            assertFalse(f.enginePlacer.isInventorySettling());
            reserveWithObservedSlime(f, FIRST);
            f.level.blocks.put(FIRST.above(2), Blocks.STONE.defaultBlockState());
            reserve(f, TARGETS.get(1));
            f.tick(0);

            f.engine.beginTick(true);
            f.engine.finishTick(true);

            var uses = f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).filter(packet -> packet.getHand() == InteractionHand.OFF_HAND).toList();
            assertEquals(1, uses.size());
            assertEquals(TARGETS.get(1).above(), uses.getFirst().getHitResult().getBlockPos()
                    .relative(uses.getFirst().getHitResult().getDirection()),
                    "Observing an existing support and rejecting a blocked machine cannot spend another owner's first USE");
        }
    }

    @Test void anEntityBlockedFirstCandidateCannotStarveTheNextActualStart() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(2);
            var world = f.allocate(BedrockInitialBatchPlacementTest.BatchLevel.class);
            world.blocks = f.level.blocks;
            world.blocked = new HashSet<>(List.of(FIRST.above()));
            world.collisionChecks = new HashSet<>();
            set(world, Level.class, "dimension", Level.OVERWORLD);
            f.client.level = world;
            f.beginFence();
            InventoryRecoveryFence.received(f.transport);
            assertFalse(f.enginePlacer.isInventorySettling());
            var blocked = reserve(f, FIRST);
            reserve(f, TARGETS.get(1));
            f.tick(0);

            f.engine.beginTick(true);
            f.engine.finishTick(true);

            assertTrue(world.collisionChecks.contains(FIRST.above()), "The first candidate reaches the real placement collision check");
            assertEquals(BedrockTarget.Status.UNINITIALIZED, blocked.getStatus(),
                    "An entity collision leaves a live candidate, rather than retiring it like a blocked head");
            var uses = f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).filter(packet -> packet.getHand() == InteractionHand.OFF_HAND).toList();
            assertEquals(1, uses.size(), "A rejected candidate cannot consume the actual-start allowance");
            assertEquals(TARGETS.get(1).above(), uses.getFirst().getHitResult().getBlockPos()
                    .relative(uses.getFirst().getHitResult().getDirection()));
        }
    }

    @Test void anEntityBlockedCriticalCandidateCannotStarveTheNextActualStart() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(2);
            f.cleanupWorld();
            var world = f.allocate(BedrockInitialBatchPlacementTest.BatchLevel.class);
            world.blocks = f.level.blocks;
            world.blocked = new HashSet<>(List.of(FIRST.above()));
            world.collisionChecks = new HashSet<>();
            set(world, Level.class, "dimension", Level.OVERWORLD);
            f.client.level = world;
            assertFalse(f.enginePlacer.prepareCriticalToolTick(), "Changing worlds invalidates the old equipment acknowledgement");
            int sequence = f.transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance)
                    .map(ServerboundUseItemPacket.class::cast).mapToInt(ServerboundUseItemPacket::getSequence).max().orElseThrow();
            f.enginePlacer.acknowledgeToolTick(f.transport, sequence);
            assertTrue(f.enginePlacer.prepareCriticalToolTick(), "The real marker for this world must be acknowledged before critical preflight");
            var blocked = reserve(f, FIRST);
            var second = reserve(f, TARGETS.get(1));
            for (var target : List.of(blocked, second)) {
                world.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                        .setValue(PistonBaseBlock.FACING, Direction.UP).setValue(PistonBaseBlock.EXTENDED, true));
                world.blocks.put(target.getHeadPos(), Blocks.PISTON_HEAD.defaultBlockState()
                        .setValue(PistonHeadBlock.FACING, Direction.UP));
                world.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            }
            f.transport.packets.clear();
            f.tick(0);

            f.engine.beginTick(true);
            f.engine.finishTick(true);

            assertTrue(world.collisionChecks.contains(FIRST.above()), "The first critical reaches replacement collision validation");
            assertFalse(blocked.hasTried(), "Rejected preflight must not dispatch a critical body");
            assertTrue(second.hasTried(), "A rejected preflight cannot reserve the only new-cycle start");
            var uses = f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).filter(packet -> packet.getHand() == InteractionHand.OFF_HAND).toList();
            assertEquals(1, uses.size());
            assertEquals(second.getPistonPos(), uses.getFirst().getHitResult().getBlockPos()
                    .relative(uses.getFirst().getHitResult().getDirection()));
        }
    }

    @Test void anAlreadyStartedCycleCanFinishItsTorchDuringTheClosedStartWindow() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(2);
            var mode = f.allocate(MaterialMode.class);
            mode.hits = new ArrayList<>();
            mode.hands = new ArrayList<>();
            f.client.gameMode = mode;
            set(f.runtime, PrinterRuntime.class, "inventorySwitchGuard", new InventorySwitchGuard(f.client, f.runtime::currentTick));
            f.beginFence();
            InventoryRecoveryFence.received(f.transport);
            assertFalse(f.enginePlacer.isInventorySettling());
            var first = reserveWithObservedSlime(f, FIRST);
            reserve(f, TARGETS.get(1));
            f.tick(0);
            f.engine.beginTick(true);
            f.engine.finishTick(true);
            assertEquals(1, pistonUses(f));
            f.level.blocks.put(first.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP));
            f.tick(1);

            f.engine.beginTick(true);
            f.engine.finishTick(true);

            assertEquals(1, pistonUses(f), "The next owner still waits for its start interval");
            assertEquals(List.of(InteractionHand.MAIN_HAND), mode.hands,
                    "The started owner's material continuation must not wait for the next new-cycle window");
        }
    }

    private static final class MaterialMode extends BedrockPlacementRegressionTest.TestGameMode {
        List<InteractionHand> hands;
        MaterialMode() { super(); }
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            this.hands.add(hand);
            this.hits.add(hit);
            return InteractionResult.PASS;
        }
    }

    private static BedrockTarget reserveWithObservedSlime(BedrockAdmissionPipelineTest.Fixture f, BlockPos pos) throws Exception {
        f.level.blocks.put(pos.east(), Blocks.SLIME_BLOCK.defaultBlockState());
        return reserve(f, pos, pos.east());
    }

    private static BedrockTarget reserve(BedrockAdmissionPipelineTest.Fixture f, BlockPos pos) throws Exception {
        return reserve(f, pos, null);
    }

    private static BedrockTarget reserve(BedrockAdmissionPipelineTest.Fixture f, BlockPos pos, BlockPos slime) throws Exception {
        var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
        var sync = (BedrockNetworkSync) get(f.engine, BedrockEngine.class, "networkSync");
        var layout = f.target(pos, Direction.UP, f.enginePlacer).layout();
        var target = new BedrockTarget(pos, f.client.level, layout,
                new BedrockTorchPlacement(pos.east(), Direction.UP), slime, critical, f.enginePlacer, sync);
        f.targets.add(target);
        return target;
    }

    private static long pistonUses(BedrockAdmissionPipelineTest.Fixture f) {
        return f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                .map(ServerboundUseItemOnPacket.class::cast).filter(packet -> packet.getHand() == InteractionHand.OFF_HAND).count();
    }
}
//#endif
