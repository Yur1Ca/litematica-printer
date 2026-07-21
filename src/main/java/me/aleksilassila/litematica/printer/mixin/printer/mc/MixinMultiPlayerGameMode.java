package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.MineDebugLog;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings({"DataFlowIssue", "DuplicateCondition"})
@Mixin(value = MultiPlayerGameMode.class, priority = 1020)
public abstract class MixinMultiPlayerGameMode implements MultiPlayerGameModeExtension {
    @Unique
    private static final float MINE_FAST_FINISH_PROGRESS = 0.5F;
    @Unique
    private static final float MINE_FAST_FINISH_COMPLETED_PROGRESS = 0.6F;

    // @formatter:off
    @Shadow
    private BlockPos destroyBlockPos;
    @Shadow
    private ItemStack destroyingItem;
    @Shadow
    private float destroyProgress;
    @Shadow
    private boolean isDestroying;
    @Shadow
    @Final
    private Minecraft minecraft;
    @Unique
    private BlockPos delayedDestroyPos;
    @Unique
    private boolean hasDelayedDestroy;
    @Unique
    private boolean delayedDestroyLocalPrediction;
    @Unique
    private long delayedDestroyStartTick;
    @Unique
    private final Map<BlockPos, Long> litematica_printer$pendingDelayedDestroys = new LinkedHashMap<>();
    @Unique
    private BlockPos litematica_printer$lastLoggedInProgressPos;
    @Unique
    private long litematica_printer$lastLoggedInProgressTick = Long.MIN_VALUE;
    @Unique
    private float litematica_printer$hitSoundTicks;
    @Unique
    private BlockPos litematica_printer$pendingBreakSoundPos;
    @Unique
    private BlockState litematica_printer$pendingBreakSoundState;

<<<<<<< HEAD
    @Override
    public void litematica_printer$resetRuntime() {
        LocalPlayer player = this.minecraft.player;
        if (this.isDestroying) {
            this.litematica_printer$clearDestroyProgress(player, this.destroyBlockPos);
        }
        if (this.hasDelayedDestroy && this.delayedDestroyPos != null) {
            this.litematica_printer$clearDestroyProgress(player, this.delayedDestroyPos);
        }
        this.isDestroying = false;
        this.destroyProgress = 0.0F;
        this.destroyBlockPos = BlockPos.ZERO;
        this.destroyingItem = ItemStack.EMPTY;
        this.delayedDestroyPos = null;
        this.hasDelayedDestroy = false;
        this.delayedDestroyLocalPrediction = false;
        this.delayedDestroyStartTick = 0L;
        this.litematica_printer$pendingDelayedDestroys.clear();
        this.litematica_printer$lastLoggedInProgressPos = null;
        this.litematica_printer$lastLoggedInProgressTick = Long.MIN_VALUE;
        this.litematica_printer$pendingBreakSoundPos = null;
        this.litematica_printer$pendingBreakSoundState = null;
        this.litematica_printer$resetHitSoundState();
    }

    @Unique
    private void litematica_printer$clearDestroyProgress(LocalPlayer player, BlockPos pos) {
        if (player != null && pos != null && this.minecraft.level != null) {
            int playerId;
            try {
                playerId = player.getId();
            } catch (IllegalStateException ignored) {
                // 26.2 登录包处理期间 LocalPlayer 可能尚未分配实体 ID。
                return;
            }
            this.minecraft.level.destroyBlockProgress(playerId, pos, -1);
=======
    @Unique
    private void litematica_printer$clearDestroyProgress(LocalPlayer player, BlockPos pos) {
        if (player != null && pos != null && this.minecraft.level != null) {
            this.minecraft.level.destroyBlockProgress(player.getId(), pos, -1);
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        }
    }

    @Unique
    private void litematica_printer$resetDestroyState(LocalPlayer player, BlockPos pos) {
        this.isDestroying = false;
        this.destroyProgress = 0.0F;
        this.litematica_printer$clearDestroyProgress(player, pos);
    }

    @Unique
    private void litematica_printer$addPendingDelayedDestroy(BlockPos pos) {
        if (pos != null) {
            this.litematica_printer$pendingDelayedDestroys.put(pos.immutable(), getClientTickCount());
        }
    }

    @Unique
    private boolean litematica_printer$hasPendingDelayedDestroy(BlockPos pos) {
        return pos != null && this.litematica_printer$pendingDelayedDestroys.containsKey(pos);
    }

    @Unique
    private void litematica_printer$removePendingDelayedDestroy(BlockPos pos) {
        if (pos != null) {
            this.litematica_printer$pendingDelayedDestroys.remove(pos);
        }
    }

    @Unique
    private void litematica_printer$cleanupPendingDelayedDestroys(LocalPlayer player, ClientLevel level) {
        if (this.litematica_printer$pendingDelayedDestroys.isEmpty()) {
            return;
        }
        long currentTick = getClientTickCount();
        Iterator<Map.Entry<BlockPos, Long>> iterator = this.litematica_printer$pendingDelayedDestroys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            BlockPos pos = entry.getKey();
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                iterator.remove();
                MineDebugLog.write("mine pending cleared pos=" + MineDebugLog.pos(pos) + " reason=state_cleared");
                continue;
            }
            int timeoutTicks = this.litematica_printer$getPendingDelayedDestroyTimeoutTicks(player, level, pos, state);
            if (currentTick - entry.getValue() >= timeoutTicks) {
                iterator.remove();
                MineDebugLog.write("mine pending timeout pos=" + MineDebugLog.pos(pos)
                        + " timeoutTicks=" + timeoutTicks
                        + " state=" + MineDebugLog.describeState(state));
            }
        }
    }

    @Unique
    private int litematica_printer$getPendingDelayedDestroyTimeoutTicks(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState state) {
        float progressPerTick = state.getDestroyProgress(player, level, pos);
        if (progressPerTick <= 0.0F) {
            return 200;
        }
        int estimatedTicks = (int) Math.ceil(1.0F / progressPerTick);
        return Math.max(8, Math.min(estimatedTicks + 10, 200));
    }

    @Unique
    private void litematica_printer$resetHitSoundState() {
        this.litematica_printer$hitSoundTicks = 0.0F;
    }

    @Unique
    private void litematica_printer$queueBreakSound(BlockPos blockPos, BlockState blockState) {
        if (blockPos == null || blockState == null || blockState.isAir()) {
            return;
        }
        this.litematica_printer$pendingBreakSoundPos = blockPos.immutable();
        this.litematica_printer$pendingBreakSoundState = blockState;
    }

    @Unique
    private void litematica_printer$clearPendingBreakSound(BlockPos blockPos) {
        if (blockPos != null && blockPos.equals(this.litematica_printer$pendingBreakSoundPos)) {
            this.litematica_printer$pendingBreakSoundPos = null;
            this.litematica_printer$pendingBreakSoundState = null;
        }
    }

    @Unique
    private void litematica_printer$playBlockHitSound(LocalPlayer player, ClientLevel level, BlockState blockState, BlockPos blockPos, boolean force) {
        if (blockState.isAir()) {
            return;
        }
        if (!force && this.litematica_printer$hitSoundTicks % 4.0F != 0.0F) {
            this.litematica_printer$hitSoundTicks += 1.0F;
            return;
        }

        SoundType soundType = blockState.getSoundType();
        //#if MC > 11802
        this.minecraft.getSoundManager().play(new SimpleSoundInstance(
                soundType.getHitSound(),
                net.minecraft.sounds.SoundSource.BLOCKS,
                Math.max((soundType.getVolume() + 1.0F) / 4.0F, 0.35F),
                soundType.getPitch() * 0.5F,
                SoundInstance.createUnseededRandom(),
                blockPos
        ));
        //#else
        //$$ this.minecraft.getSoundManager().play(new SimpleSoundInstance(
        //$$         soundType.getHitSound(),
        //$$         net.minecraft.sounds.SoundSource.BLOCKS,
        //$$         Math.max((soundType.getVolume() + 1.0F) / 4.0F, 0.35F),
        //$$         soundType.getPitch() * 0.5F,
        //$$         blockPos.getX() + 0.5D,
        //$$         blockPos.getY() + 0.5D,
        //$$         blockPos.getZ() + 0.5D
        //$$ ));
        //#endif
        this.litematica_printer$hitSoundTicks += 1.0F;
    }

    @Unique
    private void litematica_printer$playBlockBreakSound(BlockPos blockPos, BlockState blockState) {
        if (blockPos == null || blockState == null || blockState.isAir()) {
            return;
        }
        SoundType soundType = blockState.getSoundType();
        //#if MC > 11802
        this.minecraft.getSoundManager().play(new SimpleSoundInstance(
                soundType.getBreakSound(),
                net.minecraft.sounds.SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F,
                SoundInstance.createUnseededRandom(),
                blockPos
        ));
        //#else
        //$$ this.minecraft.getSoundManager().play(new SimpleSoundInstance(
        //$$         soundType.getBreakSound(),
        //$$         net.minecraft.sounds.SoundSource.BLOCKS,
        //$$         (soundType.getVolume() + 1.0F) / 2.0F,
        //$$         soundType.getPitch() * 0.8F,
        //$$         blockPos.getX() + 0.5D,
        //$$         blockPos.getY() + 0.5D,
        //$$         blockPos.getZ() + 0.5D
        //$$ ));
        //#endif
    }

    @Unique
    private void litematica_printer$flushPendingBreakSound(BlockPos blockPos) {
        if (blockPos != null && blockPos.equals(this.litematica_printer$pendingBreakSoundPos) && this.litematica_printer$pendingBreakSoundState != null) {
            this.litematica_printer$playBlockBreakSound(blockPos, this.litematica_printer$pendingBreakSoundState);
            this.litematica_printer$pendingBreakSoundPos = null;
            this.litematica_printer$pendingBreakSoundState = null;
        }
    }

    @Override
    public boolean litematica_printer$isPendingDelayedDestroy(BlockPos blockPos) {
        return this.litematica_printer$hasPendingDelayedDestroy(blockPos);
    }

    @Shadow
    public abstract boolean destroyBlock(final BlockPos pos);

    @Shadow
    protected abstract boolean sameDestroyTarget(final BlockPos pos);

    @Shadow
    protected abstract void ensureHasSentCarriedItem();

    //#if MC > 11802
    @Shadow public abstract InteractionResult useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult blockHitResult);
    //#else
    //$$ @Shadow public abstract InteractionResult useItemOn(LocalPlayer player,ClientLevel level, InteractionHand hand, BlockHitResult blockHitResult);
    //#endif

    // @formatter:on

    @Inject(at = @At("HEAD"), method = "tick")
    public void tick(CallbackInfo ci) {
        if (this.hasDelayedDestroy) {
            LocalPlayer player = this.minecraft.player;
            ClientLevel level = this.minecraft.level;
            if (player == null || level == null) {
                return;
            }
            this.litematica_printer$cleanupPendingDelayedDestroys(player, level);
            BlockState blockState = level.getBlockState(this.delayedDestroyPos);
            if (blockState.isAir()) {
                this.litematica_printer$flushPendingBreakSound(this.delayedDestroyPos);
                this.litematica_printer$removePendingDelayedDestroy(this.delayedDestroyPos);
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
                this.litematica_printer$clearDestroyProgress(player, this.delayedDestroyPos);
                return;
            }
            long currentTick = getClientTickCount();
            int elapsedTicks = (int) (currentTick - this.delayedDestroyStartTick);
            float delayedDestroyProgress = blockState.getDestroyProgress(player, level, this.delayedDestroyPos) * elapsedTicks;
            if (delayedDestroyProgress >= 1.0F) {
                if (this.delayedDestroyLocalPrediction) {
                    this.destroyBlock(this.delayedDestroyPos);
                }
                this.litematica_printer$removePendingDelayedDestroy(this.delayedDestroyPos);
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
                this.litematica_printer$clearDestroyProgress(player, this.delayedDestroyPos);
            }
        } else {
            LocalPlayer player = this.minecraft.player;
            ClientLevel level = this.minecraft.level;
            if (player != null && level != null) {
                this.litematica_printer$cleanupPendingDelayedDestroys(player, level);
            }
        }
    }

    @Override
    public InteractionResult litematica_printer$useItemOn(boolean localPrediction, InteractionHand hand, BlockHitResult blockHit) {
        if (localPrediction) {
            //#if MC > 11802
            return useItemOn(minecraft.player, hand, blockHit);
            //#else
            //$$ return useItemOn(minecraft.player, minecraft.level, hand, blockHit);
            //#endif
        }
        this.ensureHasSentCarriedItem();
        if (!this.minecraft.level.getWorldBorder().isWithinBounds(blockHit.getBlockPos())) {
            return InteractionResult.FAIL;
        }
        //#if MC > 11802
        NetworkUtils.sendPacket(sequence -> new ServerboundUseItemOnPacket(hand, blockHit, sequence));
        //#else
        //$$ NetworkUtils.sendPacket(sequence -> new ServerboundUseItemOnPacket(hand, blockHit));
        //#endif
        return InteractionResult.PASS;
    }


    @Unique
    private int litematica_printer$getDestroyStage() {
        float breakingProgress = this.destroyProgress >= ConfigUtils.getBreakProgressThreshold() ? 1.0F : this.destroyProgress;
        return breakingProgress > 0.0F ? (int) (breakingProgress * 10.0F) : -1;
    }

    @Unique
    private ServerboundPlayerActionPacket getActionPacket(Action action, BlockPos blockPos, Direction direction, int sequence) {
        //#if MC > 11802
        return new ServerboundPlayerActionPacket(action, blockPos, direction, sequence);
        //#else
        //$$ return new ServerboundPlayerActionPacket(action, blockPos, direction);
        //#endif
    }

    @Override
    public BlockBreakResult litematica_printer$continueDestroyBlockForMine(BlockPos blockPos, Direction direction, boolean allowToolSwitch) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || level == null || gameMode == null) {
            return BlockBreakResult.FAILED;
        }

        BlockState blockState = level.getBlockState(blockPos);
        if (blockState.isAir() || blockState.getBlock() instanceof LiquidBlock) {
            this.litematica_printer$flushPendingBreakSound(blockPos);
            this.litematica_printer$removePendingDelayedDestroy(blockPos);
            if (this.hasDelayedDestroy && blockPos.equals(this.delayedDestroyPos)) {
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
            }
            if (this.isDestroying && this.sameDestroyTarget(blockPos)) {
                this.litematica_printer$resetDestroyState(player, blockPos);
            }
            this.litematica_printer$resetHitSoundState();
            return BlockBreakResult.COMPLETED;
        }

        if (!level.getWorldBorder().isWithinBounds(blockPos)) {
            return BlockBreakResult.FAILED;
        }

        if (!allowToolSwitch || !InteractionUtils.trySwitchToEffectiveTool(blockPos, blockState)) {
            ensureHasSentCarriedItem();
        }
        if (!InteractionUtils.protectCurrentToolBeforeBreak(blockState)) {
            return BlockBreakResult.FAILED;
        }
        ensureHasSentCarriedItem();

        float destroyProgress = blockState.getDestroyProgress(player, level, blockPos);
        boolean fastPath = player.getAbilities().instabuild || destroyProgress >= MINE_FAST_FINISH_PROGRESS;

        if (!fastPath) {
            if (this.hasDelayedDestroy) {
                if (blockPos.equals(this.delayedDestroyPos)) {
                    return BlockBreakResult.IN_PROGRESS;
                }
                return BlockBreakResult.ABORTED;
            }
            if (this.litematica_printer$hasPendingDelayedDestroy(blockPos)) {
                return BlockBreakResult.IN_PROGRESS;
            }

            BlockBreakResult result = this.litematica_printer$continueDestroyBlock(false, blockPos, direction, false, allowToolSwitch);
            if (result == BlockBreakResult.FAILED) {
                return BlockBreakResult.FAILED;
            }
            if (this.hasDelayedDestroy && blockPos.equals(this.delayedDestroyPos)) {
                return BlockBreakResult.IN_PROGRESS;
            }
            if (this.litematica_printer$hasPendingDelayedDestroy(blockPos)) {
                return BlockBreakResult.IN_PROGRESS;
            }
            return result;
        }

        if (this.isDestroying) {
            this.litematica_printer$resetDestroyState(player, this.destroyBlockPos);
        }
        this.litematica_printer$resetHitSoundState();
        this.litematica_printer$playBlockHitSound(player, level, blockState, blockPos, true);

        NetworkUtils.sendPacket(sequence -> getActionPacket(Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
        if (!player.getAbilities().instabuild) {
            NetworkUtils.sendPacket(sequence -> getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence));
        }
        this.litematica_printer$resetHitSoundState();
        return BlockBreakResult.COMPLETED_WAIT;
    }

    @Override
    public BlockBreakResult litematica_printer$continueDestroyBlock(boolean localPrediction, BlockPos blockPos, Direction direction, boolean forceDelayedDestroy, boolean allowToolSwitch) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || level == null || gameMode == null) {
            return BlockBreakResult.FAILED;
        }
        boolean localEffects = localPrediction || forceDelayedDestroy;
        boolean localBlockRemoval = localPrediction && !forceDelayedDestroy;
        boolean manualHitSound = forceDelayedDestroy || !localPrediction;
        BlockState blockState = level.getBlockState(blockPos);
        if (blockState.isAir() || blockState.getBlock() instanceof LiquidBlock) {
            this.litematica_printer$flushPendingBreakSound(blockPos);
            this.litematica_printer$removePendingDelayedDestroy(blockPos);
            this.litematica_printer$resetHitSoundState();
            MineDebugLog.write("mine break completed pos=" + MineDebugLog.pos(blockPos) + " path=air_or_liquid");
            return BlockBreakResult.COMPLETED;
        }
        if (this.hasDelayedDestroy) {
            BlockState blockState2 = minecraft.level.getBlockState(this.delayedDestroyPos);
            long currentTick = getClientTickCount();
            int elapsedTicks = (int) (currentTick - this.delayedDestroyStartTick);
            float delayedDestroyProgress = blockState2.getDestroyProgress(player, level, this.delayedDestroyPos) * elapsedTicks;
            if (delayedDestroyProgress >= 1.0F) {
                if (this.delayedDestroyLocalPrediction) {
                    this.destroyBlock(this.delayedDestroyPos);
                }
                this.litematica_printer$removePendingDelayedDestroy(this.delayedDestroyPos);
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
                MineDebugLog.write("mine break delayed_completed pos=" + MineDebugLog.pos(this.delayedDestroyPos)
                        + " elapsedTicks=" + elapsedTicks);
            }
        }
        if (!level.getWorldBorder().isWithinBounds(blockPos)) {
            MineDebugLog.write("mine break failed pos=" + MineDebugLog.pos(blockPos) + " reason=out_of_world_border");
            return BlockBreakResult.FAILED;
        }
        if (player.getAbilities().instabuild) {
            if (manualHitSound) {
                this.litematica_printer$playBlockHitSound(player, level, blockState, blockPos, true);
            }
            NetworkUtils.sendPacket(sequence -> {
                if (localBlockRemoval) {
                    destroyBlock(blockPos);
                }
                return getActionPacket(Action.START_DESTROY_BLOCK, blockPos, direction, sequence);
            });
            this.litematica_printer$resetHitSoundState();
            MineDebugLog.write("mine break completed pos=" + MineDebugLog.pos(blockPos) + " path=instabuild");
            return BlockBreakResult.COMPLETED;
        }
        if (allowToolSwitch) {
            InteractionUtils.trySwitchToEffectiveTool(blockPos, blockState);
        }
        ensureHasSentCarriedItem();
        if (!InteractionUtils.protectCurrentToolBeforeBreak(blockState)) {
            MineDebugLog.write("mine break failed pos=" + MineDebugLog.pos(blockPos) + " reason=tool_durability_protected");
            return BlockBreakResult.FAILED;
        }
        ensureHasSentCarriedItem();
        boolean useDelayedDestroy = forceDelayedDestroy || Configs.Break.BREAK_USE_DELAYED_DESTROY.getBooleanValue();
        if (blockState.isAir()) {
            if (this.hasDelayedDestroy && blockPos.equals(this.delayedDestroyPos)) {
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
                this.litematica_printer$clearDestroyProgress(player, blockPos);
            }
            this.litematica_printer$removePendingDelayedDestroy(blockPos);
            if (this.isDestroying && this.sameDestroyTarget(blockPos)) {
                this.litematica_printer$resetDestroyState(player, blockPos);
            }
            this.litematica_printer$resetHitSoundState();
            return BlockBreakResult.COMPLETED;
        }
        if (this.litematica_printer$hasPendingDelayedDestroy(blockPos)
                && (!this.hasDelayedDestroy || !blockPos.equals(this.delayedDestroyPos))) {
            MineDebugLog.write("mine break completed_wait pos=" + MineDebugLog.pos(blockPos) + " path=pending_delayed_destroy");
            return BlockBreakResult.COMPLETED_WAIT;
        }
        if (this.hasDelayedDestroy && blockPos.equals(this.delayedDestroyPos)) {
            MineDebugLog.write("mine break completed_wait pos=" + MineDebugLog.pos(blockPos) + " path=delayed_slot_busy");
            return isDestroying ? BlockBreakResult.IN_PROGRESS : BlockBreakResult.COMPLETED;
        }
        if (this.isDestroying && !blockPos.equals(this.destroyBlockPos)) {
            this.litematica_printer$clearPendingBreakSound(this.destroyBlockPos);
            NetworkUtils.sendPacket(getActionPacket(Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos, direction, 0));
            MineDebugLog.write("mine break abort previous=" + MineDebugLog.pos(this.destroyBlockPos)
                    + " next=" + MineDebugLog.pos(blockPos));
            this.litematica_printer$resetDestroyState(player, this.destroyBlockPos);
            this.litematica_printer$resetHitSoundState();
        }
        if (blockPos.equals(this.destroyBlockPos)) {
            this.destroyProgress = this.destroyProgress + blockState.getDestroyProgress(player, level, blockPos);
            if (manualHitSound) {
                this.litematica_printer$playBlockHitSound(player, level, blockState, blockPos, false);
            }
            if (localEffects) {
                level.destroyBlockProgress(player.getId(), blockPos, this.litematica_printer$getDestroyStage());
            }
            if (this.destroyProgress >= ConfigUtils.getBreakProgressThreshold()) {
                if (!localBlockRemoval) {
                    this.litematica_printer$playBlockBreakSound(blockPos, blockState);
                }
                NetworkUtils.sendPacket(sequence -> {
                    if (localBlockRemoval) {
                        destroyBlock(blockPos);
                    }
                    this.litematica_printer$resetDestroyState(player, blockPos);
                    return getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence);
                });
                if (localEffects) {
                    level.destroyBlockProgress(player.getId(), blockPos, -1);
                }
                this.litematica_printer$resetHitSoundState();
                MineDebugLog.write("mine break completed pos=" + MineDebugLog.pos(blockPos)
                        + " path=same_target_threshold threshold=" + ConfigUtils.getBreakProgressThreshold());
                return BlockBreakResult.COMPLETED;
            }
            long currentTick = getClientTickCount();
            if (!blockPos.equals(this.litematica_printer$lastLoggedInProgressPos)
                    || currentTick - this.litematica_printer$lastLoggedInProgressTick >= 10) {
                MineDebugLog.write("mine break in_progress pos=" + MineDebugLog.pos(blockPos)
                        + " path=same_target progress=" + this.destroyProgress
                        + " threshold=" + ConfigUtils.getBreakProgressThreshold());
                this.litematica_printer$lastLoggedInProgressPos = blockPos;
                this.litematica_printer$lastLoggedInProgressTick = currentTick;
            }
            return BlockBreakResult.IN_PROGRESS;
        }
        if (!this.isDestroying || !blockPos.equals(this.destroyBlockPos)) {
            if (this.isDestroying) {
                this.litematica_printer$clearPendingBreakSound(this.destroyBlockPos);
                NetworkUtils.sendPacket(getActionPacket(Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos, direction, 0));
                MineDebugLog.write("mine break abort previous=" + MineDebugLog.pos(this.destroyBlockPos)
                        + " next=" + MineDebugLog.pos(blockPos)
                        + " reason=new_target");
                this.litematica_printer$resetDestroyState(player, this.destroyBlockPos);
                this.litematica_printer$resetHitSoundState();
            }
            if (this.destroyProgress == 0.0F) {
                if (localEffects) {
                    blockState.attack(level, blockPos, player);
                }
            }
            if (manualHitSound) {
                this.litematica_printer$playBlockHitSound(player, level, blockState, blockPos, true);
            }
            float destroyProgress = blockState.getDestroyProgress(player, level, blockPos);
            boolean mineFastFinish = forceDelayedDestroy && destroyProgress > MINE_FAST_FINISH_PROGRESS;
            if (destroyProgress >= ConfigUtils.getBreakProgressThreshold() || mineFastFinish) {
                boolean waitForServerState = mineFastFinish
                        && destroyProgress < ConfigUtils.getBreakProgressThreshold()
                        && destroyProgress <= MINE_FAST_FINISH_COMPLETED_PROGRESS;
                if (!localBlockRemoval) {
                    if (waitForServerState) {
                        this.litematica_printer$queueBreakSound(blockPos, blockState);
                    } else {
                        this.litematica_printer$playBlockBreakSound(blockPos, blockState);
                    }
                }
                NetworkUtils.sendPacket(sequence -> getActionPacket(Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
                NetworkUtils.sendPacket(sequence -> {
                    if (localBlockRemoval) {
                        destroyBlock(blockPos);
                    }
                    this.litematica_printer$resetDestroyState(player, blockPos);
                    return getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence);
                });
                if (localEffects) {
                    level.destroyBlockProgress(player.getId(), blockPos, -1);
                }
                this.litematica_printer$resetHitSoundState();
                MineDebugLog.write("mine break completed pos=" + MineDebugLog.pos(blockPos)
                        + " path=" + (mineFastFinish ? "mine_fast_finish" : "instant_threshold")
                        + " progress=" + destroyProgress
                        + " threshold=" + ConfigUtils.getBreakProgressThreshold());
                return waitForServerState ? BlockBreakResult.COMPLETED_WAIT : BlockBreakResult.COMPLETED;
            }
            NetworkUtils.sendPacket(sequence -> getActionPacket(Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
            if (destroyProgress >= 1.0F) {
                if (!localBlockRemoval) {
                    this.litematica_printer$playBlockBreakSound(blockPos, blockState);
                }
                if (localBlockRemoval) {
                    destroyBlock(blockPos);
                }
                if (localEffects) {
                    level.destroyBlockProgress(player.getId(), blockPos, -1);
                }
                this.litematica_printer$resetDestroyState(player, blockPos);
                this.litematica_printer$resetHitSoundState();
                MineDebugLog.write("mine break completed pos=" + MineDebugLog.pos(blockPos)
                        + " path=start_only progress=" + destroyProgress);
                return BlockBreakResult.COMPLETED;
            }
            if (useDelayedDestroy) {
                if (destroyProgress >= ConfigUtils.getBreakProgressThreshold()) {
                    if (!localBlockRemoval) {
                        this.litematica_printer$playBlockBreakSound(blockPos, blockState);
                    }
                    NetworkUtils.sendPacket(sequence -> {
                        if (localBlockRemoval) {
                            this.destroyBlock(blockPos);
                        }
                        this.hasDelayedDestroy = false;
                        return getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence);
                    });
                    if (localEffects) {
                        level.destroyBlockProgress(player.getId(), blockPos, -1);
                    }
                    this.litematica_printer$resetHitSoundState();
                    MineDebugLog.write("mine break completed pos=" + MineDebugLog.pos(blockPos)
                            + " path=delayed_threshold progress=" + destroyProgress);
                    return BlockBreakResult.COMPLETED;
                } else {
                    // 发送STOP让服务端当前处理位置状态转移到延迟破坏位置中
                    NetworkUtils.sendPacket(sequence -> {
                        this.hasDelayedDestroy = true;
                        this.delayedDestroyPos = blockPos;
                        this.delayedDestroyLocalPrediction = localBlockRemoval;
                        this.delayedDestroyStartTick = getClientTickCount();
                        this.litematica_printer$addPendingDelayedDestroy(blockPos);
                        this.litematica_printer$resetDestroyState(player, blockPos);
                        return getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence);
                    });
                    level.destroyBlockProgress(player.getId(), blockPos, this.litematica_printer$getDestroyStage());
                    this.litematica_printer$resetHitSoundState();
                    MineDebugLog.write("mine break completed_wait pos=" + MineDebugLog.pos(blockPos)
                            + " path=delayed_destroy progress=" + destroyProgress
                            + " threshold=" + ConfigUtils.getBreakProgressThreshold());
                    return BlockBreakResult.COMPLETED_WAIT;
                }
            }
            this.isDestroying = true;
            this.destroyBlockPos = blockPos;
            this.destroyProgress = destroyProgress;
            this.destroyingItem = player.getMainHandItem();
            if (localEffects) {
                level.destroyBlockProgress(player.getId(), blockPos, this.litematica_printer$getDestroyStage());
            }
            MineDebugLog.write("mine break start pos=" + MineDebugLog.pos(blockPos)
                    + " path=new_target progress=" + destroyProgress
                    + " localPrediction=" + localPrediction
                    + " state=" + MineDebugLog.describeState(blockState));
            return BlockBreakResult.IN_PROGRESS;
        }
        MineDebugLog.write("mine break failed pos=" + MineDebugLog.pos(blockPos) + " reason=fell_through_state_machine");
        return BlockBreakResult.FAILED;
    }

    @Unique
    private long getClientTickCount() {
        ClientLevel level = this.minecraft.level;
        return level == null ? 0L : level.getGameTime();
    }
}
