package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Attempt recovery must observe the original bundle and rebuild before trying again. */
class BedrockAttemptRecoveryTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void dispatchedAttemptDoesNotReplanAnUnavailableTorchSupport() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var target = dispatched(f);
            f.mode.rejectMutations = true;
            f.machine(Direction.UP);
            f.tick(1);

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, target.tick(false, false));
            assertTrue(target.hasTried());
            assertTrue(f.mode.hits.isEmpty(), "Observation must not create a replacement support or power source");
        }
    }

    @Test void observingTheSameRuntimeTickDoesNotAdvanceTheAttemptTwice() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = dispatched(f);
            f.machine(Direction.UP);
            f.tick(1);
            target.tick(false, false);
            int age = target.tickTimes();

            target.tick(false, false);

            assertEquals(age, target.tickTimes());
        }
    }

    @Test void statusOnlyObservationAdvancesItsDeadlineWithoutAnExecutionBudget() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = dispatched(f);
            f.machine(Direction.UP);
            f.tick(1);
            target.tick(false, false);
            int age = target.tickTimes();
            f.tick(9);

            target.refreshStatusOnly();

            assertEquals(age + 8, target.tickTimes());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void failedReversePistonEntersRecoveryWithoutBeingRepowered() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = dispatched(f);
            f.level.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.DOWN));
            for (int tick = 1; tick <= 5; tick++) {
                f.tick(tick);
                target.settleDispatchedAttempt(false);
            }

            assertEquals("RECOVERING", target.getStatus().name());
            assertFalse(target.hasTried(), "A failed event window is closed before a fresh attempt is prepared");
            assertTrue(f.mode.hits.isEmpty(), "Settling cannot repower the failed reverse piston");
        }
    }

    @Test void closingARecoveryRemovesTheOldPendingNetworkObservation() {
        var tick = new AtomicLong(100);
        var sync = new BedrockNetworkSync(tick::get);
        sync.beginAttempt(TARGET, Set.of(TARGET, TARGET.above()));
        tick.set(105);

        sync.recordRetry(TARGET);

        assertEquals(0, sync.pendingCount());
        assertEquals("retry", sync.lastResult());
    }

    @Test void aDeadlineWithoutAServerUpdateIsReportedAsTimeout() {
        var tick = new AtomicLong(100);
        var sync = new BedrockNetworkSync(tick::get);
        sync.beginAttempt(TARGET, Set.of(TARGET));
        tick.set(116);

        sync.recordTimeout(TARGET);

        assertEquals("timeout", sync.lastResult());
    }

    @Test void recoveryWaitsForTheOldBaseHeadAndPowerToDisappear() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = f.target();
            target.setStatus(BedrockTarget.Status.RECOVERING);
            f.level.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.DOWN));
            f.level.blocks.put(target.getHeadPos(), Blocks.PISTON_HEAD.defaultBlockState());
            f.level.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            assertEquals(BedrockTarget.Status.RECOVERING, target.refreshStatusOnly());

            f.level.blocks.remove(target.getPistonPos());
            f.tick(1);
            assertEquals(BedrockTarget.Status.RECOVERING, target.refreshStatusOnly());
            f.level.blocks.remove(target.getHeadPos());
            f.tick(2);
            assertEquals(BedrockTarget.Status.RECOVERING, target.refreshStatusOnly());
            f.level.blocks.remove(target.getTorchPos());
            f.tick(3);

            assertEquals(BedrockTarget.Status.UNINITIALIZED, target.refreshStatusOnly());
            assertTrue(f.mode.hits.isEmpty(), "State observation must not install or power the successor");
        }
    }

    @Test void onlyThePrimingFacingCanEnterTheOrdinaryRepowerPath() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
            var target = f.target();
            f.level.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.DOWN));
            assertEquals(BedrockTarget.Status.RECOVERING, target.refreshStatusOnly());

            target.setStatus(BedrockTarget.Status.UNINITIALIZED);
            f.level.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP));

            assertEquals(BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE, target.refreshStatusOnly());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void anotherInflightTargetBlocksSharedPowerButAllowsAnIndependentMachine() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var engine = f.engine();
            var registry = (BedrockTargetRegistry) BedrockPlacementRegressionTest.get(engine, BedrockEngine.class, "targets");
            var first = f.target();
            first.setHasTried(true);
            var second = f.target(TARGET.east(2), Direction.UP, f.placer);
            var machine = BedrockPlacementRegressionTest.get(second, BedrockTarget.class, "machine");
            var shared = new BedrockTorchPlacement(TARGET.east(), Direction.UP);
            BedrockPlacementRegressionTest.set(machine, BedrockTargetMachine.class, "torchPlacement", shared);
            BedrockPlacementRegressionTest.set(machine, BedrockTargetMachine.class, "torchSupportPos", shared.getSupportPos());
            registry.add(first);
            registry.add(second);
            f.mode.rejectMutations = true;

            assertFalse(second.canMutatePower());
            assertFalse(second.tryRepowerTorch());
            assertTrue(registry.isTorchPlacementReservedByOther(shared, second));
            assertFalse(registry.canReuseBlockingPosition(second, shared.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState()));
            var independent = f.target(TARGET.east(6), Direction.UP, f.placer);
            assertTrue(independent.canMutatePower(), "Independent targets retain parallel preparation");

            first.resetPostExecuteAttempt(BedrockTarget.Status.RECOVERING);

            assertTrue(second.canMutatePower());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    private static BedrockTarget dispatched(BedrockPlacementRegressionTest.Fixture f) throws Exception {
        var target = f.target();
        target.setHasTried(true);
        target.setExecuteTick(0);
        target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
        return target;
    }
}
