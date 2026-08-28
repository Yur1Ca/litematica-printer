package me.aleksilassila.litematica.printer.utils.mods;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Stable no-op boundary for the optional Chest Tracker integration. */
public final class ChestTrackerBridge {
    private ChestTrackerBridge() {
    }

    public static boolean isLoaded() {
        return ModLoadUtils.isChestTrackerLoaded();
    }

    /** Standalone Chest Tracker pick-block path used when Printer's work switch is off. */
    public static boolean handlePickBlock(LocalPlayer player, Item item) {
        Minecraft client = Minecraft.getInstance();
        if (Configs.Core.WORK_SWITCH.getBooleanValue()
                || client.gameMode == null
                || client.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            return false;
        }
        return RuntimeAccess.get().chestTrackerAdapter().handlePickBlock(player, item);
    }

    public static void tick() {
        RuntimeAccess.get().chestTrackerAdapter().tick();
    }

    public static void onContainerContent() {
        if (Minecraft.getInstance().player != null) {
            RuntimeAccess.get().chestTrackerAdapter().onContainerContent(
                    Minecraft.getInstance().player.containerMenu.containerId);
        }
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

    public static int selectedCacheSize() {
        return RuntimeAccess.get().chestTrackerAdapter().selectedCacheSize();
    }

    public static void reset() {
        RuntimeAccess.get().chestTrackerAdapter().reset();
    }
}
