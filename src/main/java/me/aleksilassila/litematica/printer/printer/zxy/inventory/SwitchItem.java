package me.aleksilassila.litematica.printer.printer.zxy.inventory;

import fi.dy.masa.malilib.util.InventoryUtils;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
<<<<<<< HEAD
import net.minecraft.core.registries.BuiltInRegistries;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
<<<<<<< HEAD

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class SwitchItem {
    private static final int RESTORE_TIMEOUT_TICKS = 40;
    private static final Minecraft client = Minecraft.getInstance();
    private static final List<ItemStatistics> trackedItems = new ArrayList<>();

    private static ItemStatistics pendingRestore;
    private static boolean waitingForRestoreContainer;
    private static int restoreTimeout;

    public static void newItem(
            ItemStack itemStack,
            ItemStack sourceShulker,
            int sourceContainerSlot,
            int shulkerInventoryMenuSlot,
            int playerInventorySlot
    ) {
        if (itemStack == null || itemStack.isEmpty()
                || sourceContainerSlot < 0
                || shulkerInventoryMenuSlot < 0
                || playerInventorySlot < 0
                || playerInventorySlot >= 36) {
            return;
        }
        trackedItems.removeIf(statistics -> statistics.playerInventorySlot == playerInventorySlot);
        trackedItems.add(new ItemStatistics(
                itemStack,
                sourceShulker,
                sourceContainerSlot,
                shulkerInventoryMenuSlot,
                playerInventorySlot
        ));
    }

    public static void moveTrackedItem(int oldPlayerSlot, int newPlayerSlot) {
        ItemStatistics moved = null;
        for (ItemStatistics statistics : trackedItems) {
            if (statistics.playerInventorySlot == oldPlayerSlot) {
                moved = statistics;
                break;
            }
        }
        if (moved == null) {
            return;
        }
        if (newPlayerSlot < 0 || newPlayerSlot >= 36) {
            trackedItems.remove(moved);
            if (pendingRestore == moved) {
                clearPendingRestore();
            }
            return;
        }
        ItemStatistics movedRecord = moved;
        trackedItems.removeIf(statistics -> statistics != movedRecord
                && statistics.playerInventorySlot == newPlayerSlot);
        moved.playerInventorySlot = newPlayerSlot;
    }

    public static void onMainHandUse(LocalPlayer player) {
        if (player == null) {
            return;
        }
        int selectedSlot = me.aleksilassila.litematica.printer.utils.InventoryUtils
                .getSelectedSlot(player.getInventory());
        ItemStack mainHandStack = player.getMainHandItem();
        ItemStatistics statistics = findTrackedAtSlot(selectedSlot, mainHandStack);
        if (statistics == null) {
            for (ItemStatistics candidate : trackedItems) {
                if (!isTrackedStackValid(player, candidate) && matches(candidate.itemStack, mainHandStack)) {
                    candidate.playerInventorySlot = selectedSlot;
                    statistics = candidate;
                    break;
                }
            }
        }
        if (statistics != null) {
            statistics.syncUseTime();
=======
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class SwitchItem {
    @NotNull
    static Minecraft client = Minecraft.getInstance();
    public static ItemStack reSwitchItem = null;
    public static Map<ItemStack, ItemStatistics> itemStacks = new HashMap<>();

    public static void removeItem(ItemStack itemStack) {
        itemStacks.remove(itemStack);
    }

    public static void syncUseTime(ItemStack itemStack) {
        ItemStatistics itemStatistics = itemStacks.get(itemStack);
        if (itemStatistics != null) itemStatistics.syncUseTime();
    }

    public static void newItem(ItemStack itemStack, int slot, int shulkerBox) {
        if (shulkerBox != -1) itemStacks.put(itemStack, new ItemStatistics(slot, shulkerBox));
    }

    public static void openInv(ItemStack itemStack) {
        if (!client.player.containerMenu.equals(client.player.inventoryMenu) || ModLoadUtils.closeScreen > 0) {
            return;
        }
        AbstractContainerMenu sc = client.player.containerMenu;
        if (sc.slots.stream().skip(9).limit(sc.slots.size() - 10)
                .noneMatch(slot -> InventoryUtils.areStacksEqual(slot.getItem(), reSwitchItem))) {
            itemStacks.remove(reSwitchItem);
            reSwitchItem = null;
            return;
        }
        ItemStatistics itemStatistics = itemStacks.get(itemStack);
        if (itemStatistics != null) {
            if (ShulkerUtils.openShulker(sc.slots.get(itemStatistics.shulkerBoxSlot).getItem(), itemStatistics.shulkerBoxSlot)) {
                ModLoadUtils.closeScreen++;
            } else {
                removeItem(reSwitchItem);
                reSwitchItem = null;
            }
        } else {
            removeItem(reSwitchItem);
            reSwitchItem = null;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        }
    }

    /**
<<<<<<< HEAD
     * Select the least recently used tracked stack and open the most likely source shulker.
     */
    public static void checkItems() {
        LocalPlayer player = client.player;
        if (player == null) {
            reSet();
            return;
        }
        if (pendingRestore != null) {
            if (!waitingForRestoreContainer) {
                openPendingShulker();
            }
            return;
        }

        reconcileTrackedSlots(player);
        ItemStatistics selected = null;
        for (ItemStatistics statistics : trackedItems) {
            if (selected == null
                    || statistics.useTime < selected.useTime
                    || statistics.useTime == selected.useTime
                    && statistics.playerInventorySlot < selected.playerInventorySlot) {
                selected = statistics;
            }
        }
        if (selected == null) {
            MessageUtils.setOverlayMessage(I18n.INVENTORY_FULL.getName(), false);
            return;
        }
        pendingRestore = selected;
        openPendingShulker();
    }

    public static boolean isWaitingForRestoreContainer() {
        return waitingForRestoreContainer && pendingRestore != null;
    }

    /**
     * Restore to the original inner slot first, then matching partial stacks, then empty slots.
     */
    public static void restorePendingItem() {
        if (!isWaitingForRestoreContainer() || client.player == null || client.gameMode == null) {
            return;
        }
        LocalPlayer player = client.player;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.equals(player.inventoryMenu)) {
            return;
        }

        ItemStatistics statistics = pendingRestore;
        waitingForRestoreContainer = false;
        restoreTimeout = 0;
        int playerMenuSlot = findPlayerInventoryMenuSlot(menu, statistics);
        if (playerMenuSlot < 0) {
            finishPendingRestore(false);
            player.closeContainer();
            return;
        }

        Slot playerSlot = menu.slots.get(playerMenuSlot);
        ItemStack returningStack = playerSlot.getItem();
        List<Integer> destinations = buildRestoreDestinations(menu, statistics, returningStack);
        int totalCapacity = 0;
        for (int destination : destinations) {
            totalCapacity += availableCapacity(menu.slots.get(destination), returningStack);
            if (totalCapacity >= returningStack.getCount()) {
                break;
            }
        }
        if (!matches(statistics.itemStack, returningStack)) {
            finishPendingRestore(false);
            player.closeContainer();
            return;
        }
        if (totalCapacity < returningStack.getCount()) {
            retryPendingRestore();
            player.closeContainer();
            return;
        }

        client.gameMode.handleContainerInput(menu.containerId, playerMenuSlot, 0, ContainerInput.PICKUP, player);
        for (int destination : destinations) {
            if (menu.getCarried().isEmpty()) {
                break;
            }
            client.gameMode.handleContainerInput(menu.containerId, destination, 0, ContainerInput.PICKUP, player);
        }
        boolean restored = menu.getCarried().isEmpty();
        if (!restored) {
            client.gameMode.handleContainerInput(menu.containerId, playerMenuSlot, 0, ContainerInput.PICKUP, player);
        }
        if (restored) {
            finishPendingRestore(true);
        } else {
            retryPendingRestore();
        }
        player.closeContainer();
    }

    public static void tick() {
        if (!waitingForRestoreContainer || restoreTimeout <= 0 || --restoreTimeout > 0) {
            return;
        }
        LocalPlayer player = client.player;
        retryPendingRestore();
        if (player != null && !player.containerMenu.equals(player.inventoryMenu)) {
            player.closeContainer();
        }
    }

    public static void reSet() {
        trackedItems.clear();
        clearPendingRestore();
    }

    private static void openPendingShulker() {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || pendingRestore == null
                || waitingForRestoreContainer
                || ModLoadUtils.closeScreen > 0
                || !player.containerMenu.equals(player.inventoryMenu)) {
            return;
        }
        reconcileTrackedSlots(player);
        if (!trackedItems.contains(pendingRestore)) {
            clearPendingRestore();
            return;
        }

        int shulkerMenuSlot = findBestShulkerMenuSlot(player, pendingRestore);
        if (shulkerMenuSlot < 0) {
            finishPendingRestore(false);
            return;
        }
        pendingRestore.shulkerInventoryMenuSlot = shulkerMenuSlot;
        ItemStack shulkerStack = player.inventoryMenu.slots.get(shulkerMenuSlot).getItem();
        if (!ShulkerUtils.openShulker(shulkerStack, shulkerMenuSlot)) {
            retryPendingRestore();
            return;
        }
        ModLoadUtils.closeScreen++;
        waitingForRestoreContainer = true;
        restoreTimeout = RESTORE_TIMEOUT_TICKS;
    }

    private static void reconcileTrackedSlots(LocalPlayer player) {
        Set<Integer> claimedSlots = new HashSet<>();
        List<ItemStatistics> unresolved = new ArrayList<>();
        for (ItemStatistics statistics : trackedItems) {
            if (isTrackedStackValid(player, statistics)
                    && claimedSlots.add(statistics.playerInventorySlot)) {
                continue;
            }
            unresolved.add(statistics);
        }
        for (ItemStatistics statistics : unresolved) {
            int relocatedSlot = findMatchingInventorySlot(player, statistics.itemStack, claimedSlots);
            if (relocatedSlot >= 0) {
                statistics.playerInventorySlot = relocatedSlot;
                claimedSlots.add(relocatedSlot);
            } else {
                trackedItems.remove(statistics);
            }
        }
    }

    private static int findMatchingInventorySlot(
            LocalPlayer player,
            ItemStack expected,
            Set<Integer> claimedSlots
    ) {
        int selectedSlot = me.aleksilassila.litematica.printer.utils.InventoryUtils
                .getSelectedSlot(player.getInventory());
        if (!claimedSlots.contains(selectedSlot)
                && matches(expected, player.getInventory().getItem(selectedSlot))) {
            return selectedSlot;
        }
        int size = Math.min(36, player.getInventory().getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            if (!claimedSlots.contains(slot) && matches(expected, player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static int findBestShulkerMenuSlot(LocalPlayer player, ItemStatistics statistics) {
        ItemStack returningStack = player.getInventory().getItem(statistics.playerInventorySlot);
        int bestSlot = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int menuSlot = 0; menuSlot < player.inventoryMenu.slots.size(); menuSlot++) {
            Slot slot = player.inventoryMenu.slots.get(menuSlot);
            ItemStack shulkerStack = slot.getItem();
            if (!(slot.container instanceof Inventory) || !isShulkerBox(shulkerStack)) {
                continue;
            }
            if (statistics.attemptedShulkerMenuSlots.contains(menuSlot)) {
                continue;
            }
            boolean recordedSlot = menuSlot == statistics.shulkerInventoryMenuSlot;
            boolean sameShulkerType = statistics.shulkerStack.isEmpty()
                    || shulkerStack.getItem().equals(statistics.shulkerStack.getItem());
            List<ItemStack> storedItems = readStoredItems(shulkerStack);
            boolean snapshotMatches = storedShulkerMatchesSnapshot(storedItems, statistics);
            boolean originalSlotFits = storedOriginalSlotFits(storedItems, statistics, returningStack);
            boolean hasCapacity = storedShulkerHasCapacity(storedItems, returningStack);
            int score;
            if (recordedSlot && snapshotMatches && originalSlotFits) score = 0;
            else if (snapshotMatches && originalSlotFits) score = 1;
            else if (recordedSlot && snapshotMatches && hasCapacity) score = 2;
            else if (snapshotMatches && hasCapacity) score = 3;
            else if (recordedSlot && snapshotMatches) score = 4;
            else if (snapshotMatches) score = 5;
            else if (recordedSlot && sameShulkerType && originalSlotFits) score = 6;
            else if (sameShulkerType && originalSlotFits) score = 7;
            else if (recordedSlot && originalSlotFits) score = 8;
            else if (originalSlotFits) score = 9;
            else if (recordedSlot && sameShulkerType && hasCapacity) score = 10;
            else if (sameShulkerType && hasCapacity) score = 11;
            else if (recordedSlot && hasCapacity) score = 12;
            else if (hasCapacity) score = 13;
            else if (recordedSlot && sameShulkerType) score = 14;
            else if (sameShulkerType) score = 15;
            else if (recordedSlot) score = 16;
            else score = 17;
            if (score < bestScore) {
                bestScore = score;
                bestSlot = menuSlot;
            }
        }
        return bestSlot;
    }

    private static boolean storedShulkerMatchesSnapshot(
            List<ItemStack> storedItems,
            ItemStatistics statistics
    ) {
        if (statistics.shulkerSnapshot.isEmpty()) {
            return false;
        }
        if (storedItems.size() != statistics.shulkerSnapshot.size()) {
            return false;
        }
        for (int slot = 0; slot < storedItems.size(); slot++) {
            if (slot == statistics.sourceContainerSlot) {
                continue;
            }
            ItemStack expected = statistics.shulkerSnapshot.get(slot);
            ItemStack actual = storedItems.get(slot);
            if (expected.isEmpty() != actual.isEmpty()) {
                return false;
            }
            if (!expected.isEmpty()
                    && (expected.getCount() != actual.getCount() || !matches(expected, actual))) {
                return false;
            }
        }
        return true;
    }

    private static boolean storedOriginalSlotFits(
            List<ItemStack> storedItems,
            ItemStatistics statistics,
            ItemStack returningStack
    ) {
        if (statistics.sourceContainerSlot < 0 || statistics.sourceContainerSlot >= storedItems.size()) {
            return false;
        }
        return stackCapacity(storedItems.get(statistics.sourceContainerSlot), returningStack)
                >= returningStack.getCount();
    }

    private static boolean storedShulkerHasCapacity(
            List<ItemStack> storedItems,
            ItemStack returningStack
    ) {
        int capacity = 0;
        for (ItemStack stored : storedItems) {
            capacity += stackCapacity(stored, returningStack);
            if (capacity >= returningStack.getCount()) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemStack> readStoredItems(ItemStack shulkerStack) {
        try {
            return InventoryUtils.getStoredItems(shulkerStack, -1);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<Integer> buildRestoreDestinations(
            AbstractContainerMenu menu,
            ItemStatistics statistics,
            ItemStack returningStack
    ) {
        List<Integer> destinations = new ArrayList<>();
        addRestoreDestination(menu, destinations, statistics.sourceContainerSlot, returningStack, false);
        addRestoreDestination(menu, destinations, statistics.sourceContainerSlot, returningStack, true);
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (slot == statistics.sourceContainerSlot) continue;
            addRestoreDestination(menu, destinations, slot, returningStack, false);
        }
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (slot == statistics.sourceContainerSlot) continue;
            addRestoreDestination(menu, destinations, slot, returningStack, true);
        }
        return destinations;
    }

    private static void addRestoreDestination(
            AbstractContainerMenu menu,
            List<Integer> destinations,
            int slotIndex,
            ItemStack returningStack,
            boolean emptyOnly
    ) {
        if (slotIndex < 0 || slotIndex >= menu.slots.size() || destinations.contains(slotIndex)) {
            return;
        }
        Slot slot = menu.slots.get(slotIndex);
        ItemStack stored = slot.getItem();
        if (slot.container instanceof Inventory || !slot.mayPlace(returningStack)) {
            return;
        }
        if (emptyOnly ? stored.isEmpty() : !stored.isEmpty() && matches(stored, returningStack)) {
            if (availableCapacity(slot, returningStack) > 0) {
                destinations.add(slotIndex);
            }
        }
    }

    private static int findPlayerInventoryMenuSlot(AbstractContainerMenu menu, ItemStatistics statistics) {
        int expectedSlot = findPlayerInventoryMenuSlot(menu, statistics.playerInventorySlot);
        if (expectedSlot >= 0 && matches(statistics.itemStack, menu.slots.get(expectedSlot).getItem())) {
            return expectedSlot;
        }
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container instanceof Inventory && matches(statistics.itemStack, slot.getItem())) {
                return menuSlot;
            }
        }
        return -1;
    }

    private static int findPlayerInventoryMenuSlot(AbstractContainerMenu menu, int playerInventorySlot) {
        if (playerInventorySlot < 0 || playerInventorySlot >= 36) {
            return -1;
        }
        int wantedOrdinal = playerInventorySlot < 9
                ? 27 + playerInventorySlot
                : playerInventorySlot - 9;
        int ordinal = 0;
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            if (menu.slots.get(menuSlot).container instanceof Inventory) {
                if (ordinal == wantedOrdinal) {
                    return menuSlot;
                }
                ordinal++;
            }
        }
        return -1;
    }

    private static ItemStatistics findTrackedAtSlot(int playerSlot, ItemStack stack) {
        for (ItemStatistics statistics : trackedItems) {
            if (statistics.playerInventorySlot == playerSlot && matches(statistics.itemStack, stack)) {
                return statistics;
            }
        }
        return null;
    }

    private static boolean isTrackedStackValid(LocalPlayer player, ItemStatistics statistics) {
        return statistics.playerInventorySlot >= 0
                && statistics.playerInventorySlot < player.getInventory().getContainerSize()
                && matches(statistics.itemStack, player.getInventory().getItem(statistics.playerInventorySlot));
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains("shulker_box")
                && stack.getCount() == 1;
    }

    private static int availableCapacity(Slot slot, ItemStack returningStack) {
        if (slot.container instanceof Inventory || !slot.mayPlace(returningStack)) {
            return 0;
        }
        return stackCapacity(slot.getItem(), returningStack);
    }

    private static int stackCapacity(ItemStack stored, ItemStack returningStack) {
        if (returningStack == null || returningStack.isEmpty()) {
            return 0;
        }
        if (stored.isEmpty()) {
            return returningStack.getMaxStackSize();
        }
        return matches(stored, returningStack)
                ? Math.max(0, returningStack.getMaxStackSize() - stored.getCount())
                : 0;
    }

    private static boolean matches(ItemStack expected, ItemStack actual) {
        if (expected == null || actual == null || expected.isEmpty() || actual.isEmpty()) {
            return false;
        }
        ItemStack normalizedExpected = expected.copy();
        ItemStack normalizedActual = actual.copy();
        normalizedExpected.setCount(1);
        normalizedActual.setCount(1);
        return InventoryUtils.areStacksEqual(normalizedExpected, normalizedActual);
    }

    private static List<ItemStack> snapshotShulker(ItemStack shulkerStack) {
        List<ItemStack> snapshot = new ArrayList<>();
        if (shulkerStack == null || shulkerStack.isEmpty()) {
            return snapshot;
        }
        for (ItemStack stored : readStoredItems(shulkerStack)) {
            snapshot.add(stored.copy());
        }
        return snapshot;
    }

    private static void finishPendingRestore(boolean success) {
        ItemStatistics completed = pendingRestore;
        clearPendingRestore();
        if (completed != null) {
            trackedItems.remove(completed);
        }
        if (!success) {
            MessageUtils.setOverlayMessage(I18n.INVENTORY_RESTORE_FAILED.getName(), false);
        }
    }

    private static void retryPendingRestore() {
        if (pendingRestore != null && pendingRestore.shulkerInventoryMenuSlot >= 0) {
            pendingRestore.attemptedShulkerMenuSlots.add(pendingRestore.shulkerInventoryMenuSlot);
        }
        waitingForRestoreContainer = false;
        restoreTimeout = 0;
    }

    private static void clearPendingRestore() {
        pendingRestore = null;
        waitingForRestoreContainer = false;
        restoreTimeout = 0;
    }

    private static class ItemStatistics {
        private final ItemStack itemStack;
        private final ItemStack shulkerStack;
        private final List<ItemStack> shulkerSnapshot;
        private final Set<Integer> attemptedShulkerMenuSlots = new HashSet<>();
        private final int sourceContainerSlot;
        private int shulkerInventoryMenuSlot;
        private int playerInventorySlot;
        private long useTime = System.currentTimeMillis();

        private ItemStatistics(
                ItemStack itemStack,
                ItemStack shulkerStack,
                int sourceContainerSlot,
                int shulkerInventoryMenuSlot,
                int playerInventorySlot
        ) {
            this.itemStack = itemStack.copy();
            this.itemStack.setCount(1);
            this.shulkerStack = shulkerStack == null ? ItemStack.EMPTY : shulkerStack.copy();
            if (!this.shulkerStack.isEmpty()) {
                this.shulkerStack.setCount(1);
            }
            this.shulkerSnapshot = snapshotShulker(shulkerStack);
            this.sourceContainerSlot = sourceContainerSlot;
            this.shulkerInventoryMenuSlot = shulkerInventoryMenuSlot;
            this.playerInventorySlot = playerInventorySlot;
        }

        private void syncUseTime() {
=======
     * 检查所有已记录的物品，找到最近一次使用的物品（useTime最小），
     * 并尝试自动打开该物品的背包界面进行操作。
     * 如果没有可用物品，则在游戏界面显示“背包已满，请先清理”的提示。
     */
    public static void checkItems() {
        final long[] min = {System.currentTimeMillis()};
        AtomicReference<ItemStack> key = new AtomicReference<>();
        itemStacks.keySet().forEach(k -> {
            long useTime = itemStacks.get(k).useTime;
            if (useTime < min[0]) {
                min[0] = useTime;
                key.set(k);
            }
        });
        ItemStack itemStack = key.get();
        if (itemStack != null) {
            reSwitchItem = itemStack;
            openInv(itemStack);
        } else MessageUtils.setOverlayMessage(I18n.INVENTORY_FULL.getName(), false);
    }

    public static void reSwitchItem() {
        if (client.player == null || reSwitchItem == null) return;
        LocalPlayer player = client.player;
        AbstractContainerMenu sc = player.containerMenu;
        if (sc.equals(player.inventoryMenu)) return;

        List<Integer> sameItem = new ArrayList<>();
        for (int i = 0; i < sc.slots.size(); i++) {
            Slot slot = sc.slots.get(i);
            if (!(slot.container instanceof Inventory) &&
                    InventoryUtils.areStacksEqual(reSwitchItem, slot.getItem()) &&
                    slot.getItem().getCount() < slot.getItem().getMaxStackSize()
            ) sameItem.add(i);
            if (slot.container instanceof Inventory && client.gameMode != null && InventoryUtils.areStacksEqual(slot.getItem(), reSwitchItem)) {
                int slot1 = itemStacks.get(reSwitchItem).slot;
                boolean reInv = false;
                //检查记录的槽位是否有物品
                if (sc.slots.get(slot1).getItem().isEmpty()) {
                    client.gameMode.handleContainerInput(sc.containerId, i, 0, ContainerInput.PICKUP, client.player);
                    client.gameMode.handleContainerInput(sc.containerId, slot1, 0, ContainerInput.PICKUP, client.player);
                    reInv = true;
                } else {
                    int count = reSwitchItem.getCount();
                    client.gameMode.handleContainerInput(sc.containerId, i, 0, ContainerInput.PICKUP, client.player);
                    for (Integer integer : sameItem) {
                        int count1 = sc.slots.get(integer).getItem().getCount();
                        int maxCount = sc.slots.get(integer).getItem().getMaxStackSize();
                        int i1 = maxCount - count1;
                        count -= i1;
                        client.gameMode.handleContainerInput(sc.containerId, integer, 0, ContainerInput.PICKUP, client.player);
                        if (count <= 0) reInv = true;
                    }
                }
                removeItem(reSwitchItem);
                reSwitchItem = null;
                player.closeContainer();
                if (!reInv) {
                    MessageUtils.setOverlayMessage(I18n.INVENTORY_RESTORE_FAILED.getName(), false);
                }
                client.gameMode.handleContainerInput(sc.containerId, i, 0, ContainerInput.PICKUP, client.player);
                return;
            }
        }
    }

    public static void reSet() {
        reSwitchItem = null;
        itemStacks = new HashMap<>();
    }

    public static class ItemStatistics {
        public int slot;
        public int shulkerBoxSlot;
        public long useTime = System.currentTimeMillis();

        public ItemStatistics(int slot, int shulkerBox) {
            this.slot = slot;
            this.shulkerBoxSlot = shulkerBox;
        }

        public void syncUseTime() {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            this.useTime = System.currentTimeMillis();
        }
    }
}
