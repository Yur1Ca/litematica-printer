package me.aleksilassila.litematica.printer.feature;

import me.aleksilassila.litematica.printer.feature.fill.FillPlanner;
import me.aleksilassila.litematica.printer.feature.fluid.FluidPlanner;
import me.aleksilassila.litematica.printer.feature.print.PrintPlanner;
import me.aleksilassila.litematica.printer.feature.mine.MinePlanner;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FeaturePlannerTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void fillAcceptsOnlyAirLiquidOrItsOwnReplacementFilter() {
        assertTrue(FillPlanner.isTarget(Blocks.AIR.defaultBlockState(), new String[0]));
        assertTrue(FillPlanner.isTarget(Blocks.WATER.defaultBlockState(), new String[0]));
        assertFalse(FillPlanner.isTarget(Blocks.COBBLESTONE.defaultBlockState(), new String[0]));
        assertTrue(FillPlanner.isTarget(Blocks.COBBLESTONE.defaultBlockState(), new String[]{"minecraft:cobblestone"}));
        assertFalse(FillPlanner.isTarget(Blocks.STONE.defaultBlockState(), new String[]{"minecraft:cobblestone"}));
    }

    @Test void fillFacingDoesNotFallBackWhenConfiguredSupportIsMissing() {
        assertNull(FillPlanner.placementSide(false, Direction.EAST, Direction.SOUTH, side -> side == Direction.DOWN));
        assertEquals(Direction.DOWN, FillPlanner.placementSide(false, null, Direction.SOUTH, side -> true));
        assertEquals(Direction.SOUTH, FillPlanner.placementSide(true, null, Direction.SOUTH, side -> false));
        assertEquals(Direction.EAST, FillPlanner.placementSide(true, Direction.EAST, Direction.SOUTH, side -> false));
    }

    @Test void fluidUsesOwnFluidSelectionAndSourcePolicy() {
        var water = Fluids.WATER.defaultFluidState();
        var flowing = Fluids.FLOWING_WATER.defaultFluidState();
        var selected = Set.of(Fluids.WATER, Fluids.FLOWING_WATER);
        assertTrue(FluidPlanner.isTarget(water, selected, false));
        assertFalse(FluidPlanner.isTarget(flowing, selected, false));
        assertTrue(FluidPlanner.isTarget(flowing, selected, true));
        assertFalse(FluidPlanner.isTarget(water, Set.of(Fluids.LAVA), true));
        assertFalse(FluidPlanner.isReady(false, true, true, true));
        assertFalse(FluidPlanner.isReady(true, true, false, false));
        assertTrue(FluidPlanner.isReady(true, true, true, false));
        assertEquals(Direction.DOWN, FluidPlanner.placementSide(side -> true));
        assertNull(FluidPlanner.placementSide(side -> false));
    }

    @Test void printWaitsForItsOwnConfirmationButKeepsActiveBreakWorkflowEligible() {
        assertFalse(PrintPlanner.canInspect(true, false, false));
        assertFalse(PrintPlanner.canInspect(false, true, false));
        assertTrue(PrintPlanner.canInspect(false, true, true));
        assertTrue(PrintPlanner.canInspect(false, false, false));
        assertTrue(PrintPlanner.skipRequiredState(Blocks.STONE.defaultBlockState(), true, new String[]{"minecraft:stone"}));
        assertFalse(PrintPlanner.skipRequiredState(Blocks.STONE.defaultBlockState(), false, new String[]{"minecraft:stone"}));
        assertFalse(PrintPlanner.skipRequiredState(Blocks.STONE.defaultBlockState(), true, new String[]{"minecraft:glass"}));
    }

    @Test void mineRejectsPendingFluidAndHardnessBeforeExpensiveRestrictions() {
        assertFalse(MinePlanner.isSolidTarget(Blocks.AIR.defaultBlockState()));
        assertFalse(MinePlanner.isSolidTarget(Blocks.WATER.defaultBlockState()));
        assertTrue(MinePlanner.isSolidTarget(Blocks.STONE.defaultBlockState()));
        assertFalse(MinePlanner.canInspect(true, false));
        assertFalse(MinePlanner.canInspect(false, true));
        assertTrue(MinePlanner.canInspect(false, false));
        assertFalse(MinePlanner.hardnessAllowed(true, -1));
        assertTrue(MinePlanner.hardnessAllowed(false, -1));
        assertFalse(MinePlanner.allowed(false, () -> fail("out of reach"), () -> fail("out of reach")));
        assertFalse(MinePlanner.allowed(true, () -> false, () -> fail("restricted")));
        assertFalse(MinePlanner.allowed(true, () -> true, () -> false));
        assertTrue(MinePlanner.allowed(true, () -> true, () -> true));
    }
}
