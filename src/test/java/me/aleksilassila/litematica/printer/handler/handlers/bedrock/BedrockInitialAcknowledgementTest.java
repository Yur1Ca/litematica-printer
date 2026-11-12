//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.minecraft.PredictiveAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Native initial USE receipts shorten only a stable, processed, unsuccessful power request. */
class BedrockInitialAcknowledgementTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final BlockPos SECOND = new BlockPos(2, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void onlyTheActualInitialTorchAcknowledgementCanEndTheColdWait(boolean actionTick) throws Exception {
        try (var f = new Fixture()) {
            var target = f.machineTarget(FIRST, new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            f.dispatch(target);
            int piston = f.sequence(InteractionHand.OFF_HAND, target.getPistonPos());
            int torch = f.sequence(InteractionHand.MAIN_HAND, target.getTorchPos());
            assertTrue(torch > piston);
            assertFalse(target.initialExtensionObserved());
            assertFalse(target.hasDeferredInitialTorch());
            var world = new HashMap<>(f.world.blocks);
            int packets = f.batch.packets.size();

            f.placer.acknowledgeToolTick(new Object(), torch);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(target, 103, actionTick));
            f.placer.acknowledgeToolTick(f.batch, piston);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(target, 104, actionTick));
            f.placer.acknowledgeToolTick(f.batch, torch);

            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE,
                    f.observe(target, 105, actionTick),
                    "A processed torch USE with a stable powerless priming piston needs no remaining cold timeout");
            assertEquals(2, target.initializeTick());
            assertFalse(target.hasTried());
            assertEquals(packets, f.batch.packets.size(), "Acknowledgement observation adds no synchronization packet");
            assertEquals(world, f.world.blocks, "A rejected placement must not be predicted as a torch");
        }
    }

    @Test void eachTargetWaitsForItsOwnTorchUseInsideOneNativeBatch() throws Exception {
        try (var f = new Fixture()) {
            var first = f.machineTarget(FIRST, new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            var second = f.machineTarget(SECOND, new BedrockTorchPlacement(SECOND.south(), Direction.UP));
            f.dispatch(first, second);
            int firstTorch = f.sequence(InteractionHand.MAIN_HAND, first.getTorchPos());
            int secondTorch = f.sequence(InteractionHand.MAIN_HAND, second.getTorchPos());
            assertTrue(secondTorch > firstTorch);

            f.placer.acknowledgeToolTick(f.batch, firstTorch);

            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(first, 103, false));
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(second, 103, false),
                    "A different target's processed torch must not authorize this target");
            f.placer.acknowledgeToolTick(f.batch, secondTorch);
            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(second, 104, false));
        }
    }

    @Test void anAcknowledgementObservedBeforeEnqueueCannotAuthorizeTheLaterBatch() throws Exception {
        try (var f = new Fixture()) {
            var target = f.machineTarget(FIRST, new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            f.placer.acknowledgeToolTick(f.batch, Integer.MAX_VALUE);
            f.dispatch(target);
            int piston = f.sequence(InteractionHand.OFF_HAND, target.getPistonPos());
            int torch = f.sequence(InteractionHand.MAIN_HAND, target.getTorchPos());

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(target, 103, false),
                    "Only an ACK observed after this native batch was enqueued can be its receipt");
            f.placer.acknowledgeToolTick(f.batch, piston);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(target, 104, false),
                    "An old high watermark cannot be combined with a newer insufficient ACK");
            f.placer.acknowledgeToolTick(f.batch, torch);
            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(target, 105, false));
        }
    }

    @Test void oneSharedTorchUseAcknowledgesBothOfItsInitialMachines() throws Exception {
        try (var f = new Fixture()) {
            var power = new BedrockTorchPlacement(FIRST.east(), Direction.UP);
            var first = f.machineTarget(FIRST, power);
            var second = f.machineTarget(SECOND, power);
            f.dispatch(first, second);
            assertEquals(1, f.uses().stream().filter(use -> use.getHand() == InteractionHand.MAIN_HAND).count());
            int torch = f.sequence(InteractionHand.MAIN_HAND, power.getTorchPos());

            f.placer.acknowledgeToolTick(f.batch, torch - 1);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(first, 103, false));
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(second, 103, false));
            f.placer.acknowledgeToolTick(f.batch, torch);

            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(first, 104, false));
            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(second, 104, false));
        }
    }

    @Test void aReusedTorchThatDisappearsWaitsOnlyForItsRealPistonAcknowledgement() throws Exception {
        try (var f = new Fixture()) {
            var target = f.machineTarget(FIRST, new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            f.world.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            f.dispatch(target);
            assertEquals(1, f.uses().size());
            assertEquals(InteractionHand.OFF_HAND, f.uses().getFirst().getHand());
            f.world.blocks.remove(target.getTorchPos());
            int piston = f.uses().getFirst().getSequence();
            f.placer.acknowledgeToolTick(f.batch, piston - 1);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(target, 103, false));
            f.placer.acknowledgeToolTick(f.batch, piston);

            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(target, 104, false),
                    "A reused source has no pending torch USE; its receipt must refer to the real piston USE");
        }
    }

    @Test void explicitlyDeferredPowerKeepsItsExistingMaterialRecoveryWithoutATorchReceipt() throws Exception {
        try (var f = new Fixture()) {
            var target = f.machineTarget(FIRST, new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            f.client.player.getInventory().setItem(6, ItemStack.EMPTY);
            f.readyInventory();
            f.dispatch(target);
            assertEquals(1, f.uses().size());
            assertTrue(target.hasDeferredInitialTorch());

            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(target, 103, false));
        }
    }

    @Test void aToolAcknowledgementCannotReplaceAnUnsentInitialTorchRequest() throws Exception {
        try (var f = new Fixture()) {
            var target = f.machineTarget(FIRST, new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            f.begin(target);
            f.batch.reject = true;
            assertTrue(f.placer.placePistonBatch(List.of(f.request(target))).isEmpty());
            assertTrue(f.batch.packets.isEmpty());
            target.onInitialBatchDispatched(target.initialBatchTorchPlacement());
            f.priming(target);
            f.placer.acknowledgeToolTick(f.batch, Integer.MAX_VALUE);

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(target, 103, false));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"same_tick", "second_callback", "reset_attempt"})
    void aNewInitializationCannotReuseAnOldTorchReceipt(String input) throws Exception {
        try (var f = new Fixture()) {
            var target = f.machineTarget(FIRST, new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            f.dispatch(target);
            f.placer.acknowledgeToolTick(f.batch, f.sequence(InteractionHand.MAIN_HAND, target.getTorchPos()));
            switch (input) {
                case "same_tick" -> target.setInitializeTick(target.initializeTick());
                case "second_callback" -> target.onInitialBatchDispatched(target.initialBatchTorchPlacement());
                case "reset_attempt" -> {
                    target.markDispatched();
                    target.resetPostExecuteAttempt(BedrockTarget.Status.RECOVERING);
                    f.world.blocks.remove(target.getPistonPos());
                    assertEquals(BedrockTarget.Status.UNINITIALIZED, f.observe(target, 102, false));
                    assertEquals(BedrockTarget.Status.UNINITIALIZED, f.observe(target, 104, false));
                    target.onInitialBatchDispatched(target.initialBatchTorchPlacement());
                    f.priming(target);
                }
                default -> throw new AssertionError(input);
            }

            assertEquals(2, target.initializeTick(), "Equal relative tick values still identify distinct attempts");
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(target, 105, false));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"moving_base", "moving_head", "piston_head", "polluted_torch", "existing_torch"})
    void acknowledgedInitialPowerStillPreservesAnimationPollutionAndOwnedPowerGuards(String input) throws Exception {
        try (var f = new Fixture()) {
            var target = f.machineTarget(FIRST, new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            f.dispatch(target);
            f.placer.acknowledgeToolTick(f.batch, f.sequence(InteractionHand.MAIN_HAND, target.getTorchPos()));
            switch (input) {
                case "moving_base" -> f.world.blocks.put(target.getPistonPos(), Blocks.MOVING_PISTON.defaultBlockState());
                case "moving_head" -> f.world.blocks.put(target.getHeadPos(), Blocks.MOVING_PISTON.defaultBlockState());
                case "piston_head" -> f.world.blocks.put(target.getHeadPos(), Blocks.PISTON_HEAD.defaultBlockState()
                        .setValue(PistonHeadBlock.FACING, Direction.UP));
                case "polluted_torch" -> f.world.blocks.put(target.getTorchPos(), Blocks.SLIME_BLOCK.defaultBlockState());
                case "existing_torch" -> f.world.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
                default -> throw new AssertionError(input);
            }
            var world = new HashMap<>(f.world.blocks);

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(target, 103, false));
            assertEquals(world, f.world.blocks);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"epoch", "level", "connection", "player", "reset"})
    void anAcknowledgedInitialRequestCannotCrossClientContextBoundaries(String input) throws Exception {
        try (var f = new Fixture()) {
            var target = f.machineTarget(FIRST, new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            f.dispatch(target);
            f.placer.acknowledgeToolTick(f.batch, f.sequence(InteractionHand.MAIN_HAND, target.getTorchPos()));
            switch (input) {
                case "epoch" -> set(f.runtime, f.runtime.getClass(), "epoch", f.runtime.epoch().next());
                case "level" -> f.client.level = f.level;
                case "connection" -> f.connection.transport = f.transport;
                case "player" -> {
                    var replacement = f.allocate(BedrockInitialBatchPlacementTest.PoweredPlayer.class);
                    replacement.inventory = f.client.player.getInventory();
                    f.client.player = replacement;
                }
                case "reset" -> f.placer.resetInventorySync();
                default -> throw new AssertionError(input);
            }

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(target, 103, false));
        }
    }

    static class SequencedLevel extends BedrockInitialBatchPlacementTest.BatchLevel {
        int nextSequence;
        @Override public Packet<ServerGamePacketListener> litematica_printer3$predict(PredictiveAction action) {
            return action.predict(nextSequence++);
        }
    }

    private static class Fixture extends BedrockInitialBatchPlacementTest.Fixture {
        final SequencedLevel world;
        Fixture() throws Exception {
            world = allocate(SequencedLevel.class);
            world.blocks = level.blocks;
            world.blocked = new HashSet<>();
            world.collisionChecks = new HashSet<>();
            world.nextSequence = 100;
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            client.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            client.player.getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 8));
            engine();
            readyInventory();
        }

        BedrockTarget machineTarget(BlockPos pos, BedrockTorchPlacement power) throws Exception {
            world.blocks.put(pos, Blocks.BEDROCK.defaultBlockState());
            world.blocks.put(power.getSupportPos(), Blocks.BEDROCK.defaultBlockState());
            return new BedrockTarget(pos, world, target(pos, Direction.UP, placer).layout(), power, null,
                    new BedrockCriticalExecutor(placer), placer, new BedrockNetworkSync(runtime::currentTick));
        }

        void begin(BedrockTarget target) throws Exception {
            assertEquals(BedrockTarget.Status.UNINITIALIZED, observe(target, 100, false));
            assertEquals(BedrockTarget.Status.UNINITIALIZED, observe(target, 102, false));
        }

        BedrockPlacer.PistonRequest request(BedrockTarget target) {
            return new BedrockPlacer.PistonRequest(target.getPistonPos(), Direction.UP, target.initialBatchTorchPlacement());
        }

        void dispatch(BedrockTarget... targets) throws Exception {
            for (var target : targets) begin(target);
            var requests = java.util.Arrays.stream(targets).map(this::request).toList();
            var accepted = placer.placePistonBatch(requests);
            assertEquals(targets.length, accepted.size());
            for (int i = 0; i < targets.length; i++) {
                assertEquals(targets[i].getPistonPos(), accepted.get(i).pistonPos());
                targets[i].onInitialBatchDispatched(accepted.get(i).torchPlacement());
                priming(targets[i]);
            }
            assertEquals(1, batch.batches);
        }

        void priming(BedrockTarget target) {
            world.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP));
            world.blocks.remove(target.getHeadPos());
        }

        List<ServerboundUseItemOnPacket> uses() {
            return batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).toList();
        }

        int sequence(InteractionHand hand, BlockPos placed) {
            return uses().stream().filter(use -> use.getHand() == hand
                    && use.getHitResult().getBlockPos().relative(use.getHitResult().getDirection()).equals(placed))
                    .findFirst().orElseThrow().getSequence();
        }

        BedrockTarget.Status observe(BedrockTarget target, long tick, boolean actionTick) throws Exception {
            tick(tick);
            return actionTick ? target.tick(false, false) : target.refreshStatusOnly();
        }

        @Override public void close() throws Exception {
            try { assertTrue(mode.hits.isEmpty(), "Receipt observation cannot submit a predicted placement"); }
            finally { super.close(); }
        }
    }
}
//#endif
