package me.aleksilassila.litematica.printer.handler.handlers.print;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallingPlacementTrackerTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void pendingPlacementBlocksOnlyItsColumnDependency() {
        FallingPlacementTracker tracker = new FallingPlacementTracker();
        BlockPos lower = new BlockPos(1, 10, 1);
        tracker.mark(lower, Blocks.ANVIL.defaultBlockState(), 100L);

        assertTrue(tracker.blocks(lower, 101L, true, (pos, state) -> false));
        assertTrue(tracker.blocks(lower.above(), 101L, true, (pos, state) -> false));
        assertFalse(tracker.blocks(lower.east(), 101L, true, (pos, state) -> false));
        assertFalse(tracker.blocks(lower.above(), 101L, false, (pos, state) -> false));
    }

    @Test
    void matchingWorldUpdateReleasesColumnImmediately() {
        FallingPlacementTracker tracker = new FallingPlacementTracker();
        BlockPos lower = new BlockPos(1, 10, 1);
        tracker.mark(lower, Blocks.ANVIL.defaultBlockState(), 100L);
        Set<BlockPos> settled = new HashSet<>();
        settled.add(lower);

        assertFalse(tracker.blocks(
                lower.above(),
                101L,
                true,
                (pos, state) -> settled.contains(pos)
        ));
    }

    @Test
    void sourcePositionVacatedAfterSendReleasesColumnOnNextTick() {
        FallingPlacementTracker tracker = new FallingPlacementTracker();
        BlockPos lower = new BlockPos(1, 10, 1);
        tracker.mark(
                lower,
                Blocks.ANVIL.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                100L
        );

        assertTrue(tracker.blocks(lower.above(), 100L, true, (pos, state) -> false));
        assertFalse(tracker.blocks(lower.above(), 101L, true, (pos, state) -> false));
    }

    @Test
    void supportedPlacementReleasesColumnOnNextTick() {
        FallingPlacementTracker tracker = new FallingPlacementTracker();
        BlockPos lower = new BlockPos(1, 10, 1);
        tracker.mark(
                lower,
                Blocks.SAND.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                100L
        );

        assertTrue(tracker.blocks(lower.above(), 100L, true, (pos, state) -> true));
        assertFalse(tracker.blocks(lower.above(), 101L, true, (pos, state) -> true));
    }
}
