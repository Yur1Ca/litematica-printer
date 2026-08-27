package me.aleksilassila.litematica.printer.handler.handlers.print;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/** Tracks in-flight falling placements without blocking unrelated columns. */
public final class FallingPlacementTracker {
    private final Map<BlockPos, Pending> pending = new LinkedHashMap<>();

    public void clear() {
        this.pending.clear();
    }

    public void mark(BlockPos pos, BlockState expectedState, long currentTick) {
        this.mark(pos, expectedState, null, currentTick);
    }

    public void mark(BlockPos pos, BlockState expectedState, @org.jetbrains.annotations.Nullable BlockState originalState,
                     long currentTick) {
        this.pending.put(
                pos.immutable(),
                new Pending(pos.immutable(), expectedState, originalState, currentTick)
        );
    }

    public boolean blocks(
            BlockPos target,
            long currentTick,
            boolean enforceColumnOrder,
            BiPredicate<BlockPos, BlockState> stateMatches
    ) {
        Iterator<Pending> iterator = this.pending.values().iterator();
        while (iterator.hasNext()) {
            Pending entry = iterator.next();
            // A placement may be client-predicted into the source position during the send tick.
            // Real placements are only a same-tick column barrier: on the next tick the source
            // either contains the supported falling block or has already spawned a falling
            // entity. Keeping a supported block pending while it still matches expectedState
            // permanently blocks every higher target in that column.
            boolean released = entry.originalState != null
                    ? currentTick > entry.sentTick
                    : stateMatches.test(entry.pos, entry.expectedState);
            if (released) {
                iterator.remove();
            }
        }
        if (this.pending.containsKey(target)) return true;
        if (!enforceColumnOrder) return false;
        for (Pending entry : this.pending.values()) {
            if (entry.pos.getX() == target.getX()
                    && entry.pos.getZ() == target.getZ()
                    && entry.pos.getY() < target.getY()) {
                return true;
            }
        }
        return false;
    }

    private record Pending(
            BlockPos pos,
            BlockState expectedState,
            @org.jetbrains.annotations.Nullable BlockState originalState,
            long sentTick
    ) {
    }
}
