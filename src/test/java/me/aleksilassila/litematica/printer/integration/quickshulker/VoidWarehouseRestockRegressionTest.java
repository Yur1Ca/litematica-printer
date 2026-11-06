//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import java.util.EnumSet;
import java.io.IOException;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseRestockAdapter;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseRestockNetwork;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseRestockPort;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import neko.voidwarehouse.restock.PrinterTakeResultPayload;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.Difficulty;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import net.minecraft.client.player.LocalPlayer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static org.junit.jupiter.api.Assertions.*;

class VoidWarehouseRestockRegressionTest {
    boolean oldEnabled;
    @BeforeAll static void bootstrap() throws Exception { CancelledSwapLifecycleTest.bootstrap(); }
    @BeforeEach void enable() {
        oldEnabled = Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue();
        Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(true);
    }
    @AfterEach void restore() { Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(oldEnabled); }

    static class Network implements VoidWarehouseRestockPort {
        int sends;
        int syncs;
        int status = PrinterTakeResultPayload.NONE;
        @Override public boolean isSupported() { return true; }
        @Override public int requestTake(List<String> candidates) { status = PrinterTakeResultPayload.NONE; sends++; return sends; }
        @Override public int statusOf(int nonce) { return status; }
        @Override public void forget() { status = PrinterTakeResultPayload.NONE; }
        public void requestInventorySync() { syncs++; }
    }

    static class Fixture extends TakeItOutRequestOwnershipTest.Fixture {
        final Class<?> warehouseType = new RestockTestLoader().loadClass(VoidWarehouseRestockAdapter.class.getName());
        final Network network = new Network();
        final InventoryProvider warehouse = (InventoryProvider) warehouseType
                .getConstructor(ActionPort.class, VoidWarehouseRestockPort.class).newInstance(broker, network);
        final MaterialRequestCoordinator materials = new MaterialRequestCoordinator(List.of(warehouse, next));
        final ClientLevel.ClientLevelData clock = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
        Fixture() throws Exception {
            set(client.level, Level.class, "levelData", clock);
            set(client.level, ClientLevel.class, "clientLevelData", clock);
        }
        InventoryProvider defaultWarehouse() throws Exception {
            return (InventoryProvider) warehouseType.getConstructor(ActionPort.class).newInstance(broker);
        }
        void advance(int ticks) { for (int i = 0; i < ticks; i++) materials.tick(); }
        void advanceGameTicks(int ticks) {
            for (int i = 0; i < ticks; i++) {
                clock.setGameTime(clock.getGameTime() + 1L);
                materials.tick();
            }
        }
        void assertHeld() {
            for (var resource : List.of(ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND)) {
                assertTrue(broker.isResourceHeld(resource));
                assertFalse(broker.isResourceHeldByOther(resource, "void_warehouse"));
            }
        }
    }

    @Test void successfulBedRestockRecoversTheMissingSnapshotOnceAndStillWaitsForRealInventory() throws Exception {
        try (var f = new Fixture()) {
            assertEquals(MaterialReservation.State.PENDING,
                    f.materials.request(Items.WHITE_BED, MaterialRequest.Source.PRINT).state());
            f.advanceGameTicks(20);
            assertEquals(0, f.network.syncs, "No receipt does not authorize inventory recovery");
            f.network.status = PrinterTakeResultPayload.OK;
            f.materials.tick();
            f.advanceGameTicks(4);
            assertEquals(0, f.network.syncs, "Allow the normal inventory update to arrive first");
            f.advanceGameTicks(1);
            assertEquals(1, f.network.syncs);
            f.assertHeld();
            assertTrue(f.materials.isBusy(), "The resync request is not a real bed stack");
            assertFalse(InventoryUtils.playerHasItemInInventory(f.player, Items.WHITE_BED));
            f.advanceGameTicks(20);
            assertEquals(1, f.network.syncs, "One take nonce must not flood snapshot requests");
            assertEquals(1, f.network.sends, "Snapshot recovery must not withdraw a second bed");
            f.player.inventory.setItem(10, new ItemStack(Items.WHITE_BED));
            f.advanceGameTicks(1);
            assertFalse(f.materials.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(0, f.next.calls);
            assertEquals(1, f.network.sends);
        }
    }

    @Test void inventoryArrivingWithTheReceiptNeedsNoRecoveryRequest() throws Exception {
        try (var f = new Fixture()) {
            f.materials.request(Items.WHITE_BED, MaterialRequest.Source.PRINT);
            f.network.status = PrinterTakeResultPayload.OK;
            f.player.inventory.setItem(10, new ItemStack(Items.WHITE_BED));
            f.advanceGameTicks(20);
            assertFalse(f.materials.isBusy());
            assertEquals(0, f.network.syncs);
            assertEquals(1, f.network.sends);
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 1, 2, 3, 4, 5})
    void missingOrNegativeReceiptsNeverRequestInventoryRecovery(int status) throws Exception {
        try (var f = new Fixture()) {
            f.materials.request(Items.WHITE_BED, MaterialRequest.Source.PRINT);
            f.network.status = status;
            f.advanceGameTicks(20);
            assertEquals(0, f.network.syncs);
            assertEquals(1, f.network.sends);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"menu", "cursor", "owner"})
    void recoveryWaitsForThePlayerInventoryCursorAndResourceOwner(String blocker) throws Exception {
        try (var f = new Fixture()) {
            f.materials.request(Items.WHITE_BED, MaterialRequest.Source.PRINT);
            f.network.status = PrinterTakeResultPayload.OK;
            f.materials.tick();
            if (blocker.equals("menu")) f.player.containerMenu = f.menu;
            if (blocker.equals("cursor")) f.player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            if (blocker.equals("owner")) {
                f.broker.releaseOwner("void_warehouse");
                assertTrue(f.broker.tryAcquire("container_sync", EnumSet.of(ResourceLease.INVENTORY,
                        ResourceLease.CONTAINER, ResourceLease.MAIN_HAND), 0L));
            }
            f.advanceGameTicks(10);
            assertEquals(0, f.network.syncs);
            assertEquals(1, f.network.sends);
            assertTrue(f.materials.isBusy());
            if (blocker.equals("cursor")) assertEquals(Items.DIAMOND, f.player.inventoryMenu.getCarried().getItem());
            f.player.containerMenu = f.player.inventoryMenu;
            f.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            f.broker.releaseOwner("container_sync");
            f.advanceGameTicks(1);
            assertEquals(1, f.network.syncs, "Recovery may start once the original inventory is safe again");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"connection", "level"})
    void changedSessionsNeverRecoverTheOldRequest(String changed) throws Exception {
        try (var f = new Fixture()) {
            f.materials.request(Items.WHITE_BED, MaterialRequest.Source.PRINT);
            f.network.status = PrinterTakeResultPayload.OK;
            f.materials.tick();
            if (changed.equals("connection")) {
                set(f.player, LocalPlayer.class, "connection",
                        QuickShulkerPacketContractTest.allocate(CancelledSwapLifecycleTest.Connection.class));
            } else {
                f.client.level = QuickShulkerPacketContractTest.allocate(ClientLevel.class);
            }
            f.advanceGameTicks(10);
            assertEquals(0, f.network.syncs);
            assertEquals(1, f.network.sends);
        }
    }

    @Test void resetCancelsRecoveryAndANewTakeNonceGetsItsOwnSingleAttempt() throws Exception {
        try (var f = new Fixture()) {
            f.materials.request(Items.WHITE_BED, MaterialRequest.Source.PRINT);
            f.network.status = PrinterTakeResultPayload.OK;
            f.materials.tick();
            f.advanceGameTicks(5);
            assertEquals(1, f.network.syncs);
            f.materials.reset();
            f.network.status = PrinterTakeResultPayload.OK; // A late old receipt is not active work.
            f.advanceGameTicks(10);
            assertEquals(1, f.network.syncs);
            f.materials.request(Items.WHITE_BED, MaterialRequest.Source.PRINT);
            assertEquals(2, f.network.sends);
            f.network.status = PrinterTakeResultPayload.OK;
            f.materials.tick();
            f.advanceGameTicks(4);
            assertEquals(1, f.network.syncs);
            f.advanceGameTicks(1);
            assertEquals(2, f.network.syncs);
        }
    }

    @Test void consumingAndRestockingOneBedBeforeBroadcastCanHideItsSlotDelta() throws Exception {
        try (var f = new Fixture()) {
            class Synchronizer implements ContainerSynchronizer {
                int fullSnapshots;
                int slotChanges;
                List<ItemStack> lastSnapshot;
                @Override public void sendInitialData(AbstractContainerMenu menu, List<ItemStack> items, ItemStack carried, int[] data) {
                    fullSnapshots++;
                    lastSnapshot = items;
                }
                @Override public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) { slotChanges++; }
                @Override public void sendCarriedChange(AbstractContainerMenu menu, ItemStack stack) { }
                @Override public void sendDataChange(AbstractContainerMenu menu, int id, int value) { }
                @Override public RemoteSlot createSlot() { return new RemoteSlot.Synchronized(null); }
            }
            var sync = new Synchronizer();
            f.player.inventory.setItem(0, new ItemStack(Items.WHITE_BED));
            f.player.inventoryMenu.setSynchronizer(sync);
            assertEquals(1, sync.fullSnapshots);
            f.player.inventory.setItem(0, ItemStack.EMPTY);
            f.player.inventory.setItem(0, new ItemStack(Items.WHITE_BED));
            f.player.inventoryMenu.broadcastChanges();
            assertEquals(0, sync.slotChanges, "The old and final one-bed snapshots are equal despite the client having predicted consumption");
            f.player.inventoryMenu.broadcastFullState();
            assertEquals(2, sync.fullSnapshots);
            assertEquals(1, sync.lastSnapshot.stream().filter(stack -> stack.is(Items.WHITE_BED)).mapToInt(ItemStack::getCount).sum());
            var before = sync.lastSnapshot.stream().map(ItemStack::copy).toList();
            f.player.inventoryMenu.clicked(-1, 0, ContainerInput.PICKUP, f.player);
            for (int slot = 0; slot < before.size(); slot++) {
                assertTrue(ItemStack.matches(before.get(slot), f.player.inventoryMenu.getSlot(slot).getItem()));
            }
            assertTrue(f.player.inventoryMenu.getCarried().isEmpty(), "The recovery click never picks up or drops a stack");
        }
    }

    /** Isolate cached clients without changing the production classes' bytecode or static final fields. */
    private static class RestockTestLoader extends ClassLoader {
        RestockTestLoader() { super(VoidWarehouseRestockRegressionTest.class.getClassLoader()); }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean isolated = List.of(VoidWarehouseRestockAdapter.class, InventoryUtils.class).stream()
                    .map(Class::getName).anyMatch(type -> name.equals(type) || name.startsWith(type + "$"));
            if (!isolated) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                var result = findLoadedClass(name);
                if (result == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        byte[] bytes = input.readAllBytes();
                        result = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) { throw new ClassNotFoundException(name, e); }
                }
                if (resolve) resolveClass(result);
                return result;
            }
        }
    }

    @Test void anEmptyBoxRequestNeverCompletesWithAStoredFullBox() throws Exception {
        try (var f = new Fixture()) {
            var adapter = f.defaultWarehouse();
            var request = new MaterialRequest(1, List.of(Items.SHULKER_BOX), Items.SHULKER_BOX,
                    1, MaterialRequest.Source.PRINT, true);
            var full = new ItemStack(Items.SHULKER_BOX);
            full.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 64))));
            f.player.inventory.setItem(10, full);
            // A request already sent; no Fabric networking or game launch is needed to poll it.
            set(adapter, f.warehouseType, "activeRequest", request);
            set(adapter, f.warehouseType, "activeNonce", 1);
            VoidWarehouseRestockNetwork.forget();
            assertEquals(MaterialReservation.State.PENDING, adapter.status(request).state());
            f.player.inventory.setItem(11, new ItemStack(Items.SHULKER_BOX));
            assertEquals(MaterialReservation.State.AVAILABLE, adapter.status(request).state());
            assertSame(full, f.player.inventory.getItem(10));
        }
    }

    @Test void completedRestockWaitsForTheOtherInventoryOwnerBeforeSelecting() throws Exception {
        try (var f = new Fixture()) {
            var adapter = f.defaultWarehouse();
            var request = new MaterialRequest(2, List.of(Items.STONE), Items.STONE, 1,
                    MaterialRequest.Source.PICK_BLOCK);
            set(adapter, f.warehouseType, "activeRequest", request);
            set(adapter, f.warehouseType, "activeNonce", 2);
            f.player.inventory.setItem(3, new ItemStack(Items.STONE));
            f.player.inventory.setSelectedSlot(0);
            assertTrue(f.broker.tryAcquire("container_sync", EnumSet.of(ResourceLease.INVENTORY,
                    ResourceLease.CONTAINER, ResourceLease.MAIN_HAND), 0L));
            assertEquals(MaterialReservation.State.PENDING, adapter.status(request).state());
            assertEquals(0, f.player.inventory.getSelectedSlot());
            assertTrue(f.broker.isResourceHeldByOther(ResourceLease.INVENTORY, "void_warehouse"));
            f.broker.releaseOwner("container_sync");
            assertEquals(MaterialReservation.State.AVAILABLE, adapter.status(request).state());
            assertEquals(3, f.player.inventory.getSelectedSlot());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void waitingForInventoryOwnershipDoesNotSendOrConsumeTheResponseTimeout() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.broker.tryAcquire("container_sync", EnumSet.of(ResourceLease.INVENTORY,
                    ResourceLease.CONTAINER, ResourceLease.MAIN_HAND), 0L));
            assertEquals(MaterialReservation.State.PENDING,
                    f.materials.request(Items.STONE, MaterialRequest.Source.PRINT).state());
            f.advance(300);
            assertTrue(f.materials.isBusy());
            assertEquals(0, f.network.sends);
            assertEquals(0, f.next.calls);
            f.broker.releaseOwner("container_sync");
            f.advance(1);
            assertEquals(1, f.network.sends);
            f.assertHeld();
            assertTrue(f.materials.blocksPrinterWhilePending());
            f.network.status = PrinterTakeResultPayload.OK;
            f.advance(90);
            f.assertHeld();
            assertTrue(f.materials.isBusy(), "An OK receipt is not an inventory update");
            f.player.inventory.setItem(10, new ItemStack(Items.STONE, 64));
            f.advance(1);
            assertFalse(f.materials.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(1, f.network.sends);
            assertEquals(0, f.next.calls);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"work_off", "restock_off", "connection", "level", "timeout"})
    void cancellationNeverSelectsALateDeliveryOrReplaysTheRequest(String reason) throws Exception {
        try (var f = new Fixture()) {
            f.materials.request(Items.STONE, MaterialRequest.Source.PICK_BLOCK);
            f.assertHeld();
            if (reason.equals("restock_off")) {
                Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(false);
                f.advance(1);
            } else if (reason.equals("timeout")) {
                f.advance(101);
            } else {
                if (reason.equals("work_off")) Configs.Core.WORK_SWITCH.setBooleanValue(false);
                if (reason.equals("connection")) {
                    set(f.player, LocalPlayer.class, "connection",
                            QuickShulkerPacketContractTest.allocate(CancelledSwapLifecycleTest.Connection.class));
                } else if (reason.equals("level")) {
                    f.client.level = QuickShulkerPacketContractTest.allocate(ClientLevel.class);
                }
                f.materials.onEpochChanged(new RuntimeEvent.EpochChanged(RuntimeEpoch.INITIAL,
                        RuntimeEpoch.INITIAL.next(), reason));
            }
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertFalse(f.materials.isBusy());
            assertTrue(f.broker.tryAcquire("container_sync", EnumSet.of(ResourceLease.INVENTORY), 0L));
            f.player.inventory.setItem(3, new ItemStack(Items.STONE, 64));
            f.network.status = PrinterTakeResultPayload.OK;
            f.advance(300);
            assertEquals(0, f.player.inventory.getSelectedSlot());
            assertEquals(1, f.network.sends);
            assertTrue(f.broker.isResourceHeldByOther(ResourceLease.INVENTORY, "void_warehouse"));
        }
    }

    @Test void aNewWorldWithAnEarlierClockDoesNotInheritTheFailedItemCooldown() throws Exception {
        try (var f = new Fixture()) {
            f.clock.setGameTime(1_000_000L);
            f.materials.request(Items.STONE, MaterialRequest.Source.PRINT);
            f.network.status = PrinterTakeResultPayload.RULE_OFF;
            f.advance(1);
            assertFalse(f.materials.isBusy());
            f.materials.request(Items.STONE, MaterialRequest.Source.PRINT);
            assertEquals(1, f.network.sends, "Same-session failures still need their cooldown");
            f.materials.reset();
            f.clock.setGameTime(10L);
            assertEquals(MaterialReservation.State.PENDING,
                    f.materials.request(Items.STONE, MaterialRequest.Source.PRINT).state());
            assertEquals(2, f.network.sends);
        }
    }
}
//#endif
