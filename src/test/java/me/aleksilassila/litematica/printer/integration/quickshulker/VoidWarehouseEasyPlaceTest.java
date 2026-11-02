//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.PlayerInventoryProvider;
import me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseRestockPort;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import neko.voidwarehouse.restock.PrinterTakeResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static me.aleksilassila.litematica.printer.integration.quickshulker.QuickShulkerPacketContractTest.allocate;
import static org.junit.jupiter.api.Assertions.*;

/** Callback-level coverage of the coordinator, warehouse adapter and selection. Does not exercise upstream right-click dispatch or apply Mixins. */
class VoidWarehouseEasyPlaceTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static final class Network implements VoidWarehouseRestockPort {
        final List<List<String>> requests = new ArrayList<>();
        int status = PrinterTakeResultPayload.NONE;
        boolean supported = true;
        @Override public boolean isSupported() { return supported; }
        @Override public int requestTake(List<String> candidates) {
            requests.add(List.copyOf(candidates));
            return requests.size();
        }
        @Override public int statusOf(int nonce) { return status; }
        @Override public void forget() { status = PrinterTakeResultPayload.NONE; }
    }

    static final class Fixture implements AutoCloseable {
        final boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        final boolean oldPrint = Configs.Core.PRINT.getBooleanValue();
        final boolean oldQuick = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        final boolean oldWarehouse = Configs.Special.VOID_WAREHOUSE_RESTOCK.getBooleanValue();
        final MaterialSelectionFixture game = new MaterialSelectionFixture();
        final ActionBroker broker = new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, null);
        final Network network = new Network();
        final Object returns;
        final MaterialRequestCoordinator materials;
        final ClassLoader loader = new ManualLoader(game.selector.getClassLoader());

        Fixture() throws Exception {
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            Configs.Core.PRINT.setBooleanValue(false);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(true);
            game.client.level = allocate(ClientLevel.class);
            var clock = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
            set(game.client.level, Level.class, "levelData", clock);
            set(game.client.level, ClientLevel.class, "clientLevelData", clock);
            Class<?> returnType = loader.loadClass("me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseReturnController");
            returns = returnType.getConstructor(Minecraft.class, BooleanSupplier.class, BooleanSupplier.class, Supplier.class)
                    .newInstance(game.client, (BooleanSupplier) () -> false, (BooleanSupplier) () -> false,
                            (Supplier<List<Item>>) List::of);
            Class<?> type = loader.loadClass("me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseRestockAdapter");
            var warehouse = (InventoryProvider) type.getConstructor(ActionPort.class, VoidWarehouseRestockPort.class)
                    .newInstance(broker, network);
            type.getMethod("attachReturnController", returnType).invoke(warehouse, returns);
            materials = new MaterialRequestCoordinator(List.of(new PlayerInventoryProvider(game.client), warehouse));
            set(game.runtime, PrinterRuntime.class, "materialRequests", materials);
            set(game.runtime, PrinterRuntime.class, "actionBroker", broker);
        }

        boolean easyPlace(Item item) throws Exception {
            var type = loader.loadClass("me.aleksilassila.litematica.printer.mixin.printer.litematica.MixinEasyPlaceUtils");
            var method = type.getDeclaredMethod("litematicaPrinter$restock", CallbackInfoReturnable.class, ItemStack.class);
            method.setAccessible(true);
            var result = new CallbackInfoReturnable<InteractionResult>("handleEasyPlace", true);
            method.invoke(null, result, new ItemStack(item));
            if (result.isCancelled()) assertSame(InteractionResult.FAIL, result.getReturnValue());
            return result.isCancelled();
        }

        boolean middleClick(Item item) throws Exception {
            return (boolean) loader.loadClass("me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge")
                    .getMethod("handlePickBlock", LocalPlayer.class, Item.class).invoke(null, game.player, item);
        }

        void tick(int count) { for (int tick = 0; tick < count; tick++) materials.tick(); }

        int returnCount() throws Exception { return (int) returns.getClass().getMethod("pendingCount").invoke(returns); }

        @Override public void close() throws Exception {
            try { game.close(); } finally {
                Configs.Core.WORK_SWITCH.setBooleanValue(oldWork);
                Configs.Core.PRINT.setBooleanValue(oldPrint);
                Configs.Placement.QUICK_SHULKER.setBooleanValue(oldQuick);
                Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(oldWarehouse);
            }
        }
    }

    private static final class ManualLoader extends ClassLoader {
        ManualLoader(ClassLoader parent) { super(parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean warehouse = name.startsWith("me.aleksilassila.litematica.printer.integration.voidwarehouse.")
                    && !name.equals(VoidWarehouseRestockPort.class.getName());
            if (!warehouse && !List.of("QuickShulkerBridge", "MixinEasyPlaceUtils")
                    .contains(name.substring(name.lastIndexOf('.') + 1))) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        byte[] bytes = input.readAllBytes();
                        type = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) { throw new ClassNotFoundException(name, e); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }

    @ParameterizedTest @CsvSource({"false,3", "true,3", "false,10", "true,10"})
    void manualDeliveryWaitsForReceiptAndInventoryWithPrinterAndQuickShulkerOff(boolean receiptFirst, int slot) throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.easyPlace(Items.STONE));
            long token = f.materials.activeToken();
            if (receiptFirst) f.network.status = PrinterTakeResultPayload.OK;
            else f.game.player.inventory.setItem(slot, new ItemStack(Items.STONE, 32));
            for (int tick = 0; tick < 60; tick++) {
                f.materials.tick();
                assertTrue(f.easyPlace(Items.STONE));
                assertTrue(f.middleClick(Items.STONE));
                assertEquals(token, f.materials.activeToken());
                assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            }
            assertEquals(List.of(List.of("minecraft:stone")), f.network.requests);
            assertTrue(f.game.traffic.isEmpty(), "A receipt alone or an unconfirmed arrival must not switch the hand");
            f.network.status = PrinterTakeResultPayload.OK;
            f.game.player.inventory.setItem(slot, new ItemStack(Items.STONE, 32));
            f.tick(1);
            assertFalse(f.materials.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(slot == 3 ? List.of("select:3") : List.of("select:2", "swap:10:2"), f.game.traffic);
            assertFalse(f.easyPlace(Items.STONE), "Confirmed arrival hands control back to native Easy Place");
            assertEquals(0, f.returnCount(), "Manual materials must not be reclaimed as printing leftovers");
        }
    }

    @Test void turningPrintingOffDoesNotCancelManualAcquisition() throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            Configs.Core.PRINT.setBooleanValue(true);
            assertTrue(f.easyPlace(Items.STONE));
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            Configs.Core.PRINT.setBooleanValue(false);
            for (String owner : List.of("print", "fill", "fluid", "mine", "cover", "bedrock")) {
                f.materials.cancelOwner(owner);
            }
            f.tick(60);
            assertTrue(f.materials.isBusy());
            f.network.status = PrinterTakeResultPayload.OK;
            f.game.player.inventory.setItem(3, new ItemStack(Items.STONE, 16));
            f.tick(1);
            assertEquals(List.of("select:3"), f.game.traffic);
            assertEquals(1, f.network.requests.size());
            assertEquals(0, f.returnCount());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "unrelated", "last_target_consumed"})
    void redstoneTorchRestockDoesNotRequireAnItemAlreadyInHand(String hand) throws Exception {
        try (var f = new Fixture()) {
            if (hand.equals("unrelated")) f.game.player.inventory.setItem(0, new ItemStack(Items.SMOOTH_STONE));
            if (hand.equals("last_target_consumed")) {
                f.game.player.inventory.setItem(0, new ItemStack(Items.REDSTONE_TORCH));
                assertFalse(f.easyPlace(Items.REDSTONE_TORCH));
                assertTrue(f.network.requests.isEmpty());
                f.game.player.getMainHandItem().shrink(1);
            }
            assertTrue(f.easyPlace(Items.REDSTONE_TORCH));
            f.tick(60);
            assertEquals(List.of(List.of("minecraft:redstone_torch")), f.network.requests);
            f.network.status = PrinterTakeResultPayload.OK;
            f.game.player.inventory.setItem(3, new ItemStack(Items.REDSTONE_TORCH, 10));
            f.tick(1);
            assertFalse(f.materials.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertEquals(List.of("select:3"), f.game.traffic);
            assertEquals(0, f.returnCount());
            assertFalse(f.easyPlace(Items.REDSTONE_TORCH));
        }
    }

    @Test void waitsForAnotherInventoryOwnerWithoutSpendingTheResponseTimeout() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.broker.tryAcquire("container_sync", EnumSet.of(ResourceLease.INVENTORY,
                    ResourceLease.CONTAINER, ResourceLease.MAIN_HAND), 0L));
            assertTrue(f.easyPlace(Items.STONE));
            f.tick(300);
            assertTrue(f.materials.isBusy());
            assertTrue(f.network.requests.isEmpty());
            f.broker.releaseOwner("container_sync");
            f.tick(1);
            assertEquals(List.of(List.of("minecraft:stone")), f.network.requests);
            assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"disabled", "unsupported", "already_held"})
    void leavesNativeSelectionAvailableWhenWarehouseIsNotNeededOrAvailable(String reason) throws Exception {
        try (var f = new Fixture()) {
            if (reason.equals("disabled")) Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(false);
            if (reason.equals("unsupported")) f.network.supported = false;
            if (reason.equals("already_held")) f.game.player.inventory.setItem(3, new ItemStack(Items.STONE));
            assertFalse(f.easyPlace(Items.STONE));
            assertTrue(f.network.requests.isEmpty());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"disabled", "disconnect", "client_scope_changed", "timeout"})
    void cancelledRequestDoesNotSelectLateMaterialOrLeaveAnInventoryLock(String reason) throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.easyPlace(Items.STONE));
            if (reason.equals("disabled")) {
                Configs.Special.VOID_WAREHOUSE_RESTOCK.setBooleanValue(false);
                f.tick(1);
            } else if (reason.equals("timeout")) {
                f.tick(101);
            } else {
                f.materials.onEpochChanged(new RuntimeEvent.EpochChanged(RuntimeEpoch.INITIAL,
                        RuntimeEpoch.INITIAL.next(), reason));
            }
            assertFalse(f.materials.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            f.network.status = PrinterTakeResultPayload.OK;
            f.game.player.inventory.setItem(3, new ItemStack(Items.STONE, 32));
            f.tick(200);
            assertTrue(f.game.traffic.isEmpty());
            assertEquals(1, f.network.requests.size());
            assertEquals(0, f.returnCount());
            assertFalse(f.easyPlace(Items.STONE));
        }
    }
}
//#endif
