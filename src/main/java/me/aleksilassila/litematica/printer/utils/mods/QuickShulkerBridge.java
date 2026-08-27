package me.aleksilassila.litematica.printer.utils.mods;

import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Compatibility boundary for the historical quick-shulker implementation.
 *
 * <p>The implementation remains unchanged for now. New code should use this
 * bridge instead of depending on the legacy package directly.</p>
 */
public final class QuickShulkerBridge {
    private QuickShulkerBridge() {
    }

    public static void requestItem(Item item) {
        if (item != null) {
            requestItem(item, MaterialRequest.Source.OTHER);
        }
    }

    public static MaterialReservation requestItem(Item item, MaterialRequest.Source source) {
        if (item == null) {
            return new MaterialReservation(0L, MaterialReservation.State.UNAVAILABLE);
        }
        return RuntimeAccess.get().materialRequests().request(item, source);
    }

    public static MaterialReservation requestItems(Item[] items, MaterialRequest.Source source) {
        if (items == null || items.length == 0) {
            return new MaterialReservation(0L, MaterialReservation.State.UNAVAILABLE);
        }
        return RuntimeAccess.get().materialRequests().request(items, source);
    }

    /** Handles the optional inventory fallback for both vanilla and Litematica pick-block hooks. */
    public static boolean handlePickBlock(LocalPlayer player, Item item) {
        if (player == null || item == null || item == Items.AIR
                || !Configs.Core.WORK_SWITCH.getBooleanValue()
                || player.getAbilities().instabuild
                || player.isSpectator()
                || (!Configs.Placement.QUICK_SHULKER.getBooleanValue()
                    && !TakeItOutUtils.isAutoTakeoutEnabled())
                || player.inventoryMenu.slots.stream().anyMatch(slot -> slot.getItem().is(item))
                // A print/CT request already owns the coordinator. Never turn
                // that unrelated PENDING result into a middle-click intercept.
                || RuntimeAccess.get().materialRequests().isBusy()) {
            return false;
        }
        MaterialReservation reservation = requestItem(item, MaterialRequest.Source.PICK_BLOCK);
        if (reservation.state() == MaterialReservation.State.UNAVAILABLE) {
            return false;
        }
        switchItem();
        return true;
    }

    public static boolean handlePickBlock(LocalPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()
                || ItemStack.isSameItemSameComponents(player.getMainHandItem(), stack)) {
            return false;
        }
        return handlePickBlock(player, stack.getItem());
    }

    public static boolean switchItem() {
        return RuntimeAccess.get().quickShulkerAdapter().switchItem();
    }

    public static boolean hasPendingRequest() {
        return RuntimeAccess.get().quickShulkerAdapter().hasPendingRequest();
    }

    public static boolean isOpenHandler() {
        return RuntimeAccess.get().quickShulkerAdapter().isOpenHandler();
    }

    public static boolean shouldPause() {
        return RuntimeAccess.get().quickShulkerAdapter().shouldPause();
    }

    public static boolean shouldSuppressContainerScreen() {
        return RuntimeAccess.get().quickShulkerAdapter().shouldSuppressContainerScreen();
    }

    public static void onTick() {
        RuntimeAccess.get().quickShulkerAdapter().tick();
    }

    public static void onInventoryContent() {
        RuntimeAccess.get().quickShulkerAdapter().onInventoryContent();
    }

    public static void onMainHandUse(LocalPlayer player) {
        RuntimeAccess.get().quickShulkerAdapter().onMainHandUse(player);
    }

    public static void resetRuntime() {
        RuntimeAccess.get().quickShulkerAdapter().reset();
    }
}
