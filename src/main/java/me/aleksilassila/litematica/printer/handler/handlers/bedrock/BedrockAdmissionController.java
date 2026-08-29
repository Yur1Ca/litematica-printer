package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Owns bedrock candidate admission, retry and capacity policy. */
final class BedrockAdmissionController {
    private static final String RETRY_COOLDOWN_KEY = "bedrock_retry";
    private static final int SUBMIT_RETRY_COOLDOWN_TICKS = 6;
    private static final int MACHINE_OVERLAP_RETRY_COOLDOWN_TICKS = 4;
    private static final int STARTUP_EXPOSURE_RETRY_COOLDOWN_TICKS = 4;
    private static final int MAX_VERTICAL_EXPOSURE_DEFERS = 1;
    private static final int ACCEPT_BACKPRESSURE_TICKS = 1;
    private static final int SIDE_TARGET_CAP = 1;
    private static final int HOTSPOT_SKIP_PENALTY = 120;
    private static final int BASE_CLEANUP_LIMIT_PER_TICK = 48;
    private static final int BLOCKED_CLEANUP_BONUS_LIMIT = 32;

    private final Minecraft client;
    private final BedrockTargetRegistry targets;
    private final BedrockCleanupCoordinator cleanup;
    private final BedrockRunStats stats;
    private final CooldownUtils cooldownUtils;
    private final LongSupplier tickClock;
    private final BedrockCriticalExecutor criticalExecutor;
    private final BedrockPlacer placer;
    private final BedrockNetworkSync networkSync;
    private final LitematicaAdapter litematica;
    private final BedrockExposureGate<BlockPos> exposureGate =
            new BedrockExposureGate<>(MAX_VERTICAL_EXPOSURE_DEFERS);
    private final BedrockScanActivityPolicy scanActivity = new BedrockScanActivityPolicy();
    private final BedrockSubmissionPlanCache submissionPlans = new BedrockSubmissionPlanCache();
    private final BedrockSchedulingProbe schedulingProbe;
    private long nextAcceptTick;
    private int cleanupPressure;
    private int blockedCleanupDemand;
    private int submissionBudget = 1;

    BedrockAdmissionController(
            Minecraft client,
            BedrockTargetRegistry targets,
            BedrockCleanupCoordinator cleanup,
            BedrockRunStats stats,
            CooldownUtils cooldownUtils,
            LongSupplier tickClock,
            BedrockCriticalExecutor criticalExecutor,
            BedrockPlacer placer,
            LitematicaAdapter litematica,
            BedrockNetworkSync networkSync
    ) {
        this.client = client;
        this.targets = targets;
        this.cleanup = cleanup;
        this.stats = stats;
        this.cooldownUtils = cooldownUtils;
        this.tickClock = tickClock;
        this.criticalExecutor = criticalExecutor;
        this.placer = placer;
        this.litematica = litematica;
        this.networkSync = networkSync;
        this.schedulingProbe = new BedrockSchedulingProbe(client, targets, cleanup);
    }

    void reset() {
        this.exposureGate.clear();
        this.scanActivity.reset();
        this.submissionPlans.clear();
        this.nextAcceptTick = 0L;
        this.cleanupPressure = 0;
        this.blockedCleanupDemand = 0;
        this.submissionBudget = 1;
    }

    void beginTick(ClientLevel level) {
        this.blockedCleanupDemand = 0;
        this.cleanupPressure = this.cleanup.samplePressure(level, this.targets::isReserved);
    }

    void setSubmissionBudget(int budget) {
        this.submissionBudget = Math.max(0, budget);
    }

    void refreshCleanupPressure(ClientLevel level) {
        this.cleanupPressure = this.cleanup.samplePressure(level, this.targets::isReserved);
    }

    boolean hasPendingScanWork(long tick) {
        return this.scanActivity.hasPendingWork(tick, this.targets.size());
    }

    int pendingScanWorkCount(long tick) {
        return this.scanActivity.getPendingWorkCount(tick, this.targets.size());
    }

    boolean canScanForTargets() {
        if (BedrockInventory.warningMessage() != null) {
            this.stats.lastReason = "missing_resources";
            return false;
        }
        AcceptProbe probe = this.probeCanScanForTargets();
        if (!probe.accepted()) {
            this.stats.lastReason = probe.reason();
        }
        return probe.accepted();
    }

    boolean canAccept(BlockPos pos) {
        BlockPos stablePos = stablePos(pos);
        AcceptProbe probe = this.probeCanAccept(stablePos, true);
        if (probe.accepted()) {
            return true;
        }
        this.stats.lastReason = probe.reason();
        if ("out_of_range_bedrock".equals(probe.reason())) {
            this.setRetryCooldown(stablePos, SUBMIT_RETRY_COOLDOWN_TICKS);
        } else if ("await_target_exposure".equals(probe.reason())) {
            this.setRetryCooldown(stablePos, STARTUP_EXPOSURE_RETRY_COOLDOWN_TICKS);
        }
        return false;
    }

    boolean submit(BlockPos pos) {
        BlockPos stablePos = stablePos(pos);
        ClientLevel level = this.client.level;
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(stablePos))) {
            return false;
        }
        if (!isWithinActiveSelection(stablePos)) {
            this.stats.lastReason = "outside_selection";
            return false;
        }
        if (!this.canAccept(stablePos)) {
            return false;
        }
        BedrockSubmissionPlanCache.Plan plan = this.submissionPlans.consume(
                stablePos,
                this.tickClock.getAsLong()
        );
        BedrockTarget target = plan != null
                ? new BedrockTarget(stablePos, level, plan.layout(), plan.placement(), plan.slimePos(), this.criticalExecutor, this.placer, this.networkSync)
                : new BedrockTarget(stablePos, level, this.criticalExecutor, this.placer, this.networkSync);
        if (target.getStatus() == BedrockTarget.Status.FAILED) {
            this.stats.lastReason = "target_failed_on_create";
            this.setRetryCooldown(stablePos, SUBMIT_RETRY_COOLDOWN_TICKS);
            return false;
        }
        if (BedrockEnvironment.findFirstOutOfRangePosition(target.getStaticMachinePositions()) != null) {
            this.stats.lastReason = "out_of_range_machine";
            this.setRetryCooldown(stablePos, SUBMIT_RETRY_COOLDOWN_TICKS);
            return false;
        }
        BlockPos pendingCleanup = this.findPendingCleanupConflict(target);
        if (pendingCleanup != null) {
            this.stats.lastReason = "pending_cleanup";
            var state = level.getBlockState(pendingCleanup);
            this.cleanup.expedite(pendingCleanup, state, this.targets::isReserved);
            this.setRetryCooldown(stablePos, Math.max(
                    SUBMIT_RETRY_COOLDOWN_TICKS,
                    this.cleanup.retryDelay(state) + 2
            ));
            this.noteSubmitRejected("pending_cleanup");
            return false;
        }
        BedrockTarget conflict = this.targets.findConflict(target);
        if (conflict != null) {
            this.stats.lastReason = "machine_overlap";
            this.setRetryCooldown(stablePos, MACHINE_OVERLAP_RETRY_COOLDOWN_TICKS);
            this.noteSubmitRejected("machine_overlap");
            return false;
        }
        this.targets.add(target);
        this.stats.acceptedThisTick++;
        this.submissionBudget--;
        this.stats.submittedTargets++;
        this.stats.lastReason = "running";
        return true;
    }

    boolean isPositionOnRetryCooldown(BlockPos pos) {
        BlockPos stablePos = stablePos(pos);
        return this.client.level != null
                && this.cooldownUtils.isOnCooldown(this.client.level, RETRY_COOLDOWN_KEY, stablePos);
    }

    int schedulingPenalty(BlockPos pos) {
        return this.schedulingProbe.penalty(pos);
    }

    int predictedMachineOverlapPenalty(
            BlockPos pos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement
    ) {
        return this.schedulingProbe.predictedMachineOverlapPenalty(pos, layout, placement);
    }

    boolean shouldSkipSchedulingHotspot(BlockPos pos) {
        return this.cleanupPressure >= this.mediumCleanupPressureThreshold()
                && this.schedulingPenalty(pos) >= HOTSPOT_SKIP_PENALTY;
    }

    void setRetryCooldown(BlockPos pos, int ticks) {
        if (this.client.level != null && pos != null && ticks > 0) {
            this.cooldownUtils.setCooldown(this.client.level, RETRY_COOLDOWN_KEY, pos, ticks);
            this.scanActivity.recordRetry(this.tickClock.getAsLong(), ticks);
        }
    }

    int configuredThroughput() {
        return Math.max(1, Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue());
    }

    int configuredInterval() {
        return Math.max(1, Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue());
    }

    int submitCap() {
        return BedrockSchedulingPolicy.submitCap(this.configuredThroughput(), this.cleanupPressure);
    }

    int activeCap() {
        return this.verticalActiveCap() + this.sideCap();
    }

    int verticalActiveCap() {
        return BedrockSchedulingPolicy.verticalActiveCap(this.configuredThroughput());
    }

    int sideCap() {
        return this.isSideEnabled() ? SIDE_TARGET_CAP : 0;
    }

    int cleanupLimitPerTick() {
        return BedrockSchedulingPolicy.cleanupLimit(
                this.configuredThroughput(),
                this.blockedCleanupDemand,
                BASE_CLEANUP_LIMIT_PER_TICK,
                BLOCKED_CLEANUP_BONUS_LIMIT
        );
    }

    int cleanupPressure() {
        return this.cleanupPressure;
    }

    int blockedCleanupDemand() {
        return this.blockedCleanupDemand;
    }

    void clearSubmissionPlans() {
        this.submissionPlans.clear();
    }

    void primeSubmissionPlan(
            BlockPos pos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement,
            BlockPos slimePos
    ) {
        this.submissionPlans.put(
                pos, layout, placement, slimePos, this.tickClock.getAsLong());
    }

    void purgeTargetsOutsideSelection() {
        this.targets.removeOutsideSelection(this::isWithinActiveSelection, target -> {
            for (BlockPos tempPos : target.getCleanupPositions()) {
                this.cleanup.cleanupBlockOrQueue(tempPos, false, this.targets::isReserved);
            }
        });
    }

    private BlockPos findPendingCleanupConflict(BedrockTarget candidate) {
        if (this.client.level == null) {
            return null;
        }
        for (BlockPos pos : blockingCleanupPositions(candidate)) {
            if (pos.equals(candidate.getBedrockPos())) {
                continue;
            }
            var state = this.client.level.getBlockState(pos);
            if (state.isAir() || (!BedrockTargetBlocks.isCleanupResidue(state) && !this.targets.isReserved(pos))) {
                this.cleanup.remove(pos);
                continue;
            }
            if (candidate.canReusePendingCleanupPosition(pos, state)
                    || this.targets.canReuseBlockingPosition(candidate, pos, state)) {
                continue;
            }
            if (this.cleanup.contains(pos) || BedrockTargetBlocks.isCleanupResidue(state)) {
                if (!this.cleanup.contains(pos) && !this.targets.isReserved(pos)) {
                    this.cleanup.add(pos, false);
                }
                this.blockedCleanupDemand++;
                this.cleanup.markBlocked(pos);
                return pos;
            }
        }
        return null;
    }

    private AcceptProbe probeCanScanForTargets() {
        if (this.submissionBudget <= 0) {
            return AcceptProbe.reject("interval_window");
        }
        if (this.stats.confirmedSuccesses == 0
                && this.configuredThroughput() <= 1
                && !this.targets.isEmpty()) {
            return AcceptProbe.reject("startup_serial");
        }
        if (this.tickClock.getAsLong() < this.nextAcceptTick) {
            return AcceptProbe.reject("accept_backpressure");
        }
        if (this.stats.acceptedThisTick >= this.submitCap()) {
            return AcceptProbe.reject("submit_cap");
        }
        if (this.targets.countVerticalActive() < this.verticalActiveCap()) {
            return AcceptProbe.accept();
        }
        if (this.isSideEnabled() && this.targets.findSideExclusive() == null) {
            return AcceptProbe.accept();
        }
        return AcceptProbe.reject("active_cap");
    }

    private AcceptProbe probeCanAccept(BlockPos pos, boolean mutateExposureState) {
        AcceptProbe scanProbe = this.probeCanScanForTargets();
        if (!scanProbe.accepted()) return scanProbe;
        if (!isWithinActiveSelection(pos)) return AcceptProbe.reject("outside_selection");
        if (this.isPositionOnRetryCooldown(pos)) return AcceptProbe.reject("retry_cooldown");
        boolean horizontal = this.isHorizontalSubmission(pos);
        if (horizontal && !this.isSideEnabled()) return AcceptProbe.reject("side_disabled");
        if (horizontal && this.targets.findSideExclusive() != null) return AcceptProbe.reject("side_lane_busy");
        if (!horizontal && this.targets.countVerticalActive() >= this.verticalActiveCap()) {
            return AcceptProbe.reject("active_cap");
        }
        if (this.targets.isReserved(pos)) return AcceptProbe.reject("reserved_by_active_target");
        if (!BedrockEnvironment.canInteract(pos)) return AcceptProbe.reject("out_of_range_bedrock");
        boolean shouldDefer = this.client.level != null
                && BedrockMachineLayout.shouldDeferUntilExposed(this.client.level, pos);
        if (this.exposureGate.evaluate(pos, shouldDefer, mutateExposureState)
                == BedrockExposureGate.Decision.DEFER) {
            return AcceptProbe.reject("await_target_exposure");
        }
        String conflict = this.targets.activePositionConflict(pos);
        return conflict == null ? AcceptProbe.accept() : AcceptProbe.reject(conflict);
    }

    private boolean isHorizontalSubmission(BlockPos pos) {
        Boolean plannedHorizontal = this.submissionPlans.horizontal(pos);
        if (plannedHorizontal != null) {
            return plannedHorizontal;
        }
        if (this.client.level == null || pos == null) {
            return false;
        }
        BedrockMachineLayout layout = BedrockMachineLayout.find(this.client.level, pos);
        return layout != null && layout.isHorizontal();
    }

    private boolean isSideEnabled() {
        return Configs.Bedrock.BEDROCK_ALLOW_SIDE.getBooleanValue();
    }

    private int mediumCleanupPressureThreshold() {
        return BedrockSchedulingPolicy.mediumCleanupPressureThreshold(this.configuredThroughput());
    }

    private void noteSubmitRejected(String reason) {
        int weight = this.cleanupPressure < this.mediumCleanupPressureThreshold()
                && ("machine_overlap".equals(reason) || "pending_cleanup".equals(reason)) ? 0 : 1;
        if ("machine_overlap".equals(reason) && this.cleanupPressure <= 0) {
            weight = 0;
        }
        this.stats.rejectedThisTick += weight;
        boolean heavyPressure = this.cleanupPressure
                >= BedrockSchedulingPolicy.highCleanupPressureThreshold(this.configuredThroughput());
        if (heavyPressure || this.stats.rejectedThisTick >= Math.max(8, this.configuredThroughput())) {
            this.nextAcceptTick = Math.max(
                    this.nextAcceptTick,
                    this.tickClock.getAsLong() + ACCEPT_BACKPRESSURE_TICKS
            );
        }
    }

    private static Set<BlockPos> blockingCleanupPositions(BedrockTarget candidate) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        positions.add(candidate.getPistonPos());
        positions.add(candidate.getHeadPos());
        if (candidate.getTorchSupportPos() != null) positions.add(candidate.getTorchSupportPos());
        if (candidate.getTorchPos() != null) positions.add(candidate.getTorchPos());
        if (candidate.getSlimePos() != null) positions.add(candidate.getSlimePos());
        return positions;
    }

    private static BlockPos stablePos(BlockPos pos) {
        return pos == null ? null : pos.immutable();
    }

    private boolean isWithinActiveSelection(BlockPos pos) {
        return pos != null && this.litematica.isWithinSelectionRange(pos);
    }

    private record AcceptProbe(boolean accepted, String reason) {
        private static AcceptProbe accept() {
            return new AcceptProbe(true, "accepted");
        }

        private static AcceptProbe reject(String reason) {
            return new AcceptProbe(false, reason);
        }
    }
}
