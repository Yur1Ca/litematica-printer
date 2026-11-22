//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.QuickShulkerModeType;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerEquipment;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.*;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;

/** Real adapter/controller/bridge and server packet payloads; no client tick advances a successful reply. */
public class ConfirmationAdvanceTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void extractionCompletesOnTheSecondAuthoritativeSlotInEitherOrder(boolean targetFirst) throws Exception {
        try (var f = new Fixture(false)) {
            int first = targetFirst ? 56 : 0;
            int second = targetFirst ? 0 : 56;
            f.slot(first, first == 0 ? ItemStack.EMPTY : new ItemStack(Items.STONE, 64));
            assertFalse(f.player.closed);
            assertTrue(f.leased.get());
            assertEquals(1, f.clicks().size());
            f.slot(second, second == 0 ? ItemStack.EMPTY : new ItemStack(Items.STONE, 64));
            assertTrue(f.player.closed, "Both authoritative slots should finish without the next client tick");
            assertFalse(f.leased.get());
            assertFalse(f.switchGuard.isWaiting(), "An acknowledged extraction must not start another prediction wait");
            assertEquals(2, f.player.inventory.getSelectedSlot());
            assertEquals(1, f.connection.packets.stream().filter(ServerboundSetCarriedItemPacket.class::isInstance).count());
            f.slot(second, second == 0 ? ItemStack.EMPTY : new ItemStack(Items.STONE, 64));
            assertEquals(2, f.connection.packets.size(), "Duplicate acknowledgements cannot repeat completion");
        }
    }

    @Test void clearingConfirmationImmediatelyStartsOnlyOneExtraction() throws Exception {
        try (var f = new Fixture(true)) {
            assertEquals(27, f.clicks().getFirst().slotNum());
            f.slot(27, new ItemStack(Items.DIRT, 32));
            assertEquals(1, f.clicks().size());
            f.slot(56, ItemStack.EMPTY);
            assertEquals(2, f.clicks().size(), "The accepted clear sends extraction before another tick");
            assertEquals(0, f.clicks().getLast().slotNum());
            assertFalse(f.player.closed);
            assertTrue(f.leased.get());
            f.slot(0, ItemStack.EMPTY);
            assertFalse(f.player.closed, "The previous clear acknowledgement cannot confirm the new extraction");
            f.slot(56, new ItemStack(Items.STONE, 64));
            assertTrue(f.player.closed);
            assertFalse(f.leased.get());
            assertEquals(2, f.clicks().size());
        }
    }

    @Test void fullSnapshotIsEntirelyObservedBeforeTheNextClickAndCannotConfirmThatClick() throws Exception {
        try (var f = new Fixture(true)) {
            var contents = f.contents();
            contents.set(27, new ItemStack(Items.DIRT, 32));
            contents.set(56, ItemStack.EMPTY);
            f.menu.initializeContents(1, contents, ItemStack.EMPTY);
            var read = new boolean[contents.size()];
            List<ItemStack> audited = new AbstractList<>() {
                @Override public int size() { return contents.size(); }
                @Override public ItemStack get(int index) { read[index] = true; return contents.get(index); }
            };
            f.connection.beforeSend = packet -> {
                for (int i = 0; i < read.length; i++) assertTrue(read[i], "Snapshot slot " + i + " must precede the next operation");
            };
            f.content(audited, ItemStack.EMPTY);
            assertEquals(2, f.clicks().size());
            assertEquals(0, f.clicks().getLast().slotNum());
            assertFalse(f.player.closed);
            assertTrue(f.leased.get());
            f.connection.beforeSend = packet -> { };
            f.slot(0, ItemStack.EMPTY);
            assertFalse(f.player.closed);
            f.slot(56, new ItemStack(Items.STONE, 64));
            assertTrue(f.player.closed);
        }
    }

    @Test void fullSnapshotCanFinishExtractionWithoutWaitingForTick() throws Exception {
        try (var f = new Fixture(false)) {
            var contents = f.contents();
            contents.set(0, ItemStack.EMPTY);
            contents.set(56, new ItemStack(Items.STONE, 64));
            f.menu.initializeContents(1, contents, ItemStack.EMPTY);
            f.content(contents, ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.leased.get());
            assertEquals(1, f.clicks().size());
        }
    }

    @Test void nonemptyCursorBlocksProgressUntilItsAuthoritativeEmptyUpdate() throws Exception {
        try (var f = new Fixture(true)) {
            f.menu.setCarried(new ItemStack(Items.STICK));
            f.slot(27, new ItemStack(Items.DIRT, 32));
            f.slot(56, ItemStack.EMPTY);
            assertEquals(1, f.clicks().size());
            assertTrue(f.leased.get());
            f.menu.setCarried(ItemStack.EMPTY);
            f.bridge("onSlotUpdate", new Class<?>[]{int.class, int.class, ItemStack.class}, -1, -1, ItemStack.EMPTY);
            assertEquals(2, f.clicks().size());
            assertFalse(f.player.closed);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"quick", "work"})
    void disableBeforeReplyDrainsWithoutStartingExtraction(String feature) throws Exception {
        try (var f = new Fixture(true)) {
            f.slot(27, new ItemStack(Items.DIRT, 32));
            if (feature.equals("quick")) Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            else Configs.Core.WORK_SWITCH.setBooleanValue(false);
            f.slot(56, ItemStack.EMPTY);
            assertTrue((boolean) f.request("isCancelling", new Class<?>[0]));
            assertFalse(f.player.closed);
            assertTrue(f.leased.get());
            assertEquals(2, f.clicks().size());
            assertEquals(ContainerInput.PICKUP, f.clicks().getLast().containerInput());
            assertEquals(-1, f.clicks().getLast().slotNum(), "Only the existing cancellation resync may be sent");
            f.content(f.contents(), ItemStack.EMPTY);
            assertTrue(f.player.closed);
            assertFalse(f.leased.get());
            assertEquals(1, f.clicks().stream().filter(p -> p.containerInput() == ContainerInput.SWAP).count());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"menu", "connection"})
    void aReusedContainerIdCannotFinishTheOldTransferEvenOnTick(String changed) throws Exception {
        try (var f = new Fixture(false)) {
            if (changed.equals("menu")) f.player.containerMenu = new ShulkerBoxMenu(7, f.player.inventory);
            else {
                var other = allocate(CapturedConnection.class);
                other.packets = new ArrayList<>(); other.beforeSend = packet -> { };
                set(f.player, LocalPlayer.class, "connection", other);
            }
            f.slot(0, ItemStack.EMPTY);
            f.slot(56, new ItemStack(Items.STONE, 64));
            f.adapter("tick", new Class<?>[0]);
            assertFalse(f.player.closed, "Menu id equality is not ownership");
            assertEquals(1, f.connection.packets.size());
        }
    }

    @Test void externalPickRequestCanCompleteWhileWorkIsOffAndOrderlyStorageIsOff() throws Exception {
        try (var f = new Fixture(true)) {
            f.adapter("allowExternalRequest", new Class<?>[0]);
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            f.slot(27, new ItemStack(Items.DIRT, 32));
            f.slot(56, ItemStack.EMPTY);
            assertEquals(2, f.clicks().size());
            f.slot(0, ItemStack.EMPTY);
            f.slot(56, new ItemStack(Items.STONE, 64));
            assertTrue(f.player.closed);
            assertFalse(f.leased.get());
            assertEquals(64, f.player.inventory.getItem(2).getCount());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"quick", "work", "orderly", "external_work"})
    void disabledPendingRestoreFinishesOnItsOpeningSnapshotWithoutStartingAPlan(String feature) throws Exception {
        try (var f = new Fixture(false, true)) {
            if (feature.equals("quick")) Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            else if (feature.equals("orderly")) Configs.Placement.STORE_ORDERLY.setBooleanValue(false);
            else {
                if (feature.equals("external_work")) f.adapter("allowExternalRequest", new Class<?>[0]);
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
            }
            f.content(f.contents(), ItemStack.EMPTY);
            assertEquals(0, f.connection.packets.size(), "Disabling before initial contents cannot start a return click");
            assertFalse((boolean) f.adapter("hasPendingRequest", new Class<?>[0]), "The cancelled opening must not retain a pending restore");
            assertFalse(f.leased.get());
            assertEquals(64, f.player.inventory.getItem(2).getCount());
        }
    }

    @Test void extractionWaitsForAnAlreadySentIdleReturnBeforeOpeningAnotherBox() throws Exception {
        var oldMode = Configs.Placement.QUICK_SHULKER_MODE.getOptionListValue();
        int oldCloseScreen = ModLoadUtils.closeScreen;
        try (var f = new Fixture(false, true)) {
            Configs.Placement.QUICK_SHULKER_MODE.setOptionListValue(QuickShulkerModeType.CLICK_SLOT);
            f.client.level = allocate(BedrockOrderedStorageBoundaryTest.Level.class);
            f.player.containerMenu = f.player.inventoryMenu;
            f.player.inventory.setItem(34, QuickShulkerQueryFixture.box(new ItemStack(Items.SEA_LANTERN, 64)));
            var wanted = MaterialRequest.single(31, Items.SEA_LANTERN, 1, MaterialRequest.Source.PRINT);
            var reservation = (MaterialReservation) f.adapter("request", new Class<?>[]{MaterialRequest.class}, wanted);
            assertEquals(MaterialReservation.State.PENDING, reservation.state());
            assertTrue(f.clicks().isEmpty(), "The idle return is already opening a different box");
            assertFalse((boolean) f.adapter("isOpenHandler", new Class<?>[0]));

            f.player.containerMenu = f.menu;
            f.content(f.contents(), ItemStack.EMPTY);
            assertEquals(ContainerInput.SWAP, f.clicks().getFirst().containerInput());
            var returned = f.contents();
            returned.set(0, new ItemStack(Items.STONE, 64));
            returned.set(56, ItemStack.EMPTY);
            f.menu.initializeContents(1, returned, ItemStack.EMPTY);
            f.content(returned, ItemStack.EMPTY);
            assertTrue(f.player.closed);
            f.adapter("tick", new Class<?>[0]);
            assertEquals(2, f.clicks().size());
            assertEquals(ContainerInput.PICKUP, f.clicks().getLast().containerInput());
            assertEquals(34, f.clicks().getLast().slotNum(), "The queued material request must resume");
        } finally {
            Configs.Placement.QUICK_SHULKER_MODE.setOptionListValue(oldMode);
            ModLoadUtils.closeScreen = oldCloseScreen;
        }
    }

    @Test void rejectingAnUnrelatedReturnMenuPreservesQueuedExtractionAndTrackedItems() throws Exception {
        var oldMode = Configs.Placement.QUICK_SHULKER_MODE.getOptionListValue();
        int oldCloseScreen = ModLoadUtils.closeScreen;
        try (var f = new Fixture(false, true)) {
            Configs.Placement.QUICK_SHULKER_MODE.setOptionListValue(QuickShulkerModeType.CLICK_SLOT);
            f.client.level = allocate(BedrockOrderedStorageBoundaryTest.Level.class);
            f.player.containerMenu = f.player.inventoryMenu;
            var otherBox = QuickShulkerQueryFixture.box(new ItemStack(Items.SEA_LANTERN, 64));
            set(f.player, net.minecraft.world.entity.player.Player.class, "abilities",
                    new net.minecraft.world.entity.player.Abilities());
            f.player.inventory.setItem(34, otherBox);
            var otherBorrowed = new ItemStack(Items.DIRT);
            f.player.inventory.setItem(4, otherBorrowed);
            var storage = get(f.adapter, f.adapter.getClass(), "orderedStorage");
            invoke(storage.getClass(), storage, "newItem",
                    new Class<?>[]{ItemStack.class, ItemStack.class, int.class, int.class, int.class},
                    otherBorrowed, otherBox, 1, 34, 4);
            var entries = (List<?>) get(storage, storage.getClass(), "trackedItems");
            var original = entries.getFirst();
            var wanted = MaterialRequest.single(42, Items.SEA_LANTERN, 1, MaterialRequest.Source.PRINT);
            var reservation = (MaterialReservation) f.adapter("request", new Class<?>[]{MaterialRequest.class}, wanted);
            assertEquals(MaterialReservation.State.PENDING, reservation.state());

            f.player.containerMenu = f.menu;
            f.menu.getSlot(0).set(new ItemStack(Items.GRAVEL));
            f.content(f.contents(), ItemStack.EMPTY);
            f.adapter("tick", new Class<?>[0]);
            assertFalse(f.player.closed);
            assertTrue(f.clicks().isEmpty(), "Do not click the unrelated menu");
            assertEquals(MaterialReservation.State.PENDING,
                    ((MaterialReservation) f.adapter("status", new Class<?>[]{MaterialRequest.class}, wanted)).state(),
                    "Rejecting a return must not consume the waiting extraction");
            assertEquals(2, entries.size());
            assertTrue(((Set<?>) get(original, original.getClass(), "attemptedShulkerMenuSlots")).contains(35));

            f.player.containerMenu = f.player.inventoryMenu;
            f.adapter("tick", new Class<?>[0]);
            assertEquals(1, f.clicks().size());
            assertEquals(34, f.clicks().getFirst().slotNum(), "Resume extraction from the other box");
        } finally {
            Configs.Placement.QUICK_SHULKER_MODE.setOptionListValue(oldMode);
            ModLoadUtils.closeScreen = oldCloseScreen;
        }
    }

    public static final class PickAccess {
        public static boolean canPickToSlot(Inventory inventory, int slot) {
            return QuickShulkerQueryFixture.canPickToSlot(inventory, slot);
        }
    }

    static final class CapturedConnection extends Connection {
        java.util.function.Consumer<Packet<?>> beforeSend;
        @Override public void send(Packet<?> packet) { beforeSend.accept(packet); super.send(packet); }
    }

    static final class Fixture implements AutoCloseable {
        final Object oldClient = get(null, Minecraft.class, "instance");
        final boolean oldQuick = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        final boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        final boolean oldOrderly = Configs.Placement.STORE_ORDERLY.getBooleanValue();
        final String oldSlots;
        final Minecraft client = allocate(Minecraft.class);
        final InventorySwitchGuard switchGuard = new InventorySwitchGuard(client, () -> 0L);
        final Player player = allocate(Player.class);
        final CapturedConnection connection = allocate(CapturedConnection.class);
        final AtomicBoolean leased = new AtomicBoolean();
        final ProductionLoader loader = new ProductionLoader();
        final Object adapter;
        final Object requests;
        final AbstractContainerMenu menu;

        Fixture(boolean clearing) throws Exception { this(clearing, false); }

        Fixture(boolean clearing, boolean restoring) throws Exception { this(clearing, restoring, 35); }

        Fixture(boolean clearing, boolean restoring, int sourceInventorySlot) throws Exception {
            connection.packets = new ArrayList<>(); connection.beforeSend = packet -> { };
            set(player, LocalPlayer.class, "connection", connection);
            var equipment = new PlayerEquipment(player);
            player.inventory = new Inventory(player, equipment);
            set(player, net.minecraft.world.entity.player.Player.class, "inventory", player.inventory);
            set(player, net.minecraft.world.entity.LivingEntity.class, "equipment", equipment);
            set(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
            menu = new ShulkerBoxMenu(7, player.inventory);
            menu.slots.get(0).set(new ItemStack(Items.STONE, 64));
            player.inventory.setItem(sourceInventorySlot, QuickShulkerQueryFixture.box(new ItemStack(Items.STONE, 64)));
            if (clearing) player.inventory.setItem(2, new ItemStack(Items.DIRT, 32));
            player.containerMenu = menu;
            client.player = player;
            client.gameMode = allocate(MultiPlayerGameMode.class);
            set(client, Minecraft.class, "gameDirectory", new java.io.File("."));
            set(null, Minecraft.class, "instance", client);
            oldSlots = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue();
            Configs.Placement.QUICK_SHULKER.setBooleanValue(true);
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            Configs.Placement.STORE_ORDERLY.setBooleanValue(false);
            configuredSlots("3");
            ActionPort port = (ActionPort) Proxy.newProxyInstance(ActionPort.class.getClassLoader(), new Class<?>[]{ActionPort.class}, (proxy, method, args) -> {
                if (method.getName().equals("tryAcquire")) { leased.set(true); return true; }
                if (method.getName().equals("releaseOwner")) { leased.set(false); return null; }
                return method.getReturnType() == boolean.class ? false : null;
            });
            Class<?> adapterType = loader.loadClass(BASE + "integration.quickshulker.QuickShulkerAdapter");
            adapter = adapterType.getConstructor(ActionPort.class).newInstance(port);
            requests = get(adapter, adapterType, "requests");
            Class<?> runtimeType = loader.loadClass(BASE + "runtime.PrinterRuntime");
            Object runtime = allocate(runtimeType);
            set(runtime, runtimeType, "quickShulkerAdapter", adapter);
            set(runtime, runtimeType, "inventorySwitchGuard", switchGuard);
            loader.loadClass(BASE + "runtime.RuntimeAccess").getMethod("install", runtimeType).invoke(null, runtime);
            set(requests, requests.getClass(), "automatedContainerId", 7);
            set(requests, requests.getClass(), "isOpenHandler", true);
            set(requests, requests.getClass(), "shulkerInventoryMenuSlot",
                    sourceInventorySlot < 9 ? 36 + sourceInventorySlot : sourceInventorySlot);
            Object session = get(requests, requests.getClass(), "containerSession");
            set(session, session.getClass(), "ownedId", 7);
            declared(session, "bindOwnedMenu", new Class<?>[]{Minecraft.class}, client);
            var acquire = adapterType.getDeclaredMethod("acquireResources"); acquire.setAccessible(true); acquire.invoke(adapter);
            if (restoring) {
                Configs.Placement.STORE_ORDERLY.setBooleanValue(true);
                set(requests, requests.getClass(), "isOpenHandler", false);
                set(requests, requests.getClass(), "automatedContainerId", -1);
                menu.slots.get(0).set(ItemStack.EMPTY);
                player.inventory.setItem(2, new ItemStack(Items.STONE, 64));
                ItemStack box = QuickShulkerQueryFixture.box();
                player.inventory.setItem(35, box);
                Object storage = get(adapter, adapterType, "orderedStorage");
                invoke(storage.getClass(), storage, "newItem", new Class<?>[]{ItemStack.class, ItemStack.class, int.class, int.class, int.class},
                        new ItemStack(Items.STONE, 64), box, 0, 35, 2);
                var entries = (List<?>) get(storage, storage.getClass(), "trackedItems");
                Object restore = get(storage, storage.getClass(), "restoreSession");
                declared(restore, "schedule", new Class<?>[]{Object.class}, entries.getFirst());
                declared(restore, "beginContainerWait", new Class<?>[]{int.class}, 40);
                declared(session, "begin", new Class<?>[]{ItemStack.class, boolean.class}, box, false);
                adapter("onContainerOpen", new Class<?>[]{int.class, MenuType.class}, 7, MenuType.SHULKER_BOX);
                assertEquals(0, clicks().size());
            } else {
                request("requestItem", new Class<?>[]{Item.class}, Items.STONE);
                request("switchInv", new Class<?>[0]);
                assertEquals(1, clicks().size(), "Fixture must start a real unpredicted transfer");
            }
        }

        List<ServerboundContainerClickPacket> clicks() {
            return connection.packets.stream().filter(ServerboundContainerClickPacket.class::isInstance)
                    .map(ServerboundContainerClickPacket.class::cast).toList();
        }
        ArrayList<ItemStack> contents() {
            return new ArrayList<>(menu.slots.stream().map(slot -> slot.getItem().copy()).toList());
        }
        void slot(int slot, ItemStack stack) throws Exception {
            menu.slots.get(slot).set(stack);
            bridge("onSlotUpdate", new Class<?>[]{int.class, int.class, ItemStack.class}, 7, slot, stack);
        }
        void content(List<ItemStack> contents, ItemStack cursor) throws Exception {
            bridge("onInventoryContent", new Class<?>[]{int.class, List.class, ItemStack.class}, 7, contents, cursor);
        }
        Object bridge(String method, Class<?>[] types, Object... args) throws Exception {
            return invoke(loader.loadClass(BASE + "utils.mods.QuickShulkerBridge"), null, method, types, args);
        }
        Object adapter(String method, Class<?>[] types, Object... args) throws Exception { return invoke(adapter.getClass(), adapter, method, types, args); }
        Object request(String method, Class<?>[] types, Object... args) throws Exception { return invoke(requests.getClass(), requests, method, types, args); }
        @Override public void close() throws Exception {
            configuredSlots(oldSlots);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(oldQuick);
            Configs.Core.WORK_SWITCH.setBooleanValue(oldWork);
            Configs.Placement.STORE_ORDERLY.setBooleanValue(oldOrderly);
            set(null, Minecraft.class, "instance", oldClient);
        }
    }

    private static Object invoke(Class<?> type, Object target, String method, Class<?>[] types, Object... args) throws Exception {
        try {
            var call = type.getMethod(method, types);
            call.setAccessible(true);
            return call.invoke(target, args);
        }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }
    private static Object declared(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        var call = target.getClass().getDeclaredMethod(method, types);
        call.setAccessible(true);
        return call.invoke(target, args);
    }
    private static void configuredSlots(String value) {
        fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.setValueFromString(value);
        fi.dy.masa.litematica.util.InventoryUtils.setPickBlockableSlots(value);
    }

    private static final String BASE = "me.aleksilassila.litematica.printer.";
    /** Isolates static client/runtime holders; only the unavailable Mixin invoker is replaced. */
    private static final class ProductionLoader extends ClassLoader {
        private static final Set<String> EXTRA = Set.of("runtime.RuntimeAccess", "runtime.PrinterRuntime",
                "utils.InventoryUtils", "utils.mods.QuickShulkerBridge", "utils.mods.ShulkerUtils");
        ProductionLoader() { super(ConfirmationAdvanceTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean isolated = name.startsWith(BASE) && (EXTRA.contains(name.substring(BASE.length()))
                    || name.startsWith(BASE + "integration.quickshulker."));
            if (!isolated || name.startsWith(ConfirmationAdvanceTest.class.getName())) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new ClassNode(); new ClassReader(input).accept(node, 0);
                        for (var method : node.methods) for (var instruction : method.instructions) {
                            if (instruction instanceof MethodInsnNode call && call.owner.endsWith("/InventoryUtilsAccessor")
                                    && call.name.equals("canPickToSlot")) {
                                call.owner = Type.getInternalName(PickAccess.class); call.itf = false;
                            }
                        }
                        var writer = new ClassWriter(0); node.accept(writer);
                        byte[] bytes = writer.toByteArray(); type = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
}
//#endif
