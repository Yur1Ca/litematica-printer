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
        return this.sortedMode == Boolean.TRUE ? List.of() : new ArrayList<>(this.retryTargets);
    }

    public boolean acceptScanCandidate(BlockPos pos) {
        return !this.retryTargets.contains(pos);
    }

    public void retainWithin(List<PrinterBox> sourceBoxes) {
        this.retryTargets.removeIf(pos -> sourceBoxes.stream().noneMatch(box -> box.contains(pos)));
    }

    public Iterable<BlockPos> sortedPositions(List<PrinterBox> boxes, ClientLevel level,
                                               WorldSchematic schematic, LocalPlayer player, int guardLimit) {
        return this.sortedTargets.iterable(boxes, level, schematic, player, guardLimit);
    }

    public void onResult(BlockPos pos, PrintPlacementResult result, boolean multiStage) {
        if (multiStage) return;
        if (result.taskEvent() == PrintPlacementResult.TaskEvent.SUCCESS) {
            this.retryTargets.remove(pos);
            this.sortedTargets.remove(pos);
        } else if (result.shouldRetryTarget()) {
            if (this.sortedMode == Boolean.TRUE) this.sortedTargets.requeue(pos);
            else this.retryTargets.add(pos.immutable());
        }
    }
}
