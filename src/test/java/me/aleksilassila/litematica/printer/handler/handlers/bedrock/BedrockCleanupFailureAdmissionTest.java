//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real engine, admission, construction packets and cleanup attempts with controlled server replies. */
class BedrockCleanupFailureAdmissionTest {
    private static final BlockPos OLD = new BlockPos(0, 64, 0);
    private static final BlockPos RESIDUE = OLD.above();
    private static final BlockPos NEXT = OLD.east(8);
    private static final BlockPos NEW_RESIDUE = OLD.west(4).above();

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void failedCleanupYieldsTheOnlyCycleSlotWithoutGivingAwayItsFootprint() throws Exception {
        try (var f = new Fixture()) {
            f.failCleanupThroughFallback();
            f.plan(NEXT, Direction.UP);

            assertTrue(f.engine.submit(NEXT), "A failed cleanup must release capacity for an unrelated machine");
            f.engine.finishTick(true);

            assertTrue(f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast)
                    .anyMatch(packet -> packet.getHitResult().getBlockPos().equals(NEXT)),
                    "Admission and the throughput scheduler must both let the new machine actually start");
            assertTrue(f.cleanup.contains(RESIDUE));
            assertTrue(f.enginePlacer.isCleanupPendingAt(RESIDUE), "Delayed deletion still owns the old footprint");
            assertTrue(f.enginePlacer.isCleanupRetryPausedAt(RESIDUE));
            assertFalse(f.cleanup.isCycleClear(OLD), "Yielding a slot is not a successful cleanup acknowledgement");

            f.authoritativeAir(RESIDUE);

            assertFalse(f.cleanup.contains(RESIDUE));
            assertFalse(f.enginePlacer.isCleanupPendingAt(RESIDUE));
            assertTrue(f.targets.containsOwner(NEXT), "A late old cleanup reply cannot retire the new machine");
        }
    }

    @Test void lateAirForFailedCleanupDoesNotCancelANewerMiningOwner() throws Exception {
        try (var f = new Fixture()) {
            f.failCleanupThroughFallback();
            f.startNewCleanup();
            f.transport.packets.clear();

            f.authoritativeAir(RESIDUE);
            f.enginePlacer.advanceCleanupMining();

            assertFalse(f.cleanup.contains(RESIDUE));
            assertFalse(f.enginePlacer.isCleanupPendingAt(RESIDUE));
            assertTrue(f.enginePlacer.isCleanupPendingAt(NEW_RESIDUE));
            assertTrue(f.enginePlacer.hasActiveCleanupMining());
            assertEquals(0, f.countActions(NEW_RESIDUE, ABORT_DESTROY_BLOCK));
            f.advanceTo(42);
            assertEquals(1, f.countActions(NEW_RESIDUE, STOP_DESTROY_BLOCK),
                    "The newer owner continues to its own STOP after the old AIR acknowledgement");
        }
    }

    @Test void retryAfterCooldownWaitsForTheNewMiningOwnerInsteadOfAbortingIt() throws Exception {
        try (var f = new Fixture()) {
            f.failCleanupThroughFallback();
            f.startNewCleanup();
            long oldStarts = f.countActions(RESIDUE, START_DESTROY_BLOCK);

            f.advanceTo(54);

            assertEquals(oldStarts, f.countActions(RESIDUE, START_DESTROY_BLOCK),
                    "An expired retry delay cannot replace a newer START/STOP awaiting its reply");
            assertEquals(0, f.countActions(NEW_RESIDUE, ABORT_DESTROY_BLOCK));
            assertTrue(f.cleanup.contains(RESIDUE));
            f.authoritativeAir(NEW_RESIDUE);
            f.engine.finishTick(true);

            assertEquals(oldStarts + 1, f.countActions(RESIDUE, START_DESTROY_BLOCK),
                    "The retained failed residue can retry once the delay and newer hand owner have cleared");
            assertTrue(f.enginePlacer.isCleanupPendingAt(RESIDUE));
            assertTrue(f.cleanup.contains(RESIDUE));
            assertEquals(0, f.countActions(NEW_RESIDUE, ABORT_DESTROY_BLOCK));
        }
    }

    private static class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final BedrockCleanupCoordinator cleanup;

        Fixture() throws Exception {
            cleanupWorld();
            Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.setIntegerValue(1);
            Configs.Bedrock.BEDROCK_INTERVAL.setIntegerValue(1);
            cleanup = (BedrockCleanupCoordinator) get(engine, BedrockEngine.class, "cleanup");
            cleanup.enqueueCycle(OLD, Set.of(RESIDUE), false);
            transport.packets.clear();
        }

        @Override void advance(long tick) throws Exception {
            set(client.level, client.level.getClass(), "tick", tick);
            super.advance(tick);
            engine.finishTick(true);
        }

        void advanceTo(long tick) throws Exception {
            for (long next = runtime.currentTick() + 1; next <= tick; next++) advance(next);
        }

        @SuppressWarnings("unchecked")
        void failCleanupThroughFallback() throws Exception {
            advance(0);
            assertEquals(1, countActions(RESIDUE, START_DESTROY_BLOCK));
            // Enroll the retired cycle through the scheduler contract; Engine owns all later observations.
            var starts = (BedrockThroughputScheduler<BlockPos>) get(engine, BedrockEngine.class, "throughputScheduler");
            starts.started(OLD);
            advanceTo(38);
            assertEquals(2, countActions(RESIDUE, START_DESTROY_BLOCK), "One slow attempt then one native fallback");
            assertEquals(2, countActions(RESIDUE, STOP_DESTROY_BLOCK));
            assertTrue(cleanup.contains(RESIDUE));
            assertTrue(level.blocks.get(RESIDUE).is(Blocks.PISTON), "No server success was supplied by this fixture");
        }

        void startNewCleanup() throws Exception {
            advance(39);
            level.blocks.put(NEW_RESIDUE, Blocks.PISTON.defaultBlockState());
            cleanup.enqueueCycle(NEW_RESIDUE.below(), Set.of(NEW_RESIDUE), false);
            assertTrue(enginePlacer.breakCleanupBlock(NEW_RESIDUE, Direction.DOWN),
                    "The failed old cleanup cannot retain exclusive ownership of the mining hand");
            assertEquals(1, countActions(NEW_RESIDUE, START_DESTROY_BLOCK));
        }

        void authoritativeAir(BlockPos pos) {
            level.blocks.remove(pos);
            engine.onRuntimeEvent(new RuntimeEvent.BlockUpdated(pos.getX(), pos.getY(), pos.getZ()));
        }

        long countActions(BlockPos pos, ServerboundPlayerActionPacket.Action action) {
            return transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast)
                    .filter(packet -> packet.getPos().equals(pos) && packet.getAction() == action).count();
        }
    }
}
//#endif
