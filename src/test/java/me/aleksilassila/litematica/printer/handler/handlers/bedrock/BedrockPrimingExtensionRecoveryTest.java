//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Real target observations keep cold initialization separate from a previously extended priming piston. */
class BedrockPrimingExtensionRecoveryTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void losingPowerAfterAnObservedExtensionDoesNotRestartTheColdInitializationWait(boolean actionTick) throws Exception {
        try (var f = new Fixture()) {
            int initialized = f.target.initializeTick();
            f.priming(true);
            assertEquals(BedrockTarget.Status.EXTENDED, f.observe(103, actionTick));
            assertFalse(f.target.hasTried());
            f.losePower();
            var world = new HashMap<>(f.level.blocks);

            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(104, actionTick),
                    "A server-confirmed extension has already completed this initialization's cold observation window");

            assertEquals(initialized, f.target.initializeTick());
            assertFalse(f.target.hasTried());
            assertEquals(world, f.level.blocks, "Classifying power loss cannot predict replacement blocks");
        }
    }

    @Test void aNewInitializationRestartsItsOwnColdObservationWindow() throws Exception {
        try (var f = new Fixture()) {
            f.priming(true);
            assertEquals(BedrockTarget.Status.EXTENDED, f.observe(103, false));
            f.losePower();
            f.observe(104, false);
            f.target.onInitialBatchDispatched(f.power);
            assertEquals(4, f.target.initializeTick());

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(105, false));
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(119, false));
            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(120, false));
        }
    }

    @Test void assigningTheSameInitializeTickStillStartsANewInitialization() throws Exception {
        try (var f = new Fixture()) {
            f.priming(true);
            assertEquals(BedrockTarget.Status.EXTENDED, f.observe(103, false));
            f.losePower();
            f.target.setInitializeTick(f.target.initializeTick());

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(104, false),
                    "A new initialization cannot inherit extension evidence merely because its tick value is unchanged");
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(117, false));
            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(118, false));
        }
    }

    @Test void resettingAnAttemptCannotReuseExtensionEvidenceWhenTheRelativeInitializeTickRepeats() throws Exception {
        try (var f = new Fixture()) {
            int previousInitializeTick = f.target.initializeTick();
            f.priming(true);
            assertEquals(BedrockTarget.Status.EXTENDED, f.observe(103, false));
            f.target.markDispatched();
            f.target.resetPostExecuteAttempt(BedrockTarget.Status.RECOVERING);
            f.level.blocks.remove(f.target.getPistonPos());
            f.level.blocks.remove(f.target.getHeadPos());
            f.level.blocks.remove(f.target.getTorchPos());
            assertEquals(BedrockTarget.Status.UNINITIALIZED, f.observe(103, false));
            assertEquals(BedrockTarget.Status.UNINITIALIZED, f.observe(105, false));
            f.target.onInitialBatchDispatched(f.power);
            f.priming(false);
            assertEquals(previousInitializeTick, f.target.initializeTick());

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(106, false),
                    "Reset restarts the relative clock, so equal initialize ticks belong to different attempts");
        }
    }

    @Test void aPrimingPistonThatHasNeverExtendedKeepsTheFullColdObservationWindow() throws Exception {
        try (var f = new Fixture()) {
            f.losePower();

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(103, false));
            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(117, false));
            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, f.observe(118, false));
            assertFalse(f.target.hasTried());
        }
    }

    @Test void aDispatchedCollapseStillWaitsFourTicksBeforeRecovery() throws Exception {
        try (var f = new Fixture()) {
            f.priming(true);
            assertEquals(BedrockTarget.Status.EXTENDED, f.observe(103, false));
            f.target.markDispatched();
            f.losePower();
            f.level.blocks.put(f.target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.DOWN));

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.settle(106));
            assertTrue(f.target.hasTried());
            assertEquals(BedrockTarget.Status.RECOVERING, f.settle(107));
            assertFalse(f.target.hasTried());
        }
    }

    @Test void anUnchangedDispatchedPrimingPistonStillWaitsSixteenTicksBeforeTimeoutRecovery() throws Exception {
        try (var f = new Fixture()) {
            f.priming(true);
            assertEquals(BedrockTarget.Status.EXTENDED, f.observe(103, false));
            f.target.markDispatched();

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.settle(118));
            assertTrue(f.target.hasTried());
            assertEquals(BedrockTarget.Status.RECOVERING, f.settle(119));
            assertFalse(f.target.hasTried());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"moving_base", "moving_head", "piston_head", "polluted_torch", "existing_torch"})
    void anObservedExtensionKeepsTheColdWaitWhileTransientResiduePollutionOrOwnedPowerRemains(String input) throws Exception {
        try (var f = new Fixture()) {
            f.priming(true);
            assertEquals(BedrockTarget.Status.EXTENDED, f.observe(103, false));
            f.losePower();
            switch (input) {
                case "moving_base" -> f.level.blocks.put(f.target.getPistonPos(), Blocks.MOVING_PISTON.defaultBlockState());
                case "moving_head" -> f.level.blocks.put(f.target.getHeadPos(), Blocks.MOVING_PISTON.defaultBlockState());
                case "piston_head" -> f.level.blocks.put(f.target.getHeadPos(), Blocks.PISTON_HEAD.defaultBlockState()
                        .setValue(PistonHeadBlock.FACING, Direction.UP));
                case "polluted_torch" -> {
                    f.level.blocks.put(f.target.getTorchPos(), Blocks.SLIME_BLOCK.defaultBlockState());
                    assertFalse(f.target.hasAnyTransientMachineResidue());
                    assertTrue(f.target.hasPollutedMachineState());
                }
                case "existing_torch" -> {
                    f.level.blocks.put(f.target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
                    assertTrue(f.target.hasOwnedTorchPowerSource());
                }
                default -> throw new AssertionError(input);
            }
            var world = new HashMap<>(f.level.blocks);

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, f.observe(104, false));

            assertEquals(world, f.level.blocks, "Existing animation, cleanup and powered-stall boundaries remain passive");
            assertFalse(f.target.hasTried());
        }
    }

    private static class Fixture extends BedrockPlacementRegressionTest.Fixture {
        final BedrockTarget target;
        final BedrockTorchPlacement power = new BedrockTorchPlacement(TARGET.east(), Direction.UP);

        Fixture() throws Exception {
            engine();
            level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            target = target();
            observe(100, false);
            observe(102, false);
            level.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            target.onInitialBatchDispatched(power);
            priming(false);
            mode.rejectMutations = true;
        }

        BedrockTarget.Status observe(long tick, boolean actionTick) throws Exception {
            tick(tick);
            return actionTick ? target.tick(false, false) : target.refreshStatusOnly();
        }

        BedrockTarget.Status settle(long tick) throws Exception {
            tick(tick);
            return target.settleDispatchedAttempt(false);
        }

        void priming(boolean extended) {
            level.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP).setValue(PistonBaseBlock.EXTENDED, extended));
            if (extended) level.blocks.put(target.getHeadPos(), Blocks.PISTON_HEAD.defaultBlockState()
                    .setValue(PistonHeadBlock.FACING, Direction.UP));
            else level.blocks.remove(target.getHeadPos());
        }

        void losePower() {
            level.blocks.remove(target.getTorchPos());
            priming(false);
        }

        @Override public void close() throws Exception {
            try {
                assertTrue(mode.hits.isEmpty(), "Observations cannot place a new machine");
                assertTrue(transport.packets.isEmpty(), "These lifecycle inputs cannot dispatch native packets");
            } finally { super.close(); }
        }
    }
}
//#endif
