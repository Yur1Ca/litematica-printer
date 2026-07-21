package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.TickContext;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.UsageRestrictionCache;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST;
import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST;
import static fi.dy.masa.tweakeroo.tweaks.PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION;

public class MineHandler extends Module {
    public static final String NAME = "mine";
    private static final UsageRestrictionCache MINE_RESTRICTION_CACHE = new UsageRestrictionCache();

    private final MineBreakExecutor analyzer = new MineBreakExecutor();
    private final MineToolSession toolSession = new MineToolSession();
    private final List<MineBreakExecutor.Target> candidates = new ArrayList<>();
    @Nullable
    private BlockPos activeMinePos;

    public MineHandler() {
        super(NAME, PrintModeType.MINE, Configs.Core.MINE, Configs.Mine.MINE_SELECTION_TYPE, true);
    }

    @Override
    public void tick() {
        this.tick(TickContext.capture());
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
        return this.activeMinePos == null ? 0 : 1;
    }

    public static boolean mineRestriction(BlockState blockState) {
        if (!InteractionUtils.breakRestriction(blockState)) {
            return false;
        }
        if (Configs.Mine.EXCAVATE_LIMITER.getOptionListValue().equals(ExcavateListMode.TWEAKEROO)) {
            if (!ModLoadUtils.isTweakerooLoaded()) return true;
            UsageRestriction.ListType listType = BLOCK_TYPE_BREAK_RESTRICTION.getListType();
            return MINE_RESTRICTION_CACHE.allows("tweakeroo", listType,
                    BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST.getStrings(),
                    BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST.getStrings(),
                    blockState);
        }

        Object optionListValue = Configs.Mine.EXCAVATE_LIMIT.getOptionListValue();
        UsageRestriction.ListType listType = optionListValue instanceof UsageRestriction.ListType type
                ? type
                : UsageRestriction.ListType.NONE;
        return MINE_RESTRICTION_CACHE.allows("custom", listType,
                Configs.Mine.EXCAVATE_BLACKLIST.getStrings(),
                Configs.Mine.EXCAVATE_WHITELIST.getStrings(),
                blockState);
    }

    @Override
    protected int getTickInterval() {
<<<<<<< HEAD
        return Configs.Break.BREAK_INTERVAL.getIntegerValue();
=======
        return 0;
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
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
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        if (scanSourceBoxes.isEmpty()) {
            return List.of();
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        
        return ScanCache.INSTANCE.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                SchematicWorldHandler.getSchematicWorld(),
                this.player,
                this.getScanGuardLimit(),
                ScanIntent.MINE,
                this::isMineScanCandidate,
                pos -> this.canReachIterationPosition(pos) && selectionPredicate.test(pos)
        );
    }

    @Override
    protected void preprocess() {
        this.candidates.clear();
        this.analyzer.beginTick();
        this.toolSession.beginTick();
        this.continueActiveMineTarget();
    }

    @Override
    protected void onRuntimeReset() {
        this.candidates.clear();
        this.activeMinePos = null;
        this.analyzer.reset();
        this.toolSession.reset();
    }

    @Override
    protected boolean canIterate() {
        return this.activeMinePos == null && !InteractionUtils.INSTANCE.hasActiveDestroyTarget();
    }

    @Override
<<<<<<< HEAD
    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return true;
    }

    @Override
    protected boolean iterationPositionsPrefilterCooldown() {
        return true;
    }

    @Override
    protected boolean iterationPositionsAreExactCandidates() {
        return true;
    }

    @Override
=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    public boolean canIterationBlockPos(BlockPos pos) {
        return this.isMineScanCandidate(pos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        MineBreakExecutor.Target target = this.analyzer.analyze(blockPos);
        if (target == null) {
            this.setIterationConsumedEffectiveExecution(false);
            return;
        }
        this.candidates.add(target);
        this.setIterationConsumedEffectiveExecution(false);
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        if (ActionManager.INSTANCE.needWaitModifyLook || this.activeMinePos != null || this.candidates.isEmpty()) {
            return;
        }
        this.candidates.sort(this.toolSession.comparator(this.player));
        MineBreakExecutor.Target nearest = this.candidates.get(0);
        MineBreakExecutor.Target selected = this.toolSession.selectTarget(this.candidates, this.analyzer, this.player);
        this.executeToolSession(selected, MineToolSession.distanceScore(this.player, nearest));
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
        BlockBreakResult result = InteractionUtils.INSTANCE.continueDestroyBlockForMine(pos, Direction.DOWN, true);
        MineResultReporter.record(pos, result);
        if (result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.COMPLETED
                || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeAction();
        }
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

    private boolean isMineScanCandidate(BlockPos pos) {
        if (pos == null || this.level == null || this.player == null || this.gameMode == null) {
            return false;
        }

        if (this.isBlockPosOnCooldown(pos)
                || InteractionUtils.INSTANCE.isRecentlyBroken(pos)
                || InteractionUtils.INSTANCE.isPendingDelayedDestroy(pos)) {
            return false;
        }

        BlockState state = this.level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return false;
        }

        if (Configs.Break.BREAK_CHECK_HARDNESS.getBooleanValue() && state.getBlock().defaultDestroyTime() < 0) {
            return false;
        }

        return this.canReachIterationPosition(pos)
                && !this.player.blockActionRestricted(this.level, pos, this.gameMode.getPlayerMode())
                && mineRestriction(state);
    }

    private void executeToolSession(MineBreakExecutor.Target firstTarget, double nearestDistance) {
        this.toolSession.startSession(firstTarget);
        if (!this.toolSession.ensureHandToolProtected(this.player, firstTarget)) {
            return;
        }
        BlockBreakResult result = this.executeSessionTarget(firstTarget, !this.analyzer.isCurrentToolEffective(firstTarget));
        if (this.toolSession.shouldStop(result, this.activeMinePos != null)) {
            return;
        }
        for (MineBreakExecutor.Target target : this.candidates) {
            if (target == firstTarget) {
                continue;
            }
            if (!this.toolSession.hasInstantBudget()) {
                break;
            }
            if (!this.toolSession.matchesSessionTool(this.analyzer, target)) {
                continue;
            }
            if (!this.toolSession.isInsideFrontier(this.player, target, nearestDistance)) {
                break;
            }
            if (!this.toolSession.ensureHandToolProtected(this.player, target)) {
                break;
            }
            result = this.executeSessionTarget(target, false);
            if (this.toolSession.shouldStop(result, this.activeMinePos != null)) {
                break;
            }
        }
    }

    private BlockBreakResult executeSessionTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        BlockBreakResult result = this.executeMineTarget(target, allowToolSwitch);
        if (result != BlockBreakResult.FAILED) {
            this.setBlockPosCooldown(target.pos(), ConfigUtils.getBreakCooldown());
        }
        if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeInstantBudget();
        }
        if (result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.COMPLETED
                || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeAction();
        }
        this.toolSession.onTargetResolved(result, target.pos());
        MineResultReporter.record(target.pos(), result);
        return result;
    }

    private BlockBreakResult executeMineTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        BlockBreakResult result = InteractionUtils.INSTANCE.continueDestroyBlockForMine(target.pos(), Direction.DOWN, allowToolSwitch);
        if (result == BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = target.pos();
        }
        return result;
    }

}
