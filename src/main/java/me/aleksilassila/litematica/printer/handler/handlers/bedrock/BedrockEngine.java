package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Runtime-owned orchestration for bedrock admission, execution and cleanup. */
public final class BedrockEngine implements RuntimeComponent {
    private final Minecraft client;
    private final BedrockTargetRegistry targets = new BedrockTargetRegistry();
    private final BedrockCleanupCoordinator cleanup;
    private final BedrockRunStats stats = new BedrockRunStats();
    private final BedrockNetworkSync networkSync;
    private final BedrockPlacer placer;
    private final BedrockCriticalExecutor criticalExecutor;
    private final BedrockAdmissionController admission;
    private final BedrockTargetExecutor targetExecutor;
    private final BedrockThroughputScheduler throughputScheduler = new BedrockThroughputScheduler();
    private final LongSupplier tickClock;
    private long lastProcessedTick = Long.MIN_VALUE;
    private int submissionBudget;
    private boolean awaitingSupplies;

    public BedrockEngine(
            Minecraft client,
            CooldownUtils cooldownUtils,
            LongSupplier tickClock,
            LitematicaAdapter litematica
    ) {
        this(client, cooldownUtils, tickClock, litematica, RuntimeTiming.disabled());
    }

    public BedrockEngine(
            Minecraft client,
            CooldownUtils cooldownUtils,
            LongSupplier tickClock,
            LitematicaAdapter litematica,
            RuntimeTiming timing
    ) {
        this.client = client;
        this.tickClock = tickClock;
        this.networkSync = new BedrockNetworkSync(
                tickClock,
                () -> me.aleksilassila.litematica.printer.runtime.RuntimeAccess.get()
                        .rttReplayController().getExtraIntervalTicks(100),
                timing
        );
        this.placer = new BedrockPlacer(client, this.networkSync);
        this.criticalExecutor = new BedrockCriticalExecutor(this.placer, this.stats);
        this.cleanup = new BedrockCleanupCoordinator(client, cooldownUtils, this.networkSync);
        this.admission = new BedrockAdmissionController(
                client, this.targets, this.cleanup, this.stats, cooldownUtils, tickClock,
                this.criticalExecutor, this.placer, litematica, this.networkSync);
        this.targetExecutor = new BedrockTargetExecutor(
                this.targets,
                this.cleanup,
                this.stats,
                this.admission::setRetryCooldown,
                this.placer,
                this.networkSync
        );
    }

    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        this.reset();
    }

    public void reset() {
        this.targets.clear();
        this.cleanup.reset();
        this.admission.reset();
        this.stats.reset();
        this.networkSync.reset();
        this.placer.clearHorizontalLookState();
        this.criticalExecutor.reset();
        this.throughputScheduler.reset();
        this.submissionBudget = 0;
        this.awaitingSupplies = false;
        this.lastProcessedTick = Long.MIN_VALUE;
        HudStatsManager.getRuntime().resetMode(HudStatsManager.Mode.BEDROCK);
    }

    public void clearHorizontalLookState() {
        this.placer.clearHorizontalLookState();
    }

    public void onRuntimeEvent(RuntimeEvent event) {
        if (event instanceof RuntimeEvent.BlockUpdated update) {
            BlockPos pos = new BlockPos(update.x(), update.y(), update.z());
            this.networkSync.onServerBlockUpdate(pos);
            this.cleanup.onServerBlockUpdate(pos);
        }
    }

    /** Called only at the feature's inventory-safe boundary, never inside a critical bundle. */
    public boolean prepareMaterials(MaterialRequestCoordinator requests) {
        if (this.client.level == null || this.client.player == null) return false;
        BedrockSupplies.Requirement missing = BedrockSupplies.missing(this.client.level, this.targets);
        this.awaitingSupplies = missing != null || requests.isBusy();
        // Background provider ticks own completion, including a cancelled operation's recovery.
        if (requests.isBusy()) return false;
        if (missing == null) return true;
        // Waiting for the whole registry to empty would deadlock an unexecuted machine on
        // the very materials it needs to finish. Only dispatched attempts must settle first.
        if (this.placer.hasPendingPistonRetries() || BedrockSupplies.hasDispatchedAttempt(this.targets)) {
            return true;
        }
        this.placer.clearHorizontalLookState();
        MaterialReservation reservation = requests.request(
                missing.item(), missing.minimumCount(), MaterialRequest.Source.BEDROCK);
        // No provider owns a pending request: let cleanup drain while new machines stay paused.
        return reservation.state() == MaterialReservation.State.UNAVAILABLE && !this.cleanup.isEmpty();
    }

    public void tick() {
        ClientLevel level = this.client.level;
        if (level == null) {
            this.reset();
            return;
        }
        long now = this.tickClock.getAsLong();
        if (now == this.lastProcessedTick) {
            return;
        }
        this.lastProcessedTick = now;
        this.stats.beginTick();
        this.cleanup.beginTick();
        this.criticalExecutor.beginTick(now);
        this.admission.purgeTargetsOutsideSelection();
        this.placer.tickPistonRetries();
        this.admission.beginTick(level);

        BedrockThroughputScheduler.Allocation allocation = this.throughputScheduler.allocate(
                this.admission.configuredThroughput(),
                this.admission.configuredInterval()
        );
        boolean submissionWindowOpen = allocation.total() > 0;
        int throughput = this.admission.configuredThroughput();
        this.submissionBudget = submissionWindowOpen && !this.awaitingSupplies ? throughput : 0;
        this.admission.setSubmissionBudget(this.submissionBudget);
        int continuationTotal = throughput;
        int continuationCritical = (continuationTotal + 1) / 2;
        int continuationPreparation = continuationTotal - continuationCritical;
        Set<BedrockTarget> processedTargets = new LinkedHashSet<>();
        BedrockTarget sideLookTarget = this.awaitingSupplies ? null : this.targets.findSideLookTarget();
        int unusedFastLaneBudget = this.targetExecutor.process(
                level, continuationCritical, true, processedTargets, sideLookTarget, !this.awaitingSupplies);
        int preparationBudget = continuationPreparation + unusedFastLaneBudget;
        int unusedBudget = this.targetExecutor.process(
                level, preparationBudget, false, processedTargets, sideLookTarget, !this.awaitingSupplies);
        if (unusedBudget > 0) {
            unusedBudget = this.targetExecutor.process(
                    level, unusedBudget, true, processedTargets, sideLookTarget, !this.awaitingSupplies);
        }
        this.throughputScheduler.consume(allocation, unusedBudget);
        this.cleanup.process(this.targets::isReserved, this.admission.cleanupLimitPerTick());
        this.admission.refreshCleanupPressure(level);
    }

    public boolean hasActiveWork() {
        return !this.targets.isEmpty() || !this.cleanup.isEmpty();
    }

    public boolean hasPendingScanWork() {
        return this.admission.hasPendingScanWork(this.tickClock.getAsLong());
    }

    public int getPendingScanWorkCount() {
        return this.admission.pendingScanWorkCount(this.tickClock.getAsLong());
    }

    public boolean canScanForTargets() {
        return !this.awaitingSupplies && this.admission.canScanForTargets();
    }

    boolean canSubmitInCurrentWindow() {
        return !this.awaitingSupplies && this.submissionBudget > 0;
    }

    public boolean canAccept(BlockPos pos) {
        return !this.awaitingSupplies && this.admission.canAccept(pos);
    }

    public boolean isPositionOnRetryCooldown(BlockPos pos) {
        return this.admission.isPositionOnRetryCooldown(pos);
    }

    public int getSchedulingPenalty(BlockPos pos) {
        return this.admission.schedulingPenalty(pos);
    }

    public int getPredictedMachineOverlapPenalty(
            BlockPos bedrockPos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement
    ) {
        return this.admission.predictedMachineOverlapPenalty(bedrockPos, layout, placement);
    }

    public boolean shouldSkipSchedulingHotspot(BlockPos pos) {
        return this.admission.shouldSkipSchedulingHotspot(pos);
    }

    public boolean submit(BlockPos pos) {
        return !this.awaitingSupplies && this.admission.submit(pos);
    }

    boolean isPositionReservedByOtherTarget(BlockPos pos, BedrockTarget self) {
        return this.targets.isReservedByOther(pos, self);
    }

    boolean isTorchPlacementReservedByOtherTarget(
            BedrockTorchPlacement placement,
            BedrockTarget self
    ) {
        return this.targets.isTorchPlacementReservedByOther(placement, self);
    }

    public void clearSubmissionPlans() {
        this.admission.clearSubmissionPlans();
    }

    public void primeSubmissionPlan(
            BlockPos bedrockPos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement,
            BlockPos slimePos
    ) {
        this.admission.primeSubmissionPlan(bedrockPos, layout, placement, slimePos);
    }

    public HudSnapshot getHudSnapshot() {
        return new HudSnapshot(
                this.targets.size(),
                this.targets.countActive(),
                this.targets.countVerticalActive(),
                this.targets.countSide(),
                this.cleanup.size(),
                this.admission.cleanupPressure(),
                this.admission.blockedCleanupDemand(),
                this.stats.confirmedSuccesses,
                this.stats.submittedTargets,
                this.stats.failedTargets,
                this.stats.stuckTargets,
                this.stats.acceptedThisTick,
                this.stats.rejectedThisTick,
                this.admission.configuredThroughput(),
                this.admission.submitCap(),
                this.admission.activeCap(),
                this.admission.verticalActiveCap(),
                this.admission.sideCap(),
                this.stats.successRate(),
                this.stats.lastReason,
                this.networkSync.pendingCount(),
                this.networkSync.adaptiveBackoffs(),
                this.networkSync.serverUpdateTimeouts(),
                this.networkSync.lastResult()
        );
    }

    public record HudSnapshot(
            int totalTargets,
            int activeTargets,
            int verticalActiveTargets,
            int sideTargets,
            int cleanupQueueSize,
            int cleanupPressure,
            int blockedCleanupDemand,
            int confirmedSuccesses,
            int submittedTargets,
            int failedTargets,
            int stuckTargets,
            int acceptedThisTick,
            int rejectedThisTick,
            int configuredThroughput,
            int submitCap,
            int activeCap,
            int verticalActiveCap,
            int sideCap,
            double successRate,
            String lastReason,
            int pendingServerUpdates,
            int adaptiveBackoffs,
            int serverUpdateTimeouts,
            String lastNetworkResult
    ) {
    }
}
