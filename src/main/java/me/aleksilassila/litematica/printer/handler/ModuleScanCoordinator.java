package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ScanState;
import me.aleksilassila.litematica.printer.handler.scan.BoxRegionDiff;
import me.aleksilassila.litematica.printer.handler.scan.DirtyRegionTracker;
import me.aleksilassila.litematica.printer.handler.scan.ScanLifecycle;
import me.aleksilassila.litematica.printer.handler.scan.ScanEngine;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Owns scan wake-up, lazy/partial/full transitions and dirty-region ordering for one feature. */
final class ModuleScanCoordinator {
    interface Host {
        List<PrinterBox> scanSourceBoxes(PrinterBox interactionBox);

        @Nullable PrinterBox scanSourceBox(PrinterBox interactionBox);

        FeatureModuleBase.IterationOutcome runIteration(PrinterBox interactionBox);

        boolean hasRunnableTargets();

        boolean hasWaitingTargets();

        boolean usesDirtyRegionWakeup();

        double playerX();

        double playerEyeY();

        double playerZ();
    }

    private final Host host;
    private final ScanEngine scanEngine;
    @Nullable
    private final AtomicReference<PrinterBox> externalScanBoxRef;
    private final ScanLifecycle lifecycle = new ScanLifecycle();
    private final ArrayDeque<PrinterBox> dirtyQueue = new ArrayDeque<>();
    @Nullable private PrinterBox activeDirtyBox;
    @Nullable private PrinterBox lastSourceBox;
    private List<PrinterBox> lastSourceBoxes = List.of();
    @Nullable private BlockPos lastCenterSection;
    private long lastDirtyVersion;
    private int pendingDirtyRegionCount;

    ModuleScanCoordinator(
            Host host,
            @Nullable AtomicReference<PrinterBox> externalScanBoxRef,
            ScanEngine scanEngine
    ) {
        this.host = host;
        this.externalScanBoxRef = externalScanBoxRef;
        this.scanEngine = scanEngine;
    }

    ScanState state() {
        return this.lifecycle.state();
    }

    int pendingDirtyRegionCount() {
        return this.pendingDirtyRegionCount;
    }

    void reset() {
        this.lifecycle.setState(ScanState.FULL);
        this.lifecycle.idlePolicy().reset();
        this.lastSourceBox = null;
        this.lastSourceBoxes = List.of();
        this.lastCenterSection = null;
        this.updateExternalBox(null);
        this.lastDirtyVersion = this.scanEngine.dirtyVersion();
        this.clearDirtyQueue();
    }

    void requestFullScan() {
        this.lifecycle.setState(ScanState.FULL);
        this.lifecycle.idlePolicy().reset();
        this.clearDirtyQueue();
    }

    boolean run(PrinterBox playerInteractionBox) {
        this.wakeForCenterChange();
        List<PrinterBox> sourceBoxes = this.host.scanSourceBoxes(playerInteractionBox);
        PrinterBox sourceBox = enclosingBox(sourceBoxes);
        if (sourceBox == null) {
            this.updateExternalBox(null);
            this.lastSourceBox = null;
            this.lastSourceBoxes = List.of();
            return false;
        }
        this.updateExternalBox(sourceBox);
        this.updateSource(sourceBox, sourceBoxes);
        if (this.host.hasRunnableTargets()) {
            return this.runFull(playerInteractionBox);
        }
        if (!this.isLazyEnabled()) {
            this.lifecycle.setState(ScanState.FULL);
            this.lifecycle.idlePolicy().clearCompletedPassEvidence();
            this.clearDirtyQueue();
            return this.runFull(playerInteractionBox);
        }
        return switch (this.lifecycle.state()) {
            case FULL -> this.runFull(playerInteractionBox);
            case PARTIAL -> this.runPartial(playerInteractionBox);
            case LAZY -> this.runLazy(playerInteractionBox);
        };
    }

    private void updateSource(PrinterBox sourceBox, List<PrinterBox> sourceBoxes) {
        boolean boxesChanged = !this.lastSourceBoxes.equals(sourceBoxes);
        if (sourceBox.equals(this.lastSourceBox) && !boxesChanged) {
            return;
        }
        this.lastSourceBoxes = List.copyOf(sourceBoxes);
        if (!boxesChanged && this.lastSourceBox != null && this.lastSourceBox.sameSectionWindow(sourceBox)) {
            PrinterBox previous = this.lastSourceBox;
            this.lastSourceBox = sourceBox;
            if (this.lifecycle.state() == ScanState.LAZY) {
                if (this.host.usesDirtyRegionWakeup()) {
                    this.queueNewlyExposed(previous, sourceBox);
                } else {
                    this.lifecycle.setState(ScanState.FULL);
                    this.lifecycle.idlePolicy().recordActivity();
                    this.clearDirtyQueue();
                }
            }
            return;
        }
        this.lastSourceBox = sourceBox;
        this.lifecycle.setState(ScanState.FULL);
        this.lifecycle.idlePolicy().recordActivity();
        this.lastDirtyVersion = this.scanEngine.dirtyVersion();
        this.clearDirtyQueue();
    }

    private boolean runFull(PrinterBox interactionBox) {
        FeatureModuleBase.IterationOutcome outcome = this.host.runIteration(interactionBox);
        if (outcome.scanPaused()) {
            this.lifecycle.setState(ScanState.FULL);
            return true;
        }
        int lazyThreshold = Configs.Core.LAZY_ENTER_TICKS.getIntegerValue();
        if (this.lifecycle.idlePolicy().recordFullIteration(
                outcome.didWork(), outcome.foundCandidate(), outcome.completedPass(), lazyThreshold)) {
            this.lifecycle.setState(ScanState.LAZY);
            this.clearDirtyQueue();
        }
        return outcome.interrupt();
    }

    private boolean runLazy(PrinterBox interactionBox) {
        if (this.host.usesDirtyRegionWakeup() && this.lifecycle.state() == ScanState.LAZY) {
            this.refreshDirtyQueue(interactionBox);
        }
        if (this.host.hasWaitingTargets() && this.dirtyQueue.isEmpty()) {
            return false;
        }
        if (this.lifecycle.state() == ScanState.LAZY) {
            int probeInterval = Math.max(40, Configs.Core.LAZY_ENTER_TICKS.getIntegerValue() * 10);
            if (!this.lifecycle.idlePolicy().shouldRunLazyProbe(probeInterval)) {
                return false;
            }
            FeatureModuleBase.IterationOutcome outcome = this.host.runIteration(interactionBox);
            this.pendingDirtyRegionCount = 0;
            if (outcome.scanPaused()) {
                // A time-budget pause keeps the same cursor. It is not a new world/selection
                // revision and must not promote a completed lazy feature into a fresh full pass.
                this.lifecycle.setState(ScanState.LAZY);
                return true;
            }
            if (this.lifecycle.idlePolicy().recordLazyProbe(outcome.didWork(), outcome.foundCandidate())) {
                this.lifecycle.setState(ScanState.FULL);
                return outcome.interrupt();
            }
            this.lifecycle.setState(ScanState.LAZY);
            return true;
        }
        return this.lifecycle.state() == ScanState.FULL
                ? this.runFull(interactionBox)
                : this.runPartial(interactionBox);
    }

    private boolean runPartial(PrinterBox interactionBox) {
        if (this.activeDirtyBox == null) {
            if (this.dirtyQueue.isEmpty()) {
                this.refreshDirtyQueue(interactionBox);
                if (this.lifecycle.state() == ScanState.LAZY) {
                    return false;
                }
                if (this.lifecycle.state() == ScanState.FULL) {
                    return this.runFull(interactionBox);
                }
            }
            this.activeDirtyBox = this.dirtyQueue.pollFirst();
        }
        if (this.activeDirtyBox == null) {
            this.lifecycle.setState(ScanState.LAZY);
            this.pendingDirtyRegionCount = 0;
            return false;
        }
        PrinterBox bounded = intersect(interactionBox, this.activeDirtyBox);
        if (bounded == null || this.host.scanSourceBox(bounded) == null) {
            this.activeDirtyBox = null;
            this.updatePartialState(false);
            return this.hasPendingPartialScan();
        }
        FeatureModuleBase.IterationOutcome outcome = this.host.runIteration(bounded);
        if (!outcome.interrupt()) {
            this.activeDirtyBox = null;
        }
        this.updatePartialState(outcome.interrupt());
        return outcome.interrupt() || this.hasPendingPartialScan();
    }

    private void refreshDirtyQueue(PrinterBox interactionBox) {
        DirtyRegionTracker.DirtySnapshot snapshot =
                this.scanEngine.dirtySnapshotAfter(this.lastDirtyVersion, interactionBox);
        this.lastDirtyVersion = snapshot.version();
        this.dirtyQueue.clear();
        this.activeDirtyBox = null;
        List<PrinterBox> boxes = new ArrayList<>();
        for (PrinterBox dirtyBox : snapshot.boxes()) {
            PrinterBox bounded = intersect(interactionBox, dirtyBox);
            if (bounded != null && this.host.scanSourceBox(bounded) != null) {
                boxes.add(bounded);
            }
        }
        boxes.sort(Comparator.comparingDouble(this::distanceToPlayerSqr));
        this.dirtyQueue.addAll(boxes);
        this.pendingDirtyRegionCount = this.dirtyQueue.size();
        this.lifecycle.setState(this.dirtyQueue.isEmpty() ? ScanState.LAZY : ScanState.PARTIAL);
    }

    private void updatePartialState(boolean interrupt) {
        this.pendingDirtyRegionCount = this.dirtyQueue.size() + (this.activeDirtyBox == null ? 0 : 1);
        if (!interrupt && !this.hasPendingPartialScan()) {
            this.lifecycle.setState(ScanState.LAZY);
            this.lifecycle.idlePolicy().resetIdleAndProbe();
            this.pendingDirtyRegionCount = 0;
        }
    }

    private boolean hasPendingPartialScan() {
        return this.activeDirtyBox != null || !this.dirtyQueue.isEmpty();
    }

    private void queueNewlyExposed(PrinterBox previous, PrinterBox current) {
        BoxRegionDiff.Result diff = BoxRegionDiff.newlyExposed(previous, current);
        if (diff.requiresFullScan()) {
            this.lifecycle.setState(ScanState.FULL);
            this.lifecycle.idlePolicy().recordActivity();
            this.clearDirtyQueue();
            return;
        }
        this.dirtyQueue.addAll(diff.boxes());
        if (!this.dirtyQueue.isEmpty()) {
            List<PrinterBox> sorted = new ArrayList<>(this.dirtyQueue);
            sorted.sort(Comparator.comparingDouble(this::distanceToPlayerSqr));
            this.dirtyQueue.clear();
            this.dirtyQueue.addAll(sorted);
            this.pendingDirtyRegionCount = this.dirtyQueue.size();
            this.lifecycle.setState(ScanState.PARTIAL);
            this.lifecycle.idlePolicy().resetIdle();
        }
    }

    private void wakeForCenterChange() {
        int sectionX = (int) Math.floor(this.host.playerX()) >> 4;
        int sectionY = (int) Math.floor(this.host.playerEyeY()) >> 4;
        int sectionZ = (int) Math.floor(this.host.playerZ()) >> 4;
        if (this.lastCenterSection == null) {
            this.lastCenterSection = new BlockPos(sectionX, sectionY, sectionZ);
            return;
        }
        if (this.lastCenterSection.getX() == sectionX
                && this.lastCenterSection.getY() == sectionY
                && this.lastCenterSection.getZ() == sectionZ) {
            return;
        }
        this.lastCenterSection = new BlockPos(sectionX, sectionY, sectionZ);
        if (this.lifecycle.state() != ScanState.FULL) {
            this.requestFullScan();
        }
    }

    private void clearDirtyQueue() {
        this.dirtyQueue.clear();
        this.activeDirtyBox = null;
        this.pendingDirtyRegionCount = 0;
    }

    private void updateExternalBox(@Nullable PrinterBox box) {
        if (this.externalScanBoxRef != null) {
            this.externalScanBoxRef.set(box);
        }
    }

    private double distanceToPlayerSqr(PrinterBox box) {
        double dx = axisDistance(this.host.playerX(), box.minX, box.maxX);
        double dy = axisDistance(this.host.playerEyeY(), box.minY, box.maxY);
        double dz = axisDistance(this.host.playerZ(), box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, int min, int max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0D;
    }

    private boolean isLazyEnabled() {
        return Configs.Core.LAZY_ENTER_TICKS.getIntegerValue() > 0;
    }

    private static @Nullable PrinterBox intersect(PrinterBox first, PrinterBox second) {
        int minX = Math.max(first.minX, second.minX);
        int minY = Math.max(first.minY, second.minY);
        int minZ = Math.max(first.minZ, second.minZ);
        int maxX = Math.min(first.maxX, second.maxX);
        int maxY = Math.min(first.maxY, second.maxY);
        int maxZ = Math.min(first.maxZ, second.maxZ);
        return minX > maxX || minY > maxY || minZ > maxZ
                ? null : new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
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
