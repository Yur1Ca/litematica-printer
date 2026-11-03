package me.aleksilassila.litematica.printer.integration.inventory;

import java.util.function.BooleanSupplier;

/** Authorizes only the synchronous schematic pick invoked by a user's Litematica hotkey. */
public final class PickBlockRequestScope {
    private static final ThreadLocal<Boolean> USER_PICK = new ThreadLocal<>();

    private PickBlockRequestScope() {
    }

    public static boolean isUserPick() {
        return Boolean.TRUE.equals(USER_PICK.get());
    }

    public static boolean withUserPick(BooleanSupplier operation) {
        Boolean previous = USER_PICK.get();
        USER_PICK.set(Boolean.TRUE);
        try {
            return operation.getAsBoolean();
        } finally {
            if (previous == null) USER_PICK.remove();
            else USER_PICK.set(previous);
        }
    }
}
