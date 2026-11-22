package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.aleksilassila.litematica.printer.integration.containersync.ContainerSyncPlan;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Shared only by withdrawals whose box position, components and successive contents match. */
final class OrderedStorageOrigin {
    private static final int BOX_SLOTS = 27;
    final int inventoryMenuSlot;
    private final ItemStack box;
    private List<ItemStack> contents;
    private ItemStack lastSeenBox = ItemStack.EMPTY;

    private OrderedStorageOrigin(int inventoryMenuSlot, ItemStack box) {
        this.inventoryMenuSlot = inventoryMenuSlot;
        this.box = withoutContents(box);
    }

    static OrderedStorageOrigin afterWithdrawal(OrderedStorageEntry entry, List<OrderedStorageEntry> tracked,
                                               AbstractContainerMenu menu) {
        if (entry.shulkerStack.isEmpty() || entry.shulkerSnapshot.size() != BOX_SLOTS
                || entry.sourceContainerSlot >= BOX_SLOTS || menu.slots.size() < BOX_SLOTS) return null;
        // Only the most recent withdrawals at this position can establish a continuous box history.
        OrderedStorageOrigin origin = null;
        for (int i = tracked.size() - 1; i >= 0; i--) {
            var previous = tracked.get(i);
            if (previous.originalBoxSlot != entry.originalBoxSlot) continue;
            if (previous.origin != null && previous.origin.matchesBox(entry.shulkerStack)
                    && previous.origin.matchesContents(entry.shulkerSnapshot)) origin = previous.origin;
            break;
        }
        if (origin == null) origin = new OrderedStorageOrigin(entry.originalBoxSlot, entry.shulkerStack);
        origin.recordContents(menu);
        return origin;
    }

    private static ItemStack withoutContents(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.remove(DataComponents.CONTAINER);
        return copy;
    }

    boolean matchesBox(ItemStack stack) {
        return stack.is(box.getItem()) && ItemStack.matches(box, withoutContents(stack));
    }

    boolean matchesContents(List<ItemStack> items) {
        return ContainerSyncPlan.matches(contents, items);
    }

    private static boolean matchesMenu(List<ItemStack> expected, AbstractContainerMenu menu) {
        if (expected.size() != BOX_SLOTS) return false;
        for (int i = 0; i < BOX_SLOTS; i++) {
            if (!ItemStack.matches(expected.get(i), menu.getSlot(i).getItem())) return false;
        }
        return true;
    }

    private void recordContents(AbstractContainerMenu menu) {
        contents = menu.slots.subList(0, BOX_SLOTS).stream().map(slot -> slot.getItem().copy()).toList();
    }

    boolean matchesCarriedBox(LocalPlayer player) {
        if (inventoryMenuSlot < 0 || inventoryMenuSlot >= player.inventoryMenu.slots.size()) return false;
        var slot = player.inventoryMenu.getSlot(inventoryMenuSlot);
        var carriedBox = slot.getItem();
        return slot.container == player.getInventory() && matchesBox(carriedBox)
                && matchesContents(OrderedStorageStacks.snapshotShulker(carriedBox));
    }

    boolean beginReturn(LocalPlayer player, AbstractContainerMenu menu) {
        var carriedBox = player.inventoryMenu.getSlot(inventoryMenuSlot).getItem();
        if (!matchesBox(carriedBox) || !matchesMenu(contents, menu)
                || !matchesContents(OrderedStorageStacks.snapshotShulker(carriedBox))) return false;
        lastSeenBox = carriedBox.copy();
        return true;
    }

    boolean confirmReturn(LocalPlayer player, AbstractContainerMenu menu) {
        var carriedBox = player.inventoryMenu.getSlot(inventoryMenuSlot).getItem();
        if (!ItemStack.matches(lastSeenBox, carriedBox)) {
            // Some servers update the inventory box with each click, others only on close.
            // Any independently changed box must not inherit this menu's outstanding returns.
            if (!matchesBox(carriedBox) || !matchesMenu(OrderedStorageStacks.snapshotShulker(carriedBox), menu)) return false;
            lastSeenBox = carriedBox.copy();
        }
        recordContents(menu);
        return true;
    }

    OrderedStorageEntry nextReturn(List<OrderedStorageEntry> tracked, LocalPlayer player,
                                   AbstractContainerMenu menu, long now, int recentUseProtection) {
        var candidates = new ArrayList<OrderedStorageEntry>();
        for (var entry : tracked) {
            if (entry.origin != this || entry.attemptedShulkerMenuSlots.contains(inventoryMenuSlot)
                    || !OrderedStorageTracking.isValid(player, entry)) continue;
            var stack = player.getInventory().getItem(entry.playerInventorySlot);
            var original = menu.getSlot(entry.sourceContainerSlot);
            if (OrderedStorageStacks.availableCapacity(original, stack) >= stack.getCount()
                    && original.getMaxStackSize(stack) >= original.getItem().getCount() + stack.getCount()) {
                candidates.add(entry);
            }
        }
        return OrderedStoragePolicy.selectRestoreCandidate(player, candidates, Set.of(), now,
                recentUseProtection, false, true);
    }
}
