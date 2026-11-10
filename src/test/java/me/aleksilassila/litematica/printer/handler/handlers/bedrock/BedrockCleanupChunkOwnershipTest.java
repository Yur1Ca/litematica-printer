//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Native ClientLevel.hasChunk stays true while an unloaded cache exposes VOID_AIR. */
class BedrockCleanupChunkOwnershipTest {
    private static final BlockPos PISTON = new BlockPos(0, 65, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(strings = {"process", "pressure", "add"})
    void anOwnedResidueSurvivesUnloadedAirAndAnExpiredLateWatch(String entry) throws Exception {
        try (var f = new Fixture()) {
            f.cleanup.add(PISTON, false);
            assertTrue(f.cleanup.contains(PISTON));
            assertTrue(f.cleanup.samplePressure(f.world, pos -> false) > 0);
            int sent = f.transport.packets.size();

            f.world.loaded = false;
            assertTrue(f.world.hasChunk(PISTON.getX() >> 4, PISTON.getZ() >> 4),
                    "Native ClientLevel.hasChunk cannot establish cache presence");
            assertFalse(f.world.getChunkSource().hasChunk(PISTON.getX() >> 4, PISTON.getZ() >> 4),
                    "The inherited ChunkSource.hasChunk probes the cache with FULL,false");
            assertTrue(f.world.getBlockState(PISTON).is(Blocks.VOID_AIR));
            assertTrue(f.world.getBlockState(PISTON).isAir());
            assertTrue(f.world.blocks.get(PISTON).is(Blocks.PISTON),
                    "Only the client cache unloaded; the stored residue was never removed");
            f.world.tick = 201;
            f.cleanup.beginTick(); // Beyond MAX_CLEANUP_WATCH_TICKS; queue ownership must not depend on this watch.
            switch (entry) {
                case "process" -> f.cleanup.process(pos -> false, 1);
                case "pressure" -> assertEquals(0, f.cleanup.samplePressure(f.world, pos -> false));
                case "add" -> f.cleanup.add(PISTON, false);
                default -> throw new AssertionError(entry);
            }

            assertTrue(f.cleanup.contains(PISTON), entry + " cannot treat an unloaded AIR snapshot as confirmation");
            assertEquals(1, f.cleanup.size());
            assertEquals(0, f.cleanup.samplePressure(f.world, pos -> false), "Unloaded ownership creates no reachable pressure");
            f.cleanup.process(pos -> false, 4);
            assertEquals(sent, f.transport.packets.size(), "Unloaded ownership cannot send mining or inventory packets");
            assertTrue(f.mode.hits.isEmpty());

            f.world.tick = 1000;
            f.cleanup.beginTick(); // Expire even a watch refreshed by the add entry point.
            assertTrue(f.cleanup.contains(PISTON));
            f.world.loaded = true;
            assertTrue(f.world.hasChunk(PISTON.getX() >> 4, PISTON.getZ() >> 4));
            assertTrue(f.world.getChunkSource().hasChunk(PISTON.getX() >> 4, PISTON.getZ() >> 4));
            assertTrue(f.world.getBlockState(PISTON).is(Blocks.PISTON));
            assertTrue(f.cleanup.samplePressure(f.world, pos -> false) > 0);
            // A full chunk reload does not need a synthetic per-block update to rediscover this owner.
            f.cleanup.process(pos -> false, 1);

            var digs = f.transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).toList();
            assertEquals(1, digs.size(), "The existing owned cleanup resumes through its real sequenced packet path");
            assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, digs.getFirst().getAction());
            assertEquals(PISTON, digs.getFirst().getPos());
            assertTrue(f.world.getBlockState(PISTON).is(Blocks.PISTON), "Sending cleanup never predicts server removal");
            assertTrue(f.cleanup.contains(PISTON));
            assertTrue(f.mode.hits.isEmpty());

            f.world.blocks.remove(PISTON); // A genuinely loaded AIR observation can finally drain the work.
            f.cleanup.process(pos -> false, 1);
            assertFalse(f.cleanup.contains(PISTON));
            assertEquals(1, f.transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance).count());
        }
    }

    @Test void anUnloadedCallbackCannotReleaseAnOwnedResidueWhileItsWatchIsLive() throws Exception {
        try (var f = new Fixture()) {
            f.cleanup.add(PISTON, false);
            assertTrue(f.cleanup.contains(PISTON));
            int sent = f.transport.packets.size();
            f.world.loaded = false;
            f.world.tick = 1; // The original forty-tick watch is still live.

            f.cleanup.onServerBlockUpdate(PISTON);

            assertTrue(f.cleanup.contains(PISTON),
                    "A callback cannot turn the unloaded cache's AIR into authoritative removal");
            f.assertUnloadedOwnerResumesAfterWatchExpiry(sent);
        }
    }

    @Test void retirementCanFirstTransferOwnershipWhileTheChunkIsAlreadyUnloaded() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.cleanup.isEmpty());
            f.world.loaded = false;
            assertTrue(f.world.hasChunk(PISTON.getX() >> 4, PISTON.getZ() >> 4));
            assertFalse(f.world.getChunkSource().hasChunk(PISTON.getX() >> 4, PISTON.getZ() >> 4));
            assertTrue(f.world.getBlockState(PISTON).isAir());
            int sent = f.transport.packets.size();

            f.cleanup.enqueueAuthoritative(PISTON);

            assertTrue(f.cleanup.contains(PISTON),
                    "Retirement must retain its supplied footprint even before a loaded residue was ever observed");
            f.assertUnloadedOwnerResumesAfterWatchExpiry(sent);
        }
    }

    private static class LoadedWorld extends BedrockPlacementRegressionTest.TestLevel {
        boolean loaded;
        long tick;

        @Override boolean isChunkCached(int x, int z) { return loaded; }
        @Override public long getGameTime() { return tick; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return loaded ? super.getBlockState(pos) : Blocks.VOID_AIR.defaultBlockState();
        }
    }

    private static class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final LoadedWorld world;
        final BedrockCleanupCoordinator cleanup;

        Fixture() throws Exception {
            cleanupWorld(); // Existing fixture installs the real ToolSwitchService and confirms native tool readiness.
            world = allocate(LoadedWorld.class);
            world.blocks = level.blocks;
            world.loaded = true;
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            assertFalse(enginePlacer.prepareCriticalToolTick());
            enginePlacer.acknowledgeToolTick(transport, 0);
            assertFalse(enginePlacer.isInventorySettling());
            cleanup = new BedrockCleanupCoordinator(client, () -> false, enginePlacer);
            transport.packets.clear();
        }

        void assertUnloadedOwnerResumesAfterWatchExpiry(int sent) {
            world.tick = 1000;
            cleanup.beginTick();
            cleanup.onServerBlockUpdate(PISTON); // Expired watch processing must not discard existing queue ownership.
            assertTrue(cleanup.contains(PISTON));
            assertEquals(0, cleanup.samplePressure(world, pos -> false));
            cleanup.process(pos -> false, 4);
            assertTrue(cleanup.contains(PISTON));
            assertEquals(sent, transport.packets.size());
            assertTrue(mode.hits.isEmpty());

            world.loaded = true;
            assertTrue(world.getBlockState(PISTON).is(Blocks.PISTON));
            assertTrue(cleanup.samplePressure(world, pos -> false) > 0);
            cleanup.process(pos -> false, 1);
            var digs = transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).toList();
            assertEquals(1, digs.size(), "The retained owner resumes after a full chunk reload without BlockUpdated");
            assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, digs.getFirst().getAction());
            assertEquals(PISTON, digs.getFirst().getPos());
            assertTrue(cleanup.contains(PISTON));
            assertTrue(world.getBlockState(PISTON).is(Blocks.PISTON));
            assertTrue(mode.hits.isEmpty());
        }
    }
}
//#endif
