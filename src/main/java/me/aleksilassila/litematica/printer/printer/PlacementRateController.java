package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;

/** Coordinates configured placement pacing with the optional RTT floor. */
public final class PlacementRateController implements RuntimeComponent {
    private final RttReplayController rttReplayController;
    private long lastSentTick = Long.MIN_VALUE;

    public PlacementRateController(RttReplayController rttReplayController) {
        this.rttReplayController = rttReplayController;
    }

    public int effectiveIntervalTicks() {
        int baseInterval = Math.max(0, Configs.Placement.PLACE_INTERVAL.getIntegerValue());
        if (!Configs.Placement.RTT_ADAPTIVE_INTERVAL.getBooleanValue()) {
            return baseInterval;
        }
        return Math.max(baseInterval, this.rttReplayController.getExtraIntervalTicks(
                Configs.Placement.RTT_SAFETY_PERCENT.getIntegerValue()));
    }

    public boolean canSend(long currentTick) {
        if (!Configs.Placement.RTT_ADAPTIVE_INTERVAL.getBooleanValue()) return true;
        int interval = this.effectiveIntervalTicks();
        return interval <= 0 || this.lastSentTick == Long.MIN_VALUE
                || currentTick - this.lastSentTick >= interval;
    }

    public void recordSent(long currentTick) {
        this.lastSentTick = currentTick;
    }

    public long lastSentTick() {
        return this.lastSentTick;
    }

    public boolean isAdaptiveActive() {
        return Configs.Placement.RTT_ADAPTIVE_INTERVAL.getBooleanValue()
                && this.rttReplayController.getEstimatedRttMillis() > 0;
    }

    public void reset() {
        this.lastSentTick = Long.MIN_VALUE;
    }

    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        this.reset();
    }
}
