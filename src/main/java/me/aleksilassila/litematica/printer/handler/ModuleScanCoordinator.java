package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.enums.ScanState;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Admits the complete workset each tick; scan sessions own sleeping, repair and progress. */
final class ModuleScanCoordinator {
    interface Host {
        List<PrinterBox> scanSourceBoxes(PrinterBox interactionBox);

        FeatureModuleBase.IterationOutcome runIteration(PrinterBox interactionBox);
    }

    private final Host host;
    @Nullable private final AtomicReference<PrinterBox> externalScanBoxRef;
    private ScanState state = ScanState.FULL;

    ModuleScanCoordinator(Host host, @Nullable AtomicReference<PrinterBox> externalScanBoxRef) {
        this.host = host;
        this.externalScanBoxRef = externalScanBoxRef;
    }

    ScanState state() { return this.state; }

    // Dirty positions now belong to persistent sessions, rather than an outer region queue.
    int pendingDirtyRegionCount() { return 0; }

    void reset() {
        this.requestFullScan();
        this.updateExternalBox(null);
    }

    void requestFullScan() { this.state = ScanState.FULL; }

    void clearScopeDiagnostics() {
        this.state = ScanState.LAZY;
        this.updateExternalBox(null);
    }

    boolean run(PrinterBox interactionBox) {
        PrinterBox sourceBox = enclosingBox(this.host.scanSourceBoxes(interactionBox));
        this.updateExternalBox(sourceBox);
        if (sourceBox == null) {
            this.state = ScanState.LAZY;
            return false;
        }
        // Never narrow the session's scope to a dirty region or gate it on idle/waiting timers.
        // A completed source returns cheaply; due repair and newly eligible work resume locally.
        FeatureModuleBase.IterationOutcome outcome = this.host.runIteration(interactionBox);
        this.state = !outcome.scanPaused() && !outcome.interrupt()
                && !outcome.didWork() && !outcome.foundCandidate() ? ScanState.LAZY : ScanState.FULL;
        return outcome.interrupt();
    }

    private void updateExternalBox(@Nullable PrinterBox box) {
        if (this.externalScanBoxRef != null) this.externalScanBoxRef.set(box);
    }

    private static @Nullable PrinterBox enclosingBox(List<PrinterBox> boxes) {
        if (boxes.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (PrinterBox box : boxes) {
            minX = Math.min(minX, box.minX);
            minY = Math.min(minY, box.minY);
            minZ = Math.min(minZ, box.minZ);
            maxX = Math.max(maxX, box.maxX);
            maxY = Math.max(maxY, box.maxY);
            maxZ = Math.max(maxZ, box.maxZ);
        }
        return new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
