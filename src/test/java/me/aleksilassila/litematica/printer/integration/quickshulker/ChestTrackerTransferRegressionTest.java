//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.*;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.*;
import me.aleksilassila.litematica.printer.integration.inventory.*;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.runtime.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

/** Real provider, broker and vanilla chest menu; replies are delivered explicitly after vanilla applies them. */
class ChestTrackerTransferRegressionTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }
    @SuppressWarnings("removal")
    static void inventoryClient(Object client) throws Exception {
        var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) access.get(null);
        var field = me.aleksilassila.litematica.printer.utils.InventoryUtils.class.getDeclaredField("client");
        unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), client);
    }
    static class Client extends Minecraft {
        Client() { super(null); }
        @Override public void setScreen(net.minecraft.client.gui.screens.Screen screen) {
            try {
                var guard = new me.aleksilassila.litematica.printer.mixin.MixinContainerScreenGuard() { };
                var method = guard.getClass().getSuperclass().getDeclaredMethod("suppressAutomatedQuickShulkerScreen",
                        net.minecraft.client.gui.screens.Screen.class, org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
                method.setAccessible(true);
                var callback = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("setScreen", true);
                method.invoke(guard, screen, callback);
                if (!callback.isCancelled()) this.screen = screen;
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }
    static class Level extends QuickShulkerPacketContractTest.AuditLevel {
        long tick;
        @Override public long getGameTime() { return tick; }
    }
    static class Mode extends MultiPlayerGameMode {
        int predicted;
        Mode() { super(null, null); }
        @Override public void handleContainerInput(int id, int slot, int button, ContainerInput kind,
                                                   net.minecraft.world.entity.player.Player player) {
            predicted++;
            if (kind == ContainerInput.QUICK_MOVE) player.containerMenu.quickMoveStack(player, slot);
            else player.containerMenu.clicked(slot, button, kind, player);
        }
    }
    static class Fixture implements AutoCloseable {
        final Object oldClient = get(null, Minecraft.class, "instance");
        final Object oldRuntime = get(null, RuntimeAccess.class, "current");
        final Minecraft client = allocate(Client.class);
        final OrderedStorageDrainTest.Player player = allocate(OrderedStorageDrainTest.Player.class);
        final Connection connection = allocate(Connection.class);
        final Level level = allocate(Level.class);
        final PrinterRuntime runtime = allocate(PrinterRuntime.class);
        final ActionBroker broker = new ActionBroker(runtime, new ActionManager());
        final ChestTrackerAdapter chest;
        final ChestMenu menu;
        final MaterialRequest request = MaterialRequest.single(42, Items.STONE, 1, MaterialRequest.Source.PRINT);
        Fixture() throws Exception {
            player.auditLevel = level;
            player.inventory = new net.minecraft.world.entity.player.Inventory(player, new net.minecraft.world.entity.EntityEquipment());
            set(player, net.minecraft.world.entity.player.Player.class, "abilities", new Abilities());
            set(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
            connection.packets = new ArrayList<>();
            set(player, net.minecraft.client.player.LocalPlayer.class, "connection", connection);
            menu = ChestMenu.threeRows(7, player.inventory, new net.minecraft.world.SimpleContainer(27));
            menu.slots.get(0).set(new ItemStack(Items.STONE, 64));
            player.containerMenu = menu;
            client.player = player;
            client.level = level;
            client.gameMode = allocate(Mode.class);
            set(client, Minecraft.class, "gui", allocate(net.minecraft.client.gui.Gui.class));
            set(client, Minecraft.class, "gameThread", Thread.currentThread());
            set(client, net.minecraft.util.thread.BlockableEventLoop.class, "pendingRunnables", new java.util.concurrent.ConcurrentLinkedQueue<>());
            set(null, Minecraft.class, "instance", client);
            set(runtime, PrinterRuntime.class, "epoch", RuntimeEpoch.INITIAL);
            set(runtime, PrinterRuntime.class, "actionBroker", broker);
            set(runtime, PrinterRuntime.class, "quickShulkerAdapter", new QuickShulkerAdapter(broker));
            RuntimeAccess.install(runtime);
            chest = new ChestTrackerAdapter(broker);
            set(runtime, PrinterRuntime.class, "chestTrackerAdapter", chest);
            set(chest, ChestTrackerAdapter.class, "activeRequest", request);
            set(chest, ChestTrackerAdapter.class, "requestedItem", Items.STONE);
            set(chest, ChestTrackerAdapter.class, "requestedItems", List.of(Items.STONE));
            set(chest, ChestTrackerAdapter.class, "targetPos", BlockPos.ZERO);
            set(chest, ChestTrackerAdapter.class, "requestDeadline", 200L);
            phase("WAITING_CONTENT");
            set(chest, ChestTrackerAdapter.class, "suppressContainerScreen", true);
            chest.onContainerOpen(7);
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        void phase(String name) throws Exception {
            var type = ChestTrackerAdapter.class.getDeclaredField("phase").getType();
            set(chest, ChestTrackerAdapter.class, "phase", Enum.valueOf((Class) type, name));
        }
        void initial() { reply(menu, 1, snapshot(menu), ItemStack.EMPTY); }
        List<ItemStack> snapshot(AbstractContainerMenu target) { return target.slots.stream().map(s -> s.getItem().copy()).toList(); }
        void reply(AbstractContainerMenu target, int revision, List<ItemStack> items, ItemStack cursor) {
            target.initializeContents(revision, items, cursor);
            chest.onContainerContent(target.containerId, revision, items, cursor);
        }
        void startReturn() throws Exception {
            menu.slots.get(0).set(ItemStack.EMPTY);
            player.inventory.setItem(9, new ItemStack(Items.SHULKER_BOX));
            var nested = get(chest, ChestTrackerAdapter.class, "nestedReturn");
            var record = nested.getClass().getDeclaredMethod("recordTransfer", BlockPos.class, int.class, ItemStack.class, int.class);
            record.setAccessible(true);
            record.invoke(nested, BlockPos.ZERO, 0, new ItemStack(Items.SHULKER_BOX), 9);
            set(nested, nested.getClass(), "restoring", true);
            phase("RESTORE_WAIT_CONTENT");
            initial();
        }
        void resetEpoch(String reason) throws Exception {
            var next = runtime.epoch().next();
            var event = new RuntimeEvent.EpochChanged(runtime.epoch(), next, reason);
            set(runtime, PrinterRuntime.class, "epoch", next);
            broker.onEpochChanged(event);
            chest.onEpochChanged(event);
            chest.reset(); // Coordinator also resets its providers in the same epoch.
        }
        void assertLocked() {
            assertTrue(broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(broker.isResourceHeld(ResourceLease.CONTAINER));
            for (String owner : List.of("print", "bedrock", "fill", "fluid", "quick_shulker", "take_it_out", "pick_block")) {
                assertFalse(broker.tryAcquire(owner, EnumSet.of(ResourceLease.INVENTORY), 0));
            }
        }
        @Override public void close() throws Exception {
            set(null, Minecraft.class, "instance", oldClient);
            set(null, RuntimeAccess.class, "current", oldRuntime);
        }
    }
    @Test void localShiftClickPredictionCannotAuthorizePrinting() throws Exception {
        try (var f = new Fixture()) {
            f.initial();
            assertFalse(f.player.closed, "Do not close before the server confirms the take");
            f.assertLocked();
            assertEquals(MaterialReservation.State.PENDING, f.chest.status(f.request).state());
            assertTrue(f.player.inventory.getItem(8).isEmpty(), "Do not invent the destination slot locally");
            assertEquals(0, ((Mode) f.client.gameMode).predicted);
            assertEquals(1, f.connection.packets.size());
        }
    }
    @Test void cancelCannotReleaseAnOutstandingTakeEvenAfterTheOldTimeout() throws Exception {
        try (var f = new Fixture()) {
            f.initial();
            f.chest.reset();
            for (int i = 0; i < 300; i++) { f.level.tick++; f.chest.tick(); }
            assertFalse(f.player.closed);
            f.assertLocked();
        }
    }
    @Test void nestedReturnMustNotPredictBothClicksAndClose() throws Exception {
        try (var f = new Fixture()) {
            f.startReturn();
            assertFalse(f.player.closed, "A return needs a server reply for each click");
            f.assertLocked();
            assertTrue(f.player.inventory.getItem(9).is(Items.SHULKER_BOX));
            assertEquals(1, f.connection.packets.size());
        }
    }
    @Test void actualServerDestinationWinsOverThePredictedHotbarSlot() throws Exception {
        try (var f = new Fixture()) {
            f.initial();
            f.player.inventory.setItem(8, new ItemStack(Items.DIRT, 32)); // Server picked up another stack after opening.
            f.menu.quickMoveStack(f.player, 0); // Vanilla now puts the requested stone in slot 7.
            var authoritative = f.snapshot(f.menu);
            f.player.inventory.setItem(8, ItemStack.EMPTY);
            f.player.inventory.setItem(7, ItemStack.EMPTY);
            f.assertLocked();
            f.reply(f.menu, 2, authoritative, ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(f.player.inventory.getItem(8).is(Items.DIRT));
            assertTrue(f.player.inventory.getItem(7).is(Items.STONE));
            assertEquals(MaterialReservation.State.AVAILABLE, f.chest.status(f.request).state());
        }
    }
    @Test void staleRevisionAndWrongMenuCannotFinishTheTake() throws Exception {
        try (var f = new Fixture()) {
            f.initial();
            f.chest.onContainerContent(7, 1, f.snapshot(f.menu), ItemStack.EMPTY);
            f.chest.onContainerContent(8, 2, f.snapshot(f.menu), ItemStack.EMPTY);
            f.assertLocked();
            assertFalse(f.player.closed);
            assertEquals(1, f.connection.packets.size());
        }
    }
    @Test void forcedCloseNeedsTheReplacementMenusFullReplyAndEmptyCursor() throws Exception {
        try (var f = new Fixture()) {
            f.initial();
            f.player.containerMenu = f.player.inventoryMenu;
            f.chest.tick();
            f.assertLocked();
            assertEquals(2, f.connection.packets.size());
            f.chest.onContainerContent(7, 2, f.snapshot(f.menu), ItemStack.EMPTY);
            f.assertLocked();
            f.reply(f.player.inventoryMenu, 2, f.snapshot(f.player.inventoryMenu), new ItemStack(Items.DIRT));
            f.assertLocked();
            assertFalse(f.chest.guardManualInput(), "Let the user put a foreign cursor stack away");
            f.chest.tick();
            assertTrue(f.chest.guardManualInput(), "One manual recovery at a time");
            f.reply(f.player.inventoryMenu, 3, f.snapshot(f.player.inventoryMenu), ItemStack.EMPTY);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertFalse(f.player.closed, "Never close the replacement menu");
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cancellationWaitsForTakeReplyOrServerRejection(boolean accepted) throws Exception {
        try (var f = new Fixture()) {
            f.initial();
            f.resetEpoch("work_switch_off");
            f.assertLocked();
            for (int i = 0; i < 300; i++) { f.level.tick++; f.chest.tick(); }
            assertEquals(1, f.connection.packets.size());
            if (accepted) f.menu.quickMoveStack(f.player, 0);
            f.reply(f.menu, 2, f.snapshot(f.menu), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(accepted, f.player.inventory.getItem(8).is(Items.STONE));
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void returnWaitsForEachReplyAndCancellationRestoresOnlyItsOwnCursor(boolean cancelled) throws Exception {
        try (var f = new Fixture()) {
            f.startReturn();
            if (cancelled) f.resetEpoch("work_switch_off");
            for (int i = 0; i < 300; i++) { f.level.tick++; f.chest.tick(); }
            f.assertLocked();
            assertEquals(1, f.connection.packets.size());
            var picked = new ArrayList<>(f.snapshot(f.menu));
            picked.set(27, ItemStack.EMPTY); // Player slot 9.
            f.reply(f.menu, 2, picked, new ItemStack(Items.SHULKER_BOX));
            assertEquals(2, f.connection.packets.size());
            var click = (net.minecraft.network.protocol.game.ServerboundContainerClickPacket) f.connection.packets.get(1);
            assertEquals(cancelled ? 27 : 0, click.slotNum());
            f.assertLocked();
            for (int i = 0; i < 300; i++) { f.level.tick++; f.chest.tick(); }
            assertFalse(f.player.closed, "Five ticks or any longer delay cannot acknowledge a return");
            var placed = new ArrayList<>(picked);
            placed.set(cancelled ? 27 : 0, new ItemStack(Items.SHULKER_BOX));
            f.reply(f.menu, 3, placed, ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(cancelled, f.player.inventory.getItem(9).is(Items.SHULKER_BOX));
        }
    }
    @Test void serverSwitchDiscardsOldOwnershipWithoutTouchingNewMenu() throws Exception {
        try (var f = new Fixture()) {
            f.initial();
            f.client.level = allocate(Level.class);
            var replacement = ChestMenu.threeRows(7, f.player.inventory, new net.minecraft.world.SimpleContainer(27));
            f.player.containerMenu = replacement;
            f.resetEpoch("client_scope_changed");
            f.chest.onContainerContent(7, 2, f.snapshot(f.menu), ItemStack.EMPTY);
            assertSame(replacement, f.player.containerMenu);
            assertFalse(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertFalse(f.chest.guardManualInput());
            assertFalse(f.chest.guardClose());
            assertEquals(1, f.connection.packets.size());
        }
    }
    @Test void forcedCloseBeforeInitialContentStillDrainsTheReplacement() throws Exception {
        try (var f = new Fixture()) {
            f.player.containerMenu = f.player.inventoryMenu;
            f.chest.reset();
            f.chest.tick();
            f.assertLocked();
            assertEquals(1, f.connection.packets.size(), "A close before initial content needs a replacement snapshot");
            f.reply(f.player.inventoryMenu, 1, f.snapshot(f.player.inventoryMenu), ItemStack.EMPTY);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertFalse(f.player.closed);
        }
    }
    @Test void lateOpenAfterCancellationNeverStartsATake() throws Exception {
        try (var f = new Fixture()) {
            set(f.chest, ChestTrackerAdapter.class, "ownedMenu", null);
            set(f.chest, ChestTrackerAdapter.class, "openingMenu", f.player.inventoryMenu);
            f.player.containerMenu = f.player.inventoryMenu;
            f.chest.reset();
            f.assertLocked();
            f.player.containerMenu = f.menu;
            f.chest.onContainerOpen(7);
            f.initial();
            assertTrue(f.player.closed);
            assertTrue(f.connection.packets.isEmpty());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }
    @Test void rejectedOpenBarrierCanFinishCancellation() throws Exception {
        try (var f = new Fixture()) {
            set(f.chest, ChestTrackerAdapter.class, "ownedMenu", null);
            set(f.chest, ChestTrackerAdapter.class, "openingMenu", f.player.inventoryMenu);
            f.player.containerMenu = f.player.inventoryMenu;
            f.chest.reset();
            f.reply(f.player.inventoryMenu, 1, f.snapshot(f.player.inventoryMenu), ItemStack.EMPTY);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertFalse(f.player.closed);
        }
    }
    @Test void foreignCursorDuringReturnRequiresOneManualRecoveryAndItsConfirmation() throws Exception {
        try (var f = new Fixture()) {
            f.startReturn();
            var changed = new ArrayList<>(f.snapshot(f.menu));
            changed.set(27, ItemStack.EMPTY);
            f.reply(f.menu, 2, changed, new ItemStack(Items.DIRT));
            var transfer = get(f.chest, ChestTrackerAdapter.class, "transfer");
            var returning = get(transfer, transfer.getClass(), "returning");
            set(returning, returning.getClass(), "sentAt", 0L);
            f.chest.tick();
            assertEquals(2, f.connection.packets.size(), "Unexpected cursor permits only a refresh");
            assertTrue(f.chest.guardManualInput(), "Refresh A was sent before recovery; drain it before permitting any click");
            f.reply(f.menu, 3, changed, new ItemStack(Items.DIRT));
            assertFalse(f.chest.guardManualInput());
            f.menu.setCarried(ItemStack.EMPTY); // Manual click is predicted, not yet acknowledged.
            f.chest.tick();
            f.assertLocked();
            assertTrue(f.chest.guardManualInput(), "Do not allow another recovery before its full reply");
            assertTrue(f.chest.guardClose());
            f.chest.onContainerContent(7, 3, changed, new ItemStack(Items.DIRT));
            assertTrue(f.chest.guardManualInput(), "An old refresh cannot unlock a second recovery");
            f.reply(f.menu, 4, changed, ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }
    @Test void aHiddenContainerBecomesVisibleForManualCursorRecovery() throws Exception {
        try (var f = new Fixture()) {
            var screen = allocate(net.minecraft.client.gui.screens.inventory.ContainerScreen.class);
            set(screen, net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class, "menu", f.menu);
            f.client.setScreen(screen); // Exercise the real screen-suppression mixin.
            assertNull(f.client.screen);
            f.initial();
            f.reply(f.menu, 2, f.snapshot(f.menu), new ItemStack(Items.DIRT));
            f.chest.tick();
            assertSame(screen, f.client.screen, "Recovery must show the real current menu, not an unrelated inventory screen");
            assertFalse(f.chest.shouldSuppressContainerScreen());
            assertFalse(f.chest.guardManualInput());
            f.assertLocked();
        }
    }
    @Test void pickBlockWhilePrinterIsOffSelectsOnlyTheConfirmedServerSlot() throws Exception {
        try (var f = new Fixture()) {
            var config = me.aleksilassila.litematica.printer.config.Configs.Core.WORK_SWITCH;
            boolean oldWork = config.getBooleanValue();
            var inventoryUtils = me.aleksilassila.litematica.printer.utils.InventoryUtils.class;
            Object oldInventoryClient = get(null, inventoryUtils, "client");
            try {
                config.setBooleanValue(false);
                inventoryClient(f.client);
                var pick = MaterialRequest.single(43, Items.STONE, 1, MaterialRequest.Source.PICK_BLOCK);
                set(f.chest, ChestTrackerAdapter.class, "activeRequest", pick);
                f.initial();
                f.assertLocked();
                assertEquals(1, f.connection.packets.size(), "No hotbar selection before the take is confirmed");
                f.player.inventory.setItem(8, new ItemStack(Items.DIRT));
                f.menu.quickMoveStack(f.player, 0);
                f.reply(f.menu, 2, f.snapshot(f.menu), ItemStack.EMPTY);
                var selected = (net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket) f.connection.packets.getLast();
                assertEquals(7, selected.getSlot());
                assertTrue(f.player.inventory.getItem(selected.getSlot()).is(Items.STONE));
                assertFalse(f.chest.guardManualInput());
                assertFalse(f.chest.guardClose());
                assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            } finally {
                inventoryClient(oldInventoryClient);
                config.setBooleanValue(oldWork);
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void forcedCloseDuringReturnDoesNotReplayEitherClick(boolean firstConfirmed) throws Exception {
        try (var f = new Fixture()) {
            f.startReturn();
            if (firstConfirmed) {
                var picked = new ArrayList<>(f.snapshot(f.menu));
                picked.set(27, ItemStack.EMPTY);
                f.reply(f.menu, 2, picked, new ItemStack(Items.SHULKER_BOX));
            }
            int sent = f.connection.packets.size();
            f.player.containerMenu = f.player.inventoryMenu;
            f.chest.tick();
            f.assertLocked();
            assertEquals(sent + 1, f.connection.packets.size());
            f.player.inventory.setItem(9, new ItemStack(Items.SHULKER_BOX));
            f.reply(f.player.inventoryMenu, 3, f.snapshot(f.player.inventoryMenu), ItemStack.EMPTY);
            assertFalse(f.player.closed);
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(sent + 1, f.connection.packets.size());
        }
    }
}
//#endif
