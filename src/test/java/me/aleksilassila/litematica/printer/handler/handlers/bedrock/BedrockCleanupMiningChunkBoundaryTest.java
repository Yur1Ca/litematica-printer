//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real cleanup packets with a controlled client-cache port and native Level.hasChunk semantics. */
class BedrockCleanupMiningChunkBoundaryTest {
    private static final BlockPos PISTON = new BlockPos(0, 65, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void anUnloadedAirUpdateCannotAcknowledgeAnOwnedMiningStart() throws Exception {
        try (var f = new Fixture()) {
            f.start();
            f.world.loaded = false;
            f.assertUnknownCache();
            assertTrue(f.world.getBlockState(PISTON).isAir());
            assertTrue(f.world.blocks.get(PISTON).is(Blocks.PISTON));

            f.enginePlacer.onCleanupBlockUpdate(PISTON);

            assertTrue(f.enginePlacer.hasActiveCleanupMining(), "A read-only query cannot release an uncancelled START");
            assertTrue(f.enginePlacer.isCleanupPendingAt(PISTON), "Unloaded AIR is not a native removal ACK");
            assertEquals(List.of(START_DESTROY_BLOCK), f.actions());
            f.enginePlacer.advanceCleanupMining();
            f.assertCancelled();
        }
    }

    @Test void anUnknownChunkCancelsTheOwnedStartAndReleasesTheHandWithoutDroppingCleanupDebt() throws Exception {
        try (var f = new Fixture()) {
            f.start();
            f.world.loaded = false;
            f.assertUnknownCache();
            f.world.tick = 10;

            f.enginePlacer.advanceCleanupMining();

            f.assertCancelled();
            f.enginePlacer.advanceCleanupMining();
            assertEquals(List.of(START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK), f.actions(), "Cancellation is sent once");
            f.world.loaded = true;
            assertTrue(f.enginePlacer.breakCleanupBlock(PISTON, Direction.DOWN), "Reload permits a fresh owned attempt");
            assertEquals(List.of(START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, START_DESTROY_BLOCK), f.actions());
            assertTrue(f.enginePlacer.hasActiveCleanupMining());
            assertTrue(f.cleanup.contains(PISTON));
        }
    }

    @Test void anUnknownChunkCannotStartMiningEvenIfItsCachedStateStillContainsAPiston() throws Exception {
        try (var f = new Fixture()) {
            f.world.loaded = false;
            f.world.airWhenUnloaded = false; // A stale state is insufficient evidence that its chunk is available.
            f.assertUnknownCache();
            assertTrue(f.world.getBlockState(PISTON).is(Blocks.PISTON));

            assertFalse(f.enginePlacer.breakCleanupBlock(PISTON, Direction.DOWN));

            assertTrue(f.transport.packets.isEmpty(), "Unknown chunks cannot select materials or send mining markers");
            assertFalse(f.enginePlacer.hasActiveCleanupMining());
            assertFalse(f.enginePlacer.isCleanupPendingAt(PISTON));
        }
    }

    @Test void aLoadedAirUpdateStillAcknowledgesTheOwnedStartWithoutAnAbort() throws Exception {
        try (var f = new Fixture()) {
            f.start();
            f.world.blocks.remove(PISTON);

            f.enginePlacer.onCleanupBlockUpdate(PISTON);
            f.enginePlacer.advanceCleanupMining();

            assertFalse(f.enginePlacer.hasActiveCleanupMining());
            assertFalse(f.enginePlacer.isCleanupPendingAt(PISTON));
            assertEquals(List.of(START_DESTROY_BLOCK), f.actions(), "A genuine server AIR ACK needs no cancellation");
        }
    }

    private static class LoadedWorld extends BedrockPlacementRegressionTest.TestLevel {
        boolean loaded;
        boolean airWhenUnloaded;
        long tick;

        @Override boolean isChunkCached(int x, int z) { return loaded; }
        @Override public long getGameTime() { return tick; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return !loaded && airWhenUnloaded ? Blocks.VOID_AIR.defaultBlockState() : super.getBlockState(pos);
        }
    }

    private static class Fixture extends BedrockAdmissionPipelineTest.Fixture {
        final LoadedWorld world;
        final BedrockCleanupCoordinator cleanup;

        Fixture() throws Exception {
            cleanupWorld();
            world = allocate(LoadedWorld.class);
            world.blocks = level.blocks;
            world.loaded = true;
            world.airWhenUnloaded = true;
            set(world, Level.class, "dimension", Level.OVERWORLD);
            client.level = world;
            assertFalse(enginePlacer.prepareCriticalToolTick());
            enginePlacer.acknowledgeToolTick(transport, 0);
            assertFalse(enginePlacer.isInventorySettling());
            cleanup = new BedrockCleanupCoordinator(client, new CooldownUtils(), null, () -> false, enginePlacer);
            transport.packets.clear();
        }

        void start() {
            cleanup.add(PISTON, false);
            assertTrue(cleanup.contains(PISTON));
            assertTrue(enginePlacer.breakCleanupBlock(PISTON, Direction.DOWN));
            assertEquals(List.of(START_DESTROY_BLOCK), actions(), "The real native delta opens a slow owned attempt");
            assertTrue(enginePlacer.hasActiveCleanupMining());
            assertTrue(enginePlacer.isCleanupPendingAt(PISTON));
        }

        List<ServerboundPlayerActionPacket.Action> actions() {
            return transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).map(ServerboundPlayerActionPacket::getAction).toList();
        }

        void assertUnknownCache() {
            assertTrue(world.hasChunk(PISTON.getX() >> 4, PISTON.getZ() >> 4),
                    "ClientLevel.hasChunk is natively always true, even for an absent cached chunk");
            assertFalse(world.getChunkSource().hasChunk(PISTON.getX() >> 4, PISTON.getZ() >> 4),
                    "Only the client chunk source reports this controlled cache miss");
            if (world.airWhenUnloaded) assertTrue(world.getBlockState(PISTON).is(Blocks.VOID_AIR));
        }

        void assertCancelled() {
            assertEquals(List.of(START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK), actions(), "Unknown AIR requires a real cancellation");
            var abort = transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).toList().getLast();
            assertEquals(PISTON, abort.getPos());
            assertEquals(Direction.DOWN, abort.getDirection());
            assertFalse(enginePlacer.hasActiveCleanupMining(), "Cancellation must release the preparation hand gate");
            assertFalse(enginePlacer.isCleanupPendingAt(PISTON));
            assertTrue(cleanup.contains(PISTON), "The coordinator retains the footprint debt for a later loaded observation");
            assertTrue(world.blocks.get(PISTON).is(Blocks.PISTON), "Sending a cancellation cannot predict world removal");
        }
    }
}
//#endif
