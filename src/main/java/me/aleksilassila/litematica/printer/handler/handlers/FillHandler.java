package me.aleksilassila.litematica.printer.handler.handlers;

import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class FillHandler extends FeatureModuleBase {
    public final static String NAME = "fill";
    private static final Direction[] FILL_SIDE_ORDER = {
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP
    };

    private List<String> fillCacheBlocklist = new ArrayList<>();
    private List<String> replaceableListCache = List.of();
    private String[] replaceableFilters = new String[0];
    @Getter
    private Item[] fillModeItemList = new Item[0];
    private PrinterBox fillScanBox;
    private List<PrinterBox> fillScanBoxes = List.of();
    private int fillScanConfigHash;
    private int observedFillScanConfigHash = Integer.MIN_VALUE;
    private final Set<BlockPos> retryTargets = new LinkedHashSet<>();
    private final Set<BlockPos> inFlightTargets = new LinkedHashSet<>();
    private final Map<BlockPos, Long> inFlightSince = new HashMap<>();

    public FillHandler(PrinterRuntime runtime) {
        super(runtime, NAME, PrintModeType.FILL, Configs.Core.FILL, Configs.Fill.FILL_SELECTION_TYPE, true);
        runtime.events().subscribe(event -> {
            if (event instanceof RuntimeEvent.BlockUpdated update) {
                BlockPos pos = new BlockPos(update.x(), update.y(), update.z());
                if (this.inFlightTargets.remove(pos)) {
                    this.inFlightSince.remove(pos);
                    if (this.isFillTarget(pos)) {
                        this.retryTargets.add(pos);
                    }
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
        this.updateReplaceableFilterCache();
        FillBlockModeType fillMode = (FillBlockModeType) Configs.Fill.FILL_BLOCK_MODE.getOptionListValue();
        switch (fillMode) {
            case BLOCKLIST:
                // 每次去MC注册表中获取会造成大量卡顿, 所以仅在玩家修改了填充列表, 再去读取以便注册表
                List<String> strings = Configs.Fill.FILL_BLOCK_LIST.getStrings();
                if (!strings.equals(fillCacheBlocklist)) {
                    fillCacheBlocklist = new ArrayList<>(strings);
                    fillModeItemList = new Item[0];
                    if (strings.isEmpty()) {
                        this.hudStats.recordStatus(HudStatsManager.Mode.FILL, "填充列表为空");
                        return;
                    }
                    List<Item> items = RegistryFilterResolver.resolveItems(fillCacheBlocklist);
                    fillModeItemList = items.toArray(new Item[0]);
                }
                break;
            case HANDHELD:  // 手持物品
                if (Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD) {
                    ItemStack heldStack = player.getMainHandItem(); // 获取主手物品
                    if (!heldStack.isEmpty() && heldStack.getCount() > 0) {
                        fillModeItemList = new Item[]{player.getMainHandItem().getItem()};
                        this.hudStats.recordStatus(HudStatsManager.Mode.FILL, "运行中");
                    } else {
                        fillModeItemList = new Item[0];
                        this.hudStats.recordStatus(HudStatsManager.Mode.FILL, "主手无可填充方块");
                    }
                }
                break;
        }
        if (fillModeItemList.length == 0 && fillMode == FillBlockModeType.BLOCKLIST && !fillCacheBlocklist.isEmpty()) {
            this.hudStats.recordStatus(HudStatsManager.Mode.FILL, "列表无匹配方块");
        }
        int scanConfigHash = this.getFillScanConfigHash();
        if (this.observedFillScanConfigHash != Integer.MIN_VALUE
                && this.observedFillScanConfigHash != scanConfigHash) {
            this.clearFillTargets();
            this.scanEngine.resetOwner(NAME);
            this.requestFullScan();
        }
        this.observedFillScanConfigHash = scanConfigHash;
    }

    @Override
    protected void onRuntimeReset() {
        this.clearFillTargets();
        this.retryTargets.clear();
        this.inFlightTargets.clear();
        this.inFlightSince.clear();
        this.fillScanConfigHash = 0;
        this.observedFillScanConfigHash = Integer.MIN_VALUE;
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
        if (this.level == null) {
            return;
        }
        this.retryTargets.removeIf(pos -> !this.isFillTarget(pos));
        long now = this.level.getGameTime();
        List<BlockPos> stale = new ArrayList<>();
        for (Map.Entry<BlockPos, Long> entry : this.inFlightSince.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!this.inFlightTargets.contains(pos) || !this.isFillTarget(pos)) {
                stale.add(pos);
            } else if (now > entry.getValue()) {
                this.inFlightTargets.remove(pos);
                this.retryTargets.add(pos);
                stale.add(pos);
            }
        }
        for (BlockPos pos : stale) {
            this.inFlightSince.remove(pos);
        }
    }

    private void updateReplaceableFilterCache() {
        List<String> replaceableList = Configs.Print.REPLACEABLE_LIST.getStrings();
        if (replaceableList.equals(this.replaceableListCache)) {
            return;
        }
        this.replaceableListCache = new ArrayList<>(replaceableList);
        this.replaceableFilters = this.replaceableListCache.toArray(new String[0]);
    }

    @Override
    protected boolean canIterate() {
        return fillModeItemList.length > 0;
    }

    @Override
    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return true;
    }

    @Override
    protected boolean iterationPositionsPrefilterCooldown() {
        return false;
    }

    @Override
    protected boolean iterationPositionsAreExactCandidates() {
        return Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() != FillBlockModeType.HANDHELD;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        PrinterBox fullInteractionBox = this.playerInteractionBox == null ? null : this.playerInteractionBox.get();
        List<PrinterBox> fullScanSourceBoxes = this.getScanSourceBoxes(fullInteractionBox);
        PrinterBox fullScanSourceBox = this.getScanSourceBox(fullInteractionBox);
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        Predicate<BlockPos> reachPredicate = this.createScanReachPredicate();
        List<BlockPos> retainedTargets = this.retryTargets.stream()
                .filter(pos -> !this.inFlightTargets.contains(pos)
                        && reachPredicate.test(pos)
                        && selectionPredicate.test(pos)
                        && this.isFillTarget(pos))
                .toList();
        if (scanSourceBoxes.isEmpty() || fullScanSourceBoxes.isEmpty() || fullScanSourceBox == null) {
            this.clearFillTargets();
            return retainedTargets;
        }

        int configHash = this.getFillScanConfigHash();
        if (this.fillScanBox == null || this.fillScanConfigHash != configHash) {
            this.resetFillScan(fullScanSourceBox, fullScanSourceBoxes, configHash);
        } else if (!this.fillScanBox.equals(fullScanSourceBox)
                || !this.fillScanBoxes.equals(fullScanSourceBoxes)) {
            this.fillScanBox = this.copyScanBox(fullScanSourceBox);
            this.fillScanBoxes = List.copyOf(fullScanSourceBoxes);
        }

        // ScanEngine already exposes a resumable candidate iterable and its explicit pause
        // state. A second look-ahead iterator would hide budget pauses from the coordinator.
        Iterable<BlockPos> source = this.createSourceIterator(scanSourceBoxes, reachPredicate, selectionPredicate);
        return retainedTargets.isEmpty()
                ? source
                : com.google.common.collect.Iterables.concat(retainedTargets, source);
    }

    private Iterable<BlockPos> createSourceIterator(
            List<PrinterBox> scanSourceBoxes,
            Predicate<BlockPos> reachPredicate,
            Predicate<BlockPos> selectionPredicate
    ) {
        ScanIntent scanIntent = Configs.Print.PLACE_IN_AIR.getBooleanValue()
                ? ScanIntent.CUSTOM
                : ScanIntent.FILL;
        return this.scanEngine.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                null,
                this.player,
                this.getScanGuardLimit(),
                scanIntent,
                pos -> this.isFillTarget(pos)
                        && !this.retryTargets.contains(pos)
                        && !this.inFlightTargets.contains(pos),
                pos -> this.isFillCandidatePreFilter(pos, reachPredicate, selectionPredicate),
                ScanEngine.PassPolicy.INVALIDATIONS_ONLY
        );
    }

    private boolean isFillCandidatePreFilter(
            BlockPos blockPos,
            Predicate<BlockPos> reachPredicate,
            Predicate<BlockPos> selectionPredicate
    ) {
        return reachPredicate.test(blockPos)
                && selectionPredicate.test(blockPos);
    }

    private void resetFillScan(
            PrinterBox playerInteractionBox,
            List<PrinterBox> sourceBoxes,
            int configHash
    ) {
        this.fillScanBox = this.copyScanBox(playerInteractionBox);
        this.fillScanBoxes = List.copyOf(sourceBoxes);
        this.fillScanConfigHash = configHash;
    }

    private void clearFillTargets() {
        this.fillScanBox = null;
        this.fillScanBoxes = List.of();
    }

    private PrinterBox copyScanBox(PrinterBox box) {
        return new PrinterBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private int getFillScanConfigHash() {
        int result = Arrays.hashCode(this.fillModeItemList);
        result = 31 * result + Arrays.hashCode(this.replaceableFilters);
        result = 31 * result + Configs.Fill.FILL_BLOCK_MODE.getOptionListValue().hashCode();
        result = 31 * result + Configs.Fill.FILL_SELECTION_TYPE.getOptionListValue().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Print.PLACE_IN_AIR.getBooleanValue());
        result = 31 * result + Configs.Fill.FILL_BLOCK_FACING.getOptionListValue().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue());
        return result;
    }

    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        if (Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD) {
            ItemStack heldStack = player.getMainHandItem(); // 获取主手物品
            return !heldStack.isEmpty() && heldStack.getCount() > 0 && this.isFillTarget(blockPos);
        }
        return this.isFillTarget(blockPos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (this.inFlightTargets.contains(blockPos)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        if (Configs.Placement.FALLING_CHECK.getBooleanValue() &&
                player.getMainHandItem().getItem() instanceof BlockItem item &&
                item.getBlock() instanceof FallingBlock block &&
                FallingBlock.isFree(level.getBlockState(blockPos.below()))
        ) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.FILL, "下落方块无支撑");
            MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(block.getName().getString()));
            this.retryTargets.add(blockPos.immutable());
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        boolean handheld = Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD;
        BlockState currentState = level.getBlockState(blockPos);
        if (!this.isFillTarget(currentState)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        if (!handheld && !InventoryUtils.switchToItems(player, this.fillModeItemList)) {
            boolean retrievalPending =
                    this.actionBroker.isResourceHeld(ResourceLease.INVENTORY);
            if (retrievalPending) {
                this.hudStats.recordDeferred(HudStatsManager.Mode.FILL, "等待取货");
                this.missingMaterials.resolve(this.fillModeItemList, null);
            } else {
                this.hudStats.recordDeferred(HudStatsManager.Mode.FILL, "缺少填充材料");
                this.missingMaterials.recordMissing(
                        this.fillModeItemList,
                        null,
                        null,
                        level.getGameTime()
                );
            }
            setIterationConsumedEffectiveExecution(false);
            this.retryTargets.add(blockPos.immutable());
            if (retrievalPending) {
                skipIteration.set(true);
            }
            return;
        }
        if (!handheld) {
            this.missingMaterials.resolve(this.fillModeItemList, null);
        }
        Direction side = this.getFillPlacementSide(blockPos);
        if (side == null) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.FILL, "无有效放置面");
            setIterationConsumedEffectiveExecution(false);
            this.retryTargets.add(blockPos.immutable());
            return;
        }
        BlockPos clickTarget = Configs.Print.PLACE_IN_AIR.getBooleanValue() ? blockPos : blockPos.relative(side);
        Direction clickSide = side.getOpposite();
        Item[] expectedItems = handheld
                ? new Item[]{player.getMainHandItem().getItem()}
                : this.fillModeItemList;
        if (!this.actionBroker.queueClick(
                clickTarget,
                clickSide,
                Vec3.ZERO,
                false,
                1,
                expectedItems,
                    ActionPort.ActionSource.FILL
        )) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.FILL, "动作队列占用");
            setIterationConsumedEffectiveExecution(false);
            this.retryTargets.add(blockPos.immutable());
            skipIteration.set(true);
            return;
        }
        this.actionBroker.setLook(new PlayerLook(clickSide));
        this.actionBroker.setWaitForHorizontalLook(false);
        ActionPort.SendResult sendResult = this.actionBroker.sendQueue(player);
        if (sendResult.isWaiting()) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.FILL, "等待转头");
            this.retryTargets.add(blockPos.immutable());
            skipIteration.set(true);
            return;
        }
        if (!sendResult.isSent()) {
            this.hudStats.recordDeferred(HudStatsManager.Mode.FILL, "放置动作未发送");
            setIterationConsumedEffectiveExecution(false);
            this.retryTargets.add(blockPos.immutable());
            return;
        }
        BlockPos retainedPos = blockPos.immutable();
        this.retryTargets.remove(blockPos);
        this.inFlightTargets.add(retainedPos);
        this.inFlightSince.put(retainedPos, this.level.getGameTime());
        this.hudStats.trackExpectedBlockChange(HudStatsManager.Mode.FILL, blockPos, currentState);
        this.hudStats.recordRateUnit(HudStatsManager.Mode.FILL, 1);
        this.hudStats.recordStatus(HudStatsManager.Mode.FILL, "运行中");
        this.setBlockPosCooldown(blockPos, ConfigUtils.getPlaceCooldown());
    }

    private Direction getFillPlacementSide(BlockPos blockPos) {
        if (this.level == null || this.player == null || blockPos == null) {
            return null;
        }
        Direction configuredFacing = ConfigUtils.getFillModeFacing();
        if (Configs.Print.PLACE_IN_AIR.getBooleanValue()) {
            return configuredFacing != null ? configuredFacing : getPlayerPlacementDirection();
        }
        if (configuredFacing != null) {
            return this.isValidFillPlacementSide(blockPos, configuredFacing) ? configuredFacing : null;
        }
        for (Direction side : FILL_SIDE_ORDER) {
            if (this.isValidFillPlacementSide(blockPos, side)) {
                return side;
            }
        }
        return null;
    }

    private boolean isValidFillPlacementSide(BlockPos blockPos, Direction side) {
        BlockPos neighborPos = blockPos.relative(side);
        BlockState neighborState = this.level.getBlockState(neighborPos);
        return PrinterUtils.canBeClicked(this.level, neighborPos) && !BlockUtils.isReplaceable(neighborState);
    }

    private boolean isFillTarget(BlockPos blockPos) {
        return this.level != null && this.isFillTarget(this.level.getBlockState(blockPos));
    }

    private boolean isFillTarget(BlockState currentState) {
        if (currentState.isAir() || currentState.getBlock() instanceof LiquidBlock) {
            return true;
        }
        for (String filter : this.replaceableFilters) {
            if (FilterUtils.matchName(filter, currentState)) {
                return true;
            }
        }
        return false;
    }

}
