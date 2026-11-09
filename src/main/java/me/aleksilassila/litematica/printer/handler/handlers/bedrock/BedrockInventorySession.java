package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Owns authoritative inventory preparation separately from piston exploit timing. */
final class BedrockInventorySession {
    interface InventoryAccess {
        boolean selectOffhand(Item item);
        boolean materialRequiresInventorySwap(Item item);
        /** Changing the selected hotbar slot also sends its carried-item selection packet. */
        boolean selectMaterial(Item item);
        boolean toolRequiresInventorySwap(BlockState state, boolean cleanup);
        boolean selectTool(BlockState state, boolean cleanup);
    }

    private final Minecraft client;
    private final InventoryAccess access;
    private final BedrockToolSync toolSync;
    private Object connection;
    private InventorySyncPackets.SyncFence pending;
    private InventorySwitchGuard pendingGuard;
    private long pendingGuardReceipt = -1L;
    private BudgetRefresh refresh;
    private boolean initialValidated;
    private boolean needsToolPrewarm;
    private boolean rawDirty;
    private final int[] counts = new int[41];
    private final int[] toolUses = new int[9];

    BedrockInventorySession(Minecraft client) {
        this(client, new InventoryAccess() {
            public boolean selectOffhand(Item item) { return BedrockInventory.switchToOffhand(item); }
            public boolean materialRequiresInventorySwap(Item item) { return BedrockInventory.materialRequiresInventorySwap(item); }
            public boolean selectMaterial(Item item) { return BedrockInventory.switchToItem(item); }
            public boolean toolRequiresInventorySwap(BlockState state, boolean cleanup) {
                return BedrockInventory.toolRequiresInventorySwap(state, cleanup);
            }
            public boolean selectTool(BlockState state, boolean cleanup) {
                return cleanup ? BedrockInventory.switchToCleanupTool(state) : BedrockInventory.switchToBestTool(state);
            }
        });
    }

    BedrockInventorySession(Minecraft client, InventoryAccess access) {
        this.client = client;
        this.access = access;
        this.toolSync = new BedrockToolSync(client);
    }

    private Object currentConnection() {
        var listener = this.client.getConnection();
        return listener == null ? null : listener.getConnection();
    }

    private void observeConnection() {
        Object current = this.currentConnection();
        if (this.connection != current) {
            this.reset();
            this.connection = current;
        }
    }

    boolean isSettling() {
        this.observeConnection();
        if (this.pending != null && !this.pending.isForConnection(this.connection)) {
            this.reset();
            return false;
        }
        if (this.pending != null) {
            if (!this.pending.complete()) return true;
            this.pending = null;
            this.initialValidated = true;
            this.rebuildBudgets();
            if (this.pendingGuard != null) this.pendingGuard.confirmReceipt(this.pendingGuardReceipt);
            this.pendingGuard = null;
            this.pendingGuardReceipt = -1L;
        }
        return false;
    }

    boolean isToolSettling() {
        this.observeConnection();
        return this.toolSync.isPending();
    }

    boolean isInventoryPreparationPending() { return this.isSettling() || this.refresh != null; }

    boolean reconcileInventoryIfDirty() {
        if (!this.safeContext()) return false;
        if (this.isSettling()) return this.rawDirty;
        if (!this.rawDirty) return false;
        if (this.refresh != null) return true;
        this.synchronize(null);
        return this.isSettling();
    }

    private void record(InventorySyncPackets.SyncFence fence) {
        this.observeConnection();
        this.pending = fence;
        var runtime = RuntimeAccess.currentOrNull();
        this.pendingGuard = runtime == null ? null : runtime.inventorySwitchGuard();
        this.pendingGuardReceipt = this.pendingGuard == null ? -1L : this.pendingGuard.receipt();
    }

    void maintainInventory(int reserveBuffer) {
        if (!this.safe() || !this.initialValidated || !this.rawDirty || this.refresh != null) return;
        int buffer = Math.max(1, reserveBuffer);
        boolean recoverable = false;
        var inventory = this.client.player.getInventory();
        for (int slot = 0; slot < this.counts.length && !recoverable; slot++) {
            if (slot >= 9 && slot != 40) continue;
            var stack = inventory.getItem(slot);
            if (!stack.is(Items.PISTON) && !stack.is(Items.REDSTONE_TORCH) && !stack.is(Items.SLIME_BLOCK)) continue;
            recoverable = this.counts[slot] <= buffer && stack.getCount() > this.counts[slot];
        }
        var tool = this.client.player.getMainHandItem();
        int available = this.availableToolUses();
        if (tool.isDamageableItem() && remainingUses(tool) > available) {
            var runtime = RuntimeAccess.currentOrNull();
            var service = runtime == null ? null : runtime.toolSwitchService();
            boolean usable = service == null || !service.isDurabilityGuardActive() || service.isToolUsable(tool);
            if (usable && (available <= buffer || this.guardedToolNeedsCalibration(buffer + 1))) recoverable = true;
        }
        if (!recoverable || !NetworkUtils.canSendPacketBatch()) return;
        var snapshot = InventorySyncPackets.createPlayerInventoryFullSync(this.client.player);
        var fence = InventorySyncPackets.prepareFence(this.connection);
        if (snapshot != null && fence != null && NetworkUtils.sendPacketBatch(List.of(snapshot, fence.packet()))) {
            this.refresh = new BudgetRefresh(fence);
        }
    }

    void onInventorySynchronized(Object connection) {
        this.observeConnection();
        if (this.connection != connection) return;
        this.isSettling();
        var refresh = this.refresh;
        if (refresh == null || !refresh.fence.isForConnection(connection) || !refresh.fence.complete()) return;
        this.refresh = null;
        if (!this.safeContext()) return;
        var inventory = this.client.player.getInventory();
        this.rawDirty = false;
        for (int slot = 0; slot < this.counts.length; slot++) {
            this.counts[slot] = Math.max(0, inventory.getItem(slot).getCount() - refresh.countCosts[slot]);
            this.rawDirty |= refresh.countCosts[slot] > 0;
        }
        for (int slot = 0; slot < this.toolUses.length; slot++) {
            int current = remainingUses(inventory.getItem(slot));
            this.toolUses[slot] = current == Integer.MAX_VALUE ? current : Math.max(0, current - refresh.toolCosts[slot]);
            this.rawDirty |= refresh.toolCosts[slot] > 0;
        }
    }

    void reset() {
        this.pending = null;
        this.pendingGuard = null;
        this.pendingGuardReceipt = -1L;
        this.refresh = null;
        this.initialValidated = false;
        this.connection = null;
        this.needsToolPrewarm = false;
        this.rawDirty = false;
        java.util.Arrays.fill(this.counts, 0);
        java.util.Arrays.fill(this.toolUses, 0);
        this.toolSync.reset();
    }

    private boolean safe() {
        return this.safeContext() && !this.isSettling();
    }

    private boolean safeContext() {
        var player = this.client.player;
        return player != null && this.client.level != null && this.client.gameMode != null
                && player.inventoryMenu != null && player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.getCarried().isEmpty();
    }

    boolean preparePistons() {
        if (!this.safe()) return false;
        if (!this.initialValidated) return this.synchronize(null);
        if (InventoryUtils.getOffhandStack(this.client.player).is(Items.PISTON)) {
            return this.availablePistons() > 0 || this.synchronize(null);
        }
        if (!BedrockInventory.hasAtLeast(Items.PISTON, 1)) {
            this.reconcileInventoryIfDirty();
            return false;
        }
        return this.synchronize(this.rawDirty ? null : () -> this.access.selectOffhand(Items.PISTON));
    }

    boolean preparePistons(int required) { return this.preparePistons() && this.availablePistons() >= required; }

    int availablePistons() {
        return this.availableCount(40);
    }

    int availableCount(int slot) {
        if (this.client.player == null || slot < 0 || slot >= this.counts.length) return 0;
        int current = this.client.player.getInventory().getItem(slot).getCount();
        return this.initialValidated ? Math.min(current, this.counts[slot]) : 0;
    }

    int availableToolUses() {
        var tool = this.client.player == null ? net.minecraft.world.item.ItemStack.EMPTY : this.client.player.getMainHandItem();
        int current = remainingUses(tool);
        int slot = this.client.player == null ? -1 : InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        return !this.initialValidated || slot < 0 || slot >= this.toolUses.length
                ? 0 : Math.min(current, this.toolUses[slot]);
    }

    boolean canReserveToolUses(int requiredUses) {
        int available = this.availableToolUses();
        if (requiredUses <= 0 || available < requiredUses) return false;
        var runtime = RuntimeAccess.currentOrNull();
        var service = runtime == null ? null : runtime.toolSwitchService();
        if (service == null || !service.isDurabilityGuardActive()) return true;
        ItemStack projected = this.client.player.getMainHandItem().copy();
        if (projected.isDamageableItem()) {
            // The guard evaluates the tool before this use, including all earlier reserved uses.
            projected.setDamageValue(projected.getMaxDamage() - (available - requiredUses + 1));
        }
        return service.isToolUsable(projected);
    }

    void recordToolUse() {
        if (this.client.player == null) return;
        this.recordToolUse(InventoryUtils.getSelectedSlot(this.client.player.getInventory()));
    }

    void recordToolUse(int slot) {
        if (slot >= 0 && slot < this.toolUses.length && this.toolUses[slot] < Integer.MAX_VALUE) {
            this.toolUses[slot] = Math.max(0, Math.min(this.toolUses[slot],
                    remainingUses(this.client.player.getInventory().getItem(slot))) - 1);
        }
        if (this.refresh != null && slot >= 0 && slot < this.toolUses.length) this.refresh.toolCosts[slot]++;
        this.rawDirty = true;
    }

    void recordRawUses(int pistons, int torchSlot, int torches) {
        this.debitCount(40, pistons);
        InventoryUtils.getOffhandStack(this.client.player).shrink(pistons);
        if (torches > 0) {
            this.debitCount(torchSlot, torches);
            this.client.player.getInventory().getItem(torchSlot).shrink(torches);
        }
    }

    void recordPredictedUse() {
        int slot = InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        if (slot < 0 || slot >= this.counts.length) return;
        // Vanilla has already predicted this cost; do not subtract it from that result again.
        this.counts[slot] = Math.min(Math.max(0, this.counts[slot] - 1),
                this.client.player.getInventory().getItem(slot).getCount());
        if (this.refresh != null) this.refresh.countCosts[slot]++;
        this.rawDirty = true;
    }

    private void debitCount(int slot, int used) {
        if (used <= 0 || slot < 0 || slot >= this.counts.length) return;
        this.counts[slot] = Math.max(0, Math.min(this.counts[slot],
                this.client.player.getInventory().getItem(slot).getCount()) - used);
        if (this.refresh != null) this.refresh.countCosts[slot] += used;
        this.rawDirty = true;
    }

    private void rebuildBudgets() {
        var inventory = this.client.player.getInventory();
        for (int slot = 0; slot < this.counts.length; slot++) this.counts[slot] = inventory.getItem(slot).getCount();
        for (int slot = 0; slot < this.toolUses.length; slot++) this.toolUses[slot] = remainingUses(inventory.getItem(slot));
        this.rawDirty = false;
    }

    private static int remainingUses(net.minecraft.world.item.ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.isDamageableItem()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue()) : Integer.MAX_VALUE;
    }

    boolean prepareMaterial(Item item) {
        if (item == null || !this.safe()) return false;
        if (!this.preparePistons()) return false;
        if (this.access.materialRequiresInventorySwap(item)) {
            return this.synchronize(this.rawDirty ? null : () -> this.selectMaterial(item));
        }
        int previousSlot = InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        boolean ready = this.selectMaterial(item);
        if (!ready) {
            this.reconcileInventoryIfDirty();
            return false;
        }
        // A real hotbar change has sent SetCarried; the already-held early return has not.
        if (previousSlot == InventoryUtils.getSelectedSlot(this.client.player.getInventory())) InventoryUtils.syncSelectedHotbarSlot();
        return ready && (this.availableCount(InventoryUtils.getSelectedSlot(this.client.player.getInventory())) > 0
                || this.synchronize(null));
    }

    void confirmToolSwitch() {
        this.toolSync.invalidateTool();
        if (this.safe()) this.synchronize(null);
    }

    boolean prepareTool(BlockState state, boolean cleanup) {
        if (state == null || !this.safe()) return false;
        if (!this.initialValidated) return this.synchronize(null);
        if (this.access.toolRequiresInventorySwap(state, cleanup)) {
            return this.synchronize(this.rawDirty ? null : () -> this.selectTool(state, cleanup));
        }
        boolean ready = this.selectTool(state, cleanup);
        var runtime = RuntimeAccess.currentOrNull();
        var service = runtime == null ? null : runtime.toolSwitchService();
        if (ready && this.refresh != null && service != null && service.isDurabilityGuardActive()
                && !service.isToolUsable(this.client.player.getMainHandItem())) return false;
        if (ready && this.guardedToolNeedsCalibration(1)) return this.synchronize(null);
        return ready && (this.availableToolUses() > 0 || this.synchronize(null));
    }

    private boolean guardedToolNeedsCalibration(int requiredUses) {
        if (!this.rawDirty) return false;
        var runtime = RuntimeAccess.currentOrNull();
        var service = runtime == null ? null : runtime.toolSwitchService();
        if (service == null || !service.isDurabilityGuardActive()) return false;
        ItemStack visible = this.client.player.getMainHandItem();
        int available = this.availableToolUses();
        if (!visible.isDamageableItem() || available >= remainingUses(visible) || !service.isToolUsable(visible)) return false;
        return !this.canReserveToolUses(requiredUses);
    }

    private boolean selectMaterial(Item item) {
        this.toolSync.onMainHandSelection();
        try {
            boolean ready = this.access.selectMaterial(item);
            this.needsToolPrewarm = true;
            return ready;
        } finally { this.toolSync.onMainHandSelection(); }
    }

    private boolean selectTool(BlockState state, boolean cleanup) {
        this.toolSync.onMainHandSelection();
        try { return this.access.selectTool(state, cleanup); }
        finally { this.toolSync.onMainHandSelection(); }
    }

    boolean prepareCriticalToolTick() {
        if (!this.safe()) return false;
        this.needsToolPrewarm = false;
        return this.toolSync.prepare();
    }

    void prewarmCriticalTool() {
        if (this.needsToolPrewarm
                && this.prepareTool(net.minecraft.world.level.block.Blocks.PISTON.defaultBlockState(), false)) {
            this.prepareCriticalToolTick();
        }
    }

    /** The dispatcher chooses when material preparation is complete and the tool can be warmed. */
    void onInitialPreparationSent(boolean changedMainHand) {
        this.observeConnection();
        if (changedMainHand) this.toolSync.invalidateTool();
        this.needsToolPrewarm = true;
    }

    void acknowledgeToolTick(Object connection, int sequence) { this.toolSync.acknowledged(connection, sequence); }

    /** Prepares the response before the relative move, then waits even if selection returned false. */
    private boolean synchronize(Runnable move) {
        if (!this.safe() || this.refresh != null || !NetworkUtils.canSendPacketBatch()) return false;
        var snapshot = InventorySyncPackets.createPlayerInventoryFullSync(this.client.player);
        if (snapshot == null) return false;
        var fence = InventorySyncPackets.prepareFence(this.currentConnection());
        if (fence == null) return false;
        try {
            if (move != null) move.run();
        } finally {
            boolean sent = false;
            try {
                sent = NetworkUtils.sendPacketBatch(List.of(snapshot, fence.packet()));
                if (sent) this.record(fence);
            } finally {
                // A failed transport must recover a snapshot before retrying any relative move.
                if (!sent) this.initialValidated = false;
            }
        }
        return false;
    }

    private static final class BudgetRefresh {
        final InventorySyncPackets.SyncFence fence;
        final int[] countCosts = new int[41];
        final int[] toolCosts = new int[9];

        BudgetRefresh(InventorySyncPackets.SyncFence fence) { this.fence = fence; }
    }
}
