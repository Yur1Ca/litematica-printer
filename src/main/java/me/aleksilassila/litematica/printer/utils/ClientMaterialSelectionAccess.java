package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Stateless adapter: resolve runtime services at each original decision point, never retain a session. */
final class ClientMaterialSelectionAccess implements MaterialSelectionAccess {
    @Override
    public boolean isWaitingForSwitch() {
        return RuntimeAccess.get().inventorySwitchGuard().isWaiting();
    }

    @Override
    public boolean setPickedItemToHand(int slot, ItemStack stack, Minecraft client) {
        return InventoryUtils.setPickedItemToHand(slot, stack, client);
    }

    @Override
    public boolean setPickedItemToHand(ItemStack stack, Minecraft client) {
        return InventoryUtils.setPickedItemToHand(stack, client);
    }

    @Override
    public boolean markSwitchIfNeeded(Item item) {
        return RuntimeAccess.get().inventorySwitchGuard().markSwitchIfNeeded(item);
    }

    @Override
    public void requestItems(Item[] items, MaterialRequest.Source source) {
        QuickShulkerBridge.requestItems(items, source);
    }

    @Override
    public void requestEmptyShulker(Item item) {
        RuntimeAccess.get().materialRequests().requestEmptyShulker(item);
    }
}
