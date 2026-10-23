package me.aleksilassila.litematica.printer.runtime;

import java.util.Objects;

/**
 * Thin client bridge used only where Minecraft callbacks cannot receive the runtime instance.
 * Business state remains owned by {@link PrinterRuntime}; this holder never creates or resets it.
 */
public final class RuntimeAccess {
    private static volatile PrinterRuntime current;

    private RuntimeAccess() {
    }

    public static void install(PrinterRuntime runtime) {
        current = Objects.requireNonNull(runtime, "runtime");
    }

    public static PrinterRuntime get() {
        PrinterRuntime runtime = current;
        if (runtime == null) {
            throw new IllegalStateException("PrinterRuntime has not been installed");
        }
        return runtime;
    }

    /** Optional integrations may receive callbacks before the printer has been installed. */
    public static PrinterRuntime currentOrNull() {
        return current;
    }
}
