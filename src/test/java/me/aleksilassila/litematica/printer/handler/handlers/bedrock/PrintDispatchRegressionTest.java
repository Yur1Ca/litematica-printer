package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.mc.MixinConnection;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.PlacementPolicy;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real dispatch queue against a headless world and the outbound game-mode boundary. */
class PrintDispatchRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootstrap() {
        BedrockPlacementRegressionTest.bootstrap();
    }

    @Test
    void localFailureAfterUsePacketIsSentDoesNotRejectTheDispatchedAttempt() throws Exception {
        try (var f = new DispatchFixture()) {
            List<ActionManager.SendResult> completions = new ArrayList<>();
            f.queue(1);
            assertTrue(f.manager.setQueueCompletionListener(completions::add));

            assertEquals(ActionManager.SendResult.SENT, f.manager.sendQueue(f.client.player),
                    "A local prediction failure cannot unsend the matching use packet");
            assertEquals(List.of(ActionManager.SendResult.SENT), completions);
            assertEquals(1, f.transport.packets.size(), "Dispatch observation must not send a replacement packet");

            // A later, genuinely cancelled use must not inherit the first attempt's send evidence.
            f.dispatch.emitOnCall = call -> false;
            f.queue(1);
            assertTrue(f.manager.setQueueCompletionListener(completions::add));
            assertEquals(ActionManager.SendResult.INTERACTION_REJECTED, f.manager.sendQueue(f.client.player));
            assertEquals(List.of(ActionManager.SendResult.SENT, ActionManager.SendResult.INTERACTION_REJECTED), completions);
            assertEquals(1, f.transport.packets.size());
        }
    }

    @Test
    void normalPassKeepsItsAcceptedDispatchResult() throws Exception {
        try (var f = new DispatchFixture()) {
            f.dispatch.result = InteractionResult.PASS;
            f.dispatch.emitOnCall = call -> false;
            f.queue(1);

            assertEquals(ActionManager.SendResult.SENT, f.manager.sendQueue(f.client.player));
            assertTrue(f.transport.packets.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Emission.class, names = {"OTHER_CONNECTION", "OTHER_HAND", "OTHER_TARGET"})
    void unrelatedUsePacketCannotTurnACancelledAttemptIntoSent(Emission emission) throws Exception {
        try (var f = new DispatchFixture()) {
            f.dispatch.emission = emission;
            f.queue(1);

            assertEquals(ActionManager.SendResult.INTERACTION_REJECTED, f.manager.sendQueue(f.client.player));
            assertEquals(1, f.transport.packets.size());
        }
    }

    @Test
    void cancelledRepeatDoesNotConsumeTheNextRepeatsReserveAllowance() throws Exception {
        try (var f = new DispatchFixture()) {
            // Two available items permit two dispatched uses, even with a cancelled use between them.
            f.client.player.getInventory().setItem(0, new ItemStack(Items.STONE, 2));
            f.dispatch.emitOnCall = call -> call != 2;
            f.queue(3);

            assertEquals(ActionManager.SendResult.SENT, f.manager.sendQueue(f.client.player));
            assertEquals(2, f.transport.packets.size(),
                    "Send evidence must be fresh for each repeat, so the cancelled second use leaves room for the third");
            assertEquals(3, f.dispatch.calls);
        }
    }

    @Test
    void connectionHookObservesTheOriginalPacketWithoutReplacingOrSendingIt() throws Exception {
        var connection = new MixinConnection();
        BlockHitResult hit = new BlockHitResult(new Vec3(0.5, 65.0, 0.5), Direction.UP, TARGET, false);
        Packet<?> packet = usePacket(InteractionHand.MAIN_HAND, hit, 1);
        var hook = MixinConnection.class.getDeclaredMethod("printer$trackRecoveryInput", Packet.class);
        hook.setAccessible(true);

        try (var scope = NetworkUtils.trackUseItemOn(connection, InteractionHand.MAIN_HAND, hit)) {
            assertFalse(scope.wasSent());
            assertSame(packet, hook.invoke(connection, packet));
            assertTrue(scope.wasSent(), "The actual Connection.sendPacket hook must record dispatch");
        }
    }

    private static Packet<?> usePacket(InteractionHand hand, BlockHitResult hit, int sequence) {
        //#if MC > 11802
        return new ServerboundUseItemOnPacket(hand, hit, sequence);
        //#else
        //$$ return new ServerboundUseItemOnPacket(hand, hit);
        //#endif
    }

    private enum Emission {
        MATCHING,
        OTHER_CONNECTION,
        OTHER_HAND,
        OTHER_TARGET
    }

    private static class DispatchGameMode extends BedrockPlacementRegressionTest.TestGameMode {
        DispatchFixture fixture;
        InteractionResult result;
        IntPredicate emitOnCall;
        Emission emission;
        int calls;

        @Override
        public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            assertTrue(prediction, "Ordinary print dispatch must keep the existing local prediction path");
            assertEquals(InteractionHand.MAIN_HAND, hand);
            calls++;
            if (emitOnCall.test(calls)) {
                InteractionHand packetHand = emission == Emission.OTHER_HAND ? InteractionHand.OFF_HAND : hand;
                BlockHitResult packetHit = emission == Emission.OTHER_TARGET
                        ? new BlockHitResult(hit.getLocation(), hit.getDirection(), TARGET.east(), false) : hit;
                Packet<?> packet = usePacket(packetHand, packetHit, calls);
                fixture.connection.send(packet);
                // The fixture transport does not run transformed mixins. Observe at the same
                // boundary as the real Connection.sendPacket hook, after emitting a real use packet.
                NetworkUtils.observeUseItemOn(emission == Emission.OTHER_CONNECTION
                        ? new Object() : fixture.transport, packet);
            }
            return result;
        }
    }

    @SuppressWarnings({"removal", "deprecation"})
    private static class DispatchFixture extends BedrockPlacementRegressionTest.Fixture {
        final boolean oldQuickShulker = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        final ActionManager manager = new ActionManager(source -> new PlacementPolicy(() -> 0, false, false));
        final DispatchGameMode dispatch;

        DispatchFixture() throws Exception {
            // Reference is a separate static Minecraft cache used by the real ActionManager.
            var referenceClient = Reference.class.getDeclaredField("MINECRAFT");
            referenceClient.setAccessible(true);
            cachedClients.put(referenceClient, referenceClient.get(null));
            unsafe.putObject(unsafe.staticFieldBase(referenceClient), unsafe.staticFieldOffset(referenceClient), client);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            client.player.getInventory().setItem(0, new ItemStack(Items.STONE, 16));
            InventoryUtils.setSelectedSlot(client.player.getInventory(), 0);
            dispatch = allocate(DispatchGameMode.class);
            dispatch.fixture = this;
            dispatch.result = InteractionResult.FAIL;
            dispatch.emitOnCall = call -> true;
            dispatch.emission = Emission.MATCHING;
            client.gameMode = dispatch;
        }

        void queue(int repeats) {
            assertTrue(manager.queueClick(TARGET, Direction.UP, Vec3.ZERO, false, repeats,
                    new Item[]{Items.STONE}, ActionManager.ActionSource.PRINT));
        }

        @Override
        public void close() throws Exception {
            Configs.Placement.QUICK_SHULKER.setBooleanValue(oldQuickShulker);
            super.close();
        }
    }
}
