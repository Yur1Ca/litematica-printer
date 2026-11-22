//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.containersync;

import java.util.*;
import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
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
        final RecordingListener connection = listener;
        final CrafterMenu menu;
        CrafterFixture(boolean reference) throws Exception {
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
        List<ServerboundContainerClickPacket> batchSince(int start) {
            var packets = connection.packets.subList(start, connection.packets.size());
            int noOpIndex = packets.size() - 2;
            assertTrue(noOpIndex > 0);
            var snapshotRequest = assertInstanceOf(ServerboundContainerClickPacket.class, packets.get(noOpIndex));
            assertEquals(-1, snapshotRequest.slotNum());
            assertEquals(-1, snapshotRequest.stateId());
            var marker = assertInstanceOf(ServerboundClientCommandPacket.class, packets.getLast());
            assertEquals(ServerboundClientCommandPacket.Action.REQUEST_STATS, marker.getAction());
            var clicks = new ArrayList<ServerboundContainerClickPacket>();
            for (var packet : packets.subList(0, noOpIndex)) {
                var click = assertInstanceOf(ServerboundContainerClickPacket.class, packet);
                assertEquals(menu.containerId, click.containerId());
                assertEquals(ContainerInput.PICKUP, click.containerInput());
                assertTrue(click.slotNum() >= 0 && click.slotNum() < 45, "Never click the recipe preview");
                clicks.add(click);
            }
            return clicks;
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

    @Test void cancellingWhileStatePacketsAreDelayedClosesKnownEmptyCursorAndReleasesLease() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.snapshot();
            f.controller.toggle();
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
            else set(f.player, LocalPlayer.class, "connection", allocate(RecordingListener.class));
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
            f.controller.tick();
            var clicks = f.batchSince(0);
            assertTrue(clicks.size() > 1, "A multi-click move must fit in a batch");
            for (var click : clicks) {
                server.click(click.slotNum(), click.buttonNum());
            }
            f.menu.getSlot(45).set(new ItemStack(Items.STONE_BUTTON));
            f.reply(server);
            int sent = f.connection.packets.size();
            for (int tick = 0; tick < 100; tick++) f.controller.tick();
            assertEquals(sent, f.connection.packets.size());
            assertEquals("TRANSFER", field(f.controller, "phase").toString());
            assertFalse(f.otherInventoryOwner());
            InventoryRecoveryFence.received(f.connection.getConnection());
            f.controller.tick();
            assertEquals("READY", field(f.controller, "phase").toString());
            assertEquals(1, server.items.get(0).getCount());
            assertTrue(server.cursor.isEmpty());
            assertSame(f.player.inventoryMenu, f.player.containerMenu);
        }
    }

    @Test void cancellingWithAPartialBatchInFlightReturnsCursorOnlyAfterServerConfirmation() throws Exception {
        try (var f = new CrafterFixture(false)) {
            var template = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
            template.set(0, new ItemStack(Items.STONE, 29));
            set(f.controller, ContainerSyncController.class, "template", template);
            f.ready();
            var server = f.server();
            f.controller.tick();
            var clicks = f.batchSince(0);
            f.controller.toggle();
            for (var click : clicks) server.click(click.slotNum(), click.buttonNum());
            assertFalse(server.cursor.isEmpty(), "The last click in the first batch left material on the cursor");
            int placed = server.items.get(0).getCount();
            assertTrue(placed > 0 && placed < 29);
            f.reply(server);
            int sent = f.connection.packets.size();
            for (int tick = 0; tick < 100; tick++) f.controller.tick();
            assertEquals(sent, f.connection.packets.size());
            assertFalse(f.otherInventoryOwner());
            InventoryRecoveryFence.received(f.connection.getConnection());
            f.controller.tick();
            var recoveryBatch = f.batchSince(sent);
            assertEquals(1, recoveryBatch.size(), "The only remaining move is the cursor return");
            var recovery = recoveryBatch.getFirst();
            assertEquals(clicks.getFirst().slotNum(), recovery.slotNum());
            server.click(recovery.slotNum(), recovery.buttonNum());
            assertTrue(server.cursor.isEmpty());
            assertFalse(f.otherInventoryOwner());
            f.reply(server);
            f.controller.tick();
            assertFalse(f.otherInventoryOwner());
            f.ack();
            assertEquals("IDLE", field(f.controller, "phase").toString());
            assertTrue(f.otherInventoryOwner());
            assertEquals(placed, server.items.get(0).getCount());
            assertEquals(64 - placed, f.player.inventory.getItem(9).getCount());
        }
    }

    @Test void slotDisabledAfterPickupRejectsInsertionAndStopsAfterTheBatch() throws Exception {
        try (var f = new CrafterFixture(false)) {
            f.ready();
            var server = f.server();
            f.controller.tick();
            var clicks = f.batchSince(0);
            var pickup = clicks.getFirst();
            server.click(pickup.slotNum(), pickup.buttonNum());
            f.data(0, 1);
            assertFalse(f.menu.getSlot(0).mayPlace(server.cursor));
            for (var click : clicks.subList(1, clicks.size())) {
                if (f.menu.getSlot(click.slotNum()).mayPlace(server.cursor)) server.click(click.slotNum(), click.buttonNum());
            }
            int sent = f.connection.packets.size();
            f.reply(server);
            InventoryRecoveryFence.received(f.connection.getConnection());
            f.controller.tick();
            assertEquals("PAUSED", field(f.controller, "phase").toString());
            assertEquals(sent, f.connection.packets.size(), "Never retry the rejected insertion");
            assertTrue(server.items.get(0).isEmpty());
            assertEquals(64, f.player.inventory.getItem(9).getCount());
            assertTrue(server.cursor.isEmpty());
            assertTrue(f.menu.isSlotDisabled(0));
            assertTrue(f.otherInventoryOwner());
        }
    }
}
//#endif
