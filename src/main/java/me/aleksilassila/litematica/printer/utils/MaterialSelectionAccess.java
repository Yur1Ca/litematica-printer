package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Execution capabilities needed by material selection, without exposing inventory protocol state. */
interface MaterialSelectionAccess {
    boolean isWaitingForSwitch();

    boolean setPickedItemToHand(int slot, ItemStack stack, Minecraft client);

    boolean setPickedItemToHand(ItemStack stack, Minecraft client);

    boolean markSwitchIfNeeded(Item item);

    void requestItems(Item[] items, MaterialRequest.Source source);

    void requestEmptyShulker(Item item);
}
