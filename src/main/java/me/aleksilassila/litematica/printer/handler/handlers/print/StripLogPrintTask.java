package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockStateUtils;
import me.aleksilassila.litematica.printer.interaction.StrippableBlockPort;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongSupplier;

/** Owns the place-log then strip-log transaction for one schematic position. */
final class StripLogPrintTask implements PrintTask {
    private static final int CONFIRM_TIMEOUT_TICKS = 20;
    private final BlockPos pos;
    private final Block sourceBlock;
    private final Block requiredBlock;
    private final Direction.Axis axis;
    private final LongSupplier tickClock;
    private StripLogTaskStage stage;
    private long stageSinceTick;
    private long retryAtTick;
    private long actionGeneration;

    private StripLogPrintTask(
            BlockPos pos,
            Block sourceBlock,
            Block requiredBlock,
            Direction.Axis axis,
            LongSupplier tickClock
    ) {
        this.pos = pos.immutable();
        this.sourceBlock = sourceBlock;
        this.requiredBlock = requiredBlock;
        this.axis = axis;
        this.tickClock = tickClock;
        this.stage = StripLogTaskStage.PLACE_LOG;
        this.stageSinceTick = tickClock.getAsLong();
    }

    @Nullable
    static StripLogPrintTask tryCreate(
            SchematicBlockContext context,
            LongSupplier tickClock,
            StrippableBlockPort strippableBlocks
    ) {
        if (!Configs.Print.STRIP_LOGS.getBooleanValue()) return null;
        Direction.Axis axis = context.requiredState.hasProperty(BlockStateProperties.AXIS)
                ? context.requiredState.getValue(BlockStateProperties.AXIS)
                : null;
        if (axis == null) return null;
        Block source = strippableBlocks.sourceFor(context.requiredState.getBlock());
        if (source == null) return null;
        Block current = context.currentState.getBlock();
        if (!BlockStateUtils.isReplaceable(context.currentState) && current != source) return null;
        return new StripLogPrintTask(
                context.blockPos,
                source,
                context.requiredState.getBlock(),
                axis,
                tickClock
        );
    }

    @Override public BlockPos pos() { return this.pos; }

    @Override
    public boolean shouldKeep(ClientLevel level, WorldSchematic schematic) {
        if (!Configs.Print.STRIP_LOGS.getBooleanValue()) return false;
        BlockState required = schematic.getBlockState(this.pos);
        if (required.getBlock() != this.requiredBlock) return false;
        this.reconcile(level.getBlockState(this.pos));
        return this.stage != StripLogTaskStage.COMPLETE;
    }

    @Override
    public boolean isWaitingForWorldUpdate(ClientLevel level, WorldSchematic schematic) {
        this.reconcile(level.getBlockState(this.pos));
        return this.stage.waitsForWorldUpdate() || this.stage == StripLogTaskStage.RETRY_WAIT;
    }

    @Override
    public long nextCheckTick() {
        return switch (this.stage) {
            case WAIT_LOG_CONFIRM, WAIT_STRIP_CONFIRM -> this.stageSinceTick + CONFIRM_TIMEOUT_TICKS;
            case RETRY_WAIT -> this.retryAtTick;
            case PLACE_LOG, STRIP_LOG, COMPLETE -> this.tickClock.getAsLong();
        };
    }

    @Override
    public PrintTaskBuildResult buildAction(SchematicBlockContext context) {
        this.reconcile(context.currentState);
        return switch (this.stage) {
            case PLACE_LOG -> PrintTaskBuildResult.action(
                    new Action()
                            .setSides(this.axis)
                            .setItems(this.sourceBlock.asItem(), this.requiredBlock.asItem()),
                    this.newActionHandle(StripLogTaskStage.PLACE_LOG, StripLogTaskStage.WAIT_LOG_CONFIRM)
            );
            case STRIP_LOG -> PrintTaskBuildResult.action(
                    new ClickAction().setItems(Reference.AXE_ITEMS),
                    this.newActionHandle(StripLogTaskStage.STRIP_LOG, StripLogTaskStage.WAIT_STRIP_CONFIRM)
            );
            case WAIT_LOG_CONFIRM, WAIT_STRIP_CONFIRM, RETRY_WAIT, COMPLETE -> PrintTaskBuildResult.SKIP;
        };
    }

    @Override
    public @Nullable PrintTaskAction createActionHandle(SchematicBlockContext context, Action action) {
        return null;
    }

    private PrintTaskAction newActionHandle(StripLogTaskStage expected, StripLogTaskStage success) {
        long token = ++this.actionGeneration;
        return new PrintTaskAction() {
            @Override
            public BlockState expectedBlockState(SchematicBlockContext context, Action action) {
                if (expected == StripLogTaskStage.PLACE_LOG) {
                    if (context.client.player != null
                            && context.client.player.getMainHandItem().is(requiredBlock.asItem())) {
                        return context.requiredState;
                    }
                    return sourceBlock.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
                }
                return context.requiredState;
            }

            @Override public void onQueued(SchematicBlockContext context, Action action) { submitted(); }
            @Override public void onSuccess(SchematicBlockContext context, Action action) { submitted(); }
            @Override public void onFailure(SchematicBlockContext context, Action action) { failed(); }
            @Override public void onCancelled(SchematicBlockContext context, Action action) { failed(); }

            private void submitted() {
                if (token == actionGeneration && (stage == expected || stage == success)) transition(success);
            }

            private void failed() {
                if (token != actionGeneration) return;
                retryAtTick = tickClock.getAsLong() + 1L;
                transition(StripLogTaskStage.RETRY_WAIT);
            }
        };
    }

    private void reconcile(BlockState current) {
        if (this.stage == StripLogTaskStage.COMPLETE) return;
        long now = this.tickClock.getAsLong();
        StripLogStageResolver.Observation observation;
        if (isCorrectFinalState(current)) observation = StripLogStageResolver.Observation.COMPLETE;
        else if (current.getBlock() == this.sourceBlock) observation = StripLogStageResolver.Observation.SOURCE_LOG;
        else if (BlockStateUtils.isReplaceable(current)) observation = StripLogStageResolver.Observation.REPLACEABLE;
        else observation = StripLogStageResolver.Observation.BLOCKED;
        this.transition(StripLogStageResolver.resolve(
                this.stage,
                observation,
                now - this.stageSinceTick >= CONFIRM_TIMEOUT_TICKS,
                now >= this.retryAtTick
        ));
    }

    private boolean isCorrectFinalState(BlockState current) {
        return current.getBlock() == this.requiredBlock
                && current.hasProperty(BlockStateProperties.AXIS)
                && current.getValue(BlockStateProperties.AXIS) == this.axis;
    }

    private void transition(StripLogTaskStage next) {
        if (this.stage == next) return;
        this.stage = next;
        this.stageSinceTick = this.tickClock.getAsLong();
        if (next == StripLogTaskStage.COMPLETE) this.actionGeneration++;
    }

}
