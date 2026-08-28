package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockCandidatePlanner;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockEnvironment;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockInventory;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTargetBlocks;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class BedrockHandler extends FeatureModuleBase {
    private final BedrockCandidatePlanner candidatePlanner;

    public BedrockHandler(PrinterRuntime runtime) {
        super(runtime, "bedrock", PrintModeType.BEDROCK, Configs.Hotkeys.BEDROCK, null, true);
        this.candidatePlanner = new BedrockCandidatePlanner(this.scanEngine, this.litematica);
    }

    @Override
    protected int getTickInterval() {
        return 0;
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Math.max(1, Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue());
    }

    @Override
    protected boolean canExecute() {
        if (player.isCreative()) {
            BedrockController.clearHorizontalLookState();
            MessageUtils.setOverlayMessage(I18n.BEDROCK_CREATIVE_MODE.getName());
            return false;
        }
        String warning = BedrockInventory.warningMessage();
        if (warning != null) {
            MessageUtils.setOverlayMessage(me.aleksilassila.litematica.printer.utils.minecraft.StringUtils.translatable(warning));
            if (!BedrockController.hasActiveWork()) {
                BedrockController.clearHorizontalLookState();
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean canIterate() {
        BedrockController.tick();
        // A full active-target lane may temporarily reject new submissions, but it must not
        // prevent already discovered candidates from reaching the controller. Previously this
        // gate made the last cached bedrock positions wait until player movement rebuilt the
        // interaction window and invoked the scanner again.
        if (!BedrockController.canSubmitInCurrentWindow()) {
            return false;
        }
        return this.candidatePlanner.hasPendingCandidates()
                || BedrockController.canScanForTargets();
    }

    @Override
    protected boolean hasPendingIterationWork() {
        return BedrockController.hasPendingScanWork()
                || this.candidatePlanner.hasPendingCandidates()
                || this.candidatePlanner.hasPendingScanSource();
    }

    @Override
    public int getPendingIterationWorkCount() {
        return BedrockController.getPendingScanWorkCount() + this.candidatePlanner.getPendingCandidateCount();
    }

    @Override
    protected void onRuntimeReset() {
        this.candidatePlanner.reset();
        BedrockController.reset();
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        BedrockController.clearSubmissionPlans();
        if (playerInteractionBox == null || this.level == null || this.player == null) {
            return List.of();
        }

        return this.candidatePlanner.iterable(
                playerInteractionBox,
                this.level,
                this.player,
                this.getMaxEffectiveExecutionsPerTick(),
                this.getScanGuardLimit()
        );
    }

    @Override
    public boolean canIterationBlockPos(BlockPos pos) {
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos))) {
            return false;
        }
        return BedrockController.canAccept(pos);
    }

    @Override
    protected boolean canReachIterationPosition(BlockPos pos) {
        return BedrockEnvironment.canInteract(pos);
    }

    @Override
    protected boolean requiresSelection1ModeRangeCheck() {
        return false;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(blockPos))) {
            this.candidatePlanner.discard(blockPos);
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        boolean submitted = BedrockController.submit(blockPos);
        this.candidatePlanner.recordSubmissionResult(blockPos, submitted);
        setIterationConsumedEffectiveExecution(submitted);
        if (submitted) {
            // Allow a second same-tick submit when the controller still has safe capacity.
            skipIteration.set(!BedrockController.canScanForTargets());
        }
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        if (!interrupt) {
            BedrockController.tick();
        }
    }

}
