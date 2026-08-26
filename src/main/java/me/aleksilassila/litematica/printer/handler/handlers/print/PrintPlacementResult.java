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

    /** Material waits need an inventory wake-up; all existing transient failures keep retrying. */
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
        FAILURE
    }
}
