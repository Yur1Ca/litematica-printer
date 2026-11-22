package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.malilib.util.InventoryUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Slot mapping, stack identity and destination planning for ordered restore. */
final class OrderedStorageStacks {
    private OrderedStorageStacks() {
    }

    static int findBestShulkerMenuSlot(LocalPlayer player, OrderedStorageEntry entry) {
        ItemStack returningStack = player.getInventory().getItem(entry.playerInventorySlot);
        int bestSlot = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int menuSlot = 0; menuSlot < player.inventoryMenu.slots.size(); menuSlot++) {
            Slot slot = player.inventoryMenu.slots.get(menuSlot);
            ItemStack shulkerStack = slot.getItem();
            if (slot.container != player.getInventory() || slot.getContainerSlot() == entry.playerInventorySlot
                    || !isShulkerBox(shulkerStack)
                    || entry.attemptedShulkerMenuSlots.contains(menuSlot)) {
                continue;
            }
            List<ItemStack> storedItems = readStoredItems(shulkerStack);
            boolean hasCapacity = storedShulkerHasCapacity(storedItems, returningStack);
            // A missing inventory component leaves capacity unknown until the server opens the box.
            if (!storedItems.isEmpty() && !hasCapacity) continue;
            if (entry.origin != null && menuSlot == entry.origin.inventoryMenuSlot
                    && entry.origin.matchesBox(shulkerStack) && entry.origin.matchesContents(storedItems)) return menuSlot;
            int score = ShulkerSelectionPolicy.score(
                    menuSlot == entry.shulkerInventoryMenuSlot,
                    entry.shulkerStack.isEmpty() || shulkerStack.getItem().equals(entry.shulkerStack.getItem()),
                    storedShulkerMatchesSnapshot(storedItems, entry),
                    storedOriginalSlotFits(storedItems, entry, returningStack),
                    hasCapacity
            );
            if (score < bestScore) {
                bestScore = score;
                bestSlot = menuSlot;
            }
        }
        return bestSlot;
    }

    static List<Integer> buildRestoreDestinations(
            AbstractContainerMenu menu,
            OrderedStorageEntry entry,
            ItemStack returningStack
    ) {
        List<Integer> destinations = new ArrayList<>();
        addRestoreDestination(menu, destinations, entry.sourceContainerSlot, returningStack, false);
        addRestoreDestination(menu, destinations, entry.sourceContainerSlot, returningStack, true);
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (slot != entry.sourceContainerSlot) {
                addRestoreDestination(menu, destinations, slot, returningStack, false);
            }
        }
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (slot != entry.sourceContainerSlot) {
                addRestoreDestination(menu, destinations, slot, returningStack, true);
            }
        }
        return destinations;
    }

    static int findPlayerInventoryMenuSlot(AbstractContainerMenu menu, OrderedStorageEntry entry) {
        int expectedSlot = findPlayerInventoryMenuSlot(menu, entry.playerInventorySlot);
        if (expectedSlot >= 0 && matches(entry.itemStack, menu.slots.get(expectedSlot).getItem())) {
            return expectedSlot;
        }
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container instanceof Inventory && matches(entry.itemStack, slot.getItem())) {
                return menuSlot;
            }
        }
        return -1;
    }

    static boolean canFit(AbstractContainerMenu menu, List<Integer> destinations, ItemStack stack) {
        int remaining = stack.getCount();
        for (int slot : destinations) {
            remaining -= availableCapacity(menu.getSlot(slot), stack);
            if (remaining <= 0) return true;
        }
        return false;
    }

    static int availableCapacity(Slot slot, ItemStack returningStack) {
        if (slot.container instanceof Inventory || !slot.mayPlace(returningStack)) {
            return 0;
        }
        return stackCapacity(slot.getItem(), returningStack);
    }

    static boolean matches(ItemStack expected, ItemStack actual) {
        if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()) {
            return false;
        }
        ItemStack normalizedExpected = expected.copy();
        ItemStack normalizedActual = actual.copy();
        normalizedExpected.setCount(1);
        normalizedActual.setCount(1);
        return InventoryUtils.areStacksEqual(normalizedExpected, normalizedActual);
    }

    static List<ItemStack> snapshotShulker(ItemStack shulkerStack) {
        List<ItemStack> snapshot = new ArrayList<>();
        if (shulkerStack == null || shulkerStack.isEmpty()) {
            return snapshot;
        }
        for (ItemStack stored : readStoredItems(shulkerStack)) {
            snapshot.add(stored.copy());
        }
        return snapshot;
    }

    private static boolean storedShulkerMatchesSnapshot(
            List<ItemStack> storedItems,
            OrderedStorageEntry entry
    ) {
        if (entry.shulkerSnapshot.isEmpty() || storedItems.size() != entry.shulkerSnapshot.size()) {
            return false;
        }
        for (int slot = 0; slot < storedItems.size(); slot++) {
            if (slot == entry.sourceContainerSlot) continue;
            ItemStack expected = entry.shulkerSnapshot.get(slot);
            ItemStack actual = storedItems.get(slot);
            if (expected.isEmpty() != actual.isEmpty()
                    || !expected.isEmpty()
                    && (expected.getCount() != actual.getCount() || !matches(expected, actual))) {
                return false;
            }
        }
        return true;
    }

    private static boolean storedOriginalSlotFits(
            List<ItemStack> storedItems,
            OrderedStorageEntry entry,
            ItemStack returningStack
    ) {
        return entry.sourceContainerSlot >= 0
                && entry.sourceContainerSlot < storedItems.size()
                && stackCapacity(storedItems.get(entry.sourceContainerSlot), returningStack)
                >= returningStack.getCount();
    }

    private static boolean storedShulkerHasCapacity(List<ItemStack> storedItems, ItemStack returningStack) {
        int capacity = 0;
        for (ItemStack stored : storedItems) {
            capacity += stackCapacity(stored, returningStack);
            if (capacity >= returningStack.getCount()) return true;
        }
        return false;
    }

    private static List<ItemStack> readStoredItems(ItemStack shulkerStack) {
        try {
            return InventoryUtils.getStoredItems(shulkerStack, 27);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void addRestoreDestination(
            AbstractContainerMenu menu,
            List<Integer> destinations,
            int slotIndex,
            ItemStack returningStack,
            boolean emptyOnly
    ) {
        if (slotIndex < 0 || slotIndex >= menu.slots.size() || destinations.contains(slotIndex)) return;
        Slot slot = menu.slots.get(slotIndex);
        ItemStack stored = slot.getItem();
        if (slot.container instanceof Inventory || !slot.mayPlace(returningStack)) return;
        if ((emptyOnly ? stored.isEmpty() : !stored.isEmpty() && matches(stored, returningStack))
                && availableCapacity(slot, returningStack) > 0) {
            destinations.add(slotIndex);
        }
    }

    private static int findPlayerInventoryMenuSlot(AbstractContainerMenu menu, int playerInventorySlot) {
        if (playerInventorySlot < 0 || playerInventorySlot >= 36) return -1;
        int wantedOrdinal = playerInventorySlot < 9 ? 27 + playerInventorySlot : playerInventorySlot - 9;
        int ordinal = 0;
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            if (menu.slots.get(menuSlot).container instanceof Inventory) {
                if (ordinal == wantedOrdinal) return menuSlot;
                ordinal++;
            }
        }
        return -1;
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains("shulker_box")
                && stack.getCount() == 1;
    }

    private static int stackCapacity(ItemStack stored, ItemStack returningStack) {
        if (returningStack == null || returningStack.isEmpty()) return 0;
        if (stored.isEmpty()) return returningStack.getMaxStackSize();
        return matches(stored, returningStack)
                ? Math.max(0, returningStack.getMaxStackSize() - stored.getCount()) : 0;
    }
}
