package me.aleksilassila.litematica.printer.runtime;

import java.util.Locale;
import java.util.function.Consumer;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;

/** Bounded, opt-in log windows. Logging failures must never interrupt gameplay or epoch cleanup. */
final class RuntimeDiagnostics implements RuntimeComponent {
    private final RuntimeTiming timing;
    private final Consumer<String> sink;
    private final int windowTicks;
    private int ticks;
    private long outputDuringTick;

    RuntimeDiagnostics(RuntimeTiming timing, Consumer<String> sink, int windowTicks) {
        this.timing = timing;
        this.sink = sink;
        this.windowTicks = windowTicks;
    }

    long tickStarted() {
        this.outputDuringTick = 0L;
        return this.timing.start();
    }

    void tickCompleted(RuntimeEpoch epoch, long tickStart) {
        // A scope change can flush the previous epoch inside this tick. Keep logger cost out
        // of runtime work without changing the real clocks used by interaction/scan budgets.
        this.timing.finish("runtime", RuntimeTiming.Stage.RUNTIME_TICK, tickStart + this.outputDuringTick);
        this.tickCompleted(epoch);
    }

    void tickCompleted(RuntimeEpoch epoch) {
        if (this.timing.enabled() && ++this.ticks >= this.windowTicks) this.flush(epoch, "window");
    }

    void epochEnded(RuntimeEpoch epoch) {
        if (this.timing.enabled()) this.flush(epoch, "epoch_end");
    }

    @Override public void onEpochChanged(RuntimeEvent.EpochChanged event) { this.epochEnded(event.previous()); }

    private void flush(RuntimeEpoch epoch, String reason) {
        long outputStart = this.timing.start();
        try {
            var snapshot = this.timing.snapshot();
            var line = new StringBuilder("[printer-profile] epoch=").append(epoch.value())
                    .append(" ticks=").append(this.ticks).append(" reason=").append(reason)
                    .append(" durations=inclusive");
            for (var sample : snapshot.samples()) {
                line.append(" | ").append(sample.owner()).append('/').append(sample.stage())
                        .append(" calls=").append(sample.calls())
                        .append(String.format(Locale.ROOT, " totalMs=%.3f maxMs=%.3f",
                                sample.totalNanos() / 1_000_000.0, sample.maxNanos() / 1_000_000.0));
            }
            for (var counter : snapshot.counters()) {
                line.append(" | ").append(counter.owner()).append('/').append(counter.reason())
                        .append('=').append(counter.count());
            }
            this.sink.accept(line.toString());
        } catch (RuntimeException ignored) {
            // Diagnostics are optional; do not mask an interaction exception or stop cleanup.
        } finally {
            this.ticks = 0;
            this.timing.reset();
            this.outputDuringTick += Math.max(0L, this.timing.start() - outputStart);
        }
    }
}
