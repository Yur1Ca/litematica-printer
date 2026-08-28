package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.TickContext;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;


public class MineHandler extends FeatureModuleBase {
    public static final String NAME = "mine";
    private final MineBreakExecutor analyzer;
    private final TweakerooAdapter tweakeroo;
    private final MineToolSession toolSession;
    private final MineCandidateQueue candidates = new MineCandidateQueue();
    private final Map<BlockPos, BlockState> candidateStates = new HashMap<>();
    @Nullable
    private BlockPos activeMinePos;

    public MineHandler(PrinterRuntime runtime) {
        super(runtime, NAME, PrintModeType.MINE, Configs.Core.MINE, Configs.Mine.MINE_SELECTION_TYPE, true);
        this.tweakeroo = runtime.tweakeroo();
        this.toolSession = new MineToolSession(this.tweakeroo);
        this.analyzer = new MineBreakExecutor(runtime.client(), this.tweakeroo);
    }

    @Override
    public void tick(TickContext context) {
        if (!ConfigUtils.isEnable() || !ConfigUtils.isMineMode()) {
            this.analyzer.reset();
            this.activeMinePos = null;
            this.toolSession.reset();
        }
        super.tick(context);
    }

    public int getRetryQueueSize() {
        return this.candidates.size() + (this.activeMinePos == null ? 0 : 1);
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
        if (!this.candidates.isEmpty()) {
            return List.of();
        }
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
        this.continueActiveMineTarget();
    }

    @Override
    protected boolean hasRunnableIterationWork() {
        return !this.candidates.isEmpty();
    }

    @Override
    protected boolean hasWaitingIterationWork() {
        return this.activeMinePos != null;
    }

    @Override
    protected void onRuntimeReset() {
        this.candidates.clear();
        this.candidateStates.clear();
        this.activeMinePos = null;
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
        return this.isMineScanCandidate(pos, true);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
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

}
