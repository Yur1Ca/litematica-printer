package me.aleksilassila.litematica.printer.integration.inventory;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Tracks the original container slot and moved shulker across the return interaction. */
final class NestedShulkerReturn {
    private BlockPos sourcePos;
    private int sourceSlot = -1;
    private ItemStack snapshot = ItemStack.EMPTY;
    private int inventorySlot = -1;
    private boolean restoring;
    private long syncDeadline;

    void recordTransfer(BlockPos pos, int sourceSlot, ItemStack snapshot, int inventorySlot) {
        this.sourcePos = pos.immutable();
        this.sourceSlot = sourceSlot;
        this.snapshot = snapshot.copy();
        this.inventorySlot = inventorySlot;
    }

    boolean hasSource() {
        return this.sourcePos != null;
    }

    BlockPos sourcePos() {
        return this.sourcePos;
    }

    int sourceSlot() {
        return this.sourceSlot;
    }

    int inventorySlot() {
        return this.inventorySlot;
    }

    boolean isRestoring() {
        return this.restoring;
    }

    boolean beginRestore(boolean otherRequestPending, boolean inventoryMenuOpen) {
        if (this.restoring || this.sourcePos == null || otherRequestPending || !inventoryMenuOpen) return false;
        this.restoring = true;
        return true;
    }

    boolean acceptsSourceSlot(int containerSize, ItemStack source) {
        return this.sourceSlot >= 0 && this.sourceSlot < containerSize && source.isEmpty();
    }

    boolean acceptsInventorySlot(int inventorySize, ItemStack candidate) {
        return this.inventorySlot >= 0 && this.inventorySlot < Math.min(36, inventorySize)
                && candidate != null && !candidate.isEmpty() && candidate.getCount() == 1
                && !this.snapshot.isEmpty() && candidate.getItem() == this.snapshot.getItem();
    }

    void markWaitingForSync(long now) {
        this.syncDeadline = now + 5L;
    }

    boolean waitingForSync(long now) {
        return now < this.syncDeadline;
    }

    void clear() {
        this.sourcePos = null;
        this.sourceSlot = -1;
        this.snapshot = ItemStack.EMPTY;
        this.inventorySlot = -1;
        this.restoring = false;
        this.syncDeadline = 0L;
    }

    static int locateMovedShulker(LocalPlayer player, ItemStack snapshot, List<ItemStack> before) {
        int found = -1;
        int size = Math.min(36, player.getInventory().getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            boolean wasPresent = slot < before.size()
                    && ItemStack.isSameItemSameComponents(before.get(slot), candidate);
            if (!wasPresent && ItemStack.isSameItemSameComponents(candidate, snapshot)) {
                if (found >= 0) return -1;
                found = slot;
            }
        }
        return found;
    }

    static List<ItemStack> inventorySnapshot(LocalPlayer player) {
        List<ItemStack> snapshot = new ArrayList<>();
        int size = Math.min(36, player.getInventory().getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            snapshot.add(player.getInventory().getItem(slot).copy());
        }
        return snapshot;
    }
}
