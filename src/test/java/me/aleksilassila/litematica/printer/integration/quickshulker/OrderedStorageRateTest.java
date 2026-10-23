//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real drain/adapter and vanilla click semantics; simulated RTT is not a live server measurement. */
class OrderedStorageRateTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(ints = {1, 32, 63, 64})
    void entireHotbarStackReturnsWithOneConfirmedSwap(int count) throws Exception {
        try (var f = new OrderedStorageDrainTest.Fixture(2, 64)) {
            f.menu.slots.get(1).set(new ItemStack(Items.PISTON, count));
            f.storage.restorePendingItem();
            f.adapter.tick();
            var click = (ServerboundContainerClickPacket) f.connection.packets.getFirst();
            assertEquals(ContainerInput.SWAP, click.containerInput());
            assertEquals(0, click.slotNum());
            assertEquals(2, click.buttonNum());
            assertEquals(-1, click.stateId(), "Keep the authoritative full snapshot barrier");
            assertEquals(count, f.menu.slots.get(1).getItem().getCount(), "Do not predict the swap");
            for (int i = 0; i < 300; i++) f.adapter.tick();
            assertEquals(1, f.connection.packets.size(), "A delayed reply must not replay the swap");
            f.assertExclusion();
            f.reply(f.menu, 0, List.of(new ItemStack(Items.PISTON, count), ItemStack.EMPTY), ItemStack.EMPTY);
            assertFalse(f.player.closed, "An old revision cannot complete the return");
            f.reply(f.menu, 1, List.of(new ItemStack(Items.PISTON, count), ItemStack.EMPTY), ItemStack.EMPTY);
            assertTrue(f.player.closed, "The accepted final snapshot closes without another client tick");
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(f.player.inventoryMenu.getCarried().isEmpty());
            assertEquals(count, f.menu.slots.get(0).getItem().getCount());
            assertEquals(1, f.connection.packets.size());
        }
    }

    @Test void confirmedPickupAdvancesAndFinalReplyClosesBeforeTheNextClientTick() throws Exception {
        try (var f = new OrderedStorageDrainTest.Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            f.reply(f.menu, 1, List.of(ItemStack.EMPTY, ItemStack.EMPTY), new ItemStack(Items.PISTON, 64));
            assertEquals(2, f.connection.packets.size(), "The first accepted snapshot sends the next single click");
            assertFalse(f.player.closed);
            f.assertExclusion();
            f.reply(f.menu, 2, List.of(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"work", "quick", "orderly"})
    void disablingBeforeTheReplyReturnsTheCursorWithoutAClientTick(String feature) throws Exception {
        try (var f = new OrderedStorageDrainTest.Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            disable(feature);
            f.reply(f.menu, 1, List.of(ItemStack.EMPTY, ItemStack.EMPTY), new ItemStack(Items.PISTON, 64));
            assertEquals(2, f.connection.packets.size());
            var recovery = (ServerboundContainerClickPacket) f.connection.packets.getLast();
            assertEquals(ContainerInput.PICKUP, recovery.containerInput());
            assertEquals(1, recovery.slotNum(), "All three feature switches must cancel before event-driven progress");
            f.assertExclusion();
            f.reply(f.menu, 2, List.of(ItemStack.EMPTY, new ItemStack(Items.PISTON, 64)), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"work", "quick", "orderly"})
    void disablingAnInFlightSwapStillWaitsForItsFinalServerReply(String feature) throws Exception {
        try (var f = new OrderedStorageDrainTest.Fixture(2, 64)) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            disable(feature);
            f.adapter.tick();
            f.assertExclusion();
            assertFalse(f.player.closed);
            assertEquals(1, f.connection.packets.size());
            f.reply(f.menu, 1, List.of(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(1, f.connection.packets.size());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"main_inventory", "occupied_target", "limited_target"})
    void incompatibleSourceOrTargetKeepsThePickupPath(String constraint) throws Exception {
        int source = constraint.equals("main_inventory") ? 9 : 2;
        try (var f = new OrderedStorageDrainTest.Fixture(source, constraint.equals("limited_target") ? 16 : 64)) {
            if (constraint.equals("occupied_target")) {
                f.menu.slots.get(0).set(new ItemStack(Items.PISTON, 16));
                f.menu.slots.get(1).set(new ItemStack(Items.PISTON, 32));
            }
            f.storage.restorePendingItem();
            var click = (ServerboundContainerClickPacket) f.connection.packets.getFirst();
            assertEquals(ContainerInput.PICKUP, click.containerInput());
            assertEquals(1, click.slotNum());
            assertFalse(f.player.closed);
        }
    }

    @Test void forcedCloseOfASwapWaitsForTheReplacementSnapshotWithoutReplaying() throws Exception {
        try (var f = new OrderedStorageDrainTest.Fixture(2, 64)) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            f.player.containerMenu = f.player.inventoryMenu;
            f.adapter.tick();
            assertEquals(2, f.connection.packets.size());
            f.assertExclusion();
            f.reply(f.menu, 1, List.of(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY), ItemStack.EMPTY);
            f.assertExclusion();
            f.reply(f.player.inventoryMenu, 1,
                    f.player.inventoryMenu.slots.stream().map(s -> s.getItem().copy()).toList(), ItemStack.EMPTY);
            assertFalse(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(2, f.connection.packets.size());
        }
    }

    @Test void changingServerDiscardsAnInFlightSwapWithoutTouchingTheNewMenu() throws Exception {
        try (var f = new OrderedStorageDrainTest.Fixture(2, 64)) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            var replacement = new OrderedStorageDrainTest.ReturnMenu(new SimpleContainer(1), f.player.inventory, 2, 64);
            f.player.containerMenu = replacement;
            f.resetScope("client_scope_changed");
            assertFalse(f.storage.isDraining());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            f.reply(replacement, 1, List.of(ItemStack.EMPTY, ItemStack.EMPTY), ItemStack.EMPTY);
            assertSame(replacement, f.player.containerMenu);
            assertFalse(f.player.closed);
            assertEquals(1, f.connection.packets.size());
        }
    }

    @ParameterizedTest @ValueSource(ints = {32, 64})
    void vanillaSwapMovesTheWholeStackIntoAnEmptyDestinationWithoutUsingTheCursor(int count) throws Exception {
        var server = server(2, count);
        server.menu.clicked(0, 2, ContainerInput.SWAP, server.player);
        assertEquals(count, server.menu.slots.get(0).getItem().getCount());
        assertTrue(server.menu.slots.get(1).getItem().isEmpty());
        assertTrue(server.menu.getCarried().isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {100, 250})
    void simulatedRoundTripUsesOneAcknowledgementForSwapAndTwoForPickup(int roundTripMillis) throws Exception {
        // Each captured request is executed by real vanilla clicked(), then delivered exactly one
        // modeled RTT later. No sleeping, actual network, or claim about real server throughput.
        assertEquals(roundTripMillis, replayAtFixedRoundTrip(2, roundTripMillis));
        assertEquals(2L * roundTripMillis, replayAtFixedRoundTrip(9, roundTripMillis));
    }

    private static long replayAtFixedRoundTrip(int source, int roundTripMillis) throws Exception {
        try (var f = new OrderedStorageDrainTest.Fixture(source, 64)) {
            var server = server(source, 64);
            f.storage.restorePendingItem();
            f.adapter.tick();
            long elapsed = 0;
            int handled = 0;
            while (!f.player.closed) {
                assertEquals(handled + 1, f.connection.packets.size(), "Exactly one request in flight and no tick gap after a reply");
                assertTrue(handled < 2, "Whole-stack return takes at most two clicks");
                var click = (ServerboundContainerClickPacket) f.connection.packets.get(handled++);
                server.menu.clicked(click.slotNum(), click.buttonNum(), click.containerInput(), server.player);
                elapsed += roundTripMillis;
                f.reply(f.menu, handled, server.menu.slots.stream().map(s -> s.getItem().copy()).toList(), server.menu.getCarried().copy());
            }
            assertEquals(64, server.menu.slots.get(0).getItem().getCount());
            assertTrue(server.menu.slots.get(1).getItem().isEmpty());
            assertTrue(server.menu.getCarried().isEmpty());
            return elapsed;
        }
    }

    private record Server(QuickShulkerPacketContractTest.AuditPlayer player, OrderedStorageDrainTest.ReturnMenu menu) { }

    private static Server server(int source, int count) throws Exception {
        var player = QuickShulkerPacketContractTest.allocate(QuickShulkerPacketContractTest.AuditPlayer.class);
        player.levelForAudit = QuickShulkerPacketContractTest.allocate(QuickShulkerPacketContractTest.AuditLevel.class);
        player.inventoryForAudit = new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
        player.inventoryForAudit.setItem(source, new ItemStack(Items.PISTON, count));
        var menu = new OrderedStorageDrainTest.ReturnMenu(new SimpleContainer(1), player.inventoryForAudit, source, 64);
        return new Server(player, menu);
    }

    private static void disable(String feature) {
        if (feature.equals("work")) Configs.Core.WORK_SWITCH.setBooleanValue(false);
        else if (feature.equals("quick")) Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
        else Configs.Placement.STORE_ORDERLY.setBooleanValue(false);
    }
}
//#endif
