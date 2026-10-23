//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.inventory;

import java.util.EnumSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.mixin.printer.ipn.MixinAutoRefillHandler;
import me.aleksilassila.litematica.printer.mixin.printer.ipn.MixinLockedSlotKeeper;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAutomationPauseTest {
    @BeforeAll static void bootstrap() { InventoryAutomationGuardTest.bootstrap(); }

    @ParameterizedTest
    @CsvSource({"refill,false,false", "refill,false,true", "refill,true,false", "refill,true,true",
            "keeper,false,false", "keeper,false,true", "keeper,true,false", "keeper,true,true"})
    void handUseDefersNativeWorkWithoutDiscardingIt(String kind, boolean originalMarker, boolean work) throws Exception {
        boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        try (var f = new InventoryAutomationGuardTest.Fixture(); var nativeTick = new NativePause(kind, originalMarker)) {
            Configs.Core.WORK_SWITCH.setBooleanValue(work);
            assertTrue(f.broker.tryAcquire("placement", EnumSet.of(ResourceLease.MAIN_HAND, ResourceLease.LOOK,
                    ResourceLease.INTERACTION), 0));
            for (int tick = 0; tick < 600; tick++) {
                assertTrue(nativeTick.invoke(), "Neither refill nor keeper may click before hand use finishes");
                assertEquals(originalMarker, nativeTick.marker(), "Hand use must preserve native pending work and prior reset requests");
            }
            f.broker.releaseOwner("placement");
            assertFalse(nativeTick.invoke(), "Native refill/sorting resumes even with the printer disabled");
        } finally { Configs.Core.WORK_SWITCH.setBooleanValue(oldWork); }
    }

    @ParameterizedTest
    @CsvSource({"refill,inventory", "refill,container", "refill,hidden", "refill,switch",
            "keeper,inventory", "keeper,container", "keeper,hidden", "keeper,switch"})
    void movingInventoryStillDiscardsOldPlansBeforeAnyLaterHandOnlyPause(String kind, String boundary) throws Exception {
        try (var f = new InventoryAutomationGuardTest.Fixture(); var nativeTick = new NativePause(kind, false)) {
            switch (boundary) {
                case "inventory" -> assertTrue(f.broker.tryAcquire("transfer", EnumSet.of(ResourceLease.INVENTORY), 0));
                case "container" -> assertTrue(f.broker.tryAcquire("transfer", EnumSet.of(ResourceLease.CONTAINER), 0));
                case "hidden" -> f.player.containerMenu = new ShulkerBoxMenu(7, f.player.inventory);
                case "switch" -> {
                    f.player.inventory.setItem(0, new ItemStack(Items.STONE));
                    f.switchGuard.markSwitchIfNeeded(Items.STONE);
                }
                default -> fail(boundary);
            }
            assertTrue(nativeTick.invoke());
            assertTrue(nativeTick.marker(), "Old slot/hand plans cannot survive an inventory move");
            f.broker.releaseOwner("transfer");
            f.player.containerMenu = f.player.inventoryMenu;
            f.tick++;
            assertTrue(f.broker.tryAcquire("placement", EnumSet.of(ResourceLease.MAIN_HAND), 0));
            assertTrue(nativeTick.invoke());
            assertTrue(nativeTick.marker(), "A subsequent hand-only pause cannot erase a required resnapshot");
            f.broker.releaseOwner("placement");
            assertFalse(nativeTick.invoke());
            assertTrue(nativeTick.marker(), "The native resume path consumes its own marker");
        }
    }

    private static final class Refill extends MixinAutoRefillHandler {
        boolean marker;
        @Override public void setScreenOpening(boolean opening) { marker = opening; }
    }

    private static final class NativePause implements AutoCloseable {
        final Object target;
        final java.lang.reflect.Method callback;
        final java.lang.reflect.Field keeperMarker;
        final boolean oldKeeperMarker;
        NativePause(String kind, boolean marker) throws Exception {
            if (kind.equals("refill")) {
                var refill = new Refill();
                refill.marker = marker;
                target = refill;
                callback = MixinAutoRefillHandler.class.getDeclaredMethod("litematica_printer$pauseAutoRefill", CallbackInfo.class);
                keeperMarker = null;
                oldKeeperMarker = false;
            } else {
                target = new MixinLockedSlotKeeper() { };
                callback = MixinLockedSlotKeeper.class.getDeclaredMethod("litematica_printer$pauseLockedSlotKeeper", CallbackInfo.class);
                keeperMarker = MixinLockedSlotKeeper.class.getDeclaredField("screenOpening");
                keeperMarker.setAccessible(true);
                oldKeeperMarker = keeperMarker.getBoolean(null);
                keeperMarker.setBoolean(null, marker);
            }
            callback.setAccessible(true);
        }
        boolean invoke() throws Exception {
            var info = new CallbackInfo("onTickInGame", true);
            callback.invoke(target, info);
            return info.isCancelled();
        }
        boolean marker() throws Exception {
            return keeperMarker == null ? ((Refill) target).marker : keeperMarker.getBoolean(null);
        }
        @Override public void close() throws Exception {
            if (keeperMarker != null) keeperMarker.setBoolean(null, oldKeeperMarker);
        }
    }
}
//#endif
