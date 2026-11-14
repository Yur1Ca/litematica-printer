package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.handler.scan.StableCandidateMerge;
import me.aleksilassila.litematica.printer.handler.scan.BoundedTargetSet;
import me.aleksilassila.litematica.printer.handler.scan.ScanAvailability;
import me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import net.minecraft.core.Direction;
import java.util.function.Predicate;

/** Owns ordinary print candidates and short-lived retries; workflow stages live elsewhere. */
public final class PrintTargetScheduler {
    private final SortedSchematicTargetQueue sortedTargets;
    private final Runnable resetScanSources;
    private final Map<BlockPos, Long> retryAfter = new HashMap<>();
    private final Set<BlockPos> materialWaiting = new HashSet<>();
    private final Set<BlockPos> retryTargets = new BoundedTargetSet(BoundedTargetSet.DEFAULT_CAPACITY,
            pos -> {
                this.retryAfter.remove(pos);
                this.materialWaiting.remove(pos);
            }, this::reofferEvictedTarget);
    private Boolean sortedMode;
    private long generation;
    private List<BlockPos> readyRound = List.of();
    private int readyRoundIndex;
    private long readyRoundGeneration;
    private final LongOpenHashSet readyRoundKeys = new LongOpenHashSet();
    private ScanCandidateIterable warmRound;
    private Iterator<BlockPos> warmRoundIterator;
    private long warmReplacementGeneration;
    private BlockPos nextWarmTarget;

    public PrintTargetScheduler(SortedSchematicTargetQueue sortedTargets, Runnable resetScanSources) {
        this.sortedTargets = sortedTargets;
        this.resetScanSources = resetScanSources;
    }

    private void reofferEvictedTarget(BlockPos pos) {
        this.sortedTargets.reoffer(Boolean.TRUE.equals(this.sortedMode) ? "print_sorted" : "print", pos);
    }

    public boolean setSortedMode(boolean sorted) {
        if (this.sortedMode == null) {
            this.sortedMode = sorted;
            return false;
        }
        if (this.sortedMode == sorted) return false;
        this.sortedMode = sorted;
        this.invalidateCandidates();
        return true;
    }

    public void invalidateCandidates() {
        this.generation++;
        this.clearReadyRound();
        this.retryTargets.clear();
        this.sortedTargets.clear();
        this.resetScanSources.run();
    }

    public void clear() {
        this.generation++;
        this.clearReadyRound();
        this.retryTargets.clear();
        this.sortedTargets.clear();
        this.sortedMode = null;
    }

    public void clearSorted() {
        this.sortedTargets.clear();
    }

    public boolean hasPendingWork() {
        return this.nextWarmTarget != null || this.sortedTargets.hasPendingWork();
    }

    public boolean hasRunnableWork() {
        return this.hasPendingWork() || !this.retryTargets.isEmpty();
    }

    public int pendingTargetCount() {
        return this.retryTargets.size() + this.sortedTargets.pendingTargetCount() + (this.nextWarmTarget == null ? 0 : 1);
    }

    public List<BlockPos> retainedTargets() {
        return new ArrayList<>(this.retryTargets);
    }

    public Iterable<BlockPos> mergeOrdinaryTargets(Iterable<BlockPos> source) {
        return this.mergeOrdinaryTargets(source, Long.MAX_VALUE - 1L);
    }

    public Iterable<BlockPos> mergeOrdinaryTargets(Iterable<BlockPos> source, long now) {
        List<BlockPos> ready = this.retryTargets.stream()
                .filter(pos -> this.retryAfter.getOrDefault(pos, Long.MIN_VALUE) <= now).toList();
        return StableCandidateMerge.merge(ready, source, this.sortedTargets.targetComparator(),
                pos -> this.retryTargets.add(pos.immutable()));
    }

    /** Known ready work does not need a cold source probe before its action can run. */
    public ScanCandidateIterable readyOrdinaryTargets(long now) {
        if (!this.hasReadyRoundTarget(now)) {
            this.readyRound = new ArrayList<>(this.retryTargets.stream()
                    .filter(pos -> this.retryAfter.getOrDefault(pos, Long.MIN_VALUE) <= now).toList());
            if (this.readyRound.size() > 1) this.readyRound.sort(this.sortedTargets.targetComparator());
            this.readyRoundIndex = 0;
            this.readyRoundKeys.clear();
            this.readyRound.forEach(pos -> this.readyRoundKeys.add(pos.asLong()));
            this.warmRound = this.sortedTargets.bufferedPositions();
            this.warmRoundIterator = this.warmRound.iterator();
            this.warmReplacementGeneration = this.sortedTargets.replacementGeneration();
            this.nextWarmTarget = null;
            this.readyRoundGeneration++;
        }
        long snapshotGeneration = this.generation;
        long roundGeneration = this.readyRoundGeneration;
        return new ScanCandidateIterable() {
            @Override public boolean isValid() {
                return snapshotGeneration == generation && roundGeneration == readyRoundGeneration;
            }
            @Override public boolean isBuffered() { return true; }
            @Override public ScanAvailability availability() {
                return this.isValid() && hasReadyRoundTarget(now) ? ScanAvailability.READY : ScanAvailability.COMPLETE;
            }
            @Override public Iterator<BlockPos> iterator() {
                return new Iterator<>() {
                    @Override public boolean hasNext() {
                        return isValid() && hasReadyRoundTarget(now);
                    }
                    @Override public BlockPos next() {
                        if (!this.hasNext()) throw new NoSuchElementException("ready print targets are exhausted");
                        if (hasReadyRetry(now) && (nextWarmTarget == null
                                || sortedTargets.targetComparator().compare(readyRound.get(readyRoundIndex), nextWarmTarget) <= 0)) {
                            return readyRound.get(readyRoundIndex++);
                        }
                        BlockPos result = nextWarmTarget;
                        nextWarmTarget = null;
                        return result;
                    }
                };
            }
        };
    }

    private boolean hasReadyRoundTarget(long now) {
        boolean retryReady = this.hasReadyRetry(now);
        if (this.warmRound != null && !this.warmRound.isValid()) {
            BlockPos retained = this.nextWarmTarget;
            this.warmRound = null;
            this.warmRoundIterator = null;
            this.nextWarmTarget = null;
            if (retained != null && this.sortedTargets.canRetain(retained, this.warmReplacementGeneration)) {
                // A moved scope invalidates the old iterator after it removed this lookahead.
                // Return that reference before capturing the newly ordered retained buffer.
                this.sortedTargets.requeue(retained);
                this.warmRound = this.sortedTargets.bufferedPositions();
                this.warmRoundIterator = this.warmRound.iterator();
                this.warmReplacementGeneration = this.sortedTargets.replacementGeneration();
            }
        }
        while (this.nextWarmTarget == null && this.warmRoundIterator != null && this.warmRoundIterator.hasNext()) {
            BlockPos pos = this.warmRoundIterator.next();
            if (!this.readyRoundKeys.contains(pos.asLong()) && !this.retryTargets.contains(pos)) {
                // The cached iterator removes its offer; retain this single lookahead across ticks
                // until the runner actually inspects it, without evicting an untried retry target.
                this.nextWarmTarget = pos;
            }
        }
        return retryReady || this.nextWarmTarget != null;
    }

    private boolean hasReadyRetry(long now) {
        while (this.readyRoundIndex < this.readyRound.size()) {
            BlockPos pos = this.readyRound.get(this.readyRoundIndex);
            if (this.retryTargets.contains(pos)
                    && this.retryAfter.getOrDefault(pos, Long.MIN_VALUE) <= now) return true;
            this.readyRoundIndex++;
        }
        return false;
    }

    private void clearReadyRound() {
        this.readyRound = List.of();
        this.readyRoundIndex = 0;
        this.readyRoundKeys.clear();
        this.warmRound = null;
        this.warmRoundIterator = null;
        this.nextWarmTarget = null;
        this.readyRoundGeneration++;
    }

    public void deferUntil(BlockPos pos, long retryTick) {
        BlockPos retained = pos.immutable();
        this.retryTargets.add(retained);
        this.materialWaiting.remove(retained);
        this.retryAfter.put(retained, retryTick);
    }

    public void waitForMaterial(BlockPos pos, long now, boolean submissionPending) {
        this.deferUntil(pos, now + (submissionPending ? 1L : 20L));
        this.materialWaiting.add(pos.immutable());
    }

    /** Local world changes wake timed conditions; material waits require an inventory gain. */
    public void wake(BlockPos pos) {
        this.wakeTimed(pos);
        for (Direction direction : Direction.values()) this.wakeTimed(pos.relative(direction));
    }

    private void wakeTimed(BlockPos pos) {
        Long deadline = this.retryAfter.get(pos);
        if (deadline != null && !this.materialWaiting.contains(pos)) this.retryAfter.remove(pos);
    }

    public boolean acceptScanCandidate(BlockPos pos) {
        return !this.retryTargets.contains(pos);
    }

    public void removeRetry(BlockPos pos) {
        this.retryTargets.remove(pos);
    }

    public void retainWithin(List<PrinterBox> sourceBoxes, Predicate<BlockPos> permitted) {
        this.retryTargets.removeIf(pos -> !permitted.test(pos)
                || sourceBoxes.stream().noneMatch(box -> box.contains(pos)));
        if (this.nextWarmTarget != null && (!permitted.test(this.nextWarmTarget)
                || sourceBoxes.stream().noneMatch(box -> box.contains(this.nextWarmTarget)))) {
            this.nextWarmTarget = null;
        }
    }

    public Iterable<BlockPos> sortedPositions(List<PrinterBox> boxes, ClientLevel level,
                                               WorldSchematic schematic, LocalPlayer player, int guardLimit) {
        return this.sortedTargets.iterable(boxes, level, schematic, player, guardLimit,
                false, this::acceptScanCandidate);
    }

    public void replenishSortedPositions(List<PrinterBox> boxes, ClientLevel level,
                                         WorldSchematic schematic, LocalPlayer player, int guardLimit) {
        this.sortedTargets.iterable(boxes, level, schematic, player, guardLimit,
                true, this::acceptScanCandidate);
    }

    public void onResult(BlockPos pos, PrintPlacementResult result, boolean multiStage) {
        if (multiStage) return;
        this.retryAfter.remove(pos);
        this.materialWaiting.remove(pos);
        if (result.taskEvent() == PrintPlacementResult.TaskEvent.SUCCESS) {
            this.retryTargets.remove(pos);
            this.sortedTargets.remove(pos);
        } else if (result.shouldRetryTarget()) {
            this.retryTargets.add(pos.immutable());
        } else {
            this.retryTargets.remove(pos);
        }
    }
}
