//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.ArrayList;
import java.util.Collections;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEventBus;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeScope;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

class OrderedStorageWorkOffTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void turningWorkOffStillOpensTheOriginalBoxAndReturnsTheConfirmedStack() throws Exception {
        try (var f = new Fixture()) {
            f.stopWork();
            f.awaitOpen();
            assertEquals(10, f.click(0).slotNum());
            assertFalse(f.adapter.isOpenHandler());
            f.acceptReturnMenu();
            assertEquals(2, f.connection.packets.size());
            f.assertExclusion();
            f.confirmSwap(4, 2);
            assertTrue(f.player.closed);
            assertTrue(f.tracked().isEmpty());
            assertEquals(32, f.opened.getSlot(4).getItem().getCount());
            assertTrue(f.player.inventory.getItem(2).isEmpty());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            f.tick(120);
            assertEquals(2, f.connection.packets.size());
        }
    }

    @Test void aMissingOriginalBoxCannotOpenAnUnrelatedBoxOfTheSameColorAfterStop() throws Exception {
        try (var f = new Fixture()) {
            f.stopWork();
            f.player.inventory.setItem(10, ItemStack.EMPTY);
            f.player.inventory.setItem(11, QuickShulkerQueryFixture.box(new ItemStack(Items.DIAMOND)));
            f.tick(120);
            assertTrue(f.connection.packets.isEmpty());
            assertEquals(32, f.player.inventory.getItem(2).getCount());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void theOpenReplyCannotAuthorizeABoxThatWasRemovedOrReplaced(boolean removed) throws Exception {
        try (var f = new Fixture()) {
            f.stopWork();
            f.awaitOpen();
            f.player.inventory.setItem(10, removed ? ItemStack.EMPTY
                    : QuickShulkerQueryFixture.box(new ItemStack(Items.DIAMOND)));
            // Its 27 inner slots still match the previously requested empty box.
            // The same authoritative opening snapshot also identifies the changed player slot.
            f.acceptReturnMenu();
            assertEquals(1, f.connection.packets.size(), "Never start the return after the source box changed");
            f.player.containerMenu = f.player.inventoryMenu;
            if (ShulkerUtils.isRecovering()) f.finishOpenRecovery();
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            int sent = f.connection.packets.size();
            f.tick(120);
            assertEquals(sent, f.connection.packets.size());
            assertEquals(32, f.player.inventory.getItem(2).getCount());
            assertTrue(f.opened.getSlot(4).getItem().isEmpty());
        }
    }

    @Test void losingTheOnlyBorrowedStackWhileOpeningStillTimesOutAndReleasesTheLease() throws Exception {
        try (var f = new Fixture()) {
            f.stopWork();
            f.awaitOpen();
            f.player.inventory.setItem(2, ItemStack.EMPTY);
            f.tick(50);
            assertFalse(f.storage.isWaitingForRestoreContainer(), "An unanswered open must still age out");
            assertTrue(ShulkerUtils.isRecovering());
            assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            f.finishOpenRecovery();
            f.tick(120);
            assertFalse(f.storage.hasPendingRestore());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(f.tracked().isEmpty());
            assertEquals(3, f.connection.packets.size(), "Only the original open, inventory snapshot query and marker");
            assertFalse(f.player.closed);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aVacatedUnrelatedLoanSlotCannotClaimNewStockWhileAWithdrawalWaits(boolean clearingHotbar) throws Exception {
        try (var f = new Fixture()) {
            // Pistons came from box 10 into hotbar 2. The outstanding click touches hotbar 3 in box 11.
            var withdrawal = f.startWithdrawal(11, clearingHotbar);
            f.stopWork();
            f.player.inventory.setItem(2, ItemStack.EMPTY);
            f.tick(2);
            f.player.inventory.setItem(2, new ItemStack(Items.PISTON, 16));
            f.tick(2);
            assertEquals(2, f.connection.packets.size(), "Wait for the original click and its resync");
            // Reject the unrelated withdrawal. The replacement pistons are now the only material in question.
            f.reply(withdrawal, 1, withdrawal.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            f.tick(120);
            assertEquals(2, f.connection.packets.size(), "Never reopen box 10 for the player's replacement pistons");
            assertTrue(f.tracked().isEmpty());
            assertEquals(16, f.player.inventory.getItem(2).getCount());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void anInFlightPrintWithdrawalDrainsBeforeAnOlderLoanIsReturned() throws Exception {
        try (var f = new Fixture()) {
            var withdrawal = f.startWithdrawal(11, true);
            int empty = withdrawal.findSlot(f.player.inventory, 9).orElseThrow();

            f.stopWork();
            f.tick(10);
            assertFalse(f.player.closed);
            assertEquals(2, f.connection.packets.size());
            assertEquals(-1, f.click(1).slotNum(), "Only resynchronize the outstanding withdrawal");
            withdrawal.clicked(empty, 3, ContainerInput.SWAP, f.player);
            f.reply(withdrawal, 1, withdrawal.slots.stream().map(s -> s.getItem().copy()).toList(), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertEquals(64, withdrawal.getSlot(7).getItem().getCount(), "Do not take diamonds after clearing the hotbar");
            f.awaitOpen(2);
            assertEquals(10, f.click(2).slotNum(), "Resume only the older piston return");
            f.acceptReturnMenu();
            f.confirmSwap(4, 2);
            assertTrue(f.tracked().isEmpty());
            assertEquals(4, f.connection.packets.size());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void onlyAnAcknowledgedFinalWithdrawalIsReturnedAfterStopping(boolean accepted) throws Exception {
        try (var f = new Fixture()) {
            f.storage.reset();
            f.player.inventory.setItem(2, ItemStack.EMPTY);
            var withdrawal = f.startWithdrawal(10, false);
            assertTrue(f.tracked().isEmpty(), "The only material transfer is still in flight");
            f.stopWork();
            f.tick(10);
            assertEquals(2, f.connection.packets.size());
            assertEquals(-1, f.click(1).slotNum());
            assertFalse(f.player.closed);
            if (accepted) {
                withdrawal.clicked(7, 3, ContainerInput.SWAP, f.player);
                f.player.inventory.getItem(10).set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            }
            f.reply(withdrawal, 1, withdrawal.slots.stream().map(s -> s.getItem().copy()).toList(), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertTrue(f.player.inventoryMenu.getCarried().isEmpty());
            if (accepted) {
                assertEquals(64, f.player.inventory.getItem(3).getCount());
                f.awaitOpen(2);
                assertEquals(10, f.click(2).slotNum());
                f.acceptReturnMenu();
                f.confirmSwap(7, 3);
                assertEquals(64, f.opened.getSlot(7).getItem().getCount());
                assertTrue(f.player.inventory.getItem(3).isEmpty());
            } else {
                assertEquals(64, withdrawal.getSlot(7).getItem().getCount());
                assertTrue(f.player.inventory.getItem(3).isEmpty());
            }
            f.tick(120);
            assertEquals(accepted ? 4 : 2, f.connection.packets.size());
            assertTrue(f.tracked().isEmpty());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anAuthorizedReturnFinishesWhenWorkStopsDuringOpenOrSwap(boolean swapAlreadySent) throws Exception {
        try (var f = new Fixture()) {
            f.level.tick = 200;
            f.adapter.tick();
            assertEquals(1, f.connection.packets.size());
            if (swapAlreadySent) f.acceptReturnMenu();
            f.stopWork();
            if (!swapAlreadySent) f.acceptReturnMenu();
            assertEquals(2, f.connection.packets.size());
            f.assertExclusion();
            f.confirmSwap(4, 2);
            assertTrue(f.player.closed);
            assertTrue(f.player.inventory.getItem(2).isEmpty());
            assertEquals(32, f.opened.getSlot(4).getItem().getCount());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cursor", "dead"})
    void aCarriedStackOrDeadPlayerPreventsANewReturn(String blocked) throws Exception {
        try (var f = new Fixture()) {
            if (blocked.equals("cursor")) f.player.inventoryMenu.setCarried(new ItemStack(Items.STICK, 2));
            else f.player.dead = true;
            f.stopWork();
            f.tick(160);
            assertTrue(f.connection.packets.isEmpty());
            assertFalse(f.player.closed);
            if (blocked.equals("cursor")) assertEquals(2, f.player.inventoryMenu.getCarried().getCount());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void aManualInventoryScreenOrBoxIsLeftAloneUntilThePlayerClosesIt(boolean inventoryScreen) throws Exception {
        try (var f = new Fixture()) {
            var manual = inventoryScreen ? f.player.inventoryMenu : new ShulkerBoxMenu(71, f.player.inventory);
            if (inventoryScreen) f.client.screen = allocate(InventoryScreen.class);
            f.player.containerMenu = manual;
            f.stopWork();
            if (!inventoryScreen) f.adapter.onContainerOpen(71, MenuType.SHULKER_BOX);
            f.reply(manual, 1, manual.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY);
            f.tick(160);
            assertSame(manual, f.player.containerMenu);
            assertFalse(f.player.closed);
            assertTrue(f.connection.packets.isEmpty());
            assertFalse(f.adapter.shouldSuppressContainerScreen(71));
            f.client.screen = null;
            f.player.containerMenu = f.player.inventoryMenu;
            f.awaitOpen();
            assertEquals(10, f.click(0).slotNum());
        }
    }

    @Test void aDifferentBoxOpenedWhileAwaitingTheReturnIsNeverClickedOrClosed() throws Exception {
        try (var f = new Fixture()) {
            f.stopWork();
            f.awaitOpen();
            var foreign = new ShulkerBoxMenu(81, f.player.inventory);
            foreign.getSlot(0).set(new ItemStack(Items.DIAMOND));
            f.player.containerMenu = foreign;
            f.adapter.onContainerOpen(81, MenuType.SHULKER_BOX);
            f.reply(foreign, 1, foreign.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY);
            f.tick(50);
            assertSame(foreign, f.player.containerMenu);
            assertFalse(f.player.closed);
            assertFalse(f.adapter.shouldSuppressContainerScreen(81));
            assertEquals(1, f.connection.packets.size());
            assertEquals(32, f.player.inventory.getItem(2).getCount());
            assertTrue(foreign.getSlot(0).getItem().is(Items.DIAMOND));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"world", "connection", "disconnect"})
    void changingScopeWhileStoppedNeverUsesBorrowedItemsFromTheOldSession(String change) throws Exception {
        try (var f = new Fixture()) {
            f.stopWork();
            var current = f.connection;
            if (change.equals("world")) {
                f.client.level = allocate(BedrockOrderedStorageBoundaryTest.Level.class);
            } else if (change.equals("connection")) {
                current = allocate(CancelledSwapLifecycleTest.Connection.class);
                current.packets = new ArrayList<>();
                set(f.player, LocalPlayer.class, "connection", current);
            }
            f.runtime.reset(change.equals("disconnect") ? "disconnect" : "client_scope_changed");
            f.tick(160);
            assertTrue(current.packets.isEmpty());
            assertTrue(f.connection.packets.isEmpty());
            assertFalse(f.player.closed);
            assertEquals(32, f.player.inventory.getItem(2).getCount());
            assertTrue(f.tracked().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"orderly", "quick"})
    void switchingOffEitherShulkerOptionPreventsOpeningAReturnMenu(String disabled) throws Exception {
        try (var f = new Fixture()) {
            if (disabled.equals("orderly")) Configs.Placement.STORE_ORDERLY.setBooleanValue(false);
            else Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            f.stopWork();
            f.tick(160);
            assertTrue(f.connection.packets.isEmpty());
            assertFalse(f.player.closed);
            assertEquals(32, f.player.inventory.getItem(2).getCount());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void stoppingWorkDoesNotAllowNewPrinterWithdrawals() throws Exception {
        try (var f = new Fixture()) {
            f.player.inventory.setItem(11, QuickShulkerQueryFixture.box(new ItemStack(Items.DIAMOND, 64)));
            f.stopWork();
            f.awaitOpen();
            f.adapter.request(MaterialRequest.single(55, Items.DIAMOND, 1, MaterialRequest.Source.PRINT));
            assertFalse(f.adapter.isOpenHandler());
            assertEquals(10, f.click(0).slotNum(), "Only the box owed a return may open");
            f.acceptReturnMenu();
            f.confirmSwap(4, 2);
            f.tick(160);
            assertEquals(2, f.connection.packets.size(), "The printer request must not be replayed after returning");
        }
    }

    private static final class Fixture extends OrderedStorageMenuReuseTest.Fixture {
        Fixture() throws Exception {
            var listener = allocate(ShulkerOpenRecoveryLifecycleTest.FenceConnection.class);
            listener.packets = connection.packets;
            listener.transport = allocate(net.minecraft.network.Connection.class);
            listener.onSend = packet -> {
                InventoryRecoveryFence.inputSent(listener.transport, packet);
                InventoryRecoveryFence.sent(listener.transport, packet);
            };
            set(player, LocalPlayer.class, "connection", listener);
            var scope = new RuntimeScope();
            scope.register(broker);
            scope.register(adapter);
            scope.register(runtime.materialRequests());
            set(runtime, PrinterRuntime.class, "scope", scope);
            set(runtime, PrinterRuntime.class, "events", new RuntimeEventBus());
            storage.reset();
            player.containerMenu = player.inventoryMenu;
            player.inventory.setItem(9, ItemStack.EMPTY);
            var before = new ArrayList<>(Collections.nCopies(27, ItemStack.EMPTY));
            before.set(4, new ItemStack(Items.PISTON, 32));
            borrow(before, 4, 10, 2);
        }

        void stopWork() {
            var previous = runtime.epoch();
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            // In-game the config listener broadcasts this to both the adapter and its coordinator.
            if (runtime.epoch().equals(previous)) runtime.reset("work_switch_off");
        }

        ShulkerBoxMenu startWithdrawal(int boxSlot, boolean clearingHotbar) throws Exception {
            var requests = (QuickShulkerRequestController) get(adapter, QuickShulkerAdapter.class, "requests");
            var session = (QuickShulkerContainerSession) get(requests, QuickShulkerRequestController.class, "containerSession");
            var before = new ArrayList<>(Collections.nCopies(27, ItemStack.EMPTY));
            before.set(7, new ItemStack(Items.DIAMOND, 64));
            var box = QuickShulkerQueryFixture.box(before.toArray(ItemStack[]::new));
            player.inventory.setItem(boxSlot, box.copy());
            if (clearingHotbar) player.inventory.setItem(3, new ItemStack(Items.DIRT, 32));
            var withdrawal = new ShulkerBoxMenu(81, player.inventory);
            withdrawal.getSlot(7).set(before.get(7).copy());
            player.containerMenu = withdrawal;
            session.begin(box, true);
            session.opened(81, MenuType.SHULKER_BOX);
            assertTrue(session.confirm(81, withdrawal.slots.stream().map(s -> s.getItem().copy()).toList(), ItemStack.EMPTY));
            session.bindOwnedMenu(client);
            set(requests, QuickShulkerRequestController.class, "isOpenHandler", true);
            set(requests, QuickShulkerRequestController.class, "automatedContainerId", 81);
            set(requests, QuickShulkerRequestController.class, "clearingHotbar", clearingHotbar);
            set(requests, QuickShulkerRequestController.class, "targetHotbarSlot", 3);
            set(requests, QuickShulkerRequestController.class, "shulkerInventoryMenuSlot", boxSlot);
            set(requests, QuickShulkerRequestController.class, "extractionSlot", 7);
            set(requests, QuickShulkerRequestController.class, "sourceShulker", box);
            set(requests, QuickShulkerRequestController.class, "extractedStack", before.get(7));
            set(requests, QuickShulkerRequestController.class, "movedInventorySlot", 9);
            requests.requestItem(Items.DIAMOND);
            var send = QuickShulkerRequestController.class.getDeclaredMethod("beginSwap",
                    AbstractContainerMenu.class, int.class, int.class, int.class);
            send.setAccessible(true);
            int from = clearingHotbar ? withdrawal.findSlot(player.inventory, 9).orElseThrow() : 7;
            send.invoke(requests, withdrawal, from, withdrawal.findSlot(player.inventory, 3).orElseThrow(), 3);
            assertEquals(ContainerInput.SWAP, click(0).containerInput());
            assertEquals(from, click(0).slotNum());
            return withdrawal;
        }

        void awaitOpen() {
            awaitOpen(0);
        }

        void awaitOpen(int earlierPackets) {
            for (int elapsed = 0; elapsed < 5 && connection.packets.size() == earlierPackets; elapsed++) tick(1);
            assertEquals(earlierPackets + 1, connection.packets.size());
            assertEquals(player.inventoryMenu.containerId, click(earlierPackets).containerId());
            assertEquals(ContainerInput.PICKUP, click(earlierPackets).containerInput());
        }

        void tick(int count) {
            for (int i = 0; i < count; i++) {
                level.tick++;
                if (ShulkerUtils.isRecovering()) ShulkerUtils.tickRecovery();
                adapter.tick();
            }
        }

        void finishOpenRecovery() {
            tick(1);
            int n = connection.packets.size();
            assertEquals(-1, click(n - 2).slotNum());
            var marker = assertInstanceOf(ServerboundClientCommandPacket.class, connection.packets.getLast());
            assertEquals(ServerboundClientCommandPacket.Action.REQUEST_STATS, marker.getAction());
            var inventory = player.inventoryMenu;
            reply(inventory, inventory.getStateId() + 1,
                    inventory.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY);
            InventoryRecoveryFence.received(client.getConnection().getConnection());
            tick(1);
            assertFalse(ShulkerUtils.isRecovering());
        }
    }
}
//#endif
