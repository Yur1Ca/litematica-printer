//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.*;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.*;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.runtime.*;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedStack;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.*;

/** Exercises the real orderly-return controller with delayed vanilla container replies. */
class OrderedStorageDrainTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static class Player extends CancelledSwapLifecycleTest.Player {
        QuickShulkerPacketContractTest.AuditLevel auditLevel;
        @Override public net.minecraft.world.level.Level level() { return auditLevel; }
        @Override public net.minecraft.world.level.GameType gameMode() { return net.minecraft.world.level.GameType.SURVIVAL; }
        @Override public void updateTutorialInventoryAction(ItemStack a, ItemStack b, ClickAction action) { }
    }

    static class GameMode extends MultiPlayerGameMode {
        GameMode() { super(null, null); }
        @Override public void handleContainerInput(int id, int slot, int button, ContainerInput type,
                                                  net.minecraft.world.entity.player.Player player) {
            player.containerMenu.clicked(slot, button, type, player);
        }
    }

    static class ReturnMenu extends AbstractContainerMenu {
        ReturnMenu(net.minecraft.world.Container box, Inventory inventory, int source, int targetCapacity) {
            super(null, 7);
            addSlot(new Slot(box, 0, 0, 0) {
                @Override public int getMaxStackSize(ItemStack stack) {
                    return Math.min(targetCapacity, super.getMaxStackSize(stack));
                }
            });
            addSlot(new Slot(inventory, source, 0, 0));
        }
        @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
    }

    static class SplitMenu extends ReturnMenu {
        SplitMenu(net.minecraft.world.Container box, Inventory inventory, int source) {
            super(box, inventory, source, 64);
            addSlot(new Slot(box, 1, 0, 0));
            addSlot(new Slot(box, 2, 0, 0));
            addSlot(new Slot(inventory, 3, 0, 0));
        }
    }

    static class Fixture implements AutoCloseable {
        final Object oldClient = get(null, Minecraft.class, "instance");
        final Object oldRuntime = get(null, RuntimeAccess.class, "current");
        final boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        final boolean oldQuick = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        final boolean oldOrderly = Configs.Placement.STORE_ORDERLY.getBooleanValue();
        final Minecraft client = QuickShulkerPacketContractTest.allocate(Minecraft.class);
        final Player player = QuickShulkerPacketContractTest.allocate(Player.class);
        final Connection connection = QuickShulkerPacketContractTest.allocate(Connection.class);
        final AbstractContainerMenu menu;
        final OrderedStorageController storage;
        final PrinterRuntime runtime = QuickShulkerPacketContractTest.allocate(PrinterRuntime.class);
        final ActionBroker broker = new ActionBroker(runtime, new ActionManager());
        final QuickShulkerAdapter adapter;

        Fixture() throws Exception { this(false); }
        Fixture(boolean split) throws Exception { this(split, 9, 64); }
        Fixture(int source, int targetCapacity) throws Exception { this(false, source, targetCapacity); }
        Fixture(boolean split, int source, int targetCapacity) throws Exception {
            player.auditLevel = QuickShulkerPacketContractTest.allocate(QuickShulkerPacketContractTest.AuditLevel.class);
            player.inventory = new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
            player.inventory.setItem(source, new ItemStack(Items.PISTON, 64));
            set(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
            connection.packets = new ArrayList<>();
            set(player, net.minecraft.client.player.LocalPlayer.class, "connection", connection);
            menu = split ? new SplitMenu(new net.minecraft.world.SimpleContainer(3), player.inventory, source)
                    : new ReturnMenu(new net.minecraft.world.SimpleContainer(1), player.inventory, source, targetCapacity);
            player.containerMenu = menu;
            client.player = player;
            set(client, Minecraft.class, "gui", QuickShulkerPacketContractTest.allocate(net.minecraft.client.gui.Gui.class));
            client.gameMode = QuickShulkerPacketContractTest.allocate(GameMode.class);
            set(null, Minecraft.class, "instance", client);
            set(runtime, PrinterRuntime.class, "epoch", RuntimeEpoch.INITIAL);
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard",
                    new me.aleksilassila.litematica.printer.utils.InventorySwitchGuard(client, () -> 0L));
            adapter = new QuickShulkerAdapter(broker);
            set(runtime, PrinterRuntime.class, "quickShulkerAdapter", adapter);
            RuntimeAccess.install(runtime);
            storage = (OrderedStorageController) get(adapter, QuickShulkerAdapter.class, "orderedStorage");
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(true);
            Configs.Placement.STORE_ORDERLY.setBooleanValue(true);
            storage.newItem(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY, 0, 9, source);
            @SuppressWarnings("unchecked")
            var entries = (List<OrderedStorageEntry>) get(storage, OrderedStorageController.class, "trackedItems");
            @SuppressWarnings("unchecked")
            var session = (RestoreSession<OrderedStorageEntry>) get(storage, OrderedStorageController.class, "restoreSession");
            session.schedule(entries.getFirst());
            session.beginContainerWait(40);
            storage.onContainerOpen(7);
        }
        void reply(AbstractContainerMenu target, int revision, List<ItemStack> items, ItemStack cursor) {
            target.initializeContents(revision, items, cursor);
            QuickShulkerBridge.onInventoryContent(target.containerId, items, cursor);
        }
        void resetScope(String reason) throws Exception {
            var next = runtime.epoch().next();
            var event = new RuntimeEvent.EpochChanged(runtime.epoch(), next, reason);
            set(runtime, PrinterRuntime.class, "epoch", next);
            broker.onEpochChanged(event);
            adapter.onEpochChanged(event);
        }
        void assertExclusion() {
            for (String owner : List.of("bedrock", "print", "mine", "fill", "fluid", "take_it_out", "container_sync", "pick_block")) {
                assertFalse(broker.tryAcquire(owner, EnumSet.of(ResourceLease.INVENTORY, ResourceLease.MAIN_HAND), 0));
            }
        }
        void expireUnexpectedReply() throws Exception {
            var drain = get(storage, OrderedStorageController.class, "drain");
            var transfer = get(drain, OrderedStorageDrain.class, "transfer");
            set(transfer, me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncTransfer.class, "sentAt", 0L);
            adapter.tick();
        }
        @Override public void close() throws Exception {
            set(null, Minecraft.class, "instance", oldClient);
            set(null, RuntimeAccess.class, "current", oldRuntime);
            Configs.Core.WORK_SWITCH.setBooleanValue(oldWork);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(oldQuick);
            Configs.Placement.STORE_ORDERLY.setBooleanValue(oldOrderly);
        }
    }

    @Test void mergeOrderIsPreservedAndUnrelatedPickedUpDropsDoNotInvalidateTheReturn() throws Exception {
        try (var f = new Fixture(true)) {
            f.menu.slots.get(0).set(new ItemStack(Items.PISTON, 60));
            f.menu.slots.get(1).set(new ItemStack(Items.PISTON, 16));
            f.menu.slots.get(2).set(new ItemStack(Items.PISTON, 63));
            f.storage.restorePendingItem();
            f.adapter.tick();
            assertEquals(16, f.menu.slots.get(1).getItem().getCount(), "No predicted pickup");
            var loot = new ItemStack(Items.DIAMOND, 3);
            f.reply(f.menu, 1, List.of(new ItemStack(Items.PISTON, 60), ItemStack.EMPTY,
                    new ItemStack(Items.PISTON, 63), ItemStack.EMPTY, loot), new ItemStack(Items.PISTON, 16));
            f.adapter.tick();
            f.reply(f.menu, 2, List.of(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY,
                    new ItemStack(Items.PISTON, 63), ItemStack.EMPTY, loot), new ItemStack(Items.PISTON, 12));
            f.adapter.tick();
            f.reply(f.menu, 3, List.of(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY,
                    new ItemStack(Items.PISTON, 64), ItemStack.EMPTY, loot), new ItemStack(Items.PISTON, 11));
            f.adapter.tick();
            assertFalse(f.player.closed);
            f.reply(f.menu, 4, List.of(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY,
                    new ItemStack(Items.PISTON, 64), new ItemStack(Items.PISTON, 11), loot), ItemStack.EMPTY);
            f.adapter.tick();
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(List.of(1, 0, 2, 3), f.connection.packets.stream()
                    .map(p -> (int) ((net.minecraft.network.protocol.game.ServerboundContainerClickPacket) p).slotNum()).toList());
            assertEquals(3, f.player.inventory.getItem(3).getCount());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"enabled", "work_off", "quick_off", "orderly_off"})
    void delayedConfirmationRetainsTheLeaseAcrossFeatureSwitches(String mode) throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            if (mode.equals("work_off")) {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                f.resetScope("work_switch_off");
            } else if (mode.equals("quick_off")) {
                Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
                f.adapter.reset();
            } else if (mode.equals("orderly_off")) Configs.Placement.STORE_ORDERLY.setBooleanValue(false);
            for (int i = 0; i < 300; i++) f.adapter.tick();
            f.assertExclusion();
            assertFalse(f.player.closed);
            assertEquals(1, f.connection.packets.size(), "Wait without replaying any return clicks");
            f.reply(f.menu, f.menu.getStateId(), List.of(ItemStack.EMPTY, new ItemStack(Items.PISTON, 64)), ItemStack.EMPTY);
            assertFalse(f.player.closed, "A snapshot older than the barrier must not release ownership");
            f.reply(f.menu, 1, List.of(ItemStack.EMPTY, ItemStack.EMPTY), new ItemStack(Items.PISTON, 64));
            f.adapter.tick();
            f.assertExclusion();
            assertEquals(2, f.connection.packets.size());
            boolean enabled = mode.equals("enabled");
            var next = (net.minecraft.network.protocol.game.ServerboundContainerClickPacket) f.connection.packets.getLast();
            assertEquals(enabled ? 0 : 1, next.slotNum(), "Cancellation returns the cursor to inventory");
            f.reply(f.menu, 2, enabled ? List.of(new ItemStack(Items.PISTON, 64), ItemStack.EMPTY)
                    : List.of(ItemStack.EMPTY, new ItemStack(Items.PISTON, 64)), ItemStack.EMPTY);
            f.adapter.tick();
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(f.player.inventoryMenu.getCarried().isEmpty());
        }
    }

    @Test void correctedPickupReturnsOnlyOurCursorAndWaitsForTheReturnReply() throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            f.reply(f.menu, 1, List.of(ItemStack.EMPTY, ItemStack.EMPTY), new ItemStack(Items.PISTON, 63));
            f.expireUnexpectedReply();
            assertFalse(f.player.closed);
            f.assertExclusion();
            assertEquals(2, f.connection.packets.size());
            var packet = (net.minecraft.network.protocol.game.ServerboundContainerClickPacket) f.connection.packets.getLast();
            assertEquals(1, packet.slotNum());
            assertEquals(0, packet.buttonNum());
            assertEquals(-1, packet.stateId());
            assertTrue(f.menu.slots.get(1).getItem().isEmpty(), "Returning the cursor must not predict confirmation");
            for (int i = 0; i < 300; i++) f.adapter.tick();
            assertEquals(2, f.connection.packets.size());
            f.reply(f.menu, 2, List.of(ItemStack.EMPTY, new ItemStack(Items.PISTON, 63)), ItemStack.EMPTY);
            f.adapter.tick();
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(63, f.player.inventory.getItem(9).getCount());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void foreignCursorOrOccupiedReturnSlotIsNeverClickedOrClosed(boolean occupied) throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            f.reply(f.menu, 1, List.of(ItemStack.EMPTY, occupied ? new ItemStack(Items.DIAMOND) : ItemStack.EMPTY),
                    new ItemStack(occupied ? Items.PISTON : Items.DIAMOND));
            f.expireUnexpectedReply();
            for (int i = 0; i < 300; i++) f.adapter.tick();
            assertFalse(f.player.closed);
            assertEquals(2, f.connection.packets.size());
            assertEquals(-1, ((net.minecraft.network.protocol.game.ServerboundContainerClickPacket) f.connection.packets.getLast()).slotNum(),
                    "Only a no-op snapshot request is permitted");
            f.assertExclusion();
            f.menu.setCarried(ItemStack.EMPTY); // User manually resolves the cursor.
            f.adapter.tick();
            assertEquals(2, f.connection.packets.size());
            f.reply(f.menu, 2, List.of(ItemStack.EMPTY, new ItemStack(Items.DIAMOND)), ItemStack.EMPTY);
            f.adapter.tick();
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @Test void forcedCloseSynchronizesTheReplacementWithoutClosingItOrReplaying() throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            f.player.containerMenu = f.player.inventoryMenu;
            f.adapter.tick();
            assertEquals(2, f.connection.packets.size());
            f.assertExclusion();
            f.adapter.onInventoryContent(7, List.of(new ItemStack(Items.PISTON), ItemStack.EMPTY), ItemStack.EMPTY);
            f.assertExclusion();
            f.reply(f.player.inventoryMenu, 1,
                    f.player.inventoryMenu.slots.stream().map(s -> s.getItem().copy()).toList(), ItemStack.EMPTY);
            assertFalse(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(2, f.connection.packets.size());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"coordinator", "direct", "queued"})
    void manualPickRequestCannotReleaseOrReplaceAnUnconfirmedReturnDrain(String entry) throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            f.player.containerMenu = f.player.inventoryMenu;
            f.adapter.tick();
            var request = MaterialRequest.single(1, Items.REDSTONE_TORCH, 1, MaterialRequest.Source.PICK_BLOCK);
            if (entry.equals("coordinator")) assertEquals(MaterialReservation.State.PENDING, f.adapter.request(request).state());
            else if (entry.equals("direct")) assertFalse(f.adapter.requestItemsDirect(List.of(Items.REDSTONE_TORCH)));
            else {
                var requests = (QuickShulkerRequestController) get(f.adapter, QuickShulkerAdapter.class, "requests");
                requests.requestItem(Items.REDSTONE_TORCH);
                assertTrue(f.adapter.switchItem(), "Queued requests must wait for the old return to drain");
            }
            f.assertExclusion();
            assertEquals(2, f.connection.packets.size(), "Only the in-flight pickup and replacement resync may be sent");
        }
    }

    @Test void changingServerDiscardsTheOldReturnWithoutTouchingTheNewMenu() throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            var replacement = new QuickShulkerPacketContractTest.ServerMenu(new net.minecraft.world.SimpleContainer(1), f.player.inventory);
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

    @Test void returningItemsMustKeepTheOwnedMenuUntilServerConfirmation() throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            assertFalse(f.player.closed, "Local prediction must not close the menu ahead of delayed cursor replies");
            assertTrue(f.storage.hasPendingRestore(), "The inventory lease must survive the return clicks");
            for (int i = 0; i < 300; i++) f.storage.tick();
            assertFalse(f.player.closed, "Time passing is not server confirmation");
        }
    }

    @Test void intermediateRejectedPickupCannotBeMistakenForTheFinalReturnConfirmation() throws Exception {
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            f.adapter.tick();
            // Server rejected the pickup. The cursor is empty, but this is not a completed return.
            f.reply(f.menu, 1, List.of(ItemStack.EMPTY, new ItemStack(Items.PISTON, 64)), ItemStack.EMPTY);
            assertFalse(f.player.closed);
            f.assertExclusion();
        }
    }

    @Test void vanillaCorrectedPickupCanLeaveALateCursorWithoutAnEmptyCursorReply() throws Exception {
        var player = QuickShulkerPacketContractTest.allocate(QuickShulkerPacketContractTest.AuditPlayer.class);
        player.levelForAudit = QuickShulkerPacketContractTest.allocate(QuickShulkerPacketContractTest.AuditLevel.class);
        player.inventoryForAudit = new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
        player.inventoryForAudit.setItem(2, new ItemStack(Items.PISTON, 63));
        var server = new QuickShulkerPacketContractTest.ServerMenu(new net.minecraft.world.SimpleContainer(1), player.inventoryForAudit);
        var cursors = new ArrayList<ItemStack>();
        var replies = new QuickShulkerPacketContractTest.Replies() {
            @Override public void sendCarriedChange(AbstractContainerMenu menu, ItemStack stack) { cursors.add(stack.copy()); }
        };
        server.setSynchronizer(replies);
        server.suppressRemoteUpdates();
        server.clicked(1, 0, ContainerInput.PICKUP, player);
        // Client predicted 64; a previous use/replenishment changed the authoritative count to 63.
        server.setRemoteCarried(new HashedStack.ActualItem(BuiltInRegistries.ITEM.wrapAsHolder(Items.PISTON), 64,
                new net.minecraft.network.HashedPatchMap(Map.of(), Set.of())));
        server.resumeRemoteUpdates();
        server.broadcastChanges();
        server.suppressRemoteUpdates();
        server.clicked(0, 0, ContainerInput.PICKUP, player);
        server.setRemoteCarried(HashedStack.EMPTY); // Client also predicted an empty cursor after placement.
        server.resumeRemoteUpdates();
        server.broadcastChanges();
        assertTrue(server.getCarried().isEmpty());
        assertEquals(1, cursors.size(), "Vanilla does not echo a matching empty cursor prediction");
        assertEquals(63, cursors.getFirst().getCount());
        try (var f = new Fixture()) {
            f.storage.restorePendingItem();
            // handleSetCursorItem applies to the current menu, without a container ID.
            cursors.forEach(stack -> f.player.containerMenu.setCarried(stack));
            assertTrue(f.player.inventoryMenu.getCarried().isEmpty(),
                    "A late old-menu correction must not poison the inventory cursor and pause the scheduler");
        }
    }
}
//#endif
