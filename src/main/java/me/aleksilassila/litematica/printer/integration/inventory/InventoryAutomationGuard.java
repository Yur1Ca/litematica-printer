package me.aleksilassila.litematica.printer.integration.inventory;

import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.Minecraft;

/** Shared read of the existing inventory-operation boundaries for optional auto-refill. */
public final class InventoryAutomationGuard {
    private InventoryAutomationGuard() { }

    public static boolean shouldPauseAutoRefill() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return false;
        // IPN already pauses refill with a visible screen. Keep its GUI swipe/save tail running.
        //#if MC > 260100
        //$$ if (client.gui.screen() != null) return false;
        //#else
        if (client.screen != null) return false;
        //#endif
        // IPN searches inventoryMenu slot numbers but clicks containerMenu. A hidden screen
        // does not make a foreign menu safe for that operation.
        if (client.player.containerMenu != client.player.inventoryMenu) return true;
        var runtime = RuntimeAccess.currentOrNull();
        if (runtime == null) return false;
        // Leases survive feature disable until their in-flight cleanup is confirmed.
        return runtime.actionBroker().isResourceHeld(ResourceLease.INVENTORY)
                || runtime.actionBroker().isResourceHeld(ResourceLease.CONTAINER)
                || runtime.actionBroker().isResourceHeld(ResourceLease.MAIN_HAND)
                || runtime.inventorySwitchGuard().isWaiting();
    }

    /** Called while paused: hand use alone defers native refill/sorting without invalidating its baseline. */
    public static boolean shouldResetAfterPause() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return false;
        if (client.player.containerMenu != client.player.inventoryMenu) return true;
        var runtime = RuntimeAccess.currentOrNull();
        return runtime != null && (runtime.actionBroker().isResourceHeld(ResourceLease.INVENTORY)
                || runtime.actionBroker().isResourceHeld(ResourceLease.CONTAINER)
                || runtime.inventorySwitchGuard().isWaiting());
    }
}
