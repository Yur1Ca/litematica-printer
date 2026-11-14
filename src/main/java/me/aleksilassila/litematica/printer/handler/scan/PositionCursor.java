package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;

interface PositionCursor extends AutoCloseable {
    enum PollResult {
        AVAILABLE,
        COMPLETE
    }

    PollResult poll(BlockPos.MutableBlockPos target);

    long peekDistanceSqr();

    /** Read-only metadata; never expands a column or consumes a position. */
    default BlockPos peekPosition() { return null; }

    boolean isComplete();

    @Override
    default void close() {
    }
}
