//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.QuickShulkerModeType;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;

/** Real open/cancel/recovery code, with network replies delivered explicitly after arbitrary delays. */
class ShulkerOpenRecoveryLifecycleTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static class FenceConnection extends Connection {
        java.util.function.Consumer<net.minecraft.network.protocol.Packet<?>> onSend;
        net.minecraft.network.Connection transport;
        @Override public net.minecraft.network.Connection getConnection() { return transport; }
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
            super.send(packet);
            if (onSend != null) onSend.accept(packet);
        }
    }

    static class Fixture implements AutoCloseable {
        final Object oldClient = get(null, Minecraft.class, "instance");
        final ConfigSnapshot config = new ConfigSnapshot();
        final Minecraft client = allocate(Minecraft.class);
        final Player player = allocate(Player.class);
        final FenceConnection connection = allocate(FenceConnection.class);
        final Object transport = allocate(net.minecraft.network.Connection.class);
        final AtomicBoolean leased = new AtomicBoolean();
        final Class<?> utility;
        final Class<?> fence;
        final Object adapter;
        final ItemStack box = new ItemStack(Items.SHULKER_BOX);
        final int source;

        Fixture() throws Exception { this(12); }
        Fixture(int source) throws Exception { this(source, true); }
        Fixture(int source, boolean cancel) throws Exception {
            this.source = source;
            connection.packets = new ArrayList<>();
            connection.transport = (net.minecraft.network.Connection) transport;
            set(player, net.minecraft.client.player.LocalPlayer.class, "connection", connection);
            player.inventory = new net.minecraft.world.entity.player.Inventory(player, new net.minecraft.world.entity.EntityEquipment());
            set(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
            player.containerMenu = player.inventoryMenu;
            player.inventoryMenu.slots.get(source).set(box.copy());
            client.player = player;
            client.gameMode = allocate(net.minecraft.client.multiplayer.MultiPlayerGameMode.class);
            set(null, Minecraft.class, "instance", client);
            var loader = new ClassLoader(getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.startsWith("me.aleksilassila.litematica.printer.integration.quickshulker.")
                            && !name.equals("me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils")) return super.loadClass(name, resolve);
                    synchronized (getClassLoadingLock(name)) {
                        var type = findLoadedClass(name);
                        if (type == null) try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) throw new ClassNotFoundException(name);
                            byte[] bytes = input.readAllBytes();
                            type = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
            };
            utility = loader.loadClass("me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils");
            fence = loader.loadClass(InventoryRecoveryFence.class.getName());
            connection.onSend = packet -> {
                try {
                    fence.getMethod("inputSent", Object.class, net.minecraft.network.protocol.Packet.class).invoke(null, transport, packet);
                    fence.getMethod("sent", Object.class, net.minecraft.network.protocol.Packet.class).invoke(null, transport, packet);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            };
            var port = (ActionPort) Proxy.newProxyInstance(ActionPort.class.getClassLoader(), new Class<?>[]{ActionPort.class}, (p,m,a) -> {
                if (m.getName().equals("tryAcquire")) { leased.set(true); return true; }
                if (m.getName().equals("releaseOwner")) { leased.set(false); return null; }
                return m.getReturnType() == boolean.class ? false : null;
            });
            adapter = loader.loadClass(QuickShulkerAdapter.class.getName()).getConstructor(ActionPort.class).newInstance(port);
            Configs.Placement.QUICK_SHULKER_MODE.setOptionListValue(QuickShulkerModeType.CLICK_SLOT);
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            assertEquals(true, utility.getMethod("openShulker", ItemStack.class, int.class).invoke(null, box, source));
            if (cancel) adapter.getClass().getMethod("reset").invoke(adapter);
        }

        void tick() throws Exception {
            utility.getMethod("tickRecovery").invoke(null);
            adapter.getClass().getMethod("tick").invoke(adapter);
        }
        boolean recovering() throws Exception { return (boolean) utility.getMethod("isRecovering").invoke(null); }
        void pickupReply() {
            player.inventoryMenu.slots.get(source).set(ItemStack.EMPTY);
            player.inventoryMenu.setCarried(box.copy());
        }
        long returns() { return connection.packets.stream().filter(p -> p instanceof ServerboundContainerClickPacket click
                && click.slotNum() == source && click.buttonNum() == 0 && click.containerInput() == ContainerInput.PICKUP).count(); }
        long clicks() { return connection.packets.stream().filter(ServerboundContainerClickPacket.class::isInstance).count(); }
        long markers() { return connection.packets.stream().filter(p -> p instanceof
                net.minecraft.network.protocol.game.ServerboundClientCommandPacket command
                && command.getAction() == net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.REQUEST_STATS).count(); }
        void fenceReply() throws Exception {
            fence.getMethod("received", Object.class).invoke(null, transport);
            tick();
        }
        void manualClick(int slot) throws Exception {
            utility.getClassLoader().loadClass(QuickShulkerClicks.class.getName()).getMethod("send",
                    AbstractContainerMenu.class, int.class, int.class, ContainerInput.class)
                    .invoke(null, player.inventoryMenu, slot, 0, ContainerInput.PICKUP);
        }
        void snapshot(ItemStack carried) throws Exception {
            List<ItemStack> items = player.inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList();
            player.inventoryMenu.initializeContents(player.inventoryMenu.getStateId() + 1, items, carried);
            // Mirror the bridge's observe-entire-packet-before-advance contract, including
            // the slot/cursor acknowledgements which the previous fixture omitted.
            for (int i = 0; i < items.size(); i++) {
                utility.getMethod("onSlotUpdate", int.class, int.class, ItemStack.class).invoke(null, 0, i, items.get(i));
                adapter.getClass().getMethod("observeSlotUpdate", int.class, int.class, ItemStack.class).invoke(adapter, 0, i, items.get(i));
            }
            utility.getMethod("onSlotUpdate", int.class, int.class, ItemStack.class).invoke(null, -1, -1, carried);
            adapter.getClass().getMethod("observeSlotUpdate", int.class, int.class, ItemStack.class).invoke(adapter, -1, -1, carried);
            adapter.getClass().getMethod("onInventoryContent", int.class, List.class, ItemStack.class).invoke(adapter, 0, items, carried);
        }
        @Override public void close() throws Exception {
            set(null, Minecraft.class, "instance", oldClient);
            config.restore();
        }
    }
    static class ConfigSnapshot {
        final boolean work = Configs.Core.WORK_SWITCH.getBooleanValue();
        final boolean quick = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        final fi.dy.masa.malilib.config.IConfigOptionListEntry mode = Configs.Placement.QUICK_SHULKER_MODE.getOptionListValue();
        void restore() {
            Configs.Core.WORK_SWITCH.setBooleanValue(work);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(quick);
            Configs.Placement.QUICK_SHULKER_MODE.setOptionListValue(mode);
        }
    }

    @Test void disabledPrinterRetainsInventoryLeaseUntilFailedOpenIsSynchronized() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.recovering());
            assertTrue(f.leased.get(), "An outstanding pickup must exclude bedrock, manual pick and other providers");
        }
    }

    @Test void delayedRepliesAndRepeatedDisableDoNotPollTheServer() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 0; i < 600; i++) {
                f.adapter.getClass().getMethod("reset").invoke(f.adapter);
                f.tick();
            }
            assertEquals(1, f.markers(), "A missing reply must not cause per-tick statistics requests");
            assertEquals(2, f.clicks(), "Open plus one full synchronization request");
            assertTrue(f.leased.get());
            f.pickupReply();
            f.fenceReply();
            for (int i = 0; i < 600; i++) f.tick();
            assertEquals(2, f.markers(), "The return needs one marker, never repeated polling");
            assertEquals(3, f.clicks());
            assertEquals(1, f.returns());
            f.player.inventoryMenu.slots.get(f.source).set(f.box.copy());
            f.snapshot(ItemStack.EMPTY);
            f.fenceReply();
            assertFalse(f.leased.get());
        }
    }

    @Test void successfulOpeningNeverRequestsARecoveryMarker() throws Exception {
        try (var f = new Fixture(12, false)) {
            // Exercise the real controller confirmation that accepts the matching box.
            Object requests = get(f.adapter, f.adapter.getClass(), "requests");
            Object session = get(requests, requests.getClass(), "containerSession");
            var begin = session.getClass().getDeclaredMethod("begin", ItemStack.class, boolean.class);
            begin.setAccessible(true);
            begin.invoke(session, f.box, true);
            set(requests, requests.getClass(), "isOpenHandler", true);
            for (int i = 0; i < 300; i++) f.utility.getMethod("tickRecovery").invoke(null);
            f.player.containerMenu = new ShulkerBoxMenu(8, f.player.inventory);
            requests.getClass().getMethod("onContainerOpen", int.class, MenuType.class).invoke(requests, 8, MenuType.SHULKER_BOX);
            var items = f.player.containerMenu.slots.stream().map(slot -> slot.getItem().copy()).toList();
            assertEquals(true, requests.getClass().getMethod("confirmContainer", int.class, List.class, ItemStack.class)
                    .invoke(requests, 8, items, ItemStack.EMPTY));
            f.adapter.getClass().getMethod("reset").invoke(f.adapter);
            for (int i = 0; i < 300; i++) f.utility.getMethod("tickRecovery").invoke(null);
            assertEquals(0, f.markers());
            assertEquals(1, f.clicks(), "Only the original opening click, no recovery synchronization");
            assertFalse(f.recovering());
        }
    }

    @Test void pickupArrivingAfterFortyTicksIsStillReturnedExactlyOnce() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 0; i < 300; i++) f.tick();
            assertTrue(f.recovering(), "Elapsed time is not a server acknowledgement");
            f.pickupReply();
            f.fenceReply();
            for (int i = 0; i < 300; i++) f.tick();
            assertEquals(1, f.returns());
            assertTrue(f.leased.get(), "Keep the lease while the return is in flight");
        }
    }

    @Test void unrelatedContainerDoesNotForgetPendingPickupOrReceiveRecoveryClicks() throws Exception {
        try (var f = new Fixture()) {
            f.player.containerMenu = new ShulkerBoxMenu(8, f.player.inventory);
            int before = f.connection.packets.size();
            f.tick();
            assertTrue(f.recovering());
            assertEquals(before, f.connection.packets.size());
            f.player.containerMenu = f.player.inventoryMenu;
            f.pickupReply();
            f.tick();
            f.fenceReply();
            assertEquals(1, f.returns());
        }
    }

    @Test void authoritativeNoPickupReplyReleasesLeaseWithoutTouchingInventory() throws Exception {
        try (var f = new Fixture()) {
            f.tick();
            assertTrue(f.leased.get());
            assertEquals(2, f.clicks(), "Open plus one synchronization request");
            f.snapshot(ItemStack.EMPTY);
            assertTrue(f.recovering());
            assertTrue(f.leased.get());
            f.fenceReply();
            assertFalse(f.recovering());
            assertFalse(f.leased.get());
            assertEquals(0, f.returns());
            assertTrue(f.player.inventoryMenu.slots.get(12).getItem().is(Items.SHULKER_BOX));
        }
    }

    @Test void delayedReturnCompletionReleasesLeaseWithoutAnotherPickup() throws Exception {
        try (var f = new Fixture()) {
            f.pickupReply();
            f.tick();
            f.fenceReply();
            for (int i = 0; i < 300; i++) f.tick();
            assertEquals(1, f.returns());
            f.player.inventoryMenu.slots.get(12).set(f.box.copy());
            f.snapshot(ItemStack.EMPTY);
            assertTrue(f.leased.get());
            f.fenceReply();
            f.tick();
            assertFalse(f.recovering());
            assertFalse(f.leased.get());
            assertEquals(1, f.returns());
        }
    }

    @Test void unrelatedCursorIsNotMovedAndManualCleanupResynchronizes() throws Exception {
        try (var f = new Fixture()) {
            f.tick();
            f.snapshot(new ItemStack(Items.DIRT));
            f.fenceReply();
            for (int i = 0; i < 300; i++) f.tick();
            assertEquals(0, f.returns());
            assertEquals(2, f.clicks());
            assertTrue(f.leased.get());
            f.player.inventoryMenu.setCarried(ItemStack.EMPTY);
            f.manualClick(13);
            f.tick();
            assertEquals(4, f.clicks()); // Open, first sync, manual placement, second sync.
            f.snapshot(ItemStack.EMPTY);
            f.fenceReply();
            assertFalse(f.leased.get());
        }
    }

    @Test void changingServerDiscardsOldCursorOwnershipAndNeverClicksTheNewInventory() throws Exception {
        try (var f = new Fixture()) {
            f.pickupReply();
            f.adapter.getClass().getMethod("onEpochChanged", RuntimeEvent.EpochChanged.class).invoke(f.adapter,
                    new RuntimeEvent.EpochChanged(RuntimeEpoch.INITIAL, RuntimeEpoch.INITIAL.next(), "client_scope_changed"));
            f.tick();
            assertFalse(f.recovering());
            assertEquals(0, f.returns());
            assertEquals(1, f.connection.packets.size());
        }
    }

    @Test void olderEmptySnapshotCannotReleaseTheLaterOpeningPickup() throws Exception {
        try (var f = new Fixture()) {
            f.tick();
            // S0 was produced by a previous inventory click, before the opening PICKUP.
            // Both replies are delayed; S0 still has a newer revision than the client knew.
            f.snapshot(ItemStack.EMPTY);
            assertTrue(f.recovering(), "A newer menu revision does not identify the acknowledged click");
            assertTrue(f.leased.get());
            // S1 is the subsequent result of our opening PICKUP, in normal TCP order.
            f.pickupReply();
            f.snapshot(f.box.copy());
            f.tick();
            assertEquals(0, f.returns(), "Do not act on snapshots preceding the fence reply");
            f.fenceReply();
            assertEquals(1, f.returns());
            assertTrue(f.leased.get());
            f.player.inventoryMenu.slots.get(12).set(f.box.copy());
            f.snapshot(ItemStack.EMPTY);
            assertTrue(f.leased.get());
            f.fenceReply();
            assertFalse(f.leased.get());
            assertFalse(f.recovering());
        }
    }

    @Test void earlierStatsScreenReplyCannotCompleteRecoveryFence() throws Exception {
        try (var f = new Fixture()) {
            f.connection.send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                    net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.REQUEST_STATS));
            f.tick();
            f.snapshot(ItemStack.EMPTY);
            f.fenceReply();
            assertTrue(f.recovering());
            assertTrue(f.leased.get());
            f.fenceReply();
            assertFalse(f.recovering());
            assertFalse(f.leased.get());
        }
    }

    @Test void serverMenuRoundTripInvalidatesFenceEvenWithoutAnInterveningTick() throws Exception {
        try (var f = new Fixture()) {
            f.tick();
            // These are the invalidations emitted by the open/forced-close packet hooks,
            // including while QUICK_SHULKER is disabled. End on the same inventory object.
            f.player.containerMenu = new ShulkerBoxMenu(8, f.player.inventory);
            f.fence.getMethod("invalidate", Object.class).invoke(null, f.transport);
            f.player.containerMenu = f.player.inventoryMenu;
            f.fence.getMethod("invalidate", Object.class).invoke(null, f.transport);
            f.snapshot(ItemStack.EMPTY);
            f.fenceReply();
            assertTrue(f.recovering());
            assertTrue(f.leased.get());
            f.snapshot(ItemStack.EMPTY);
            f.fenceReply();
            assertFalse(f.recovering());
            assertFalse(f.leased.get());
        }
    }

    @Test void manualPickupAfterAutomaticReturnInvalidatesItsCompletion() throws Exception {
        try (var f = new Fixture()) {
            f.pickupReply();
            f.tick();
            f.fenceReply();
            assertEquals(1, f.returns());
            // The user clicks after the automatic return was queued, before its reply.
            f.manualClick(12);
            long sent = f.returns();
            f.player.inventoryMenu.slots.get(12).set(f.box.copy());
            f.snapshot(ItemStack.EMPTY); // Only the earlier automatic return has completed.
            f.fenceReply();
            assertTrue(f.recovering());
            assertTrue(f.leased.get());
            f.pickupReply();
            f.snapshot(f.box.copy());
            f.fenceReply();
            for (int i = 0; i < 100; i++) f.tick();
            assertEquals(sent, f.returns(), "Do not fight a new manual pickup by replaying our return");
            assertTrue(f.leased.get());
            f.manualClick(12);
            f.tick();
            f.player.inventoryMenu.slots.get(12).set(f.box.copy());
            f.snapshot(ItemStack.EMPTY);
            f.fenceReply();
            assertFalse(f.recovering());
            assertFalse(f.leased.get());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"recipe", "entity_pick", "entity_interact", "use_item_on", "use_item"})
    void inventoryInputAfterMarkerCannotExchangeAnUnrelatedSourceStackOntoCursor(String input) throws Exception {
        try (var f = new Fixture(36)) {
            // The box originated in the selected hotbar slot. It is the only free inventory
            // slot after the opening PICKUP; recipe returns/native entity picking can fill it.
            for (int i = 1; i < 36; i++) f.player.inventory.setItem(i, new ItemStack(Items.DIRT, 64));
            f.pickupReply();
            if (input.equals("use_item")) f.player.inventoryMenu.slots.get(45).set(new ItemStack(Items.BUCKET, 2));
            f.tick();
            var earlierItems = f.player.inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList();
            net.minecraft.network.protocol.Packet<?> packet = switch (input) {
                case "recipe" -> new net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket(0,
                        new net.minecraft.world.item.crafting.display.RecipeDisplayId(0), false);
                case "entity_interact" -> new net.minecraft.network.protocol.game.ServerboundInteractPacket(
                        1, net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.phys.Vec3.ZERO, false);
                case "use_item_on" -> new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(
                        net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(
                                net.minecraft.world.phys.Vec3.ZERO, net.minecraft.core.Direction.UP, net.minecraft.core.BlockPos.ZERO, false), 1);
                case "use_item" -> new net.minecraft.network.protocol.game.ServerboundUseItemPacket(
                        net.minecraft.world.InteractionHand.OFF_HAND, 1, 0, 0);
                default -> new net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket(1, false);
            };
            f.connection.send(packet);
            if (input.equals("use_item")) {
                // A stacked offhand bucket predicts a filled bucket in the only empty slot.
                // The earlier recovery snapshot then overwrites that prediction before its marker.
                f.player.inventoryMenu.slots.get(36).set(new ItemStack(Items.WATER_BUCKET));
                f.player.inventoryMenu.slots.get(45).set(new ItemStack(Items.BUCKET));
                f.player.inventoryMenu.initializeContents(f.player.inventoryMenu.getStateId() + 1, earlierItems, f.box.copy());
                f.snapshot(f.box.copy());
                assertTrue(f.player.inventoryMenu.slots.get(36).getItem().isEmpty());
            }
            f.fenceReply(); // The older marker cannot acknowledge the later input.
            assertEquals(0, f.returns());
            assertTrue(f.leased.get());
            Item received = switch (input) {
                case "entity_interact" -> Items.IRON_HELMET;
                case "use_item_on" -> Items.BOOK;
                case "use_item" -> Items.WATER_BUCKET;
                default -> Items.STONE;
            };
            f.player.inventoryMenu.slots.get(36).set(new ItemStack(received));
            f.snapshot(f.box.copy());
            f.fenceReply();
            assertEquals(0, f.returns(), "Never PICKUP an occupied original slot and swap its material onto the cursor");
            assertTrue(f.player.inventoryMenu.slots.get(36).getItem().is(received));
            assertTrue(f.player.inventoryMenu.getCarried().is(Items.SHULKER_BOX));
            assertTrue(f.recovering());
            assertTrue(f.leased.get());
        }
    }
}
//#endif
