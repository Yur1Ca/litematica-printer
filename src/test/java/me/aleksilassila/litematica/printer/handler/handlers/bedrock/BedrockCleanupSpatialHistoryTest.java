//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Distant owned machines must cost storage, not repeated local world probes. */
class BedrockCleanupSpatialHistoryTest {
    private static final BlockPos NEAR = new BlockPos(0, 65, 0);
    private static final BlockPos FAR = NEAR.east(64);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void thousandsOfDistantCyclesDoNotIncreaseNearbyWorldQueries(boolean distantLoaded) throws Exception {
        try (var f = new Fixture()) {
            f.residue(NEAR, false);
            f.world.tick = 1;
            f.serviceNearby();
            f.world.queries = 0;
            f.serviceNearby();
            int nearbyQueries = f.world.queries;

            for (int i = 0; i < 4096; i++) f.residue(FAR.east(i * 32), true);
            f.world.allLoaded = distantLoaded;
            f.world.queries = 0;
            f.serviceNearby();

            assertEquals(nearbyQueries, f.world.queries,
                    "Admission counts, pressure and cleanup must not probe a trail outside the interaction window");
            assertEquals(4097, f.cleanup.size(), "Reducing local work cannot discard distant ownership");
            f.world.tick = 1000;
            f.cleanup.beginTick();
            assertTrue(f.cleanup.contains(FAR), "An expired watch does not expire an unobserved residue");
            f.world.allLoaded = true;
            f.move(64.5D, 64.0D, 0.5D);
            assertEquals(1, f.cleanup.reachableCycleCount());
            assertEquals(0, f.cleanup.verticalCycleCount());
            f.cleanup.process(pos -> false, 1);
            var digs = f.transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).toList();
            assertFalse(digs.isEmpty(), "Returning must resume real cleanup without a synthetic block update");
            assertEquals(FAR, digs.getLast().getPos());
            f.world.blocks.remove(FAR);
            f.cleanup.process(pos -> false, 1);
            assertFalse(f.cleanup.contains(FAR));
            assertEquals(4096, f.cleanup.size());
        }
    }

    @Test void theFootprintMayBeNearbyEvenWhenItsCycleOwnerIsFarAway() throws Exception {
        try (var f = new Fixture()) {
            f.world.blocks.put(NEAR, Blocks.REDSTONE_TORCH.defaultBlockState());
            f.cleanup.enqueueCycle(FAR, Set.of(NEAR), false);
            f.cleanup.enqueueCycle(FAR.east(32), Set.of(NEAR), true);
            assertEquals(2, f.cleanup.reachableCycleCount());
            assertEquals(1, f.cleanup.verticalCycleCount());
            f.cleanup.transferOwnership(Set.of(NEAR));
            f.cleanup.onServerBlockUpdate(NEAR);
            assertEquals(0, f.cleanup.reachableCycleCount());
            assertFalse(f.cleanup.contains(NEAR), "Every shared owner and its late watch transfer together");
        }
    }

    @Test void sectionCandidatesStillUseExactSphereDistanceAcrossNegativeCoordinates() throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.WORK_RANGE.setIntegerValue(4);
            f.move(-0.5D, 64.0D, -0.5D);
            f.residue(new BlockPos(-4, 65, -1), false);
            f.residue(new BlockPos(-4, 65, -4), false);
            assertEquals(1, f.cleanup.reachableCycleCount(), "A section or cube corner is not inside the work sphere");
            assertEquals(1, f.cleanup.samplePressure(f.world, pos -> false));
        }
    }

    @Test void changingRangeAndShapeReopensStoredResidueWithoutMovement() throws Exception {
        try (var f = new Fixture()) {
            f.residue(new BlockPos(3, 64, 3), false);
            Configs.Core.WORK_RANGE.setIntegerValue(4);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            assertEquals(0, f.cleanup.reachableCycleCount());
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.CUBE);
            assertEquals(1, f.cleanup.reachableCycleCount());
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.OCTAHEDRON);
            assertEquals(0, f.cleanup.reachableCycleCount());
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            assertEquals(1, f.cleanup.reachableCycleCount());
        }
    }

    @Test void interactionBoundsIncludeTheBlocksNearFaceRatherThanOnlyItsCenter() throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(true);
            // ReadyPlayer's interaction attribute is one, plus the existing one-block grace.
            BlockPos reachableFace = new BlockPos(2, 65, 0);
            BlockPos outside = reachableFace.east();
            assertTrue(BedrockEnvironment.canInteract(reachableFace));
            assertFalse(BedrockEnvironment.canInteract(outside));
            f.residue(reachableFace, false);
            f.residue(outside, false);
            assertEquals(1, f.cleanup.reachableCycleCount());
            assertEquals(1, f.cleanup.samplePressure(f.world, pos -> false));
        }
    }

    @Test void refreshingAWatchDoesNotLetItsOldDeadlineForgetTheCycle() throws Exception {
        try (var f = new Fixture()) {
            f.cleanup.enqueueCycle(NEAR.below(), Set.of(NEAR), false);
            f.world.tick = 20;
            f.cleanup.enqueueAuthoritative(NEAR);
            f.world.tick = 41;
            f.cleanup.beginTick();
            f.world.blocks.put(NEAR, Blocks.PISTON.defaultBlockState());
            f.cleanup.onServerBlockUpdate(NEAR);
            assertEquals(1, f.cleanup.reachableCycleCount());
            f.world.blocks.remove(NEAR);
            f.cleanup.onServerBlockUpdate(NEAR);
            f.world.tick = 61;
            f.cleanup.beginTick();
            f.world.blocks.put(NEAR, Blocks.PISTON.defaultBlockState());
            f.cleanup.add(NEAR, false);
            assertEquals(0, f.cleanup.reachableCycleCount(), "Expired owners cannot attach to later independent cleanup");
        }
    }

    @Test void distantOwnedAirUpdatesReleaseHistoryAfterTheWatchExpires() throws Exception {
        try (var f = new Fixture()) {
            f.residue(FAR, false);
            f.world.tick = 1000;
            f.cleanup.beginTick();
            assertTrue(f.cleanup.contains(FAR));
            f.world.blocks.remove(FAR);
            f.cleanup.onServerBlockUpdate(FAR);
            assertFalse(f.cleanup.contains(FAR), "A real loaded AIR update releases even distant owned residue");
            f.world.blocks.put(FAR, Blocks.PISTON.defaultBlockState());
            f.cleanup.onServerBlockUpdate(FAR);
            assertFalse(f.cleanup.contains(FAR), "An unrelated later block cannot restore expired ownership");
            f.move(64.5D, 64.0D, 0.5D);
            assertEquals(0, f.cleanup.reachableCycleCount());
        }
    }

    @Test void largeSparseRangeDoesNotProbeEveryEmptySection() throws Exception {
        try (var f = new Fixture()) {
            Configs.Core.WORK_RANGE.setIntegerValue(256);
            var queue = new BedrockCleanupQueue();
            var sections = new CountingSections();
            set(queue, BedrockCleanupQueue.class, "sections", sections);
            queue.add(NEAR);
            queue.add(FAR.east(1024));
            assertEquals(Set.of(NEAR), Set.copyOf(queue.nearby(f.client)));
            assertTrue(sections.lookups <= sections.size(), "A sparse large range must not visit all empty section coordinates");
        }
    }

    private static class CountingSections extends HashMap<BlockPos, Set<BlockPos>> {
        int lookups;
        @Override public Set<BlockPos> get(Object key) {
            lookups++;
            return super.get(key);
        }
    }

    private static class CountingWorld extends BedrockPlacementRegressionTest.TestLevel {
        boolean allLoaded;
        long tick;
        int queries;

        @Override boolean isChunkCached(int x, int z) {
            queries++;
            return allLoaded || x == 0 && z == 0;
        }
        @Override public long getGameTime() { return tick; }
        @Override public BlockState getBlockState(BlockPos pos) {
            queries++;
            return allLoaded || pos.getX() >> 4 == 0 && pos.getZ() >> 4 == 0
                    ? super.getBlockState(pos) : Blocks.VOID_AIR.defaultBlockState();
        }
    }

    private static class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final CountingWorld world;
        final BedrockCleanupCoordinator cleanup;

        Fixture() throws Exception {
            world = allocate(CountingWorld.class);
            world.blocks = level.blocks;
            world.allLoaded = true;
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            Configs.Core.WORK_RANGE.setIntegerValue(6);
            cleanup = new BedrockCleanupCoordinator(client, () -> false, enginePlacer);
            move(0.5D, 64.0D, 0.5D);
            transport.packets.clear();
        }

        void residue(BlockPos pos, boolean horizontal) {
            world.blocks.put(pos, Blocks.REDSTONE_TORCH.defaultBlockState());
            cleanup.enqueueCycle(pos.below(), Set.of(pos), horizontal);
        }

        void serviceNearby() {
            cleanup.beginTick();
            cleanup.transferOwnership(Set.of(NEAR.east(4)));
            assertEquals(1, cleanup.reachableCycleCount());
            assertEquals(1, cleanup.verticalCycleCount());
            assertEquals(1, cleanup.samplePressure(world, pos -> false));
            cleanup.process(pos -> true, 1);
        }

        void move(double x, double y, double z) throws Exception {
            set(client.player, Entity.class, "position", new Vec3(x, y, z));
            set(client.player, Entity.class, "blockPosition", new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        }
    }
}
//#endif
