package me.aleksilassila.litematica.printer.integration.quickshulker;

import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mutable tracking record for one stack borrowed from a shulker box. */
final class OrderedStorageEntry {
    final ItemStack itemStack;
    final ItemStack shulkerStack;
    final List<ItemStack> shulkerSnapshot;
    final Set<Integer> attemptedShulkerMenuSlots = new HashSet<>();
    final int sourceContainerSlot;
    final int originalBoxSlot;
    OrderedStorageOrigin origin;
    int shulkerInventoryMenuSlot;
    int playerInventorySlot;
    long lastUseTick;

    OrderedStorageEntry(
            ItemStack itemStack,
            ItemStack shulkerStack,
            int sourceContainerSlot,
            int shulkerInventoryMenuSlot,
            int playerInventorySlot,
            long currentTick
    ) {
        this.itemStack = itemStack.copy();
        this.itemStack.setCount(1);
        this.shulkerStack = shulkerStack == null ? ItemStack.EMPTY : shulkerStack.copy();
        if (!this.shulkerStack.isEmpty()) {
            this.shulkerStack.setCount(1);
        }
        this.shulkerSnapshot = OrderedStorageStacks.snapshotShulker(shulkerStack);
        this.sourceContainerSlot = sourceContainerSlot;
        this.originalBoxSlot = shulkerInventoryMenuSlot;
        this.shulkerInventoryMenuSlot = shulkerInventoryMenuSlot;
        this.playerInventorySlot = playerInventorySlot;
        this.lastUseTick = currentTick;
    }

    void markUsed(long currentTick) {
        this.lastUseTick = currentTick;
    }
}
