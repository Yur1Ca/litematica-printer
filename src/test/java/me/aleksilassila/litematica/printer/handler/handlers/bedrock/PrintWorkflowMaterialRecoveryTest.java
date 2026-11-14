//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import java.util.List;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real water stages must recover when eligibility changes without an inventory count gain. */
class PrintWorkflowMaterialRecoveryTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void ordinaryMaterialWaitRechecksAfterTwentyTicksButNotOnNeighbourTraffic() throws Exception {
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            f.now = 1;
            assertTrue(f.build(TARGET).hasAction(), "The real water task must reach its ice placement stage");
            f.workflows.onMaterialUnavailable(f.context(TARGET));
            assertFalse(f.workflows.hasReadyWorkflow());
            f.now = 6;
            f.workflows.wake(TARGET.below());
            f.workflows.tick(f.level, f.schematic);
            assertFalse(f.workflows.hasReadyWorkflow(), "Neighbour packets cannot hot-wake a missing-material stage");
            assertSame(PrintTaskBuildResult.SKIP, f.build(TARGET));
            f.now = 21;
            f.workflows.tick(f.level, f.schematic);

            assertEquals(List.of(TARGET), f.workflows.readyTargetPositions(), "A material-bound workflow must get a bounded slow eligibility recheck");
            assertTrue(f.build(TARGET).hasAction(), "Recovery keeps the exact water stage instead of restarting discovery");
            assertTrue(f.mining.requests.isEmpty(), "Merely recovering material eligibility cannot start mining");
        }
    }

    @Test void pendingMaterialWaitCanResumeOnTheNextTick() throws Exception {
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            f.now = 1;
            assertTrue(f.build(TARGET).hasAction());
            f.workflows.onMaterialUnavailable(f.context(TARGET), true);
            assertFalse(f.workflows.hasReadyWorkflow());
            assertSame(PrintTaskBuildResult.SKIP, f.build(TARGET), "A PENDING stage must still pause within its current tick");
            f.now = 2;
            f.workflows.tick(f.level, f.schematic);

            assertEquals(List.of(TARGET), f.workflows.readyTargetPositions(), "Slot/provider confirmation may finish without increasing item totals");
            assertTrue(f.build(TARGET).hasAction());
        }
    }

    @Test void inventoryGainWakesMaterialWaitBeforeTheFallback() throws Exception {
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            f.now = 1;
            assertTrue(f.build(TARGET).hasAction());
            f.workflows.onMaterialUnavailable(f.context(TARGET));
            f.workflows.onInventoryAvailabilityChanged();
            f.workflows.tick(f.level, f.schematic);

            assertEquals(List.of(TARGET), f.workflows.readyTargetPositions());
            assertTrue(f.build(TARGET).hasAction());
        }
    }
}
//#endif
