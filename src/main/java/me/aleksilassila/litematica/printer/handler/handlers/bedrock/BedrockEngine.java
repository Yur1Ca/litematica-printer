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
    private final BedrockBatchPipeline batchPipeline;
    private final BedrockThroughputScheduler throughputScheduler = new BedrockThroughputScheduler();
    private final LongSupplier tickClock;
    private long lastProcessedTick = Long.MIN_VALUE;
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
        this.placer = new BedrockPlacer(client, this.networkSync, true);
        this.criticalExecutor = new BedrockCriticalExecutor(this.placer, this.stats);
        this.cleanup = new BedrockCleanupCoordinator(
                client, cooldownUtils, this.networkSync, this::mutationsDeferred, this.placer);
        this.admission = new BedrockAdmissionController(
                client, this.targets, this.cleanup, this.stats, cooldownUtils, tickClock,
                this.criticalExecutor, this.placer, litematica, this.networkSync);
        this.targetExecutor = new BedrockTargetExecutor(
                this.targets,
                this.cleanup,
                this.stats,
                this.admission::setRetryCooldown,
                this.placer,
                this.networkSync,
                this::mutationsDeferred
        );
        this.batchPipeline = new BedrockBatchPipeline(this.targets, this.targetExecutor, this.criticalExecutor, this.placer);
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
        this.placer.resetInventorySync();
        this.criticalExecutor.reset();
        this.throughputScheduler.reset();
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
            this.placer.onCleanupBlockUpdate(pos);
        } else if (event instanceof RuntimeEvent.BlockActionAcknowledged ack) {
            this.placer.acknowledgeToolTick(ack.connection(), ack.sequence());
        }
    }

    /** Called only at the feature's inventory-safe boundary, never inside a critical bundle. */
    public boolean prepareMaterials(MaterialRequestCoordinator requests) {
        if (this.client.level == null || this.client.player == null) return false;
        BedrockSupplies.Requirement missing = BedrockSupplies.missing(this.client.level, this.targets);
        this.awaitingSupplies = missing != null || requests.isBusy();
        // Background provider ticks own completion, including a cancelled operation's recovery.
        if (requests.isBusy()) return false;
        if (this.mutationsDeferred() || this.placer.hasActiveCleanupMining()) return true;
        if (missing == null) return true;
        // Waiting for the whole registry to empty would deadlock an unexecuted machine on
        // the very materials it needs to finish. Only dispatched attempts must settle first.
        if (BedrockSupplies.hasDispatchedAttempt(this.targets)) {
            return true;
        }
        // A rejected raw use may leave the client's last material optimistically empty.
        // Resolve that dirty baseline once before declaring supplies unavailable.
        if (this.placer.reconcileInventoryIfDirty()) return true;
        this.placer.clearHorizontalLookState();
        MaterialReservation reservation = requests.request(
                missing.item(), missing.minimumCount(), MaterialRequest.Source.BEDROCK);
        // No provider owns a pending request: let cleanup drain while new machines stay paused.
        return reservation.state() == MaterialReservation.State.UNAVAILABLE
                && (!this.cleanup.isEmpty() || BedrockSupplies.hasRecoveringMachine(this.targets));
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
        this.targetExecutor.observeAndRetire(level);
        if (!this.mutationsDeferred()) this.placer.advanceCleanupMining();
        // Finish already-owned cycles before preparation can change the hand or start a real menu swap.
        this.cleanup.process(this.targets::isReserved, this.admission.cleanupLimitPerTick());
        this.admission.beginTick(level);

        BedrockThroughputScheduler.Allocation allocation = this.throughputScheduler.allocate(
                this.admission.effectiveThroughput(),
                this.admission.configuredInterval()
        );
        // Reservation only reads the world and owns a footprint. Inventory settlement and
        // a dispatched bundle still gate every physical mutation in the pipeline below.
        this.admission.setSubmissionBudget(this.allowAdmission() ? allocation.total() : 0);
        this.batchPipeline.advance(level, this.allowPreparation());
        this.cleanup.process(this.targets::isReserved, this.admission.cleanupLimitPerTick());
        this.admission.refreshCleanupPressure(level);
    }

    public boolean hasActiveWork() {
        return !this.targets.isEmpty() || !this.cleanup.isEmpty()
                || this.networkSync.pendingCount() > 0 || this.placer.isInventorySettling()
                || this.placer.hasActiveCleanupMining();
    }

    public boolean hasPendingScanWork() {
        return this.admission.hasPendingScanWork(this.tickClock.getAsLong());
    }

    public int getPendingScanWorkCount() {
        return this.admission.pendingScanWorkCount(this.tickClock.getAsLong());
    }

    public boolean canScanForTargets() {
        return this.allowAdmission() && this.admission.canScanForTargets();
    }

    boolean canSubmitInCurrentWindow() {
        return this.allowAdmission() && this.admission.hasSubmissionBudget();
    }

    public boolean canAccept(BlockPos pos) {
        return this.allowAdmission() && this.admission.canAccept(pos);
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
        return this.allowAdmission() && this.admission.submit(pos);
    }

    private boolean allowAdmission() {
        return !this.awaitingSupplies;
    }

    private boolean allowPreparation() {
        return this.allowAdmission() && !this.mutationsDeferred() && !this.placer.isToolSettling()
                && !this.placer.hasActiveCleanupMining();
    }

    private boolean mutationsDeferred() {
        return this.hasDispatchedInCurrentTick() || this.placer.isInventorySettling();
    }

    private boolean hasDispatchedInCurrentTick() {
        return this.lastProcessedTick == this.tickClock.getAsLong() && this.criticalExecutor.hasDispatchedThisTick();
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

    boolean hasInFlightPowerConflict(BedrockTarget self, BlockPos torchPos) {
        return this.targets.hasInFlightPowerConflict(self, torchPos);
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
        int cleanupCycles = this.cleanup.reachableCycleCount();
        int verticalCleanup = this.cleanup.verticalCycleCount();
        return new HudSnapshot(
                this.targets.size() + cleanupCycles,
                this.targets.countActive() + cleanupCycles,
                this.targets.countVerticalActive() + verticalCleanup,
                this.targets.countSide() + cleanupCycles - verticalCleanup,
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
