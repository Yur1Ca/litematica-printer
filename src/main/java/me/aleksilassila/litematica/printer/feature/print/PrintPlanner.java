package me.aleksilassila.litematica.printer.feature.print;

import me.aleksilassila.litematica.printer.utils.FilterUtils;
import net.minecraft.world.level.block.state.BlockState;

/** Eligibility before building print workflows; it neither starts nor advances an interaction. */
public final class PrintPlanner {
    private PrintPlanner() {}

    public static boolean canInspect(boolean placementPending, boolean recentlyBroken, boolean activeWorkflow) {
        return !placementPending && (!recentlyBroken || activeWorkflow);
    }

    public static boolean skipRequiredState(BlockState required, boolean skipEnabled, String[] filters) {
        if (!skipEnabled) return false;
        for (String filter : filters) {
            if (FilterUtils.matchName(filter, required)) return true;
        }
        return false;
    }
}
