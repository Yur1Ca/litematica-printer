package me.aleksilassila.litematica.printer.utils.mods;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.world.item.ItemStack;

/** Stable no-op boundary for the optional Chest Tracker integration. */
public final class ChestTrackerBridge {
    private ChestTrackerBridge() {
    }

    public static boolean isLoaded() {
        return ModLoadUtils.isChestTrackerLoaded();
    }

    public static void tick() {
        RuntimeAccess.get().chestTrackerAdapter().tick();
    }

    public static void onContainerContent() {
        RuntimeAccess.get().chestTrackerAdapter().onContainerContent();
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

    public static int selectedCacheSize() {
        return RuntimeAccess.get().chestTrackerAdapter().selectedCacheSize();
    }

    public static void reset() {
        RuntimeAccess.get().chestTrackerAdapter().reset();
    }
}
