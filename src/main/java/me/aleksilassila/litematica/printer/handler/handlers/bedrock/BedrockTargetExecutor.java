package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Advances active target state machines without owning admission or scanning policy. */
final class BedrockTargetExecutor {
    private static final int FAILURE_RETRY_COOLDOWN_TICKS = 12;
    private static final int OUT_OF_RANGE_RETRY_COOLDOWN_TICKS = 6;

    private final BedrockTargetRegistry targets;
    private final BedrockCleanupCoordinator cleanup;
    private final BedrockRunStats stats;
    private final BedrockPlacer placer;
    private final BedrockNetworkSync networkSync;
    private final BiConsumer<BlockPos, Integer> retryCooldown;
    private final BooleanSupplier observationOnly;

    BedrockTargetExecutor(
            BedrockTargetRegistry targets,
            BedrockCleanupCoordinator cleanup,
            BedrockRunStats stats,
            BiConsumer<BlockPos, Integer> retryCooldown,
            BedrockPlacer placer,
            BedrockNetworkSync networkSync,
            BooleanSupplier observationOnly
    ) {
        this.targets = targets;
        this.cleanup = cleanup;
        this.stats = stats;
        this.retryCooldown = retryCooldown;
        this.placer = placer;
        this.networkSync = networkSync;
        this.observationOnly = observationOnly;
    }

    int process(
            ClientLevel level,
            int executeBudget,
            boolean priorityOnly,
            Set<BedrockTarget> processedTargets,
            BedrockTarget sideLookTarget,
            boolean allowPreparation
    ) {
        return this.process(level, executeBudget, priorityOnly, processedTargets, sideLookTarget, allowPreparation, false);
    }

    int processPreparation(ClientLevel level, int executeBudget, boolean priorityOnly,
                           Set<BedrockTarget> processedTargets, boolean allowPreparation) {
        return this.process(level, executeBudget, priorityOnly, processedTargets, null, allowPreparation, true);
    }

    private int process(ClientLevel level, int executeBudget, boolean priorityOnly,
                        Set<BedrockTarget> processedTargets, BedrockTarget sideLookTarget,
                        boolean allowPreparation, boolean preparationOnly) {
        Iterator<BedrockTarget> iterator = this.targets.iterator();
        while (iterator.hasNext()) {
            BedrockTarget target = iterator.next();
            if (target == null) {
                iterator.remove();
                continue;
            }
            if (sideLookTarget != null && target != sideLookTarget) {
                continue;
            }
            if (processedTargets.contains(target)) {
                continue;
            }
            // A raw reverse USE owns the rest of this tick. Even retirement can
            // otherwise reach an ordinary cleanup tool swap through its callback.
            if (this.observationOnly.getAsBoolean()) {
                target.refreshStatusOnly();
                processedTargets.add(target);
                continue;
            }
            if (!allowPreparation && !target.hasTried()
                    && target.getStatus() != BedrockTarget.Status.RECOVERING) continue;
            BlockPos outOfRangePos = BedrockEnvironment.findFirstOutOfRangePosition(target.getStaticMachinePositions());
            if (outOfRangePos != null) {
                target.refreshStatusOnly();
                this.cleanupTarget(iterator, target, "out_of_range", level);
                processedTargets.add(target);
                continue;
            }

            boolean fastLane = BedrockSchedulingPolicy.isFastLane(target.getStatus());
            if (priorityOnly != fastLane) {
                continue;
            }
            boolean activeStatus = BedrockSchedulingPolicy.countsTowardsActiveCap(target.getStatus());
            boolean hadBudget = activeStatus && executeBudget > 0;
            BedrockTarget.Status status;
            if (!allowPreparation && (hadBudget || !activeStatus)) {
                status = target.settleDispatchedAttempt(hadBudget);
            } else if (hadBudget) {
                status = preparationOnly ? target.tickPreparation() : target.tick(true, true);
            } else if (activeStatus) {
                status = target.refreshStatusOnly();
            } else {
                status = target.tick(false, false);
            }
            if (hadBudget && target.consumedThroughputThisTick()) {
                executeBudget--;
            }
            boolean retireOnSuccessfulRetracting = status == BedrockTarget.Status.RETRACTING
                    && !BedrockTargetBlocks.isTargetBlock(level.getBlockState(target.getBedrockPos()));
            if (status == BedrockTarget.Status.RETRACTED
                    || status == BedrockTarget.Status.FAILED
                    || status == BedrockTarget.Status.STUCK
                    || retireOnSuccessfulRetracting) {
                this.cleanupTarget(iterator, target, null, level);
            } else if (target.isHorizontalLayout()
                    && target.hasPendingHorizontalLook()) {
                processedTargets.add(target);
                break;
            } else {
                boolean deferredForBudget = activeStatus
                        && !hadBudget
                        && BedrockSchedulingPolicy.countsTowardsActiveCap(status)
                        && BedrockSchedulingPolicy.isFastLane(status) == priorityOnly;
                if (!deferredForBudget) {
                    processedTargets.add(target);
                }
            }
        }
        return executeBudget;
    }

    /** Completion bookkeeping and queue ownership do not mutate inventory or world state. */
    void observeAndRetire(ClientLevel level) {
        Iterator<BedrockTarget> iterator = this.targets.iterator();
        while (iterator.hasNext()) {
            BedrockTarget target = iterator.next();
            if (target == null) { iterator.remove(); continue; }
            BedrockTarget.Status status = target.getStatus();
            if (status != BedrockTarget.Status.FAILED && status != BedrockTarget.Status.STUCK) {
                status = target.refreshStatusOnly();
            }
            if (status == BedrockTarget.Status.RETRACTED || status == BedrockTarget.Status.FAILED
                    || status == BedrockTarget.Status.STUCK) this.cleanupTarget(iterator, target, null, level);
        }
    }

    private void cleanupTarget(
            Iterator<BedrockTarget> iterator,
            BedrockTarget target,
            String reason,
            ClientLevel level
    ) {
        BedrockDiagnostics.trace(target, reason == null ? "retired" : "retired:" + reason);
        if (target.getStatus() == BedrockTarget.Status.FAILED
                || target.getStatus() == BedrockTarget.Status.STUCK) {
            int delay = this.networkSync.retryDelayTicks(FAILURE_RETRY_COOLDOWN_TICKS);
            this.retryCooldown.accept(target.getBedrockPos(), delay);
        } else if ("out_of_range".equals(reason)) {
            int delay = this.networkSync.retryDelayTicks(OUT_OF_RANGE_RETRY_COOLDOWN_TICKS);
            this.retryCooldown.accept(target.getBedrockPos(), delay);
        }
        if (reason == null && !BedrockTargetBlocks.isTargetBlock(level.getBlockState(target.getBedrockPos()))) {
            this.networkSync.confirmed(target.getBedrockPos());
            this.stats.confirmedSuccesses++;
            this.stats.lastReason = "running";
            HudStatsManager.getRuntime().recordRateUnit(HudStatsManager.Mode.BEDROCK, 1);
        }
        if (target.getStatus() == BedrockTarget.Status.FAILED) {
            this.networkSync.discarded(target.getBedrockPos(), false);
            this.stats.failedTargets++;
            this.stats.lastReason = "failed";
        } else if (target.getStatus() == BedrockTarget.Status.STUCK) {
            this.networkSync.discarded(target.getBedrockPos(), true);
            this.stats.stuckTargets++;
            this.stats.lastReason = "stuck";
        } else if ("out_of_range".equals(reason)) {
            this.networkSync.discarded(target.getBedrockPos(), false);
        }
        this.placer.cancelPistonPlacement(target.getPistonPos());
        iterator.remove();
        this.cleanup.enqueueCycle(target.getBedrockPos(), target.getCleanupPositions(), target.isHorizontalLayout());
    }
}
