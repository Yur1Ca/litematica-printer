package me.aleksilassila.litematica.printer.core.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Client-thread diagnostics only. Inclusive durations are never inputs to scheduling or budgets. */
public final class RuntimeTiming {
    public enum Stage {
        RUNTIME_TICK, MATERIAL_TICK, MINING_TICK, MODULE_TICK, MODULE_PREPROCESS,
        MODULE_ITERATION, ACTION_EXECUTION, CANDIDATE_SETUP, CANDIDATE_LOOKUP,
        INDEX_CHECK, INDEX_BUILD, CURSOR_BUILD, INDEX_FILTER, INDEX_SORT, INDEX_COPY,
        CLASSIFICATION, EXACT_FILTER, PRINT_COLLECTION, PRINT_SORT, TRENCH_DISCOVERY,
        SHADOW_SNAPSHOT, SHADOW_QUEUE, SHADOW_COMPUTE, SHADOW_READY
    }

    public enum Reason {
        NEW_SESSION, REGION_REPLACED, OWNER_RESET, CHUNK_REPLACED, CURSOR_CREATED,
        PASS_RESTART, SOURCE_CHANGED, INDEX_REBUILT, INDEX_WAITING,
        INDEX_POSITIONS_VISITED, INDEX_CANDIDATES,
        BEDROCK_ATTEMPTS, BEDROCK_RETRIES, BEDROCK_CONFIRMED_ATTEMPTS, BEDROCK_DISCARDED_ATTEMPTS,
        BEDROCK_ATTEMPT_TIMEOUTS, BEDROCK_FIRST_UPDATES, BEDROCK_FIRST_UPDATE_TICKS,
        BEDROCK_FINISHED_ATTEMPTS, BEDROCK_ATTEMPT_TICKS,
        SHADOW_MATCHED, SHADOW_MISMATCHED, SHADOW_STALE, SHADOW_FAILED,
        SHADOW_SUPERSEDED, SHADOW_OVERWRITTEN
    }

    public record Sample(String owner, Stage stage, long calls, long totalNanos, long maxNanos) { }
    public record Counter(String owner, Reason reason, long count) { }
    public record Snapshot(List<Sample> samples, List<Counter> counters) { }

    private static final RuntimeTiming DISABLED = new RuntimeTiming(false, () -> 0L);
    private static final int MAX_OWNERS = 16;
    private final boolean enabled;
    private final LongSupplier clock;
    private final Map<String, Totals> owners;

    public RuntimeTiming(boolean enabled, LongSupplier clock) {
        this.enabled = enabled;
        this.clock = clock;
        this.owners = enabled ? new LinkedHashMap<>() : null;
    }

    public static RuntimeTiming disabled() { return DISABLED; }
    public boolean enabled() { return this.enabled; }
    public long start() { return this.enabled ? this.clock.getAsLong() : 0L; }

    public void finish(String owner, Stage stage, long start) {
        if (!this.enabled) return;
        this.recordElapsed(owner, stage, this.clock.getAsLong() - start);
    }

    /** Import a completed worker duration on the client thread; never call this from the worker. */
    public void recordElapsed(String owner, Stage stage, long durationNanos) {
        if (!this.enabled) return;
        long elapsed = Math.max(0L, durationNanos);
        Totals totals = this.totals(owner);
        int index = stage.ordinal();
        totals.calls[index]++;
        totals.nanos[index] += elapsed;
        totals.maximum[index] = Math.max(totals.maximum[index], elapsed);
    }

    public void count(String owner, Reason reason, long amount) {
        if (this.enabled && amount > 0L) this.totals(owner).counts[reason.ordinal()] += amount;
    }

    private Totals totals(String owner) {
        Totals totals = this.owners.get(owner);
        if (totals != null) return totals;
        // Bound diagnostic memory even if a future caller accidentally supplies position-based keys.
        String key = this.owners.size() < MAX_OWNERS ? owner : "other";
        return this.owners.computeIfAbsent(key, ignored -> new Totals());
    }

    public Snapshot snapshot() {
        if (!this.enabled) return new Snapshot(List.of(), List.of());
        List<Sample> samples = new ArrayList<>();
        List<Counter> counters = new ArrayList<>();
        this.owners.forEach((owner, totals) -> {
            for (Stage stage : Stage.values()) {
                int index = stage.ordinal();
                if (totals.calls[index] > 0) samples.add(new Sample(owner, stage, totals.calls[index],
                        totals.nanos[index], totals.maximum[index]));
            }
            for (Reason reason : Reason.values()) {
                long count = totals.counts[reason.ordinal()];
                if (count > 0) counters.add(new Counter(owner, reason, count));
            }
        });
        return new Snapshot(List.copyOf(samples), List.copyOf(counters));
    }

    public void reset() { if (this.enabled) this.owners.clear(); }

    private static final class Totals {
        final long[] calls = new long[Stage.values().length];
        final long[] nanos = new long[Stage.values().length];
        final long[] maximum = new long[Stage.values().length];
        final long[] counts = new long[Reason.values().length];
    }
}
