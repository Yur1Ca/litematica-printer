package me.aleksilassila.litematica.printer.interaction;

import me.aleksilassila.litematica.printer.handler.handlers.MineDebugLog;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stateful mining protocol. The Mixin only exposes Minecraft fields through {@link MiningInteractionPort}. */
public final class MiningInteractionController {
    private static final int MIN_PENDING_DESTROY_TICKS = 8;
    private static final int MAX_PENDING_DESTROY_TICKS = 200;
    private static final int SAFETY_MARGIN = 5;

    private final MiningInteractionPort port;
    private final MiningFeedback feedback;
    private final ToolSwitchService toolSwitchService;
    private final HeldToolMiningProgress heldToolProgress = new HeldToolMiningProgress();
    private final Map<BlockPos, DelayedDestroyEntry> pendingDelayedDestroys = new LinkedHashMap<>();
    private BlockPos activeDestroyPos;
    private long activeDestroyStartTick = Long.MIN_VALUE;
    private int activeReservedSlot = -1;
    private ItemStack activeReservedTool = ItemStack.EMPTY;
    private int projectedDurabilityCost;
    private boolean suppressFastBreak;
    private BlockPos lastLoggedProgressPos;
    private long lastLoggedProgressTick = Long.MIN_VALUE;

    public MiningInteractionController(MiningInteractionPort port, ToolSwitchService toolSwitchService) {
        this.port = port;
        this.toolSwitchService = toolSwitchService;
        this.feedback = new MiningFeedback(port.client());
    }

    public void reset() {
        this.heldToolProgress.reset();
        LocalPlayer player = this.port.client().player;
        if (this.port.isDestroying()) this.clearDestroyProgress(player, this.port.destroyPos());
        for (DelayedDestroyEntry entry : this.pendingDelayedDestroys.values()) {
            if (entry.pos != null) this.clearDestroyProgress(player, entry.pos);
        }
        this.port.isDestroying(false);
        this.port.destroyProgress(0.0F);
        this.port.destroyPos(BlockPos.ZERO);
        this.port.destroyingItem(ItemStack.EMPTY);
        this.pendingDelayedDestroys.clear();
        this.activeDestroyPos = null;
        this.activeDestroyStartTick = Long.MIN_VALUE;
        this.activeReservedSlot = -1;
        this.activeReservedTool = ItemStack.EMPTY;
        this.projectedDurabilityCost = 0;
        this.suppressFastBreak = false;
        this.lastLoggedProgressPos = null;
        this.lastLoggedProgressTick = Long.MIN_VALUE;
        this.feedback.reset();
    }

    public boolean isPendingDelayedDestroy(BlockPos pos) {
        return this.feedback.hasPending(pos);
    }

    public void confirmServerBlockUpdate(BlockPos pos) {
        this.confirmServerBlockUpdate(pos, false);
    }

    public void confirmServerBlockUpdate(BlockPos pos, boolean confirmedAir) {
        if (pos == null) return;
        boolean finishedHold = confirmedAir && pos.equals(this.activeDestroyPos)
                && this.port.isDestroying() && pos.equals(this.port.destroyPos());
        if (!finishedHold && !this.feedback.hasPending(pos)) return;
        ClientLevel level = this.port.client().level;
        if (level != null && level.getBlockState(pos).isAir()) {
            // A server-side instant break can finish our slow hold before the next local tick.
            if (finishedHold) {
                this.resetDestroyState(this.port.client().player, pos);
                this.feedback.resetHitSound();
            }
            this.feedback.removePending(pos);
            DelayedDestroyEntry removed = this.pendingDelayedDestroys.remove(pos);
            if (removed != null) {
                this.clearDestroyProgress(this.port.client().player, pos);
            }
        }
    }

    public void tick() {
        LocalPlayer player = this.port.client().player;
        ClientLevel level = this.port.client().level;
        if (player == null || level == null) return;
        this.feedback.cleanupPending(level, this.clientTick());
        this.projectedDurabilityCost = 0;
        this.suppressFastBreak = false;
        Iterator<Map.Entry<BlockPos, DelayedDestroyEntry>> iterator = this.pendingDelayedDestroys.entrySet().iterator();
        while (iterator.hasNext()) {
            DelayedDestroyEntry entry = iterator.next().getValue();
            BlockPos pos = entry.pos;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                this.feedback.removePending(pos);
                iterator.remove();
                this.clearDestroyProgress(player, pos);
                continue;
            }
            if (!entry.localPrediction) {
                if (this.clientTick() >= entry.deadline) {
                    this.feedback.removePending(pos);
                    iterator.remove();
                    if (entry.mineTarget) this.port.onPendingDestroyExpired(pos);
                }
                continue;
            }
            if (!this.feedback.hasPending(pos)) {
                iterator.remove();
                this.clearDestroyProgress(player, pos);
                if (entry.mineTarget && this.clientTick() >= entry.deadline) this.port.onPendingDestroyExpired(pos);
            }
        }
    }

    public BlockBreakResult continueForMine(BlockPos pos, Direction direction, boolean allowToolSwitch) {
        LocalPlayer player = this.port.client().player;
        ClientLevel level = this.port.client().level;
        if (player == null || level == null || this.port.client().gameMode == null) return BlockBreakResult.FAILED;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            this.feedback.removePending(pos);
            this.pendingDelayedDestroys.remove(pos);
            if (this.port.isDestroying() && this.port.matchesDestroyTarget(pos)) this.resetDestroyState(player, pos);
            this.feedback.resetHitSound();
            return BlockBreakResult.COMPLETED;
        }
        if (!level.getWorldBorder().isWithinBounds(pos)) return BlockBreakResult.FAILED;
        // A tool/config change must not let the fast path bypass an outstanding confirmation.
        if (this.pendingDelayedDestroys.containsKey(pos) || this.feedback.hasPending(pos)) {
            return BlockBreakResult.IN_PROGRESS;
        }
        if (this.port.isInventorySwitchPending()) {
            return BlockBreakResult.IN_PROGRESS;
        }
        this.heldToolProgress.observeHand(player);
        BlockBreakResult preparation = this.prepareTool(pos, state, allowToolSwitch);
        if (preparation != null) return preparation;
        if (this.port.isDestroying() && pos.equals(this.activeDestroyPos) && pos.equals(this.port.destroyPos())) {
            return this.continueDestroy(true, pos, direction, false, allowToolSwitch, true);
        }
        this.port.ensureCarriedItemSent();
        float progress = this.heldToolProgress.progress(state, player, level, pos);
        progress = this.stopProgress(progress, state, player, level, pos, true);
        // The server breaks on STOP when delta * (elapsed + 1) >= 0.7 regardless of any config.
        // Gating the fast path on the configurable threshold made blocks whose delta sits just below
        // the threshold (e.g. 0.777 < 0.78) fall into the slow path and get capped at 10/s.
        // Use the server's own 0.7 rule so mining speed no longer depends on the threshold value.
        boolean fast = player.getAbilities().instabuild
                || progress >= 0.7F;
        if (fast && !player.getAbilities().instabuild && !this.nativeProtectionEnabled()
                && this.toolSwitchService.isDurabilityGuardActive()) {
            ItemStack held = player.getMainHandItem();
            if (held.isDamageableItem()) {
                int remaining = held.getMaxDamage() - held.getDamageValue();
                if (remaining - this.projectedDurabilityCost <= SAFETY_MARGIN) {
                    fast = false;
                    // Hold the downgrade through continueDestroy so its fast gate doesn't re-enable it.
                    this.suppressFastBreak = true;
                }
            }
        }
        if (fast) {
            if (!this.toolSwitchService.reserveForBreak(pos, state)) return BlockBreakResult.BLOCKED;
            boolean delayed = delayedConfirmation(false);
            if (this.port.isDestroying()) this.resetDestroyState(player, this.port.destroyPos());
            this.feedback.resetHitSound();
            this.feedback.playHitSound(player, level, state, pos, true);
            if (progress >= 0.7F) {
                this.feedback.playBreakSound(pos, state);
            }
            if (delayed) this.queuePendingDestroy(pos, true, true);
            else if (!player.getAbilities().instabuild && progress < 1.0F) this.queuePendingDestroy(pos, false, true);
            this.sendStart(pos, direction, true);
            if (!player.getAbilities().instabuild) {
                if (progress >= 1.0F && !delayed) {
                    NetworkUtils.sendPacket(sequence -> {
                        this.port.destroyBlock(pos);
                        return this.port.actionPacket(Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
                    });
                } else {
                    NetworkUtils.sendPacket(sequence -> this.port.actionPacket(Action.STOP_DESTROY_BLOCK, pos, direction, sequence));
                }
                ItemStack held = player.getMainHandItem();
                if (held.isDamageableItem()) {
                    this.projectedDurabilityCost++;
                }
            }
            this.feedback.resetHitSound();
            if (delayed) return BlockBreakResult.COMPLETED_WAIT;
            return player.getAbilities().instabuild || progress >= 1.0F
                    ? BlockBreakResult.COMPLETED
                    : BlockBreakResult.COMPLETED_WAIT;
        }
        BlockBreakResult result = this.continueDestroy(true, pos, direction, false, allowToolSwitch, true);
        if (result == BlockBreakResult.FAILED) return result;
        return this.pendingDelayedDestroys.containsKey(pos) || this.feedback.hasPending(pos)
                ? BlockBreakResult.IN_PROGRESS : result;
    }

    public BlockBreakResult continueDestroy(
            boolean localPrediction,
            BlockPos pos,
            Direction direction,
            boolean forceDelayedDestroy,
            boolean allowToolSwitch
    ) {
        return this.continueDestroy(
                localPrediction, pos, direction, forceDelayedDestroy, allowToolSwitch, false);
    }

    private BlockBreakResult continueDestroy(
            boolean localPrediction,
            BlockPos pos,
            Direction direction,
            boolean forceDelayedDestroy,
            boolean allowToolSwitch,
            boolean miningFeedback
    ) {
        LocalPlayer player = this.port.client().player;
        ClientLevel level = this.port.client().level;
        if (player == null || level == null || this.port.client().gameMode == null) return BlockBreakResult.FAILED;
        boolean delayedConfirmation = delayedConfirmation(forceDelayedDestroy);
        boolean localEffects = localPrediction || forceDelayedDestroy;
        boolean localRemoval = localPrediction && !delayedConfirmation;
        boolean manualSound = miningFeedback || forceDelayedDestroy || !localPrediction;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            this.feedback.removePending(pos);
            this.feedback.resetHitSound();
            MineDebugLog.write("mine break completed pos=" + MineDebugLog.pos(pos) + " path=air_or_liquid");
            return BlockBreakResult.COMPLETED;
        }
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            MineDebugLog.write("mine break failed pos=" + MineDebugLog.pos(pos) + " reason=out_of_world_border");
            return BlockBreakResult.FAILED;
        }
        if (player.getAbilities().instabuild) {
            if (manualSound) this.feedback.playHitSound(player, level, state, pos, true);
            NetworkUtils.sendPacket(sequence -> {
                if (localRemoval) this.port.destroyBlock(pos);
                return this.port.actionPacket(Action.START_DESTROY_BLOCK, pos, direction, sequence);
            });
            this.feedback.resetHitSound();
            return BlockBreakResult.COMPLETED;
        }
        BlockBreakResult preparation = this.prepareTool(pos, state, allowToolSwitch);
        if (preparation != null) return preparation;
        this.port.ensureCarriedItemSent();
        if (this.feedback.hasPending(pos) && !this.pendingDelayedDestroys.containsKey(pos)) {
            return BlockBreakResult.COMPLETED_WAIT;
        }
        if (this.pendingDelayedDestroys.containsKey(pos)) {
            return this.port.isDestroying() ? BlockBreakResult.IN_PROGRESS : BlockBreakResult.COMPLETED;
        }
        if (this.port.isDestroying() && !pos.equals(this.port.destroyPos())) this.abortPrevious(player, pos, direction);
        if (this.port.isDestroying() && pos.equals(this.port.destroyPos())) {
            long now = this.clientTick();
            if (!pos.equals(this.activeDestroyPos) || this.activeDestroyStartTick == Long.MIN_VALUE) {
                // A native session can predate this controller's observation; start conservatively.
                this.activeDestroyPos = pos.immutable();
                this.activeDestroyStartTick = now;
            }
            long elapsed = Math.max(0L, now - this.activeDestroyStartTick);
            float delta = this.destroyProgress(state, player, level, pos, miningFeedback);
            this.port.destroyProgress(delta * (elapsed + 1L));
            if (manualSound) this.feedback.playHitSound(player, level, state, pos, false);
            if (localEffects) level.destroyBlockProgress(player.getId(), pos, this.destroyStage());
            // STOP uses the current delta over elapsed game ticks. Jumping can lower that delta,
            // and several callbacks in the same tick cannot add mining time.
            float threshold = completionThreshold(forceDelayedDestroy);
            if (this.port.destroyProgress() >= threshold
                    && this.stopProgress(delta, state, player, level, pos, miningFeedback) * (elapsed + 1L) >= threshold) {
                if (miningFeedback || !localRemoval) this.feedback.playBreakSound(pos, state);
                NetworkUtils.sendPacket(sequence -> {
                    if (delayedConfirmation) this.queuePendingDestroy(pos, localPrediction, miningFeedback);
                    if (localRemoval) this.port.destroyBlock(pos);
                    this.resetDestroyState(player, pos);
                    return this.port.actionPacket(Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
                });
                if (localEffects) level.destroyBlockProgress(player.getId(), pos, -1);
                this.feedback.resetHitSound();
                return BlockBreakResult.COMPLETED;
            }
            this.logProgress(pos);
            return BlockBreakResult.IN_PROGRESS;
        }
        if (this.port.isDestroying()) this.abortPrevious(player, pos, direction);
        if (this.port.destroyProgress() == 0.0F && localEffects) state.attack(level, pos, player);
        if (manualSound) this.feedback.playHitSound(player, level, state, pos, true);
        float progress = this.destroyProgress(state, player, level, pos, miningFeedback);
        // Server breaks on same-tick START+STOP whenever delta >= 0.7, independent of the
        // configurable threshold. Keep this fast path so delta>=0.7 blocks are never dragged
        // down to the hold path (and don't pay a per-tick packet cost either). The session-level
        // The session-level BREAK_BLOCKS_PER_TICK budget (MineToolSession) is the per-tick batch
        // ceiling; here only suppressFastBreak propagates the durability downgrade from
        // continueForMine so the Tweakeroo near-broken-tool protection is never punched through.
        float threshold = completionThreshold(forceDelayedDestroy);
        if (!this.suppressFastBreak && progress >= threshold
                && this.stopProgress(progress, state, player, level, pos, miningFeedback) >= threshold) {
            if (!this.toolSwitchService.reserveForBreak(pos, state)) return BlockBreakResult.BLOCKED;
            if (miningFeedback || !localRemoval) this.feedback.playBreakSound(pos, state);
            this.sendStart(pos, direction, miningFeedback);
            NetworkUtils.sendPacket(sequence -> {
                if (delayedConfirmation) this.queuePendingDestroy(pos, localPrediction, miningFeedback);
                if (localRemoval) this.port.destroyBlock(pos);
                this.resetDestroyState(player, pos);
                return this.port.actionPacket(Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
            });
            if (localEffects) level.destroyBlockProgress(player.getId(), pos, -1);
            this.feedback.resetHitSound();
            return BlockBreakResult.COMPLETED;
        }
        if (!localPrediction) {
            if (!this.toolSwitchService.reserveForBreak(pos, state)) return BlockBreakResult.BLOCKED;
            // Server-authoritative callers (e.g. bedrock cleanup) need START before STOP so the
            // server tracks this position and can finish a slower break. The client waits for the
            // server update instead of removing the block locally. Cleanup handles several positions
            // per tick, so it cannot hold one mining session open across ticks.
            this.sendStart(pos, direction, miningFeedback);
            NetworkUtils.sendPacket(sequence -> {
                this.queuePendingDestroy(pos, false, miningFeedback);
                this.resetDestroyState(player, pos);
                return this.port.actionPacket(Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
            });
            level.destroyBlockProgress(player.getId(), pos, this.destroyStage());
            this.feedback.resetHitSound();
            return BlockBreakResult.COMPLETED_WAIT;
        }
        // delta < 0.7 with local prediction: open a hold-OPEN session (single server mining slot).
        // Later calls use the current delta and game ticks since this START, then STOP breaks it
        // through the server's accumulated elapsed. This is the only path that avoids
        // the failedToMine queue, which auto-breaks at progress>=1.0 → the 10/s single-slot dead end.
        if (!this.toolSwitchService.reserveForBreak(pos, state)) return BlockBreakResult.BLOCKED;
        this.sendStart(pos, direction, miningFeedback);
        this.activeDestroyPos = pos.immutable();
        this.activeDestroyStartTick = this.clientTick();
        this.activeReservedSlot = InventoryUtils.getSelectedSlot(player.getInventory());
        this.activeReservedTool = player.getMainHandItem().copy();
        this.port.isDestroying(true);
        this.port.destroyPos(pos);
        this.port.destroyProgress(progress);
        this.port.destroyingItem(player.getMainHandItem());
        if (localEffects) level.destroyBlockProgress(player.getId(), pos, this.destroyStage());
        return BlockBreakResult.IN_PROGRESS;
    }

    private static boolean delayedConfirmation(boolean forceDelayedDestroy) {
        return forceDelayedDestroy || Configs.Break.BREAK_USE_DELAYED_DESTROY.getBooleanValue();
    }

    private static float completionThreshold(boolean forceDelayedDestroy) {
        return delayedConfirmation(forceDelayedDestroy) ? ConfigUtils.getBreakProgressThreshold() : 0.7F;
    }

    private void queuePendingDestroy(BlockPos pos, boolean localPrediction, boolean mineTarget) {
        long startTick = this.clientTick();
        var client = this.port.client();
        float progress = this.destroyProgress(client.level.getBlockState(pos), client.player, client.level, pos, mineTarget);
        long deadline = startTick + pendingDestroyTimeout(progress);
        this.pendingDelayedDestroys.put(pos.immutable(),
                new DelayedDestroyEntry(pos.immutable(), localPrediction, deadline, mineTarget));
        this.feedback.addPending(pos, deadline);
    }

    private float destroyProgress(BlockState state, LocalPlayer player, ClientLevel level, BlockPos pos, boolean mineTarget) {
        return mineTarget ? this.heldToolProgress.progress(state, player, level, pos)
                : state.getDestroyProgress(player, level, pos);
    }

    private float stopProgress(float delta, BlockState state, LocalPlayer player, ClientLevel level, BlockPos pos, boolean mineTarget) {
        return mineTarget ? this.heldToolProgress.stopProgress(delta, state, player, level, pos,
                this.toolSwitchService.lastAcknowledgedMiningAction()) : delta;
    }

    private static int pendingDestroyTimeout(float progress) {
        if (progress <= 0.0F) {
            return MAX_PENDING_DESTROY_TICKS;
        }
        int estimatedTicks = (int) Math.ceil(1.0F / progress);
        return Math.max(MIN_PENDING_DESTROY_TICKS, Math.min(estimatedTicks + 10, MAX_PENDING_DESTROY_TICKS));
    }

    private void abortPrevious(LocalPlayer player, BlockPos next, Direction direction) {
        BlockPos previous = this.port.destroyPos();
        NetworkUtils.sendPacket(this.port.actionPacket(Action.ABORT_DESTROY_BLOCK, previous, direction, 0));
        MineDebugLog.write("mine break abort previous=" + MineDebugLog.pos(previous) + " next=" + MineDebugLog.pos(next));
        this.resetDestroyState(player, previous);
        this.feedback.resetHitSound();
    }

    private void resetDestroyState(LocalPlayer player, BlockPos pos) {
        this.activeDestroyPos = null;
        this.activeDestroyStartTick = Long.MIN_VALUE;
        this.activeReservedSlot = -1;
        this.activeReservedTool = ItemStack.EMPTY;
        this.port.isDestroying(false);
        this.port.destroyProgress(0.0F);
        this.clearDestroyProgress(player, pos);
    }

    private boolean nativeProtectionEnabled() {
        return Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue() > 0;
    }

    /** null means ready; an existing paid hold keeps its credit until the target/tool changes. */
    private BlockBreakResult prepareTool(BlockPos pos, BlockState state, boolean allowToolSwitch) {
        var player = this.port.client().player;
        if (this.nativeProtectionEnabled() && this.port.isDestroying()) {
            boolean same = pos.equals(this.activeDestroyPos)
                    && this.activeReservedSlot == InventoryUtils.getSelectedSlot(player.getInventory());
            ItemStack held = player.getMainHandItem();
            if (same) {
                ItemStack compared = this.activeReservedTool.copy();
                if (compared.isDamageableItem()) compared.setDamageValue(held.getDamageValue());
                same = ItemStack.matches(compared, held);
            }
            if (same && this.toolSwitchService.canContinueReservedBreak(pos, state)) return null;
            this.abortPrevious(player, pos, Direction.DOWN);
        }
        var prepared = this.toolSwitchService.prepareForBreak(pos, state, allowToolSwitch);
        return switch (prepared) {
            case READY -> null;
            case BLOCKED_BY_DURABILITY -> BlockBreakResult.BLOCKED;
            default -> BlockBreakResult.IN_PROGRESS;
        };
    }

    private void clearDestroyProgress(LocalPlayer player, BlockPos pos) {
        if (player == null || pos == null || this.port.client().level == null) return;
        try {
            this.port.client().level.destroyBlockProgress(player.getId(), pos, -1);
        } catch (IllegalStateException ignored) {
        }
    }

    private int destroyStage() {
        float progress = this.port.destroyProgress() >= 0.7F
                ? 1.0F : this.port.destroyProgress();
        return progress > 0.0F ? (int) (progress * 10.0F) : -1;
    }

    private void sendStart(BlockPos pos, Direction direction, boolean mineTarget) {
        if (this.port.client().getConnection() == null) return;
        NetworkUtils.sendPacket(sequence -> {
            if (mineTarget) this.heldToolProgress.started(sequence);
            return this.port.actionPacket(Action.START_DESTROY_BLOCK, pos, direction, sequence);
        });
    }

    private void logProgress(BlockPos pos) {
        long tick = this.clientTick();
        if (!pos.equals(this.lastLoggedProgressPos) || tick - this.lastLoggedProgressTick >= 10) {
            MineDebugLog.write("mine break in_progress pos=" + MineDebugLog.pos(pos)
                    + " progress=" + this.port.destroyProgress());
            this.lastLoggedProgressPos = pos;
            this.lastLoggedProgressTick = tick;
        }
    }

    private long clientTick() {
        ClientLevel level = this.port.client().level;
        return level == null ? 0L : level.getGameTime();
    }

    private static final class DelayedDestroyEntry {
        final BlockPos pos;
        final boolean localPrediction;
        final long deadline;
        final boolean mineTarget;

        DelayedDestroyEntry(BlockPos pos, boolean localPrediction, long deadline, boolean mineTarget) {
            this.pos = pos;
            this.localPrediction = localPrediction;
            this.deadline = deadline;
            this.mineTarget = mineTarget;
        }
    }
}
