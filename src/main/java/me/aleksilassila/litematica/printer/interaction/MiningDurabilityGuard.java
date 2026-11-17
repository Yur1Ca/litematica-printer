package me.aleksilassila.litematica.printer.interaction;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Conservative, fixed-size tool budgets. Only an ordered inventory fence grants new credits. */
public final class MiningDurabilityGuard {
    private static final int SLOTS = 36;
    private final Minecraft client;
    private final int[] remaining = new int[SLOTS];
    private final ItemStack[] identities = new ItemStack[SLOTS];
    private final ItemStack[] baselines = new ItemStack[SLOTS];
    private final AtomicLong mutations = new AtomicLong();
    private final AtomicReference<Swap> permittedSwap = new AtomicReference<>();
    private Object playerScope, levelScope, connectionScope;
    private boolean initialized, hasDebt, bypassed;
    private volatile boolean dirty;
    private volatile Packet<?> requestedSnapshot;
    private InventorySyncPackets.SyncFence pending;
    private long pendingMutation;
    private long calibratedMutation;
    private Swap activeSwap;

    public MiningDurabilityGuard(Minecraft client) { this.client = client; }

    public boolean enabled() { return Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue() > 0; }

    /** Prepares accounting, without rejecting the held tool before a replacement can be searched. */
    public boolean prepare() {
        this.observeScope();
        if (!this.enabled()) {
            this.bypassed = true;
            return true;
        }
        if (this.client.player == null) return false;
        if (this.client.player.getAbilities().instabuild) return true;
        if (this.initialized && this.calibratedMutation != this.mutations.get()) this.dirty = true;
        if (this.bypassed) { this.dirty = true; this.bypassed = false; }
        if (this.pending != null) return false;
        if (this.dirty) { this.requestCalibration(); return false; }
        if (!this.initialized) this.capture(this.mutations.get());
        if (this.dirty) { this.requestCalibration(); return false; }
        int slot = InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        if (!this.observeSlot(slot)) { this.requestCalibration(); return false; }
        return true;
    }

    /** Pure stack policy, also used by existing Bedrock projected-budget checks. */
    public boolean isStackUsable(ItemStack stack) {
        return !this.enabled() || !stack.isDamageableItem()
                || (long) visibleRemaining(stack) >= this.reserveFloor(stack) + maximumCost(stack);
    }

    public boolean canUse(int slot, BlockState state, BlockPos pos) {
        if (!this.enabled() || this.client.player.getAbilities().instabuild) return true;
        if (slot < 0 || slot >= SLOTS || !this.initialized || this.dirty || this.pending != null
                || this.calibratedMutation != this.mutations.get()) return false;
        ItemStack stack = this.stack(slot);
        // Material counts can change while collecting drops; they carry no durability credit.
        ItemStack baseline = this.baselines[slot];
        if (!stack.isDamageableItem() && baseline != null && !baseline.isDamageableItem()) return true;
        if (!this.observeSlot(slot)) return false;
        int cost = this.cost(stack, state, pos);
        return cost == 0 || (long) this.remaining[slot] >= this.reserveFloor(stack) + cost;
    }

    /** Exhausted reservations do not prove the held tool is worn out: calibrate it before replacing it. */
    public boolean needsCurrentCalibration(BlockState state, BlockPos pos) {
        var player = this.client.player;
        if (!this.enabled() || player == null || player.getAbilities().instabuild || !this.initialized
                || !this.hasDebt || this.dirty || this.pending != null
                || this.calibratedMutation != this.mutations.get()) return false;
        int slot = InventoryUtils.getSelectedSlot(player.getInventory());
        if (!this.observeSlot(slot)) return false;
        ItemStack stack = this.stack(slot);
        int cost = this.cost(stack, state, pos);
        long required = this.reserveFloor(stack) + cost;
        return cost > 0 && this.remaining[slot] < required && visibleRemaining(stack) >= required;
    }

    /** The current hold already paid for this target; subsequent ticks do not reserve it again. */
    public boolean canContinue(BlockState state, BlockPos pos) {
        if (!this.enabled() || this.client.player.getAbilities().instabuild) return true;
        int slot = InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        if (slot < 0 || slot >= SLOTS || this.dirty || this.pending != null
                || this.calibratedMutation != this.mutations.get() || !this.observeSlot(slot)) return false;
        ItemStack stack = this.stack(slot);
        return this.cost(stack, state, pos) == 0 || this.remaining[slot] >= this.reserveFloor(stack);
    }

    /** Called once before a new START, never for subsequent ticks of the same hold session. */
    public boolean reserve(BlockState state, BlockPos pos) {
        if (!this.enabled()) { this.bypassed = true; return true; }
        if (this.client.player.getAbilities().instabuild) return true;
        int slot = InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        if (!this.canUse(slot, state, pos)) return false;
        int cost = this.cost(this.stack(slot), state, pos);
        this.remaining[slot] -= cost;
        this.hasDebt |= cost > 0;
        return true;
    }

    /** Prefix planning for Bedrock's raw batches; this does not debit the shared budget. */
    public boolean canReserveRawToolUses(int uses) {
        if (!this.prepare()) return false;
        if (!this.enabled() || this.client.player.getAbilities().instabuild) return true;
        int slot = InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        // A lookahead beyond the affordable prefix must not start a fence and block that prefix.
        return this.covers(slot, (long) Math.max(0, uses) * maximumCost(this.stack(slot)));
    }

    /** Reserves the whole frozen raw bundle before its first packet can reach the transport. */
    public boolean reserveRawBreaks(List<Packet<?>> packets) {
        if (!this.prepare()) return false;
        if (!this.enabled() || this.client.player.getAbilities().instabuild) return true;
        long[] costs = new long[SLOTS];
        int slot = InventoryUtils.getSelectedSlot(this.client.player.getInventory());
        for (Packet<?> packet : packets) {
            if (packet instanceof ServerboundSetCarriedItemPacket carried) slot = carried.getSlot();
            else if (packet instanceof ServerboundPlayerActionPacket action
                    && action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
                if (slot < 0 || slot >= SLOTS) return false;
                BlockPos pos = action.getPos();
                costs[slot] += this.cost(this.stack(slot), this.client.level.getBlockState(pos), pos);
            }
        }
        for (int index = 0; index < SLOTS; index++) {
            if (costs[index] > 0 && !this.covers(index, costs[index])) {
                this.requestCalibration();
                return false;
            }
        }
        for (int index = 0; index < SLOTS; index++) {
            this.remaining[index] -= (int) costs[index];
            this.hasDebt |= costs[index] > 0;
        }
        return true;
    }

    private boolean covers(int slot, long cost) {
        return cost == 0 || slot >= 0 && slot < SLOTS && !this.dirty && this.pending == null
                && this.calibratedMutation == this.mutations.get() && this.observeSlot(slot)
                && this.remaining[slot] >= this.reserveFloor(this.stack(slot)) + cost;
    }

    /** A rare calibration, never a per-block wait. No deadline can make this fence succeed. */
    public boolean requestCalibration() {
        if (this.pending != null) return true;
        if (!this.dirty && !this.hasDebt) return false;
        var snapshot = InventorySyncPackets.createPlayerInventoryFullSync(this.client.player);
        if (snapshot == null || !NetworkUtils.canSendPacketBatch()) return false;
        var fence = InventorySyncPackets.prepareFence(this.connectionScope);
        if (fence == null) return false;
        this.requestedSnapshot = snapshot;
        this.pendingMutation = this.mutations.get();
        this.pending = fence;
        if (!NetworkUtils.sendPacketBatch(List.of(snapshot, fence.packet()))) {
            this.pending = null;
            this.requestedSnapshot = null;
            return false;
        }
        return true;
    }

    /** Runs at the stats reply, immediately after the preceding authoritative snapshot was applied. */
    public void onInventorySynchronized(Object connection) {
        this.observeScope();
        if (this.pending == null || !this.pending.isForConnection(connection) || !this.pending.complete()) return;
        this.pending = null;
        this.requestedSnapshot = null;
        var player = this.client.player;
        if (player == null || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()
                || this.pendingMutation != this.mutations.get()) {
            this.dirty = true;
            return;
        }
        this.capture(this.pendingMutation);
        // An input can be submitted by the transport thread while the client captures the baseline.
        this.dirty = this.pendingMutation != this.mutations.get();
    }

    /** Only signals cross-thread invalidation; transport callbacks never touch inventory arrays. */
    public void onInventoryInput(Packet<?> packet) {
        if (packet == this.requestedSnapshot) return;
        if (packet instanceof ServerboundContainerClickPacket click) {
            // The existing full-sync query is a vanilla no-op, including other consumers' queries.
            if (click.slotNum() == -1 && click.buttonNum() == 0 && click.containerInput() == ContainerInput.PICKUP) return;
            Swap permit = this.permittedSwap.get();
            if (permit != null && permit.matches(click) && this.permittedSwap.compareAndSet(permit, null)) return;
        } else if (!(packet instanceof ServerboundContainerClosePacket
                || packet instanceof ServerboundSetCreativeModeSlotPacket
                || packet instanceof ServerboundPlaceRecipePacket
                || packet instanceof ServerboundPickItemFromBlockPacket
                || packet instanceof ServerboundPickItemFromEntityPacket
                || packet instanceof ServerboundPlayerActionPacket action
                    && (action.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                        || action.getAction() == ServerboundPlayerActionPacket.Action.DROP_ITEM
                        || action.getAction() == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS))) return;
        this.mutations.incrementAndGet();
        this.dirty = true;
    }

    /** Authorizes just the known exact SWAP; other inventory moves require calibration. */
    public void beginSwap(int source, int destination) {
        Swap swap = new Swap(source, destination, this.client.player.inventoryMenu.containerId,
                this.remaining[source], this.remaining[destination]);
        this.activeSwap = swap;
        if (!this.permittedSwap.compareAndSet(null, swap)) this.dirty = true;
    }

    public void endSwap(boolean predictedApplied) {
        Swap swap = this.activeSwap;
        this.activeSwap = null;
        if (swap == null) return;
        if (!predictedApplied) {
            this.permittedSwap.compareAndSet(swap, null);
            this.dirty = true;
            return;
        }
        this.remaining[swap.source] = Math.min(swap.destinationRemaining, visibleRemaining(this.stack(swap.source)));
        this.remaining[swap.destination] = Math.min(swap.sourceRemaining, visibleRemaining(this.stack(swap.destination)));
        this.observeBaseline(swap.source);
        this.observeBaseline(swap.destination);
    }

    private boolean observeSlot(int slot) {
        if (slot < 0 || slot >= SLOTS) return false;
        ItemStack current = this.stack(slot);
        ItemStack before = this.baselines[slot];
        if (current == this.identities[slot]) {
            if (before != null && current.getDamageValue() < before.getDamageValue()) {
                this.dirty = true;
                return false;
            }
            this.remaining[slot] = Math.min(this.remaining[slot], visibleRemaining(current));
            return true;
        }
        // A normal durability receipt may replace the stack object. It only tightens the ledger.
        for (int other = 0; other < SLOTS; other++) {
            if (other != slot && !current.isEmpty() && current == this.identities[other]) {
                this.dirty = true;
                return false;
            }
        }
        boolean wear = before != null && !before.isEmpty() && !current.isEmpty()
                && current.getDamageValue() >= before.getDamageValue();
        if (wear) {
            ItemStack compared = before.copy();
            compared.setDamageValue(current.getDamageValue());
            wear = ItemStack.matches(compared, current);
        }
        if (wear) {
            this.remaining[slot] = Math.min(this.remaining[slot], visibleRemaining(current));
            this.observeBaseline(slot);
            return true;
        }
        // A copied or moved tool is not proof of replenished durability, even if its Item matches.
        this.dirty = true;
        return false;
    }

    private long reserveFloor(ItemStack stack) {
        // Vanilla has one delayed destroy slot, which can consume the then-held tool after a fence.
        return (long) Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue() + maximumCost(stack);
    }

    private int cost(ItemStack stack, BlockState state, BlockPos pos) {
        if (!stack.isDamageableItem()) return 0;
        if (!(stack.getItem() instanceof ShearsItem) && state != null && this.client.level != null
                && state.getDestroySpeed(this.client.level, pos) == 0.0F) return 0;
        return maximumCost(stack);
    }

    private static int maximumCost(ItemStack stack) {
        if (!stack.isDamageableItem()) return 0;
        var tool = stack.get(DataComponents.TOOL);
        int cost = tool == null ? 1 : Math.max(0, tool.damagePerBlock());
        return stack.getItem() instanceof ShearsItem ? Math.max(1, cost) : cost;
    }

    private static int visibleRemaining(ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.isDamageableItem()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue()) : Integer.MAX_VALUE;
    }

    private ItemStack stack(int slot) {
        var player = this.client.player;
        return slot == InventoryUtils.getSelectedSlot(player.getInventory())
                ? player.getMainHandItem() : player.getInventory().getItem(slot);
    }

    private void observeBaseline(int slot) {
        ItemStack current = this.stack(slot);
        this.identities[slot] = current;
        this.baselines[slot] = current.copy();
    }

    private void capture(long mutation) {
        for (int slot = 0; slot < SLOTS; slot++) {
            this.remaining[slot] = visibleRemaining(this.stack(slot));
            this.observeBaseline(slot);
        }
        this.initialized = true;
        this.hasDebt = false;
        this.calibratedMutation = mutation;
        this.dirty = mutation != this.mutations.get();
    }

    private void observeScope() {
        var listener = this.client.getConnection();
        Object connection = listener == null ? null : listener.getConnection();
        if (this.playerScope == this.client.player && this.levelScope == this.client.level
                && this.connectionScope == connection) return;
        this.reset();
        this.playerScope = this.client.player;
        this.levelScope = this.client.level;
        this.connectionScope = connection;
    }

    public void reset() {
        this.initialized = this.hasDebt = this.bypassed = this.dirty = false;
        this.pending = null;
        this.requestedSnapshot = null;
        this.activeSwap = null;
        this.permittedSwap.set(null);
        this.playerScope = this.levelScope = this.connectionScope = null;
    }

    private record Swap(int source, int destination, int menu, int sourceRemaining, int destinationRemaining) {
        boolean matches(ServerboundContainerClickPacket click) {
            int menuSlot = this.source < 9 ? this.source + 36 : this.source;
            return click.containerId() == this.menu && click.slotNum() == menuSlot
                    && click.buttonNum() == this.destination && click.containerInput() == ContainerInput.SWAP;
        }
    }
}
