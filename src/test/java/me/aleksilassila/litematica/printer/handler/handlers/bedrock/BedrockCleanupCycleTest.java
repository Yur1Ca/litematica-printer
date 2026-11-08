//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** A configured cycle owns reachable retirement work through its authoritative final removal. */
class BedrockCleanupCycleTest {
    private static final BlockPos OWNER = new BlockPos(0, 64, 0);
    private static final BlockPos PISTON = OWNER.above();
    private static final BlockPos SUPPORT = PISTON.east();

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void oneMachineKeepsOneSlotUntilItsLastResidueIsAuthoritativelyAir() throws Exception {
        try (var f = new Fixture()) {
            f.world.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            f.world.blocks.put(SUPPORT, Blocks.SLIME_BLOCK.defaultBlockState());
            int sent = f.transport.packets.size();

            f.cleanup.enqueueCycle(OWNER, Set.of(PISTON, SUPPORT), false);

            assertEquals(1, f.cleanup.reachableCycleCount());
            assertEquals(1, f.cleanup.verticalCycleCount());
            assertEquals(2, f.cleanup.size(), "Positions and whole cycles are different limits");
            f.air(PISTON);
            assertEquals(1, f.cleanup.reachableCycleCount(), "One remaining support still belongs to this cycle");
            f.air(SUPPORT);
            assertEquals(0, f.cleanup.reachableCycleCount());
            assertEquals(0, f.cleanup.verticalCycleCount());
            assertEquals(sent, f.transport.packets.size(), "Retirement and slot accounting cannot send confirmation packets");
        }
    }

    @Test void twoCyclesCanShareOneQueuedPositionAndDrainFromOneServerUpdate() throws Exception {
        try (var f = new Fixture()) {
            f.world.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            f.cleanup.enqueueCycle(OWNER, Set.of(PISTON), false);
            f.cleanup.enqueueCycle(OWNER.east(2), Set.of(PISTON), true);

            assertEquals(2, f.cleanup.reachableCycleCount(), "Each completed machine retains its own concurrency slot");
            assertEquals(1, f.cleanup.verticalCycleCount());
            assertEquals(1, f.cleanup.size(), "Shared material is queued once without mutual reservations");
            f.air(PISTON);
            assertEquals(0, f.cleanup.reachableCycleCount(), "One real AIR update releases both cycle references");
            assertTrue(f.cleanup.isEmpty());
        }
    }

    @Test void anAirOnlyWatchUsesNoSlotButALateOwnedPistonRestoresItsCycle() throws Exception {
        try (var f = new Fixture()) {
            f.cleanup.enqueueCycle(OWNER, Set.of(PISTON), false);
            assertEquals(0, f.cleanup.reachableCycleCount());
            assertTrue(f.cleanup.isEmpty());

            f.world.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            f.cleanup.onServerBlockUpdate(PISTON);

            assertEquals(1, f.cleanup.reachableCycleCount(), "A live late-placement watch preserves the cycle relationship");
            assertEquals(1, f.cleanup.verticalCycleCount());
            f.air(PISTON);
            assertEquals(0, f.cleanup.reachableCycleCount());
        }
    }

    @Test void anExpiredUnownedCycleCannotClaimLaterIndependentCleanupAtTheSameCoordinate() throws Exception {
        try (var f = new Fixture()) {
            f.cleanup.enqueueCycle(OWNER, Set.of(PISTON), false);
            f.world.tick = 1000;
            f.cleanup.beginTick();
            f.world.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            f.cleanup.add(PISTON, false);

            assertTrue(f.cleanup.contains(PISTON));
            assertEquals(0, f.cleanup.reachableCycleCount(),
                    "A cycle with neither queue ownership nor a live watch must have retired, not attach to a later block");
        }
    }

    @Test void anUnloadedCycleKeepsItsDebtAfterWatchExpiryWithoutUsingANearbySlot() throws Exception {
        try (var f = new Fixture()) {
            f.world.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            f.cleanup.enqueueCycle(OWNER, Set.of(PISTON), false);
            assertEquals(1, f.cleanup.reachableCycleCount());
            int sent = f.transport.packets.size();
            f.world.loaded = false;
            assertTrue(f.world.hasChunk(0, 0), "Native ClientLevel reports true even when its cache is absent");
            assertFalse(f.world.getChunkSource().hasChunk(0, 0));
            assertTrue(f.world.getBlockState(PISTON).is(Blocks.VOID_AIR));
            f.world.tick = 1000;
            f.cleanup.beginTick();
            f.cleanup.process(pos -> false, 16);

            assertTrue(f.cleanup.contains(PISTON));
            assertEquals(0, f.cleanup.reachableCycleCount());
            assertEquals(0, f.cleanup.verticalCycleCount());
            assertEquals(sent, f.transport.packets.size());
            f.world.loaded = true;
            assertEquals(1, f.cleanup.reachableCycleCount(), "A full chunk reload needs no fabricated BlockUpdated event");
            assertEquals(1, f.cleanup.verticalCycleCount());
        }
    }

    @Test void walkingAwayReleasesLocalCapacityWithoutForgettingTheRetiredMachine() throws Exception {
        try (var f = new Fixture()) {
            f.world.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            f.cleanup.enqueueCycle(OWNER, Set.of(PISTON), false);
            assertEquals(1, f.cleanup.reachableCycleCount());
            f.move(32.5D);
            assertFalse(BedrockEnvironment.canInteract(PISTON));
            assertEquals(0, f.cleanup.reachableCycleCount());
            assertTrue(f.cleanup.contains(PISTON));
            f.move(0.5D);
            assertEquals(1, f.cleanup.reachableCycleCount());
        }
    }

    @Test void repeatedRetirementForOneOwnerMergesItsFootprintsWithoutDuplicatingItsSlot() throws Exception {
        try (var f = new Fixture()) {
            f.world.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            f.world.blocks.put(SUPPORT, Blocks.SLIME_BLOCK.defaultBlockState());
            f.cleanup.enqueueCycle(OWNER, Set.of(PISTON), false);
            f.cleanup.enqueueCycle(OWNER, Set.of(SUPPORT), false);

            assertEquals(1, f.cleanup.reachableCycleCount());
            f.air(SUPPORT);
            assertEquals(1, f.cleanup.reachableCycleCount(), "A repeated handover cannot forget the older piston footprint");
            f.air(PISTON);
            assertEquals(0, f.cleanup.reachableCycleCount());
        }
    }

    @Test void directionCountsOnlyIncludeTheRetiredCyclesThatAreCurrentlyReachable() throws Exception {
        try (var f = new Fixture()) {
            BlockPos far = PISTON.east(32);
            f.world.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            f.world.blocks.put(far, Blocks.PISTON.defaultBlockState());
            f.cleanup.enqueueCycle(OWNER, Set.of(PISTON), false);
            f.cleanup.enqueueCycle(far.below(), Set.of(far), true);
            assertEquals(1, f.cleanup.reachableCycleCount());
            assertEquals(1, f.cleanup.verticalCycleCount());

            f.move(32.5D);

            assertEquals(1, f.cleanup.reachableCycleCount());
            assertEquals(0, f.cleanup.verticalCycleCount(), "The reachable side cycle cannot be presented as vertical work");
        }
    }

    @Test void aLongTrailOfOwnedResidueCannotSilentlyEvictItsFirstMachine() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 0; i < 513; i++) {
                BlockPos pos = i == 0 ? PISTON : PISTON.east(32 + i);
                f.world.blocks.put(pos, Blocks.PISTON.defaultBlockState());
                f.cleanup.enqueueCycle(pos.below(), Set.of(pos), false);
            }

            assertEquals(513, f.cleanup.size());
            assertTrue(f.cleanup.contains(PISTON), "A queue size policy must not throw away a real owned machine");
            assertEquals(1, f.cleanup.reachableCycleCount(), "Only the nearby machine consumes local capacity");
        }
    }

    @Test void resetRemovesCycleAndLateUpdateOwnershipTogether() throws Exception {
        try (var f = new Fixture()) {
            f.world.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
            f.cleanup.enqueueCycle(OWNER, Set.of(PISTON), false);
            assertEquals(1, f.cleanup.reachableCycleCount());
            f.cleanup.reset();
            f.cleanup.onServerBlockUpdate(PISTON);

            assertTrue(f.cleanup.isEmpty());
            assertEquals(0, f.cleanup.reachableCycleCount());
            assertEquals(0, f.cleanup.verticalCycleCount());
        }
    }

    private static class CachedWorld extends BedrockPlacementRegressionTest.TestLevel {
        boolean loaded;
        long tick;

        @Override boolean isChunkCached(int x, int z) { return loaded; }
        @Override public long getGameTime() { return tick; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return loaded ? super.getBlockState(pos) : Blocks.VOID_AIR.defaultBlockState();
        }
    }

    private static class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final CachedWorld world;
        final BedrockCleanupCoordinator cleanup;

        Fixture() throws Exception {
            world = allocate(CachedWorld.class);
            world.blocks = level.blocks;
            world.loaded = true;
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            cleanup = new BedrockCleanupCoordinator(client, new CooldownUtils());
        }

        void air(BlockPos pos) {
            world.blocks.remove(pos);
            cleanup.onServerBlockUpdate(pos);
        }

        void move(double x) throws Exception {
            set(client.player, Entity.class, "position", new Vec3(x, 64.0D, 0.5D));
        }
    }
}
//#endif
