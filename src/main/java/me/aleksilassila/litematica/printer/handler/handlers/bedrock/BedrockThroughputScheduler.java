package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

/**
 * Pure scheduling policy for bedrock target advancement.
 *
 * <p>An interval is an execution window, rather than a credit accumulator. This keeps
 * {@code interval=2, throughput=6} as a deterministic {@code 6,0,6,0} cadence and prevents
 * unused work from becoming a later burst.</p>
 */
final class BedrockThroughputScheduler {
    private long tick;
    private int configuredThroughput = -1;
    private int configuredInterval = -1;

    void reset() {
        this.tick = 0L;
        this.configuredThroughput = -1;
        this.configuredInterval = -1;
    }

    Allocation allocate(int requestedThroughput, int requestedInterval) {
        int throughput = Math.max(1, requestedThroughput);
        int interval = Math.max(1, requestedInterval);
        if (throughput != this.configuredThroughput || interval != this.configuredInterval) {
            this.configuredThroughput = throughput;
            this.configuredInterval = interval;
            this.tick = 0L;
        }
        boolean window = this.tick++ % interval == 0L;
        int total = window ? throughput : 0;
        int critical = (total + 1) / 2;
        return new Allocation(total, critical, total - critical, interval);
    }

    void consume(Allocation allocation, int unusedActions) {
        if (allocation == null) {
            return;
        }
        // A window expires at the end of its tick. Never carry unused capacity into a later
        // window: doing so turns a bounded throughput setting into an unbounded burst.
    }

    record Allocation(int total, int critical, int preparation, int interval) {
    }
}
