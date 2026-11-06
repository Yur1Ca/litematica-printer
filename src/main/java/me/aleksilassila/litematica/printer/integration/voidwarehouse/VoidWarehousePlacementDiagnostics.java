package me.aleksilassila.litematica.printer.integration.voidwarehouse;

/** Bounded client-thread counters; request ownership and acceptance stay in the placement state. */
final class VoidWarehousePlacementDiagnostics {
    private static final int SUMMARY_INTERVAL_TICKS = 200;
    private long windowStart = Long.MIN_VALUE;
    private long sent;
    private long ok;
    private long rejected;
    private long stale;
    private long throttled;
    private long empty;
    private long other;
    private long responseCount;
    private long responseTicks;
    private long maxResponseTicks;

    void recordSent(long now) {
        startWindow(now);
        this.sent++;
    }

    void recordResult(int status, long sentAt, long now) {
        startWindow(now);
        switch (status) {
            case 0 -> this.ok++;
            case 2 -> this.empty++;
            case 3 -> this.rejected++;
            case 4 -> this.throttled++;
            case 6 -> this.stale++;
            default -> this.other++;
        }
        long elapsed = Math.max(0L, now - sentAt);
        this.responseCount++;
        this.responseTicks += elapsed;
        this.maxResponseTicks = Math.max(this.maxResponseTicks, elapsed);
    }

    Summary poll(long now, int pending) {
        startWindow(now);
        if (now - this.windowStart < SUMMARY_INTERVAL_TICKS) return null;
        Summary summary = this.sent > 0 || this.responseCount > 0
                ? new Summary(now - this.windowStart, this.sent, this.ok, this.rejected, this.stale,
                        this.throttled, this.empty, this.other, pending,
                        this.responseCount == 0 ? 0.0 : (double) this.responseTicks / this.responseCount,
                        this.maxResponseTicks)
                : null;
        reset(now);
        return summary;
    }

    void reset(long now) {
        this.windowStart = now;
        this.sent = 0;
        this.ok = 0;
        this.rejected = 0;
        this.stale = 0;
        this.throttled = 0;
        this.empty = 0;
        this.other = 0;
        this.responseCount = 0;
        this.responseTicks = 0;
        this.maxResponseTicks = 0;
    }

    private void startWindow(long now) {
        if (this.windowStart == Long.MIN_VALUE) this.windowStart = now;
    }

    record Summary(long ticks, long sent, long ok, long rejected, long stale, long throttled,
                   long empty, long other, int pending, double averageResponseTicks, long maxResponseTicks) { }
}
