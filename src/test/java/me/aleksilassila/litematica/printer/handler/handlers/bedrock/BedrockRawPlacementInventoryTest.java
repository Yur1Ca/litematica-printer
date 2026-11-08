//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import java.util.ArrayList;
import java.util.List;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Raw placement stays ordered without a reply per use; actual inventory calibration still gates mutations. */
class BedrockRawPlacementInventoryTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    static class CaptureConnection extends BedrockPlacementRegressionTest.TestConnection {
        Connection transport;
        List<Packet<?>> packets;
        @Override public Connection getConnection() { return this.transport; }
        @Override public void send(Packet<?> packet) {
            this.packets.add(packet);
            InventoryRecoveryFence.inputSent(this.transport, packet);
            InventoryRecoveryFence.sent(this.transport, packet);
        }
    }

    static class Fixture extends BedrockPlacementRegressionTest.Fixture {
        CaptureConnection connection;
        Fixture() throws Exception {
            super();
            var player = this.client.player;
            set(player, Player.class, "inventoryMenu", new InventoryMenu(player.getInventory(), true, player));
            player.containerMenu = player.inventoryMenu;
            this.replaceConnection();
        }

        void replaceConnection() throws Exception {
            this.connection = this.allocate(CaptureConnection.class);
            this.connection.transport = new Connection(PacketFlow.CLIENTBOUND);
            this.connection.packets = new ArrayList<>();
            set(this.client.player, LocalPlayer.class, "connection", this.connection);
        }

        boolean settling() throws Exception {
            var method = assertDoesNotThrow(() -> BedrockPlacer.class.getDeclaredMethod("isInventorySettling"),
                    "Actual inventory calibration needs a cross-tick response barrier");
            method.setAccessible(true);
            return (boolean) method.invoke(this.placer);
        }

        void acknowledge() {
            assertTrue(this.connection.packets.stream().anyMatch(packet -> packet instanceof ServerboundClientCommandPacket command
                    && command.getAction() == ServerboundClientCommandPacket.Action.REQUEST_STATS),
                    "A stats marker must follow the full inventory sync request");
            InventoryRecoveryFence.received(this.connection.transport);
        }

        void beginFence() {
            var fence = InventorySyncPackets.prepareFence(this.connection.transport);
            this.connection.send(InventorySyncPackets.createPlayerInventoryFullSync(this.client.player));
            this.connection.send(fence.packet());
            me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.recordFence(this.placer, fence);
        }
    }

    @Test void ordinaryRawPistonPlacementPredictsConsumptionWithoutAnInventoryRoundTrip() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.place(TARGET.above(), Direction.UP));
            assertEquals(15, f.client.player.getInventory().getItem(40).getCount(),
                    "Initial raw placement must not leave its spent piston in the offhand prediction");
            assertFalse(f.mode.predictions.getFirst(), "World replacement still waits for the server");
            var packets = f.connection.packets;
            assertFalse(packets.stream().anyMatch(ServerboundContainerClickPacket.class::isInstance));
            assertFalse(packets.stream().anyMatch(ServerboundClientCommandPacket.class::isInstance));
            assertFalse(f.settling(), "A raw USE does not start an unrelated inventory response barrier");
        }
    }

    @Test void aDelayedActualCalibrationReplyKeepsBothSimpleAndRawPlacementsPausedAcrossTicks() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.place(TARGET.above(), Direction.UP));
            assertFalse(f.settling());
            f.beginFence();
            f.tick(10);
            // An older full-content response can restore a higher hand count.
            // It must not release the barrier for the outstanding placement marker.
            f.client.player.getInventory().setItem(40, new ItemStack(Items.PISTON, 16));
            assertTrue(f.settling());
            int previousHits = f.mode.hits.size();
            assertFalse(f.placer.placePiston(TARGET.above(), Direction.UP));
            assertFalse(f.placer.placeSimple(TARGET, Direction.UP, Items.PISTON));
            assertEquals(previousHits, f.mode.hits.size());
            f.acknowledge();
            assertFalse(f.settling());
            assertTrue(f.placer.placePiston(TARGET.above(), Direction.UP));
        }
    }

    @Test void changingConnectionDropsTheOldResponseBarrier() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.place(TARGET.above(), Direction.UP));
            f.beginFence();
            assertTrue(f.settling());
            f.replaceConnection();
            assertFalse(f.settling());
        }
    }

    @Test void clearingPreparedLookCannotDropAnOutstandingInventoryBarrier() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.place(TARGET.above(), Direction.UP));
            f.beginFence();
            f.placer.clearHorizontalLookState();
            assertTrue(f.settling());
            var reset = assertDoesNotThrow(() -> BedrockPlacer.class.getDeclaredMethod("resetInventorySync"));
            reset.setAccessible(true);
            reset.invoke(f.placer);
            assertFalse(f.settling());
        }
    }
}
//#endif
