package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Paces the first real construction use; already-started cycles never wait for another start. */
final class BedrockThroughputScheduler<K> {
    enum CycleState { RUNNING, UNMEASURED, COMPLETE, DISCARDED }

    private final Map<K, Long> started = new HashMap<>();
    private long tick = Long.MIN_VALUE;
    private long lastStart = Long.MIN_VALUE;
    private int capacity;
    private int interval = -1;
    private double nextStart;
    private double cycleTicks;
    private boolean learning;

    void reset() {
        this.started.clear();
        this.tick = this.lastStart = Long.MIN_VALUE;
        this.capacity = 0;
        this.interval = -1;
        this.nextStart = this.cycleTicks = 0;
        this.learning = false;
    }

    void beginTick(long now, int capacity, int interval, boolean learning) {
        int cap = Math.max(1, capacity);
        int delay = Math.max(0, interval);
        if (!learning || this.tick != Long.MIN_VALUE && now - this.tick > 1) {
            this.started.replaceAll((owner, start) -> Long.MIN_VALUE);
        }
        this.tick = now;
        this.learning = learning;
        if (cap != this.capacity || delay != this.interval) {
            this.capacity = cap;
            this.interval = delay;
            this.nextStart = this.lastStart == Long.MIN_VALUE ? now : this.lastStart + this.period();
        }
    }

    boolean hasStarted(K owner) { return this.started.containsKey(owner); }

    boolean canStart(K owner) {
        return this.hasStarted(owner) || this.availableStarts() > 0;
    }

    int availableStarts() {
        if (this.tick == Long.MIN_VALUE) return 0;
        // Keep only this tick's fractional allowance. Time spent idle cannot buy a later burst.
        double due = this.tick + 1.0 - Math.max(this.nextStart, this.tick);
        int allowance = (int) Math.ceil(Math.max(0, due / this.period() - 1.0e-9));
        return Math.max(0, Math.min(this.capacity - this.started.size(), allowance));
    }

    void started(K owner) {
        if (this.hasStarted(owner)) return;
        if (this.availableStarts() == 0) throw new IllegalStateException("No bedrock construction start available");
        this.started.put(owner, this.learning ? this.tick : Long.MIN_VALUE);
        this.lastStart = this.tick;
        this.nextStart = Math.max(this.nextStart, this.tick) + this.period();
    }

    void observeCycles(Function<K, CycleState> observation) {
        var iterator = this.started.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            CycleState state = observation.apply(entry.getKey());
            if (state == CycleState.RUNNING) continue;
            if (state == CycleState.UNMEASURED) { entry.setValue(Long.MIN_VALUE); continue; }
            if (state == CycleState.COMPLETE && entry.getValue() != Long.MIN_VALUE && this.learning) {
                double elapsed = Math.max(1, this.tick - entry.getValue());
                this.cycleTicks = this.cycleTicks == 0 ? elapsed : this.cycleTicks + (elapsed - this.cycleTicks) / 4;
            }
            iterator.remove();
        }
    }

    private double period() {
        return this.interval > 0 ? this.interval : this.cycleTicks > 0 ? this.cycleTicks / this.capacity : 1;
    }

}
