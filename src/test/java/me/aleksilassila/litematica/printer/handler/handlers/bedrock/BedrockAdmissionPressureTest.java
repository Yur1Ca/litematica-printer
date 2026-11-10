//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static org.junit.jupiter.api.Assertions.*;

/** Real residue pressure and candidate rejection must not stall an unrelated funded initial USE. */
class BedrockAdmissionPressureTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final BlockPos SECOND = FIRST.east(4);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void oneBlockedCandidateAtHighPressureCannotCloseTheIndependentStartLane() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            var cleanup = cleanup(f);
            // MOVING_PISTON is real residue, but cannot create a slow native mining owner
            // that would independently and legitimately block construction in this test.
            for (int x = -3; x <= -2; x++) {
                for (int z = -2; z <= 2; z++) addResidue(f, cleanup, new BlockPos(x, 65, z));
            }
            addResidue(f, cleanup, FIRST.above());
            f.tick(0);
            f.engine.beginTick(false);
            assertTrue(f.engine.getHudSnapshot().cleanupPressure() >= 20);
            assertEquals(0, f.engine.getHudSnapshot().activeTargets());
            assertTrue(f.engine.canScanForTargets());
            f.plan(FIRST, Direction.UP);
            f.passInitialExposureDeferral(FIRST);

            assertFalse(f.engine.submit(FIRST));
            assertEquals("pending_cleanup", f.engine.getHudSnapshot().lastReason());
            assertTrue(cleanup.contains(FIRST.above()));
            assertEquals(1, f.engine.getHudSnapshot().rejectedThisTick());
            f.plan(SECOND, Direction.UP);
            assertTrue(f.engine.submit(SECOND),
                    "One locally blocked footprint cannot spend an independent owner's remaining start lane");
            assertEquals(1, f.engine.getHudSnapshot().activeTargets());
            int sent = f.transport.packets.size();

            f.engine.finishTick(true);

            assertEquals(List.of(SECOND.above()), pistonPlacements(f, sent),
                    "The independent reservation must reach a real OFF_HAND initial USE in this same tick");
            assertFalse(f.client.level.getBlockState(FIRST.above()).isAir(),
                    "Admission cannot assume that the first machine's real residue disappeared");
        }
    }

    @Test void repeatedRealRejectionsStillCloseThisTickAndReopenOnTheNextTick() throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            f.level.blocks.remove(FIRST.above());
            var cleanup = cleanup(f);
            var blocked = new ArrayList<BlockPos>();
            for (int x = -9; x <= 0; x += 3) {
                for (int z : new int[]{-9, -6}) {
                    BlockPos pos = new BlockPos(x, 64, z);
                    blocked.add(pos);
                    f.level.blocks.put(pos, Blocks.BEDROCK.defaultBlockState());
                    f.level.blocks.put(pos.east(), Blocks.BEDROCK.defaultBlockState());
                    addResidue(f, cleanup, pos.above());
                }
            }
            f.tick(0);
            f.engine.beginTick(false);
            assertEquals(16, f.engine.getHudSnapshot().cleanupPressure(),
                    "Eight real moving residues reach the counting threshold without triggering the old pressure-only rule");
            for (int index = 0; index < blocked.size(); index++) {
                assertTrue(f.engine.canScanForTargets(), "The bounded scan allows the next actual rejection");
                BlockPos pos = blocked.get(index);
                f.plan(pos, Direction.UP);
                f.passInitialExposureDeferral(pos);
                assertFalse(f.engine.submit(pos));
                assertEquals("pending_cleanup", f.engine.getHudSnapshot().lastReason());
                assertEquals(index + 1, f.engine.getHudSnapshot().rejectedThisTick());
            }
            assertFalse(f.engine.canScanForTargets());
            assertEquals("accept_backpressure", f.engine.getHudSnapshot().lastReason());
            f.plan(SECOND, Direction.UP);
            assertFalse(f.engine.submit(SECOND));
            assertEquals(0, f.engine.getHudSnapshot().activeTargets());
            int sent = f.transport.packets.size();
            f.engine.finishTick(true);
            assertTrue(pistonPlacements(f, sent).isEmpty());

            f.tick(1);
            f.engine.beginTick(false);
            assertTrue(f.engine.canScanForTargets());
            f.plan(SECOND, Direction.UP);
            assertTrue(f.engine.submit(SECOND));
            sent = f.transport.packets.size();
            f.engine.finishTick(true);
            assertEquals(List.of(SECOND.above()), pistonPlacements(f, sent));
        }
    }

    private static BedrockCleanupCoordinator cleanup(BedrockAdmissionPipelineTest.Fixture f) throws Exception {
        return (BedrockCleanupCoordinator) get(f.engine, BedrockEngine.class, "cleanup");
    }

    private static void addResidue(BedrockAdmissionPipelineTest.Fixture f,
                                   BedrockCleanupCoordinator cleanup, BlockPos pos) {
        f.level.blocks.put(pos, Blocks.MOVING_PISTON.defaultBlockState());
        assertTrue(BedrockEnvironment.canInteract(pos));
        cleanup.add(pos, false);
        assertTrue(cleanup.contains(pos));
    }

    private static List<BlockPos> pistonPlacements(BedrockAdmissionPipelineTest.Fixture f, int fromIndex) {
        return f.transport.packets.subList(fromIndex, f.transport.packets.size()).stream()
                .filter(ServerboundUseItemOnPacket.class::isInstance)
                .map(ServerboundUseItemOnPacket.class::cast)
                .filter(packet -> packet.getHand() == InteractionHand.OFF_HAND)
                .map(packet -> packet.getHitResult().getBlockPos().relative(packet.getHitResult().getDirection()))
                .toList();
    }
}
//#endif
