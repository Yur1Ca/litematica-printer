package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.handler.scan.ScanWorkSlice;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.scan.ScanAvailability;
import me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public final class BedrockCandidatePlanner {
    private static final int REJECTED_PLAN_RETRY_TICKS = 20;
    private static final int MAX_REJECTED_PLANS = 1024;
    private static final int REJECTED_PRUNE_PER_TICK = 64;
    private final ScanEngine scanEngine;
    private final LitematicaAdapter litematica;
    private final BedrockCandidateBacklog<BedrockCandidatePlan> candidateBacklog = new BedrockCandidateBacklog<>();
    /** Avoid rebuilding the same unavailable piston layout on every scan pass. */
    private final Long2ObjectLinkedOpenHashMap<RejectedPlan> rejectedPlans = new Long2ObjectLinkedOpenHashMap<>();
    private long lastRejectedPruneTick = Long.MIN_VALUE;
    private boolean rejectionRetryDue;
    private PrinterBox sourceScope;
    private final Map<BlockPos, Long> retryUntil = new HashMap<>();
    private boolean sourceHasMore;
    private long deliveryGeneration;
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
        this.pruneRejectedPlans(level.getGameTime());
        if (this.sourceEyePosition == null) {
            this.captureEligibility(player);
            return false;
        }
        // Finish a cooperatively paused pass before replacing its cursor. Otherwise ordinary
        // walking repeatedly rescans its prefix and starves positions at the edge of the range.
        if (this.sourceHasMore) return false;
        boolean contextChanged = this.eligibilityChanged(player);
        boolean retryDue = this.rejectionRetryDue;
        if (!contextChanged && !retryDue) return false;
        if (contextChanged) this.rejectedPlans.clear();
        this.rejectionRetryDue = false;
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
        long generation = ++this.deliveryGeneration;
        this.plannerTick++;
        this.sourceScope = sourceBox;
        this.candidateBacklog.setCapacity(Math.max(maxEffectiveExecutions * 2, 16));
        this.pruneCandidateBacklog(sourceBox, level);
        int selectionLimit = this.getCandidateSelectionLimit(maxEffectiveExecutions);
        List<BedrockCandidatePlan> candidates = this.getEligibleCandidates();
        ScanCandidateIterable discoveryGuard = null;
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
            discoveryGuard = shard.sourceGuard();
            for (BedrockCandidatePlan candidate : shard.candidates()) {
                this.candidateBacklog.offer(candidate.pos(), candidate);
            }
            candidates = this.getEligibleCandidates();
        }

        // Refresh the bounded backlog before admission: dirty geometry and active reservations
        // must not reserve a local conflict slot ahead of an independent eligible plan.
        List<BedrockCandidatePlan> liveCandidates = new ArrayList<>(candidates.size());
        for (BedrockCandidatePlan cachedCandidate : candidates) {
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
            liveCandidates.add(candidate);
        }
        liveCandidates.sort(java.util.Comparator.comparing(BedrockCandidatePlan::pos,
                this.scanEngine.targetComparator("bedrock")));

        selectionLimit = Math.min(liveCandidates.size(), selectionLimit);
        List<BedrockCandidatePlan> liveSelection = new ArrayList<>(selectionLimit);
        BedrockCandidateConflictIndex conflicts = new BedrockCandidateConflictIndex();
        for (BedrockCandidatePlan candidate : liveCandidates) {
            if (liveSelection.size() >= selectionLimit) {
                break;
            }
            // Admission probes mutate exposure/cooldown state, so run them once in stable
            // order, outside the comparator and before reserving this pass's local footprint.
            BedrockController.primeSubmissionPlan(candidate.pos(), candidate.layout(), candidate.placement(), candidate.slimePos());
            if (!BedrockController.canAccept(candidate.pos()) || this.hasActiveConflict(candidate)) {
                continue;
            }
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
        ScanCandidateIterable guard = discoveryGuard;
        boolean discoveryPending = this.sourceHasMore;
        return new ScanCandidateIterable() {
            private int remaining = filtered.size();

            @Override public boolean isValid() {
                return generation == deliveryGeneration && (guard == null || guard.isValid());
            }

            @Override public boolean isBuffered() { return true; }

            @Override public ScanAvailability availability() {
                if (!this.isValid()) return ScanAvailability.COMPLETE;
                if (this.remaining > 0) return ScanAvailability.READY;
                return discoveryPending ? ScanAvailability.PAUSED : ScanAvailability.COMPLETE;
            }

            @Override public Iterator<BlockPos> iterator() {
                this.remaining = filtered.size();
                Iterator<BlockPos> ready = filtered.iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() { return isValid() && ready.hasNext(); }
                    @Override public BlockPos next() {
                        if (!this.hasNext()) throw new java.util.NoSuchElementException("bedrock candidate batch was exhausted or replaced");
                        remaining--;
                        return ready.next();
                    }
                };
            }
        };
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
        this.deliveryGeneration++;
        this.candidateBacklog.clear();
        this.rejectedPlans.clear();
        this.lastRejectedPruneTick = Long.MIN_VALUE;
        this.rejectionRetryDue = false;
        this.sourceScope = null;
        this.retryUntil.clear();
        this.sourceHasMore = false;
        this.plannerTick = 0L;
        this.sourceEyePosition = null;
    }

    private boolean hasActiveConflict(BedrockCandidatePlan candidate) {
        for (BlockPos pos : candidate.structuralPositions()) {
            if (BedrockController.isPositionReservedByOtherTarget(pos, null)) return true;
        }
        return candidate.placement() != null
                && BedrockController.isTorchPlacementReservedByOtherTarget(candidate.placement(), null);
    }

    private List<BedrockCandidatePlan> getEligibleCandidates() {
        List<BedrockCandidatePlan> candidates = new ArrayList<>();
        for (BedrockCandidatePlan candidate : this.candidateBacklog.snapshot()) {
            if (BedrockController.isPositionOnRetryCooldown(candidate.pos())) continue;
            if (this.retryUntil.getOrDefault(candidate.pos(), Long.MIN_VALUE) > this.plannerTick) continue;
            candidates.add(candidate);
        }
        return candidates;
    }

    private void pruneCandidateBacklog(PrinterBox sourceBox, ClientLevel level) {
        this.candidateBacklog.removeIf((pos, ignored) -> !sourceBox.contains(pos)
                || !this.litematica.isWithinSelectionRange(pos)
                || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos)));
        this.pruneRejectedPlans(level.getGameTime());
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
                this.selectedSourceBoxes(sourceBox),
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
        List<BedrockCandidatePlan> candidates = new ArrayList<>();
        boolean allowSide = Configs.Bedrock.BEDROCK_ALLOW_SIDE.getBooleanValue();
        int scanned = 0;
        int modeled = 0;
        boolean hasMoreSource = false;
        long modelingStart = System.nanoTime();
        long modelingBudgetNanos = ScanWorkSlice.configuredTimeLimitNanos();

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
                candidates.add(candidate);
            } else {
                this.rejectedPlans.putAndMoveToLast(pos.asLong(),
                        new RejectedPlan(this.scanEngine.dirtyVersion(), level.getGameTime() + REJECTED_PLAN_RETRY_TICKS));
                if (this.rejectedPlans.size() > MAX_REJECTED_PLANS) this.rejectedPlans.removeFirst();
            }
        }

        if (scanned >= scanLimit) {
            hasMoreSource = true;
        }
        return new CandidateShard(candidates, hasMoreSource,
                source instanceof ScanCandidateIterable scanSource ? scanSource : null);
    }

    /** Clip discovery to the same selection checked live below, before traversing its volume. */
    private List<PrinterBox> selectedSourceBoxes(PrinterBox interactionBox) {
        List<PrinterBox> selected = new ArrayList<>();
        for (PrinterBox box : this.litematica.createSelectionBoxes()) {
            int minX = Math.max(box.minX, interactionBox.minX);
            int minY = Math.max(box.minY, interactionBox.minY);
            int minZ = Math.max(box.minZ, interactionBox.minZ);
            int maxX = Math.min(box.maxX, interactionBox.maxX);
            int maxY = Math.min(box.maxY, interactionBox.maxY);
            int maxZ = Math.min(box.maxZ, interactionBox.maxZ);
            if (minX <= maxX && minY <= maxY && minZ <= maxZ) {
                selected.add(new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ));
            }
        }
        return selected;
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
            return cached.refreshSchedulingPriority(level, dirty.version());
        }
        return this.buildModeledCandidate(level, cached.pos(), allowSide);
    }

    private int getCandidateSelectionLimit(int maxEffectiveExecutions) {
        return Math.max(1, maxEffectiveExecutions);
    }

    private boolean isRejectedAtCurrentRevision(BlockPos pos, long gameTick) {
        var rejected = this.rejectedPlans.getAndMoveToLast(pos.asLong());
        // Movement and range settings do not dirty the world; range-dependent failures must expire.
        if (rejected != null && rejected.revision() == this.scanEngine.dirtyVersion()
                && gameTick < rejected.retryTick()) return true;
        this.rejectedPlans.remove(pos.asLong());
        return false;
    }

    private void pruneRejectedPlans(long gameTick) {
        if (this.lastRejectedPruneTick == gameTick) return;
        this.lastRejectedPruneTick = gameTick;
        long revision = this.scanEngine.dirtyVersion();
        int remaining = Math.min(REJECTED_PRUNE_PER_TICK, this.rejectedPlans.size());
        while (remaining-- > 0 && !this.rejectedPlans.isEmpty()) {
            long key = this.rejectedPlans.firstLongKey();
            RejectedPlan rejected = this.rejectedPlans.get(key);
            BlockPos pos = BlockPos.of(key);
            boolean due = rejected.revision() != revision || rejected.retryTick() <= gameTick;
            if (due || this.sourceScope != null && !this.sourceScope.contains(pos)
                    || !this.litematica.isWithinSelectionRange(pos)) {
                this.rejectedPlans.removeFirst();
                this.rejectionRetryDue |= due;
            } else {
                // Rotate the finite memo so a valid prefix cannot hide expired later entries.
                this.rejectedPlans.getAndMoveToLast(key);
            }
        }
    }

    private int getCandidateScanLimit(int scanGuardLimit) {
        // The scan session already yields cooperatively on the configured time budget and keeps
        // its cursor between ticks. A second fixed spatial slice would stop scanning around
        // the same part of a large selection until movement rebuilt the cursor.
        return scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
    }

    private record CandidateShard(List<BedrockCandidatePlan> candidates, boolean hasMoreSource,
                                  ScanCandidateIterable sourceGuard) {
    }

    private record RejectedPlan(long revision, long retryTick) { }

}
