//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Ordinary initial/replacement torch interactions use their own actual native submission receipt. */
class BedrockTorchAcknowledgementTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @CsvSource({"repower,false", "repower,true", "initial,false", "initial,true"})
    void aProcessedOrdinaryTorchUseDoesNotRestartAnUnnecessaryColdWait(String entry, boolean actionTick) throws Exception {
        try (var f = new Fixture(entry)) {
            int previous = f.target.initializeTick();
            f.placer.acknowledgeToolTick(f.sent, Integer.MAX_VALUE);
            assertTrue(f.place(entry));
            int torch = f.lastTorchSequence();
            int packets = f.sent.packets.size();
            int initialized = f.target.initializeTick();
            assertEquals(entry.equals("repower") ? f.target.tickTimes() : previous, initialized);
            assertFalse(f.target.hasDeferredInitialTorch());
            f.world.blocks.put(f.target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(105, actionTick));
            f.world.blocks.remove(f.target.getTorchPos());
            var world = new HashMap<>(f.world.blocks);

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(106, actionTick),
                    "A pre-enqueue ACK cannot authorize this newly emitted torch USE");
            f.placer.acknowledgeToolTick(new Object(), torch);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(106, actionTick));
            f.placer.acknowledgeToolTick(f.sent, torch - 1);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(106, actionTick));
            f.placer.acknowledgeToolTick(f.sent, torch);

            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(107, actionTick),
                    "The actual replacement/initial torch USE has been processed and left a stable powerless piston");
            assertEquals(initialized, f.target.initializeTick());
            assertFalse(f.target.hasTried());
            assertEquals(packets, f.sent.packets.size(), "Classifying a receipt cannot add synchronization packets");
            assertEquals(world, f.world.blocks);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"repower", "initial"})
    void aPassWithoutANativeUseCannotAuthorizeTheOrdinaryTorchWindow(String entry) throws Exception {
        try (var f = new Fixture(entry)) {
            f.useMode.emit = false;
            int before = f.torchUses().size();

            assertTrue(f.place(entry), "Preserve the existing interaction-result contract independently of receipt evidence");
            assertEquals(before, f.torchUses().size());
            f.placer.acknowledgeToolTick(f.sent, Integer.MAX_VALUE);

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(107, false),
                    "PASS and an unrelated ACK cannot manufacture a Torch USE receipt");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"moving_base", "moving_head", "piston_head", "polluted_torch", "existing_torch"})
    void anOrdinaryTorchReceiptPreservesAnimationPollutionAndOwnedPowerGuards(String input) throws Exception {
        try (var f = new Fixture("repower")) {
            assertTrue(f.place("repower"));
            f.placer.acknowledgeToolTick(f.sent, f.lastTorchSequence());
            switch (input) {
                case "moving_base" -> f.world.blocks.put(f.target.getPistonPos(), Blocks.MOVING_PISTON.defaultBlockState());
                case "moving_head" -> f.world.blocks.put(f.target.getHeadPos(), Blocks.MOVING_PISTON.defaultBlockState());
                case "piston_head" -> f.world.blocks.put(f.target.getHeadPos(), Blocks.PISTON_HEAD.defaultBlockState()
                        .setValue(PistonHeadBlock.FACING, Direction.UP));
                case "polluted_torch" -> f.world.blocks.put(f.target.getTorchPos(), Blocks.SLIME_BLOCK.defaultBlockState());
                case "existing_torch" -> f.world.blocks.put(f.target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
                default -> throw new AssertionError(input);
            }

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(107, false));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"repower"})
    void anotherRepowerKeepsTheFourTickIntervalAndNeedsItsOwnAcknowledgement(String entry) throws Exception {
        try (var f = new Fixture(entry)) {
            assertTrue(f.place(entry));
            int first = f.lastTorchSequence();
            f.placer.acknowledgeToolTick(f.sent, first);
            int initialized = f.target.initializeTick();
            int uses = f.torchUses().size();
            f.observe(107, false);

            assertFalse(f.target.tryRepowerTorch(), "The original four-tick repower interval is still in force");
            assertEquals(initialized, f.target.initializeTick());
            assertEquals(uses, f.torchUses().size());
            f.observe(108, false);
            assertTrue(f.target.tryRepowerTorch());
            int second = f.lastTorchSequence();
            assertTrue(second > first);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(109, false));
            f.placer.acknowledgeToolTick(f.sent, first);
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(109, false));
            f.placer.acknowledgeToolTick(f.sent, second);

            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(110, false));
        }
    }

    static class ObservedTransport extends BedrockPlacementRegressionTest.CaptureTransport {
        @Override void capture(Packet<?> packet) {
            // The existing Connection mixin observes each real submission before delegating to transport.
            NetworkUtils.observeUseItemOn(this, packet);
            super.capture(packet);
        }
    }

    static class SendingMode extends BedrockPlacementRegressionTest.TestGameMode {
        Minecraft client;
        boolean emit;
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            assertTrue(prediction);
            assertEquals(InteractionHand.MAIN_HAND, hand);
            hits.add(hit);
            predictions.add(prediction);
            if (this.emit) NetworkUtils.sendPacket(sequence -> new ServerboundUseItemOnPacket(hand, hit, sequence));
            return InteractionResult.PASS;
        }
    }

    private static class Fixture extends BedrockPlacementRegressionTest.Fixture {
        final BedrockInitialAcknowledgementTest.SequencedLevel world;
        final ObservedTransport sent;
        final SendingMode useMode;
        final BedrockTarget target;
        Fixture(String entry) throws Exception {
            world = allocate(BedrockInitialAcknowledgementTest.SequencedLevel.class);
            world.blocks = level.blocks;
            world.blocked = new HashSet<>();
            world.collisionChecks = new HashSet<>();
            world.nextSequence = 100;
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            sent = allocate(ObservedTransport.class);
            sent.packets = new ArrayList<>();
            connection.transport = sent;
            useMode = allocate(SendingMode.class);
            useMode.client = client;
            useMode.emit = true;
            useMode.hits = new ArrayList<>();
            useMode.predictions = new ArrayList<>();
            client.gameMode = useMode;
            client.player.getInventory().setItem(0, new ItemStack(Items.REDSTONE_TORCH, 32));
            engine();
            synchronizeInventory();
            readyTool();
            sent.packets.clear();
            var power = new BedrockTorchPlacement(TARGET.east(), Direction.UP);
            world.blocks.put(power.getSupportPos(), Blocks.BEDROCK.defaultBlockState());
            target = new BedrockTarget(TARGET, world, target().layout(), power, null,
                    new BedrockCriticalExecutor(placer), placer, new BedrockNetworkSync(runtime::currentTick));
            assertEquals(BedrockTarget.Status.UNINITIALIZED, observe(100, false));
            assertEquals(BedrockTarget.Status.UNINITIALIZED, observe(102, false));
            var requests = placer.placePistonBatch(List.of(new BedrockPlacer.PistonRequest(target.getPistonPos(), Direction.UP,
                    entry.equals("initial") ? null : power)));
            assertEquals(1, requests.size());
            target.onInitialBatchDispatched(requests.getFirst().torchPlacement());
            world.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
            var initialUses = sent.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).toList();
            placer.acknowledgeToolTick(sent, initialUses.getLast().getSequence());
            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, observe(104, false));
        }

        boolean place(String entry) { return entry.equals("initial") ? target.placeInitialTorch() : target.tryRepowerTorch(); }

        List<ServerboundUseItemOnPacket> torchUses() {
            return sent.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).filter(use -> use.getHand() == InteractionHand.MAIN_HAND).toList();
        }

        int lastTorchSequence() { return torchUses().getLast().getSequence(); }

        BedrockTarget.Status observe(long tick, boolean actionTick) throws Exception {
            tick(tick);
            return actionTick ? target.tick(false, false) : target.refreshStatusOnly();
        }
    }
}
//#endif
