package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.HudStatus;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class FluidHandler extends FeatureModuleBase {
    public final static String NAME = "fluid";
    private static final Direction[] PLACEMENT_SIDE_ORDER = {
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP
    };

    private List<String> fillBlocks = new ArrayList<>();
    private List<Item> fillItems = new ArrayList<>();
    private Item[] fillItemArray = new Item[0];

    private List<String> fluidBlocks = new ArrayList<>();
    private Set<Fluid> fluids = Set.of();
    private int observedScanConfigHash = Integer.MIN_VALUE;
    private final Set<BlockPos> retryTargets = new LinkedHashSet<>();
    private final Set<BlockPos> inFlightTargets = new LinkedHashSet<>();
    private final Map<BlockPos, Long> inFlightSince = new HashMap<>();

    public FluidHandler(PrinterRuntime runtime) {
        super(runtime, NAME, PrintModeType.FLUID, Configs.Core.FLUID, Configs.Fluid.FLUID_SELECTION_TYPE, true);
        runtime.events().subscribe(event -> {
            if (event instanceof RuntimeEvent.BlockUpdated update) {
                BlockPos pos = new BlockPos(update.x(), update.y(), update.z());
                if (this.inFlightTargets.remove(pos)) {
                    this.inFlightSince.remove(pos);
                    if (this.isTargetFluid(pos)) this.retryTargets.add(pos);
                }
            }
        });
    }

    @Override
    protected int getTickInterval() {
        return Configs.Placement.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected void preprocess() {
        this.refreshTargetStates();
        // 填充方块
        List<String> fileBlocks = Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.getStrings();
        if (!fileBlocks.equals(fillBlocks)) {
            fillBlocks = new ArrayList<>(fileBlocks);
            fillItems = new ArrayList<>();
            if (!fileBlocks.isEmpty()) {
                fillItems.addAll(RegistryFilterResolver.resolveItems(fillBlocks));
            }
            fillItemArray = fillItems.toArray(new Item[0]);
        }
        // 流体方块
        List<String> fluidBlocks = Configs.Fluid.FLUID_LIST.getStrings();
        if (!fluidBlocks.equals(this.fluidBlocks)) {
            this.fluidBlocks = new ArrayList<>(fluidBlocks);
            fluids = fluidBlocks.isEmpty() ? Set.of() : RegistryFilterResolver.resolveFluids(this.fluidBlocks);
        }
        if (fillItems.isEmpty()) {
            this.hudStats.recordStatus(HudStatsManager.Mode.FLUID, HudStatus.NO_FLUID_BLOCK);
        } else if (this.fluidBlocks.isEmpty()) {
            this.hudStats.recordStatus(HudStatsManager.Mode.FLUID, HudStatus.NO_FLUID_CONFIG);
        } else {
            this.hudStats.recordStatus(HudStatsManager.Mode.FLUID, HudStatus.RUNNING);
        }
        int scanConfigHash = this.getScanConfigHash();
        if (this.observedScanConfigHash != Integer.MIN_VALUE
                && this.observedScanConfigHash != scanConfigHash) {
            this.scanEngine.resetOwner(NAME);
            this.requestFullScan();
        }
        this.observedScanConfigHash = scanConfigHash;
    }

    @Override
    protected void onRuntimeReset() {
        this.observedScanConfigHash = Integer.MIN_VALUE;
        this.retryTargets.clear();
        this.inFlightTargets.clear();
        this.inFlightSince.clear();
    }

    @Override
    protected boolean hasRunnableIterationWork() {
        return !this.retryTargets.isEmpty();
    }

    @Override
    protected boolean hasWaitingIterationWork() {
        return !this.inFlightTargets.isEmpty();
    }

    private void refreshTargetStates() {
        if (this.level == null) return;
        this.retryTargets.removeIf(pos -> !this.isTargetFluid(pos));
        long now = this.level.getGameTime();
        List<BlockPos> stale = new ArrayList<>();
        for (Map.Entry<BlockPos, Long> entry : this.inFlightSince.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!this.inFlightTargets.contains(pos) || !this.isTargetFluid(pos)) {
                stale.add(pos);
            } else if (now > entry.getValue()) {
                this.inFlightTargets.remove(pos);
                this.retryTargets.add(pos);
                stale.add(pos);
            }
        }
        for (BlockPos pos : stale) this.inFlightSince.remove(pos);
    }

    @Override
    protected boolean canIterate() {
        return !fillItems.isEmpty() && !fluids.isEmpty();
    }

    @Override
    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return true;
    }

    @Override
    protected boolean iterationPositionsAreExactCandidates() {
        return true;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        Predicate<BlockPos> reachPredicate = this.createScanReachPredicate();
        List<BlockPos> retainedTargets = this.retryTargets.stream()
                .filter(pos -> !this.inFlightTargets.contains(pos)
                        && reachPredicate.test(pos)
                        && selectionPredicate.test(pos)
                        && this.isTargetFluid(pos))
                .toList();
        if (scanSourceBoxes.isEmpty()) {
            return retainedTargets;
        }

        // Finish the initial pass, then let ModuleScanCoordinator enter lazy mode. The lazy
        // policy bounds restart frequency while retaining recovery when a client-side fluid
        // condition changes; dirty regions still run first.
        //
        // Iterate the scan session directly (Beta2.6 behaviour). The session cursor resumes from
        // where the previous tick left off and yields distance-ordered targets for as long as the
        // per-tick scan budget allows. No intermediate FIFO queue: a queue serialised work to one
        // target per tick and let already-placed ("zombie") entries accumulate to ~15k, which read
        // as a slow ring-by-ring expansion even though the scan itself finished in 3 ticks.
        Iterable<BlockPos> source = this.scanEngine.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                null,
                this.player,
                this.getScanGuardLimit(),
                ScanIntent.FLUID,
                pos -> this.isReadyFluidTarget(pos)
                        && !this.retryTargets.contains(pos)
                        && !this.inFlightTargets.contains(pos),
                pos -> reachPredicate.test(pos) && selectionPredicate.test(pos)
        );
        return retainedTargets.isEmpty()
                ? source
                : com.google.common.collect.Iterables.concat(retainedTargets, source);
    }

    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        return this.isTargetFluid(blockPos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (this.inFlightTargets.contains(blockPos)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        FluidState fluidState = level.getBlockState(blockPos).getFluidState();
        if (!this.isTargetFluid(fluidState)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        if (!InventoryUtils.switchToItems(player, fillItemArray)) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.FLUID, HudStatus.MISSING_FLUID_FILL_BLOCK);
            this.missingMaterials.recordMissing(
                    fillItemArray,
                    null,
                    null,
                    level.getGameTime()
            );
            setIterationConsumedEffectiveExecution(false);
            this.retryTargets.add(blockPos.immutable());
            if (this.actionBroker.isResourceHeld(ResourceLease.INVENTORY)) {
                skipIteration.set(true);
            }
            return;
        }
        this.missingMaterials.resolve(fillItemArray, null);
        BlockPos clickTarget = blockPos;
        Direction clickSide = Direction.DOWN;
        if (!Configs.Print.PLACE_IN_AIR.getBooleanValue()) {
            Direction placementSide = this.findPlacementSide(blockPos);
            if (placementSide == null) {
                this.hudStats.recordDeferred(HudStatsManager.Mode.FLUID, HudStatus.NO_VALID_FACE);
                // This was ready when scanned but its support disappeared before execution.
                setIterationConsumedEffectiveExecution(false);
                this.retryTargets.add(blockPos.immutable());
                return;
            }
            clickTarget = blockPos.relative(placementSide);
            clickSide = placementSide.getOpposite();
        }
        if (!this.actionBroker.queueClick(
                clickTarget,
                clickSide,
                Vec3.ZERO,
                false,
                1,
                fillItemArray,
                ActionPort.ActionSource.FLUID
        )) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.FLUID, HudStatus.ACTION_QUEUE_BUSY);
            setIterationConsumedEffectiveExecution(false);
            this.retryTargets.add(blockPos.immutable());
            skipIteration.set(true);
            return;
        }
        BlockState previousState = level.getBlockState(blockPos);
        ActionPort.SendResult sendResult = this.actionBroker.sendQueue(player);
        if (sendResult.isWaiting()) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.FLUID, HudStatus.WAITING_LOOK);
            this.retryTargets.add(blockPos.immutable());
            skipIteration.set(true);
            return;
        }
        if (!sendResult.isSent()) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.FLUID, HudStatus.PLACEMENT_NOT_SENT);
            setIterationConsumedEffectiveExecution(false);
            this.retryTargets.add(blockPos.immutable());
            return;
        }
        this.retryTargets.remove(blockPos);
        BlockPos retainedPos = blockPos.immutable();
        this.inFlightTargets.add(retainedPos);
        this.inFlightSince.put(retainedPos, this.level.getGameTime());
        this.hudStats.trackExpectedBlockChange(HudStatsManager.Mode.FLUID, blockPos, previousState);
        this.hudStats.recordRateUnit(HudStatsManager.Mode.FLUID, 1);
        this.hudStats.recordStatus(HudStatsManager.Mode.FLUID, HudStatus.RUNNING);
        this.setBlockPosCooldown(blockPos, ConfigUtils.getPlaceCooldown());
    }

    private Direction findPlacementSide(BlockPos blockPos) {
        for (Direction side : PLACEMENT_SIDE_ORDER) {
            BlockPos neighborPos = blockPos.relative(side);
            if (PrinterUtils.canBeClicked(this.level, neighborPos)
                    && !BlockUtils.isReplaceable(this.level.getBlockState(neighborPos))) {
                return side;
            }
        }
        return null;
    }

    private boolean isTargetFluid(BlockPos blockPos) {
        return this.level != null && this.isTargetFluid(this.level.getBlockState(blockPos).getFluidState());
    }

    private boolean isReadyFluidTarget(BlockPos blockPos) {
        if (this.level == null) {
            return false;
        }
        // Only target cells the fill block can actually replace. Waterlogged non-replaceable
        // blocks (kelp, seagrass, plants) still report a source fluid state, but a solid block
        // cannot be placed into them: BlockPlaceContext.canPlace() fails on replaceClicked, so
        // every scan would emit them and every attempt would be INTERACTION_REJECTED. Excluding
        // them both avoids wasted rejected traffic and lets the scan actually complete so the
        // module can settle into lazy scanning once all real water is filled.
        if (!BlockUtils.isReplaceable(this.level.getBlockState(blockPos))) {
            return false;
        }
        return this.isTargetFluid(blockPos)
                && (Configs.Print.PLACE_IN_AIR.getBooleanValue() || this.findPlacementSide(blockPos) != null);
    }

    private boolean isTargetFluid(FluidState fluidState) {
        return fluids.contains(fluidState.getType())
                && (Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue() || fluidState.isSource());
    }

    private int getScanConfigHash() {
        int result = this.fillBlocks.hashCode();
        result = 31 * result + this.fluidBlocks.hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue());
        result = 31 * result + Boolean.hashCode(Configs.Print.PLACE_IN_AIR.getBooleanValue());
        return result;
    }
}
