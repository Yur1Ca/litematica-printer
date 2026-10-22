//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.junit.jupiter.api.Assertions.*;

class ManualPickBlockLifecycleTest {
    @BeforeAll static void bootstrap() throws Exception { CancelledSwapLifecycleTest.bootstrap(); }

    private static class Fixture extends PickBlockInventoryExclusionTest.Fixture {
        private final Object previousInventoryClient;

        Fixture() throws Exception {
            CancelledSwapLifecycleTest.set(player, net.minecraft.world.entity.LivingEntity.class,
                    "equipment", new net.minecraft.world.entity.EntityEquipment());
            previousInventoryClient = CancelledSwapLifecycleTest.get(null, InventoryUtils.class, "client");
            setInventoryClient(client);
        }

        @SuppressWarnings("removal")
        private static void setInventoryClient(Object client) throws Exception {
            var access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            access.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) access.get(null);
            var field = InventoryUtils.class.getDeclaredField("client");
            unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), client);
        }

        @Override public void close() throws Exception {
            try { setInventoryClient(previousInventoryClient); } finally { super.close(); }
        }
    }

    private static CallbackInfoReturnable<InteractionResult> easyPlace(ItemStack stack) throws Exception {
        var type = Class.forName("me.aleksilassila.litematica.printer.mixin.printer.litematica.MixinEasyPlaceUtils");
        var method = type.getDeclaredMethod("litematicaPrinter$restock", CallbackInfoReturnable.class, ItemStack.class);
        method.setAccessible(true);
        var callback = new CallbackInfoReturnable<InteractionResult>("handleEasyPlace", true);
        method.invoke(null, callback, stack);
        return callback;
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void rewrittenEasyPlaceWaitsForMaterialsRegardlessOfPrinterSwitches(boolean work, boolean print) throws Exception {
        boolean oldPrint = Configs.Core.PRINT.getBooleanValue();
        try (var f = new Fixture()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(work);
            Configs.Core.PRINT.setBooleanValue(print);
            var callback = easyPlace(new ItemStack(Items.STONE));
            assertTrue(callback.isCancelled(), "Do not run native picking or placement while acquisition is pending");
            assertSame(InteractionResult.FAIL, callback.getReturnValue());
            assertEquals(List.of(Items.STONE), f.api.sent);
            long token = f.coordinator.activeToken();
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            Configs.Core.PRINT.setBooleanValue(false);
            for (String owner : List.of("print", "fill", "fluid", "mine", "cover", "bedrock")) f.coordinator.cancelOwner(owner);
            for (int tick = 0; tick < 60; tick++) {
                f.coordinator.tick();
                assertTrue(easyPlace(new ItemStack(Items.STONE)).isCancelled());
                assertEquals(token, f.coordinator.activeToken(), "Feature cancellation must not replace a manual token");
                assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            }
            assertEquals(List.of(Items.STONE), f.api.sent, "Repeated Easy Place must not duplicate the external request");
            f.player.inventory.setItem(2, new ItemStack(Items.STONE, 16));
            f.api.pending = ItemStack.EMPTY;
            f.coordinator.tick();
            assertFalse(easyPlace(new ItemStack(Items.STONE)).isCancelled(), "Arrival returns control to native Easy Place");
            assertFalse(f.coordinator.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
        } finally {
            Configs.Core.PRINT.setBooleanValue(oldPrint);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"easy_place", "middle_click"})
    void manualEntryCannotBypassAnOccupiedContainerWhilePrinterIsOff(String entry) throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            f.startSync("RESYNC");
            for (int tick = 0; tick < 300; tick++) {
                boolean consumed = entry.equals("easy_place")
                        ? easyPlace(new ItemStack(Items.STONE)).isCancelled()
                        : QuickShulkerBridge.handlePickBlock(f.player, Items.STONE);
                assertTrue(consumed);
                f.coordinator.tick();
            }
            assertTrue(f.api.sent.isEmpty());
            f.assertSyncStillOwnsInventory();
        }
    }

    @Test void changingSessionDiscardsTheOldManualRequestBeforeANewOneStarts() throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            assertTrue(easyPlace(new ItemStack(Items.STONE)).isCancelled());
            long previous = f.coordinator.activeToken();
            f.resetScope("client_scope_changed");
            assertFalse(f.coordinator.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(easyPlace(new ItemStack(Items.DIRT)).isCancelled());
            assertNotEquals(previous, f.coordinator.activeToken());
            assertEquals(List.of(Items.STONE, Items.DIRT), f.api.sent);
        }
    }
}
//#endif
