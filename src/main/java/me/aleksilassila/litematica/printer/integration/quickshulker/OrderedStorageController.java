package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.ShulkerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

final class OrderedStorageController {
    private int automatedContainerId = -1;
    private static final int RESTORE_TIMEOUT_TICKS = 40;
    private static final int PRESSURE_TRIGGER_FREE_SLOTS = 4;
    private static final int PRESSURE_TARGET_FREE_SLOTS = 8;
    private static final int EMERGENCY_FREE_SLOTS = 1;
    private static final int RECENT_USE_PROTECTION_TICKS = 40;
    private static final int IDLE_RESTORE_DELAY_TICKS = 100;
    private final Minecraft client;
    private final QuickShulkerContainerSession containerSession;
    private final Supplier<Set<Item>> neededItems;
    private final List<OrderedStorageEntry> trackedItems = new ArrayList<>();
    private final RestoreSession<OrderedStorageEntry> restoreSession = new RestoreSession<>();
    private final OrderedStorageDrain drain = new OrderedStorageDrain(this::returnsEnabled);
    private final StoppedWorkReturns stopped = new StoppedWorkReturns();
    private OrderedStorageOrigin openOrigin;
    private boolean stoppedOpening;
    private long nextRestoreTick = Long.MIN_VALUE;

    OrderedStorageController(Minecraft client, Supplier<Set<Item>> neededItems, QuickShulkerContainerSession containerSession) {
        this.client = client;
        this.containerSession = containerSession;
        this.neededItems = neededItems;
    }

    public void newItem(ItemStack itemStack, ItemStack sourceShulker, int sourceContainerSlot,
                        int shulkerInventoryMenuSlot, int playerInventorySlot) {
        if (itemStack == null || itemStack.isEmpty()
                || sourceContainerSlot < 0
                || shulkerInventoryMenuSlot < 0
                || playerInventorySlot < 0
                || playerInventorySlot >= 36) {
            return;
        }
        var entry = new OrderedStorageEntry(itemStack, sourceShulker, sourceContainerSlot,
                shulkerInventoryMenuSlot, playerInventorySlot, currentGameTick());
        stopped.noteWithdrawal(client, entry);
        if (containerSession.ownsCurrentMenu()) {
            entry.origin = OrderedStorageOrigin.afterWithdrawal(entry, trackedItems, client.player.containerMenu);
        }
        this.trackedItems.removeIf(statistics -> statistics.playerInventorySlot == playerInventorySlot);
        this.trackedItems.add(entry);
        restoreSession.markActivity(currentGameTick());
        yieldToWork();
    }

    public void moveTrackedItem(int oldPlayerSlot, int newPlayerSlot) {
        if (returnsAfterStop()) trackedItems.stream().filter(entry -> entry.playerInventorySlot == oldPlayerSlot)
                .findFirst().ifPresent(entry -> stopped.moved(entry, newPlayerSlot, client.player));
        OrderedStorageTracking.move(trackedItems, restoreSession, oldPlayerSlot, newPlayerSlot);
    }

    public void onMainHandUse(LocalPlayer player) {
        if (player == null) {
            return;
        }
        long currentTick = currentGameTick();
        this.restoreSession.markActivity(currentTick);
        OrderedStorageTracking.markMainHandUsed(this.trackedItems, player, currentTick);
    }

    /**
     * Keep enough free inventory slots while printing, then return every
     * tracked stack after the printer has been idle for a short period.
     */
    public boolean maintainOrderlyStorage() {
        if (deferUnopenedRestoreForBedrock()) {
            return false;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null
                //#if MC > 260100
                //$$ || client.gui.screen() != null
                //#else
                || client.screen != null
                //#endif
                || !player.containerMenu.equals(player.inventoryMenu)) {
            return this.restoreSession.hasPending();
        }
        boolean afterStop = returnsAfterStop();
        if (afterStop && !StoppedWorkReturns.manualInputIdle(client)) return this.restoreSession.hasPending();
        if (this.restoreSession.hasPending()) {
            if (!this.restoreSession.isWaitingForContainer()) {
                openPendingShulker();
            }
            return true;
        }
        if (afterStop) {
            if (!canContinueIdleReturn()) return false;
            return scheduleRestore(player, currentGameTick(), true, true);
        }
        if (!Configs.Core.WORK_SWITCH.getBooleanValue() || shouldYieldNewRestore()) return false;

        return scheduleMaintenance(player, false);
    }

    public boolean tryRestoreForInventoryPressure() {
        if (!Configs.Core.WORK_SWITCH.getBooleanValue() || deferUnopenedRestoreForBedrock()) {
            return false;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null
                || !player.containerMenu.equals(player.inventoryMenu)) {
            return false;
        }
        if (!this.restoreSession.hasPending() && shouldYieldNewRestore()) return false;
        return scheduleMaintenance(player, true);
    }

    private boolean scheduleMaintenance(LocalPlayer player, boolean pressureOnly) {
        OrderedStorageTracking.reconcile(this.trackedItems, player);
        if (this.trackedItems.isEmpty()) {
            this.restoreSession.clearPressureRecovery();
            if (!pressureOnly) this.restoreSession.clearActivity();
            return false;
        }
        long currentTick = client.level.getGameTime();
        OrderedStoragePolicy.normalizeActivityTicks(this.restoreSession, this.trackedItems, currentTick);
        int freeSlots = OrderedStoragePolicy.countEmptyInventorySlots(player);
        this.restoreSession.updatePressureRecovery(freeSlots, PRESSURE_TRIGGER_FREE_SLOTS, PRESSURE_TARGET_FREE_SLOTS);
        if (this.restoreSession.isPressureRecoveryActive())
            return scheduleRestore(player, currentTick, freeSlots <= EMERGENCY_FREE_SLOTS, false);
        return !pressureOnly && neededItems.get().isEmpty()
                && currentTick - restoreSession.lastActivityTick() >= IDLE_RESTORE_DELAY_TICKS
                && scheduleRestore(player, currentTick, false, true);
    }

    public boolean hasPendingRestore() {
        return this.drain.active() || this.restoreSession.hasPending();
    }

    boolean beginStoppedReturns(boolean pendingWithdrawal, int movingHotbarSlot) {
        return Configs.Placement.QUICK_SHULKER.getBooleanValue() && Configs.Placement.STORE_ORDERLY.getBooleanValue()
                && stopped.begin(client, trackedItems, pendingWithdrawal, movingHotbarSlot);
    }

    boolean returnsAfterStop() {
        if (!Configs.Placement.QUICK_SHULKER.getBooleanValue() || !Configs.Placement.STORE_ORDERLY.getBooleanValue()) {
            stopped.disable(trackedItems);
            return false;
        }
        return stopped.active(client, trackedItems, hasPendingRestore(), drain.active() ? restoreSession.pending() : null);
    }
    boolean hasTrackedReturns() { return !trackedItems.isEmpty(); }
    boolean returnsEnabled() {
        return Configs.Placement.QUICK_SHULKER.getBooleanValue() && Configs.Placement.STORE_ORDERLY.getBooleanValue()
                && (Configs.Core.WORK_SWITCH.getBooleanValue() || returnsAfterStop());
    }
    void endStoppedReturns() {
        stopped.active(client, trackedItems, hasPendingRestore(), drain.active() ? restoreSession.pending() : null);
        stopped.end();
    }
    void finishStoppedWithdrawal() { stopped.finishWithdrawal(); }

    /** Observe activity before acquiring leases, including ticks when bedrock owns the main hand. */
    boolean deferUnopenedRestoreForBedrock() {
        var runtime = RuntimeAccess.currentOrNull();
        if (runtime == null || runtime.bedrockEngine() == null || !runtime.bedrockEngine().hasActiveWork()) {
            return false;
        }
        restoreSession.markActivity(currentGameTick());
        // A sent open or return click still owns its menu/cursor until it safely settles.
        if (this.drain.active() || this.restoreSession.isWaitingForContainer()) {
            return false;
        }
        this.restoreSession.clearPending();
        return true;
    }

    boolean isDraining() { return this.drain.active(); }
    void cancelDrain() { this.drain.cancel(); }

    boolean onInventoryContent(int id, java.util.List<ItemStack> items, ItemStack cursor) {
        if (client.player == null || !this.drain.observe(client, id,
                client.player.containerMenu.getStateId(), items, cursor)) return false;
        if (!this.drain.active()) finishDrain();
        return true;
    }

    void discardForClientScopeChange() { this.drain.discard(); }

    public boolean isWaitingForRestoreContainer() {
        return this.restoreSession.isWaitingForContainer();
    }

    boolean canConfirmRestoreOpening() {
        return !stoppedOpening || StoppedWorkReturns.originalBoxSlot(restoreSession.pending(), client.player) >= 0;
    }

    void rejectRestoreContainer() {
        // Retain the borrowed stack for a later return to a different box.
        retryPendingRestore();
        this.restoreSession.clearPending();
        this.automatedContainerId = -1;
        yieldToWork();
    }

    /**
     * Restore to the original inner slot first, then matching partial stacks, then empty slots.
     */
    public void onContainerOpen(int containerId) {
        if (isWaitingForRestoreContainer()) this.automatedContainerId = containerId;
    }

    public void restorePendingItem() {
        if (!this.isWaitingForRestoreContainer() || client.player == null || client.gameMode == null) {
            return;
        }
        LocalPlayer player = client.player;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.equals(player.inventoryMenu) || menu.containerId != automatedContainerId || !menu.getCarried().isEmpty()) {
            return;
        }

        OrderedStorageEntry statistics = this.restoreSession.pending();
        this.restoreSession.stopContainerWait();
        var origin = statistics.origin;
        openOrigin = origin != null && statistics.shulkerInventoryMenuSlot == origin.inventoryMenuSlot
                && containerSession.ownsCurrentMenu() && origin.beginReturn(player, menu) ? origin : null;
        if (stoppedOpening && openOrigin == null) {
            rejectRestoreContainer();
            if (containerSession.ownsCurrentMenu()) player.closeContainer();
            return;
        }
        stoppedOpening = false;
        returnStack(menu, statistics);
    }

    private void returnStack(AbstractContainerMenu menu, OrderedStorageEntry statistics) {
        LocalPlayer player = client.player;
        int playerMenuSlot = !Configs.Core.WORK_SWITCH.getBooleanValue()
                ? stopped.sourceSlot(statistics, player, menu)
                : OrderedStorageStacks.findPlayerInventoryMenuSlot(menu, statistics);
        if (playerMenuSlot < 0) {
            failPendingRestore();
            player.closeContainer();
            return;
        }

        Slot playerSlot = menu.slots.get(playerMenuSlot);
        ItemStack returningStack = playerSlot.getItem().copy();
        List<Integer> destinations = OrderedStorageStacks.buildRestoreDestinations(menu, statistics, returningStack);
        if (!OrderedStorageStacks.matches(statistics.itemStack, returningStack)) {
            failPendingRestore();
            player.closeContainer();
            return;
        }
        if (!OrderedStorageStacks.canFit(menu, destinations, returningStack)) {
            retryPendingRestore();
            player.closeContainer();
            return;
        }

        // Keep one click in flight. Prediction plus an early close lets delayed cursor corrections
        // land on inventoryMenu and permanently pause the scheduler.
        this.drain.begin(client, menu, playerMenuSlot, destinations);
    }

    public void tick() {
        if (this.drain.active()) {
            this.drain.tick(client);
            if (!this.drain.active()) finishDrain();
            return;
        }
        if (!this.restoreSession.tickContainerTimeout()) {
            return;
        }
        LocalPlayer player = client.player;
        retryPendingRestore();
        ShulkerUtils.recoverFailedOpen();
        ModLoadUtils.closeScreen = 0;
        if (player != null && this.containerSession.ownsCurrentMenu()) {
            player.closeContainer();
        }
    }

    public void reset() {
        stopped.reset();
        stoppedOpening = false;
        this.drain.cancel();
        this.openOrigin = null;
        this.automatedContainerId = -1;
        this.trackedItems.clear();
        this.restoreSession.reset();
        this.nextRestoreTick = Long.MIN_VALUE;
    }

    private void finishDrain() {
        if (!drain.restored()) {
            openOrigin = null;
            failPendingRestore();
            return;
        }

        var completed = restoreSession.clearPending();
        trackedItems.remove(completed);
        boolean pressure = restoreSession.isPressureRecoveryActive();
        var player = client.player;
        if (pressure && OrderedStoragePolicy.countEmptyInventorySlots(player) >= PRESSURE_TARGET_FREE_SLOTS) {
            restoreSession.clearPressureRecovery();
        }
        var menu = player.containerMenu;
        if (completed != null && openOrigin != null && containerSession.ownsCurrentMenu()
                && openOrigin.confirmReturn(player, menu)) {
            boolean afterStop = returnsAfterStop();
            if ((!pressure || afterStop) && canContinueIdleReturn()) {
                var next = openOrigin.nextReturn(trackedItems, player, menu, currentGameTick(), afterStop ? 0 : RECENT_USE_PROTECTION_TICKS);
                if (next != null) {
                    restoreSession.schedule(next);
                    returnStack(menu, next);
                    return;
                }
            }
        }
        openOrigin = null;
        yieldToWork();
        player.closeContainer();
    }

    private boolean canContinueIdleReturn() {
        var runtime = RuntimeAccess.currentOrNull();
        return !trackedItems.isEmpty() && returnsEnabled()
                && neededItems.get().isEmpty() && (runtime == null || !runtime.materialRequests().isBusy())
                && (returnsAfterStop() ? StoppedWorkReturns.manualInputIdle(client)
                                      : currentGameTick() - restoreSession.lastActivityTick() >= IDLE_RESTORE_DELAY_TICKS)
                && !deferUnopenedRestoreForBedrock();
    }

    private long currentGameTick() {
        return client.level == null ? 0L : client.level.getGameTime();
    }

    private void yieldToWork() {
        // Leave the next normal work tick free before selecting another maintenance transaction.
        // Already-pending returns and in-flight cursor recovery continue without this delay.
        if (this.nextRestoreTick == Long.MIN_VALUE) this.nextRestoreTick = currentGameTick() + 2L;
    }

    private boolean shouldYieldNewRestore() {
        if (currentGameTick() < this.nextRestoreTick) return true;
        // Only a maintenance pass can rearm the yield; repeated deliveries cannot extend it.
        this.nextRestoreTick = Long.MIN_VALUE;
        return false;
    }

    private boolean scheduleRestore(LocalPlayer player, long currentTick, boolean allowRecentlyUsed,
                                    boolean allowCurrentMainHand) {
        if (this.restoreSession.hasPending()) {
            if (!this.restoreSession.isWaitingForContainer()) {
                openPendingShulker();
            }
            return true;
        }
        OrderedStorageEntry selected = OrderedStoragePolicy.selectRestoreCandidate(player, trackedItems,
                neededItems.get(), currentTick, RECENT_USE_PROTECTION_TICKS, allowRecentlyUsed, allowCurrentMainHand);
        if (selected == null) {
            return false;
        }
        this.restoreSession.schedule(selected);
        openPendingShulker();
        return this.restoreSession.hasPending();
    }

    private void openPendingShulker() {
        LocalPlayer player = client.player;
        OrderedStorageEntry pendingRestore = this.restoreSession.pending();
        if (player == null || client.gameMode == null || pendingRestore == null
                || this.restoreSession.isWaitingForContainer()
                || ModLoadUtils.closeScreen > 0
                || !player.containerMenu.equals(player.inventoryMenu)) {
            return;
        }
        boolean afterStop = !Configs.Core.WORK_SWITCH.getBooleanValue();
        if (afterStop && !returnsAfterStop()) return;
        if (!afterStop) OrderedStorageTracking.reconcile(this.trackedItems, player);
        if (!this.trackedItems.contains(pendingRestore)) {
            this.restoreSession.clearPending();
            return;
        }

        int shulkerMenuSlot = afterStop ? StoppedWorkReturns.originalBoxSlot(pendingRestore, player)
                : OrderedStorageStacks.findBestShulkerMenuSlot(player, pendingRestore);
        if (shulkerMenuSlot < 0) {
            failPendingRestore();
            return;
        }
        pendingRestore.shulkerInventoryMenuSlot = shulkerMenuSlot;
        ItemStack shulkerStack = player.inventoryMenu.slots.get(shulkerMenuSlot).getItem();
        this.containerSession.begin(shulkerStack,
                me.aleksilassila.litematica.printer.config.Configs.Placement.QUICK_SHULKER_MODE.getOptionListValue()
                        == me.aleksilassila.litematica.printer.enums.QuickShulkerModeType.CLICK_SLOT);
        if (!ShulkerUtils.openShulker(shulkerStack, shulkerMenuSlot)) {
            this.containerSession.reset();
            retryPendingRestore();
            return;
        }
        stoppedOpening = afterStop;
        ModLoadUtils.closeScreen++;
        this.restoreSession.beginContainerWait(RESTORE_TIMEOUT_TICKS);
    }

    private void failPendingRestore() {
        trackedItems.remove(restoreSession.clearPending());
        yieldToWork();
        MessageUtils.setOverlayMessage(I18n.INVENTORY_RESTORE_FAILED.getName(), false);
    }

    private void retryPendingRestore() {
        stoppedOpening = false;
        OrderedStorageEntry pendingRestore = this.restoreSession.pending();
        if (pendingRestore != null && pendingRestore.shulkerInventoryMenuSlot >= 0) {
            pendingRestore.attemptedShulkerMenuSlots.add(pendingRestore.shulkerInventoryMenuSlot);
        }
        this.restoreSession.stopContainerWait();
    }

}
