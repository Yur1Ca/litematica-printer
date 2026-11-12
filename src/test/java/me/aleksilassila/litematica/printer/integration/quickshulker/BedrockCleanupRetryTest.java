//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacer;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static me.aleksilassila.litematica.printer.integration.quickshulker.BedrockToolEligibilityTest.set;
import static net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.*;
import static org.junit.jupiter.api.Assertions.*;

/** Captures real cleanup submissions while deliberately withholding server block removal. */
class BedrockCleanupRetryTest {
    private static final BlockPos FAILED = BlockPos.ZERO.above();
    private static final BlockPos OTHER = FAILED.east(2);

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void aFailedFallbackYieldsButCanLaterFinishTheSameResidue() throws Exception {
        try (var f = new Fixture()) {
            f.rejectedFallback();
            f.clock.tick = 33;
            f.advanceMining();

            assertFalse(f.active());
            assertEquals(ABORT_DESTROY_BLOCK, f.actions().getLast());
            assertTrue(f.pending(FAILED));
            assertTrue(f.paused(FAILED));
            assertFalse(f.clock.getBlockState(FAILED).isAir());
            int beforeQueries = f.actions().size();
            assertFalse(f.paused(OTHER));
            assertEquals(beforeQueries, f.actions().size(), "Cooldown queries cannot mutate native ownership");
            assertFalse(f.cleanup(OTHER), "The release tick gives construction a turn before another cleanup starts");
            f.clock.tick = 34;
            assertTrue(f.cleanup(OTHER), "An unrelated cleanup must be able to progress");

            f.clock.tick = 48;
            assertFalse(f.cleanup(FAILED));
            f.clock.tick = 49;
            assertTrue(f.paused(FAILED), "Expired retries do not reclaim capacity until their START is sent");
            assertTrue(f.cleanup(FAILED));
            assertFalse(f.paused(FAILED));
            assertTrue(f.active(), "The failed coordinate retains its native fallback, not an optimistic instant retry");
            assertEquals(START_DESTROY_BLOCK, f.actions().getLast());
            f.clock.tick = 50;
            f.advanceMining();
            assertEquals(STOP_DESTROY_BLOCK, f.actions().getLast());

            f.clock.blocks.remove(FAILED);
            f.update(FAILED);
            assertFalse(f.active());
            assertFalse(f.pending(FAILED));
            assertFalse(f.paused(FAILED));
        }
    }

    @Test void aRejectedAbortKeepsTheOwnerAndCannotBeOvertakenByAnotherStart() throws Exception {
        try (var f = new Fixture()) {
            f.rejectedFallback();
            f.clock.tick = 33;
            f.transport.reject = true;
            int before = f.actions().size();
            f.advanceMining();

            assertTrue(f.active());
            assertTrue(f.pending(FAILED));
            assertFalse(f.paused(FAILED), "Retry suspension begins only once cancellation is submitted");
            assertFalse(f.cleanup(OTHER));
            assertEquals(before, f.actions().size());

            f.transport.reject = false;
            f.clock.tick = 34;
            f.advanceMining();
            assertEquals(ABORT_DESTROY_BLOCK, f.actions().getLast());
            assertFalse(f.active());
            assertTrue(f.paused(FAILED));
            assertFalse(f.cleanup(OTHER));
            f.clock.tick = 35;
            assertTrue(f.cleanup(OTHER));
            var actions = f.actionPackets();
            assertEquals(FAILED, actions.get(actions.size() - 2).getPos());
            assertEquals(OTHER, actions.getLast().getPos());
            assertEquals(START_DESTROY_BLOCK, actions.getLast().getAction());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"state", "hand", "reset", "epoch"})
    void cancellationFailureCannotDiscardAnOwnerInTheSameWorld(String reason) throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.cleanup(FAILED));
            f.transport.reject = true;
            switch (reason) {
                case "state" -> f.clock.blocks.put(FAILED, Blocks.STONE.defaultBlockState());
                case "hand" -> f.player.inventory.setItem(0, new ItemStack(Items.STONE));
                case "reset" -> f.reset();
                case "epoch" -> set(RuntimeAccess.get(), PrinterRuntime.class, "epoch", RuntimeEpoch.INITIAL);
            }
            f.clock.tick = 3;
            f.advanceMining();
            assertTrue(f.active(), "The same connection still owns START until it accepts ABORT");
            assertFalse(f.cleanup(OTHER));
            assertEquals(List.of(START_DESTROY_BLOCK), f.actions());

            f.transport.reject = false;
            f.advanceMining();
            assertEquals(List.of(START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK), f.actions());
            assertFalse(f.active());
            assertFalse(f.pending(FAILED), "Invalidated context/target is discarded only after cancellation");
            assertFalse(f.clock.getBlockState(FAILED).isAir());
        }
    }

    @Test void leavingTheWorldDropsItsOldCancellationWithoutSendingIntoTheNewWorld() throws Exception {
        try (var f = new Fixture()) {
            assertTrue(f.cleanup(FAILED));
            f.transport.reject = true;
            f.reset();
            f.client.level = f.traceLevel;
            f.transport.reject = false;
            f.advanceMining();
            assertFalse(f.active());
            assertFalse(f.pending(FAILED));
            assertEquals(List.of(START_DESTROY_BLOCK), f.actions());
        }
    }

    @Test void normalSlowMiningKeepsItsEntireElapsedTimeBeforeConfirmationTimeoutStarts() throws Exception {
        try (var f = new Fixture()) {
            f.player.breakSpeed = 0.1;
            assertTrue(f.cleanup(FAILED));
            for (int tick = 1; tick <= 30; tick++) {
                f.clock.tick = tick;
                f.advanceMining();
            }
            assertEquals(List.of(START_DESTROY_BLOCK), f.actions());
            assertTrue(f.active());
            f.clock.tick = 40;
            f.advanceMining();
            assertEquals(List.of(START_DESTROY_BLOCK, STOP_DESTROY_BLOCK), f.actions());
            assertTrue(f.active());
            f.clock.blocks.remove(FAILED);
            f.update(FAILED);
            assertFalse(f.active());
            assertFalse(f.pending(FAILED));
        }
    }

    private static final class Fixture extends BedrockCleanupInventorySessionTest.CleanupFixture {
        Fixture() throws Exception { clock.blocks.put(OTHER, Blocks.PISTON.defaultBlockState()); }

        void rejectedFallback() throws Exception {
            player.breakSpeed = 6;
            assertTrue(cleanup(FAILED));
            clock.tick = 16;
            assertTrue(cleanup(FAILED), "The first unknown result keeps its immediate fallback opportunity");
            clock.tick = 17;
            advanceMining();
            assertTrue(active());
            assertEquals(List.of(START_DESTROY_BLOCK, START_DESTROY_BLOCK, STOP_DESTROY_BLOCK), actions());
        }

        boolean paused(BlockPos pos) throws Exception {
            var method = BedrockPlacer.class.getDeclaredMethod("isCleanupRetryPausedAt", BlockPos.class);
            method.setAccessible(true);
            return (boolean) method.invoke(placer, pos);
        }

        void update(BlockPos pos) throws Exception {
            var method = BedrockPlacer.class.getDeclaredMethod("onCleanupBlockUpdate", BlockPos.class);
            method.setAccessible(true);
            method.invoke(placer, pos);
        }

        void reset() throws Exception {
            var method = BedrockPlacer.class.getDeclaredMethod("resetInventorySync");
            method.setAccessible(true);
            method.invoke(placer);
        }

        List<ServerboundPlayerActionPacket> actionPackets() {
            return connection.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast).toList();
        }
    }
}
//#endif
