package me.aleksilassila.litematica.printer.interaction;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.MineDebugLog;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Stateful mining protocol. The Mixin only exposes Minecraft fields through {@link MiningInteractionPort}. */
public final class MiningInteractionController {
    private static final int MIN_PENDING_DESTROY_TICKS = 8;
    private static final int MAX_PENDING_DESTROY_TICKS = 200;

    private final MiningInteractionPort port;
    private final MiningFeedback feedback;
    private final ToolSwitchService toolSwitchService;
    private BlockPos delayedDestroyPos;
    private boolean hasDelayedDestroy;
    private boolean delayedDestroyLocalPrediction;
    private long delayedDestroyStartTick;
    private BlockPos lastLoggedProgressPos;
    private long lastLoggedProgressTick = Long.MIN_VALUE;

    public MiningInteractionController(MiningInteractionPort port, ToolSwitchService toolSwitchService) {
        this.port = port;
        this.toolSwitchService = toolSwitchService;
        this.feedback = new MiningFeedback(port.client());
    }

    public void reset() {
        LocalPlayer player = this.port.client().player;
        if (this.port.isDestroying()) this.clearDestroyProgress(player, this.port.destroyPos());
        if (this.hasDelayedDestroy && this.delayedDestroyPos != null) this.clearDestroyProgress(player, this.delayedDestroyPos);
        this.port.isDestroying(false);
        this.port.destroyProgress(0.0F);
        this.port.destroyPos(BlockPos.ZERO);
        this.port.destroyingItem(ItemStack.EMPTY);
        this.delayedDestroyPos = null;
        this.hasDelayedDestroy = false;
        this.delayedDestroyLocalPrediction = false;
        this.delayedDestroyStartTick = 0L;
        this.lastLoggedProgressPos = null;
        this.lastLoggedProgressTick = Long.MIN_VALUE;
        this.feedback.reset();
    }

    public boolean isPendingDelayedDestroy(BlockPos pos) {
        return this.feedback.hasPending(pos);
    }

    public void tick() {
        LocalPlayer player = this.port.client().player;
        ClientLevel level = this.port.client().level;
        if (player == null || level == null) return;
        this.feedback.cleanupPending(player, level, this.clientTick());
        if (!this.hasDelayedDestroy || this.delayedDestroyPos == null) return;
        BlockState state = level.getBlockState(this.delayedDestroyPos);
        if (state.isAir()) {
            this.feedback.flushPendingBreakSound(this.delayedDestroyPos);
            this.finishDelayed(player);
            return;
        }
        // A survival START+STOP does not give the client authority to remove a block. Keep
        // the server's delayed-destroy slot occupied until the block update arrives; using a
        // local progress prediction here lets the next target overwrite that slot and causes
        // the familiar crackle/flash without a successful break.
        if (!this.delayedDestroyLocalPrediction) {
            int elapsed = (int) (this.clientTick() - this.delayedDestroyStartTick);
            if (elapsed >= this.pendingDestroyTimeout(player, level, this.delayedDestroyPos, state)) {
                this.feedback.removePending(this.delayedDestroyPos);
                this.clearDelayed();
            }
            return;
        }
        int elapsed = (int) (this.clientTick() - this.delayedDestroyStartTick);
        if (state.getDestroyProgress(player, level, this.delayedDestroyPos) * elapsed >= 1.0F) {
            if (this.delayedDestroyLocalPrediction) this.port.destroyBlock(this.delayedDestroyPos);
            this.finishDelayed(player);
        }
    }

    public BlockBreakResult continueForMine(BlockPos pos, Direction direction, boolean allowToolSwitch) {
        LocalPlayer player = this.port.client().player;
        ClientLevel level = this.port.client().level;
        if (player == null || level == null || this.port.client().gameMode == null) return BlockBreakResult.FAILED;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            this.feedback.flushPendingBreakSound(pos);
            this.feedback.removePending(pos);
            if (this.hasDelayedDestroy && pos.equals(this.delayedDestroyPos)) this.clearDelayed();
            if (this.port.isDestroying() && this.port.matchesDestroyTarget(pos)) this.resetDestroyState(player, pos);
            this.feedback.resetHitSound();
            return BlockBreakResult.COMPLETED;
        }
        if (!level.getWorldBorder().isWithinBounds(pos)) return BlockBreakResult.FAILED;
        if (this.port.isInventorySwitchPending()) {
            return BlockBreakResult.IN_PROGRESS;
        }
        if (this.toolSwitchService.prepareForBreak(pos, state, allowToolSwitch)
                != ToolPreparationResult.READY) {
            return BlockBreakResult.IN_PROGRESS;
        }
        this.port.ensureCarriedItemSent();
        float progress = state.getDestroyProgress(player, level, pos);
        boolean fast = player.getAbilities().instabuild
                || progress >= ConfigUtils.getBreakProgressThreshold();
        if (fast) {
            if (this.port.isDestroying()) this.resetDestroyState(player, this.port.destroyPos());
            this.feedback.resetHitSound();
            this.feedback.playHitSound(player, level, state, pos, true);
            this.send(Action.START_DESTROY_BLOCK, pos, direction);
            if (!player.getAbilities().instabuild) {
                if (progress >= 1.0F) {
                    NetworkUtils.sendPacket(sequence -> {
                        this.port.destroyBlock(pos);
                        return this.port.actionPacket(Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
                    });
                } else {
                    this.send(Action.STOP_DESTROY_BLOCK, pos, direction);
                }
            }
            this.feedback.resetHitSound();
            return player.getAbilities().instabuild || progress >= 1.0F
                    ? BlockBreakResult.COMPLETED
                    : BlockBreakResult.COMPLETED_WAIT;
        }
        if (this.hasDelayedDestroy) {
            return pos.equals(this.delayedDestroyPos)
                    ? BlockBreakResult.IN_PROGRESS : BlockBreakResult.ABORTED;
        }
        if (this.feedback.hasPending(pos)) return BlockBreakResult.IN_PROGRESS;
        BlockBreakResult result = this.continueDestroy(true, pos, direction, false, allowToolSwitch);
        if (result == BlockBreakResult.FAILED) return result;
        return this.hasDelayedDestroy && pos.equals(this.delayedDestroyPos) || this.feedback.hasPending(pos)
                ? BlockBreakResult.IN_PROGRESS : result;
    }

    public BlockBreakResult continueDestroy(
            boolean localPrediction,
            BlockPos pos,
            Direction direction,
            boolean forceDelayedDestroy,
            boolean allowToolSwitch
    ) {
        LocalPlayer player = this.port.client().player;
        ClientLevel level = this.port.client().level;
        if (player == null || level == null || this.port.client().gameMode == null) return BlockBreakResult.FAILED;
        boolean localEffects = localPrediction || forceDelayedDestroy;
        boolean localRemoval = localPrediction && !forceDelayedDestroy;
        boolean manualSound = forceDelayedDestroy || !localPrediction;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            this.feedback.flushPendingBreakSound(pos);
            this.feedback.removePending(pos);
            this.feedback.resetHitSound();
            MineDebugLog.write("mine break completed pos=" + MineDebugLog.pos(pos) + " path=air_or_liquid");
            return BlockBreakResult.COMPLETED;
        }
        this.completeDelayedIfReady(player, level);
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
        if (this.toolSwitchService.prepareForBreak(pos, state, allowToolSwitch)
                != ToolPreparationResult.READY) {
            return BlockBreakResult.IN_PROGRESS;
        }
        this.port.ensureCarriedItemSent();
        boolean useDelayed = forceDelayedDestroy || Configs.Break.BREAK_USE_DELAYED_DESTROY.getBooleanValue();
        if (this.feedback.hasPending(pos) && (!this.hasDelayedDestroy || !pos.equals(this.delayedDestroyPos))) {
            return BlockBreakResult.COMPLETED_WAIT;
        }
        if (this.hasDelayedDestroy && pos.equals(this.delayedDestroyPos)) {
            return this.port.isDestroying() ? BlockBreakResult.IN_PROGRESS : BlockBreakResult.COMPLETED;
        }
        if (this.port.isDestroying() && !pos.equals(this.port.destroyPos())) this.abortPrevious(player, pos, direction);
        if (pos.equals(this.port.destroyPos())) {
            this.port.destroyProgress(this.port.destroyProgress() + state.getDestroyProgress(player, level, pos));
            if (manualSound) this.feedback.playHitSound(player, level, state, pos, false);
            if (localEffects) level.destroyBlockProgress(player.getId(), pos, this.destroyStage());
            if (this.port.destroyProgress() >= ConfigUtils.getBreakProgressThreshold()) {
                if (!localRemoval) this.feedback.playBreakSound(pos, state);
                NetworkUtils.sendPacket(sequence -> {
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
        float progress = state.getDestroyProgress(player, level, pos);
        boolean fastFinish = forceDelayedDestroy
                && progress > ConfigUtils.getBreakProgressThreshold();
        if (progress >= ConfigUtils.getBreakProgressThreshold() || fastFinish) {
            boolean waitForServer = fastFinish && progress < ConfigUtils.getBreakProgressThreshold()
                    && progress < 1.0F;
            if (!localRemoval) {
                if (waitForServer) this.feedback.queueBreakSound(pos, state);
                else this.feedback.playBreakSound(pos, state);
            }
            this.send(Action.START_DESTROY_BLOCK, pos, direction);
            NetworkUtils.sendPacket(sequence -> {
                if (localRemoval) this.port.destroyBlock(pos);
                this.resetDestroyState(player, pos);
                return this.port.actionPacket(Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
            });
            if (localEffects) level.destroyBlockProgress(player.getId(), pos, -1);
            this.feedback.resetHitSound();
            return waitForServer ? BlockBreakResult.COMPLETED_WAIT : BlockBreakResult.COMPLETED;
        }
        this.send(Action.START_DESTROY_BLOCK, pos, direction);
        if (progress >= 1.0F) {
            if (!localRemoval) this.feedback.playBreakSound(pos, state);
            if (localRemoval) this.port.destroyBlock(pos);
            if (localEffects) level.destroyBlockProgress(player.getId(), pos, -1);
            this.resetDestroyState(player, pos);
            this.feedback.resetHitSound();
            return BlockBreakResult.COMPLETED;
        }
        if (useDelayed) {
            NetworkUtils.sendPacket(sequence -> {
                this.hasDelayedDestroy = true;
                this.delayedDestroyPos = pos;
                this.delayedDestroyLocalPrediction = localRemoval;
                this.delayedDestroyStartTick = this.clientTick();
                this.feedback.addPending(pos, this.delayedDestroyStartTick);
                this.resetDestroyState(player, pos);
                return this.port.actionPacket(Action.STOP_DESTROY_BLOCK, pos, direction, sequence);
            });
            level.destroyBlockProgress(player.getId(), pos, this.destroyStage());
            this.feedback.resetHitSound();
            return BlockBreakResult.COMPLETED_WAIT;
        }
        this.port.isDestroying(true);
        this.port.destroyPos(pos);
        this.port.destroyProgress(progress);
        this.port.destroyingItem(player.getMainHandItem());
        if (localEffects) level.destroyBlockProgress(player.getId(), pos, this.destroyStage());
        return BlockBreakResult.IN_PROGRESS;
    }

    private void completeDelayedIfReady(LocalPlayer player, ClientLevel level) {
        if (!this.hasDelayedDestroy || this.delayedDestroyPos == null) return;
        if (!this.delayedDestroyLocalPrediction) return;
        BlockState state = level.getBlockState(this.delayedDestroyPos);
        int elapsed = (int) (this.clientTick() - this.delayedDestroyStartTick);
        if (state.getDestroyProgress(player, level, this.delayedDestroyPos) * elapsed < 1.0F) return;
        if (this.delayedDestroyLocalPrediction) this.port.destroyBlock(this.delayedDestroyPos);
        MineDebugLog.write("mine break delayed_completed pos=" + MineDebugLog.pos(this.delayedDestroyPos)
                + " elapsedTicks=" + elapsed);
        this.finishDelayed(player);
    }

    private int pendingDestroyTimeout(
            LocalPlayer player,
            ClientLevel level,
            BlockPos pos,
            BlockState state
    ) {
        float progress = state.getDestroyProgress(player, level, pos);
        if (progress <= 0.0F) {
            return MAX_PENDING_DESTROY_TICKS;
        }
        int estimatedTicks = (int) Math.ceil(1.0F / progress);
        return Math.max(MIN_PENDING_DESTROY_TICKS, Math.min(estimatedTicks + 10, MAX_PENDING_DESTROY_TICKS));
    }

    private void abortPrevious(LocalPlayer player, BlockPos next, Direction direction) {
        BlockPos previous = this.port.destroyPos();
        this.feedback.clearPendingBreakSound(previous);
        NetworkUtils.sendPacket(this.port.actionPacket(Action.ABORT_DESTROY_BLOCK, previous, direction, 0));
        MineDebugLog.write("mine break abort previous=" + MineDebugLog.pos(previous) + " next=" + MineDebugLog.pos(next));
        this.resetDestroyState(player, previous);
        this.feedback.resetHitSound();
    }

    private void finishDelayed(LocalPlayer player) {
        BlockPos pos = this.delayedDestroyPos;
        this.feedback.removePending(pos);
        this.clearDelayed();
        this.clearDestroyProgress(player, pos);
    }

    private void clearDelayed() {
        this.hasDelayedDestroy = false;
        this.delayedDestroyLocalPrediction = false;
    }

    private void resetDestroyState(LocalPlayer player, BlockPos pos) {
        this.port.isDestroying(false);
        this.port.destroyProgress(0.0F);
        this.clearDestroyProgress(player, pos);
    }

    private void clearDestroyProgress(LocalPlayer player, BlockPos pos) {
        if (player == null || pos == null || this.port.client().level == null) return;
        try {
            this.port.client().level.destroyBlockProgress(player.getId(), pos, -1);
        } catch (IllegalStateException ignored) {
        }
    }

    private int destroyStage() {
        float progress = this.port.destroyProgress() >= ConfigUtils.getBreakProgressThreshold()
                ? 1.0F : this.port.destroyProgress();
        return progress > 0.0F ? (int) (progress * 10.0F) : -1;
    }

    private void send(Action action, BlockPos pos, Direction direction) {
        NetworkUtils.sendPacket(sequence -> this.port.actionPacket(action, pos, direction, sequence));
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
}
