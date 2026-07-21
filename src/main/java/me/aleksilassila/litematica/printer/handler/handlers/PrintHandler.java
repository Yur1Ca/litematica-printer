package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.guide.Guides;
<<<<<<< HEAD
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
=======
import me.aleksilassila.litematica.printer.handler.Module;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementExecutor;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskAction;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskController;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

public class PrintHandler extends Module {
    public final static String NAME = "print";

    private Action action;
    @Nullable
    private PrintTaskAction printTaskAction;

    private SchematicBlockContext ctx;
    private final PrintTaskController printTasks = new PrintTaskController();
    private final SortedSchematicTargetQueue sortedTargets = new SortedSchematicTargetQueue();
    private final PrintPlacementExecutor placementExecutor = new PrintPlacementExecutor();

    private List<String> printSkipListCache = List.of();
    private String[] printSkipFilters = new String[0];
<<<<<<< HEAD
    private int observedActionConfigHash = Integer.MIN_VALUE;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)

    public PrintHandler() {
        super(NAME, PrintModeType.PRINTER, Configs.Core.PRINT, Configs.Print.PRINT_SELECTION_TYPE, true);
    }

    public SchematicBlockContext getContext() {
        return ctx;
    }

    @Override
    protected int getTickInterval() {
        if (this.printTasks.hasActiveTask()) {
            return 0;
        }
        int baseInterval = Configs.Placement.PLACE_INTERVAL.getIntegerValue();
        if (Configs.Placement.RTT_ADAPTIVE_INTERVAL.getBooleanValue()) {
            // 保证重放间隔不低于一次往返(RTT),避免在服务端确认上一次放置前就发下一个导致放错。
            int rttFloor = RttReplayController.INSTANCE.getExtraIntervalTicks(
                    Configs.Placement.RTT_SAFETY_PERCENT.getIntegerValue());
            return Math.max(baseInterval, rttFloor);
        }
        return baseInterval;
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean isSchematicBlockHandler() {
        return true;
    }

    @Override
    protected void preprocess() {
        this.updatePrintSkipCache();
<<<<<<< HEAD
        int actionConfigHash = this.getActionConfigHash();
        if (this.observedActionConfigHash != Integer.MIN_VALUE
                && this.observedActionConfigHash != actionConfigHash) {
            this.sortedTargets.clear();
            ScanCache.INSTANCE.resetOwner(NAME);
            ScanCache.INSTANCE.resetOwner("print_sorted");
            this.requestFullScan();
        }
        this.observedActionConfigHash = actionConfigHash;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    @Override
    protected void onRuntimeReset() {
        this.action = null;
        this.printTaskAction = null;
        this.ctx = null;
        this.printTasks.clear();
        this.sortedTargets.clear();
<<<<<<< HEAD
        this.observedActionConfigHash = Integer.MIN_VALUE;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        BlockPos activeTaskPos = this.printTasks.getActiveTargetPos(level, schematic);
        if (activeTaskPos != null) {
            this.sortedTargets.clear();
            return List.of(activeTaskPos);
        }
        if (!Configs.Print.PRINT_SORT_TARGETS.getBooleanValue()) {
            this.sortedTargets.clear();
            return this.getCachedFilteredIterationPositions(playerInteractionBox, ScanIntent.PRINT, pos -> true);
        }
        if (schematic == null || player == null) {
            this.sortedTargets.clear();
            return playerInteractionBox;
        }
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        if (scanSourceBoxes.isEmpty()) {
            this.sortedTargets.clear();
            return List.of();
        }
        return this.sortedTargets.iterable(scanSourceBoxes, level, schematic, player, getScanGuardLimit());
    }

    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        this.action = null;
        this.printTaskAction = null;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return false;
<<<<<<< HEAD
        if (HudStatsManager.INSTANCE.isPrintPlacementPending(blockPos)) {
            return false;
        }
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        if (InteractionUtils.INSTANCE.isRecentlyBroken(blockPos) && !this.printTasks.isActiveTaskPos(blockPos)) {
            return false;
        }
        this.ctx = new SchematicBlockContext(client, level, schematic, blockPos);
        if (this.shouldSkipRequiredState(ctx.requiredState)) {
            return false;
        }
        PrintTaskBuildResult taskResult = this.printTasks.buildAction(ctx);
        if (taskResult.handled()) {
            if (!taskResult.hasAction()) {
                return false;
            }
            this.action = taskResult.action();
            this.printTaskAction = taskResult.actionHandle();
            return true;
        }
//        Action action = guide.getAction(ctx);
        Optional<Action> action = Guides.INSTANCE.buildAction(ctx);
        if (action.isEmpty())
            return false;
        this.action = action.get();
        this.printTaskAction = this.printTasks.createActionHandle(ctx, this.action);
        return true;
    }

    private void updatePrintSkipCache() {
        List<String> skipList = Configs.Print.PRINT_SKIP_LIST.getStrings();
        if (skipList.equals(this.printSkipListCache)) {
            return;
        }
        this.printSkipListCache = new ArrayList<>(skipList);
        this.printSkipFilters = this.printSkipListCache.toArray(new String[0]);
    }

<<<<<<< HEAD
    private int getActionConfigHash() {
        int result = Boolean.hashCode(Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.PRINT_SKIP.getBooleanValue());
        result = 31 * result + this.printSkipListCache.hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Print.PRINT_REPLACE.getBooleanValue());
        result = 31 * result + Configs.Print.REPLACEABLE_LIST.getStrings().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.REPLACE_CORAL.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.STRIP_LOGS.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.NOTE_BLOCK_TUNING.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.SAFELY_OBSERVER.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.FILL_COMPOSTER.getBooleanValue());
        result = 31 * result + Configs.Print.FILL_COMPOSTER_WHITELIST.getStrings().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Print.BONEMEAL_CROPS.getBooleanValue());
        result = 31 * result + Configs.Print.BONEMEAL_CROPS_CLICKS.getIntegerValue();
        result = 31 * result + Boolean.hashCode(Configs.Print.REPAIR_RAIL_SHAPE.getBooleanValue());
        return result;
    }

=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    private boolean shouldSkipRequiredState(BlockState requiredState) {
        if (!Configs.Print.PRINT_SKIP.getBooleanValue() || this.printSkipFilters.length == 0) {
            return false;
        }
        for (String filter : this.printSkipFilters) {
            if (FilterUtils.matchName(filter, requiredState)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        PrintTaskAction taskAction = this.printTaskAction;
        PrintPlacementResult result = this.placementExecutor.execute(this.ctx, this.action, taskAction);
        if (!result.consumedEffectiveExecution()) {
            setIterationConsumedEffectiveExecution(false);
        }
        if (taskAction != null) {
            this.applyTaskEvent(taskAction, result.taskEvent());
        }
        if (result.skipIteration()) {
            skipIteration.set(true);
        }
        if (result.cooldownTicks() >= 0) {
            setBlockPosCooldown(blockPos, result.cooldownTicks());
        }
    }

    private void applyTaskEvent(PrintTaskAction taskAction, PrintPlacementResult.TaskEvent taskEvent) {
        switch (taskEvent) {
            case SUCCESS -> this.printTasks.onActionSuccess(taskAction, this.ctx, this.action);
            case QUEUED -> this.printTasks.onActionQueued(taskAction, this.ctx, this.action);
<<<<<<< HEAD
            case CANCELLED -> taskAction.onCancelled(this.ctx, this.action);
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            case FAILURE -> this.printTasks.onActionFailure(taskAction, this.ctx, this.action);
        }
    }

    @Override
    public boolean isBlockPosOnCooldown(@Nullable BlockPos pos) {
        if (this.printTasks.isActiveTaskPos(pos)) {
            return false;
        }
        return super.isBlockPosOnCooldown(pos);
    }

}

