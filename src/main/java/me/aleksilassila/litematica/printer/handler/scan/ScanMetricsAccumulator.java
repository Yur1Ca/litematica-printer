package me.aleksilassila.litematica.printer.handler.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;

/** Mutable, client-thread-owned counters for one scan owner. */
final class ScanMetricsAccumulator {
    final RuntimeTiming timing;
    final String owner;

    ScanMetricsAccumulator() { this(RuntimeTiming.disabled(), "scan"); }
    ScanMetricsAccumulator(RuntimeTiming timing, String owner) {
        this.timing = timing;
        this.owner = owner;
    }

    long scanNanos;
    int scannedBlocks;
    int scannedSections;
    private final LongSet scannedSectionKeys = new LongOpenHashSet();
    int sourceCandidates;
    int acceptedTargets;
    int budgetPauses;
    int completedPasses;

    void reset() {
        this.scanNanos = 0L;
        this.scannedBlocks = 0;
        this.scannedSections = 0;
        this.scannedSectionKeys.clear();
        this.sourceCandidates = 0;
        this.acceptedTargets = 0;
        this.budgetPauses = 0;
        this.completedPasses = 0;
    }

    void recordScannedSection(long sectionKey) {
        if (this.scannedSectionKeys.add(sectionKey)) {
            this.scannedSections++;
        }
    }

    ScanCache.ScanMetrics snapshot() {
        return new ScanCache.ScanMetrics(
                this.scanNanos,
                this.scannedBlocks,
                this.scannedSections,
                this.sourceCandidates,
                this.acceptedTargets,
                this.budgetPauses,
                this.completedPasses
        );
    }
}
