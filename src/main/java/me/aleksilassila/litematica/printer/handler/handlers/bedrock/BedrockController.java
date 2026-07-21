package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BedrockController {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST
    };
    private static final String RETRY_COOLDOWN_KEY = "bedrock_retry";
    private static final String CLEANUP_RETRY_COOLDOWN_KEY = "cleanup_retry";
    private static final int SUBMIT_RETRY_COOLDOWN_TICKS = 6;
    private static final int MACHINE_OVERLAP_RETRY_COOLDOWN_TICKS = 4;
    private static final int STARTUP_EXPOSURE_RETRY_COOLDOWN_TICKS = 4;
    private static final int MAX_VERTICAL_EXPOSURE_DEFERS = 1;
    private static final int FAILURE_RETRY_COOLDOWN_TICKS = 12;
    private static final int BASE_CLEANUP_LIMIT_PER_TICK = 48;
    private static final int BLOCKED_CLEANUP_BONUS_LIMIT = 32;
    private static final int ACCEPT_BACKPRESSURE_TICKS = 1;
    private static final int SIDE_TARGET_CAP = 1;
    private static final int HOTSPOT_SKIP_PENALTY = 120;
    private static final List<BedrockTarget> TARGETS = new ArrayList<>();
    private static final Set<BlockPos> CLEANUP_QUEUE = new LinkedHashSet<>();
    private static final Set<BlockPos> CONSERVATIVE_CLEANUP = new LinkedHashSet<>();
    private static final Set<BlockPos> BLOCKED_CLEANUP_POSITIONS = new LinkedHashSet<>();
    private static final Map<BlockPos, Integer> EXPOSURE_DEFERRALS = new HashMap<>();
    private static final Map<BlockPos, Integer> EXPOSURE_BYPASS_USES = new HashMap<>();
    private static final Map<BlockPos, SubmissionPlan> SUBMISSION_PLANS = new HashMap<>();
    private static long nextAcceptTick = 0L;
    private static long nextExecuteTick = 0L;
    private static long lastProcessedTick = Long.MIN_VALUE;
    private static int acceptedThisTick = 0;
    private static int rejectedThisTick = 0;
    private static int cleanupPressureThisTick = 0;
    private static int blockedCleanupDemandThisTick = 0;
    private static int confirmedSuccessesSinceReset = 0;
    private static int submittedTargetsSinceReset = 0;
    private static int failedTargetsSinceReset = 0;
    private static int stuckTargetsSinceReset = 0;
    private static String lastHudReason = "idle";
    private BedrockController() {
    }

    public static void reset() {
        TARGETS.clear();
        CLEANUP_QUEUE.clear();
        CONSERVATIVE_CLEANUP.clear();
        BLOCKED_CLEANUP_POSITIONS.clear();
        EXPOSURE_DEFERRALS.clear();
        EXPOSURE_BYPASS_USES.clear();
        SUBMISSION_PLANS.clear();
        BedrockPlacer.clearHorizontalLookState();
        nextAcceptTick = 0L;
        nextExecuteTick = 0L;
        lastProcessedTick = Long.MIN_VALUE;
        acceptedThisTick = 0;
        rejectedThisTick = 0;
        cleanupPressureThisTick = 0;
        blockedCleanupDemandThisTick = 0;
        confirmedSuccessesSinceReset = 0;
        submittedTargetsSinceReset = 0;
        failedTargetsSinceReset = 0;
        stuckTargetsSinceReset = 0;
        lastHudReason = "idle";
        HudStatsManager.INSTANCE.resetMode(HudStatsManager.Mode.BEDROCK);
    }

    public static void clearHorizontalLookState() {
        BedrockPlacer.clearHorizontalLookState();
    }

    public static void tick() {
        ClientLevel level = CLIENT.level;
        if (level == null) {
            reset();
            return;
        }

        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (now == lastProcessedTick) {
            return;
        }
        lastProcessedTick = now;
        acceptedThisTick = 0;
        rejectedThisTick = 0;
        blockedCleanupDemandThisTick = 0;
        BLOCKED_CLEANUP_POSITIONS.clear();

        purgeTargetsOutsideSelection();
        processCleanupQueue();
        cleanupPressureThisTick = sampleCleanupPressure(level);

        if (BedrockInventory.warningMessage() != null) {
            return;
        }

        int executeBudget = getExecuteBudget();
        int initialExecuteBudget = executeBudget;
        Set<BedrockTarget> processedTargets = new LinkedHashSet<>();
        executeBudget = processTargets(level, executeBudget, true, processedTargets, findSideLookTarget());
        executeBudget = processTargets(level, executeBudget, false, processedTargets, findSideLookTarget());

        if (executeBudget < initialExecuteBudget) {
            scheduleNextExecuteWindow();
        }
    }

    public static boolean canScanForTargets() {
        AcceptProbe probe = probeCanScanForTargets();
        if (!probe.accepted()) {
            lastHudReason = probe.reason();
        }
        return probe.accepted();
    }

    public static boolean canAccept(BlockPos pos) {
        BlockPos stablePos = stablePos(pos);
        AcceptProbe probe = probeCanAccept(stablePos, true);
        if (probe.accepted()) {
            return true;
        }
        lastHudReason = probe.reason();
        if ("out_of_range_bedrock".equals(probe.reason())) {
            setRetryCooldown(stablePos, SUBMIT_RETRY_COOLDOWN_TICKS);
            return false;
        }
        if ("await_target_exposure".equals(probe.reason())) {
            setRetryCooldown(stablePos, STARTUP_EXPOSURE_RETRY_COOLDOWN_TICKS);
            return false;
        }
        return false;
    }

    public static boolean isPositionOnRetryCooldown(BlockPos pos) {
        return isTargetOnRetryCooldown(stablePos(pos));
    }

    public static int getSchedulingPenalty(BlockPos pos) {
        if (pos == null || CLIENT.level == null || (TARGETS.isEmpty() && CLEANUP_QUEUE.isEmpty())) {
            return 0;
        }

        int penalty = 0;
        penalty += getSchedulingProbePenalty(pos);
        penalty += getSchedulingProbePenalty(pos.above());
        penalty += getSchedulingProbePenalty(pos.above(2));

        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            BlockPos neighbor = pos.relative(direction);
            penalty += getSchedulingProbePenalty(neighbor);
            penalty += getSchedulingProbePenalty(neighbor.above());
        }

        return penalty;
    }

    public static int getPredictedMachineOverlapPenalty(BlockPos bedrockPos, BedrockMachineLayout layout, BedrockTorchPlacement placement) {
        if (CLIENT.level == null || bedrockPos == null || layout == null || TARGETS.isEmpty()) {
            return 0;
        }

        CandidateFootprint candidate = CandidateFootprint.of(bedrockPos, layout, placement);
        if (candidate.isEmpty()) {
            return 0;
        }

        int penalty = 0;
        for (BedrockTarget target : TARGETS) {
            if (candidate.conflictsWith(target)) {
                penalty += 4_000;
            }
        }
        return penalty;
    }

    public static boolean shouldSkipSchedulingHotspot(BlockPos pos) {
        if (cleanupPressureThisTick < getMediumCleanupPressureThreshold()) {
            return false;
        }
        return getSchedulingPenalty(pos) >= HOTSPOT_SKIP_PENALTY;
    }

    public static boolean submit(BlockPos pos) {
        BlockPos stablePos = stablePos(pos);
        ClientLevel level = CLIENT.level;
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(stablePos))) return false;
        if (!isWithinActiveSelection(stablePos)) {
            lastHudReason = "outside_selection";
            return false;
        }

        if (!canAccept(stablePos)) {
            return false;
        }
        SubmissionPlan submissionPlan = consumeSubmissionPlan(stablePos);
        BedrockTarget target = submissionPlan != null
                ? new BedrockTarget(stablePos, level, submissionPlan.layout(), submissionPlan.placement(), submissionPlan.slimePos())
                : new BedrockTarget(stablePos, level);
        if (target.getStatus() == BedrockTarget.Status.FAILED) {
            lastHudReason = "target_failed_on_create";
            setRetryCooldown(stablePos, SUBMIT_RETRY_COOLDOWN_TICKS);
            return false;
        }
        BlockPos outOfRangePos = BedrockEnvironment.findFirstOutOfRangePosition(target.getStaticMachinePositions());
        if (outOfRangePos != null) {
            lastHudReason = "out_of_range_machine";
            setRetryCooldown(stablePos, SUBMIT_RETRY_COOLDOWN_TICKS);
            return false;
        }

        BlockPos pendingCleanupPos = findPendingCleanupConflict(target);
        if (pendingCleanupPos != null) {
            lastHudReason = "pending_cleanup";
            var blockingState = level.getBlockState(pendingCleanupPos);
            expeditePendingCleanup(pendingCleanupPos, blockingState);
            setRetryCooldown(stablePos, getPendingCleanupRetryTicks(blockingState));
            noteSubmitRejected("pending_cleanup", stablePos, pendingCleanupPos);
            return false;
        }

        BedrockTarget conflict = findConflictTarget(target);
        if (conflict != null) {
            lastHudReason = "machine_overlap";
            setRetryCooldown(stablePos, MACHINE_OVERLAP_RETRY_COOLDOWN_TICKS);
            noteSubmitRejected("machine_overlap", stablePos, conflict.getBedrockPos());
            return false;
        }

        if (target.getStatus() != BedrockTarget.Status.FAILED) {
            TARGETS.add(target);
            acceptedThisTick++;
            submittedTargetsSinceReset++;
            lastHudReason = "running";
            return true;
        }
        return false;
    }

    private static void addToCleanup(BlockPos pos) {
        addToCleanup(pos, true);
    }

    private static void addToCleanup(BlockPos pos, boolean predictRemoval) {
        if (pos != null) {
            CLEANUP_QUEUE.add(pos);
            if (!predictRemoval) {
                CONSERVATIVE_CLEANUP.add(pos);
            }
        }
    }

    private static void processCleanupQueue() {
        if (CLEANUP_QUEUE.isEmpty()) return;

        reorderCleanupQueue();
        int limit = getCleanupLimitPerTick();
        int count = 0;
        Iterator<BlockPos> iterator = CLEANUP_QUEUE.iterator();

        while (iterator.hasNext() && count < limit) {
            BlockPos pos = iterator.next();
            if (CLIENT.level == null) break;

            var state = CLIENT.level.getBlockState(pos);
            if (state.isAir()) {
                iterator.remove();
                CONSERVATIVE_CLEANUP.remove(pos);
                BLOCKED_CLEANUP_POSITIONS.remove(pos);
                continue;
            }

            if (!BedrockTargetBlocks.isCleanupResidue(state)) {
                iterator.remove();
                CONSERVATIVE_CLEANUP.remove(pos);
                BLOCKED_CLEANUP_POSITIONS.remove(pos);
                continue;
            }

            if (isReservedByActiveTarget(pos)) {
                continue;
            }

            int retryDelay = getCleanupRetryDelay(state);
            if (!CooldownUtils.INSTANCE.isOnCooldown(CLIENT.level, CLEANUP_RETRY_COOLDOWN_KEY, pos)) {
                boolean predictRemoval = !CONSERVATIVE_CLEANUP.contains(pos);
                if (BedrockBreaker.breakBlock(pos, predictRemoval)) {
                    CooldownUtils.INSTANCE.setCooldown(CLIENT.level, CLEANUP_RETRY_COOLDOWN_KEY, pos, retryDelay);
                    count++;
                }
            }
        }
    }

    private static int getExecuteBudget() {
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (now < nextExecuteTick) {
            return 0;
        }
        return getConfiguredThroughput();
    }

    private static BedrockTarget findConflictTarget(BedrockTarget candidate) {
        for (BedrockTarget existing : TARGETS) {
            if (hasStructuralConflict(candidate, existing) || hasPowerConflict(candidate, existing)) {
                return existing;
            }
        }
        return null;
    }

    private static BlockPos findPendingCleanupConflict(BedrockTarget candidate) {
        if (CLIENT.level == null) {
            return null;
        }
        for (BlockPos pos : getBlockingCleanupPositions(candidate)) {
            if (pos.equals(candidate.getBedrockPos())) {
                continue;
            }
            var state = CLIENT.level.getBlockState(pos);
            if (state.isAir()) {
                CLEANUP_QUEUE.remove(pos);
                CONSERVATIVE_CLEANUP.remove(pos);
                continue;
            }
            if (!BedrockTargetBlocks.isCleanupResidue(state) && !isReservedByActiveTarget(pos)) {
                CLEANUP_QUEUE.remove(pos);
                CONSERVATIVE_CLEANUP.remove(pos);
                continue;
            }
            if (candidate.canReusePendingCleanupPosition(pos, state) || canReuseBlockingPosition(candidate, pos, state)) {
                continue;
            }
            if (CLEANUP_QUEUE.contains(pos)) {
                blockedCleanupDemandThisTick++;
                BLOCKED_CLEANUP_POSITIONS.add(pos);
                return pos;
            }
            if (BedrockTargetBlocks.isCleanupResidue(state)) {
                if (!isReservedByActiveTarget(pos)) {
                    addToCleanup(pos, false);
                }
                blockedCleanupDemandThisTick++;
                BLOCKED_CLEANUP_POSITIONS.add(pos);
                return pos;
            }
        }
        return null;
    }

    private static Set<BlockPos> getBlockingCleanupPositions(BedrockTarget candidate) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        positions.add(candidate.getPistonPos());
        positions.add(candidate.getHeadPos());
        if (candidate.getTorchSupportPos() != null) {
            positions.add(candidate.getTorchSupportPos());
        }
        if (candidate.getTorchPos() != null) {
            positions.add(candidate.getTorchPos());
        }
        if (candidate.getSlimePos() != null) {
            positions.add(candidate.getSlimePos());
        }
        return positions;
    }

    private static boolean hasStructuralConflict(BedrockTarget candidate, BedrockTarget existing) {
        Set<BlockPos> candidateStructural = candidate.getStructuralPositions();
        Set<BlockPos> existingStructural = existing.getStructuralPositions();
        for (BlockPos pos : candidateStructural) {
            if (existingStructural.contains(pos) || existing.getPowerReservationPositions().contains(pos)) {
                return true;
            }
        }
        for (BlockPos pos : candidate.getPowerReservationPositions()) {
            if (existingStructural.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPowerConflict(BedrockTarget candidate, BedrockTarget existing) {
        if (candidate.sharesTorchPlacementWith(existing)) {
            return false;
        }
        BlockPos candidateTorchPos = candidate.getTorchPos();
        if (candidateTorchPos != null && existing.isTorchPoweredBy(candidateTorchPos)) {
            return true;
        }
        BlockPos existingTorchPos = existing.getTorchPos();
        if (existingTorchPos != null && candidate.isTorchPoweredBy(existingTorchPos)) {
            return true;
        }
        return false;
    }

    private static boolean canReuseBlockingPosition(BedrockTarget candidate, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (pos == null || state == null || state.isAir()) {
            return false;
        }
        for (BedrockTarget target : TARGETS) {
            if (!target.sharesTorchPlacementWith(candidate)) {
                continue;
            }
            return candidate.canReusePowerReservation(pos, state);
        }
        return false;
    }

    private static int processTargets(ClientLevel level, int executeBudget, boolean priorityOnly, Set<BedrockTarget> processedTargets, BedrockTarget sideLookTarget) {
        Iterator<BedrockTarget> iterator = TARGETS.iterator();
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
            BlockPos outOfRangePos = BedrockEnvironment.findFirstOutOfRangePosition(target.getStaticMachinePositions());
            if (outOfRangePos != null) {
                BedrockTarget.Status status = target.refreshStatusOnly();
                if (shouldRetireOutOfRange(status)) {
                    cleanupTarget(iterator, target, "out_of_range");
                }
                processedTargets.add(target);
                continue;
            }

            boolean fastLane = isFastLaneStatus(target.getStatus());
            if (priorityOnly != fastLane) {
                continue;
            }

            boolean activeStatus = countsTowardsActiveCap(target.getStatus());
            BedrockTarget.Status status;
            boolean hadBudget = activeStatus && executeBudget > 0;
            if (hadBudget) {
                status = target.tick(true, true);
            } else if (activeStatus) {
                status = target.refreshStatusOnly();
            } else {
                status = target.tick(false, false);
            }
            processedTargets.add(target);

            if (hadBudget && target.consumedThroughputThisTick()) {
                executeBudget--;
            }
            boolean retireOnSuccessfulRetracting = status == BedrockTarget.Status.RETRACTING
                    && !BedrockTargetBlocks.isTargetBlock(level.getBlockState(target.getBedrockPos()));
            if (status == BedrockTarget.Status.RETRACTED
                    || status == BedrockTarget.Status.FAILED
                    || status == BedrockTarget.Status.STUCK
                    || retireOnSuccessfulRetracting) {
                cleanupTarget(iterator, target, null);
            } else if (target.isHorizontalLayout() && BedrockPlacer.hasPendingHorizontalLook(target.getPistonPos())) {
                break;
            }
        }
        return executeBudget;
    }

    private static void cleanupTarget(Iterator<BedrockTarget> iterator, BedrockTarget target, String reason) {
        if (target.getStatus() == BedrockTarget.Status.FAILED || target.getStatus() == BedrockTarget.Status.STUCK) {
            setRetryCooldown(target.getBedrockPos(), FAILURE_RETRY_COOLDOWN_TICKS);
        } else if ("out_of_range".equals(reason)) {
            setRetryCooldown(target.getBedrockPos(), SUBMIT_RETRY_COOLDOWN_TICKS);
        }
        if (shouldCountConfirmedSuccess(target, reason)) {
            confirmedSuccessesSinceReset++;
            lastHudReason = "running";
            HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.BEDROCK, 1);
        }
        if (target.getStatus() == BedrockTarget.Status.FAILED) {
            failedTargetsSinceReset++;
            lastHudReason = "failed";
        } else if (target.getStatus() == BedrockTarget.Status.STUCK) {
            stuckTargetsSinceReset++;
            lastHudReason = "stuck";
        }
        if (target.isHorizontalLayout()) {
            BedrockPlacer.clearHorizontalLookState();
        }
        iterator.remove();
        for (BlockPos tempPos : target.getCleanupPositions()) {
            cleanupBlockOrQueue(tempPos, false);
        }
    }

    private static int countActiveTargets() {
        int count = 0;
        for (BedrockTarget target : TARGETS) {
            if (target != null && countsTowardsActiveCap(target.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private static int countVerticalActiveTargets() {
        int count = 0;
        for (BedrockTarget target : TARGETS) {
            if (target != null && !target.isHorizontalLayout() && countsTowardsActiveCap(target.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private static int countSideTargets() {
        int count = 0;
        for (BedrockTarget target : TARGETS) {
            if (target != null && target.isHorizontalLayout()) {
                count++;
            }
        }
        return count;
    }

    private static BedrockTarget findSideExclusiveTarget() {
        for (BedrockTarget target : TARGETS) {
            if (target != null && target.isHorizontalLayout()) {
                return target;
            }
        }
        return null;
    }

    private static boolean hasSideExclusiveTarget() {
        return findSideExclusiveTarget() != null;
    }

    private static BedrockTarget findSideLookTarget() {
        for (BedrockTarget target : TARGETS) {
            if (target != null && target.isHorizontalLayout() && BedrockPlacer.hasPendingHorizontalLook(target.getPistonPos())) {
                return target;
            }
        }
        return null;
    }

    private static int getActiveTargetCap() {
        return getVerticalActiveTargetCap() + getSideTargetCap();
    }

    private static int getVerticalActiveTargetCap() {
        return Math.max(1, getConfiguredThroughput());
    }

    private static int getSideTargetCap() {
        return isSideEnabled() ? SIDE_TARGET_CAP : 0;
    }

    private static boolean isSideEnabled() {
        return Configs.Bedrock.BEDROCK_ALLOW_SIDE.getBooleanValue();
    }

    private static boolean canAcceptMoreVerticalTargets() {
        return countVerticalActiveTargets() < getVerticalActiveTargetCap();
    }

    private static int getSubmitCap() {
        int throughput = getConfiguredThroughput();
        int baseSubmitCap = Math.max(1, throughput);
        if (cleanupPressureThisTick >= getHighCleanupPressureThreshold()) {
            return 1;
        }
        if (cleanupPressureThisTick >= getMediumCleanupPressureThreshold()) {
            return Math.min(baseSubmitCap, 1);
        }
        if (cleanupPressureThisTick >= getLowCleanupPressureThreshold()) {
            return Math.min(baseSubmitCap, 1);
        }
        return baseSubmitCap;
    }

    private static boolean countsTowardsActiveCap(BedrockTarget.Status status) {
        return status == BedrockTarget.Status.UNINITIALIZED
                || status == BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE
                || status == BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE
                || status == BedrockTarget.Status.EXTENDED;
    }

    private static boolean isFastLaneStatus(BedrockTarget.Status status) {
        return status == BedrockTarget.Status.EXTENDED
                || status == BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
    }

    private static boolean shouldRetireOutOfRange(BedrockTarget.Status status) {
        return status == BedrockTarget.Status.UNINITIALIZED
                || status == BedrockTarget.Status.RETRACTED
                || status == BedrockTarget.Status.FAILED
                || status == BedrockTarget.Status.STUCK
                || status == BedrockTarget.Status.EXTENDED
                || status == BedrockTarget.Status.RETRACTING
                || status == BedrockTarget.Status.NEEDS_WAITING
                || status == BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE
                || status == BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
    }

    private static boolean isStartupSerialPhase() {
        return confirmedSuccessesSinceReset == 0;
    }

    private static boolean isStrictStartupSerialMode() {
        return isStartupSerialPhase() && getConfiguredThroughput() <= 1;
    }

    private static int getConfiguredThroughput() {
        return Math.max(1, Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue());
    }

    private static int getLowCleanupPressureThreshold() {
        return Math.max(8, getConfiguredThroughput());
    }

    private static int getMediumCleanupPressureThreshold() {
        return Math.max(14, getConfiguredThroughput() + 6);
    }

    private static int getHighCleanupPressureThreshold() {
        return Math.max(20, getConfiguredThroughput() * 2);
    }

    private static boolean shouldCountConfirmedSuccess(BedrockTarget target, String reason) {
        if (reason != null || CLIENT.level == null) {
            return false;
        }
        return !BedrockTargetBlocks.isTargetBlock(CLIENT.level.getBlockState(target.getBedrockPos()));
    }

    private static int getCleanupLimitPerTick() {
        int base = Math.max(BASE_CLEANUP_LIMIT_PER_TICK, getConfiguredThroughput() * 5);
        return base + Math.min(BLOCKED_CLEANUP_BONUS_LIMIT, blockedCleanupDemandThisTick);
    }

    private static void cleanupBlockOrQueue(BlockPos pos, boolean predictRemoval) {
        if (pos == null) {
            return;
        }

        addToCleanup(pos, predictRemoval);
        if (CLIENT.level == null) {
            return;
        }
        if (isReservedByActiveTarget(pos)) {
            return;
        }
        var state = CLIENT.level.getBlockState(pos);
        if (BedrockTargetBlocks.isCleanupResidue(state)) {
            if (BedrockBreaker.breakBlock(pos, predictRemoval)) {
                CooldownUtils.INSTANCE.setCooldown(CLIENT.level, CLEANUP_RETRY_COOLDOWN_KEY, pos, getCleanupRetryDelay(state));
            }
        }
    }

    private static void reorderCleanupQueue() {
        if (CLEANUP_QUEUE.size() < 2 || CLIENT.level == null) {
            return;
        }
        List<BlockPos> ordered = new ArrayList<>(CLEANUP_QUEUE);
        ordered.sort((left, right) -> Integer.compare(
                getCleanupPriority(left, CLIENT.level.getBlockState(left)),
                getCleanupPriority(right, CLIENT.level.getBlockState(right))
        ));
        CLEANUP_QUEUE.clear();
        CLEANUP_QUEUE.addAll(ordered);
    }

    private static int getCleanupPriority(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        int priority = BLOCKED_CLEANUP_POSITIONS.contains(pos) ? -10 : 0;
        if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_TORCH)
                || state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WALL_TORCH)) {
            return priority;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.PISTON_HEAD)
                || state.is(net.minecraft.world.level.block.Blocks.PISTON)) {
            return priority + 1;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
            return priority + 2;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK)) {
            return priority + 3;
        }
        return priority + 4;
    }

    private static int getCleanupRetryDelay(net.minecraft.world.level.block.state.BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_TORCH)
                || state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WALL_TORCH)) {
            return 3;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.PISTON_HEAD)
                || state.is(net.minecraft.world.level.block.Blocks.PISTON)) {
            return 4;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
            return 6;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK)) {
            return 8;
        }
        return 6;
    }

    private static void expeditePendingCleanup(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (CLIENT.level == null || pos == null || state == null) {
            return;
        }
        if (!BedrockTargetBlocks.isCleanupResidue(state) || isReservedByActiveTarget(pos)) {
            return;
        }
        if (CooldownUtils.INSTANCE.isOnCooldown(CLIENT.level, CLEANUP_RETRY_COOLDOWN_KEY, pos)) {
            return;
        }
        int retryDelay = getCleanupRetryDelay(state);
        if (BedrockBreaker.breakBlock(pos, false)) {
            CooldownUtils.INSTANCE.setCooldown(CLIENT.level, CLEANUP_RETRY_COOLDOWN_KEY, pos, retryDelay);
        }
    }

    private static int sampleCleanupPressure(ClientLevel level) {
        int pressure = 0;
        for (BlockPos pos : CLEANUP_QUEUE) {
            if (pos == null || isReservedByActiveTarget(pos)) {
                continue;
            }

            var state = level.getBlockState(pos);
            if (state.isAir() || !BedrockTargetBlocks.isCleanupResidue(state)) {
                continue;
            }
            pressure += getCleanupPressureWeight(state);
        }
        return pressure;
    }

    private static int getCleanupPressureWeight(net.minecraft.world.level.block.state.BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)
                || state.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK)) {
            return 2;
        }
        return 1;
    }

    private static boolean isReservedByActiveTarget(BlockPos pos) {
        for (BedrockTarget target : TARGETS) {
            if (target.getReservedPositions().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    static boolean isPositionReservedByOtherTarget(BlockPos pos, BedrockTarget self) {
        for (BedrockTarget target : TARGETS) {
            if (target == self) {
                continue;
            }
            if (target.getReservedPositions().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    static boolean isTorchPlacementReservedByOtherTarget(BedrockTorchPlacement placement, BedrockTarget self) {
        if (placement == null) {
            return false;
        }
        for (BedrockTarget target : TARGETS) {
            if (target == self) {
                continue;
            }
            if (target.matchesTorchPlacement(placement)) {
                continue;
            }
            if (target.getReservedPositions().contains(placement.getSupportPos())
                    || target.getReservedPositions().contains(placement.getTorchPos())) {
                return true;
            }
        }
        return false;
    }

    private static void scheduleNextExecuteWindow() {
        int interval = Math.max(0, Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue());
        if (interval <= 0) {
            return;
        }
        nextExecuteTick = ClientPlayerTickManager.getCurrentHandlerTime() + interval;
    }

    private static boolean isTargetOnRetryCooldown(BlockPos pos) {
        return CLIENT.level != null && CooldownUtils.INSTANCE.isOnCooldown(CLIENT.level, RETRY_COOLDOWN_KEY, pos);
    }

    private static boolean isAcceptBackpressured() {
        return ClientPlayerTickManager.getCurrentHandlerTime() < nextAcceptTick;
    }

    private static void noteSubmitRejected(String reason, BlockPos pos, BlockPos blocker) {
        lastHudReason = reason;
        int rejectWeight = getSubmitRejectWeight(reason);
        if (rejectWeight > 0) {
            rejectedThisTick += rejectWeight;
        }
        boolean heavyCleanupPressure = cleanupPressureThisTick >= getHighCleanupPressureThreshold();
        boolean exhaustedRejectBudget = rejectedThisTick >= getSubmitRejectBudget();
        if (!heavyCleanupPressure && !exhaustedRejectBudget) {
            return;
        }

        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        long candidateNextAcceptTick = now + ACCEPT_BACKPRESSURE_TICKS;
        if (candidateNextAcceptTick <= nextAcceptTick) {
            return;
        }
        nextAcceptTick = candidateNextAcceptTick;
    }

    private static int getSubmitRejectWeight(String reason) {
        if (cleanupPressureThisTick < getMediumCleanupPressureThreshold()) {
            if ("machine_overlap".equals(reason) || "pending_cleanup".equals(reason)) {
                return 0;
            }
        }
        if ("machine_overlap".equals(reason) && cleanupPressureThisTick <= 0) {
            return 0;
        }
        return 1;
    }

    private static int getSubmitRejectBudget() {
        return Math.max(8, getConfiguredThroughput());
    }

    private static int getPendingCleanupRetryTicks(net.minecraft.world.level.block.state.BlockState blockingState) {
        return Math.max(SUBMIT_RETRY_COOLDOWN_TICKS, getCleanupRetryDelay(blockingState) + 2);
    }

    private record CandidateFootprint(Set<BlockPos> structuralPositions, Set<BlockPos> powerReservationPositions) {
        private static CandidateFootprint of(BlockPos bedrockPos, BedrockMachineLayout layout, BedrockTorchPlacement placement) {
            LinkedHashSet<BlockPos> structuralPositions = new LinkedHashSet<>();
            LinkedHashSet<BlockPos> powerReservationPositions = new LinkedHashSet<>();

            structuralPositions.add(bedrockPos);
            structuralPositions.add(layout.getPistonPos());
            structuralPositions.add(layout.getHeadPos());
            if (placement != null) {
                if (placement.getSupportPos() != null) {
                    powerReservationPositions.add(placement.getSupportPos());
                }
                if (placement.getTorchPos() != null) {
                    powerReservationPositions.add(placement.getTorchPos());
                }
            }

            return new CandidateFootprint(structuralPositions, powerReservationPositions);
        }

        private boolean isEmpty() {
            return this.structuralPositions.isEmpty() && this.powerReservationPositions.isEmpty();
        }

        private boolean conflictsWith(BedrockTarget target) {
            Set<BlockPos> targetStructural = target.getStructuralPositions();
            Set<BlockPos> targetPower = target.getPowerReservationPositions();
            for (BlockPos pos : this.structuralPositions) {
                if (targetStructural.contains(pos) || targetPower.contains(pos)) {
                    return true;
                }
            }
            for (BlockPos pos : this.powerReservationPositions) {
                if (targetStructural.contains(pos)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static int getSchedulingProbePenalty(BlockPos pos) {
        if (pos == null || CLIENT.level == null) {
            return 0;
        }

        boolean reservedByActiveTarget = isReservedByActiveTarget(pos);
        int penalty = reservedByActiveTarget ? 60 : 0;

        var state = CLIENT.level.getBlockState(pos);
        if (CLEANUP_QUEUE.contains(pos)) {
            penalty += getSchedulingCleanupPenalty(state);
        } else if (!reservedByActiveTarget && BedrockTargetBlocks.isCleanupResidue(state)) {
            penalty += getSchedulingCleanupPenalty(state);
        }

        return penalty;
    }

    private static int getSchedulingCleanupPenalty(net.minecraft.world.level.block.state.BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
            return 100;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK)) {
            return 80;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.PISTON_HEAD)
                || state.is(net.minecraft.world.level.block.Blocks.PISTON)) {
            return 70;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_TORCH)
                || state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WALL_TORCH)) {
            return 55;
        }
        return 40;
    }

    private static void setRetryCooldown(BlockPos pos, int ticks) {
        if (CLIENT.level != null && pos != null && ticks > 0) {
            CooldownUtils.INSTANCE.setCooldown(CLIENT.level, RETRY_COOLDOWN_KEY, pos, ticks);
        }
    }

    private static AcceptProbe probeCanScanForTargets() {
        if (isStrictStartupSerialMode() && !TARGETS.isEmpty()) {
            return AcceptProbe.reject("startup_serial");
        }
        if (isAcceptBackpressured()) {
            return AcceptProbe.reject("accept_backpressure");
        }
        if (acceptedThisTick >= getSubmitCap()) {
            return AcceptProbe.reject("submit_cap");
        }
        if (canAcceptMoreVerticalTargets()) {
            return AcceptProbe.accept();
        }
        if (isSideEnabled() && !hasSideExclusiveTarget()) {
            return AcceptProbe.accept();
        }
        return AcceptProbe.reject("active_cap");
    }

    private static AcceptProbe probeCanAccept(BlockPos pos, boolean mutateExposureState) {
        BlockPos stablePos = stablePos(pos);
        AcceptProbe scanProbe = probeCanScanForTargets();
        if (!scanProbe.accepted()) {
            return scanProbe;
        }
        if (!isWithinActiveSelection(stablePos)) {
            return AcceptProbe.reject("outside_selection");
        }
        if (isTargetOnRetryCooldown(stablePos)) {
            return AcceptProbe.reject("retry_cooldown");
        }
        boolean horizontalSubmission = isHorizontalSubmission(stablePos);
        if (horizontalSubmission && !isSideEnabled()) {
            return AcceptProbe.reject("side_disabled");
        }
        if (horizontalSubmission && hasSideExclusiveTarget()) {
            return AcceptProbe.reject("side_lane_busy");
        }
        if (!horizontalSubmission && !canAcceptMoreVerticalTargets()) {
            return AcceptProbe.reject("active_cap");
        }
        if (isReservedByActiveTarget(stablePos)) {
            return AcceptProbe.reject("reserved_by_active_target");
        }
        if (!BedrockEnvironment.canInteract(stablePos)) {
            return AcceptProbe.reject("out_of_range_bedrock");
        }
        if (hasExposureBypass(stablePos)) {
            if (mutateExposureState) {
                consumeExposureBypass(stablePos);
            }
            return AcceptProbe.accept();
        }
        if (CLIENT.level != null && BedrockMachineLayout.shouldDeferUntilExposed(CLIENT.level, stablePos)) {
            int defers = EXPOSURE_DEFERRALS.getOrDefault(stablePos, 0) + 1;
<<<<<<< HEAD
            if (defers <= MAX_VERTICAL_EXPOSURE_DEFERS) {
=======
            if (defers < MAX_VERTICAL_EXPOSURE_DEFERS) {
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
                if (mutateExposureState) {
                    EXPOSURE_DEFERRALS.put(stablePos, defers);
                }
                return AcceptProbe.reject("await_target_exposure");
            }
            if (mutateExposureState) {
                EXPOSURE_DEFERRALS.remove(stablePos);
                EXPOSURE_BYPASS_USES.put(stablePos, 1);
            }
        } else {
            if (mutateExposureState) {
                EXPOSURE_DEFERRALS.remove(stablePos);
                EXPOSURE_BYPASS_USES.remove(stablePos);
            }
        }

        for (BedrockTarget target : TARGETS) {
            if (target.getBedrockPos().equals(stablePos)) {
                return AcceptProbe.reject("duplicate_active_target");
            }
            if (target.getPistonPos().equals(stablePos)) {
                return AcceptProbe.reject("occupied_by_active_piston");
            }
        }
        return AcceptProbe.accept();
    }

    private static BlockPos stablePos(BlockPos pos) {
        return pos == null ? null : pos.immutable();
    }

    private static boolean isHorizontalSubmission(BlockPos pos) {
        SubmissionPlan plan = SUBMISSION_PLANS.get(pos);
        if (plan != null && plan.layout() != null) {
            return plan.layout().isHorizontal();
        }
        if (CLIENT.level == null || pos == null) {
            return false;
        }
        BedrockMachineLayout layout = BedrockMachineLayout.find(CLIENT.level, pos);
        return layout != null && layout.isHorizontal();
    }

    public static void clearSubmissionPlans() {
        SUBMISSION_PLANS.clear();
    }

    public static void primeSubmissionPlan(BlockPos bedrockPos, BedrockMachineLayout layout, BedrockTorchPlacement placement, BlockPos slimePos) {
        BlockPos stablePos = stablePos(bedrockPos);
        if (stablePos == null || layout == null) {
            return;
        }
        SUBMISSION_PLANS.put(stablePos, new SubmissionPlan(
                layout,
                placement,
                slimePos,
                ClientPlayerTickManager.getCurrentHandlerTime()
        ));
    }

    private static boolean hasExposureBypass(BlockPos pos) {
        return pos != null && EXPOSURE_BYPASS_USES.getOrDefault(pos, 0) > 0;
    }

    private static void consumeExposureBypass(BlockPos pos) {
        if (pos == null) {
            return;
        }
        int uses = EXPOSURE_BYPASS_USES.getOrDefault(pos, 0);
        if (uses <= 1) {
            EXPOSURE_BYPASS_USES.remove(pos);
            return;
        }
        EXPOSURE_BYPASS_USES.put(pos, uses - 1);
    }

    private static boolean isWithinActiveSelection(BlockPos pos) {
        return pos != null && LitematicaUtils.isWithinSelection1ModeRange(pos);
    }

    private static void purgeTargetsOutsideSelection() {
        if (TARGETS.isEmpty()) {
            return;
        }

        Iterator<BedrockTarget> iterator = TARGETS.iterator();
        while (iterator.hasNext()) {
            BedrockTarget target = iterator.next();
            if (target == null || isWithinActiveSelection(target.getBedrockPos())) {
                continue;
            }
            iterator.remove();
            for (BlockPos tempPos : target.getCleanupPositions()) {
                cleanupBlockOrQueue(tempPos, false);
            }
        }
    }

    private record AcceptProbe(boolean accepted, String reason) {
        private static AcceptProbe accept() {
            return new AcceptProbe(true, "accepted");
        }

        private static AcceptProbe reject(String reason) {
            return new AcceptProbe(false, reason);
        }
    }

    private static SubmissionPlan consumeSubmissionPlan(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        SubmissionPlan plan = SUBMISSION_PLANS.remove(pos);
        if (plan == null) {
            return null;
        }
        long age = ClientPlayerTickManager.getCurrentHandlerTime() - plan.plannedAtTick();
        return age <= 1L ? plan : null;
    }

    private record SubmissionPlan(
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement,
            BlockPos slimePos,
            long plannedAtTick
    ) {
    }

    public static HudSnapshot getHudSnapshot() {
        int totalFailures = failedTargetsSinceReset + stuckTargetsSinceReset;
        int resolved = confirmedSuccessesSinceReset + totalFailures;
        double successRate = resolved > 0 ? (double) confirmedSuccessesSinceReset / (double) resolved : 0.0D;
        return new HudSnapshot(
                TARGETS.size(),
                countActiveTargets(),
                countVerticalActiveTargets(),
                countSideTargets(),
                CLEANUP_QUEUE.size(),
                cleanupPressureThisTick,
                blockedCleanupDemandThisTick,
                confirmedSuccessesSinceReset,
                submittedTargetsSinceReset,
                failedTargetsSinceReset,
                stuckTargetsSinceReset,
                acceptedThisTick,
                rejectedThisTick,
                getConfiguredThroughput(),
                getSubmitCap(),
                getActiveTargetCap(),
                getVerticalActiveTargetCap(),
                getSideTargetCap(),
                successRate,
                lastHudReason
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
            String lastReason
    ) {
    }
}
