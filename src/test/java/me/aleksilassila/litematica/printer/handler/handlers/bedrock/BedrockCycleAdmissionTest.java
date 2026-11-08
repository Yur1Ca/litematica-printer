//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static org.junit.jupiter.api.Assertions.*;

class BedrockCycleAdmissionTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final BlockPos CLEANED_TARGET = FIRST.west(4);
    private static final List<BlockPos> NEXT = List.of(FIRST, FIRST.east(4), FIRST.east(8),
            FIRST.south(4), FIRST.south(4).east(4), FIRST.south(4).east(8));

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void oneUnfinishedCleanupCycleLeavesFiveOfTheSixAdmissionSlots() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.beginFence(); // The fence defers mutations, not read-only cycle accounting.
            var cleanup = cycle(f);
            f.advance(0);
            assertEquals(1, f.engine.getHudSnapshot().activeTargets());
            assertEquals(1, f.engine.getHudSnapshot().verticalActiveTargets());
            assertEquals(6, f.engine.getHudSnapshot().activeCap());
            for (BlockPos pos : NEXT.subList(0, 5)) {
                f.plan(pos, Direction.UP);
                assertTrue(f.engine.submit(pos));
            }
            f.plan(NEXT.get(5), Direction.UP);
            assertFalse(f.engine.submit(NEXT.get(5)), "A completed break still owns its cycle until reachable cleanup drains");
            assertEquals(6, f.engine.getHudSnapshot().activeTargets());

            f.level.blocks.remove(CLEANED_TARGET.above());
            cleanup.onServerBlockUpdate(CLEANED_TARGET.above());
            assertTrue(f.engine.submit(NEXT.get(5)), "Real AIR releases the final cycle slot without another delay");
            assertEquals(6, f.engine.getHudSnapshot().activeTargets());
        }
    }

    @Test void sideEnabledStillHasOnlyOneSharedSlotIncludingVerticalCleanup() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.beginFence();
            var cleanup = cycle(f);
            Configs.Bedrock.BEDROCK_ALLOW_SIDE.setBooleanValue(true);
            f.advance(0);
            f.plan(FIRST, Direction.UP);
            assertFalse(f.engine.submit(FIRST));
            assertEquals(1, f.engine.getHudSnapshot().activeCap());
            assertEquals(1, f.engine.getHudSnapshot().activeTargets());
            f.level.blocks.remove(CLEANED_TARGET.above());
            cleanup.onServerBlockUpdate(CLEANED_TARGET.above());
            assertTrue(f.engine.submit(FIRST));
        }
    }

    @Test void manyPartsOfOneCleanupCycleDoNotCreateASecondSubmissionThrottle() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.beginFence();
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            var parts = new java.util.LinkedHashSet<BlockPos>();
            for (int x = -4; x < 0; x++) for (int z = 0; z < 4; z++) {
                var pos = new BlockPos(x, 65, z);
                f.level.blocks.put(pos, Blocks.PISTON.defaultBlockState());
                parts.add(pos);
            }
            cleanup.enqueueCycle(CLEANED_TARGET, parts, false);
            f.advance(0);
            assertTrue(f.engine.getHudSnapshot().cleanupPressure() >= 16);
            assertEquals(1, f.engine.getHudSnapshot().activeTargets());
            assertEquals(6, f.engine.getHudSnapshot().submitCap(),
                    "Count complete cycles once instead of independently throttling again for each machine part");
        }
    }

    private static BedrockCleanupCoordinator cycle(BedrockAdmissionPipelineTest.Fixture f) throws Exception {
        var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
        f.level.blocks.put(CLEANED_TARGET.above(), Blocks.PISTON.defaultBlockState());
        cleanup.enqueueCycle(CLEANED_TARGET, Set.of(CLEANED_TARGET.above()), false);
        return cleanup;
    }
}
//#endif
