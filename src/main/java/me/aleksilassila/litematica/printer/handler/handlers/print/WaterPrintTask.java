package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
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
//#if MC >= 260300
//$$ import net.minecraft.tags.BlockTags;
//#endif
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Owns one water/ice workflow independently from the full selection scan. */
public final class WaterPrintTask implements PrintTask {
    private static final int STALL_PADDING_TICKS = 8;
    private static final int MIN_STALL_TICKS = 12;
    private static final int MAX_STALL_TICKS = 40;

    private final BlockPos pos;
    private final LongSupplier tickClock;
    private WaterTaskStage stage = WaterTaskStage.RESERVED;
    private long stageSinceTick;
    private long retryAtTick;
    private long naturalSourceDeadline = Long.MIN_VALUE;
    private long actionGeneration;
    private boolean started;
    private long queuedActionToken = -1L;
    private @Nullable BooleanSupplier warehouseIcePending;
    private int rejectedIceNonce;
    private @Nullable BlockState rejectedIceGoal;

    private WaterPrintTask(BlockPos pos, LongSupplier tickClock) {
        this.pos = pos.immutable();
        this.tickClock = tickClock;
        this.stageSinceTick = tickClock.getAsLong();
    }

    @Nullable
    public static WaterPrintTask tryCreate(SchematicBlockContext context, LongSupplier tickClock) {
        return isCandidate(context) ? new WaterPrintTask(context.blockPos, tickClock) : null;
    }

    @Override
    public BlockPos pos() {
        return this.pos;
    }

    @Override public boolean hasStarted() { return this.started || this.queuedActionToken >= 0L; }

    @Override
    public boolean hasInFlightOwnership() {
        return this.queuedActionToken >= 0L
                || this.stage == WaterTaskStage.WAIT_REMOVE_CONFIRM
                || this.stage == WaterTaskStage.WAIT_ICE_CONFIRM
                || this.stage == WaterTaskStage.WAIT_WATER_CONFIRM
                || this.stage == WaterTaskStage.WAIT_FINAL_CONFIRM;
    }

    WaterTaskStage stage() {
        return this.stage;
    }

    boolean isBreaking() {
        return Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                && (this.stage == WaterTaskStage.REMOVE_EXISTING || this.stage == WaterTaskStage.BREAK_ICE);
    }

    @Override
    public boolean shouldKeep(ClientLevel level, WorldSchematic schematic) {
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return false;
        }
        BlockState requiredState = schematic.getBlockState(this.pos);
        if (!BlockStateUtils.isWaterBlock(requiredState) || shouldSkipWaterloggedTarget(requiredState)) {
            return false;
        }
        this.reconcile(level, requiredState, level.getBlockState(this.pos));
        return this.stage != WaterTaskStage.COMPLETE;
    }

    @Override
    public boolean isWaitingForWorldUpdate(ClientLevel level, WorldSchematic schematic) {
        BlockState requiredState = schematic.getBlockState(this.pos);
        BlockState currentState = level.getBlockState(this.pos);
        this.reconcile(level, requiredState, currentState);
        if (this.stage == WaterTaskStage.WAIT_SUPPORT || this.stage == WaterTaskStage.WAIT_FINAL_CHANGE) {
            this.retryAtTick = this.tickClock.getAsLong() + 5L;
        }
        return this.stage.waitsForWorldUpdate()
                || this.stage == WaterTaskStage.RETRY_WAIT;
    }

    @Override
    public long nextCheckTick() {
        if (this.stage == WaterTaskStage.WAIT_NATURAL_SOURCE) return this.naturalSourceDeadline;
        if (this.warehouseIcePending != null && !this.warehouseIcePending.getAsBoolean()) {
            return this.tickClock.getAsLong();
        }
        if (this.stage == WaterTaskStage.RETRY_WAIT || this.stage == WaterTaskStage.WAIT_SUPPORT
                || this.stage == WaterTaskStage.WAIT_FINAL_CHANGE) {
            return this.retryAtTick;
        }
        if (this.stage.waitsForWorldUpdate()) return this.stageSinceTick + MIN_STALL_TICKS;
        return this.tickClock.getAsLong();
    }

    @Override
    public PrintTaskBuildResult buildAction(SchematicBlockContext context) {
        this.reconcile(context.level, context.requiredState, context.currentState);
        return switch (this.stage) {
            case COMPLETE -> PrintTaskBuildResult.SKIP;
            case REMOVE_EXISTING -> this.breakBlockForWorkflow(context, false);
            case PLACE_ICE -> this.buildIcePlacement(context);
            case BREAK_ICE -> this.breakBlockForWorkflow(context, true);
            case PLACE_FINAL_BLOCK -> PrintTaskBuildResult.PASS;
            case RESERVED, WAIT_SUPPORT, WAIT_REMOVE_CONFIRM, WAIT_NATURAL_SOURCE, WAIT_ICE_CONFIRM,
                    WAIT_WATER_CONFIRM, WAIT_FINAL_CONFIRM, WAIT_FINAL_CHANGE, RETRY_WAIT -> PrintTaskBuildResult.SKIP;
        };
    }

    @Override
    public @Nullable PrintTaskAction createActionHandle(SchematicBlockContext context, Action action) {
        this.reconcile(context.level, context.requiredState, context.currentState);
        if (this.stage != WaterTaskStage.PLACE_FINAL_BLOCK) {
            return null;
        }
        return new FinalPlacement();
    }

    private PrintTaskBuildResult buildIcePlacement(SchematicBlockContext context) {
        if (!BlockStateUtils.isReplaceable(context.currentState)
                || !canStartIceWaterWorkflow(context.level, context.blockPos)) {
            return PrintTaskBuildResult.SKIP;
        }
        if (this.awaitNaturalSource(context.level, context.currentState, context.requiredState)) {
            this.transition(WaterTaskStage.WAIT_NATURAL_SOURCE);
            return PrintTaskBuildResult.SKIP;
        }
        Action action = new Action().setItem(Items.ICE).setRequiresSupport();
        return PrintTaskBuildResult.action(action, new IcePlacement());
    }

    private PrintTaskBuildResult breakBlockForWorkflow(SchematicBlockContext context, boolean ice) {
        if (!InteractionUtils.canBreakBlock(context.blockPos)) {
            return PrintTaskBuildResult.SKIP;
        }
        if (ice && !IceBreakToolSelector.switchToNonSilkTouchBreakItem(context.client, context.blockPos)) {
            return PrintTaskBuildResult.SKIP;
        }

        InteractionUtils.getRuntime().suppressQueuedBreaks(2);
        BlockBreakResult result = ice
                ? InteractionUtils.getRuntime().continueDestroyBlockWithoutToolSwitch(
                        context.blockPos, Direction.DOWN, false)
                : InteractionUtils.getRuntime().continueDestroyBlockWithoutTracking(
                        context.blockPos, Direction.DOWN);
        if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
            this.started = true;
            this.transition(ice
                    ? WaterTaskStage.WAIT_WATER_CONFIRM
                    : WaterTaskStage.WAIT_REMOVE_CONFIRM);
            return PrintTaskBuildResult.SKIP;
        }
        if (result == BlockBreakResult.IN_PROGRESS) {
            this.started = true;
            return PrintTaskBuildResult.SKIP;
        }
        this.retrySoon();
        return PrintTaskBuildResult.SKIP;
    }

    private void reconcile(ClientLevel level, BlockState requiredState, BlockState currentState) {
        // A queued click or an outstanding warehouse request can still replace a regenerated source.
        if (this.stage == WaterTaskStage.COMPLETE
                || this.stage == WaterTaskStage.WAIT_ICE_CONFIRM && (this.queuedActionToken >= 0L
                    || this.warehouseIcePending != null && this.warehouseIcePending.getAsBoolean())) {
            return;
        }
        this.warehouseIcePending = null;
        long now = this.tickClock.getAsLong();
        WaterStageResolver.Observation observation = observe(level, requiredState, currentState);
        if (this.stage == WaterTaskStage.WAIT_NATURAL_SOURCE
                && observation == WaterStageResolver.Observation.REPLACEABLE_WITH_SUPPORT
                && this.awaitNaturalSource(level, currentState, requiredState)) return;
        this.transition(WaterStageResolver.resolve(
                this.stage,
                observation,
                isWaterloggedTarget(requiredState),
                this.isStageStalled(level, currentState),
                now >= this.retryAtTick
        ));
    }

    private WaterStageResolver.Observation observe(
            ClientLevel level,
            BlockState requiredState,
            BlockState currentState
    ) {
        if (isWorkflowComplete(requiredState, currentState)) {
            return WaterStageResolver.Observation.COMPLETE;
        }
        if (BlockStateUtils.isCorrectWaterLevel(requiredState, currentState)) {
            return WaterStageResolver.Observation.WATER_READY;
        }
        if ((this.stage == WaterTaskStage.WAIT_FINAL_CONFIRM || this.stage == WaterTaskStage.WAIT_FINAL_CHANGE)
                && isDryWaterloggedBlock(requiredState, currentState)) {
            return WaterStageResolver.Observation.DRY_FINAL_BLOCK;
        }
        if (!canStartIceWaterWorkflow(level, this.pos)) {
            return WaterStageResolver.Observation.WITHOUT_WATER_SUPPORT;
        }
        if (currentState.getBlock() instanceof IceBlock) {
            return WaterStageResolver.Observation.ICE;
        }
        if (BlockStateUtils.isReplaceable(currentState)) {
            return WaterStageResolver.Observation.REPLACEABLE_WITH_SUPPORT;
        }
        return WaterStageResolver.Observation.BLOCKED;
    }

    private void retrySoon() {
        this.retryAtTick = this.tickClock.getAsLong() + 1L;
        this.transition(WaterTaskStage.RETRY_WAIT);
    }

    /** Give a naturally forming source one fluid update before spending ice. Server rules may disable it. */
    @SuppressWarnings("deprecation")
    private boolean awaitNaturalSource(ClientLevel level, BlockState current, BlockState required) {
        long now = this.tickClock.getAsLong();
        if ((this.naturalSourceDeadline != Long.MIN_VALUE && now >= this.naturalSourceDeadline)
                || (!current.isAir() && !current.is(Blocks.WATER))
                || (required.is(Blocks.WATER) && !required.getFluidState().isSource())) return false;
        BlockState below = level.getBlockState(this.pos.below());
        if (!below.isSolid() && !isWaterSource(below)) return false;
        int sources = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = this.pos.relative(side);
            BlockState state = level.getBlockState(neighbour);
            if (!isWaterSource(state) || Shapes.mergedFaceOccludes(current.getCollisionShape(level, this.pos),
                    state.getCollisionShape(level, neighbour), side)) continue;
            if (++sources == 2) {
                if (this.naturalSourceDeadline == Long.MIN_VALUE) {
                    this.naturalSourceDeadline = now + Fluids.WATER.getTickDelay(level) + 1;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isWaterSource(BlockState state) {
        var fluid = state.getFluidState();
        return fluid.isSource() && fluid.getType().isSame(Fluids.WATER);
    }

    private void transition(WaterTaskStage next) {
        if (this.stage == next) {
            return;
        }
        this.stage = next;
        this.stageSinceTick = this.tickClock.getAsLong();
        if (next == WaterTaskStage.COMPLETE || next == WaterTaskStage.WAIT_FINAL_CHANGE) {
            this.actionGeneration++;
        }
    }

    private boolean isStageStalled(ClientLevel level, BlockState currentState) {
        long elapsed = this.tickClock.getAsLong() - this.stageSinceTick;
        if (this.stage == WaterTaskStage.WAIT_ICE_CONFIRM) {
            // Placing ice takes the same time in air and in flow; neither cell is being mined.
            return elapsed > MIN_STALL_TICKS;
        }
        return elapsed > getStallLimit(level, currentState);
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
            return true;
        }
        if (isWaterloggedTarget(context.requiredState)
                && BlockStateUtils.isCorrectWaterLevel(context.requiredState, context.currentState)) {
            return true;
        }
        if (isDryWaterloggedBlock(context.requiredState, context.currentState)) {
            return true;
        }
        if (isWaterloggedTarget(context.requiredState) && !BlockStateUtils.isReplaceable(context.currentState)) {
            return true;
        }
        return isWaterloggedTarget(context.requiredState)
                || BlockStateUtils.isReplaceable(context.currentState);
    }

    private static boolean canStartIceWaterWorkflow(ClientLevel level, BlockPos pos) {
        return canIceBecomeWaterSource(level, pos);
    }

    @SuppressWarnings("deprecation")
    private static boolean canIceBecomeWaterSource(ClientLevel level, BlockPos pos) {
        if (level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos)) {
            return false;
        }
        // Match IceBlock.playerDestroy: collision and contained fluid do not imply water support.
        BlockState belowState = level.getBlockState(pos.below());
        //#if MC >= 260300
        //$$ return belowState.is(BlockTags.ICE_MELTS_WHEN_DESTROYED_ABOVE) || belowState.liquid();
        //#else
        return belowState.blocksMotion() || belowState.liquid();
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

    static @Nullable BooleanSupplier collisionCheck(@Nullable PrintTaskAction handle, SchematicBlockContext context,
            Action action, Direction side, Item[] items, @Nullable Vec3 protocolHit, boolean warehouse) {
        if (handle instanceof IcePlacement) {
            return () -> unobstructed(context, Blocks.ICE.defaultBlockState());
        }
        BlockState goal = context.requiredState;
        if (!(handle instanceof FinalPlacement) || action.getClass() != Action.class
                || items.length != 1 || items[0] != goal.getBlock().asItem()) return null;
        // Only these vanilla placement shapes are determined by the click, look and immediate neighbours.
        var block = goal.getBlock();
        if (block.getClass() != StairBlock.class && block.getClass() != SlabBlock.class) return null;
        if (goal.hasProperty(SlabBlock.TYPE) && goal.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) return null;
        if (warehouse && protocolHit != null) return () -> unobstructed(context, goal);
        return () -> {
            var player = context.client.player;
            if (player == null) return false;
            // A stair or slab lies within its cell. Resolve its exact shape only if an entity occupies the cell.
            if (unobstructed(context, Blocks.ICE.defaultBlockState())) return true;
            // Both vanilla blocks only read the face and fractional hit Y. Rotation changes horizontal offsets only.
            Vec3 hit = protocolHit != null ? protocolHit : Vec3.atCenterOf(context.blockPos)
                    .add(0, action.getSides().get(side).y * 0.5, 0);
            var placement = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, items[0].getDefaultInstance(),
                    new BlockHitResult(hit, side.getOpposite(), context.blockPos, false)) {
                @Override public Direction getHorizontalDirection() {
                    return action.getPlayerLook() == null ? super.getHorizontalDirection()
                            : Direction.fromYRot(action.getPlayerLook().yaw);
                }
            };
            if (!context.blockPos.equals(placement.getClickedPos())) return true;
            BlockState actual = block.getStateForPlacement(placement);
            // An ordinary server may or may not decode the precision hit. Leave ambiguous states to it.
            return actual == null || protocolHit != null && actual != goal || unobstructed(context, actual);
        };
    }

    private static boolean unobstructed(SchematicBlockContext context, BlockState state) {
        return context.level.isUnobstructed(state, context.blockPos, CollisionContext.placementContext(context.client.player));
    }

    private class WaterTaskAction implements PrintTaskAction {
        final long token = ++actionGeneration;
        private final WaterTaskStage expected;
        private final WaterTaskStage success;

        private WaterTaskAction(WaterTaskStage expected, WaterTaskStage success) {
            this.expected = expected;
            this.success = success;
        }

        @Override
        public BlockState expectedBlockState(SchematicBlockContext context, Action action) {
            return this.expected == WaterTaskStage.PLACE_ICE
                    ? Blocks.ICE.defaultBlockState()
                    : context.requiredState;
        }

        @Override
        public void onQueued(SchematicBlockContext context, Action action) {
            this.markSubmitted(false);
        }

        @Override
        public void onSuccess(SchematicBlockContext context, Action action) {
            this.markSubmitted(true);
        }

        private void markSubmitted(boolean sent) {
            if (sent) {
                started = true;
                if (queuedActionToken == this.token) queuedActionToken = -1L;
            } else {
                queuedActionToken = this.token;
            }
            if (this.token == actionGeneration
                    && (stage == this.expected || stage == this.success)) {
                transition(this.success);
            }
        }

        @Override
        public void onCancelled(SchematicBlockContext context, Action action) {
            this.retryAfterFailure(context);
        }

        @Override
        public void onFailure(SchematicBlockContext context, Action action) {
            this.retryAfterFailure(context);
        }

        final void retryAfterFailure(SchematicBlockContext context) {
            if (queuedActionToken == this.token) queuedActionToken = -1L;
            if (this.token != actionGeneration) return;
            // Deferred callbacks hold an old context; preserve a final block already present in the world.
            if (stage == WaterTaskStage.WAIT_FINAL_CONFIRM
                    && isDryWaterloggedBlock(context.requiredState, context.level.getBlockState(pos))) {
                started = true;
                return;
            }
            retrySoon();
        }
    }

    final class FinalPlacement extends WaterTaskAction {
        private FinalPlacement() {
            super(WaterTaskStage.PLACE_FINAL_BLOCK, WaterTaskStage.WAIT_FINAL_CONFIRM);
        }

        int rejectedIceNonce(SchematicBlockContext context) {
            return rejectedIceNonce > 0 && context.requiredState.equals(rejectedIceGoal) && canSend(context)
                    ? rejectedIceNonce : 0;
        }

        boolean canSend(SchematicBlockContext context) {
            return this.token == actionGeneration && context.client.level == context.level
                    && Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                    && (stage == WaterTaskStage.PLACE_FINAL_BLOCK || stage == WaterTaskStage.WAIT_FINAL_CONFIRM)
                    && isWaterloggedTarget(context.requiredState)
                    && !shouldSkipWaterloggedTarget(context.requiredState)
                    && context.schematic.getBlockState(pos).equals(context.requiredState)
                    && BlockStateUtils.isCorrectWaterLevel(context.requiredState, context.level.getBlockState(pos));
        }

        @Override
        public void onSuccess(SchematicBlockContext context, Action action) {
            super.onSuccess(context, action);
            if (this.token == actionGeneration) rejectedIceNonce = 0;
        }

        void onRejected(SchematicBlockContext context) {
            if (this.token == actionGeneration && stage == WaterTaskStage.WAIT_FINAL_CONFIRM) {
                this.retryAfterFailure(context);
                reconcile(context.level, context.requiredState, context.level.getBlockState(pos));
            }
        }
    }

    final class IcePlacement extends WaterTaskAction {
        private IcePlacement() {
            super(WaterTaskStage.PLACE_ICE, WaterTaskStage.WAIT_ICE_CONFIRM);
        }

        boolean canSend(SchematicBlockContext context) {
            return this.token == actionGeneration && context.client.level == context.level
                    && Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                    && (stage == WaterTaskStage.PLACE_ICE || stage == WaterTaskStage.WAIT_ICE_CONFIRM)
                    && !shouldSkipWaterloggedTarget(context.requiredState)
                    && context.schematic.getBlockState(pos).equals(context.requiredState)
                    && observe(context.level, context.requiredState, context.level.getBlockState(pos))
                            == WaterStageResolver.Observation.REPLACEABLE_WITH_SUPPORT
                    && !awaitNaturalSource(context.level, context.level.getBlockState(pos), context.requiredState);
        }

        @Override
        public void onSuccess(SchematicBlockContext context, Action action) {
            super.onSuccess(context, action);
            if (this.token == actionGeneration && stage == WaterTaskStage.WAIT_ICE_CONFIRM) {
                rejectedIceNonce = 0;
                stageSinceTick = tickClock.getAsLong();
                reconcile(context.level, context.requiredState, context.level.getBlockState(pos));
            }
        }

        void onRejected(SchematicBlockContext context, int nonce) {
            if (this.token == actionGeneration && stage == WaterTaskStage.WAIT_ICE_CONFIRM) {
                warehouseIcePending = null;
                rejectedIceNonce = nonce;
                rejectedIceGoal = context.requiredState;
                retrySoon();
            }
        }

        void awaitWarehouseResult(@Nullable BooleanSupplier pendingRequest) {
            if (this.token == actionGeneration) warehouseIcePending = pendingRequest;
        }
    }
}
