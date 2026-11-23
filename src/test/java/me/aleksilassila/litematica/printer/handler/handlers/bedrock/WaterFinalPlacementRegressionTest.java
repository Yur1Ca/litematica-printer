//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskAction;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
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

    @ParameterizedTest @ValueSource(ints = {1, 8})
    void regeneratedSourceAllowsFinalPlacementWhileWaitingForDispatchedIce(int level) throws Exception {
        try (var f = new WaterFixture()) {
            submitIceInFlow(f, level, false);
            observe(f, 1, Blocks.WATER.defaultBlockState());

            assertEquals(List.of(POS), f.workflows.readyTargetPositions());
            assertSame(PrintTaskBuildResult.PASS, f.build(POS));
            assertNotNull(f.workflows.createActionHandle(f.context(POS), new Action()));
            assertTrue(f.mining.requests.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void regeneratedSourceCannotOvertakeAnUnsentIceAction(boolean cancelled) throws Exception {
        try (var f = new WaterFixture()) {
            var queued = submitIceInFlow(f, 1, true);
            var queuedContext = f.context(POS);
            for (long tick : new long[]{1, 50}) {
                observe(f, tick, Blocks.WATER.defaultBlockState());
                assertFalse(f.workflows.hasReadyWorkflow());
                assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
            }
            if (cancelled) {
                queued.actionHandle().onCancelled(queuedContext, queued.action());
            } else {
                queued.actionHandle().onSuccess(queuedContext, queued.action());
            }
            observe(f, 51, Blocks.WATER.defaultBlockState());
            assertEquals(List.of(POS), f.workflows.readyTargetPositions());
            assertSame(PrintTaskBuildResult.PASS, f.build(POS));
            assertTrue(f.mining.requests.isEmpty());
        }
    }

    @Test void remainingFlowAndIceDoNotQualifyAsTheSourceForTheFinalBlock() throws Exception {
        try (var f = new WaterFixture()) {
            submitIceInFlow(f, 1, false);
            observe(f, 1, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 2));
            assertFalse(f.workflows.hasReadyWorkflow());
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
            assertTrue(f.mining.requests.isEmpty());

            observe(f, 2, Blocks.ICE.defaultBlockState());
            assertTrue(f.workflows.isBreakingTaskPos(POS));
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
            assertEquals(List.of(POS), f.mining.requests);
            observe(f, 3, Blocks.WATER.defaultBlockState());
            assertSame(PrintTaskBuildResult.PASS, f.build(POS));
        }
    }

    @Test void regeneratedSourceCompletesAWaterOnlyTargetImmediately() throws Exception {
        try (var f = new WaterFixture()) {
            f.schematic.states.put(POS, Blocks.WATER.defaultBlockState());
            submitIceInFlow(f, 1, false);
            observe(f, 1, Blocks.WATER.defaultBlockState());
            assertFalse(f.workflows.isActiveTaskPos(POS));
            assertTrue(f.mining.requests.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownIcePlacementInFlowRetriesOnTheSameDeadlineAsInAir(boolean flow) throws Exception {
        try (var f = new WaterFixture()) {
            if (flow) f.level.blocks.put(POS, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1));
            var ice = f.build(POS);
            assertTrue(ice.hasAction());
            f.workflows.onActionSuccess(ice.actionHandle(), f.context(POS), ice.action());
            f.now = 12;
            f.workflows.tick(f.level, f.schematic);
            assertFalse(f.workflows.hasReadyWorkflow());
            f.now = 13;
            f.workflows.tick(f.level, f.schematic);
            assertEquals(List.of(POS), f.workflows.readyTargetPositions());
            assertTrue(f.build(POS).hasAction());
        }
    }

    private static PrintTaskBuildResult submitIceInFlow(WaterFixture f, int level, boolean queued) {
        f.level.blocks.put(POS, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level));
        var ice = f.build(POS);
        assertTrue(ice.hasAction());
        assertEquals(Blocks.ICE.defaultBlockState(), ice.actionHandle().expectedBlockState(f.context(POS), ice.action()));
        if (queued) f.workflows.onActionQueued(ice.actionHandle(), f.context(POS), ice.action());
        else f.workflows.onActionSuccess(ice.actionHandle(), f.context(POS), ice.action());
        return ice;
    }

    private static void observe(WaterFixture f, long tick, BlockState state) {
        f.observe(POS, tick, state);
    }

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
