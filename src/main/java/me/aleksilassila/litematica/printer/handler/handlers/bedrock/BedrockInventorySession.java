package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
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
        boolean selectMaterial(Item item);
        boolean toolRequiresInventorySwap(BlockState state, boolean cleanup);
        boolean selectTool(BlockState state, boolean cleanup);
    }

    private final Minecraft client;
    private final boolean dedicatedPistonHand;
    private final InventoryAccess access;
    private final BedrockToolSync toolSync;
    private Object connection;
    private InventorySyncPackets.SyncFence pending;
    private boolean initialValidated;
    private boolean needsToolPrewarm;
    private boolean rawDirty;
    private final int[] counts = new int[41];
    private final int[] toolUses = new int[9];

    BedrockInventorySession(Minecraft client, boolean dedicatedPistonHand) {
        this(client, dedicatedPistonHand, new InventoryAccess() {
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

    BedrockInventorySession(Minecraft client, boolean dedicatedPistonHand, InventoryAccess access) {
        this.client = client;
        this.dedicatedPistonHand = dedicatedPistonHand;
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
        }
        return false;
    }

    boolean isToolSettling() {
        this.observeConnection();
        return this.dedicatedPistonHand && this.toolSync.isPending();
    }

    boolean reconcileInventoryIfDirty() {
        if (!this.dedicatedPistonHand || !this.safeContext()) return false;
        if (this.isSettling()) return this.rawDirty;
        if (!this.rawDirty) return false;
        this.synchronize(null);
        return this.isSettling();
    }

    private void record(InventorySyncPackets.SyncFence fence) {
        this.observeConnection();
        this.pending = fence;
    }

    void reset() {
        this.pending = null;
        this.initialValidated = false;
        this.connection = null;
        this.needsToolPrewarm = false;
        this.rawDirty = false;
        java.util.Arrays.fill(this.counts, 0);
        java.util.Arrays.fill(this.toolUses, 0);
        this.toolSync.reset();
    }

    InteractionHand placementHand() {
        return this.dedicatedPistonHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
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
        if (this.dedicatedPistonHand && !this.initialValidated) return this.synchronize(null);
        if (InventoryUtils.getOffhandStack(this.client.player).is(Items.PISTON)) {
            return !this.dedicatedPistonHand || this.availablePistons() > 0 || this.synchronize(null);
        }
        if (this.dedicatedPistonHand && !BedrockInventory.hasAtLeast(Items.PISTON, 1)) {
            this.reconcileInventoryIfDirty();
            return false;
        }
        if (this.dedicatedPistonHand) return this.synchronize(this.rawDirty ? null : () -> this.access.selectOffhand(Items.PISTON));
        // Legacy placement keeps its original prepare-then-return boundary.
        this.access.selectOffhand(Items.PISTON);
        return false;
    }

    boolean preparePistons(int required) { return this.preparePistons() && this.availablePistons() >= required; }

    int availablePistons() {
        return this.availableCount(40);
    }

    int availableCount(int slot) {
        if (this.client.player == null || slot < 0 || slot >= this.counts.length) return 0;
        int current = this.client.player.getInventory().getItem(slot).getCount();
        return this.dedicatedPistonHand ? this.initialValidated ? Math.min(current, this.counts[slot]) : 0 : current;
    }

    int availableToolUses() {
        var tool = this.client.player == null ? net.minecraft.world.item.ItemStack.EMPTY : this.client.player.getMainHandItem();
        int current = remainingUses(tool);
        int slot = this.client.player == null ? -1 : InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        return !this.dedicatedPistonHand ? current : !this.initialValidated || slot < 0 || slot >= this.toolUses.length
                ? 0 : Math.min(current, this.toolUses[slot]);
    }

    void recordToolUse() {
        if (!this.dedicatedPistonHand || this.client.player == null) return;
        this.recordToolUse(InventoryUtils.getSelectedSlot(this.client.player.getInventory()));
    }

    void recordToolUse(int slot) {
        if (!this.dedicatedPistonHand) return;
        if (slot >= 0 && slot < this.toolUses.length && this.toolUses[slot] < Integer.MAX_VALUE) {
            this.toolUses[slot] = Math.max(0, Math.min(this.toolUses[slot],
                    remainingUses(this.client.player.getInventory().getItem(slot))) - 1);
        }
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
        if (!this.dedicatedPistonHand) return;
        int slot = InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        if (slot < 0 || slot >= this.counts.length) return;
        // Vanilla has already predicted this cost; do not subtract it from that result again.
        this.counts[slot] = Math.min(Math.max(0, this.counts[slot] - 1),
                this.client.player.getInventory().getItem(slot).getCount());
        this.rawDirty = true;
    }

    private void debitCount(int slot, int used) {
        if (!this.dedicatedPistonHand || used <= 0 || slot < 0 || slot >= this.counts.length) return;
        this.counts[slot] = Math.max(0, Math.min(this.counts[slot],
                this.client.player.getInventory().getItem(slot).getCount()) - used);
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
        if (!this.dedicatedPistonHand) return this.access.selectOffhand(item);
        if (!this.preparePistons()) return false;
        if (this.access.materialRequiresInventorySwap(item)) {
            return this.synchronize(this.rawDirty ? null : () -> this.selectMaterial(item));
        }
        boolean ready = this.selectMaterial(item);
        if (!ready) {
            this.reconcileInventoryIfDirty();
            return false;
        }
        if (ready) InventoryUtils.syncSelectedHotbarSlot();
        return ready && (this.availableCount(InventoryUtils.getSelectedSlot(this.client.player.getInventory())) > 0
                || this.synchronize(null));
    }

    void confirmToolSwitch() {
        this.toolSync.invalidateTool();
        if (this.dedicatedPistonHand && this.safe()) this.synchronize(null);
    }

    boolean prepareTool(BlockState state, boolean cleanup) {
        if (state == null || !this.safe()) return false;
        if (!this.dedicatedPistonHand) return this.access.selectTool(state, cleanup);
        if (!this.initialValidated) return this.synchronize(null);
        if (this.access.toolRequiresInventorySwap(state, cleanup)) {
            return this.synchronize(this.rawDirty ? null : () -> this.selectTool(state, cleanup));
        }
        boolean ready = this.selectTool(state, cleanup);
        if (ready && this.guardedToolNeedsCalibration()) return this.synchronize(null);
        return ready && (this.availableToolUses() > 0 || this.synchronize(null));
    }

    private boolean guardedToolNeedsCalibration() {
        if (!this.rawDirty) return false;
        var runtime = RuntimeAccess.currentOrNull();
        var service = runtime == null ? null : runtime.toolSwitchService();
        if (service == null || !service.isDurabilityGuardActive()) return false;
        ItemStack visible = this.client.player.getMainHandItem();
        int available = this.availableToolUses();
        if (!visible.isDamageableItem() || available >= remainingUses(visible) || !service.isToolUsable(visible)) return false;
        ItemStack projected = visible.copy();
        projected.setDamageValue(projected.getMaxDamage() - available);
        return !service.isToolUsable(projected);
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
        if (!this.dedicatedPistonHand) return true;
        if (!this.safe()) return false;
        this.needsToolPrewarm = false;
        return this.toolSync.prepare();
    }

    void prewarmCriticalTool() {
        if (this.dedicatedPistonHand && this.needsToolPrewarm
                && this.prepareTool(net.minecraft.world.level.block.Blocks.PISTON.defaultBlockState(), false)) {
            this.prepareCriticalToolTick();
        }
    }

    /** Only the just-frozen preparation may select an existing hotbar tool through its own snapshot. */
    void onInitialPreparationSent(boolean changedMainHand) {
        this.observeConnection();
        if (!this.dedicatedPistonHand) return;
        if (changedMainHand) this.toolSync.invalidateTool();
        this.needsToolPrewarm = true;
        var piston = net.minecraft.world.level.block.Blocks.PISTON.defaultBlockState();
        if (!this.initialValidated || !this.safeContext() || this.access.toolRequiresInventorySwap(piston, false)) return;
        if (!this.selectTool(piston, false)) return;
        if (this.toolSync.prepare() || this.toolSync.isPending()) this.needsToolPrewarm = false;
    }

    void acknowledgeToolTick(Object connection, int sequence) { this.toolSync.acknowledged(connection, sequence); }

    /** Prepares the response before the relative move, then waits even if selection returned false. */
    private boolean synchronize(Runnable move) {
        if (!this.safe() || !NetworkUtils.canSendPacketBatch()) return false;
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
}
