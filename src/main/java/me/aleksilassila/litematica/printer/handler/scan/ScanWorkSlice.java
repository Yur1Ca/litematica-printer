package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.config.Configs;

/** Client-thread iteration allowance, independent of discovery and actual action execution. */
public interface ScanWorkSlice {
    /** Expensive work yields early; the user-selected amount scales both independent pools. */
    static long configuredTimeLimitNanos() {
        return Math.max(1_000_000L, Configs.Core.WORK_PER_TICK.getIntegerValue() * 2_000_000L / 8192L);
    }

    /** Reserves one candidate from the tick's shared iteration quota before advancing. */
    boolean tryAdvance();

    /** Checks both iteration allowances before reserving another candidate. */
    boolean isExceeded(long excludedNanos);

    /** Checks planning time for a candidate whose numeric permit may already be reserved. */
    boolean isTimeExceeded(long excludedNanos);

    /** Records iteration time separately from discovery. Finishing is idempotent. */
    void finish(long excludedNanos);
}
