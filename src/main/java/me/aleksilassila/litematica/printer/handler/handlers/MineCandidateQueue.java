package me.aleksilassila.litematica.printer.handler.handlers;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * Retains mine candidates across scan batches and ticks.
 *
 * <p>The scan engine is allowed to wait for another batch while a mine action
 * is still in progress.  A coordinate keyed queue makes that boundary
 * explicit: a rescan refreshes a target instead of duplicating it, and a
 * temporarily empty scan never discards targets already admitted by the
 * feature.</p>
 */
final class MineCandidateQueue {
    private final LinkedHashSet<BlockPos> entries = new LinkedHashSet<>();

    void add(BlockPos pos) {
        if (pos != null) this.entries.add(pos.immutable());
    }

    boolean isEmpty() {
        return this.entries.isEmpty();
    }

    int size() {
        return this.entries.size();
    }

    List<BlockPos> snapshot() {
        return new ArrayList<>(this.entries);
    }

    void remove(BlockPos pos) {
        if (pos != null) {
            this.entries.remove(pos);
        }
    }

    void removeIf(Predicate<BlockPos> predicate) {
        this.entries.removeIf(predicate);
    }

    void clear() {
        this.entries.clear();
    }
}
