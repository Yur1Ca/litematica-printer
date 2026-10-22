package me.aleksilassila.litematica.printer.feature.fill;

import me.aleksilassila.litematica.printer.utils.FilterUtils;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.Predicate;

/** Fill-only target and support decisions. Inputs are observations, never execution capabilities. */
public final class FillPlanner {
    private static final Direction[] SIDE_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };
    private FillPlanner() {}

    public static boolean isTarget(BlockState state, String[] replaceableFilters) {
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) return true;
        for (String filter : replaceableFilters) {
            if (FilterUtils.matchName(filter, state)) return true;
        }
        return false;
    }

    public static Direction placementSide(boolean placeInAir, Direction configuredFacing,
                                          Direction playerDirection, Predicate<Direction> validSupport) {
        if (placeInAir) return configuredFacing != null ? configuredFacing : playerDirection;
        if (configuredFacing != null) return validSupport.test(configuredFacing) ? configuredFacing : null;
        for (Direction side : SIDE_ORDER) {
            if (validSupport.test(side)) return side;
        }
        return null;
    }
}
