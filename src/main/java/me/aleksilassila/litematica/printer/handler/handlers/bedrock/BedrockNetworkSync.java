package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Tracks authoritative block updates without delaying the first critical packet bundle. */
final class BedrockNetworkSync {
    static final int DEFAULT_CONFIRMATION_TIMEOUT_TICKS = 16;
    static final int MAX_CONFIRMATION_TIMEOUT_TICKS = 56;
    static final int MAX_RETRY_DELAY_TICKS = 40;
    static final int MIN_CLEANUP_WATCH_TICKS = 40;
    static final int MAX_CLEANUP_WATCH_TICKS = 200;

    private final LongSupplier tickClock;
    private final IntSupplier rttExtraTicks;
    private final Map<BlockPos, PendingAttempt> pending = new HashMap<>();
    private int adaptiveBackoffs;
    private int serverUpdateTimeouts;
    private String lastResult = "idle";

    BedrockNetworkSync(LongSupplier tickClock, IntSupplier rttExtraTicks) {
        this.tickClock = tickClock;
        this.rttExtraTicks = rttExtraTicks;
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
        this.pending.put(target.immutable(), new PendingAttempt(footprint, this.tickClock.getAsLong()));
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
        attempt.result = "late_update";
        this.serverUpdateTimeouts++;
        this.lastResult = "late_update";
    }

    void recordRetry(BlockPos target) {
        PendingAttempt attempt = this.pending.get(target);
        if (attempt != null) {
            attempt.result = "retry";
            this.lastResult = "retry";
        }
    }

    void confirmed(BlockPos target) {
        if (target != null) {
            PendingAttempt attempt = this.pending.remove(target);
            if (attempt != null) {
                attempt.result = "confirmed";
            }
            this.lastResult = "confirmed";
        }
    }

    void discarded(BlockPos target, boolean stuck) {
        if (target != null) {
            PendingAttempt attempt = this.pending.remove(target);
            if (attempt != null) {
                attempt.result = stuck ? "stuck" : "retry";
            }
        }
        this.lastResult = stuck ? "stuck" : "retry";
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
