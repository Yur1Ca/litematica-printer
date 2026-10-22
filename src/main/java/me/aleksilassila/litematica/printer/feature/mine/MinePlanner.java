package me.aleksilassila.litematica.printer.feature.mine;

import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.BooleanSupplier;

/** Mining target decisions; tool sessions and destroy confirmation remain in their executors. */
public final class MinePlanner {
    private MinePlanner() {}

    public static boolean canInspect(boolean recentlyBroken, boolean pendingDestroy) {
        return !recentlyBroken && !pendingDestroy;
    }

    public static boolean isSolidTarget(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
    }

    public static boolean hardnessAllowed(boolean checkHardness, float hardness) {
        return !checkHardness || !(hardness < 0.0F);
    }

    public static boolean allowed(boolean reachable, BooleanSupplier permission, BooleanSupplier restriction) {
        return reachable && permission.getAsBoolean() && restriction.getAsBoolean();
    }
}
