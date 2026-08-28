package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.handler.scan.SectionScanSession.Candidate;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Builds the candidate stream for one scan request.
 *
 * <p>This class deliberately owns only candidate production. Session lifetime, dirty-region
 * invalidation and the global time budget remain in their dedicated services. The returned
 * iterable exposes an explicit availability state so a budget-limited pass cannot be mistaken
 * for a completed scan.</p>
 */
final class ScanCandidateSourceFactory {
    private static final int BUDGET_CHECK_INTERVAL = 8;

    private final ScanSessionStore sessions;
    private final ScanBudget budget;
    private final SectionSnapshotStore snapshots;
    private final RuntimeEpoch runtimeEpoch;
    private final LongSupplier snapshotRevision;
    private final LongSupplier generationSequence;
    private final long tickTime;

    ScanCandidateSourceFactory(
            ScanSessionStore sessions,
            ScanBudget budget,
            SectionSnapshotStore snapshots,
            RuntimeEpoch runtimeEpoch,
            LongSupplier snapshotRevision,
            LongSupplier generationSequence,
            long tickTime
    ) {
        this.sessions = sessions;
        this.budget = budget;
        this.snapshots = snapshots;
        this.runtimeEpoch = runtimeEpoch;
        this.snapshotRevision = snapshotRevision;
        this.generationSequence = generationSequence;
        this.tickTime = tickTime;
    }

    Iterable<BlockPos> create(
            String cacheOwnerKey,
            String metricsOwnerKey,
            List<PrinterBox> sourceBoxes,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int scanGuardLimit,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter,
            boolean unbounded
    ) {
        int scanLimit = unbounded ? Integer.MAX_VALUE : this.getScanLimit(scanGuardLimit);
        ScanMetricsAccumulator metrics = this.sessions.metrics(metricsOwnerKey);
        PrinterBox sourceBounds = enclosingBox(sourceBoxes);
        if (sourceBounds == null) {
            return List.of();
        }
        SectionScanSession session = this.sessions.getOrCreate(
                cacheOwnerKey,
                metrics,
                intent,
                sourceBounds,
                sourceBoxes,
                player,
                this.runtimeEpoch,
                this.snapshotRevision,
                this.generationSequence
        );
        return new ScanCandidateIterable() {
            private final ScanAvailability[] state = {ScanAvailability.READY};

            @Override
            public ScanAvailability availability() {
                return this.state[0];
            }

            @Override
            public Iterator<BlockPos> iterator() {
                return new Iterator<>() {
                    private final LongSet emitted = new LongOpenHashSet();
                    private final WorldObservationPort observation = level == null
                            ? null
                            : new SnapshotWorldObservation(
                                    ScanCandidateSourceFactory.this.snapshots,
                                    new LiveWorldObservation(level, schematic)
                            );
                    private BlockPos next;
                    private boolean prepared;
                    private int considered;
                    private int budgetChecks;

                    private void prepare() {
                        if (this.prepared) {
                            return;
                        }
                        this.prepared = true;
                        state[0] = ScanAvailability.READY;

                        long budgetStart = System.nanoTime();
                        boolean budgetHit = false;
                        try {
                            while (this.considered < scanLimit && session.canScan(tickTime)) {
                                if (!unbounded && ++this.budgetChecks % BUDGET_CHECK_INTERVAL == 0
                                        && ScanCandidateSourceFactory.this.budget.isExceeded(
                                        metricsOwnerKey, budgetStart)) {
                                    budgetHit = true;
                                    break;
                                }

                                Candidate candidate = session.next(
                                        this.observation,
                                        tickTime,
                                        () -> !unbounded && ScanCandidateSourceFactory.this.budget.isExceeded(
                                                metricsOwnerKey, budgetStart),
                                        preFilter,
                                        unbounded
                                );
                                if (!session.belongsTo(ScanCandidateSourceFactory.this.runtimeEpoch)) {
                                    break;
                                }
                                if (candidate == null) {
                                    if (session.wasPaused()) {
                                        budgetHit = true;
                                        state[0] = ScanAvailability.PAUSED;
                                    }
                                    break;
                                }
                                metrics.sourceCandidates++;
                                this.considered++;
                                BlockPos pos = candidate.pos();
                                if (!session.contains(pos)) {
                                    continue;
                                }
                                boolean target = candidate.acceptedByFlags(intent);
                                if (!target && intent.shouldRunExactPredicate(candidate.flags())) {
                                    target = exactPredicate.test(pos);
                                }
                                if (!target) {
                                    continue;
                                }
                                if (this.emitted.add(ScanCache.key(pos))) {
                                    metrics.acceptedTargets++;
                                    this.next = pos;
                                    return;
                                }
                            }
                        } finally {
                            if (!unbounded) {
                                ScanCandidateSourceFactory.this.budget.record(metricsOwnerKey, metrics, budgetStart);
                            }
                        }

                        if (budgetHit) {
                            metrics.budgetPauses++;
                        }
                        if (state[0] == ScanAvailability.PAUSED) {
                            return;
                        }
                        boolean pending = session.hasPendingSource(tickTime);
                        if (pending && (budgetHit || this.considered >= scanLimit)) {
                            state[0] = ScanAvailability.PAUSED;
                        } else if (!pending) {
                            state[0] = ScanAvailability.COMPLETE;
                        }
                    }

                    @Override
                    public boolean hasNext() {
                        this.prepare();
                        return this.next != null;
                    }

                    @Override
                    public BlockPos next() {
                        this.prepare();
                        if (this.next == null) {
                            throw new NoSuchElementException("scan candidate source is exhausted");
                        }
                        BlockPos result = this.next;
                        this.next = null;
                        this.prepared = false;
                        return result;
                    }
                };
            }
        };
    }

    private int getScanLimit(int scanGuardLimit) {
        return scanGuardLimit > 0 ? scanGuardLimit : Integer.MAX_VALUE;
    }

    private static PrinterBox enclosingBox(List<PrinterBox> boxes) {
        PrinterBox result = null;
        for (PrinterBox box : boxes) {
            if (box == null) {
                continue;
            }
            if (result == null) {
                result = box;
                continue;
            }
            result = new PrinterBox(
                    Math.min(result.minX, box.minX),
                    Math.min(result.minY, box.minY),
                    Math.min(result.minZ, box.minZ),
                    Math.max(result.maxX, box.maxX),
                    Math.max(result.maxY, box.maxY),
                    Math.max(result.maxZ, box.maxZ)
            );
        }
        return result;
    }
}
