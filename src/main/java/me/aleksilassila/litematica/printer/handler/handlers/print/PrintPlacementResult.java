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

<<<<<<< HEAD
    public static PrintPlacementResult cancelled(boolean skipIteration) {
        return new PrintPlacementResult(false, skipIteration, TaskEvent.CANCELLED, -1);
    }

    public enum TaskEvent {
        SUCCESS,
        QUEUED,
        CANCELLED,
=======
    public enum TaskEvent {
        SUCCESS,
        QUEUED,
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
        FAILURE
    }
}
