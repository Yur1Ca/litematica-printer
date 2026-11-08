package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.scan.ScanAvailability;
import me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class BedrockCandidatePlanner {
    private static final int REJECTED_PLAN_RETRY_TICKS = 20;
    private final ScanEngine scanEngine;
    private final LitematicaAdapter litematica;
    private final BedrockCandidateBacklog<BedrockCandidatePlan> candidateBacklog = new BedrockCandidateBacklog<>();
    /** Avoid rebuilding the same unavailable piston layout on every scan pass. */
    private final Long2ObjectOpenHashMap<RejectedPlan> rejectedPlans = new Long2ObjectOpenHashMap<>();
    private final Map<BlockPos, Long> retryUntil = new HashMap<>();
    private boolean sourceHasMore;
    private long plannerTick;
    private Vec3 sourceEyePosition;
    private int sourceWorkRange;
    private Object sourceRangeShape;
    private boolean sourceInteractionCheck;
    private double sourceInteractionRange;
    private boolean sourceSideEnabled;

    public BedrockCandidatePlanner(ScanEngine scanEngine, LitematicaAdapter litematica) {
        this.scanEngine = scanEngine;
        this.litematica = litematica;
    }

    /** Reopen only a completed bedrock source whose live eligibility or rejection deadline changed. */
    public boolean refreshEligibility(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null) return false;
        if (this.sourceEyePosition == null) {
            this.captureEligibility(player);
            return false;
        }
        // Finish a cooperatively paused pass before replacing its cursor. Otherwise ordinary
        // walking repeatedly rescans its prefix and starves positions at the edge of the range.
        if (this.sourceHasMore) return false;
        boolean contextChanged = this.eligibilityChanged(player);
        long now = level.getGameTime();
        long revision = this.scanEngine.dirtyVersion();
        boolean retryDue = this.rejectedPlans.values().stream().anyMatch(
                rejected -> rejected.revision() != revision || rejected.retryTick() <= now);
        if (!contextChanged && !retryDue) return false;
        if (contextChanged) this.rejectedPlans.clear();
        else this.rejectedPlans.values().removeIf(
                rejected -> rejected.revision() != revision || rejected.retryTick() <= now);
        this.captureEligibility(player);
        // A deadline must produce another source, not merely keep the outer coordinator busy.
        // Owner reset preserves all other features' sessions and the world's dirty revision.
        this.scanEngine.resetOwner("bedrock");
        this.sourceHasMore = true;
        return true;
    }

    private boolean eligibilityChanged(LocalPlayer player) {
        return !this.sourceEyePosition.equals(player.getEyePosition())
                || this.sourceWorkRange != Configs.Core.WORK_RANGE.getIntegerValue()
                || this.sourceRangeShape != Configs.Core.ITERATOR_SHAPE.getOptionListValue()
                || this.sourceInteractionCheck != Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue()
                || Double.compare(this.sourceInteractionRange, this.currentInteractionRange()) != 0
                || this.sourceSideEnabled != Configs.Bedrock.BEDROCK_ALLOW_SIDE.getBooleanValue();
    }

    private void captureEligibility(LocalPlayer player) {
        this.sourceEyePosition = player.getEyePosition();
        this.sourceWorkRange = Configs.Core.WORK_RANGE.getIntegerValue();
        this.sourceRangeShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        this.sourceInteractionCheck = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue();
        this.sourceInteractionRange = this.currentInteractionRange();
        this.sourceSideEnabled = Configs.Bedrock.BEDROCK_ALLOW_SIDE.getBooleanValue();
    }

    private double currentInteractionRange() {
        return Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue()
                ? PlayerUtils.getPlayerBlockInteractionRange() : 0.0D;
    }

    public Iterable<BlockPos> iterable(PrinterBox sourceBox, ClientLevel level, LocalPlayer player, int maxEffectiveExecutions, int scanGuardLimit) {
        this.plannerTick++;
        this.candidateBacklog.setCapacity(Math.max(maxEffectiveExecutions * 2, 16));
        this.pruneCandidateBacklog(sourceBox, level);
        int selectionLimit = this.getCandidateSelectionLimit(maxEffectiveExecutions);
        List<BedrockCandidatePlan> candidates = this.getEligibleCandidates();
        boolean backlogNeedsScanAdvance = this.candidateBacklog.remainingCapacity() == 0
                && this.sourceHasMore;
        if ((candidates.size() < selectionLimit || backlogNeedsScanAdvance)
                && (backlogNeedsScanAdvance || BedrockController.canScanForTargets())) {
            CandidateShard shard = this.collectCandidateShard(
                    sourceBox,
                    level,
                    player,
                    scanGuardLimit
            );
            this.sourceHasMore = shard.hasMoreSource();
            for (BedrockCandidatePlan candidate : shard.candidates()) {
                this.candidateBacklog.offer(candidate.pos(), candidate);
            }
            candidates = this.getEligibleCandidates();
        }

        List<BedrockCandidatePlan> selectedCandidates;
        if (candidates.size() <= 1) {
            selectedCandidates = candidates;
        } else {
            candidates.sort(Comparator
                    .comparingInt(BedrockCandidatePlan::priority)
                    .thenComparingInt(BedrockCandidatePlan::neighborTargetCount));

            selectedCandidates = candidates;
        }

        selectionLimit = Math.min(selectedCandidates.size(), selectionLimit);
        List<BedrockCandidatePlan> liveSelection = new ArrayList<>(selectionLimit);
        BedrockCandidateConflictIndex conflicts = new BedrockCandidateConflictIndex();
        for (BedrockCandidatePlan cachedCandidate : selectedCandidates) {
            if (liveSelection.size() >= selectionLimit) {
                break;
            }
            BedrockCandidatePlan candidate = this.refreshModeledCandidate(
                    level,
                    cachedCandidate,
                    Configs.Bedrock.BEDROCK_ALLOW_SIDE.getBooleanValue()
            );
            if (candidate == null) {
                this.candidateBacklog.remove(cachedCandidate.pos());
                continue;
            }
            this.candidateBacklog.offer(candidate.pos(), candidate);
            if (!conflicts.tryReserve(candidate)) {
                continue;
            }
            liveSelection.add(candidate);
        }

        List<BlockPos> filtered = new ArrayList<>(liveSelection.size() + 1);
        for (BedrockCandidatePlan candidate : liveSelection) {
            BedrockController.primeSubmissionPlan(candidate.pos(), candidate.layout(), candidate.placement(), candidate.slimePos());
            filtered.add(candidate.pos());
        }
        return filtered;
    }

    public void recordSubmissionResult(BlockPos pos, boolean submitted) {
        if (submitted) {
            this.candidateBacklog.remove(pos);
            this.retryUntil.remove(pos);
        } else if (pos != null) {
            this.retryUntil.put(pos.immutable(), this.plannerTick + 1L);
        }
    }

    public void discard(BlockPos pos) {
        this.candidateBacklog.remove(pos);
        this.retryUntil.remove(pos);
    }

    public boolean hasPendingCandidates() {
        return !this.candidateBacklog.isEmpty();
    }

    public boolean hasPendingScanSource() {
        return this.sourceHasMore;
    }

    public int getPendingCandidateCount() {
        return this.candidateBacklog.size();
    }

    public void reset() {
        this.candidateBacklog.clear();
        this.rejectedPlans.clear();
        this.retryUntil.clear();
        this.sourceHasMore = false;
        this.plannerTick = 0L;
        this.sourceEyePosition = null;
    }

    private List<BedrockCandidatePlan> getEligibleCandidates() {
        List<BedrockCandidatePlan> verticalCandidates = new ArrayList<>();
        List<BedrockCandidatePlan> sideCandidates = new ArrayList<>();
        for (BedrockCandidatePlan candidate : this.candidateBacklog.snapshot()) {
            if (BedrockController.isPositionOnRetryCooldown(candidate.pos())) {
                continue;
            }
            if (this.retryUntil.getOrDefault(candidate.pos(), Long.MIN_VALUE) > this.plannerTick) {
                continue;
            }
            if (candidate.layout().isHorizontal()) {
                sideCandidates.add(candidate);
            } else {
                verticalCandidates.add(candidate);
            }
        }
        return verticalCandidates.isEmpty() ? sideCandidates : verticalCandidates;
    }

    private void pruneCandidateBacklog(PrinterBox sourceBox, ClientLevel level) {
        this.candidateBacklog.removeIf((pos, ignored) -> !sourceBox.contains(pos)
                || !this.litematica.isWithinSelectionRange(pos)
                || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos)));
        this.rejectedPlans.keySet().removeIf(key -> {
            BlockPos pos = BlockPos.of(key);
            return !sourceBox.contains(pos)
                    || !this.litematica.isWithinSelectionRange(pos);
        });
        this.retryUntil.keySet().removeIf(pos -> !this.candidateBacklog.contains(pos));
    }

    private CandidateShard collectCandidateShard(
            PrinterBox sourceBox,
            ClientLevel level,
            LocalPlayer player,
            int scanGuardLimit
    ) {
        int scanLimit = this.getCandidateScanLimit(scanGuardLimit);
        Iterable<BlockPos> source = this.scanEngine.iterable(
                "bedrock",
                List.of(sourceBox),
                level,
                null,
                player,
                scanLimit,
                ScanIntent.BEDROCK,
                pos -> true,
                pos -> this.passesCheapFilters(level, pos)
                        && !this.candidateBacklog.contains(pos)
                        && !this.isRejectedAtCurrentRevision(pos, level.getGameTime())
        );
        Iterator<BlockPos> iterator = source.iterator();
        List<BedrockCandidatePlan> verticalCandidates = new ArrayList<>();
        List<BedrockCandidatePlan> sideCandidates = new ArrayList<>();
        boolean allowSide = Configs.Bedrock.BEDROCK_ALLOW_SIDE.getBooleanValue();
        int scanned = 0;
        int modeled = 0;
        boolean hasMoreSource = false;
        long modelingStart = System.nanoTime();
        long modelingBudgetNanos = Math.max(1L, Configs.Core.SCAN_TIME_BUDGET_MS.getIntegerValue()) * 1_000_000L;

        while (scanned < scanLimit) {
            // Always model at least one candidate so a very small budget cannot
            // deadlock progress. After that, yield cooperatively to the next tick.
            if (modeled > 0 && System.nanoTime() - modelingStart >= modelingBudgetNanos) {
                hasMoreSource = true;
                break;
            }
            if (!iterator.hasNext()) {
                if (source instanceof ScanCandidateIterable scanSource
                        && scanSource.availability() == ScanAvailability.PAUSED) {
                    hasMoreSource = true;
                }
                break;
            }
            BlockPos pos = iterator.next();
            scanned++;
            if (this.candidateBacklog.remainingCapacity() == 0) {
                continue;
            }
            modeled++;
            BedrockCandidatePlan candidate = this.buildModeledCandidate(level, pos, allowSide);
            if (candidate != null) {
                if (candidate.layout() != null && candidate.layout().isHorizontal()) {
                    sideCandidates.add(candidate);
                } else {
                    verticalCandidates.add(candidate);
                }
            } else {
                this.rejectedPlans.put(pos.asLong(), new RejectedPlan(this.scanEngine.dirtyVersion(), level.getGameTime() + REJECTED_PLAN_RETRY_TICKS));
            }
        }

        if (scanned >= scanLimit) {
            hasMoreSource = true;
        }
        List<BedrockCandidatePlan> candidates = new ArrayList<>(verticalCandidates.size() + sideCandidates.size());
        candidates.addAll(verticalCandidates);
        candidates.addAll(sideCandidates);
        return new CandidateShard(candidates, hasMoreSource);
    }

    /**
     * 廉价过滤:仅做范围/目标方块/冷却判断,不触碰 layout 与火把探测等重型逻辑。不计入建模预算。
     */
    private boolean passesCheapFilters(ClientLevel level, BlockPos pos) {
        if (pos == null || !BedrockEnvironment.canInteract(pos)) {
            return false;
        }
        if (!this.litematica.isWithinSelectionRange(pos)) {
            return false;
        }
        if (!BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos))) {
            return false;
        }
        // Cooldown is an admission concern. Keeping the target in the backlog lets it become
        // eligible as soon as the deadline expires without requiring movement or another scan.
        return true;
    }

    /**
     * 重型建模阶段:调用方需保证已通过 {@link #passesCheapFilters}。计入建模预算。
     */
    private BedrockCandidatePlan buildModeledCandidate(ClientLevel level, BlockPos pos, boolean allowSide) {
        BedrockCandidatePlan candidate = BedrockCandidatePlan.build(level, pos.immutable(), this.scanEngine::dirtyVersion);
        if (candidate.layout() == null) {
            return null;
        }
        if (candidate.layout().isHorizontal() && !allowSide) {
            return null;
        }
        return candidate;
    }

    private BedrockCandidatePlan refreshModeledCandidate(
            ClientLevel level,
            BedrockCandidatePlan cached,
            boolean allowSide
    ) {
        if ((cached.layout().isHorizontal() && !allowSide)
                || !BedrockEnvironment.arePositionsInteractable(cached.structuralPositions())
                || !BedrockEnvironment.arePositionsInteractable(cached.powerReservationPositions())) {
            return this.buildModeledCandidate(level, cached.pos(), allowSide);
        }
        var dirty = this.scanEngine.dirtySnapshotAfter(cached.planRevision(), cached.footprint());
        if (dirty.boxes().isEmpty()) {
            return cached.planRevision() == dirty.version()
                    ? cached
                    : cached.withPlanRevision(dirty.version());
        }
        return this.buildModeledCandidate(level, cached.pos(), allowSide);
    }

    private int getCandidateSelectionLimit(int maxEffectiveExecutions) {
        return Math.max(1, maxEffectiveExecutions);
    }

    private boolean isRejectedAtCurrentRevision(BlockPos pos, long gameTick) {
        var rejected = this.rejectedPlans.get(pos.asLong());
        // Movement and range settings do not dirty the world; range-dependent failures must expire.
        if (rejected != null && rejected.revision() == this.scanEngine.dirtyVersion()
                && gameTick < rejected.retryTick()) return true;
        this.rejectedPlans.remove(pos.asLong());
        return false;
    }

    private int getCandidateScanLimit(int scanGuardLimit) {
        // The scan session already yields cooperatively on the configured time budget and keeps
        // its cursor between ticks. A second fixed spatial slice would stop scanning around
        // the same part of a large selection until movement rebuilt the cursor.
        return scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
    }

    private record CandidateShard(List<BedrockCandidatePlan> candidates, boolean hasMoreSource) {
    }

    private record RejectedPlan(long revision, long retryTick) { }

}
