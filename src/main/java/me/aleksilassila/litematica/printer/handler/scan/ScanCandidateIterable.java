package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;

/**
 * Candidate stream with an explicit lifecycle status.
 *
 * <p>A budget pause is distinct from completion. Consumers can finish the currently
 * available candidates and inspect {@link #availability()} without rebuilding the
 * resumable cursor.</p>
 */
public interface ScanCandidateIterable extends Iterable<BlockPos> {
    ScanAvailability availability();

    /** True when the iterable is an already-built action queue, not a live world scan. */
    default boolean isBuffered() {
        return false;
    }
}
