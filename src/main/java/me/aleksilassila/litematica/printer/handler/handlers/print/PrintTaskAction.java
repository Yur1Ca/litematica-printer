package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;

public interface PrintTaskAction {
    default void onQueued(SchematicBlockContext context, Action action) {
    }

    void onSuccess(SchematicBlockContext context, Action action);

    void onFailure(SchematicBlockContext context, Action action);

<<<<<<< HEAD
    default void onCancelled(SchematicBlockContext context, Action action) {
    }

=======
>>>>>>> 98e8cb2f (feat: Initial commit - Add upstream litematica-printer repository)
    default boolean stopIterationAfterAction() {
        return true;
    }
}
