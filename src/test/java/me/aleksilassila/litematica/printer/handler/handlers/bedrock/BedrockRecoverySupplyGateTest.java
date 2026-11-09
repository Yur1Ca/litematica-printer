package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exhausted supplies may pause construction, but must not strand a failed machine. */
class BedrockRecoverySupplyGateTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void unavailableStockAllowsOwnedRecoveryToContinueWithoutAGlobalCleanupEntry() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            addRecovery(f);
            var cleanup = (BedrockCleanupCoordinator) BedrockPlacementRegressionTest.get(
                    f.engine, BedrockEngine.class, "cleanup");
            assertTrue(cleanup.isEmpty());

            assertTrue(f.engine.prepareMaterials(emptySources()),
                    "An unavailable replacement piston must still permit removal of the old reverse machine");

            assertFalse(f.engine.canSubmitInCurrentWindow());
            assertFalse(f.engine.canScanForTargets());
        }
    }

    @Test void supplyDrainCleansRecoveryButNeverInitializesOrRepowersItsSuccessor() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            var target = addRecovery(f);
            f.mode.rejectMutations = true;

            f.settleTargets();

            assertEquals(1, target.cleanupCalls,
                    "RECOVERING must participate in the cleanup lane while preparation is disabled");
            f.tick(1);
            f.settleTargets();
            f.tick(2);
            f.settleTargets();
            assertEquals(BedrockTarget.Status.UNINITIALIZED, target.getStatus());
            assertEquals(1, target.cleanupCalls);
            assertTrue(f.mode.hits.isEmpty(), "Exhausted supplies permit cleanup only");
            assertFalse(target.hasTried());
        }
    }

    @Test void pendingContainerRequestStillBlocksRecoveryContinuation() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            var target = addRecovery(f);

            assertFalse(f.prepare());

            assertTrue(f.requests.isBusy());
            assertEquals(1, f.sent.size());
            assertEquals(0, target.cleanupCalls);
            assertFalse(f.prepare(), "A container owner retains its interaction and inventory boundary");
            assertEquals(0, target.cleanupCalls);
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    private static MaterialRequestCoordinator emptySources() {
        return new MaterialRequestCoordinator(List.of(new InventoryProvider() {
            @Override public String id() { return "empty_recovery_source"; }
            @Override public MaterialReservation request(MaterialRequest request) {
                return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
            }
        }));
    }

    private static RecoveryProbe addRecovery(BedrockRestockTest.Fixture f) throws Exception {
        f.level.blocks.put(TARGET.east(), Blocks.BEDROCK.defaultBlockState());
        var layout = f.target(TARGET, Direction.UP, f.enginePlacer).layout();
        var target = new RecoveryProbe(f, layout);
        target.setStatus(BedrockTarget.Status.RECOVERING);
        f.level.blocks.put(target.getPistonPos(), Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.DOWN));
        f.level.blocks.put(target.getHeadPos(), Blocks.PISTON_HEAD.defaultBlockState());
        f.level.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
        f.targets.add(target);
        return target;
    }

    /** Only the mining transport and its later world confirmation are simulated. */
    private static final class RecoveryProbe extends BedrockTarget {
        private final BedrockRestockTest.Fixture fixture;
        int cleanupCalls;

        RecoveryProbe(BedrockRestockTest.Fixture fixture, BedrockMachineLayout layout) {
            super(TARGET, fixture.level, layout, new BedrockTorchPlacement(TARGET.east(), Direction.UP), null,
                    new BedrockCriticalExecutor(fixture.enginePlacer), fixture.enginePlacer, null);
            this.fixture = fixture;
        }

        @Override public void cleanupRecoveryResidue() {
            assertFalse(this.hasTried());
            this.cleanupCalls++;
            this.fixture.level.blocks.remove(this.getTorchPos());
            this.fixture.level.blocks.remove(this.getHeadPos());
            this.fixture.level.blocks.remove(this.getPistonPos());
        }
    }
}
