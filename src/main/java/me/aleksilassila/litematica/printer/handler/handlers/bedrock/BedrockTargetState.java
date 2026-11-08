package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

/** Owns the per-attempt state that survives between target ticks. */
final class BedrockTargetState {
    private int tickTimes;
    private boolean hasTried;
    private int stuckTicks;
    private int executeTick = -1;
    private int initializeTick = -1;
    private boolean throughputConsumed;
    private BedrockTarget.Status status = BedrockTarget.Status.UNINITIALIZED;
    private long lastAdvancedTick = Long.MIN_VALUE;

    void beginTick(long now) {
        if (this.lastAdvancedTick == now) return;
        this.throughputConsumed = false;
        if (this.lastAdvancedTick != Long.MIN_VALUE && now > this.lastAdvancedTick) {
            this.tickTimes += (int) Math.min(Integer.MAX_VALUE - this.tickTimes, now - this.lastAdvancedTick);
        }
        this.lastAdvancedTick = now;
    }

    void resetAttempt() {
        this.tickTimes = 0;
        this.hasTried = false;
        this.stuckTicks = 0;
        this.executeTick = -1;
        this.initializeTick = -1;
    }

    int tickTimes() {
        return this.tickTimes;
    }

    boolean hasTried() {
        return this.hasTried;
    }

    void markDispatched() {
        this.hasTried = true;
        this.executeTick = this.tickTimes;
    }

    void setHasTried(boolean value) {
        this.hasTried = value;
    }

    int stuckTicks() {
        return this.stuckTicks;
    }

    void incrementStuckTicks() {
        this.stuckTicks++;
    }

    int executeTick() {
        return this.executeTick;
    }

    void setExecuteTick(int value) {
        this.executeTick = value;
    }

    int initializeTick() {
        return this.initializeTick;
    }

    void setInitializeTick(int value) {
        this.initializeTick = value;
    }

    BedrockTarget.Status status() {
        return this.status;
    }

    void setStatus(BedrockTarget.Status status) {
        this.status = status;
    }

    boolean consumedThroughput() {
        return this.throughputConsumed;
    }

    void markThroughputAction() {
        this.throughputConsumed = true;
    }
}
