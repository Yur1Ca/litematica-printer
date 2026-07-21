package me.aleksilassila.litematica.printer.handler.handlers;

import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
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
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class FillHandler extends Module {
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

    public FillHandler() {
        super(NAME, PrintModeType.FILL, Configs.Core.FILL, Configs.Fill.FILL_SELECTION_TYPE, true);
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
                        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "填充列表为空");
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
                        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "运行中");
                    } else {
                        fillModeItemList = new Item[0];
                        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "主手无可填充方块");
                    }
                }
                break;
        }
        if (fillModeItemList.length == 0 && fillMode == FillBlockModeType.BLOCKLIST && !fillCacheBlocklist.isEmpty()) {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "列表无匹配方块");
        }
        int scanConfigHash = this.getFillScanConfigHash();
        if (this.observedFillScanConfigHash != Integer.MIN_VALUE
                && this.observedFillScanConfigHash != scanConfigHash) {
            this.clearFillTargets();
<<<<<<< HEAD
            ScanCache.INSTANCE.resetOwner(NAME);
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
            this.requestFullScan();
        }
        this.observedFillScanConfigHash = scanConfigHash;
    }

    @Override
    protected void onRuntimeReset() {
        this.clearFillTargets();
        this.fillScanConfigHash = 0;
        this.observedFillScanConfigHash = Integer.MIN_VALUE;
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
<<<<<<< HEAD
=======
    protected boolean usesDirtyRegionWakeup() {
        return false;
    }

    @Override
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return true;
    }

    @Override
    protected boolean iterationPositionsPrefilterCooldown() {
        return true;
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
        if (scanSourceBoxes.isEmpty() || fullScanSourceBoxes.isEmpty() || fullScanSourceBox == null) {
            this.clearFillTargets();
            return List.of();
        }

        int configHash = this.getFillScanConfigHash();
        if (this.fillScanBox == null || this.fillScanConfigHash != configHash) {
            this.resetFillScan(fullScanSourceBox, fullScanSourceBoxes, configHash);
        } else if (!this.fillScanBox.equals(fullScanSourceBox)
                || !this.fillScanBoxes.equals(fullScanSourceBoxes)) {
            this.fillScanBox = this.copyScanBox(fullScanSourceBox);
            this.fillScanBoxes = List.copyOf(fullScanSourceBoxes);
        }

        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        return () -> new Iterator<>() {
            private final Iterator<BlockPos> sourceIterator =
                    createSourceIterator(scanSourceBoxes, selectionPredicate);
            private BlockPos next;
            private boolean prepared;
            private boolean nextAvailable;

            private void prepare() {
                if (this.prepared) {
                    return;
                }
                this.prepared = true;
                if (this.sourceIterator.hasNext()) {
                    this.next = this.sourceIterator.next();
                    this.nextAvailable = true;
                }
            }

            @Override
            public boolean hasNext() {
                this.prepare();
                return this.nextAvailable;
            }

            @Override
            public BlockPos next() {
                if (!this.hasNext()) {
                    throw new NoSuchElementException();
                }
                BlockPos result = this.next;
                this.next = null;
                this.prepared = false;
                this.nextAvailable = false;
                return result;
            }
        };
    }

    private Iterator<BlockPos> createSourceIterator(
            List<PrinterBox> scanSourceBoxes,
            Predicate<BlockPos> selectionPredicate
    ) {
        ScanIntent scanIntent = Configs.Print.PLACE_IN_AIR.getBooleanValue()
                ? ScanIntent.CUSTOM
                : ScanIntent.FILL;
        return ScanCache.INSTANCE.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                null,
                this.player,
                this.getScanGuardLimit(),
                scanIntent,
                this::isFillTarget,
                pos -> this.isFillCandidatePreFilter(pos, selectionPredicate)
        ).iterator();
    }

    private boolean isFillCandidatePreFilter(BlockPos blockPos, Predicate<BlockPos> selectionPredicate) {
        return this.canReachIterationPosition(blockPos)
                && selectionPredicate.test(blockPos)
                && !this.isBlockPosOnCooldown(blockPos);
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
        if (Configs.Placement.FALLING_CHECK.getBooleanValue() &&
                player.getMainHandItem().getItem() instanceof BlockItem item &&
                item.getBlock() instanceof FallingBlock block &&
                FallingBlock.isFree(level.getBlockState(blockPos.below()))
        ) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "下落方块无支撑");
            MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(block.getName().getString()));
            return;
        }
        boolean handheld = Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD;
        BlockState currentState = level.getBlockState(blockPos);
        if (!this.isFillTarget(currentState)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        if (!handheld && !InventoryUtils.switchToItems(player, this.fillModeItemList)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "缺少填充材料");
            setIterationConsumedEffectiveExecution(false);
            if (me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.shouldPauseForSwitchRequest()
                    || me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils.isAwaitingStack()) {
                skipIteration.set(true);
            }
            return;
        }
        Direction side = this.getFillPlacementSide(blockPos);
        if (side == null) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "无有效放置面");
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        BlockPos clickTarget = Configs.Print.PLACE_IN_AIR.getBooleanValue() ? blockPos : blockPos.relative(side);
        Direction clickSide = side.getOpposite();
<<<<<<< HEAD
        Item[] expectedItems = handheld
                ? new Item[]{player.getMainHandItem().getItem()}
                : this.fillModeItemList;
        if (!ActionManager.INSTANCE.queueClick(
                clickTarget,
                clickSide,
                Vec3.ZERO,
                false,
                1,
                expectedItems,
                ActionManager.ActionSource.FILL
        )) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "动作队列占用");
            setIterationConsumedEffectiveExecution(false);
            skipIteration.set(true);
            return;
        }
        ActionManager.INSTANCE.setLook(new PlayerLook(clickSide));
        ActionManager.INSTANCE.setWaitForHorizontalLook(false);
        ActionManager.SendResult sendResult = ActionManager.INSTANCE.sendQueue(player);
        if (sendResult.isWaiting()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "等待转头");
            skipIteration.set(true);
            return;
        }
        if (!sendResult.isSent()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "放置动作未发送");
            setIterationConsumedEffectiveExecution(false);
            skipIteration.set(true);
            return;
        }
        HudStatsManager.INSTANCE.trackExpectedBlockChange(HudStatsManager.Mode.FILL, blockPos, currentState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.FILL, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "运行中");
=======
        ActionManager.INSTANCE.queueClick(clickTarget, clickSide, Vec3.ZERO, false);
        ActionManager.INSTANCE.setLook(new PlayerLook(clickSide));
        ActionManager.INSTANCE.setWaitForHorizontalLook(false);
        HudStatsManager.INSTANCE.trackExpectedBlockChange(HudStatsManager.Mode.FILL, blockPos, currentState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.FILL, 1);
        if (ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "等待转头");
            skipIteration.set(true);
        } else {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "运行中");
        }
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
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
