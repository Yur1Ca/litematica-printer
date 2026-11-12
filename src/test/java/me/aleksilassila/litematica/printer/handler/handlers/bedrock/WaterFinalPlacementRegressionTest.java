//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskAction;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.bootstrap;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.WaterSupportRegressionTest.WaterFixture;
import static org.junit.jupiter.api.Assertions.*;

class WaterFinalPlacementRegressionTest {
    private static final BlockPos POS = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrapWater() { bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void dryLeavesAfterFinalPlacementDoNotRestartIceAndLeaveOtherTargetsReady(boolean queued) throws Exception {
        try (var f = new WaterFixture()) {
            submitLeaves(f, queued);
            BlockPos other = POS.east(5);
            f.schematic.states.put(other, f.required);
            f.level.blocks.put(other.below(), Blocks.STONE.defaultBlockState());
            assertTrue(f.build(other).hasAction());
            f.workflows.onActionDeferred(f.context(other));

            for (long tick : new long[]{1, 50, 100}) {
                f.now = tick;
                f.workflows.tick(f.level, f.schematic);
                assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
                assertTrue(f.mining.requests.isEmpty(), "A dry final block must not start another remove/ice cycle");
                assertFalse(f.workflows.isBreakingTaskPos(POS));
                assertEquals(List.of(other), f.workflows.readyTargetPositions());
            }
        }
    }

    @Test void delayedWaterloggingCompletesThePausedLeavesTarget() throws Exception {
        try (var f = new WaterFixture()) {
            submitLeaves(f, true);
            waitForFinalChange(f);

            f.level.blocks.put(POS, wetLeaves());
            f.workflows.wake(POS);
            f.workflows.tick(f.level, f.schematic);

            assertFalse(f.workflows.isActiveTaskPos(POS));
            assertTrue(f.mining.requests.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void replacingDryLeavesResumesFromTheNewWorldState(boolean water) throws Exception {
        try (var f = new WaterFixture()) {
            submitLeaves(f, true);
            waitForFinalChange(f);

            f.level.blocks.put(POS, water ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
            f.workflows.wake(POS);
            f.workflows.tick(f.level, f.schematic);

            assertEquals(List.of(POS), f.workflows.readyTargetPositions());
            var resumed = f.build(POS);
            if (water) assertSame(PrintTaskBuildResult.PASS, resumed);
            else assertTrue(resumed.hasAction());
            assertTrue(f.mining.requests.isEmpty());
        }
    }

    @Test void lateFailureFromTheFinalActionDoesNotRestartThePausedCycle() throws Exception {
        try (var f = new WaterFixture()) {
            PrintTaskAction handle = submitLeaves(f, true);
            waitForFinalChange(f);

            f.workflows.onActionFailure(handle, f.context(POS), new Action());
            f.now = 100;
            f.workflows.tick(f.level, f.schematic);
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));

            assertTrue(f.mining.requests.isEmpty());
            assertFalse(f.workflows.hasReadyWorkflow());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void finalCallbackBeforeConfirmationTimeoutPreservesTheDryFinalBlock(boolean cancelled) throws Exception {
        try (var f = new WaterFixture()) {
            f.schematic.states.put(POS, wetLeaves());
            f.level.blocks.put(POS, Blocks.WATER.defaultBlockState());
            var submittedContext = f.context(POS);
            PrintTaskAction handle = submitLeaves(f, true);
            f.now = 1;

            // Deferred completions retain the context from before placement; inspect the live block.
            if (cancelled) f.workflows.onActionCancelled(handle, submittedContext, new Action());
            else f.workflows.onActionFailure(handle, submittedContext, new Action());
            f.now = 2;
            f.workflows.tick(f.level, f.schematic);
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));

            assertTrue(f.mining.requests.isEmpty());
            assertFalse(f.workflows.hasReadyWorkflow());
            waitForFinalChange(f);
        }
    }

    private static PrintTaskAction submitLeaves(WaterFixture f, boolean queued) {
        f.schematic.states.put(POS, wetLeaves());
        f.level.blocks.put(POS, Blocks.WATER.defaultBlockState());
        assertSame(PrintTaskBuildResult.PASS, f.build(POS));
        Action action = new Action();
        PrintTaskAction handle = f.workflows.createActionHandle(f.context(POS), action);
        assertNotNull(handle);
        if (queued) f.workflows.onActionQueued(handle, f.context(POS), action);
        else f.workflows.onActionSuccess(handle, f.context(POS), action);
        f.level.blocks.put(POS, wetLeaves().setValue(BlockStateProperties.WATERLOGGED, false));
        return handle;
    }

    private static void waitForFinalChange(WaterFixture f) {
        f.now = 50;
        f.workflows.tick(f.level, f.schematic);
        assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
        assertTrue(f.mining.requests.isEmpty());
        assertFalse(f.workflows.hasReadyWorkflow());
    }

    private static BlockState wetLeaves() {
        return Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true)
                .setValue(BlockStateProperties.WATERLOGGED, true);
    }
}
//#endif
