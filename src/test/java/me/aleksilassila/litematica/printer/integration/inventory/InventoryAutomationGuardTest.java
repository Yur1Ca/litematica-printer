//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.inventory;

import java.util.EnumSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAutomationGuardTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(net.minecraft.data.registries.VanillaRegistries.createLookup()).forEach(patch -> patch.apply());
    }

    // Before the integration exists, IPN ticks are unguarded. This preserves that RED baseline.
    private static boolean paused() throws Exception {
        try {
            return (boolean) Class.forName("me.aleksilassila.litematica.printer.integration.inventory.InventoryAutomationGuard")
                    .getMethod("shouldPauseAutoRefill").invoke(null);
        } catch (ClassNotFoundException absent) { return false; }
    }

    @Test void hiddenNonPlayerMenuPausesEvenBeforeRuntimeInstallation() throws Exception {
        try (var f = new Fixture()) {
            set(null, RuntimeAccess.class, "current", null);
            f.player.containerMenu = new ShulkerBoxMenu(7, f.player.inventory);
            assertTrue(paused());
        }
    }

    @Test void visibleContainerScreenKeepsNativeGuiTickDuringDrain() throws Exception {
        try (var f = new Fixture()) {
            f.player.containerMenu = new ShulkerBoxMenu(7, f.player.inventory);
            f.broker.tryAcquire("quick_shulker", EnumSet.of(ResourceLease.CONTAINER), 0);
            f.client.screen = allocate(net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen.class);
            assertFalse(paused(), "IPN's visible-screen branch already pauses refill but must process swipe/save");
            f.client.screen = null;
            assertTrue(paused(), "Hiding that same menu must restore protection immediately");
        }
    }
    @Test void eachInventoryResourcePausesUntilItsExistingLeaseIsReleased() throws Exception {
        try (var f = new Fixture()) {
            for (var resource : new ResourceLease[]{ResourceLease.INVENTORY, ResourceLease.CONTAINER, ResourceLease.MAIN_HAND}) {
                assertTrue(f.broker.tryAcquire("provider", EnumSet.of(resource), 0));
                assertTrue(paused(), resource.name());
                f.broker.releaseOwner("provider");
                assertFalse(paused());
            }
        }
    }

    @Test void disablingFeaturesDoesNotReleaseTheCancellationDrainBarrier() throws Exception {
        boolean work = Configs.Core.WORK_SWITCH.getBooleanValue();
        boolean quick = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        try (var f = new Fixture()) {
            f.broker.tryAcquire("quick_shulker", EnumSet.of(ResourceLease.CONTAINER), 0);
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            assertTrue(paused(), "The replacement player menu still belongs to the in-flight drain");
            f.broker.releaseOwner("quick_shulker");
            assertFalse(paused());
        } finally {
            Configs.Core.WORK_SWITCH.setBooleanValue(work);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(quick);
        }
    }

    @Test void predictedHandSwitchWaitsForTheExistingConfirmationWindow() throws Exception {
        try (var f = new Fixture()) {
            f.player.inventory.setItem(0, new ItemStack(Items.STONE));
            f.switchGuard.markSwitchIfNeeded(Items.STONE);
            assertTrue(paused());
            f.tick++;
            assertFalse(paused());
        }
    }

    @Test void idleManualActionsAndUnrelatedLookLeaseAreNotBlocked() throws Exception {
        try (var f = new Fixture()) {
            assertFalse(paused());
            f.broker.tryAcquire("look", EnumSet.of(ResourceLease.LOOK), 0);
            assertFalse(paused());
            set(null, RuntimeAccess.class, "current", null);
            assertFalse(paused());
        }
    }

    @Test void disconnectAndNewRuntimeDoNotReuseAnOldBusyResult() throws Exception {
        try (var f = new Fixture()) {
            f.broker.tryAcquire("quick_shulker", EnumSet.of(ResourceLease.CONTAINER), 0);
            assertTrue(paused());
            f.client.player = null;
            assertFalse(paused());
            set(null, RuntimeAccess.class, "current", null);
            f.client.player = f.player;
            assertFalse(paused());
        }
    }

    static class Player extends LocalPlayer {
        Inventory inventory;
        Player() { super(null, null, null, null, null, null, false, null); }
        @Override public Inventory getInventory() { return inventory; }
        @Override public ItemStack getMainHandItem() { return inventory.getItem(0); }
    }

    static class Fixture implements AutoCloseable {
        final Object oldClient = get(null, Minecraft.class, "instance");
        final Object oldRuntime = get(null, RuntimeAccess.class, "current");
        final Minecraft client = allocate(Minecraft.class);
        final Player player = allocate(Player.class);
        final PrinterRuntime runtime = allocate(PrinterRuntime.class);
        final ActionBroker broker = new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> null, new ActionManager());
        long tick;
        final InventorySwitchGuard switchGuard = new InventorySwitchGuard(client, () -> tick);
        Fixture() throws Exception {
            player.inventory = new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
            set(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
            player.containerMenu = player.inventoryMenu;
            client.player = player;
            set(null, Minecraft.class, "instance", client);
            set(runtime, PrinterRuntime.class, "actionBroker", broker);
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard", switchGuard);
            RuntimeAccess.install(runtime);
        }
        public void close() throws Exception {
            set(null, RuntimeAccess.class, "current", oldRuntime);
            set(null, Minecraft.class, "instance", oldClient);
        }
    }

    static Object get(Object object, Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    static void set(Object object, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var unsafe = Class.forName("sun.misc.Unsafe");
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(get(null, unsafe, "theUnsafe"), type));
    }
}
//#endif
