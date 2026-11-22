//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class OrderedStorageMenuReuseTest {
    private enum Source { SAME_BOX, DIFFERENT_SLOT, REPLACEMENT_IN_SAME_SLOT }
    private enum Replacement { REMOVED, DIFFERENT_COLOR, DIFFERENT_CONTENTS }

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void idleReturnsKeepTheSameMenuAndUseBothOriginalSlots(boolean saveBoxOnClose) throws Exception {
        try (var f = new Fixture()) {
            f.borrowAndOpen(Source.SAME_BOX);
            f.reply(f.opened, f.opened.getStateId(), f.snapshot(), ItemStack.EMPTY);
            f.adapter.tick();
            assertEquals(2, f.connection.packets.size(), "A stale snapshot cannot start the next return");
            assertFalse(f.player.closed);
            var firstReply = f.swapResult(4, 2);
            if (saveBoxOnClose) {
                int carriedBox = f.opened.findSlot(f.player.inventory, 10).orElseThrow();
                firstReply.set(carriedBox, f.player.inventory.getItem(10).copy());
            }
            f.reply(f.opened, f.opened.getStateId() + 1, firstReply, ItemStack.EMPTY);
            assertSame(f.opened, f.player.containerMenu);
            f.assertExclusion();
            f.confirmSwap(18, 3);
            assertTrue(f.player.closed);
            assertEquals(3, f.connection.packets.size(), "One open and one swap for each stack");
            assertTrue(f.tracked().isEmpty());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(32, f.opened.getSlot(4).getItem().getCount());
            assertEquals(16, f.opened.getSlot(18).getItem().getCount());
        }
    }

    @ParameterizedTest @ValueSource(ints = {10, 200})
    void pressureTriggeredReturnsYieldEvenAfterTheIdleDelay(int tick) throws Exception {
        try (var f = new Fixture()) {
            f.borrowAndOpen(Source.SAME_BOX, true, tick);
            assertTrue(f.session().isPressureRecoveryActive());
            f.confirmSwap(4, 2);
            assertTrue(f.player.closed);
            assertEquals(2, f.connection.packets.size());
            assertEquals(1, f.tracked().size());
            assertEquals(16, f.player.inventory.getItem(3).getCount());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            f.level.tick++;
            f.adapter.tick();
            assertEquals(2, f.connection.packets.size(), "Printing gets its next work tick");
        }
    }

    @ParameterizedTest @EnumSource(Replacement.class)
    void changingTheCarriedBoxInTheFirstSwapReplyStopsTheNextReturn(Replacement replacement) throws Exception {
        try (var f = new Fixture()) {
            f.borrowAndOpen(Source.SAME_BOX);
            var reply = f.swapResult(4, 2);
            int carriedBox = f.opened.findSlot(f.player.inventory, 10).orElseThrow();
            reply.set(carriedBox, switch (replacement) {
                case REMOVED -> ItemStack.EMPTY;
                case DIFFERENT_COLOR -> new ItemStack(Items.RED_SHULKER_BOX);
                case DIFFERENT_CONTENTS -> QuickShulkerQueryFixture.box(new ItemStack(Items.DIAMOND));
            });
            f.reply(f.opened, f.opened.getStateId() + 1, reply, ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertEquals(2, f.connection.packets.size(), "A changed carried box cannot receive a follow-up swap");
            assertEquals(1, f.tracked().size());
            assertEquals(16, f.player.inventory.getItem(3).getCount());
            assertTrue(f.opened.getSlot(18).getItem().isEmpty());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void aNewPrinterRequestYieldsTheMenuAfterTheCurrentSwap() throws Exception {
        try (var f = new Fixture()) {
            f.borrowAndOpen(Source.SAME_BOX);
            var requests = f.runtime.materialRequests();
            var reservation = requests.request(Items.DIAMOND, MaterialRequest.Source.PRINT);
            assertEquals(MaterialReservation.State.PENDING, reservation.state());
            assertTrue(requests.isBusy());
            f.assertExclusion();
            f.confirmSwap(4, 2);
            assertTrue(f.player.closed);
            assertEquals(2, f.connection.packets.size());
            assertEquals(1, f.tracked().size());
            assertEquals(16, f.player.inventory.getItem(3).getCount());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void identicalLookingBoxesInDifferentInventorySlotsKeepSeparateReturns() throws Exception {
        try (var f = new Fixture()) {
            f.borrowAndOpen(Source.DIFFERENT_SLOT);
            f.confirmSwap(4, 2);
            assertTrue(f.player.closed);
            assertEquals(2, f.connection.packets.size());
            assertEquals(1, f.tracked().size());
            assertTrue(f.opened.getSlot(18).getItem().isEmpty());
            assertEquals(16, f.player.inventory.getItem(3).getCount());

            f.level.tick += 2;
            f.adapter.tick();
            assertEquals(3, f.connection.packets.size());
            assertEquals(11, f.click(2).slotNum(), "Open the second physical box");
        }
    }

    @Test void replacingAnEmptyBoxAtTheSameSlotDoesNotLinkEarlierBorrowedMaterial() throws Exception {
        try (var f = new Fixture()) {
            f.borrowAndOpen(Source.REPLACEMENT_IN_SAME_SLOT);
            f.confirmSwap(4, 2);
            assertTrue(f.player.closed);
            assertEquals(2, f.connection.packets.size());
            assertEquals(1, f.tracked().size());
            assertTrue(f.opened.getSlot(18).getItem().isEmpty());
            assertEquals(16, f.player.inventory.getItem(3).getCount());
        }
    }

    @Test void disablingOrderlyWithTheFirstSwapInFlightNeverStartsTheSecond() throws Exception {
        try (var f = new Fixture()) {
            f.borrowAndOpen(Source.SAME_BOX);
            Configs.Placement.STORE_ORDERLY.setBooleanValue(false);
            f.adapter.tick();
            f.assertExclusion();
            assertFalse(f.player.closed);
            f.confirmSwap(4, 2);
            assertTrue(f.player.closed);
            assertEquals(2, f.connection.packets.size());
            assertEquals(16, f.player.inventory.getItem(3).getCount());
            assertTrue(f.opened.getSlot(18).getItem().isEmpty());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    static class Fixture extends BedrockOrderedStorageBoundaryTest.Fixture {
        ShulkerBoxMenu opened;

        Fixture() throws Exception {
            CancelledSwapLifecycleTest.set(runtime, PrinterRuntime.class, "materialRequests",
                    new MaterialRequestCoordinator(List.of(adapter)));
        }

        void borrowAndOpen(Source source) throws Exception {
            borrowAndOpen(source, false, 200);
        }

        void borrowAndOpen(Source source, boolean pressure, int tick) throws Exception {
            storage.reset();
            player.containerMenu = player.inventoryMenu;
            player.inventory.setItem(9, ItemStack.EMPTY);
            var pistons = new ItemStack(Items.PISTON, 32);
            var torches = new ItemStack(Items.REDSTONE_TORCH, 16);
            var beforeFirst = new ArrayList<>(Collections.nCopies(27, ItemStack.EMPTY));
            beforeFirst.set(4, pistons);
            if (source == Source.SAME_BOX) beforeFirst.set(18, torches);
            borrow(beforeFirst, 4, 10, 2);
            var beforeSecond = new ArrayList<>(Collections.nCopies(27, ItemStack.EMPTY));
            beforeSecond.set(18, torches);
            borrow(beforeSecond, 18, source == Source.DIFFERENT_SLOT ? 11 : 10, 3);
            if (pressure) {
                // One empty slot permits urgent recovery before the recent-use and idle delays expire.
                for (int slot = 0; slot < 36; slot++) {
                    if (slot != 13 && player.inventory.getItem(slot).isEmpty()) {
                        player.inventory.setItem(slot, new ItemStack(Items.STONE, 64));
                    }
                }
            }
            level.tick = tick;
            adapter.tick();
            assertEquals(1, connection.packets.size());
            assertEquals(10, click(0).slotNum());
            acceptReturnMenu();
            assertEquals(2, connection.packets.size());
        }

        void acceptReturnMenu() {
            opened = new ShulkerBoxMenu(7, player.inventory);
            player.containerMenu = opened;
            adapter.onContainerOpen(7, MenuType.SHULKER_BOX);
            reply(opened, 1, snapshot(), ItemStack.EMPTY);
        }

        void borrow(List<ItemStack> before, int originalSlot, int boxSlot, int hotbarSlot) throws Exception {
            var box = QuickShulkerQueryFixture.box(before.toArray(ItemStack[]::new));
            player.inventory.setItem(boxSlot, box.copy());
            var menu = new ShulkerBoxMenu(5 + hotbarSlot, player.inventory);
            player.containerMenu = menu;
            var session = (QuickShulkerContainerSession) CancelledSwapLifecycleTest.get(
                    storage, OrderedStorageController.class, "containerSession");
            session.begin(box, true);
            session.opened(menu.containerId, MenuType.SHULKER_BOX);
            for (int i = 0; i < before.size(); i++) menu.getSlot(i).set(before.get(i).copy());
            assertTrue(session.confirm(menu.containerId,
                    menu.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY));
            session.bindOwnedMenu(client);
            menu.clicked(originalSlot, hotbarSlot, ContainerInput.SWAP, player);
            player.inventory.getItem(boxSlot).set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                    menu.slots.subList(0, 27).stream().map(slot -> slot.getItem().copy()).toList()));
            storage.newItem(before.get(originalSlot), box, originalSlot, boxSlot, hotbarSlot);
            session.reset();
            player.containerMenu = player.inventoryMenu;
        }

        ServerboundContainerClickPacket click(int index) {
            return assertInstanceOf(ServerboundContainerClickPacket.class, connection.packets.get(index));
        }

        List<ItemStack> snapshot() {
            return opened.slots.stream().map(slot -> slot.getItem().copy()).toList();
        }

        void confirmSwap(int originalSlot, int hotbarSlot) {
            reply(opened, opened.getStateId() + 1, swapResult(originalSlot, hotbarSlot), ItemStack.EMPTY);
        }

        List<ItemStack> swapResult(int originalSlot, int hotbarSlot) {
            var packet = click(connection.packets.size() - 1);
            assertEquals(opened.containerId, packet.containerId());
            assertEquals(ContainerInput.SWAP, packet.containerInput());
            assertEquals(originalSlot, packet.slotNum());
            assertEquals(hotbarSlot, packet.buttonNum());
            var items = new ArrayList<>(snapshot());
            int source = opened.findSlot(player.inventory, hotbarSlot).orElseThrow();
            Collections.swap(items, originalSlot, source);
            var box = items.get(opened.findSlot(player.inventory, 10).orElseThrow());
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items.subList(0, 27)));
            return items;
        }
    }
}
//#endif
