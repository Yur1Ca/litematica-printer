package me.aleksilassila.litematica.printer.handler.handlers.print;

public record PrintPlacementResult(
        boolean consumedEffectiveExecution,
        boolean skipIteration,
        TaskEvent taskEvent,
        int cooldownTicks
) {
    public static PrintPlacementResult failure(boolean consumedEffectiveExecution, boolean skipIteration) {
        return new PrintPlacementResult(consumedEffectiveExecution, skipIteration, TaskEvent.FAILURE, -1);
    }

    public static PrintPlacementResult deferred(boolean skipIteration) {
        return new PrintPlacementResult(false, skipIteration, TaskEvent.DEFERRED, -1);
    }

    public static PrintPlacementResult cancelled(boolean skipIteration) {
        return new PrintPlacementResult(false, skipIteration, TaskEvent.CANCELLED, -1);
    }

    public static PrintPlacementResult materialUnavailable(boolean skipIteration) {
        return new PrintPlacementResult(false, skipIteration, TaskEvent.MATERIAL_UNAVAILABLE, -1);
    }

    /** A neighbouring world update must make this target placeable before it is scanned again. */
    public static PrintPlacementResult worldBlocked() {
        return new PrintPlacementResult(false, false, TaskEvent.WORLD_BLOCKED, -1);
    }

    /** Only transient submission failures retry immediately; external waits need a wake-up. */
    public boolean shouldRetryTarget() {
        return this.taskEvent == TaskEvent.DEFERRED
                || this.taskEvent == TaskEvent.CANCELLED
                || this.taskEvent == TaskEvent.FAILURE;
    }

    public enum TaskEvent {
        SUCCESS,
        QUEUED,
        DEFERRED,
        CANCELLED,
        MATERIAL_UNAVAILABLE,
        WORLD_BLOCKED,
        FAILURE
    }
}
