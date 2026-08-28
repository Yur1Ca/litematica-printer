package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.handler.scan.ScanAvailability;
import me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public final class BedrockCandidatePlanner {
    private static final Direction[] NEIGHBOR_DIRECTIONS = Direction.values();
    private final ScanEngine scanEngine;
    private final LitematicaAdapter litematica;
    private final BedrockCandidateBacklog<BedrockCandidatePlan> candidateBacklog = new BedrockCandidateBacklog<>();
    /** Avoid rebuilding the same unavailable piston layout on every scan pass. */
    private final Long2LongOpenHashMap rejectedPlanRevisions = new Long2LongOpenHashMap();
    private boolean sourceHasMore;

    public BedrockCandidatePlanner(ScanEngine scanEngine, LitematicaAdapter litematica) {
        this.scanEngine = scanEngine;
        this.litematica = litematica;
    }

    public Iterable<BlockPos> iterable(PrinterBox sourceBox, ClientLevel level, LocalPlayer player, int maxEffectiveExecutions, int scanGuardLimit) {
        this.pruneCandidateBacklog(sourceBox, level);
        int selectionLimit = this.getCandidateSelectionLimit(maxEffectiveExecutions);
        List<BedrockCandidatePlan> candidates = this.getEligibleCandidates();
        if (candidates.size() < selectionLimit && BedrockController.canScanForTargets()) {
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
        }
    }

    public void discard(BlockPos pos) {
        this.candidateBacklog.remove(pos);
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
        this.rejectedPlanRevisions.clear();
        this.sourceHasMore = false;
    }

    private List<BedrockCandidatePlan> getEligibleCandidates() {
        List<BedrockCandidatePlan> verticalCandidates = new ArrayList<>();
        List<BedrockCandidatePlan> sideCandidates = new ArrayList<>();
        for (BedrockCandidatePlan candidate : this.candidateBacklog.snapshot()) {
            if (BedrockController.isPositionOnRetryCooldown(candidate.pos())) {
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
        this.rejectedPlanRevisions.keySet().removeIf(key -> {
            BlockPos pos = BlockPos.of(key);
            return !sourceBox.contains(pos)
                    || !this.litematica.isWithinSelectionRange(pos);
        });
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
                        && !this.isRejectedAtCurrentRevision(pos)
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
            modeled++;
            BedrockCandidatePlan candidate = this.buildModeledCandidate(level, pos, allowSide);
            if (candidate != null) {
                if (candidate.layout() != null && candidate.layout().isHorizontal()) {
                    sideCandidates.add(candidate);
                } else {
                    verticalCandidates.add(candidate);
                }
            } else {
                this.rejectedPlanRevisions.put(pos.asLong(), this.scanEngine.dirtyVersion());
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
        BedrockCandidatePlan candidate = buildCandidate(level, pos.immutable());
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

    private boolean isRejectedAtCurrentRevision(BlockPos pos) {
        return this.rejectedPlanRevisions.getOrDefault(pos.asLong(), Long.MIN_VALUE)
                == this.scanEngine.dirtyVersion();
    }

    private int getCandidateScanLimit(int scanGuardLimit) {
        // The scan session already yields cooperatively on the configured time budget and keeps
        // its cursor between ticks. A second fixed spatial slice would stop scanning around
        // the same part of a large selection until movement rebuilt the cursor.
        return scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
    }

    private BedrockCandidatePlan buildCandidate(ClientLevel level, BlockPos pos) {
        BedrockMachineLayout layout = BedrockMachineLayout.find(level, pos);
        PlacementSelection placementSelection = layout == null ? null : findPlacementSelection(level, layout, pos);
        BedrockTorchPlacement placement = placementSelection == null ? null : placementSelection.placement();
        BlockPos slimePos = placementSelection == null ? null : placementSelection.slimePos();
        int priority = candidatePriority(level, pos, layout, placement);
        int neighborTargetCount = neighborTargetCount(level, pos);
        return new BedrockCandidatePlan(
                pos,
                layout,
                placement,
                slimePos,
                buildStructuralPositions(pos, layout),
                buildPowerReservationPositions(placement),
                priority,
                neighborTargetCount,
                footprint(pos),
                this.scanEngine.dirtyVersion()
        );
    }

    private static PrinterBox footprint(BlockPos pos) {
        // Layout search reaches at most three blocks from the bedrock target. One extra block
        // covers neighbor-dependent support and torch-face changes at the boundary.
        return new PrinterBox(
                pos.getX() - 4,
                pos.getY() - 4,
                pos.getZ() - 4,
                pos.getX() + 4,
                pos.getY() + 4,
                pos.getZ() + 4
        );
    }

    private int candidatePriority(ClientLevel level, BlockPos pos, BedrockMachineLayout layout, BedrockTorchPlacement placement) {
        int controllerPenalty = BedrockController.getSchedulingPenalty(pos);
        if (layout != null) {
            int penalty = controllerPenalty;
            penalty += BedrockController.getSchedulingPenalty(layout.getPistonPos());
            penalty += BedrockController.getSchedulingPenalty(layout.getHeadPos());
            if (placement != null) {
                penalty += BedrockController.getSchedulingPenalty(placement.getSupportPos());
                penalty += BedrockController.getSchedulingPenalty(placement.getTorchPos());
                if (level.getBlockState(placement.getSupportPos()).is(Blocks.SLIME_BLOCK)) {
                    penalty += 200;
                }
            }
            penalty += BedrockController.getPredictedMachineOverlapPenalty(pos, layout, placement);
            return penalty;
        }
        if (BedrockMachineLayout.shouldDeferUntilExposed(level, pos)) {
            return controllerPenalty + 1_000;
        }
        return controllerPenalty + 10_000;
    }

    private static List<BlockPos> buildStructuralPositions(BlockPos bedrockPos, BedrockMachineLayout layout) {
        List<BlockPos> positions = new ArrayList<>(3);
        positions.add(bedrockPos);
        if (layout != null) {
            positions.add(layout.getPistonPos());
            positions.add(layout.getHeadPos());
        }
        return positions;
    }

    private static List<BlockPos> buildPowerReservationPositions(BedrockTorchPlacement placement) {
        if (placement == null) {
            return List.of();
        }
        List<BlockPos> positions = new ArrayList<>(2);
        if (placement.getSupportPos() != null) {
            positions.add(placement.getSupportPos());
        }
        if (placement.getTorchPos() != null) {
            positions.add(placement.getTorchPos());
        }
        return positions;
    }

    private static PlacementSelection findPlacementSelection(ClientLevel level, BedrockMachineLayout layout, BlockPos bedrockPos) {
        BedrockTorchPlacement placement = BedrockEnvironment.findTorchPlacement(
                level,
                layout.getPistonPos(),
                layout.getPistonOffset().getOpposite(),
                bedrockPos,
                layout.getPistonPos(),
                layout.getHeadPos()
        );
        if (placement != null) {
            return new PlacementSelection(placement, level.getBlockState(placement.getSupportPos()).is(Blocks.SLIME_BLOCK)
                    ? placement.getSupportPos()
                    : null);
        }
        placement = BedrockEnvironment.findPossibleSlimeTorchPlacement(
                level,
                layout.getPistonPos(),
                layout.getPistonOffset().getOpposite(),
                bedrockPos,
                layout.getPistonPos(),
                layout.getHeadPos()
        );
        return placement == null ? null : new PlacementSelection(placement, placement.getSupportPos());
    }

    private static int neighborTargetCount(ClientLevel level, BlockPos pos) {
        int count = 0;
        for (Direction direction : NEIGHBOR_DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            if (BedrockTargetBlocks.isTargetBlock(level.getBlockState(neighborPos))) {
                count++;
            }
        }
        return count;
    }

    private record CandidateShard(List<BedrockCandidatePlan> candidates, boolean hasMoreSource) {
    }

    private record PlacementSelection(BedrockTorchPlacement placement, BlockPos slimePos) {
    }
}
