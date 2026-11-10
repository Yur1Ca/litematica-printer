package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Owns ordinary print candidates and short-lived retries; workflow stages live elsewhere. */
public final class PrintTargetScheduler {
    private final SortedSchematicTargetQueue sortedTargets;
    private final Runnable resetScanSources;
    private final Set<BlockPos> retryTargets = new LinkedHashSet<>();
    private Boolean sortedMode;

    public PrintTargetScheduler(SortedSchematicTargetQueue sortedTargets, Runnable resetScanSources) {
        this.sortedTargets = sortedTargets;
        this.resetScanSources = resetScanSources;
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
        this.retryTargets.clear();
        this.sortedTargets.clear();
        this.resetScanSources.run();
    }

    public void clear() {
        this.retryTargets.clear();
        this.sortedTargets.clear();
        this.sortedMode = null;
    }

    public void clearSorted() {
        this.sortedTargets.clear();
    }

    public boolean hasPendingWork() {
        return this.sortedTargets.hasPendingWork();
    }

    public boolean hasRunnableWork() {
        return this.hasPendingWork() || !this.retryTargets.isEmpty();
    }

    public List<BlockPos> retainedTargets() {
        return new ArrayList<>(this.retryTargets);
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
