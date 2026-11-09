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
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

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
    private final BedrockThroughputScheduler<BlockPos> throughputScheduler = new BedrockThroughputScheduler<>();
    private final LongSupplier tickClock;
    private long lastProcessedTick = Long.MIN_VALUE;
    private boolean awaitingSupplies;
    private boolean mutationsAllowed;

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
        this.criticalExecutor = new BedrockCriticalExecutor(this.placer);
        this.cleanup = new BedrockCleanupCoordinator(
                client, this.networkSync, this::mutationsDeferred, this.placer);
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
                this.criticalExecutor,
                this.throughputScheduler
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
        this.placer.resetInventorySync();
        this.throughputScheduler.reset();
        this.targetExecutor.reset();
        this.awaitingSupplies = false;
        this.mutationsAllowed = false;
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
            this.admission.onServerBlockUpdate(pos);
        } else if (event instanceof RuntimeEvent.BlockActionAcknowledged ack) {
            this.placer.acknowledgeToolTick(ack.connection(), ack.sequence());
        } else if (event instanceof RuntimeEvent.InventorySynchronized sync) {
            this.placer.onInventorySynchronized(sync.connection());
        }
    }

    /** Refresh construction permission without acquiring a provider or moving inventory. */
    BedrockSupplies.Requirement observeSupplies(MaterialRequestCoordinator requests) {
        var missing = this.client.level == null || this.client.player == null ? null
                : BedrockSupplies.missing(this.client.level, this.targets);
        this.awaitingSupplies = missing != null || requests.isBusy();
        return missing;
    }

    /** Called only at the feature's inventory-safe boundary, never inside a critical bundle. */
    public boolean prepareMaterials(MaterialRequestCoordinator requests) {
        if (this.client.level == null || this.client.player == null) return false;
        BedrockSupplies.Requirement missing = this.observeSupplies(requests);
        // Background provider ticks own completion, including a cancelled operation's recovery.
        if (requests.isBusy()) return false;
        if (this.placer.isInventorySettling() || this.placer.hasActiveCleanupMining()) return true;
        if (missing == null) return true;
        // Waiting for the whole registry to empty would deadlock an unexecuted machine on
        // the very materials it needs to finish. Preserve a funded attempt's already-sent
        // tool boundary as well as an attempt whose critical body has been dispatched.
        if (BedrockSupplies.hasDispatchedAttempt(this.targets) || this.awaitingFundedCriticalTool()) {
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

    private boolean awaitingFundedCriticalTool() {
        if (!this.placer.isToolSettling() || !InventoryUtils.getOffhandStack(this.client.player).is(Items.PISTON)
                || this.placer.availablePistons() < 1 || !this.placer.canReserveToolUses(1)) return false;
        var iterator = this.targets.iterator();
        while (iterator.hasNext()) {
            var target = iterator.next();
            if (target == null || target.hasTried() || target.getStatus() != BedrockTarget.Status.EXTENDED
                    || !target.canMutatePower() || !BedrockTargetBlocks.isTargetBlock(this.client.level.getBlockState(target.getBedrockPos()))
                    || !BedrockEnvironment.arePositionsInteractable(target.getStaticMachinePositions())) continue;
            var piston = this.client.level.getBlockState(target.getPistonPos());
            if (!piston.is(Blocks.PISTON) || !piston.getValue(PistonBaseBlock.EXTENDED)
                    || piston.getValue(PistonBaseBlock.FACING) != target.layout().getPrimingFacing()
                    || !BedrockInventory.canBreakInCriticalBundle(this.client.player, piston, this.client.player.getMainHandItem())) continue;
            var torches = target.getOwnedTorchPositions();
            if (!torches.isEmpty() && torches.stream().allMatch(pos ->
                    this.client.level.getBlockState(pos).getValue(RedstoneTorchBlock.LIT))) return true;
        }
        return false;
    }

    public void tick() { this.beginTick(true); }

    /** Observe and service existing cycles before the scanner decides whether it may admit work. */
    public void beginTick(boolean allowMutations) {
        ClientLevel level = this.client.level;
        if (level == null) {
            this.reset();
            return;
        }
        long now = this.tickClock.getAsLong();
        this.mutationsAllowed = allowMutations;
        if (now == this.lastProcessedTick) {
            return;
        }
        this.lastProcessedTick = now;
        this.throughputScheduler.beginTick(now, this.admission.effectiveThroughput(),
                this.admission.configuredInterval(), !this.mutationsDeferred() && this.allowAdmission());
        this.stats.beginTick();
        this.cleanup.beginTick();
        this.admission.purgeTargetsOutsideSelection();
        this.targetExecutor.observeAndRetire(level);
        if (!this.mutationsDeferred()) this.placer.maintainInventory(Math.max(2, this.admission.effectiveThroughput() * 2));
        this.advanceWork(level);
        this.observeCycleCompletions(level);
        this.admission.beginTick(level);
        // Reserving a candidate does not send a USE. The executor paces the first
        // actual construction use even when an inventory fence releases a backlog.
        this.admission.setSubmissionBudget(this.admission.effectiveThroughput());
        this.admission.refreshCleanupPressure(level);
    }

    /** New reservations use this tick's remaining action phase instead of waiting for another tick. */
    public void finishTick(boolean allowMutations) {
        if (this.client.level == null) return;
        if (this.lastProcessedTick != this.tickClock.getAsLong()) {
            this.beginTick(allowMutations);
            return;
        }
        this.mutationsAllowed = allowMutations;
        this.targetExecutor.observeAndRetire(this.client.level);
        this.advanceWork(this.client.level);
        this.observeCycleCompletions(this.client.level);
        this.admission.refreshCleanupPressure(this.client.level);
    }

    private void observeCycleCompletions(ClientLevel level) {
        this.throughputScheduler.observeCycles(owner -> {
            boolean observable = BedrockEnvironment.isChunkLoaded(level, owner)
                    && BedrockEnvironment.canInteract(owner);
            if (this.targets.containsOwner(owner) || this.cleanup.hasReachableCycle(owner)) {
                return observable ? BedrockThroughputScheduler.CycleState.RUNNING
                        : BedrockThroughputScheduler.CycleState.UNMEASURED;
            }
            return observable && !BedrockTargetBlocks.isTargetBlock(level.getBlockState(owner))
                    && this.cleanup.isCycleClear(owner) ? BedrockThroughputScheduler.CycleState.COMPLETE
                    : BedrockThroughputScheduler.CycleState.DISCARDED;
        });
    }

    private void advanceWork(ClientLevel level) {
        if (this.mutationsDeferred()) return;
        this.placer.advanceCleanupMining();
        if (!this.targetExecutor.hasPreparationMaterialHand()) {
            this.cleanup.process(this.targets::isReserved, this.admission.cleanupLimitPerTick());
        }
        this.targetExecutor.advance(level, this.allowAdmission());
        this.cleanup.process(this.targets::isReserved, this.admission.cleanupLimitPerTick());
    }

    public boolean hasActiveWork() {
        return !this.targets.isEmpty() || !this.cleanup.isEmpty()
                || this.networkSync.pendingCount() > 0 || this.placer.isInventoryPreparationPending()
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

    private boolean mutationsDeferred() {
        return !this.mutationsAllowed || this.placer.isInventorySettling();
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
