package me.aleksilassila.litematica.printer.core.runtime;

import java.util.HashMap;
import java.util.Map;

/** Client-thread admission identity. Stopping a producer does not reset transport state. */
public final class FeatureLifecycle {
    private final Map<String, State> owners = new HashMap<>();

    public record Work(String owner, RuntimeEpoch epoch, long generation) {}
    private record State(boolean enabled, long generation) {}

    public boolean observe(String owner, boolean enabled) {
        State previous = this.owners.get(owner);
        if (previous != null && previous.enabled == enabled) return false;
        this.owners.put(owner, new State(enabled, previous == null ? 0 : previous.generation + 1));
        return previous != null;
    }

    public Work capture(String owner, RuntimeEpoch epoch) {
        State state = this.owners.get(owner);
        return new Work(owner, epoch, state == null ? 0 : state.generation);
    }

    public boolean canSend(Work work, RuntimeEpoch epoch) {
        State state = this.owners.get(work.owner);
        return work.epoch.equals(epoch) && (state == null
                ? work.generation == 0 : state.enabled && state.generation == work.generation);
    }

    public void reset() {
        this.owners.clear();
    }
}
