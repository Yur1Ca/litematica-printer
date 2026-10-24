//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.containersync;

import java.util.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import static me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncControllerTest.*;
import static me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncTypesTest.field;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSyncCrafterTest {
    @BeforeAll static void bootstrap() { ContainerSyncPlanTest.bootstrap(); }
    static class Player extends LocalPlayer {
        Inventory inventory;
        Player() { super(null, null, null, null, null, null, false, null); }
        @Override public Inventory getInventory() { return inventory; }
        @Override public void closeContainer() { containerMenu = inventoryMenu; }
    }
    static class CrafterFixture extends Fixture {
        final Player player = allocate(Player.class);
        final ContainerSyncClosePacketTest.Connection connection = allocate(ContainerSyncClosePacketTest.Connection.class);
        final CrafterMenu menu;
        CrafterFixture(boolean reference) throws Exception {
            connection.packets = new ArrayList<>();
            set(player, LocalPlayer.class, "connection", connection);
            player.inventory = new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
            set(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
            client.player = player;
            set(controller, ContainerSyncController.class, "connection", connection);
            menu = new CrafterMenu(7, player.inventory);
            player.containerMenu = menu;
            // Model a protected hotbar without relying on the live Litematica mixin transform.
            for (int i = 36; i < 45; i++) {
                int inventorySlot = menu.getSlot(i).getContainerSlot();
                var protectedSlot = new Slot(player.inventory, inventorySlot, 0, 0) {
                    @Override public boolean mayPickup(net.minecraft.world.entity.player.Player player) { return false; }
                };
                protectedSlot.index = i;
                menu.slots.set(i, protectedSlot);
            }
            set(controller, ContainerSyncController.class, "block", Blocks.CRAFTER);
            set(controller, ContainerSyncController.class, "size", 9);
            set(controller, ContainerSyncController.class, "target", net.minecraft.core.BlockPos.ZERO);
            set(controller, ContainerSyncController.class, "highlights", new HashSet<>());
            if (!reference) {
                var template = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
                template.set(0, new ItemStack(Items.STONE));
                set(controller, ContainerSyncController.class, "template", template);
                player.inventory.setItem(9, new ItemStack(Items.STONE, 64));
            }
            phase("OPENING");
            controller.opened(7, MenuType.CRAFTER_3x3);
        }
        void snapshot() { controller.content(7, menu.getStateId(), menu.slots.stream().map(s -> s.getItem().copy()).toList(), menu.getCarried()); }
        void data(int slot, int value) throws Exception {
            menu.setData(slot, value);
            controller.data(7, slot);
        }
        void ready() throws Exception { snapshot(); for (int i = 0; i < 9; i++) data(i, 0); }
        ContainerSyncPickupSemantics server() {
            return new ContainerSyncPickupSemantics(ContainerSyncLayout.CRAFTER.contents(menu.slots.stream()
                    .map(s -> s.getItem()).toList()), ContainerSyncPlanTest.RULES);
        }
        net.minecraft.network.protocol.game.ServerboundContainerClickPacket lastClick() {
            return assertInstanceOf(net.minecraft.network.protocol.game.ServerboundContainerClickPacket.class,
                    connection.packets.get(connection.packets.size() - 1));
        }
        void reply(ContainerSyncPickupSemantics server) {
            for (int i = 0; i < server.items.size(); i++) menu.getSlot(i).set(server.items.get(i).copy());
            menu.setCarried(server.cursor.copy());
            menu.incrementStateId();
            snapshot();
        }
    }

    @Test void delayedSlotStatesHoldTheLeaseBeforeAnyItemClick() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.snapshot();
            assertEquals("CONTENT", field(f.controller, "phase").toString());
            for (int i = 0; i < 8; i++) f.data(i, 0);
            for (int i = 0; i < 300; i++) f.controller.tick();
            assertTrue(f.connection.packets.isEmpty());
            assertFalse(f.otherInventoryOwner());
            f.data(8, 0);
            assertEquals("TRANSFER", field(f.controller, "phase").toString());
        }
    }

    @Test void disabledDestinationPausesWithoutChangingItsStateOrMovingItems() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.snapshot();
            assertEquals("CONTENT", field(f.controller, "phase").toString());
            for (int i = 0; i < 9; i++) f.data(i, i == 0 ? 1 : 0);
            assertEquals("PAUSED", field(f.controller, "phase").toString());
            assertTrue(f.menu.isSlotDisabled(0));
            assertTrue(f.connection.packets.isEmpty());
            assertTrue(f.otherInventoryOwner());
            assertEquals(64, f.player.inventory.getItem(9).getCount());
        }
    }

    @Test void resultPreviewIsNotIncludedInTheTemplate() throws Exception {
        try (var f = new CrafterFixture(true)) {
            f.menu.getSlot(0).set(new ItemStack(Items.STONE, 2));
            f.menu.getSlot(45).set(new ItemStack(Items.STONE_BUTTON));
            f.snapshot();
            assertEquals("CONTENT", field(f.controller, "phase").toString());
            for (int i = 0; i < 9; i++) f.data(i, 0);
            assertEquals("READY", field(f.controller, "phase").toString());
            var template = (List<?>) field(f.controller, "template");
            assertEquals(9, template.size());
            assertEquals(2, ((ItemStack) template.get(0)).getCount());
            assertEquals(1, template.stream().filter(s -> !((ItemStack) s).isEmpty()).count());
            assertTrue(f.connection.packets.isEmpty());
        }
    }

    @Test void statePacketsBeforeContentsAreAlsoAcceptedButForeignAndPoweredPacketsAreNot() throws Exception {
        try (var f = new CrafterFixture(false)) {
            for (int i = 0; i < 9; i++) f.controller.data(8, i);
            f.data(9, 1);
            f.snapshot();
            assertEquals("CONTENT", field(f.controller, "phase").toString());
            assertTrue(f.connection.packets.isEmpty());
        }
        try (var f = new CrafterFixture(false)) {
            for (int i = 0; i < 9; i++) f.data(i, 0);
            f.snapshot();
            assertEquals("TRANSFER", field(f.controller, "phase").toString());
        }
    }

    @Test void disabledEmptySlotIsPreservedAndDoesNotBlockOtherSlots() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.snapshot();
            for (int i = 0; i < 9; i++) f.data(i, i == 8 ? 1 : 0);
            assertEquals("TRANSFER", field(f.controller, "phase").toString());
            assertTrue(f.menu.isSlotDisabled(8));
        }
    }

    @Test void resultPreviewCannotSupplyMissingMaterials() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.player.inventory.setItem(9, ItemStack.EMPTY);
            f.menu.getSlot(45).set(new ItemStack(Items.STONE, 64));
            f.ready();
            assertEquals("PAUSED", field(f.controller, "phase").toString());
            assertTrue(f.connection.packets.isEmpty());
            assertTrue(f.otherInventoryOwner());
        }
    }

    @Test void changingInputWhileSlotStatesAreDelayedPausesWithoutUsingStalePlan() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.snapshot();
            f.menu.getSlot(0).set(new ItemStack(Items.DIRT));
            for (int i = 0; i < 9; i++) f.data(i, 0);
            assertEquals("PAUSED", field(f.controller, "phase").toString());
            assertTrue(f.connection.packets.isEmpty());
            assertTrue(f.otherInventoryOwner());
        }
    }

    @Test void disablingWhileStatePacketsAreDelayedClosesKnownEmptyCursorAndReleasesLease() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.snapshot();
            me.aleksilassila.litematica.printer.config.Configs.Core.WORK_SWITCH.setBooleanValue(false);
            f.controller.tick();
            assertEquals("IDLE", field(f.controller, "phase").toString());
            assertSame(f.player.inventoryMenu, f.player.containerMenu);
            assertTrue(f.otherInventoryOwner());
            for (int i = 0; i < 9; i++) f.controller.data(7, i);
            assertTrue(f.connection.packets.isEmpty());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"connection", "level"})
    void scopeChangeDiscardsDelayedStateAndContents(String scope) throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.snapshot();
            if (scope.equals("level")) f.client.level = allocate(net.minecraft.client.multiplayer.ClientLevel.class);
            else set(f.player, LocalPlayer.class, "connection", allocate(ContainerSyncClosePacketTest.Connection.class));
            f.controller.tick();
            for (int i = 0; i < 9; i++) f.controller.data(7, i);
            f.snapshot();
            assertEquals("IDLE", field(f.controller, "phase").toString());
            assertTrue(f.otherInventoryOwner());
            assertTrue(f.connection.packets.isEmpty());
        }
    }

    @Test void serverCloseWhileStatePacketsAreDelayedDoesNotTouchReplacementMenu() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.snapshot();
            f.player.containerMenu = f.player.inventoryMenu;
            f.controller.tick();
            for (int i = 0; i < 9; i++) f.controller.data(7, i);
            assertEquals("PAUSED", field(f.controller, "phase").toString());
            assertSame(f.player.inventoryMenu, f.player.containerMenu);
            assertTrue(f.otherInventoryOwner());
            assertTrue(f.connection.packets.isEmpty());
        }
    }

    @Test void previewChangesDoNotInvalidateConfirmedTransfersAndLatencyDoesNotRepeatClicks() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.ready();
            var server = f.server();
            for (int step = 0; step < 10 && field(f.controller, "phase").toString().equals("TRANSFER"); step++) {
                int before = f.connection.packets.size();
                f.controller.tick();
                if (field(f.controller, "phase").toString().equals("READY")) break;
                assertEquals(before + 1, f.connection.packets.size());
                for (int tick = 0; tick < 100; tick++) f.controller.tick();
                assertEquals(before + 1, f.connection.packets.size());
                assertFalse(f.otherInventoryOwner());
                var click = f.lastClick();
                assertTrue(click.slotNum() >= 0 && click.slotNum() < 45);
                server.click(click.slotNum(), click.buttonNum());
                f.menu.getSlot(45).set(new ItemStack(Items.STONE_BUTTON, step + 1));
                f.reply(server);
            }
            assertEquals("READY", field(f.controller, "phase").toString());
            assertEquals(1, server.items.get(0).getCount());
            assertTrue(server.cursor.isEmpty());
            assertSame(f.player.inventoryMenu, f.player.containerMenu);
        }
    }

    @Test void disablingWithPickupInFlightReturnsCursorOnlyAfterServerConfirmation() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.ready();
            var server = f.server();
            f.controller.tick();
            var pickup = f.lastClick();
            server.click(pickup.slotNum(), pickup.buttonNum());
            me.aleksilassila.litematica.printer.config.Configs.Core.WORK_SWITCH.setBooleanValue(false);
            for (int tick = 0; tick < 100; tick++) f.controller.tick();
            assertEquals(1, f.connection.packets.size());
            assertFalse(f.otherInventoryOwner());
            f.reply(server);
            f.controller.tick();
            var recovery = f.lastClick();
            assertEquals(pickup.slotNum(), recovery.slotNum());
            server.click(recovery.slotNum(), recovery.buttonNum());
            assertTrue(server.cursor.isEmpty());
            assertFalse(f.otherInventoryOwner());
            f.reply(server);
            f.controller.tick();
            assertEquals("IDLE", field(f.controller, "phase").toString());
            assertTrue(f.otherInventoryOwner());
            assertTrue(server.items.get(0).isEmpty());
            assertEquals(64, f.player.inventory.getItem(9).getCount());
        }
    }

    @Test void slotDisableAfterPickupStopsPlacementAndRecoversTheOwnedCursor() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.ready();
            var server = f.server();
            f.controller.tick();
            var pickup = f.lastClick();
            server.click(pickup.slotNum(), pickup.buttonNum());
            f.reply(server);
            f.data(0, 1);
            f.controller.tick();
            assertEquals(-1, f.lastClick().slotNum(), "A changed disabled mask requests a snapshot instead of placing");
            f.reply(server);
            var transfer = (ContainerSyncTransfer) field(f.controller, "transfer");
            transfer.tick(System.nanoTime() / 1_000_000L + 5001, true);
            var recovery = f.lastClick();
            assertEquals(pickup.slotNum(), recovery.slotNum());
            server.click(recovery.slotNum(), recovery.buttonNum());
            f.reply(server);
            f.controller.tick();
            assertEquals("PAUSED", field(f.controller, "phase").toString());
            assertTrue(server.cursor.isEmpty());
            assertTrue(f.menu.isSlotDisabled(0));
            assertTrue(f.otherInventoryOwner());
        }
    }
}
//#endif
