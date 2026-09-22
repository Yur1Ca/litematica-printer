package me.aleksilassila.litematica.printer.integration.quickshulker;

/** Timing rules shared by the Easy Place hook and the hidden container screen. */
public final class QuickShulkerInvocationPolicy {
    private QuickShulkerInvocationPolicy() {
    }

    public static boolean allowsPickBlock(boolean instabuild, boolean spectator) {
        return !instabuild && !spectator;
    }

    public static boolean shouldSuppressScreen(int pendingTokens, boolean containerScreen) {
        return pendingTokens > 0 && containerScreen;
    }

    public static int consumeScreenToken(int pendingTokens) {
        return Math.max(0, pendingTokens - 1);
    }

}
