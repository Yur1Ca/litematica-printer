package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.TickContext;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


public class MineHandler extends FeatureModuleBase {
    public static final String NAME = "mine";
    private final MineBreakExecutor analyzer;
    private final TweakerooAdapter tweakeroo;
    private final MineToolSession toolSession;
    private final MineCandidateQueue candidates = new MineCandidateQueue();
    private final Map<BlockPos, BlockState> candidateStates = new HashMap<>();
    private final Set<BlockPos> trenchFillTargets = new LinkedHashSet<>();
    private final Set<BlockPos> trenchWaterloggedTargets = new LinkedHashSet<>();
    private final Map<BlockPos, Long> trenchFillInFlight = new HashMap<>();
    private int trenchFillSentThisTick;
    private long lastTrenchFillTick = Long.MIN_VALUE;
    @Nullable
    private BlockPos activeMinePos;

    public MineHandler(PrinterRuntime runtime) {
        super(runtime, NAME, PrintModeType.MINE, Configs.Core.MINE, Configs.Mine.MINE_SELECTION_TYPE, true);
        this.tweakeroo = runtime.tweakeroo();
        this.toolSession = new MineToolSession(this.tweakeroo);
        this.analyzer = new MineBreakExecutor(runtime.client(), this.tweakeroo);
        runtime.events().subscribe(event -> {
            if (event instanceof RuntimeEvent.BlockUpdated update) {
                BlockPos pos = new BlockPos(update.x(), update.y(), update.z());
                if (this.trenchFillInFlight.remove(pos) != null
                        && (this.level == null || !this.isFluid(this.level.getBlockState(pos)))) {
                    this.trenchFillTargets.remove(pos);
                }
            }
        });
    }

    @Override
    public void tick(TickContext context) {
        if (!ConfigUtils.isEnable() || !ConfigUtils.isMineMode()) {
            this.analyzer.reset();
            this.activeMinePos = null;
            this.toolSession.reset();
            this.trenchFillTargets.clear();
            this.trenchWaterloggedTargets.clear();
            this.trenchFillInFlight.clear();
            this.lastTrenchFillTick = Long.MIN_VALUE;
        }
        super.tick(context);
    }

    public int getRetryQueueSize() {
        return this.candidates.size() + this.trenchFillTargets.size()
                + this.trenchWaterloggedTargets.size()
                + (this.activeMinePos == null ? 0 : 1);
    }

    private boolean mineRestriction(BlockState blockState) {
        if (!InteractionUtils.breakRestriction(blockState)) {
            return false;
        }
        if (Configs.Mine.EXCAVATE_LIMITER.getOptionListValue().equals(ExcavateListMode.TWEAKEROO)) {
            return this.tweakeroo.allowsBreak(blockState);
        }
        return this.tweakeroo.allowsConfiguredBreak(blockState);
    }

    @Override
    protected int getTickInterval() {
        return Configs.Break.BREAK_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return 0;
    }

    @Override
    protected int getScanGuardLimit() {
        return 0;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        if (this.hasTrenchFillWork()) {
            if (!this.trenchWaterloggedTargets.isEmpty()) {
                return List.of(this.trenchWaterloggedTargets.iterator().next());
            }
            List<BlockPos> fillPositions = new ArrayList<>();
            for (BlockPos pos : this.trenchFillTargets) {
                if (!this.trenchFillInFlight.containsKey(pos)
                        && this.isFluid(this.level.getBlockState(pos))) {
                    fillPositions.add(pos);
                }
            }
            if (!fillPositions.isEmpty()) {
                Iterable<BlockPos> minePositions = this.candidates.isEmpty()
                        ? this.getMineScanPositions(playerInteractionBox)
                        : List.of();
                return () -> Stream.concat(
                        fillPositions.stream(),
                        StreamSupport.stream(minePositions.spliterator(), false)
                ).iterator();
            }
        }
        if (!this.candidates.isEmpty()) {
            return List.of();
        }
        return this.getMineScanPositions(playerInteractionBox);
    }

    private Iterable<BlockPos> getMineScanPositions(PrinterBox playerInteractionBox) {
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        if (scanSourceBoxes.isEmpty()) {
            return List.of();
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        Predicate<BlockPos> reachPredicate = this.createScanReachPredicate();
        
        return this.scanEngine.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                this.litematica.schematicWorld(),
                this.player,
                this.getScanGuardLimit(),
                ScanIntent.MINE,
                pos -> this.isMineScanCandidate(pos, false),
                pos -> reachPredicate.test(pos) && selectionPredicate.test(pos)
        );
    }

    @Override
    protected void preprocess() {
        this.analyzer.beginTick();
        this.toolSession.beginTick();
        this.trenchFillSentThisTick = 0;
        this.refreshTrenchFillTargets();
        this.continueActiveMineTarget();
    }

    @Override
    protected boolean hasRunnableIterationWork() {
        return !this.candidates.isEmpty() || this.hasTrenchFillWork();
    }

    @Override
    protected boolean hasWaitingIterationWork() {
        return this.activeMinePos != null || this.hasTrenchFillWork();
    }

    @Override
    protected void onRuntimeReset() {
        this.candidates.clear();
        this.candidateStates.clear();
        this.activeMinePos = null;
        this.trenchFillTargets.clear();
        this.trenchWaterloggedTargets.clear();
        this.trenchFillInFlight.clear();
        this.lastTrenchFillTick = Long.MIN_VALUE;
        this.analyzer.reset();
        this.toolSession.reset();
    }

    @Override
    protected boolean canIterate() {
        return this.activeMinePos == null && !InteractionUtils.getRuntime().hasActiveDestroyTarget();
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
        return true;
    }

    @Override
    public boolean canIterationBlockPos(BlockPos pos) {
        if (this.hasTrenchFillWork() && this.trenchFillTargets.contains(pos)) {
            return this.level != null
                    && !this.trenchFillInFlight.containsKey(pos)
                    && this.isFluid(this.level.getBlockState(pos));
        }
        if (this.hasTrenchFillWork() && this.trenchWaterloggedTargets.contains(pos)) {
            return this.level != null && this.isWaterlogged(this.level.getBlockState(pos));
        }
        return this.isMineScanCandidate(pos, true);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (this.hasTrenchFillWork() && this.trenchFillTargets.contains(blockPos)) {
            this.executeTrenchFill(blockPos, skipIteration);
            return;
        }
        if (this.hasTrenchFillWork() && this.trenchWaterloggedTargets.contains(blockPos)) {
            this.executeTrenchWaterloggedBreak(blockPos, skipIteration);
            return;
        }
        MineBreakExecutor.Target target = this.analyzer.analyze(blockPos, this.candidateStates.remove(blockPos));
        if (target == null) {
            this.setIterationConsumedEffectiveExecution(false);
            return;
        }
        this.candidates.add(target);
        this.setIterationConsumedEffectiveExecution(false);
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        if (this.actionBroker.isWaitingForLook() || this.activeMinePos != null || this.candidates.isEmpty()) {
            return;
        }
        List<MineBreakExecutor.Target> orderedCandidates = this.refreshCandidateTargets();
        if (orderedCandidates.isEmpty()) {
            return;
        }
        MineBreakExecutor.Target nearest = orderedCandidates.get(0);
        MineBreakExecutor.Target selected = this.toolSession.selectTarget(orderedCandidates, this.analyzer, this.player);
        this.executeToolSession(selected, orderedCandidates);
    }

    private List<MineBreakExecutor.Target> refreshCandidateTargets() {
        List<MineBreakExecutor.Target> refreshed = new ArrayList<>();
        for (MineBreakExecutor.Target queued : this.candidates.snapshot()) {
            BlockPos pos = queued.pos();
            this.candidateStates.remove(pos);
            if (!this.isMineScanCandidate(pos, true)) {
                this.candidates.remove(pos);
                continue;
            }
            MineBreakExecutor.Target current = this.analyzer.analyze(pos);
            if (current == null) {
                this.candidates.remove(pos);
                continue;
            }
            this.candidates.add(current);
            refreshed.add(current);
        }
        refreshed.sort(this.toolSession.comparator(this.player));
        return refreshed;
    }

    private void continueActiveMineTarget() {
        BlockPos pos = this.activeMinePos;
        if (pos == null) {
            return;
        }
        if (!this.canContinueActiveMineTarget(pos)) {
            this.activeMinePos = null;
            return;
        }
        BlockBreakResult result = InteractionUtils.getRuntime().continueDestroyBlockForMine(pos, Direction.DOWN, true);
        MineResultReporter.record(pos, result);
        this.toolSession.onTargetResolved(result, pos);
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = null;
            this.setBlockPosCooldown(pos, ConfigUtils.getBreakCooldown());
        }
    }

    private boolean canContinueActiveMineTarget(BlockPos pos) {
        return pos != null
                && this.canReachIterationPosition(pos)
                && InteractionUtils.canBreakBlock(pos)
                && mineRestriction(this.level.getBlockState(pos));
    }

    private boolean isMineScanCandidate(BlockPos pos, boolean checkReach) {
        if (pos == null || this.level == null || this.player == null || this.gameMode == null) {
            return false;
        }

        if (InteractionUtils.getRuntime().isRecentlyBroken(pos)
                || InteractionUtils.getRuntime().isPendingDelayedDestroy(pos)) {
            return false;
        }

        BlockState state = this.level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return false;
        }

        if (Configs.Break.BREAK_CHECK_HARDNESS.getBooleanValue()
                && state.getDestroySpeed(this.level, pos) < 0.0F) {
            return false;
        }

        if (checkReach && !this.canReachIterationPosition(pos)) {
            return false;
        }
        if (this.player.blockActionRestricted(this.level, pos, this.gameMode.getPlayerMode())
                || !mineRestriction(state)) {
            return false;
        }
        this.candidateStates.put(pos.immutable(), state);
        return true;
    }

    private void executeToolSession(MineBreakExecutor.Target firstTarget,
                                    List<MineBreakExecutor.Target> orderedCandidates) {
        this.toolSession.startSession(firstTarget);
        if (!this.toolSession.ensureHandToolProtected(this.player, firstTarget)) {
            return;
        }
        // Batch dispatch: the per-tick budget lives on the session (BREAK_BLOCKS_PER_TICK,
        // 0 = unlimited). The controller has no per-call fast budget — delta>=0.7 blocks always
        // take the same-tick START+STOP path, throttled only by the session budget and the
        // durability guard.
        BlockBreakResult result = this.executeSessionTarget(firstTarget, !this.analyzer.isCurrentToolEffective(firstTarget));
        if (this.toolSession.shouldStop(result, this.activeMinePos != null)) {
            return;
        }
        for (MineBreakExecutor.Target queuedTarget : orderedCandidates) {
            if (queuedTarget.pos().equals(firstTarget.pos())) {
                continue;
            }
            if (!this.toolSession.hasInstantBudget()) {
                break;
            }
            if (!this.toolSession.matchesSessionTool(this.analyzer, queuedTarget)) {
                continue;
            }
            if (!this.toolSession.ensureHandToolProtected(this.player, queuedTarget)) {
                break;
            }
            result = this.executeSessionTarget(queuedTarget, false);
            if (this.toolSession.shouldStop(result, this.activeMinePos != null)) {
                break;
            }
        }
    }

    private BlockBreakResult executeSessionTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        boolean switchForRecovery = this.player != null
                && target.shouldSwitchToRecoveryTool(this.player.getMainHandItem());
        BlockBreakResult result = this.executeMineTarget(target, allowToolSwitch || switchForRecovery);
        if (result != BlockBreakResult.FAILED) {
            this.setBlockPosCooldown(target.pos(), ConfigUtils.getBreakCooldown());
        }
        if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
            // Batch dispatch: each same-tick START+STOP is an independent server judgment, so the
            // budget ticks down per dispatched block, not per server slot.
            this.toolSession.consumeInstantBudget();
        }
        this.toolSession.onTargetResolved(result, target.pos());
        MineResultReporter.record(target.pos(), result);
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.removeCandidate(target.pos());
        }
        return result;
    }

    private BlockBreakResult executeMineTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        BlockBreakResult result = InteractionUtils.getRuntime().continueDestroyBlockForMine(target.pos(), Direction.DOWN, allowToolSwitch);
        if (result == BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = target.pos();
        }
        return result;
    }

    private void removeCandidate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.candidates.remove(pos);
    }

    private boolean hasTrenchFillWork() {
        return Configs.Mine.MINE_TRENCH_MODE.getBooleanValue()
                && (!this.trenchFillTargets.isEmpty()
                || !this.trenchWaterloggedTargets.isEmpty()
                || !this.trenchFillInFlight.isEmpty());
    }

    private void refreshTrenchFillTargets() {
        if (!Configs.Mine.MINE_TRENCH_MODE.getBooleanValue()
                || this.level == null || this.playerInteractionBox == null) {
            this.trenchFillTargets.clear();
            this.trenchWaterloggedTargets.clear();
            this.trenchFillInFlight.clear();
            return;
        }
        PrinterBox interactionBox = this.playerInteractionBox.get();
        if (interactionBox == null) return;
        Set<BlockPos> discovered = new LinkedHashSet<>();
        Set<BlockPos> waterlogged = new LinkedHashSet<>();
        for (PrinterBox box : this.getScanSourceBoxes(interactionBox)) {
            // A trench is a one-block-wide horizontal line. Keep non-line selections a safe no-op.
            if (box.minX != box.maxX && box.minZ != box.maxZ) continue;
            Direction[] sides = box.minX == box.maxX
                    ? new Direction[]{Direction.EAST, Direction.WEST}
                    : new Direction[]{Direction.NORTH, Direction.SOUTH};
            for (BlockPos pos : box) {
                BlockState state = this.level.getBlockState(pos);
                if (this.isFluid(state)) discovered.add(pos.immutable());
                else if (this.isWaterlogged(state)) waterlogged.add(pos.immutable());
                for (Direction side : sides) {
                    BlockPos outside = pos.relative(side);
                    if (!box.contains(outside)) {
                        BlockState outsideState = this.level.getBlockState(outside);
                        if (this.isFluid(outsideState)) discovered.add(outside.immutable());
                        else if (this.isWaterlogged(outsideState)) waterlogged.add(outside.immutable());
                    }
                }
            }
        }
        this.trenchWaterloggedTargets.removeIf(pos -> !waterlogged.contains(pos));
        this.trenchWaterloggedTargets.addAll(waterlogged);
        this.trenchFillTargets.removeIf(pos -> !discovered.contains(pos)
                && !this.trenchFillInFlight.containsKey(pos));
        this.trenchFillTargets.addAll(discovered);
        long now = this.level.getGameTime();
        this.trenchFillInFlight.entrySet().removeIf(entry ->
                !this.isFluid(this.level.getBlockState(entry.getKey()))
                        || now - entry.getValue() > 40L);
    }

    private void executeTrenchFill(BlockPos pos, AtomicReference<Boolean> skipIteration) {
        if (this.level == null || this.player == null || !this.isFluid(this.level.getBlockState(pos))) {
            this.trenchFillTargets.remove(pos);
            return;
        }
        int placementLimit = Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
        if (placementLimit > 0 && this.trenchFillSentThisTick >= placementLimit) {
            skipIteration.set(true);
            return;
        }
        long now = this.level.getGameTime();
        int placementInterval = this.placementRateController.effectiveIntervalTicks();
        if (placementInterval > 0 && this.lastTrenchFillTick != Long.MIN_VALUE
                && now - this.lastTrenchFillTick < placementInterval) {
            skipIteration.set(true);
            return;
        }
        if (!this.placementRateController.canSend(now)) {
            skipIteration.set(true);
            return;
        }
        if (!InventoryUtils.switchToItems(this.player, new Item[]{Items.SAND})) {
            skipIteration.set(true);
            return;
        }
        Direction supportDirection = this.findTrenchSupport(pos);
        if (supportDirection == null || !this.actionBroker.queueClick(
                pos.relative(supportDirection),
                supportDirection.getOpposite(),
                net.minecraft.world.phys.Vec3.ZERO,
                false,
                1,
                new Item[]{Items.SAND},
                ActionPort.ActionSource.FILL)) {
            skipIteration.set(true);
            return;
        }
        this.actionBroker.setLook(new PlayerLook(supportDirection.getOpposite()));
        this.actionBroker.setWaitForHorizontalLook(false);
        ActionPort.SendResult result = this.actionBroker.sendQueue(this.player);
        if (result.isSent() || result.isWaiting()) {
            this.trenchFillInFlight.put(pos.immutable(), this.level.getGameTime());
            if (result.isSent()) {
                this.trenchFillSentThisTick++;
                this.lastTrenchFillTick = now;
                this.placementRateController.recordSent(now);
            } else {
                skipIteration.set(true);
            }
        } else {
            skipIteration.set(true);
        }
    }

    private void executeTrenchWaterloggedBreak(BlockPos pos, AtomicReference<Boolean> skipIteration) {
        if (this.level == null || !this.isWaterlogged(this.level.getBlockState(pos))) {
            this.trenchWaterloggedTargets.remove(pos);
            return;
        }
        if (this.activeMinePos != null && !this.activeMinePos.equals(pos)) {
            skipIteration.set(true);
            return;
        }
        BlockBreakResult result = InteractionUtils.getRuntime()
                .continueDestroyBlockForMine(pos, Direction.DOWN, true);
        MineResultReporter.record(pos, result);
        if (result == BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = pos.immutable();
        } else {
            this.trenchWaterloggedTargets.remove(pos);
        }
        skipIteration.set(true);
    }

    private Direction findTrenchSupport(BlockPos pos) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction direction : order) {
            BlockPos support = pos.relative(direction);
            BlockState state = this.level.getBlockState(support);
            if (!BlockUtils.isReplaceable(state) && PrinterUtils.canBeClicked(this.level, support)) {
                return direction;
            }
        }
        return null;
    }

    private boolean isFluid(BlockState state) {
        return state != null && state.getBlock() instanceof LiquidBlock;
    }

    private boolean isWaterlogged(BlockState state) {
        return state != null
                && !(state.getBlock() instanceof LiquidBlock)
                && !state.getFluidState().isEmpty();
    }

}
