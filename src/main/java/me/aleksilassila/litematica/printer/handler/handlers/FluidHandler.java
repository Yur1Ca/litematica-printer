package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
<<<<<<< HEAD
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
=======
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
<<<<<<< HEAD
import net.minecraft.world.level.block.state.BlockState;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class FluidHandler extends Module {
    public final static String NAME = "fluid";
<<<<<<< HEAD
    private static final Direction[] PLACEMENT_SIDE_ORDER = {
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP
    };
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)

    private List<String> fillBlocks = new ArrayList<>();
    private List<Item> fillItems = new ArrayList<>();
    private Item[] fillItemArray = new Item[0];

    private List<String> fluidBlocks = new ArrayList<>();
    private Set<Fluid> fluids = Set.of();
<<<<<<< HEAD
    private int observedScanConfigHash = Integer.MIN_VALUE;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)

    public FluidHandler() {
        super(NAME, PrintModeType.FLUID, Configs.Core.FLUID, Configs.Fluid.FLUID_SELECTION_TYPE, true);
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
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "无流体填充方块");
        } else if (this.fluidBlocks.isEmpty()) {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "无目标流体配置");
        } else {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "运行中");
        }
<<<<<<< HEAD
        int scanConfigHash = this.getScanConfigHash();
        if (this.observedScanConfigHash != Integer.MIN_VALUE
                && this.observedScanConfigHash != scanConfigHash) {
            ScanCache.INSTANCE.resetOwner(NAME);
            this.requestFullScan();
        }
        this.observedScanConfigHash = scanConfigHash;
    }

    @Override
    protected void onRuntimeReset() {
        this.observedScanConfigHash = Integer.MIN_VALUE;
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    @Override
    protected boolean canIterate() {
<<<<<<< HEAD
        return !fillItems.isEmpty() && !fluids.isEmpty();
=======
        return !fillItems.isEmpty() && !fluidBlocks.isEmpty();
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
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
        if (scanSourceBoxes.isEmpty()) {
            return List.of();
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        
        return ScanCache.INSTANCE.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                null,
                this.player,
                this.getScanGuardLimit(),
                ScanIntent.FLUID,
                this::isTargetFluid,
                pos -> this.canReachIterationPosition(pos) && selectionPredicate.test(pos)
        );
    }

    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        return this.isTargetFluid(blockPos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        FluidState fluidState = level.getBlockState(blockPos).getFluidState();
        if (!this.isTargetFluid(fluidState)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        if (!InventoryUtils.switchToItems(player, fillItemArray)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "缺少流体填充方块");
            setIterationConsumedEffectiveExecution(false);
            if (me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.shouldPauseForSwitchRequest()
                    || me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils.isAwaitingStack()) {
                skipIteration.set(true);
            }
            return;
        }
<<<<<<< HEAD
        BlockPos clickTarget = blockPos;
        Direction clickSide = Direction.DOWN;
        if (!Configs.Print.PLACE_IN_AIR.getBooleanValue()) {
            Direction placementSide = this.findPlacementSide(blockPos);
            if (placementSide == null) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "无有效放置面");
                setIterationConsumedEffectiveExecution(false);
                return;
            }
            clickTarget = blockPos.relative(placementSide);
            clickSide = placementSide.getOpposite();
        }
        if (!ActionManager.INSTANCE.queueClick(
                clickTarget,
                clickSide,
                Vec3.ZERO,
                false,
                1,
                fillItemArray,
                ActionManager.ActionSource.FLUID
        )) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "动作队列占用");
            setIterationConsumedEffectiveExecution(false);
            skipIteration.set(true);
            return;
        }
        BlockState previousState = level.getBlockState(blockPos);
        ActionManager.SendResult sendResult = ActionManager.INSTANCE.sendQueue(player);
        if (sendResult.isWaiting()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "等待转头");
            skipIteration.set(true);
            return;
        }
        if (!sendResult.isSent()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "放置动作未发送");
            setIterationConsumedEffectiveExecution(false);
            skipIteration.set(true);
            return;
        }
        HudStatsManager.INSTANCE.trackExpectedBlockChange(HudStatsManager.Mode.FLUID, blockPos, previousState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.FLUID, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "运行中");
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
=======
        ActionManager.INSTANCE.queueClick(
                Configs.Print.PLACE_IN_AIR.getBooleanValue() ? blockPos : blockPos.above(),
                Direction.DOWN,
                Vec3.ZERO,
                false
        );
        HudStatsManager.INSTANCE.trackExpectedBlockChange(HudStatsManager.Mode.FLUID, blockPos, level.getBlockState(blockPos));
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.FLUID, 1);
        if (ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FLUID, "等待转头");
            skipIteration.set(true);
        } else {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FLUID, "运行中");
        }
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    }

    private boolean isTargetFluid(BlockPos blockPos) {
        return this.level != null && this.isTargetFluid(this.level.getBlockState(blockPos).getFluidState());
    }

    private boolean isTargetFluid(FluidState fluidState) {
        return fluids.contains(fluidState.getType())
                && (Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue() || fluidState.isSource());
    }
<<<<<<< HEAD

    private int getScanConfigHash() {
        int result = this.fillBlocks.hashCode();
        result = 31 * result + this.fluidBlocks.hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue());
        return result;
    }
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
}
