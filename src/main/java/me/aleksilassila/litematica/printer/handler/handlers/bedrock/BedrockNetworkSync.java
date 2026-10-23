package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import static me.aleksilassila.litematica.printer.core.diagnostics.RuntimeTiming.Reason.*;

/** Tracks authoritative block updates without delaying the first critical packet bundle. */
final class BedrockNetworkSync {
    static final int DEFAULT_CONFIRMATION_TIMEOUT_TICKS = 16;
    static final int MAX_CONFIRMATION_TIMEOUT_TICKS = 56;
    static final int MAX_RETRY_DELAY_TICKS = 40;
    static final int MIN_CLEANUP_WATCH_TICKS = 40;
    static final int MAX_CLEANUP_WATCH_TICKS = 200;

    private final LongSupplier tickClock;
    private final IntSupplier rttExtraTicks;
    private final RuntimeTiming timing;
    private final Map<BlockPos, PendingAttempt> pending = new HashMap<>();
    private int adaptiveBackoffs;
    private int serverUpdateTimeouts;
    private String lastResult = "idle";

    BedrockNetworkSync(LongSupplier tickClock, IntSupplier rttExtraTicks) {
        this(tickClock, rttExtraTicks, RuntimeTiming.disabled());
    }

    BedrockNetworkSync(LongSupplier tickClock, IntSupplier rttExtraTicks, RuntimeTiming timing) {
        this.tickClock = tickClock;
        this.rttExtraTicks = rttExtraTicks;
        this.timing = timing;
    }

    void reset() {
        this.pending.clear();
        this.adaptiveBackoffs = 0;
        this.serverUpdateTimeouts = 0;
        this.lastResult = "idle";
    }

    void beginAttempt(BlockPos target, Set<BlockPos> footprint) {
        if (target == null) {
            return;
        }
        PendingAttempt previous = this.pending.put(
                target.immutable(), new PendingAttempt(footprint, this.tickClock.getAsLong()));
        this.finishObservation(previous, BEDROCK_DISCARDED_ATTEMPTS);
        this.timing.count("bedrock", BEDROCK_ATTEMPTS, 1);
        this.lastResult = "dispatched";
    }

    void onServerBlockUpdate(BlockPos updatedPos) {
        if (updatedPos == null) {
            return;
        }
        long tick = this.tickClock.getAsLong();
        for (PendingAttempt attempt : this.pending.values()) {
            if (!attempt.footprint.contains(updatedPos)) {
                continue;
            }
            if (attempt.firstServerUpdateTick < 0) {
                attempt.firstServerUpdateTick = tick;
                if (this.timing.enabled() && !attempt.outcomeRecorded) {
                    this.timing.count("bedrock", BEDROCK_FIRST_UPDATES, 1);
                    this.timing.count("bedrock", BEDROCK_FIRST_UPDATE_TICKS,
                            Math.max(0L, tick - attempt.submittedTick));
                }
            }
            if (attempt.timeoutRecorded) {
                attempt.lateUpdate = true;
                attempt.result = "late_update";
                this.lastResult = "late_update";
            }
        }
    }

    void recordTimeout(BlockPos target) {
        PendingAttempt attempt = this.pending.get(target);
        if (attempt == null || attempt.timeoutRecorded) {
            return;
        }
        attempt.timeoutRecorded = true;
        if (!attempt.outcomeRecorded) {
            this.timing.count("bedrock", BEDROCK_ATTEMPT_TIMEOUTS, 1);
        }
        attempt.result = "late_update";
        this.serverUpdateTimeouts++;
        this.lastResult = "late_update";
    }

    void recordRetry(BlockPos target) {
        PendingAttempt attempt = this.pending.get(target);
        if (attempt != null) {
            this.finishObservation(attempt, BEDROCK_RETRIES);
            attempt.result = "retry";
            this.lastResult = "retry";
        }
    }

    void confirmed(BlockPos target) {
        if (target != null) {
            PendingAttempt attempt = this.pending.remove(target);
            if (attempt != null) {
                this.finishObservation(attempt, BEDROCK_CONFIRMED_ATTEMPTS);
                attempt.result = "confirmed";
            }
            this.lastResult = "confirmed";
        }
    }

    void discarded(BlockPos target, boolean stuck) {
        if (target != null) {
            PendingAttempt attempt = this.pending.remove(target);
            if (attempt != null) {
                this.finishObservation(attempt, BEDROCK_DISCARDED_ATTEMPTS);
                attempt.result = stuck ? "stuck" : "retry";
            }
        }
        this.lastResult = stuck ? "stuck" : "retry";
    }

    private void finishObservation(PendingAttempt attempt, RuntimeTiming.Reason outcome) {
        if (attempt == null || attempt.outcomeRecorded) {
            return;
        }
        // Retain the existing pending-observation lifetime for late updates and the HUD.
        // Only diagnostic outcomes close here; they never gate an action or change a deadline.
        attempt.outcomeRecorded = true;
        if (!this.timing.enabled()) {
            return;
        }
        this.timing.count("bedrock", outcome, 1);
        this.timing.count("bedrock", BEDROCK_FINISHED_ATTEMPTS, 1);
        this.timing.count("bedrock", BEDROCK_ATTEMPT_TICKS,
                Math.max(0L, this.tickClock.getAsLong() - attempt.submittedTick));
    }

    int pendingCount() {
        return this.pending.size();
    }

    int adaptiveBackoffs() {
        return this.adaptiveBackoffs;
    }

    int serverUpdateTimeouts() {
        return this.serverUpdateTimeouts;
    }

    String lastResult() {
        return this.lastResult;
    }

    int confirmationTimeoutTicks() {
        int extra = this.currentRttExtraTicks();
        return confirmationTimeoutTicks(Configs.Bedrock.BEDROCK_MULTIPLAYER_ADAPTIVE.getBooleanValue(), extra);
    }

    int retryDelayTicks(int existingDelay) {
        int extra = this.currentRttExtraTicks();
        int delay = retryDelayTicks(
                Configs.Bedrock.BEDROCK_MULTIPLAYER_ADAPTIVE.getBooleanValue(),
                existingDelay,
                extra
        );
        if (extra > 0 && delay > Math.max(0, existingDelay)) {
            this.adaptiveBackoffs++;
        }
        return delay;
    }

    boolean isAdaptiveMultiplayer() {
        return this.currentRttExtraTicks() > 0;
    }

    int cleanupWatchTicks() {
        return cleanupWatchTicks(this.isAdaptiveMultiplayer(), this.confirmationTimeoutTicks());
    }

    static int confirmationTimeoutTicks(boolean adaptive, int extraRttTicks) {
        if (!adaptive || extraRttTicks <= 0) {
            return DEFAULT_CONFIRMATION_TIMEOUT_TICKS;
        }
        return Math.min(MAX_CONFIRMATION_TIMEOUT_TICKS,
                DEFAULT_CONFIRMATION_TIMEOUT_TICKS + extraRttTicks);
    }

    static int retryDelayTicks(boolean adaptive, int existingDelay, int extraRttTicks) {
        int base = Math.max(1, existingDelay);
        if (!adaptive || extraRttTicks <= 0) {
            return base;
        }
        return Math.min(MAX_RETRY_DELAY_TICKS, Math.max(4, Math.max(base, extraRttTicks)));
    }

    static int cleanupWatchTicks(boolean adaptive, int confirmationTimeoutTicks) {
        if (!adaptive || confirmationTimeoutTicks <= 0) {
            return 0;
        }
        return Math.min(MAX_CLEANUP_WATCH_TICKS,
                Math.max(MIN_CLEANUP_WATCH_TICKS, confirmationTimeoutTicks * 3));
    }

    private int currentRttExtraTicks() {
        if (!Configs.Bedrock.BEDROCK_MULTIPLAYER_ADAPTIVE.getBooleanValue()) {
            return 0;
        }
        return Math.max(0, this.rttExtraTicks.getAsInt());
    }

    private static final class PendingAttempt {
        private final Set<BlockPos> footprint;
        private final long submittedTick;
        private long firstServerUpdateTick = -1L;
        private boolean timeoutRecorded;
        private boolean outcomeRecorded;
        private boolean lateUpdate;
        private String result = "dispatched";

        private PendingAttempt(Set<BlockPos> footprint, long submittedTick) {
            this.footprint = new HashSet<>();
            if (footprint != null) {
                for (BlockPos pos : footprint) {
                    if (pos != null) {
                        this.footprint.add(pos.immutable());
                    }
                }
            }
            this.submittedTick = submittedTick;
        }
    }
}
