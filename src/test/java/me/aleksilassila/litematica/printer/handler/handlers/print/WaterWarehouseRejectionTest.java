//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.handler.handlers.bedrock.WaterSupportRegressionTest;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.WaterSupportRegressionTest.WaterFixture;
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

import static org.junit.jupiter.api.Assertions.*;

class WaterWarehouseRejectionTest {
    private static final BlockPos POS = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { WaterSupportRegressionTest.bootstrapWater(); }

    @ParameterizedTest @ValueSource(ints = {73, 0})
    void naturalSourceAfterIceRejectionPassesOnlyTheExactNonceToTheStair(int rejectedNonce) throws Exception {
        try (var f = new WaterFixture()) {
            f.setSchematicTarget(POS, wetStairs());
            f.observe(POS, 0, flow());
            var ice = sendIce(f);
            ice.onRejected(f.context(POS), rejectedNonce);

            for (int tick = 1; tick <= 5; tick++) {
                f.observe(POS, tick, flow());
                assertInstanceOf(WaterPrintTask.IcePlacement.class, f.build(POS).actionHandle());
                assertNull(f.workflows.createActionHandle(f.context(POS), new Action()));
                f.workflows.onActionDeferred(f.context(POS));
            }

            f.observe(POS, 6, Blocks.WATER.defaultBlockState());
            assertEquals(List.of(POS), f.workflows.readyTargetPositions());
            var stairs = finalAction(f);
            var context = f.context(POS);
            assertTrue(stairs.canSend(context));
            assertEquals(rejectedNonce, stairs.rejectedIceNonce(context));
            var action = new Action();
            assertEquals(wetStairs(), stairs.expectedBlockState(context, action));
            f.workflows.onActionSuccess(stairs, context, action);
            assertEquals(0, stairs.rejectedIceNonce(context));
            f.observe(POS, 7, wetStairs());
            assertFalse(f.workflows.isActiveTaskPos(POS));
        }
    }

    @Test void replacingTheSchematicTargetDoesNotPassTheRejectedIceNonceToTheNewBlock() throws Exception {
        try (var f = new WaterFixture()) {
            f.observe(POS, 0, flow());
            var ice = sendIce(f);
            ice.onRejected(f.context(POS), 73);
            f.setSchematicTarget(POS, wetStairs());
            f.observe(POS, 1, Blocks.WATER.defaultBlockState());
            assertEquals(0, finalAction(f).rejectedIceNonce(f.context(POS)));
        }
    }

    @Test void aLateRejectionForAnOldIceActionCannotReplaceTheCurrentNonce() throws Exception {
        try (var f = new WaterFixture()) {
            f.observe(POS, 0, flow());
            var oldIce = sendIce(f);
            f.observe(POS, 13, flow());
            var currentIce = sendIce(f);
            oldIce.onRejected(f.context(POS), 41);
            f.observe(POS, 14, flow());
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
            currentIce.onRejected(f.context(POS), 42);
            oldIce.onRejected(f.context(POS), 41);
            f.observe(POS, 15, Blocks.WATER.defaultBlockState());
            assertEquals(42, finalAction(f).rejectedIceNonce(f.context(POS)));
        }
    }

    @Test void rejectedStairRetriesFromSourceAndLateReceiptsDoNotRestartIt() throws Exception {
        try (var f = new WaterFixture()) {
            f.setSchematicTarget(POS, wetStairs());
            f.observe(POS, 0, Blocks.WATER.defaultBlockState());
            var original = finalAction(f);
            var submissionContext = f.context(POS);
            f.workflows.onActionSuccess(original, submissionContext, new Action());
            f.observe(POS, 1, Blocks.WATER.defaultBlockState());
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));

            original.onRejected(submissionContext);
            f.observe(POS, 2, Blocks.WATER.defaultBlockState());
            assertEquals(List.of(POS), f.workflows.readyTargetPositions());
            var retry = finalAction(f);
            assertEquals(0, retry.rejectedIceNonce(f.context(POS)));
            f.workflows.onActionSuccess(retry, f.context(POS), new Action());
            original.onRejected(submissionContext);
            f.observe(POS, 3, Blocks.WATER.defaultBlockState());
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));

            // The block packet arrives before the rejection; the scheduler has not seen it yet.
            f.setCurrentState(POS, wetStairs());
            retry.onRejected(submissionContext);
            f.observe(POS, 4, wetStairs());
            assertFalse(f.workflows.isActiveTaskPos(POS));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void finalActionRechecksTheSourceAndSchematicWhileWaitingToSend(boolean loseSource) throws Exception {
        try (var f = new WaterFixture()) {
            f.setSchematicTarget(POS, wetStairs());
            f.observe(POS, 0, Blocks.WATER.defaultBlockState());
            var stairs = finalAction(f);
            var submissionContext = f.context(POS);
            f.workflows.onActionQueued(stairs, submissionContext, new Action());
            assertTrue(stairs.canSend(submissionContext));
            if (loseSource) f.observe(POS, 1, flow());
            else f.setSchematicTarget(POS, Blocks.COBBLESTONE.defaultBlockState());
            assertFalse(stairs.canSend(submissionContext));
        }
    }

    private static WaterPrintTask.IcePlacement sendIce(WaterFixture f) {
        var built = f.build(POS);
        var ice = assertInstanceOf(WaterPrintTask.IcePlacement.class, built.actionHandle());
        f.workflows.onActionSuccess(ice, f.context(POS), built.action());
        return ice;
    }

    private static WaterPrintTask.FinalPlacement finalAction(WaterFixture f) {
        assertSame(PrintTaskBuildResult.PASS, f.build(POS));
        return assertInstanceOf(WaterPrintTask.FinalPlacement.class, f.workflows.createActionHandle(f.context(POS), new Action()));
    }

    private static BlockState flow() {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);
    }

    private static BlockState wetStairs() {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
    }
}
//#endif
