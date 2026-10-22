package me.aleksilassila.litematica.printer.feature.fluid;

import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import java.util.Set;
import java.util.function.Predicate;

/** Fluid selection and replaceability are independent of ordinary fill rules. */
public final class FluidPlanner {
    private static final Direction[] SIDE_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };
    private FluidPlanner() {}

    public static boolean isTarget(FluidState fluid, Set<? extends Fluid> selected, boolean includeFlowing) {
        return selected.contains(fluid.getType()) && (includeFlowing || fluid.isSource());
    }

    public static boolean isReady(boolean replaceable, boolean selectedFluid, boolean placeInAir, boolean hasSupport) {
        return replaceable && selectedFluid && (placeInAir || hasSupport);
    }

    public static Direction placementSide(Predicate<Direction> validSupport) {
        for (Direction side : SIDE_ORDER) {
            if (validSupport.test(side)) return side;
        }
        return null;
    }
}
