package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockStateUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

public class WaterPrintTask implements PrintTask {
    private static final int STALL_PADDING_TICKS = 8;
    private static final int MIN_STALL_TICKS = 12;
    private static final int MAX_STALL_TICKS = 40;

    private final BlockPos pos;
    @Nullable
    private BlockState lastState;
    private int stateTicks;
    private long stateTickTime = Long.MIN_VALUE;
    private boolean icePlacementSent;
    private boolean iceBreakSent;
    private boolean readyForPlacement;
    private boolean complete;
    private boolean aborted;

    private WaterPrintTask(BlockPos pos) {
        this.pos = pos.immutable();
    }

    @Nullable
    public static WaterPrintTask tryCreate(SchematicBlockContext context) {
        if (!isCandidate(context)) {
            return null;
        }
        return new WaterPrintTask(context.blockPos);
    }

    @Override
    public BlockPos pos() {
        return this.pos;
    }

    @Override
    public boolean shouldKeep(ClientLevel level, WorldSchematic schematic) {
        if (this.complete || this.aborted || !Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return false;
        }
        BlockState requiredState = schematic.getBlockState(this.pos);
        if (!BlockStateUtils.isWaterBlock(requiredState) || shouldSkipWaterloggedTarget(requiredState)) {
            return false;
        }
        BlockState currentState = level.getBlockState(this.pos);
        if (isWorkflowComplete(requiredState, currentState)) {
            return false;
        }

        this.readyForPlacement = false;
        if (BlockStateUtils.isCorrectWaterLevel(requiredState, currentState)) {
            this.icePlacementSent = false;
            this.iceBreakSent = false;
            this.refreshState(currentState);
            if (isWaterloggedTarget(requiredState)) {
                this.readyForPlacement = true;
            }
            return isWaterloggedTarget(requiredState);
        }
        if (currentState.getBlock() instanceof IceBlock) {
            this.icePlacementSent = false;
            return !this.isStateStalled(level, currentState);
        }
        if (this.iceBreakSent || InteractionUtils.INSTANCE.isPendingDelayedDestroy(this.pos)) {
            this.refreshState(currentState);
            return !this.isStateStalled(level, currentState);
        }
        if (this.icePlacementSent) {
            return !this.isStateStalled(level, currentState);
        }
        if (isDryWaterloggedBlock(requiredState, currentState)
                && InteractionUtils.canBreakBlock(this.pos)
                && canStartIceWaterWorkflow(level, this.pos)) {
            return !this.isStateStalled(level, currentState);
        }
        return BlockStateUtils.isReplaceable(currentState) && canStartIceWaterWorkflow(level, this.pos);
    }

    @Override
    public PrintTaskBuildResult buildAction(SchematicBlockContext context) {
        if (this.complete || this.aborted) {
            return PrintTaskBuildResult.PASS;
        }
        if (isWorkflowComplete(context.requiredState, context.currentState)) {
            this.complete = true;
            return PrintTaskBuildResult.SKIP;
        }
        if (context.currentState.getBlock() instanceof IceBlock) {
            this.icePlacementSent = false;
            return this.breakBlockForWorkflow(context, true);
        }
        if (isDryWaterloggedBlock(context.requiredState, context.currentState)) {
            if (!canStartIceWaterWorkflow(context.level, context.blockPos)) {
                this.complete = true;
                return PrintTaskBuildResult.SKIP;
            }
            return this.breakBlockForWorkflow(context, false);
        }
        if (BlockStateUtils.isCorrectWaterLevel(context.requiredState, context.currentState)) {
            this.icePlacementSent = false;
            this.iceBreakSent = false;
            if (isWaterloggedTarget(context.requiredState)) {
                this.readyForPlacement = true;
                this.refreshState(context.currentState);
                return PrintTaskBuildResult.PASS;
            }
            this.complete = true;
            return PrintTaskBuildResult.SKIP;
        }
        if (this.iceBreakSent || InteractionUtils.INSTANCE.isPendingDelayedDestroy(this.pos)) {
            if (this.isStateStalled(context.level, context.currentState)) {
                this.aborted = true;
                return PrintTaskBuildResult.PASS;
            }
            this.refreshState(context.currentState);
            return PrintTaskBuildResult.SKIP;
        }
        if (this.icePlacementSent) {
            if (this.isStateStalled(context.level, context.currentState)) {
                this.aborted = true;
                return PrintTaskBuildResult.PASS;
            }
            this.refreshState(context.currentState);
            return PrintTaskBuildResult.SKIP;
        }
        if (isWaterloggedTarget(context.requiredState) && !canStartIceWaterWorkflow(context.level, context.blockPos)) {
            this.aborted = true;
            return PrintTaskBuildResult.PASS;
        }
        if (isWaterloggedTarget(context.requiredState) && !BlockStateUtils.isReplaceable(context.currentState)) {
            this.aborted = true;
            return PrintTaskBuildResult.PASS;
        }
        if (!canIceBecomeWaterSource(context.level, context.blockPos)) {
            this.aborted = true;
            return isWaterloggedTarget(context.requiredState) ? PrintTaskBuildResult.PASS : PrintTaskBuildResult.SKIP;
        }

        Action action = new Action().setItem(Items.ICE).setRequiresSupport();
        return PrintTaskBuildResult.action(action, new WaterTaskAction(false));
    }

    @Override
    public @Nullable PrintTaskAction createActionHandle(SchematicBlockContext context, Action action) {
        if (this.readyForPlacement && isWaterloggedTarget(context.requiredState)) {
            return new WaterTaskAction(true);
        }
        return null;
    }

    private PrintTaskBuildResult breakBlockForWorkflow(SchematicBlockContext context, boolean ice) {
        if (!InteractionUtils.canBreakBlock(context.blockPos) || this.isStateStalled(context.level, context.currentState)) {
            this.aborted = true;
            return PrintTaskBuildResult.PASS;
        }
        if (ice && !IceBreakToolSelector.switchToNonSilkTouchBreakItem(context.client)) {
            this.aborted = true;
            return PrintTaskBuildResult.PASS;
        }

        InteractionUtils.INSTANCE.suppressQueuedBreaks(2);
        BlockBreakResult result = ice
                ? InteractionUtils.INSTANCE.continueDestroyBlockWithoutToolSwitch(context.blockPos, Direction.DOWN, false)
                : InteractionUtils.INSTANCE.continueDestroyBlockWithoutTracking(context.blockPos, Direction.DOWN);
        if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
            if (ice) {
                this.markIceBreakSent(context.currentState);
            } else {
                this.refreshState(context.currentState);
            }
            return PrintTaskBuildResult.SKIP;
        }
        if (result == BlockBreakResult.IN_PROGRESS) {
            this.refreshState(context.currentState);
            return PrintTaskBuildResult.SKIP;
        }

        this.aborted = true;
        return PrintTaskBuildResult.PASS;
    }

    private void markIceBreakSent(BlockState currentState) {
        this.refreshState(currentState);
        this.iceBreakSent = true;
    }

    private void refreshState(BlockState currentState) {
        long currentTick = ClientPlayerTickManager.getCurrentHandlerTime();
        if (currentTick == this.stateTickTime) {
            return;
        }
        this.stateTickTime = currentTick;
        if (currentState.equals(this.lastState)) {
            this.stateTicks++;
        } else {
            this.lastState = currentState;
            this.stateTicks = 0;
        }
    }

    private boolean isStateStalled(ClientLevel level, BlockState currentState) {
        this.refreshState(currentState);
        return this.stateTicks > getStallLimit(level, currentState);
    }

    private int getStallLimit(ClientLevel level, BlockState currentState) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return MIN_STALL_TICKS;
        }
        float progressPerTick = currentState.getDestroyProgress(player, level, this.pos);
        if (progressPerTick <= 0.0F) {
            return MIN_STALL_TICKS;
        }
        int estimatedTicks = (int) Math.ceil(1.0F / progressPerTick);
        return Math.max(MIN_STALL_TICKS, Math.min(MAX_STALL_TICKS, estimatedTicks + STALL_PADDING_TICKS));
    }

    private static boolean isCandidate(SchematicBlockContext context) {
        if (!BlockStateUtils.isWaterBlock(context.requiredState)
                || context.client.gameMode == null
                || context.client.gameMode.getPlayerMode().isCreative()
                || shouldSkipWaterloggedTarget(context.requiredState)
                || !Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                || isWorkflowComplete(context.requiredState, context.currentState)) {
            return false;
        }
        if (context.currentState.getBlock() instanceof IceBlock) {
            return canIceBecomeWaterSource(context.level, context.blockPos);
        }
        if (isWaterloggedTarget(context.requiredState)
                && BlockStateUtils.isCorrectWaterLevel(context.requiredState, context.currentState)) {
            return true;
        }
        if (isDryWaterloggedBlock(context.requiredState, context.currentState)) {
            return InteractionUtils.canBreakBlock(context.blockPos)
                    && canStartIceWaterWorkflow(context.level, context.blockPos);
        }
        if (isWaterloggedTarget(context.requiredState) && !BlockStateUtils.isReplaceable(context.currentState)) {
            return false;
        }
        return BlockStateUtils.isReplaceable(context.currentState)
                && canStartIceWaterWorkflow(context.level, context.blockPos);
    }

    private static boolean canStartIceWaterWorkflow(ClientLevel level, BlockPos pos) {
        return hasIceForWaterWorkflow() && canIceBecomeWaterSource(level, pos);
    }

    private static boolean hasIceForWaterWorkflow() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.getInventory().countItem(Items.ICE) > 0;
    }

    private static boolean canIceBecomeWaterSource(ClientLevel level, BlockPos pos) {
        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        //#if MC > 11904
        return !belowState.getCollisionShape(level, belowPos, CollisionContext.empty()).isEmpty()
                || !belowState.getFluidState().isEmpty();
        //#else
        //$$ return belowState.getMaterial().blocksMotion() || belowState.getMaterial().isLiquid();
        //#endif
    }

    private static boolean shouldSkipWaterloggedTarget(BlockState requiredState) {
        return Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue() && isWaterloggedTarget(requiredState);
    }

    private static boolean isWaterloggedTarget(BlockState requiredState) {
        return requiredState.hasProperty(BlockStateProperties.WATERLOGGED)
                && requiredState.getValue(BlockStateProperties.WATERLOGGED);
    }

    private static boolean isCurrentWaterlogged(BlockState currentState) {
        return currentState.hasProperty(BlockStateProperties.WATERLOGGED)
                && currentState.getValue(BlockStateProperties.WATERLOGGED);
    }

    private static boolean isDryWaterloggedBlock(BlockState requiredState, BlockState currentState) {
        return isWaterloggedTarget(requiredState)
                && currentState.getBlock() == requiredState.getBlock()
                && !isCurrentWaterlogged(currentState);
    }

    private static boolean isWorkflowComplete(BlockState requiredState, BlockState currentState) {
        if (isWaterloggedTarget(requiredState)) {
            return currentState.getBlock() == requiredState.getBlock() && isCurrentWaterlogged(currentState);
        }
        return BlockStateUtils.isCorrectWaterLevel(requiredState, currentState);
    }

    private class WaterTaskAction implements PrintTaskAction {
        private final boolean finalPlacement;

        private WaterTaskAction(boolean finalPlacement) {
            this.finalPlacement = finalPlacement;
        }

        @Override
        public void onQueued(SchematicBlockContext context, Action action) {
<<<<<<< HEAD
            refreshState(context.currentState);
        }

        @Override
        public void onSuccess(SchematicBlockContext context, Action action) {
            if (this.finalPlacement) {
                complete = true;
            } else {
=======
            if (!this.finalPlacement) {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                icePlacementSent = true;
                iceBreakSent = false;
                refreshState(context.currentState);
            }
        }

        @Override
<<<<<<< HEAD
        public void onCancelled(SchematicBlockContext context, Action action) {
            if (!this.finalPlacement) {
                icePlacementSent = false;
            }
            refreshState(context.currentState);
=======
        public void onSuccess(SchematicBlockContext context, Action action) {
            if (this.finalPlacement) {
                complete = true;
            } else {
                this.onQueued(context, action);
            }
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        }

        @Override
        public void onFailure(SchematicBlockContext context, Action action) {
            aborted = true;
        }
    }
}
