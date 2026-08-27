package me.aleksilassila.litematica.printer.handler.handlers;

import com.google.common.collect.Iterables;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementExecutor;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskAction;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintWorkflowScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.FallingPlacementTracker;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

public class PrintHandler extends FeatureModuleBase {
    public final static String NAME = "print";

    private final Guides guides = new Guides();
    private Action action;
    @Nullable
    private PrintTaskAction printTaskAction;

    private SchematicBlockContext ctx;
    private final PrintWorkflowScheduler printTasks;
    private final SortedSchematicTargetQueue sortedTargets;
    private final PrintPlacementExecutor placementExecutor;
    private final FallingPlacementTracker fallingPlacements = new FallingPlacementTracker();
    private final Set<BlockPos> retryTargets = new LinkedHashSet<>();

    private List<String> printSkipListCache = List.of();
    private String[] printSkipFilters = new String[0];
    private int observedActionConfigHash = Integer.MIN_VALUE;

    public PrintHandler(PrinterRuntime runtime) {
        super(runtime, NAME, PrintModeType.PRINTER, Configs.Core.PRINT, Configs.Print.PRINT_SELECTION_TYPE, true);
        this.printTasks = new PrintWorkflowScheduler(runtime::currentTick, runtime.strippableBlocks());
        runtime.events().subscribe(event -> {
            if (event instanceof RuntimeEvent.BlockUpdated update) {
                this.printTasks.wake(new BlockPos(update.x(), update.y(), update.z()));
            }
        });
        this.sortedTargets = new SortedSchematicTargetQueue(this.scanEngine);
        this.placementExecutor = new PrintPlacementExecutor(
                this.actionBroker,
                this.cooldownUtils,
                runtime.inventorySwitchGuard(),
                this.hudStats,
                this.missingMaterials,
                this.litematica,
                this.fallingPlacements,
                runtime.materialRequests(),
                this.placementRateController);
    }

    public SchematicBlockContext getContext() {
        return ctx;
    }

    @Override
    protected int getTickInterval() {
        if (this.printTasks.hasActiveWorkflow()) {
            return 0;
        }
        return this.placementRateController.effectiveIntervalTicks();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean hasPendingIterationWork() {
        return this.printTasks.hasReadyWorkflow() || this.sortedTargets.hasPendingWork();
    }

    @Override
    protected boolean hasRunnableIterationWork() {
        return this.hasPendingIterationWork() || !this.retryTargets.isEmpty();
    }

    @Override
    protected boolean hasWaitingIterationWork() {
        return this.printTasks.hasActiveWorkflow() && !this.printTasks.hasReadyWorkflow();
    }

    @Override
    protected void onInventoryAvailabilityChanged() {
        // A completed PRINT scan is invalidation-only, and inventory changes do not dirty world
        // positions. Rebuild the candidate source so targets skipped for missing material become
        // eligible again when the player receives any item.
        this.printTasks.onInventoryAvailabilityChanged();
        this.retryTargets.clear();
        this.sortedTargets.clear();
        this.scanEngine.resetOwner(NAME);
        this.scanEngine.resetOwner("print_sorted");
        this.requestFullScan();
    }

    @Override
    protected boolean isSchematicBlockHandler() {
        return true;
    }

    @Override
    protected void preprocess() {
        this.printTasks.tick(this.level, this.litematica.schematicWorld());
        this.updatePrintSkipCache();
        int actionConfigHash = this.getActionConfigHash();
        if (this.observedActionConfigHash != Integer.MIN_VALUE
                && this.observedActionConfigHash != actionConfigHash) {
            this.sortedTargets.clear();
            this.scanEngine.resetOwner(NAME);
            this.scanEngine.resetOwner("print_sorted");
            this.requestFullScan();
        }
        this.observedActionConfigHash = actionConfigHash;
    }

    @Override
    protected void onRuntimeReset() {
        this.action = null;
        this.printTaskAction = null;
        this.ctx = null;
        this.printTasks.clear();
        this.fallingPlacements.clear();
        this.retryTargets.clear();
        this.sortedTargets.clear();
        this.observedActionConfigHash = Integer.MIN_VALUE;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        WorldSchematic schematic = this.litematica.schematicWorld();
        List<BlockPos> runnableTasks = this.printTasks.readyTargetPositions();
        List<BlockPos> retainedTargets = Configs.Print.PRINT_SORT_TARGETS.getBooleanValue()
                ? List.of() : new ArrayList<>(this.retryTargets);
        Iterable<BlockPos> normalPositions = this.getNormalIterationPositions(playerInteractionBox, schematic);
        if (runnableTasks.isEmpty() && retainedTargets.isEmpty()) return normalPositions;
        return Iterables.concat(runnableTasks, retainedTargets, normalPositions);
    }

    private Iterable<BlockPos> getNormalIterationPositions(
            PrinterBox playerInteractionBox,
            @Nullable WorldSchematic schematic
    ) {
        if (!Configs.Print.PRINT_SORT_TARGETS.getBooleanValue()) {
            this.sortedTargets.clear();
            return this.getCachedFilteredIterationPositions(
                    playerInteractionBox, ScanIntent.PRINT, pos -> !this.retryTargets.contains(pos));
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
        WorldSchematic schematic = this.litematica.schematicWorld();
        if (schematic == null) return false;
        if (InteractionUtils.getRuntime().isRecentlyBroken(blockPos) && !this.printTasks.isActiveTaskPos(blockPos)) {
            return false;
        }
        this.ctx = new SchematicBlockContext(client, level, schematic, blockPos);
        if (this.ctx.requiredState.getBlock() instanceof net.minecraft.world.level.block.FallingBlock
                && this.fallingPlacements.blocks(
                        blockPos,
                        this.level.getGameTime(),
                        Configs.Placement.FALLING_CHECK.getBooleanValue(),
                        (pendingPos, expectedState) -> this.level.getBlockState(pendingPos).equals(expectedState)
                )) {
            return false;
        }
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
        Optional<Action> action = this.guides.buildAction(ctx);
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

    private int getActionConfigHash() {
        int result = Boolean.hashCode(Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.BREAK_WRONG_STATE_BLOCK.getBooleanValue());
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
        if (taskAction == null && result.taskEvent() == PrintPlacementResult.TaskEvent.SUCCESS) {
            this.retryTargets.remove(blockPos);
            this.sortedTargets.remove(blockPos);
        } else if (taskAction == null && result.shouldRetryTarget()) {
            if (!Configs.Print.PRINT_SORT_TARGETS.getBooleanValue()) {
                this.retryTargets.add(blockPos.immutable());
            } else {
                this.sortedTargets.requeue(blockPos);
            }
        }
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
            case DEFERRED -> this.printTasks.onActionDeferred(this.ctx);
            case CANCELLED -> this.printTasks.onActionCancelled(taskAction, this.ctx, this.action);
            case MATERIAL_UNAVAILABLE -> this.printTasks.onMaterialUnavailable(this.ctx);
            case WORLD_BLOCKED -> {
                // Falling blocks are ordinary targets, not multi-stage workflow actions.
            }
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
