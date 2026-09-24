package me.aleksilassila.litematica.printer.utils.mods;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.world.item.ItemStack;

/** Routes Chest Tracker mixin callbacks into the current runtime. */
public final class ChestTrackerBridge {
    private ChestTrackerBridge() {
    }

    public static void onContainerContent(int containerId) {
        RuntimeAccess.get().chestTrackerAdapter().onContainerContent(containerId);
    }

    public static void onContainerOpen(int containerId) {
        RuntimeAccess.get().chestTrackerAdapter().onContainerOpen(containerId);
    }

    public static boolean shouldSuppressContainerScreen() {
        return RuntimeAccess.get().chestTrackerAdapter().shouldSuppressContainerScreen();
    }

    public static boolean takeFromScreen(ItemStack stack) {
        return RuntimeAccess.get().chestTrackerAdapter().requestFromScreen(stack);
    }

    public static int addSelectionToCache() {
        return RuntimeAccess.get().chestTrackerAdapter().addSelectionToCache();
    }

    public static int clearSelectionCache() {
        return RuntimeAccess.get().chestTrackerAdapter().clearSelectionCache();
    }
}
