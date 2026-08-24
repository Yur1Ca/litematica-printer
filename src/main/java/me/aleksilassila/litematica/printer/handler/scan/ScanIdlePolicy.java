package me.aleksilassila.litematica.printer.handler.scan;

/**
 * Owns the counters that decide when a full scanner may sleep and when a lazy scanner must probe.
 * This class deliberately contains no Minecraft state so wake-up behavior can be regression tested.
 */
public final class ScanIdlePolicy {
    private int idleTicks;
    private int lazyProbeTicks;
    private boolean completedPassSinceActivity;

    public void reset() {
        this.idleTicks = 0;
        this.lazyProbeTicks = 0;
        this.completedPassSinceActivity = false;
    }

    public void recordActivity() {
        this.idleTicks = 0;
        this.completedPassSinceActivity = false;
    }

    public void clearCompletedPassEvidence() {
        this.completedPassSinceActivity = false;
    }

    public void resetIdleAndProbe() {
        this.idleTicks = 0;
        this.lazyProbeTicks = 0;
    }

    public void resetIdle() {
        this.idleTicks = 0;
    }

    public boolean recordFullIteration(
            boolean didWork,
            boolean foundCandidate,
            boolean completedPass,
            int lazyThreshold
    ) {
        if (didWork || foundCandidate) {
            this.recordActivity();
            return false;
        }
        if (completedPass) {
            this.completedPassSinceActivity = true;
        }
        if (lazyThreshold <= 0) {
            return false;
        }
        if (this.idleTicks < lazyThreshold) {
            this.idleTicks++;
        }
        if (!this.completedPassSinceActivity || this.idleTicks < lazyThreshold) {
            return false;
        }
        this.reset();
        return true;
    }

    public boolean shouldRunLazyProbe(int probeInterval) {
        int interval = Math.max(1, probeInterval);
        if (++this.lazyProbeTicks < interval) {
            return false;
        }
        this.lazyProbeTicks = 0;
        return true;
    }

    public boolean recordLazyProbe(boolean didWork, boolean foundCandidate) {
        if (!didWork && !foundCandidate) {
            return false;
        }
        this.reset();
        return true;
    }
}
