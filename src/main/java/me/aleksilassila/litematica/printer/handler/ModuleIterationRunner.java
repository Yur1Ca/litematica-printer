package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.handler.scan.ScanAvailability;
import me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import net.minecraft.core.BlockPos;

import java.util.concurrent.atomic.AtomicReference;

/** Consumes one feature candidate stream without owning its scan lifecycle. */
final class ModuleIterationRunner {
    private static final int BUDGET_CHECK_INTERVAL = 8;

    private ModuleIterationRunner() {
    }

    static FeatureModuleBase.IterationOutcome run(FeatureModuleBase module, PrinterBox interactionBox) {
        int maxEffectiveExec = module.getMaxEffectiveExecutionsPerTick();
        int scanGuardLimit = module.getScanGuardLimit();
        int totalIterCount = 0;
        int effectiveExecCount = 0;
        int budgetChecks = 0;
        long iterationStartNanos = System.nanoTime();
        long actionExecutionNanos = 0L;
        long budgetNanos = Math.max(1L, Configs.Core.SCAN_TIME_BUDGET_MS.getIntegerValue()) * 1_000_000L;
        boolean interrupt = false;
        boolean didWork = false;
        boolean foundCandidate = false;
        boolean trackGui = module.shouldTrackGuiBlockInfo();
        boolean prefilteredReachAndSelection = module.iterationPositionsPrefilterReachAndSelection();
        boolean prefilteredCooldown = module.iterationPositionsPrefilterCooldown();
        boolean exactCandidates = module.iterationPositionsAreExactCandidates();
        AtomicReference<Boolean> skip = new AtomicReference<>(false);
        module.guiBuffer().resetForTracking(trackGui);

        int completedPassesBefore = module.scanEngine.metricsFor(module.getId()).completedPasses();
        Iterable<BlockPos> positions = module.getIterationPositions(interactionBox);
        boolean bufferedCandidates = positions instanceof ScanCandidateIterable source
                && source.isBuffered()
                && source.availability() == ScanAvailability.READY;
        for (BlockPos pos : positions) {
            if (!bufferedCandidates && ++budgetChecks % BUDGET_CHECK_INTERVAL == 0
                    && System.nanoTime() - iterationStartNanos - actionExecutionNanos >= budgetNanos) {
                interrupt = true;
                break;
            }
            if (scanGuardLimit > 0 && totalIterCount++ >= scanGuardLimit) {
                interrupt = true;
                break;
            }
            if (skip.get() || module.actionBroker.isResourceHeldByOther(ResourceLease.LOOK, module.getId())) {
                interrupt = true;
                break;
            }
            GuiBlockInfo gui = module.createGuiBlockInfo(trackGui, pos);
            if (prefilteredReachAndSelection || module.canReachIterationPosition(pos)) {
                if (gui != null) gui.interacted = true;
            } else {
                if (gui != null) gui.interacted = false;
                module.guiBuffer().add(gui);
                continue;
            }
            if (module.isSchematicBlockHandler() && !module.isSchematicBlock(pos)) {
                module.guiBuffer().add(gui);
                continue;
            }
            if (!prefilteredReachAndSelection && !module.isInSelectionRange(pos)) {
                if (gui != null) gui.posInSelectionRange = false;
                module.guiBuffer().add(gui);
                continue;
            }
            if (gui != null) gui.posInSelectionRange = true;
            if (!prefilteredCooldown && module.isBlockPosOnCooldown(pos)) {
                foundCandidate = true;
                module.guiBuffer().add(gui);
                continue;
            }
            if (exactCandidates || module.canIterationBlockPos(pos)) {
                foundCandidate = true;
                if (module.actionBroker.isResourceHeldByOther(
                        me.aleksilassila.litematica.printer.core.action.ResourceLease.MAIN_HAND,
                        module.getId())) {
                    interrupt = true;
                    break;
                }
                module.beginEffectiveExecution();
                long actionStart = System.nanoTime();
                try {
                    module.executeIteration(pos, skip);
                } finally {
                    if (module.consumedEffectiveExecution()) {
                        actionExecutionNanos += Math.max(0L, System.nanoTime() - actionStart);
                    }
                }
                if (gui != null) gui.execute = true;
                boolean consumed = module.consumedEffectiveExecution();
                if (skip.get() || maxEffectiveExec > 0 && consumed && ++effectiveExecCount >= maxEffectiveExec) {
                    interrupt = true;
                }
                if (consumed) didWork = true;
            }
            module.guiBuffer().add(gui);
            if (interrupt) break;
        }
        boolean scanPaused = positions instanceof ScanCandidateIterable source
                && source.availability() == ScanAvailability.PAUSED;
        if (scanPaused) {
            interrupt = true;
        }
        boolean completedPass = module.scanEngine.metricsFor(module.getId()).completedPasses() > completedPassesBefore
                || positions instanceof ScanCandidateIterable source
                && source.availability() == ScanAvailability.COMPLETE;
        module.stopIteration(interrupt);
        return new FeatureModuleBase.IterationOutcome(interrupt, didWork, foundCandidate, completedPass, scanPaused);
    }
}
