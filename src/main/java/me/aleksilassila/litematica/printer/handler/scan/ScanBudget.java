package me.aleksilassila.litematica.printer.handler.scan;

import me.aleksilassila.litematica.printer.config.Configs;
import java.util.Objects;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Owns separate discovery and iteration allowances shared by all owners in a client tick.
 *
 * <p>Discovery charges setup and live lookups; iteration charges target planning and cached
 * candidate consumption. Cold discovery cannot consume the allowance for dispatching ready work.
 * The scheduler rotates the first owner. Actual action execution and nested discovery time are
 * excluded from iteration; action throughput limits remain the runner's concern.</p>
 */
final class ScanBudget {
    private final LongSupplier nanoClock;
    private final LongSupplier budgetNanosSupplier;
    private final LongSupplier iterationBudgetNanosSupplier;
    private final Map<String, Long> consumedNanos = new HashMap<>();
    private long tickTime = Long.MIN_VALUE;
    private long totalConsumedNanos;
    private int consumedWorkUnits;
    private long iterationConsumedNanos;
    private int iterationCandidates;

    ScanBudget() {
        this(System::nanoTime);
    }

    ScanBudget(LongSupplier nanoClock) {
        this(nanoClock, ScanWorkSlice::configuredTimeLimitNanos);
    }

    ScanBudget(LongSupplier nanoClock, LongSupplier budgetNanosSupplier) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.budgetNanosSupplier = Objects.requireNonNull(budgetNanosSupplier, "budgetNanosSupplier");
        this.iterationBudgetNanosSupplier = ScanWorkSlice::configuredTimeLimitNanos;
    }

    void reset() {
        this.tickTime = Long.MIN_VALUE;
        this.consumedNanos.clear();
        this.totalConsumedNanos = 0L;
        this.consumedWorkUnits = 0;
        this.iterationConsumedNanos = 0L;
        this.iterationCandidates = 0;
    }

    void beginTick(long tickTime) {
        if (this.tickTime == tickTime) {
            return;
        }
        this.tickTime = tickTime;
        this.consumedNanos.clear();
        this.totalConsumedNanos = 0L;
        this.consumedWorkUnits = 0;
        this.iterationConsumedNanos = 0L;
        this.iterationCandidates = 0;
    }

    long start() {
        return this.nanoClock.getAsLong();
    }

    boolean isExceeded(String ownerKey, long ownerStartNanos) {
        return this.consumedWorkUnits >= Configs.Core.WORK_PER_TICK.getIntegerValue()
                || this.totalConsumedNanos + elapsedSince(ownerStartNanos)
                >= Math.max(1L, this.budgetNanosSupplier.getAsLong());
    }

    boolean tryAdvance() {
        if (this.consumedWorkUnits >= Configs.Core.WORK_PER_TICK.getIntegerValue()) return false;
        this.consumedWorkUnits++;
        return true;
    }

    ScanWorkSlice openSlice(String ownerKey, ScanMetricsAccumulator metrics) {
        long sliceStart = this.start();
        long discoveryBefore = this.totalConsumedNanos;
        long iterationBefore = this.iterationConsumedNanos;
        return new ScanWorkSlice() {
            private boolean finished;

            @Override
            public boolean tryAdvance() {
                if (ScanBudget.this.iterationCandidates >= Configs.Core.WORK_PER_TICK.getIntegerValue()) return false;
                ScanBudget.this.iterationCandidates++;
                return true;
            }

            @Override
            public boolean isExceeded(long excludedNanos) {
                return ScanBudget.this.iterationCandidates >= Configs.Core.WORK_PER_TICK.getIntegerValue()
                        || this.isTimeExceeded(excludedNanos);
            }

            @Override
            public boolean isTimeExceeded(long excludedNanos) {
                return iterationBefore + this.planningNanos(excludedNanos)
                        >= Math.max(1L, ScanBudget.this.iterationBudgetNanosSupplier.getAsLong());
            }

            @Override
            public void finish(long excludedNanos) {
                if (this.finished) return;
                this.finished = true;
                long planning = this.planningNanos(excludedNanos);
                ScanBudget.this.iterationConsumedNanos += planning;
                metrics.scanNanos += planning;
                ScanBudget.this.consumedNanos.merge(ownerKey, planning, Long::sum);
            }

            private long planningNanos(long excludedNanos) {
                long nestedDiscovery = ScanBudget.this.totalConsumedNanos - discoveryBefore;
                return Math.max(0L, ScanBudget.this.elapsedSince(sliceStart) - excludedNanos - nestedDiscovery);
            }
        };
    }

    void record(String ownerKey, ScanMetricsAccumulator metrics, long ownerStartNanos) {
        this.recordElapsed(ownerKey, metrics, this.elapsedSince(ownerStartNanos));
    }

    private void recordElapsed(String ownerKey, ScanMetricsAccumulator metrics, long elapsed) {
        metrics.scanNanos += elapsed;
        this.consumedNanos.merge(ownerKey, elapsed, Long::sum);
        this.totalConsumedNanos += elapsed;
    }

    private long elapsedSince(long startNanos) {
        return Math.max(0L, this.nanoClock.getAsLong() - startNanos);
    }

}
