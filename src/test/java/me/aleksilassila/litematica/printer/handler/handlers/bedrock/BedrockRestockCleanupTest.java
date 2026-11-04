package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import java.util.List;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises vanilla placement resolution when a sent hit outlives its target, plus cleanup handoff. */
class BedrockRestockCleanupTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void anUndispatchedMachineDoesNotClaimItsTargetCellForCleanup() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            assertFalse(f.target(TARGET, Direction.UP, f.placer).getCleanupPositions().contains(TARGET));
        }
    }

    @ParameterizedTest @EnumSource(Direction.class)
    void aLateReversePlacementAtTheRemovedAnchorRemainsOwnedCleanup(Direction priming) throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var target = f.target(TARGET, priming, f.placer);
            f.machine(priming);
            assertTrue(f.place(target.getPistonPos(), priming.getOpposite()));
            var sentHit = f.mode.hits.get(0);
            assertEquals(TARGET, sentHit.getBlockPos());
            target.recordNetworkAttempt();

            // The server has already broken the bedrock when it receives this older hit.
            // Vanilla can replace the now-air clicked position instead of placing beside it.
            f.level.blocks.remove(TARGET);
            assertEquals(TARGET, f.serverPlacementPosition(sentHit));
            f.level.blocks.put(TARGET, Blocks.PISTON.defaultBlockState());
            assertTrue(target.getCleanupPositions().contains(TARGET),
                    "A piston landing in the original bedrock cell must not escape this attempt's cleanup");
        }
    }

    @Test void aLatePistonDuringRestockingIsStillQueuedWhenTheRequestFinishes() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            var target = f.addTarget();
            target.recordNetworkAttempt();
            target.setHasTried(true);
            target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            f.level.blocks.remove(TARGET);
            f.settleTargets();
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            assertFalse(f.prepare());
            assertTrue(f.requests.isBusy());

            f.level.blocks.put(TARGET, Blocks.PISTON.defaultBlockState());
            cleanup.onServerBlockUpdate(TARGET);
            assertTrue(cleanup.contains(TARGET), "A retired target still owns its late placement during restocking");
            assertTrue(f.requests.isBusy(), "Observing cleanup must not cancel or steal the inventory request");
            for (int tick = 0; tick < 60; tick++) {
                f.requests.tick();
                assertFalse(f.prepare(), "Cleanup must wait while the provider owns the inventory");
            }
            assertEquals(1, f.sent.size());
            assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));

            f.put(40, Items.PISTON, 2);
            f.put(10, Items.REDSTONE_TORCH, 1);
            f.put(11, Items.SLIME_BLOCK, 1);
            f.requests.tick();
            assertTrue(f.prepare());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(cleanup.contains(TARGET));
            assertTrue(cleanup.samplePressure(f.level, pos -> false) > 0);
            assertTrue(f.mode.hits.isEmpty(), "Settling the request must not replay an old placement");
        }
    }

    @Test void missingStockMustNotPreventAlreadyOwnedResidueFromBeingCleaned() throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            f.level.blocks.put(TARGET, Blocks.PISTON.defaultBlockState());
            cleanup.add(TARGET, false);
            var emptySources = new MaterialRequestCoordinator(List.of(new InventoryProvider() {
                @Override public String id() { return "empty_source"; }
                @Override public MaterialReservation request(MaterialRequest request) {
                    return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
                }
            }));
            assertTrue(f.engine.prepareMaterials(emptySources),
                    "An unavailable consumable must not block the existing cleanup lane");
            assertFalse(f.engine.canSubmitInCurrentWindow());
            assertFalse(f.engine.canScanForTargets());
            assertFalse(emptySources.isBusy());
            cleanup.remove(TARGET);
            assertFalse(f.engine.prepareMaterials(emptySources), "No cleanup means no work without supplies");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cleanupNeverMinesSurvivingBedrockOrAnOrdinaryReplacement(boolean replacement) throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            var target = f.addTarget();
            target.recordNetworkAttempt();
            assertTrue(target.getCleanupPositions().contains(TARGET));
            var state = (replacement ? Blocks.STONE : Blocks.BEDROCK).defaultBlockState();
            f.level.blocks.put(TARGET, state);
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            cleanup.cleanupBlockOrQueue(TARGET, false, pos -> false);
            cleanup.process(pos -> false, 4);
            assertSame(state, f.level.getBlockState(TARGET));
            cleanup.onServerBlockUpdate(TARGET);
            assertFalse(cleanup.contains(TARGET));
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"work_switch_disabled", "execution_scope_changed", "client_scope_changed", "disconnect"})
    void resetDropsOriginalCellCleanupAndItsLateUpdates(String reason) throws Exception {
        try (var f = new BedrockRestockTest.Fixture()) {
            var target = f.addTarget();
            target.recordNetworkAttempt();
            target.setHasTried(true);
            target.setStatus(BedrockTarget.Status.NEEDS_WAITING);
            f.level.blocks.remove(TARGET);
            f.settleTargets();
            var cleanup = (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
            assertTrue(cleanup.contains(TARGET));
            assertFalse(f.prepare());

            f.runtime.reset(reason);
            f.level.blocks.put(TARGET, Blocks.PISTON.defaultBlockState());
            cleanup.onServerBlockUpdate(TARGET);
            f.deliver(Items.PISTON, 64);
            assertTrue(cleanup.isEmpty());
            assertFalse(f.engine.hasActiveWork());
            assertFalse(f.requests.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            assertTrue(f.mode.hits.isEmpty());
        }
    }
}
